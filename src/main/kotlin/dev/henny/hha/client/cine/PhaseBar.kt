package dev.henny.hha.client.cine

import dev.henny.hha.client.gui.Ui
import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.lerpColor
import dev.henny.hha.logic.Vfx
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.util.math.MathHelper

/**
 * Die Lebensleiste der großen Bosse: ein breiter Balken, geteilt in so viele Abschnitte wie der Kampf Phasen hat. Erledigte
 * Phasen sind dunkel, die laufende leert sich, die kommenden warten voll. Ein heller Nachlauf zeigt, wie viel der letzte
 * Treffer genommen hat. Der Server schickt nur Zahlen (`Cine.phaseBar`), gezeichnet wird hier.
 *
 * Farbthemen: 0 Grün, 1 Gold, ab [STYLE_THEMES] die Gefahrenpalette von `Vfx.Style.entries[theme − 10]`
 * (z. B. `10 + Vfx.Style.THUNDER.ordinal` für Zeus).
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

    /** Unterkante der Leiste samt Ziffern in GUI-Pixeln, solange sie sichtbar ist, sonst 0. Das Ultra-HUD weicht darunter aus. */
    fun bottom(): Int = if (appear > 0.001f) 50 else 0

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
        // Website-Bossleiste (.bossbar): Name als Etikett, darunter Segmente mit 4 px Lücke, schlicht und eckig.
        // Ein dunkler Grund hinter jedem Segment hält die Leiste vor hellem Himmel lesbar.
        val barWidth = (width * 0.5f).toInt().coerceIn(220, 420)
        val gap = 4
        val segWidth = (barWidth - gap * (segments - 1)) / segments
        val x0 = (width - barWidth) / 2
        val y0 = 18 - ((1f - a) * 8f).toInt()
        val height = 6
        val alpha = (a * 255f).toInt().coerceIn(0, 255)
        fun argb(rgb: Int, mult: Float = 1f) = ((alpha * mult).toInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

        val (fill, fillLight, glowRgb) = colors(theme)

        // Dunkle Platte hinter Name, Balken, Phase und Ziffern: Der Olymp ist weißer Marmor unter hellem Himmel.
        val sub = phase
        val plateW = maxOf(barWidth, font.getWidth(sub)) + 16
        Ui.frame(context, (width - plateW) / 2, y0 - 15, (width + plateW) / 2, y0 + height + 26, argb(0xFFFFFF, 0.09f))
        context.fill((width - plateW) / 2 + 1, y0 - 14, (width + plateW) / 2 - 1, y0 + height + 25, argb(0x000000, 0.5f))
        // Name über dem Balken in der fetten Pixelschrift, Phase darunter in der Spielschrift (lesbar im Kampf).
        val title = Ui.displayText(name.string)
        context.drawText(font, title, (width - font.getWidth(title)) / 2, y0 - 11, argb(0xF4F4F5), true)
        context.drawText(font, sub, (width - font.getWidth(sub)) / 2, y0 + height + 5, argb(glowRgb, 0.95f), true)

        for (i in 0 until segments) {
            val sx = x0 + i * (segWidth + gap)
            val number = i + 1
            context.fill(sx - 1, y0 - 1, sx + segWidth + 1, y0 + height + 1, argb(0x000000, 0.7f))
            context.fill(sx, y0, sx + segWidth, y0 + height, argb(0xFFFFFF, 0.12f))
            val level = when {
                number < current -> 0f
                number > current -> 1f
                else -> shown
            }
            val trailLevel = if (number == current) trail else level
            if (trailLevel > level) {
                context.fill(sx + (segWidth * level).toInt(), y0, sx + (segWidth * trailLevel).toInt(), y0 + height, argb(0xFFFFFF, 0.7f))
            }
            if (level > 0f) {
                val end = sx + (segWidth * level).toInt()
                val dim = if (number > current) 0.5f else 1f
                context.fill(sx, y0, end, y0 + height, argb(fill, dim))
                context.fill(sx, y0, end, y0 + 1, argb(fillLight, dim))
            }
            if (number == current && flash > 0f) {
                context.fill(sx, y0, sx + segWidth, y0 + height, argb(0xFFFFFF, flash * 0.8f))
            }
            // Römische Ziffer der Phase unter dem Abschnitt, als Etikett (Website: .hb-num)
            val label = Ui.labelText(ROMAN.getOrElse(i) { "$number" })
            val lw = font.getWidth(label)
            val labelColor = if (number == current) glowRgb else 0x6B6B75
            context.drawText(font, label, sx + segWidth / 2 - lw / 2, y0 + height + 17, argb(labelColor, if (number < current) 0.5f else 0.9f), true)
        }
    }

    private val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII")

    /** Ab hier zählen Themen als `Vfx.Style`-Ordinal + 10. */
    const val STYLE_THEMES = 10

    /** Füllung, helle Oberkante, Schrift- und Glanzfarbe (je RGB ohne Alpha). */
    private fun colors(theme: Int): Triple<Int, Int, Int> {
        val style = if (theme >= STYLE_THEMES) Vfx.Style.entries.getOrNull(theme - STYLE_THEMES) else null
        if (style != null) {
            // Gefahrenpalette: Auch dunkle Stile (SHADOW) haben dort eine leuchtende Variante.
            val pal = Palette.danger(style)
            val fill = lerpColor(pal.main, pal.edge, 0.25f)
            return Triple(fill and 0xFFFFFF, lerpColor(pal.main, pal.core, 0.55f) and 0xFFFFFF, lerpColor(pal.main, pal.core, 0.3f) and 0xFFFFFF)
        }
        return when (theme) {
            1 -> Triple(0xE09A2A, 0xFFE08A, 0xFFB43C)
            else -> Triple(0x2FCB52, 0xA8FFB4, 0x4CFF5A)
        }
    }
}
