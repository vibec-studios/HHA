package dev.henny.hha.client.rpg.game

import dev.henny.hha.client.gui.Ui
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Abspann der RPG-Welt (Ereignis `CREDITS`, geschickt von P17 nach `rpg.end`): eine Rolle im Website-Stil über der
 * laufenden Endszene. Titel, Weg, Mitspielernamen, Spielzeit, Tode, Dank (Schlüssel `hha.rpg.credits.*` von P17, P20).
 * Läuft [DURATION_MS], Esc beendet. Als eigener
 * Bildschirm, weil das HUD in Szenen ganz abgeschaltet ist (`InGameHudMixin`); er pausiert nicht und zeichnet keinen
 * Hintergrund außer der abgedunkelten Mittelspalte, damit die Szene links und rechts sichtbar bleibt.
 * Maße wie `rpgCredits` in der Attrappe.
 */
class RpgCreditsScreen(private val names: List<String>, private val playTicks: Long, private val deaths: Int) :
    Screen(Text.translatable("hha.rpg.credits.title")) {

    companion object {
        const val DURATION_MS = 40_000L

        /** In dieser Zeit am Ende ist die Rolle ganz durchgelaufen (danach nur noch Ausblenden). */
        const val ROLL_END_MS = 36_000L
        const val FADE_MS = 1200L
        const val COLUMN_MAX = 300

        /** Höhe der Kino-Balken der Szene (wie `CinePlayer`): die Rolle bleibt dazwischen. */
        const val BAR_FRACTION = 0.11f

        /** Ereignis `CREDITS`: numbers = (Spielzeit in Ticks, Tode), texts = Namen. */
        fun open(numbers: List<Double>, texts: List<String>) {
            val client = MinecraftClient.getInstance()
            if (client.player == null) return
            val ticks = numbers.getOrNull(0)?.toLong() ?: 0L
            val deaths = numbers.getOrNull(1)?.toInt() ?: 0
            client.setScreen(RpgCreditsScreen(texts, ticks, deaths))
        }
    }

    private sealed interface Block
    private class Label(val text: String, val color: Int) : Block
    private class Display(val text: String, val color: Int, val scale: Int) : Block
    private class Body(val text: String, val color: Int) : Block
    private class Gap(val h: Int) : Block
    private object Line : Block
    private object Logo : Block

    private var openedAt = 0L
    private var blocks: List<Block> = emptyList()
    private var contentH = 0

    override fun init() {
        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        val path = RpgUi.path()
        val list = ArrayList<Block>()
        list += Label(RpgUi.tr("hha.rpg.credits.kicker"), path)
        list += Gap(6)
        val title = RpgUi.tr("hha.rpg.credits.title")
        list += Display(title, Ui.TEXT, RpgUi.displayScale(textRenderer, title, min(COLUMN_MAX, width - 40) - 8, 3, 2, 1))
        list += Line
        if (RpgClientState.path == "heaven" || RpgClientState.path == "hell") {
            list += Gap(6)
            list += Body(RpgUi.tr("hha.rpg.credits.path.${RpgClientState.path}"), Ui.MUTED)
        }
        list += Gap(26)
        if (names.isNotEmpty()) {
            list += Label(RpgUi.tr("hha.rpg.credits.party"), path)
            list += Gap(4)
            for (n in names) list += Display(n, Ui.TEXT, RpgUi.displayScale(textRenderer, n, min(COLUMN_MAX, width - 40) - 8, 2, 1))
            list += Gap(22)
        }
        list += Label(RpgUi.tr("hha.rpg.credits.time", playTime()), path)
        list += Gap(14)
        list += Label(if (deaths > 0) RpgUi.tr("hha.rpg.credits.deaths", deaths) else RpgUi.tr("hha.rpg.credits.deaths.none"), path)
        list += Gap(30)
        list += Body(RpgUi.tr("hha.rpg.credits.thanks"), Ui.MUTED)
        list += Gap(18)
        list += Body(RpgUi.tr("hha.rpg.credits.music"), Ui.DIM)
        list += Gap(4)
        list += Body(RpgUi.tr("hha.rpg.credits.voices"), Ui.DIM)
        list += Gap(14)
        list += Logo
        list += Gap(4)
        list += Label(RpgUi.tr("hha.rpg.credits.made"), Ui.DIM)
        list += Gap(30)
        val end = RpgUi.tr("hha.rpg.credits.end")
        list += Display(end, Ui.TEXT, RpgUi.displayScale(textRenderer, end, min(COLUMN_MAX, width - 40) - 8, 2, 1))
        blocks = list
        contentH = list.sumOf { heightOf(it) }
    }

    private fun playTime(): String {
        val minutes = playTicks / 20 / 60
        return if (minutes >= 60) RpgUi.tr("hha.rpg.hud.time.hm", minutes / 60, minutes % 60) else RpgUi.tr("hha.rpg.hud.time.m", minutes)
    }

    private fun heightOf(b: Block): Int = when (b) {
        is Label -> 10
        is Display -> 9 * b.scale + 3
        is Body -> 10
        is Gap -> b.h
        Line -> 5
        Logo -> 12
    }

    override fun shouldPause(): Boolean = false

    /** Ohne `super`: kein Weichzeichner; nur die Mittelspalte wird abgedunkelt. */
    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val a = alpha()
        val colW = min(COLUMN_MAX, width - 40)
        val c1 = width / 2 - colW / 2
        val c2 = c1 + colW
        context.fill(c1, 0, c2, height, Ui.alpha(0xB4000000.toInt(), a))
        for (i in 0 until 24) {
            val k = 0.55f * (1f - i / 24f) * a
            context.fill(c1 - i - 1, 0, c1 - i, height, Ui.alpha(0xFF000000.toInt(), k))
            context.fill(c2 + i, 0, c2 + i + 1, height, Ui.alpha(0xFF000000.toInt(), k))
        }
    }

    private fun alpha(): Float {
        val t = System.currentTimeMillis() - openedAt
        return (min(t, DURATION_MS - t) / FADE_MS.toFloat()).coerceIn(0f, 1f)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val t = System.currentTimeMillis() - openedAt
        if (t >= DURATION_MS) {
            close()
            return
        }
        val tr = textRenderer
        val a = alpha()
        val cx = width / 2
        val bar = (height * BAR_FRACTION).toInt()
        val colW = min(COLUMN_MAX, width - 40)
        val c1 = cx - colW / 2
        val c2 = c1 + colW
        val path = RpgUi.path()

        // Die Rolle läuft vom unteren Balken, bis alles oben hinaus ist
        val travel = (height - 2 * bar) + contentH
        val progress = (t / ROLL_END_MS.toFloat()).coerceIn(0f, 1f)
        var y = height - bar - (travel * progress).roundToInt()
        context.enableScissor(0, bar, width, height - bar)
        for (b in blocks) {
            when (b) {
                is Label -> Ui.label(context, tr, b.text, cx - Ui.labelWidth(tr, b.text) / 2, y, Ui.alpha(b.color, a))
                is Display -> Ui.display(context, tr, b.text, cx - Ui.displayWidth(tr, b.text, b.scale) / 2, y, Ui.alpha(b.color, a), b.scale)
                is Body -> context.drawText(tr, b.text, cx - tr.getWidth(b.text) / 2, y, Ui.alpha(b.color, a), false)
                is Gap -> {}
                Line -> RpgUi.lightLine(context, cx, y + 2, 70, path, a)
                Logo -> Ui.logo(context, cx - 6, y, a)
            }
            y += heightOf(b)
        }
        context.disableScissor()
        // Weiche Ränder oben und unten in der Spalte
        context.fillGradient(c1, bar, c2, bar + 30, Ui.alpha(0xFF000000.toInt(), a), 0)
        context.fillGradient(c1, height - bar - 30, c2, height - bar, 0, Ui.alpha(0xFF000000.toInt(), a))
        val skip = RpgUi.tr("hha.rpg.credits.hint")
        Ui.label(context, tr, skip, width - 10 - Ui.labelWidth(tr, skip), height - 14, Ui.alpha(Ui.MUTED, 0.7f * a))
    }
}
