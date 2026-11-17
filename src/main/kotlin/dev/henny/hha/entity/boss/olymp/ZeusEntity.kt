package dev.henny.hha.entity.boss.olymp

import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.DuelBossEntity
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.entity.miniboss.GuardianEntity
import dev.henny.hha.entity.miniboss.MiniBossKit
import dev.henny.hha.entity.miniboss.ScriptedBolt
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.logic.boss.BossDefense
import dev.henny.hha.logic.boss.BossScaling
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha.logic.boss.ZeusScenes
import dev.henny.hha.logic.boss.timings.ZeusTimings as Z
import dev.henny.hha.world.olymp.OlympusLayout
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.damage.DamageSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Aktionen des Zeus für `kit.act` (PLAN 10.2). Werte gehen über die Leitung: nicht umnummerieren. */
object ZeusAction {
    const val NONE = 0
    const val THROW = 1
    const val BASH = 2
    const val SLAM = 3
    const val CALL = 4
    const val STEP = 5
    const val GAZE = 6
    const val SCALES = 7
    const val STAGGER = 8
    const val EMPTY = 9
    const val STUCK = 10
    const val GUARD_UP = 11
    const val RISE = 12
    const val PHASE = 13
    const val YIELD = 14
    /** Überarbeitung 3: In der Niederlage zeigt er nach unten (Szene `zeus.defeat`). Nur hinten anfügen. */
    const val POINT = 15
}

/**
 * Zeus, König des Himmels, Endkampf des Olymp (PLAN 5.5). Drei Leisten je 1100 effektiv als drei Phasen einer Leiste.
 * Neben Kronos der härteste Kampf der Mod: Er lehrt eine Mechanik, die man beherrschen muss, und belohnt sie mit
 * langen goldenen Fenstern.
 *
 * - **Erdung:** Sechs Kupferpfeiler auf Ring 21 ([OlympusLayout.PILLARS]). Ruft er einen Himmelsblitz auf jemanden, der
 *   am Boden näher als 3 an einem **frischen** Pfeiler steht, schlägt der Blitz in den Pfeiler (kein Schaden); der
 *   Pfeiler ist 20 s **geladen** (goldene Rune am Fuß). Kommt Zeus einem geladenen Pfeiler näher als 4 (er läuft,
 *   stößt oder tritt dorthin, oder sein Keraunos fliegt hinein), entlädt sich der Pfeiler in ihn: Taumeln, offen 90.
 *   Danach ist der Pfeiler verbraucht (oxidiertes Kupfer); zu Beginn von Phase II und III werden zwei erneuert. Ein
 *   ungenutzt verglühter Pfeiler wird wieder frisch. Am Kampfende ist alles wieder gewachstes Kupfer.
 * - **Luftregel:** Wer länger als 30 Ticks 3 Blöcke über dem Boden ist, bekommt einen eigenen Himmelsblitz (`SKY`).
 * - **Phase I** Grundangriffe nach Entfernung; **Phase II** dazu Gorgonenblick (alle 30 s) und eine Saal-Gefahr im
 *   Wechsel (Adler, Sturmwand); **Phase III** nur noch die drei Ketten und zweimal die Waage.
 * - Gold heißt nur: geladener Pfeiler, Schale der Waage, Lücke der Sturmwand, offenes Fenster.
 * - Echte Blitze sind kosmetisch und höchstens zwei je Sekunde ([lightning]).
 *
 * Auftritt, Phasenwechsel und Ende sind kurze Szenen in [ZeusScenes].
 */
class ZeusEntity(type: EntityType<out ZeusEntity>, world: World) : DuelBossEntity(type, world) {

    override val kit = MiniBossKit(this, "entity.hha.zeus", BossBar.Color.BLUE, Vfx.Style.THUNDER)
    override val fightName = "zeus"
    override val danger = Vfx.Style.THUNDER
    override val bossId = "zeus"
    override val effectiveHealth = Z.EFFECTIVE_HEALTH
    override val phases = listOf(Z.PHASE_2, Z.PHASE_3)
    override val antiAirAction get() = ZeusAction.GUARD_UP
    override val antiAirDamage get() = Z.COUNTER_DAMAGE
    override val antiAirExtraRadius get() = Z.COUNTER_RADIUS - BossDefense.COUNTER_RADIUS
    override val yieldAction = ZeusAction.YIELD
    override val entranceAction get() = ZeusAction.RISE
    override val music: SoundEvent get() = HhaSounds.THEME_ZEUS

    init {
        kit.stats(Z.HEALTH, Z.ARMOR, Z.ATTACK, Z.SPEED)
    }

    /** Auftritt, Phasenwechsel und Ende (Cutscenes über [Cine]). */
    internal val scenes = ZeusScenes(this)

    // ---------------------------------------------------------------------------------------------
    // Zustand
    // ---------------------------------------------------------------------------------------------

    private enum class RodState { FRESH, CHARGED, SPENT }

    /** Ein Blitzableiter-Pfeiler: [foot] ist der unterste Kupferblock (y = Boden). */
    private class Rod(val foot: BlockPos, val temporary: Boolean) {
        var state = RodState.FRESH
        var chargedUntil = 0L
        var nextRune = 0L

        /** Vorher dort stehende Blöcke (nur bei [temporary]): am Kampfende zurück. */
        val saved = ArrayList<Pair<BlockPos, BlockState>>()

        val center: Vec3d get() = Vec3d(foot.x + 0.5, foot.y.toDouble(), foot.z + 0.5)
        val tip: Vec3d get() = Vec3d(foot.x + 0.5, foot.y + OlympusLayout.PILLAR_HEIGHT + 1.0, foot.z + 0.5)
    }

    private val rods = ArrayList<Rod>()
    private var rodsReady = false
    private var rodsRestored = false

    /** Kampf auf dem Thronhof des Olymp (flacher Boden in [OlympusLayout.F]) statt in einer Test-Arena. */
    private var flatCourt = false

    /** Ticks in der laufenden Phase (ohne Szenen): Uhr für Blick und Saal-Gefahren. */
    private var phaseAge = 0
    private var gazeAt = Z.GAZE_FIRST
    private var hazardAt = Z.HAZARD_FIRST
    private var hazardTurn = 0

    /** Die eine laufende Saal-Gefahr (Adler, Sturmwand) samt Gruppe und Requisit. */
    private var hazard: Iterator<Unit>? = null
    private var hazardGroup: Telegraph.Fight.Group? = null
    private var hazardProp: ScriptedBolt? = null

    /** Läuft gerade Blick oder Waage? Dann beginnt keine Saal-Gefahr. */
    private var alone = false

    /** Die Waage läuft: kein Taumeln, keine Erdung. */
    private var scalesActive = false
    private var scalesDone = 0
    private var scalesDue = false

    /** Himmelsblitze bisher (der erste kommt vor Wurf und Donnerschritt, damit Phase I die Erdung lehrt). */
    private var callsDone = 0

    /** Eine Entladung hat die laufende Kette unterbrochen. */
    private var chainBroken = false

    /** Sieg erreicht: kein Kampf mehr, nur noch die Szene. */
    private var ending = false

    /** Nebenläufiges des Bosses (Ringe des Donnerschlags, Blitze der Luftregel); läuft nicht in Szenen. */
    private val jobs = ArrayList<Iterator<Unit>>()

    /** Luftregel je Spieler: Ticks in der Luft, frühester nächster Blitz, läuft gerade einer. */
    private val airTicks = HashMap<UUID, Int>()
    private val airReady = HashMap<UUID, Long>()
    private val airActive = HashSet<UUID>()
    private val airHinted = HashSet<UUID>()

    /** Eigene Ansage-Buchhaltung der Luftregel: zählt nicht gegen das Budget des Bosses (Briefing P10, Punkt 3). */
    private var airCallsState: Telegraph.Fight? = null
    private val airCalls: Telegraph.Fight
        get() = airCallsState ?: Telegraph.open(sw, "zeus_air", Vfx.Style.THUNDER) { fighters() }.also { airCallsState = it }

    /** Schaden je Spieler (wer bekommt Nikes Kranz). */
    private val damageBy = HashMap<UUID, Float>()

    /** Echte (kosmetische) Blitze der letzten Sekunde. */
    private val strikes = ArrayDeque<Long>()

    /** Wer Musik und Himmel des Kampfes bekommen hat (für Abgänge und spätes Betreten). */
    private val tuned = HashSet<UUID>()
    private var musicOn = false
    private var stormOn = false

    // ---------------------------------------------------------------------------------------------
    // Ablauf
    // ---------------------------------------------------------------------------------------------

    override fun basePause(): Int = when (phase) {
        0 -> Z.PAUSE
        1 -> Z.PAUSE_PHASE_2
        else -> Z.PAUSE_PHASE_3
    }

    /** In Szenen steht er auch außerhalb der Fläche (Thron im Norden); dort greift die Leine nicht. */
    override fun leash(): Double = if (scenes.playing || ending) 1000.0 else super.leash()

    /** Zeus hat ein eigenes Szenen-Drehbuch ([ZeusScenes]). */
    override val cinematic: Boolean get() = false

    override fun acceptsHit(world: ServerWorld, source: DamageSource): Boolean =
        !scenes.playing && !ending && super.acceptsHit(world, source)

    override fun entrance(): Sequence<Unit> = scenes.entrance()

    override fun onPhase(newPhase: Int): Sequence<Unit> = sequence {
        quiet()
        yieldAll(scenes.phase(newPhase))
        phaseAge = 0
        gazeAt = Z.GAZE_FIRST
        hazardAt = Z.HAZARD_FIRST
        lastMove = -1
    }

    override fun victory(): Sequence<Unit> = sequence {
        ending = true
        quiet()
        yieldAll(scenes.victory())
    }

    /** Höhepunkte vor dem nächsten Angriff: Phasenwechsel und Sieg (Basis), die Waage (III), der Blick (II). */
    override fun phaseEvent(): Sequence<Unit>? {
        super.phaseEvent()?.let { return it }
        if (ending) return null
        if (phase == 2 && scalesDue) {
            scalesDue = false
            val index = scalesDone++
            return solo(scales(index))
        }
        if (phase == 1 && hazard == null && phaseAge >= gazeAt) return solo(gorgonGaze(Z.GAZE_W, null, chained = false))
        return null
    }

    /** Ein Höhepunkt: Solange er läuft, beginnt keine Saal-Gefahr. */
    private fun solo(body: Sequence<Unit>): Sequence<Unit> = sequence {
        alone = true
        yieldAll(body)
        alone = false
    }

    override fun tickBody() {
        val arena = arena
        if (arena != null) {
            if (!rodsReady) setupRods(arena)
            syncViewers()
        }
        if (scenes.playing || ending) return
        phaseAge++
        tickRods()
        for (job in jobs.toList()) if (job.hasNext()) job.next() else jobs.remove(job)
        tickHazard()
        // Ohne Arena (`/summon`) keine Luftregel: Es gibt keinen Hof, über dem man „in der Luft“ wäre.
        if (arena != null) tickAir()
    }

    /** Alles Laufende beenden (vor Szenen und Sieg): Ansagen, Saal-Gefahr, Nebenläufiges, Fenster. */
    private fun quiet() {
        stopMoving()
        telegraph.reset()
        airCallsState?.reset()
        jobs.clear()
        airActive.clear()
        endHazard()
        close()
        alone = false
        scalesActive = false
        kit.act(0)
    }

    override fun remove(reason: RemovalReason) {
        if (!entityWorld.isClient) {
            scenes.abort()
            val players = tuned.mapNotNull { sw.getPlayerByUuid(it) as? ServerPlayerEntity }
            if (players.isNotEmpty()) {
                Cine.atmosphere(players, null, courtCenter(), 1)
                Cine.music(players, null)
            }
            tuned.clear()
            jobs.clear()
            endHazard()
            // Beim Entladen eines Chunks keine Blöcke tauschen; der nächste Kampf macht bestehende Pfeiler wieder frisch.
            if (reason.shouldDestroy()) restoreRods()
        }
        super.remove(reason)
    }

    // ---------------------------------------------------------------------------------------------
    // Angriffe: Wahl nach Entfernung, nie derselbe zweimal (Basis)
    // ---------------------------------------------------------------------------------------------

    override fun createMoves(): List<Move> = listOf(
        // Phase I und II: Grundangriffe (PLAN 5.5, Tabelle). Wurf und Donnerschritt erst nach dem ersten Himmelsblitz.
        Move(ZeusAction.THROW, Z.THROW_MIN_RANGE..Z.THROW_LENGTH.toDouble(), 2, { phase < 2 && callsDone > 0 }) { keraunosThrow(it) },
        Move(ZeusAction.BASH, 0.0..Z.BASH_RANGE, 2, { phase < 2 }) { aegisBash(it) },
        Move(ZeusAction.SLAM, 0.0..Z.SLAM_RANGE, 2, { phase < 2 }) { thunderSlam() },
        Move(ZeusAction.CALL, Z.CALL_MIN_RANGE..FAR, 2, { phase < 2 }) { skyCall() },
        Move(ZeusAction.STEP, Z.STEP_MIN_RANGE..FAR, 1, { phase < 2 && callsDone > 0 }) { thunderStep(it) },
        // Phase III: die drei Ketten, benannt nach den Kyklopen.
        Move(CHAIN_BRONTES, 0.0..Z.CHAIN_NEAR, 3, { phase >= 2 }) { chainBrontes() },
        Move(CHAIN_STEROPES, Z.CHAIN_FAR_MIN..FAR, 2, { phase >= 2 }) { chainSteropes(it) },
        Move(CHAIN_ARGES, 0.0..FAR, 2, { phase >= 2 }) { chainArges() },
    )

    /** Er läuft auf jemanden zu: Kommt er dabei einem geladenen Pfeiler zu nahe, entlädt sich der Pfeiler in ihn. */
    override fun approach(target: ServerPlayerEntity, distance: Double) {
        if (groundingAllowed()) {
            val rod = chargedRodNear(entityPos)
            if (rod != null) {
                interrupt(discharge(rod))
                return
            }
        }
        super.approach(target, distance)
    }

    /**
     * Keraunos-Wurf (fern): Linie 2,4 × 30, W 18 (`THROW`), S 24. Ein Pfeiler auf der Linie hält den Blitz auf; ist er
     * geladen, entlädt er sich in Zeus. Sonst steckt der Blitz 30 Ticks (Kreis 2,5, S 6 je 10 Ticks), und Zeus steht mit
     * leerer Hand **offen** (F 30). Danach kehrt der Blitz in die Hand zurück.
     */
    private fun keraunosThrow(target: ServerPlayerEntity, chain: Telegraph.Fight.Group? = null): Sequence<Unit> = sequence {
        val dir = dirTo(target.entityPos)
        hold(dir)
        val from = spot(entityPos)
        val blocker = rodOnLine(from, dir, Z.THROW_LENGTH.toDouble())
        val length = blocker?.let { (it.second - 0.7).coerceAtLeast(1.5) } ?: Z.THROW_LENGTH.toDouble()
        val to = spot(from.add(dir.multiply(length)))
        // Die Gruppe ohne eigene Mindestart: Die Kreise des steckenden Blitzes dauern 10 Ticks, nur der Wurf ist PROJECTILE.
        val group = chain ?: telegraph.boss()
        val line = group.line(from, to, Z.THROW_WIDTH, Z.THROW_W, Telegraph.Pace.PROJECTILE)
        telegraph.sound(HhaSounds.THUNDER_CALL, entityPos, 2.2f, 1.25f)
        kit.act(ZeusAction.THROW)
        val started = sw.time
        wait(line.ticks)
        for (player in line.hits(fighters())) strike(player, Z.THROW_DAMAGE)
        val hand = entityPos.add(0.0, height * 0.78, 0.0).add(dir.multiply(0.8))
        val stuckAt = Vec3d(to.x, to.y + 0.9, to.z)
        val bolt = prop(ScriptedBolt.spawn(sw, self, HermesBoltEntity.KERAUNOS, 1f, hand))
        // Spur des fliegenden Blitzes (endet am Client, sobald das Requisit weg ist).
        bolt?.entity?.let { BossFx.projectile(sw, Vfx.Style.THUNDER, it, BossFx.Look.KERAUNOS) }
        for (i in 1..Z.THROW_FLIGHT) {
            bolt?.moveTo(hand.lerp(stuckAt, i.toDouble() / Z.THROW_FLIGHT))
            yield(Unit)
        }
        bolt?.rest()
        telegraph.sound(HhaSounds.IMPACT, to, 2f, 1.3f)
        val rod = blocker?.first
        if (rod != null && rod.state == RodState.CHARGED && groundingAllowed()) {
            bolt?.discard()
            yieldAll(discharge(rod))
            return@sequence
        }
        // Der Blitz steckt: leere Hand, offen. Um ihn knistert ein stehender Bogen bis zum Rückruf.
        Vfx.flare(sw, Vfx.Style.THUNDER, stuckAt, 1.2f, 8)
        BossFx.play(sw, "zeus.stuck", Vfx.Style.THUNDER, to, a = Z.THROW_STUCK_RADIUS, life = Z.THROW_STUCK + Z.THROW_RETURN)
        open(Z.THROW_F, OPEN_FACTOR)
        var zone: Telegraph.Ring? = null
        for (t in 0 until Z.THROW_STUCK) {
            if (t % Z.THROW_STUCK_INTERVAL == 0) zone = group.ring(to, Z.THROW_STUCK_RADIUS, Z.THROW_STUCK_INTERVAL)
            if (sw.time - started == Z.THROW_END.toLong()) kit.act(ZeusAction.EMPTY)
            yield(Unit)
            val current = zone
            if (current != null && !current.running) {
                for (player in current.hits(fighters())) strike(player, Z.THROW_STUCK_DAMAGE)
                zone = null
            }
        }
        // Er ruft den Blitz zurück in die Hand.
        for (i in 1..Z.THROW_RETURN) {
            bolt?.moveTo(stuckAt.lerp(entityPos.add(0.0, height * 0.78, 0.0), i.toDouble() / Z.THROW_RETURN))
            yield(Unit)
        }
        bolt?.discard()
        kit.act(0)
    }

    /** Aigis-Stoß (nah): Kegel 70° halb, Radius 7, W 14 (`BASH`), S 20 und Rückstoß, F 16. Der Stoß trägt ihn nach vorn. */
    private fun aegisBash(target: ServerPlayerEntity, chain: Telegraph.Fight.Group? = null): Sequence<Unit> = sequence {
        val dir = dirTo(target.entityPos)
        hold(dir)
        val group = chain ?: telegraph.boss()
        val cone = group.cone(spot(entityPos), dir, Z.BASH_RADIUS, Z.BASH_HALF_ANGLE, Z.BASH_W)
        telegraph.sound(HhaSounds.SHIELD, entityPos, 2f, 0.8f)
        kit.act(ZeusAction.BASH)
        wait(cone.ticks)
        velocity = Vec3d(dir.x * Z.BASH_LUNGE, velocity.y, dir.z * Z.BASH_LUNGE)
        velocityDirty = true
        for (player in cone.hits(fighters())) {
            if (strike(player, Z.BASH_DAMAGE)) knock(player, player.entityPos.subtract(entityPos), Z.BASH_KNOCKBACK, 0.35)
        }
        Vfx.nova(sw, Vfx.Style.THUNDER, entityPos.add(dir.multiply(1.6)).add(0.0, 1.8, 0.0), 2.2f, 10)
        BossFx.kick(sw, entityPos, KICK_BASH, ticks = KICK_TICKS, radius = KICK_RADIUS)
        telegraph.sound(HhaSounds.IMPACT, entityPos, 2.4f, 0.9f)
        wait(Z.BASH_HIT_END - Z.BASH_HIT)
        stopMoving()
        if (groundingAllowed()) chargedRodNear(entityPos)?.let {
            yieldAll(discharge(it))
            return@sequence
        }
        if (chain == null) {
            wait(Z.BASH_F)
            kit.act(0)
        }
    }

    /**
     * Donnerschlag (nah): Kreis Radius 8, W 20 (`SLAM`), S 28; danach laufen zwei Ringe nach außen (Abstand 14 Ticks,
     * S 14, `LOW`: überspringen). Der Blitz steckt: **offen** F 36 (`STUCK`). In der Kette zieht er ihn sofort heraus.
     */
    private fun thunderSlam(chain: Telegraph.Fight.Group? = null): Sequence<Unit> = sequence {
        nearestFighter()?.let { hold(dirTo(it.entityPos)) }
        val center = spot(entityPos)
        val group = chain ?: telegraph.boss()
        val ring = group.ring(center, Z.SLAM_RADIUS, Z.SLAM_W)
        telegraph.sound(HhaSounds.CHARGE, entityPos, 2.6f, 0.6f)
        kit.act(ZeusAction.SLAM)
        wait(ring.ticks)
        for (player in ring.hits(fighters())) {
            if (strike(player, Z.SLAM_DAMAGE)) knock(player, player.entityPos.subtract(center), 1.0, 0.5)
        }
        val tip = center.add(facing().multiply(2.2))
        // Der eine Flächeneffekt: Risse und echte Trümmer um den Einschlag (statt der alten Stoßwelle).
        BossFx.play(sw, "zeus.slam", Vfx.Style.THUNDER, center, tip, a = Z.SLAM_RADIUS, life = SLAM_FX_LIFE, impact = true)
        skyBolt(tip)
        BossFx.kick(sw, center, KICK_SLAM, ticks = KICK_TICKS + 1, radius = KICK_RADIUS)
        telegraph.sound(HhaSounds.THUNDER, center, 3f, 0.9f)
        for (i in 0 until Z.SLAM_RINGS) jobs.add(expandingRing(center, i * Z.SLAM_RING_GAP, i).iterator())
        val travel = ceil((courtRadius() - Z.SLAM_RADIUS) / Z.SLAM_RING_SPEED).toInt()
        group.hold((Z.SLAM_RINGS - 1) * Z.SLAM_RING_GAP + travel + 1)
        if (chain == null) {
            kit.act(ZeusAction.STUCK)
            open(Z.SLAM_F, OPEN_FACTOR)
            wait(Z.SLAM_F)
            kit.act(0)
        } else {
            wait(4)
        }
    }

    /** Ein Ring läuft vom Einschlag nach außen; wer am Boden steht, wenn er durchläuft, bekommt S 14 (`LOW`). */
    private fun expandingRing(from: Vec3d, delay: Int, index: Int): Sequence<Unit> = sequence {
        wait(delay)
        val struck = HashSet<UUID>()
        var radius = Z.SLAM_RADIUS.toDouble()
        telegraph.sound(HhaSounds.FLOOD, from, 2.2f, 0.8f)
        val edge = courtRadius()
        while (radius < edge) {
            // Jeder Ring zeichnet sich in eigenen Ticks, damit sich die Bänder nicht gegenseitig verschlucken.
            if ((sw.time + index) % 2L == 0L) {
                Vfx.flood(sw, Vfx.Style.THUNDER, from, (radius + Z.SLAM_RING_DEPTH).toFloat(), (radius - Z.SLAM_RING_DEPTH).coerceAtLeast(0.0).toFloat(), 3)
            }
            for (player in fighters()) {
                val r = GuardianEntity.flat(player.entityPos, from)
                if (abs(r - radius) < Z.SLAM_RING_DEPTH && player.uuid !in struck &&
                    strike(player, Z.SLAM_RING_DAMAGE, BossDefense.Reach.LOW, courtFloor())
                ) {
                    struck.add(player.uuid)
                    Fx.launchPlayer(player, Vec3d(0.0, 0.5, 0.0))
                }
            }
            radius += Z.SLAM_RING_SPEED
            yield(Unit)
        }
    }

    /**
     * Himmelsblitz (fern): je Spieler ein Kreis Radius 3,5, W 22 (`CALL`), S 26, `SKY`, F 14; alle Kreise einer Welle
     * sind **eine** Gruppe. Steht der Gerufene (oder wer im Kreis steht) am Boden nah an einem frischen Pfeiler, schlägt
     * der Blitz in den Pfeiler. Mehrere Wellen ([waves], Abstand [gap]) für die Kette „Arges“.
     */
    private fun skyCall(
        chain: Telegraph.Fight.Group? = null, waves: Int = 1, gap: Int = 0, window: Int = Z.CALL_F,
    ): Sequence<Unit> = sequence {
        val group = chain ?: telegraph.boss()
        val pending = ArrayList<Pair<ServerPlayerEntity, Telegraph.Ring>>()
        // Bild des Himmelsblitzes: [SKY_LEAD] Ticks vor dem Treffer entschieden (Strahl oder Bogen in den Pfeiler).
        val announced = HashMap<Telegraph.Ring, Rod?>()
        var made = 0
        var t = 0
        while (made < waves || pending.isNotEmpty()) {
            if (made < waves && t == made * gap) {
                nearestFighter()?.let { hold(dirTo(it.entityPos)) }
                kit.act(ZeusAction.CALL)
                for (player in fighters()) pending.add(player to group.ring(spot(player.entityPos), Z.CALL_RADIUS, Z.CALL_W))
                telegraph.sound(HhaSounds.THUNDER_CALL, entityPos, 2.4f, 1f)
                made++
            }
            yield(Unit)
            t++
            for ((player, ring) in pending) {
                if (ring !in announced && ring.remaining <= SKY_LEAD) announced[ring] = announceSky(player, ring)
            }
            val due = pending.filter { !it.second.running }
            if (due.isNotEmpty()) {
                pending.removeAll(due.toSet())
                resolveCall(due, announced)
            }
            if (t > waves * max(gap, 1) + Z.CALL_W + 40) break
        }
        callsDone += waves
        hint(HINT_ROD)
        wait(window)
        kit.act(0)
    }

    private fun resolveCall(due: List<Pair<ServerPlayerEntity, Telegraph.Ring>>, announced: Map<Telegraph.Ring, Rod?> = emptyMap()) {
        for ((target, ring) in due) {
            val rod = if (groundingAllowed()) groundingRod(target, ring) else null
            if (rod != null) {
                // Kam die Erdung erst in den letzten Ticks zustande, biegt der Bogen jetzt noch in den Pfeiler.
                if (announced[ring] !== rod) groundArc(ring.center, rod)
                chargeRod(rod)
                continue
            }
            for (player in ring.hits(fighters())) strike(player, Z.CALL_DAMAGE, BossDefense.Reach.SKY)
            skyBolt(ring.center)
            skyImpact(target, ring.center)
        }
        telegraph.sound(HhaSounds.THUNDER, entityPos, 2.6f, 1.1f)
    }

    /**
     * Bild eines Himmelsblitzes, [SKY_LEAD] Ticks vor dem Treffer: Steht der Gerufene dann an einem frischen Pfeiler,
     * öffnet sich der Riss über ihm und ein Bogen biegt in den Blitzableiter; sonst `core.sky_strike` (Riss, Strahl,
     * Einschlag genau im Treffer-Tick). Liefert den Pfeiler der Vorhersage.
     */
    private fun announceSky(target: ServerPlayerEntity, ring: Telegraph.Ring): Rod? {
        val rod = if (groundingAllowed()) groundingRod(target, ring) else null
        if (rod != null) {
            groundArc(ring.center, rod)
        } else {
            skyStrike(ring.center, Z.CALL_RADIUS, ring.remaining)
        }
        return rod
    }

    /** `core.sky_strike` mit Treffer genau in [ticksToHit] Ticks (der Strahl kommt `delay + 6` nach dem Aufruf, P03). */
    private fun skyStrike(ground: Vec3d, radius: Float, ticksToHit: Int) {
        BossFx.skyStrike(sw, Vfx.Style.THUNDER, ground, radius, SKY_HEIGHT, (ticksToHit - SKY_LEAD).coerceAtLeast(0), SKY_AFTER)
    }

    /** Erdung im Bild: Riss in der Wolkendecke über [ground], ein Bogen mit zwei Strängen biegt in die Spitze von [rod]. */
    private fun groundArc(ground: Vec3d, rod: Rod) {
        val sky = ground.add(0.0, SKY_HEIGHT.toDouble(), 0.0)
        BossFx.tear(sw, Vfx.Style.THUNDER, sky, flatNorm(rod.center.subtract(ground)).let { if (it.lengthSquared() < 1e-6) Vec3d(1.0, 0.0, 0.0) else it },
            GROUND_TEAR_LENGTH, GROUND_TEAR_WIDTH, GROUND_TEAR_LIFE, GROUND_TEAR_OPEN)
        BossFx.arc(sw, Vfx.Style.THUNDER, sky, rod.tip, bend = GROUND_ARC_BEND, width = GROUND_ARC_WIDTH, life = GROUND_ARC_LIFE, strands = 2)
    }

    /**
     * Einschlag eines Himmels- bzw. Luftblitzes für [player] (nur seiner, auch wenn mehrere im selben Tick fallen):
     * Stoßring durchs Bild ([BossFx.Screen.SHOCK_RIPPLE], Radius 10, Stärke 0,5, 6 Ticks) und Ruck 1,5°.
     */
    private fun skyImpact(player: ServerPlayerEntity, at: Vec3d) {
        if (player.isRemoved || player.isDisconnected) return
        BossFx.play(
            sw, "core.screen", Vfx.Style.THUNDER, at, a = RIPPLE_RADIUS, b = RIPPLE_STRENGTH, c = 0f,
            d = BossFx.Screen.SHOCK_RIPPLE.ordinal.toFloat(), life = RIPPLE_TICKS, to = player,
        )
        BossFx.kick(player, KICK_SKY, ticks = KICK_TICKS)
    }

    /**
     * Donnerschritt (fern): Rune am Ziel W 20 (`STEP`), Kreis Radius 5, S 24, F 20. Er verschwindet bei 14 und erscheint
     * bei 20 im Kreis. Landet er nah an einem geladenen Pfeiler, entlädt sich der Pfeiler in ihn.
     */
    private fun thunderStep(target: ServerPlayerEntity, chain: Telegraph.Fight.Group? = null): Sequence<Unit> = sequence {
        val landing = landingFor(target.entityPos)
        val group = chain ?: telegraph.boss()
        val ring = group.ring(landing, Z.STEP_RADIUS, Z.STEP_W)
        telegraph.sound(HhaSounds.THUNDER_CALL, landing, 2.2f, 1.4f)
        stopMoving()
        hold(dirTo(landing))
        kit.act(ZeusAction.STEP)
        var t = 0
        var moved = false
        while (ring.running) {
            if (t == Z.STEP_VANISH) stepOut(entityPos)
            if (t == Z.STEP_MOVE) {
                place(landing, target.entityPos)
                moved = true
            }
            yield(Unit)
            t++
        }
        if (!moved) place(landing, target.entityPos)
        hold(dirTo(target.entityPos))
        for (player in ring.hits(fighters())) strike(player, Z.STEP_DAMAGE)
        Vfx.shockwave(sw, Vfx.Style.THUNDER, landing, Z.STEP_RADIUS, 14, 1.6f)
        stepIn(landing)
        BossFx.kick(sw, landing, KICK_STEP, ticks = KICK_TICKS, radius = KICK_RADIUS)
        telegraph.sound(HhaSounds.THUNDER, landing, 3f, 1f)
        if (groundingAllowed()) chargedRodNear(entityPos)?.let {
            yieldAll(discharge(it))
            return@sequence
        }
        if (chain == null) {
            wait(Z.STEP_F)
            kit.act(0)
        } else {
            wait(4)
        }
    }

    /**
     * Gorgonenblick: Er hebt die Aigis (`GAZE`), Kegel 60° halb, Radius 26, W 40 (Kette „Arges“: W 30, die Aigis hebt sich
     * 10 Ticks vorher). S 30 für alle im Kegel, die zu ihm schauen (weniger als 70° von ihm weg) **und** ihn sehen: Ein
     * Pfeiler dazwischen schützt. Danach F 24.
     */
    private fun gorgonGaze(windup: Int, chain: Telegraph.Fight.Group?, chained: Boolean): Sequence<Unit> = sequence {
        if (!chained) gazeAt = phaseAge + Z.GAZE_INTERVAL
        val target = nearestFighter() ?: return@sequence
        stopMoving()
        val dir = dirTo(target.entityPos)
        hold(dir)
        kit.act(ZeusAction.GAZE)
        if (chained) wait(Z.GAZE_CHAIN_LEAD)
        val group = chain ?: telegraph.boss(pace = Telegraph.Pace.ROOM)
        val cone = group.cone(spot(entityPos), dir, Z.GAZE_RADIUS, Z.GAZE_HALF_ANGLE, windup)
        hint(HINT_GAZE)
        telegraph.sound(HhaSounds.CHARGE, entityPos, 3f, 0.55f)
        // Aigis-Schein: lädt über die Ansage an der Aigis auf, im Treffer ein gerichteter Lichtkegel (kein Bildschirm-Pass).
        BossFx.play(
            sw, "zeus.gaze", Vfx.Style.THUNDER, gazeEye(), dir, a = Z.GAZE_RADIUS, b = Z.GAZE_HALF_ANGLE,
            c = cone.ticks.toFloat(), life = cone.ticks + GAZE_FX_AFTER, entity = self,
        )
        wait(cone.ticks)
        val eye = gazeEye()
        for (player in fighters()) {
            // Wer hinsieht, bekommt eine kühle Vignette (getroffen oder hinter einem Pfeiler geschützt).
            if (looksAt(player, eye)) BossFx.vignette(player, Vfx.Style.THUNDER, GAZE_VIGNETTE, GAZE_VIGNETTE_TICKS)
            if (!cone.contains(player) || !looksAt(player, eye) || !sees(eye, player)) continue
            if (strike(player, Z.GAZE_DAMAGE, BossDefense.Reach.SKY)) {
                Vfx.beam(sw, Vfx.Style.THUNDER, eye, player.eyePos.subtract(0.0, 0.3, 0.0), 0.5f, 8)
            }
        }
        telegraph.sound(HhaSounds.THUNDER, entityPos, 3f, 0.7f)
        if (!chained) {
            wait(Z.GAZE_F)
            kit.act(0)
        }
    }

    /** Kette „Brontes“ (nah): Donnerschlag → Ringe → Keraunos-Wurf; leere Hand: offen F 30. */
    private fun chainBrontes(): Sequence<Unit> = sequence {
        chainBroken = false
        val group = telegraph.boss()
        yieldAll(thunderSlam(group))
        if (chainBroken) return@sequence
        val next = nearestFighter() ?: return@sequence
        yieldAll(keraunosThrow(next, group))
    }

    /** Kette „Steropes“ (fern): Donnerschritt → Aigis-Stoß → Keraunos-Wurf; offen F 30. */
    private fun chainSteropes(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        chainBroken = false
        val group = telegraph.boss()
        yieldAll(thunderStep(target, group))
        if (chainBroken) return@sequence
        nearestFighter()?.let { yieldAll(aegisBash(it, group)) }
        if (chainBroken) return@sequence
        nearestFighter()?.let { yieldAll(keraunosThrow(it, group)) }
    }

    /** Kette „Arges“ (fern): Gorgonenblick (W 30) → drei Himmelsblitze im Abstand von 16 Ticks; F 24. */
    private fun chainArges(): Sequence<Unit> = sequence {
        chainBroken = false
        val group = telegraph.boss()
        yieldAll(gorgonGaze(Z.GAZE_W_CHAIN, group, chained = true))
        yieldAll(skyCall(group, Z.ARGES_BOLTS, Z.ARGES_GAP, Z.ARGES_F))
    }

    /** Taumeln: offen (goldene Rune), danach richtet er sich auf. */
    private fun stagger(ticks: Int): Sequence<Unit> = sequence {
        stopMoving()
        kit.act(ZeusAction.STAGGER)
        open(ticks, OPEN_FACTOR)
        telegraph.sound(HhaSounds.IMPACT, entityPos, 3f, 0.5f)
        wait(ticks)
        kit.act(0)
        wait(Z.STAGGER_RISE)
    }

    // ---------------------------------------------------------------------------------------------
    // Die Waage (Phase III, bei 66 % und 33 % der dritten Leiste)
    // ---------------------------------------------------------------------------------------------

    /** Lebensschwelle der nächsten Waage als Anteil des ganzen Lebens, `null` = keine mehr. */
    private fun scalesFloor(): Float? = when {
        phase != 2 -> null
        scalesDone == 0 -> Z.SCALES_AT_1
        scalesDone == 1 -> Z.SCALES_AT_2
        else -> null
    }

    /** Hält an der Schwelle der nächsten Waage an (sie löst nach dem laufenden Angriff aus), danach die Basis. */
    override fun limitBarDamage(source: DamageSource, bar: Float): Float {
        var limited = bar
        val floor = scalesFloor()
        if (floor != null) {
            val room = health - floor * maxHealth
            if (room <= 0.01f) {
                scalesDue = true
                return 0f
            }
            if (limited >= room) limited = room
        }
        return super.limitBarDamage(source, limited)
    }

    /** Die Waage wird erst vorgemerkt, wenn das Leben wirklich an ihrer Schwelle steht (Review R2 m1). */
    override fun afterHealthLoss() {
        val floor = scalesFloor()
        if (floor != null && health - floor * maxHealth <= 0.01f) scalesDue = true
        super.afterHealthLoss()
    }

    /**
     * Die Waage (PLAN 5.5): Er tritt zur Mitte und hebt die goldene Schicksalswaage (`SCALES`, W 40, nicht ins Taumeln
     * zu bringen, keine Erdung). Am Rand liegt eine goldene Schale (Radius 7, +1 je weiterem Spieler, höchstens 10).
     * Außerhalb tobt ab Tick 60 der Schale der Sturm (S 8 je 10 Ticks, `SKY`); in die Schale fallen vier Blitze (Radius
     * 2,6, W 20, alle 50 Ticks). Steht am Ende jemand in der Schale: Taumeln, **offen** F 110. Sonst heilt er 12 % der Leiste.
     */
    private fun scales(index: Int): Sequence<Unit> = sequence {
        endHazard()
        val mid = courtCenter()
        if (GuardianEntity.flat(entityPos, mid) > Z.SCALES_CENTER_DISTANCE) yieldAll(stepTo(mid))
        scalesActive = true
        val radius = min(Z.BOWL_RADIUS + Z.BOWL_RADIUS_PER_PLAYER * (crowdSize - 1), Z.BOWL_RADIUS_MAX)
        val bowlCenter = chooseBowl(radius.toDouble())
        stopMoving()
        hold(dirTo(bowlCenter))
        kit.act(ZeusAction.SCALES)
        val storm = telegraph.hazard(pace = Telegraph.Pace.ROOM) ?: telegraph.boss(pace = Telegraph.Pace.ROOM)
        val bowl = storm.safe(bowlCenter, radius, Z.SCALES_END)
        hint(HINT_SCALES)
        Cine.dialog(viewers(), Cine.SPEAKER_ZEUS, if (index == 0) "hha.zeus.line.scales.1" else "hha.zeus.line.scales.2", 60)
        telegraph.sound(HhaSounds.CHIME, entityPos, 2.4f, 0.6f)
        // Er lädt die Waage über das Heben auf (Schalen ziehen sich zusammen, kein Rütteln, kein Bildschirm-Pass).
        BossFx.charge(sw, Vfx.Style.THUNDER, self, SCALES_CHARGE_RADIUS, Z.SCALES_W)
        val stormStart = (Z.SCALES_W + Z.SCALES_STORM_START).coerceAtLeast(bowlEscape(bowlCenter, radius.toDouble()))
            .coerceAtMost(Z.SCALES_END - 60)
        val casts = intArrayOf(Z.SCALES_CAST_1, Z.SCALES_CAST_2, Z.SCALES_CAST_3, Z.SCALES_CAST_4)
        val bolts = ArrayList<Telegraph.Ring>()
        for (t in 0 until Z.SCALES_END) {
            for (cast in casts) {
                if (t != cast - Z.SCALES_BOLT_W) continue
                val spot = bowlTarget(bowlCenter, radius.toDouble(), bowl)
                val boltRing = telegraph.boss(pace = Telegraph.Pace.QUICK).ring(spot, Z.SCALES_BOLT_RADIUS, Z.SCALES_BOLT_W)
                bolts.add(boltRing)
                telegraph.sound(HhaSounds.THUNDER_CALL, spot, 2f, 1.3f)
                // Riss und Strahl wie beim Himmelsblitz, aber ohne Bildschirm-Pass und ohne Ruck (Waage).
                skyStrike(spot, Z.SCALES_BOLT_RADIUS, boltRing.ticks)
            }
            // Der Sturm außerhalb der Schale als weiche Regenwand (die goldene Schale bleibt frei und Ansage).
            if (t == stormStart) {
                BossFx.play(
                    sw, "zeus.storm_wall", Vfx.Style.THUNDER, bowlCenter, a = radius, b = courtRadius().toFloat(),
                    d = STORM_RAIN_HEIGHT, life = Z.SCALES_END - stormStart, extra = STORM_RING,
                )
            }
            if (t >= stormStart && (t - stormStart) % Z.SCALES_STORM_INTERVAL == 0) {
                val outside = fighters().filter { !bowl.contains(it) }
                for (player in outside) strike(player, Z.SCALES_STORM_DAMAGE, BossDefense.Reach.SKY)
                outside.randomOrNull()?.let { skyBolt(spot(it.entityPos)) }
                if (t == stormStart) telegraph.sound(HhaSounds.THUNDER, mid, 3f, 0.6f)
            }
            yield(Unit)
            val due = bolts.filter { !it.running }
            for (ring in due) {
                for (player in ring.hits(fighters())) strike(player, Z.SCALES_BOLT_DAMAGE, BossDefense.Reach.SKY)
                skyBolt(ring.center)
            }
            bolts.removeAll(due.toSet())
        }
        val tipped = fighters().any { bowl.contains(it) }
        storm.end()
        scalesActive = false
        if (tipped) {
            Cine.dialog(viewers(), Cine.SPEAKER_ZEUS, "hha.zeus.line.scales.tipped", 44)
            Vfx.flare(sw, Vfx.Style.GOLD, bowlCenter.add(0.0, 1.2, 0.0), 2.4f, 12)
            yieldAll(stagger(Z.SCALES_STAGGER_F))
        } else {
            Cine.dialog(viewers(), Cine.SPEAKER_ZEUS, "hha.zeus.line.scales.empty", 44)
            val cap = Z.PHASE_3 * maxHealth - 1f
            val amount = (Z.SCALES_HEAL * Z.PHASE_3 * maxHealth).coerceAtMost(cap - health)
            if (amount > 0f) heal(amount)
            Vfx.flare(sw, Vfx.Style.THUNDER, entityPos.add(0.0, height * 0.6, 0.0), 2f, 10)
            kit.act(0)
            wait(20)
        }
    }

    /** Schale am Rand, zwischen zwei Pfeilern; die Lage, die alle am schnellsten erreichen. */
    private fun chooseBowl(radius: Double): Vec3d {
        val mid = courtCenter()
        val distance = (courtRadius() - radius - Z.BOWL_EDGE_MARGIN).coerceAtLeast(0.0)
        val options = BOWL_ANGLES.map { spot(mid.add(OlympusLayout.dir(it).multiply(distance))) }
        val scored = options.map { it to bowlEscape(it, radius) }
        val best = scored.minOf { it.second }
        val near = scored.filter { it.second <= best + 10 }
        return near[random.nextInt(near.size)].first
    }

    /** Ticks, bis der Langsamste die Schale erreicht hätte (Reaktion + Weg bei [BossTimings.Min.ESCAPE_SPEED]). */
    private fun bowlEscape(center: Vec3d, radius: Double): Int = fighters().maxOfOrNull { player ->
        val walk = (GuardianEntity.flat(player.entityPos, center) - radius + 0.6).coerceAtLeast(0.0) * DETOUR
        ceil(BossTimings.Min.REACTION + walk / BossTimings.Min.ESCAPE_SPEED).toInt()
    } ?: 0

    /** Ziel eines Blitzes in die Schale: wer darin steht, sonst eine Stelle darin. */
    private fun bowlTarget(center: Vec3d, radius: Double, bowl: Telegraph.Ring): Vec3d {
        val inside = fighters().filter { bowl.contains(it) }
        if (inside.isNotEmpty()) return spot(inside[random.nextInt(inside.size)].entityPos)
        val a = random.nextDouble() * 360.0
        val r = random.nextDouble() * (radius - Z.SCALES_BOLT_RADIUS).coerceAtLeast(0.0)
        return spot(center.add(OlympusLayout.dir(a).multiply(r)))
    }

    /** Donnerschritt ohne Schaden (vor der Waage zur Mitte). */
    private fun stepTo(goal: Vec3d): Sequence<Unit> = sequence {
        stopMoving()
        hold(dirTo(goal))
        kit.act(ZeusAction.STEP)
        for (t in 0 until Z.STEP_APPEAR) {
            if (t == Z.STEP_VANISH) stepOut(entityPos)
            if (t == Z.STEP_MOVE) place(goal, null)
            yield(Unit)
        }
        stepIn(goal)
        wait(6)
    }

    // ---------------------------------------------------------------------------------------------
    // Saal-Gefahren (Phase II, alle 16 s im Wechsel, nie mit Blick oder Waage)
    // ---------------------------------------------------------------------------------------------

    private fun tickHazard() {
        val running = hazard
        if (running != null) {
            if (running.hasNext()) running.next() else endHazard()
            return
        }
        if (phase != 1 || alone || phaseAge < hazardAt) return
        // Steht der Blick bald an, wartet die Gefahr: Er soll nicht in sie hineinfallen.
        if (phaseAge >= gazeAt - Z.HAZARD_GAZE_GUARD) return
        val group = telegraph.hazard() ?: return
        hazardAt = phaseAge + Z.HAZARD_INTERVAL
        hazardGroup = group
        hazard = (if (hazardTurn++ % 2 == 0) eagle(group) else stormWall(group)).iterator()
    }

    private fun endHazard() {
        hazard = null
        hazardGroup?.end()
        hazardGroup = null
        hazardProp?.discard()
        hazardProp = null
    }

    /** Der Adler: Linie 3 breit quer durch den Hof (durch einen Spieler), W 30; er fliegt sie in 16 Ticks, S 18. */
    private fun eagle(group: Telegraph.Fight.Group): Sequence<Unit> = sequence {
        val target = fighters().randomOrNull() ?: return@sequence
        val mid = courtCenter()
        val dir = OlympusLayout.dir(random.nextDouble() * 360.0)
        val rel = Vec3d(target.x - mid.x, 0.0, target.z - mid.z)
        val r = courtRadius()
        // Sehne durch den Spieler: Schnittpunkte der Geraden rel + s · dir mit dem Rand des Hofs.
        val b = rel.x * dir.x + rel.z * dir.z
        val disc = b * b - (rel.lengthSquared() - r * r)
        val (a, end) = if (disc > 0.0) {
            val root = sqrt(disc)
            spot(mid.add(rel).add(dir.multiply(-b - root))) to spot(mid.add(rel).add(dir.multiply(-b + root)))
        } else {
            spot(mid.add(dir.multiply(-r))) to spot(mid.add(dir.multiply(r)))
        }
        val line = group.line(a, end, Z.EAGLE_WIDTH, Z.EAGLE_W)
        group.hold(Z.EAGLE_W + Z.EAGLE_FLIGHT + 1)
        telegraph.sound(HhaSounds.GRIFFIN_CRY, a, 2.6f, 1.25f)
        wait(line.ticks)
        val lift = Vec3d(0.0, Z.EAGLE_HEIGHT, 0.0)
        val bird = ScriptedBolt.spawn(sw, self, HermesBoltEntity.EAGLE, 1f, a.add(lift))
        hazardProp = bird
        val length = GuardianEntity.flat(a, end)
        val axis = flatNorm(end.subtract(a))
        val struck = HashSet<UUID>()
        for (i in 1..Z.EAGLE_FLIGHT) {
            val before = length * (i - 1) / Z.EAGLE_FLIGHT
            val now = length * i / Z.EAGLE_FLIGHT
            bird?.moveTo(a.lerp(end, i.toDouble() / Z.EAGLE_FLIGHT).add(lift))
            for (player in fighters()) {
                val at = along(player.entityPos, a, axis)
                if (at < before - 1.0 || at > now + 1.0 || !line.contains(player) || player.uuid in struck) continue
                if (strike(player, Z.EAGLE_DAMAGE)) struck.add(player.uuid)
            }
            if (i == Z.EAGLE_FLIGHT / 2) telegraph.sound(HhaSounds.WHOOSH, a.lerp(end, 0.5), 2.4f, 1.1f)
            yield(Unit)
        }
        bird?.discard()
        hazardProp = null
    }

    /**
     * Sturmwand: Eine Regenwand zieht quer durch den Hof (W ≥ 40, danach [Z.STORM_TRAVEL] Ticks Lauf), S 24. Nur die
     * goldene Lücke (8 breit) bleibt verschont. Die Lücke liegt so, dass jeder sie rechtzeitig erreicht (wie der Zug der
     * Toten bei Hades): sonst erst längere Ansage, dann breitere Lücke, zur Not fällt die Wand aus.
     */
    private fun stormWall(group: Telegraph.Fight.Group): Sequence<Unit> = sequence {
        val mid = courtCenter()
        val reach = courtRadius() - 1.0
        val axis = OlympusLayout.dir(random.nextDouble() * 360.0)
        val across = Vec3d(-axis.z, 0.0, axis.x)
        fun point(at: Double, side: Double) = spot(mid.add(axis.multiply(at)).add(across.multiply(side)))
        val players = fighters()
        fun windupFor(gap: Double, half: Double): Int = players.maxOfOrNull { p ->
            val off = p.entityPos.subtract(mid)
            val a = off.x * axis.x + off.z * axis.z
            val c = off.x * across.x + off.z * across.z
            val walk = (abs(c - gap) - half + 0.6).coerceAtLeast(0.0) * DETOUR
            val travel = ((a + reach) / (2.0 * reach)).coerceIn(0.0, 1.0) * Z.STORM_TRAVEL
            ceil(BossTimings.Min.REACTION + walk / BossTimings.Min.ESCAPE_SPEED - travel).toInt()
        } ?: 0
        var chosen: Triple<Double, Double, Int>? = null
        for (width in listOf(Z.STORM_GAP.toDouble(), 12.0, 16.0, 20.0)) {
            val half = width / 2.0
            val limit = reach - half - 4.0
            if (limit <= 0.0) break
            val options = (-20..20).map { it / 20.0 * limit }.map { it to max(Z.STORM_W, windupFor(it, half)) }
            val best = options.minOf { it.second }
            if (best > BossTimings.Min.ESCAPE_MAX) continue
            val near = options.filter { it.second <= best + 2 }
            val (gap, needed) = near[random.nextInt(near.size)]
            chosen = Triple(gap, half, needed)
            break
        }
        val (gap, half, windup) = chosen ?: return@sequence
        // Die Ansage zeigt genau, was die Wand trifft: alles außer der goldenen Lücke.
        val low = gap - half + reach
        val high = reach - (gap + half)
        group.line(point(-reach, (gap - half - reach) / 2.0), point(reach, (gap - half - reach) / 2.0), low.toFloat(), windup)
        group.line(point(-reach, (gap + half + reach) / 2.0), point(reach, (gap + half + reach) / 2.0), high.toFloat(), windup)
        group.safeLine(point(-reach, gap), point(reach, gap), (half * 2.0).toFloat(), windup)
        group.hold(windup + Z.STORM_TRAVEL + 1)
        hint(HINT_STORM)
        telegraph.sound(HhaSounds.THUNDER, point(-reach, gap), 3f, 0.6f)
        wait(group.ticks)
        val struck = HashSet<UUID>()
        // Die Wand als ein Effekt: Regenwand mit Lücke, läuft in [Z.STORM_TRAVEL] Ticks von −reach nach +reach (wie der
        // Treffer unten); die Lücke hat feine goldene Pfosten (Gold = sicher).
        BossFx.play(
            sw, "zeus.storm_wall", Vfx.Style.THUNDER, spot(mid), axis, a = reach.toFloat(), b = gap.toFloat(), c = half.toFloat(),
            d = STORM_WALL_HEIGHT, life = Z.STORM_TRAVEL + 1, extra = STORM_LINE,
        )
        for (i in 0..Z.STORM_TRAVEL) {
            val at = -reach + 2.0 * reach * i / Z.STORM_TRAVEL
            if (i % 10 == 0) telegraph.sound(HhaSounds.WHOOSH, point(at, gap), 2.2f, 0.6f)
            for (player in fighters()) {
                val off = player.entityPos.subtract(mid)
                val a = off.x * axis.x + off.z * axis.z
                val c = off.x * across.x + off.z * across.z
                if (abs(a - at) >= Z.STORM_DEPTH || abs(c - gap) <= half || player.uuid in struck) continue
                if (strike(player, Z.STORM_DAMAGE)) {
                    struck.add(player.uuid)
                    Fx.launchPlayer(player, axis.multiply(0.8).add(0.0, 0.4, 0.0))
                }
            }
            yield(Unit)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Luftregel
    // ---------------------------------------------------------------------------------------------

    private fun tickAir() {
        val now = sw.time
        for (player in fighters()) {
            val id = player.uuid
            val ground = floorOrNull(player.entityPos.add(0.0, 0.1, 0.0), 24.0) ?: courtFloor()
            val up = !player.isOnGround && player.y - ground >= Z.AIR_HEIGHT
            val ticks = if (up) (airTicks[id] ?: 0) + 1 else 0
            airTicks[id] = ticks
            if (ticks <= Z.AIR_TICKS || now < (airReady[id] ?: 0L) || id in airActive) continue
            airTicks[id] = 0
            airReady[id] = now + Z.AIR_COOLDOWN
            airActive.add(id)
            jobs.add(airBolt(player).iterator())
            if (airHinted.add(id)) player.sendMessage(Text.translatable(HINT_AIR).formatted(Formatting.GOLD), true)
        }
    }

    /**
     * Himmelsblitz der Luftregel: W 16, die Markierung folgt dem Spieler bis Tick 6, dann steht der Kreis 10 Ticks fest
     * (Mindestdauer), `SKY`. Erdung gilt hier nicht.
     */
    private fun airBolt(player: ServerPlayerEntity): Sequence<Unit> = sequence {
        Vfx.rune(sw, Vfx.Style.THUNDER, player.entityPos, 1.2f, Z.AIR_LOCK + 2, player)
        telegraph.sound(HhaSounds.THUNDER_CALL, player.entityPos, 2f, 1.5f)
        wait(Z.AIR_LOCK)
        if (!player.isAlive || player.isRemoved) {
            airActive.remove(player.uuid)
            return@sequence
        }
        val ring = airCalls.boss(listOf(player)).ring(spot(player.entityPos), Z.AIR_RADIUS, Z.AIR_W - Z.AIR_LOCK)
        // Am Beginn der festen Ansage: Riss und Strahl, der genau im Treffer-Tick unten ankommt.
        skyStrike(ring.center, Z.AIR_RADIUS, ring.ticks)
        wait(ring.ticks)
        for (hit in ring.hits(fighters())) strike(hit, Z.AIR_DAMAGE, BossDefense.Reach.SKY)
        skyBolt(ring.center)
        skyImpact(player, ring.center)
        airActive.remove(player.uuid)
    }

    // ---------------------------------------------------------------------------------------------
    // Pfeiler (Erdung)
    // ---------------------------------------------------------------------------------------------

    /** Darf ein Pfeiler gerade laden oder sich entladen? Nicht in Szenen, nicht während der Waage. */
    private fun groundingAllowed(): Boolean = arena != null && !scenes.playing && !scalesActive && !ending

    private fun setupRods(arena: Arenas.Arena) {
        rodsReady = true
        val world = sw
        val station = world.registryKey == OlympusLayout.WORLD && GuardianEntity.flat(arena.center, OlympusLayout.F.centerVec) < 2.0
        flatCourt = station
        for (i in 0 until OlympusLayout.PILLAR_COUNT) {
            val column = if (station) {
                OlympusLayout.pillar(i)
            } else {
                val a = Math.toRadians(OlympusLayout.PILLAR_ANGLES[i])
                BlockPos.ofFloored(
                    arena.center.x + OlympusLayout.PILLAR_RING * cos(a), arena.floorY, arena.center.z + OlympusLayout.PILLAR_RING * sin(a),
                )
            }
            val existing = (-1..1).map { column.up(it) }.firstOrNull { isCopper(world.getBlockState(it)) }
            if (existing != null) {
                // Bestehender Pfeiler (Bau von P04): frisch machen, falls ein abgebrochener Kampf ihn verbraucht zurückließ.
                setCopper(existing, Blocks.WAXED_COPPER_BLOCK.defaultState)
                rods.add(Rod(existing, temporary = false))
            } else {
                buildRod(column, station)?.let(rods::add)
            }
        }
    }

    /**
     * Fehlt der Pfeiler (Test-Arena um einen gesetzten Altar, Thronhof noch nicht gebaut), steht er für den Kampf als
     * Leihgabe: fünf gewachste Kupferblöcke und ein Blitzableiter, nur auf Luft oder Ersetzbarem. Am Ende zurück.
     */
    private fun buildRod(column: BlockPos, station: Boolean): Rod? {
        val world = sw
        val foot = if (station) column else findFoot(column) ?: return null
        val cells = (0..OlympusLayout.PILLAR_HEIGHT).map { foot.up(it) }
        if (cells.any { !world.getBlockState(it).isReplaceable || world.getBlockEntity(it) != null }) return null
        val rod = Rod(foot, temporary = true)
        for (cell in cells) rod.saved.add(cell to world.getBlockState(cell))
        for (k in 0 until OlympusLayout.PILLAR_HEIGHT) world.setBlockState(foot.up(k), Blocks.WAXED_COPPER_BLOCK.defaultState, Block.NOTIFY_ALL)
        world.setBlockState(foot.up(OlympusLayout.PILLAR_HEIGHT), Blocks.WAXED_LIGHTNING_ROD.defaultState, Block.NOTIFY_ALL)
        return rod
    }

    /** Boden unter [column] in der Nähe der Arenahöhe (Test-Arenen sind nicht flach). */
    private fun findFoot(column: BlockPos): BlockPos? {
        val world = sw
        for (dy in 4 downTo -6) {
            val pos = column.up(dy)
            val below = pos.down()
            if (world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP) && world.getBlockState(pos).isReplaceable) return pos
        }
        return null
    }

    /** Am Kampfende (Sieg, Abbruch, Entfernen): alles zurück. Mehrfacher Aufruf schadet nicht. */
    private fun restoreRods() {
        if (!rodsReady || rodsRestored) return
        rodsRestored = true
        val world = entityWorld as? ServerWorld ?: return
        for (rod in rods) {
            if (rod.temporary) {
                for ((pos, state) in rod.saved) world.setBlockState(pos, state, Block.NOTIFY_ALL)
            } else {
                setCopper(rod.foot, Blocks.WAXED_COPPER_BLOCK.defaultState)
            }
        }
        rods.clear()
    }

    private fun isCopper(state: BlockState): Boolean = COPPER.any { state.isOf(it) }

    /** Setzt die Kupferblöcke des Pfeilers (nur solche, die Kupfer sind) auf [state]. */
    private fun setCopper(foot: BlockPos, state: BlockState) {
        val world = sw
        for (k in 0 until OlympusLayout.PILLAR_HEIGHT) {
            val pos = foot.up(k)
            val current = world.getBlockState(pos)
            if (isCopper(current) && !current.isOf(state.block)) world.setBlockState(pos, state, Block.NOTIFY_ALL)
        }
    }

    private fun tickRods() {
        val now = sw.time
        for (rod in rods) {
            if (rod.state != RodState.CHARGED) continue
            if (now >= rod.chargedUntil) {
                // Ungenutzt verglüht: Der Pfeiler ist wieder frisch.
                rod.state = RodState.FRESH
                continue
            }
            if (now >= rod.nextRune) {
                rod.nextRune = now + Z.ROD_RUNE_CYCLE
                val life = min(Z.ROD_RUNE_CYCLE + 4, (rod.chargedUntil - now).toInt())
                Vfx.rune(sw, Vfx.Style.GOLD, rod.center.add(0.0, 0.06, 0.0), Z.ROD_RUNE_RADIUS, life)
                // Dazu ein kleiner, ruhiger Schein am Fuß (knistert ≤ 1 Hz); endet mit der Rune oder bei der Entladung.
                BossFx.play(sw, "zeus.rod", Vfx.Style.THUNDER, rod.center, life = life)
            }
        }
    }

    /** Der Blitz schlägt in den Pfeiler: kein Schaden, der Pfeiler ist geladen. */
    private fun chargeRod(rod: Rod) {
        val now = sw.time
        rod.state = RodState.CHARGED
        rod.chargedUntil = now + Z.ROD_CHARGED
        rod.nextRune = now
        Vfx.bolt(sw, Vfx.Style.THUNDER, rod.tip.add(0.0, 22.0, 0.0), rod.tip, 1.1f, 8)
        lightning(rod.tip)
        telegraph.sound(HhaSounds.THUNDER, rod.tip, 2.6f, 1.2f)
        hint(HINT_LURE)
    }

    /** Entladung in Zeus: 40 × H(n) effektiv (unter dem Deckel), Taumeln, **offen** F 90; der Pfeiler ist verbraucht. */
    private fun discharge(rod: Rod): Sequence<Unit> = sequence {
        chainBroken = true
        rod.state = RodState.SPENT
        rod.chargedUntil = 0L
        setCopper(rod.foot, Blocks.WAXED_OXIDIZED_COPPER.defaultState)
        stopMoving()
        val chest = entityPos.add(0.0, height * 0.6, 0.0)
        // Dicker Bogen Pfeiler → Zeus (zwei Stränge), Funken an der Brust, Ruck.
        BossFx.arc(sw, Vfx.Style.THUNDER, rod.tip, chest, bend = DISCHARGE_BEND, width = DISCHARGE_WIDTH, life = DISCHARGE_LIFE, strands = 2, toEntity = self)
        BossFx.sparks(sw, Vfx.Style.THUNDER, chest, Vec3d(0.0, 1.0, 0.0), BossFx.SPARKS_PER_CALL, DISCHARGE_SPARK_SPEED, DISCHARGE_SPARK_LIFE)
        BossFx.kick(sw, entityPos, KICK_DISCHARGE, ticks = KICK_TICKS + 1, radius = KICK_RADIUS)
        lightning(entityPos)
        telegraph.sound(HhaSounds.THUNDER, entityPos, 3f, 0.8f)
        val bar = BossScaling.toBar(Z.ROD_DAMAGE * BossScaling.health(crowdSize), toughness(), crowdSize)
        val allowed = limitBarDamage(sw.damageSources.magic(), bar)
        if (allowed > 0f) hurtSelf(allowed)
        hint(HINT_DISCHARGE)
        yieldAll(stagger(Z.ROD_STAGGER_F))
    }

    private fun chargedRodNear(pos: Vec3d): Rod? = rods
        .filter { it.state == RodState.CHARGED && GuardianEntity.flat(pos, it.center) <= Z.ROD_DISCHARGE_DISTANCE + ROD_HALF }
        .minByOrNull { GuardianEntity.flat(pos, it.center) }

    /** Frischer Pfeiler für einen Himmelsblitz: der Gerufene oder jemand im Kreis steht am Boden nah daran. */
    private fun groundingRod(target: ServerPlayerEntity, ring: Telegraph.Ring): Rod? {
        val candidates = (listOf(target) + ring.hits(fighters())).distinct().filter { it.isAlive && !it.isRemoved && grounded(it) }
        var best: Rod? = null
        var bestDistance = Double.MAX_VALUE
        for (player in candidates) {
            for (rod in rods) {
                if (rod.state != RodState.FRESH) continue
                val d = GuardianEntity.flat(player.entityPos, rod.center)
                if (d <= Z.ROD_LURE_DISTANCE + ROD_HALF && d < bestDistance) {
                    best = rod
                    bestDistance = d
                }
            }
        }
        return best
    }

    /** Erster Pfeiler auf der Wurflinie ab [from] in [dir] bis [length] und sein Abstand entlang der Linie. */
    private fun rodOnLine(from: Vec3d, dir: Vec3d, length: Double): Pair<Rod, Double>? {
        var best: Pair<Rod, Double>? = null
        for (rod in rods) {
            val at = along(rod.center, from, dir)
            if (at < 1.5 || at > length) continue
            val px = from.x + dir.x * at
            val pz = from.z + dir.z * at
            val side = sqrt((rod.center.x - px) * (rod.center.x - px) + (rod.center.z - pz) * (rod.center.z - pz))
            if (side > Z.THROW_WIDTH / 2.0 + Z.THROW_ROD_MARGIN) continue
            if (best == null || at < best.second) best = rod to at
        }
        return best
    }

    // ---------------------------------------------------------------------------------------------
    // Hilfen
    // ---------------------------------------------------------------------------------------------

    /** Mitte der Kampffläche (Füße). */
    private fun courtCenter(): Vec3d = arena?.let { Vec3d(it.center.x, it.floorY, it.center.z) } ?: (home ?: entityPos)

    private fun courtRadius(): Double = arena?.radius ?: OlympusLayout.F.arenaRadius.toDouble()

    private fun courtFloor(): Double = arena?.floorY ?: defaultFloor()

    /** Stelle auf dem Boden für eine Ansage (Thronhof: flach; Test-Arenen: Boden darunter). */
    private fun spot(pos: Vec3d): Vec3d {
        val a = arena ?: return ground(pos)
        if (flatCourt) return Vec3d(pos.x, a.floorY + 0.05, pos.z)
        val hit = floorOrNull(Vec3d(pos.x, a.floorY + 6.0, pos.z), 14.0)
        return Vec3d(pos.x, (hit ?: a.floorY) + 0.05, pos.z)
    }

    private fun grounded(player: ServerPlayerEntity): Boolean = player.isOnGround || player.y - courtFloor() < 0.6

    private fun nearestFighter(): ServerPlayerEntity? = fighters().minByOrNull { it.squaredDistanceTo(self) }

    /** Landepunkt des Donnerschritts: auf der Fläche und nicht im Kupfer. */
    private fun landingFor(target: Vec3d): Vec3d {
        val mid = courtCenter()
        var x = target.x
        var z = target.z
        val limit = courtRadius() - 2.0
        val d = GuardianEntity.flat(Vec3d(x, 0.0, z), mid)
        if (d > limit) {
            x = mid.x + (x - mid.x) * limit / d
            z = mid.z + (z - mid.z) * limit / d
        }
        for (rod in rods) {
            val dx = x - rod.center.x
            val dz = z - rod.center.z
            val dist = sqrt(dx * dx + dz * dz)
            if (dist >= Z.STEP_ROD_CLEARANCE) continue
            val k = if (dist < 1e-3) 1.0 else Z.STEP_ROD_CLEARANCE / dist
            x = rod.center.x + (if (dist < 1e-3) Z.STEP_ROD_CLEARANCE else dx * k)
            z = rod.center.z + (if (dist < 1e-3) 0.0 else dz * k)
        }
        return spot(Vec3d(x, target.y, z))
    }

    /** Setzt ihn an [pos] (Füße) und dreht ihn zu [face]. */
    private fun place(pos: Vec3d, face: Vec3d?) {
        stopMoving()
        val y = floorOrNull(Vec3d(pos.x, pos.y + 1.5, pos.z), 4.0) ?: pos.y
        refreshPositionAndAngles(pos.x, y, pos.z, yaw, 0f)
        if (face != null) hold(dirTo(face))
    }

    private fun gazeEye(): Vec3d = entityPos.add(0.0, height * 0.82, 0.0).add(facing().multiply(0.6))

    /** Schaut [player] zu ihm (Blickrichtung weniger als [Z.GAZE_LOOK_ANGLE] von der Richtung zu [eye])? */
    private fun looksAt(player: ServerPlayerEntity, eye: Vec3d): Boolean {
        val to = eye.subtract(player.eyePos)
        if (to.lengthSquared() < 1e-6) return true
        val cos = player.rotationVector.normalize().dotProduct(to.normalize()).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos)) < Z.GAZE_LOOK_ANGLE
    }

    /** Freie Sicht von [eye] auf [player]? Ein Pfeiler (oder anderes Festes) dazwischen schützt. */
    private fun sees(eye: Vec3d, player: ServerPlayerEntity): Boolean {
        val hit = sw.raycast(RaycastContext(eye, player.eyePos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, self))
        return hit.type == HitResult.Type.MISS
    }

    /** Blitz aus dem Himmel auf [ground] (nur Bild). */
    private fun skyBolt(ground: Vec3d) = Vfx.bolt(sw, Vfx.Style.THUNDER, ground.add(0.0, 24.0, 0.0), ground, 1f, 8)

    /** Donnerschritt, Verschwinden: Blitz nach oben, ein Wirbel schließt sich um seine Füße. */
    private fun stepOut(at: Vec3d) {
        Vfx.bolt(sw, Vfx.Style.THUNDER, at.add(0.0, 0.5, 0.0), at.add(0.0, 22.0, 0.0), 0.9f, 6)
        BossFx.swirl(sw, Vfx.Style.THUNDER, at.add(0.0, 0.08, 0.0), Vec3d(0.0, 1.0, 0.0), STEP_SWIRL_RADIUS, STEP_SWIRL_LIFE, closing = true)
    }

    /** Donnerschritt, Erscheinen: Blitz aus dem Himmel und ein Lichtstern (`zeus.star`, kein Flächeneffekt). */
    private fun stepIn(at: Vec3d) {
        skyBolt(at)
        BossFx.play(sw, "zeus.star", Vfx.Style.THUNDER, at, a = STAR_RADIUS, life = STAR_LIFE)
    }

    /** Bossthema der Phase [p] (PLAN 6.8): `music.zeus`, `music.zeus_2`, `music.zeus_3`. */
    private fun theme(p: Int): SoundEvent = when (p) {
        0 -> HhaSounds.THEME_ZEUS
        1 -> HhaSounds.THEME_ZEUS_2
        else -> HhaSounds.THEME_ZEUS_3
    }

    /**
     * Echter Blitz, immer kosmetisch (kein Feuer, kein Vanilla-Schaden), höchstens [Z.LIGHTNING_PER_SECOND] je Sekunde;
     * darüber hinaus bleibt es beim gezeichneten Blitz. Ein Vanilla-Blitz lässt den ganzen Himmel aufhellen und donnert
     * laut; darum nur an den großen Momenten: ein Pfeiler fängt den Himmelsblitz, Entladung in Zeus, Szenen (Auftritt,
     * Phasenwechsel, Abgang). Alle normalen Angriffe zeigen nur den gezeichneten Blitz ([skyBolt]).
     */
    internal fun lightning(pos: Vec3d) {
        if (Cine.muted) return
        val world = entityWorld as? ServerWorld ?: return
        val now = world.time
        while (strikes.isNotEmpty() && strikes.first() <= now - 20) strikes.removeFirst()
        if (strikes.size >= Z.LIGHTNING_PER_SECOND) return
        strikes.addLast(now)
        val bolt = EntityType.LIGHTNING_BOLT.create(world, SpawnReason.EVENT) ?: return
        bolt.refreshPositionAfterTeleport(pos.x, pos.y, pos.z)
        bolt.setCosmetic(true)
        world.spawnEntity(bolt)
    }

    override fun onDamaged(source: DamageSource, lost: Float, factor: Float) {
        val player = source.attacker as? ServerPlayerEntity ?: return
        damageBy[player.uuid] = (damageBy[player.uuid] ?: 0f) + lost
    }

    /** Musik und Himmel für alle, die mitkämpfen; wer geht, bekommt beides zurückgesetzt (auch mitten in einer Szene). */
    private fun syncViewers() {
        if (age % 10 != 0) return
        val now = viewers()
        val ids = now.mapTo(HashSet()) { it.uuid }
        for (id in tuned.toList()) {
            if (id in ids) continue
            tuned.remove(id)
            val player = sw.getPlayerByUuid(id) as? ServerPlayerEntity ?: continue
            val list = listOf(player)
            if (scenes.playing) Cine.reset(list)
            Cine.music(list, null)
            Cine.atmosphere(list, null, courtCenter(), 1)
        }
        if (ending) return
        for (player in now) {
            if (!tuned.add(player.uuid)) continue
            val list = listOf(player)
            if (musicOn) Cine.music(list, theme(phase))
            if (stormOn) Cine.atmosphere(list, Vfx.Style.THUNDER, courtCenter(), 40)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Für die Szenen (ZeusScenes, gleiches Modul)
    // ---------------------------------------------------------------------------------------------

    /** Orte der Szenen: Siegel, Thron (Füße vor dem Sitz), Landepunkt im Hof, Hof-Mitte. */
    internal class Stage(val seal: Vec3d, val throne: Vec3d, val landing: Vec3d, val center: Vec3d)

    /** Bühne der Szenen; `null` ohne Arena (z. B. `/summon`). */
    internal fun sceneStage(): Stage? {
        val a = arena ?: return null
        val center = courtCenter()
        if (flatCourt) {
            val altar = OlympusLayout.F.altar ?: OlympusLayout.F.center
            val seal = Vec3d(altar.x + 0.5, a.floorY, altar.z + 0.5)
            val throne = Vec3d(OlympusLayout.THRONE.x + 0.5, a.floorY, OlympusLayout.THRONE.z + 1.5)
            val toSeal = flatNorm(seal.subtract(center))
            return Stage(seal, throne, spot(center.add(toSeal.multiply(4.0))), center)
        }
        // Test-Arena: Der Altar ist die Mitte, der „Thron“ liegt 22 nördlich, gelandet wird 10 nördlich.
        val throne = spot(center.add(0.0, 0.0, -22.0)).let { Vec3d(it.x, it.y - 0.05, it.z) }
        return Stage(center, throne, spot(center.add(0.0, 0.0, -10.0)), center)
    }

    internal fun sceneViewers(): List<ServerPlayerEntity> = viewers()

    internal fun sceneAct(action: Int) = kit.act(action)

    internal fun scenePlace(pos: Vec3d, face: Vec3d?) = place(pos, face)

    internal fun sceneFace(pos: Vec3d) = hold(dirTo(pos))

    internal fun sceneSound(event: SoundEvent, at: Vec3d, volume: Float, pitch: Float) {
        telegraph.sound(event, at, volume, pitch)
    }

    internal fun sceneSkyBolt(ground: Vec3d) = skyBolt(ground)

    internal fun sceneStepOut(at: Vec3d) = stepOut(at)

    internal fun sceneStepIn(at: Vec3d) = stepIn(at)

    /** Musik an (nach dem Auftritt) für alle Teilnehmer; spätere Teilnehmer bekommen sie in [syncViewers]. */
    internal fun sceneMusic(players: List<ServerPlayerEntity>) {
        musicOn = true
        Cine.music(players, theme(phase))
        for (player in players) tuned.add(player.uuid)
    }

    /** Thema der neuen Phase [newPhase] (im Phasenwechsel; die Überblendung macht der Client). */
    internal fun scenePhaseMusic(players: List<ServerPlayerEntity>, newPhase: Int) {
        musicOn = true
        Cine.music(players, theme(newPhase))
        for (player in players) tuned.add(player.uuid)
    }

    /** Gewitterhimmel über dem Hof (ab Phase II) bzw. aus. */
    internal fun sceneStorm(players: List<ServerPlayerEntity>, on: Boolean, ticks: Int) {
        stormOn = on
        if (on) Cine.atmosphere(players, Vfx.Style.THUNDER, courtCenter(), ticks) else Cine.atmosphere(players, null, courtCenter(), 1)
    }

    /** Zu Beginn von Phase II und III: zwei verbrauchte Pfeiler werden wieder frisch. Liefert ihre Spitzen. */
    internal fun sceneRenewRods(): List<Vec3d> {
        val renewed = rods.filter { it.state == RodState.SPENT }.take(Z.ROD_RENEW)
        for (rod in renewed) {
            rod.state = RodState.FRESH
            setCopper(rod.foot, Blocks.WAXED_COPPER_BLOCK.defaultState)
        }
        return renewed.map { it.tip }
    }

    /** Wer Nikes Kranz bekommt: der Spieler mit dem meisten Schaden (sonst der nächste). */
    internal fun sceneVictor(): ServerPlayerEntity? {
        val players = viewers().filter { !it.isSpectator }
        return players.maxByOrNull { damageBy[it.uuid] ?: 0f }?.takeIf { (damageBy[it.uuid] ?: 0f) > 0f }
            ?: players.minByOrNull { it.squaredDistanceTo(self) }
    }

    /** Beute, Fortschritt, Fernsäule (genau einmal). */
    internal fun sceneFinish() = finishVictory()

    /** Er geht: Blitz nach oben, weg. */
    internal fun sceneVanish() {
        Vfx.bolt(sw, Vfx.Style.THUNDER, entityPos.add(0.0, 0.5, 0.0), entityPos.add(0.0, 30.0, 0.0), 1.2f, 10)
        lightning(entityPos)
        discard()
    }

    companion object {
        fun attributes(): DefaultAttributeContainer.Builder = guardianAttributes()

        /** Kennungen der Ketten (für „nie dieselbe zweimal“), getrennt von den Aktionsnummern. */
        private const val CHAIN_BRONTES = 101
        private const val CHAIN_STEROPES = 102
        private const val CHAIN_ARGES = 103

        /** Obergrenze der Reichweite für Fernangriffe (über den Hof hinweg). */
        private const val FAR = 64.0

        /** Halbe Blockbreite des Pfeilers: Abstände zählen ab seiner Mitte. */
        private const val ROD_HALF = 0.5

        /** Umwege um Pfeiler beim Weg zu sicheren Stellen. */
        private const val DETOUR = 1.15

        // --- Darstellung (Überarbeitung 3, P11; keine Kampfzahlen) -------------------------------------------

        /** Ein Himmelsblitz erreicht den Boden so viele Ticks nach seinem `delay` (P03 `SkyStrikeFx.IMPACT_AFTER`). */
        const val SKY_LEAD = 6
        /** Höhe des Risses über dem Boden (in der Gewitterwolke, [OlympusLayout.STORM_CLOUD_HEIGHT] + 2). */
        const val SKY_HEIGHT = 26f
        /** Nachglühen des Himmelsblitzes nach dem Riss (Ticks). */
        const val SKY_AFTER = 16

        /** Einschlag des Himmels-/Luftblitzes: Stoßring im Bild nur für den Gerufenen. */
        const val RIPPLE_RADIUS = 10f
        const val RIPPLE_STRENGTH = 0.5f
        const val RIPPLE_TICKS = 6

        /** Kamera-Ruck (Grad, nach unten) je Treffer; ersetzt das Rütteln dort (nie beides). */
        const val KICK_SKY = 1.5f
        const val KICK_BASH = 1.4f
        const val KICK_STEP = 2f
        const val KICK_SLAM = 2.5f
        const val KICK_DISCHARGE = 2f
        const val KICK_TICKS = 6
        const val KICK_RADIUS = 32f

        /** Erdung: Riss über dem Gerufenen, Bogen in die Spitze des Pfeilers (Kopf erreicht sie nach ~55 % der Zeit). */
        const val GROUND_ARC_BEND = 0.18f
        const val GROUND_ARC_WIDTH = 0.7f
        const val GROUND_ARC_LIFE = 11
        const val GROUND_TEAR_LENGTH = 6f
        const val GROUND_TEAR_WIDTH = 1.1f
        const val GROUND_TEAR_LIFE = 22
        const val GROUND_TEAR_OPEN = 4

        /** Entladung in Zeus: dicker Bogen und Funken. */
        const val DISCHARGE_BEND = 0.22f
        const val DISCHARGE_WIDTH = 1.1f
        const val DISCHARGE_LIFE = 14
        const val DISCHARGE_SPARK_SPEED = 0.55f
        const val DISCHARGE_SPARK_LIFE = 18

        /** Donnerschlag `zeus.slam` (Risse, Trümmer, Lichtring). */
        const val SLAM_FX_LIFE = 50

        /** Donnerschritt: Wirbel beim Verschwinden, Lichtstern beim Erscheinen. */
        const val STEP_SWIRL_RADIUS = 1.8f
        const val STEP_SWIRL_LIFE = 12
        const val STAR_RADIUS = 3.6f
        const val STAR_LIFE = 14

        /** Gorgonenblick: Lichtkegel nach dem Treffer, kühle Vignette für alle, die hinsehen. */
        const val GAZE_FX_AFTER = 20
        const val GAZE_VIGNETTE = 0.2f
        const val GAZE_VIGNETTE_TICKS = 34

        /** Waage: Aufladen an der Waage; Regen außerhalb der Schale. */
        const val SCALES_CHARGE_RADIUS = 2.6f
        const val STORM_RAIN_HEIGHT = 9f
        const val STORM_WALL_HEIGHT = 5.5f
        const val STORM_RING = "ring"
        const val STORM_LINE = "line"

        /** Lagen der Schale: zwischen den Pfeilern (die stehen bei 0°, 60° …). */
        private val BOWL_ANGLES = listOf(30.0, 90.0, 150.0, 210.0, 270.0, 330.0)

        private val COPPER = listOf(
            Blocks.COPPER_BLOCK, Blocks.EXPOSED_COPPER, Blocks.WEATHERED_COPPER, Blocks.OXIDIZED_COPPER,
            Blocks.WAXED_COPPER_BLOCK, Blocks.WAXED_EXPOSED_COPPER, Blocks.WAXED_WEATHERED_COPPER, Blocks.WAXED_OXIDIZED_COPPER,
        )

        const val HINT_ROD = "hha.zeus.hint.rod"
        const val HINT_LURE = "hha.zeus.hint.lure"
        const val HINT_DISCHARGE = "hha.zeus.hint.discharge"
        const val HINT_GAZE = "hha.zeus.hint.gaze"
        const val HINT_STORM = "hha.zeus.hint.storm"
        const val HINT_SCALES = "hha.zeus.hint.scales"
        const val HINT_AIR = "hha.zeus.hint.air"
    }
}
