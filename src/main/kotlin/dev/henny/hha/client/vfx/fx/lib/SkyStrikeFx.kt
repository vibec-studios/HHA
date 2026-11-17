package dev.henny.hha.client.vfx.fx.lib

import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.client.vfx.fx.Tear
import dev.henny.hha.logic.Vfx
import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin

/**
 * `core.sky_strike`: Nach `delay` Ticks öffnet sich in `height` über dem Punkt ein Riss, dann fährt ein Strahl herab,
 * am Boden ein Einschlagschein mit Stoßring und Funken (Vorbild Void `SkyStrikeEffect`, `VoidEffects.kt:851-891`).
 * **Vor `delay` zeichnet der Effekt nichts** (die Ansage gehört dem Telegraph; kein Blinken). Der Strahl erreicht den
 * Boden genau [IMPACT_AFTER] Ticks nach `delay` – wer Schaden und Bild abstimmt, rechnet damit.
 *
 * Kodierung: pos = ground, a = radius, b = height, c = delay (Ticks), life = delay + Dauer danach. Flächeneffekt.
 */
class SkyStrikeFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(args.c.toInt() + MIN_AFTER)) {
    private val ground = args.pos
    private val radius = args.a.coerceIn(0.3f, MAX_RADIUS)
    private val height = args.b.coerceIn(4f, MAX_HEIGHT)
    private val delay = args.c.coerceIn(0f, (life - MIN_AFTER).toFloat())
    private val palette = args.palette
    private val seed = args.seed.toInt()
    private val shape = Tear.Shape(seed)
    private val top = ground.add(0.0, height.toDouble(), 0.0)
    private val tearAlong = (seed * 0.61f).let { Vec3d(cos(it).toDouble(), 0.0, sin(it).toDouble()) }
    private val tearAcross = Vec3d(-tearAlong.z, 0.0, tearAlong.x)
    private val sparks = SparkSet(SPARKS, SPARK_SPEED, FxLib.UP, args.seed)
    private val mode = if (args.style == Vfx.Style.THUNDER || args.style == Vfx.Style.STORM) FxMode.BOLT else FxMode.RIBBON

    /** Ticks nach dem Einschlag (negativ davor). */
    private fun afterImpact(t: Float) = t - delay - IMPACT_AFTER

    private fun fadeAfter(t: Float): Float {
        val span = (life - delay - IMPACT_AFTER).coerceAtLeast(1f)
        return (1f - afterImpact(t) / span).coerceIn(0f, 1f)
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val local = t - delay
        if (local <= 0f) return
        // Riss im Himmel: öffnet weich, schließt am Ende.
        val open = Ease.outBack(local / TEAR_OPEN) * ((life - t) / TEAR_CLOSE).coerceIn(0f, 1f)
        Tear.draw(f, top, tearAlong, tearAcross, radius * TEAR_LENGTH, radius * TEAR_WIDTH, open, shape, palette, t, Ease.inOut((local - TEAR_OPEN) / HOT_RAMP))

        // Strahl: der Kopf fährt in [DROP_TICKS] herab, danach wird er schmaler und blendet aus.
        val drop = Ease.inQuad((local - (IMPACT_AFTER - DROP_TICKS)) / DROP_TICKS)
        if (drop <= 0f) return
        val fade = fadeAfter(t)
        if (fade <= 0f) return
        val tip = FxLib.lerp(top, ground, drop)
        val points = List(BEAM_POINTS) { i -> FxLib.lerp(top, tip, i / (BEAM_POINTS - 1f)) }
        FxDraw.ribbon(
            f, points, width = { s -> radius * BEAM_WIDTH * fade * (0.7f + 0.3f * s) },
            color = { s -> FxLib.alpha(FxLib.mix(palette.edge, palette.main, s), fade * BEAM_ALPHA) }, mode = mode, scroll = t * 0.2f, seed = seed,
        )
        val after = afterImpact(t)
        if (after > 0f) {
            val ring = Ease.outCubic(after / RING_TICKS)
            FxDraw.flat(
                f, ground.add(0.0, FxLib.GROUND_LIFT * 3, 0.0), FxLib.UP, radius * (RING_START + RING_GROW * ring),
                FxLib.alpha(palette.main, RING_ALPHA * (1f - ring)), FxMode.SHOCK, inner = radius * (RING_START + RING_GROW * ring) * 0.72f,
                progress = ring, seed = seed + 2,
            )
            sparks.draw(f, ground.add(0.0, 0.3, 0.0), after, (life - delay - IMPACT_AFTER).coerceAtLeast(4f), 1f, palette)
        }
    }

    /** Heller Kern des Strahls und Einschlagschein (sehr hell, nach dem Bildschirm-Pass). */
    override fun over(f: FxFrame) {
        val t = time(f)
        val local = t - delay
        val drop = Ease.inQuad((local - (IMPACT_AFTER - DROP_TICKS)) / DROP_TICKS)
        if (drop <= 0f) return
        val fade = fadeAfter(t)
        if (fade <= 0f) return
        val tip = FxLib.lerp(top, ground, drop)
        FxDraw.ribbon(
            f, listOf(top, FxLib.lerp(top, tip, 0.5f), tip), width = { _ -> radius * CORE_WIDTH * fade },
            color = { _ -> FxLib.alpha(palette.core, fade * CORE_ALPHA) }, mode = mode, seed = seed + 1,
        )
        if (afterImpact(t) > 0f) {
            FxDraw.glow(f, ground.add(0.0, 0.4, 0.0), radius * GLOW_SIZE * fade, FxLib.alpha(palette.core, GLOW_ALPHA * fade * fade), seed)
        }
    }

    companion object {
        /** Der Strahl erreicht den Boden so viele Ticks nach `delay`. */
        const val IMPACT_AFTER = 6f
        const val DROP_TICKS = 3f
        const val MIN_AFTER = 10
        const val MAX_RADIUS = 12f
        const val MAX_HEIGHT = 96f
        const val TEAR_OPEN = 4f
        const val TEAR_CLOSE = 6f
        /** Der Rissrand glüht weich auf, sobald der Strahl kommt (Ticks). */
        const val HOT_RAMP = 3f
        const val TEAR_LENGTH = 4.5f
        const val TEAR_WIDTH = 0.4f
        const val BEAM_POINTS = 6
        const val BEAM_WIDTH = 0.45f
        const val BEAM_ALPHA = 0.85f
        const val CORE_WIDTH = 0.18f
        const val CORE_ALPHA = 0.9f
        const val RING_TICKS = 8f
        const val RING_START = 0.3f
        const val RING_GROW = 1.4f
        const val RING_ALPHA = 0.55f
        const val GLOW_SIZE = 1.6f
        const val GLOW_ALPHA = 0.7f
        const val SPARKS = 10
        const val SPARK_SPEED = 0.4f
    }
}
