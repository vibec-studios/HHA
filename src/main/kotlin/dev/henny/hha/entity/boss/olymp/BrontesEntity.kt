package dev.henny.hha.entity.boss.olymp

import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.DuelBossEntity
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.entity.miniboss.MiniBossKit
import dev.henny.hha.entity.miniboss.ScriptedBolt
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossDefense
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha.logic.boss.timings.BrontesTimings
import dev.henny.hha.world.olymp.OlympusLayout
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.boss.BossBar
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Aktionen des Brontes für `kit.act` (PLAN 10.2). Werte gehen über die Leitung: nicht umnummerieren. */
object BrontesAction {
    const val NONE = 0
    const val HAMMER = 1
    const val SWING = 2
    const val TOSS = 3
    const val BELLOWS = 4
    const val BLINDED = 5
    const val DOUBLE_HAMMER = 6
    const val FORGE = 7
    const val WEDGE_THROW = 8
    const val TONGS_UP = 9
    const val RISE = 10
    const val YIELD = 11
}

/**
 * Brontes, der Donnerer, Kyklop in der Schmiede der Insel D (PLAN 5.3). Langsam, schwer, ein Duell ohne Helfer.
 * Alle Zahlen stehen in [BrontesTimings].
 *
 * - **Hammerschlag** (mittel): Linie 3 × 10, danach läuft ein Riss bis 18 weiter (eigene Ansage). Der Hammer steckt.
 *   In Phase II als **Doppelschlag** (zweiter Hieb mit eigener Ansage, höchstens ± 25° gedreht).
 * - **Rundschwung** (nah): Kegel 100° halb, Radius 7. **Blasebalg** (fern): Kegel 35° halb, Radius 16, schiebt 6 Blöcke.
 * - **Glutwurf** (fern): Kreis am Ziel (`SKY`); der Rohling bleibt 10 s liegen, glüht 8 s und kühlt 2 s ab.
 * - **Fehlschlag** (Können): Liegt ein glühender Rohling in der Linie des Hammerschlags, zerspringt er, Funken fliegen
 *   ihm ins Auge: `BLINDED`, 80 Ticks **offen**. Das ist die einzige Quelle für `BLINDED`; der Riss entfällt dann.
 *   Liegt ein Rohling in seiner Linie zum Ziel, wählt er den Hammer öfter und von weiter weg (Köder).
 * - **Der Amboss** in der Mitte deckt: Hammer und Riss enden an ihm, hinter ihm trifft der Rundschwung nicht. Der
 *   Blasebalg bläst um ihn herum.
 * - **Phase II „Die Esse brennt“** (50 %): Er facht die Esse an. Saal-Gefahr **Roste** alle 14 s: drei der sechs
 *   Sektoren glühen auf (nie dieselben drei zweimal hintereinander), die kühlen haben goldenen Rand (`LOW`).
 *   **Höhepunkt „Der Keil“** bei 35 % und 15 %: drei Glutringe vom Amboss zum Überspringen (`LOW`), dann der Wurf des
 *   Keils (`SKY`), danach leere Hände: offen. Während des Keils glühen keine Roste.
 * - **Konter** gegen Spieler über ihm: Er greift mit der Zange nach oben (`TONGS_UP`).
 *
 * Überarbeitung 3 (P09, nur Darstellung): eigene Effekte je Angriff (`brontes.*`, Client `BrontesFx`), echte Trümmer
 * und glühende Risse am Hammer, Kamera-Ruck statt Rütteln, Hitzeflimmern am Blasebalg und Stoß-Pass am Keil (die
 * einzigen zwei Bildschirm-Pässe des Kampfs), Szenen `brontes.intro`/`brontes.phase2`/`brontes.defeat` als
 * Bühnen-Skripte (`stage/scripts/BrontesStages`). Alle Zahlen der Darstellung stehen unten im `companion`.
 */
class BrontesEntity(type: EntityType<out BrontesEntity>, world: World) : DuelBossEntity(type, world) {

    override val kit = MiniBossKit(this, "entity.hha.brontes", BossBar.Color.RED, Vfx.Style.FIRE)
    override val fightName = "brontes"
    override val danger = Vfx.Style.FIRE
    override val bossId = "brontes"
    override val effectiveHealth = BrontesTimings.EFFECTIVE_HEALTH
    override val phases = listOf(BrontesTimings.PHASE_2)
    override val antiAirAction get() = BrontesAction.TONGS_UP
    override val antiAirDamage get() = BrontesTimings.COUNTER_DAMAGE
    override val yieldAction = BrontesAction.YIELD
    override val entranceAction get() = BrontesAction.RISE
    override val music: SoundEvent get() = HhaSounds.THEME_BRONTES

    /** Szenen laufen als Bühnen-Skripte (PLAN 2.1 „Einbau der Szenen“), nicht über die alten Rahmen. */
    override val cinematic: Boolean get() = false

    /** Ein liegender Rohling (Requisit `INGOT`): seit [since] am Boden; [cold] = abgekühlt, zählt nicht mehr. */
    private class Ingot(var bolt: ScriptedBolt, val pos: Vec3d, val since: Long) {
        var cold = false
    }

    /** Ein Hammerhieb: Richtung, Linie am Boden (am Amboss gekürzt), Länge, ob ein Block (der Amboss) ihn aufhielt. */
    private class Blow(val dir: Vec3d, val from: Vec3d, val to: Vec3d, val length: Double, val blocked: Boolean)

    private val ingots = ArrayList<Ingot>()
    private var attackCount = 0
    private var lastToss = -BrontesTimings.TOSS_EVERY
    private var nextGrate = 0L
    private var grateRunning = false
    private var lastHot: Set<Int> = emptySet()
    private var wedgesDone = 0
    private var wedgeDue = false
    private var wedgeRunning = false
    private var attacksAtWedge = -1
    private var over = false
    private val spoken = HashSet<String>()

    /** Sieg-Stinger schon gesendet (sonst holt der Endzustand der Szene ihn nach, z. B. nach dem Überspringen). */
    private var stingerSent = false

    init {
        kit.stats(BrontesTimings.HEALTH, BrontesTimings.ARMOR, BrontesTimings.ATTACK, BrontesTimings.SPEED)
    }

    override fun chaseSpeed(): Double = BrontesTimings.CHASE_SPEED

    override fun createMoves(): List<Move> {
        val t = BrontesTimings
        return listOf(
            Move(BrontesAction.HAMMER, t.HAMMER_RANGE_MIN..t.HAMMER_RANGE_MAX, t.HAMMER_WEIGHT) { hammer(it) },
            // Köder (gleiche Kennung, also nie zweimal hintereinander): Ein glühender Rohling liegt in der Linie zum Ziel.
            Move(BrontesAction.HAMMER, t.HAMMER_RANGE_MIN..t.BAIT_RANGE_MAX, t.BAIT_WEIGHT, { baited() }) { hammer(it) },
            Move(BrontesAction.SWING, 0.0..t.SWING_RANGE_MAX, t.SWING_WEIGHT) { swing(it) },
            Move(BrontesAction.TOSS, t.TOSS_RANGE_MIN..t.TOSS_RANGE_MAX, t.TOSS_WEIGHT, { tossReady() }) { toss(it) },
            Move(BrontesAction.BELLOWS, t.BELLOWS_RANGE_MIN..t.BELLOWS_RANGE_MAX, t.BELLOWS_WEIGHT) { bellows(it) },
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Auftritt, Phasen, Sieg
    // ---------------------------------------------------------------------------------------------

    /**
     * Auftritt als Szene `brontes.intro` (10 s): Schwarz, ein Hammerschlag hallt (Ton und Ruck im Skript), er kauert in
     * der Glut der Esse (`RISE`) und richtet sich auf, das Feuer schlägt hoch, das Auge öffnet sich, Titelkarte und
     * Zeile im Skript. Endzustand (auch übersprungen): steht, Musik läuft.
     */
    override fun entrance(): Sequence<Unit> = cinematics.staged(INTRO_STAGE, introInner(), finish = {
        kit.act(0)
        Cine.music(viewers(), music)
    })

    private fun introInner(): Sequence<Unit> = sequence {
        stopMoving()
        hold(null)
        wait(INTRO_RISE_AT)
        kit.act(BrontesAction.RISE)
        wait(INTRO_MUSIC_AT - INTRO_RISE_AT)
        Cine.music(viewers(), music)
        wait(INTRO_FLARE_AT - INTRO_MUSIC_AT)
        telegraph.sound(HhaSounds.FORGE, entityPos, 2.4f, 0.6f)
        Vfx.pillar(sw, danger, entityPos, 1.6f, 6f, 30)
        Fx.burst(sw, ParticleTypes.LAVA, entityPos.add(0.0, 0.6, 0.0), 8, 0.8, 0.1)
        repeat(INTRO_STAND_UNTIL - INTRO_FLARE_AT) {
            fighters().minByOrNull { it.squaredDistanceTo(self) }?.let { lookControl.lookAt(it, 30f, 30f) }
            yield(Unit)
        }
        kit.act(0)
    }

    override fun phaseEvent(): Sequence<Unit>? {
        super.phaseEvent()?.let { return it }
        if (over || phase < 1) return null
        val t = BrontesTimings
        val marks = floatArrayOf(t.WEDGE_AT_1, t.WEDGE_AT_2)
        if (!wedgeDue && wedgesDone < marks.size && health <= marks[wedgesDone] * maxHealth && attackCount > attacksAtWedge) {
            wedgeDue = true
        }
        // Während „Der Keil“ läuft keine Saal-Gefahr: Glühen gerade Roste, wartet er, bis sie abgekühlt sind.
        if (wedgeDue && !grateRunning) {
            wedgeDue = false
            wedgesDone++
            return wedge()
        }
        return null
    }

    /**
     * Phase II als Szene `brontes.phase2` (5 s). Endzustand: Thema der Phase II, erste Roste [BrontesTimings.GRATE_FIRST]
     * Ticks nach dem Ende der Szene (wie bisher nach dem Ende des Übergangs).
     */
    override fun onPhase(newPhase: Int): Sequence<Unit>? = if (newPhase != 1) null else cinematics.staged(PHASE2_STAGE, forgeBurns(), finish = {
        kit.act(0)
        Cine.music(viewers(), HhaSounds.THEME_BRONTES_2)
        nextGrate = sw.time + BrontesTimings.GRATE_FIRST
    })

    /** Phase II „Die Esse brennt“: Er dreht sich zur Esse und facht sie mit dem Blasebalg an. Kein Treffer. */
    private fun forgeBurns(): Sequence<Unit> = sequence {
        val t = BrontesTimings
        stopMoving()
        val forge = forgePoint()
        hold(dirTo(forge))
        kit.act(BrontesAction.BELLOWS)
        say("hha.brontes.line.phase2")
        telegraph.sound(HhaSounds.FORGE, entityPos, 2.4f, 0.7f)
        wait(t.BELLOWS_HIT)
        // Er bläst in die Esse: derselbe Funkenstrom wie im Kampf, nur in die Glut (kein Treffer, kein Pass).
        BossFx.play(sw, FX_BELLOWS, danger, mouthPoint(), dirTo(forge), a = flat(entityPos, forge).toFloat().coerceIn(4f, 24f),
            b = PHASE2_BELLOWS_HALF, life = BELLOWS_FX_LIFE, entity = self)
        Vfx.pillar(sw, danger, forge, 1.8f, 6f, 30)
        telegraph.sound(SoundEvents.ITEM_FIRECHARGE_USE, forge, 2.6f, 0.5f)
        wait(t.BELLOWS_END - t.BELLOWS_HIT)
        kit.act(0)
        nextGrate = sw.time + t.GRATE_FIRST
    }

    /**
     * Niederlage als Szene `brontes.defeat` (10 s): Er stellt den Hammer ab und nickt (`YIELD`); Beute und Fortschritt
     * über die Basis wie bisher, zwei Zeilen, Sieg-Stinger, dann weg. Endzustand: `YIELD`, Stinger (falls übersprungen).
     */
    override fun victory(): Sequence<Unit> {
        var audience: List<ServerPlayerEntity> = viewers()
        return cinematics.staged(DEFEAT_STAGE, victoryInner { audience = it }, finish = {
            kit.act(BrontesAction.YIELD)
            if (!stingerSent) {
                stingerSent = true
                Cine.music(audience.filter { !it.isRemoved && !it.isDisconnected }, HhaSounds.VICTORY_OLYMP)
            }
        })
    }

    private fun victoryInner(onAudience: (List<ServerPlayerEntity>) -> Unit): Sequence<Unit> = sequence {
        val t = BrontesTimings
        over = true
        clearIngots()
        stopMoving()
        hold(null)
        kit.act(BrontesAction.YIELD)
        telegraph.sound(SoundEvents.BLOCK_ANVIL_PLACE, entityPos, 1.6f, 0.6f)
        val audience = viewers()
        onAudience(audience)
        Cine.dialog(audience, SPEAKER, "hha.brontes.line.yield", t.LINE_TICKS)
        finishVictory()
        // Sieg-Stinger des Olymp (das Bossthema blendet aus); beim stummen Vorspulen holt ihn der Endzustand nach.
        if (!Cine.muted) {
            stingerSent = true
            Cine.music(audience, HhaSounds.VICTORY_OLYMP)
        }
        wait(t.YIELD_SECOND_LINE)
        Cine.dialog(audience.filter { !it.isRemoved && !it.isDisconnected }, SPEAKER, "hha.brontes.line.yield2", t.LINE_TICKS)
        wait(DuelBossEntity.VICTORY_LINGER - t.YIELD_SECOND_LINE)
        discard()
    }

    override fun onReset() {
        clearIngots()
        grateRunning = false
        wedgeRunning = false
        clearScreenPasses()
    }

    override fun remove(reason: Entity.RemovalReason) {
        clearScreenPasses()
        super.remove(reason)
    }

    /** Beendet Blasebalg-Flimmern und Keil-Stoß bei allen in Reichweite (jeder Kampfausgang, Prüfung R1-m1). */
    private fun clearScreenPasses() {
        val sw = entityWorld as? ServerWorld ?: return
        BossFx.screenClear(sw, entityPos, BossFx.Screen.HEAT_HAZE, range = 96f)
        BossFx.screenClear(sw, entityPos, BossFx.Screen.SHOCK_RIPPLE, range = 96f)
    }

    // ---------------------------------------------------------------------------------------------
    // Konter
    // ---------------------------------------------------------------------------------------------

    /**
     * Konter (PLAN 4.3) wie in der Basis, aber im Takt der Zange: Kreis 12 Ticks, Schnappen bei 12, Halten bis 40.
     */
    override fun counter(): Sequence<Unit> = sequence {
        val t = BrontesTimings
        guard.beginCounter()
        stopMoving()
        hold(null)
        kit.act(BrontesAction.TONGS_UP)
        val radius = guard.counterRadius(antiAirExtraRadius).toFloat()
        val group = telegraph.boss()
        val ring = group.ring(ground(entityPos), radius, BossDefense.COUNTER_TICKS)
        telegraph.sound(HhaSounds.CHARGE, entityPos, 1.6f, 1.2f)
        wait(group.ticks)
        for (player in fighters()) {
            if (!ring.contains(player)) continue
            strike(player, antiAirDamage, BossDefense.Reach.SKY)
            guard.counterHit(player)
        }
        telegraph.sound(SoundEvents.BLOCK_CHAIN_HIT, entityPos, 2f, 0.6f)
        BossFx.sparks(sw, danger, entityPos.add(0.0, height * COUNTER_SPARK_HEIGHT, 0.0), UP, COUNTER_SPARKS, 0.4f, 16)
        guard.endCounter()
        wait(t.TONGS_END - BossDefense.COUNTER_TICKS)
        kit.act(0)
    }

    // ---------------------------------------------------------------------------------------------
    // Hammerschlag, Doppelschlag, Riss, Fehlschlag
    // ---------------------------------------------------------------------------------------------

    /** Hammerschlag (Phase II: Doppelschlag). Trifft er einen glühenden Rohling, folgt der Fehlschlag. */
    private fun hammer(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val t = BrontesTimings
        attackCount++
        val double = phase >= 1
        var dir = dirTo(target.entityPos)
        hold(dir)
        kit.act(if (double) BrontesAction.DOUBLE_HAMMER else BrontesAction.HAMMER)
        telegraph.sound(HhaSounds.CHARGE, entityPos, 2f, 0.5f)
        val group = telegraph.boss()
        var blow = blow(dir)
        var line = group.line(blow.from, blow.to, t.HAMMER_WIDTH, t.HAMMER_W, Telegraph.Pace.HEAVY)
        if (double) {
            wait(line.ticks)
            // Erster Hieb: Ruck 2,5° (die Ansage des zweiten beginnt erst danach im selben Tick).
            if (falls(blow, line, ingotIn(blow), KICK_HAMMER)) {
                group.end()
                yieldAll(blinded())
                return@sequence
            }
            // Er reißt den Hammer sofort wieder hoch; der zweite Hieb hat seine eigene Ansage.
            val aim = if (alive(target)) dirTo(target.entityPos) else dir
            dir = turnToward(dir, aim, t.DOUBLE_MAX_TURN)
            hold(dir)
            blow = blow(dir)
            line = group.line(blow.from, blow.to, t.HAMMER_WIDTH, t.DOUBLE_GAP, Telegraph.Pace.FOLLOW_UP)
        }
        // Kurz vor dem Treffer: Liegt ein glühender Rohling in der Linie, zerschlägt der Hammer ihn, der Riss entfällt.
        // Sonst erscheint die Riss-Ansage jetzt (W 10) und trifft 8 Ticks nach dem Hammer.
        wait(line.remaining - t.CRACK_LEAD)
        val bait = ingotIn(blow)
        val crack = if (bait == null && !blow.blocked) crackLine(group, blow) else null
        wait(line.remaining)
        // Ruck am Hammer nur ohne laufende Riss-Ansage (sonst verworfen, PLAN 2.2); dann kommt er mit dem Riss.
        val kick = if (double) KICK_DOUBLE_2 else KICK_HAMMER
        if (falls(blow, line, bait, if (crack == null) kick else 0f)) {
            group.end()
            yieldAll(blinded())
            return@sequence
        }
        // Der Hammer steckt (F 28): Er zerrt ihn aus dem Boden.
        var tail = if (double) t.DOUBLE_END - t.DOUBLE_HIT2 else t.HAMMER_END - t.HAMMER_HIT
        if (crack != null) {
            // Der Riss frisst sich unter seiner Ansage vom Hammerkopf weiter und erreicht das Ende mit dem Treffer.
            val until = crack.remaining
            BossFx.crack(sw, danger, crack.from, blow.dir, crack.from.distanceTo(crack.to).toFloat(), life = until + CRACK_FX_HOLD, grow = until)
            wait(until)
            tail -= until
            cracks(crack, blow.dir, kick)
        }
        wait(tail)
        kit.act(0)
    }

    /** Hieb in [dir] ab seiner Mitte, höchstens [BrontesTimings.HAMMER_LENGTH] weit, am Amboss gekürzt. */
    private fun blow(dir: Vec3d): Blow {
        val (length, blocked) = reachAlong(entityPos, dir, BrontesTimings.HAMMER_LENGTH.toDouble())
        return Blow(dir, ground(entityPos), ground(entityPos.add(dir.multiply(length))), length, blocked)
    }

    /** Der Riss läuft vom Ende des Hammers weiter bis [BrontesTimings.CRACK_LENGTH] (eigene Ansage, auch am Amboss gekürzt). */
    private fun crackLine(group: Telegraph.Fight.Group, blow: Blow): Telegraph.Line? {
        val t = BrontesTimings
        val start = entityPos.add(blow.dir.multiply(blow.length))
        val (extra, _) = reachAlong(start, blow.dir, (t.CRACK_LENGTH - t.HAMMER_LENGTH).toDouble())
        if (extra < 1.0) return null
        val end = start.add(blow.dir.multiply(extra))
        return group.line(ground(start), ground(end), t.CRACK_WIDTH, t.CRACK_W + t.CRACK_LEAD, Telegraph.Pace.FOLLOW_UP)
    }

    /**
     * Der Hammer fällt: Treffer, Einschlag, Amboss-Klang. Zerschlägt er einen Rohling, ist das der Fehlschlag (true).
     * [kick] = Nicken des Kamera-Rucks in Grad, 0 = keiner (wenn gleich danach eine Ansage läuft, z. B. der Riss).
     */
    private fun falls(blow: Blow, line: Telegraph.Line, bait: Ingot?, kick: Float): Boolean {
        val t = BrontesTimings
        val reach = min(blow.length, t.HAMMER_HEAD)
        val head = ground(entityPos.add(blow.dir.multiply(reach)))
        val struck = line.hits(fighters())
        for (player in struck) {
            strike(player, t.HAMMER_DAMAGE)
            knock(player, blow.dir, 0.5, 0.35)
        }
        // Am Amboss gekürzt: Risse und Trümmer bleiben vor ihm (kein Bild deutet eine Trefferfläche dahinter an).
        val room = if (blow.blocked) (blow.length - reach + ANVIL_MARGIN).toFloat().coerceIn(HAMMER_CRACKS_MIN, HAMMER_CRACKS_RADIUS) else HAMMER_CRACKS_RADIUS
        BossFx.play(sw, FX_HAMMER, danger, head, blow.dir, a = min(room, HAMMER_RING), life = HAMMER_FX_LIFE, impact = true)
        BossFx.cracks(sw, danger, head, room, BossFx.CRACKS_PER_CALL, HAMMER_CRACKS_LIFE)
        // Beim Fehlschlag bringt der zerspringende Rohling die Brocken (Budget 8 je Treffer).
        if (bait == null) BossFx.debris(sw, head, min(room, HAMMER_DEBRIS_RADIUS), HAMMER_DEBRIS)
        telegraph.sound(HhaSounds.ANVIL, head, 2.4f, if (blow.blocked) 1.0f else 0.8f)
        // Tiefer Schlag zum Ruck (zweiter Ton des Ticks); beim Fehlschlag gehört der Platz dem Zersplittern.
        if (bait == null) telegraph.sound(HhaSounds.THUD, head, 2.6f, 0.8f)
        if (kick > 0f) BossFx.kick(sw, head, kick, ticks = KICK_TICKS)
        if (blow.blocked) hint("hha.brontes.hint.anvil")
        val ingot = bait?.takeIf { it.bolt.alive } ?: return false
        shatter(ingot)
        return true
    }

    /**
     * Der Riss bricht auf: Treffer in der Linie, Brocken am Riss, ein Streifen Rauch; der Ruck des Hammers [kick] kommt
     * hier, weil während der Riss-Ansage keiner erlaubt ist.
     */
    private fun cracks(line: Telegraph.Line, dir: Vec3d, kick: Float) {
        val t = BrontesTimings
        val struck = line.hits(fighters())
        for (player in struck) {
            strike(player, t.CRACK_DAMAGE)
            knock(player, dir, 0.4, 0.3)
        }
        BossFx.debris(sw, line.from.lerp(line.to, CRACK_DEBRIS_AT), CRACK_DEBRIS_RADIUS, CRACK_DEBRIS)
        for (i in 0..4) {
            val p = line.from.lerp(line.to, i / 4.0)
            Fx.spawn(sw, ParticleTypes.SMOKE, p.x, p.y + 0.2, p.z, 2, 0.4, 0.1, 0.4, 0.01)
        }
        telegraph.sound(SoundEvents.BLOCK_DEEPSLATE_BREAK, line.to, 2f, 0.6f)
        if (kick > 0f) BossFx.kick(sw, line.from.lerp(line.to, 0.5), kick, ticks = KICK_TICKS)
    }

    /**
     * Der Rohling zerspringt unter dem Hammer: Funken fliegen in sein einziges Auge (14 Lichtstriche zum Auge, ein
     * Glutfaden), Brocken nur, wenn echte Bodenblöcke da sind, ein schwacher Blitz (≤ 0,25), der Schein am Auge.
     */
    private fun shatter(ingot: Ingot) {
        val eye = eyePoint()
        val from = ingot.pos
        for (i in 1..6) {
            val p = from.lerp(eye, i / 7.0)
            Fx.spawn(sw, ParticleTypes.FLAME, p.x, p.y, p.z, 1, 0.08, 0.08, 0.08, 0.01)
        }
        BossFx.sparks(sw, danger, from.add(0.0, 0.3, 0.0), eye.subtract(from), BossFx.SPARKS_PER_CALL, SHATTER_SPARK_SPEED, SHATTER_SPARK_LIFE)
        if (!sw.getBlockState(BlockPos.ofFloored(from.x, from.y - 0.3, from.z)).isAir) BossFx.debris(sw, from, SHATTER_DEBRIS_RADIUS, SHATTER_DEBRIS)
        for (player in viewers()) Vfx.screenFlash(player, danger, BLIND_FLASH, BLIND_FLASH_TICKS)
        Vfx.flare(sw, danger, eye, 1.4f, 8)
        telegraph.sound(SoundEvents.BLOCK_LAVA_EXTINGUISH, from, 2f, 0.7f)
        ingot.bolt.discard()
        ingots.remove(ingot)
    }

    /** Fehlschlag: geblendet, beide Hände vor dem Auge, 80 Ticks offen (goldene Rune). */
    private fun blinded(): Sequence<Unit> = sequence {
        val t = BrontesTimings
        stopMoving()
        hold(null)
        kit.act(BrontesAction.BLINDED)
        hint("hha.brontes.hint.blinded")
        say("hha.brontes.line.blinded")
        // Einen Tick später, damit Amboss- und Zersplitter-Klang nicht dem Ton-Budget zum Opfer fallen.
        wait(1)
        open(t.BLINDED_F, DuelBossEntity.OPEN_FACTOR)
        wait(2)
        telegraph.sound(HhaSounds.ROAR, entityPos, 2.4f, 0.55f)
        // Erreicht sein Leben die Schwelle der Phase, endet das Fenster früher (danach Phasenwechsel bzw. Sieg).
        window(t.BLINDED_F - 3) { finishing() }
        close()
        kit.act(0)
    }

    /** Der nächste glühende Rohling in der Linie von [blow] (Abstand zur Mittellinie ≤ 2, innerhalb der Länge). */
    private fun ingotIn(blow: Blow): Ingot? {
        val floor = defaultFloor()
        return ingots.asSequence()
            .filter { !it.cold && it.bolt.alive && abs(it.pos.y - floor) < 2.5 }
            .map { it to along(it.pos, entityPos, blow.dir) }
            .filter { (ingot, a) -> a in 0.0..blow.length && sideways(ingot.pos, entityPos, blow.dir) <= BrontesTimings.MISFIRE_DISTANCE }
            .minByOrNull { it.second }?.first
    }

    /** Köder: Liegt in seiner Hammerlinie zum jetzigen Ziel ein glühender Rohling? */
    private fun baited(): Boolean {
        val target = prey ?: return false
        if (ingots.none { !it.cold && it.bolt.alive }) return false
        return ingotIn(blow(dirTo(target.entityPos))) != null
    }

    // ---------------------------------------------------------------------------------------------
    // Rundschwung, Blasebalg
    // ---------------------------------------------------------------------------------------------

    /** Rundschwung (nah): Kegel 100° halb, Radius 7. Wer hinter dem Amboss steht, ist gedeckt. */
    private fun swing(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val t = BrontesTimings
        attackCount++
        val dir = dirTo(target.entityPos)
        hold(dir)
        kit.act(BrontesAction.SWING)
        val cone = telegraph.cone(ground(entityPos), dir, t.SWING_RADIUS, t.SWING_HALF_ANGLE, t.SWING_W, Telegraph.Pace.HEAVY)
        telegraph.sound(HhaSounds.WHOOSH, entityPos, 2f, 0.5f)
        wait(cone.ticks)
        val inside = cone.hits(fighters())
        val struck = inside.filter { !covered(it) }
        if (struck.size < inside.size) hint("hha.brontes.hint.anvil")
        for (player in struck) {
            strike(player, t.SWING_DAMAGE)
            knock(player, dirTo(player.entityPos), t.SWING_KNOCK, t.SWING_LIFT)
        }
        // Glutband am Hammerkopf statt Sichel (der Effekt folgt dem Hammer im Modell).
        BossFx.play(sw, FX_SWING, danger, entityPos.add(0.0, height * SWING_FX_HEIGHT, 0.0), dir, a = t.SWING_RADIUS, b = t.SWING_HALF_ANGLE,
            life = SWING_FX_LIFE, entity = self, impact = true)
        if (struck.isNotEmpty()) {
            telegraph.sound(HhaSounds.IMPACT, entityPos, 2f, 0.6f)
            BossFx.kick(sw, entityPos, SWING_KICK_PITCH, yaw = SWING_KICK_YAW, ticks = KICK_TICKS)
        }
        wait(t.SWING_END - t.SWING_HIT)
        kit.act(0)
    }

    /** Blasebalg (fern): Kegel 35° halb, Radius 16, 6 Blöcke Schub nach hinten. Der Amboss deckt hier nicht. */
    private fun bellows(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val t = BrontesTimings
        attackCount++
        val dir = dirTo(target.entityPos)
        hold(dir)
        kit.act(BrontesAction.BELLOWS)
        val apex = ground(entityPos)
        val cone = telegraph.cone(apex, dir, t.BELLOWS_RADIUS, t.BELLOWS_HALF_ANGLE, t.BELLOWS_W, Telegraph.Pace.HEAVY)
        telegraph.sound(HhaSounds.WHOOSH, entityPos, 2f, 0.4f)
        wait(cone.ticks)
        for (player in cone.hits(fighters())) {
            strike(player, t.BELLOWS_DAMAGE)
            knock(player, dirTo(player.entityPos), t.BELLOWS_KNOCK, t.BELLOWS_LIFT)
        }
        // Funkenstrom aus dem Mund in den Kegel und schwaches Hitzeflimmern (erste der zwei Pass-Stellen des Kampfs).
        BossFx.play(sw, FX_BELLOWS, danger, mouthPoint(), dir, a = t.BELLOWS_RADIUS, b = t.BELLOWS_HALF_ANGLE, life = BELLOWS_FX_LIFE,
            entity = self, impact = true)
        val haze = apex.add(dir.multiply(t.BELLOWS_RADIUS / 2.0)).add(0.0, HAZE_HEIGHT, 0.0)
        BossFx.screen(sw, BossFx.Screen.HEAT_HAZE, danger, haze, t.BELLOWS_RADIUS / 2f, HAZE_STRENGTH, t.BELLOWS_END - t.BELLOWS_HIT)
        for (i in 1..6) {
            val d = t.BELLOWS_RADIUS * i / 7.0
            val p = apex.add(dir.multiply(d)).add(0.0, 1.0, 0.0)
            val spread = 0.3 + d * 0.12
            if (i % 2 == 1) Fx.spawn(sw, ParticleTypes.SMOKE, p.x, p.y, p.z, 2, spread, 0.3, spread, 0.02)
        }
        telegraph.sound(SoundEvents.ITEM_FIRECHARGE_USE, entityPos, 2.2f, 0.6f)
        wait(t.BELLOWS_END - t.BELLOWS_HIT)
        kit.act(0)
    }

    // ---------------------------------------------------------------------------------------------
    // Glutwurf und Rohlinge
    // ---------------------------------------------------------------------------------------------

    /** Frühestens jeder dritte Angriff, höchstens zwei Rohlinge gleichzeitig (auch abkühlende zählen). */
    private fun tossReady(): Boolean {
        ingots.removeIf { !it.bolt.alive }
        return attackCount + 1 - lastToss >= BrontesTimings.TOSS_EVERY && ingots.size < BrontesTimings.INGOT_MAX
    }

    /** Glutwurf: Er zieht einen Rohling aus der Esse und wirft ihn im Bogen; Kreis am Ziel, W 22, `SKY`. */
    private fun toss(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val t = BrontesTimings
        attackCount++
        lastToss = attackCount
        hold(dirTo(target.entityPos))
        kit.act(BrontesAction.TOSS)
        telegraph.sound(HhaSounds.FORGE, entityPos, 1.8f, 0.9f)
        val flight = (flatDistance(target) / t.TOSS_SPEED).roundToInt().coerceIn(t.TOSS_FLIGHT_MIN, t.TOSS_FLIGHT_MAX)
        // Der Rohling verlässt die Zange bei 23 (Modell); die Ansage beginnt so, dass sie genau W 22 dauert.
        val lead = (t.TOSS_RELEASE + flight - t.TOSS_W).coerceIn(0, t.TOSS_RELEASE)
        repeat(lead) {
            if (alive(target)) hold(dirTo(target.entityPos))
            yield(Unit)
        }
        val spot = ground(if (alive(target)) target.entityPos else entityPos.add(facing().multiply(8.0)))
        hold(dirTo(spot))
        val ring = telegraph.ring(spot, t.TOSS_RADIUS, t.TOSS_W, pace = Telegraph.Pace.PROJECTILE)
        wait(t.TOSS_RELEASE - lead)
        val from = leftHand()
        val bolt = prop(ScriptedBolt.spawn(sw, self, HermesBoltEntity.INGOT, 1f, from))
        val ingotEntity = entityOf(bolt)
        ingotEntity?.let { BossFx.projectile(sw, danger, it, BossFx.Look.HOT_IRON) }
        telegraph.sound(HhaSounds.WHOOSH, from, 1.6f, 0.8f)
        val rest = spot.add(0.0, t.INGOT_REST_Y, 0.0)
        val ticks = max(1, ring.remaining)
        val lift = 2.0 + flat(from, rest) * 0.15
        for (i in 1..ticks) {
            bolt?.moveTo(arc(from, rest, i.toDouble() / ticks, lift))
            yield(Unit)
        }
        // Einschlag: ein Flächeneffekt, ein Klang. Der Rohling bleibt glühend liegen.
        val struck = ring.hits(fighters())
        for (player in struck) {
            strike(player, t.TOSS_DAMAGE, BossDefense.Reach.SKY)
            knock(player, player.entityPos.subtract(spot), 0.4, 0.4)
        }
        BossFx.play(sw, FX_SPLASH, danger, spot, a = t.TOSS_RADIUS, life = SPLASH_FX_LIFE, impact = true)
        BossFx.debris(sw, spot, t.TOSS_RADIUS * TOSS_DEBRIS_SCALE, TOSS_DEBRIS)
        telegraph.sound(SoundEvents.BLOCK_ANVIL_LAND, spot, 1.4f, 1.3f)
        if (struck.isNotEmpty()) BossFx.kick(sw, spot, TOSS_KICK, ticks = KICK_TICKS)
        if (bolt != null) {
            bolt.rest()
            ingots.add(Ingot(bolt, rest, sw.time))
            // Der liegende Rohling flimmert schwach (kein Bildschirm-Pass), solange er glüht.
            BossFx.play(sw, FX_INGOT, danger, rest, life = t.INGOT_GLOW, entity = ingotEntity)
            hint("hha.brontes.hint.ingot")
        }
        wait(t.TOSS_END - t.TOSS_RELEASE - ticks)
        kit.act(0)
    }

    /** Jeden Tick: Rohlinge glühen, kühlen ab (Wechsel auf den kalten Knochen) und verschwinden; Roste in Phase II. */
    override fun tickBody() {
        tickIngots()
        tickGrates()
    }

    private fun tickIngots() {
        if (ingots.isEmpty()) return
        val t = BrontesTimings
        val now = sw.time
        val iterator = ingots.iterator()
        while (iterator.hasNext()) {
            val ingot = iterator.next()
            val age = now - ingot.since
            when {
                !ingot.bolt.alive -> iterator.remove()
                age >= t.INGOT_LIFE -> {
                    Fx.burst(sw, ParticleTypes.SMOKE, ingot.pos, 4, 0.2, 0.01)
                    ingot.bolt.discard()
                    iterator.remove()
                }
                !ingot.cold && age >= t.INGOT_GLOW -> cool(ingot)
            }
        }
    }

    /** Abkühlen: Der glühende Rohling wird gegen den kalten getauscht (Größe < 1 = Knochen `ingot_cold`), Dampf. */
    private fun cool(ingot: Ingot) {
        ingot.cold = true
        val cold = prop(ScriptedBolt.spawn(sw, self, HermesBoltEntity.INGOT, BrontesTimings.INGOT_COLD_SIZE, ingot.pos))
        ingot.bolt.discard()
        if (cold != null) ingot.bolt = cold
        Fx.burst(sw, ParticleTypes.SMOKE, ingot.pos.add(0.0, 0.2, 0.0), 6, 0.25, 0.02)
        telegraph.sound(SoundEvents.BLOCK_LAVA_EXTINGUISH, ingot.pos, 1.2f, 1.2f)
    }

    private fun clearIngots() {
        for (ingot in ingots) ingot.bolt.discard()
        ingots.clear()
    }

    // ---------------------------------------------------------------------------------------------
    // Phase II: Roste (Saal-Gefahr)
    // ---------------------------------------------------------------------------------------------

    private fun tickGrates() {
        if (over || phase < 1 || nextGrate == 0L || grateRunning || wedgeDue || wedgeRunning) return
        val t = BrontesTimings
        val now = sw.time
        if (now < nextGrate) return
        val group = telegraph.hazard(pace = Telegraph.Pace.ROOM, reserve = t.GRATE_W + t.GRATE_BURN)
        if (group == null) {
            // Der Platz für eine Saal-Gefahr ist belegt: gleich noch einmal versuchen.
            nextGrate = now + 20
            return
        }
        nextGrate = now + t.GRATE_INTERVAL
        grateRunning = true
        launch(grates(group))
    }

    /**
     * Drei der sechs Roste glühen auf (Kegel ab der Ambossmitte, W 40), dann brennen sie 80 Ticks (S 8 je 10 Ticks,
     * `LOW`). Die kühlen Roste tragen solange einen goldenen Rand, eine Spielerbreite nach innen versetzt.
     */
    private fun grates(group: Telegraph.Fight.Group): Sequence<Unit> = sequence {
        val t = BrontesTimings
        val mid = floorPoint(center())
        val floor = defaultFloor()
        val hot = pickHot()
        lastHot = hot
        for (i in hot) group.cone(mid, sectorDir(i), OlympusLayout.GRATE_OUTER.toFloat(), HALF_SECTOR.toFloat(), t.GRATE_W)
        for (i in 0 until OlympusLayout.GRATE_SECTORS) if (i !in hot) rim(group, mid, i, t.GRATE_W + t.GRATE_BURN)
        // Glut unter den heißen Gittern: wächst unter der Ansage, brennt mit ihr (kein Flächeneffekt eines Treffers).
        BossFx.play(sw, FX_GRATES, danger, mid, a = OlympusLayout.GRATE_OUTER.toFloat(), b = OlympusLayout.GRATE_INNER.toFloat(),
            c = t.GRATE_W.toFloat(), d = t.GRATE_BURN.toFloat(), life = t.GRATE_W + t.GRATE_BURN, extra = hot.sorted().joinToString(","))
        telegraph.sound(HhaSounds.FORGE, mid, 2.4f, 1.1f)
        hint("hha.brontes.hint.grates")
        for (k in 0 until t.GRATE_W) {
            if (over) {
                group.end()
                grateRunning = false
                return@sequence
            }
            yield(Unit)
        }
        telegraph.sound(SoundEvents.ITEM_FIRECHARGE_USE, mid, 2.4f, 0.7f)
        group.hold(t.GRATE_BURN)
        for (k in 0 until t.GRATE_BURN) {
            if (over) break
            if (k % 3 == 0) embers(mid, hot)
            if (k % t.GRATE_DAMAGE_INTERVAL == 0) {
                for (player in fighters()) {
                    if (hot.none { inSector(player, mid, it) }) continue
                    strike(player, t.GRATE_DAMAGE, BossDefense.Reach.LOW, floor)
                }
            }
            yield(Unit)
        }
        group.end()
        grateRunning = false
    }

    /** Drei andere Sektoren als beim letzten Mal. */
    private fun pickHot(): Set<Int> {
        val rng = kotlin.random.Random(random.nextLong())
        var pick: Set<Int>
        var tries = 0
        do {
            pick = (0 until OlympusLayout.GRATE_SECTORS).shuffled(rng).take(BrontesTimings.GRATE_HOT).toSet()
            tries++
        } while (pick == lastHot && tries < 10)
        return pick
    }

    /** Goldener Rand eines kühlen Rostes: je Kante eine sichere Linie, nach innen versetzt (Gold ist nie auf der Kante). */
    private fun rim(group: Telegraph.Fight.Group, center: Vec3d, i: Int, ticks: Int) {
        val t = BrontesTimings
        val a0 = i * OlympusLayout.GRATE_SECTOR_DEGREES
        for ((edge, inward) in listOf(a0 to 1.0, a0 + OlympusLayout.GRATE_SECTOR_DEGREES to -1.0)) {
            val e = OlympusLayout.dir(edge)
            // Senkrecht zur Kante, in Richtung wachsender Winkel (+) bzw. zurück (−): immer in den Sektor hinein.
            val n = Vec3d(-e.z, 0.0, e.x).multiply(inward * t.GRATE_RIM_OFFSET)
            val from = center.add(e.multiply(t.GRATE_RIM_FROM)).add(n)
            val to = center.add(e.multiply(t.GRATE_RIM_TO)).add(n)
            group.safeLine(from, to, t.GRATE_RIM_WIDTH, ticks)
        }
    }

    /** Glut und etwas Rauch über den heißen Rosten (ruhig: wenige Partikel alle drei Ticks). */
    private fun embers(center: Vec3d, hot: Set<Int>) {
        for (i in hot) {
            repeat(2) {
                val a = Math.toRadians(i * OlympusLayout.GRATE_SECTOR_DEGREES + 4.0 + random.nextDouble() * (OlympusLayout.GRATE_SECTOR_DEGREES - 8.0))
                val r = 2.5 + random.nextDouble() * (OlympusLayout.GRATE_OUTER - 2.5)
                val x = center.x + cos(a) * r
                val z = center.z + sin(a) * r
                val particle = if (random.nextInt(3) == 0) ParticleTypes.SMOKE else ParticleTypes.FLAME
                Fx.spawn(sw, particle, x, center.y + 0.1, z, 2, 0.4, 0.05, 0.4, 0.01)
            }
        }
    }

    private fun sectorDir(i: Int): Vec3d = OlympusLayout.dir(OlympusLayout.grateAngle(i))

    /** Steht [player] im (gezeigten) Kegel des Sektors [i]? Dieselbe Prüfung wie die Ansage. */
    private fun inSector(player: ServerPlayerEntity, center: Vec3d, i: Int): Boolean =
        Telegraph.inCone(player.entityPos, center, sectorDir(i), OlympusLayout.GRATE_OUTER, HALF_SECTOR, player.width * 0.5)

    // ---------------------------------------------------------------------------------------------
    // Höhepunkt „Der Keil“
    // ---------------------------------------------------------------------------------------------

    /**
     * Er tritt an den Amboss und schlägt dreimal (je W 20): Glutringe bis Radius 8 / 14 / 20 laufen vom Amboss nach außen
     * (`LOW`, überspringen). Dann wirft er den fertigen Keil auf den Spieler, der am weitesten weg steht: Kreis Radius 6,
     * W 24, S 30, `SKY`. Mit leeren Händen ist er danach offen (F 40).
     */
    private fun wedge(): Sequence<Unit> = sequence {
        val t = BrontesTimings
        wedgeRunning = true
        attacksAtWedge = attackCount
        val c = center()
        val side = c.add(flatNorm(entityPos.subtract(c)).multiply(t.ANVIL_STAND))
        var walked = 0
        while (flat(entityPos, side) > 1.0 && walked < t.FORGE_WALK_MAX) {
            walkTo(side, t.FORGE_WALK_SPEED)
            walked++
            yield(Unit)
        }
        stopMoving()
        if (flat(entityPos, side) > 3.0) refreshPositionAndAngles(side.x, side.y, side.z, yaw, 0f)
        hold(dirTo(c))
        kit.act(BrontesAction.FORGE)
        say("hha.brontes.line.wedge")
        hint("hha.brontes.hint.forge")
        val base = floorPoint(c)
        val group = telegraph.boss()
        val rings = floatArrayOf(t.FORGE_RING_1, t.FORGE_RING_2, t.FORGE_RING_3)
        for (k in rings.indices) {
            val ring = group.ring(base, rings[k], t.FORGE_W)
            if (k == 0) telegraph.sound(HhaSounds.FORGE, entityPos, 2.2f, 0.8f)
            wait(ring.ticks)
            telegraph.sound(HhaSounds.ANVIL, c, 2.6f, 0.7f + 0.08f * k)
            // Je Ambossschlag ein Ruck, steigend 1,5° / 2° / 2,5° (vor der nächsten Ring-Ansage im selben Tick).
            BossFx.kick(sw, c, FORGE_KICKS[k], ticks = KICK_TICKS)
            launch(forgeWave(base, rings[k]))
            group.hold(waveTicks(rings[k]) + 2)
            if (finishing()) {
                endWedge()
                return@sequence
            }
        }
        // Er zeigt den fertigen Keil (FORGE bis 84), dann folgt direkt der Wurf.
        wait(t.FORGE_END - t.FORGE_HIT_3)
        val target = fighters().maxByOrNull { it.squaredDistanceTo(self) }
        if (target == null) {
            endWedge()
            return@sequence
        }
        kit.act(BrontesAction.WEDGE_THROW)
        hold(dirTo(target.entityPos))
        val flight = (flatDistance(target) / t.WEDGE_SPEED).roundToInt().coerceIn(t.WEDGE_FLIGHT_MIN, t.WEDGE_FLIGHT_MAX)
        // Der Keil verlässt die Faust bei 25 (Modell); die Ansage beginnt so, dass sie genau W 24 dauert.
        val lead = (t.WEDGE_RELEASE + flight - t.WEDGE_W).coerceIn(0, t.WEDGE_RELEASE)
        repeat(lead) {
            if (alive(target)) hold(dirTo(target.entityPos))
            yield(Unit)
        }
        val spot = ground(if (alive(target)) target.entityPos else entityPos.add(facing().multiply(10.0)))
        hold(dirTo(spot))
        val ring = group.ring(spot, t.WEDGE_RADIUS, t.WEDGE_W, pace = Telegraph.Pace.AREA)
        telegraph.sound(HhaSounds.CHARGE, entityPos, 2.2f, 0.7f)
        wait(t.WEDGE_RELEASE - lead)
        // Abwurf: ab jetzt leere Hände, offen.
        val from = rightHandHigh()
        val bolt = prop(ScriptedBolt.spawn(sw, self, HermesBoltEntity.HAMMER_BOLT, 1f, from))
        // Glutspur am Keil; ohne eigenen Einschlag (impactRadius 0), weil der Keil danach noch steckt.
        entityOf(bolt)?.let { BossFx.projectile(sw, danger, it, BossFx.Look.HOT_IRON) }
        open(t.WEDGE_F, DuelBossEntity.OPEN_FACTOR)
        telegraph.sound(HhaSounds.WHOOSH, from, 2f, 0.6f)
        val goal = spot.add(0.0, 0.6, 0.0)
        val ticks = max(1, ring.remaining)
        for (i in 1..ticks) {
            bolt?.moveTo(from.lerp(goal, i.toDouble() / ticks))
            yield(Unit)
        }
        val struck = ring.hits(fighters())
        for (player in struck) {
            strike(player, t.WEDGE_DAMAGE, BossDefense.Reach.SKY)
            knock(player, player.entityPos.subtract(spot), 0.7, 0.5)
        }
        // Glutsäule und Stoß-Pass (zweite der zwei Pass-Stellen des Kampfs), echte Brocken, Ruck.
        BossFx.play(sw, FX_WEDGE, danger, spot, a = t.WEDGE_RADIUS, life = WEDGE_FX_LIFE, impact = true)
        BossFx.screen(sw, BossFx.Screen.SHOCK_RIPPLE, danger, spot, t.WEDGE_RADIUS * RIPPLE_RADIUS_SCALE, RIPPLE_STRENGTH, RIPPLE_TICKS)
        BossFx.debris(sw, spot, WEDGE_DEBRIS_RADIUS, BossFx.DEBRIS_PER_CALL)
        telegraph.sound(HhaSounds.THUNDER, spot, 2.6f, 0.9f)
        telegraph.sound(HhaSounds.THUD, spot, 2.6f, 0.7f)
        BossFx.kick(sw, spot, KICK_WEDGE, ticks = KICK_TICKS)
        // Der Keil steckt im Boden, solange er offen ist.
        bolt?.rest()
        window(t.WEDGE_END - t.WEDGE_RELEASE - ticks) { finishing() }
        bolt?.discard()
        close()
        endWedge()
    }

    private fun endWedge() {
        kit.act(0)
        wedgeRunning = false
        nextGrate = max(nextGrate, sw.time + BrontesTimings.GRATE_AFTER_WEDGE)
    }

    /**
     * Ein Glutring läuft vom Amboss bis [radius]: Wer am Boden steht, wenn die Front vorbeikommt, wird getroffen. Bild:
     * niedrige Flammenwand genau an der Front (`brontes.ring`); den Ruck gibt es schon am Ambossschlag.
     */
    private fun forgeWave(center: Vec3d, radius: Float): Sequence<Unit> = sequence {
        val t = BrontesTimings
        val floor = defaultFloor()
        val travel = waveTicks(radius)
        BossFx.play(sw, FX_RING, danger, center, a = radius, b = travel.toFloat(), life = travel + RING_FX_TAIL, impact = true)
        val passed = HashSet<UUID>()
        for (step in 1..travel) {
            if (over) return@sequence
            val front = radius * step.toDouble() / travel
            for (player in fighters()) {
                if (player.uuid in passed) continue
                val d = flat(player.entityPos, center)
                if (d > front + player.width * 0.5 || d > radius + player.width * 0.5) continue
                passed.add(player.uuid)
                // Wer oben auf dem Amboss steht, steht unter dem Hammer; sonst trifft der Ring nur am Boden (LOW).
                val onAnvil = d <= OlympusLayout.ANVIL_HALF + 1.2 && floorAt(player.entityPos, 3.0) > floor + 1.0
                val reach = if (onAnvil) BossDefense.Reach.BODY else BossDefense.Reach.LOW
                if (strike(player, t.FORGE_RING_DAMAGE, reach, floor)) {
                    knock(player, player.entityPos.subtract(center), 0.4, 0.35)
                }
            }
            yield(Unit)
        }
    }

    private fun waveTicks(radius: Float): Int = ceil(radius / BrontesTimings.FORGE_WAVE_SPEED).toInt().coerceAtLeast(1)

    // ---------------------------------------------------------------------------------------------
    // Geometrie und Hilfen
    // ---------------------------------------------------------------------------------------------

    /** Mitte der Arena (Amboss); ohne Arena die Mitte seines Platzes. */
    private fun center(): Vec3d = arena?.center ?: anchor()

    /** [pos] auf Höhe des Arenabodens (nicht auf dem Amboss, den ein Strahl von oben träfe). */
    private fun floorPoint(pos: Vec3d): Vec3d = Vec3d(pos.x, defaultFloor() + 0.05, pos.z)

    /** Die Esse im Norden der Schmiede; außerhalb des Olymp (Test-Arena) nördlich der Mitte. */
    private fun forgePoint(): Vec3d {
        val c = center()
        val f = OlympusLayout.FORGE
        val atStation = sw.registryKey == OlympusLayout.WORLD && flat(c, OlympusLayout.D.centerVec) < 4.0
        return if (atStation) Vec3d(f.x + 0.5, f.y.toDouble(), f.z + 0.5) else c.add(0.0, 0.0, -FORGE_TEST_DISTANCE)
    }

    /**
     * Wie weit ein Hieb von [origin] in [dir] reicht, bevor ihn ein Block aufhält (der Amboss, Sichtlinie in
     * [BrontesTimings.COVER_HEIGHT] über dem Boden): Länge und ob er aufgehalten wurde.
     */
    private fun reachAlong(origin: Vec3d, dir: Vec3d, max: Double): Pair<Double, Boolean> {
        val y = defaultFloor() + BrontesTimings.COVER_HEIGHT
        val start = Vec3d(origin.x, y, origin.z)
        val hit = sw.raycast(RaycastContext(start, start.add(dir.multiply(max)), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, self))
        if (hit.type != HitResult.Type.BLOCK) return max to false
        return (flat(start, hit.pos) - 0.2).coerceIn(0.0, max) to true
    }

    /** Steht [player] am Boden hinter dem Amboss (Sichtlinie von ihm aus blockiert)? Dann deckt ihn der Amboss. */
    private fun covered(player: ServerPlayerEntity): Boolean {
        val floor = defaultFloor()
        if (player.y - floor > BrontesTimings.COVER_MAX_FEET) return false
        val y = floor + BrontesTimings.COVER_HEIGHT
        val hit = sw.raycast(
            RaycastContext(Vec3d(x, y, z), Vec3d(player.x, y, player.z), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, self),
        )
        return hit.type == HitResult.Type.BLOCK
    }

    /** Waagerechter Abstand von [pos] zur Linie ab [from] in Richtung [dir] (normiert). */
    private fun sideways(pos: Vec3d, from: Vec3d, dir: Vec3d): Double = abs((pos.x - from.x) * dir.z - (pos.z - from.z) * dir.x)

    /** Steht er an der Schwelle der laufenden Phase (oder vor dem Sieg)? Dann enden lange Fenster früher. */
    private fun finishing(): Boolean {
        val floor = if (phase < phases.size) phases[phase] * maxHealth else 1f
        return health <= floor + 0.01f
    }

    private fun alive(player: ServerPlayerEntity): Boolean = player.isAlive && !player.isRemoved && player.entityWorld === entityWorld

    /** Linke Hand mit der Zange (vor ihm, links, auf Schulterhöhe). */
    private fun leftHand(): Vec3d = entityPos.add(facing().multiply(1.2)).add(rotate(facing(), -90.0).multiply(0.9)).add(0.0, height * 0.63, 0.0)

    /** Rechte Faust hoch über dem Kopf (Keil). */
    private fun rightHandHigh(): Vec3d = entityPos.add(rotate(facing(), 90.0).multiply(0.8)).add(0.0, height * 1.05, 0.0)

    /** Sein einziges Auge (vorn am Kopf). */
    private fun eyePoint(): Vec3d = entityPos.add(facing().multiply(0.7)).add(0.0, height * 0.86, 0.0)

    /** Mund vor dem Kiefer (Ursprung des Blasebalgs; der Client nimmt, wenn möglich, den Anker aus dem Modell). */
    private fun mouthPoint(): Vec3d = entityPos.add(facing().multiply(MOUTH_FORWARD)).add(0.0, height * MOUTH_HEIGHT, 0.0)

    /** Das Wesen hinter einem [ScriptedBolt]. Nur für Effekte (`BossFx.projectile`, Rohling-Flimmern). */
    private fun entityOf(bolt: ScriptedBolt?): Entity? = bolt?.entity

    /** Eine Textzeile (ohne Tonaufnahme), jede höchstens einmal je Kampf. */
    private fun say(key: String) {
        if (!spoken.add(key)) return
        Cine.dialog(viewers(), SPEAKER, key, BrontesTimings.LINE_TICKS)
    }

    companion object {
        /** Sprecher für `Cine.dialog` (Name in Glut-Orange und tiefe Stimm-Laute im Client, `CinePlayer`). */
        const val SPEAKER = Cine.SPEAKER_BRONTES

        /** Halber Öffnungswinkel eines Rost-Sektors (60° / 2). */
        private const val HALF_SECTOR = OlympusLayout.GRATE_SECTOR_DEGREES / 2.0

        /** Außerhalb des Olymp (Test-Arena) liegt die gedachte Esse so weit nördlich der Mitte. */
        private const val FORGE_TEST_DISTANCE = 21.0

        // --- Überarbeitung 3: reine Darstellung (keine Kampfzahl) ---------------------------------------------------
        /** Bühnen-Skripte (`stage/scripts/BrontesStages`). */
        const val INTRO_STAGE = "brontes.intro"
        const val PHASE2_STAGE = "brontes.phase2"
        const val DEFEAT_STAGE = "brontes.defeat"
        /** Effekt-IDs (Client `client/vfx/fx/boss/BrontesFx`). */
        private const val FX_HAMMER = "brontes.hammer"
        private const val FX_SPLASH = "brontes.splash"
        private const val FX_SWING = "brontes.swing"
        private const val FX_BELLOWS = "brontes.bellows"
        private const val FX_INGOT = "brontes.ingot"
        private const val FX_GRATES = "brontes.grates"
        private const val FX_RING = "brontes.ring"
        private const val FX_WEDGE = "brontes.wedge"

        /** Auftritt (Ticks der Szene): kauert ab, Musik ab, Feuer schlägt hoch, steht bis. Szene 200 Ticks. */
        const val INTRO_RISE_AT = 10
        const val INTRO_MUSIC_AT = 30
        const val INTRO_FLARE_AT = 60
        const val INTRO_STAND_UNTIL = 192

        /** Kamera-Ruck (Grad Nicken nach unten; Deckel im Kampf 2,5°, 8 Ticks). */
        const val KICK_TICKS = 6
        const val KICK_HAMMER = 2.5f
        const val KICK_DOUBLE_2 = 2f
        const val KICK_WEDGE = 2.5f
        const val TOSS_KICK = 1.5f
        const val SWING_KICK_PITCH = 0.8f
        const val SWING_KICK_YAW = 1.5f
        val FORGE_KICKS = floatArrayOf(1.5f, 2f, 2.5f)

        /** Hammer: Stoßring, sternförmige Risse, Trümmer (Budget: 6 Risse, 8 Brocken je Treffer). */
        const val HAMMER_RING = 2.6f
        const val HAMMER_FX_LIFE = 44
        const val HAMMER_CRACKS_RADIUS = 5f
        const val HAMMER_CRACKS_MIN = 1.2f
        const val HAMMER_CRACKS_LIFE = 70
        const val HAMMER_DEBRIS = 6
        const val HAMMER_DEBRIS_RADIUS = 2.6f
        /** Am Amboss: so viel Abstand zum Hindernis bleibt für Risse und Trümmer. */
        const val ANVIL_MARGIN = 0.4
        /** Weiterlaufender Riss: sichtbar so lange nach seinem Treffer; Brocken am Riss. */
        const val CRACK_FX_HOLD = 50
        const val CRACK_DEBRIS_AT = 0.6
        const val CRACK_DEBRIS_RADIUS = 1.6f
        const val CRACK_DEBRIS = 2

        /** Fehlschlag: Funken zum Auge, Brocken des Rohlings, Blitz (≤ 0,25, kubisch am Client). */
        const val SHATTER_SPARK_SPEED = 0.7f
        const val SHATTER_SPARK_LIFE = 18
        const val SHATTER_DEBRIS = 6
        const val SHATTER_DEBRIS_RADIUS = 1.4f
        const val BLIND_FLASH = 0.22f
        const val BLIND_FLASH_TICKS = 10

        /** Rundschwung: Höhe des Ursprungs (Anteil der Größe), Lebenszeit des Glutbands. */
        const val SWING_FX_HEIGHT = 0.32
        const val SWING_FX_LIFE = 18

        /** Blasebalg: Mund (vor, Anteil der Höhe), Funkenstrom, Hitzeflimmern (Stärke ≤ 0,35). */
        const val MOUTH_FORWARD = 1.0
        const val MOUTH_HEIGHT = 0.78
        const val BELLOWS_FX_LIFE = 28
        const val HAZE_HEIGHT = 1.2
        const val HAZE_STRENGTH = 0.3f
        const val PHASE2_BELLOWS_HALF = 18f

        /** Glutwurf: Spritzer, Brocken (Anteil des Radius, Zahl). */
        const val SPLASH_FX_LIFE = 40
        const val TOSS_DEBRIS = 4
        const val TOSS_DEBRIS_SCALE = 0.8f

        /** Keil: Glutsäule, Stoß-Pass (Radius × Kreis, Stärke, Ticks), Brocken; Glutringe laufen so lange nach. */
        const val WEDGE_FX_LIFE = 36
        const val RIPPLE_RADIUS_SCALE = 2f
        const val RIPPLE_STRENGTH = 0.5f
        const val RIPPLE_TICKS = 6
        const val WEDGE_DEBRIS_RADIUS = 3.5f
        const val RING_FX_TAIL = 8

        /** Konter: Funken an der Zange über ihm. */
        const val COUNTER_SPARK_HEIGHT = 1.05
        const val COUNTER_SPARKS = 8

        /** Suche des Geschoss-Wesens hinter einem [ScriptedBolt] (Kantenlänge der Suchbox). */
        const val BOLT_SEARCH = 1.2
        val UP: Vec3d = Vec3d(0.0, 1.0, 0.0)

        fun attributes(): DefaultAttributeContainer.Builder = guardianAttributes()
    }
}
