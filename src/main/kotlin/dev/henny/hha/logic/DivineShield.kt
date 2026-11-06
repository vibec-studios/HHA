package dev.henny.hha.logic

import dev.henny.hha.config.HhaConfig
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import java.util.UUID

/**
 * Heaven's Chestplate: Divine Shield — einmal pro Minute wird ein tödlicher
 * Treffer abgefangen und der Spieler überlebt mit 3 Herzen (6 HP).
 */
object DivineShield {

    private const val SURVIVE_HEALTH = 6.0f

    private val readyAt = HashMap<UUID, Long>()

    fun init() {
        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, _, _ ->
            if (entity is ServerPlayerEntity && HhaConfig.enabled("divine_shield") &&
                HeavenSet.hasChestplate(entity) &&
                FactionLock.canUse(entity, entity.getEquippedStack(EquipmentSlot.CHEST), notify = false)
            ) {
                !tryAbsorbLethalHit(entity)
            } else {
                true
            }
        }
    }

    /** @return true, wenn der tödliche Treffer abgefangen wurde. */
    private fun tryAbsorbLethalHit(player: ServerPlayerEntity): Boolean {
        val world = player.entityWorld
        val cooldown = HhaConfig.num("divine_shield_cooldown").toLong()
        val ticks = world.server!!.ticks
        if (!UltraMode.isActive(player)) {
            if (ticks < readyAt.getOrDefault(player.uuid, 0L)) return false
            readyAt[player.uuid] = ticks + cooldown
        }

        player.health = SURVIVE_HEALTH
        player.addStatusEffect(StatusEffectInstance(StatusEffects.REGENERATION, 100, 0, false, true, true))
        player.itemCooldownManager.set(player.getEquippedStack(EquipmentSlot.CHEST), cooldown.toInt())

        world.playSound(
            null, player.blockPos, SoundEvents.ITEM_TOTEM_USE,
            SoundCategory.PLAYERS, 1.0f, 1.3f
        )
        Fx.spawn(world, 
            dev.henny.hha.HhaParticles.DIVINE_FLASH,
            player.x, player.y + 1.2, player.z,
            1, 0.0, 0.0, 0.0, 0.0
        )
        Fx.spawn(world, 
            dev.henny.hha.HhaParticles.HOLY_SPARK,
            player.x, player.y + 1.0, player.z,
            30, 0.5, 0.8, 0.5, 0.08
        )
        Vfx.shield(world, Vfx.Style.HEAVEN, player, 1.7f, 30)
        Vfx.nova(world, Vfx.Style.HEAVEN, player.entityPos.add(0.0, 1.0, 0.0), 5f, 16)
        Vfx.feathers(world, Vfx.Style.HEAVEN, player.entityPos.add(0.0, 1.4, 0.0), 26, 1.3f, 50)
        Vfx.skyMood(world, Vfx.Style.GOLD, player.entityPos, 0.8f, 70, 32f)
        Vfx.screenFlash(player, Vfx.Style.HEAVEN, 0.55f, 12)
        Vfx.shake(world, player.entityPos, 3f, 12, 16f)
        Fx.spawn(world, 
            dev.henny.hha.HhaParticles.LIGHT_MOTE,
            player.x, player.y + 1.0, player.z,
            22, 0.4, 0.6, 0.4, 0.08
        )
        return true
    }
}
