package dev.henny.hha.logic

import dev.henny.hha.HhaParticles
import net.minecraft.entity.Entity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.Vec3d
import java.util.UUID

/**
 * Hell's Mace: Kette an die eigene Wind Charge oder Enderperle werfen und
 * sich hinterherziehen lassen — Movement-Tech für Fortgeschrittene.
 */
object ProjectileGrapple {

    private const val TIMEOUT_TICKS = 100L
    private const val REEL_SPEED = 0.9
    private const val ARRIVE_DISTANCE = 2.0

    private class Ride(
        val projectile: UUID,
        val expiry: Long,
        /** Erst nach einmaligem Nicht-Sneaken scharf — wer sneakend abfeuert, ridet trotzdem. */
        var sneakArmed: Boolean,
    )

    private val rides = HashMap<UUID, Ride>()

    fun start(world: ServerWorld, player: ServerPlayerEntity, projectile: Entity) {
        rides[player.uuid] = Ride(projectile.uuid, world.time + TIMEOUT_TICKS, !player.isSneaking)
        Vfx.chain(world, Vfx.Style.FIRE, player, projectile.entityPos, projectile, TIMEOUT_TICKS.toInt())
        world.playSound(null, player.blockPos, SoundEvents.BLOCK_CHAIN_PLACE, SoundCategory.PLAYERS, 1.0f, 0.9f)
        world.playSound(null, player.blockPos, SoundEvents.ITEM_TRIDENT_THROW.value(), SoundCategory.PLAYERS, 0.8f, 1.2f)
    }

    fun tick(world: ServerWorld) {
        if (rides.isEmpty()) return
        val iterator = rides.entries.iterator()
        while (iterator.hasNext()) {
            val (uuid, ride) = iterator.next()
            val player = world.getPlayerByUuid(uuid) as? ServerPlayerEntity ?: continue
            val projectile = world.getEntity(ride.projectile)
            if (world.time >= ride.expiry || projectile == null || !projectile.isAlive || !player.isAlive) {
                iterator.remove()
                continue
            }

            // Sneaken lässt die Kette los — Schwung weg, Spieler fällt.
            if (!player.isSneaking) {
                ride.sneakArmed = true
            } else if (ride.sneakArmed) {
                GrappleFlight.release(world, player)
                iterator.remove()
                continue
            }

            val toProjectile = projectile.entityPos.subtract(player.entityPos)
            val distance = toProjectile.length()
            if (distance <= ARRIVE_DISTANCE) {
                iterator.remove()
                continue
            }

            val direction = toProjectile.multiply(1.0 / distance)
            val drag = projectile.velocity.multiply(0.6)
            Fx.launchPlayer(
                player,
                Vec3d(
                    direction.x * REEL_SPEED + drag.x,
                    direction.y * REEL_SPEED * 0.9 + drag.y + 0.08,
                    direction.z * REEL_SPEED + drag.z,
                )
            )
            player.fallDistance = 0.0

            if (world.time % 2L == 0L) {
                val steps = (distance * 2.5).toInt().coerceIn(4, 40)
                for (i in 1 until steps) {
                    val p = player.eyePos.add(toProjectile.multiply(i.toDouble() / steps))
                    Fx.spawnRaw(world, HhaParticles.CHAIN_LINK, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0)
                }
            }
        }
    }
}
