package dev.henny.hha.client.rpg.tunnel

import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import kotlin.math.sin

/**
 * Kamera der Tunnelbühne (PLAN 4.1): bei (0; 0,6; 7,5), Blick −Z leicht nach unten auf die Gruppe, ruhiges Treiben
 * (±[DRIFT]), die Maus verschiebt den Blick um höchstens ±[MOUSE_DEG]°. Szenen-Einheiten = Blöcke (Figur 1,8 hoch).
 * Renderer und Namensschilder rechnen mit denselben Matrizen; `tmp/overhaul3/previews/tunnel/glcheck.py` auch.
 */
object TunnelCamera {

    /** Senkrechtes Sichtfeld (Grad). */
    const val FOV = 58f
    const val NEAR = 0.05f
    const val FAR = 400f

    /** Ruhiges Treiben der Kamera (Blöcke). */
    const val DRIFT = 0.15f

    /** Größter Blickversatz durch die Maus (Grad). */
    const val MOUSE_DEG = 1.5f

    private val EYE = Vector3f(0f, 0.6f, 7.5f)
    private val TARGET = Vector3f(0f, 0.15f, -3f)

    /** Sicht-Matrix: Szene → Kamera. [lookX]/[lookY] in −1..1 (Maus), [time] in Sekunden. */
    fun view(time: Float, lookX: Float, lookY: Float): Matrix4f {
        val eye = Vector3f(
            EYE.x + DRIFT * sin(time * 0.23f),
            EYE.y + DRIFT * 0.6f * sin(time * 0.31f + 1f),
            EYE.z + DRIFT * 0.5f * sin(time * 0.17f + 2f),
        )
        val dir = Vector3f(TARGET).sub(EYE).normalize()
        dir.rotateY(Math.toRadians((-lookX.coerceIn(-1f, 1f) * MOUSE_DEG).toDouble()).toFloat())
        val right = Vector3f(dir).cross(0f, 1f, 0f).normalize()
        dir.rotateAxis(Math.toRadians((-lookY.coerceIn(-1f, 1f) * MOUSE_DEG).toDouble()).toFloat(), right.x, right.y, right.z)
        val center = Vector3f(eye).add(dir)
        return Matrix4f().lookAt(eye, center, Vector3f(0f, 1f, 0f))
    }

    /** Projektion für ein Bild von [width] × [height] Bildpunkten (wie `ProjectionMatrix3.set`). */
    fun projection(width: Int, height: Int): Matrix4f =
        Matrix4f().perspective(Math.toRadians(FOV.toDouble()).toFloat(), width.toFloat() / height.coerceAtLeast(1), NEAR, FAR)

    /**
     * Projiziert den Szenenpunkt (x, y, z) in GUI-Koordinaten des Rechtecks [left]..[left]+[width],
     * [top]..[top]+[height]. null, wenn der Punkt hinter der Kamera liegt.
     */
    fun project(view: Matrix4f, left: Int, top: Int, width: Int, height: Int, x: Float, y: Float, z: Float): FloatArray? {
        val clip = Vector4f(x, y, z, 1f)
        view.transform(clip)
        projection(width, height).transform(clip)
        if (clip.w <= 0.01f) return null
        val nx = clip.x / clip.w
        val ny = clip.y / clip.w
        return floatArrayOf(left + (nx * 0.5f + 0.5f) * width, top + (0.5f - ny * 0.5f) * height)
    }
}
