package dev.henny.hha.logic

import net.minecraft.entity.LivingEntity
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.Vec3d
import java.util.UUID

/**
 * Hell's Mace: herangezogene Gegner werden wie an einer Kette kontinuierlich
 * nachgezogen, bis sie wirklich beim Angreifer ankommen (< 5 Blöcke), und
 * bekommen beim Ankommen sofort Schaden.
 */
object PullTracker {

    private const val ARRIVAL_RANGE = 4.0
    private const val REEL_INTERVAL = 3L
    private const val TIMEOUT_TICKS = 80L

    private data class Pull(val attacker: UUID, val expiry: Long)

    private val pulls = HashMap<UUID, Pull>()

    fun start(world: ServerWorld, target: LivingEntity, attacker: ServerPlayerEntity) {
        pulls[target.uuid] = Pull(attacker.uuid, world.time + TIMEOUT_TICKS)
    }

    fun tick(world: ServerWorld) {
        if (pulls.isEmpty()) return
        val iterator = pulls.entries.iterator()
        while (iterator.hasNext()) {
            val (uuid, pull) = iterator.next()
            if (world.time >= pull.expiry) {
                iterator.remove()
                continue
            }
            val target = world.getEntity(uuid) as? LivingEntity ?: continue
            val attacker = world.getPlayerByUuid(pull.attacker) as? ServerPlayerEntity ?: run {
                iterator.remove()
                continue
            }
            if (!target.isAlive) {
                iterator.remove()
                continue
            }
            if (target.distanceTo(attacker) > ARRIVAL_RANGE) {
                if (world.time % REEL_INTERVAL == 0L) reel(target, attacker)
                continue
            }
            if (Targeting.shouldHarm(attacker, target)) {
                target.damage(world, world.damageSources.playerAttack(attacker), dev.henny.hha.config.HhaConfig.numF("pull_arrival_damage"))
            }
            Vfx.nova(world, Vfx.Style.FIRE, target.entityPos.add(0.0, target.height * 0.5, 0.0), 3.2f, 12)
            Fx.spawn(world,
                dev.henny.hha.HhaParticles.HELLFIRE,
                target.x, target.y + target.height * 0.5, target.z,
                12, 0.3, 0.4, 0.3, 0.05
            )
            Fx.spawn(world, 
                dev.henny.hha.HhaParticles.EMBER_SPARK,
                target.x, target.y + target.height * 0.5, target.z,
                10, 0.2, 0.3, 0.2, 0.04
            )
            world.playSound(
                null, target.blockPos, SoundEvents.ENTITY_PLAYER_ATTACK_CRIT,
                SoundCategory.PLAYERS, 1.0f, 0.9f
            )
            world.playSound(
                null, target.blockPos, SoundEvents.ENTITY_BLAZE_HURT,
                SoundCategory.PLAYERS, 0.7f, 1.2f
            )
            iterator.remove()
        }
    }

    /** Zieht das Ziel erneut Richtung Angreifer — stärker, je weiter es noch weg ist. */
    private fun reel(target: LivingEntity, attacker: ServerPlayerEntity) {
        val delta = attacker.entityPos.subtract(target.entityPos)
        val distance = delta.length().coerceAtLeast(0.1)
        val direction = delta.multiply(1.0 / distance)
        val speed = (0.55 + distance * 0.08).coerceAtMost(1.6)
        val velocity = Vec3d(
            direction.x * speed,
            (direction.y * speed + 0.12).coerceIn(-0.4, 0.8),
            direction.z * speed,
        )
        if (target is ServerPlayerEntity) {
            Fx.launchPlayer(target, velocity)
        } else {
            target.velocity = velocity
            target.velocityDirty = true
        }
        target.fallDistance = 0.0
    }
}
