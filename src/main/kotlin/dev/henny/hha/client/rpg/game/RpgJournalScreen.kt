package dev.henny.hha.client.rpg.game

import dev.henny.hha.api.rpg.HhaRpg
import dev.henny.hha.client.gui.Ui
import dev.henny.hha.client.rpg.RpgNet
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.input.KeyInput
import net.minecraft.client.resource.language.I18n
import net.minecraft.text.Text
import net.minecraft.util.Language
import org.lwjgl.glfw.GLFW
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tagebuch der RPG-Welt (Taste J, PLAN 3.5), aufgebaut wie eine Seite des Kontrollzentrums: Kopfleiste mit Logo,
 * Weg-Chip und Schließen, Tab-Leiste 01 Kapitel · 02 Wissen · 03 Gruppe, darunter eine zentrierte Spalte.
 *
 * - **Kapitel:** links die Kapitel des Wegs mit Haken (erledigt / läuft / offen, aus `RpgClientState.done` und
 *   `chapter`), rechts das gewählte Kapitel mit seinen Zielschritten.
 * - **Wissen:** ein Eintrag je Gegner; `<boss>` im Tagebuch schaltet Absatz `.a` frei, `<boss>.done` Absatz `.b`
 *   (Schlüssel `hha.rpg.lore.<boss>.title/.a/.b`, Texte P17).
 * - **Gruppe:** Lobby-Code (aus `RpgNet.lobby`, solange verbunden) mit Kopieren, Mitglieder mit Leben.
 *
 * Mausrad scrollt, Esc oder die Tagebuch-Taste schließt (zurück zu [parent]). Pausiert das Spiel nicht. Maße wie
 * `rpgJournal` in der Attrappe.
 */
class RpgJournalScreen(initialTab: Int = 0, private val parent: Screen? = null) : Screen(Text.translatable("hha.rpg.journal.title")) {

    companion object {
        const val TABS = 3
        private val TAB_KEYS = listOf("chapters", "lore", "party")

        const val CHAPTER_ROW = 26
        const val GOAL_ROW = 22
        const val LORE_ROW = 20
        const val MEMBER_ROW = 30
        const val CODE_BOX_H = 44
        const val FADE_MS = 200L
        const val COPIED_MS = 2000L

        /** Alle Wissens-Einträge in Spielreihenfolge (Addon-Einträge hängen sich hinten an). */
        val BOSSES = listOf("hermes", "brontes", "kratos", "zeus", "hekate", "warden", "kampe", "sisyphus", "ixion", "tantalus", "hades", "kronos")

        /** Gegner des Olymps: auf dem Unterweltweg nur, wenn sie trotzdem im Tagebuch stehen. */
        private val OLYMPUS = setOf("hermes", "brontes", "kratos", "zeus", "hekate")
    }

    private class Hit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val action: () -> Unit)

    private val springs = Ui.Springs()
    private var lastNanos = 0L
    private var openedAt = 0L
    private var tabAt = 0L
    private var tab = initialTab.coerceIn(0, TABS - 1)
    private val scroll = FloatArray(TABS)
    private val scrollTarget = FloatArray(TABS)
    private val contentHeight = IntArray(TABS)
    private val hits = ArrayList<Hit>()
    private var selectedChapter: String? = null
    private var selectedLore: String? = null
    private var copiedUntil = 0L
    private var wrapX = 0
    private var wrapW = 0
    private var mx = -1.0
    private var my = -1.0

    override fun init() {
        val now = System.currentTimeMillis()
        if (openedAt == 0L) {
            openedAt = now
            tabAt = now
        }
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(parent)
    }

    private fun fade(): Float = Ui.easeOutCubic((System.currentTimeMillis() - openedAt) / FADE_MS.toFloat())

    private fun layout() {
        val pad = if (width < 520) 12 else 24
        wrapW = min(width - 2 * pad, RpgUi.WRAP)
        wrapX = (width - wrapW) / 2
    }

    /** Ohne `super`: kein Vanilla-Weichzeichner. Schwarz über der Welt, oben ein Hauch Wegfarbe, Hilfslinien. */
    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        layout()
        val a = fade()
        val path = RpgUi.path()
        context.fill(0, 0, width, height, Ui.alpha(0xE0000000.toInt(), a))
        context.fillGradient(0, 0, width, height * 2 / 3, Ui.alpha(path, 0.13f * a), path and 0x00FFFFFF)
        if (width - wrapW >= 8) {
            Ui.vline(context, wrapX - 1, 0, height, Ui.alpha(Ui.LINE, a))
            Ui.vline(context, wrapX + wrapW, 0, height, Ui.alpha(Ui.LINE, a))
        }
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val nanos = System.nanoTime()
        springs.dt = if (lastNanos == 0L) 0.016f else ((nanos - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = nanos
        layout()
        mx = mouseX.toDouble()
        my = mouseY.toDouble()
        hits.clear()
        val a = fade()
        val top = RpgUi.NAV + RpgUi.TOC
        val view = height - top
        val tabT = Ui.easeOutCubic((System.currentTimeMillis() - tabAt) / 260f)
        val rise = (1f - tabT) * 6f
        scrollTarget[tab] = scrollTarget[tab].coerceIn(0f, max(0f, (contentHeight[tab] - view).toFloat()))
        scroll[tab] = springs.get("scroll$tab", scrollTarget[tab], 18f)

        context.enableScissor(wrapX - 4, top, wrapX + wrapW + 4, height)
        val m = context.matrices
        m.pushMatrix()
        m.translate(0f, rise)
        val y0 = top + 12 - scroll[tab].roundToInt()
        val ca = a * tabT.coerceAtLeast(0.15f)
        val end = when (tab) {
            0 -> tabChapters(context, wrapX, y0, wrapW, ca)
            1 -> tabLore(context, wrapX, y0, wrapW, ca)
            else -> tabParty(context, wrapX, y0, wrapW, ca)
        }
        contentHeight[tab] = end - y0 + 12
        m.popMatrix()
        context.disableScissor()
        if (contentHeight[tab] > view) {
            val bar = max(16, view * view / contentHeight[tab])
            val pos = ((view - bar) * (scroll[tab] / max(1, contentHeight[tab] - view))).toInt()
            context.fill(wrapX + wrapW + 3, top + pos, wrapX + wrapW + 5, top + pos + bar, Ui.alpha(Ui.LINE_2, a))
        }

        // Kopf und Tabs über dem Inhalt
        context.createNewRootLayer()
        drawNav(context, a)
        drawToc(context, a)
    }

    // ================================================================== Kopf und Tabs

    private fun pathName(): String {
        val key = HhaRpg.path(RpgClientState.path)?.titleKey ?: "hha.rpg.path.${RpgClientState.path}"
        return RpgUi.tr(key)
    }

    private fun drawNav(ctx: DrawContext, a: Float) {
        val tr = textRenderer
        val nav = RpgUi.NAV
        val path = RpgUi.path()
        ctx.fill(0, 0, width, nav, Ui.alpha(0xD1000000.toInt(), a))
        Ui.hline(ctx, 0, width, nav - 1, Ui.alpha(Ui.LINE, a))
        Ui.logo(ctx, wrapX, 5, a)
        val end = Ui.display(ctx, tr, "H&H Armors", wrapX + 18, 7, Ui.alpha(Ui.TEXT, a))

        val cx2 = wrapX + wrapW
        val cx1 = cx2 - nav
        val hoverClose = Ui.inside(mx, my, cx1, 0, cx2, nav - 1)
        if (hoverClose) ctx.fill(cx1, 0, cx2, nav - 1, Ui.alpha(Ui.SURFACE_2, a))
        Ui.vline(ctx, cx1, 0, nav - 1, Ui.alpha(Ui.LINE, a))
        Ui.vline(ctx, cx2 - 1, 0, nav - 1, Ui.alpha(Ui.LINE, a))
        Ui.cross(ctx, cx1 + nav / 2, nav / 2, Ui.alpha(if (hoverClose) Ui.TEXT else Ui.MUTED, a))
        hits.add(Hit(cx1, 0, cx2, nav - 1) { close() })

        // Weg-Chip mit Punkt in Wegfarbe
        val chip = pathName()
        val cw = Ui.labelWidth(tr, chip) + 16
        val chipX = cx1 - 8 - cw
        Ui.frame(ctx, chipX, 5, chipX + cw, 17, Ui.alpha(Ui.LINE_2, a))
        ctx.fill(chipX + 5, 9, chipX + 8, 12, Ui.alpha(path, a))
        Ui.label(ctx, tr, chip, chipX + 11, 7, Ui.alpha(Ui.TEXT, a))
        val sub = RpgUi.tr("hha.rpg.journal.title")
        if (end + 10 + Ui.labelWidth(tr, sub) < chipX - 10) Ui.label(ctx, tr, sub, end + 10, 7, Ui.alpha(Ui.DIM, a))
    }

    private fun drawToc(ctx: DrawContext, a: Float) {
        val tr = textRenderer
        val nav = RpgUi.NAV
        val toc = RpgUi.TOC
        val path = RpgUi.path()
        ctx.fill(0, nav, width, nav + toc, Ui.alpha(0xD1000000.toInt(), a))
        Ui.hline(ctx, 0, width, nav + toc - 1, Ui.alpha(Ui.LINE, a))
        val ty = nav + 5
        val gap = if (wrapW < 360) 10 else 16
        var x = wrapX
        var selX = 0
        var selW = 0
        for (i in 0 until TABS) {
            val name = RpgUi.tr("hha.rpg.journal.tab.${TAB_KEYS[i]}")
            val w = Ui.labelWidth(tr, "0${i + 1}") + 4 + Ui.labelWidth(tr, name)
            val on = i == tab
            val hover = Ui.inside(mx, my, x - gap / 2, nav, x + w + gap / 2, nav + toc)
            val hv = springs.get("toc$i", if (hover || on) 1f else 0f, 16f, 0f)
            val tx = Ui.label(ctx, tr, "0${i + 1}", x, ty, Ui.alpha(if (on) path else Ui.DIM, a)) + 4
            Ui.label(ctx, tr, name, tx, ty, Ui.alpha(Ui.lerp(Ui.DIM, Ui.TEXT, hv), a))
            if (on) {
                selX = x
                selW = w - 1
            }
            hits.add(Hit(x - gap / 2, nav, x + w + gap / 2, nav + toc) { selectTab(i) })
            x += w + gap
        }
        val ux = springs.get("tocX", selX.toFloat(), 18f)
        val uw = springs.get("tocW", selW.toFloat(), 18f)
        ctx.fill(ux.roundToInt(), nav + toc - 2, (ux + uw).roundToInt(), nav + toc, Ui.alpha(path, a))
        val keyName = RpgHud.journalKey?.boundKeyLocalizedText?.string ?: "J"
        val hint = RpgUi.tr("hha.rpg.journal.hint", keyName)
        val hw = Ui.labelWidth(tr, hint)
        if (x - gap + 20 + hw <= wrapX + wrapW) Ui.label(ctx, tr, hint, wrapX + wrapW - hw, ty, Ui.alpha(Ui.DIM, a))
    }

    private fun selectTab(i: Int) {
        if (i == tab || i !in 0 until TABS) return
        tab = i
        tabAt = System.currentTimeMillis()
        RpgUi.click()
    }

    /** Abschnittskopf wie das Kontrollzentrum: Etikett „01 · …“, Pixel-Überschrift, ruhige Einleitung. */
    private fun pageHead(ctx: DrawContext, x: Int, y0: Int, w: Int, a: Float, index: Int, scope: String, title: String, lead: String): Int {
        val tr = textRenderer
        var y = y0
        Ui.label(ctx, tr, "0${index + 1} · $scope", x, y, Ui.alpha(RpgUi.path(), a))
        y += 12
        Ui.display(ctx, tr, title, x, y, Ui.alpha(Ui.TEXT, a), 2)
        y += 20
        val lines = tr.wrapLines(Text.literal(lead), min(w, 360)).take(3)
        lines.forEachIndexed { i, line -> ctx.drawText(tr, line, x, y + i * 10, Ui.alpha(Ui.MUTED, a), false) }
        y += lines.size * 10
        return y + 10
    }

    // ================================================================== 01 Kapitel

    private fun goalSteps(chapter: String): List<Int> =
        (0 until 8).filter { I18n.hasTranslation("hha.rpg.story.$chapter.goal.$it") }

    /** Laufender Zielschritt des laufenden Kapitels (aus `objectiveKey` …`.goal.<n>`), sonst −1. */
    private fun currentStep(chapter: String): Int {
        val key = RpgClientState.objectiveKey
        val prefix = "hha.rpg.story.$chapter.goal."
        return if (key.startsWith(prefix)) key.removePrefix(prefix).toIntOrNull() ?: -1 else -1
    }

    /** Zieltext ohne laufende Zahlen (z. B. „… · %s/%s“ fällt weg), für Schritte, die nicht gerade laufen. */
    private fun staticGoal(key: String): String {
        val raw = Language.getInstance().get(key)
        val cut = raw.indexOf('%')
        if (cut < 0) return raw
        val dot = raw.lastIndexOf(" · ", cut)
        return if (dot > 0) raw.substring(0, dot) else raw.replace(Regex("%(\\d+\\$)?s"), "…")
    }

    private fun tabChapters(ctx: DrawContext, x: Int, y0: Int, w: Int, a: Float): Int {
        val tr = textRenderer
        val path = RpgUi.path()
        var y = pageHead(ctx, x, y0, w, a, 0, pathName(), RpgUi.tr("hha.rpg.journal.tab.chapters"), RpgUi.tr("hha.rpg.journal.chapters.lead"))
        val chapters = RpgUi.chapters()
        val current = RpgClientState.chapter
        val selected = selectedChapter?.takeIf { id -> chapters.any { it.id == id } } ?: current
        fun stateOf(id: String) = when {
            id in RpgClientState.done -> RpgUi.Check.DONE
            id == current -> RpgUi.Check.CURRENT
            else -> RpgUi.Check.OPEN
        }

        val narrow = w < 380
        val listW = if (narrow) w else (w * 0.42f).toInt().coerceIn(150, 240)
        val listBottom = y + chapters.size * CHAPTER_ROW + 1
        ctx.fill(x, y, x + listW, listBottom, Ui.alpha(Ui.LINE, a))
        chapters.forEachIndexed { i, ch ->
            val ry = y + 1 + i * CHAPTER_ROW
            val state = stateOf(ch.id)
            val sel = ch.id == selected
            val hover = Ui.inside(mx, my, x, ry, x + listW, ry + CHAPTER_ROW - 1)
            ctx.fill(x + 1, ry, x + listW - 1, ry + CHAPTER_ROW - 1, Ui.alpha(if (sel || hover) Ui.SURFACE_2 else Ui.SURFACE, a))
            if (sel) ctx.fill(x + 1, ry, x + 3, ry + CHAPTER_ROW - 1, Ui.alpha(path, a))
            RpgUi.check(ctx, x + 9, ry + 9, state, path, a)
            Ui.label(ctx, tr, RpgUi.tr(ch.kickerKey), x + 23, ry + 4, Ui.alpha(if (state == RpgUi.Check.CURRENT) path else Ui.DIM, a))
            val stKey = when (state) {
                RpgUi.Check.DONE -> "done"
                RpgUi.Check.CURRENT -> "current"
                RpgUi.Check.OPEN -> "open"
            }
            val st = RpgUi.tr("hha.rpg.journal.status.$stKey")
            val stW = if (listW >= 190) Ui.labelWidth(tr, st) + 8 else 0
            Ui.display(ctx, tr, RpgUi.trimLabel(tr, RpgUi.tr(ch.titleKey), listW - 23 - stW - 8), x + 23, ry + 14,
                Ui.alpha(if (state == RpgUi.Check.OPEN) Ui.MUTED else Ui.TEXT, a))
            if (stW > 0) Ui.label(ctx, tr, st, x + listW - 8 - Ui.labelWidth(tr, st), ry + 9, Ui.alpha(if (state == RpgUi.Check.CURRENT) path else Ui.DIM, a))
            hits.add(Hit(x, ry, x + listW, ry + CHAPTER_ROW - 1) {
                selectedChapter = ch.id
                RpgUi.click()
            })
        }

        // Rechts (bzw. darunter bei schmaler Spalte): das gewählte Kapitel und seine Ziele
        val ch = chapters.firstOrNull { it.id == selected } ?: return listBottom
        val rx = if (narrow) x else x + listW + 12
        val rw = if (narrow) w else w - listW - 12
        var yy = if (narrow) listBottom + 16 else y
        val state = stateOf(ch.id)
        Ui.label(ctx, tr, RpgUi.tr(ch.kickerKey), rx, yy, Ui.alpha(path, a))
        yy += 12
        val title = RpgUi.tr(ch.titleKey)
        Ui.display(ctx, tr, title, rx, yy, Ui.alpha(Ui.TEXT, a), RpgUi.displayScale(tr, title, rw, 2, 1))
        yy += 20
        ctx.drawText(tr, RpgUi.trimText(tr, RpgUi.tr(ch.subKey), rw), rx, yy, Ui.alpha(Ui.MUTED, a), false)
        yy += 18
        Ui.label(ctx, tr, RpgUi.tr("hha.rpg.journal.goals"), rx, yy, Ui.alpha(path, a))
        yy += 13
        val steps = goalSteps(ch.id)
        if (steps.isEmpty()) {
            ctx.drawText(tr, RpgUi.tr("hha.rpg.journal.goals.none"), rx, yy, Ui.alpha(Ui.DIM, a), false)
            return max(listBottom, yy + 10)
        }
        val step = if (state == RpgUi.Check.CURRENT) currentStep(ch.id) else -1
        ctx.fill(rx, yy, rx + rw, yy + steps.size * GOAL_ROW + 1, Ui.alpha(Ui.LINE, a))
        steps.forEachIndexed { i, n ->
            val gy = yy + 1 + i * GOAL_ROW
            val gs = when {
                state == RpgUi.Check.DONE -> RpgUi.Check.DONE
                state == RpgUi.Check.OPEN -> RpgUi.Check.OPEN
                n < step -> RpgUi.Check.DONE
                n == step -> RpgUi.Check.CURRENT
                else -> RpgUi.Check.OPEN
            }
            ctx.fill(rx + 1, gy, rx + rw - 1, gy + GOAL_ROW - 1, Ui.alpha(if (gs == RpgUi.Check.CURRENT) Ui.SURFACE_2 else Ui.SURFACE, a))
            RpgUi.check(ctx, rx + 9, gy + 7, gs, path, a)
            val key = "hha.rpg.story.${ch.id}.goal.$n"
            val text = if (gs == RpgUi.Check.CURRENT) RpgClientState.objective?.string ?: staticGoal(key) else staticGoal(key)
            val color = when (gs) {
                RpgUi.Check.OPEN -> Ui.DIM
                RpgUi.Check.DONE -> Ui.MUTED
                RpgUi.Check.CURRENT -> Ui.TEXT
            }
            ctx.drawText(tr, RpgUi.trimText(tr, text, rw - 32), rx + 23, gy + 7, Ui.alpha(color, a), false)
        }
        yy += steps.size * GOAL_ROW + 1
        if (state == RpgUi.Check.DONE) {
            yy += 8
            ctx.drawText(tr, RpgUi.tr("hha.rpg.journal.goals.all_done"), rx, yy, Ui.alpha(Ui.DIM, a), false)
            yy += 10
        }
        return max(listBottom, yy)
    }

    // ================================================================== 02 Wissen

    private fun loreList(): List<String> {
        val journal = RpgClientState.journal
        val extra = journal.map { it.removeSuffix(".done") }.distinct().filter { it !in BOSSES }
        val hell = RpgClientState.path == "hell"
        val builtin = if (RpgClientState.path == "heaven" || hell) {
            BOSSES.filter { !hell || it !in OLYMPUS || it in journal }
        } else {
            BOSSES.filter { it in journal }
        }
        return builtin + extra
    }

    private fun tabLore(ctx: DrawContext, x: Int, y0: Int, w: Int, a: Float): Int {
        val tr = textRenderer
        val path = RpgUi.path()
        val journal = RpgClientState.journal
        val list = loreList()
        val known = list.filter { it in journal }
        var y = pageHead(ctx, x, y0, w, a, 1, RpgUi.tr("hha.rpg.journal.lore.count", known.size, list.size),
            RpgUi.tr("hha.rpg.journal.tab.lore"), RpgUi.tr("hha.rpg.journal.lore.lead"))
        if (known.isEmpty()) {
            ctx.drawText(tr, RpgUi.tr("hha.rpg.journal.lore.empty"), x, y, Ui.alpha(Ui.DIM, a), false)
            if (list.isEmpty()) return y + 10
            y += 20
        }
        // Standard: der jüngste Eintrag
        val recent = journal.lastOrNull { !it.endsWith(".done") && it in list }
        val sel = selectedLore?.takeIf { it in known } ?: recent ?: known.lastOrNull()

        val narrow = w < 380
        val listW = if (narrow) w else (w * 0.34f).toInt().coerceIn(130, 200)
        val listBottom = y + list.size * LORE_ROW + 1
        ctx.fill(x, y, x + listW, listBottom, Ui.alpha(Ui.LINE, a))
        list.forEachIndexed { i, b ->
            val ry = y + 1 + i * LORE_ROW
            val isKnown = b in journal
            val on = b == sel
            val hover = isKnown && Ui.inside(mx, my, x, ry, x + listW, ry + LORE_ROW - 1)
            ctx.fill(x + 1, ry, x + listW - 1, ry + LORE_ROW - 1, Ui.alpha(if (on || hover) Ui.SURFACE_2 else Ui.SURFACE, a))
            if (on) ctx.fill(x + 1, ry, x + 3, ry + LORE_ROW - 1, Ui.alpha(path, a))
            if (isKnown) {
                Ui.display(ctx, tr, RpgUi.trimLabel(tr, RpgHud.loreTitle(b).string, listW - 34), x + 10, ry + 6, Ui.alpha(if (on) Ui.TEXT else Ui.MUTED, a))
                if ("$b.done" in journal) RpgUi.check(ctx, x + listW - 15, ry + 6, RpgUi.Check.DONE, path, a)
                hits.add(Hit(x, ry, x + listW, ry + LORE_ROW - 1) {
                    selectedLore = b
                    RpgUi.click()
                })
            } else {
                Ui.label(ctx, tr, RpgUi.tr("hha.rpg.journal.lore.locked"), x + 10, ry + 6, Ui.alpha(Ui.DIM, a))
            }
        }
        if (sel == null) return listBottom

        val rx = if (narrow) x else x + listW + 14
        val rw = if (narrow) w else min(w - listW - 14, 340)
        var yy = if (narrow) listBottom + 16 else y
        Ui.label(ctx, tr, RpgUi.tr("hha.rpg.journal.tab.lore"), rx, yy, Ui.alpha(path, a))
        yy += 12
        val title = RpgHud.loreTitle(sel).string
        Ui.display(ctx, tr, title, rx, yy, Ui.alpha(Ui.TEXT, a), RpgUi.displayScale(tr, title, rw, 2, 1))
        yy += 22
        for (line in tr.wrapLines(Text.translatable("hha.rpg.lore.$sel.a"), rw)) {
            ctx.drawText(tr, line, rx, yy, Ui.alpha(Ui.TEXT, a), false)
            yy += 10
        }
        yy += 10
        val won = "$sel.done" in journal
        Ui.label(ctx, tr, RpgUi.tr("hha.rpg.journal.lore.after"), rx, yy, Ui.alpha(if (won) path else Ui.DIM, a))
        yy += 12
        if (won) {
            for (line in tr.wrapLines(Text.translatable("hha.rpg.lore.$sel.b"), rw)) {
                ctx.drawText(tr, line, rx, yy, Ui.alpha(Ui.TEXT, a), false)
                yy += 10
            }
        } else {
            ctx.drawText(tr, RpgUi.tr("hha.rpg.journal.lore.pending"), rx, yy, Ui.alpha(Ui.DIM, a), false)
            yy += 10
        }
        return max(listBottom, yy)
    }

    // ================================================================== 03 Gruppe

    private class Row(val name: String, val health: Float, val max: Float, val down: Boolean, val you: Boolean, val elsewhere: Boolean)

    private fun members(): List<Row> {
        val c = client
        val own = c?.player?.uuidAsString
        val here = c?.world?.registryKey?.value?.toString()
        val rows = RpgClientState.party.map {
            Row(it.name, it.health, it.maxHealth, it.down, it.uuid == own, here != null && it.world.isNotEmpty() && it.world != here)
        }.sortedByDescending { it.you }
        if (rows.any { it.you }) return rows
        // Noch keine Gruppenmeldung: wenigstens man selbst
        val p = c?.player ?: return rows
        return listOf(Row(p.name.string, p.health, p.maxHealth, p.isDead, true, false)) + rows
    }

    private fun tabParty(ctx: DrawContext, x: Int, y0: Int, w: Int, a: Float): Int {
        val tr = textRenderer
        val path = RpgUi.path()
        val rows = members()
        var y = pageHead(ctx, x, y0, w, a, 2, "${RpgUi.tr("hha.rpg.journal.party.members")} · ${rows.size}",
            RpgUi.tr("hha.rpg.journal.tab.party"), RpgUi.tr("hha.rpg.journal.party.lead"))

        // Lobby-Code mit Kopieren
        val boxW = min(w, 300)
        ctx.fill(x, y, x + boxW, y + CODE_BOX_H, Ui.alpha(Ui.SURFACE, a))
        Ui.frame(ctx, x, y, x + boxW, y + CODE_BOX_H, Ui.alpha(Ui.LINE_2, a))
        Ui.label(ctx, tr, RpgUi.tr("hha.rpg.journal.party.code"), x + 10, y + 7, Ui.alpha(Ui.DIM, a))
        val code = RpgNet.lobby?.code
        if (code != null) {
            val spaced = code.toCharArray().joinToString(" ")
            Ui.display(ctx, tr, spaced, x + 10, y + 18, Ui.alpha(Ui.TEXT, a), RpgUi.displayScale(tr, spaced, boxW - 110, 2, 1))
            val copied = System.currentTimeMillis() < copiedUntil
            val bl = RpgUi.tr(if (copied) "hha.rpg.journal.party.copied" else "hha.rpg.journal.party.copy")
            val bw = Ui.labelWidth(tr, bl) + 20
            val bx = x + boxW - 10 - bw
            val by = y + 16
            val hover = Ui.inside(mx, my, bx, by, bx + bw, by + 16)
            if (copied) {
                ctx.fill(bx, by, bx + bw, by + 16, Ui.alpha(path, a))
                Ui.label(ctx, tr, bl, bx + 10, by + 5, Ui.alpha(Ui.ON_ACCENT, a))
            } else {
                if (hover) ctx.fill(bx, by, bx + bw, by + 16, Ui.alpha(Ui.SURFACE_2, a))
                Ui.frame(ctx, bx, by, bx + bw, by + 16, Ui.alpha(if (hover) path else Ui.LINE_2, a))
                Ui.label(ctx, tr, bl, bx + 10, by + 5, Ui.alpha(Ui.TEXT, a))
            }
            hits.add(Hit(bx, by, bx + bw, by + 16) {
                client?.keyboard?.clipboard = code
                copiedUntil = System.currentTimeMillis() + COPIED_MS
                RpgUi.click()
            })
        } else {
            for ((i, line) in tr.wrapLines(Text.translatable("hha.rpg.journal.party.no_code"), boxW - 20).take(2).withIndex()) {
                ctx.drawText(tr, line, x + 10, y + 19 + i * 10, Ui.alpha(Ui.MUTED, a), false)
            }
        }
        y += CODE_BOX_H + 12

        // Mitglieder als Kachelraster
        if (rows.size <= 1) {
            ctx.drawText(tr, RpgUi.tr("hha.rpg.journal.party.alone"), x, y + rows.size * (MEMBER_ROW + 1) + 6, Ui.alpha(Ui.DIM, a), false)
        }
        val cols = if (w >= 420) 2 else 1
        val cw = (w - (cols - 1)) / cols
        val gridRows = (rows.size + cols - 1) / cols
        ctx.fill(x, y, x + w, y + gridRows * MEMBER_ROW + 1, Ui.alpha(Ui.LINE, a))
        rows.forEachIndexed { i, m ->
            val col = i % cols
            val cx = x + col * (cw + 1)
            val cy = y + 1 + (i / cols) * MEMBER_ROW
            ctx.fill(cx + (if (col == 0) 1 else 0), cy, cx + cw - (if (col == cols - 1) 1 else 0), cy + MEMBER_ROW - 1, Ui.alpha(Ui.SURFACE, a))
            val st = when {
                m.down -> RpgUi.tr("hha.rpg.hud.down")
                m.elsewhere -> RpgUi.tr("hha.rpg.journal.party.elsewhere")
                else -> "${ceil(m.health).toInt()} / ${ceil(m.max).toInt()}"
            }
            val sw = Ui.labelWidth(tr, st)
            val youW = if (m.you) Ui.labelWidth(tr, RpgUi.tr("hha.rpg.journal.party.you")) + 14 else 0
            val end = Ui.label(ctx, tr, RpgUi.trimLabel(tr, m.name, cw - 20 - sw - youW - 8), cx + 10, cy + 6, Ui.alpha(if (m.down) Ui.DIM else Ui.TEXT, a))
            if (m.you) {
                val you = RpgUi.tr("hha.rpg.journal.party.you")
                val yw = Ui.labelWidth(tr, you) + 8
                Ui.frame(ctx, end + 6, cy + 4, end + 6 + yw, cy + 15, Ui.alpha(path, a))
                Ui.label(ctx, tr, you, end + 10, cy + 6, Ui.alpha(path, a))
            }
            Ui.label(ctx, tr, st, cx + cw - 10 - sw, cy + 6, Ui.alpha(Ui.MUTED, a))
            ctx.fill(cx + 10, cy + 19, cx + cw - 10, cy + 21, Ui.alpha(RpgUi.TRACK, a))
            if (!m.down && m.max > 0f) {
                val f = (m.health / m.max).coerceIn(0f, 1f)
                ctx.fill(cx + 10, cy + 19, cx + 10 + ((cw - 20) * f).roundToInt(), cy + 21, Ui.alpha(if (f <= RpgUi.LOW_HEALTH) Ui.BAD else path, a))
            }
        }
        return y + gridRows * MEMBER_ROW + 1 + if (rows.size <= 1) 24 else 0
    }

    // ================================================================== Eingabe

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            val top = RpgUi.NAV + RpgUi.TOC
            // Inhalt ist verschoben (Aufsteigen beim Tabwechsel); Kopf und Tabs nicht
            for (hit in hits.asReversed()) {
                val inHead = hit.y2 <= top
                if (!inHead && click.y() < top) continue
                if (Ui.inside(click.x(), click.y(), hit.x1, hit.y1, hit.x2, hit.y2)) {
                    hit.action()
                    return true
                }
            }
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        scrollTarget[tab] -= (verticalAmount * 24).toFloat()
        return true
    }

    override fun keyPressed(input: KeyInput): Boolean {
        if (RpgHud.journalKey?.matchesKey(input) == true) {
            close()
            return true
        }
        val n = input.key() - GLFW.GLFW_KEY_1
        if (n in 0 until TABS) {
            selectTab(n)
            return true
        }
        if (input.key() == GLFW.GLFW_KEY_TAB) {
            selectTab((tab + 1) % TABS)
            return true
        }
        return super.keyPressed(input)
    }
}
