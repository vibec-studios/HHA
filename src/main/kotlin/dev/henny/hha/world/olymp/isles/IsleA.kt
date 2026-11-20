package dev.henny.hha.world.olymp.isles

import dev.henny.hha.HhaBlocks
import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.world.DecoPalettes
import dev.henny.hha.block.BossAltarBlock
import dev.henny.hha.block.SteleBlock
import dev.henny.hha.world.Kit
import dev.henny.hha.world.OlympusTemplePlan
import dev.henny.hha.world.block
import dev.henny.hha.world.brazier as greenBrazier
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.WallBlock
import net.minecraft.block.enums.WallShape
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import dev.henny.hha.world.olymp.*
import dev.henny.hha.world.olymp.OlympKit.Attr
import dev.henny.hha.world.olymp.OlympKit.angleOf
import dev.henny.hha.world.olymp.OlympKit.figure
import dev.henny.hha.world.olymp.OlympKit.herm
import dev.henny.hha.world.olymp.OlympKit.path
import dev.henny.hha.world.olymp.OlympKit.polar
import dev.henny.hha.world.olymp.OlympKit.s
import dev.henny.hha.world.olymp.OlympKit.stair
import dev.henny.hha.world.olymp.OlympKit.stele
import dev.henny.hha.world.olymp.OlympusPlans.bridgeHeads

/** Bauplan der Insel A (aus `OlympusPlans` herausgelöst, Überarbeitung 4, P00). Aufruf über `OlympusPlans.draw`. */
internal object IsleA {

    // =============================================================================================
    // A  Wolkentor der Horen
    // =============================================================================================

    fun draw(c: SkyCanvas) {
        val isle = OlympusLayout.A
        val fy = isle.floorY
        val cx = isle.center.x
        val cz = isle.center.z
        val body = OlympusIsles.Body(cx, fy - 1, cz, isle.radius.toDouble(), IsleKind.MEADOW, 11, 0.9)
        OlympusIsles.body(c, body)
        bridgeHeads(c, isle)
        // Platz der Horen: polierter Diorit, Strahlen aus Kalkstein, die vom Tor ausgehen.
        val gate = OlympusLayout.ARRIVAL_GATE
        val gx = gate.x + OlympusLayout.ARRIVAL_GATE_WIDTH / 2.0
        val gz = gate.z + 0.5
        c.disc(0, -2, 12.5) { x, z, r ->
            val a = angleOf(x + 0.5 - gx, z + 0.5 - gz)
            val ray = a in 12.0..168.0 && (a + 7.5) % 15.0 < 2.6
            val state = when {
                r > 11.5 -> s(Blocks.QUARTZ_BRICKS)
                r > 10.8 -> s(Blocks.CALCITE)
                ray && hypot(x + 0.5 - gx, z + 0.5 - gz) > 3.0 -> s(Blocks.CALCITE)
                else -> s(Blocks.POLISHED_DIORITE)
            }
            c.put(x, fy - 1, z, state)
            for (h in 0..12) c.put(x, fy + h, z, Kit.AIR)
        }
        // Weg vom Tor zur Brücke nach Norden.
        path(c, 0, -10, 0, -21, fy - 1, 2.6) { x, _ -> if (abs(x) == 2) s(Blocks.CALCITE) else s(Blocks.POLISHED_DIORITE) }
        gateArch(c, fy)
        gatePlinth(c, fy)
        for (px in PYLON_X) pylon(c, px, fy, OlympusLayout.ARRIVAL_GATE.z)
        lookout(c, fy)
        // Die drei Horen: Eunomia (Ordnung) und Dike (Recht) zu beiden Seiten, Eirene (Frieden) gegenüber dem Tor.
        figure(c, -10, fy, -3, Direction.EAST, Attr.SCEPTER)
        figure(c, 9, fy, -3, Direction.WEST, Attr.SCALES)
        figure(c, 0, fy, 9, Direction.NORTH, Attr.CORNUCOPIA)
        // Halbrunde Bank hinter Eirene.
        for (deg in 55..125 step 5) {
            val (bx, bz) = polar(0, -2, 14.6, deg.toDouble())
            if (abs(bx) <= 1) continue
            c.block(bx, fy, bz, stair(Blocks.SMOOTH_QUARTZ_STAIRS, Direction.SOUTH))
        }
        stele(c, -3, fy, -4, 6, Direction.SOUTH)
        stele(c, 4, fy, -18, 7, Direction.WEST)
        herm(c, -4, fy, -20, Direction.NORTH)
        herm(c, 4, fy, -20, Direction.NORTH)
        // Garten: Kirschen und Ölbäume am Rand, Zypressen am Weg zur Brücke.
        OlympusIsles.cherry(c, -15, fy, 7, 3)
        OlympusIsles.cherry(c, 15, fy, 8, 4)
        OlympusIsles.olive(c, -16, fy, -10, 5)
        OlympusIsles.olive(c, 16, fy, -12, 6)
        OlympusIsles.cypress(c, -5, fy, -14, 8, 7)
        OlympusIsles.cypress(c, 5, fy, -14, 8, 8)
        OlympusIsles.cypress(c, -8, fy, 17, 7, 9)
        OlympusIsles.cypress(c, 8, fy, 17, 7, 10)
        OlympusIsles.meadow(c, body, fy, { x, z -> hypot(x.toDouble(), z + 2.0) < 14.0 || (abs(x) <= 4 && z in -26..-8) })
        OlympusIsles.cloudSkirt(c, body, 4)
        OlympusIsles.cloud(c, -42, 104, 12, 18.0, 11.0, 12, 3.0)
        OlympusIsles.cloud(c, 36, 108, -38, 14.0, 9.0, 13, 2.6)
        OlympusIsles.cloud(c, 18, 98, 34, 16.0, 10.0, 14, 3.0)
    }

    /** Die beiden Pylone, die das Wolkentor flankieren (Mitte x; symmetrisch zur Tormitte x −0,5). */
    private val PYLON_X = intArrayOf(-11, 10)
    private const val PYLON_HEIGHT = 11

    /** Sockel des Wolkentors: Marmorplatte um den Bogen, Rand gemeißelt, eine halbe Stufe als Fassung (Achse frei). */
    private const val PLINTH_X0 = -8
    private const val PLINTH_X1 = 7
    private const val PLINTH_Z0 = -13
    private const val PLINTH_Z1 = -5

    /** Aussichtsplatz am Nordrand: Mitte, Radius, eine Stufe erhöht; Blick nach Norden über die Brücken zum Thron. */
    private const val LOOKOUT_X = -11
    private const val LOOKOUT_Z = -17
    private const val LOOKOUT_R = 3.5

    /**
     * Sockel um den Bogen (Überarbeitung 4, P06): polierter Olymp-Marmor mit gemeißeltem Rand und einer halben Stufe
     * Fassung, die Achse des Tors (x −3 … 2) bleibt frei, damit Ankunftspunkt und Rahmen unverändert bleiben.
     */
    private fun gatePlinth(c: SkyCanvas, fy: Int) {
        for (x in PLINTH_X0..PLINTH_X1) for (z in PLINTH_Z0..PLINTH_Z1) {
            if (x in -3..2) continue
            val edge = x == PLINTH_X0 || x == PLINTH_X1 || z == PLINTH_Z0 || z == PLINTH_Z1
            if (c.isFree(x, fy, z)) {
                c.block(x, fy - 1, z, if (edge) s(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE) else s(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE))
                if (edge) c.block(x, fy, z, Kit.slab(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_SLAB))
            }
        }
    }

    /**
     * Pylon neben dem Wolkentor: Stufensockel 5 × 5, Schaft 3 × 3 mit Säulen an den Ecken und einem goldenen Band,
     * Gesims, Spitze mit Lichtstab ([PYLON_HEIGHT] über dem Boden).
     */
    private fun pylon(c: SkyCanvas, cx: Int, fy: Int, cz: Int) {
        for (dx in -2..2) for (dz in -2..2) {
            val ring = abs(dx) == 2 || abs(dz) == 2
            val x = cx + dx
            val z = cz + dz
            c.block(x, fy - 1, z, s(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE))
            if (ring) {
                c.block(x, fy, z, stair(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_STAIRS, Kit.inward(dx, dz)))
                continue
            }
            val cornerCol = abs(dx) == 1 && abs(dz) == 1
            for (h in 0 until PYLON_HEIGHT - 3) {
                val y = fy + h
                c.block(x, y, z, when {
                    h == 0 -> s(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS)
                    h == 5 -> if (dx == 0 || dz == 0) s(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE) else s(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE)
                    cornerCol -> s(HhaDecoBlocks.OLYMPUS_MARBLE_PILLAR)
                    else -> DecoPalettes.MARBLE_WALL(x, y, z)
                })
            }
            // Gesims und Spitze.
            val top = fy + PYLON_HEIGHT - 3
            c.block(x, top, z, if (dx == 0 && dz == 0) s(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE) else stair(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_STAIRS, Kit.inward(dx, dz), top = true))
        }
        for (d in OlympKit.HORIZONTAL) c.block(cx + d.offsetX * 2, fy + PYLON_HEIGHT - 4, cz + d.offsetZ * 2, stair(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_STAIRS, d.opposite, top = true))
        c.block(cx, fy + PYLON_HEIGHT - 2, cz, s(HhaDecoBlocks.OLYMPUS_MARBLE_BRICKS))
        c.block(cx, fy + PYLON_HEIGHT - 1, cz, s(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE))
        c.block(cx, fy + PYLON_HEIGHT, cz, s(Blocks.END_ROD))
    }

    /**
     * Aussichtsplatz am Nordrand (Überarbeitung 4, P06): runde Plattform eine Stufe hoch, Brüstung zur Nordseite, Zugang
     * über eine Stufe (drei breit) im Süden, goldene Leitlinie zur Brüstung. Von hier läuft der Blick nach Norden die Brücken entlang
     * bis zum Thron. Was über den Inselrand hinausragt, trägt ein Unterbau aus Naturmarmor.
     */
    private fun lookout(c: SkyCanvas, fy: Int) {
        val r = LOOKOUT_R
        for (x in LOOKOUT_X - 4..LOOKOUT_X + 4) for (z in LOOKOUT_Z - 4..LOOKOUT_Z + 4) {
            val d = hypot((x - LOOKOUT_X).toDouble(), (z - LOOKOUT_Z).toDouble())
            if (d > r) continue
            for (y in fy - 4..fy - 1) if (c.isFree(x, y, z)) c.block(x, y, z, if (y == fy - 4) stair(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_STAIRS, Kit.inward(x - LOOKOUT_X, z - LOOKOUT_Z), top = true) else s(HhaDecoBlocks.OLYMPUS_MARBLE))
            val axis = x == LOOKOUT_X && z < LOOKOUT_Z
            c.block(x, fy, z, when {
                d > r - 0.9 -> s(HhaDecoBlocks.CHISELED_OLYMPUS_MARBLE)
                axis -> s(HhaDecoBlocks.GILDED_OLYMPUS_MARBLE)
                else -> s(HhaDecoBlocks.POLISHED_OLYMPUS_MARBLE)
            })
            for (h in 1..4) c.block(x, fy + h, z, Kit.AIR)
            // Brüstung auf der Nordhälfte, an den Enden Laternenpfosten.
            if (d > r - 0.9 && z <= LOOKOUT_Z) {
                val end = z == LOOKOUT_Z
                if (end) {
                    c.block(x, fy + 1, z, OlympKit.wall(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_WALL, alongX = true, post = true))
                    c.block(x, fy + 2, z, Kit.lantern(hanging = false, soul = false))
                } else c.block(x, fy + 1, z, OlympKit.wall(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_WALL, alongX = abs(z - LOOKOUT_Z) >= 3, post = abs(x - LOOKOUT_X) == abs(z - LOOKOUT_Z)))
            }
        }
        // Zugang: eine Stufe, drei breit, im Süden.
        for (x in LOOKOUT_X - 1..LOOKOUT_X + 1) c.block(x, fy, LOOKOUT_Z + 4, stair(HhaDecoBlocks.OLYMPUS_MARBLE_BRICK_STAIRS, Direction.NORTH))
    }

    /**
     * Das Wolkentor: ein Ehrenbogen aus Marmor. Zwei Pylonen mit vorgestellten Säulen, dazwischen der Rundbogen mit
     * goldenem Schlussstein; im Bogen steht der Glowstone-Rahmen mit dem Tor. Darüber Gebälk, Gesims und eine Attika.
     * In den Außenseiten der Pylonen je eine Nische mit Amphore.
     */
    private fun gateArch(c: SkyCanvas, fy: Int) {
        val g = OlympusLayout.ARRIVAL_GATE
        val w = OlympusLayout.ARRIVAL_GATE_WIDTH
        val h = OlympusLayout.ARRIVAL_GATE_HEIGHT
        val gz = g.z
        val x0 = g.x - 1
        val x1 = g.x + w
        val top = g.y + h
        val mid = g.x + w / 2.0
        val spring = top.toDouble()
        val left = x0 - 3..x0 - 1
        val right = x1 + 1..x1 + 3
        val beam = top + 5
        // Pylonen (drei tief) und die Wand über dem Bogen, unten ein Sockel.
        for (x in x0 - 3..x1 + 3) for (z in gz - 1..gz + 1) for (y in fy - 1 until beam) {
            val pylon = x in left || x in right
            val r = hypot(x + 0.5 - mid, y + 0.5 - spring)
            if (!pylon && (y <= top || r <= 3.6)) {
                if (z != gz || y > top) c.block(x, y, z, Kit.AIR)
                continue
            }
            val face = z != gz
            val state = when {
                y == fy - 1 -> s(Blocks.SMOOTH_QUARTZ)
                y == fy -> s(Blocks.CHISELED_QUARTZ_BLOCK)
                !pylon && face && (x == g.x + 1 || x == g.x + 2) && y == beam - 1 -> s(Blocks.GOLD_BLOCK)
                !pylon && face && r <= 4.8 -> s(Blocks.CHISELED_QUARTZ_BLOCK)
                pylon && (x == x0 - 3 || x == x1 + 3) && face -> s(Blocks.QUARTZ_PILLAR)
                Kit.noise(x, y, z) % 9 == 0 -> s(Blocks.SMOOTH_QUARTZ)
                else -> s(Blocks.QUARTZ_BRICKS)
            }
            c.block(x, y, z, state)
        }
        // Vorgestellte Säulen vor den Pylonen, vorn und hinten.
        for (x in intArrayOf(x0 - 2, x1 + 2)) for (z in intArrayOf(gz - 2, gz + 2)) {
            val out = if (z < gz) Direction.NORTH else Direction.SOUTH
            c.block(x, fy, z, s(Blocks.CHISELED_QUARTZ_BLOCK))
            for (side in listOf(out, out.rotateYClockwise(), out.rotateYCounterclockwise())) {
                c.block(x + side.offsetX, fy, z + side.offsetZ, stair(Blocks.QUARTZ_STAIRS, side.opposite))
                c.block(x + side.offsetX, beam - 1, z + side.offsetZ, stair(Blocks.QUARTZ_STAIRS, side.opposite, top = true))
            }
            for (y in fy + 1 until beam - 1) c.block(x, y, z, s(Blocks.QUARTZ_PILLAR))
            c.block(x, beam - 1, z, s(Blocks.CHISELED_QUARTZ_BLOCK))
            c.block(x, fy - 1, z, s(Blocks.SMOOTH_QUARTZ))
        }
        // Gebälk: Architrav, Fries mit Triglyphen, Gesims nach allen Seiten.
        for (x in x0 - 4..x1 + 4) for (z in gz - 2..gz + 2) {
            c.block(x, beam, z, s(Blocks.SMOOTH_QUARTZ))
            val edge = x == x0 - 4 || x == x1 + 4 || z == gz - 2 || z == gz + 2
            c.block(x, beam + 1, z, if (edge && Math.floorMod(x - x0, 3) == 0) s(Blocks.QUARTZ_PILLAR) else if (edge) s(Blocks.CHISELED_QUARTZ_BLOCK) else s(Blocks.SMOOTH_QUARTZ))
            c.block(x, beam + 2, z, s(Blocks.SMOOTH_QUARTZ))
        }
        for (x in x0 - 5..x1 + 5) for (z in gz - 3..gz + 3) {
            val ring = x == x0 - 5 || x == x1 + 5 || z == gz - 3 || z == gz + 3
            if (ring) c.block(x, beam + 2, z, stair(Blocks.SMOOTH_QUARTZ_STAIRS, Kit.inward(x * 2 - (x0 + x1), z - gz), top = true))
        }
        // Attika mit zwei kleinen Akroteren und Lichtstäben (das Licht der Horen über dem Tor).
        for (x in x0 - 2..x1 + 2) for (z in gz - 1..gz + 1) {
            c.block(x, beam + 3, z, if (x == x0 - 2 || x == x1 + 2) s(Blocks.CHISELED_QUARTZ_BLOCK) else s(Blocks.QUARTZ_BRICKS))
            c.block(x, beam + 4, z, if (x == x0 - 2 || x == x1 + 2) s(Blocks.CHISELED_QUARTZ_BLOCK) else Kit.slab(Blocks.SMOOTH_QUARTZ_SLAB))
        }
        for (x in intArrayOf(x0 - 2, x1 + 2)) c.block(x, beam + 5, gz, s(Blocks.END_ROD))
        // Nischen in den Außenseiten der Pylonen, darin je eine Amphore.
        for ((x, out) in listOf(x0 - 3 to Direction.WEST, x1 + 3 to Direction.EAST)) {
            for (y in fy + 2..fy + 4) c.block(x, y, gz, Kit.AIR)
            c.block(x, fy + 5, gz, stair(Blocks.QUARTZ_STAIRS, out.opposite, top = true))
            c.block(x, fy + 2, gz, s(Blocks.DECORATED_POT))
        }
        // Der Rahmen aus Glowstone (untere Reihe im Boden) und das Tor.
        for (x in x0..x1) for (y in g.y - 1..top) {
            val frame = x == x0 || x == x1 || y == g.y - 1 || y == top
            c.block(x, y, gz, if (frame) s(Blocks.GLOWSTONE) else HhaBlocks.OLYMPUS_PORTAL.defaultState.with(Properties.HORIZONTAL_AXIS, OlympusLayout.ARRIVAL_GATE_AXIS))
        }
    }
}
