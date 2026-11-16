package dev.henny.hha.world

import dev.henny.hha.HhaBlocks
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.state.property.Properties

/**
 * Die Blockpaletten der Bauwerke, je Bauwerk eine eigene Leitfarbe (Plan 5.2). Alles Vanilla; eigene Blöcke nur dort,
 * wo sie eine Aufgabe haben (grünes Feuer, Siegel, Altar, Stele). Jede Palette entscheidet je Position, welcher Block
 * dort sitzt; so entstehen Flecken und Fugen statt glatter Flächen.
 */
object Palettes {

    private fun Block.s(): BlockState = defaultState
    private fun pick(x: Int, y: Int, z: Int, mod: Int) = Kit.noise(x, y, z) % mod

    /** Flecken über mehrere Blöcke (für Patina, Laufspuren), nicht Block für Block. */
    private fun patch(x: Int, y: Int, z: Int, size: Int, mod: Int) = Kit.noise(Math.floorDiv(x, size), Math.floorDiv(y, size), Math.floorDiv(z, size)) % mod

    // -----------------------------------------------------------------------------------------------------
    /** Bronzefeste: Hesiods eherne Mauer. Tiefschiefer, warme Bronze in Bändern, Torflügeln und Zinnenplatten. */
    object Fortress {
        val FACE: Pal = { x, y, z -> if (pick(x, y, z, 9) == 0) Blocks.DEEPSLATE_TILES.s() else Blocks.DEEPSLATE_BRICKS.s() }
        val WORN: Pal = { x, y, z -> if (pick(x, y, z, 3) == 0) Blocks.COBBLED_DEEPSLATE.s() else Blocks.CRACKED_DEEPSLATE_BRICKS.s() }
        val PLINTH: Pal = { _, _, _ -> Blocks.POLISHED_DEEPSLATE.s() }
        val PIER: Pal = { x, y, z -> if ((y and 1) == 0) Blocks.POLISHED_DEEPSLATE.s() else Blocks.DEEPSLATE_TILES.s() }

        /** Warme Bronze: geschnitten, gemeißelt, als Block; selten schon angelaufen. */
        val BRONZE: Pal = { x, y, z ->
            when (pick(x, y, z, 11)) {
                0 -> Blocks.WAXED_CHISELED_COPPER.s()
                1, 2 -> Blocks.WAXED_EXPOSED_CUT_COPPER.s()
                3 -> Blocks.WAXED_COPPER_BLOCK.s()
                else -> Blocks.WAXED_CUT_COPPER.s()
            }
        }

        /** Laufspuren unter Simsen: Hier hat das Wasser die Bronze grün gefärbt. */
        val STREAK: Pal = { _, _, _ -> Blocks.WAXED_WEATHERED_CUT_COPPER.s() }
        val DOOR: Pal = { x, y, z -> if ((y and 3) == 0) Blocks.WAXED_CHISELED_COPPER.s() else Blocks.WAXED_COPPER_BLOCK.s() }
        val FLOOR: Pal = { x, y, z -> if (pick(x, y, z, 13) == 0) Blocks.CRACKED_DEEPSLATE_TILES.s() else Blocks.DEEPSLATE_TILES.s() }
        val LANE: Pal = { _, _, _ -> Blocks.WAXED_EXPOSED_CUT_COPPER.s() }
        val RUBBLE: Pal = { _, _, _ -> Blocks.COBBLED_DEEPSLATE.s() }
        val LIGHT: BlockState get() = Blocks.WAXED_COPPER_BULB.defaultState.with(Properties.LIT, true)
        val LANTERN: BlockState get() = Kit.lantern(hanging = true, soul = false)

        val STYLE = WallStyle(
            face = FACE, worn = WORN, plinth = PLINTH, pier = PIER, band = BRONZE,
            plinthStair = Blocks.POLISHED_DEEPSLATE_STAIRS, cornice = Blocks.DEEPSLATE_BRICK_STAIRS,
            merlon = { _, _, _ -> Blocks.DEEPSLATE_BRICKS.s() }, merlonCap = Blocks.WAXED_CUT_COPPER_SLAB,
            rubbleStair = Blocks.COBBLED_DEEPSLATE_STAIRS, rubbleSlab = Blocks.COBBLED_DEEPSLATE_SLAB, streak = STREAK,
            merlonCapAlt = Blocks.DEEPSLATE_TILE_SLAB,
        )
    }

    // -----------------------------------------------------------------------------------------------------
    /** Titanenkerker: Tiefschiefer mit hellen Tuffbändern, Eisen in Gittern und Ketten, Seelenlaternen. */
    object Prison {
        val FACE: Pal = { x, y, z -> if (pick(x, y, z, 6) == 0) Blocks.COBBLED_DEEPSLATE.s() else Blocks.DEEPSLATE_BRICKS.s() }
        val WORN: Pal = { x, y, z -> if (pick(x, y, z, 2) == 0) Blocks.CRACKED_DEEPSLATE_BRICKS.s() else Blocks.CRACKED_DEEPSLATE_TILES.s() }
        val BAND: Pal = { x, y, z -> if (pick(x, y, z, 7) == 0) Blocks.CHISELED_TUFF_BRICKS.s() else Blocks.TUFF_BRICKS.s() }
        val PLINTH: Pal = { _, _, _ -> Blocks.POLISHED_DEEPSLATE.s() }
        val FLOOR: Pal = { x, y, z -> if (pick(x, y, z, 8) == 0) Blocks.CRACKED_DEEPSLATE_TILES.s() else Blocks.DEEPSLATE_TILES.s() }

        /** Grubenboden: Schutt und Seelenerde. */
        val PIT: Pal = { x, y, z ->
            when (patch(x, y, z, 3, 5)) {
                0, 1 -> Blocks.SOUL_SOIL.s()
                else -> if (pick(x, y, z, 4) == 0) Blocks.DEEPSLATE.s() else Blocks.COBBLED_DEEPSLATE.s()
            }
        }

        /** Kettenglied für die schweren Ketten: Tuff-Glieder im Wechsel mit Steinmauer. */
        val LINK_A: BlockState get() = Blocks.CHISELED_TUFF_BRICKS.defaultState
        val LINK_B: BlockState get() = Blocks.POLISHED_BLACKSTONE_WALL.defaultState
        /** Haut des Titanen: schwarz wie verbrannter Fels, damit er sich vom grauen Mauerwerk der Grube abhebt. */
        val SKIN: Pal = { x, y, z ->
            when {
                pick(x, y, z, 7) == 0 -> Blocks.BASALT.s()
                pick(x, y, z, 4) == 0 -> Blocks.POLISHED_BLACKSTONE.s()
                else -> Blocks.BLACKSTONE.s()
            }
        }
        val RUBBLE: Pal = { x, y, z -> if (pick(x, y, z, 4) == 0) Blocks.DEEPSLATE.s() else Blocks.COBBLED_DEEPSLATE.s() }
        val GLOW: BlockState get() = Blocks.MAGMA_BLOCK.defaultState

        val STYLE = WallStyle(
            face = FACE, worn = WORN, plinth = PLINTH, pier = BAND, band = BAND,
            plinthStair = Blocks.POLISHED_DEEPSLATE_STAIRS, cornice = Blocks.TUFF_BRICK_STAIRS,
            merlon = { _, _, _ -> Blocks.DEEPSLATE_BRICK_WALL.s() }, merlonCap = null,
            rubbleStair = Blocks.COBBLED_DEEPSLATE_STAIRS, rubbleSlab = Blocks.COBBLED_DEEPSLATE_SLAB, streak = null,
        )
    }

    // -----------------------------------------------------------------------------------------------------
    /** Stätte des Sisyphos: ein trockener Hügel aus Tuff, Andesit und Bruchstein; die Bahn ausgeschliffen. */
    object Sisyphus {
        val HILL: Pal = { x, y, z ->
            when (patch(x, y, z, 3, 7)) {
                0, 1 -> Blocks.ANDESITE.s()
                2 -> Blocks.COBBLESTONE.s()
                else -> Blocks.TUFF.s()
            }
        }
        val RUT: Pal = { x, y, z -> if (pick(x, y, z, 5) == 0) Blocks.BASALT.s() else Blocks.SMOOTH_BASALT.s() }
        val TRACK: Pal = { x, y, z -> if (pick(x, y, z, 6) == 0) Blocks.MUD_BRICKS.s() else Blocks.PACKED_MUD.s() }
        val SUMMIT: Pal = { x, y, z -> if (pick(x, y, z, 9) == 0) Blocks.ANDESITE.s() else Blocks.POLISHED_ANDESITE.s() }
        val SLIDE: Pal = { _, _, _ -> Blocks.GRAVEL.s() }
        val BOULDER: Pal = { x, y, z -> if (pick(x, y, z, 3) == 0) Blocks.DEEPSLATE.s() else Blocks.COBBLED_DEEPSLATE.s() }
        val EMBER: BlockState get() = Blocks.MAGMA_BLOCK.defaultState
        val CURB: BlockState get() = Blocks.COBBLESTONE_WALL.defaultState
    }

    // -----------------------------------------------------------------------------------------------------
    /** Stätte des Ixion: Netherziegel und Schwarzstein, das Rad rot-schwarz mit glühender Nabe. */
    object Ixion {
        val FACE: Pal = { x, y, z -> if (pick(x, y, z, 5) == 0) Blocks.CRACKED_NETHER_BRICKS.s() else Blocks.NETHER_BRICKS.s() }
        val TRIM: Pal = { x, y, z -> if (pick(x, y, z, 6) == 0) Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.s() else Blocks.POLISHED_BLACKSTONE_BRICKS.s() }
        val FLOOR: Pal = { x, y, z -> if (pick(x, y, z, 7) == 0) Blocks.POLISHED_BLACKSTONE.s() else Blocks.BLACKSTONE.s() }
        val RING: Pal = { _, _, _ -> Blocks.BASALT.s() }
        val RIM: Pal = { x, y, z ->
            when (pick(x, y, z, 7)) {
                0 -> Blocks.CRACKED_NETHER_BRICKS.s()
                1 -> Blocks.NETHER_BRICKS.s()
                else -> Blocks.RED_NETHER_BRICKS.s()
            }
        }
        val SPOKE: Pal = { x, y, z -> if (pick(x, y, z, 4) == 0) Blocks.RED_NETHER_BRICKS.s() else Blocks.COAL_BLOCK.s() }
        val HUB: Pal = { _, _, _ -> Blocks.MAGMA_BLOCK.s() }
        val FLAME: BlockState get() = Blocks.SHROOMLIGHT.defaultState
    }

    // -----------------------------------------------------------------------------------------------------
    /** Stätte des Tantalos: Steinziegel mit Moos, Prismarin am Beckenrand, ein Obstbaum mit Leuchtbeeren. */
    object Tantalus {
        val FACE: Pal = { x, y, z ->
            when (pick(x, y, z, 8)) {
                0, 1 -> Blocks.MOSSY_STONE_BRICKS.s()
                2 -> Blocks.CRACKED_STONE_BRICKS.s()
                else -> Blocks.STONE_BRICKS.s()
            }
        }
        val EDGE: Pal = { x, y, z -> if (pick(x, y, z, 4) == 0) Blocks.DARK_PRISMARINE.s() else Blocks.PRISMARINE_BRICKS.s() }
        val BED: Pal = { x, y, z -> if (patch(x, y, z, 2, 3) == 0) Blocks.GRAVEL.s() else Blocks.PRISMARINE.s() }
        val SHELF: Pal = { x, y, z -> if (pick(x, y, z, 3) == 0) Blocks.GRAVEL.s() else Blocks.MUD.s() }
        val WOOD: Pal = { _, _, _ -> Blocks.OAK_WOOD.s() }
        val LEAVES: Pal = { x, y, z ->
            (if (pick(x, y, z, 4) == 0) Blocks.FLOWERING_AZALEA_LEAVES else Blocks.AZALEA_LEAVES).defaultState.with(Properties.PERSISTENT, true)
        }
        val STONE: Pal = { x, y, z -> if (pick(x, y, z, 3) == 0) Blocks.COBBLESTONE.s() else Blocks.STONE.s() }
    }

    // -----------------------------------------------------------------------------------------------------
    /** Palast des Hades: Schwarzstein, Gold an den Kanten, smaragdgrünes Feuer. */
    object Palace {
        val FACE: Pal = { x, y, z ->
            when (pick(x, y, z, 100)) {
                in 0..64 -> Blocks.POLISHED_BLACKSTONE_BRICKS.s()
                in 65..82 -> Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.s()
                else -> Blocks.BLACKSTONE.s()
            }
        }
        val WORN: Pal = { x, y, z -> if (pick(x, y, z, 3) == 0) Blocks.BLACKSTONE.s() else Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.s() }
        val PLINTH: Pal = { x, y, z -> if (pick(x, y, z, 5) == 0) Blocks.DEEPSLATE_TILES.s() else Blocks.POLISHED_DEEPSLATE.s() }
        val TRIM: Pal = { x, y, z -> if (pick(x, y, z, 4) == 0) Blocks.GILDED_BLACKSTONE.s() else Blocks.CHISELED_POLISHED_BLACKSTONE.s() }
        val PIER: Pal = { _, _, _ -> Blocks.POLISHED_BLACKSTONE.s() }
        val SHAFT: Pal = { _, y, _ -> if ((y and 7) == 0) Blocks.CHISELED_POLISHED_BLACKSTONE.s() else Blocks.POLISHED_BASALT.s() }

        /** Kuppelrippen: das einzige Kupfer am Palast. */
        val RIB: Pal = { x, y, z -> if (pick(x, y, z, 17) == 0) Blocks.WAXED_WEATHERED_CUT_COPPER.s() else Blocks.WAXED_OXIDIZED_CUT_COPPER.s() }
        val GOLD: BlockState get() = Blocks.GOLD_BLOCK.defaultState
        val ROCK: Pal = { x, y, z -> if (pick(x, y, z, 5) == 0) Blocks.BASALT.s() else Blocks.BLACKSTONE.s() }

        val STYLE = WallStyle(
            face = FACE, worn = WORN, plinth = PLINTH, pier = PIER, band = TRIM,
            plinthStair = Blocks.POLISHED_DEEPSLATE_STAIRS, cornice = Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS,
            merlon = { _, _, _ -> Blocks.POLISHED_BLACKSTONE_BRICKS.s() }, merlonCap = Blocks.POLISHED_BLACKSTONE_BRICK_SLAB,
            rubbleStair = Blocks.BLACKSTONE_STAIRS, rubbleSlab = Blocks.BLACKSTONE_SLAB, streak = null, pierDepth = 2,
        )
        val STATUE = StatueStyle()
    }

    // -----------------------------------------------------------------------------------------------------
    /** Hades-Halle des Fährmanns: Schwarzstein, Basaltrippen, Prismarin am Ufer des Styx, eine goldene Münze im Boden. */
    object Hall {
        val SHELL: Pal = { x, y, z -> if (pick(x, y, z, 16) == 0) Blocks.CRYING_OBSIDIAN.s() else Blocks.BLACKSTONE.s() }
        val WALL: Pal = { x, y, z -> if (pick(x, y, z, 5) == 0) Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.s() else Blocks.POLISHED_BLACKSTONE_BRICKS.s() }
        val RIB: Pal = { _, _, _ -> Blocks.POLISHED_BASALT.s() }
        val SHORE: Pal = { _, _, _ -> Blocks.DARK_PRISMARINE.s() }
        val COIN: BlockState get() = Blocks.GILDED_BLACKSTONE.defaultState
        val TRIM: Pal = Palace.TRIM
    }

    // -----------------------------------------------------------------------------------------------------
    /** Olymp-Tempel: heller Marmor, Gold nur an den Akroterien, ein Dach aus Bronze. */
    object Temple {
        val BASE: Pal = { x, y, z -> if (pick(x, y, z, 7) == 0) Blocks.CALCITE.s() else Blocks.QUARTZ_BRICKS.s() }
        val SMOOTH: Pal = { x, y, z -> if (pick(x, y, z, 23) == 0) Blocks.DIORITE.s() else Blocks.SMOOTH_QUARTZ.s() }
        val FLOOR: Pal = { _, _, _ -> Blocks.POLISHED_DIORITE.s() }
        val BAND: Pal = { _, _, _ -> Blocks.CALCITE.s() }
        val ROOF_STAIR: Block get() = Blocks.WAXED_CUT_COPPER_STAIRS
        val ROOF_SLAB: Block get() = Blocks.WAXED_CUT_COPPER_SLAB
        val ROOF: BlockState get() = Blocks.WAXED_CUT_COPPER.defaultState
        val GOLD: BlockState get() = Blocks.GOLD_BLOCK.defaultState
        val LIGHT: BlockState get() = Blocks.PEARLESCENT_FROGLIGHT.defaultState
    }

    // -----------------------------------------------------------------------------------------------------
    /** Ruinen: Reste einer älteren, hellen Welt. */
    object Ruin {
        val FLOOR: Pal = { x, y, z -> if (pick(x, y, z, 3) == 0) Blocks.CRACKED_STONE_BRICKS.s() else Blocks.STONE_BRICKS.s() }
        val BORDER: Pal = { x, y, z -> if (pick(x, y, z, 2) == 0) Blocks.CHISELED_STONE_BRICKS.s() else Blocks.POLISHED_DIORITE.s() }
        val BROKEN: Pal = { x, y, z -> if (pick(x, y, z, 2) == 0) Blocks.DIORITE.s() else Blocks.CALCITE.s() }
        val STATUE = StatueStyle(
            plinth = Blocks.POLISHED_DIORITE.defaultState, base = Blocks.CHISELED_STONE_BRICKS.defaultState,
            body = Blocks.CALCITE.defaultState, robe = Blocks.DIORITE.defaultState, face = Blocks.POLISHED_DIORITE.defaultState,
            plinthStair = Blocks.POLISHED_DIORITE_STAIRS, hood = Blocks.DIORITE_STAIRS, bowl = null, flame = null,
        )
    }
}

/** Woraus eine Wand mit Tiefe besteht: Fläche, Verwitterung, Sockel, Pfeiler, Gesims, Zinnen und der Schutt am Fuß. */
class WallStyle(
    val face: Pal,
    val worn: Pal,
    val plinth: Pal,
    val pier: Pal,
    val band: Pal?,
    val plinthStair: Block,
    val cornice: Block,
    val merlon: Pal,
    val merlonCap: Block?,
    val rubbleStair: Block,
    val rubbleSlab: Block,
    val streak: Pal?,
    /** Tiefe der Pfeiler: 1 = Lisene, 2 = Strebepfeiler mit Schräge. */
    val pierDepth: Int = 1,
    /** Abdeckung jeder zweiten Zinne auf Mauern, damit die Krone nicht gleichförmig wirkt. */
    val merlonCapAlt: Block? = null,
)

/** Woraus eine Statue ist; ohne [flame] hält sie keine Feuerschale. */
class StatueStyle(
    val plinth: BlockState = Blocks.POLISHED_DEEPSLATE.defaultState,
    val base: BlockState = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultState,
    val body: BlockState = Blocks.POLISHED_BASALT.defaultState,
    val robe: BlockState = Blocks.BLACKSTONE.defaultState,
    val face: BlockState = Blocks.CRYING_OBSIDIAN.defaultState,
    val plinthStair: Block = Blocks.POLISHED_DEEPSLATE_STAIRS,
    val hood: Block = Blocks.BLACKSTONE_STAIRS,
    val bowl: BlockState? = Blocks.GILDED_BLACKSTONE.defaultState,
    val flame: BlockState? = HhaBlocks.GREEN_FIRE.defaultState,
)
