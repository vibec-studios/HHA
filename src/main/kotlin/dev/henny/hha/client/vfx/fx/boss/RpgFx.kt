package dev.henny.hha.client.vfx.fx.boss

import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMeshes
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.client.vfx.fx.FxRegistry
import dev.henny.hha.client.vfx.fx.FxSolidMode
import dev.henny.hha.client.vfx.fx.lib.FxLib
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Client-Effekte der RPG-Szenen (P17, Präfix `rpg.`). Nur für die Bühnen-Skripte `rpg.*` (`stage/scripts/RpgStages.kt`);
 * kein Kampf benutzt sie. Alles weich aus [FxDraw] (Röhren, Flächen, Scheine, glatte Meshes), ohne Raster, ohne
 * Zeit- oder Alpha-Stufen, ohne Blinken. Jeder Effekt blendet über eine glatte Hüllkurve ein und aus ([envelope]).
 * Kein Effekt hier ist ein Bildschirm-Pass; der einzige Pass der RPG-Szenen steht als `screen`-Spur in `rpg.end`.
 *
 * | ID | Kodierung | Bild |
 * | --- | --- | --- |
 * | `rpg.light_shaft` | pos = Boden, a = Radius, b = Höhe, c = 1 Lichtkörner steigen / 0 sinken, life | weiche Lichtsäule aus dem Himmel, heller Bodenschein, wandernde Lichtkörner |
 * | `rpg.feathers` | pos = Mitte oben, a = Radius, b = Anzahl (≤ 16), c = Fallhöhe, life | Federn sinken pendelnd und drehend, verblassen am Ende |
 * | `rpg.land_ring` | pos = Boden, a = Radius, life | flacher Lichtring läuft weich aus (Landung) |
 * | `rpg.beacon` | pos = Fuß, a = Radius, b = Höhe, life | schlanke Fernsäule (Ziel, Stil `GOLD`), Runenkreis am Fuß |
 * | `rpg.embers` | pos = Mitte, a = Radius, b = Anzahl (≤ 28), c = Steiggeschwindigkeit (< 0 = Asche sinkt), life | Glut bzw. Asche, kühlt über die Höhe ab |
 * | `rpg.gate_glow` | pos = Mitte der Öffnung, dir = Normale, a = Radius, life | Wirbel mit dunklem Auge in der Toröffnung, weicher Hof |
 * | `rpg.chain_fall` | pos = oberes Ende, a = Länge, b = Glieder (≤ 20), c = Fallhöhe, life | Kette aus glatten Ringen hängt kurz, fällt, liegt, vergeht |
 * | `rpg.rune_glow` | pos = Mitte, dir = Normale, a = Radius, life | Runenkreis zieht sich auf, Hof und Kern (Palastsiegel, Tiefe) |
 * | `rpg.light_tunnel` | pos = Fuß, a = Radius, b = Höhe, c = Ringe je Tick (Tempo), life | Lichtringe ziehen nach unten, zwei Lichtbänder winden sich: Aufstieg |
 */
object RpgFx {

    /** Alle IDs dieses Pakets (für Prüfungen). */
    val IDS = listOf(
        "rpg.light_shaft", "rpg.feathers", "rpg.land_ring", "rpg.beacon", "rpg.embers", "rpg.gate_glow",
        "rpg.chain_fall", "rpg.rune_glow", "rpg.light_tunnel",
    )

    fun init() {
        FxRegistry.register("rpg.light_shaft") { LightShaftFx(it) }
        FxRegistry.register("rpg.feathers") { FeathersFx(it) }
        FxRegistry.register("rpg.land_ring") { LandRingFx(it) }
        FxRegistry.register("rpg.beacon") { BeaconFx(it) }
        FxRegistry.register("rpg.embers") { EmbersFx(it) }
        FxRegistry.register("rpg.gate_glow") { GateGlowFx(it) }
        FxRegistry.register("rpg.chain_fall") { ChainFallFx(it) }
        FxRegistry.register("rpg.rune_glow") { RuneGlowFx(it) }
        FxRegistry.register("rpg.light_tunnel") { LightTunnelFx(it) }
    }

    /** Senkrecht nach oben. */
    internal val UP = Vec3d(0.0, 1.0, 0.0)

    /** Glatte Hüllkurve: steigt in [fadeIn] Ticks auf 1, fällt in den letzten [fadeOut] Ticks auf 0. */
    internal fun envelope(t: Float, life: Int, fadeIn: Float, fadeOut: Float): Float {
        val a = if (fadeIn <= 0f) 1f else Ease.inOut((t / fadeIn).coerceIn(0f, 1f))
        val b = if (fadeOut <= 0f) 1f else Ease.inOut(((life - t) / fadeOut).coerceIn(0f, 1f))
        return min(a, b)
    }

    /** Weiches Ein- und Ausblenden über einen Lauf 0..1 (für wandernde Teilchen, ohne Sprung am Umlauf). */
    internal fun bell(u: Float): Float = sin(PI.toFloat() * u.coerceIn(0f, 1f))

    /** Gebrochener Anteil ohne Raster (stetig, auch für negative Werte). */
    internal fun frac(v: Float): Float = v - kotlin.math.floor(v.toDouble()).toFloat()
}

// =====================================================================================================================
// rpg.light_shaft
// =====================================================================================================================

/**
 * Lichtsäule aus dem Himmel (Ankunft, Aufstieg im Ende): zwei weiche Röhren (weiter Hof, schmaler Kern), die sich nach
 * oben weiten und an beiden Enden ausblenden, ein heller Bodenschein und [MOTES] Lichtkörner, die stetig wandern.
 */
private class LightShaftFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val base = args.pos
    private val radius = args.a.coerceIn(0.5f, MAX_RADIUS).let { if (args.a <= 0f) DEFAULT_RADIUS else it }
    private val height = args.b.coerceIn(4f, MAX_HEIGHT).let { if (args.b <= 0f) DEFAULT_HEIGHT else it }
    private val rising = args.c >= 0.5f
    private val palette: Palette = args.palette
    private val seed = args.seed.toInt()

    private class Mote(val angle: Float, val r: Float, val speed: Float, val phase: Float, val size: Float)

    private val motes = Random(args.seed).let { rnd ->
        List(MOTES) { Mote(rnd.nextFloat() * 2f * PI.toFloat(), rnd.nextFloat(), 0.6f + 0.8f * rnd.nextFloat(), rnd.nextFloat(), 0.7f + 0.6f * rnd.nextFloat()) }
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val e = RpgFx.envelope(t, life, FADE_IN, FADE_OUT)
        if (e <= 0.001f) return
        val top = base.add(0.0, height.toDouble(), 0.0)
        val points = List(SEGMENTS + 1) { i -> base.lerp(top, i / SEGMENTS.toDouble()) }
        // Hof: weit, schwach; endet weich am Boden und verliert sich nach oben.
        FxDraw.tube(
            f, points, radius = { u -> radius * (0.8f + 0.35f * u) },
            color = { u -> FxLib.alpha(palette.main, HALO_ALPHA * e * ends(u)) }, mode = FxMode.RIBBON, sides = 12, seed = seed,
        )
        // Kern: schmaler und heller.
        FxDraw.tube(
            f, points, radius = { u -> radius * CORE_SHARE * (0.9f + 0.2f * u) },
            color = { u -> FxLib.alpha(palette.core, CORE_ALPHA * e * ends(u)) }, mode = FxMode.RIBBON, sides = 10, seed = seed + 1,
        )
        FxDraw.flat(f, base.add(0.0, GROUND_LIFT, 0.0), RpgFx.UP, radius * GROUND_SPREAD, FxLib.alpha(palette.core, GROUND_ALPHA * e), FxMode.GLOW, seed = seed)
        for ((i, m) in motes.withIndex()) {
            val run = RpgFx.frac(m.phase + t * MOTE_SPEED * m.speed / height)
            val y = if (rising) run else 1f - run
            val r = radius * 0.9f * m.r
            val a = m.angle + t * MOTE_SPIN
            val at = base.add((cos(a) * r).toDouble(), (y * height).toDouble(), (sin(a) * r).toDouble())
            FxDraw.glow(f, at, MOTE_SIZE * m.size, FxLib.alpha(palette.core, MOTE_ALPHA * e * RpgFx.bell(run)), seed + i)
        }
    }

    /** Ausblenden am Fuß (unterste [FOOT]) und zur Spitze hin (ab [TOP]). */
    private fun ends(u: Float): Float = min((u / FOOT).coerceIn(0f, 1f), ((1f - u) / (1f - TOP)).coerceIn(0f, 1f))

    companion object {
        const val MIN_LIFE = 20
        const val DEFAULT_RADIUS = 4f
        const val DEFAULT_HEIGHT = 40f
        const val MAX_RADIUS = 12f
        const val MAX_HEIGHT = 96f
        const val FADE_IN = 18f
        const val FADE_OUT = 30f
        const val SEGMENTS = 8
        const val HALO_ALPHA = 0.16f
        const val CORE_ALPHA = 0.26f
        const val CORE_SHARE = 0.42f
        const val FOOT = 0.08f
        const val TOP = 0.55f
        const val GROUND_LIFT = 0.04
        const val GROUND_SPREAD = 1.3f
        const val GROUND_ALPHA = 0.32f
        const val MOTES = 26
        const val MOTE_SPEED = 0.35f
        const val MOTE_SPIN = 0.006f
        const val MOTE_SIZE = 0.14f
        const val MOTE_ALPHA = 0.75f
    }
}

// =====================================================================================================================
// rpg.feathers
// =====================================================================================================================

/** Federn sinken pendelnd und drehend (Hermes, Ankunft). Je Feder ein kurzes, spitz zulaufendes Band. */
private class FeathersFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val center = args.pos
    private val radius = if (args.a <= 0f) DEFAULT_RADIUS else args.a.coerceIn(0.2f, MAX_RADIUS)
    private val count = if (args.b <= 0f) DEFAULT_COUNT else args.b.toInt().coerceIn(1, MAX_COUNT)
    private val drop = if (args.c <= 0f) DEFAULT_DROP else args.c.coerceIn(0.5f, MAX_DROP)
    private val palette = args.palette
    private val seed = args.seed.toInt()

    private class Feather(val offset: Vec3d, val sway: Float, val phase: Float, val spin: Float, val delay: Float, val length: Float)

    private val feathers = Random(args.seed).let { rnd ->
        List(count) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val r = radius * kotlin.math.sqrt(rnd.nextFloat())
            Feather(
                Vec3d((cos(a) * r).toDouble(), rnd.nextFloat() * 0.6, (sin(a) * r).toDouble()),
                SWAY * (0.6f + 0.8f * rnd.nextFloat()), rnd.nextFloat() * 6.28f, SPIN * (if (rnd.nextBoolean()) 1f else -1f) * (0.6f + 0.8f * rnd.nextFloat()),
                rnd.nextFloat() * DELAY_SPREAD, LENGTH * (0.75f + 0.5f * rnd.nextFloat()),
            )
        }
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val e = RpgFx.envelope(t, life, FADE_IN, life * FADE_SHARE)
        if (e <= 0.001f) return
        for ((i, fe) in feathers.withIndex()) {
            val s = (t - fe.delay).coerceAtLeast(0f)
            val k = (s / (life - fe.delay).coerceAtLeast(1f)).coerceIn(0f, 1f)
            // Fallen mit Luftwiderstand: erst schneller, dann gleichmäßig sanft (Ease.outQuad über die Lebenszeit).
            val fall = drop * Ease.outQuad(k)
            val swing = sin(s * SWING_SPEED + fe.phase)
            val side = Vec3d(cos(fe.phase.toDouble()), 0.0, sin(fe.phase.toDouble()))
            val mid = center.add(fe.offset).add(side.multiply((swing * fe.sway).toDouble())).add(0.0, -fall.toDouble(), 0.0)
            // Achse der Feder: neigt sich mit dem Pendeln, dreht sich langsam um die Senkrechte.
            val turn = s * fe.spin + fe.phase
            val tilt = TILT * swing
            val axis = Vec3d(cos(turn.toDouble()) * cos(tilt.toDouble()), sin(tilt.toDouble()) + 0.25, sin(turn.toDouble()) * cos(tilt.toDouble())).normalize()
            val half = axis.multiply(fe.length * 0.5)
            val pts = listOf(mid.subtract(half), mid.add(axis.multiply(fe.length * 0.08)), mid.add(half))
            val alpha = FEATHER_ALPHA * e * (if (s <= 0f) 0f else 1f)
            FxDraw.ribbon(
                f, pts, width = { u -> WIDTH * (0.25f + sin(PI.toFloat() * min(u * 1.25f, 1f))) },
                color = { u -> FxLib.alpha(FxLib.mix(palette.core, palette.main, u * 0.5f), alpha) }, mode = FxMode.RIBBON, seed = seed + i,
            )
        }
    }

    companion object {
        const val MIN_LIFE = 30
        const val DEFAULT_RADIUS = 1.4f
        const val MAX_RADIUS = 8f
        const val DEFAULT_COUNT = 10
        const val MAX_COUNT = 16
        const val DEFAULT_DROP = 3.5f
        const val MAX_DROP = 16f
        const val FADE_IN = 6f
        const val FADE_SHARE = 0.3f
        const val SWAY = 0.35f
        const val SWING_SPEED = 0.16f
        const val SPIN = 0.05f
        const val TILT = 0.6f
        const val DELAY_SPREAD = 10f
        const val LENGTH = 0.34f
        const val WIDTH = 0.06f
        const val FEATHER_ALPHA = 0.85f
    }
}

// =====================================================================================================================
// rpg.land_ring
// =====================================================================================================================

/** Flacher Lichtring am Boden, der weich ausläuft (Landung der Gruppe), dazu ein kurzer Bodenschein. */
private class LandRingFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val center = args.pos.add(0.0, LIFT, 0.0)
    private val radius = if (args.a <= 0f) DEFAULT_RADIUS else args.a.coerceIn(0.5f, MAX_RADIUS)
    private val palette = args.palette
    private val seed = args.seed.toInt()

    override fun energy(f: FxFrame) {
        val k = k(f)
        val grow = Ease.outCubic(k)
        val fade = (1f - k) * (1f - k)
        val outer = radius * (START + (1f - START) * grow)
        FxDraw.flat(f, center, RpgFx.UP, outer, FxLib.alpha(palette.main, RING_ALPHA * fade), FxMode.SHOCK, inner = outer * WIDTH, seed = seed)
        FxDraw.flat(f, center, RpgFx.UP, radius * GLOW_SHARE * (0.6f + 0.4f * grow), FxLib.alpha(palette.core, GLOW_ALPHA * fade * fade), FxMode.GLOW, seed = seed + 1)
    }

    companion object {
        const val MIN_LIFE = 16
        const val DEFAULT_RADIUS = 6f
        const val MAX_RADIUS = 16f
        const val LIFT = 0.05
        const val START = 0.15f
        const val WIDTH = 0.18f
        const val RING_ALPHA = 0.6f
        const val GLOW_SHARE = 0.7f
        const val GLOW_ALPHA = 0.4f
    }
}

// =====================================================================================================================
// rpg.beacon
// =====================================================================================================================

/** Schlanke Fernsäule zum Ziel (Stil `GOLD` = Ziel), Runenkreis und Schein am Fuß. Atmet kaum merklich. */
private class BeaconFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val base = args.pos
    private val radius = if (args.a <= 0f) DEFAULT_RADIUS else args.a.coerceIn(0.2f, MAX_RADIUS)
    private val height = if (args.b <= 0f) DEFAULT_HEIGHT else args.b.coerceIn(4f, MAX_HEIGHT)
    private val palette = args.palette
    private val seed = args.seed.toInt()

    override fun energy(f: FxFrame) {
        val t = time(f)
        val e = RpgFx.envelope(t, life, FADE_IN, FADE_OUT)
        if (e <= 0.001f) return
        val breath = 1f + BREATH * sin(t * BREATH_SPEED)
        // Die Säule wächst beim Erscheinen von unten nach oben.
        val rise = Ease.outCubic((t / RISE_TICKS).coerceIn(0f, 1f))
        val top = base.add(0.0, (height * rise).toDouble(), 0.0)
        val points = List(SEGMENTS + 1) { i -> base.lerp(top, i / SEGMENTS.toDouble()) }
        FxDraw.tube(
            f, points, radius = { u -> radius * breath * (1f - 0.35f * u) },
            color = { u -> FxLib.alpha(FxLib.mix(palette.core, palette.main, u), COLUMN_ALPHA * e * (1f - u * u)) }, mode = FxMode.RIBBON, seed = seed,
        )
        FxDraw.flat(
            f, base.add(0.0, LIFT, 0.0), RpgFx.UP, RUNE_RADIUS, FxLib.alpha(palette.main, RUNE_ALPHA * e), FxMode.RUNE,
            inner = RUNE_RADIUS * RUNE_INNER, rotation = t * RUNE_SPIN, progress = Ease.outCubic((t / RUNE_DRAW).coerceIn(0f, 1f)), seed = seed,
        )
        FxDraw.glow(f, base.add(0.0, GLOW_UP, 0.0), GLOW_SIZE * breath, FxLib.alpha(palette.core, GLOW_ALPHA * e), seed)
    }

    companion object {
        const val MIN_LIFE = 30
        const val DEFAULT_RADIUS = 0.6f
        const val MAX_RADIUS = 3f
        const val DEFAULT_HEIGHT = 48f
        const val MAX_HEIGHT = 128f
        const val FADE_IN = 14f
        const val FADE_OUT = 24f
        const val RISE_TICKS = 18f
        const val SEGMENTS = 10
        const val COLUMN_ALPHA = 0.55f
        const val BREATH = 0.06f
        const val BREATH_SPEED = 0.09f
        const val LIFT = 0.05
        const val RUNE_RADIUS = 2.2f
        const val RUNE_INNER = 0.8f
        const val RUNE_SPIN = 0.01f
        const val RUNE_DRAW = 24f
        const val RUNE_ALPHA = 0.5f
        const val GLOW_UP = 0.6
        const val GLOW_SIZE = 1.6f
        const val GLOW_ALPHA = 0.45f
    }
}

// =====================================================================================================================
// rpg.embers
// =====================================================================================================================

/**
 * Glut (steigt, [rate] > 0) bzw. Asche (sinkt, [rate] < 0) um einen Punkt: kleine Scheine, die pendeln und über ihren
 * Lauf von Kern- über Haupt- zur Randfarbe abkühlen. Jedes Korn hat einen eigenen Lauf, also kein Takt im Bild.
 */
private class EmbersFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val center = args.pos
    private val radius = if (args.a <= 0f) DEFAULT_RADIUS else args.a.coerceIn(0.3f, MAX_RADIUS)
    private val count = if (args.b <= 0f) DEFAULT_COUNT else args.b.toInt().coerceIn(1, MAX_COUNT)
    private val rate = if (args.c == 0f) DEFAULT_RATE else args.c.coerceIn(-MAX_RATE, MAX_RATE)
    private val palette = args.palette
    private val seed = args.seed.toInt()

    private class Grain(val x: Double, val z: Double, val phase: Float, val speed: Float, val size: Float, val sway: Float)

    private val grains = Random(args.seed).let { rnd ->
        List(count) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val r = radius * kotlin.math.sqrt(rnd.nextFloat())
            Grain((cos(a) * r).toDouble(), (sin(a) * r).toDouble(), rnd.nextFloat(), 0.6f + 0.8f * rnd.nextFloat(), 0.6f + 0.8f * rnd.nextFloat(), rnd.nextFloat() * 6.28f)
        }
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val e = RpgFx.envelope(t, life, FADE_IN, FADE_OUT)
        if (e <= 0.001f) return
        val span = SPAN
        for ((i, g) in grains.withIndex()) {
            val run = RpgFx.frac(g.phase + t * kotlin.math.abs(rate) * g.speed / span)
            val h = if (rate > 0f) run * span else (1f - run) * span
            val drift = sin(t * DRIFT_SPEED + g.sway) * DRIFT
            val at = center.add(g.x + drift, h.toDouble(), g.z + cos(t * DRIFT_SPEED * 0.8f + g.sway) * DRIFT)
            val color = if (run < 0.5f) FxLib.mix(palette.core, palette.main, run * 2f) else FxLib.mix(palette.main, palette.edge, (run - 0.5f) * 2f)
            FxDraw.glow(f, at, SIZE * g.size * (1f - 0.4f * run), FxLib.alpha(color, ALPHA * e * RpgFx.bell(run)), seed + i)
        }
    }

    companion object {
        const val MIN_LIFE = 20
        const val DEFAULT_RADIUS = 3f
        const val MAX_RADIUS = 16f
        const val DEFAULT_COUNT = 16
        const val MAX_COUNT = 28
        const val DEFAULT_RATE = 0.06f
        const val MAX_RATE = 0.3f
        const val SPAN = 4.5f
        const val FADE_IN = 16f
        const val FADE_OUT = 24f
        const val DRIFT = 0.25
        const val DRIFT_SPEED = 0.07f
        const val SIZE = 0.11f
        const val ALPHA = 0.8f
    }
}

// =====================================================================================================================
// rpg.gate_glow
// =====================================================================================================================

/** Das offene Höllentor: Wirbel mit dunklem Auge in der Toröffnung, öffnet weich, weicher Hof, ein Kernschein. */
private class GateGlowFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val center = args.pos
    private val normal = FxLib.unit(args.dir, Vec3d(1.0, 0.0, 0.0))
    private val radius = if (args.a <= 0f) DEFAULT_RADIUS else args.a.coerceIn(0.5f, MAX_RADIUS)
    private val palette = args.palette
    private val seed = args.seed.toInt()

    override fun energy(f: FxFrame) {
        val t = time(f)
        val e = RpgFx.envelope(t, life, FADE_IN, FADE_OUT)
        if (e <= 0.001f) return
        val open = Ease.outCubic((t / OPEN_TICKS).coerceIn(0f, 1f))
        FxDraw.flat(
            f, center, normal, radius * (0.6f + 0.4f * open), FxLib.alpha(palette.main, SWIRL_ALPHA * e), FxMode.SWIRL,
            inner = radius * EYE * open, rotation = t * SPIN, progress = open, seed = seed,
        )
        FxDraw.flat(f, center, normal, radius * HALO, FxLib.alpha(palette.edge, HALO_ALPHA * e), FxMode.GLOW, seed = seed + 1)
        FxDraw.glow(f, center, radius * CORE, FxLib.alpha(palette.core, CORE_ALPHA * e * open), seed)
    }

    companion object {
        const val MIN_LIFE = 30
        const val DEFAULT_RADIUS = 3.5f
        const val MAX_RADIUS = 12f
        const val FADE_IN = 16f
        const val FADE_OUT = 24f
        const val OPEN_TICKS = 30f
        const val SWIRL_ALPHA = 0.65f
        const val EYE = 0.32f
        const val SPIN = 0.02f
        const val HALO = 1.7f
        const val HALO_ALPHA = 0.3f
        const val CORE = 0.35f
        const val CORE_ALPHA = 0.25f
    }
}

// =====================================================================================================================
// rpg.chain_fall
// =====================================================================================================================

/**
 * Die Kette fällt (Palastsiegel): glatte Ringe (Torus, fest schattiert), abwechselnd um 90° gedreht, hängen
 * [HANG_TICKS] Ticks mit glühendem Saum, fallen dann mit Schwerkraft, lösen sich leicht voneinander, liegen am Boden
 * ([drop] tiefer) und schrumpfen am Ende weich weg.
 */
private class ChainFallFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val top = args.pos
    private val length = if (args.a <= 0f) DEFAULT_LENGTH else args.a.coerceIn(1f, MAX_LENGTH)
    private val count = if (args.b <= 0f) (length / LINK_STEP).toInt().coerceIn(2, MAX_LINKS) else args.b.toInt().coerceIn(2, MAX_LINKS)
    private val drop = if (args.c <= 0f) length + DEFAULT_DROP_EXTRA else args.c.coerceIn(1f, MAX_DROP)
    private val palette = args.palette
    private val seed = args.seed.toInt()
    private val floorY = top.y - drop

    private class Link(val hang: Vec3d, val side: Vec3d, val spin: Float, val tumble: Float)

    private val links = Random(args.seed).let { rnd ->
        val step = length / count
        List(count) { i ->
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            Link(
                top.add(0.0, -(i + 0.5) * step.toDouble(), 0.0),
                Vec3d(cos(a).toDouble(), 0.0, sin(a).toDouble()).multiply(SCATTER * (0.4 + 0.6 * rnd.nextFloat())),
                rnd.nextFloat() * 6.28f, TUMBLE * (if (rnd.nextBoolean()) 1f else -1f) * (0.5f + rnd.nextFloat()),
            )
        }
    }

    /** Lage und Achse des Glieds [i] zur Zeit [t]. */
    private fun pose(i: Int, l: Link, t: Float): Pair<Vec3d, Vec3d> {
        val baseAxis = if (i % 2 == 0) Vec3d(1.0, 0.0, 0.0) else Vec3d(0.0, 0.0, 1.0)
        val s = t - HANG_TICKS
        if (s <= 0f) return l.hang to baseAxis
        val y0 = l.hang.y
        val y = max(floorY + LINK_RADIUS * 0.5, y0 - 0.5 * GRAVITY * s * s)
        val falling = y > floorY + LINK_RADIUS * 0.5 + 1e-3
        val sideways = l.side.multiply(min(s, LAND_ESTIMATE).toDouble())
        val ang = (if (falling) s else LAND_ESTIMATE) * l.tumble + l.spin
        val axis = Vec3d(cos(ang.toDouble()) * baseAxis.x - sin(ang.toDouble()) * 0.3, sin(ang.toDouble()), cos(ang.toDouble()) * baseAxis.z + sin(ang.toDouble()) * 0.3)
        return Vec3d(l.hang.x + sideways.x, y, l.hang.z + sideways.z) to FxLib.unit(axis)
    }

    private fun shrink(t: Float): Float = ((life - t) / SHRINK_TICKS).coerceIn(0f, 1f).let(Ease::inOut)

    override fun solid(f: FxFrame) {
        val t = time(f)
        val scale = LINK_RADIUS * shrink(t) * Ease.outCubic((t / APPEAR_TICKS).coerceIn(0f, 1f))
        if (scale <= 0.005f) return
        for ((i, l) in links.withIndex()) {
            val (at, axis) = pose(i, l, t)
            FxDraw.mesh(f, FxMeshes.TORUS, FxDraw.place(at, scale, axis), IRON, FxSolidMode.PLAIN, solid = true, seed = seed + i)
        }
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        // Glühender Saum, solange sie hängt, verglüht im Fall.
        val heat = (1f - ((t - HANG_TICKS) / COOL_TICKS).coerceIn(0f, 1f)) * Ease.outCubic((t / APPEAR_TICKS).coerceIn(0f, 1f)) * shrink(t)
        if (heat <= 0.005f) return
        for ((i, l) in links.withIndex()) {
            val (at, axis) = pose(i, l, t)
            FxDraw.mesh(f, FxMeshes.TORUS, FxDraw.place(at, LINK_RADIUS * RIM, axis), FxLib.alpha(palette.main, RIM_ALPHA * heat), FxMode.SHELL, seed = seed + i)
        }
        FxDraw.glow(f, top.add(0.0, -length * 0.5, 0.0), length * 0.35f, FxLib.alpha(palette.core, GLOW_ALPHA * heat), seed)
    }

    companion object {
        const val MIN_LIFE = 40
        const val DEFAULT_LENGTH = 8f
        const val MAX_LENGTH = 24f
        const val MAX_LINKS = 20
        const val LINK_STEP = 0.55f
        const val LINK_RADIUS = 0.34f
        const val DEFAULT_DROP_EXTRA = 6f
        const val MAX_DROP = 48f
        const val HANG_TICKS = 18f
        const val APPEAR_TICKS = 8f
        const val GRAVITY = 0.045f
        const val SCATTER = 0.035
        const val TUMBLE = 0.12f
        const val LAND_ESTIMATE = 22f
        const val COOL_TICKS = 30f
        const val SHRINK_TICKS = 14f
        const val RIM = 1.12f
        const val RIM_ALPHA = 0.45f
        const val GLOW_ALPHA = 0.25f
        /** Dunkles Eisen, fest schattiert. */
        val IRON = 0xFF3A3A44.toInt()
    }
}

// =====================================================================================================================
// rpg.rune_glow
// =====================================================================================================================

/** Runenkreis, der sich weich aufzieht und langsam dreht, mit weitem Hof und Kernschein (Palastsiegel, Tiefe). */
private class RuneGlowFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val center = args.pos
    private val normal = FxLib.unit(args.dir, RpgFx.UP)
    private val radius = if (args.a <= 0f) DEFAULT_RADIUS else args.a.coerceIn(0.5f, MAX_RADIUS)
    private val palette = args.palette
    private val seed = args.seed.toInt()

    override fun energy(f: FxFrame) {
        val t = time(f)
        val e = RpgFx.envelope(t, life, FADE_IN, FADE_OUT)
        if (e <= 0.001f) return
        val draw = Ease.outCubic((t / DRAW_TICKS).coerceIn(0f, 1f))
        val breath = 1f + BREATH * sin(t * BREATH_SPEED)
        FxDraw.flat(
            f, center, normal, radius * breath, FxLib.alpha(palette.main, RUNE_ALPHA * e), FxMode.RUNE,
            inner = radius * INNER, rotation = t * SPIN, progress = draw, seed = seed,
        )
        FxDraw.flat(f, center, normal, radius * HALO, FxLib.alpha(palette.edge, HALO_ALPHA * e * draw), FxMode.GLOW, seed = seed + 1)
        FxDraw.glow(f, center, radius * CORE * breath, FxLib.alpha(palette.core, CORE_ALPHA * e * draw), seed)
    }

    companion object {
        const val MIN_LIFE = 30
        const val DEFAULT_RADIUS = 4f
        const val MAX_RADIUS = 24f
        const val FADE_IN = 16f
        const val FADE_OUT = 30f
        const val DRAW_TICKS = 40f
        const val BREATH = 0.03f
        const val BREATH_SPEED = 0.08f
        const val INNER = 0.82f
        const val SPIN = 0.008f
        const val RUNE_ALPHA = 0.7f
        const val HALO = 1.8f
        const val HALO_ALPHA = 0.3f
        const val CORE = 0.45f
        const val CORE_ALPHA = 0.35f
    }
}

// =====================================================================================================================
// rpg.light_tunnel
// =====================================================================================================================

/**
 * Aufstieg durch einen Tunnel aus Licht (Ende): [RINGS] weiche Lichtringe ziehen stetig nach unten (die Kamera steigt
 * gegen sie), zwei Lichtbänder winden sich um die Achse und drehen langsam. Entspricht im Bild dem Tunnel der Lobby.
 */
private class LightTunnelFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val base = args.pos
    private val radius = if (args.a <= 0f) DEFAULT_RADIUS else args.a.coerceIn(1f, MAX_RADIUS)
    private val height = if (args.b <= 0f) DEFAULT_HEIGHT else args.b.coerceIn(8f, MAX_HEIGHT)
    private val speed = if (args.c <= 0f) DEFAULT_SPEED else args.c.coerceIn(0.001f, MAX_SPEED)
    private val palette = args.palette
    private val seed = args.seed.toInt()

    override fun energy(f: FxFrame) {
        val t = time(f)
        val e = RpgFx.envelope(t, life, FADE_IN, FADE_OUT)
        if (e <= 0.001f) return
        // Tempo zieht an: erst langsam, dann voll (Ease.inQuad über die ersten RAMP Ticks, als Weg integriert).
        val travel = if (t < RAMP) speed * t * t / (2f * RAMP) else speed * (t - RAMP / 2f)
        for (i in 0 until RINGS) {
            val run = RpgFx.frac(i / RINGS.toFloat() - travel)
            val at = base.add(0.0, (run * height).toDouble(), 0.0)
            val r = radius * (0.85f + 0.3f * run)
            FxDraw.flat(f, at, RpgFx.UP, r, FxLib.alpha(FxLib.mix(palette.core, palette.main, run), RING_ALPHA * e * RpgFx.bell(run)), FxMode.SHOCK, inner = r * RING_WIDTH, seed = seed + i)
        }
        for (strand in 0 until STRANDS) {
            val offset = strand * PI.toFloat()
            val pts = List(STRAND_POINTS) { j ->
                val u = j / (STRAND_POINTS - 1f)
                val a = offset + u * TURNS * 2f * PI.toFloat() + t * TWIST
                base.add((cos(a) * radius * 0.92f).toDouble(), (u * height).toDouble(), (sin(a) * radius * 0.92f).toDouble())
            }
            FxDraw.ribbon(
                f, pts, width = { u -> STRAND_WIDTH * (0.6f + 0.4f * sin(PI.toFloat() * u)) },
                color = { u -> FxLib.alpha(palette.core, STRAND_ALPHA * e * RpgFx.bell(u)) }, mode = FxMode.RIBBON, scroll = -t * 0.2f, seed = seed + 50 + strand,
            )
        }
    }

    companion object {
        const val MIN_LIFE = 40
        const val DEFAULT_RADIUS = 4.5f
        const val MAX_RADIUS = 12f
        const val DEFAULT_HEIGHT = 48f
        const val MAX_HEIGHT = 120f
        const val DEFAULT_SPEED = 0.02f
        const val MAX_SPEED = 0.2f
        const val RAMP = 40f
        const val FADE_IN = 20f
        const val FADE_OUT = 30f
        const val RINGS = 12
        const val RING_WIDTH = 0.12f
        const val RING_ALPHA = 0.5f
        const val STRANDS = 2
        const val STRAND_POINTS = 40
        const val TURNS = 3f
        const val TWIST = 0.015f
        const val STRAND_WIDTH = 0.18f
        const val STRAND_ALPHA = 0.32f
    }
}
