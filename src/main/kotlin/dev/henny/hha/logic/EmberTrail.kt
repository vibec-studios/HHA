package dev.henny.hha.logic

import net.minecraft.block.AbstractFireBlock
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.tag.BlockTags
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.world.World

/**
 * Boots: Ember Trail — Feuerblöcke beim Sprinten. Feinde darin fangen Feuer und
 * nehmen Schaden wie auf einem Magmablock, aber armor- und feuerresistenz-ignorierend.
 */
object EmberTrail {

    private const val FIRE_LIFETIME = 45L
    private const val DAMAGE_INTERVAL = 10L

    private data class FireEntry(val expiry: Long, val owner: java.util.UUID)

    private val fires = HashMap<RegistryKey<World>, HashMap<BlockPos, FireEntry>>()

    /** Letzte Blockposition pro sprintendem Spieler — Feuer entsteht im verlassenen Block. */
    private val lastPos = HashMap<java.util.UUID, BlockPos>()

    fun place(world: ServerWorld, player: ServerPlayerEntity) {
        val current = player.blockPos.toImmutable()
        val previous = lastPos.put(player.uuid, current)
        if (previous == null || previous == current) return
        if (!world.getBlockState(previous).isAir || dev.henny.hha.logic.boss.HermesFights.isProtected(world, previous)) return
        val below = previous.down()
        if (!world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP)) return

        world.setBlockState(previous, AbstractFireBlock.getState(world, previous))
        fires.getOrPut(world.registryKey) { HashMap() }[previous] =
            FireEntry(world.time + FIRE_LIFETIME, player.uuid)
        Fx.spawn(world, 
            dev.henny.hha.HhaParticles.EMBER_SPARK,
            previous.x + 0.5, previous.y + 0.4, previous.z + 0.5,
            3, 0.25, 0.2, 0.25, 0.02
        )
    }

    fun stopTrail(playerUuid: java.util.UUID) {
        lastPos.remove(playerUuid)
    }

    fun tick(world: ServerWorld) {
        val map = fires[world.registryKey] ?: return
        if (map.isEmpty()) return

        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            val (pos, entry) = iterator.next()
            val state = world.getBlockState(pos)
            if (!state.isIn(BlockTags.FIRE)) {
                iterator.remove()
                continue
            }
            if (world.time >= entry.expiry) {
                world.removeBlock(pos, false)
                iterator.remove()
            }
        }

        if (world.time % DAMAGE_INTERVAL != 0L) return
        for ((pos, entry) in map) {
            val owner = world.getPlayerByUuid(entry.owner) as? net.minecraft.server.network.ServerPlayerEntity
            val box = Box(pos).expand(0.25, 0.25, 0.25)
            for (entity in world.getEntitiesByClass(LivingEntity::class.java, box, { it.isAlive })) {
                if (owner != null) {
                    if (!Targeting.shouldHarm(owner, entity)) continue
                } else {
                    if (entity is PlayerEntity || BruteAllies.isAlly(entity.uuid)) continue
                }
                entity.damage(world, world.damageSources.magic(), dev.henny.hha.config.HhaConfig.numF("ember_damage"))
                entity.setOnFireFor(4.0f)
            }
        }
    }
}
