package dev.henny.hha.world

import dev.henny.hha.HhaBlocks
import dev.henny.hha.block.SteleBlock
import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.world.palace.PalaceApproach
import dev.henny.hha.world.palace.PalaceCourt
import dev.henny.hha.world.palace.PalaceHall
import dev.henny.hha.world.TartarusPlans.CHANDELIER_Y
import dev.henny.hha.world.TartarusPlans.COURT
import dev.henny.hha.world.TartarusPlans.DAIS_HEIGHT
import dev.henny.hha.world.TartarusPlans.DOME_RISE
import dev.henny.hha.world.TartarusPlans.DRUM
import dev.henny.hha.world.TartarusPlans.DRUM_TOP
import dev.henny.hha.world.TartarusPlans.EYE
import dev.henny.hha.world.TartarusPlans.HALL
import dev.henny.hha.world.TartarusPlans.PILLAR_HEIGHT
import dev.henny.hha.world.TartarusPlans.PILLAR_RADIUS
import dev.henny.hha.world.TartarusPlans.SEAL_HALF_WIDTH
import dev.henny.hha.world.TartarusPlans.SEAL_HEIGHT
import dev.henny.hha.world.TartarusPlans.SEAL_Z
import dev.henny.hha.world.TartarusPlans.THRONE_Z
import dev.henny.hha.world.TartarusPlans.coverPillars
import dev.henny.hha.world.TartarusPlans.inSealOpening
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Der Palast des Hades: Schwarz, Gold und smaragdgrünes Feuer. Ringmauer mit Strebepfeilern und Statuennischen, Ecktürme
 * mit offenem Säulenkranz und Feuerschale, vor dem Tor ein Propylon mit Giebel und zwei Obelisken, in der Mitte die
 * Rippenkuppel über dem Thronsaal. Die Maße des Saals (für den Kampf) stehen in [TartarusPlans].
 */
internal object PalacePlan {

    internal val pal = Palettes.Palace

    /**
     * Reihenfolge: Gelände, Graben, Mauern und Türme, dann der Hof (über das Pflaster), dann der Anmarsch (Abschnitte
     * 0 … 4, er schneidet sich durch Hof und Torhaus), Trommel und Kuppel, danach das Hundetor (Abschnitt 5, sitzt vor der
     * Trommel) und zuletzt der Saal. Weiche Blöcke (Laternen, Pflanzen) setzt jeder Teil nach seinem eigenen Träger.
     */
    fun draw(c: BlockCanvas, o: BlockPos) {
        ground(c, o)
        moat(c, o)
        courtWalls(c, o)
        PalaceCourt.draw(c, o)
        PalaceApproach.approach(c, o)
        PalaceApproach.propylon(c, o)
        PalaceApproach.shadows(c, o)
        PalaceApproach.judges(c, o)
        PalaceApproach.styx(c, o)
        drum(c, o)
        dome(c, o)
        PalaceApproach.portico(c, o)
        PalaceHall.arenaCore(c, o)
    }

    internal fun stele(story: Int): BlockState = HhaBlocks.STELE.defaultState.with(SteleBlock.STORY, story)

    /** Plateau, Pflaster und eine weiche Böschung ins Gelände. */
    private fun ground(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        c.pad(o.x, o.z, COURT + 3.0, y + 1, 12, pal.ROCK, square = true, clear = 70, top = pal.PLINTH)
        val edge = COURT + 3
        for (x in maxOf(o.x - edge, c.minX)..minOf(o.x + edge, c.maxX)) {
            for (z in maxOf(o.z - edge, c.minZ)..minOf(o.z + edge, c.maxZ)) {
                c.put(x, y, z, paving(x - o.x, z - o.z, x, y, z))
            }
        }
    }

    /** Pflaster aus Tiefschiefer; wo sich die Fugen kreuzen, liegt Gold. */
    internal fun paving(dx: Int, dz: Int, x: Int, y: Int, z: Int): BlockState = when {
        dx % 9 == 0 && dz % 9 == 0 -> Blocks.GILDED_BLACKSTONE.defaultState
        dx % 9 == 0 || dz % 9 == 0 -> Blocks.POLISHED_DEEPSLATE.defaultState
        Kit.noise(x, y, z) % 11 == 0 -> Blocks.CRACKED_DEEPSLATE_TILES.defaultState
        else -> Blocks.DEEPSLATE_TILES.defaultState
    }

    /**
     * Der Styx läuft als Graben um die Halle (r 58,5 … 63,5, randvoll). Im Süden weitet er sich zum Becken der Styx-Brücke,
     * das [PalaceApproach.styx] zeichnet; die Brüstung des Grabens fehlt nahe dem Anmarsch, damit die Fluchttreppen des
     * Beckens in den Hof führen.
     */
    private fun moat(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        c.disc(o.x, o.z, 65.5) { x, z, r ->
            val dx = x - o.x
            val dz = z - o.z
            val bridge = PalaceApproach.inBasin(dx, dz)
            val open = abs(dx) <= 12 && dz > 0
            when {
                r in 58.5..63.5 && !bridge -> {
                    c.put(x, y, z, Blocks.WATER.defaultState)
                    c.put(x, y - 1, z, Blocks.WATER.defaultState)
                    c.put(x, y - 2, z, Blocks.WATER.defaultState)
                    c.put(x, y - 3, z, if (Kit.noise(x, 6, z) % 4 == 0) Blocks.SOUL_SOIL.defaultState else Blocks.BLACKSTONE.defaultState)
                }
                (r in 57.0..58.5 || r in 63.5..65.0) && !bridge -> {
                    c.put(x, y, z, pal.TRIM(x, y, z))
                    if (!open && (r in 57.3..58.2 || r in 63.8..64.7)) {
                        c.put(x, y + 1, z, if (Kit.noise(x, 7, z) % 3 == 0) Blocks.POLISHED_BLACKSTONE_WALL.defaultState else Blocks.POLISHED_BLACKSTONE_SLAB.defaultState)
                    }
                }
            }
        }
    }

    /** Ringmauer mit Strebepfeilern alle zwölf Blöcke, Statuennischen dazwischen, Ecktürmen und dem Torbau im Süden. */
    private fun courtWalls(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val a = COURT
        val h = 19
        val style = pal.STYLE
        // In jeder Nische eine kleine Figur: Basaltleib, ein Schädel als Kopf.
        val niche: (Int, Int, Int, Direction) -> Unit = { x, ny, z, facing ->
            c.put(x, ny, z, Blocks.POLISHED_BASALT.defaultState)
            c.put(x, ny + 1, z, Blocks.POLISHED_BASALT.defaultState)
            c.put(x, ny + 2, z, skullFacing(facing, wither = Kit.noise(x, ny, z) % 3 == 0))
        }
        c.wall(true, o.x - a, o.x + a, o.z - a, y, h, 5, style, pierEvery = 12, crestSides = intArrayOf(-1), bandAt = h - 4, niche = niche)
        c.wall(false, o.z - a, o.z + a, o.x - a, y, h, 5, style, pierEvery = 12, crestSides = intArrayOf(-1), bandAt = h - 4, niche = niche)
        c.wall(false, o.z - a, o.z + a, o.x + a, y, h, 5, style, pierEvery = 12, crestSides = intArrayOf(1), bandAt = h - 4, niche = niche)
        c.wall(true, o.x - a, o.x - 16, o.z + a, y, h, 5, style, pierEvery = 12, crestSides = intArrayOf(1), bandAt = h - 4, niche = niche)
        c.wall(true, o.x + 16, o.x + a, o.z + a, y, h, 5, style, pierEvery = 12, crestSides = intArrayOf(1), bandAt = h - 4, niche = niche)
        crestBand(c, o, h)
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            tower(c, o.x + sx * a, o.z + sz * a, y, CORNER_TOWER_R, CORNER_TOWER_H, 10)
            towerStair(c, o, o.x + sx * a, o.z + sz * a, CORNER_TOWER_R, CORNER_TOWER_H)
        }
        // Torbau: zwei Türme und ein massiver Block; durch ihn führt nur der Gang der Schatten (PalaceApproach.shadows).
        val gz = o.z + a
        for (sx in intArrayOf(-1, 1)) tower(c, o.x + sx * 15, gz, y, 7.0, 28, 6)
        c.prism(o.x - 11, y + 1, gz - 3, o.x + 11, y + 25, gz + 3, style.face)
        c.prism(o.x - 11, y + 21, gz - 4, o.x + 11, y + 21, gz + 4, pal.TRIM)
        c.prism(o.x - 11, y + 1, gz - 4, o.x + 11, y + 2, gz - 4, pal.PLINTH)
        for (x in o.x - 11..o.x + 11) {
            for (side in intArrayOf(-1, 1)) {
                c.block(x, y + 24, gz + side * 4, Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, if (side > 0) Direction.NORTH else Direction.SOUTH, top = true))
                if ((x - o.x) % 3 != 2) {
                    c.block(x, y + 26, gz + side * 3, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultState)
                    c.block(x, y + 27, gz + side * 3, Kit.slab(Blocks.POLISHED_BLACKSTONE_BRICK_SLAB))
                }
            }
        }
        // Der alte Torbogen bleibt als Blendbogen zum Hof: Basaltziegel im Feld, gemeißelter Bogenrand.
        for (dx in -8..8) {
            for (hh in 1..17) {
                val r = hypot(dx.toDouble(), hh - 9.0)
                val inside = hh <= 9 || r <= 8.4
                val rim = (abs(dx) == 8 && hh <= 9) || (hh > 9 && r in 7.4..8.4)
                if (inside) c.block(o.x + dx, y + hh, gz - 3, if (rim) HhaDecoBlocks.CHISELED_TARTARUS_BASALT.defaultState else HhaDecoBlocks.TARTARUS_BASALT_BRICKS.defaultState)
            }
        }
        // Über dem Tor das Zeichen des Hauses: der Bident, aus Gold und Licht (über dem Giebel des Propylons).
        val rz = gz + 4
        for (hh in 20..31) c.block(o.x, y + hh, rz, Blocks.GILDED_BLACKSTONE.defaultState)
        for (dx in -3..3) c.block(o.x + dx, y + 28, rz, Blocks.GILDED_BLACKSTONE.defaultState)
        for (sx in intArrayOf(-3, 3)) for (hh in 29..32) c.block(o.x + sx, y + hh, rz, Blocks.VERDANT_FROGLIGHT.defaultState)
        c.block(o.x, y + 32, rz, pal.GOLD)
    }

    /** Ecktürme: Radius und Höhe (bis zur Plattform mit dem Säulenkranz). */
    private const val CORNER_TOWER_R = 11.5
    private const val CORNER_TOWER_H = 38

    /** Band aus gemeißeltem Basalt in der obersten Lage der Hofmauer, direkt unter den Zinnen (außen). */
    private fun crestBand(c: BlockCanvas, o: BlockPos, h: Int) {
        val a = COURT
        val band = HhaDecoBlocks.CHISELED_TARTARUS_BASALT.defaultState
        for (t in -a..a) {
            c.block(o.x + t, o.y + h, o.z - a - 2, band)
            c.block(o.x - a - 2, o.y + h, o.z + t, band)
            c.block(o.x + a + 2, o.y + h, o.z + t, band)
            if (abs(t) >= 16) c.block(o.x + t, o.y + h, o.z + a + 2, band)
        }
    }

    /**
     * Macht einen Eckturm begehbar: hohler Schaft (r < 9), Tür zum Hof (3 breit, 4 hoch, Richtung Palastmitte), eine
     * Wendelrampe aus Treppen an der Wand (Lauffläche r 6 … 9, innen ein Geländer) bis zur Plattform mit Säulenkranz und
     * Feuerschale, Seelenadern als Licht in der Rampe. Die Rampe dreht sich knapp 0,8 Mal, also gibt es keine Kopfstöße.
     */
    private fun towerStair(c: BlockCanvas, o: BlockPos, cx: Int, cz: Int, r: Double, height: Int) {
        val y = o.y
        val hollow = r - 2.5
        val door = atan2((o.z - cz).toDouble(), (o.x - cx).toDouble())
        val start = door + 0.55
        val pitch = 7.5
        val railIn = hollow - 4.0
        val walk = hollow - 3.0
        val stairs = HhaDecoBlocks.TARTARUS_BASALT_BRICK_STAIRS
        c.disc(cx, cz, hollow) { x, z, d ->
            c.put(x, y, z, if (d < railIn) HhaDecoBlocks.POLISHED_TARTARUS_BASALT.defaultState else Blocks.POLISHED_DEEPSLATE.defaultState)
            for (h in 1 until height) c.put(x, y + h, z, Kit.AIR)
            if (d < railIn) return@disc
            val a = atan2((z - cz).toDouble(), (x - cx).toDouble())
            val rel = Math.floorMod(Math.round((a - start) * 1000.0), Math.round(2.0 * Math.PI * 1000.0)) / 1000.0
            val level = 1 + (rel * pitch).toInt()
            if (level > height) return@disc
            // Unter der Stufe massiv, die Wangen mit Seelenadern alle sechs Lagen.
            for (h in 1 until level) c.put(x, y + h, z, if (d < walk && h % 6 == 0) HhaDecoBlocks.SOUL_VEIN_BASALT.defaultState else pal.FACE(x, y + h, z))
            val tx = -sin(a)
            val tz = cos(a)
            val facing = if (abs(tx) > abs(tz)) (if (tx > 0) Direction.EAST else Direction.WEST) else (if (tz > 0) Direction.SOUTH else Direction.NORTH)
            if (d < walk) {
                c.put(x, y + level, z, pal.FACE(x, y + level, z))
                if (level < height) c.put(x, y + level + 1, z, HhaDecoBlocks.TARTARUS_BASALT_BRICK_WALL.defaultState)
            } else {
                c.put(x, y + level, z, if (level >= height) Blocks.POLISHED_DEEPSLATE.defaultState else Kit.stair(stairs, facing))
            }
            // Ausstieg: über den obersten Stufen ist die Plattform offen.
            if (level >= height - 3 && d >= walk) for (h in level + 1..height) c.put(x, y + h, z, Kit.AIR)
        }
        // Tür zum Hof durch Wand und Fuß, mit gemeißeltem Sturz.
        val ux = cos(door)
        val uz = sin(door)
        var t = hollow - 0.5
        while (t <= r + 3.0) {
            var off = -1.5
            while (off <= 1.5) {
                val x = cx + Math.round(ux * t - uz * off).toInt()
                val z = cz + Math.round(uz * t + ux * off).toInt()
                c.block(x, y, z, HhaDecoBlocks.POLISHED_TARTARUS_BASALT.defaultState)
                for (h in 1..4) c.block(x, y + h, z, Kit.AIR)
                if (t <= r) c.block(x, y + 5, z, HhaDecoBlocks.CHISELED_TARTARUS_BASALT.defaultState)
                off += 0.5
            }
            t += 0.5
        }
        // Innen am Fuß zwei Seelenlaternen.
        for (side in intArrayOf(-1, 1)) {
            val lx = cx + Math.round(ux * (hollow - 4.5) - uz * side * 2.0).toInt()
            val lz = cz + Math.round(uz * (hollow - 4.5) + ux * side * 2.0).toInt()
            c.block(lx, y + 1, lz, HhaDecoBlocks.CHISELED_TARTARUS_BASALT.defaultState)
            c.block(lx, y + 2, lz, Kit.lantern(hanging = false))
        }
    }

    /**
     * Turm des Palasts: runder Schaft mit geböschtem Fuß, Lisenen und Lichtnischen, oben ein offener Säulenkranz mit
     * Gebälk, goldenen Spitzen und einer großen Feuerschale in der Mitte. Kein Kegeldach.
     */
    private fun tower(c: BlockCanvas, cx: Int, cz: Int, y: Int, r: Double, height: Int, columns: Int) {
        c.disc(cx, cz, r + 2.6) { x, z, _ -> c.footing(x, z, y, pal.PLINTH) }
        c.taper(cx, cz, y + 1, y + 6, r + 2.4, r + 0.3, pal.PLINTH)
        c.disc(cx, cz, r) { x, z, d ->
            val outer = d > r - 1.3
            for (hh in 1..height) {
                val state = when {
                    outer && hh % 12 == 0 -> pal.TRIM(x, y + hh, z)
                    outer && Kit.wear(x, y + hh, z, hh.toDouble() / height, 1.0, 0.7) -> pal.WORN(x, y + hh, z)
                    else -> pal.FACE(x, y + hh, z)
                }
                c.put(x, y + hh, z, state)
            }
        }
        // Lisenen und dazwischen schmale Lichtnischen, in denen grünes Feuer brennt.
        val count = if (r > 9) 8 else 6
        for (i in 0 until count) {
            val a = i * Math.PI * 2.0 / count + Math.PI / count
            val px = cx + Math.round(cos(a) * (r + 0.7)).toInt()
            val pz = cz + Math.round(sin(a) * (r + 0.7)).toInt()
            for (hh in 7..height - 2) c.block(px, y + hh, pz, pal.PIER(px, y + hh, pz))
            val na = i * Math.PI * 2.0 / count
            val nx = cx + Math.round(cos(na) * (r - 0.4)).toInt()
            val nz = cz + Math.round(sin(na) * (r - 0.4)).toInt()
            for (level in 14..height - 8 step 12) {
                c.block(nx, y + level - 1, nz, Blocks.GILDED_BLACKSTONE.defaultState)
                c.block(nx, y + level, nz, Kit.FIRE)
                for (hh in 1..2) c.block(nx, y + level + hh, nz, Kit.AIR)
                c.block(nx, y + level + 3, nz, pal.TRIM(nx, y + level + 3, nz))
            }
        }
        // Krone: Kragsteine, Plattform, Säulenkranz, Gebälk mit goldenen Spitzen und die Feuerschale.
        val top = y + height
        c.disc(cx, cz, r + 1.6) { x, z, d ->
            if (d > r + 0.3) c.put(x, top - 1, z, Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Kit.inward(x - cx, z - cz), top = true))
            c.put(x, top, z, if (d > r + 0.5) pal.TRIM(x, top, z) else Blocks.POLISHED_DEEPSLATE.defaultState)
            if (d > r + 0.6 && Kit.noise(x, 9, z) % 2 == 0) c.put(x, top + 1, z, Blocks.POLISHED_BLACKSTONE_WALL.defaultState)
        }
        val ring = r - 2.0
        val colH = if (r > 9) 8 else 6
        for (i in 0 until columns) {
            val a = i * Math.PI * 2.0 / columns
            val px = cx + Math.round(cos(a) * ring).toInt()
            val pz = cz + Math.round(sin(a) * ring).toInt()
            c.column(px, top + 1, pz, colH, 0)
            c.block(px, top + colH + 3, pz, pal.GOLD)
        }
        c.disc(cx, cz, ring + 1.6) { x, z, d ->
            if (d > ring - 1.6) {
                c.put(x, top + colH + 2, z, if (d > ring + 0.9 || d < ring - 0.9) Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, if (d > ring) Kit.inward(x - cx, z - cz) else Kit.inward(cx - x, cz - z), top = true) else pal.TRIM(x, top + colH + 2, z))
            }
        }
        // Die Feuerschale: Sockel, goldener Rand, ein Feld aus grünem Feuer.
        val bowl = if (r > 9) 2 else 1
        c.prism(cx - 1, top + 1, cz - 1, cx + 1, top + 3, cz + 1) { _, _, _ -> Blocks.POLISHED_BLACKSTONE.defaultState }
        c.disc(cx, cz, bowl + 1.5) { x, z, d ->
            c.put(x, top + 4, z, if (d > bowl + 0.5) Kit.stair(Blocks.POLISHED_BLACKSTONE_STAIRS, Kit.inward(x - cx, z - cz), top = true) else Blocks.GILDED_BLACKSTONE.defaultState)
            if (d <= bowl + 0.5) c.put(x, top + 5, z, Kit.FIRE) else if (d > bowl + 1.0) c.put(x, top + 5, z, Kit.slab(Blocks.POLISHED_BLACKSTONE_SLAB))
        }
    }

    /** Polar nach Block: [a] im Bogenmaß, [off] quer zur Richtung. */
    internal fun polarX(o: BlockPos, a: Double, r: Double, off: Double) = o.x + Math.round(cos(a) * r - sin(a) * off).toInt()
    internal fun polarZ(o: BlockPos, a: Double, r: Double, off: Double) = o.z + Math.round(sin(a) * r + cos(a) * off).toInt()

    /** Die runde Außenwand: schwere Quader, hohe Fenster, Strebebögen. */
    private fun drum(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        c.disc(o.x, o.z, DRUM + 0.5) { x, z, r ->
            if (r > HALL) {
                for (h in y + 1..y + DRUM_TOP) {
                    val course = h - y
                    val state = when {
                        course == 1 || course == DRUM_TOP -> pal.TRIM(x, h, z)
                        course % 12 == 0 -> pal.TRIM(x, h, z)
                        r > DRUM - 0.6 && Kit.wear(x, h, z, course.toDouble() / DRUM_TOP, 1.0, 0.6) -> pal.WORN(x, h, z)
                        else -> pal.FACE(x, h, z)
                    }
                    c.put(x, h, z, state)
                }
            }
        }
        c.disc(o.x, o.z, DRUM + 2.0) { x, z, r ->
            if (r > DRUM + 0.4) {
                c.put(x, y + 1, z, pal.PLINTH(x, y + 1, z))
                c.put(x, y + 2, z, Kit.stair(Blocks.POLISHED_DEEPSLATE_STAIRS, Kit.inward(x - o.x, z - o.z)))
                c.put(x, y + DRUM_TOP, z, Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Kit.inward(x - o.x, z - o.z), top = true))
                if (r > DRUM + 1.0 && Kit.noise(x, 9, z) % 2 == 0) {
                    c.put(x, y + DRUM_TOP + 1, z, Blocks.POLISHED_BLACKSTONE_WALL.defaultState)
                    c.put(x, y + DRUM_TOP + 2, z, Blocks.POLISHED_BLACKSTONE_SLAB.defaultState)
                }
            }
        }
        for (i in 0 until 12) {
            val strut = i * Math.PI / 6.0 + Math.PI / 12.0
            val sx0 = polarX(o, strut, DRUM - 0.5, 0.0)
            val sz0 = polarZ(o, strut, DRUM - 0.5, 0.0)
            val sx1 = polarX(o, strut, DRUM + 7.0, 0.0)
            val sz1 = polarZ(o, strut, DRUM + 7.0, 0.0)
            c.limb(sx0, y + DRUM_TOP - 2, sz0, sx1, y + 13, sz1, 1.0, 1.5, pal.FACE)
            c.taper(sx1, sz1, y + 1, y + 13, 3.2, 1.9, pal.FACE)
            c.block(sx1, y + 14, sz1, Blocks.GILDED_BLACKSTONE.defaultState)
            c.block(sx1, y + 15, sz1, Kit.FIRE)
            val pane = i * Math.PI / 6.0
            if (i == 3 || i == 9) continue
            for (h in 7..25) {
                val width = if (h > 22) (2.6 - (h - 22) * 0.8).coerceAtLeast(0.0) else 2.6
                var off = -width
                while (off <= width) {
                    var r = HALL - 0.5
                    while (r <= DRUM + 0.5) {
                        val wx = polarX(o, pane, r, off)
                        val wz = polarZ(o, pane, r, off)
                        val edge = abs(off) > width - 0.5
                        c.block(wx, y + h, wz, if (r > DRUM - 1.0 && edge) pal.TRIM(wx, y + h, wz) else if (r in (HALL + 1.5)..(DRUM - 1.0)) Blocks.IRON_BARS.defaultState else Kit.AIR)
                        r += 0.5
                    }
                    off += 0.5
                }
            }
        }
    }

    /** Die Kuppel: glatt geführte Schale aus Schindeln, zwölf Kupferrippen, oben ein offenes Auge. */
    private fun dome(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val base = y + DRUM_TOP + 1
        c.disc(o.x, o.z, DRUM + 0.5) { x, z, r ->
            val outer = base + DOME_RISE * sqrt((1.0 - (r / (DRUM + 0.5)) * (r / (DRUM + 0.5))).coerceAtLeast(0.0))
            val inner = base - 3 + (DOME_RISE - 1) * sqrt((1.0 - (r / HALL) * (r / HALL)).coerceAtLeast(0.0))
            if (r < EYE) return@disc
            val a = atan2((z - o.z).toDouble(), (x - o.x).toDouble())
            val rib = abs(Math.IEEEremainder(a * 6.0 / Math.PI, 1.0)) * r * Math.PI / 6.0 < 1.4
            val from = if (r <= HALL) inner.toInt() else base
            val top = outer.toInt()
            val up = outer - top
            for (h in from..top) {
                val state = when {
                    r < EYE + 2.0 -> pal.TRIM(x, h, z)
                    rib -> pal.RIB(x, h, z)
                    Kit.noise(x, h, z) % 9 == 0 -> Blocks.CRACKED_DEEPSLATE_TILES.defaultState
                    else -> Blocks.DEEPSLATE_TILES.defaultState
                }
                c.put(x, h, z, state)
            }
            if (up >= 0.45) {
                c.put(x, top + 1, z, if (rib) Kit.stair(Blocks.WAXED_OXIDIZED_CUT_COPPER_STAIRS, Kit.inward(x - o.x, z - o.z)) else Kit.stair(Blocks.DEEPSLATE_TILE_STAIRS, Kit.inward(x - o.x, z - o.z)))
            }
            if (r <= HALL) {
                for (h in y + DRUM_TOP - 2 until from) c.put(x, h, z, Kit.AIR)
            }
        }
        // Um das Auge steht ein Kranz aus Hörnern mit goldenen Spitzen; das Licht fällt grün in den Saal.
        val crown = base + DOME_RISE - 2
        for (i in 0 until 8) {
            val a = i * Math.PI / 4.0
            val ix = polarX(o, a, EYE + 1.5, 0.0)
            val iz = polarZ(o, a, EYE + 1.5, 0.0)
            val ox = polarX(o, a, EYE + 4.5, 0.0)
            val oz = polarZ(o, a, EYE + 4.5, 0.0)
            c.limb(ix, crown - 1, iz, ox, crown + 6, oz, 1.2, 0.7, pal.FACE)
            c.limb(ox, crown + 6, oz, ix, crown + 11, iz, 0.7, 0.4, pal.TRIM)
            c.block(ix, crown + 12, iz, pal.GOLD)
            c.block(ix, crown + 13, iz, Kit.FIRE)
        }
        c.tube(o.x, o.z, crown - 2, crown, EYE + 1.5, 1.5, pal.TRIM)
    }
}
