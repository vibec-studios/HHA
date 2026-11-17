package dev.henny.hha.client.vfx.fx

import dev.henny.hha.client.vfx.VfxManager
import net.minecraft.client.render.Camera
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.util.math.Vec3d
import org.joml.Vector3f

/**
 * Rahmen eines Bildes für die neuen Effekte (PLAN 6.4): Kameralage, Zwischenbild-Anteil, die Kamera-Achsen in
 * Weltrichtung, das Zeichenziel und die laufende Phase. Alle Zeichner in [FxDraw] schreiben kamerarelativ in
 * [consumers]; der `VfxManager` leert den Puffer am Ende jeder Phase (`draw()`).
 */
class FxFrame(
    /** Kamera in Weltkoordinaten. */
    val camPos: Vec3d,
    /** Anteil des laufenden Ticks (0..1). */
    val tickDelta: Float,
    /** Kamera-Achsen in Weltrichtung (Einheitsvektoren). */
    val right: Vector3f,
    val up: Vector3f,
    val forward: Vector3f,
    /** Zeichenziel: der eigene Puffer des `VfxManager` (2 MiB), geleert nach jeder Phase. */
    val consumers: VertexConsumerProvider.Immediate = VfxManager.fxBuffers,
    /** Laufende Phase; [FxDraw.block] und [FxDraw.part] zeichnen nur in [Phase.SOLID]. */
    val phase: Phase = Phase.ENERGY,
) {
    enum class Phase { SOLID, ENERGY, OVER }

    /** Puffer einer Ebene im Zeichenziel dieses Bildes. */
    fun buffer(layer: RenderLayer): VertexConsumer = consumers.getBuffer(layer)

    /** Weltpunkt → kamerarelativ (so erwarten es die Pipelines). */
    fun rel(p: Vec3d): Vector3f = Vector3f((p.x - camPos.x).toFloat(), (p.y - camPos.y).toFloat(), (p.z - camPos.z).toFloat())

    /** Derselbe Rahmen in einer anderen Phase. */
    fun inPhase(next: Phase): FxFrame = if (next == phase) this else FxFrame(camPos, tickDelta, right, up, forward, consumers, next)

    /** 0 direkt vor der Kamera, 1 ab [NEAR_FULL] Blöcken: weiches Ausblenden naher Effekte (stetig, ohne Stufen). */
    fun nearFade(p: Vec3d): Float {
        val k = ((p.distanceTo(camPos) - NEAR_ZERO) / (NEAR_FULL - NEAR_ZERO)).coerceIn(0.0, 1.0).toFloat()
        return k * k * (3f - 2f * k)
    }

    companion object {
        /** Bis hierhin (Blöcke) ist ein Effekt unsichtbar … */
        const val NEAR_ZERO = 0.4
        /** … ab hier voll sichtbar. (Der Shader blendet zusätzlich je Fragment von 0,35 bis 2,2 aus, wie `vfx.fsh`.) */
        const val NEAR_FULL = 2.0

        /** Rahmen aus der Spielkamera (Achsen wie `VfxDraw`). */
        fun of(
            camera: Camera, tickDelta: Float, consumers: VertexConsumerProvider.Immediate = VfxManager.fxBuffers,
            phase: Phase = Phase.ENERGY,
        ): FxFrame {
            val d = camera.diagonalPlane
            val v = camera.verticalPlane
            val h = camera.horizontalPlane
            return FxFrame(
                camera.cameraPos, tickDelta,
                Vector3f(-d.x(), -d.y(), -d.z()), Vector3f(v.x(), v.y(), v.z()), Vector3f(h.x(), h.y(), h.z()),
                consumers, phase,
            )
        }
    }
}
