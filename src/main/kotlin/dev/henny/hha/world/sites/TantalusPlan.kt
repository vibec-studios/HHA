package dev.henny.hha.world.sites

import dev.henny.hha.HhaDecoBlocks
import net.minecraft.block.Blocks
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import dev.henny.hha.world.*
import dev.henny.hha.world.TartarusArenas.TANTALUS_EDGE_OUTER
import dev.henny.hha.world.TartarusArenas.TANTALUS_LEDGE_INNER
import dev.henny.hha.world.TartarusArenas.TANTALUS_LEDGE_OUTER
import dev.henny.hha.world.TartarusArenas.TANTALUS_RIM_OUTER
import dev.henny.hha.world.TartarusPlans.stele
import dev.henny.hha.world.arena.ArenaGeo

/**
 * Bauplan der Strafstätte des Tantalos (Überarbeitung 4, P13). Becken r 16 mit Absatz r 10 … 13 (Kern, o.y − 1),
 * Beckenrand r 13 … 15,8 (o.y) und Rand r 15,8 … 19,2 (o.y + 1) aus dem Vertrag [TartarusArenas.tantalus]. Neu: jede Stufe
 * mit einer Kante aus poliertem Tartaros-Basalt gefasst (Prismarin bleibt), vier Schleusenhäuser um die Räder
 * (`sluice_wheel`) mit gemauerter Rinne ins Becken, Treppen an den Zugängen, Zuweg nach Süden mit den Resten eines
 * trockenen Aquädukts, das auf die Schleuse im Südosten zuläuft. Baum, Früchte und Fels an Ketten bleiben.
 *
 * [draw] zeichnet, was am Gelände hängt (Planum, äußere Stufen, Zuweg, Aquädukt); [arenaCore] das Becken mit allem,
 * was eine feste Höhe relativ zu `o` hat.
 */
internal object TantalusPlan {

    /** Kanten: polierter Basalt auf der Innenseite jeder Stufe, so breit (Blockringe). */
    private const val LEDGE_LIP = 10.8
    private const val RIM_LIP = 13.9
    private const val EDGE_LIP = 16.7

    /** Bis zu diesem Blockring ist die Wand zum Innenbecken aus Prismarin. */
    private const val BASIN_WALL = 11.0

    /** Lichte Höhe über allen Beckenstufen, die der Kern freiräumt (Vertrag: Kern 4 frei ab o.y − 1). */
    private const val CLEAR = 3

    /** Planum und äußere Stufen. */
    private const val PAD = 21.5
    private const val OUTER = 21.0

    /** Zugänge: halbe Breite quer. Feuerschalen am Rand beidseits der Zugänge zum Rand (Abstand quer, Blockring). */
    private const val ACCESS_HALF = 1.5
    private const val FIRE_RING = 17.6
    private const val FIRE_SIDE = 3.5

    /** Zuweg nach Süden (Vertrag: Eingang z + 21,5) und das Aquädukt auf der Ostseite, in Flucht der Schleuse bei 65°. */
    private const val ROAD_FROM = 29
    private const val ROAD_TO = 22
    private const val ROAD_WIDTH = 5
    private const val ROAD_POST = 4.0
    private const val AQUEDUCT_X = 7
    private const val AQUEDUCT_DECK = 5
    private val AQUEDUCT_PIERS = listOf(19..20, 23..24, 27..28)
    private val AQUEDUCT_ARCHES = listOf(17..18, 21..22, 25..26)
    private const val AQUEDUCT_FROM = 16

    private val T get() = Palettes.Tantalus
    private fun s(b: net.minecraft.block.Block) = b.defaultState

    private fun across(dx: Int, dz: Int, deg: Double): Double {
        val a = Math.toRadians(deg)
        val along = dx * cos(a) + dz * sin(a)
        return if (along <= 0.0) Double.MAX_VALUE else abs(-dx * sin(a) + dz * cos(a))
    }

    private fun cardinal(deg: Double): Direction = when ((((deg % 360.0) + 405.0) % 360.0 / 90.0).toInt()) {
        0 -> Direction.EAST
        1 -> Direction.SOUTH
        2 -> Direction.WEST
        else -> Direction.NORTH
    }

    // =============================================================================================================
    // Voller Plan
    // =============================================================================================================

    /** Tantalos: das Wasser weicht zurück, die Früchte hängen knapp außer Reichweite, über ihm schwebt ein Fels an Ketten. */
    fun draw(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        c.pad(o.x, o.z, PAD, y + 1, 4, T.STONE, square = false, clear = 30)
        c.disc(o.x, o.z, OUTER) { x, z, r ->
            c.footing(x, z, y - 1, T.FACE)
            if (r > TANTALUS_EDGE_OUTER) c.put(x, y, z, Kit.stair(Blocks.STONE_BRICK_STAIRS, Kit.inward(x - o.x, z - o.z)))
        }
        approach(c, o)
        arenaCore(c, o)
        aqueduct(c, o)
    }

    /** Zuweg nach Süden mit Feuerpfosten; die Stele am Weganfang. */
    private fun approach(c: BlockCanvas, o: BlockPos) {
        val from = BlockPos(o.x, o.y, o.z + ROAD_FROM)
        val to = BlockPos(o.x, o.y, o.z + ROAD_TO)
        SiteKit.road(c, from, to, ROAD_WIDTH, roadPal)
        SiteKit.firePosts(c, from, to, ROAD_POST)
        val sx = o.x - 5
        val sz = o.z + ROAD_FROM - 1
        if (c.inBox(sx, sz)) c.put(sx, c.ground(sx, sz), sz, stele(4))
    }

    private val roadPal: Pal = { x, y, z ->
        when (Kit.noise(x, y, z) % 9) {
            0 -> s(HhaDecoBlocks.TARTARUS_BASALT)
            1 -> s(Blocks.MOSSY_STONE_BRICKS)
            else -> s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS)
        }
    }

    /**
     * Reste eines trockenen Aquädukts: drei Bögen auf Pfeilern in Flucht der Schleuse bei 65° (x + 7), die Rinne oben
     * führt ins Dach des Schleusenhauses. Der äußere Bogen ist gebrochen: Pfeilerstumpf, Trümmer im Staub.
     */
    private fun aqueduct(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val deck = y + AQUEDUCT_DECK
        val body = T.FACE
        val broken = AQUEDUCT_PIERS.last()
        for (dx in -1..1) {
            val x = o.x + AQUEDUCT_X + dx
            for ((i, piers) in AQUEDUCT_PIERS.withIndex()) for (dz in piers) {
                val z = o.z + dz
                if (!c.inBox(x, z)) continue
                if (piers == broken) {
                    // Stumpf: zwei Lagen über dem Grund, oben gerissen.
                    val g = c.ground(x, z)
                    c.footing(x, z, g, body)
                    c.put(x, g + 1, z, if (Kit.noise(x, 7, z) % 2 == 0) s(Blocks.CRACKED_STONE_BRICKS) else Kit.slab(HhaDecoBlocks.TARTARUS_BASALT_BRICK_SLAB))
                    continue
                }
                c.footing(x, z, deck - 1, body)
                // Sockel und Kämpferband aus Basalt gliedern den Pfeiler.
                c.put(x, maxOf(y, c.ground(x, z)), z, s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS))
                c.put(x, deck - 1, z, s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
                if (i >= 0) c.put(x, deck, z, body(x, deck, z))
            }
            // Bögen: Fahrbahn oben, Konsolen unter der Fahrbahn an beiden Pfeilerseiten; der letzte nur als Stummel.
            for ((i, arch) in AQUEDUCT_ARCHES.withIndex()) {
                val last = i == AQUEDUCT_ARCHES.lastIndex
                for (dz in arch) {
                    if (last && dz != arch.first) continue
                    val z = o.z + dz
                    if (!c.inBox(x, z)) continue
                    if (last && dx == 1) continue
                    c.put(x, deck, z, body(x, deck, z))
                    val towardSouth = dz == arch.last
                    c.put(x, deck - 1, z, Kit.stair(Blocks.STONE_BRICK_STAIRS, if (towardSouth) Direction.SOUTH else Direction.NORTH, top = true))
                    for (h in 1 until AQUEDUCT_DECK - 1) c.put(x, y + h, z, Kit.AIR)
                }
            }
            // Anschluss über dem Schleusenhaus.
            val z0 = o.z + AQUEDUCT_FROM
            if (c.inBox(x, z0)) c.put(x, deck, z0, body(x, deck, z0))
        }
        // Rinne: Brüstungsplatten an beiden Seiten der Fahrbahn bis zum Bruch; Einlauf ins Dach.
        val end = AQUEDUCT_ARCHES.last().first
        for (dz in AQUEDUCT_FROM..end) {
            val z = o.z + dz
            for (dx in intArrayOf(-1, 1)) {
                val x = o.x + AQUEDUCT_X + dx
                if (dz == end && dx == 1) continue
                if (c.inBox(x, z)) c.put(x, deck + 1, z, Kit.slab(HhaDecoBlocks.TARTARUS_BASALT_BRICK_SLAB))
            }
        }
        val ix = o.x + AQUEDUCT_X
        val iz = o.z + AQUEDUCT_FROM
        if (c.inBox(ix, iz)) c.put(ix, deck - 1, iz, s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS))
        // Trümmer des gebrochenen Bogens im Staub.
        for ((dx, dz) in listOf(5 to 26, 8 to 27, 9 to 25, 6 to 29)) {
            val x = o.x + dx
            val z = o.z + dz
            if (!c.inBox(x, z)) continue
            val g = c.ground(x, z)
            c.put(x, g, z, if ((dx + dz) % 2 == 0) Kit.slab(HhaDecoBlocks.TARTARUS_BASALT_BRICK_SLAB) else s(Blocks.CRACKED_STONE_BRICKS))
        }
    }

    // =============================================================================================================
    // Arena-Kern
    // =============================================================================================================

    /** Arena-Kern des Tantalos, ohne Geländebezug relativ zu [o]: Becken mit Kanten, Zugänge, Schleusenhäuser, Baum und Fels. */
    fun arenaCore(c: BlockCanvas, o: BlockPos) {
        basin(c, o)
        accesses(c, o)
        sluices(c, o)
        fires(c, o)
        tree(c, o)
        rock(c, o)
    }

    /** Die Stufen des Beckens: Rand (o.y), Beckenrand (o.y − 1), Absatz (o.y − 2), Innenbecken mit zwei Lagen Wasser. */
    private fun basin(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val lip = s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT)
        c.disc(o.x, o.z, TANTALUS_EDGE_OUTER) { x, z, r ->
            when {
                r > TANTALUS_RIM_OUTER -> {
                    c.put(x, y, z, if (r <= EDGE_LIP) lip else T.FACE(x, y, z))
                    for (h in 1..CLEAR) c.put(x, y + h, z, Kit.AIR)
                    if (r > EDGE_LIP && Kit.noise(x, 5, z) % 6 == 0) c.put(x, y + 1, z, s(Blocks.MOSS_CARPET))
                }
                r > TANTALUS_LEDGE_OUTER -> {
                    // Der oberste Absatz ist längst trocken.
                    c.put(x, y - 1, z, if (r <= RIM_LIP) lip else T.SHELF(x, y - 1, z))
                    for (h in 0..CLEAR) c.put(x, y + h, z, Kit.AIR)
                }
                r > TANTALUS_LEDGE_INNER -> {
                    // Hier stand das Wasser gestern noch.
                    c.put(x, y - 3, z, if (r <= BASIN_WALL) T.EDGE(x, y - 3, z) else T.FACE(x, y - 3, z))
                    c.put(x, y - 2, z, if (r <= LEDGE_LIP) lip else T.BED(x, y - 2, z))
                    for (h in -1..CLEAR) c.put(x, y + h, z, Kit.AIR)
                }
                else -> {
                    c.put(x, y - 4, z, T.BED(x, y - 4, z))
                    c.put(x, y - 3, z, s(Blocks.WATER))
                    c.put(x, y - 2, z, s(Blocks.WATER))
                    for (h in -1..CLEAR) c.put(x, y + h, z, Kit.AIR)
                }
            }
        }
    }

    /**
     * Zugänge an den Vertragspunkten: Stufen vom Absatz auf den Beckenrand und vom Beckenrand auf den Rand, je
     * Vertragspunkt plus die Kante daneben (3 breit, soweit die Zellen nicht im Kern liegen).
     */
    private fun accesses(c: BlockCanvas, o: BlockPos) {
        val spec = TartarusArenas.tantalus(o)
        for ((level, lipFrom, lipTo) in listOf(Triple("rim", TANTALUS_LEDGE_OUTER, RIM_LIP), Triple("edge", TANTALUS_RIM_OUTER, EDGE_LIP))) {
            for (p in spec.level(level).access) {
                val deg = ArenaGeo.angleOf((p.x - o.x).toDouble(), (p.z - o.z).toDouble())
                val out = cardinal(deg)
                val stair = Kit.stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, out)
                c.put(p.x, p.y, p.z, stair)
                c.disc(o.x, o.z, lipTo) { x, z, r ->
                    if (r <= lipFrom || across(x - o.x, z - o.z, deg) > ACCESS_HALF) return@disc
                    c.put(x, p.y, z, stair)
                    c.put(x, p.y + 1, z, Kit.AIR)
                }
            }
        }
    }

    /**
     * Schleusenhäuser um die Räder (Vertrag `sluice_0..3`): 3 breit, 3 hoch, offen zur Mitte; Pfosten aus Basaltsäulen,
     * Rückwand aus Basaltziegeln, Sturz aus poliertem Basalt, darauf eine Seelenlaterne. Vor dem Rad eine gemauerte
     * Rinne (eingetiefte Platten) bis an die Kante des Beckenrands, wo sie auf den Absatz fällt.
     */
    private fun sluices(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        for (d in TartarusArenas.tantalus(o).devices) {
            val w = d.pos
            val f = d.facing
            val side = f.rotateYClockwise()
            val back = w.offset(f.opposite)
            for (k in -1..1) {
                val front = w.offset(side, k)
                val rear = back.offset(side, k)
                for (h in 0..1) {
                    c.put(rear.x, w.y + h, rear.z, DecoPalettes.BASALT_WALL(rear.x, w.y + h, rear.z))
                    if (k != 0) c.put(front.x, w.y + h, front.z, s(HhaDecoBlocks.TARTARUS_BASALT_PILLAR))
                }
                c.put(front.x, w.y + 2, front.z, s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
                c.put(rear.x, w.y + 2, rear.z, s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS))
            }
            c.put(w.x, w.y, w.z, d.block().defaultState.with(Properties.HORIZONTAL_FACING, f))
            c.put(w.x, w.y + 3, w.z, Kit.lantern(hanging = false))
            // Rinne zur Mitte: auf dem Rand (o.y) und dem Beckenrand (o.y − 1) eine eingetiefte Plattenreihe.
            var p = w.offset(f)
            while (true) {
                val r = hypot((p.x - o.x).toDouble(), (p.z - o.z).toDouble())
                if (r <= TANTALUS_LEDGE_OUTER) break
                val top = if (r > TANTALUS_RIM_OUTER) y else y - 1
                c.put(p.x, top, p.z, Kit.slab(HhaDecoBlocks.TARTARUS_BASALT_BRICK_SLAB))
                p = p.offset(f)
            }
        }
    }

    /** Feuerschalen auf dem Rand beidseits der Zugänge zum Rand; keine im Baum. */
    private fun fires(c: BlockCanvas, o: BlockPos) {
        val tree = rel(o, TartarusArenas.TANTALUS_TREE)
        for (p in TartarusArenas.tantalus(o).level("edge").access) {
            val a = Math.toRadians(ArenaGeo.angleOf((p.x - o.x).toDouble(), (p.z - o.z).toDouble()))
            for (side in doubleArrayOf(-FIRE_SIDE, FIRE_SIDE)) {
                val x = floor(o.x + 0.5 + cos(a) * FIRE_RING - sin(a) * side).toInt()
                val z = floor(o.z + 0.5 + sin(a) * FIRE_RING + cos(a) * side).toInt()
                if (x in tree.minX - 2..tree.maxX + 2 && z in tree.minZ - 2..tree.maxZ + 2) continue
                c.brazier(x, o.y + 1, z, 1)
            }
        }
    }

    private fun rel(o: BlockPos, b: BlockBox) = BlockBox(o.x + b.minX, o.y + b.minY, o.z + b.minZ, o.x + b.maxX, o.y + b.maxY, o.z + b.maxZ)

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

    /** Der Obstbaum am Rand neigt sich über das Becken (Bestand); der Stamm ist unten 2 hoch massiv (Deckung). */
    private fun tree(c: BlockCanvas, o: BlockPos) {
        val pal = T
        val y = o.y
        val tx = o.x - 17
        val tz = o.z - 4
        val crowns = listOf(Triple(tx + 12, y + 15, tz + 3), Triple(tx + 8, y + 16, tz - 4), Triple(tx + 6, y + 17, tz + 8), Triple(tx + 17, y + 14, tz + 4), Triple(tx + 3, y + 14, tz + 1))
        for ((cx, cy, cz) in crowns) c.blob(cx, cy, cz, 3.2, 2.2, 3.0, pal.LEAVES)
        c.limb(tx, y, tz, tx + 3, y + 8, tz + 1, 1.4, 1.0, pal.WOOD)
        // Wurzelanlauf: der Stamm steht unten 2 hoch voll (Deckung gegen den Dreizack).
        c.cyl(tx, tz, y + 1, y + 2, 1.5, pal.WOOD)
        val branches = listOf(
            listOf(tx + 3, y + 8, tz + 1, tx + 12, y + 13, tz + 3),
            listOf(tx + 3, y + 8, tz + 1, tx + 8, y + 14, tz - 4),
            listOf(tx + 3, y + 9, tz + 1, tx + 6, y + 15, tz + 8),
            listOf(tx + 12, y + 13, tz + 3, tx + 18, y + 12, tz + 4),
        )
        for ((x1, y1, z1, x2, y2, z2) in branches.map { Branch(it) }) twig(c, x1, y1, z1, x2, y2, z2, pal.WOOD)
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
    }

    /**
     * Dünner Ast als Linie, Block an Block: Wo die Linie in mehr als einer Achse springt, setzt sie Zwischenblöcke, eine
     * Achse nach der anderen. So hängt jedes Aststück am vorigen (vorher hingen die Astenden nur über Eck).
     */
    private fun twig(c: BlockCanvas, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, pal: Pal) {
        val steps = maxOf(abs(x2 - x1), abs(y2 - y1), abs(z2 - z1)).coerceAtLeast(1)
        var px = x1
        var py = y1
        var pz = z1
        c.block(px, py, pz, pal(px, py, pz))
        for (i in 1..steps) {
            val x = x1 + Math.round((x2 - x1) * i.toDouble() / steps).toInt()
            val y = y1 + Math.round((y2 - y1) * i.toDouble() / steps).toInt()
            val z = z1 + Math.round((z2 - z1) * i.toDouble() / steps).toInt()
            if (x != px) { px = x; c.block(px, py, pz, pal(px, py, pz)) }
            if (y != py) { py = y; c.block(px, py, pz, pal(px, py, pz)) }
            if (z != pz) { pz = z; c.block(px, py, pz, pal(px, py, pz)) }
        }
    }

    /** Der Fels hängt an zwei Ketten zwischen zwei Säulen über dem Becken (Säulen = Deckung `column_n`/`_s`). */
    private fun rock(c: BlockCanvas, o: BlockPos) {
        val pal = T
        val y = o.y
        val rx = o.x + 4
        val ry = y + 12
        val rz = o.z + 1
        c.blob(rx, ry, rz, 2.8, 2.4, 2.6, pal.STONE)
        for (side in intArrayOf(-1, 1)) {
            val px = o.x + 12
            val pz = o.z + side * 12
            c.taper(px, pz, y + 1, y + 3, 2.6, 2.0, pal.FACE)
            c.taper(px, pz, y + 4, y + 17, 1.6, 0.9, pal.FACE)
            val chain = Kit.chain(if (abs(pz - rz) >= abs(px - rx)) Direction.Axis.Z else Direction.Axis.X)
            c.heavyChain(px, y + 17, pz, rx, ry + 2, rz + side * 2, 2.0, chain, chain)
            // Säulenkopf nach der Kette, damit die Kette am Stein hängt und der Kopf auf dem Schaft sitzt.
            c.block(px, y + 17, pz, pal.FACE(px, y + 17, pz))
            c.block(px, y + 18, pz, Blocks.CHISELED_STONE_BRICKS.defaultState)
        }
    }
}
