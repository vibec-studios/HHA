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

/** Art eines Postens (PLAN 5.6): Kureten an Brückenköpfen und Treppen, Greife auf Säulen, Wächterinnen auf Sockeln. */
enum class PostKind { KURETE, GRIFFIN, AUTOMATON }

/**
 * Ein Posten für die Gegner des Olymp (P11 liest ihn): Füße bei [pos], höchstens [group] zugleich, Insel [isle].
 * [yaw] = Blickrichtung (Minecraft-Yaw: 0 Süden, 90 Westen, 180 Norden, −90 Osten), `null` = von der Inselmitte weg.
 */
data class Post(val pos: BlockPos, val kind: PostKind, val group: Int, val isle: Char?, val yaw: Float? = null)

/**
 * Die Bauten des Olymp (PLAN 8.2/8.3), je Insel ein Plan. Alle Koordinaten kommen aus [OlympusLayout]; was der Kampf
 * braucht (Kampfflächen, Altäre, Hekataion, Amboss, Roste, Sockel, Pfeiler), sitzt genau dort. Nur Vanilla-Blöcke außer
 * den Funktionsblöcken `olympus_portal`, `hades_portal`, `boss_altar`, `stele`, `offering_pedestal`, `olympus_altar`.
 *
 * Reihenfolge je Insel: Körper, Brückenköpfe, Bauten, dann Bewuchs und Wolken (weich, nie über einem Bau).
 */
object OlympusPlans {

    private fun s(b: Block): BlockState = b.defaultState
    private fun stair(b: Block, facing: Direction, top: Boolean = false) = Kit.stair(b, facing, top)
    private fun slab(b: Block, top: Boolean = false) = Kit.slab(b, top)

    /**
     * Was außerhalb des Inselkreises noch zur Insel gehört: die Treppe und die Terrasse der Insel E, die Höllentor-
     * Terrasse der Insel C. Brücken zeichnen dort nicht.
     */
    val EXTRA_CLAIMS: List<Pair<OlympusLayout.Isle, BlockBox>> = listOf(
        OlympusLayout.E to BlockBox(136, 150, -612, 164, 240, -590),
        OlympusLayout.C to BlockBox(-50, 110, -318, -24, 170, -284),
    )

    /** Kasten eines Insel-Pieces: großzügig, mit Wolken, Ausläufern und allem, was über dem Boden steht. */
    fun box(isle: OlympusLayout.Isle): BlockBox {
        val reach = isle.radius + 28
        val depth = (isle.radius * 0.8).roundToInt()
        return BlockBox(
            isle.center.x - reach, isle.floorY - depth - 40, isle.center.z - reach,
            isle.center.x + reach, isle.floorY + 64, isle.center.z + reach,
        )
    }

    fun draw(c: SkyCanvas, isle: OlympusLayout.Isle) {
        when (isle.letter) {
            'A' -> isleA(c)
            'B' -> isleB(c)
            'C' -> isleC(c)
            'D' -> isleD(c)
            'E' -> isleE(c)
            'F' -> isleF(c)
        }
    }

    /** Die Brückenstücke, die in dieser Insel liegen. */
    private fun bridgeHeads(c: SkyCanvas, isle: OlympusLayout.Isle) {
        for (bridge in OlympusBridges.ALL) {
            if (bridge.from === isle || bridge.to === isle) OlympusBridges.draw(c, bridge, OlympusBridges.Part.INSIDE)
        }
    }

    // =============================================================================================
    // Posten (P11)
    // =============================================================================================

    /** Alle Posten des festen Pfads. Keine auf Insel A, keine in einer Kampffläche. */
    fun posts(): List<Post> = POSTS

    private val POSTS: List<Post> = listOf(
        // B: Kureten an beiden Brückenköpfen, Greife auf den zwei Siegessäulen.
        Post(BlockPos(-6, 128, -103), PostKind.KURETE, 2, 'B', 0f),
        Post(BlockPos(6, 128, -157), PostKind.KURETE, 2, 'B', 180f),
        Post(BlockPos(29, 134, -130), PostKind.GRIFFIN, 1, 'B'),
        Post(BlockPos(-29, 134, -130), PostKind.GRIFFIN, 1, 'B'),
        // C: Kureten am Südweg und am Brückenkopf nach D, ein Greif auf der Säule im Osten.
        Post(BlockPos(6, 140, -248), PostKind.KURETE, 2, 'C', 0f),
        Post(BlockPos(26, 140, -303), PostKind.KURETE, 2, 'C', -90f),
        Post(BlockPos(33, 146, -270), PostKind.GRIFFIN, 1, 'C'),
        // D: eine Wächterin zwischen zwei Bronzestatuen im Westen, Kureten am Ausgang nach E.
        Post(BlockPos(118, 161, -390), PostKind.AUTOMATON, 1, 'D', -90f),
        Post(BlockPos(160, 160, -419), PostKind.KURETE, 2, 'D', 180f),
        // E: Kureten oben an der Treppe, ein Greif auf der Säule im Südosten.
        Post(BlockPos(150, 204, -603), PostKind.KURETE, 2, 'E', 0f),
        Post(BlockPos(178, 198, -545), PostKind.GRIFFIN, 1, 'E'),
        // F: Kureten am Brückenkopf, zwei Wächterinnen am Südtor, zwei Greife auf Säulen im Garten.
        Post(BlockPos(34, 232, -651), PostKind.KURETE, 3, 'F', 180f),
        Post(BlockPos(-9, 233, -641), PostKind.AUTOMATON, 1, 'F', 0f),
        Post(BlockPos(9, 233, -641), PostKind.AUTOMATON, 1, 'F', 0f),
        Post(BlockPos(-41, 238, -668), PostKind.GRIFFIN, 1, 'F'),
        Post(BlockPos(41, 238, -692), PostKind.GRIFFIN, 1, 'F'),
    )

    // =============================================================================================
    // Baukasten
    // =============================================================================================

    /** Ort mit Blickrichtung: a = nach rechts, f = nach vorn, h = nach oben. */
    private class Frame(val c: SkyCanvas, val x: Int, val y: Int, val z: Int, val facing: Direction) {
        val right: Direction = facing.rotateYClockwise()
        fun px(a: Int, f: Int) = x + right.offsetX * a + facing.offsetX * f
        fun pz(a: Int, f: Int) = z + right.offsetZ * a + facing.offsetZ * f
        fun put(a: Int, f: Int, h: Int, state: BlockState) = c.block(px(a, f), y + h, pz(a, f), state)
        fun inward(a: Int, f: Int): Direction = Kit.inward(px(a, f) - x, pz(a, f) - z)
    }

    /** Säulenordnung: Basis, Schaft, Kapitell. */
    class Order(val base: BlockState, val baseStair: Block, val shaft: BlockState, val capital: BlockState, val capitalStair: Block)

    internal val MARBLE = Order(s(Blocks.CHISELED_QUARTZ_BLOCK), Blocks.QUARTZ_STAIRS, s(Blocks.QUARTZ_PILLAR), s(Blocks.CHISELED_QUARTZ_BLOCK), Blocks.QUARTZ_STAIRS)
    private val FORGE = Order(s(Blocks.POLISHED_DEEPSLATE), Blocks.POLISHED_DEEPSLATE_STAIRS, s(Blocks.DEEPSLATE_BRICKS), s(Blocks.WAXED_CUT_COPPER), Blocks.WAXED_CUT_COPPER_STAIRS)
    private val DARK = Order(s(Blocks.POLISHED_BLACKSTONE), Blocks.POLISHED_BLACKSTONE_STAIRS, s(Blocks.POLISHED_BASALT), s(Blocks.CHISELED_POLISHED_BLACKSTONE), Blocks.POLISHED_BLACKSTONE_STAIRS)

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

    private val HORIZONTAL = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)

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
    private enum class Attr { SCEPTER, SCALES, CORNUCOPIA, SPEAR, BOWL, HAMMER, NONE }

    /** Material einer Statue. */
    private class Look(
        val plinth: BlockState, val plinthTop: BlockState, val plinthStair: Block,
        val robe: BlockState, val fold: BlockState, val robeStair: Block, val skin: BlockState,
        val hair: BlockState, val hairSlab: Block, val arm: BlockState, val belt: BlockState, val lights: Boolean,
    )

    private val MARBLE_LOOK = Look(
        s(Blocks.QUARTZ_BRICKS), s(Blocks.CHISELED_QUARTZ_BLOCK), Blocks.QUARTZ_STAIRS,
        s(Blocks.CALCITE), s(Blocks.DIORITE), Blocks.SMOOTH_QUARTZ_STAIRS, s(Blocks.SMOOTH_QUARTZ),
        s(Blocks.POLISHED_DIORITE), Blocks.POLISHED_DIORITE_SLAB, wall(Blocks.DIORITE_WALL, alongX = false, post = true), s(Blocks.POLISHED_DIORITE), true,
    )

    private val BRONZE_LOOK = Look(
        s(Blocks.POLISHED_DEEPSLATE), s(Blocks.CHISELED_DEEPSLATE), Blocks.POLISHED_DEEPSLATE_STAIRS,
        s(Blocks.WAXED_CUT_COPPER), s(Blocks.WAXED_EXPOSED_CUT_COPPER), Blocks.WAXED_CUT_COPPER_STAIRS, s(Blocks.WAXED_COPPER_BLOCK),
        s(Blocks.WAXED_CHISELED_COPPER), Blocks.WAXED_CUT_COPPER_SLAB, s(Blocks.WAXED_EXPOSED_COPPER), s(Blocks.WAXED_CHISELED_COPPER), false,
    )

    /**
     * Gewandete Figur auf einem Sockel, neun Blöcke hoch: Saum, Falten, Gürtel, Schultern, Kopf mit Haarknoten und das,
     * was sie in den Händen hält. Steht bei (x, y, z) mit Blick nach [facing].
     */
    private fun figure(c: SkyCanvas, x: Int, y: Int, z: Int, facing: Direction, attr: Attr, look: Look = MARBLE_LOOK) {
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
                    f.put(a, 2, 4, s(Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE))
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

    private fun altar(c: SkyCanvas, pos: BlockPos, boss: Int) {
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
    private fun griffinColumn(c: SkyCanvas, x: Int, y: Int, z: Int) {
        column(c, x, y, z, 6)
        for (ox in -1..1) for (oz in -1..1) for (h in 6..9) c.block(x + ox, y + h, z + oz, Kit.AIR)
        c.block(x, y + 5, z, s(Blocks.GOLD_BLOCK))
    }

    /** Sockel für eine Goldene Wächterin: 3 × 3, eine Lage, mit Schrägen. */
    private fun automatonPlinth(c: SkyCanvas, x: Int, y: Int, z: Int, look: Look) {
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
    private fun pediment(c: SkyCanvas, x: Int, y: Int, z: Int, facing: Direction, half: Int, depth: Int, tympanum: BlockState = s(Blocks.SMOOTH_QUARTZ)) {
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
    private fun entablature(c: SkyCanvas, x0: Int, z0: Int, x1: Int, z1: Int, y: Int, frieze: BlockState = s(Blocks.CHISELED_QUARTZ_BLOCK)) {
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
    private fun bayer(x: Int, z: Int): Int = BAYER[Math.floorMod(z, 4) * 4 + Math.floorMod(x, 4)]

    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

    /**
     * Brüstung am Inselrand: Mauerstücke, die wirklich mit ihren Nachbarn verbunden sind, an Ecken und alle paar
     * Schritte ein Pfosten mit Deckplatte. [skip] lässt Öffnungen (Brücken, Wege, Terrassen).
     */
    private fun rimBalustrade(c: SkyCanvas, body: OlympusIsles.Body, y: Int, block: Block, post: BlockState, skip: (Int, Int) -> Boolean) {
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
    private fun torchPillar(c: SkyCanvas, x: Int, y: Int, z: Int) {
        slimColumn(c, x, y, z, 6, DARK)
        c.block(x, y + 6, z, s(Blocks.SOUL_CAMPFIRE))
        for (d in HORIZONTAL) c.block(x + d.offsetX, y + 6, z + d.offsetZ, stair(Blocks.POLISHED_BLACKSTONE_STAIRS, d.opposite, top = true))
    }

    private fun polar(cx: Int, cz: Int, r: Double, deg: Double): Pair<Int, Int> {
        val a = Math.toRadians(deg)
        return (cx + r * cos(a)).roundToInt() to (cz + r * sin(a)).roundToInt()
    }

    private fun angleOf(dx: Double, dz: Double): Double = (Math.toDegrees(atan2(dz, dx)) + 360.0) % 360.0

    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /** Gerader, gepflasterter Weg von (x0, z0) nach (x1, z1) (achsparallel oder schräg), [half] breit. */
    private fun path(c: SkyCanvas, x0: Int, z0: Int, x1: Int, z1: Int, y: Int, half: Double, pal: (Int, Int) -> BlockState) {
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
    private fun clearAbove(c: SkyCanvas, cx: Int, cz: Int, r: Double, y: Int, height: Int) {
        c.disc(cx, cz, r) { x, z, _ -> for (h in 0 until height) c.put(x, y + h, z, Kit.AIR) }
    }

    // =============================================================================================
    // A  Wolkentor der Horen
    // =============================================================================================

    private fun isleA(c: SkyCanvas) {
        val isle = OlympusLayout.A
        val fy = isle.floorY
        val cx = isle.center.x
        val cz = isle.center.z
        val body = OlympusIsles.Body(cx, fy - 1, cz, isle.radius.toDouble(), IsleKind.MEADOW, 11, 0.9)
        OlympusIsles.body(c, body)
        bridgeHeads(c, isle)
        // Platz der Horen: polierter Diorit, Strahlen aus Kalkstein, die vom Tor ausgehen.
        val gate = OlympusLayout.ARRIVAL_GATE
        val gx = gate.x + OlympusLayout.ARRIVAL_GATE_WIDTH / 2.0
        val gz = gate.z + 0.5
        c.disc(0, -2, 12.5) { x, z, r ->
            val a = angleOf(x + 0.5 - gx, z + 0.5 - gz)
            val ray = a in 12.0..168.0 && (a + 7.5) % 15.0 < 2.6
            val state = when {
                r > 11.5 -> s(Blocks.QUARTZ_BRICKS)
                r > 10.8 -> s(Blocks.CALCITE)
                ray && hypot(x + 0.5 - gx, z + 0.5 - gz) > 3.0 -> s(Blocks.CALCITE)
                else -> s(Blocks.POLISHED_DIORITE)
            }
            c.put(x, fy - 1, z, state)
            for (h in 0..12) c.put(x, fy + h, z, Kit.AIR)
        }
        // Weg vom Tor zur Brücke nach Norden.
        path(c, 0, -10, 0, -21, fy - 1, 2.6) { x, _ -> if (abs(x) == 2) s(Blocks.CALCITE) else s(Blocks.POLISHED_DIORITE) }
        gateArch(c, fy)
        // Die drei Horen: Eunomia (Ordnung) und Dike (Recht) zu beiden Seiten, Eirene (Frieden) gegenüber dem Tor.
        figure(c, -10, fy, -3, Direction.EAST, Attr.SCEPTER)
        figure(c, 9, fy, -3, Direction.WEST, Attr.SCALES)
        figure(c, 0, fy, 9, Direction.NORTH, Attr.CORNUCOPIA)
        // Halbrunde Bank hinter Eirene.
        for (deg in 55..125 step 5) {
            val (bx, bz) = polar(0, -2, 14.6, deg.toDouble())
            if (abs(bx) <= 1) continue
            c.block(bx, fy, bz, stair(Blocks.SMOOTH_QUARTZ_STAIRS, Direction.SOUTH))
        }
        stele(c, -3, fy, -4, 6, Direction.SOUTH)
        stele(c, 4, fy, -18, 7, Direction.WEST)
        herm(c, -4, fy, -20, Direction.NORTH)
        herm(c, 4, fy, -20, Direction.NORTH)
        // Garten: Kirschen und Ölbäume am Rand, Zypressen am Weg zur Brücke.
        OlympusIsles.cherry(c, -15, fy, 7, 3)
        OlympusIsles.cherry(c, 15, fy, 8, 4)
        OlympusIsles.olive(c, -16, fy, -10, 5)
        OlympusIsles.olive(c, 16, fy, -12, 6)
        OlympusIsles.cypress(c, -5, fy, -14, 8, 7)
        OlympusIsles.cypress(c, 5, fy, -14, 8, 8)
        OlympusIsles.cypress(c, -8, fy, 17, 7, 9)
        OlympusIsles.cypress(c, 8, fy, 17, 7, 10)
        OlympusIsles.meadow(c, body, fy, { x, z -> hypot(x.toDouble(), z + 2.0) < 14.0 || (abs(x) <= 4 && z in -26..-8) })
        OlympusIsles.cloudSkirt(c, body, 4)
        OlympusIsles.cloud(c, -42, 104, 12, 18.0, 11.0, 12, 3.0)
        OlympusIsles.cloud(c, 36, 108, -38, 14.0, 9.0, 13, 2.6)
        OlympusIsles.cloud(c, 18, 98, 34, 16.0, 10.0, 14, 3.0)
    }

    /**
     * Das Wolkentor: ein Ehrenbogen aus Marmor. Zwei Pylonen mit vorgestellten Säulen, dazwischen der Rundbogen mit
     * goldenem Schlussstein; im Bogen steht der Glowstone-Rahmen mit dem Tor. Darüber Gebälk, Gesims und eine Attika.
     * In den Außenseiten der Pylonen je eine Nische mit Amphore.
     */
    private fun gateArch(c: SkyCanvas, fy: Int) {
        val g = OlympusLayout.ARRIVAL_GATE
        val w = OlympusLayout.ARRIVAL_GATE_WIDTH
        val h = OlympusLayout.ARRIVAL_GATE_HEIGHT
        val gz = g.z
        val x0 = g.x - 1
        val x1 = g.x + w
        val top = g.y + h
        val mid = g.x + w / 2.0
        val spring = top.toDouble()
        val left = x0 - 3..x0 - 1
        val right = x1 + 1..x1 + 3
        val beam = top + 5
        // Pylonen (drei tief) und die Wand über dem Bogen, unten ein Sockel.
        for (x in x0 - 3..x1 + 3) for (z in gz - 1..gz + 1) for (y in fy - 1 until beam) {
            val pylon = x in left || x in right
            val r = hypot(x + 0.5 - mid, y + 0.5 - spring)
            if (!pylon && (y <= top || r <= 3.6)) {
                if (z != gz || y > top) c.block(x, y, z, Kit.AIR)
                continue
            }
            val face = z != gz
            val state = when {
                y == fy - 1 -> s(Blocks.SMOOTH_QUARTZ)
                y == fy -> s(Blocks.CHISELED_QUARTZ_BLOCK)
                !pylon && face && (x == g.x + 1 || x == g.x + 2) && y == beam - 1 -> s(Blocks.GOLD_BLOCK)
                !pylon && face && r <= 4.8 -> s(Blocks.CHISELED_QUARTZ_BLOCK)
                pylon && (x == x0 - 3 || x == x1 + 3) && face -> s(Blocks.QUARTZ_PILLAR)
                Kit.noise(x, y, z) % 9 == 0 -> s(Blocks.SMOOTH_QUARTZ)
                else -> s(Blocks.QUARTZ_BRICKS)
            }
            c.block(x, y, z, state)
        }
        // Vorgestellte Säulen vor den Pylonen, vorn und hinten.
        for (x in intArrayOf(x0 - 2, x1 + 2)) for (z in intArrayOf(gz - 2, gz + 2)) {
            val out = if (z < gz) Direction.NORTH else Direction.SOUTH
            c.block(x, fy, z, s(Blocks.CHISELED_QUARTZ_BLOCK))
            for (side in listOf(out, out.rotateYClockwise(), out.rotateYCounterclockwise())) {
                c.block(x + side.offsetX, fy, z + side.offsetZ, stair(Blocks.QUARTZ_STAIRS, side.opposite))
                c.block(x + side.offsetX, beam - 1, z + side.offsetZ, stair(Blocks.QUARTZ_STAIRS, side.opposite, top = true))
            }
            for (y in fy + 1 until beam - 1) c.block(x, y, z, s(Blocks.QUARTZ_PILLAR))
            c.block(x, beam - 1, z, s(Blocks.CHISELED_QUARTZ_BLOCK))
            c.block(x, fy - 1, z, s(Blocks.SMOOTH_QUARTZ))
        }
        // Gebälk: Architrav, Fries mit Triglyphen, Gesims nach allen Seiten.
        for (x in x0 - 4..x1 + 4) for (z in gz - 2..gz + 2) {
            c.block(x, beam, z, s(Blocks.SMOOTH_QUARTZ))
            val edge = x == x0 - 4 || x == x1 + 4 || z == gz - 2 || z == gz + 2
            c.block(x, beam + 1, z, if (edge && Math.floorMod(x - x0, 3) == 0) s(Blocks.QUARTZ_PILLAR) else if (edge) s(Blocks.CHISELED_QUARTZ_BLOCK) else s(Blocks.SMOOTH_QUARTZ))
            c.block(x, beam + 2, z, s(Blocks.SMOOTH_QUARTZ))
        }
        for (x in x0 - 5..x1 + 5) for (z in gz - 3..gz + 3) {
            val ring = x == x0 - 5 || x == x1 + 5 || z == gz - 3 || z == gz + 3
            if (ring) c.block(x, beam + 2, z, stair(Blocks.SMOOTH_QUARTZ_STAIRS, Kit.inward(x * 2 - (x0 + x1), z - gz), top = true))
        }
        // Attika mit zwei kleinen Akroteren und Lichtstäben (das Licht der Horen über dem Tor).
        for (x in x0 - 2..x1 + 2) for (z in gz - 1..gz + 1) {
            c.block(x, beam + 3, z, if (x == x0 - 2 || x == x1 + 2) s(Blocks.CHISELED_QUARTZ_BLOCK) else s(Blocks.QUARTZ_BRICKS))
            c.block(x, beam + 4, z, if (x == x0 - 2 || x == x1 + 2) s(Blocks.CHISELED_QUARTZ_BLOCK) else Kit.slab(Blocks.SMOOTH_QUARTZ_SLAB))
        }
        for (x in intArrayOf(x0 - 2, x1 + 2)) c.block(x, beam + 5, gz, s(Blocks.END_ROD))
        // Nischen in den Außenseiten der Pylonen, darin je eine Amphore.
        for ((x, out) in listOf(x0 - 3 to Direction.WEST, x1 + 3 to Direction.EAST)) {
            for (y in fy + 2..fy + 4) c.block(x, y, gz, Kit.AIR)
            c.block(x, fy + 5, gz, stair(Blocks.QUARTZ_STAIRS, out.opposite, top = true))
            c.block(x, fy + 2, gz, s(Blocks.DECORATED_POT))
        }
        // Der Rahmen aus Glowstone (untere Reihe im Boden) und das Tor.
        for (x in x0..x1) for (y in g.y - 1..top) {
            val frame = x == x0 || x == x1 || y == g.y - 1 || y == top
            c.block(x, y, gz, if (frame) s(Blocks.GLOWSTONE) else HhaBlocks.OLYMPUS_PORTAL.defaultState.with(Properties.HORIZONTAL_AXIS, OlympusLayout.ARRIVAL_GATE_AXIS))
        }
    }

    // =============================================================================================
    // B  Tempel des Boten
    // =============================================================================================

    private fun isleB(c: SkyCanvas) {
        val isle = OlympusLayout.B
        val fy = isle.floorY
        val cx = isle.center.x
        val cz = isle.center.z
        val body = OlympusIsles.Body(cx, fy - 1, cz, isle.radius.toDouble(), IsleKind.MEADOW, 22, 0.92)
        OlympusIsles.body(c, body)
        bridgeHeads(c, isle)
        // Der Tempel (Maße unverändert) mit seiner Mitte auf der Inselmitte; Plateau-Block bei fy − 1.
        OlympusTemplePlan.draw(c, BlockPos(cx, fy - 1, cz), stairs = false)
        // Prozessionswege zu beiden Brücken.
        path(c, 0, cz + 25, 0, cz + 31, fy - 1, 2.6) { x, _ -> if (abs(x) == 2) s(Blocks.CALCITE) else s(Blocks.POLISHED_DIORITE) }
        path(c, 0, cz - 25, 0, cz - 31, fy - 1, 2.6) { x, _ -> if (abs(x) == 2) s(Blocks.CALCITE) else s(Blocks.POLISHED_DIORITE) }
        for (sx in intArrayOf(-4, 4)) {
            herm(c, sx, fy, cz + 30, Direction.SOUTH)
            herm(c, sx, fy, cz - 30, Direction.NORTH)
        }
        // Zwei Siegessäulen für die Greife, im Osten und Westen.
        griffinColumn(c, 29, fy, cz)
        griffinColumn(c, -29, fy, cz)
        stele(c, -4, fy, cz + 27, 8, Direction.SOUTH)
        // Amphoren am Fuß der Tempelfront.
        for (sx in intArrayOf(-7, 7)) {
            c.block(sx, fy, cz + 26, s(Blocks.DECORATED_POT))
            c.block(sx + if (sx < 0) -1 else 1, fy, cz + 26, s(Blocks.DECORATED_POT))
        }
        // Zypressen im Ring um den Tempel, Ölbäume und Kirschen dazwischen.
        for (deg in 0 until 360 step 24) {
            if (angleDiff(deg.toDouble(), 90.0) < 20 || angleDiff(deg.toDouble(), 270.0) < 20 || angleDiff(deg.toDouble(), 0.0) < 10 || angleDiff(deg.toDouble(), 180.0) < 10) continue
            val (tx, tz) = polar(cx, cz, 29.5, deg.toDouble())
            OlympusIsles.cypress(c, tx, fy, tz, 7 + (deg / 24) % 3, deg)
        }
        OlympusIsles.olive(c, 20, fy, cz + 22, 31)
        OlympusIsles.olive(c, -21, fy, cz - 21, 32)
        OlympusIsles.cherry(c, -20, fy, cz + 23, 33)
        OlympusIsles.cherry(c, 21, fy, cz - 22, 34)
        OlympusIsles.meadow(c, body, fy, { x, z -> hypot((x - cx).toDouble(), (z - cz).toDouble()) < 25.5 || abs(x) <= 3 })
        OlympusIsles.cloudSkirt(c, body, 5)
        // Über dem Altar bleibt der Himmel frei (Himmelsforum des Hermes); Wolken nur unten und seitlich.
        OlympusIsles.cloud(c, -46, 112, cz + 10, 20.0, 12.0, 23, 3.0)
        OlympusIsles.cloud(c, 44, 106, cz - 18, 17.0, 11.0, 24, 2.8)
    }

    // =============================================================================================
    // C  Dreiweg der Hekate
    // =============================================================================================

    private fun isleC(c: SkyCanvas) {
        val isle = OlympusLayout.C
        val fy = isle.floorY
        val cx = isle.center.x
        val cz = isle.center.z
        val arena = isle.arenaRadius.toDouble()
        val body = OlympusIsles.Body(cx, fy - 1, cz, isle.radius.toDouble(), IsleKind.MARBLE, 33, 0.92)
        OlympusIsles.body(c, body)
        // Die tiefere Terrasse mit dem Höllentor im Nordwesten: eigener Fels, Fläche 6 tiefer.
        val ty = OlympusLayout.HELL_TERRACE_Y
        val gate = OlympusLayout.HELL_GATE
        OlympusIsles.body(c, OlympusIsles.Body(gate.x + 2, ty - 1, gate.z, 12.0, IsleKind.MARBLE, 34, 0.85))
        bridgeHeads(c, isle)
        // Der Platz: völlig eben, Marmor mit zwei Ringbändern, die drei Wege aus Andesit im Boden.
        c.disc(cx, cz, arena + 0.5) { x, z, r ->
            val dx = x - cx.toDouble()
            val dz = z - cz.toDouble()
            val a = angleOf(dx, dz)
            var way = -1
            for ((i, deg) in OlympusLayout.C_PATH_ANGLES.withIndex()) {
                val dir = OlympusLayout.dir(deg)
                val along = dx * dir.x + dz * dir.z
                val across = abs(-dx * dir.z + dz * dir.x)
                if (along > 1.5 && across < OlympusLayout.C_PATH_WIDTH / 2.0) way = i
                else if (along > 1.5 && across < OlympusLayout.C_PATH_WIDTH / 2.0 + 0.8 && way < 0) way = 10 + i
            }
            // Zum Höllentor hin wird der Marmor schwarz: geordnet, nicht verstreut.
            val toward = (1.0 - angleDiff(a, 210.0) / 45.0).coerceAtLeast(0.0)
            val darkness = ((r - 10.0) / 16.0).coerceIn(0.0, 1.0) * toward
            val dark = bayer(x, z) < (darkness * 17).toInt()
            // Das Rad der Hekate (Strophalos): drei Spiralarme um das Hekataion.
            val spiral = r in 6.5..11.5 && Math.floorMod((a + r * 17.0).roundToInt(), 120) < 22
            val state = when {
                way == OlympusLayout.C_PATH_NORTHWEST && bayer(x, z) < (((r - 8.0) / 16.0).coerceIn(0.0, 1.0) * 17).toInt() -> s(Blocks.POLISHED_BLACKSTONE)
                way in 0..2 -> s(Blocks.POLISHED_ANDESITE)
                way >= 10 -> if (way - 10 == OlympusLayout.C_PATH_NORTHWEST && r > 14.0) s(Blocks.POLISHED_BLACKSTONE_BRICKS) else s(Blocks.ANDESITE)
                r > arena - 0.5 -> if (dark) s(Blocks.CHISELED_POLISHED_BLACKSTONE) else s(Blocks.CHISELED_QUARTZ_BLOCK)
                r in 12.0..13.0 || r in 5.5..6.3 -> if (dark) s(Blocks.POLISHED_BLACKSTONE_BRICKS) else s(Blocks.POLISHED_DIORITE)
                r in 19.0..19.7 -> if (dark) s(Blocks.POLISHED_BLACKSTONE_BRICKS) else s(Blocks.QUARTZ_BRICKS)
                spiral -> s(Blocks.POLISHED_DIORITE)
                dark -> s(Blocks.POLISHED_BLACKSTONE)
                Math.floorMod(x + z, 2) == 0 -> s(Blocks.SMOOTH_QUARTZ)
                else -> s(Blocks.CALCITE)
            }
            c.put(x, fy - 1, z, state)
            for (h in 0..30) c.put(x, fy + h, z, Kit.AIR)
        }
        hekataion(c, cx, fy, cz)
        altar(c, isle.altar!!, 0)
        // Ring um den Platz: Wandelgang aus Diorit, dahinter Beete mit Zypressen und Seelenlaternen.
        c.disc(cx, cz, 31.5) { x, z, r ->
            if (r <= arena + 0.5) return@disc
            if (!body.rim.containsCell(x, z)) return@disc
            c.put(x, fy - 1, z, if (r > 30.5) s(Blocks.QUARTZ_BRICKS) else s(Blocks.POLISHED_DIORITE))
        }
        // Wege über den Ring hinaus: Süd zur Brücke von B, Nordost zur Brücke nach D, Nordwest zur Treppe.
        path(c, 0, cz + 27, 0, cz + 37, fy - 1, 3.0) { _, _ -> s(Blocks.POLISHED_ANDESITE) }
        path(c, 22, cz - 13, 31, cz - 20, fy - 1, 3.0) { _, _ -> s(Blocks.POLISHED_ANDESITE) }
        path(c, -23, cz - 14, -25, cz - 15, fy - 1, 3.4) { _, _ -> s(Blocks.POLISHED_BLACKSTONE) }
        for (deg in 0 until 360 step 15) {
            val d = deg.toDouble()
            if (OlympusLayout.C_PATH_ANGLES.any { angleDiff(it, d) < 16.0 } || d in 178.0..242.0) continue
            val (tx, tz) = polar(cx, cz, 35.0, d)
            if (!body.rim.containsCell(tx, tz)) continue
            // Beet aus Quarzziegeln, darin Moos und eine Zypresse; dazwischen Laternen.
            if (deg % 30 == 0) {
                for (ox in -1..1) for (oz in -1..1) c.block(tx + ox, fy, tz + oz, if (ox == 0 && oz == 0) s(Blocks.MOSS_BLOCK) else s(Blocks.QUARTZ_BRICKS))
                OlympusIsles.cypress(c, tx, fy + 1, tz, 7 + (deg / 30) % 3, deg + 300)
            } else {
                val (lx, lz) = polar(cx, cz, 32.5, d)
                lanternPost(c, lx, fy, lz, soul = true, block = Blocks.POLISHED_BLACKSTONE_WALL)
            }
        }
        stele(c, -4, fy, cz + 33, 9, Direction.SOUTH)
        griffinColumn(c, 33, fy, cz + 10)
        // Fackelsäulen zu beiden Seiten der Wege nach Süden und Nordosten; am Nordwestweg rahmen sie die Treppe.
        for ((px, pz) in listOf(-6 to cz + 29, 6 to cz + 29, 29 to cz - 25, 29 to cz - 15, -27 to cz - 22, -27 to cz - 8)) {
            torchPillar(c, px, fy, pz)
        }
        rimBalustrade(c, body, fy, Blocks.DIORITE_WALL, s(Blocks.QUARTZ_PILLAR)) { x, z ->
            (abs(x) <= 5 && z > cz + 28) || (x > 18 && z in cz - 27..cz - 13) || (x < -18 && z in cz - 40..cz - 2)
        }
        hellTerrace(c, fy, ty)
        OlympusIsles.cloudSkirt(c, body, 5)
        OlympusIsles.cloud(c, 50, 118, cz + 16, 18.0, 12.0, 35, 3.0)
        OlympusIsles.cloud(c, -54, 112, cz + 22, 16.0, 11.0, 36, 2.6)
    }

    /** Hekataion: drei Gestalten Rücken an Rücken (3 × 3, 5 hoch), Tiefenschiefer, drei Seelenlaternen als Häupter. */
    private fun hekataion(c: SkyCanvas, cx: Int, fy: Int, cz: Int) {
        val faces = listOf(0 to 1, 1 to -1, -1 to -1)
        for (ox in -1..1) for (oz in -1..1) {
            c.block(cx + ox, fy, cz + oz, if (ox != 0 && oz != 0) stair(Blocks.POLISHED_DEEPSLATE_STAIRS, Kit.inward(ox, oz)) else s(Blocks.POLISHED_DEEPSLATE))
            val face = (ox to oz) in faces
            val center = ox == 0 && oz == 0
            for (h in 1..2) c.block(cx + ox, fy + h, cz + oz, when {
                center || face -> if (h == 2 && face) s(Blocks.CHISELED_DEEPSLATE) else s(Blocks.POLISHED_DEEPSLATE)
                else -> stair(Blocks.DEEPSLATE_TILE_STAIRS, Kit.inward(ox, oz).opposite, top = h == 2)
            })
            c.block(cx + ox, fy + 3, cz + oz, if (center) s(Blocks.CHISELED_POLISHED_BLACKSTONE) else if (face) s(Blocks.POLISHED_DEEPSLATE) else s(Blocks.DEEPSLATE_TILES))
            c.block(cx + ox, fy + 4, cz + oz, when {
                face -> Kit.lantern(hanging = false, soul = true)
                center -> s(Blocks.POLISHED_BLACKSTONE_WALL)
                else -> Kit.AIR
            })
        }
    }

    /**
     * Treppe sechs Blöcke hinab zur Terrasse des Höllentors: Propylon aus Schwarzsteinziegeln, die runde Öffnung
     * geschlossen (P03 öffnet sie mit `HadesGate.openRow`); der Marmor geht zum Tor hin in Schwarzstein über.
     */
    private fun hellTerrace(c: SkyCanvas, fy: Int, ty: Int) {
        val gate = OlympusLayout.HELL_GATE
        val gx = gate.x
        val gz = gate.z
        // Terrasse x −46 … −30; Treppe x −29 … −25 im Band z gz + 1 … gz + 11; der Platz endet bei r 26,5.
        val west = gx - 10
        val east = gx + 6
        val stairTop = gx + 11
        val band = gz + 1..gz + 11
        val north = gz - 14
        val south = gz + 13
        fun darkness(x: Int): Double = ((stairTop - x) / 15.0).coerceIn(0.0, 1.0)
        fun marble(x: Int, y: Int, z: Int): BlockState {
            val t = darkness(x)
            return if (Kit.noise(x, y, z) % 100 < (t * t * 100).toInt()) {
                if (Kit.noise(x, y + 1, z) % 5 == 0) s(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS) else s(Blocks.POLISHED_BLACKSTONE_BRICKS)
            } else if (Kit.noise(x, y, z) % 7 == 0) s(Blocks.CALCITE) else s(Blocks.QUARTZ_BRICKS)
        }
        // Terrasse: alles darüber frei, Boden vom Diorit in den Schwarzstein.
        for (x in west..east) for (z in north..south) {
            if (!c.inside(x, z)) continue
            for (y in ty..fy + 24) c.put(x, y, z, Kit.AIR)
            val t = darkness(x)
            val floor = when {
                Kit.noise(x, ty, z) % 100 < (t * 100).toInt() -> if ((x + z) % 2 == 0) s(Blocks.POLISHED_BLACKSTONE) else s(Blocks.POLISHED_BLACKSTONE_BRICKS)
                Kit.noise(x, ty + 2, z) % 6 == 0 -> s(Blocks.CALCITE)
                else -> s(Blocks.POLISHED_DIORITE)
            }
            c.put(x, ty - 1, z, floor)
            for (y in ty - 4..ty - 2) c.put(x, y, z, marble(x, y, z))
        }
        // Stützmauer zwischen Platz und Terrasse (bei x = east + 1), oben eine Brüstung; die Treppe schneidet sie.
        val wallX = east + 1
        for (z in north..south) {
            if (z in band) continue
            for (y in ty - 1..fy - 1) c.block(wallX, y, z, if (y == fy - 1) s(Blocks.CHISELED_QUARTZ_BLOCK) else marble(wallX, y, z))
            c.block(wallX, fy, z, if (Math.floorMod(z, 4) == 0) s(Blocks.QUARTZ_PILLAR) else wall(Blocks.DIORITE_WALL, alongX = false))
            if (Math.floorMod(z, 6) == 0) for (h in 0..3) c.block(wallX - 1, ty + h, z, if (h == 3) stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Direction.EAST, top = true) else marble(wallX - 1, ty + h, z))
        }
        // Die Treppe: fünf Stufen nach Westen hinab, Wangen mit Brüstung.
        for (k in 1..5) {
            val x = wallX + k - 1
            val y = ty + k - 1
            for (z in band.first - 1..band.last + 1) {
                val edge = z == band.first - 1 || z == band.last + 1
                for (yy in ty - 4 until y) c.block(x, yy, z, marble(x, yy, z))
                c.block(x, y, z, if (edge) marble(x, y, z) else stair(Blocks.QUARTZ_STAIRS, Direction.EAST))
                if (edge) c.block(x, y + 1, z, wall(Blocks.DIORITE_WALL, alongX = true))
                for (h in (if (edge) 2 else 1)..5) c.block(x, y + h, z, Kit.AIR)
            }
        }
        // Brüstung an den freien Rändern der Terrasse.
        for (x in west..east) for (z in intArrayOf(north, south)) {
            c.block(x, ty, z, if (Math.floorMod(x, 4) == 0) s(Blocks.POLISHED_BLACKSTONE_BRICKS) else wall(Blocks.POLISHED_BLACKSTONE_BRICK_WALL, alongX = true))
        }
        for (z in north..south) c.block(west, ty, z, if (Math.floorMod(z, 4) == 0) s(Blocks.POLISHED_BLACKSTONE_BRICKS) else wall(Blocks.POLISHED_BLACKSTONE_BRICK_WALL, alongX = false))
        // Erhöhte Schwelle vor dem Tor: Die Öffnung beginnt eine Reihe über den Füßen.
        for (x in gx + 2..gx + 3) for (z in gz - 6..gz + 6) {
            c.block(x, ty, z, if (x == gx + 3) stair(Blocks.POLISHED_BLACKSTONE_STAIRS, Direction.WEST) else s(Blocks.POLISHED_BLACKSTONE))
        }
        // Torwand: drei dick (x gx − 1 … gx + 1), die runde Öffnung geht durch; die Torebene liegt bei gx und ist
        // geschlossen. Was im 9 × 9-Feld nicht zur Öffnung gehört, ist schon Schwarzsteinziegel (wie nach openRow).
        for (d in -8..8) for (h in 0..15) {
            val z = gz + d
            val y = gate.y + h
            val open = dev.henny.hha.logic.world.HadesGate.inOpening(d, h)
            val ring = !open && hypot(d.toDouble(), h - 5.5) <= 5.9
            val square = abs(d) <= 4 && h in 1..9
            for (x in gx - 1..gx + 1) {
                val plane = x == gx
                val state = when {
                    h == 0 -> s(Blocks.CHISELED_POLISHED_BLACKSTONE)
                    plane && (open || square) -> s(Blocks.POLISHED_BLACKSTONE_BRICKS)
                    open -> Kit.AIR
                    ring && Math.floorMod(d + h, 3) == 0 -> s(Blocks.GILDED_BLACKSTONE)
                    ring -> s(Blocks.CHISELED_POLISHED_BLACKSTONE)
                    h == 13 -> s(Blocks.POLISHED_BLACKSTONE)
                    h == 14 -> if (abs(d) % 3 == 0) s(Blocks.CHISELED_POLISHED_BLACKSTONE) else s(Blocks.POLISHED_BLACKSTONE_BRICKS)
                    h == 15 -> s(Blocks.POLISHED_BLACKSTONE)
                    abs(d) == 8 -> s(Blocks.POLISHED_BLACKSTONE)
                    abs(d) > 5 && Kit.noise(x, y, z) % 13 == 0 -> s(Blocks.CRYING_OBSIDIAN)
                    Kit.noise(x, y, z) % 5 == 0 -> s(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS)
                    else -> s(Blocks.POLISHED_BLACKSTONE_BRICKS)
                }
                c.block(x, y, z, state)
            }
        }
        // Propylon nach Osten: zwei Säulen vor der Torwand, Gebälk, darüber der Giebel.
        for (d in intArrayOf(-6, 6)) column(c, gx + 4, ty, gz + d, 14, DARK)
        for (d in -8..8) for (x in gx + 2..gx + 5) {
            c.block(x, ty + 14, gz + d, if (x == gx + 5) stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Direction.WEST, top = true) else s(Blocks.POLISHED_BLACKSTONE_BRICKS))
            c.block(x, ty + 15, gz + d, if (x == gx + 5 && Math.floorMod(d, 2) == 0) s(Blocks.GILDED_BLACKSTONE) else s(Blocks.POLISHED_BLACKSTONE))
        }
        for (d in -9..9) {
            c.block(gx + 6, ty + 16, gz + d, stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Direction.WEST, top = true))
            c.block(gx - 2, ty + 16, gz + d, stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Direction.EAST, top = true))
        }
        for (d in -9..9) {
            val run = 9 - abs(d)
            val rise = run / 2
            for (h in 0 until rise) for (x in gx - 1..gx + 5) c.block(x, ty + 17 + h, gz + d, if (x == gx + 5 && h == 0 && abs(d) <= 2) s(Blocks.GILDED_BLACKSTONE) else s(Blocks.POLISHED_BLACKSTONE_BRICKS))
            val odd = run % 2 == 1
            for (x in gx - 1..gx + 6) c.block(x, ty + 17 + rise, gz + d, if (d == 0) s(Blocks.CHISELED_POLISHED_BLACKSTONE) else if (odd) stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, if (d < 0) Direction.SOUTH else Direction.NORTH) else slab(Blocks.POLISHED_BLACKSTONE_BRICK_SLAB))
        }
        // Grünes Feuer in zwei Schalen vor dem Tor, Seelenlaternen an der Treppe.
        for (d in intArrayOf(-10, 10)) c.greenBrazier(gx + 4, ty, gz + d, 1)
        lanternPost(c, east - 1, ty, band.last + 2, soul = true, block = Blocks.POLISHED_BLACKSTONE_WALL)
        lanternPost(c, east - 1, ty, north + 3, soul = true, block = Blocks.POLISHED_BLACKSTONE_WALL)
        stele(c, east - 3, ty, band.last + 1, 10, Direction.EAST)
    }

    // =============================================================================================
    // D  Schmiede der Kyklopen
    // =============================================================================================

    private fun isleD(c: SkyCanvas) {
        val isle = OlympusLayout.D
        val fy = isle.floorY
        val cx = isle.center.x
        val cz = isle.center.z
        val arena = isle.arenaRadius.toDouble()
        val body = OlympusIsles.Body(cx, fy - 1, cz, isle.radius.toDouble(), IsleKind.MARBLE, 44, 0.92)
        OlympusIsles.body(c, body)
        bridgeHeads(c, isle)
        // Kampffläche: Tiefenschieferfliesen, sechs Roste aus Kupfergittern über Magma, Rippen dazwischen.
        val forge = OlympusLayout.FORGE
        c.disc(cx, cz, arena + 0.5) { x, z, r ->
            val dx = x - cx.toDouble()
            val dz = z - cz.toDouble()
            val a = angleOf(dx, dz)
            val boundary = (0 until OlympusLayout.GRATE_SECTORS).minOf { angleDiff(a, it * OlympusLayout.GRATE_SECTOR_DEGREES) }
            val rib = Math.toRadians(boundary) * r < 1.0
            // Gusseiserne Querrippen alle vier Ringe: Die Roste lesen sich als Gitter, nicht als Fläche.
            val bar = Math.floorMod(r.roundToInt(), 4) == 0
            val grate = r >= OlympusLayout.GRATE_INNER + 0.5 && r <= OlympusLayout.GRATE_OUTER - 0.5 && !rib && !bar
            val mouth = abs(x - forge.x) <= 1 && abs(z - forge.z) <= 1
            val state = when {
                grate || mouth -> s(Blocks.WAXED_COPPER_GRATE)
                bar && !rib && r in OlympusLayout.GRATE_INNER..OlympusLayout.GRATE_OUTER -> s(Blocks.POLISHED_DEEPSLATE)
                r < OlympusLayout.GRATE_INNER - 0.5 -> if (Kit.noise(x, 1, z) % 6 == 0) s(Blocks.CRACKED_DEEPSLATE_TILES) else s(Blocks.POLISHED_DEEPSLATE)
                r <= OlympusLayout.GRATE_OUTER + 0.5 -> s(Blocks.DEEPSLATE_BRICKS)
                r > arena - 0.5 -> s(Blocks.CHISELED_DEEPSLATE)
                else -> if (Kit.noise(x, 2, z) % 9 == 0) s(Blocks.CRACKED_DEEPSLATE_TILES) else s(Blocks.DEEPSLATE_TILES)
            }
            c.put(x, fy - 1, z, state)
            c.put(x, fy - 2, z, if (mouth) s(Blocks.LAVA) else if (grate) s(Blocks.MAGMA_BLOCK) else s(Blocks.DEEPSLATE_BRICKS))
            c.put(x, fy - 3, z, s(Blocks.POLISHED_DEEPSLATE))
            for (h in 0..24) c.put(x, fy + h, z, Kit.AIR)
        }
        // Amboss: 3 × 2 × 3, unten Schwarzstein, oben drei Ambosse in einer Reihe, vorn und hinten Schrägen.
        val anvil = OlympusLayout.ANVIL
        for (ox in -1..1) for (oz in -1..1) {
            c.block(anvil.x + ox, fy, anvil.z + oz, if (ox == 0 && oz == 0) s(Blocks.CHISELED_POLISHED_BLACKSTONE) else s(Blocks.POLISHED_BLACKSTONE))
            c.block(anvil.x + ox, fy + 1, anvil.z + oz, when (oz) {
                0 -> (if (ox == 0) Blocks.ANVIL else if (ox < 0) Blocks.CHIPPED_ANVIL else Blocks.DAMAGED_ANVIL).defaultState.with(Properties.HORIZONTAL_FACING, Direction.NORTH)
                else -> stair(Blocks.POLISHED_BLACKSTONE_STAIRS, if (oz < 0) Direction.SOUTH else Direction.NORTH)
            })
        }
        altar(c, isle.altar!!, 1)
        // Die Halle: ein Ring aus wuchtigen Pfeilern mit Kupferbändern, darauf ein umlaufender Balken; kein Dach.
        val entrance = 142.0
        val exit = 297.0
        for (k in 0 until 16) {
            val deg = k * 22.5 + 11.25
            if (angleDiff(deg, 270.0) < 24.0 || angleDiff(deg, entrance) < 9.0 || angleDiff(deg, exit) < 9.0) continue
            val (px, pz) = polar(cx, cz, 26.8, deg)
            hallPier(c, px, fy, pz)
        }
        c.disc(cx, cz, 29.0) { x, z, r ->
            if (r < 25.0) return@disc
            val a = angleOf(x - cx.toDouble(), z - cz.toDouble())
            if (angleDiff(a, 270.0) < 18.0) return@disc
            c.put(x, fy + 9, z, if (Math.floorMod(x + z, 2) == 0) s(Blocks.DEEPSLATE_TILES) else s(Blocks.DEEPSLATE_BRICKS))
            c.put(x, fy + 10, z, if (r in 26.0..27.6) s(Blocks.WAXED_CUT_COPPER) else s(Blocks.DEEPSLATE_BRICKS))
            c.put(x, fy + 11, z, when {
                r > 28.0 -> stair(Blocks.DEEPSLATE_BRICK_STAIRS, Kit.inward(x - cx, z - cz), top = true)
                r < 25.8 -> stair(Blocks.DEEPSLATE_BRICK_STAIRS, Kit.inward(x - cx, z - cz).opposite, top = true)
                else -> s(Blocks.POLISHED_DEEPSLATE)
            })
        }
        // Ketten mit Laternen zwischen den Pfeilern.
        for (k in 0 until 16 step 2) {
            val deg = k * 22.5
            if (angleDiff(deg, 270.0) < 24.0) continue
            val (lx, lz) = polar(cx, cz, 26.8, deg)
            for (h in 6..8) c.block(lx, fy + h, lz, Kit.chain())
            c.block(lx, fy + 5, lz, Kit.lantern(hanging = true, soul = false))
        }
        forgeBuilding(c, fy)
        // Werkhof: ein Ring aus Fliesen um die Halle.
        c.disc(cx, cz, 31.5) { x, z, r ->
            if (r < 28.6 || !body.rim.containsCell(x, z)) return@disc
            if (angleDiff(angleOf(x - cx.toDouble(), z - cz.toDouble()), 270.0) < 22.0) return@disc
            c.put(x, fy - 1, z, if (r > 30.6) s(Blocks.POLISHED_DEEPSLATE) else s(Blocks.DEEPSLATE_TILES))
        }
        giantHammer(c, cx - 8, fy, cz + 32)
        rimBalustrade(c, body, fy, Blocks.DEEPSLATE_BRICK_WALL, s(Blocks.POLISHED_DEEPSLATE)) { x, z ->
            (x < cx - 18 && z > cz + 10) || (x > cx + 8 && z < cz - 24) || (abs(x - cx) <= 13 && z < cz - 22)
        }
        // Wege: vom Brückenkopf durch die Lücke im Ring, und zum Ausgang nach E.
        path(c, 120, -369, 131, -375, fy - 1, 3.0) { _, _ -> s(Blocks.DEEPSLATE_TILES) }
        path(c, 166, -420, 162, -413, fy - 1, 3.0) { _, _ -> s(Blocks.DEEPSLATE_TILES) }
        // Löschtrog im Osten.
        for (x in 179..183) for (z in -395..-385) {
            val edge = x == 179 || x == 183 || z == -395 || z == -385
            c.block(x, fy - 1, z, s(Blocks.POLISHED_DEEPSLATE))
            c.block(x, fy, z, if (edge) s(Blocks.POLISHED_DEEPSLATE) else s(Blocks.WATER))
            c.block(x, fy + 1, z, if (edge) (if ((x + z) % 2 == 0) slab(Blocks.WAXED_CUT_COPPER_SLAB) else s(Blocks.WAXED_CUT_COPPER)) else Kit.AIR)
            if (!edge) c.block(x, fy + 1, z, s(Blocks.WATER))
        }
        // Ein Stapel Rohlinge und Werkzeug am Rand.
        for ((x, z) in listOf(176 to -402, 177 to -402, 176 to -403)) c.block(x, fy, z, s(Blocks.IRON_BLOCK))
        c.block(176, fy + 1, -402, s(Blocks.WAXED_COPPER_BLOCK))
        c.block(123, fy, -404, s(Blocks.GRINDSTONE))
        c.block(122, fy, -400, s(Blocks.SMITHING_TABLE))
        c.block(124, fy, -409, s(Blocks.COAL_BLOCK))
        c.block(125, fy, -409, s(Blocks.COAL_BLOCK))
        c.block(124, fy + 1, -409, s(Blocks.COAL_BLOCK))
        // Eine Wächterin zwischen zwei Bronzestatuen im Westen, Blick zur Halle.
        figure(c, 118, fy, -396, Direction.EAST, Attr.HAMMER, BRONZE_LOOK)
        figure(c, 118, fy, -384, Direction.EAST, Attr.BOWL, BRONZE_LOOK)
        automatonPlinth(c, 118, fy, -390, BRONZE_LOOK)
        stele(c, 126, fy, -369, 11, Direction.SOUTH)
        OlympusIsles.cloudSkirt(c, body, 5)
        OlympusIsles.cloud(c, cx + 46, 132, cz + 12, 18.0, 12.0, 45, 3.0)
        OlympusIsles.cloud(c, cx - 40, 128, cz - 30, 15.0, 10.0, 46, 2.6)
    }

    /**
     * Der Hammer eines Kyklopen, abgelegt im Hof: Stiel aus dunklem Holz mit Eisenringen, Kopf aus Schwarzstein mit
     * Bahnen aus Eisen. Liegt entlang x; (x, z) ist das Stielende.
     */
    private fun giantHammer(c: SkyCanvas, x: Int, fy: Int, z: Int) {
        for (i in 0 until 10) c.block(x + i, fy, z, if (i % 4 == 1) s(Blocks.IRON_BLOCK) else s(Blocks.STRIPPED_DARK_OAK_LOG).with(Properties.AXIS, Direction.Axis.X))
        for (i in 10..12) for (dz in -2..2) for (h in 0..2) {
            val face = i == 10 || i == 12
            val state = when {
                face && abs(dz) <= 1 && h <= 1 -> s(Blocks.IRON_BLOCK)
                abs(dz) == 2 && h == 2 -> stair(Blocks.POLISHED_BLACKSTONE_STAIRS, if (dz < 0) Direction.SOUTH else Direction.NORTH, top = false)
                else -> s(Blocks.POLISHED_BLACKSTONE)
            }
            c.block(x + i, fy + h, z + dz, state)
        }
    }

    /** Hallenpfeiler: Sockel aus poliertem Tiefenschiefer, Ecken im Wechsel, ein Kupferband, Kapitell aus Kupfer. */
    private fun hallPier(c: SkyCanvas, x: Int, fy: Int, z: Int) {
        for (ox in -1..1) for (oz in -1..1) {
            val corner = ox != 0 && oz != 0
            for (h in 0..8) {
                val state = when {
                    h == 0 -> if (corner) stair(Blocks.POLISHED_DEEPSLATE_STAIRS, Kit.inward(ox, oz)) else s(Blocks.POLISHED_DEEPSLATE)
                    h == 8 -> if (corner) stair(Blocks.WAXED_CUT_COPPER_STAIRS, Kit.inward(ox, oz), top = true) else s(Blocks.WAXED_CUT_COPPER)
                    h == 4 -> s(Blocks.WAXED_CUT_COPPER)
                    corner -> if (h % 2 == 0) s(Blocks.DEEPSLATE_TILES) else s(Blocks.POLISHED_DEEPSLATE)
                    else -> if (Kit.noise(x + ox, fy + h, z + oz) % 7 == 0) s(Blocks.CRACKED_DEEPSLATE_BRICKS) else s(Blocks.DEEPSLATE_BRICKS)
                }
                c.block(x + ox, fy + h, z + oz, state)
            }
        }
    }

    /**
     * Die Esse im Norden: eine gedrungene Schmiede aus Tiefenschiefer mit einem Feuermaul nach Süden, innen Glut und
     * Lava hinter Gittern, dahinter der hohe Schlot mit Kupferbändern, aus dem Rauch steigt (Landmarke).
     */
    private fun forgeBuilding(c: SkyCanvas, fy: Int) {
        val fx = OlympusLayout.FORGE.x
        val front = OlympusLayout.D.center.z - 25
        val back = front - 13
        for (x in fx - 11..fx + 11) for (z in back..front) {
            val wallX = abs(x - fx) == 11
            val wallZ = z == front || z == back
            val inside = !wallX && !wallZ
            for (h in -1..13) {
                val y = fy + h
                val mouth = z >= front - 1 && abs(x - fx) <= 3 && h in 0..5 && hypot((x - fx).toDouble(), (h - 2.0) * 1.2) <= 4.2
                val state = when {
                    h == -1 -> s(Blocks.POLISHED_DEEPSLATE)
                    mouth -> Kit.AIR
                    inside && h in 0..9 -> Kit.AIR
                    h == 13 -> if (inside || abs(x - fx) < 11) s(Blocks.POLISHED_DEEPSLATE) else s(Blocks.DEEPSLATE_TILES)
                    h == 6 || h == 11 -> s(Blocks.WAXED_CUT_COPPER)
                    (wallX && wallZ) -> if (h % 2 == 0) s(Blocks.DEEPSLATE_TILES) else s(Blocks.POLISHED_DEEPSLATE)
                    Kit.noise(x, y, z) % 8 == 0 -> s(Blocks.CRACKED_DEEPSLATE_BRICKS)
                    else -> s(Blocks.DEEPSLATE_BRICKS)
                }
                c.block(x, y, z, state)
            }
        }
        // Gesims, schräges Dach nach hinten.
        val d = OlympusLayout.D
        for (x in fx - 12..fx + 12) {
            // Das Gesims kragt nur dort vor, wo es nicht über die Kampffläche ragt.
            if (hypot((x - d.center.x).toDouble(), (front + 1 - d.center.z).toDouble()) > d.arenaRadius + 0.5) {
                c.block(x, fy + 13, front + 1, stair(Blocks.DEEPSLATE_BRICK_STAIRS, Direction.NORTH, top = true))
            }
            for (k in 0..5) c.block(x, fy + 14 + k / 2, front - k * 2, if (k % 2 == 0) stair(Blocks.DEEPSLATE_TILE_STAIRS, Direction.NORTH) else s(Blocks.DEEPSLATE_TILES))
        }
        // Bogen über dem Feuermaul aus Kupfer, darüber das Kyklopenauge als runde Kupferscheibe.
        for (x in fx - 4..fx + 4) for (h in 0..7) {
            val r = hypot((x - fx).toDouble(), (h - 2.0) * 1.2)
            if (r in 4.2..5.3) c.block(x, fy + h, front, s(Blocks.WAXED_COPPER_BLOCK))
        }
        for (x in fx - 1..fx + 1) for (h in 8..10) c.block(x, fy + h, front, if (x == fx && h == 9) s(Blocks.MAGMA_BLOCK) else s(Blocks.WAXED_CHISELED_COPPER))
        // Innen: Glutboden, Lava hinter Gittern an der Rückwand, Hochöfen.
        for (x in fx - 10..fx + 10) for (z in back + 1 until front - 1) {
            val pit = abs(x - fx) <= 4 && z <= back + 5
            c.block(x, fy - 1, z, if (pit) s(Blocks.MAGMA_BLOCK) else s(Blocks.DEEPSLATE_TILES))
        }
        for (x in fx - 3..fx + 3) for (h in 1..3) {
            c.block(x, fy + h, back, s(Blocks.LAVA))
            c.block(x, fy + h, back + 1, Kit.bars(alongX = true))
            c.block(x, fy + h, back - 1, s(Blocks.POLISHED_DEEPSLATE))
        }
        for (x in intArrayOf(fx - 9, fx + 9)) for (z in back + 3..back + 9 step 3) c.block(x, fy, z, s(Blocks.BLAST_FURNACE).with(Properties.HORIZONTAL_FACING, if (x < fx) Direction.EAST else Direction.WEST))
        for (h in 5..9) c.block(fx, fy + h, back + 8, Kit.chain())
        c.block(fx, fy + 4, back + 8, Kit.lantern(hanging = true, soul = false))
        // Der Schlot.
        val sz = back + 4
        for (h in 13..40) {
            val y = fy + h
            val half = when {
                h < 26 -> 3
                h < 36 -> 2
                else -> 1
            }
            for (x in fx - half..fx + half) for (z in sz - half..sz + half) {
                val rim = abs(x - fx) == half || abs(z - sz) == half
                val state = when {
                    !rim -> if (h == 39) s(Blocks.HAY_BLOCK) else if (h == 40) s(Blocks.CAMPFIRE).with(Properties.SIGNAL_FIRE, true) else Kit.AIR
                    h % 7 == 0 -> s(Blocks.WAXED_CUT_COPPER)
                    abs(x - fx) == half && abs(z - sz) == half -> s(Blocks.POLISHED_DEEPSLATE)
                    Kit.noise(x, y, z) % 9 == 0 -> s(Blocks.CRACKED_DEEPSLATE_BRICKS)
                    else -> s(Blocks.DEEPSLATE_BRICKS)
                }
                c.block(x, y, z, state)
            }
            if (h == 25 || h == 35) for (x in fx - half - 1..fx + half + 1) for (z in sz - half - 1..sz + half + 1) {
                if (abs(x - fx) == half + 1 || abs(z - sz) == half + 1) c.block(x, y, z, stair(Blocks.DEEPSLATE_BRICK_STAIRS, Kit.inward(x - fx, z - sz), top = true))
            }
        }
        for (x in fx - 2..fx + 2) for (z in sz - 2..sz + 2) {
            if (abs(x - fx) == 2 || abs(z - sz) == 2) c.block(x, fy + 41, z, if ((x + z) % 2 == 0) s(Blocks.WAXED_CUT_COPPER) else Kit.AIR)
        }
    }

    // =============================================================================================
    // E  Stufen der Macht
    // =============================================================================================

    private fun isleE(c: SkyCanvas) {
        val isle = OlympusLayout.E
        val fy = isle.floorY
        val cx = isle.center.x
        val cz = isle.center.z
        val arena = isle.arenaRadius.toDouble()
        val body = OlympusIsles.Body(cx, fy - 1, cz, isle.radius.toDouble(), IsleKind.MARBLE, 55, 0.92)
        OlympusIsles.body(c, body)
        // Ausläufer nach Norden unter der Treppe und der oberen Terrasse.
        OlympusIsles.body(c, OlympusIsles.Body(cx, fy - 1, OlympusLayout.E_STAIRS_TOP_Z + 2, 14.0, IsleKind.MARBLE, 56, 0.88))
        bridgeHeads(c, isle)
        // Kampffläche: polierter Diorit, zwei Ringbänder, ein Läufer aus roter Terrakotta von Süd nach Nord.
        c.disc(cx, cz, arena + 0.5) { x, z, r ->
            val state = when {
                abs(x - cx) <= 1 -> s(Blocks.RED_TERRACOTTA)
                abs(x - cx) == 2 -> s(Blocks.CHISELED_QUARTZ_BLOCK)
                r > arena - 0.5 -> s(Blocks.CALCITE)
                r in 11.5..12.5 -> s(Blocks.QUARTZ_BRICKS)
                Kit.noise(x, fy, z) % 17 == 0 -> s(Blocks.CALCITE)
                else -> s(Blocks.POLISHED_DIORITE)
            }
            c.put(x, fy - 1, z, state)
            for (h in 0..30) c.put(x, fy + h, z, Kit.AIR)
        }
        // Die beiden Sockel (Bia links, rechts leer): Kanten aus Quarz, Ecken aus Gold.
        for (p in listOf(OlympusLayout.PLINTH_LEFT, OlympusLayout.PLINTH_RIGHT)) {
            for (ox in -1..1) for (oz in -1..1) {
                val corner = ox != 0 && oz != 0
                c.block(p.x + ox, p.y, p.z + oz, if (corner) s(Blocks.GOLD_BLOCK) else s(Blocks.SMOOTH_QUARTZ))
                c.block(p.x + ox, p.y + 1, p.z + oz, if (ox == 0 && oz == 0) s(Blocks.CHISELED_QUARTZ_BLOCK) else s(Blocks.QUARTZ_BRICKS))
            }
        }
        altar(c, isle.altar!!, 2)
        grandStairs(c, fy)
        // Kolonnade im Ring um die Fläche, offen nach Süden (Eingang) und nach Norden (Treppe).
        val ring = 28.5
        c.disc(cx, cz, 34.0) { x, z, r ->
            if (r <= arena + 0.5 || !body.rim.containsCell(x, z)) return@disc
            if (z < OlympusLayout.E_STAIRS_FOOT_Z && abs(x - cx) <= 12) return@disc
            c.put(x, fy - 1, z, if (r in 27.0..30.0) s(Blocks.SMOOTH_QUARTZ) else if (r > 33.0) s(Blocks.QUARTZ_BRICKS) else s(Blocks.POLISHED_DIORITE))
        }
        val colonnade = ArrayList<Double>()
        var deg = -58.0
        while (deg < 239.0) {
            if (angleDiff(deg, 90.0) > 9.0) colonnade.add(deg)
            deg += 11.5
        }
        for (d in colonnade) {
            val (px, pz) = polar(cx, cz, ring, d)
            column(c, px, fy, pz, 10)
        }
        c.disc(cx, cz, 30.5) { x, z, r ->
            if (r < 26.5) return@disc
            val a = angleOf(x - cx.toDouble(), z - cz.toDouble())
            if (a > 238.0 + 3 && a < 360.0 - 58.0 - 3) return@disc
            val y = fy + 10
            c.put(x, y, z, s(Blocks.SMOOTH_QUARTZ))
            c.put(x, y + 1, z, if (r in 27.5..29.5) s(Blocks.RED_TERRACOTTA) else s(Blocks.QUARTZ_BRICKS))
            c.put(x, y + 2, z, when {
                r > 29.8 -> stair(Blocks.SMOOTH_QUARTZ_STAIRS, Kit.inward(x - cx, z - cz), top = true)
                r < 27.2 -> stair(Blocks.SMOOTH_QUARTZ_STAIRS, Kit.inward(x - cx, z - cz).opposite, top = true)
                else -> s(Blocks.SMOOTH_QUARTZ)
            })
        }
        for ((i, d) in colonnade.withIndex()) {
            if (i % 2 == 1 || i + 1 >= colonnade.size) continue
            val (lx, lz) = polar(cx, cz, ring, (d + colonnade[i + 1]) / 2)
            c.block(lx, fy + 9, lz, Kit.lantern(hanging = true, soul = false))
        }
        // Ein Greif auf der Säule im Südosten, außerhalb der Kolonnade.
        griffinColumn(c, 178, fy, -545)
        rimBalustrade(c, body, fy, Blocks.DIORITE_WALL, s(Blocks.QUARTZ_PILLAR)) { x, z ->
            (x in 158..174 && z > cz + 22) || z < OlympusLayout.E_STAIRS_FOOT_Z + 6
        }
        // Roter Läufer in der Lücke der Kolonnade (außerhalb der Kampffläche, darum echte Teppiche).
        for (x in cx - 1..cx + 1) for (z in cz + 26..cz + 30) c.block(x, fy, z, s(Blocks.RED_CARPET))
        stele(c, 155, fy, -530, 12, Direction.SOUTH)
        OlympusIsles.cloudSkirt(c, body, 5)
        OlympusIsles.cloud(c, cx - 48, 160, cz + 16, 20.0, 12.0, 57, 3.0)
        OlympusIsles.cloud(c, cx + 42, 168, cz - 34, 16.0, 11.0, 58, 2.8)
    }

    /**
     * Die Freitreppe nach Norden: drei Läufe zu je vier Stufen mit zwei Absätzen, Wangen mit rotem Band, oben eine
     * Terrasse mit Säulenfront und Giebel (der Blick vom Platz), von deren Westende die Brücke zum Thron ausgeht.
     */
    private fun grandStairs(c: SkyCanvas, fy: Int) {
        val cx = OlympusLayout.E.center.x
        val foot = OlympusLayout.E_STAIRS_FOOT_Z
        val top = OlympusLayout.E_STAIRS_TOP_Z
        val levels = IntArray(foot - top) { 0 }
        var y = fy - 1
        for (k in levels.indices) {
            val step = k % 6 < 4
            if (step) y++
            levels[k] = y
        }
        val topFloor = levels.last()
        for (k in levels.indices) {
            val z = foot - 1 - k
            val step = k % 6 < 4
            val ly = levels[k]
            for (x in cx - 12..cx + 12) {
                val cheek = abs(x - cx) >= 11
                for (yy in fy - 2 until ly) c.block(x, yy, z, if (Kit.noise(x, yy, z) % 6 == 0) s(Blocks.CALCITE) else s(Blocks.QUARTZ_BRICKS))
                if (cheek) {
                    for (yy in ly..ly + 1) c.block(x, yy, z, if (yy == ly + 1) s(Blocks.RED_TERRACOTTA) else s(Blocks.QUARTZ_BRICKS))
                    c.block(x, ly + 2, z, if (abs(x - cx) == 12) stair(Blocks.SMOOTH_QUARTZ_STAIRS, if (x < cx) Direction.EAST else Direction.WEST, top = false) else slab(Blocks.SMOOTH_QUARTZ_SLAB))
                    continue
                }
                c.block(x, ly, z, if (step) stair(Blocks.QUARTZ_STAIRS, Direction.NORTH) else if (abs(x - cx) <= 1) s(Blocks.RED_TERRACOTTA) else s(Blocks.POLISHED_DIORITE))
                for (h in 1..6) c.block(x, ly + h, z, Kit.AIR)
            }
        }
        // Pylonen mit Laternen am Treppenfuß.
        for (sx in intArrayOf(-12, 11)) {
            for (x in cx + sx..cx + sx + 1) for (h in 0..4) c.block(x, fy + h, foot - 1, if (h == 4) s(Blocks.CHISELED_QUARTZ_BLOCK) else if (h == 2) s(Blocks.RED_TERRACOTTA) else s(Blocks.QUARTZ_BRICKS))
            c.block(cx + sx + (if (sx < 0) 0 else 1), fy + 5, foot - 1, Kit.lantern(hanging = false, soul = false))
        }
        // Obere Terrasse.
        val t0 = top - 1
        val t1 = top - 7
        for (x in 136..164) for (z in t1..t0) {
            for (yy in fy - 2 until topFloor) c.block(x, yy, z, s(Blocks.QUARTZ_BRICKS))
            c.block(x, topFloor, z, if (abs(x - cx) <= 1) s(Blocks.RED_TERRACOTTA) else if ((x + z) % 7 == 0) s(Blocks.CALCITE) else s(Blocks.POLISHED_DIORITE))
            for (h in 1..14) c.block(x, topFloor + h, z, Kit.AIR)
            val edgeN = z == t1
            val edgeE = x == 164
            if (edgeN || edgeE) c.block(x, topFloor + 1, z, if (Math.floorMod(x + z, 4) == 0) s(Blocks.QUARTZ_PILLAR) else wall(Blocks.DIORITE_WALL, alongX = edgeN))
        }
        // Säulenfront: fünf Säulen, Gebälk mit rotem Fries, Giebel nach Süden.
        val fz = t1 + 2
        for (x in intArrayOf(cx - 10, cx - 5, cx, cx + 5, cx + 10)) column(c, x, topFloor + 1, fz, 10)
        entablature(c, cx - 11, fz - 1, cx + 11, fz + 1, topFloor + 11, s(Blocks.RED_TERRACOTTA))
        pediment(c, cx, topFloor + 14, fz + 1, Direction.SOUTH, 12, 3)
        // Feuerschalen an den Enden der Terrasse.
        brazier(c, cx - 11, topFloor + 1, t0 - 1)
        brazier(c, cx + 11, topFloor + 1, t0 - 1)
    }

    // =============================================================================================
    // F  Thron des Zeus
    // =============================================================================================

    private fun isleF(c: SkyCanvas) {
        val isle = OlympusLayout.F
        val fy = isle.floorY
        val cx = isle.center.x
        val cz = isle.center.z
        val arena = isle.arenaRadius.toDouble()
        val body = OlympusIsles.Body(cx, fy - 1, cz, isle.radius.toDouble(), IsleKind.MARBLE, 66, 0.94)
        OlympusIsles.body(c, body)
        bridgeHeads(c, isle)
        // Kampffläche: polierter Diorit mit einem Hexagramm aus Kalkstein durch die sechs Pfeiler, Gold nur in der Mitte.
        val pillars = OlympusLayout.PILLARS
        c.disc(cx, cz, arena + 0.5) { x, z, r ->
            var star = false
            for (i in 0 until OlympusLayout.PILLAR_COUNT) {
                val a = pillars[i]
                val b = pillars[(i + 2) % OlympusLayout.PILLAR_COUNT]
                if (segmentDistance(x.toDouble(), z.toDouble(), a.x.toDouble(), a.z.toDouble(), b.x.toDouble(), b.z.toDouble()) < 0.62) star = true
            }
            val state = when {
                x == cx && z == cz -> s(Blocks.GOLD_BLOCK)
                r > arena - 0.5 -> s(Blocks.CHISELED_QUARTZ_BLOCK)
                star -> s(Blocks.CALCITE)
                r in 4.5..5.3 -> s(Blocks.QUARTZ_BRICKS)
                r in OlympusLayout.PILLAR_RING - 0.4..OlympusLayout.PILLAR_RING + 0.4 -> s(Blocks.QUARTZ_BRICKS)
                Kit.noise(x, fy, z) % 19 == 0 -> s(Blocks.CALCITE)
                else -> s(Blocks.POLISHED_DIORITE)
            }
            c.put(x, fy - 1, z, state)
            for (h in 0..22) c.put(x, fy + h, z, Kit.AIR)
        }
        // Die sechs Pfeiler: reine 1 × 1-Säulen aus gewachstem Kupfer, oben ein Blitzableiter.
        for (p in pillars) {
            for (h in 0 until OlympusLayout.PILLAR_HEIGHT) c.block(p.x, p.y + h, p.z, s(Blocks.WAXED_COPPER_BLOCK))
            c.block(p.x, p.y + OlympusLayout.PILLAR_HEIGHT, p.z, s(Blocks.WAXED_LIGHTNING_ROD))
        }
        altar(c, isle.altar!!, 3)
        // Säulenhof: Stylobat (eine Stufe), Ringhalle aus großen Säulen, Gebälk, kein Dach.
        c.disc(cx, cz, 44.5) { x, z, r ->
            if (r <= arena + 0.5 || !body.rim.containsCell(x, z)) return@disc
            val step = r in 32.5..33.3
            val stylobate = r > 33.3 && r <= 37.5
            when {
                step -> c.put(x, fy, z, stair(Blocks.SMOOTH_QUARTZ_STAIRS, Kit.inward(x - cx, z - cz).opposite))
                stylobate -> c.put(x, fy, z, if (r > 36.8) s(Blocks.CHISELED_QUARTZ_BLOCK) else s(Blocks.SMOOTH_QUARTZ))
            }
            c.put(x, fy - 1, z, if (r > 43.5) s(Blocks.QUARTZ_BRICKS) else if (Math.floorMod(Math.round(r).toInt(), 5) == 0) s(Blocks.CALCITE) else s(Blocks.POLISHED_DIORITE))
        }
        val columns = ArrayList<Double>()
        for (k in 0 until 24) {
            val d = k * 15.0
            if (angleDiff(d, 270.0) < 20.0 || angleDiff(d, 90.0) < 5.0) continue
            columns.add(d)
            val (px, pz) = polar(cx, cz, 35.0, d)
            column(c, px, fy + 1, pz, 13)
        }
        c.disc(cx, cz, 37.0) { x, z, r ->
            if (r < 33.0) return@disc
            val a = angleOf(x - cx.toDouble(), z - cz.toDouble())
            if (angleDiff(a, 270.0) < 13.0) return@disc
            val y = fy + 14
            c.put(x, y, z, s(Blocks.SMOOTH_QUARTZ))
            c.put(x, y + 1, z, if (r in 34.0..36.0 && columns.any { angleDiff(it, a) < 1.6 }) s(Blocks.QUARTZ_PILLAR) else if (r in 34.0..36.0) s(Blocks.CHISELED_QUARTZ_BLOCK) else s(Blocks.SMOOTH_QUARTZ))
            c.put(x, y + 2, z, when {
                r > 36.3 -> stair(Blocks.SMOOTH_QUARTZ_STAIRS, Kit.inward(x - cx, z - cz), top = true)
                r < 33.7 -> stair(Blocks.SMOOTH_QUARTZ_STAIRS, Kit.inward(x - cx, z - cz).opposite, top = true)
                else -> s(Blocks.SMOOTH_QUARTZ)
            })
        }
        // Südtor auf der Achse mit Giebel; davor zwei Wächterinnen zwischen Statuen.
        pediment(c, cx, fy + 17, cz + 37, Direction.SOUTH, 7, 4)
        for (x in cx - 2..cx + 2) for (z in cz + 31..cz + 45) {
            c.block(x, fy - 1, z, if (abs(x - cx) == 2) s(Blocks.CALCITE) else s(Blocks.POLISHED_DIORITE))
            c.block(x, fy, z, if (z in cz + 33..cz + 37) s(Blocks.SMOOTH_QUARTZ) else Kit.AIR)
        }
        for (side in intArrayOf(-1, 1)) {
            figure(c, cx + side * 13, fy, cz + 39, Direction.SOUTH, if (side < 0) Attr.SPEAR else Attr.SCEPTER)
            automatonPlinth(c, cx + side * 9, fy, cz + 39, MARBLE_LOOK)
            figure(c, cx + side * 5, fy, cz + 40, Direction.SOUTH, Attr.BOWL)
            c.block(cx + side * 3, fy + 17, cz + 37, s(Blocks.END_ROD))
        }
        throne(c, fy)
        // Gärten am Rand: Lorbeer und Zypressen in Beeten, zwei Säulen für Greife.
        for (k in 0 until 20) {
            val d = k * 18.0 + 9.0
            if (angleDiff(d, 90.0) < 14.0 || angleDiff(d, 270.0) < 22.0 || angleDiff(d, 33.0) < 10.0) continue
            val (tx, tz) = polar(cx, cz, 46.0, d)
            if (!body.rim.containsCell(tx, tz)) continue
            for (ox in -1..1) for (oz in -1..1) c.block(tx + ox, fy - 1, tz + oz, if (ox == 0 && oz == 0) s(Blocks.GRASS_BLOCK) else s(Blocks.MOSS_BLOCK))
            if (k % 2 == 0) OlympusIsles.cypress(c, tx, fy, tz, 9 + k % 3, k + 600) else OlympusIsles.bush(c, tx, fy, tz, k)
        }
        griffinColumn(c, -41, fy, -668)
        griffinColumn(c, 41, fy, -692)
        // Vier Götter in den Gärten, Blick nach außen: Athena, Hera, Apollon (Schale), Demeter (Füllhorn).
        figure(c, 29, fy, -651, Direction.SOUTH, Attr.SPEAR)
        figure(c, -29, fy, -651, Direction.SOUTH, Attr.SCEPTER)
        figure(c, -29, fy, -709, Direction.NORTH, Attr.BOWL)
        figure(c, 29, fy, -709, Direction.NORTH, Attr.CORNUCOPIA)
        rimBalustrade(c, body, fy, Blocks.DIORITE_WALL, s(Blocks.QUARTZ_PILLAR)) { x, z -> x in 34..50 && z in -664..-640 }
        stele(c, 30, fy, -657, 13, Direction.SOUTH)
        stormCloud(c, cx, fy + OlympusLayout.STORM_CLOUD_HEIGHT, cz)
        OlympusIsles.cloudSkirt(c, body, 6)
        OlympusIsles.cloud(c, cx - 56, 196, cz + 20, 22.0, 13.0, 67, 3.0)
        OlympusIsles.cloud(c, cx + 50, 204, cz - 30, 18.0, 12.0, 68, 3.0)
    }

    private fun segmentDistance(px: Double, pz: Double, ax: Double, az: Double, bx: Double, bz: Double): Double {
        val dx = bx - ax
        val dz = bz - az
        val t = (((px - ax) * dx + (pz - az) * dz) / (dx * dx + dz * dz)).coerceIn(0.0, 1.0)
        return hypot(px - (ax + dx * t), pz - (az + dz * t))
    }

    /**
     * Der Thron im Norden, außerhalb der Kampffläche: zwei Stufen, Sitz aus Quarztreppe, Lehne und Armlehnen aus Gold,
     * darüber der Adler mit ausgebreiteten Schwingen; dahinter eine halbrunde Exedra mit Nischen.
     */
    private fun throne(c: SkyCanvas, fy: Int) {
        val t = OlympusLayout.THRONE
        val tx = t.x
        val tz = t.z
        // Podest: vordere Kante als Stufe, alles frei darüber.
        for (x in tx - 7..tx + 7) for (z in tz - 8..tz + 3) {
            for (h in 1..16) c.block(x, fy + h, z, Kit.AIR)
            c.block(x, fy - 1, z, s(Blocks.QUARTZ_BRICKS))
            c.block(x, fy, z, if (z == tz + 3) stair(Blocks.QUARTZ_STAIRS, Direction.NORTH) else if (abs(x - tx) <= 1 && z >= tz - 1) s(Blocks.RED_TERRACOTTA) else s(Blocks.SMOOTH_QUARTZ))
        }
        for (x in tx - 3..tx + 3) for (z in tz - 4..tz - 1) c.block(x, fy + 1, z, if (z == tz - 1) stair(Blocks.QUARTZ_STAIRS, Direction.NORTH) else s(Blocks.CHISELED_QUARTZ_BLOCK))
        // Sitz, Armlehnen, Lehne.
        c.block(tx, t.y, tz, stair(Blocks.QUARTZ_STAIRS, Direction.NORTH))
        for (sx in intArrayOf(-1, 1)) {
            c.block(tx + sx, t.y, tz, s(Blocks.GOLD_BLOCK))
            c.block(tx + sx, t.y + 1, tz, slab(Blocks.SMOOTH_QUARTZ_SLAB))
        }
        for (x in tx - 1..tx + 1) for (h in 0..4) c.block(x, t.y + h, tz - 1, if (x == tx) s(Blocks.GOLD_BLOCK) else s(Blocks.CHISELED_QUARTZ_BLOCK))
        // Der Adler.
        val ey = t.y + 5
        for (x in tx - 4..tx + 4) {
            val ax = abs(x - tx)
            val rise = if (ax <= 1) 0 else (ax - 1) / 2
            c.block(x, ey + rise, tz - 1, if (ax == 4) stair(Blocks.QUARTZ_STAIRS, if (x < tx) Direction.EAST else Direction.WEST, top = true) else s(Blocks.CHISELED_QUARTZ_BLOCK))
            if (ax in 2..3) c.block(x, ey + rise - 1, tz - 1, stair(Blocks.SMOOTH_QUARTZ_STAIRS, if (x < tx) Direction.EAST else Direction.WEST, top = true))
        }
        c.block(tx, ey + 1, tz - 1, s(Blocks.CHISELED_QUARTZ_BLOCK))
        c.block(tx, ey + 2, tz - 1, s(Blocks.SMOOTH_QUARTZ))
        c.block(tx, ey + 2, tz, stair(Blocks.SMOOTH_QUARTZ_STAIRS, Direction.NORTH, top = true))
        c.block(tx, ey + 3, tz - 1, s(Blocks.GOLD_BLOCK))
        // Exedra: halbrunde Wand mit Nischen und Lichtstäben, oben ein Gesims.
        val ez = tz - 2
        c.disc(tx, ez, 10.5) { x, z, r ->
            if (z > ez || r < 8.5) return@disc
            for (h in 0..12) {
                val y = fy + h
                val niche = r < 9.5 && h in 2..5 && Math.floorMod(x, 4) == 1
                val state = when {
                    niche -> Kit.AIR
                    h == 12 -> stair(Blocks.SMOOTH_QUARTZ_STAIRS, Kit.inward(x - tx, z - ez).opposite, top = true).takeIf { r < 9.3 } ?: s(Blocks.SMOOTH_QUARTZ)
                    h == 11 -> s(Blocks.CHISELED_QUARTZ_BLOCK)
                    h == 6 -> s(Blocks.QUARTZ_PILLAR).with(Properties.AXIS, Direction.Axis.X)
                    r > 10.0 && Math.floorMod(x - tx, 4) == 0 -> s(Blocks.QUARTZ_PILLAR)
                    else -> if (Kit.noise(x, y, z) % 8 == 0) s(Blocks.SMOOTH_QUARTZ) else s(Blocks.QUARTZ_BRICKS)
                }
                c.block(x, y, z, state)
            }
            if (r < 9.5 && Math.floorMod(x, 4) == 1) c.block(x, fy + 2, z, s(Blocks.END_ROD))
        }
        // Zwei Kandelaber aus Lichtstäben neben dem Thron.
        for (sx in intArrayOf(-5, 5)) {
            c.block(tx + sx, fy + 1, tz - 2, s(Blocks.CHISELED_QUARTZ_BLOCK))
            for (h in 2..4) c.block(tx + sx, fy + h, tz - 2, wall(Blocks.DIORITE_WALL, alongX = true, post = true))
            c.block(tx + sx, fy + 5, tz - 2, s(Blocks.END_ROD))
        }
    }

    /** Flache Gewitterwolke über dem Hof (ab 24 über dem Boden): ein unregelmäßiger Ring aus grauem Beton, in der Mitte das Auge. */
    private fun stormCloud(c: SkyCanvas, cx: Int, y: Int, cz: Int) {
        c.disc(cx, cz, 36.0) { x, z, r ->
            val a = atan2((z - cz).toDouble(), (x - cx).toDouble())
            val outer = 24.0 + 4.0 * sin(a * 3.0 + 0.7) + 2.5 * sin(a * 7.0 + 2.1)
            val inner = 9.0 + 2.5 * sin(a * 5.0 + 1.3)
            if (r > outer || r < inner) return@disc
            // Lücken und Fetzen: Die Wolke soll ziehen, nicht wie ein Deckel liegen.
            val hole = Kit.noise(Math.floorDiv(x, 3), y, Math.floorDiv(z, 3)) % 100 < 16
            if (hole) return@disc
            val edge = minOf(outer - r, r - inner)
            val thick = when {
                edge < 1.5 -> 1
                edge < 5.0 -> 2
                else -> 2 + Kit.noise(x, y, z) % 2
            }
            for (k in 0 until thick) {
                val state = when {
                    k == 0 && thick > 1 -> s(Blocks.GRAY_CONCRETE)
                    k == thick - 1 -> s(Blocks.LIGHT_GRAY_CONCRETE)
                    else -> if (Kit.noise(x, y + k, z) % 4 == 0) s(Blocks.GRAY_CONCRETE) else s(Blocks.LIGHT_GRAY_CONCRETE)
                }
                c.put(x, y + k, z, state)
            }
        }
    }
}
