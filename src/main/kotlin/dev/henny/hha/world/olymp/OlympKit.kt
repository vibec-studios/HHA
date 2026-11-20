package dev.henny.hha.world.olymp

import dev.henny.hha.HhaBlocks
import dev.henny.hha.block.BossAltarBlock
import dev.henny.hha.block.SteleBlock
import dev.henny.hha.world.Kit
import dev.henny.hha.world.OlympusTemplePlan
import dev.henny.hha.world.block
import dev.henny.hha.world.brazier as greenBrazier
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.WallBlock
import net.minecraft.block.enums.WallShape
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Gemeinsamer Baukasten der Olymp-Inseln (aus `OlympusPlans` herausgelöst, Überarbeitung 4, P00): Säulenordnungen,
 * Hermen, Figuren, Stelen, Laternen, Giebel, Gebälk, Wege. Eingefroren; die Insel-Dateien unter `isles/` benutzen ihn.
 */
internal object OlympKit {

    internal fun s(b: Block): BlockState = b.defaultState
    internal fun stair(b: Block, facing: Direction, top: Boolean = false) = Kit.stair(b, facing, top)
    internal fun slab(b: Block, top: Boolean = false) = Kit.slab(b, top)

    // =============================================================================================
    // Baukasten
    // =============================================================================================

    /** Ort mit Blickrichtung: a = nach rechts, f = nach vorn, h = nach oben. */
    internal class Frame(val c: SkyCanvas, val x: Int, val y: Int, val z: Int, val facing: Direction) {
        val right: Direction = facing.rotateYClockwise()
        fun px(a: Int, f: Int) = x + right.offsetX * a + facing.offsetX * f
        fun pz(a: Int, f: Int) = z + right.offsetZ * a + facing.offsetZ * f
        fun put(a: Int, f: Int, h: Int, state: BlockState) = c.block(px(a, f), y + h, pz(a, f), state)
        fun inward(a: Int, f: Int): Direction = Kit.inward(px(a, f) - x, pz(a, f) - z)
    }

    /** Säulenordnung: Basis, Schaft, Kapitell. */
    class Order(val base: BlockState, val baseStair: Block, val shaft: BlockState, val capital: BlockState, val capitalStair: Block)

    internal val MARBLE = Order(s(Blocks.CHISELED_QUARTZ_BLOCK), Blocks.QUARTZ_STAIRS, s(Blocks.QUARTZ_PILLAR), s(Blocks.CHISELED_QUARTZ_BLOCK), Blocks.QUARTZ_STAIRS)
    internal val FORGE = Order(s(Blocks.POLISHED_DEEPSLATE), Blocks.POLISHED_DEEPSLATE_STAIRS, s(Blocks.DEEPSLATE_BRICKS), s(Blocks.WAXED_CUT_COPPER), Blocks.WAXED_CUT_COPPER_STAIRS)
    internal val DARK = Order(s(Blocks.POLISHED_BLACKSTONE), Blocks.POLISHED_BLACKSTONE_STAIRS, s(Blocks.POLISHED_BASALT), s(Blocks.CHISELED_POLISHED_BLACKSTONE), Blocks.POLISHED_BLACKSTONE_STAIRS)

    /**
     * Dicke Säule wie am Tempel: Plinthe 3 × 3 mit Schrägen an den Ecken, Schaft im Kreuzquerschnitt (wirkt rund und
     * kanneliert), Kapitell 3 × 3 mit auskragenden Ecken. Unterste Lage bei [y], [height] Lagen.
     */
    internal fun column(c: SkyCanvas, x: Int, y: Int, z: Int, height: Int, o: Order = MARBLE) {
        for (ox in -1..1) for (oz in -1..1) {
            val corner = ox != 0 && oz != 0
            c.block(x + ox, y, z + oz, if (corner) stair(o.baseStair, Kit.inward(ox, oz)) else o.base)
            c.block(x + ox, y + height - 1, z + oz, if (corner) stair(o.capitalStair, Kit.inward(ox, oz), top = true) else o.capital)
        }
        for (h in 1 until height - 1) {
            c.block(x, y + h, z, o.shaft)
            for (d in HORIZONTAL) c.block(x + d.offsetX, y + h, z + d.offsetZ, o.shaft)
        }
    }

    /** Schlanke Säule (1 breit) mit Basis und einem Kapitell aus vier Kragsteinen. */
    internal fun slimColumn(c: SkyCanvas, x: Int, y: Int, z: Int, height: Int, o: Order = MARBLE) {
        c.block(x, y, z, o.base)
        for (d in HORIZONTAL) c.block(x + d.offsetX, y, z + d.offsetZ, stair(o.baseStair, d.opposite))
        for (h in 1 until height - 1) c.block(x, y + h, z, o.shaft)
        c.block(x, y + height - 1, z, o.capital)
        for (d in HORIZONTAL) c.block(x + d.offsetX, y + height - 1, z + d.offsetZ, stair(o.capitalStair, d.opposite, top = true))
    }

    internal val HORIZONTAL = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)

    /** Mauerblock mit echten Verbindungen entlang einer Achse; [post] = freistehender Pfosten. */
    internal fun wall(block: Block, alongX: Boolean, post: Boolean = false): BlockState {
        val x = if (post) WallShape.NONE else if (alongX) WallShape.LOW else WallShape.NONE
        val z = if (post) WallShape.NONE else if (alongX) WallShape.NONE else WallShape.LOW
        return block.defaultState.with(WallBlock.EAST_WALL_SHAPE, x).with(WallBlock.WEST_WALL_SHAPE, x)
            .with(WallBlock.NORTH_WALL_SHAPE, z).with(WallBlock.SOUTH_WALL_SHAPE, z).with(WallBlock.UP, post)
    }

    /** Herme: Pfeiler mit dem Kopf des Hermes, der Wächter der Wege. Fünf hoch, blickt nach [facing]. */
    internal fun herm(c: SkyCanvas, x: Int, y: Int, z: Int, facing: Direction) {
        val f = Frame(c, x, y, z, facing)
        f.put(0, 0, 0, s(Blocks.CHISELED_QUARTZ_BLOCK))
        f.put(0, 0, 1, s(Blocks.QUARTZ_PILLAR))
        f.put(0, 0, 2, s(Blocks.QUARTZ_PILLAR))
        f.put(0, 0, 3, s(Blocks.SMOOTH_QUARTZ))
        f.put(0, 1, 2, stair(Blocks.SMOOTH_QUARTZ_STAIRS, facing.opposite, top = true))
        f.put(0, 0, 4, slab(Blocks.POLISHED_DIORITE_SLAB))
    }

    /** Was eine Statue in den Händen hält. */
    internal enum class Attr { SCEPTER, SCALES, CORNUCOPIA, SPEAR, BOWL, HAMMER, NONE }

    /** Material einer Statue. */
    internal class Look(
        val plinth: BlockState, val plinthTop: BlockState, val plinthStair: Block,
        val robe: BlockState, val fold: BlockState, val robeStair: Block, val skin: BlockState,
        val hair: BlockState, val hairSlab: Block, val arm: BlockState, val belt: BlockState, val lights: Boolean,
    )

    internal val MARBLE_LOOK = Look(
        s(Blocks.QUARTZ_BRICKS), s(Blocks.CHISELED_QUARTZ_BLOCK), Blocks.QUARTZ_STAIRS,
        s(Blocks.CALCITE), s(Blocks.DIORITE), Blocks.SMOOTH_QUARTZ_STAIRS, s(Blocks.SMOOTH_QUARTZ),
        s(Blocks.POLISHED_DIORITE), Blocks.POLISHED_DIORITE_SLAB, wall(Blocks.DIORITE_WALL, alongX = false, post = true), s(Blocks.POLISHED_DIORITE), true,
    )

    internal val BRONZE_LOOK = Look(
        s(Blocks.POLISHED_DEEPSLATE), s(Blocks.CHISELED_DEEPSLATE), Blocks.POLISHED_DEEPSLATE_STAIRS,
        s(Blocks.WAXED_CUT_COPPER), s(Blocks.WAXED_EXPOSED_CUT_COPPER), Blocks.WAXED_CUT_COPPER_STAIRS, s(Blocks.WAXED_COPPER_BLOCK),
        s(Blocks.WAXED_CHISELED_COPPER), Blocks.WAXED_CUT_COPPER_SLAB, s(Blocks.WAXED_EXPOSED_COPPER), s(Blocks.WAXED_CHISELED_COPPER), false,
    )

    /**
     * Gewandete Figur auf einem Sockel, neun Blöcke hoch: Saum, Falten, Gürtel, Schultern, Kopf mit Haarknoten und das,
     * was sie in den Händen hält. Steht bei (x, y, z) mit Blick nach [facing].
     */
    internal fun figure(c: SkyCanvas, x: Int, y: Int, z: Int, facing: Direction, attr: Attr, look: Look = MARBLE_LOOK) {
        val f = Frame(c, x, y, z, facing)
        val back = facing.opposite
        for (a in -1..1) for (d in -1..1) {
            val corner = a != 0 && d != 0
            f.put(a, d, 0, if (corner) stair(look.plinthStair, f.inward(a, d)) else look.plinth)
            f.put(a, d, 1, look.plinthTop)
        }
        // Saum und Gewand: drei breit, hinten ein Faltenwurf, vorn ausgestellt.
        for (a in -1..1) {
            f.put(a, 0, 2, look.robe)
            f.put(a, -1, 2, if (a == 0) look.robe else look.fold)
            f.put(a, 1, 2, stair(look.robeStair, back))
            f.put(a, 0, 3, if (a == 0) look.robe else look.fold)
            f.put(a, 0, 4, if (a == 0) look.fold else look.robe)
            f.put(a, 0, 5, if (a == 0) look.belt else look.robe)
            f.put(a, 0, 6, look.robe)
        }
        f.put(0, -1, 3, look.robe)
        f.put(0, -1, 4, look.fold)
        f.put(0, -1, 5, look.robe)
        // Schultern.
        f.put(2, 0, 6, stair(look.robeStair, f.right.opposite, top = true))
        f.put(-2, 0, 6, stair(look.robeStair, f.right, top = true))
        // Kopf.
        f.put(0, 0, 7, look.skin)
        f.put(0, -1, 7, look.hair)
        f.put(0, 0, 8, slab(look.hairSlab))
        // Arme: hängend, außer der Hand mit dem Attribut.
        val leftBusy = attr == Attr.SCALES || attr == Attr.CORNUCOPIA || attr == Attr.SPEAR || attr == Attr.BOWL
        val rightBusy = attr == Attr.SCEPTER || attr == Attr.SPEAR || attr == Attr.BOWL || attr == Attr.HAMMER
        if (!leftBusy) for (h in 4..5) f.put(-2, 0, h, look.arm)
        if (!rightBusy) for (h in 4..5) f.put(2, 0, h, look.arm)
        when (attr) {
            Attr.SCEPTER -> {
                f.put(2, 0, 5, look.arm)
                for (h in 2..8) f.put(2, 1, h, wall(Blocks.DIORITE_WALL, alongX = false, post = true))
                f.put(2, 1, 9, s(Blocks.END_ROD))
            }
            Attr.SCALES -> {
                f.put(-2, 1, 6, stair(look.robeStair, back, top = true))
                f.put(-2, 2, 6, look.arm)
                for (a in intArrayOf(-3, -1)) {
                    f.put(a, 2, 6, Kit.chain(f.right.axis))
                    f.put(a, 2, 5, Kit.chain())
                    // P24: Schale als hängende Laterne (eine Druckplatte an der Kette schwebt und fiele beim ersten Update ab).
                    f.put(a, 2, 4, Blocks.LANTERN.defaultState.with(Properties.HANGING, true))
                }
                for (h in 4..5) f.put(2, 0, h, look.arm)
            }
            Attr.CORNUCOPIA -> {
                f.put(-2, 1, 5, stair(look.robeStair, back, top = true))
                f.put(-2, 1, 6, stair(look.robeStair, back))
                f.put(-2, 1, 7, OlympusIsles.leaves(Blocks.FLOWERING_AZALEA_LEAVES))
                f.put(-2, 0, 5, look.arm)
                f.put(2, 1, 5, OlympusIsles.leaves(Blocks.AZALEA_LEAVES))
            }
            Attr.SPEAR -> {
                for (h in 2..9) f.put(2, 1, h, wall(Blocks.DIORITE_WALL, alongX = false, post = true))
                f.put(2, 1, 10, s(Blocks.WAXED_LIGHTNING_ROD))
                f.put(2, 0, 5, look.arm)
                f.put(-2, 1, 4, s(Blocks.WAXED_COPPER_BLOCK))
                f.put(-2, 1, 3, stair(Blocks.WAXED_CUT_COPPER_STAIRS, facing))
                f.put(-2, 1, 5, stair(Blocks.WAXED_CUT_COPPER_STAIRS, facing, top = true))
                f.put(-2, 0, 5, look.arm)
                f.put(0, 0, 8, slab(Blocks.WAXED_CUT_COPPER_SLAB))
                f.put(0, -1, 8, s(Blocks.WAXED_CUT_COPPER))
            }
            Attr.BOWL -> {
                for (a in intArrayOf(-1, 1)) f.put(a, 1, 5, stair(look.robeStair, back, top = true))
                f.put(0, 1, 5, slab(Blocks.WAXED_CUT_COPPER_SLAB, top = true))
                f.put(-2, 0, 5, look.arm)
                f.put(2, 0, 5, look.arm)
            }
            Attr.HAMMER -> {
                f.put(2, 0, 5, look.arm)
                f.put(2, 1, 5, Kit.chain())
                f.put(2, 1, 4, Kit.chain())
                f.put(2, 1, 3, s(Blocks.POLISHED_BLACKSTONE))
            }
            Attr.NONE -> {}
        }
        if (look.lights) {
            f.put(-1, 1, 2, s(Blocks.END_ROD))
            f.put(1, 1, 2, s(Blocks.END_ROD))
        }
    }

    /** Stele mit ihrer Geschichte auf einem kleinen Fuß; blickt nach [facing]. */
    internal fun stele(c: SkyCanvas, x: Int, y: Int, z: Int, story: Int, facing: Direction) {
        c.block(x, y - 1, z, s(Blocks.CHISELED_QUARTZ_BLOCK))
        c.block(x, y, z, HhaBlocks.STELE.defaultState.with(SteleBlock.STORY, story))
        c.block(x, y + 1, z, Kit.AIR)
        val f = Frame(c, x, y, z, facing)
        f.put(-1, 0, 0, stair(Blocks.SMOOTH_QUARTZ_STAIRS, f.right))
        f.put(1, 0, 0, stair(Blocks.SMOOTH_QUARTZ_STAIRS, f.right.opposite))
    }

    internal fun altar(c: SkyCanvas, pos: BlockPos, boss: Int) {
        c.block(pos.x, pos.y, pos.z, HhaBlocks.BOSS_ALTAR.defaultState.with(BossAltarBlock.BOSS, boss))
        for (h in 1..3) c.block(pos.x, pos.y + h, pos.z, Kit.AIR)
    }

    /** Laternenpfosten: Fuß, zwei Lagen Mauer, oben die Laterne. */
    internal fun lanternPost(c: SkyCanvas, x: Int, y: Int, z: Int, soul: Boolean = false, block: Block = Blocks.DIORITE_WALL) {
        c.block(x, y, z, if (soul) s(Blocks.POLISHED_BLACKSTONE) else s(Blocks.CHISELED_QUARTZ_BLOCK))
        c.block(x, y + 1, z, wall(block, alongX = true, post = true))
        c.block(x, y + 2, z, wall(block, alongX = true, post = true))
        c.block(x, y + 3, z, Kit.lantern(hanging = false, soul = soul))
    }

    /** Feuerschale aus Bronze auf einem Fuß: ein Lagerfeuer in einer Kupferschale, darüber steigt Rauch. */
    internal fun brazier(c: SkyCanvas, x: Int, y: Int, z: Int) {
        c.block(x, y, z, s(Blocks.CHISELED_QUARTZ_BLOCK))
        c.block(x, y + 1, z, wall(Blocks.DIORITE_WALL, alongX = true, post = true))
        c.block(x, y + 2, z, s(Blocks.WAXED_CUT_COPPER))
        for (d in HORIZONTAL) c.block(x + d.offsetX, y + 2, z + d.offsetZ, stair(Blocks.WAXED_CUT_COPPER_STAIRS, d.opposite, top = true))
        c.block(x, y + 3, z, s(Blocks.CAMPFIRE))
    }

    /** Freistehende Säule für einen Greif: 6 hoch, oben 3 × 3 frei (PLAN 5.6). Unterste Lage bei [y]. */
    internal fun griffinColumn(c: SkyCanvas, x: Int, y: Int, z: Int) {
        column(c, x, y, z, 6)
        for (ox in -1..1) for (oz in -1..1) for (h in 6..9) c.block(x + ox, y + h, z + oz, Kit.AIR)
        c.block(x, y + 5, z, s(Blocks.GOLD_BLOCK))
    }

    /** Sockel für eine Goldene Wächterin: 3 × 3, eine Lage, mit Schrägen. */
    internal fun automatonPlinth(c: SkyCanvas, x: Int, y: Int, z: Int, look: Look) {
        for (ox in -1..1) for (oz in -1..1) {
            val corner = ox != 0 && oz != 0
            c.block(x + ox, y, z + oz, if (corner) stair(look.plinthStair, Kit.inward(ox, oz)) else look.plinthTop)
            for (h in 1..4) c.block(x + ox, y + h, z + oz, Kit.AIR)
        }
    }

    /**
     * Giebel über einer Front mit Blick nach [facing]: Fußlinie bei [y], Breite von −[half] bis [half], nach hinten
     * [depth] tief. Vorn das Giebelfeld, eine Reihe davor das Schräggesims, hinten das Bronzedach, oben ein goldenes
     * Akroter.
     */
    internal fun pediment(c: SkyCanvas, x: Int, y: Int, z: Int, facing: Direction, half: Int, depth: Int, tympanum: BlockState = s(Blocks.SMOOTH_QUARTZ)) {
        val f = Frame(c, x, y, z, facing)
        for (a in -half..half) {
            val run = half - abs(a)
            val rise = run / 2
            val odd = run % 2 == 1
            val towardMiddle = if (a < 0) f.right else f.right.opposite
            for (d in -depth + 1..0) {
                for (h in 0 until rise) f.put(a, d, h, if (d == 0) tympanum else s(Blocks.WAXED_CUT_COPPER))
                f.put(a, d, rise, when {
                    a == 0 -> s(Blocks.WAXED_CUT_COPPER)
                    odd -> stair(Blocks.WAXED_CUT_COPPER_STAIRS, towardMiddle)
                    else -> slab(Blocks.WAXED_CUT_COPPER_SLAB)
                })
            }
            // Schräggesims vorn.
            f.put(a, 1, rise, if (a == 0) s(Blocks.CHISELED_QUARTZ_BLOCK) else if (odd) stair(Blocks.SMOOTH_QUARTZ_STAIRS, towardMiddle) else slab(Blocks.SMOOTH_QUARTZ_SLAB))
        }
        f.put(0, 1, half / 2 + 1, s(Blocks.GOLD_BLOCK))
    }

    /** Gebälk über einer geraden Säulenreihe: Architrav, Fries im Wechsel, Gesims nach außen. */
    internal fun entablature(c: SkyCanvas, x0: Int, z0: Int, x1: Int, z1: Int, y: Int, frieze: BlockState = s(Blocks.CHISELED_QUARTZ_BLOCK)) {
        for (x in minOf(x0, x1)..maxOf(x0, x1)) for (z in minOf(z0, z1)..maxOf(z0, z1)) {
            val edge = x == minOf(x0, x1) || x == maxOf(x0, x1) || z == minOf(z0, z1) || z == maxOf(z0, z1)
            c.block(x, y, z, s(Blocks.SMOOTH_QUARTZ))
            c.block(x, y + 1, z, if (edge && Math.floorMod(x + z, 4) == 0) s(Blocks.QUARTZ_PILLAR) else if (edge) frieze else s(Blocks.SMOOTH_QUARTZ))
            c.block(x, y + 2, z, s(Blocks.SMOOTH_QUARTZ))
        }
        for (x in minOf(x0, x1) - 1..maxOf(x0, x1) + 1) for (z in minOf(z0, z1) - 1..maxOf(z0, z1) + 1) {
            val outer = x == minOf(x0, x1) - 1 || x == maxOf(x0, x1) + 1 || z == minOf(z0, z1) - 1 || z == maxOf(z0, z1) + 1
            if (!outer) continue
            val toward = if (x < minOf(x0, x1)) Direction.EAST else if (x > maxOf(x0, x1)) Direction.WEST else if (z < minOf(z0, z1)) Direction.SOUTH else Direction.NORTH
            c.block(x, y + 2, z, stair(Blocks.SMOOTH_QUARTZ_STAIRS, toward, top = true))
        }
    }

    /** Geordnetes Raster 4 × 4 (0 … 15): Übergänge wirken gesetzt statt verstreut. */
    internal fun bayer(x: Int, z: Int): Int = BAYER[Math.floorMod(z, 4) * 4 + Math.floorMod(x, 4)]

    internal val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    /**
     * Brüstung am Inselrand: Mauerstücke, die wirklich mit ihren Nachbarn verbunden sind, an Ecken und alle paar
     * Schritte ein Pfosten mit Deckplatte. [skip] lässt Öffnungen (Brücken, Wege, Terrassen).
     */
    internal fun rimBalustrade(c: SkyCanvas, body: OlympusIsles.Body, y: Int, block: Block, post: BlockState, skip: (Int, Int) -> Boolean) {
        val reach = body.radius.toInt() + 2
        val cells = HashSet<Long>()
        for (x in body.cx - reach..body.cx + reach) for (z in body.cz - reach..body.cz + reach) {
            if (!body.rim.containsCell(x, z) || skip(x, z)) continue
            if (HORIZONTAL.none { !body.rim.containsCell(x + it.offsetX, z + it.offsetZ) }) continue
            cells.add(BlockPos.asLong(x, 0, z))
        }
        for (k in cells) {
            val x = BlockPos.unpackLongX(k)
            val z = BlockPos.unpackLongZ(k)
            if (!c.inside(x, z)) continue
            val linked = HORIZONTAL.filter { BlockPos.asLong(x + it.offsetX, 0, z + it.offsetZ) in cells }
            val straight = linked.size == 2 && linked[0] == linked[1].opposite
            if (!straight || Kit.noise(x, y, z) % 6 == 0) {
                c.block(x, y, z, post)
                c.block(x, y + 1, z, slab(Blocks.SMOOTH_QUARTZ_SLAB))
                continue
            }
            var state = block.defaultState.with(WallBlock.UP, false)
            for (d in HORIZONTAL) {
                val shape = if (d in linked) WallShape.LOW else WallShape.NONE
                state = state.with(WallBlock.WALL_SHAPE_PROPERTIES_BY_DIRECTION[d]!!, shape)
            }
            c.block(x, y, z, state)
        }
    }

    /** Fackelsäule der Hekate: dunkler Schaft, oben eine Schale mit blauem Seelenfeuer. */
    internal fun torchPillar(c: SkyCanvas, x: Int, y: Int, z: Int) {
        slimColumn(c, x, y, z, 6, DARK)
        c.block(x, y + 6, z, s(Blocks.SOUL_CAMPFIRE))
        for (d in HORIZONTAL) c.block(x + d.offsetX, y + 6, z + d.offsetZ, stair(Blocks.POLISHED_BLACKSTONE_STAIRS, d.opposite, top = true))
    }

    internal fun polar(cx: Int, cz: Int, r: Double, deg: Double): Pair<Int, Int> {
        val a = Math.toRadians(deg)
        return (cx + r * cos(a)).roundToInt() to (cz + r * sin(a)).roundToInt()
    }

    internal fun angleOf(dx: Double, dz: Double): Double = (Math.toDegrees(atan2(dz, dx)) + 360.0) % 360.0

    internal fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /** Gerader, gepflasterter Weg von (x0, z0) nach (x1, z1) (achsparallel oder schräg), [half] breit. */
    internal fun path(c: SkyCanvas, x0: Int, z0: Int, x1: Int, z1: Int, y: Int, half: Double, pal: (Int, Int) -> BlockState) {
        val dx = (x1 - x0).toDouble()
        val dz = (z1 - z0).toDouble()
        val len = hypot(dx, dz)
        val minX = minOf(x0, x1) - half.toInt() - 1
        val maxX = maxOf(x0, x1) + half.toInt() + 1
        val minZ = minOf(z0, z1) - half.toInt() - 1
        val maxZ = maxOf(z0, z1) + half.toInt() + 1
        for (x in minX..maxX) for (z in minZ..maxZ) {
            val t = (((x - x0) * dx + (z - z0) * dz) / (len * len)).coerceIn(0.0, 1.0)
            val d = hypot(x - (x0 + dx * t), z - (z0 + dz * t))
            if (d > half) continue
            c.block(x, y, z, pal(x, z))
            for (h in 1..3) if (c.inside(x, z) && !c.isFree(x, y + h, z)) c.block(x, y + h, z, Kit.AIR)
        }
    }

    /** Freiraum über einer Fläche, damit nichts Weiches (Wolken, Gras) hineinragt. */
    internal fun clearAbove(c: SkyCanvas, cx: Int, cz: Int, r: Double, y: Int, height: Int) {
        c.disc(cx, cz, r) { x, z, _ -> for (h in 0 until height) c.put(x, y + h, z, Kit.AIR) }
    }
}
