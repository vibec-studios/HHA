package dev.henny.hha.world

import dev.henny.hha.HhaBlocks
import dev.henny.hha.block.SteleBlock
import dev.henny.hha.world.sites.FortressPlan
import dev.henny.hha.world.sites.IxionPlan
import dev.henny.hha.world.sites.PrisonPlan
import dev.henny.hha.world.sites.SisyphusPlan
import dev.henny.hha.world.sites.TantalusPlan
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Die Baupläne des Tartaros. Alles ist deterministisch aus der Position abgeleitet, also passen benachbarte Chunks zusammen.
 * Jedes Bauwerk hat eine eigene Leitfarbe und Form (siehe [Palettes]); die Maße, auf die die Kämpfe zählen, stehen hier.
 */
object TartarusPlans {

    // --- Palast: Maße, auf die der Hades-Kampf zählt (unverändert) -------------------------------------------

    /** Das Siegel steht so weit südlich der Hallenmitte, in der Dicke der Hallenwand. */
    const val SEAL_Z = 47
    const val SEAL_HALF_WIDTH = 5
    const val SEAL_HEIGHT = 15

    /** Der Thron, von der Hallenmitte aus nach Norden. */
    const val THRONE_Z = -36

    /** Stufen des Podests: So viel höher stehen Hades' Füße auf dem Thron als die Spieler im Saal. */
    const val DAIS_HEIGHT = 3

    /** Bis zu dieser Höhe reißt der Einsturz alles mit, was auf dem Boden steht. */
    const val DAIS_CLEAR_HEIGHT = 30

    /** Die acht Deckungssäulen des Kampfes. */
    const val PILLAR_RADIUS = 2
    const val PILLAR_HEIGHT = 29

    /** Höhe des Kronleuchters über dem Saalboden. */
    const val CHANDELIER_Y = 40

    /** Innenseite der Kuppel ganz oben: Hier sitzt das Auge, durch das Kronos kommt. */
    const val OCULUS_Y = 63

    /** Radius des offenen Auges oben in der Kuppel: Dort bricht Kronos durch. */
    const val EYE = 6.5

    internal const val PILLAR_RING = 27.0
    internal const val HALL = 46
    internal const val DRUM = 50
    internal const val DRUM_TOP = 30
    internal const val DOME_RISE = 36
    internal const val COURT = 84

    // --- Wächterstätten: Maße, auf die die Wächterkämpfe zählen ----------------------------------------------

    /** Halbe Seitenlänge des Hofs der Bronzefeste (Mittellinie der Hauptmauer). */
    const val FORTRESS_COURT = 30

    /** Radius und Tiefe der Grube im Titanenkerker; der Grubenboden liegt [PRISON_PIT_DEPTH] unter der Mitte. */
    const val PRISON_PIT_RADIUS = 24
    const val PRISON_PIT_DEPTH = 24

    /** Der Hügel des Sisyphos: so hoch über dem Fuß, mit einem ebenen Gipfel dieses Radius. */
    const val SISYPHUS_HEIGHT = 14
    const val SISYPHUS_SUMMIT = 9
    const val SISYPHUS_TRACK_WIDTH = 5
    const val SISYPHUS_BOULDER = 4

    /** Plattform des Ixion und Becken des Tantalos (Radien ab der Mitte). */
    const val IXION_PLATFORM = 19
    const val TANTALUS_BASIN = 16

    internal const val HILL_R = 27.0
    internal const val TRACK_FROM = 25.0
    internal const val TRACK_TO = 10.0
    internal const val TRACK_TURNS = 1.25

    /** Rundbogen des Tors: [dy] zählt vom Hallenboden aufwärts. */
    fun inSealOpening(dx: Int, dy: Int): Boolean = abs(dx) <= SEAL_HALF_WIDTH && dy >= 1 && (dy <= 10 || hypot(dx.toDouble(), dy - 10.0) <= SEAL_HALF_WIDTH + 0.4)

    /** Versatz der Deckungssäulen zur Hallenmitte. */
    fun coverPillars(): List<Pair<Int, Int>> = List(8) { i ->
        val a = i * Math.PI / 4.0 + Math.PI / 8.0
        Pair(Math.round(cos(a) * PILLAR_RING).toInt(), Math.round(sin(a) * PILLAR_RING).toInt())
    }

    /**
     * Alte Streuformel: welche Strafstätte an dieser Stelle steht (0 Sisyphos, 1 Ixion, 2 Tantalos). Gilt nur noch für
     * Bauwerke alter Welten, die ihre Variante nicht gespeichert haben (siehe [TartarusPiece.siteVariant]).
     */
    fun siteVariant(o: BlockPos): Int = Math.floorMod(o.x / 16 * 31 + o.z / 16 * 17, 3)

    /** Rasterzelle der Strafstätten in Chunks, wie `spacing` in `structure_set/punishment_sites.json`. */
    const val SITE_CELL = 22

    /**
     * Variante neuer Strafstätten (Überarbeitung 2, PLAN 5.7) aus der Rasterzelle ihres Start-Chunks:
     * `floorMod(cellX + 2 · cellZ, 3)`. So sind Nachbarn in Ost-West- und Nord-Süd-Richtung immer verschieden.
     */
    fun cellVariant(chunkX: Int, chunkZ: Int): Int =
        Math.floorMod(Math.floorDiv(chunkX, SITE_CELL) + 2 * Math.floorDiv(chunkZ, SITE_CELL), 3)

    // --- Sisyphos: Werte für den Wächterkampf ------------------------------------------------------------

    /** Höhe des Hügels über dem Fuß bei Abstand [r] zur Mitte (auf ebenem Grund). */
    fun sisyphusHill(r: Double): Double = when {
        r <= SISYPHUS_SUMMIT -> SISYPHUS_HEIGHT.toDouble()
        r >= HILL_R -> 0.0
        else -> SISYPHUS_HEIGHT * (0.5 + 0.5 * cos(PI * (r - SISYPHUS_SUMMIT) / (HILL_R - SISYPHUS_SUMMIT)))
    }

    internal fun trackStart(o: BlockPos): Double = (Kit.noise(o.x shr 4, 5, o.z shr 4) % 360) * PI / 180.0

    /**
     * Ein Punkt auf der Mittellinie der Bahn: [t] = 0 am Fuß, 1 am Gipfelrand. Liefert die Mitte des Blocks, auf dem man
     * steht (Lauffläche + 1). Die Bahn ist [SISYPHUS_TRACK_WIDTH] Blöcke breit und quer dazu eben.
     */
    fun sisyphusTrack(o: BlockPos, t: Double): Vec3d {
        val r = TRACK_FROM + (TRACK_TO - TRACK_FROM) * t
        val a = trackStart(o) + t * TRACK_TURNS * 2.0 * PI
        return Vec3d(o.x + cos(a) * r + 0.5, o.y + sisyphusHill(r).roundToInt() + 1.0, o.z + sin(a) * r + 0.5)
    }

    /** Wo der Fels liegt (0..1 auf der Bahn): je Welt-Position eine andere Stelle. */
    fun sisyphusBoulderT(o: BlockPos): Double = 0.15 + (Kit.noise(o.x shr 4, 9, o.z shr 4) % 1000) / 1000.0 * 0.45

    /** Mitte des Felsens auf der Bahn. */
    fun sisyphusBoulder(o: BlockPos): BlockPos {
        val p = sisyphusTrack(o, sisyphusBoulderT(o))
        return BlockPos.ofFloored(p.x, p.y - 1 + SISYPHUS_BOULDER, p.z)
    }

    /** Standplatz auf dem Gipfel (Mitte des Plateaus, Füße). */
    fun sisyphusSummit(o: BlockPos): Vec3d = Vec3d(o.x + 0.5, o.y + SISYPHUS_HEIGHT + 1.0, o.z + 0.5)

    internal fun stele(story: Int): BlockState = HhaBlocks.STELE.defaultState.with(SteleBlock.STORY, story)

    /**
     * Zeichnet ein Bauwerk. [variant] ist die gespeicherte Variante einer Strafstätte ([TartarusPiece.siteVariant]);
     * ohne Angabe (Prüflauf, alte Bauwerke) gilt die alte Streuformel [siteVariant].
     */
    fun draw(c: BlockCanvas, kind: TartarusKind, center: BlockPos, variant: Int = -1) {
        when (kind) {
            TartarusKind.PALACE -> PalacePlan.draw(c, center)
            TartarusKind.FORTRESS -> FortressPlan.draw(c, center)
            TartarusKind.PRISON -> PrisonPlan.draw(c, center)
            TartarusKind.PUNISHMENT -> when (if (variant in 0..2) variant else siteVariant(center)) {
                0 -> SisyphusPlan.draw(c, center)
                1 -> IxionPlan.draw(c, center)
                else -> TantalusPlan.draw(c, center)
            }
        }
    }
}
