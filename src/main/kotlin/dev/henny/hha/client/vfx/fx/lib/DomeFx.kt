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
import kotlin.math.PI
import kotlin.math.sin

/**
 * `core.dome`: Fresnel-Schale um einen Punkt oder ein Wesen (Kratos' Schild, Schutzkuppeln). Springt weich auf, atmet
 * kaum merklich (≤ 0,3 Hz), blendet am Ende aus. Mit `shatter` zerspringt sie in den letzten [SHATTER_TICKS] Ticks in
 * spitze Scherben (liegende Bänder tangential zur Schale), die nach außen fliegen, fallen und verblassen.
 *
 * Kodierung: pos = center, a = radius, b = 1 zerspringt am Ende, life, entity = folgt diesem Wesen (Körpermitte).
 */
class DomeFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val follow: Entity? = args.entity
    private val fallback = args.pos
    private val radius = args.a.coerceIn(0.3f, MAX_RADIUS)
    private val shatter = args.b >= 0.5f
    private val palette = args.palette
    private val seed = args.seed.toInt()
    private val breakAt = if (shatter) (life - SHATTER_TICKS).coerceAtLeast(life / 2).toFloat() else Float.MAX_VALUE

    private class Shard(val dir: Vec3d, val tangent: Vec3d, val size: Float, val speed: Float)

    private val shards: List<Shard> = Random(args.seed).let { rnd ->
        List(if (shatter) SHARDS else 0) {
            val d = FxLib.sphere(rnd.nextFloat(), 0.25f + 0.75f * rnd.nextFloat())
            val t = FxLib.basis(d).let { (u, v) -> u.multiply(rnd.nextGaussian()).add(v.multiply(rnd.nextGaussian())) }
            Shard(d, FxLib.unit(t, FxLib.across(d)), SHARD_SIZE * (0.6f + 0.8f * rnd.nextFloat()), SHARD_SPEED * (0.6f + 0.8f * rnd.nextFloat()))
        }
    }

    private fun center(tickDelta: Float): Vec3d = follow?.takeIf { !it.isRemoved }?.let { FxLib.body(it, tickDelta, 0.5) } ?: fallback

    override fun energy(f: FxFrame) {
        val t = time(f)
        val at = center(f.tickDelta)
        if (t < breakAt) {
            val grow = Ease.outBack(t / OPEN_TICKS)
            val fade = if (shatter) 1f else ((life - t) / FADE_TICKS).coerceIn(0f, 1f)
            val breath = 1f + BREATH * sin(t * BREATH_SPEED + seed)
            val r = radius * grow * breath * (1f + (1f - fade) * FADE_GROW)
            val crack = if (shatter) Ease.inQuad((t - (breakAt - WARN_TICKS)) / WARN_TICKS) else 0f
            val m = Matrix4f().translation(at.x.toFloat(), at.y.toFloat(), at.z.toFloat()).scale(r)
            FxDraw.mesh(f, FxMeshes.SPHERE, m, FxLib.alpha(FxLib.mix(palette.main, palette.core, crack * 0.6f), SHELL_ALPHA * fade), FxMode.DOME, seed = seed)
            val inner = Matrix4f().translation(at.x.toFloat(), at.y.toFloat(), at.z.toFloat()).scale(r * INNER)
            FxDraw.mesh(f, FxMeshes.SPHERE, inner, FxLib.alpha(palette.edge, INNER_ALPHA * fade), FxMode.SHELL, seed = seed + 1)
            return
        }
        // Scherben: tangentiale Spitzbänder, fliegen hinaus, fallen, verblassen.
        val s = t - breakAt
        val k = (s / SHATTER_TICKS).coerceIn(0f, 1f)
        val fade = 1f - Ease.inQuad(k)
        for ((index, sh) in shards.withIndex()) {
            val travel = sh.speed * s
            val mid = at.add(sh.dir.multiply((radius + travel).toDouble())).add(0.0, -0.5 * SHARD_GRAVITY * s * s, 0.0)
            val spin = s * SHARD_SPIN * (if (index % 2 == 0) 1f else -1f)
            val t2 = FxLib.unit(sh.tangent.multiply(kotlin.math.cos(spin).toDouble()).add(sh.dir.crossProduct(sh.tangent).multiply(sin(spin).toDouble())))
            val half = sh.size * (1f - 0.5f * k)
            val pts = listOf(mid.add(t2.multiply(-half.toDouble())), mid, mid.add(t2.multiply(half.toDouble())))
            FxDraw.strip(
                f, pts, sh.dir, width = { u -> half * SHARD_WIDTH * sin(u * PI.toFloat()) },
                color = { _ -> FxLib.alpha(FxLib.mix(palette.main, palette.core, 0.4f), SHARD_ALPHA * fade) }, mode = FxMode.SHELL, seed = seed + index,
            )
        }
    }

    /** Beim Zerspringen ein kurzer weicher Schein (kubischer Abfall, ≤ 0,5). */
    override fun over(f: FxFrame) {
        val s = time(f) - breakAt
        if (s < 0f || s > FLASH_TICKS) return
        val fade = 1f - s / FLASH_TICKS
        FxDraw.glow(f, center(f.tickDelta), radius * FLASH_SIZE, FxLib.alpha(palette.core, FLASH_ALPHA * fade * fade * fade), seed)
    }

    companion object {
        const val MIN_LIFE = 6
        const val MAX_RADIUS = 24f
        const val OPEN_TICKS = 6f
        const val FADE_TICKS = 8f
        const val FADE_GROW = 0.08f
        const val BREATH = 0.015f
        const val BREATH_SPEED = 0.12f
        const val SHELL_ALPHA = 0.55f
        const val INNER = 0.96f
        const val INNER_ALPHA = 0.18f
        /** Vor dem Zerspringen hellt die Schale über so viele Ticks leicht auf (kein Blinken). */
        const val WARN_TICKS = 6f
        const val SHATTER_TICKS = 12
        const val SHARDS = 14
        const val SHARD_SIZE = 0.35f
        const val SHARD_WIDTH = 0.7f
        const val SHARD_SPEED = 0.12f
        const val SHARD_GRAVITY = 0.02f
        const val SHARD_SPIN = 0.25f
        const val SHARD_ALPHA = 0.85f
        const val FLASH_TICKS = 6f
        const val FLASH_SIZE = 1.2f
        const val FLASH_ALPHA = 0.45f
    }
}
