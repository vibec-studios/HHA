package dev.henny.hha.logic

import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import java.util.UUID

/**
 * Hell's Sword Aktiv-Fähigkeit: 20 Sekunden Haste + Auto-Crits, 90 Sekunden Cooldown.
 */
object SwordBuff {

    const val DURATION_TICKS = 400
    const val COOLDOWN_TICKS = 1800

    private val active = HashMap<UUID, Long>()

    fun activate(world: ServerWorld, player: ServerPlayerEntity) {
        active[player.uuid] = world.server!!.ticks + DURATION_TICKS.toLong()
        player.addStatusEffect(
            StatusEffectInstance(StatusEffects.HASTE, DURATION_TICKS, 4, false, true, true)
        )
        world.playSound(
            null, player.blockPos, SoundEvents.ENTITY_BLAZE_SHOOT,
            SoundCategory.PLAYERS, 1.0f, 1.4f
        )
        world.playSound(
            null, player.blockPos, SoundEvents.ITEM_TRIDENT_RETURN,
            SoundCategory.PLAYERS, 0.8f, 0.6f
        )
        Fx.spiral(world, player.entityPos, 0.8, 2.2, dev.henny.hha.HhaParticles.HELLFIRE)
        Vfx.aura(world, Vfx.Style.FIRE, player, DURATION_TICKS, 0.9f)
        Vfx.nova(world, Vfx.Style.FIRE, player.entityPos.add(0.0, 1.0, 0.0), 3.5f, 12)
        Vfx.skyMood(world, Vfx.Style.FIRE, player.entityPos, 0.5f, 60, 24f)
        Fx.ring(world, player.entityPos.add(0.0, 0.15, 0.0), 1.4, dev.henny.hha.HhaParticles.EMBER_SPARK, 18, 0.05)
        Fx.spawn(world, 
            dev.henny.hha.HhaParticles.EMBER_SPARK,
            player.x, player.y + 1.2, player.z,
            10, 0.3, 0.6, 0.3, 0.03
        )
    }

    fun isActive(server: MinecraftServer, player: ServerPlayerEntity): Boolean {
        val expiry = active[player.uuid] ?: return false
        if (server.ticks >= expiry) {
            active.remove(player.uuid)
            return false
        }
        return true
    }

    fun tick(server: MinecraftServer) {
        active.entries.removeIf { server.ticks >= it.value }
        if (active.isEmpty() || server.ticks % 8 != 0) return

        for (world in server.worlds) {
            for (player in world.players) {
                if (player.uuid !in active) continue
                Fx.spawn(world, 
                    dev.henny.hha.HhaParticles.EMBER_SPARK,
                    player.x, player.y + 1.0, player.z,
                    1, 0.35, 0.5, 0.35, 0.0
                )
            }
        }
    }
}
