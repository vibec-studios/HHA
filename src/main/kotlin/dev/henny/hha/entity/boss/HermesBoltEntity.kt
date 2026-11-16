package dev.henny.hha.entity.boss

import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.Telegraph
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
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

/**
 * Geschosse der Bosse: Federpfeil, Seelenlanze, Giftspucke, Feuerball der Teufelchen, Felsbrocken (fällt im Bogen) und
 * Zeitsand des Kronos. Dazu die fliegenden Waffen ([BIDENT], [HARPE], [BOULDER]), die der Client als Knochen aus dem
 * Modell ihres Besitzers zeichnet (Plan 6.5).
 *
 * Für die Kämpfe (nur Server, nicht synchronisiert):
 * - [fight]: Einschläge laufen im Scope dieses Kampfes, damit die Effekt-Regeln aus Plan 3.3 auch für Geschosse gelten
 *   (Rütteln über [Telegraph.Fight.hitShake], höchstens ein Flächeneffekt je Treffer, Gefahrenfarbe).
 * - [style]: Farbe des Einschlags; ohne Angabe die übliche Farbe der Art.
 * - [visual]: reines Schaubild (z. B. die Lanze, deren Treffer die Ansage am Boden prüft); fliegt durch Spieler durch.
 * - [maxAge]: Lebensdauer in Ticks; der Federfächer endet so am Ende seines Kegels.
 */
class HermesBoltEntity(type: EntityType<out HermesBoltEntity>, world: World) : ProjectileEntity(type, world) {

    var damage = 6f

    /** Zielsuche; die Bosskämpfe benutzen sie nicht mehr (Plan 3.3), andere Nutzer dürfen. */
    var homing = 0.0

    /** Fallbeschleunigung je Tick; Felsbrocken und Sand fliegen im Bogen. */
    var fall = 0.0

    /** Wer im Umkreis des Einschlags steht, bekommt auch etwas ab. */
    var splash = 0.0
    var target: LivingEntity? = null

    /** Nur Server: Kampf, in dessen Scope Treffer und Einschlag laufen. */
    var fight: Telegraph.Fight? = null

    /** Nur Server: Farbe des Einschlags (`null` = übliche Farbe der Art). */
    var style: Vfx.Style? = null

    /** Nur Server: trifft keine Spieler, schlägt nur im Boden ein. */
    var visual = false

    /** Nur Server: so viele Ticks fliegt das Geschoss höchstens. */
    var maxAge = MAX_AGE
    private var lifetime = 0

    /**
     * Nur Server: so weit (Blöcke, waagerecht ab [rangeFrom] bzw. dem Startpunkt) darf das Geschoss höchstens etwas
     * treffen, z. B. die Reichweite der Kegel-Ansage ab der Kegelspitze. Ein Flugschritt, dessen Ende (plus halbe Größe)
     * darüber hinausreicht, wird nicht mehr geprüft: Das Geschoss verschwindet vorher. So trifft es keinen Spieler, dessen
     * Mitte außerhalb der Ansage (Reichweite + 0,3 Spielerbreite) steht.
     */
    var maxRange = Double.MAX_VALUE

    /** Nur Server: Bezugspunkt für [maxRange], z. B. die Spitze des angesagten Kegels. */
    var rangeFrom: net.minecraft.util.math.Vec3d? = null

    /** Nur Server: Wurde ein Werfer gesetzt? Löst er sich später nicht mehr auf (entfernt), verschwindet das Geschoss. */
    private var ownerAssigned = false

    override fun initDataTracker(builder: DataTracker.Builder) {
        builder.add(KIND, FEATHER)
        builder.add(SIZE, 1f)
    }

    /** Darstellungsgröße, vor allem für Felsbrocken. */
    var size: Float
        get() = dataTracker.get(SIZE)
        set(value) = dataTracker.set(SIZE, value)

    var kind: Int
        get() = dataTracker.get(KIND)
        set(value) = dataTracker.set(KIND, value)

    private fun <T> scoped(block: () -> T): T {
        val scope = fight
        return if (scope != null) scope.during(block) else block()
    }

    override fun tick() {
        super.tick()
        val world = entityWorld
        if (world is ServerWorld) {
            lifetime++
            if (lifetime > maxAge) {
                discard()
                return
            }
            // Ohne Werfer (Kampf vorbei, Wächter verschwunden, Hermes-Geist vertrieben) fliegt nichts weiter. Ein
            // entfernter Werfer löst sich über LazyEntityReference zu null auf, darum zählt „gesetzt, aber weg“.
            if (ownerAssigned && getOwner() == null) {
                discard()
                return
            }
            val start = rangeFrom ?: entityPos.also { rangeFrom = it }
            if (maxRange < Double.MAX_VALUE) {
                val next = entityPos.add(velocity)
                val flat = kotlin.math.hypot(next.x - start.x, next.z - start.z)
                if (flat + size * 0.5 > maxRange) {
                    discard()
                    return
                }
            }
            val aim = target
            if (homing > 0.0 && aim != null && aim.isAlive && lifetime < 50) {
                val speed = velocity.length()
                val desired = aim.entityPos.add(0.0, aim.height * 0.5, 0.0).subtract(entityPos).normalize().multiply(speed)
                velocity = velocity.lerp(desired, homing).normalize().multiply(speed)
                velocityDirty = true
            }
            if (fall != 0.0) {
                velocity = velocity.add(0.0, -fall, 0.0)
                velocityDirty = true
            }
            val hit = ProjectileUtil.getCollision(this, ::canHit)
            if (hit.type != HitResult.Type.MISS) {
                scoped { hitOrDeflect(hit) }
                if (isRemoved) return
            }
        }
        setPosition(entityPos.add(velocity))
        ProjectileUtil.setRotationFromVelocity(this, 1f)
    }

    override fun setOwner(entity: Entity?) {
        ownerAssigned = entity != null
        super.setOwner(entity)
    }

    override fun canHit(entity: Entity): Boolean = !visual && entity is PlayerEntity && super.canHit(entity)

    override fun onEntityHit(entityHitResult: EntityHitResult) {
        val world = entityWorld as? ServerWorld ?: return
        val owner = getOwner() as? LivingEntity
        val victim = entityHitResult.entity
        victim.damage(world, world.damageSources.mobProjectile(this, owner), damage)
        if (victim is LivingEntity) afflict(victim)
        impact(world)
    }

    override fun onBlockHit(blockHitResult: BlockHitResult) {
        val world = entityWorld as? ServerWorld ?: return
        impact(world)
    }

    /** Nebenwirkungen; Plan 9.2: nie Slowness über Stufe II, Gift nur kurz. */
    private fun afflict(victim: LivingEntity) {
        // Hat der Treffer den Spieler k.o. aus dem Kampf geworfen, bleibt es dabei.
        if (victim is net.minecraft.server.network.ServerPlayerEntity && !dev.henny.hha.logic.Fx.canAffect(victim)) return
        when (kind) {
            VENOM -> victim.addStatusEffect(StatusEffectInstance(StatusEffects.POISON, BossTimings.Kampe.SPIT_POISON, 0))
            FIRE -> victim.setOnFireFor(4f)
            SAND -> victim.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1))
        }
    }

    private fun impact(world: ServerWorld) {
        val color = style ?: when (kind) {
            LANCE -> Vfx.Style.SOUL
            VENOM, BIDENT -> Vfx.Style.HADES
            FIRE, ROCK, BOULDER -> Vfx.Style.FIRE
            HARPE, SAND -> Vfx.Style.AEON
            else -> Vfx.Style.HEAVEN
        }
        Vfx.flare(world, color, entityPos, 1.6f * size, 7)
        // Genau ein Flächeneffekt je Einschlag (Plan 3.3), Rütteln höchstens wie ein normaler Treffer.
        when (kind) {
            LANCE -> Vfx.shockwave(world, color, entityPos, 2.6f, 10, 0.8f)
            ROCK, BOULDER -> {
                Vfx.shockwave(world, color, entityPos, 2.4f * size + 1.5f, 12, 1.4f)
                val strength = (3f * size).coerceAtMost(Vfx.SHAKE_HIT_MAX)
                val scope = fight
                if (scope != null) scope.hitShake(entityPos, strength) else Vfx.shake(world, entityPos, strength, Telegraph.HIT_SHAKE_TICKS, 24f)
                world.playSound(null, x, y, z, net.minecraft.sound.SoundEvents.ENTITY_GENERIC_EXPLODE.value(), net.minecraft.sound.SoundCategory.HOSTILE, 1.2f, 0.7f)
            }
            VENOM -> Vfx.nova(world, color, entityPos, 1.8f, 8)
        }
        if (splash > 0.0) {
            val owner = getOwner() as? LivingEntity
            for (player in world.getPlayers { it.squaredDistanceTo(entityPos) < splash * splash && !it.isSpectator }) {
                player.damage(world, world.damageSources.mobProjectile(this, owner), damage * 0.6f)
                afflict(player)
            }
        }
        discard()
    }

    override fun readCustomData(view: ReadView) {}
    override fun writeCustomData(view: WriteView) {}
    override fun shouldSave(): Boolean = false

    companion object {
        const val FEATHER = 0
        const val LANCE = 1
        const val VENOM = 2
        const val FIRE = 3
        const val ROCK = 4
        const val SAND = 5

        /** Der Bident des Hades im Flug: Knochen `bident` aus dem Hades-Modell. */
        const val BIDENT = 6

        /** Die Harpe des Kronos im Flug: Knochen `harpe` aus dem Kronos-Modell, dreht wie ein Bumerang. */
        const val HARPE = 7

        /** Sisyphos' Fels im Flug: Knochen `boulder` aus dem Sisyphos-Modell, rollt. */
        const val BOULDER = 8

        /** Standard-Lebensdauer in Ticks. */
        const val MAX_AGE = 200
        private val SIZE: TrackedData<Float> = DataTracker.registerData(HermesBoltEntity::class.java, TrackedDataHandlerRegistry.FLOAT)
        private val KIND: TrackedData<Int> = DataTracker.registerData(HermesBoltEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
    }
}
