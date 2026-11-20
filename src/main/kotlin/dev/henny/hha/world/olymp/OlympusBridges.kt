package dev.henny.hha.world.olymp

import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.world.DecoPalettes
import dev.henny.hha.world.Kit
import dev.henny.hha.world.Pal
import net.minecraft.block.BellBlock
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.WallBlock
import net.minecraft.block.enums.Attachment
import net.minecraft.block.enums.WallShape
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Die fünf Brücken des Pfads (PLAN 8.2): 5 Blöcke breit, Platten zwischen Geländern, darunter ein Kiel, der sich zu den
 * Pfeilern hin zu Bögen vertieft, alle 32 Blöcke ein kleiner Pfeilerfels mit Wolkenfetzen. Die Brücken laufen
 * achsparallel; wo sie abknicken, liegt ein Absatz mit Herme. Steigung nur über Treppenläufe von vier Stufen mit
 * Absätzen dazwischen. In vier Brücken fehlen die mittleren [OlympusLayout.SEAL_GAP] Blöcke, bis der Boss davor besiegt
 * ist ([dev.henny.hha.logic.world.OlympusPath] setzt sie aus demselben Bauplan nach).
 *
 * **Schlachtwege (Überarbeitung 4, PLAN 5.1, P06).** Jede Brücke hat dasselbe Gerüst: ein Kopftor an beiden Enden
 * ([gate]; das Tor in einem Inselkreis zeichnet das Insel-Piece über `OlympusPlans.bridgeHeads`), drei Ereignisse in
 * steigender Dichte, eine Engstelle ([narrow], 3 breit über [NARROW_LENGTH] Blöcke) und bei Siegelbrücken je einen
 * Torrahmen vor und hinter der Lücke ([sealFrame]; steht auch bei offener Lücke). Dazu ein Thema je Brücke
 * ([dressAB] … [dressEF]): Boden, Geländer, Licht und Ereignisse. Verlauf, Höhen, Treppenläufe, Lücke und Markierblock
 * bleiben wie vorher; wo was steht, rechnet [Layout] aus dem Verlauf (nie auf Stufen, Knicken oder an der Lücke).
 */
object OlympusBridges {

    /** Welche Spalten gezeichnet werden: alles, nur in Inseln (Insel-Piece), nur außerhalb (Brücken-Piece), nur die Lücke. */
    enum class Part { ALL, INSIDE, OUTSIDE, GAP }

    private const val FLIGHT = 4
    private const val HALF = 2
    private const val RAIL = 3
    private const val CORNER_ZONE = 4
    private const val PIER_SPACING = OlympusLayout.BRIDGE_PIER_SPACING

    /** Höchste Aufbauten über der Lauffläche (PLAN 5.1): Türme 14, Tore 9. */
    const val TOWER_HEIGHT = 14
    const val GATE_HEIGHT = 9

    /** Engstelle: so lang, so breit (zwischen massiven Wangen). */
    const val NARROW_LENGTH = 6
    const val NARROW_WIDTH = 3

    /** Kopftore und Siegelrahmen sind zwei Zellen tief. */
    const val GATE_DEPTH = 2

    /** Wie weit Aufbauten seitlich reichen dürfen (Mitte bis Außenkante; Kasten ±12). */
    const val DRESS_REACH = 9

    /** Kasten nach oben: höchster Turm plus Rand. */
    private const val BOX_TOP = TOWER_HEIGHT + 4

    /** Eine Zelle der Mittellinie: Lauflänge [s], Lage, Laufrichtung; [corner] = Knick. */
    class Cell(val s: Int, val x: Int, val z: Int, val dir: Direction, val corner: Boolean)

    /** Ein Ereignis auf dem Weg: Art, erste Zelle, Länge in Zellen, Seite (−1 links, +1 rechts, 0 beide/quer). */
    class Event(val kind: String, val s: Int, val length: Int, val side: Int = 0) {
        val range: IntRange get() = s until s + length
        override fun toString() = "$kind@$s+$length${if (side != 0) "/$side" else ""}"
    }

    /** Wo was auf einer Brücke steht (Zellindex). */
    class Layout(val narrow: Int, val events: List<Event>, val sealFrames: List<Int>, val startGate: Int, val headGate: Int) {
        override fun toString() = "Tore $startGate/$headGate, Engstelle $narrow, Rahmen $sealFrames, Ereignisse $events"
    }

    class Bridge(
        val id: String, val from: OlympusLayout.Isle, val to: OlympusLayout.Isle, corners: List<Pair<Int, Int>>,
        /** Bodenblock am Anfang und am Ende (Füße − 1). */
        val y0: Int, val y1: Int,
    ) {
        /** Wer die Lücke schließt (Boss-Schlüssel), `null` = keine Siegelbrücke. */
        val sealedBy: String? = OlympusLayout.SEALED_BRIDGES[from.letter to to.letter]

        val cells: List<Cell> = buildList {
            var s = 0
            for (i in 0 until corners.size - 1) {
                val (ax, az) = corners[i]
                val (bx, bz) = corners[i + 1]
                val dir = when {
                    bx > ax -> Direction.EAST
                    bx < ax -> Direction.WEST
                    bz > az -> Direction.SOUTH
                    else -> Direction.NORTH
                }
                val length = abs(bx - ax) + abs(bz - az)
                for (k in (if (i == 0) 0 else 1)..length) {
                    val corner = k == length && i < corners.size - 2
                    add(Cell(s++, ax + dir.offsetX * k, az + dir.offsetZ * k, dir, corner))
                }
            }
        }

        val length: Int get() = cells.size

        val cornerS: List<Int> = cells.filter { it.corner }.map { it.s }

        internal fun nearCorner(s: Int, margin: Int) = cornerS.any { abs(it - s) <= margin }

        private fun claimed(cell: Cell) = OlympusStructures.claim(cell.x, cell.z) != null

        /** Die Lücke: [OlympusLayout.SEAL_GAP] Zellen möglichst in der Mitte, nie an einem Knick oder in einer Insel. */
        val gap: IntRange? = if (sealedBy == null) null else {
            val want = length / 2 - OlympusLayout.SEAL_GAP / 2
            (0 until length).sortedBy { abs(it - want) }.firstOrNull { g0 ->
                val g1 = g0 + OlympusLayout.SEAL_GAP - 1
                g0 > 6 && g1 < length - 7 && (g0 - 3..g1 + 3).none { nearCorner(it, CORNER_ZONE + 1) || claimed(cells[it]) }
            }?.let { it until it + OlympusLayout.SEAL_GAP }
        }

        /** Zellen, an denen eine Stufe sitzt. */
        val steps: BooleanArray = BooleanArray(length).also { place(it) }

        /** Bodenblock je Zelle. */
        val level: IntArray = IntArray(length).also { levels ->
            var y = y0
            for (s in 0 until length) {
                if (steps[s]) y++
                levels[s] = y
            }
        }

        /** Pfeiler: alle 32 Zellen, nicht an Knicken, nicht in der Lücke, nicht nah an einer Insel. */
        val piers: List<Int> = (PIER_SPACING / 2 until length step PIER_SPACING).filter { s ->
            val cell = cells[s]
            !nearCorner(s, CORNER_ZONE + 2) && (gap == null || s !in gap.first - 4..gap.last + 4) &&
                OlympusLayout.ISLES.none { hypot((cell.x - it.center.x).toDouble(), (cell.z - it.center.z).toDouble()) < it.radius + 8 } &&
                OlympusPlans.EXTRA_CLAIMS.none { (_, box) -> cell.x in box.minX - 8..box.maxX + 8 && cell.z in box.minZ - 8..box.maxZ + 8 }
        }

        /** Kasten: Zellen ±12 seitlich (Aufbauten reichen höchstens [DRESS_REACH]), oben Platz für die Türme. */
        val box: BlockBox = run {
            val minX = cells.minOf { it.x } - 12
            val maxX = cells.maxOf { it.x } + 12
            val minZ = cells.minOf { it.z } - 12
            val maxZ = cells.maxOf { it.z } + 12
            BlockBox(minX, minOf(y0, y1) - 22, minZ, maxX, maxOf(y0, y1) + BOX_TOP, maxZ)
        }

        /** Mittelzelle der Lücke: Dort sitzt nach dem Nachsetzen der Markierblock. */
        fun marker(): BlockPos? {
            val g = gap ?: return null
            val cell = cells[g.first + OlympusLayout.SEAL_GAP / 2]
            return BlockPos(cell.x, level[cell.s] - 1, cell.z)
        }

        /** Mitte der Lücke (Füße), für Abstandsprüfungen. */
        fun gapCenter(): BlockPos? {
            val g = gap ?: return null
            val cell = cells[g.first + OlympusLayout.SEAL_GAP / 2]
            return BlockPos(cell.x, level[cell.s] + 1, cell.z)
        }

        /** Die beiden Sperren an den Enden der Lücke (Füße), für den Hinweis an Spieler. */
        fun gapEnds(): List<BlockPos> {
            val g = gap ?: return emptyList()
            return listOf(g.first - 1, g.last + 1).map { s -> BlockPos(cells[s].x, level[s] + 1, cells[s].z) }
        }

        /** Gehört die Spalte zur Lücke (samt Sperrreihen)? */
        fun inGapColumn(x: Int, z: Int): Boolean {
            val g = gap ?: return false
            for (s in g.first - 1..g.last + 1) {
                val cell = cells[s]
                val (px, pz) = perp(cell.dir)
                for (w in -RAIL..RAIL) if (cell.x + px * w == x && cell.z + pz * w == z) return true
            }
            return false
        }

        /** Spalten quer über die ganze Breite des Kastens auf Höhe der Lücke: Dort wächst kein Fels (sonst ein Steg). */
        private val gapBand: Set<Long> by lazy {
            val g = gap ?: return@lazy emptySet()
            buildSet {
                for (s in g.first - 1..g.last + 1) {
                    val cell = cells[s]
                    val (px, pz) = perp(cell.dir)
                    for (w in -12..12) add(BlockPos.asLong(cell.x + px * w, 0, cell.z + pz * w))
                }
            }
        }

        fun inGapBand(x: Int, z: Int): Boolean = BlockPos.asLong(x, 0, z) in gapBand

        /** Erste und letzte Zelle außerhalb jeder Insel. */
        val firstFree: Int get() = cells.indexOfFirst { OlympusStructures.claim(it.x, it.z) == null }.coerceAtLeast(0)
        val lastFree: Int get() = cells.indexOfLast { OlympusStructures.claim(it.x, it.z) == null }.coerceAtLeast(0)

        /** Darf an Zelle [s] ein Bauteil stehen: keine Stufe, kein Knick, nicht in einer Insel, nicht an der Lücke. */
        fun flat(s: Int): Boolean = s in 0 until length && !steps[s] && !nearCorner(s, CORNER_ZONE + 1) && !claimed(cells[s]) &&
            (gap == null || s !in gap.first - 3..gap.last + 3)

        /** Anfang der freien Strecke von [len] Zellen, deren Mitte [center] am nächsten liegt und die nichts in [taken] berührt. */
        fun slot(center: Int, len: Int, taken: List<IntRange> = emptyList()): Int {
            val want = center - len / 2
            return (0 until length).sortedBy { abs(it - want) }.firstOrNull { s ->
                (s until s + len).all { flat(it) } && taken.none { r -> s <= r.last + 1 && s + len - 1 >= r.first - 1 }
            } ?: error("Brücke $id: kein Platz für $len Zellen bei $center")
        }

        /** Kopftor an der Ausgangsinsel (zwei Zellen ab hier) und an der Zielinsel; beide im Inselrand. */
        val startGate: Int get() = (firstFree - 2).coerceAtLeast(1)
        val headGate: Int get() = (lastFree + 1).coerceAtMost(length - 1 - GATE_DEPTH)

        /** Wo was steht; aus dem Verlauf berechnet ([plan]). */
        val layout: Layout by lazy { plan(this) }

        /** Lage zwischen erster und letzter freier Zelle (0 … 1) als Zellindex. */
        fun at(t: Double): Int = (firstFree + t * (lastFree - firstFree)).roundToInt()

        /**
         * Verteilt die Steigung auf Treppenläufe von je vier Stufen: möglichst gleichmäßig über die Länge, nie in Inseln,
         * an Knicken, an der Lücke oder an den Enden.
         */
        private fun place(steps: BooleanArray) {
            val rise = y1 - y0
            if (rise <= 0) return
            val ok = BooleanArray(length) { s ->
                s >= 3 && s < length - 3 && !nearCorner(s, CORNER_ZONE + 1) && !claimed(cells[s]) &&
                    (gap == null || s !in gap.first - 2..gap.last + 2)
            }
            val flights = (rise + FLIGHT - 1) / FLIGHT
            var left = rise
            var lastEnd = -10
            val candidates = (0 until length).filter { ok[it] }
            for (k in 0 until flights) {
                val count = minOf(FLIGHT, left)
                val target = candidates[((k + 0.5) * candidates.size / flights).toInt().coerceIn(0, candidates.size - 1)]
                val start = (0 until length).sortedBy { abs(it - target) }.firstOrNull { st ->
                    st > lastEnd + 2 && (st until st + count).all { it < length && ok[it] }
                } ?: (0 until length).firstOrNull { st -> st > lastEnd + 2 && (st until st + count).all { it < length && ok[it] } }
                    ?: error("Brücke $id: kein Platz für Treppenlauf ${k + 1} von $flights")
                for (s in start until start + count) steps[s] = true
                lastEnd = start + count - 1
                left -= count
            }
        }
    }

    /** Senkrecht zur Laufrichtung (nach rechts gesehen). */
    fun perp(d: Direction): Pair<Int, Int> = -d.offsetZ to d.offsetX

    val AB = Bridge("ab", OlympusLayout.A, OlympusLayout.B, listOf(0 to -21, 0 to -99), 119, 127)
    val BC = Bridge("bc", OlympusLayout.B, OlympusLayout.C, listOf(0 to -161, 0 to -243), 127, 139)
    val CD = Bridge("cd", OlympusLayout.C, OlympusLayout.D, listOf(31 to -300, 120 to -300, 120 to -369), 139, 159)
    val DE = Bridge("de", OlympusLayout.D, OlympusLayout.E, listOf(166 to -420, 166 to -531), 159, 191)
    val EF = Bridge("ef", OlympusLayout.E, OlympusLayout.F, listOf(135 to -604, 42 to -604, 42 to -656), 203, 231)

    val ALL: List<Bridge> = listOf(AB, BC, CD, DE, EF)

    fun byIsles(from: Char, to: Char): Bridge? = ALL.firstOrNull { it.from.letter == from && it.to.letter == to }

    // ---------------------------------------------------------------------------------------------
    // Planung: wo die Ereignisse liegen (PLAN 5.1, Steigerung zum Boss: Abstände werden kürzer)
    // ---------------------------------------------------------------------------------------------

    /** Kleiner Helfer: reserviert Strecken nacheinander, damit sich Bauteile nicht berühren. */
    private class Planner(val b: Bridge) {
        val taken = ArrayList<IntRange>()
        val events = ArrayList<Event>()
        fun take(center: Int, len: Int): Int = b.slot(center, len, taken).also { taken.add(it until it + len) }
        fun event(kind: String, center: Int, len: Int, side: Int = 0): Int = take(center, len).also { events.add(Event(kind, it, len, side)) }
    }

    private fun sealFrames(b: Bridge): List<Int> {
        val g = b.gap ?: return emptyList()
        return listOf(g.first - 2 - GATE_DEPTH + 1, g.last + 2)
    }

    private fun plan(b: Bridge): Layout {
        val p = Planner(b)
        if (b.startGate >= b.firstFree) p.taken.add(b.startGate until b.startGate + GATE_DEPTH)
        val narrow: Int
        when (b.id) {
            // Weg der Boten: Windbogen → Engstelle → Aussicht mit Botenstatue → zwei Windbögen dicht vor dem Flügeltor.
            "ab" -> {
                narrow = p.take(b.at(0.5), NARROW_LENGTH)
                p.event("wind_arch", b.at(0.33), 1)
                p.event("belvedere", 48, 7, 1)
                p.event("wind_arch", b.at(0.85), 1)
                p.event("wind_arch", b.at(0.91), 1)
            }
            // Weg der Schwelle: Bogen der Trivia, Engstelle vor der Lücke, danach zwei Bögen dicht hintereinander.
            "bc" -> {
                narrow = p.take(b.gap!!.first - 7, NARROW_LENGTH)
                p.event("trivia_arch", b.at(0.2), 1)
                p.event("trivia_arch", b.at(0.72), 1)
                p.event("trivia_arch", b.at(0.82), 1)
            }
            // Schlackenweg: Kettenkran mit Schlackekübeln, Wachturm am Knick, zweiter Kran über den Glutrissen vor D.
            "cd" -> {
                narrow = p.take(b.gap!!.first - 6, NARROW_LENGTH)
                p.event("slag_gantry", b.at(0.17), 1)
                p.events.add(Event("corner_tower", b.cornerS.first(), 1))
                p.event("slag_gantry", b.at(0.86), 1)
            }
            // Heerstraße: Wachturm links, Tropaia, Wachturm rechts, Torhaus als Engstelle.
            "de" -> {
                p.event("tower", b.at(0.11), 4, -1)
                p.event("tropaia", b.at(0.32), 5)
                p.event("tower", b.at(0.72), 4, 1)
                narrow = p.take(b.at(0.82), NARROW_LENGTH)
            }
            // Himmelstreppe: drei Götterstatuen, jede größer; Engstelle vor dem Tor der Blitze.
            else -> {
                p.event("god_statue", b.at(0.14), 3, -1)
                p.event("god_statue", 85, 3, 1)
                p.event("god_statue", 112, 3, -1)
                narrow = p.take(b.at(0.88), NARROW_LENGTH)
            }
        }
        return Layout(narrow, p.events.sortedBy { it.s }, sealFrames(b), b.startGate, b.headGate)
    }

    /** Für Bericht und Prüfung: Lage aller Bauteile je Brücke. */
    fun describe(): List<String> = ALL.map { "${it.id}: ${it.layout}" }

    // ---------------------------------------------------------------------------------------------
    // Material
    // ---------------------------------------------------------------------------------------------

    private fun s(b: Block): BlockState = b.defaultState

    private fun wallState(block: Block, along: Direction): BlockState {
        val alongX = along.axis == Direction.Axis.X
        return block.defaultState
            .with(WallBlock.EAST_WALL_SHAPE, if (alongX) WallShape.LOW else WallShape.NONE)
            .with(WallBlock.WEST_WALL_SHAPE, if (alongX) WallShape.LOW else WallShape.NONE)
            .with(WallBlock.NORTH_WALL_SHAPE, if (alongX) WallShape.NONE else WallShape.LOW)
            .with(WallBlock.SOUTH_WALL_SHAPE, if (alongX) WallShape.NONE else WallShape.LOW)
            .with(WallBlock.UP, false)
    }

    private fun post(block: Block): BlockState = OlympKit.wall(block, alongX = true, post = true)

    /** Boden und Geländer einer Zelle: Thema der Brücke, bei B–C nach Band gestuft. */
    private class Deck(
        val field: Pal, val edge: BlockState, val center: Pal, val mark: BlockState, val markEvery: Int,
        val step: Block, val stepUnder: BlockState, val rail: Block, val postBase: BlockState, val postShaft: BlockState, val cap: Block,
    )

    /** Werkstoff der Aufbauten (Tore, Bögen, Wangen, Türme). */
    private class Style(
        val wall: Pal, val plinth: BlockState, val shaft: BlockState, val band: BlockState,
        val stair: Block, val slab: Block, val accent: BlockState, val lamp: BlockState,
    )

    private fun marbleStyle() = Style(
        DecoPalettes.MARBLE_WALL, s(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE), s(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR),
        s(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE), HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_STAIRS, HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_SLAB,
        s(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE), Kit.lantern(hanging = true, soul = false),
    )

    private fun basaltStyle() = Style(
        DecoPalettes.BASALT_WALL, s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT), s(HhaDecoBlocks.TARTARUS_BASALT_PILLAR),
        s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT), HhaDecoBlocks.TARTARUS_BASALT_BRICK_STAIRS, HhaDecoBlocks.TARTARUS_BASALT_BRICK_SLAB,
        s(HhaDecoBlocks.SOUL_VEIN_BASALT), Kit.lantern(hanging = true, soul = true),
    )

    /** Rußiger Marmor der Schmiede: Marmorwand mit Flecken aus Andesit und Tuff, die zum Ziel hin dichter werden. */
    private fun sooty(amount: Double): Pal = { x, y, z ->
        val n = Kit.noise(Math.floorDiv(x, 2), Math.floorDiv(y, 2), Math.floorDiv(z, 2)) % 1000
        when {
            n < amount * 500 -> s(Blocks.POLISHED_ANDESITE)
            n < amount * 800 -> s(Blocks.TUFF_BRICKS)
            else -> DecoPalettes.MARBLE_WALL(x, y, z)
        }
    }

    private fun forgeStyle() = Style(
        sooty(0.35), s(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE), s(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR), s(HhaDecoBlocks.OLYMPUS_BRONZE),
        HhaDecoBlocks.OLYMPUS_BRONZE_STAIRS, HhaDecoBlocks.OLYMPUS_BRONZE_SLAB, s(HhaDecoBlocks.EMBER_BASALT), Kit.lantern(hanging = true, soul = false),
    )

    private fun warStyle() = Style(
        DecoPalettes.MARBLE_WALL, s(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE), s(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR), s(Blocks.RED_TERRACOTTA),
        HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_STAIRS, HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_SLAB, s(HhaDecoBlocks.OLYMPUS_BRONZE), Kit.lantern(hanging = true, soul = false),
    )

    private fun stormStyle() = Style(
        DecoPalettes.MARBLE_WALL, s(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE), s(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR), s(HhaDecoBlocks.STORM_MARBLE),
        HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_STAIRS, HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_SLAB, s(HhaDecoBlocks.STORM_MARBLE), Kit.lantern(hanging = true, soul = false),
    )

    /** Stufen des Übergangs auf B–C: 0 Marmor, 1 Mittelband Basalt, 2 dazu Ränder und Geländer, 3 ganz Basalt. */
    private fun bcZone(b: Bridge, s: Int): Int = when {
        s < b.at(0.30) -> 0
        s < b.at(0.55) -> 1
        s < b.at(0.75) -> 2
        else -> 3
    }

    private fun quartzDeck(center: Pal, mark: BlockState, edge: BlockState = s(Blocks.QUARTZ_BRICKS), markEvery: Int = 8) = Deck(
        { _, _, _ -> s(Blocks.SMOOTH_QUARTZ) }, edge, center, mark, markEvery,
        Blocks.SMOOTH_QUARTZ_STAIRS, s(Blocks.SMOOTH_QUARTZ), Blocks.DIORITE_WALL, s(Blocks.CHISELED_QUARTZ_BLOCK), s(Blocks.QUARTZ_PILLAR), Blocks.SMOOTH_QUARTZ_SLAB,
    )

    private fun deck(b: Bridge, s: Int): Deck {
        val polished: Pal = { _, _, _ -> s(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE) }
        return when (b.id) {
            "ab" -> quartzDeck(polished, s(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE))
            "bc" -> {
                val basalt: Pal = { _, _, _ -> s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT) }
                when (bcZone(b, s)) {
                    0 -> quartzDeck(polished, s(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE))
                    1 -> quartzDeck(basalt, s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT))
                    2 -> Deck(
                        { _, _, _ -> s(Blocks.SMOOTH_QUARTZ) }, s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS), basalt, s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT), 8,
                        Blocks.SMOOTH_QUARTZ_STAIRS, s(Blocks.SMOOTH_QUARTZ), HhaDecoBlocks.TARTARUS_BASALT_BRICK_WALL,
                        s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT), s(HhaDecoBlocks.TARTARUS_BASALT_PILLAR), HhaDecoBlocks.TARTARUS_BASALT_BRICK_SLAB,
                    )
                    else -> Deck(
                        DecoPalettes.BASALT_FLOOR, s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS), { _, _, _ -> s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT) },
                        s(HhaDecoBlocks.SOUL_VEIN_BASALT), 8,
                        HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT), HhaDecoBlocks.TARTARUS_BASALT_BRICK_WALL,
                        s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT), s(HhaDecoBlocks.TARTARUS_BASALT_PILLAR), HhaDecoBlocks.TARTARUS_BASALT_BRICK_SLAB,
                    )
                }
            }
            "cd" -> {
                val t = s.toDouble() / b.length
                val near = s >= b.lastFree - 30
                val field: Pal = { x, y, z ->
                    val n = Kit.noise(Math.floorDiv(x, 2), y, Math.floorDiv(z, 2)) % 1000
                    when {
                        n < 120 + 300 * t -> s(Blocks.POLISHED_ANDESITE)
                        n < 170 + 420 * t -> s(Blocks.TUFF_BRICKS)
                        else -> s(Blocks.SMOOTH_QUARTZ)
                    }
                }
                // Vor D reißt die Mitte auf: Glutrisse in poliertem Basalt.
                val center: Pal = if (near) { x, y, z -> if (Kit.noise(x, y, z) % 3 == 0) s(HhaDecoBlocks.EMBER_BASALT) else s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT) } else polished
                Deck(
                    field, s(Blocks.QUARTZ_BRICKS), center, if (near) s(HhaDecoBlocks.EMBER_BASALT) else s(HhaDecoBlocks.OLYMPUS_BRONZE), 8,
                    Blocks.SMOOTH_QUARTZ_STAIRS, s(Blocks.SMOOTH_QUARTZ), Blocks.ANDESITE_WALL, s(Blocks.CHISELED_QUARTZ_BLOCK), s(Blocks.QUARTZ_PILLAR), Blocks.SMOOTH_QUARTZ_SLAB,
                )
            }
            "de" -> quartzDeck({ _, _, _ -> s(Blocks.RED_TERRACOTTA) }, s(HhaDecoBlocks.OLYMPUS_BRONZE), s(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS))
            else -> Deck(
                { _, _, _ -> s(Blocks.SMOOTH_QUARTZ) }, s(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS), polished, s(HhaDecoBlocks.STORM_MARBLE), 4,
                HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_STAIRS, s(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE), HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_WALL,
                s(HhaDecoBlocks.STORM_MARBLE), s(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR), HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_SLAB,
            )
        }
    }

    /**
     * Licht auf einem Geländerpfosten (jeder achte Pfosten, an Pfeilern und an den Enden). Steigerung zum Boss: A–B
     * Laternen, B–C Laternen → Seelenlaternen, C–D Bronze-Feuerschalen, D–E Laternen, E–F Blitzableiter auf
     * Sturmmarmor (der Sturmmarmor leuchtet selbst).
     */
    private fun lampOn(b: Bridge, s: Int): BlockState = when (b.id) {
        "bc" -> Kit.lantern(hanging = false, soul = bcZone(b, s) >= 2)
        "ef" -> s(Blocks.WAXED_LIGHTNING_ROD)
        else -> Kit.lantern(hanging = false, soul = false)
    }

    private fun style(b: Bridge, s: Int): Style = when (b.id) {
        "bc" -> if (bcZone(b, s) >= 2) basaltStyle() else marbleStyle()
        "cd" -> forgeStyle()
        "de" -> warStyle()
        "ef" -> stormStyle()
        else -> marbleStyle()
    }

    // ---------------------------------------------------------------------------------------------
    // Zeichnen
    // ---------------------------------------------------------------------------------------------

    /**
     * Zeichnet [b] auf [c]. [sealed]: Die Lücke bleibt offen und an ihren Enden steht je eine Sperre. Mit [Part.GAP]
     * werden nur Lücke und Sperrreihen gezeichnet (Nachsetzen im Spiel).
     */
    fun draw(c: SkyCanvas, b: Bridge, part: Part, sealed: Boolean = b.sealedBy != null) {
        fun owns(x: Int, z: Int): Boolean = when (part) {
            Part.ALL -> true
            Part.INSIDE -> OlympusStructures.claim(x, z) != null
            Part.OUTSIDE -> OlympusStructures.claim(x, z) == null
            Part.GAP -> b.inGapColumn(x, z)
        }
        fun put(x: Int, y: Int, z: Int, state: BlockState) {
            if (c.inside(x, z) && owns(x, z)) c.put(x, y, z, state)
        }
        val gap = b.gap
        for (cell in b.cells) {
            if (b.cornerS.any { abs(it - cell.s) <= RAIL } && !cell.corner) continue
            if (cell.corner) {
                corner(c, b, cell, ::put, ::owns)
                continue
            }
            val inGap = gap != null && cell.s in gap
            if (inGap && sealed) continue
            deckCell(b, cell, sealed, ::put)
            if (cell.s in b.piers) pier(c, b, cell, ::put, ::owns)
        }
        if (part != Part.GAP) dress(Ctx(c, b, ::owns))
        if (sealed && gap != null && part != Part.GAP) brokenEnds(c, b, gap, ::put)
    }

    /** Lauffläche, Kiel und Geländer einer Zelle. */
    private fun deckCell(b: Bridge, cell: Cell, sealed: Boolean, put: (Int, Int, Int, BlockState) -> Unit) {
        val gap = b.gap
        val y = b.level[cell.s]
        val step = b.steps[cell.s]
        val (px, pz) = perp(cell.dir)
        val barrier = sealed && gap != null && (cell.s == gap.first - 1 || cell.s == gap.last + 1)
        val keel = keelDepth(b, cell.s)
        val marker = !sealed && gap != null && cell.s == gap.first + OlympusLayout.SEAL_GAP / 2
        val d = deck(b, cell.s)
        for (w in -RAIL..RAIL) {
            val x = cell.x + px * w
            val z = cell.z + pz * w
            val aw = abs(w)
            val outward = if (w > 0) dirOf(px, pz) else dirOf(-px, -pz)
            if (aw <= HALF) {
                // Lauffläche.
                if (step) {
                    put(x, y, z, Kit.stair(d.step, cell.dir))
                    put(x, y - 1, z, d.stepUnder)
                } else {
                    put(x, y, z, when {
                        aw == HALF -> d.edge
                        w == 0 && cell.s % d.markEvery == d.markEvery / 2 -> d.mark
                        w == 0 -> d.center(x, y, z)
                        else -> d.field(x, y, z)
                    })
                }
                for (h in 1..3) put(x, y + h, z, Kit.AIR)
                if (barrier) put(x, y + 1, z, if (w == 0) s(Blocks.CHISELED_QUARTZ_BLOCK) else wallState(Blocks.DIORITE_WALL, cell.dir.rotateYClockwise()))
                // Unterseite: Kiel in der Mitte, an den Rändern eine Schräge.
                if (aw == HALF) put(x, y - 1, z, Kit.stair(Blocks.SMOOTH_QUARTZ_STAIRS, outward.opposite, top = true))
                else {
                    put(x, y - 1, z, if (marker && w == 0) s(Blocks.CHISELED_QUARTZ_BLOCK) else s(Blocks.QUARTZ_BRICKS))
                    if (aw == 0) for (k in 2..keel) put(x, y - k, z, if (k == keel) Kit.stair(Blocks.QUARTZ_STAIRS, cell.dir, top = true) else s(Blocks.QUARTZ_BRICKS))
                    else for (k in 2 until keel) put(x, y - k, z, if (k == keel - 1) Kit.stair(Blocks.SMOOTH_QUARTZ_STAIRS, outward.opposite, top = true) else s(Blocks.SMOOTH_QUARTZ))
                }
            } else {
                // Geländer: Sockel, Brüstung, alle vier Zellen ein Pfosten.
                val isPost = cell.s % 4 == 0 || barrier
                put(x, y, z, if (isPost) d.postBase else s(Blocks.QUARTZ_BRICKS))
                if (step) put(x, y - 1, z, s(Blocks.QUARTZ_BRICKS))
                put(x, y - 1 - (if (step) 1 else 0), z, Kit.stair(Blocks.QUARTZ_STAIRS, outward.opposite, top = true))
                if (isPost) {
                    put(x, y + 1, z, d.postShaft)
                    val lamp = cell.s % 8 == 0 || cell.s in b.piers || cell.s == 2 || cell.s == b.length - 3
                    put(x, y + 2, z, if (lamp) lampOn(b, cell.s) else Kit.slab(d.cap))
                } else {
                    put(x, y + 1, z, wallState(d.rail, cell.dir))
                    put(x, y + 2, z, Kit.AIR)
                }
            }
        }
    }

    private fun dirOf(dx: Int, dz: Int): Direction = when {
        dx > 0 -> Direction.EAST
        dx < 0 -> Direction.WEST
        dz > 0 -> Direction.SOUTH
        else -> Direction.NORTH
    }

    /** Wie tief der Kiel reicht: an Pfeilern und Inseln tief (Bogenansatz), in der Spannweite flach. */
    private fun keelDepth(b: Bridge, s: Int): Int {
        val supports = b.piers + b.cornerS + listOf(b.firstFree, b.lastFree)
        val u = supports.minOf { abs(it - s) }.toDouble()
        val t = (1.0 - (u / 14.0)).coerceIn(0.0, 1.0)
        return 2 + (7.0 * t * t).roundToInt()
    }

    /** Absatz am Knick: 7 × 7, Geländer an den freien Seiten, eine Herme an der Außenecke, darunter ein großer Fels. */
    private fun corner(c: SkyCanvas, b: Bridge, cell: Cell, put: (Int, Int, Int, BlockState) -> Unit, owns: (Int, Int) -> Boolean) {
        val y = b.level[cell.s]
        val inDir = b.cells[cell.s - 1].dir
        val outDir = b.cells[cell.s + 1].dir
        for (i in -RAIL..RAIL) for (j in -RAIL..RAIL) {
            val x = cell.x + i
            val z = cell.z + j
            val edge = abs(i) == RAIL || abs(j) == RAIL
            // Offene Seiten: wo der Lauf hereinkommt (entgegen inDir) und wo er hinausgeht (outDir).
            val openIn = (i * -inDir.offsetX + j * -inDir.offsetZ) == RAIL && abs(i * inDir.offsetZ + j * inDir.offsetX) <= HALF
            val openOut = (i * outDir.offsetX + j * outDir.offsetZ) == RAIL && abs(i * outDir.offsetZ + j * outDir.offsetX) <= HALF
            val ring = maxOf(abs(i), abs(j))
            put(x, y, z, when {
                i == 0 && j == 0 -> s(Blocks.CHISELED_QUARTZ_BLOCK)
                ring == 2 && !edge -> s(Blocks.QUARTZ_BRICKS)
                edge && !(openIn || openOut) -> s(Blocks.QUARTZ_BRICKS)
                else -> s(Blocks.SMOOTH_QUARTZ)
            })
            for (h in 1..3) put(x, y + h, z, Kit.AIR)
            put(x, y - 1, z, if (edge) Kit.stair(Blocks.QUARTZ_STAIRS, Kit.inward(i, j), top = true) else s(Blocks.QUARTZ_BRICKS))
            if (edge && !(openIn || openOut)) {
                val isPost = abs(i) == RAIL && abs(j) == RAIL || i == 0 || j == 0
                if (isPost) {
                    put(x, y + 1, z, s(Blocks.QUARTZ_PILLAR))
                    put(x, y + 2, z, Kit.slab(Blocks.SMOOTH_QUARTZ_SLAB))
                } else put(x, y + 1, z, wallState(Blocks.DIORITE_WALL, if (abs(i) == RAIL) Direction.SOUTH else Direction.EAST))
            }
        }
        // Herme an der Außenecke (dort, wo weder Lauf hereinkommt noch hinausgeht).
        val hx = cell.x + (inDir.offsetX - outDir.offsetX) * 2
        val hz = cell.z + (inDir.offsetZ - outDir.offsetZ) * 2
        val face = dirOf(cell.x - hx, cell.z - hz)
        put(hx, y + 1, hz, s(Blocks.CHISELED_QUARTZ_BLOCK))
        put(hx, y + 2, hz, s(Blocks.QUARTZ_PILLAR))
        put(hx, y + 3, hz, s(Blocks.SMOOTH_QUARTZ))
        put(hx + face.offsetX, y + 2, hz + face.offsetZ, Kit.stair(Blocks.SMOOTH_QUARTZ_STAIRS, face.opposite, top = true))
        put(hx, y + 4, hz, Kit.slab(Blocks.SMOOTH_QUARTZ_SLAB))
        put(cell.x, y + 1, cell.z, Kit.AIR)
        // Der Fels unter dem Absatz.
        val body = OlympusIsles.Body(cell.x, y - 2, cell.z, 6.5, IsleKind.MARBLE, b.id.hashCode() + cell.s, 0.85)
        val canvas = ClippedCanvas(c) { x, z -> owns(x, z) && OlympusStructures.claim(x, z) == null }
        OlympusIsles.body(canvas, body)
        OlympusIsles.cloudSkirt(canvas, body, 2)
    }

    /** Pfeilerfels unter der Brücke, mit ein, zwei Wolkenfetzen. */
    private fun pier(c: SkyCanvas, b: Bridge, cell: Cell, put: (Int, Int, Int, BlockState) -> Unit, owns: (Int, Int) -> Boolean) {
        val y = b.level[cell.s]
        // Der Fels trägt die Brücke: Er beginnt direkt unter der Fahrbahn und hängt als Spitze tief hinab.
        val top = y - 2
        val seed = b.id.hashCode() * 31 + cell.s
        val canvas = ClippedCanvas(c) { x, z -> owns(x, z) && OlympusStructures.claim(x, z) == null }
        val body = OlympusIsles.Body(cell.x, top, cell.z, 4.5 + OlympusIsles.rnd(seed, 1) * 1.5, IsleKind.MARBLE, seed, 0.8, 1.9)
        OlympusIsles.body(canvas, body)
        for (k in 1..keelDepth(b, cell.s)) put(cell.x, y - k, cell.z, s(Blocks.QUARTZ_BRICKS))
        val (px, pz) = perp(cell.dir)
        val side = if (OlympusIsles.rnd(seed, 2) < 0.5) 1 else -1
        OlympusIsles.cloud(canvas, cell.x + px * side * 5, top - 3, cell.z + pz * side * 5, 6.5, 4.5, seed, 2.2)
    }

    /** Die Enden der Lücke: ein paar ausgebrochene Kanten, damit die Lücke wie eingestürzt aussieht und nicht wie vergessen. */
    private fun brokenEnds(c: SkyCanvas, b: Bridge, gap: IntRange, put: (Int, Int, Int, BlockState) -> Unit) {
        for (s in listOf(gap.first - 1, gap.last + 1)) {
            val cell = b.cells[s]
            val toward = if (s < gap.first) cell.dir else cell.dir.opposite
            val y = b.level[s]
            val (px, pz) = perp(cell.dir)
            for (w in -HALF..HALF) {
                if (Kit.noise(cell.x + w, y, cell.z) % 3 != 0) continue
                put(cell.x + px * w + toward.offsetX, y - 1, cell.z + pz * w + toward.offsetZ, Kit.stair(Blocks.QUARTZ_STAIRS, toward.opposite, top = true))
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Aufbauten (Überarbeitung 4): Koordinaten als Zelle s, Querlage w (rechts +), Höhe h über dem Bodenblock
    // ---------------------------------------------------------------------------------------------

    private val SIDES = intArrayOf(-1, 1)

    /** Zeichenhilfe für die Aufbauten einer Brücke; schreibt nur in eigene Spalten. */
    private class Ctx(val c: SkyCanvas, val b: Bridge, val owns: (Int, Int) -> Boolean) {
        /** Für die Bausteine aus [OlympKit] (Figuren, Hermen). */
        val cc = ClippedCanvas(c) { x, z -> c.inside(x, z) && owns(x, z) }

        /** Für Felsen: nur außerhalb der Inseln und nie quer über der Lücke. */
        val rocks = ClippedCanvas(c) { x, z -> c.inside(x, z) && owns(x, z) && OlympusStructures.claim(x, z) == null && !b.inGapBand(x, z) }

        fun x(s: Int, w: Int) = b.cells[s].x + perp(b.cells[s].dir).first * w
        fun z(s: Int, w: Int) = b.cells[s].z + perp(b.cells[s].dir).second * w
        fun y(s: Int) = b.level[s]
        fun forward(s: Int): Direction = b.cells[s].dir

        /** Nach außen auf Seite [side] (+1 rechts, −1 links). */
        fun outward(s: Int, side: Int): Direction {
            val (px, pz) = perp(b.cells[s].dir)
            return if (side > 0) dirOf(px, pz) else dirOf(-px, -pz)
        }

        fun put(s: Int, w: Int, h: Int, state: BlockState) = putY(s, w, y(s) + h, state)

        fun putY(s: Int, w: Int, y: Int, state: BlockState) {
            val x = x(s, w)
            val z = z(s, w)
            if (c.inside(x, z) && owns(x, z)) c.put(x, y, z, state)
        }

        fun soft(s: Int, w: Int, h: Int, state: BlockState) {
            val x = x(s, w)
            val z = z(s, w)
            if (c.inside(x, z) && owns(x, z)) c.soft(x, y(s) + h, z, state)
        }

        /** Block aus einer Palette an (s, w, absolut y). */
        fun pal(s: Int, w: Int, y: Int, p: Pal) = putY(s, w, y, p(x(s, w), y, z(s, w)))

        fun dirOf(dx: Int, dz: Int): Direction = OlympusBridges.dirOf(dx, dz)
    }

    /** Thema und Gerüst der Brücke (PLAN 5.1). */
    private fun dress(k: Ctx) {
        val b = k.b
        val layout = b.layout
        when (b.id) {
            "ab" -> dressAB(k)
            "bc" -> dressBC(k)
            "cd" -> dressCD(k)
            "de" -> dressDE(k)
            else -> dressEF(k)
        }
        for (f in layout.sealFrames) sealFrame(k, f)
    }

    /** Fels unter einem Aufbau: wie [pier], an Zelle [s], Querlage [w]. */
    private fun rock(k: Ctx, s: Int, w: Int, radius: Double) {
        val seed = k.b.id.hashCode() * 53 + s * 7 + w
        val body = OlympusIsles.Body(k.x(s, w), k.y(s) - 2, k.z(s, w), radius, IsleKind.MARBLE, seed, 0.8, 1.9)
        OlympusIsles.body(k.rocks, body)
    }

    /** Fundament unter einem Pfosten außerhalb der Lauffläche: bis [depth] unter die Fahrbahn, unten eine Schräge. */
    private fun footing(k: Ctx, s: Int, w: Int, st: Style, depth: Int = 4) {
        val y = k.y(s)
        for (yy in y - depth + 1..y) k.pal(s, w, yy, st.wall)
        k.putY(s, w, y - depth, Kit.stair(st.stair, k.outward(s, if (w > 0) -1 else 1), top = true))
    }

    enum class GateKind { PLAIN, WINGS, BOLTS, BANNERS, SEAL }

    /**
     * Kopftor bzw. Torrahmen: zwei Pylone (je 2 × 2, Querlage ±3…4) über [GATE_DEPTH] Zellen ab [s0], lichte Weite 5,
     * lichte Höhe 5 (am Rand 4, Bogenansatz); Architrav +6, Fries +7, Gesims +8, Krone +9 ([GATE_HEIGHT]).
     */
    private fun gate(k: Ctx, s0: Int, st: Style, kind: GateKind) {
        val cells = s0 until s0 + GATE_DEPTH
        val top = cells.maxOf { k.y(it) }
        for (s in cells) for (side in SIDES) {
            val out = k.outward(s, side)
            for (a in 3..4) {
                val w = side * a
                if (a == 4) footing(k, s, w, st) else {
                    k.pal(s, w, k.y(s), st.wall)
                    k.pal(s, w, k.y(s) - 1, st.wall)
                }
                for (y in k.y(s) + 1..top + 5) {
                    val state = when {
                        y == k.y(s) + 1 -> st.plinth
                        y == top + 4 -> st.band
                        a == 3 -> st.shaft
                        else -> st.wall(k.x(s, w), y, k.z(s, w))
                    }
                    k.putY(s, w, y, state)
                }
            }
            // Bogenansatz unter dem Architrav.
            k.putY(s, side * 2, top + 5, Kit.stair(st.stair, out, top = true))
        }
        // Architrav +6, Fries +7 (Mitte Akzent), Abdeckung +8 mit Schrägen an den Enden; Gesims vor den Stirnseiten.
        for (s in cells) for (w in -4..4) {
            k.putY(s, w, top + 6, st.plinth)
            k.putY(s, w, top + 7, if (w == 0) st.accent else st.wall(k.x(s, w), top + 7, k.z(s, w)))
            val towardMiddle = if (w > 0) k.outward(s, -1) else k.outward(s, 1)
            k.putY(s, w, top + 8, if (abs(w) == 4) Kit.stair(st.stair, towardMiddle) else Kit.slab(st.slab))
            val face = if (s == s0) k.forward(s).opposite else k.forward(s)
            putFace(k, s, w, top + 6, face, Kit.stair(st.stair, face.opposite, top = true))
        }
        // Krone und Licht.
        for (s in cells) {
            k.putY(s, 0, top + 9, st.accent)
            for (side in SIDES) k.putY(s, side, top + 9, Kit.slab(st.slab))
        }
        k.putY(s0, 0, top + 5, st.lamp)
        when (kind) {
            GateKind.WINGS -> for (s in cells) for (side in SIDES) wing(k, s, side, top, st)
            GateKind.BOLTS -> for (side in SIDES) {
                k.putY(s0, side * 4, top + 9, s(Blocks.WAXED_LIGHTNING_ROD))
                k.putY(s0 + 1, side * 4, top + 9, s(Blocks.WAXED_LIGHTNING_ROD))
                for (s in cells) for (a in 5..7) for (h in 5..8) {
                    if (Kit.noise(k.x(s, side * a), top + h, k.z(s, side * a)) % 5 < 3 - (a - 5)) k.c.soft(k.x(s, side * a), top + h, k.z(s, side * a), s(HhaDecoBlocks.STORM_CLOUD))
                }
            }
            GateKind.BANNERS -> for (s in cells) for (side in SIDES) {
                k.putY(s, side * 5, top + 3, s(Blocks.RED_WALL_BANNER).with(Properties.HORIZONTAL_FACING, k.outward(s, side)))
            }
            GateKind.PLAIN, GateKind.SEAL -> {}
        }
        // Eigener Fels nur, wenn das Tor ganz außerhalb der Inseln steht (im Inselrand trägt die Insel).
        if (cells.all { s -> OlympusStructures.claim(k.x(s, 0), k.z(s, 0)) == null && OlympusStructures.claim(k.x(s, 4), k.z(s, 4)) == null && OlympusStructures.claim(k.x(s, -4), k.z(s, -4)) == null }) {
            rock(k, s0, 0, 4.5)
        }
    }

    /** Ein Block vor der Stirnseite (Gesims), nur wo daneben schon der Torkörper steht. */
    private fun putFace(k: Ctx, s: Int, w: Int, y: Int, face: Direction, state: BlockState) {
        val x = k.x(s, w) + face.offsetX
        val z = k.z(s, w) + face.offsetZ
        if (k.c.inside(x, z) && k.owns(x, z)) k.c.put(x, y, z, state)
    }

    /** Flügel des Flügeltors (Weg der Boten): drei Federn, die nach außen ansteigen, mit goldenen Spitzen. */
    private fun wing(k: Ctx, s: Int, side: Int, top: Int, st: Style) {
        val out = k.outward(s, side)
        for (y in top + 2..top + 6) k.pal(s, side * 5, y, st.wall)
        k.putY(s, side * 5, top + 1, Kit.stair(st.stair, out.opposite, top = true))
        for (y in top + 4..top + 7) k.pal(s, side * 6, y, st.wall)
        k.putY(s, side * 6, top + 3, Kit.stair(st.stair, out.opposite, top = true))
        k.putY(s, side * 7, top + 6, st.wall(k.x(s, side * 7), top + 6, k.z(s, side * 7)))
        k.putY(s, side * 7, top + 7, st.accent)
        k.putY(s, side * 7, top + 5, Kit.stair(st.stair, out.opposite, top = true))
        k.putY(s, side * 6, top + 8, Kit.stair(st.stair, out.opposite))
    }

    /** Torrahmen an der Siegellücke: steht auch, wenn die Lücke wieder geschlossen ist (PLAN 5.1). */
    private fun sealFrame(k: Ctx, s0: Int) = gate(k, s0, style(k.b, s0), GateKind.SEAL)

    /**
     * Engstelle: [NARROW_LENGTH] Zellen, massive Wangen auf den Querlagen ±2…3 bis +3, Deckplatte +4, an den Enden ein
     * Pfosten mit Licht. [roofed] = Torhaus (Heerstraße): Wangen bis +5, Dach +6, Zinnen +7, Ecktürmchen bis +9.
     */
    private fun narrow(k: Ctx, n0: Int, st: Style, roofed: Boolean = false) {
        val last = n0 + NARROW_LENGTH - 1
        val wallTop = if (roofed) 5 else 3
        for (s in n0..last) {
            val end = s == n0 || s == last
            for (side in SIDES) for (a in 2..3) {
                val w = side * a
                if (a == 3) k.pal(s, w, k.y(s), st.wall)
                k.put(s, w, 1, st.plinth)
                for (h in 2..wallTop) k.put(s, w, h, if (a == 2 && h == 2 && s % 2 == 0) st.band else st.wall(k.x(s, w), k.y(s) + h, k.z(s, w)))
                if (!roofed) {
                    if (end && a == 3) {
                        k.put(s, w, 4, st.shaft)
                        k.put(s, w, 5, st.shaft)
                        k.put(s, w, 6, Kit.lantern(hanging = false, soul = st.lamp.isOf(Blocks.SOUL_LANTERN)))
                    } else k.put(s, w, 4, Kit.slab(st.slab))
                }
            }
            if (roofed) {
                for (w in -3..3) {
                    k.put(s, w, 6, st.wall(k.x(s, w), k.y(s) + 6, k.z(s, w)))
                    if (abs(w) == 3 && (s + w) % 2 == 0) k.put(s, w, 7, st.wall(k.x(s, w), k.y(s) + 7, k.z(s, w)))
                }
                if (s == n0 + 1 || s == last - 1) k.put(s, 0, 5, st.lamp)
                if (end) for (w in -3..3) putFace(k, s, w, k.y(s) + 6, if (s == n0) k.forward(s).opposite else k.forward(s), Kit.stair(st.stair, if (s == n0) k.forward(s) else k.forward(s).opposite, top = true))
            }
        }
        if (roofed) for (s in intArrayOf(n0, last)) for (side in SIDES) {
            for (h in 7..8) k.put(s, side * 3, h, st.shaft)
            k.put(s, side * 3, 9, st.accent)
        }
    }

    enum class Hang { CHIMES, SOUL, SLAG }

    /**
     * Leichter Bogen über der Fahrbahn: Pfosten auf den Geländern (±3), Bogenansatz +5, Balken +6, Abdeckung +7 mit
     * Schlussstein. Darunter hängt je nach Thema ein Windspiel (Glocke und Laternen), eine Seelenlaterne oder, beim
     * Schlackenweg, Ketten zu Kübeln mit glühender Schlacke auf Konsolen außen.
     */
    private fun arch(k: Ctx, s: Int, st: Style, hang: Hang) {
        val reach = if (hang == Hang.SLAG) 4 else 3
        for (side in SIDES) {
            val w = side * 3
            k.pal(s, w, k.y(s), st.wall)
            k.pal(s, w, k.y(s) - 1, st.wall)
            k.put(s, w, -2, Kit.stair(st.stair, k.outward(s, -side), top = true))
            k.put(s, w, 1, st.plinth)
            for (h in 2..5) k.put(s, w, h, st.shaft)
            k.put(s, side * 2, 5, Kit.stair(st.stair, k.outward(s, side), top = true))
        }
        for (w in -reach..reach) {
            k.put(s, w, 6, if (abs(w) <= 2) st.band else st.wall(k.x(s, w), k.y(s) + 6, k.z(s, w)))
            k.put(s, w, 7, if (w == 0) st.accent else Kit.slab(st.slab))
        }
        when (hang) {
            Hang.CHIMES -> {
                k.put(s, 0, 5, s(Blocks.BELL).with(BellBlock.ATTACHMENT, Attachment.CEILING).with(BellBlock.FACING, k.outward(s, 1)))
                for (side in SIDES) {
                    k.put(s, side, 5, Kit.chain())
                    k.put(s, side, 4, Kit.lantern(hanging = true, soul = false))
                }
            }
            Hang.SOUL -> k.put(s, 0, 5, Kit.lantern(hanging = true, soul = true))
            Hang.SLAG -> {
                k.put(s, 0, 5, Kit.lantern(hanging = true, soul = false))
                for (side in SIDES) {
                    val w = side * 4
                    k.put(s, w, 0, s(HhaDecoBlocks.OLYMPUS_BRONZE))
                    k.put(s, w, -1, Kit.stair(HhaDecoBlocks.OLYMPUS_BRONZE_STAIRS, k.outward(s, -side), top = true))
                    k.put(s, w, 1, s(Blocks.LAVA_CAULDRON))
                    for (h in 2..5) k.put(s, w, h, Kit.chain())
                    // Schlackehaufen neben dem Kübel.
                    for (f in intArrayOf(-1, 1)) {
                        val ss = s + f
                        k.put(ss, w, 0, Kit.slab(HhaDecoBlocks.OLYMPUS_BRONZE_SLAB, top = true))
                        k.put(ss, w, 1, if (f < 0) s(Blocks.MAGMA_BLOCK) else s(Blocks.BLACKSTONE))
                    }
                }
            }
        }
    }

    /** Wachturm 5 breit: hohl, Eckpfeiler, Bänder, vergitterte Fenster, Zinnen +12/+13, Feuermast bis [TOWER_HEIGHT]. */
    private fun tower(k: Ctx, cells: IntRange, side: Int, st: Style) {
        // Grundriss aus den Zellen: Querlage 4 … 8 auf Seite [side].
        val xs = ArrayList<Int>()
        val zs = ArrayList<Int>()
        for (s in cells) for (a in 4..8) {
            xs.add(k.x(s, side * a))
            zs.add(k.z(s, side * a))
        }
        val base = cells.minOf { k.y(it) }
        rock(k, cells.first + cells.count() / 2, side * 6, 4.5)
        towerAt(k, xs.min(), zs.min(), xs.max(), zs.max(), base, st)
        // Laterne an einer Konsole über dem Geländer, zur Fahrbahn hin.
        val mid = cells.first + cells.count() / 2
        k.putY(mid, side * 3, base + 5, Kit.stair(st.stair, k.outward(mid, side), top = true))
        k.putY(mid, side * 3, base + 4, Kit.lantern(hanging = true, soul = false))
    }

    /** Turmkörper im Kasten x0…x1, z0…z1 auf dem Bodenblock [base] (absolute Koordinaten). */
    private fun towerAt(k: Ctx, x0: Int, z0: Int, x1: Int, z1: Int, base: Int, st: Style) {
        val c = k.c
        fun put(x: Int, y: Int, z: Int, state: BlockState) {
            if (c.inside(x, z) && k.owns(x, z)) c.put(x, y, z, state)
        }
        val mx = (x0 + x1) / 2
        val mz = (z0 + z1) / 2
        for (x in x0..x1) for (z in z0..z1) {
            val ex = x == x0 || x == x1
            val ez = z == z0 || z == z1
            val edge = ex || ez
            val cornerCol = ex && ez
            // Fundament bis sechs unter den Boden, unten eingezogen.
            for (y in base - 6..base) put(x, y, z, st.wall(x, y, z))
            if (edge) put(x, base - 7, z, Kit.stair(st.stair, Kit.inward(x * 2 - x0 - x1, z * 2 - z0 - z1), top = true))
            for (h in 1..11) {
                val y = base + h
                val window = edge && !cornerCol && (if (ex) z == mz else x == mx) && (h in 4..5 || h == 9)
                val state = when {
                    !edge && h < 11 -> Kit.AIR
                    window -> Kit.bars(alongX = ez)
                    h == 1 || h == 7 -> st.band
                    cornerCol -> st.shaft
                    else -> st.wall(x, y, z)
                }
                put(x, y, z, state)
            }
            // Wehrgang: Gesims +11 als Platte, Brüstung +12, Zinnen +13 im Wechsel.
            if (edge) {
                put(x, base + 12, z, st.wall(x, base + 12, z))
                if (cornerCol || (x + z) % 2 == 0) put(x, base + 13, z, st.wall(x, base + 13, z))
            } else for (h in 12..13) put(x, base + h, z, Kit.AIR)
        }
        put(mx, base + 12, mz, st.shaft)
        put(mx, base + 13, mz, st.accent)
        put(mx, base + TOWER_HEIGHT, mz, s(Blocks.CAMPFIRE))
    }

    /** Herme auf dem Geländer (Hermen-Allee), Blick zur Fahrbahn. */
    private fun hermOnRail(k: Ctx, s: Int, side: Int) {
        val w = side * 3
        OlympKit.herm(k.cc, k.x(s, w), k.y(s) + 1, k.z(s, w), k.outward(s, -side))
    }

    /**
     * Statue auf einer Kanzel neben der Fahrbahn: Plattform 3 × 3 auf Querlage 4…6 über drei Zellen ab [s0], auf
     * eigenem Fels; [raise] Lagen Sockel unter der Figur (die Götter werden zum Thron hin größer).
     */
    private fun statue(k: Ctx, s0: Int, side: Int, st: Style, attr: OlympKit.Attr, raise: Int) {
        val mid = s0 + 1
        rock(k, mid, side * 5, 4.0)
        for (s in s0..s0 + 2) for (a in 4..6) {
            val w = side * a
            k.pal(s, w, k.y(s), st.wall)
            k.pal(s, w, k.y(s) - 1, st.wall)
            k.put(s, w, -2, if (a == 6) Kit.stair(st.stair, k.outward(s, -side), top = true) else st.wall(k.x(s, w), k.y(s) - 2, k.z(s, w)))
            for (h in 1..raise) k.put(s, w, h, if (h == raise) st.band else st.wall(k.x(s, w), k.y(s) + h, k.z(s, w)))
        }
        OlympKit.figure(k.cc, k.x(mid, side * 5), k.y(mid) + 1 + raise, k.z(mid, side * 5), k.outward(mid, -side), attr)
    }

    /** Wolkenfetzen aus Sturmwolke neben der Brücke (weich, ohne Kollision). */
    private fun stormPuff(k: Ctx, s: Int, side: Int, h0: Int) {
        for (ds in -2..2) for (a in 6..9) for (h in h0..h0 + 2) {
            val ss = (s + ds).coerceIn(0, k.b.length - 1)
            val x = k.x(ss, side * a)
            val y = k.y(ss) + h
            val z = k.z(ss, side * a)
            val r = abs(ds) / 2.5 + abs(a - 7.5) / 2.0 + abs(h - h0 - 1) / 1.5
            if (r < 1.6 + (Kit.noise(x, y, z) % 100) / 200.0) k.soft(ss, side * a, h, s(HhaDecoBlocks.STORM_CLOUD))
        }
    }

    // --- Themen ------------------------------------------------------------------------------------

    /** A → B, Weg der Boten: Hermen-Allee alle 12, Windbögen mit Glocke, Aussicht mit Botenstatue, Flügeltor an B. */
    private fun dressAB(k: Ctx) {
        val b = k.b
        val l = b.layout
        val st = marbleStyle()
        gate(k, l.startGate, st, GateKind.PLAIN)
        gate(k, l.headGate, st, GateKind.WINGS)
        narrow(k, l.narrow, st)
        val busy = l.events.map { it.range } + listOf(l.narrow until l.narrow + NARROW_LENGTH)
        for (s in 6 until b.length step 12) {
            if (!b.flat(s) || busy.any { s in it.first - 1..it.last + 1 }) continue
            for (side in SIDES) hermOnRail(k, s, side)
        }
        for (e in l.events) when (e.kind) {
            "wind_arch" -> arch(k, e.s, st, Hang.CHIMES)
            "belvedere" -> belvedere(k, e.s, e.side, st)
        }
    }

    /**
     * Aussicht auf dem Pfeilerfels: Plattform Querlage 4…7 über fünf Zellen, Brüstung auf 8, in der Mitte die Statue
     * des Boten mit Heroldstab; das Geländer öffnet sich zur Plattform.
     */
    private fun belvedere(k: Ctx, s0: Int, side: Int, st: Style) {
        val mid = s0 + 3
        rock(k, mid, side * 6, 5.0)
        for (s in s0..s0 + 6) for (a in 3..8) {
            val w = side * a
            val ring = s == s0 || s == s0 + 6 || a == 8
            if (a == 3) {
                if (s in s0 + 1..s0 + 5) {
                    k.put(s, w, 0, s(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE))
                    for (h in 1..3) k.put(s, w, h, Kit.AIR)
                }
                continue
            }
            k.put(s, w, 0, if (ring) st.plinth else if (a == 4) s(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE) else s(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE))
            k.pal(s, w, k.y(s) - 1, st.wall)
            k.put(s, w, -2, if (ring) Kit.stair(st.stair, if (a == 8) k.outward(s, -side) else if (s == s0) k.forward(s) else k.forward(s).opposite, top = true) else st.wall(k.x(s, w), k.y(s) - 2, k.z(s, w)))
            for (h in 1..4) k.put(s, w, h, Kit.AIR)
            if (ring) {
                val corner = (s == s0 || s == s0 + 6) && a == 8
                if (corner) {
                    k.put(s, w, 1, post(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_WALL))
                    k.put(s, w, 2, Kit.lantern(hanging = false, soul = false))
                } else k.put(s, w, 1, wallState(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_WALL, if (a == 8) k.forward(s) else k.forward(s).rotateYClockwise()))
            }
        }
        statue(k, mid - 1, side, st, OlympKit.Attr.SCEPTER, 0)
    }

    /** B → C, Weg der Schwelle: Marmor wird in drei Bändern Basalt, drei Bögen der Trivia, Hunde am Kopf von C. */
    private fun dressBC(k: Ctx) {
        val b = k.b
        val l = b.layout
        gate(k, l.startGate, marbleStyle(), GateKind.PLAIN)
        gate(k, l.headGate, basaltStyle(), GateKind.SEAL)
        narrow(k, l.narrow, style(b, l.narrow))
        for (e in l.events) if (e.kind == "trivia_arch") arch(k, e.s, style(b, e.s), Hang.SOUL)
        // Hundestatuen der Hekate zu beiden Seiten hinter dem Kopftor, im Inselrand von C.
        for (side in SIDES) hound(k, l.headGate + GATE_DEPTH, side)
    }

    /** Sitzender Hund aus Schwarzstein mit glühendem Halsband, Blick zur Brücke (entgegen der Laufrichtung). */
    private fun hound(k: Ctx, s: Int, side: Int) {
        if (s + 1 >= k.b.length) return
        val w = side * 5
        val back = k.forward(s)
        for (ss in s..s + 1) {
            k.put(ss, w, -1, s(HhaDecoBlocks.TARTARUS_BASALT))
            k.put(ss, w, 0, s(HhaDecoBlocks.TARTARUS_BASALT))
            k.put(ss, w, 1, s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT))
        }
        k.put(s, w, 2, post(Blocks.POLISHED_BLACKSTONE_WALL))
        k.put(s + 1, w, 2, s(Blocks.POLISHED_BLACKSTONE))
        k.put(s, w, 3, s(HhaDecoBlocks.SOUL_VEIN_BASALT))
        k.put(s + 1, w, 3, Kit.stair(Blocks.POLISHED_BLACKSTONE_STAIRS, back.opposite))
        k.put(s, w, 4, s(Blocks.POLISHED_BLACKSTONE_BRICKS))
        k.put(s - 1, w, 4, Kit.stair(Blocks.POLISHED_BLACKSTONE_STAIRS, back))
        k.put(s, w, 5, post(Blocks.POLISHED_BLACKSTONE_WALL))
    }

    /** C → D, Schlackenweg: rußiger Marmor, Feuerschalen, Kettenkräne mit Schlacke, Wachturm am Knick, Glutrisse vor D. */
    private fun dressCD(k: Ctx) {
        val b = k.b
        val l = b.layout
        val st = forgeStyle()
        gate(k, l.startGate, st, GateKind.PLAIN)
        gate(k, l.headGate, st, GateKind.PLAIN)
        narrow(k, l.narrow, st)
        // Feuerschalen: auf jedem Lampenpfosten eine Bronzeschale mit Glut statt der Laterne.
        for (s in 0 until b.length) {
            if (s % 8 != 0 || b.nearCorner(s, RAIL) || (b.gap != null && s in b.gap)) continue
            if (l.events.any { s in it.range } || s in l.narrow until l.narrow + NARROW_LENGTH) continue
            for (side in SIDES) {
                k.put(s, side * 3, 2, s(HhaDecoBlocks.OLYMPUS_BRONZE))
                k.put(s, side * 3, 3, s(Blocks.CAMPFIRE))
            }
        }
        for (e in l.events) when (e.kind) {
            "slag_gantry" -> arch(k, e.s, st, Hang.SLAG)
            "corner_tower" -> cornerTower(k, e.s, st)
        }
    }

    /** Wachturm an der Außenecke des Knick-Absatzes, auf dem Fels des Absatzes. */
    private fun cornerTower(k: Ctx, cs: Int, st: Style) {
        val b = k.b
        val cell = b.cells[cs]
        val inDir = b.cells[cs - 1].dir
        val outDir = b.cells[cs + 1].dir
        val ox = inDir.offsetX - outDir.offsetX
        val oz = inDir.offsetZ - outDir.offsetZ
        val xa = cell.x + ox * 3
        val xb = cell.x + ox * 7
        val za = cell.z + oz * 3
        val zb = cell.z + oz * 7
        towerAt(k, minOf(xa, xb), minOf(za, zb), maxOf(xa, xb), maxOf(za, zb), b.level[cs], st)
    }

    /** D → E, Heerstraße: roter Läufer, Standarten, Tropaia, zwei Wachtürme, Torhaus als Engstelle. */
    private fun dressDE(k: Ctx) {
        val b = k.b
        val l = b.layout
        val st = warStyle()
        gate(k, l.startGate, st, GateKind.BANNERS)
        gate(k, l.headGate, st, GateKind.BANNERS)
        narrow(k, l.narrow, st, roofed = true)
        val busy = l.events.map { it.range } + listOf(l.narrow - 1 until l.narrow + NARROW_LENGTH + 1)
        for (s in 8 until b.length step 16) {
            if (!b.flat(s) || busy.any { s in it }) continue
            for (side in SIDES) standard(k, s, side)
        }
        for (e in l.events) when (e.kind) {
            "tower" -> tower(k, e.range, e.side, st)
            "tropaia" -> for (s in intArrayOf(e.s, e.s + e.length - 1)) for (side in SIDES) tropaion(k, s, side)
        }
    }

    /** Standarte auf dem Geländer: Säulenfuß, Schaft, Bronzekopf mit Laterne, rotes Banner zur Fahrbahn. */
    private fun standard(k: Ctx, s: Int, side: Int) {
        val w = side * 3
        k.put(s, w, 1, s(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR))
        for (h in 2..4) k.put(s, w, h, post(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_WALL))
        k.put(s, w, 5, s(HhaDecoBlocks.OLYMPUS_BRONZE))
        k.put(s, w, 6, Kit.lantern(hanging = false, soul = false))
        k.put(s, side * 2, 4, s(Blocks.RED_WALL_BANNER).with(Properties.HORIZONTAL_FACING, k.outward(s, -side)))
    }

    /** Tropaion: erbeutete Rüstung auf einem Pfahl (Panzer, Schultern, Helm), außen ein rotes Banner. */
    private fun tropaion(k: Ctx, s: Int, side: Int) {
        val w = side * 3
        k.put(s, w, 1, s(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE))
        k.put(s, w, 2, s(HhaDecoBlocks.OLYMPUS_BRONZE))
        k.put(s, w, 3, s(HhaDecoBlocks.OLYMPUS_BRONZE))
        for (f in intArrayOf(-1, 1)) k.put(s + f, w, 3, Kit.stair(HhaDecoBlocks.OLYMPUS_BRONZE_STAIRS, if (f < 0) k.forward(s) else k.forward(s).opposite, top = true))
        k.put(s, w, 4, s(Blocks.WAXED_CHISELED_COPPER))
        k.put(s, w, 5, Kit.slab(Blocks.WAXED_CUT_COPPER_SLAB))
        k.put(s, side * 4, 3, s(Blocks.RED_WALL_BANNER).with(Properties.HORIZONTAL_FACING, k.outward(s, side)))
    }

    /** E → F, Himmelstreppe: Sturmmarmor, Blitzableiter, wachsende Götterstatuen, Wolkenfetzen, Tor der Blitze. */
    private fun dressEF(k: Ctx) {
        val b = k.b
        val l = b.layout
        val st = stormStyle()
        gate(k, l.startGate, st, GateKind.PLAIN)
        gate(k, l.headGate, st, GateKind.BOLTS)
        narrow(k, l.narrow, st)
        val attrs = listOf(OlympKit.Attr.BOWL, OlympKit.Attr.SPEAR, OlympKit.Attr.SCEPTER)
        for ((i, e) in l.events.filter { it.kind == "god_statue" }.withIndex()) {
            statue(k, e.s, e.side, st, attrs[i], i * 2)
            stormPuff(k, e.s + 1, -e.side, -3 + i)
        }
        for (p in b.piers) stormPuff(k, p, if (p % 2 == 0) 1 else -1, -4)
    }
}

/** Leinwand, die nur Spalten durchlässt, für die [allow] gilt (für Felsen und Wolken einer Brücke). */
internal class ClippedCanvas(private val inner: SkyCanvas, private val allow: (Int, Int) -> Boolean) : SkyCanvas() {
    override val minX get() = inner.minX
    override val maxX get() = inner.maxX
    override val minZ get() = inner.minZ
    override val maxZ get() = inner.maxZ
    override fun put(x: Int, y: Int, z: Int, state: BlockState) {
        if (allow(x, z)) inner.put(x, y, z, state)
    }
    override fun isFree(x: Int, y: Int, z: Int): Boolean = allow(x, z) && inner.isFree(x, y, z)
    override fun ground(x: Int, z: Int): Int = inner.ground(x, z)
    override fun chest(x: Int, y: Int, z: Int) = inner.chest(x, y, z)
    override fun spawner(x: Int, y: Int, z: Int, type: net.minecraft.entity.EntityType<*>) = inner.spawner(x, y, z, type)
}
