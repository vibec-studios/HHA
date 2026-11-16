package dev.henny.hha.entity.boss.olymp

import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.DuelBossEntity
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.entity.miniboss.MiniBossKit
import dev.henny.hha.entity.miniboss.ScriptedBolt
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossDefense
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha.logic.boss.timings.HekateTimings
import dev.henny.hha.logic.world.HadesGate
import dev.henny.hha.world.olymp.OlympusLayout
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.damage.DamageTypes
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Aktionen der Hekate für `kit.act` (PLAN 10.2). Werte gehen über die Leitung: nicht umnummerieren. */
object HekateAction {
    const val NONE = 0
    const val TORCH_SWEEP = 1
    const val TORCH_THROW = 2
    const val PATH = 3
    const val KEY_TURN = 4
    const val STEP = 5
    const val TRIFORM = 6
    const val ECLIPSE = 7
    const val STAGGER = 8
    const val HOWL = 9
    const val YIELD = 10
}

/**
 * Hekate, die Dreigestaltige, Hüterin des Höllentors am Dreiweg (PLAN 5.2). Ersatz für den Schatten des Boten.
 *
 * **Gedanke:** Am Kreuzweg zählt nicht Kraft, sondern die Wahl. Sie schwebt (gleitet, keine Schritte), Gefahrfarbe
 * [Vfx.Style.MOON]; der Platz des Dreiwegs (Radius 26) hat drei 6 breite Wege aus der Mitte (90°, 210°, 330°,
 * [OlympusLayout.C_PATH_ANGLES]), in der Mitte steht das Hekataion. Leben 650 × Zähigkeit 2,0 = 1300 effektiv, Phasen
 * bei 66 % und 33 %; je Phase tritt eine andere Gestalt nach vorn (das Modell dreht den Kopfkranz über [phase]).
 *
 * Ablauf (alle Zahlen aus [HekateTimings], Tabelle PLAN 5.2):
 * - **Feste Reihenfolge:** I Schwung → Wurf → Pfad; II und III Schritt → Schwung → Wurf → Pfad. Den Schwung lässt
 *   sie aus, wenn das Ziel weit weg ist ([HekateTimings.SWEEP_SKIP_DISTANCE]); nie zweimal derselbe Angriff.
 * - **Schlüsseldrehung** (ab II, alle 28 s) und **Mondfinsternis** (III, alle 20 s) sind saalweit und zählen als
 *   Saal-Gefahr: nie zugleich miteinander, nie am Stück ([HekateTimings.HAZARD_GAP]), nie neben dem Bodenfeuer. Sie
 *   spielt sie selbst (eigene Aktion), also läuft nebenher kein zweiter Angriff. Gold heißt sicher.
 * - **Dreigestalt** (III, nach jedem dritten Angriff): sie und zwei Trugbilder ([echo]) an den drei Wegenden, eine
 *   Ansage-Gruppe. Nur die Echte hält die brennende Fackel; wer sie in der Ansage trifft, löst die Trugbilder auf:
 *   STAGGER, offen.
 * - **Konter** gegen Spieler über ihr: „Geheul der Hunde“ ([HekateAction.HOWL]) über die Basis.
 * - **Sieg:** Sie tritt zur Seite, der Schlüssel dreht sich, das Höllentor füllt sich ([HadesGate.openOlympusGate]);
 *   Beute und Fortschritt über [finishVictory]. Kein Tod, sie bleibt 10 s stehen.
 *
 * Trugbilder sind eigene [HekateEntity] mit [echo] = true: ohne Drehbuch, ohne Leiste, ohne Mitkämpfer, nehmen keinen
 * Schaden (ein Treffer lässt sie verwehen) und sind kein Ziel für Vanilla-Mobs.
 */
class HekateEntity(type: EntityType<out HekateEntity>, world: World) : DuelBossEntity(type, world) {

    override val kit = MiniBossKit(this, "entity.hha.hekate", BossBar.Color.PURPLE, Vfx.Style.MOON)
    override val fightName = "hekate"
    override val danger = Vfx.Style.MOON
    override val bossId = "hekate"
    override val effectiveHealth = T.EFFECTIVE_HEALTH
    override val phases = listOf(T.PHASE_2, T.PHASE_3)
    override val antiAirAction get() = HekateAction.HOWL
    override val antiAirDamage get() = T.COUNTER_DAMAGE
    override val yieldAction = HekateAction.YIELD
    override val music: SoundEvent get() = HhaSounds.THEME_HEKATE

    /** Trugbild der Dreigestalt (andere Textur, nimmt keinen Schaden). */
    var echo: Boolean
        get() = dataTracker.get(ECHO)
        set(value) = dataTracker.set(ECHO, value)

    /** Gestalt in der Dreigestalt: 0 Fackel, 1 Schlüssel, 2 Dolch. */
    var form: Int
        get() = dataTracker.get(FORM)
        set(value) = dataTracker.set(FORM, value)

    // --- Drehbuch ----------------------------------------------------------------------------------
    /** Stelle in der festen Reihenfolge der laufenden Phase. */
    private var rotation = 0

    /** Normale Angriffe seit der letzten Dreigestalt (Phase III). */
    private var sinceTriform = 0
    private var nextKeyAt = Long.MAX_VALUE
    private var nextEclipseAt = Long.MAX_VALUE
    private var lastHazardEnd = Long.MIN_VALUE / 4

    /** Zuletzt goldener Weg (nie zweimal derselbe). */
    private var lastKeyPath = -1

    // --- Dreigestalt -------------------------------------------------------------------------------
    private val echoes = ArrayList<HekateEntity>()
    private var triformWindup = false
    private var triformBroken = false

    // --- Bodenfeuer und Vorhalt --------------------------------------------------------------------
    private var fireUntil = 0L
    private val tracks = HashMap<UUID, ArrayDeque<Vec3d>>()

    init {
        kit.stats(T.HEALTH, T.ARMOR, T.ATTACK, T.SPEED)
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(ECHO, false)
        builder.add(FORM, 0)
    }

    override fun chaseSpeed(): Double = T.CHASE_SPEED

    override fun basePause(): Int = if (phase >= 2) T.PAUSE_PHASE_3 else T.PAUSE

    // ---------------------------------------------------------------------------------------------
    // Trugbilder: kein Drehbuch, keine Mitkämpfer (also keine Leiste), keine Abwehr, kein Ziel für Mobs
    // ---------------------------------------------------------------------------------------------

    override val autonomous: Boolean get() = !echo
    override val usesDefense: Boolean get() = !echo && super.usesDefense
    override fun fighters(): List<ServerPlayerEntity> = if (echo) emptyList() else super.fighters()
    override fun isPartOfGame(): Boolean = !echo && super.isPartOfGame()

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        // Sie schwebt aus dem Hekataion und durch die Wege: Steckenbleiben tut ihr nichts.
        if (source.isOf(DamageTypes.IN_WALL)) return false
        if (echo) {
            if (source.attacker is PlayerEntity) vanish()
            return false
        }
        // Dreigestalt: Wer die Echte in der Ansage trifft (auch mit Pfeil oder Dreizack), hat sie erkannt.
        // Nur ein gültiger Treffer zählt (nicht vom Rand aus; Review R4 m1).
        if (triformWindup && source.attacker is PlayerEntity && acceptsHit(world, source)) triformBroken = true
        return super.damage(world, source, amount)
    }

    /** Ein Trugbild verweht (Treffer oder Ende der Dreigestalt). */
    private fun vanish() {
        if (isRemoved) return
        val world = entityWorld as? ServerWorld ?: return
        Vfx.flare(world, Vfx.Style.MOON, entityPos.add(0.0, 1.8, 0.0), 1.6f, 8)
        Fx.burst(world, ParticleTypes.SMOKE, entityPos.add(0.0, 1.4, 0.0), 10, 0.4, 0.02)
        world.playSound(null, x, y, z, HhaSounds.BLINK, net.minecraft.sound.SoundCategory.HOSTILE, 1.4f, 0.7f)
        discard()
    }

    private fun dismissEchoes() {
        for (e in echoes) e.vanish()
        echoes.clear()
    }

    override fun onReset() {
        dismissEchoes()
        triformWindup = false
        fireUntil = 0L
    }

    override fun remove(reason: RemovalReason) {
        if (!entityWorld.isClient && !echo) dismissEchoes()
        super.remove(reason)
    }

    /** Letzte Positionen der Mitkämpfer (für den Vorhalt des Fackelwurfs; Spielertempo ist auf dem Server unzuverlässig). */
    override fun tickBody() {
        if (echo) return
        val present = HashSet<UUID>()
        for (player in fighters()) {
            present.add(player.uuid)
            val track = tracks.getOrPut(player.uuid) { ArrayDeque() }
            track.addLast(player.entityPos)
            while (track.size > TRACK_SPAN + 1) track.removeFirst()
        }
        tracks.keys.retainAll(present)
    }

    // ---------------------------------------------------------------------------------------------
    // Auftritt, Phasen, Sieg
    // ---------------------------------------------------------------------------------------------

    /** Sie tritt aus dem Hekataion: Titelkarte, Musik, eine Zeile, Gleiten an ihren Platz (zusammen 50 Ticks). */
    override fun entrance(): Sequence<Unit> = sequence {
        val players = viewers()
        Cine.title(players, "hha.hekate.title", "hha.hekate.title.sub", 70)
        Cine.music(players, music)
        say("entrance")
        val to = entityPos
        val c = plaza()
        val out = flatNorm(to.subtract(c))
        val from = Vec3d(c.x + out.x * T.ENTRANCE_FROM, to.y, c.z + out.z * T.ENTRANCE_FROM)
        Vfx.rune(sw, danger, flatGround(from), 2.2f, T.ENTRANCE_GLIDE + 6)
        telegraph.sound(HhaSounds.KEY, from, 2f, 0.6f)
        hold(out)
        setPosition(from)
        for (i in 1..T.ENTRANCE_GLIDE) {
            val u = i.toDouble() / T.ENTRANCE_GLIDE
            setPosition(from.lerp(to, u * u * (3.0 - 2.0 * u)))
            yield(Unit)
        }
        hold(null)
        repeat(T.ENTRANCE_HOLD) {
            fighters().minByOrNull { it.squaredDistanceTo(self) }?.let { lookControl.lookAt(it, 30f, 30f) }
            yield(Unit)
        }
    }

    /** Neue Gestalt tritt nach vorn (das Modell dreht den Kopfkranz); Titel und eine Zeile, dann die Uhren der Phase. */
    override fun onPhase(newPhase: Int): Sequence<Unit> = sequence {
        stopMoving()
        close()
        hold(null)
        dismissEchoes()
        triformWindup = false
        kit.act(0)
        val number = newPhase + 1
        Cine.title(viewers(), "hha.hekate.phase.$number", "hha.hekate.phase.$number.sub", 50)
        say(if (newPhase == 1) "phase2" else "phase3")
        telegraph.sound(HhaSounds.KEY, entityPos, 2f, if (newPhase == 1) 1f else 0.7f)
        Vfx.nova(sw, danger, entityPos.add(0.0, 2.0, 0.0), 4f, 12)
        rotation = 0
        val start = sw.time + T.PHASE_TRANSITION
        if (newPhase == 1) nextKeyAt = start + T.KEY_FIRST
        if (newPhase >= 2) {
            nextEclipseAt = start + T.ECLIPSE_FIRST
            sinceTriform = 0
        }
        yieldAll(rest(T.PHASE_TRANSITION))
    }

    /** Sie tritt zur Seite, der Schlüssel dreht sich im Schloss, das Höllentor füllt sich; nach 10 s ist sie fort. */
    override fun victory(): Sequence<Unit> = sequence {
        stopMoving()
        close()
        hold(null)
        dismissEchoes()
        fireUntil = 0L
        kit.act(HekateAction.YIELD)
        say("victory")
        val from = entityPos
        val side = rotate(flatNorm(plaza().subtract(from)), 90.0).multiply(T.YIELD_STEP_DISTANCE)
        val to = from.add(side)
        for (i in 1..T.YIELD_STEP) {
            val u = i.toDouble() / T.YIELD_STEP
            setPosition(from.lerp(to, u * u * (3.0 - 2.0 * u)))
            yield(Unit)
        }
        telegraph.sound(HhaSounds.KEY, entityPos, 2.4f, 0.7f)
        val players = viewers()
        finishVictory()
        HadesGate.openOlympusGate(sw)
        for (player in players) player.sendMessage(Text.translatable("hha.hekate.gate.open").formatted(Formatting.GOLD), false)
        wait(VICTORY_LINE_GAP)
        say("victory2")
        wait(DuelBossEntity.VICTORY_LINGER - T.YIELD_STEP - VICTORY_LINE_GAP)
        Vfx.flare(sw, danger, entityPos.add(0.0, 1.8, 0.0), 2f, 10)
        telegraph.sound(HhaSounds.BLINK, entityPos, 1.6f, 0.6f)
        discard()
    }

    /** „Geheul der Hunde“: Konter gegen Spieler über ihr (PLAN 4.3), wie die Basis, mit Heulen statt Ladeton. */
    override fun counter(): Sequence<Unit> = sequence {
        guard.beginCounter()
        stopMoving()
        kit.act(HekateAction.HOWL)
        val radius = guard.counterRadius().toFloat()
        val group = telegraph.boss()
        val ring = group.ring(flatGround(entityPos), radius, BossDefense.COUNTER_TICKS)
        telegraph.sound(HhaSounds.HOWL, entityPos, 2.2f, 0.9f)
        wait(group.ticks)
        for (player in fighters()) {
            if (!ring.contains(player)) continue
            strike(player, antiAirDamage, BossDefense.Reach.SKY)
            guard.counterHit(player)
        }
        Vfx.nova(sw, danger, entityPos.add(0.0, 2.6, 0.0), radius, 10)
        guard.endCounter()
        yieldAll(rest(T.HOWL_END - T.HOWL_HIT))
        kit.act(0)
    }

    // ---------------------------------------------------------------------------------------------
    // Reihenfolge
    // ---------------------------------------------------------------------------------------------

    override fun createMoves(): List<Move> = listOf(
        Move(HekateAction.TORCH_SWEEP, 0.0..T.SWEEP_REACH, ready = { planned() == HekateAction.TORCH_SWEEP }) { torchSweep(it) },
        Move(HekateAction.TORCH_THROW, ANYWHERE, ready = { planned() == HekateAction.TORCH_THROW }) { torchThrow(it) },
        Move(HekateAction.PATH, ANYWHERE, ready = { planned() == HekateAction.PATH }) { firePath(it) },
        Move(HekateAction.STEP, ANYWHERE, ready = { planned() == HekateAction.STEP }) { thresholdStep(it) },
        Move(HekateAction.KEY_TURN, ANYWHERE, ready = { planned() == HekateAction.KEY_TURN }) { keyTurn() },
        Move(HekateAction.ECLIPSE, ANYWHERE, ready = { planned() == HekateAction.ECLIPSE }) { eclipse() },
        Move(HekateAction.TRIFORM, ANYWHERE, ready = { planned() == HekateAction.TRIFORM }) { triform(it) },
    )

    /** Was als Nächstes dran ist: erst die Uhren (Schlüssel, Mondfinsternis, Dreigestalt), sonst die feste Reihenfolge. */
    private fun planned(): Int {
        val now = sw.time
        val hazardFree = now - lastHazardEnd >= T.HAZARD_GAP && telegraph.canStartHazard()
        if (phase >= 1 && now >= nextKeyAt && hazardFree && lastMove != HekateAction.KEY_TURN) return HekateAction.KEY_TURN
        if (phase >= 2 && now >= nextEclipseAt && hazardFree && lastMove != HekateAction.ECLIPSE) return HekateAction.ECLIPSE
        if (phase >= 2 && sinceTriform >= T.TRIFORM_EVERY && lastMove != HekateAction.TRIFORM) return HekateAction.TRIFORM
        return plannedRegular()
    }

    private fun order(): IntArray = if (phase == 0) ORDER_I else ORDER_II

    /** Der nächste Eintrag der Reihenfolge, der nicht der letzte Angriff ist (den Schwung nur, wenn das Ziel nah ist). */
    private fun plannedRegular(): Int {
        val list = order()
        val far = prey?.let { flatDistance(it) > T.SWEEP_SKIP_DISTANCE } ?: false
        for (k in list.indices) {
            val id = list[(rotation + k) % list.size]
            if (id == lastMove) continue
            if (id == HekateAction.TORCH_SWEEP && far) continue
            return id
        }
        return list[rotation % list.size]
    }

    /** Ein normaler Angriff läuft: Reihenfolge dahinter fortsetzen, für die Dreigestalt mitzählen. */
    private fun consume(id: Int) {
        val list = order()
        for (k in list.indices) {
            if (list[(rotation + k) % list.size] == id) {
                rotation = (rotation + k + 1) % list.size
                break
            }
        }
        if (phase >= 2) sinceTriform++
    }

    // ---------------------------------------------------------------------------------------------
    // 1 Fackelschwung × 2: Kegel 60° halb, Radius 7; W 14, zweiter gespiegelt W 10; S 14; F 22
    // ---------------------------------------------------------------------------------------------

    private fun torchSweep(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        consume(HekateAction.TORCH_SWEEP)
        stopMoving()
        kit.act(HekateAction.TORCH_SWEEP)
        var dir = dirTo(target.entityPos)
        hold(dir)
        val group = telegraph.boss()
        val first = group.cone(flatGround(entityPos), dir, T.SWEEP_RADIUS, T.SWEEP_HALF_ANGLE, T.SWEEP_W)
        telegraph.sound(HhaSounds.TORCH, entityPos, 1.8f, 1.1f)
        wait(first.ticks)
        sweepHit(first, dir)
        // Der Schwung läuft aus, dann der zweite gespiegelt, neu gezielt.
        wait(T.SWEEP_HIT_END - T.SWEEP_HIT)
        if (target.isAlive) dir = turnToward(dir, dirTo(target.entityPos), T.SWEEP_RETARGET)
        hold(dir)
        val second = group.cone(flatGround(entityPos), dir, T.SWEEP_RADIUS, T.SWEEP_HALF_ANGLE, T.SWEEP_W2, Telegraph.Pace.FOLLOW_UP)
        wait(second.ticks)
        sweepHit(second, dir)
        hold(null)
        yieldAll(rest(T.SWEEP_F))
        kit.act(0)
    }

    private fun sweepHit(cone: Telegraph.Cone, dir: Vec3d) {
        for (player in cone.hits(fighters())) strike(player, T.SWEEP_DAMAGE)
        Vfx.crescent(sw, danger, entityPos.add(0.0, 1.6, 0.0), dir, T.SWEEP_RADIUS, 3f, 8)
        telegraph.sound(HhaSounds.WHOOSH, entityPos, 2f, 0.9f)
    }

    // ---------------------------------------------------------------------------------------------
    // 2 Fackelwurf: 2 Kreise Radius 3 (Ziel und Vorhalt), W 20; S 12; Feuer 60 Ticks (S 3 je 10, LOW);
    //   eine Fackel fehlt: offen F 30, bis sie zurückkehrt
    // ---------------------------------------------------------------------------------------------

    private fun torchThrow(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        consume(HekateAction.TORCH_THROW)
        stopMoving()
        kit.act(HekateAction.TORCH_THROW)
        hold(dirTo(target.entityPos))
        wait(T.THROW_TELEGRAPH)
        val aim = clampToPlaza(flatGround(target.entityPos))
        val lead = leadSpot(target, aim)
        hold(dirTo(lead))
        val group = telegraph.boss(pace = Telegraph.Pace.PROJECTILE)
        val near = group.ring(aim, T.THROW_RADIUS, T.THROW_W)
        val ahead = group.ring(lead, T.THROW_RADIUS, T.THROW_W)
        telegraph.sound(HhaSounds.TORCH, entityPos, 1.8f, 1.2f)
        wait(T.THROW_HIT - T.THROW_TELEGRAPH)
        // Wurf: Die linke Fackel fliegt im Bogen zum vorderen Kreis.
        val hand = handPos()
        val torch = prop(ScriptedBolt.spawn(sw, this@HekateEntity, HermesBoltEntity.TORCH, 1f, hand))
        val flight = group.ticks.coerceAtLeast(1)
        for (k in 1..flight) {
            torch?.moveTo(arc(hand, lead.add(0.0, 0.2, 0.0), k.toDouble() / flight, TORCH_ARC))
            yield(Unit)
        }
        torch?.rest()
        for (player in group.hits(fighters())) strike(player, T.THROW_DAMAGE)
        Vfx.shockwave(sw, danger, aim, T.THROW_RADIUS, 10, 1.2f)
        if (flatDist(aim, lead) >= Telegraph.IMPACT_MERGE) Vfx.shockwave(sw, danger, lead, T.THROW_RADIUS, 10, 1.2f)
        telegraph.sound(HhaSounds.IMPACT, lead, 1.8f, 1.3f)
        startFire(listOf(near, ahead))
        // Eine Fackel fehlt: Sie steht offen, bis die Fackel zurück ist.
        hint("hha.hekate.hint.torch")
        open(T.THROW_F, DuelBossEntity.OPEN_FACTOR)
        hold(null)
        wait(T.THROW_F - T.THROW_RETURN)
        val start = torch?.pos ?: lead
        for (k in 1..T.THROW_RETURN) {
            torch?.moveTo(arc(start, handPos(), k.toDouble() / T.THROW_RETURN, TORCH_ARC * 0.6))
            yield(Unit)
        }
        torch?.discard()
        kit.act(0)
    }

    /** Vorhalt: wohin das Ziel in [HekateTimings.THROW_LEAD_TICKS] läuft; steht es still, ein Stück in Blickrichtung. */
    private fun leadSpot(target: ServerPlayerEntity, aim: Vec3d): Vec3d {
        val move = motion(target).multiply(T.THROW_LEAD_TICKS.toDouble())
        var offset = if (move.length() > T.THROW_LEAD_MAX) move.normalize().multiply(T.THROW_LEAD_MAX) else move
        if (offset.length() < T.THROW_LEAD_MIN) offset = flatNorm(target.getRotationVec(1f)).multiply(T.THROW_LEAD_STILL)
        return clampToPlaza(aim.add(offset))
    }

    private fun motion(player: ServerPlayerEntity): Vec3d {
        val track = tracks[player.uuid] ?: return Vec3d.ZERO
        if (track.size < 2) return Vec3d.ZERO
        val d = track.last().subtract(track.first()).multiply(1.0 / (track.size - 1))
        return Vec3d(d.x, 0.0, d.z)
    }

    /** Das Feuer bleibt liegen (Saal-Gefahr; ist der Platz belegt, brennt nichts nach). */
    private fun startFire(spots: List<Telegraph.Ring>) {
        val hazard = telegraph.hazard(reserve = T.THROW_FIRE_TICKS) ?: return
        for (spot in spots) Vfx.flood(sw, danger, spot.center, spot.radius.toFloat(), 0f, T.THROW_FIRE_TICKS)
        fireUntil = sw.time + T.THROW_FIRE_TICKS
        launch(sequence {
            var tick = 0
            while (sw.time < fireUntil && !isRemoved) {
                if (tick % 4 == 0) {
                    val spot = spots[tick / 4 % spots.size].center
                    Fx.spawn(sw, ParticleTypes.SOUL_FIRE_FLAME, spot.x, spot.y + 0.1, spot.z, 1, 1.0, 0.05, 1.0, 0.01)
                }
                if (tick % T.THROW_FIRE_INTERVAL == T.THROW_FIRE_INTERVAL - 1) {
                    for (player in fighters()) {
                        if (spots.any { it.contains(player) }) strike(player, T.THROW_FIRE_DAMAGE, BossDefense.Reach.LOW, floorY())
                    }
                }
                tick++
                yield(Unit)
            }
            hazard.end()
        })
    }

    // ---------------------------------------------------------------------------------------------
    // 3 Feuerpfad: einer der drei Wege brennt, Linie 6 × 26, W 24; S 16; F 16
    // ---------------------------------------------------------------------------------------------

    private fun firePath(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        consume(HekateAction.PATH)
        stopMoving()
        kit.act(HekateAction.PATH)
        val path = nearestPath(target.entityPos)
        val from = flatGround(pathPoint(path, T.PATH_START.toDouble()))
        val to = flatGround(pathPoint(path, T.PATH_LENGTH.toDouble()))
        hold(dirTo(to))
        val line = telegraph.line(from, to, T.PATH_WIDTH, T.PATH_W)
        telegraph.sound(HhaSounds.TORCH, entityPos, 2f, 0.8f)
        wait(line.ticks)
        for (player in line.hits(fighters())) strike(player, T.PATH_DAMAGE)
        Vfx.beam(sw, danger, from.add(0.0, 0.4, 0.0), to.add(0.0, 0.4, 0.0), 1.6f, 12)
        telegraph.sound(HhaSounds.RIFT, to.lerp(from, 0.5), 2f, 1.2f)
        hold(null)
        yieldAll(rest(T.PATH_F))
        kit.act(0)
    }

    // ---------------------------------------------------------------------------------------------
    // 4 Schlüsseldrehung: der ganze Platz füllt sich, genau ein Weg hat goldenen Rand; W 50; S 22 SKY; F 30
    // ---------------------------------------------------------------------------------------------

    private fun keyTurn(): Sequence<Unit> = sequence {
        nextKeyAt = sw.time + T.KEY_INTERVAL
        stopMoving()
        val choices = (0..2).filter { it != lastKeyPath }
        val path = choices[random.nextInt(choices.size)]
        val c = flatGround(plaza())
        val dir = pathDir(path)
        val end = flatGround(pathPoint(path, T.PATH_LENGTH.toDouble()))
        // Jeder muss den goldenen Weg zu Fuß erreichen können; sonst wird die Ansage länger.
        val ticks = escapeTicks(T.KEY_W) { segmentDistance(it.entityPos, c, end) - (T.PATH_WIDTH * 0.5 - EDGE_MARGIN) }
        val hazard = telegraph.hazard(pace = Telegraph.Pace.ROOM)
        if (hazard == null) {
            nextKeyAt = sw.time + RETRY
            return@sequence
        }
        lastKeyPath = path
        // Rot: alles außer einem schmalen Keil um den Weg (Kegel nach hinten); Gold darüber: der sichere Weg.
        val half = (180.0 - Math.toDegrees(atan(T.PATH_WIDTH * 0.5 / T.PATH_LENGTH.toDouble()))).toFloat()
        val room = hazard.cone(c, dir.multiply(-1.0), T.PATH_LENGTH, half, ticks)
        val safe = hazard.safeLine(c, end, T.PATH_WIDTH, ticks)
        hint("hha.hekate.hint.key")
        telegraph.sound(HhaSounds.KEY, entityPos, 2.2f, 0.8f)
        hold(dir)
        // Die Drehung fällt genau mit dem Treffer: Bei verlängerter Ansage hebt sie den Schlüssel entsprechend später.
        wait((hazard.ticks - T.KEY_W).coerceAtLeast(0))
        kit.act(HekateAction.KEY_TURN)
        wait(hazard.ticks)
        for (player in fighters()) {
            if (room.contains(player) && !safe.contains(player)) strike(player, T.KEY_DAMAGE, BossDefense.Reach.SKY)
        }
        Vfx.shockwave(sw, danger, c, T.PATH_LENGTH, 16, 1.6f)
        telegraph.roomShake(c, 2.5f)
        telegraph.sound(HhaSounds.KEY, c, 2.6f, 0.6f)
        hazard.end()
        lastHazardEnd = sw.time
        hold(null)
        yieldAll(rest(T.KEY_F))
        kit.act(0)
    }

    // ---------------------------------------------------------------------------------------------
    // 5 Schwellentritt: verschwindet in einer Rune, erscheint an einem Wegende; Linie 2,4 durch das Ziel zur Mitte,
    //   W 20; S 18; F 24
    // ---------------------------------------------------------------------------------------------

    private fun thresholdStep(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        consume(HekateAction.STEP)
        stopMoving()
        kit.act(HekateAction.STEP)
        hold(dirTo(target.entityPos))
        wait(T.STEP_VANISH)
        Vfx.rune(sw, danger, flatGround(entityPos), 1.8f, T.STEP_TELEPORT - T.STEP_VANISH + 4)
        telegraph.sound(HhaSounds.BLINK, entityPos, 1.6f, 0.7f)
        wait(T.STEP_TELEPORT - T.STEP_VANISH)
        val end = standAt(pathPoint(stepPath(target.entityPos), T.PATH_LENGTH.toDouble()))
        setPosition(end)
        Vfx.rune(sw, danger, flatGround(end), 1.8f, T.STEP_APPEAR - T.STEP_TELEPORT + 6)
        wait(T.STEP_APPEAR - T.STEP_TELEPORT)
        // Die Linie geht vom Wegende durch das Ziel Richtung Mitte.
        val aim = flatGround(target.entityPos)
        val toAim = flatDist(end, aim)
        val dir = if (toAim < 2.0) flatNorm(plaza().subtract(end)) else flatNorm(aim.subtract(end))
        val length = (toAim + T.STEP_OVERSHOOT).coerceIn(T.STEP_MIN_LENGTH, T.PATH_LENGTH.toDouble())
        val tip = clampToPlaza(flatGround(end.add(dir.multiply(length))))
        hold(dir)
        val line = telegraph.line(flatGround(end), tip, T.STEP_LINE_WIDTH, T.STEP_W, Telegraph.Pace.HEAVY)
        telegraph.sound(HhaSounds.CHARGE, end, 1.6f, 1.2f)
        wait(line.ticks)
        for (player in line.hits(fighters())) strike(player, T.STEP_DAMAGE)
        Vfx.beam(sw, danger, end.add(0.0, 1.4, 0.0), tip.add(0.0, 1.4, 0.0), 0.6f, 8)
        telegraph.sound(HhaSounds.WHOOSH, end, 2f, 1.1f)
        // Der Stoß trägt sie durch die Linie (nicht in das Hekataion hinein).
        val stop = outsideStatue(standAt(tip))
        val lunge = T.STEP_HIT_END - T.STEP_HIT
        for (i in 1..lunge) {
            setPosition(end.lerp(stop, i.toDouble() / lunge))
            yield(Unit)
        }
        yieldAll(rest(T.STEP_F))
        hold(null)
        kit.act(0)
    }

    /** Das Wegende „hinter“ dem Ziel (vom Platz aus gesehen); steht es zu nah daran, das nächstbeste. */
    private fun stepPath(target: Vec3d): Int {
        val c = plaza()
        val out = flatNorm(target.subtract(c))
        val ranked = (0..2).sortedByDescending { pathDir(it).dotProduct(out) }
        return ranked.firstOrNull { flatDist(pathPoint(it, T.PATH_LENGTH.toDouble()), target) >= STEP_MIN_GAP } ?: ranked.first()
    }

    // ---------------------------------------------------------------------------------------------
    // 6 Dreigestalt: drei Körper an den Wegenden, eine Gruppe (Fackel-Kegel, Schlüssel-Linie, Dolch-Kreis), W 30,
    //   S 16 je Form; Treffer auf die Echte in der Ansage: Trugbilder verwehen, STAGGER F 70 offen
    // ---------------------------------------------------------------------------------------------

    private fun triform(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        sinceTriform = 0
        stopMoving()
        dismissEchoes()
        val forms = mutableListOf(0, 1, 2).also { it.shuffle(java.util.Random(random.nextLong())) }
        val realPath = random.nextInt(3)
        // Die Echte wechselt an ihr Wegende, die Trugbilder erscheinen an den beiden anderen.
        Vfx.flare(sw, danger, entityPos.add(0.0, 1.8, 0.0), 1.6f, 8)
        telegraph.sound(HhaSounds.BLINK, entityPos, 1.8f, 0.8f)
        val bodies = arrayOfNulls<HekateEntity>(3)
        for (i in 0..2) {
            val spot = standAt(pathPoint(i, T.PATH_LENGTH.toDouble()))
            if (i == realPath) {
                setPosition(spot)
                form = forms[i]
                bodies[i] = this@HekateEntity
            } else {
                bodies[i] = spawnEcho(spot, forms[i])
            }
            Vfx.rune(sw, danger, flatGround(spot), 1.6f, T.TRIFORM_SETUP + T.TRIFORM_W)
        }
        hold(flatNorm(plaza().subtract(entityPos)))
        hint("hha.hekate.hint.triform")
        wait(T.TRIFORM_SETUP)
        for (body in bodies) body?.kit?.act(HekateAction.TRIFORM)
        // Eine Attacke, eine Gruppe: Jeder Körper sagt seine Form an.
        val group = telegraph.boss()
        val shapes = ArrayList<Telegraph.Shape>()
        val aim = clampToPlaza(flatGround(target.entityPos))
        for (i in 0..2) {
            val body = bodies[i] ?: continue
            val spot = flatGround(body.entityPos)
            val inward = flatNorm(plaza().subtract(spot))
            shapes += when (forms[i]) {
                0 -> group.cone(spot, inward, T.TRIFORM_CONE_RADIUS, T.TRIFORM_CONE_HALF_ANGLE, T.TRIFORM_W)
                1 -> group.line(spot, flatGround(spot.add(inward.multiply(T.TRIFORM_LINE_LENGTH.toDouble()))), T.TRIFORM_LINE_WIDTH, T.TRIFORM_W)
                else -> group.ring(aim, T.TRIFORM_CIRCLE_RADIUS, T.TRIFORM_W)
            }
        }
        telegraph.sound(HhaSounds.TORCH, entityPos, 2.2f, 0.7f)
        triformBroken = false
        triformWindup = true
        val broken = window(group.ticks) { triformBroken || isRemoved }
        triformWindup = false
        if (broken && triformBroken) {
            // Erkannt: Die Trugbilder verwehen und nur die Ansagen der Dreigestalt mit ihnen (andere Effekte, auch die
            // der Rüstung, bleiben stehen); sie taumelt offen. Ein liegendes Bodenfeuer brennt weiter wie angesagt.
            group.cancel()
            dismissEchoes()
            kit.act(HekateAction.STAGGER)
            say("broken")
            hint("hha.hekate.hint.stagger")
            telegraph.sound(HhaSounds.IMPACT, entityPos, 2f, 1.4f)
            open(T.TRIFORM_STAGGER_F, DuelBossEntity.OPEN_FACTOR)
            hold(null)
            wait(T.TRIFORM_STAGGER_F)
            kit.act(0)
            return@sequence
        }
        for (shape in shapes) {
            for (player in shape.hits(fighters())) strike(player, T.TRIFORM_DAMAGE)
        }
        for (i in 0..2) {
            val body = bodies[i] ?: continue
            val spot = flatGround(body.entityPos)
            val inward = flatNorm(plaza().subtract(spot))
            when (forms[i]) {
                0 -> Vfx.crescent(sw, danger, spot.add(0.0, 1.4, 0.0), inward, T.TRIFORM_CONE_RADIUS, 3f, 8)
                1 -> Vfx.beam(sw, danger, spot.add(0.0, 1.2, 0.0), spot.add(inward.multiply(T.TRIFORM_LINE_LENGTH.toDouble())).add(0.0, 1.2, 0.0), 0.6f, 8)
                else -> Vfx.shockwave(sw, danger, aim, T.TRIFORM_CIRCLE_RADIUS, 10, 1.2f)
            }
        }
        telegraph.sound(HhaSounds.IMPACT, aim, 2f, 1.1f)
        wait(T.TRIFORM_END - T.TRIFORM_HIT)
        dismissEchoes()
        hold(null)
        kit.act(0)
    }

    private fun spawnEcho(at: Vec3d, shape: Int): HekateEntity? {
        val echo = HhaEntities.HEKATE.create(sw, SpawnReason.EVENT) ?: return null
        echo.echo = true
        echo.form = shape
        val face = flatNorm(plaza().subtract(at))
        val yaw = (Math.toDegrees(atan2(face.z, face.x)) - 90.0).toFloat()
        echo.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0f)
        echo.headYaw = yaw
        echo.bodyYaw = yaw
        echo.home = at
        echo.post = at
        sw.spawnEntity(echo)
        echoes.add(echo)
        Vfx.flare(sw, danger, at.add(0.0, 1.8, 0.0), 1.4f, 8)
        return echo
    }

    // ---------------------------------------------------------------------------------------------
    // 7 Mondfinsternis (Saal-Gefahr): der Himmel wird dunkel, 2 goldene Kreise Radius 5 sicher, W 50; S 24 SKY
    // ---------------------------------------------------------------------------------------------

    private fun eclipse(): Sequence<Unit> = sequence {
        nextEclipseAt = sw.time + T.ECLIPSE_INTERVAL
        stopMoving()
        val spots = eclipseSpots()
        val ticks = escapeTicks(T.ECLIPSE_W) { p -> spots.minOf { flatDist(p.entityPos, it) } - (T.ECLIPSE_SAFE_RADIUS - EDGE_MARGIN) }
        val hazard = telegraph.hazard(pace = Telegraph.Pace.ROOM)
        if (hazard == null) {
            nextEclipseAt = sw.time + RETRY
            return@sequence
        }
        val safe = spots.map { hazard.safe(it, T.ECLIPSE_SAFE_RADIUS, ticks) }
        hint("hha.hekate.hint.eclipse")
        // Stimmung, keine Blindheit: Der Himmel über dem Platz wird nachtblau-violett (Mondfinsternis).
        Vfx.skyMood(sw, Vfx.Style.MOON, plaza(), T.ECLIPSE_MOOD, ticks + MOOD_TAIL, plazaRadius().toFloat() + MOOD_MARGIN)
        telegraph.sound(HhaSounds.HOWL, entityPos, 2.4f, 0.6f)
        wait((hazard.ticks - T.ECLIPSE_W).coerceAtLeast(0))
        kit.act(HekateAction.ECLIPSE)
        wait(hazard.ticks)
        for (player in fighters()) {
            if (safe.none { it.contains(player) }) strike(player, T.ECLIPSE_DAMAGE, BossDefense.Reach.SKY)
        }
        Vfx.shockwave(sw, danger, flatGround(plaza()), plazaRadius().toFloat(), 18, 1.4f)
        telegraph.sound(HhaSounds.IMPACT, plaza(), 2.6f, 0.6f)
        hazard.end()
        lastHazardEnd = sw.time
        yieldAll(rest(T.ECLIPSE_HIT_END - T.ECLIPSE_HIT + ECLIPSE_RECOVER))
        kit.act(0)
    }

    /**
     * Zwei Stellen für die goldenen Kreise: auf den Wegen oder dazwischen, weit genug auseinander; von den Paaren mit dem
     * kürzesten Weg für den am schlechtesten stehenden Spieler eines der besten drei (etwas Zufall).
     */
    private fun eclipseSpots(): List<Vec3d> {
        val c = plaza()
        val mid = (T.ECLIPSE_SAFE_MIN + T.ECLIPSE_SAFE_MAX) * 0.5
        val candidates = ArrayList<Vec3d>()
        for (angle in OlympusLayout.C_PATH_ANGLES) {
            candidates += flatGround(c.add(OlympusLayout.dir(angle).multiply(T.ECLIPSE_SAFE_MIN + 2.0)))
            candidates += flatGround(c.add(OlympusLayout.dir(angle).multiply(T.ECLIPSE_SAFE_MAX - 1.0)))
            candidates += flatGround(c.add(OlympusLayout.dir(angle + 60.0).multiply(mid)))
        }
        val players = fighters()
        val pairs = ArrayList<Pair<Vec3d, Vec3d>>()
        for (a in candidates.indices) for (b in a + 1 until candidates.size) {
            if (flatDist(candidates[a], candidates[b]) >= T.ECLIPSE_SAFE_SPREAD) pairs += candidates[a] to candidates[b]
        }
        if (pairs.isEmpty()) return candidates.take(T.ECLIPSE_SAFE_COUNT)
        val ranked = pairs.sortedBy { (a, b) ->
            players.maxOfOrNull { min(flatDist(it.entityPos, a), flatDist(it.entityPos, b)) } ?: 0.0
        }
        val pick = ranked[random.nextInt(min(SPOT_CHOICES, ranked.size))]
        return listOf(pick.first, pick.second)
    }

    // ---------------------------------------------------------------------------------------------
    // Geometrie des Dreiwegs
    // ---------------------------------------------------------------------------------------------

    private fun plaza(): Vec3d = arena?.center ?: anchor()

    private fun plazaRadius(): Double = arena?.radius ?: T.PATH_LENGTH.toDouble()

    private fun floorY(): Double = arena?.floorY ?: anchor().y

    /** [pos] auf der Fläche des Platzes (für Ansagen). */
    private fun flatGround(pos: Vec3d): Vec3d = Vec3d(pos.x, floorY() + 0.05, pos.z)

    /** [pos] als Standort auf dem Platz. */
    private fun standAt(pos: Vec3d): Vec3d = Vec3d(pos.x, floorY(), pos.z)

    private fun pathDir(i: Int): Vec3d = OlympusLayout.dir(OlympusLayout.C_PATH_ANGLES[i])

    private fun pathPoint(i: Int, along: Double): Vec3d = plaza().add(pathDir(i).multiply(along))

    /** Der Weg, auf dem [pos] steht bzw. dem es am nächsten ist. */
    private fun nearestPath(pos: Vec3d): Int = (0..2).minBy {
        segmentDistance(pos, pathPoint(it, T.PATH_START.toDouble()), pathPoint(it, T.PATH_LENGTH.toDouble()))
    }

    /** Innerhalb des Platzes halten. */
    private fun clampToPlaza(pos: Vec3d, margin: Double = 1.5): Vec3d {
        val c = plaza()
        val limit = plazaRadius() - margin
        val d = flatDist(pos, c)
        if (d <= limit) return pos
        val k = limit / d
        return Vec3d(c.x + (pos.x - c.x) * k, pos.y, c.z + (pos.z - c.z) * k)
    }

    /** Nicht im Hekataion stehen bleiben (3 × 3 um die Mitte). */
    private fun outsideStatue(pos: Vec3d): Vec3d {
        val c = plaza()
        val d = flatDist(pos, c)
        if (d >= STATUE_CLEAR) return pos
        val out = if (d < 1e-3) facing() else Vec3d((pos.x - c.x) / d, 0.0, (pos.z - c.z) / d)
        return Vec3d(c.x + out.x * STATUE_CLEAR, pos.y, c.z + out.z * STATUE_CLEAR)
    }

    /** Ungefähr die linke Hand (Fackel `torch_l`). */
    private fun handPos(): Vec3d {
        val forward = facing()
        val left = Vec3d(forward.z, 0.0, -forward.x)
        return entityPos.add(forward.multiply(0.5)).add(left.multiply(0.7)).add(0.0, HAND_HEIGHT, 0.0)
    }

    /**
     * Ansagezeit für eine saalweite Gefahr mit sicherer Stelle: mindestens [base], sonst so lange, dass der am
     * schlechtesten stehende Spieler sie zu Fuß erreicht (wie bei der Styx-Flut), höchstens `ESCAPE_MAX`.
     */
    private fun escapeTicks(base: Int, gap: (ServerPlayerEntity) -> Double): Int {
        val walk = fighters().maxOfOrNull { gap(it).coerceAtLeast(0.0) } ?: 0.0
        val needed = BossTimings.Min.REACTION + ceil(walk * 1.1 / BossTimings.Min.ESCAPE_SPEED).toInt()
        return max(base, needed).coerceAtMost(BossTimings.Min.ESCAPE_MAX)
    }

    /** Eine Zeile Hekates (Text; eine eigene Sprecherin fehlt in `Cine`, darum vorerst die Erzählstimme). */
    private fun say(key: String) {
        Cine.dialog(viewers(), SPEAKER, "hha.hekate.line.$key", LINE_TICKS)
    }

    /** Waagerechter Abstand. */
    private fun flatDist(a: Vec3d, b: Vec3d): Double = hypot(a.x - b.x, a.z - b.z)

    private fun segmentDistance(p: Vec3d, a: Vec3d, b: Vec3d): Double {
        val vx = b.x - a.x
        val vz = b.z - a.z
        val len2 = vx * vx + vz * vz
        val t = if (len2 < 1e-9) 0.0 else (((p.x - a.x) * vx + (p.z - a.z) * vz) / len2).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + vx * t), p.z - (a.z + vz * t))
    }

    companion object {
        private val ECHO: TrackedData<Boolean> = DataTracker.registerData(HekateEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        private val FORM: TrackedData<Int> = DataTracker.registerData(HekateEntity::class.java, TrackedDataHandlerRegistry.INTEGER)

        private val T = HekateTimings

        /** Feste Reihenfolge (PLAN 5.2): I Schwung → Wurf → Pfad; II und III Schritt → Schwung → Wurf → Pfad. */
        private val ORDER_I = intArrayOf(HekateAction.TORCH_SWEEP, HekateAction.TORCH_THROW, HekateAction.PATH)
        private val ORDER_II = intArrayOf(HekateAction.STEP, HekateAction.TORCH_SWEEP, HekateAction.TORCH_THROW, HekateAction.PATH)

        /** Angriffe, die sie von überall auf dem Platz beginnt. */
        private val ANYWHERE = 0.0..64.0

        /** Sprecherin der Textzeilen (Name und Stimm-Laute im Client, `CinePlayer`). */
        private const val SPEAKER = Cine.SPEAKER_HEKATE
        private const val LINE_TICKS = 80
        private const val VICTORY_LINE_GAP = 50

        /** Über so viele Ticks wird die Bewegung der Spieler gemittelt (Vorhalt). */
        private const val TRACK_SPAN = 6

        /** Bogenhöhe der fliegenden Fackel. */
        private const val TORCH_ARC = 2.5
        private const val HAND_HEIGHT = 2.3

        /** Ein sicherer Bereich gilt als erreicht, wenn man so weit hinein steht (wie `reachTicks` bei Hermes). */
        private const val EDGE_MARGIN = 0.6

        /** Belegter Platz: so viele Ticks später erneut versuchen. */
        private const val RETRY = 40L

        /** Schwellentritt: Das Wegende soll mindestens so weit vom Ziel weg sein. */
        private const val STEP_MIN_GAP = 6.0

        /** Abstand zur Mitte, ab dem sie das Hekataion nicht mehr berührt. */
        private const val STATUE_CLEAR = 2.6

        /** Mondfinsternis: Stimmung klingt so lange nach, über den Platz hinaus so weit. */
        private const val MOOD_TAIL = 30
        private const val MOOD_MARGIN = 20f
        private const val ECLIPSE_RECOVER = 10
        private const val SPOT_CHOICES = 3

        fun attributes(): DefaultAttributeContainer.Builder = guardianAttributes()
    }
}
