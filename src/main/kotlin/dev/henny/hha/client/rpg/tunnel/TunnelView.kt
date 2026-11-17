package dev.henny.hha.client.rpg.tunnel

import dev.henny.hha.api.rpg.HhaRpg
import dev.henny.hha.client.gui.Ui
import dev.henny.hha.client.rpg.RpgGfx
import net.minecraft.client.gui.DrawContext
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Ein Eingang für alle Orte der Tunnelbühne (Lobby, Übergangsbildschirm, ersetzte Ladebildschirme, Überlagerung in der
 * Welt): rückt die Bewegung fort, setzt das Sonderelement ab und liefert eine [Projection] für Namensschilder. Ist der
 * Shader ungültig ([TunnelPipelines.failed]), zeichnet [draw] den 2D-Rückfall (PLAN 5): `RpgGfx`-Hintergrund und jede
 * Figur einzeln per `ctx.addEntity` (weiter mit Kostüm und Pose).
 *
 * Ebenen: [draw] legt den Grund (falls [opaqueBase]), die Bühne und die Figuren je in eine eigene Ebene und öffnet
 * danach eine neue für die Oberfläche des Aufrufers.
 */
object TunnelView {

    /** Rechnet Szenenpunkte in GUI-Koordinaten um (gleiche Kamera wie das Bild). */
    class Projection(private val view: Matrix4f, private val width: Int, private val height: Int) {
        fun project(x: Float, y: Float, z: Float): FloatArray? = TunnelCamera.project(view, 0, 0, width, height, x, y, z)

        /** Fußpunkt und Kopfpunkt einer Figur auf Platz [slot] (+ Vorflug [forward]). */
        fun feet(slot: FloatArray, forward: Float = 0f) = project(slot[0], slot[1] - TunnelRenderer.HALF_HEIGHT, slot[2] - forward)
        fun head(slot: FloatArray, forward: Float = 0f) = project(slot[0], slot[1] + TunnelRenderer.HALF_HEIGHT, slot[2] - forward)
    }

    /** Was ein Bild zeigt. */
    class Frame(
        val path: String,
        val speed: Float,
        val figures: List<TunnelFigures.Spec>,
        val freeSlots: List<Int> = emptyList(),
        val exitOpen: Float = 0f,
        val flash: Float = 0f,
        val lookX: Float = 0f,
        val lookY: Float = 0f,
        /** Schwarzer Grund unter der Bühne (nicht beim Austritt in die Welt, dort muss das Loch durchsichtig bleiben). */
        val opaqueBase: Boolean = true,
    )

    /** Wegvariante des Shaders. */
    fun variant(path: String): TunnelPipelines.Variant = when (path) {
        "heaven" -> TunnelPipelines.Variant.HEAVEN
        "hell" -> TunnelPipelines.Variant.HELL
        else -> if (HhaRpg.path(path) == null) TunnelPipelines.Variant.HEAVEN else TunnelPipelines.Variant.TINT
    }

    /** Wegfarbe 0xRRGGBB. */
    fun color(path: String): Int = when (path) {
        "heaven" -> 0xFFD75E
        "hell" -> 0x3FD8FF
        else -> (HhaRpg.path(path)?.color ?: Ui.GOLD) and 0xFFFFFF
    }

    /** Läuft gerade die echte 3D-Bühne (sonst der 2D-Rückfall)? Prüft die Pipelines beim ersten Aufruf. */
    fun threeD(): Boolean = !TunnelPipelines.failed && TunnelPipelines.ready()

    fun draw(ctx: DrawContext, width: Int, height: Int, frame: Frame): Projection {
        TunnelMotion.advance(frame.speed)
        val time = TunnelMotion.time
        val view = TunnelCamera.view(time, frame.lookX, frame.lookY)
        val projection = Projection(view, width, height)
        if (frame.opaqueBase) {
            ctx.fill(0, 0, width, height, 0xFF000000.toInt())
            ctx.createNewRootLayer()
        }
        if (threeD()) {
            val figures = frame.figures.map { TunnelFigures.build(it, time) }
            val free = frame.freeSlots.map { TunnelFigures.slot(it) }
            ctx.state.addSpecialElement(
                TunnelScene(
                    0, 0, width, height, time, TunnelMotion.scroll, TunnelMotion.rotation, TunnelMotion.speed,
                    variant(frame.path), color(frame.path), frame.exitOpen, frame.flash, figures, free, frame.lookX, frame.lookY,
                ),
            )
        } else {
            fallback(ctx, width, height, frame, time, projection)
        }
        ctx.createNewRootLayer()
        return projection
    }

    // ---------------------------------------------------------------------------------------------
    // 2D-Rückfall (PLAN 5): RpgGfx-Hintergrund, Figuren einzeln per ctx.addEntity
    // ---------------------------------------------------------------------------------------------

    private fun fallback(ctx: DrawContext, width: Int, height: Int, frame: Frame, time: Float, projection: Projection) {
        val accent = 0xFF000000.toInt() or color(frame.path)
        val open = frame.exitOpen.coerceIn(0f, 1f)
        val a = 1f - open
        val cx = width / 2
        val cy = (height * 0.42f).toInt()
        val diag = kotlin.math.hypot(width.toDouble(), height.toDouble()).toFloat()
        if (open > 0f) {
            // Austritt: Irisblende öffnet sich über der Welt
            RpgGfx.iris(ctx, width, height, cx, cy, (diag * 0.5f / 0.6f * RpgGfx.easeInOut(open)).toInt())
        }
        val travel = 1f + frame.speed
        RpgGfx.texture(ctx, RpgGfx.GLOW, cx, cy, (diag * 0.9f).toInt(), (diag * 0.9f).toInt(), 0f, Ui.alpha(accent, 0.24f * a))
        val rays = (max(width, height) * 1.2f).toInt()
        RpgGfx.texture(ctx, RpgGfx.RAYS, cx, cy, rays, rays, time * 0.02f * travel, Ui.alpha(accent, 0.28f * a))
        RpgGfx.texture(ctx, RpgGfx.RAYS, cx, cy, (rays * 0.7f).toInt(), (rays * 0.7f).toInt(), -time * 0.015f * travel + 0.11f, Ui.alpha(Ui.brighten(accent, 0.3f), 0.16f * a))
        RpgGfx.texture(ctx, RpgGfx.GLOW, cx, cy, min(width, height) / 3, min(width, height) / 3, 0f, Ui.alpha(0xFFFFFFFF.toInt(), 0.45f * a))
        // Lichtschlieren nach außen, beim Rasen länger
        val m = ctx.matrices
        for (i in 0 until 48) {
            val angle = RpgGfx.hash(i * 7 + 1) * (2f * PI.toFloat())
            val p = ((time * (0.08f + RpgGfx.hash(i * 7 + 2) * 0.12f) * travel + RpgGfx.hash(i * 7 + 3)) % 1f)
            val r = 12f + p * p * diag * 0.6f
            val len = 2f + p * p * (8f + 10f * frame.speed)
            val la = (p * (1f - p) * 4f).coerceIn(0f, 1f) * 0.5f * a
            if (la < 0.02f) continue
            m.pushMatrix()
            m.translate(cx.toFloat(), cy.toFloat())
            m.rotate(angle)
            ctx.fill(r.toInt(), 0, (r + len).toInt(), 1, Ui.alpha(Ui.brighten(accent, 0.4f), la))
            m.popMatrix()
        }
        // Lichtflecken unter den Plätzen
        for (spec in frame.figures) footSpot(ctx, projection, TunnelFigures.slot(spec.slot), spec.ring, accent, a)
        for (slot in frame.freeSlots) footSpot(ctx, projection, TunnelFigures.slot(slot), 0.22f, accent, a)
        if (frame.flash > 0.01f) ctx.fill(0, 0, width, height, Ui.alpha(0xFFFFF2D6.toInt(), frame.flash))
        ctx.createNewRootLayer()
        // Figuren einzeln, hinten zuerst
        for (spec in frame.figures.sortedBy { TunnelFigures.slot(it.slot)[2] - it.forward }) {
            val figure = TunnelFigures.build(spec, time, gui = true)
            val head = projection.project(figure.x, figure.y + TunnelRenderer.HALF_HEIGHT, figure.z) ?: continue
            val feet = projection.project(figure.x, figure.y - TunnelRenderer.HALF_HEIGHT, figure.z) ?: continue
            val perBlock = (feet[1] - head[1]) / (2f * TunnelRenderer.HALF_HEIGHT)
            if (perBlock < 2f) continue
            val centerX = (head[0] + feet[0]) / 2f
            val centerY = (head[1] + feet[1]) / 2f
            val halfW = perBlock * 1.1f
            val halfH = perBlock * 1.15f
            val state = figure.state
            val camera = Quaternionf()
            val rotation = Quaternionf().rotateZ(PI.toFloat())
            ctx.addEntity(
                state, perBlock, Vector3f(0f, state.height / 2f + 0.0625f, 0f), rotation, camera,
                (centerX - halfW).toInt(), (centerY - halfH).toInt(), (centerX + halfW).toInt(), (centerY + halfH).toInt(),
            )
        }
    }

    private fun footSpot(ctx: DrawContext, projection: Projection, slot: FloatArray, strength: Float, accent: Int, a: Float) {
        val feet = projection.feet(slot) ?: return
        val head = projection.head(slot) ?: return
        val w = ((feet[1] - head[1]) * 0.9f).toInt().coerceAtLeast(8)
        RpgGfx.texture(ctx, RpgGfx.SPOT, feet[0].toInt(), feet[1].toInt(), w, max(4, w / 5), 0f, Ui.alpha(accent, (0.25f + 0.5f * strength) * a))
        if (strength > 0.6f) {
            val pulse = 0.5f + 0.5f * sin(TunnelMotion.time * 2.4f + slot[0])
            RpgGfx.texture(ctx, RpgGfx.SPOT, feet[0].toInt(), feet[1].toInt(), (w * (0.6f + 0.1f * pulse)).toInt(), max(3, w / 7), 0f, Ui.alpha(accent, 0.5f * a))
        }
    }
}
