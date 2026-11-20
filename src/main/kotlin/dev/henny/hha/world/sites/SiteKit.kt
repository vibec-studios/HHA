package dev.henny.hha.world.sites

import dev.henny.hha.world.BlockCanvas
import dev.henny.hha.world.Kit
import dev.henny.hha.world.Pal
import dev.henny.hha.world.block
import dev.henny.hha.world.brazier
import dev.henny.hha.world.footing
import net.minecraft.block.Blocks
import net.minecraft.util.math.BlockPos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Baukasten der Zuwege im Tartaros (Überarbeitung 4, PLAN 5.3, 7.12; P00, eingefroren). Ein Zuweg folgt dem Gelände
 * (`ground`) und ist Bauwerk: Pflaster mit Fundament bis in den Boden, darüber frei; keine Geländeschnitte.
 */
object SiteKit {

    /** Lichte Höhe über dem Pflaster, die ein Zuweg freiräumt. */
    const val ROAD_CLEAR = 4

    /**
     * Zuweg von [from] nach [to] (nur x/z zählen), [width] Blöcke breit, Pflaster aus [pal]. Die Höhe folgt dem Gelände
     * je Spalte: Pflaster auf `ground − 1`, darunter Fundament ([footing]), darüber [ROAD_CLEAR] frei.
     */
    fun road(c: BlockCanvas, from: BlockPos, to: BlockPos, width: Int, pal: Pal) {
        val ax = from.x + 0.5
        val az = from.z + 0.5
        val bx = to.x + 0.5
        val bz = to.z + 0.5
        val len2 = (bx - ax) * (bx - ax) + (bz - az) * (bz - az)
        val half = width / 2.0
        val minX = floor(minOf(ax, bx) - half).toInt()
        val maxX = floor(maxOf(ax, bx) + half).toInt()
        val minZ = floor(minOf(az, bz) - half).toInt()
        val maxZ = floor(maxOf(az, bz) + half).toInt()
        for (x in maxOf(minX, c.minX)..minOf(maxX, c.maxX)) {
            for (z in maxOf(minZ, c.minZ)..minOf(maxZ, c.maxZ)) {
                val px = x + 0.5
                val pz = z + 0.5
                val t = if (len2 <= 0.0) 0.0 else ((px - ax) * (bx - ax) + (pz - az) * (bz - az)) / len2
                if (t < 0.0 || t > 1.0) continue
                if (hypot(px - (ax + (bx - ax) * t), pz - (az + (bz - az) * t)) > half) continue
                val top = c.ground(x, z) - 1
                c.footing(x, z, top - 1, pal)
                c.put(x, top, z, pal(x, top, z))
                for (h in 1..ROAD_CLEAR) c.put(x, top + h, z, Kit.AIR)
            }
        }
    }

    /**
     * Feuerpfosten am Weg: Sockel auf dem Gelände bei (x, z), darauf eine grüne Feuerschale ([brazier], Schaft [height]
     * hoch). Steht auf Grund (Fundament bis in den Boden).
     */
    fun firePost(c: BlockCanvas, x: Int, z: Int, height: Int = 2) {
        if (x !in c.minX..c.maxX || z !in c.minZ..c.maxZ) return
        val y = c.ground(x, z)
        c.footing(x, z, y - 1, { _, _, _ -> Blocks.POLISHED_BLACKSTONE.defaultState })
        c.block(x, y, z, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultState)
        c.brazier(x, y + 1, z, height)
    }

    /** Feuerpfosten beidseits eines Zuwegs, alle [spacing] Blöcke, [offset] neben der Mittellinie. */
    fun firePosts(c: BlockCanvas, from: BlockPos, to: BlockPos, offset: Double, spacing: Int = 8) {
        val dx = (to.x - from.x).toDouble()
        val dz = (to.z - from.z).toDouble()
        val len = hypot(dx, dz)
        if (len < 1.0) return
        val ux = dx / len
        val uz = dz / len
        var s = spacing / 2.0
        while (s < len) {
            for (side in intArrayOf(-1, 1)) {
                val x = floor(from.x + 0.5 + ux * s - uz * offset * side).toInt()
                val z = floor(from.z + 0.5 + uz * s + ux * offset * side).toInt()
                firePost(c, x, z)
            }
            s += spacing
        }
    }
}
