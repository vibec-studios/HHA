package dev.henny.hha.client.vfx

import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private val UP = Vec3d(0.0, 1.0, 0.0)
private val X = Vec3d(1.0, 0.0, 0.0)
private val Z = Vec3d(0.0, 0.0, 1.0)

/** Ein laufender 3D-Effekt. [render] bekommt die interpolierte Zeit in Ticks. */
abstract class VfxEffect(val life: Int) {
    var age = 0
        private set

    /** @return false, sobald der Effekt entfernt werden soll. */
    fun advance(world: ClientWorld): Boolean {
        age++
        tick(world)
        return age < life && !finished()
    }

    protected open fun tick(world: ClientWorld) {}

    protected open fun finished(): Boolean = false

    abstract fun render(d: VfxDraw, time: Float)

    protected fun progress(time: Float): Float = (time / life).coerceIn(0f, 1f)
}

/** Ist dies die eigene Figur in der Ego-Perspektive? Dann nichts direkt vors Gesicht zeichnen. */
private fun isSelfFirstPerson(entity: Entity?): Boolean {
    val client = MinecraftClient.getInstance()
    return entity != null && entity === client.cameraEntity && client.options.perspective.isFirstPerson
}

private fun Entity.lerped(tickDelta: Float): Vec3d = getLerpedPos(tickDelta)

// -------------------------------------------------------------------------------------------------
// Strahl
// -------------------------------------------------------------------------------------------------

/** Volumetrischer Energiestrahl: Kern, Hülle, Spiralbänder, Runenringe, Mündung und Einschlag. */
class BeamEffect(
    private val start: Vec3d, private val end: Vec3d, private val pal: Palette,
    private val width: Float, life: Int,
) : VfxEffect(life) {

    private val dir = end.subtract(start).let { if (it.lengthSquared() < 1e-6) UP else it.normalize() }
    private val length = start.distanceTo(end)
    private val basis = basisOf(dir)
    private val points = (length / 1.0).toInt().coerceIn(4, 96)

    private fun along(fraction: Double): Vec3d = start.add(dir.multiply(length * fraction))

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val growth = easeOut(time / 3f)
        val fade = envelope(t, 0.02f, 0.65f)
        val w = width * (0.35f + 0.65f * growth) * (0.5f + 0.5f * fade)
        val tip = min(1.0, time / 2.0)
        val path = List(points + 1) { along(it.toDouble() / points * tip) }

        // Weiches Glühen außen, dann Plasma-Hülle, dann weißer Kern.
        d.ribbon(VfxLayers.GLOW_ADD, listOf(path.first(), path.last()), { w * 2.4 }, { withAlpha(pal.main, 0.22f * fade) })
        d.ribbon(VfxLayers.ENERGY, path, { w * (1.15 + 0.15 * sin(it * 40.0 + time * 1.3)) }, { withAlpha(pal.main, 0.8f * fade) }, uScale = 0.18f)
        d.ribbon(VfxLayers.ENERGY, path, { w * 0.55 }, { withAlpha(pal.main, 0.8f * fade) }, uScale = 0.32f, uOffset = 7.3f)
        d.ribbon(VfxLayers.GLOW_ADD, listOf(path.first(), path.last()), { w * 0.26 }, { withAlpha(pal.core, 0.9f * fade) })

        // Drei Spiralbänder, die um den Strahl fräsen.
        val (r, u) = basis
        for (strand in 0 until 3) {
            val offset = strand * (PI * 2.0 / 3.0)
            val helix = List(points * 2 + 1) { i ->
                val f = i.toDouble() / (points * 2) * tip
                val angle = f * length * 1.1 + offset - time * 0.45
                val radius = w * (1.0 + 0.18 * sin(f * 24.0 + time))
                along(f).add(r.multiply(cos(angle) * radius)).add(u.multiply(sin(angle) * radius))
            }
            d.ribbon(VfxLayers.ENERGY, helix, { w * 0.17 }, { withAlpha(if (strand == 0) pal.alt else pal.main, 0.8f * fade) }, uScale = 0.6f, uOffset = strand * 3.1f)
        }

        // Runenringe gleiten am Strahl entlang.
        val ringCount = (length / 5.0).toInt().coerceIn(1, 8)
        for (i in 0 until ringCount) {
            val f = ((i + time * 0.14f) / ringCount) % 1f
            if (f > tip) continue
            val ringFade = envelope(f, 0.08f, 0.2f) * fade
            val radius = w * (1.5 + 0.8 * sin(i * 2.4 + time * 0.3))
            d.ring(VfxLayers.RING_ADD, along(f.toDouble()), r, u, radius, radius * 1.32, withAlpha(pal.main, ringFade), withAlpha(pal.edge, ringFade * 0.7f), 28, 3f, -time * 0.05f + i * 0.3f)
        }

        // Mündungskreis.
        val muzzle = start.add(dir.multiply(0.7))
        d.disc(VfxLayers.RUNE_ADD, muzzle, r, u, w * 1.5 * growth, withAlpha(pal.main, fade), time * 0.22)
        d.billboard(VfxLayers.FLARE_ADD, muzzle, w * 1.8 * (1.0 + 0.25 * sin(time * 1.7)), withAlpha(pal.core, fade), time * 0.06)

        // Einschlag.
        if (tip >= 1.0) {
            val impact = end
            val pulse = 1.0 + 0.3 * sin(time * 2.1)
            d.billboard(VfxLayers.FLARE_ADD, impact, w * 4.6 * pulse, withAlpha(pal.core, fade), -time * 0.09)
            d.billboard(VfxLayers.GLOW_ADD, impact, w * 4.5, withAlpha(pal.main, 0.4f * fade))
            d.sphere(VfxLayers.ENERGY, impact, w * (1.2 + 2.4 * t), { view -> withAlpha(pal.main, (1f - view).pow(1.5f) * fade) }, 10, 16, time * 0.1f)
            d.ring(VfxLayers.ENERGY, impact, r, u, w * (2.0 + 5.0 * t), w * (2.5 + 6.5 * t), withAlpha(pal.main, fade), withAlpha(pal.edge, 0f), 36, 4f, time * 0.03f)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Schockwelle
// -------------------------------------------------------------------------------------------------

/** Rohe Trümmer und Glut, die jeder Boden-Effekt mit sich reißt. */
private class Debris(seed: Long, val center: Vec3d, count: Int, val radius: Double, val speed: Double) {
    private val rnd = Random(seed)

    private class Piece(
        val angle: Double, val dist: Double, val vy: Double, val vh: Double,
        val size: Double, val spin: Double, val axisTilt: Double, val delay: Float,
    )

    private val pieces = List(count) {
        Piece(
            rnd.nextDouble() * PI * 2.0, 0.2 + rnd.nextDouble() * radius * 0.35,
            0.18 + rnd.nextDouble() * 0.34, speed * (0.3 + rnd.nextDouble() * 0.7),
            0.04 + rnd.nextDouble() * 0.09, rnd.nextDouble() * 0.5 + 0.1, rnd.nextDouble() * PI, rnd.nextFloat() * 3f,
        )
    }

    fun render(d: VfxDraw, time: Float, life: Int, color: Int) {
        for (p in pieces) {
            val tt = (time - p.delay).coerceAtLeast(0f)
            val h = p.dist + p.vh * tt
            val y = p.vy * tt - 0.022 * tt * tt
            if (y < -0.2) continue
            val fade = envelope(tt / life, 0.02f, 0.3f)
            val pos = center.add(cos(p.angle) * h, y + 0.1, sin(p.angle) * h)
            val a = tt * p.spin + p.axisTilt
            val ax = Vec3d(cos(a), sin(a), 0.0).multiply(p.size)
            val ay = Vec3d(-sin(a) * cos(p.axisTilt), cos(a) * cos(p.axisTilt), sin(p.axisTilt)).multiply(p.size * 0.7)
            val az = ax.normalize().crossProduct(ay.normalize()).multiply(p.size * 0.8)
            d.box(VfxLayers.SHARD_ALPHA, pos, ax, ay, az, withAlpha(color, fade))
        }
    }
}

/** Bodenring, stehende Lichtwand, zweite Nachwelle und Trümmerflug. */
class ShockwaveEffect(
    private val center: Vec3d, private val pal: Palette, private val radius: Float,
    private val height: Float, life: Int, seed: Long,
) : VfxEffect(life) {

    private val debris = Debris(seed, center, (radius * 7f).toInt().coerceIn(8, 80), radius.toDouble(), radius * 0.075)

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val r = easeOut(t) * radius
        val fade = envelope(t, 0.01f, 0.6f)
        val y = center.add(0.0, 0.08, 0.0)
        val band = (radius * 0.16f * (1f - t * 0.6f)).toDouble().coerceAtLeast(0.15)

        d.disc(VfxLayers.GLOW_ADD, y, X, Z, r * 1.25, withAlpha(pal.main, 0.2f * fade))
        d.ring(VfxLayers.ENERGY, y, X, Z, max(0.0, r - band * 1.6), r + band, withAlpha(pal.main, 0f), withAlpha(pal.main, fade), 64, 6f, time * 0.04f)
        d.ring(VfxLayers.RING_ADD, y.add(0.0, 0.02, 0.0), X, Z, r * 0.72, r * 0.96, withAlpha(pal.main, fade * 0.7f), withAlpha(pal.edge, fade * 0.7f), 64, 6f, time * 0.02f)

        val h = height * (1f - t * 0.7f) * easeOut(t * 4f)
        d.wall(VfxLayers.RAYS_ADD, y, X, Z, UP, r.toDouble(), r * 1.04, h.toDouble(), withAlpha(pal.main, fade * 0.65f), withAlpha(pal.main, 0f), 64, 8f, time * 0.03f)
        d.wall(VfxLayers.ENERGY, y, X, Z, UP, r.toDouble(), r * 1.02, h * 0.55, withAlpha(pal.main, fade * 0.6f), withAlpha(pal.edge, 0f), 64, 6f, -time * 0.05f)

        // Nachwelle.
        val t2 = ((time - 3f) / life).coerceIn(0f, 1f)
        if (t2 > 0f) {
            val r2 = easeOut(t2) * radius * 0.72
            val f2 = envelope(t2, 0.01f, 0.7f) * 0.7f
            d.ring(VfxLayers.ENERGY, y, X, Z, max(0.0, r2 - band), r2 + band * 0.6, withAlpha(pal.alt, 0f), withAlpha(pal.alt, f2), 48, 5f, -time * 0.05f)
        }

        val flash = 1f - (time / 5f).coerceIn(0f, 1f)
        if (flash > 0f) {
            d.billboard(VfxLayers.FLARE_ADD, center.add(0.0, 0.8, 0.0), radius * 0.6 * (1f + (1f - flash)), withAlpha(pal.core, flash * 0.8f))
        }
        debris.render(d, time, life, pal.main)
    }
}

// -------------------------------------------------------------------------------------------------
// Sichel
// -------------------------------------------------------------------------------------------------

/** Fliegende Lichtsichel; drei gestaffelte Bögen und ein Nachglühen. */
class CrescentEffect(
    private val origin: Vec3d, direction: Vec3d, private val pal: Palette,
    private val range: Float, private val width: Float, life: Int,
) : VfxEffect(life) {

    private val dir = if (direction.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else direction.normalize()
    private val side = dir.crossProduct(UP).let { if (it.lengthSquared() < 1e-4) X else it.normalize() }
    private val tiltUp = side.crossProduct(dir).normalize()

    private fun arc(center: Vec3d, radius: Double, spread: Double, thick: Double, color: Int, uOffset: Float, d: VfxDraw) {
        val vc = d.buffer(VfxLayers.ENERGY)
        val segments = 28
        with(d) {
            for (i in 0 until segments) {
                val f0 = i.toFloat() / segments
                val f1 = (i + 1f) / segments
                val a0 = (f0 - 0.5) * spread
                val a1 = (f1 - 0.5) * spread
                val t0 = sin(f0 * PI) // spitze Enden
                val t1 = sin(f1 * PI)
                fun at(a: Double, offset: Double): Vec3d =
                    center.add(dir.multiply(cos(a) * (radius + offset))).add(side.multiply(sin(a) * (radius + offset)))
                vc.put(at(a0, -thick * t0).add(tiltUp.multiply(thick * 0.3 * t0)), uOffset + f0 * 2f, 0f, color)
                vc.put(at(a0, thick * t0).add(tiltUp.multiply(-thick * 0.3 * t0)), uOffset + f0 * 2f, 1f, color)
                vc.put(at(a1, thick * t1).add(tiltUp.multiply(-thick * 0.3 * t1)), uOffset + f1 * 2f, 1f, color)
                vc.put(at(a1, -thick * t1).add(tiltUp.multiply(thick * 0.3 * t1)), uOffset + f1 * 2f, 0f, color)
            }
        }
    }

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val travel = easeOut(t) * range
        val fade = envelope(t, 0.01f, 0.4f)
        val w = width * (0.5 + 0.5 * easeOut(t * 3f))
        for (i in 0 until 4) {
            val back = i * 0.9
            val c = origin.add(dir.multiply(max(0.0, travel - back) - w * 0.5))
            val f = fade * (1f - i * 0.22f)
            arc(c, w, 2.2, w * 0.28, withAlpha(if (i == 0) pal.core else pal.main, f), time * 0.06f + i, d)
        }
        arc(origin.add(dir.multiply(max(0.0, travel - w * 0.4))).add(tiltUp.multiply(w * 0.35)), w * 0.75, 1.9, w * 0.14, withAlpha(pal.alt, fade), time * 0.1f, d)
        arc(origin.add(dir.multiply(max(0.0, travel - w * 0.4))).add(tiltUp.multiply(-w * 0.35)), w * 0.75, 1.9, w * 0.14, withAlpha(pal.alt, fade), -time * 0.1f, d)
        d.billboard(VfxLayers.GLOW_ADD, origin.add(dir.multiply(travel + w * 0.4)), w * 1.6, withAlpha(pal.main, 0.45f * fade))
    }
}

// -------------------------------------------------------------------------------------------------
// Blitz
// -------------------------------------------------------------------------------------------------

/** Verästelter Blitz, der sich alle zwei Ticks neu verzweigt. */
class BoltEffect(
    private val start: Vec3d, private val end: Vec3d, private val pal: Palette,
    private val width: Float, life: Int, seed: Long,
) : VfxEffect(life) {

    private val rnd = Random(seed)
    private var paths: List<List<Vec3d>> = emptyList()

    init {
        regenerate()
    }

    private fun jag(a: Vec3d, b: Vec3d, depth: Int, spread: Double): List<Vec3d> {
        var pts = listOf(a, b)
        var s = spread
        repeat(depth) {
            val next = ArrayList<Vec3d>(pts.size * 2)
            for (i in 0 until pts.size - 1) {
                val p = pts[i]
                val q = pts[i + 1]
                next.add(p)
                next.add(
                    p.lerp(q, 0.5).add(
                        (rnd.nextDouble() - 0.5) * s, (rnd.nextDouble() - 0.5) * s, (rnd.nextDouble() - 0.5) * s,
                    ),
                )
            }
            next.add(pts.last())
            pts = next
            s *= 0.55
        }
        return pts
    }

    private fun regenerate() {
        val length = start.distanceTo(end)
        val main = jag(start, end, 5, length * 0.28)
        val out = ArrayList<List<Vec3d>>()
        out.add(main)
        val branches = 2 + (length / 6.0).toInt().coerceAtMost(5)
        repeat(branches) {
            val i = rnd.nextInt((main.size - 2).coerceAtLeast(1))
            val root = main[i]
            val remaining = length * (0.15 + rnd.nextDouble() * 0.25)
            val dir = end.subtract(start).normalize().add(
                (rnd.nextDouble() - 0.5) * 1.6, (rnd.nextDouble() - 0.5) * 1.6, (rnd.nextDouble() - 0.5) * 1.6,
            ).normalize()
            out.add(jag(root, root.add(dir.multiply(remaining)), 3, remaining * 0.3))
        }
        paths = out
    }

    override fun tick(world: ClientWorld) {
        if (age % 2 == 0) regenerate()
    }

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val fade = (1f - t).pow(0.7f)
        val flicker = if ((time.toInt() and 1) == 0) 1f else 0.72f
        for ((i, path) in paths.withIndex()) {
            val w = width * (if (i == 0) 1.0 else 0.45)
            d.ribbon(VfxLayers.GLOW_ADD, path, { w * 1.05 }, { withAlpha(pal.main, 0.8f * fade * flicker) })
            d.ribbon(VfxLayers.GLOW_ADD, path, { w * 0.34 }, { withAlpha(pal.core, fade * flicker) })
        }
        d.billboard(VfxLayers.FLARE_ADD, end, width * 3.4, withAlpha(pal.core, fade * flicker), time * 0.2)
        d.billboard(VfxLayers.GLOW_ADD, start, width * 2.2, withAlpha(pal.main, 0.6f * fade * flicker))
    }
}

// -------------------------------------------------------------------------------------------------
// Säule
// -------------------------------------------------------------------------------------------------

/** Aufsteigende Lichtsäule mit Bodenrune und hochgleitenden Ringen. */
class PillarEffect(
    private val center: Vec3d, private val pal: Palette, private val radius: Float,
    private val height: Float, life: Int,
) : VfxEffect(life) {

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val rise = easeOut(t * 3.5f)
        val fade = envelope(t, 0.03f, 0.45f)
        val h = (height * rise).toDouble()
        val r = radius * (0.72 + 0.28 * easeOut(t * 5f))
        val base = center.add(0.0, 0.06, 0.0)
        d.disc(VfxLayers.RUNE_ADD, base, X, Z, r * 1.7, withAlpha(pal.main, fade), time * 0.08)
        d.disc(VfxLayers.GLOW_ADD, base, X, Z, r * 2.6, withAlpha(pal.main, 0.4f * fade))
        d.wall(VfxLayers.RAYS_ADD, base, X, Z, UP, r, r * 0.9, h, withAlpha(pal.main, fade * 0.7f), withAlpha(pal.main, 0f), 28, 3f, time * 0.04f)
        d.wall(VfxLayers.ENERGY, base, X, Z, UP, r * 0.8, r * 0.62, h, withAlpha(pal.main, fade * 0.65f), withAlpha(pal.edge, 0f), 28, 3f, -time * 0.08f)
        d.ribbon(VfxLayers.GLOW_ADD, listOf(base, base.add(0.0, h, 0.0)), { r * 0.42 }, { withAlpha(pal.core, 0.55f * fade * (1f - it * 0.7f)) })
        for (i in 0 until 4) {
            val f = ((i * 0.25f + time * 0.03f) % 1f)
            val pos = base.add(0.0, h * f, 0.0)
            val rr = r * (1.2 - 0.5 * f)
            val a = fade * envelope(f, 0.08f, 0.35f)
            d.ring(VfxLayers.RING_ADD, pos, X, Z, rr, rr * 1.25, withAlpha(pal.main, a), withAlpha(pal.edge, a * 0.5f), 28, 3f, time * 0.05f)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Telegraphen
// -------------------------------------------------------------------------------------------------

/** Warnkreis: Runenkreis mit wachsender Füllung; blitzt kurz vor dem Einschlag auf. */
class TelegraphRingEffect(
    private val center: Vec3d, private val pal: Palette, private val radius: Float, life: Int,
) : VfxEffect(life) {

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val pos = center.add(0.0, 0.09, 0.0)
        val blink = if (t > 0.75f) 0.75f + 0.25f * sin(time * 2.2f) else 0.75f
        d.disc(VfxLayers.RUNE_ADD, pos, X, Z, radius.toDouble(), withAlpha(pal.main, blink * easeOut(t * 5f)), time * 0.035)
        d.ring(VfxLayers.ENERGY, pos, X, Z, 0.0, (radius * easeInOut(t)).toDouble(), withAlpha(pal.main, 0.35f), withAlpha(pal.main, 0.5f), 48, 3f, time * 0.05f)
        d.ring(VfxLayers.ENERGY, pos, X, Z, radius * 0.97, radius * 1.04, withAlpha(pal.core, blink), withAlpha(pal.core, blink), 64, 6f, time * 0.1f)
        d.wall(VfxLayers.RAYS_ADD, pos, X, Z, UP, radius.toDouble(), radius.toDouble(), 1.2, withAlpha(pal.main, 0.6f * t), withAlpha(pal.main, 0f), 48, 5f, time * 0.03f)
    }
}

/** Warnlinie: leuchtender Korridor, in dem Pfeile zum Ziel laufen. */
class TelegraphLineEffect(
    private val from: Vec3d, private val to: Vec3d, private val pal: Palette, private val width: Float, life: Int,
) : VfxEffect(life) {

    private val delta = to.subtract(from)
    private val dir = if (delta.lengthSquared() < 1e-6) Z else delta.normalize()
    private val side = dir.crossProduct(UP).let { if (it.lengthSquared() < 1e-4) X else it.normalize() }

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val blink = if (t > 0.7f) 0.7f + 0.3f * sin(time * 2.6f) else 0.7f
        val len = delta.length()
        val a = from.add(0.0, 0.1, 0.0)
        val w = side.multiply(width.toDouble() * easeOut(t * 4f))
        val vc = d.buffer(VfxLayers.ENERGY)
        with(d) {
            val col = withAlpha(pal.main, 0.8f * blink)
            val steps = (len / 2.0).toInt().coerceIn(1, 40)
            for (i in 0 until steps) {
                val f0 = i.toDouble() / steps
                val f1 = (i + 1.0) / steps
                val p0 = a.add(dir.multiply(len * f0))
                val p1 = a.add(dir.multiply(len * f1))
                vc.put(p0.subtract(w), (f0 * len * 0.2).toFloat() - time * 0.1f, 0f, col)
                vc.put(p0.add(w), (f0 * len * 0.2).toFloat() - time * 0.1f, 1f, col)
                vc.put(p1.add(w), (f1 * len * 0.2).toFloat() - time * 0.1f, 1f, col)
                vc.put(p1.subtract(w), (f1 * len * 0.2).toFloat() - time * 0.1f, 0f, col)
            }
        }
        // Pfeile, die mit wachsendem Fortschritt schneller laufen.
        val arrows = (len / 3.5).toInt().coerceIn(1, 14)
        for (i in 0 until arrows) {
            val f = ((i.toFloat() / arrows + time * 0.035f * (1f + t * 2f)) % 1f).toDouble()
            val tipPos = a.add(dir.multiply(len * f)).add(0.0, 0.02, 0.0)
            val back = tipPos.subtract(dir.multiply(width * 0.9))
            val alpha = blink * envelope(f.toFloat(), 0.05f, 0.15f)
            d.ribbon(VfxLayers.GLOW_ADD, listOf(back.subtract(w), tipPos), { 0.14 }, { withAlpha(pal.core, alpha) })
            d.ribbon(VfxLayers.GLOW_ADD, listOf(back.add(w), tipPos), { 0.14 }, { withAlpha(pal.core, alpha) })
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Schild
// -------------------------------------------------------------------------------------------------

/** Facettierte Lichtkugel um ein Wesen: blendet auf und zerspringt in fliegende Scherben. */
class ShieldEffect(
    private val entity: Entity?, private val fallback: Vec3d, private val pal: Palette,
    private val radius: Float, life: Int, seed: Long,
) : VfxEffect(life) {

    private class Facet(val normal: Vec3d, val a: Vec3d, val b: Vec3d, val speed: Double, val spin: Double)

    private val facets: List<Facet>

    init {
        val rnd = Random(seed)
        val out = ArrayList<Facet>()
        val lat = 7
        val lon = 12
        for (i in 0 until lat) {
            val phi = PI * (i + 0.5) / lat
            val count = max(3, (lon * sin(phi)).toInt())
            for (j in 0 until count) {
                val th = 2.0 * PI * (j + (i % 2) * 0.5) / count
                val n = Vec3d(sin(phi) * cos(th), cos(phi), sin(phi) * sin(th))
                val (a, b) = basisOf(n)
                out.add(Facet(n, a, b, 0.05 + rnd.nextDouble() * 0.18, (rnd.nextDouble() - 0.5) * 0.5))
            }
        }
        facets = out
    }

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val center = (entity?.takeIf { it.isAlive }?.let { it.lerped(d.tickDelta).add(0.0, it.height * 0.5, 0.0) }) ?: fallback.add(0.0, 1.0, 0.0)
        val form = easeOut(t * 5f)
        val shatterStart = 0.55f
        val shatter = ((t - shatterStart) / (1f - shatterStart)).coerceIn(0f, 1f)
        val fade = if (shatter > 0f) (1f - shatter).pow(1.5f) else form
        val r = radius * (0.7 + 0.3 * form)
        val size = r * 0.32
        val vc = d.buffer(VfxLayers.ENERGY)
        with(d) {
            for (f in facets) {
                val fly = shatter * shatter * life * f.speed * 3.0
                val p = center.add(f.normal.multiply(r + fly))
                val view = p.subtract(cam).normalize()
                val rim = 1f - abs(f.normal.dotProduct(view)).toFloat()
                val alpha = fade * (0.06f + rim * rim * 0.75f)
                val col = withAlpha(if (rim > 0.75f) pal.core else pal.main, alpha)
                val rot = shatter * life * f.spin
                val ax = f.a.multiply(cos(rot) * size).add(f.b.multiply(sin(rot) * size))
                val ay = f.a.multiply(-sin(rot) * size).add(f.b.multiply(cos(rot) * size))
                vc.put(p.subtract(ax).subtract(ay), time * 0.05f, 0f, col)
                vc.put(p.add(ax).subtract(ay), time * 0.05f + 0.4f, 0f, col)
                vc.put(p.add(ax).add(ay), time * 0.05f + 0.4f, 1f, col)
                vc.put(p.subtract(ax).add(ay), time * 0.05f, 1f, col)
            }
        }
        val flash = 1f - (time / 6f).coerceIn(0f, 1f)
        if (flash > 0f) d.billboard(VfxLayers.FLARE_ADD, center, radius * 1.6, withAlpha(pal.core, flash * 0.7f))
    }
}

// -------------------------------------------------------------------------------------------------
// Flügel
// -------------------------------------------------------------------------------------------------

/**
 * Sechs Seraph-Flügel aus einzelnen Federn, die einem Wesen folgen. Jede Feder ist ein eigenes
 * Polygon und schwingt phasenversetzt mit — dadurch wirkt der Flügel wirklich dreidimensional.
 */
class WingsEffect(
    private val entity: Entity, private val pal: Palette, private val spread: Float, life: Int,
    private val flameWing: Boolean,
) : VfxEffect(life) {

    override fun finished(): Boolean = !entity.isAlive || entity.isRemoved

    private fun wing(d: VfxDraw, root: Vec3d, side: Double, forward: Vec3d, right: Vec3d, lift: Double, scale: Double, flap: Double, alpha: Float) {
        val feathers = 17
        val up = UP
        val vc = d.buffer(if (flameWing) VfxLayers.FEATHER_ADD else VfxLayers.FEATHER_ALPHA)
        with(d) {
            for (i in 0 until feathers) {
                val f = i.toDouble() / (feathers - 1)
                // Knochen des Flügels: Bogen vom Rücken nach außen und oben.
                val boneAngle = lift + flap * (0.35 + f * 0.65)
                val reach = scale * (0.25 + f * 1.45)
                val bone = root
                    .add(right.multiply(side * reach * cos(boneAngle)))
                    .add(up.multiply(reach * sin(boneAngle) + f * f * scale * 0.4))
                    .add(forward.multiply(-0.28 * scale * f))
                // Federblatt hängt nach außen-unten.
                val fall = 0.55 + f * 1.05 - flap * 0.12
                val dirOut = right.multiply(side * sin(fall)).add(up.multiply(-cos(fall))).normalize()
                val len = scale * (1.12 - 0.25 * abs(f - 0.6) * 2.0)
                val widthVec = dirOut.crossProduct(forward).normalize().multiply(scale * 0.17).add(forward.multiply(scale * 0.05 * (f - 0.5)))
                val tipCol = withAlpha(lerpColor(pal.core, pal.main, f.toFloat() * 0.35f), alpha)
                val rootCol = withAlpha(lerpColor(pal.core, pal.main, f.toFloat() * 0.12f), alpha)
                val tip = bone.add(dirOut.multiply(len))
                vc.put(bone.subtract(widthVec), 0f, 1f, rootCol)
                vc.put(bone.add(widthVec), 1f, 1f, rootCol)
                vc.put(tip.add(widthVec), 1f, 0f, tipCol)
                vc.put(tip.subtract(widthVec), 0f, 0f, tipCol)
            }
        }
        // Lichtsaum.
        val edge = List(feathers) { i ->
            val f = i.toDouble() / (feathers - 1)
            val boneAngle = lift + flap * (0.35 + f * 0.65)
            val reach = scale * (0.25 + f * 1.45)
            root.add(right.multiply(side * reach * cos(boneAngle)))
                .add(up.multiply(reach * sin(boneAngle) + f * f * scale * 0.4))
                .add(forward.multiply(-0.28 * scale * f))
        }
        d.ribbon(VfxLayers.GLOW_ADD, edge, { 0.1 * scale * (1.0 - it * 0.6) }, { withAlpha(pal.core, alpha * 0.55f) })
    }

    override fun render(d: VfxDraw, time: Float) {
        if (isSelfFirstPerson(entity)) return
        val t = progress(time)
        val alpha = envelope(t, 12f / life, 20f / life)
        val pos = entity.lerped(d.tickDelta)
        val yaw = if (entity is LivingEntity) MathHelper.lerp(d.tickDelta, entity.lastBodyYaw, entity.bodyYaw) else entity.yaw
        val rad = Math.toRadians(yaw.toDouble())
        val forward = Vec3d(-sin(rad), 0.0, cos(rad))
        val right = Vec3d(cos(rad), 0.0, sin(rad))
        val shoulder = pos.add(0.0, entity.height * 0.74, 0.0).subtract(forward.multiply(0.22))
        val flap = sin(time * 0.17) * 0.3 + sin(time * 0.05) * 0.1
        val s = spread.toDouble()
        val unfold = easeOut(time / 16f).toDouble()
        for (side in doubleArrayOf(-1.0, 1.0)) {
            wing(d, shoulder.add(0.0, 0.3, 0.0), side, forward, right, 0.1 + 0.65 * unfold, s * 1.25 * unfold, flap, alpha)
            wing(d, shoulder.add(0.0, 0.0, 0.0).subtract(forward.multiply(0.1)), side, forward, right, -0.15 + 0.25 * unfold, s * 1.0 * unfold, flap * 0.8, alpha * 0.92f)
            wing(d, shoulder.add(0.0, -0.32, 0.0).subtract(forward.multiply(0.18)), side, forward, right, -0.75 * unfold, s * 0.72 * unfold, flap * 0.6, alpha * 0.85f)
        }
        d.billboard(VfxLayers.GLOW_ADD, shoulder, s * 1.5, withAlpha(pal.main, 0.22f * alpha))
    }
}

// -------------------------------------------------------------------------------------------------
// Kette
// -------------------------------------------------------------------------------------------------

/** Echte 3D-Kette mit Gliedern; schießt heraus, glüht zum Ziel hin und zieht sich wieder ein. */
class ChainEffect(
    private val from: Entity?, private val fromFallback: Vec3d, private val toEntity: Entity?,
    private val toFallback: Vec3d, private val pal: Palette, life: Int,
) : VfxEffect(life) {

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val startPos = from?.takeIf { it.isAlive }?.let {
            if (isSelfFirstPerson(it)) d.cam.add(d.camForward.multiply(0.6)).add(d.camRight.multiply(0.35)).add(d.camUp.multiply(-0.3))
            else it.lerped(d.tickDelta).add(0.0, it.height * 0.65, 0.0)
        } ?: fromFallback
        val endPos = toEntity?.takeIf { it.isAlive }?.let { it.lerped(d.tickDelta).add(0.0, it.height * 0.5, 0.0) } ?: toFallback
        val shoot = easeOut(time / 3f)
        val retract = if (t > 0.65f) ((t - 0.65f) / 0.35f) else 0f
        val delta = endPos.subtract(startPos)
        val len = delta.length()
        if (len < 0.2) return
        val dir = delta.multiply(1.0 / len)
        val reach = len * shoot * (1f - retract)
        val (ra, rb) = basisOf(dir)
        val spacing = 0.34
        val links = (reach / spacing).toInt().coerceAtMost(180)
        val fade = 1f - retract * retract
        // Lichtschein unter der Kette.
        if (reach > 0.5) {
            val tipPoint = startPos.add(dir.multiply(reach))
            d.ribbon(VfxLayers.GLOW_ADD, listOf(startPos, tipPoint), { 0.42 }, { withAlpha(pal.alt, 0.34f * fade * it) })
            d.billboard(VfxLayers.FLARE_ADD, tipPoint, 1.4, withAlpha(pal.alt, fade), time * 0.3)
        }
        for (i in 0 until links) {
            val f = i * spacing
            val sag = sin(f / max(reach, 0.1) * PI) * reach * 0.012 * (1f - t)
            val center = startPos.add(dir.multiply(f)).add(0.0, -sag, 0.0)
            val odd = i % 2 == 0
            val twistA = if (odd) ra else rb
            val heat = (f / max(reach, 0.1)).toFloat().pow(2f)
            val color = withAlpha(lerpColor(0xFFC8CCD6.toInt(), pal.alt, heat), fade)
            val h = 0.21
            val w = 0.085
            val th = 0.024
            // Ein Glied: zwei Längs- und zwei Querstreben.
            val axial = dir.multiply(h)
            val lateral = twistA.multiply(w)
            val normal = dir.crossProduct(twistA).multiply(th)
            d.box(VfxLayers.CHAIN_ALPHA, center.add(lateral), axial, twistA.multiply(th), normal, color)
            d.box(VfxLayers.CHAIN_ALPHA, center.subtract(lateral), axial, twistA.multiply(th), normal, color)
            d.box(VfxLayers.CHAIN_ALPHA, center.add(axial), dir.multiply(th), lateral, normal, color)
            d.box(VfxLayers.CHAIN_ALPHA, center.subtract(axial), dir.multiply(th), lateral, normal, color)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Spur
// -------------------------------------------------------------------------------------------------

/** Leuchtendes Band hinter einem Wesen; zeichnet die letzten Positionen nach. */
class TrailEffect(
    private val entity: Entity, private val pal: Palette, private val width: Float, life: Int,
) : VfxEffect(life) {

    private val points = ArrayDeque<Vec3d>()
    private var deadFor = 0

    private fun anchor(tickDelta: Float): Vec3d = entity.lerped(tickDelta).add(0.0, entity.height * 0.5, 0.0)

    override fun tick(world: ClientWorld) {
        if (entity.isAlive && !entity.isRemoved) {
            points.addLast(anchor(1f))
        } else {
            deadFor++
        }
        if (points.size > 22 || deadFor > 0) points.removeFirstOrNull()
    }

    override fun finished(): Boolean = deadFor > 0 && points.size < 2

    override fun render(d: VfxDraw, time: Float) {
        if (points.size < 2) return
        val fade = envelope(progress(time), 0f, 0.2f)
        val path = ArrayList<Vec3d>(points.size + 1)
        path.addAll(points)
        if (entity.isAlive && !entity.isRemoved) path.add(anchor(d.tickDelta))
        d.ribbon(VfxLayers.ENERGY, path, { width * it.toDouble() }, { withAlpha(pal.main, it * fade) }, uScale = 0.3f)
        d.ribbon(VfxLayers.GLOW_ADD, path, { width * 1.5 * it }, { withAlpha(pal.core, it * it * 0.55f * fade) }, uScale = 0.3f)
    }
}

// -------------------------------------------------------------------------------------------------
// Portal
// -------------------------------------------------------------------------------------------------

/** Wirbel aus verdrillten Ringen mit gleißendem Kern und Strahlenkranz. */
class RiftEffect(
    private val center: Vec3d, normal: Vec3d, private val pal: Palette, private val radius: Float, life: Int,
) : VfxEffect(life) {

    private val n = if (normal.lengthSquared() < 1e-6) UP else normal.normalize()
    private val basis = basisOf(n)

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val open = easeOut(t * 4f) * envelope(t, 0f, 0.15f)
        val r = radius * open.toDouble()
        val (a, b) = basis
        val vc = d.buffer(VfxLayers.ENERGY)
        with(d) {
            val rings = 7
            val segments = 40
            for (ring in 0 until rings) {
                val r0 = r * ring / rings
                val r1 = r * (ring + 1) / rings
                val depth0 = (1.0 - ring.toDouble() / rings).pow(2.0) * r * 0.9
                val depth1 = (1.0 - (ring + 1.0) / rings).pow(2.0) * r * 0.9
                val twist0 = time * 0.11 + ring * 0.7
                val twist1 = time * 0.11 + (ring + 1) * 0.7
                val alpha = (0.45f + 0.55f * ring / rings) * open
                val color = withAlpha(lerpColor(pal.core, pal.main, ring.toFloat() / rings), alpha)
                for (i in 0 until segments) {
                    val p0 = i.toDouble() / segments * PI * 2.0
                    val p1 = (i + 1.0) / segments * PI * 2.0
                    fun at(radiusValue: Double, depth: Double, phase: Double, tw: Double): Vec3d =
                        center.subtract(n.multiply(depth))
                            .add(a.multiply(cos(phase + tw) * radiusValue))
                            .add(b.multiply(sin(phase + tw) * radiusValue))
                    val u0 = i.toFloat() / segments * 4f
                    val u1 = (i + 1f) / segments * 4f
                    vc.put(at(r0, depth0, p0, twist0), u0, 0f, color)
                    vc.put(at(r0, depth0, p1, twist0), u1, 0f, color)
                    vc.put(at(r1, depth1, p1, twist1), u1, 1f, color)
                    vc.put(at(r1, depth1, p0, twist1), u0, 1f, color)
                }
            }
        }
        d.ring(VfxLayers.RING_ADD, center, a, b, r, r * 1.12, withAlpha(pal.core, open), withAlpha(pal.main, open * 0.6f), 48, 6f, time * 0.04f)
        d.ring(VfxLayers.GLOW_ADD, center, a, b, r * 0.95, r * 1.5, withAlpha(pal.main, open * 0.6f), withAlpha(pal.main, 0f), 48)
        d.billboard(VfxLayers.FLARE_ADD, center.subtract(n.multiply(r * 0.5)), r * 1.4, withAlpha(pal.core, open), time * 0.05)
    }
}

// -------------------------------------------------------------------------------------------------
// Nova
// -------------------------------------------------------------------------------------------------

/** Explodierende Lichtkugel mit zwei gekippten Druckringen. */
class NovaEffect(private val center: Vec3d, private val pal: Palette, private val radius: Float, life: Int) : VfxEffect(life) {

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val r = easeOut(t) * radius
        val fade = (1f - t).pow(1.3f)
        d.sphere(VfxLayers.ENERGY, center, r.toDouble(), { view -> withAlpha(pal.main, (1f - view).pow(2.5f) * fade * 0.8f) }, 14, 24, time * 0.06f)
        d.billboard(VfxLayers.FLARE_ADD, center, radius * 0.8 * (1f - t * 0.4f), withAlpha(pal.core, fade * 0.8f), time * 0.08)
        d.billboard(VfxLayers.GLOW_ADD, center, radius * 1.3 * easeOut(t * 2f), withAlpha(pal.main, 0.3f * fade))
        for (i in 0 until 2) {
            val tilt = (i * 2 - 1) * 0.5
            val a = X
            val b = Z.multiply(cos(tilt)).add(UP.multiply(sin(tilt)))
            d.ring(VfxLayers.ENERGY, center, a, b, r * 0.92, r * 1.08, withAlpha(pal.main, fade), withAlpha(pal.main, fade), 56, 5f, time * 0.05f)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Runenkreis
// -------------------------------------------------------------------------------------------------

/** Drehender Runenkreis mit Runenband, wahlweise an ein Wesen geheftet. */
class RuneEffect(
    private val center: Vec3d, normal: Vec3d, private val pal: Palette, private val radius: Float,
    life: Int, private val follow: Entity?,
) : VfxEffect(life) {

    private val n = if (normal.lengthSquared() < 1e-6) UP else normal.normalize()
    private val basis = basisOf(n)

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val alpha = envelope(t, 0.1f, 0.25f)
        val pos = (follow?.takeIf { it.isAlive }?.lerped(d.tickDelta)?.add(0.0, 0.08, 0.0)) ?: center
        val (a, b) = basis
        val r = radius * easeOut(t * 5f).toDouble()
        d.disc(VfxLayers.RUNE_ADD, pos, a, b, r, withAlpha(pal.main, alpha), time * 0.06)
        d.disc(VfxLayers.RUNE_ADD, pos.add(n.multiply(0.06)), a, b, r * 0.62, withAlpha(pal.core, alpha * 0.9f), -time * 0.1)
        d.disc(VfxLayers.GLOW_ADD, pos, a, b, r * 1.4, withAlpha(pal.main, 0.3f * alpha))
        d.wall(VfxLayers.RING_ADD, pos, a, b, n, r * 0.9, r * 0.9, r * 0.14, withAlpha(pal.main, alpha * 0.9f), withAlpha(pal.main, alpha * 0.9f), 40, 3f, time * 0.03f)
        d.wall(VfxLayers.RAYS_ADD, pos, a, b, n, r, r * 0.78, r * 0.75, withAlpha(pal.main, alpha * 0.5f), withAlpha(pal.main, 0f), 40, 5f, -time * 0.03f)
    }
}

// -------------------------------------------------------------------------------------------------
// Wirbelsturm
// -------------------------------------------------------------------------------------------------

/** Trichter aus rotierenden Energiebändern, der über den Boden wandert. */
class TornadoEffect(
    private val start: Vec3d, private val velocity: Vec3d, private val pal: Palette,
    private val radius: Float, private val height: Float, life: Int,
) : VfxEffect(life) {

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val alpha = envelope(t, 0.1f, 0.2f)
        val base = start.add(velocity.multiply(time.toDouble()))
        val layers = 10
        for (i in 0 until layers) {
            val f0 = i.toFloat() / layers
            val f1 = (i + 1f) / layers
            val r0 = radius * (0.18 + 0.82 * f0.pow(1.4f)).toDouble()
            val r1 = radius * (0.18 + 0.82 * f1.pow(1.4f)).toDouble()
            val wobble = Vec3d(sin(time * 0.1 + f0 * 4.0) * f0 * radius * 0.25, 0.0, cos(time * 0.13 + f0 * 3.0) * f0 * radius * 0.25)
            val y = base.add(0.0, height * f0.toDouble(), 0.0).add(wobble)
            val a = alpha * (1f - f0 * 0.5f)
            d.wall(VfxLayers.ENERGY, y, X, Z, UP, r0, r1, height.toDouble() / layers, withAlpha(pal.main, a * 0.55f), withAlpha(pal.main, a * 0.55f), 20, 2f, time * 0.2f * (1f + i * 0.08f))
            d.wall(VfxLayers.RAYS_ADD, y, X, Z, UP, r0 * 1.12, r1 * 1.12, height.toDouble() / layers, withAlpha(pal.main, a * 0.28f), withAlpha(pal.main, a * 0.06f), 20, 3f, -time * 0.18f)
        }
        d.disc(VfxLayers.RUNE_ADD, base.add(0.0, 0.06, 0.0), X, Z, radius * 0.9, withAlpha(pal.main, alpha * 0.6f), time * 0.14)
    }
}

// -------------------------------------------------------------------------------------------------
// Aura
// -------------------------------------------------------------------------------------------------

/** Spiralbänder und Bodenkreis, die einem Wesen folgen. */
class AuraEffect(private val entity: Entity, private val pal: Palette, private val radius: Float, life: Int) : VfxEffect(life) {

    override fun finished(): Boolean = !entity.isAlive || entity.isRemoved

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val alpha = envelope(t, 0.06f, 0.15f)
        val pos = entity.lerped(d.tickDelta)
        val self = isSelfFirstPerson(entity)
        val h = entity.height * 1.05
        if (!self) {
            for (strand in 0 until 2) {
                val phase = strand * PI
                val path = List(30) { i ->
                    val f = i / 29.0
                    val a = f * PI * 4.0 + phase + time * 0.32
                    val r = radius * (0.72 - f * 0.28 + 0.08 * sin(time * 0.2 + f * 8.0))
                    pos.add(cos(a) * r, f * h, sin(a) * r)
                }
                d.ribbon(VfxLayers.ENERGY, path, { 0.14 * (1.0 - it * 0.6) }, { withAlpha(if (strand == 0) pal.main else pal.alt, alpha * (1f - it * 0.5f)) }, uScale = 0.8f)
            }
        }
        val y = pos.add(0.0, 0.06, 0.0)
        d.disc(VfxLayers.RUNE_ADD, y, X, Z, radius * 1.15, withAlpha(pal.main, alpha * 0.85f), time * 0.05)
        d.ring(VfxLayers.RING_ADD, y.add(0.0, 0.03, 0.0), X, Z, radius * 1.25, radius * 1.5, withAlpha(pal.main, alpha * 0.7f), withAlpha(pal.edge, 0f), 36, 3f, -time * 0.03f)
    }
}

// -------------------------------------------------------------------------------------------------
// Flut
// -------------------------------------------------------------------------------------------------

/** Ansteigende Flutfläche (Styx) als Ring zwischen zwei Radien. */
class FloodEffect(
    private val center: Vec3d, private val pal: Palette, private val outer: Float,
    private val inner: Float, life: Int,
) : VfxEffect(life) {

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val alpha = envelope(t, 0.2f, 0.2f)
        val y = center.add(0.0, 0.16 + 0.35 * easeOut(t * 3f), 0.0)
        val vc = d.buffer(VfxLayers.FLOW_ADD)
        with(d) {
            val rings = 6
            val segments = 56
            for (ring in 0 until rings) {
                val r0 = inner + (outer - inner) * ring.toDouble() / rings
                val r1 = inner + (outer - inner) * (ring + 1.0) / rings
                for (i in 0 until segments) {
                    val a0 = i.toDouble() / segments * PI * 2.0
                    val a1 = (i + 1.0) / segments * PI * 2.0
                    fun put(r: Double, a: Double) {
                        val px = y.x + cos(a) * r
                        val pz = y.z + sin(a) * r
                        val edge = ((r - inner) / 1.2).coerceIn(0.0, 1.0).toFloat()
                        vc.put(px, y.y, pz, (px * 0.08).toFloat(), (pz * 0.08).toFloat(), withAlpha(pal.main, alpha * (0.2f + 0.65f * edge)))
                    }
                    put(r0, a0)
                    put(r0, a1)
                    put(r1, a1)
                    put(r1, a0)
                }
            }
        }
        d.ring(VfxLayers.ENERGY, y.add(0.0, 0.03, 0.0), X, Z, inner.toDouble(), inner + 0.8, withAlpha(pal.core, alpha), withAlpha(pal.main, 0f), 56, 8f, time * 0.05f)
        d.wall(VfxLayers.RAYS_ADD, y, X, Z, UP, inner.toDouble(), inner.toDouble(), 1.6, withAlpha(pal.main, alpha * 0.7f), withAlpha(pal.main, 0f), 56, 7f, time * 0.03f)
    }
}

// -------------------------------------------------------------------------------------------------
// Speer
// -------------------------------------------------------------------------------------------------

/** Riesiger Lichtspeer: entsteht hoch oben, wird dichter und schlägt am Ende senkrecht ein. */
class SpearEffect(
    private val ground: Vec3d, private val pal: Palette, private val height: Float,
    private val scale: Float, life: Int,
) : VfxEffect(life) {

    private fun lance(d: VfxDraw, tip: Vec3d, s: Double, alpha: Float, time: Float) {
        val length = s * 16.0
        val profile = listOf(0.0 to 0.0, 0.08 to 1.0, 0.16 to 0.7, 0.24 to 0.32, 1.0 to 0.2)
        val sides = 10
        val vc = d.buffer(VfxLayers.ENERGY)
        with(d) {
            for (k in 0 until profile.size - 1) {
                val (f0, w0) = profile[k]
                val (f1, w1) = profile[k + 1]
                val c0 = tip.add(0.0, f0 * length, 0.0)
                val c1 = tip.add(0.0, f1 * length, 0.0)
                val color = withAlpha(if (k == 0) pal.core else pal.main, alpha)
                for (i in 0 until sides) {
                    val a0 = i.toDouble() / sides * PI * 2.0 + time * 0.04
                    val a1 = (i + 1.0) / sides * PI * 2.0 + time * 0.04
                    val r0 = w0 * s * 2.2
                    val r1 = w1 * s * 2.2
                    val u = time * 0.1f
                    vc.put(c0.add(cos(a0) * r0, 0.0, sin(a0) * r0), u + i * 0.3f, 0f, color)
                    vc.put(c0.add(cos(a1) * r0, 0.0, sin(a1) * r0), u + i * 0.3f, 1f, color)
                    vc.put(c1.add(cos(a1) * r1, 0.0, sin(a1) * r1), u + i * 0.3f + 0.6f, 1f, color)
                    vc.put(c1.add(cos(a0) * r1, 0.0, sin(a0) * r1), u + i * 0.3f + 0.6f, 0f, color)
                }
            }
        }
        d.ribbon(VfxLayers.GLOW_ADD, listOf(tip, tip.add(0.0, length, 0.0)), { s * (3.2 - it * 1.8) }, { withAlpha(pal.main, alpha * 0.5f * (1f - it * 0.5f)) })
        d.ribbon(VfxLayers.GLOW_ADD, listOf(tip, tip.add(0.0, length, 0.0)), { s * 0.9 }, { withAlpha(pal.core, alpha) })
        // Parierstange.
        val guard = tip.add(0.0, length * 0.24, 0.0)
        d.ring(VfxLayers.ENERGY, guard, X, Z, s * 0.5, s * 5.2, withAlpha(pal.core, alpha), withAlpha(pal.main, 0f), 40, 4f, time * 0.08f)
    }

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val dropStart = 0.86f
        val s = scale.toDouble()
        val form = easeOut(t / dropStart)
        val tipY = if (t < dropStart) height * (0.92 - 0.04 * sin(time * 0.1)) else height * (1.0 - easeInOut((t - dropStart) / (1f - dropStart)).toDouble())
        val tip = ground.add(0.0, tipY, 0.0)
        lance(d, tip, s * (0.4 + 0.6 * form), (0.25f + 0.75f * form), time)
        // Runenkreise hoch oben und Zielkreis am Boden.
        for (i in 0 until 3) {
            val p = ground.add(0.0, height * (1.05 + i * 0.13), 0.0)
            d.disc(VfxLayers.RUNE_ADD, p, X, Z, s * (9.0 - i * 2.2) * form, withAlpha(pal.main, form), time * 0.05 * (if (i % 2 == 0) 1 else -1))
        }
        d.disc(VfxLayers.RUNE_ADD, ground.add(0.0, 0.1, 0.0), X, Z, s * 13.0, withAlpha(pal.main, form), -time * 0.03)
        d.ribbon(VfxLayers.GLOW_ADD, listOf(ground, tip), { s * 0.5 }, { withAlpha(pal.main, 0.35f * form) })
    }
}

// -------------------------------------------------------------------------------------------------
// Federn
// -------------------------------------------------------------------------------------------------

/** Wolke einzelner, taumelnder Federn. */
class FeathersEffect(
    private val center: Vec3d, private val pal: Palette, count: Int, private val spread: Float, life: Int, seed: Long,
) : VfxEffect(life) {

    private class F(val vx: Double, val vy: Double, val vz: Double, val phase: Double, val size: Double, val tint: Float)

    private val feathers = Random(seed).let { rnd ->
        List(count.coerceIn(1, 120)) {
            F(
                (rnd.nextDouble() - 0.5) * 0.5 * spread, 0.05 + rnd.nextDouble() * 0.25 * spread, (rnd.nextDouble() - 0.5) * 0.5 * spread,
                rnd.nextDouble() * 6.28, 0.16 + rnd.nextDouble() * 0.2, rnd.nextFloat(),
            )
        }
    }

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val alpha = envelope(t, 0.02f, 0.35f)
        val vc = d.buffer(VfxLayers.FEATHER_ALPHA)
        with(d) {
            for (f in feathers) {
                val drag = 1.0 - 1.0 / (1.0 + time * 0.25)
                val dist = drag * 11.0
                val sway = sin(time * 0.17 + f.phase) * 0.35
                val pos = center.add(
                    f.vx * dist + sway, f.vy * dist - time * 0.035, f.vz * dist + cos(time * 0.13 + f.phase) * 0.35,
                )
                val rot = sin(time * 0.12 + f.phase) * 0.9 + f.phase
                val ax = camRight.multiply(cos(rot) * f.size).add(camUp.multiply(sin(rot) * f.size))
                val ay = camRight.multiply(-sin(rot) * f.size * 2.0).add(camUp.multiply(cos(rot) * f.size * 2.0))
                val color = withAlpha(lerpColor(pal.core, pal.main, f.tint * 0.22f), alpha)
                vc.put(pos.subtract(ax).subtract(ay), 0f, 1f, color)
                vc.put(pos.add(ax).subtract(ay), 1f, 1f, color)
                vc.put(pos.add(ax).add(ay), 1f, 0f, color)
                vc.put(pos.subtract(ax).add(ay), 0f, 0f, color)
            }
        }
    }
}

/** Eine einzelne, große Feder, die langsam sinkt — für die Nahaufnahme in der Cutscene. */
class FallingFeatherEffect(
    private val start: Vec3d, private val pal: Palette, private val fall: Float, life: Int,
) : VfxEffect(life) {

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val alpha = envelope(t, 0.05f, 0.05f)
        val sway = sin(time * 0.07)
        val pos = start.add(sway * 0.45, -fall * easeInOut(t).toDouble(), cos(time * 0.05) * 0.3)
        val rot = sway * 0.7 + time * 0.008
        val size = 0.34
        val vc = d.buffer(VfxLayers.FEATHER_ALPHA)
        with(d) {
            val ax = camRight.multiply(cos(rot) * size).add(camUp.multiply(sin(rot) * size))
            val ay = camRight.multiply(-sin(rot) * size * 1.9).add(camUp.multiply(cos(rot) * size * 1.9))
            val color = withAlpha(pal.core, alpha)
            vc.put(pos.subtract(ax).subtract(ay), 0f, 1f, color)
            vc.put(pos.add(ax).subtract(ay), 1f, 1f, color)
            vc.put(pos.add(ax).add(ay), 1f, 0f, color)
            vc.put(pos.subtract(ax).add(ay), 0f, 0f, color)
        }
        d.billboard(VfxLayers.GLOW_ADD, pos, 1.2, withAlpha(pal.main, 0.4f * alpha))
    }
}

/** Kurzer Lichtstern. */
class FlareEffect(private val center: Vec3d, private val pal: Palette, private val size: Float, life: Int) : VfxEffect(life) {
    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val fade = (1f - t).pow(1.2f)
        d.billboard(VfxLayers.FLARE_ADD, center, size * (0.6 + 0.6 * easeOut(t * 2f)), withAlpha(pal.core, fade), time * 0.08)
        d.billboard(VfxLayers.GLOW_ADD, center, size * 1.25, withAlpha(pal.main, 0.55f * fade))
    }
}

/** Zwei gekippte Ringe, die um ein Wesen kreisen. */
class OrbitRingsEffect(private val entity: Entity, private val pal: Palette, private val radius: Float, life: Int) : VfxEffect(life) {
    override fun finished(): Boolean = !entity.isAlive || entity.isRemoved

    override fun render(d: VfxDraw, time: Float) {
        if (isSelfFirstPerson(entity)) return
        val alpha = envelope(progress(time), 0.08f, 0.12f)
        val center = entity.lerped(d.tickDelta).add(0.0, entity.height * 0.55, 0.0)
        for (i in 0 until 3) {
            val tilt = time * (0.05 + i * 0.02) + i * 1.1
            val a = Vec3d(cos(tilt), sin(tilt) * 0.5, sin(tilt))
                .normalize()
            val b = a.crossProduct(Vec3d(0.0, 1.0, 0.2)).normalize()
            d.ring(VfxLayers.RING_ADD, center, a, b, radius * (1.0 + i * 0.16), radius * (1.14 + i * 0.16), withAlpha(pal.main, alpha), withAlpha(pal.edge, alpha * 0.7f), 40, 4f, time * 0.03f)
        }
    }
}
