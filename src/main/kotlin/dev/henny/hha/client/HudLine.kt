package dev.henny.hha.client

import dev.henny.hha.client.cine.CinePlayer
import dev.henny.hha.client.gui.Ui
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import net.minecraft.text.TranslatableTextContent
import net.minecraft.util.Util

/**
 * Statuszeile über der Schnellleiste (PLAN 3.5, REF T9, Vorbild Blood `HudLineOverlay`): ein Hinweis mit Tastennamen
 * (Argument `@key:<id>` → `Text.keybind`), 150 ms ein, 500 ms aus, im Website-Stil (Platte, 1-px-Linie, `hha:label`).
 * Eigene Zeile bei `height − 98`, weil die Vanilla-Aktionsleiste hinter HHAs Fähigkeits-Symbolen liegt. Kommt über
 * `Cine.OP_HUD_LINE` (durch `CinePlayer`) oder direkt vom Client. Zeichnet nicht in Szenen.
 */
object HudLine {

    /** Abstand der Zeile vom unteren Bildrand (HHAs Symbole beginnen bei `height − 84`). */
    const val OFFSET_FROM_BOTTOM = 98

    const val FADE_IN_MS = 150L
    const val FADE_OUT_MS = 500L

    /** Innenabstand der Platte links/rechts und oben/unten (GUI-Pixel). */
    private const val PAD_X = 8
    private const val PAD_Y = 4

    /** Höhe der Etikett-Zeile (`hha:label`: Versalien bei y + 2 … y + 6). */
    private const val LINE_H = 9

    /** Deckkraft der Platte bei voller Sichtbarkeit. */
    private const val PLATE_ALPHA = 0.86f

    /** Präfix eines Arguments, das zur Taste der Tastenbelegung `<id>` wird. */
    const val KEY_PREFIX = "@key:"

    private var text: Text? = null
    private var color = 0
    private var shownAt = 0L
    private var until = 0L

    /** Zeigt [text] für [ticks] in [color] (0xRRGGBB, 0 = Standard). Ein neuer Text ersetzt den alten. */
    fun show(text: Text, ticks: Int = 60, color: Int = 0) {
        val now = Util.getMeasuringTimeMs()
        // Läuft schon eine Zeile, blendet die neue nicht erneut ein (kein Flackern beim Ersetzen).
        if (now >= until) shownAt = now
        this.text = withKeys(text)
        this.color = color and 0xFFFFFF
        until = now + ticks.coerceAtLeast(1) * 50L
    }

    /** Ersetzt in einem übersetzten Text die Argumente `@key:<id>` durch den Namen der belegten Taste. */
    private fun withKeys(text: Text): Text {
        val content = text.content as? TranslatableTextContent ?: return text
        if (content.args.none { it is String && it.startsWith(KEY_PREFIX) }) return text
        val args = content.args.map { arg ->
            if (arg is String && arg.startsWith(KEY_PREFIX)) Text.keybind(arg.removePrefix(KEY_PREFIX)) else arg
        }.toTypedArray()
        return Text.translatableWithFallback(content.key, content.fallback, *args).setStyle(text.style)
    }

    /** Je Client-Tick: abgelaufene Zeile vergessen. */
    fun tick() {
        if (text != null && Util.getMeasuringTimeMs() >= until) text = null
    }

    /** Aus dem HUD (`HudRenderCallback`). */
    fun render(ctx: DrawContext) {
        val t = text ?: return
        val client = MinecraftClient.getInstance()
        if (client.player == null || client.options.hudHidden || CinePlayer.active) return
        val now = Util.getMeasuringTimeMs()
        val left = until - now
        if (left <= 0) return
        val alpha = (minOf(1f, (now - shownAt) / FADE_IN_MS.toFloat()) * minOf(1f, left / FADE_OUT_MS.toFloat())).coerceIn(0f, 1f)
        if (alpha <= 0.02f) return

        // Etikett-Schrift in Versalien; `string` löst Tastennamen und Übersetzung auf.
        val label = Ui.upper(t.string)
        val tr = client.textRenderer
        val w = Ui.labelWidth(tr, label)
        val cx = ctx.scaledWindowWidth / 2
        val y = ctx.scaledWindowHeight - OFFSET_FROM_BOTTOM
        val x1 = cx - w / 2 - PAD_X
        val x2 = cx + (w + 1) / 2 + PAD_X
        val y1 = y - PAD_Y
        val y2 = y + LINE_H + PAD_Y
        val accent = if (color == 0) Ui.TEXT else (0xFF000000.toInt() or color)

        ctx.fill(x1, y1, x2, y2, Ui.alpha(Ui.SURFACE, PLATE_ALPHA * alpha))
        Ui.frame(ctx, x1, y1, x2, y2, Ui.alpha(Ui.LINE_2, alpha))
        // Akzentlinie unten (1 px), über die mittlere Hälfte der Platte
        val quarter = (x2 - x1) / 4
        Ui.hline(ctx, x1 + quarter, x2 - quarter, y2 - 1, Ui.alpha(accent, 0.9f * alpha))
        Ui.label(ctx, tr, label, cx - w / 2, y + 1, Ui.alpha(if (color == 0) Ui.TEXT else accent, alpha))
    }

    /** Verbindungsende. */
    fun reset() {
        text = null
        until = 0L
    }
}
