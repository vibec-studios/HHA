package dev.henny.hha.client.vfx.fx

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ein glattes, fest vorgebautes Mesh mit Normalen (keine Voxel). Je vier Ecken bilden ein Viereck. [pos], [nrm] je drei
 * Werte, [uv] je zwei Werte pro Ecke; [col] optional ARGB je Ecke (ersetzt dann die Farbe des Aufrufs).
 */
class FxMesh(val pos: FloatArray, val nrm: FloatArray, val uv: FloatArray, val col: IntArray? = null) {
    /** Anzahl der Ecken. */
    val vertices: Int get() = pos.size / 3

    companion object {
        val EMPTY = FxMesh(FloatArray(0), FloatArray(0), FloatArray(0))
    }
}

/**
 * Die Grund-Meshes, einmal beim ersten Zugriff gebaut (Einheitsgröße, Mitte im Ursprung, +Y oben). UV: u läuft einmal
 * rundherum (0..1, an der Naht periodisch – die Shader nutzen periodisches Rauschen), v von unten bzw. vom Fuß nach oben.
 * Normalen glatt (je Ecke aus der Form, nicht je Fläche), also ohne Facetten.
 */
object FxMeshes {

    /** Ringe/Segmente: fein genug für glatte Silhouetten bis etwa 20 Blöcke Radius. */
    private const val LON = 40
    private const val LAT = 20

    /** Kugel, Radius 1; v = 0 am Südpol, 1 am Nordpol. */
    val SPHERE: FxMesh by lazy { revolve(LAT, LON) { k -> val phi = PI * (1.0 - k); Profile(sin(phi), cos(phi), sin(phi), cos(phi)) } }

    /** Halbkugel, Radius 1, Fuß bei y = 0; v = 0 am Fuß, 1 am Scheitel. */
    val DOME: FxMesh by lazy { revolve(LAT / 2, LON) { k -> val phi = PI / 2 * (1.0 - k); Profile(sin(phi), cos(phi), sin(phi), cos(phi)) } }

    /** Torus in der XZ-Ebene: Mittelkreis Radius 1, Rohr Radius [TORUS_TUBE]; v läuft einmal ums Rohr. */
    const val TORUS_TUBE = 0.22f
    val TORUS: FxMesh by lazy { torus(LON, 16, TORUS_TUBE.toDouble()) }

    /** Kegelmantel: Fuß Radius 1 bei y = 0, Spitze bei y = 1 (offen, ohne Boden); v = 0 Fuß, 1 Spitze. */
    val CONE: FxMesh by lazy {
        // Mantelnormale eines Kegels mit Höhe 1, Radius 1: (cos, 1, sin) / √2
        revolve(8, LON) { k -> Profile(1.0 - k, k, 1.0 / sqrt(2.0), 1.0 / sqrt(2.0)) }
    }

    /** Trichter: unten eng (Radius 0,15 bei y = 0), oben weit (Radius 1 bei y = 1), offen; v = y. */
    val FUNNEL: FxMesh by lazy {
        revolve(16, LON) { k ->
            val r = 0.15 + 0.85 * k * k
            val slope = 1.7 * k                       // dr/dy
            val len = sqrt(1.0 + slope * slope)
            Profile(r, k, 1.0 / len, -slope / len)
        }
    }

    /** Himmelskuppel: Halbkugel Radius 1, Normalen nach innen; v = 0 am Horizont, 1 im Zenit. */
    val SKY_DOME: FxMesh by lazy {
        revolve(LAT / 2, LON * 2) { k -> val phi = PI / 2 * (1.0 - k); Profile(sin(phi), cos(phi), -sin(phi), -cos(phi)) }
    }

    /** Profil einer Drehform bei Anteil k (0..1 entlang v): Radius, Höhe und Normale (radial, y). */
    private class Profile(val radius: Double, val y: Double, val nRadial: Double, val nY: Double)

    private class Builder(quads: Int) {
        val pos = FloatArray(quads * 12)
        val nrm = FloatArray(quads * 12)
        val uv = FloatArray(quads * 8)
        var i = 0

        fun put(x: Double, y: Double, z: Double, nx: Double, ny: Double, nz: Double, u: Double, v: Double) {
            pos[i * 3] = x.toFloat(); pos[i * 3 + 1] = y.toFloat(); pos[i * 3 + 2] = z.toFloat()
            val len = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-9 } ?: 1.0
            nrm[i * 3] = (nx / len).toFloat(); nrm[i * 3 + 1] = (ny / len).toFloat(); nrm[i * 3 + 2] = (nz / len).toFloat()
            uv[i * 2] = u.toFloat(); uv[i * 2 + 1] = v.toFloat()
            i++
        }

        fun build() = FxMesh(pos, nrm, uv)
    }

    /** Drehform um +Y aus [rings] × [segments] Vierecken. */
    private fun revolve(rings: Int, segments: Int, profile: (Double) -> Profile): FxMesh {
        val b = Builder(rings * segments)
        val prof = Array(rings + 1) { profile(it.toDouble() / rings) }
        for (r in 0 until rings) {
            for (s in 0 until segments) {
                for ((dr, ds) in CORNERS) {
                    val p = prof[r + dr]
                    val th = 2.0 * PI * (s + ds) / segments
                    val c = cos(th)
                    val sn = sin(th)
                    b.put(c * p.radius, p.y, sn * p.radius, c * p.nRadial, p.nY, sn * p.nRadial, (s + ds).toDouble() / segments, (r + dr).toDouble() / rings)
                }
            }
        }
        return b.build()
    }

    private fun torus(segments: Int, sides: Int, tube: Double): FxMesh {
        val b = Builder(segments * sides)
        for (s in 0 until segments) {
            for (k in 0 until sides) {
                for ((dk, ds) in CORNERS) {
                    val th = 2.0 * PI * (s + ds) / segments
                    val ph = 2.0 * PI * (k + dk) / sides
                    val ct = cos(th); val st = sin(th)
                    val cp = cos(ph); val sp = sin(ph)
                    val r = 1.0 + tube * cp
                    b.put(ct * r, tube * sp, st * r, ct * cp, sp, st * cp, (s + ds).toDouble() / segments, (k + dk).toDouble() / sides)
                }
            }
        }
        return b.build()
    }

    /** Ecken eines Vierecks als (Schritt v, Schritt u). */
    private val CORNERS = arrayOf(0 to 0, 0 to 1, 1 to 1, 1 to 0)
}
