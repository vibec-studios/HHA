package dev.henny.hha.world.sites

import dev.henny.hha.HhaDecoBlocks
import net.minecraft.block.Blocks
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import dev.henny.hha.world.*
import dev.henny.hha.world.TartarusPlans.IXION_PLATFORM
import dev.henny.hha.world.TartarusPlans.stele
import dev.henny.hha.world.arena.ArenaGeo

/**
 * Bauplan der Strafstätte des Ixion (Überarbeitung 4, P13). Rad, Stützen und der Verurteilte bleiben. Die Plattform r 19
 * bekommt einen klaren Rand: Kampfring r 10,8 … 17,4 als ruhige Schwarzsteinfläche mit Fugenbild und sechs bündigen
 * Glutrinnen (die Speichen des Speichenfeuers), Randstufe r 17,6 … 19 auf +1 mit drei Aufgängen, außen eine Stützmauer
 * bis ins Gelände; drei Prellsteine (Deckung gegen das Speichenfeuer) mit einem Standfeld dahinter. Zuweg nach Süden als
 * Allee aus Glutrinnen. Alle Vertragsmaße kommen aus [TartarusArenas.ixion].
 *
 * [draw] zeichnet, was am Gelände hängt (Planum, Fundamente, untere Außenstufen, Zuweg); [arenaCore] alles mit fester
 * Höhe relativ zu `o`.
 */
internal object IxionPlan {

    // --- Plattform (Blockringe, Abstand der Blockmitte zur Mitte) ----------------------------------------------------

    /** Glutmulde unter der Nabe. */
    private const val HOLLOW = 3.6
    private const val HOLLOW_FIRE = 2.6

    /** Fugenring im Kampfring und Randband vor der Stufe. */
    private const val JOINT_RING_INNER = 13.6
    private const val JOINT_RING_OUTER = 14.4
    private const val CORE_BAND = 16.4

    /** Vorderkante der Randstufe (Trittkante aus poliertem Basalt) bis hier, dahinter Basaltziegel. */
    private const val STEP_NOSE = 18.2

    /** Stützmauer außen: bis zu diesem Blockring, Krone bündig mit der Randstufe. */
    private const val WALL_OUTER = 20.2

    /** Standfeld hinter jedem Prellstein: bis zu diesem Blockring, halbe Breite quer. */
    private const val BASTION_OUTER = 21.6
    private const val BASTION_HALF = 2.5

    /** Glutrinnen: sechs, von der Mulde bis an die Stufe, zwischen Prellsteinen und Aufgängen. */
    private val CHANNEL_ANGLES = doubleArrayOf(0.0, 60.0, 120.0, 180.0, 240.0, 300.0)

    /** Fugen im Kampfring: zeigen auf Prellsteine und Aufgänge. */
    private val JOINT_ANGLES = doubleArrayOf(30.0, 90.0, 150.0, 210.0, 270.0, 330.0)

    /** Aufgänge: halbe Breite quer (3 breit), innerer Lauf bis zu diesem Blockring, äußere Stufe bis [ACCESS_LOW_OUTER]. */
    private const val ACCESS_HALF = 1.5
    private const val ACCESS_INNER_TO = 18.6
    private const val ACCESS_LOW_OUTER = 21.4

    /** Feuerschalen auf der Stützmauer beidseits jedes Aufgangs (Abstand quer) und am Ende jedes Standfelds. */
    private const val ACCESS_FIRE_SIDE = 3.0
    private const val FIRE_RING = 19.6
    private const val BASTION_FIRE = 21.0

    /** Lichte Höhe über Kampfring und Rand, die der Kern freiräumt. */
    private const val CLEAR = 4

    /** Fuß der schrägen Streben (vorher x ± 9; jetzt ± 7, damit der Kampfring ab r 10,8 frei bleibt). */
    private const val STRUT_FOOT = 7

    /** Zuweg: Länge nach Süden ab der Plattform; Glutrinnen und Feuerpfosten quer zur Mittellinie. */
    private const val ROAD_FROM = 29
    private const val ROAD_TO = 21
    private const val ROAD_WIDTH = 5
    private const val ROAD_CHANNEL = 3
    private const val ROAD_POST = 4.0

    private val P get() = Palettes.Ixion

    private fun angleDeg(dx: Int, dz: Int): Double = ArenaGeo.angleOf(dx.toDouble(), dz.toDouble())

    /** Abstand quer zu einem Strahl unter [deg] (nur vor der Mitte, sonst unendlich). */
    private fun across(dx: Int, dz: Int, deg: Double): Double {
        val a = Math.toRadians(deg)
        val along = dx * cos(a) + dz * sin(a)
        return if (along <= 0.0) Double.MAX_VALUE else abs(-dx * sin(a) + dz * cos(a))
    }

    /** Himmelsrichtung, die einem Winkel am nächsten liegt (0° Osten, 90° Süden). */
    private fun cardinal(deg: Double): Direction = when ((((deg % 360.0) + 405.0) % 360.0 / 90.0).toInt()) {
        0 -> Direction.EAST
        1 -> Direction.SOUTH
        2 -> Direction.WEST
        else -> Direction.NORTH
    }

    private fun accessAngles(o: BlockPos): List<Double> =
        TartarusArenas.ixion(o).level("rim_step").access.map { angleDeg(it.x - o.x, it.z - o.z) }

    private fun bastion(dx: Int, dz: Int, r: Double): Boolean =
        r > WALL_OUTER && r <= BASTION_OUTER && TartarusArenas.IXION_BUMPER_ANGLES.any { across(dx, dz, it) <= BASTION_HALF }

    private val wallPal: Pal get() = DecoPalettes.BASALT_WALL

    // =============================================================================================================
    // Voller Plan
    // =============================================================================================================

    /** Ixion: ewig auf ein feuriges Rad gebunden. Rot und Schwarz, die Nabe glüht. */
    fun draw(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        c.pad(o.x, o.z, IXION_PLATFORM.toDouble(), y + 1, 3, P.FACE, square = false, clear = 34)
        approach(c, o)
        foundations(c, o)
        arenaCore(c, o)
    }

    /** Fundamente: Stützmauer und Standfelder bis ins Gelände (Band aus Basaltziegeln unter der Krone), untere Außenstufen. */
    private fun foundations(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val access = accessAngles(o)
        c.disc(o.x, o.z, ACCESS_LOW_OUTER + 0.5) { x, z, r ->
            val dx = x - o.x
            val dz = z - o.z
            if (r <= IXION_PLATFORM) return@disc
            val stairs = access.firstOrNull { across(dx, dz, it) <= ACCESS_HALF }
            if (stairs != null && r > WALL_OUTER && r <= ACCESS_LOW_OUTER) {
                // Untere Außenstufe: vom Gelände auf die Stufe der Mauerkrone.
                c.footing(x, z, y - 1, wallPal)
                c.put(x, y, z, Kit.stair(HhaDecoBlocks.TARTARUS_BASALT_BRICK_STAIRS, cardinal(stairs + 180.0)))
                for (h in 1..CLEAR) c.put(x, y + h, z, Kit.AIR)
                return@disc
            }
            if (r <= WALL_OUTER || bastion(dx, dz, r)) c.footing(x, z, y, wallPal)
        }
    }

    /** Zuweg nach Süden: Pflaster, links und rechts eine bündige Glutrinne, Feuerpfosten, die Stele am Weganfang. */
    private fun approach(c: BlockCanvas, o: BlockPos) {
        val from = BlockPos(o.x, o.y, o.z + ROAD_FROM)
        val to = BlockPos(o.x, o.y, o.z + ROAD_TO)
        SiteKit.road(c, from, to, ROAD_WIDTH, roadPal)
        for (side in intArrayOf(-1, 1)) {
            val x = o.x + side * ROAD_CHANNEL
            for (z in o.z + ROAD_TO + 1..o.z + ROAD_FROM) {
                if (!c.inBox(x, z)) continue
                val top = c.ground(x, z) - 1
                c.footing(x, z, top - 1, wallPal)
                c.put(x, top, z, HhaDecoBlocks.EMBER_BASALT.defaultState)
                for (h in 1..SiteKit.ROAD_CLEAR) c.put(x, top + h, z, Kit.AIR)
            }
        }
        SiteKit.firePosts(c, from, to, ROAD_POST)
        val sx = o.x - ROAD_CHANNEL - 2
        val sz = o.z + ROAD_FROM - 1
        if (c.inBox(sx, sz)) c.put(sx, c.ground(sx, sz), sz, stele(3))
    }

    /** Pflaster der Zuwege: Tartaros-Basaltziegel, in Flecken polierter Schwarzstein. */
    private val roadPal: Pal = { x, y, z ->
        when (Kit.noise(x, y, z) % 9) {
            0 -> HhaDecoBlocks.TARTARUS_BASALT.defaultState
            1 -> Blocks.POLISHED_BLACKSTONE.defaultState
            else -> HhaDecoBlocks.TARTARUS_BASALT_BRICKS.defaultState
        }
    }

    // =============================================================================================================
    // Arena-Kern
    // =============================================================================================================

    /**
     * Arena-Kern des Ixion, ohne Geländebezug relativ zu [o]: Plattformboden mit Fugen und Glutrinnen, Randstufe,
     * Mauerkrone, Standfelder, Aufgänge, Prellsteine, Feuerschalen; dann Glutmulde, Stützen, Rad und der Verurteilte.
     */
    fun arenaCore(c: BlockCanvas, o: BlockPos) {
        platform(c, o)
        accesses(c, o)
        bumpers(c, o)
        fires(c, o)
        wheel(c, o)
    }

    /** Boden der Plattform (o.y) und Randstufe/Mauerkrone (o.y + 1), darüber frei. */
    private fun platform(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        c.disc(o.x, o.z, BASTION_OUTER + 0.5) { x, z, r ->
            val dx = x - o.x
            val dz = z - o.z
            val bast = bastion(dx, dz, r)
            if (r > WALL_OUTER && !bast) return@disc
            val channel = r > HOLLOW && r <= TartarusArenas.IXION_OUTER && CHANNEL_ANGLES.any { across(dx, dz, it) < 0.5 }
            val joint = (r > JOINT_RING_INNER && r <= JOINT_RING_OUTER) || JOINT_ANGLES.any { across(dx, dz, it) < 0.5 }
            when {
                r <= HOLLOW -> return@disc
                r <= TartarusArenas.IXION_INNER -> {
                    c.put(x, y, z, if (channel) HhaDecoBlocks.EMBER_BASALT.defaultState else P.TRIM(x, y, z))
                }
                r <= TartarusArenas.IXION_OUTER -> {
                    c.put(x, y, z, when {
                        channel -> HhaDecoBlocks.EMBER_BASALT.defaultState
                        r > CORE_BAND -> HhaDecoBlocks.TARTARUS_BASALT_BRICKS.defaultState
                        joint -> Blocks.POLISHED_BLACKSTONE_BRICKS.defaultState
                        else -> Blocks.POLISHED_BLACKSTONE.defaultState
                    })
                    for (h in 1..CLEAR) c.put(x, y + h, z, Kit.AIR)
                }
                else -> {
                    // Randstufe, Mauerkrone und Standfelder: eine Lage höher.
                    c.put(x, y, z, if (r > IXION_PLATFORM) wallPal(x, y, z) else P.FACE(x, y, z))
                    c.put(x, y + 1, z, when {
                        r <= STEP_NOSE -> HhaDecoBlocks.POLISHED_TARTARUS_BASALT.defaultState
                        r <= IXION_PLATFORM -> HhaDecoBlocks.TARTARUS_BASALT_BRICKS.defaultState
                        else -> HhaDecoBlocks.POLISHED_TARTARUS_BASALT.defaultState
                    })
                    for (h in 2..CLEAR + 1) c.put(x, y + h, z, Kit.AIR)
                }
            }
        }
    }

    /**
     * Aufgänge an den Vertragspunkten (`rim_step.access`): innen eine Stufe vom Kampfring auf die Randstufe (der
     * Vertragspunkt selbst plus der Lauf auf der Stufe, 3 breit), außen über die Mauerkrone hinab.
     */
    private fun accesses(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val spec = TartarusArenas.ixion(o)
        for (p in spec.level("rim_step").access) {
            val deg = angleDeg(p.x - o.x, p.z - o.z)
            val out = cardinal(deg)
            c.put(p.x, p.y, p.z, Kit.stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, out))
            c.disc(o.x, o.z, WALL_OUTER) { x, z, r ->
                if (across(x - o.x, z - o.z, deg) > ACCESS_HALF) return@disc
                when {
                    r > TartarusArenas.IXION_OUTER && r <= ACCESS_INNER_TO ->
                        c.put(x, y + 1, z, Kit.stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, out))
                    r > IXION_PLATFORM ->
                        c.put(x, y + 1, z, Kit.stair(HhaDecoBlocks.TARTARUS_BASALT_BRICK_STAIRS, out.opposite))
                }
            }
        }
    }

    /** Prellsteine (Vertrag `bumper_*`): Basaltziegel, darüber ein Bronzeband, oben eine Deckplatte. */
    private fun bumpers(c: BlockCanvas, o: BlockPos) {
        for (spot in TartarusArenas.ixion(o).cover) {
            val b = spot.box
            for (x in b.minX..b.maxX) for (z in b.minZ..b.maxZ) {
                for (y in b.minY..b.maxY) {
                    c.put(x, y, z, if (y == b.maxY) HhaDecoBlocks.TARTARUS_BRONZE.defaultState else HhaDecoBlocks.TARTARUS_BASALT_BRICKS.defaultState)
                }
                c.put(x, b.maxY + 1, z, Kit.slab(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_SLAB))
            }
        }
    }

    /** Feuerschalen: beidseits jedes Aufgangs auf der Mauerkrone und am äußeren Ende jedes Standfelds. */
    private fun fires(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        fun at(r: Double, deg: Double, side: Double) {
            val a = Math.toRadians(deg)
            val x = floor(o.x + 0.5 + cos(a) * r - sin(a) * side).toInt()
            val z = floor(o.z + 0.5 + sin(a) * r + cos(a) * side).toInt()
            c.brazier(x, y + 2, z, 1)
        }
        for (deg in accessAngles(o)) for (side in doubleArrayOf(-ACCESS_FIRE_SIDE, ACCESS_FIRE_SIDE)) at(FIRE_RING, deg, side)
        for (deg in TartarusArenas.IXION_BUMPER_ANGLES) at(BASTION_FIRE, deg, 0.0)
    }

    /** Glutmulde, Stützen, Rad und der Verurteilte (Bestand; Streben kürzer, Nabe mit Bronzekranz, Beine verbunden). */
    private fun wheel(c: BlockCanvas, o: BlockPos) {
        val pal = P
        val y = o.y
        val hub = y + 18
        val wheel = 15.0
        val bronze: Pal = { _, _, _ -> HhaDecoBlocks.TARTARUS_BRONZE.defaultState }
        // Unter der Nabe eine flache Glutmulde.
        c.disc(o.x, o.z, HOLLOW) { x, z, r ->
            c.put(x, y, z, if (r > HOLLOW_FIRE) pal.TRIM(x, y, z) else Kit.AIR)
            if (r <= HOLLOW_FIRE) c.put(x, y - 1, z, Blocks.MAGMA_BLOCK.defaultState)
        }
        // Zwei Stützen tragen die Achse, schräge Streben halten sie.
        for (side in intArrayOf(-1, 1)) {
            val pz = o.z + side * 6
            c.taper(o.x, pz, y + 1, hub + 1, 3.2, 2.0) { x, yy, z -> if ((yy - y) % 6 == 0) pal.TRIM(x, yy, z) else pal.FACE(x, yy, z) }
            c.limb(o.x - STRUT_FOOT, y + 1, pz, o.x - 1, hub - 3, pz, 1.1, 0.8, pal.TRIM)
            c.limb(o.x + STRUT_FOOT, y + 1, pz, o.x + 1, hub - 3, pz, 1.1, 0.8, pal.TRIM)
            c.prism(o.x - 1, hub - 1, pz - 1, o.x + 1, hub + 1, pz + 1, pal.TRIM)
        }
        c.prism(o.x, hub, o.z - 6, o.x, hub, o.z + 6, pal.TRIM)
        // Das Rad steht in der x-y-Ebene: Reifen, sechs Speichen (zwei fehlen, eine ist verbogen), Nabe aus Glut, darum ein Kranz aus Tartaros-Bronze.
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
                val collar = r >= 2.7 && r < 3.5
                val x = o.x + dx
                val yy = hub + dy
                for (dz in -1..1) {
                    val z = o.z + dz
                    when {
                        hubPart -> c.block(x, yy, z, pal.HUB(x, yy, z))
                        collar -> c.block(x, yy, z, bronze(x, yy, z))
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
        // Beine gespreizt, Block an Block (vorher nur über Eck verbunden).
        for (sx in intArrayOf(-1, 1)) {
            c.block(o.x + sx, hub - 3, fz, body)
            c.block(o.x + sx * 2, hub - 3, fz, body)
            c.block(o.x + sx * 2, hub - 4, fz, body)
            c.block(o.x + sx * 3, hub - 4, fz, body)
        }
        c.block(o.x, hub + 6, fz, Blocks.POLISHED_BASALT.defaultState)
        c.block(o.x, hub + 6, fz - 1, Blocks.MAGMA_BLOCK.defaultState)
        for (sx in intArrayOf(-1, 1)) {
            for (a in 5..8) c.block(o.x + sx * a, hub + 3, fz, Kit.chain(Direction.Axis.X))
            for (h in 1..3) c.block(o.x + sx * 3, hub - 4 - h, fz, Kit.chain())
        }
    }
}
