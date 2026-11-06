package dev.henny.hha.client.vfx

import dev.henny.hha.logic.Vfx
import net.minecraft.client.render.Camera
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Farbpalette eines Effekts: Kern, Hauptfarbe, Rand. */
class Palette(val core: Int, val main: Int, val edge: Int, val alt: Int) {
    companion object {
        private val BY_STYLE = mapOf(
            Vfx.Style.HEAVEN to Palette(0xFFFFFBEA.toInt(), 0xFFFFD75A.toInt(), 0xFFE0A628.toInt(), 0xFFB8E2FF.toInt()),
            Vfx.Style.GOLD to Palette(0xFFFFF4C2.toInt(), 0xFFFFC23C.toInt(), 0xFFB06A16.toInt(), 0xFFFFFFFF.toInt()),
            Vfx.Style.HELL to Palette(0xFFD8FDFF.toInt(), 0xFF3CD6EF.toInt(), 0xFF0B7690.toInt(), 0xFFF7841F.toInt()),
            Vfx.Style.FIRE to Palette(0xFFFFE59A.toInt(), 0xFFF7841F.toInt(), 0xFF9C2A08.toInt(), 0xFF3CD6EF.toInt()),
            Vfx.Style.SOUL to Palette(0xFFD8FDFF.toInt(), 0xFF14A6C4.toInt(), 0xFF064A5C.toInt(), 0xFF8FF2FF.toInt()),
            Vfx.Style.STORM to Palette(0xFFFFFFFF.toInt(), 0xFFA8D8FF.toInt(), 0xFF4A88C0.toInt(), 0xFFFFE27A.toInt()),
            Vfx.Style.HADES to Palette(0xFFE8FFD0.toInt(), 0xFF4CFF5A.toInt(), 0xFF0E7A2C.toInt(), 0xFF9CFF3C.toInt()),
            Vfx.Style.SHADOW to Palette(0xFF6A4FA0.toInt(), 0xFF2C2338.toInt(), 0xFF130E19.toInt(), 0xFF3CD6EF.toInt()),
        )

        fun of(style: Vfx.Style): Palette = BY_STYLE.getValue(style)
    }
}

fun withAlpha(argb: Int, alpha: Float): Int {
    val a = (alpha.coerceIn(0f, 1f) * ((argb ushr 24) and 0xFF)).toInt()
    return (a shl 24) or (argb and 0xFFFFFF)
}

fun lerpColor(from: Int, to: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun ch(shift: Int): Int {
        val a = (from ushr shift) and 0xFF
        val b = (to ushr shift) and 0xFF
        return (a + (b - a) * k).toInt() and 0xFF
    }
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** Sanfte Ein-/Ausblendkurven. */
fun easeOut(t: Float): Float = 1f - (1f - t.coerceIn(0f, 1f)).let { it * it * it }
fun easeInOut(t: Float): Float = t.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

/** Blendet in den ersten [fadeIn] Anteilen ein und in den letzten [fadeOut] Anteilen aus. */
fun envelope(t: Float, fadeIn: Float, fadeOut: Float): Float {
    val a = if (fadeIn > 0f) (t / fadeIn).coerceIn(0f, 1f) else 1f
    val b = if (fadeOut > 0f) ((1f - t) / fadeOut).coerceIn(0f, 1f) else 1f
    return minOf(a, b)
}

/** Orthonormalbasis quer zu einer Richtung. */
fun basisOf(direction: Vec3d): Pair<Vec3d, Vec3d> {
    val dir = if (direction.lengthSquared() < 1e-9) Vec3d(0.0, 1.0, 0.0) else direction.normalize()
    val ref = if (abs(dir.y) > 0.98) Vec3d(1.0, 0.0, 0.0) else Vec3d(0.0, 1.0, 0.0)
    val right = dir.crossProduct(ref).normalize()
    val up = right.crossProduct(dir).normalize()
    return right to up
}

/**
 * Zeichenkontext eines Frames: wandelt Weltkoordinaten in kamerarelative Vertices um und bietet die
 * geometrischen Grundformen, aus denen alle Effekte bestehen.
 */
class VfxDraw(val consumers: VertexConsumerProvider, val camera: Camera, val tickDelta: Float) {

    val cam: Vec3d = camera.cameraPos
    val camRight: Vec3d = camera.diagonalPlane.let { Vec3d(-it.x().toDouble(), -it.y().toDouble(), -it.z().toDouble()) }
    val camUp: Vec3d = camera.verticalPlane.let { Vec3d(it.x().toDouble(), it.y().toDouble(), it.z().toDouble()) }
    val camForward: Vec3d = camera.horizontalPlane.let { Vec3d(it.x().toDouble(), it.y().toDouble(), it.z().toDouble()) }

    fun buffer(layer: RenderLayer): VertexConsumer = consumers.getBuffer(layer)

    fun VertexConsumer.put(p: Vec3d, u: Float, v: Float, color: Int) {
        vertex((p.x - cam.x).toFloat(), (p.y - cam.y).toFloat(), (p.z - cam.z).toFloat())
            .texture(u, v)
            .color(color)
    }

    fun VertexConsumer.put(x: Double, y: Double, z: Double, u: Float, v: Float, color: Int) {
        vertex((x - cam.x).toFloat(), (y - cam.y).toFloat(), (z - cam.z).toFloat())
            .texture(u, v)
            .color(color)
    }

    /** Zur Kamera gedrehtes Sprite. */
    fun billboard(layer: RenderLayer, center: Vec3d, size: Double, color: Int, rotation: Double = 0.0) {
        val c = cos(rotation) * size
        val s = sin(rotation) * size
        val ax = camRight.multiply(c).add(camUp.multiply(s))
        val ay = camRight.multiply(-s).add(camUp.multiply(c))
        val vc = buffer(layer)
        vc.put(center.subtract(ax).subtract(ay), 0f, 1f, color)
        vc.put(center.add(ax).subtract(ay), 1f, 1f, color)
        vc.put(center.add(ax).add(ay), 1f, 0f, color)
        vc.put(center.subtract(ax).add(ay), 0f, 0f, color)
    }

    /** Fest orientiertes Sprite in der Ebene aus [axisA]/[axisB]. */
    fun disc(layer: RenderLayer, center: Vec3d, axisA: Vec3d, axisB: Vec3d, radius: Double, color: Int, rotation: Double = 0.0) {
        val c = cos(rotation) * radius
        val s = sin(rotation) * radius
        val ax = axisA.multiply(c).add(axisB.multiply(s))
        val ay = axisA.multiply(-s).add(axisB.multiply(c))
        val vc = buffer(layer)
        vc.put(center.subtract(ax).subtract(ay), 0f, 1f, color)
        vc.put(center.add(ax).subtract(ay), 1f, 1f, color)
        vc.put(center.add(ax).add(ay), 1f, 0f, color)
        vc.put(center.subtract(ax).add(ay), 0f, 0f, color)
    }

    /** Ringband: innen v=0, außen v=1, u läuft einmal (mal [uTile]) herum. */
    fun ring(
        layer: RenderLayer, center: Vec3d, axisA: Vec3d, axisB: Vec3d,
        inner: Double, outer: Double, colorInner: Int, colorOuter: Int,
        segments: Int = 48, uTile: Float = 1f, uOffset: Float = 0f,
    ) {
        val vc = buffer(layer)
        for (i in 0 until segments) {
            val a0 = i.toDouble() / segments * PI * 2.0
            val a1 = (i + 1.0) / segments * PI * 2.0
            val c0 = cos(a0); val s0 = sin(a0)
            val c1 = cos(a1); val s1 = sin(a1)
            val u0 = uOffset + uTile * i / segments
            val u1 = uOffset + uTile * (i + 1) / segments
            val d0 = axisA.multiply(c0).add(axisB.multiply(s0))
            val d1 = axisA.multiply(c1).add(axisB.multiply(s1))
            vc.put(center.add(d0.multiply(inner)), u0, 0f, colorInner)
            vc.put(center.add(d1.multiply(inner)), u1, 0f, colorInner)
            vc.put(center.add(d1.multiply(outer)), u1, 1f, colorOuter)
            vc.put(center.add(d0.multiply(outer)), u0, 1f, colorOuter)
        }
    }

    /** Stehender Ring (Zylindermantel): unten v=0, oben v=1. */
    fun wall(
        layer: RenderLayer, center: Vec3d, axisA: Vec3d, axisB: Vec3d, up: Vec3d,
        radiusBottom: Double, radiusTop: Double, height: Double,
        colorBottom: Int, colorTop: Int, segments: Int = 40, uTile: Float = 2f, uOffset: Float = 0f,
    ) {
        val vc = buffer(layer)
        val top = center.add(up.multiply(height))
        for (i in 0 until segments) {
            val a0 = i.toDouble() / segments * PI * 2.0
            val a1 = (i + 1.0) / segments * PI * 2.0
            val d0 = axisA.multiply(cos(a0)).add(axisB.multiply(sin(a0)))
            val d1 = axisA.multiply(cos(a1)).add(axisB.multiply(sin(a1)))
            val u0 = uOffset + uTile * i / segments
            val u1 = uOffset + uTile * (i + 1) / segments
            vc.put(center.add(d0.multiply(radiusBottom)), u0, 0f, colorBottom)
            vc.put(center.add(d1.multiply(radiusBottom)), u1, 0f, colorBottom)
            vc.put(top.add(d1.multiply(radiusTop)), u1, 1f, colorTop)
            vc.put(top.add(d0.multiply(radiusTop)), u0, 1f, colorTop)
        }
    }

    /**
     * Röhre entlang eines Pfads. v läuft einmal um den Umfang (0..1..0), damit der Energie-Shader an
     * beiden Rändern jeder Facette ausblendet; u läuft entlang des Pfads in Blöcken × [uScale].
     */
    fun tube(
        layer: RenderLayer, path: List<Vec3d>, radius: (Float) -> Double, color: (Float) -> Int,
        sides: Int = 6, uScale: Float = 0.25f, uOffset: Float = 0f,
    ) {
        if (path.size < 2) return
        val vc = buffer(layer)
        var length = 0.0
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val prev = path[(i - 1).coerceAtLeast(0)]
            val next = path[(i + 2).coerceAtMost(path.size - 1)]
            val (ra, ua) = basisOf(b.subtract(prev))
            val (rb, ub) = basisOf(next.subtract(a))
            val ta = i.toFloat() / (path.size - 1)
            val tb = (i + 1f) / (path.size - 1)
            val r0 = radius(ta)
            val r1 = radius(tb)
            val c0 = color(ta)
            val c1 = color(tb)
            val segLen = a.distanceTo(b)
            val u0 = uOffset + length.toFloat() * uScale
            val u1 = uOffset + (length + segLen).toFloat() * uScale
            length += segLen
            for (s in 0 until sides) {
                val p0 = s.toDouble() / sides * PI * 2.0
                val p1 = (s + 1.0) / sides * PI * 2.0
                val d00 = ra.multiply(cos(p0)).add(ua.multiply(sin(p0))).multiply(r0)
                val d01 = ra.multiply(cos(p1)).add(ua.multiply(sin(p1))).multiply(r0)
                val d10 = rb.multiply(cos(p0)).add(ub.multiply(sin(p0))).multiply(r1)
                val d11 = rb.multiply(cos(p1)).add(ub.multiply(sin(p1))).multiply(r1)
                vc.put(a.add(d00), u0, 0f, c0)
                vc.put(a.add(d01), u0, 1f, c0)
                vc.put(b.add(d11), u1, 1f, c1)
                vc.put(b.add(d10), u1, 0f, c1)
            }
        }
    }

    /** Kamerafolgendes Band entlang eines Pfads — für weiches Glühen und Blitze. */
    fun ribbon(
        layer: RenderLayer, path: List<Vec3d>, width: (Float) -> Double, color: (Float) -> Int,
        uScale: Float = 0.25f, uOffset: Float = 0f,
    ) {
        if (path.size < 2) return
        val vc = buffer(layer)
        var length = 0.0
        var lastLeft: Vec3d? = null
        var lastRight: Vec3d? = null
        var lastU = uOffset
        var lastColor = 0
        for (i in path.indices) {
            val p = path[i]
            val tangent = path[(i + 1).coerceAtMost(path.size - 1)].subtract(path[(i - 1).coerceAtLeast(0)])
            val toCam = cam.subtract(p)
            var side = tangent.crossProduct(toCam)
            if (side.lengthSquared() < 1e-8) side = camRight
            side = side.normalize()
            val t = i.toFloat() / (path.size - 1)
            val w = width(t)
            val left = p.add(side.multiply(w))
            val right = p.subtract(side.multiply(w))
            if (i > 0) length += p.distanceTo(path[i - 1])
            val u = uOffset + length.toFloat() * uScale
            val c = color(t)
            if (lastLeft != null) {
                vc.put(lastLeft, lastU, 0f, lastColor)
                vc.put(lastRight!!, lastU, 1f, lastColor)
                vc.put(right, u, 1f, c)
                vc.put(left, u, 0f, c)
            }
            lastLeft = left
            lastRight = right
            lastU = u
            lastColor = c
        }
    }

    /** Kugel aus Breitengrad-Bändern; [shade] liefert die Farbe je nach Blickwinkel (0 = Rand, 1 = Mitte). */
    fun sphere(
        layer: RenderLayer, center: Vec3d, radius: Double, shade: (Float) -> Int,
        lat: Int = 12, lon: Int = 20, uOffset: Float = 0f,
    ) {
        val vc = buffer(layer)
        fun point(i: Int, j: Int): Vec3d {
            val phi = PI * i / lat
            val th = 2.0 * PI * j / lon
            return Vec3d(sin(phi) * cos(th), cos(phi), sin(phi) * sin(th))
        }
        for (i in 0 until lat) {
            for (j in 0 until lon) {
                val n00 = point(i, j)
                val n01 = point(i, j + 1)
                val n11 = point(i + 1, j + 1)
                val n10 = point(i + 1, j)
                fun c(n: Vec3d): Int {
                    val view = center.add(n.multiply(radius)).subtract(cam).normalize()
                    return shade(abs(n.dotProduct(view)).toFloat())
                }
                val u0 = uOffset + j.toFloat() / lon * 2f
                val u1 = uOffset + (j + 1f) / lon * 2f
                vc.put(center.add(n00.multiply(radius)), u0, i.toFloat() / lat, c(n00))
                vc.put(center.add(n01.multiply(radius)), u1, i.toFloat() / lat, c(n01))
                vc.put(center.add(n11.multiply(radius)), u1, (i + 1f) / lat, c(n11))
                vc.put(center.add(n10.multiply(radius)), u0, (i + 1f) / lat, c(n10))
            }
        }
    }

    /** Quader mit orientierten Achsen, Oberseiten heller als Unterseiten (für Trümmer und Kettenglieder). */
    fun box(
        layer: RenderLayer, center: Vec3d, ax: Vec3d, ay: Vec3d, az: Vec3d, color: Int,
    ) {
        val vc = buffer(layer)
        fun face(a: Vec3d, b: Vec3d, c: Vec3d, shade: Float) {
            val col = lerpColor(0xFF000000.toInt() or (color and 0xFFFFFF), color, 1f).let {
                val r = (((it ushr 16) and 0xFF) * shade).toInt()
                val g = (((it ushr 8) and 0xFF) * shade).toInt()
                val bl = ((it and 0xFF) * shade).toInt()
                (color and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or bl
            }
            val origin = center.add(a)
            vc.put(origin.subtract(b).subtract(c), 0f, 0f, col)
            vc.put(origin.add(b).subtract(c), 1f, 0f, col)
            vc.put(origin.add(b).add(c), 1f, 1f, col)
            vc.put(origin.subtract(b).add(c), 0f, 1f, col)
        }
        val nx = ax.negate(); val ny = ay.negate(); val nz = az.negate()
        face(ay, ax, az, 1.0f)
        face(ny, ax, az, 0.5f)
        face(ax, ay, az, 0.78f)
        face(nx, ay, az, 0.72f)
        face(az, ax, ay, 0.86f)
        face(nz, ax, ay, 0.66f)
    }
}
