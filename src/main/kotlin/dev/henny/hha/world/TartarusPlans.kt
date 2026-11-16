package dev.henny.hha.world

import dev.henny.hha.HhaBlocks
import dev.henny.hha.block.SteleBlock
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

    private const val HILL_R = 27.0
    private const val TRACK_FROM = 25.0
    private const val TRACK_TO = 10.0
    private const val TRACK_TURNS = 1.25

    /** Rundbogen des Tors: [dy] zählt vom Hallenboden aufwärts. */
    fun inSealOpening(dx: Int, dy: Int): Boolean = abs(dx) <= SEAL_HALF_WIDTH && dy >= 1 && (dy <= 10 || hypot(dx.toDouble(), dy - 10.0) <= SEAL_HALF_WIDTH + 0.4)

    /** Versatz der Deckungssäulen zur Hallenmitte. */
    fun coverPillars(): List<Pair<Int, Int>> = List(8) { i ->
        val a = i * Math.PI / 4.0 + Math.PI / 8.0
        Pair(Math.round(cos(a) * PILLAR_RING).toInt(), Math.round(sin(a) * PILLAR_RING).toInt())
    }

    /** Welche Strafstätte an dieser Stelle steht: 0 Sisyphos, 1 Ixion, 2 Tantalos. */
    fun siteVariant(o: BlockPos): Int = Math.floorMod(o.x / 16 * 31 + o.z / 16 * 17, 3)

    // --- Sisyphos: Werte für den Wächterkampf ------------------------------------------------------------

    /** Höhe des Hügels über dem Fuß bei Abstand [r] zur Mitte (auf ebenem Grund). */
    fun sisyphusHill(r: Double): Double = when {
        r <= SISYPHUS_SUMMIT -> SISYPHUS_HEIGHT.toDouble()
        r >= HILL_R -> 0.0
        else -> SISYPHUS_HEIGHT * (0.5 + 0.5 * cos(PI * (r - SISYPHUS_SUMMIT) / (HILL_R - SISYPHUS_SUMMIT)))
    }

    private fun trackStart(o: BlockPos): Double = (Kit.noise(o.x shr 4, 5, o.z shr 4) % 360) * PI / 180.0

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

    private fun stele(story: Int): BlockState = HhaBlocks.STELE.defaultState.with(SteleBlock.STORY, story)

    fun draw(c: BlockCanvas, kind: TartarusKind, center: BlockPos) {
        when (kind) {
            TartarusKind.PALACE -> PalacePlan.draw(c, center)
            TartarusKind.FORTRESS -> fortress(c, center)
            TartarusKind.PRISON -> prison(c, center)
            TartarusKind.PUNISHMENT -> when (siteVariant(center)) {
                0 -> sisyphus(c, center)
                1 -> ixion(c, center)
                else -> tantalus(c, center)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Die Bronzefeste: „Eine eherne Mauer läuft ringsum“ (Hesiod). Niedrig, breit, kantig, dreifach gestaffelt.
    // ---------------------------------------------------------------------------------------------

    private fun fortress(c: BlockCanvas, o: BlockPos) {
        val pal = Palettes.Fortress
        val style = pal.STYLE
        val y = o.y
        val half = FORTRESS_COURT
        val outer = 40
        c.pad(o.x, o.z, outer + 2.0, y + 1, 4, pal.RUBBLE, square = true, clear = 40)
        // Boden: im Hof Tiefschieferfliesen mit Bronzebahnen, im Zwinger Fliesen, Kies und Schutt.
        for (x in maxOf(o.x - outer - 2, c.minX)..minOf(o.x + outer + 2, c.maxX)) {
            for (z in maxOf(o.z - outer - 2, c.minZ)..minOf(o.z + outer + 2, c.maxZ)) {
                val dx = x - o.x
                val dz = z - o.z
                val ring = maxOf(abs(dx), abs(dz))
                val state = when {
                    ring < half && abs(dx) <= 2 && dz > 4 -> if (abs(dx) == 2) pal.LANE(x, y, z) else Blocks.POLISHED_DEEPSLATE.defaultState
                    ring < half && (dx % 8 == 0 || dz % 8 == 0) -> pal.LANE(x, y, z)
                    ring < half -> pal.FLOOR(x, y, z)
                    Kit.noise(x, y, z) % 5 == 0 -> Blocks.GRAVEL.defaultState
                    Kit.noise(x, y, z) % 5 == 1 -> Blocks.COBBLED_DEEPSLATE.defaultState
                    else -> pal.FLOOR(x, y, z)
                }
                c.put(x, y, z, state)
            }
        }
        // Vormauer: niedrig, drei Blöcke dick, an den Ecken kleine Bastionen, im Süden ein Vortor.
        val low = 7
        c.wall(true, o.x - outer, o.x + outer, o.z - outer, y, low, 3, style, pierEvery = 10, crestSides = intArrayOf(-1), bandAt = low - 2)
        c.wall(true, o.x - outer, o.x + outer, o.z + outer, y, low, 3, style, pierEvery = 10, crestSides = intArrayOf(1), bandAt = low - 2)
        c.wall(false, o.z - outer, o.z + outer, o.x - outer, y, low, 3, style, pierEvery = 10, crestSides = intArrayOf(-1), bandAt = low - 2)
        c.wall(false, o.z - outer, o.z + outer, o.x + outer, y, low, 3, style, pierEvery = 10, crestSides = intArrayOf(1), bandAt = low - 2)
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            c.squareTower(o.x + sx * outer, o.z + sz * outer, y, 3, 10, style, batter = 1, hollow = false, bandEvery = 8, slits = false)
        }
        for (sx in intArrayOf(-1, 1)) c.squareTower(o.x + sx * 6, o.z + outer, y, 2, 11, style, batter = 1, hollow = false, bandEvery = 9, slits = false)
        for (dx in -3..3) {
            for (dz in -4..4) {
                val x = o.x + dx
                val z = o.z + outer + dz
                c.block(x, y, z, if (abs(dx) == 3) pal.LANE(x, y, z) else Blocks.POLISHED_DEEPSLATE.defaultState)
                for (h in 1..6) c.block(x, y + h, z, Kit.AIR)
            }
        }
        // Hauptmauer: fünf Blöcke dick, Bronzeband unter dem Gesims, Lisenen alle zehn Blöcke.
        val wallH = 14
        c.wall(true, o.x - half, o.x + half, o.z - half, y, wallH, 5, style, pierEvery = 10, crestSides = intArrayOf(-1), bandAt = wallH - 3)
        c.wall(true, o.x - half, o.x + half, o.z + half, y, wallH, 5, style, pierEvery = 10, crestSides = intArrayOf(1), bandAt = wallH - 3)
        c.wall(false, o.z - half, o.z + half, o.x - half, y, wallH, 5, style, pierEvery = 10, crestSides = intArrayOf(-1), bandAt = wallH - 3)
        c.wall(false, o.z - half, o.z + half, o.x + half, y, wallH, 5, style, pierEvery = 10, crestSides = intArrayOf(1), bandAt = wallH - 3)
        // Ecktürme: eckig, geböschter Fuß, flache Zinnenkrone, Bronzebänder.
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            val tx = o.x + sx * half
            val tz = o.z + sz * half
            c.squareTower(tx, tz, y, 6, 21, style, batter = 2, bandEvery = 7)
            for (cx in intArrayOf(-4, 4)) for (cz in intArrayOf(-4, 4)) c.block(tx + cx, y + 22, tz + cz, pal.LIGHT)
        }
        gatehouse(c, o, o.z + half, style)
        keep(c, o, style)
        // Pfeiler im Hof: Deckung, und für die Kette des Kerkermeisters etwas zum Festhaken.
        for ((dx, dz) in listOf(-13 to 17, 13 to 17, -21 to 3, 21 to 3)) {
            val px = o.x + dx
            val pz = o.z + dz
            c.prism(px - 1, y + 1, pz - 1, px + 1, y + 1, pz + 1, style.plinth)
            c.prism(px - 1, y + 2, pz - 1, px + 1, y + 6, pz + 1, style.face)
            c.prism(px - 1, y + 4, pz - 1, px + 1, y + 4, pz + 1, pal.BRONZE)
            c.prism(px - 1, y + 7, pz - 1, px + 1, y + 7, pz + 1) { _, _, _ -> Kit.slab(Blocks.WAXED_CUT_COPPER_SLAB) }
            c.block(px, y + 7, pz, pal.LIGHT)
        }
        // Zwei leere Käfige: Hier hielten sie, was sie gefangen hatten. Übrig sind Knochen.
        for (sx in intArrayOf(-1, 1)) {
            val cx = o.x + sx * 21
            val cz = o.z + 20
            c.prism(cx - 2, y + 1, cz - 2, cx + 2, y + 1, cz + 2, style.plinth)
            for (d in -2..2) {
                for (h in 2..4) {
                    if (Kit.noise(cx + d, y + h, cz) % 6 != 0) c.block(cx + d, y + h, cz - 2, Kit.bars(true))
                    c.block(cx + d, y + h, cz + 2, Kit.bars(true))
                    c.block(cx - 2, y + h, cz + d, Kit.bars(false))
                    c.block(cx + 2, y + h, cz + d, Kit.bars(false))
                }
            }
            c.prism(cx - 2, y + 5, cz - 2, cx + 2, y + 5, cz + 2, pal.BRONZE)
            c.block(cx, y + 2, cz, Blocks.BONE_BLOCK.defaultState.with(Properties.AXIS, Direction.Axis.X))
            c.block(cx + 1, y + 2, cz - 1, skull(Kit.noise(cx, 0, cz) % 16))
            c.block(cx, y + 4, cz, Kit.chain())
        }
    }

    /** Das Torhaus: zwei eckige Tortürme, ein Bogen, das Doppeltor mit offenen Bronzeflügeln. */
    private fun gatehouse(c: BlockCanvas, o: BlockPos, gz: Int, style: WallStyle) {
        val pal = Palettes.Fortress
        val y = o.y
        for (sx in intArrayOf(-1, 1)) c.squareTower(o.x + sx * 9, gz, y, 4, 19, style, batter = 1, bandEvery = 6)
        c.prism(o.x - 5, y + 1, gz - 4, o.x + 5, y + 16, gz + 4, style.face)
        c.prism(o.x - 5, y + 1, gz + 5, o.x + 5, y + 2, gz + 5, style.plinth)
        c.prism(o.x - 5, y + 13, gz - 4, o.x + 5, y + 13, gz + 4, pal.BRONZE)
        for (x in o.x - 6..o.x + 6) {
            c.block(x, y + 15, gz + 5, Kit.stair(style.cornice, Direction.NORTH, top = true))
            c.block(x, y + 15, gz - 5, Kit.stair(style.cornice, Direction.SOUTH, top = true))
            if ((x - o.x) % 2 == 0) {
                c.block(x, y + 17, gz + 4, style.merlon(x, y + 17, gz + 4))
                c.block(x, y + 18, gz + 4, Kit.slab(Blocks.WAXED_CUT_COPPER_SLAB))
            }
        }
        // Durchgang mit Rundbogen.
        for (dx in -3..3) {
            for (h in 1..9) {
                if (h > 6 && hypot(dx.toDouble(), h - 6.0) > 3.5) continue
                for (dz in -6..6) c.block(o.x + dx, y + h, gz + dz, Kit.AIR)
            }
            for (dz in -6..6) c.block(o.x + dx, y, gz + dz, Blocks.POLISHED_DEEPSLATE.defaultState)
        }
        // Bogenrahmen aus gemeißelter Bronze.
        for (dx in -4..4) {
            for (h in 1..10) {
                val inside = h <= 6 && abs(dx) <= 3 || hypot(dx.toDouble(), h - 6.0) <= 3.5
                val rim = h <= 6 && abs(dx) == 4 || !inside && hypot(dx.toDouble(), h - 6.0) <= 4.6 && h > 6
                if (rim) {
                    c.block(o.x + dx, y + h, gz + 4, pal.DOOR(o.x + dx, y + h, gz + 4))
                    c.block(o.x + dx, y + h, gz - 4, pal.DOOR(o.x + dx, y + h, gz - 4))
                }
            }
        }
        // Das Doppeltor steht offen: die Flügel nach innen an die Laibung geklappt.
        for (sx in intArrayOf(-1, 1)) {
            for (dz in -3..-1) for (h in 1..7) c.block(o.x + sx * 3, y + h, gz + dz, pal.DOOR(o.x, y + h, gz + dz))
        }
        for (dz in intArrayOf(-2, 2)) {
            c.block(o.x, y + 9, gz + dz, Kit.chain())
            c.block(o.x, y + 8, gz + dz, pal.LANTERN)
        }
    }

    /** Der Bergfried als Stufenbau: drei Stockwerke, jedes mit eigener Zinnenterrasse. */
    private fun keep(c: BlockCanvas, o: BlockPos, style: WallStyle) {
        val pal = Palettes.Fortress
        val y = o.y
        val kz = o.z - 8
        c.squareTower(o.x, kz, y, 12, 12, style, batter = 2, bandEvery = 10, slits = false)
        c.squareTower(o.x, kz, y + 12, 9, 10, style, batter = 0, bandEvery = 8, slits = false, grounded = false)
        c.squareTower(o.x, kz, y + 22, 5, 9, style, batter = 0, hollow = false, bandEvery = 7, slits = false, grounded = false)
        // Oben: Bronzesäule mit Laterne, an den Ecken Kupferlampen.
        c.block(o.x, y + 32, kz, Blocks.WAXED_CHISELED_COPPER.defaultState)
        c.block(o.x, y + 33, kz, Blocks.WAXED_COPPER_BLOCK.defaultState)
        c.block(o.x, y + 34, kz, Kit.lantern(hanging = false, soul = false))
        for (cx in intArrayOf(-3, 3)) for (cz in intArrayOf(-3, 3)) c.block(o.x + cx, y + 32, kz + cz, pal.LIGHT)
        // Erdgeschoss: Tor mit Bronzeflügeln, innen Fliesen, vier Pfeiler, Beute und die Tafel.
        for (dx in -2..2) {
            for (h in 1..6) {
                if (h > 4 && hypot(dx.toDouble(), h - 4.0) > 2.5) continue
                for (dz in 10..15) c.block(o.x + dx, y + h, kz + dz, Kit.AIR)
            }
            for (dz in 10..15) c.block(o.x + dx, y, kz + dz, Blocks.POLISHED_DEEPSLATE.defaultState)
        }
        for (sx in intArrayOf(-1, 1)) for (h in 1..5) for (dx in 3..4) c.block(o.x + sx * dx, y + h, kz + 10, pal.DOOR(o.x, y + h, kz))
        c.disc(o.x, kz, 10.5) { x, z, r -> c.put(x, y, z, if (r in 6.0..7.0) pal.LANE(x, y, z) else pal.FLOOR(x, y, z)) }
        for ((dx, dz) in listOf(-5 to -5, 5 to -5, -5 to 5, 5 to 5)) {
            c.prism(o.x + dx, y + 1, kz + dz, o.x + dx + 1, y + 11, kz + dz + 1, style.pier)
            c.block(o.x + dx, y + 6, kz + dz + 2, pal.LIGHT)
        }
        c.chest(o.x - 3, y + 1, kz - 9)
        c.chest(o.x + 3, y + 1, kz - 9)
        c.block(o.x, y + 1, kz - 9, stele(0))
        for (dx in -2..2) c.block(o.x + dx, y + 1, kz - 10, Blocks.WAXED_COPPER_BLOCK.defaultState)
        // Treppe an der Ostwand hinauf auf die erste Terrasse.
        for (step in 0 until 11) {
            c.block(o.x + 10, y + 1 + step, kz + 9 - step, Kit.stair(Blocks.DEEPSLATE_BRICK_STAIRS, Direction.NORTH))
            for (up in 1..3) c.block(o.x + 10, y + 1 + step + up, kz + 9 - step, Kit.AIR)
        }
        for (dz in -2..-1) c.block(o.x + 10, y + 12, kz + dz, Kit.AIR)
        // Zweites Geschoss: Tür zur Terrasse, drinnen die zweite Truhe und eine Wache.
        for (dx in -1..1) for (h in 1..3) for (dz in 7..9) c.block(o.x + dx, y + 12 + h, kz + dz, Kit.AIR)
        c.chest(o.x, y + 13, kz - 5)
        c.spawner(o.x + 4, y + 13, kz - 5, net.minecraft.entity.EntityType.WITHER_SKELETON)
        c.block(o.x - 4, y + 14, kz - 5, pal.LIGHT)
    }

    // ---------------------------------------------------------------------------------------------
    // Der Titanenkerker: Hier wachen die Hundertarmigen über die gestürzten Titanen
    // ---------------------------------------------------------------------------------------------

    private fun prison(c: BlockCanvas, o: BlockPos) {
        val pal = Palettes.Prison
        val y = o.y
        val pit = PRISON_PIT_RADIUS
        val bottom = y - PRISON_PIT_DEPTH
        c.pad(o.x, o.z, 30.5, y + 1, 5, pal.RUBBLE, square = false, clear = 26)
        // Grube und niedriger Rand.
        c.disc(o.x, o.z, 30.5) { x, z, r ->
            if (r <= pit) {
                for (h in bottom + 1..y + 26) c.put(x, h, z, Kit.AIR)
                c.put(x, bottom, z, pal.PIT(x, bottom, z))
                c.put(x, bottom - 1, z, pal.FACE(x, bottom - 1, z))
                return@disc
            }
            c.footing(x, z, y - 1, pal.FACE)
            for (h in bottom..y - 1) {
                val state = when {
                    r > pit + 1.5 -> pal.FACE(x, h, z)
                    (y - h) % 8 == 0 -> pal.BAND(x, h, z)
                    Kit.wear(x, h, z, (h - bottom).toDouble() / PRISON_PIT_DEPTH, 1.0) -> pal.WORN(x, h, z)
                    else -> pal.FACE(x, h, z)
                }
                c.put(x, h, z, state)
            }
            c.put(x, y, z, when {
                r > 29.4 -> Kit.stair(Blocks.DEEPSLATE_TILE_STAIRS, Kit.inward(x - o.x, z - o.z))
                r in 26.6..27.5 -> pal.BAND(x, y, z)
                else -> pal.FLOOR(x, y, z)
            })
            if (r < pit + 1.2 && Kit.noise(x, 2, z) % 3 != 0) c.put(x, y + 1, z, Blocks.DEEPSLATE_BRICK_WALL.defaultState)
        }
        // Wendelrampe an der Grubenwand: Eine Umdrehung sinkt sie um zwölf Blöcke.
        c.disc(o.x, o.z, pit.toDouble()) { x, z, r ->
            if (r >= pit - 3.6) {
                val a = (atan2((z - o.z).toDouble(), (x - o.x).toDouble()) + Math.PI) / (Math.PI * 2.0)
                var h = y - (a * 12.0).toInt()
                var turn = 0
                while (h > bottom) {
                    c.put(x, h, z, if ((x + z + turn) and 7 == 0) Blocks.TUFF_BRICKS.defaultState else pal.FLOOR(x, h, z))
                    if (r < pit - 2.8 && (x + z) and 1 == 0) c.put(x, h + 1, z, Blocks.DEEPSLATE_BRICK_WALL.defaultState)
                    h -= 12
                    turn++
                }
            }
        }
        // Zwei Reihen Zellen in der Grubenwand: Bogen, Eisengitter (manche ausgebrochen), drinnen Knochen.
        for (tier in 0..1) {
            val floor = bottom + 1 + tier * 11
            for (i in 0 until 10) {
                val a = i * Math.PI / 5.0 + tier * Math.PI / 10.0 + 0.13
                val cx = o.x + (cos(a) * (pit + 3.5)).toInt()
                val cz = o.z + (sin(a) * (pit + 3.5)).toInt()
                c.prism(cx - 2, floor, cz - 2, cx + 2, floor + 4, cz + 2) { _, _, _ -> Kit.AIR }
                c.prism(cx - 2, floor - 1, cz - 2, cx + 2, floor - 1, cz + 2, pal.FLOOR)
                val fx = o.x + (cos(a) * pit).toInt()
                val fz = o.z + (sin(a) * pit).toInt()
                val alongX = abs(sin(a)) > abs(cos(a))
                val broken = (i + tier) % 4 == 1
                for (w in -2..2) {
                    for (h in 0..4) {
                        val bx = if (alongX) fx + w else fx
                        val bz = if (alongX) fz else fz + w
                        val gap = w == 0 && h <= 1 || broken && w in -1..0 && h <= 3
                        c.block(bx, floor + h, bz, if (gap) Kit.AIR else if (h == 4 || abs(w) == 2) pal.BAND(bx, floor + h, bz) else Kit.bars(alongX))
                    }
                }
                c.block(cx, floor, cz, if (i % 3 == 0) Blocks.BONE_BLOCK.defaultState else Blocks.SOUL_SOIL.defaultState)
                c.block(cx + 1, floor, cz, skull(i * 3))
                c.block(cx - 1, floor + 4, cz + 1, Kit.chain())
                c.block(cx - 1, floor + 3, cz + 1, Kit.lantern())
                if ((i + tier * 3) % 10 == 2) c.chest(cx + 1, floor, cz + 1)
            }
        }
        // Glutnester am Grund: wenige, klein, gebündelt.
        for (i in 0 until 6) {
            val a = i * 1.07 + 0.4
            val r = 13.0 + (i % 3) * 3.0
            val nx = o.x + (cos(a) * r).toInt()
            val nz = o.z + (sin(a) * r).toInt()
            for ((ox, oz) in listOf(0 to 0, 1 to 0, 0 to 1)) c.block(nx + ox, bottom, nz + oz, pal.GLOW)
        }
        val ty = bottom + 1
        titan(c, o.x, ty, o.z)
        c.block(o.x, y + 1, o.z + pit + 3, stele(1))
        // Vier Kettenpylonen am Rand, nach innen geneigt; von ihnen laufen die schweren Ketten zu den Armen des Titanen.
        for (i in 0 until 4) {
            val a = Math.PI / 4.0 + i * Math.PI / 2.0
            val bx = o.x + Math.round(cos(a) * 28.0).toInt()
            val bz = o.z + Math.round(sin(a) * 28.0).toInt()
            val tx = o.x + Math.round(cos(a) * 24.0).toInt()
            val tz = o.z + Math.round(sin(a) * 24.0).toInt()
            c.taper(bx, bz, y + 1, y + 4, 4.2, 3.0, pal.PLINTH)
            val pylon: Pal = { x, yy, z -> if ((yy - y) % 4 == 0) pal.BAND(x, yy, z) else if (Kit.wear(x, yy, z, (yy - y) / 14.0, 1.0)) pal.WORN(x, yy, z) else pal.FACE(x, yy, z) }
            c.limb(bx, y + 1, bz, tx, y + 12, tz, 2.9, 1.9, pylon)
            // Der Kopf: ein Kettenjoch aus Tuff mit Eisenringen, darauf eine Spitze.
            c.prism(tx - 2, y + 12, tz - 2, tx + 2, y + 13, tz + 2) { x, _, z -> if (abs(x - tx) == 2 && abs(z - tz) == 2) Kit.AIR else pal.LINK_A }
            c.prism(tx - 1, y + 14, tz - 1, tx + 1, y + 14, tz + 1) { _, _, _ -> Blocks.TUFF_BRICKS.defaultState }
            c.block(tx, y + 15, tz, Blocks.CHISELED_TUFF_BRICKS.defaultState)
            for (d in Direction.Type.HORIZONTAL) c.block(tx + d.offsetX * 3, y + 12, tz + d.offsetZ * 3, Kit.bars(d.axis == Direction.Axis.Z))
            val ix = o.x + Math.round(cos(a) * 21.0).toInt()
            val iz = o.z + Math.round(sin(a) * 21.0).toInt()
            for (h in 10..11) c.block(ix, y + h, iz, Kit.chain())
            c.block(ix, y + 9, iz, Kit.lantern())
            // Die schwere Kette zum nächsten Handgelenk des Titanen.
            val side = if (cos(a) > 0) 1 else -1
            c.heavyChain(tx, y + 11, tz, o.x + side * 20, ty + 23, o.z - 3, 2.0, pal.LINK_A, pal.LINK_B, thick = true)
        }
    }

    /** Der Titan: ein kniender Riese. Runde Körper statt Quader, die Rippen offen, die Brust glimmt. */
    private fun titan(c: BlockCanvas, x: Int, y: Int, z: Int) {
        val pal = Palettes.Prison
        val skin = pal.SKIN
        // Knie, Oberschenkel und Hüfte.
        c.limb(x - 7, y + 2, z - 3, x - 6, y + 5, z + 5, 3.4, 3.0, skin)
        c.limb(x + 7, y + 2, z - 3, x + 6, y + 5, z + 5, 3.4, 3.0, skin)
        c.limb(x - 6, y + 5, z + 5, x - 3, y + 8, z + 1, 3.2, 3.4, skin)
        c.limb(x + 6, y + 5, z + 5, x + 3, y + 8, z + 1, 3.2, 3.4, skin)
        c.blob(x, y + 9, z + 1, 6.0, 3.6, 4.6, skin)
        // Rumpf, nach vorn gebeugt.
        c.blob(x, y + 14, z, 6.6, 5.8, 4.8, skin)
        c.blob(x, y + 20, z - 1, 9.0, 5.2, 5.4, skin)
        // Offene Brust: Glut zwischen den Rippen.
        c.blob(x, y + 18, z - 5, 3.2, 3.2, 1.5) { _, _, _ -> pal.GLOW }
        for (i in -1..1) {
            for (dx in -5..5) {
                if (abs(dx) > 1) c.block(x + dx, y + 18 + i * 3, z - 6, Blocks.BONE_BLOCK.defaultState.with(Properties.AXIS, Direction.Axis.X))
            }
        }
        // Kopf hängt auf die Brust; die Augen glühen noch.
        c.blob(x, y + 24, z - 4, 3.8, 4.4, 3.8, skin)
        c.block(x - 2, y + 24, z - 8, pal.GLOW)
        c.block(x + 2, y + 24, z - 8, pal.GLOW)
        c.block(x, y + 21, z - 7, Blocks.BONE_BLOCK.defaultState)
        // Schultern und nach außen gerissene Arme mit schweren Handschellen.
        for (side in intArrayOf(-1, 1)) {
            c.blob(x + side * 9, y + 21, z - 1, 3.8, 3.4, 3.8, skin)
            c.limb(x + side * 9, y + 21, z - 1, x + side * 17, y + 19, z - 2, 2.6, 2.2, skin)
            c.limb(x + side * 17, y + 19, z - 2, x + side * 20, y + 23, z - 3, 2.2, 1.8, skin)
            c.cyl(x + side * 19, z - 3, y + 21, y + 22, 2.4) { _, _, _ -> pal.LINK_A }
            c.heavyChain(x + side * 20, y + 24, z - 3, x + side * 26, y + 29, z - 3, 1.0, pal.LINK_A, pal.LINK_B)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Strafstätten
    // ---------------------------------------------------------------------------------------------

    /** Sisyphos: ein flacher Hügel mit Spiralbahn; der Fels liegt mal hier, mal dort auf dem Weg nach oben. */
    private fun sisyphus(c: BlockCanvas, o: BlockPos) {
        val pal = Palettes.Sisyphus
        val y = o.y
        val start = trackStart(o)
        val half = SISYPHUS_TRACK_WIDTH / 2.0
        val slides = List(5) { start + 1.1 + it * 1.27 }
        c.disc(o.x, o.z, HILL_R + 3.0) { x, z, r ->
            val dx = (x - o.x).toDouble()
            val dz = (z - o.z).toDouble()
            val a = atan2(dz, dx)
            var trackR = -1.0
            if (r > TRACK_TO - half - 0.5) {
                val frac = (((a - start) / (2.0 * PI)) % 1.0 + 1.0) % 1.0
                for (k in 0..1) {
                    val t = (frac + k) / TRACK_TURNS
                    if (t > 1.0) continue
                    val rc = TRACK_FROM + (TRACK_TO - TRACK_FROM) * t
                    if (abs(r - rc) <= half) trackR = rc
                }
            }
            val natural = c.ground(x, z) - 1
            val top: Int
            val surface: Pal
            if (trackR > 0) {
                top = y + sisyphusHill(trackR).roundToInt()
                surface = if (abs(r - trackR) < 1.3) pal.RUT else pal.TRACK
            } else {
                val shape = y + sisyphusHill(r)
                val s = ((r - 22.0) / (HILL_R + 3.0 - 22.0)).coerceIn(0.0, 1.0)
                top = (shape + (natural - shape) * s * s * (3 - 2 * s)).roundToInt()
                val slide = r in 11.0..26.0 && slides.any { abs(Math.IEEEremainder(a - it, 2.0 * PI)) * r < 1.4 + (r - 11.0) * 0.06 }
                surface = when {
                    r <= SISYPHUS_SUMMIT - 1.0 -> pal.SUMMIT
                    r <= SISYPHUS_SUMMIT + 0.5 -> pal.HILL
                    slide -> pal.SLIDE
                    else -> pal.HILL
                }
            }
            c.footing(x, z, top, pal.HILL)
            c.put(x, top, z, surface(x, top, z))
            for (h in 1..18) c.put(x, top + h, z, Kit.AIR)
            if (trackR > 0 && r - trackR > 1.9 && Kit.noise(x, 1, z) % 3 == 0) c.put(x, top + 1, z, pal.CURB)
        }
        // Der Fels: facettierte Kugel, gerissen, in den Rissen glüht es.
        val b = sisyphusBoulder(o)
        val reach = SISYPHUS_BOULDER + 1
        for (dx in -reach..reach) for (dy in -reach..reach) for (dz in -reach..reach) {
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 > 4.4 * 4.4 || abs(dx) + abs(dy) + abs(dz) > 6 || maxOf(abs(dx), abs(dy), abs(dz)) > SISYPHUS_BOULDER) continue
            val crack = d2 > 9 && ((dx - dz + dy) == 0 || (dx + 2 * dz - dy) == 1)
            val x = b.x + dx
            val yy = b.y + dy
            val z = b.z + dz
            c.block(x, yy, z, if (crack) pal.EMBER else pal.BOULDER(x, yy, z))
        }
        // Am Fuß die Versuche von gestern: kleinere Brocken, halb im Geröll.
        for (i in 0 until 3) {
            val a = start - 0.9 - i * 0.7
            val px = o.x + (cos(a) * 28.0).toInt()
            val pz = o.z + (sin(a) * 28.0).toInt()
            if (c.inBox(px, pz)) c.blob(px, c.ground(px, pz), pz, 2.0, 1.6, 2.2, pal.BOULDER)
        }
        val foot = sisyphusTrack(o, 0.0)
        val sx = o.x + ((foot.x - o.x - 0.5) * 1.14).roundToInt()
        val sz = o.z + ((foot.z - o.z - 0.5) * 1.14).roundToInt()
        if (c.inBox(sx, sz)) c.put(sx, c.ground(sx, sz), sz, stele(2))
    }

    /** Ixion: ewig auf ein feuriges Rad gebunden. Rot und Schwarz, die Nabe glüht. */
    private fun ixion(c: BlockCanvas, o: BlockPos) {
        val pal = Palettes.Ixion
        val y = o.y
        val hub = y + 18
        val wheel = 15.0
        c.pad(o.x, o.z, IXION_PLATFORM + 0.5, y + 1, 4, pal.FLOOR, square = false, clear = 34)
        // Runde Plattform: Schwarzstein, ein Basaltring, außen eine Stufe aus Ziegeln.
        c.disc(o.x, o.z, IXION_PLATFORM.toDouble()) { x, z, r ->
            c.footing(x, z, y - 1, pal.FACE)
            c.put(x, y, z, when {
                r > 17.6 -> Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Kit.inward(x - o.x, z - o.z))
                r > 16.2 -> pal.RING(x, y, z)
                r > 15.4 -> pal.TRIM(x, y, z)
                else -> pal.FLOOR(x, y, z)
            })
        }
        // Unter der Nabe eine flache Glutmulde.
        c.disc(o.x, o.z, 3.6) { x, z, r -> c.put(x, y, z, if (r > 2.6) pal.TRIM(x, y, z) else Kit.AIR); if (r <= 2.6) c.put(x, y - 1, z, Blocks.MAGMA_BLOCK.defaultState) }
        // Zwei Stützen tragen die Achse, schräge Streben halten sie.
        for (side in intArrayOf(-1, 1)) {
            val pz = o.z + side * 6
            c.taper(o.x, pz, y + 1, hub + 1, 3.2, 2.0) { x, yy, z -> if ((yy - y) % 6 == 0) pal.TRIM(x, yy, z) else pal.FACE(x, yy, z) }
            c.limb(o.x - 9, y + 1, pz, o.x - 1, hub - 3, pz, 1.1, 0.8, pal.TRIM)
            c.limb(o.x + 9, y + 1, pz, o.x + 1, hub - 3, pz, 1.1, 0.8, pal.TRIM)
            c.prism(o.x - 1, hub - 1, pz - 1, o.x + 1, hub + 1, pz + 1, pal.TRIM)
        }
        c.prism(o.x, hub, o.z - 6, o.x, hub, o.z + 6, pal.TRIM)
        // Das Rad steht in der x-y-Ebene: Reifen, sechs Speichen (zwei fehlen, eine ist verbogen), Nabe aus Glut.
        val missing = setOf(2, 5)
        for (dx in -17..17) {
            for (dy in -17..17) {
                val r = hypot(dx.toDouble(), dy.toDouble())
                val a = atan2(dy.toDouble(), dx.toDouble())
                var spoke = false
                for (i in 0 until 8) {
                    if (i in missing) continue
                    var sa = i * PI / 4.0
                    if (i == 7) sa += (r / wheel) * 0.38
                    val off = Math.IEEEremainder(a - sa, 2.0 * PI)
                    if (abs(off) < PI / 2 && abs(off) * r < 0.95 && r > 2.4 && r < wheel - 1.6) spoke = true
                }
                val rim = r in wheel - 2.2..wheel && !(a in -2.42..-2.18)
                val ring = r in 5.6..6.9
                val hubPart = r < 2.7
                val x = o.x + dx
                val yy = hub + dy
                for (dz in -1..1) {
                    val z = o.z + dz
                    when {
                        hubPart -> c.block(x, yy, z, pal.HUB(x, yy, z))
                        rim -> c.block(x, yy, z, pal.RIM(x, yy, z))
                        ring && dz == 0 -> c.block(x, yy, z, pal.RIM(x, yy, z))
                        spoke && (dz == 0 || r < 5.0) -> c.block(x, yy, z, pal.SPOKE(x, yy, z))
                    }
                }
                if (hubPart) {
                    c.block(x, yy, o.z - 2, pal.TRIM(x, yy, o.z - 2))
                    c.block(x, yy, o.z + 2, pal.TRIM(x, yy, o.z + 2))
                }
            }
        }
        // Sparsame Flammen am Reifen.
        for (i in 0 until 6) {
            val a = i * PI / 3.0 + 0.3
            c.block(o.x + Math.round(cos(a) * (wheel - 0.4)).toInt(), hub + Math.round(sin(a) * (wheel - 0.4)).toInt(), o.z, pal.FLAME)
        }
        // Der Verurteilte, vor dem Rad, mit ausgestreckten Armen und Beinen; Ketten binden ihn an die Speichen.
        val body = Blocks.BLACKSTONE.defaultState
        val fz = o.z - 2
        for (h in -4..5) c.block(o.x, hub + h, fz, body)
        for (a in -4..4) c.block(o.x + a, hub + 3, fz, body)
        c.block(o.x - 2, hub - 3, fz, body)
        c.block(o.x + 2, hub - 3, fz, body)
        c.block(o.x - 3, hub - 4, fz, body)
        c.block(o.x + 3, hub - 4, fz, body)
        c.block(o.x, hub + 6, fz, Blocks.POLISHED_BASALT.defaultState)
        c.block(o.x, hub + 6, fz - 1, Blocks.MAGMA_BLOCK.defaultState)
        for (sx in intArrayOf(-1, 1)) {
            for (a in 5..8) c.block(o.x + sx * a, hub + 3, fz, Kit.chain(Direction.Axis.X))
            for (h in 1..3) c.block(o.x + sx * 3, hub - 4 - h, fz, Kit.chain())
        }
        c.block(o.x, y + 1, o.z + 16, stele(3))
    }

    /** Tantalos: das Wasser weicht zurück, die Früchte hängen knapp außer Reichweite, über ihm schwebt ein Fels an Ketten. */
    private fun tantalus(c: BlockCanvas, o: BlockPos) {
        val pal = Palettes.Tantalus
        val y = o.y
        c.pad(o.x, o.z, 21.5, y + 1, 4, pal.STONE, square = false, clear = 30)
        c.disc(o.x, o.z, 21.0) { x, z, r ->
            c.footing(x, z, y - 1, pal.FACE)
            when {
                r > 19.2 -> c.put(x, y, z, Kit.stair(Blocks.STONE_BRICK_STAIRS, Kit.inward(x - o.x, z - o.z)))
                r > 16.5 -> {
                    c.put(x, y, z, pal.FACE(x, y, z))
                    if (Kit.noise(x, 5, z) % 6 == 0) c.put(x, y + 1, z, Blocks.MOSS_CARPET.defaultState)
                }
                r > 15.8 -> {
                    c.put(x, y, z, pal.EDGE(x, y, z))
                    if (Kit.noise(x, 4, z) % 3 != 0) c.put(x, y + 1, z, Kit.slab(Blocks.DARK_PRISMARINE_SLAB))
                }
                r > 13.0 -> {
                    // Der oberste Absatz ist längst trocken.
                    c.put(x, y - 1, z, pal.SHELF(x, y - 1, z))
                    c.put(x, y, z, Kit.AIR)
                }
                r > 10.0 -> {
                    // Hier stand das Wasser gestern noch.
                    c.put(x, y - 2, z, pal.BED(x, y - 2, z))
                    c.put(x, y - 1, z, Kit.AIR)
                    c.put(x, y, z, Kit.AIR)
                }
                else -> {
                    c.put(x, y - 4, z, pal.BED(x, y - 4, z))
                    c.put(x, y - 3, z, Blocks.WATER.defaultState)
                    c.put(x, y - 2, z, Blocks.WATER.defaultState)
                    c.put(x, y - 1, z, Kit.AIR)
                    c.put(x, y, z, Kit.AIR)
                }
            }
        }
        // Der Obstbaum am Rand neigt sich über das Becken.
        val tx = o.x - 17
        val tz = o.z - 4
        val crowns = listOf(Triple(tx + 12, y + 15, tz + 3), Triple(tx + 8, y + 16, tz - 4), Triple(tx + 6, y + 17, tz + 8), Triple(tx + 17, y + 14, tz + 4), Triple(tx + 3, y + 14, tz + 1))
        for ((cx, cy, cz) in crowns) c.blob(cx, cy, cz, 3.2, 2.2, 3.0, pal.LEAVES)
        c.limb(tx, y, tz, tx + 3, y + 8, tz + 1, 1.4, 1.0, pal.WOOD)
        val branches = listOf(
            listOf(tx + 3, y + 8, tz + 1, tx + 12, y + 13, tz + 3),
            listOf(tx + 3, y + 8, tz + 1, tx + 8, y + 14, tz - 4),
            listOf(tx + 3, y + 9, tz + 1, tx + 6, y + 15, tz + 8),
            listOf(tx + 12, y + 13, tz + 3, tx + 18, y + 12, tz + 4),
        )
        for ((x1, y1, z1, x2, y2, z2) in branches.map { Branch(it) }) c.limb(x1, y1, z1, x2, y2, z2, 0.8, 0.5, pal.WOOD)
        // Die Früchte: Leuchtbeeren an Ranken, die vom Ast über dem Wasser hängen, knapp über seinem Kopf.
        val last = Branch(branches[0])
        val over = Branch(branches[3])
        for ((b, t, length) in listOf(Triple(last, 0.55, 5), Triple(last, 0.8, 6), Triple(over, 0.3, 6), Triple(over, 0.6, 7), Triple(over, 0.95, 5))) {
            val vx = Math.round(b.x1 + (b.x2 - b.x1) * t).toInt()
            val vy = Math.round(b.y1 + (b.y2 - b.y1) * t).toInt()
            val vz = Math.round(b.z1 + (b.z2 - b.z1) * t).toInt()
            for (h in 1 until length) c.block(vx, vy - h, vz, Blocks.CAVE_VINES_PLANT.defaultState.with(Properties.BERRIES, true))
            c.block(vx, vy - length, vz, Blocks.CAVE_VINES.defaultState.with(Properties.BERRIES, true).with(Properties.AGE_25, 25))
        }
        // Der Fels hängt an zwei Ketten zwischen zwei Säulen über dem Becken.
        val rx = o.x + 4
        val ry = y + 12
        val rz = o.z + 1
        c.blob(rx, ry, rz, 2.8, 2.4, 2.6, pal.STONE)
        for (side in intArrayOf(-1, 1)) {
            val px = o.x + 12
            val pz = o.z + side * 12
            c.taper(px, pz, y + 1, y + 3, 2.6, 2.0, pal.FACE)
            c.taper(px, pz, y + 4, y + 17, 1.6, 0.9, pal.FACE)
            c.block(px, y + 18, pz, Blocks.CHISELED_STONE_BRICKS.defaultState)
            val chain = Kit.chain(if (abs(pz - rz) >= abs(px - rx)) Direction.Axis.Z else Direction.Axis.X)
            c.heavyChain(px, y + 17, pz, rx, ry + 2, rz + side * 2, 2.0, chain, chain)
        }
        c.block(o.x, y + 1, o.z + 18, stele(4))
    }

    /** Ein Ast als zwei Punkte; nur für die Ranken des Tantalos-Baums. */
    private class Branch(v: List<Int>) {
        val x1 = v[0]
        val y1 = v[1]
        val z1 = v[2]
        val x2 = v[3]
        val y2 = v[4]
        val z2 = v[5]
        operator fun component1() = x1
        operator fun component2() = y1
        operator fun component3() = z1
        operator fun component4() = x2
        operator fun component5() = y2
        operator fun component6() = z2
    }
}
