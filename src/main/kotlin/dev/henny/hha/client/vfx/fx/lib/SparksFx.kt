package dev.henny.hha.client.vfx.fx.lib

import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.logic.boss.BossFx
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.exp
import kotlin.math.pow

/**
 * Ballistische Lichtstriche (Vorbild Void `Sparks`, `VoidEffects.kt:38-63`), hier als kurze weiche Bänder statt
 * Würfelketten. Seed-fest: dieselben Zahlen geben dieselben Funken. Wird auch von anderen Bibliotheks-Effekten benutzt
 * (Säule, Himmelsschlag, Einschlag).
 */
internal class SparkSet(
    count: Int, speed: Float, dir: Vec3d, seed: Long,
    private val gravity: Float = GRAVITY,
) {
    private class Spark(val v: Vec3d, val end: Float, val width: Float)

    private val sparks: List<Spark> = Random(seed).let { rnd ->
        val bias = FxLib.unit(dir)
        List(count.coerceIn(0, BossFx.SPARKS_PER_CALL)) {
            val d = FxLib.unit(bias.multiply(BIAS).add(FxLib.sphere(rnd.nextFloat(), rnd.nextFloat())))
            val s = speed * (SPEED_MIN + rnd.nextFloat() * SPEED_RANGE)
            Spark(d.multiply(s.toDouble()), END_MIN + rnd.nextFloat() * (1f - END_MIN), WIDTH * (0.7f + 0.6f * rnd.nextFloat()))
        }
    }

    /** Lage eines Funkens nach [t] Ticks: Luftwiderstand als stetige Abnahme, Schwerkraft quadratisch. */
    private fun at(origin: Vec3d, v: Vec3d, t: Float): Vec3d {
        val travel = ((1f - exp(-DRAG * t)) / DRAG).toDouble()
        return origin.add(v.x * travel, v.y * travel - 0.5 * gravity * t * t, v.z * travel)
    }

    /**
     * Zeichnet alle Funken bei [time] Ticks; [span] = Lebenszeit in Ticks (jeder Funke erlischt zu seinem eigenen
     * Anteil davon), [fade] blendet alle zusammen.
     */
    fun draw(f: FxFrame, origin: Vec3d, time: Float, span: Float, fade: Float, palette: Palette) {
        if (fade <= 0.01f || time <= 0f) return
        for ((index, s) in sparks.withIndex()) {
            val k = time / (span * s.end)
            if (k >= 1f) continue
            val life = (1f - k).pow(1.5f) * fade
            val head = at(origin, s.v, time)
            val mid = at(origin, s.v, (time - TRAIL * 0.5f).coerceAtLeast(0f))
            val tail = at(origin, s.v, (time - TRAIL).coerceAtLeast(0f))
            val hot = (1f - k * 1.4f).coerceIn(0f, 1f)
            FxDraw.ribbon(
                f, listOf(tail, mid, head),
                width = { u -> s.width * (0.25f + 0.75f * u) },
                color = { u -> FxLib.alpha(FxLib.mix(palette.main, palette.core, hot * u), life * (0.3f + 0.7f * u)) },
                mode = FxMode.RIBBON, seed = index,
            )
        }
    }

    companion object {
        const val GRAVITY = 0.03f
        const val DRAG = 0.08f
        const val TRAIL = 2.4f
        const val WIDTH = 0.055f
        const val BIAS = 1.15
        const val SPEED_MIN = 0.45f
        const val SPEED_RANGE = 0.75f
        const val END_MIN = 0.55f
    }
}

/** `core.sparks`: pos = Mitte, dir = Richtung, a = Zahl (≤ 14), b = Tempo (Blöcke/Tick), life. */
class SparksFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val origin = args.pos
    private val palette = args.palette
    private val sparks = SparkSet(args.a.toInt(), args.b.coerceIn(0.05f, MAX_SPEED), args.dir, args.seed)

    override fun energy(f: FxFrame) {
        val t = time(f)
        sparks.draw(f, origin, t, life.toFloat(), 1f, palette)
    }

    companion object {
        const val MIN_LIFE = 6
        const val MAX_SPEED = 1.5f
    }
}
