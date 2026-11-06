package dev.henny.hha.logic

import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.Vec3d
import java.util.UUID

/**
 * Heaven's Leggings: Doppelsprung + Ascension — insgesamt 3 Sprünge
 * (Bodensprung + 2 Luftsprünge). Client meldet den Sprungwunsch per Payload,
 * der Server validiert und synchronisiert die Velocity explizit zurück.
 */
object AirJumps {

    private const val JUMP_VELOCITY = 0.62

    private val used = HashMap<UUID, Int>()

    /** Jeden Tick aufrufen: am Boden werden die Luftsprünge zurückgesetzt. */
    fun tickReset(player: ServerPlayerEntity) {
        if (player.isOnGround || player.isTouchingWater) {
            used.remove(player.uuid)
        }
    }

    fun tryJump(player: ServerPlayerEntity) {
        if (!dev.henny.hha.config.HhaConfig.enabled("air_jumps")) return
        if (!HeavenSet.hasLeggings(player) && !dev.henny.hha.logic.boss.HermesFights.hasMessengerWings(player)) return
        if (player.isOnGround || player.isTouchingWater || player.isSpectator ||
            player.abilities.flying || player.hasVehicle()
        ) return
        val world = player.entityWorld as? ServerWorld ?: return

        val count = used.getOrDefault(player.uuid, 0)
        if (count >= dev.henny.hha.config.HhaConfig.num("air_jumps").toInt()) return
        used[player.uuid] = count + 1

        val look = player.getRotationVec(1.0f)
        val v = player.velocity
        Fx.launchPlayer(
            player,
            Vec3d(
                v.x * 0.9 + look.x * 0.35,
                JUMP_VELOCITY,
                v.z * 0.9 + look.z * 0.35,
            )
        )
        player.fallDistance = 0.0

        val feet = player.entityPos.add(0.0, 0.05, 0.0)
        Vfx.shockwave(world, Vfx.Style.HEAVEN, feet, 2.4f, 9, 0.5f)
        Vfx.wings(world, Vfx.Style.HEAVEN, player, 16, 0.75f)
        Fx.spawn(world, 
            dev.henny.hha.HhaParticles.FEATHER,
            feet.x, feet.y + 0.3, feet.z,
            3, 0.4, 0.1, 0.4, 0.0
        )
        world.playSound(
            null, player.blockPos, SoundEvents.ENTITY_BREEZE_JUMP,
            SoundCategory.PLAYERS, 0.9f, if (count == 0) 1.3f else 1.6f
        )
    }
}
