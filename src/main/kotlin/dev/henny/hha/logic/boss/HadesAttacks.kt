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
import dev.henny.hha.world.TartarusPlans
import net.minecraft.block.Blocks
import net.minecraft.entity.SpawnReason
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
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
 * - Phase II „Die Pforten der Hölle“: dazu Seelenketten und Schattenschritt, und der Saal greift mit an: Zug der Toten,
 *   Höllenfeuer und Spalten im Wechsel. Die zwei Pforten am Rand sind reine Kulisse.
 * - Phase III „Das Bündnis“: dazu der Zangenangriff mit Hermes, das Urteil und der Einsturz an den Lebensschwellen;
 *   als Saal-Gefahr nur noch Seelenkette und Zug der Toten.
 *
 * Saal-Gefahren laufen über **einen** Platz ([hazardSlot]): nie zwei gleichzeitig, nie zusammen mit Blick,
 * Schattenschritt, Urteil oder Einsturz. Spätere Phasen werden nur über kürzere Pausen schneller, die Ansagen bleiben.
 *
 * Können lohnt sich: Wer vor einer Säule ausweicht, lässt ihn beim Ausfallschritt hineinrennen; wer hinter einer Säule
 * steht, reißt seine Ketten; nach Bodenschlag, Wurf, Schattenschritt und Urteil steht er offen (goldene Rune,
 * [HadesFight.openingFor]), und zu viel Schaden außerhalb davon bricht seine Haltung.
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

    fun reset() {
        last = ""
        poiseBroken = false
        hazard = null
        alone = false
        hermesBusy = false
    }

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
        wait(pause())
    }

    /** Ein Höhepunkt: Solange er läuft, beginnt keine Saal-Gefahr. */
    private fun solo(body: Sequence<Unit>): Sequence<Unit> = sequence {
        alone = true
        yieldAll(body)
        alone = false
    }

    /**
     * Wahl nach Entfernung aus dem Repertoire der Phase. Nah: Hiebfolge, Bodenschlag. Mittel: Ausfallschritt.
     * Fern: Bident-Wurf, Ausfallschritt. Ab Phase II auf Abstand die Seelenketten und überall der Schattenschritt
     * (nur, wenn keine Saal-Gefahr läuft).
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
            "chains" -> reapingChains()
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
            entity.setPosition(fight.clampToHall(entity.entityPos.add(to.normalize().multiply(WALK))))
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

    private class Sweep(val pose: Int, val windup: Int, val damage: Float)

    /**
     * Hiebfolge: Hieb A (W 14), Rückhand B (W 10), Schlusshieb von oben C (W 16), je ein Kegel 75° halb, Radius 8,5.
     * Jeder Folgehieb bekommt seine eigene Ansage, sobald der vorige gefallen ist. Danach steht er F 26 gestützt.
     */
    private fun sweepCombo(): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        entity.cloaked = false
        val group = calls.boss()
        for ((i, hit) in SWEEPS.withIndex()) {
            val target = fight.nearestTarget(entity.entityPos) ?: break
            fight.face(target.entityPos)
            entity.pose = hit.pose
            val cone = group.cone(
                floorAt(entity.entityPos), forward(), H.SWEEP_RADIUS, H.SWEEP_HALF_ANGLE, hit.windup,
                if (i == 0) null else Telegraph.Pace.FOLLOW_UP,
            )
            sound(HhaSounds.WHOOSH, entity.entityPos, 1.6f, 0.55f + i * 0.12f)
            wait(cone.ticks)
            strike(cone, hit.damage, i == SWEEPS.size - 1)
            // Der Schlag selbst läuft noch [SWEEP_STRIKE] Ticks, erst dann holt er zum nächsten aus.
            if (i < SWEEPS.size - 1) wait(H.SWEEP_STRIKE)
        }
        wait(H.SWEEP_WINDOW)
        entity.pose = HadesPose.IDLE
    }

    /** Ein Hieb fällt: wer im Kegel steht, ein Flächeneffekt, beim Schlusshieb ein kurzes Rütteln. */
    private fun strike(cone: Telegraph.Cone, damage: Float, final: Boolean) {
        for (player in cone.hits(fight.targets())) {
            if (player.y > center.y + 3.0) continue
            fight.hurt(player, damage)
            shove(player, cone.apex, 0.9, 0.35)
        }
        Vfx.crescent(world, Vfx.Style.HADES, cone.apex.add(cone.direction.multiply(2.0)).add(0.0, 1.6, 0.0), cone.direction, H.SWEEP_RADIUS, 7f, 8)
        sound(HhaSounds.DASH, cone.apex, 3f, if (final) 0.5f else 0.65f)
        if (final) calls.hitShake(cone.apex, 3f)
    }

    /** Ausfallschritt: Linie Breite 2,2, W 18, S 22. Wer vor einer Säule ausweicht, lässt ihn hineinrennen. */
    private fun lunge(): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        val target = fight.nearestTarget(entity.entityPos) ?: return@sequence
        entity.cloaked = false
        fight.face(target.entityPos)
        entity.pose = HadesPose.THRUST
        val (from, to) = thrustPath(entity.entityPos, target.entityPos)
        val line = calls.line(from, to, H.THRUST_WIDTH, H.THRUST_WINDUP, Telegraph.Pace.HEAVY)
        sound(HhaSounds.CHARGE, from, 3f, 0.8f)
        wait(line.ticks)
        yieldAll(thrust(line))
    }

    private fun thrustPath(from: Vec3d, toward: Vec3d): Pair<Vec3d, Vec3d> {
        val dir = flatDir(from, toward) ?: forward()
        val reach = (hypot(toward.x - from.x, toward.z - from.z) + 7.0).coerceIn(12.0, 34.0)
        return floorAt(from) to fight.clampToHall(from.add(dir.multiply(reach)))
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
                calls.hitShake(at, 4f)
                sound(HhaSounds.IMPACT, at, 4f, 0.65f)
                yieldAll(staggered(H.THRUST_PILLAR_STAGGER))
                return@sequence
            }
            entity.setPosition(at)
            for (player in fight.targets()) {
                if (player.uuid in struck || !line.contains(player)) continue
                if (hypot(player.x - at.x, player.z - at.z) < 2.6 && player.y < center.y + 3.5) {
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
        val ring = calls.ring(floorAt(entity.entityPos), H.SLAM_RADIUS, H.SLAM_WINDUP)
        sound(HhaSounds.CHARGE, ring.center, 2.5f, 0.7f)
        wait(ring.ticks)
        yieldAll(slamImpact(ring))
    }

    private fun slamImpact(ring: Telegraph.Ring): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        for (player in ring.hits(fight.targets())) {
            if (!fight.grounded(player)) continue
            fight.hurt(player, 24f)
            shove(player, ring.center, 1.2, 0.7)
        }
        Vfx.shockwave(world, Vfx.Style.HADES, ring.center, H.SLAM_RADIUS, 16, 3f)
        calls.hitShake(ring.center, 4f)
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
        val landing = fight.clampToHall(target.entityPos, 2.0)
        val ring = calls.ring(landing, H.THROW_RADIUS, H.THROW_WINDUP, pace = Telegraph.Pace.PROJECTILE)
        sound(HhaSounds.CHARGE, entity.entityPos, 3f, 0.9f)
        wait(H.THROW_RELEASE)
        entity.bidentOut = true
        val hand = handOf()
        val stuck = landing.add(0.0, BIDENT_STUCK_HEIGHT, 0.0)
        val bolt = fight.puppet(HermesBoltEntity.BIDENT, hand)
        if (bolt != null) Vfx.trail(world, Vfx.Style.HADES, bolt, 0.6f, ring.remaining + 4)
        sound(HhaSounds.SPEAR, hand, 3.5f, 0.75f)
        val flight = ring.remaining.coerceAtLeast(1)
        val path = stuck.subtract(hand)
        for (i in 1..flight) {
            if (bolt != null) fight.steer(bolt, hand.lerp(stuck, i.toDouble() / flight), path)
            yield(Unit)
        }
        // Einschlag.
        for (player in ring.hits(fight.targets())) {
            fight.hurt(player, 22f)
            shove(player, landing, 1.2, 0.6)
        }
        Vfx.shockwave(world, Vfx.Style.HADES, landing, H.THROW_RADIUS, 14, 2.4f)
        calls.hitShake(landing, 4f)
        sound(HhaSounds.IMPACT, landing, 3.5f, 0.85f)
        if (bolt != null) fight.steer(bolt, stuck, DOWN)
        entity.pose = HadesPose.EMPTY
        markOpen(H.EMPTY_WINDOW)
        wait(H.EMPTY_WINDOW - H.RETURN_WINDUP)
        // Er ruft ihn zurück: Die Linie zeigt den Rückweg.
        val back = calls.line(landing, floorAt(entity.entityPos), H.RETURN_WIDTH, H.RETURN_WINDUP, Telegraph.Pace.PROJECTILE)
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
    // Phase II und III: Ketten, Schattenschritt
    // -------------------------------------------------------------------------------------------------

    /**
     * Seelenketten: grüne Ketten zu allen Spielern in Sicht (W 14), dann 20 Ticks Zug, dann der Schlusshieb von oben
     * (S 20). Wer sich hinter eine Säule zieht, reißt die Kette: Er strauchelt (offen, F 30) und der Hieb fällt aus.
     */
    private fun reapingChains(): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        val bound = fight.targets().filter { exposed(headOf(), it) }
        if (bound.isEmpty()) {
            yieldAll(lunge())
            return@sequence
        }
        entity.cloaked = false
        bound.minByOrNull { it.squaredDistanceTo(entity) }?.let { fight.face(it.entityPos) }
        entity.pose = HadesPose.CHAINS
        val group = calls.boss(players = bound, reserve = H.CHAINS_PULL_END)
        sound(HhaSounds.CHAIN, entity.entityPos, 3f, 1f)
        // Die Ketten fliegen sofort (das ist die Ansage) und greifen nach [CHAINS_CAST] Ticks: Wer bis dahin oder
        // während des Zugs hinter einer Säule steht, reißt sie.
        for (player in bound) {
            player.sendMessage(Text.translatable("hha.hades.hint.chains").formatted(Formatting.GREEN), true)
            Vfx.chain(world, Vfx.Style.HADES, entity, player.entityPos, player, H.CHAINS_PULL_END + 4)
        }
        wait(H.CHAINS_CAST)
        val held = bound.toMutableList()
        var broken = false
        repeat(H.CHAINS_PULL) { tick ->
            val eye = headOf()
            val iterator = held.iterator()
            while (iterator.hasNext()) {
                val player = iterator.next()
                // Ausgeschieden oder in eine andere Welt gewechselt: Die Kette lässt los, ohne dass es als Riss zählt.
                if (!fight.isParticipant(player) || player.entityWorld !== world || player.isRemoved) {
                    iterator.remove()
                    continue
                }
                if (!player.isAlive || !exposed(eye, player)) {
                    // Die Säule hält die Kette auf: Sie reißt, und das bringt ihn aus dem Tritt.
                    iterator.remove()
                    broken = true
                    Vfx.flare(world, Vfx.Style.GOLD, player.entityPos.add(0.0, 1.2, 0.0), 2f, 8)
                    sound(HhaSounds.DEFLECT, player.entityPos, 2f, 0.6f)
                } else if (tick % 4 == 0) {
                    val pull = flatDir(player.entityPos, entity.entityPos) ?: Vec3d.ZERO
                    Fx.launchPlayer(player, pull.multiply(0.65).add(0.0, player.velocity.y.coerceAtLeast(-0.1), 0.0))
                }
            }
            yield(Unit)
        }
        if (broken) {
            group.end()
            yieldAll(staggered(H.CHAINS_BREAK_STAGGER))
            return@sequence
        }
        fight.nearestTarget(entity.entityPos)?.let { fight.face(it.entityPos) }
        entity.pose = HadesPose.SWEEP_C
        val cone = group.cone(floorAt(entity.entityPos), forward(), H.SWEEP_RADIUS, H.SWEEP_HALF_ANGLE, H.SWEEP_C_WINDUP, Telegraph.Pace.HEAVY)
        sound(HhaSounds.WHOOSH, entity.entityPos, 1.8f, 0.5f)
        wait(cone.ticks)
        strike(cone, 20f, true)
        wait(H.SWEEP_WINDOW)
        entity.pose = HadesPose.IDLE
    }

    /**
     * Schattenschritt: Er setzt den Helm auf (VANISH), unter dem Ziel steht eine Rune (Radius 5, W 24); kurz vor dem
     * Treffer taucht er hinter dem Ziel auf und schlägt rundum (AMBUSH, S 22). Wer die Rune verlässt, bleibt heil.
     * Danach steht er offen (F 60).
     */
    private fun ambush(): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        val target = fight.randomTarget() ?: return@sequence
        entity.pose = HadesPose.VANISH
        entity.cloaked = true
        val spot = fight.clampToHall(target.entityPos, 3.0)
        val ring = calls.ring(spot, H.AMBUSH_RADIUS, H.AMBUSH_WINDUP, pace = Telegraph.Pace.HEAVY)
        sound(HhaSounds.SOUL, entity.entityPos, 2.5f, 0.45f)
        wait((ring.ticks - H.AMBUSH_HIT).coerceAtLeast(0))
        // Er taucht hinter dem Ziel auf, innerhalb der Rune.
        val look = target.getRotationVec(1f).let { flatDir(Vec3d.ZERO, it) } ?: forward()
        val appear = fight.clampToHall(spot.subtract(look.multiply(2.4)), 3.0)
        entity.setPosition(appear)
        fight.face(target.entityPos)
        entity.cloaked = false
        entity.pose = HadesPose.AMBUSH
        Vfx.flare(world, Vfx.Style.HADES, appear.add(0.0, 2.0, 0.0), 3.5f, 8)
        sound(HhaSounds.BLINK, appear, 2.6f, 0.6f)
        wait(ring.remaining)
        for (player in ring.hits(fight.targets())) {
            fight.hurt(player, 22f)
            shove(player, appear, 1.0, 0.4)
        }
        Vfx.crescent(world, Vfx.Style.HADES, appear.add(0.0, 1.4, 0.0), forward(), H.AMBUSH_RADIUS, 6f, 10)
        calls.hitShake(appear, 3f)
        sound(HhaSounds.DASH, appear, 3f, 0.55f)
        markOpen(H.AMBUSH_WINDOW)
        wait(H.AMBUSH_WINDOW)
        entity.pose = HadesPose.IDLE
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
        Vfx.pillar(world, Vfx.Style.HADES, base, 2.6f, 18f, 24)
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
        sound(HhaSounds.CHARGE, entity.entityPos, 4f, 0.45f)
        repeat(H.GAZE_WINDUP) { tick ->
            if (tick % 6 == 0) {
                val eye = headOf()
                for (player in fight.targets()) {
                    if (exposed(eye, player)) Vfx.beam(world, Vfx.Style.HADES, eye, player.eyePos.subtract(0.0, 0.3, 0.0), 0.3f, 7)
                }
            }
            yield(Unit)
        }
        val eye = headOf()
        val fallen = HashSet<Pair<Int, Int>>()
        for (player in fight.targets()) {
            if (exposed(eye, player)) {
                Vfx.beam(world, Vfx.Style.HADES, eye, player.eyePos.subtract(0.0, 0.3, 0.0), 2.6f, 14)
                fight.hurt(player, 32f, magic = true)
            } else {
                pillarBetween(entity.entityPos, player.entityPos)?.let { fallen.add(it) }
            }
        }
        for (pillar in fallen) {
            Vfx.beam(world, Vfx.Style.HADES, eye, pillarAt(pillar).add(0.0, 3.0, 0.0), 2.6f, 14)
            crumble(pillar, loud = false)
        }
        calls.roomShake(entity.entityPos, 6f)
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
        wait(group.ticks)
        for (player in fight.targets()) {
            if (safe.none { it.contains(player) }) fight.hurt(player, 34f, magic = true)
        }
        Vfx.shockwave(world, Vfx.Style.HADES, center, fight.safeRadius.toFloat(), 22, 6f)
        calls.roomShake(center, 7f)
        sound(HhaSounds.THUNDER, center, 4f, 0.6f)
        markOpen(H.JUDGE_WINDOW)
        wait(H.JUDGE_WINDOW)
        entity.pose = HadesPose.IDLE
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
        val best = options.minOf { it.first }
        val near = options.filter { it.first <= best + 2.0 }
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
            spots.add(at)
        }
        return spots
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
     */
    private fun collapse(): Sequence<Unit> = sequence {
        val entity = fight.boss ?: return@sequence
        val index = (collapsesDone + 1).coerceAtMost(H.COLLAPSE_RADII.size - 1)
        collapsesDone++
        val next = H.COLLAPSE_RADII[index].toDouble()
        val outer = fight.safeRadius
        if (next >= outer) return@sequence
        entity.cloaked = false
        entity.pose = HadesPose.ROAR
        val band = ArrayList<Pair<Int, Int>>()
        val limit = outer.toInt() + 1
        for (dx in -limit..limit) {
            for (dz in -limit..limit) {
                val r = hypot(dx.toDouble(), dz.toDouble())
                if (r > next && r <= outer) band.add(dx to dz)
            }
        }
        for ((dx, dz) in band) {
            // Glimmt, schadet aber nicht: Der Einsturz selbst ist die Gefahr.
            fight.edit(fight.floor.add(dx, 0, dz), if ((dx + dz) and 1 == 0) Blocks.CRYING_OBSIDIAN.defaultState else Blocks.VERDANT_FROGLIGHT.defaultState)
        }
        val group = calls.boss(pace = Telegraph.Pace.ROOM)
        group.safe(center, next.toFloat(), H.COLLAPSE_WINDUP)
        fight.hint("hha.hades.hint.collapse", Formatting.GOLD)
        sound(HhaSounds.RIFT, center, 4f, 0.5f)
        wait(group.ticks)
        if (fight.isEnded || fight.stage != 3) return@sequence
        fight.safeRadius = next
        for (pillar in fight.intactPillars.toList()) {
            if (hypot(pillar.first.toDouble(), pillar.second.toDouble()) > next - 1.0) crumble(pillar)
        }
        Vfx.shockwave(world, Vfx.Style.HADES, center, outer.toFloat(), 26, 6f)
        calls.roomShake(center, 7f)
        sound(HhaSounds.IMPACT, center, 4f, 0.4f)
        // Von außen nach innen, ein paar Spalten je Tick, damit es wie ein echter Einsturz aussieht.
        val order = band.sortedByDescending { hypot(it.first.toDouble(), it.second.toDouble()) }
        val perTick = (order.size / H.COLLAPSE_FALL).coerceAtLeast(8)
        var i = 0
        while (i < order.size) {
            repeat(perTick) {
                if (i < order.size) {
                    val (dx, dz) = order[i++]
                    dropColumn(dx, dz)
                }
            }
            yield(Unit)
        }
        entity.pose = HadesPose.IDLE
    }

    private fun dropColumn(dx: Int, dz: Int) {
        for (y in 0 downTo -6) fight.edit(fight.floor.add(dx, y, dz), Blocks.AIR.defaultState)
        fight.edit(fight.floor.add(dx, -7, dz), if ((dx * 3 + dz) and 7 == 0) Blocks.MAGMA_BLOCK.defaultState else Blocks.BLACKSTONE.defaultState)
        // Was darauf stand, fällt mit.
        for (y in 1..TartarusPlans.DAIS_CLEAR_HEIGHT) {
            val pos = fight.floor.add(dx, y, dz)
            if (!world.getBlockState(pos).isAir) fight.edit(pos, Blocks.AIR.defaultState)
        }
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
        val from = fight.clampToHall(aim.add(side.multiply(H.PINCER_HERMES_RANGE)), 2.0)
        val to = fight.clampToHall(aim.subtract(side.multiply(H.PINCER_HERMES_RANGE - 2.0)), 2.0)
        blink(ghost, from)
        ghost.pose = HermesPose.DASH_WINDUP
        fight.faceGhost(ghost, to)
        val group = calls.boss()
        val dash = group.line(from, to, BossTimings.Hermes.DASH_WIDTH, H.PINCER_LINE_WINDUP, Telegraph.Pace.HEAVY)
        sound(HhaSounds.CHARGE, from, 2f, 1.3f)
        wait(H.PINCER_DELAY)
        // Hades setzt quer dazu an.
        fight.face(aim)
        entity.pose = HadesPose.THRUST
        val (lungeFrom, lungeTo) = thrustPath(entity.entityPos, aim)
        val lunge = group.line(lungeFrom, lungeTo, H.THRUST_WIDTH, H.THRUST_WINDUP, Telegraph.Pace.HEAVY)
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
        val spot = fight.clampToHall(aim.add(toTarget.multiply(9.0)), 2.0)
        blink(ghost, spot)
        ghost.pose = HermesPose.FAN
        fight.faceGhost(ghost, entity.entityPos)
        val fanDir = flatDir(spot, entity.entityPos) ?: toTarget.multiply(-1.0)
        val group = calls.boss()
        val fan = group.cone(spot, fanDir, BossTimings.Hermes.FAN_RANGE, BossTimings.Hermes.FAN_HALF_ANGLE, H.PINCER_FAN_WINDUP, Telegraph.Pace.PROJECTILE)
        sound(HhaSounds.CHARGE, spot, 2f, 1.4f)
        wait(H.PINCER_SLAM_DELAY)
        fight.face(aim)
        entity.pose = HadesPose.SLAM
        val ring = group.ring(floorAt(entity.entityPos), H.SLAM_RADIUS, H.SLAM_WINDUP)
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
                    fight.spawnGhostHermes(fight.clampToHall(hades.entityPos.add(cos(a) * 9.0, 0.0, sin(a) * 9.0), 3.0).add(0.0, 0.4, 0.0))
                }
                yield(Unit)
                continue
            }
            if (!hermesBusy) {
                val target = fight.nearestTarget(hades.entityPos)
                if (target != null) {
                    val toTarget = flatDir(hades.entityPos, target.entityPos) ?: Vec3d(0.0, 0.0, 1.0)
                    val goal = fight.clampToHall(hades.entityPos.add(Vec3d(-toTarget.z, 0.0, toTarget.x).multiply(7.0)), 3.0).add(0.0, 0.4, 0.0)
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
        group.hold(windup + H.DEAD_MARCH_TRAVEL + 1)
        fight.hint("hha.hades.hint.march")
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
                    val spot = fight.clampToHall(player.entityPos, 2.0)
                    val ring = group.ring(spot, H.HELLFIRE_RADIUS, H.HELLFIRE_WINDUP)
                    val top = spot.add(0.0, 16.0, 0.0)
                    drops.add(Drop(ring, fight.puppet(HermesBoltEntity.FIRE, top, 1.4f), top))
                }
                sound(HhaSounds.WHOOSH, center, 2.6f, 0.5f)
            }
            var landed = false
            val iterator = drops.iterator()
            while (iterator.hasNext()) {
                val drop = iterator.next()
                val left = drop.ring.remaining
                if (left <= 0) {
                    for (player in drop.ring.hits(fight.targets())) fight.hurt(player, 12f, magic = true)
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
        val start = world.random.nextDouble() * Math.PI * 2.0
        val limit = fight.safeRadius - 1.0
        val length = min(H.RIFT_LENGTH.toDouble(), limit)
        val cracks = LinkedHashSet<BlockPos>()
        for (ray in 0 until H.RIFT_BEAMS) {
            val a = start + ray * Math.PI * 2.0 / H.RIFT_BEAMS
            val dir = Vec3d(cos(a), 0.0, sin(a))
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
            val spot = fight.clampToHall(player.entityPos.add(cos(a) * H.SOUL_CHAIN_DISTANCE, 0.0, sin(a) * H.SOUL_CHAIN_DISTANCE), 3.0)
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
            fight.hurt(player, 24f, magic = true)
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
        for (gate in gates) {
            val inward = flatDir(gate, center) ?: Vec3d(1.0, 0.0, 0.0)
            Vfx.rift(world, Vfx.Style.HADES, gate.add(0.0, GATE_HEIGHT, 0.0), inward, GATE_RADIUS, H.GATE_LIFE)
        }
    }

    /** Zu Beginn von Phase II (in der Szene): Am Rand reißen zwei Pforten auf. Nicht angreifbar, kein Schild. */
    fun openGates() {
        placeGates()
        showGates()
        fight.sound(HhaSounds.RIFT, 4f, 0.6f)
        fight.sound(HhaSounds.THUNDER, 3f, 0.7f)
    }

    private companion object {
        val H = BossTimings.Hades

        /** Fluggeschwindigkeit der Federn im Zangenangriff (Blöcke je Tick). */
        const val PINCER_FEATHER_SPEED = 1.1

        /** Umwegfaktor für Laufwege im Saal (Säulen, Stufen). */
        const val DETOUR = 1.15

        /** Höchstens so viele sichere Kreise beim Urteil (drei laut Plan, Zusatzkreise nur für verteilte Gruppen). */
        const val JUDGE_MAX_CIRCLES = 6

        val SWEEPS = listOf(
            Sweep(HadesPose.SWEEP, BossTimings.Hades.SWEEP_A_WINDUP, 16f),
            Sweep(HadesPose.SWEEP_B, BossTimings.Hades.SWEEP_B_WINDUP, 16f),
            Sweep(HadesPose.SWEEP_C, BossTimings.Hades.SWEEP_C_WINDUP, 20f),
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
    }
}
