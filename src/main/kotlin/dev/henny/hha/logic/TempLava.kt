package dev.henny.hha.logic

import net.minecraft.block.Blocks
import net.minecraft.registry.RegistryKey
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.world.World

/** Kurzlebige Lava-Blöcke (Magma Stomp) — werden nach Ablauf wieder entfernt. */
object TempLava {

    /** Wie weit weggeflossene Lava um eine Quelle noch weggeräumt wird. */
    private const val SWEEP_RADIUS_SQ = 10.0 * 10.0

    /** Obergrenze entfernter Fließ-Lava-Blöcke pro Quelle (Sicherheitsnetz). */
    private const val SWEEP_BUDGET = 128

    private val lava = HashMap<RegistryKey<World>, HashMap<BlockPos, Long>>()

    fun place(world: ServerWorld, pos: BlockPos, lifetimeTicks: Long) {
        if (!world.getBlockState(pos).isAir || dev.henny.hha.logic.boss.HermesFights.isProtected(world, pos)) return
        world.setBlockState(pos, Blocks.LAVA.defaultState)
        lava.getOrPut(world.registryKey) { HashMap() }[pos.toImmutable()] = world.time + lifetimeTicks
    }

    fun tick(world: ServerWorld) {
        val map = lava[world.registryKey] ?: return
        if (map.isEmpty()) return

        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val (pos, expiry) = iterator.next()
            if (world.time < expiry) continue
            removePool(world, pos)
            iterator.remove()
        }
    }

    /**
     * Entfernt die gesetzte Quelle und alle von ihr weggeflossenen Lava-Blöcke
     * per Flutfüllung. Natürliche Quellblöcke (still, aber nicht von uns
     * getrackt) bleiben unangetastet — nur Fließ-Lava wird mitgeräumt.
     */
    private fun removePool(world: ServerWorld, source: BlockPos) {
        if (world.getBlockState(source).isOf(Blocks.LAVA)) {
            // removeBlock() würde den Fluid-Blockzustand wieder einsetzen (Vanilla-
            // "Block abbauen, Flüssigkeit behalten") — Lava muss explizit Luft werden.
            world.setBlockState(source, Blocks.AIR.defaultState)
        }

        val queue = ArrayDeque<BlockPos>()
        val seen = HashSet<BlockPos>()
        queue.add(source)
        seen.add(source)
        var budget = SWEEP_BUDGET

        while (queue.isNotEmpty() && budget > 0) {
            val current = queue.removeFirst()
            for (direction in Direction.entries) {
                val next = current.offset(direction)
                if (!seen.add(next)) continue
                if (next.getSquaredDistance(source) > SWEEP_RADIUS_SQ) continue
                if (!world.getBlockState(next).isOf(Blocks.LAVA)) continue
                if (world.getFluidState(next).isStill) continue
                world.setBlockState(next, Blocks.AIR.defaultState)
                budget--
                queue.add(next)
            }
        }
    }
}
