package dev.henny.hha.world.olymp.isles

import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.world.DecoPalettes
import dev.henny.hha.world.Kit
import dev.henny.hha.world.block
import dev.henny.hha.world.brazier as greenBrazier
import dev.henny.hha.world.arena.ArenaGeo
import dev.henny.hha.world.arena.CoverSpot
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.WallBlock
import net.minecraft.block.enums.WallShape
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import dev.henny.hha.world.olymp.*
import dev.henny.hha.world.olymp.OlympKit.altar
import dev.henny.hha.world.olymp.OlympKit.angleDiff
import dev.henny.hha.world.olymp.OlympKit.angleOf
import dev.henny.hha.world.olymp.OlympKit.column
import dev.henny.hha.world.olymp.OlympKit.griffinColumn
import dev.henny.hha.world.olymp.OlympKit.lanternPost
import dev.henny.hha.world.olymp.OlympKit.path
import dev.henny.hha.world.olymp.OlympKit.polar
import dev.henny.hha.world.olymp.OlympKit.rimBalustrade
import dev.henny.hha.world.olymp.OlympKit.s
import dev.henny.hha.world.olymp.OlympKit.slab
import dev.henny.hha.world.olymp.OlympKit.stair
import dev.henny.hha.world.olymp.OlympKit.stele
import dev.henny.hha.world.olymp.OlympKit.torchPillar
import dev.henny.hha.world.olymp.OlympKit.wall
import dev.henny.hha.world.olymp.OlympusPlans.bridgeHeads

/**
 * Bauplan der Insel C, Dreiweg der Hekate (Überarbeitung 4, P07). Aufruf über `OlympusPlans.draw`.
 *
 * Aus der leeren Scheibe wird ein Ort mit Schwellen: drei Tore über den drei Wegen, drei Fackeltürme, drei Terrassen und
 * in der Mitte das Hekataion als Wahrzeichen. Alle Maße kommen aus dem Vertrag [OlympusArenas.C] (Deckung, Terrassen,
 * Treppen, Fallgitter-Ebenen); diese Datei zeichnet nur hinein. Die Teile in Zeichenreihenfolge:
 *
 * 1. Inselkörper, Brückenköpfe (P06), Platzboden mit Ringbändern, Wegen, Strophalos und den gestuften Bändern zum
 *    Höllentor ([plazaFloor], [nwBand]);
 * 2. Umgang und Wege über den Platz hinaus ([ringWalk], [outerPaths]);
 * 3. drei Terrassen samt Treppe, Feuerschalen, Bank bzw. Stele und Zypressen ([terrace]);
 * 4. Deckung: [hekataion], drei Schwellentore ([gate]), drei Fackeltürme ([tower]); Gold liegt nur als Ring um die
 *    Deckung ([goldRing]);
 * 5. Rand: Beete, Laternen, Greifensäule, Balustrade und gegliederte Klippe ([rimDressing], [cliff]);
 * 6. Terrasse des Höllentors ([hellTerrace]) und Wolken.
 */
internal object IsleC {

    // ---------------------------------------------------------------------------------------------
    // Benannte Zahlen (Bauweise; die Vertragsmaße stehen in OlympusArenas)
    // ---------------------------------------------------------------------------------------------

    /**
     * Sturz quer über die Fallgitter-Öffnung. Der Prüflauf nimmt Kernspalten über der Öffnung ab der Oberkante des
     * Fallgitters vom Freiraum aus (PZ1, Wunsch `tmp/overhaul4/requests/P07.md`). `false` zeichnet nur Kragstürze über den Pfeilern (im
     * Deckungsrand), die Mitte bleibt offen; `true` (Stand nach PZ1) den vollen Sturz.
     */
    const val FULL_LINTEL = true

    /** Der Sturz liegt auf den Ringen 15 … 16 (Lage entlang des Wegs, Zellmitten) und reicht quer bis ±5,5. */
    private const val LINTEL_ALONG_FROM = 14.5
    private const val LINTEL_ALONG_TO = 16.5
    private const val LINTEL_HALF = 5.5

    /** Ringbänder im Platz (gemeißelter Marmor) und Spiralarme des Strophalos. */
    private val RING_BANDS = listOf(5.5..6.3, 12.0..13.0)
    private val SPIRAL = 6.5..11.5

    /** Übergang zum Höllentor: Bänder ab Lage 16 entlang des Nordwestwegs, je 3,5 breit, alle 3 Blöcke quer eine Stufe weiter. */
    private const val NW_BAND_START = 16.0
    private const val NW_BAND_WIDTH = 3.5
    private const val NW_BAND_STEP = 3.0
    private const val NW_BAND_REACH = 14.0

    /** Terrassen: Feuerschalen bei ±14°, Zypressenbeete bei ±31°, Bank bzw. Stele in der Mitte. */
    private const val TERRACE_BRAZIER_ANGLE = 14.0
    private const val TERRACE_BRAZIER_R = 23.5
    private const val TERRACE_PLANTER_ANGLE = 31.0
    private const val TERRACE_PLANTER_R = 23.0
    private const val TERRACE_SEAT_R = 25.0

    /** Klippe: Vorsprünge alle 9 Blöcke am Rand, 2 breit. */
    private const val CLIFF_BUTTRESS_SPACING = 9
    /** Länge der hängenden Pfeiler unter der Deckplatte (Ziegel), darunter der Kragstein. */
    private const val CLIFF_BUTTRESS_LENGTH = 5

    private val isle get() = OlympusLayout.C
    private val spec get() = OlympusArenas.C

    private fun d(b: Block): BlockState = b.defaultState

    /** Ordnung der dunklen Bauten (Propylon, Nordwesttor): Tartaros-Basalt statt Schwarzstein. */
    private val TARTARUS: OlympKit.Order
        get() = OlympKit.Order(
            d(HhaDecoBlocks.POLISHED_TARTARUS_BASALT), HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS,
            d(HhaDecoBlocks.TARTARUS_BASALT_PILLAR), d(HhaDecoBlocks.CHISELED_TARTARUS_BASALT), HhaDecoBlocks.TARTARUS_BASALT_BRICK_STAIRS,
        )

    // =============================================================================================
    // C  Dreiweg der Hekate
    // =============================================================================================

    fun draw(c: SkyCanvas) {
        val fy = isle.floorY
        val cx = isle.center.x
        val cz = isle.center.z
        val body = OlympusIsles.Body(cx, fy - 1, cz, isle.radius.toDouble(), IsleKind.MARBLE, 33, 0.92)
        OlympusIsles.body(c, body)
        // Die tiefere Terrasse mit dem Höllentor im Nordwesten: eigener Fels, Fläche 6 tiefer.
        val ty = OlympusLayout.HELL_TERRACE_Y
        val gate = OlympusLayout.HELL_GATE
        val hellBody = OlympusIsles.Body(gate.x + 2, ty - 1, gate.z, 12.0, IsleKind.MARBLE, 34, 0.85)
        OlympusIsles.body(c, hellBody)
        bridgeHeads(c, isle)
        plazaFloor(c, cx, fy, cz)
        ringWalk(c, body, cx, fy, cz)
        outerPaths(c, fy, cz)
        for (level in spec.levels) terrace(c, level.id.removePrefix("terrace_").toDouble(), fy)
        hekataion(c, cx, fy, cz)
        for (a in OlympusLayout.C_PATH_ANGLES) gate(c, a, fy)
        for (i in OlympusArenas.C_TOWER_ANGLES.indices) tower(c, OlympusArenas.cTower(i), fy)
        goldRing(c, cx, cz, fy, 3)
        for (i in OlympusArenas.C_TOWER_ANGLES.indices) OlympusArenas.cTower(i).let { goldRing(c, it.x, it.z, fy, 3) }
        altar(c, isle.altar!!, 0)
        rimDressing(c, body, cx, fy, cz)
        cliff(c, body, hellBody, fy, cz)
        hellTerrace(c, fy, ty)
        OlympusIsles.cloudSkirt(c, body, 5)
        OlympusIsles.cloud(c, 50, 118, cz + 16, 18.0, 12.0, 35, 3.0)
        OlympusIsles.cloud(c, -54, 112, cz + 22, 16.0, 11.0, 36, 2.6)
    }

    // ---------------------------------------------------------------------------------------------
    // Geometrie-Helfer (Zellmitten relativ zur Platzmitte, wie die Zonen des Vertrags)
    // ---------------------------------------------------------------------------------------------

    /** Lage entlang und quer zum Weg mit Winkel [deg] für die Zelle (dx, dz) relativ zur Mitte. */
    private fun along(deg: Double, dx: Double, dz: Double): Double {
        val dir = OlympusLayout.dir(deg)
        return dx * dir.x + dz * dir.z
    }

    private fun across(deg: Double, dx: Double, dz: Double): Double {
        val dir = OlympusLayout.dir(deg)
        return -dx * dir.z + dz * dir.x
    }

    /** Waagerechte Richtung, die dem Winkel [deg] am nächsten liegt (0° Osten, 90° Süden). */
    private fun facing(deg: Double): Direction {
        val a = Math.toRadians(deg)
        val x = cos(a)
        val z = sin(a)
        return if (abs(x) >= abs(z)) (if (x > 0) Direction.EAST else Direction.WEST) else (if (z > 0) Direction.SOUTH else Direction.NORTH)
    }

    /** Liegt die Spalte im Kern des Vertrags (Platz r 19 und die drei Wege)? */
    private fun inCore(x: Int, z: Int): Boolean = spec.core.containsCell(x, z)

    private fun inTerrace(x: Int, z: Int): Boolean = spec.levels.any { it.zone.containsCell(x, z) }

    /** Spalten, die der Prüflauf als Deckung samt Rand 1 vom Freiraum-Check ausnimmt. */
    private fun inCoverRim(x: Int, z: Int, cover: CoverSpot): Boolean =
        x in cover.box.minX - 1..cover.box.maxX + 1 && z in cover.box.minZ - 1..cover.box.maxZ + 1

    /**
     * Übergangsband zum Höllentor für die Zelle (dx, dz): 0 = keins (normaler Boden), 1 = polierter Marmor rein,
     * 2 = gemeißelter Marmor, 3 = polierter Tartaros-Basalt. Die Grenzen springen alle [NW_BAND_STEP] Blöcke quer um
     * einen Block nach außen: Die Bänder bilden einen gestuften Pfeil, der zum Tor zeigt.
     */
    private fun nwBand(dx: Double, dz: Double): Int {
        val nw = OlympusLayout.C_PATH_ANGLES[OlympusLayout.C_PATH_NORTHWEST]
        val l = along(nw, dx, dz)
        val q = abs(across(nw, dx, dz))
        if (l < NW_BAND_START - 0.5 || q > NW_BAND_REACH) return 0
        val start = NW_BAND_START + floor(q / NW_BAND_STEP)
        return when {
            l < start -> 0
            l < start + NW_BAND_WIDTH -> 1
            l < start + 2 * NW_BAND_WIDTH -> 2
            else -> 3
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Platzboden
    // ---------------------------------------------------------------------------------------------

    /**
     * Der Platz (r 26): polierter Olymp-Marmor mit zwei gemeißelten Ringbändern und dem Strophalos aus Diorit, die drei
     * Wege aus Andesit (6 breit, Rand 1), eine gemeißelte Schwelle unter jedem Tor (Ring 15). Zum Höllentor hin drei
     * gestufte Bänder statt des früheren Punktrasters, mit einer Seelenader als Mittellinie.
     */
    private fun plazaFloor(c: SkyCanvas, cx: Int, fy: Int, cz: Int) {
        val arena = isle.arenaRadius.toDouble()
        val nwWay = OlympusLayout.C_PATH_NORTHWEST
        c.disc(cx, cz, arena + 0.5) { x, z, r ->
            val dx = (x - cx).toDouble()
            val dz = (z - cz).toDouble()
            val a = angleOf(dx, dz)
            var way = -1
            var threshold = false
            for ((i, deg) in OlympusLayout.C_PATH_ANGLES.withIndex()) {
                val l = along(deg, dx, dz)
                val q = abs(across(deg, dx, dz))
                if (l > 1.5 && q <= OlympusArenas.C_WAY_HALF) way = i
                else if (l > 1.5 && q < OlympusArenas.C_WAY_HALF + 0.8 && way < 0) way = 10 + i
                if (abs(l - OlympusArenas.C_GATE_RADIUS) < 0.5 && q <= 3.5) threshold = true
            }
            val band = nwBand(dx, dz)
            val nwSide = way == nwWay || way == 10 + nwWay
            val y = fy - 1
            val state = when {
                threshold && nwSide -> d(HhaDecoBlocks.CHISELED_TARTARUS_BASALT)
                threshold -> d(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE)
                way == nwWay && band == 3 -> s(Blocks.POLISHED_BLACKSTONE_BRICKS)
                way == 10 + nwWay && band == 3 -> d(HhaDecoBlocks.TARTARUS_BASALT_BRICKS)
                way in 0..2 -> s(Blocks.POLISHED_ANDESITE)
                way >= 10 -> s(Blocks.ANDESITE)
                r > arena - 0.5 -> if (band == 3) d(HhaDecoBlocks.CHISELED_TARTARUS_BASALT) else s(Blocks.CHISELED_QUARTZ_BLOCK)
                band == 3 -> d(HhaDecoBlocks.POLISHED_TARTARUS_BASALT)
                band == 2 -> d(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE)
                band == 1 -> d(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE)
                RING_BANDS.any { r in it } -> d(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE)
                r in SPIRAL && Math.floorMod((a + r * 17.0).roundToInt(), 120) < 22 -> s(Blocks.POLISHED_DIORITE)
                else -> DecoPalettes.MARBLE_FLOOR(x, y, z)
            }
            c.put(x, y, z, state)
            for (h in 0..30) c.put(x, fy + h, z, Kit.AIR)
        }
    }

    /**
     * Seelenader als Mittellinie des Nordwestwegs: vom Tor (Lage 16) bis zur obersten Stufe der Höllentreppe; unten auf
     * der Terrasse setzt [hellTerrace] sie bis zur Schwelle fort.
     */
    private fun soulLine(c: SkyCanvas, fy: Int, cz: Int) {
        val nw = OlympusLayout.C_PATH_ANGLES[OlympusLayout.C_PATH_NORTHWEST]
        val start = ArenaGeo.polar(isle.center, OlympusArenas.C_GATE_RADIUS + 1.0, nw)
        val stairTop = stairTopX()
        line(c, start.x, start.z, stairTop + 1, cz - 14, fy - 1, d(HhaDecoBlocks.SOUL_VEIN_BASALT))
        line(c, stairTop + 1, cz - 14, stairTop + 1, hellStairBand().let { (it.first + it.last) / 2 }, fy - 1, d(HhaDecoBlocks.SOUL_VEIN_BASALT))
    }

    /** Linie aus Blöcken auf Höhe [y] (vierfach verbunden, damit sie als geschlossenes Band liest). */
    private fun line(c: SkyCanvas, x0: Int, z0: Int, x1: Int, z1: Int, y: Int, state: BlockState) {
        val n = maxOf(abs(x1 - x0), abs(z1 - z0)).coerceAtLeast(1)
        var px = x0
        var pz = z0
        for (i in 0..n) {
            val x = (x0 + (x1 - x0) * i / n.toDouble()).roundToInt()
            val z = (z0 + (z1 - z0) * i / n.toDouble()).roundToInt()
            if (x != px && z != pz) c.block(x, y, pz, state)
            c.block(x, y, z, state)
            px = x
            pz = z
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Umgang und Wege nach außen
    // ---------------------------------------------------------------------------------------------

    /** Umgang r 26,5 … 31,5 hinter den Terrassen; zum Höllentor hin aus Tartaros-Basalt. */
    private fun ringWalk(c: SkyCanvas, body: OlympusIsles.Body, cx: Int, fy: Int, cz: Int) {
        val arena = isle.arenaRadius.toDouble()
        val nw = OlympusLayout.C_PATH_ANGLES[OlympusLayout.C_PATH_NORTHWEST]
        c.disc(cx, cz, 31.5) { x, z, r ->
            if (r <= arena + 0.5) return@disc
            if (!body.rim.containsCell(x, z)) return@disc
            val a = angleOf((x - cx).toDouble(), (z - cz).toDouble())
            val dark = angleDiff(a, nw) < 18.0
            val state = when {
                r > 30.5 -> if (dark) d(HhaDecoBlocks.TARTARUS_BASALT_BRICKS) else s(Blocks.QUARTZ_BRICKS)
                dark -> d(HhaDecoBlocks.POLISHED_TARTARUS_BASALT)
                else -> s(Blocks.POLISHED_DIORITE)
            }
            c.put(x, fy - 1, z, state)
        }
    }

    /** Wege über den Ring hinaus: Süd zur Brücke von B, Nordost zur Brücke nach D, Nordwest zur Treppe. */
    private fun outerPaths(c: SkyCanvas, fy: Int, cz: Int) {
        path(c, 0, cz + 27, 0, cz + 37, fy - 1, 3.0) { _, _ -> s(Blocks.POLISHED_ANDESITE) }
        path(c, 22, cz - 13, 31, cz - 20, fy - 1, 3.0) { _, _ -> s(Blocks.POLISHED_ANDESITE) }
        path(c, -23, cz - 14, -25, cz - 15, fy - 1, 3.4) { _, _ -> d(HhaDecoBlocks.POLISHED_TARTARUS_BASALT) }
        soulLine(c, fy, cz)
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Terrassen
    // ---------------------------------------------------------------------------------------------

    /**
     * Terrasse mit Mitte [a] (Blockringe 20 … 26, ±40°, Boden +3): Stützmauer aus Ziegeln mit Lisenen über einem
     * polierten Sockel, Belag poliert, Kante zum Kern vergoldet, Brüstung nur außen. Treppe 5 breit genau auf den
     * Zugangszellen des Vertrags; Lücken zwischen Treppe und Terrasse außerhalb des Kerns werden Podest.
     */
    private fun terrace(c: SkyCanvas, a: Double, fy: Int) {
        val level = spec.level("terrace_${a.toInt()}")
        val zone = level.zone
        val top = level.floorY - 1
        val cx = isle.center.x
        val cz = isle.center.z
        val cells = zone.cells().toList()
        val set = cells.mapTo(HashSet()) { BlockPos.asLong(it.first, 0, it.second) }
        fun has(x: Int, z: Int) = BlockPos.asLong(x, 0, z) in set
        val outer = HashSet<Long>()
        for ((x, z) in cells) {
            val dx = (x - cx).toDouble()
            val dz = (z - cz).toDouble()
            val r = hypot(dx, dz)
            val inner = r < OlympusArenas.C_TERRACE_INNER + 0.5
            val rim = r > OlympusArenas.C_TERRACE_OUTER - 0.5 && OlympKit.HORIZONTAL.any { !has(x + it.offsetX, z + it.offsetZ) }
            val edge = OlympKit.HORIZONTAL.any { !has(x + it.offsetX, z + it.offsetZ) }
            // Lisenen: alle 6 Blöcke Bogenlänge eine Säule in der Mauer.
            val arc = (angleOf(dx, dz) - a + 540.0) % 360.0 - 180.0
            val lisene = Math.floorMod((Math.toRadians(arc) * r).roundToInt(), 6) == 0
            for (y in fy until top) {
                val state = when {
                    !edge -> d(HhaDecoBlocks.OLYMPUS_MARBLE)
                    lisene -> d(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR)
                    y == fy -> d(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE)
                    else -> d(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS)
                }
                c.put(x, y, z, state)
            }
            c.put(x, top, z, when {
                inner -> d(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE)
                rim -> d(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS)
                edge -> d(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE)
                else -> DecoPalettes.MARBLE_FLOOR(x, top, z)
            })
            for (h in 1..8) c.put(x, top + h, z, Kit.AIR)
            if (rim) outer.add(BlockPos.asLong(x, 0, z))
        }
        balustrade(c, outer, top + 1)
        terraceStair(c, level.access, a, fy)
        // Ausstattung: zwei Feuerschalen, zwei Zypressenbeete, in der Mitte Bank (30°, 150°) bzw. Stele (270°).
        for (side in intArrayOf(-1, 1)) {
            val (bx, bz) = polar(cx, cz, TERRACE_BRAZIER_R, a + side * TERRACE_BRAZIER_ANGLE)
            bronzeBrazier(c, bx, top + 1, bz)
            val (px, pz) = polar(cx, cz, TERRACE_PLANTER_R, a + side * TERRACE_PLANTER_ANGLE)
            for (ox in -1..1) for (oz in -1..1) c.block(px + ox, top + 1, pz + oz, if (ox == 0 && oz == 0) s(Blocks.MOSS_BLOCK) else d(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS))
            OlympusIsles.cypress(c, px, top + 2, pz, 6 + Math.floorMod(a.toInt() / 30 + side, 3), a.toInt() + side * 7 + 300)
        }
        val inward = facing(a + 180.0)
        if (a == 270.0) {
            val (sx, sz) = polar(cx, cz, TERRACE_SEAT_R - 0.5, a)
            stele(c, sx, top + 1, sz, 9, inward)
            c.block(sx, top, sz, d(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE))
        } else {
            bench(c, a, top + 1)
        }
    }

    /**
     * Treppe auf den Zugangszellen des Vertrags (`radialStairs`, Ringe 17/18/19 auf +0/+1/+2): Treppenblöcke steigen
     * nach außen, darunter voll. Zellen im Treppenstreifen außerhalb des Kerns, die weder Treppe noch Terrasse sind,
     * werden zum Podest auf Terrassenhöhe, damit keine Lücke zwischen letzter Stufe und Terrasse bleibt.
     */
    private fun terraceStair(c: SkyCanvas, access: List<BlockPos>, a: Double, fy: Int) {
        val up = facing(a)
        val top = HashMap<Long, Int>()
        for (p in access) top.merge(BlockPos.asLong(p.x, 0, p.z), p.y, ::maxOf)
        for ((k, y) in top) {
            val x = BlockPos.unpackLongX(k)
            val z = BlockPos.unpackLongZ(k)
            for (yy in fy until y) c.put(x, yy, z, d(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE))
            c.put(x, y, z, stair(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_STAIRS, up))
        }
        val cx = isle.center.x
        val cz = isle.center.z
        val half = OlympusArenas.C_TERRACE_STAIR_WIDTH / 2.0
        val landing = fy + OlympusArenas.C_TERRACE_HEIGHT - 1
        for (x in cx - 28..cx + 28) for (z in cz - 28..cz + 28) {
            val dx = (x - cx).toDouble()
            val dz = (z - cz).toDouble()
            val l = along(a, dx, dz)
            if (l !in OlympusArenas.C_TERRACE_STAIR_FROM - 0.5..OlympusArenas.C_TERRACE_INNER + 0.5) continue
            if (abs(across(a, dx, dz)) > half) continue
            if (BlockPos.asLong(x, 0, z) in top || inCore(x, z) || inTerrace(x, z)) continue
            for (y in fy..landing) c.put(x, y, z, if (y == landing) d(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE) else d(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE))
        }
    }

    /** Brüstung aus Marmorziegel-Mauer mit echten Verbindungen; an Ecken und alle 5 Schritte ein Pfosten mit Deckplatte. */
    private fun balustrade(c: SkyCanvas, cells: Set<Long>, y: Int) {
        for (k in cells) {
            val x = BlockPos.unpackLongX(k)
            val z = BlockPos.unpackLongZ(k)
            val linked = OlympKit.HORIZONTAL.filter { BlockPos.asLong(x + it.offsetX, 0, z + it.offsetZ) in cells }
            val straight = linked.size == 2 && linked[0] == linked[1].opposite
            if (!straight || Math.floorMod(x * 3 + z * 7, 5) == 0) {
                c.block(x, y, z, d(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR))
                c.block(x, y + 1, z, slab(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_SLAB))
                continue
            }
            var state = HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_WALL.defaultState.with(WallBlock.UP, false)
            for (dir in OlympKit.HORIZONTAL) {
                state = state.with(WallBlock.WALL_SHAPE_PROPERTIES_BY_DIRECTION[dir]!!, if (dir in linked) WallShape.LOW else WallShape.NONE)
            }
            c.block(x, y, z, state)
        }
    }

    /** Feuerschale der Terrassen: gemeißelter Fuß, Mauerstiel, Bronzeschale mit Lagerfeuer (warmes Licht am Rand). */
    private fun bronzeBrazier(c: SkyCanvas, x: Int, y: Int, z: Int) {
        c.block(x, y, z, d(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE))
        c.block(x, y + 1, z, wall(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_WALL, alongX = true, post = true))
        c.block(x, y + 2, z, d(HhaDecoBlocks.OLYMPUS_BRONZE))
        for (dir in OlympKit.HORIZONTAL) c.block(x + dir.offsetX, y + 2, z + dir.offsetZ, stair(HhaDecoBlocks.OLYMPUS_BRONZE_STAIRS, dir.opposite, top = true))
        c.block(x, y + 3, z, s(Blocks.CAMPFIRE))
    }

    /** Bank an der Brüstung: drei Stufen, Rücken nach außen, Sitz zur Mitte. */
    private fun bench(c: SkyCanvas, a: Double, y: Int) {
        val back = facing(a)
        val seat = HashSet<Long>()
        for (k in -1..1) {
            val p = ArenaGeo.polar(isle.center, TERRACE_SEAT_R, a + Math.toDegrees(k / TERRACE_SEAT_R))
            seat.add(BlockPos.asLong(p.x, 0, p.z))
        }
        for (k in seat) c.block(BlockPos.unpackLongX(k), y, BlockPos.unpackLongZ(k), stair(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_STAIRS, back))
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Deckung
    // ---------------------------------------------------------------------------------------------

    /**
     * Hekataion (Deckung `hekataion`, 3 × 3, massiv bis +7): Stufensockel 5 × 5, Sockel aus Tartaros-Basalt, darüber
     * der dreigesichtige Schaft — drei Gesichter aus Tiefenschiefer zu den drei Wegen, dazwischen Basaltsäulen —, drei
     * Fackelarme mit Seelenlaternen, Krone und oben eine Feuerschale mit Seelenfeuer als Wahrzeichen.
     */
    private fun hekataion(c: SkyCanvas, cx: Int, fy: Int, cz: Int) {
        val height = OlympusLayout.HEKATAION_HEIGHT
        val crown = fy + height - 1
        val faces = listOf(0 to 1, -1 to -1, 1 to -1)
        val arms = listOf(0 to 2, -2 to -1, 2 to -1)
        // Stufensockel 5 × 5.
        for (ox in -2..2) for (oz in -2..2) {
            if (abs(ox) == 2 || abs(oz) == 2) c.block(cx + ox, fy, cz + oz, stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, Kit.inward(ox, oz)))
        }
        for (ox in -1..1) for (oz in -1..1) {
            val corner = ox != 0 && oz != 0
            val face = (ox to oz) in faces
            val center = ox == 0 && oz == 0
            c.block(cx + ox, fy, cz + oz, d(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
            c.block(cx + ox, fy + 1, cz + oz, if (corner) d(HhaDecoBlocks.CHISELED_TARTARUS_BASALT) else d(HhaDecoBlocks.TARTARUS_BASALT_BRICKS))
            for (y in fy + 2 until crown) c.block(cx + ox, y, cz + oz, when {
                center -> d(HhaDecoBlocks.TARTARUS_BASALT_PILLAR)
                face && y == fy + 4 -> s(Blocks.CHISELED_DEEPSLATE)
                face -> s(Blocks.POLISHED_DEEPSLATE)
                else -> d(HhaDecoBlocks.TARTARUS_BASALT_PILLAR)
            })
            c.block(cx + ox, crown, cz + oz, when {
                center -> d(HhaDecoBlocks.TARTARUS_BASALT_PILLAR)
                corner -> d(HhaDecoBlocks.CHISELED_TARTARUS_BASALT)
                else -> d(HhaDecoBlocks.POLISHED_TARTARUS_BASALT)
            })
        }
        // Fackelarme: Kragstein eine Lage unter der Krone, darauf die Seelenlaterne.
        for ((ox, oz) in arms) {
            c.block(cx + ox, crown - 1, cz + oz, stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, Kit.inward(ox, oz), top = true))
            c.block(cx + ox, crown, cz + oz, Kit.lantern(hanging = false, soul = true))
        }
        // Spitze: Stufenpyramide, Feuerschale mit Seelenfeuer.
        c.block(cx, crown + 1, cz, d(HhaDecoBlocks.CHISELED_TARTARUS_BASALT))
        for (dir in OlympKit.HORIZONTAL) {
            c.block(cx + dir.offsetX, crown + 1, cz + dir.offsetZ, stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, dir.opposite))
            c.block(cx + dir.offsetX, crown + 2, cz + dir.offsetZ, stair(HhaDecoBlocks.TARTARUS_BASALT_BRICK_STAIRS, dir.opposite, top = true))
        }
        c.block(cx, crown + 2, cz, s(Blocks.SOUL_CAMPFIRE))
    }

    /**
     * Schwellentor über dem Weg [a]: zwei Pfeiler 2 × 2 an den Vertragskästen (`gate_<a>_l/_r`, 5 hoch) aus
     * Säulenmarmor auf einem Ziegelsockel, darüber der Sturz (+5 … +6, Architrav und gemeißelter Fries) mit Deckplatte
     * und je einer Laterne über den Pfeilern. Das Nordwesttor zum Höllentor in dunkler Ordnung (Tartaros-Basalt).
     * Die Öffnung (Fallgitter-Teil `portcullis_<a>`) bleibt Luft.
     */
    private fun gate(c: SkyCanvas, a: Double, fy: Int) {
        val dark = a == OlympusLayout.C_PATH_ANGLES[OlympusLayout.C_PATH_NORTHWEST]
        val pillars = listOf(spec.cover("gate_${a.toInt()}_l"), spec.cover("gate_${a.toInt()}_r"))
        val sockel = if (dark) d(HhaDecoBlocks.TARTARUS_BASALT_BRICKS) else d(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS)
        val shaft = if (dark) d(HhaDecoBlocks.TARTARUS_BASALT_PILLAR) else d(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR)
        for (p in pillars) {
            val b = p.box
            for (x in b.minX..b.maxX) for (z in b.minZ..b.maxZ) for (y in b.minY..b.maxY) c.block(x, y, z, if (y == b.minY) sockel else shaft)
        }
        gateLintel(c, a, fy, pillars, dark)
    }

    /** Sturz: Ringe 15 … 16, quer ±5,5, dazu die Pfeilerzellen (die schrägen Tore als Stufenlinie gerastert). */
    private fun gateLintel(c: SkyCanvas, a: Double, fy: Int, pillars: List<CoverSpot>, dark: Boolean) {
        val y0 = fy + OlympusArenas.C_GATE_LINTEL
        val cx = isle.center.x
        val cz = isle.center.z
        val cells = HashSet<Long>()
        for (p in pillars) for (x in p.box.minX..p.box.maxX) for (z in p.box.minZ..p.box.maxZ) cells.add(BlockPos.asLong(x, 0, z))
        for (x in cx - 28..cx + 28) for (z in cz - 28..cz + 28) {
            val dx = (x - cx).toDouble()
            val dz = (z - cz).toDouble()
            if (along(a, dx, dz) !in LINTEL_ALONG_FROM..LINTEL_ALONG_TO || abs(across(a, dx, dz)) > LINTEL_HALF) continue
            if (!FULL_LINTEL && pillars.none { inCoverRim(x, z, it) }) continue
            cells.add(BlockPos.asLong(x, 0, z))
        }
        val architrave = if (dark) d(HhaDecoBlocks.POLISHED_TARTARUS_BASALT) else d(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE)
        val frieze = if (dark) d(HhaDecoBlocks.CHISELED_TARTARUS_BASALT) else d(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE)
        val cap = if (dark) slab(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_SLAB) else slab(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_SLAB)
        val capFull = if (dark) d(HhaDecoBlocks.TARTARUS_BASALT_BRICKS) else d(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS)
        for (k in cells) {
            val x = BlockPos.unpackLongX(k)
            val z = BlockPos.unpackLongZ(k)
            val overPillar = pillars.any { x in it.box.minX..it.box.maxX && z in it.box.minZ..it.box.maxZ }
            c.block(x, y0, z, architrave)
            c.block(x, y0 + 1, z, frieze)
            c.block(x, y0 + 2, z, if (overPillar) capFull else cap)
        }
        // Je Pfeiler eine Laterne auf der Deckplatte (warm an den Marmortoren, Seelenlicht am Tor zum Höllentor).
        for (p in pillars) c.block(p.box.minX, y0 + 3, p.box.minZ, Kit.lantern(hanging = false, soul = dark))
    }

    /**
     * Fackelturm (Deckung `tower_<a>`, 3 × 3, 6 hoch, verbrauchbar bis auf einen Stumpf von 2): Die unteren zwei Lagen
     * sind ein Sockel für sich (Ziegel, darüber eine Lage poliert mit gemeißelten Ecken, also mit fertiger Oberkante),
     * darüber der Schaft mit Säulen an den Ecken und die Feuerschale **innerhalb** des Kastens (oberste Lage: gemeißelte
     * Ecken, Bronze-Kragsteine, Lagerfeuer). Bricht der Kampf alles über dem Stumpf ab, schwebt nichts.
     */
    private fun tower(c: SkyCanvas, t: BlockPos, fy: Int) {
        val top = fy + OlympusArenas.C_TOWER_HEIGHT - 1
        val stump = fy + OlympusArenas.C_TOWER_STUMP - 1
        for (ox in -2..2) for (oz in -2..2) {
            if (abs(ox) == 2 || abs(oz) == 2) c.block(t.x + ox, fy, t.z + oz, stair(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_STAIRS, Kit.inward(ox, oz)))
        }
        for (ox in -1..1) for (oz in -1..1) {
            val corner = ox != 0 && oz != 0
            val center = ox == 0 && oz == 0
            for (y in fy..top) c.block(t.x + ox, y, t.z + oz, when {
                y < stump -> d(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS)
                y == stump -> if (corner) d(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE) else d(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE)
                y < top -> if (corner) d(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR) else d(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS)
                corner -> d(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE)
                center -> s(Blocks.CAMPFIRE)
                else -> stair(HhaDecoBlocks.OLYMPUS_BRONZE_STAIRS, Kit.inward(ox, oz), top = true)
            })
            // Unter der Schale ein Bronzeboden, damit das Feuer auf Bronze steht.
            if (center) c.block(t.x, top - 1, t.z, d(HhaDecoBlocks.OLYMPUS_BRONZE))
        }
    }

    /** Goldring im Boden um eine Deckung (Abstand [ring] in Zellen, Chebyshev): Gold = sicher (Leitlinie 24). */
    private fun goldRing(c: SkyCanvas, x: Int, z: Int, fy: Int, ring: Int) {
        for (ox in -ring..ring) for (oz in -ring..ring) {
            if (maxOf(abs(ox), abs(oz)) != ring) continue
            c.block(x + ox, fy - 1, z + oz, d(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 5. Rand
    // ---------------------------------------------------------------------------------------------

    /**
     * Hinter den Terrassen: Beete mit Zypressen und Seelenlaternen im Ring (r 35 / 32,5), Greifensäule im Osten (Posten),
     * Fackelsäulen an der Höllentreppe, Stele 9 wanderte auf die Nordterrasse. Die Streifen ±6 um die Brückenanschlüsse
     * (Süd, Nordost) bleiben frei für die Kopftore (P06). Balustrade am Inselrand aus Marmorziegel-Mauer.
     */
    private fun rimDressing(c: SkyCanvas, body: OlympusIsles.Body, cx: Int, fy: Int, cz: Int) {
        for (deg in 0 until 360 step 15) {
            val dd = deg.toDouble()
            if (OlympusLayout.C_PATH_ANGLES.any { angleDiff(it, dd) < 16.0 } || dd in 178.0..242.0) continue
            val (tx, tz) = polar(cx, cz, 35.0, dd)
            if (!body.rim.containsCell(tx, tz)) continue
            if (deg % 30 == 0) {
                for (ox in -1..1) for (oz in -1..1) c.block(tx + ox, fy, tz + oz, if (ox == 0 && oz == 0) s(Blocks.MOSS_BLOCK) else d(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS))
                OlympusIsles.cypress(c, tx, fy + 1, tz, 7 + (deg / 30) % 3, deg + 300)
            } else {
                val (lx, lz) = polar(cx, cz, 32.5, dd)
                lanternPost(c, lx, fy, lz, soul = true, block = HhaDecoBlocks.TARTARUS_BASALT_BRICK_WALL)
            }
        }
        griffinColumn(c, 33, fy, cz + 10)
        // Fackelsäulen rahmen die Treppe zum Höllentor.
        for ((px, pz) in listOf(-27 to cz - 22, -27 to cz - 8)) torchPillar(c, px, fy, pz)
        rimBalustrade(c, body, fy, HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_WALL, d(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR)) { x, z -> bridgeOrHell(x, z, cz) }
    }

    /** Brückenanschlüsse (Süd ±6, Nordost) und die Höllentor-Terrasse: dort keine Brüstung und keine Klippenzier. */
    private fun bridgeOrHell(x: Int, z: Int, cz: Int): Boolean =
        (abs(x) <= 6 && z > cz + 28) || (x > 18 && z in cz - 27..cz - 13) || (x < -18 && z in cz - 40..cz - 2)

    /**
     * Gegliederte Klippe: ein Band aus Naturmarmor in der untersten Lage des Randbands, alle [CLIFF_BUTTRESS_SPACING]
     * Blöcke ein Vorsprung (2 Lagen Ziegel auf einem Kragstein, sichtbar getragen) und hängende Ranken. Nur in freie
     * Stellen außerhalb des Körpers, damit Brücken und die Höllentor-Terrasse unberührt bleiben.
     */
    private fun cliff(c: SkyCanvas, body: OlympusIsles.Body, hell: OlympusIsles.Body, fy: Int, cz: Int) {
        val reach = body.radius.toInt() + 2
        val bandY = fy - 3
        for (x in body.cx - reach..body.cx + reach) for (z in body.cz - reach..body.cz + reach) {
            if (!c.inside(x, z) || bridgeOrHell(x, z, cz) || hell.rim.containsCell(x, z)) continue
            if (body.rim.containsCell(x, z)) {
                if (OlympKit.HORIZONTAL.any { !body.rim.containsCell(x + it.offsetX, z + it.offsetZ) }) c.put(x, bandY, z, d(HhaDecoBlocks.OLYMPUS_MARBLE))
                continue
            }
            val wallDir = OlympKit.HORIZONTAL.firstOrNull { body.rim.containsCell(x - it.offsetX, z - it.offsetZ) } ?: continue
            val arc = (angleOf((x - body.cx).toDouble(), (z - body.cz).toDouble()) / 360.0 * 2 * Math.PI * body.radius).roundToInt()
            if (Math.floorMod(arc, CLIFF_BUTTRESS_SPACING) < 2) {
                // Hängender Pfeiler: Deckplatte bündig unter dem Rand, Ziegelschaft, unten ein Kragstein.
                c.soft(x, fy - 2, z, d(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE))
                for (k in 3 until 2 + CLIFF_BUTTRESS_LENGTH) c.soft(x, fy - k, z, d(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS))
                c.soft(x, fy - 2 - CLIFF_BUTTRESS_LENGTH, z, stair(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_STAIRS, wallDir.opposite, top = true))
            } else if (Kit.noise(x, 47, z) % 100 < 14) {
                val vine = Blocks.VINE.defaultState.with(vineFace(wallDir.opposite), true)
                val length = 2 + Kit.noise(x, 49, z) % 3
                for (k in 2 until 2 + length) c.soft(x, fy - k, z, vine)
            }
        }
    }

    private fun vineFace(d: Direction) = when (d) {
        Direction.NORTH -> Properties.NORTH
        Direction.SOUTH -> Properties.SOUTH
        Direction.EAST -> Properties.EAST
        else -> Properties.WEST
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Terrasse des Höllentors
    // ---------------------------------------------------------------------------------------------

    /** Oberste Stufe der Höllentreppe (x), die Treppe liegt im Band [hellStairBand]. */
    private fun stairTopX(): Int = OlympusLayout.HELL_GATE.x + 12

    private fun hellStairBand(): IntRange = OlympusLayout.HELL_GATE.z + 1..OlympusLayout.HELL_GATE.z + 11

    /**
     * Treppe sechs Blöcke hinab zur Terrasse des Höllentors: Propylon in dunkler Ordnung aus Tartaros-Basalt, die runde
     * Öffnung geschlossen (P03 öffnet sie mit `HadesGate.openRow`). Lage und Öffnung des Tors sind unverändert; statt
     * des Rauschens gehen Mauer und Boden in gestuften Bändern vom Marmor in den Basalt über.
     */
    private fun hellTerrace(c: SkyCanvas, fy: Int, ty: Int) {
        val gate = OlympusLayout.HELL_GATE
        val gx = gate.x
        val gz = gate.z
        // Terrasse x −46 … −30; Treppe x −29 … −24 im Band z gz + 1 … gz + 11; der Platz endet bei r 26,5.
        val west = gx - 10
        val east = gx + 6
        val stairTop = stairTopX()
        val band = hellStairBand()
        val north = gz - 14
        val south = gz + 13
        /** Mauerwerk in Bändern: am Platz Marmorziegel, zur Torwand hin Schwarzsteinziegel, dann Tartaros-Basalt. */
        fun masonry(x: Int, y: Int, z: Int): BlockState = when (stairTop - x) {
            in Int.MIN_VALUE..5 -> d(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS)
            in 6..9 -> if (y == ty - 3) d(HhaDecoBlocks.CHISELED_TARTARUS_BASALT) else s(Blocks.POLISHED_BLACKSTONE_BRICKS)
            else -> DecoPalettes.BASALT_WALL(x, y, z)
        }
        // Terrasse: alles darüber frei, Boden in Bändern zum Tor hin.
        for (x in west..east) for (z in north..south) {
            if (!c.inside(x, z)) continue
            for (y in ty..fy + 24) c.put(x, y, z, Kit.AIR)
            val floor = when {
                x == east -> d(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE)
                x == east - 1 -> d(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE)
                x >= gx + 2 -> d(HhaDecoBlocks.POLISHED_TARTARUS_BASALT)
                else -> DecoPalettes.BASALT_FLOOR(x, ty - 1, z)
            }
            c.put(x, ty - 1, z, floor)
            for (y in ty - 4..ty - 2) c.put(x, y, z, masonry(x, y, z))
        }
        // Stützmauer zwischen Platz und Terrasse (bei x = east + 1), oben eine Brüstung; die Treppe schneidet sie.
        val wallX = east + 1
        for (z in north..south) {
            if (z in band) continue
            for (y in ty - 1..fy - 1) c.block(wallX, y, z, if (y == fy - 1) d(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE) else masonry(wallX, y, z))
            c.block(wallX, fy, z, if (Math.floorMod(z, 4) == 0) d(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR) else wall(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_WALL, alongX = false))
            if (Math.floorMod(z, 6) == 0) for (h in 0..3) c.block(wallX - 1, ty + h, z, if (h == 3) stair(HhaDecoBlocks.TARTARUS_BASALT_BRICK_STAIRS, Direction.EAST, top = true) else d(HhaDecoBlocks.TARTARUS_BASALT_PILLAR))
        }
        // Die Treppe: sechs Stufen nach Westen hinab (die sechste schließt bündig an den Platz an), oben Marmor, unten
        // Basalt, Wangen mit Brüstung.
        for (k in 1..6) {
            val x = wallX + k - 1
            val y = ty + k - 1
            val step = if (k >= 4) HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_STAIRS else HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS
            for (z in band.first - 1..band.last + 1) {
                val edge = z == band.first - 1 || z == band.last + 1
                for (yy in ty - 4 until y) c.block(x, yy, z, masonry(x, yy, z))
                c.block(x, y, z, if (edge) masonry(x, y, z) else stair(step, Direction.EAST))
                if (edge) c.block(x, y + 1, z, wall(if (k >= 4) HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_WALL else HhaDecoBlocks.TARTARUS_BASALT_BRICK_WALL, alongX = true))
                for (h in (if (edge) 2 else 1)..5) c.block(x, y + h, z, Kit.AIR)
            }
        }
        // Brüstung an den freien Rändern der Terrasse.
        for (x in west..east) for (z in intArrayOf(north, south)) {
            c.block(x, ty, z, if (Math.floorMod(x, 4) == 0) d(HhaDecoBlocks.TARTARUS_BASALT_PILLAR) else wall(HhaDecoBlocks.TARTARUS_BASALT_BRICK_WALL, alongX = true))
        }
        for (z in north..south) c.block(west, ty, z, if (Math.floorMod(z, 4) == 0) d(HhaDecoBlocks.TARTARUS_BASALT_PILLAR) else wall(HhaDecoBlocks.TARTARUS_BASALT_BRICK_WALL, alongX = false))
        // Erhöhte Schwelle vor dem Tor: Die Öffnung beginnt eine Reihe über den Füßen.
        for (x in gx + 2..gx + 3) for (z in gz - 6..gz + 6) {
            c.block(x, ty, z, if (x == gx + 3) stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, Direction.WEST) else d(HhaDecoBlocks.CHISELED_TARTARUS_BASALT))
        }
        // Seelenader vom Fuß der Treppe zur Schwelle.
        line(c, east, (band.first + band.last) / 2, gx + 4, gz, ty - 1, d(HhaDecoBlocks.SOUL_VEIN_BASALT))
        // Torwand: drei dick (x gx − 1 … gx + 1), die runde Öffnung geht durch; die Torebene liegt bei gx und ist
        // geschlossen. Was im 9 × 9-Feld nicht zur Öffnung gehört, ist schon Schwarzsteinziegel (wie nach openRow).
        for (dz in -8..8) for (h in 0..15) {
            val z = gz + dz
            val y = gate.y + h
            val open = dev.henny.hha.logic.world.HadesGate.inOpening(dz, h)
            val ring = !open && hypot(dz.toDouble(), h - 5.5) <= 5.9
            val square = abs(dz) <= 4 && h in 1..9
            for (x in gx - 1..gx + 1) {
                val plane = x == gx
                val state = when {
                    h == 0 -> s(Blocks.CHISELED_POLISHED_BLACKSTONE)
                    plane && (open || square) -> s(Blocks.POLISHED_BLACKSTONE_BRICKS)
                    open -> Kit.AIR
                    ring && Math.floorMod(dz + h, 3) == 0 -> s(Blocks.GILDED_BLACKSTONE)
                    ring -> s(Blocks.CHISELED_POLISHED_BLACKSTONE)
                    h == 13 -> s(Blocks.POLISHED_BLACKSTONE)
                    h == 14 -> if (abs(dz) % 3 == 0) d(HhaDecoBlocks.CHISELED_TARTARUS_BASALT) else s(Blocks.POLISHED_BLACKSTONE_BRICKS)
                    h == 15 -> s(Blocks.POLISHED_BLACKSTONE)
                    abs(dz) == 8 -> d(HhaDecoBlocks.TARTARUS_BASALT_PILLAR)
                    abs(dz) > 5 && Kit.noise(x, y, z) % 13 == 0 -> s(Blocks.CRYING_OBSIDIAN)
                    Kit.noise(x, y, z) % 5 == 0 -> s(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS)
                    else -> s(Blocks.POLISHED_BLACKSTONE_BRICKS)
                }
                c.block(x, y, z, state)
            }
        }
        // Propylon nach Osten: zwei Säulen vor der Torwand, Gebälk mit Basaltfries, darüber der Giebel.
        for (dz in intArrayOf(-6, 6)) column(c, gx + 4, ty, gz + dz, 14, TARTARUS)
        for (dz in -8..8) for (x in gx + 2..gx + 5) {
            c.block(x, ty + 14, gz + dz, if (x == gx + 5) stair(HhaDecoBlocks.TARTARUS_BASALT_BRICK_STAIRS, Direction.WEST, top = true) else d(HhaDecoBlocks.TARTARUS_BASALT_BRICKS))
            c.block(x, ty + 15, gz + dz, if (x == gx + 5 && Math.floorMod(dz, 2) == 0) d(HhaDecoBlocks.CHISELED_TARTARUS_BASALT) else s(Blocks.POLISHED_BLACKSTONE))
        }
        for (dz in -9..9) {
            c.block(gx + 6, ty + 16, gz + dz, stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Direction.WEST, top = true))
            c.block(gx - 2, ty + 16, gz + dz, stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Direction.EAST, top = true))
        }
        for (dz in -9..9) {
            val run = 9 - abs(dz)
            val rise = run / 2
            for (h in 0 until rise) for (x in gx - 1..gx + 5) c.block(x, ty + 17 + h, gz + dz, if (x == gx + 5 && h == 0 && abs(dz) <= 2) s(Blocks.GILDED_BLACKSTONE) else s(Blocks.POLISHED_BLACKSTONE_BRICKS))
            val odd = run % 2 == 1
            for (x in gx - 1..gx + 6) c.block(x, ty + 17 + rise, gz + dz, if (dz == 0) d(HhaDecoBlocks.CHISELED_TARTARUS_BASALT) else if (odd) stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, if (dz < 0) Direction.SOUTH else Direction.NORTH) else slab(Blocks.POLISHED_BLACKSTONE_BRICK_SLAB))
        }
        // Grünes Feuer in zwei Schalen vor dem Tor, Seelenlaternen an der Treppe.
        for (dz in intArrayOf(-10, 10)) c.greenBrazier(gx + 4, ty, gz + dz, 1)
        lanternPost(c, east - 1, ty, band.last + 2, soul = true, block = HhaDecoBlocks.TARTARUS_BASALT_BRICK_WALL)
        lanternPost(c, east - 1, ty, north + 3, soul = true, block = HhaDecoBlocks.TARTARUS_BASALT_BRICK_WALL)
        stele(c, east - 3, ty, band.last + 1, 10, Direction.EAST)
    }
}
