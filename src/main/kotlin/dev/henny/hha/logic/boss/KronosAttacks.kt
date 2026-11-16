package dev.henny.hha.logic.boss

import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.CoreEntity
import dev.henny.hha.entity.boss.CoreHandler
import dev.henny.hha.entity.boss.HadesPose
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.entity.boss.KronosPose
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import net.minecraft.block.Blocks
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundEvent
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Was Kronos tut (Plan 4.5), der härteste Kampf. Er ist langsam und riesig und schlägt in **vier festen Ketten**, die
 * man lernt; gewählt wird nach der Entfernung, nie dieselbe zweimal hintereinander:
 *
 * - A „Ernte“ (nah): Sichelhieb rechts→links, Sichelhieb links→rechts, Spalthieb von oben; dann steckt die Harpe (offen).
 * - B „Äon“ (nah): Äonenschlag, drei Ringe zum Überspringen (Phase II: vier), Sichelhieb.
 * - C „Wurf“ (fern): Die Harpe fliegt als Modell eine Linie hin, steht, und fliegt dieselbe Linie zurück (offen).
 * - D „Zeitschritt“ (fern): Rune am Ziel, er erscheint dort, Sichelhieb, Spalthieb; dann steckt die Harpe (offen).
 *
 * Dazu fest geplante Zeit-Höhepunkte: Uhrzeiger (alle 45 s), Stillstand (alle 60 s, versetzt) und die Rückspulung an
 * Lebensschwellen, bei der man Sanduhren zerschlagen muss (2 allein, 3 ab zwei Spielern). In Phase II verschlingt er
 * nach jeder zweiten Kette, der Saal altert oder lässt Zeitsand fallen, und Hades hilft mit seinen Ketten.
 *
 * Farbe: Gefahr ist AEON (Indigo/Sternweiß); Gold heißt nur Sanduhr, offen oder sicher. Kein Slowness über II.
 */
class KronosAttacks(private val fight: HadesFight) : CoreHandler {

    private val world get() = fight.world
    private val center get() = fight.center
    private val calls get() = fight.kronosCalls

    private var poiseBroken = false

    /** Zuletzt gelaufene Kette ('A' … 'D'): nie dieselbe zweimal hintereinander. */
    private var lastChain = ' '

    /** Ketten seit dem letzten Verschlingen (Phase II). */
    private var sinceDevour = 0

    // Feste Uhren der Phase (in Ticks von [HadesFight.stageAge]).
    private var handsAt = 0
    private var haltAt = 0
    private var chainedAt = 0
    private var hazardAt = 0
    private var hazardTurn = 0

    /** Rückspulungen, die an einer Lebensschwelle fällig geworden sind, und wie viele schon liefen. */
    private var rewindsDue = 0
    private var rewindsDone = 0

    /** Die eine laufende Saal-Gefahr (Phase II, siehe [hazardSlot]). */
    private var hazard: Iterator<Unit>? = null

    /** Läuft gerade ein Zeit-Höhepunkt? Dann beginnt keine Saal-Gefahr. */
    private var alone = false

    /** Hades legt gerade Ketten an; solange lässt ihn [allyFollow] stehen. */
    private var allyBusy = false

    /** Sanduhren der laufenden Rückspulung. */
    private val glasses = ArrayList<CoreEntity>()

    fun reset() {
        poiseBroken = false
        lastChain = ' '
        sinceDevour = 0
        hazard = null
        alone = false
        allyBusy = false
        for (glass in glasses) glass.discard()
        glasses.clear()
    }

    fun breakPoise() {
        poiseBroken = true
    }

    fun onStageStart(number: Int) {
        handsAt = K.HANDS_FIRST
        haltAt = K.HALT_FIRST
        chainedAt = K.CHAINED_FIRST
        hazardAt = K.HAZARD_FIRST
        hazardTurn = 0
        rewindsDue = 0
        rewindsDone = 0
        sinceDevour = 0
        if (number == 5) fight.addTask(allyFollow())
    }

    /** Vom Kampf nach jedem Treffer: Rückspulung bei 70 % und 35 % der Phase I, bei 50 % der Phase II. */
    fun onHealth(left: Float) {
        val marks = if (fight.stage == 5) K.REWIND_AT_2 else K.REWIND_AT_1
        while (rewindsDone + rewindsDue < marks.size && left < marks[rewindsDone + rewindsDue]) rewindsDue++
    }

    /** Jeden Tick der Phase (außerhalb von Szenen): der Platz für Saal-Gefahren. */
    fun tick() {
        if (fight.stage == 5) hazardSlot()
    }

    // -------------------------------------------------------------------------------------------------
    // Ablauf
    // -------------------------------------------------------------------------------------------------

    private fun pause(): Int = if (fight.stage == 5) (K.PAUSE * K.PHASE2_PAUSE_FACTOR).toInt() else K.PAUSE

    private fun handsDue() = fight.stageAge >= handsAt
    private fun haltDue() = fight.stageAge >= haltAt
    private fun chainedDue() = fight.stage == 5 && fight.stageAge >= chainedAt && fight.boss?.isRemoved == false

    private fun highlightDue() = rewindsDue > 0 || handsDue() || haltDue()

    /** Die nächste Handlung: ein fälliger Höhepunkt, sonst heranschreiten und die passende Kette. */
    fun next(): Sequence<Unit> = sequence {
        val entity = fight.kronos ?: return@sequence
        alone = false
        allyBusy = false
        val free = hazard == null
        when {
            poiseBroken -> {
                poiseBroken = false
                yieldAll(staggered(K.POISE_STAGGER))
            }
            free && rewindsDue > 0 -> {
                rewindsDue--
                rewindsDone++
                yieldAll(solo(rewind()))
            }
            chainedDue() -> yieldAll(hadesChains())
            free && handsDue() && (!haltDue() || handsAt <= haltAt) -> yieldAll(solo(clockHands()))
            free && haltDue() -> yieldAll(solo(standstill()))
            fight.stage == 5 && sinceDevour >= K.DEVOUR_EVERY -> {
                sinceDevour = 0
                yieldAll(devour())
            }
            else -> {
                val target = fight.nearestTarget(entity.entityPos) ?: return@sequence
                yieldAll(stride(target.uuid))
                val after = fight.nearestTarget(entity.entityPos) ?: return@sequence
                val near = hypot(after.x - entity.x, after.z - entity.z) < K.SWEEP_RADIUS
                val chain = when {
                    near && lastChain == 'A' -> 'B'
                    near && lastChain == 'B' -> 'A'
                    near -> if (world.random.nextBoolean()) 'A' else 'B'
                    lastChain == 'C' -> 'D'
                    lastChain == 'D' -> 'C'
                    else -> if (world.random.nextBoolean()) 'C' else 'D'
                }
                lastChain = chain
                sinceDevour++
                yieldAll(
                    when (chain) {
                        'A' -> harvest()
                        'B' -> aeon()
                        'C' -> harpeThrow()
                        else -> timeStep()
                    },
                )
            }
        }
        // PLAN 4.3: Wer ihn von oben bearbeitet, bekommt statt der Pause den Konter. Pausen sonst nach Spielerzahl (4.1).
        if (fight.counterDue()) yieldAll(antiAir()) else wait(BossScaling.pause(pause(), fight.crowd))
    }

    /**
     * Der Konter gegen Spieler über ihm (PLAN 4.3): ein Aufwärtshieb der Harpe ([KronosPose.SWEEP_L] mit
     * [dev.henny.hha.entity.boss.KronosEntity.antiAir]). Kreis um ihn, [BossDefense.COUNTER_TICKS] Ticks, Höhe unbegrenzt;
     * wer darin ist, bekommt [ANTI_AIR_DAMAGE] und wird nach unten geworfen. Danach ist er wachsam.
     */
    private fun antiAir(): Sequence<Unit> = sequence {
        val entity = fight.kronos ?: return@sequence
        val guard = fight.guard ?: return@sequence
        guard.beginCounter()
        entity.antiAir = true
        entity.pose = KronosPose.SWEEP_L
        val ring = calls.ring(floorAt(entity.entityPos), guard.counterRadius().toFloat(), BossDefense.COUNTER_TICKS)
        sound(HhaSounds.CHARGE, entity.entityPos, 2.4f, 0.9f)
        wait(ring.ticks)
        for (player in ring.hits(fight.targets())) {
            fight.hurt(player, ANTI_AIR_DAMAGE, reach = BossDefense.Reach.SKY)
            guard.counterHit(player)
        }
        sound(HhaSounds.DASH, entity.entityPos, 3f, 0.6f)
        guard.endCounter()
        wait(BossDefense.COUNTER_TICKS)
        entity.antiAir = false
        entity.pose = KronosPose.IDLE
    }

    /** Ein Zeit-Höhepunkt: Solange er läuft, beginnt keine Saal-Gefahr. */
    private fun solo(body: Sequence<Unit>): Sequence<Unit> = sequence {
        alone = true
        yieldAll(body)
        alone = false
    }

    // -------------------------------------------------------------------------------------------------
    // Hilfen
    // -------------------------------------------------------------------------------------------------

    private suspend fun SequenceScope<Unit>.wait(ticks: Int) {
        repeat(ticks) { yield(Unit) }
    }

    /** Jeder Schlag eines Titanen ist deutlich härter als die seines Sohnes. */
    private fun strike(player: ServerPlayerEntity, base: Float, magic: Boolean = false, reach: BossDefense.Reach = BossDefense.Reach.BODY) =
        fight.hurt(player, base * POWER, magic, reach)

    private fun sound(event: SoundEvent, at: Vec3d, volume: Float = 2.5f, pitch: Float = 1f) {
        calls.sound(event, at, volume, pitch)
    }

    private fun floorAt(pos: Vec3d) = Vec3d(pos.x, center.y, pos.z)

    private fun flatDir(from: Vec3d, to: Vec3d): Vec3d? {
        val d = Vec3d(to.x - from.x, 0.0, to.z - from.z)
        return if (d.lengthSquared() < 1e-4) null else d.normalize()
    }

    private fun shove(player: ServerPlayerEntity, from: Vec3d, strength: Double, up: Double) {
        val away = flatDir(from, player.entityPos) ?: Vec3d(0.0, 0.0, 1.0)
        Fx.launchPlayer(player, away.multiply(strength).add(0.0, up, 0.0))
    }

    private fun forward(): Vec3d {
        val rad = Math.toRadians((fight.kronos?.yaw ?: 0f).toDouble())
        return Vec3d(-sin(rad), 0.0, cos(rad))
    }

    private fun faceNearest() {
        val entity = fight.kronos ?: return
        fight.nearestTarget(entity.entityPos)?.let { fight.faceTitan(it.entityPos) }
    }

    /** Er steht offen: goldene Rune, die Treffer zählen mehr. */
    private fun markOpen(ticks: Int) {
        val entity = fight.kronos ?: return
        fight.openingFor = ticks
        Vfx.rune(world, Vfx.Style.GOLD, entity.entityPos.add(0.0, 0.08, 0.0), 5f, ticks, entity)
        sound(HhaSounds.CHIME, entity.entityPos, 2.6f, 0.6f)
        fight.hint("hha.kronos.hint.opening", Formatting.GOLD)
    }

    /** Taumeln (offen), dann richtet er sich auf, ohne jemanden wegzustoßen. */
    private fun staggered(ticks: Int): Sequence<Unit> = sequence {
        val entity = fight.kronos ?: return@sequence
        entity.pose = KronosPose.STAGGER
        sound(HhaSounds.ROAR, entity.entityPos, 3.5f, 0.4f)
        markOpen(ticks)
        wait(ticks)
        entity.pose = KronosPose.ROAR
        wait(K.RISE)
        entity.pose = KronosPose.IDLE
    }

    /** Schwere, langsame Schritte; jeder ist leise zu hören, gerüttelt wird nicht. */
    private fun stride(id: UUID): Sequence<Unit> = sequence {
        val entity = fight.kronos ?: return@sequence
        entity.pose = KronosPose.IDLE
        val speed = if (fight.stage == 5) 0.42 else 0.34
        var step = 0
        while (step++ < K.STRIDE_MAX) {
            val target = world.getPlayerByUuid(id) ?: fight.nearestTarget(entity.entityPos) ?: return@sequence
            val to = Vec3d(target.x - entity.x, 0.0, target.z - entity.z)
            if (to.length() < STOP_AT) break
            entity.setPosition(fight.clampToHall(entity.entityPos.add(to.normalize().multiply(speed)), 3.0))
            fight.faceTitan(target.entityPos)
            if (step % K.STRIDE_STEP == 0) sound(HhaSounds.IMPACT, entity.entityPos, 0.8f, 0.4f)
            yield(Unit)
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Die Hiebe
    // -------------------------------------------------------------------------------------------------

    /** Sichelhieb: Kegel 100° halb, Radius 13, S 24. Rechts→links ([KronosPose.SWEEP], W 22) oder Rückhand (W 16). */
    private fun sickle(group: Telegraph.Fight.Group, backhand: Boolean): Sequence<Unit> = sequence {
        val entity = fight.kronos ?: return@sequence
        faceNearest()
        entity.pose = if (backhand) KronosPose.SWEEP_L else KronosPose.SWEEP
        val cone = group.cone(
            floorAt(entity.entityPos), forward(), K.SWEEP_RADIUS, K.SWEEP_HALF_ANGLE,
            if (backhand) K.SWEEP_L_WINDUP else K.SWEEP_WINDUP, Telegraph.Pace.HEAVY,
        )
        sound(HhaSounds.CHARGE, entity.entityPos, 2.4f, if (backhand) 0.6f else 0.5f)
        wait(cone.ticks)
        for (player in cone.hits(fight.targets())) {
            if (!strike(player, 24f)) continue
            shove(player, cone.apex, 1.2, 0.45)
        }
        Vfx.crescent(world, Vfx.Style.AEON, cone.apex.add(cone.direction.multiply(3.0)).add(0.0, 2.2, 0.0), cone.direction, K.SWEEP_RADIUS, 9f, 10)
        sound(HhaSounds.DASH, cone.apex, 3.5f, 0.45f)
        wait(K.SWEEP_STRIKE)
    }

    /** Spalthieb von oben: Linie 3 × 22 vor ihm, W 24, S 30. Danach steckt die Harpe im Boden (offen, F 36). */
    private fun cleave(group: Telegraph.Fight.Group): Sequence<Unit> = sequence {
        val entity = fight.kronos ?: return@sequence
        faceNearest()
        entity.pose = KronosPose.CLEAVE
        val from = floorAt(entity.entityPos)
        val to = fight.clampToHall(from.add(forward().multiply(K.CLEAVE_LENGTH.toDouble())), 1.0)
        val line = group.line(from, to, K.CLEAVE_WIDTH, K.CLEAVE_WINDUP, Telegraph.Pace.HEAVY)
        sound(HhaSounds.CHARGE, from, 3f, 0.4f)
        wait(line.ticks)
        for (player in line.hits(fight.targets())) {
            if (!strike(player, 30f)) continue
            shove(player, from, 0.8, 0.6)
        }
        Vfx.shockwave(world, Vfx.Style.AEON, from.lerp(to, 0.35), 4f, 12, 2.4f)
        calls.hitShake(from, 4f)
        sound(HhaSounds.IMPACT, from, 4f, 0.45f)
        wait(K.CLEAVE_STRIKE)
        entity.pose = KronosPose.STUCK
        markOpen(K.STUCK_WINDOW)
        wait(K.STUCK_WINDOW)
        sound(HhaSounds.WHOOSH, from, 2.4f, 0.5f)
        entity.pose = KronosPose.IDLE
    }

    /** Kette A „Ernte“: Sichelhieb → Rückhand → Spalthieb → Harpe steckt. */
    private fun harvest(): Sequence<Unit> = sequence {
        val group = calls.boss(pace = Telegraph.Pace.HEAVY)
        yieldAll(sickle(group, backhand = false))
        yieldAll(sickle(group, backhand = true))
        yieldAll(cleave(group))
    }

    /**
     * Kette B „Äon“: Äonenschlag (Kreis 8, W 20, S 26), dann laufen drei Ringe (Phase II: vier) im Abstand von 16 Ticks
     * nach außen (S 16, überspringen), und während die Ringe laufen, steht er auf und holt zum Sichelhieb aus. F 20.
     */
    private fun aeon(): Sequence<Unit> = sequence {
        val entity = fight.kronos ?: return@sequence
        faceNearest()
        entity.pose = KronosPose.SLAM
        val spot = floorAt(entity.entityPos)
        val group = calls.boss()
        val slam = group.ring(spot, K.SLAM_RADIUS, K.SLAM_WINDUP)
        sound(HhaSounds.CHARGE, spot, 3f, 0.45f)
        wait(slam.ticks)
        for (player in slam.hits(fight.targets())) {
            // Äonenschlag: am Boden (LOW), wie der Bodenschlag seines Sohnes.
            if (!fight.grounded(player) || !strike(player, 26f, reach = BossDefense.Reach.LOW)) continue
            shove(player, spot, 1.3, 0.7)
        }
        Vfx.shockwave(world, Vfx.Style.AEON, spot, K.SLAM_RADIUS, 16, 3f)
        calls.hitShake(spot, 4f)
        sound(HhaSounds.IMPACT, spot, 4f, 0.45f)
        val rings = if (fight.stage == 5) K.SLAM_RINGS_PHASE2 else K.SLAM_RINGS
        for (ring in 0 until rings) fight.addTask(expandingRing(spot, ring * K.SLAM_RING_GAP, ring))
        // Die Ringe gehören zur Attacke: Die Gruppe bleibt, bis der letzte den Rand erreicht.
        group.hold((rings - 1) * K.SLAM_RING_GAP + ((fight.safeRadius - K.SLAM_RADIUS) / K.SLAM_RING_SPEED).toInt() + 1)
        wait(K.SLAM_END - K.SLAM_WINDUP)
        yieldAll(sickle(group, backhand = false))
        wait(K.AEON_WINDOW)
        entity.pose = KronosPose.IDLE
    }

    /** Ein Ring läuft vom Einschlag nach außen; wer am Boden steht, wenn er durchläuft, bekommt S 16. */
    private fun expandingRing(from: Vec3d, delay: Int, index: Int): Sequence<Unit> = sequence {
        wait(delay)
        val struck = HashSet<UUID>()
        var radius = K.SLAM_RADIUS.toDouble()
        sound(HhaSounds.FLOOD, from, 2.6f, 0.6f)
        while (radius < fight.safeRadius && !fight.isEnded && !fight.inCutscene) {
            // Jeder Ring zeichnet sich in eigenen Ticks, damit sich die Bänder nicht gegenseitig verschlucken.
            if ((world.time + index) % 2L == 0L) {
                Vfx.flood(world, Vfx.Style.AEON, from, (radius + K.SLAM_RING_DEPTH).toFloat(), (radius - K.SLAM_RING_DEPTH).coerceAtLeast(0.0).toFloat(), 3)
            }
            for (player in fight.targets()) {
                val r = hypot(player.x - from.x, player.z - from.z)
                if (abs(r - radius) < K.SLAM_RING_DEPTH && fight.grounded(player) && struck.add(player.uuid)) {
                    // Äonen-Ringe zum Überspringen (LOW).
                    if (strike(player, 16f, magic = true, reach = BossDefense.Reach.LOW)) Fx.launchPlayer(player, Vec3d(0.0, 0.6, 0.0))
                }
            }
            radius += K.SLAM_RING_SPEED
            yield(Unit)
        }
    }

    /**
     * Kette C „Wurf“: Linie Breite 3 (W 22), die Harpe fliegt als Modell 20 Ticks hin, steht 10, fliegt 20 zurück, beide
     * Male über dieselbe Linie (S 18). Der Rückweg wird angesagt, solange sie noch hinfliegt. Leere Hand: offen, F 40.
     */
    private fun harpeThrow(): Sequence<Unit> = sequence {
        val entity = fight.kronos ?: return@sequence
        val target = fight.nearestTarget(entity.entityPos) ?: return@sequence
        fight.faceTitan(target.entityPos)
        entity.pose = KronosPose.THROW
        val origin = entity.entityPos.add(0.0, 3.0, 0.0)
        val away = flatDir(entity.entityPos, target.entityPos) ?: forward()
        val far = fight.clampToHall(target.entityPos.add(away.multiply(6.0)), 2.0).add(0.0, 1.4, 0.0)
        val group = calls.boss(pace = Telegraph.Pace.PROJECTILE)
        val out = group.line(floorAt(origin), floorAt(far), K.THROW_WIDTH, K.THROW_WINDUP)
        sound(HhaSounds.CHARGE, origin, 3f, 0.6f)
        wait(out.ticks)
        entity.harpeOut = true
        entity.pose = KronosPose.EMPTY
        markOpen(K.EMPTY_WINDOW)
        val bolt = fight.puppet(HermesBoltEntity.HARPE, origin, 1f)
        if (bolt != null) Vfx.trail(world, Vfx.Style.AEON, bolt, 0.8f, K.HARPE_OUT + K.HARPE_HOVER + K.HARPE_BACK)
        sound(HhaSounds.SPEAR, origin, 3.5f, 0.55f)
        var line: Telegraph.Line = out
        val struck = HashMap<UUID, Int>()
        fun cut(at: Vec3d, pass: Int) {
            for (player in fight.targets()) {
                if (struck[player.uuid] == pass || !line.contains(player)) continue
                if (hypot(player.x - at.x, player.z - at.z) < 2.0 && abs(player.y + 1.0 - at.y) < 2.6) {
                    struck[player.uuid] = pass
                    strike(player, 18f)
                    shove(player, at, 0.9, 0.4)
                }
            }
        }
        val path = far.subtract(origin)
        val returnAt = K.HARPE_OUT + K.HARPE_HOVER - K.HARPE_RETURN_WINDUP
        var back: Telegraph.Line? = null
        for (i in 1..K.HARPE_OUT) {
            val at = origin.lerp(far, i.toDouble() / K.HARPE_OUT)
            if (bolt != null) fight.steer(bolt, at, path)
            cut(at, 1)
            if (i == returnAt) back = group.line(floorAt(far), floorAt(origin), K.THROW_WIDTH, K.HARPE_RETURN_WINDUP)
            yield(Unit)
        }
        repeat(K.HARPE_HOVER) {
            if (back == null && K.HARPE_OUT + it + 1 == returnAt) back = group.line(floorAt(far), floorAt(origin), K.THROW_WIDTH, K.HARPE_RETURN_WINDUP)
            yield(Unit)
        }
        line = back ?: out
        sound(HhaSounds.WHOOSH, far, 2.4f, 0.7f)
        val hand = entity.entityPos.add(0.0, 3.0, 0.0)
        for (i in 1..K.HARPE_BACK) {
            val at = far.lerp(hand, i.toDouble() / K.HARPE_BACK)
            if (bolt != null) fight.steer(bolt, at, hand.subtract(far))
            cut(at, 2)
            yield(Unit)
        }
        bolt?.discard()
        entity.harpeOut = false
        entity.pose = KronosPose.IDLE
    }

    /**
     * Kette D „Zeitschritt“: Eine Rune am Ziel (W 20, STEP), er erscheint dort, dann Rückhand-Sichelhieb (W 16) und
     * Spalthieb (W 24); dann steckt die Harpe (offen, F 36).
     */
    private fun timeStep(): Sequence<Unit> = sequence {
        val entity = fight.kronos ?: return@sequence
        val target = fight.nearestTarget(entity.entityPos) ?: return@sequence
        fight.faceTitan(target.entityPos)
        entity.pose = KronosPose.STEP
        val spot = fight.clampToHall(target.entityPos, 3.0)
        Vfx.rune(world, Vfx.Style.AEON, spot.add(0.0, 0.06, 0.0), K.STEP_RUNE_RADIUS, K.STEP_WINDUP)
        sound(HhaSounds.BLINK, entity.entityPos, 3f, 0.5f)
        wait(K.STEP_WINDUP)
        Vfx.flare(world, Vfx.Style.AEON, entity.entityPos.add(0.0, 3.0, 0.0), 5f, 8)
        entity.setPosition(spot)
        Vfx.flare(world, Vfx.Style.AEON, spot.add(0.0, 3.0, 0.0), 6f, 10)
        sound(HhaSounds.BLINK, spot, 3f, 0.7f)
        val group = calls.boss(pace = Telegraph.Pace.HEAVY)
        yieldAll(sickle(group, backhand = true))
        yieldAll(cleave(group))
    }

    // -------------------------------------------------------------------------------------------------
    // Die Zeit-Höhepunkte
    // -------------------------------------------------------------------------------------------------

    /**
     * Uhrzeiger (alle 45 s): Er tritt in die Mitte (HANDS), zwei Linien zeigen die Zeiger (W 34), dann laufen sie
     * gegenläufig 120 Ticks über den Boden (S 14, springen). Danach offen (F 40).
     */
    private fun clockHands(): Sequence<Unit> = sequence {
        val entity = fight.kronos ?: return@sequence
        handsAt = fight.stageAge + K.HANDS_INTERVAL
        Vfx.flare(world, Vfx.Style.AEON, entity.entityPos.add(0.0, 3.0, 0.0), 5f, 8)
        entity.setPosition(center)
        entity.yaw = 0f
        entity.pose = KronosPose.HANDS
        sound(HhaSounds.BLINK, center, 3f, 0.4f)
        val reach = fight.safeRadius - 1.0
        val minuteStart = world.random.nextDouble() * Math.PI * 2.0
        val hourStart = minuteStart + Math.PI * (0.6 + world.random.nextDouble() * 0.8)
        val group = calls.boss(pace = Telegraph.Pace.AREA)
        group.line(center, center.add(cos(minuteStart) * reach, 0.0, sin(minuteStart) * reach), K.HANDS_WIDTH, K.HANDS_WINDUP)
        group.line(center, center.add(cos(hourStart) * reach * 0.7, 0.0, sin(hourStart) * reach * 0.7), K.HANDS_WIDTH, K.HANDS_WINDUP)
        fight.hint("hha.kronos.hint.hands", Formatting.BLUE)
        sound(HhaSounds.CHIME, center, 3f, 0.5f)
        wait(group.ticks)
        val lastHit = HashMap<UUID, Int>()
        val turn = Math.PI * 2.0 / K.HANDS_TURN
        for (tick in 0..K.HANDS_SWEEP) {
            val minute = minuteStart + tick * turn
            val hour = hourStart - tick * turn * K.HANDS_HOUR_SPEED
            for ((angle, length) in listOf(minute to reach, hour to reach * 0.7)) {
                val tip = center.add(cos(angle) * length, 0.6, sin(angle) * length)
                if (tick % 2 == 0) Vfx.beam(world, Vfx.Style.AEON, center.add(0.0, 0.6, 0.0), tip, 1.8f, 3)
                for (player in fight.targets()) {
                    if (!fight.grounded(player)) continue
                    val off = Vec3d(player.x - center.x, 0.0, player.z - center.z)
                    val dist = off.length()
                    if (dist > length || dist < 2.0) continue
                    val miss = abs(Math.IEEEremainder(atan2(off.z, off.x) - angle, Math.PI * 2.0)) * dist
                    if (miss < K.HANDS_WIDTH / 2.0 && tick - (lastHit[player.uuid] ?: -99) > K.HANDS_REHIT) {
                        lastHit[player.uuid] = tick
                        // Uhrzeiger zum Überspringen (LOW).
                        if (strike(player, 14f, magic = true, reach = BossDefense.Reach.LOW)) {
                            Fx.launchPlayer(player, Vec3d(-sin(angle) * 0.8, 0.5, cos(angle) * 0.8))
                        }
                    }
                }
            }
            if (tick % 15 == 0) sound(HhaSounds.CHIME, center, 2f, 0.5f + (tick % 60) * 0.01f)
            yield(Unit)
        }
        markOpen(K.HANDS_WINDOW)
        wait(K.HANDS_WINDOW)
        entity.pose = KronosPose.IDLE
    }

    /**
     * Stillstand (alle 60 s): Er hebt die Hand (HALT, 14 Ticks), dann steht die Zeit 40 Ticks: Slowness I
     * für 30 Ticks (kein Bildschirmblitz mehr, PLAN 9.1: nur Cutscenes und Zeus' Phasenwechsel), alle Geschosse halten an, eine Rune folgt jedem Spieler. Wenn die Zeit weiterläuft, erscheinen unter
     * jedem Spieler und an zwei Stellen daneben Kreise (Radius 2,8), die 16 Ticks später einschlagen (S 22). F 14.
     */
    private fun standstill(): Sequence<Unit> = sequence {
        val entity = fight.kronos ?: return@sequence
        haltAt = fight.stageAge + K.HALT_INTERVAL
        faceNearest()
        entity.pose = KronosPose.HALT
        sound(HhaSounds.CHARGE, entity.entityPos, 3.5f, 0.35f)
        wait(K.HALT_RAISE)
        // Die Zeit steht.
        for (player in fight.targets()) {
            // Überarbeitung 2 (PLAN 12.3): höchstens Slowness I, kein Kontrollverlust.
            player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, K.HALT_SLOWNESS, 0, false, false, true))
            Vfx.rune(world, Vfx.Style.AEON, player.entityPos.add(0.0, 0.06, 0.0), K.HALT_RADIUS, K.HALT_FROZEN, player)
        }
        fight.hint("hha.kronos.hint.halt", Formatting.BLUE)
        sound(HhaSounds.THUNDER, center, 4f, 1.6f)
        val frozen = freezeBolts()
        wait(K.HALT_RESUME - K.HALT_RAISE)
        // Sie läuft weiter, und alles holt sich auf einmal nach.
        thaw(frozen)
        sound(HhaSounds.BLINK, center, 3.5f, 1.4f)
        val group = calls.boss()
        val marks = ArrayList<Telegraph.Ring>()
        for (player in fight.targets()) {
            val under = fight.clampToHall(player.entityPos, 2.0)
            marks.add(group.ring(under, K.HALT_RADIUS, K.HALT_IMPACT_DELAY))
            val start = world.random.nextDouble() * Math.PI * 2.0
            repeat(K.HALT_EXTRA_CIRCLES) { k ->
                val a = start + k * Math.PI * 2.0 / 3.0
                marks.add(group.ring(fight.clampToHall(under.add(cos(a) * K.HALT_SPREAD, 0.0, sin(a) * K.HALT_SPREAD), 2.0), K.HALT_RADIUS, K.HALT_IMPACT_DELAY))
            }
        }
        wait(group.ticks)
        val struck = HashSet<UUID>()
        for (mark in marks) {
            for (player in mark.hits(fight.targets())) {
                if (!struck.add(player.uuid)) continue
                // Stillstand-Kreise schlagen von oben ein (SKY).
                if (strike(player, 22f, magic = true, reach = BossDefense.Reach.SKY)) Fx.launchPlayer(player, Vec3d(0.0, 0.9, 0.0))
            }
            Vfx.pillar(world, Vfx.Style.AEON, mark.center, K.HALT_RADIUS * 0.8f, 12f, 12)
        }
        sound(HhaSounds.IMPACT, center, 4f, 0.8f)
        wait(K.HALT_WINDOW)
        entity.pose = KronosPose.IDLE
    }

    private class Frozen(val bolt: HermesBoltEntity, val velocity: Vec3d, val fall: Double)

    /** Hält alle Geschosse im Saal an (sie hängen in der Luft) und merkt sich, wie sie flogen. */
    private fun freezeBolts(): List<Frozen> {
        val hall = net.minecraft.util.math.Box.of(center, 100.0, 70.0, 100.0)
        return world.getEntitiesByClass(HermesBoltEntity::class.java, hall) { it.velocity.lengthSquared() > 1e-6 || it.fall != 0.0 }.map {
            val frozen = Frozen(it, it.velocity, it.fall)
            it.velocity = Vec3d.ZERO
            it.fall = 0.0
            it.velocityDirty = true
            frozen
        }
    }

    private fun thaw(frozen: List<Frozen>) {
        for (f in frozen) {
            if (f.bolt.isRemoved) continue
            f.bolt.velocity = f.velocity
            f.bolt.fall = f.fall
            f.bolt.velocityDirty = true
        }
    }

    /**
     * Rückspulung (bei 70 % und 35 % in Phase I, bei 50 % in Phase II): Er dreht die Harpe wie einen Uhrschlüssel, im
     * Saal stehen 2 Sanduhren (3 ab zwei Spielern), je 72 Leben, 12 s Zeit. Geschafft: Er taumelt lange (offen, F 110).
     * Nicht geschafft: +20 % Leben, und alle stehen wieder dort, wo sie vor 12 s waren.
     */
    private fun rewind(): Sequence<Unit> = sequence {
        val entity = fight.kronos ?: return@sequence
        Vfx.flare(world, Vfx.Style.AEON, entity.entityPos.add(0.0, 3.0, 0.0), 5f, 8)
        entity.setPosition(center.add(0.0, 0.0, -4.0))
        entity.yaw = 0f
        entity.pose = KronosPose.REWIND
        fight.hint("hha.kronos.hint.rewind", Formatting.GOLD)
        sound(HhaSounds.CHIME, center, 4f, 0.35f)
        // Wo alle stehen: Dorthin geht es zurück, wenn er es schafft.
        val past = HashMap<UUID, Vec3d>()
        for (player in fight.targets()) past[player.uuid] = player.entityPos
        val count = if (fight.targets().size >= 2) K.REWIND_GLASSES_GROUP else K.REWIND_GLASSES_SOLO
        val start = world.random.nextDouble() * Math.PI * 2.0
        for (i in 0 until count) {
            val a = start + i * Math.PI * 2.0 / count
            val spot = center.add(cos(a) * K.REWIND_RING, 0.0, sin(a) * K.REWIND_RING)
            val glass = HhaEntities.CORE.create(world, SpawnReason.EVENT) ?: continue
            glass.kind = CoreEntity.HOURGLASS
            glass.handler = this@KronosAttacks
            val hp = GLASS_HEALTH * fight.healthScale
            glass.getAttributeInstance(EntityAttributes.MAX_HEALTH)?.baseValue = hp.toDouble()
            glass.health = hp
            glass.setPosition(spot)
            world.spawnEntity(glass)
            glasses.add(glass)
            Vfx.flare(world, Vfx.Style.GOLD, spot.add(0.0, 1.3, 0.0), 4f, 12)
        }
        for (tick in 0 until K.REWIND_TIME) {
            if (glasses.isEmpty()) break
            if (tick % 20 == 0) {
                sound(HhaSounds.CHIME, center, 2.4f, 0.5f + tick.toFloat() / K.REWIND_TIME)
                for (glass in glasses) Vfx.chain(world, Vfx.Style.AEON, glass, entity.entityPos.add(0.0, 4.0, 0.0), entity, 22)
            }
            yield(Unit)
        }
        if (glasses.isEmpty()) {
            fight.hint("hha.kronos.hint.rewind.broken", Formatting.GOLD)
            yieldAll(staggered(K.REWIND_STAGGER))
            return@sequence
        }
        // Zu spät: Die Zeit läuft zurück.
        for (glass in glasses) glass.discard()
        glasses.clear()
        sound(HhaSounds.THUNDER, center, 4f, 1.4f)
        fight.hint("hha.kronos.hint.rewind.done", Formatting.RED)
        entity.health = (entity.health + entity.maxHealth * REWIND_HEAL).coerceAtMost(entity.maxHealth)
        for (player in fight.targets()) {
            val spot = past[player.uuid] ?: continue
            player.teleport(world, spot.x, spot.y, spot.z, emptySet(), player.yaw, player.pitch, false)
            player.fallDistance = 0.0
        }
        wait(20)
        entity.pose = KronosPose.IDLE
    }

    override fun onCoreDamaged(core: CoreEntity, source: DamageSource, amount: Float): Float {
        if (fight.inCutscene) return 0f
        Vfx.flare(world, Vfx.Style.GOLD, core.entityPos.add(0.0, 1.3, 0.0), 1.6f, 5)
        fight.sound(HhaSounds.DEFLECT, 1.4f, 1.6f, core.entityPos)
        return amount.coerceAtMost(GLASS_MAX_HIT)
    }

    override fun onCoreBroken(core: CoreEntity) {
        glasses.remove(core)
        val at = core.entityPos.add(0.0, 1.3, 0.0)
        core.discard()
        Vfx.nova(world, Vfx.Style.GOLD, at, 6f, 14)
        fight.sound(HhaSounds.THUNDER, 2.6f, 1.6f, at)
    }

    // -------------------------------------------------------------------------------------------------
    // Phase II: Verschlingen, Hades hilft
    // -------------------------------------------------------------------------------------------------

    /**
     * Verschlingen (nach jeder zweiten Kette): Er beugt sich herab und saugt alles heran (Ring Radius 10, 50 Ticks Sog),
     * Biss bei Tick 60: S 34 und Wither 4 s. Gegenspiel: nach außen laufen. Danach offen (F 36).
     */
    private fun devour(): Sequence<Unit> = sequence {
        val entity = fight.kronos ?: return@sequence
        faceNearest()
        entity.pose = KronosPose.DEVOUR
        val mouth = floorAt(entity.entityPos)
        val ring = calls.ring(mouth, K.DEVOUR_RADIUS, K.DEVOUR_BITE)
        fight.hint("hha.kronos.hint.devour", Formatting.BLUE)
        sound(HhaSounds.ROAR, mouth, 4f, 0.3f)
        repeat(K.DEVOUR_PULL) { tick ->
            if (tick % K.DEVOUR_TUG == 0) {
                for (player in fight.targets()) {
                    val pull = flatDir(player.entityPos, mouth) ?: continue
                    Fx.launchPlayer(player, pull.multiply(0.42).add(0.0, player.velocity.y.coerceAtLeast(-0.1), 0.0))
                }
            }
            // Sog: ein kleiner Partikelstoß je Tick, auf einer Spirale nach innen.
            val a = tick * 0.9
            val r = K.DEVOUR_RADIUS * (1.0 - (tick % 12) / 12.0)
            Fx.burst(world, ParticleTypes.REVERSE_PORTAL, mouth.add(cos(a) * r, 1.0, sin(a) * r), 4, 0.2, 0.02)
            yield(Unit)
        }
        wait(ring.remaining)
        for (player in ring.hits(fight.targets())) {
            // Verschlingen (SKY): Sein Biss holt auch, wer über ihm hängt.
            if (!strike(player, 34f, reach = BossDefense.Reach.SKY)) continue
            if (Fx.canAffect(player)) player.addStatusEffect(StatusEffectInstance(StatusEffects.WITHER, K.DEVOUR_WITHER, 0))
            shove(player, mouth, 1.6, 0.8)
        }
        Vfx.nova(world, Vfx.Style.AEON, mouth.add(0.0, 2.0, 0.0), K.DEVOUR_RADIUS, 16)
        calls.hitShake(mouth, 4f)
        sound(HhaSounds.IMPACT, mouth, 4f, 0.35f)
        markOpen(K.DEVOUR_WINDOW)
        wait(K.DEVOUR_WINDOW)
        entity.pose = KronosPose.IDLE
    }

    /** Hades hilft (alle ~40 s): Er wirft dem Vater Ketten um und hält ihn 45 Ticks fest (offen). */
    private fun hadesChains(): Sequence<Unit> = sequence {
        chainedAt = fight.stageAge + K.CHAINED_INTERVAL
        val titan = fight.kronos ?: return@sequence
        val hades = fight.boss ?: return@sequence
        allyBusy = true
        titan.pose = KronosPose.IDLE
        fight.face(titan.entityPos)
        hades.pose = HadesPose.CHAINS
        sound(HhaSounds.CHAIN, hades.entityPos, 3.5f, 0.7f)
        wait(BossTimings.Hades.CHAINS_CAST)
        for (k in 0 until 3) Vfx.chain(world, Vfx.Style.HADES, hades, titan.entityPos.add(0.0, 2.0 + k * 1.5, 0.0), titan, K.CHAINED + 6)
        titan.pose = KronosPose.CHAINED
        fight.hint("hha.kronos.hint.chains", Formatting.GOLD)
        markOpen(K.CHAINED)
        wait(K.CHAINED)
        hades.pose = HadesPose.IDLE
        allyBusy = false
        titan.pose = KronosPose.ROAR
        sound(HhaSounds.ROAR, titan.entityPos, 3.5f, 0.4f)
        wait(K.RISE)
        titan.pose = KronosPose.IDLE
    }

    /** Hades bleibt in Phase II in der Nähe des Vaters, aber nie im Weg. */
    private fun allyFollow(): Sequence<Unit> = sequence {
        while (fight.stage == 5 && !fight.isEnded) {
            val hades = fight.boss
            val titan = fight.kronos
            if (hades != null && titan != null && !fight.inCutscene && !allyBusy) {
                val away = flatDir(titan.entityPos, hades.entityPos) ?: Vec3d(1.0, 0.0, 0.0)
                val goal = fight.clampToHall(titan.entityPos.add(away.multiply(ALLY_DISTANCE)), 3.0)
                val step = Vec3d(goal.x - hades.x, 0.0, goal.z - hades.z)
                if (step.length() > 0.3) hades.setPosition(hades.entityPos.add(step.normalize().multiply(0.25)))
                fight.face(titan.entityPos)
            }
            yield(Unit)
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Phase II: Saal-Gefahren
    // -------------------------------------------------------------------------------------------------

    /**
     * Der eine Platz für Saal-Gefahren in Phase II: Alternder Boden und Zeitsand im Wechsel, alle 16 s, nie während
     * eines Zeit-Höhepunkts.
     */
    private fun hazardSlot() {
        val running = hazard
        if (running != null) {
            if (running.hasNext()) running.next() else hazard = null
            return
        }
        if (alone || highlightDue() || fight.stageAge < hazardAt) return
        val group = calls.hazard() ?: return
        hazardAt = fight.stageAge + K.HAZARD_INTERVAL
        hazard = (if (hazardTurn++ % 2 == 0) agingFloor(group) else timeSand(group)).iterator()
    }

    /**
     * Alternder Boden: ein Keil in AEON (W 60) wird zu Sand und bricht dann 110 Ticks ein. Wer im Loch steckt, bekommt
     * alle 10 Ticks S 10 und wird hinausgeschleudert.
     */
    private fun agingFloor(group: Telegraph.Fight.Group): Sequence<Unit> = sequence {
        if (fight.floorBusy) {
            group.end()
            return@sequence
        }
        fight.floorBusy = true
        val start = world.random.nextDouble() * Math.PI * 2.0
        val half = Math.toRadians(K.AGING_HALF_ANGLE.toDouble())
        val limit = fight.safeRadius - 1.0
        val cells = ArrayList<BlockPos>()
        val bound = limit.toInt()
        for (dx in -bound..bound) {
            for (dz in -bound..bound) {
                val r = hypot(dx.toDouble(), dz.toDouble())
                if (r < 2.0 || r > limit) continue
                val diff = abs(Math.IEEEremainder(atan2(dz.toDouble(), dx.toDouble()) - start, Math.PI * 2.0))
                val pos = fight.floor.add(dx, 0, dz)
                if (diff < half && world.getBlockState(pos.up()).isAir) cells.add(pos)
            }
        }
        val cone = group.cone(center, Vec3d(cos(start), 0.0, sin(start)), limit.toFloat(), K.AGING_HALF_ANGLE, K.AGING_WINDUP)
        group.hold(K.AGING_WINDUP + K.AGING_COLLAPSE + 1)
        for (pos in cells) fight.scar(pos, Blocks.SAND.defaultState)
        fight.hint("hha.kronos.hint.ages", Formatting.BLUE)
        sound(HhaSounds.RIFT, center, 3f, 1.5f)
        wait(cone.ticks)
        for (pos in cells) {
            fight.scar(pos, Blocks.AIR.defaultState)
            fight.scar(pos.down(), Blocks.AIR.defaultState)
            fight.scar(pos.down(2), Blocks.AIR.defaultState)
            fight.scar(pos.down(3), Blocks.SOUL_SOIL.defaultState)
        }
        sound(HhaSounds.IMPACT, center, 4f, 0.5f)
        repeat(K.AGING_COLLAPSE) { tick ->
            if (tick % K.AGING_TICK == 0) {
                for (player in fight.targets()) {
                    if (player.y < center.y - 0.6 && player.y > center.y - 4.5) {
                        strike(player, 10f, magic = true)
                        Fx.launchPlayer(player, Vec3d(0.0, 1.05, 0.0))
                    }
                }
            }
            yield(Unit)
        }
        fight.closeScars()
        group.end()
    }

    private class Grain(val ring: Telegraph.Ring, val bolt: HermesBoltEntity?, val top: Vec3d)

    /** Zeitsand: drei Wellen, je ein Kreis (Radius 2,6) unter jedem Spieler, W 22; aus dem Riss fällt Sand. S 12. */
    private fun timeSand(group: Telegraph.Fight.Group): Sequence<Unit> = sequence {
        val grains = ArrayList<Grain>()
        var tick = 0
        var waves = 0
        sound(HhaSounds.RIFT, center, 3f, 1.3f)
        while (waves < K.SAND_WAVES || grains.isNotEmpty()) {
            if (waves < K.SAND_WAVES && tick == waves * K.SAND_GAP) {
                waves++
                for (player in fight.targets()) {
                    val spot = fight.clampToHall(player.entityPos, 2.0)
                    val ring = group.ring(spot, K.SAND_RADIUS, K.SAND_WINDUP)
                    val top = spot.add(0.0, 18.0, 0.0)
                    grains.add(Grain(ring, fight.puppet(HermesBoltEntity.SAND, top, 1.3f), top))
                }
                sound(HhaSounds.WHOOSH, center, 2.6f, 0.6f)
            }
            var landed = false
            val iterator = grains.iterator()
            while (iterator.hasNext()) {
                val grain = iterator.next()
                val left = grain.ring.remaining
                if (left <= 0) {
                    for (player in grain.ring.hits(fight.targets())) strike(player, 12f, magic = true, reach = BossDefense.Reach.SKY)
                    Vfx.pillar(world, Vfx.Style.AEON, grain.ring.center, K.SAND_RADIUS * 0.8f, 6f, 12)
                    grain.bolt?.discard()
                    iterator.remove()
                    landed = true
                } else {
                    grain.bolt?.let { fight.steer(it, grain.ring.center.lerp(grain.top, left.toDouble() / grain.ring.ticks), DOWN) }
                }
            }
            if (landed) sound(HhaSounds.IMPACT, center, 2.4f, 1.2f)
            tick++
            yield(Unit)
        }
        group.end()
    }

    private companion object {
        val K = BossTimings.Kronos

        /** Er bleibt stehen, wenn das Ziel so nah ist. */
        const val STOP_AT = 10.0

        /** So weit hält sich Hades in Phase II vom Vater. */
        const val ALLY_DISTANCE = 13.0

        /** Sanduhren: Leben (× Spielerzahl wie die Bosse) und höchster Schaden je Schlag. */
        const val GLASS_HEALTH = 72f
        const val GLASS_MAX_HIT = 24f

        /** Nicht geschafft: so viel Leben holt er sich zurück. */
        const val REWIND_HEAL = 0.2f

        /** Schadensfaktor aller Kronos-Angriffe (dazu × 1,2 für Hades und Kronos in `HadesFight.scaledDamage`). */
        const val POWER = 1.5f

        /** Konter: Aufwärtshieb der Harpe, Schaden vor Skalierung (PLAN 4.3; ohne [POWER]). */
        const val ANTI_AIR_DAMAGE = 22f

        val DOWN = Vec3d(0.0, -1.0, 0.0)
    }
}
