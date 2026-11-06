package dev.henny.hha.logic.boss

import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Vec3d
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Das Himmelsforum: eine schwebende Marmorscheibe hoch über dem Beschwörungsort. Jeder überschriebene Block
 * wird gemerkt und am Ende exakt wiederhergestellt; zwischen den Stages färbt sich der Boden wellenförmig um.
 */
class HermesArena(private val world: ServerWorld, val center: BlockPos, private val permanent: Boolean = false) {

    /** Innerhalb dieser Zone lässt der Kampf keine Änderung an Blöcken zu. */
    fun contains(pos: BlockPos): Boolean {
        val reach = RADIUS + if (permanent) 14 else 4
        if (pos.y !in (center.y - 14)..(center.y + if (permanent) 44 else 60)) return false
        return hypot((pos.x - center.x).toDouble(), (pos.z - center.z).toDouble()) <= reach
    }


    /** Originalzustand aller veränderten Positionen. */
    private val original = LinkedHashMap<BlockPos, BlockState>()
    private val forcedChunks = ArrayList<ChunkPos>()

    /** Noch umzufärbende Blöcke, nach Abstand zur Mitte sortiert (ergibt die Welle). */
    private val repaintQueue = ArrayDeque<Pair<BlockPos, BlockState>>()

    val floorY: Double get() = center.y + 1.0

    fun centerPos(): Vec3d = Vec3d(center.x + 0.5, floorY, center.z + 0.5)

    fun build(stage: Int) {
        forceChunks()
        // Die Hades-Halle steht bereits; sie wird nicht gebaut und nicht wieder abgetragen.
        if (permanent) return
        for (dx in -RADIUS..RADIUS) {
            for (dz in -RADIUS..RADIUS) {
                val r = hypot(dx.toDouble(), dz.toDouble())
                if (r > RADIUS + 0.5) continue
                val pos = center.add(dx, 0, dz)
                put(pos, floorBlock(stage, dx, dz, r))
                if (r > RADIUS - 3.5) {
                    // Die Unterseite verjüngt sich: wirkt wie eine schwebende Schale.
                    put(pos.down(), Blocks.SMOOTH_QUARTZ.defaultState)
                } else if (r > RADIUS - 8) {
                    put(pos.down(2), Blocks.SMOOTH_QUARTZ.defaultState)
                }
            }
        }
        for (i in 0 until COLUMNS) {
            val angle = i.toDouble() / COLUMNS * Math.PI * 2.0
            val cx = (kotlin.math.cos(angle) * (RADIUS - 1.5)).toInt()
            val cz = (kotlin.math.sin(angle) * (RADIUS - 1.5)).toInt()
            for (y in 1..COLUMN_HEIGHT) {
                val block = when (y) {
                    COLUMN_HEIGHT -> Blocks.GOLD_BLOCK
                    1 -> Blocks.CHISELED_QUARTZ_BLOCK
                    else -> Blocks.QUARTZ_PILLAR
                }
                put(center.add(cx, y, cz), block.defaultState)
            }
            put(center.add(cx, COLUMN_HEIGHT + 1, cz), Blocks.SEA_LANTERN.defaultState)
        }
    }

    /** Färbt den Boden der neuen Stage ein; die Welle läuft von der Mitte nach außen. */
    fun repaint(stage: Int) {
        repaintQueue.clear()
        if (permanent) return
        val entries = ArrayList<Triple<Double, BlockPos, BlockState>>()
        for (dx in -RADIUS..RADIUS) {
            for (dz in -RADIUS..RADIUS) {
                val r = hypot(dx.toDouble(), dz.toDouble())
                if (r > RADIUS + 0.5) continue
                entries.add(Triple(r, center.add(dx, 0, dz), floorBlock(stage, dx, dz, r)))
            }
        }
        entries.sortBy { it.first }
        for ((_, pos, state) in entries) repaintQueue.addLast(pos to state)
    }

    fun tick() {
        var budget = BLOCKS_PER_TICK
        while (budget-- > 0 && repaintQueue.isNotEmpty()) {
            val (pos, state) = repaintQueue.removeFirst()
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
        }
    }

    private fun put(pos: BlockPos, state: BlockState) {
        val key = pos.toImmutable()
        original.putIfAbsent(key, world.getBlockState(key))
        world.setBlockState(key, state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
    }

    private fun floorBlock(stage: Int, dx: Int, dz: Int, r: Double): BlockState {
        val angle = atan2(dz.toDouble(), dx.toDouble())
        val spoke = floor((angle / (Math.PI * 2.0) + 0.5) * 8.0 + 0.5).let { (angle / (Math.PI * 2.0) + 0.5) * 8.0 - it }
        val onSpoke = kotlin.math.abs(spoke) * r * (Math.PI * 2.0 / 8.0) < 0.8 && r > 5.0
        val ringGold = r in 4.5..5.5 || r in 13.0..14.0 || r in (RADIUS - 2.0)..(RADIUS - 1.0)
        val ringLight = r in 9.0..9.5 && (kotlin.math.abs(dx) + kotlin.math.abs(dz)) % 4 == 0
        val checker = (floor(r / 2.0).toInt() + floor((angle + Math.PI) * 4.0).toInt()) % 2 == 0
        val edge = r > RADIUS - 0.8
        return when (stage) {
            3 -> when {
                edge -> Blocks.CRYING_OBSIDIAN
                ringGold || onSpoke -> Blocks.PRISMARINE
                ringLight -> Blocks.SEA_LANTERN
                checker -> Blocks.POLISHED_BLACKSTONE
                else -> Blocks.BLACKSTONE
            }
            4 -> when {
                edge -> Blocks.GOLD_BLOCK
                ringGold || onSpoke -> Blocks.GOLD_BLOCK
                ringLight -> Blocks.SEA_LANTERN
                checker -> Blocks.SMOOTH_SANDSTONE
                else -> Blocks.CUT_SANDSTONE
            }
            else -> when {
                edge -> Blocks.CHISELED_QUARTZ_BLOCK
                ringGold || onSpoke -> Blocks.GOLD_BLOCK
                ringLight -> Blocks.SEA_LANTERN
                checker -> Blocks.SMOOTH_QUARTZ
                else -> Blocks.QUARTZ_BRICKS
            }
        }.defaultState
    }

    private fun forceChunks() {
        val min = ChunkPos(center.add(-RADIUS - 2, 0, -RADIUS - 2))
        val max = ChunkPos(center.add(RADIUS + 2, 0, RADIUS + 2))
        for (x in min.x..max.x) {
            for (z in min.z..max.z) {
                if (world.setChunkForced(x, z, true)) forcedChunks.add(ChunkPos(x, z))
            }
        }
    }

    /** Stellt alles wieder her, auch wenn der Kampf abbricht. */
    fun restore() {
        repaintQueue.clear()
        for ((pos, state) in original) {
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
        }
        original.clear()
        for (chunk in forcedChunks) world.setChunkForced(chunk.x, chunk.z, false)
        forcedChunks.clear()
    }

    companion object {
        const val RADIUS = 26
        const val COLUMNS = 8
        const val COLUMN_HEIGHT = 7
        private const val BLOCKS_PER_TICK = 160

        /** Höhe des Forums: deutlich über dem Boden, aber unterhalb der Weltobergrenze. */
        fun chooseHeight(world: ServerWorld, groundY: Int): Int =
            (groundY + 80).coerceAtLeast(190).coerceAtMost(world.topYInclusive - 30)
    }
}
