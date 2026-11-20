package dev.henny.hha.client.vfx.fx.boss

import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.model.KampeAnchors
import dev.henny.hha.entity.boss.SubTargetEntity
import dev.henny.hha.logic.boss.SubTarget
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxClock
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMeshes
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.client.vfx.fx.FxRegistry
import dev.henny.hha.client.vfx.lerpColor
import dev.henny.hha.client.vfx.withAlpha
import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Client-Effekte von P15 (PLAN 2.12): Kampe (`kampe.*`) und die Gegner des Olymp (`mob.*`). Jede Klasse ist genau ein
 * Effekt; Zeiten und Größen sind benannte Konstanten. Gezeichnet wird nur über [FxDraw] (weiche Bänder, Flächen,
 * glatte Meshes), ohne Zeitstufen und ohne Blinken. Farben kommen aus der Palette des Aufrufs (im Kampf die
 * Gefahrenpalette); Gold zeigt nur `kampe.daze` (benommen = offen), weil der Server ihn mit `GOLD` schickt.
 *
 * | ID | Server-Stelle | Kodierung |
 * | --- | --- | --- |
 * | `kampe.venom` | Beginn des Giftfächers (Ausholen) | entity = Kampe, pos = Maul (Schätzung), life = Ausholen |
 * | `kampe.spit` | Freigabe der Geschosse | entity = Kampe, pos = Mündung, dir = Zielrichtung, a = Reichweite, b = halbe Streuung (°), c = Zahl |
 * | `kampe.sweep` | Treffer des Schwanzfegers (Flächeneffekt) | pos = Boden unter ihr, dir = nach hinten, a = Radius, b = halber Winkel (°) |
 * | `kampe.daze` | Beginn „benommen" | entity = Kampe, pos = über dem Kopf (Schätzung), life = Dauer |
 * | `kampe.shackle_glow` | Schloss wird golden (P22) | entity = Schloss-Wesen, pos = Sockel, dir = Kettenende am Titanen (Punkt), life = Höchstdauer |
 * | `kampe.titan_jolt` | Schloss gesprengt (P22) | pos = Kettenende am Titanen, dir = Brustmitte (Punkt), a = halber Schulterabstand |
 * | `mob.griffin_land` | Landung des Sturzflugs | pos = Boden, a = Radius (≤ 2), life ≤ 10 |
 * | `mob.steam` | Dampfstoß der Goldenen Wächterin | pos = Düse, dir = Richtung, a = Reichweite, b = halber Winkel (°) |
 */
object KampeFx {

    fun init() {
        FxRegistry.register("kampe.venom") { VenomChargeFx(it) }
        FxRegistry.register("kampe.spit") { SpitFx(it) }
        FxRegistry.register("kampe.sweep") { SweepFx(it) }
        FxRegistry.register("kampe.daze") { DazeFx(it) }
        FxRegistry.register("kampe.shackle_glow") { ShackleGlowFx(it) }
        FxRegistry.register("kampe.titan_jolt") { TitanJoltFx(it) }
        FxRegistry.register("mob.griffin_land") { GriffinLandFx(it) }
        FxRegistry.register("mob.steam") { SteamFx(it) }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Gemeinsame Hilfen
    // ---------------------------------------------------------------------------------------------------------------

    internal val UP = Vec3d(0.0, 1.0, 0.0)

    /** Matter Staub (wie `core.drip` ROCK_DUST), leicht in den Rand der Palette gezogen. */
    internal const val DUST_COLOR = 0xFFB4AA98.toInt()

    internal fun alpha(argb: Int, a: Float) = withAlpha(argb, a)
    internal fun mix(a: Int, b: Int, t: Float) = lerpColor(a, b, t)

    /** Wesen des Aufrufs (auch wenn es beim Eintreffen noch unbekannt war). */
    internal fun entityOf(args: FxArgs): Entity? = args.entity ?: args.entityId.takeIf { it >= 0 }?.let { args.world.getEntityById(it) }

    /** Dreht [v] um die Hochachse um [deg] Grad im Sinn von `yaw` (+ = wie `yaw + deg`; vorn = (−sin, 0, cos)). */
    internal fun yawTurn(v: Vec3d, deg: Double): Vec3d {
        val r = Math.toRadians(deg)
        val c = cos(r)
        val s = sin(r)
        return Vec3d(v.x * c - v.z * s, v.y, v.z * c + v.x * s)
    }

    /** Waagrechte Einheitsrichtung (Nullvektor → +Z). */
    internal fun flat(v: Vec3d): Vec3d = Vec3d(v.x, 0.0, v.z).let { if (it.lengthSquared() < 1e-9) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
}

// =====================================================================================================================
// Kampe
// =====================================================================================================================

/**
 * `kampe.venom`: Während sie zum Giftfächer ausholt, sammelt sich Gift im Maul – ein kleiner, ruhig wachsender Schein
 * am Maul, dazu fallen drei Tropfen vom Kiefer. Liegt am Kopf, nie auf der Kegel-Ansage am Boden.
 */
class VenomChargeFx(args: FxArgs) : FxEffect(args.life.coerceIn(MIN_LIFE, MAX_LIFE)) {
    private val entity = KampeFx.entityOf(args)
    private val fallback = args.pos
    private val palette = args.palette

    private fun mouth(f: FxFrame): Vec3d =
        entity?.let { KampeAnchors.mouth(it.id) } ?: entity?.let { e -> SpitFx.guessMouth(e, f.tickDelta) } ?: fallback

    override fun energy(f: FxFrame) {
        if (entity != null && (entity.isRemoved || !entity.isAlive)) return
        val t = time(f)
        val m = mouth(f)
        val k = Ease.inQuad(t / life)
        val end = ((life - t) / END_FADE).coerceIn(0f, 1f)
        FxDraw.glow(f, m, GLOW_MIN + (GLOW_MAX - GLOW_MIN) * k, KampeFx.alpha(KampeFx.mix(palette.main, palette.core, k), (GLOW_ALPHA_MIN + GLOW_ALPHA_RANGE * k) * (0.4f + 0.6f * end)))
        // Tropfen: lösen sich zu festen Zeiten, fallen mit leichter Schwerkraft, kurzer weicher Strich.
        for (i in 0 until DROPS) {
            val s = t - (DROP_FIRST + i * DROP_EVERY)
            if (s <= 0f || s > DROP_LIFE) continue
            val y = 0.5 * DROP_GRAVITY * s * s
            val tailY = 0.5 * DROP_GRAVITY * (s - DROP_TRAIL).coerceAtLeast(0f).pow(2)
            val fade = 1f - Ease.inQuad(s / DROP_LIFE)
            FxDraw.ribbon(
                f, listOf(m.add(0.0, -tailY, 0.0), m.add(0.0, -y, 0.0)), width = { u -> DROP_WIDTH * (0.4f + 0.6f * u) },
                color = { u -> KampeFx.alpha(KampeFx.mix(palette.edge, palette.main, u), DROP_ALPHA * fade) }, seed = i,
            )
        }
    }

    companion object {
        const val MIN_LIFE = 6
        const val MAX_LIFE = 40
        const val GLOW_MIN = 0.12f
        const val GLOW_MAX = 0.42f
        const val GLOW_ALPHA_MIN = 0.2f
        const val GLOW_ALPHA_RANGE = 0.3f
        const val END_FADE = 3f
        const val DROPS = 3
        const val DROP_FIRST = 3f
        const val DROP_EVERY = 4.5f
        const val DROP_LIFE = 10f
        const val DROP_GRAVITY = 0.04
        const val DROP_TRAIL = 2f
        const val DROP_WIDTH = 0.05f
        const val DROP_ALPHA = 0.7f
    }
}

/**
 * `kampe.spit`: Der Giftfächer kommt aus dem **Maul, das das Modell gerade zeigt** ([KampeAnchors.mouth]; fehlt es,
 * die Kopfhöhe vor ihr). Fünf verjüngte Strahlen in genau den Richtungen der Geschosse (dieselbe Streuung wie der
 * Server) schießen [REACH] Blöcke weit heraus und hängen leicht durch, ein kurzer Schein am Maul und weicher Nebel
 * entlang der Strahlen. Die Geschosse selbst zeichnet ihr eigener Renderer; die Lachen am Boden kommen aus `core.drip`.
 */
class SpitFx(args: FxArgs) : FxEffect(args.life.coerceIn(MIN_LIFE, MAX_LIFE)) {
    private val entity = KampeFx.entityOf(args)
    private val fallback = args.pos
    private val palette = args.palette
    private val dirs: List<Vec3d>

    init {
        val aim = if (args.dir.lengthSquared() < 1e-9) Vec3d(0.0, 0.0, 1.0) else args.dir.normalize()
        val n = args.c.toInt().coerceIn(1, MAX_JETS)
        val half = args.b.toDouble().coerceIn(0.0, 60.0)
        // Wie der Server: (i − (n − 1) / 2) · Schritt, Schritt = halbe Streuung / ((n − 1) / 2).
        val stepDeg = if (n > 1) half / ((n - 1) / 2.0) else 0.0
        dirs = List(n) { i ->
            val turn = Math.toRadians((i - (n - 1) / 2.0) * stepDeg)
            Vec3d(aim.x * cos(turn) - aim.z * sin(turn), aim.y, aim.x * sin(turn) + aim.z * cos(turn)).normalize()
        }
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val m = entity?.takeIf { !it.isRemoved }?.let { KampeAnchors.mouth(it.id) ?: guessMouth(it, f.tickDelta) } ?: fallback
        val k = t / life
        val fade = 1f - Ease.inQuad(k)
        val head = REACH * Ease.outCubic(t / GROW_TICKS)
        val tail = REACH * Ease.inQuad(((t - TAIL_DELAY) / (life - TAIL_DELAY)).coerceIn(0f, 1f))
        // Kurzer Schein am Maul beim Speien.
        val burst = Ease.bump(t / BURST_TICKS)
        if (burst > 0.01f) FxDraw.glow(f, m, BURST_SIZE, KampeFx.alpha(palette.core, BURST_ALPHA * burst))
        if (head - tail < 0.05f || fade <= 0.01f) return
        for ((j, d) in dirs.withIndex()) {
            val points = List(JET_POINTS) { i ->
                val dist = tail + (head - tail) * i / (JET_POINTS - 1.0)
                m.add(d.multiply(dist)).add(0.0, -SAG * dist * dist, 0.0)
            }
            FxDraw.ribbon(
                f, points, width = { u -> JET_TAIL_WIDTH + (JET_HEAD_WIDTH - JET_TAIL_WIDTH) * u },
                color = { u -> KampeFx.alpha(KampeFx.mix(palette.edge, palette.main, 0.4f + 0.6f * u), JET_ALPHA * fade * (0.35f + 0.65f * u)) },
                scroll = t * 0.15f, seed = j,
            )
            // Nebel: zwei weiche Scheine entlang des Strahls, wachsen und vergehen.
            for (q in 0 until MIST_PER_JET) {
                val dist = head * (0.45f + 0.45f * q)
                val p = m.add(d.multiply(dist.toDouble())).add(0.0, -SAG * dist * dist, 0.0)
                FxDraw.glow(f, p, MIST_SIZE + MIST_GROW * k, KampeFx.alpha(KampeFx.mix(palette.edge, palette.main, 0.5f), MIST_ALPHA * fade), seed = j * 4 + q)
            }
        }
    }

    companion object {
        const val MIN_LIFE = 8
        const val MAX_LIFE = 30
        const val MAX_JETS = 7

        /** So weit schießen die sichtbaren Strahlen aus dem Maul (Blöcke; die Geschosse fliegen weiter). */
        const val REACH = 3.4
        const val GROW_TICKS = 6f
        const val TAIL_DELAY = 3f
        const val JET_POINTS = 6

        /** Durchhängen der Strahlen (Blöcke je Block²). */
        const val SAG = 0.025
        const val JET_HEAD_WIDTH = 0.17f
        const val JET_TAIL_WIDTH = 0.04f
        const val JET_ALPHA = 0.75f
        const val BURST_TICKS = 6f
        const val BURST_SIZE = 0.6f
        const val BURST_ALPHA = 0.45f
        const val MIST_PER_JET = 2
        const val MIST_SIZE = 0.3f
        const val MIST_GROW = 0.45f
        const val MIST_ALPHA = 0.16f

        /** Rückfall ohne Modell-Anker: Kopfhöhe, ein Block vor ihr (wie der Server die Mündung rechnet). */
        fun guessMouth(e: Entity, tickDelta: Float): Vec3d {
            val r = Math.toRadians(e.getLerpedYaw(tickDelta).toDouble())
            return e.getLerpedPos(tickDelta).add(-sin(r), e.height * 0.82, cos(r))
        }
    }
}

/**
 * `kampe.sweep` (Flächeneffekt des Schwanzfegers): Hinter der Schwanzspitze bleibt ein **Staubband am Boden** – weiche,
 * matte Staubflecken auf dem Bogen, den die Spitze überstreicht (von +[halfAngle] nach −[halfAngle] um „hinten"), dazu
 * ein dünner Lichtstrich, der der Spitze folgt und verglimmt. Beginnt erst mit dem Treffer (die Kegel-Ansage ist dann
 * vorbei) und bleibt durchscheinend.
 */
class SweepFx(args: FxArgs) : FxEffect(args.life.coerceIn(MIN_LIFE, MAX_LIFE)) {
    private val center = args.pos
    private val back = KampeFx.flat(args.dir)
    private val radius = args.a.coerceIn(1f, MAX_RADIUS)
    private val halfAngle = args.b.coerceIn(10f, 180f)
    private val palette = args.palette
    private val dust = KampeFx.mix(KampeFx.DUST_COLOR, palette.edge, DUST_TINT)

    /** Punkt auf dem Bogen bei Winkel [phi] (Grad, 0 = genau hinten) im Abstand [r]. */
    private fun arc(phi: Float, r: Float): Vec3d = center.add(KampeFx.yawTurn(back, phi.toDouble()).multiply(r.toDouble()))

    /** Winkel der Schwanzspitze nach [t] Ticks. */
    private fun tip(t: Float): Float = halfAngle - 2f * halfAngle * Ease.outCubic(t / SWING_TICKS)

    override fun energy(f: FxFrame) {
        val t = time(f)
        val end = ((life - t) / END_FADE).coerceIn(0f, 1f)
        // Staubflecken: Fleck j entsteht, wenn die Spitze über ihn streicht.
        for (j in 0 until PUFFS) {
            val share = (j + 0.5f) / PUFFS
            val born = (1f - cbrt(1f - share)) * SWING_TICKS
            val age = t - born
            if (age <= 0f) continue
            val phi = halfAngle - 2f * halfAngle * share
            val drift = (age * DRIFT).coerceAtMost(DRIFT_MAX)
            val p = arc(phi, radius * PUFF_RING + drift).add(0.0, LIFT, 0.0)
            val grow = Ease.outCubic(age / PUFF_GROW_TICKS)
            val fade = (1f - Ease.inQuad(age / (life - born).coerceAtLeast(1f))) * end
            if (fade <= 0.01f) continue
            FxDraw.flat(
                f, p, KampeFx.UP, PUFF_SIZE_MIN + PUFF_SIZE_GROW * grow, KampeFx.alpha(dust, DUST_ALPHA * fade), FxMode.DUST,
                rotation = j * 1.1f + age * 0.01f, progress = DUST_DENSITY, seed = j,
            )
        }
        // Lichtstrich hinter der Spitze: von der Spitze bis [TRAIL_DEG] zurück, verglimmt nach dem Schwung.
        val glimmer = 1f - Ease.inOut((t - SWING_TICKS) / TRAIL_FADE_TICKS)
        if (glimmer <= 0.01f) return
        val head = tip(t)
        val from = (head + TRAIL_DEG).coerceAtMost(halfAngle)
        if (from - head < 1f) return
        val points = List(TRAIL_POINTS) { i -> arc(from + (head - from) * i / (TRAIL_POINTS - 1f), radius * TRAIL_RING).add(0.0, TRAIL_LIFT, 0.0) }
        FxDraw.strip(
            f, points, KampeFx.UP, width = { u -> TRAIL_WIDTH * (0.25f + 0.75f * u) },
            color = { u -> KampeFx.alpha(KampeFx.mix(palette.edge, palette.main, u), TRAIL_ALPHA * glimmer * u) }, mode = FxMode.RIBBON,
        )
    }

    companion object {
        const val MIN_LIFE = 12
        const val MAX_LIFE = 40
        const val MAX_RADIUS = 14f

        /** Der Schwung dauert so lange wie `SWEEP_STRIKE` (+1 zum Ausschwingen). */
        const val SWING_TICKS = 5f
        const val END_FADE = 6f
        const val PUFFS = 9
        const val PUFF_RING = 0.78f
        const val PUFF_SIZE_MIN = 0.6f
        const val PUFF_SIZE_GROW = 0.9f
        const val PUFF_GROW_TICKS = 10f
        const val DRIFT = 0.03f
        const val DRIFT_MAX = 0.5f
        const val LIFT = 0.06
        const val DUST_TINT = 0.15f
        const val DUST_ALPHA = 0.34f
        const val DUST_DENSITY = 0.7f
        const val TRAIL_DEG = 70f
        const val TRAIL_POINTS = 12
        const val TRAIL_RING = 0.86f
        const val TRAIL_LIFT = 0.12
        const val TRAIL_WIDTH = 0.2f
        const val TRAIL_ALPHA = 0.55f
        const val TRAIL_FADE_TICKS = 8f
    }
}

/**
 * `kampe.daze`: Benommen – ein kleiner Ring aus drei ruhigen Lichtpunkten über dem Kopf, der mit [TURN_HZ] Hz kreist,
 * jeder mit einem kurzen Schweif. Konstante Helligkeit (kein Pulsieren), weich ein- und ausgeblendet. Der Server
 * schickt ihn in Gold: benommen heißt offen.
 */
class DazeFx(args: FxArgs) : FxEffect(args.life.coerceIn(MIN_LIFE, MAX_LIFE)) {
    private val entity = KampeFx.entityOf(args)
    private val fallback = args.pos
    private val palette = args.palette

    private fun center(f: FxFrame): Vec3d {
        val e = entity ?: return fallback
        return KampeAnchors.head(e.id)?.add(0.0, ABOVE_HEAD, 0.0) ?: e.getLerpedPos(f.tickDelta).add(0.0, FALLBACK_HEIGHT, 0.0)
    }

    override fun energy(f: FxFrame) {
        if (entity != null && (entity.isRemoved || !entity.isAlive)) return
        val t = time(f)
        val fade = Ease.inOut(t / FADE_IN) * Ease.inOut((life - t) / FADE_OUT)
        if (fade <= 0.01f) return
        val c = center(f)
        val omega = (2.0 * PI * TURN_HZ / 20.0).toFloat()
        for (i in 0 until POINTS) {
            val a0 = t * omega + i * (2f * PI.toFloat() / POINTS)
            fun at(a: Float) = c.add(cos(a) * RING, sin(a * 2f + i) * WOBBLE, sin(a) * RING)
            FxDraw.glow(f, at(a0), DOT_SIZE, KampeFx.alpha(palette.core, DOT_ALPHA * fade), seed = i)
            val trail = List(TRAIL_POINTS) { q -> at(a0 - TRAIL_RAD * (1f - q / (TRAIL_POINTS - 1f))) }
            FxDraw.ribbon(
                f, trail, width = { u -> TRAIL_WIDTH * u },
                color = { u -> KampeFx.alpha(KampeFx.mix(palette.edge, palette.main, u), TRAIL_ALPHA * fade * u) }, seed = 10 + i,
            )
        }
    }

    companion object {
        const val MIN_LIFE = 10
        const val MAX_LIFE = 200
        const val POINTS = 3

        /** Umläufe je Sekunde (Briefing: 0,5 Hz). */
        const val TURN_HZ = 0.5
        const val RING = 0.55
        const val WOBBLE = 0.05
        const val ABOVE_HEAD = 0.85
        const val FALLBACK_HEIGHT = 2.2
        const val DOT_SIZE = 0.13f
        const val DOT_ALPHA = 0.6f
        const val TRAIL_POINTS = 5
        const val TRAIL_RAD = 0.7f
        const val TRAIL_WIDTH = 0.045f
        const val TRAIL_ALPHA = 0.35f
        const val FADE_IN = 8f
        const val FADE_OUT = 10f
    }
}

// =====================================================================================================================
// Kampe: Kettenschlösser (Überarbeitung 4, P22)
// =====================================================================================================================

/**
 * `kampe.shackle_glow`: Das goldene (offene) Kettenschloss zeigt, **welche Kette** es hält: ein weicher, ruhig atmender
 * Schein über dem Schloss (0,5 Hz, kein Blinken) und eine sehr schwache goldene Linie vom Schloss zum Kettenende am
 * Titanen, über die alle [RUN_EVERY] Ticks ein kurzer heller Abschnitt zum Titanen hinaufläuft. Das Schloss selbst
 * leuchtet aus seinem Renderer (P04); hier nur Hof und Weg. Endet weich, sobald das Wesen nicht mehr offen ist
 * (Zustand ≠ offen: gesprengt oder gedämpft) oder fehlt; `life` ist nur die Obergrenze.
 */
class ShackleGlowFx(args: FxArgs) : FxEffect(args.life.coerceIn(MIN_LIFE, MAX_LIFE)) {
    private val entity = KampeFx.entityOf(args)
    private val foot = args.pos
    private val top = args.dir
    private val palette = args.palette

    /** Sichtbares Alter, ab dem das Schloss nicht mehr offen war (NaN = noch offen). */
    private var closedAt = Float.NaN

    private fun stillOpen(): Boolean {
        val e = entity ?: return true
        if (e.isRemoved) return false
        return (e as? SubTargetEntity)?.let { it.state == SubTarget.STATE_OPEN } ?: true
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        if (closedAt.isNaN() && !stillOpen()) closedAt = t
        val out = if (closedAt.isNaN()) 1f else 1f - ((t - closedAt) / FADE_OUT).coerceIn(0f, 1f)
        val fade = Ease.inOut((t / FADE_IN).coerceIn(0f, 1f)) * out
        if (fade <= 0.01f) return
        val base = entity?.getLerpedPos(f.tickDelta) ?: foot
        val breath = 0.5f + 0.5f * sin((2.0 * PI * BREATH_HZ * t / 20.0).toFloat())
        // Hof über dem Schloss.
        FxDraw.glow(f, base.add(0.0, GLOW_LIFT, 0.0), GLOW_SIZE * (0.92f + 0.08f * breath), KampeFx.alpha(palette.main, (GLOW_ALPHA + GLOW_BREATH * breath) * fade))
        // Weg der Kette: schwache Linie, darüber der laufende Abschnitt.
        val from = base.add(0.0, LINE_LIFT, 0.0)
        val path = List(PATH_POINTS) { i -> from.lerp(top, i / (PATH_POINTS - 1.0)) }
        FxDraw.ribbon(f, path, width = { LINE_WIDTH }, color = { u -> KampeFx.alpha(palette.main, LINE_ALPHA * fade * (1f - 0.5f * u)) }, seed = 3)
        val phase = (t % RUN_EVERY) / RUN_TICKS
        if (phase < 1f) {
            val head = Ease.inOut(phase)
            val tail = (head - RUN_LENGTH).coerceAtLeast(0f)
            val seg = List(RUN_POINTS) { i -> from.lerp(top, (tail + (head - tail) * i / (RUN_POINTS - 1f)).toDouble()) }
            val runFade = sin((PI * phase).toFloat())
            FxDraw.ribbon(
                f, seg, width = { u -> RUN_WIDTH * (0.4f + 0.6f * u) },
                color = { u -> KampeFx.alpha(KampeFx.mix(palette.main, palette.core, u), RUN_ALPHA * runFade * fade * u) }, seed = 4,
            )
        }
    }

    override fun expired(): Boolean = super.expired() || (!closedAt.isNaN() && FxClock.age(this, age.toFloat()) >= closedAt + FADE_OUT)

    companion object {
        const val MIN_LIFE = 20
        const val MAX_LIFE = 600
        const val FADE_IN = 10f
        const val FADE_OUT = 8f
        const val BREATH_HZ = 0.5
        const val GLOW_LIFT = 0.9
        const val GLOW_SIZE = 1.15f
        const val GLOW_ALPHA = 0.16f
        const val GLOW_BREATH = 0.1f
        const val LINE_LIFT = 1.1
        const val PATH_POINTS = 12
        const val LINE_WIDTH = 0.05f
        const val LINE_ALPHA = 0.16f

        /** Ein Lauf alle so viele Ticks; er dauert [RUN_TICKS]. */
        const val RUN_EVERY = 50f
        const val RUN_TICKS = 34f

        /** Länge des hellen Abschnitts als Anteil des Wegs. */
        const val RUN_LENGTH = 0.16f
        const val RUN_POINTS = 6
        const val RUN_WIDTH = 0.12f
        const val RUN_ALPHA = 0.5f
    }
}

/**
 * `kampe.titan_jolt`: Das Schloss ist gesprengt, der Titan zuckt. Ein Ruck läuft als heller Strich vom Kettenende (pos)
 * zur Brust (dir) und breitet sich dort als ein weicher Stoßring aus; von beiden Schultern (Brust ± a waagerecht)
 * rieselt Staub und fällt. Ein Durchgang, matte Farben, Glanz nur im Strich.
 */
class TitanJoltFx(args: FxArgs) : FxEffect(args.life.coerceIn(MIN_LIFE, MAX_LIFE)) {
    private val limb = args.pos
    private val chest = args.dir
    private val half = args.a.toDouble().coerceIn(0.0, MAX_HALF)
    private val palette = args.palette
    private val dust = KampeFx.DUST_COLOR

    override fun energy(f: FxFrame) {
        val t = time(f)
        val fade = 1f - Ease.inQuad((t / life).coerceIn(0f, 1f))
        if (fade <= 0.01f) return
        // 1. Der Ruck: Strich vom Kettenende zur Brust.
        val s = (t / RUN_TICKS).coerceIn(0f, 1f)
        if (s < 1f) {
            val head = Ease.outCubic(s)
            val tail = (head - RUN_LENGTH).coerceAtLeast(0f)
            val seg = List(RUN_POINTS) { i -> limb.lerp(chest, (tail + (head - tail) * i / (RUN_POINTS - 1f)).toDouble()) }
            FxDraw.ribbon(
                f, seg, width = { u -> RUN_WIDTH * (0.3f + 0.7f * u) },
                color = { u -> KampeFx.alpha(KampeFx.mix(palette.edge, palette.main, u), RUN_ALPHA * (1f - s) * u) }, seed = 5,
            )
        }
        // 2. Stoßring an der Brust, sobald der Ruck ankommt.
        val r = ((t - RUN_TICKS) / RING_TICKS).coerceIn(0f, 1f)
        if (t >= RUN_TICKS && r < 1f) {
            val normal = f.camPos.subtract(chest).let { if (it.lengthSquared() < 1e-6) KampeFx.UP else it.normalize() }
            FxDraw.flat(f, chest, normal, RING_MAX * Ease.outCubic(r), KampeFx.alpha(palette.main, RING_ALPHA * (1f - r)), FxMode.SHOCK, progress = r, seed = 6)
        }
        // 3. Staub von den Schultern: Ballen lösen sich, fallen beschleunigt und vergehen.
        for (side in 0..1) {
            val shoulder = chest.add(if (side == 0) -half else half, SHOULDER_LIFT, 0.0)
            for (i in 0 until PUFFS) {
                val start = DUST_DELAY + i * PUFF_STAGGER
                val u = ((t - start) / (life - start)).coerceIn(0f, 1f)
                if (u <= 0f) continue
                val drop = FALL * u * u
                val spread = (i - (PUFFS - 1) / 2.0) * PUFF_SPACING
                val p = shoulder.add(spread, -drop, (i % 2) * PUFF_SPACING * 0.5)
                val normal = f.camPos.subtract(p).let { if (it.lengthSquared() < 1e-6) KampeFx.UP else it.normalize() }
                FxDraw.flat(
                    f, p, normal, PUFF_SIZE * (0.6f + 0.8f * u), KampeFx.alpha(dust, PUFF_ALPHA * (1f - Ease.inQuad(u)) * fade),
                    FxMode.DUST, rotation = i * 1.3f + side, progress = 0.7f, seed = 10 * side + i,
                )
            }
        }
    }

    companion object {
        const val MIN_LIFE = 14
        const val MAX_LIFE = 40
        const val MAX_HALF = 14.0
        const val RUN_TICKS = 7f
        const val RUN_LENGTH = 0.3f
        const val RUN_POINTS = 7
        const val RUN_WIDTH = 0.28f
        const val RUN_ALPHA = 0.75f
        const val RING_TICKS = 10f
        const val RING_MAX = 4.5f
        const val RING_ALPHA = 0.3f
        const val SHOULDER_LIFT = 0.6
        const val DUST_DELAY = 5f
        const val PUFFS = 5
        const val PUFF_STAGGER = 1.5f
        const val PUFF_SPACING = 0.8
        const val PUFF_SIZE = 0.7f
        const val PUFF_ALPHA = 0.32f
        const val FALL = 7.0
    }
}

// =====================================================================================================================
// Gegner des Olymp
// =====================================================================================================================

/**
 * `mob.griffin_land`: Staubring bei der Landung des Sturzflugs – sechs weiche Staubflecken laufen in [GROW_TICKS] auf
 * höchstens [MAX_RADIUS] Blöcke hinaus, einer bleibt in der Mitte. Kein Flächeneffekt im Sinne des Budgets (klein, kurz).
 */
class GriffinLandFx(args: FxArgs) : FxEffect(args.life.coerceIn(MIN_LIFE, MAX_LIFE)) {
    private val center = args.pos.add(0.0, LIFT, 0.0)
    private val radius = args.a.coerceIn(0.5f, MAX_RADIUS)
    private val dust = KampeFx.mix(KampeFx.DUST_COLOR, 0xFFF2EEE6.toInt(), WARM)

    override fun energy(f: FxFrame) {
        val t = time(f)
        val k = t / life
        val out = Ease.outCubic(t / GROW_TICKS)
        val fade = 1f - Ease.inQuad(k)
        if (fade <= 0.01f) return
        FxDraw.flat(f, center, KampeFx.UP, radius * CENTER_SIZE * (0.6f + 0.4f * out), KampeFx.alpha(dust, ALPHA * 0.7f * fade), FxMode.DUST, progress = 0.6f, seed = 7)
        for (i in 0 until PUFFS) {
            val a = i * (2.0 * PI / PUFFS) + 0.4
            val r = radius * (START + (1f - START) * out)
            val p = center.add(cos(a) * r, 0.0, sin(a) * r)
            FxDraw.flat(f, p, KampeFx.UP, PUFF_SIZE * (0.7f + 0.5f * out), KampeFx.alpha(dust, ALPHA * fade), FxMode.DUST, rotation = i * 0.9f, progress = 0.7f, seed = i)
        }
    }

    companion object {
        const val MIN_LIFE = 6
        const val MAX_LIFE = 10
        const val MAX_RADIUS = 2f
        const val GROW_TICKS = 7f
        const val START = 0.3f
        const val PUFFS = 6
        const val PUFF_SIZE = 0.45f
        const val CENTER_SIZE = 0.45f
        const val ALPHA = 0.3f
        const val LIFT = 0.05
        const val WARM = 0.35f
    }
}

/**
 * `mob.steam`: Dampfstoß der Goldenen Wächterin als **weicher Kegel-Schleier** – ein sehr durchscheinender Kegelmantel
 * (Fresnel-Hülle) öffnet sich aus der Düse bis zur Reichweite der Ansage, darin treiben matte Dampfballen nach vorn
 * und vergehen. Weiß-grau, kein Glühen der Gefahrenfarbe (Dampf, kein Feuer). Ersetzt die Vanilla-Wolken.
 */
class SteamFx(args: FxArgs) : FxEffect(args.life.coerceIn(MIN_LIFE, MAX_LIFE)) {
    private val nozzle = args.pos
    private val dir = if (args.dir.lengthSquared() < 1e-9) Vec3d(0.0, 0.0, 1.0) else args.dir.normalize()
    private val reach = args.a.coerceIn(1f, MAX_REACH)
    private val spread = tan(Math.toRadians(args.b.toDouble().coerceIn(5.0, 45.0))).toFloat()

    override fun energy(f: FxFrame) {
        val t = time(f)
        val k = t / life
        val open = Ease.outCubic(t / OPEN_TICKS)
        val fade = (1f - k).pow(1.5f)
        if (fade <= 0.01f) return
        // Kegelmantel: Spitze an der Düse, Fuß bei der aktuellen Länge.
        val length = reach * open * VEIL_SHARE
        if (length > 0.2f) {
            val foot = nozzle.add(dir.multiply(length.toDouble()))
            val r = length * spread
            FxDraw.mesh(
                f, FxMeshes.CONE, FxDraw.place(foot, up = dir.multiply(-1.0), scaleXYZ = Vector3f(r, length, r)),
                KampeFx.alpha(STEAM, VEIL_ALPHA * fade), FxMode.SHELL, seed = 3,
            )
        }
        // Dampfballen: laufen mit abnehmendem Tempo nach vorn, wachsen, vergehen.
        for (i in 0 until PUFFS) {
            val lag = i * PUFF_STAGGER
            val s = ((t - lag) / (life - lag)).coerceIn(0f, 1f)
            if (s <= 0f) continue
            val d = reach * Ease.outCubic(s) * (0.35f + 0.65f * (i + 1f) / PUFFS)
            val p = nozzle.add(dir.multiply(d.toDouble())).add(0.0, RISE * s, 0.0)
            val size = PUFF_MIN + d * spread * PUFF_SPREAD
            val normal = f.camPos.subtract(p).let { if (it.lengthSquared() < 1e-6) KampeFx.UP else it.normalize() }
            FxDraw.flat(f, p, normal, size, KampeFx.alpha(STEAM, PUFF_ALPHA * (1f - Ease.inQuad(s))), FxMode.DUST, rotation = i * 1.7f + s, progress = 0.75f, seed = i)
        }
    }

    companion object {
        const val MIN_LIFE = 10
        const val MAX_LIFE = 40
        const val MAX_REACH = 10f
        const val STEAM = 0xFFEEF2F4.toInt()
        const val OPEN_TICKS = 7f
        const val VEIL_SHARE = 0.9f
        const val VEIL_ALPHA = 0.16f
        const val PUFFS = 7
        const val PUFF_STAGGER = 1.2f
        const val PUFF_MIN = 0.3f
        const val PUFF_SPREAD = 0.9f
        const val PUFF_ALPHA = 0.26f
        const val RISE = 0.6
    }
}
