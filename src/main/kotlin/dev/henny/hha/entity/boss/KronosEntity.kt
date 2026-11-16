package dev.henny.hha.entity.boss

import dev.henny.hha.logic.boss.HadesFights
import net.minecraft.entity.EntityType
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.mob.MobEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.world.World
import kotlin.math.hypot

/**
 * Animationszustände von Kronos; die Werte gehen über die Leitung, also nur hinten anfügen. Zeiten: `BossTimings.Kronos`.
 * [SWEEP] ist der Sichelhieb rechts → links mit Ausholen; [SLAM], [THROW] und [HALT] enthalten ihr Ausholen.
 */
object KronosPose {
    const val IDLE = 0
    const val DESCEND = 1
    const val WINDUP = 2
    const val SWEEP = 3
    const val SLAM = 4
    const val CAST = 5
    const val HALT = 6
    const val ROAR = 7
    const val BOUND = 8
    const val THROW = 9
    const val STAGGER = 10

    /** Sichelhieb links → rechts (Rückhand). */
    const val SWEEP_L = 11

    /** Spalthieb von oben; er richtet sich dafür zur vollen Größe auf. */
    const val CLEAVE = 12

    /** Klinge steckt im Boden (offen). */
    const val STUCK = 13

    /** Hand leer, die Harpe fliegt (offen). */
    const val EMPTY = 14

    /** Zeitschritt. */
    const val STEP = 15

    /** Verschlingen: Sog, Biss, Erholung. */
    const val DEVOUR = 16

    /** Uhrzeiger: Arme waagerecht ausgebreitet. */
    const val HANDS = 17

    /** Rückspulung: dreht die Harpe wie einen Uhrschlüssel. */
    const val REWIND = 18

    /** Von Hades' Ketten gehalten (offen). */
    const val CHAINED = 19
}

/** Kronos, der Titan der Zeit. Wie Hades ein Darsteller ohne eigene KI; der Kampf im Thronsaal bewegt ihn. */
class KronosEntity(type: EntityType<out KronosEntity>, world: World) : PuppetEntity(type, world) {

    var previousPose = KronosPose.IDLE
        private set
    var poseChangedAge = 0
        private set
    private var lastPoseSeen = KronosPose.IDLE
    var walkPhase = 0f
        private set
    var walkAmount = 0f
        private set
    private var seenX = 0.0
    private var seenZ = 0.0
    private var seen = false

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(POSE_ID, KronosPose.IDLE)
        builder.add(FORM, 0)
        builder.add(HARPE_OUT, false)
    }

    var pose: Int
        get() = dataTracker.get(POSE_ID)
        set(value) = dataTracker.set(POSE_ID, value)

    /** Zweite Gestalt: größer, die Risse glühen heller, der Uhrenkranz dreht sich schneller. */
    var form: Int
        get() = dataTracker.get(FORM)
        set(value) {
            dataTracker.set(FORM, value)
            calculateDimensions()
        }

    /** Fliegt die Harpe gerade (Kette C)? Dann ist die Hand leer und das Modell zeigt sie nicht. */
    var harpeOut: Boolean
        get() = dataTracker.get(HARPE_OUT)
        set(value) = dataTracker.set(HARPE_OUT, value)

    override fun getBaseDimensions(pose: net.minecraft.entity.EntityPose): net.minecraft.entity.EntityDimensions =
        super.getBaseDimensions(pose).scaled(1f + form * FORM_GROWTH)

    override fun onTrackedDataSet(data: TrackedData<*>) {
        super.onTrackedDataSet(data)
        if (data == FORM) calculateDimensions()
        if (data == POSE_ID && entityWorld.isClient) {
            previousPose = lastPoseSeen
            lastPoseSeen = pose
            poseChangedAge = age
        }
    }

    override fun tick() {
        super.tick()
        if (!entityWorld.isClient) return
        val moved = if (seen) hypot(x - seenX, z - seenZ) else 0.0
        seenX = x
        seenZ = z
        seen = true
        val pace = if (moved > 0.9) 0.0 else moved
        walkPhase += (pace * 1.1).toFloat()
        walkAmount += ((pace / 0.24).toFloat().coerceAtMost(1f) - walkAmount) * 0.2f
    }

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        val fight = HadesFights.of(world) ?: run {
            discard()
            return false
        }
        val allowed = fight.onKronosDamaged(this, source, amount)
        if (allowed <= 0f) return false
        return super.damage(world, source, allowed)
    }

    companion object {
        const val FORM_GROWTH = 0.18f

        private val POSE_ID: TrackedData<Int> = DataTracker.registerData(KronosEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val FORM: TrackedData<Int> = DataTracker.registerData(KronosEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val HARPE_OUT: TrackedData<Boolean> = DataTracker.registerData(KronosEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)

        fun attributes(): DefaultAttributeContainer.Builder = MobEntity.createMobAttributes()
            .add(EntityAttributes.MAX_HEALTH, 1000.0)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(EntityAttributes.FOLLOW_RANGE, 128.0)
    }
}

/** Wer einen Kern aufgestellt hat, erfährt von Treffern und vom Zerbrechen. */
interface CoreHandler {
    fun onCoreDamaged(core: CoreEntity, source: DamageSource, amount: Float): Float
    fun onCoreBroken(core: CoreEntity)
}

/**
 * Ein Ziel, das man zerschlagen muss: eine Sanduhr des Kronos bei der Rückspulung (golden, Plan 4.5). Hat kein Modell;
 * der Client zeichnet es ganz aus Licht (siehe `client/model/CoreRenderer`). [RIFT] bleibt als Wire-Wert stehen, wird
 * aber nicht mehr benutzt: Die Pforten im Hades-Kampf sind seit Plan 4.4 reine Kulisse.
 */
class CoreEntity(type: EntityType<out CoreEntity>, world: World) : PuppetEntity(type, world) {

    var handler: CoreHandler? = null

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(KIND, RIFT)
    }

    var kind: Int
        get() = dataTracker.get(KIND)
        set(value) = dataTracker.set(KIND, value)

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        val owner = handler ?: run {
            discard()
            return false
        }
        val allowed = owner.onCoreDamaged(this, source, amount)
        if (allowed <= 0f) return false
        val result = super.damage(world, source, allowed)
        if (result && health <= 0f) owner.onCoreBroken(this)
        return result
    }

    /** Zerbricht ohne Leiche: Der Kampf räumt ihn sofort weg. */
    override fun onDeath(damageSource: DamageSource) {}

    override fun tick() {
        super.tick()
        // Sicherung: Ohne Lenker oder ohne laufenden Hades-Kampf (Abbruch, Absturz, Neustart) bleibt keine Sanduhr stehen.
        val world = entityWorld
        if (world is ServerWorld && age > 5 && (handler == null || dev.henny.hha.logic.boss.HadesFights.of(world) == null)) discard()
    }

    companion object {
        const val RIFT = 0
        const val HOURGLASS = 1
        private val KIND: TrackedData<Int> = DataTracker.registerData(CoreEntity::class.java, TrackedDataHandlerRegistry.INTEGER)

        fun attributes(): DefaultAttributeContainer.Builder = MobEntity.createMobAttributes()
            .add(EntityAttributes.MAX_HEALTH, 60.0)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
    }
}
