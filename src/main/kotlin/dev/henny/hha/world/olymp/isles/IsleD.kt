package dev.henny.hha.world.olymp.isles

import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.world.DecoPalettes
import dev.henny.hha.world.Kit
import dev.henny.hha.world.arena.Level
import dev.henny.hha.world.block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.WallBlock
import net.minecraft.block.enums.WallShape
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import dev.henny.hha.world.olymp.*
import dev.henny.hha.world.olymp.OlympKit.Attr
import dev.henny.hha.world.olymp.OlympKit.BRONZE_LOOK
import dev.henny.hha.world.olymp.OlympKit.HORIZONTAL
import dev.henny.hha.world.olymp.OlympKit.altar
import dev.henny.hha.world.olymp.OlympKit.angleDiff
import dev.henny.hha.world.olymp.OlympKit.angleOf
import dev.henny.hha.world.olymp.OlympKit.automatonPlinth
import dev.henny.hha.world.olymp.OlympKit.figure
import dev.henny.hha.world.olymp.OlympKit.path
import dev.henny.hha.world.olymp.OlympKit.polar
import dev.henny.hha.world.olymp.OlympKit.rimBalustrade
import dev.henny.hha.world.olymp.OlympKit.s
import dev.henny.hha.world.olymp.OlympKit.slab
import dev.henny.hha.world.olymp.OlympKit.stair
import dev.henny.hha.world.olymp.OlympKit.stele
import dev.henny.hha.world.olymp.OlympusPlans.bridgeHeads

/**
 * Bauplan der Insel D, Schmiede der Kyklopen (Überarbeitung 4, P08; vorher aus `OlympusPlans` herausgelöst, P00).
 * Aufruf über `OlympusPlans.draw`.
 *
 * Arena (Vertrag `OlympusArenas.D`): Kern r 24 mit Rosten, Amboss als verbrauchbare Deckung (eigener Körper, eigener
 * Sockelrahmen), drei Kokillen 2 × 2 × 3 auf den Rippen bei r 15, zwei Essebühnen auf +2 mit Treppe am äußeren Ende.
 * Außerhalb der Arena: Schmiedehalle in der Esse (Esse mit Haube, Blasebalg, Gussrinne zum Feuermaul, Kokillenlager,
 * Löschbecken, Kette mit Haken), begehbare Galerie auf dem Ringbalken mit Außentreppe, Hof mit Schlacke, Erzkarren und
 * zweitem Wasserbecken. Alle Maße der Deckung und der Ebenen kommen aus dem Vertrag, nicht aus Kopien.
 */
internal object IsleD {

    // ---------------------------------------------------------------------------------------------
    // Benannte Maße des Bauwerks (keine Vertragsmaße: die stehen in OlympusArenas.D)
    // ---------------------------------------------------------------------------------------------

    /** Glutrisse auf den Rippen: Mittellinie (halbe Breite in Blöcken), von Ring 3 (außerhalb des Sockelrahmens) bis zum Bronzering. */
    private const val EMBER_HALF_WIDTH = 0.5
    private const val EMBER_FROM = 3.0
    /** Bronzering des Kerns: Blockringe 22 … 24. */
    private const val BRONZE_RING_FROM = 21.5

    /** Ringbalken = Galerie: Blockringe 25 … 29, Lücke ±18° um die Esse (270°). Belag auf +11, Füße auf +12. */
    private const val GALLERY_INNER = 25.0
    private const val GALLERY_OUTER = 29.0
    private const val GALLERY_GAP = 18.0
    private const val GALLERY_DECK = 11
    /** Brüstungspfosten mit Laterne etwa alle so vielen Grad. */
    private const val GALLERY_POST_STEP = 15.0

    /** Außentreppe zur Galerie im Hof westlich der Esse: Lauf 1 nach Osten (9 Stufen), Lauf 2 nach Süden (2 Stufen). */
    private const val STAIR_X0 = 130
    private const val STAIR_Z0 = -419
    private const val STAIR_RUN1 = 9
    private const val STAIR_WIDTH = 3

    /** Halle der Esse: Außenmaße x fx ± 11, z (Mitte − 25) − 13 … Mitte − 25, innen frei bis +9. */
    private const val HALL_HALF = 11
    private const val HALL_DEPTH = 13
    private const val HALL_CLEAR = 9

    fun draw(c: SkyCanvas) {
        val isle = OlympusLayout.D
        val fy = isle.floorY
        val cx = isle.center.x
        val cz = isle.center.z
        val body = OlympusIsles.Body(cx, fy - 1, cz, isle.radius.toDouble(), IsleKind.MARBLE, 44, 0.92)
        OlympusIsles.body(c, body)
        bridgeHeads(c, isle)
        arenaFloor(c, fy)
        anvilBody(c, fy)
        for (spot in OlympusArenas.D.cover) if (spot.id.startsWith("mold_")) moldBody(c, spot.box)
        for (level in OlympusArenas.D.levels) forgeStage(c, level, fy)
        altar(c, isle.altar!!, 1)
        hall(c, fy)
        // Weg zum Ausgang nach E vor der Esse zeichnen: Sein Freiraum schnitt früher die Ostecke der Esse an.
        path(c, 166, -420, 162, -413, fy - 1, 3.0) { _, _ -> s(Blocks.DEEPSLATE_TILES) }
        forgeBuilding(c, fy)
        forgeHall(c, fy)
        // Werkhof: ein Ring aus Fliesen um die Halle.
        c.disc(cx, cz, 31.5) { x, z, r ->
            if (r < 28.6 || !body.rim.containsCell(x, z)) return@disc
            if (angleDiff(angleOf(x - cx.toDouble(), z - cz.toDouble()), 270.0) < 22.0) return@disc
            c.put(x, fy - 1, z, if (r > 30.6) s(Blocks.POLISHED_DEEPSLATE) else s(Blocks.DEEPSLATE_TILES))
        }
        giantHammer(c, cx - 8, fy, cz + 32)
        rimBalustrade(c, body, fy, Blocks.DEEPSLATE_BRICK_WALL, s(Blocks.POLISHED_DEEPSLATE)) { x, z ->
            (x < cx - 18 && z > cz + 10) || (x > cx + 8 && z < cz - 24) || (abs(x - cx) <= 13 && z < cz - 22)
        }
        // Weg vom Brückenkopf durch die Lücke im Ring (der Weg zum Ausgang nach E steht vor der Esse).
        path(c, 120, -369, 131, -375, fy - 1, 3.0) { _, _ -> s(Blocks.DEEPSLATE_TILES) }
        yard(c, fy)
        galleryStair(c, fy)
        // Eine Wächterin zwischen zwei Bronzestatuen im Westen, Blick zur Halle.
        figure(c, 118, fy, -396, Direction.EAST, Attr.HAMMER, BRONZE_LOOK)
        figure(c, 118, fy, -384, Direction.EAST, Attr.BOWL, BRONZE_LOOK)
        automatonPlinth(c, 118, fy, -390, BRONZE_LOOK)
        stele(c, 126, fy, -369, 11, Direction.SOUTH)
        OlympusIsles.cloudSkirt(c, body, 5)
        OlympusIsles.cloud(c, cx + 46, 132, cz + 12, 18.0, 12.0, 45, 3.0)
        OlympusIsles.cloud(c, cx - 40, 128, cz - 30, 15.0, 10.0, 46, 2.6)
    }

    // =============================================================================================
    // Arena
    // =============================================================================================

    /** Liegt die Spalte unter einer Essebühne (Vertrag)? Dort enden die Roste bei r 18. */
    private fun underStage(x: Int, z: Int): Boolean = OlympusArenas.D.levels.any { it.zone.containsCell(x, z) }

    /**
     * Kampffläche: Tiefenschieferfliesen, sechs Roste aus Kupfergittern über Magma, gusseiserne Rippen dazwischen mit
     * einer Glutlinie aus `ember_basalt`, die auf den Amboss zuläuft, außen ein Bronzering (Blockringe 22 … 24).
     */
    private fun arenaFloor(c: SkyCanvas, fy: Int) {
        val isle = OlympusLayout.D
        val cx = isle.center.x
        val cz = isle.center.z
        val arena = isle.arenaRadius.toDouble()
        val forge = OlympusLayout.FORGE
        c.disc(cx, cz, arena + 0.5) { x, z, r ->
            val a = angleOf(x - cx.toDouble(), z - cz.toDouble())
            val boundary = (0 until OlympusLayout.GRATE_SECTORS).minOf { angleDiff(a, it * OlympusLayout.GRATE_SECTOR_DEGREES) }
            val across = Math.toRadians(boundary) * r
            val rib = across < 1.0
            // Gusseiserne Querrippen alle vier Ringe: Die Roste lesen sich als Gitter, nicht als Fläche.
            val bar = Math.floorMod(r.roundToInt(), 4) == 0
            val stage = underStage(x, z)
            val grate = !stage && r >= OlympusLayout.GRATE_INNER + 0.5 && r <= OlympusLayout.GRATE_OUTER - 0.5 && !rib && !bar
            val mouth = abs(x - forge.x) <= 1 && abs(z - forge.z) <= 1
            val ember = across < EMBER_HALF_WIDTH && r >= EMBER_FROM && r < BRONZE_RING_FROM
            val state = when {
                mouth || grate -> s(Blocks.WAXED_COPPER_GRATE)
                stage -> s(Blocks.DEEPSLATE_BRICKS)
                ember -> s(HhaDecoBlocks.EMBER_BASALT)
                r >= BRONZE_RING_FROM -> DecoPalettes.BRONZE(x, fy - 1, z)
                bar && !rib && r in OlympusLayout.GRATE_INNER..OlympusLayout.GRATE_OUTER -> s(Blocks.POLISHED_DEEPSLATE)
                r < OlympusLayout.GRATE_INNER - 0.5 -> if (Kit.noise(x, 1, z) % 6 == 0) s(Blocks.CRACKED_DEEPSLATE_TILES) else s(Blocks.POLISHED_DEEPSLATE)
                else -> s(Blocks.DEEPSLATE_BRICKS)
            }
            c.put(x, fy - 1, z, state)
            c.put(x, fy - 2, z, if (mouth) s(Blocks.LAVA) else if (grate) s(Blocks.MAGMA_BLOCK) else s(Blocks.DEEPSLATE_BRICKS))
            c.put(x, fy - 3, z, s(Blocks.POLISHED_DEEPSLATE))
            for (h in 0..24) c.put(x, fy + h, z, Kit.AIR)
        }
    }

    /**
     * Amboss (Deckung `anvil`, verbrauchbar): eigener Körper 3 × 3 × 2 auf eigenem Sockelrahmen. Der Rahmen liegt im
     * Boden (5 × 5, Bronze mit vergoldeten Ecken: Gold an der Deckung), darin der Fuß aus gemeißeltem Schwarzstein.
     * Unten Schwarzstein, oben drei Ambosse in einer Reihe, vorn und hinten Schrägen.
     */
    private fun anvilBody(c: SkyCanvas, fy: Int) {
        val anvil = OlympusLayout.ANVIL
        val half = OlympusLayout.ANVIL_HALF
        val frame = half + 1
        for (ox in -frame..frame) for (oz in -frame..frame) {
            val ring = abs(ox) == frame || abs(oz) == frame
            val corner = abs(ox) == frame && abs(oz) == frame
            val state = when {
                corner -> s(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE)
                ring -> s(HhaDecoBlocks.OLYMPUS_BRONZE)
                else -> s(Blocks.CHISELED_POLISHED_BLACKSTONE)
            }
            c.block(anvil.x + ox, fy - 1, anvil.z + oz, state)
        }
        for (ox in -half..half) for (oz in -half..half) {
            c.block(anvil.x + ox, fy, anvil.z + oz, if (ox == 0 && oz == 0) s(Blocks.CHISELED_POLISHED_BLACKSTONE) else s(Blocks.POLISHED_BLACKSTONE))
            c.block(anvil.x + ox, fy + 1, anvil.z + oz, when (oz) {
                0 -> (if (ox == 0) Blocks.ANVIL else if (ox < 0) Blocks.CHIPPED_ANVIL else Blocks.DAMAGED_ANVIL).defaultState.with(Properties.HORIZONTAL_FACING, Direction.NORTH)
                else -> stair(Blocks.POLISHED_BLACKSTONE_STAIRS, if (oz < 0) Direction.SOUTH else Direction.NORTH)
            })
        }
    }

    /**
     * Kokille (Deckung `mold_*`, fest): Gussform 2 × 2, 3 hoch, massiv. Fuß aus Tiefenschiefer, Körper aus Olymp-Bronze,
     * oben der glühende Einguss aus `ember_basalt` (zählt zur Höhe). Im Boden ein Rahmen aus poliertem Tiefenschiefer.
     */
    private fun moldBody(c: SkyCanvas, box: BlockBox) {
        for (x in box.minX - 1..box.maxX + 1) for (z in box.minZ - 1..box.maxZ + 1) {
            val inside = x in box.minX..box.maxX && z in box.minZ..box.maxZ
            if (!inside) c.block(x, box.minY - 1, z, s(Blocks.POLISHED_DEEPSLATE))
        }
        for (x in box.minX..box.maxX) for (z in box.minZ..box.maxZ) for (y in box.minY..box.maxY) {
            val h = y - box.minY
            val state = when {
                h == 0 -> s(Blocks.POLISHED_DEEPSLATE)
                y == box.maxY -> s(HhaDecoBlocks.EMBER_BASALT)
                else -> s(HhaDecoBlocks.OLYMPUS_BRONZE)
            }
            c.block(x, y, z, state)
        }
    }

    /**
     * Essebühne (Ebene `forge_stage_*`, +2): Körper aus Tiefenschiefer, Belag aus poliertem Olymp-Marmor mit Bronzekante,
     * zur Mitte eine vergoldete Kante (Ebenenkante). Treppe 3 breit aus dem Vertrag (`Level.access`). An der Außenseite
     * ein Werkzeugständer und ein Kohlebecken.
     */
    private fun forgeStage(c: SkyCanvas, level: Level, fy: Int) {
        val isle = OlympusLayout.D
        val cx = isle.center.x
        val cz = isle.center.z
        val cells = level.zone.cells().toSet()
        val bodyY = fy
        val deckY = level.floorY - 1
        for ((x, z) in cells) {
            val r = hypot((x - cx).toDouble(), (z - cz).toDouble())
            val edge = HORIZONTAL.any { (x + it.offsetX to z + it.offsetZ) !in cells }
            val inner = r < OlympusArenas.D_STAGE_INNER + 0.5
            for (y in bodyY until deckY) c.block(x, y, z, if (edge) s(Blocks.POLISHED_DEEPSLATE) else s(Blocks.DEEPSLATE_BRICKS))
            c.block(x, deckY, z, when {
                inner -> s(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE)
                edge -> s(HhaDecoBlocks.OLYMPUS_BRONZE)
                else -> s(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE)
            })
            for (h in 0..3) c.block(x, level.floorY + h, z, Kit.AIR)
        }
        // Treppe: Stufen genau an den Zugängen des Vertrags, darunter fest.
        val i = level.id.removePrefix("forge_stage_").toInt()
        val (from, to) = OlympusArenas.D_STAGES[i]
        val toward = if (i == 0) from + 90.0 else to - 90.0
        val facing = facingOf(toward)
        for (p in level.access) {
            for (y in bodyY until p.y) c.block(p.x, y, p.z, s(Blocks.POLISHED_DEEPSLATE))
            c.block(p.x, p.y, p.z, stair(Blocks.POLISHED_DEEPSLATE_STAIRS, facing))
            for (h in 1..3) c.block(p.x, p.y + h, p.z, Kit.AIR)
        }
        // Ausstattung an der Außenseite (Ring 23), weg von der Treppe.
        val far = if (i == 0) to else from
        val near = if (i == 0) from else to
        val rack = polar(cx, cz, 23.0, far + (near - far) * 0.25)
        val basin = polar(cx, cz, 23.0, far + (near - far) * 0.55)
        toolRack(c, rack.first, level.floorY, rack.second, facingOf(angleOf((rack.first - cx).toDouble(), (rack.second - cz).toDouble()) + 90.0))
        coalBasin(c, basin.first, level.floorY, basin.second)
    }

    /** Waagerechte Richtung, die dem Winkel am nächsten liegt (0° Osten, 90° Süden). */
    private fun facingOf(deg: Double): Direction {
        val a = Math.toRadians(deg)
        val dx = cos(a)
        val dz = sin(a)
        return if (abs(dx) >= abs(dz)) (if (dx > 0) Direction.EAST else Direction.WEST) else (if (dz > 0) Direction.SOUTH else Direction.NORTH)
    }

    /** Werkzeugständer: zwei Pfosten aus dunkler Eiche, ein Querholz, daran Zangen (Ketten) und ein Schleifstein. */
    private fun toolRack(c: SkyCanvas, x: Int, y: Int, z: Int, along: Direction) {
        val ax = along.offsetX
        val az = along.offsetZ
        for (k in intArrayOf(-1, 1)) {
            c.block(x + ax * k, y, z + az * k, s(Blocks.DARK_OAK_FENCE))
            c.block(x + ax * k, y + 1, z + az * k, s(Blocks.DARK_OAK_FENCE))
        }
        val axis = if (ax != 0) Direction.Axis.X else Direction.Axis.Z
        for (k in -1..1) c.block(x + ax * k, y + 2, z + az * k, s(Blocks.STRIPPED_DARK_OAK_LOG).with(Properties.AXIS, axis))
        c.block(x, y + 1, z, Kit.chain())
        c.block(x, y, z, s(Blocks.GRINDSTONE))
    }

    /** Kohlebecken: Bronzeschale auf Tiefenschiefer, darin Glut (Lagerfeuer auf Kohle). */
    private fun coalBasin(c: SkyCanvas, x: Int, y: Int, z: Int) {
        c.block(x, y, z, s(Blocks.POLISHED_DEEPSLATE))
        for (d in HORIZONTAL) c.block(x + d.offsetX, y, z + d.offsetZ, stair(HhaDecoBlocks.OLYMPUS_BRONZE_STAIRS, d.opposite, top = true))
        c.block(x, y + 1, z, s(Blocks.CAMPFIRE))
    }

    // =============================================================================================
    // Halle und Galerie
    // =============================================================================================

    /** Gehört die Spalte zum Ringbalken (Galerie)? Gleiche Rechnung wie die Halle (ganzzahlige Abstände). */
    private fun galleryCell(x: Int, z: Int): Boolean {
        val d = OlympusLayout.D.center
        val r = hypot((x - d.x).toDouble(), (z - d.z).toDouble())
        return r in GALLERY_INNER..GALLERY_OUTER && angleDiff(angleOf((x - d.x).toDouble(), (z - d.z).toDouble()), 270.0) >= GALLERY_GAP
    }

    /**
     * Die Halle: ein Ring aus wuchtigen Pfeilern mit Kupferbändern, darauf der umlaufende Balken als begehbare Galerie
     * (Belag +11, Brüstung innen und außen, Laternenpfosten). Kein Dach. Ketten mit Laternen zwischen den Pfeilern.
     */
    private fun hall(c: SkyCanvas, fy: Int) {
        val isle = OlympusLayout.D
        val cx = isle.center.x
        val cz = isle.center.z
        val entrance = 142.0
        val exit = 297.0
        for (k in 0 until 16) {
            val deg = k * 22.5 + 11.25
            if (angleDiff(deg, 270.0) < 24.0 || angleDiff(deg, entrance) < 9.0 || angleDiff(deg, exit) < 9.0) continue
            val (px, pz) = polar(cx, cz, 26.8, deg)
            hallPier(c, px, fy, pz)
        }
        val deck = fy + GALLERY_DECK
        c.disc(cx, cz, GALLERY_OUTER) { x, z, r ->
            if (!galleryCell(x, z)) return@disc
            c.put(x, fy + 9, z, if (Math.floorMod(x + z, 2) == 0) s(Blocks.DEEPSLATE_TILES) else s(Blocks.DEEPSLATE_BRICKS))
            c.put(x, fy + 10, z, if (r in 26.0..27.6) s(Blocks.WAXED_CUT_COPPER) else s(Blocks.DEEPSLATE_BRICKS))
            c.put(x, deck, z, if (r in 26.4..27.6) s(Blocks.DEEPSLATE_TILES) else s(Blocks.POLISHED_DEEPSLATE))
            for (h in 1..3) c.put(x, deck + h, z, Kit.AIR)
        }
        galleryRailing(c, deck + 1)
        // Ketten mit Laternen zwischen den Pfeilern.
        for (k in 0 until 16 step 2) {
            val deg = k * 22.5
            if (angleDiff(deg, 270.0) < 24.0) continue
            val (lx, lz) = polar(cx, cz, 26.8, deg)
            for (h in 6..8) c.block(lx, fy + h, lz, Kit.chain())
            c.block(lx, fy + 5, lz, Kit.lantern(hanging = true, soul = false))
        }
    }

    /**
     * Brüstung der Galerie: Mauer auf jeder Randspalte des Balkens (innen zur Arena, außen zum Hof, an den Enden), echt
     * verbunden; etwa alle [GALLERY_POST_STEP] Grad ein Pfosten mit Laterne. Die Öffnung der Außentreppe bleibt frei.
     */
    private fun galleryRailing(c: SkyCanvas, y: Int) {
        val d = OlympusLayout.D.center
        val cells = HashSet<Long>()
        c.disc(d.x, d.z, GALLERY_OUTER + 1.0) { x, z, _ ->
            if (!galleryCell(x, z) || stairOpening(x, z)) return@disc
            if (HORIZONTAL.none { !galleryCell(x + it.offsetX, z + it.offsetZ) }) return@disc
            cells.add(BlockPos.asLong(x, 0, z))
        }
        var lastPost = Double.NaN
        for (k in cells.sortedBy { angleOf((BlockPos.unpackLongX(it) - d.x).toDouble(), (BlockPos.unpackLongZ(it) - d.z).toDouble()) }) {
            val x = BlockPos.unpackLongX(k)
            val z = BlockPos.unpackLongZ(k)
            val linked = HORIZONTAL.filter { BlockPos.asLong(x + it.offsetX, 0, z + it.offsetZ) in cells }
            val r = hypot((x - d.x).toDouble(), (z - d.z).toDouble())
            val a = angleOf((x - d.x).toDouble(), (z - d.z).toDouble())
            val inner = r < GALLERY_INNER + 1.0
            if (inner && (lastPost.isNaN() || angleDiff(a, lastPost) >= GALLERY_POST_STEP)) {
                lastPost = a
                c.block(x, y, z, s(Blocks.POLISHED_DEEPSLATE))
                c.block(x, y + 1, z, Kit.lantern(hanging = false, soul = false))
                continue
            }
            var state = Blocks.DEEPSLATE_BRICK_WALL.defaultState.with(WallBlock.UP, linked.size != 2 || linked[0] != linked[1].opposite)
            for (dir in HORIZONTAL) state = state.with(WallBlock.WALL_SHAPE_PROPERTIES_BY_DIRECTION[dir]!!, if (dir in linked) WallShape.LOW else WallShape.NONE)
            c.block(x, y, z, state)
        }
    }

    /** Hallenpfeiler: Sockel aus poliertem Tiefenschiefer, Ecken im Wechsel, ein Kupferband, Kapitell aus Kupfer. */
    private fun hallPier(c: SkyCanvas, x: Int, fy: Int, z: Int) {
        for (ox in -1..1) for (oz in -1..1) {
            val corner = ox != 0 && oz != 0
            for (h in 0..8) {
                val state = when {
                    h == 0 -> if (corner) stair(Blocks.POLISHED_DEEPSLATE_STAIRS, Kit.inward(ox, oz)) else s(Blocks.POLISHED_DEEPSLATE)
                    h == 8 -> if (corner) stair(Blocks.WAXED_CUT_COPPER_STAIRS, Kit.inward(ox, oz), top = true) else s(Blocks.WAXED_CUT_COPPER)
                    h == 4 -> s(Blocks.WAXED_CUT_COPPER)
                    corner -> if (h % 2 == 0) s(Blocks.DEEPSLATE_TILES) else s(Blocks.POLISHED_DEEPSLATE)
                    else -> if (Kit.noise(x + ox, fy + h, z + oz) % 7 == 0) s(Blocks.CRACKED_DEEPSLATE_BRICKS) else s(Blocks.DEEPSLATE_BRICKS)
                }
                c.block(x + ox, fy + h, z + oz, state)
            }
        }
    }

    /** Spalten, an denen die Außentreppe in die Galerie mündet (dort keine Brüstung, Belag aufgeschnitten). */
    private fun stairOpening(x: Int, z: Int): Boolean =
        x in STAIR_X0 + STAIR_RUN1 - STAIR_WIDTH until STAIR_X0 + STAIR_RUN1 && z in STAIR_Z0 + STAIR_WIDTH..STAIR_Z0 + STAIR_WIDTH + 2

    /**
     * Außentreppe zur Galerie im Hof westlich der Esse, am Hallenpfeiler bei 236°: Lauf 1 steigt entlang der Esse nach
     * Osten (Stufen +0 … +8), Lauf 2 wendet nach Süden in den Balken (+9, +10) und endet auf dem Belag (+11). Darunter
     * ein voller Treppenkörper mit Bronzeband, außen eine Brüstung. Liegt ganz außerhalb des Arena-Radius.
     */
    private fun galleryStair(c: SkyCanvas, fy: Int) {
        val wall: (Int, Int, Int) -> BlockState = { x, y, z ->
            if (y == fy + 4) s(HhaDecoBlocks.OLYMPUS_BRONZE) else if (Kit.noise(x, y, z) % 7 == 0) s(Blocks.CRACKED_DEEPSLATE_BRICKS) else s(Blocks.DEEPSLATE_BRICKS)
        }
        // Lauf 1: x STAIR_X0 … +8, z STAIR_Z0 … +2, Stufe k auf fy + k.
        for (k in 0 until STAIR_RUN1) for (w in 0 until STAIR_WIDTH) {
            val x = STAIR_X0 + k
            val z = STAIR_Z0 + w
            c.block(x, fy - 1, z, s(Blocks.POLISHED_DEEPSLATE))
            for (y in fy until fy + k) c.block(x, y, z, wall(x, y, z))
            c.block(x, fy + k, z, stair(Blocks.POLISHED_DEEPSLATE_STAIRS, Direction.EAST))
            for (h in 1..3) c.block(x, fy + k + h, z, Kit.AIR)
        }
        // Brüstung zu beiden Seiten, wo die Insel trägt und Lauf 2 nicht anschließt: Mauer eine Lage über der Stufe auf
        // vollem Unterbau.
        val run2X = STAIR_X0 + STAIR_RUN1 - STAIR_WIDTH until STAIR_X0 + STAIR_RUN1
        for (k in 3 until STAIR_RUN1) for (z in intArrayOf(STAIR_Z0 - 1, STAIR_Z0 + STAIR_WIDTH)) {
            val x = STAIR_X0 + k
            if (z == STAIR_Z0 + STAIR_WIDTH && x in run2X) continue
            parapet(c, x, z, fy, fy + k, wall)
        }
        // Lauf 2: x die letzten drei Stufen von Lauf 1, z STAIR_Z0 + 3 (+9) und + 4 (+10); der Belag des Balkens folgt.
        val top = STAIR_RUN1
        for (j in 0..1) for (w in 0 until STAIR_WIDTH) {
            val x = STAIR_X0 + STAIR_RUN1 - STAIR_WIDTH + w
            val z = STAIR_Z0 + STAIR_WIDTH + j
            for (y in fy until fy + top + j) c.block(x, y, z, wall(x, y, z))
            c.block(x, fy + top + j, z, stair(Blocks.POLISHED_DEEPSLATE_STAIRS, Direction.SOUTH))
            for (h in 1..3) c.block(x, fy + top + j + h, z, Kit.AIR)
        }
        // Brüstung an der Westseite von Lauf 2 (die Ostseite ist die Wand der Esse).
        for (j in 0..1) parapet(c, run2X.first - 1, STAIR_Z0 + STAIR_WIDTH + j, fy, fy + top + j, wall)
        // Laterne am Treppenfuß.
        c.block(STAIR_X0 - 1, fy, STAIR_Z0 + 1, s(Blocks.POLISHED_DEEPSLATE))
        c.block(STAIR_X0 - 1, fy + 1, STAIR_Z0 + 1, Kit.lantern(hanging = false, soul = false))
    }

    /** Brüstung neben einer Stufe auf [step]: voller Unterbau ab [fy], darüber eine Mauer. */
    private fun parapet(c: SkyCanvas, x: Int, z: Int, fy: Int, step: Int, wall: (Int, Int, Int) -> BlockState) {
        for (y in fy..step) c.block(x, y, z, wall(x, y, z))
        c.block(x, step + 1, z, Blocks.DEEPSLATE_BRICK_WALL.defaultState.with(WallBlock.UP, true))
    }

    // =============================================================================================
    // Esse
    // =============================================================================================

    /**
     * Die Esse im Norden: eine gedrungene Schmiede aus Tiefenschiefer mit einem Feuermaul nach Süden, dahinter der hohe
     * Schlot mit Kupferbändern, aus dem Rauch steigt (Landmarke). Das Innere zeichnet [forgeHall].
     */
    private fun forgeBuilding(c: SkyCanvas, fy: Int) {
        val fx = OlympusLayout.FORGE.x
        val front = OlympusLayout.D.center.z - 25
        val back = front - HALL_DEPTH
        for (x in fx - HALL_HALF..fx + HALL_HALF) for (z in back..front) {
            val wallX = abs(x - fx) == HALL_HALF
            val wallZ = z == front || z == back
            val inside = !wallX && !wallZ
            for (h in -1..13) {
                val y = fy + h
                val mouth = z >= front - 1 && abs(x - fx) <= 3 && h in 0..5 && hypot((x - fx).toDouble(), (h - 2.0) * 1.2) <= 4.2
                val state = when {
                    h == -1 -> s(Blocks.POLISHED_DEEPSLATE)
                    mouth -> Kit.AIR
                    inside && h in 0..HALL_CLEAR -> Kit.AIR
                    h == 13 -> if (inside || abs(x - fx) < HALL_HALF) s(Blocks.POLISHED_DEEPSLATE) else s(Blocks.DEEPSLATE_TILES)
                    h == 6 || h == 11 -> s(Blocks.WAXED_CUT_COPPER)
                    (wallX && wallZ) -> if (h % 2 == 0) s(Blocks.DEEPSLATE_TILES) else s(Blocks.POLISHED_DEEPSLATE)
                    Kit.noise(x, y, z) % 8 == 0 -> s(Blocks.CRACKED_DEEPSLATE_BRICKS)
                    else -> s(Blocks.DEEPSLATE_BRICKS)
                }
                c.block(x, y, z, state)
            }
        }
        // Gesims, schräges Dach nach hinten.
        val d = OlympusLayout.D
        for (x in fx - HALL_HALF - 1..fx + HALL_HALF + 1) {
            // Das Gesims kragt nur dort vor, wo es nicht über die Kampffläche und nicht über die Galerie ragt.
            if (hypot((x - d.center.x).toDouble(), (front + 1 - d.center.z).toDouble()) > d.arenaRadius + 0.5 && !galleryCell(x, front + 1)) {
                c.block(x, fy + 13, front + 1, stair(Blocks.DEEPSLATE_BRICK_STAIRS, Direction.NORTH, top = true))
            }
            for (k in 0..5) c.block(x, fy + 14 + k / 2, front - k * 2, if (k % 2 == 0) stair(Blocks.DEEPSLATE_TILE_STAIRS, Direction.NORTH) else s(Blocks.DEEPSLATE_TILES))
        }
        // Bogen über dem Feuermaul aus Kupfer, darüber das Kyklopenauge als runde Kupferscheibe.
        for (x in fx - 4..fx + 4) for (h in 0..7) {
            val r = hypot((x - fx).toDouble(), (h - 2.0) * 1.2)
            if (r in 4.2..5.3) c.block(x, fy + h, front, s(Blocks.WAXED_COPPER_BLOCK))
        }
        for (x in fx - 1..fx + 1) for (h in 8..10) c.block(x, fy + h, front, if (x == fx && h == 9) s(Blocks.MAGMA_BLOCK) else s(Blocks.WAXED_CHISELED_COPPER))
        // Der Schlot.
        val sz = back + 4
        for (h in 13..40) {
            val y = fy + h
            val half = when {
                h < 26 -> 3
                h < 36 -> 2
                else -> 1
            }
            for (x in fx - half..fx + half) for (z in sz - half..sz + half) {
                val rim = abs(x - fx) == half || abs(z - sz) == half
                val state = when {
                    !rim -> if (h == 39) s(Blocks.HAY_BLOCK) else if (h == 40) s(Blocks.CAMPFIRE).with(Properties.SIGNAL_FIRE, true) else Kit.AIR
                    h % 7 == 0 -> s(Blocks.WAXED_CUT_COPPER)
                    abs(x - fx) == half && abs(z - sz) == half -> s(Blocks.POLISHED_DEEPSLATE)
                    Kit.noise(x, y, z) % 9 == 0 -> s(Blocks.CRACKED_DEEPSLATE_BRICKS)
                    else -> s(Blocks.DEEPSLATE_BRICKS)
                }
                c.block(x, y, z, state)
            }
            if (h == 25 || h == 35) for (x in fx - half - 1..fx + half + 1) for (z in sz - half - 1..sz + half + 1) {
                if (abs(x - fx) == half + 1 || abs(z - sz) == half + 1) c.block(x, y, z, stair(Blocks.DEEPSLATE_BRICK_STAIRS, Kit.inward(x - fx, z - sz), top = true))
            }
        }
        // Krone: ein auskragender Ring (trägt) und darauf Zinnen im Wechsel; früher schwebten die Zinnen frei.
        for (x in fx - 2..fx + 2) for (z in sz - 2..sz + 2) {
            if (abs(x - fx) != 2 && abs(z - sz) != 2) continue
            c.block(x, fy + 40, z, stair(Blocks.WAXED_CUT_COPPER_STAIRS, Kit.inward(x - fx, z - sz), top = true))
            c.block(x, fy + 41, z, if ((x + z) % 2 == 0) s(Blocks.WAXED_CUT_COPPER) else Kit.AIR)
        }
    }

    /**
     * Schmiedehalle in der Esse (Mangel aus HB: leerer Kasten). Achse x = fx vom Feuermaul nach Norden:
     * Gussrinne (Gitter über Lava) im Boden → Esse mit Glut und Lava unter einer Bronzehaube, die in den Schlot führt →
     * dahinter die Lava hinter Gittern. Westlich der zweistufige Blasebalg mit Düse in die Esse und Hebel an einer Kette,
     * vorn das Kokillenlager; östlich das Löschbecken, Hochöfen, ein Ersatz-Amboss und die Kette mit Haken.
     * Die Mittelgasse (fx ± 2) bleibt bis +5 frei: Durch das Feuermaul sieht man Rinne, Esse, Haube und Blasebalg.
     */
    private fun forgeHall(c: SkyCanvas, fy: Int) {
        val fx = OlympusLayout.FORGE.x
        val front = OlympusLayout.D.center.z - 25
        val back = front - HALL_DEPTH
        // Boden: Fliesen, eine Bronzelinie entlang der Wände.
        for (x in fx - HALL_HALF + 1..fx + HALL_HALF - 1) for (z in back + 1..front - 1) {
            val border = abs(x - fx) == HALL_HALF - 1 || z == back + 1
            c.block(x, fy - 1, z, if (border) s(HhaDecoBlocks.OLYMPUS_BRONZE) else if (Kit.noise(x, 3, z) % 7 == 0) s(Blocks.CRACKED_DEEPSLATE_TILES) else s(Blocks.DEEPSLATE_TILES))
        }
        // Lava hinter Gittern an der Rückwand (wie bisher).
        for (x in fx - 3..fx + 3) for (h in 1..3) {
            c.block(x, fy + h, back, s(Blocks.LAVA))
            c.block(x, fy + h, back + 1, Kit.bars(alongX = true))
            c.block(x, fy + h, back - 1, s(Blocks.POLISHED_DEEPSLATE))
        }
        hearth(c, fx, fy, back)
        castingChannel(c, fx, fy, back + 8, OlympusLayout.FORGE.z - 2)
        bellows(c, fx - 9, fy, back + 3)
        moldStore(c, fx - 9, fy, front - 5)
        quenchTank(c, fx + 5, fy, back + 2)
        // Hochöfen an der Ostwand, ein Ersatz-Amboss (Phase II holt Brontes einen neuen aus der Esse) und ein Schleifstein.
        for (z in intArrayOf(front - 5, front - 3)) c.block(fx + HALL_HALF - 1, fy, z, s(Blocks.BLAST_FURNACE).with(Properties.HORIZONTAL_FACING, Direction.WEST))
        c.block(fx + 6, fy, front - 3, s(Blocks.POLISHED_BLACKSTONE_BRICKS))
        c.block(fx + 6, fy + 1, front - 3, Blocks.ANVIL.defaultState.with(Properties.HORIZONTAL_FACING, Direction.EAST))
        c.block(fx + 7, fy, front - 3, s(Blocks.POLISHED_BLACKSTONE_BRICKS))
        c.block(fx + 6, fy, front - 5, s(Blocks.GRINDSTONE))
        // Kette mit Haken (Blitzableiter als Haken) über dem Löschbecken-Vorplatz.
        for (h in 4..HALL_CLEAR) c.block(fx + 5, fy + h, front - 7, Kit.chain())
        c.block(fx + 5, fy + 3, front - 7, Blocks.WAXED_LIGHTNING_ROD.defaultState.with(Properties.FACING, Direction.DOWN))
        // Licht an den Seiten (die Mittelgasse bleibt frei).
        for (x in intArrayOf(fx - 6, fx + 7)) {
            for (h in 8..HALL_CLEAR) c.block(x, fy + h, front - 4, Kit.chain())
            c.block(x, fy + 7, front - 4, Kit.lantern(hanging = true, soul = false))
        }
    }

    /**
     * Die Esse: Herd 7 × 6 (Rand aus Schwarzsteinziegeln, zwei hoch), darin Glut und ein Lavabecken; darüber eine
     * zweistufige Haube aus Olymp-Bronze, durch die Decke offen in den Schlot.
     */
    private fun hearth(c: SkyCanvas, fx: Int, fy: Int, back: Int) {
        val z0 = back + 2
        val z1 = back + 7
        for (x in fx - 3..fx + 3) for (z in z0..z1) {
            val rim = abs(x - fx) == 3 || z == z0 || z == z1
            c.block(x, fy, z, if (rim) s(Blocks.POLISHED_BLACKSTONE_BRICKS) else s(Blocks.MAGMA_BLOCK))
            val pool = abs(x - fx) <= 1 && z in z0 + 2..z1 - 2
            c.block(x, fy + 1, z, when {
                rim -> if (abs(x - fx) == 3 && (z == z0 || z == z1)) s(Blocks.CHISELED_POLISHED_BLACKSTONE) else s(Blocks.POLISHED_BLACKSTONE_BRICKS)
                pool -> s(Blocks.LAVA)
                else -> s(Blocks.MAGMA_BLOCK)
            })
        }
        // Haube: zwei Ringe Bronzetreppen (kopfüber) unter der Decke, darüber offen bis in den Schlot.
        val sz = back + 4
        for (x in fx - 4..fx + 4) for (z in sz - 4..sz + 4) {
            val ringOuter = abs(x - fx) == 4 || abs(z - sz) == 4
            val ringInner = abs(x - fx) == 3 || abs(z - sz) == 3
            if (z <= back) continue
            if (ringOuter) c.block(x, fy + 8, z, stair(HhaDecoBlocks.OLYMPUS_BRONZE_STAIRS, Kit.inward(x - fx, z - sz).opposite, top = true))
            if (ringOuter || ringInner) c.block(x, fy + 9, z, if (ringOuter) s(HhaDecoBlocks.OLYMPUS_BRONZE) else stair(HhaDecoBlocks.OLYMPUS_BRONZE_STAIRS, Kit.inward(x - fx, z - sz).opposite, top = true))
        }
        for (x in fx - 2..fx + 2) for (z in sz - 2..sz + 2) for (h in HALL_CLEAR..12) c.block(x, fy + h, z, Kit.AIR)
    }

    /** Gussrinne von der Esse zum Feuermaul: in der Achse ein Kupfergitter über Lava, gefasst in Schwarzstein. */
    private fun castingChannel(c: SkyCanvas, fx: Int, fy: Int, fromZ: Int, toZ: Int) {
        for (z in fromZ..toZ) {
            c.block(fx, fy - 1, z, s(Blocks.WAXED_COPPER_GRATE))
            c.block(fx, fy - 2, z, s(Blocks.LAVA))
            c.block(fx, fy - 3, z, s(Blocks.POLISHED_DEEPSLATE))
            for (side in intArrayOf(-1, 1)) {
                c.block(fx + side, fy - 2, z, s(Blocks.POLISHED_DEEPSLATE))
                c.block(fx + side, fy - 1, z, s(Blocks.POLISHED_BLACKSTONE_BRICKS))
            }
        }
        c.block(fx, fy - 2, fromZ - 1, s(Blocks.POLISHED_DEEPSLATE))
    }

    /**
     * Blasebalg, zwei Stufen groß: zwei Bälge übereinander (Bretter aus dunkler Eiche, dazwischen Leder), die Düse aus
     * Kupfer zeigt nach Osten in die Esse; obenauf der Hebelbalken, der an einer Kette von der Decke hängt.
     * (x, z) = Westende, Mitte der drei Reihen.
     */
    private fun bellows(c: SkyCanvas, x: Int, fy: Int, z: Int) {
        for (dz in -1..1) {
            for (k in 0..4) c.block(x + k, fy, z + dz, s(Blocks.DARK_OAK_PLANKS))
            for (k in 0..3) c.block(x + k, fy + 1, z + dz, s(Blocks.BROWN_WOOL))
            c.block(x + 4, fy + 1, z + dz, if (dz == 0) s(Blocks.WAXED_CUT_COPPER) else stair(Blocks.DARK_OAK_STAIRS, Direction.WEST, top = true))
            for (k in 0..4) c.block(x + k, fy + 2, z + dz, if (k == 4) slab(Blocks.DARK_OAK_SLAB) else s(Blocks.DARK_OAK_PLANKS))
            for (k in 0..2) c.block(x + k, fy + 3, z + dz, s(Blocks.BROWN_WOOL))
            c.block(x + 3, fy + 3, z + dz, stair(Blocks.DARK_OAK_STAIRS, Direction.WEST, top = true))
            for (k in 0..3) c.block(x + k, fy + 4, z + dz, slab(Blocks.DARK_OAK_SLAB))
        }
        c.block(x + 5, fy + 1, z, Blocks.WAXED_LIGHTNING_ROD.defaultState.with(Properties.FACING, Direction.EAST))
        for (k in 0..4) c.block(x + k, fy + 5, z, s(Blocks.STRIPPED_DARK_OAK_LOG).with(Properties.AXIS, Direction.Axis.X))
        for (h in 6..HALL_CLEAR) c.block(x + 2, fy + h, z, Kit.chain())
    }

    /** Kokillenlager: zwei Reihen Gussformen auf Bohlen, die meisten mit Deckel, eine frisch gegossen (Glut). */
    private fun moldStore(c: SkyCanvas, x: Int, fy: Int, z: Int) {
        for (k in 0..2) for (row in 0..1) {
            val mx = x + k * 2
            val mz = z + row * 2
            c.block(mx, fy, mz, s(HhaDecoBlocks.OLYMPUS_BRONZE))
            c.block(mx, fy + 1, mz, if (k == 1 && row == 0) s(HhaDecoBlocks.EMBER_BASALT) else slab(HhaDecoBlocks.OLYMPUS_BRONZE_SLAB))
            if (k < 2) c.block(mx + 1, fy, mz, slab(Blocks.DARK_OAK_SLAB))
        }
    }

    /** Löschbecken: Becken 5 × 4 aus Schwarzsteinziegeln mit Bronzekante, Wasser auf Bodenhöhe. (x, z) = Nordwestecke. */
    private fun quenchTank(c: SkyCanvas, x: Int, fy: Int, z: Int) {
        for (dx in 0..4) for (dz in 0..3) {
            val rim = dx == 0 || dx == 4 || dz == 0 || dz == 3
            c.block(x + dx, fy - 1, z + dz, s(Blocks.POLISHED_BLACKSTONE_BRICKS))
            c.block(x + dx, fy, z + dz, if (rim) s(Blocks.POLISHED_BLACKSTONE_BRICKS) else s(Blocks.WATER))
            if (rim) c.block(x + dx, fy + 1, z + dz, slab(HhaDecoBlocks.OLYMPUS_BRONZE_SLAB))
        }
    }

    // =============================================================================================
    // Hof
    // =============================================================================================

    /** Hof: Löschtrog im Osten (wie bisher), zweites Wasserbecken im Süden, Schlackehaufen, Erzkarren, Werkzeug. */
    private fun yard(c: SkyCanvas, fy: Int) {
        // Löschtrog im Osten.
        trough(c, 179, -395, 183, -385, fy)
        // Zweites Wasserbecken im Süden, östlich des Hammers.
        trough(c, 158, -363, 162, -360, fy)
        slagHeap(c, 121, -406, fy)
        slagHeap(c, 168, -370, fy)
        slagHeap(c, 125, -380, fy)
        oreCart(c, 132, -365, fy)
        // Ein Stapel Rohlinge und Werkzeug am Rand.
        for ((x, z) in listOf(176 to -402, 177 to -402, 176 to -403)) c.block(x, fy, z, s(Blocks.IRON_BLOCK))
        c.block(176, fy + 1, -402, s(Blocks.WAXED_COPPER_BLOCK))
        c.block(123, fy, -404, s(Blocks.GRINDSTONE))
        c.block(122, fy, -400, s(Blocks.SMITHING_TABLE))
        c.block(124, fy, -409, s(Blocks.COAL_BLOCK))
        c.block(125, fy, -409, s(Blocks.COAL_BLOCK))
        c.block(124, fy + 1, -409, s(Blocks.COAL_BLOCK))
    }

    /** Wasserbecken mit Rand aus Tiefenschiefer und Kupferkante (x0, z0 … x1, z1 eingeschlossen). */
    private fun trough(c: SkyCanvas, x0: Int, z0: Int, x1: Int, z1: Int, fy: Int) {
        for (x in x0..x1) for (z in z0..z1) {
            val edge = x == x0 || x == x1 || z == z0 || z == z1
            c.block(x, fy - 1, z, s(Blocks.POLISHED_DEEPSLATE))
            c.block(x, fy, z, if (edge) s(Blocks.POLISHED_DEEPSLATE) else s(Blocks.WATER))
            c.block(x, fy + 1, z, if (edge) (if ((x + z) % 2 == 0) slab(Blocks.WAXED_CUT_COPPER_SLAB) else s(Blocks.WAXED_CUT_COPPER)) else s(Blocks.WATER))
        }
    }

    /** Schlackehaufen: flacher Kegel 3 × 3 aus Schwarzstein und Basalt, oben ein glimmender Brocken. (x, z) = Mitte. */
    private fun slagHeap(c: SkyCanvas, x: Int, z: Int, fy: Int) {
        for (ox in -1..1) for (oz in -1..1) {
            val corner = ox != 0 && oz != 0
            val n = Kit.noise(x + ox, fy, z + oz)
            c.block(x + ox, fy, z + oz, when {
                corner -> slab(if (n % 2 == 0) Blocks.BLACKSTONE_SLAB else Blocks.POLISHED_BLACKSTONE_SLAB)
                n % 3 == 0 -> s(Blocks.BASALT)
                else -> s(Blocks.BLACKSTONE)
            })
        }
        c.block(x, fy + 1, z, s(Blocks.MAGMA_BLOCK))
        c.block(x + 1, fy + 1, z, slab(Blocks.BLACKSTONE_SLAB))
        c.block(x, fy + 1, z - 1, stair(Blocks.BLACKSTONE_STAIRS, Direction.SOUTH))
    }

    /** Erzkarren: Kasten aus dunkler Eiche auf zwei Achsen (Schleifsteine als Räder), beladen mit Roherz; Deichsel nach Westen. */
    private fun oreCart(c: SkyCanvas, x: Int, z: Int, fy: Int) {
        for (k in 0..2) {
            c.block(x + k, fy, z, if (k == 1) s(Blocks.STRIPPED_DARK_OAK_LOG).with(Properties.AXIS, Direction.Axis.X) else s(Blocks.GRINDSTONE))
            c.block(x + k, fy, z + 1, if (k == 1) s(Blocks.STRIPPED_DARK_OAK_LOG).with(Properties.AXIS, Direction.Axis.X) else s(Blocks.GRINDSTONE))
            c.block(x + k, fy + 1, z, s(Blocks.DARK_OAK_PLANKS))
            c.block(x + k, fy + 1, z + 1, s(Blocks.DARK_OAK_PLANKS))
        }
        c.block(x, fy + 2, z, s(Blocks.RAW_IRON_BLOCK))
        c.block(x + 1, fy + 2, z, s(Blocks.RAW_COPPER_BLOCK))
        c.block(x + 1, fy + 2, z + 1, s(Blocks.RAW_IRON_BLOCK))
        c.block(x + 2, fy + 2, z + 1, slab(Blocks.COBBLED_DEEPSLATE_SLAB))
        c.block(x - 1, fy + 1, z, s(Blocks.DARK_OAK_FENCE))
        c.block(x - 1, fy, z, s(Blocks.DARK_OAK_FENCE))
    }

    /**
     * Der Hammer eines Kyklopen, abgelegt im Hof: Stiel aus dunklem Holz mit Eisenringen, Kopf aus Schwarzstein mit
     * Bahnen aus Eisen. Liegt entlang x; (x, z) ist das Stielende.
     */
    private fun giantHammer(c: SkyCanvas, x: Int, fy: Int, z: Int) {
        for (i in 0 until 10) c.block(x + i, fy, z, if (i % 4 == 1) s(Blocks.IRON_BLOCK) else s(Blocks.STRIPPED_DARK_OAK_LOG).with(Properties.AXIS, Direction.Axis.X))
        for (i in 10..12) for (dz in -2..2) for (h in 0..2) {
            val face = i == 10 || i == 12
            val state = when {
                face && abs(dz) <= 1 && h <= 1 -> s(Blocks.IRON_BLOCK)
                abs(dz) == 2 && h == 2 -> stair(Blocks.POLISHED_BLACKSTONE_STAIRS, if (dz < 0) Direction.SOUTH else Direction.NORTH, top = false)
                else -> s(Blocks.POLISHED_BLACKSTONE)
            }
            c.block(x + i, fy + h, z + dz, state)
        }
    }
}
