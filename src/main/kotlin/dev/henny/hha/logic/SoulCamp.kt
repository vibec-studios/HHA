package dev.henny.hha.logic

import dev.henny.hha.HhaParticles
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.registry.RegistryKey
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import java.util.UUID

/**
 * Fire Camp Kulisse: Um den Beschwörungsort entsteht ein echtes Soul-Lager —
 * Soul Campfire in der Mitte, ein unregelmäßiger Teppich aus Soul Soil und
 * Soul Sand (Radius ca. 5–7 Blöcke) mit verstreuten Soul Torches und Soul
 * Lanterns. Sobald alle drei Brutes tot oder abgelaufen sind, wird jeder
 * Block exakt in seinen Ursprungszustand zurückversetzt.
 */
object SoulCamp {

    /** Harte Obergrenze: Brute-Lebenszeit (10 Minuten) plus Sicherheitspuffer. */
    private const val MAX_LIFETIME_TICKS = BruteAllies.LIFETIME_TICKS + 100L

    private class Camp(
        val brutes: Set<UUID>,
        val center: BlockPos,
        /** Kampfeuer: Position und vorheriger Zustand. */
        val campfire: Pair<BlockPos, BlockState>?,
        /** Boden, der zu Soul Soil/Sand wurde: Position → Originalzustand. */
        val converted: LinkedHashMap<BlockPos, BlockState>,
        /** Weggeräumte Pflanzen/Schnee über dem Boden: Position → Original. */
        val cleared: LinkedHashMap<BlockPos, BlockState>,
        /** Gesetzte Soul Torches/Lanterns: Position → Originalzustand (Luft). */
        val decorations: LinkedHashMap<BlockPos, BlockState>,
        val expiry: Long,
    )

    private val camps = HashMap<RegistryKey<World>, ArrayList<Camp>>()

    fun create(world: ServerWorld, player: ServerPlayerEntity, brutes: Collection<UUID>) {
        if (brutes.isEmpty()) return
        val random = world.random

        val fireColumn = player.blockPos.offset(player.horizontalFacing, 2)
        val center = findGround(world, fireColumn.x, fireColumn.z, player.blockY)
            ?: findGround(world, player.blockX, player.blockZ, player.blockY)
            ?: return
        // In einer Arena gibt es kein Lager: Der Boden dort ist unantastbar.
        if (dev.henny.hha.logic.boss.HermesFights.isProtected(world, center)) return

        val converted = LinkedHashMap<BlockPos, BlockState>()
        val cleared = LinkedHashMap<BlockPos, BlockState>()

        for (dx in -7..7) {
            for (dz in -7..7) {
                val distSq = (dx * dx + dz * dz).toDouble()
                val edge = 4.5 + random.nextDouble() * 2.5
                if (distSq > edge * edge) continue
                val ground = findGround(world, center.x + dx, center.z + dz, center.y) ?: continue
                val state = world.getBlockState(ground)
                if (state.isOf(Blocks.SOUL_SOIL) || state.isOf(Blocks.SOUL_SAND)) continue

                val above = ground.up()
                val aboveState = world.getBlockState(above)
                if (!aboveState.isAir && aboveState.isReplaceable) {
                    cleared[above.toImmutable()] = aboveState
                    world.setBlockState(above, Blocks.AIR.defaultState, Block.NOTIFY_LISTENERS)
                }

                converted[ground.toImmutable()] = state
                val soul = if (random.nextFloat() < 0.2f) Blocks.SOUL_SAND else Blocks.SOUL_SOIL
                world.setBlockState(ground, soul.defaultState)
            }
        }
        if (converted.isEmpty()) return

        var campfire: Pair<BlockPos, BlockState>? = null
        val firePos = center.up()
        val firePrev = world.getBlockState(firePos)
        if (firePrev.isAir || firePrev.isReplaceable) {
            campfire = firePos.toImmutable() to firePrev
            world.setBlockState(firePos, Blocks.SOUL_CAMPFIRE.defaultState)
        }

        val decorations = LinkedHashMap<BlockPos, BlockState>()
        val candidates = converted.keys.toMutableList()
        var torches = 0
        var lanterns = 0
        while (candidates.isNotEmpty() && (torches < 4 || lanterns < 3)) {
            val ground = candidates.removeAt(random.nextInt(candidates.size))
            if (ground.getSquaredDistance(center) < 6.0) continue
            val spot = ground.up()
            if (!world.getBlockState(spot).isAir) continue
            if (decorations.keys.any { it.getSquaredDistance(spot) < 5.0 }) continue
            val block = if (torches < 4 && (lanterns >= 3 || random.nextBoolean())) {
                torches++; Blocks.SOUL_TORCH
            } else {
                lanterns++; Blocks.SOUL_LANTERN
            }
            decorations[spot.toImmutable()] = world.getBlockState(spot)
            world.setBlockState(spot, block.defaultState)
        }

        camps.getOrPut(world.registryKey) { ArrayList() }.add(
            Camp(
                brutes.toSet(), center, campfire,
                converted, cleared, decorations,
                world.time + MAX_LIFETIME_TICKS,
            )
        )

        world.playSound(
            null, center, SoundEvents.BLOCK_SOUL_SAND_PLACE,
            SoundCategory.BLOCKS, 1.2f, 0.7f
        )
    }

    fun tick(world: ServerWorld) {
        val list = camps[world.registryKey] ?: return
        if (list.isEmpty()) return

        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val camp = iterator.next()
            // Über BruteAllies prüfen statt world.getEntity(): ein Brute in einem
            // entladenen Chunk zählt sonst fälschlich als tot und das Camp
            // verschwindet, obwohl noch Brutes leben.
            val anyAlive = camp.brutes.any { BruteAllies.isAlly(it) }
            if (anyAlive && world.time < camp.expiry) {
                if (world.time % 20L == 0L && camp.decorations.isNotEmpty()) {
                    val pos = camp.decorations.keys.random()
                    Fx.spawn(world, 
                        HhaParticles.SOUL_MOTE,
                        pos.x + 0.5, pos.y + 0.7, pos.z + 0.5,
                        1, 0.1, 0.15, 0.1, 0.0
                    )
                }
                continue
            }
            restore(world, camp)
            iterator.remove()
        }
    }

    /** Baut das Lager komplett zurück: Dekoration → Feuer → Boden → Pflanzen. */
    private fun restore(world: ServerWorld, camp: Camp) {
        for ((pos, prev) in camp.decorations) {
            val current = world.getBlockState(pos)
            if (current.isOf(Blocks.SOUL_TORCH) || current.isOf(Blocks.SOUL_LANTERN)) {
                world.setBlockState(pos, prev)
            }
        }
        camp.campfire?.let { (pos, prev) ->
            if (world.getBlockState(pos).isOf(Blocks.SOUL_CAMPFIRE)) {
                world.setBlockState(pos, prev)
            }
        }
        for ((pos, prev) in camp.converted) {
            val current = world.getBlockState(pos)
            if (current.isOf(Blocks.SOUL_SOIL) || current.isOf(Blocks.SOUL_SAND)) {
                world.setBlockState(pos, prev)
            }
        }
        for ((pos, prev) in camp.cleared) {
            if (world.getBlockState(pos).isAir) {
                world.setBlockState(pos, prev)
            }
        }

        val center = camp.center
        Fx.spawn(world, 
            HhaParticles.SOUL_MOTE,
            center.x + 0.5, center.y + 1.2, center.z + 0.5,
            18, 2.5, 0.6, 2.5, 0.01
        )
        Fx.spawn(world, 
            HhaParticles.SOOT,
            center.x + 0.5, center.y + 1.0, center.z + 0.5,
            10, 2.0, 0.5, 2.0, 0.01
        )
        world.playSound(
            null, center, SoundEvents.BLOCK_FIRE_EXTINGUISH,
            SoundCategory.BLOCKS, 0.9f, 0.7f
        )
    }

    /**
     * Oberster fester Voll-Block einer Säule nahe der Referenzhöhe — Säulen ohne
     * brauchbaren Boden (Wände, Flüssigkeiten, Block-Entities) werden übersprungen.
     */
    private fun findGround(world: ServerWorld, x: Int, z: Int, yRef: Int): BlockPos? {
        var pos = BlockPos(x, yRef + 3, z)
        repeat(8) {
            val state = world.getBlockState(pos)
            if (!state.isAir && !state.isReplaceable) {
                val above = world.getBlockState(pos.up())
                if (!above.isAir && !above.isReplaceable) return null
                if (state.hasBlockEntity()) return null
                if (!state.fluidState.isEmpty) return null
                if (!state.isSolidBlock(world, pos)) return null
                return pos
            }
            pos = pos.down()
        }
        return null
    }
}
