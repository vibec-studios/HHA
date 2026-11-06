package dev.henny.hha.entity

import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaItems
import dev.henny.hha.config.HhaConfig
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.CustomModelDataComponent
import net.minecraft.entity.EntityType
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.projectile.thrown.ThrownItemEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.EntityHitResult
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.UUID

/**
 * Geworfener Heaven's Mace: fliegt bis zu 50 Blöcke geradeaus, ruft auf jedes
 * lebende Ziel im Weg einen Blitz herab und kehrt dann zum Werfer zurück.
 */
class HeavensMaceEntity : ThrownItemEntity {

    constructor(type: EntityType<out HeavensMaceEntity>, world: World) : super(type, world)

    constructor(world: World, owner: LivingEntity) :
        super(HhaEntities.HEAVENS_MACE, owner, world, createThrownRenderStack())

    private var startPos: Vec3d? = null
    private var returning = false
    private val struck = HashSet<UUID>()
    private var life = 0

    override fun getDefaultItem(): Item = HhaItems.HEAVENS_MACE

    override fun getGravity(): Double = 0.0

    override fun tick() {
        super.tick()
        val world = entityWorld as? ServerWorld ?: return
        life++
        if (startPos == null) {
            startPos = entityPos
            dev.henny.hha.logic.Vfx.trail(world, dev.henny.hha.logic.Vfx.Style.GOLD, this, 0.55f, 420)
            dev.henny.hha.logic.Vfx.orbitRings(world, dev.henny.hha.logic.Vfx.Style.GOLD, this, 0.75f, 420)
        }

        dev.henny.hha.logic.Fx.spawn(world, dev.henny.hha.HhaParticles.LIGHT_MOTE, x, y, z, 1, 0.12, 0.12, 0.12, 0.01)
        if (life % 7 == 0) {
            // Kleine Überschläge rund um den fliegenden Hammer.
            val arc = world.random
            dev.henny.hha.logic.Vfx.bolt(
                world, dev.henny.hha.logic.Vfx.Style.STORM, entityPos,
                entityPos.add(arc.nextDouble() * 3.0 - 1.5, arc.nextDouble() * 3.0 - 1.5, arc.nextDouble() * 3.0 - 1.5),
                0.12f, 4,
            )
        }
        if (life % 4 == 0) {
            dev.henny.hha.logic.Fx.spawn(world, dev.henny.hha.HhaParticles.FEATHER, x, y, z, 1, 0.15, 0.15, 0.15, 0.0)
        }

        if (!returning) {
            smiteEntitiesOnPath(world)
            if (startPos!!.distanceTo(entityPos) >= MAX_RANGE || life > 90) {
                startReturn()
            }
        } else {
            smiteEntitiesOnPath(world)
            val target = this.getOwner()
            if (target == null || !target.isAlive || life > 400) {
                discard()
                return
            }
            val toOwner = target.eyePos.subtract(entityPos)
            if (toOwner.length() < 2.0) {
                world.playSound(null, target.blockPos, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.8f, 1.0f)
                discard()
                return
            }
            velocity = toOwner.normalize().multiply(RETURN_SPEED)
        }
    }

    /**
     * Der Blitz ist rein kosmetisch — der Schaden kommt direkt vom Werfer und
     * ist über `mace_throw_damage` konfigurierbar (Feuer wie beim echten Blitz).
     */
    private fun smiteEntitiesOnPath(world: ServerWorld) {
        val thrower = this.getOwner()
        for (entity in world.getEntitiesByClass(LivingEntity::class.java, boundingBox.expand(1.2), { it.isAlive })) {
            if (entity == thrower || !struck.add(entity.uuid)) continue
            if (thrower is ServerPlayerEntity && !dev.henny.hha.logic.Targeting.shouldHarm(thrower, entity)) continue
            val bolt = EntityType.LIGHTNING_BOLT.create(world, SpawnReason.TRIGGERED) ?: continue
            bolt.refreshPositionAfterTeleport(entity.x, entity.y, entity.z)
            bolt.setCosmetic(true)
            (thrower as? ServerPlayerEntity)?.let { bolt.channeler = it }
            world.spawnEntity(bolt)

            val damage = HhaConfig.numF("mace_throw_damage")
            if (damage > 0f) {
                val source = if (thrower is ServerPlayerEntity) {
                    world.damageSources.playerAttack(thrower)
                } else {
                    world.damageSources.lightningBolt()
                }
                entity.damage(world, source, damage)
                entity.setOnFireFor(8.0f)
            }
            dev.henny.hha.logic.Fx.spawn(world,
                dev.henny.hha.HhaParticles.DIVINE_FLASH,
                entity.x, entity.y + entity.height * 0.5, entity.z,
                1, 0.0, 0.0, 0.0, 0.0
            )
            val strikeTop = entity.entityPos.add(world.random.nextDouble() * 4.0 - 2.0, 24.0, world.random.nextDouble() * 4.0 - 2.0)
            dev.henny.hha.logic.Vfx.bolt(world, dev.henny.hha.logic.Vfx.Style.STORM, strikeTop, entity.entityPos.add(0.0, entity.height * 0.5, 0.0), 0.55f, 10)
            dev.henny.hha.logic.Vfx.shockwave(world, dev.henny.hha.logic.Vfx.Style.STORM, entity.entityPos, 4.5f, 12, 1.6f)
            dev.henny.hha.logic.Vfx.shake(world, entity.entityPos, 2.2f, 8, 18f)
        }
    }

    private fun startReturn() {
        returning = true
        noClip = true
        struck.clear()
    }

    override fun onEntityHit(hitResult: EntityHitResult) {
    }

    override fun onBlockHit(blockHitResult: BlockHitResult) {
        if (!returning) {
            startReturn()
        }
    }

    companion object {
        private const val MAX_RANGE = 50.0
        private const val RETURN_SPEED = 1.6

        /** Nur der Projektil-Stack aktiviert das separate 3D-Modell. */
        private fun createThrownRenderStack(): ItemStack =
            ItemStack(HhaItems.HEAVENS_MACE).apply {
                set(
                    DataComponentTypes.CUSTOM_MODEL_DATA,
                    CustomModelDataComponent(emptyList(), listOf(true), emptyList(), emptyList()),
                )
            }
    }
}
