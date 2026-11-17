package dev.henny.hha.client.rpg.game

import dev.henny.hha.client.cine.CinePlayer
import dev.henny.hha.client.gui.Ui
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import kotlin.math.roundToInt

/**
 * Kleine Karten oben rechts für RPG-Ereignisse (Rast, Tagebuch, Hinweise; PLAN 3.5): dunkle Platte, 1-px-Rahmen,
 * 2-px-Streifen in Wegfarbe, Kicker in `hha:label`, Text in der Minecraft-Schrift. Höchstens [MAX_VISIBLE] zugleich,
 * je [LIFE_MS], weich ein und aus; weitere warten. In Halte, Ankunft und Szenen startet keine neue Karte. Unter den
 * Statuseffekt-Symbolen, damit nichts verdeckt wird.
 */
object RpgToasts {

    const val MAX_VISIBLE = 2
    const val LIFE_MS = 4000L
    const val IN_MS = 250L
    const val OUT_MS = 400L

    const val WIDTH = 150
    const val MAX_WIDTH = 240
    const val HEIGHT = 30
    const val GAP = 4
    const val MARGIN = 8

    /** Höhe einer Reihe Statuseffekt-Symbole (Vanilla: 25 px + Abstand). */
    private const val EFFECT_ROW = 26

    /** Höchstens so viele warten; ältere fallen weg (kein endloser Rückstau). */
    private const val MAX_QUEUE = 6

    private class Card(val kicker: Text, val text: Text, val sound: SoundEvent?) {
        var startedAt = 0L
        var y = Float.NaN
    }

    private val queue = ArrayDeque<Card>()
    private val shown = ArrayList<Card>()
    private val springs = Ui.Springs()
    private var lastNanos = 0L

    /** Neue Karte; [sound] spielt, wenn sie erscheint. */
    fun push(kicker: Text, text: Text, sound: SoundEvent?) {
        queue.addLast(Card(kicker, text, sound))
        while (queue.size > MAX_QUEUE) queue.removeFirst()
    }

    /** Je Client-Tick: abgelaufene entfernen, wartende nachrücken (nicht in Halte/Szene). */
    fun tick() {
        val now = System.currentTimeMillis()
        shown.removeAll { now - it.startedAt >= LIFE_MS }
        if (!RpgClientState.enabled) {
            reset()
            return
        }
        if (RpgClientState.arriving || CinePlayer.active || MinecraftClient.getInstance().player == null) return
        while (shown.size < MAX_VISIBLE && queue.isNotEmpty()) {
            val card = queue.removeFirst()
            card.startedAt = now
            shown.add(card)
            card.sound?.let { RpgUi.play(it, 0.8f) }
        }
    }

    /** Oberkante der ersten Karte: unter den Statuseffekt-Symbolen (gut und schlecht je eine Reihe). */
    private fun top(client: MinecraftClient): Int {
        val effects = client.player?.statusEffects?.filter { it.shouldShowIcon() } ?: emptyList()
        var rows = 0
        if (effects.any { it.effectType.value().isBeneficial }) rows++
        if (effects.any { !it.effectType.value().isBeneficial }) rows++
        return MARGIN + rows * EFFECT_ROW
    }

    fun render(ctx: DrawContext) {
        if (shown.isEmpty()) return
        val client = MinecraftClient.getInstance()
        if (client.options.hudHidden) return
        val nanos = System.nanoTime()
        springs.dt = if (lastNanos == 0L) 0.016f else ((nanos - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = nanos
        val tr = client.textRenderer
        val path = RpgUi.path()
        val now = System.currentTimeMillis()
        val top = top(client)
        val x2 = ctx.scaledWindowWidth - MARGIN
        shown.forEachIndexed { i, card ->
            val target = (top + i * (HEIGHT + GAP)).toFloat()
            if (card.y.isNaN()) card.y = target
            // Rückt nach, wenn die obere Karte geht
            card.y += (target - card.y) * (1f - kotlin.math.exp(-springs.dt * 14f))
            val age = now - card.startedAt
            val a = (Ui.easeOutCubic(age / IN_MS.toFloat()) * ((LIFE_MS - age) / OUT_MS.toFloat()).coerceIn(0f, 1f)).coerceIn(0f, 1f)
            if (a <= 0.02f) return@forEachIndexed
            val slide = ((1f - Ui.easeOutCubic(age / IN_MS.toFloat())) * 12f).roundToInt()
            val kicker = card.kicker.string
            val text = card.text.string
            val w = minOf(MAX_WIDTH, ctx.scaledWindowWidth / 2 - 16, maxOf(WIDTH, tr.getWidth(text) + 20, Ui.labelWidth(tr, kicker) + 20))
            val x = x2 - w + slide
            val y = card.y.roundToInt()
            ctx.fill(x, y, x + w, y + HEIGHT, Ui.alpha(Ui.SURFACE, 0.9f * a))
            Ui.frame(ctx, x, y, x + w, y + HEIGHT, Ui.alpha(Ui.LINE_2, a))
            ctx.fill(x, y, x + 2, y + HEIGHT, Ui.alpha(path, a))
            Ui.label(ctx, tr, kicker, x + 10, y + 5, Ui.alpha(path, a))
            ctx.drawText(tr, RpgUi.trimText(tr, text, w - 20), x + 10, y + 16, Ui.alpha(Ui.TEXT, a), false)
        }
    }

    fun reset() {
        queue.clear()
        shown.clear()
    }
}
