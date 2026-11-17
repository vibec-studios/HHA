package dev.henny.hha.client.rpg.game

import dev.henny.hha.client.gui.GuiClient
import dev.henny.hha.client.gui.Ui
import dev.henny.hha.client.rpg.RpgNet
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.GameMenuScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.option.OptionsScreen
import net.minecraft.client.input.KeyInput
import net.minecraft.client.world.ClientWorld
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pausenmenü der RPG-Welt (PLAN 3.5) im Website-Stil: abgedunkelte Welt ohne Weichzeichner, links Kicker, Kapitel und
 * Ziel, darunter die Einträge Weiter · Tagebuch · Gruppe (Code kopieren) · Optionen (Vanilla) · HHA-Menü · Zum
 * Hauptmenü; rechts die Gruppe (bei Platz). Maus, Pfeiltasten + Enter und 1–6.
 *
 * Ersetzt Vanillas `GameMenuScreen` im selben Moment ([register]): nur in RPG-Welten mit Schalter `rpg_hud` und nur,
 * wenn das Menü gezeigt wird (`shouldShowMenu`; F3+Esc bleibt Vanilla). Pausiert wie Vanilla (`shouldPause` bleibt
 * true: Einzelspieler ohne LAN hält an). Maße wie `rpgPause` in der Attrappe.
 */
class RpgPauseScreen : Screen(Text.translatable("hha.rpg.pause.kicker")) {

    companion object {
        const val ROW_H = 18
        const val COL_MIN = 200
        const val COL_MAX = 230
        const val PARTY_W = 150

        /** Ab dieser GUI-Breite steht rechts die Gruppe. */
        const val WIDE = 520
        const val COPIED_MS = 2000L
        const val FADE_MS = 160L

        /** Hängt sich an `ScreenEvents.AFTER_INIT` (aus [RpgHud.init]). */
        fun register() {
            ScreenEvents.AFTER_INIT.register { client, screen, _, _ ->
                if (screen is GameMenuScreen && screen.shouldShowMenu() && RpgHud.active()) client.setScreen(RpgPauseScreen())
            }
        }
    }

    private enum class Item(val key: String) { RESUME("resume"), JOURNAL("journal"), PARTY("party"), OPTIONS("options"), MENU("menu"), QUIT("quit") }

    private val items = Item.entries
    private val springs = Ui.Springs()
    private var lastNanos = 0L
    private var openedAt = 0L
    private var focus = 0
    private var copiedUntil = 0L
    private var quitting = false
    private var rowsTop = 0
    private var colX = 0
    private var colW = 0

    override fun init() {
        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        // Zurück aus dem Meldungsentwurf (tryShowDraftScreen → Bearbeiten): noch nicht getrennt (Prüfung R1-m3).
        quitting = false
    }

    private fun fade(): Float = Ui.easeOutCubic((System.currentTimeMillis() - openedAt) / FADE_MS.toFloat())

    /** Ohne `super`: kein Vanilla-Weichzeichner, nur die abgedunkelte Welt mit einem Hauch Wegfarbe. */
    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val a = fade()
        val path = RpgUi.path()
        context.fill(0, 0, width, height, Ui.alpha(0xB8000000.toInt(), a))
        context.fillGradient(0, 0, width, height * 2 / 3, Ui.alpha(path, 0.12f * a), path and 0x00FFFFFF)
        context.fillGradient(0, height / 2, width, height, 0, Ui.alpha(0x99000000.toInt(), a))
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val nanos = System.nanoTime()
        springs.dt = if (lastNanos == 0L) 0.016f else ((nanos - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = nanos
        val tr = textRenderer
        val a = fade()
        val path = RpgUi.path()
        val l = max(16, (width * 0.09f).roundToInt())
        colX = l
        colW = min(COL_MAX, max(COL_MIN, (width * 0.46f).toInt()))

        // Kopf: Logo wie das Kontrollzentrum
        Ui.logo(context, l, 12, a)
        val e = Ui.display(context, tr, "H&H Armors", l + 18, 14, Ui.alpha(Ui.TEXT, a))
        Ui.label(context, tr, "RPG", e + 8, 14, Ui.alpha(Ui.DIM, a))

        val chapter = RpgClientState.chapter
        val title = if (chapter.isEmpty()) RpgUi.tr("hha.rpg.pause.kicker") else RpgUi.tr("hha.rpg.story.$chapter.title")
        val scale = RpgUi.displayScale(tr, title, width - 2 * l, if (height >= 300) 3 else 2, 2, 1)
        val blockH = 12 + 9 * scale + 6 + 12 + 14 + items.size * ROW_H
        var y = max(40, (height - blockH) / 2)
        val rise = ((1f - a) * 6f).roundToInt()
        y += rise
        val kicker = if (chapter.isEmpty()) RpgUi.tr("hha.rpg.pause.kicker") else "${RpgUi.tr("hha.rpg.pause.kicker")} · ${RpgUi.tr(RpgUi.kickerKey(chapter))}"
        Ui.label(context, tr, kicker, l, y, Ui.alpha(path, a))
        y += 12
        Ui.display(context, tr, title, l, y, Ui.alpha(Ui.TEXT, a), scale)
        y += 9 * scale + 6
        RpgClientState.objective?.let {
            context.drawText(tr, RpgUi.trimText(tr, it.string, width - 2 * l), l, y, Ui.alpha(Ui.MUTED, a), false)
        }
        y += 12 + 14
        rowsTop = y

        // Einträge
        val hoverRow = rowAt(mouseX.toDouble(), mouseY.toDouble())
        if (hoverRow >= 0) focus = hoverRow
        val code = RpgNet.lobby?.code
        val copied = System.currentTimeMillis() < copiedUntil
        items.forEachIndexed { i, item ->
            val ry = y + i * ROW_H
            val on = i == focus
            val hv = springs.get("row$i", if (on) 1f else 0f, 18f, 0f)
            Ui.hline(context, l, l + colW, ry, Ui.alpha(Ui.LINE, a))
            if (hv > 0.01f) {
                context.fill(l, ry + 1, l + colW, ry + ROW_H, Ui.alpha(Ui.SURFACE_2, 0.9f * hv * a))
                context.fill(l, ry + 1, l + 2, ry + ROW_H, Ui.alpha(path, hv * a))
            }
            val dx = (hv * 4f).roundToInt()
            Ui.label(context, tr, "0${i + 1}", l + 8 + dx, ry + 6, Ui.alpha(Ui.lerp(Ui.DIM, path, hv), a))
            val name = RpgUi.tr("hha.rpg.pause.${item.key}")
            val ne = Ui.label(context, tr, name, l + 26 + dx, ry + 6, Ui.alpha(Ui.lerp(Ui.MUTED, Ui.TEXT, hv), a))
            if (item == Item.PARTY) {
                // Rechts der Code und was ein Klick tut; je nach Platz kürzer
                val room = l + colW - 8 - ne - 10
                val options = if (code != null) {
                    listOf("$code · ${RpgUi.tr(if (copied) "hha.rpg.pause.copied" else "hha.rpg.pause.copy")}", if (copied) RpgUi.tr("hha.rpg.pause.copied") else code)
                } else {
                    listOf(RpgUi.tr("hha.rpg.pause.no_code"))
                }
                options.firstOrNull { Ui.labelWidth(tr, it) <= room }?.let {
                    Ui.label(context, tr, it, l + colW - 8 - Ui.labelWidth(tr, it), ry + 6, Ui.alpha(if (copied) path else Ui.DIM, a))
                }
            }
        }
        Ui.hline(context, l, l + colW, y + items.size * ROW_H, Ui.alpha(Ui.LINE, a))

        if (width >= WIDE) drawParty(context, width - l - PARTY_W, width - l, y, a)

        // Fuß: angehalten oder läuft weiter (Einzelspieler ohne LAN hält an), Tastenhinweis
        val c = client
        val paused = c != null && c.isIntegratedServerRunning && c.server?.isRemote == false
        Ui.label(context, tr, RpgUi.tr(if (paused) "hha.rpg.pause.paused" else "hha.rpg.pause.running"), l, height - 16, Ui.alpha(Ui.DIM, a))
        val keys = RpgUi.tr("hha.rpg.pause.keys")
        Ui.label(context, tr, keys, width - l - Ui.labelWidth(tr, keys), height - 16, Ui.alpha(Ui.DIM, a))
    }

    /** Rechte Spalte: alle Mitglieder (auch du), Lebenslinie in Wegfarbe, „gefallen“ grau. */
    private fun drawParty(ctx: DrawContext, x1: Int, x2: Int, y: Int, a: Float) {
        val tr = textRenderer
        val path = RpgUi.path()
        val own = client?.player?.uuidAsString
        val members = RpgClientState.party
        if (members.isEmpty()) return
        Ui.label(ctx, tr, RpgUi.tr("hha.rpg.pause.members", members.size), x1, y - 14, Ui.alpha(path, a))
        members.take(8).forEachIndexed { i, m ->
            val ry = y + i * ROW_H
            Ui.hline(ctx, x1, x2, ry, Ui.alpha(Ui.LINE, a))
            val st = if (m.down) RpgUi.tr("hha.rpg.hud.down") else ceil(m.health).toInt().toString()
            val sw = Ui.labelWidth(tr, st)
            Ui.label(ctx, tr, RpgUi.trimLabel(tr, m.name, x2 - x1 - sw - 12), x1 + 2, ry + 4, Ui.alpha(if (m.down) Ui.DIM else Ui.TEXT, a))
            Ui.label(ctx, tr, st, x2 - 2 - sw, ry + 4, Ui.alpha(if (m.uuid == own) path else Ui.DIM, a))
            ctx.fill(x1 + 2, ry + 13, x2 - 2, ry + 14, Ui.alpha(RpgUi.TRACK, a))
            if (!m.down && m.maxHealth > 0f) {
                val f = (m.health / m.maxHealth).coerceIn(0f, 1f)
                ctx.fill(x1 + 2, ry + 13, x1 + 2 + ((x2 - x1 - 4) * f).roundToInt(), ry + 14, Ui.alpha(path, a))
            }
        }
        Ui.hline(ctx, x1, x2, y + min(8, members.size) * ROW_H, Ui.alpha(Ui.LINE, a))
    }

    private fun rowAt(mx: Double, my: Double): Int {
        if (!Ui.inside(mx, my, colX, rowsTop, colX + colW, rowsTop + items.size * ROW_H)) return -1
        return ((my - rowsTop) / ROW_H).toInt().coerceIn(0, items.size - 1)
    }

    private fun activate(index: Int) {
        val c = client ?: return
        RpgUi.click()
        when (items[index]) {
            Item.RESUME -> close()
            Item.JOURNAL -> c.setScreen(RpgJournalScreen(0, this))
            Item.PARTY -> {
                val code = RpgNet.lobby?.code
                if (code != null) {
                    c.keyboard.clipboard = code
                    copiedUntil = System.currentTimeMillis() + COPIED_MS
                } else {
                    c.setScreen(RpgJournalScreen(2, this))
                }
            }
            Item.OPTIONS -> c.setScreen(OptionsScreen(this, c.options))
            Item.MENU -> GuiClient.open()
            Item.QUIT -> {
                // Wie Vanilla (GameMenuScreen): erst ein offener Meldungs-Entwurf, dann Welt schließen bzw. trennen
                if (quitting) return
                quitting = true
                c.abuseReportContext.tryShowDraftScreen(c, this, { c.disconnect(ClientWorld.QUITTING_MULTIPLAYER_TEXT) }, true)
            }
        }
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            val row = rowAt(click.x(), click.y())
            if (row >= 0) {
                activate(row)
                return true
            }
        }
        return super.mouseClicked(click, doubled)
    }

    override fun keyPressed(input: KeyInput): Boolean {
        when (input.key()) {
            GLFW.GLFW_KEY_UP -> { focus = (focus + items.size - 1) % items.size; return true }
            GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_TAB -> { focus = (focus + 1) % items.size; return true }
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> { activate(focus); return true }
        }
        val n = input.key() - GLFW.GLFW_KEY_1
        if (n in items.indices) {
            focus = n
            activate(n)
            return true
        }
        return super.keyPressed(input)
    }
}
