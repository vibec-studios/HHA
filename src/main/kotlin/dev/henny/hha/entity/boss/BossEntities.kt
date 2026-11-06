package dev.henny.hha.entity.boss

import dev.henny.hha.logic.boss.HermesFights
import net.minecraft.entity.EntityType
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.mob.HostileEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.storage.ReadView
import net.minecraft.storage.WriteView
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World

/** Animationszustände von Hermes; die Werte gehen über die Leitung. */
object HermesPose {
    const val IDLE = 0
    const val DASH = 1
    const val CAST = 2
    const val THROW = 3
    const val SLAM = 4
    const val READ = 5
    const val TAUNT = 6
    const val KNEEL = 7
    const val STAGGER = 8
    const val ASCEND = 9
    const val SNAP = 10
    const val FALL = 11
    const val LETTER = 12
}

/** Hintergrund-Wesen des Bossfights: kein KI-Eigenleben, alles steuert [dev.henny.hha.logic.boss.HermesFight]. */
abstract class PuppetEntity(type: EntityType<out HostileEntity>, world: World) : HostileEntity(type, world) {

    init {
        setNoGravity(true)
        noClip = true
        setPersistent()
    }

    override fun tick() {
        super.tick()
        velocity = Vec3d.ZERO
        bodyYaw = yaw
        headYaw = yaw
    }

    override fun takeKnockback(strength: Double, x: Double, z: Double) {}
    override fun isPushable(): Boolean = false
    override fun pushAwayFrom(entity: net.minecraft.entity.Entity) {}
    override fun canHaveStatusEffect(effect: StatusEffectInstance): Boolean = false
    override fun isFireImmune(): Boolean = true
    override fun canImmediatelyDespawn(distanceSquared: Double): Boolean = false
    override fun shouldSave(): Boolean = false
    override fun canUsePortals(allowVehicles: Boolean): Boolean = false
    override fun getAmbientSound() = null
    override fun getHurtSound(source: DamageSource): net.minecraft.sound.SoundEvent? = null
    override fun getDeathSound() = null
}

/** Hermes selbst — oder, mit gesetztem [isIllusion], eines seiner Trugbilder. */
class HermesEntity(type: EntityType<out HermesEntity>, world: World) : PuppetEntity(type, world) {

    /** Nur auf dem Client: Pose vor dem letzten Wechsel und wann gewechselt wurde (für weiche Übergänge). */
    var previousPose = HermesPose.IDLE
        private set
    var poseChangedAge = 0
        private set

    /** Nur auf dem Client: letzte Positionen für die Nachbilder im Dash. */
    val history = ArrayDeque<Vec3d>()

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(POSE, HermesPose.IDLE)
        builder.add(ILLUSION, false)
        builder.add(STAGE, 1)
        builder.add(VULNERABLE, true)
        builder.add(CORRUPTED, false)
        builder.add(GUIDE, false)
    }

    var pose: Int
        get() = dataTracker.get(POSE)
        set(value) = dataTracker.set(POSE, value)

    var isIllusion: Boolean
        get() = dataTracker.get(ILLUSION)
        set(value) = dataTracker.set(ILLUSION, value)

    var stage: Int
        get() = dataTracker.get(STAGE)
        set(value) = dataTracker.set(STAGE, value)

    /** Stage 4: Hermes ist nur in der Erschöpfung nach einem Sturzflug verwundbar. */
    var vulnerable: Boolean
        get() = dataTracker.get(VULNERABLE)
        set(value) = dataTracker.set(VULNERABLE, value)

    /** Der Schatten des Boten aus der Hades-Halle: gleiches Skelett, verdorbene Textur und grünes Licht. */
    var corrupted: Boolean
        get() = dataTracker.get(CORRUPTED)
        set(value) = dataTracker.set(CORRUPTED, value)

    /** Der Geleiter im Tartaros: nur eine Erscheinung, die niemand treffen kann. */
    var guide: Boolean
        get() = dataTracker.get(GUIDE)
        set(value) = dataTracker.set(GUIDE, value)

    override fun canHit(): Boolean = !guide && super.canHit()

    private var lastPoseSeen = HermesPose.IDLE

    override fun onTrackedDataSet(data: TrackedData<*>) {
        super.onTrackedDataSet(data)
        if (data == POSE && entityWorld.isClient) {
            previousPose = lastPoseSeen
            lastPoseSeen = pose
            poseChangedAge = age
        }
    }

    override fun tick() {
        super.tick()
        if (entityWorld.isClient) {
            history.addLast(entityPos)
            while (history.size > 8) history.removeFirst()
        }
    }

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        if (guide) return false
        val fight = HermesFights.of(world) ?: run {
            discard()
            return false
        }
        val allowed = fight.onBossDamaged(this, source, amount)
        if (allowed <= 0f) return false
        return super.damage(world, source, allowed)
    }

    companion object {
        private val POSE: TrackedData<Int> = DataTracker.registerData(HermesEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val ILLUSION: TrackedData<Boolean> = DataTracker.registerData(HermesEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        private val STAGE: TrackedData<Int> = DataTracker.registerData(HermesEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val VULNERABLE: TrackedData<Boolean> = DataTracker.registerData(HermesEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        private val CORRUPTED: TrackedData<Boolean> = DataTracker.registerData(HermesEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        private val GUIDE: TrackedData<Boolean> = DataTracker.registerData(HermesEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)

        fun attributes(): DefaultAttributeContainer.Builder = MobEntity.createMobAttributes()
            .add(EntityAttributes.MAX_HEALTH, 1000.0)
            .add(EntityAttributes.ARMOR, 0.0)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(EntityAttributes.FOLLOW_RANGE, 128.0)
    }
}

/** Eine der beiden Caduceus-Schlangen; den Körper rechnet der Client aus der Flugbahn. */
class SerpentEntity(type: EntityType<out SerpentEntity>, world: World) : PuppetEntity(type, world) {

    override fun getHurtSound(source: DamageSource): net.minecraft.sound.SoundEvent? = dev.henny.hha.HhaSounds.HIT

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(VARIANT, 0)
        builder.add(BITING, false)
    }

    var variant: Int
        get() = dataTracker.get(VARIANT)
        set(value) = dataTracker.set(VARIANT, value)

    var biting: Boolean
        get() = dataTracker.get(BITING)
        set(value) = dataTracker.set(BITING, value)

    /** Glieder-Positionen (aktuell und Vortick). Der Server rechnet sie mit, weil die Trefferzonen daran hängen. */
    val body = ArrayList<Vec3d>()
    val bodyPrev = ArrayList<Vec3d>()

    /** Nur auf dem Server: unsichtbare Trefferzonen entlang des Körpers und vor dem Kopf. */
    private val parts = ArrayList<SerpentPartEntity>()

    override fun tick() {
        super.tick()
        updateBody()
        val world = entityWorld
        if (world is ServerWorld) syncParts(world)
    }

    /**
     * Die Kollisionsbox einer Entität ist ein einziger Quader, der Drache aber fast 16 Blöcke lang. Darum tragen
     * mitfliegende Teile die Treffer an den Kopf weiter, nach dem Muster der Enderdrachen-Teile.
     */
    private fun syncParts(world: ServerWorld) {
        if (parts.isEmpty() && isAlive) {
            val count = SEGMENTS / PART_STEP + 1
            repeat(count + 1) { index ->
                val part = dev.henny.hha.HhaEntities.SERPENT_PART.create(world, net.minecraft.entity.SpawnReason.EVENT) ?: return
                part.owner = this
                // Index 0 ist die Schnauze, dann vom Hals zum Schwanz immer schmaler.
                val taper = if (index == 0) 1.1f else (1.7f * (1f - (index.toFloat() / count) * 0.62f))
                part.size = taper
                part.setPosition(entityPos)
                world.spawnEntity(part)
                parts.add(part)
            }
        }
        val radians = Math.toRadians(yaw.toDouble())
        for ((index, part) in parts.withIndex()) {
            val anchor = if (index == 0) {
                entityPos.add(-kotlin.math.sin(radians) * 1.05, 0.64, kotlin.math.cos(radians) * 1.05)
            } else {
                val at = ((index - 1) * PART_STEP + PART_STEP / 2).coerceAtMost(body.size - 1)
                body[at].add(0.0, 0.64, 0.0)
            }
            part.setPosition(anchor.x, anchor.y - part.size / 2.0, anchor.z)
        }
    }

    private fun clearParts() {
        for (part in parts) part.discard()
        parts.clear()
    }

    override fun remove(reason: RemovalReason) {
        clearParts()
        super.remove(reason)
    }

    override fun onDeath(source: DamageSource) {
        clearParts()
        super.onDeath(source)
    }

    private fun updateBody() {
        val head = entityPos
        if (body.isEmpty()) {
            repeat(SEGMENTS) {
                body.add(head)
                bodyPrev.add(head)
            }
        }
        for (i in body.indices) bodyPrev[i] = body[i]
        var previous = head
        for (i in body.indices) {
            val offset = body[i].subtract(previous)
            val distance = offset.length()
            if (distance > SPACING) {
                body[i] = previous.add(offset.multiply(SPACING / distance))
            }
            previous = body[i]
        }
    }

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        val fight = HermesFights.of(world) ?: run {
            discard()
            return false
        }
        if (!fight.canHurtMinions()) return false
        val result = super.damage(world, source, amount.coerceAtMost(30f))
        if (result && health <= 0f) fight.onSerpentDied(this)
        return result
    }

    companion object {
        const val SEGMENTS = 38
        const val SPACING = 0.42

        /** Alle wie viele Glieder eine Trefferzone sitzt. */
        const val PART_STEP = 3

        private val VARIANT: TrackedData<Int> = DataTracker.registerData(SerpentEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val BITING: TrackedData<Boolean> = DataTracker.registerData(SerpentEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)

        fun attributes(): DefaultAttributeContainer.Builder = MobEntity.createMobAttributes()
            .add(EntityAttributes.MAX_HEALTH, 120.0)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
    }
}

/** Unsichtbare Trefferzone einer Schlange; jeder Schaden geht direkt an den Kopf. */
class SerpentPartEntity(type: EntityType<out SerpentPartEntity>, world: World) : net.minecraft.entity.Entity(type, world) {

    var owner: SerpentEntity? = null

    init {
        setNoGravity(true)
        noClip = true
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        builder.add(SIZE, 1.4f)
    }

    var size: Float
        get() = dataTracker.get(SIZE)
        set(value) = dataTracker.set(SIZE, value)

    override fun onTrackedDataSet(data: TrackedData<*>) {
        super.onTrackedDataSet(data)
        if (data == SIZE) calculateDimensions()
    }

    override fun getDimensions(pose: net.minecraft.entity.EntityPose): net.minecraft.entity.EntityDimensions =
        net.minecraft.entity.EntityDimensions.changing(size, size)

    override fun tick() {
        super.tick()
        val world = entityWorld
        if (world is ServerWorld && owner?.isAlive != true) discard()
    }

    override fun canHit(): Boolean = true
    override fun isPushable(): Boolean = false
    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean = owner?.damage(world, source, amount) ?: false
    override fun readCustomData(view: ReadView) {}
    override fun writeCustomData(view: WriteView) {}
    override fun shouldSave(): Boolean = false

    private companion object {
        val SIZE: TrackedData<Float> = DataTracker.registerData(SerpentPartEntity::class.java, TrackedDataHandlerRegistry.FLOAT)
    }
}

/** Herma — Grenzstein mit Hermes-Büste. Solange Steine stehen, kann Hermes zwischen ihnen springen. */
class HermaEntity(type: EntityType<out HermaEntity>, world: World) : PuppetEntity(type, world) {

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        val fight = HermesFights.of(world) ?: run {
            discard()
            return false
        }
        if (!fight.canHurtMinions()) return false
        val result = super.damage(world, source, amount.coerceAtMost(20f))
        if (result && health <= 0f) fight.onHermaDestroyed(this)
        return result
    }

    companion object {
        fun attributes(): DefaultAttributeContainer.Builder = MobEntity.createMobAttributes()
            .add(EntityAttributes.MAX_HEALTH, 50.0)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
    }
}

/** Das Götterpaket: sinkt am Ende herab und öffnet sich per Rechtsklick. */
class DivineParcelEntity(type: EntityType<out DivineParcelEntity>, world: World) : net.minecraft.entity.Entity(type, world) {

    init {
        setNoGravity(true)
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        builder.add(OPEN_AGE, -1)
    }

    /** Ticks seit dem Öffnen, -1 = noch verschlossen. */
    var openAge: Int
        get() = dataTracker.get(OPEN_AGE)
        set(value) = dataTracker.set(OPEN_AGE, value)

    override fun tick() {
        super.tick()
        if (!entityWorld.isClient && openAge >= 0) openAge += 1
    }

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean = false

    override fun canHit(): Boolean = true

    override fun interact(player: PlayerEntity, hand: Hand): ActionResult {
        val world = entityWorld as? ServerWorld ?: return ActionResult.SUCCESS
        val fight = HermesFights.of(world) ?: return ActionResult.PASS
        fight.onParcelOpened(this, player)
        return ActionResult.SUCCESS
    }

    override fun readCustomData(view: ReadView) {}
    override fun writeCustomData(view: WriteView) {}
    override fun shouldSave(): Boolean = false

    companion object {
        private val OPEN_AGE: TrackedData<Int> = DataTracker.registerData(DivineParcelEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
    }
}
