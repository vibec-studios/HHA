package dev.henny.hha.logic.boss.sig

import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.CoreEntity
import dev.henny.hha.entity.boss.KronosEntity
import dev.henny.hha.entity.boss.KronosPose
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.ArenaNav
import dev.henny.hha.logic.boss.BossDefense
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Cover
import dev.henny.hha.logic.boss.HadesFight
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha.logic.boss.arena.ArenaBlueprints
import dev.henny.hha.logic.boss.timings.KronosSigTimings
import dev.henny.hha.world.TartarusArenas
import dev.henny.hha.world.TartarusPlans
import dev.henny.hha.world.arena.ArenaSpec
import net.minecraft.block.Blocks
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Einstiegspunkt der Signatur-Mechanik: Kronos: Stillstand mit Säulen-Deckung (PLAN 4.2). Gerüst P00 (Überarbeitung 4, PLAN 7.7), Besitzer P20.
 * [init] ruft `Hha.onInitialize` genau einmal auf (Ereignisse anmelden, Geräte-Handler, Renderer-Arten nur am Client).
 *
 * Inhalt (Briefing P20):
 * - [Run]: der Stillstand mit dem Sanduhr-Strahl. Kronos hebt die Hand, sagt den Strahl an (Startkante, überstrichener
 *   Halbkreis, Drehsinn; ROOM mit Erreichbarkeitsrechnung), dann steht die Zeit und der Strahl schwenkt 180°. Wer im
 *   Strahl steht und keine Säule zwischen sich und der Hand hat, wird an seinen Ort von vor 60 Ticks zurückgespult und
 *   nimmt 22 (SKY). Säulen, die gedeckt haben, altern danach auf einen Stumpf; zu Beginn der zweiten Kronos-Phase stehen
 *   sie wieder ([Run.restoreAged]).
 * - [Run.coreSpot]: Kronos' eigene Zielpunkte liegen im Kern (r ≤ 38, nicht in Deckung, nicht auf dem Thronpodest).
 * - [Run.echo]: zweite Ansage-Form auf Umgang und Podest für Linien, die eine Ebene erreichen.
 * - [HourglassBars]: je Sanduhr der Rückspulung eine Unterleiste (`Cine.subBars`).
 *
 * Winkel wie im Vertrag (`ArenaGeo`): 0° Osten, 90° Süden, waagerecht `atan2(dz, dx)`.
 */
object KronosStasis {
    fun init() {}

    /** Was der Stillstand von `KronosAttacks` braucht: Schaden mit Kronos' Faktor, Zeitstopp-Pass und Geschosse. */
    interface Host {
        /** Schaden über den vorhandenen Weg (× `POWER` 1,5, dann × 1,2 in `HadesFight.hurt`). */
        fun strike(player: ServerPlayerEntity, base: Float, magic: Boolean, reach: BossDefense.Reach): Boolean

        /** Zeitstopp an: Pass, Zeitfaktor der Effekte, Vignette, Geschosse halten an. */
        fun freezeTime(entity: KronosEntity, ticks: Int)

        /** Je Tick, solange die Zeit steht (wer geht, bekommt das Ende des Passes einzeln). */
        fun whileFrozen()

        /** Zeitstopp regulär vorbei: Geschosse fliegen weiter. */
        fun resumeTime()
    }

    // =================================================================================================================
    // Stillstand
    // =================================================================================================================

    /** Ein Kampf, eine Instanz (gehalten von `KronosAttacks`). Zustand nur für den laufenden Kampf, nichts Verstecktes. */
    class Run(private val fight: HadesFight, private val host: Host) {

        private val world get() = fight.world
        private val center get() = fight.center
        private val calls get() = fight.kronosCalls

        /** Vertrag des Saals (Deckung `pillar_0..7`, Ebenen, Kern). */
        val spec: ArenaSpec = TartarusArenas.hall(fight.floor)

        /** Wo jeder Spieler war (neuester hinten), je Tick, höchstens `REWIND_TICKS` + 1 Einträge. */
        private val history = HashMap<UUID, ArrayDeque<Vec3d>>()

        /** Der Hinweis „Hinter Stein vergeht die Zeit normal“ kam in diesem Kampf schon (einmal je Kampf). */
        private var hinted = false

        /** Je Tick der Phase (aus `KronosAttacks.tick`): Positionsverlauf der Mitkämpfer. */
        fun track() {
            val alive = HashSet<UUID>()
            for (player in fight.targets()) {
                alive.add(player.uuid)
                val trail = history.getOrPut(player.uuid) { ArrayDeque() }
                trail.addLast(player.entityPos)
                while (trail.size > KronosSigTimings.REWIND_TICKS + 1) trail.removeFirst()
            }
            history.keys.retainAll(alive)
        }

        /** Phasenwechsel, Szene, Ausgang: Der Verlauf beginnt neu (Teleports der Szenen sollen nicht zurückspulen). */
        fun reset() {
            history.clear()
        }

        /**
         * Der Stillstand (alle `HALT_INTERVAL` 1200, als `solo` aus `KronosAttacks.next`). Heben, Zeit, Langsamkeit und
         * Fenster wie bisher; die drei Einschläge je Spieler entfallen (PLAN 4.3), an ihre Stelle tritt der Strahl.
         */
        fun standstill(): Sequence<Unit> = sequence {
            val entity = fight.kronos ?: return@sequence
            val k = BossTimings.Kronos
            // Er steht im Kern, nie in einer Säule: sonst läge die Quelle der Sichtlinie im Stein.
            val stand = coreSpot(entity.entityPos)
            if (stand.squaredDistanceTo(entity.entityPos) > 0.01) entity.setPosition(stand)
            fight.nearestTarget(entity.entityPos)?.let { fight.faceTitan(it.entityPos) }
            entity.pose = KronosPose.STASIS
            calls.sound(HhaSounds.CHARGE, entity.entityPos, 3.5f, 0.35f)
            wait(k.HALT_RAISE)

            // --- Ansage: Startkante, Halbkreis, Drehsinn -------------------------------------------------------
            val source = hand(entity)
            val beam = Beam.plan(source, midDegrees(entity, source), targets())
            val group = calls.boss(pace = Telegraph.Pace.ROOM)
            val apex = floorAt(source)
            // Der Telegraph hebt zu kurze Ansagen an: Ab hier gilt die Dauer der Form.
            val area = group.cone(apex, Beam.dir(beam.midDeg), KronosSigTimings.RAY_RADIUS, KronosSigTimings.RAY_ARC / 2f, ansageTicks(beam))
            val ansage = area.ticks
            group.line(apex, apex.add(Beam.dir(beam.startDeg).multiply(KronosSigTimings.RAY_RADIUS.toDouble())), START_EDGE_WIDTH, ansage)
            group.hold(ansage + KronosSigTimings.RAY_SWEEP)
            BossFx.play(
                world, "kronos.ray", Vfx.Style.AEON, apex, Beam.dir(beam.startDeg), a = ansage.toFloat(),
                b = KronosSigTimings.RAY_SWEEP.toFloat(), c = beam.sign.toFloat(), d = KronosSigTimings.RAY_RADIUS,
                life = ansage + KronosSigTimings.RAY_SWEEP + RAY_FX_TAIL, entity = entity,
            )
            Cover.mark(calls, spec, TartarusArenas.KRONOS_STASIS, source, ansage + KronosSigTimings.RAY_SWEEP)
            calls.sound(HhaSounds.APPROACH_TICKING, source, 3f, 0.7f)
            val statusFrom = if (hinted) 0 else KronosSigTimings.HINT_HOLD
            if (!hinted) {
                hinted = true
                fight.hint("hha.kronos.hint.stasis", Formatting.GOLD)
            }
            entity.yaw = Beam.yaw(beam.startDeg)
            for (tick in 0 until ansage) {
                if (tick >= statusFrom && tick % KronosSigTimings.STATUS_EVERY == 0) showStatus(beam, 0.0, emptySet())
                yield(Unit)
            }

            // --- Die Zeit steht, der Strahl schwenkt ----------------------------------------------------------
            for (player in targets()) {
                // Überarbeitung 2 (PLAN 12.3): höchstens Langsamkeit I, kein Kontrollverlust.
                player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, k.HALT_SLOWNESS, 0, false, false, true))
            }
            calls.sound(HhaSounds.THUNDER, center, 4f, 1.6f)
            calls.sound(HhaSounds.TIME_STOP, center, 4f, 1f)
            host.freezeTime(entity, k.HALT_FROZEN)
            var frozen = true
            val struck = HashSet<UUID>()
            val sheltering = LinkedHashSet<String>()
            for (step in 1..KronosSigTimings.RAY_SWEEP) {
                if (frozen && step > k.HALT_FROZEN) {
                    frozen = false
                    host.resumeTime()
                    calls.sound(HhaSounds.BLINK, center, 3.5f, 1.4f)
                }
                if (frozen) host.whileFrozen()
                val lead = Beam.lead(step)
                val trail = Beam.trail(lead)
                entity.yaw = Beam.yaw(beam.startDeg + beam.sign * (lead + trail) / 2.0)
                for (player in targets()) {
                    if (player.uuid in struck) continue
                    if (!beam.inBeam(player.entityPos, trail, lead)) continue
                    val spot = Cover.shelter(world, source, player, spec, TartarusArenas.KRONOS_STASIS)
                    if (spot != null) {
                        sheltering.add(spot.id)
                        continue
                    }
                    struck.add(player.uuid)
                    rewind(player, source)
                }
                if (step % KronosSigTimings.STATUS_EVERY == 0) showStatus(beam, trail, struck)
                if (step % TICK_SOUND_EVERY == 0) {
                    val front = source.add(Beam.dir(beam.startDeg + beam.sign * lead).multiply(TICK_SOUND_DISTANCE))
                    calls.sound(HhaSounds.APPROACH_TICKING, front, 2.6f, 0.6f + 0.4f * step / KronosSigTimings.RAY_SWEEP)
                }
                yield(Unit)
            }
            if (frozen) host.resumeTime()

            // --- Säulen altern --------------------------------------------------------------------------------
            for (id in sheltering) agePillar(id)
            entity.pose = KronosPose.IDLE
            wait(k.HALT_WINDOW)
        }

        /** Wer im überstrichenen Bereich steht und den Strahl noch vor sich hat, sieht „gedeckt“ oder „gesehen“. */
        private fun showStatus(beam: Beam, passed: Double, struck: Set<UUID>) {
            for (player in targets()) {
                if (player.uuid in struck) continue
                val rel = beam.relDeg(player.entityPos) ?: continue
                if (rel < passed) continue
                Cover.status(player, Cover.shelter(world, beam.source, player, spec, TartarusArenas.KRONOS_STASIS) != null)
            }
        }

        /** Getroffen: zurück an den Ort von vor `REWIND_TICKS` (der nächste sichere im Kern, wenn er es nicht mehr ist), dann 22 (SKY). */
        private fun rewind(player: ServerPlayerEntity, source: Vec3d) {
            val from = player.entityPos
            val past = history[player.uuid]?.firstOrNull() ?: from
            val to = safeSpot(past)
            BossFx.play(world, "kronos.rewind_hit", Vfx.Style.AEON, from, to.subtract(from), a = player.height, life = REWIND_FX_LIFE)
            player.teleport(world, to.x, to.y, to.z, emptySet(), player.yaw, player.pitch, false)
            player.velocity = Vec3d.ZERO
            player.velocityDirty = true
            player.fallDistance = 0.0
            history[player.uuid]?.clear()
            player.sendMessage(Text.translatable("hha.kronos.stasis.rewound").formatted(Formatting.RED), true)
            calls.sound(HhaSounds.BLINK, to, 2.6f, 0.55f)
            // Strahl des Stillstands: von oben, der Umgang schützt nicht (SKY, magisch wie die alten Einschläge).
            host.strike(player, KronosSigTimings.RAY_DAMAGE, true, BossDefense.Reach.SKY)
        }

        /** Ein Ort, an dem man stehen kann: im Saal, fester Boden darunter, Füße und Kopf frei. Sonst der nächste im Kern. */
        private fun safeSpot(pos: Vec3d): Vec3d {
            val limit = min(TartarusArenas.HALL_ARENA.toDouble(), fight.safeRadius) - SAFE_EDGE
            if (hypot(pos.x - center.x, pos.z - center.z) <= limit && standable(pos)) return pos
            val core = ArenaNav.nearestCore(pos, spec)?.let { Vec3d(it.x, center.y, it.z) }
            if (core != null && standable(core)) return core
            return fight.clampToHall(pos, 3.0)
        }

        private fun standable(pos: Vec3d): Boolean {
            val feet = BlockPos.ofFloored(pos.x, pos.y + 0.01, pos.z)
            val below = feet.down()
            if (world.getBlockState(below).getCollisionShape(world, below).isEmpty) return false
            return world.getBlockState(feet).getCollisionShape(world, feet).isEmpty &&
                world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty
        }

        // --- Erreichbarkeit --------------------------------------------------------------------------------------

        /**
         * Ansage-Dauer (PLAN 4.1, 4.2): mindestens [KronosSigTimings.RAY_W]; reicht die Zeit für einen Spieler im
         * überstrichenen Bereich nicht bis hinter die nächste stehende Säule oder aus dem Bereich hinaus, wird sie
         * verlängert, höchstens bis `ESCAPE_MAX`. Gerechnet mit `REACTION` 12 und 0,24 Blöcken je Tick; die Zeit, bis
         * die Vorderkante ihn erreicht, zählt mit (in den ersten `HALT_SLOWNESS` Ticks mit Langsamkeit I).
         */
        private fun ansageTicks(beam: Beam): Int {
            val min = BossTimings.Min
            var need = KronosSigTimings.RAY_W
            for (player in targets()) {
                val rel = beam.relDeg(player.entityPos) ?: continue
                if (Cover.shelter(world, beam.source, player, spec, TartarusArenas.KRONOS_STASIS) != null) continue
                val walk = min(shadowDistance(player.entityPos, beam.source), beam.outDistance(player.entityPos, rel, center))
                val arrive = rel / KronosSigTimings.RAY_DEG_PER_TICK
                val slowed = min(arrive, BossTimings.Kronos.HALT_SLOWNESS.toDouble())
                val during = min.ESCAPE_SPEED * (slowed * SLOWNESS_SPEED + max(0.0, arrive - slowed))
                val ticks = min.REACTION + ceil(max(0.0, walk - during) / min.ESCAPE_SPEED).toInt()
                need = max(need, ticks)
            }
            return need.coerceAtMost(min.ESCAPE_MAX)
        }

        /** Weg bis in den Schatten der nächsten stehenden Säule (Punkt dicht hinter ihr, von der Hand aus gesehen). */
        private fun shadowDistance(pos: Vec3d, source: Vec3d): Double {
            var best = Double.MAX_VALUE
            for (pillar in fight.intactPillars) {
                val c = pillarCenter(pillar)
                val away = Vec3d(c.x - source.x, 0.0, c.z - source.z)
                if (away.lengthSquared() < 1e-6) continue
                val shadow = c.add(away.normalize().multiply(TartarusPlans.PILLAR_RADIUS + SHADOW_GAP))
                best = min(best, hypot(shadow.x - pos.x, shadow.z - pos.z))
            }
            return best
        }

        // --- Säulen ----------------------------------------------------------------------------------------------

        private fun pillarCenter(offset: Pair<Int, Int>): Vec3d = center.add(offset.first.toDouble(), 0.0, offset.second.toDouble())

        /** `pillar_i` aus dem Vertrag = i-te Säule aus `TartarusPlans.coverPillars()` (gleiche Reihenfolge). */
        private fun pillarOf(id: String): Pair<Int, Int>? =
            id.removePrefix("pillar_").toIntOrNull()?.let { TartarusPlans.coverPillars().getOrNull(it) }

        /**
         * Die Säule hat gegen den Strahl gedeckt und altert (PLAN 3.6): Sie verlässt `intactPillars` sofort und zerfällt
         * in [KronosSigTimings.AGE_TICKS] Ticks von oben auf [KronosSigTimings.PILLAR_STUMP] Blöcke, alles über
         * `fight.edit` (Kampfende stellt sie über `restoreEdits` wieder her). Staub rieselt dabei nach oben.
         */
        private fun agePillar(id: String) {
            val pillar = pillarOf(id) ?: return
            if (!fight.intactPillars.remove(pillar)) return
            val base = pillarCenter(pillar)
            BossFx.play(world, "kronos.age", Vfx.Style.AEON, base, a = TartarusPlans.PILLAR_RADIUS + 0.5f,
                b = TartarusPlans.PILLAR_HEIGHT.toFloat(), life = KronosSigTimings.AGE_TICKS + AGE_FX_TAIL)
            calls.sound(HhaSounds.ARENA_CRUMBLE, base.add(0.0, AGE_SOUND_HEIGHT, 0.0), 3f, 0.7f)
            val reach = TartarusPlans.PILLAR_RADIUS + 1
            val top = TartarusPlans.PILLAR_HEIGHT + PILLAR_TOP_EXTRA
            val stump = KronosSigTimings.PILLAR_STUMP
            fight.addTask(sequence {
                var height = top
                for (tick in 1..KronosSigTimings.AGE_TICKS) {
                    val until = top - ((top - stump) * tick.toDouble() / KronosSigTimings.AGE_TICKS).roundToInt()
                    while (height > until) {
                        for (dx in -reach..reach) for (dz in -reach..reach) {
                            val pos = fight.floor.add(pillar.first + dx, height, pillar.second + dz)
                            if (!world.getBlockState(pos).isAir) fight.edit(pos, Blocks.AIR.defaultState)
                        }
                        height--
                    }
                    yield(Unit)
                }
            })
        }

        /**
         * Zu Beginn der zweiten Kronos-Phase stehen alle Säulen wieder (PLAN 3.6, Leitlinie 20). Seit P24 stellt
         * `HadesFight.startStage(5)` sie über `restorePillars` her (vor `onStageStart`); dies ist nur noch der Rückfall,
         * falls dort eine Säule fehlt (dann findet es sie hier, sonst tut es nichts): Blöcke aus dem Bauplan `palace_hall`
         * über `fight.edit` zurück. Wer im Säulenkasten steht, wird vorher waagerecht hinausgesetzt.
         */
        fun restoreAged() {
            val all = TartarusPlans.coverPillars()
            val missing = all.filter { it !in fight.intactPillars }
            if (missing.isEmpty()) return
            val design = ArenaBlueprints.design(TartarusArenas.HALL_ID, fight.floor)
            val reach = TartarusPlans.PILLAR_RADIUS + 1
            val top = TartarusPlans.PILLAR_HEIGHT + PILLAR_TOP_EXTRA
            for (pillar in missing) {
                val c = pillarCenter(pillar)
                for (player in targets()) {
                    val dx = player.x - c.x
                    val dz = player.z - c.z
                    if (abs(dx) > reach + 0.8 || abs(dz) > reach + 0.8) continue
                    val out = Vec3d(dx, 0.0, dz).let { if (it.lengthSquared() < 1e-4) Vec3d(1.0, 0.0, 0.0) else it.normalize() }
                    val spot = c.add(out.multiply(reach + PUSH_OUT))
                    player.teleport(world, spot.x, center.y, spot.z, emptySet(), player.yaw, player.pitch, false)
                    player.fallDistance = 0.0
                }
                for (height in 1..top) for (dx in -reach..reach) for (dz in -reach..reach) {
                    val pos = fight.floor.add(pillar.first + dx, height, pillar.second + dz)
                    val want = design.at(pos) ?: continue
                    if (world.getBlockState(pos) != want) fight.edit(pos, want)
                }
                BossFx.play(world, "kronos.age", Vfx.Style.AEON, c, a = TartarusPlans.PILLAR_RADIUS + 0.5f,
                    b = TartarusPlans.PILLAR_HEIGHT.toFloat(), c = 1f, life = KronosSigTimings.AGE_TICKS + AGE_FX_TAIL)
                fight.intactPillars.add(pillar)
            }
            calls.sound(HhaSounds.ARENA_CRUMBLE, center, 3f, 1.3f)
        }

        // --- Zielpunkte und Ebenen -------------------------------------------------------------------------------

        /**
         * Kronos' eigener Ort (Zeitschritt, Schreiten, Stillstand): im Kern r ≤ 38 ohne Thronpodest, mit Abstand zu jeder
         * Deckung (`ArenaNav.COVER_MARGIN`) und zum Kernrand (`ArenaNav.CORE_INSET`). Sonst der nächste solche Punkt.
         */
        fun coreSpot(pos: Vec3d): Vec3d {
            if (freeCore(pos.x, pos.z)) return Vec3d(pos.x, center.y, pos.z)
            val near = ArenaNav.nearestCore(pos, spec) ?: return Vec3d(center.x, center.y, center.z)
            return Vec3d(near.x, center.y, near.z)
        }

        private fun freeCore(x: Double, z: Double): Boolean {
            val inset = ArenaNav.CORE_INSET.toDouble()
            if (!spec.core.contains(x, z)) return false
            for ((ox, oz) in INSET_PROBES) if (!spec.core.contains(x + ox * inset, z + oz * inset)) return false
            return spec.cover.none { ArenaNav.inflate(it.box, ArenaNav.COVER_MARGIN).inside(x, z) }
        }

        /** Boden der Ebene, auf der [player] steht (Umgang +2, Podest +3), sonst der Saalboden. */
        fun floorOf(player: ServerPlayerEntity): Double {
            for (level in spec.levels) {
                if (level.zone.contains(player.x, player.z) && player.y >= level.floorY - LEVEL_TOLERANCE) return level.floorY.toDouble()
            }
            return center.y
        }

        /**
         * Zweite Form für die Ebenen (Pflicht 3, `_KAMPF_GEMEINSAM.md`): Der Client zeichnet flach auf der Höhe der Form;
         * das Stück einer Linie, das über Umgang oder Podest läuft, liegt sonst unsichtbar unter deren Belag. Für jedes
         * solche Stück eine gleich lange Linie derselben Gruppe auf der Höhe der Ebene (nur Anzeige: Treffer prüft die
         * Hauptform).
         */
        fun echo(group: Telegraph.Fight.Group, from: Vec3d, to: Vec3d, width: Float, ticks: Int, pace: Telegraph.Pace? = null) {
            val length = hypot(to.x - from.x, to.z - from.z)
            if (length < 0.5) return
            val steps = ceil(length / ECHO_STEP).toInt()
            for (level in spec.levels) {
                var runStart = -1
                for (i in 0..steps + 1) {
                    val t = (i.toDouble() / steps).coerceAtMost(1.0)
                    val on = i <= steps && level.zone.contains(from.x + (to.x - from.x) * t, from.z + (to.z - from.z) * t)
                    if (on && runStart < 0) runStart = i
                    if (!on && runStart >= 0) {
                        val t0 = runStart.toDouble() / steps
                        val t1 = ((i - 1).toDouble() / steps).coerceAtMost(1.0)
                        if ((t1 - t0) * length >= ECHO_MIN) {
                            val y = level.floorY.toDouble()
                            val a = Vec3d(from.x + (to.x - from.x) * t0, y, from.z + (to.z - from.z) * t0)
                            val b = Vec3d(from.x + (to.x - from.x) * t1, y, from.z + (to.z - from.z) * t1)
                            group.line(a, b, width, ticks, pace)
                        }
                        runStart = -1
                    }
                }
            }
        }

        // --- Hilfen ----------------------------------------------------------------------------------------------

        private fun targets(): List<ServerPlayerEntity> = fight.targets()

        private fun floorAt(pos: Vec3d) = Vec3d(pos.x, center.y, pos.z)

        /** Quelle des Strahls und der Sichtlinie: über seiner Mitte in Höhe der erhobenen Hand (fest während des Stillstands). */
        private fun hand(entity: KronosEntity): Vec3d = entity.entityPos.add(0.0, entity.height * HAND_HEIGHT, 0.0)

        /**
         * Mitte des überstrichenen Halbkreises: von ihm zur Saalmitte (so liegt der größte Teil des Saals darin); steht
         * er nahe der Mitte, seine Blickrichtung (zum nächsten Spieler).
         */
        private fun midDegrees(entity: KronosEntity, source: Vec3d): Double {
            val dx = center.x - source.x
            val dz = center.z - source.z
            if (hypot(dx, dz) > MID_FROM_CENTER) return Beam.deg(dx, dz)
            val rad = Math.toRadians(entity.yaw.toDouble())
            return Beam.deg(-sin(rad), cos(rad))
        }

        private suspend fun SequenceScope<Unit>.wait(ticks: Int) {
            repeat(ticks) { yield(Unit) }
        }
    }

    // =================================================================================================================
    // Geometrie des Strahls (rein, ohne Welt)
    // =================================================================================================================

    /**
     * Ein geplanter Strahl: Quelle [source], Startkante [startDeg], Drehsinn [sign] (+1 wachsende Winkel). Die
     * Vorderkante läuft in `RAY_SWEEP` Ticks von der Startkante bis `RAY_ARC`; der Strahl ist der Fächer von
     * `lead − 2 · RAY_HALF_ANGLE` (mindestens 0) bis `lead`. Überstrichen wird genau der Halbkreis `0 … RAY_ARC`.
     */
    class Beam(val source: Vec3d, val startDeg: Double, val sign: Int) {

        /** Mitte des überstrichenen Bereichs (Grad). */
        val midDeg: Double get() = startDeg + sign * KronosSigTimings.RAY_ARC / 2.0

        /** Winkel von [pos] ab der Startkante im Drehsinn (0 … 360), `null` außerhalb von Reichweite oder Halbkreis. */
        fun relDeg(pos: Vec3d): Double? {
            val dx = pos.x - source.x
            val dz = pos.z - source.z
            val dist = hypot(dx, dz)
            if (dist > KronosSigTimings.RAY_RADIUS || dist < MIN_DIST) return null
            val rel = norm((deg(dx, dz) - startDeg) * sign)
            return if (rel <= KronosSigTimings.RAY_ARC) rel else null
        }

        /** Steht [pos] im Strahl, dessen Fächer gerade von [trail] bis [lead] reicht? */
        fun inBeam(pos: Vec3d, trail: Double, lead: Double): Boolean {
            val rel = relDeg(pos) ?: return false
            return rel in trail..lead
        }

        /**
         * Kürzester Weg aus dem überstrichenen Bereich: hinter die Startkante (der Strahl kehrt nie um), hinter die
         * Endkante, oder hinaus über die Reichweite, wenn der Saal dort noch Boden hat.
         */
        fun outDistance(pos: Vec3d, rel: Double, hall: Vec3d): Double {
            val dist = hypot(pos.x - source.x, pos.z - source.z)
            val toStart = if (rel <= 90.0) dist * sin(Math.toRadians(rel)) else dist
            val toEnd = KronosSigTimings.RAY_ARC - rel
            val toEndDist = if (toEnd <= 90.0) dist * sin(Math.toRadians(toEnd)) else dist
            var best = min(toStart, toEndDist)
            val out = source.add(dir(startDeg + sign * rel).multiply(KronosSigTimings.RAY_RADIUS + 0.5))
            if (hypot(out.x - hall.x, out.z - hall.z) <= TartarusArenas.HALL_WALK_OUTER - 0.5) best = min(best, KronosSigTimings.RAY_RADIUS - dist)
            return best
        }

        companion object {
            /** Unter so viel Abstand zur Quelle (in ihm) gilt kein Winkel. */
            const val MIN_DIST = 0.8

            /** Vorderkante nach [step] Ticks Schwenk (Grad ab der Startkante). */
            fun lead(step: Int): Double = (step * KronosSigTimings.RAY_DEG_PER_TICK.toDouble()).coerceAtMost(KronosSigTimings.RAY_ARC.toDouble())

            /** Hinterkante zur Vorderkante [lead]: ein voller Strahl ist 2 · `RAY_HALF_ANGLE` breit, nie vor der Startkante. */
            fun trail(lead: Double): Double = max(0.0, lead - 2.0 * KronosSigTimings.RAY_HALF_ANGLE)

            fun deg(dx: Double, dz: Double): Double = Math.toDegrees(atan2(dz, dx))
            fun norm(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
            fun dir(deg: Double): Vec3d = Math.toRadians(deg).let { Vec3d(cos(it), 0.0, sin(it)) }

            /** Gierwinkel des Titanen, der in Richtung [deg] blickt (wie `HadesFight.faceTitan`). */
            fun yaw(deg: Double): Float = (deg - 90.0).toFloat()

            /**
             * Plant den Strahl um die Mitte [midDeg]: Der Drehsinn wird so gewählt, dass die Hälfte (Viertelkreis) mit den
             * meisten Spielern zuletzt erreicht wird; bei Gleichstand gegen den Uhrzeigersinn der Ansicht von oben (+1).
             */
            fun plan(source: Vec3d, midDeg: Double, players: Collection<ServerPlayerEntity>): Beam {
                var first = 0
                var last = 0
                for (player in players) {
                    val dx = player.x - source.x
                    val dz = player.z - source.z
                    if (hypot(dx, dz) > KronosSigTimings.RAY_RADIUS) continue
                    val off = norm(deg(dx, dz) - midDeg)
                    when {
                        off >= 270.0 -> first++        // −90 … 0: bei +1 zuerst erreicht
                        off <= 90.0 -> last++          // 0 … +90: bei +1 zuletzt erreicht
                    }
                }
                val sign = if (first > last) -1 else 1
                return Beam(source, midDeg - sign * KronosSigTimings.RAY_ARC / 2.0, sign)
            }
        }
    }

    // =================================================================================================================
    // Sanduhren der Rückspulung: Unterleisten
    // =================================================================================================================

    /**
     * Je Sanduhr eine Unterleiste „Sanduhr“ (Briefing P20 Schritt 2): Anteil aus dem Leben des Wesens, Zustand 1 (golden),
     * solange sie steht, 2 nach dem Bruch. [show] alle `UPDATE_EVERY` Ticks und bei jedem Bruch; [hide] an jedem Ende
     * der Rückspulung (geschafft, zu spät, Phasenwechsel, Kampfende über `KronosAttacks.reset`).
     */
    class HourglassBars(private val fight: HadesFight) {
        private val viewers = HashSet<UUID>()
        private var slots: List<CoreEntity> = emptyList()

        fun start(glasses: List<CoreEntity>) {
            slots = glasses.toList()
            show()
        }

        fun show() {
            if (slots.isEmpty()) return
            val players = fight.players()
            for (player in players) viewers.add(player.uuid)
            val bars = slots.map { glass ->
                val broken = glass.isRemoved || glass.health <= 0f
                Cine.SubBar(BAR_KEY, if (broken) 0f else (glass.health / glass.maxHealth).coerceIn(0f, 1f), if (broken) 2 else 1)
            }
            Cine.subBars(players, bars)
        }

        fun hide() {
            if (slots.isEmpty() && viewers.isEmpty()) return
            slots = emptyList()
            val server = fight.world.server
            val players = viewers.mapNotNull { server?.playerManager?.getPlayer(it) }
            viewers.clear()
            if (players.isNotEmpty()) Cine.subBars(players, emptyList())
        }

        companion object {
            const val BAR_KEY = "hha.kronos.sub.hourglass"

            /** So oft gehen die Leisten während der Rückspulung hinaus (Ticks). */
            const val UPDATE_EVERY = 5
        }
    }

    // --- Darstellungs- und Rechenkonstanten (keine Kampfzahlen) ---------------------------------------------------

    /** Volle Breite der Ansage-Linie an der Startkante. */
    private const val START_EDGE_WIDTH = 1.4f

    /** `kronos.ray`: so lange nach dem Schwenk blendet der Strahl aus. */
    private const val RAY_FX_TAIL = 10

    /** `kronos.rewind_hit`: Dauer des Sandbands vom Treffer- zum Rückkehrort. */
    private const val REWIND_FX_LIFE = 30

    /** `kronos.age`: Staub rieselt noch so lange nach dem Zerfall. */
    private const val AGE_FX_TAIL = 20

    /** Ton des Zerfalls so hoch über dem Säulenfuß. */
    private const val AGE_SOUND_HEIGHT = 8.0

    /** Ticken des Strahls: so oft, so weit vor der Hand auf der Vorderkante. */
    private const val TICK_SOUND_EVERY = 10
    private const val TICK_SOUND_DISTANCE = 18.0

    /** Hand: Höhe über den Füßen als Anteil seiner Höhe (die erhobene Hand der Pose `STASIS`). */
    private const val HAND_HEIGHT = 0.75

    /** Steht er weiter als so viel von der Saalmitte, zeigt der Halbkreis zur Mitte. */
    private const val MID_FROM_CENTER = 6.0

    /** Schattenpunkt so weit hinter dem Säulenrand (Erreichbarkeitsrechnung). */
    private const val SHADOW_GAP = 1.5

    /** Gehen mit Langsamkeit I (−15 %). */
    private const val SLOWNESS_SPEED = 0.85

    /** Rückspulung: so weit innerhalb des noch stehenden Bodens muss der alte Ort liegen. */
    private const val SAFE_EDGE = 1.5

    /** Säulen: oberste Lage, die der Zerfall abräumt (Band, Laternen über dem Schaft; wie Hades' Einsturz). */
    private const val PILLAR_TOP_EXTRA = 4

    /** Wer beim Wiederaufbau im Säulenkasten steht, wird so weit vor den Kasten gesetzt. */
    private const val PUSH_OUT = 1.5

    /** Ebenen: Füße bis so weit unter dem Ebenenboden zählen noch als „auf der Ebene“ (Stufenkanten). */
    private const val LEVEL_TOLERANCE = 0.5

    /** Zweite Form: Schrittweite der Abtastung und kürzestes Stück, das gezeigt wird. */
    private const val ECHO_STEP = 0.5
    private const val ECHO_MIN = 1.0

    /** Kern mit Abstand: diese vier Richtungen × `CORE_INSET` müssen ebenfalls im Kern liegen. */
    private val INSET_PROBES = listOf(1.0 to 0.0, -1.0 to 0.0, 0.0 to 1.0, 0.0 to -1.0)
}
