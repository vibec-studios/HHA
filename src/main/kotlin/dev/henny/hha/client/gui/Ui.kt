package dev.henny.hha.client.gui

import dev.henny.hha.Hha
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.StyleSpriteSource
import net.minecraft.text.Text
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Farben, Formen, Schriften und Easing im Stil der Studio-Website (vibec-studios.github.io): tiefes Schwarz, feine
 * 1-px-Linien, eckige Flächen, Pixel-Überschriften (Silkscreen), Etiketten in weiter Laufweite, Akzent je Mod.
 * Alles wird selbst gezeichnet, keine Vanilla-Widgets. Die HTML-Attrappe `tmp/ui_redesign/mock/ui.js` nutzt dieselben
 * Werte.
 */
object Ui {

    // ------------------------------------------------------------------ Palette (css/style.css :root)

    const val SURFACE = 0xFF0B0B0E.toInt()
    const val SURFACE_2 = 0xFF121217.toInt()
    const val LINE = 0x17FFFFFF            // rgba(255,255,255,.09)
    const val LINE_2 = 0x29FFFFFF          // rgba(255,255,255,.16)
    const val TEXT = 0xFFF4F4F5.toInt()
    const val MUTED = 0xFFA1A1AA.toInt()
    const val DIM = 0xFF6B6B75.toInt()
    const val ON_ACCENT = 0xFF0A0A0C.toInt()

    /** HHA-Akzent und Heaven. Im Spiel heißt Gold „sicher/offen“, deshalb nie für Gefahr verwenden. */
    const val GOLD = 0xFFFFD75E.toInt()

    /** Zweiter HHA-Akzent und Hell (Seelenfeuer). */
    const val SOUL = 0xFF3FD8FF.toInt()

    /** Studio-Violett und -Türkis für Allgemeines, Bosse und Addons. */
    const val BRAND = 0xFF8B6CFF.toInt()
    const val TEAL = 0xFF3FE0C5.toInt()
    const val GOOD = 0xFF6BE38C.toInt()
    const val BAD = 0xFFFF6B5E.toInt()

    // ------------------------------------------------------------------ Farbe

    fun alpha(color: Int, a: Float): Int {
        val base = (color ushr 24) and 0xFF
        val na = (base * a.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (na shl 24) or (color and 0xFFFFFF)
    }

    fun withAlpha(color: Int, a: Int): Int = (a.coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)

    fun lerp(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = ((((a ushr shift) and 0xFF) * (1 - k)) + (((b ushr shift) and 0xFF) * k)).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun brighten(color: Int, k: Float): Int = lerp(color, (color and 0xFF000000.toInt()) or 0xFFFFFF, k)

    // ------------------------------------------------------------------ Easing

    fun easeOutCubic(t: Float): Float = 1f - (1f - t.coerceIn(0f, 1f)).pow(3)

    /** Weich nachlaufender Wert je ID (pro Bild auf das Ziel zu). */
    class Springs {
        private val values = HashMap<String, Float>()
        var dt = 0f
        fun get(id: String, target: Float, speed: Float = 14f, initial: Float = target): Float {
            val cur = values[id] ?: initial
            val next = cur + (target - cur) * (1f - exp(-dt * speed))
            values[id] = next
            return next
        }
        fun set(id: String, value: Float) { values[id] = value }
    }

    // ------------------------------------------------------------------ Formen (eckig wie die Website)

    fun hline(ctx: DrawContext, x1: Int, x2: Int, y: Int, color: Int) = ctx.fill(x1, y, x2, y + 1, color)

    fun vline(ctx: DrawContext, x: Int, y1: Int, y2: Int, color: Int) = ctx.fill(x, y1, x + 1, y2, color)

    /** 1-px-Rahmen. */
    fun frame(ctx: DrawContext, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        if (x2 - x1 < 2 || y2 - y1 < 2 || (color ushr 24) == 0) return
        ctx.fill(x1, y1, x2, y1 + 1, color)
        ctx.fill(x1, y2 - 1, x2, y2, color)
        ctx.fill(x1, y1 + 1, x1 + 1, y2 - 1, color)
        ctx.fill(x2 - 1, y1 + 1, x2, y2 - 1, color)
    }

    /** Elliptischer Lichtfleck (Sockel unter dem 3D-Modell). */
    fun ellipse(ctx: DrawContext, cx: Int, cy: Int, rx: Int, ry: Int, color: Int) {
        for (j in -ry until ry) {
            val t = (j + 0.5f) / ry
            val w = (rx * sqrt(max(0f, 1f - t * t))).toInt()
            ctx.fill(cx - w, cy + j, cx + w, cy + j + 1, color)
        }
    }

    /** Pixel-Kreuz 7 × 7 (Schließen, Entfernen). */
    fun cross(ctx: DrawContext, cx: Int, cy: Int, color: Int) {
        for (i in -3..3) {
            ctx.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, color)
            ctx.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, color)
        }
    }

    /** HHA-Würfel im Stil des Studio-Logos (Pixel-Block): Deckfläche Gold (Heaven), Seiten Blau (Hell). 12 × 11. */
    private val LOGO = listOf(
        "....TTTT....",
        "..TTTTTTTT..",
        "TTTTttttTTTT",
        "LLTTTTTTTTRR",
        "LLLLTTTTRRRR",
        "LLLLLLRRRRRR",
        "LLlLLLRRrRRR",
        "LLLLLLRRRRRR",
        "LLLlLLRRRrRR",
        "..LLLLRRRR..",
        "....LLRR....",
    )
    private val LOGO_COLORS = mapOf(
        'T' to GOLD, 't' to 0xFFFFF0B8.toInt(), 'L' to 0xFF1F86A6.toInt(), 'l' to 0xFF155F78.toInt(),
        'R' to SOUL, 'r' to 0xFF9BEBFF.toInt(),
    )

    fun logo(ctx: DrawContext, x: Int, y: Int, a: Float) {
        LOGO.forEachIndexed { j, row ->
            var i = 0
            while (i < row.length) {
                val c = LOGO_COLORS[row[i]]
                var run = 1
                while (i + run < row.length && row[i + run] == row[i]) run++
                if (c != null) ctx.fill(x + i, y + j, x + i + run, y + j + 1, alpha(c, a))
                i += run
            }
        }
    }

    // ------------------------------------------------------------------ Schriften
    // `hha:display` = Silkscreen Bold als Bitmap (Überschriften), `hha:label` = Silkscreen mit +1 px Laufweite
    // (Etiketten, Knöpfe, Mono-Look der Website). Fließtext bleibt die Minecraft-Schrift. Beide Pixelschriften sind
    // 1:1 gerastert (Versalien 5 px, Grundlinie wie Vanilla bei y + 7); bei Skalierung 2 bleiben sie scharf.

    val DISPLAY_FONT: StyleSpriteSource = StyleSpriteSource.Font(Hha.id("display"))
    val LABEL_FONT: StyleSpriteSource = StyleSpriteSource.Font(Hha.id("label"))

    fun upper(s: String): String = s.uppercase(Locale.ROOT)

    fun displayText(s: String): Text = Text.literal(upper(s)).styled { it.withFont(DISPLAY_FONT) }

    fun labelText(s: String): Text = Text.literal(upper(s)).styled { it.withFont(LABEL_FONT) }

    fun displayWidth(tr: TextRenderer, s: String, scale: Int = 1): Int = tr.getWidth(displayText(s)) * scale

    /** Überschrift in Silkscreen Bold; bei [scale] 2 liegen die Versalien bei y + 4 … y + 13. Gibt das rechte Ende zurück. */
    fun display(ctx: DrawContext, tr: TextRenderer, s: String, x: Int, y: Int, color: Int, scale: Int = 1): Int {
        if ((color ushr 24) == 0) return x + displayWidth(tr, s, scale)
        val m = ctx.matrices
        m.pushMatrix()
        m.translate(x.toFloat(), y.toFloat())
        m.scale(scale.toFloat(), scale.toFloat())
        ctx.drawText(tr, displayText(s), 0, 0, color, false)
        m.popMatrix()
        return x + displayWidth(tr, s, scale)
    }

    fun labelWidth(tr: TextRenderer, s: String): Int = tr.getWidth(labelText(s))

    /** Etikett: Versalien 5 px hoch bei y + 2 … y + 6. Gibt das rechte Ende zurück. */
    fun label(ctx: DrawContext, tr: TextRenderer, s: String, x: Int, y: Int, color: Int): Int {
        if ((color ushr 24) != 0) ctx.drawText(tr, labelText(s), x, y, color, false)
        return x + labelWidth(tr, s)
    }

    fun inside(mx: Double, my: Double, x1: Int, y1: Int, x2: Int, y2: Int): Boolean =
        mx >= x1 && mx < x2 && my >= y1 && my < y2
}
