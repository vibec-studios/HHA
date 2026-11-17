package dev.henny.hha.client.vfx.fx.lib

import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMeshes
import dev.henny.hha.client.vfx.fx.FxMode
import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import java.util.Random

/**
 * `core.charge`: Schalen und Lichtstriche ziehen sich auf ein Wesen zusammen (Vorbild Void `ChargeEffect`,
 * `VoidEffects.kt:821-848`), Stärke steigt über die Dauer. **Kein** eigenes Rütteln. Die Schalen laufen als stetige
 * Sägezahn-Zyklen; ihre Helligkeit ist an beiden Enden 0, daher springt beim Neustart nichts sichtbar.
 *
 * Kodierung: pos = Lage des Ziels, a = radius, life, entity = Ziel (folgt dessen Körpermitte).
 */
class ChargeFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val target: Entity? = args.entity
    private val fallback = args.pos.add(0.0, 1.0, 0.0)
    private val radius = args.a.coerceIn(0.5f, MAX_RADIUS)
    private val palette = args.palette
    private val seed = args.seed.toInt()
    private val dirs: List<Vec3d> = Random(args.seed).let { rnd -> List(STROKES) { FxLib.sphere(rnd.nextFloat(), rnd.nextFloat()) } }

    private fun center(tickDelta: Float): Vec3d = target?.takeIf { !it.isRemoved }?.let { FxLib.body(it, tickDelta, 0.5) } ?: fallback

    /** Gesamtstärke: steigt weich, blendet am Ende kurz aus. */
    private fun strength(k: Float): Float = (0.25f + 0.75f * Ease.inQuad(k)) * ((1f - k) / END_FADE).coerceIn(0f, 1f)

    override fun energy(f: FxFrame) {
        val t = time(f)
        val s = strength(k(f))
        if (s <= 0.01f) return
        val at = center(f.tickDelta)
        for (i in 0 until SHELLS) {
            val cycle = ((t / CYCLE + i.toFloat() / SHELLS) % 1f + 1f) % 1f
            val r = radius * (1f - cycle) + SHELL_MIN
            val m = Matrix4f().translation(at.x.toFloat(), at.y.toFloat(), at.z.toFloat()).scale(r)
            FxDraw.mesh(f, FxMeshes.SPHERE, m, FxLib.alpha(palette.main, SHELL_ALPHA * s * Ease.bump(cycle)), FxMode.SHELL, seed = seed + i)
        }
        for ((index, d) in dirs.withIndex()) {
            val local = ((t * STROKE_SPEED + index * 0.137f) % 1f + 1f) % 1f
            val near = radius * (1f - local)
            val a = at.add(d.multiply(near.toDouble()))
            val b = at.add(d.multiply((near + radius * STROKE_LENGTH).toDouble()))
            FxDraw.ribbon(
                f, listOf(b, a),
                width = { u -> STROKE_WIDTH * (0.3f + 0.7f * u) },
                color = { u -> FxLib.alpha(FxLib.mix(palette.main, palette.core, u), STROKE_ALPHA * s * Ease.bump(local) * (0.3f + 0.7f * u)) },
                mode = FxMode.RIBBON, seed = seed + 10 + index,
            )
        }
    }

    override fun over(f: FxFrame) {
        val k = k(f)
        val s = strength(k)
        if (s <= 0.01f) return
        FxDraw.glow(f, center(f.tickDelta), CORE_SIZE + CORE_GROW * k, FxLib.alpha(palette.core, (CORE_ALPHA + CORE_ALPHA_GROW * k) * s), seed)
    }

    companion object {
        const val MIN_LIFE = 6
        const val MAX_RADIUS = 12f
        const val SHELLS = 2
        const val CYCLE = 22f
        const val SHELL_MIN = 0.35f
        const val SHELL_ALPHA = 0.4f
        const val STROKES = 8
        const val STROKE_SPEED = 0.05f
        const val STROKE_LENGTH = 0.22f
        const val STROKE_WIDTH = 0.05f
        const val STROKE_ALPHA = 0.85f
        const val END_FADE = 0.08f
        const val CORE_SIZE = 0.4f
        const val CORE_GROW = 1.0f
        const val CORE_ALPHA = 0.2f
        const val CORE_ALPHA_GROW = 0.35f
    }
}
