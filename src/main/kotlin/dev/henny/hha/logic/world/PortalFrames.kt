package dev.henny.hha.logic.world

import dev.henny.hha.HhaBlocks
import dev.henny.hha.block.OlympusPortalBlock
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.world.BlockView
import net.minecraft.world.WorldAccess

/**
 * Rahmenerkennung des Wolkentors (PLAN 7.1): ein stehender Rahmen aus `minecraft:glowstone`, innen 2–9 breit und
 * 3–9 hoch, Ecken dürfen fehlen, beide Achsen. Innen darf nur Luft oder schon Wolkentor stehen.
 *
 * Die eigentliche Suche ([FrameScan]) ist eine reine Funktion über ein Raster aus Zellen, damit sie ohne Welt prüfbar
 * ist ([PortalFramesCheck]); [PortalFrames] bildet die Welt darauf ab.
 */
object PortalFrames {

    const val MIN_WIDTH = 2
    const val MAX_WIDTH = 9
    const val MIN_HEIGHT = 3
    const val MAX_HEIGHT = 9

    /** Ein erkannter Rahmen: [corner] = untere Ecke der Öffnung (kleinstes x bzw. z, unterste Reihe). */
    data class Frame(val axis: Direction.Axis, val corner: BlockPos, val width: Int, val height: Int) {

        /** Richtung entlang der Öffnung (x bei Achse X, z bei Achse Z). */
        val along: Direction get() = if (axis == Direction.Axis.X) Direction.EAST else Direction.SOUTH

        /** Alle Zellen der Öffnung. */
        fun cells(): List<BlockPos> = buildList {
            for (h in 0 until height) for (w in 0 until width) add(corner.offset(along, w).up(h))
        }

        /** Mitte der Öffnung (Weltkoordinaten, y = Mitte der Höhe). */
        fun centerX(): Double = corner.x + (if (axis == Direction.Axis.X) width / 2.0 else 0.5)
        fun centerZ(): Double = corner.z + (if (axis == Direction.Axis.Z) width / 2.0 else 0.5)
    }

    private fun cellOf(state: BlockState, axis: Direction.Axis): FrameScan.Cell = when {
        state.isOf(Blocks.GLOWSTONE) -> FrameScan.Cell.FRAME
        state.isAir -> FrameScan.Cell.INNER
        state.isOf(HhaBlocks.OLYMPUS_PORTAL) && state.get(OlympusPortalBlock.AXIS) == axis -> FrameScan.Cell.INNER
        else -> FrameScan.Cell.OTHER
    }

    /** Sucht einen gültigen Rahmen mit der Achse [axis], in dessen Öffnung [inside] liegt. */
    fun find(world: BlockView, inside: BlockPos, axis: Direction.Axis): Frame? {
        val along = if (axis == Direction.Axis.X) Direction.EAST else Direction.SOUTH
        val rect = FrameScan.scan(0, 0) { a, h -> cellOf(world.getBlockState(inside.offset(along, a).up(h)), axis) } ?: return null
        return Frame(axis, inside.offset(along, rect.minA).up(rect.minH), rect.width, rect.height)
    }

    /** Wie [find], probiert beide Achsen (zuerst x). */
    fun find(world: BlockView, inside: BlockPos): Frame? =
        find(world, inside, Direction.Axis.X) ?: find(world, inside, Direction.Axis.Z)

    /** Füllt die Öffnung mit Wolkentor-Blöcken der richtigen Achse. */
    fun fill(world: WorldAccess, frame: Frame) {
        val state = HhaBlocks.OLYMPUS_PORTAL.defaultState.with(OlympusPortalBlock.AXIS, frame.axis)
        for (cell in frame.cells()) world.setBlockState(cell, state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
    }

    /**
     * Baut an [corner] (untere Ecke der Öffnung) einen Rahmen aus Glowstone mit einer [width] × [height]-Öffnung
     * samt Ecken, räumt die Öffnung frei und füllt sie. Für den Rückweg, wenn das alte Tor fehlt.
     */
    fun build(world: WorldAccess, corner: BlockPos, axis: Direction.Axis, width: Int = 2, height: Int = 3): Frame {
        val along = if (axis == Direction.Axis.X) Direction.EAST else Direction.SOUTH
        val frameState = Blocks.GLOWSTONE.defaultState
        for (a in -1..width) {
            for (h in -1..height) {
                val pos = corner.offset(along, a).up(h)
                val edge = a == -1 || a == width || h == -1 || h == height
                world.setBlockState(pos, if (edge) frameState else Blocks.AIR.defaultState, Block.NOTIFY_ALL)
            }
        }
        val frame = Frame(axis, corner, width, height)
        fill(world, frame)
        return frame
    }
}

/** Die reine Rahmensuche über einem Raster (a = entlang der Öffnung, h = Höhe), unabhängig von Minecraft. */
object FrameScan {

    enum class Cell { FRAME, INNER, OTHER }

    /** Öffnung relativ zum Startpunkt. */
    data class Rect(val minA: Int, val minH: Int, val width: Int, val height: Int)

    /**
     * Sucht von der Innenzelle ([startA], [startH]) aus einen vollständigen Rahmen. Ecken werden nicht geprüft.
     * Liefert die Öffnung oder `null`, wenn die Zelle nicht innen liegt, der Rahmen ein Loch hat, innen etwas steht
     * oder die Maße außerhalb von 2–9 × 3–9 liegen.
     */
    fun scan(startA: Int, startH: Int, cell: (a: Int, h: Int) -> Cell): Rect? {
        if (cell(startA, startH) != Cell.INNER) return null
        // Nach unten bis zum Boden des Rahmens.
        var bottom = startH
        while (startH - bottom < PortalFrames.MAX_HEIGHT && cell(startA, bottom - 1) == Cell.INNER) bottom--
        if (cell(startA, bottom - 1) != Cell.FRAME) return null
        // Auf der untersten Reihe nach links und rechts.
        var left = startA
        while (startA - left < PortalFrames.MAX_WIDTH && cell(left - 1, bottom) == Cell.INNER) left--
        if (cell(left - 1, bottom) != Cell.FRAME) return null
        var right = startA
        while (right - left + 1 <= PortalFrames.MAX_WIDTH && cell(right + 1, bottom) == Cell.INNER) right++
        if (cell(right + 1, bottom) != Cell.FRAME) return null
        val width = right - left + 1
        if (width !in PortalFrames.MIN_WIDTH..PortalFrames.MAX_WIDTH) return null
        // Höhe an der linken Spalte.
        var top = bottom
        while (top - bottom + 1 <= PortalFrames.MAX_HEIGHT && cell(left, top + 1) == Cell.INNER) top++
        if (cell(left, top + 1) != Cell.FRAME) return null
        val height = top - bottom + 1
        if (height !in PortalFrames.MIN_HEIGHT..PortalFrames.MAX_HEIGHT) return null
        // Ganzes Rechteck prüfen: innen nur Luft/Tor, rundum Rahmen.
        for (h in bottom..top) {
            for (a in left..right) if (cell(a, h) != Cell.INNER) return null
            if (cell(left - 1, h) != Cell.FRAME || cell(right + 1, h) != Cell.FRAME) return null
        }
        for (a in left..right) {
            if (cell(a, bottom - 1) != Cell.FRAME || cell(a, top + 1) != Cell.FRAME) return null
        }
        return Rect(left, bottom, width, height)
    }

    /** Hilfe für Selbsttests: Raster aus Zeichen (`G` Rahmen, `.` innen, sonst fremd), oberste Zeile zuerst. */
    fun ofRows(rows: List<String>): (Int, Int) -> Cell = { a, h ->
        val row = rows.size - 1 - h
        val ch = rows.getOrNull(row)?.getOrNull(a) ?: ' '
        when (ch) {
            'G' -> Cell.FRAME
            '.' -> Cell.INNER
            else -> Cell.OTHER
        }
    }
}

/**
 * Selbsttest der Rahmensuche (Abnahme P03), ohne Welt. P13 ruft [run] auf (z. B. aus `WorldgenVerify`); eine leere
 * Liste heißt bestanden, sonst steht je Fehlschlag eine Zeile darin.
 */
object PortalFramesCheck {

    private class Case(val name: String, val rows: List<String>, val start: Pair<Int, Int>, val expect: Pair<Int, Int>?)

    private fun rect(width: Int, height: Int, corners: Boolean = true, mutate: (Array<CharArray>) -> Unit = {}): List<String> {
        val grid = Array(height + 2) { r -> CharArray(width + 2) { c ->
            val edgeRow = r == 0 || r == height + 1
            val edgeCol = c == 0 || c == width + 1
            when {
                edgeRow && edgeCol -> if (corners) 'G' else ' '
                edgeRow || edgeCol -> 'G'
                else -> '.'
            }
        } }
        mutate(grid)
        return grid.map { String(it) }
    }

    private val CASES = listOf(
        Case("2x3 gültig", rect(2, 3), 1 to 1, 2 to 3),
        Case("9x9 gültig", rect(9, 9), 5 to 5, 9 to 9),
        Case("4x5 ohne Ecken gültig", rect(4, 5, corners = false), 2 to 3, 4 to 5),
        Case("Start oben rechts gültig", rect(3, 4), 3 to 4, 3 to 4),
        Case("1x3 ungültig", rect(1, 3), 1 to 1, null),
        Case("10x3 ungültig", rect(10, 3), 1 to 1, null),
        Case("2x2 ungültig", rect(2, 2), 1 to 1, null),
        Case("Loch im Rahmen ungültig", rect(3, 4) { it[0][2] = ' ' }, 1 to 1, null),
        Case("Loch in der Seite ungültig", rect(3, 4) { it[2][4] = ' ' }, 1 to 1, null),
        Case("Block im Inneren ungültig", rect(3, 4) { it[2][2] = 'S' }, 1 to 1, null),
        Case("Start auf dem Rahmen ungültig", rect(3, 4), 0 to 1, null),
    )

    fun run(): List<String> {
        val failures = ArrayList<String>()
        for (case in CASES) {
            val rect = FrameScan.scan(case.start.first, case.start.second, FrameScan.ofRows(case.rows))
            val got = rect?.let { it.width to it.height }
            if (got != case.expect) failures.add("${case.name}: erwartet ${case.expect}, bekommen $got")
        }
        return failures
    }
}
