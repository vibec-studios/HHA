package dev.henny.hha.world.olymp

import dev.henny.hha.world.Kit
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.WallBlock
import net.minecraft.block.enums.WallShape
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Die fünf Brücken des Pfads (PLAN 8.2): 5 Blöcke breit, Quarzplatten zwischen Geländern, darunter ein Kiel, der sich zu
 * den Pfeilern hin zu Bögen vertieft, alle 32 Blöcke ein kleiner Pfeilerfels mit Wolkenfetzen. Die Brücken laufen
 * achsparallel; wo sie abknicken, liegt ein Absatz mit Herme. Steigung nur über Treppenläufe von vier Stufen mit
 * Absätzen dazwischen. In vier Brücken fehlen die mittleren [OlympusLayout.SEAL_GAP] Blöcke, bis der Boss davor besiegt
 * ist ([dev.henny.hha.logic.world.OlympusPath] setzt sie aus demselben Bauplan nach).
 */
object OlympusBridges {

    /** Welche Spalten gezeichnet werden: alles, nur in Inseln (Insel-Piece), nur außerhalb (Brücken-Piece), nur die Lücke. */
    enum class Part { ALL, INSIDE, OUTSIDE, GAP }

    private const val FLIGHT = 4
    private const val HALF = 2
    private const val RAIL = 3
    private const val CORNER_ZONE = 4
    private const val PIER_SPACING = OlympusLayout.BRIDGE_PIER_SPACING

    /** Eine Zelle der Mittellinie: Lauflänge [s], Lage, Laufrichtung; [corner] = Knick. */
    class Cell(val s: Int, val x: Int, val z: Int, val dir: Direction, val corner: Boolean)

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

        private fun nearCorner(s: Int, margin: Int) = cornerS.any { abs(it - s) <= margin }

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

        val box: BlockBox = run {
            val minX = cells.minOf { it.x } - 12
            val maxX = cells.maxOf { it.x } + 12
            val minZ = cells.minOf { it.z } - 12
            val maxZ = cells.maxOf { it.z } + 12
            BlockBox(minX, minOf(y0, y1) - 22, minZ, maxX, maxOf(y0, y1) + 6, maxZ)
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
    // Zeichnen
    // ---------------------------------------------------------------------------------------------

    private fun s(b: Block): BlockState = b.defaultState

    private fun rail(along: Direction): BlockState {
        val alongX = along.axis == Direction.Axis.X
        return Blocks.DIORITE_WALL.defaultState
            .with(WallBlock.EAST_WALL_SHAPE, if (alongX) WallShape.LOW else WallShape.NONE)
            .with(WallBlock.WEST_WALL_SHAPE, if (alongX) WallShape.LOW else WallShape.NONE)
            .with(WallBlock.NORTH_WALL_SHAPE, if (alongX) WallShape.NONE else WallShape.LOW)
            .with(WallBlock.SOUTH_WALL_SHAPE, if (alongX) WallShape.NONE else WallShape.LOW)
            .with(WallBlock.UP, false)
    }

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
        fun soft(x: Int, y: Int, z: Int, state: BlockState) {
            if (c.inside(x, z) && owns(x, z)) c.soft(x, y, z, state)
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
            val y = b.level[cell.s]
            val step = b.steps[cell.s]
            val (px, pz) = perp(cell.dir)
            val barrier = sealed && gap != null && (cell.s == gap.first - 1 || cell.s == gap.last + 1)
            val keel = keelDepth(b, cell.s)
            val marker = !sealed && gap != null && cell.s == gap.first + OlympusLayout.SEAL_GAP / 2
            for (w in -RAIL..RAIL) {
                val x = cell.x + px * w
                val z = cell.z + pz * w
                val aw = abs(w)
                val outward = if (w > 0) dirOf(px, pz) else dirOf(-px, -pz)
                when {
                    aw <= HALF -> {
                        // Lauffläche.
                        if (step) {
                            put(x, y, z, Kit.stair(Blocks.SMOOTH_QUARTZ_STAIRS, cell.dir))
                            put(x, y - 1, z, s(Blocks.SMOOTH_QUARTZ))
                        } else {
                            put(x, y, z, when {
                                aw == HALF -> s(Blocks.QUARTZ_BRICKS)
                                w == 0 && cell.s % 8 == 4 -> s(Blocks.CHISELED_QUARTZ_BLOCK)
                                w == 0 -> s(Blocks.POLISHED_DIORITE)
                                else -> s(Blocks.SMOOTH_QUARTZ)
                            })
                        }
                        for (h in 1..3) put(x, y + h, z, Kit.AIR)
                        if (barrier) put(x, y + 1, z, if (w == 0) s(Blocks.CHISELED_QUARTZ_BLOCK) else rail(cell.dir.rotateYClockwise()))
                        // Unterseite: Kiel in der Mitte, an den Rändern eine Schräge.
                        if (aw == HALF) put(x, y - 1, z, Kit.stair(Blocks.SMOOTH_QUARTZ_STAIRS, outward.opposite, top = true))
                        else {
                            put(x, y - 1, z, if (marker && w == 0) s(Blocks.CHISELED_QUARTZ_BLOCK) else s(Blocks.QUARTZ_BRICKS))
                            if (aw == 0) for (k in 2..keel) put(x, y - k, z, if (k == keel) Kit.stair(Blocks.QUARTZ_STAIRS, cell.dir, top = true) else s(Blocks.QUARTZ_BRICKS))
                            else for (k in 2 until keel) put(x, y - k, z, if (k == keel - 1) Kit.stair(Blocks.SMOOTH_QUARTZ_STAIRS, outward.opposite, top = true) else s(Blocks.SMOOTH_QUARTZ))
                        }
                    }
                    else -> {
                        // Geländer: Sockel, Brüstung, alle vier Zellen ein Pfosten.
                        val post = cell.s % 4 == 0 || barrier
                        put(x, y, z, if (post) s(Blocks.CHISELED_QUARTZ_BLOCK) else s(Blocks.QUARTZ_BRICKS))
                        if (step) put(x, y - 1, z, s(Blocks.QUARTZ_BRICKS))
                        put(x, y - 1 - (if (step) 1 else 0), z, Kit.stair(Blocks.QUARTZ_STAIRS, outward.opposite, top = true))
                        if (post) {
                            put(x, y + 1, z, s(Blocks.QUARTZ_PILLAR))
                            val lamp = cell.s in b.piers || cell.s == 2 || cell.s == b.length - 3
                            put(x, y + 2, z, if (lamp) Kit.lantern(hanging = false, soul = false) else Kit.slab(Blocks.SMOOTH_QUARTZ_SLAB))
                        } else {
                            put(x, y + 1, z, rail(cell.dir))
                            put(x, y + 2, z, Kit.AIR)
                        }
                    }
                }
            }
            if (cell.s in b.piers) pier(c, b, cell, ::put, ::owns)
        }
        if (sealed && gap != null && part != Part.GAP) brokenEnds(c, b, gap, ::put)
    }

    private fun dirOf(dx: Int, dz: Int): Direction = when {
        dx > 0 -> Direction.EAST
        dx < 0 -> Direction.WEST
        dz > 0 -> Direction.SOUTH
        else -> Direction.NORTH
    }

    /** Wie tief der Kiel reicht: an Pfeilern und Inseln tief (Bogenansatz), in der Spannweite flach. */
    private fun keelDepth(b: Bridge, s: Int): Int {
        val supports = b.piers + b.cornerS + listOf(firstFree(b), lastFree(b))
        val u = supports.minOf { abs(it - s) }.toDouble()
        val t = (1.0 - (u / 14.0)).coerceIn(0.0, 1.0)
        return 2 + (7.0 * t * t).roundToInt()
    }

    private fun firstFree(b: Bridge): Int = b.cells.indexOfFirst { OlympusStructures.claim(it.x, it.z) == null }.coerceAtLeast(0)

    private fun lastFree(b: Bridge): Int = b.cells.indexOfLast { OlympusStructures.claim(it.x, it.z) == null }.coerceAtLeast(0)

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
                val post = abs(i) == RAIL && abs(j) == RAIL || i == 0 || j == 0
                if (post) {
                    put(x, y + 1, z, s(Blocks.QUARTZ_PILLAR))
                    put(x, y + 2, z, Kit.slab(Blocks.SMOOTH_QUARTZ_SLAB))
                } else put(x, y + 1, z, rail(if (abs(i) == RAIL) Direction.SOUTH else Direction.EAST))
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
