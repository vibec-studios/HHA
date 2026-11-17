package dev.henny.hha.client.vfx.fx.lib

import dev.henny.hha.HhaSounds
import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMeshes
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.client.vfx.fx.FxSolidMode
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossFx
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.Random
import kotlin.math.sin

/**
 * `core.pillar_shatter` (Flächeneffekt): Eine Säule fährt aus dem Boden, hält und zerspringt (Vorbild Void
 * `PillarEffect`, `VoidEffects.kt:650-753`). Stil `SOUL`/`HELL`: Wassersäule (Röhre im Modus WATER), zerspringt in
 * Tropfen und Spritzringe (Tantalos' Geysir). Sonst: Fels mit Glutadern (glatte feste Formen, `FxSolidMode.EMBER`, keine Voxel), zerspringt
 * in Brocken und Glutfunken (Hades' Einsturz, Brontes' Keil).
 *
 * Kodierung: pos = base, a = radius, b = height, c = rise (Ticks), d = hold (Ticks), life = rise + hold + 20.
 */
class PillarShatterFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val base = args.pos
    private val radius = args.a.coerceIn(0.3f, MAX_RADIUS)
    private val height = args.b.coerceIn(1f, MAX_HEIGHT)
    private val rise = args.c.coerceIn(1f, 200f)
    private val breakAt = (rise + args.d.coerceIn(0f, 400f)).coerceAtMost((life - 4).toFloat())
    private val water = args.style == Vfx.Style.SOUL || args.style == Vfx.Style.HELL
    private val palette = args.palette
    private val seed = args.seed.toInt()
    private val shatterSpan = BossFx.PILLAR_SHATTER_TICKS.toFloat()

    /** Stücke nach dem Zerspringen: Richtung, Tempo, Größe, Drehachse. */
    private class Piece(val dir: Vec3d, val speed: Float, val size: Float, val axis: Vector3f, val lift: Float)

    private val pieces: List<Piece> = Random(args.seed).let { rnd ->
        List(if (water) DROPS else ROCKS) {
            val a = rnd.nextFloat() * Math.PI.toFloat() * 2f
            val dir = FxLib.unit(Vec3d(kotlin.math.cos(a).toDouble(), 0.6 + rnd.nextDouble() * 0.9, kotlin.math.sin(a).toDouble()))
            Piece(
                dir, PIECE_SPEED * (0.6f + 0.8f * rnd.nextFloat()), radius * (0.25f + 0.25f * rnd.nextFloat()),
                Vector3f(rnd.nextFloat() - 0.5f, rnd.nextFloat(), rnd.nextFloat() - 0.5f).normalize(), 0.2f + 0.7f * rnd.nextFloat(),
            )
        }
    }
    private val sparks = SparkSet(if (water) 0 else EMBERS, EMBER_SPEED, FxLib.UP, args.seed)

    /** Ausfahren 0..1 (leichtes Überschießen), hält bis zum Zerspringen. */
    private fun extent(t: Float): Float = if (t >= breakAt) 0f else Ease.outBack(t / rise)

    override fun tick(world: ClientWorld) {
        if (age == breakAt.toInt() + 1) FxLib.sound(world, base, HhaSounds.DEBRIS, SOUND_VOLUME, if (water) 1.3f else 0.8f)
    }

    /** Fels: drei glatte, gestreckte Formen mit Glutadern (feste Phase). */
    override fun solid(f: FxFrame) {
        if (water) return
        val t = time(f)
        val e = extent(t)
        if (e > 0.01f) {
            val wobble = if (t < rise + WOBBLE_TICKS) sin(t * 2.4f) * 0.02f * (1f - (t - rise).coerceAtLeast(0f) / WOBBLE_TICKS) else 0f
            for (i in 0 until 3) {
                val r = radius * (if (i == 0) 1f else 0.62f)
                val h = height * e * (if (i == 0) 1f else 0.6f + 0.15f * i)
                val off = if (i == 0) 0f else radius * 0.55f * (if (i == 1) 1f else -1f)
                val m = Matrix4f().translation(base.x.toFloat() + off, base.y.toFloat() + h * 0.5f - radius * 0.3f, base.z.toFloat())
                    .rotateY(seed * 0.7f + i * 2.1f).rotateZ(wobble + (if (i == 0) 0f else 0.18f * (if (i == 1) -1f else 1f)))
                    .scale(r, h * 0.5f + radius * 0.3f, r)
                FxDraw.mesh(f, FxMeshes.SPHERE, m, FxLib.alpha(ROCK_COLOR, ROCK_HEAT), FxSolidMode.EMBER, solid = true, seed = seed + i)
            }
            return
        }
        val s = t - breakAt
        if (s < 0f || s > shatterSpan) return
        val shrink = 1f - Ease.inQuad(s / shatterSpan)
        for ((index, p) in pieces.withIndex()) {
            val at = piecePos(p, s)
            val m = Matrix4f().translation(at.x.toFloat(), at.y.toFloat(), at.z.toFloat())
                .rotate(Quaternionf().rotationAxis(s * 0.35f + index, p.axis)).scale(p.size * shrink, p.size * 1.4f * shrink, p.size * shrink)
            FxDraw.mesh(f, FxMeshes.SPHERE, m, FxLib.alpha(ROCK_COLOR, ROCK_HEAT * shrink), FxSolidMode.EMBER, solid = true, seed = seed + 10 + index)
        }
    }

    private fun piecePos(p: Piece, s: Float): Vec3d =
        base.add(0.0, (height * p.lift).toDouble(), 0.0).add(p.dir.multiply((p.speed * s).toDouble())).add(0.0, -0.5 * GRAVITY * s * s, 0.0)

    override fun energy(f: FxFrame) {
        val t = time(f)
        val e = extent(t)
        val ground = base.add(0.0, FxLib.GROUND_LIFT * 2, 0.0)
        // Bodenfleck unter der Säule: durchscheinend (≤ 0,45), damit die Ansage lesbar bleibt.
        val stain = Ease.outCubic(t / rise) * ((life - t) / FADE_TICKS).coerceIn(0f, 1f)
        FxDraw.flat(f, ground, FxLib.UP, radius * 1.6f, FxLib.alpha(palette.edge, STAIN_ALPHA * stain), if (water) FxMode.WATER else FxMode.STAIN, seed = seed)
        if (e > 0.01f) {
            if (water) waterColumn(f, t, e) else emberVeil(f, e)
            return
        }
        val s = t - breakAt
        if (s < 0f || s > shatterSpan) return
        val k = s / shatterSpan
        val ring = Ease.outCubic(k)
        FxDraw.flat(
            f, ground, FxLib.UP, radius * (1f + RING_GROW * ring), FxLib.alpha(palette.main, RING_ALPHA * (1f - ring)),
            if (water) FxMode.WATER else FxMode.DUST, inner = radius * (1f + RING_GROW * ring) * 0.7f, progress = ring, seed = seed + 1,
        )
        if (water) {
            for ((index, p) in pieces.withIndex()) {
                val head = piecePos(p, s)
                val tail = piecePos(p, (s - DROP_TRAIL).coerceAtLeast(0f))
                FxDraw.ribbon(
                    f, listOf(tail, FxLib.lerp(tail, head, 0.5f), head), width = { u -> p.size * 0.35f * (0.3f + 0.7f * u) },
                    color = { u -> FxLib.alpha(FxLib.mix(palette.main, palette.core, u), (1f - k) * (0.4f + 0.5f * u)) }, mode = FxMode.WATER, seed = seed + index,
                )
            }
        } else {
            sparks.draw(f, base.add(0.0, height * 0.5, 0.0), s, shatterSpan, 1f, palette)
        }
    }

    /** Wassersäule: Röhre mit breiterer Krone, Oberfläche läuft im Shader. */
    private fun waterColumn(f: FxFrame, t: Float, e: Float) {
        val top = base.add(0.0, (height * e).toDouble(), 0.0)
        val points = List(COLUMN_POINTS) { i -> FxLib.lerp(base, top, i / (COLUMN_POINTS - 1f)) }
        val sway = 1f + 0.04f * sin(t * 0.3f + seed)
        FxDraw.tube(
            f, points, radius = { s -> radius * sway * (0.85f - 0.25f * s + CROWN * Ease.inCubic(s)) },
            color = { s -> FxLib.alpha(FxLib.mix(palette.edge, palette.main, s), WATER_ALPHA) }, mode = FxMode.WATER, sides = 12, seed = seed,
        )
        FxDraw.tube(
            f, points, radius = { s -> radius * 0.45f * (1f - 0.3f * s) },
            color = { _ -> FxLib.alpha(palette.core, WATER_CORE_ALPHA) }, mode = FxMode.WATER, sides = 8, seed = seed + 1,
        )
    }

    /** Glutschleier um den Fels: schwach, nur an der Basis kräftiger. */
    private fun emberVeil(f: FxFrame, e: Float) {
        val top = base.add(0.0, (height * e * 0.8f).toDouble(), 0.0)
        FxDraw.tube(
            f, listOf(base, FxLib.lerp(base, top, 0.5f), top), radius = { s -> radius * (1.15f - 0.4f * s) },
            color = { s -> FxLib.alpha(palette.main, VEIL_ALPHA * (1f - s)) }, mode = FxMode.FLAME, sides = 10, seed = seed,
        )
    }

    companion object {
        const val MIN_LIFE = 8
        const val MAX_RADIUS = 8f
        const val MAX_HEIGHT = 48f
        /** Dunkler Fels; die Glut der Adern kommt aus dem Modus. */
        const val ROCK_COLOR = 0xFF3A322E.toInt()
        /** Hitze der Glutadern (Alpha bei `FxSolidMode.EMBER`); Brocken kühlen beim Fliegen ab. */
        const val ROCK_HEAT = 0.7f
        const val WOBBLE_TICKS = 8f
        const val ROCKS = 7
        const val DROPS = 12
        const val EMBERS = 10
        const val EMBER_SPEED = 0.35f
        const val PIECE_SPEED = 0.22f
        const val GRAVITY = 0.04f
        const val DROP_TRAIL = 2.5f
        const val FADE_TICKS = 10f
        const val STAIN_ALPHA = 0.45f
        const val RING_GROW = 1.6f
        const val RING_ALPHA = 0.5f
        const val COLUMN_POINTS = 8
        const val CROWN = 0.5f
        const val WATER_ALPHA = 0.6f
        const val WATER_CORE_ALPHA = 0.35f
        const val VEIL_ALPHA = 0.35f
        const val SOUND_VOLUME = 0.5f
    }
}
