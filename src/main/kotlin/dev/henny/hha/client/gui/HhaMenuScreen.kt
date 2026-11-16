package dev.henny.hha.client.gui

import dev.henny.hha.HhaItems
import dev.henny.hha.api.client.HudCooldowns
import dev.henny.hha.api.set.HhaSets
import dev.henny.hha.client.ClientConfigCache
import dev.henny.hha.client.CooldownHud
import dev.henny.hha.client.HhaClientConfig
import dev.henny.hha.net.GuiStatePayload
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.PlayerSkinDrawer
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.input.KeyInput
import net.minecraft.entity.EquipmentSlot
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Das HHA-Kontrollzentrum: Einstellungen (Server und Client), Status mit 3D-Spielermodell und Werkzeuge.
 *
 * Aufgebaut wie eine Seite der Studio-Website: schwarze Fläche über der weichgezeichneten Welt, oben die Kopfleiste
 * (Logo, Status, Schließen) und die Tab-Leiste mit Nummern (01–05 = Tasten 1–5), darunter eine zentrierte Spalte
 * mit feinen Hilfslinien an den Rändern. Jeder Tab beginnt mit Etikett, Pixel-Überschrift und Einleitung; Inhalte
 * stehen in Kachelrastern mit 1-px-Linien. Eckige Schalter und Knöpfe, ruhiges Einblenden statt Effekten.
 * Layout und Klickflächen entstehen jedes Bild neu (Immediate Mode). Maße und Farben gleichen der HTML-Attrappe
 * `tmp/ui_redesign/mock/menu.js`.
 */
class HhaMenuScreen(initialTab: Int = 0) : Screen(Text.translatable("hha.gui.title")) {

    private class Hit(
        val x1: Int, val y1: Int, val x2: Int, val y2: Int,
        val clip: IntArray?,
        val click: (Double, Double) -> Unit,
        val drag: ((Double, Double) -> Unit)? = null,
        val release: (() -> Unit)? = null,
    )

    private val springs = Ui.Springs()
    private var lastNanos = 0L
    private var openedAt = 0L
    private var closingAt = -1L
    private var tab = initialTab.coerceIn(0, TABS - 1)
    private var tabAt = 0L
    private val scroll = FloatArray(TABS)
    private val scrollTarget = FloatArray(TABS)

    /** Ausgewählte Quelle in „Fähigkeiten“ und „Werte“: `hha` oder die Mod-ID eines Addons. */
    private var source = "hha"
    private val contentHeight = IntArray(TABS)
    private val hits = ArrayList<Hit>()
    private var dragging: Hit? = null
    private var previewYaw = -25f
    private var previewDragging = false
    private var confirmResetUntil = 0L
    private val localNumbers = HashMap<String, Double>()
    private val localToggles = HashMap<String, Boolean>()
    private var seenState: Any? = null
    private var seconds = 0f

    // Layout des aktuellen Bilds
    private var wrapX = 0
    private var wrapW = 0
    private var rise = 0f
    private var clip = IntArray(4)
    private var mx = -1.0
    private var my = -1.0
    private var viewHeight = 200
    private var accent = Ui.GOLD

    companion object {
        const val TABS = 5
        private val TAB_KEYS = listOf("overview", "abilities", "values", "visuals", "tools")
        private val HUD = { name: String -> dev.henny.hha.Hha.id("hud/$name") }

        /** Kopfleiste (Website: .nav) und Tab-Leiste (.toc). */
        private const val NAV = 22
        private const val TOC = 16

        /** Breite der Inhaltsspalte höchstens (Website: --wrap). */
        private const val MAX_WRAP = 700

        private const val TILE_H = 50
        private const val ROW_H = 46

        /** Abstand zwischen zwei Gruppen (vorher 14: wirkte gedrängt). */
        private const val GROUP_GAP = 24
        private const val KIT_H = 72
        private const val TRUST_H = 26
    }

    fun selectTab(index: Int) {
        if (index == tab || index !in 0 until TABS) return
        tab = index
        tabAt = System.currentTimeMillis()
    }

    override fun init() {
        val now = System.currentTimeMillis()
        if (openedAt == 0L) {
            openedAt = now
            tabAt = now
            GuiClient.request()
        }
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        if (closingAt < 0) closingAt = System.currentTimeMillis()
    }

    override fun removed() {
        Gui3d.clear()
    }

    // ================================================================== Zeichnen

    private fun fade(): Float {
        val now = System.currentTimeMillis()
        if (closingAt >= 0) return (1f - (now - closingAt) / 140f).coerceIn(0f, 1f)
        return Ui.easeOutCubic((now - openedAt) / 220f)
    }

    private fun layout() {
        val pad = if (width < 520) 12 else 24
        wrapW = min(width - 2 * pad, MAX_WRAP)
        wrapX = (width - wrapW) / 2
    }

    /** Akzent der Seite: in der Übersicht die Farbe des getragenen Sets, sonst HHA-Gold. */
    private fun pageAccent(): Int = if (tab == 0) wornSet()?.third ?: Ui.GOLD else Ui.GOLD

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        applyBlur(context)
        layout()
        val fade = fade()
        accent = springsColor(pageAccent())
        // Website: schwarzer Grund, oben ein Hauch der Akzentfarbe (.mod-hero), feine Linien an der Spalte
        context.fill(0, 0, width, height, Ui.alpha(0xE0000000.toInt(), fade))
        context.fillGradient(0, 0, width, height * 2 / 3, Ui.alpha(accent, 0.13f * fade), Ui.withAlpha(accent, 0))
        if (width - wrapW >= 8) {
            Ui.vline(context, wrapX - 1, 0, height, Ui.alpha(Ui.LINE, fade))
            Ui.vline(context, wrapX + wrapW, 0, height, Ui.alpha(Ui.LINE, fade))
        }
    }

    /** Akzent gleitet weich zur neuen Farbe (z. B. beim Tabwechsel); beim Öffnen sofort richtig. */
    private fun springsColor(target: Int): Int {
        if (!accentReady) {
            accentReady = true
            accentFrom = target
            lastAccentTarget = target
            springs.set("accentT", 1f)
        }
        if (target != lastAccentTarget) {
            accentFrom = accent
            lastAccentTarget = target
            springs.set("accentT", 0f)
        }
        return Ui.lerp(accentFrom, target, springs.get("accentT", 1f, 10f, 1f))
    }
    private var accentReady = false
    private var accentFrom = Ui.GOLD
    private var lastAccentTarget = Ui.GOLD

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val nanos = System.nanoTime()
        springs.dt = if (lastNanos == 0L) 0.016f else ((nanos - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = nanos
        seconds += springs.dt
        val now = System.currentTimeMillis()

        if (closingAt >= 0 && now - closingAt > 140) {
            client?.setScreen(null)
            return
        }
        val state = GuiClient.state
        if (state !== seenState) {
            seenState = state
            localNumbers.clear()
            localToggles.clear()
        }
        layout()
        val fade = fade()
        mx = mouseX.toDouble()
        my = mouseY.toDouble()
        hits.clear()

        // Inhalt: steigt beim Öffnen und Tabwechsel ein paar Pixel auf (Website: [data-reveal])
        val top = NAV + TOC
        val view = height - top
        viewHeight = view
        val tabT = Ui.easeOutCubic((now - tabAt) / 260f)
        rise = (1f - tabT) * 6f + (1f - Ui.easeOutCubic((now - openedAt) / 320f)) * 8f
        clip = intArrayOf(wrapX - 4, top, wrapX + wrapW + 4, height)
        scrollTarget[tab] = scrollTarget[tab].coerceIn(0f, max(0f, (contentHeight[tab] - view).toFloat()))
        scroll[tab] = springs.get("scroll$tab", scrollTarget[tab], 18f)
        context.enableScissor(clip[0], clip[1], clip[2], clip[3])
        val m = context.matrices
        m.pushMatrix()
        m.translate(0f, rise)
        val y0 = top + 12 - scroll[tab].roundToInt()
        val a = fade * tabT.coerceAtLeast(0.15f)
        contentHeight[tab] = when (tab) {
            0 -> tabOverview(context, wrapX, y0, wrapW, a)
            1 -> tabToggles(context, wrapX, y0, wrapW, a, state)
            2 -> tabNumbers(context, wrapX, y0, wrapW, a, state)
            3 -> tabVisuals(context, wrapX, y0, wrapW, a)
            else -> tabTools(context, wrapX, y0, wrapW, a, state)
        } - y0 + 12
        m.popMatrix()
        context.disableScissor()

        // Scrollleiste rechts neben der Spalte
        if (contentHeight[tab] > view) {
            val bar = max(16, view * view / contentHeight[tab])
            val pos = ((view - bar) * (scroll[tab] / max(1, contentHeight[tab] - view))).toInt()
            context.fill(wrapX + wrapW + 3, top + pos, wrapX + wrapW + 5, top + pos + bar, Ui.alpha(Ui.LINE_2, fade))
        }

        // Kopf und Tabs in einer neuen Ebene über dem Inhalt (auch über 3D-Modellen), ebenso die Meldungen
        context.createNewRootLayer()
        drawNav(context, fade, state)
        drawToc(context, fade)
        drawToasts(context, fade)
    }

    // ------------------------------------------------------------------ Kopfleiste (.nav) und Tabs (.toc)

    private fun drawNav(ctx: DrawContext, a: Float, state: GuiStatePayload?) {
        val tr = textRenderer
        ctx.fill(0, 0, width, NAV, Ui.alpha(0xD1000000.toInt(), a))
        Ui.hline(ctx, 0, width, NAV - 1, Ui.alpha(Ui.LINE, a))
        Ui.logo(ctx, wrapX, 5, a)
        val logoEnd = Ui.display(ctx, tr, "H&H Armors", wrapX + 18, 7, Ui.alpha(Ui.TEXT, a))

        // Schließen: quadratischer Knopf ganz rechts (.icon-btn)
        val cx2 = wrapX + wrapW
        val cx1 = cx2 - NAV
        val hoverClose = Ui.inside(mx, my, cx1, 0, cx2, NAV - 1)
        if (hoverClose) ctx.fill(cx1, 0, cx2, NAV - 1, Ui.alpha(Ui.SURFACE_2, a))
        Ui.vline(ctx, cx1, 0, NAV - 1, Ui.alpha(Ui.LINE, a))
        Ui.vline(ctx, cx2 - 1, 0, NAV - 1, Ui.alpha(Ui.LINE, a))
        Ui.cross(ctx, cx1 + NAV / 2, NAV / 2, Ui.alpha(if (hoverClose) Ui.TEXT else Ui.MUTED, a))
        hits.add(Hit(cx1, 0, cx2, NAV - 1, null, { _, _ -> close() }))

        // Status-Chip (.chip): Verbindung und Rolle
        val (chip, chipColor) = when {
            !GuiClient.serverAvailable() -> Text.translatable("hha.gui.chip.offline") to Ui.BAD
            state == null -> Text.translatable("hha.gui.chip.loading") to Ui.MUTED
            state.admin -> Text.translatable("hha.gui.chip.admin") to Ui.GOLD
            else -> Text.translatable("hha.gui.chip.player") to Ui.TEAL
        }
        val cw = Ui.labelWidth(tr, chip.string) + 16
        val chipX = cx1 - 8 - cw
        Ui.frame(ctx, chipX, 5, chipX + cw, 17, Ui.alpha(Ui.LINE_2, a))
        ctx.fill(chipX + 5, 9, chipX + 8, 12, Ui.alpha(chipColor, a))
        Ui.label(ctx, tr, chip.string, chipX + 11, 7, Ui.alpha(Ui.TEXT, a))

        // Untertitel, wenn Platz ist
        val sub = Text.translatable("hha.gui.subtitle").string
        if (logoEnd + 10 + Ui.labelWidth(tr, sub) < chipX - 10) Ui.label(ctx, tr, sub, logoEnd + 10, 7, Ui.alpha(Ui.DIM, a))
    }

    private class TocLayout(val numbers: Boolean, val gap: Int, val widths: IntArray)

    /** Tabs mit Nummern, wenn sie passen; sonst enger, dann ohne Nummern. */
    private fun tocLayout(): TocLayout {
        val tr = textRenderer
        val names = TAB_KEYS.map { Text.translatable("hha.gui.tab.$it").string }
        for ((nums, gap) in listOf(true to 16, true to 10, true to 7, false to 12, false to 7)) {
            val widths = IntArray(TABS) { i -> (if (nums) Ui.labelWidth(tr, "0${i + 1}") + 4 else 0) + Ui.labelWidth(tr, names[i]) }
            if (widths.sum() + gap * (TABS - 1) <= wrapW || (!nums && gap == 7)) return TocLayout(nums, gap, widths)
        }
        error("unerreichbar")
    }

    private fun drawToc(ctx: DrawContext, a: Float) {
        val tr = textRenderer
        ctx.fill(0, NAV, width, NAV + TOC, Ui.alpha(0xD1000000.toInt(), a))
        Ui.hline(ctx, 0, width, NAV + TOC - 1, Ui.alpha(Ui.LINE, a))
        val layout = tocLayout()
        val ty = NAV + 5
        var x = wrapX
        var selX = 0
        var selW = 0
        for (i in 0 until TABS) {
            val w = layout.widths[i]
            val hover = Ui.inside(mx, my, x - layout.gap / 2, NAV, x + w + layout.gap / 2, NAV + TOC)
            val on = i == tab
            val hv = springs.get("toc$i", if (hover || on) 1f else 0f, 16f, 0f)
            var tx = x
            if (layout.numbers) tx = Ui.label(ctx, tr, "0${i + 1}", x, ty, Ui.alpha(if (on) accent else Ui.DIM, a)) + 4
            Ui.label(ctx, tr, Text.translatable("hha.gui.tab.${TAB_KEYS[i]}").string, tx, ty, Ui.alpha(Ui.lerp(Ui.DIM, Ui.TEXT, hv), a))
            if (on) { selX = x; selW = w - 1 }
            hits.add(Hit(x - layout.gap / 2, NAV, x + w + layout.gap / 2, NAV + TOC, null, { _, _ -> selectTab(i) }))
            x += w + layout.gap
        }
        // Unterstrich gleitet zum aktiven Tab (Website: .toc a.on)
        val ux = springs.get("tocX", selX.toFloat(), 18f)
        val uw = springs.get("tocW", selW.toFloat(), 18f)
        ctx.fill(ux.roundToInt(), NAV + TOC - 2, (ux + uw).roundToInt(), NAV + TOC, Ui.alpha(accent, a))
        val hint = Text.translatable("hha.gui.hint_keys").string
        val hw = Ui.labelWidth(tr, hint)
        if (x - layout.gap + 20 + hw <= wrapX + wrapW) Ui.label(ctx, tr, hint, wrapX + wrapW - hw, ty, Ui.alpha(Ui.DIM, a))
    }

    // ------------------------------------------------------------------ Bausteine

    /** Sanftes Einblenden Reihe für Reihe (Website: [data-reveal]); tiefe Reihen warten höchstens acht Stufen. */
    private fun rowFade(alpha: Float, index: Int): Float =
        alpha * ((System.currentTimeMillis() - tabAt) / 1000f * 5f - index.coerceAtMost(8) * 0.12f).coerceIn(0f, 1f)

    private fun insideClip(): Boolean = Ui.inside(mx, my, clip[0], clip[1], clip[2], clip[3])

    /** Abschnittskopf wie auf der Website: Etikett „02 · SERVER“, Pixel-Überschrift, ruhige Einleitung. */
    private fun pageHead(ctx: DrawContext, x: Int, y0: Int, w: Int, a: Float, index: Int, scope: Text, title: Text, lead: Text?): Int {
        val tr = textRenderer
        var y = y0
        Ui.label(ctx, tr, "0${index + 1} · ${scope.string}", x, y, Ui.alpha(accent, a))
        y += 12
        Ui.display(ctx, tr, title.string, x, y, Ui.alpha(Ui.TEXT, a), 2)
        y += 20
        if (lead != null && lead.string.isNotEmpty()) {
            val lines = tr.wrapLines(lead, min(w, 360)).take(3)
            lines.forEachIndexed { i, line -> ctx.drawText(tr, line, x, y + i * 10, Ui.alpha(Ui.MUTED, a), false) }
            y += lines.size * 10
        }
        return y + 10
    }

    /** Hinweis (.trick): heller Hauch, links ein 2-px-Streifen in [color]. */
    private fun notice(ctx: DrawContext, x: Int, y: Int, w: Int, text: Text, color: Int, a: Float): Int {
        val lines = textRenderer.wrapLines(text, w - 18)
        val h = 10 + lines.size * 10
        ctx.fill(x, y, x + w, y + h, Ui.alpha(0x0AFFFFFF, a))
        ctx.fill(x, y, x + 2, y + h, Ui.alpha(color, a))
        lines.forEachIndexed { i, line -> ctx.drawText(textRenderer, line, x + 10, y + 6 + i * 10, Ui.alpha(Ui.TEXT, a), false) }
        return y + h + 12
    }

    /** Gruppenkopf (.moves-label): Etikett in Gruppenfarbe, Zusatz gedämpft. */
    private fun groupHead(ctx: DrawContext, x: Int, y: Int, title: Text, color: Int, a: Float, extra: Text? = null): Int {
        val end = Ui.label(ctx, textRenderer, title.string, x, y, Ui.alpha(color, a))
        if (extra != null) Ui.label(ctx, textRenderer, extra.string, end + 6, y, Ui.alpha(Ui.DIM, a))
        return y + 15
    }

    /**
     * Kachelraster (.tiles): Fläche in Linienfarbe, darauf die Kacheln mit 1 px Abstand. Liefert die Zellen
     * (x1, y1, x2, y2); leere Zellen der letzten Reihe werden still gefüllt.
     */
    private fun grid(ctx: DrawContext, x: Int, y: Int, w: Int, count: Int, cols: Int, rowH: Int, a: Float): List<IntArray> {
        val rows = (count + cols - 1) / cols
        ctx.fill(x, y, x + w, y + rows * rowH + 1, Ui.alpha(Ui.LINE, a))
        val cells = ArrayList<IntArray>(count)
        for (i in 0 until rows * cols) {
            val c = i % cols
            val r = i / cols
            val x1 = x + (c * (w - 1) / cols.toFloat()).roundToInt() + 1
            val x2 = x + ((c + 1) * (w - 1) / cols.toFloat()).roundToInt()
            val y1 = y + r * rowH + 1
            val y2 = y + (r + 1) * rowH
            if (i >= count) ctx.fill(x1, y1, x2, y2, Ui.alpha(Ui.SURFACE, a)) else cells.add(intArrayOf(x1, y1, x2, y2))
        }
        return cells
    }

    private fun gridHeight(count: Int, cols: Int, rowH: Int): Int = ((count + cols - 1) / cols) * rowH + 1

    /** Kachelgrund: Fläche, beim Überfahren etwas heller. Gibt den Hover-Wert zurück. */
    private fun tile(ctx: DrawContext, cell: IntArray, id: String, a: Float): Float {
        val hover = Ui.inside(mx, my, cell[0], cell[1], cell[2], cell[3]) && insideClip()
        val hv = springs.get("tile:$id", if (hover) 1f else 0f, 18f, 0f)
        ctx.fill(cell[0], cell[1], cell[2], cell[3], Ui.alpha(Ui.lerp(Ui.SURFACE, Ui.SURFACE_2, hv), a))
        return hv
    }

    /** Eckiger Schalter 18 × 10: aus = Rahmen mit grauem Knopf links, an = Akzentfläche mit dunklem Knopf rechts. */
    private fun switch(ctx: DrawContext, x: Int, y: Int, on: Boolean, color: Int, a: Float, id: String, enabled: Boolean, onClick: () -> Unit) {
        val t = springs.get("sw:$id", if (on) 1f else 0f, 16f)
        val hover = enabled && Ui.inside(mx, my, x - 3, y - 3, x + 21, y + 13) && insideClip()
        val k = if (enabled) 1f else 0.4f
        if (t > 0.01f) ctx.fill(x, y, x + 18, y + 10, Ui.alpha(color, a * k * t))
        Ui.frame(ctx, x, y, x + 18, y + 10, Ui.alpha(Ui.lerp(if (hover) Ui.TEXT else Ui.LINE_2, color, t), a * k))
        val kx = x + 2 + (t * 8).roundToInt()
        ctx.fill(kx, y + 2, kx + 6, y + 8, Ui.alpha(Ui.lerp(if (hover) Ui.TEXT else Ui.DIM, Ui.ON_ACCENT, t), a * k))
        if (enabled) hits.add(Hit(x - 3, y - 3, x + 21, y + 13, clip, { _, _ -> onClick() }))
    }

    private enum class Kind { PRIMARY, GHOST, DANGER }

    /** Eckiger Knopf (.btn), Höhe 16: primär = Akzentfläche mit dunkler Schrift, Ghost = 1-px-Rand, Gefahr = rote Schrift. */
    private fun button(ctx: DrawContext, x: Int, y: Int, w: Int, label: Text, kind: Kind, color: Int, a: Float, id: String, enabled: Boolean = true, onClick: () -> Unit): Int {
        val h = 16
        val hover = enabled && Ui.inside(mx, my, x, y, x + w, y + h) && insideClip()
        val hv = springs.get("btn:$id", if (hover) 1f else 0f, 18f, 0f)
        val k = if (enabled) 1f else 0.35f
        val lift = if (kind == Kind.PRIMARY) hv.roundToInt() else 0
        val text = when (kind) {
            Kind.PRIMARY -> {
                ctx.fill(x, y - lift, x + w, y + h - lift, Ui.alpha(Ui.brighten(color, 0.12f * hv), a * k))
                Ui.ON_ACCENT
            }
            else -> {
                if (hv > 0.01f) ctx.fill(x, y, x + w, y + h, Ui.alpha(0x0AFFFFFF, a * hv))
                val hoverBorder = if (kind == Kind.DANGER) Ui.BAD else Ui.TEXT
                Ui.frame(ctx, x, y, x + w, y + h, Ui.alpha(Ui.lerp(Ui.LINE_2, hoverBorder, hv), a * k))
                if (kind == Kind.DANGER) Ui.BAD else Ui.TEXT
            }
        }
        val tw = Ui.labelWidth(textRenderer, label.string)
        Ui.label(ctx, textRenderer, label.string, x + (w - tw + 1) / 2, y + 4 - lift, Ui.alpha(text, a * k))
        if (enabled) hits.add(Hit(x, y, x + w, y + h, clip, { _, _ -> onClick() }))
        return x + w
    }

    private fun slider(
        ctx: DrawContext, x: Int, y: Int, w: Int, value: Double, range: ConfigMeta.Range, color: Int, a: Float, id: String, enabled: Boolean,
        onChange: (Double) -> Unit, onRelease: () -> Unit,
    ) {
        val t = ((value - range.min) / (range.max - range.min)).toFloat().coerceIn(0f, 1f)
        val st = springs.get("sl:$id", t, 22f)
        val k = if (enabled) 1f else 0.4f
        ctx.fill(x, y + 3, x + w, y + 5, Ui.alpha(Ui.LINE_2, a * k))
        val fx = x + (st * w).roundToInt()
        if (fx > x) ctx.fill(x, y + 3, fx, y + 5, Ui.alpha(color, a * k))
        val active = dragging?.let { it.x1 == x - 4 && it.y1 == y - 3 } == true
        val hover = enabled && (active || Ui.inside(mx, my, x - 4, y - 3, x + w + 4, y + 11) && insideClip())
        ctx.fill(fx - 2, y, fx + 2, y + 8, Ui.alpha(if (hover) color else Ui.TEXT, a * k))
        if (!enabled) return
        val set = { mxv: Double ->
            val p = ((mxv - x) / w).coerceIn(0.0, 1.0)
            var v = range.min + p * (range.max - range.min)
            v = (v / range.step).roundToInt() * range.step
            onChange(v.coerceIn(range.min, range.max))
        }
        hits.add(Hit(x - 4, y - 3, x + w + 4, y + 11, clip, { mxv, _ -> set(mxv) }, { _, _ -> set(mx) }, onRelease))
    }

    private fun trimmed(text: Text, width: Int): String = trimmed(text.string, width)

    private fun trimmed(s: String, width: Int): String {
        if (textRenderer.getWidth(s) <= width) return s
        return textRenderer.trimToWidth(s, max(0, width - textRenderer.getWidth("…"))) + "…"
    }

    /** Beschreibung in höchstens zwei Zeilen; die zweite wird bei Bedarf gekürzt. */
    private fun descLines(ctx: DrawContext, text: Text, x: Int, y: Int, width: Int, color: Int, a: Float) {
        if (text.string.isEmpty()) return
        val lines = textRenderer.wrapLines(text, width)
        lines.take(2).forEachIndexed { i, line ->
            if (i == 1 && lines.size > 2) {
                val rest = lines.drop(1).joinToString(" ") { l -> buildString { l.accept { _, _, cp -> appendCodePoint(cp); true } } }
                ctx.drawText(textRenderer, trimmed(rest, width), x, y + i * 10, Ui.alpha(color, a), false)
            } else {
                ctx.drawText(textRenderer, line, x, y + i * 10, Ui.alpha(color, a), false)
            }
        }
    }

    /** Schalter-Kachel: Name, Beschreibung, Schalter rechts. Aus = Name gedämpft. */
    private fun toggleTile(ctx: DrawContext, cell: IntArray, name: Text, desc: Text, on: Boolean, color: Int, a: Float, id: String, enabled: Boolean, onClick: () -> Unit) {
        tile(ctx, cell, id, a)
        val tw = cell[2] - cell[0] - 44
        ctx.drawText(textRenderer, trimmed(name, tw), cell[0] + 10, cell[1] + 9, Ui.alpha(if (on) Ui.TEXT else Ui.MUTED, a), false)
        descLines(ctx, desc, cell[0] + 10, cell[1] + 22, tw - 2, if (on) Ui.MUTED else Ui.DIM, a)
        switch(ctx, cell[2] - 28, (cell[1] + cell[3]) / 2 - 5, on, color, a, id, enabled, onClick)
    }

    /** Zahlen-Zeile: Name und Beschreibung, Wert rechts (geändert = Akzent), ↺ zum Zurücksetzen, Regler darunter. */
    private fun numberRow(
        ctx: DrawContext, cell: IntArray, name: Text, desc: Text, value: Double, def: Double, range: ConfigMeta.Range, color: Int, a: Float,
        id: String, enabled: Boolean, onChange: (Double) -> Unit, onRelease: () -> Unit, onReset: () -> Unit,
    ) {
        val tr = textRenderer
        val (x1, y1, x2) = Triple(cell[0], cell[1], cell[2])
        tile(ctx, cell, id, a)
        val changed = abs(value - def) > 1e-9
        val valueText = ConfigMeta.format(value, range.unit)
        val vw = tr.getWidth(valueText)
        ctx.drawText(tr, valueText, x2 - 10 - vw, y1 + 8, Ui.alpha(if (changed) color else Ui.MUTED, a), false)
        var nameEnd = x2 - 10 - vw - 8
        if (changed && enabled) {
            val rx = nameEnd - 10
            val rh = Ui.inside(mx, my, rx - 2, y1 + 5, rx + 10, y1 + 17) && insideClip()
            ctx.drawText(tr, "↺", rx, y1 + 8, Ui.alpha(if (rh) Ui.TEXT else Ui.DIM, a), false)
            hits.add(Hit(rx - 2, y1 + 5, rx + 10, y1 + 17, clip, { _, _ -> onReset() }))
            nameEnd = rx - 6
        }
        ctx.drawText(tr, trimmed(name, nameEnd - x1 - 10), x1 + 10, y1 + 8, Ui.alpha(Ui.TEXT, a), false)
        if (desc.string.isNotEmpty()) {
            ctx.drawText(tr, trimmed(desc, x2 - x1 - 20), x1 + 10, y1 + 19, Ui.alpha(Ui.DIM, a), false)
        }
        slider(ctx, x1 + 10, y1 + 33, x2 - x1 - 20, value, range, color, a, id, enabled, onChange, onRelease)
    }

    /** Rechnet ein Rechteck aus Inhaltskoordinaten in Bildschirmkoordinaten (3D-Modelle folgen der Matrix nicht). */
    private fun screenBox(x1: Int, y1: Int, x2: Int, y2: Int): IntArray {
        val dy = rise.roundToInt()
        return intArrayOf(x1, y1 + dy, x2, y2 + dy)
    }

    // ------------------------------------------------------------------ Tab 01: Übersicht

    private class Cd(val icon: Identifier, val name: Text, val stack: ItemStack, val key: String?, val color: Int)

    private fun cooldowns(): List<Cd> {
        val player = client?.player ?: return emptyList()
        val list = ArrayList<Cd>()
        val chest = player.getEquippedStack(EquipmentSlot.CHEST)
        val legs = player.getEquippedStack(EquipmentSlot.LEGS)
        if (chest.isOf(HhaItems.HEAVEN_CHESTPLATE)) list.add(Cd(HUD("beam_light"), Text.translatable("hha.gui.cd.light_beam"), chest, "beam_cooldown", Ui.GOLD))
        if (chest.isOf(HhaItems.HELL_CHESTPLATE)) list.add(Cd(HUD("beam_lava"), Text.translatable("hha.gui.cd.lava_beam"), chest, "beam_cooldown", Ui.SOUL))
        if (legs.isOf(HhaItems.HELL_LEGGINGS)) list.add(Cd(HUD("camp"), Text.translatable("hha.gui.cd.fire_camp"), legs, "fire_camp_cooldown", Ui.SOUL))
        for (stack in listOf(player.mainHandStack, player.offHandStack)) {
            when {
                stack.isOf(HhaItems.HEAVENS_SWORD) -> list.add(Cd(HUD("wave"), Text.translatable("hha.gui.cd.light_wave"), stack, "light_wave_cooldown", Ui.GOLD))
                stack.isOf(HhaItems.HELLS_SWORD) -> list.add(Cd(HUD("sword"), Text.translatable("hha.gui.cd.sword_buff"), stack, "sword_buff_cooldown", Ui.SOUL))
                stack.isOf(HhaItems.HEAVENS_MACE) -> list.add(Cd(HUD("mace"), Text.translatable("hha.gui.cd.mace_throw"), stack, "mace_throw_cooldown", Ui.GOLD))
                stack.isOf(HhaItems.HELLS_MACE) -> list.add(Cd(HUD("chain"), Text.translatable("hha.gui.cd.chain"), stack, "chain_recharge", Ui.SOUL))
            }
        }
        for (entry in HudCooldowns.all()) {
            val stack = entry.provider(player) ?: continue
            if (!stack.isEmpty) list.add(Cd(entry.icon, stack.name, stack, null, 0xFF000000.toInt() or (entry.accentColor and 0xFFFFFF)))
        }
        return list
    }

    /** Welches Set trägt der Spieler (meiste Teile), wie viele Teile, Farbe. */
    private var wornArmorSet: dev.henny.hha.api.set.ArmorSet? = null

    private fun wornSet(): Triple<Text, Int, Int>? {
        val player = client?.player ?: return null
        var best: Triple<Text, Int, Int>? = null
        wornArmorSet = null
        for (set in HhaSets.all()) {
            val count = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
                .count { slot -> set.piece(slot)?.let { player.getEquippedStack(slot).isOf(it) } == true }
            if (count == 0 || (best != null && count <= best.second)) continue
            val color = when (set.id) { HhaSets.HEAVEN_ID -> Ui.GOLD; HhaSets.HELL_ID -> Ui.SOUL; else -> Ui.BRAND }
            val name = Text.translatableWithFallback("hha.gui.set.${set.id.namespace}.${set.id.path}", set.id.path.replaceFirstChar(Char::uppercaseChar))
            best = Triple(name, count, color)
            wornArmorSet = set
        }
        return best
    }

    private fun tabOverview(ctx: DrawContext, x: Int, y0: Int, w: Int, a: Float): Int {
        val player = client?.player ?: return y0
        val tr = textRenderer
        val worn = wornSet()
        val setColor = worn?.third ?: Ui.BRAND

        // Links: freigestellte 3D-Bühne (.armor-stage) ohne Rahmen, Lichtfleck unter den Füßen
        val pw = (w * 0.38f).toInt().coerceIn(120, 210)
        val ph = max(150, viewHeight - 24)
        val pa = rowFade(a, 0)
        val fx = x + pw / 2
        val fy = y0 + ph - 24
        val pulse = 0.5f + 0.5f * sin(seconds * 1.6f)
        Ui.ellipse(ctx, fx, fy, pw / 2 - 6, 14, Ui.alpha(accent, 0.035f * pa))
        Ui.ellipse(ctx, fx, fy, pw / 2 - 22, 10, Ui.alpha(accent, (0.045f + 0.01f * pulse) * pa))
        Ui.ellipse(ctx, fx, fy, pw / 2 - 40, 6, Ui.alpha(accent, (0.065f + 0.015f * pulse) * pa))
        if (!previewDragging) previewYaw += springs.dt * 18f
        Ui.label(ctx, tr, Text.translatable("hha.gui.drag_rotate").string, x, y0 + ph - 8, Ui.alpha(Ui.DIM, pa))
        hits.add(Hit(x, y0, x + pw, y0 + ph, clip, { _, _ -> previewDragging = true }, { dx, _ -> previewYaw += dx.toFloat() * 1.6f }, { previewDragging = false }))
        if (pa > 0.6f) {
            // Modell so groß, dass Kopf und Füße in die Bühne passen; Kasten mittig um das Modell
            val size = min((ph - 32) / 1.86f, pw / 1.25f)
            val center = fy - (0.93f * size).toInt()
            val box = screenBox(x, y0, x + pw, 2 * center - y0)
            Gui3d.living(ctx, player, box[0], box[1], box[2], box[3], size, previewYaw, 6f, 0f, seconds * 20f)
            // Waffen schweben links und rechts
            val setWeapons = wornArmorSet?.weapons.orEmpty()
            val (left, right) = when {
                worn?.third == Ui.GOLD -> ItemStack(HhaItems.HEAVENS_SWORD) to ItemStack(HhaItems.HEAVENS_MACE)
                worn?.third == Ui.SOUL -> ItemStack(HhaItems.HELLS_SWORD) to ItemStack(HhaItems.HELLS_MACE)
                setWeapons.isNotEmpty() -> ItemStack(setWeapons[0]) to ItemStack(setWeapons.getOrElse(1) { setWeapons[0] })
                else -> ItemStack(HhaItems.HEAVENS_SWORD) to ItemStack(HhaItems.HELLS_SWORD)
            }
            val l = screenBox(x + 2, fy - 48, x + 34, fy - 4)
            val r = screenBox(x + pw - 34, fy - 48, x + pw - 2, fy - 4)
            Gui3d.item(ctx, left, "ov_l", l[0], l[1], l[2], l[3], 28f, seconds * 20f)
            Gui3d.item(ctx, right, "ov_r", r[0], r[1], r[2], r[3], 28f, seconds * 20f + 40f)
        }

        // Rechts: Set, Ausrüstung, Fähigkeiten
        val rx = x + pw + 16
        val rw = w - pw - 16
        var y = y0
        val sa = rowFade(a, 1)
        Ui.label(ctx, tr, Text.translatable("hha.gui.your_set").string, rx, y, Ui.alpha(accent, sa))
        y += 12
        val setName = worn?.first ?: Text.translatable("hha.gui.no_set")
        Ui.display(ctx, tr, trimmedDisplay(setName.string, rw / 2), rx, y, Ui.alpha(if (worn != null) setColor else Ui.TEXT, sa), 2)
        y += 19
        val pieces = worn?.second ?: 0
        ctx.drawText(tr, Text.translatable("hha.gui.pieces", pieces), rx, y, Ui.alpha(Ui.TEXT, sa), false)
        y += 10
        if (pieces == 4) {
            val lines = tr.wrapLines(Text.translatable("hha.gui.full_set"), rw).take(2)
            lines.forEachIndexed { i, line -> ctx.drawText(tr, line, rx, y + i * 10, Ui.alpha(Ui.MUTED, sa), false) }
            y += lines.size * 10
        }
        y += 7

        // Ausrüstung als Hotbar (.hotbar / .hb-slot): 1-px-Linien, Set-Teile mit Akzent-Unterkante
        val ea = rowFade(a, 2)
        val slot = 20
        ctx.fill(rx, y, rx + 4 * (slot + 1) + 1, y + slot + 2, Ui.alpha(Ui.LINE, ea))
        listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET).forEachIndexed { i, equip ->
            val sx = rx + 1 + i * (slot + 1)
            ctx.fill(sx, y + 1, sx + slot, y + slot + 1, Ui.alpha(Ui.SURFACE, ea))
            val stack = player.getEquippedStack(equip)
            if (!stack.isEmpty) {
                if (ea > 0.5f) ctx.drawItem(stack, sx + 2, y + 3)
                val ofSet = wornArmorSet?.piece(equip)?.let { stack.isOf(it) } == true
                if (ofSet) ctx.fill(sx, y + slot - 1, sx + slot, y + slot + 1, Ui.alpha(setColor, ea))
            }
        }
        y += slot + 11

        // Fähigkeiten als Liste (.req): Symbol, Name, rechts „Bereit“ oder Restzeit, Fortschritt als Unterkante
        val ca = rowFade(a, 3)
        Ui.label(ctx, tr, Text.translatable("hha.gui.abilities_ready").string, rx, y, Ui.alpha(Ui.DIM, ca))
        y += 11
        val cds = cooldowns()
        val rh = 21
        val rows = cds.size + 1 + (if (cds.isEmpty()) 1 else 0)
        ctx.fill(rx, y, rx + rw, y + rows * rh + 1, Ui.alpha(Ui.LINE, ca))
        var ry = y + 1
        fun rowBase() = ctx.fill(rx + 1, ry, rx + rw - 1, ry + rh - 1, Ui.alpha(Ui.SURFACE, ca))
        if (cds.isEmpty()) {
            rowBase()
            ctx.drawText(tr, trimmed(Text.translatable("hha.gui.no_abilities"), rw - 16), rx + 8, ry + 6, Ui.alpha(Ui.DIM, ca), false)
            ry += rh
        }
        for (cd in cds) {
            rowBase()
            ctx.drawGuiTexture(RenderPipelines.GUI_TEXTURED, cd.icon, rx + 4, ry + 2, 16, 16)
            val progress = player.itemCooldownManager.getCooldownProgress(cd.stack, 0f)
            if (progress <= 0f) {
                val ready = Text.translatable("hha.gui.ready").string
                val sw = Ui.labelWidth(tr, ready)
                ctx.drawText(tr, trimmed(cd.name, rw - 40 - sw), rx + 25, ry + 6, Ui.alpha(Ui.TEXT, ca), false)
                Ui.label(ctx, tr, ready, rx + rw - 7 - sw, ry + 6, Ui.alpha(cd.color, ca))
            } else {
                val label = cd.key?.let { ConfigMeta.format(progress * ClientConfigCache.num(it), ConfigMeta.Unit.TICKS) } ?: "${(progress * 100).toInt()} %"
                val sw = tr.getWidth(label)
                ctx.drawText(tr, trimmed(cd.name, rw - 40 - sw), rx + 25, ry + 6, Ui.alpha(Ui.TEXT, ca), false)
                ctx.drawText(tr, label, rx + rw - 7 - sw, ry + 6, Ui.alpha(Ui.MUTED, ca), false)
                ctx.fill(rx + 1, ry + rh - 3, rx + rw - 1, ry + rh - 1, Ui.alpha(Ui.LINE, ca))
                ctx.fill(rx + 1, ry + rh - 3, rx + 1 + ((rw - 2) * (1f - progress)).roundToInt(), ry + rh - 1, Ui.alpha(Ui.lerp(cd.color, 0xFF000000.toInt(), 0.45f), ca))
            }
            ry += rh
        }
        // Ultra als letzte Zeile
        rowBase()
        ctx.drawGuiTexture(RenderPipelines.GUI_TEXTURED, HUD("ultra"), rx + 4, ry + 2, 16, 16)
        val ultra = CooldownHud.ultraRemainingTicks
        if (ultra > 0) {
            val status = Text.translatable("hha.gui.ultra_active", (ultra + 19) / 20).string
            val sw = Ui.labelWidth(tr, status)
            ctx.drawText(tr, trimmed(Text.translatable("hha.gui.ultra"), rw - 40 - sw), rx + 25, ry + 6, Ui.alpha(Ui.TEXT, ca), false)
            Ui.label(ctx, tr, status, rx + rw - 7 - sw, ry + 6, Ui.alpha(Ui.GOLD, ca))
            val frac = (ultra / ClientConfigCache.num("ultra_duration").coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
            ctx.fill(rx + 1, ry + rh - 3, rx + 1 + ((rw - 2) * frac).roundToInt(), ry + rh - 1, Ui.alpha(Ui.GOLD, ca))
        } else {
            val status = trimmed(Text.translatable("hha.gui.ultra_key"), rw / 2)
            val sw = tr.getWidth(status)
            ctx.drawText(tr, trimmed(Text.translatable("hha.gui.ultra"), rw - 40 - sw), rx + 25, ry + 6, Ui.alpha(Ui.TEXT, ca), false)
            ctx.drawText(tr, status, rx + rw - 7 - sw, ry + 6, Ui.alpha(Ui.DIM, ca), false)
        }
        ry += rh
        return max(y0 + ph, ry)
    }

    private fun trimmedDisplay(s: String, width: Int): String {
        var t = s
        while (t.length > 1 && Ui.displayWidth(textRenderer, t) > width) t = t.dropLast(1)
        return t
    }

    // ------------------------------------------------------------------ Quellen: HHA oder ein Addon

    /** Quellen eines Server-Tabs: immer H&H Armors, dazu jedes Addon mit eigenen Schlüsseln (`addonid.key`). */
    private fun sources(keys: Collection<String>, known: List<ConfigMeta.Group>): List<String> =
        listOf("hha") + ConfigMeta.extraGroups(keys, known).map { it.id }.filter { it != "other" }.sorted()

    /** Gruppen der gewählten Quelle (HHA: die eingebauten Gruppen und Ungruppiertes; Addon: dessen Gruppe). */
    private fun groupsOf(keys: Collection<String>, known: List<ConfigMeta.Group>, sources: List<String>): List<ConfigMeta.Group> {
        if (source !in sources) source = "hha"
        val extra = ConfigMeta.extraGroups(keys, known)
        return if (source == "hha") known + extra.filter { it.id == "other" } else extra.filter { it.id == source }
    }

    /**
     * Umschalter zwischen H&H Armors und den Addons (eckige Chips wie die Filter der Website). Bei nur einer Quelle
     * erscheint er nicht.
     */
    private fun sourceBar(ctx: DrawContext, x: Int, y0: Int, w: Int, a: Float, sources: List<String>): Int {
        if (sources.size < 2) return y0
        val tr = textRenderer
        var y = y0
        var cx = x
        val h = 16
        for (src in sources) {
            val title = if (src == "hha") "H&H Armors" else ConfigMeta.groupTitle(src).string
            val tw = Ui.labelWidth(tr, title) + 20
            if (cx > x && cx + tw > x + w) { cx = x; y += h + 6 }
            val on = src == source
            val hover = Ui.inside(mx, my, cx, y, cx + tw, y + h) && insideClip()
            val hv = springs.get("src:$src", if (hover || on) 1f else 0f, 18f, 0f)
            if (on) ctx.fill(cx, y, cx + tw, y + h, Ui.alpha(accent, a))
            else Ui.frame(ctx, cx, y, cx + tw, y + h, Ui.alpha(Ui.lerp(Ui.LINE_2, Ui.TEXT, hv * 0.6f), a))
            Ui.label(ctx, tr, title, cx + 10, y + 4, Ui.alpha(if (on) Ui.ON_ACCENT else Ui.lerp(Ui.MUTED, Ui.TEXT, hv), a))
            hits.add(Hit(cx, y, cx + tw, y + h, clip, { _, _ ->
                if (source != src) {
                    source = src
                    scrollTarget[tab] = 0f
                    tabAt = System.currentTimeMillis()
                }
            }))
            cx += tw + 6
        }
        return y + h + 16
    }

    // ------------------------------------------------------------------ Tab 02: Fähigkeiten (Server-Schalter)

    /** Kopf eines Server-Tabs; Offline, Laden und Nur-Lesen erscheinen als Hinweis darunter. */
    private fun serverHead(ctx: DrawContext, x: Int, y0: Int, w: Int, a: Float, state: GuiStatePayload?, index: Int, leadFallback: String): Int {
        val head = rowFade(a, 0)
        var y = pageHead(
            ctx, x, y0, w, head, index, Text.translatableWithFallback("hha.gui.scope.server", "Server"),
            Text.translatable("hha.gui.tab.${TAB_KEYS[index]}"), Text.translatableWithFallback("hha.gui.lead.${TAB_KEYS[index]}", leadFallback),
        )
        y = when {
            !GuiClient.serverAvailable() -> notice(ctx, x, y, w, Text.translatable("hha.gui.notice.offline"), Ui.BAD, head)
            state == null -> notice(ctx, x, y, w, Text.translatable("hha.gui.notice.loading"), Ui.MUTED, head)
            !state.admin -> notice(ctx, x, y, w, Text.translatable("hha.gui.notice.read_only"), Ui.TEAL, head)
            else -> y
        }
        return y
    }

    private fun tabToggles(ctx: DrawContext, x: Int, y0: Int, w: Int, a: Float, state: GuiStatePayload?): Int {
        var y = serverHead(ctx, x, y0, w, a, state, 1, "Schalte einzelne Fähigkeiten für alle Spieler an oder aus.")
        state ?: return y
        val sources = sources(state.toggles.keys, ConfigMeta.TOGGLE_GROUPS)
        y = sourceBar(ctx, x, y, w, rowFade(a, 0), sources)
        val groups = groupsOf(state.toggles.keys, ConfigMeta.TOGGLE_GROUPS, sources)
        var index = 1
        for (group in groups) {
            val keys = group.keys.filter { it in state.toggles }
            if (keys.isEmpty()) continue
            val ga = rowFade(a, index++)
            val active = keys.count { localToggles[it] ?: state.toggles[it] == true }
            y = groupHead(ctx, x, y, ConfigMeta.groupTitle(group.id), group.color, ga, Text.translatable("hha.gui.active_count", active, keys.size))
            val cols = if (w >= 420) 2 else 1
            val cells = grid(ctx, x, y, w, keys.size, cols, TILE_H, ga)
            keys.forEachIndexed { i, key ->
                val on = localToggles[key] ?: state.toggles[key] == true
                toggleTile(ctx, cells[i], ConfigMeta.name(key), ConfigMeta.desc(key), on, group.color, rowFade(a, index + i / cols), key, state.admin) {
                    localToggles[key] = !on
                    GuiClient.send("toggle", key, (!on).toString())
                }
            }
            index += (keys.size + cols - 1) / cols
            y += gridHeight(keys.size, cols, TILE_H) + GROUP_GAP
        }
        return y
    }

    // ------------------------------------------------------------------ Tab 03: Werte (Server-Zahlen)

    private fun tabNumbers(ctx: DrawContext, x: Int, y0: Int, w: Int, a: Float, state: GuiStatePayload?): Int {
        var y = serverHead(ctx, x, y0, w, a, state, 2, "Schaden, Reichweiten und Abklingzeiten. Ziehen zum Ändern, ↺ setzt zurück.")
        state ?: return y
        val sources = sources(state.numbers.keys, ConfigMeta.NUMBER_GROUPS)
        y = sourceBar(ctx, x, y, w, rowFade(a, 0), sources)
        val groups = groupsOf(state.numbers.keys, ConfigMeta.NUMBER_GROUPS, sources)
        var index = 1
        for (group in groups) {
            val keys = group.keys.filter { it in state.numbers }
            if (keys.isEmpty()) continue
            y = groupHead(ctx, x, y, ConfigMeta.groupTitle(group.id), group.color, rowFade(a, index++))
            val cols = if (w >= 560) 2 else 1
            val cells = grid(ctx, x, y, w, keys.size, cols, ROW_H, rowFade(a, index))
            keys.forEachIndexed { i, key ->
                val def = state.numberDefaults[key] ?: 0.0
                val value = localNumbers[key] ?: state.numbers[key] ?: def
                val baseRange = ConfigMeta.range(key, def)
                val range = if (value > baseRange.max) ConfigMeta.Range(baseRange.min, value * 1.5, baseRange.step, baseRange.unit) else baseRange
                numberRow(
                    ctx, cells[i], ConfigMeta.name(key), ConfigMeta.desc(key), value, def, range, group.color, rowFade(a, index + i / cols),
                    key, state.admin,
                    { v -> localNumbers[key] = v },
                    { localNumbers[key]?.let { GuiClient.send("set", key, it.toString()) } },
                    {
                        localNumbers[key] = def
                        GuiClient.send("set", key, def.toString())
                    },
                )
            }
            index += (keys.size + cols - 1) / cols
            y += gridHeight(keys.size, cols, ROW_H) + GROUP_GAP
        }
        return y
    }

    // ------------------------------------------------------------------ Tab 04: Darstellung (Client)

    private fun tabVisuals(ctx: DrawContext, x: Int, y0: Int, w: Int, a: Float): Int {
        val tr = textRenderer
        var y = pageHead(
            ctx, x, y0, w, rowFade(a, 0), 3, Text.translatableWithFallback("hha.gui.scope.client", "Client"), Text.translatable("hha.gui.tab.visuals"),
            Text.translatableWithFallback("hha.gui.lead.visuals", "Nur für dich und sofort aktiv: Effekte, Bildfilter und die Cooldown-Leiste."),
        )
        val toggles = listOf("vfx_3d", "screen_shake", "tartaros_shader", "portal_shader", "hud_enabled", "hud_pulse", "hud_background", "ultra_bar")
        y = groupHead(ctx, x, y, Text.translatable("hha.gui.group.client"), Ui.GOLD, rowFade(a, 1), Text.translatable("hha.gui.client_note"))
        val cols = if (w >= 360) 2 else 1
        val cells = grid(ctx, x, y, w, toggles.size, cols, TILE_H, rowFade(a, 1))
        toggles.forEachIndexed { i, key ->
            val on = HhaClientConfig.enabled(key)
            toggleTile(
                ctx, cells[i], Text.translatable("hha.gui.client.$key"), Text.translatableWithFallback("hha.gui.client.$key.desc", ""), on, Ui.GOLD,
                rowFade(a, 2 + i / cols), "c:$key", true,
            ) { HhaClientConfig.setToggle(key, !on) }
        }
        y += gridHeight(toggles.size, cols, TILE_H) + 14

        val numbers = listOf(
            "hud_scale" to ConfigMeta.Range(0.5, 2.0, 0.05, ConfigMeta.Unit.MULTIPLIER),
            "particle_density" to ConfigMeta.Range(0.0, 1.0, 0.05, ConfigMeta.Unit.FRACTION),
            "hud_offset_x" to ConfigMeta.Range(-300.0, 300.0, 1.0, ConfigMeta.Unit.NONE),
            "hud_offset_y" to ConfigMeta.Range(-300.0, 300.0, 1.0, ConfigMeta.Unit.NONE),
        )
        val ncols = if (w >= 480) 2 else 1
        val na = rowFade(a, 6)
        val ncells = grid(ctx, x, y, w, numbers.size, ncols, ROW_H, na)
        numbers.forEachIndexed { i, (key, range) ->
            val def = HhaClientConfig.NUMBER_DEFAULTS[key] ?: 0.0
            numberRow(
                ctx, ncells[i], Text.translatable("hha.gui.client.$key"), Text.empty(), HhaClientConfig.num(key), def, range, Ui.GOLD,
                rowFade(a, 6 + i / ncols), "c:$key", true,
                { v -> HhaClientConfig.setNumber(key, v) }, {}, { HhaClientConfig.setNumber(key, def) },
            )
        }
        y += gridHeight(numbers.size, ncols, ROW_H) + 14

        // Vorschau der Cooldown-Leiste mit den aktuellen Einstellungen, gezeichnet wie im Spiel
        val pa = rowFade(a, 9)
        y = groupHead(ctx, x, y, Text.translatable("hha.gui.hud_preview"), Ui.DIM, pa)
        val ph = 46
        ctx.fill(x, y, x + w, y + ph, Ui.alpha(Ui.LINE, pa))
        ctx.fill(x + 1, y + 1, x + w - 1, y + ph - 1, Ui.alpha(Ui.SURFACE, pa))
        if (HhaClientConfig.enabled("hud_enabled")) {
            val s = HhaClientConfig.numF("hud_scale").coerceIn(0.5f, 2f)
            val icons = listOf(Triple("beam_light", Ui.GOLD, 0f), Triple("wave", Ui.GOLD, 0.45f), Triple("camp", CooldownHud.SOUL_COLOR, 0f))
            val total = (icons.size * CooldownHud.STEP - (CooldownHud.STEP - CooldownHud.SLOT)) * s
            val pulse = 0.5f + 0.5f * sin(seconds * 7f)
            ctx.matrices.pushMatrix()
            ctx.matrices.translate(x + (w - total) / 2f, y + (ph - CooldownHud.SLOT * s) / 2f)
            ctx.matrices.scale(s, s)
            icons.forEachIndexed { i, (icon, color, progress) ->
                CooldownHud.drawSlot(ctx, i * CooldownHud.STEP, 0, HUD(icon), color, progress, pulse, pa)
            }
            ctx.matrices.popMatrix()
        } else {
            ctx.drawText(tr, Text.translatable("hha.gui.hud_off"), x + 8, y + 19, Ui.alpha(Ui.DIM, pa), false)
        }
        y += ph + 12
        val resetLabel = Text.translatable("hha.gui.client_reset")
        button(ctx, x, y, max(120, Ui.labelWidth(tr, resetLabel.string) + 24), resetLabel, Kind.DANGER, Ui.BAD, rowFade(a, 10), "client_reset") { HhaClientConfig.reset() }
        return y + 16
    }

    // ------------------------------------------------------------------ Tab 05: Werkzeuge

    private fun tabTools(ctx: DrawContext, x: Int, y0: Int, w: Int, a: Float, state: GuiStatePayload?): Int {
        var y = serverHead(ctx, x, y0, w, a, state, 4, "Wen deine Fähigkeiten verschonen, Kits zum Ausprobieren und Werkzeuge für Admins.")
        state ?: return y
        val tr = textRenderer
        var index = 1

        // Vertrauen: Spieler als Kacheln mit Kopf, Name und Schalter (Gold = sicher)
        y = groupHead(ctx, x, y, Text.translatable("hha.gui.trust"), Ui.GOLD, rowFade(a, index++), Text.translatable("hha.gui.trust_note"))
        val me = client?.player?.uuid
        val online = client?.networkHandler?.playerList?.filter { it.profile.id != me }?.sortedBy { it.profile.name.lowercase() } ?: emptyList()
        val trusted = state.trusted.associateBy { it.uuid }
        if (online.isEmpty()) {
            ctx.drawText(tr, Text.translatable("hha.gui.trust_alone"), x, y + 2, Ui.alpha(Ui.DIM, rowFade(a, index)), false)
            y += 16
        } else {
            val cols = if (w >= 300) 2 else 1
            val cells = grid(ctx, x, y, w, online.size, cols, TRUST_H, rowFade(a, index))
            online.forEachIndexed { i, entry ->
                val cell = cells[i]
                val ra = rowFade(a, index + i / cols)
                val id = entry.profile.id.toString()
                val isTrusted = localToggles["trust:$id"] ?: (id in trusted)
                tile(ctx, cell, "trust:$id", ra)
                if (ra > 0.3f) PlayerSkinDrawer.draw(ctx, entry.skinTextures, cell[0] + 6, cell[1] + 5, 16)
                ctx.drawText(tr, trimmed(entry.profile.name, cell[2] - cell[0] - 64), cell[0] + 28, cell[1] + 9, Ui.alpha(Ui.TEXT, ra), false)
                switch(ctx, cell[2] - 26, cell[1] + 8, isTrusted, Ui.GOLD, ra, "trust:$id", true) {
                    localToggles["trust:$id"] = !isTrusted
                    GuiClient.send(if (isTrusted) "trust_remove" else "trust_add", id, entry.profile.name)
                }
            }
            index += (online.size + cols - 1) / cols
            y += gridHeight(online.size, cols, TRUST_H) + 6
        }
        // Offline Vertraute als Chips mit Kreuz
        val offline = state.trusted.filter { t -> online.none { it.profile.id.toString() == t.uuid } }
        if (offline.isNotEmpty()) {
            val oa = rowFade(a, index++)
            var bx = Ui.label(ctx, tr, Text.translatable("hha.gui.trust_offline").string, x, y + 4, Ui.alpha(Ui.DIM, oa)) + 8
            for (t in offline) {
                val bw = Ui.labelWidth(tr, t.name) + 22
                if (bx + bw > x + w) { bx = x; y += 18 }
                val hover = Ui.inside(mx, my, bx, y, bx + bw, y + 14) && insideClip()
                Ui.frame(ctx, bx, y, bx + bw, y + 14, Ui.alpha(if (hover) Ui.BAD else Ui.LINE_2, oa))
                Ui.label(ctx, tr, t.name, bx + 6, y + 3, Ui.alpha(Ui.TEXT, oa))
                Ui.cross(ctx, bx + bw - 8, y + 7, Ui.alpha(if (hover) Ui.BAD else Ui.DIM, oa))
                hits.add(Hit(bx, y, bx + bw, y + 14, clip, { _, _ -> GuiClient.send("trust_remove", t.uuid, t.name) }))
                bx += bw + 6
            }
            y += 24
        }
        y += 8

        // Kits: nummerierte Kacheln mit schwebender Waffe (.tiles + .tile-tag)
        if (state.kits.isNotEmpty()) {
            y = groupHead(ctx, x, y, Text.translatable("hha.gui.kits"), Ui.GOLD, rowFade(a, index++), Text.translatable("hha.gui.kits_note"))
            val cols = max(2, min(4, w / 96))
            val cells = grid(ctx, x, y, w, state.kits.size, cols, KIT_H, rowFade(a, index))
            state.kits.forEachIndexed { i, kit ->
                val cell = cells[i]
                val ka = rowFade(a, index + i / cols)
                val color = kitColor(kit.key)
                val hv = tile(ctx, cell, "kit:${kit.key}", ka)
                Ui.label(ctx, tr, "%02d".format(i + 1), cell[0] + 8, cell[1] + 7, Ui.alpha(color, ka))
                ctx.drawText(tr, "→", cell[2] - 14 + (hv * 2).roundToInt(), cell[1] + 6, Ui.alpha(Ui.lerp(Ui.DIM, Ui.TEXT, hv), ka), false)
                val weapon = Identifier.tryParse(kit.weapon)?.let { Registries.ITEM.getOptionalValue(it).orElse(null) }?.let { ItemStack(it) } ?: ItemStack.EMPTY
                if (ka > 0.6f && !weapon.isEmpty) {
                    val b = screenBox(cell[0] + 4, cell[1] + 10, cell[2] - 4, cell[1] + 54)
                    Gui3d.item(ctx, weapon, "kit:${kit.key}", b[0], b[1], b[2], b[3], 42f + 6f * hv, seconds * (20f + 30f * hv) + i * 17f, 18f)
                }
                val name = Text.translatableWithFallback("hha.gui.kit.${kit.key}", kit.key.substringAfter('.').replaceFirstChar(Char::uppercaseChar))
                ctx.drawText(tr, trimmed(name, cell[2] - cell[0] - 16), cell[0] + 8, cell[3] - 15, Ui.alpha(Ui.TEXT, ka), false)
                if (hv > 0.01f) ctx.fill(cell[0], cell[3] - 2, cell[2], cell[3], Ui.alpha(color, ka * hv))
                hits.add(Hit(cell[0], cell[1], cell[2], cell[3], clip, { _, _ -> GuiClient.send("kit", kit.key) }))
            }
            val rows = (state.kits.size + cols - 1) / cols
            index += rows
            y += gridHeight(state.kits.size, cols, KIT_H) + 14
        }

        // Admin: Kämpfe und Konfiguration
        if (state.admin) {
            y = groupHead(ctx, x, y, Text.translatable("hha.gui.admin_fights"), Ui.TEAL, rowFade(a, index++))
            val fa = rowFade(a, index++)
            val hermes = state.fights and 1 != 0
            val hades = state.fights and 2 != 0
            val status = when {
                hermes -> Text.translatable("hha.gui.fight_hermes")
                hades -> Text.translatable("hha.gui.fight_hades")
                else -> Text.translatable("hha.gui.fight_none")
            }
            ctx.fill(x, y + 2, x + 4, y + 6, Ui.alpha(if (hermes || hades) Ui.GOOD else Ui.DIM, fa))
            ctx.drawText(tr, status, x + 10, y, Ui.alpha(Ui.MUTED, fa), false)
            y += 14
            y = buttonGrid(ctx, x, y, w, fa, listOf(
                Btn("hermes_start", Kind.PRIMARY, Ui.GOLD, !hermes) { GuiClient.send("hermes_start") },
                Btn("hermes_skip", Kind.GHOST, Ui.TEXT, hermes) { GuiClient.send("hermes_skip") },
                Btn("hermes_stop", Kind.DANGER, Ui.BAD, hermes) { GuiClient.send("hermes_stop") },
                Btn("hades_stop", Kind.DANGER, Ui.BAD, hades) { GuiClient.send("hades_stop") },
            ))

            y = groupHead(ctx, x, y, Text.translatable("hha.gui.admin_config"), Ui.TEAL, rowFade(a, index++))
            val ca = rowFade(a, index++)
            val confirming = System.currentTimeMillis() < confirmResetUntil
            y = buttonGrid(ctx, x, y, w, ca, listOf(
                Btn("config_save", Kind.GHOST, Ui.TEXT) { GuiClient.send("config_save") },
                Btn("config_reload", Kind.GHOST, Ui.TEXT) { GuiClient.send("config_reload") },
                Btn("recipes_reload", Kind.GHOST, Ui.TEXT) { GuiClient.send("recipes_reload") },
                // Zurücksetzen fragt nach: erst rot umrandet, dann rot gefüllt „Sicher? Nochmal“
                Btn(if (confirming) "confirm" else "config_reset", if (confirming) Kind.PRIMARY else Kind.DANGER, Ui.BAD) {
                    if (System.currentTimeMillis() < confirmResetUntil) {
                        confirmResetUntil = 0L
                        GuiClient.send("config_reset")
                    } else {
                        confirmResetUntil = System.currentTimeMillis() + 3000
                    }
                },
            ))
        }
        return y
    }

    private fun kitColor(key: String): Int = when (key) {
        "heaven" -> Ui.GOLD
        "hell" -> Ui.SOUL
        "diamond" -> 0xFF6FE6E0.toInt()
        "netherite" -> 0xFFA89BAE.toInt()
        else -> Ui.BRAND
    }

    private class Btn(val key: String, val kind: Kind, val color: Int, val enabled: Boolean = true, val action: () -> Unit)

    /** Knöpfe als Raster: so viele Spalten, wie mit lesbarer Beschriftung passen. */
    private fun buttonGrid(ctx: DrawContext, x: Int, y: Int, w: Int, a: Float, buttons: List<Btn>): Int {
        val widest = buttons.maxOf { Ui.labelWidth(textRenderer, Text.translatable("hha.gui.btn.${it.key}").string) } + 20
        val cols = ((w + 6) / (widest + 6)).coerceIn(1, buttons.size)
        val bw = (w - (cols - 1) * 6) / cols
        buttons.forEachIndexed { i, b ->
            val bx = x + (i % cols) * (bw + 6)
            val by = y + (i / cols) * 22
            button(ctx, bx, by, bw, Text.translatable("hha.gui.btn.${b.key}"), b.kind, b.color, a, b.key, b.enabled, b.action)
        }
        return y + ((buttons.size + cols - 1) / cols) * 22 + 10
    }

    // ------------------------------------------------------------------ Meldungen

    /** Meldungen unten rechts: dunkle Fläche, 1-px-Rand, links ein Streifen (grün = ok, rot = Fehler). */
    private fun drawToasts(ctx: DrawContext, fade: Float) {
        val now = System.currentTimeMillis()
        GuiClient.toasts.removeIf { now - it.time > 3200 }
        var y = height - 8
        for (toast in GuiClient.toasts.reversed()) {
            val age = (now - toast.time) / 1000f
            val inT = Ui.easeOutCubic(age / 0.22f)
            val outT = ((3.2f - age) / 0.3f).coerceIn(0f, 1f)
            val a = fade * inT * outT
            val w = min(260, textRenderer.getWidth(toast.text) + 22)
            val h = 18
            val x = width - 8 - w + ((1f - inT) * 8).toInt()
            y -= h + 4
            ctx.fill(x, y, x + w, y + h, Ui.alpha(0xF20B0B0E.toInt(), a))
            Ui.frame(ctx, x, y, x + w, y + h, Ui.alpha(Ui.LINE_2, a))
            ctx.fill(x, y, x + 2, y + h, Ui.alpha(if (toast.ok) Ui.GOOD else Ui.BAD, a))
            ctx.drawText(textRenderer, trimmed(toast.text, w - 18), x + 10, y + 5, Ui.alpha(Ui.TEXT, a), false)
        }
    }

    // ================================================================== Eingabe

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() != 0) return super.mouseClicked(click, doubled)
        val px = click.x()
        val py = click.y()
        for (hit in hits.asReversed()) {
            if (!Ui.inside(px, py, hit.x1, hit.y1, hit.x2, hit.y2)) continue
            val c = hit.clip
            if (c != null && !Ui.inside(px, py, c[0], c[1], c[2], c[3])) continue
            hit.click(px, py)
            if (hit.drag != null || hit.release != null) dragging = hit
            client?.soundManager?.play(net.minecraft.client.sound.PositionedSoundInstance.ui(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.6f))
            return true
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseDragged(click: Click, offsetX: Double, offsetY: Double): Boolean {
        val hit = dragging ?: return super.mouseDragged(click, offsetX, offsetY)
        mx = click.x()
        my = click.y()
        hit.drag?.invoke(offsetX, offsetY)
        return true
    }

    override fun mouseReleased(click: Click): Boolean {
        val hit = dragging
        if (hit != null) {
            dragging = null
            hit.release?.invoke()
            return true
        }
        return super.mouseReleased(click)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        scrollTarget[tab] -= (verticalAmount * 26).toFloat()
        return true
    }

    override fun keyPressed(input: KeyInput): Boolean {
        if (GuiClient.menuKey?.matchesKey(input) == true) {
            close()
            return true
        }
        val key = input.key()
        if (key in GLFW.GLFW_KEY_1..GLFW.GLFW_KEY_5) {
            selectTab(key - GLFW.GLFW_KEY_1)
            return true
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            selectTab((tab + 1) % TABS)
            return true
        }
        return super.keyPressed(input)
    }
}
