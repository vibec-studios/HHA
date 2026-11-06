package dev.henny.hha.logic

import dev.henny.hha.HhaParticles
import dev.henny.hha.config.HhaConfig
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.projectile.SmallFireballEntity
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin

/**
 * Aufprall-Logik (vom LivingEntityMixin aufgerufen):
 * Boots → Fall Damage Immunity, Leggings → Magma Stomp, volles Set → Volcanic.
 */
object FallEvents {

    /** @return true, wenn der Fallschaden komplett unterdrückt werden soll. */
    @JvmStatic
    fun onFall(player: ServerPlayerEntity, fallDistance: Double): Boolean {
        val world = player.entityWorld
        val boots = player.getEquippedStack(EquipmentSlot.FEET)
        val leggings = player.getEquippedStack(EquipmentSlot.LEGS)
        val hellBootsAllowed = HellSet.hasBoots(player) && FactionLock.canUse(player, boots, notify = false)
        val heavenBootsAllowed = HeavenSet.hasBoots(player) && FactionLock.canUse(player, boots, notify = false)
        val hellLeggingsAllowed = HellSet.hasLeggings(player) && FactionLock.canUse(player, leggings, notify = false)
        val heavenLeggingsAllowed = HeavenSet.hasLeggings(player) && FactionLock.canUse(player, leggings, notify = false)
        val immune = HhaConfig.enabled("fall_immunity") &&
            (hellBootsAllowed || heavenBootsAllowed)

        if (fallDistance >= HhaConfig.num("stomp_min_fall") && hellLeggingsAllowed) {
            if (HhaConfig.enabled("magma_stomp")) magmaStomp(world, player)
            if (HhaConfig.enabled("volcanic") && HellSet.hasFullSet(player)) volcanic(world, player)
        }
        if (fallDistance >= HhaConfig.num("shockwave_min_fall") &&
            HhaConfig.enabled("shockwave") && heavenLeggingsAllowed
        ) {
            shockwave(world, player, fallDistance)
        }
        return immune
    }

    /** Heaven's Leggings: Shockwave — Fallschaden-AoE wie Mace mit Density III. */
    private fun shockwave(world: ServerWorld, player: ServerPlayerEntity, fallDistance: Double) {
        val damage = (HhaConfig.num("shockwave_base_damage") + fallDistance * 1.5)
            .coerceAtMost(HhaConfig.num("shockwave_max_damage")).toFloat()
        val box = Box.of(player.entityPos, 8.0, 3.0, 8.0)
        for (entity in world.getEntitiesByClass(LivingEntity::class.java, box, { it.isAlive && it != player })) {
            if (!Targeting.shouldHarm(player, entity)) continue
            entity.damage(world, world.damageSources.playerAttack(player), damage)
            val away = entity.entityPos.subtract(player.entityPos)
            val horizontal = away.horizontalLength().coerceAtLeast(0.1)
            entity.velocity = Vec3d(away.x / horizontal * 0.9, 0.45, away.z / horizontal * 0.9)
            entity.velocityDirty = true
        }

        world.playSound(
            null, player.blockPos, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY,
            SoundCategory.PLAYERS, 1.0f, 1.0f
        )
        Vfx.shockwave(world, Vfx.Style.HEAVEN, player.entityPos, (6.0 + fallDistance * 0.35).coerceAtMost(14.0).toFloat(), 18, 3f)
        Vfx.feathers(world, Vfx.Style.HEAVEN, player.entityPos.add(0.0, 0.6, 0.0), 18, 1.4f, 40)
        Vfx.shake(world, player.entityPos, 3f, 12, 20f)
        Fx.spawn(world, 
            HhaParticles.DIVINE_FLASH,
            player.x, player.y + 0.6, player.z,
            1, 0.0, 0.0, 0.0, 0.0
        )
        Fx.spawn(world, 
            HhaParticles.HOLY_SPARK,
            player.x, player.y + 0.3, player.z,
            18, 1.6, 0.3, 1.6, 0.05
        )
    }

    /** Leggings: 3×3-Lava-Splash am Aufprallpunkt; Lebensdauer aus der Config (Ticks). */
    private fun magmaStomp(world: ServerWorld, player: ServerPlayerEntity) {
        val feet = player.blockPos
        val lifetime = HhaConfig.num("stomp_lava_lifetime").toLong().coerceAtLeast(1L)

        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val pos = feet.add(dx, 0, dz)
                if (hasSolidFloor(world, pos)) {
                    TempLava.place(world, pos, lifetime)
                }
            }
        }

        val box = Box.of(player.entityPos, 7.0, 3.0, 7.0)
        for (entity in world.getEntitiesByClass(LivingEntity::class.java, box, { it.isAlive && it != player })) {
            if (!Targeting.shouldHarm(player, entity)) continue
            entity.damage(world, world.damageSources.playerAttack(player), HhaConfig.numF("stomp_damage"))
            entity.setOnFireFor(5.0f)
        }

        world.playSound(
            null, feet, SoundEvents.ITEM_BUCKET_EMPTY_LAVA,
            SoundCategory.PLAYERS, 1.0f, 0.8f
        )
        Vfx.shockwave(world, Vfx.Style.FIRE, player.entityPos, 7f, 18, 3f)
        Vfx.pillar(world, Vfx.Style.FIRE, player.entityPos, 1.6f, 6f, 18)
        Vfx.shake(world, player.entityPos, 3.5f, 12, 20f)
        Fx.spawn(world,
            HhaParticles.SOOT,
            player.x, player.y + 0.3, player.z,
            12, 1.2, 0.3, 1.2, 0.02
        )
        Fx.spawn(world, 
            HhaParticles.INFERNAL_BURST,
            player.x, player.y + 0.6, player.z,
            1, 0.0, 0.0, 0.0, 0.0
        )
        Fx.spawn(world, 
            HhaParticles.EMBER_SPARK,
            player.x, player.y + 0.4, player.z,
            22, 1.4, 0.4, 1.4, 0.05
        )
    }

    private fun hasSolidFloor(world: ServerWorld, pos: BlockPos): Boolean {
        val below = pos.down()
        return world.getBlockState(pos).isAir &&
            world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP)
    }

    /** Set-Bonus: Aufprall schießt 3–5 Feuerbälle in alle Richtungen. */
    private fun volcanic(world: ServerWorld, player: ServerPlayerEntity) {
        val count = 3 + world.random.nextInt(3)
        for (i in 0 until count) {
            val angle = (i.toDouble() / count) * Math.PI * 2.0 + world.random.nextDouble() * 0.5
            val dirX = cos(angle)
            val dirZ = sin(angle)
            val velocity = Vec3d(dirX * 0.7, 0.12, dirZ * 0.7)
            val fireball = SmallFireballEntity(world, player, velocity)
            fireball.setPosition(player.x + dirX * 1.3, player.y + 1.0, player.z + dirZ * 1.3)
            world.spawnEntity(fireball)
        }
        world.playSound(
            null, player.blockPos, SoundEvents.ENTITY_BLAZE_SHOOT,
            SoundCategory.PLAYERS, 1.0f, 0.7f
        )
        Fx.spawn(world, 
            HhaParticles.HELLFIRE,
            player.x, player.y + 0.6, player.z,
            20, 1.0, 0.5, 1.0, 0.04
        )
        Fx.spawn(world, 
            HhaParticles.EMBER_SPARK,
            player.x, player.y + 0.5, player.z,
            18, 1.0, 0.4, 1.0, 0.06
        )
    }
}
