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

/** Animationszustände von Hermes; die Werte gehen über die Leitung, also nur hinten anfügen. Zeiten: `BossTimings.Hermes`. */
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

    /** Hält den zweiten Brief (Absender: Hades) vor sich, bevor er ihn zuwirft. */
    const val MESSAGE = 12

    /** Geduckt vor dem Kurierstoß; danach [DASH]. */
    const val DASH_WINDUP = 13

    /** Bremst nach dem letzten Stoß, rutscht auf ein Knie (offen). */
    const val SKID = 14

    /** Federfächer: zwei Würfe in einer Pose. */
    const val FAN = 15

    /** Rammt den Stab in den Boden (Die Zwillinge). */
    const val PLANT = 16

    /** Im Kurierstoß abgefangen (offen). */
    const val STUMBLE = 17

    /** Nach dem Sturmschlag: Stab steckt im Boden (offen). */
    const val STUCK = 18

    /** Stage IV: Sturzflug. */
    const val DIVE = 19

    /** Entfallen (Rauchschritt des früheren Schattens); Wert bleibt belegt, das Modell zeigt die Grundhaltung. */
    const val SMOKE = 20

    /** Entfallen (Münzwurf des früheren Schattens); Wert bleibt belegt. */
    const val COIN = 21
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
        builder.add(LOOT, false)
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

    /**
     * Hermes im Dienst des Hades (Geist im dritten Kampfabschnitt von Hades, `HadesFight`): grün getöntes Trugbild mit
     * grünem Licht. Seit Überarbeitung 2 ohne eigene Textur und ohne eigene Teile.
     */
    var corrupted: Boolean
        get() = dataTracker.get(CORRUPTED)
        set(value) = dataTracker.set(CORRUPTED, value)

    /** Hermes im Tartaros: eine Erscheinung, die man nicht töten, aber mit einem Treffer vertreiben kann. */
    var guide: Boolean
        get() = dataTracker.get(GUIDE)
        set(value) = dataTracker.set(GUIDE, value)

    /** Diebesgriff: Hält er gerade die Beute-Kugel mit den gestohlenen Effekten in der linken Hand? */
    var loot: Boolean
        get() = dataTracker.get(LOOT)
        set(value) = dataTracker.set(LOOT, value)

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
        if (guide) {
            if (dev.henny.hha.logic.boss.HadesFights.of(world)?.onHermesStruck(this) != true) {
                dev.henny.hha.logic.world.TartarosGuide.onStruck(this, world)
            }
            return false
        }
        val fight = HermesFights.of(world) ?: run {
            discard()
            return false
        }
        val allowed = fight.onBossDamaged(this, source, amount)
        if (allowed <= 0f) return false
        val hurt = super.damage(world, source, allowed)
        // Erst ein angenommener Treffer zählt für die Smash-Folge der Abwehr (PLAN 4.3).
        if (hurt) fight.onBossLanded(this, source)
        return hurt
    }

    companion object {
        private val POSE: TrackedData<Int> = DataTracker.registerData(HermesEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val ILLUSION: TrackedData<Boolean> = DataTracker.registerData(HermesEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        private val STAGE: TrackedData<Int> = DataTracker.registerData(HermesEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val VULNERABLE: TrackedData<Boolean> = DataTracker.registerData(HermesEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        private val CORRUPTED: TrackedData<Boolean> = DataTracker.registerData(HermesEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        private val GUIDE: TrackedData<Boolean> = DataTracker.registerData(HermesEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)
        private val LOOT: TrackedData<Boolean> = DataTracker.registerData(HermesEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)

        fun attributes(): DefaultAttributeContainer.Builder = MobEntity.createMobAttributes()
            .add(EntityAttributes.MAX_HEALTH, 1000.0)
            .add(EntityAttributes.ARMOR, 0.0)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
            .add(EntityAttributes.FOLLOW_RANGE, 128.0)
    }
}

/**
 * Eine Caduceus-Schlange mit langem Körper; den Körper rechnet der Client aus der Flugbahn.
 *
 * Variante 0 (golden) und 1 (silberblau) gehören zu Hermes. Seit Plan 4.1 sind sie
 * **eine Attacke** („Die Zwillinge"), kein Gegner: keine Lebensleiste, keine Trefferzonen, nicht anvisierbar, kein
 * Gift. Die Kampflogik bewegt nur den Kopf; Treffer prüft die Ansage der Attacke.
 */
class SerpentEntity(type: EntityType<out SerpentEntity>, world: World) : PuppetEntity(type, world) {

    override fun getHurtSound(source: DamageSource): net.minecraft.sound.SoundEvent? = dev.henny.hha.HhaSounds.HIT

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(VARIANT, 0)
        builder.add(BITING, false)
        builder.add(GIRTH, 1f)
    }

    /** Dicke und Länge relativ zur Grundschlange. */
    var girth: Float
        get() = dataTracker.get(GIRTH)
        set(value) = dataTracker.set(GIRTH, value)

    var variant: Int
        get() = dataTracker.get(VARIANT)
        set(value) = dataTracker.set(VARIANT, value)

    var biting: Boolean
        get() = dataTracker.get(BITING)
        set(value) = dataTracker.set(BITING, value)

    /** Glieder-Positionen (aktuell und Vortick), nur auf dem Client für den Renderer. */
    val body = ArrayList<Vec3d>()
    val bodyPrev = ArrayList<Vec3d>()

    override fun tick() {
        super.tick()
        if (entityWorld !is ServerWorld) updateBody()
    }

    override fun canHit(): Boolean = false
    override fun isAttackable(): Boolean = false

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
        val spacing = SPACING * girth
        for (i in body.indices) {
            val offset = body[i].subtract(previous)
            val distance = offset.length()
            if (distance > spacing) {
                body[i] = previous.add(offset.multiply(spacing / distance))
            }
            previous = body[i]
        }
    }

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        // Eine Attacke nimmt keinen Schaden. Ohne laufenden Kampf (z. B. nach einem Absturz) räumt sie sich weg.
        if (HermesFights.of(world) == null) discard()
        return false
    }

    override fun getBaseDimensions(pose: net.minecraft.entity.EntityPose): net.minecraft.entity.EntityDimensions =
        super.getBaseDimensions(pose).scaled(girth)

    override fun onTrackedDataSet(data: TrackedData<*>) {
        super.onTrackedDataSet(data)
        if (data == GIRTH) calculateDimensions()
    }

    companion object {
        const val SEGMENTS = 38
        const val SPACING = 0.42

        private val GIRTH: TrackedData<Float> = DataTracker.registerData(SerpentEntity::class.java, TrackedDataHandlerRegistry.FLOAT)

        private val VARIANT: TrackedData<Int> = DataTracker.registerData(SerpentEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val BITING: TrackedData<Boolean> = DataTracker.registerData(SerpentEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)

        fun attributes(): DefaultAttributeContainer.Builder = MobEntity.createMobAttributes()
            .add(EntityAttributes.MAX_HEALTH, 120.0)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
    }
}

/**
 * Herma — Grenzstein mit Hermes-Büste für den Herma-Sprung (Plan 4.2): Hermes springt von Stein zu Stein, der Zielstein
 * glüht ([lit]). Wer ihn vorher zerschlägt, lässt ihn abstürzen. Keine Unverwundbarkeit, keine Strafe.
 */
class HermaEntity(type: EntityType<out HermaEntity>, world: World) : PuppetEntity(type, world) {

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(LIT, false)
    }

    /** Herma-Sprung: Dieser Stein ist das nächste Ziel von Hermes; das Caduceus-Relief am Schaft leuchtet. */
    var lit: Boolean
        get() = dataTracker.get(LIT)
        set(value) = dataTracker.set(LIT, value)

    /** Nur auf dem Client: wann [lit] zuletzt gewechselt hat (für Pulsieren und das Herumdrehen der Büste). */
    var litChangedAge = 0
        private set

    override fun onTrackedDataSet(data: TrackedData<*>) {
        super.onTrackedDataSet(data)
        if (data == LIT && entityWorld.isClient) litChangedAge = age
    }

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
        private val LIT: TrackedData<Boolean> = DataTracker.registerData(HermaEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)

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
