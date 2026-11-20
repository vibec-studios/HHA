package dev.henny.hha.world.palace

import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.world.BlockCanvas
import dev.henny.hha.world.Kit
import dev.henny.hha.world.PalacePlan.pal
import dev.henny.hha.world.TartarusPlans.COURT
import dev.henny.hha.world.block
import dev.henny.hha.world.brazier
import dev.henny.hha.world.column
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.DoorBlock
import net.minecraft.block.LeavesBlock
import net.minecraft.block.PillarBlock
import net.minecraft.block.enums.DoubleBlockHalf
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Der Palasthof (Überarbeitung 4, PLAN 5.2 und 6.1): Kolonnade an der Innenseite der Hofmauer (alle 6 eine Säule,
 * Pultdach), vier Radialwege von den Ecktürmen zum Graben mit Feuerschalen, dazwischen Beete aus Asche und totem
 * Gesträuch, zwei Pavillons (Persephones Garten im Nordosten, das Schatzhaus im Nordwesten). Der Anmarsch auf der
 * Südachse gehört [PalaceApproach]; der Hof lässt ihm |x| < [AXIS_CLEAR] frei.
 *
 * Koordinaten relativ zur Palastmitte `o`; Boden auf `o.y` wie das Pflaster aus `PalacePlan.ground`.
 */
internal object PalaceCourt {

    /** Säulenlinie der Kolonnade (Abstand zur Mitte); Innenkante der Hofmauer bei [COURT] − 2. */
    const val COLONNADE_LINE = COURT - 9
    const val COLONNADE_SPACING = 6
    const val COLONNADE_REACH = 68

    /** Auf der Südseite bleibt die Mitte frei für Torhaus, Tortürme und den Gang der Schatten. */
    const val COLONNADE_SOUTH_GAP = 26

    /** Radialwege: halbe Breite, Anfang (am Graben) und Ende (am Turm) als Abstand zur Mitte auf der Diagonale. */
    const val PATH_HALF = 2.5
    const val PATH_FROM = 66.0
    const val PATH_TO = 104.0

    /** Beete: so weit von Graben, Kolonnade, Achsen und Wegen weg. */
    const val FIELD_MIN_R = 69.0
    const val FIELD_MAX = 72
    const val AXIS_CLEAR = 14

    /** Pavillons: Mitte (x, z) relativ zu o, halbe Seitenlänge. */
    val GARDEN = intArrayOf(65, -40)
    const val GARDEN_HALF = 7
    val TREASURY = intArrayOf(-65, -40)
    const val TREASURY_HALF = 4

    private fun s(b: Block): BlockState = b.defaultState

    fun draw(c: BlockCanvas, o: BlockPos) {
        fields(c, o)
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) radialPath(c, o, sx, sz)
        colonnade(c, o)
        garden(c, o)
        treasury(c, o)
    }

    // --- Kolonnade --------------------------------------------------------------------------------------------------

    /**
     * Kolonnade an allen vier Mauern: Säulen auf der Linie [COLONNADE_LINE] alle 6 Blöcke (auf den Strebepfeilern der
     * Mauer ausgerichtet), Architrav, Pultdach zur Mauer hin ansteigend, Seelenlaternen zwischen den Säulen.
     */
    private fun colonnade(c: BlockCanvas, o: BlockPos) {
        for (side in 0 until 4) {
            for (a in -COLONNADE_REACH..COLONNADE_REACH) {
                if (side == SOUTH && abs(a) < COLONNADE_SOUTH_GAP) continue
                for (k in 0..7) colonnadeCell(c, o, side, a, k)
            }
            var a = -COLONNADE_REACH / COLONNADE_SPACING * COLONNADE_SPACING
            while (a <= COLONNADE_REACH) {
                if (!(side == SOUTH && abs(a) < COLONNADE_SOUTH_GAP)) {
                    val (x, z) = place(o, side, a, 1)
                    c.column(x, o.y + 1, z, 6, 0)
                    val (lx, lz) = place(o, side, a + COLONNADE_SPACING / 2, 3)
                    if (abs(a + COLONNADE_SPACING / 2) <= COLONNADE_REACH && !(side == SOUTH && abs(a + COLONNADE_SPACING / 2) < COLONNADE_SOUTH_GAP)) {
                        c.block(lx, o.y + 9, lz, Kit.chain())
                        c.block(lx, o.y + 8, lz, Kit.lantern())
                    }
                }
                a += COLONNADE_SPACING
            }
        }
    }

    private const val NORTH = 0
    private const val EAST = 1
    private const val SOUTH = 2
    private const val WEST = 3

    /**
     * Weltposition einer Zelle der Kolonnade: [a] läuft entlang der Mauer, [k] = 0 an der Hofkante (eine Lage vor der
     * Säulenlinie), 7 an der Mauer.
     */
    private fun place(o: BlockPos, side: Int, a: Int, k: Int): Pair<Int, Int> {
        val d = COLONNADE_LINE - 1 + k
        return when (side) {
            NORTH -> Pair(o.x + a, o.z - d)
            SOUTH -> Pair(o.x + a, o.z + d)
            EAST -> Pair(o.x + d, o.z + a)
            else -> Pair(o.x - d, o.z + a)
        }
    }

    private fun towardWall(side: Int): Direction = when (side) {
        NORTH -> Direction.NORTH
        SOUTH -> Direction.SOUTH
        EAST -> Direction.EAST
        else -> Direction.WEST
    }

    private fun colonnadeCell(c: BlockCanvas, o: BlockPos, side: Int, a: Int, k: Int) {
        val y = o.y
        val (x, z) = place(o, side, a, k)
        // Strebepfeiler der Mauer stehen auf a ≡ 0 (mod 12) in den Reihen 6 und 7: dort nichts freiräumen.
        val pier = Math.floorMod(a, 12) == 0 && k >= 6
        c.block(x, y, z, if (k == 0) s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT) else s(Blocks.POLISHED_DEEPSLATE))
        // Pultdach: 1 zu 2, von +9 an der Hofkante bis +12 an der Mauer.
        val roof = y + 9 + k / 2
        if (!pier) for (h in y + 1 until roof) c.block(x, h, z, Kit.AIR)
        val wall = towardWall(side)
        if (k % 2 == 0) {
            c.block(x, roof, z, Kit.stair(Blocks.DEEPSLATE_TILE_STAIRS, wall))
        } else {
            c.block(x, roof, z, s(Blocks.DEEPSLATE_TILES))
            c.block(x, roof + 1, z, Kit.slab(Blocks.DEEPSLATE_TILE_SLAB))
        }
        // Architrav über den Säulen.
        if (k == 1) c.block(x, y + 8, z, if (Math.floorMod(a, COLONNADE_SPACING) == 0) s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT) else s(Blocks.POLISHED_BLACKSTONE_BRICKS))
    }

    // --- Radialwege -------------------------------------------------------------------------------------------------

    /** Weg vom Eckturm ([sx], [sz]) diagonal zum Graben: Basaltziegel, polierte Kanten, Feuerschalen alle 10 Blöcke. */
    private fun radialPath(c: BlockCanvas, o: BlockPos, sx: Int, sz: Int) {
        val y = o.y
        val reach = PATH_TO.toInt() + 4
        for (dx in 0..reach) for (dz in 0..reach) {
            val along = (dx + dz) / SQRT2
            val across = abs(dx - dz) / SQRT2
            if (along !in PATH_FROM..PATH_TO || across > PATH_HALF) continue
            val x = o.x + sx * dx
            val z = o.z + sz * dz
            c.block(x, y, z, if (across > PATH_HALF - 1.0) s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT) else s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS))
            for (h in 1..4) c.block(x, y + h, z, Kit.AIR)
        }
        var t = PATH_FROM + 4.0
        while (t < PATH_TO - 6.0) {
            for (side in intArrayOf(-1, 1)) {
                val bx = Math.round((t + side * (PATH_HALF + 1.5)) / SQRT2).toInt()
                val bz = Math.round((t - side * (PATH_HALF + 1.5)) / SQRT2).toInt()
                c.brazier(o.x + sx * bx, y + 1, o.z + sz * bz, 1)
            }
            t += 12.0
        }
    }

    private val SQRT2 = sqrt(2.0)

    // --- Beete aus Asche ------------------------------------------------------------------------------------------

    /** Gehört die Zelle (relativ zu o) zu einem Beet? Weg von Graben, Kolonnade, Achsen, Radialwegen, Türmen und Pavillons. */
    fun inField(dx: Int, dz: Int): Boolean {
        val ax = abs(dx)
        val az = abs(dz)
        if (hypot(dx.toDouble(), dz.toDouble()) < FIELD_MIN_R) return false
        if (maxOf(ax, az) > FIELD_MAX || minOf(ax, az) < AXIS_CLEAR) return false
        if (abs(ax - az) / SQRT2 < PATH_HALF + 3.5) return false
        if (hypot((ax - COURT).toDouble(), (az - COURT).toDouble()) < 17.0) return false
        for (p in listOf(GARDEN to GARDEN_HALF, TREASURY to TREASURY_HALF)) {
            val (cx, cz) = p.first[0] to p.first[1]
            if (abs(dx - cx) <= p.second + 2 && abs(dz - cz) <= p.second + 2) return false
            // Zugang vom Pavillon zum Radialweg (nach Norden).
            if (abs(dx - cx) <= 2 && dz < cz && dz >= cz - 26) return false
        }
        return true
    }

    /** Beete: Rand aus poliertem Tiefschiefer, innen Seelenerde mit Flecken groben Bodens, tote Büsche, selten eine Witherrose. */
    private fun fields(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        for (dx in -FIELD_MAX..FIELD_MAX) for (dz in -FIELD_MAX..FIELD_MAX) {
            if (!inField(dx, dz)) continue
            val x = o.x + dx
            val z = o.z + dz
            val edge = !(inField(dx + 1, dz) && inField(dx - 1, dz) && inField(dx, dz + 1) && inField(dx, dz - 1))
            if (edge) {
                c.block(x, y, z, s(Blocks.POLISHED_DEEPSLATE))
                continue
            }
            // Asche: Seelenerde, in Flecken Basalt (Schlacke); grober Boden nur einzeln unter den toten Büschen.
            val n = Kit.noise(x, 5, z)
            val cinder = Kit.noise(Math.floorDiv(x, 3), 4, Math.floorDiv(z, 3)) % 3 == 0
            when {
                n % 9 == 0 -> {
                    c.block(x, y, z, s(Blocks.COARSE_DIRT))
                    c.block(x, y + 1, z, s(Blocks.DEAD_BUSH))
                }
                cinder -> c.block(x, y, z, if (n % 2 == 0) s(Blocks.BASALT) else s(Blocks.SOUL_SAND))
                else -> {
                    c.block(x, y, z, s(Blocks.SOUL_SOIL))
                    if (n % 31 == 0) c.block(x, y + 1, z, s(Blocks.WITHER_ROSE))
                }
            }
        }
    }

    // --- Persephones Garten -----------------------------------------------------------------------------------------

    /**
     * Persephones Garten (Nordost): ummauertes Geviert 15 × 15, Tor nach Norden zum Radialweg, Boden aus Podsol mit
     * Affodill (Maiglöckchen) und Witherrosen, ein Granatapfelbaum aus Mangrove mit roten Blättern und leuchtenden Früchten,
     * in der Mitte ein Brunnen mit einer Seelenlaterne.
     */
    private fun garden(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val cx = o.x + GARDEN[0]
        val cz = o.z + GARDEN[1]
        val h = GARDEN_HALF
        for (x in cx - h..cx + h) for (z in cz - h..cz + h) {
            val ring = maxOf(abs(x - cx), abs(z - cz))
            for (yy in y + 1..y + 6) c.block(x, yy, z, Kit.AIR)
            when {
                ring == h -> {
                    val corner = abs(x - cx) == h && abs(z - cz) == h
                    val mid = abs(x - cx) == 0 || abs(z - cz) == 0
                    c.block(x, y, z, s(Blocks.POLISHED_DEEPSLATE))
                    for (yy in 1..3) c.block(x, y + yy, z, if (corner || (mid && yy < 3)) s(HhaDecoBlocks.TARTARUS_BASALT_PILLAR) else s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS))
                    c.block(x, y + 4, z, if (corner) s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT) else Kit.slab(HhaDecoBlocks.TARTARUS_BASALT_BRICK_SLAB))
                }
                ring == h - 1 -> c.block(x, y, z, s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
                else -> {
                    c.block(x, y, z, s(Blocks.PODZOL))
                    val n = Kit.noise(x, 6, z)
                    if (ring >= 3 && n % 5 == 0) c.block(x, y + 1, z, if (n % 3 == 0) s(Blocks.WITHER_ROSE) else s(Blocks.LILY_OF_THE_VALLEY))
                }
            }
        }
        // Tor nach Norden (3 breit), Weg zum Radialweg.
        for (dx in -1..1) {
            for (yy in 1..4) c.block(cx + dx, y + yy, cz - h, Kit.AIR)
            c.block(cx + dx, y, cz - h, s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
        }
        for (dx in intArrayOf(-2, 2)) {
            for (yy in 1..4) c.block(cx + dx, y + yy, cz - h, s(HhaDecoBlocks.TARTARUS_BASALT_PILLAR))
            c.block(cx + dx, y + 5, cz - h, Kit.lantern(hanging = false))
        }
        for (dz in 1..24) for (dx in -1..1) {
            val z = cz - h - dz
            if (dz > 4 && abs((abs(cx + dx - o.x)) - abs(z - o.z)) / SQRT2 < PATH_HALF) break
            c.block(cx + dx, y, z, if (dx == 0) s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS) else s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
        }
        // Brunnen in der Mitte.
        for (dx in -2..2) for (dz in -2..2) {
            val ring = maxOf(abs(dx), abs(dz))
            c.block(cx + dx, y, cz + dz, s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
            c.block(cx + dx, y + 1, cz + dz, if (ring == 2) Kit.slab(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_SLAB, top = true) else s(Blocks.WATER))
        }
        c.block(cx, y + 1, cz, s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT))
        c.block(cx, y + 2, cz, s(HhaDecoBlocks.TARTARUS_BASALT_PILLAR))
        c.block(cx, y + 3, cz, Kit.lantern(hanging = false))
        // Der Granatapfelbaum in der Südwestecke.
        pomegranate(c, cx - 3, y, cz + 3)
    }

    private fun pomegranate(c: BlockCanvas, x: Int, y: Int, z: Int) {
        for (h in 1..4) c.block(x, y + h, z, s(Blocks.MANGROVE_LOG))
        // Zwei Äste: waagerecht aus dem Stamm, dann aufwärts (lückenlos, damit nichts schwebt).
        val across = s(Blocks.MANGROVE_LOG).with(PillarBlock.AXIS, Direction.Axis.X)
        for (side in intArrayOf(-1, 1)) {
            for (d in 1..2) c.block(x + side * d, y + 5, z, across)
            c.block(x + side * 2, y + 6, z, s(Blocks.MANGROVE_LOG))
        }
        // Krone aus roten Blättern (Netherwarzenblock) mit Flecken von Mangrovenlaub, darunter die leuchtenden Früchte.
        // Jeder Warzenblock braucht einen festen Nachbarn (Laub trägt nicht), vereinzelte werden zu Laub.
        val leaves = s(Blocks.MANGROVE_LEAVES).with(LeavesBlock.PERSISTENT, true)
        fun inCrown(dx: Int, dy: Int, dz: Int) = (dx / 3.0) * (dx / 3.0) + (dy / 1.8) * (dy / 1.8) + (dz / 3.0) * (dz / 3.0) <= 1.0
        fun inCore(dx: Int, dy: Int, dz: Int) = (dx / 2.2) * (dx / 2.2) + (dy / 1.2) * (dy / 1.2) + (dz / 2.2) * (dz / 2.2) <= 1.0
        fun wart(dx: Int, dy: Int, dz: Int) = inCore(dx, dy, dz) || (inCrown(dx, dy, dz) && Kit.noise(x + dx, y + 7 + dy, z + dz) % 4 != 0) || (dx == 0 && dz == 0 && dy <= 0)
        for (dx in -3..3) for (dy in -2..2) for (dz in -3..3) {
            if (!inCrown(dx, dy, dz)) continue
            val held = wart(dx, dy, dz) && (inCore(dx, dy, dz) || listOf(intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0), intArrayOf(0, 1, 0), intArrayOf(0, -1, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1))
                .any { d -> wart(dx + d[0], dy + d[1], dz + d[2]) })
            c.block(x + dx, y + 7 + dy, z + dz, if (held) s(Blocks.NETHER_WART_BLOCK) else leaves)
        }
        for (h in 5..7) c.block(x, y + h, z, s(Blocks.MANGROVE_LOG))
        for ((fx, fz) in listOf(2 to 0, -2 to 0, 0 to 2, 0 to -2)) c.block(x + fx, y + 6, z + fz, s(Blocks.SHROOMLIGHT))
    }

    // --- Schatzhaus -------------------------------------------------------------------------------------------------

    /**
     * Das Schatzhaus (Nordwest): Bau 9 × 9 auf einem Sockel, Wände aus Schwarzstein mit einem Steinband aus gemeißeltem
     * Basalt, Ecksäulen, Pyramidendach mit goldener Spitze, Tür aus Kupfer in einem Rahmen aus Tartaros-Bronze nach
     * Norden. Innen eine Truhe (über `c.chest`) und eine Seelenlaterne.
     */
    private fun treasury(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val cx = o.x + TREASURY[0]
        val cz = o.z + TREASURY[1]
        val h = TREASURY_HALF
        for (x in cx - h - 1..cx + h + 1) for (z in cz - h - 1..cz + h + 1) {
            val ring = maxOf(abs(x - cx), abs(z - cz))
            for (yy in y + 1..y + 13) c.block(x, yy, z, Kit.AIR)
            if (ring == h + 1) {
                c.block(x, y, z, s(Blocks.POLISHED_DEEPSLATE))
                c.block(x, y + 1, z, Kit.stair(Blocks.POLISHED_DEEPSLATE_STAIRS, Kit.inward(x - cx, z - cz)))
                continue
            }
            c.block(x, y, z, s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
            c.block(x, y + 1, z, s(Blocks.POLISHED_DEEPSLATE))
            if (ring == h) {
                val corner = abs(x - cx) == h && abs(z - cz) == h
                for (yy in 2..6) {
                    c.block(x, y + yy, z, when {
                        corner -> s(HhaDecoBlocks.TARTARUS_BASALT_PILLAR)
                        yy == 4 -> s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT)
                        else -> pal.FACE(x, y + yy, z)
                    })
                }
            }
        }
        // Pyramidendach.
        for (k in 0..h + 1) {
            val r = h + 1 - k
            for (x in cx - r..cx + r) for (z in cz - r..cz + r) {
                val ring = maxOf(abs(x - cx), abs(z - cz))
                if (ring == r) c.block(x, y + 7 + k, z, if (r == 0) s(Blocks.GILDED_BLACKSTONE) else Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Kit.inward(x - cx, z - cz)))
                else if (ring < r) c.block(x, y + 7 + k, z, s(Blocks.POLISHED_BLACKSTONE_BRICKS))
            }
        }
        c.block(cx, y + 8 + h + 1, cz, pal.GOLD)
        // Tür nach Norden: Rahmen aus Tartaros-Bronze, Kupfertür.
        val dz = cz - h
        for (yy in 2..4) for (side in intArrayOf(-1, 1)) c.block(cx + side, y + yy, dz, s(HhaDecoBlocks.TARTARUS_BRONZE))
        for (dx in -1..1) c.block(cx + dx, y + 5, dz, s(HhaDecoBlocks.TARTARUS_BRONZE))
        val door = Blocks.WAXED_OXIDIZED_COPPER_DOOR.defaultState.with(DoorBlock.FACING, Direction.NORTH)
        c.block(cx, y + 2, dz, door.with(DoorBlock.HALF, DoubleBlockHalf.LOWER))
        c.block(cx, y + 3, dz, door.with(DoorBlock.HALF, DoubleBlockHalf.UPPER))
        c.block(cx, y + 4, dz, s(HhaDecoBlocks.TARTARUS_BRONZE))
        // Stufe vor der Tür und Weg zum Radialweg.
        c.block(cx, y + 1, dz - 1, Kit.stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, Direction.SOUTH))
        for (step in 2..26) for (dx in -1..1) {
            val z = dz - step
            if (step > 4 && abs((abs(cx + dx - o.x)) - abs(z - o.z)) / SQRT2 < PATH_HALF) break
            c.block(cx + dx, y, z, if (dx == 0) s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS) else s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT))
        }
        // Innen: die Truhe, eine Laterne an der Decke.
        if (cx in c.minX..c.maxX && cz + 2 in c.minZ..c.maxZ) c.chest(cx, y + 2, cz + 2)
        c.block(cx, y + 6, cz, Kit.chain())
        c.block(cx, y + 5, cz, Kit.lantern())
    }
}
