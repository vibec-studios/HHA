package dev.henny.hha.logic.boss.sig

import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.logic.boss.ArenaCollapse
import dev.henny.hha.logic.boss.Cover
import dev.henny.hha.logic.boss.SubTarget
import dev.henny.hha.logic.boss.SubTargetGroup
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha.logic.boss.arena.ArenaEdits
import dev.henny.hha.logic.boss.subTargetHandler
import dev.henny.hha.logic.boss.timings.KratosTimings
import dev.henny.hha.world.arena.ArenaSpec
import dev.henny.hha.world.arena.CoverSpot
import dev.henny.hha.world.arena.Disc
import dev.henny.hha.world.arena.Level
import dev.henny.hha.world.arena.Rect
import net.minecraft.block.BlockState
import net.minecraft.block.StairsBlock
import net.minecraft.entity.damage.DamageSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Signatur-Mechanik „Kratos: Bia als Teilziel“ (Überarbeitung 4, PLAN 3.4, 4.2, 4.3; Besitzer P17).
 *
 * Alles, was die Mechanik ausmacht, steht hier; `KratosEntity` ruft es an den Einhakstellen auf:
 *
 * | Baustein | Was | Einhakstelle in `KratosEntity` |
 * | --- | --- | --- |
 * | [Target] | Bias Unterleiste (Teilziel **ohne eigenes Wesen**: Bia selbst ist das Wesen), Leben 25 %, Deckel 12 % | `biaTarget()`, `openBia`, `hitBia`, `tickBody`, alle Ausgänge |
 * | [standardHits], [topple] | Hammerfall < 2,5 an einer Standarte zerschlägt sie (bis auf den Sockel) | `hammer` |
 * | [shatterPlinth] | Bias Sockel wird zu Trümmern (obere Lage weg, untere als Bruch) | Szene `kratos.bia_break` |
 * | [stormEnd], [STORM_ATTACK] | Bias Sturm endet an der ersten Standarte bzw. am rechten Sockel | `storm` |
 * | [landing], [onLanding], [clampSmash], [landingEcho] | Treppenabsatz als Ebene: Bänder, Zweitform, Zielpunkt des Hammers | alle Angriffe |
 * | [wrathPause] | Zorn: Pausen × 0,85, nie unter 60 % der Grundpause | `basePause` |
 *
 * Regeln (PLAN 4.1, Zeile Teilziel): Bia handelt nie selbst (keine eigene Auswahl, kein eigener Zug); golden nur, solange
 * sie Treffer nimmt; Leben über `BossScaling.health`; Zerstören streicht zwei Angriffe; wer sie ignoriert, kämpft wie
 * heute (bis auf Standarten und Absatz).
 */
object BiaTarget {

    /** Nichts anzumelden: Bia ist ihr eigenes Wesen, die Effekte meldet `KratosFx` an, die Szene `KratosStages`. */
    fun init() {}

    /** Kennung und Name (Sprachschlüssel) der Unterleiste. */
    const val SUB_ID = "bia"
    const val SUB_NAME = "hha.kratos.sub.bia"

    /** Angriffs-ID, gegen die die Standarten schützen (Vertrag `CoverSpot.protects`). */
    const val STORM_ATTACK = dev.henny.hha.world.olymp.OlympusArenas.KRATOS_BIA_STORM

    /** Deckungen im Vertrag: Bias Sockel (verbrauchbar), rechter Sockel, Standarten `standard_<Winkel>`. */
    const val PLINTH_LEFT = "plinth_left"
    const val STANDARD_PREFIX = "standard_"
    const val LANDING = "landing"

    /** Über dem Deckungskasten der Standarte stehen Tuch, Querholz, Mast und Spitze (`IsleE.standardBody`): so hoch. */
    const val STANDARD_CROWN = 5

    /** Höhe über dem Boden, auf der Bias Sturm auf Deckung prüft (über der ersten Freitreppen-Stufe, unter Kopfhöhe). */
    const val STORM_PROBE_HEIGHT = 1.2

    /** Bias Lauf endet so weit vor dem ersten Block (sie prallt nicht in den Bronzeschaft). */
    const val STORM_STOP_BEFORE = 0.6

    /** Kürzeste Sturm-Linie, auch wenn die Deckung direkt vor ihr steht. */
    const val STORM_MIN_LENGTH = 1.0

    /** Abstand eines geklemmten Hammer-Zielpunkts zum Rand von Kern bzw. Absatz (der Kreis liegt sichtbar auf der Fläche). */
    const val CLAMP_INSET = 0.6

    // =============================================================================================================
    // Teilziel
    // =============================================================================================================

    /**
     * Bias Unterleiste für einen Kampf. [viewers] sehen die Leiste (ihre Zahl beim Anlegen bestimmt die Skalierung),
     * [admit] entscheidet je Treffer, ob er zählt (nur Kampfteilnehmer von der Kampffläche), [onBreak] läuft genau
     * einmal, wenn die Leiste leer ist. Der Kampf ruft [tick] jeden Tick und [close] an **jedem** Ausgang.
     */
    class Target(
        world: ServerWorld,
        viewers: () -> Collection<ServerPlayerEntity>,
        admit: (DamageSource) -> Boolean,
        onBreak: () -> Unit,
    ) {
        private val group = SubTargetGroup(
            world, viewers,
            subTargetHandler(
                onHit = { _, source, amount -> if (admit(source)) amount else 0f },
                onBroken = { onBreak() },
            ),
        )

        /** Die Leiste (Leben solo 425 vor Skalierung, Deckel 12 % je Treffer). */
        val sub: SubTarget = group.add(SUB_ID, SUB_NAME, KratosTimings.EFFECTIVE_HEALTH * KratosTimings.BIA_HEALTH_SHARE, KratosTimings.BIA_CAP)

        /** Welt-Tick, bis zu dem Bia offen ist (ausschließlich). */
        var openUntil: Long = 0L
            private set

        val open: Boolean get() = sub.open
        val broken: Boolean get() = sub.broken
        val closed: Boolean get() = group.closed

        /** Bia taumelt: [ticks] Ticks ab [now] verwundbar (Leiste golden). Längeres Fenster gewinnt. */
        fun open(ticks: Int, now: Long) {
            if (broken || group.closed) return
            openUntil = max(openUntil, now + ticks)
            sub.open = true
        }

        /** Fenster sofort zu (Rückkehr, Szene, Neustart). */
        fun shut() {
            openUntil = 0L
            sub.open = false
        }

        /** Ein Treffer auf Bia; liefert den gezählten Schaden (0 = abgewehrt, nicht offen oder gesperrt). */
        fun hit(amount: Float, source: DamageSource): Float = sub.hit(amount, source)

        /** Jeden Tick: Fenster nach Ablauf schließen, Ort für Klang/Blitz nachführen ([at] = Bias Mitte), Leiste senden. */
        fun tick(now: Long, at: Vec3d?) {
            if (sub.open && now >= openUntil) sub.open = false
            if (at != null) sub.anchor = at
            group.tick()
        }

        /** Leiste und Gruppe weg (mehrfach harmlos). */
        fun close() {
            sub.open = false
            group.close()
        }
    }

    // =============================================================================================================
    // Standarten
    // =============================================================================================================

    /** Die vier Standarten aus dem Vertrag. */
    fun standards(spec: ArenaSpec): List<CoverSpot> = spec.cover.filter { it.id.startsWith(STANDARD_PREFIX) }

    /** Waagerechter Abstand von (x, z) zum Kasten von [spot] (0 innen). */
    fun distanceToBox(spot: CoverSpot, x: Double, z: Double): Double {
        val b = spot.box
        val dx = max(max(b.minX - x, 0.0), x - (b.maxX + 1.0))
        val dz = max(max(b.minZ - z, 0.0), z - (b.maxZ + 1.0))
        return hypot(dx, dz)
    }

    /** Steht die Standarte in der Welt noch (Schaft über dem Sockel fest)? Prüft die Welt, nicht nur den Vertrag. */
    fun standing(world: ServerWorld, spot: CoverSpot): Boolean {
        val b = spot.box
        return !world.getBlockState(BlockPos(b.minX, b.minY + 1, b.minZ)).isAir
    }

    /**
     * Die stehenden Standarten, die ein Hammerfall mit Mitte [impact] zerschlägt (Mitte näher als
     * [KratosTimings.STANDARD_BREAK_RADIUS] am Kasten). [fallen] = schon zerschlagene IDs dieses Kampfs.
     */
    fun standardHits(world: ServerWorld, spec: ArenaSpec, fallen: Set<String>, impact: Vec3d): List<CoverSpot> =
        standards(spec).filter { it.id !in fallen && distanceToBox(it, impact.x, impact.z) < KratosTimings.STANDARD_BREAK_RADIUS && standing(world, it) }

    /**
     * Die Standarte zerbricht von oben bis auf ihren Marmorsockel (Kernhöhe bleibt), samt Tuch und Mast über dem
     * Deckungskasten ([STANDARD_CROWN]). Über [edits]: Rückkehr beim Kampfende ([dev.henny.hha.logic.boss.Arenas.close]).
     * Ton `arena.crumble` und Trümmer macht [ArenaCollapse.crumble].
     */
    fun topple(edits: ArenaEdits, spot: CoverSpot) {
        val b = spot.box
        val box = BlockBox(b.minX, b.minY, b.minZ, b.maxX, b.maxY + STANDARD_CROWN, b.maxZ)
        ArenaCollapse.crumble(edits, box, keepBelow = b.minY + 1)
    }

    // =============================================================================================================
    // Bias Sockel
    // =============================================================================================================

    /**
     * Bias Sockel (3 × 3 × 2, `plinth_left`) wird zu Trümmern: obere Lage weg, die untere als Bruch (Mitte ein roher
     * Stumpf, Kanten halb abgeschlagene Stufen, Ecken Platten oder leer). Über [edits] (Rückkehr beim Kampfende).
     */
    fun shatterPlinth(edits: ArenaEdits, spec: ArenaSpec) {
        val box = spec.cover(PLINTH_LEFT).box
        for (y in box.maxY downTo box.minY + 1) for (x in box.minX..box.maxX) for (z in box.minZ..box.maxZ) edits.clear(BlockPos(x, y, z))
        val cx = (box.minX + box.maxX) / 2
        val cz = (box.minZ + box.maxZ) / 2
        for (x in box.minX..box.maxX) for (z in box.minZ..box.maxZ) {
            edits.set(BlockPos(x, box.minY, z), rubble(x - cx, z - cz))
        }
    }

    /** Bruchstück an der Stelle (dx, dz) relativ zur Sockelmitte (fest gewählt, damit Bild und Wiederholung gleich sind). */
    private fun rubble(dx: Int, dz: Int): BlockState = when {
        dx == 0 && dz == 0 -> HhaDecoBlocks.OLYMPUS_MARBLE.defaultState
        dx == 0 || dz == 0 -> {
            // Kanten: abgeschlagene Stufe, die hohe Seite zur Mitte (wie ein Rest der oberen Lage), im Wechsel eine Platte.
            if ((dx + dz) > 0) {
                HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_STAIRS.defaultState.with(StairsBlock.FACING, toward(-dx, -dz))
            } else {
                HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_SLAB.defaultState
            }
        }
        // Ecken: zwei Platten, zwei abgesprungen (die vergoldeten Ecken liegen nicht mehr).
        (dx > 0) == (dz > 0) -> HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_SLAB.defaultState
        else -> net.minecraft.block.Blocks.AIR.defaultState
    }

    private fun toward(dx: Int, dz: Int): Direction = when {
        dx > 0 -> Direction.EAST
        dx < 0 -> Direction.WEST
        dz > 0 -> Direction.SOUTH
        else -> Direction.NORTH
    }

    // =============================================================================================================
    // Bias Sturm an der Deckung
    // =============================================================================================================

    /**
     * Wo Bias Sturm von [start] Richtung [end] endet: am ersten Block auf der Strecke in [STORM_PROBE_HEIGHT] über dem
     * Boden (Standarte, rechter Sockel, Treppenwange), [STORM_STOP_BEFORE] davor; sonst [end]. Echter Raycast
     * ([Cover.lineEnd]): Eine zerschlagene Standarte hält nichts mehr auf.
     */
    fun stormEnd(world: ServerWorld, start: Vec3d, end: Vec3d): Vec3d {
        val from = start.add(0.0, STORM_PROBE_HEIGHT, 0.0)
        val to = Vec3d(end.x, start.y + STORM_PROBE_HEIGHT, end.z)
        val hit = Cover.lineEnd(world, from, to)
        if (hit.squaredDistanceTo(to) < 1e-6) return end
        val dx = end.x - start.x
        val dz = end.z - start.z
        val full = hypot(dx, dz).coerceAtLeast(1e-6)
        val reach = (hypot(hit.x - start.x, hit.z - start.z) - STORM_STOP_BEFORE).coerceIn(STORM_MIN_LENGTH.coerceAtMost(full), full)
        return Vec3d(start.x + dx / full * reach, end.y, start.z + dz / full * reach)
    }

    /** Augpunkt, von dem aus die Standarten gegen den Sturm decken (`Cover.mark`/`Cover.shelter`). */
    fun stormEye(start: Vec3d): Vec3d = start.add(0.0, STORM_PROBE_HEIGHT, 0.0)

    // =============================================================================================================
    // Treppenabsatz (Ebene `landing`, +4)
    // =============================================================================================================

    fun landing(spec: ArenaSpec): Level? = spec.levels.firstOrNull { it.id == LANDING }

    /** Steht [pos] auf dem Absatz (waagerecht in der Zone, Füße nicht tiefer als einen halben Block darunter)? */
    fun onLanding(spec: ArenaSpec, pos: Vec3d): Boolean {
        val lv = landing(spec) ?: return false
        return lv.zone.contains(pos.x, pos.z) && pos.y >= lv.floorY - 0.5
    }

    /**
     * Zielpunkt des Hammerfalls: auf dem Kern oder auf dem Absatz, nie auf den Stufen dazwischen. [spot] ist der heutige
     * Punkt (am Ziel, höchstens [reach] vor Kratos bei [from]). Liegt er über dem Absatz, gilt der Absatz (dort gibt es
     * keine tiefere Fläche); liegt er auf dem Kern, bleibt er unverändert (auch seine Höhe, wie bisher). Sonst nimmt der
     * Hammer den nächsten Punkt des Absatzes, wenn [preferLanding] (Ziel steht oben) und Kratos ihn mit [reach] erreicht,
     * sonst den nächsten Punkt des Kerns. Danach setzt der Kampf den Punkt auf den Boden (`ground`).
     */
    fun clampSmash(spec: ArenaSpec, from: Vec3d, spot: Vec3d, reach: Double, preferLanding: Boolean): Vec3d {
        val lv = landing(spec)
        if (lv != null && lv.zone.contains(spot.x, spot.z)) return Vec3d(spot.x, lv.floorY.toDouble(), spot.z)
        if (spec.core.contains(spot.x, spot.z)) return spot
        val onCore = nearestCore(spec, spot)
        if (lv != null && preferLanding) {
            val up = nearestIn(lv.zone, spot)
            if (up != null && hypot(up.x - from.x, up.z - from.z) <= reach + 1e-3) return Vec3d(up.x, lv.floorY.toDouble(), up.z)
        }
        return Vec3d(onCore.x, spec.coreY.toDouble(), onCore.z)
    }

    /** Nächster Punkt des Kerns (Kreis), [CLAMP_INSET] innerhalb des Randes. */
    private fun nearestCore(spec: ArenaSpec, p: Vec3d): Vec3d {
        val core = spec.core
        if (core is Disc) {
            val dx = p.x - core.cx
            val dz = p.z - core.cz
            val d = hypot(dx, dz)
            val r = (core.radius - CLAMP_INSET).coerceAtLeast(0.0)
            if (d <= r || d < 1e-6) return p
            return Vec3d(core.cx + dx / d * r, p.y, core.cz + dz / d * r)
        }
        // Andere Kernformen (nicht bei E): zur Mitte ziehen, bis der Punkt darin liegt.
        val c = spec.centerVec
        var q = p
        repeat(32) {
            if (core.contains(q.x, q.z)) return q
            q = q.lerp(c, 0.1)
        }
        return c
    }

    /** Nächster Punkt eines Rechtecks (andere Zonen: `null`). */
    private fun nearestIn(zone: dev.henny.hha.world.arena.Zone, p: Vec3d): Vec3d? {
        if (zone !is Rect) return null
        val x = p.x.coerceIn(zone.minX + CLAMP_INSET, zone.maxX + 1.0 - CLAMP_INSET)
        val z = p.z.coerceIn(zone.minZ + CLAMP_INSET, zone.maxZ + 1.0 - CLAMP_INSET)
        return Vec3d(x, p.y, z)
    }

    /**
     * Zweitform auf dem Absatz (der Client zeichnet Formen flach auf ihrer Höhe): Trifft [covers] (waagerechte Prüfung
     * der Hauptform) Zellen des Absatzes, legt [group] dort eine flache Linie über genau die getroffenen Spalten
     * (volle Tiefe des Absatzes) auf dessen Höhe, Band Absatz +3. Sie ist **nur Bild**: Getroffen wird über die
     * Hauptform (deren Band reicht bis Kern + [KratosTimings.BAND_BOTH_ABOVE]). Die Linie zeigt eher zu viel als zu
     * wenig (volle Tiefe des Absatzes): Wo sie fehlt, trifft es nie. Liefert die Linie oder `null`.
     *
     * Achtung: setzt das Band der Gruppe auf den Absatz; wer danach eine Form auf dem Kern anlegt, setzt es neu.
     */
    fun landingEcho(group: Telegraph.Fight.Group, spec: ArenaSpec, ticks: Int, pace: Telegraph.Pace? = null, covers: (Double, Double) -> Boolean): Telegraph.Line? {
        val lv = landing(spec) ?: return null
        val zone = lv.zone as? Rect ?: return null
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        for ((x, z) in zone.cells()) {
            if (!covers(x + 0.5, z + 0.5)) continue
            if (x < minX) minX = x
            if (x > maxX) maxX = x
        }
        if (minX > maxX || ticks <= 0) return null
        val y = lv.floorY + ECHO_LIFT
        val zc = (zone.minZ + zone.maxZ + 1) / 2.0
        val depth = (zone.maxZ - zone.minZ + 1).toFloat()
        group.band(lv.floorY.toDouble(), below = 1.0, above = KratosTimings.BAND_CORE_ABOVE)
        return group.line(Vec3d(minX.toDouble(), y, zc), Vec3d(maxX + 1.0, y, zc), depth, ticks, pace)
    }

    private const val ECHO_LIFT = 0.05

    // =============================================================================================================
    // Zorn
    // =============================================================================================================

    /**
     * Zorn ohne Bia: Pause [base] × [KratosTimings.WRATH_PAUSE] × [other] (andere Pausenfaktoren, z. B. ein Letztes
     * Gefecht), nie unter [KratosTimings.WRATH_PAUSE_FLOOR] von [base].
     */
    fun wrathPause(base: Int, other: Double = 1.0): Int {
        val floor = ceil(base * KratosTimings.WRATH_PAUSE_FLOOR).toInt()
        return max(floor, (base * KratosTimings.WRATH_PAUSE * other).roundToInt())
    }

    /** Bias Leben solo vor Skalierung (425 = 25 % von 1700). */
    val SOLO_HEALTH: Float get() = KratosTimings.EFFECTIVE_HEALTH * KratosTimings.BIA_HEALTH_SHARE
}
