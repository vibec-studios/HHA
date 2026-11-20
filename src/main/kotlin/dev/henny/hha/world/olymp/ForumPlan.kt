package dev.henny.hha.world.olymp

import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.block.ArenaDeviceBlock
import dev.henny.hha.logic.boss.HermesArena
import dev.henny.hha.world.BlockCanvas
import dev.henny.hha.world.arena.ArenaGeo
import dev.henny.hha.world.arena.ArenaSpec
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.HorizontalFacingBlock
import net.minecraft.block.SlabBlock
import net.minecraft.block.StairsBlock
import net.minecraft.block.WallBlock
import net.minecraft.block.enums.BlockHalf
import net.minecraft.block.enums.SlabType
import net.minecraft.block.enums.WallShape
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Bauplan des Himmelsforums (Überarbeitung 4, PLAN 3.1; Besitzer P14). Alle Maße kommen aus dem Vertrag
 * ([OlympusArenas.FORUM]); hier stehen nur Werkstoffe und Zier.
 *
 * Höhen relativ zur Mitte [center] (Bodenblock der Scheibe, `c`); die Füße stehen auf `c + 1` (Kernhöhe):
 *
 * | Höhe | Teil |
 * | --- | --- |
 * | c − 3 | Schlussstein der Schale (Blockringe ≤ 14, Mitte vergoldet) |
 * | c − 2 | Schale (Ringe ≤ 20) |
 * | c − 1 | Schale (Ringe ≤ 25) |
 * | c | Kernboden mit dem Belag der Stage (Ringe ≤ 21), unter der Empore Ziegel |
 * | c + 1 … c + 2 | Empore (Ringe 22 … 26): Innenwand mit Seelaternen, Lauffläche, goldene Kante auf Ring 22 |
 * | c + 3 | Brüstung (Ring 26), Mauerblöcke |
 * | Kern + 0 … + 7 | acht Botensäulen 3 × 3 auf Ring 20: Ziegelsockel, Band, Schaft, goldenes Kapitell, Krone mit Seelaterne |
 * | Kern + 0 / + 1 | vor jeder Säule (Ring 18) ein voller Sockelblock und darauf die Botenglocke |
 *
 * Der Kern trägt zwei Ringbänder aus gemeißeltem Marmor (Ring 8 und 14) und acht Speichen zu den Säulen; nur das
 * letzte Stück vor der Glocke ist golden (Gold liegt an Geräten, PLAN 1 Leitlinie 24). Die Empore besteht aus vier
 * Bögen (Teile `arc_0/90/180/270`), die getrennt abstürzen können; [lost] nennt die, die schon fehlen. Ein Bogen
 * zeichnet sich samt Schale, Brüstung und Laternen nur, solange er da ist. Ein Aufgang, dessen beide Bögen fehlen,
 * entfällt mit ihnen.
 */
object ForumPlan {

    /** Unterste Lage der Schale (relativ zu `c`) und die Blockringe der drei Schalenlagen. */
    const val SHELL_DEPTH = 3
    const val SHELL_RING_1 = 25
    const val SHELL_RING_2 = 20
    const val SHELL_RING_3 = 14

    /** Kernboden: Blockring der Kante (letzter Ring vor der Empore). */
    const val CORE_EDGE_RING = 21

    /** Ringbänder aus gemeißeltem Marmor. */
    const val BAND_RING_1 = 8
    const val BAND_RING_2 = 14

    /** Speichen: von diesem Ring bis vor die Glocke; die letzten [SPOKE_GOLD] Ringe golden. */
    const val SPOKE_FROM = 9
    const val SPOKE_GOLD = 2
    const val SPOKE_HALF_WIDTH = 0.8

    /** Medaillon in der Mitte (Blockringe ≤) und Lichtpunkte auf Ring 11 unter den Lückenwinkeln (0°, 45°, …). */
    const val MEDALLION_RING = 3
    const val LIGHT_RING = 11

    /** Innenwand der Empore: alle so viele Grad eine Seelaterne (versetzt zu den Säulen). */
    const val GALLERY_LIGHT_STEP = 15.0

    /** Kapitell-Höhe und Krone über dem Kern (die Deckung reicht bis + 6, die Krone sitzt auf + 7). */
    const val CAPITAL = 6
    const val CROWN = 7

    /** Kasten des Forums um [center]: Schale bis 3 darunter, Säulen samt Krone darüber. */
    fun box(center: BlockPos): BlockBox {
        val r = HermesArena.RADIUS + 2
        return BlockBox(center.x - r, center.y - SHELL_DEPTH, center.z - r, center.x + r, center.y + HermesArena.COLUMN_HEIGHT + 2, center.z + r)
    }

    /** Blockring der Spalte (dx, dz) um die Mitte (Ringmaß wie `ArenaGeo.ring`). */
    fun ringOf(dx: Int, dz: Int): Int = floor(hypot(dx.toDouble(), dz.toDouble()) + 0.5).toInt()

    fun draw(canvas: BlockCanvas, center: BlockPos, stage: Int, lost: Set<String> = emptySet()) {
        val spec = OlympusArenas.FORUM(center)
        shell(canvas, center, spec, lost)
        floor(canvas, center, stage, spec, lost)
        gallery(canvas, center, spec, lost)
        stairs(canvas, center, spec, lost)
        for (k in 0 until OlympusArenas.FORUM_COLUMNS) column(canvas, center, k)
        for (k in 0 until OlympusArenas.FORUM_COLUMNS) bell(canvas, center, spec, k)
    }

    /** Liegt die Spalte in einem verlorenen Bogen? (Zellen auf den Achsen gehören zu beiden Nachbarbögen.) */
    fun inLostArc(spec: ArenaSpec, lost: Set<String>, x: Int, z: Int): Boolean =
        lost.isNotEmpty() && spec.parts.any { it.id in lost && it.zone.containsCell(x, z) }

    // --- Schale ---------------------------------------------------------------------------------------------------

    /** Unterseite als Schale, die sich nach unten verjüngt; in der Mitte ein vergoldeter Schlussstein (vom Tempel aus sichtbar). */
    private fun shell(c: BlockCanvas, center: BlockPos, spec: ArenaSpec, lost: Set<String>) {
        val radius = HermesArena.RADIUS
        for (dx in -radius..radius) for (dz in -radius..radius) {
            val k = ringOf(dx, dz)
            if (k > SHELL_RING_1) continue
            val x = center.x + dx
            val z = center.z + dz
            if (k > CORE_EDGE_RING && inLostArc(spec, lost, x, z)) continue
            c.put(x, center.y - 1, z, Blocks.SMOOTH_QUARTZ.defaultState)
            if (k <= SHELL_RING_2) c.put(x, center.y - 2, z, Blocks.SMOOTH_QUARTZ.defaultState)
            if (k <= SHELL_RING_3) {
                val key = if (k <= 1) HhaDecoBlocks.GILDED_OLYMPUS_MARBLE.defaultState else Blocks.SMOOTH_QUARTZ.defaultState
                c.put(x, center.y - SHELL_DEPTH, z, key)
            }
        }
    }

    // --- Kernboden ------------------------------------------------------------------------------------------------

    /** Kernboden (Ringe ≤ 21) im Belag der Stage; unter der Empore (Ringe 22 … 26) Ziegel als deren Unterbau. */
    private fun floor(c: BlockCanvas, center: BlockPos, stage: Int, spec: ArenaSpec, lost: Set<String>) {
        val radius = HermesArena.RADIUS
        for (dx in -radius..radius) for (dz in -radius..radius) {
            val r = hypot(dx.toDouble(), dz.toDouble())
            if (r > radius + 0.5) continue
            val k = ringOf(dx, dz)
            val x = center.x + dx
            val z = center.z + dz
            if (k > CORE_EDGE_RING) {
                if (!inLostArc(spec, lost, x, z)) c.put(x, center.y, z, HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS.defaultState)
                continue
            }
            c.put(x, center.y, z, floorBlock(stage, dx, dz, r))
        }
    }

    /**
     * Kernboden einer Stage an der Stelle ([dx], [dz]) mit Abstand [r] zur Mitte (auch für die Umfärbe-Welle). Belag wie
     * vor Überarbeitung 4 je Stage (I/II Quarz, III Schwarzstein und Prismarin, IV heller Marmor), dazu die Ringbänder,
     * die Speichen zu den Säulen und das Medaillon.
     */
    fun floorBlock(stage: Int, dx: Int, dz: Int, r: Double): BlockState {
        val k = floor(r + 0.5).toInt()
        val angle = ArenaGeo.angleOf(dx.toDouble(), dz.toDouble())
        val checker = (floor(r / 2.0).toInt() + floor(angle / 22.5).toInt()) % 2 == 0
        val spoke = spokeAt(dx, dz, k)
        return when {
            k <= 0 -> when (stage) {
                3 -> Blocks.SEA_LANTERN
                4 -> Blocks.PEARLESCENT_FROGLIGHT
                else -> Blocks.SEA_LANTERN
            }.defaultState
            k <= MEDALLION_RING -> when (stage) {
                3 -> Blocks.DARK_PRISMARINE
                else -> HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE
            }.defaultState
            k == BAND_RING_1 || k == BAND_RING_2 -> HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE.defaultState
            spoke == SPOKE_GOLD_CELL -> HhaDecoBlocks.GILDED_OLYMPUS_MARBLE.defaultState
            spoke == SPOKE_CELL -> when (stage) {
                3 -> Blocks.PRISMARINE_BRICKS
                4 -> HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS
                else -> HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE
            }.defaultState
            k == LIGHT_RING && lightAt(angle) -> when (stage) {
                4 -> Blocks.PEARLESCENT_FROGLIGHT
                else -> Blocks.SEA_LANTERN
            }.defaultState
            k >= CORE_EDGE_RING -> when (stage) {
                3 -> Blocks.CRYING_OBSIDIAN
                4 -> HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE
                else -> Blocks.CHISELED_QUARTZ_BLOCK
            }.defaultState
            else -> when (stage) {
                3 -> if (checker) Blocks.POLISHED_BLACKSTONE else Blocks.BLACKSTONE
                4 -> if (checker) Blocks.SMOOTH_QUARTZ else HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE
                else -> if (checker) Blocks.SMOOTH_QUARTZ else Blocks.QUARTZ_BRICKS
            }.defaultState
        }
    }

    private const val SPOKE_NONE = 0
    private const val SPOKE_CELL = 1
    private const val SPOKE_GOLD_CELL = 2

    /** Liegt (dx, dz) auf einer Speiche zu einer Säule? Speichen laufen von [SPOKE_FROM] bis vor die Glocke (Ring 18). */
    private fun spokeAt(dx: Int, dz: Int, k: Int): Int {
        val last = OlympusArenas.FORUM_BELL_RING.toInt() - 1
        if (k < SPOKE_FROM || k > last) return SPOKE_NONE
        for (i in 0 until OlympusArenas.FORUM_COLUMNS) {
            val a = Math.toRadians(OlympusArenas.forumColumnAngle(i))
            val along = dx * kotlin.math.cos(a) + dz * kotlin.math.sin(a)
            val across = abs(-dx * kotlin.math.sin(a) + dz * kotlin.math.cos(a))
            if (along > 0 && across < SPOKE_HALF_WIDTH) return if (k > last - SPOKE_GOLD) SPOKE_GOLD_CELL else SPOKE_CELL
        }
        return SPOKE_NONE
    }

    /** Lichtpunkt unter den Lückenwinkeln (0°, 45°, …: zwischen den Säulen). */
    private fun lightAt(angle: Double): Boolean {
        val off = ((angle % 45.0) + 45.0) % 45.0
        return off < 2.5 || off > 42.5
    }

    // --- Empore ---------------------------------------------------------------------------------------------------

    /**
     * Empore: Blockringe 22 … 26, Lauffläche auf c + 2 (Füße Kern + 2). Innenwand Ring 22 mit Seelaternen, Kante Ring 22
     * golden, Brüstung Ring 26 auf c + 3. Je Spalte nur, wenn ihr Bogen noch steht.
     */
    private fun gallery(c: BlockCanvas, center: BlockPos, spec: ArenaSpec, lost: Set<String>) {
        val inner = OlympusArenas.FORUM_GALLERY_INNER.toInt()
        val outer = OlympusArenas.FORUM_GALLERY_OUTER.toInt()
        val top = center.y + OlympusArenas.FORUM_GALLERY_HEIGHT
        val parapet = LinkedHashSet<Long>()
        val radius = HermesArena.RADIUS
        for (dx in -radius..radius) for (dz in -radius..radius) {
            val k = ringOf(dx, dz)
            if (k < inner || k > outer) continue
            val x = center.x + dx
            val z = center.z + dz
            if (inLostArc(spec, lost, x, z)) continue
            val angle = ArenaGeo.angleOf(dx.toDouble(), dz.toDouble())
            // Wand unter der Lauffläche.
            for (y in center.y + 1 until top) {
                val face = k == inner
                val light = face && galleryLight(angle)
                c.put(x, y, z, when {
                    light -> Blocks.SEA_LANTERN.defaultState
                    face -> HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE.defaultState
                    else -> HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS.defaultState
                })
            }
            c.put(x, top, z, when (k) {
                inner -> HhaDecoBlocks.GILDED_OLYMPUS_MARBLE.defaultState
                outer -> HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS.defaultState
                else -> HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE.defaultState
            })
            if (k == outer) parapet.add(BlockPos.asLong(x, 0, z))
        }
        closeDiagonals(parapet, center, spec, lost)
        // Brüstung: Mauerblöcke, verbunden mit ihren Nachbarn; Pfosten, wo die Reihe abknickt.
        for (key in parapet) {
            val x = BlockPos.unpackLongX(key)
            val z = BlockPos.unpackLongZ(key)
            c.put(x, top + 1, z, parapetState(parapet, x, z))
        }
    }

    /**
     * Schließt die Brüstung: Wo zwei Mauerblöcke des Rings nur über Eck aneinanderstoßen, kommt der innere Eckblock
     * dazu (sonst bliebe eine Lücke in der Brüstung, und der Ring hinge nicht zusammen).
     */
    private fun closeDiagonals(cells: MutableSet<Long>, center: BlockPos, spec: ArenaSpec, lost: Set<String>) {
        val add = ArrayList<Long>()
        for (key in cells) {
            val x = BlockPos.unpackLongX(key)
            val z = BlockPos.unpackLongZ(key)
            for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
                if (BlockPos.asLong(x + sx, 0, z + sz) !in cells) continue
                val a = BlockPos.asLong(x + sx, 0, z)
                val b = BlockPos.asLong(x, 0, z + sz)
                if (a in cells || b in cells || a in add || b in add) continue
                // Der Eckblock, der näher an der Mitte liegt, steht noch auf der Empore.
                val ra = ringOf(x + sx - center.x, z - center.z)
                val rb = ringOf(x - center.x, z + sz - center.z)
                val pick = if (ra <= rb) a else b
                val px = BlockPos.unpackLongX(pick)
                val pz = BlockPos.unpackLongZ(pick)
                if (ringOf(px - center.x, pz - center.z) > OlympusArenas.FORUM_GALLERY_OUTER.toInt()) continue
                if (inLostArc(spec, lost, px, pz)) continue
                add.add(pick)
            }
        }
        cells.addAll(add)
    }

    private fun galleryLight(angle: Double): Boolean {
        val off = ((angle % GALLERY_LIGHT_STEP) + GALLERY_LIGHT_STEP) % GALLERY_LIGHT_STEP
        return abs(off - GALLERY_LIGHT_STEP / 2.0) < 1.6
    }

    private fun parapetState(cells: Set<Long>, x: Int, z: Int): BlockState {
        var state = HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_WALL.defaultState
        val linked = ArrayList<Direction>()
        for (dir in Direction.Type.HORIZONTAL) {
            if (BlockPos.asLong(x + dir.offsetX, 0, z + dir.offsetZ) in cells) linked.add(dir)
        }
        for (dir in Direction.Type.HORIZONTAL) {
            val prop = WallBlock.WALL_SHAPE_PROPERTIES_BY_DIRECTION[dir] ?: continue
            state = state.with(prop, if (dir in linked) WallShape.LOW else WallShape.NONE)
        }
        val straight = linked.size == 2 && linked[0] == linked[1].opposite
        return state.with(WallBlock.UP, !straight)
    }

    /**
     * Aufgänge bei 0°, 90°, 180°, 270° (Vertrag `Level.access`: Ringe 20 und 21 auf Kern + 0 und + 1), polierte
     * Treppen nach außen steigend, darunter Ziegel. Ein Aufgang entfällt, wenn beide Bögen daneben verloren sind.
     */
    private fun stairs(c: BlockCanvas, center: BlockPos, spec: ArenaSpec, lost: Set<String>) {
        for (a in OlympusArenas.FORUM_STAIR_ANGLES) {
            if (stairGone(a, lost)) continue
            val facing = outward(a)
            for (cell in ArenaGeo.radialStairs(center, a, OlympusArenas.FORUM_CORE - 2.0, OlympusArenas.FORUM_GALLERY_HEIGHT, OlympusArenas.FORUM_STAIR_WIDTH, spec.coreY)) {
                for (y in spec.coreY until cell.y) c.put(cell.x, y, cell.z, HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS.defaultState)
                c.put(cell.x, cell.y, cell.z, HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_STAIRS.defaultState.with(StairsBlock.FACING, facing).with(StairsBlock.HALF, BlockHalf.BOTTOM))
            }
        }
    }

    /** Liegen beide Bögen neben dem Aufgang bei [angle] in [lost]? */
    fun stairGone(angle: Double, lost: Set<String>): Boolean {
        val after = "arc_${angle.toInt() % 360}"
        val before = "arc_${((angle + 270.0) % 360.0).toInt()}"
        return after in lost && before in lost
    }

    /** Treppen nach außen steigend: Blickrichtung des Spielers beim Hinaufgehen. */
    private fun outward(angle: Double): Direction = when (((angle % 360.0 + 360.0) % 360.0 / 90.0 + 0.5).toInt() % 4) {
        0 -> Direction.EAST
        1 -> Direction.SOUTH
        2 -> Direction.WEST
        else -> Direction.NORTH
    }

    // --- Botensäulen und Glocken ----------------------------------------------------------------------------------

    /**
     * Botensäule [k]: 3 × 3 ab Kernhöhe, sieben hoch (Vertrag `column_k`): Ziegelsockel, gemeißeltes Band, Marmorschaft,
     * goldenes Kapitell auf + 6 mit umlaufender Kehle (umgedrehte Treppen), Krone auf + 7 mit Seelaterne. Der Bruch
     * ([HermesArena.breakColumn]) lässt einen Stumpf von Sockel und Band stehen.
     */
    fun column(c: BlockCanvas, center: BlockPos, k: Int) {
        val p = OlympusArenas.forumColumn(center, k)
        val y0 = p.y
        for (dx in -1..1) for (dz in -1..1) {
            val x = p.x + dx
            val z = p.z + dz
            c.put(x, y0, z, HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS.defaultState)
            c.put(x, y0 + 1, z, HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE.defaultState)
            for (y in y0 + 2 until y0 + CAPITAL) c.put(x, y, z, HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR.defaultState)
            c.put(x, y0 + CAPITAL, z, HhaDecoBlocks.GILDED_OLYMPUS_MARBLE.defaultState)
            val crown = if (dx == 0 && dz == 0) Blocks.SEA_LANTERN.defaultState
            else HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_SLAB.defaultState.with(SlabBlock.TYPE, SlabType.BOTTOM)
            c.put(x, y0 + CROWN, z, crown)
        }
        // Kehle unter dem Abakus: umgedrehte Treppen rund um das Kapitell, Rücken an der Säule (ohne Ecken).
        for (dir in Direction.Type.HORIZONTAL) {
            for (s in -1..1) {
                val x = p.x + dir.offsetX * 2 + (if (dir.offsetX == 0) s else 0)
                val z = p.z + dir.offsetZ * 2 + (if (dir.offsetZ == 0) s else 0)
                c.put(x, y0 + CAPITAL, z, HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_STAIRS.defaultState
                    .with(StairsBlock.FACING, dir.opposite).with(StairsBlock.HALF, BlockHalf.TOP))
            }
        }
    }

    /** Kasten, den [column] zeichnet (mit Kehle und Krone): für Bruch und Rückkehr. */
    fun columnBox(center: BlockPos, k: Int): BlockBox {
        val p = OlympusArenas.forumColumn(center, k)
        return BlockBox(p.x - 2, p.y, p.z - 2, p.x + 2, p.y + CROWN, p.z + 2)
    }

    /** Botenglocke [k] (Vertrag `bell_k`, Zustand 0) auf einem vollen Sockelblock (Wunsch P05 2). */
    fun bell(c: BlockCanvas, center: BlockPos, spec: ArenaSpec, k: Int) {
        val spot = spec.devices[k]
        c.put(spot.pos.x, spot.pos.y - 1, spot.pos.z, HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE.defaultState)
        c.put(spot.pos.x, spot.pos.y, spot.pos.z, bellState(spec, k, ArenaDeviceBlock.IDLE))
    }

    /** Zustand der Glocke [k] mit Vorderseite aus dem Vertrag. */
    fun bellState(spec: ArenaSpec, k: Int, mode: Int): BlockState =
        spec.devices[k].block().defaultState.with(HorizontalFacingBlock.FACING, spec.devices[k].facing).with(ArenaDeviceBlock.MODE, mode)
}
