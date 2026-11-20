package dev.henny.hha.client.model

import dev.henny.hha.Hha
import dev.henny.hha.client.vfx.BossGlow
import dev.henny.hha.entity.boss.CoreEntity
import dev.henny.hha.entity.boss.SubTargetEntity
import dev.henny.hha.logic.boss.SubTarget
import net.minecraft.client.MinecraftClient
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.EntityRenderer
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.state.EntityRenderState
import net.minecraft.client.render.state.CameraRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.RotationAxis
import net.minecraft.util.math.Vec3d
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

class SubTargetRenderState : EntityRenderState() {
    var kind = ""
    var size = 1f

    /** 0 ruht (gedämpft), 1 offen (Gold), 2 gebrochen (`SubTarget.STATE_*`). */
    var subState = 0

    /** Blickrichtung der Kamera (für den Lichthof), vom Renderer gesetzt. */
    var camera: Quaternionf = Quaternionf()
}

/**
 * Aussehen der Teilziele (`hha:sub_target`, Überarbeitung 4, PLAN 7.6; Besitzer P04). Kampf-Pakete melden je `kind`
 * eine Zeichnung an ([register]); ohne Anmeldung ist es ein goldener Lichtkörper wie die Sanduhr (`CoreRenderer`).
 *
 * Fertig dabei (glatt schattierte Meshes aus Röhren und Superellipsoiden, Vanilla-Entity-Licht, keine Kanten-Facetten):
 * - `hades.fetter`: zwei verschränkte Kettenglieder, unten ein Vorhängeschloss (Eisen);
 * - `kampe.shackle`: großes Bronzeschloss am Boden mit Bügel, zwei Bändern, Nieten und Schlüsselloch;
 * - `sisyphus.boulder`: der vorhandene Fels aus dem Sisyphos-Modell (Knochen `boulder`, Textur `sisyphus`).
 * Ruhend gedämpft (dunkles Metall, gewöhnliches Licht), offen Gold (volle Helligkeit) mit weichem Lichthof, der mit
 * 0,8 Hz atmet (kein Blinken).
 */
object SubTargetRenderer {

    /** Zeichnet ein Teilziel; bekommt Zustand (mit `light`, `age`, `subState`), Matrizen (Füße), Warteschlange und Weltposition. */
    fun interface Draw {
        fun draw(state: SubTargetRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue, world: Vec3d)
    }

    const val FETTER = "hades.fetter"
    const val SHACKLE = "kampe.shackle"
    const val BOULDER = "sisyphus.boulder"

    private val kinds = HashMap<String, Draw>()

    /** Meldet eine Zeichnung für [kind] an; eine spätere Anmeldung ersetzt die frühere (Kampf-Pakete dürfen überschreiben). */
    fun register(kind: String, draw: Draw) {
        kinds[kind] = draw
    }

    /** Standard: goldener Lichtkörper (wie die Sanduhr des Kronos), Helligkeit nach Zustand. */
    private val DEFAULT = Draw { state, _, _, world ->
        val power = when (state.subState) {
            SubTarget.STATE_OPEN -> 1f
            SubTarget.STATE_BROKEN -> 0.15f
            else -> 0.35f
        }
        BossGlow.submit(BossGlow.CoreFrame(world, CoreEntity.HOURGLASS, power, state.age))
    }

    internal fun drawer(kind: String): Draw = kinds[kind] ?: DEFAULT

    // ---------------------------------------------------------------------------------------------
    // Farben und Licht
    // ---------------------------------------------------------------------------------------------

    private const val FULL_LIGHT = 0xF000F0

    /** Ruhend: Eisen (Fessel), Bronze (Schloss); offen: Gold. Je Teil (Körper, Band, Loch). */
    private val IRON = intArrayOf(0x5A5F69, 0x3E424A, 0x0E0F12)
    private val BRONZE = intArrayOf(0x7A5A36, 0x5C4128, 0x15100A)
    private val GOLD = intArrayOf(0xFFC94A, 0xE8A93A, 0x3A2A10)
    private val BROKEN = intArrayOf(0x2E3036, 0x24262B, 0x0A0A0C)

    private const val HALO_RGB = 0xFFD75E

    /** Atmen des Lichthofs: 0,8 Hz bei 20 Ticks/s. */
    private val HALO_RAD_PER_TICK = (2.0 * PI * 0.8 / 20.0).toFloat()

    private val SOLID_LAYER: RenderLayer = RenderLayers.entityCutoutNoCull(Hha.id("textures/vfx/solid.png"))
    private val HALO_LAYER: RenderLayer = RenderLayers.entityTranslucentEmissive(Hha.id("textures/vfx/glow.png"))

    private fun palette(base: IntArray, state: Int): IntArray = when (state) {
        SubTarget.STATE_OPEN -> GOLD
        SubTarget.STATE_BROKEN -> BROKEN
        else -> base
    }

    private fun lightFor(state: SubTargetRenderState): Int = if (state.subState == SubTarget.STATE_OPEN) FULL_LIGHT else state.light

    // ---------------------------------------------------------------------------------------------
    // Meshes (einmal gebaut, Einheiten = Blöcke bei size 1, Ursprung = Füße, +y oben, Vorderseite +z)
    // ---------------------------------------------------------------------------------------------

    /** Ein Mesh aus Vierecken: je Ecke x, y, z, nx, ny, nz, Teil (0 Körper, 1 Band, 2 Loch). */
    class Mesh {
        internal val data = ArrayList<Float>()
        val quads: Int get() = data.size / (4 * STRIDE)

        fun quad(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray, part: Int) {
            for (v in arrayOf(a, b, c, d)) {
                for (i in 0 until 6) data.add(v[i])
                data.add(part.toFloat())
            }
        }

        /** Eckpunkte für die Vorschau ohne Spiel (`tmp/overhaul4/previews/P04`). */
        fun vertices(): FloatArray = data.toFloatArray()

        companion object {
            const val STRIDE = 7
        }
    }

    private fun v(p: Vector3f, n: Vector3f) = floatArrayOf(p.x, p.y, p.z, n.x, n.y, n.z)

    /**
     * Röhre mit Radius [r] entlang [path] (geschlossen oder offen); Rahmen per Paralleltransport, Normalen glatt. Offene
     * Enden bekommen eine Halbkugel (rund, ohne Kante).
     */
    fun tube(mesh: Mesh, path: List<Vector3f>, closed: Boolean, r: Float, sides: Int, part: Int) {
        val n = path.size
        if (n < 2) return
        val tangents = (0 until n).map { i ->
            val prev = path[if (closed) (i - 1 + n) % n else max(i - 1, 0)]
            val next = path[if (closed) (i + 1) % n else minOf(i + 1, n - 1)]
            Vector3f(next).sub(prev).normalize()
        }
        val frames = ArrayList<Pair<Vector3f, Vector3f>>()
        var normal = Vector3f(tangents[0]).cross(if (abs(tangents[0].y) < 0.9f) Vector3f(0f, 1f, 0f) else Vector3f(1f, 0f, 0f)).normalize()
        for (i in 0 until n) {
            val t = tangents[i]
            // Paralleltransport: die alte Normale auf die neue Tangente projizieren.
            normal = Vector3f(normal).sub(Vector3f(t).mul(normal.dot(t))).normalize()
            frames.add(Vector3f(normal) to Vector3f(t).cross(normal).normalize())
        }
        fun ring(i: Int, j: Int): Pair<Vector3f, Vector3f> {
            val (nn, bb) = frames[i]
            val a = (2.0 * PI * j / sides).toFloat()
            val dir = Vector3f(nn).mul(cos(a)).add(Vector3f(bb).mul(sin(a)))
            return Vector3f(path[i]).add(Vector3f(dir).mul(r)) to dir
        }
        val segs = if (closed) n else n - 1
        for (i in 0 until segs) {
            val i2 = (i + 1) % n
            for (j in 0 until sides) {
                val (p00, n00) = ring(i, j)
                val (p01, n01) = ring(i, j + 1)
                val (p11, n11) = ring(i2, j + 1)
                val (p10, n10) = ring(i2, j)
                mesh.quad(v(p00, n00), v(p10, n10), v(p11, n11), v(p01, n01), part)
            }
        }
        if (!closed) {
            blob(mesh, path.first(), r, r, r, 1f, 8, sides, part)
            blob(mesh, path.last(), r, r, r, 1f, 8, sides, part)
        }
    }

    /**
     * Superellipsoid um [c] mit Halbachsen [rx], [ry], [rz]; [e] = 1 Kugel/Ellipsoid, kleiner = kantiger (aber mit
     * glatten, aus dem Gradienten gerechneten Normalen). Damit entstehen Schlosskörper, Bänder, Nieten.
     */
    fun blob(mesh: Mesh, c: Vector3f, rx: Float, ry: Float, rz: Float, e: Float, lat: Int, lon: Int, part: Int) {
        fun spow(x: Float, p: Float) = sign(x) * abs(x).pow(p)
        val p = 2f / e
        fun point(i: Int, j: Int): Pair<Vector3f, Vector3f> {
            val phi = (-PI / 2 + PI * i / lat).toFloat()
            val th = (2.0 * PI * j / lon).toFloat()
            val x = rx * spow(cos(phi), e) * spow(cos(th), e)
            val y = ry * spow(sin(phi), e)
            val z = rz * spow(cos(phi), e) * spow(sin(th), e)
            // Gradient der Fläche |x/rx|^p + |y/ry|^p + |z/rz|^p = 1.
            val nx = sign(x) * abs(x / rx).pow(p - 1f) / rx
            val ny = sign(y) * abs(y / ry).pow(p - 1f) / ry
            val nz = sign(z) * abs(z / rz).pow(p - 1f) / rz
            val nrm = Vector3f(nx, ny, nz)
            if (nrm.lengthSquared() < 1e-12f) nrm.set(0f, sign(y).takeIf { it != 0f } ?: 1f, 0f) else nrm.normalize()
            return Vector3f(c).add(x, y, z) to nrm
        }
        for (i in 0 until lat) {
            for (j in 0 until lon) {
                val (a, na) = point(i, j)
                val (b, nb) = point(i + 1, j)
                val (cc, nc) = point(i + 1, j + 1)
                val (d, nd) = point(i, j + 1)
                mesh.quad(v(a, na), v(d, nd), v(cc, nc), v(b, nb), part)
            }
        }
    }

    /** Ovales Kettenglied (Stadion-Bahn) in der Ebene aus [axisA] (lang) und [axisB] (kurz) um [c]. */
    private fun link(mesh: Mesh, c: Vector3f, axisA: Vector3f, axisB: Vector3f, halfLong: Float, halfShort: Float, r: Float, part: Int) {
        val path = ArrayList<Vector3f>()
        val straight = halfLong - halfShort
        val steps = 10
        // Zwei Halbkreise und zwei gerade Stücke: glatt, ohne Knick.
        for (k in 0..steps) {
            val a = (PI * k / steps).toFloat()
            path.add(Vector3f(c).add(Vector3f(axisA).mul(straight + halfShort * sin(a))).add(Vector3f(axisB).mul(halfShort * cos(a))))
        }
        for (k in 0..steps) {
            val a = (PI * k / steps).toFloat()
            path.add(Vector3f(c).add(Vector3f(axisA).mul(-straight - halfShort * sin(a))).add(Vector3f(axisB).mul(-halfShort * cos(a))))
        }
        tube(mesh, path, true, r, 10, part)
    }

    /** Bügel eines Vorhängeschlosses: zwei Schenkel ab [baseY] bis [topY], oben ein Halbkreis mit Radius [half]. */
    private fun shackle(mesh: Mesh, cx: Float, cz: Float, baseY: Float, topY: Float, half: Float, r: Float, part: Int) {
        val path = ArrayList<Vector3f>()
        path.add(Vector3f(cx - half, baseY, cz))
        path.add(Vector3f(cx - half, (baseY + topY) / 2f, cz))
        val steps = 12
        for (k in 0..steps) {
            val a = (PI * k / steps).toFloat()
            path.add(Vector3f(cx - half * cos(a), topY + half * sin(a), cz))
        }
        path.add(Vector3f(cx + half, (baseY + topY) / 2f, cz))
        path.add(Vector3f(cx + half, baseY, cz))
        tube(mesh, path, false, r, 10, part)
    }

    /** `hades.fetter`: oberes Glied (quer), mittleres Glied (längs), Vorhängeschloss unten. Höhe ≈ 1,6. */
    val FETTER_MESH: Mesh by lazy {
        Mesh().also { m ->
            val x = Vector3f(1f, 0f, 0f)
            val y = Vector3f(0f, 1f, 0f)
            val z = Vector3f(0f, 0f, 1f)
            link(m, Vector3f(0f, 1.28f, 0f), y, x, 0.32f, 0.17f, 0.065f, 0)
            link(m, Vector3f(0f, 0.86f, 0f), y, z, 0.32f, 0.17f, 0.065f, 0)
            shackle(m, 0f, 0f, 0.42f, 0.55f, 0.12f, 0.045f, 1)
            blob(m, Vector3f(0f, 0.3f, 0f), 0.24f, 0.19f, 0.1f, 0.35f, 14, 20, 0)
            blob(m, Vector3f(0f, 0.3f, 0f), 0.255f, 0.035f, 0.115f, 0.5f, 6, 20, 1) // Band um den Körper
            blob(m, Vector3f(0f, 0.27f, 0.095f), 0.035f, 0.035f, 0.012f, 1f, 6, 10, 2) // Schlüsselloch
            blob(m, Vector3f(0f, 0.2f, 0.095f), 0.016f, 0.05f, 0.012f, 0.6f, 6, 8, 2)
        }
    }

    /** `kampe.shackle`: Schlosskörper 1,0 × 0,75 × 0,45 am Boden, dicker Bügel bis ≈ 1,45, Bänder, Nieten, Loch. */
    val SHACKLE_MESH: Mesh by lazy {
        Mesh().also { m ->
            shackle(m, 0f, 0f, 0.62f, 1.05f, 0.3f, 0.1f, 1)
            blob(m, Vector3f(0f, 0.42f, 0f), 0.5f, 0.37f, 0.22f, 0.3f, 16, 24, 0)
            blob(m, Vector3f(0f, 0.66f, 0f), 0.52f, 0.05f, 0.235f, 0.4f, 6, 24, 1) // oberes Band
            blob(m, Vector3f(0f, 0.17f, 0f), 0.52f, 0.05f, 0.235f, 0.4f, 6, 24, 1) // unteres Band
            for (sx in floatArrayOf(-0.36f, 0.36f)) for (sy in floatArrayOf(0.3f, 0.54f)) {
                blob(m, Vector3f(sx, sy, 0.215f), 0.035f, 0.035f, 0.025f, 1f, 6, 10, 1) // Nieten
            }
            blob(m, Vector3f(0f, 0.47f, 0.212f), 0.07f, 0.07f, 0.02f, 1f, 8, 14, 2) // Schlüsselloch rund
            blob(m, Vector3f(0f, 0.34f, 0.212f), 0.03f, 0.1f, 0.02f, 0.6f, 6, 10, 2) // … und Schlitz
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Zeichnen
    // ---------------------------------------------------------------------------------------------

    private fun submitMesh(mesh: Mesh, colors: IntArray, matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int) {
        val data = mesh.data
        queue.submitCustom(matrices, SOLID_LAYER) { entry, vc ->
            var i = 0
            while (i < data.size) {
                val rgb = colors[data[i + 6].toInt().coerceIn(0, colors.size - 1)]
                put(vc, entry, data[i], data[i + 1], data[i + 2], data[i + 3], data[i + 4], data[i + 5], 0xFF000000.toInt() or rgb, light)
                i += Mesh.STRIDE
            }
        }
    }

    private fun put(vc: VertexConsumer, entry: MatrixStack.Entry, x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, argb: Int, light: Int) {
        vc.vertex(entry, x, y, z).color(argb).texture(0.5f, 0.5f).overlay(OverlayTexture.DEFAULT_UV).light(light).normal(entry, nx, ny, nz)
    }

    /** Weicher goldener Lichthof hinter dem Teilziel, zur Kamera gedreht; nur im offenen Zustand. */
    private fun submitHalo(state: SubTargetRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue, centerY: Float, radius: Float) {
        if (state.subState != SubTarget.STATE_OPEN) return
        val pulse = 0.5f + 0.5f * sin(state.age * HALO_RAD_PER_TICK)
        val alpha = ((0.28f + 0.14f * pulse) * 255f).toInt().coerceIn(0, 255)
        val color = (alpha shl 24) or HALO_RGB
        matrices.push()
        matrices.translate(0f, centerY, 0f)
        matrices.multiply(state.camera)
        queue.submitCustom(matrices, HALO_LAYER) { entry, vc ->
            val r = radius
            vc.vertex(entry, -r, -r, 0f).color(color).texture(0f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_LIGHT).normal(entry, 0f, 0f, 1f)
            vc.vertex(entry, r, -r, 0f).color(color).texture(1f, 1f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_LIGHT).normal(entry, 0f, 0f, 1f)
            vc.vertex(entry, r, r, 0f).color(color).texture(1f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_LIGHT).normal(entry, 0f, 0f, 1f)
            vc.vertex(entry, -r, r, 0f).color(color).texture(0f, 0f).overlay(OverlayTexture.DEFAULT_UV).light(FULL_LIGHT).normal(entry, 0f, 0f, 1f)
        }
        matrices.pop()
    }

    /** Hängt leicht und dreht sich sehr langsam (ein Requisit, kein Wesen: keine eigene Bewegung darüber hinaus). */
    private fun meshDraw(mesh: () -> Mesh, base: IntArray, haloY: Float, haloR: Float, sway: Boolean) = Draw { state, matrices, queue, _ ->
        matrices.push()
        val s = state.size
        matrices.scale(s, s, s)
        if (sway) {
            // Ruhiges Pendeln um den oberen Aufhängepunkt (±3°, 0,25 Hz).
            matrices.translate(0f, 1.6f, 0f)
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(3f * sin(state.age * 0.0785f)))
            matrices.translate(0f, -1.6f, 0f)
        }
        submitHalo(state, matrices, queue, haloY, haloR)
        submitMesh(mesh(), palette(base, state.subState), matrices, queue, lightFor(state))
        matrices.pop()
    }

    init {
        register(FETTER, meshDraw({ FETTER_MESH }, IRON, 0.75f, 0.95f, sway = true))
        register(SHACKLE, meshDraw({ SHACKLE_MESH }, BRONZE, 0.6f, 1.05f, sway = false))
    }

    // ---------------------------------------------------------------------------------------------
    // Fels des Sisyphos (vorhandenes Modell)
    // ---------------------------------------------------------------------------------------------

    /** Der Knochen `boulder` aus dem Sisyphos-Modell, um seine eigene Mitte gelegt (wie `FlyingWeapon` im `BoltRenderer`). */
    private class Boulder(context: EntityRendererFactory.Context) {
        val center = Vector3f()

        /** Halbe größte Ausdehnung in Blöcken (vor Skalierung). */
        var radius = 0.5f
        val part: ModelPart? = try {
            context.getPart(BossLayers.SISYPHUS).createPartGetter().apply("boulder")?.also { measure(it) }
        } catch (e: Exception) {
            null
        }
        val layer: RenderLayer = RenderLayers.entityCutoutNoCull(boss("sisyphus"))

        private fun measure(p: ModelPart) {
            p.setOrigin(0f, 0f, 0f)
            p.setAngles(0f, 0f, 0f)
            p.xScale = 1f
            p.yScale = 1f
            p.zScale = 1f
            p.visible = true
            val min = Vector3f(Float.MAX_VALUE)
            val max = Vector3f(-Float.MAX_VALUE)
            p.collectVertices(MatrixStack()) {
                min.min(it)
                max.max(it)
            }
            if (min.x > max.x) return
            Vector3f(max).add(min).mul(0.5f, center)
            val ext = Vector3f(max).sub(min)
            radius = max(ext.x, max(ext.y, ext.z)) * 0.5f
        }
    }

    /** Fels: ein Block Durchmesser bei size 1 (die Trefferbox 1,2 × 1,6 umschließt ihn), Unterkante auf den Füßen. */
    private fun boulderDraw(boulder: Boulder) = Draw { state, matrices, queue, world ->
        val part = boulder.part
        if (part == null) {
            DEFAULT.draw(state, matrices, queue, world)
            return@Draw
        }
        val s = state.size
        val scale = BOULDER_DIAMETER * 0.5f / boulder.radius.coerceAtLeast(0.01f) * s
        matrices.push()
        submitHalo(state, matrices, queue, BOULDER_DIAMETER * 0.5f * s, BOULDER_DIAMETER * 1.05f * s)
        matrices.translate(0f, BOULDER_DIAMETER * 0.5f * s, 0f)
        matrices.scale(scale, scale, scale)
        // Modellraum der Bestandsmodelle steht kopf (y nach unten): umdrehen.
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(180f))
        matrices.translate(-boulder.center.x, -boulder.center.y, -boulder.center.z)
        val tint = when (state.subState) {
            SubTarget.STATE_OPEN -> 0xFFFFE6A8.toInt()
            SubTarget.STATE_BROKEN -> 0xFF5A5A5A.toInt()
            else -> 0xFFB8B8B8.toInt()
        }
        queue.submitModelPart(part, matrices, boulder.layer, lightFor(state), OverlayTexture.DEFAULT_UV, null, false, false, tint, null, 0)
        matrices.pop()
    }

    private const val BOULDER_DIAMETER = 1.1f

    class Renderer(context: EntityRendererFactory.Context) : EntityRenderer<SubTargetEntity, SubTargetRenderState>(context) {

        init {
            // Der Fels braucht die Modellschicht aus dem Kontext; die Anmeldung läuft bei jedem Neuladen der Ressourcen.
            val boulder = Boulder(context)
            if (kinds[BOULDER] == null || kinds[BOULDER] === lastBoulder) {
                val draw = boulderDraw(boulder)
                lastBoulder = draw
                register(BOULDER, draw)
            }
        }

        override fun createRenderState() = SubTargetRenderState()

        override fun updateRenderState(entity: SubTargetEntity, state: SubTargetRenderState, tickDelta: Float) {
            super.updateRenderState(entity, state, tickDelta)
            state.kind = entity.kind
            state.size = entity.size
            state.subState = entity.state
        }

        override fun render(state: SubTargetRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue, cameraState: CameraRenderState) {
            val origin = matrices.peek().positionMatrix.transformPosition(0f, 0f, 0f, Vector3f())
            val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
            state.camera = cameraState.orientation ?: Quaternionf()
            drawer(state.kind).draw(state, matrices, queue, Vec3d(origin.x + cam.x, origin.y + cam.y, origin.z + cam.z))
            super.render(state, matrices, queue, cameraState)
        }
    }

    /** Die zuletzt von hier angemeldete Fels-Zeichnung (damit ein Kampf-Paket sie überschreiben kann, ohne dass ein Neuladen sie zurücksetzt). */
    private var lastBoulder: Draw? = null
}
