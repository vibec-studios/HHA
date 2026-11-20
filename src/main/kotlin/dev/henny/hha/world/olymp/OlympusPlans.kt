package dev.henny.hha.world.olymp

import dev.henny.hha.HhaBlocks
import dev.henny.hha.block.BossAltarBlock
import dev.henny.hha.block.SteleBlock
import dev.henny.hha.world.Kit
import dev.henny.hha.world.olymp.isles.IsleA
import dev.henny.hha.world.olymp.isles.IsleB
import dev.henny.hha.world.olymp.isles.IsleC
import dev.henny.hha.world.olymp.isles.IsleD
import dev.henny.hha.world.olymp.isles.IsleE
import dev.henny.hha.world.olymp.isles.IsleF
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
            'A' -> IsleA.draw(c)
            'B' -> IsleB.draw(c)
            'C' -> IsleC.draw(c)
            'D' -> IsleD.draw(c)
            'E' -> IsleE.draw(c)
            'F' -> IsleF.draw(c)
        }
    }

    /** Die Brückenstücke, die in dieser Insel liegen. */
    internal fun bridgeHeads(c: SkyCanvas, isle: OlympusLayout.Isle) {
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
}
