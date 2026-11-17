package dev.henny.hha.client.rpg.tunnel

import com.mojang.blaze3d.systems.ProjectionType
import com.mojang.blaze3d.systems.RenderSystem
import dev.henny.hha.client.vfx.fx.FxClient
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.render.SpecialGuiElementRenderer
import net.minecraft.client.render.DiffuseLighting
import net.minecraft.client.render.ProjectionMatrix3
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.render.state.CameraRenderState
import net.minecraft.client.util.BufferAllocator
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Zeichnet die Tunnelbühne ([TunnelScene]) als GUI-Sonderelement (STAND 2.1 Weg A): eigene Perspektive
 * ([TunnelCamera], FOV 58), ein Tiefenpuffer für Röhre und Figuren. Ablauf je Bild: Röhre (fest vorgebautes Mesh,
 * Bewegung im Shader) → Figuren (Vanilla-Spielerrenderer mit Skin, Kostüm und `HhaPose`) → Lichtringe und Schleier
 * (additiv, ohne Tiefe zu schreiben). Das Ergebnis ist vormultipliziert; Löcher (Austrittskreis) haben Alpha 0.
 *
 * Registriert über die Fabric-API `SpecialGuiElementRegistry` in `RpgClient.init` (Client-Start). Fehler beim Zeichnen
 * fängt [FxClient.guarded]; danach steht [TunnelPipelines.failed] und es gilt der 2D-Rückfall.
 */
class TunnelRenderer(vertexConsumers: VertexConsumerProvider.Immediate) : SpecialGuiElementRenderer<TunnelScene>(vertexConsumers) {

    private val allocator = BufferAllocator(BUFFER_BYTES)
    private val immediate = VertexConsumerProvider.immediate(allocator)
    private val projection = ProjectionMatrix3("hha_tunnel", TunnelCamera.NEAR, TunnelCamera.FAR)
    private val scratch = Vector3f()

    override fun getElementClass(): Class<TunnelScene> = TunnelScene::class.java

    override fun getName(): String = "hha_tunnel"

    override fun render(state: TunnelScene, matrices: MatrixStack) {
        if (FxClient.guarded("tunnel.render") { draw(state) } == null) {
            TunnelPipelines.fail("Tunnelbühne ließ sich nicht zeichnen", null)
        }
    }

    private fun draw(state: TunnelScene) {
        val client = MinecraftClient.getInstance()
        val scale = client.window.scaleFactor
        val width = ((state.x2() - state.x1()) * scale).coerceAtLeast(1)
        val height = ((state.y2() - state.y1()) * scale).coerceAtLeast(1)
        RenderSystem.setProjectionMatrix(projection.set(width, height, TunnelCamera.FOV), ProjectionType.PERSPECTIVE)
        val view = TunnelCamera.view(state.time, state.lookX, state.lookY)

        // 1. Röhre und Deckel
        val wallLayer = TunnelPipelines.wall(state.variant)
        emitTunnel(immediate.getBuffer(wallLayer), view, state)
        immediate.draw(wallLayer)

        // 2. Figuren: je eigener Render-Zustand, Lage in Szenen-Einheiten (die Sicht steckt in der Matrix)
        if (state.figures.isNotEmpty()) {
            client.gameRenderer.diffuseLighting.setShaderLights(DiffuseLighting.Type.LEVEL)
            val dispatcher = client.gameRenderer.entityRenderDispatcher
            val manager = client.entityRenderDispatcher
            val stack = MatrixStack()
            stack.peek().positionMatrix.set(view)
            stack.peek().normalMatrix.set(view)
            val camera = CameraRenderState().apply {
                pos = Vec3d.ZERO
                entityPos = Vec3d.ZERO
                blockPos = BlockPos.ORIGIN
                orientation = Quaternionf()
                initialized = true
            }
            for (figure in state.figures) {
                stack.push()
                stack.translate(figure.x, figure.y - HALF_HEIGHT, figure.z)
                manager.render(figure.state, camera, 0.0, 0.0, 0.0, stack, dispatcher.queue)
                stack.pop()
            }
            dispatcher.render()
        }

        // 3. Lichtringe (belegte und freie Plätze) und Schleier
        val glow = immediate.getBuffer(TunnelPipelines.GLOW)
        var any = false
        for (figure in state.figures) {
            if (figure.ring > 0.01f) {
                ring(glow, view, figure.x, figure.y - HALF_HEIGHT + RING_LIFT, figure.z, figure.ringColor, figure.ring)
                any = true
            }
            if (figure.veil in 0f..1f) {
                veil(glow, view, figure, figure.veil)
                any = true
            }
        }
        for (slot in state.freeSlots) {
            ring(glow, view, slot[0], slot[1] - HALF_HEIGHT + RING_LIFT, slot[2], state.tint, FREE_RING)
            any = true
        }
        if (any) immediate.draw(TunnelPipelines.GLOW)
    }

    /** Röhre und Deckel: Lage in Kameraachsen, u = Umlauf + Drehung, v = −z + Fahrt, Farbe = Weg + Lichtgipfel. */
    private fun emitTunnel(out: VertexConsumer, view: Matrix4f, state: TunnelScene) {
        val flash = (state.flash.coerceIn(0f, 1f) * 255f).toInt()
        val color = (flash shl 24) or (state.tint and 0xFFFFFF)
        val speed = (state.speed * 1000f).toInt().coerceIn(0, 32000)
        val exit = (state.exitOpen.coerceIn(0f, 1f) * 1000f).toInt()
        val v = scratch
        for (i in 0 until MESH.count) {
            v.set(MESH.x[i], MESH.y[i], MESH.z[i])
            view.transformPosition(v)
            out.vertex(v.x, v.y, v.z).texture(MESH.u[i] + state.rotation, -MESH.z[i] + state.scroll).color(color).light(speed, exit)
        }
    }

    /** Waagrechter Lichtring unter den Füßen (Form 0 im Glüh-Shader). */
    private fun ring(out: VertexConsumer, view: Matrix4f, x: Float, y: Float, z: Float, rgb: Int, strength: Float) {
        val color = ((strength.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (rgb and 0xFFFFFF)
        corner(out, view, x - RING_SIZE, y, z - RING_SIZE, -1f, -1f, color, 0, 0)
        corner(out, view, x - RING_SIZE, y, z + RING_SIZE, -1f, 1f, color, 0, 0)
        corner(out, view, x + RING_SIZE, y, z + RING_SIZE, 1f, 1f, color, 0, 0)
        corner(out, view, x + RING_SIZE, y, z - RING_SIZE, 1f, -1f, color, 0, 0)
    }

    /** Senkrechter Lichtschleier vor der Figur, zieht mit [progress] von oben nach unten (Form 1). */
    private fun veil(out: VertexConsumer, view: Matrix4f, figure: TunnelFigure, progress: Float) {
        val color = (0xE6 shl 24) or 0xFFF4DC
        val run = (progress.coerceIn(0f, 1f) * 1000f).toInt()
        val z = figure.z + VEIL_FRONT
        val y0 = figure.y - HALF_HEIGHT - 0.15f
        val y1 = figure.y + HALF_HEIGHT + 0.25f
        corner(out, view, figure.x - VEIL_HALF_WIDTH, y0, z, -1f, -1f, color, 1000, run)
        corner(out, view, figure.x + VEIL_HALF_WIDTH, y0, z, 1f, -1f, color, 1000, run)
        corner(out, view, figure.x + VEIL_HALF_WIDTH, y1, z, 1f, 1f, color, 1000, run)
        corner(out, view, figure.x - VEIL_HALF_WIDTH, y1, z, -1f, 1f, color, 1000, run)
    }

    private fun corner(out: VertexConsumer, view: Matrix4f, x: Float, y: Float, z: Float, u: Float, w: Float, color: Int, shape: Int, param: Int) {
        val v = scratch.set(x, y, z)
        view.transformPosition(v)
        out.vertex(v.x, v.y, v.z).texture(u, w).color(color).light(shape, param)
    }

    override fun close() {
        super.close()
        projection.close()
        allocator.close()
    }

    /** Fest vorgebautes Mesh der Röhre (je Ecke Lage und Umlauf u). */
    private class Mesh(val x: FloatArray, val y: FloatArray, val z: FloatArray, val u: FloatArray) {
        val count: Int get() = x.size
    }

    companion object {
        /** Röhre: Radius, Anfang hinter der Kamera, Ende (Sichtlänge 220), Ringe, Segmente, Verdichtung zur Kamera. */
        const val RADIUS = 9f
        const val Z_NEAR = 10f
        const val Z_FAR = -210f
        const val RINGS = 64
        const val SEGMENTS = 64
        const val DEPTH_CURVE = 1.5

        /** Vierecke der Röhre plus Deckel (Fluchtpunkt). */
        const val QUADS = (RINGS - 1) * SEGMENTS + SEGMENTS

        /** Halbe Figurenhöhe: Platzangaben (PLAN 6.7) meinen die Körpermitte. */
        const val HALF_HEIGHT = 0.9f

        /** Lichtring: halbe Kantenlänge, Abstand über den Füßen, Stärke an freien Plätzen. */
        const val RING_SIZE = 0.8f
        const val RING_LIFT = 0.02f
        const val FREE_RING = 0.22f

        /** Schleier: halbe Breite, Abstand vor der Figur. */
        const val VEIL_HALF_WIDTH = 0.75f
        const val VEIL_FRONT = 0.45f

        private const val BUFFER_BYTES = QUADS * 4 * 28 + 65536

        private val MESH: Mesh by lazy { buildMesh() }

        private fun ringZ(i: Int): Float {
            val t = i / (RINGS - 1).toDouble()
            return (Z_NEAR + (Z_FAR - Z_NEAR) * t.pow(DEPTH_CURVE)).toFloat()
        }

        /** Gleiche Geometrie wie `glcheck.py` (`tunnel_quads`). */
        private fun buildMesh(): Mesh {
            val n = QUADS * 4
            val x = FloatArray(n)
            val y = FloatArray(n)
            val z = FloatArray(n)
            val u = FloatArray(n)
            var k = 0
            fun put(uu: Float, zz: Float, rim: Boolean) {
                val a = uu * 2.0 * PI
                x[k] = if (rim) (RADIUS * cos(a)).toFloat() else 0f
                y[k] = if (rim) (RADIUS * sin(a)).toFloat() else 0f
                z[k] = zz
                u[k] = uu
                k++
            }
            for (i in 0 until RINGS - 1) {
                val z0 = ringZ(i)
                val z1 = ringZ(i + 1)
                for (j in 0 until SEGMENTS) {
                    val u0 = j / SEGMENTS.toFloat()
                    val u1 = (j + 1) / SEGMENTS.toFloat()
                    put(u0, z0, true)
                    put(u1, z0, true)
                    put(u1, z1, true)
                    put(u0, z1, true)
                }
            }
            for (j in 0 until SEGMENTS) {
                val u0 = j / SEGMENTS.toFloat()
                val u1 = (j + 1) / SEGMENTS.toFloat()
                val mid = (u0 + u1) / 2f
                put(mid, Z_FAR, false)
                put(mid, Z_FAR, false)
                put(u1, Z_FAR, true)
                put(u0, Z_FAR, true)
            }
            return Mesh(x, y, z, u)
        }
    }
}
