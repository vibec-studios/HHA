package dev.henny.hha.client.vfx.fx.boss

import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.client.vfx.fx.FxRegistry
import dev.henny.hha.client.vfx.fx.lib.FxLib
import dev.henny.hha.client.vfx.fx.lib.SparkSet
import dev.henny.hha.logic.Vfx
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Client-Effekte von Hermes (P07, Präfix `hermes.`), aufgerufen aus `FxClient.init()`. Der Server schickt sie über
 * `HermesLook` (`logic/boss/HermesAttacks.kt`); die Kodierung steht hier je Effekt und dort noch einmal.
 *
 * | ID | Bild |
 * | --- | --- |
 * | [STREAK] `hermes.streak` | gerade Lichtbahn eines Stoßes: Kopf sitzt **am Läufer** (Lage des Wesens auf der Bahn), der Schweif läuft getrennt hinterher, holt am Ende auf; dort 6 Funken |
 * | [LANE] `hermes.lane` | Kielwasser einer Caduceus-Schlange genau auf den Stützpunkten der Bahn, die der Server fliegt |
 * | [STAR] `hermes.star` | kurzer Lichtstern (Ziel des Blinzelns) |
 * | [GUST] `hermes.gust` | Windstoß der Botenglocken (Überarbeitung 4, P14): drei geschwungene Luftbänder laufen die dritte Linie entlang auf Hermes zu, verwehen dort |
 *
 * Alles weich: stetige Zeit, Smoothstep/Ease-Kurven, keine Zeit- oder Alpha-Stufen, kein Blinken.
 */
object HermesFx {

    const val STREAK = "hermes.streak"
    const val LANE = "hermes.lane"
    const val STAR = "hermes.star"
    const val GUST = "hermes.gust"

    fun init() {
        FxRegistry.register(STREAK) { StreakFx(it) }
        FxRegistry.register(LANE) { LaneFx(it) }
        FxRegistry.register(STAR) { StarFx(it) }
        FxRegistry.register(GUST) { GustFx(it) }
    }

    /** Liest die Punkte aus `extra` (`x,y,z;x,y,z;…`, `Locale.ROOT`); kaputte Einträge fallen weg. */
    internal fun decodePoints(extra: String): List<Vec3d> = extra.split(';').mapNotNull { entry ->
        val p = entry.split(',')
        if (p.size != 3) return@mapNotNull null
        val x = p[0].toDoubleOrNull() ?: return@mapNotNull null
        val y = p[1].toDoubleOrNull() ?: return@mapNotNull null
        val z = p[2].toDoubleOrNull() ?: return@mapNotNull null
        Vec3d(x, y, z)
    }

    /** Gegenstück zu [decodePoints] (für Prüfungen und die Vorschau). */
    internal fun encodePoints(points: List<Vec3d>): String =
        points.joinToString(";") { String.format(Locale.ROOT, "%.2f,%.2f,%.2f", it.x, it.y, it.z) }

    /** Energie-Modus nach Stil: Sturm/Donner als zuckender Blitz, sonst glattes Band. */
    internal fun modeOf(style: Vfx.Style): Int =
        if (style == Vfx.Style.STORM || style == Vfx.Style.THUNDER) FxMode.BOLT else FxMode.RIBBON
}

// -------------------------------------------------------------------------------------------------------------------
// hermes.streak
// -------------------------------------------------------------------------------------------------------------------

/**
 * Gerade Lichtbahn eines Stoßes (Kurierstoß, Spiegel-Stoß, Sturzflug, Konter, Auftritt). Ersetzt das alte `trail`.
 *
 * Kodierung: pos = Start der Bahn, dir = Ende (absolut), a = halbe Breite, b = Flugzeit in Ticks (Rückfall, solange das
 * Wesen fehlt), c = Höhe des Bandes über den Füßen, life = b + [TAIL_OUT] + Reserve, entity = Läufer.
 *
 * Der Kopf ist die Lage des Läufers, auf die Bahn projiziert (also genau dort, wo der Server ihn bewegt und trifft);
 * er läuft nie zurück. Der Schweif folgt dem Kopf mit [TAIL_DELAY] Ticks Verzug, hält also Abstand, solange der
 * Läufer fliegt. Steht der Kopf (Bahnende oder abgefangen), sprühen 6 Funken und der Schweif holt in [TAIL_OUT]
 * Ticks weich auf; dann ist der Effekt fertig.
 */
private class StreakFx(args: FxArgs) : FxEffect(max(args.life, MIN_LIFE)) {
    private val palette: Palette = args.palette
    private val from = args.pos

    /**
     * Ende der Bahn. Der Server schickt einen absoluten Punkt; `/hha fx play` schickt nur die Blickrichtung (Länge 1) –
     * dann eine Vorführbahn von [DEMO_LENGTH] Blöcken in diese Richtung.
     */
    private val to = if (args.dir.lengthSquared() <= 1.0001 && args.pos.lengthSquared() > 4.0) args.pos.add(args.dir.multiply(DEMO_LENGTH)) else args.dir
    private val width = args.a.coerceIn(0.1f, 1.5f)
    private val travel = args.b.coerceAtLeast(1f)
    private val lift = args.c.toDouble().coerceIn(0.0, 4.0)
    private val runnerId = args.entityId
    private var runner: Entity? = args.entity
    private val world = args.world
    private val seed = args.seed.toInt()
    private val mode = HermesFx.modeOf(args.style)
    private val length = from.distanceTo(to)
    private val way = FxLib.unit(to.subtract(from), FxLib.UP)

    /** Kopf je Tick (Anteil 0..1 der Bahn), Index = Alter. */
    private val heads = FloatArray(max(args.life, MIN_LIFE) + 2)
    private var head = 0f
    private var lastMove = 0
    private var stopAge = -1
    private var tailAtStop = 0f
    private val sparks = SparkSet(END_SPARKS, SPARK_SPEED, way.add(0.0, 0.35, 0.0), args.seed)

    private fun runnerK(): Float? {
        val e = runner?.takeIf { !it.isRemoved } ?: world.getEntityById(runnerId)?.also { runner = it } ?: return null
        if (length < 1e-3) return 1f
        return (e.entityPos.subtract(from).dotProduct(way) / length).toFloat().coerceIn(0f, 1f)
    }

    override fun tick(world: ClientWorld) {
        val target = runnerK() ?: (age / travel).coerceAtMost(1f)
        if (target > head + MOVE_EPS) {
            head = target
            lastMove = age
        }
        if (age < heads.size) heads[age] = head
        if (stopAge < 0 && age > 1 && (head >= 0.999f || age - lastMove >= STALL_TICKS)) {
            stopAge = age
            tailAtStop = tailBefore(age.toFloat())
        }
    }

    /** Kopf bei Zeit [t] (Ticks), aus der Liste je Tick weich zwischen den Ticks gemischt. */
    private fun headAt(t: Float): Float {
        if (t <= 0f) return 0f
        val i = min(t.toInt(), age.coerceAtMost(heads.size - 1))
        val j = min(i + 1, age.coerceAtMost(heads.size - 1))
        return heads[i] + (heads[j] - heads[i]) * (t - t.toInt()).coerceIn(0f, 1f)
    }

    /** Schweif vor dem Stopp: der Kopf von vor [TAIL_DELAY] Ticks. */
    private fun tailBefore(t: Float): Float = headAt(t - 1f - TAIL_DELAY)

    /**
     * (Schweif, Kopf, Deckkraft) zur Zeit [t]. Der Kopf ist um einen Tick verzögert gelesen: So steht er genau dort,
     * wo das Wesen zwischen zwei Ticks gezeichnet wird (Lage von Tick n − 1 nach n gemischt).
     */
    private fun spans(t: Float): Triple<Float, Float, Float> {
        val h = headAt(t - 1f)
        if (stopAge < 0) return Triple(min(tailBefore(t), h), h, Ease.outCubic(t / RISE_TICKS))
        val k = Ease.inOut((t - stopAge) / TAIL_OUT)
        val tail = tailAtStop + (h - tailAtStop) * k
        return Triple(tail, h, (1f - k) * Ease.outCubic(t / RISE_TICKS))
    }

    private fun point(s: Float): Vec3d = from.lerp(to, s.toDouble()).add(0.0, lift, 0.0)

    override fun energy(f: FxFrame) {
        val t = time(f)
        val (tail, h, alpha) = spans(t)
        if (alpha > 0.01f && h - tail > 1e-3f) {
            val (u, v) = FxLib.basis(way)
            val span = ((h - tail) * length).toFloat()
            val jitter = (JITTER * span).coerceAtMost(JITTER_MAX)
            val points = List(POINTS) { i ->
                val s = i / (POINTS - 1f)
                val taper = 1f - abs(s - 0.5f) * 2f
                val wu = Ease.wobble(t * WOBBLE_SPEED + i * 0.8f, seed * 0.31f) * jitter * taper
                val wv = Ease.wobble(t * WOBBLE_SPEED + i * 0.8f, seed * 0.31f + 4.7f) * jitter * taper
                point(tail + (h - tail) * s).add(u.multiply(wu.toDouble())).add(v.multiply(wv.toDouble()))
            }
            FxDraw.ribbon(
                f, points,
                width = { s -> width * (0.15f + 0.85f * Ease.outQuad(s)) },
                color = { s -> FxLib.alpha(FxLib.mix(palette.edge, palette.main, s), alpha * (0.15f + 0.6f * s)) },
                mode = mode, scroll = t * SCROLL, seed = seed,
            )
            FxDraw.ribbon(
                f, points,
                width = { s -> width * CORE_WIDTH * (0.1f + 0.9f * s) },
                color = { s -> FxLib.alpha(palette.core, alpha * (0.1f + 0.8f * s)) },
                mode = mode, scroll = t * SCROLL, seed = seed + 3,
            )
        }
        if (stopAge >= 0) {
            val st = t - stopAge
            sparks.draw(f, point(head), st, SPARK_LIFE, 1f - Ease.inCubic(st / SPARK_LIFE), palette)
        }
    }

    override fun over(f: FxFrame) {
        val t = time(f)
        val (_, h, alpha) = spans(t)
        if (alpha <= 0.01f) return
        FxDraw.glow(f, point(h), width * HEAD_GLOW, FxLib.alpha(palette.core, HEAD_ALPHA * alpha), seed)
    }

    override fun finished(): Boolean = stopAge >= 0 && age - stopAge > max(TAIL_OUT, SPARK_LIFE)

    companion object {
        const val MIN_LIFE = 8
        const val DEMO_LENGTH = 8.0
        /** Der Schweif hängt so viele Ticks hinter dem Kopf. */
        const val TAIL_DELAY = 4f
        /** So lange holt der Schweif nach dem Stopp auf. */
        const val TAIL_OUT = 9f
        /** Steht der Kopf so viele Ticks, gilt der Stoß als beendet (abgefangen). */
        const val STALL_TICKS = 2
        const val MOVE_EPS = 1e-4f
        const val RISE_TICKS = 3f
        const val POINTS = 18
        const val JITTER = 0.025f
        const val JITTER_MAX = 0.18f
        const val WOBBLE_SPEED = 0.8f
        const val CORE_WIDTH = 0.36f
        const val SCROLL = 0.12f
        const val HEAD_GLOW = 2.4f
        const val HEAD_ALPHA = 0.55f
        /** PLAN 2.4: „am Ende 6 Funken“. */
        const val END_SPARKS = 6
        const val SPARK_SPEED = 0.42f
        const val SPARK_LIFE = 16f
    }
}

// -------------------------------------------------------------------------------------------------------------------
// hermes.lane
// -------------------------------------------------------------------------------------------------------------------

/**
 * Kielwasser einer Schlange in „Die Zwillinge“. Die Bahn kommt als Stützpunkte vom Server (dieselbe Liste, auf der
 * `HermesAttacks.moveSerpent` den Kopf bewegt: geradlinig zwischen den Punkten), der Fortschritt nach derselben Regel:
 * hinaus in `c` Ticks (k / c), zurück in `d` Ticks (1 − k / d). Gezeichnet wird nur das Stück, das der Kopf in den
 * letzten [WAKE_TICKS] Ticks durchlaufen hat – also hinter der Schlange, nie vor ihr auf der Ansage.
 *
 * Kodierung: pos = erster Punkt, a = halbe Breite, b = Höhe über der Bahn, c = Ticks hinaus, d = Ticks zurück,
 * life = c + d + [FADE], extra = Punkte (`x,y,z;…`).
 */
private class LaneFx(args: FxArgs) : FxEffect(max(args.life, 4)) {
    private val palette = args.palette
    private val lane = HermesFx.decodePoints(args.extra).ifEmpty { listOf(args.pos, args.pos) }
    private val width = args.a.coerceIn(0.05f, 1f)
    private val lift = args.b.toDouble().coerceIn(0.0, 3.0)
    private val out = args.c.coerceAtLeast(1f)
    private val back = args.d.coerceAtLeast(1f)
    private val seed = args.seed.toInt()
    private val mode = HermesFx.modeOf(args.style)

    /** Fortschritt des Kopfes wie am Server (Tick 1 = erster Schritt). */
    private fun headProgress(t: Float): Float = when {
        t <= 0f -> 0f
        t <= out -> t / out
        else -> 1f - ((t - out) / back).coerceAtMost(1f)
    }

    private fun pointAt(p: Float): Vec3d {
        if (lane.size < 2) return lane.first()
        val x = p.coerceIn(0f, 1f) * (lane.size - 1)
        val i = min(x.toInt(), lane.size - 2)
        return lane[i].lerp(lane[i + 1], (x - i).toDouble()).add(0.0, lift, 0.0)
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val end = out + back
        val fade = 1f - Ease.inOut((t - end) / FADE)
        if (fade <= 0.01f) return
        val now = t.coerceAtMost(end)
        val start = (now - WAKE_TICKS).coerceAtLeast(0f)
        if (now - start < 0.05f) return
        val points = List(POINTS) { i -> pointAt(headProgress(start + (now - start) * i / (POINTS - 1f))) }
        val rise = Ease.outCubic(t / 3f)
        FxDraw.ribbon(
            f, points,
            width = { s -> width * (0.1f + 0.9f * s) },
            color = { s -> FxLib.alpha(FxLib.mix(palette.edge, palette.main, s), fade * rise * ALPHA * s) },
            mode = mode, scroll = t * 0.1f, seed = seed,
        )
        FxDraw.ribbon(
            f, points,
            width = { s -> width * 0.35f * s },
            color = { s -> FxLib.alpha(palette.core, fade * rise * ALPHA * s) },
            mode = mode, scroll = t * 0.1f, seed = seed + 5,
        )
    }

    companion object {
        /** Länge des Kielwassers in Ticks Kopfweg. */
        const val WAKE_TICKS = 7f
        const val FADE = 8f
        const val POINTS = 16
        const val ALPHA = 0.55f
    }
}

// -------------------------------------------------------------------------------------------------------------------
// hermes.star
// -------------------------------------------------------------------------------------------------------------------

/**
 * Kurzer Lichtstern am Ziel des Blinzelns: weicher Schein und zwei gekreuzte, sich verjüngende Strahlen (dazu zwei
 * halb so lange schräge), die mit `outBack` aufgehen, sich langsam drehen und kubisch verlöschen. Kein Blinken.
 *
 * Kodierung: pos = Mitte, a = Größe (halbe Strahllänge), life.
 */
private class StarFx(args: FxArgs) : FxEffect(max(args.life, 4)) {
    private val palette = args.palette
    private val center = args.pos
    private val size = args.a.coerceIn(0.3f, 4f)
    private val seed = args.seed.toInt()
    private val spin0 = (seed % 7) * 0.4f

    private fun envelope(t: Float): Pair<Float, Float> {
        val open = Ease.outBack((t / OPEN_TICKS).coerceIn(0f, 1f))
        val fade = 1f - Ease.inCubic(((t - OPEN_TICKS) / (life - OPEN_TICKS).coerceAtLeast(1f)).coerceIn(0f, 1f))
        return open to fade
    }

    override fun over(f: FxFrame) {
        val t = time(f)
        val (open, fade) = envelope(t)
        if (fade <= 0.01f) return
        val r = Vec3d(f.right.x.toDouble(), f.right.y.toDouble(), f.right.z.toDouble())
        val u = Vec3d(f.up.x.toDouble(), f.up.y.toDouble(), f.up.z.toDouble())
        val spin = spin0 + t * SPIN
        FxDraw.glow(f, center, size * GLOW * open, FxLib.alpha(palette.main, ALPHA * 0.55f * fade), seed)
        for (ray in 0 until 4) {
            val angle = spin + ray * (Math.PI.toFloat() / 4f)
            val reach = size * open * (if (ray % 2 == 0) 1f else DIAGONAL)
            val axis = r.multiply(cos(angle).toDouble()).add(u.multiply(sin(angle).toDouble()))
            val points = List(5) { i -> center.add(axis.multiply(reach * (i / 2.0 - 1.0))) }
            FxDraw.ribbon(
                f, points,
                width = { s -> RAY_WIDTH * size * (1f - abs(s - 0.5f) * 2f).coerceAtLeast(0.05f) },
                color = { s -> FxLib.alpha(FxLib.mix(palette.main, palette.core, 1f - abs(s - 0.5f) * 2f), ALPHA * fade) },
                mode = FxMode.RIBBON, seed = seed + ray,
            )
        }
    }

    companion object {
        const val OPEN_TICKS = 3f
        const val SPIN = 0.05f
        const val GLOW = 0.8f
        const val ALPHA = 0.7f
        const val DIAGONAL = 0.5f
        const val RAY_WIDTH = 0.07f
    }
}

// -------------------------------------------------------------------------------------------------------------------
// hermes.gust
// -------------------------------------------------------------------------------------------------------------------

/**
 * Windstoß der Botenglocken (Überarbeitung 4, P14, `HermesBells.State.gust`): drei schmale Luftbänder, nebeneinander
 * und leicht gegeneinander geschwungen, laufen in `b` Ticks von `pos` (Glockenseite) nach `dir` (Hermes). Der Kopf jedes
 * Bandes folgt einer weichen Kurve (outCubic), der Schweif hängt [TAIL] der Bahn dahinter; am Ziel verwehen sie in
 * [FADE] Ticks und ein weicher Schein bleibt kurz stehen. Ruhig: keine Funkenregen, kein Blinken.
 *
 * Kodierung: pos = Anfang (absolut), dir = Ende (absolut), a = Breite der Bahn, b = Laufzeit, life = b + Ausklang.
 */
private class GustFx(args: FxArgs) : FxEffect(max(args.life, 6)) {
    private val palette = args.palette
    private val from = args.pos
    private val to = if (args.dir.lengthSquared() <= 1.0001 && args.pos.lengthSquared() > 4.0) args.pos.add(args.dir.multiply(DEMO_LENGTH)) else args.dir
    private val spread = args.a.coerceIn(0.3f, 3f)
    private val travel = args.b.coerceAtLeast(2f)
    private val seed = args.seed.toInt()
    private val way = FxLib.unit(to.subtract(from), FxLib.UP)
    private val side = FxLib.unit(way.crossProduct(FxLib.UP), Vec3d(1.0, 0.0, 0.0))

    /** Kopf (0..1) zur Zeit [t]. */
    private fun head(t: Float): Float = Ease.outCubic(t / travel)

    /** Deckkraft: weich auf, am Ziel weich aus. */
    private fun alpha(t: Float): Float = Ease.outCubic(t / RISE) * (1f - Ease.inOut((t - travel) / FADE))

    /** Punkt des Bandes [band] bei Anteil [s] der Bahn: seitlich versetzt, sanft geschwungen, leicht steigend. */
    private fun point(band: Int, s: Float, t: Float): Vec3d {
        val offset = (band - 1) * spread * LANE_SHARE
        val swing = sin((s * SWING_WAVES + band * 0.7f + t * SWING_SPEED) * PI_F) * spread * SWING * (1f - abs(band - 1) * 0.35f)
        val lift = sin(s * PI_F) * LIFT + band * 0.08f
        return from.lerp(to, s.toDouble()).add(side.multiply((offset + swing).toDouble())).add(0.0, lift.toDouble(), 0.0)
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val a = alpha(t)
        if (a <= 0.01f) return
        val h = head(t)
        val tail = (h - TAIL).coerceAtLeast(0f)
        if (h - tail < 1e-3f) return
        for (band in 0 until BANDS) {
            val points = List(POINTS) { i -> point(band, tail + (h - tail) * i / (POINTS - 1f), t) }
            val w = if (band == 1) WIDTH else WIDTH * 0.7f
            FxDraw.ribbon(
                f, points,
                width = { s -> w * (0.1f + 0.9f * Ease.outQuad(s)) },
                color = { s -> FxLib.alpha(FxLib.mix(palette.edge, palette.main, s), a * ALPHA * s) },
                mode = FxMode.RIBBON, scroll = t * 0.15f, seed = seed + band,
            )
            FxDraw.ribbon(
                f, points,
                width = { s -> w * 0.35f * s },
                color = { s -> FxLib.alpha(palette.core, a * ALPHA * 0.8f * s) },
                mode = FxMode.RIBBON, scroll = t * 0.15f, seed = seed + band + 7,
            )
        }
    }

    override fun over(f: FxFrame) {
        val t = time(f)
        val a = alpha(t)
        if (a <= 0.01f) return
        FxDraw.glow(f, point(1, head(t), t), GLOW * spread, FxLib.alpha(palette.core, GLOW_ALPHA * a), seed)
    }

    companion object {
        const val DEMO_LENGTH = 12.0
        const val BANDS = 3
        const val POINTS = 16
        const val TAIL = 0.4f
        const val RISE = 3f
        const val FADE = 9f
        const val LANE_SHARE = 0.45f
        const val SWING = 0.18f
        const val SWING_WAVES = 2.2f
        const val SWING_SPEED = 0.06f
        const val LIFT = 0.35f
        const val WIDTH = 0.16f
        const val ALPHA = 0.6f
        const val GLOW = 1.2f
        const val GLOW_ALPHA = 0.4f
        const val PI_F = Math.PI.toFloat()
    }
}
