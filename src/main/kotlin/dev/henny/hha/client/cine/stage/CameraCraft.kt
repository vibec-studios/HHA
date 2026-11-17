package dev.henny.hha.client.cine.stage

import dev.henny.hha.client.HhaClientConfig
import dev.henny.hha.client.anim.Ease
import dev.henny.hha.stage.EaseKind
import net.minecraft.client.MinecraftClient
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kamera-Handwerk der Bühne (PLAN 6.5, REF T10/T11), **gleich für Bühnen-Skripte und die alten Einstellungen**
 * (`Cine.OP_CAMERA`/`OP_ORBIT` in `CinePlayer`):
 * - [Follow]: Folge-Blick mit Zeitkonstante (Blood `follow`), beim Schnitt genau auf dem Ziel.
 * - [handheld]: ganz leichtes, langsames Gieren/Nicken (±0,3°) mal Stärke mal `distortionEffectScale`, Schalter
 *   `cine_handheld`.
 * - [Clip]: Kollisionsschutz per Raycast vom Blickpunkt zur Kamera (Void `clip`): die Kamera rückt [CLIP_MARGIN] vor
 *   das Hindernis, die Korrektur selbst gleitet über [CLIP_SMOOTH_TICKS] Ticks (kein Springen).
 * - Kurven: [ease], [arcAround] (Bogen um einen Punkt, Void `arcBetween`), [spline] (Catmull-Rom mit
 *   Bogenlängen-Parametrisierung).
 *
 * Alles hier ist zustandslos bis auf die beiden kleinen Halter [Follow] und [Clip]; je Kamera ein eigener Halter.
 */
object CameraCraft {

    /** Standard-Zeitkonstante des Folge-Blicks in Sekunden. */
    const val FOLLOW_DEFAULT = 0.1f

    /** Höchster Ausschlag der Handkamera in Grad (Summe der beiden Sinus je Achse). */
    const val HANDHELD_MAX_DEG = 0.3f

    /** So weit rückt die Kamera vor ein Hindernis zwischen Blickpunkt und Kamera (Blöcke). */
    const val CLIP_MARGIN = 0.4

    /** Über so viele Ticks gleitet die Korrektur des Kollisionsschutzes (≈ 98 % nach dieser Zeit). */
    const val CLIP_SMOOTH_TICKS = 4f

    /** Treffer so nah am Blickpunkt zählen nicht (der Blickpunkt streift oft den Boden oder eine Wand am Darsteller). */
    const val CLIP_IGNORE_NEAR = 0.6

    /** Stützpunkte je Kurvenabschnitt für die Bogenlänge. */
    private const val SPLINE_SAMPLES = 16

    /** Kurve einer Einstellung; [OUT_BACK] darf leicht über 1 schießen. */
    fun ease(kind: EaseKind, t: Float): Float = when (kind) {
        EaseKind.LINEAR -> Ease.linear(t)
        EaseKind.SMOOTH -> Ease.inOut(t)
        EaseKind.IN -> Ease.inCubic(t)
        EaseKind.OUT -> Ease.outCubic(t)
        EaseKind.OUT_BACK -> Ease.outBack(t)
    }

    // ---------------------------------------------------------------------------------------------
    // Blickwinkel
    // ---------------------------------------------------------------------------------------------

    /** Yaw (Grad, wie `Entity.yaw`) von [from] nach [to]. */
    fun yawOf(from: Vec3d, to: Vec3d): Float {
        val d = to.subtract(from)
        return (Math.toDegrees(atan2(d.z, d.x)) - 90.0).toFloat()
    }

    /** Pitch (Grad, positiv = nach unten) von [from] nach [to]. */
    fun pitchOf(from: Vec3d, to: Vec3d): Float {
        val d = to.subtract(from)
        return (-Math.toDegrees(atan2(d.y, sqrt(d.x * d.x + d.z * d.z)))).toFloat()
    }

    /**
     * Handkamera: Versatz (Gieren, Nicken) in Grad zur Zeit [seconds], mal [amount] (Stärke der Einstellung). Zwei
     * langsame Sinus je Achse (Blood), Summe höchstens [HANDHELD_MAX_DEG]. Aus, wenn `cine_handheld` aus ist.
     */
    fun handheld(seconds: Double, amount: Float): Pair<Float, Float> {
        if (amount <= 0f || !HhaClientConfig.enabled("cine_handheld")) return 0f to 0f
        val scale = MinecraftClient.getInstance().options.distortionEffectScale.value.toFloat() * amount
        if (scale <= 0f) return 0f to 0f
        // 0,22 + 0,08 = 0,30° Gieren, 0,20 + 0,10 = 0,30° Nicken.
        val yaw = (sin(seconds * 0.53) * 0.22 + sin(seconds * 1.31 + 1.0) * 0.08).toFloat()
        val pitch = (sin(seconds * 0.41 + 2.0) * 0.20 + sin(seconds * 1.07) * 0.10).toFloat()
        return yaw * scale to pitch * scale
    }

    /**
     * Folge-Blick: glättet ein bewegtes Blickziel mit der Zeitkonstante `tau` (Sekunden). [reset] (neue Einstellung mit
     * Schnitt, erster Frame) setzt genau auf das Ziel. `tau <= 0` = kein Nachziehen.
     */
    class Follow {
        private var value: Vec3d? = null
        private var nanos = 0L

        fun reset() {
            value = null
        }

        fun apply(target: Vec3d, tau: Float): Vec3d {
            val now = System.nanoTime()
            val current = value
            if (current == null || tau <= 0f) {
                value = target
                nanos = now
                return target
            }
            val dt = ((now - nanos) / 1.0e9).coerceIn(0.0, 0.1)
            nanos = now
            val k = 1.0 - exp(-dt / tau)
            val next = current.add(target.subtract(current).multiply(k))
            value = next
            return next
        }
    }

    /**
     * Kollisionsschutz: Raycast vom Blickpunkt zur Kamera. Liegt ein Block dazwischen, gehört die Kamera [CLIP_MARGIN]
     * vor das Hindernis. Geglättet wird der *Abstand der Korrektur* (wie weit die Kamera zum Blickpunkt hin rückt), damit
     * eine Säule, die ins Bild läuft, die Kamera nicht springen lässt. [reset] (Schnitt) setzt sofort.
     */
    class Clip {
        private var pull = 0.0
        private var nanos = 0L
        private var fresh = true

        fun reset() {
            fresh = true
        }

        fun apply(look: Vec3d, cam: Vec3d): Vec3d {
            val target = targetPull(look, cam)
            val now = System.nanoTime()
            if (fresh) {
                pull = target
                fresh = false
            } else {
                val dtTicks = ((now - nanos) / 1.0e9 * 20.0).coerceIn(0.0, 2.0)
                // Zeitkonstante = CLIP_SMOOTH_TICKS / 4: nach CLIP_SMOOTH_TICKS Ticks sind 98 % erreicht.
                val k = 1.0 - exp(-dtTicks / (CLIP_SMOOTH_TICKS / 4.0))
                pull += (target - pull) * k
            }
            nanos = now
            if (pull <= 1.0e-4) return cam
            val back = look.subtract(cam)
            val length = back.length()
            if (length < 1.0e-4) return cam
            return cam.add(back.multiply(pull.coerceAtMost(length) / length))
        }

        /** Wie weit die Kamera zum Blickpunkt rücken müsste (0 = frei). */
        private fun targetPull(look: Vec3d, cam: Vec3d): Double {
            val client = MinecraftClient.getInstance()
            val world = client.world ?: return 0.0
            val entity = client.player ?: return 0.0
            val distance = look.distanceTo(cam)
            if (distance < CLIP_IGNORE_NEAR * 2) return 0.0
            // Steckt der Blickpunkt selbst in einem Block (Blick auf den Boden), lässt sich nichts sinnvoll prüfen.
            val inside = world.getBlockState(BlockPos.ofFloored(look))
            if (!inside.getCollisionShape(world, BlockPos.ofFloored(look)).isEmpty) return 0.0
            val dir = cam.subtract(look).normalize()
            val start = look.add(dir.multiply(CLIP_IGNORE_NEAR))
            val hit = world.raycast(RaycastContext(start, cam, RaycastContext.ShapeType.VISUAL, RaycastContext.FluidHandling.NONE, entity))
            if (hit.type == HitResult.Type.MISS) return 0.0
            val free = look.distanceTo(hit.pos) - CLIP_MARGIN
            return (distance - free.coerceAtLeast(CLIP_IGNORE_NEAR)).coerceAtLeast(0.0)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Kurven
    // ---------------------------------------------------------------------------------------------

    /**
     * Bogen von [a] nach [b] um [center] (Void `arcBetween`): Waagerecht werden Winkel und Radius überblendet (kürzerer
     * Weg), die Höhe linear. Bei k = 0 genau [a], bei k = 1 genau [b].
     */
    fun arcAround(a: Vec3d, b: Vec3d, center: Vec3d, k: Double): Vec3d {
        val ax = a.x - center.x
        val az = a.z - center.z
        val bx = b.x - center.x
        val bz = b.z - center.z
        val ra = sqrt(ax * ax + az * az)
        val rb = sqrt(bx * bx + bz * bz)
        if (ra < 1.0e-3 || rb < 1.0e-3) return a.lerp(b, k)
        val from = atan2(az, ax)
        var delta = atan2(bz, bx) - from
        while (delta > Math.PI) delta -= Math.PI * 2.0
        while (delta < -Math.PI) delta += Math.PI * 2.0
        val radius = ra + (rb - ra) * k
        val angle = from + delta * k
        return Vec3d(center.x + cos(angle) * radius, a.y + (b.y - a.y) * k, center.z + sin(angle) * radius)
    }

    private fun catmull(p0: Vec3d, p1: Vec3d, p2: Vec3d, p3: Vec3d, u: Double): Vec3d {
        val u2 = u * u
        val u3 = u2 * u
        fun c(a: Double, b: Double, c: Double, d: Double) =
            0.5 * (2 * b + (-a + c) * u + (2 * a - 5 * b + 4 * c - d) * u2 + (-a + 3 * b - 3 * c + d) * u3)
        return Vec3d(c(p0.x, p1.x, p2.x, p3.x), c(p0.y, p1.y, p2.y, p3.y), c(p0.z, p1.z, p2.z, p3.z))
    }

    /** Stützpunkte einer Catmull-Rom-Kurve durch [points] (Enden verdoppelt) samt aufsummierter Weglänge. */
    class Samples(val points: List<Vec3d>, val acc: DoubleArray) {
        val total: Double get() = acc.last()

        /** Punkt bei Weglänge-Anteil [s] (0..1). */
        fun at(s: Double): Vec3d {
            if (points.size == 1 || total < 1.0e-6) return points.first()
            val target = total * s.coerceIn(0.0, 1.0)
            var lo = 0
            var hi = acc.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (acc[mid] < target) lo = mid else hi = mid
            }
            val len = (acc[hi] - acc[lo]).coerceAtLeast(1.0e-9)
            return points[lo].lerp(points[hi], ((target - acc[lo]) / len).coerceIn(0.0, 1.0))
        }

        /** Index des Stützpunkts bei Weglänge-Anteil [s] (für den Blick voraus). */
        fun indexAt(s: Double): Int {
            val target = total * s.coerceIn(0.0, 1.0)
            var i = 0
            while (i < acc.size - 2 && acc[i + 1] < target) i++
            return i
        }
    }

    fun samples(points: List<Vec3d>): Samples {
        if (points.size < 2) return Samples(points, DoubleArray(points.size.coerceAtLeast(1)))
        val out = ArrayList<Vec3d>((points.size - 1) * SPLINE_SAMPLES + 1)
        for (seg in 0 until points.size - 1) {
            val p0 = points[maxOf(seg - 1, 0)]
            val p3 = points[minOf(seg + 2, points.size - 1)]
            for (k in 0 until SPLINE_SAMPLES) out += catmull(p0, points[seg], points[seg + 1], p3, k / SPLINE_SAMPLES.toDouble())
        }
        out += points.last()
        val acc = DoubleArray(out.size)
        for (i in 1 until out.size) acc[i] = acc[i - 1] + out[i].distanceTo(out[i - 1])
        return Samples(out, acc)
    }

    /** Catmull-Rom durch [points], [k] = Anteil der Weglänge (gleichmäßiges Tempo, Kurve aus der Einstellung). */
    fun spline(points: List<Vec3d>, k: Double): Vec3d = samples(points).at(k)

    /**
     * Wegprofil 0..1 (Blood `trapezoid`): beschleunigt über den ersten Anteil [r] der Zeit, hält das Tempo, bremst über
     * den letzten Anteil [r].
     */
    fun trapezoid(x: Double, r: Double = 0.2): Double {
        val t = x.coerceIn(0.0, 1.0)
        val v = 1.0 / (1.0 - r)
        return when {
            t < r -> 0.5 * v * t * t / r
            t > 1 - r -> 1.0 - 0.5 * v * (1 - t) * (1 - t) / r
            else -> 0.5 * v * r + v * (t - r)
        }
    }
}
