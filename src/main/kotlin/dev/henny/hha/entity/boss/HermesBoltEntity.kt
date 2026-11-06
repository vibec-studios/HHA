package dev.henny.hha.entity.boss

import dev.henny.hha.logic.Vfx
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.projectile.ProjectileEntity
import net.minecraft.entity.projectile.ProjectileUtil
import net.minecraft.server.world.ServerWorld
import net.minecraft.storage.ReadView
import net.minecraft.storage.WriteView
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.EntityHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.world.World

/** Geschosse des Bosses: Federpfeil (leicht zielsuchend) und Seelenlanze (schnell und gerade). */
class HermesBoltEntity(type: EntityType<out HermesBoltEntity>, world: World) : ProjectileEntity(type, world) {

    var damage = 6f
    var homing = 0.0
    var target: LivingEntity? = null
    private var lifetime = 0

    override fun initDataTracker(builder: DataTracker.Builder) {
        builder.add(KIND, FEATHER)
    }

    var kind: Int
        get() = dataTracker.get(KIND)
        set(value) = dataTracker.set(KIND, value)

    override fun tick() {
        super.tick()
        val world = entityWorld
        if (world is ServerWorld) {
            lifetime++
            if (lifetime > 200) {
                discard()
                return
            }
            val aim = target
            if (homing > 0.0 && aim != null && aim.isAlive && lifetime < 50) {
                val speed = velocity.length()
                val desired = aim.entityPos.add(0.0, aim.height * 0.5, 0.0).subtract(entityPos).normalize().multiply(speed)
                velocity = velocity.lerp(desired, homing).normalize().multiply(speed)
                velocityDirty = true
            }
            val hit = ProjectileUtil.getCollision(this, ::canHit)
            if (hit.type != HitResult.Type.MISS) {
                hitOrDeflect(hit)
                if (isRemoved) return
            }
        }
        setPosition(entityPos.add(velocity))
        ProjectileUtil.setRotationFromVelocity(this, 1f)
    }

    override fun canHit(entity: Entity): Boolean = entity is PlayerEntity && super.canHit(entity)

    override fun onEntityHit(entityHitResult: EntityHitResult) {
        val world = entityWorld as? ServerWorld ?: return
        val owner = getOwner() as? LivingEntity
        entityHitResult.entity.damage(world, world.damageSources.mobProjectile(this, owner), damage)
        impact(world)
    }

    override fun onBlockHit(blockHitResult: BlockHitResult) {
        val world = entityWorld as? ServerWorld ?: return
        impact(world)
    }

    private fun impact(world: ServerWorld) {
        Vfx.flare(world, if (kind == LANCE) Vfx.Style.SOUL else Vfx.Style.GOLD, entityPos, 1.6f, 7)
        if (kind == LANCE) Vfx.shockwave(world, Vfx.Style.SOUL, entityPos, 2.6f, 10, 0.8f)
        discard()
    }

    override fun readCustomData(view: ReadView) {}
    override fun writeCustomData(view: WriteView) {}
    override fun shouldSave(): Boolean = false

    companion object {
        const val FEATHER = 0
        const val LANCE = 1
        private val KIND: TrackedData<Int> = DataTracker.registerData(HermesBoltEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
    }
}
