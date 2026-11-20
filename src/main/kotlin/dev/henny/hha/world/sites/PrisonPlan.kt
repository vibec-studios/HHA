package dev.henny.hha.world.sites

import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.world.*
import dev.henny.hha.world.TartarusPlans.PRISON_PIT_DEPTH
import dev.henny.hha.world.TartarusPlans.PRISON_PIT_RADIUS
import dev.henny.hha.world.TartarusPlans.stele
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.enums.WallShape
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bauplan des Titanenkerkers (aus `TartarusPlans` herausgelöst in Überarbeitung 4, P00; umgebaut in P12).
 *
 * Gliederung: [rim] Grubenwand und Rand mit Brüstung, Feuerschalen und Randweg, [ramp] Wendelrampe, [cells] zwei
 * Zellenränge, [arenaCore] Arena der Kampe (Grubenboden, Sims, Titan, Ketten), [pylon] vier Kettenpylone, [crane]
 * Kettenkran als Fernsilhouette, [approach] Zuweg von Süden.
 *
 * Winkel wie im Vertrag: 0° Osten, 90° Süden, 180° Westen, 270° Norden. Die Rampe beginnt im Westen auf Randhöhe und
 * sinkt mit wachsendem Winkel um [RAMP_TURN] je Umdrehung. Ihr zweiter Lauf läuft im Nordbogen eben auf +5 über dem
 * Grubenboden: Das ist der Sims (Vertrag `ledge`); die Umbauten daran gehören zum Arena-Kern ([rampRework]).
 */
internal object PrisonPlan {

    private const val PIT = PRISON_PIT_RADIUS
    private const val RIM = 30.5
    private const val RAMP_INNER = PIT - 3.6
    private const val RAMP_RAIL = PIT - 2.8
    private const val RAMP_TURN = 12

    /** Zweiter Lauf: Treppe von y − 12 auf den Sims (+5) zwischen 180° und 200°, eben bis 30°, danach wie gehabt. */
    private const val STAIR_END = 20.0 / 360.0
    private const val REWORK_END = 7.0 / 12.0
    private const val LEDGE_END = 160.0 / 360.0
    private const val LEDGE_RAIL = 21.5

    /** Schwellen aus Bronze an den Enden des Simses (Winkel). Die östliche enthält den Vertragszugang (344°). */
    private val THRESHOLDS = listOf(200.0 to 203.5, 340.5 to 347.0)
    private const val STAIR_LAMP = 190.0

    /** Brüstung am Grubenrand (Blockabstand), Lücken (Mitte, halbe Breite in Grad) und Randweg Süd → West. */
    private const val RAIL_OUTER = 25.2
    private val RIM_GAPS = listOf(0.0 to 4.0, 90.0 to 4.0, 270.0 to 4.0)
    private const val HEAD_GAP_FROM = 180.0
    private const val HEAD_GAP_TO = 200.0
    private const val PATH_INNER = 25.5
    private const val PATH_OUTER = 27.5
    private const val PATH_FROM = 90.0
    private const val PATH_TO = 197.0

    /** Pylone: Fuß auf r 28, Kopf auf r 24 (Ketten-Joch). Die zwei Kranbeine (Südwest, Nordwest) stehen auf r 32. */
    private const val PYLON_FOOT = 28.0
    private const val CRANE_FOOT = 32.0
    private const val PYLON_HEAD = 24.0
    private val CRANE_LEGS = setOf(1, 2)

    /** Kettenkran: Beine bis +28, Querbalken +29 … +30, Ausleger zur Grubenmitte, Kette bis 6 über dem Rand. */
    private const val CRANE_BEAM = 29
    private const val HOOK_Y = 6

    /** Titan-Fußabdruck (relativ zu o) für den Glutring: x ±10, z −6 … 8; der Ring liegt 2 … 3 Blöcke außen. */
    private const val TITAN_X = 10
    private const val TITAN_Z0 = -6
    private const val TITAN_Z1 = 8

    private fun s(b: Block): BlockState = b.defaultState

    // ---------------------------------------------------------------------------------------------
    // Der Titanenkerker: Hier wachen die Hundertarmigen über die gestürzten Titanen
    // ---------------------------------------------------------------------------------------------

    fun draw(c: BlockCanvas, o: BlockPos) {
        val pal = Palettes.Prison
        c.pad(o.x, o.z, RIM, o.y + 1, 5, pal.RUBBLE, square = false, clear = 26)
        rim(c, o)
        ramp(c, o)
        cells(c, o)
        // Pylone vor dem Arena-Kern: Die schweren Ketten beginnen im Pylonkopf, der Kern darf nicht überschrieben werden
        // (sonst würde die Reparatur beim Kampfbeginn den Plan verändern; PZ1, Wunsch P02 3).
        for (i in 0 until 4) pylon(c, o, i)
        arenaCore(c, o)
        c.block(o.x, o.y + 1, o.z + PIT + 3, stele(1))
        crane(c, o)
        approach(c, o)
    }

    /** Rampen-Parameter einer Spalte: 0 … 1, beginnt im Westen (Nordseite) und wächst im Uhrzeigersinn von oben. */
    private fun rampA(dx: Int, dz: Int): Double = ((atan2(dz.toDouble(), dx.toDouble()) + PI) / (PI * 2.0)) % 1.0

    /** Winkel im Vertragsmaß (0° Osten, 90° Süden) zu einem Rampen-Parameter. */
    private fun angle(a: Double): Double = (a * 360.0 + 180.0) % 360.0

    private fun angleOf(dx: Int, dz: Int): Double = (Math.toDegrees(atan2(dz.toDouble(), dx.toDouble())) + 360.0) % 360.0

    /** Liegt [deg] im Bogen [from] … [to] (beide in 0 … 360, über 0 hinweg erlaubt)? */
    private fun inArc(deg: Double, from: Double, to: Double): Boolean = if (from <= to) deg in from..to else deg >= from || deg <= to

    /**
     * Grubenwand und Rand: Tiefschiefer mit Tuffbändern, oben ein Plattenring mit Band; am Grubenrand eine Brüstung mit
     * vier Lücken (Rampenkopf im Westen, Blicke nach Nord, Ost, Süd) und Feuerschalen; der Randweg führt vom Ende der
     * Straße im Süden unter dem Kranbein hindurch zum Rampenkopf.
     */
    private fun rim(c: BlockCanvas, o: BlockPos) {
        val pal = Palettes.Prison
        val y = o.y
        val bottom = y - PRISON_PIT_DEPTH
        val rail = HashSet<Pair<Int, Int>>()
        c.disc(o.x, o.z, RIM) { x, z, r ->
            if (r <= PIT) {
                for (h in bottom + 1..y + 26) c.put(x, h, z, Kit.AIR)
                c.put(x, bottom, z, pal.PIT(x, bottom, z))
                c.put(x, bottom - 1, z, pal.FACE(x, bottom - 1, z))
                return@disc
            }
            c.footing(x, z, y - 1, pal.FACE)
            for (h in bottom..y - 1) {
                val state = when {
                    r > PIT + 1.5 -> pal.FACE(x, h, z)
                    (y - h) % 8 == 0 -> pal.BAND(x, h, z)
                    Kit.wear(x, h, z, (h - bottom).toDouble() / PRISON_PIT_DEPTH, 1.0) -> pal.WORN(x, h, z)
                    else -> pal.FACE(x, h, z)
                }
                c.put(x, h, z, state)
            }
            val deg = angleOf(x - o.x, z - o.z)
            val path = r > PATH_INNER && r <= PATH_OUTER && inArc(deg, PATH_FROM, PATH_TO)
            c.put(x, y, z, when {
                r > 29.4 -> Kit.stair(Blocks.DEEPSLATE_TILE_STAIRS, Kit.inward(x - o.x, z - o.z))
                path && abs(((deg - PATH_FROM) % 12.0) - 6.0) < 0.9 && r in 26.0..27.0 -> s(HhaDecoBlocks.SOUL_VEIN_BASALT)
                path -> s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS)
                r in 26.6..27.5 -> pal.BAND(x, y, z)
                else -> pal.FLOOR(x, y, z)
            })
            if (r <= RAIL_OUTER && !(deg > HEAD_GAP_FROM && deg <= HEAD_GAP_TO) && RIM_GAPS.none { (m, w) -> abs(((deg - m + 540.0) % 360.0) - 180.0) <= w }) {
                rail.add(x to z)
            }
        }
        c.wallRun(rail, y + 1, Blocks.DEEPSLATE_BRICK_WALL)
        // Feuerschalen an den Lücken.
        for ((m, w) in RIM_GAPS) for (side in intArrayOf(-1, 1)) brazierAt(c, o, m + side * (w + 3.0))
        brazierAt(c, o, HEAD_GAP_FROM - 3.0)
        brazierAt(c, o, HEAD_GAP_TO + 3.0)
    }

    private fun brazierAt(c: BlockCanvas, o: BlockPos, deg: Double) {
        val a = Math.toRadians(deg)
        c.brazier(o.x + (cos(a) * 24.8).roundToInt(), o.y + 1, o.z + (sin(a) * 24.8).roundToInt())
    }

    /**
     * Wendelrampe an der Grubenwand (r 20,4 … 24): eine Umdrehung sinkt sie um zwölf Blöcke, innen eine lose Brüstung.
     * Der Kopf liegt im Westen; die Zeile genau westlich (Naht) gehört zum oberen Lauf und trägt eine Brüstung.
     */
    private fun ramp(c: BlockCanvas, o: BlockPos) {
        val pal = Palettes.Prison
        val y = o.y
        val bottom = y - PRISON_PIT_DEPTH
        c.disc(o.x, o.z, PIT.toDouble()) { x, z, r ->
            if (r < RAMP_INNER) return@disc
            val a = rampA(x - o.x, z - o.z)
            var h = y - (a * RAMP_TURN).toInt()
            var turn = 0
            while (h > bottom) {
                c.put(x, h, z, if ((x + z + turn) and 7 == 0) s(Blocks.TUFF_BRICKS) else pal.FLOOR(x, h, z))
                if (r < RAMP_RAIL && (x + z) and 1 == 0) c.put(x, h + 1, z, s(Blocks.DEEPSLATE_BRICK_WALL))
                h -= RAMP_TURN
                turn++
            }
        }
        // Brüstung über der Naht: oben endet der Lauf, darunter liegt sein Ende elf Blöcke tiefer.
        val seam = HashSet<Pair<Int, Int>>()
        for (x in o.x - PIT..o.x - RAMP_INNER.toInt() - 1) seam.add(x to o.z)
        c.wallRun(seam, y + 1, Blocks.DEEPSLATE_BRICK_WALL)
    }

    private fun cellAngle(tier: Int, i: Int): Double = i * PI / 5.0 + tier * PI / 10.0 + 0.13

    /**
     * Die Gitterfronten beider Zellenränge (Position → Zustand, Luft für Türen und Ausbrüche). Gemeinsam für den vollen
     * Plan und für [rampRework], damit der Umbau der Rampe keine Front anschneidet.
     */
    private fun cellFronts(o: BlockPos): LinkedHashMap<Long, BlockState> {
        val pal = Palettes.Prison
        val bottom = o.y - PRISON_PIT_DEPTH
        val out = LinkedHashMap<Long, BlockState>()
        for (tier in 0..1) {
            val floor = bottom + 1 + tier * 11
            for (i in 0 until 10) {
                val a = cellAngle(tier, i)
                val fx = o.x + (cos(a) * PIT).toInt()
                val fz = o.z + (sin(a) * PIT).toInt()
                val alongX = abs(sin(a)) > abs(cos(a))
                val broken = (i + tier) % 4 == 1
                for (w in -2..2) {
                    for (h in 0..4) {
                        val bx = if (alongX) fx + w else fx
                        val bz = if (alongX) fz else fz + w
                        val gap = w == 0 && h <= 1 || broken && w in -1..0 && h <= 3
                        out[BlockPos.asLong(bx, floor + h, bz)] = if (gap) Kit.AIR else if (h == 4 || abs(w) == 2) pal.BAND(bx, floor + h, bz) else Kit.bars(alongX)
                    }
                }
            }
        }
        return out
    }

    /** Zwei Reihen Zellen in der Grubenwand: Bogen, Eisengitter (manche ausgebrochen), drinnen Knochen. */
    private fun cells(c: BlockCanvas, o: BlockPos) {
        val pal = Palettes.Prison
        val bottom = o.y - PRISON_PIT_DEPTH
        for (tier in 0..1) {
            val floor = bottom + 1 + tier * 11
            for (i in 0 until 10) {
                val a = cellAngle(tier, i)
                val cx = o.x + (cos(a) * (PIT + 3.5)).toInt()
                val cz = o.z + (sin(a) * (PIT + 3.5)).toInt()
                c.prism(cx - 2, floor, cz - 2, cx + 2, floor + 4, cz + 2) { _, _, _ -> Kit.AIR }
                c.prism(cx - 2, floor - 1, cz - 2, cx + 2, floor - 1, cz + 2, pal.FLOOR)
            }
        }
        for ((k, state) in cellFronts(o)) c.block(BlockPos.unpackLongX(k), BlockPos.unpackLongY(k), BlockPos.unpackLongZ(k), state)
        for (tier in 0..1) {
            val floor = bottom + 1 + tier * 11
            for (i in 0 until 10) {
                val a = cellAngle(tier, i)
                val cx = o.x + (cos(a) * (PIT + 3.5)).toInt()
                val cz = o.z + (sin(a) * (PIT + 3.5)).toInt()
                c.block(cx, floor, cz, if (i % 3 == 0) s(Blocks.BONE_BLOCK) else s(Blocks.SOUL_SOIL))
                c.block(cx + 1, floor, cz, skull(i * 3))
                c.block(cx - 1, floor + 4, cz + 1, Kit.chain())
                c.block(cx - 1, floor + 3, cz + 1, Kit.lantern())
                if ((i + tier * 3) % 10 == 2) c.chest(cx + 1, floor, cz + 1)
            }
        }
    }

    /**
     * Kettenpylon [i] (45° + 90° · i), nach innen geneigt; am Kopf (r 24) das Kettenjoch, an dem die schwere Kette zum
     * Handgelenk des Titanen hängt. Die zwei westlichen sind die Beine des Kettenkrans: Fuß weiter außen (r 32), damit der
     * Randweg unter der Neigung hindurchführt, und ein senkrechter Schaft bis unter den Querbalken.
     */
    private fun pylon(c: BlockCanvas, o: BlockPos, i: Int) {
        val pal = Palettes.Prison
        val y = o.y
        val crane = i in CRANE_LEGS
        val a = PI / 4.0 + i * PI / 2.0
        val foot = if (crane) CRANE_FOOT else PYLON_FOOT
        val bx = o.x + Math.round(cos(a) * foot).toInt()
        val bz = o.z + Math.round(sin(a) * foot).toInt()
        val tx = o.x + Math.round(cos(a) * PYLON_HEAD).toInt()
        val tz = o.z + Math.round(sin(a) * PYLON_HEAD).toInt()
        val pylonPal: Pal = { x, yy, z -> if ((yy - y) % 4 == 0) pal.BAND(x, yy, z) else if (Kit.wear(x, yy, z, (yy - y) / 14.0, 1.0)) pal.WORN(x, yy, z) else pal.FACE(x, yy, z) }
        c.disc(bx, bz, 4.2) { x, z, _ -> c.footing(x, z, y, pal.PLINTH) }
        c.taper(bx, bz, y + 1, y + 4, 4.2, 3.0, pal.PLINTH)
        c.limb(bx, y + 1, bz, tx, y + 12, tz, 2.9, 1.9, pylonPal)
        if (crane) {
            c.taper(bx, bz, y + 5, y + CRANE_BEAM - 1, 2.4, 1.7, pylonPal)
            c.cyl(bx, bz, y + 16, y + 16, 2.2, DecoPalettes.DARK_BRONZE)
        }
        // Der Kopf: ein Kettenjoch aus Tuff mit Eisenringen, darauf eine Spitze.
        c.prism(tx - 2, y + 12, tz - 2, tx + 2, y + 13, tz + 2) { x, _, z -> if (abs(x - tx) == 2 && abs(z - tz) == 2) Kit.AIR else pal.LINK_A }
        c.prism(tx - 1, y + 14, tz - 1, tx + 1, y + 14, tz + 1) { _, _, _ -> s(Blocks.TUFF_BRICKS) }
        c.block(tx, y + 15, tz, s(Blocks.CHISELED_TUFF_BRICKS))
        for (d in Direction.Type.HORIZONTAL) c.block(tx + d.offsetX * 3, y + 12, tz + d.offsetZ * 3, Kit.bars(d.axis == Direction.Axis.Z))
        val ix = o.x + Math.round(cos(a) * 21.0).toInt()
        val iz = o.z + Math.round(sin(a) * 21.0).toInt()
        for (h in 10..11) c.block(ix, y + h, iz, Kit.chain())
        c.block(ix, y + 9, iz, Kit.lantern())
    }

    /**
     * Kettenkran (Fernsilhouette): Querbalken über den zwei Kranbeinen im Westen, genau über dem Rampenkopf; von seiner
     * Mitte ein Ausleger bis über die Grubenmitte, dort ein Rollenkasten, eine Kette und ein Haken, der 6 über dem Rand
     * endet.
     */
    private fun crane(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val legX = o.x + Math.round(cos(PI * 0.75) * CRANE_FOOT).toInt()
        val legZ = Math.round(sin(PI * 0.75) * CRANE_FOOT).toInt()
        val top = y + CRANE_BEAM
        val beam: Pal = { x, yy, z -> if (yy == top && Math.floorMod(z - o.z, 6) == 0) s(HhaDecoBlocks.TARTARUS_BRONZE) else s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS) }
        // Querbalken über beide Beine (3 breit, 2 hoch), Enden mit Deckstein.
        c.prism(legX - 1, top, o.z - legZ - 1, legX + 1, top + 1, o.z + legZ + 1, beam)
        for (sz in intArrayOf(-1, 1)) c.prism(legX - 1, top + 2, o.z + sz * legZ - 1, legX + 1, top + 2, o.z + sz * legZ + 1) { _, _, _ -> s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT) }
        // Ausleger zur Grubenmitte, unten am Balken zwei Kragsteine.
        c.prism(legX + 2, top, o.z - 1, o.x + 1, top + 1, o.z + 1) { x, yy, z ->
            if (yy == top && z == o.z && Math.floorMod(x - o.x, 5) == 0) s(HhaDecoBlocks.TARTARUS_BRONZE) else s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS)
        }
        for (z in o.z - 1..o.z + 1) c.block(legX + 2, top - 1, z, Kit.stair(HhaDecoBlocks.TARTARUS_BASALT_BRICK_STAIRS, Direction.WEST, top = true))
        // Rollenkasten über der Mitte.
        c.prism(o.x - 1, top - 1, o.z - 1, o.x + 1, top + 2, o.z + 1) { _, yy, _ -> if (yy == top) s(HhaDecoBlocks.TARTARUS_BRONZE) else s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT) }
        // Kette und Haken.
        for (h in y + HOOK_Y + 1 until top - 1) c.block(o.x, h, o.z, Kit.chain())
        for (dx in -1..1) c.block(o.x + dx, y + HOOK_Y, o.z, Kit.chain(Direction.Axis.X))
        for (dx in intArrayOf(-1, 1)) c.block(o.x + dx, y + HOOK_Y + 1, o.z, Kit.chain())
    }

    /**
     * Zuweg (PLAN 5.3): Straße 5 breit vom Südrand des Kastens bis an den Grubenrand, Feuerpfosten; über den Rand gepflastert
     * bis zur Stele. Von dort führt der Randweg (in [rim]) unter dem Kranbein hindurch zum Rampenkopf.
     */
    private fun approach(c: BlockCanvas, o: BlockPos) {
        val from = BlockPos(o.x, o.y, o.z + TartarusKind.PRISON.reach)
        val to = BlockPos(o.x, o.y, o.z + RIM.toInt() + 1)
        val road: Pal = { x, y, z ->
            when (Kit.noise(x, y, z) % 7) {
                0 -> s(Blocks.COBBLED_DEEPSLATE)
                1 -> s(HhaDecoBlocks.TARTARUS_BASALT)
                else -> s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS)
            }
        }
        SiteKit.road(c, from, to, 5, road)
        SiteKit.firePosts(c, from, to, 4.0)
        for (dx in -2..2) for (z in o.z + PIT + 4..o.z + RIM.toInt()) c.block(o.x + dx, o.y, z, road(o.x + dx, o.y, z))
    }

    // ---------------------------------------------------------------------------------------------
    // Arena-Kern der Kampe (PLAN 3.7): ohne Geländebezug, relativ zu [o]; Maße aus `TartarusArenas.prison`
    // ---------------------------------------------------------------------------------------------

    /**
     * Arena-Kern: Grubenboden (Kern r 20 frei), Glutring um den Titan, vier Schloss-Sockel; der Sims im Nordbogen samt
     * Treppe von der Rampe, Brüstung, Bronzeschwellen und Kragsteinen; der Titan; die vier schweren Ketten (Teile
     * `chain_0 … 3`, Glied an Glied, je für sich entfernbar) und von jedem Schloss eine Kette zu Handgelenk bzw. Schulter.
     */
    fun arenaCore(c: BlockCanvas, o: BlockPos) {
        val ty = TartarusArenas.prisonFloor(o)
        pitFloor(c, o)
        rampRework(c, o)
        clearOldChains(c, o)
        titan(c, o.x, ty, o.z)
        for (i in 0 until 4) pylonChain(c, o, i)
        for (i in 0 until 4) lockChain(c, o, i)
    }

    /**
     * Grubenboden: im Kern polierter Tartaros-Basalt (ruhige Fläche), darüber 4 frei; ein Ring aus Glutbasalt 2 … 3 Blöcke
     * um den Fußabdruck des Titanen; vier Schloss-Sockel (3 × 3, Bronzering 5 × 5) im Boden; ein Pfeil aus Seelenader vom
     * Fuß der Rampe zur Mitte. Unter der Rampe (r > 20) Schutt, Seelenerde und Glutnester an der Wand.
     */
    private fun pitFloor(c: BlockCanvas, o: BlockPos) {
        val pal = Palettes.Prison
        val bottom = o.y - PRISON_PIT_DEPTH
        val core = TartarusArenas.PRISON_CORE
        val floorPal = DecoPalettes.basaltFloor(0.06)
        c.disc(o.x, o.z, PIT.toDouble()) { x, z, r ->
            val dx = x - o.x
            val dz = z - o.z
            c.put(x, bottom - 1, z, pal.FACE(x, bottom - 1, z))
            val ex = maxOf(0, abs(dx) - TITAN_X)
            val ez = maxOf(0, dz - TITAN_Z1, TITAN_Z0 - dz)
            val edge = hypot(ex.toDouble(), ez.toDouble())
            c.put(x, bottom, z, when {
                r > core -> if (Kit.noise(x, 5, z) % 11 == 0) pal.GLOW else pal.PIT(x, bottom, z)
                edge > 2.0 && edge <= 3.0 -> s(HhaDecoBlocks.EMBER_BASALT)
                else -> floorPal(x, bottom, z)
            })
            if (r <= core) for (h in 1..4) c.put(x, bottom + h, z, Kit.AIR)
        }
        for (i in 0 until 4) {
            val lock = BlockPos.ofFloored(TartarusArenas.prisonLock(o, i))
            for (dx in -2..2) for (dz in -2..2) {
                val ring = maxOf(abs(dx), abs(dz))
                c.block(lock.x + dx, bottom, lock.z + dz, when (ring) {
                    0 -> s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT)
                    1 -> s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT)
                    else -> s(HhaDecoBlocks.TARTARUS_BRONZE)
                })
            }
        }
        // Pfeil (Winkel) am Fuß der Rampe im Westen, Spitze zur Mitte.
        for ((ax, az) in listOf(-15 to 5, -16 to 4, -16 to 6, -17 to 3, -17 to 7)) c.block(o.x + ax, bottom, o.z + az, s(HhaDecoBlocks.SOUL_VEIN_BASALT))
    }

    /**
     * Umbau des zweiten Rampenlaufs (Rampen-Parameter 0 … 7/12, also 180° → 30°): Die alte Lage wird geräumt, dann läuft
     * die Rampe als Treppe von y − 12 bis auf den Sims (+5, Boden y − 19) und eben weiter bis 30°, wo sie die alte Lage
     * wieder trifft. Im Simsbogen (200° … 340°) trägt der Innenring eine durchgehende Brüstung, die Simskante ist Bronze;
     * an beiden Enden liegt eine Bronzeschwelle (Stufe oben), die östliche ist der Vertragszugang. Gitterfronten der Zellen
     * bleiben unangetastet. So ist der Sims in alten und neuen Welten gleich und von oben wie unten erreichbar.
     */
    private fun rampRework(c: BlockCanvas, o: BlockPos) {
        val pal = Palettes.Prison
        val y = o.y
        val ledgeFloor = TartarusArenas.prisonFloor(o) + TartarusArenas.PRISON_LEDGE_HEIGHT - 1
        val fronts = cellFronts(o)
        val floorPal = DecoPalettes.basaltFloor(0.04)
        val rail = HashSet<Pair<Int, Int>>()
        fun clear(x: Int, h: Int, z: Int) {
            if (BlockPos.asLong(x, h, z) !in fronts) c.put(x, h, z, Kit.AIR)
        }
        c.disc(o.x, o.z, PIT + 0.5) { x, z, r ->
            if (r < RAMP_INNER) return@disc
            val dx = x - o.x
            val dz = z - o.z
            val a = rampA(dx, dz)
            if (a >= REWORK_END) return@disc
            val deg = angle(a)
            val wall = r > PIT
            val old = y - RAMP_TURN - (a * RAMP_TURN).toInt()
            val stair = a < STAIR_END
            val h = if (stair) y - RAMP_TURN - floor(a / STAIR_END * (y - RAMP_TURN - ledgeFloor)).toInt() else ledgeFloor
            val ledge = !stair && a <= LEDGE_END
            // Alte Lage samt Brüstung räumen (nur auf der Rampe, nicht in der Wand).
            if (!wall) {
                clear(x, old, z)
                clear(x, old + 1, z)
            } else if (!ledge) {
                return@disc
            }
            for (k in 1..3) clear(x, h + k, z)
            val threshold = THRESHOLDS.any { (f, t) -> deg >= f && deg < t }
            val state = when {
                threshold -> Kit.slab(HhaDecoBlocks.TARTARUS_BRONZE_SLAB, top = true)
                stair -> {
                    // Bergauf = kleinerer Parameter: Tangente (sin θ, −cos θ).
                    val th = atan2(dz.toDouble(), dx.toDouble())
                    val ux = sin(th)
                    val uz = -cos(th)
                    Kit.stair(Blocks.DEEPSLATE_TILE_STAIRS, if (abs(ux) > abs(uz)) (if (ux > 0) Direction.EAST else Direction.WEST) else (if (uz > 0) Direction.SOUTH else Direction.NORTH))
                }
                ledge && r <= LEDGE_RAIL -> s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS)
                ledge && r <= LEDGE_RAIL + 1.0 -> s(HhaDecoBlocks.TARTARUS_BRONZE)
                ledge -> floorPal(x, h, z)
                (x + z) and 7 == 0 -> s(Blocks.TUFF_BRICKS)
                else -> pal.FLOOR(x, h, z)
            }
            c.put(x, h, z, state)
            if (stair) c.put(x, h - 1, z, pal.FLOOR(x, h - 1, z))
            when {
                ledge && r <= LEDGE_RAIL && !threshold -> {
                    rail.add(x to z)
                    // Kragstein unter der Simskante.
                    if (Math.floorMod(x + 2 * z, 4) == 0) c.put(x, h - 1, z, Kit.stair(HhaDecoBlocks.TARTARUS_BASALT_BRICK_STAIRS, Kit.inward(dx, dz).opposite, top = true))
                }
                !ledge && r < RAMP_RAIL && (x + z) and 1 == 0 -> {
                    c.put(x, h + 1, z, s(Blocks.DEEPSLATE_BRICK_WALL))
                    // Licht an der Treppe: Laterne auf dem Brüstungspfosten.
                    if (stair && abs(deg - STAIR_LAMP) < 2.5) c.put(x, h + 2, z, Kit.lantern(hanging = false, soul = false))
                }
            }
        }
        c.wallRun(rail, ledgeFloor + 1, HhaDecoBlocks.TARTARUS_BASALT_BRICK_WALL)
        // Licht: Laternen auf der Simsbrüstung alle 20°.
        for ((x, z) in rail) {
            val deg = angleOf(x - o.x, z - o.z)
            if (abs(((deg - 200.0) % 20.0) - 10.0) < 1.6) c.put(x, ledgeFloor + 2, z, Kit.lantern(hanging = false, soul = true))
        }
    }

    /** Kopf und Handgelenk der schweren Kette [i] (wie im Vertrag `prisonChain`). */
    private fun chainEnds(o: BlockPos, i: Int): Pair<BlockPos, BlockPos> {
        val a = PI / 4.0 + i * PI / 2.0
        val head = BlockPos(o.x + Math.round(cos(a) * PYLON_HEAD).toInt(), o.y + 11, o.z + Math.round(sin(a) * PYLON_HEAD).toInt())
        val side = if (cos(a) > 0) 1 else -1
        val wrist = BlockPos(o.x + side * 20, TartarusArenas.prisonFloor(o) + 23, o.z - 3)
        return head to wrist
    }

    /**
     * Räumt die Ketten des alten Plans (diagonal gezogen, teils ohne Flächenkontakt): die vier schweren Ketten und die
     * zwei Stummel vom Handgelenk schräg nach oben (endeten frei in der Luft). Die Enden am Joch und am Handgelenk
     * bleiben (dort setzt der neue Plan an).
     */
    private fun clearOldChains(c: BlockCanvas, o: BlockPos) {
        val ty = TartarusArenas.prisonFloor(o)
        val runs = ArrayList<Triple<BlockPos, BlockPos, Pair<Double, Boolean>>>()
        for (i in 0 until 4) {
            val (head, wrist) = chainEnds(o, i)
            runs.add(Triple(head, wrist, 2.0 to true))
        }
        for (side in intArrayOf(-1, 1)) runs.add(Triple(BlockPos(o.x + side * 20, ty + 24, o.z - 3), BlockPos(o.x + side * 26, ty + 29, o.z - 3), 1.0 to false))
        for ((a, b, opt) in runs) {
            for (p in oldChainCells(a, b, opt.first, opt.second)) {
                if (p.getManhattanDistance(a) <= 3 || p.getManhattanDistance(b) <= 2) continue
                c.block(p.x, p.y, p.z, Kit.AIR)
            }
        }
    }

    /** Die Zellen, die der alte `heavyChain` gesetzt hat (gleiche Rechnung), für [clearOldChains]. */
    private fun oldChainCells(a: BlockPos, b: BlockPos, sag: Double, thick: Boolean): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        val length = maxOf(abs(b.x - a.x), abs(b.y - a.y), abs(b.z - a.z)).coerceAtLeast(1)
        val acrossX = abs(b.z - a.z) >= abs(b.x - a.x)
        var lastY = Int.MIN_VALUE
        for (i in 0..length) {
            val t = i.toDouble() / length
            val x = a.x + (b.x - a.x) * i / length
            val z = a.z + (b.z - a.z) * i / length
            val yy = a.y + ((b.y - a.y) * t - 4.0 * t * (1.0 - t) * sag).roundToInt()
            if (lastY != Int.MIN_VALUE) for (f in minOf(lastY, yy) + 1 until maxOf(lastY, yy)) out.add(BlockPos(x, f, z))
            out.add(BlockPos(x, yy, z))
            if (thick && i % 2 == 0) {
                if (acrossX) {
                    out.add(BlockPos(x - 1, yy, z))
                    out.add(BlockPos(x + 1, yy, z))
                } else {
                    out.add(BlockPos(x, yy, z - 1))
                    out.add(BlockPos(x, yy, z + 1))
                }
            }
            lastY = yy
        }
        return out
    }

    /** Schwere Kette [i] vom Joch zum Handgelenk: Glied an Glied mit Wangen, Durchhang 2 (Teil `chain_i`). */
    private fun pylonChain(c: BlockCanvas, o: BlockPos, i: Int) {
        val pal = Palettes.Prison
        val (head, wrist) = chainEnds(o, i)
        c.links(head, wrist, 2.0, thick = true) { k, _, _ -> if (k % 2 == 0) pal.LINK_A else pal.LINK_B }
    }

    /**
     * Kette vom Schloss [i] zum Titanen: Die zwei südlichen Schlösser halten die Handgelenke, die zwei nördlichen die
     * Schultern am Hals. Sie beginnt am Bronzering neben dem Sockel (der Würfel über dem Sockel bleibt frei); die
     * untersten vier Lagen über dem Boden sind Eisenkette (zählen im Kern als frei), darüber schwere Glieder.
     */
    private fun lockChain(c: BlockCanvas, o: BlockPos, i: Int) {
        val pal = Palettes.Prison
        val ty = TartarusArenas.prisonFloor(o)
        val lock = BlockPos.ofFloored(TartarusArenas.prisonLock(o, i))
        val deg = TartarusArenas.PRISON_LOCK_ANGLES[i]
        val side = if (cos(Math.toRadians(deg)) > 0) 1 else -1
        val south = sin(Math.toRadians(deg)) > 0
        val target = if (south) BlockPos(o.x + side * 19, ty + 20, o.z - 3) else BlockPos(o.x + side * 9, ty + 20, o.z - 4)
        val vx = (target.x - lock.x).toDouble()
        val vz = (target.z - lock.z).toDouble()
        val len = sqrt(vx * vx + vz * vz)
        val start = BlockPos(lock.x + (vx / len * 2.0).roundToInt(), ty, lock.z + (vz / len * 2.0).roundToInt())
        c.links(start, target, 0.0, thick = false) { k, p, axis -> if (p.y <= ty + 3) Kit.chain(axis) else if (k % 2 == 0) pal.LINK_A else pal.LINK_B }
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
                if (abs(dx) > 1) c.block(x + dx, y + 18 + i * 3, z - 6, s(Blocks.BONE_BLOCK).with(Properties.AXIS, Direction.Axis.X))
            }
        }
        // Kopf hängt auf die Brust; die Augen glühen noch.
        c.blob(x, y + 24, z - 4, 3.8, 4.4, 3.8, skin)
        c.block(x - 2, y + 24, z - 8, pal.GLOW)
        c.block(x + 2, y + 24, z - 8, pal.GLOW)
        c.block(x, y + 21, z - 7, s(Blocks.BONE_BLOCK))
        // Schultern und nach außen gerissene Arme mit schweren Handschellen (die Ketten setzt [arenaCore]).
        for (side in intArrayOf(-1, 1)) {
            c.blob(x + side * 9, y + 21, z - 1, 3.8, 3.4, 3.8, skin)
            c.limb(x + side * 9, y + 21, z - 1, x + side * 17, y + 19, z - 2, 2.6, 2.2, skin)
            c.limb(x + side * 17, y + 19, z - 2, x + side * 20, y + 23, z - 3, 2.2, 1.8, skin)
            c.cyl(x + side * 19, z - 3, y + 21, y + 22, 2.4) { _, _, _ -> pal.LINK_A }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Helfer
    // ---------------------------------------------------------------------------------------------

    /**
     * Kette von [a] nach [b] mit Durchhang [sag], Glied an Glied (jeder Schritt ändert nur eine Achse, so hat jedes Glied
     * Flächenkontakt zum nächsten). [thick]: an jedem zweiten Glied Wangen quer zur Kette. [pick] bekommt den Zähler, die
     * Stelle und die Achse des Schritts.
     */
    private fun BlockCanvas.links(a: BlockPos, b: BlockPos, sag: Double, thick: Boolean, pick: (Int, BlockPos, Direction.Axis) -> BlockState) {
        val acrossX = abs(b.z - a.z) >= abs(b.x - a.x)
        val samples = maxOf(abs(b.x - a.x), abs(b.y - a.y), abs(b.z - a.z)).coerceAtLeast(1) * 4
        var cx = a.x
        var cy = a.y
        var cz = a.z
        var k = 0
        fun place(axis: Direction.Axis) {
            val p = BlockPos(cx, cy, cz)
            block(cx, cy, cz, pick(k, p, axis))
            if (thick && k % 2 == 0 && axis != Direction.Axis.Y) {
                if (acrossX) {
                    block(cx - 1, cy, cz, pick(0, p, axis))
                    block(cx + 1, cy, cz, pick(0, p, axis))
                } else {
                    block(cx, cy, cz - 1, pick(0, p, axis))
                    block(cx, cy, cz + 1, pick(0, p, axis))
                }
            }
            k++
        }
        place(Direction.Axis.Y)
        for (i in 1..samples) {
            val t = i.toDouble() / samples
            val tx = Math.round(a.x + (b.x - a.x) * t).toInt()
            val ty = Math.round(a.y + (b.y - a.y) * t - 4.0 * t * (1.0 - t) * sag).toInt()
            val tz = Math.round(a.z + (b.z - a.z) * t).toInt()
            while (cx != tx || cy != ty || cz != tz) {
                val ex = abs(tx - cx)
                val ey = abs(ty - cy)
                val ez = abs(tz - cz)
                when {
                    ey >= ex && ey >= ez -> { cy += Integer.signum(ty - cy); place(Direction.Axis.Y) }
                    ex >= ez -> { cx += Integer.signum(tx - cx); place(Direction.Axis.X) }
                    else -> { cz += Integer.signum(tz - cz); place(Direction.Axis.Z) }
                }
            }
        }
    }

    /** Mauerreihe auf Höhe [y]: Nachbarn in [cells] werden verbunden, gerade Stücke ohne Pfosten. */
    private fun BlockCanvas.wallRun(cells: Set<Pair<Int, Int>>, y: Int, wallBlock: Block) {
        for ((x, z) in cells) {
            val n = (x to z - 1) in cells
            val sd = (x to z + 1) in cells
            val e = (x + 1 to z) in cells
            val w = (x - 1 to z) in cells
            var state = wallBlock.defaultState
                .with(Properties.NORTH_WALL_SHAPE, if (n) WallShape.LOW else WallShape.NONE)
                .with(Properties.SOUTH_WALL_SHAPE, if (sd) WallShape.LOW else WallShape.NONE)
                .with(Properties.EAST_WALL_SHAPE, if (e) WallShape.LOW else WallShape.NONE)
                .with(Properties.WEST_WALL_SHAPE, if (w) WallShape.LOW else WallShape.NONE)
            val straight = (n && sd && !e && !w) || (e && w && !n && !sd)
            state = state.with(Properties.UP, !straight)
            this.block(x, y, z, state)
        }
    }
}
