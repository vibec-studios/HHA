package dev.henny.hha.world.palace

import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.world.*
import dev.henny.hha.world.PalacePlan.pal
import dev.henny.hha.world.TartarusArenas
import dev.henny.hha.world.TartarusPlans.CHANDELIER_Y
import dev.henny.hha.world.TartarusPlans.DAIS_HEIGHT
import dev.henny.hha.world.TartarusPlans.DOME_RISE
import dev.henny.hha.world.TartarusPlans.DRUM_TOP
import dev.henny.hha.world.TartarusPlans.EYE
import dev.henny.hha.world.TartarusPlans.HALL
import dev.henny.hha.world.TartarusPlans.OCULUS_Y
import dev.henny.hha.world.TartarusPlans.PILLAR_HEIGHT
import dev.henny.hha.world.TartarusPlans.SEAL_HALF_WIDTH
import dev.henny.hha.world.TartarusPlans.THRONE_Z
import dev.henny.hha.world.TartarusPlans.coverPillars
import dev.henny.hha.world.arena.ArenaGeo
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.PillarBlock
import net.minecraft.entity.EntityType
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Der Thronsaal des Palasts (Überarbeitung 4, P11). [arenaCore] zeichnet den ganzen Saal samt Thron; der volle
 * Palastplan ruft dieselbe Funktion, der Bauplan `palace_hall` ebenso (auch für alte Paläste).
 *
 * Höhenstaffel von unten nach oben: Bodenbild (Kern r 38) → Umgang (+2, Blockringe 39 … 44, zwei Bögen) → Sockelzone
 * der Wand (+1 … +6) → Beinhaus-Nischen mit Rundbögen (+7 … +12) → Gesims (+13) → Empore (+16) mit Brüstung und zwei
 * Wendeltreppen → Kapitelle der zwölf Wandpfeiler (+27) → Ketten zum Kronleuchter (+40, Ringe r 11 und r 8) →
 * Rippen und Seelenadern unter der Kuppel → Auge.
 *
 * Alles liegt im Saalzylinder (r ≤ [HALL], von o.y − 2 bis unter die Kuppelschale); das sichert [HallClip] beim
 * Zeichnen. Die Maße von Deckung, Umgang, Treppen und Podest kommen aus dem Vertrag [TartarusArenas.hall].
 */
internal object PalaceHall {

    // --- Bodenbild -------------------------------------------------------------------------------------------------

    /** Mittelmedaillon mit dem Bident (Abstand der Blockmitten). */
    private const val MEDALLION = 5.0
    /** Rand des Medaillons ab diesem Abstand. */
    private const val MEDALLION_RIM = 4.2
    /** Blockringe der gemeißelten Bänder: Säulenring und Kante zum Umgang. */
    private const val BAND_PILLARS = 27
    private const val BAND_EDGE = 38
    /** Lichtpunkte im Kantenband: einer je so viel Grad. */
    private const val EDGE_LIGHT_EVERY = 15.0
    /** Läufer Siegel → Thron: halbe Breite, Lage der zwei Seelenadern, Abstand der Pfeile. */
    private const val RUNNER_HALF = 3
    private const val RUNNER_VEIN = 2
    private const val CHEVRON_EVERY = 6
    /** Der Läufer endet im Süden kurz vor dem Siegel (dz) und im Norden am Fuß des Podests. */
    private const val RUNNER_SOUTH = 45
    /** Speichen von der Mitte zu den Säulen: von, bis (Abstand), halbe Breite. */
    private const val SPOKE_FROM = 6.0
    private const val SPOKE_TO = 24.0
    private const val SPOKE_HALF = 1.0

    // --- Deckungssäulen (Form 5 × 5, r 2, 29 hoch: Vertrag) --------------------------------------------------------

    /** Bänder des Schafts: alle 7 Blöcke ab der Oberkante des Sockels (+2). */
    private const val PILLAR_PLINTH = 2
    private const val PILLAR_BAND_EVERY = 7
    /** Konsole mit Feuerschale an der Innenseite: Schale auf dieser Höhe, Abstand zur Säulenmitte. */
    private const val PILLAR_BOWL_Y = 5
    private const val PILLAR_BOWL_OFFSET = 3.0

    // --- Umgang ----------------------------------------------------------------------------------------------------

    /** Blendnischen in der Stützmauer: eine je so viel Grad; an den Treppen bleibt so viel Grad frei. */
    private const val WALK_NICHE_EVERY = 9.0
    private const val WALK_STAIR_KEEP = 5.0
    /** Brüstung an der Innenkante: nur in den Feldern zwischen den Wandpfeiler-Achsen (Abstand zur Achse in Blöcken). */
    private const val PARAPET_FROM = 2.0
    private const val PARAPET_TO = 7.5
    private const val PARAPET_STAIR_FROM = 3.5

    // --- Wand ------------------------------------------------------------------------------------------------------

    /** Die Wandzone liegt ganz außerhalb des äußeren Einsturzrings (r > 44,5) und berührt den Umgang nur mit einer Fuge. */
    private const val WALL_INNER = 44.5
    private const val WALL_FRONT = 45.5
    private const val SOCLE_TOP = 6
    private const val OSSUARY_Y = 7
    private const val NICHE_HALF = 1.5
    private const val NICHE_FROM_WINDOW = 6.8
    private const val CORNICE_Y = 13
    /** Fenster der Trommel (`PalacePlan.drum`): alle 30° ab 0°, außer Süd und Nord; halbe Breite in Blöcken plus Laibung. */
    private const val WINDOW_EVERY = 30.0
    private const val WINDOW_HALF = 3.4
    /** Zwölf Wandpfeiler zwischen den Fenstern (15° + 30° · k), halbe Breite, Kapitell. */
    private const val PILASTER_PHASE = 15.0
    private const val PILASTER_HALF = 1.6
    private const val PILASTER_TOP = DRUM_TOP - 3
    /** Über dem Thronpodest bleibt alles bis +30 frei: Diese Wandpfeiler bekommen keine Kette zum Kronleuchter. */
    private val PILASTER_NO_CHAIN = setOf(255.0, 285.0)

    // --- Empore und Wendeltreppen ----------------------------------------------------------------------------------

    private const val GALLERY_Y = 16
    private const val GALLERY_FROM = HALL - 4.5
    private const val GALLERY_RIM = HALL - 3.4
    /** Ein Pfosten mit Laterne in der Emporenbrüstung je so viel Grad. */
    private const val GALLERY_POST_EVERY = 10.0
    /** Treppentürme 3 × 3 am Süd-Ende jedes Umgang-Bogens (im Ring 39 … 44, ganz außerhalb von r 36). */
    private const val TOWER_DX = 12
    private const val TOWER_DZ = 41

    // --- Kuppel und Kronleuchter -----------------------------------------------------------------------------------

    private const val RIB_EVERY = 30.0
    private const val RIB_HALF = 0.75
    private const val CHANDELIER_OUTER = 11.0
    private const val CHANDELIER_INNER = 8.0

    // =============================================================================================================

    /** Arena-Kern des Thronsaals: der ganze Saal und der Thron, in derselben Reihenfolge wie im vollen Plan. */
    fun arenaCore(c: BlockCanvas, o: BlockPos) {
        val clip = HallClip(c, o)
        hall(clip, o)
        throne(clip, o)
    }

    /** Der Saal ohne Thron, Teil für Teil von unten nach oben. */
    internal fun hall(c: BlockCanvas, o: BlockPos) {
        floor(c, o)
        walks(c, o)
        wall(c, o)
        gallery(c, o)
        pilasters(c, o)
        for (side in intArrayOf(-1, 1)) stairTower(c, o, side)
        for ((dx, dz) in coverPillars()) pillar(c, o, o.x + dx, o.z + dz)
        chandelier(c, o)
        domeLines(c, o)
    }

    // --- Hilfen ----------------------------------------------------------------------------------------------------

    private fun s(b: net.minecraft.block.Block): BlockState = b.defaultState

    /** Blockring nach dem Vertrag („r a–b“ = Blockringe a … b). */
    private fun ring(r: Double): Int = floor(r + 0.5).toInt()

    private fun angle(dx: Int, dz: Int): Double = ArenaGeo.angleOf(dx.toDouble(), dz.toDouble())

    /** Kleinster Winkelabstand in Grad. */
    private fun angDiff(a: Double, b: Double): Double {
        val d = Math.floorMod(Math.round((a - b) * 1000.0), 360_000L) / 1000.0
        return min(d, 360.0 - d)
    }

    /** Bogenabstand (Blöcke) von [deg] zur nächsten Achse im Raster [every] mit Versatz [phase]. */
    private fun offAxis(deg: Double, r: Double, every: Double, phase: Double = 0.0): Double {
        val d = Math.IEEEremainder(deg - phase, every)
        return abs(d) * Math.PI / 180.0 * r
    }

    /** Vorzeichenbehafteter Bogenabstand zur nächsten Achse. */
    private fun signedOffAxis(deg: Double, r: Double, every: Double, phase: Double = 0.0): Double =
        Math.IEEEremainder(deg - phase, every) * Math.PI / 180.0 * r

    /** Zur Wand hin (weg von der Saalmitte). */
    private fun outward(o: BlockPos, x: Int, z: Int): Direction = Kit.inward(o.x - x, o.z - z)

    /** Zur Saalmitte hin. */
    private fun inward(o: BlockPos, x: Int, z: Int): Direction = Kit.inward(x - o.x, z - o.z)

    /** Unterste freie Höhe unter der Kuppelschale (wie `PalacePlan.dome`: Schale ab `inner.toInt()`). */
    private fun domeCeiling(oy: Int, r: Double): Int {
        val base = oy + DRUM_TOP + 1
        val inner = base - 3 + (DOME_RISE - 1) * sqrt((1.0 - (r / HALL) * (r / HALL)).coerceAtLeast(0.0))
        return inner.toInt() - 1
    }

    /** Im Bereich vor dem Siegel (Süden) steht keine Wandverkleidung, damit Tor und Bogen frei bleiben. */
    private fun sealZone(dx: Int, dz: Int): Boolean = dz > 0 && abs(dx) <= SEAL_HALF_WIDTH + 2

    // --- Boden -----------------------------------------------------------------------------------------------------

    /** Unterbau (2 Lagen), Bodenbild und der freie Raum bis zur Trommeloberkante. */
    private fun floor(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        c.disc(o.x, o.z, HALL.toDouble()) { x, z, r ->
            c.put(x, y - 2, z, s(Blocks.BLACKSTONE))
            c.put(x, y - 1, z, s(Blocks.POLISHED_BLACKSTONE))
            c.put(x, y, z, hallFloor(x - o.x, z - o.z, r, x, y, z))
            for (h in y + 1..y + DRUM_TOP - 3) c.put(x, h, z, Kit.AIR)
        }
    }

    private val FIELD = DecoPalettes.basaltFloor(0.04)

    /**
     * Bodenbild: polierter Basalt als Fläche, gemeißelte Ringbänder bei 27 (Säulen) und 38 (Kante zum Umgang), acht
     * Speichen aus Schwarzstein-Ziegeln zu den Säulen, der Läufer Siegel → Thron mit zwei pulsierenden Seelenadern und
     * Pfeilen nach Norden, in der Mitte das Medaillon mit dem Bident. Gold nur im Bident und an Kanten.
     */
    private fun hallFloor(dx: Int, dz: Int, r: Double, x: Int, y: Int, z: Int): BlockState {
        val band = ring(r)
        val ax = abs(dx)
        val runner = ax <= RUNNER_HALF && (dz in 1..RUNNER_SOUTH || dz in THRONE_Z + TartarusArenas.HALL_DAIS_FOOT.toInt()..-1)
        return when {
            r <= MEDALLION -> medallion(dx, dz, r)
            runner && ax == RUNNER_VEIN -> s(HhaDecoBlocks.SOUL_VEIN_BASALT)
            runner && chevron(dx, dz) -> s(HhaDecoBlocks.SOUL_VEIN_BASALT)
            band == BAND_EDGE && offAxis(angle(dx, dz), r, EDGE_LIGHT_EVERY, EDGE_LIGHT_EVERY / 2) < 0.5 -> s(Blocks.VERDANT_FROGLIGHT)
            band == BAND_PILLARS || band == BAND_EDGE -> s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT)
            runner && ax == RUNNER_HALF -> s(Blocks.POLISHED_BLACKSTONE)
            runner -> s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS)
            r > BAND_EDGE + 0.5 -> pal.PLINTH(x, y, z)
            spoke(dx, dz, r) -> s(Blocks.POLISHED_BLACKSTONE_BRICKS)
            else -> FIELD(x, y, z)
        }
    }

    /** Pfeil im Läufer, Spitze nach Norden (zum Thron): Spitze in der Mitte, Schenkel eine Reihe südlich. */
    private fun chevron(dx: Int, dz: Int): Boolean =
        (dx == 0 && Math.floorMod(dz, CHEVRON_EVERY) == 0) || (abs(dx) == 1 && Math.floorMod(dz - 1, CHEVRON_EVERY) == 0)

    /** Speiche zu einer der acht Säulen (22,5° + 45° · k). */
    private fun spoke(dx: Int, dz: Int, r: Double): Boolean {
        if (r < SPOKE_FROM || r > SPOKE_TO) return false
        return offAxis(angle(dx, dz), r, 45.0, 22.5) < SPOKE_HALF
    }

    /** Medaillon r 5: gemeißelter Rand, schwarzes Feld, der Bident in Gold mit den Zinken nach Norden (zum Thron). */
    private fun medallion(dx: Int, dz: Int, r: Double): BlockState {
        val bident = (dx == 0 && dz in -1..3) || (dz == -1 && abs(dx) <= 2) || (abs(dx) == 2 && dz in -3..-2)
        return when {
            bident -> s(Blocks.GILDED_BLACKSTONE)
            r > MEDALLION_RIM -> s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT)
            else -> s(Blocks.POLISHED_BLACKSTONE)
        }
    }

    // --- Umgang ----------------------------------------------------------------------------------------------------

    /**
     * Der Umgang auf +2 in zwei Bögen (Zonen aus dem Vertrag): eigener Unterbau auf dem Saalboden, Stützmauer mit
     * Blendnischen (Seelenlaterne), Belag poliert, gemeißelte Kante nach innen, an den Treppen Gold; Brüstung 1 hoch
     * nur in den Feldern zwischen den Wandpfeiler-Achsen; vier Treppen 5 breit auf den Vertragszellen.
     */
    private fun walks(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val spec = TartarusArenas.hall(o)
        val walks = spec.levels.filter { it.id.startsWith("walk") }
        val walkTop = walks.first().floorY - 1
        val stairs = TartarusArenas.HALL_WALK_STAIRS
        c.disc(o.x, o.z, TartarusArenas.HALL_WALK_OUTER + 0.5) { x, z, r ->
            if (walks.none { it.zone.containsCell(x, z) }) return@disc
            val dx = x - o.x
            val dz = z - o.z
            val deg = angle(dx, dz)
            val edge = ring(r) == TartarusArenas.HALL_WALK_INNER.toInt()
            val nearStair = stairs.minOf { angDiff(deg, it) }
            // Unterbau: eine Lage auf dem Saalboden, in der Stirn die Blendnischen.
            for (h in y + 1 until walkTop) {
                val niche = edge && nearStair > WALK_STAIR_KEEP && offAxis(deg, r, WALK_NICHE_EVERY) < 0.6
                c.put(x, h, z, when {
                    niche -> Kit.lantern(hanging = false)
                    edge -> DecoPalettes.BASALT_WALL(x, h, z)
                    else -> s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS)
                })
            }
            c.put(x, walkTop, z, when {
                edge && nearStair * Math.PI / 180.0 * r <= TartarusArenas.HALL_WALK_STAIR_WIDTH / 2.0 -> s(Blocks.GILDED_BLACKSTONE)
                edge -> s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT)
                else -> s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT)
            })
            // Brüstung an der Innenkante, nur zwischen den Wandpfeiler-Achsen; an jedem Feldende ein Pfosten mit Laterne.
            if (edge) {
                val t = offAxis(deg, r, WINDOW_EVERY, PILASTER_PHASE)
                val from = if (nearStair * Math.PI / 180.0 * r < PARAPET_TO + 1.0) PARAPET_STAIR_FROM else PARAPET_FROM
                if (t in from..PARAPET_TO) {
                    val post = t < from + 1.0 || t > PARAPET_TO - 1.0
                    c.put(x, walkTop + 1, z, if (post) s(Blocks.POLISHED_BLACKSTONE_BRICKS) else s(HhaDecoBlocks.TARTARUS_BASALT_BRICK_WALL))
                    if (post) c.put(x, walkTop + 2, z, Kit.lantern(hanging = false))
                }
            }
        }
        // Treppen auf den Vertragszellen (Blockringe 37 und 38), steigen nach außen; darunter voll gemauert.
        for (level in walks) {
            for (p in level.access) {
                c.put(p.x, p.y, p.z, Kit.stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, outward(o, p.x, p.z)))
                for (h in y + 1 until p.y) c.put(p.x, h, p.z, s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS))
            }
        }
    }

    // --- Wand ------------------------------------------------------------------------------------------------------

    /**
     * Wandzone r 44,5 … 46 (Fuge zum Umgang, nichts davon fällt beim Einsturz): Sockel +1 … +6 mit gemeißeltem Band,
     * darüber zwischen Fenster und Wandpfeiler je eine Beinhaus-Nische mit Rundbogen (+7 … +12), Gesims auf +13. Die
     * Fenster der Trommel bleiben offen, vor dem Siegel bleibt alles frei.
     */
    private fun wall(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val wallPal = DecoPalettes.basaltWall(0.12)
        c.disc(o.x, o.z, HALL.toDouble()) { x, z, r ->
            if (r <= WALL_INNER) return@disc
            val dx = x - o.x
            val dz = z - o.z
            if (sealZone(dx, dz)) return@disc
            val deg = angle(dx, dz)
            val front = r <= WALL_FRONT
            val k = Math.floorMod(Math.round(deg / WINDOW_EVERY).toInt(), 12)
            val windowed = k != 3 && k != 9
            val sw = signedOffAxis(deg, WALL_FRONT, WINDOW_EVERY)
            val window = windowed && abs(sw) <= WINDOW_HALF
            // Sockelzone
            c.put(x, y + 1, z, s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
            for (h in y + 2 until y + SOCLE_TOP) c.put(x, h, z, wallPal(x, h, z))
            c.put(x, y + SOCLE_TOP, z, s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT))
            if (window) {
                if (front) c.put(x, y + OSSUARY_Y, z, Kit.slab(Blocks.POLISHED_BLACKSTONE_BRICK_SLAB))
                return@disc
            }
            // Nischenfeld: Nischen bei ±6,8 Blöcken neben der Fenstermitte; im Nordfeld (hinter dem Thron) auch in der
            // Mitte, im Südfeld neben dem Siegel keine (dort ist nur Platz für glatte Wand).
            val centers = when (k) {
                3 -> doubleArrayOf()
                9 -> doubleArrayOf(-NICHE_FROM_WINDOW, 0.0, NICHE_FROM_WINDOW)
                else -> doubleArrayOf(-NICHE_FROM_WINDOW, NICHE_FROM_WINDOW)
            }
            val off = centers.minByOrNull { abs(sw - it) }?.let { sw - it } ?: Double.MAX_VALUE
            val opening = front && abs(off) <= NICHE_HALF
            for (h in y + OSSUARY_Y..y + CORNICE_Y - 1) {
                val dy = h - y
                val state = when {
                    opening && dy == OSSUARY_Y -> s(Blocks.POLISHED_BLACKSTONE)
                    opening && dy == OSSUARY_Y + 1 && abs(off) < 0.5 -> skullFacing(inward(o, x, z), wither = Kit.noise(x, h, z) % 4 == 0)
                    opening && dy == OSSUARY_Y + 1 && Kit.noise(x, 8, z) % 3 == 0 -> skull(Kit.noise(x, h, z) % 16)
                    opening && dy in OSSUARY_Y + 1..OSSUARY_Y + 2 -> Kit.AIR
                    // Rundbogen: oben ist nur die Mitte offen, die Seiten sind Bogensteine.
                    opening && dy == OSSUARY_Y + 3 && abs(off) < 0.5 -> Kit.AIR
                    opening && dy == OSSUARY_Y + 3 -> Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, if (off > 0) sideways(o, x, z, 1) else sideways(o, x, z, -1), top = true)
                    front && abs(off) <= NICHE_HALF + 1.0 -> s(Blocks.POLISHED_BLACKSTONE_BRICKS)
                    dy == CORNICE_Y - 1 -> s(Blocks.POLISHED_BLACKSTONE_BRICKS)
                    else -> wallPal(x, h, z)
                }
                c.put(x, h, z, state)
            }
            c.put(x, y + CORNICE_Y, z, if (front) Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, outward(o, x, z), top = true) else pal.TRIM(x, y + CORNICE_Y, z))
        }
    }

    /** Tangential: [sign] = +1 in Richtung wachsender Winkel, −1 dagegen (gerundet auf eine Himmelsrichtung). */
    private fun sideways(o: BlockPos, x: Int, z: Int, sign: Int): Direction {
        val dx = (x - o.x).toDouble()
        val dz = (z - o.z).toDouble()
        // Wachsender Winkel (0° Ost, 90° Süd): Tangente (−dz, dx).
        val tx = -dz * sign
        val tz = dx * sign
        return if (abs(tx) >= abs(tz)) (if (tx > 0) Direction.EAST else Direction.WEST) else (if (tz > 0) Direction.SOUTH else Direction.NORTH)
    }

    /** Zwölf Wandpfeiler (15° + 30° · k) in der Wandzone bis +27 mit Kapitell, die meisten mit Kette zum Kronleuchter. */
    private fun pilasters(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val top = y + PILASTER_TOP
        c.disc(o.x, o.z, HALL.toDouble()) { x, z, r ->
            if (r <= WALL_INNER) return@disc
            val dx = x - o.x
            val dz = z - o.z
            val t = offAxis(angle(dx, dz), WALL_FRONT, WINDOW_EVERY, PILASTER_PHASE)
            if (t > PILASTER_HALF) return@disc
            for (h in y + 1..top) {
                c.put(x, h, z, when {
                    h == y + 1 -> s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT)
                    h == y + 2 -> s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT)
                    h == top - 1 -> s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT)
                    h == top -> if (t < 0.5) s(Blocks.GILDED_BLACKSTONE) else s(Blocks.POLISHED_BLACKSTONE)
                    else -> s(HhaDecoBlocks.TARTARUS_BASALT_PILLAR)
                })
            }
        }
        val ring = y + CHANDELIER_Y
        for (i in 0 until 12) {
            val deg = PILASTER_PHASE + i * WINDOW_EVERY
            if (deg in PILASTER_NO_CHAIN) continue
            val from = ArenaGeo.polar(o, WALL_INNER + 0.3, deg)
            val to = ArenaGeo.polar(o, CHANDELIER_OUTER + 1.0, deg)
            c.heavyChain(from.x, top + 1, from.z, to.x, ring - 1, to.z, 2.0, Kit.chain(), Kit.chain())
        }
    }

    // --- Empore ----------------------------------------------------------------------------------------------------

    /** Empore +16 mit Kragsteinen, Brüstung aus Basaltmauer und Pfosten mit Laterne; darunter die 32 Hängelaternen. */
    private fun gallery(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val g = y + GALLERY_Y
        c.disc(o.x, o.z, HALL.toDouble()) { x, z, r ->
            if (r <= GALLERY_FROM) return@disc
            val rim = r < GALLERY_FROM + 1.0
            c.put(x, g, z, if (rim) Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, outward(o, x, z), top = true) else pal.PLINTH(x, g, z))
            if (r <= GALLERY_RIM && !sealZone(x - o.x, z - o.z)) {
                val post = offAxis(angle(x - o.x, z - o.z), r, GALLERY_POST_EVERY) < 0.6
                c.put(x, g + 1, z, if (post) s(Blocks.POLISHED_BLACKSTONE_BRICKS) else s(HhaDecoBlocks.TARTARUS_BASALT_BRICK_WALL))
                if (post) c.put(x, g + 2, z, Kit.lantern(hanging = false))
            }
        }
        for (i in 0 until 32) {
            val a = i * Math.PI / 16.0
            val lx = PalacePlan.polarX(o, a, HALL - 3.5, 0.0)
            val lz = PalacePlan.polarZ(o, a, HALL - 3.5, 0.0)
            for (h in 1..2) c.block(lx, g - h, lz, Kit.chain())
            c.block(lx, g - 3, lz, Kit.lantern())
        }
    }

    /**
     * Wendeltreppe 3 × 3 vom Umgang (+2) zur Empore (+16), offen gemauert: Spindel aus Basaltsäule, je Stufe eine Treppe
     * auf einem Mauerstein, eine Umdrehung = 8 Stufen (6 Blöcke Kopffreiheit). Die letzte Stufe liegt an der Wandseite
     * auf Emporenhöhe. [side] −1 West, +1 Ost (gespiegelt).
     */
    private fun stairTower(c: BlockCanvas, o: BlockPos, side: Int) {
        val y = o.y
        val cx = o.x + side * TOWER_DX
        val cz = o.z + TOWER_DZ
        val g = y + GALLERY_Y
        val walkTop = y + TartarusArenas.HALL_WALK_HEIGHT
        for (dx in -1..1) for (dz in -1..1) for (h in walkTop + 1..g + 3) c.put(cx + dx, h, cz + dz, Kit.AIR)
        // Umlauf im Uhrzeigersinn (von oben, Ost-Turm) bzw. dagegen (West-Turm).
        val ringCells = listOf(-1 to -1, 0 to -1, 1 to -1, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0)
        val steps = g - walkTop
        // Die letzte Stufe liegt an der Außenseite (Richtung Osten bzw. Westen): Daneben ist die Empore frei vom Wandpfeiler.
        val last = ringCells.indexOf(side to 0)
        for (k in 0 until steps) {
            val idx = Math.floorMod(last - (steps - 1 - k) * side, 8)
            val next = Math.floorMod(idx + side, 8)
            val (ox, oz) = ringCells[idx]
            val (nx, nz) = ringCells[next]
            val facing = when { nx > ox -> Direction.EAST; nx < ox -> Direction.WEST; nz > oz -> Direction.SOUTH; else -> Direction.NORTH }
            val h = walkTop + 1 + k
            if (k > 0) c.put(cx + ox, h - 1, cz + oz, s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS))
            c.put(cx + ox, h, cz + oz, if (k == steps - 1) s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT) else Kit.stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, facing))
        }
        for (h in walkTop + 1..g + 1) c.put(cx, h, cz, s(HhaDecoBlocks.TARTARUS_BASALT_PILLAR))
        c.put(cx, g + 2, cz, Kit.lantern(hanging = false))
    }

    // --- Deckungssäulen --------------------------------------------------------------------------------------------

    /**
     * Deckungssäule (5 × 5, r 2, bis +29): Sockel 2 hoch mit Gold an den Ecken, Schaft aus Basaltsäule mit gemeißelten
     * Bändern alle 7, zur Mitte hin eine Seelenader, auf +5 eine Konsole mit grüner Feuerschale, oben Kapitell und Deckplatte.
     */
    private fun pillar(c: BlockCanvas, o: BlockPos, cx: Int, cz: Int) {
        val y = o.y
        val top = y + PILLAR_HEIGHT
        val ux = (o.x - cx).toDouble()
        val uz = (o.z - cz).toDouble()
        val len = hypot(ux, uz)
        val veinX = cx + Math.round(ux / len * 2.0).toInt()
        val veinZ = cz + Math.round(uz / len * 2.0).toInt()
        c.disc(cx, cz, 3.6) { x, z, d ->
            c.put(x, y + 1, z, if (d > 2.9) Kit.stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, Kit.inward(x - cx, z - cz)) else s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
        }
        c.disc(cx, cz, 2.9) { x, z, _ ->
            val corner = abs(x - cx) == 2 && abs(z - cz) == 2
            c.put(x, y + PILLAR_PLINTH, z, if (corner) s(Blocks.GILDED_BLACKSTONE) else s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT))
        }
        c.disc(cx, cz, 2.6) { x, z, d ->
            for (h in y + PILLAR_PLINTH + 1 until top - 1) {
                val band = (h - y - PILLAR_PLINTH) % PILLAR_BAND_EVERY == 0
                c.put(x, h, z, when {
                    x == veinX && z == veinZ -> s(HhaDecoBlocks.SOUL_VEIN_BASALT)
                    band && d > 1.6 -> s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT)
                    else -> s(HhaDecoBlocks.TARTARUS_BASALT_PILLAR)
                })
            }
        }
        // Das oberste Band kragt aus: Daran hängen die vier Laternen.
        val hanger = y + PILLAR_PLINTH + PILLAR_BAND_EVERY * 3
        c.disc(cx, cz, 3.2) { x, z, d ->
            if (d > 2.6) c.put(x, hanger, z, Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Kit.inward(x - cx, z - cz), top = true))
        }
        for (i in 0 until 4) {
            val a = i * Math.PI / 2.0 + Math.PI / 4.0
            val sx = cx + (cos(a) * 3.2).toInt()
            val sz = cz + (sin(a) * 3.2).toInt()
            for (h in 1..2) c.block(sx, hanger - h, sz, Kit.chain())
            c.block(sx, hanger - 3, sz, Kit.lantern())
        }
        // Konsole mit Feuerschale an der Innenseite (zählt nicht zur Deckung, hängt an der Ader).
        val bx = cx + Math.round(ux / len * PILLAR_BOWL_OFFSET).toInt()
        val bz = cz + Math.round(uz / len * PILLAR_BOWL_OFFSET).toInt()
        c.put(bx, y + PILLAR_BOWL_Y - 1, bz, Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Kit.inward(bx - cx, bz - cz), top = true))
        c.put(bx, y + PILLAR_BOWL_Y, bz, s(Blocks.GILDED_BLACKSTONE))
        c.put(bx, y + PILLAR_BOWL_Y + 1, bz, Kit.FIRE)
        // Kapitell und Deckplatte (oben 5 × 5 bis +29).
        c.disc(cx, cz, 3.6) { x, z, d ->
            c.put(x, top - 1, z, if (d > 2.9) Kit.stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, Kit.inward(x - cx, z - cz), top = true) else s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT))
        }
        c.disc(cx, cz, 2.9) { x, z, _ -> c.put(x, top, z, s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT)) }
    }

    // --- Kronleuchter und Kuppel -----------------------------------------------------------------------------------

    /**
     * Kronleuchter auf +40: äußerer Ring r 11 wie bisher (Feuerschalen, Hängelaternen, vier Ketten zur Kuppel), neu
     * ein innerer Ring r 8 mit zwölf grünen Feuern, an sechs Ketten zur Kuppel und sechs Speichen am äußeren Ring.
     * Das Kuppelauge (r 6,5) bleibt frei.
     */
    private fun chandelier(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val ring = y + CHANDELIER_Y
        c.tube(o.x, o.z, ring, ring, CHANDELIER_OUTER, 1.6, pal.TRIM)
        c.tube(o.x, o.z, ring + 1, ring + 1, CHANDELIER_OUTER, 0.8) { _, _, _ -> s(Blocks.POLISHED_BLACKSTONE_SLAB) }
        for (i in 0 until 12) {
            val a = i * Math.PI / 6.0
            val fx = PalacePlan.polarX(o, a, CHANDELIER_OUTER - 0.7, 0.0)
            val fz = PalacePlan.polarZ(o, a, CHANDELIER_OUTER - 0.7, 0.0)
            if (i % 3 == 0) {
                for (h in ring + 1..domeCeiling(y, CHANDELIER_OUTER - 0.7)) c.block(fx, h, fz, Kit.chain())
            } else {
                c.block(fx, ring + 1, fz, s(Blocks.GILDED_BLACKSTONE))
                c.block(fx, ring + 2, fz, Kit.FIRE)
            }
            for (h in 1..3) c.block(fx, ring - h, fz, Kit.chain())
            c.block(fx, ring - 4, fz, Kit.lantern())
        }
        // Innerer Ring r 8.
        c.tube(o.x, o.z, ring, ring, CHANDELIER_INNER + 0.6, 1.2) { x, yy, z -> if (Kit.noise(x, yy, z) % 3 == 0) s(Blocks.POLISHED_BLACKSTONE) else s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT) }
        for (i in 0 until 12) {
            val deg = 15.0 + i * 30.0
            val p = ArenaGeo.polar(o, CHANDELIER_INNER, deg)
            c.block(p.x, ring, p.z, s(Blocks.GILDED_BLACKSTONE))
            c.block(p.x, ring + 1, p.z, Kit.FIRE)
            for (h in 1..2) c.block(p.x, ring - h, p.z, Kit.chain())
            c.block(p.x, ring - 3, p.z, Kit.lantern())
        }
        for (i in 0 until 6) {
            val deg = 60.0 * i
            // Speiche zwischen den Ringen.
            var r = CHANDELIER_INNER + 0.5
            while (r <= CHANDELIER_OUTER - 0.5) {
                val p = ArenaGeo.polar(o, r, deg)
                c.block(p.x, ring, p.z, s(Blocks.POLISHED_BLACKSTONE_BRICKS))
                r += 0.5
            }
            // Kette vom inneren Ring zur Kuppel.
            val q = ArenaGeo.polar(o, CHANDELIER_INNER, deg)
            for (h in ring + 1..domeCeiling(y, CHANDELIER_INNER)) c.block(q.x, h, q.z, Kit.chain())
        }
    }

    /**
     * Unter der Kuppelschale: zwölf Rippen aus Basaltsäule unter den Kupferrippen (0° + 30° · k) und acht Seelenadern
     * über den Säulenachsen (22,5° + 45° · k) hinauf zum Auge. Je Spalte hängt der Streifen direkt unter der Schale und
     * reicht bis zur Höhe der nächsten Spalte nach außen, damit die Linie durchgeht.
     */
    private fun domeLines(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        c.disc(o.x, o.z, HALL.toDouble()) { x, z, r ->
            if (r < EYE + 2.0) return@disc
            val dx = x - o.x
            val dz = z - o.z
            val deg = angle(dx, dz)
            val rib = offAxis(deg, r, RIB_EVERY) < RIB_HALF
            val vein = offAxis(deg, r, 45.0, 22.5) < RIB_HALF
            if (!rib && !vein) return@disc
            val top = domeCeiling(y, r)
            val low = min(top, domeCeiling(y, r + 1.0))
            for (h in low..top) {
                val state = if (vein) s(HhaDecoBlocks.SOUL_VEIN_BASALT) else {
                    val axis = if (top - low >= 1) Direction.Axis.Y else if (abs(dx) >= abs(dz)) Direction.Axis.X else Direction.Axis.Z
                    s(HhaDecoBlocks.TARTARUS_BASALT_PILLAR).with(PillarBlock.AXIS, axis)
                }
                c.put(x, h, z, state)
            }
        }
    }

    // --- Thron -----------------------------------------------------------------------------------------------------

    /** Podest, Thron mit zwei Kerberos-Köpfen, die leere Lehne Persephones und die Wand dahinter mit Froschlicht. */
    internal fun throne(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val tz = o.z + THRONE_Z
        for (step in 1..DAIS_HEIGHT) {
            val radius = 15.0 - (step - 1) * 2.4
            c.disc(o.x, tz, radius) { x, z, r ->
                if (hypot((x - o.x).toDouble(), (z - o.z).toDouble()) <= HALL) {
                    c.put(x, y + step, z, if (r > radius - 1.0) Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Kit.inward(x - o.x, z - tz)) else if (step == DAIS_HEIGHT && r < 2.0) s(Blocks.GILDED_BLACKSTONE) else s(Blocks.POLISHED_DEEPSLATE))
                }
            }
        }
        val top = y + DAIS_HEIGHT
        c.prism(o.x - 2, top + 1, tz - 2, o.x + 2, top + 1, tz) { _, _, _ -> s(Blocks.POLISHED_BLACKSTONE_BRICKS) }
        c.prism(o.x - 2, top + 1, tz, o.x + 2, top + 1, tz) { _, _, _ -> s(Blocks.GILDED_BLACKSTONE) }
        for (side in intArrayOf(-3, 3)) {
            c.prism(o.x + side, top + 1, tz - 2, o.x + side, top + 3, tz + 1, pal.TRIM)
            c.block(o.x + side, top + 4, tz + 1, skull(0, wither = true))
        }
        c.prism(o.x - 3, top + 1, tz - 3, o.x + 3, top + 15, tz - 3, pal.FACE)
        c.prism(o.x - 4, top + 1, tz - 4, o.x + 4, top + 15, tz - 4, pal.TRIM)
        for (h in 2..13) c.block(o.x, top + h, tz - 2, if (h % 2 == 0) s(Blocks.VERDANT_FROGLIGHT) else s(Blocks.GILDED_BLACKSTONE))
        for ((i, offset) in listOf(-4, -2, 0, 2, 4).withIndex()) {
            val tall = 7 - abs(i - 2) * 2
            c.limb(o.x + offset, top + 15, tz - 3, o.x + offset + offset / 2, top + 15 + tall + 3, tz - 4, 1.2, 0.5, pal.FACE)
            c.block(o.x + offset + offset / 2, top + 15 + tall + 4, tz - 4, if (offset == 0) pal.GOLD else Kit.FIRE)
        }
        for (side in intArrayOf(-1, 1)) cerberusHead(c, o.x + side * 6, top + KERBEROS_Y, tz - 3, side)
        val px = o.x + 8
        val pz = tz + 1
        c.prism(px - 1, top + 1, pz - 1, px + 1, top + 1, pz) { _, _, _ -> s(Blocks.POLISHED_DEEPSLATE) }
        c.prism(px - 1, top + 1, pz - 2, px + 1, top + 7, pz - 2, pal.FACE)
        c.block(px, top + 8, pz - 2, s(Blocks.GILDED_BLACKSTONE))
        c.block(px, top + 2, pz, s(Blocks.WITHER_ROSE))
        c.block(px - 1, top + 2, pz - 1, s(Blocks.LILY_OF_THE_VALLEY))
        for (side in intArrayOf(-1, 1)) {
            c.column(o.x + side * 13, top - 2, tz + 3, 12)
            c.brazier(o.x + side * 13, top + 12, tz + 3, 0)
        }
        // Die Wand hinter dem Thron mit Froschlicht, gerahmt von gemeißeltem Basalt.
        fun inside(dx: Int): Boolean = hypot(dx.toDouble(), (tz - 7 - (dx * dx / 40) - o.z).toDouble()) <= HALL
        for (dx in -16..16) {
            if (!inside(dx)) continue
            val z = tz - 7 - (abs(dx) * abs(dx) / 40)
            val x = o.x + dx
            val frame = !inside(dx + if (dx >= 0) 1 else -1)
            for (h in 1..THRONE_WALL_HEIGHT) {
                c.put(x, y + h, z, when {
                    frame || h == THRONE_WALL_HEIGHT -> s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT)
                    abs(dx) % 6 == 0 && h > 3 -> s(Blocks.VERDANT_FROGLIGHT)
                    h % 8 == 0 -> pal.TRIM(x, y + h, z)
                    else -> pal.FACE(x, y + h, z)
                })
                if (!frame && abs(dx) % 3 == 0 && h in 5..THRONE_WALL_HEIGHT - 1 && h % 6 == 5) c.block(x, y + h, z + 1, Blocks.END_ROD.defaultState.with(Properties.FACING, Direction.SOUTH))
            }
        }
    }

    private const val THRONE_WALL_HEIGHT = 24
    /** Unterkante der Kerberos-Köpfe über dem Podest (über der Lehne Persephones, die bis +8 reicht). */
    private const val KERBEROS_Y = 9

    /**
     * Ein Kerberos-Kopf an der Flanke der Thronlehne, Blick nach Süden: Schädel 3 × 3 × 2, Schnauze mit Ober- und
     * Unterkiefer, grüne Augen, zwei Ohren. [cx] ist die Mitte, [backZ] die Rückseite (an der Lehne), [side] die Seite.
     */
    private fun cerberusHead(c: BlockCanvas, cx: Int, y0: Int, backZ: Int, side: Int) {
        val skullBlock = s(Blocks.POLISHED_BLACKSTONE)
        c.prism(cx - 1, y0, backZ - 1, cx + 1, y0 + 2, backZ) { _, _, _ -> skullBlock }
        // Halsansatz an der Lehne (x ±4 bei backZ − 1) und Nacken.
        c.block(cx - side * 2, y0 + 1, backZ - 1, s(Blocks.POLISHED_BLACKSTONE_BRICKS))
        // Augen in der Stirn.
        c.block(cx - 1, y0 + 2, backZ + 1, s(Blocks.VERDANT_FROGLIGHT))
        c.block(cx + 1, y0 + 2, backZ + 1, s(Blocks.VERDANT_FROGLIGHT))
        c.block(cx, y0 + 2, backZ + 1, Kit.stair(Blocks.POLISHED_BLACKSTONE_STAIRS, Direction.NORTH, top = true))
        // Schnauze: Unterkiefer als Stufe, Oberkiefer als Treppe, Nase vorne.
        for (dx in -1..1) {
            c.block(cx + dx, y0, backZ + 1, s(Blocks.POLISHED_BLACKSTONE))
            c.block(cx + dx, y0 + 1, backZ + 1, Kit.stair(Blocks.POLISHED_BLACKSTONE_STAIRS, Direction.NORTH))
        }
        c.block(cx, y0, backZ + 2, Kit.slab(Blocks.POLISHED_BLACKSTONE_SLAB, top = true))
        c.block(cx, y0 + 1, backZ + 2, Kit.slab(Blocks.BLACKSTONE_SLAB))
        // Ohren.
        for (dx in intArrayOf(-1, 1)) c.block(cx + dx, y0 + 3, backZ - 1, Kit.stair(Blocks.POLISHED_BLACKSTONE_STAIRS, if (dx < 0) Direction.EAST else Direction.WEST))
    }

    // --- Leinwand mit Zylinder-Grenze ------------------------------------------------------------------------------

    /**
     * Lässt nur Blöcke im Saalzylinder durch: waagerecht r ≤ [HALL] um `o`, senkrecht von o.y − 2 bis unter die
     * Kuppelschale (im Auge bis unter [OCULUS_Y]). So kann der Bauplan `palace_hall` in alte Paläste gezeichnet werden,
     * ohne Trommel, Kuppel oder Hof anzufassen.
     */
    private class HallClip(private val c: BlockCanvas, private val o: BlockPos) : BlockCanvas() {
        override val minX = c.minX
        override val maxX = c.maxX
        override val minZ = c.minZ
        override val maxZ = c.maxZ

        override fun put(x: Int, y: Int, z: Int, state: BlockState) {
            val r = hypot((x - o.x).toDouble(), (z - o.z).toDouble())
            if (r > HALL || y < o.y - 2) return
            val ceiling = if (r < EYE) o.y + OCULUS_Y - 1 else domeCeiling(o.y, r)
            if (y > ceiling) return
            c.put(x, y, z, state)
        }

        override fun ground(x: Int, z: Int): Int = c.ground(x, z)
        override fun chest(x: Int, y: Int, z: Int) = Unit
        override fun spawner(x: Int, y: Int, z: Int, type: EntityType<*>) = Unit
        override fun surfaceState(x: Int, z: Int): BlockState? = c.surfaceState(x, z)
    }
}
