package dev.henny.hha.client.vfx.fx.lib

import dev.henny.hha.client.HhaClientConfig
import dev.henny.hha.client.vfx.lerpColor
import dev.henny.hha.client.vfx.withAlpha
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Quaternionf
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Gemeinsame kleine Helfer der Bibliothek `core.*` (P03): Richtungen, Kurven, Bodenhöhe, Ortston. Keine Zustände.
 * Alle Lagen in Weltkoordinaten (wie `FxDraw`).
 */
internal object FxLib {

    val UP: Vec3d = Vec3d(0.0, 1.0, 0.0)

    /** So hoch liegen Bodeneffekte über der Oberfläche (Briefing: 0,02). */
    const val GROUND_LIFT = 0.02

    /** Bodensuche: so weit über bzw. unter der Ausgangshöhe (Blöcke). */
    private const val SEARCH_UP = 2
    private const val SEARCH_DOWN = 4

    /** Einheitsvektor; Nullvektor → [fallback]. */
    fun unit(v: Vec3d, fallback: Vec3d = UP): Vec3d = if (v.lengthSquared() < 1e-9) fallback else v.normalize()

    /** Waagrechter Anteil als Einheitsvektor; senkrecht → [fallback]. */
    fun flat(v: Vec3d, fallback: Vec3d = Vec3d(1.0, 0.0, 0.0)): Vec3d = unit(Vec3d(v.x, 0.0, v.z), fallback)

    /** Zwei Einheitsvektoren quer zu [n] (rechtshändig). */
    fun basis(n: Vec3d): Pair<Vec3d, Vec3d> {
        val d = unit(n)
        val ref = if (abs(d.y) > 0.95) Vec3d(1.0, 0.0, 0.0) else UP
        val u = d.crossProduct(ref).normalize()
        return u to u.crossProduct(d).normalize()
    }

    /** Querachse eines Risses entlang [along] (waagrecht, wenn möglich). */
    fun across(along: Vec3d): Vec3d = basis(along).first

    /** Quadratische Bézierkurve a → c mit Kontrollpunkt b. */
    fun bezier(a: Vec3d, b: Vec3d, c: Vec3d, t: Float): Vec3d {
        val k = 1.0 - t
        val tt = t.toDouble()
        return a.multiply(k * k).add(b.multiply(2.0 * k * tt)).add(c.multiply(tt * tt))
    }

    fun lerp(a: Vec3d, b: Vec3d, t: Float): Vec3d = a.lerp(b, t.toDouble())

    /** Mitte eines Wesens zwischen zwei Ticks ([up] = Anteil der Höhe). */
    fun body(e: Entity, tickDelta: Float, up: Double = 0.55): Vec3d = e.getLerpedPos(tickDelta).add(0.0, e.height * up, 0.0)

    fun alpha(argb: Int, a: Float): Int = withAlpha(argb, a)

    fun mix(a: Int, b: Int, t: Float): Int = lerpColor(a, b, t)

    /** Weltlage → Matrix: verschoben nach [at], +Y auf [normal] gedreht. */
    fun aligned(at: Vec3d, normal: Vec3d): Matrix4f {
        val n = unit(normal)
        return Matrix4f().translation(at.x.toFloat(), at.y.toFloat(), at.z.toFloat())
            .rotate(Quaternionf().rotationTo(0f, 1f, 0f, n.x.toFloat(), n.y.toFloat(), n.z.toFloat()))
    }

    /**
     * Oberkante des festen Bodens unter (bzw. knapp über) [x], [y], [z]: sucht von [SEARCH_UP] Blöcken darüber bis
     * [SEARCH_DOWN] darunter den ersten Block mit Kollisionsform und freiem Raum darüber. Nichts gefunden → [y].
     */
    fun groundY(world: ClientWorld, x: Double, y: Double, z: Double): Double {
        val base = BlockPos.ofFloored(x, y, z)
        for (dy in SEARCH_UP downTo -SEARCH_DOWN) {
            val pos = base.up(dy)
            val shape = world.getBlockState(pos).getCollisionShape(world, pos)
            if (shape.isEmpty) continue
            val above = pos.up()
            if (!world.getBlockState(above).getCollisionShape(world, above).isEmpty) continue
            return pos.y + shape.getMax(Direction.Axis.Y)
        }
        return y
    }

    /** Leiser Ortston am Client (höchstens einer je Effekt, Briefing). */
    fun sound(world: ClientWorld, at: Vec3d, event: SoundEvent, volume: Float = 0.6f, pitch: Float = 1f) {
        world.playSoundClient(at.x, at.y, at.z, event, SoundCategory.HOSTILE, volume, pitch, false)
    }

    /** Gilt der Client-Schalter für 3D-Effekte? (für Helfer, die selbst in den `VfxManager` legen). */
    fun enabled(): Boolean = HhaClientConfig.enabled("vfx_3d")

    /** Bogenlängen-Anteile 0..1 einer Punktliste (erster 0, letzter 1). */
    fun arcFractions(points: List<Vec3d>): FloatArray {
        val out = FloatArray(points.size)
        var total = 0.0
        for (i in 1 until points.size) {
            total += points[i].distanceTo(points[i - 1])
            out[i] = total.toFloat()
        }
        if (total > 1e-6) for (i in out.indices) out[i] = (out[i] / total).toFloat()
        return out
    }

    /**
     * Die Punktliste bis zum Anteil [k] (0..1, nach Bogenlänge), mit genau interpoliertem Endpunkt: wächst stetig
     * statt Punkt für Punkt.
     */
    fun partial(points: List<Vec3d>, fractions: FloatArray, k: Float): List<Vec3d> {
        if (k >= 1f) return points
        val out = ArrayList<Vec3d>(points.size)
        out += points[0]
        for (i in 1 until points.size) {
            if (fractions[i] < k) {
                out += points[i]
                continue
            }
            val span = (fractions[i] - fractions[i - 1]).coerceAtLeast(1e-6f)
            out += lerp(points[i - 1], points[i], (k - fractions[i - 1]) / span)
            break
        }
        return out
    }

    /** Zufällige Einheitsrichtung aus zwei gleichverteilten Zahlen 0..1. */
    fun sphere(u: Float, v: Float): Vec3d {
        val y = 2.0 * v - 1.0
        val r = sqrt((1.0 - y * y).coerceAtLeast(0.0))
        val a = u * Math.PI * 2.0
        return Vec3d(kotlin.math.cos(a) * r, y, kotlin.math.sin(a) * r)
    }
}
