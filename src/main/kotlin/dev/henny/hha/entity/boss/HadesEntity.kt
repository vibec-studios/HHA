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
 * Animationszustände von Hades; die Werte gehen über die Leitung, also nur hinten anfügen. Zeiten: `BossTimings.Hades`.
 * [SWEEP] ist Hieb A **mit** Ausholen; [SLAM], [THRUST] und [THROW] enthalten ihr Ausholen; [WINDUP] bleibt die neutrale
 * Drohhaltung für Cutscenes.
 */
object HadesPose {
    const val IDLE = 0
    const val SIT = 1
    const val CAST = 2
    const val SLAM = 3
    const val KNEEL = 4
    const val FALLEN = 5
    const val WINDUP = 6
    const val SWEEP = 7
    const val THRUST = 8
    const val THROW = 9
    const val ROAR = 10
    const val STAGGER = 11

    /** Hieb B: Rückhand links → rechts. */
    const val SWEEP_B = 12

    /** Schlusshieb von oben, danach auf den Bident gestützt (offen). */
    const val SWEEP_C = 13

    /** Bident steckt nach dem Bodenschlag (offen). */
    const val STUCK = 14

    /** Hand leer nach dem Wurf (offen). */
    const val EMPTY = 15

    /** Der Blick. */
    const val GAZE = 16

    /** Seelenketten werfen und ziehen. */
    const val CHAINS = 17

    /** Setzt den Helm auf (Schattenschritt). */
    const val VANISH = 18

    /** Taucht hinter dem Ziel auf und schlägt. */
    const val AMBUSH = 19

    /** Das Urteil. */
    const val JUDGE = 20
}

/** Hades selbst. Wie alle Boss-Wesen ohne eigene KI: Der Kampf bewegt ihn. */
class HadesEntity(type: EntityType<out HadesEntity>, world: World) : PuppetEntity(type, world) {

    /** Nur auf dem Client: vorherige Pose und Zeitpunkt des Wechsels, für weiche Übergänge. */
    var previousPose = HadesPose.IDLE
        private set
    var poseChangedAge = 0
        private set
    private var lastPoseSeen = HadesPose.IDLE

    /** Nur auf dem Client: Gangphase und wie stark er gerade läuft. Der Server schiebt ihn nur herum. */
    var walkPhase = 0f
        private set
    var walkAmount = 0f
        private set
    private var seenX = 0.0
    private var seenZ = 0.0
    private var seen = false

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(POSE_ID, HadesPose.IDLE)
        builder.add(CLOAKED, false)
        builder.add(STAGE, 1)
        builder.add(FORM, 0)
        builder.add(BIDENT_OUT, false)
    }

    var pose: Int
        get() = dataTracker.get(POSE_ID)
        set(value) = dataTracker.set(POSE_ID, value)

    /** Trägt er gerade den Helm der Unsichtbarkeit? */
    var cloaked: Boolean
        get() = dataTracker.get(CLOAKED)
        set(value) = dataTracker.set(CLOAKED, value)

    var stage: Int
        get() = dataTracker.get(STAGE)
        set(value) = dataTracker.set(STAGE, value)

    /** Fliegt der Bident gerade durch den Saal? Dann ist die Hand leer. */
    var bidentOut: Boolean
        get() = dataTracker.get(BIDENT_OUT)
        set(value) = dataTracker.set(BIDENT_OUT, value)

    /** Wie oft er schon aufgestanden ist: Mit jedem Mal wird er größer. */
    var form: Int
        get() = dataTracker.get(FORM)
        set(value) {
            dataTracker.set(FORM, value)
            calculateDimensions()
        }

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
        // Ein großer Sprung ist ein Stoß oder das Auftauchen aus dem Nichts, kein Gehen.
        val pace = if (moved > 0.9) 0.0 else moved
        walkPhase += (pace * 1.5).toFloat()
        walkAmount += ((pace / WALK_SPEED).toFloat().coerceAtMost(1f) - walkAmount) * 0.22f
    }

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        val fight = HadesFights.of(world) ?: run {
            discard()
            return false
        }
        val allowed = fight.onDamaged(this, source, amount)
        if (allowed <= 0f) return false
        return super.damage(world, source, allowed)
    }

    companion object {
        const val FORM_GROWTH = 0.2f
        private const val WALK_SPEED = 0.22

        private val POSE_ID: TrackedData<Int> = DataTracker.registerData(HadesEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val CLOAKED: TrackedData<Boolean> = DataTracker.registerData(HadesEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        private val STAGE: TrackedData<Int> = DataTracker.registerData(HadesEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val FORM: TrackedData<Int> = DataTracker.registerData(HadesEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val BIDENT_OUT: TrackedData<Boolean> = DataTracker.registerData(HadesEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)

        fun attributes(): DefaultAttributeContainer.Builder = MobEntity.createMobAttributes()
            .add(EntityAttributes.MAX_HEALTH, 1000.0)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(EntityAttributes.FOLLOW_RANGE, 128.0)
    }
}
