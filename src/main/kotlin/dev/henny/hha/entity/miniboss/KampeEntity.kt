package dev.henny.hha.entity.miniboss

import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.Telegraph
import net.minecraft.entity.EntityStatuses
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundEvents
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/** Animationszustände der Kampe; die Werte gehen über die Leitung, also nur hinten anfügen. Zeiten: `BossTimings.Kampe`. */
object KampePose {
    const val IDLE = 0

    /** Schwanzfeger flach über den Boden. */
    const val TAIL_SWEEP = 1

    /** Giftfächer. */
    const val SPIT = 2

    /** Skorpionstich; danach steckt der Stachel (offen). */
    const val STING = 3

    /** Windet sich am angeketteten Titanen hoch. */
    const val COIL_UP = 4

    /** Sturzstoß vom Titanen herab. */
    const val DIVE = 5

    /** Benommen am Boden (offen). */
    const val DAZED = 6

    const val ROAR = 7
    const val DEATH = 8
}

/**
 * Kampe, die Kerkermeisterin der Titanen: Frau, Schlangenleib und Skorpionschwanz (Plan 4.6, Brief `kampe.md`). Sie
 * kämpft am **Boden der Grube** (kein Kreisen mehr außer Reichweite):
 *
 * - **Schwanzfeger** gegen alle, die hinter oder neben ihr stehen: Kegel 120° halb nach hinten, Radius 8, darüber springen.
 * - **Giftfächer**: Kegel 30° halb, fünf Geschosse, Gift nur 2 s.
 * - **Skorpionstich**: Kreis am Ziel; danach **steckt der Stachel** (36 Ticks), Treffer zählen × 1,5.
 * - Bei 50 %: Sie windet sich am angeketteten Titanen hoch und stößt **dreimal** herab (Linien). Wen sie von oben
 *   nicht sieht, den trifft sie nicht: **Der Titan gibt Deckung.** Nach dem dritten Stoß liegt sie benommen (offen).
 *
 * Wie bei den Bossen setzt der Server eine Pose je Angriff zu Beginn des Ausholens ([pose]); der Client merkt sich die
 * vorige Pose und den Zeitpunkt des Wechsels für weiche Übergänge.
 */
class KampeEntity(type: EntityType<out KampeEntity>, world: World) : GuardianEntity(type, world) {

    override val kit = MiniBossKit(this, "entity.hha.kampe", BossBar.Color.GREEN, Vfx.Style.HADES)
    override val fightName = "kampe"
    override val danger = Vfx.Style.HADES

    /** Fußpunkt des angeketteten Titanen (Mitte des Grubenbodens); Standard: die Mitte ihres Platzes. */
    var titan: Vec3d? = null

    /** Nur auf dem Client: Pose vor dem letzten Wechsel und wann gewechselt wurde. */
    var previousPose = KampePose.IDLE
        private set
    var poseChangedAge = 0
        private set
    private var lastPoseSeen = KampePose.IDLE

    private var coiled = false

    init {
        kit.stats(HEALTH, 10.0, 14.0, 0.3)
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(POSE_ID, KampePose.IDLE)
        builder.add(RESTART_ID, 0)
    }

    var pose: Int
        get() = dataTracker.get(POSE_ID)
        set(value) = dataTracker.set(POSE_ID, value)

    /** Setzt die Pose und startet sie auch neu, wenn sie schon läuft (drei Sturzstöße hintereinander). */
    private fun play(value: Int) {
        if (pose == value) dataTracker.set(RESTART_ID, dataTracker.get(RESTART_ID) + 1) else pose = value
    }

    override fun onTrackedDataSet(data: TrackedData<*>) {
        super.onTrackedDataSet(data)
        if (!entityWorld.isClient) return
        if (data == POSE_ID) {
            previousPose = lastPoseSeen
            lastPoseSeen = pose
            poseChangedAge = age
        } else if (data == RESTART_ID) {
            previousPose = pose
            poseChangedAge = age
        }
    }

    override fun createMoves() = listOf(
        Move(KampePose.TAIL_SWEEP, 0.0..BossTimings.Kampe.SWEEP_RADIUS.toDouble(), 3, { flanker() != null }) { sweep() },
        Move(KampePose.SPIT, 5.0..BossTimings.Kampe.SPIT_RANGE.toDouble(), 2) { spit(it) },
        Move(KampePose.STING, 2.0..7.0, 3) { sting(it) },
    )

    override fun closeRange(): Double = 3.2

    override fun entrance(): Sequence<Unit> = sequence {
        play(KampePose.ROAR)
        telegraph.sound(HhaSounds.HISS, entityPos, 2.6f, 0.7f)
        wait(BossTimings.Kampe.ROAR)
        play(KampePose.IDLE)
    }

    override fun phaseEvent(): Sequence<Unit>? {
        if (!coiled && health < maxHealth * COIL_AT) {
            coiled = true
            return titanDives()
        }
        return null
    }

    override fun onReset() {
        scripted(false)
        play(KampePose.IDLE)
    }

    /** Wer in Reichweite des Schwanzes hinter oder neben ihr steht (außerhalb der vorderen ±60°). */
    private fun flanker(): ServerPlayerEntity? {
        val f = facing()
        val reach = BossTimings.Kampe.SWEEP_RADIUS
        return fighters().firstOrNull { p ->
            val to = flatNorm(p.entityPos.subtract(entityPos))
            flatDistance(p) <= reach && to.x * f.x + to.z * f.z < FRONT_COS
        }
    }

    /** Schwanzfeger: Kegel nach hinten und zur Seite (vorn ±60° frei), Radius 8; darüber springen. */
    private fun sweep(): Sequence<Unit> = sequence {
        val k = BossTimings.Kampe
        val dir = facing()
        hold(dir)
        play(KampePose.TAIL_SWEEP)
        val back = dir.multiply(-1.0)
        val cone = telegraph.cone(ground(entityPos), back, k.SWEEP_RADIUS, k.SWEEP_HALF_ANGLE, k.SWEEP_WINDUP, Telegraph.Pace.HEAVY)
        telegraph.sound(HhaSounds.HISS, entityPos, 2.2f, 0.7f)
        wait(cone.ticks)
        val struck = cone.hits(fighters()).filter { !airborne(it) }
        for (player in struck) {
            strike(player, 14f)
            knock(player, dirTo(player.entityPos), 1.0, 0.5)
        }
        Vfx.crescent(sw, danger, entityPos.add(0.0, 0.5, 0.0), back, k.SWEEP_RADIUS, 6f, 10)
        telegraph.sound(HhaSounds.WHOOSH, entityPos, 2f, 0.6f)
        if (struck.isNotEmpty()) telegraph.hitShake(entityPos, 3f)
        wait(k.SWEEP_END - k.SWEEP_WINDUP)
        hold(null)
        play(KampePose.IDLE)
    }

    /** Giftfächer: Kegel 30° halb, fünf Geschosse, ohne Zielsuche; das Gift wirkt nur 2 s (`SPIT_POISON`, im Geschoss). */
    private fun spit(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val k = BossTimings.Kampe
        val dir = dirTo(target.entityPos)
        // Die Richtung wird beim Ansagen festgelegt: Wer aus dem Kegel läuft, ist sicher (kein Nachzielen).
        val aimPoint = target.eyePos.subtract(0.0, 0.3, 0.0)
        hold(dir)
        play(KampePose.SPIT)
        val cone = telegraph.cone(ground(entityPos), dir, k.SPIT_RANGE, k.SPIT_HALF_ANGLE, k.SPIT_WINDUP, Telegraph.Pace.PROJECTILE)
        telegraph.sound(HhaSounds.HISS, entityPos, 2.4f, 1.1f)
        wait(cone.ticks)
        val mouth = entityPos.add(0.0, height * 0.82, 0.0).add(dir.multiply(1.0))
        val flat = kotlin.math.sqrt(dir.x * dir.x + dir.z * dir.z).coerceAtLeast(1e-6)
        val horizontal = aimPoint.subtract(mouth).let { kotlin.math.sqrt(it.x * it.x + it.z * it.z) }.coerceAtLeast(1.0)
        val drop = ((aimPoint.y - mouth.y) / horizontal).coerceIn(-0.6, 0.6)
        val aim = Vec3d(dir.x / flat, drop, dir.z / flat).normalize()
        // Die Geschosse treffen nur, so weit der Kegel angesagt war: Die Mündung liegt 1 Block vor der Kegelspitze.
        val range = k.SPIT_RANGE - 1.0
        val life = kotlin.math.ceil(range / SPIT_SPEED).toInt() + 1
        val spread = k.SPIT_HALF_ANGLE * 0.8 / ((k.SPIT_SHOTS - 1) / 2.0)
        for (i in 0 until k.SPIT_SHOTS) {
            val turn = Math.toRadians((i - (k.SPIT_SHOTS - 1) / 2.0) * spread)
            val v = Vec3d(aim.x * kotlin.math.cos(turn) - aim.z * kotlin.math.sin(turn), aim.y, aim.x * kotlin.math.sin(turn) + aim.z * kotlin.math.cos(turn))
            val bolt = HhaEntities.HERMES_BOLT.create(sw, SpawnReason.EVENT) ?: continue
            bolt.kind = HermesBoltEntity.VENOM
            bolt.size = 0.9f
            bolt.damage = 6f * damageScale
            bolt.fight = telegraph
            bolt.setOwner(self)
            bolt.setPosition(mouth)
            bolt.velocity = v.normalize().multiply(SPIT_SPEED)
            bolt.maxAge = life
            bolt.maxRange = range
            sw.spawnEntity(bolt)
        }
        telegraph.sound(SoundEvents.ENTITY_LLAMA_SPIT, mouth, 2f, 0.5f)
        wait(k.SPIT_END - k.SPIT_WINDUP)
        hold(null)
        play(KampePose.IDLE)
    }

    /** Skorpionstich: Kreis am Ziel; der Stachel steckt danach (F 36, × 1,5). */
    private fun sting(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val k = BossTimings.Kampe
        val dir = dirTo(target.entityPos)
        hold(dir)
        play(KampePose.STING)
        val reach = flatDistance(target).coerceIn(2.5, k.STING_REACH.toDouble())
        val spot = ground(entityPos.add(dir.multiply(reach)))
        val ring = telegraph.ring(spot, k.STING_RADIUS, k.STING_WINDUP, pace = Telegraph.Pace.HEAVY)
        telegraph.sound(HhaSounds.STING, entityPos, 2.2f, 0.8f)
        wait(ring.ticks)
        val struck = ring.hits(fighters())
        for (player in struck) {
            strike(player, 18f)
            knock(player, dir, 0.6, 0.45)
        }
        Vfx.shockwave(sw, danger, spot, k.STING_RADIUS + 0.5f, 12, 1.4f)
        telegraph.sound(HhaSounds.IMPACT, spot, 2f, 0.9f)
        if (struck.isNotEmpty()) telegraph.hitShake(spot, 3f)
        // Der Stachel steckt im Boden: Sie zerrt, Treffer zählen mehr (der Stachel glüht golden, BossGlow).
        hint("hha.guardian.hint.sting")
        open(k.STING_STUCK_END - k.STING_WINDUP, OPEN_FACTOR, at = spot)
        wait(k.STING_STUCK_END - k.STING_WINDUP)
        close()
        wait(k.STING_END - k.STING_STUCK_END)
        hold(null)
        play(KampePose.IDLE)
    }

    // ---------------------------------------------------------------------------------------------
    // Bei 50 %: am Titanen hoch, drei Sturzstöße
    // ---------------------------------------------------------------------------------------------

    /** Geführte Bewegung am Titanen: durch seine Blöcke hindurch, ohne Schwerkraft. */
    private fun scripted(on: Boolean) {
        noClip = on
        setNoGravity(on)
        if (on) stopMoving()
    }

    private fun place(at: Vec3d) {
        setPosition(at.x, at.y, at.z)
        velocity = Vec3d.ZERO
    }

    private fun titanDives(): Sequence<Unit> = sequence {
        val k = BossTimings.Kampe
        val base = titan ?: anchor()
        hold(null)
        play(KampePose.ROAR)
        hint("hha.guardian.hint.titan")
        telegraph.sound(HhaSounds.ROAR, entityPos, 3f, 0.8f)
        telegraph.roomShake(entityPos, 5f)
        wait(k.ROAR)
        var seat = random.nextInt(PERCHES.size)
        for (n in 0 until k.DIVE_COUNT) {
            // Hinauf an den Titanen.
            var perch = perchAt(base, seat)
            play(KampePose.COIL_UP)
            scripted(true)
            val from = entityPos
            for (i in 1..k.COIL) {
                val t = i.toDouble() / k.COIL
                val e = t * t * (3.0 - 2.0 * t)
                place(Vec3d(from.x + (perch.x - from.x) * e, from.y + (perch.y - from.y) * t, from.z + (perch.z - from.z) * e))
                hold(flatNorm(perch.subtract(from)))
                yield(Unit)
            }
            // Wen sieht sie von oben? Wer hinter dem Titanen steht, ist gedeckt.
            var target = visibleFrom(perch)
            var tries = 0
            while (target == null && tries < 2 && isAlive) {
                tries++
                telegraph.sound(HhaSounds.HISS, perch, 2.4f, 0.6f)
                wait(k.DIVE_SEARCH)
                seat = (seat + 1) % PERCHES.size
                val next = perchAt(base, seat)
                for (i in 1..12) {
                    place(perch.lerp(next, i / 12.0))
                    yield(Unit)
                }
                perch = next
                target = visibleFrom(perch)
            }
            val foot = Vec3d(perch.x, base.y, perch.z)
            val aim = target?.entityPos ?: foot.add(flatNorm(foot.subtract(base)).multiply(14.0))
            val dir = flatNorm(aim.subtract(foot))
            val g0 = landing(base, foot, dir, max(6.0, flat(foot, aim) - 6.0))
            val g1 = clampPit(base, g0.add(dir.multiply(k.DIVE_LENGTH.toDouble())))
            play(KampePose.DIVE)
            hold(dir)
            val line = telegraph.line(ground(g0), ground(g1), k.DIVE_WIDTH, k.DIVE_WINDUP, Telegraph.Pace.HEAVY)
            telegraph.sound(HhaSounds.CHARGE, perch, 2.4f, 0.6f)
            wait(line.ticks)
            // Sturz: vom Titanen an den Anfang der Linie, dann die Linie entlang.
            for (i in 1..k.DIVE_DROP) {
                place(perch.lerp(g0, i.toDouble() / k.DIVE_DROP))
                yield(Unit)
            }
            Vfx.shockwave(sw, danger, ground(g0), 3f, 12, 1.6f)
            telegraph.sound(HhaSounds.IMPACT, g0, 2.6f, 0.7f)
            val struck = HashSet<UUID>()
            var anyHit = false
            for (i in 0..k.DIVE_SLIDE) {
                val p = g0.lerp(g1, i.toDouble() / k.DIVE_SLIDE)
                place(p)
                val travelled = flat(g0, p)
                for (player in fighters()) {
                    if (player.uuid in struck || abs(player.y - p.y) > 3.0) continue
                    if (abs(along(player.entityPos, g0, dir) - travelled) > 2.4) continue
                    if (!line.contains(player)) continue
                    struck.add(player.uuid)
                    anyHit = true
                    strike(player, 16f)
                    knock(player, dir, 1.0, 0.5)
                }
                yield(Unit)
            }
            if (anyHit) telegraph.hitShake(g1, 4f)
        }
        // Nach dem dritten Stoß liegt sie benommen am Boden (F 60, offen).
        scripted(false)
        play(KampePose.DAZED)
        open(k.DAZED, OPEN_FACTOR)
        wait(k.DAZED)
        close()
        play(KampePose.IDLE)
    }

    private fun perchAt(base: Vec3d, index: Int): Vec3d {
        val p = PERCHES[index % PERCHES.size]
        return base.add(p.x, p.y, p.z)
    }

    /** Der nächste Spieler, den sie von [perch] aus sieht (kein Block dazwischen). */
    private fun visibleFrom(perch: Vec3d): ServerPlayerEntity? {
        val eye = perch.add(0.0, 2.0, 0.0)
        return fighters().filter { p ->
            sw.raycast(RaycastContext(eye, p.eyePos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, self)).type == HitResult.Type.MISS
        }.minByOrNull { it.squaredDistanceTo(perch) }
    }

    /** Landepunkt am Boden, außerhalb des Titanen: von [foot] aus [distance] in [dir], weiter hinaus, bis Platz ist. */
    private fun landing(base: Vec3d, foot: Vec3d, dir: Vec3d, distance: Double): Vec3d {
        var d = distance
        var p = clampPit(base, foot.add(dir.multiply(d)))
        val box = boundingBox.offset(-x, -y, -z).contract(0.2)
        repeat(12) {
            if (entityWorld.isSpaceEmpty(self, box.offset(p.x, base.y + 0.1, p.z))) return p
            d += 1.0
            p = clampPit(base, foot.add(dir.multiply(d)))
        }
        return p
    }

    /** Hält [p] auf dem Grubenboden (Radius [PIT]). */
    private fun clampPit(base: Vec3d, p: Vec3d): Vec3d {
        val d = flat(p, base)
        val q = if (d <= PIT) p else Vec3d(base.x + (p.x - base.x) * PIT / d, p.y, base.z + (p.z - base.z) * PIT / d)
        return Vec3d(q.x, base.y, q.z)
    }

    // ---------------------------------------------------------------------------------------------

    override fun onDeath(damageSource: DamageSource) {
        if (!entityWorld.isClient) {
            scripted(false)
            pose = KampePose.DEATH
        }
        super.onDeath(damageSource)
    }

    /** Die Todesbewegung braucht 40 Ticks, nicht 20. */
    override fun updatePostDeath() {
        deathTime++
        if (deathTime >= BossTimings.Kampe.DEATH && !entityWorld.isClient && !isRemoved) {
            entityWorld.sendEntityStatus(this, EntityStatuses.ADD_DEATH_PARTICLES)
            remove(RemovalReason.KILLED)
        }
    }

    companion object {
        const val HEALTH = 420.0

        /** Fluggeschwindigkeit der Giftgeschosse (Blöcke je Tick). */
        private const val SPIT_SPEED = 0.85
        private const val COIL_AT = 0.5f

        /** cos 60°: Vor ihr (±60°) trifft der Schwanzfeger nicht. */
        private const val FRONT_COS = 0.5

        /** Grubenboden: so weit von der Mitte reichen die Sturzlinien (Grubenradius 24 minus Rand). */
        private const val PIT = 22.5

        /**
         * Sitze am Titanen (vom Fußpunkt der Grubenmitte): linke und rechte Schulter, der gebeugte Rücken. Abgelesen aus
         * `TartarusPlans.titan` (Schultern bei ±9 / +21, Rücken bis +25).
         */
        private val PERCHES = listOf(Vec3d(-9.0, 25.0, -1.0), Vec3d(9.0, 25.0, -1.0), Vec3d(0.0, 26.0, 3.5))

        private val POSE_ID: TrackedData<Int> = DataTracker.registerData(KampeEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val RESTART_ID: TrackedData<Int> = DataTracker.registerData(KampeEntity::class.java, TrackedDataHandlerRegistry.INTEGER)

        fun attributes(): DefaultAttributeContainer.Builder = guardianAttributes()
    }
}
