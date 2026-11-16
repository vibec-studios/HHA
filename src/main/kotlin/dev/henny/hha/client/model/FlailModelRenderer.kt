package dev.henny.hha.client.model

import com.mojang.serialization.MapCodec
import dev.henny.hha.Hha
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.item.ItemRenderState
import net.minecraft.client.render.item.model.special.SpecialModelRenderer
import net.minecraft.client.render.item.model.special.SpecialModelTypes
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.ItemDisplayContext
import net.minecraft.item.ItemStack
import net.minecraft.util.HeldItemContext
import net.minecraft.util.Identifier
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import org.joml.Vector3fc
import java.util.function.Consumer
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Kettenphysik des Hells Mace in der Hand (Item-Modelltyp `hha:flail`, siehe `items/hells_mace.json`).
 *
 * Der Griff kommt als normales Modell (`hells_mace_handle_3d`), dieser Renderer zeichnet die drei Kettenglieder und
 * den Kopf (`items/flail/hells_mace_*`). Die Glieder hängen an einer Verlet-Kette, die **in Weltkoordinaten** läuft:
 * Schwerkraft zieht den Kopf nach unten, Laufen, Drehen und Schlagen schleudern ihn mit, der Griff stößt ihn ab.
 * Jeder Träger (Wesen + Hand + Ego-/3rd-Sicht) hat eine eigene Kette. Ohne Träger (Inventar, Rahmen, Kopf) steht
 * das Modell wie gebaut.
 */
class FlailModelRenderer : SpecialModelRenderer<FlailModelRenderer.Data> {

    /** Träger (Wesen-ID) und der Stapel selbst (für Verzauberung/Glanz der Teile); gleich ist, was denselben Träger hat. */
    class Data(val holder: Int, val stack: ItemStack) {
        override fun equals(other: Any?): Boolean = other is Data && other.holder == holder
        override fun hashCode(): Int = holder
    }

    override fun getData(stack: ItemStack): Data {
        val entity = runCatching { HOLDER.get()?.entity }.getOrNull()
        return Data(entity?.id ?: NO_HOLDER, stack)
    }

    override fun render(
        data: Data?, displayContext: ItemDisplayContext, matrices: MatrixStack, queue: OrderedRenderCommandQueue,
        light: Int, overlay: Int, glint: Boolean, outlineColor: Int,
    ) {
        val d = data ?: return
        val mc = MinecraftClient.getInstance()
        val nodes = FlailPhysics.pose(d.holder, displayContext, matrices.peek().positionMatrix)
        for (i in PARTS.indices) {
            val from = FlailPhysics.REST[i]
            val to = FlailPhysics.REST[i + 1]
            val a = nodes[i]
            val b = nodes[i + 1]
            val restDir = Vector3f(to).sub(from).normalize()
            val dir = Vector3f(b).sub(a)
            if (dir.lengthSquared() < 1e-8f) dir.set(restDir) else dir.normalize()
            matrices.push()
            matrices.translate(a.x / 16f, a.y / 16f, a.z / 16f)
            matrices.multiply(Quaternionf().rotationTo(restDir, dir))
            // Das Teilmodell liegt an seiner Stelle im Gesamtmodell; die Anzeige ohne Kontext verschiebt um -0,5
            matrices.translate(-from.x / 16f + 0.5f, -from.y / 16f + 0.5f, -from.z / 16f + 0.5f)
            val part = d.stack.copy()
            part.set(DataComponentTypes.ITEM_MODEL, PARTS[i])
            // Je Aufruf ein eigener Zustand: die Befehlsliste hält Verweise auf die Quads bis zum Zeichnen
            val state = ItemRenderState()
            mc.itemModelManager.clearAndUpdate(state, part, ItemDisplayContext.NONE, mc.world, mc.player, 0)
            state.render(matrices, queue, light, overlay, outlineColor)
            matrices.pop()
        }
    }

    override fun collectVertices(vertices: Consumer<Vector3fc>) {
        // Hüllquader der Kette samt Kopf in Ruhelage (für Rahmen, Umriss und GUI-Größe)
        for (x in floatArrayOf(3f, 13f)) for (y in floatArrayOf(3.5f, 19.5f)) for (z in floatArrayOf(3f, 13f)) {
            vertices.accept(Vector3f(x / 16f, y / 16f, z / 16f))
        }
    }

    class Unbaked : SpecialModelRenderer.Unbaked {
        override fun bake(context: SpecialModelRenderer.BakeContext): SpecialModelRenderer<*> = FlailModelRenderer()
        override fun getCodec(): MapCodec<out SpecialModelRenderer.Unbaked> = CODEC
    }

    companion object {
        const val NO_HOLDER = Int.MIN_VALUE

        /** Träger des Gegenstands, den `ItemModelManager.update` gerade auflöst (gesetzt von ItemModelManagerMixin). */
        @JvmField
        val HOLDER: ThreadLocal<HeldItemContext?> = ThreadLocal()

        val CODEC: MapCodec<Unbaked> = MapCodec.unit(Unbaked())

        private val PARTS: Array<Identifier> = arrayOf(
            Hha.id("flail/hells_mace_link1"), Hha.id("flail/hells_mace_link2"), Hha.id("flail/hells_mace_link3"), Hha.id("flail/hells_mace_head"),
        )

        fun register() {
            SpecialModelTypes.ID_MAPPER.put(Hha.id("flail"), CODEC)
        }
    }
}

/** Verlet-Kette je Träger, in Weltkoordinaten; liefert die Knoten im Modellraum (Pixel) zurück. */
object FlailPhysics {

    /** Aufhängepunkte in Ruhelage (Pixel im Modell): Öse, Gelenke zwischen den Gliedern, Mitte des Kopfs. */
    val REST: Array<Vector3f> = arrayOf(
        Vector3f(8f, 3.85f, 8f), Vector3f(8f, 5.85f, 8f), Vector3f(8f, 7.85f, 8f), Vector3f(8f, 9.95f, 8f), Vector3f(8f, 14f, 8f),
    )

    /** Griff als Kapsel (Knauf bis Tülle), daran prallen Glieder und Kopf ab. Radien in Pixeln. */
    private val HANDLE_A = Vector3f(8f, -8f, 8f)
    private val HANDLE_B = Vector3f(8f, 3f, 8f)
    private const val HANDLE_R = 1.1f
    private val RADIUS = floatArrayOf(0f, 0.9f, 0.9f, 1.1f, 3.6f)

    private const val GRAVITY = 16.0      // Blöcke/s², etwas kräftiger als echt: der Kopf ist schwer
    private const val DAMPING = 0.993     // je Teilschritt
    private const val STEP = 1.0 / 240.0
    private const val ITERATIONS = 4
    private const val RESET_NANOS = 500_000_000L

    private class Sim {
        val pos = Array(5) { DoubleArray(3) }
        val prev = Array(5) { DoubleArray(3) }
        val anchor = DoubleArray(3)
        var last = 0L
        var ready = false
    }

    private val sims = HashMap<Long, Sim>()
    private var lastCleanup = 0L

    private fun rest(): Array<Vector3f> = Array(REST.size) { Vector3f(REST[it]) }

    fun pose(holder: Int, context: ItemDisplayContext, pose: Matrix4f): Array<Vector3f> {
        val mc = MinecraftClient.getInstance()
        val first = context.isFirstPerson
        val third = context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
        if (holder == FlailModelRenderer.NO_HOLDER || !(first || third) || mc.world == null) return rest()
        val camera = mc.gameRenderer.camera
        // Spielerfigur im Inventar: 3rd-Person-Hand, während die Kamera in der Ego-Sicht ist → ruhig stehen lassen
        if (third && holder == mc.cameraEntity?.id && !camera.isThirdPerson) return rest()
        val inv = Matrix4f(pose)
        if (inv.determinant() == 0f) return rest()
        inv.invert()

        val cam = camera.cameraPos
        val rot = if (first) Quaternionf(camera.rotation) else null
        val back = rot?.let { Quaternionf(it).conjugate() }
        val tmp = Vector3f()
        fun world(p: Vector3fc, out: DoubleArray) {
            pose.transformPosition(p.x() / 16f, p.y() / 16f, p.z() / 16f, tmp)
            rot?.transform(tmp)
            out[0] = cam.x + tmp.x
            out[1] = cam.y + tmp.y
            out[2] = cam.z + tmp.z
        }

        val restWorld = Array(5) { DoubleArray(3) }
        for (i in 0 until 5) world(REST[i], restWorld[i])
        val lengths = DoubleArray(4) { dist(restWorld[it], restWorld[it + 1]) }
        val px = lengths[0] / (REST[1].y - REST[0].y) // Weltlänge eines Modellpixels
        if (px <= 1e-6 || px > 1.0) return rest()
        val handleA = DoubleArray(3).also { world(HANDLE_A, it) }
        val handleB = DoubleArray(3).also { world(HANDLE_B, it) }

        val now = System.nanoTime()
        val key = (holder.toLong() shl 8) or context.ordinal.toLong()
        val sim = sims.getOrPut(key) { Sim() }
        val anchor = restWorld[0]
        if (!sim.ready || now - sim.last > RESET_NANOS || dist(anchor, sim.anchor) > 4.0) {
            for (i in 0 until 5) {
                restWorld[i].copyInto(sim.pos[i])
                restWorld[i].copyInto(sim.prev[i])
            }
            anchor.copyInto(sim.anchor)
            sim.last = now
            sim.ready = true
        }
        val dt = if (mc.isPaused) 0.0 else ((now - sim.last) / 1e9).coerceIn(0.0, 0.05)
        sim.last = now
        val steps = ceil(dt / STEP).toInt()
        val from = sim.anchor.copyOf()
        for (s in 1..steps) {
            val k = s.toDouble() / steps
            val h = dt / steps
            for (c in 0 until 3) {
                sim.pos[0][c] = from[c] + (anchor[c] - from[c]) * k
                sim.prev[0][c] = sim.pos[0][c]
            }
            for (i in 1 until 5) {
                val p = sim.pos[i]
                val q = sim.prev[i]
                for (c in 0 until 3) {
                    val v = (p[c] - q[c]) * DAMPING
                    q[c] = p[c]
                    p[c] += v + if (c == 1) -GRAVITY * h * h else 0.0
                }
            }
            repeat(ITERATIONS) {
                for (i in 0 until 4) link(sim.pos[i], sim.pos[i + 1], lengths[i], pinned = i == 0)
                for (i in 1 until 5) pushOut(sim.pos[i], handleA, handleB, (HANDLE_R + RADIUS[i]) * px)
            }
        }
        anchor.copyInto(sim.anchor)
        cleanup(now)

        val out = Array(5) { Vector3f() }
        for (i in 0 until 5) {
            val p = if (i == 0) anchor else sim.pos[i]
            tmp.set((p[0] - cam.x).toFloat(), (p[1] - cam.y).toFloat(), (p[2] - cam.z).toFloat())
            back?.transform(tmp)
            inv.transformPosition(tmp)
            out[i].set(tmp.x * 16f, tmp.y * 16f, tmp.z * 16f)
        }
        return out
    }

    /** Abstand zweier Knoten auf [len] bringen; der erste ist bei [pinned] fest (Öse am Griff). */
    private fun link(a: DoubleArray, b: DoubleArray, len: Double, pinned: Boolean) {
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val dz = b[2] - a[2]
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d < 1e-9) return
        val diff = (d - len) / d
        if (pinned) {
            b[0] -= dx * diff; b[1] -= dy * diff; b[2] -= dz * diff
        } else {
            val h = diff * 0.5
            a[0] += dx * h; a[1] += dy * h; a[2] += dz * h
            b[0] -= dx * h; b[1] -= dy * h; b[2] -= dz * h
        }
    }

    /** Punkt aus der Kapsel a–b (Radius r) schieben. */
    private fun pushOut(p: DoubleArray, a: DoubleArray, b: DoubleArray, r: Double) {
        val abx = b[0] - a[0]
        val aby = b[1] - a[1]
        val abz = b[2] - a[2]
        val len2 = abx * abx + aby * aby + abz * abz
        if (len2 < 1e-12) return
        val t = (((p[0] - a[0]) * abx + (p[1] - a[1]) * aby + (p[2] - a[2]) * abz) / len2).coerceIn(0.0, 1.0)
        val cx = a[0] + abx * t
        val cy = a[1] + aby * t
        val cz = a[2] + abz * t
        val dx = p[0] - cx
        val dy = p[1] - cy
        val dz = p[2] - cz
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        if (d >= r) return
        if (d < 1e-9) {
            // genau auf der Achse (Kette fällt senkrecht am Griff herab): zur Seite ausweichen
            val ex = if (kotlin.math.abs(abx) < kotlin.math.abs(abz)) 1.0 else 0.0
            val ez = 1.0 - ex
            val sx = aby * ez
            val sy = abz * ex - abx * ez
            val sz = -aby * ex
            val sl = sqrt(sx * sx + sy * sy + sz * sz)
            if (sl < 1e-12) return
            p[0] = cx + sx / sl * r; p[1] = cy + sy / sl * r; p[2] = cz + sz / sl * r
            return
        }
        val k = (r - d) / d
        p[0] += dx * k; p[1] += dy * k; p[2] += dz * k
    }

    private fun dist(a: DoubleArray, b: DoubleArray): Double {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun cleanup(now: Long) {
        if (now - lastCleanup < 5_000_000_000L) return
        lastCleanup = now
        sims.values.removeIf { now - it.last > 10_000_000_000L }
    }
}
