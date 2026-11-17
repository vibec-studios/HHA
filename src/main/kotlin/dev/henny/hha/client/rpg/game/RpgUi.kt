package dev.henny.hha.client.rpg.game

import dev.henny.hha.client.gui.Ui
import dev.henny.hha.rpg.RpgStory
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Gemeinsame Bausteine der RPG-Oberflächen (P16) im Website-Stil über [Ui]: Wegfarbe, Lichtlinie, Status-Quadrat,
 * Richtungspfeil im Pixelraster, Seitenkopf wie im Kontrollzentrum. Die HTML-Attrappe
 * `tmp/ui_redesign/mock/rpg_game/rpg.js` zeichnet mit denselben Maßen.
 */
internal object RpgUi {

    /** Kopfleiste und Tab-Leiste der Seiten (wie `HhaMenuScreen`). */
    const val NAV = 22
    const val TOC = 16

    /** Höchstbreite der Inhaltsspalte der Seiten. */
    const val WRAP = 640

    /** Ab diesem Anteil am Höchstwert gilt das Leben als niedrig (roter Rand, nie Blinken). */
    const val LOW_HEALTH = 0.25f

    /** Leerer Grund eines Segments bzw. einer Lebenslinie. */
    const val TRACK = 0x26FFFFFF

    /** Wegfarbe als ARGB (deckend). */
    fun path(): Int = 0xFF000000.toInt() or (RpgClientState.color() and 0xFFFFFF)

    fun tr(key: String, vararg args: Any): String = Text.translatable(key, *args).string

    /** Lichtlinie wie die Kapitelkarte: zwölf Stücke, in der Mitte am hellsten. */
    fun lightLine(ctx: DrawContext, cx: Int, y: Int, half: Int, color: Int, a: Float) {
        if (half <= 0) return
        for (i in 0 until 12) {
            val x1 = cx - half + half * 2 * i / 12
            val x2 = cx - half + half * 2 * (i + 1) / 12
            val k = 1f - abs((i + 0.5f) / 12f * 2f - 1f)
            ctx.fill(x1, y, x2, y + 1, Ui.alpha(color, (0.15f + 0.85f * k) * a))
        }
    }

    enum class Check { DONE, CURRENT, OPEN }

    /** Status-Quadrat 7 × 7: erledigt = voll mit Haken, laufend = Rahmen mit Kern, offen = gedämpfter Rahmen. */
    fun check(ctx: DrawContext, x: Int, y: Int, state: Check, color: Int, a: Float) {
        when (state) {
            Check.DONE -> {
                ctx.fill(x, y, x + 7, y + 7, Ui.alpha(color, a))
                val on = Ui.alpha(Ui.ON_ACCENT, a)
                ctx.fill(x + 2, y + 3, x + 3, y + 4, on)
                ctx.fill(x + 3, y + 4, x + 4, y + 5, on)
                ctx.fill(x + 4, y + 2, x + 5, y + 4, on)
            }
            Check.CURRENT -> {
                Ui.frame(ctx, x, y, x + 7, y + 7, Ui.alpha(color, a))
                ctx.fill(x + 2, y + 2, x + 5, y + 5, Ui.alpha(color, a))
            }
            Check.OPEN -> Ui.frame(ctx, x, y, x + 7, y + 7, Ui.alpha(Ui.LINE_2, a))
        }
    }

    /** Umriss des Pfeils in Pfeil-Koordinaten (u = rechts, v = vorn), Spitze bei v = 5,2. */
    private val ARROW = arrayOf(floatArrayOf(0f, 5.2f), floatArrayOf(4.3f, -4.2f), floatArrayOf(0f, -1.6f), floatArrayOf(-4.3f, -4.2f))

    /**
     * Pixel des Richtungspfeils im 11 × 11-Raster um (0, 0), gedreht um [deg] (0 = geradeaus = oben, im
     * Uhrzeigersinn). Jeder Pixel einzeln getestet, damit der Pfeil in jeder Lage scharf bleibt (keine gedrehten Quads).
     */
    fun arrowPixels(deg: Float): List<IntArray> {
        val a = Math.toRadians(deg.toDouble())
        val s = sin(a).toFloat()
        val c = cos(a).toFloat()
        val out = ArrayList<IntArray>()
        for (j in -5..5) for (i in -5..5) {
            val u = i * c + j * s
            val v = i * s - j * c
            var inside = false
            var m = ARROW.size - 1
            for (k in ARROW.indices) {
                val (ux, vy) = ARROW[k]
                val (px, py) = ARROW[m]
                if ((vy > v) != (py > v) && u < (px - ux) * (v - vy) / (py - vy) + ux) inside = !inside
                m = k
            }
            if (inside) out.add(intArrayOf(i, j))
        }
        return out
    }

    fun arrow(ctx: DrawContext, cx: Int, cy: Int, deg: Float, color: Int) {
        for (p in arrowPixels(deg)) ctx.fill(cx + p[0], cy + p[1], cx + p[0] + 1, cy + p[1] + 1, color)
    }

    /** Kürzt ein Etikett mit „…“ auf [width]. */
    fun trimLabel(tr: TextRenderer, s: String, width: Int): String {
        if (Ui.labelWidth(tr, s) <= width) return s
        var t = s
        while (t.length > 1 && Ui.labelWidth(tr, "$t…") > width) t = t.dropLast(1)
        return "$t…"
    }

    /** Kürzt Fließtext (Minecraft-Schrift) mit „…“ auf [width]. */
    fun trimText(tr: TextRenderer, s: String, width: Int): String {
        if (tr.getWidth(s) <= width) return s
        var t = s
        while (t.length > 1 && tr.getWidth("$t…") > width) t = t.dropLast(1)
        return "$t…"
    }

    /** Größte ganzzahlige Skalierung aus [scales], bei der [s] in [maxW] passt (Pixelschrift bleibt scharf). */
    fun displayScale(tr: TextRenderer, s: String, maxW: Int, vararg scales: Int): Int =
        scales.firstOrNull { Ui.displayWidth(tr, s, it) <= maxW } ?: 1

    fun play(sound: SoundEvent, volume: Float = 0.8f, pitch: Float = 1f) {
        MinecraftClient.getInstance().soundManager.play(PositionedSoundInstance.ui(sound, pitch, volume))
    }

    /** Klick wie im Kontrollzentrum. */
    fun click() {
        MinecraftClient.getInstance().soundManager.play(PositionedSoundInstance.ui(SoundEvents.UI_BUTTON_CLICK, 1.6f))
    }

    /** Kicker-Schlüssel eines Kapitels (der Unterweltweg hat eigene, z. B. „Kapitel I“). */
    fun kickerKey(chapter: String): String =
        chapters().firstOrNull { it.id == chapter }?.kickerKey ?: "hha.rpg.story.$chapter.kicker"

    private var chaptersPath: String? = null
    private var chaptersCache: List<RpgStory.Chapter> = emptyList()

    /** Kapitel des laufenden Wegs (reine Daten aus [RpgStory], je Weg einmal geholt). */
    fun chapters(): List<RpgStory.Chapter> {
        val path = RpgClientState.path
        if (path != chaptersPath) {
            chaptersPath = path
            chaptersCache = if (path.isEmpty()) emptyList() else RpgStory.chapters(path)
        }
        return chaptersCache
    }
}
