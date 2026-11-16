package dev.henny.hha.client.cine

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.math.MathHelper

/**
 * Die Lebensleiste der großen Bosse: ein breiter Balken, geteilt in so viele Abschnitte wie der Kampf Phasen hat. Erledigte
 * Phasen sind dunkel, die laufende leert sich, die kommenden warten voll. Ein heller Nachlauf zeigt, wie viel der letzte
 * Treffer genommen hat. Der Server schickt nur Zahlen (`Cine.phaseBar`), gezeichnet wird hier.
 */
object PhaseBar {

    private var visible = false
    private var segments = 3
    private var current = 1
    private var target = 1f
    private var shown = 1f
    private var trail = 1f
    private var trailHold = 0
    private var theme = 0
    private var name: Text = Text.empty()
    private var phase: Text = Text.empty()
    private var appear = 0f
    private var flash = 0f

    fun handle(numbers: List<Double>, texts: List<String>) {
        val show = numbers.getOrElse(0) { 0.0 } > 0.5
        if (!show) {
            visible = false
            return
        }
        val nextSegments = numbers[1].toInt().coerceIn(1, 8)
        val nextCurrent = numbers[2].toInt().coerceIn(1, nextSegments)
        val fraction = numbers[3].toFloat().coerceIn(0f, 1f)
        if (!visible || nextCurrent != current || nextSegments != segments) {
            // Neue Phase: Der Balken füllt sich, der Nachlauf springt mit.
            if (visible && nextCurrent != current) flash = 1f
            shown = if (visible) fraction else 0f
            trail = fraction
        }
        if (fraction < target - 0.001f) trailHold = 14
        visible = true
        segments = nextSegments
        current = nextCurrent
        target = fraction
        theme = numbers.getOrElse(4) { 0.0 }.toInt()
        name = Text.translatable(texts.getOrElse(0) { "" })
        phase = Text.translatable(texts.getOrElse(1) { "" })
    }

    fun reset() {
        visible = false
        appear = 0f
    }

    fun tick() {
        appear = MathHelper.clamp(appear + if (visible) 0.06f else -0.1f, 0f, 1f)
        shown += (target - shown) * 0.35f
        if (trailHold > 0) trailHold-- else trail += (shown - trail) * 0.12f
        if (trail < shown) trail = shown
        flash = (flash - 0.04f).coerceAtLeast(0f)
    }

    fun render(context: DrawContext, tickDelta: Float) {
        if (appear <= 0.001f) return
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val width = client.window.scaledWidth
        val a = appear * appear * (3f - 2f * appear)
        val barWidth = (width * 0.5f).toInt().coerceIn(220, 420)
        val gap = 4
        val segWidth = (barWidth - gap * (segments - 1)) / segments
        val x0 = (width - barWidth) / 2
        val y0 = 18 - ((1f - a) * 12f).toInt()
        val height = 7
        val alpha = (a * 255f).toInt().coerceIn(0, 255)
        fun argb(rgb: Int, mult: Float = 1f) = ((alpha * mult).toInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

        val (fill, fillLight, glowRgb) = when (theme) {
            1 -> Triple(0xE09A2A, 0xFFE08A, 0xFFB43C)
            else -> Triple(0x2FCB52, 0xA8FFB4, 0x4CFF5A)
        }

        // Name und Phase über dem Balken.
        val title = name
        val titleWidth = font.getWidth(title)
        context.drawTextWithShadow(font, title, (width - titleWidth) / 2, y0 - 11, argb(0xF0EAD8))
        val sub = phase
        val subWidth = font.getWidth(sub)
        context.drawTextWithShadow(font, sub, (width - subWidth) / 2, y0 + height + 4, argb(glowRgb, 0.9f))

        for (i in 0 until segments) {
            val sx = x0 + i * (segWidth + gap)
            val number = i + 1
            // Rahmen und Grund.
            context.fill(sx - 1, y0 - 1, sx + segWidth + 1, y0 + height + 1, argb(0x000000, 0.8f))
            context.fill(sx, y0, sx + segWidth, y0 + height, argb(0x1A1410, 0.85f))
            val level = when {
                number < current -> 0f
                number > current -> 1f
                else -> shown
            }
            val trailLevel = if (number == current) trail else level
            if (trailLevel > level) {
                context.fill(sx + (segWidth * level).toInt(), y0, sx + (segWidth * trailLevel).toInt(), y0 + height, argb(0xFFF4E0, 0.75f))
            }
            if (level > 0f) {
                val end = sx + (segWidth * level).toInt()
                val dim = if (number > current) 0.55f else 1f
                context.fill(sx, y0, end, y0 + height, argb(fill, dim))
                context.fill(sx, y0, end, y0 + 2, argb(fillLight, dim))
                context.fill(sx, y0 + height - 1, end, y0 + height, argb(0x000000, 0.35f))
            }
            if (number == current && flash > 0f) {
                context.fill(sx, y0, sx + segWidth, y0 + height, argb(0xFFFFFF, flash * 0.8f))
            }
            // Römische Ziffer der Phase unter dem Abschnitt.
            val label = Text.literal(ROMAN.getOrElse(i) { "$number" })
            val lw = font.getWidth(label)
            val labelColor = if (number == current) glowRgb else 0x8A8070
            context.drawTextWithShadow(font, label, sx + segWidth / 2 - lw / 2, y0 + height + 15, argb(labelColor, if (number < current) 0.4f else 0.85f))
        }
    }

    private val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII")
}
