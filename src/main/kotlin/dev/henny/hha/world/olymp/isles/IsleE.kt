package dev.henny.hha.world.olymp.isles

import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.world.DecoPalettes
import dev.henny.hha.world.Kit
import dev.henny.hha.world.block
import net.minecraft.block.Blocks
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.abs
import kotlin.math.roundToInt
import dev.henny.hha.world.olymp.*
import dev.henny.hha.world.olymp.OlympKit.altar
import dev.henny.hha.world.olymp.OlympKit.angleDiff
import dev.henny.hha.world.olymp.OlympKit.angleOf
import dev.henny.hha.world.olymp.OlympKit.brazier
import dev.henny.hha.world.olymp.OlympKit.column
import dev.henny.hha.world.olymp.OlympKit.entablature
import dev.henny.hha.world.olymp.OlympKit.griffinColumn
import dev.henny.hha.world.olymp.OlympKit.pediment
import dev.henny.hha.world.olymp.OlympKit.polar
import dev.henny.hha.world.olymp.OlympKit.rimBalustrade
import dev.henny.hha.world.olymp.OlympKit.s
import dev.henny.hha.world.olymp.OlympKit.slab
import dev.henny.hha.world.olymp.OlympKit.stair
import dev.henny.hha.world.olymp.OlympKit.stele
import dev.henny.hha.world.olymp.OlympKit.wall
import dev.henny.hha.world.olymp.OlympusPlans.bridgeHeads

/**
 * Bauplan der Insel E, Stufen der Macht (Überarbeitung 4, P08; vorher aus `OlympusPlans` herausgelöst, P00).
 * Aufruf über `OlympusPlans.draw`.
 *
 * Arena (Vertrag `OlympusArenas.E`): Kern r 24 aus poliertem Olymp-Marmor mit rotem Läufer, zwei Sockel (der linke,
 * Bias, als eigener Körper), vier Standarten 2 × 2 × 4 als Deckung, der Treppenabsatz auf +4 als Ebene im
 * Zusatzkasten `E_EXTRA`. Außerhalb: Kolonnade mit Pfeilern und Standarten, Freitreppe zur Terrasse, deren Unterbau
 * eine gegliederte Fassade bekommt (Lisenen, Blendbögen, Bronzeband, Marmorsockel).
 */
internal object IsleE {

    // ---------------------------------------------------------------------------------------------
    // Benannte Maße des Bauwerks (Deckung, Absatz und Arena-Kasten stehen im Vertrag OlympusArenas.E)
    // ---------------------------------------------------------------------------------------------

    /** Ringband des Kerns (Blockring 12) aus gemeißeltem Olymp-Marmor. */
    private const val RING_BAND = 12.0
    /** Kolonnade: Ring der Säulen, Winkelschritt; jede [PIER_EVERY]-te Säule wird ein Pfeiler mit Standarte. */
    private const val COLONNADE_RING = 28.5
    private const val COLONNADE_STEP = 11.5
    private const val PIER_EVERY = 4
    private const val COLUMN_HEIGHT = 10
    /** Fassade des Unterbaus: Lisene alle [LISENE_STEP] Blöcke, Sockel bis [SOCKEL_TOP], Bronzeband auf [BAND_Y], Blendbögen. */
    private const val LISENE_STEP = 5
    private const val SOCKEL_TOP = 193
    private const val BAND_Y = 197
    private const val ARCH_FROM = 198
    private const val ARCH_TOP = 201
    /** Treppe: halbe Breite der Läufe; Wangen ab [CHEEK]. */
    private const val STAIR_HALF = 12
    private const val CHEEK = 11
    /** Obere Terrasse: x-Ausdehnung und Tiefe. */
    private const val TERRACE_X0 = 136
    private const val TERRACE_X1 = 164
    private const val TERRACE_DEPTH = 7

    fun draw(c: SkyCanvas) {
        val isle = OlympusLayout.E
        val fy = isle.floorY
        val cx = isle.center.x
        val cz = isle.center.z
        val arena = isle.arenaRadius.toDouble()
        val body = OlympusIsles.Body(cx, fy - 1, cz, isle.radius.toDouble(), IsleKind.MARBLE, 55, 0.92)
        OlympusIsles.body(c, body)
        // Ausläufer nach Norden unter der Treppe und der oberen Terrasse.
        OlympusIsles.body(c, OlympusIsles.Body(cx, fy - 1, OlympusLayout.E_STAIRS_TOP_Z + 2, 14.0, IsleKind.MARBLE, 56, 0.88))
        bridgeHeads(c, isle)
        arenaFloor(c, fy, arena)
        biaPlinth(c)
        rightPlinth(c)
        for (spot in OlympusArenas.E.cover) if (spot.id.startsWith("standard_")) standardBody(c, spot.box)
        altar(c, isle.altar!!, 2)
        grandStairs(c, fy)
        colonnade(c, fy, body, arena)
        // Ein Greif auf der Säule im Südosten, außerhalb der Kolonnade.
        griffinColumn(c, 178, fy, -545)
        rimBalustrade(c, body, fy, Blocks.DIORITE_WALL, s(Blocks.QUARTZ_PILLAR)) { x, z ->
            (x in 158..174 && z > cz + 22) || z < OlympusLayout.E_STAIRS_FOOT_Z + 6
        }
        // Roter Läufer in der Lücke der Kolonnade (außerhalb der Kampffläche, darum echte Teppiche).
        for (x in cx - 1..cx + 1) for (z in cz + 26..cz + 30) c.block(x, fy, z, s(Blocks.RED_CARPET))
        stele(c, 155, fy, -530, 12, Direction.SOUTH)
        OlympusIsles.cloudSkirt(c, body, 5)
        OlympusIsles.cloud(c, cx - 48, 160, cz + 16, 20.0, 12.0, 57, 3.0)
        OlympusIsles.cloud(c, cx + 42, 168, cz - 34, 16.0, 11.0, 58, 2.8)
    }

    // =============================================================================================
    // Arena
    // =============================================================================================

    /**
     * Kampffläche: polierter Olymp-Marmor (Mischung `MARBLE_FLOOR`), der rote Läufer von Süd nach Nord mit Quarzkanten,
     * ein Ringband aus gemeißeltem Marmor auf Ring 12, außen ein Rand aus Marmorziegeln.
     */
    private fun arenaFloor(c: SkyCanvas, fy: Int, arena: Double) {
        val cx = OlympusLayout.E.center.x
        val cz = OlympusLayout.E.center.z
        c.disc(cx, cz, arena + 0.5) { x, z, r ->
            val state = when {
                abs(x - cx) <= 1 -> s(Blocks.RED_TERRACOTTA)
                abs(x - cx) == 2 -> s(Blocks.CHISELED_QUARTZ_BLOCK)
                r > arena - 0.5 -> s(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS)
                abs(r - RING_BAND) <= 0.5 -> s(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE)
                else -> DecoPalettes.MARBLE_FLOOR(x, fy - 1, z)
            }
            c.put(x, fy - 1, z, state)
            for (h in 0..30) c.put(x, fy + h, z, Kit.AIR)
        }
    }

    /**
     * Sockel 3 × 3 × 2: unten Marmorziegel mit vergoldeten Ecken (Gold an der Deckung), oben polierter Marmor mit
     * gemeißelter Mitte; im Boden ein Rahmen aus gemeißeltem Marmor, damit der Körper eine klare Kante hat.
     */
    private fun plinthBody(c: SkyCanvas, p: BlockPos) {
        val half = OlympusLayout.PLINTH_HALF
        for (ox in -half - 1..half + 1) for (oz in -half - 1..half + 1) {
            if (abs(ox) <= half && abs(oz) <= half) continue
            c.block(p.x + ox, p.y - 1, p.z + oz, s(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE))
        }
        for (ox in -half..half) for (oz in -half..half) {
            val corner = abs(ox) == half && abs(oz) == half
            c.block(p.x + ox, p.y, p.z + oz, if (corner) s(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE) else s(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS))
            for (h in 1 until OlympusLayout.PLINTH_HEIGHT) {
                c.block(p.x + ox, p.y + h, p.z + oz, if (ox == 0 && oz == 0) s(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE) else s(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE))
            }
        }
    }

    /** Linker Sockel (Deckung `plinth_left`, verbrauchbar): Bias Statue, zerspringt im Kampf. Eigener Körper. */
    private fun biaPlinth(c: SkyCanvas) = plinthBody(c, OlympusLayout.PLINTH_LEFT)

    /** Rechter Sockel (Deckung `plinth_right`, fest), leer. */
    private fun rightPlinth(c: SkyCanvas) = plinthBody(c, OlympusLayout.PLINTH_RIGHT)

    /**
     * Standarte (Deckung `standard_*`, verbrauchbar): Pfeiler 2 × 2, massiv bis +4 (Marmorsockel, Schaft aus
     * Olymp-Bronze). Darüber, außerhalb der Deckungshöhe, ein rotes Tuch zur Mitte hin unter einem Querholz aus Bronze,
     * dahinter der Mast mit Bronzespitze.
     */
    private fun standardBody(c: SkyCanvas, box: BlockBox) {
        val cx = OlympusLayout.E.center.x
        val cz = OlympusLayout.E.center.z
        for (x in box.minX..box.maxX) for (z in box.minZ..box.maxZ) for (y in box.minY..box.maxY) {
            c.block(x, y, z, if (y == box.minY) s(HhaDecoBlocks.OLYMPUS_MARBLE) else s(HhaDecoBlocks.OLYMPUS_BRONZE))
        }
        // Die zur Mitte zeigende Reihe trägt das Tuch, die äußere den Mast.
        val toward = Kit.inward(
            ((box.minX + box.maxX + 1) / 2.0 - (cx + 0.5)).roundToInt(),
            ((box.minZ + box.maxZ + 1) / 2.0 - (cz + 0.5)).roundToInt(),
        )
        val cells = ArrayList<Pair<Int, Int>>()
        for (x in box.minX..box.maxX) for (z in box.minZ..box.maxZ) cells.add(x to z)
        val score = { p: Pair<Int, Int> -> p.first * toward.offsetX + p.second * toward.offsetZ }
        val best = cells.maxOf(score)
        val inner = cells.filter { score(it) == best }
        val outer = cells.filter { score(it) != best }
        val top = box.maxY
        val mast = outer.first()
        for (h in 1..4) c.block(mast.first, top + h, mast.second, s(HhaDecoBlocks.OLYMPUS_BRONZE))
        c.block(mast.first, top + 5, mast.second, Blocks.WAXED_LIGHTNING_ROD.defaultState.with(Properties.FACING, Direction.UP))
        for ((x, z) in inner) {
            for (h in 1..3) c.block(x, top + h, z, s(Blocks.RED_WOOL))
            c.block(x, top + 4, z, s(HhaDecoBlocks.OLYMPUS_BRONZE))
        }
    }

    // =============================================================================================
    // Kolonnade
    // =============================================================================================

    /**
     * Kolonnade im Ring um die Fläche, offen nach Süden (Eingang) und nach Norden (Treppe). Rhythmus: jede vierte Säule
     * ist ein Pfeiler mit Standarte (rotes Tuch zur Mitte, Bronzespitze über dem Gebälk).
     */
    private fun colonnade(c: SkyCanvas, fy: Int, body: OlympusIsles.Body, arena: Double) {
        val cx = OlympusLayout.E.center.x
        val cz = OlympusLayout.E.center.z
        c.disc(cx, cz, 34.0) { x, z, r ->
            if (r <= arena + 0.5 || !body.rim.containsCell(x, z)) return@disc
            if (z < OlympusLayout.E_STAIRS_FOOT_Z && abs(x - cx) <= STAIR_HALF) return@disc
            c.put(x, fy - 1, z, if (r in 27.0..30.0) s(Blocks.SMOOTH_QUARTZ) else if (r > 33.0) s(Blocks.QUARTZ_BRICKS) else s(Blocks.POLISHED_DIORITE))
        }
        val angles = ArrayList<Double>()
        var deg = -58.0
        while (deg < 239.0) {
            if (angleDiff(deg, 90.0) > 9.0) angles.add(deg)
            deg += COLONNADE_STEP
        }
        for ((i, d) in angles.withIndex()) {
            val (px, pz) = polar(cx, cz, COLONNADE_RING, d)
            if (i % PIER_EVERY == 0) standardPier(c, px, fy, pz, Kit.inward(px - cx, pz - cz)) else column(c, px, fy, pz, COLUMN_HEIGHT)
        }
        c.disc(cx, cz, 30.5) { x, z, r ->
            if (r < 26.5) return@disc
            val a = angleOf(x - cx.toDouble(), z - cz.toDouble())
            if (a > 238.0 + 3 && a < 360.0 - 58.0 - 3) return@disc
            val y = fy + COLUMN_HEIGHT
            c.put(x, y, z, s(Blocks.SMOOTH_QUARTZ))
            c.put(x, y + 1, z, if (r in 27.5..29.5) s(Blocks.RED_TERRACOTTA) else s(Blocks.QUARTZ_BRICKS))
            c.put(x, y + 2, z, when {
                r > 29.8 -> stair(Blocks.SMOOTH_QUARTZ_STAIRS, Kit.inward(x - cx, z - cz), top = true)
                r < 27.2 -> stair(Blocks.SMOOTH_QUARTZ_STAIRS, Kit.inward(x - cx, z - cz).opposite, top = true)
                else -> s(Blocks.SMOOTH_QUARTZ)
            })
        }
        for ((i, d) in angles.withIndex()) {
            if (i % 2 == 1 || i + 1 >= angles.size) continue
            val (lx, lz) = polar(cx, cz, COLONNADE_RING, (d + angles[i + 1]) / 2)
            c.block(lx, fy + 9, lz, Kit.lantern(hanging = true, soul = false))
        }
        // Bronzespitzen der Pfeiler über dem Gebälk.
        for ((i, d) in angles.withIndex()) {
            if (i % PIER_EVERY != 0) continue
            val (px, pz) = polar(cx, cz, COLONNADE_RING, d)
            c.block(px, fy + COLUMN_HEIGHT + 3, pz, s(HhaDecoBlocks.OLYMPUS_BRONZE))
            c.block(px, fy + COLUMN_HEIGHT + 4, pz, Blocks.WAXED_LIGHTNING_ROD.defaultState.with(Properties.FACING, Direction.UP))
        }
    }

    /**
     * Pfeiler mit Standarte an Stelle einer Säule: 3 × 3, Sockel aus Olymp-Marmor, Schaft aus Quarzziegeln mit
     * Marmorsäulen an den Ecken, Bronzeband; zur Mitte ([inward]) hängt ein rotes Tuch an einem Bronzearm.
     */
    private fun standardPier(c: SkyCanvas, x: Int, fy: Int, z: Int, inward: Direction) {
        for (ox in -1..1) for (oz in -1..1) {
            val corner = ox != 0 && oz != 0
            for (h in 0 until COLUMN_HEIGHT) {
                val state = when {
                    h == 0 -> s(HhaDecoBlocks.OLYMPUS_MARBLE)
                    h == COLUMN_HEIGHT - 1 -> if (corner) stair(Blocks.QUARTZ_STAIRS, Kit.inward(ox, oz), top = true) else s(Blocks.CHISELED_QUARTZ_BLOCK)
                    h == 5 -> s(HhaDecoBlocks.OLYMPUS_BRONZE)
                    corner -> s(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR)
                    else -> s(Blocks.QUARTZ_BRICKS)
                }
                c.block(x + ox, fy + h, z + oz, state)
            }
        }
        val tx = x + inward.offsetX * 2
        val tz = z + inward.offsetZ * 2
        c.block(tx, fy + 8, tz, s(HhaDecoBlocks.OLYMPUS_BRONZE))
        for (h in 4..7) c.block(tx, fy + h, tz, s(Blocks.RED_WOOL))
    }

    // =============================================================================================
    // Freitreppe, Absatz und Terrasse
    // =============================================================================================

    /** Höhe des Treppenblocks in der Reihe [z]: erster Lauf (Arena), Absatz `E_LANDING`, oberer Lauf zur Terrasse. */
    private fun stairY(z: Int, fy: Int): Int {
        val foot = OlympusLayout.E_STAIRS_FOOT_Z
        val landing = OlympusArenas.E_LANDING
        val landingY = fy + OlympusArenas.E_LANDING_HEIGHT - 1
        val k = (foot - 1) - z
        return when {
            z > landing.maxZ -> fy + k
            z >= landing.minZ -> landingY
            else -> landingY + (landing.minZ - z)
        }
    }

    /**
     * Die Freitreppe nach Norden. Der erste Lauf (drei Stufen) und der Absatz auf +4 gehören zur Arena (Vertrag
     * `E_LANDING`, Zugang `landing.access`): Belag poliert, vergoldete Kante an der Stufe zum Kern, Wangen mit Brüstung
     * 1 hoch (kein Sichtschutz), zwei Feuerschalen an den Enden, am Ende ein gemeißeltes Band. Danach ein Lauf von acht
     * Stufen zur oberen Terrasse (Säulenfront mit Giebel, Westende = Brücke zum Thron). Der Unterbau bekommt eine
     * Fassade ([facade]).
     */
    private fun grandStairs(c: SkyCanvas, fy: Int) {
        val cx = OlympusLayout.E.center.x
        val foot = OlympusLayout.E_STAIRS_FOOT_Z
        val top = OlympusLayout.E_STAIRS_TOP_Z
        val landing = OlympusArenas.E_LANDING
        val landingY = fy + OlympusArenas.E_LANDING_HEIGHT - 1
        val topFloor = stairY(top, fy)
        val wallPal = DecoPalettes.marbleWall(0.0)
        for (z in top..foot - 1) {
            val onLanding = z in landing.minZ..landing.maxZ
            val ly = stairY(z, fy)
            val arenaPart = z >= landing.minZ
            for (x in cx - STAIR_HALF..cx + STAIR_HALF) {
                val dx = abs(x - cx)
                val cheek = dx >= CHEEK
                for (yy in fy - 2 until ly) c.block(x, yy, z, if (Kit.noise(x, yy, z) % 6 == 0) s(Blocks.CALCITE) else s(Blocks.QUARTZ_BRICKS))
                if (cheek && arenaPart) {
                    // Wangen im Arenateil: oben bündig mit Stufe bzw. Absatz, außen eine Brüstung 1 hoch.
                    c.block(x, ly, z, if (onLanding) s(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE) else wallPal(x, ly, z))
                    c.block(x, ly + 1, z, if (dx == STAIR_HALF) wall(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_WALL, alongX = false) else Kit.AIR)
                    for (h in 2..6) c.block(x, ly + h, z, Kit.AIR)
                    continue
                }
                if (cheek) {
                    for (yy in ly..ly + 1) c.block(x, yy, z, if (yy == ly + 1) s(Blocks.RED_TERRACOTTA) else s(Blocks.QUARTZ_BRICKS))
                    c.block(x, ly + 2, z, if (dx == STAIR_HALF) stair(Blocks.SMOOTH_QUARTZ_STAIRS, if (x < cx) Direction.EAST else Direction.WEST, top = false) else slab(Blocks.SMOOTH_QUARTZ_SLAB))
                    continue
                }
                val state = when {
                    !onLanding -> stair(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE_STAIRS, Direction.NORTH)
                    dx <= 1 -> s(Blocks.RED_TERRACOTTA)
                    z == landing.maxZ -> s(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE)
                    z == landing.minZ -> s(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE)
                    else -> s(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE)
                }
                c.block(x, ly, z, state)
                for (h in 1..6) c.block(x, ly + h, z, Kit.AIR)
            }
        }
        // Pylonen mit Laternen am Treppenfuß.
        for (sx in intArrayOf(-STAIR_HALF, CHEEK)) {
            for (x in cx + sx..cx + sx + 1) for (h in 0..4) c.block(x, fy + h, foot - 1, if (h == 4) s(Blocks.CHISELED_QUARTZ_BLOCK) else if (h == 2) s(Blocks.RED_TERRACOTTA) else s(Blocks.QUARTZ_BRICKS))
            c.block(cx + sx + (if (sx < 0) 0 else 1), fy + 5, foot - 1, Kit.lantern(hanging = false, soul = false))
        }
        // Feuerschalen an den Enden des Absatzes.
        val midZ = (landing.minZ + landing.maxZ) / 2
        brazier(c, cx - CHEEK + 1, landingY + 1, midZ)
        brazier(c, cx + CHEEK - 1, landingY + 1, midZ)
        // Obere Terrasse.
        val t0 = top - 1
        val t1 = top - TERRACE_DEPTH
        for (x in TERRACE_X0..TERRACE_X1) for (z in t1..t0) {
            for (yy in fy - 2 until topFloor) c.block(x, yy, z, s(Blocks.QUARTZ_BRICKS))
            c.block(x, topFloor, z, if (abs(x - cx) <= 1) s(Blocks.RED_TERRACOTTA) else if ((x + z) % 7 == 0) s(Blocks.CALCITE) else s(Blocks.POLISHED_DIORITE))
            for (h in 1..14) c.block(x, topFloor + h, z, Kit.AIR)
            val edgeN = z == t1
            val edgeE = x == TERRACE_X1
            if (edgeN || edgeE) c.block(x, topFloor + 1, z, if (Math.floorMod(x + z, 4) == 0) s(Blocks.QUARTZ_PILLAR) else wall(Blocks.DIORITE_WALL, alongX = edgeN))
        }
        // Säulenfront: fünf Säulen, Gebälk mit rotem Fries, Giebel nach Süden.
        val fz = t1 + 2
        for (x in intArrayOf(cx - 10, cx - 5, cx, cx + 5, cx + 10)) column(c, x, topFloor + 1, fz, 10)
        entablature(c, cx - 11, fz - 1, cx + 11, fz + 1, topFloor + 11, s(Blocks.RED_TERRACOTTA))
        pediment(c, cx, topFloor + 14, fz + 1, Direction.SOUTH, 12, 3)
        // Feuerschalen an den Enden der Terrasse.
        brazier(c, cx - 11, topFloor + 1, t0 - 1)
        brazier(c, cx + 11, topFloor + 1, t0 - 1)
        // Fassade des Unterbaus (Mangel aus HB): Nord- und Ostseite der Terrasse, Außenseiten der Treppenwangen.
        // Die Westseite der Terrasse bleibt glatt: Dort setzt die Brücke zum Thron an (Streifen ±6 frei für P06).
        for (x in TERRACE_X0..TERRACE_X1) facade(c, x, t1, topFloor, Direction.NORTH, Direction.EAST, x - cx, TERRACE_X0 - cx, TERRACE_X1 - cx, fy)
        for (z in t1..t0) facade(c, TERRACE_X1, z, topFloor, Direction.EAST, Direction.SOUTH, z - t1, 0, t0 - t1, fy)
        val rows = foot - 1 - top
        for (z in top..foot - 1) {
            val k = (foot - 1) - z
            val faceTop = if (z >= landing.minZ) stairY(z, fy) else stairY(z, fy) + 1
            facade(c, cx - STAIR_HALF, z, faceTop, Direction.WEST, Direction.NORTH, k, 0, rows, fy)
            facade(c, cx + STAIR_HALF, z, faceTop, Direction.EAST, Direction.NORTH, k, 0, rows, fy)
        }
    }

    /**
     * Eine Spalte der Fassade am Unterbau: (x, z) = Wandblock, Wand bis unter [faceTop], [out] = Blick nach außen,
     * [along] = Richtung wachsender Lage [pos] entlang der Wand, [first] … [last] = Lage der Enden. Von unten: Sockel aus
     * Olymp-Marmor bis [SOCKEL_TOP], Quarzziegel, Bronzeband auf [BAND_Y], darüber Blendbögen (Nische einen Block tief,
     * Rückwand poliert, Bogen aus Marmortreppen), oben die vorhandene Wandkrone. Alle [LISENE_STEP] Blöcke und an den
     * Enden tritt eine Lisene einen Block vor (Marmorsäule, Bronze am Band, Kapitell als Treppe).
     */
    private fun facade(c: SkyCanvas, x: Int, z: Int, faceTop: Int, out: Direction, along: Direction, pos: Int, first: Int, last: Int, fy: Int) {
        val bottom = fy - 2
        if (faceTop - bottom < 4) return
        val lisene = Math.floorMod(pos, LISENE_STEP) == 0 || pos == first || pos == last
        val bay = Math.floorMod(pos, LISENE_STEP)
        val bayStart = pos - bay
        val fullBay = bayStart >= first && bayStart + LISENE_STEP <= last
        val arch = !lisene && fullBay && (bay == 2 || bay == 3) && faceTop >= ARCH_TOP + 2
        val banded = faceTop > BAND_Y + 1
        for (y in bottom until faceTop) {
            val state = when {
                y <= SOCKEL_TOP -> s(HhaDecoBlocks.OLYMPUS_MARBLE)
                y == BAND_Y && banded -> s(HhaDecoBlocks.OLYMPUS_BRONZE)
                else -> s(Blocks.QUARTZ_BRICKS)
            }
            c.block(x, y, z, state)
        }
        if (arch) {
            for (y in ARCH_FROM until ARCH_TOP) {
                c.block(x, y, z, Kit.AIR)
                c.block(x - out.offsetX, y, z - out.offsetZ, s(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE))
            }
            c.block(x, ARCH_TOP, z, stair(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_STAIRS, if (bay == 2) along.opposite else along, top = true))
        }
        if (lisene) {
            val px = x + out.offsetX
            val pz = z + out.offsetZ
            for (y in bottom until faceTop) {
                val state = when {
                    y <= SOCKEL_TOP -> s(HhaDecoBlocks.OLYMPUS_MARBLE)
                    y == BAND_Y && banded -> s(HhaDecoBlocks.OLYMPUS_BRONZE)
                    y == faceTop - 1 -> stair(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_STAIRS, out.opposite, top = true)
                    else -> s(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR)
                }
                c.block(px, y, pz, state)
            }
        }
    }
}
