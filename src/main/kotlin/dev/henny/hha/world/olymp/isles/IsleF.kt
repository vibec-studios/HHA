package dev.henny.hha.world.olymp.isles

import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.world.Kit
import dev.henny.hha.world.block
import dev.henny.hha.world.olymp.IsleKind
import dev.henny.hha.world.olymp.OlympKit.Attr
import dev.henny.hha.world.olymp.OlympKit.MARBLE_LOOK
import dev.henny.hha.world.olymp.OlympKit.altar
import dev.henny.hha.world.olymp.OlympKit.angleDiff
import dev.henny.hha.world.olymp.OlympKit.angleOf
import dev.henny.hha.world.olymp.OlympKit.automatonPlinth
import dev.henny.hha.world.olymp.OlympKit.column
import dev.henny.hha.world.olymp.OlympKit.figure
import dev.henny.hha.world.olymp.OlympKit.griffinColumn
import dev.henny.hha.world.olymp.OlympKit.pediment
import dev.henny.hha.world.olymp.OlympKit.polar
import dev.henny.hha.world.olymp.OlympKit.rimBalustrade
import dev.henny.hha.world.olymp.OlympKit.s
import dev.henny.hha.world.olymp.OlympKit.slab
import dev.henny.hha.world.olymp.OlympKit.stair
import dev.henny.hha.world.olymp.OlympKit.stele
import dev.henny.hha.world.olymp.OlympusArenas
import dev.henny.hha.world.olymp.OlympusIsles
import dev.henny.hha.world.olymp.OlympusLayout
import dev.henny.hha.world.olymp.OlympusPlans.bridgeHeads
import dev.henny.hha.world.olymp.SkyCanvas
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.HorizontalFacingBlock
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Bauplan der Insel F, Thron des Zeus (Überarbeitung 4, P09; aus `OlympusPlans` herausgelöst von P00). Aufruf über
 * `OlympusPlans.draw`.
 *
 * Aufbau von innen nach außen (Maße aus dem Vertrag `OlympusArenas.F`, PLAN 3.5):
 * - **Kern** r ≤ 19,5 auf y 232: polierter Olymp-Marmor, Hexagramm aus Kalkstein mit gemeißelten Knoten, Gold nur in der
 *   Mitte, Sturmband r 18 … 19,5 als Leitlinie zur Terrasse.
 * - **Sechs Schollen** r 20 … 30 auf +2: je ein eigener Körper (höchstens 6 Fels unter dem Belag), Fugen aus
 *   Sturmmarmor/Tiefenschiefer zur Nachbarscholle und zur Bühne, Goldkante zum Kern und zu den Gassen. Unter den
 *   Schollen ist die Insel ausgehöhlt (Sturmwolken, darunter frei): Was abbricht, fällt ins Freie.
 * - **Blitzpfeiler** 3 × 3 auf Ring 21 (Bronzeband, Sturmsockel, Säulenmantel, Kupferkern, Ableiter, Krone) und je ein
 *   **Erdungsgriff** in der Terrassenwand.
 * - **Südgasse** (82° … 98°) auf Kernhöhe, **Thronaufgang** (262° … 278°) mit drei Stufen zur **Thronbühne** (+3).
 * - Ringhalle mit betonten Jochen an den Pfeilerachsen, Südtor, Gärten, Statuen; gegliederte Klippe; **Gewitterkranz**
 *   aus `storm_cloud` außerhalb der Arena statt des früheren Beton-Deckels.
 *
 * Bruchregel (Vertrag): Alles in der Zone einer Scholle von y 226 bis 243 bricht mit ihr. Kern, Gassen, Bühne (soweit
 * der Vertrag sie nicht in die Schollen legt, siehe `tmp/overhaul4/requests/P09.md`), Ringhalle und Treppen ruhen auf
 * eigenem Grund.
 */
internal object IsleF {

    // =============================================================================================
    // Maße (alles, was der Kampf liest, steht in OlympusArenas.F und OlympusLayout)
    // =============================================================================================

    /** Sturmband am Kernrand: Felder mit r über diesem Wert (bis 19,5). */
    private const val CORE_BAND = 18.0
    /** Gemeißelter Zierring um die Goldmitte. */
    private const val CORE_RING_FROM = 4.5
    private const val CORE_RING_TO = 5.3
    /** Halbe Strichbreite des Hexagramms und Radius der gemeißelten Knoten (Schnittpunkte, r = 21 / √3). */
    private const val STAR_LINE = 0.62
    private const val STAR_NODE = 1.3
    /** Luft über Kern, Schollen und Gassen. Reicht über +24 hinaus, damit der alte Beton-Deckel (+24 … +26) in alten Welten verschwindet. */
    private const val CLEAR_ABOVE = 28

    /** Fels unter dem Schollenbelag (Belag = y 233): innen und unter den Pfeilern voll, außen 4 … 6. */
    private const val FLOE_DEPTH = 6
    private const val FLOE_DEPTH_THIN = 4
    private const val FLOE_FULL_RING = 22.5
    /** Sturmwolke unter jeder Scholle: bis zu so viele Lagen, etwa die Hälfte der Felder. */
    private const val FLOE_CLOUD_LAYERS = 3
    private const val FLOE_CLOUD_SHARE = 55

    /** Bühne und Thronhof: Körper ab dieser Höhe unter dem Kern (wie die Scholle), Licht-Einlagen im Bühnenboden. */
    private const val STAGE_BODY_BELOW = 6
    private const val STAGE_LIGHT_X = 7
    private val STAGE_LIGHT_DZ = intArrayOf(-25, -31)
    /** Läufer (rote Terrakotta) auf der Bühne: halbe Breite. */
    private const val RUNNER_HALF = 1

    /** Ringhalle: Säulen alle 15° auf r 35, 13 hoch; an den sechs Pfeilerachsen eine Doppelsäule im Abstand ±[YOKE_SPREAD]°. */
    private const val HALL_RADIUS = 35.0
    private const val HALL_COLUMN_HEIGHT = 13
    private const val YOKE_SPREAD = 4.5
    /** Das Gebälk bleibt über der Thronbühne offen (±17° um Norden, die Bühne reicht bis ±16,9°). */
    private const val HALL_OPEN_NORTH = 17.0

    /** Thron: Lehne 12 hoch über dem Sitz, Adler darüber, Blitzbündel auf zwei Pfosten seitlich. */
    private const val THRONE_BACK = 12
    private const val THRONE_DAIS_HALF = 4
    private const val BUNDLE_X = 6
    /** Exedra: Mitte zwei hinter dem Sitz, Wand r 8,5 … 10,5, bis 12 über der Bühne. */
    private const val EXEDRA_INNER = 8.5
    private const val EXEDRA_OUTER = 10.5
    private const val EXEDRA_TOP = 12

    /**
     * Gewitterkranz (PLAN 3.5): Ring r 36 … 50 bei +30 … +33, 1 … 3 dick. Schwelle des weichen Rauschens: 0,5 ergibt
     * nachgemessen 41 % Wolke, also rund 60 % Lücken; je [CROWN_LAYER] über der Schwelle eine Lage mehr.
     */
    private const val CROWN_INNER = 36.0
    private const val CROWN_OUTER = 50.0
    private const val CROWN_FROM = 30
    private const val CROWN_TO = 33
    private const val CROWN_THRESHOLD = 0.5
    private const val CROWN_LAYER = 0.08

    /** Klippe: Vorsprünge (Kragsteine) an etwa jedem elften Feld der Lagenkanten. */
    private const val CLIFF_CORBEL = 11

    /** Streifen um den Brückenanschluss von E (Kopftor, P06): F zeichnet dort nichts Neues. */
    private fun bridgeStrip(x: Int, z: Int): Boolean = x in 34..50 && z in -670..-640

    // =============================================================================================
    // Zonen der Arena
    // =============================================================================================

    private enum class Area { CORE, FLOE, SOUTH_WAY, THRONE_WAY, STAGE, OUTSIDE }

    private enum class Edge { NONE, INNER, FLANK, OUTER }

    private val floeZones by lazy { OlympusArenas.F_FLOES.indices.map { OlympusArenas.F.part("floe_$it").zone } }

    /** Scholle der Spalte laut Vertrag (Teil `floe_i`), −1 = keine. */
    private fun floeOf(x: Int, z: Int): Int = floeZones.indexOfFirst { it.containsCell(x, z) }

    /** Wozu gehört die Spalte? Die Bühne geht vor (sie darf nicht auf einer Scholle ruhen). */
    private fun area(x: Int, z: Int): Area {
        val c = OlympusLayout.F.center
        val dx = (x - c.x).toDouble()
        val dz = (z - c.z).toDouble()
        val r = hypot(dx, dz)
        return when {
            r <= OlympusArenas.F_CORE -> Area.CORE
            OlympusArenas.F_STAGE.containsCell(x, z) -> Area.STAGE
            r > OlympusArenas.F_TERRACE_OUTER + 0.5 -> Area.OUTSIDE
            floeOf(x, z) >= 0 -> Area.FLOE
            angleDiff(angleOf(dx, dz), 90.0) < 90.0 -> Area.SOUTH_WAY
            else -> Area.THRONE_WAY
        }
    }

    private val SIDES = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)

    /** Fuge: die Scholle stößt an die Scholle davor (Uhrzeigersinn) oder an die Bühne. So ist jede Fuge genau 1 breit. */
    private fun seam(x: Int, z: Int, i: Int): Boolean = SIDES.any { d ->
        val nx = x + d.offsetX
        val nz = z + d.offsetZ
        when (area(nx, nz)) {
            Area.STAGE -> true
            Area.FLOE -> floeOf(nx, nz) == Math.floorMod(i - 1, OlympusArenas.F_FLOES.size)
            else -> false
        }
    }

    private fun edge(x: Int, z: Int): Edge {
        var out = Edge.NONE
        for (d in SIDES) {
            when (area(x + d.offsetX, z + d.offsetZ)) {
                Area.CORE -> return Edge.INNER
                Area.SOUTH_WAY, Area.THRONE_WAY -> out = Edge.FLANK
                Area.OUTSIDE -> if (out == Edge.NONE) out = Edge.OUTER
                else -> {}
            }
        }
        return out
    }

    private fun deco(b: net.minecraft.block.Block): BlockState = b.defaultState

    // =============================================================================================
    // F  Thron des Zeus
    // =============================================================================================

    fun draw(c: SkyCanvas) {
        val isle = OlympusLayout.F
        val fy = isle.floorY
        val cx = isle.center.x
        val cz = isle.center.z
        val body = OlympusIsles.Body(cx, fy - 1, cz, isle.radius.toDouble(), IsleKind.MARBLE, 66, 0.94)
        OlympusIsles.body(c, body)
        cliff(c, body)
        bridgeHeads(c, isle)
        hollowUnderFloes(c, body, fy)
        arena(c, fy)
        floeStairs(c, fy)
        throneWay(c, fy)
        for (i in 0 until OlympusLayout.PILLAR_COUNT) pillar(c, i)
        for (d in OlympusArenas.F.devices) groundingRod(c, d.pos, d.facing)
        ringHall(c, body, fy, cx, cz)
        southGate(c, fy, cx, cz)
        altar(c, isle.altar!!, 3)
        throne(c, fy)
        gardens(c, body, fy, cx, cz)
        stormCrown(c, cx, cz, fy)
        OlympusIsles.cloudSkirt(c, body, 6)
        OlympusIsles.cloud(c, cx - 56, 196, cz + 20, 22.0, 13.0, 67, 3.0)
        OlympusIsles.cloud(c, cx + 50, 204, cz - 30, 18.0, 12.0, 68, 3.0)
    }

    // =============================================================================================
    // Kern, Schollen, Gassen, Bühne
    // =============================================================================================

    /** Jede Spalte bis r 30,5 und die Bühne: Luft darüber, dann je nach Zone Kernboden, Scholle, Gasse oder Bühne. */
    private fun arena(c: SkyCanvas, fy: Int) {
        val center = OlympusLayout.F.center
        val reach = OlympusArenas.F_STAGE_TO + 2
        for (x in center.x - reach..center.x + reach) for (z in center.z - reach..center.z + reach) {
            if (!c.inside(x, z)) continue
            val area = area(x, z)
            if (area == Area.OUTSIDE) continue
            for (h in 0 until CLEAR_ABOVE) c.put(x, fy + h, z, Kit.AIR)
            when (area) {
                Area.CORE -> c.put(x, fy - 1, z, coreFloor(x - center.x, z - center.z))
                Area.FLOE -> floeColumn(c, x, z, floeOf(x, z), fy)
                Area.SOUTH_WAY -> c.put(x, fy - 1, z, if (x == center.x) deco(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE) else deco(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE))
                Area.THRONE_WAY -> c.put(x, fy - 1, z, deco(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE))
                Area.STAGE -> stageColumn(c, x, z, fy)
                Area.OUTSIDE -> {}
            }
        }
    }

    /** Kernboden: Goldmitte, Zierring, Hexagramm durch die sechs Pfeiler, Sturmband am Rand; sonst polierter Marmor. */
    private fun coreFloor(dx: Int, dz: Int): BlockState {
        val r = hypot(dx.toDouble(), dz.toDouble())
        return when {
            dx == 0 && dz == 0 -> s(Blocks.GOLD_BLOCK)
            r > CORE_BAND -> deco(HhaDecoBlocks.STORM_MARBLE)
            starLine(dx, dz) -> if (starNode(dx, dz)) s(Blocks.CALCITE) else deco(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE)
            r in CORE_RING_FROM..CORE_RING_TO -> deco(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE)
            else -> deco(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE)
        }
    }

    /** Liegt (dx, dz) auf einer Linie des Hexagramms (Pfeiler i zu Pfeiler i + 2)? */
    private fun starLine(dx: Int, dz: Int): Boolean {
        val c = OlympusLayout.F.center
        val p = OlympusLayout.PILLARS
        val n = OlympusLayout.PILLAR_COUNT
        return (0 until n).any { i ->
            val a = p[i]
            val b = p[(i + 2) % n]
            segmentDistance(dx.toDouble(), dz.toDouble(), (a.x - c.x).toDouble(), (a.z - c.z).toDouble(), (b.x - c.x).toDouble(), (b.z - c.z).toDouble()) < STAR_LINE
        }
    }

    /** Nahe einem Schnittpunkt der beiden Dreiecke (innere Sechseck-Ecken bei 30° + 60° · k)? */
    private fun starNode(dx: Int, dz: Int): Boolean {
        val r = OlympusLayout.PILLAR_RING / Math.sqrt(3.0)
        return (0 until 6).any { k ->
            val a = Math.toRadians(30.0 + 60.0 * k)
            hypot(dx - r * cos(a), dz - r * sin(a)) < STAR_NODE
        }
    }

    /**
     * Eine Spalte der Scholle [i]: Belag auf y 233 (Füße 234), darunter höchstens 6 Fels; Fuge dunkel bis in den
     * Unterbau; darunter Luft bis zur Unterkante des Teils und Sturmwolke.
     */
    private fun floeColumn(c: SkyCanvas, x: Int, z: Int, i: Int, fy: Int) {
        val center = OlympusLayout.F.center
        val r = hypot((x - center.x).toDouble(), (z - center.z).toDouble())
        val top = fy + OlympusArenas.F_TERRACE_HEIGHT - 1
        val depth = if (r <= FLOE_FULL_RING) FLOE_DEPTH else FLOE_DEPTH_THIN + Kit.noise(x, 61, z) % (FLOE_DEPTH - FLOE_DEPTH_THIN + 1)
        val bottom = top - depth
        val seam = seam(x, z, i)
        val edge = edge(x, z)
        // Unter der Scholle bis zur Unterkante des Teils: frei (der Rest ist schon ausgehöhlt).
        for (y in fy - OlympusArenas.F_FLOE_BELOW until bottom) c.put(x, y, z, Kit.AIR)
        for (y in bottom until top) {
            val state = when {
                seam -> s(Blocks.POLISHED_DEEPSLATE)
                y == top - 1 && edge != Edge.NONE -> deco(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS)
                Kit.noise(x, y, z) % 7 == 0 -> s(Blocks.CALCITE)
                else -> deco(HhaDecoBlocks.OLYMPUS_MARBLE)
            }
            c.put(x, y, z, state)
        }
        c.put(x, top, z, when {
            seam -> deco(HhaDecoBlocks.STORM_MARBLE)
            edge == Edge.INNER || edge == Edge.FLANK -> deco(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE)
            edge == Edge.OUTER -> deco(HhaDecoBlocks.STORM_MARBLE)
            else -> deco(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE)
        })
        // Sturmwolke unter der Scholle: Fetzen in Zweierfeldern, nach unten dünner.
        for (k in 1..FLOE_CLOUD_LAYERS) {
            if (Kit.noise(Math.floorDiv(x, 2), 63 + k, Math.floorDiv(z, 2)) % 100 < FLOE_CLOUD_SHARE - (k - 1) * 12) {
                c.soft(x, bottom - k, z, deco(HhaDecoBlocks.STORM_CLOUD))
            }
        }
    }

    /**
     * Thronbühne: Körper bis 6 unter den Kern, Boden auf y 234 (Füße 235); Goldkante, wo sie an etwas anderes stößt
     * als Bühne oder Thronhof, roter Läufer zur Thronstufe, vier Sturm-Einlagen als Licht.
     */
    private fun stageColumn(c: SkyCanvas, x: Int, z: Int, fy: Int) {
        val center = OlympusLayout.F.center
        val top = fy + OlympusArenas.F_STAGE_HEIGHT - 1
        val edge = SIDES.any { d -> !throneCourt(x + d.offsetX, z + d.offsetZ) }
        for (y in fy - STAGE_BODY_BELOW until top) {
            c.put(x, y, z, if (edge && y >= fy) deco(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS) else deco(HhaDecoBlocks.OLYMPUS_MARBLE))
        }
        val dx = x - center.x
        val dz = z - center.z
        c.put(x, top, z, when {
            edge -> deco(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE)
            abs(dx) <= RUNNER_HALF -> s(Blocks.RED_TERRACOTTA)
            abs(dx) == STAGE_LIGHT_X && dz in STAGE_LIGHT_DZ -> deco(HhaDecoBlocks.STORM_MARBLE)
            else -> deco(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE)
        })
    }

    /** Bühne oder Innenraum der Exedra (der Thronhof liegt auf Bühnenhöhe). */
    private fun throneCourt(x: Int, z: Int): Boolean = area(x, z) == Area.STAGE || exedraInside(x, z)

    private fun exedraCenter(): BlockPos = OlympusLayout.THRONE.add(0, 0, -2)

    private fun exedraInside(x: Int, z: Int): Boolean {
        val e = exedraCenter()
        return z <= e.z && hypot((x - e.x).toDouble(), (z - e.z).toDouble()) < EXEDRA_INNER
    }

    /**
     * Unter den Schollen ist die Insel hohl: Von der Unterkante des Teils (y 226) an abwärts bleibt nur Luft, damit
     * eine abgebrochene Scholle ins Freie fällt. Kern, Gassen und Ringhalle behalten ihren Fels.
     */
    private fun hollowUnderFloes(c: SkyCanvas, body: OlympusIsles.Body, fy: Int) {
        val center = OlympusLayout.F.center
        val low = OlympusIsles.bands(body).last().bottom - body.depth
        val high = fy - OlympusArenas.F_FLOE_BELOW - 1
        val reach = OlympusArenas.F_TERRACE_OUTER.toInt() + 1
        for (x in center.x - reach..center.x + reach) for (z in center.z - reach..center.z + reach) {
            if (!c.inside(x, z) || floeOf(x, z) < 0) continue
            for (y in low..high) if (!c.isFree(x, y, z)) c.put(x, y, z, Kit.AIR)
        }
    }

    /** Je Scholle zwei Treppen 3 breit neben dem Pfeiler (Vertragspunkte `Level.access`), auf den Ringen 18/19 im Kern. */
    private fun floeStairs(c: SkyCanvas, fy: Int) {
        val center = OlympusLayout.F.center
        for (level in OlympusArenas.F.levels) {
            if (!level.id.startsWith("floe_")) continue
            for (p in level.access) {
                val out = Kit.inward(p.x - center.x, p.z - center.z).opposite
                for (y in fy until p.y) c.block(p.x, y, p.z, deco(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE))
                c.block(p.x, p.y, p.z, stair(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_STAIRS, out))
            }
        }
    }

    /** Thronaufgang: drei Stufen (Vertrag) von r 20 bis zur Bühne; die übrigen Felder der Gasse sind Wangen. */
    private fun throneWay(c: SkyCanvas, fy: Int) {
        val center = OlympusLayout.F.center
        val stairs = OlympusArenas.F.level("throne_stage").access
        val cells = stairs.map { it.x to it.z }.toSet()
        for (p in stairs) {
            for (y in fy until p.y) c.block(p.x, y, p.z, deco(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE))
            c.block(p.x, p.y, p.z, stair(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_STAIRS, Direction.NORTH))
        }
        val top = fy + OlympusArenas.F_STAGE_HEIGHT - 1
        for (x in center.x - 4..center.x + 4) for (z in center.z - OlympusArenas.F_STAGE_FROM..center.z - 19) {
            if (!c.inside(x, z) || area(x, z) != Area.THRONE_WAY || (x to z) in cells) continue
            for (y in fy until top) c.put(x, y, z, deco(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS))
            c.put(x, top, z, deco(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE))
        }
    }

    // =============================================================================================
    // Blitzpfeiler und Erdungsgriffe
    // =============================================================================================

    /**
     * Blitzpfeiler [i], 3 × 3 ab der Terrasse (y 234 … 241, Vertrag `pillar_i`):
     * - y 234 Bronzeband, y 235 Sturmsockel (zusammen der Sockel, 2 hoch);
     * - Kupferkern 5 hoch in der Mittelspalte ab `OlympusLayout.pillar(i)` (der Kampf tauscht nur ihn), darüber der Ableiter;
     * - y 236 … 238 Mantel aus Marmorsäulen an den vier Seiten, Ecken frei (dort sieht man das Kupfer);
     * - y 239 Kapitell, y 240 … 241 Krone aus vier Zacken (Bronze, Goldspitze) um die Spitze des Ableiters.
     */
    private fun pillar(c: SkyCanvas, i: Int) {
        val p = OlympusLayout.pillar(i)
        val y0 = p.y
        for (ox in -1..1) for (oz in -1..1) {
            if (ox == 0 && oz == 0) continue
            val x = p.x + ox
            val z = p.z + oz
            // Ragt ein Feld des Kastens in den Kern (Pfeiler 2: eine Ecke bei r 19,2), bleibt es leer: Der Pfeiler gehört
            // ganz zu seiner Scholle und darf nach dem Bruch nicht als Rest über dem Kern hängen.
            if (area(x, z) != Area.FLOE) continue
            val corner = ox != 0 && oz != 0
            c.block(x, y0, z, deco(HhaDecoBlocks.OLYMPUS_BRONZE))
            c.block(x, y0 + 1, z, deco(HhaDecoBlocks.STORM_MARBLE))
            for (h in 2..4) c.block(x, y0 + h, z, if (corner) Kit.AIR else deco(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR))
            c.block(x, y0 + 5, z, if (corner) deco(HhaDecoBlocks.OLYMPUS_BRONZE) else deco(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE))
            c.block(x, y0 + 6, z, if (corner) deco(HhaDecoBlocks.OLYMPUS_BRONZE) else Kit.AIR)
            c.block(x, y0 + 7, z, if (corner) deco(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE) else Kit.AIR)
        }
        for (h in 0 until OlympusLayout.PILLAR_HEIGHT) c.block(p.x, y0 + h, p.z, s(Blocks.WAXED_COPPER_BLOCK))
        c.block(p.x, y0 + OlympusLayout.PILLAR_HEIGHT, p.z, s(Blocks.WAXED_LIGHTNING_ROD))
        for (h in OlympusLayout.PILLAR_HEIGHT + 1 until OlympusArenas.F_PILLAR_TOTAL) c.block(p.x, y0 + h, p.z, Kit.AIR)
    }

    /**
     * Erdungsgriff an seinem Vertragspunkt (Ring 20, y 233, Vorderseite zur Mitte) in der Terrassenwand: Bronzerahmen
     * links, rechts und darunter, dahinter und darüber eine Sturmader hinauf in den Pfeilersockel.
     */
    private fun groundingRod(c: SkyCanvas, pos: BlockPos, facing: Direction) {
        val back = facing.opposite
        for (side in listOf(facing.rotateYClockwise(), facing.rotateYCounterclockwise())) {
            for (dy in -1..0) {
                val q = pos.offset(side).up(dy)
                if (area(q.x, q.z) == Area.FLOE) c.block(q.x, q.y, q.z, deco(HhaDecoBlocks.OLYMPUS_BRONZE))
            }
        }
        c.block(pos.x, pos.y - 1, pos.z, deco(HhaDecoBlocks.OLYMPUS_BRONZE))
        val behind = pos.offset(back)
        if (area(behind.x, behind.z) == Area.FLOE) c.block(behind.x, behind.y, behind.z, deco(HhaDecoBlocks.STORM_MARBLE))
        if (area(pos.x, pos.z) == Area.FLOE) c.block(pos.x, pos.y + 1, pos.z, deco(HhaDecoBlocks.STORM_MARBLE))
        c.block(pos.x, pos.y, pos.z, HhaDecoBlocks.GROUNDING_ROD.defaultState.with(HorizontalFacingBlock.FACING, facing))
    }

    // =============================================================================================
    // Ringhalle, Südtor, Gärten
    // =============================================================================================

    /** Stylobat (eine Stufe), Ringhalle aus großen Säulen mit Gebälk, an den Pfeilerachsen Doppelsäule und Attika. */
    private fun ringHall(c: SkyCanvas, body: OlympusIsles.Body, fy: Int, cx: Int, cz: Int) {
        val arena = OlympusArenas.F_TERRACE_OUTER + 0.5
        c.disc(cx, cz, 44.5) { x, z, r ->
            if (r <= arena || !body.rim.containsCell(x, z) || throneCourt(x, z)) return@disc
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
            val yoke = OlympusLayout.PILLAR_ANGLES.any { angleDiff(it, d) < 0.5 }
            val at = if (yoke) listOf(d - YOKE_SPREAD, d + YOKE_SPREAD) else listOf(d)
            for (a in at) {
                columns.add(a)
                val (px, pz) = polar(cx, cz, HALL_RADIUS, a)
                column(c, px, fy + 1, pz, HALL_COLUMN_HEIGHT)
            }
        }
        val y = fy + HALL_COLUMN_HEIGHT + 1
        c.disc(cx, cz, 37.0) { x, z, r ->
            if (r < 33.0) return@disc
            val a = angleOf(x - cx.toDouble(), z - cz.toDouble())
            if (angleDiff(a, 270.0) < HALL_OPEN_NORTH) return@disc
            c.put(x, y, z, s(Blocks.SMOOTH_QUARTZ))
            c.put(x, y + 1, z, if (r in 34.0..36.0 && columns.any { angleDiff(it, a) < 1.6 }) s(Blocks.QUARTZ_PILLAR) else if (r in 34.0..36.0) s(Blocks.CHISELED_QUARTZ_BLOCK) else s(Blocks.SMOOTH_QUARTZ))
            c.put(x, y + 2, z, when {
                r > 36.3 -> stair(Blocks.SMOOTH_QUARTZ_STAIRS, Kit.inward(x - cx, z - cz), top = true)
                r < 33.7 -> stair(Blocks.SMOOTH_QUARTZ_STAIRS, Kit.inward(x - cx, z - cz).opposite, top = true)
                else -> s(Blocks.SMOOTH_QUARTZ)
            })
        }
        for (a in OlympusLayout.PILLAR_ANGLES) yokeAttic(c, cx, cz, y + 3, a)
    }

    /**
     * Attika über dem betonten Joch einer Pfeilerachse: Kreuz aus gemeißeltem Marmor, darauf Sturmmarmor und ein
     * Blitzableiter. Kündigt von außen die Scholle mit ihrem Blitzpfeiler an.
     */
    private fun yokeAttic(c: SkyCanvas, cx: Int, cz: Int, y: Int, deg: Double) {
        val (px, pz) = polar(cx, cz, HALL_RADIUS, deg)
        c.block(px, y, pz, deco(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE))
        for (d in SIDES) c.block(px + d.offsetX, y, pz + d.offsetZ, stair(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_STAIRS, d.opposite))
        c.block(px, y + 1, pz, deco(HhaDecoBlocks.STORM_MARBLE))
        c.block(px, y + 2, pz, s(Blocks.WAXED_LIGHTNING_ROD))
    }

    /** Südtor auf der Achse mit Giebel, davor zwei Wächterinnen zwischen Statuen; der Weg trägt eine Goldlinie zur Gasse. */
    private fun southGate(c: SkyCanvas, fy: Int, cx: Int, cz: Int) {
        pediment(c, cx, fy + 17, cz + 37, Direction.SOUTH, 7, 4)
        for (x in cx - 2..cx + 2) for (z in cz + 31..cz + 45) {
            c.block(x, fy - 1, z, when {
                x == cx -> deco(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE)
                abs(x - cx) == 2 -> s(Blocks.CALCITE)
                else -> s(Blocks.POLISHED_DIORITE)
            })
            c.block(x, fy, z, if (z in cz + 33..cz + 37) s(Blocks.SMOOTH_QUARTZ) else Kit.AIR)
        }
        for (side in intArrayOf(-1, 1)) {
            figure(c, cx + side * 13, fy, cz + 39, Direction.SOUTH, if (side < 0) Attr.SPEAR else Attr.SCEPTER)
            automatonPlinth(c, cx + side * 9, fy, cz + 39, MARBLE_LOOK)
            figure(c, cx + side * 5, fy, cz + 40, Direction.SOUTH, Attr.BOWL)
            c.block(cx + side * 3, fy + 17, cz + 37, s(Blocks.END_ROD))
        }
    }

    /** Gärten am Rand: Lorbeer und Zypressen in Beeten, zwei Säulen für Greife, vier Götter, Balustrade, Stele. */
    private fun gardens(c: SkyCanvas, body: OlympusIsles.Body, fy: Int, cx: Int, cz: Int) {
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
    }

    // =============================================================================================
    // Thron, Bühne, Exedra
    // =============================================================================================

    /**
     * Der Thron auf der Bühne (Sitz `OlympusLayout.THRONE`, y 236): Stufe, Sitz, Armlehnen als Bronzelöwen, Lehne 12
     * hoch mit goldenem Blitz, darüber der Bronzeadler; seitlich zwei Blitzbündel; dahinter die Exedra.
     */
    private fun throne(c: SkyCanvas, fy: Int) {
        val t = OlympusLayout.THRONE
        val tx = t.x
        val tz = t.z
        val sy = fy + OlympusArenas.F_STAGE_HEIGHT
        throneCourtFloor(c, fy)
        // Thronstufe (y 235), vorne als Treppe.
        for (x in tx - THRONE_DAIS_HALF..tx + THRONE_DAIS_HALF) for (z in tz - 3..tz + 3) {
            c.block(x, sy, z, when {
                z == tz + 3 -> stair(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_STAIRS, Direction.NORTH)
                abs(x - tx) == THRONE_DAIS_HALF || z == tz - 3 -> deco(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE)
                else -> deco(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE)
            })
        }
        // Sitz und Löwen als Armlehnen (Körper, Mähne, Kopf mit Schnauze nach Süden, Pranken).
        c.block(tx, t.y, tz, stair(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_STAIRS, Direction.NORTH))
        for (sx in intArrayOf(-1, 1)) {
            c.block(tx + sx, t.y, tz, deco(HhaDecoBlocks.OLYMPUS_BRONZE))
            c.block(tx + sx, t.y + 1, tz, slab(HhaDecoBlocks.OLYMPUS_BRONZE_SLAB))
            c.block(tx + sx, t.y, tz + 1, deco(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE))
            c.block(tx + sx, t.y + 1, tz + 1, stair(HhaDecoBlocks.OLYMPUS_BRONZE_STAIRS, Direction.NORTH))
            c.block(tx + sx, t.y, tz + 2, slab(HhaDecoBlocks.OLYMPUS_BRONZE_SLAB))
        }
        // Lehne: 3 breit, 12 hoch; Mitte Sturmmarmor, darüber ein goldener Blitz im Zickzack.
        val bolt = mapOf(3 to 1, 4 to 0, 5 to -1, 6 to 0, 7 to 1, 8 to 0, 9 to -1)
        for (x in tx - 1..tx + 1) for (h in 0 until THRONE_BACK) {
            val state = when {
                bolt[h] == x - tx -> deco(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE)
                h == THRONE_BACK - 1 -> deco(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE)
                x == tx -> deco(HhaDecoBlocks.STORM_MARBLE)
                else -> deco(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE)
            }
            c.block(x, t.y + h, tz - 1, state)
        }
        eagle(c, tx, t.y + THRONE_BACK, tz - 1)
        for (sx in intArrayOf(-BUNDLE_X, BUNDLE_X)) bundle(c, tx + sx, sy, tz + 1)
        exedra(c, fy)
    }

    /** Bronzeadler mit ausgebreiteten Schwingen (6 je Seite, nach außen steigend) und goldenem Kopf. */
    private fun eagle(c: SkyCanvas, tx: Int, ey: Int, ez: Int) {
        for (x in tx - 6..tx + 6) {
            val ax = abs(x - tx)
            val rise = if (ax <= 1) 0 else (ax - 1) / 2
            val inward = if (x < tx) Direction.EAST else Direction.WEST
            c.block(x, ey + rise, ez, if (ax == 6) stair(HhaDecoBlocks.OLYMPUS_BRONZE_STAIRS, inward, top = true) else deco(HhaDecoBlocks.OLYMPUS_BRONZE))
            if (ax in 2..5) c.block(x, ey + rise - 1, ez, stair(HhaDecoBlocks.OLYMPUS_BRONZE_STAIRS, inward, top = true))
        }
        c.block(tx, ey + 1, ez, deco(HhaDecoBlocks.OLYMPUS_BRONZE))
        c.block(tx, ey + 2, ez, deco(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE))
        c.block(tx, ey + 2, ez + 1, stair(HhaDecoBlocks.OLYMPUS_BRONZE_STAIRS, Direction.NORTH, top = true))
        c.block(tx, ey + 3, ez, s(Blocks.GOLD_BLOCK))
    }

    /** Blitzbündel auf einem Pfosten: Fuß, Säulenschaft, Bronze, Gold, darauf drei Blitzableiter als Fächer. */
    private fun bundle(c: SkyCanvas, x: Int, y: Int, z: Int) {
        c.block(x, y, z, deco(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE))
        for (h in 1..3) c.block(x, y + h, z, deco(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR))
        c.block(x, y + 4, z, deco(HhaDecoBlocks.OLYMPUS_BRONZE))
        c.block(x, y + 5, z, deco(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE))
        c.block(x, y + 6, z, s(Blocks.WAXED_LIGHTNING_ROD))
        c.block(x - 1, y + 5, z, s(Blocks.WAXED_LIGHTNING_ROD).with(Properties.FACING, Direction.WEST))
        c.block(x + 1, y + 5, z, s(Blocks.WAXED_LIGHTNING_ROD).with(Properties.FACING, Direction.EAST))
    }

    /** Thronhof in der Exedra: Boden auf Bühnenhöhe, darüber frei (auch die Reste des alten Podests). */
    private fun throneCourtFloor(c: SkyCanvas, fy: Int) {
        val e = exedraCenter()
        val top = fy + OlympusArenas.F_STAGE_HEIGHT - 1
        val clear = THRONE_BACK + 8
        c.disc(e.x, e.z, EXEDRA_INNER) { x, z, _ ->
            if (!exedraInside(x, z) || area(x, z) == Area.STAGE) return@disc
            for (y in fy - 1 until top) c.put(x, y, z, deco(HhaDecoBlocks.OLYMPUS_MARBLE))
            c.put(x, top, z, deco(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE))
            for (h in 1..clear) c.put(x, top + h, z, Kit.AIR)
        }
    }

    /**
     * Exedra: halbrunde Wand hinter dem Thron vom Hofboden (y 232) bis 12 über der Bühne, mit Sockel, Nischen und
     * Lichtstäben auf Bühnenhöhe, liegendem Säulenband, Fries und Gesims.
     */
    private fun exedra(c: SkyCanvas, fy: Int) {
        val e = exedraCenter()
        val sy = fy + OlympusArenas.F_STAGE_HEIGHT
        c.disc(e.x, e.z, EXEDRA_OUTER) { x, z, r ->
            if (z > e.z || r < EXEDRA_INNER) return@disc
            for (y in fy..sy + EXEDRA_TOP) {
                val h = y - sy
                val niche = r < 9.5 && h in 2..5 && Math.floorMod(x, 4) == 1
                val state = when {
                    niche -> Kit.AIR
                    h == EXEDRA_TOP -> stair(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_STAIRS, Kit.inward(x - e.x, z - e.z).opposite, top = true).takeIf { r < 9.3 } ?: deco(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE)
                    h == EXEDRA_TOP - 1 -> deco(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE)
                    h == 6 -> deco(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR).with(Properties.AXIS, Direction.Axis.X)
                    h < 0 -> deco(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE)
                    r > 10.0 && Math.floorMod(x - e.x, 4) == 0 -> deco(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR)
                    else -> if (Kit.noise(x, y, z) % 8 == 0) s(Blocks.QUARTZ_BRICKS) else deco(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS)
                }
                c.block(x, y, z, state)
            }
            if (r < 9.5 && Math.floorMod(x, 4) == 1) c.block(x, sy + 2, z, s(Blocks.END_ROD))
        }
    }

    // =============================================================================================
    // Klippe und Himmel
    // =============================================================================================

    /**
     * Gliedert die Klippe: An jeder Lagenkante des Inselkörpers ein Band aus Marmorziegeln (gerade Lagen; die ungeraden
     * tragen schon Quarzziegel), in der Wand Flecken aus Naturmarmor, darunter vereinzelte Kragsteine als Vorsprünge.
     */
    private fun cliff(c: SkyCanvas, body: OlympusIsles.Body) {
        val bands = OlympusIsles.bands(body)
        val reach = body.radius.toInt() + 2
        val claim = body.radius + 0.4
        for (band in bands) {
            for (x in body.cx - reach..body.cx + reach) for (z in body.cz - reach..body.cz + reach) {
                if (!c.inside(x, z) || bridgeStrip(x, z) || !band.rim.containsCell(x, z)) continue
                if (SIDES.none { d -> !band.rim.containsCell(x + d.offsetX, z + d.offsetZ) }) continue
                if (hypot((x - body.cx).toDouble(), (z - body.cz).toDouble()) > claim) continue
                for (y in band.bottom..band.topY) {
                    if (band.index == 0 && y == band.topY) continue
                    when {
                        y == band.bottom && band.index % 2 == 0 -> c.put(x, y, z, deco(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS))
                        y != band.bottom && Kit.noise(Math.floorDiv(x, 3), y, Math.floorDiv(z, 3)) % 100 < 35 -> c.put(x, y, z, deco(HhaDecoBlocks.OLYMPUS_MARBLE))
                    }
                }
                val below = bands.getOrNull(band.index + 1)
                if (Kit.noise(x, band.bottom, z) % CLIFF_CORBEL == 0 && (below == null || !below.rim.containsCell(x, z))) {
                    c.put(x, band.bottom - 1, z, stair(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_STAIRS, Kit.inward(x - body.cx, z - body.cz), top = true))
                }
            }
        }
    }

    /**
     * Gewitterkranz statt Beton-Deckel: Ring r 36 … 50 aus `storm_cloud` bei +30 … +33, zerfetzt: weiches Rauschen
     * (Gitter 6, dazu Gitter 3 für ausgefranste Ränder) lässt rund 60 % Lücken, zu den Ringrändern hin mehr. Wo die
     * Wolke dicht ist, ist sie bis 3 dick und wölbt sich nach oben, am Rand 1. Über Kern, Terrasse und Bühne bleibt
     * der Himmel frei.
     */
    private fun stormCrown(c: SkyCanvas, cx: Int, cz: Int, fy: Int) {
        val cloud = deco(HhaDecoBlocks.STORM_CLOUD)
        c.disc(cx, cz, CROWN_OUTER) { x, z, r ->
            if (r < CROWN_INNER || bridgeStrip(x, z)) return@disc
            val fray = (minOf(r - CROWN_INNER, CROWN_OUTER - r) / 4.0).coerceIn(0.0, 1.0)
            val v = 0.7 * smooth(x, z, 6, 81) + 0.3 * smooth(x, z, 3, 83) - 0.18 * (1.0 - fray)
            val dense = v - CROWN_THRESHOLD
            if (dense < 0.0) return@disc
            val thick = (1 + (dense / CROWN_LAYER).toInt()).coerceAtMost(CROWN_TO - CROWN_FROM + 1)
            val base = fy + CROWN_FROM + if (thick == 1 && Kit.noise(x, 85, z) % 3 == 0) 1 else 0
            for (k in 0 until thick) c.soft(x, base + k, z, cloud)
        }
    }

    /** Weiches Rauschen 0 … 1: Werte an den Ecken eines Gitters der Weite [cell], dazwischen geglättet überblendet. */
    private fun smooth(x: Int, z: Int, cell: Int, seed: Int): Double {
        val gx = Math.floorDiv(x, cell)
        val gz = Math.floorDiv(z, cell)
        fun corner(i: Int, k: Int) = Kit.noise(gx + i, seed, gz + k) / 65535.0
        fun ease(t: Double) = t * t * (3 - 2 * t)
        val tx = ease((x - gx * cell) / cell.toDouble())
        val tz = ease((z - gz * cell) / cell.toDouble())
        val top = corner(0, 0) + (corner(1, 0) - corner(0, 0)) * tx
        val bottom = corner(0, 1) + (corner(1, 1) - corner(0, 1)) * tx
        return top + (bottom - top) * tz
    }

    private fun segmentDistance(px: Double, pz: Double, ax: Double, az: Double, bx: Double, bz: Double): Double {
        val dx = bx - ax
        val dz = bz - az
        val t = (((px - ax) * dx + (pz - az) * dz) / (dx * dx + dz * dz)).coerceIn(0.0, 1.0)
        return hypot(px - (ax + dx * t), pz - (az + dz * t))
    }
}
