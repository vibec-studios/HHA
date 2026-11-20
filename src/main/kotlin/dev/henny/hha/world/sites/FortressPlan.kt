package dev.henny.hha.world.sites

import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.block.ArenaDeviceBlock
import dev.henny.hha.world.*
import dev.henny.hha.world.TartarusPlans.FORTRESS_COURT
import dev.henny.hha.world.TartarusPlans.stele
import dev.henny.hha.world.arena.ArenaSpec
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.HorizontalFacingBlock
import net.minecraft.block.enums.WallShape
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Bauplan der Bronzefeste (aus `TartarusPlans` herausgelöst in Überarbeitung 4, P00; umgebaut in P12).
 *
 * Gliederung (jede Funktion ein Bauteil): [floor] Boden, [outerWall] Vormauer mit Bastionen und Vortor, [mainWalls]
 * Hauptmauer mit Steinzinnen, [cornerTowers] begehbare Ecktürme, [gatehouse] Torhaus, [keep] Bergfried, [wallWalk]
 * Wehrgang mit Brüstung und Durchgang über das Torhaus, [arenaCore] Arena des Kerkermeisters, [cages] Käfige an der
 * Nordmauer, [approach] Zuweg mit Kettenbogen und Galgenpfählen.
 *
 * Zinnen-Hierarchie (Mangel „Zinnenteppich“): Bronze-Zinnen (`tartarus_bronze`) nur auf Bergfried, Ecktürmen und
 * Torhaus ([bronzeStyle]); Mauern, Vormauer und Bastionen tragen Steinzinnen aus Tartaros-Basaltziegeln über einem
 * durchlaufenden Bronzeband ([stoneStyle]). Kupferbirnen bleiben das Licht.
 */
internal object FortressPlan {

    /** Halbe Seitenlänge der Vormauer. */
    private const val OUTER = 40
    private const val OUTER_HEIGHT = 7
    private const val WALL_HEIGHT = 14
    private const val WALL_THICK = 5
    private const val TOWER_HALF = 6
    private const val TOWER_HEIGHT = 21
    private const val GATE_TOWER_X = 9
    private const val GATE_TOWER_HALF = 4
    private const val GATE_TOWER_HEIGHT = 19

    /** Wehrgang: Lauffläche der Hauptmauer (Füße) relativ zu o.y. */
    private const val WALK = WALL_HEIGHT + 1

    /** Bergfried: Mitte z relativ zu o. */
    private const val KEEP_Z = -8

    /** Ringband im Hofboden um den Standplatz (Blockring dieses Radius). */
    private const val SPOT_RING = 7.0

    /** Zuweg: Südrand des Kastens bis vor das Vortor. */
    private const val ROAD_FROM_Z = 46
    private const val ROAD_TO_Z = 44
    private const val ROAD_WIDTH = 5

    /** Kettenbogen vor dem Vortor: zwei Pylone 9 hoch beidseits der Straße. */
    private const val ARCH_X = 4
    private const val ARCH_Z = 45
    private const val ARCH_HEIGHT = 9

    /** Galgenpfähle und Feuerpfosten vor dem Vortor. */
    private const val GALLOWS_X = 10
    private const val FIRE_X = 7

    /** Käfige an der Nordmauer (Mitte relativ zu o), außerhalb des Arena-Kerns. */
    private val CAGES = listOf(-18 to -24, 18 to -24)

    private fun s(b: Block): BlockState = b.defaultState

    // ---------------------------------------------------------------------------------------------
    // Stile
    // ---------------------------------------------------------------------------------------------

    /** Steinzinnen: Basaltziegel mit Ziegelplatte, darunter das Bronzeband aus Tartaros-Bronze. */
    private fun stoneStyle(): WallStyle {
        val pal = Palettes.Fortress
        return WallStyle(
            face = pal.FACE, worn = pal.WORN, plinth = pal.PLINTH, pier = pal.PIER, band = DecoPalettes.DARK_BRONZE,
            plinthStair = Blocks.POLISHED_DEEPSLATE_STAIRS, cornice = Blocks.DEEPSLATE_BRICK_STAIRS,
            merlon = { _, _, _ -> s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS) }, merlonCap = HhaDecoBlocks.TARTARUS_BASALT_BRICK_SLAB,
            rubbleStair = Blocks.COBBLED_DEEPSLATE_STAIRS, rubbleSlab = Blocks.COBBLED_DEEPSLATE_SLAB, streak = pal.STREAK,
        )
    }

    /** Bronze-Zinnen für Bergfried, Ecktürme und Torhaus: Tartaros-Bronze mit Bronzeplatte. */
    private fun bronzeStyle(): WallStyle {
        val pal = Palettes.Fortress
        return WallStyle(
            face = pal.FACE, worn = pal.WORN, plinth = pal.PLINTH, pier = pal.PIER, band = DecoPalettes.DARK_BRONZE,
            plinthStair = Blocks.POLISHED_DEEPSLATE_STAIRS, cornice = Blocks.DEEPSLATE_BRICK_STAIRS,
            merlon = DecoPalettes.darkBronze(0.04), merlonCap = HhaDecoBlocks.TARTARUS_BRONZE_SLAB,
            rubbleStair = Blocks.COBBLED_DEEPSLATE_STAIRS, rubbleSlab = Blocks.COBBLED_DEEPSLATE_SLAB, streak = pal.STREAK,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Die Bronzefeste: „Eine eherne Mauer läuft ringsum“ (Hesiod). Niedrig, breit, kantig, dreifach gestaffelt.
    // ---------------------------------------------------------------------------------------------

    fun draw(c: BlockCanvas, o: BlockPos) {
        val pal = Palettes.Fortress
        c.pad(o.x, o.z, OUTER + 2.0, o.y + 1, 4, pal.RUBBLE, square = true, clear = 40)
        floor(c, o)
        outerWall(c, o)
        mainWalls(c, o)
        cornerTowers(c, o)
        gatehouse(c, o, o.z + FORTRESS_COURT)
        keep(c, o)
        wallWalk(c, o)
        arenaCore(c, o)
        cages(c, o)
        approach(c, o)
    }

    /**
     * Boden der Feste: im Hof ruhige Tiefenschiefer-Fliesen mit dem Prozessionsweg und dem Ringband (wie im Arena-Kern,
     * [courtFloor]), im Zwinger Fliesen, Kies und Schutt.
     */
    private fun floor(c: BlockCanvas, o: BlockPos) {
        val pal = Palettes.Fortress
        val y = o.y
        val r = OUTER + 2
        for (x in maxOf(o.x - r, c.minX)..minOf(o.x + r, c.maxX)) {
            for (z in maxOf(o.z - r, c.minZ)..minOf(o.z + r, c.maxZ)) {
                val ring = maxOf(abs(x - o.x), abs(z - o.z))
                val state = when {
                    ring < FORTRESS_COURT -> courtFloor(o, x, z)
                    Kit.noise(x, y, z) % 5 == 0 -> s(Blocks.GRAVEL)
                    Kit.noise(x, y, z) % 5 == 1 -> s(Blocks.COBBLED_DEEPSLATE)
                    else -> pal.FLOOR(x, y, z)
                }
                c.put(x, y, z, state)
            }
        }
    }

    /**
     * Hofboden (Füße auf o.y + 1): Tiefenschiefer-Fliesen; der Prozessionsweg vom Tor zum Bergfried (5 breit, Bronzekanten =
     * Weg); ein Ringband aus Tartaros-Basaltziegeln um den Standplatz des Kerkermeisters, in seiner Mitte ein Merkstein.
     */
    private fun courtFloor(o: BlockPos, x: Int, z: Int): BlockState {
        val pal = Palettes.Fortress
        val dx = x - o.x
        val dz = z - o.z
        val spot = TartarusArenas.FORTRESS_SPOT
        val r = hypot((dx - spot.x).toDouble(), (dz - spot.z).toDouble())
        return when {
            dx == spot.x && dz == spot.z -> s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT)
            abs(r - SPOT_RING) <= 0.5 -> s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS)
            abs(dx) <= 2 && dz > 4 -> if (abs(dx) == 2) pal.LANE(x, o.y, z) else s(Blocks.POLISHED_DEEPSLATE)
            else -> pal.FLOOR(x, o.y, z)
        }
    }

    /** Vormauer: niedrig, drei Blöcke dick, Steinzinnen über dem Bronzeband, an den Ecken Bastionen, im Süden das Vortor. */
    private fun outerWall(c: BlockCanvas, o: BlockPos) {
        val pal = Palettes.Fortress
        val stone = stoneStyle()
        val y = o.y
        val low = OUTER_HEIGHT
        c.wall(true, o.x - OUTER, o.x + OUTER, o.z - OUTER, y, low, 3, stone, pierEvery = 10, crestSides = intArrayOf(-1), bandAt = low)
        c.wall(true, o.x - OUTER, o.x + OUTER, o.z + OUTER, y, low, 3, stone, pierEvery = 10, crestSides = intArrayOf(1), bandAt = low)
        c.wall(false, o.z - OUTER, o.z + OUTER, o.x - OUTER, y, low, 3, stone, pierEvery = 10, crestSides = intArrayOf(-1), bandAt = low)
        c.wall(false, o.z - OUTER, o.z + OUTER, o.x + OUTER, y, low, 3, stone, pierEvery = 10, crestSides = intArrayOf(1), bandAt = low)
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            c.squareTower(o.x + sx * OUTER, o.z + sz * OUTER, y, 3, 10, stone, batter = 1, hollow = false, bandEvery = 8, slits = false)
        }
        for (sx in intArrayOf(-1, 1)) c.squareTower(o.x + sx * 6, o.z + OUTER, y, 2, 11, stone, batter = 1, hollow = false, bandEvery = 9, slits = false)
        // Durchgang des Vortors.
        for (dx in -3..3) {
            for (dz in -4..4) {
                val x = o.x + dx
                val z = o.z + OUTER + dz
                c.block(x, y, z, if (abs(dx) == 3) pal.LANE(x, y, z) else s(Blocks.POLISHED_DEEPSLATE))
                for (h in 1..6) c.block(x, y + h, z, Kit.AIR)
            }
        }
    }

    /** Hauptmauer: fünf Blöcke dick, Lisenen alle zehn Blöcke, Steinzinnen auf dem durchlaufenden Bronzeband. */
    private fun mainWalls(c: BlockCanvas, o: BlockPos) {
        val stone = stoneStyle()
        val y = o.y
        val half = FORTRESS_COURT
        c.wall(true, o.x - half, o.x + half, o.z - half, y, WALL_HEIGHT, WALL_THICK, stone, pierEvery = 10, crestSides = intArrayOf(-1), bandAt = WALL_HEIGHT)
        c.wall(true, o.x - half, o.x + half, o.z + half, y, WALL_HEIGHT, WALL_THICK, stone, pierEvery = 10, crestSides = intArrayOf(1), bandAt = WALL_HEIGHT)
        c.wall(false, o.z - half, o.z + half, o.x - half, y, WALL_HEIGHT, WALL_THICK, stone, pierEvery = 10, crestSides = intArrayOf(-1), bandAt = WALL_HEIGHT)
        c.wall(false, o.z - half, o.z + half, o.x + half, y, WALL_HEIGHT, WALL_THICK, stone, pierEvery = 10, crestSides = intArrayOf(1), bandAt = WALL_HEIGHT)
    }

    /** Ecktürme: eckig, geböschter Fuß, Bronze-Zinnen; innen eine Wendeltreppe mit Tür zum Hof und Ausstieg auf den Wehrgang. */
    private fun cornerTowers(c: BlockCanvas, o: BlockPos) {
        val pal = Palettes.Fortress
        val bronze = bronzeStyle()
        val y = o.y
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            val tx = o.x + sx * FORTRESS_COURT
            val tz = o.z + sz * FORTRESS_COURT
            c.squareTower(tx, tz, y, TOWER_HALF, TOWER_HEIGHT, bronze, batter = 2, bandEvery = 7)
            towerStair(c, tx, tz, sx, sz, y)
            for (cx in intArrayOf(-4, 4)) for (cz in intArrayOf(-4, 4)) c.block(tx + cx, y + TOWER_HEIGHT + 1, tz + cz, pal.LIGHT)
        }
    }

    /**
     * Wendeltreppe im Eckturm (Innenraum 9 × 9, Spindel 3 × 3, Lauf drei breit auf den Ringen 2 … 4). Örtliche Achsen:
     * u zeigt vom Turm zum Hof in x, v zum Hof in z. Winkel φ in (u, v): 0° = Ausstieg zur Mauer entlang x, 90° = zur
     * Mauer entlang z, 45° = Tür zum Hof. Lauf 1 steigt von der Tür (45°) einmal herum bis auf den Absatz +14 (0° … 90°,
     * Füße auf Wehrganghöhe), Lauf 2 von 90° weiter bis aufs Dach (+21, Ausstieg durch die Dachluke).
     */
    private fun towerStair(c: BlockCanvas, tx: Int, tz: Int, sx: Int, sz: Int, y: Int) {
        val pal = Palettes.Fortress
        val rate = (WALK - 1) / 315.0
        val landing = WALK - 1
        val roof = TOWER_HEIGHT
        val tread = HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS
        fun at(u: Int, v: Int) = BlockPos(tx - sx * u, 0, tz - sz * v)
        // Spindel mit Licht.
        for (u in -1..1) for (v in -1..1) {
            val p = at(u, v)
            for (h in 1 until roof) c.block(p.x, y + h, p.z, if (u == 0 && v == 0 && h % 6 == 3) pal.LIGHT else s(Blocks.POLISHED_DEEPSLATE))
        }
        for (u in -4..4) for (v in -4..4) {
            val ring = maxOf(abs(u), abs(v))
            if (ring < 2) continue
            val phi = (Math.toDegrees(atan2(v.toDouble(), u.toDouble())) + 360.0) % 360.0
            val p = at(u, v)
            // Laufrichtung (wachsendes φ) in Weltachsen: Treppen zeigen bergauf.
            val tu = -sin(Math.toRadians(phi))
            val tv = cos(Math.toRadians(phi))
            val wx = -sx * tu
            val wz = -sz * tv
            val facing = when {
                abs(abs(wx) - abs(wz)) < 0.3 -> null
                abs(wx) > abs(wz) -> if (wx > 0) Direction.EAST else Direction.WEST
                else -> if (wz > 0) Direction.SOUTH else Direction.NORTH
            }
            fun step(h: Int, flat: Boolean = false) {
                if (h < 1) return
                c.block(p.x, y + h, p.z, if (facing != null && !flat) Kit.stair(tread, facing) else s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
            }
            // Lauf 1: 45° … 360°, Höhe 0 … 13; danach der Absatz.
            if (phi >= 45.0) step(floor((phi - 45.0) * rate).toInt())
            if (phi <= 90.0) step(landing, flat = true)
            // Lauf 2: ab 90° vom Absatz bis zum Dach; über den obersten Stufen öffnet sich die Dachluke.
            val h2 = landing + floor((phi - 90.0) * rate).toInt()
            if (phi > 90.0 && h2 <= roof) {
                step(h2)
                if (h2 >= roof - 3 && h2 < roof && !(abs(u) == 4 && abs(v) == 4)) c.block(p.x, y + roof, p.z, Kit.AIR)
            }
        }
        // Tür zum Hof (u = 4, durch Böschung und Wand), drei hoch.
        for (v in 5..8) {
            val p = at(4, v)
            for (h in 1..3) c.block(p.x, y + h, p.z, Kit.AIR)
        }
        // Ausstiege auf den Wehrgang (Absatz +14): zur Mauer entlang x (v = 0) und entlang z (u = 0).
        for (d in 5..6) {
            val a = at(d, 0)
            val b = at(0, d)
            for (h in WALK..WALK + 2) {
                c.block(a.x, y + h, a.z, Kit.AIR)
                c.block(b.x, y + h, b.z, Kit.AIR)
            }
            // Die Schießscharte unter dem Ausstieg wieder schließen.
            for (h in WALK - 2 until WALK) {
                c.block(a.x, y + h, a.z, pal.FACE(a.x, y + h, a.z))
                c.block(b.x, y + h, b.z, pal.FACE(b.x, y + h, b.z))
            }
        }
    }

    /** Das Torhaus: zwei eckige Tortürme, ein Bogen, das Doppeltor mit offenen Bronzeflügeln; Bronze-Zinnen. */
    private fun gatehouse(c: BlockCanvas, o: BlockPos, gz: Int) {
        val pal = Palettes.Fortress
        val bronze = bronzeStyle()
        val y = o.y
        for (sx in intArrayOf(-1, 1)) c.squareTower(o.x + sx * GATE_TOWER_X, gz, y, GATE_TOWER_HALF, GATE_TOWER_HEIGHT, bronze, batter = 1, bandEvery = 6)
        c.prism(o.x - 5, y + 1, gz - 4, o.x + 5, y + 16, gz + 4, bronze.face)
        c.prism(o.x - 5, y + 1, gz + 5, o.x + 5, y + 2, gz + 5, bronze.plinth)
        c.prism(o.x - 5, y + 13, gz - 4, o.x + 5, y + 13, gz + 4, DecoPalettes.DARK_BRONZE)
        for (x in o.x - 6..o.x + 6) {
            c.block(x, y + 15, gz + 5, Kit.stair(bronze.cornice, Direction.NORTH, top = true))
            c.block(x, y + 15, gz - 5, Kit.stair(bronze.cornice, Direction.SOUTH, top = true))
            if ((x - o.x) % 2 == 0) {
                c.block(x, y + 17, gz + 4, bronze.merlon(x, y + 17, gz + 4))
                c.block(x, y + 18, gz + 4, Kit.slab(HhaDecoBlocks.TARTARUS_BRONZE_SLAB))
            }
        }
        // Durchgang mit Rundbogen.
        for (dx in -3..3) {
            for (h in 1..9) {
                if (h > 6 && hypot(dx.toDouble(), h - 6.0) > 3.5) continue
                for (dz in -6..6) c.block(o.x + dx, y + h, gz + dz, Kit.AIR)
            }
            for (dz in -6..6) c.block(o.x + dx, y, gz + dz, s(Blocks.POLISHED_DEEPSLATE))
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

    /** Der Bergfried als Stufenbau: drei Stockwerke, jedes mit eigener Bronze-Zinnenterrasse. */
    private fun keep(c: BlockCanvas, o: BlockPos) {
        val pal = Palettes.Fortress
        val style = bronzeStyle()
        val y = o.y
        val kz = o.z + KEEP_Z
        c.squareTower(o.x, kz, y, 12, 12, style, batter = 2, bandEvery = 10, slits = false)
        c.squareTower(o.x, kz, y + 12, 9, 10, style, batter = 0, bandEvery = 8, slits = false, grounded = false)
        c.squareTower(o.x, kz, y + 22, 5, 9, style, batter = 0, hollow = false, bandEvery = 7, slits = false, grounded = false)
        // Oben: Bronzesäule mit Laterne, an den Ecken Kupferlampen.
        c.block(o.x, y + 32, kz, s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT))
        c.block(o.x, y + 33, kz, s(HhaDecoBlocks.TARTARUS_BRONZE))
        c.block(o.x, y + 34, kz, Kit.lantern(hanging = false, soul = false))
        for (cx in intArrayOf(-3, 3)) for (cz in intArrayOf(-3, 3)) c.block(o.x + cx, y + 32, kz + cz, pal.LIGHT)
        // Erdgeschoss: Tor mit Bronzeflügeln, innen Fliesen, vier Pfeiler, Beute und die Tafel.
        for (dx in -2..2) {
            for (h in 1..6) {
                if (h > 4 && hypot(dx.toDouble(), h - 4.0) > 2.5) continue
                for (dz in 10..15) c.block(o.x + dx, y + h, kz + dz, Kit.AIR)
            }
            for (dz in 10..15) c.block(o.x + dx, y, kz + dz, s(Blocks.POLISHED_DEEPSLATE))
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
        for (dx in -2..2) c.block(o.x + dx, y + 1, kz - 10, s(Blocks.WAXED_COPPER_BLOCK))
        // Treppe an der Ostwand hinauf auf die erste Terrasse.
        for (step in 0 until 11) {
            c.block(o.x + 10, y + 1 + step, kz + 9 - step, Kit.stair(Blocks.DEEPSLATE_BRICK_STAIRS, Direction.NORTH))
            for (up in 1..3) c.block(o.x + 10, y + 1 + step + up, kz + 9 - step, Kit.AIR)
        }
        for (dz in -2..-1) c.block(o.x + 10, y + 12, kz + dz, Kit.AIR)
        // Zweites Geschoss: Tür zur Terrasse, drinnen die zweite Truhe und eine Wache; die Lampe steht auf dem Boden.
        for (dx in -1..1) for (h in 1..3) for (dz in 7..9) c.block(o.x + dx, y + 12 + h, kz + dz, Kit.AIR)
        c.chest(o.x, y + 13, kz - 5)
        c.spawner(o.x + 4, y + 13, kz - 5, net.minecraft.entity.EntityType.WITHER_SKELETON)
        c.block(o.x - 4, y + 13, kz - 5, pal.LIGHT)
    }

    /**
     * Wehrgang der Hauptmauer: auf der Mauerkrone (Füße +15) mit Brüstung zur Hofseite und Laternen an den Lisenen; im
     * Süden führt er durch die Tortürme (Absatz +14, zwei Stufen) über das Dach des Torhauses. Liegt über der Arena-Höhe
     * und ist kein Kampfplatz.
     */
    private fun wallWalk(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val half = FORTRESS_COURT
        val inner = half - WALL_THICK / 2
        val span = half - TOWER_HALF - 1
        val gateFree = GATE_TOWER_X + GATE_TOWER_HALF + 1
        // Brüstung zur Hofseite: Nord, Ost, West ganz; Süd bis an die Tortürme.
        for (a in -span..span) {
            val south = abs(a) >= gateFree
            parapet(c, o.x + a, y + WALK, o.z - inner, Direction.Axis.X, a == -span, a == span)
            if (south) parapet(c, o.x + a, y + WALK, o.z + inner, Direction.Axis.X, a == -span || a == gateFree, a == span || a == -gateFree)
            parapet(c, o.x - inner, y + WALK, o.z + a, Direction.Axis.Z, a == -span, a == span)
            parapet(c, o.x + inner, y + WALK, o.z + a, Direction.Axis.Z, a == -span, a == span)
            if (a % 10 == 0) {
                c.block(o.x + a, y + WALK + 1, o.z - inner, Kit.lantern(hanging = false, soul = false))
                if (south) c.block(o.x + a, y + WALK + 1, o.z + inner, Kit.lantern(hanging = false, soul = false))
                c.block(o.x - inner, y + WALK + 1, o.z + a, Kit.lantern(hanging = false, soul = false))
                c.block(o.x + inner, y + WALK + 1, o.z + a, Kit.lantern(hanging = false, soul = false))
            }
        }
        // Durchgang durch die Tortürme: Tür vom Wehrgang, Absatz, zwei Stufen, Tür aufs Torhausdach.
        val gz = o.z + half
        for (sx in intArrayOf(-1, 1)) {
            val gx = o.x + sx * GATE_TOWER_X
            val inward = if (sx > 0) Direction.WEST else Direction.EAST
            for (x in gx - 2..gx + 2) for (z in gz - 2..gz + 2) c.block(x, y + WALK - 1, z, s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
            for (d in 3..4) for (h in WALK..WALK + 2) c.block(gx + sx * d, y + h, gz, Kit.AIR)
            c.block(gx - sx * 1, y + WALK, gz, Kit.stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, inward))
            c.block(gx - sx * 2, y + WALK, gz, s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
            c.block(gx - sx * 2, y + WALK + 1, gz, Kit.stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, inward))
            for (d in 3..4) for (h in WALK + 2..WALK + 3) c.block(gx - sx * d, y + h, gz, Kit.AIR)
            c.block(gx + sx, y + WALK, gz - 2, Kit.lantern(hanging = false, soul = false))
        }
    }

    /** Ein Stück Brüstung (Mauer), verbunden entlang [axis]; an den Enden ein Pfosten. */
    private fun parapet(c: BlockCanvas, x: Int, y: Int, z: Int, axis: Direction.Axis, startEnd: Boolean, stopEnd: Boolean) {
        var state = s(HhaDecoBlocks.TARTARUS_BASALT_BRICK_WALL).with(Properties.UP, startEnd || stopEnd)
        if (axis == Direction.Axis.X) {
            if (!startEnd) state = state.with(Properties.WEST_WALL_SHAPE, WallShape.LOW)
            if (!stopEnd) state = state.with(Properties.EAST_WALL_SHAPE, WallShape.LOW)
        } else {
            if (!startEnd) state = state.with(Properties.NORTH_WALL_SHAPE, WallShape.LOW)
            if (!stopEnd) state = state.with(Properties.SOUTH_WALL_SHAPE, WallShape.LOW)
        }
        c.block(x, y, z, state)
    }

    /** Zwei leere Käfige an der Nordmauer (Hier hielten sie, was sie gefangen hatten. Übrig sind Knochen.) */
    private fun cages(c: BlockCanvas, o: BlockPos) {
        val pal = Palettes.Fortress
        val style = stoneStyle()
        val y = o.y
        for ((dx, dz) in CAGES) {
            val cx = o.x + dx
            val cz = o.z + dz
            c.prism(cx - 2, y + 1, cz - 2, cx + 2, y + 1, cz + 2, style.plinth)
            for (d in -2..2) {
                for (h in 2..4) {
                    c.block(cx + d, y + h, cz - 2, Kit.bars(true))
                    if (Kit.noise(cx + d, y + h, cz) % 6 != 0) c.block(cx + d, y + h, cz + 2, Kit.bars(true))
                    c.block(cx - 2, y + h, cz + d, Kit.bars(false))
                    c.block(cx + 2, y + h, cz + d, Kit.bars(false))
                }
            }
            c.prism(cx - 2, y + 5, cz - 2, cx + 2, y + 5, cz + 2, DecoPalettes.DARK_BRONZE)
            for (h in 2..4) c.block(cx, y + h, cz, Kit.AIR)
            c.block(cx, y + 2, cz, s(Blocks.BONE_BLOCK).with(Properties.AXIS, Direction.Axis.X))
            c.block(cx + 1, y + 2, cz + 1, skull(Kit.noise(cx, 0, cz) % 16))
            c.block(cx, y + 4, cz, Kit.chain())
            c.block(cx, y + 3, cz, pal.LANTERN)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Zuweg (PLAN 5.3): Straße vom Kastenrand zum Vortor, Kettenbogen und Galgenpfähle als Wegzeichen
    // ---------------------------------------------------------------------------------------------

    /**
     * Zuweg vor dem Vortor. Der Kasten der Feste endet 6 Blöcke vor der Vormauer, darum ist die Straße kurz: Pflaster vom
     * Kastenrand bis ans Vortor, beidseits ein Feuerpfosten, darüber der Kettenbogen (zwei Pylone 9 hoch, schwere Kette mit
     * Durchhang), außen die zwei Galgenpfähle mit hängender Laterne.
     */
    private fun approach(c: BlockCanvas, o: BlockPos) {
        val road: Pal = { x, y, z ->
            when (Kit.noise(x, y, z) % 7) {
                0 -> s(Blocks.COBBLED_DEEPSLATE)
                1 -> s(HhaDecoBlocks.TARTARUS_BASALT)
                else -> s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS)
            }
        }
        SiteKit.road(c, BlockPos(o.x, o.y, o.z + ROAD_FROM_Z), BlockPos(o.x, o.y, o.z + ROAD_TO_Z), ROAD_WIDTH, road)
        for (sx in intArrayOf(-1, 1)) SiteKit.firePost(c, o.x + sx * FIRE_X, o.z + ROAD_FROM_Z)
        chainArch(c, o)
        for (sx in intArrayOf(-1, 1)) gallows(c, o.x + sx * GALLOWS_X, o.z + ARCH_Z, if (sx > 0) Direction.WEST else Direction.EAST)
    }

    /** Kettenbogen: zwei Pylone 9 hoch (Sockel 3 × 3, Schaft mit Bronzebändern, Kettenjoch), dazwischen die schwere Kette. */
    private fun chainArch(c: BlockCanvas, o: BlockPos) {
        val z = o.z + ARCH_Z
        // Beide Joche auf gleicher Höhe: Der Schaft auf tieferem Grund wird länger.
        val top = intArrayOf(-1, 1).maxOf { c.ground(o.x + it * ARCH_X, z) } + ARCH_HEIGHT
        for (sx in intArrayOf(-1, 1)) {
            val x = o.x + sx * ARCH_X
            val base = c.ground(x, z)
            for (px in x - 1..x + 1) for (pz in z - 1..z + 1) {
                c.footing(px, pz, base - 1, Palettes.Fortress.PLINTH)
                c.block(px, base, pz, s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
            }
            for (px in x - 1..x + 1) for (pz in z - 1..z + 1) {
                if (px != x && pz != z) continue
                c.block(px, base + 1, pz, if (px == x && pz == z) s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS) else Kit.stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, Kit.inward(px - x, pz - z)))
            }
            for (h in base + 1 until top) {
                c.block(x, h, z, if ((h - base) % 3 == 0) s(HhaDecoBlocks.TARTARUS_BRONZE) else s(HhaDecoBlocks.TARTARUS_BASALT_PILLAR))
            }
            c.block(x, top, z, s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT))
            c.block(x, top + 1, z, Kit.lantern(hanging = false, soul = false))
        }
        // Schwere Kette quer über die Straße mit Durchhang 2: Glied an Glied (flächig verbunden), an beiden Schäften fest.
        val x0 = o.x - ARCH_X + 1
        val x1 = o.x + ARCH_X - 1
        var last = top - 1
        for (x in x0..x1) {
            val t = (x - x0 + 1).toDouble() / (x1 - x0 + 2)
            val ys = top - 1 - Math.round(4.0 * t * (1.0 - t) * 2.0).toInt()
            for (h in minOf(ys, last)..maxOf(ys, last)) c.block(x, h, z, if ((x + h) % 2 == 0) Palettes.Prison.LINK_A else s(HhaDecoBlocks.TARTARUS_BRONZE))
            last = ys
        }
        c.block(x1, top - 1, z, Palettes.Prison.LINK_A)
    }

    /** Galgenpfahl: Säule 6 hoch, Arm zur Straße, daran Kette und Laterne. [toward] zeigt zur Straße. */
    private fun gallows(c: BlockCanvas, x: Int, z: Int, toward: Direction) {
        val base = c.ground(x, z)
        c.footing(x, z, base - 1, Palettes.Fortress.PLINTH)
        c.block(x, base, z, s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
        for (h in 1..5) c.block(x, base + h, z, s(HhaDecoBlocks.TARTARUS_BASALT_PILLAR))
        c.block(x, base + 6, z, s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT))
        for (d in 1..2) c.block(x + toward.offsetX * d, base + 6, z + toward.offsetZ * d, Kit.slab(HhaDecoBlocks.TARTARUS_BRONZE_SLAB, top = true))
        val hx = x + toward.offsetX * 2
        val hz = z + toward.offsetZ * 2
        c.block(hx, base + 5, hz, Kit.chain())
        c.block(hx, base + 4, hz, Kit.lantern(hanging = true, soul = true))
        c.block(x - toward.offsetX, base + 1, z - toward.offsetZ, skull(Kit.noise(x, 1, z) % 16))
    }

    // ---------------------------------------------------------------------------------------------
    // Arena-Kern des Kerkermeisters (PLAN 3.7): ohne Geländebezug, relativ zu [o]; Maße aus `TartarusArenas.fortress`
    // ---------------------------------------------------------------------------------------------

    /**
     * Arena-Kern: Hofboden (ruhige Fläche mit Ringband und Weg), darüber 4 Blöcke frei; die vier Hofpfeiler (Deckung gegen
     * den Kettenwurf); die Hofterrasse (+3) mit Treppen an beiden Enden; die vier Kettenpfähle auf ihren Sockeln.
     */
    fun arenaCore(c: BlockCanvas, o: BlockPos) {
        val spec = TartarusArenas.fortress(o)
        courtCore(c, o, spec)
        terrace(c, o, spec)
        for (cover in spec.cover) pillar(c, cover.box)
        for (device in spec.devices) {
            postSocket(c, device.pos)
            c.block(
                device.pos.x, device.pos.y, device.pos.z,
                device.block().defaultState.with(HorizontalFacingBlock.FACING, device.facing).with(ArenaDeviceBlock.MODE, ArenaDeviceBlock.IDLE),
            )
        }
    }

    /** Kernfläche: Boden auf o.y, darüber [TartarusArenas]-Kern 4 Blöcke frei (räumt Schutt und Spielerblöcke). */
    private fun courtCore(c: BlockCanvas, o: BlockPos, spec: ArenaSpec) {
        for ((x, z) in spec.core.cells()) {
            c.block(x, o.y, z, courtFloor(o, x, z))
            for (h in 1..4) c.block(x, o.y + h, z, Kit.AIR)
        }
    }

    /**
     * Hofterrasse (+3) vor dem Bergfried, Zone aus dem Vertrag: zwei Flügel, Stützmauer aus Basaltziegeln mit Lisenen,
     * Bronzekante (Ebenenkante), Brüstung als Pfostenreihe mit Laternen (die Terrasse ist nur zwei tief), Treppen an beiden
     * Enden auf den Vertragspunkten. Vor dem Bergfriedtor (|x| ≤ 2) bleibt der Durchgang frei.
     */
    private fun terrace(c: BlockCanvas, o: BlockPos, spec: ArenaSpec) {
        val level = spec.level("terrace")
        val top = level.floorY - 1
        val cells = level.zone.cells().filter { (x, z) -> !(abs(x - o.x) <= 2 && z - o.z == TartarusArenas.FORTRESS_TERRACE_Z0) }.toSet()
        for ((x, z) in cells) {
            val front = (x to z + 1) !in cells
            for (h in o.y + 1 until top) {
                c.block(x, h, z, if (front && Math.floorMod(x - o.x, 3) == 0) s(HhaDecoBlocks.TARTARUS_BASALT_PILLAR) else DecoPalettes.BASALT_WALL(x, h, z))
            }
            c.block(x, top, z, if (front) s(HhaDecoBlocks.TARTARUS_BRONZE) else s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
            for (h in 1..3) c.block(x, top + h, z, Kit.AIR)
        }
        // Brüstung: Pfosten an der Vorderkante, oben Laternen.
        for (sx in intArrayOf(-1, 1)) {
            for (ax in intArrayOf(5, 8, 12)) {
                val x = o.x + sx * ax
                val z = o.z + TartarusArenas.FORTRESS_TERRACE_Z1
                c.block(x, top + 1, z, s(HhaDecoBlocks.TARTARUS_BASALT_BRICK_WALL))
                c.block(x, top + 2, z, Kit.lantern(hanging = false, soul = false))
            }
        }
        // Treppen an beiden Enden (Vertragspunkte); darunter voll.
        for (p in level.access) {
            val side = if (p.x > o.x) 1 else -1
            for (h in o.y + 1 until p.y) c.block(p.x, h, p.z, DecoPalettes.BASALT_WALL(p.x, h, p.z))
            c.block(p.x, p.y, p.z, Kit.stair(HhaDecoBlocks.TARTARUS_BASALT_BRICK_STAIRS, if (side > 0) Direction.WEST else Direction.EAST))
            for (h in 1..3) c.block(p.x, p.y + h, p.z, Kit.AIR)
        }
    }

    /**
     * Hofpfeiler (Deckung, 3 × 3 × 7, voller Körper): Basaltsockel, Schaft aus Basaltziegeln mit Bronzeband, gemeißelter
     * Deckstein; außen ein Treppenkranz am Fuß und auf jeder Seite ein Kettenhaken; obendrauf die Kupferlampe.
     */
    private fun pillar(c: BlockCanvas, box: BlockBox) {
        val pal = Palettes.Fortress
        val cx = (box.minX + box.maxX) / 2
        val cz = (box.minZ + box.maxZ) / 2
        val y0 = box.minY
        val y1 = box.maxY
        for (x in box.minX..box.maxX) for (z in box.minZ..box.maxZ) {
            c.block(x, y0, z, s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
            for (h in y0 + 1 until y1) c.block(x, h, z, if (h == y0 + 3) DecoPalettes.DARK_BRONZE(x, h, z) else DecoPalettes.BASALT_WALL(x, h, z))
            c.block(x, y1, z, s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT))
        }
        // Treppenkranz am Fuß (Rand 1, gehört zur Deckung).
        for (x in box.minX - 1..box.maxX + 1) for (z in box.minZ - 1..box.maxZ + 1) {
            if (box.contains(x, y0, z)) continue
            if (x != cx && z != cz) continue
            c.block(x, y0, z, Kit.stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, Kit.inward(x - cx, z - cz)))
        }
        // Kettenhaken: auf jeder Seite ein Kettenstück, das aus dem Schaft ragt.
        for (d in Direction.Type.HORIZONTAL) {
            c.block(cx + d.offsetX * 2, y0 + 4, cz + d.offsetZ * 2, Kit.chain(d.axis))
        }
        // Krone: Kupferlampe in der Mitte, an den Seiten Bronzeplatten.
        c.block(cx, y1 + 1, cz, pal.LIGHT)
        for (d in Direction.Type.HORIZONTAL) c.block(cx + d.offsetX, y1 + 1, cz + d.offsetZ, Kit.slab(HhaDecoBlocks.TARTARUS_BRONZE_SLAB))
    }

    /** Sockel eines Kettenpfahls im Boden: gemeißelter Stein unter dem Pfahl, ringsum ein Bronzering (3 × 3). */
    private fun postSocket(c: BlockCanvas, post: BlockPos) {
        val y = post.y - 1
        for (dx in -1..1) for (dz in -1..1) {
            c.block(post.x + dx, y, post.z + dz, if (dx == 0 && dz == 0) s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT) else s(HhaDecoBlocks.TARTARUS_BRONZE))
        }
    }
}
