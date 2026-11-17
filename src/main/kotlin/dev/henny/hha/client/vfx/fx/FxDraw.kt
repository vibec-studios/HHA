package dev.henny.hha.client.vfx.fx

import dev.henny.hha.Hha
import dev.henny.hha.client.vfx.VfxLayers
import dev.henny.hha.client.vfx.basisOf
import dev.henny.hha.client.vfx.withAlpha
import net.minecraft.block.BlockState
import net.minecraft.client.MinecraftClient
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.WorldRenderer
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import org.joml.Matrix3f
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Zeichner der neuen Effekte (PLAN 6.4). Alle Lagen in **Weltkoordinaten**, Farben ARGB (nicht vormultipliziert),
 * Modus aus [FxMode]. Funktionen mit `(Float) -> …` bekommen den Anteil 0..1 entlang der Punktliste. Alles landet im
 * Puffer des Rahmens ([FxFrame.consumers]), den der `VfxManager` am Ende der Phase leert.
 *
 * ## Vertexbelegung (Format `POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL`, beide Pipelines)
 *
 * | Kanal | `fx_energy` | `fx_solid` |
 * | --- | --- | --- |
 * | Position | kamerarelativ (Welt − Kamera) | dito |
 * | Color | ARGB; A = Deckkraft des ganzen Beitrags | Grundfarbe; A = Hitze (nur [FxSolidMode.EMBER]) |
 * | UV0 | Fläche: −1..1 über das Viereck (Radius = Länge). Band: (Weg in Blöcken + Verschiebung, Querlage × b). Mesh: Mesh-UV | Mesh-UV |
 * | UV1 (`overlay`, 2 × short) | (Modus, Seed & 0x7FFF) | (FxSolidMode, Seed & 0x7FFF) |
 * | UV2 (`light`, 2 × short) | (x, Fortschritt) je × 32767; x = Innenradius/Außenradius (Fläche), b (Band), 0 (Mesh) | Lichtwert der Welt wie Vanilla |
 * | Normal | Flächennormale (Meshes: echte Normale für Fresnel) | Normale für die Schattierung |
 *
 * **Bänder projektiv:** Querlage s ∈ −1..1, relative Halbbreite b = Breite / größte Breite des Bandes. Der Vertex trägt
 * UV0.y = s·b und UV2.x = b; der Shader rechnet s = UV0.y / UV2.x. Weil beide Größen in jedem Dreieck exakt
 * linear sind, bleibt die Querlage auch in verjüngten (trapezförmigen) Vierecken gerade – ohne den Trick knickt sie an
 * jeder Diagonale und harte Kanten (Riss, Sprung) bekommen Zähne.
 *
 * **Drehung** von Flächen ([flat] `rotation`) dreht das Viereck selbst (stetig in Fließkomma, keine Stufen im Vertex).
 *
 * ## Rückfall (PLAN 5)
 * Ohne gültige Pipelines (`!FxPipelines.ready()`) zeichnet jede Funktion eine vereinfachte Form über `VfxLayers`:
 * Schein → `GLOW_ADD`, Ring/Rune/Stoß → `RING_ADD`, übrige Flächen → `FLOW_ALPHA` als weich auslaufende Scheibe,
 * Band/Röhre → `ENERGY`, Wand → `FLOW_ALPHA`, Energie-Mesh → `FLOW_ADD` mit Fresnel in der Vertexfarbe,
 * festes Mesh → Vanilla `entitySolid`. [block] und [part] laufen immer über Vanilla-Ebenen.
 */
object FxDraw {

    /** Weiches Ausblenden an den Enden jeder Wandspanne (Blöcke), damit Lücken keine harten Schnitte haben. */
    const val WALL_EDGE_FADE = 0.35

    /** Segmente der Rückfall-Ringe und -Scheiben. */
    private const val FALLBACK_SEGMENTS = 40

    /** Skala des Rauschens der Bestands-Energie entlang (wie `VfxDraw.ribbon`). */
    private const val FALLBACK_U = 0.25f

    private const val Q = 32767f
    private val UP = Vec3d(0.0, 1.0, 0.0)
    private var warnedPhase = false

    // ---------------------------------------------------------------------------------------------------------------
    // Vertex-Schreiber
    // ---------------------------------------------------------------------------------------------------------------

    private fun q(x: Float): Int = (x.coerceIn(0f, 1f) * Q).roundToInt()

    private fun energy(
        vc: VertexConsumer, x: Float, y: Float, z: Float, argb: Int, u: Float, v: Float,
        mode: Int, seed: Int, p1: Float, p2: Float, nx: Float, ny: Float, nz: Float,
    ) {
        vc.vertex(x, y, z).color(argb).texture(u, v).overlay(mode, seed and 0x7FFF).light(q(p1), q(p2)).normal(nx, ny, nz)
    }

    private fun legacy(vc: VertexConsumer, x: Float, y: Float, z: Float, u: Float, v: Float, argb: Int) {
        vc.vertex(x, y, z).texture(u, v).color(argb)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Helfer für Effekt-Autoren
    // ---------------------------------------------------------------------------------------------------------------

    /** Zwei zueinander und zu [normal] senkrechte Einheitsvektoren (wie `basisOf` im Bestand). */
    fun basis(normal: Vec3d): Pair<Vec3d, Vec3d> = basisOf(normal)

    /**
     * Lage-Matrix für [mesh]/[block]/[part]: Ursprung des Meshes nach [center], +Y nach [up], um [up] um [spin]
     * (Bogenmaß) gedreht, gleichmäßig mit [scale] (bzw. je Achse mit [scaleXYZ]) skaliert.
     */
    fun place(center: Vec3d, scale: Float = 1f, up: Vec3d = UP, spin: Float = 0f, scaleXYZ: Vector3f? = null): Matrix4f {
        val n = if (up.lengthSquared() < 1e-9) UP else up.normalize()
        val rot = Quaternionf().rotationTo(0f, 1f, 0f, n.x.toFloat(), n.y.toFloat(), n.z.toFloat()).rotateY(spin)
        val m = Matrix4f().translation(center.x.toFloat(), center.y.toFloat(), center.z.toFloat()).rotate(rot)
        return if (scaleXYZ != null) m.scale(scaleXYZ) else m.scale(scale)
    }

    /**
     * Glättet eine Punktliste (Catmull-Rom durch alle Punkte, [perSegment] Zwischenpunkte je Abschnitt), z. B. für
     * Bögen aus wenigen Stützpunkten. Endpunkte bleiben gleich.
     */
    fun smooth(points: List<Vec3d>, perSegment: Int = 4): List<Vec3d> {
        if (points.size < 3 || perSegment < 1) return points
        val out = ArrayList<Vec3d>((points.size - 1) * perSegment + 1)
        for (i in 0 until points.size - 1) {
            val p0 = points[max(i - 1, 0)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[min(i + 2, points.size - 1)]
            for (k in 0 until perSegment) {
                val t = k.toDouble() / perSegment
                val t2 = t * t
                val t3 = t2 * t
                fun c(a: Double, b: Double, cc: Double, d: Double) =
                    0.5 * (2 * b + (-a + cc) * t + (2 * a - 5 * b + 4 * cc - d) * t2 + (-a + 3 * b - 3 * cc + d) * t3)
                out.add(Vec3d(c(p0.x, p1.x, p2.x, p3.x), c(p0.y, p1.y, p2.y, p3.y), c(p0.z, p1.z, p2.z, p3.z)))
            }
        }
        out.add(points.last())
        return out
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Flächen
    // ---------------------------------------------------------------------------------------------------------------

    /** Kamerazugewandter weicher Schein, [size] = Halbmesser. */
    fun glow(f: FxFrame, at: Vec3d, size: Float, argb: Int, seed: Int = 0) {
        if (size <= 0.001f) return
        val c = f.rel(at)
        val r = f.right
        val u = f.up
        if (FxPipelines.ready()) {
            val vc = f.buffer(FxPipelines.ENERGY)
            val n = f.forward
            for ((su, sv) in CORNERS) {
                energy(
                    vc, c.x + (r.x * su + u.x * sv) * size, c.y + (r.y * su + u.y * sv) * size, c.z + (r.z * su + u.z * sv) * size,
                    argb, su, sv, FxMode.GLOW, seed, 0f, 1f, -n.x, -n.y, -n.z,
                )
            }
        } else {
            val vc = f.buffer(VfxLayers.GLOW_ADD)
            for ((su, sv) in CORNERS) {
                legacy(
                    vc, c.x + (r.x * su + u.x * sv) * size, c.y + (r.y * su + u.y * sv) * size, c.z + (r.z * su + u.z * sv) * size,
                    (su + 1f) * 0.5f, (1f - sv) * 0.5f, argb,
                )
            }
        }
    }

    /**
     * Um [normal] ausgerichtete Fläche mit Außenradius [outer]: Scheibe, Ring ([inner] > 0, Blöcke), Rune, Fleck,
     * Wirbel, Stoß, Staub, Schein – je nach [mode]. [rotation] (Bogenmaß) dreht die Fläche um [normal],
     * [progress] ist der Fortschritt des Modus (siehe [FxMode]).
     */
    fun flat(
        f: FxFrame, center: Vec3d, normal: Vec3d, outer: Float, argb: Int, mode: Int, inner: Float = 0f,
        rotation: Float = 0f, progress: Float = 1f, seed: Int = 0,
    ) {
        if (outer <= 0.001f) return
        val (a0, b0) = basisOf(normal)
        val cr = cos(rotation.toDouble())
        val sr = sin(rotation.toDouble())
        val a = a0.multiply(cr).add(b0.multiply(sr))
        val b = b0.multiply(cr).subtract(a0.multiply(sr))
        val c = f.rel(center)
        if (FxPipelines.ready()) {
            val vc = f.buffer(FxPipelines.ENERGY)
            val n = (if (normal.lengthSquared() < 1e-9) UP else normal.normalize())
            val ratio = (inner / outer).coerceIn(0f, 1f)
            for ((su, sv) in CORNERS) {
                energy(
                    vc,
                    c.x + ((a.x * su + b.x * sv) * outer).toFloat(), c.y + ((a.y * su + b.y * sv) * outer).toFloat(),
                    c.z + ((a.z * su + b.z * sv) * outer).toFloat(),
                    argb, su, sv, mode, seed, ratio, progress, n.x.toFloat(), n.y.toFloat(), n.z.toFloat(),
                )
            }
            return
        }
        // Rückfall
        when (mode) {
            FxMode.GLOW -> {
                val vc = f.buffer(VfxLayers.GLOW_ADD)
                for ((su, sv) in CORNERS) {
                    legacy(
                        vc, c.x + ((a.x * su + b.x * sv) * outer).toFloat(), c.y + ((a.y * su + b.y * sv) * outer).toFloat(),
                        c.z + ((a.z * su + b.z * sv) * outer).toFloat(), (su + 1f) * 0.5f, (1f - sv) * 0.5f, argb,
                    )
                }
            }
            FxMode.RUNE, FxMode.SHOCK -> {
                val from = if (inner > 0f) inner else outer * 0.8f
                val sweep = if (mode == FxMode.RUNE) progress.coerceIn(0f, 1f) else 1f
                fallbackRing(f, VfxLayers.RING_ADD, c, a, b, from, outer, argb, argb, sweep, 3f)
            }
            else -> {
                val reach = if (mode == FxMode.STAIN || mode == FxMode.SWIRL) outer * progress.coerceIn(0.02f, 1f) else outer
                fallbackRing(f, VfxLayers.FLOW_ALPHA, c, a, b, 0f, reach, argb, withAlpha(argb, 0f), 1f, 1f)
            }
        }
    }

    private fun fallbackRing(
        f: FxFrame, layer: RenderLayer, c: Vector3f, a: Vec3d, b: Vec3d, inner: Float, outer: Float,
        colorInner: Int, colorOuter: Int, sweep: Float, uTile: Float,
    ) {
        if (sweep <= 0f) return
        val vc = f.buffer(layer)
        val segments = max(4, (FALLBACK_SEGMENTS * sweep).roundToInt())
        val span = 2.0 * PI * sweep
        for (i in 0 until segments) {
            val t0 = span * i / segments
            val t1 = span * (i + 1) / segments
            val d0x = a.x * cos(t0) + b.x * sin(t0); val d0y = a.y * cos(t0) + b.y * sin(t0); val d0z = a.z * cos(t0) + b.z * sin(t0)
            val d1x = a.x * cos(t1) + b.x * sin(t1); val d1y = a.y * cos(t1) + b.y * sin(t1); val d1z = a.z * cos(t1) + b.z * sin(t1)
            val u0 = uTile * i / segments * sweep
            val u1 = uTile * (i + 1) / segments * sweep
            legacy(vc, c.x + (d0x * inner).toFloat(), c.y + (d0y * inner).toFloat(), c.z + (d0z * inner).toFloat(), u0, 0f, colorInner)
            legacy(vc, c.x + (d1x * inner).toFloat(), c.y + (d1y * inner).toFloat(), c.z + (d1z * inner).toFloat(), u1, 0f, colorInner)
            legacy(vc, c.x + (d1x * outer).toFloat(), c.y + (d1y * outer).toFloat(), c.z + (d1z * outer).toFloat(), u1, 1f, colorOuter)
            legacy(vc, c.x + (d0x * outer).toFloat(), c.y + (d0y * outer).toFloat(), c.z + (d0z * outer).toFloat(), u0, 1f, colorOuter)
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Bänder
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Gemeinsamer Bandzeichner: [sides] liefert je Punkt die Querrichtung (Einheit), [widths] die halbe Breite.
     * Projektive Querlage (Kopf der Datei).
     */
    private fun band(
        f: FxFrame, points: List<Vec3d>, sides: Array<Vec3d>, widths: FloatArray, colors: IntArray, faces: Array<Vec3d>,
        mode: Int, scroll: Float, seed: Int, progress: Float,
    ) {
        val count = points.size
        var wmax = 0f
        for (w in widths) wmax = max(wmax, w)
        if (wmax <= 1e-5f) return
        val ready = FxPipelines.ready()
        val vc = f.buffer(if (ready) FxPipelines.ENERGY else VfxLayers.ENERGY)
        var length = 0.0
        for (i in 0 until count - 1) {
            val pa = points[i]
            val pb = points[i + 1]
            val seg = pa.distanceTo(pb)
            val ua = scroll + length.toFloat()
            val ub = scroll + (length + seg).toFloat()
            length += seg
            val ra = f.rel(pa)
            val rb = f.rel(pb)
            val sa = sides[i]
            val sb = sides[i + 1]
            val wa = widths[i]
            val wb = widths[i + 1]
            val ca = colors[i]
            val cb = colors[i + 1]
            // Ecken: a links, b links, b rechts, a rechts (links = −Seite)
            val ax0 = ra.x - (sa.x * wa).toFloat(); val ay0 = ra.y - (sa.y * wa).toFloat(); val az0 = ra.z - (sa.z * wa).toFloat()
            val ax1 = ra.x + (sa.x * wa).toFloat(); val ay1 = ra.y + (sa.y * wa).toFloat(); val az1 = ra.z + (sa.z * wa).toFloat()
            val bx0 = rb.x - (sb.x * wb).toFloat(); val by0 = rb.y - (sb.y * wb).toFloat(); val bz0 = rb.z - (sb.z * wb).toFloat()
            val bx1 = rb.x + (sb.x * wb).toFloat(); val by1 = rb.y + (sb.y * wb).toFloat(); val bz1 = rb.z + (sb.z * wb).toFloat()
            if (ready) {
                val ba = max(wa / wmax, 1e-3f)
                val bb = max(wb / wmax, 1e-3f)
                val na = faces[i]
                val nb = faces[i + 1]
                val nax = na.x.toFloat(); val nay = na.y.toFloat(); val naz = na.z.toFloat()
                val nbx = nb.x.toFloat(); val nby = nb.y.toFloat(); val nbz = nb.z.toFloat()
                energy(vc, ax0, ay0, az0, ca, ua, -ba, mode, seed, ba, progress, nax, nay, naz)
                energy(vc, bx0, by0, bz0, cb, ub, -bb, mode, seed, bb, progress, nbx, nby, nbz)
                energy(vc, bx1, by1, bz1, cb, ub, bb, mode, seed, bb, progress, nbx, nby, nbz)
                energy(vc, ax1, ay1, az1, ca, ua, ba, mode, seed, ba, progress, nax, nay, naz)
            } else {
                legacy(vc, ax0, ay0, az0, ua * FALLBACK_U, 0f, ca)
                legacy(vc, bx0, by0, bz0, ub * FALLBACK_U, 0f, cb)
                legacy(vc, bx1, by1, bz1, ub * FALLBACK_U, 1f, cb)
                legacy(vc, ax1, ay1, az1, ua * FALLBACK_U, 1f, ca)
            }
        }
    }

    private fun tangent(points: List<Vec3d>, i: Int): Vec3d =
        points[min(i + 1, points.size - 1)].subtract(points[max(i - 1, 0)])

    /**
     * Kamerazugewandtes glattes Band durch [points] (halbe Breite [width]). [scroll] verschiebt das Rauschen entlang
     * (Blöcke), [progress] ist der Fortschritt des Modus. Für weiche Bögen aus wenigen Punkten vorher [smooth].
     */
    fun ribbon(
        f: FxFrame, points: List<Vec3d>, width: (Float) -> Float, color: (Float) -> Int, mode: Int = FxMode.RIBBON,
        scroll: Float = 0f, seed: Int = 0, progress: Float = 1f,
    ) {
        if (points.size < 2) return
        val n = points.size
        val right = Vec3d(f.right.x.toDouble(), f.right.y.toDouble(), f.right.z.toDouble())
        val faces = Array(n) { f.camPos.subtract(points[it]).let { d -> if (d.lengthSquared() < 1e-9) UP else d.normalize() } }
        val sides = Array(n) { i ->
            val s = tangent(points, i).crossProduct(faces[i])
            if (s.lengthSquared() < 1e-10) right else s.normalize()
        }
        band(f, points, sides, FloatArray(n) { width(it / (n - 1f)) }, IntArray(n) { color(it / (n - 1f)) }, faces, mode, scroll, seed, progress)
    }

    /** Liegendes Band mit fester [normal] (Risse, Pfeile, Sprünge am Boden), halbe Breite [width]. */
    fun strip(
        f: FxFrame, points: List<Vec3d>, normal: Vec3d, width: (Float) -> Float, color: (Float) -> Int, mode: Int, seed: Int = 0,
        progress: Float = 1f, scroll: Float = 0f,
    ) {
        if (points.size < 2) return
        val n = points.size
        val nn = if (normal.lengthSquared() < 1e-9) UP else normal.normalize()
        val fallbackSide = basisOf(nn).first
        val sides = Array(n) { i ->
            val s = tangent(points, i).crossProduct(nn)
            if (s.lengthSquared() < 1e-10) fallbackSide else s.normalize()
        }
        band(f, points, sides, FloatArray(n) { width(it / (n - 1f)) }, IntArray(n) { color(it / (n - 1f)) }, Array(n) { nn }, mode, scroll, seed, progress)
    }

    /**
     * Röhre durch [points] mit [sides] Seiten. Die Querlage im Shader kommt aus der Blickrichtung (Mitte der Silhouette
     * = Bandmitte), also leuchtet die Röhre wie ein räumliches Band – ohne Streifen je Facette.
     */
    fun tube(
        f: FxFrame, points: List<Vec3d>, radius: (Float) -> Float, color: (Float) -> Int, mode: Int = FxMode.RIBBON,
        sides: Int = 8, seed: Int = 0, progress: Float = 1f,
    ) {
        if (points.size < 2 || sides < 3) return
        val n = points.size
        val radii = FloatArray(n) { radius(it / (n - 1f)) }
        val colors = IntArray(n) { color(it / (n - 1f)) }
        var rmax = 0f
        for (r in radii) rmax = max(rmax, r)
        if (rmax <= 1e-5f) return
        val ready = FxPipelines.ready()
        val vc = f.buffer(if (ready) FxPipelines.ENERGY else VfxLayers.ENERGY)
        val ringA = arrayOfNulls<Vec3d>(n)
        val ringB = arrayOfNulls<Vec3d>(n)
        val view = arrayOfNulls<Vec3d>(n)
        val along = FloatArray(n)
        var length = 0.0
        for (i in 0 until n) {
            val t = tangent(points, i)
            val (a, b) = basisOf(t)
            ringA[i] = a
            ringB[i] = b
            val toCam = f.camPos.subtract(points[i])
            val s = t.crossProduct(toCam)
            view[i] = if (s.lengthSquared() < 1e-10) a else s.normalize()
            if (i > 0) length += points[i].distanceTo(points[i - 1])
            along[i] = length.toFloat()
        }
        for (i in 0 until n - 1) {
            val ra = f.rel(points[i])
            val rb = f.rel(points[i + 1])
            val ba = max(radii[i] / rmax, 1e-3f)
            val bb = max(radii[i + 1] / rmax, 1e-3f)
            for (k in 0 until sides) {
                val p0 = 2.0 * PI * k / sides
                val p1 = 2.0 * PI * (k + 1) / sides
                fun corner(j: Int, ph: Double, rel: Vector3f, b: Float, vLegacy: Float) {
                    val d = ringA[j]!!.multiply(cos(ph)).add(ringB[j]!!.multiply(sin(ph)))
                    val r = radii[j]
                    val x = rel.x + (d.x * r).toFloat()
                    val y = rel.y + (d.y * r).toFloat()
                    val z = rel.z + (d.z * r).toFloat()
                    if (ready) {
                        val s = d.dotProduct(view[j]!!).toFloat()
                        energy(vc, x, y, z, colors[j], along[j], s * b, mode, seed, b, progress, d.x.toFloat(), d.y.toFloat(), d.z.toFloat())
                    } else {
                        legacy(vc, x, y, z, along[j] * FALLBACK_U, vLegacy, colors[j])
                    }
                }
                corner(i, p0, ra, ba, 0f)
                corner(i, p1, ra, ba, 1f)
                corner(i + 1, p1, rb, bb, 1f)
                corner(i + 1, p0, rb, bb, 0f)
            }
        }
    }

    /**
     * Senkrechte Wand von [from] nach [to], [height] hoch (nach +Y). [gaps] sind Lücken als Anteile 0..1 entlang der
     * Wand; jede Spanne blendet an ihren Enden über [WALL_EDGE_FADE] weich aus. Im Shader: u = Weg in Blöcken,
     * Querlage −1 unten bis +1 oben (für [FxMode.WATER]/[FxMode.FLAME]: v = 0 Fuß, 1 Krone).
     */
    fun wall(
        f: FxFrame, from: Vec3d, to: Vec3d, height: Float, argb: Int, mode: Int, gaps: List<Pair<Float, Float>> = emptyList(),
        seed: Int = 0, progress: Float = 1f,
    ) {
        val total = from.distanceTo(to)
        if (total < 1e-4 || height <= 0f) return
        val spans = spans(gaps)
        val ready = FxPipelines.ready()
        val vc = f.buffer(if (ready) FxPipelines.ENERGY else VfxLayers.FLOW_ALPHA)
        val dir = to.subtract(from).multiply(1.0 / total)
        val nrm = dir.crossProduct(UP).let { if (it.lengthSquared() < 1e-9) Vec3d(1.0, 0.0, 0.0) else it.normalize() }
        val nx = nrm.x.toFloat(); val ny = nrm.y.toFloat(); val nz = nrm.z.toFloat()
        fun column(k: Double, alpha: Float, out: ArrayList<FloatArray>) {
            val p = f.rel(from.add(to.subtract(from).multiply(k)))
            out.add(floatArrayOf(p.x, p.y, p.z, (k * total).toFloat(), alpha))
        }
        for ((s, e) in spans) {
            val len = (e - s) * total
            if (len < 1e-3) continue
            val fade = min(WALL_EDGE_FADE, len / 3.0) / total
            val cols = ArrayList<FloatArray>(4)
            column(s, 0f, cols)
            column(s + fade, 1f, cols)
            column(e - fade, 1f, cols)
            column(e, 0f, cols)
            for (i in 0 until 3) {
                val c0 = cols[i]
                val c1 = cols[i + 1]
                val col0 = withAlpha(argb, c0[4])
                val col1 = withAlpha(argb, c1[4])
                if (ready) {
                    energy(vc, c0[0], c0[1], c0[2], col0, c0[3], -1f, mode, seed, 1f, progress, nx, ny, nz)
                    energy(vc, c1[0], c1[1], c1[2], col1, c1[3], -1f, mode, seed, 1f, progress, nx, ny, nz)
                    energy(vc, c1[0], c1[1] + height, c1[2], col1, c1[3], 1f, mode, seed, 1f, progress, nx, ny, nz)
                    energy(vc, c0[0], c0[1] + height, c0[2], col0, c0[3], 1f, mode, seed, 1f, progress, nx, ny, nz)
                } else {
                    val topAlpha = 0.25f
                    legacy(vc, c0[0], c0[1], c0[2], c0[3] * 0.3f, 0f, col0)
                    legacy(vc, c1[0], c1[1], c1[2], c1[3] * 0.3f, 0f, col1)
                    legacy(vc, c1[0], c1[1] + height, c1[2], c1[3] * 0.3f, height * 0.3f, withAlpha(col1, topAlpha))
                    legacy(vc, c0[0], c0[1] + height, c0[2], c0[3] * 0.3f, height * 0.3f, withAlpha(col0, topAlpha))
                }
            }
        }
    }

    /** Spannen zwischen den Lücken (Anteile, sortiert, zusammengelegt). */
    private fun spans(gaps: List<Pair<Float, Float>>): List<Pair<Double, Double>> {
        val cuts = gaps.map { (a, b) -> min(a, b).coerceIn(0f, 1f).toDouble() to max(a, b).coerceIn(0f, 1f).toDouble() }
            .filter { it.second > it.first }
            .sortedBy { it.first }
        val out = ArrayList<Pair<Double, Double>>()
        var start = 0.0
        for ((a, b) in cuts) {
            if (a > start) out.add(start to a)
            start = max(start, b)
        }
        if (start < 1.0) out.add(start to 1.0)
        return out
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Meshes, Blöcke, Modellteile
    // ---------------------------------------------------------------------------------------------------------------

    /** [transform] (Mesh → Welt) kamerarelativ machen: nur die Verschiebung, in Double gerechnet. */
    private fun relative(f: FxFrame, transform: Matrix4f): Matrix4f = Matrix4f(transform).apply {
        m30((transform.m30() - f.camPos.x).toFloat())
        m31((transform.m31() - f.camPos.y).toFloat())
        m32((transform.m32() - f.camPos.z).toFloat())
    }

    /**
     * Ein [FxMesh] mit [transform] (Mesh-Raum → Welt, z. B. aus [place]). [solid] = schattiert mit Tiefe über
     * `FxPipelines.SOLID`, dann ist [mode] ein [FxSolidMode] und das Alpha der Farbe die Hitze; sonst Energie mit
     * [mode] aus [FxMode] und [progress]. [light] = Lichtwert für feste Meshes (−1 = aus der Welt am Mesh-Ursprung).
     */
    fun mesh(
        f: FxFrame, mesh: FxMesh, transform: Matrix4f, argb: Int, mode: Int, solid: Boolean = false, seed: Int = 0,
        progress: Float = 1f, light: Int = -1,
    ) {
        val count = mesh.vertices - mesh.vertices % 4
        if (count <= 0) return
        val m = relative(f, transform)
        val nm = m.normal(Matrix3f())
        val p = Vector3f()
        val n = Vector3f()
        val pos = mesh.pos
        val nrm = mesh.nrm
        val uv = mesh.uv
        val col = mesh.col
        val ready = FxPipelines.ready()
        if (solid) {
            val lit = if (light >= 0) light else worldLight(transform)
            val vc = f.buffer(if (ready) FxPipelines.SOLID else RenderLayers.entitySolid(VfxLayers.SOLID))
            for (i in 0 until count) {
                m.transformPosition(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2], p)
                nm.transform(nrm[i * 3], nrm[i * 3 + 1], nrm[i * 3 + 2], n).normalize()
                val c = col?.get(i) ?: argb
                if (ready) {
                    vc.vertex(p.x, p.y, p.z).color(c).texture(uv[i * 2], uv[i * 2 + 1]).overlay(mode, seed and 0x7FFF).light(lit).normal(n.x, n.y, n.z)
                } else {
                    vc.vertex(p.x, p.y, p.z).color(c or (0xFF shl 24)).texture(uv[i * 2], uv[i * 2 + 1])
                        .overlay(OverlayTexture.DEFAULT_UV).light(lit).normal(n.x, n.y, n.z)
                }
            }
            return
        }
        val band = FxMode.isBand(mode)
        val vc = f.buffer(if (ready) FxPipelines.ENERGY else VfxLayers.FLOW_ADD)
        for (i in 0 until count) {
            m.transformPosition(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2], p)
            nm.transform(nrm[i * 3], nrm[i * 3 + 1], nrm[i * 3 + 2], n).normalize()
            val c = col?.get(i) ?: argb
            val u = uv[i * 2]
            val v = uv[i * 2 + 1]
            if (ready) {
                if (band) energy(vc, p.x, p.y, p.z, c, u, v * 2f - 1f, mode, seed, 1f, progress, n.x, n.y, n.z)
                else energy(vc, p.x, p.y, p.z, c, u, v, mode, seed, 0f, progress, n.x, n.y, n.z)
            } else {
                // Fresnel auf der CPU: Rand hell, Mitte fast durchsichtig.
                val len = p.length().coerceAtLeast(1e-4f)
                val facing = abs((n.x * p.x + n.y * p.y + n.z * p.z) / len)
                val rim = (1f - facing).let { it * it }
                legacy(vc, p.x, p.y, p.z, u * 2f, v, withAlpha(c, 0.12f + 0.88f * rim))
            }
        }
    }

    private fun worldLight(transform: Matrix4f): Int {
        val world = MinecraftClient.getInstance().world ?: return FULL_BRIGHT
        return WorldRenderer.getLightmapCoordinates(world, BlockPos.ofFloored(transform.m30().toDouble(), transform.m31().toDouble(), transform.m32().toDouble()))
    }

    /** Lichtwert „volle Helligkeit“ (Block 15, Himmel 15). */
    const val FULL_BRIGHT = 0xF000F0

    private fun matrices(f: FxFrame, transform: Matrix4f): MatrixStack = MatrixStack().apply { multiplyPositionMatrix(relative(f, transform)) }

    private fun solidPhase(f: FxFrame, what: String): Boolean {
        if (f.phase == FxFrame.Phase.SOLID) return true
        if (!warnedPhase) {
            warnedPhase = true
            Hha.LOGGER.warn("FxDraw.{} außerhalb von FxEffect.solid() aufgerufen ({}); wird nicht gezeichnet", what, f.phase)
        }
        return false
    }

    /**
     * Echtes Blockmodell (Vanilla-Ebenen, wie der Block in der Welt aussieht), nur in `solid()`. [transform] bildet den
     * Einheitswürfel 0..1 in die Welt ab (für einen um seine Mitte gedrehten Block erst um −0,5 verschieben);
     * [light] z. B. aus `WorldRenderer.getLightmapCoordinates(world, pos)`.
     */
    fun block(f: FxFrame, state: BlockState, transform: Matrix4f, light: Int) {
        if (!solidPhase(f, "block")) return
        MinecraftClient.getInstance().blockRenderManager.renderBlockAsEntity(state, matrices(f, transform), f.consumers, light, OverlayTexture.DEFAULT_UV)
    }

    /**
     * Teil eines Bossmodells mit dessen Textur (Vanilla-Ebene `entityCutoutNoCull`), nur in `solid()`. [transform]
     * bildet den Modellraum (Blöcke, wie im Renderer nach `scale(-1, -1, 1)` und Verschiebung um 1,501) in die Welt
     * ab; die eigene Lage und Drehung des Teils wendet `ModelPart.render` selbst an. [argb] färbt (−1 = unverändert).
     */
    fun part(f: FxFrame, part: ModelPart, texture: Identifier, transform: Matrix4f, light: Int, argb: Int = -1) {
        if (!solidPhase(f, "part")) return
        part.render(matrices(f, transform), f.buffer(RenderLayers.entityCutoutNoCull(texture)), light, OverlayTexture.DEFAULT_UV, argb)
    }

    /** Ecken eines Vierecks in Flächenkoordinaten −1..1 (gegen den Uhrzeigersinn). */
    private val CORNERS = arrayOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)
}
