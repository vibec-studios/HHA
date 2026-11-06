package dev.henny.hha.client.vfx

import dev.henny.hha.client.HhaClientConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.util.math.Vec3d
import kotlin.math.sin

/** Bildschirmweite Effekte: Kameraschütteln und Lichtblitze. */
object ScreenFx {

    private var shakeStrength = 0f
    private var shakeTicks = 0
    private var shakeTotal = 1
    private var shakeTime = 0

    private var flashColor = 0
    private var flashStrength = 0f
    private var flashTicks = 0
    private var flashTotal = 1

    fun shake(strength: Float, ticks: Int) {
        // Stärkeres Schütteln verdrängt schwächeres, sonst läuft das aktuelle weiter.
        if (strength >= currentShake()) {
            shakeStrength = strength
            shakeTicks = ticks
            shakeTotal = ticks.coerceAtLeast(1)
        }
    }

    private fun currentShake(): Float = if (shakeTicks > 0) shakeStrength * shakeTicks / shakeTotal else 0f

    fun flash(color: Int, strength: Float, ticks: Int) {
        flashColor = color
        flashStrength = strength
        flashTicks = ticks
        flashTotal = ticks.coerceAtLeast(1)
    }

    fun tick() {
        shakeTime++
        if (shakeTicks > 0) shakeTicks--
        if (flashTicks > 0) flashTicks--
    }

    /** Versatz für die Kamera; wird im Camera-Mixin addiert. */
    fun shakeOffset(tickDelta: Float): Vec3d {
        val current = currentShake()
        if (current <= 0f || !HhaClientConfig.enabled("screen_shake")) return Vec3d.ZERO
        val time = shakeTime + tickDelta
        return Vec3d(
            (sin(time * 2.3) + sin(time * 5.1) * 0.5) * current * 0.08,
            (sin(time * 3.1 + 1.0) + sin(time * 6.7) * 0.5) * current * 0.08,
            (sin(time * 2.7 + 2.0) + sin(time * 4.3) * 0.5) * current * 0.08,
        )
    }

    fun renderOverlay(context: DrawContext) {
        if (flashTicks <= 0) return
        val client = MinecraftClient.getInstance()
        val alpha = flashStrength * flashTicks / flashTotal
        val width = client.window.scaledWidth
        val height = client.window.scaledHeight
        context.fill(0, 0, width, height, withAlpha(flashColor or 0xFF000000.toInt(), alpha))
    }

    fun reset() {
        shakeTicks = 0
        flashTicks = 0
    }
}
