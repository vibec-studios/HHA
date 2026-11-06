package dev.henny.hha.item

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Cooldowns
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.GrappleBounce
import dev.henny.hha.logic.ChainCharges
import dev.henny.hha.logic.PullTracker
import dev.henny.hha.logic.Targeting
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.projectile.ProjectileUtil
import net.minecraft.item.ItemStack
import net.minecraft.item.MaceItem
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import net.minecraft.world.World

/**
 * Hell's Mace — basiert auf der echten Vanilla-Mace (Smash-Attack, Density etc. bleiben).
 * Rechtsklick auf Gegner (bis pull_range Blöcke): zieht ihn heran, Schaden beim Ankommen.
 * Rechtsklick auf Block (bis grapple_range Blöcke): Grappling Hook, zieht dich selbst hin.
 */
class HellsMaceItem(settings: Settings) : MaceItem(settings) {

    override fun useOnEntity(
        stack: ItemStack,
        user: PlayerEntity,
        entity: LivingEntity,
        hand: Hand,
    ): ActionResult {
        if (user.itemCooldownManager.isCoolingDown(stack)) return ActionResult.PASS
        val world = user.entityWorld as? ServerWorld ?: return ActionResult.SUCCESS
        if (user !is ServerPlayerEntity || !entity.isAlive) return ActionResult.PASS
        if (!HhaConfig.enabled("mace_pull")) return ActionResult.PASS
        if (!Targeting.canPull(user, entity)) return ActionResult.PASS
        if (!ChainCharges.tryUse(user, stack)) return ActionResult.PASS

        pullEntity(world, user, stack, entity)
        return ActionResult.SUCCESS
    }

    override fun use(world: World, user: PlayerEntity, hand: Hand): ActionResult {
        val stack = user.getStackInHand(hand)
        if (user.itemCooldownManager.isCoolingDown(stack)) return ActionResult.PASS
        if (world !is ServerWorld || user !is ServerPlayerEntity) return ActionResult.SUCCESS

        val start = user.eyePos
        val direction = user.getRotationVec(1.0f)

        val pullRange = HhaConfig.num("pull_range")
        val pullEnd = start.add(direction.multiply(pullRange))
        val entityHit = ProjectileUtil.raycast(
            user, start, pullEnd,
            Box(start, pullEnd).expand(1.0),
            { it is LivingEntity && !it.isSpectator && Targeting.canPull(user, it) },
            pullRange * pullRange
        )
        if (entityHit != null && HhaConfig.enabled("mace_pull")) {
            val blockToTarget = world.raycast(
                RaycastContext(
                    start, entityHit.pos,
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, user
                )
            )
            if (blockToTarget.type == HitResult.Type.MISS) {
                if (!ChainCharges.tryUse(user, stack)) return ActionResult.PASS
                pullEntity(world, user, stack, entityHit.entity as LivingEntity)
                return ActionResult.SUCCESS
            }
        }

        if (!HhaConfig.enabled("grapple")) return ActionResult.PASS
        val grappleRange = HhaConfig.num("grapple_range")
        val grappleEnd = start.add(direction.multiply(grappleRange))
        val projectileHit = ProjectileUtil.raycast(
            user, start, grappleEnd,
            Box(start, grappleEnd).expand(1.5),
            { entity ->
                entity.isAlive &&
                    (entity is net.minecraft.entity.projectile.AbstractWindChargeEntity ||
                        entity is net.minecraft.entity.projectile.thrown.EnderPearlEntity) &&
                    (entity as net.minecraft.entity.projectile.ProjectileEntity).getOwner() == user
            },
            grappleRange * grappleRange
        )
        if (projectileHit != null) {
            if (!ChainCharges.tryUse(user, stack)) return ActionResult.PASS
            dev.henny.hha.logic.ProjectileGrapple.start(world, user, projectileHit.entity)
            spawnFireChain(world, user.eyePos, projectileHit.entity.entityPos)
            return ActionResult.SUCCESS
        }

        val hit = world.raycast(
            RaycastContext(
                start, start.add(direction.multiply(grappleRange)),
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, user
            )
        )
        if (hit.type != HitResult.Type.BLOCK) {
            world.playSound(null, user.blockPos, SoundEvents.BLOCK_CHAIN_STEP, SoundCategory.PLAYERS, 0.6f, 0.5f)
            return ActionResult.PASS
        }

        if (!ChainCharges.tryUse(user, stack)) return ActionResult.PASS

        val target = hit.pos
        val delta = target.subtract(user.entityPos)
        val distance = delta.length().coerceAtLeast(0.1)
        val speed = (distance * 0.25).coerceIn(1.1, 3.2)
        val launchDir = delta.multiply(1.0 / distance)
        Fx.launchPlayer(
            user,
            Vec3d(
                launchDir.x * speed,
                launchDir.y * speed * 1.1 + 0.35,
                launchDir.z * speed,
            )
        )
        user.fallDistance = 0.0
        dev.henny.hha.logic.GrappleFlight.start(world, user)

        if (hit.side == Direction.UP) {
            GrappleBounce.arm(world, user)
        }

        world.playSound(null, user.blockPos, SoundEvents.ITEM_TRIDENT_THROW.value(), SoundCategory.PLAYERS, 1.0f, 0.8f)
        world.playSound(null, user.blockPos, SoundEvents.BLOCK_CHAIN_PLACE, SoundCategory.PLAYERS, 1.0f, 0.6f)
        world.playSound(null, user.blockPos, SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.PLAYERS, 0.7f, 1.4f)
        Fx.ring(world, user.entityPos.add(0.0, 0.1, 0.0), 0.9, dev.henny.hha.HhaParticles.HELLFIRE, 14, 0.04)
        spawnFireChain(world, user.eyePos, target)
        dev.henny.hha.logic.Vfx.chain(world, dev.henny.hha.logic.Vfx.Style.FIRE, user, target, null, 12)
        dev.henny.hha.logic.Vfx.flare(world, dev.henny.hha.logic.Vfx.Style.FIRE, target, 1.6f, 8)
        return ActionResult.SUCCESS
    }

    /** Gegner heranziehen — gemeinsame Logik für Nah- und Fernbereich. */
    private fun pullEntity(
        world: ServerWorld,
        user: ServerPlayerEntity,
        stack: ItemStack,
        entity: LivingEntity,
    ) {
        val delta = user.entityPos.subtract(entity.entityPos)
        val horizontal = delta.horizontalLength().coerceAtLeast(0.1)
        val strength = (PULL_STRENGTH + horizontal * 0.06).coerceAtMost(2.4)
        val pull = Vec3d(
            delta.x / horizontal * strength,
            (delta.y * 0.12 + 0.35 + horizontal * 0.02).coerceIn(0.2, 1.0),
            delta.z / horizontal * strength,
        )
        if (entity is ServerPlayerEntity) {
            Fx.launchPlayer(entity, pull)
        } else {
            entity.velocity = pull
            entity.velocityDirty = true
        }
        entity.setOnFireFor(3.0f)
        PullTracker.start(world, entity, user)

        world.playSound(null, entity.blockPos, SoundEvents.ENTITY_FISHING_BOBBER_RETRIEVE, SoundCategory.PLAYERS, 1.0f, 0.6f)
        world.playSound(null, user.blockPos, SoundEvents.BLOCK_CHAIN_HIT, SoundCategory.PLAYERS, 1.0f, 0.7f)
        dev.henny.hha.logic.Fx.spawn(world, 
            dev.henny.hha.HhaParticles.EMBER_SPARK,
            entity.x, entity.y + entity.height * 0.5, entity.z,
            6, 0.3, 0.3, 0.3, 0.02
        )
        spawnFireChain(world, user.eyePos, entity.entityPos.add(0.0, entity.height * 0.5, 0.0))
        dev.henny.hha.logic.Vfx.chain(world, dev.henny.hha.logic.Vfx.Style.FIRE, user, entity.entityPos, entity, 18)
    }

    /** Feuerkette zwischen zwei Punkten — funktionales Visual, wird nie ausgedünnt. */
    private fun spawnFireChain(world: ServerWorld, from: Vec3d, to: Vec3d) {
        val delta = to.subtract(from)
        val steps = (delta.length() * 3.5).toInt().coerceIn(6, 96)
        for (i in 0..steps) {
            val p = from.add(delta.multiply(i.toDouble() / steps))
            dev.henny.hha.logic.Fx.spawnRaw(world, dev.henny.hha.HhaParticles.CHAIN_LINK, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0)
            if (i % 3 == 0) {
                dev.henny.hha.logic.Fx.spawnRaw(world, dev.henny.hha.HhaParticles.EMBER_SPARK, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.0)
            }
        }
    }

    companion object {
        private const val PULL_STRENGTH = 1.35
    }
}
