package dev.henny.hha.entity.mob

import dev.henny.hha.HhaItems
import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.miniboss.GuardianEntity
import dev.henny.hha.entity.miniboss.MiniBossKit
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossDefense
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.logic.boss.BossScaling
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha.logic.boss.timings.OlympMobTimings
import dev.henny.hha.logic.world.OlympusLife
import net.minecraft.entity.EntityType
import net.minecraft.entity.ExperienceOrbEntity
import net.minecraft.entity.MovementType
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Aktionen der Kurete (PLAN 10.2). */
object KureteAction {
    const val NONE = 0
    const val CLASH = 1
    const val THRUST = 2
    const val SHIELD_BASH = 3
    const val GUARD = 4
}

/** Aktionen des Greifen (PLAN 10.2). */
object GriffinAction {
    const val NONE = 0
    const val PERCH = 1
    const val DIVE = 2
    const val LANDED = 3
    const val SWIPE = 4
    const val TAKEOFF = 5
}

/** Aktionen der Goldenen Wächterin (PLAN 10.2). */
object AutomatonAction {
    const val NONE = 0
    const val STATUE = 1
    const val WAKE = 2
    const val HAMMER_ARM = 3
    const val STEAM = 4
}

/**
 * Gemeinsame Basis der normalen Gegner des Olymp (PLAN 5.6): Drehbuch der Wächter ([GuardianEntity]), aber ohne
 * Lebensleiste, ohne Titel, ohne „befreit für immer“, Stufe [BossScaling.Tier.MOB] (kein Deckel, keine Abwehr),
 * Familie Olymp (`hermes_damage`), Skalierung nach Spielern im Umkreis [OlympMobTimings.SCALE_RADIUS].
 *
 * - **Aufmerksamkeit:** Ein Gegner am Posten kämpft erst, wenn ein Spieler (nicht Kreativ/Zuschauer) näher als
 *   [alertRange] kommt und sichtbar ist, oder wenn ihn jemand trifft. Dann zählen alle Spieler im
 *   [OlympMobTimings.ENGAGE_RADIUS] um den Posten als Mitkämpfer. Nach [OlympMobTimings.CALM_TICKS] ohne Spieler dort
 *   wird er wieder ruhig.
 * - **Leine** [OlympMobTimings.LEASH] um den Posten ([home]); dahinter zurück und heilen (aus der Basis).
 * - **Verschwinden:** Ist [OlympusLife.DESPAWN_TICKS] lang kein Spieler im Umkreis [OlympusLife.DESPAWN_DISTANCE],
 *   verschwindet er (wie die Wächter wird er nie gespeichert). Der Posten besetzt sich beim nächsten Besuch neu.
 * - **Beute** (PLAN 5.6) nur, wenn ein Spieler ihn besiegt hat.
 */
abstract class OlympMobEntity(type: EntityType<out OlympMobEntity>, world: World) : GuardianEntity(type, world) {

    override val tier: BossScaling.Tier get() = BossScaling.Tier.MOB
    override val family: BossScaling.Family get() = BossScaling.Family.OLYMP

    override fun leash(): Double = OlympMobTimings.LEASH
    override fun basePause(): Int = OlympMobTimings.PAUSE + random.nextInt(6)

    /** Ist er gerade im Kampf (sonst steht er an seinem Posten)? */
    var alerted = false
        private set

    private var calm = 0
    private var away = 0
    private var killedByPlayer = false

    /** Ab dieser Nähe bemerkt er einen Spieler. */
    protected abstract fun alertRange(): Double

    /** Kämpft er? Standard: aufmerksam. Die Wächterin kämpft nur wach (oder wenn sie gleich erwacht). */
    protected open fun engaged(): Boolean = alerted

    /** Wird aufmerksam (auch von außen: die Gruppe der Kureten). */
    open fun alert() {
        calm = 0
        alerted = true
    }

    override fun scalingCrowd(): Int {
        val r = OlympMobTimings.SCALE_RADIUS
        return BossScaling.crowd(fighters().filter { it.squaredDistanceTo(this) < r * r })
    }

    /** Mitkämpfer: nur wenn er kämpft, dann alle lebenden Spieler (nicht Kreativ/Zuschauer) rund um den Posten. */
    override fun fighters(): List<ServerPlayerEntity> {
        if (!engaged()) return emptyList()
        val world = entityWorld as? ServerWorld ?: return emptyList()
        val anchor = home ?: entityPos
        val r = OlympMobTimings.ENGAGE_RADIUS
        return world.players.filter { survivor(it) && it.squaredDistanceTo(anchor) < r * r }
    }

    /** Kein Auftritt: Normale Gegner haben keinen Titel und kein Brüllen. */
    override fun entrance(): Sequence<Unit> = emptySequence()

    /** Ohne Mitkämpfer steht er nur (verschwinden regelt [tick] nach eigenem Maß). */
    override fun onLonely() {}

    override fun tick() {
        val world = entityWorld as? ServerWorld
        if (world != null && kit.barVisible) kit.barVisible = false
        super.tick()
        if (world == null || !isAlive || isRemoved) return
        if (age % 5 == 0) watch(world)
        if (age % 20 == 0) {
            val r = OlympusLife.DESPAWN_DISTANCE
            val anyone = world.players.any { !it.isSpectator && it.squaredDistanceTo(this) < r * r }
            away = if (anyone) 0 else away + 20
            if (away >= OlympusLife.DESPAWN_TICKS) discard()
        }
    }

    /** Wer kommt nahe genug, wer ist noch da? */
    private fun watch(world: ServerWorld) {
        val r = alertRange()
        if (!alerted && world.players.any { survivor(it) && it.squaredDistanceTo(this) < r * r && canSee(it) }) {
            alert()
            onAlert()
        }
        if (!alerted) return
        val anchor = home ?: entityPos
        val e = OlympMobTimings.ENGAGE_RADIUS
        if (world.players.any { survivor(it) && it.squaredDistanceTo(anchor) < e * e }) {
            calm = 0
        } else {
            calm += 5
            if (calm >= OlympMobTimings.CALM_TICKS) alerted = false
        }
    }

    /** Gerade aufmerksam geworden (z. B. Ruf an die Gruppe). */
    protected open fun onAlert() {}

    override fun onDamaged(source: DamageSource, lost: Float, factor: Float) {
        if (source.attacker is PlayerEntity && !alerted) {
            alert()
            onAlert()
        }
    }

    /** Stößt [player] weg, aber nie über eine Inselkante: Ohne Boden dahinter bleibt es bei einem Schubser. */
    protected fun safeKnock(player: ServerPlayerEntity, dir: Vec3d, strength: Double, up: Double) {
        val n = flatNorm(dir)
        val land = player.entityPos.add(n.multiply(strength * 4.0))
        val floor = floorOrNull(Vec3d(land.x, player.y + 1.0, land.z), OlympMobTimings.EDGE_SAFE_DEPTH + 1.0)
        if (floor == null) knock(player, n, OlympMobTimings.EDGE_SAFE_PUSH, 0.1) else knock(player, n, strength, up)
    }

    /** Liegt der Angreifer (bzw. sein Geschoss) im Halbwinkel [halfAngle] vor ihm? */
    protected fun fromFront(source: DamageSource, halfAngle: Double): Boolean? {
        val from = source.position ?: source.attacker?.entityPos ?: return null
        val to = Vec3d(from.x - x, 0.0, from.z - z)
        if (to.lengthSquared() < 1e-4) return null
        return facing().dotProduct(to.normalize()) > cos(Math.toRadians(halfAngle))
    }

    /** Ein Hinweis in Gold, einmal je Spieler und Schlüssel (für alle Gegner gemeinsam). */
    protected fun hintOnce(player: ServerPlayerEntity, key: String) {
        if (hinted.getOrPut(player.uuid) { HashSet() }.add(key)) {
            player.sendMessage(Text.translatable(key).formatted(Formatting.GOLD), true)
        }
    }

    override fun onDeath(damageSource: DamageSource) {
        killedByPlayer = damageSource.attacker is PlayerEntity || primeAdversary is PlayerEntity
        super.onDeath(damageSource)
    }

    /** Beute nach PLAN 5.6: Goldnuggets 2–5, Kupferbarren 1–3, 5 % ein Heavens Barren, 5–8 Erfahrung. */
    override fun onDefeated() {
        val world = entityWorld as? ServerWorld ?: return
        if (!killedByPlayer) return
        val t = OlympMobTimings
        dropStack(world, ItemStack(Items.GOLD_NUGGET, t.LOOT_NUGGETS_MIN + random.nextInt(t.LOOT_NUGGETS_MAX - t.LOOT_NUGGETS_MIN + 1)))
        dropStack(world, ItemStack(Items.COPPER_INGOT, t.LOOT_COPPER_MIN + random.nextInt(t.LOOT_COPPER_MAX - t.LOOT_COPPER_MIN + 1)))
        if (random.nextFloat() < t.LOOT_INGOT_CHANCE) dropStack(world, ItemStack(HhaItems.HEAVEN_INGOT))
        ExperienceOrbEntity.spawn(world, entityPos, t.LOOT_XP_MIN + random.nextInt(t.LOOT_XP_MAX - t.LOOT_XP_MIN + 1))
    }

    companion object {
        /** Welche Hinweise jeder Spieler schon gesehen hat (bis zum Neustart des Servers). */
        private val hinted = HashMap<UUID, MutableSet<String>>()

        /** Löst aus und kämpft mit: lebt, nicht Kreativ, kein Zuschauer. */
        fun survivor(player: ServerPlayerEntity): Boolean = player.isAlive && !player.isSpectator && !player.isCreative

        fun clearHints() = hinted.clear()
    }
}

// =================================================================================================
// Kurete
// =================================================================================================

/**
 * Die Marke einer Kureten-Gruppe: **Immer nur einer greift an** (PLAN 5.6), die anderen umkreisen das Ziel in 5–6
 * Blöcken. Wer die Marke hält, hält sie vom Speerschlag an den Schild bis zum Ende seiner Deckung; nimmt einer sie,
 * ohne anzugreifen (Ziel lief weg), verfällt sie nach [OlympMobTimings.TOKEN_GRACE] Ticks. Der Posten
 * ([OlympusLife]) legt je Gruppe eine an; eine einzelne Kurete ohne Gruppe braucht keine.
 */
class KureteSquad {
    private val members = ArrayList<KureteEntity>()
    private var holder: KureteEntity? = null
    private var takenAt = 0L
    private var last: KureteEntity? = null
    private var releasedAt = 0L

    fun add(kurete: KureteEntity) {
        members.add(kurete)
        kurete.squad = this
    }

    /** Alle der Gruppe werden aufmerksam (die Kureten schlagen gemeinsam Lärm). */
    fun alertAll() {
        for (k in members) if (k.isAlive && !k.alerted) k.alert()
    }

    /** Darf [k] jetzt angreifen? */
    fun mayAttack(k: KureteEntity, now: Long): Boolean {
        members.removeIf { it.isRemoved || !it.isAlive }
        val h = holder
        if (h != null && h !== k) {
            val valid = h.isAlive && !h.isRemoved && (h.attacking || now - takenAt < OlympMobTimings.TOKEN_GRACE)
            if (valid) return false
            holder = null
        }
        if (h === k) return true
        // Wer eben dran war, lässt den anderen den Vortritt.
        return !(k === last && now - releasedAt < OlympMobTimings.TOKEN_TURN && members.size > 1)
    }

    /** Nimmt die Marke (falls erlaubt). */
    fun take(k: KureteEntity, now: Long): Boolean {
        if (!mayAttack(k, now)) return false
        if (holder !== k) {
            holder = k
            takenAt = now
        }
        return true
    }

    fun release(k: KureteEntity, now: Long) {
        if (holder !== k) return
        holder = null
        last = k
        releasedAt = now
    }
}

/**
 * Kurete, Himmelssoldat (PLAN 5.6). Die Kureten schlugen die Speere an die Schilde, damit Kronos den Säugling Zeus
 * nicht schreien hörte: **Vor jedem Angriff** schlägt er den Speer an den Schild (`CLASH`, der Ton ist die Ansage),
 * dann nach Entfernung **Speerstoß** (Linie) oder **Schildschlag** (Kegel, nah), danach 30 Ticks **Deckung**: von vorn
 * × 0,3, Seite und Rücken voll. In der Gruppe greift immer nur einer an ([KureteSquad]).
 */
class KureteEntity(type: EntityType<out KureteEntity>, world: World) : OlympMobEntity(type, world) {

    override val kit = MiniBossKit(this, "entity.hha.kurete", BossBar.Color.WHITE, Vfx.Style.HEAVEN)
    override val fightName = "kurete"
    override val danger = Vfx.Style.HEAVEN

    /** Seine Gruppe (vom Posten gesetzt); `null` = allein. */
    var squad: KureteSquad? = null

    /** Läuft gerade sein Angriff (vom Schildschlag bis zum Ende der Deckung)? */
    var attacking = false
        private set

    private var guardUntil = 0L
    private var orbitSign = if (random.nextBoolean()) 1.0 else -1.0

    init {
        kit.stats(OlympMobTimings.KURETE_HEALTH, 6.0, 6.0, OlympMobTimings.KURETE_SPEED)
    }

    override fun alertRange(): Double = OlympMobTimings.KURETE_ALERT

    override fun onAlert() {
        squad?.alertAll()
        // Ein Speerschlag als Ruf: Die Gruppe hat dich bemerkt.
        telegraph.sound(HhaSounds.KURETE_CLASH, entityPos, 1.2f, 0.85f)
    }

    private fun now(): Long = entityWorld.time

    /** Ein Angriff mit der Ansage `CLASH`; welcher Hieb folgt, entscheidet die Entfernung danach. */
    override fun createMoves() = listOf(
        Move(KureteAction.CLASH, 0.0..OlympMobTimings.KURETE_REACH, 1, { squad?.take(this, now()) ?: true }) { assault(it) },
    )

    /** Wer nicht dran ist, umkreist das Ziel in 5–6 Blöcken statt heranzugehen. */
    override fun approach(target: ServerPlayerEntity, distance: Double) {
        val sq = squad
        if (sq == null || sq.mayAttack(this, now())) {
            super.approach(target, distance)
            return
        }
        lookControl.lookAt(target, 30f, 30f)
        val t = OlympMobTimings
        val r = (t.KURETE_CIRCLE_MIN + t.KURETE_CIRCLE_MAX) * 0.5
        val angle = atan2(z - target.z, x - target.x) + Math.toRadians(t.KURETE_ORBIT_STEP) * orbitSign
        val goal = Vec3d(target.x + cos(angle) * r, target.y, target.z + sin(angle) * r)
        if (age % 8 == 0 || navigation.isIdle) navigation.startMovingTo(goal.x, goal.y, goal.z, 0.85)
        if (horizontalCollision && age % 20 == 0) orbitSign = -orbitSign
    }

    private fun assault(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val t = OlympMobTimings
        if (squad?.take(this@KureteEntity, now()) == false) return@sequence
        attacking = true
        stopMoving()
        var dir = dirTo(target.entityPos)
        hold(dir)
        // CLASH: Speer an den Schild, zweimal. Das ist die Ansage, die Hiebe kommen erst danach.
        kit.act(KureteAction.CLASH)
        for (tick in 0 until t.CLASH_END) {
            if (tick == t.CLASH_HIT_1 || tick == t.CLASH_HIT_2) {
                telegraph.sound(HhaSounds.KURETE_CLASH, entityPos, 1.6f, if (tick == t.CLASH_HIT_1) 1.0f else 1.12f)
            }
            if (target.isAlive) dir = turnToward(dir, dirTo(target.entityPos), t.CLASH_TURN)
            hold(dir)
            yield(Unit)
        }
        if (target.isAlive) dir = turnToward(dir, dirTo(target.entityPos), t.CLASH_TURN)
        hold(dir)
        if (target.isAlive && flatDistance(target) <= t.SHIELD_BASH_PICK) yieldAll(shieldBash(dir)) else yieldAll(thrust(dir))
        yieldAll(guard(dir, target))
        kit.act(0)
        attacking = false
        squad?.release(this@KureteEntity, now())
        // Nur ein Angriff, also „nie zweimal derselbe“ nicht anwenden: Die Entfernung entscheidet.
        lastMove = -1
    }

    /** Speerstoß: Linie 1,2 × 4 vor ihm, W 12, S 8. */
    private fun thrust(dir: Vec3d): Sequence<Unit> = sequence {
        val t = OlympMobTimings
        kit.act(KureteAction.THRUST)
        val from = entityPos.add(dir.multiply(0.4))
        val group = telegraph.boss()
        group.line(ground(from), ground(from.add(dir.multiply(t.THRUST_LENGTH.toDouble()))), t.THRUST_WIDTH, t.THRUST_W)
        wait(group.ticks)
        telegraph.sound(HhaSounds.WHOOSH, entityPos, 1.2f, 1.3f)
        for (player in group.hits()) strike(player, t.THRUST_DAMAGE)
        wait(t.THRUST_END - t.THRUST_W)
    }

    /** Schildschlag: Kegel 50° (halb 25°), Radius 3, W 10, S 6, kurzer Schubs. */
    private fun shieldBash(dir: Vec3d): Sequence<Unit> = sequence {
        val t = OlympMobTimings
        kit.act(KureteAction.SHIELD_BASH)
        val group = telegraph.boss()
        group.cone(ground(entityPos), dir, t.SHIELD_BASH_RADIUS, t.SHIELD_BASH_HALF_ANGLE, t.SHIELD_BASH_W)
        wait(group.ticks)
        telegraph.sound(HhaSounds.IMPACT, entityPos, 1.0f, 1.4f)
        // Überarbeitung 3 (P15): ein paar Funken am Schildrand.
        BossFx.sparks(sw, danger, shieldEdge(dir), dir, MobFx.SPARKS, MobFx.SPARK_SPEED, MobFx.SPARK_LIFE)
        for (player in group.hits()) {
            if (strike(player, t.SHIELD_BASH_DAMAGE)) safeKnock(player, dir, t.SHIELD_BASH_PUSH, 0.25)
        }
        wait(t.SHIELD_BASH_END - t.SHIELD_BASH_W)
    }

    /** Deckung: 30 Ticks Schild hoch; er dreht sich nur langsam nach, also flankieren. */
    private fun guard(start: Vec3d, target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val t = OlympMobTimings
        kit.act(KureteAction.GUARD)
        guardUntil = now() + t.GUARD_TICKS
        var dir = start
        repeat(t.GUARD_TICKS) {
            if (target.isAlive) dir = turnToward(dir, dirTo(target.entityPos), t.GUARD_TURN)
            hold(dir)
            yield(Unit)
        }
        guardUntil = 0L
    }

    override fun hitFactor(source: DamageSource): Float {
        if (now() >= guardUntil) return 1f
        if (fromFront(source, OlympMobTimings.GUARD_HALF_ANGLE) != true) return 1f
        (source.attacker as? ServerPlayerEntity)?.let { hintOnce(it, "hha.kurete.hint.shield") }
        telegraph.sound(HhaSounds.KURETE_CLASH, entityPos, 0.9f, 1.5f)
        // Abprall: Funken springen vom Schild zum Angreifer (im Kampf-Scope, damit sie die Gefahrenfarbe tragen).
        val toward = source.attacker?.let { dirTo(it.entityPos) } ?: facing()
        telegraph.during { BossFx.sparks(sw, danger, shieldEdge(toward), toward, MobFx.SPARKS, MobFx.SPARK_SPEED, MobFx.SPARK_LIFE) }
        return OlympMobTimings.GUARD_FACTOR
    }

    /** Rand des Schilds in Richtung [dir] (Brusthöhe, knapp vor ihm). */
    private fun shieldEdge(dir: Vec3d): Vec3d = entityPos.add(dir.multiply(MobFx.SHIELD_AHEAD)).add(0.0, MobFx.SHIELD_HEIGHT, 0.0)

    override fun onReset() {
        guardUntil = 0L
        attacking = false
        squad?.release(this, now())
    }

    override fun remove(reason: RemovalReason) {
        attacking = false
        squad?.release(this, entityWorld.time)
        super.remove(reason)
    }

    companion object {
        fun attributes(): DefaultAttributeContainer.Builder = guardianAttributes()
    }
}

// =================================================================================================
// Greif
// =================================================================================================

/**
 * Greif, bei Aischylos der stumme Hund des Zeus mit scharfem Schnabel (PLAN 5.6). Er **sitzt auf seiner Säule**
 * (`PERCH`), bis ein Spieler näher als 16 kommt. Dann der **Sturzflug**: Linie von der Säule über das Ziel als Ansage
 * (W 16), Sturz in 12 Ticks; danach liegt er 30 Ticks am Boden (`LANDED`, offen × 1,5). Am Boden **Prankenhieb**
 * (Kegel). Nach 6 s am Boden oder zwei Hieben fliegt er auf (`TAKEOFF`) und zurück auf die Säule; fehlt die Säule,
 * schwebt er 8 Blöcke über dem Boden. Er fliegt nie durch Blöcke ([MovementType.SELF] mit Kollision; ist der Weg zu,
 * stürzt er nicht).
 */
class GriffinEntity(type: EntityType<out GriffinEntity>, world: World) : OlympMobEntity(type, world) {

    override val kit = MiniBossKit(this, "entity.hha.griffin", BossBar.Color.WHITE, Vfx.Style.STORM)
    override val fightName = "griffin"
    override val danger = Vfx.Style.STORM

    /** Fliegt er gerade (Schwingenschlag im Modell)? */
    var flying: Boolean
        get() = dataTracker.get(FLYING)
        set(value) = dataTracker.set(FLYING, value)

    private enum class Mode { PERCHED, DIVING, GROUND, FLYING }

    private var mode = Mode.PERCHED

    /** Sein Sitzplatz oben auf der Säule (Füße); vom Posten gesetzt, sonst die Stelle, an der er erscheint. */
    var perch: Vec3d? = null

    private var groundTicks = 0
    private var swipes = 0
    private var perchSentAt = -1000
    private var retaliate: ServerPlayerEntity? = null

    init {
        kit.stats(OlympMobTimings.GRIFFIN_HEALTH, 4.0, 6.0, OlympMobTimings.GRIFFIN_SPEED)
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(FLYING, false)
    }

    override fun alertRange(): Double = OlympMobTimings.GRIFFIN_TRIGGER

    override fun createMoves() = listOf(
        Move(GriffinAction.SWIPE, 0.0..OlympMobTimings.SWIPE_REACH, 1, { mode == Mode.GROUND }) { swipe(it) },
    )

    // --- Sitzplatz --------------------------------------------------------------------------------

    private fun perchPos(): Vec3d = perch ?: entityPos.also { perch = it }

    /** Steht unter dem Sitzplatz noch die Säule (ein fester Block)? */
    private fun hasPillar(spot: Vec3d): Boolean {
        val below = BlockPos.ofFloored(spot.x, spot.y - 0.5, spot.z)
        return !entityWorld.getBlockState(below).getCollisionShape(entityWorld, below).isEmpty
    }

    /** Wohin er zurückfliegt: auf die Säule, oder ohne Säule 8 Blöcke über den Boden darunter. */
    private fun perchSpot(): Vec3d {
        val spot = perchPos()
        if (hasPillar(spot)) return spot
        val floor = floorOrNull(spot.add(0.0, 1.0, 0.0), 40.0) ?: (spot.y - OlympMobTimings.TAKEOFF_HEIGHT)
        return Vec3d(spot.x, floor + OlympMobTimings.TAKEOFF_HEIGHT, spot.z)
    }

    override fun tick() {
        super.tick()
        if (entityWorld.isClient || !isAlive) return
        // Ohne Posten (`/summon`) ist der Sitzplatz die Stelle, an der er erscheint.
        if (perch == null) perch = entityPos
        if (mode == Mode.PERCHED) {
            if (hasPillar(entityPos) && !hasNoGravity()) {
                // PERCH ist eine Schleife; der Client setzt Aktionen nach 320 Ticks zurück.
                if (age - perchSentAt >= OlympMobTimings.PERCH_REFRESH) {
                    kit.act(GriffinAction.PERCH)
                    perchSentAt = age
                }
            } else if (!hasPillar(entityPos)) {
                // Schweben ohne Säule.
                setNoGravity(true)
                velocity = Vec3d.ZERO
                if (!flying) flying = true
            }
        }
    }

    override fun tickBody() {
        if (mode == Mode.GROUND) groundTicks++
    }

    override fun phaseEvent(): Sequence<Unit>? = when (mode) {
        Mode.PERCHED -> watchFromPerch()
        Mode.GROUND -> if (groundTicks >= OlympMobTimings.GROUND_TICKS || swipes >= OlympMobTimings.GROUND_SWIPES || fighters().isEmpty()) takeoff() else null
        else -> null
    }

    /** Auf der Säule: umschauen, bis jemand nahe genug ist (oder ihn trifft), dann stürzen. */
    private fun watchFromPerch(): Sequence<Unit> = sequence {
        val t = OlympMobTimings
        repeat(20) {
            velocity = Vec3d(0.0, velocity.y, 0.0)
            val crowd = fighters()
            val trigger = t.GRIFFIN_TRIGGER
            val target = retaliate?.takeIf { it in crowd && it.squaredDistanceTo(self) < t.GRIFFIN_RETALIATE * t.GRIFFIN_RETALIATE }
                ?: crowd.filter { it.squaredDistanceTo(self) < trigger * trigger && canSee(it) }.minByOrNull { it.squaredDistanceTo(self) }
            retaliate = null
            if (target != null) {
                // Steht der Spieler schon neben ihm (oben auf der Säule): gleich am Boden kämpfen.
                if (flatDistance(target) < t.GRIFFIN_BESIDE && kotlin.math.abs(target.y - y) < 1.5) {
                    land()
                    return@sequence
                }
                yieldAll(dive(target))
                return@sequence
            }
            crowd.minByOrNull { it.squaredDistanceTo(self) }?.let { lookControl.lookAt(it, 10f, 10f) }
            yield(Unit)
        }
    }

    /** Sturzflug: Linie 2,4 breit von der Säule über das Ziel (W 16), Sturz in 12 Ticks, dann 30 Ticks am Boden. */
    private fun dive(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val t = OlympMobTimings
        val start = entityPos
        val dir = if (flatDistance(target) < 1.0) facing() else dirTo(target.entityPos)
        val preferred = (flatDistance(target) + t.DIVE_OVERSHOOT).coerceIn(t.DIVE_MIN_LENGTH.toDouble(), t.DIVE_LENGTH.toDouble())
        val landing = listOf(preferred, t.DIVE_LENGTH.toDouble(), 11.0, 8.0, t.DIVE_MIN_LENGTH.toDouble())
            .distinct().firstNotNullOfOrNull { landingAt(start, dir, it) }
        if (landing == null) {
            // Der Weg ist zu (Mauer, Dach): kein Sturz, er wartet weiter.
            wait(20)
            return@sequence
        }
        mode = Mode.DIVING
        hold(dir)
        kit.act(GriffinAction.DIVE)
        telegraph.sound(HhaSounds.GRIFFIN_CRY, start, 1.8f, 1.0f)
        val foot = Vec3d(start.x, landing.y, start.z).add(dir.multiply(1.0))
        val group = telegraph.boss(pace = Telegraph.Pace.HEAVY)
        terrainLine(group, foot, landing.add(dir.multiply(0.6)), t.DIVE_WIDTH, t.DIVE_W)
        wait(group.ticks)
        // Sturz in 12 Ticks, mit Blockkollision. Die Front fegt die angesagte Linie ab: Wer in der Linie steht, wird
        // getroffen, sobald der Greif an ihm vorbei ist (also genau die Fläche der Ansage, nur nacheinander).
        setNoGravity(true)
        flying = true
        val struck = HashSet<UUID>()
        for (i in 1..t.DIVE_FALL) {
            val k = i.toDouble() / t.DIVE_FALL
            val goal = start.lerp(landing, k * k)
            velocity = Vec3d.ZERO
            move(MovementType.SELF, goal.subtract(entityPos))
            hold(dir)
            val front = along(entityPos, foot, dir) + t.DIVE_HIT_RADIUS
            for (player in fighters()) {
                if (player.uuid in struck) continue
                if (!Telegraph.inLine(player.entityPos, foot, landing, t.DIVE_WIDTH.toDouble(), player.width * 0.5)) continue
                if (along(player.entityPos, foot, dir) > front) continue
                struck.add(player.uuid)
                if (strike(player, t.DIVE_DAMAGE, BossDefense.Reach.BODY, landing.y)) safeKnock(player, dir, t.DIVE_PUSH, 0.3)
            }
            yield(Unit)
        }
        land()
        telegraph.sound(HhaSounds.IMPACT, entityPos, 1.2f, 1.1f)
        // Überarbeitung 3 (P15): kleiner Staubring beim Aufsetzen (kein Flächeneffekt: Radius ≤ 2, 10 Ticks).
        BossFx.play(sw, "mob.griffin_land", danger, ground(entityPos), a = MobFx.LAND_RADIUS, life = MobFx.LAND_LIFE)
        // Liegt am Boden: offen × 1,5.
        kit.act(GriffinAction.LANDED)
        open(t.LANDED_TICKS, t.LANDED_FACTOR)
        wait(t.LANDED_TICKS)
        kit.act(0)
    }

    /** Wo der Sturz endet (Boden mit Platz, Weg frei), oder `null`. */
    private fun landingAt(start: Vec3d, dir: Vec3d, length: Double): Vec3d? {
        val p = start.add(dir.multiply(length))
        val floor = floorOrNull(Vec3d(p.x, start.y + 1.0, p.z), 24.0) ?: return null
        if (floor > start.y + 0.5) return null
        val end = Vec3d(p.x, floor, p.z)
        if (!entityWorld.isSpaceEmpty(this, type.dimensions.getBoxAt(end))) return null
        for (h in doubleArrayOf(0.3, height * 0.8)) {
            val hit = entityWorld.raycast(RaycastContext(start.add(0.0, h, 0.0), end.add(0.0, h, 0.0), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this))
            if (hit.type != HitResult.Type.MISS) return null
        }
        return end
    }

    /** Ab jetzt am Boden (Schwerkraft, Hiebe, Uhr für das Auffliegen). */
    private fun land() {
        setNoGravity(false)
        flying = false
        mode = Mode.GROUND
        groundTicks = 0
        swipes = 0
    }

    /** Prankenhieb: Kegel 60° (halb), Radius 3,5, W 10, S 8. */
    private fun swipe(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val t = OlympMobTimings
        stopMoving()
        val dir = dirTo(target.entityPos)
        hold(dir)
        kit.act(GriffinAction.SWIPE)
        val group = telegraph.boss()
        group.cone(ground(entityPos), dir, t.SWIPE_RADIUS, t.SWIPE_HALF_ANGLE, t.SWIPE_W)
        wait(group.ticks)
        telegraph.sound(HhaSounds.WHOOSH, entityPos, 1.2f, 1.1f)
        for (player in group.hits()) strike(player, t.SWIPE_DAMAGE)
        wait(t.SWIPE_END - t.SWIPE_W)
        kit.act(0)
        swipes++
        // Nur ein Angriff am Boden: „nie zweimal derselbe“ gilt hier nicht.
        lastMove = -1
    }

    /** Auffliegen und zurück auf die Säule (ohne Säule: 8 Blöcke über dem Boden schweben). */
    private fun takeoff(): Sequence<Unit> = sequence {
        val t = OlympMobTimings
        mode = Mode.FLYING
        stopMoving()
        kit.act(GriffinAction.TAKEOFF)
        telegraph.sound(HhaSounds.GRIFFIN_CRY, entityPos, 1.4f, 1.2f)
        wait(t.TAKEOFF_DUCK)
        setNoGravity(true)
        flying = true
        repeat(t.TAKEOFF_END - t.TAKEOFF_DUCK) {
            velocity = Vec3d.ZERO
            move(MovementType.SELF, Vec3d(0.0, t.TAKEOFF_RISE, 0.0))
            yield(Unit)
        }
        val goal = perchSpot()
        val cruise = max(y, goal.y) + t.FLY_CLEARANCE
        var ok = flyTo(Vec3d(x, cruise, z), t.FLY_MAX_TICKS / 3)
        if (ok) ok = flyTo(Vec3d(goal.x, cruise, goal.z), t.FLY_MAX_TICKS)
        if (ok) ok = flyTo(goal, t.FLY_MAX_TICKS / 3)
        if (!ok) {
            // Kein freier Weg: landen, wo er ist, und es später noch einmal versuchen.
            land()
            groundTicks = t.GROUND_TICKS / 2
            kit.act(0)
            return@sequence
        }
        velocity = Vec3d.ZERO
        mode = Mode.PERCHED
        if (hasPillar(entityPos)) {
            setNoGravity(false)
            flying = false
            kit.act(GriffinAction.PERCH)
            perchSentAt = age
        } else {
            kit.act(0)
        }
    }

    /** Fliegt geradlinig nach [goal], mit Blockkollision; false, wenn er hängen bleibt oder die Zeit abläuft. */
    private suspend fun SequenceScope<Unit>.flyTo(goal: Vec3d, maxTicks: Int): Boolean {
        var stuck = 0
        repeat(maxTicks) {
            val to = goal.subtract(entityPos)
            val dist = to.length()
            velocity = Vec3d.ZERO
            if (dist < 0.3) {
                move(MovementType.SELF, to)
                return true
            }
            val before = entityPos
            val step = to.multiply(min(OlympMobTimings.FLY_SPEED, dist) / dist)
            move(MovementType.SELF, step)
            if (step.x * step.x + step.z * step.z > 1e-4) hold(step)
            stuck = if (entityPos.squaredDistanceTo(before) < 0.0025) stuck + 1 else 0
            if (stuck > 10) return false
            yield(Unit)
        }
        return false
    }

    override fun onDamaged(source: DamageSource, lost: Float, factor: Float) {
        super.onDamaged(source, lost, factor)
        if (mode == Mode.PERCHED) retaliate = source.attacker as? ServerPlayerEntity
    }

    /** Leine oder Neustart: mitten im Flug abgebrochen → zu Boden, später zurück auf die Säule. */
    override fun onReset() {
        if (mode == Mode.PERCHED && hasPillar(entityPos)) return
        setNoGravity(false)
        flying = false
        mode = Mode.GROUND
        groundTicks = OlympMobTimings.GROUND_TICKS
    }

    companion object {
        private val FLYING: TrackedData<Boolean> = DataTracker.registerData(GriffinEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)

        fun attributes(): DefaultAttributeContainer.Builder = guardianAttributes()
    }
}

// =================================================================================================
// Goldene Wächterin
// =================================================================================================

/**
 * Goldene Wächterin, Automat des Hephaistos (PLAN 5.6): Werk ohne Willen. Sie steht als **Statue** auf ihrem Sockel
 * (unbeweglich, aber verwundbar) und **erwacht**, wenn jemand näher als 6 kommt oder sie trifft (`WAKE`, 20 Ticks).
 * Wach ist sie langsam: **Hammerarm** (Kreis vor ihr, danach steckt der Arm 18 Ticks und sie dreht sich nicht) und
 * **Dampfstoß** (schmaler Kegel mit Schub). Das **Ventil auf dem Rücken** ist die Schwachstelle: von hinten × 1,5.
 * Ist 200 Ticks niemand im Umkreis 20, gleitet sie zum Sockel zurück und erstarrt.
 */
class AutomatonEntity(type: EntityType<out AutomatonEntity>, world: World) : OlympMobEntity(type, world) {

    override val kit = MiniBossKit(this, "entity.hha.automaton", BossBar.Color.YELLOW, Vfx.Style.FIRE)
    override val fightName = "automaton"
    override val danger = Vfx.Style.FIRE

    /** Wach (Augen glühen) oder Statue? */
    var awake: Boolean
        get() = dataTracker.get(AWAKE)
        set(value) = dataTracker.set(AWAKE, value)

    /** Blickrichtung als Statue (vom Posten gesetzt, sonst die beim Erscheinen). */
    var statueYaw: Float? = null

    private var wakeDue = false
    private var sleepDue = false
    /** Auf dem Rückweg getroffen oder bedrängt: nicht einschlafen. */
    private var disturbed = false
    private var quiet = 0
    private var statueSent = false

    init {
        kit.stats(OlympMobTimings.AUTOMATON_HEALTH, 10.0, 8.0, OlympMobTimings.AUTOMATON_SPEED)
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(AWAKE, false)
    }

    override fun alertRange(): Double = OlympMobTimings.WAKE_DISTANCE

    /**
     * Sie kämpft nur wach, oder wenn sie gleich erwacht: Ohne Mitkämpfer hält die Basis das Drehbuch nach einer Weile an,
     * dann käme das Erwachen nie dran.
     */
    override fun engaged(): Boolean = awake || wakeDue

    override fun chaseSpeed(): Double = OlympMobTimings.AUTOMATON_CHASE

    override fun createMoves() = listOf(
        Move(AutomatonAction.HAMMER_ARM, 0.0..OlympMobTimings.HAMMER_REACH, 2) { hammer(it) },
        Move(AutomatonAction.STEAM, OlympMobTimings.STEAM_REACH_MIN..OlympMobTimings.STEAM_REACH_MAX, 2) { steam(it) },
    )

    override fun tick() {
        super.tick()
        val world = entityWorld as? ServerWorld ?: return
        if (!isAlive) return
        if (statueYaw == null) statueYaw = yaw
        if (!awake) {
            if (!statueSent) {
                kit.act(AutomatonAction.STATUE)
                statueSent = true
            }
            val r = OlympMobTimings.WAKE_DISTANCE
            if (world.players.any { survivor(it) && it.squaredDistanceTo(this) < r * r }) wakeDue = true
            return
        }
        val s = OlympMobTimings.SLEEP_DISTANCE
        quiet = if (world.players.any { survivor(it) && it.squaredDistanceTo(this) < s * s }) 0 else quiet + 1
        if (quiet >= OlympMobTimings.SLEEP_TICKS) sleepDue = true
    }

    override fun phaseEvent(): Sequence<Unit>? = when {
        !awake && wakeDue -> wake()
        !awake -> statue()
        sleepDue -> sleep()
        else -> null
    }

    /** Statue: völlig reglos, Blick wie aufgestellt. */
    private fun statue(): Sequence<Unit> = sequence {
        repeat(10) {
            stopMoving()
            statueYaw?.let { yaw = it; bodyYaw = it; headYaw = it }
            if (wakeDue) return@sequence
            yield(Unit)
        }
    }

    /** Erwachen: Kopf ruckt, Arme lösen sich, die Augen glühen; Ton `mob.automaton_wake`. */
    private fun wake(): Sequence<Unit> = sequence {
        wakeDue = false
        sleepDue = false
        quiet = 0
        awake = true
        alert()
        kit.act(AutomatonAction.WAKE)
        telegraph.sound(HhaSounds.AUTOMATON_WAKE, entityPos, 1.6f, 1.0f)
        for (player in fighters()) hintOnce(player, "hha.automaton.hint.valve")
        repeat(OlympMobTimings.WAKE_END) {
            fighters().minByOrNull { it.squaredDistanceTo(self) }?.let { lookControl.lookAt(it, 8f, 8f) }
            yield(Unit)
        }
        kit.act(0)
    }

    /** Niemand mehr da: zum Sockel zurück (das letzte Stück gleitet sie hinauf), erstarren, ganz heilen. */
    private fun sleep(): Sequence<Unit> = sequence {
        val t = OlympMobTimings
        sleepDue = false
        disturbed = false
        kit.act(0)
        val spot = post ?: home ?: entityPos
        var ticks = 0
        while (flat(entityPos, spot) > t.SLEEP_GLIDE_FROM && ticks < t.SLEEP_WALK_MAX) {
            if (disturbed || nearbySurvivor()) return@sequence
            walkTo(spot, 1.0)
            ticks++
            yield(Unit)
        }
        stopMoving()
        // Kam sie zu Fuß nicht hin (Weg versperrt), steht sie einfach wieder auf dem Sockel: nie lange durch Wände gleiten.
        if (flat(entityPos, spot) > t.SLEEP_GLIDE_FROM + 1.0) refreshPositionAndAngles(spot.x, spot.y, spot.z, yaw, 0f)
        setNoGravity(true)
        while (entityPos.squaredDistanceTo(spot) > 0.01) {
            if (disturbed || nearbySurvivor()) {
                setNoGravity(false)
                return@sequence
            }
            val to = spot.subtract(entityPos)
            val len = to.length()
            setPosition(if (len <= t.SLEEP_GLIDE) spot else entityPos.add(to.multiply(t.SLEEP_GLIDE / len)))
            velocity = Vec3d.ZERO
            yield(Unit)
        }
        setNoGravity(false)
        statueYaw?.let { yaw = it; bodyYaw = it; headYaw = it }
        health = maxHealth
        awake = false
        kit.act(AutomatonAction.STATUE)
        statueSent = true
        wakeDue = false
    }

    private fun nearbySurvivor(): Boolean {
        val world = entityWorld as? ServerWorld ?: return false
        val s = OlympMobTimings.SLEEP_DISTANCE
        return world.players.any { survivor(it) && it.squaredDistanceTo(this) < s * s }
    }

    /** Hammerarm: Kreis Radius 3 vor ihr, W 18, S 12; danach steckt der Arm 18 Ticks (sie dreht sich nicht). */
    private fun hammer(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val t = OlympMobTimings
        stopMoving()
        val dir = dirTo(target.entityPos)
        hold(dir)
        kit.act(AutomatonAction.HAMMER_ARM)
        val group = telegraph.boss(pace = Telegraph.Pace.HEAVY)
        group.ring(ground(entityPos.add(dir.multiply(t.HAMMER_OFFSET))), t.HAMMER_RADIUS, t.HAMMER_W)
        telegraph.sound(HhaSounds.CHARGE, entityPos, 1.0f, 0.7f)
        wait(group.ticks)
        telegraph.sound(HhaSounds.IMPACT, entityPos, 1.6f, 0.8f)
        for (player in group.hits()) strike(player, t.HAMMER_DAMAGE)
        // Der Arm steckt: Zeit, hinter sie zu kommen.
        wait(t.HAMMER_END - t.HAMMER_W)
        kit.act(0)
    }

    /** Dampfstoß: Kegel 40° (halb 20°), Radius 6, W 14, S 6 und Schub (nie über die Kante). */
    private fun steam(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val t = OlympMobTimings
        stopMoving()
        val dir = dirTo(target.entityPos)
        hold(dir)
        kit.act(AutomatonAction.STEAM)
        val group = telegraph.boss()
        group.cone(ground(entityPos), dir, t.STEAM_RADIUS, t.STEAM_HALF_ANGLE, t.STEAM_W)
        wait(group.ticks)
        telegraph.sound(HhaSounds.HISS, entityPos, 1.4f, 0.9f)
        puffSteam(dir)
        for (player in group.hits()) {
            if (strike(player, t.STEAM_DAMAGE)) safeKnock(player, dirTo(player.entityPos), t.STEAM_PUSH, t.STEAM_UP)
        }
        wait(t.STEAM_END - t.STEAM_W)
        kit.act(0)
    }

    /**
     * Ein ruhiger Dampfschwall entlang des Kegels. Überarbeitung 3 (P15): weicher Kegel-Schleier `mob.steam` (glatter
     * Mantel und matte Dampfballen, kein Glühen) statt der Vanilla-Wolken; Reichweite und Winkel wie die Ansage.
     */
    private fun puffSteam(dir: Vec3d) {
        val world = entityWorld as? ServerWorld ?: return
        val nozzle = entityPos.add(0.0, MobFx.STEAM_HEIGHT, 0.0).add(dir.multiply(MobFx.STEAM_AHEAD))
        val t = OlympMobTimings
        BossFx.play(world, "mob.steam", danger, nozzle, dir, a = t.STEAM_RADIUS, b = t.STEAM_HALF_ANGLE, life = MobFx.STEAM_LIFE)
    }

    override fun hitFactor(source: DamageSource): Float {
        // Von hinten: das Ventil.
        val front = fromFront(source, 180.0 - OlympMobTimings.VALVE_HALF_ANGLE) ?: return 1f
        return if (front) 1f else OlympMobTimings.VALVE_FACTOR
    }

    override fun onDamaged(source: DamageSource, lost: Float, factor: Float) {
        super.onDamaged(source, lost, factor)
        if (!awake) wakeDue = true else disturbed = true
    }

    override fun onReset() {
        setNoGravity(false)
    }

    companion object {
        private val AWAKE: TrackedData<Boolean> = DataTracker.registerData(AutomatonEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)

        fun attributes(): DefaultAttributeContainer.Builder = guardianAttributes()
    }
}

/** Überarbeitung 3 (P15): reine Darstellungs-Konstanten der Olymp-Gegner (keine Kampfzahl). */
internal object MobFx {
    /** Funken am Schild der Kurete (Schildschlag und Abprall): Zahl, Tempo (Blöcke/Tick), Lebenszeit. */
    const val SPARKS = 4
    const val SPARK_SPEED = 0.32f
    const val SPARK_LIFE = 14
    const val SHIELD_AHEAD = 0.8
    const val SHIELD_HEIGHT = 1.25

    /** Staubring der Greifen-Landung (`mob.griffin_land`). */
    const val LAND_RADIUS = 2f
    const val LAND_LIFE = 10

    /** Dampfstoß der Wächterin (`mob.steam`): Düse über dem Boden und vor ihr, Lebenszeit. */
    const val STEAM_HEIGHT = 1.4
    const val STEAM_AHEAD = 0.6
    const val STEAM_LIFE = 22
}
