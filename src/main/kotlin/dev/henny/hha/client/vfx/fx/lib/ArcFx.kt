package dev.henny.hha.client.vfx.fx.lib

import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.logic.Vfx
import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * `core.arc`: Bézier-Energiebogen von A nach B (Vorbild Void `ArcEffect`, `VoidEffects.kt:270-331`). Der Kopf fliegt
 * vor, der Schweif läuft getrennt hinterher und verjüngt sich; ein oder zwei Stränge. Optional an zwei Wesen geheftet
 * (Enden folgen deren Körpermitte). Das Zittern ist stetiges Rauschen über die Zeit (keine Zeitstufen).
 *
 * Kodierung: pos = from, dir = to (absolut), a = bend (Anteil der Länge), b = width, c = strands (1..2), life,
 * entity / entity2 = an diese Wesen geheftet.
 */
class ArcFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val palette = args.palette
    private val fromPos = args.pos
    private val toPos = args.dir
    private val fromEntity: Entity? = args.entity
    private val toEntity: Entity? = args.entity2
    private val bend = args.a.coerceIn(-MAX_BEND, MAX_BEND)
    private val width = args.b.coerceIn(MIN_WIDTH, MAX_WIDTH)
    private val strands = args.c.toInt().coerceIn(1, 2)
    private val seed = args.seed.toInt()
    private val mode = if (args.style == Vfx.Style.THUNDER || args.style == Vfx.Style.STORM) FxMode.BOLT else FxMode.RIBBON

    /** Seitliche Neigung der Biegung (aus dem Seed, damit Folgebögen nicht deckungsgleich sind). */
    private val lean = Random(args.seed).nextFloat() * 2f * LEAN - LEAN

    /** Kopf und Schweif in Ticks. */
    private val headTicks = (life * HEAD_SHARE).coerceAtLeast(2f)
    private val tailStart = life * TAIL_START_SHARE

    private fun ends(tickDelta: Float): Pair<Vec3d, Vec3d> {
        val a = fromEntity?.takeIf { !it.isRemoved }?.let { FxLib.body(it, tickDelta) } ?: fromPos
        val b = toEntity?.takeIf { !it.isRemoved }?.let { FxLib.body(it, tickDelta) } ?: toPos
        return a to b
    }

    /** Kontrollpunkt: Mitte, senkrecht zur Strecke nach oben gebogen und leicht seitlich geneigt. */
    private fun control(a: Vec3d, b: Vec3d): Vec3d {
        val d = b.subtract(a)
        val length = d.length()
        if (length < 1e-4) return a
        val dir = d.multiply(1.0 / length)
        val upPerp = FxLib.UP.subtract(dir.multiply(FxLib.UP.dotProduct(dir))).let { FxLib.unit(it, FxLib.across(dir)) }
        val side = FxLib.unit(dir.crossProduct(upPerp), FxLib.across(dir))
        val off = upPerp.multiply(cos(lean).toDouble()).add(side.multiply(sin(lean).toDouble()))
        return a.add(b).multiply(0.5).add(off.multiply(length * bend))
    }

    private fun spans(t: Float): Pair<Float, Float> {
        val head = Ease.outCubic(t / headTicks)
        val tail = Ease.inOut((t - tailStart) / (life - tailStart).coerceAtLeast(1f))
        return tail to head
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val (tail, head) = spans(t)
        if (tail >= 1f || head - tail < 1e-3f) return
        val (a, b) = ends(f.tickDelta)
        val ctl = control(a, b)
        val length = a.distanceTo(b).toFloat()
        val (u, v) = FxLib.basis(b.subtract(a))
        val jitter = (JITTER * length).coerceAtMost(JITTER_MAX)
        val fade = 1f - tail
        for (strand in 0 until strands) {
            val strength = if (strand == 0) 1f else SECOND_STRAND
            val phase = strand * 17.3f + seed * 0.37f
            val points = List(POINTS) { i ->
                val s = i / (POINTS - 1f)
                val along = tail + (head - tail) * s
                val taper = 1f - abs(s - 0.5f) * 2f
                val wu = Ease.wobble(t * WOBBLE_SPEED + i * 0.9f, phase) * jitter * taper
                val wv = Ease.wobble(t * WOBBLE_SPEED + i * 0.9f, phase + 5.1f) * jitter * taper
                FxLib.bezier(a, ctl, b, along).add(u.multiply(wu.toDouble())).add(v.multiply(wv.toDouble()))
            }
            // Hülle: verjüngt zum Schweif, Farbe vom Rand zur Hauptfarbe.
            FxDraw.ribbon(
                f, points,
                width = { s -> width * strength * (0.2f + 0.8f * s) },
                color = { s -> FxLib.alpha(FxLib.mix(palette.edge, palette.main, s), fade * strength * (0.25f + 0.65f * s)) },
                mode = mode, scroll = t * SCROLL, seed = seed + strand,
            )
            // Kern: schmal und hell.
            FxDraw.ribbon(
                f, points,
                width = { s -> width * CORE_WIDTH * strength * (0.15f + 0.85f * s) },
                color = { s -> FxLib.alpha(palette.core, fade * strength * (0.2f + 0.8f * s)) },
                mode = mode, scroll = t * SCROLL, seed = seed + strand + 7,
            )
        }
    }

    override fun over(f: FxFrame) {
        val t = time(f)
        val (tail, head) = spans(t)
        if (head >= 1f || tail >= 1f) return
        val (a, b) = ends(f.tickDelta)
        val at = FxLib.bezier(a, control(a, b), b, head)
        FxDraw.glow(f, at, width * HEAD_GLOW, FxLib.alpha(palette.core, HEAD_ALPHA * (1f - head * 0.5f)), seed)
    }

    companion object {
        const val MIN_LIFE = 4
        const val MAX_BEND = 1.5f
        const val MIN_WIDTH = 0.05f
        const val MAX_WIDTH = 3f
        const val LEAN = 0.6f
        /** Der Kopf erreicht B nach diesem Anteil der Lebenszeit … */
        const val HEAD_SHARE = 0.55f
        /** … der Schweif startet hier und erreicht B am Ende. */
        const val TAIL_START_SHARE = 0.3f
        const val POINTS = 26
        const val JITTER = 0.04f
        const val JITTER_MAX = 0.45f
        const val WOBBLE_SPEED = 0.9f
        const val SECOND_STRAND = 0.55f
        const val CORE_WIDTH = 0.38f
        const val SCROLL = 0.08f
        const val HEAD_GLOW = 2.2f
        const val HEAD_ALPHA = 0.7f
    }
}
