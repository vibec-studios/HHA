package dev.henny.hha.logic.boss

import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.HermaEntity
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.entity.boss.HermesEntity
import dev.henny.hha.entity.boss.HermesPose
import dev.henny.hha.entity.boss.SerpentEntity
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import net.minecraft.entity.Entity
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.effect.StatusEffectCategory
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Bilder der Hermes-Angriffe (Überarbeitung 3, P07, PLAN 2.4): Text-IDs `hermes.*`, am Client in
 * `client/vfx/fx/boss/HermesFx.kt` gezeichnet. Nur Darstellung: keine Zeit, kein Schaden, keine Fläche hängt daran.
 * Alles geht über [BossFx.play] (stumm beim Vorspulen, Gefahrenpalette im Kampf).
 */
internal object HermesLook {
    const val STREAK = "hermes.streak"
    const val LANE = "hermes.lane"
    const val STAR = "hermes.star"

    /** Höhe der Lichtbahn über den Füßen des Läufers (Körpermitte von Hermes, Modell 2,7 Blöcke). */
    const val BODY_LIFT = 1.3f

    /** Halbe Breite der Stoß-Lichtbahn. */
    const val STREAK_WIDTH = 0.45f

    /** Nachlauf der Lichtbahn nach dem Ende des Fluges (Schweif holt auf, Funken). Spiegelt `StreakFx`. */
    const val STREAK_TAIL = 20

    /** Kielwasser der Schlangen: halbe Breite, Höhe über der Bahn (Kopfhöhe), Ausblenden. */
    const val LANE_WIDTH = 0.28f
    const val LANE_LIFT = 0.45f
    const val LANE_FADE = 8

    /** Lichtstern am Ziel des Blinzelns. */
    const val STAR_SIZE = 1.5f
    const val STAR_LIFE = 12

    /** Kleines, schließendes Portal am Start des Blinzelns bzw. beim Auflösen eines Trugbilds. */
    const val PORTAL_RADIUS = 1.1f
    const val PORTAL_LIFE = 16
    const val DISSOLVE_RADIUS = 1.4f
    const val DISSOLVE_LIFE = 18

    /**
     * `hermes.streak`: gerade Lichtbahn von [from] nach [to]; der Kopf sitzt am Läufer [runner]. [ticks] = Flugzeit
     * (Rückfall am Client, solange das Wesen fehlt). pos = from, dir = to, a = Breite, b = ticks, c = Höhe.
     */
    fun streak(world: ServerWorld, style: Vfx.Style, runner: Entity, from: Vec3d, to: Vec3d, ticks: Int, width: Float = STREAK_WIDTH) =
        BossFx.play(
            world, STREAK, style, from, to, a = width, b = ticks.coerceAtLeast(1).toFloat(), c = BODY_LIFT,
            life = ticks.coerceAtLeast(1) + STREAK_TAIL, entity = runner,
        )

    /** Flugzeit einer geraden Bahn mit [speed] Blöcken je Tick (wie `dashTask`: Schritte bis zur Länge). */
    fun ticksFor(from: Vec3d, to: Vec3d, speed: Double): Int =
        kotlin.math.ceil(Vec3d(to.x - from.x, 0.0, to.z - from.z).length() / speed).toInt().coerceAtLeast(1)

    /**
     * `hermes.lane`: Kielwasser einer Schlange **auf denselben Stützpunkten** [lane], auf denen der Server den Kopf
     * bewegt ([out] Ticks hinaus, [back] zurück). pos = erster Punkt, a = Breite, b = Höhe, c = out, d = back,
     * extra = Punkte.
     */
    fun lane(world: ServerWorld, style: Vfx.Style, lane: List<Vec3d>, out: Int, back: Int) =
        BossFx.play(
            world, LANE, style, lane.first(), a = LANE_WIDTH, b = LANE_LIFT, c = out.toFloat(), d = back.toFloat(),
            life = out + back + LANE_FADE,
            extra = lane.joinToString(";") { String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f", it.x, it.y, it.z) },
        )

    /** `hermes.star`: kurzer Lichtstern bei [at]. */
    fun star(world: ServerWorld, style: Vfx.Style, at: Vec3d, size: Float = STAR_SIZE, life: Int = STAR_LIFE) =
        BossFx.play(world, STAR, style, at, a = size, life = life)

    /** Kleines Portal, das sich schließt (Blinzeln: Start), stehend, mit Blick in [facing]. */
    fun portal(world: ServerWorld, style: Vfx.Style, at: Vec3d, facing: Vec3d) =
        BossFx.swirl(world, style, at, if (facing.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else facing, PORTAL_RADIUS, PORTAL_LIFE, closing = true)

    /** Ein Trugbild löst sich auf: schließender Wirbel und eine kleine Federwolke (dort fliegen wirklich Federn). */
    fun dissolve(world: ServerWorld, style: Vfx.Style, body: Vec3d) {
        BossFx.swirl(world, style, body, Vec3d(0.0, 1.0, 0.0), DISSOLVE_RADIUS, DISSOLVE_LIFE, closing = true)
        Vfx.feathers(world, style, body, DISSOLVE_FEATHERS, 0.9f, 26)
    }

    const val DISSOLVE_FEATHERS = 8
}

/**
 * Das Kampfrepertoire von Hermes (Plan 4.2): je Stage eine **feste Reihenfolge**.
 *
 * Jeder Angriff hat drei Teile: Ansage über [Telegraph] (die Form ist genau die Trefferfläche, die Zeiten kommen aus
 * [BossTimings]) und eine Pose zu Beginn des Ausholens → Treffer → Erholung, in der er steht. Offene Fenster (SKID,
 * STUCK, STUMBLE, STAGGER, Erschöpfung) markiert [HermesFight.markOpen] mit der goldenen Rune.
 *
 * Kernidee in allen Stages: **den Boten fangen.** Ein Nahkampftreffer, während der echte Hermes im Stoß vorbeifliegt
 * (Pose DASH), zählt × [INTERCEPT_BONUS] und lässt ihn stolpern (STUMBLE, offen), höchstens einmal je Stoßfolge.
 *
 * Effekte nach Plan 3.3/3.4: je Treffer genau ein Flächeneffekt (Schockwelle/Nova/Säule/Sichel) und höchstens ein
 * Rütteln ≤ 4; Gefahr in der Farbe der Stage ([HermesFight.style]), Gold nur für Rune, sichere Flächen und den echten
 * Hermes. Keine Wirbelstürme, keine Zielsuche, kein Hotbar-Mischen, keine Unverwundbarkeit außerhalb von Stage IV.
 *
 * Überarbeitung 2 (PLAN 4.3, 5.1): Jeder Treffer nennt seine Reichweite ([BossDefense.Reach]); Seelensäulen, Speere des
 * Olymp und der Einschlag des Sturzflugs treffen auch in der Luft (`SKY`), Bodenwellen und Letztes Wort nur am Boden
 * (`LOW`), alles andere bis Körperhöhe (`BODY`). Wer ihn von oben bearbeitet, bekommt nach dem Angriff statt der Pause
 * den Konter „Abfangen in der Luft“ ([counter]). Pausen werden mit der Spielerzahl kürzer ([BossScaling.pause]).
 */
class HermesAttacks(private val fight: HermesFight) {

    private val world get() = fight.world
    private val center get() = fight.center
    private val boss: HermesEntity get() = fight.boss!!
    private val telegraph get() = fight.telegraph
    private val style get() = fight.style
    private var rotation = 0
    private var targetIndex = 0

    /** Abfangen: scharf, solange der echte Hermes in einer Stoßfolge ist; höchstens einmal je Folge. */
    private var interceptArmed = false
    private var intercepted = false

    /** Herma-Sprung: der glühende Zielstein und ob er zerschlagen wurde, bevor Hermes ankam. */
    private var leapTarget: HermaEntity? = null
    private var leapBroken = false

    private suspend fun SequenceScope<Unit>.wait(ticks: Int) {
        repeat(max(0, ticks)) { yield(Unit) }
    }

    /** Wartet höchstens [ticks] Ticks; bricht ab, sobald [stop] wahr wird. @return true, wenn ganz gewartet. */
    private suspend fun SequenceScope<Unit>.waitUnless(ticks: Int, stop: () -> Boolean): Boolean {
        repeat(max(0, ticks)) {
            if (stop()) return false
            yield(Unit)
        }
        return !stop()
    }

    fun reset() {
        rotation = 0
        interceptArmed = false
        intercepted = false
        leapTarget = null
        leapBroken = false
    }

    /**
     * Die nächste Attacke der Stage in fester Reihenfolge, danach die Pause der Stage (kürzer mit mehr Spielern) oder,
     * wenn ihn jemand von oben bearbeitet, statt der Pause der Konter.
     */
    fun next(stage: Int): Sequence<Unit> {
        val list: List<() -> Sequence<Unit>> = when (stage) {
            1 -> listOf({ courierDash(H.DASH_COUNT) }, ::featherFan, ::stormSlam)
            2 -> listOf(::mirrorDash, ::stickyFingers, ::crossroads, ::mirrorDash, ::hermaLeap)
            3 -> listOf(::twins, ::soulPillars, ::lanceRow, ::styx, { courierDash(H.DASH_COUNT_STAGE3) })
            else -> listOf({ dives(H.DIVE_EXHAUST) }, ::skySpears, { lastWord(H.FINAL_EXHAUST) })
        }
        val attack = list[rotation++ % list.size]()
        val pause = if (stage >= 4) H.PAUSE_STAGE4 else H.PAUSE
        return sequence {
            yieldAll(attack)
            fight.boss?.let { if (it.isAlive) it.pose = HermesPose.IDLE }
            if (fight.counterDue()) yieldAll(counter()) else wait(BossScaling.pause(pause, fight.crowd))
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Konter (PLAN 4.3): Abfangen in der Luft
    // -------------------------------------------------------------------------------------------------

    /**
     * **Abfangen in der Luft:** geduckt (DIVE) mit dem gemeinsamen Konter-Kreis um ihn ([BossDefense.COUNTER_TICKS],
     * Höhe unbegrenzt), dann schießt er senkrecht hoch: S 12 (`SKY`) und Abwurf nach außen und unten. Danach ist er
     * wachsam (Sturztreffer prallen ab).
     */
    private fun counter(): Sequence<Unit> = sequence {
        val guard = fight.guard ?: return@sequence
        guard.beginCounter()
        val from = boss.entityPos
        val spot = ground(from, 0.05)
        boss.pose = HermesPose.DIVE
        val ring = telegraph.ring(spot, guard.counterRadius().toFloat(), BossDefense.COUNTER_TICKS)
        fight.sound(HhaSounds.CHARGE, from, 1.8f, 1.4f)
        wait(ring.ticks)
        for (target in ring.hits(fight.targets())) {
            fight.hurt(target, Dmg.COUNTER, reach = BossDefense.Reach.SKY)
            guard.counterHit(target)
        }
        boss.pose = HermesPose.ASCEND
        HermesLook.streak(world, style, boss, from, from.add(0.0, H.COUNTER_HEIGHT, 0.0), H.COUNTER_RISE)
        fight.sound(HhaSounds.DASH, from, 2.2f, 1.3f)
        flyTo(from.add(0.0, H.COUNTER_HEIGHT, 0.0), H.COUNTER_RISE, smooth = false)
        guard.endCounter()
        flyTo(from, H.COUNTER_RETURN)
        boss.pose = HermesPose.IDLE
        wait(H.COUNTER_RECOVER)
    }

    // -------------------------------------------------------------------------------------------------
    // Treffer-Reaktionen
    // -------------------------------------------------------------------------------------------------

    /** Trugbilder zerplatzen beim ersten Treffer in einer Federwolke. */
    fun onIllusionStruck(entity: HermesEntity, source: DamageSource) {
        val pos = entity.entityPos
        HermesLook.dissolve(world, style, pos.add(0.0, 1.5, 0.0))
        fight.sound(HhaSounds.BLINK, pos, 1.5f, 1.3f)
        (source.attacker as? ServerPlayerEntity)?.let { fight.hint("hha.hermes.hint.fake", listOf(it)) }
        fight.illusions.remove(entity)
        entity.discard()
    }

    /**
     * Ein Treffer auf den echten Hermes: Trugbilder verpuffen, oder er wird im Stoß abgefangen.
     * @return Schadensfaktor ([INTERCEPT_BONUS] beim Abfangen, sonst 1).
     */
    fun onBossStruck(entity: HermesEntity, source: DamageSource): Float {
        fight.sound(HhaSounds.HIT, entity.entityPos.add(0.0, 1.5, 0.0), 1.6f, 0.9f + world.random.nextFloat() * 0.25f)
        if (fight.illusions.isNotEmpty()) {
            for (illusion in fight.illusions) {
                HermesLook.dissolve(world, style, illusion.entityPos.add(0.0, 1.5, 0.0))
                illusion.discard()
            }
            fight.illusions.clear()
        }
        val player = source.attacker as? ServerPlayerEntity ?: return 1f
        // Nahkampf: Angreifer und Schadensquelle sind derselbe (kein Pfeil, kein Dreizack).
        if (!source.isDirect) return 1f
        if (interceptArmed && !intercepted && entity.pose == HermesPose.DASH) {
            intercepted = true
            interceptArmed = false
            Vfx.flare(world, Vfx.Style.GOLD, entity.entityPos.add(0.0, 1.4, 0.0), 2.4f, 8)
            fight.hint("hha.hermes.hint.intercept", listOf(player))
            return INTERCEPT_BONUS
        }
        return 1f
    }

    /** Ein Herma-Stein ist zerbrochen; war es der glühende, stürzt Hermes ab. */
    fun onStoneBroken(stone: HermaEntity) {
        if (stone === leapTarget) leapBroken = true
    }

    // -------------------------------------------------------------------------------------------------
    // Hilfen
    // -------------------------------------------------------------------------------------------------

    /** Trifft ein Angriff mit [reach] den Spieler (Höhenregel, Boden = Forum)? Für Stöße, die nur Getroffene schieben. */
    private fun inReach(player: ServerPlayerEntity, reach: BossDefense.Reach = BossDefense.Reach.BODY): Boolean =
        BossDefense.reaches(player, fight.arena.floorY, reach, boss.height.toDouble())

    /** Punkt auf Bodenhöhe des Forums (plus [lift]). */
    private fun ground(pos: Vec3d, lift: Double = 0.0): Vec3d = Vec3d(pos.x, center.y + lift, pos.z)

    private fun flat(v: Vec3d): Vec3d = Vec3d(v.x, 0.0, v.z)

    private fun dir(angle: Double): Vec3d = Vec3d(cos(angle), 0.0, sin(angle))

    /** Waagerechte Richtung von [from] nach [to]; [fallback], wenn beide übereinander liegen. */
    private fun heading(from: Vec3d, to: Vec3d, fallback: Vec3d = Vec3d(1.0, 0.0, 0.0)): Vec3d =
        flat(to.subtract(from)).let { if (it.lengthSquared() < 1e-4) fallback else it.normalize() }

    private fun rotate(v: Vec3d, angle: Double): Vec3d {
        val c = cos(angle)
        val s = sin(angle)
        return Vec3d(v.x * c - v.z * s, 0.0, v.x * s + v.z * c)
    }

    private fun facing(entity: HermesEntity): Vec3d {
        val rad = Math.toRadians(entity.yaw.toDouble())
        return Vec3d(-sin(rad), 0.0, cos(rad))
    }

    private fun randomAngle(): Double = world.random.nextDouble() * PI * 2.0

    /** Ziele der Reihe nach, damit im Mehrspieler jeder einmal dran ist. */
    private fun nextTarget(): ServerPlayerEntity? {
        val list = fight.targets()
        if (list.isEmpty()) return null
        return list[targetIndex++ % list.size]
    }

    private suspend fun SequenceScope<Unit>.flyTo(to: Vec3d, ticks: Int, smooth: Boolean = true) {
        val from = boss.entityPos
        for (i in 1..max(1, ticks)) {
            val raw = i.toFloat() / max(1, ticks)
            val t = if (smooth) raw * raw * (3f - 2f * raw) else raw
            boss.setPosition(from.lerp(to, t.toDouble()))
            yield(Unit)
        }
    }

    /** Landet auf dem Forum, falls er in der Luft ist: Stöße und Fenster finden am Boden statt. */
    private suspend fun SequenceScope<Unit>.land(at: Vec3d = boss.entityPos) {
        val spot = fight.clampToArena(ground(at, 0.05))
        if (boss.entityPos.squaredDistanceTo(spot) > 0.25) flyTo(spot, H.LAND)
    }

    /**
     * Blinzelt an einen neuen Ort (PLAN 2.4): am Start ein kleines Portal, das sich schließt, und die einzige Federwolke,
     * am Ziel ein kurzer Lichtstern.
     */
    private fun blink(entity: HermesEntity, to: Vec3d) {
        val start = entity.entityPos.add(0.0, 1.5, 0.0)
        HermesLook.portal(world, style, start, flat(to.subtract(entity.entityPos)))
        Vfx.feathers(world, style, start, BLINK_FEATHERS, 1.0f, 26)
        entity.setPosition(to)
        HermesLook.star(world, style, to.add(0.0, 1.5, 0.0))
        fight.sound(HhaSounds.BLINK, to, 1.8f, 1f)
    }

    private fun shootBolt(
        kind: Int, from: Vec3d, velocity: Vec3d, damage: Float, life: Int = HermesBoltEntity.MAX_AGE, visual: Boolean = false,
        range: Double = Double.MAX_VALUE, rangeFrom: Vec3d? = null,
    ) {
        val bolt = HhaEntities.HERMES_BOLT.create(world, SpawnReason.EVENT) ?: return
        bolt.kind = kind
        bolt.damage = fight.scaledDamage(damage)
        bolt.homing = 0.0
        bolt.visual = visual
        bolt.maxAge = life
        bolt.maxRange = range
        bolt.rangeFrom = rangeFrom
        bolt.fight = telegraph
        bolt.style = style
        bolt.setOwner(boss)
        bolt.setPosition(from)
        bolt.velocity = velocity
        world.spawnEntity(bolt)
    }

    /**
     * Federfächer: fünf Federn gleichmäßig im Kegel ab [apex] in [direction] (halber Winkel, Reichweite aus
     * [BossTimings.Hermes]), geworfen aus [hand]. Keine Zielsuche; zwischen den Federn ist Platz.
     */
    private fun throwFan(hand: Vec3d, apex: Vec3d, direction: Vec3d) {
        val count = H.FAN_FEATHERS
        val half = Math.toRadians(H.FAN_HALF_ANGLE.toDouble()) * FAN_INSET
        val reach = H.FAN_RANGE.toDouble()
        for (i in 0 until count) {
            val angle = -half + 2.0 * half * i / (count - 1)
            val goal = ground(apex.add(rotate(direction, angle).multiply(reach)), FEATHER_HEIGHT)
            val path = goal.subtract(hand)
            val life = (path.length() / H.FAN_FEATHER_SPEED).toInt() + 2
            // Getroffen wird nur innerhalb des angesagten Kegels: Reichweite ab der Kegelspitze, nicht ab der Hand.
            shootBolt(HermesBoltEntity.FEATHER, hand, path.normalize().multiply(H.FAN_FEATHER_SPEED), Dmg.FEATHER, life, range = reach, rangeFrom = apex)
        }
        fight.sound(HhaSounds.FEATHER_VOLLEY, hand, 2f, 1f)
    }

    /**
     * Bodenwelle: läuft ab [delay] Ticks in [travel] Ticks von Radius [from] bis [to] um [at] (Verlauf wie die
     * Schockwelle: erst schnell, dann langsamer) und trifft jeden einmal, der am Boden steht. Wer springt, entkommt.
     */
    private fun groundWave(at: Vec3d, from: Double, to: Double, delay: Int, travel: Int, damage: Float, effect: Boolean): Sequence<Unit> = sequence {
        wait(delay)
        if (effect) Vfx.shockwave(world, style, at, to.toFloat(), travel, 1.2f)
        val hit = HashSet<UUID>()
        var previous = from
        for (k in 1..travel) {
            val t = k.toDouble() / travel
            val eased = 1.0 - (1.0 - t) * (1.0 - t) * (1.0 - t)
            val radius = max(from, to * eased)
            for (player in fight.targets()) {
                if (player.uuid in hit || !fight.grounded(player)) continue
                val d = flat(player.entityPos.subtract(at)).length()
                if (d >= from - WAVE_BAND && d in (previous - WAVE_BAND)..(radius + WAVE_BAND)) {
                    hit.add(player.uuid)
                    fight.hurt(player, damage, reach = BossDefense.Reach.LOW)
                    Fx.launchPlayer(player, Vec3d(0.0, 0.45, 0.0))
                }
            }
            previous = radius
            yield(Unit)
        }
    }

    /** Erholung nach einer Stoßfolge: bremst, rutscht auf ein Knie (SKID, offen). */
    private fun skid(window: Int = H.SKID_WINDOW): Sequence<Unit> = sequence {
        boss.setPosition(ground(boss.entityPos, 0.05))
        boss.pose = HermesPose.SKID
        Fx.burst(world, ParticleTypes.CLOUD, ground(boss.entityPos, 0.2), 10, 0.5, 0.02)
        fight.markOpen(window)
        wait(window)
    }

    /** Abgefangen: überschlägt sich halb und fängt sich auf einer Hand (STUMBLE, offen). */
    private fun stumble(): Sequence<Unit> = sequence {
        boss.setPosition(ground(boss.entityPos, 0.05))
        boss.pose = HermesPose.STUMBLE
        // Er überschlägt sich: Hier fliegen wirklich Federn.
        Vfx.feathers(world, style, boss.entityPos.add(0.0, 1.2, 0.0), STUMBLE_FEATHERS, 1.2f, 30)
        fight.sound(HhaSounds.IMPACT, boss.entityPos, 2f, 1.5f)
        fight.markOpen(H.STUMBLE_WINDOW)
        wait(H.STUMBLE_WINDOW)
    }

    /** Erschöpfung (Stage IV): kniet, goldene Rune, verwundbar und offen. Am Ende ein Flare, kein Wegstoßen (Plan 3.4). */
    private fun exhausted(ticks: Int): Sequence<Unit> = sequence {
        val spot = fight.clampToArena(ground(boss.entityPos, 0.05), 6.0)
        flyTo(spot, H.LAND)
        boss.pose = HermesPose.KNEEL
        boss.vulnerable = true
        fight.markOpen(ticks)
        fight.hint("hha.hermes.hint.exhausted")
        wait(ticks)
        // In Stage IV ist er nur hier verwundbar.
        boss.vulnerable = false
        boss.pose = HermesPose.ASCEND
        Vfx.flare(world, style, boss.entityPos.add(0.0, 1.5, 0.0), 2.4f, 10)
        flyTo(spot.add(0.0, EXHAUST_LIFT, 0.0), H.EXHAUST_RISE)
    }

    // -------------------------------------------------------------------------------------------------
    // Kurierstoß (Stage I und III) – der Kern: Linien lesen, abfangen
    // -------------------------------------------------------------------------------------------------

    /**
     * Ein Stoß eines Hermes-Körpers entlang der Bahn [from] → [to] mit [BossTimings.Hermes.DASH_SPEED]; trifft, wer in
     * der gezeigten Linie steht, und stößt ihn zur Seite. Der echte Hermes bricht ab, sobald er abgefangen wurde.
     */
    private fun dashTask(entity: HermesEntity, from: Vec3d, to: Vec3d, damage: Float): Sequence<Unit> = sequence {
        entity.pose = HermesPose.DASH
        fight.face(entity, to)
        // Nur die Lichtbahn, keine Schockwelle (Plan 3.4; PLAN 2.4: Kopf am Läufer, Schweif getrennt, am Ende Funken).
        HermesLook.streak(world, style, entity, from, to, HermesLook.ticksFor(from, to, H.DASH_SPEED))
        val way = heading(from, to)
        val side = way.crossProduct(Vec3d(0.0, 1.0, 0.0))
        val length = flat(to.subtract(from)).length()
        val hit = HashSet<UUID>()
        var travelled = 0.0
        var position = from
        while (travelled < length && entity.isAlive && !entity.isRemoved) {
            if (entity === fight.boss && intercepted) break
            val stride = min(H.DASH_SPEED, length - travelled)
            val next = position.add(way.multiply(stride))
            for (player in fight.targets()) {
                if (player.uuid in hit || player.y < from.y - DASH_REACH_Y || !inReach(player)) continue
                if (!Telegraph.inLine(player.entityPos, position, next, H.DASH_WIDTH.toDouble(), player.width * 0.5)) continue
                hit.add(player.uuid)
                fight.hurt(player, damage)
                val sign = if (player.entityPos.subtract(position).dotProduct(side) >= 0.0) 1.0 else -1.0
                Fx.launchPlayer(player, side.multiply(sign * 1.05).add(0.0, 0.4, 0.0))
            }
            entity.setPosition(next)
            position = next
            travelled += stride
            yield(Unit)
        }
    }

    /** Ende der Bahn: durch das Ziel hindurch und [BossTimings.Hermes.DASH_OVERSHOOT] weiter, im Forum. */
    private fun dashEnd(from: Vec3d, player: ServerPlayerEntity): Vec3d {
        var way = heading(from, player.entityPos, facing(boss))
        var to = fight.clampToArena(ground(player.entityPos.add(way.multiply(H.DASH_OVERSHOOT)), DASH_LIFT))
        if (flat(to.subtract(from)).length() < H.DASH_MIN_LENGTH) {
            // Am Rand: quer durch die Mitte statt gegen die Säulen.
            way = heading(from, center, way)
            to = fight.clampToArena(ground(from.add(way.multiply(H.DASH_MIN_LENGTH * 1.5)), DASH_LIFT))
        }
        return to
    }

    /**
     * **Kurierstoß × n:** geduckt (DASH_WINDUP) mit Linie Breite 1,6, W 20 für den ersten und 16 für jeden weiteren
     * Stoß; S 10 und Stoß zur Seite. Nach dem letzten SKID F 30 (offen). Abgefangen: STUMBLE F 40 (offen).
     */
    private fun courierDash(count: Int): Sequence<Unit> = sequence {
        land()
        intercepted = false
        interceptArmed = true
        for (i in 0 until count) {
            val player = nextTarget() ?: break
            val from = ground(boss.entityPos, DASH_LIFT)
            val to = dashEnd(from, player)
            boss.setPosition(from)
            fight.face(boss, to)
            boss.pose = HermesPose.DASH_WINDUP
            val windup = if (i == 0) H.DASH_WINDUP_FIRST else H.DASH_WINDUP
            val line = telegraph.line(ground(from), ground(to), H.DASH_WIDTH, windup, Telegraph.Pace.HEAVY)
            fight.sound(HhaSounds.CHARGE, from, 1.8f, 1f + i * 0.08f)
            wait(line.ticks)
            fight.sound(HhaSounds.DASH, from, 2f, 1f)
            yieldAll(dashTask(boss, from, to, Dmg.DASH))
            if (intercepted) {
                interceptArmed = false
                yieldAll(stumble())
                return@sequence
            }
        }
        interceptArmed = false
        yieldAll(skid())
    }

    // -------------------------------------------------------------------------------------------------
    // Stage I: Der Bote
    // -------------------------------------------------------------------------------------------------

    /**
     * **Federfächer × 2** (FAN): Kegel 30° halb, Reichweite 18, je Wurf eine volle Ansage (W 16, Plan 14 angehoben);
     * 5 Federn S 4 ohne Zielsuche. Danach F 14.
     */
    private fun featherFan(): Sequence<Unit> = sequence {
        val player = nextTarget() ?: return@sequence
        // Wurfplatz: ein gutes Stück vor dem Ziel, zur Mitte hin.
        val back = heading(player.entityPos, center, dir(randomAngle()))
        val spot = fight.clampToArena(ground(player.entityPos.add(rotate(back, (world.random.nextDouble() - 0.5) * 1.2).multiply(FAN_DISTANCE)), 0.05))
        flyTo(spot, H.FAN_MOVE)
        boss.pose = HermesPose.FAN
        var previous = 0
        for (at in intArrayOf(H.FAN_THROW_1, H.FAN_THROW_2)) {
            val target = fight.targets().minByOrNull { it.squaredDistanceTo(boss) } ?: break
            val apex = ground(boss.entityPos)
            val direction = heading(apex, target.entityPos, facing(boss))
            fight.face(boss, apex.add(direction))
            val cone = telegraph.cone(apex, direction, H.FAN_RANGE, H.FAN_HALF_ANGLE, at - previous, Telegraph.Pace.PROJECTILE)
            wait(cone.ticks)
            throwFan(apex.add(0.0, FEATHER_HEIGHT + 0.3, 0.0), apex, direction)
            previous = at
        }
        wait(H.FAN_WINDOW)
    }

    /**
     * **Sturmschlag:** steigt 16 Ticks (ASCEND), Kreis Radius 7 mit W 24 (SLAM); S 14 + Hochwurf, eine Schockwelle
     * und Rütteln 3. Danach die **Bodenwelle** als eigene Ring-Ansage (bis Radius 20, S 6, überspringen), während der
     * Stab steckt: STUCK F 24 (offen).
     */
    private fun stormSlam(): Sequence<Unit> = sequence {
        val player = nextTarget() ?: return@sequence
        val spot = fight.clampToArena(ground(player.entityPos))
        boss.pose = HermesPose.ASCEND
        flyTo(spot.add(0.0, SLAM_HEIGHT, 0.0), H.SLAM_ASCEND)
        boss.pose = HermesPose.SLAM
        val group = telegraph.boss()
        val circle = group.ring(spot, H.SLAM_RADIUS, H.SLAM_WINDUP)
        fight.sound(HhaSounds.RIFT, spot, 1.5f, 1.5f)
        wait(circle.ticks - H.SLAM_DROP)
        flyTo(ground(spot, 0.05), H.SLAM_DROP, smooth = false)
        for (target in circle.hits(fight.targets())) {
            if (!inReach(target)) continue
            fight.hurt(target, Dmg.SLAM)
            Fx.launchPlayer(target, heading(spot, target.entityPos).multiply(0.8).add(0.0, 0.95, 0.0))
        }
        Vfx.shockwave(world, style, spot, H.SLAM_RADIUS, 14, 2f)
        // Beiwerk im Budget: Sprünge im Marmor innerhalb des Kreises (unter der folgenden Wellen-Ansage, die erst
        // außerhalb beginnt) und ein paar echte Bodenblöcke; Ruck nach unten statt Rütteln.
        BossFx.cracks(world, style, spot, H.SLAM_RADIUS * SLAM_CRACK_SHARE, SLAM_CRACKS, SLAM_CRACK_LIFE)
        BossFx.debris(world, spot, SLAM_DEBRIS_RADIUS, SLAM_DEBRIS)
        BossFx.kick(world, spot, SLAM_KICK)
        fight.sound(HhaSounds.IMPACT, spot, 3f, 1f)
        boss.pose = HermesPose.STUCK
        fight.markOpen(H.STUCK_WINDOW)
        val wave = group.ring(spot, H.SLAM_WAVE_RADIUS, H.SLAM_WAVE_WINDUP, inner = H.SLAM_RADIUS)
        fight.runTask(groundWave(spot, H.SLAM_RADIUS.toDouble(), H.SLAM_WAVE_RADIUS.toDouble(), wave.ticks, H.SLAM_WAVE_TRAVEL, Dmg.SLAM_WAVE, effect = true))
        wait(H.STUCK_WINDOW)
    }

    // -------------------------------------------------------------------------------------------------
    // Stage II: Der Dieb
    // -------------------------------------------------------------------------------------------------

    private fun spawnIllusion(pos: Vec3d): HermesEntity {
        val ghost = HhaEntities.HERMES.create(world, SpawnReason.EVENT)!!
        ghost.isIllusion = true
        ghost.stage = boss.stage
        ghost.setPosition(pos)
        world.spawnEntity(ghost)
        fight.illusions.add(ghost)
        return ghost
    }

    private fun vanish(ghost: HermesEntity) {
        if (ghost.isRemoved) return
        HermesLook.dissolve(world, style, ghost.entityPos.add(0.0, 1.5, 0.0))
        ghost.discard()
        fight.illusions.remove(ghost)
    }

    /**
     * **Spiegel-Stoß:** drei Körper (zwei Trugbilder) im Kreis um das Ziel, jeder mit Linie W 30; nur unter dem Echten
     * liegt der goldene Kreis. Echter S 12, Trugbild S 4. Danach spotten die Trugbilder 30 Ticks, der Echte steht in
     * SKID F 30 (offen). Treffer auf den Echten: Trugbilder verpuffen; im Stoß abgefangen: STUMBLE.
     */
    private fun mirrorDash(): Sequence<Unit> = sequence {
        val player = nextTarget() ?: return@sequence
        val aim = ground(player.entityPos)
        val phase = randomAngle()
        val count = H.MIRROR_BODIES
        val slots = List(count) { i ->
            fight.clampToArena(ground(aim.add(dir(phase + i * PI * 2.0 / count).multiply(H.MIRROR_RING)), DASH_LIFT))
        }
        val real = world.random.nextInt(count)
        for (old in fight.illusions) old.discard()
        fight.illusions.clear()
        blink(boss, slots[real])
        val bodies = slots.indices.map { i -> if (i == real) boss else spawnIllusion(slots[i]) }
        // Verrät sich nur durch den goldenen Kreis unter dem Echten.
        Vfx.rune(world, Vfx.Style.GOLD, ground(slots[real], 0.05), 2.0f, H.MIRROR_WINDUP + 6, boss)
        val group = telegraph.boss(pace = Telegraph.Pace.HEAVY)
        val ends = bodies.mapIndexed { i, body ->
            val end = fight.clampToArena(ground(aim.add(heading(slots[i], aim).multiply(MIRROR_OVERSHOOT)), DASH_LIFT))
            body.pose = HermesPose.DASH_WINDUP
            fight.face(body, end)
            group.line(ground(slots[i]), ground(end), H.DASH_WIDTH, H.MIRROR_WINDUP)
            end
        }
        fight.sound(HhaSounds.CHARGE, aim, 2.2f, 1.2f)
        wait(group.ticks)
        fight.sound(HhaSounds.DASH, aim, 3f, 1f)
        intercepted = false
        interceptArmed = true
        for ((i, body) in bodies.withIndex()) {
            if (body === boss) continue
            fight.runTask(sequence {
                yieldAll(dashTask(body, slots[i], ends[i], Dmg.MIRROR_ILLUSION))
                if (!body.isRemoved) {
                    body.pose = HermesPose.TAUNT
                    wait(H.MIRROR_TAUNT)
                    vanish(body)
                }
            })
        }
        yieldAll(dashTask(boss, slots[real], ends[real], Dmg.MIRROR))
        interceptArmed = false
        if (intercepted) yieldAll(stumble()) else yieldAll(skid())
    }

    /**
     * **Diebesgriff** (SNAP): taucht hinter dem Ziel auf, Kreis 2,4 mit W 18; S 6 und die positiven Effekte wandern in
     * die **Beute-Kugel** in seiner Linken (12 s). Wer ihn in der Zeit trifft, holt sie zurück und bekommt Tempo I.
     * Kein Hotbar-Mischen mehr.
     */
    private fun stickyFingers(): Sequence<Unit> = sequence {
        val player = nextTarget() ?: return@sequence
        val spot = ground(player.entityPos)
        val look = flat(player.getRotationVec(1f)).let { if (it.lengthSquared() < 1e-4) heading(center, spot) else it.normalize() }
        val behind = fight.clampToArena(ground(spot.subtract(look.multiply(H.SNAP_BEHIND)), 0.05), 1.5)
        blink(boss, behind)
        fight.face(boss, spot)
        boss.pose = HermesPose.SNAP
        val grab = telegraph.ring(spot, H.SNAP_RADIUS, H.SNAP_WINDUP, players = listOf(player))
        fight.sound(HhaSounds.CHARGE, spot, 1.6f, 1.5f)
        wait(grab.ticks)
        var robbed = false
        for (victim in grab.hits(fight.targets())) {
            steal(victim)
            robbed = true
        }
        Vfx.nova(world, style, spot.add(0.0, 1.0, 0.0), H.SNAP_RADIUS, 8)
        fight.sound(HhaSounds.BLINK, spot, 2f, 0.8f)
        wait(H.SNAP_END - H.SNAP_WINDUP)
        if (robbed) {
            // Er spottet mit der Beute: jetzt umdrehen und zuschlagen.
            boss.pose = HermesPose.TAUNT
            wait(H.LOOT_TAUNT)
        }
    }

    /** Dieb bleibt Dieb: nimmt alle positiven Effekte in die Beute-Kugel. Kein Gegenstand, keine Schnellleiste. */
    private fun steal(player: ServerPlayerEntity) {
        val beneficial = player.statusEffects
            .filter { it.effectType.value().category == StatusEffectCategory.BENEFICIAL }
            .map { StatusEffectInstance(it) }
        for (effect in beneficial) player.removeStatusEffect(effect.effectType)
        fight.takeLoot(player, beneficial)
        fight.hint("hha.hermes.hint.stolen", listOf(player))
        fight.hurt(player, Dmg.SNAP)
    }

    /**
     * **Wegkreuzungen:** drei Wellen mit 2/2/3 Linien (Breite 3) quer durch die Arena, je W 24, 14 Ticks Abstand;
     * S 10. Zwischen die Linien stellen. Danach landet er: F 20.
     */
    private fun crossroads(): Sequence<Unit> = sequence {
        boss.pose = HermesPose.CAST
        flyTo(ground(center, CAST_HEIGHT), CAST_MOVE)
        val group = telegraph.boss()
        val reach = HermesArena.RADIUS - 1.0
        for ((wave, count) in H.CROSS_LINES.withIndex()) {
            val base = world.random.nextDouble() * PI
            val rays = List(count) { i ->
                val offset = dir(base + i * PI / count).multiply(reach)
                ground(center.add(offset)) to ground(center.subtract(offset))
            }
            val lines = rays.map { (a, b) -> group.line(a, b, H.CROSS_WIDTH, H.CROSS_WINDUP) }
            fight.sound(HhaSounds.CHARGE, center, 2f, 0.9f + wave * 0.1f)
            wait(group.ticks)
            // Je Bahn ein flacher Energiebogen (PLAN 2.4 statt Strahl); kaum gebogen, damit er über der Linie bleibt.
            for ((a, b) in rays) BossFx.arc(world, style, a.add(0.0, 0.8, 0.0), b.add(0.0, 0.8, 0.0), CROSS_ARC_BEND, CROSS_ARC_WIDTH, CROSS_ARC_LIFE, strands = 2)
            val struck = LinkedHashSet<ServerPlayerEntity>()
            for (line in lines) for (p in line.hits(fight.targets())) struck.add(p)
            for (p in struck) fight.hurt(p, Dmg.CROSS)
            fight.sound(HhaSounds.DASH, center, 2.5f, 1.3f)
            if (wave < H.CROSS_LINES.size - 1) wait(H.CROSS_GAP)
        }
        land()
        wait(H.CROSS_WINDOW)
    }

    /**
     * **Herma-Sprung:** drei Herma-Steine (30 Leben) steigen auf; Hermes springt dreimal von Stein zu Stein. Der
     * Zielstein glüht 14 Ticks ([HermaEntity.lit]), dann der Sprung; oben wirft er einen Fächer (wie oben). Wer den
     * glühenden Stein zerschlägt, bevor er ankommt, lässt ihn abstürzen: STAGGER F 80 (offen). Danach zerfallen die
     * Steine von selbst. Keine Unverwundbarkeit, keine Strafe.
     */
    private fun hermaLeap(): Sequence<Unit> = sequence {
        fight.hint("hha.hermes.hint.stones")
        land()
        val phase = randomAngle()
        val count = H.HERMA_STONES
        val stones = List(count) { i ->
            val spot = ground(center.add(dir(phase + i * PI * 2.0 / count).multiply(H.HERMA_RING)))
            val stone = HhaEntities.HERMA.create(world, SpawnReason.EVENT)!!
            stone.setPosition(spot)
            fight.face(stone, center)
            world.spawnEntity(stone)
            fight.scaleMinion(stone, HermesFight.HERMA_HEALTH)
            fight.stones.add(stone)
            stone
        }
        fight.sound(SoundEvents.BLOCK_STONE_PLACE, center, 2f, 0.6f)
        wait(H.HERMA_RISE)

        val order = stones.shuffled(java.util.Random(world.random.nextLong()))
        var perch: HermaEntity? = null
        var crash: Vec3d? = null
        for (jump in 0 until H.HERMA_JUMPS) {
            val target = order.firstOrNull { it !== perch && it.isAlive && !it.isRemoved && it in fight.stones } ?: break
            leapTarget = target
            leapBroken = false
            target.lit = true
            fight.sound(HhaSounds.CHIME, target.entityPos, 2f, 1.2f)
            fight.face(boss, target.entityPos)
            boss.pose = HermesPose.IDLE
            val waited = waitUnless(H.HERMA_GLOW) { leapBroken }
            if (waited) {
                boss.pose = HermesPose.ASCEND
                val from = boss.entityPos
                val top = target.entityPos.add(0.0, HERMA_TOP, 0.0)
                for (i in 1..H.HERMA_LEAP) {
                    if (leapBroken) break
                    val t = i.toDouble() / H.HERMA_LEAP
                    boss.setPosition(from.lerp(top, t).add(0.0, LEAP_ARC * 4.0 * t * (1.0 - t), 0.0))
                    yield(Unit)
                }
            }
            if (leapBroken) {
                crash = ground(target.entityPos)
                break
            }
            target.lit = false
            leapTarget = null
            perch = target
            boss.setPosition(target.entityPos.add(0.0, HERMA_TOP, 0.0))
            // Oben angekommen: ein Fächer vom Stein herab.
            val player = nextTarget() ?: break
            boss.pose = HermesPose.FAN
            val apex = ground(target.entityPos)
            val direction = heading(apex, player.entityPos, heading(apex, center))
            fight.face(boss, apex.add(direction))
            val cone = telegraph.cone(apex, direction, H.FAN_RANGE, H.FAN_HALF_ANGLE, H.FAN_THROW_1, Telegraph.Pace.PROJECTILE)
            wait(cone.ticks)
            throwFan(boss.entityPos.add(0.0, 1.6, 0.0), apex, direction)
            wait(HERMA_THROW_RECOVER)
            // Der Stein unter ihm ist weg: Er springt einfach ab.
            if (!target.isAlive || target.isRemoved) break
        }
        leapTarget = null
        for (stone in fight.stones) stone.lit = false
        val fall = crash
        if (fall != null) {
            // Abgestürzt: Er taumelt am Boden, offen für einen Konter.
            boss.pose = HermesPose.FALL
            flyTo(ground(fall, 0.05), H.HERMA_CRASH, smooth = false)
            // Absturz auf den Marmor: Federn stieben, ein paar Bodenblöcke springen.
            Vfx.feathers(world, style, boss.entityPos.add(0.0, 1.0, 0.0), STUMBLE_FEATHERS, 1.4f, 30)
            BossFx.debris(world, ground(fall), CRASH_DEBRIS_RADIUS, CRASH_DEBRIS)
            fight.sound(HhaSounds.IMPACT, fall, 3f, 1.4f)
            boss.pose = HermesPose.STAGGER
            fight.hint("hha.hermes.hint.stagger")
            fight.markOpen(H.HERMA_STAGGER)
            fight.runTask(crumble())
            wait(H.HERMA_STAGGER)
        } else {
            val base = perch?.entityPos ?: boss.entityPos
            land(base.add(heading(base, center).multiply(2.0)))
            fight.runTask(crumble())
        }
    }

    /** Die übrigen Steine zerfallen nacheinander (keine Entladung, kein Schaden). */
    private fun crumble(): Sequence<Unit> = sequence {
        for (stone in fight.stones.toList()) {
            wait(H.HERMA_DECAY)
            if (stone.isRemoved) continue
            // Der Stein zerspringt in echte Marmorbrocken (PLAN 2.4, `core.debris`).
            BossFx.debris(world, ground(stone.entityPos), HERMA_DEBRIS_RADIUS, HERMA_DEBRIS)
            Fx.burst(world, ParticleTypes.POOF, stone.entityPos.add(0.0, 1.6, 0.0), 6, 0.6, 0.04)
            fight.sound(SoundEvents.BLOCK_STONE_BREAK, stone.entityPos, 1.6f, 0.8f)
            stone.discard()
            fight.stones.remove(stone)
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Stage III: Der Seelenführer
    // -------------------------------------------------------------------------------------------------

    /** Geschwungene Bahn vom Stab zum Rand: Radius wächst gleichmäßig, der Winkel dreht um [bend]. */
    private fun lanePath(staff: Vec3d, angle: Double, bend: Double): List<Vec3d> {
        val end = HermesArena.RADIUS - H.TWINS_EDGE.toDouble()
        return List(TWINS_SEGMENTS + 1) { j ->
            val t = j.toDouble() / TWINS_SEGMENTS
            val r = TWINS_START + (end - TWINS_START) * t
            ground(staff.add(dir(angle + bend * t).multiply(r)))
        }
    }

    /** Punkt auf der Bahn bei [progress] 0..1. */
    private fun pointAt(lane: List<Vec3d>, progress: Double): Vec3d {
        val x = progress.coerceIn(0.0, 1.0) * (lane.size - 1)
        val i = x.toInt().coerceAtMost(lane.size - 2)
        return lane[i].lerp(lane[i + 1], x - i)
    }

    private fun laneLines(group: Telegraph.Fight.Group, lane: List<Vec3d>, ticks: Int) {
        for ((a, b) in lane.zipWithNext()) group.line(a, b, H.TWINS_WIDTH, ticks, Telegraph.Pace.AREA)
    }

    /** Bewegt den Schlangenkopf auf der Bahn und trifft, wer in der Bahn am Kopf steht (einmal je Durchlauf). */
    private fun moveSerpent(serpent: SerpentEntity, lane: List<Vec3d>, progress: Double, ahead: Double, hit: MutableSet<UUID>) {
        val head = pointAt(lane, progress).add(0.0, SERPENT_HEIGHT, 0.0)
        serpent.setPosition(head)
        fight.face(serpent, pointAt(lane, (progress + ahead).coerceIn(0.0, 1.0)).let { if (it.squaredDistanceTo(head) < 0.01) head.add(flat(head.subtract(center))) else it })
        for (player in fight.targets()) {
            if (player.uuid in hit || !inReach(player)) continue
            if (flat(player.entityPos.subtract(head)).length() > H.TWINS_WIDTH * 0.5 + SERPENT_HEAD) continue
            if (lane.zipWithNext().none { (a, b) -> Telegraph.inLine(player.entityPos, a, b, H.TWINS_WIDTH.toDouble(), player.width * 0.5) }) continue
            hit.add(player.uuid)
            fight.hurt(player, Dmg.TWINS)
            fight.sound(HhaSounds.BITE, player.entityPos, 1.6f, 1.1f)
            Fx.launchPlayer(player, heading(head, player.entityPos, flat(head.subtract(center)).normalize()).multiply(0.9).add(0.0, 0.45, 0.0))
        }
    }

    /**
     * **Die Zwillinge** (PLANT): Hermes rammt den Stab in die Mitte (20 Ticks). Zwei geschwungene Bahnen (Breite 2,4)
     * von der Mitte zum Rand, W 30; die Schlangen schnellen in 30 Ticks hinaus und in 20 zurück, S 12. Der zweite
     * Durchgang ist gespiegelt. Er kanalisiert die ganze Zeit in der Mitte und ist verwundbar.
     * Keine Lebensleiste, kein Gift, keine Verfolgung, keine Trefferzonen (Plan 4.1).
     */
    private fun twins(): Sequence<Unit> = sequence {
        val toward = dir(randomAngle())
        val staff = ground(center)
        flyTo(ground(center.subtract(toward.multiply(STAFF_REACH)), 0.05), CAST_MOVE)
        fight.face(boss, staff)
        boss.pose = HermesPose.PLANT
        wait(H.TWINS_PLANT_STRIKE)
        Fx.burst(world, ParticleTypes.CLOUD, staff.add(0.0, 0.2, 0.0), 12, 0.5, 0.04)
        BossFx.cracks(world, style, staff, PLANT_CRACK_RADIUS, PLANT_CRACKS, PLANT_CRACK_LIFE)
        fight.sound(HhaSounds.IMPACT, staff, 2.4f, 1.2f)
        wait(H.TWINS_PLANT - H.TWINS_PLANT_STRIKE)
        fight.hint("hha.hermes.hint.serpents")
        val base = randomAngle()
        for (pass in 0 until H.TWINS_PASSES) {
            if (fight.boss == null) break
            val bend = if (pass % 2 == 0) TWINS_BEND else -TWINS_BEND
            val lanes = List(2) { i -> lanePath(staff, base + i * PI, bend) }
            val group = telegraph.boss(pace = Telegraph.Pace.AREA)
            for (lane in lanes) laneLines(group, lane, H.TWINS_WINDUP)
            fight.sound(HhaSounds.HISS, staff, 2.2f, 1f)
            wait(group.ticks)

            // Die Bahnen bleiben angesagt, bis die Schlangen zurück am Stab sind.
            val travel = H.TWINS_OUT + H.TWINS_BACK
            for (lane in lanes) laneLines(group, lane, travel)
            val serpents = fight.spawnSerpents(staff.add(0.0, SERPENT_HEIGHT, 0.0))
            // Kielwasser auf genau den Stützpunkten, auf denen moveSerpent die Köpfe bewegt.
            for (lane in lanes) HermesLook.lane(world, style, lane, H.TWINS_OUT, H.TWINS_BACK)
            fight.sound(HhaSounds.ROAR, staff, 2.4f, 1.3f)
            val hits = List(serpents.size) { HashSet<UUID>() }
            for (k in 1..H.TWINS_OUT) {
                for ((i, serpent) in serpents.withIndex()) moveSerpent(serpent, lanes[i], k.toDouble() / H.TWINS_OUT, 0.05, hits[i])
                yield(Unit)
            }
            for (h in hits) h.clear()
            for (k in 1..H.TWINS_BACK) {
                for ((i, serpent) in serpents.withIndex()) moveSerpent(serpent, lanes[i], 1.0 - k.toDouble() / H.TWINS_BACK, -0.05, hits[i])
                yield(Unit)
            }
            fight.dismissSerpents(serpents)
            if (pass < H.TWINS_PASSES - 1) wait(H.TWINS_PASS_GAP)
        }
    }

    /**
     * **Seelensäulen:** 4 Wellen mit je einem Kreis Radius 3 je Spieler, W 20, 14 Ticks Abstand; die letzten zwei mit
     * Vorhalt. S 11 + Hochwurf, trifft auch in der Luft (`SKY`). Danach landet er: F 20.
     */
    private fun soulPillars(): Sequence<Unit> = sequence {
        boss.pose = HermesPose.CAST
        flyTo(ground(center, CAST_HEIGHT), CAST_MOVE)
        val group = telegraph.boss()
        for (wave in 0 until H.PILLAR_COUNT) {
            val lead = wave >= H.PILLAR_COUNT - H.PILLAR_LEAD_WAVES
            for ((index, player) in fight.targets().withIndex()) {
                var spot = ground(player.entityPos)
                if (lead) {
                    val ahead = fight.motion(player).multiply(H.PILLAR_WINDUP.toDouble())
                    spot = spot.add(if (ahead.length() > H.PILLAR_LEAD_MAX) ahead.normalize().multiply(H.PILLAR_LEAD_MAX) else ahead)
                }
                val ring = group.ring(fight.clampToArena(spot, 2.0), H.PILLAR_RADIUS, H.PILLAR_WINDUP)
                fight.runTask(pillarStrike(ring, loud = index == 0))
            }
            fight.sound(HhaSounds.CHARGE, center, 1.6f, 1.1f + wave * 0.08f)
            if (wave < H.PILLAR_COUNT - 1) wait(H.PILLAR_GAP)
        }
        land()
        wait(group.ticks)
        wait(H.PILLAR_WINDOW)
    }

    private fun pillarStrike(ring: Telegraph.Ring, loud: Boolean): Sequence<Unit> = sequence {
        wait(ring.ticks)
        Vfx.pillar(world, style, ring.center, 2.2f, 14f, 18)
        if (loud) fight.sound(HhaSounds.SOUL, ring.center, 1.6f, 1.2f)
        for (target in ring.hits(fight.targets())) {
            fight.hurt(target, Dmg.PILLAR, magic = true, reach = BossDefense.Reach.SKY)
            Fx.launchPlayer(target, Vec3d(0.0, 0.9, 0.0))
        }
    }

    /**
     * **Lanzenreihe:** je Spieler 8 Kreise Radius 1,6 in einer Reihe auf ihn zu, je W 16, 6 Ticks versetzt; die Lanze
     * fällt genau mit dem Ende der Ansage. S 9. Quer zur Reihe laufen. Danach landet er: F 20.
     */
    private fun lanceRow(): Sequence<Unit> = sequence {
        boss.pose = HermesPose.THROW
        flyTo(fight.clampToArena(ground(center.add(fight.randomHorizontal(LANCE_OFFSET)), LANCE_HOVER)), CAST_MOVE)
        val group = telegraph.boss(pace = Telegraph.Pace.PROJECTILE)
        val from = ground(boss.entityPos)
        val rows = fight.targets().map { p ->
            val way = heading(from, p.entityPos, dir(randomAngle()))
            ground(p.entityPos).subtract(way.multiply(H.LANCE_LEAD_IN)) to way
        }
        fight.sound(HhaSounds.SOUL, boss.entityPos, 2f, 0.9f)
        for (i in 0 until H.LANCE_COUNT) {
            for ((row, pair) in rows.withIndex()) {
                val (start, way) = pair
                val spot = start.add(way.multiply(i * H.LANCE_SPACING))
                if (flat(spot.subtract(center)).length() > HermesArena.RADIUS - 1.0) continue
                val ring = group.ring(spot, H.LANCE_RADIUS, H.LANCE_WINDUP)
                fight.runTask(lanceStrike(ring, loud = row == 0))
            }
            if (i < H.LANCE_COUNT - 1) wait(H.LANCE_GAP)
        }
        land()
        wait(group.ticks)
        wait(H.LANCE_WINDOW)
    }

    private fun lanceStrike(ring: Telegraph.Ring, loud: Boolean): Sequence<Unit> = sequence {
        wait(ring.ticks - H.LANCE_FALL)
        val owner = fight.boss ?: return@sequence
        // Die Lanze ist nur das Bild; getroffen wird, wer im Kreis steht.
        val origin = ring.center.add(heading(ring.center, owner.entityPos).multiply(LANCE_SLANT)).add(0.0, LANCE_DROP_HEIGHT, 0.0)
        shootBolt(HermesBoltEntity.LANCE, origin, ring.center.subtract(origin).multiply(1.0 / H.LANCE_FALL), 0f, H.LANCE_FALL + 4, visual = true)
        if (loud) fight.sound(HhaSounds.SPEAR, ring.center, 1.4f, 0.9f + world.random.nextFloat() * 0.25f)
        wait(H.LANCE_FALL)
        for (target in ring.hits(fight.targets())) fight.hurt(target, Dmg.LANCE, magic = true)
    }

    /**
     * Ansagezeit für eine saalweite Gefahr mit sicherem Band zwischen [safeInner] und [safeOuter] (Abstand zur Mitte):
     * mindestens [base], sonst so lange, dass der am schlechtesten stehende Spieler das Band zu Fuß erreicht
     * (Reaktionszeit + Weg bei `ESCAPE_SPEED`, 10 % Umweg), höchstens `ESCAPE_MAX`.
     */
    private fun reachTicks(base: Int, safeInner: Double, safeOuter: Double): Int {
        val walk = fight.targets().maxOfOrNull { p ->
            val d = kotlin.math.hypot(p.x - center.x, p.z - center.z)
            when {
                d < safeInner + 0.6 -> safeInner + 0.6 - d
                d > safeOuter - 0.6 -> d - (safeOuter - 0.6)
                else -> 0.0
            }
        } ?: 0.0
        val needed = BossTimings.Min.REACTION + kotlin.math.ceil(walk * 1.1 / BossTimings.Min.ESCAPE_SPEED).toInt()
        return max(base, needed).coerceAtMost(BossTimings.Min.ESCAPE_MAX)
    }

    /** Goldener Rand um einen sicheren Bereich, der kein voller Kreis ist (Plan: „sicherer Bereich mit goldenem Rand“). */
    private fun goldRim(group: Telegraph.Fight.Group, radius: Double, ticks: Int) {
        // Ein schmales goldenes Band als Grenze; Trefferfläche bleibt allein die Gefahr-Form der Gruppe.
        group.safeRing(center, (radius + RIM_WIDTH).toFloat(), radius.toFloat(), ticks)
    }

    /**
     * **Styx-Flut:** zwei Phasen (erst der Rand, dann die Mitte), W 40 (Plan 30, saalweit angehoben); die Flut in
     * SOUL, der sichere Bereich gold (Phase 1 als goldene Fläche, Phase 2 mit goldenem Rand). S 7 alle 10 Ticks bis
     * Körperhöhe (`BODY`, Überspringen hilft nicht). Er schwebt darüber.
     */
    private fun styx(): Sequence<Unit> = sequence {
        boss.pose = HermesPose.CAST
        flyTo(ground(center, STYX_HOVER), CAST_MOVE)
        fight.hint("hha.hermes.hint.styx")
        fight.sound(HhaSounds.FLOOD, center, 3.5f, 1f)
        val outer = HermesArena.RADIUS.toFloat()
        for (phase in 0 until H.STYX_PHASES) {
            val group = telegraph.boss(pace = Telegraph.Pace.ROOM)
            val rim = phase % 2 == 0
            // Jeder muss den goldenen Bereich zu Fuß erreichen können: Wer weit draußen steht, bekommt mehr Zeit.
            val ticks = if (rim) reachTicks(H.STYX_WINDUP, 0.0, H.STYX_SAFE_RADIUS.toDouble())
            else reachTicks(H.STYX_WINDUP, H.STYX_CENTER_RADIUS.toDouble(), outer.toDouble())
            val danger = if (rim) {
                group.safe(center, H.STYX_SAFE_RADIUS - 0.4f, ticks)
                group.ring(center, outer, ticks, inner = H.STYX_SAFE_RADIUS)
            } else {
                goldRim(group, H.STYX_CENTER_RADIUS + 0.6, ticks)
                group.ring(center, H.STYX_CENTER_RADIUS, ticks)
            }
            wait(group.ticks)
            fight.sound(HhaSounds.SOUL, center, 2f, 0.7f)
            Vfx.flood(world, style, center, danger.radius.toFloat(), danger.inner.toFloat(), H.STYX_FLOOD)
            group.hold(H.STYX_FLOOD)
            for (tick in 0 until H.STYX_FLOOD) {
                if (tick % H.STYX_TICK == 0) {
                    for (player in danger.hits(fight.targets())) fight.hurt(player, Dmg.STYX, magic = true)
                }
                yield(Unit)
            }
            group.end()
            if (phase < H.STYX_PHASES - 1) wait(H.STYX_GAP)
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Stage IV: Der Götterbote — nur in der Erschöpfung verwundbar
    // -------------------------------------------------------------------------------------------------

    /**
     * **Sturzflug × 3** (DIVE): Kreis Radius 5, W 16 Ausholen + 3 Sturz; S 13 (`SKY`) und eine kleine Bodenwelle bis 9 (mit
     * dem Kreis angesagt; sie läuft los, wenn ihr Ring endet; überspringen). Danach Erschöpfung (KNEEL, goldene Rune).
     */
    private fun dives(exhaust: Int): Sequence<Unit> = sequence {
        val group = telegraph.boss()
        for (n in 0 until H.DIVE_COUNT) {
            val player = nextTarget() ?: break
            val spot = fight.clampToArena(ground(player.entityPos))
            boss.pose = HermesPose.ASCEND
            flyTo(spot.add(0.0, DIVE_HEIGHT, 0.0), H.DIVE_RISE)
            boss.pose = HermesPose.DIVE
            val circle = group.ring(spot, H.DIVE_RADIUS, H.DIVE_WINDUP + H.DIVE_DROP)
            // Der Wellenring endet, wenn die Welle losläuft (frühestens beim Einschlag): Füllung und Aufblitzen heißen
            // überall „jetzt trifft es". Der Ring hat Radius 9 und damit mindestens die Flächen-Mindestdauer.
            val wave = group.ring(spot, H.DIVE_WAVE_RADIUS, H.DIVE_WINDUP + H.DIVE_DROP, inner = H.DIVE_RADIUS)
            fight.sound(HhaSounds.CHARGE, spot, 2f, 1.3f)
            wait(circle.ticks - H.DIVE_DROP)
            HermesLook.streak(world, style, boss, boss.entityPos, ground(spot, 0.1), H.DIVE_DROP)
            flyTo(ground(spot, 0.1), H.DIVE_DROP, smooth = false)
            for (target in circle.hits(fight.targets())) {
                fight.hurt(target, Dmg.DIVE, reach = BossDefense.Reach.SKY)
                Fx.launchPlayer(target, heading(spot, target.entityPos).multiply(1.0).add(0.0, 0.7, 0.0))
            }
            // Eine Schockwelle trägt Einschlag und Bodenwelle; Rütteln entfällt, weil der Wellenring noch angesagt ist.
            Vfx.shockwave(world, style, spot, H.DIVE_WAVE_RADIUS, H.DIVE_WAVE_TRAVEL, 2f)
            BossFx.debris(world, spot, IMPACT_DEBRIS_RADIUS, IMPACT_DEBRIS)
            fight.sound(HhaSounds.IMPACT, spot, 3f, 1.1f)
            fight.runTask(groundWave(spot, H.DIVE_RADIUS.toDouble(), H.DIVE_WAVE_RADIUS.toDouble(), (wave.ticks - circle.ticks).coerceAtLeast(0), H.DIVE_WAVE_TRAVEL, Dmg.DIVE_WAVE, effect = false))
            wait(H.DIVE_GAP)
        }
        yieldAll(exhausted(exhaust))
    }

    /**
     * **Speere des Olymp × 2:** je Spieler ein Speer mit Kreis Radius 8, W 50 und dann 40; S 22 (`SKY`), eine Schockwelle und
     * Rütteln 4. Danach je eine Bodenwelle als eigene Ring-Ansage (bis 18, S 7). Danach Erschöpfung.
     */
    private fun skySpears(): Sequence<Unit> = sequence {
        boss.pose = HermesPose.CAST
        flyTo(ground(center, SPEAR_HOVER), CAST_MOVE)
        fight.hint("hha.hermes.hint.spear")
        val group = telegraph.boss()
        for (windup in intArrayOf(H.SPEAR_WINDUP_1, H.SPEAR_WINDUP_2)) {
            val spots = fight.targets().map { fight.clampToArena(ground(it.entityPos)) }
            if (spots.isEmpty()) break
            val circles = spots.map { spot ->
                // Riss in der Wolkendecke, dann der Speer (PLAN 2.4): vor dem Riss nichts (die Ansage gehört dem
                // Telegraph); der Strahl trifft genau `delay + SKY_IMPACT_AFTER` = windup Ticks nach diesem Aufruf.
                BossFx.skyStrike(world, style, spot, SPEAR_STRIKE_RADIUS, SPEAR_STRIKE_HEIGHT, (windup - SKY_IMPACT_AFTER).coerceAtLeast(0))
                group.ring(spot, H.SPEAR_RADIUS, windup)
            }
            fight.sound(HhaSounds.RIFT, center, 2.6f, 0.7f)
            wait(circles.maxOf { it.ticks })
            for (circle in circles) {
                for (target in circle.hits(fight.targets())) {
                    fight.hurt(target, Dmg.SPEAR, reach = BossDefense.Reach.SKY)
                    Fx.launchPlayer(target, heading(circle.center, target.entityPos).multiply(1.1).add(0.0, 0.8, 0.0))
                }
                // Der Flächeneffekt ist der Himmelsschlag (impact); dazu vier echte Marmorblöcke.
                BossFx.debris(world, circle.center, IMPACT_DEBRIS_RADIUS, IMPACT_DEBRIS)
            }
            BossFx.kick(world, spots.first(), SPEAR_KICK)
            fight.sound(HhaSounds.IMPACT, spots.first(), 4f, 0.7f)
            for (circle in circles) {
                val wave = group.ring(circle.center, H.SPEAR_WAVE_RADIUS, H.SPEAR_WAVE_WINDUP, inner = H.SPEAR_RADIUS)
                fight.runTask(groundWave(circle.center, H.SPEAR_RADIUS.toDouble(), H.SPEAR_WAVE_RADIUS.toDouble(), wave.ticks, H.SPEAR_WAVE_TRAVEL, Dmg.SPEAR_WAVE, effect = true))
            }
        }
        wait(group.ticks + H.SPEAR_WAVE_TRAVEL)
        yieldAll(exhausted(H.SPEAR_EXHAUST))
    }

    /**
     * **Letztes Wort:** zwei gegenläufige Strahlen aus der Mitte, Anlauf W 20 (Plan 10, angehoben), dann 100 Ticks; S 9
     * am Boden, überspringen (Doppelsprung). Danach Erschöpfung.
     */
    private fun lastWord(exhaust: Int): Sequence<Unit> = sequence {
        boss.pose = HermesPose.CAST
        flyTo(ground(center, FINAL_HOVER), CAST_MOVE)
        fight.hint("hha.hermes.hint.jump")
        val length = HermesArena.RADIUS - 1.0
        val origin = ground(center, BEAM_HEIGHT)
        val base = randomAngle()
        val group = telegraph.boss(pace = Telegraph.Pace.AREA)
        for (i in 0..1) group.line(ground(center), ground(center.add(dir(base + i * PI).multiply(length))), H.FINAL_WIDTH, H.FINAL_WINDUP)
        fight.sound(HhaSounds.RIFT, center, 3f, 1.4f)
        wait(group.ticks)
        group.hold(H.FINAL_SWEEP)
        val cooldown = HashMap<UUID, Int>()
        for (tick in 0 until H.FINAL_SWEEP) {
            val turn = H.FINAL_TURN * tick + H.FINAL_TURN_GAIN * tick * tick
            // Gegenläufig: einer im, einer gegen den Uhrzeigersinn.
            for (angle in doubleArrayOf(base + turn, base + PI - turn)) {
                val tip = origin.add(dir(angle).multiply(length))
                if (tick % 2 == 0) Vfx.beam(world, style, origin, tip, 0.5f, 3)
                for (player in fight.targets()) {
                    if (!fight.grounded(player) || (cooldown[player.uuid] ?: 0) > tick) continue
                    if (!Telegraph.inLine(player.entityPos, ground(origin), ground(tip), H.FINAL_WIDTH.toDouble(), player.width * 0.5)) continue
                    cooldown[player.uuid] = tick + FINAL_COOLDOWN
                    fight.hurt(player, Dmg.FINAL, magic = true, reach = BossDefense.Reach.LOW)
                    Fx.launchPlayer(player, Vec3d(0.0, 0.6, 0.0))
                }
            }
            if (tick % 12 == 0) fight.sound(HhaSounds.WHOOSH, center, 2f, 1.4f)
            yield(Unit)
        }
        group.end()
        yieldAll(exhausted(exhaust))
    }

    /** Grundschaden S (Plan 4.2), vor [HermesFight.DAMAGE_FACTOR], `hermes_damage` und Spielerzahl. */
    private object Dmg {
        const val DASH = 10f
        const val FEATHER = 4f
        const val SLAM = 14f
        const val SLAM_WAVE = 6f
        const val MIRROR = 12f
        const val MIRROR_ILLUSION = 4f
        const val SNAP = 6f
        const val CROSS = 10f
        const val TWINS = 12f
        const val PILLAR = 11f
        const val LANCE = 9f
        const val STYX = 7f
        const val DIVE = 13f
        const val DIVE_WAVE = 5f
        const val SPEAR = 22f
        const val SPEAR_WAVE = 7f
        const val FINAL = 9f

        /** Konter „Abfangen in der Luft“ (PLAN 4.3). */
        const val COUNTER = 12f
    }

    private companion object {
        val H = BossTimings.Hermes

        /** Abgefangen im Kurierstoß: der Treffer zählt so viel mehr. */
        const val INTERCEPT_BONUS = 1.5f

        // Geometrie (Blöcke), keine Zeiten.
        const val DASH_LIFT = 0.4
        const val DASH_REACH_Y = 2.5
        const val MIRROR_OVERSHOOT = 10.0
        const val FAN_DISTANCE = 11.0
        const val FAN_INSET = 0.9
        const val FEATHER_HEIGHT = 1.1
        const val SLAM_HEIGHT = 10.0
        const val WAVE_BAND = 1.2
        const val CAST_HEIGHT = 3.0
        const val CAST_MOVE = 12
        const val HERMA_TOP = 4.0
        const val LEAP_ARC = 3.0
        const val HERMA_THROW_RECOVER = 6
        const val STAFF_REACH = 0.9
        const val TWINS_START = 1.6
        const val TWINS_SEGMENTS = 5
        const val TWINS_BEND = 1.15
        const val SERPENT_HEIGHT = 0.35
        const val SERPENT_HEAD = 0.7
        const val LANCE_OFFSET = 8.0
        const val LANCE_HOVER = 6.0
        const val LANCE_SLANT = 4.0
        const val LANCE_DROP_HEIGHT = 24.0
        const val RIM_WIDTH = 0.8f
        const val STYX_HOVER = 9.0
        const val DIVE_HEIGHT = 16.0
        const val SPEAR_HOVER = 12.0
        const val FINAL_HOVER = 1.1
        const val BEAM_HEIGHT = 1.0
        const val FINAL_COOLDOWN = 10
        const val EXHAUST_LIFT = 3.0

        // Überarbeitung 3 (P07): reine Darstellung (Anzahl, Größen, Ruck-Winkel). Keine Zeit, keine Trefferfläche.
        const val BLINK_FEATHERS = 8
        const val STUMBLE_FEATHERS = 10
        const val SLAM_CRACK_SHARE = 0.85f
        const val SLAM_CRACKS = 5
        const val SLAM_CRACK_LIFE = 50
        const val SLAM_DEBRIS_RADIUS = 3.2f
        const val SLAM_DEBRIS = 6
        /** Ruck nach unten (Grad) beim Sturmschlag; ersetzt `hitShake(3)`. */
        const val SLAM_KICK = 1.8f
        const val CROSS_ARC_BEND = 0.05f
        const val CROSS_ARC_WIDTH = 0.8f
        const val CROSS_ARC_LIFE = 9
        const val CRASH_DEBRIS_RADIUS = 2.2f
        const val CRASH_DEBRIS = 4
        const val HERMA_DEBRIS_RADIUS = 1.4f
        const val HERMA_DEBRIS = 5
        const val PLANT_CRACK_RADIUS = 2.4f
        const val PLANT_CRACKS = 4
        const val PLANT_CRACK_LIFE = 50
        /** PLAN 2.4: „4 Marmorblöcke“ bei Sturzflug und Speer. */
        const val IMPACT_DEBRIS = 4
        const val IMPACT_DEBRIS_RADIUS = 3.0f
        const val SPEAR_STRIKE_RADIUS = 2.2f
        const val SPEAR_STRIKE_HEIGHT = 40f
        /** `SkyStrikeFx.IMPACT_AFTER` (P03): Riss + Strahl brauchen so viele Ticks nach `delay`. */
        const val SKY_IMPACT_AFTER = 6
        /** Ruck nach unten (Grad) beim Einschlag der Speere; ersetzt `hitShake(4)` (PLAN 2.4: 2°). */
        const val SPEAR_KICK = 2f
    }
}

