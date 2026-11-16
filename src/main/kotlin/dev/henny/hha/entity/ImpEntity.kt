package dev.henny.hha.entity

import dev.henny.hha.HhaEntities
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.logic.boss.BossTimings
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.Flutterer
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.ai.control.FlightMoveControl
import net.minecraft.entity.ai.goal.ActiveTargetGoal
import net.minecraft.entity.ai.goal.FlyGoal
import net.minecraft.entity.ai.goal.Goal
import net.minecraft.entity.ai.goal.LookAroundGoal
import net.minecraft.entity.ai.goal.LookAtEntityGoal
import net.minecraft.entity.ai.goal.RevengeGoal
import net.minecraft.entity.ai.pathing.BirdNavigation
import net.minecraft.entity.ai.pathing.EntityNavigation
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.mob.HostileEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.util.Hand
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.EnumSet
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ein Teufelchen aus der Unterwelt, Bewohner des Tartaros (Plan 4.6): eine eigene fliegende Kreatur, die **nicht** durch
 * Wände fliegt (Vogel-Pfadsuche statt Plagegeist). Es umkreist sein Ziel auf Abstand und greift mit Ansage an:
 *
 * - **Stich** mit dem Zweizack: holt aus (`swingHand`, das Modell spielt 18 Ticks), trifft bei
 *   [BossTimings.Imp.STAB_WINDUP] und setzt in Brand.
 * - **Feuerball** aus der erhobenen, glühenden Hand: lädt [BossTimings.Imp.FIREBALL_WINDUP] Ticks ([fireballCharging]),
 *   dann fliegt ein Feuerball gerade (ohne Zielsuche).
 *
 * Höchstens zwei in einer Gruppe (`TartarosLife`), 16 Leben.
 */
class ImpEntity(type: EntityType<out ImpEntity>, world: World) : HostileEntity(type, world), Flutterer {

    /** Bleibt aus Altbeständen (ein Bosskampf hat es gerufen): Dann verschwindet es mit ihm und lässt nichts fallen. */
    var summoned = false

    init {
        moveControl = FlightMoveControl(this, 20, true)
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(FIREBALL_CHARGING, false)
    }

    /** Lädt es gerade einen Feuerball in der erhobenen Hand auf? Der Renderer reicht es als `charging` weiter. */
    var fireballCharging: Boolean
        get() = dataTracker.get(FIREBALL_CHARGING)
        set(value) = dataTracker.set(FIREBALL_CHARGING, value)

    /** Nur auf dem Client: wann [fireballCharging] zuletzt gewechselt hat. */
    var chargeChangedAge = 0
        private set

    override fun onTrackedDataSet(data: TrackedData<*>) {
        super.onTrackedDataSet(data)
        if (data == FIREBALL_CHARGING && entityWorld.isClient) chargeChangedAge = age
    }

    override fun createNavigation(world: World): EntityNavigation {
        val navigation = BirdNavigation(this, world)
        navigation.setCanOpenDoors(false)
        navigation.setCanSwim(false)
        navigation.setMaxFollowRange(40f)
        return navigation
    }

    override fun initGoals() {
        goalSelector.add(2, ImpAttackGoal())
        goalSelector.add(6, FlyGoal(this, 1.0))
        goalSelector.add(7, LookAtEntityGoal(this, PlayerEntity::class.java, 10f))
        goalSelector.add(8, LookAroundGoal(this))
        targetSelector.add(1, RevengeGoal(this))
        targetSelector.add(2, ActiveTargetGoal(this, PlayerEntity::class.java, true))
    }

    /** Vortrieb in der Luft (Standard 0,02 wäre für einen Kobold zu träge). */
    override fun getOffGroundSpeed(): Float = AIR_SPEED

    override fun isInAir(): Boolean = !isOnGround

    override fun handleFallDamage(fallDistance: Double, damagePerDistance: Float, damageSource: DamageSource): Boolean = false

    override fun tryAttack(world: ServerWorld, target: Entity): Boolean {
        val hit = super.tryAttack(world, target)
        if (hit) target.setOnFireFor(3f)
        return hit
    }

    override fun isFireImmune(): Boolean = true

    override fun getSoundPitch(): Float = 0.7f + random.nextFloat() * 0.25f
    override fun getAmbientSound(): SoundEvent = SoundEvents.ENTITY_VEX_AMBIENT
    override fun getHurtSound(source: DamageSource): SoundEvent = SoundEvents.ENTITY_VEX_HURT
    override fun getDeathSound(): SoundEvent = SoundEvents.ENTITY_VEX_DEATH

    override fun shouldSave(): Boolean = !summoned

    override fun dropLoot(world: ServerWorld, damageSource: DamageSource, causedByPlayer: Boolean) {
        if (!summoned) super.dropLoot(world, damageSource, causedByPlayer)
    }

    /**
     * Umkreist das Ziel auf Abstand und wählt im Wechsel Stich (nah) oder Feuerball (fern). Jeder Angriff hat seine
     * Ansage im Modell (Ausholen, glühende Hand) und einen Ton; danach eine Pause.
     */
    private inner class ImpAttackGoal : Goal() {
        private var rest = 20
        private var orbit = random.nextDouble() * Math.PI * 2.0
        private var closing = -1
        private var stab = -1
        private var charge = -1
        private var lastWasStab = false

        init {
            controls = EnumSet.of(Control.MOVE, Control.LOOK)
        }

        override fun canStart(): Boolean {
            val target = target ?: return false
            return target.isAlive && squaredDistanceTo(target) < 28.0 * 28.0
        }

        override fun shouldContinue(): Boolean = canStart() || stab >= 0 || charge >= 0

        override fun shouldRunEveryTick(): Boolean = true

        override fun stop() {
            if (charge >= 0) fireballCharging = false
            stab = -1
            charge = -1
            closing = -1
        }

        override fun tick() {
            val world = entityWorld as? ServerWorld ?: return
            val target = target
            if (target == null || !target.isAlive) {
                stop()
                return
            }
            lookControl.lookAt(target, 30f, 30f)
            when {
                stab >= 0 -> stabTick(world, target)
                charge >= 0 -> chargeTick(world, target)
                closing >= 0 -> closeIn(target)
                else -> circle(target)
            }
        }

        private fun circle(target: LivingEntity) {
            orbit += 0.045
            val goal = target.entityPos.add(cos(orbit) * 6.0, 2.4, sin(orbit) * 6.0)
            moveControl.moveTo(goal.x, goal.y, goal.z, 1.0)
            if (--rest > 0) return
            val distance = sqrt(squaredDistanceTo(target))
            // Im Wechsel, damit es nicht immer dasselbe tut; aus der Ferne nur, wenn es das Ziel sieht.
            val wantStab = distance < 5.0 || (!lastWasStab && distance < 9.0)
            if (wantStab) {
                closing = 0
            } else if (distance < 22.0 && canSee(target)) {
                charge = 0
                fireballCharging = true
                playSound(SoundEvents.ENTITY_BLAZE_BURN, 0.8f, 1.6f)
            } else {
                rest = 10
            }
        }

        private fun closeIn(target: LivingEntity) {
            closing++
            val goal = target.entityPos.add(0.0, target.height * 0.55, 0.0)
            moveControl.moveTo(goal.x, goal.y, goal.z, 1.4)
            if (squaredDistanceTo(target) < 2.2 * 2.2) {
                closing = -1
                stab = 0
                swingHand(Hand.MAIN_HAND)
                playSound(SoundEvents.ENTITY_VEX_CHARGE, 0.9f, 1.3f)
            } else if (closing > 50) {
                closing = -1
                rest = 20
            }
        }

        /** Stich: ausholen, bei STAB_WINDUP zustoßen (kurzer Vorstoß), bei STAB_END fertig. */
        private fun stabTick(world: ServerWorld, target: LivingEntity) {
            stab++
            moveControl.moveTo(x, y, z, 0.2)
            if (stab == BossTimings.Imp.STAB_WINDUP) {
                val toward = target.entityPos.add(0.0, target.height * 0.5, 0.0).subtract(entityPos).normalize()
                velocity = velocity.add(toward.multiply(0.45))
                if (squaredDistanceTo(target) < BossTimings.Imp.STAB_REACH * BossTimings.Imp.STAB_REACH) tryAttack(world, target)
            }
            if (stab >= BossTimings.Imp.STAB_END) {
                stab = -1
                lastWasStab = true
                rest = BossTimings.Imp.REST + random.nextInt(BossTimings.Imp.REST)
            }
        }

        /** Feuerball: steht in der Luft, lädt in der erhobenen Hand, wirft bei FIREBALL_WINDUP, Salto bis FIREBALL_END. */
        private fun chargeTick(world: ServerWorld, target: LivingEntity) {
            charge++
            moveControl.moveTo(x, y, z, 0.2)
            if (charge == BossTimings.Imp.FIREBALL_WINDUP) {
                fireballCharging = false
                throwFireball(world, target)
            }
            if (charge >= BossTimings.Imp.FIREBALL_END) {
                charge = -1
                lastWasStab = false
                rest = BossTimings.Imp.REST + random.nextInt(BossTimings.Imp.REST + 20)
            }
        }

        private fun throwFireball(world: ServerWorld, target: LivingEntity) {
            val bolt = HhaEntities.HERMES_BOLT.create(world, SpawnReason.EVENT) ?: return
            bolt.kind = HermesBoltEntity.FIRE
            bolt.size = 0.55f
            bolt.damage = 4f
            bolt.homing = 0.0
            bolt.setOwner(this@ImpEntity)
            // Aus der Glut in der erhobenen rechten Hand (Anfrage imp.md): 0,44 nach rechts, 0,93 hoch.
            val yaw = Math.toRadians(bodyYaw.toDouble())
            val right = Vec3d(-cos(yaw), 0.0, -sin(yaw))
            val origin = entityPos.add(right.multiply(0.44)).add(0.0, 0.93, 0.0)
            bolt.setPosition(origin)
            bolt.velocity = target.eyePos.subtract(0.0, 0.4, 0.0).subtract(origin).normalize().multiply(0.7)
            world.spawnEntity(bolt)
            world.playSound(null, x, y, z, SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.HOSTILE, 0.7f, 1.5f)
        }
    }

    companion object {
        private val FIREBALL_CHARGING: TrackedData<Boolean> = DataTracker.registerData(ImpEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)

        private const val AIR_SPEED = 0.045f

        fun attributes(): DefaultAttributeContainer.Builder = createHostileAttributes()
            .add(EntityAttributes.MAX_HEALTH, 16.0)
            .add(EntityAttributes.ATTACK_DAMAGE, 4.0)
            .add(EntityAttributes.FOLLOW_RANGE, 40.0)
            .add(EntityAttributes.MOVEMENT_SPEED, 0.3)
            .add(EntityAttributes.FLYING_SPEED, 0.7)
    }
}
