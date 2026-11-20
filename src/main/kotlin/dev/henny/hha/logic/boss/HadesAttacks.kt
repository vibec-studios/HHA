package dev.henny.hha.logic.boss

import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaParticles
import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.HadesPose
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.entity.boss.HermesEntity
import dev.henny.hha.entity.boss.HermesPose
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.sig.HadesFetters
import dev.henny.hha.world.TartarusArenas
import dev.henny.hha.world.TartarusPlans
import dev.henny.hha.world.arena.Level
import dev.henny.hha.world.arena.Part
import net.minecraft.block.Blocks
import net.minecraft.block.ChainBlock
import net.minecraft.entity.SpawnReason
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Was Hades im Kampf tut (Plan 4.4). Er ist ein Duellant: Zwischen zwei Angriffen geht er auf den nächsten Spieler zu
 * und wählt nach der Entfernung aus dem **festen Repertoire der Phase**; nie zweimal denselben Angriff hintereinander.
 * Jeder Angriff hat eine Ansage am Boden ([Telegraph]), einen Treffer und danach ein Fenster, in dem er steht.
 *
 * - Phase I „Der Gastgeber“: Hiebfolge, Ausfallschritt, Bodenschlag, Bident-Wurf; dazu als fester Höhepunkt sein Blick,
 *   vor dem nur die Säulen schützen.
 * - Phase II „Die Pforten der Hölle“: dazu die Fesseln ([HadesFetters], Überarbeitung 4: ersetzen den Zug der
 *   Seelenketten) und Schattenschritt, und der Saal greift mit an: Zug der Toten, Höllenfeuer und Spalten im Wechsel.
 *   Die zwei Pforten am Rand sind reine Kulisse.
 * - Phase III „Das Bündnis“: dazu der Zangenangriff mit Hermes, das Urteil und der Einsturz an den Lebensschwellen;
 *   als Saal-Gefahr nur noch Seelenkette und Zug der Toten.
 *
 * Saal-Gefahren laufen über **einen** Platz ([hazardSlot]): nie zwei gleichzeitig, nie zusammen mit Blick,
 * Schattenschritt, Urteil oder Einsturz. Spätere Phasen werden nur über kürzere Pausen schneller, die Ansagen bleiben.
 *
 * Können lohnt sich: Wer vor einer Säule ausweicht, lässt ihn beim Ausfallschritt hineinrennen; wer hinter einer Säule
 * steht, reißt seine Ketten; nach Bodenschlag, Wurf, Schattenschritt und Urteil steht er offen (goldene Rune,
 * [HadesFight.openingFor]), und zu viel Schaden außerhalb davon bricht seine Haltung.
 *
 * Überarbeitung 4 (P19): Der Saal hat einen Umgang (+2) und das Podest (+3). Körperangriffe treffen beide Höhen
 * ([bodyBand]) und zeigen sich dort, wo jemand oben steht ([echoCone], [echoRing], [echoLine]); Bodenschlag und
 * Spalten bleiben am Hallenboden ([groundBand]). Punkte, die Hades oder Hermes selbst betreten, liegen im Kern
 * ([HadesFight.clampToCore]); Ansagen unter einem Spieler liegen auf seiner Ebene ([HadesFight.onLevel]).
 */
class HadesAttacks(private val fight: HadesFight) {

    private val world get() = fight.world
    private val center get() = fight.center
    private val calls get() = fight.hadesCalls

    /** Der letzte Grundangriff: Derselbe kommt nie zweimal hintereinander. */
    private var last = ""
    private var poiseBroken = false

    // Feste Uhren der Phase (in Ticks von [HadesFight.stageAge]).
    private var gazes = 0
    private var gazeAt = 0
    private var judgeAt = 0
    private var pincerAt = 0
    private var pincerTurn = 0
    private var hazardAt = 0
    private var hazardTurn = 0

    /** Einstürze, die an einer Lebensschwelle fällig geworden sind, und wie viele schon gefallen sind. */
    private var collapsesDue = 0
    private var collapsesDone = 0

    /** Die eine laufende Saal-Gefahr (siehe [hazardSlot]). */
    private var hazard: Iterator<Unit>? = null

    /** Läuft gerade Blick, Schattenschritt, Urteil oder Einsturz? Dann beginnt keine Saal-Gefahr. */
    private var alone = false

    /** Hermes führt gerade seinen Teil der Zange aus; solange lässt ihn [hermesAlly] in Ruhe. */
    private var hermesBusy = false

    /** Die zwei Höllenpforten am Rand: ab Phase II reine Kulisse, aus einer davon kommt der Zug der Toten. */
    private val gates = ArrayList<Vec3d>()
    private var gatesShown = Long.MIN_VALUE

    /** Die Fesseln (Überarbeitung 4, P19): ein Gefesselter höchstens, lebt den ganzen Kampf. */
    private val fetter = HadesFetters.Fetter(fight, FetterMoves())

    fun reset() {
        last = ""
        poiseBroken = false
        hazard = null
        alone = false
        hermesBusy = false
        fetter.abort()
    }

    /** Hält die Fessel [player]? Dann trifft ihn sonst nichts (`HadesFight.hurt`). */
    fun fettered(player: ServerPlayerEntity): Boolean = fetter.shields(player)

    /** Ablauf ersetzt (`HadesFight.interrupt`): Fessel lösen, ohne Folgen. */
    fun abortFetter() = fetter.abort()

    fun breakPoise() {
        poiseBroken = true
    }

    fun onStageStart(number: Int) {
        gazes = 0
        gazeAt = H.GAZE_FIRST
        judgeAt = H.JUDGE_FIRST
        pincerAt = H.PINCER_FIRST
        pincerTurn = 0
        hazardAt = H.HAZARD_FIRST
        hazardTurn = 0
        collapsesDue = 0
        collapsesDone = 0
        if (number >= 2 && gates.isEmpty()) placeGates()
        if (number == 3) fight.addTask(hermesAlly())
    }

    /** Vom Kampf nach jedem Treffer: In Phase III stürzt der Boden an den Lebensschwellen ein (66 % und 33 %). */
    fun onHealth(left: Float) {
        if (fight.stage != 3) return
        while (collapsesDone + collapsesDue < H.COLLAPSE_AT.size && left < H.COLLAPSE_AT[collapsesDone + collapsesDue]) collapsesDue++
    }

    /** Jeden Tick der Phase (außerhalb von Szenen): Kulisse der Pforten und der Platz für Saal-Gefahren. */
    fun tick() {
        if (fight.stage < 2) return
        if (world.time - gatesShown >= H.GATE_REFRESH) showGates()
        hazardSlot()
    }

    // -------------------------------------------------------------------------------------------------
    // Ablauf
    // -------------------------------------------------------------------------------------------------

    private fun pause(): Int = when (fight.stage) {
        3 -> H.PAUSE_3
        2 -> H.PAUSE_2
        else -> H.PAUSE_1
    }

    private fun gazeDue() = fight.stage == 1 && gazes < H.GAZE_PER_PHASE && fight.stageAge >= gazeAt && fight.intactPillars.isNotEmpty()
    private fun judgeDue() = fight.stage == 3 && fight.stageAge >= judgeAt
    private fun pincerDue() = fight.stage == 3 && fight.stageAge >= pincerAt && fight.hermes?.isRemoved == false

    /** Steht ein Höhepunkt an? Dann wartet die nächste Saal-Gefahr, damit er nicht in eine hineinfällt. */
    private fun highlightDue() = collapsesDue > 0 || gazeDue() || judgeDue()

    /** Die nächste Handlung: Höhepunkt, wenn einer fällig ist, sonst anlaufen und einen Grundangriff wählen. */
    fun next(): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        alone = false
        hermesBusy = false
        val free = hazard == null
        when {
            poiseBroken -> {
                poiseBroken = false
                yieldAll(staggered(H.POISE_STAGGER))
            }
            free && collapsesDue > 0 -> {
                collapsesDue--
                yieldAll(solo(collapse()))
            }
            free && gazeDue() -> yieldAll(solo(gaze()))
            free && judgeDue() -> yieldAll(solo(judgement()))
            pincerDue() -> yieldAll(pincer())
            else -> {
                val target = fight.nearestTarget(entity.entityPos) ?: return@sequence
                yieldAll(stride(target.uuid))
                val after = fight.nearestTarget(entity.entityPos) ?: return@sequence
                yieldAll(pick(hypot(after.x - entity.x, after.z - entity.z)))
            }
        }
        // PLAN 4.3: Wer ihn von oben bearbeitet, bekommt statt der Pause den Konter. Pausen sonst nach Spielerzahl (4.1).
        if (fight.counterDue()) yieldAll(antiAir()) else wait(BossScaling.pause(pause(), fight.crowd))
    }

    /**
     * Der Konter gegen Spieler über ihm (PLAN 4.3): Er stößt den Bident senkrecht nach oben ([HadesPose.SLAM] rückwärts
     * gespielt, [dev.henny.hha.entity.boss.HadesEntity.antiAir]). Kreis um ihn, [BossDefense.COUNTER_TICKS] Ticks, Höhe
     * unbegrenzt; wer darin ist, bekommt [ANTI_AIR_DAMAGE] und wird nach unten geworfen. Danach ist er wachsam:
     * Sturztreffer prallen ab, Treffer vom Boden zählen normal.
     */
    private fun antiAir(): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        val guard = fight.guard ?: return@sequence
        guard.beginCounter()
        entity.cloaked = false
        entity.antiAir = true
        entity.pose = HadesPose.SLAM
        val ring = calls.ring(floorAt(entity.entityPos), guard.counterRadius().toFloat(), BossDefense.COUNTER_TICKS)
        sound(HhaSounds.CHARGE, entity.entityPos, 2.2f, 1.2f)
        wait(ring.ticks)
        for (player in ring.hits(fight.targets())) {
            fight.hurt(player, ANTI_AIR_DAMAGE, reach = BossDefense.Reach.SKY)
            guard.counterHit(player)
        }
        sound(HhaSounds.IMPACT, entity.entityPos, 2.6f, 1.1f)
        guard.endCounter()
        wait(BossDefense.COUNTER_TICKS)
        entity.antiAir = false
        entity.pose = HadesPose.IDLE
    }

    /** Ein Höhepunkt: Solange er läuft, beginnt keine Saal-Gefahr. */
    private fun solo(body: Sequence<Unit>): Sequence<Unit> = sequence {
        alone = true
        yieldAll(body)
        alone = false
    }

    /**
     * Wahl nach Entfernung aus dem Repertoire der Phase. Nah: Hiebfolge, Bodenschlag. Mittel: Ausfallschritt.
     * Fern: Bident-Wurf, Ausfallschritt. Ab Phase II auf Abstand die Fesseln (an der Stelle der Seelenketten, gleiche
     * Auswahl) und überall der Schattenschritt (nur, wenn keine Saal-Gefahr läuft).
     */
    private fun pick(distance: Double): Sequence<Unit> {
        val options = ArrayList<Pair<String, Int>>()
        fun add(name: String, weight: Int) {
            if (name != last) options.add(name to weight)
        }
        when {
            distance < NEAR -> {
                add("sweep", 4)
                add("slam", 3)
            }
            distance < FAR -> {
                add("thrust", 4)
                add("throw", 1)
            }
            else -> {
                add("throw", 4)
                add("thrust", 2)
            }
        }
        if (fight.stage >= 2) {
            if (distance >= NEAR) add("chains", 3)
            if (hazard == null) add("ambush", 2)
        }
        var roll = world.random.nextInt(options.sumOf { it.second })
        var chosen = options.last().first
        for ((name, weight) in options) {
            roll -= weight
            if (roll < 0) {
                chosen = name
                break
            }
        }
        last = chosen
        return when (chosen) {
            "sweep" -> sweepCombo()
            "slam" -> slam()
            "thrust" -> lunge()
            "throw" -> bidentThrow()
            // Überarbeitung 4: die Fesseln. Als Höhepunkt (`solo`): Solange sie läuft, beginnt keine Saal-Gefahr.
            "chains" -> solo(fetter.cast())
            else -> solo(ambush())
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Hilfen
    // -------------------------------------------------------------------------------------------------

    private suspend fun SequenceScope<Unit>.wait(ticks: Int) {
        repeat(ticks) { yield(Unit) }
    }

    /** Ein Ton des Gegners, mit dem Budget aus Plan 3.3 (höchstens zwei je Tick). */
    private fun sound(event: SoundEvent, at: Vec3d, volume: Float = 2.5f, pitch: Float = 1f) {
        calls.sound(event, at, volume, pitch)
    }

    private fun floorAt(pos: Vec3d) = Vec3d(pos.x, center.y, pos.z)

    // --- Ebenen und Höhenband (Überarbeitung 4, P19 Schritt 4, PLAN 7.5) -------------------------------------------

    /** Körperangriffe reichen vom Kern bis zur höchsten Ebene des Saals (Podest +3) plus [BAND_ABOVE_LEVEL]. */
    private val bodyAbove: Double get() = fight.spec.levels.maxOf { it.floorY } - center.y + BAND_ABOVE_LEVEL

    /** Höhenband „Kern und Ebenen“ für alle danach angelegten Formen der Gruppe. */
    private fun bodyBand(group: Telegraph.Fight.Group): Telegraph.Fight.Group = group.band(center.y, above = bodyAbove)

    /** Höhenband „nur Hallenboden“ (Bodenschlag, Spalten): Standardgrenzen um den Kern. */
    private fun groundBand(group: Telegraph.Fight.Group): Telegraph.Fight.Group = group.band(center.y)

    /** Feldmitten je Ebene (Umgang Ost/West, Podest), einmal je Kampf aus dem Vertrag. */
    private val levelCells: List<Pair<Level, List<Vec3d>>> by lazy {
        fight.spec.levels.map { level -> level to level.zone.cells().map { (x, z) -> Vec3d(x + 0.5, 0.0, z + 0.5) }.toList() }
    }

    /**
     * Ebenen, auf denen ein Echo Sinn hat: Jemand steht gerade darauf, und sie ist noch nicht eingestürzt. (Ohne
     * Zuschauer oben bliebe das Echo nur ein schwebendes Doppelbild über dem Kern.)
     */
    private fun echoLevels(): List<Pair<Level, List<Vec3d>>> {
        val up = fight.targets().filter { fight.levelFloor(it.entityPos) > center.y + 0.5 }
        if (up.isEmpty()) return emptyList()
        return levelCells.filter { (level, _) -> up.any { level.zone.contains(it.x, it.z) } }
            .map { (level, cells) -> level to cells.filter { hypot(it.x - center.x, it.z - center.z) <= fight.safeRadius } }
            .filter { it.second.isNotEmpty() }
    }

    /** Ein Kegel, der über einer besetzten Ebene liegt, zeigt sich dort noch einmal (gleiche Gruppe, gleiche Zeit). */
    private fun echoCone(group: Telegraph.Fight.Group, cone: Telegraph.Cone) {
        for ((level, cells) in echoLevels()) {
            if (abs(level.floorY - cone.apex.y) < 0.5) continue
            if (cells.none { Telegraph.inCone(it, cone.apex, cone.direction, cone.radius, cone.halfAngle) }) continue
            group.cone(Vec3d(cone.apex.x, level.floorY.toDouble(), cone.apex.z), cone.direction, cone.radius.toFloat(), cone.halfAngle.toFloat(), cone.remaining.coerceAtLeast(1))
        }
    }

    /** Wie [echoCone] für Kreise (Schattenschritt, Zange). */
    private fun echoRing(group: Telegraph.Fight.Group, ring: Telegraph.Ring) {
        for ((level, cells) in echoLevels()) {
            if (abs(level.floorY - ring.center.y) < 0.5) continue
            if (cells.none { Telegraph.inRing(it, ring.center, ring.radius, ring.inner) }) continue
            group.ring(Vec3d(ring.center.x, level.floorY.toDouble(), ring.center.z), ring.radius.toFloat(), ring.remaining.coerceAtLeast(1), ring.inner.toFloat())
        }
    }

    /**
     * Linien zeigen sich auf einer besetzten Ebene nur dort, wo sie über ihr verlaufen (Stücke der Mittellinie in der
     * Ebene, gleiche Breite). [safe]: goldene Linie (Lücke im Zug der Toten).
     */
    private fun echoLine(group: Telegraph.Fight.Group, from: Vec3d, to: Vec3d, width: Float, ticks: Int, safe: Boolean = false) {
        val levels = echoLevels()
        if (levels.isEmpty()) return
        val length = hypot(to.x - from.x, to.z - from.z)
        val steps = (length / ECHO_STEP).toInt().coerceAtLeast(1)
        for ((level, _) in levels) {
            if (abs(level.floorY - from.y) < 0.5) continue
            val y = level.floorY.toDouble()
            var runStart = -1
            for (i in 0..steps + 1) {
                val inside = i <= steps && from.lerp(to, i.toDouble() / steps).let { level.zone.contains(it.x, it.z) && hypot(it.x - center.x, it.z - center.z) <= fight.safeRadius }
                if (inside && runStart < 0) runStart = i
                if (!inside && runStart >= 0) {
                    val a = from.lerp(to, runStart.toDouble() / steps)
                    val b = from.lerp(to, (i - 1).toDouble() / steps)
                    if (hypot(b.x - a.x, b.z - a.z) >= ECHO_MIN_LENGTH) {
                        val pa = Vec3d(a.x, y, a.z)
                        val pb = Vec3d(b.x, y, b.z)
                        if (safe) group.safeLine(pa, pb, width, ticks.coerceAtLeast(1)) else group.line(pa, pb, width, ticks.coerceAtLeast(1))
                    }
                    runStart = -1
                }
            }
        }
    }

    private fun echoLine(group: Telegraph.Fight.Group, line: Telegraph.Line) =
        echoLine(group, line.from, line.to, line.width.toFloat(), line.remaining)

    private fun flatDir(from: Vec3d, to: Vec3d): Vec3d? {
        val d = Vec3d(to.x - from.x, 0.0, to.z - from.z)
        return if (d.lengthSquared() < 1e-4) null else d.normalize()
    }

    /** Stößt vom Stoß weg: Die Wucht kommt immer vom Boss. */
    private fun shove(player: ServerPlayerEntity, from: Vec3d, strength: Double, up: Double) {
        val away = flatDir(from, player.entityPos) ?: Vec3d(0.0, 0.0, 1.0)
        Fx.launchPlayer(player, away.multiply(strength).add(0.0, up, 0.0))
    }

    private fun forward(): Vec3d {
        val rad = Math.toRadians((fight.boss?.yaw ?: 0f).toDouble())
        return Vec3d(-sin(rad), 0.0, cos(rad))
    }

    private fun headOf(): Vec3d {
        val entity = fight.boss ?: return center
        return entity.entityPos.add(0.0, entity.height * 0.92, 0.0)
    }

    /** Sieht er den Spieler? Säulen (und alles andere Feste) dazwischen verdecken ihn. */
    private fun exposed(eye: Vec3d, player: ServerPlayerEntity): Boolean {
        val result = world.raycast(RaycastContext(eye, player.eyePos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, fight.boss ?: player))
        return result.type == HitResult.Type.MISS
    }

    /** Er steht offen: goldene Rune, die Treffer zählen mehr. */
    private fun markOpen(ticks: Int) {
        val entity = fight.boss ?: return
        fight.openingFor = ticks
        entity.cloaked = false
        Vfx.rune(world, Vfx.Style.GOLD, entity.entityPos.add(0.0, 0.08, 0.0), 3.6f, ticks, entity)
        sound(HhaSounds.CHIME, entity.entityPos, 2.4f, 0.75f)
        fight.hint("hha.hades.hint.opening", Formatting.GOLD)
    }

    /** Taumeln (offen), danach richtet er sich auf. Kein Wegstoßen am Ende: Wer dran bleibt, wird nicht bestraft. */
    private fun staggered(ticks: Int): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        entity.pose = HadesPose.STAGGER
        sound(HhaSounds.ROAR, entity.entityPos, 3f, 0.6f)
        markOpen(ticks)
        wait(ticks)
        entity.pose = HadesPose.ROAR
        wait(H.RISE)
        entity.pose = HadesPose.IDLE
    }

    // -------------------------------------------------------------------------------------------------
    // Bewegung
    // -------------------------------------------------------------------------------------------------

    /** Er geht auf den Spieler zu, bis er nah genug ist oder die Zeit um ist. */
    private fun stride(id: UUID): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        entity.pose = HadesPose.IDLE
        var step = 0
        while (step++ < H.STRIDE_MAX) {
            val target = world.getPlayerByUuid(id) ?: fight.nearestTarget(entity.entityPos) ?: return@sequence
            val to = Vec3d(target.x - entity.x, 0.0, target.z - entity.z)
            if (to.length() < STOP_AT) break
            entity.setPosition(fight.clampToCore(entity.entityPos.add(to.normalize().multiply(WALK))))
            fight.face(target.entityPos)
            if (entity.cloaked && step % 9 == 0) footprint(entity.entityPos, step)
            yield(Unit)
        }
    }

    /** Spur unter dem Helm der Unsichtbarkeit: nur ein paar Funken, kein Ton (Plan 3.4). */
    private fun footprint(at: Vec3d, step: Int) {
        val rad = Math.toRadians((fight.boss?.yaw ?: 0f).toDouble())
        val side = if (step % 18 == 0) 0.55 else -0.55
        Fx.burst(world, HhaParticles.SOUL_FLAME, at.add(cos(rad) * side, 0.15, sin(rad) * side), 4, 0.12, 0.01)
    }

    // -------------------------------------------------------------------------------------------------
    // Grundangriffe
    // -------------------------------------------------------------------------------------------------

    /** [side]: Richtung des Hiebs für Band und Ruck: +1 rechts → links, −1 links → rechts, 0 von oben. */
    private class Sweep(val pose: Int, val windup: Int, val damage: Float, val side: Float)

    /**
     * Hiebfolge: Hieb A (W 14), Rückhand B (W 10), Schlusshieb von oben C (W 16), je ein Kegel 75° halb, Radius 8,5.
     * Jeder Folgehieb bekommt seine eigene Ansage, sobald der vorige gefallen ist. Danach steht er F 26 gestützt.
     */
    private fun sweepCombo(): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        entity.cloaked = false
        val group = bodyBand(calls.boss())
        for ((i, hit) in SWEEPS.withIndex()) {
            val target = fight.nearestTarget(entity.entityPos) ?: break
            fight.face(target.entityPos)
            entity.pose = hit.pose
            val cone = group.cone(
                floorAt(entity.entityPos), forward(), H.SWEEP_RADIUS, H.SWEEP_HALF_ANGLE, hit.windup,
                if (i == 0) null else Telegraph.Pace.FOLLOW_UP,
            )
            echoCone(group, cone)
            sound(HhaSounds.WHOOSH, entity.entityPos, 1.6f, 0.55f + i * 0.12f)
            wait(cone.ticks)
            strike(cone, hit.damage, i == SWEEPS.size - 1, hit.side)
            // Der Schlag selbst läuft noch [SWEEP_STRIKE] Ticks, erst dann holt er zum nächsten aus.
            if (i < SWEEPS.size - 1) wait(H.SWEEP_STRIKE)
        }
        wait(H.SWEEP_WINDOW)
        entity.pose = HadesPose.IDLE
    }

    /**
     * Ein Hieb fällt: wer im Kegel steht; als Flächeneffekt das Sichelband am Bident (`hades.bident`, folgt der Waffe),
     * dazu ein Ruck in Hiebrichtung (Überarbeitung 3: ersetzt das Rütteln des Schlusshiebs). [side] wie
     * [Sweep.side].
     */
    private fun strike(cone: Telegraph.Cone, damage: Float, final: Boolean, side: Float) {
        for (player in cone.hits(fight.targets())) {
            if (!fight.hurt(player, damage)) continue
            shove(player, cone.apex, 0.9, 0.35)
        }
        bidentBand(cone.apex, cone.direction, H.SWEEP_RADIUS, H.SWEEP_HALF_ANGLE, side)
        sound(HhaSounds.DASH, cone.apex, 3f, if (final) 0.5f else 0.65f)
        bidentKick(cone.apex, side)
    }

    /** `hades.bident` (Flächeneffekt des Hiebs): Sichelband an der Waffe, Kodierung in `HadesFx`. */
    private fun bidentBand(apex: Vec3d, direction: Vec3d, radius: Float, halfAngle: Float, side: Float) {
        BossFx.play(
            world, "hades.bident", Vfx.Style.HADES, apex, direction, a = radius, b = halfAngle, c = side,
            life = BAND_LIFE, entity = fight.boss, impact = true,
        )
    }

    /** Ruck eines Bident-Hiebs (PLAN 2.9): Rollen in Hiebrichtung, beim Hieb von oben ([side] 0) Nicken. */
    private fun bidentKick(at: Vec3d, side: Float) {
        if (side == 0f) BossFx.kick(world, at, pitch = SWEEP_KICK, ticks = KICK_TICKS)
        else BossFx.kick(world, at, pitch = 0f, roll = side * SWEEP_KICK, ticks = KICK_TICKS)
    }

    /** Ruck beim Einschlag des Bident (Boden, Säule, Wurf): Nicken 2,5° (PLAN 2.9). */
    private fun impactKick(at: Vec3d) {
        BossFx.kick(world, at, pitch = IMPACT_KICK, ticks = KICK_TICKS)
    }

    /** Ausfallschritt: Linie Breite 2,2, W 18, S 22. Wer vor einer Säule ausweicht, lässt ihn hineinrennen. */
    private fun lunge(): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        val target = fight.nearestTarget(entity.entityPos) ?: return@sequence
        entity.cloaked = false
        fight.face(target.entityPos)
        entity.pose = HadesPose.THRUST
        val (from, to) = thrustPath(entity.entityPos, target.entityPos)
        val group = bodyBand(calls.boss())
        val line = group.line(from, to, H.THRUST_WIDTH, H.THRUST_WINDUP, Telegraph.Pace.HEAVY)
        echoLine(group, line)
        // Säulen, hinter denen jemand steht, tragen die goldene Rune (sie halten den Stoß auf, Pflicht 4).
        Cover.mark(calls, fight.spec, TartarusArenas.HADES_LUNGE, entity.entityPos.add(0.0, entity.height * 0.5, 0.0), line.ticks)
        sound(HhaSounds.CHARGE, from, 3f, 0.8f)
        wait(line.ticks)
        yieldAll(thrust(line))
    }

    private fun thrustPath(from: Vec3d, toward: Vec3d): Pair<Vec3d, Vec3d> {
        val dir = flatDir(from, toward) ?: forward()
        val reach = (hypot(toward.x - from.x, toward.z - from.z) + 7.0).coerceIn(12.0, 34.0)
        // Das Ende betritt er selbst: im Kern (Überarbeitung 4), nicht im Umgang.
        return floorAt(from) to fight.clampToCore(from.add(dir.multiply(reach)))
    }

    /** Der Stoß: Er gleitet die angesagte Linie entlang. Steht eine Säule im Weg, rennt er hinein und taumelt (offen). */
    private fun thrust(line: Telegraph.Line): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        entity.pose = HadesPose.THRUST
        sound(HhaSounds.SPEAR, line.from, 3.5f, 0.7f)
        Vfx.trail(world, Vfx.Style.HADES, entity, 1.2f, 12)
        val dir = flatDir(line.from, line.to) ?: forward()
        val steps = ceil(line.from.distanceTo(line.to) / H.THRUST_SPEED).toInt().coerceAtLeast(2)
        val struck = HashSet<UUID>()
        val behind = pillarNear(line.from, 3.5)
        for (i in 1..steps) {
            val at = line.from.lerp(line.to, i.toDouble() / steps)
            val pillar = pillarNear(at, 3.0)
            if (pillar != null && pillar != behind) {
                // Der Bident fährt in den Stein, er selbst steht benommen davor.
                Vfx.shockwave(world, Vfx.Style.HADES, at, 3.5f, 12, 2f)
                impactKick(at)
                BossFx.debris(world, at, IMPACT_DEBRIS_RADIUS, IMPACT_DEBRIS)
                sound(HhaSounds.IMPACT, at, 4f, 0.65f)
                yieldAll(staggered(H.THRUST_PILLAR_STAGGER))
                return@sequence
            }
            entity.setPosition(at)
            for (player in fight.targets()) {
                if (player.uuid in struck || !line.contains(player)) continue
                if (hypot(player.x - at.x, player.z - at.z) < 2.6 && fight.reaches(player, BossDefense.Reach.BODY)) {
                    struck.add(player.uuid)
                    fight.hurt(player, 22f)
                    Fx.launchPlayer(player, dir.multiply(1.5).add(0.0, 0.55, 0.0))
                }
            }
            yield(Unit)
        }
        wait(H.THRUST_RECOVER)
        entity.pose = HadesPose.IDLE
    }

    /** Bodenschlag: Kreis Radius 9 um ihn, S 24 nur am Boden. Danach steckt der Bident (offen, F 48). */
    private fun slam(): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        entity.cloaked = false
        fight.nearestTarget(entity.entityPos)?.let { fight.face(it.entityPos) }
        entity.pose = HadesPose.SLAM
        // Bodenangriff: nur am Hallenboden (Überarbeitung 4), der Umgang (+2) ist davor sicher.
        val ring = groundBand(calls.boss()).ring(floorAt(entity.entityPos), H.SLAM_RADIUS, H.SLAM_WINDUP)
        sound(HhaSounds.CHARGE, ring.center, 2.5f, 0.7f)
        wait(ring.ticks)
        yieldAll(slamImpact(ring))
    }

    private fun slamImpact(ring: Telegraph.Ring): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        for (player in ring.hits(fight.targets())) {
            // Bodenschlag: nur am Boden (LOW), darüber springt man.
            if (!fight.grounded(player) || !fight.hurt(player, 24f, reach = BossDefense.Reach.LOW)) continue
            shove(player, ring.center, 1.2, 0.7)
        }
        Vfx.shockwave(world, Vfx.Style.HADES, ring.center, H.SLAM_RADIUS, 16, 3f)
        impactKick(ring.center)
        BossFx.cracks(world, Vfx.Style.HADES, ring.center, H.SLAM_RADIUS * SLAM_CRACK_SHARE, BossFx.CRACKS_PER_CALL, SLAM_CRACK_LIFE)
        BossFx.debris(world, ring.center, SLAM_DEBRIS_RADIUS, SLAM_DEBRIS)
        sound(HhaSounds.IMPACT, ring.center, 3.5f, 0.7f)
        entity.pose = HadesPose.STUCK
        markOpen(H.STUCK_WINDOW)
        wait(H.STUCK_WINDOW)
        sound(HhaSounds.WHOOSH, ring.center, 2f, 0.6f)
        entity.pose = HadesPose.IDLE
    }

    /**
     * Bident-Wurf: Kreis Radius 6 am Ziel, W 22, Abwurf bei Tick 14; der Bident fliegt als Modell und steckt danach im
     * Boden. Leere Hand: offen, F 40. In den letzten Ticks davon ruft er ihn zurück: eigene Linie, wer darauf steht,
     * wird vom Rückflug getroffen.
     */
    private fun bidentThrow(): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        val target = fight.nearestTarget(entity.entityPos) ?: return@sequence
        entity.cloaked = false
        fight.face(target.entityPos)
        entity.pose = HadesPose.THROW
        // Der Kreis liegt auf der Ebene des Ziels (Umgang, Podest); Einschlag von oben trifft jede Höhe (SKY, kein Band).
        val landing = fight.onLevel(fight.clampToHall(target.entityPos, 2.0))
        val ring = calls.ring(landing, H.THROW_RADIUS, H.THROW_WINDUP, pace = Telegraph.Pace.PROJECTILE)
        sound(HhaSounds.CHARGE, entity.entityPos, 3f, 0.9f)
        wait(H.THROW_RELEASE)
        entity.bidentOut = true
        val hand = handOf()
        val stuck = landing.add(0.0, BIDENT_STUCK_HEIGHT, 0.0)
        val bolt = fight.puppet(HermesBoltEntity.BIDENT, hand)
        // Seelenspur am fliegenden Bident (zwei umeinander laufende Stränge); endet am Client mit dem Wesen.
        if (bolt != null) BossFx.projectile(world, Vfx.Style.SOUL, bolt, BossFx.Look.SOUL)
        sound(HhaSounds.SPEAR, hand, 3.5f, 0.75f)
        val flight = ring.remaining.coerceAtLeast(1)
        val path = stuck.subtract(hand)
        for (i in 1..flight) {
            if (bolt != null) fight.steer(bolt, hand.lerp(stuck, i.toDouble() / flight), path)
            yield(Unit)
        }
        // Einschlag von oben (PLAN 4.3: SKY).
        for (player in ring.hits(fight.targets())) {
            if (!fight.hurt(player, 22f, reach = BossDefense.Reach.SKY)) continue
            shove(player, landing, 1.2, 0.6)
        }
        Vfx.shockwave(world, Vfx.Style.HADES, landing, H.THROW_RADIUS, 14, 2.4f)
        impactKick(landing)
        BossFx.debris(world, landing, IMPACT_DEBRIS_RADIUS, IMPACT_DEBRIS)
        sound(HhaSounds.IMPACT, landing, 3.5f, 0.85f)
        if (bolt != null) fight.steer(bolt, stuck, DOWN)
        entity.pose = HadesPose.EMPTY
        markOpen(H.EMPTY_WINDOW)
        wait(H.EMPTY_WINDOW - H.RETURN_WINDUP)
        // Er ruft ihn zurück: Die Linie zeigt den Rückweg.
        val backGroup = bodyBand(calls.boss())
        val back = backGroup.line(floorAt(landing), floorAt(entity.entityPos), H.RETURN_WIDTH, H.RETURN_WINDUP, Telegraph.Pace.PROJECTILE)
        echoLine(backGroup, back)
        sound(HhaSounds.CHARGE, landing, 2.4f, 1.2f)
        wait(back.ticks)
        sound(HhaSounds.SPEAR, landing, 3.5f, 0.95f)
        val struck = HashSet<UUID>()
        val home = handOf()
        for (i in 1..H.BIDENT_RETURN) {
            val at = stuck.lerp(home, i.toDouble() / H.BIDENT_RETURN)
            if (bolt != null) fight.steer(bolt, at, home.subtract(stuck))
            for (player in fight.targets()) {
                if (player.uuid in struck || !back.contains(player)) continue
                if (hypot(player.x - at.x, player.z - at.z) < 2.2) {
                    struck.add(player.uuid)
                    fight.hurt(player, 14f)
                }
            }
            yield(Unit)
        }
        bolt?.discard()
        entity.bidentOut = false
        entity.pose = HadesPose.IDLE
    }

    /** Wo die rechte Hand den Bident hält (ungefähr: Schulterhöhe, rechts vorn). */
    private fun handOf(): Vec3d {
        val entity = fight.boss ?: return center
        val f = forward()
        val right = Vec3d(-f.z, 0.0, f.x)
        return entity.entityPos.add(0.0, entity.height * 0.62, 0.0).add(right.multiply(-0.9)).add(f.multiply(0.4))
    }

    // -------------------------------------------------------------------------------------------------
    // Phase II und III: Fesseln (HadesFetters), Schattenschritt
    // -------------------------------------------------------------------------------------------------

    // Die Seelenketten (Zug 0,65) sind in Überarbeitung 4 durch die Fesseln ersetzt: `sig/HadesFetters.kt`.

    /**
     * Schattenschritt: Er setzt den Helm auf (VANISH), unter dem Ziel steht eine Rune (Radius 5, W 24); kurz vor dem
     * Treffer taucht er hinter dem Ziel auf und schlägt rundum (AMBUSH, S 22). Wer die Rune verlässt, bleibt heil.
     * Danach steht er offen (F 60).
     */
    private fun ambush(): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        val target = fight.randomTarget() ?: return@sequence
        entity.pose = HadesPose.VANISH
        // Dunkler Wirbel, der sich um ihn schließt (PLAN 2.9), dann ist er fort.
        BossFx.swirl(world, Vfx.Style.SHADOW, entity.entityPos.add(0.0, entity.height * 0.5, 0.0), UP, VANISH_SWIRL_RADIUS, VANISH_SWIRL_LIFE, closing = true)
        entity.cloaked = true
        val spot = fight.onLevel(fight.clampToHall(target.entityPos, 3.0))
        val group = bodyBand(calls.boss())
        val ring = group.ring(spot, H.AMBUSH_RADIUS, H.AMBUSH_WINDUP, pace = Telegraph.Pace.HEAVY)
        echoRing(group, ring)
        sound(HhaSounds.SOUL, entity.entityPos, 2.5f, 0.45f)
        // Solange er verhüllt ist: schwacher roter Herzschlag am Bildrand (sagt nie, wo er ist), alle 40 Ticks erneuert,
        // jede Sendung endet spätestens mit dem Auftauchen. Gleich viele Ticks wie das frühere wait().
        val cloak = (ring.ticks - H.AMBUSH_HIT).coerceAtLeast(0)
        for (tick in 0 until cloak) {
            if (tick % HUNTED_RENEW == 0) hunted((cloak - tick).coerceAtMost(HUNTED_RENEW + HUNTED_OVERLAP))
            yield(Unit)
        }
        // Er taucht hinter dem Ziel auf, innerhalb der Rune.
        val look = target.getRotationVec(1f).let { flatDir(Vec3d.ZERO, it) } ?: forward()
        // Dort steht er selbst: im Kern (Überarbeitung 4); die Rune liegt weiter am Ziel.
        val appear = fight.clampToCore(spot.subtract(look.multiply(2.4)), 3.0)
        entity.setPosition(appear)
        fight.face(target.entityPos)
        entity.cloaked = false
        entity.pose = HadesPose.AMBUSH
        Vfx.flare(world, Vfx.Style.HADES, appear.add(0.0, 2.0, 0.0), 3.5f, 8)
        sound(HhaSounds.BLINK, appear, 2.6f, 0.6f)
        wait(ring.remaining)
        for (player in ring.hits(fight.targets())) {
            if (!fight.hurt(player, 22f)) continue
            shove(player, appear, 1.0, 0.4)
        }
        bidentBand(appear, forward(), H.AMBUSH_RADIUS, AMBUSH_BAND_HALF_ANGLE, SIDE_RIGHT_TO_LEFT)
        bidentKick(appear, SIDE_RIGHT_TO_LEFT)
        sound(HhaSounds.DASH, appear, 3f, 0.55f)
        markOpen(H.AMBUSH_WINDOW)
        wait(H.AMBUSH_WINDOW)
        entity.pose = HadesPose.IDLE
    }

    /**
     * Herzschlag-Rand für alle Mitkämpfer ([BossFx.vignette], rot, schwach). Rot ist hier Gefahr, deshalb ohne den
     * grünen Kampfstil des Saals (der würde jede Farbe außer Gold übermalen).
     */
    private fun hunted(ticks: Int) {
        if (ticks <= 0) return
        val saved = Vfx.styleOverride
        Vfx.styleOverride = null
        try {
            for (player in fight.targets()) BossFx.vignette(player, Vfx.Style.CRIMSON, HUNTED_STRENGTH, ticks, beat = true)
        } finally {
            Vfx.styleOverride = saved
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Höhepunkte: Blick, Urteil, Einsturz
    // -------------------------------------------------------------------------------------------------

    private fun pillarAt(offset: Pair<Int, Int>): Vec3d = center.add(offset.first.toDouble(), 0.0, offset.second.toDouble())

    private fun pillarNear(at: Vec3d, reach: Double): Pair<Int, Int>? =
        fight.intactPillars.firstOrNull { hypot(pillarAt(it).x - at.x, pillarAt(it).z - at.z) < reach }

    /** Die Säule, die zwischen [from] und [to] steht (waagerecht), falls eine. */
    private fun pillarBetween(from: Vec3d, to: Vec3d): Pair<Int, Int>? {
        fun distance(p: Vec3d): Double {
            val vx = to.x - from.x
            val vz = to.z - from.z
            val len2 = vx * vx + vz * vz
            val t = if (len2 < 1e-9) 0.0 else (((p.x - from.x) * vx + (p.z - from.z) * vz) / len2).coerceIn(0.0, 1.0)
            return hypot(p.x - (from.x + vx * t), p.z - (from.z + vz * t))
        }
        return fight.intactPillars.minByOrNull { distance(pillarAt(it)) }?.takeIf { distance(pillarAt(it)) < TartarusPlans.PILLAR_RADIUS + 2.5 }
    }

    fun crumblePillar(pillar: Pair<Int, Int>) = crumble(pillar)

    /**
     * Eine Säule bricht von oben her zusammen. Wenn sie weg ist, schützt sie vor nichts mehr. [loud] = eigener Ton
     * (beim Blick nicht: Dort gibt es genau einen Ton für den Treffer).
     */
    private fun crumble(pillar: Pair<Int, Int>, loud: Boolean = true) {
        if (!fight.intactPillars.remove(pillar)) return
        val base = pillarAt(pillar)
        // Die Säule zerspringt (Flächeneffekt an ihrem Fuß), echte Brocken fliegen; die Blöcke selbst fallen unten.
        val style = if (fight.stage >= 4) Vfx.Style.AEON else Vfx.Style.HADES
        BossFx.pillarShatter(world, style, base, TartarusPlans.PILLAR_RADIUS.toFloat(), SHATTER_HEIGHT, SHATTER_RISE, SHATTER_HOLD)
        BossFx.debris(world, base, PILLAR_DEBRIS_RADIUS, PILLAR_DEBRIS)
        if (loud) sound(HhaSounds.IMPACT, base, 3.5f, 0.55f)
        fight.addTask(sequence {
            val radius = TartarusPlans.PILLAR_RADIUS + 1
            val stump = 3 + (abs(pillar.first * 7 + pillar.second * 13) % 5)
            var height = TartarusPlans.PILLAR_HEIGHT + 4
            while (height > stump) {
                repeat(3) {
                    for (dx in -radius..radius) {
                        for (dz in -radius..radius) {
                            val pos = fight.floor.add(pillar.first + dx, height, pillar.second + dz)
                            if (!world.getBlockState(pos).isAir) fight.edit(pos, Blocks.AIR.defaultState)
                        }
                    }
                    height--
                }
                yield(Unit)
            }
        })
    }

    /**
     * Sein Blick (Phase I, fester Höhepunkt alle 35 s, höchstens zweimal): Er schiebt das Visier hoch, dünne Linien
     * zeigen 60 Ticks lang, wen er sieht. Wer dann ohne Deckung steht, bekommt S 32; die Säule, hinter der jemand
     * stand, stürzt ein. Danach steht er F 24.
     */
    private fun gaze(): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        gazes++
        gazeAt = fight.stageAge + H.GAZE_INTERVAL
        entity.cloaked = false
        entity.pose = HadesPose.GAZE
        fight.hint("hha.hades.hint.cover", Formatting.GREEN)
        calls.boss(pace = Telegraph.Pace.ROOM, reserve = H.GAZE_WINDUP)
        // Überarbeitung 4 (P19 Schritt 6): Säulen, die gerade jemanden decken, tragen die goldene Rune; jeder sieht
        // „gedeckt“/„gesehen“. Die Treffer-Logik bleibt [exposed] (Kopf → Augen), siehe Bericht.
        Cover.mark(calls, fight.spec, TartarusArenas.HADES_GAZE, headOf(), H.GAZE_WINDUP)
        sound(HhaSounds.CHARGE, entity.entityPos, 4f, 0.45f)
        repeat(H.GAZE_WINDUP) { tick ->
            if (tick % 6 == 0) {
                val eye = headOf()
                for (player in fight.targets()) {
                    val seen = exposed(eye, player)
                    Cover.status(player, !seen)
                    if (seen) Vfx.beam(world, Vfx.Style.HADES, eye, player.eyePos.subtract(0.0, 0.3, 0.0), 0.3f, 7)
                }
            }
            yield(Unit)
        }
        val eye = headOf()
        val fallen = HashSet<Pair<Int, Int>>()
        for (player in fight.targets()) {
            if (exposed(eye, player)) {
                Vfx.beam(world, Vfx.Style.HADES, eye, player.eyePos.subtract(0.0, 0.3, 0.0), 2.6f, 14)
                fight.hurt(player, 32f, magic = true, reach = BossDefense.Reach.SKY)
            } else {
                pillarBetween(entity.entityPos, player.entityPos)?.let { fallen.add(it) }
            }
        }
        for (pillar in fallen) {
            Vfx.beam(world, Vfx.Style.HADES, eye, pillarAt(pillar).add(0.0, 3.0, 0.0), 2.6f, 14)
            crumble(pillar, loud = false)
        }
        BossFx.kick(world, entity.entityPos, pitch = GAZE_KICK, ticks = KICK_TICKS, radius = ROOM_KICK_RADIUS)
        sound(HhaSounds.ROAR, entity.entityPos, 4f, 0.5f)
        wait(H.GAZE_WINDOW)
        entity.pose = HadesPose.IDLE
    }

    /**
     * Sein Urteil (Phase III, alle 40 s): Er hebt den Bident wie eine Waage (W 71), nur drei goldene Kreise sind
     * sicher; wer außerhalb steht, bekommt S 34. Danach ist er ausgebrannt (offen, F 50).
     */
    private fun judgement(): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        judgeAt = fight.stageAge + H.JUDGE_INTERVAL
        entity.cloaked = false
        // Die drei Kreise liegen so, dass jeder Spieler einen davon zu Fuß erreicht (Plan 3.2: Fairness vor Härte).
        // Reicht die Zeit trotzdem nicht, wird die Ansage länger; das Heben beginnt dann entsprechend später.
        val spots = judgeLayout()
        val needed = escapeTicks(spots, H.JUDGE_SAFE_RADIUS.toDouble())
        // Auch mit Zusatzkreisen nicht fair lösbar (sehr große, weit verteilte Gruppe): Diesmal kein Urteil, statt die
        // nötige Zeit zu kappen. Das nächste kommt nach dem üblichen Abstand.
        if (needed > BossTimings.Min.ESCAPE_MAX) return@sequence
        val windup = max(H.JUDGE_HIT, needed)
        val group = calls.boss(pace = Telegraph.Pace.ROOM)
        val safe = spots.map { group.safe(it, H.JUDGE_SAFE_RADIUS, windup) }
        fight.hint("hha.hades.hint.judgement", Formatting.GREEN)
        sound(HhaSounds.CHARGE, entity.entityPos, 4f, 0.5f)
        // group.ticks ist die Restzeit bis zum Treffer: erst warten, bis noch genau JUDGE_HIT übrig sind, dann heben.
        val lead = (group.ticks - H.JUDGE_HIT).coerceAtLeast(0)
        if (lead > 0) {
            entity.pose = HadesPose.WINDUP
            wait(lead)
        }
        entity.pose = HadesPose.JUDGE
        // Die Lichtsäulen fallen [JUDGE_FALL] Ticks vor dem Treffer los und berühren den Boden genau im Treffer-Tick
        // (gleich viele Ticks wie das frühere wait(group.ticks)).
        val left = group.ticks
        val fall = JUDGE_FALL.coerceAtMost(left)
        wait(left - fall)
        judgeBeams(spots, fall)
        wait(fall)
        for (player in fight.targets()) {
            if (safe.none { it.contains(player) }) fight.hurt(player, 34f, magic = true, reach = BossDefense.Reach.SKY)
        }
        BossFx.kick(world, center, pitch = IMPACT_KICK, ticks = KICK_TICKS, radius = ROOM_KICK_RADIUS)
        sound(HhaSounds.THUNDER, center, 4f, 0.6f)
        markOpen(H.JUDGE_WINDOW)
        wait(H.JUDGE_WINDOW)
        entity.pose = HadesPose.IDLE
    }

    /**
     * `hades.judge` (Flächeneffekt des Urteils, **ein** Aufruf): Lichtsäulen fahren von oben in die Flächen außerhalb der
     * goldenen Kreise. Zuerst über jeden Spieler, der gerade außerhalb steht, dann auf Ringen über den Saal; keine Säule
     * berührt einen sicheren Kreis (Abstand [JUDGE_SAFE_MARGIN]). Versätze zur Saalmitte in `extra` (`dx,dz;…`).
     */
    private fun judgeBeams(spots: List<Vec3d>, fall: Int) {
        val keepOut = H.JUDGE_SAFE_RADIUS + JUDGE_SAFE_MARGIN
        val reach = fight.safeRadius - 2.0
        fun free(x: Double, z: Double): Boolean =
            hypot(x - center.x, z - center.z) <= reach && spots.none { hypot(x - it.x, z - it.z) < keepOut }
        val beams = ArrayList<Pair<Double, Double>>()
        for (player in fight.targets()) {
            if (beams.size >= JUDGE_MAX_BEAMS) break
            if (free(player.x, player.z)) beams.add(player.x - center.x to player.z - center.z)
        }
        val turn = world.random.nextDouble() * Math.PI * 2.0
        for ((ring, count) in JUDGE_RINGS) {
            for (i in 0 until count) {
                if (beams.size >= JUDGE_MAX_BEAMS) break
                val a = turn + ring * 0.37 + i * Math.PI * 2.0 / count
                val x = center.x + cos(a) * ring
                val z = center.z + sin(a) * ring
                if (free(x, z) && beams.none { hypot(it.first - (x - center.x), it.second - (z - center.z)) < JUDGE_BEAM_SPACING }) {
                    beams.add(x - center.x to z - center.z)
                }
            }
        }
        val extra = beams.joinToString(";") { (x, z) -> String.format(Locale.ROOT, "%.1f,%.1f", x, z) }
        BossFx.play(
            world, "hades.judge", Vfx.Style.HADES, center, a = JUDGE_BEAM_HEIGHT, b = JUDGE_BEAM_RADIUS, c = fall.toFloat(),
            life = fall + JUDGE_BEAM_LIFE, impact = true, extra = extra,
        )
    }

    /**
     * Lage der Urteils-Kreise: Aus vielen Drehungen und Ringradien wird die gewählt, bei der der am schlechtesten
     * stehende Spieler den kürzesten Weg zum nächsten Kreis hat; unter fast gleich guten entscheidet der Zufall.
     * Stehen Spieler so verteilt, dass einer trotzdem keinen der drei Kreise in der Grundzeit erreicht, bekommt er einen
     * zusätzlichen Kreis in Laufweite (höchstens [JUDGE_MAX_CIRCLES] insgesamt). Die Ansage verlängert sich nur noch,
     * wenn auch das nicht reicht.
     */
    private fun judgeLayout(): List<Vec3d> {
        val outer = fight.safeRadius
        val rings = listOf(0.35, 0.5, 0.6, 0.75).map { (outer * it).coerceIn(6.0, max(6.0, outer - 6.0)) }.distinct()
        val players = fight.targets()
        val options = ArrayList<Pair<Double, List<Vec3d>>>()
        for (ring in rings) {
            for (step in 0 until 36) {
                val start = step * Math.PI * 2.0 / 36 + world.random.nextDouble() * 0.05
                val spots = List(H.JUDGE_SAFE_CIRCLES) { i ->
                    val a = start + i * Math.PI * 2.0 / H.JUDGE_SAFE_CIRCLES
                    center.add(cos(a) * ring, 0.0, sin(a) * ring)
                }
                options.add(worstWalk(spots, H.JUDGE_SAFE_RADIUS.toDouble(), players) to spots)
            }
        }
        // Überarbeitung 4: Die goldenen Kreise liegen im Kern (nicht auf Umgang oder Podest, nicht halb in der Wand).
        val inCore = options.filter { (_, spots) -> spots.all { insideCore(it, H.JUDGE_SAFE_RADIUS.toDouble()) } }
        val pool = inCore.ifEmpty { options.map { (walk, spots) -> walk to spots.map { fight.clampToCore(it, H.JUDGE_SAFE_RADIUS.toDouble()) } } }
        val best = pool.minOf { it.first }
        val near = pool.filter { it.first <= best + 2.0 }
        val spots = ArrayList(near[world.random.nextInt(near.size)].second)
        // Zusatzkreise für Spieler, die in der Grundzeit (JUDGE_HIT) keinen Kreis erreichen: ein Kreis in der Richtung
        // zur Saalmitte, so weit weg, dass man trotzdem laufen muss (80 % der erreichbaren Strecke).
        val radius = H.JUDGE_SAFE_RADIUS.toDouble()
        val reach = (H.JUDGE_HIT - BossTimings.Min.REACTION) * BossTimings.Min.ESCAPE_SPEED / DETOUR
        for (p in players.sortedByDescending { walkTo(it, spots, radius) }) {
            if (spots.size >= JUDGE_MAX_CIRCLES) break
            if (walkTo(p, spots, radius) <= reach) continue
            val toCenter = Vec3d(center.x - p.x, 0.0, center.z - p.z)
            val length = toCenter.length()
            val step = min(length, reach * 0.8 + radius - 0.6)
            val at = if (length < 1e-3) center else Vec3d(p.x, center.y, p.z).add(toCenter.multiply(step / length))
            spots.add(fight.clampToCore(at, radius))
        }
        return spots
    }

    /** Liegt ein Kreis mit [radius] um [spot] ganz im Kern (Kreis r 38 ohne Podest)? */
    private fun insideCore(spot: Vec3d, radius: Double): Boolean {
        val clamped = fight.clampToCore(spot, radius)
        return hypot(clamped.x - spot.x, clamped.z - spot.z) < 1e-3
    }

    /** Fußweg (Blöcke) eines Spielers bis in den nächsten sicheren Kreis. */
    private fun walkTo(p: ServerPlayerEntity, spots: List<Vec3d>, radius: Double): Double =
        (spots.minOf { hypot(p.x - it.x, p.z - it.z) } - (radius - 0.6)).coerceAtLeast(0.0)

    /** Längster Fußweg (Blöcke) eines Spielers bis in den nächsten sicheren Kreis. */
    private fun worstWalk(spots: List<Vec3d>, radius: Double, players: List<ServerPlayerEntity>): Double =
        players.maxOfOrNull { walkTo(it, spots, radius) } ?: 0.0

    /**
     * So viele Ticks braucht der am schlechtesten stehende Spieler bis in einen sicheren Kreis: Reaktionszeit plus Weg
     * bei [BossTimings.Min.ESCAPE_SPEED], mit [DETOUR] für Umwege um Säulen.
     */
    private fun escapeTicks(spots: List<Vec3d>, radius: Double): Int {
        val walk = worstWalk(spots, radius, fight.targets()) * DETOUR
        return BossTimings.Min.REACTION + kotlin.math.ceil(walk / BossTimings.Min.ESCAPE_SPEED).toInt()
    }

    /**
     * Einsturz an den Lebensschwellen von Phase III (66 % und 33 %): Der äußere Ring glüht, ein goldener Ring zeigt, was
     * stehen bleibt (W 80), dann stürzt der Rest in die Tiefe (44 → 36 → 28). Wer draußen steht, fällt.
     *
     * Überarbeitung 4 (P19 Schritt 5): Was fällt, ist das Vertragsteil (`ring_outer`, dann `ring_middle`), nicht mehr ein
     * Radius um die Mitte; so bleibt am Rand des Umgangs kein Saum stehen. Je Spalte fällt alles, was vom Fundament
     * an zusammenhängend steht (Boden, Umgang samt Brüstung, Treppen, Podest); Spalten neben einer hohen Spalte
     * (Treppentürme, Säulen, Thronlehne) fallen bis zu deren Spitze ([collapsePlan]). Was an der Wand hängt (Empore,
     * Laternen darunter, Ketten zum Kronleuchter), bleibt. Die Ansage reicht nach der Erreichbarkeitsrechnung
     * ([collapseWindup]).
     */
    private fun collapse(): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        val index = (collapsesDone + 1).coerceAtMost(H.COLLAPSE_RADII.size - 1)
        collapsesDone++
        val next = H.COLLAPSE_RADII[index].toDouble()
        val outer = fight.safeRadius
        if (next >= outer) return@sequence
        // Einsturzring Nummer index aus dem Vertrag (index 1 → ring_outer, 2 → ring_middle).
        val part = fight.spec.parts.getOrNull(index - 1) ?: return@sequence
        entity.cloaked = false
        entity.pose = HadesPose.ROAR
        val cells = part.zone.cells().toList()
        // Glimmt, schadet aber nicht: Der Einsturz selbst ist die Gefahr (auf dem Umgang dessen Belag).
        for ((x, z) in cells) glowCell(x, z)
        val windup = collapseWindup(next)
        val group = calls.boss(pace = Telegraph.Pace.ROOM)
        group.safe(center, next.toFloat(), windup)
        fight.hint("hha.hades.hint.collapse", Formatting.GOLD)
        sound(HhaSounds.RIFT, center, 4f, 0.5f)
        // Glühende Risse laufen vom Fuß jeder Säule, die gleich stürzt, nach außen (über die Ansage, nicht in Gold).
        for ((n, pillar) in fight.intactPillars.filter { hypot(it.first.toDouble(), it.second.toDouble()) > next - 1.0 }.withIndex()) {
            if (n >= BossFx.CRACKS_PER_CALL) break
            val base = pillarAt(pillar)
            val out = flatDir(center, base) ?: continue
            val length = (outer - hypot(pillar.first.toDouble(), pillar.second.toDouble())).toFloat().coerceIn(COLLAPSE_CRACK_MIN, COLLAPSE_CRACK_MAX)
            BossFx.crack(
                world, Vfx.Style.HADES, base.add(out.multiply(TartarusPlans.PILLAR_RADIUS + 0.6)), out, length,
                life = windup + COLLAPSE_CRACK_AFTER, grow = (windup * COLLAPSE_CRACK_GROW).toInt(),
            )
        }
        wait(group.ticks)
        if (fight.isEnded || fight.stage != 3) return@sequence
        // Was fällt, wird jetzt bestimmt (vor dem Bröckeln der Säulen, das die Spalten verändert).
        val order = collapsePlan(part, cells)
        fight.safeRadius = next
        // Jede Säule im fallenden Ring zerspringt (je ein Flächeneffekt an ihrem Fuß, siehe [crumble]); statt des
        // saalweiten Rings und des Rüttelns ein einzelner Ruck.
        for (pillar in fight.intactPillars.toList()) {
            if (hypot(pillar.first.toDouble(), pillar.second.toDouble()) > next - 1.0) crumble(pillar)
        }
        BossFx.kick(world, center, pitch = IMPACT_KICK, ticks = KICK_TICKS, radius = ROOM_KICK_RADIUS)
        sound(HhaSounds.IMPACT, center, 4f, 0.4f)
        sound(HhaSounds.ARENA_COLLAPSE, center, 3.5f, 0.7f)
        // Von außen nach innen, ein paar Spalten je Tick, damit es wie ein echter Einsturz aussieht.
        val perTick = (order.size / H.COLLAPSE_FALL).coerceAtLeast(8)
        var i = 0
        while (i < order.size) {
            repeat(perTick) {
                if (i < order.size) dropColumn(order[i++], part)
            }
            yield(Unit)
        }
        entity.pose = HadesPose.IDLE
    }

    /**
     * Ansage des Einsturzes (Pflicht 5, Erreichbarkeitsrechnung): [H.COLLAPSE_WINDUP], länger nur, wenn der am weitesten
     * draußen Stehende den sicheren Ring sonst nicht erreicht ([BossTimings.Min.REACTION] + Weg × [DETOUR] /
     * [BossTimings.Min.ESCAPE_SPEED]), höchstens [BossTimings.Min.ESCAPE_MAX]. Mit dem Saal von heute reichen die 80.
     */
    private fun collapseWindup(next: Double): Int {
        val walk = fight.targets().maxOfOrNull { (hypot(it.x - center.x, it.z - center.z) - (next - 0.6)).coerceAtLeast(0.0) } ?: 0.0
        val needed = BossTimings.Min.REACTION + ceil(walk * DETOUR / BossTimings.Min.ESCAPE_SPEED).toInt()
        return max(H.COLLAPSE_WINDUP, needed).coerceAtMost(max(H.COLLAPSE_WINDUP, BossTimings.Min.ESCAPE_MAX))
    }

    /** Eine Spalte des Einsturzes: wo, bis zu welcher Höhe (Welt-y) alles fällt, und lose Blöcke darüber ([loose]). */
    private class ColumnDrop(val x: Int, val z: Int, val top: Int, var loose: List<Int> = emptyList())

    /**
     * Was je Spalte von [part] fällt:
     * 1. alles vom Fundament (`part.minY`) an, was zusammenhängend steht ([stackTop]); ist die Spalte selbst oder eine
     *    Nachbarspalte im Teil höher als [TALL_ABOVE_LEVEL] über der höchsten Ebene (Turm, Säule), fällt sie bis zu
     *    deren Spitze;
     * 2. was danach im Teil noch steht, aber weder nach außen (Wand, Empore) noch nach oben (über `part.maxY`) an
     *    festem Bau hängt, fällt mit ([looseAbove]; z. B. der hintere Teil der Thronlehne). Ketten bleiben hängen.
     * Reihenfolge: von außen nach innen.
     */
    private fun collapsePlan(part: Part, cells: List<Pair<Int, Int>>): List<ColumnDrop> {
        val inPart = cells.mapTo(HashSet()) { (x, z) -> BlockPos.asLong(x, 0, z) }
        val tops = HashMap<Long, Int>(cells.size * 2)
        for ((x, z) in cells) tops[BlockPos.asLong(x, 0, z)] = stackTop(x, z, part)
        val tall = fight.spec.levels.maxOf { it.floorY } + TALL_ABOVE_LEVEL
        val drops = ArrayList<ColumnDrop>(cells.size)
        for ((x, z) in cells) {
            var top = tops.getValue(BlockPos.asLong(x, 0, z))
            for (dx in -1..1) for (dz in -1..1) {
                val key = BlockPos.asLong(x + dx, 0, z + dz)
                if (key !in inPart) continue
                val t = tops.getValue(key)
                if (t >= tall) top = max(top, t)
            }
            drops.add(ColumnDrop(x, z, top))
        }
        val loose = looseAbove(part, drops)
        for (drop in drops) drop.loose = loose[BlockPos.asLong(drop.x, 0, drop.z)] ?: emptyList()
        return drops.sortedByDescending { hypot(it.x + 0.5 - center.x, it.z + 0.5 - center.z) }
    }

    /**
     * Schritt 2 von [collapsePlan]: alle Blöcke im Teil über den fallenden Stapeln, die über ihre Nachbarn (6 Richtungen,
     * im Teil) nicht mit festem Bau außerhalb verbunden sind, der weiter außen liegt oder über `part.maxY`. Der Kern
     * innen trägt nicht (er bleibt, aber was nur an ihm hängt, ragte über die Grube). Liefert je Spalte die y-Werte.
     */
    private fun looseAbove(part: Part, drops: List<ColumnDrop>): Map<Long, List<Int>> {
        val topOf = HashMap<Long, Int>(drops.size * 2)
        for (d in drops) topOf[BlockPos.asLong(d.x, 0, d.z)] = d.top
        val rest = HashSet<Long>()
        val pos = BlockPos.Mutable()
        for (d in drops) {
            for (y in d.top + 1..part.maxY) {
                val state = world.getBlockState(pos.set(d.x, y, d.z))
                if (!state.isAir) rest.add(BlockPos.asLong(d.x, y, d.z))
            }
        }
        if (rest.isEmpty()) return emptyMap()
        val reached = HashSet<Long>()
        val queue = ArrayDeque<Long>()
        fun radius(x: Int, z: Int) = hypot(x + 0.5 - center.x, z + 0.5 - center.z)
        for (key in rest) {
            val x = BlockPos.unpackLongX(key)
            val y = BlockPos.unpackLongY(key)
            val z = BlockPos.unpackLongZ(key)
            val r = radius(x, z)
            for (dir in Direction.entries) {
                val nx = x + dir.offsetX
                val ny = y + dir.offsetY
                val nz = z + dir.offsetZ
                val n = BlockPos.asLong(nx, ny, nz)
                if (n in rest) continue
                val inPart = topOf.containsKey(BlockPos.asLong(nx, 0, nz))
                // Fester Halt: oberhalb des Teils oder außerhalb des Teils weiter außen (Wand); nicht der Kern innen.
                val holds = ny > part.maxY || (!inPart && radius(nx, nz) > r)
                if (holds && !world.getBlockState(pos.set(nx, ny, nz)).isAir) {
                    reached.add(key)
                    queue.add(key)
                    break
                }
            }
        }
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            val x = BlockPos.unpackLongX(key)
            val y = BlockPos.unpackLongY(key)
            val z = BlockPos.unpackLongZ(key)
            for (dir in Direction.entries) {
                val n = BlockPos.asLong(x + dir.offsetX, y + dir.offsetY, z + dir.offsetZ)
                if (n in rest && reached.add(n)) queue.add(n)
            }
        }
        val out = HashMap<Long, MutableList<Int>>()
        for (key in rest) {
            if (key in reached) continue
            val state = world.getBlockState(pos.set(key))
            // Ketten hängen auch nur über Eck (schräg zum Kronleuchter): Sie bleiben.
            if (state.block is ChainBlock || state.hasBlockEntity()) continue
            out.getOrPut(BlockPos.asLong(BlockPos.unpackLongX(key), 0, BlockPos.unpackLongZ(key))) { ArrayList() }.add(BlockPos.unpackLongY(key))
        }
        return out
    }

    /** Oberster Block, der vom Fundament der Spalte an ohne Lücke steht (höchstens `part.maxY`). */
    private fun stackTop(x: Int, z: Int, part: Part): Int {
        val pos = BlockPos.Mutable(x, part.minY, z)
        var top = part.minY - 1
        for (y in part.minY..part.maxY) {
            pos.setY(y)
            if (world.getBlockState(pos).isAir) break
            top = y
        }
        return top
    }

    /** Die Oberfläche einer fallenden Spalte glimmt: Belag des Umgangs, sonst der Hallenboden (nur, wenn frei liegend). */
    private fun glowCell(x: Int, z: Int) {
        val walk = fight.spec.levels.firstOrNull { it.zone.containsCell(x, z) && it.id.startsWith(WALK_PREFIX) }
        val y = if (walk != null && !world.getBlockState(BlockPos(x, walk.floorY - 1, z)).isAir) walk.floorY - 1 else fight.floor.y
        val pos = BlockPos(x, y, z)
        if (world.getBlockState(pos).isAir || !world.getBlockState(pos.up()).isAir) return
        fight.edit(pos, if ((x + z) and 1 == 0) Blocks.CRYING_OBSIDIAN.defaultState else Blocks.VERDANT_FROGLIGHT.defaultState)
    }

    /**
     * Eine Spalte fällt: ihre losen Blöcke ([ColumnDrop.loose]), dann von [ColumnDrop.top] bis zum Fundament des Teils
     * alles außer Luft und Blöcken mit Inventar, darunter
     * die Grube bis 6 tief, unten Schwarzstein und Magma (wie bisher). Alles über [HadesFight.edit] (Register, Bauplan).
     */
    private fun dropColumn(drop: ColumnDrop, part: Part) {
        val pos = BlockPos.Mutable(drop.x, drop.top, drop.z)
        for (y in drop.loose.sortedDescending()) fight.edit(BlockPos(drop.x, y, drop.z), Blocks.AIR.defaultState)
        for (y in drop.top downTo part.minY) {
            pos.setY(y)
            val state = world.getBlockState(pos)
            if (!state.isAir && !state.hasBlockEntity()) fight.edit(pos.toImmutable(), Blocks.AIR.defaultState)
        }
        val floorY = fight.floor.y
        for (y in part.minY - 1 downTo floorY - PIT_DEPTH + 1) {
            pos.setY(y)
            if (!world.getBlockState(pos).isAir) fight.edit(pos.toImmutable(), Blocks.AIR.defaultState)
        }
        val bottom = if ((drop.x * 3 + drop.z) and 7 == 0) Blocks.MAGMA_BLOCK.defaultState else Blocks.BLACKSTONE.defaultState
        fight.edit(BlockPos(drop.x, floorY - PIT_DEPTH, drop.z), bottom)
    }

    // -------------------------------------------------------------------------------------------------
    // Phase III: Hermes
    // -------------------------------------------------------------------------------------------------

    /**
     * Zangenangriff (alle ~12 s, nie unabhängig): (a) Hermes' Linie (W 18), 10 Ticks später Hades' Ausfallschritt quer
     * dazu; (b) Hermes' Fächer (W 16) treibt Richtung Hades, der den Bodenschlag ansetzt. Beides ist **eine** Ansage.
     */
    private fun pincer(): Sequence<Unit> = sequence {
        pincerAt = fight.stageAge + H.PINCER_INTERVAL
        val entity = fight.boss ?: return@sequence
        val ghost = fight.hermes?.takeIf { !it.isRemoved } ?: return@sequence
        val target = fight.nearestTarget(entity.entityPos) ?: return@sequence
        hermesBusy = true
        entity.cloaked = false
        fight.hint("hha.hades.hint.pincer", Formatting.GREEN)
        if (pincerTurn++ % 2 == 0) yieldAll(pincerCross(ghost, target)) else yieldAll(pincerDrive(ghost, target))
        hermesBusy = false
        if (!ghost.isRemoved) ghost.pose = HermesPose.IDLE
    }

    private fun pincerCross(ghost: HermesEntity, target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        val aim = floorAt(target.entityPos)
        val toTarget = flatDir(entity.entityPos, aim) ?: forward()
        val side = Vec3d(-toTarget.z, 0.0, toTarget.x)
        // Hermes läuft die Linie selbst: Anfang und Ende im Kern (Überarbeitung 4).
        val from = fight.clampToCore(aim.add(side.multiply(H.PINCER_HERMES_RANGE)))
        val to = fight.clampToCore(aim.subtract(side.multiply(H.PINCER_HERMES_RANGE - 2.0)))
        blink(ghost, from)
        ghost.pose = HermesPose.DASH_WINDUP
        fight.faceGhost(ghost, to)
        val group = bodyBand(calls.boss())
        val dash = group.line(from, to, BossTimings.Hermes.DASH_WIDTH, H.PINCER_LINE_WINDUP, Telegraph.Pace.HEAVY)
        echoLine(group, dash)
        sound(HhaSounds.CHARGE, from, 2f, 1.3f)
        wait(H.PINCER_DELAY)
        // Hades setzt quer dazu an.
        fight.face(aim)
        entity.pose = HadesPose.THRUST
        val (lungeFrom, lungeTo) = thrustPath(entity.entityPos, aim)
        val lunge = group.line(lungeFrom, lungeTo, H.THRUST_WIDTH, H.THRUST_WINDUP, Telegraph.Pace.HEAVY)
        echoLine(group, lunge)
        sound(HhaSounds.CHARGE, lungeFrom, 3f, 0.8f)
        wait(dash.remaining)
        if (!ghost.isRemoved) {
            ghost.pose = HermesPose.DASH
            Vfx.trail(world, Vfx.Style.HADES, ghost, 0.8f, 14)
            sound(HhaSounds.DASH, from, 3f, 1f)
            val struck = HashSet<UUID>()
            for (step in 1..H.PINCER_DASH) {
                if (ghost.isRemoved) break
                val at = from.lerp(to, step.toDouble() / H.PINCER_DASH)
                ghost.setPosition(at.add(0.0, 0.6, 0.0))
                for (player in dash.hits(fight.targets())) {
                    if (hypot(player.x - at.x, player.z - at.z) < 2.4 && struck.add(player.uuid)) hermesHurt(ghost, player, 10f)
                }
                yield(Unit)
            }
            if (!ghost.isRemoved) ghost.pose = HermesPose.SKID
        }
        wait(lunge.remaining)
        yieldAll(thrust(lunge))
    }

    private fun pincerDrive(ghost: HermesEntity, target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        val aim = floorAt(target.entityPos)
        val toTarget = flatDir(entity.entityPos, aim) ?: forward()
        // Hermes steht hinter dem Ziel und wirft Richtung Hades: Wer zurückweicht, läuft in den Bodenschlag.
        val spot = fight.clampToCore(aim.add(toTarget.multiply(9.0)))
        blink(ghost, spot)
        ghost.pose = HermesPose.FAN
        fight.faceGhost(ghost, entity.entityPos)
        val fanDir = flatDir(spot, entity.entityPos) ?: toTarget.multiply(-1.0)
        val group = bodyBand(calls.boss())
        val fan = group.cone(spot, fanDir, BossTimings.Hermes.FAN_RANGE, BossTimings.Hermes.FAN_HALF_ANGLE, H.PINCER_FAN_WINDUP, Telegraph.Pace.PROJECTILE)
        echoCone(group, fan)
        sound(HhaSounds.CHARGE, spot, 2f, 1.4f)
        wait(H.PINCER_SLAM_DELAY)
        fight.face(aim)
        entity.pose = HadesPose.SLAM
        // Bodenschlag: nur am Hallenboden (Band ab hier für die Gruppe).
        val ring = groundBand(group).ring(floorAt(entity.entityPos), H.SLAM_RADIUS, H.SLAM_WINDUP)
        sound(HhaSounds.CHARGE, ring.center, 2.5f, 0.7f)
        wait(fan.remaining)
        if (!ghost.isRemoved) featherFan(ghost, spot, fanDir)
        wait(ring.remaining)
        yieldAll(slamImpact(ring))
    }

    /** Fünf Federn im Fächer, gerade, ohne Zielsuche (Plan 3.3). */
    private fun featherFan(ghost: HermesEntity, from: Vec3d, dir: Vec3d) {
        val origin = from.add(0.0, 1.6, 0.0)
        val feathers = BossTimings.Hermes.FAN_FEATHERS
        val spread = Math.toRadians(BossTimings.Hermes.FAN_HALF_ANGLE.toDouble()) * 2.0 / (feathers - 1)
        val base = Math.atan2(dir.z, dir.x)
        for (i in 0 until feathers) {
            val a = base + (i - (feathers - 1) / 2.0) * spread
            val bolt = HhaEntities.HERMES_BOLT.create(world, SpawnReason.EVENT) ?: continue
            bolt.kind = HermesBoltEntity.FEATHER
            bolt.damage = fight.scaledDamage(4f)
            bolt.homing = 0.0
            bolt.setOwner(ghost)
            bolt.setPosition(origin)
            bolt.velocity = Vec3d(cos(a), 0.0, sin(a)).multiply(PINCER_FEATHER_SPEED)
            // Nur so weit, wie der Kegel angesagt war (die Federn starten an der Kegelspitze).
            bolt.maxRange = BossTimings.Hermes.FAN_RANGE.toDouble()
            bolt.maxAge = kotlin.math.ceil(BossTimings.Hermes.FAN_RANGE / PINCER_FEATHER_SPEED).toInt() + 1
            world.spawnEntity(bolt)
        }
        sound(HhaSounds.FEATHER_VOLLEY, origin, 2.5f, 1f)
    }

    private fun hermesHurt(ghost: HermesEntity, player: ServerPlayerEntity, base: Float) {
        if (fight.inCutscene) return
        if (!fight.isParticipant(player) || player.entityWorld !== world) return
        if (!fight.reaches(player, BossDefense.Reach.BODY)) return
        player.damage(world, world.damageSources.mobAttack(ghost), fight.scaledDamage(base))
    }

    /** Hermes springt an seinen Platz für die Zange: Federn am Start, ein Aufblitzen am Ziel. */
    private fun blink(ghost: HermesEntity, to: Vec3d) {
        Vfx.feathers(world, Vfx.Style.HADES, ghost.entityPos.add(0.0, 1.2, 0.0), 10, 1.2f, 30)
        ghost.setPosition(to.add(0.0, 0.4, 0.0))
        Vfx.flare(world, Vfx.Style.HADES, to.add(0.0, 1.5, 0.0), 2.4f, 8)
        sound(HhaSounds.BLINK, to, 2f, 0.9f)
    }

    /**
     * Hermes als Geist in Phase III: Er greift nie für sich an. Er hält sich seitlich neben Hades, schaut auf das Ziel
     * und wartet auf die Zange. Ein Treffer vertreibt ihn für 12 s ([HadesFight.onHermesStruck]).
     */
    private fun hermesAlly(): Sequence<Unit> = sequence {
        while (fight.stage == 3 && !fight.isEnded) {
            val ghost = fight.hermes
            val hades = fight.boss
            if (fight.inCutscene || hades == null) {
                yield(Unit)
                continue
            }
            if (ghost == null || ghost.isRemoved) {
                if (--fight.hermesAway <= 0) {
                    val a = world.random.nextDouble() * Math.PI * 2.0
                    fight.spawnGhostHermes(fight.clampToCore(hades.entityPos.add(cos(a) * 9.0, 0.0, sin(a) * 9.0), 3.0).add(0.0, 0.4, 0.0))
                }
                yield(Unit)
                continue
            }
            if (!hermesBusy) {
                val target = fight.nearestTarget(hades.entityPos)
                if (target != null) {
                    val toTarget = flatDir(hades.entityPos, target.entityPos) ?: Vec3d(0.0, 0.0, 1.0)
                    val goal = fight.clampToCore(hades.entityPos.add(Vec3d(-toTarget.z, 0.0, toTarget.x).multiply(7.0)), 3.0).add(0.0, 0.4, 0.0)
                    val step = goal.subtract(ghost.entityPos)
                    if (step.length() > 0.4) ghost.setPosition(ghost.entityPos.add(step.normalize().multiply(min(0.35, step.length()))))
                    fight.faceGhost(ghost, target.entityPos)
                }
                if (ghost.pose != HermesPose.IDLE) ghost.pose = HermesPose.IDLE
            }
            yield(Unit)
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Saal-Gefahren (Phase II und III)
    // -------------------------------------------------------------------------------------------------

    /**
     * **Der eine Platz für Saal-Gefahren** (Plan 4.4): Hier und nur hier beginnt eine. Läuft schon eine, wird sie
     * weitergeführt; sonst beginnt die nächste erst, wenn ihre Uhr abgelaufen ist, kein Höhepunkt läuft oder ansteht und
     * [Telegraph] den Platz gibt. Phase II: Zug der Toten, Höllenfeuer, Spalten im Wechsel. Phase III: Seelenkette und
     * Zug der Toten im Wechsel.
     */
    private fun hazardSlot() {
        val running = hazard
        if (running != null) {
            if (running.hasNext()) running.next() else hazard = null
            return
        }
        if (alone || highlightDue() || fight.stageAge < hazardAt) return
        val group = calls.hazard() ?: return
        hazardAt = fight.stageAge + H.HAZARD_INTERVAL
        val turn = hazardTurn++
        hazard = when (fight.stage) {
            2 -> when (turn % 3) {
                0 -> marchOfTheDead(group)
                1 -> hellfire(group)
                else -> rendTheFloor(group)
            }
            else -> if (turn % 2 == 0) soulChain(group) else marchOfTheDead(group)
        }.iterator()
    }

    /**
     * Zug der Toten: Eine Wand aus Seelen kommt aus einer Pforte und läuft quer durch den Saal (W 34, dann 38 Ticks
     * Lauf). Nur die goldene Lücke (8 breit) bleibt verschont; wer getroffen wird, bekommt S 24.
     */
    private fun marchOfTheDead(group: Telegraph.Fight.Group): Sequence<Unit> = sequence {
        val reach = fight.safeRadius - 1.0
        val gate = if (gates.isEmpty()) center.add(reach, 0.0, 0.0) else gates[world.random.nextInt(gates.size)]
        val axis = flatDir(gate, center) ?: Vec3d(1.0, 0.0, 0.0)
        val across = Vec3d(-axis.z, 0.0, axis.x)
        fun point(along: Double, side: Double) = center.add(axis.multiply(along)).add(across.multiply(side))
        // Die Lücke liegt so, dass jeder sie erreicht, bevor die Wand bei ihm ist: Wer nah an der Pforte steht, hat
        // weniger Zeit. Unter fast gleich guten Lagen entscheidet der Zufall; reicht keine, wird erst die Ansage länger,
        // dann die Lücke breiter. Ist auch das nicht fair lösbar, fällt der Zug diesmal aus.
        val players = fight.targets()
        fun windupFor(gap: Double, half: Double): Int = players.maxOfOrNull { p ->
            val off = p.entityPos.subtract(center)
            val a = off.x * axis.x + off.z * axis.z
            val c = off.x * across.x + off.z * across.z
            val walk = (abs(c - gap) - half + 0.6).coerceAtLeast(0.0) * DETOUR
            val travel = ((a + reach) / (2.0 * reach)).coerceIn(0.0, 1.0) * H.DEAD_MARCH_TRAVEL
            kotlin.math.ceil(BossTimings.Min.REACTION + walk / BossTimings.Min.ESCAPE_SPEED - travel).toInt()
        } ?: 0
        var chosen: Triple<Double, Double, Int>? = null
        for (width in listOf(H.DEAD_MARCH_GAP.toDouble(), 12.0, 16.0, 20.0)) {
            val half = width / 2.0
            val limit = reach - half - 4.0
            if (limit <= 0.0) break
            val options = (-20..20).map { it / 20.0 * limit }.map { it to max(H.DEAD_MARCH_WINDUP, windupFor(it, half)) }
            val best = options.minOf { it.second }
            if (best > BossTimings.Min.ESCAPE_MAX) continue
            val near = options.filter { it.second <= best + 2 }
            val (gap, needed) = near[world.random.nextInt(near.size)]
            chosen = Triple(gap, half, needed)
            break
        }
        val (gap, half, windup) = chosen ?: run {
            group.end()
            return@sequence
        }
        // Die Ansage zeigt genau, was die Wand trifft: alles außer der goldenen Lücke.
        val low = gap - half + reach
        val high = reach - (gap + half)
        group.line(point(-reach, (gap - half - reach) / 2.0), point(reach, (gap - half - reach) / 2.0), low.toFloat(), windup)
        group.line(point(-reach, (gap + half + reach) / 2.0), point(reach, (gap + half + reach) / 2.0), high.toFloat(), windup)
        group.safeLine(point(-reach, gap), point(reach, gap), (half * 2.0).toFloat(), windup)
        // Auf dem Umgang zeigt sich die goldene Lücke noch einmal (die Wand selbst sieht man dort als Seelenstrahl).
        echoLine(group, point(-reach, gap), point(reach, gap), (half * 2.0).toFloat(), windup, safe = true)
        group.hold(windup + H.DEAD_MARCH_TRAVEL + 1)
        fight.hint("hha.hades.hint.march")
        if (gates.isNotEmpty()) gateFx(gate, surge = true, life = windup + H.DEAD_MARCH_TRAVEL + GATE_SURGE_AFTER)
        sound(HhaSounds.SOUL, gate, 3.5f, 0.5f)
        wait(group.ticks)
        val struck = HashSet<UUID>()
        for (i in 0..H.DEAD_MARCH_TRAVEL) {
            val along = -reach + 2.0 * reach * i / H.DEAD_MARCH_TRAVEL
            if (i % 2 == 0) {
                Vfx.beam(world, Vfx.Style.HADES, point(along, -reach).add(0.0, 1.0, 0.0), point(along, gap - half).add(0.0, 1.0, 0.0), 2.6f, 4)
                Vfx.beam(world, Vfx.Style.HADES, point(along, gap + half).add(0.0, 1.0, 0.0), point(along, reach).add(0.0, 1.0, 0.0), 2.6f, 4)
            }
            if (i % 8 == 0) sound(HhaSounds.SOUL, point(along, gap), 2f, 0.6f + i * 0.01f)
            for (player in fight.targets()) {
                val off = player.entityPos.subtract(center)
                val a = off.x * axis.x + off.z * axis.z
                val c = off.x * across.x + off.z * across.z
                if (abs(a - along) < H.DEAD_MARCH_DEPTH && abs(c - gap) > half && struck.add(player.uuid)) {
                    fight.hurt(player, 24f, magic = true)
                    Fx.launchPlayer(player, axis.multiply(1.2).add(0.0, 0.5, 0.0))
                }
            }
            yield(Unit)
        }
        group.end()
    }

    private class Drop(val ring: Telegraph.Ring, val bolt: HermesBoltEntity?, val top: Vec3d)

    /** Höllenfeuer: drei Wellen, je ein Kreis Radius 3 unter jedem Spieler (W 20), aus der Kuppel fällt ein Feuerball. S 12. */
    private fun hellfire(group: Telegraph.Fight.Group): Sequence<Unit> = sequence {
        val drops = ArrayList<Drop>()
        var tick = 0
        var waves = 0
        while (waves < H.HELLFIRE_WAVES || drops.isNotEmpty()) {
            if (waves < H.HELLFIRE_WAVES && tick == waves * H.HELLFIRE_GAP) {
                waves++
                for (player in fight.targets()) {
                    val spot = fight.onLevel(fight.clampToHall(player.entityPos, 2.0))
                    val ring = group.ring(spot, H.HELLFIRE_RADIUS, H.HELLFIRE_WINDUP)
                    val top = spot.add(0.0, 16.0, 0.0)
                    val ball = fight.puppet(HermesBoltEntity.FIRE, top, 1.4f)
                    if (ball != null) BossFx.projectile(world, Vfx.Style.HADES, ball, BossFx.Look.TORCH_FLAME)
                    drops.add(Drop(ring, ball, top))
                }
                sound(HhaSounds.WHOOSH, center, 2.6f, 0.5f)
            }
            var landed = false
            val iterator = drops.iterator()
            while (iterator.hasNext()) {
                val drop = iterator.next()
                val left = drop.ring.remaining
                if (left <= 0) {
                    for (player in drop.ring.hits(fight.targets())) fight.hurt(player, 12f, magic = true, reach = BossDefense.Reach.SKY)
                    Vfx.pillar(world, Vfx.Style.HADES, drop.ring.center, H.HELLFIRE_RADIUS * 0.8f, 6f, 14)
                    drop.bolt?.discard()
                    iterator.remove()
                    landed = true
                } else {
                    drop.bolt?.let { fight.steer(it, drop.ring.center.lerp(drop.top, left.toDouble() / drop.ring.ticks), DOWN) }
                }
            }
            if (landed) sound(HhaSounds.IMPACT, center, 2.6f, 1.1f)
            tick++
            yield(Unit)
        }
        group.end()
    }

    /**
     * Spalten: vier Strahlen von der Mitte, der Boden glimmt (W 24) und bricht dann 90 Ticks auf. Wer in einer Spalte
     * steckt, bekommt alle 10 Ticks S 14 und wird hinausgeschleudert.
     */
    private fun rendTheFloor(group: Telegraph.Fight.Group): Sequence<Unit> = sequence {
        if (fight.floorBusy) {
            group.end()
            return@sequence
        }
        fight.floorBusy = true
        // Bodenangriff: nur am Hallenboden (Überarbeitung 4).
        groundBand(group)
        val start = world.random.nextDouble() * Math.PI * 2.0
        val limit = fight.safeRadius - 1.0
        val length = min(H.RIFT_LENGTH.toDouble(), limit)
        val cracks = LinkedHashSet<BlockPos>()
        val rays = ArrayList<Vec3d>()
        for (ray in 0 until H.RIFT_BEAMS) {
            val a = start + ray * Math.PI * 2.0 / H.RIFT_BEAMS
            val dir = Vec3d(cos(a), 0.0, sin(a))
            rays.add(dir)
            group.line(center.add(dir.multiply(2.0)), center.add(dir.multiply(length)), H.RIFT_WIDTH, H.RIFT_WINDUP)
            var step = 2.0
            while (step <= length) {
                for (w in -1..1) {
                    val x = center.x + dir.x * step - dir.z * w
                    val z = center.z + dir.z * step + dir.x * w
                    val crack = BlockPos(MathHelper.floor(x), fight.floor.y, MathHelper.floor(z))
                    if (hypot(x - center.x, z - center.z) < limit && world.getBlockState(crack.up()).isAir) cracks.add(crack)
                }
                step += 0.5
            }
        }
        group.hold(H.RIFT_WINDUP + H.RIFT_OPEN + 1)
        // Warnung: Der Boden glimmt, bevor er nachgibt.
        for (pos in cracks) fight.scar(pos, Blocks.CRYING_OBSIDIAN.defaultState)
        sound(HhaSounds.RIFT, center, 3.5f, 0.6f)
        wait(H.RIFT_WINDUP)
        for (pos in cracks) {
            fight.scar(pos, Blocks.AIR.defaultState)
            fight.scar(pos.down(), Blocks.AIR.defaultState)
            fight.scar(pos.down(2), Blocks.AIR.defaultState)
            fight.scar(pos.down(3), Blocks.VERDANT_FROGLIGHT.defaultState)
        }
        // Die Kanten der Spalten glühen auf (erst jetzt, die Ansage ist vorbei).
        for (dir in rays.take(BossFx.CRACKS_PER_CALL)) {
            BossFx.crack(world, Vfx.Style.HADES, center.add(dir.multiply(2.0)), dir, (length - 2.0).toFloat(), life = H.RIFT_OPEN + RIFT_CRACK_AFTER, grow = RIFT_CRACK_GROW)
        }
        sound(HhaSounds.IMPACT, center, 4f, 0.5f)
        repeat(H.RIFT_OPEN) { tick ->
            if (tick % H.RIFT_TICK == 0) {
                for (player in fight.targets()) {
                    if (player.y < center.y - 0.6 && player.y > center.y - 4.5) {
                        fight.hurt(player, 14f, magic = true)
                        Fx.launchPlayer(player, Vec3d(0.0, 1.05, 0.0))
                    }
                }
            }
            yield(Unit)
        }
        fight.closeScars()
        group.end()
    }

    /**
     * Seelenkette (Phase III): Jeder Spieler bekommt einen goldenen Kreis 16 Blöcke entfernt, eine Kette zeigt dorthin.
     * Wer ihn in 6 s erreicht, ist frei; wer nicht, bekommt S 24. Keine Verlangsamung, kein Wither.
     */
    private fun soulChain(group: Telegraph.Fight.Group): Sequence<Unit> = sequence {
        val spots = LinkedHashMap<UUID, Telegraph.Ring>()
        for (player in fight.targets()) {
            val a = world.random.nextDouble() * Math.PI * 2.0
            val spot = fight.onLevel(fight.clampToHall(player.entityPos.add(cos(a) * H.SOUL_CHAIN_DISTANCE, 0.0, sin(a) * H.SOUL_CHAIN_DISTANCE), 3.0))
            spots[player.uuid] = group.safe(spot, H.SOUL_CHAIN_RADIUS, H.SOUL_CHAIN_TIME)
            player.sendMessage(Text.translatable("hha.hades.hint.soul").formatted(Formatting.GREEN), true)
        }
        sound(HhaSounds.SOUL, center, 3f, 0.55f)
        val saved = HashSet<UUID>()
        for (tick in 0 until H.SOUL_CHAIN_TIME) {
            for (player in fight.targets()) {
                val ring = spots[player.uuid] ?: continue
                if (player.uuid in saved) continue
                if (ring.contains(player)) {
                    saved.add(player.uuid)
                    Vfx.flare(world, Vfx.Style.GOLD, ring.center.add(0.0, 1.0, 0.0), 2.4f, 10)
                    sound(HhaSounds.CHIME, ring.center, 1.8f, 1.2f)
                } else if (tick % 20 == 0) {
                    Vfx.chain(world, Vfx.Style.HADES, player, ring.center.add(0.0, 1.0, 0.0), null, 22)
                }
            }
            yield(Unit)
        }
        var anyone = false
        for (player in fight.targets()) {
            if (player.uuid !in spots || player.uuid in saved) continue
            // Wer seinen goldenen Kreis nicht erreicht, wird gerichtet, auch in der Luft (saalweites Urteil, SKY).
            fight.hurt(player, 24f, magic = true, reach = BossDefense.Reach.SKY)
            Vfx.nova(world, Vfx.Style.HADES, player.entityPos.add(0.0, 1.0, 0.0), 3f, 12)
            anyone = true
        }
        if (anyone) sound(HhaSounds.ROAR, center, 2.5f, 0.6f)
        group.end()
    }

    // -------------------------------------------------------------------------------------------------
    // Die Pforten (Kulisse)
    // -------------------------------------------------------------------------------------------------

    private fun placeGates() {
        gates.clear()
        val a = world.random.nextDouble() * Math.PI * 2.0
        for (k in 0..1) {
            val angle = a + k * Math.PI
            gates.add(center.add(cos(angle) * H.GATE_RING, 0.0, sin(angle) * H.GATE_RING))
        }
    }

    private fun showGates() {
        gatesShown = world.time
        for (gate in gates) gateFx(gate, surge = false, life = H.GATE_LIFE)
    }

    /**
     * `hades.gate`: hochkant stehender Riss mit dunklem Kern am Rand. Der Client verlängert eine stehende Pforte am
     * selben Ort, statt eine zweite zu öffnen; [surge] lässt sie aufglühen (der Zug der Toten tritt aus).
     */
    private fun gateFx(gate: Vec3d, surge: Boolean, life: Int) {
        val inward = flatDir(gate, center) ?: Vec3d(1.0, 0.0, 0.0)
        BossFx.play(
            world, "hades.gate", Vfx.Style.HADES, gate, inward, a = GATE_RADIUS * 2f, b = GATE_WIDTH, c = if (surge) 1f else 0f,
            life = life,
        )
    }

    /** Zu Beginn von Phase II (in der Szene): Am Rand reißen zwei Pforten auf. Nicht angreifbar, kein Schild. */
    fun openGates() {
        placeGates()
        showGates()
        fight.sound(HhaSounds.RIFT, 4f, 0.6f)
        fight.sound(HhaSounds.THUNDER, 3f, 0.7f)
    }

    /** Was die Fessel aus diesem Teil braucht ([HadesFetters.Moves]). */
    private inner class FetterMoves : HadesFetters.Moves {
        override fun hand(): Vec3d = handOf()
        override fun staggered(ticks: Int): Sequence<Unit> = this@HadesAttacks.staggered(ticks)
        override fun fallback(): Sequence<Unit> = lunge()
        override fun hazardRunning(): Boolean = hazard != null
        override fun strike(cone: Telegraph.Cone, damage: Float) = this@HadesAttacks.strike(cone, damage, false, SIDE_RIGHT_TO_LEFT)
        override fun echo(group: Telegraph.Fight.Group, cone: Telegraph.Cone) = echoCone(group, cone)
        override fun bodyBand(group: Telegraph.Fight.Group): Telegraph.Fight.Group = this@HadesAttacks.bodyBand(group)
    }

    private companion object {
        val H = BossTimings.Hades

        /** Fluggeschwindigkeit der Federn im Zangenangriff (Blöcke je Tick). */
        const val PINCER_FEATHER_SPEED = 1.1

        /** Umwegfaktor für Laufwege im Saal (Säulen, Stufen). */
        const val DETOUR = 1.15

        /** Höchstens so viele sichere Kreise beim Urteil (drei laut Plan, Zusatzkreise nur für verteilte Gruppen). */
        const val JUDGE_MAX_CIRCLES = 6

        /** Konter: Bident senkrecht nach oben, Schaden vor Skalierung (PLAN 4.3). */
        const val ANTI_AIR_DAMAGE = 18f

        val SWEEPS = listOf(
            Sweep(HadesPose.SWEEP, BossTimings.Hades.SWEEP_A_WINDUP, 16f, SIDE_RIGHT_TO_LEFT),
            Sweep(HadesPose.SWEEP_B, BossTimings.Hades.SWEEP_B_WINDUP, 16f, -SIDE_RIGHT_TO_LEFT),
            Sweep(HadesPose.SWEEP_C, BossTimings.Hades.SWEEP_C_WINDUP, 20f, SIDE_OVERHEAD),
        )

        /** Entfernungsklassen für die Wahl des Grundangriffs. */
        const val NEAR = 10.0
        const val FAR = 20.0

        /** Gehtempo und Abstand, bei dem er stehen bleibt. */
        const val WALK = 0.28
        const val STOP_AT = 8.0

        /** Der steckende Bident: Mitte des Modells so hoch über dem Boden, die Zinken stecken im Stein. */
        const val BIDENT_STUCK_HEIGHT = 1.5

        val DOWN = Vec3d(0.0, -1.0, 0.0)

        const val GATE_HEIGHT = 4.5
        const val GATE_RADIUS = 4.5f

        // --- Überarbeitung 3: Darstellung (keine Kampfzahl) ---

        val UP = Vec3d(0.0, 1.0, 0.0)

        /** Hiebrichtung für Band und Ruck: Hieb A von rechts nach links, B zurück, C von oben. */
        const val SIDE_RIGHT_TO_LEFT = 1f
        const val SIDE_OVERHEAD = 0f

        /** Sichelband: Lebenszeit; im Hinterhalt rundum (halber Winkel, Obergrenze des Effekts). */
        const val BAND_LIFE = 14
        const val AMBUSH_BAND_HALF_ANGLE = 120f

        /** Rucke (Grad, Ticks): Bident-Hiebe rollen 1,5°, Einschläge nicken 2,5°, der Blick 2°; saalweit bis 64 Blöcke. */
        const val SWEEP_KICK = 1.5f
        const val IMPACT_KICK = 2.5f
        const val GAZE_KICK = 2f
        const val KICK_TICKS = 6
        const val ROOM_KICK_RADIUS = 64f

        /** Trümmer echter Blöcke: Einschlag des Bident (4), Bodenschlag (6), stürzende Säule (6). */
        const val IMPACT_DEBRIS = 4
        const val IMPACT_DEBRIS_RADIUS = 2.5f
        const val SLAM_DEBRIS = 6
        const val SLAM_DEBRIS_RADIUS = 4f
        const val SLAM_CRACK_SHARE = 0.7f
        const val SLAM_CRACK_LIFE = 60
        const val PILLAR_DEBRIS = 6
        const val PILLAR_DEBRIS_RADIUS = 3.5f

        /** Zerspringende Säule (`core.pillar_shatter`): in der echten Säule verborgen, sichtbar wird das Zerspringen. */
        const val SHATTER_HEIGHT = 12f
        const val SHATTER_RISE = 2
        const val SHATTER_HOLD = 2

        /** Einsturz: Risse wachsen über 60 % der Ansage, glühen danach noch kurz. */
        const val COLLAPSE_CRACK_MIN = 3f
        const val COLLAPSE_CRACK_MAX = 10f
        const val COLLAPSE_CRACK_GROW = 0.6
        const val COLLAPSE_CRACK_AFTER = 30

        /** Spalten: Risse an den Kanten, schnell gewachsen, so lange die Spalte offen ist. */
        const val RIFT_CRACK_GROW = 6
        const val RIFT_CRACK_AFTER = 10

        /** Urteil: Säulen fallen so viele Ticks vor dem Treffer los; Höhe, Radius, Nachglühen, Anordnung. */
        const val JUDGE_FALL = 4
        const val JUDGE_BEAM_HEIGHT = 28f
        const val JUDGE_BEAM_RADIUS = 1.6f
        const val JUDGE_BEAM_LIFE = 22
        const val JUDGE_MAX_BEAMS = 22
        const val JUDGE_SAFE_MARGIN = 2.0
        const val JUDGE_BEAM_SPACING = 5.0
        val JUDGE_RINGS = listOf(9.0 to 4, 19.0 to 7, 30.0 to 10)

        /** Schattenschritt: Wirbel beim Verschwinden; Herzschlag-Rand (Stärke, Erneuerung, Überlappung). */
        const val VANISH_SWIRL_RADIUS = 2.6f
        const val VANISH_SWIRL_LIFE = 18
        const val HUNTED_STRENGTH = 0.18f
        const val HUNTED_RENEW = 40
        const val HUNTED_OVERLAP = 4

        /** Pforte: Breite des Risses; Aufglühen hält über den Zug der Toten hinaus. */
        const val GATE_WIDTH = 3.6f
        const val GATE_SURGE_AFTER = 20

        // --- Überarbeitung 4 (P19): Ebenen und Einsturz; keine Kampfzahl ---

        /** Höhenband der Körperangriffe: über die höchste Ebene hinaus so viel (Pflicht 3: „plus 3“). */
        const val BAND_ABOVE_LEVEL = 3.0

        /** Echo einer Linie: Schrittweite der Abtastung und kürzestes Stück, das gezeigt wird. */
        const val ECHO_STEP = 0.5
        const val ECHO_MIN_LENGTH = 1.0

        /** Einsturz: Eine Spalte gilt als hoch (Turm, Säule, Lehne), wenn sie so weit über die höchste Ebene reicht. */
        const val TALL_ABOVE_LEVEL = 2

        /** Einsturz: Grube so tief unter dem Hallenboden (unten Schwarzstein und Magma, wie bisher). */
        const val PIT_DEPTH = 7

        /** Ebenen-IDs des Umgangs im Vertrag (`walk_east`, `walk_west`). */
        const val WALK_PREFIX = "walk"
    }
}
