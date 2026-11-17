package dev.henny.hha.client.vfx.fx.lib

import dev.henny.hha.HhaSounds
import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMeshes
import dev.henny.hha.client.vfx.fx.FxMode
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * `core.swirl`: Wirbel-Scheibe, leuchtender Torus und drei Ausläufer (Vorbild Void `PortalEffect`,
 * `VoidEffects.kt:97-150`). Öffnend: springt weich auf, hält, schließt am Ende. Schließend: steht sofort offen und
 * zieht sich mit nach innen drehenden Ausläufern in einen Punkt zusammen (Verschwinden, Teleport).
 *
 * Kodierung: pos = center, dir = normal (Einheit), a = radius, b = 1 schließend / 0 öffnend, life.
 */
class SwirlFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val center = args.pos
    private val normal = FxLib.unit(args.dir)
    private val radius = args.a.coerceIn(0.2f, MAX_RADIUS)
    private val closing = args.b >= 0.5f
    private val palette = args.palette
    private val seed = args.seed.toInt()
    private val axes = FxLib.basis(normal)

    /** Öffnung 0..1 über den Anteil [k] der Lebenszeit. */
    private fun openness(k: Float): Float = if (closing) {
        Ease.outCubic(k / CLOSE_OPEN) * (1f - Ease.inCubic((k - CLOSE_HOLD) / (1f - CLOSE_HOLD)))
    } else {
        Ease.outBack(k / OPEN_SHARE) * (1f - Ease.inQuad((k - OPEN_END) / (1f - OPEN_END)))
    }

    override fun tick(world: ClientWorld) {
        if (age == 1) FxLib.sound(world, center, HhaSounds.SWIRL, SOUND_VOLUME, if (closing) 1.15f else 0.9f)
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val k = k(f)
        val r = openness(k) * radius
        if (r <= 0.02f) return
        val spin = t * SPIN * (if (closing) -1.6f else 1f)
        val (u, v) = axes

        FxDraw.flat(f, center, normal, r, FxLib.alpha(palette.edge, DISC_ALPHA), FxMode.SWIRL, rotation = spin, seed = seed)
        FxDraw.flat(
            f, center, normal, r * HALO_OUTER, FxLib.alpha(palette.main, HALO_ALPHA), FxMode.SHOCK,
            inner = r * HALO_INNER, rotation = spin * 0.5f, seed = seed + 1,
        )
        val torus = FxLib.aligned(center, normal).rotateY(spin).scale(r)
        FxDraw.mesh(f, FxMeshes.TORUS, torus, FxLib.alpha(palette.main, TORUS_ALPHA), FxMode.SHELL, seed = seed + 2)

        // Ausläufer: drehen aus dem Rand hinaus (öffnend) bzw. in die Mitte hinein (schließend).
        for (arm in 0 until ARMS) {
            val base = arm * (2f * PI.toFloat() / ARMS) + spin
            val points = List(ARM_POINTS) { i ->
                val s = i / (ARM_POINTS - 1f)
                val a = base + s * ARM_TURN * (if (closing) -1f else 1f)
                val rr = r * (if (closing) 1f + (1f - s) * ARM_REACH else 1f + s * ARM_REACH)
                val lift = sin(s * 3f + spin) * ARM_LIFT * r
                center.add(u.multiply((cos(a) * rr).toDouble())).add(v.multiply((sin(a) * rr).toDouble()))
                    .add(normal.multiply(lift.toDouble()))
            }
            FxDraw.ribbon(
                f, points,
                width = { s -> (if (closing) s else 1f - s) * ARM_WIDTH * r },
                color = { s -> FxLib.alpha(FxLib.mix(palette.main, palette.core, 0.3f), ARM_ALPHA * (if (closing) s else 1f - s)) },
                mode = FxMode.RIBBON, seed = seed + 3 + arm,
            )
        }
    }

    /** Heller Kern: beim Öffnen am Anfang, beim Schließen am Ende (zieht sich in einen Lichtpunkt). */
    override fun over(f: FxFrame) {
        val k = k(f)
        val flare = if (closing) Ease.bump((k - FLARE_START) / (1f - FLARE_START)) else 1f - Ease.outQuad(k / FLARE_OPEN)
        if (flare <= 0.01f) return
        FxDraw.glow(f, center, radius * FLARE_SIZE, FxLib.alpha(palette.core, FLARE_ALPHA * flare), seed)
    }

    companion object {
        const val MIN_LIFE = 8
        const val MAX_RADIUS = 16f
        const val OPEN_SHARE = 0.22f
        const val OPEN_END = 0.7f
        const val CLOSE_OPEN = 0.12f
        const val CLOSE_HOLD = 0.3f
        const val SPIN = 0.3f
        const val DISC_ALPHA = 0.8f
        const val HALO_INNER = 1.02f
        const val HALO_OUTER = 1.3f
        const val HALO_ALPHA = 0.45f
        const val TORUS_ALPHA = 0.8f
        const val ARMS = 3
        const val ARM_POINTS = 10
        const val ARM_TURN = 2.2f
        const val ARM_REACH = 0.75f
        const val ARM_LIFT = 0.25f
        const val ARM_WIDTH = 0.14f
        const val ARM_ALPHA = 0.85f
        const val FLARE_OPEN = 0.3f
        const val FLARE_START = 0.75f
        const val FLARE_SIZE = 1.1f
        const val FLARE_ALPHA = 0.6f
        const val SOUND_VOLUME = 0.5f
    }
}
