package dev.henny.hha.world

import dev.henny.hha.HhaBlocks
import dev.henny.hha.block.SteleBlock
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

    private val pal = Palettes.Palace

    fun draw(c: BlockCanvas, o: BlockPos) {
        ground(c, o)
        moat(c, o)
        courtWalls(c, o)
        approach(c, o)
        propylon(c, o)
        drum(c, o)
        dome(c, o)
        portico(c, o)
        hall(c, o)
        throne(c, o)
    }

    private fun stele(story: Int): BlockState = HhaBlocks.STELE.defaultState.with(SteleBlock.STORY, story)

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
    private fun paving(dx: Int, dz: Int, x: Int, y: Int, z: Int): BlockState = when {
        dx % 9 == 0 && dz % 9 == 0 -> Blocks.GILDED_BLACKSTONE.defaultState
        dx % 9 == 0 || dz % 9 == 0 -> Blocks.POLISHED_DEEPSLATE.defaultState
        Kit.noise(x, y, z) % 11 == 0 -> Blocks.CRACKED_DEEPSLATE_TILES.defaultState
        else -> Blocks.DEEPSLATE_TILES.defaultState
    }

    /** Der Styx läuft als Graben um die Halle. */
    private fun moat(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        c.disc(o.x, o.z, 65.5) { x, z, r ->
            val dx = x - o.x
            val dz = z - o.z
            val bridge = abs(dx) <= 7 && dz > 0
            when {
                r in 58.5..63.5 && !bridge -> {
                    c.put(x, y, z, Blocks.WATER.defaultState)
                    c.put(x, y - 1, z, Blocks.WATER.defaultState)
                    c.put(x, y - 2, z, Blocks.WATER.defaultState)
                    c.put(x, y - 3, z, if (Kit.noise(x, 6, z) % 4 == 0) Blocks.SOUL_SOIL.defaultState else Blocks.BLACKSTONE.defaultState)
                }
                (r in 57.0..58.5 || r in 63.5..65.0) && !bridge -> {
                    c.put(x, y, z, pal.TRIM(x, y, z))
                    if (r in 57.3..58.2 || r in 63.8..64.7) {
                        c.put(x, y + 1, z, if (Kit.noise(x, 7, z) % 3 == 0) Blocks.POLISHED_BLACKSTONE_WALL.defaultState else Blocks.POLISHED_BLACKSTONE_SLAB.defaultState)
                    }
                }
            }
        }
        for (dz in 56..67) {
            for (side in intArrayOf(-1, 1)) {
                val bx = o.x + side * 7
                val bz = o.z + dz
                c.block(bx, y, bz, pal.TRIM(bx, y, bz))
                c.block(bx, y + 1, bz, if (dz % 3 == 0) Blocks.POLISHED_BLACKSTONE_BRICKS.defaultState else Blocks.POLISHED_BLACKSTONE_WALL.defaultState)
                if (dz == 57 || dz == 66) c.brazier(bx, y + 2, bz, 1)
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
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) tower(c, o.x + sx * a, o.z + sz * a, y, 11.5, 38, 10)
        // Torbau: zwei Türme und ein Bogen mit halb herabgelassenem Fallgitter.
        val gz = o.z + a
        for (sx in intArrayOf(-1, 1)) tower(c, o.x + sx * 15, gz, y, 7.0, 28, 6)
        c.prism(o.x - 11, y + 1, gz - 3, o.x + 11, y + 25, gz + 3, style.face)
        c.prism(o.x - 11, y + 21, gz - 4, o.x + 11, y + 21, gz + 4, pal.TRIM)
        c.prism(o.x - 11, y + 1, gz + 4, o.x + 11, y + 2, gz + 4, pal.PLINTH)
        for (x in o.x - 11..o.x + 11) {
            for (side in intArrayOf(-1, 1)) {
                c.block(x, y + 24, gz + side * 4, Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, if (side > 0) Direction.NORTH else Direction.SOUTH, top = true))
                if ((x - o.x) % 3 != 2) {
                    c.block(x, y + 26, gz + side * 3, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultState)
                    c.block(x, y + 27, gz + side * 3, Kit.slab(Blocks.POLISHED_BLACKSTONE_BRICK_SLAB))
                }
            }
        }
        for (dx in -8..8) {
            for (hh in 1..16) {
                if (hh <= 9 || hypot(dx.toDouble(), hh - 9.0) <= 8.4) {
                    for (dz in -4..4) c.block(o.x + dx, y + hh, gz + dz, Kit.AIR)
                    if (hh >= 11) c.block(o.x + dx, y + hh, gz, Kit.bars(true))
                }
            }
        }
        // Über dem Tor das Zeichen des Hauses: der Bident, aus Gold und Licht.
        val rz = gz + 4
        for (hh in 19..30) c.block(o.x, y + hh, rz, Blocks.GILDED_BLACKSTONE.defaultState)
        for (dx in -3..3) c.block(o.x + dx, y + 27, rz, Blocks.GILDED_BLACKSTONE.defaultState)
        for (sx in intArrayOf(-3, 3)) for (hh in 28..31) c.block(o.x + sx, y + hh, rz, Blocks.VERDANT_FROGLIGHT.defaultState)
        c.block(o.x, y + 31, rz, pal.GOLD)
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

    /** Prozessionsweg durch den Hof, Totenwächter und Obelisken. */
    private fun approach(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        for (dz in DRUM..COURT + 3) {
            for (dx in -7..7) {
                val x = o.x + dx
                val z = o.z + dz
                c.block(x, y, z, if (abs(dx) == 7) pal.TRIM(x, y, z) else if (dx == 0 && dz % 3 == 0) Blocks.CRYING_OBSIDIAN.defaultState else if ((abs(dx) + dz) % 4 == 0) Blocks.GILDED_BLACKSTONE.defaultState else Blocks.POLISHED_BLACKSTONE.defaultState)
            }
        }
        for (dz in 68..80 step 6) {
            for (side in intArrayOf(-1, 1)) {
                c.statue(o.x + side * 11, y, o.z + dz, if (side > 0) Direction.WEST else Direction.EAST, 15)
            }
        }
        for ((dx, dz) in listOf(-48 to 70, 48 to 70, -72 to -2, 72 to -2, -48 to -70, 48 to -70)) obelisk(c, o.x + dx, y, o.z + dz, 22)
        c.block(o.x + 9, y + 1, o.z + COURT - 5, stele(5))
    }

    private fun obelisk(c: BlockCanvas, x: Int, y: Int, z: Int, height: Int) {
        c.prism(x - 2, y + 1, z - 2, x + 2, y + 1, z + 2, pal.PLINTH)
        c.prism(x - 2, y + 2, z - 2, x + 2, y + 2, z + 2) { px, py, pz -> if (abs(px - x) == 2 || abs(pz - z) == 2) Kit.stair(Blocks.POLISHED_DEEPSLATE_STAIRS, Kit.inward(px - x, pz - z)) else pal.TRIM(px, py, pz) }
        c.taper(x, z, y + 3, y + height, 1.8, 0.6, pal.FACE)
        c.block(x, y + height + 1, z, pal.GOLD)
        c.block(x, y + height + 2, z, Kit.FIRE)
    }

    /**
     * Das Propylon vor dem Tor: Torhalle aus vier schwarzen Säulen, Gebälk, Giebel mit dem Bident als Relief, Gold an den
     * Akroterien. Daneben zwei Obelisken, davor Stufen ins Gelände.
     */
    private fun propylon(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val z0 = o.z + COURT + 5
        val z1 = o.z + COURT + 16
        for (x in o.x - 19..o.x + 19) {
            for (z in z0..z1) {
                c.footing(x, z, y - 1, pal.PLINTH)
                c.block(x, y, z, if (abs(x - o.x) <= 4) Blocks.POLISHED_BLACKSTONE.defaultState else paving(x - o.x, z - o.z, x, y, z))
                for (hh in 1..30) c.block(x, y + hh, z, Kit.AIR)
            }
        }
        // Drei Stufen hinab nach Süden.
        for (step in 1..3) {
            for (x in o.x - 19 + step..o.x + 19 - step) {
                val z = z1 + step
                c.footing(x, z, y - step - 1, pal.PLINTH)
                c.block(x, y - step + 1, z, Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Direction.NORTH))
                for (hh in 1..6) c.block(x, y - step + 1 + hh, z, Kit.AIR)
            }
        }
        // Säulen in zwei Reihen, Gebälk darüber.
        val colH = 13
        for (dx in intArrayOf(-14, -6, 6, 14)) for (z in intArrayOf(z0 + 2, z1 - 2)) c.column(o.x + dx, y + 1, z, colH)
        val beam = y + colH + 2
        c.prism(o.x - 17, beam, z0, o.x + 17, beam + 1, z1, pal.FACE)
        c.prism(o.x - 18, beam, z1 + 1, o.x + 18, beam, z1 + 1) { _, _, _ -> Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Direction.NORTH, top = true) }
        c.prism(o.x - 18, beam, z0 - 1, o.x + 18, beam, z0 - 1) { _, _, _ -> Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Direction.SOUTH, top = true) }
        c.prism(o.x - 17, beam + 1, z1 + 1, o.x + 17, beam + 1, z1 + 1, pal.TRIM)
        // Der Giebel: Dach aus Stufen, vorn und hinten ein Dreieck; im vorderen Feld der Bident.
        val rise = 9
        for (dx in -18..18) {
            val up = rise - abs(dx) / 2
            for (z in z0..z1) {
                val x = o.x + dx
                for (hh in 0 until up) c.block(x, beam + 2 + hh, z, pal.FACE(x, beam + 2 + hh, z))
                c.block(x, beam + 2 + up, z, Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, if (dx < 0) Direction.EAST else Direction.WEST))
            }
        }
        for (dx in -14..14) {
            val up = rise - abs(dx) / 2 - 1
            for (hh in 0 until up) {
                val x = o.x + dx
                c.block(x, beam + 2 + hh, z1 + 1, if (hh == 0) pal.TRIM(x, beam + 2, z1 + 1) else Blocks.POLISHED_BLACKSTONE.defaultState)
            }
        }
        val fz = z1 + 2
        for (hh in 3..8) c.block(o.x, beam + hh, fz, Blocks.GILDED_BLACKSTONE.defaultState)
        for (dx in -2..2) c.block(o.x + dx, beam + 5, fz, Blocks.GILDED_BLACKSTONE.defaultState)
        for (sx in intArrayOf(-2, 2)) for (hh in 6..7) c.block(o.x + sx, beam + hh, fz, Blocks.GILDED_BLACKSTONE.defaultState)
        c.block(o.x, beam + 3 + rise, (z0 + z1) / 2, pal.GOLD)
        for (sx in intArrayOf(-18, 18)) for (z in intArrayOf(z0 - 1, z1 + 1)) c.block(o.x + sx, beam + 2, z, pal.GOLD)
        // Hängende Laternen unter dem Gebälk, zwei Obelisken zu beiden Seiten.
        for (dx in intArrayOf(-10, 0, 10)) {
            for (hh in 1..2) c.block(o.x + dx, beam - hh, (z0 + z1) / 2, Kit.chain())
            c.block(o.x + dx, beam - 3, (z0 + z1) / 2, Kit.lantern())
        }
        for (sx in intArrayOf(-1, 1)) {
            val x = o.x + sx * 25
            val z = (z0 + z1) / 2
            c.prism(x - 3, y - 1, z - 3, x + 3, y, z + 3, pal.PLINTH)
            c.footing(x, z, y - 2, pal.PLINTH)
            obelisk(c, x, y, z, 26)
        }
        for (side in intArrayOf(-1, 1)) c.brazier(o.x + side * 12, y + 1, z1 + 5, 1)
    }

    /** Polar nach Block: [a] im Bogenmaß, [off] quer zur Richtung. */
    private fun polarX(o: BlockPos, a: Double, r: Double, off: Double) = o.x + Math.round(cos(a) * r - sin(a) * off).toInt()
    private fun polarZ(o: BlockPos, a: Double, r: Double, off: Double) = o.z + Math.round(sin(a) * r + cos(a) * off).toInt()

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

    /** Das Vorhaus: Säulen, Giebel und das Rundbogenportal mit dem Siegel. */
    private fun portico(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val half = SEAL_HALF_WIDTH
        val front = o.z + DRUM + 9
        for (dx in -half..half) {
            for (dy in 1..SEAL_HEIGHT) {
                if (!inSealOpening(dx, dy)) continue
                for (dz in HALL - 1..DRUM + 1) c.block(o.x + dx, y + dy, o.z + dz, Kit.AIR)
                c.block(o.x + dx, y + dy, o.z + SEAL_Z, HhaBlocks.HADES_SEAL.defaultState)
            }
        }
        for (dx in -half - 2..half + 2) {
            for (dy in 1..SEAL_HEIGHT + 3) {
                if (inSealOpening(dx, dy)) continue
                val near = abs(dx) <= half + 2 && (dy <= 10 || hypot(dx.toDouble(), dy - 10.0) <= half + 2.4)
                if (near) c.block(o.x + dx, y + dy, o.z + DRUM + 1, if ((dx + dy) and 1 == 0) Blocks.GILDED_BLACKSTONE.defaultState else Blocks.CHISELED_POLISHED_BLACKSTONE.defaultState)
            }
        }
        for (dx in intArrayOf(-17, -10, 10, 17)) {
            for (dz in intArrayOf(DRUM + 2, DRUM + 7)) c.column(o.x + dx, y + 1, o.z + dz, 20)
        }
        c.prism(o.x - 20, y + 22, o.z + DRUM + 1, o.x + 20, y + 24, front, pal.FACE)
        c.prism(o.x - 20, y + 25, o.z + DRUM + 1, o.x + 20, y + 25, front, pal.TRIM)
        c.prism(o.x - 21, y + 22, front + 1, o.x + 21, y + 22, front + 1, pal.TRIM)
        for (dx in -21..21) {
            val rise = 11 - abs(dx) / 2
            for (dz in DRUM + 1..DRUM + 9) {
                val x = o.x + dx
                val z = o.z + dz
                for (h in 0 until rise) c.block(x, y + 26 + h, z, pal.FACE(x, y + 26 + h, z))
                c.block(x, y + 26 + rise, z, Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, if (dx < 0) Direction.EAST else Direction.WEST))
            }
        }
        val fz = o.z + DRUM + 10
        for (dx in -9..9) {
            val rise = 11 - abs(dx) / 2 - 1
            for (h in 0 until rise) c.block(o.x + dx, y + 26 + h, fz, if (h == 0 || h == rise - 1) pal.TRIM(o.x + dx, y + 26 + h, fz) else Blocks.DEEPSLATE_TILES.defaultState)
        }
        for (h in 28..34) c.block(o.x, y + h, fz + 1, Blocks.VERDANT_FROGLIGHT.defaultState)
        for (dx in -2..2) c.block(o.x + dx, y + 30, fz + 1, Blocks.VERDANT_FROGLIGHT.defaultState)
        for (sx in intArrayOf(-2, 2)) for (h in 31..33) c.block(o.x + sx, y + h, fz + 1, Blocks.VERDANT_FROGLIGHT.defaultState)
        c.block(o.x, y + 37, o.z + DRUM + 5, pal.GOLD)
        for (dx in intArrayOf(-7, 0, 7)) {
            for (h in 21 downTo 18) c.block(o.x + dx, y + h, o.z + DRUM + 5, Kit.chain())
            c.block(o.x + dx, y + 17, o.z + DRUM + 5, Kit.lantern())
        }
        for (side in intArrayOf(-1, 1)) c.statue(o.x + side * 25, y, o.z + DRUM + 5, Direction.SOUTH, 18)
    }

    /** Der Saal: Boden mit Runenrädern, Wandpfeiler, Empore, Beinhausnischen, die acht Deckungssäulen und der Kronleuchter. */
    private fun hall(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        c.disc(o.x, o.z, HALL.toDouble()) { x, z, r ->
            c.put(x, y, z, hallFloor(x - o.x, z - o.z, r))
            for (h in y + 1..y + DRUM_TOP - 3) c.put(x, h, z, Kit.AIR)
        }
        for (i in 0 until 12) {
            val a = i * Math.PI / 6.0 + Math.PI / 12.0
            val px = polarX(o, a, HALL - 1.0, 0.0)
            val pz = polarZ(o, a, HALL - 1.0, 0.0)
            c.column(px, y + 1, pz, DRUM_TOP - 4)
        }
        val gallery = y + 16
        c.disc(o.x, o.z, HALL.toDouble()) { x, z, r ->
            if (r > HALL - 4.5) {
                c.put(x, gallery, z, if (r < HALL - 3.5) Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Kit.inward(o.x - x, o.z - z), top = true) else pal.PLINTH(x, gallery, z))
                if (r in (HALL - 4.5)..(HALL - 3.4)) c.put(x, gallery + 1, z, if (Kit.noise(x, 8, z) % 2 == 0) Blocks.POLISHED_BLACKSTONE_WALL.defaultState else Blocks.POLISHED_BLACKSTONE_BRICK_WALL.defaultState)
            }
        }
        for (dx in -SEAL_HALF_WIDTH..SEAL_HALF_WIDTH) {
            for (dz in HALL - 4..HALL) {
                if (hypot(dx.toDouble(), dz.toDouble()) <= HALL) c.block(o.x + dx, gallery + 1, o.z + dz, Kit.AIR)
            }
        }
        for (i in 0 until 32) {
            val a = i * Math.PI / 16.0
            val lx = polarX(o, a, HALL - 3.5, 0.0)
            val lz = polarZ(o, a, HALL - 3.5, 0.0)
            for (h in 1..2) c.block(lx, gallery - h, lz, Kit.chain())
            c.block(lx, gallery - 3, lz, Kit.lantern())
        }
        c.disc(o.x, o.z, HALL.toDouble()) { x, z, r ->
            if (r > HALL - 1.2) {
                val dz = z - o.z
                if (!(dz > 0 && abs(x - o.x) <= SEAL_HALF_WIDTH + 2)) {
                    c.put(x, y + 7, z, Blocks.POLISHED_BLACKSTONE_BRICK_SLAB.defaultState)
                    if (Kit.noise(x, 7, z) % 3 != 0) c.put(x, y + 8, z, skull(Kit.noise(x, 8, z) % 16))
                }
            }
        }
        for ((dx, dz) in coverPillars()) {
            c.column(o.x + dx, y + 1, o.z + dz, PILLAR_HEIGHT - 2, PILLAR_RADIUS)
            for (band in intArrayOf(9, 18)) {
                c.cyl(o.x + dx, o.z + dz, y + band, y + band, PILLAR_RADIUS + 0.7, pal.TRIM)
            }
            for (i in 0 until 4) {
                val a = i * Math.PI / 2.0 + Math.PI / 4.0
                val sx = o.x + dx + (cos(a) * 3.2).toInt()
                val sz = o.z + dz + (sin(a) * 3.2).toInt()
                for (h in 0..1) c.block(sx, y + 22 - h, sz, Kit.chain())
                c.block(sx, y + 20, sz, Kit.lantern())
            }
        }
        val ring = y + CHANDELIER_Y
        c.tube(o.x, o.z, ring, ring, 11.0, 1.6, pal.TRIM)
        c.tube(o.x, o.z, ring + 1, ring + 1, 11.0, 0.8) { _, _, _ -> Blocks.POLISHED_BLACKSTONE_SLAB.defaultState }
        for (i in 0 until 12) {
            val a = i * Math.PI / 6.0
            val fx = polarX(o, a, 10.3, 0.0)
            val fz = polarZ(o, a, 10.3, 0.0)
            if (i % 3 == 0) {
                val ceiling = y + DRUM_TOP + DOME_RISE - 4
                for (h in ring + 1..ceiling) c.block(fx, h, fz, Kit.chain())
            } else {
                c.block(fx, ring + 1, fz, Blocks.GILDED_BLACKSTONE.defaultState)
                c.block(fx, ring + 2, fz, Kit.FIRE)
            }
            for (h in 1..3) c.block(fx, ring - h, fz, Kit.chain())
            c.block(fx, ring - 4, fz, Kit.lantern())
        }
    }

    private fun hallFloor(dx: Int, dz: Int, r: Double): BlockState {
        val angle = atan2(dz.toDouble(), dx.toDouble())
        val sector = Math.floorMod(Math.floor(angle * 12.0 / Math.PI).toInt(), 2)
        val spoke = abs(sin(angle * 6.0)) * r < 0.8 && r > 7.0
        return when {
            r > HALL - 1.5 -> Blocks.POLISHED_DEEPSLATE.defaultState
            // Lichtpunkte statt eines Rings: Licht sparsam, keine Froschlicht-Flächen.
            r > HALL - 2.7 -> if ((abs(dx) + abs(dz)) % 4 == 0) Blocks.VERDANT_FROGLIGHT.defaultState else Blocks.GILDED_BLACKSTONE.defaultState
            r < 2.2 -> Blocks.CRYING_OBSIDIAN.defaultState
            r < 3.4 -> Blocks.VERDANT_FROGLIGHT.defaultState
            r in 7.0..8.0 || r in 24.0..25.0 -> Blocks.GILDED_BLACKSTONE.defaultState
            r in 15.0..16.0 || r in 33.0..34.0 -> Blocks.CHISELED_POLISHED_BLACKSTONE.defaultState
            spoke -> Blocks.POLISHED_BLACKSTONE_BRICKS.defaultState
            sector == 0 -> Blocks.POLISHED_BLACKSTONE.defaultState
            else -> Blocks.DEEPSLATE_TILES.defaultState
        }
    }

    /** Podest, Thron, die leere Lehne Persephones und die Wand dahinter. */
    private fun throne(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val tz = o.z + THRONE_Z
        for (step in 1..DAIS_HEIGHT) {
            val radius = 15.0 - (step - 1) * 2.4
            c.disc(o.x, tz, radius) { x, z, r ->
                if (hypot((x - o.x).toDouble(), (z - o.z).toDouble()) <= HALL) {
                    c.put(x, y + step, z, if (r > radius - 1.0) Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Kit.inward(x - o.x, z - tz)) else if (step == DAIS_HEIGHT && r < 2.0) Blocks.GILDED_BLACKSTONE.defaultState else Blocks.POLISHED_DEEPSLATE.defaultState)
                }
            }
        }
        val top = y + DAIS_HEIGHT
        c.prism(o.x - 2, top + 1, tz - 2, o.x + 2, top + 1, tz) { _, _, _ -> Blocks.POLISHED_BLACKSTONE_BRICKS.defaultState }
        c.prism(o.x - 2, top + 1, tz, o.x + 2, top + 1, tz) { _, _, _ -> Blocks.GILDED_BLACKSTONE.defaultState }
        for (side in intArrayOf(-3, 3)) {
            c.prism(o.x + side, top + 1, tz - 2, o.x + side, top + 3, tz + 1, pal.TRIM)
            c.block(o.x + side, top + 4, tz + 1, skull(0, wither = true))
        }
        c.prism(o.x - 3, top + 1, tz - 3, o.x + 3, top + 15, tz - 3, pal.FACE)
        c.prism(o.x - 4, top + 1, tz - 4, o.x + 4, top + 15, tz - 4, pal.TRIM)
        for (h in 2..13) c.block(o.x, top + h, tz - 2, if (h % 2 == 0) Blocks.VERDANT_FROGLIGHT.defaultState else Blocks.GILDED_BLACKSTONE.defaultState)
        for ((i, offset) in listOf(-4, -2, 0, 2, 4).withIndex()) {
            val tall = 7 - abs(i - 2) * 2
            c.limb(o.x + offset, top + 15, tz - 3, o.x + offset + offset / 2, top + 15 + tall + 3, tz - 4, 1.2, 0.5, pal.FACE)
            c.block(o.x + offset + offset / 2, top + 15 + tall + 4, tz - 4, if (offset == 0) pal.GOLD else Kit.FIRE)
        }
        val px = o.x + 8
        val pz = tz + 1
        c.prism(px - 1, top + 1, pz - 1, px + 1, top + 1, pz) { _, _, _ -> Blocks.POLISHED_DEEPSLATE.defaultState }
        c.prism(px - 1, top + 1, pz - 2, px + 1, top + 7, pz - 2, pal.FACE)
        c.block(px, top + 8, pz - 2, Blocks.GILDED_BLACKSTONE.defaultState)
        c.block(px, top + 2, pz, Blocks.WITHER_ROSE.defaultState)
        c.block(px - 1, top + 2, pz - 1, Blocks.LILY_OF_THE_VALLEY.defaultState)
        for (side in intArrayOf(-1, 1)) {
            c.column(o.x + side * 13, top - 2, tz + 3, 12)
            c.brazier(o.x + side * 13, top + 12, tz + 3, 0)
        }
        for (dx in -16..16) {
            for (h in 1..24) {
                val z = tz - 7 - (abs(dx) * abs(dx) / 40)
                val x = o.x + dx
                if (hypot(dx.toDouble(), (z - o.z).toDouble()) > HALL) continue
                c.put(x, y + h, z, when {
                    abs(dx) % 6 == 0 && h > 3 -> Blocks.VERDANT_FROGLIGHT.defaultState
                    h % 8 == 0 -> pal.TRIM(x, y + h, z)
                    else -> pal.FACE(x, y + h, z)
                })
                if (abs(dx) % 3 == 0 && h in 5..23 && h % 6 == 5) c.block(x, y + h, z + 1, Blocks.END_ROD.defaultState.with(Properties.FACING, Direction.SOUTH))
            }
        }
    }
}
