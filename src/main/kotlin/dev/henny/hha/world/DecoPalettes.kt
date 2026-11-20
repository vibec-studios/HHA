package dev.henny.hha.world

import dev.henny.hha.HhaDecoBlocks
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks

/**
 * Standard-Mischungen aus Deko-Blöcken und Vanilla (Überarbeitung 4, PLAN 7.12; P00, eingefroren). Vanilla bleibt
 * Grundmaterial (Leitlinie 23): Jede Palette setzt die Deko-Blöcke als ruhige Fläche und mischt Vanilla-Töne in
 * Flecken ein; die Verwitterung (`wear`, Anteil 0–1) ersetzt Steine durch rissige bzw. grobe. Alle Paletten sind wie
 * [Palettes] aus der Position abgeleitet, also chunkübergreifend gleich.
 *
 * Nutzung: `c.prism(…, DecoPalettes.MARBLE_WALL)`, oder mit eigener Verwitterung `DecoPalettes.marbleWall(0.3)`.
 */
object DecoPalettes {

    private fun s(b: Block): BlockState = b.defaultState
    private fun pick(x: Int, y: Int, z: Int, mod: Int) = Kit.noise(x, y, z) % mod
    private fun patch(x: Int, y: Int, z: Int, size: Int, mod: Int) = Kit.noise(Math.floorDiv(x, size), Math.floorDiv(y, size), Math.floorDiv(z, size)) % mod
    private fun worn(x: Int, y: Int, z: Int, amount: Double) = amount > 0.0 && Kit.noise(x + 7, y + 13, z + 29) % 1000 < amount * 1000

    /** Marmorwand: Olymp-Marmorziegel, in Flecken Quarzziegel und Naturmarmor; verwittert zu Diorit und Kalkstein. */
    fun marbleWall(wear: Double = 0.08): Pal = { x, y, z ->
        when {
            worn(x, y, z, wear) -> if (pick(x, y, z, 2) == 0) s(Blocks.DIORITE) else s(Blocks.CALCITE)
            patch(x, y, z, 3, 7) == 0 -> s(Blocks.QUARTZ_BRICKS)
            pick(x, y, z, 13) == 0 -> s(HhaDecoBlocks.OLYMPUS_MARBLE)
            else -> s(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS)
        }
    }

    /** Marmorboden: polierter Olymp-Marmor, dazwischen glatter Quarz; verwittert zu poliertem Diorit. */
    fun marbleFloor(wear: Double = 0.05): Pal = { x, y, z ->
        when {
            worn(x, y, z, wear) -> s(Blocks.POLISHED_DIORITE)
            patch(x, y, z, 4, 6) == 0 -> s(Blocks.SMOOTH_QUARTZ)
            else -> s(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE)
        }
    }

    /** Basaltwand: Tartaros-Basaltziegel, in Flecken polierte Schwarzsteinziegel; verwittert zu rissigen Ziegeln. */
    fun basaltWall(wear: Double = 0.10): Pal = { x, y, z ->
        when {
            worn(x, y, z, wear) -> if (pick(x, y, z, 2) == 0) s(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS) else s(Blocks.BLACKSTONE)
            patch(x, y, z, 3, 6) == 0 -> s(Blocks.POLISHED_BLACKSTONE_BRICKS)
            pick(x, y, z, 15) == 0 -> s(HhaDecoBlocks.TARTARUS_BASALT)
            else -> s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS)
        }
    }

    /** Basaltboden: polierter Tartaros-Basalt, dazwischen Tiefschieferfliesen; verwittert zu rissigen Fliesen. */
    fun basaltFloor(wear: Double = 0.06): Pal = { x, y, z ->
        when {
            worn(x, y, z, wear) -> s(Blocks.CRACKED_DEEPSLATE_TILES)
            patch(x, y, z, 4, 5) == 0 -> s(Blocks.DEEPSLATE_TILES)
            else -> s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT)
        }
    }

    /** Bronze des Olymp: Olymp-Bronze, selten gewachstes Schnittkupfer; verwittert zu angelaufenem Kupfer. */
    fun bronze(wear: Double = 0.06): Pal = { x, y, z ->
        when {
            worn(x, y, z, wear) -> s(Blocks.WAXED_EXPOSED_CUT_COPPER)
            pick(x, y, z, 9) == 0 -> s(Blocks.WAXED_CUT_COPPER)
            else -> s(HhaDecoBlocks.OLYMPUS_BRONZE)
        }
    }

    /**
     * Dunkle Bronze des Tartaros: Tartaros-Bronze, selten und an verwitterten Stellen verwittertes Kupfer. Kein
     * oxidiertes Kupfer: Das helle Türkis stach neben der olivgrünen Bronze als Fleck heraus (PZ1, Wunsch P01 3, Beleg
     * `tmp/overhaul4/previews/PZ1/dark_bronze.png`).
     */
    fun darkBronze(wear: Double = 0.08): Pal = { x, y, z ->
        when {
            worn(x, y, z, wear) -> s(Blocks.WAXED_WEATHERED_CUT_COPPER)
            pick(x, y, z, 9) == 0 -> s(Blocks.WAXED_WEATHERED_CUT_COPPER)
            else -> s(HhaDecoBlocks.TARTARUS_BRONZE)
        }
    }

    val MARBLE_WALL: Pal = marbleWall()
    val MARBLE_FLOOR: Pal = marbleFloor()
    val BASALT_WALL: Pal = basaltWall()
    val BASALT_FLOOR: Pal = basaltFloor()
    val BRONZE: Pal = bronze()
    val DARK_BRONZE: Pal = darkBronze()
}
