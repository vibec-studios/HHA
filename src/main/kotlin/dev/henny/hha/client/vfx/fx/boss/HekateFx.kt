package dev.henny.hha.client.vfx.fx.boss

import dev.henny.hha.HhaEntities
import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.model.HekateTorches
import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxAttach
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMeshes
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.client.vfx.fx.FxRegistry
import dev.henny.hha.client.vfx.lerpColor
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha.entity.boss.olymp.HekateAction
import dev.henny.hha.entity.boss.olymp.HekateEntity
import dev.henny.hha.logic.boss.timings.HekateTimings
import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Client-Effekte der Hekate (P08, PLAN 2.5), Präfix `hekate.`. Jede ID ist genau eine Klasse; Zeiten und Größen stehen
 * als benannte Konstanten im `companion` der Klasse. Alles weich gezeichnet über [FxDraw] (Bänder, Flächen, glatte
 * Meshes), keine Stufen in Zeit oder Alpha, nichts blinkt. Farben aus der Palette des Aufrufs (Gefahr = Mond-Violett);
 * nur die Trugbilder tragen ein festes kaltes Blau ([HekateCold]), Gold kommt nicht vor.
 *
 * | ID | Server-Stelle | Kodierung |
 * | --- | --- | --- |
 * | `hekate.torch_arc` | Fackelschwung, je Treffer (Flächeneffekt) | pos = Füße, dir = Mitte des Schwungs, a = Bogenradius, b = halber Winkel (°), c = +1/−1 Schwungrichtung, d = Höhe, entity = Hekate |
 * | `hekate.key_ring` | Schlüsseldrehung, ab Beginn der Ansage | pos = Ringmitte über dem Platz, dir = goldener Weg (waagrecht), a = Radius, b = Tick des Einrastens (= Treffer), life = b + Nachlauf |
 * | `hekate.echo_fade` | Trugbild verweht | pos = Füße, a = Höhe |
 * | `hekate.kindle` | Auftritt, je Fackelsäule (Marke `torch<n>`) | pos = Feuerschale |
 * | `hekate.ground_fire` | Fackelwurf, liegendes Feuer | pos = Mitte am Boden, a = Radius, life = Brenndauer |
 * | `hekate.path_fire` | Feuerpfad, Treffer (Flächeneffekt) | pos = Wegende, dir = Weganfang (absolut), a = Wegbreite |
 *
 * Dauer-Effekt ([FxAttach]) für jede Hekate: die Echte trägt eine weiche Flamme an der rechten Fackel (Stärke folgt dem
 * Kampfzustand), die Trugbilder einen kalten Mantel (SHELL). So bleibt die Echte in der Dreigestalt erkennbar.
 */
object HekateFx {

    const val TORCH_ARC = "hekate.torch_arc"
    const val KEY_RING = "hekate.key_ring"
    const val ECHO_FADE = "hekate.echo_fade"
    const val KINDLE = "hekate.kindle"
    const val GROUND_FIRE = "hekate.ground_fire"
    const val PATH_FIRE = "hekate.path_fire"

    fun init() {
        FxRegistry.register(TORCH_ARC) { HekateTorchArcFx(it) }
        FxRegistry.register(KEY_RING) { HekateKeyRingFx(it) }
        FxRegistry.register(ECHO_FADE) { HekateEchoFadeFx(it) }
        FxRegistry.register(KINDLE) { HekateKindleFx(it) }
        FxRegistry.register(GROUND_FIRE) { HekateGroundFireFx(it) }
        FxRegistry.register(PATH_FIRE) { HekatePathFireFx(it) }
        FxAttach.register(HhaEntities.HEKATE) { HekatePresenceFx(it) }
    }
}

/** Kalte Farben der Trugbilder (fest, unabhängig vom Kampfstil; bewusst kein Gold, kein Mond-Violett). */
private object HekateCold {
    const val CORE = 0xFFEAF2FF.toInt()
    const val MAIN = 0xFFAFC6EE.toInt()
    const val EDGE = 0xFF5E6F9A.toInt()
}


private val HEK_UP = Vec3d(0.0, 1.0, 0.0)

private fun hekAlpha(argb: Int, alpha: Float): Int = withAlpha(argb, alpha)

private fun hekMix(x: Int, y: Int, t: Float): Int = lerpColor(x, y, t)

/** Waagrechter Einheitsvektor (Nullvektor → +Z). */
private fun hekFlat(v: Vec3d): Vec3d {
    val h = Vec3d(v.x, 0.0, v.z)
    return if (h.lengthSquared() < 1e-9) Vec3d(0.0, 0.0, 1.0) else h.normalize()
}

/** Rechts von [forward] (wie `Entity.yaw`: vorn = (−sin, 0, cos), rechts = (−cos, 0, −sin)). */
private fun hekRightOf(forward: Vec3d): Vec3d = Vec3d(-forward.z, 0.0, forward.x)

/**
 * Kamerazugewandtes Band, dessen Querlage nach oben zeigt (für FLAME: Fuß unten, Spitze oben). Kehrt die Punktfolge um,
 * wenn die Seite des Bandes nach unten zeigen würde. [widths] und [colors] gehören zu den Punkten.
 */
private fun hekUpwardRibbon(f: FxFrame, points: List<Vec3d>, widths: FloatArray, colors: IntArray, mode: Int, seed: Int) {
    val n = points.size
    if (n < 2) return
    val mid = n / 2
    val tangent = points[minOf(mid + 1, n - 1)].subtract(points[maxOf(mid - 1, 0)])
    val face = f.camPos.subtract(points[mid])
    val side = tangent.crossProduct(face)
    val flip = side.y < 0.0
    val pts = if (flip) points.reversed() else points
    val w = if (flip) widths.reversedArray() else widths
    val c = if (flip) colors.reversedArray() else colors
    FxDraw.ribbon(
        f, pts,
        width = { s -> w[(s * (n - 1)).roundToInt().coerceIn(0, n - 1)] },
        color = { s -> c[(s * (n - 1)).roundToInt().coerceIn(0, n - 1)] },
        mode = mode, seed = seed,
    )
}

/** Kamerazugewandte Flammenkarte (FLAME-Wand quer zum Blick) mit Fuß bei [foot]. */
private fun hekFlameCard(f: FxFrame, foot: Vec3d, halfWidth: Float, height: Float, argb: Int, seed: Int) {
    if (height <= 0.01f || halfWidth <= 0.01f) return
    val r = Vec3d(f.right.x.toDouble(), 0.0, f.right.z.toDouble()).let { if (it.lengthSquared() < 1e-6) Vec3d(1.0, 0.0, 0.0) else it.normalize() }
    FxDraw.wall(f, foot.subtract(r.multiply(halfWidth.toDouble())), foot.add(r.multiply(halfWidth.toDouble())), height, argb, FxMode.FLAME, seed = seed)
}

// =====================================================================================================================
// hekate.torch_arc – Flammenband am Fackelkopf (Fackelschwung)
// =====================================================================================================================

/**
 * Der Fackelkopf zieht ein Flammenband auf einem Bogen um Hekate: Der Kopf läuft in [SWING] Ticks über den ganzen
 * Winkel (`outCubic`), der Schweif folgt ab [TAIL_DELAY] und holt ihn bis [TAIL_END] ein; das Band hängt an der Lage
 * der Entity. Flamme (FLAME, Fuß unten) plus heller Kernstrich (RIBBON), Kopf-Schein in [over].
 */
private class HekateTorchArcFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val entity: Entity? = args.entity
    private val origin = args.pos
    private val dir = hekFlat(args.dir)
    private val right = hekRightOf(dir)
    private val radius = args.a.coerceIn(0.6f, MAX_RADIUS)
    private val half = Math.toRadians(args.b.coerceIn(10f, 90f).toDouble()).toFloat()
    private val side = if (args.c < 0f) -1f else 1f
    private val height = if (args.d > 0f) args.d else DEFAULT_HEIGHT
    private val palette: Palette = args.palette
    private val seed = args.seed.toInt()

    private fun feet(f: FxFrame): Vec3d = entity?.takeIf { !it.isRemoved }?.getLerpedPos(f.tickDelta) ?: origin

    /** Lage auf dem Bogen beim Anteil [p] (0 = Start der Bewegung, 1 = Ende). */
    private fun at(feet: Vec3d, p: Float, lift: Float): Vec3d {
        val th = half * side * (1f - 2f * p)
        val d = dir.multiply(cos(th).toDouble()).add(right.multiply(sin(th).toDouble()))
        return feet.add(d.multiply(radius.toDouble())).add(0.0, (height + lift).toDouble(), 0.0)
    }

    private fun fade(t: Float): Float = 1f - Ease.inQuad((t - FADE_START) / (life - FADE_START).coerceAtLeast(1f))

    override fun energy(f: FxFrame) {
        val t = time(f)
        val head = Ease.outCubic(t / SWING)
        val tail = Ease.inOut((t - TAIL_DELAY) / (TAIL_END - TAIL_DELAY))
        if (head - tail < 0.01f) return
        val alpha = fade(t)
        if (alpha <= 0.01f) return
        val feet = feet(f)
        val n = POINTS
        val flame = ArrayList<Vec3d>(n)
        val core = ArrayList<Vec3d>(n)
        val fw = FloatArray(n)
        val fc = IntArray(n)
        val cw = FloatArray(n)
        val cc = IntArray(n)
        for (i in 0 until n) {
            val s = i / (n - 1f)                      // 0 Schweif … 1 Kopf
            val p = tail + (head - tail) * s
            val taper = Ease.outQuad(s) * (1f - 0.35f * Ease.inQuad((s - 0.85f) / 0.15f))
            val w = FLAME_WIDTH * taper
            flame += at(feet, p, w * 0.6f)
            core += at(feet, p, 0f)
            fw[i] = w
            fc[i] = hekAlpha(hekMix(palette.main, palette.core, 0.35f * s), FLAME_ALPHA * alpha * (0.25f + 0.75f * s))
            cw[i] = CORE_WIDTH * taper
            cc[i] = hekAlpha(palette.core, CORE_ALPHA * alpha * s)
        }
        hekUpwardRibbon(f, flame, fw, fc, FxMode.FLAME, seed)
        FxDraw.ribbon(f, core, width = { s -> cw[(s * (n - 1)).roundToInt()] }, color = { s -> cc[(s * (n - 1)).roundToInt()] }, mode = FxMode.RIBBON, seed = seed + 1)
    }

    override fun over(f: FxFrame) {
        val t = time(f)
        val k = 1f - Ease.inQuad(t / (SWING + 4f))
        if (k <= 0.01f) return
        val head = Ease.outCubic(t / SWING)
        FxDraw.glow(f, at(feet(f), head, 0f), HEAD_GLOW, hekAlpha(palette.core, HEAD_GLOW_ALPHA * k), seed)
    }

    companion object {
        const val MIN_LIFE = 12
        const val MAX_RADIUS = 4f
        /** Standardhöhe des Bogens über den Füßen (Fackelkopf im Schwung). */
        const val DEFAULT_HEIGHT = 2.0f
        /** Kopf überstreicht den Winkel in so vielen Ticks (Schwung im Modell: 3 Ticks) … */
        const val SWING = 5f
        /** … der Schweif folgt ab hier und holt ihn bis [TAIL_END] ein. */
        const val TAIL_DELAY = 2f
        const val TAIL_END = 10f
        const val FADE_START = 6f
        const val POINTS = 16
        const val FLAME_WIDTH = 0.55f
        const val FLAME_ALPHA = 0.85f
        const val CORE_WIDTH = 0.09f
        const val CORE_ALPHA = 0.8f
        const val HEAD_GLOW = 1.1f
        const val HEAD_GLOW_ALPHA = 0.4f
    }
}

// =====================================================================================================================
// hekate.key_ring – Runenring mit drehenden Schlitzen über dem Platz (Schlüsseldrehung)
// =====================================================================================================================

/**
 * Zwei Runenringe (RUNE) liegen waagrecht über dem Platz und drehen gegeneinander, während die Ansage läuft; der äußere
 * zeichnet sich in [DRAW_IN] Ticks ein. In den letzten [LOCK] Ticks vor dem Treffer ([lockAt]) rasten beide ein: Der
 * Winkel läuft mit `outBack` auf die Richtung des goldenen Wegs und bleibt dort stehen; der Kern-Schein schwillt einmal
 * an (Hüllkurve `bump`, ≤ 0,25 zusätzlich). Danach blendet alles im Nachlauf aus.
 */
private class HekateKeyRingFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val center = args.pos
    private val radius = args.a.coerceIn(1f, MAX_RADIUS)
    private val lockAt = args.b.coerceIn(LOCK + 1f, maxOf(LOCK + 1f, life.toFloat()))
    private val palette: Palette = args.palette
    private val seed = args.seed.toInt()
    /** Zielwinkel des Einrastens: Richtung des goldenen Wegs in der Ringebene (Drehung um +Y). */
    private val target: Float = hekFlat(args.dir).let { kotlin.math.atan2(it.z, it.x).toFloat() }

    private val lockFrom = lockAt - LOCK
    private val freeAngle = SPIN * lockFrom
    /** Einrast-Winkel: der nächste Winkel ≥ dem freien Lauf, der auf [target] zeigt (Schlitze haben 1/[FOLD] Periode). */
    private val lockedAngle: Float = run {
        val period = (2.0 * PI / FOLD).toFloat()
        var g = target
        while (g < freeAngle + period * 0.25f) g += period
        while (g - period >= freeAngle + period * 0.25f) g -= period
        g
    }

    private fun angle(t: Float): Float = when {
        t < lockFrom -> SPIN * t
        else -> freeAngle + (lockedAngle - freeAngle) * Ease.outBack((t - lockFrom) / LOCK)
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val inK = Ease.outCubic(t / DRAW_IN)
        val out = 1f - Ease.inQuad((t - lockAt - HOLD) / (life - lockAt - HOLD).coerceAtLeast(1f))
        val alpha = inK * out
        if (alpha <= 0.01f) return
        val th = angle(t)
        val build = Ease.inQuad(t / lockAt) // Spannung steigt bis zum Einrasten
        FxDraw.flat(
            f, center, HEK_UP, radius, hekAlpha(palette.main, OUTER_ALPHA * alpha * (0.75f + 0.25f * build)), FxMode.RUNE,
            inner = radius * OUTER_INNER, rotation = th, progress = inK, seed = seed,
        )
        FxDraw.flat(
            f, center, HEK_UP, radius * INNER_OUTER, hekAlpha(hekMix(palette.main, palette.alt, 0.5f), INNER_ALPHA * alpha), FxMode.RUNE,
            inner = radius * INNER_INNER, rotation = -th * INNER_RATIO, progress = inK, seed = seed + 7,
        )
        // Weicher Hof unter dem Ring: lässt ihn aus der Tiefe des Platzes lesbar wirken.
        FxDraw.flat(f, center.add(0.0, -0.05, 0.0), HEK_UP, radius * 1.15f, hekAlpha(palette.edge, HALO_ALPHA * alpha), FxMode.GLOW, seed = seed + 3)
    }

    override fun over(f: FxFrame) {
        val t = time(f)
        val click = Ease.bump((t - lockFrom) / (LOCK + HOLD))
        val out = 1f - Ease.inQuad((t - lockAt - HOLD) / (life - lockAt - HOLD).coerceAtLeast(1f))
        val k = (CORE_ALPHA * Ease.outCubic(t / DRAW_IN) + CLICK_ALPHA * click) * out
        if (k <= 0.01f) return
        FxDraw.glow(f, center, radius * CORE_SIZE, hekAlpha(palette.core, k), seed)
    }

    companion object {
        const val MIN_LIFE = 16
        const val MAX_RADIUS = 14f
        /** Drehung im freien Lauf (rad/Tick; ≈ 0,29 Umdrehungen je Sekunde, keine Helligkeitsänderung). */
        const val SPIN = 0.09f
        /** Einrasten: so viele Ticks vor dem Treffer (Briefing: 6). */
        const val LOCK = 6f
        /** Nach dem Einrasten so lange voll stehen, dann ausblenden. */
        const val HOLD = 4f
        /** Der äußere Ring zeichnet sich in so vielen Ticks ein. */
        const val DRAW_IN = 12f
        /** Symmetrie der Schlitze für den Einrast-Winkel (drei Wege). */
        const val FOLD = 3
        const val OUTER_INNER = 0.7f
        const val OUTER_ALPHA = 0.8f
        const val INNER_OUTER = 0.62f
        const val INNER_INNER = 0.42f
        const val INNER_ALPHA = 0.6f
        const val INNER_RATIO = 1.6f
        const val HALO_ALPHA = 0.25f
        const val CORE_SIZE = 0.35f
        const val CORE_ALPHA = 0.18f
        /** Zusätzlicher Schein beim Einrasten (PLAN 2.2: Blitze ≤ 0,25). */
        const val CLICK_ALPHA = 0.25f
    }
}

// =====================================================================================================================
// hekate.echo_fade – Trugbild verweht als kalter Rauchwirbel
// =====================================================================================================================

/**
 * Der kalte Mantel des Trugbilds löst sich mit Glutkante auf (SHELL, Fortschritt 1 → 0), drei Rauchbänder schrauben
 * sich um die Achse nach oben und weiten sich, ein flacher Wirbel dreht in Hüfthöhe auf. Alles in [HekateCold].
 */
private class HekateEchoFadeFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val feet = args.pos
    private val height = args.a.coerceIn(1f, 6f).takeIf { args.a > 0f } ?: DEFAULT_HEIGHT
    private val seed = args.seed.toInt()

    override fun energy(f: FxFrame) {
        val t = time(f)
        val k = k(f)
        // Mantel löst sich auf
        val dissolve = 1f - Ease.inOut(k / SHELL_SHARE)
        if (dissolve > 0.01f) {
            val m = Matrix4f().translation(feet.x.toFloat(), (feet.y + height * 0.5).toFloat(), feet.z.toFloat())
                .scale(SHELL_W * (1f + 0.25f * k), height * 0.55f, SHELL_W * (1f + 0.25f * k))
            FxDraw.mesh(f, FxMeshes.SPHERE, m, hekAlpha(HekateCold.MAIN, SHELL_ALPHA), FxMode.SHELL, seed = seed, progress = dissolve)
        }
        // Rauchbänder
        val alpha = SMOKE_ALPHA * Ease.outQuad(k / 0.15f) * (1f - Ease.inQuad((k - 0.35f) / 0.65f))
        if (alpha > 0.01f) {
            val spin = t * SMOKE_SPIN
            for (arm in 0 until ARMS) {
                val base = arm * (2f * PI.toFloat() / ARMS) + spin
                val pts = List(ARM_POINTS) { i ->
                    val s = i / (ARM_POINTS - 1f)
                    val ang = base + s * ARM_TURN
                    val r = (0.35f + s * 0.9f) * (1f + 1.4f * Ease.outQuad(k))
                    feet.add((cos(ang) * r).toDouble(), (0.2f + s * height * (0.9f + 0.6f * k)).toDouble(), (sin(ang) * r).toDouble())
                }
                FxDraw.ribbon(
                    f, pts,
                    width = { s -> (0.12f + 0.35f * s) * (1f + k) },
                    color = { s -> hekAlpha(hekMix(HekateCold.CORE, HekateCold.EDGE, s), alpha * (1f - 0.6f * s)) },
                    mode = FxMode.RIBBON, seed = seed + 1 + arm,
                )
            }
        }
        // Wirbel in Hüfthöhe
        val swirl = Ease.bump(k)
        if (swirl > 0.01f) {
            FxDraw.flat(
                f, feet.add(0.0, height * 0.35, 0.0), HEK_UP, SWIRL_RADIUS * (0.6f + 0.8f * k), hekAlpha(HekateCold.MAIN, SWIRL_ALPHA * swirl),
                FxMode.SWIRL, rotation = t * 0.25f, progress = 1f - k, seed = seed + 5,
            )
        }
    }

    companion object {
        const val MIN_LIFE = 14
        const val DEFAULT_HEIGHT = 3.2f
        const val SHELL_W = 0.75f
        const val SHELL_ALPHA = 0.55f
        const val SHELL_SHARE = 0.55f
        const val SMOKE_ALPHA = 0.55f
        const val SMOKE_SPIN = 0.18f
        const val ARMS = 3
        const val ARM_POINTS = 10
        const val ARM_TURN = 2.4f
        const val SWIRL_RADIUS = 1.4f
        const val SWIRL_ALPHA = 0.5f
    }
}

// =====================================================================================================================
// hekate.kindle – eine Fackelsäule entzündet sich (Auftritt)
// =====================================================================================================================

/**
 * Seelenfeuer schießt aus der Schale hoch (Flammenkarte, `outBack` auf [BURST] Blöcke), sinkt auf die Ruhehöhe des
 * echten Lagerfeuers und blendet aus; dazu ein kleiner Stoßring auf der Schale und ein weicher Schein (≤ 0,45, `bump`).
 */
private class HekateKindleFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val bowl = args.pos
    private val palette: Palette = args.palette
    private val seed = args.seed.toInt()

    override fun energy(f: FxFrame) {
        val t = time(f)
        val k = k(f)
        val rise = Ease.outBack(t / RISE)
        val settle = Ease.inOut((t - RISE) / SETTLE)
        val h = (BURST * rise) * (1f - settle) + REST * settle
        val alpha = 1f - Ease.inQuad((k - 0.55f) / 0.45f)
        hekFlameCard(f, bowl, HALF_WIDTH * (0.7f + 0.3f * rise), h, hekAlpha(hekMix(palette.main, palette.core, 0.3f), FLAME_ALPHA * alpha), seed)
        val ring = Ease.outCubic(t / RING_TICKS)
        if (ring < 1f) {
            FxDraw.flat(
                f, bowl.add(0.0, 0.05, 0.0), HEK_UP, RING_RADIUS * ring, hekAlpha(palette.main, RING_ALPHA * (1f - ring)), FxMode.SHOCK,
                inner = RING_RADIUS * ring * 0.7f, seed = seed + 1,
            )
        }
    }

    override fun over(f: FxFrame) {
        val glow = Ease.bump(time(f) / GLOW_TICKS)
        if (glow > 0.01f) FxDraw.glow(f, bowl.add(0.0, 0.6, 0.0), GLOW_SIZE, hekAlpha(palette.core, GLOW_ALPHA * glow), seed)
    }

    companion object {
        const val MIN_LIFE = 20
        const val RISE = 6f
        const val SETTLE = 14f
        const val BURST = 2.4f
        const val REST = 0.9f
        const val HALF_WIDTH = 0.55f
        const val FLAME_ALPHA = 0.9f
        const val RING_TICKS = 10f
        const val RING_RADIUS = 1.6f
        const val RING_ALPHA = 0.6f
        const val GLOW_TICKS = 16f
        const val GLOW_SIZE = 2.6f
        const val GLOW_ALPHA = 0.45f
    }
}

// =====================================================================================================================
// hekate.ground_fire – das liegende Feuer nach dem Fackelwurf
// =====================================================================================================================

/**
 * Weiche Flammenfläche genau im Radius der Gefahr: dunkler Brandfleck (STAIN, breitet sich in [SPREAD] Ticks aus),
 * schwacher Glutschein, ein Kranz aus [RIM] Flammen auf dem Rand (zeigt die Grenze) und [INNER] Flammen innen.
 * Blendet in [FADE_IN] ein und in den letzten [FADE_OUT] Ticks aus.
 */
private class HekateGroundFireFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val center = args.pos
    private val radius = args.a.coerceIn(0.5f, 8f)
    private val palette: Palette = args.palette
    private val seed = args.seed.toInt()
    private val rng = java.util.Random(args.seed)
    private val rimHeight = FloatArray(RIM) { 0.55f + rng.nextFloat() * 0.5f }
    private val rimPhase = FloatArray(RIM) { rng.nextFloat() * 6.28f }
    private val inner = Array(INNER) {
        val ang = rng.nextFloat() * 6.28f
        val r = radius * (0.2f + rng.nextFloat() * 0.45f)
        Vec3d((cos(ang) * r).toDouble(), 0.0, (sin(ang) * r).toDouble())
    }

    private fun envelope(t: Float): Float = Ease.outQuad(t / FADE_IN) * (1f - Ease.inQuad((t - (life - FADE_OUT)) / FADE_OUT))

    override fun energy(f: FxFrame) {
        val t = time(f)
        val env = envelope(t)
        if (env <= 0.01f) return
        val ground = center.add(0.0, LIFT, 0.0)
        FxDraw.flat(f, ground, HEK_UP, radius, hekAlpha(palette.edge, STAIN_ALPHA * env), FxMode.STAIN, progress = Ease.outCubic(t / SPREAD), seed = seed)
        FxDraw.flat(f, ground.add(0.0, 0.02, 0.0), HEK_UP, radius * 1.05f, hekAlpha(palette.main, GLOW_ALPHA * env), FxMode.GLOW, seed = seed + 1)
        val flameColor = hekAlpha(hekMix(palette.main, palette.core, 0.25f), FLAME_ALPHA * env)
        for (i in 0 until RIM) {
            val ang = i * (2f * PI.toFloat() / RIM)
            val d = Vec3d(cos(ang).toDouble(), 0.0, sin(ang).toDouble())
            val tan = Vec3d(-d.z, 0.0, d.x).multiply(radius * RIM_SPAN.toDouble())
            val foot = center.add(d.multiply(radius.toDouble()))
            val h = rimHeight[i] * (0.85f + 0.15f * sin(t * 0.21f + rimPhase[i])) * Ease.outQuad(t / FADE_IN)
            FxDraw.wall(f, foot.subtract(tan), foot.add(tan), h, flameColor, FxMode.FLAME, seed = seed + 10 + i)
        }
        for ((i, off) in inner.withIndex()) {
            hekFlameCard(f, center.add(off), 0.35f, 0.7f * env, flameColor, seed + 40 + i)
        }
    }

    companion object {
        const val MIN_LIFE = 20
        const val LIFT = 0.04
        const val SPREAD = 6f
        const val FADE_IN = 4f
        const val FADE_OUT = 10f
        const val STAIN_ALPHA = 0.5f
        const val GLOW_ALPHA = 0.3f
        const val FLAME_ALPHA = 0.75f
        const val RIM = 9
        /** Halbe Breite einer Randflamme als Anteil des Radius (lässt Lücken zwischen den Zungen). */
        const val RIM_SPAN = 0.22f
        const val INNER = 3
    }
}

// =====================================================================================================================
// hekate.path_fire – der Weg brennt (Treffer des Feuerpfads)
// =====================================================================================================================

/**
 * Drei Flammenwände (Mitte hoch, Seiten niedriger) schießen vom Wegende zur Mitte hin in [RUN] Ticks hoch und sinken
 * über die restliche Lebenszeit; darunter ein heißer, liegender Streifen (RIBBON flach) in voller Wegbreite.
 */
private class HekatePathFireFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val end = args.pos
    /**
     * Weganfang (absolut). Kommt nur eine Richtung an (Vorführung `/hha fx play`: Einheitsvektor), liegt er
     * [DEMO_LENGTH] Blöcke in dieser Richtung; höchstens [MAX_LENGTH] Blöcke vom Wegende.
     */
    private val start: Vec3d = run {
        val raw = if (args.dir.lengthSquared() <= 1.0001) end.add(args.dir.multiply(DEMO_LENGTH)) else args.dir
        val d = raw.subtract(end)
        if (d.length() > MAX_LENGTH) end.add(d.normalize().multiply(MAX_LENGTH)) else raw
    }
    private val half = (args.a.coerceIn(1f, 12f) * 0.5f)
    private val palette: Palette = args.palette
    private val seed = args.seed.toInt()
    private val along = hekFlat(start.subtract(end))
    private val across = hekRightOf(along)

    override fun energy(f: FxFrame) {
        val t = time(f)
        val run = Ease.outCubic(t / RUN)
        val fall = 1f - Ease.inQuad((t - RUN) / (life - RUN).coerceAtLeast(1f))
        if (fall <= 0.01f) return
        val front = end.lerp(start, run.toDouble())
        val flame = hekAlpha(hekMix(palette.main, palette.core, 0.3f), FLAME_ALPHA * fall)
        FxDraw.wall(f, end, front, CENTER_HEIGHT * fall, flame, FxMode.FLAME, seed = seed)
        for (s in listOf(-1.0, 1.0)) {
            val o = across.multiply(s * half * SIDE_OFFSET)
            FxDraw.wall(f, end.add(o), front.add(o), SIDE_HEIGHT * fall, hekAlpha(palette.main, SIDE_ALPHA * fall), FxMode.FLAME, seed = seed + if (s < 0) 1 else 2)
        }
        val lift = Vec3d(0.0, 0.05, 0.0)
        FxDraw.strip(
            f, listOf(end.add(lift), front.add(lift)), HEK_UP, width = { half * 0.9f },
            color = { s -> hekAlpha(palette.main, BED_ALPHA * fall * (1f - 0.3f * s)) }, mode = FxMode.RIBBON, seed = seed + 3,
        )
    }

    companion object {
        const val MIN_LIFE = 10
        const val DEMO_LENGTH = 20.0
        const val MAX_LENGTH = 40.0
        const val RUN = 4f
        const val CENTER_HEIGHT = 2.2f
        const val SIDE_HEIGHT = 1.3f
        const val SIDE_OFFSET = 0.55
        const val FLAME_ALPHA = 0.85f
        const val SIDE_ALPHA = 0.6f
        const val BED_ALPHA = 0.35f
    }
}

// =====================================================================================================================
// Dauer-Effekt je Hekate: Fackelflamme der Echten, kalter Mantel der Trugbilder
// =====================================================================================================================

/**
 * Leitet sein Bild allein aus dem Wesen ab (Nachzügler sehen es sofort):
 * - **Trugbild** ([HekateEntity.echo]): kalter Fresnel-Mantel (SHELL) um den Körper, blendet in [FADE_IN] ein.
 * - **Echte:** weiche Flammenkarte an der rechten Fackel (Lage aus [HekateTorches], nur wenn frisch). Die
 *   Größe folgt dem Kampfzustand (PLAN 2.1 Punkt 7): steigt beim Ausholen, fällt im offenen Fenster (Fackel geworfen,
 *   Taumeln), geglättet mit τ = [LEVEL_TAU] Ticks; keine Helligkeitsänderung schneller als die Glättung.
 */
private class HekatePresenceFx(private val entity: Entity) : FxEffect(LIFE) {
    private var level = REST
    private var lastTime = Float.NaN

    private fun targetLevel(e: HekateEntity): Float {
        val act = e.kit.action
        val age = e.kit.actionAge
        val hit = when (act) {
            HekateAction.TORCH_SWEEP -> HekateTimings.SWEEP_HIT2
            HekateAction.PATH -> HekateTimings.PATH_HIT
            HekateAction.KEY_TURN -> HekateTimings.KEY_HIT
            HekateAction.STEP -> HekateTimings.STEP_HIT
            HekateAction.TRIFORM -> HekateTimings.TRIFORM_HIT
            HekateAction.ECLIPSE -> HekateTimings.ECLIPSE_HIT
            HekateAction.HOWL -> HekateTimings.HOWL_HIT
            HekateAction.TORCH_THROW -> HekateTimings.THROW_HIT
            else -> -1
        }
        return when {
            act == HekateAction.STAGGER -> OPEN
            act == HekateAction.TORCH_THROW && age >= HekateTimings.THROW_HIT -> OPEN
            hit > 0 && age < hit -> CHARGE
            else -> REST
        }
    }

    override fun energy(f: FxFrame) {
        val e = entity as? HekateEntity ?: return
        val t = time(f)
        if (e.echo) {
            val fade = Ease.outQuad(t / FADE_IN)
            val feet = e.getLerpedPos(f.tickDelta)
            val m = Matrix4f().translation(feet.x.toFloat(), (feet.y + e.height * 0.52).toFloat(), feet.z.toFloat())
                .scale(MANTLE_W, e.height * 0.56f, MANTLE_W)
            FxDraw.mesh(f, FxMeshes.SPHERE, m, hekAlpha(HekateCold.MAIN, MANTLE_ALPHA * fade), FxMode.SHELL, seed = e.id)
            return
        }
        val dt = if (lastTime.isNaN()) 0f else (t - lastTime).coerceIn(0f, 4f)
        lastTime = t
        level += (targetLevel(e) - level) * (1f - exp(-dt / LEVEL_TAU))
        val tip = HekateTorches.tip(e.id, right = true) ?: return
        hekFlameCard(f, tip.add(0.0, -FLAME_SINK, 0.0), FLAME_HALF * (0.7f + 0.3f * level), FLAME_HEIGHT * level, hekAlpha(FLAME_COLOR, FLAME_ALPHA * (0.6f + 0.4f * level)), e.id)
    }

    companion object {
        /** Lange Lebenszeit; `FxAttach` erzeugt den Effekt danach neu. */
        const val LIFE = 20 * 60 * 10
        const val FADE_IN = 8f
        const val MANTLE_W = 0.8f
        const val MANTLE_ALPHA = 0.32f
        const val REST = 0.6f
        const val CHARGE = 1f
        const val OPEN = 0.3f
        const val LEVEL_TAU = 5f
        const val FLAME_HALF = 0.22f
        const val FLAME_HEIGHT = 0.75f
        const val FLAME_SINK = 0.15
        const val FLAME_ALPHA = 0.8f
        /** Farbe wie das Dauerlicht der Fackel (`BossGlow`, Mond-Palette zwischen Haupt- und Kernfarbe). */
        val FLAME_COLOR: Int = Palette.of(dev.henny.hha.logic.Vfx.Style.MOON).let { lerpColor(it.main, it.core, 0.4f) }
    }
}
