package dev.henny.hha.client.vfx.fx.lib

import dev.henny.hha.HhaSounds
import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.client.vfx.fx.Tear
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * `core.tear`: Riss in der Luft (Vorbild Void `RiftEffect`, `VoidRifts.kt:81-140`, gezeichnet mit [Tear.draw]). Öffnet
 * in `open` Ticks mit leichtem Überschießen, hält, schließt in [CLOSE_TICKS] am Ende. Beim Öffnen glüht der Rand auf;
 * ein paar Lichtstriche werden hineingezogen.
 *
 * Kodierung: pos = center, dir = along (Einheit), a = length, b = width, c = open (Ticks), life.
 */
class TearFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val center = args.pos
    private val along = FxLib.unit(args.dir)
    private val across = FxLib.across(along)
    private val length = args.a.coerceIn(0.3f, MAX_LENGTH)
    private val width = args.b.coerceIn(0.05f, length)
    private val openTicks = args.c.coerceIn(1f, life * 0.8f)
    private val palette = args.palette
    private val shape = Tear.Shape(args.seed.toInt())
    private val motes: List<FloatArray> = Random(args.seed + 7).let { rnd ->
        List(MOTES) { floatArrayOf(rnd.nextFloat() * 6.28f, rnd.nextFloat() * 6.28f, rnd.nextFloat()) }
    }

    /** Öffnung 0..1 bei [t] Ticks. */
    fun openness(t: Float): Float {
        val opening = Ease.outBack(t / openTicks)
        val closing = ((life - t) / CLOSE_TICKS).coerceIn(0f, 1f)
        return opening * closing * closing
    }

    override fun tick(world: ClientWorld) {
        if (age == 1) FxLib.sound(world, center, HhaSounds.TEAR, SOUND_VOLUME, 0.95f + (shape.seed and 7) * 0.02f)
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val open = openness(t)
        val hot = (1f - t / (openTicks + FLARE_TICKS)).coerceIn(0f, 1f)
        Tear.draw(f, center, along, across, length, width, open, shape, palette, t, hot)
        if (open <= 0.05f) return
        // Lose Lichtstriche werden hineingezogen (stetiger Zyklus, Helligkeit an beiden Enden 0).
        for ((index, m) in motes.withIndex()) {
            val cycle = ((t * MOTE_SPEED + m[2]) % 1f + 1f) % 1f
            val dist = (1f - cycle) * length * MOTE_REACH
            val from = Vec3d(cos(m[0]) * dist.toDouble(), sin(m[1]) * dist * 0.6, sin(m[0]) * dist.toDouble())
            FxDraw.ribbon(
                f, listOf(center.add(from), center.add(from.multiply(0.86))),
                width = { MOTE_WIDTH }, color = { _ -> FxLib.alpha(palette.main, open * MOTE_ALPHA * Ease.bump(cycle)) },
                mode = FxMode.RIBBON, seed = index,
            )
        }
    }

    companion object {
        const val MIN_LIFE = 6
        const val MAX_LENGTH = 64f
        const val CLOSE_TICKS = 6f
        const val FLARE_TICKS = 6f
        const val MOTES = 8
        const val MOTE_SPEED = 0.04f
        const val MOTE_REACH = 0.55f
        const val MOTE_WIDTH = 0.05f
        const val MOTE_ALPHA = 0.8f
        const val SOUND_VOLUME = 0.55f
    }
}
