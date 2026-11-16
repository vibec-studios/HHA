package dev.henny.hha.entity.miniboss

import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaSounds
import dev.henny.hha.config.HhaConfig
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.Telegraph
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.mob.HostileEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import net.minecraft.world.World
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Basis der Wächter des Tartaros (Plan 4.6): eine [HostileEntity] mit Navigation, aber **ohne** Vanilla-Ziele und
 * -Angriffe. Was er tut, steht in seinem **Drehbuch** wie bei den Bossen: herangehen → einen Angriff nach Entfernung
 * wählen (nie zweimal denselben hintereinander) → Erholung. Es gibt keine festen Uhren; jeder Angriff ist eine
 * Folge aus Ansage (über [Telegraph]), Treffer und Fenster.
 *
 * Gemeinsam für alle:
 * - **Leine** ([BossTimings.Guardian.LEASH]): Weiter weg von der Mitte seines Platzes ([home]) bricht er ab, geht zurück
 *   zu seinem Standplatz ([post]) und heilt sich dabei ganz.
 * - **Spielerzahl:** Leben und Schaden wachsen mit `hermes_health_per_player`/`hermes_damage_per_player` (wie bei den
 *   Bossen teilt [healthScale] den eingehenden Schaden, damit die Lebensleiste stimmt).
 * - **Offen** ([open]): goldene Rune, Treffer zählen mehr. Die Wächter setzen damit ihre Mythos-Mechanik um.
 * - Keine Adds, keine Statuseffekte auf ihm, kein Rückstoß, kein Fallschaden.
 *
 * Unterklassen liefern Name und Farbe fürs [telegraph], die Angriffe ([createMoves]) und Lebensschwellen
 * ([phaseEvent]). Nebenläufiges (rollende Felsen, Feuerspuren) läuft über [launch].
 */
abstract class GuardianEntity(type: EntityType<out GuardianEntity>, world: World) : HostileEntity(type, world), MiniBoss {

    /** Kurzer Name fürs Ansage-Log (`[Telegraph:warden]`). */
    protected abstract val fightName: String

    /** Gefahrenfarbe (Plan 3.1). */
    protected abstract val danger: Vfx.Style

    /** Mitte seines Platzes: Leine und Kampfradius zählen von hier. Ohne Angabe die Stelle, an der er zuerst steht. */
    var home: Vec3d? = null

    /** Wo er steht, wenn nichts los ist, und wohin er nach der Leine zurückgeht (Standard: [home]). */
    var post: Vec3d? = null

    private var fightState: Telegraph.Fight? = null

    /** Ansagen dieses Wächters (nur Server). */
    protected val telegraph: Telegraph.Fight
        get() = fightState ?: Telegraph.open(entityWorld as ServerWorld, fightName, danger) { fighters() }.also { fightState = it }

    /** Für Aufrufe, die eine Entity brauchen (innerhalb von `sequence { }` ist `this` der Sequenz-Rahmen). */
    protected val self: GuardianEntity get() = this

    /** Die Serverwelt; nur aus dem Drehbuch und dessen Aufgaben aufrufen. */
    protected val sw: ServerWorld get() = entityWorld as ServerWorld

    /** Ein Angriff: Kennung (für „nie zweimal derselbe“), Reichweite zum Ziel, Gewicht, Bedingung, Ablauf. */
    protected class Move(
        val id: Int,
        val reach: ClosedFloatingPointRange<Double>,
        val weight: Int = 1,
        val ready: () -> Boolean = { true },
        val run: (ServerPlayerEntity) -> Sequence<Unit>,
    )

    /** Die Angriffe des Wächters; einmal je Wächter abgefragt. */
    protected abstract fun createMoves(): List<Move>

    private val moves: List<Move> by lazy { createMoves() }

    private var script: Iterator<Unit>? = null
    private val tasks = ArrayList<Iterator<Unit>>()
    private val props = ArrayList<ScriptedBolt>()
    private var started = false
    private var returning = false
    private var returnTicks = 0
    private var lonely = 0
    private var held: Vec3d? = null
    private var openUntil = 0L
    private var openFactor = 1f
    private var scaledFor = 0
    private val hinted = HashSet<String>()

    /** Der zuletzt gewählte Angriff ([Move.id]); der nächste ist ein anderer. */
    protected var lastMove = -1

    /** Gegen wen er gerade kämpft. */
    protected var prey: ServerPlayerEntity? = null
        private set

    /** Teilt eingehenden Schaden (mehr Spieler = mehr Leben). */
    protected var healthScale = 1f
        private set

    /** Vervielfacht seinen Schaden (mehr Spieler = mehr Schlagkraft). */
    protected var damageScale = 1f
        private set

    init {
        setPersistent()
    }

    // ---------------------------------------------------------------------------------------------
    // Ablauf
    // ---------------------------------------------------------------------------------------------

    /** Keine Vanilla-Ziele, keine Vanilla-Angriffe: Alles steht im Drehbuch. */
    override fun initGoals() {}

    override fun tick() {
        super.tick()
        kit.animTick()
        val world = entityWorld as? ServerWorld ?: return
        if (!isAlive) return
        kit.tick()
        telegraph.during { think() }
    }

    private fun think() {
        val anchor = anchor()
        if (age % 20 == 0) updateScaling()
        lonely = if (fighters().isEmpty()) lonely + 1 else 0
        if (lonely > BossTimings.Guardian.LONELY) {
            discard()
            return
        }
        tickBody()
        runTasks()
        if (!returning && flat(entityPos, anchor) > BossTimings.Guardian.LEASH) beginReturn()
        if (returning) {
            returnHome()
            return
        }
        val mind = script ?: drehbuch().iterator().also { script = it }
        if (mind.hasNext()) mind.next() else script = null
        held?.let(::applyFacing)
    }

    /** Jeder Tick vor dem Drehbuch (z. B. Schweben, Nachsorge von Geschossen). */
    protected open fun tickBody() {}

    /** Auftritt vor dem ersten Angriff (der Titel kommt aus [MiniBossKit.announce]). */
    protected open fun entrance(): Sequence<Unit> = sequence {
        repeat(BossTimings.Guardian.ENTRANCE) {
            fighters().minByOrNull { it.squaredDistanceTo(self) }?.let { lookControl.lookAt(it, 30f, 30f) }
            yield(Unit)
        }
    }

    /**
     * Lebensschwellen und andere Ereignisse, die vor dem nächsten Angriff dran sind (Wut, Durst, Fluch, Taumeln).
     * `null` = nichts; sonst läuft die gelieferte Folge statt eines Angriffs.
     */
    protected open fun phaseEvent(): Sequence<Unit>? = null

    /** Ab hier ist er „zu nah“, um weiter heranzugehen. */
    protected open fun closeRange(): Double = 1.6 + width

    /** Tempo beim Herangehen (Faktor auf `MOVEMENT_SPEED`). */
    protected open fun chaseSpeed(): Double = 1.0

    /** Leine oder Neustart: alles Laufende vergessen (Schweben, Fels, Schlangenleib …). */
    protected open fun onReset() {}

    /** Das Drehbuch: Auftritt, dann immer wieder Ereignis oder Herangehen → Angriff → Erholung. */
    private fun drehbuch(): Sequence<Unit> = sequence {
        if (!started) {
            started = true
            yieldAll(entrance())
        }
        while (true) {
            val event = phaseEvent()
            if (event != null) {
                stopMoving()
                yieldAll(event)
                held = null
                continue
            }
            val target = choosePrey()
            if (target == null) {
                idleStep()
                yield(Unit)
                continue
            }
            prey = target
            yieldAll(engage(target))
            held = null
            yieldAll(rest(BossTimings.Guardian.PAUSE + random.nextInt(8)))
        }
    }

    /** Herangehen, bis ein Angriff in Reichweite ist (nie derselbe wie zuletzt), dann ihn ausführen. */
    private fun engage(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val least = BossTimings.Guardian.APPROACH_MIN + random.nextInt(BossTimings.Guardian.APPROACH_MIN + 1)
        var waited = 0
        var chosen: Move? = null
        while (waited < BossTimings.Guardian.APPROACH_MAX) {
            if (!stillFighting(target)) {
                stopMoving()
                return@sequence
            }
            val d = flatDistance(target)
            var options = moves.filter { it.id != lastMove && d in it.reach && it.ready() }
            // Passt nur der letzte Angriff, nimmt er nach einer Weile einen, der beinahe passt.
            if (options.isEmpty() && waited >= least + 16) {
                options = moves.filter { it.id != lastMove && it.ready() && d in (it.reach.start - 3.0)..(it.reach.endInclusive + 3.0) }
            }
            if (options.isNotEmpty() && (waited >= least || d <= closeRange())) {
                chosen = pick(options)
                break
            }
            approach(target, d)
            yield(Unit)
            waited++
        }
        stopMoving()
        val d = flatDistance(target)
        val move = chosen ?: moves.filter { it.id != lastMove && it.ready() }.minByOrNull { gap(it.reach, d) } ?: return@sequence
        lastMove = move.id
        yieldAll(move.run(target))
    }

    private fun gap(reach: ClosedFloatingPointRange<Double>, d: Double): Double = when {
        d < reach.start -> reach.start - d
        d > reach.endInclusive -> d - reach.endInclusive
        else -> 0.0
    }

    private fun pick(options: List<Move>): Move {
        var roll = random.nextInt(options.sumOf { it.weight }.coerceAtLeast(1))
        for (move in options) {
            roll -= move.weight
            if (roll < 0) return move
        }
        return options.last()
    }

    /** Ein Schritt aufs Ziel zu (Pfadsuche), nie über die Leine hinaus. */
    protected open fun approach(target: ServerPlayerEntity, distance: Double) {
        lookControl.lookAt(target, 30f, 30f)
        if (distance <= closeRange()) {
            navigation.stop()
            return
        }
        if (age % 8 == 0 || navigation.isIdle) {
            val goal = clampToLeash(target.entityPos)
            navigation.startMovingTo(goal.x, goal.y, goal.z, chaseSpeed())
        }
    }

    /** Geht auf [goal] zu (für Wege, die kein Ziel-Spieler sind: Standplatz, Rückweg, Mitte). */
    protected open fun walkTo(goal: Vec3d, speed: Double = 1.0) {
        if (age % 8 == 0 || navigation.isIdle) navigation.startMovingTo(goal.x, goal.y, goal.z, speed)
    }

    private fun clampToLeash(goal: Vec3d): Vec3d {
        val anchor = anchor()
        val limit = BossTimings.Guardian.LEASH - 3.0
        val d = flat(goal, anchor)
        if (d <= limit) return goal
        val k = limit / d
        return Vec3d(anchor.x + (goal.x - anchor.x) * k, goal.y, anchor.z + (goal.z - anchor.z) * k)
    }

    /** Steht und schaut auf sein Ziel; die Erholung zwischen zwei Angriffen. */
    protected fun rest(ticks: Int): Sequence<Unit> = sequence {
        repeat(ticks) {
            prey?.let { lookControl.lookAt(it, 20f, 20f) }
            yield(Unit)
        }
    }

    private fun idleStep() {
        val spot = post ?: anchor()
        if (flat(entityPos, spot) > 3.0) walkTo(spot, 0.8) else stopMoving()
        if (age % 40 == 0 && health < maxHealth) heal(maxHealth * 0.01f)
    }

    private fun choosePrey(): ServerPlayerEntity? {
        val anchor = anchor()
        val crowd = fighters().filter { flat(it.entityPos, anchor) < BossTimings.Guardian.LEASH + 10.0 }
        if (crowd.isEmpty()) return null
        val nearest = crowd.minBy { it.squaredDistanceTo(this) }
        val current = prey?.takeIf { it in crowd } ?: return nearest
        // Beim alten Ziel bleiben, solange es nicht deutlich weiter weg ist als das nächste.
        return if (current.squaredDistanceTo(this) <= nearest.squaredDistanceTo(this) + 36.0) current else nearest
    }

    private fun stillFighting(target: ServerPlayerEntity): Boolean =
        target.isAlive && !target.isRemoved && !target.isSpectator && !target.isCreative &&
            target.entityWorld === entityWorld && target.squaredDistanceTo(anchor()) < BossTimings.Guardian.FIGHT_RADIUS * BossTimings.Guardian.FIGHT_RADIUS

    /** Wer mitkämpft: lebende Spieler (nicht Kreativ/Zuschauer) im Kampfradius um [home]. */
    fun fighters(): List<ServerPlayerEntity> {
        val world = entityWorld as? ServerWorld ?: return emptyList()
        val anchor = anchor()
        val r = BossTimings.Guardian.FIGHT_RADIUS
        return world.players.filter { it.isAlive && !it.isSpectator && !it.isCreative && it.squaredDistanceTo(anchor) < r * r }
    }

    /** Mitte des Platzes (setzt [home] beim ersten Aufruf, falls niemand sie gesetzt hat). */
    protected fun anchor(): Vec3d = home ?: entityPos.also { home = it }

    // ---------------------------------------------------------------------------------------------
    // Leine
    // ---------------------------------------------------------------------------------------------

    private fun beginReturn() {
        returning = true
        returnTicks = 0
        script = null
        prey = null
        held = null
        tasks.clear()
        clearProps()
        close()
        telegraph.reset()
        kit.act(0)
        onReset()
        stopMoving()
    }

    private fun returnHome() {
        val spot = post ?: anchor()
        returnTicks++
        walkTo(spot, 1.25)
        if (returnTicks % 10 == 0) heal(maxHealth * 0.03f)
        val arrived = flat(entityPos, spot) < 2.5
        if (arrived || returnTicks > 400) {
            if (!arrived) refreshPositionAndAngles(spot.x, spot.y, spot.z, yaw, 0f)
            returning = false
            health = maxHealth
            stopMoving()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Nebenläufiges
    // ---------------------------------------------------------------------------------------------

    /** Startet eine Aufgabe neben dem Drehbuch (ab dem nächsten Tick, im Ansage-Scope). */
    protected fun launch(task: Sequence<Unit>) {
        tasks.add(task.iterator())
    }

    private fun runTasks() {
        if (tasks.isEmpty()) return
        for (task in tasks.toList()) {
            if (task.hasNext()) task.next() else tasks.remove(task)
        }
    }

    /**
     * Bricht das Drehbuch ab und spielt [event] (z. B. Taumeln, weil ihn der eigene Fels traf); danach geht es normal
     * weiter. Laufende Aufgaben ([launch]) laufen weiter.
     */
    protected fun interrupt(event: Sequence<Unit>) {
        stopMoving()
        held = null
        script = sequence {
            yieldAll(event)
            held = null
            yieldAll(drehbuch())
        }.iterator()
    }

    /** Merkt sich ein geführtes Geschoss, damit es mit ihm verschwindet. */
    protected fun prop(bolt: ScriptedBolt?): ScriptedBolt? {
        if (bolt != null) props.add(bolt)
        props.removeIf { !it.alive }
        return bolt
    }

    private fun clearProps() {
        for (bolt in props) bolt.discard()
        props.clear()
    }

    // ---------------------------------------------------------------------------------------------
    // Treffer
    // ---------------------------------------------------------------------------------------------

    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        val factor = max(if (world.time < openUntil) openFactor else 1f, hitFactor(source))
        val before = health
        val hurt = super.damage(world, source, amount * factor / healthScale)
        if (hurt && before > health) {
            if (factor > 1f && source.attacker is PlayerEntity) Vfx.flare(world, Vfx.Style.GOLD, entityPos.add(0.0, height * 0.6, 0.0), 1.6f, 5)
            onDamaged(source, before - health, factor)
        }
        return hurt
    }

    /** Zusätzlicher Faktor für diesen Treffer (z. B. von hinten auf die Laterne). */
    protected open fun hitFactor(source: DamageSource): Float = 1f

    /** Nach einem Treffer: [lost] Lebenspunkte weniger. */
    protected open fun onDamaged(source: DamageSource, lost: Float, factor: Float) {}

    /** Eigener Schaden ohne Faktoren und Spielerzahl (z. B. vom eigenen Fels getroffen). */
    protected fun hurtSelf(amount: Float) {
        val world = entityWorld as? ServerWorld ?: return
        timeUntilRegen = 0
        super.damage(world, world.damageSources.magic(), amount)
    }

    /** Offen: goldene Rune (an ihm oder an [at]), Treffer × [factor] für [ticks] Ticks. */
    protected fun open(ticks: Int, factor: Float, rune: Boolean = true, at: Vec3d? = null) {
        val world = entityWorld as? ServerWorld ?: return
        openUntil = world.time + ticks
        openFactor = factor
        if (rune) {
            val radius = if (at != null) 1.8f else max(2.2f, width * 1.3f)
            Vfx.rune(world, Vfx.Style.GOLD, (at ?: entityPos).add(0.0, 0.08, 0.0), radius, ticks, if (at == null) this else null)
        }
        telegraph.sound(HhaSounds.CHIME, entityPos, 1.8f, 0.8f)
    }

    protected fun close() {
        openUntil = 0L
    }

    /** Schaden an einem Spieler, nach Spielerzahl skaliert. */
    protected fun strike(player: ServerPlayerEntity, base: Float): Boolean {
        val world = entityWorld as? ServerWorld ?: return false
        return player.damage(world, world.damageSources.mobAttack(this), base * damageScale)
    }

    /** Stößt [player] waagerecht in [dir] und nach oben. */
    protected fun knock(player: ServerPlayerEntity, dir: Vec3d, strength: Double, up: Double) {
        Fx.launchPlayer(player, flatNorm(dir).multiply(strength).add(0.0, up, 0.0))
    }

    /** Zeigt allen Mitkämpfern einmal je Wächter einen Hinweis in Gold. */
    protected fun hint(key: String) {
        if (!hinted.add(key)) return
        for (player in fighters()) player.sendMessage(Text.translatable(key).formatted(Formatting.GOLD), true)
    }

    private fun updateScaling() {
        val crowd = max(1, fighters().size)
        if (crowd == scaledFor) return
        scaledFor = crowd
        healthScale = 1f + HhaConfig.numF("hermes_health_per_player") * (crowd - 1)
        damageScale = 1f + HhaConfig.numF("hermes_damage_per_player") * (crowd - 1)
    }

    // ---------------------------------------------------------------------------------------------
    // Blick und Geometrie
    // ---------------------------------------------------------------------------------------------

    /** Hält die Blickrichtung (waagerecht) jeden Tick, bis `hold(null)`; für Ausholen und Fenster. */
    protected fun hold(dir: Vec3d?) {
        held = dir?.let(::flatNorm)
        held?.let(::applyFacing)
    }

    private fun applyFacing(dir: Vec3d) {
        val yaw = (Math.toDegrees(atan2(dir.z, dir.x)) - 90.0).toFloat()
        this.yaw = yaw
        bodyYaw = yaw
        headYaw = yaw
    }

    /** Blickrichtung waagerecht (aus dem Yaw). */
    protected fun facing(): Vec3d {
        val r = Math.toRadians(yaw.toDouble())
        return Vec3d(-sin(r), 0.0, cos(r))
    }

    protected fun flatNorm(v: Vec3d): Vec3d = Vec3d(v.x, 0.0, v.z).let { if (it.lengthSquared() < 1e-6) facing() else it.normalize() }

    protected fun dirTo(pos: Vec3d): Vec3d = flatNorm(pos.subtract(entityPos))

    protected fun flatDistance(entity: Entity): Double = flat(entityPos, entity.entityPos)

    /** Dreht [dir] um [degrees] (von oben im Uhrzeigersinn positiv). */
    protected fun rotate(dir: Vec3d, degrees: Double): Vec3d {
        val a = Math.toRadians(degrees)
        return Vec3d(dir.x * cos(a) - dir.z * sin(a), 0.0, dir.x * sin(a) + dir.z * cos(a))
    }

    /** Dreht [from] auf [to] zu, höchstens um [maxDegrees]. */
    protected fun turnToward(from: Vec3d, to: Vec3d, maxDegrees: Double): Vec3d {
        val delta = Math.toDegrees(atan2(to.z, to.x) - atan2(from.z, from.x)).let { ((it + 540.0) % 360.0) - 180.0 }
        return rotate(from, delta.coerceIn(-maxDegrees, maxDegrees))
    }

    /** Höhe des Bodens unter [pos] (Strahl nach unten ab 1,5 Blöcken darüber), sonst `pos.y`. */
    protected fun floorAt(pos: Vec3d, depth: Double = 6.0): Double = floorOrNull(pos.add(0.0, 1.5, 0.0), depth + 1.5) ?: pos.y

    /** Erster fester Block unter [start], höchstens [depth] tief; `null`, wenn keiner da ist. */
    protected fun floorOrNull(start: Vec3d, depth: Double): Double? {
        val hit = entityWorld.raycast(RaycastContext(start, start.add(0.0, -depth, 0.0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this))
        return if (hit.type == HitResult.Type.MISS) null else hit.pos.y
    }

    /** [pos] auf den Boden gesetzt (für Ansagen). */
    protected fun ground(pos: Vec3d): Vec3d = Vec3d(pos.x, floorAt(pos) + 0.05, pos.z)

    /** Springt [player] gerade (über eine Bodenwelle oder einen Feger)? */
    protected fun airborne(player: ServerPlayerEntity): Boolean = !player.isOnGround && player.y - floorAt(player.entityPos, 4.0) > 0.45

    /** Wie weit [pos] entlang [dir] ab [from] liegt (waagerecht). */
    protected fun along(pos: Vec3d, from: Vec3d, dir: Vec3d): Double = (pos.x - from.x) * dir.x + (pos.z - from.z) * dir.z

    /** Punkt auf einem Bogen von [from] nach [to] bei [t] (0..1), [lift] Blöcke über der höheren Seite. */
    protected fun arc(from: Vec3d, to: Vec3d, t: Double, lift: Double): Vec3d {
        val top = max(from.y, to.y) + lift
        val control = 2.0 * top - (from.y + to.y) * 0.5
        val u = 1.0 - t
        return Vec3d(from.x + (to.x - from.x) * t, u * u * from.y + 2.0 * u * t * control + t * t * to.y, from.z + (to.z - from.z) * t)
    }

    /**
     * Eine Linien-Ansage, die dem Gelände folgt (Hügel, Absätze): Stücke von höchstens 4 Blöcken in [group], jedes auf
     * dem Boden. Für die Trefferprüfung zählt die ganze Linie [from]–[to] (waagerecht). Liefert die Dauer bis zum Treffer.
     */
    protected fun terrainLine(group: Telegraph.Fight.Group, from: Vec3d, to: Vec3d, width: Float, ticks: Int): Int {
        val parts = max(1, ceil(flat(from, to) / 4.0).toInt())
        var last = ground(from)
        var result = ticks
        for (i in 1..parts) {
            val next = ground(from.lerp(to, i.toDouble() / parts))
            result = group.line(last, next, width, ticks).ticks
            last = next
        }
        return result
    }

    protected fun stopMoving() {
        navigation.stop()
        velocity = Vec3d(0.0, velocity.y, 0.0)
        forwardSpeed = 0f
        sidewaysSpeed = 0f
    }

    protected suspend fun SequenceScope<Unit>.wait(ticks: Int) {
        repeat(ticks) { yield(Unit) }
    }

    /** Wartet [ticks] Ticks, bricht aber ab, sobald [breakIf] gilt; true = abgebrochen. */
    protected suspend fun SequenceScope<Unit>.window(ticks: Int, breakIf: () -> Boolean): Boolean {
        repeat(ticks) {
            if (breakIf()) return true
            yield(Unit)
        }
        return false
    }

    // ---------------------------------------------------------------------------------------------
    // Vanilla
    // ---------------------------------------------------------------------------------------------

    override fun handleStatus(status: Byte) {
        if (!kit.onStatus(status)) super.handleStatus(status)
    }

    override fun onDeath(damageSource: DamageSource) {
        super.onDeath(damageSource)
        if (!entityWorld.isClient) {
            tasks.clear()
            clearProps()
            telegraph.reset()
            kit.defeated()
        }
    }

    override fun remove(reason: RemovalReason) {
        kit.onRemoved()
        clearProps()
        super.remove(reason)
    }

    override fun handleFallDamage(fallDistance: Double, damagePerDistance: Float, damageSource: DamageSource): Boolean = false
    override fun canBreatheInWater(): Boolean = true
    override fun isPushable(): Boolean = false
    override fun takeKnockback(strength: Double, x: Double, z: Double) {}
    override fun canHaveStatusEffect(effect: StatusEffectInstance): Boolean = false
    override fun canImmediatelyDespawn(distanceSquared: Double): Boolean = false
    override fun canUsePortals(allowVehicles: Boolean): Boolean = false
    override fun shouldSave(): Boolean = false
    override fun isFireImmune(): Boolean = true
    override fun dropLoot(world: ServerWorld, damageSource: DamageSource, causedByPlayer: Boolean) {}

    companion object {
        /** Waagerechter Abstand. */
        fun flat(a: Vec3d, b: Vec3d): Double = hypot(a.x - b.x, a.z - b.z)

        /** Grundwerte; die Wächter setzen ihre eigenen über [MiniBossKit.stats]. */
        fun guardianAttributes(): DefaultAttributeContainer.Builder = createHostileAttributes()
    }
}

/**
 * Ein Geschoss, das der Wächter selbst führt (Fels im Wurf und im Rollen, Funke): Der Server setzt es Tick für Tick an
 * seinen Ort; es fliegt nicht selbst und trifft nichts (Geschwindigkeit 0, also keine Kollision). Getroffen wird
 * über die Ansage. Dem Client wird die Bewegung als Geschwindigkeit gemeldet, damit der Fels im Bild abrollt und der
 * Funke seinen Schweif zieht (`BoltRenderer`).
 */
class ScriptedBolt private constructor(private val bolt: HermesBoltEntity) {

    val pos: Vec3d get() = bolt.entityPos
    val alive: Boolean get() = !bolt.isRemoved

    /** Setzt das Geschoss nach [to] und meldet den Schritt dem Client. */
    fun moveTo(to: Vec3d) {
        if (bolt.isRemoved) return
        val step = to.subtract(bolt.entityPos)
        bolt.setPosition(to)
        bolt.velocity = Vec3d.ZERO
        (bolt.entityWorld as? ServerWorld)?.chunkManager?.sendToOtherNearbyPlayers(bolt, EntityVelocityUpdateS2CPacket(bolt.id, step))
    }

    /** Liegt still (der Client hört auf zu rollen). */
    fun rest() = moveTo(bolt.entityPos)

    fun discard() {
        if (!bolt.isRemoved) bolt.discard()
    }

    companion object {
        /** Sicherheitsgrenze; der Wächter entfernt seine Geschosse selbst. */
        private const val MAX_AGE = 400

        fun spawn(world: ServerWorld, owner: Entity, kind: Int, size: Float, at: Vec3d): ScriptedBolt? {
            val bolt = HhaEntities.HERMES_BOLT.create(world, SpawnReason.EVENT) ?: return null
            bolt.kind = kind
            bolt.size = size
            bolt.damage = 0f
            // Nur Bild: trifft nie selbst, lebt so lange, wie der Wächter es führt (liegender Fels bis 8 s).
            bolt.visual = true
            bolt.maxAge = MAX_AGE
            bolt.setOwner(owner)
            bolt.setPosition(at)
            bolt.velocity = Vec3d.ZERO
            world.spawnEntity(bolt)
            return ScriptedBolt(bolt)
        }
    }
}
