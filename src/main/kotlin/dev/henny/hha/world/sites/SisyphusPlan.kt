package dev.henny.hha.world.sites

import dev.henny.hha.HhaDecoBlocks
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.WallBlock
import net.minecraft.block.enums.WallShape
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import dev.henny.hha.world.*
import dev.henny.hha.world.TartarusPlans.HILL_R
import dev.henny.hha.world.TartarusPlans.SISYPHUS_BOULDER
import dev.henny.hha.world.TartarusPlans.SISYPHUS_HEIGHT
import dev.henny.hha.world.TartarusPlans.SISYPHUS_SUMMIT
import dev.henny.hha.world.TartarusPlans.SISYPHUS_TRACK_WIDTH
import dev.henny.hha.world.TartarusPlans.TRACK_FROM
import dev.henny.hha.world.TartarusPlans.TRACK_TO
import dev.henny.hha.world.TartarusPlans.TRACK_TURNS
import dev.henny.hha.world.TartarusPlans.sisyphusBoulder
import dev.henny.hha.world.TartarusPlans.sisyphusHill
import dev.henny.hha.world.TartarusPlans.sisyphusTrack
import dev.henny.hha.world.TartarusPlans.stele
import dev.henny.hha.world.TartarusPlans.trackStart

/**
 * Bauplan der Strafstätte des Sisyphos (Überarbeitung 4, P13). Der Hügel (Profil [sisyphusHill]) und die Spiralbahn
 * ([sisyphusTrack]) bleiben, wie die Kämpfe sie kennen. Neu: Gipfelplatz mit Ringband und Gipfelaltar (Deckung gegen den
 * rollenden Fels und Fernzeichen), Bahn mit Bordsteinen und zwei Glutrillen, drei Ausweichbuchten mit Felsnase, Hügel
 * mit Felsbändern, zwei Geröllrutschen und einer Abbruchkante im Westen, Zuweg mit Felsreihe.
 *
 * [draw] zeichnet alles, was am Gelände hängt (Hügelkörper, Rutschen, Zuweg); [arenaCore] zeichnet ohne Geländebezug,
 * relativ zu `o`: Gipfelplatz, Altar, Lauffläche der Bahn samt Rillen und Bordsteinen, Buchten.
 */
internal object SisyphusPlan {

    // --- Gipfel (Kern r 8,5 auf o.y + 15; Boden o.y + 14) ---------------------------------------------------------

    /** Ringband des Gipfelplatzes: Blockringe (Abstand der Blockmitte) über [SUMMIT_BAND_INNER] bis [SUMMIT_BAND_OUTER]. */
    private const val SUMMIT_BAND_INNER = 7.2
    private const val SUMMIT_BAND_OUTER = 8.2

    /** Mittelmarke des Gipfels (gemeißelt), hier steht Sisyphos. */
    private const val SUMMIT_MARK = 1.5

    /** Lichte Höhe über dem Gipfelplatz, die der Kern freiräumt. */
    private const val SUMMIT_CLEAR = 6

    // --- Bahn -----------------------------------------------------------------------------------------------------

    /** Glutrillen: Querabstand zur Mittellinie der Bahn (der Fels hat sie geschliffen). */
    private const val RUT_FROM = 0.6
    private const val RUT_TO = 1.6

    /** Bordstein an der Talseite: Zellen mit so viel Abstand nach außen von der Mittellinie. */
    private const val CURB_FROM = 1.6

    /** Alle so viele Blöcke Bahnlänge eine Lücke im Bordstein (Ablauf), [CURB_GAP] lang. */
    private const val CURB_PERIOD = 11.0
    private const val CURB_GAP = 1.2

    /** Am Fuß bleibt der Bordstein offen, damit der Zuweg auf die Bahn trifft (Anteil der Bahn). */
    private const val CURB_FREE_FOOT = 0.05

    /** Lichte Höhe über der Bahn. */
    private const val TRACK_CLEAR = 4

    /** Ausweichbuchten an der Bergseite: Stelle auf der Bahn (0 Fuß … 1 Gipfel), Tiefe und Länge in Blöcken. */
    private val BAYS = doubleArrayOf(0.2, 0.45, 0.68)
    private const val BAY_DEPTH = 2.6
    private const val BAY_LENGTH = 6.0

    /** Felsnase am oberen Ende jeder Bucht: Radius und Höhe über der Bahn. */
    private const val NOSE_RADIUS = 1.5
    private const val NOSE_HEIGHT = 3

    // --- Hügelkörper ----------------------------------------------------------------------------------------------

    /**
     * Felsbänder: Im Hügelkörper ist jede so vielte Lage (ab o.y) `tartarus_basalt`. Sichtbar werden sie an den Stufen
     * des Hangs, an der Bergseite der Bahn und an der Abbruchkante; die Oberfläche bleibt Geröll.
     */
    private const val STRATA_STEP = 4
    private const val STRATA_PHASE = 2

    /** Zwei Geröllrutschen (Weltwinkel, 0° Osten, 90° Süden). */
    private val SLIDE_ANGLES = doubleArrayOf(30.0, 110.0)

    /** Abbruchkante im Westen: Sektor (Grad), Radien, Abstand zur Bahn, Stufenhöhe der Kante. */
    private const val SCARP_FROM = 160.0
    private const val SCARP_TO = 215.0
    private const val SCARP_INNER = 11.0
    private const val SCARP_OUTER = 22.5
    private const val SCARP_TRACK_GAP = 2.0
    private const val SCARP_STEP = 3

    // --- Zuweg ----------------------------------------------------------------------------------------------------

    /** Zuweg: vom Rand des Bauwerkskastens zum Fuß der Bahn ([TartarusArenas.siteApproach]), 5 breit. */
    private const val ROAD_WIDTH = 5
    private const val ROAD_OUTER = 29.5

    /** Felsreihe: fünf aufgerichtete Steine am Hügelfuß, von außen nach innen wachsend. */
    private const val ROW_RADIUS = 29.0
    private const val ROW_FIRST = 0.27
    private const val ROW_SPACING = 0.14
    private val ROW_HEIGHTS = intArrayOf(6, 5, 4, 3, 2)

    private val S get() = Palettes.Sisyphus

    /** Wo eine Spalte auf der Bahn liegt: Mittellinien-Radius und Bahnanteil t (0 … 1), oder null. */
    private class TrackCell(val trackR: Double, val t: Double, val along: Double)

    private fun trackAt(o: BlockPos, x: Int, z: Int): TrackCell? {
        val dx = (x - o.x).toDouble()
        val dz = (z - o.z).toDouble()
        val r = hypot(dx, dz)
        val half = SISYPHUS_TRACK_WIDTH / 2.0
        if (r <= TRACK_TO - half - 0.5) return null
        val a = atan2(dz, dx)
        val frac = (((a - trackStart(o)) / (2.0 * PI)) % 1.0 + 1.0) % 1.0
        var best: TrackCell? = null
        for (k in 0..1) {
            val t = (frac + k) / TRACK_TURNS
            if (t > 1.0) continue
            val rc = TRACK_FROM + (TRACK_TO - TRACK_FROM) * t
            if (abs(r - rc) <= half) best = TrackCell(rc, t, (frac + k) * 2.0 * PI * rc)
        }
        return best
    }

    /** Abstand einer Spalte zur nächsten Bahn-Mittellinie unter ihrem Winkel (für Kante und Rutschen). */
    private fun trackGap(o: BlockPos, r: Double, a: Double): Double {
        val frac = (((a - trackStart(o)) / (2.0 * PI)) % 1.0 + 1.0) % 1.0
        var gap = Double.MAX_VALUE
        for (k in 0..1) {
            val t = (frac + k) / TRACK_TURNS
            if (t > 1.0) continue
            gap = minOf(gap, abs(r - (TRACK_FROM + (TRACK_TO - TRACK_FROM) * t)))
        }
        return gap - SISYPHUS_TRACK_WIDTH / 2.0
    }

    private fun trackTop(o: BlockPos, cell: TrackCell): Int = o.y + sisyphusHill(cell.trackR).roundToInt()

    /** Liegt die Spalte unter dem liegenden Fels? Dort zeichnet der Kern nichts (der Fels gehört dem Kampf). */
    private fun underBoulder(o: BlockPos, x: Int, z: Int): Boolean {
        val b = sisyphusBoulder(o)
        return hypot((x - b.x).toDouble(), (z - b.z).toDouble()) <= SISYPHUS_BOULDER + 1.5
    }

    /** Hügelfüllung mit Felsbändern: jede [STRATA_STEP]-te Lage ist Basalt. */
    private fun strata(o: BlockPos): Pal = { x, y, z -> if (Math.floorMod(y - o.y, STRATA_STEP) == STRATA_PHASE) HhaDecoBlocks.TARTARUS_BASALT.defaultState else S.HILL(x, y, z) }

    private fun angleDeg(dx: Double, dz: Double): Double = (Math.toDegrees(atan2(dz, dx)) + 360.0) % 360.0

    /** Abbruchkante: Im Westsektor fern der Bahn springt der Hang in Stufen ([SCARP_STEP] hoch) und zeigt seine Bänder. */
    private fun scarp(o: BlockPos, r: Double, a: Double): Boolean {
        val deg = angleDeg(cos(a), sin(a))
        return deg in SCARP_FROM..SCARP_TO && r in SCARP_INNER..SCARP_OUTER && trackGap(o, r, a) > SCARP_TRACK_GAP
    }

    // =============================================================================================================
    // Voller Plan
    // =============================================================================================================

    /** Sisyphos: ein Hügel mit Spiralbahn; der Fels liegt mal hier, mal dort auf dem Weg nach oben. */
    fun draw(c: BlockCanvas, o: BlockPos) {
        hill(c, o)
        boulder(c, o)
        footBoulders(c, o)
        approach(c, o)
        arenaCore(c, o)
    }

    /** Hügelkörper: Profil [sisyphusHill], außen weich ins Gelände; Felsbänder, zwei Rutschen, Abbruchkante im Westen. */
    private fun hill(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val fill = strata(o)
        c.disc(o.x, o.z, HILL_R + 3.0) { x, z, r ->
            val dx = (x - o.x).toDouble()
            val dz = (z - o.z).toDouble()
            val a = atan2(dz, dx)
            val cell = trackAt(o, x, z)
            val natural = c.ground(x, z) - 1
            val top: Int
            val surface: BlockState
            if (cell != null) {
                top = trackTop(o, cell)
                surface = S.TRACK(x, top, z)
            } else {
                val shape = y + sisyphusHill(r)
                val s = ((r - 22.0) / (HILL_R + 3.0 - 22.0)).coerceIn(0.0, 1.0)
                var t = (shape + (natural - shape) * s * s * (3 - 2 * s)).roundToInt()
                if (scarp(o, r, a)) t = (y + ceil((t - y).toDouble() / SCARP_STEP) * SCARP_STEP).toInt().coerceAtMost(y + SISYPHUS_HEIGHT - 1)
                top = t
                val slide = r in 11.0..26.0 && SLIDE_ANGLES.any { abs(Math.IEEEremainder(a - Math.toRadians(it), 2.0 * PI)) * r < 1.8 + (r - 11.0) * 0.09 }
                surface = when {
                    r <= SISYPHUS_SUMMIT + 0.5 -> S.HILL(x, top, z)
                    slide && !scarp(o, r, a) -> S.SLIDE(x, top, z)
                    else -> S.HILL(x, top, z)
                }
            }
            c.footing(x, z, top, fill)
            c.put(x, top, z, surface)
            for (h in 1..18) c.put(x, top + h, z, Kit.AIR)
        }
    }

    /** Der Fels: facettierte Kugel, gerissen, in den Rissen glüht es (Lage aus [sisyphusBoulder], unverändert). */
    private fun boulder(c: BlockCanvas, o: BlockPos) {
        val b = sisyphusBoulder(o)
        val reach = SISYPHUS_BOULDER + 1
        for (dx in -reach..reach) for (dy in -reach..reach) for (dz in -reach..reach) {
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 > 4.4 * 4.4 || abs(dx) + abs(dy) + abs(dz) > 6 || maxOf(abs(dx), abs(dy), abs(dz)) > SISYPHUS_BOULDER) continue
            val crack = d2 > 9 && ((dx - dz + dy) == 0 || (dx + 2 * dz - dy) == 1)
            val x = b.x + dx
            val yy = b.y + dy
            val z = b.z + dz
            c.block(x, yy, z, if (crack) S.EMBER else S.BOULDER(x, yy, z))
        }
    }

    /** Am Fuß die Versuche von gestern: kleinere Brocken, halb im Geröll. */
    private fun footBoulders(c: BlockCanvas, o: BlockPos) {
        val start = trackStart(o)
        for (i in 0 until 3) {
            val a = start - 0.9 - i * 0.7
            val px = o.x + (cos(a) * 28.0).toInt()
            val pz = o.z + (sin(a) * 28.0).toInt()
            if (c.inBox(px, pz)) c.blob(px, c.ground(px, pz), pz, 2.0, 1.6, 2.2, S.BOULDER)
        }
    }

    /**
     * Zuweg: vom Kastenrand radial zum Fuß der Bahn (Punkte wie [TartarusArenas.siteApproach]), Pflaster aus
     * Tartaros-Basalt; links die Felsreihe, rechts die Stele, beidseits ein Feuerpfosten am Anfang.
     */
    private fun approach(c: BlockCanvas, o: BlockPos) {
        val start = trackStart(o)
        val foot = sisyphusTrack(o, 0.0)
        val ux = cos(start)
        val uz = sin(start)
        val from = BlockPos(floor(o.x + 0.5 + ux * ROAD_OUTER).toInt(), o.y, floor(o.z + 0.5 + uz * ROAD_OUTER).toInt())
        val to = BlockPos(floor(foot.x).toInt(), o.y, floor(foot.z).toInt())
        SiteKit.road(c, from, to, ROAD_WIDTH, roadPal)
        // Feuerpfosten beidseits des Weganfangs.
        for (side in intArrayOf(-1, 1)) {
            val px = floor(o.x + 0.5 + ux * ROAD_OUTER - uz * 4.0 * side).toInt()
            val pz = floor(o.z + 0.5 + uz * ROAD_OUTER + ux * 4.0 * side).toInt()
            SiteKit.firePost(c, px, pz)
        }
        standingStones(c, o, start)
        // Die Stele mit der Geschichte steht rechts vom Weg (gegenüber der Felsreihe).
        val sa = start + ROW_FIRST
        val sx = floor(o.x + 0.5 + cos(sa) * ROW_RADIUS).toInt()
        val sz = floor(o.z + 0.5 + sin(sa) * ROW_RADIUS).toInt()
        if (c.inBox(sx, sz)) c.put(sx, c.ground(sx, sz), sz, stele(2))
    }

    /** Pflaster der Zuwege: Tartaros-Basaltziegel, in Flecken roher Basalt und Schwarzstein. */
    private val roadPal: Pal = { x, y, z ->
        when (Kit.noise(x, y, z) % 9) {
            0 -> HhaDecoBlocks.TARTARUS_BASALT.defaultState
            1 -> Blocks.POLISHED_BLACKSTONE.defaultState
            else -> HhaDecoBlocks.TARTARUS_BASALT_BRICKS.defaultState
        }
    }

    /** Felsreihe: fünf aufgerichtete Steine am Fuß, der höchste am Weg. Jeder steht mit Fundament auf dem Gelände. */
    private fun standingStones(c: BlockCanvas, o: BlockPos, start: Double) {
        for ((i, h) in ROW_HEIGHTS.withIndex()) {
            val a = start - ROW_FIRST - i * ROW_SPACING
            val x = floor(o.x + 0.5 + cos(a) * ROW_RADIUS).toInt()
            val z = floor(o.z + 0.5 + sin(a) * ROW_RADIUS).toInt()
            if (!c.inBox(x, z)) continue
            // Zweite Spalte quer zum Radius: der Stein ist eine Platte, keine Nadel.
            val tx = -sin(a)
            val tz = cos(a)
            val x2 = if (abs(tx) >= abs(tz)) x + (if (tx > 0) 1 else -1) else x
            val z2 = if (abs(tx) >= abs(tz)) z else z + (if (tz > 0) 1 else -1)
            val base = c.ground(x, z)
            c.footing(x, z, base - 1, { _, _, _ -> HhaDecoBlocks.TARTARUS_BASALT.defaultState })
            for (k in 0 until h) {
                c.put(x, base + k, z, if (k == h - 1) HhaDecoBlocks.CHISELED_TARTARUS_BASALT.defaultState else HhaDecoBlocks.TARTARUS_BASALT.defaultState)
            }
            if (h >= 3 && c.inBox(x2, z2) && c.ground(x2, z2) >= base) {
                for (k in 0 until h - 1) c.put(x2, base + k, z2, if (Kit.noise(x2, base + k, z2) % 3 == 0) Blocks.BASALT.defaultState else HhaDecoBlocks.TARTARUS_BASALT.defaultState)
            }
        }
    }

    // =============================================================================================================
    // Arena-Kern
    // =============================================================================================================

    /**
     * Arena-Kern des Sisyphos, ohne Geländebezug relativ zu [o]: Lauffläche der Bahn mit Rillen und Bordsteinen,
     * Ausweichbuchten, Gipfelplatz und Gipfelaltar. Unter dem liegenden Fels zeichnet er nichts.
     */
    fun arenaCore(c: BlockCanvas, o: BlockPos) {
        track(c, o)
        curbs(c, o)
        bays(c, o)
        summit(c, o)
        altar(c, o)
    }

    /** Lauffläche der Bahn: Mitte geglätteter Basalt, zwei Glutrillen, Ränder Lehm; darüber frei. */
    private fun track(c: BlockCanvas, o: BlockPos) {
        c.disc(o.x, o.z, TRACK_FROM + SISYPHUS_TRACK_WIDTH / 2.0 + 0.5) { x, z, r ->
            if (r <= SISYPHUS_SUMMIT) return@disc
            val cell = trackAt(o, x, z) ?: return@disc
            if (underBoulder(o, x, z)) return@disc
            val top = trackTop(o, cell)
            val d = abs(r - cell.trackR)
            val state = when {
                d < RUT_FROM -> S.RUT(x, top, z)
                d <= RUT_TO -> if (Kit.noise(x, 3, z) % 9 == 0) Blocks.SMOOTH_BASALT.defaultState else HhaDecoBlocks.EMBER_BASALT.defaultState
                else -> S.TRACK(x, top, z)
            }
            c.put(x, top, z, state)
            for (h in 1..TRACK_CLEAR) c.put(x, top + h, z, Kit.AIR)
        }
    }

    /** Bordsteine an der Talseite: Basalt-Mauerstücke, verbunden, alle [CURB_PERIOD] Blöcke eine Lücke. */
    private fun curbs(c: BlockCanvas, o: BlockPos) {
        val cells = HashMap<Long, Int>()
        c.disc(o.x, o.z, TRACK_FROM + SISYPHUS_TRACK_WIDTH / 2.0 + 0.5) { x, z, r ->
            val cell = trackAt(o, x, z) ?: return@disc
            if (r - cell.trackR <= CURB_FROM || cell.t < CURB_FREE_FOOT || underBoulder(o, x, z)) return@disc
            if (Math.floorMod(cell.along.roundToInt(), CURB_PERIOD.toInt()) < CURB_GAP) return@disc
            cells[BlockPos.asLong(x, 0, z)] = trackTop(o, cell) + 1
        }
        for ((k, y) in cells) {
            val x = BlockPos.unpackLongX(k)
            val z = BlockPos.unpackLongZ(k)
            var state = HhaDecoBlocks.TARTARUS_BASALT_BRICK_WALL.defaultState
            val linked = HORIZONTAL.filter { cells[BlockPos.asLong(x + it.offsetX, 0, z + it.offsetZ)] == y }
            for (dir in HORIZONTAL) state = state.with(WallBlock.WALL_SHAPE_PROPERTIES_BY_DIRECTION[dir]!!, if (dir in linked) WallShape.LOW else WallShape.NONE)
            val straight = linked.size == 2 && linked[0] == linked[1].opposite
            c.put(x, y, z, state.with(WallBlock.UP, !straight))
        }
    }

    private val HORIZONTAL = listOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)

    /**
     * Ausweichbuchten: An drei Stellen weicht die Bergseite [BAY_DEPTH] Blöcke zurück, auf Bahnhöhe; am oberen Ende
     * (bergauf, woher der Fels rollt) steht eine Felsnase aus Basalt.
     */
    private fun bays(c: BlockCanvas, o: BlockPos) {
        val start = trackStart(o)
        for (t in BAYS) {
            val rc = TRACK_FROM + (TRACK_TO - TRACK_FROM) * t
            val ac = start + t * TRACK_TURNS * 2.0 * PI
            val top = o.y + sisyphusHill(rc).roundToInt()
            val inner = rc - SISYPHUS_TRACK_WIDTH / 2.0
            val halfAngle = BAY_LENGTH / 2.0 / rc
            c.disc(o.x, o.z, inner + 0.5) { x, z, r ->
                if (r < inner - BAY_DEPTH || r >= inner) return@disc
                val a = atan2((z - o.z).toDouble(), (x - o.x).toDouble())
                if (abs(Math.IEEEremainder(a - ac, 2.0 * PI)) > halfAngle) return@disc
                if (trackAt(o, x, z) != null || underBoulder(o, x, z)) return@disc
                c.put(x, top, z, S.TRACK(x, top, z))
                for (h in 1..SISYPHUS_HEIGHT) c.put(x, top + h, z, Kit.AIR)
            }
            // Felsnase am oberen Ende der Bucht (größerer Winkel = bergauf).
            val na = ac + halfAngle + 0.6 / rc
            val nr = inner - BAY_DEPTH / 2.0
            val nx = floor(o.x + 0.5 + cos(na) * nr).toInt()
            val nz = floor(o.z + 0.5 + sin(na) * nr).toInt()
            // Die Nase steht nur auf der Bergseite: Zellen der Bahn bleiben frei.
            c.disc(nx, nz, NOSE_RADIUS) { x, z, d ->
                if (trackAt(o, x, z) != null || underBoulder(o, x, z)) return@disc
                val h = if (d < 0.9) NOSE_HEIGHT else NOSE_HEIGHT - 1
                for (k in 1..h) c.put(x, top + k, z, if (Kit.noise(x, top + k, z) % 4 == 0) Blocks.BASALT.defaultState else HhaDecoBlocks.TARTARUS_BASALT.defaultState)
            }
        }
    }

    /** Gipfelplatz r 9 auf o.y + 14: polierter Andesit, Ringband aus Basaltziegeln, Fuge von der Bahn zur Mitte. */
    private fun summit(c: BlockCanvas, o: BlockPos) {
        val y = o.y + SISYPHUS_HEIGHT
        val arrive = trackStart(o) + TRACK_TURNS * 2.0 * PI
        c.disc(o.x, o.z, SISYPHUS_SUMMIT.toDouble()) { x, z, r ->
            val dx = (x - o.x).toDouble()
            val dz = (z - o.z).toDouble()
            val off = abs(Math.IEEEremainder(atan2(dz, dx) - arrive, 2.0 * PI))
            val joint = off < PI / 2 && r * sin(off) < 0.55 && r > SUMMIT_MARK
            val state = when {
                r <= SUMMIT_MARK -> HhaDecoBlocks.CHISELED_TARTARUS_BASALT.defaultState
                r > SUMMIT_BAND_INNER && r <= SUMMIT_BAND_OUTER -> HhaDecoBlocks.TARTARUS_BASALT_BRICKS.defaultState
                joint -> HhaDecoBlocks.POLISHED_TARTARUS_BASALT.defaultState
                else -> S.SUMMIT(x, y, z)
            }
            c.put(x, y - 1, z, S.HILL(x, y - 1, z))
            c.put(x, y, z, state)
            for (h in 1..SUMMIT_CLEAR) c.put(x, y + h, z, Kit.AIR)
        }
    }

    /**
     * Gipfelaltar am Nordrand (Vertrag `SISYPHUS_ALTAR`): massiver Block aus gemeißeltem Basalt, 3 × 2 × 2, ein Sockel aus
     * Stufen, oben eine Bronzeschale mit grünem Feuer (Fernzeichen).
     */
    private fun altar(c: BlockCanvas, o: BlockPos) {
        val box = TartarusArenas.SISYPHUS_ALTAR
        val y = TartarusArenas.sisyphusFloor(o)
        for (x in box.minX..box.maxX) for (z in box.minZ..box.maxZ) for (h in box.minY..box.maxY) {
            c.put(o.x + x, y + h, o.z + z, HhaDecoBlocks.CHISELED_TARTARUS_BASALT.defaultState)
        }
        // Sockelstufen an den drei offenen Seiten (Süden zur Mitte, Osten, Westen), Norden fällt der Hang ab.
        for (x in box.minX..box.maxX) c.put(o.x + x, y, o.z + box.maxZ + 1, Kit.stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, Direction.NORTH))
        for (z in box.minZ..box.maxZ) {
            c.put(o.x + box.minX - 1, y, o.z + z, Kit.stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, Direction.EAST))
            c.put(o.x + box.maxX + 1, y, o.z + z, Kit.stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, Direction.WEST))
        }
        // Schale: Bronze in der Mitte, Bronzestufen als Rand, darauf das Feuer.
        val bowl = y + box.maxY + 1
        for (z in box.minZ..box.maxZ) {
            c.put(o.x + box.minX, bowl, o.z + z, Kit.slab(HhaDecoBlocks.TARTARUS_BRONZE_SLAB))
            c.put(o.x + box.maxX, bowl, o.z + z, Kit.slab(HhaDecoBlocks.TARTARUS_BRONZE_SLAB))
            c.put(o.x, bowl, o.z + z, HhaDecoBlocks.TARTARUS_BRONZE.defaultState)
            c.put(o.x, bowl + 1, o.z + z, Kit.FIRE)
        }
    }
}
