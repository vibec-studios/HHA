package dev.henny.hha.client.model

import dev.henny.hha.Hha
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.command.RenderCommandQueue
import net.minecraft.client.util.math.MatrixStack

/** Alle Federn eines Knochens als Vertex-Tabelle; erzeugt von tools/hha_assets/boss_models.py. */
class FeatherBone(val path: Array<String>, val data: FloatArray)

/**
 * Zeichnet Federn als glatte, gebogene Streifen aus einem hochauflösenden Atlas mit linearem Filter. Würfel-Ebenen
 * mit Modell-UVs würden auf dem Atlas nur Pixeltreppen ergeben, deshalb laufen Federn nicht über das normale Modell.
 */
object FeatherDraw {

    private val TEXTURE = Hha.id("textures/entity/boss/feathers.png")

    val LAYER: RenderLayer = RenderLayers.entityCutoutNoCull(TEXTURE)
    val GLOW_LAYER: RenderLayer = RenderLayers.eyes(Hha.id("textures/entity/boss/feathers_glow.png"))
    val GHOST_LAYER: RenderLayer = RenderLayers.entityTranslucentEmissive(TEXTURE)

    const val CELL_LONG = 0
    const val CELL_COVERT = 1
    const val CELL_PLUME = 2
    const val CELL_TINT = 3

    private const val ROWS = 4
    private const val PER_VERTEX = 6
    private const val PER_FEATHER = ROWS * 2 * PER_VERTEX
    private const val ATLAS_W = 256f
    private const val CELL_W = 64f

    private fun shaded(color: Int, shade: Float): Int {
        val r = (((color ushr 16) and 0xFF) * shade).toInt()
        val g = (((color ushr 8) and 0xFF) * shade).toInt()
        val b = ((color and 0xFF) * shade).toInt()
        return (color and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    private fun VertexConsumer.feather(
        entry: MatrixStack.Entry, x: Float, y: Float, z: Float, u: Float, v: Float, color: Int, overlay: Int, light: Int,
    ) {
        // Modelleinheiten; die Normale zeigt immer nach oben, sonst würde die Rückseite einer Feder grau.
        vertex(entry, x / 16f, y / 16f, z / 16f).color(color).texture(u, v).overlay(overlay).light(light).normal(0f, 1f, 0f)
    }

    /** Eine Vertex-Tabelle aus dem Generator. */
    fun table(vc: VertexConsumer, entry: MatrixStack.Entry, data: FloatArray, color: Int, overlay: Int, light: Int) {
        var i = 0
        while (i < data.size) {
            for (row in 0 until ROWS - 1) {
                val a = i + row * 2 * PER_VERTEX
                val b = a + PER_VERTEX
                val c = b + PER_VERTEX * 2
                val d = a + PER_VERTEX * 2
                for (at in intArrayOf(a, b, c, d)) {
                    vc.feather(entry, data[at], data[at + 1], data[at + 2], data[at + 3], data[at + 4], shaded(color, data[at + 5]), overlay, light)
                }
            }
            i += PER_FEATHER
        }
    }

    /**
     * Eine einzelne Feder, frei platziert (Modelleinheiten). [dir] ist die Wuchsrichtung, [across] die Breitenachse,
     * beide normiert; [normal] gibt die Wölbung an.
     */
    fun strip(
        vc: VertexConsumer, entry: MatrixStack.Entry,
        rx: Float, ry: Float, rz: Float,
        dx: Float, dy: Float, dz: Float,
        ax: Float, ay: Float, az: Float,
        nx: Float, ny: Float, nz: Float,
        length: Float, width: Float, bend: Float, cell: Int, flip: Boolean, color: Int, overlay: Int, light: Int,
    ) {
        val left = (cell * CELL_W + 1f) / ATLAS_W
        val right = ((cell + 1) * CELL_W - 1f) / ATLAS_W
        val u0 = if (flip) right else left
        val u1 = if (flip) left else right
        val half = width / 2f
        val steps = 3
        fun corner(row: Int, side: Float, u: Float) {
            val t = row.toFloat() / steps
            val along = length * t
            val out = bend * length * t * t
            vc.feather(
                entry,
                rx + dx * along + ax * half * side + nx * out,
                ry + dy * along + ay * half * side + ny * out,
                rz + dz * along + az * half * side + nz * out,
                u, 1f - t, shaded(color, 0.74f + 0.26f * t), overlay, light,
            )
        }
        for (row in 0 until steps) {
            corner(row, -1f, u0)
            corner(row, 1f, u1)
            corner(row + 1, 1f, u1)
            corner(row + 1, -1f, u0)
        }
    }
}

/** Bindet die Federtabellen eines Modells an dessen Knochen. */
class FeatherRig(root: ModelPart, bones: List<FeatherBone>) {

    private class Bound(val chain: List<ModelPart>, val data: FloatArray)

    private val bound = bones.map { bone ->
        val chain = ArrayList<ModelPart>()
        chain.add(root)
        var part = root
        for (name in bone.path) {
            part = part.getChild(name)
            chain.add(part)
        }
        Bound(chain, bone.data)
    }

    /** Die Knochen müssen schon gestellt sein (setAngles); die Matrizen werden hier festgehalten, nicht erst beim Zeichnen. */
    fun submit(matrices: MatrixStack, queue: RenderCommandQueue, layer: RenderLayer, color: Int, overlay: Int, light: Int) {
        for (bone in bound) {
            if (bone.chain.any { !it.visible }) continue
            matrices.push()
            for (part in bone.chain) part.applyTransform(matrices)
            queue.submitCustom(matrices, layer) { entry, vc -> FeatherDraw.table(vc, entry, bone.data, color, overlay, light) }
            matrices.pop()
        }
    }
}
