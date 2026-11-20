package dev.henny.hha.entity.boss

import dev.henny.hha.HhaSounds
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.SubTarget
import dev.henny.hha.logic.boss.SubTargetGroup
import net.minecraft.entity.EntityDimensions
import net.minecraft.entity.EntityPose
import net.minecraft.entity.EntityType
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.mob.MobEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.world.World

/**
 * Schlagbares Teilziel ohne KI (`hha:sub_target`, Überarbeitung 4, PLAN 7.6; Besitzer P04): Fessel, Kettenschloss,
 * Fels, Sanduhr.
 *
 * - Puppe: unbeweglich, ohne Schwerkraft, kein Rückstoß, keine Statuseffekte, kein Loot, wird nicht gespeichert
 *   (alles aus [PuppetEntity]); dazu KI aus.
 * - [kind] (getrackter Text) wählt das Aussehen am Client (`SubTargetRenderer`), [size] (getrackte Zahl) skaliert
 *   Trefferbox und Modell, [state] (getrackt, 0 ruht, 1 offen, 2 gebrochen) färbt es.
 * - Schaden geht **nur** an die [SubTargetGroup], und nur von Spielern (auch ihre Geschosse). Nicht offen: Abprall
 *   (Ton `boss.deflect`, höchstens einmal je [DEFLECT_GAP] Ticks). Das Wesen selbst verliert nie Leben.
 * - **Sicherung:** ohne Gruppe, mit geschlossener Gruppe oder nach [ORPHAN_TICKS] Ticks ohne `tick()` der Gruppe →
 *   `discard()`. Nach einem Neustart gibt es keine Gruppe (nicht gespeichert), also bleibt nie eines stehen.
 */
class SubTargetEntity(type: EntityType<out SubTargetEntity>, world: World) : PuppetEntity(type, world) {

    var group: SubTargetGroup? = null
    var target: SubTarget? = null

    /** Welt-Tick, in dem die Gruppe zuletzt getickt hat. */
    var lastGroupTick: Long = 0

    private var lastDeflect = Long.MIN_VALUE / 2

    init {
        isAiDisabled = true
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(KIND, "")
        builder.add(SIZE, 1f)
        builder.add(STATE, SubTarget.STATE_IDLE)
    }

    var kind: String
        get() = dataTracker.get(KIND)
        set(value) = dataTracker.set(KIND, value)

    /** Größe (1 = Trefferbox 1,2 × 1,6 aus `HhaEntities.SUB_TARGET`). */
    var size: Float
        get() = dataTracker.get(SIZE)
        set(value) = dataTracker.set(SIZE, value.coerceIn(MIN_SIZE, MAX_SIZE))

    /** 0 ruht (gedämpft), 1 offen (Gold), 2 gebrochen. */
    val state: Int get() = dataTracker.get(STATE)

    /** Zustand aus dem Teilziel übernehmen (die Gruppe ruft es jeden Tick; ändert nur, wenn nötig). */
    fun syncState() {
        val next = target?.state ?: SubTarget.STATE_IDLE
        if (dataTracker.get(STATE) != next) dataTracker.set(STATE, next)
    }

    override fun getBaseDimensions(pose: EntityPose): EntityDimensions = super.getBaseDimensions(pose).scaled(size)

    override fun onTrackedDataSet(data: TrackedData<*>) {
        super.onTrackedDataSet(data)
        if (data == SIZE) calculateDimensions()
    }

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        val g = group
        val t = target
        if (g == null || t == null || g.closed) {
            discard()
            return false
        }
        if (source.attacker !is PlayerEntity) return false
        if (!t.open) {
            deflect(world)
            return false
        }
        return g.hitByEntity(t, source, amount)
    }

    private fun deflect(world: ServerWorld) {
        val now = world.time
        if (now - lastDeflect < DEFLECT_GAP || Cine.muted) return
        lastDeflect = now
        world.playSound(null, x, y + height * 0.5, z, HhaSounds.DEFLECT, SoundCategory.HOSTILE, 0.9f, 1.3f)
    }

    /** Stirbt nie über das Leben des Wesens: Das Teilziel hält sein Leben selbst. */
    override fun onDeath(damageSource: DamageSource) {}

    /** Kein Verschwinden über Vanilla (auch nicht auf „Friedlich“): Die Gruppe und die Sicherung unten räumen auf. */
    override fun checkDespawn() {}

    override fun tick() {
        super.tick()
        val world = entityWorld
        if (world is ServerWorld && age > SPAWN_GRACE) {
            val g = group
            if (g == null || g.closed || world.time - lastGroupTick > ORPHAN_TICKS) discard()
        }
    }

    companion object {
        /** Sicherung: so lange ohne `tick()` der Gruppe, dann verschwindet das Wesen. */
        const val ORPHAN_TICKS = 40

        /** Abprall-Ton höchstens einmal in so vielen Ticks. */
        const val DEFLECT_GAP = 10L

        private const val SPAWN_GRACE = 5
        const val MIN_SIZE = 0.25f
        const val MAX_SIZE = 6f

        private val KIND: TrackedData<String> = DataTracker.registerData(SubTargetEntity::class.java, TrackedDataHandlerRegistry.STRING)
        private val SIZE: TrackedData<Float> = DataTracker.registerData(SubTargetEntity::class.java, TrackedDataHandlerRegistry.FLOAT)
        private val STATE: TrackedData<Int> = DataTracker.registerData(SubTargetEntity::class.java, TrackedDataHandlerRegistry.INTEGER)

        fun attributes(): DefaultAttributeContainer.Builder = MobEntity.createMobAttributes()
            .add(EntityAttributes.MAX_HEALTH, 1000.0)
            .add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
    }
}
