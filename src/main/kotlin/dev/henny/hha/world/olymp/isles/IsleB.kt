package dev.henny.hha.world.olymp.isles

import dev.henny.hha.HhaBlocks
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
import dev.henny.hha.world.olymp.OlympKit.angleDiff
import dev.henny.hha.world.olymp.OlympKit.griffinColumn
import dev.henny.hha.world.olymp.OlympKit.herm
import dev.henny.hha.world.olymp.OlympKit.path
import dev.henny.hha.world.olymp.OlympKit.polar
import dev.henny.hha.world.olymp.OlympKit.s
import dev.henny.hha.world.olymp.OlympKit.stele
import dev.henny.hha.world.olymp.OlympusPlans.bridgeHeads

/** Bauplan der Insel B (aus `OlympusPlans` herausgelöst, Überarbeitung 4, P00). Aufruf über `OlympusPlans.draw`. */
internal object IsleB {

    // =============================================================================================
    // B  Tempel des Boten
    // =============================================================================================

    fun draw(c: SkyCanvas) {
        val isle = OlympusLayout.B
        val fy = isle.floorY
        val cx = isle.center.x
        val cz = isle.center.z
        val body = OlympusIsles.Body(cx, fy - 1, cz, isle.radius.toDouble(), IsleKind.MEADOW, 22, 0.92)
        OlympusIsles.body(c, body)
        bridgeHeads(c, isle)
        // Der Tempel (Maße unverändert) mit seiner Mitte auf der Inselmitte; Plateau-Block bei fy − 1.
        OlympusTemplePlan.draw(c, BlockPos(cx, fy - 1, cz), stairs = false)
        // Prozessionswege zu beiden Brücken.
        path(c, 0, cz + 25, 0, cz + 31, fy - 1, 2.6) { x, _ -> if (abs(x) == 2) s(Blocks.CALCITE) else s(Blocks.POLISHED_DIORITE) }
        path(c, 0, cz - 25, 0, cz - 31, fy - 1, 2.6) { x, _ -> if (abs(x) == 2) s(Blocks.CALCITE) else s(Blocks.POLISHED_DIORITE) }
        for (sx in intArrayOf(-4, 4)) {
            herm(c, sx, fy, cz + 30, Direction.SOUTH)
            herm(c, sx, fy, cz - 30, Direction.NORTH)
        }
        // Zwei Siegessäulen für die Greife, im Osten und Westen.
        griffinColumn(c, 29, fy, cz)
        griffinColumn(c, -29, fy, cz)
        stele(c, -4, fy, cz + 27, 8, Direction.SOUTH)
        // Amphoren am Fuß der Tempelfront.
        for (sx in intArrayOf(-7, 7)) {
            c.block(sx, fy, cz + 26, s(Blocks.DECORATED_POT))
            c.block(sx + if (sx < 0) -1 else 1, fy, cz + 26, s(Blocks.DECORATED_POT))
        }
        // Zypressen im Ring um den Tempel, Ölbäume und Kirschen dazwischen.
        for (deg in 0 until 360 step 24) {
            if (angleDiff(deg.toDouble(), 90.0) < 20 || angleDiff(deg.toDouble(), 270.0) < 20 || angleDiff(deg.toDouble(), 0.0) < 10 || angleDiff(deg.toDouble(), 180.0) < 10) continue
            val (tx, tz) = polar(cx, cz, 29.5, deg.toDouble())
            OlympusIsles.cypress(c, tx, fy, tz, 7 + (deg / 24) % 3, deg)
        }
        OlympusIsles.olive(c, 20, fy, cz + 22, 31)
        OlympusIsles.olive(c, -21, fy, cz - 21, 32)
        OlympusIsles.cherry(c, -20, fy, cz + 23, 33)
        OlympusIsles.cherry(c, 21, fy, cz - 22, 34)
        OlympusIsles.meadow(c, body, fy, { x, z -> hypot((x - cx).toDouble(), (z - cz).toDouble()) < 25.5 || abs(x) <= 3 })
        OlympusIsles.cloudSkirt(c, body, 5)
        // Über dem Altar bleibt der Himmel frei (Himmelsforum des Hermes); Wolken nur unten und seitlich.
        OlympusIsles.cloud(c, -46, 112, cz + 10, 20.0, 12.0, 23, 3.0)
        OlympusIsles.cloud(c, 44, 106, cz - 18, 17.0, 11.0, 24, 2.8)
    }
}
