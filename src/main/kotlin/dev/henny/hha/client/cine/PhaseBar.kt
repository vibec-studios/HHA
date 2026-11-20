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

    // --- Unterleisten (Überarbeitung 4, PLAN 7.6; P04) --------------------------------------------------------

    /** Eine empfangene Unterleiste: Schlüssel, Anteil, Zustand (0 ruht, 1 golden, 2 gebrochen), Kerben (= Abschnitte). */
    class Sub(val key: String, val fraction: Float, val state: Int, val notches: Int)

    /** Die zuletzt empfangenen Unterleisten (höchstens vier); leer, wenn ausgeblendet. */
    var subs: List<Sub> = emptyList()
        private set

    /** Was am Client je Unterleiste läuft: weicher Anteil, Nachlauf, Alter des Bruchs, eigene Deckkraft. */
    private class SubView(var sub: Sub) {
        var shown = sub.fraction
        var trail = sub.fraction
        var trailHold = 0
        var brokenAge = if (sub.state == STATE_BROKEN) 0 else -1
        var alpha = 1f
        val name: Text = Text.translatable(sub.key)
    }

    private var views: List<SubView> = emptyList()
    private var subVisible = false
    private var subAppear = 0f
    private var subTheme = -1
    private var subSilence = 0
    private var subTime = 0

    /** `Cine.OP_SUB_BARS`: Anzahl, dann je Leiste Anteil, Zustand, Kerben; optional danach das Thema. Texte: Schlüssel. */
    fun handleSub(numbers: List<Double>, texts: List<String>) {
        val count = numbers.getOrElse(0) { 0.0 }.toInt().coerceIn(0, MAX_SUBS)
        subs = (0 until count).mapNotNull { i ->
            val base = 1 + i * 3
            if (numbers.size < base + 3) return@mapNotNull null
            Sub(
                texts.getOrElse(i) { "" }, numbers[base].toFloat().coerceIn(0f, 1f), numbers[base + 1].toInt().coerceIn(0, 2),
                numbers[base + 2].toInt().coerceIn(0, MAX_NOTCHES),
            )
        }
        subTheme = numbers.getOrElse(1 + count * 3) { -1.0 }.toInt()
        subSilence = 0
        if (subs.isEmpty()) {
            // Ausblenden: Die alten Leisten bleiben stehen, bis die Reihe weich verschwunden ist.
            subVisible = false
            return
        }
        val old = views
        views = subs.mapIndexed { i, sub ->
            val view = old.getOrNull(i)?.takeIf { it.sub.key == sub.key && subVisible }
            if (view == null) {
                // Neue Leiste: füllt sich beim ersten Erscheinen der Reihe von links (wie die Hauptleiste).
                SubView(sub).also { if (sub.state != STATE_BROKEN && subAppear <= 0.001f) it.shown = 0f }
            } else {
                if (sub.fraction < view.sub.fraction - 0.001f) view.trailHold = TRAIL_HOLD
                if (sub.state == STATE_BROKEN && view.sub.state != STATE_BROKEN) view.brokenAge = 0
                if (sub.state != STATE_BROKEN) {
                    view.brokenAge = -1
                    view.alpha = 1f
                }
                view.sub = sub
                view
            }
        }
        subVisible = true
    }

    private fun tickSubs() {
        subTime++
        if (subVisible && ++subSilence > SUB_TIMEOUT) subVisible = false // Sicherung: Der Server schweigt (Kampf weg).
        subAppear = MathHelper.clamp(subAppear + if (subVisible) 0.08f else -0.1f, 0f, 1f)
        if (subAppear <= 0f && !subVisible) {
            views = emptyList()
            subs = emptyList()
            return
        }
        for (v in views) {
            v.shown += (v.sub.fraction - v.shown) * 0.35f
            if (v.trailHold > 0) v.trailHold-- else v.trail += (v.shown - v.trail) * 0.12f
            if (v.trail < v.shown) v.trail = v.shown
            if (v.brokenAge >= 0) {
                v.brokenAge++
                if (v.brokenAge > BROKEN_HOLD) v.alpha = (v.alpha - 1f / BROKEN_FADE).coerceAtLeast(0f)
            }
        }
    }

    /** Oberkante der Unterleisten-Reihe (Platte) in GUI-Pixeln: unter der Hauptleiste oder an deren Platz. */
    private fun subTop(): Int {
        val main = appear * appear * (3f - 2f * appear)
        return MathHelper.lerp(main, SUB_TOP_ALONE.toFloat(), SUB_TOP_BELOW.toFloat()).toInt()
    }

    private fun subRowBottom(): Int = subTop() + SUB_ROW_HEIGHT

    private fun renderSubs(context: DrawContext) {
        if (subAppear <= 0.001f || views.isEmpty()) return
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val width = client.window.scaledWidth
        val a = subAppear * subAppear * (3f - 2f * subAppear)
        val n = views.size
        val rowWidth = n * SUB_WIDTH + (n - 1) * SUB_GAP
        val x0 = (width - rowWidth) / 2
        val top = subTop() - ((1f - a) * 4f).toInt()
        val alpha = (a * 255f).toInt().coerceIn(0, 255)
        fun argb(rgb: Int, mult: Float = 1f) = ((alpha * mult).toInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

        // Dunkle Platte wie bei der Hauptleiste (lesbar vor hellem Marmor).
        val px1 = x0 - SUB_PAD
        val px2 = x0 + rowWidth + SUB_PAD
        Ui.frame(context, px1, top, px2, top + SUB_ROW_HEIGHT, argb(0xFFFFFF, 0.09f))
        context.fill(px1 + 1, top + 1, px2 - 1, top + SUB_ROW_HEIGHT - 1, argb(0x000000, 0.5f))

        val (idleFill, idleLight) = idleColors()
        // Ruhiger Schein der goldenen Leisten: 0,8 Hz, nur die Stärke des Hofs schwankt (kein Blinken).
        val pulse = 0.5f + 0.5f * kotlin.math.sin(subTime * GOLD_PULSE_RAD_PER_TICK)
        for ((i, v) in views.withIndex()) {
            val sub = v.sub
            if (v.alpha <= 0.001f) continue
            val sx = x0 + i * (SUB_WIDTH + SUB_GAP)
            val labelY = top + SUB_LABEL_Y
            val barY = top + SUB_BAR_Y
            val k = v.alpha
            val broken = sub.state == STATE_BROKEN
            val open = sub.state == STATE_OPEN

            // Name über der Leiste (Etikett), mittig, auf die Leistenbreite samt Abstand gekürzt.
            val label = fitLabel(font, v.name.string)
            val lw = font.getWidth(label)
            val lx = sx + (SUB_WIDTH - lw) / 2
            val nameColor = when {
                broken -> 0x6B6B75
                open -> 0xFFD75E
                else -> 0xA1A1AA
            }
            context.drawText(font, label, lx, labelY, argb(nameColor, k), true)
            if (broken) context.fill(lx - 1, labelY + 4, lx + lw, labelY + 5, argb(0x8A8A94, k)) // durchgestrichen

            // Grund und Rand.
            context.fill(sx - 1, barY - 1, sx + SUB_WIDTH + 1, barY + SUB_HEIGHT + 1, argb(0x000000, 0.7f * k))
            context.fill(sx, barY, sx + SUB_WIDTH, barY + SUB_HEIGHT, argb(0xFFFFFF, (if (broken) 0.05f else 0.1f) * k))
            if (open) {
                // Goldener Hof: 1 px um die Leiste, darum ein zweiter, schwächerer.
                Ui.frame(context, sx - 2, barY - 2, sx + SUB_WIDTH + 2, barY + SUB_HEIGHT + 2, argb(GOLD_GLOW, (0.35f + 0.35f * pulse) * k))
                Ui.frame(context, sx - 3, barY - 3, sx + SUB_WIDTH + 3, barY + SUB_HEIGHT + 3, argb(GOLD_GLOW, (0.1f + 0.15f * pulse) * k))
            }

            val level = if (broken) 0f else v.shown.coerceIn(0f, 1f)
            val end = sx + (SUB_WIDTH * level).toInt()
            val trailEnd = sx + (SUB_WIDTH * v.trail.coerceIn(0f, 1f)).toInt()
            if (trailEnd > end && !broken) context.fill(end, barY, trailEnd, barY + SUB_HEIGHT, argb(0xFFFFFF, 0.7f * k))
            if (end > sx) {
                val fill = if (open) GOLD_FILL else idleFill
                val light = if (open) GOLD_LIGHT else idleLight
                context.fill(sx, barY, end, barY + SUB_HEIGHT, argb(fill, k))
                context.fill(sx, barY, end, barY + 1, argb(light, k))
            }
            if (broken) {
                // Bruchlinie: heller Zickzack aus 1-px-Stücken quer über die Leiste.
                val seg = SUB_WIDTH / (CRACK.size + 1)
                for ((j, dy) in CRACK.withIndex()) {
                    val cx = sx + seg / 2 + j * seg
                    context.fill(cx, barY + dy, cx + seg, barY + dy + 1, argb(0x8A8A94, 0.9f * k))
                }
            }

            // Kerben: n Abschnitte → n − 1 Fugen quer durch die Leiste, mit einem Pixel Überstand oben und unten.
            if (sub.notches >= 2) {
                for (j in 1 until sub.notches) {
                    val nx = sx + SUB_WIDTH * j / sub.notches
                    context.fill(nx, barY - 1, nx + 1, barY + SUB_HEIGHT + 1, argb(0x000000, 0.85f * k))
                }
            }
        }
    }

    /**
     * Name über der Leiste: als Etikett (Website-Stil), wenn er über die Leiste passt; sonst in der schmaleren
     * Spielschrift; passt auch die nicht, Zeichen für Zeichen gekürzt. Kampf-Pakete wählen kurze Namen (≤ 10 Zeichen).
     */
    private fun fitLabel(font: net.minecraft.client.font.TextRenderer, name: String): Text {
        val clean = name.trim()
        val label = Ui.labelText(clean)
        if (font.getWidth(label) <= SUB_WIDTH) return label
        var s = clean
        while (s.length > 1 && font.getWidth(s) > SUB_WIDTH) s = s.dropLast(1).trimEnd()
        return Text.literal(s)
    }

    /** Füllung und Oberkante der ruhenden Leisten: Gefahrenfarbe des Kampfs, gedämpft (zu 45 % ins Dunkle gezogen). */
    private fun idleColors(): Pair<Int, Int> {
        val t = when {
            subTheme >= 0 -> subTheme
            visible -> theme
            else -> -1
        }
        val fill: Int
        val light: Int
        // Gold heißt „offen“ (PLAN 4.1): Ein goldenes Kampfthema (Kronos) färbt ruhende Leisten nicht.
        if (t < 0 || t == 1 || t == STYLE_THEMES + Vfx.Style.GOLD.ordinal) {
            fill = IDLE_DEFAULT_FILL
            light = IDLE_DEFAULT_LIGHT
        } else {
            val c = colors(t)
            fill = c.first
            light = c.second
        }
        return (lerpColor(0xFF000000.toInt() or fill, IDLE_DARK, IDLE_DIM) and 0xFFFFFF) to
            (lerpColor(0xFF000000.toInt() or light, IDLE_DARK, IDLE_DIM) and 0xFFFFFF)
    }

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

    /**
     * Unterkante der Leiste samt Ziffern (und der Unterleisten-Reihe, falls sichtbar) in GUI-Pixeln, sonst 0. Das
     * Ultra-HUD weicht darunter aus.
     */
    fun bottom(): Int {
        val main = if (appear > 0.001f) MAIN_BOTTOM else 0
        return if (subAppear > 0.001f && views.isNotEmpty()) maxOf(main, subRowBottom()) else main
    }

    /** Alles aus (auch die Unterleisten), z. B. nach `Cine.OP_RESET` oder beim Verlassen der Welt. */
    fun reset() {
        visible = false
        appear = 0f
        subVisible = false
        subAppear = 0f
        views = emptyList()
        subs = emptyList()
    }

    fun tick() {
        tickSubs()
        appear = MathHelper.clamp(appear + if (visible) 0.06f else -0.1f, 0f, 1f)
        shown += (target - shown) * 0.35f
        if (trailHold > 0) trailHold-- else trail += (shown - trail) * 0.12f
        if (trail < shown) trail = shown
        flash = (flash - 0.04f).coerceAtLeast(0f)
    }

    fun render(context: DrawContext, tickDelta: Float) {
        renderSubs(context)
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

    /** Unterkante der Hauptleiste samt Ziffern (Platte endet bei y0 + 32 mit y0 = 18). */
    private const val MAIN_BOTTOM = 50

    // Unterleisten (Briefing P04): bis zu vier, je 72 × 5 px, 6 px Abstand, Name darüber als Etikett.
    const val MAX_SUBS = 4
    const val MAX_NOTCHES = 8
    const val SUB_WIDTH = 72
    const val SUB_HEIGHT = 5
    const val SUB_GAP = 6
    private const val SUB_PAD = 6

    /** Reihe: Platte 20 hoch, darin Etikett (Versalien y + 2 … 6) bei +3, Leiste bei +12 … +17. */
    private const val SUB_LABEL_Y = 3
    private const val SUB_BAR_Y = 12
    const val SUB_ROW_HEIGHT = 20

    /** Platte der Reihe: 3 px unter der Hauptleiste bzw. an deren Platz (Oberkante der Hauptplatte). */
    private const val SUB_TOP_BELOW = MAIN_BOTTOM + 3
    private const val SUB_TOP_ALONE = 3

    const val STATE_IDLE = 0
    const val STATE_OPEN = 1
    const val STATE_BROKEN = 2

    /** Gebrochen: so lange stehen lassen, dann in [BROKEN_FADE] Ticks ausblenden. */
    const val BROKEN_HOLD = 60
    private const val BROKEN_FADE = 20f

    /** Nachlauf hält nach einem Treffer so viele Ticks (wie die Hauptleiste). */
    private const val TRAIL_HOLD = 14

    /** Ohne Paket so lange (20 s; der Server sendet spätestens alle 5 s), dann blendet die Reihe aus. */
    const val SUB_TIMEOUT = 400

    /** Puls des goldenen Hofs: 0,8 Hz bei 20 Ticks/s. */
    private val GOLD_PULSE_RAD_PER_TICK = (2.0 * Math.PI * 0.8 / 20.0).toFloat()

    private const val GOLD_FILL = 0xFFC94A
    private const val GOLD_LIGHT = 0xFFF0B0
    private const val GOLD_GLOW = 0xFFD75E

    /** Ruhend ohne Thema: Gefahrenrot der Website (`Ui.BAD`), gedämpft. */
    private const val IDLE_DEFAULT_FILL = 0xFF6B5E
    private const val IDLE_DEFAULT_LIGHT = 0xFFB0A6
    private const val IDLE_DARK = 0xFF1A1A1F.toInt()
    private const val IDLE_DIM = 0.45f

    /** Zickzack der Bruchlinie: Zeile je Stück (0 … SUB_HEIGHT − 1). */
    private val CRACK = intArrayOf(1, 3, 2, 4, 1, 3, 0, 2)

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
