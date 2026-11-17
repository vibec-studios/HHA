package dev.henny.hha.client.rpg

import com.google.gson.JsonArray
import dev.henny.hha.Hha
import dev.henny.hha.HhaItems
import dev.henny.hha.api.rpg.HhaRpg
import dev.henny.hha.client.audio.MusicDirector
import dev.henny.hha.client.gui.Ui
import dev.henny.hha.client.rpg.tunnel.TunnelFigures
import dev.henny.hha.client.rpg.tunnel.TunnelSound
import dev.henny.hha.client.rpg.tunnel.TunnelView
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.input.KeyInput
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Lobby des RPG-Modus als Ort (PLAN 4.1/4.2): Die Gruppe schwebt gemeinsam im Lichttunnel, jeder mit Skin und der
 * Rüstung des Wegs ([TunnelView], 3D-Sonderelement über den ganzen Bildschirm). Die Oberfläche liegt als normale GUI
 * darüber, im Stil der Website ([Ui]):
 *
 * | Ort | Inhalt |
 * | --- | --- |
 * | oben links | Logo, „HHA RPG“, Verbindungsstatus |
 * | oben rechts | ohne Lobby: „Lobby erstellen“ und Code-Feld; mit Lobby: Code groß + „Kopieren“ |
 * | unter jeder Figur | Name, Etikett (HOST / BEREIT / WARTET / DU), aus der Szene projiziert |
 * | unten links | Weg-Karten (nur der Gastgeber wählt) |
 * | unten rechts | Addons, eingeklappt als „Addons (n)“ |
 * | unten Mitte | Mitspieler „Bereit“/„Doch nicht“, Gastgeber „Aufbrechen“; daneben „Verlassen“ |
 *
 * Protokoll und Logik wie bisher ([RpgNet]: `create`, `join`, `set`, `ready`, `kick`, `start`). Nach „Aufbrechen“
 * (Relay `starting`) gleitet die Oberfläche weg, die Figuren drehen sich in Flugrichtung; danach übernimmt
 * [RpgJourney] (es gibt dann keinen Lobby-Bildschirm mehr). Abmessungen: Konstanten im Begleitobjekt; die HTML-Attrappe
 * `tmp/ui_redesign/mock/rpg_lobby/` rechnet mit denselben Zahlen.
 */
class RpgLobbyScreen(private val parent: Screen?) : Screen(Text.translatable("hha.rpg.title")) {

    private class Hit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val action: () -> Unit)
    private class Toast(val text: String, val color: Int, val at: Long)
    private class Leaver(val uuid: String, val name: String, val slot: Int, val path: String, val at: Long)

    private val hits = ArrayList<Hit>()
    private val springs = Ui.Springs()
    private var mx = -1.0
    private var my = -1.0
    private var openedAt = 0L
    private var closingAt = -1L
    private var lastNanos = 0L
    private var seconds = 0f
    private var codeField: TextFieldWidget? = null

    /** Wahl vor dem Erstellen (danach gilt, was die Lobby sagt). */
    private var path = "heaven"
    private val addons = LinkedHashSet<String>()
    private var addonsOpen = false

    // Gruppe und Meldungen
    private val joinedAt = HashMap<String, Long>()
    private val leavers = ArrayList<Leaver>()
    private var lastMembers: List<RpgNet.Member> = emptyList()
    private var lastCode: String? = null
    private var lobbyShownAt = 0L
    private val toasts = ArrayList<Toast>()
    private var lastError: String? = null

    // Wegwechsel: Lichtschleier zieht über die Figuren, das Kostüm wechselt in der Mitte
    private var shownPath = ""
    private var previousPath = ""
    private var pathChangedAt = -1L

    // Akzent gleitet weich zur Farbe des Wegs
    private var accent = Ui.GOLD
    private var accentFrom = Ui.GOLD
    private var accentTarget = Ui.GOLD

    override fun init() {
        if (openedAt == 0L) {
            openedAt = System.currentTimeMillis()
            addons.addAll(RpgNet.installedAddons().filter { it.rpg }.map { it.id })
            accent = pathColor(currentPath())
            accentFrom = accent
            accentTarget = accent
            shownPath = currentPath()
            RpgJourney.takeFailure()?.let { reason ->
                toast(Text.translatableWithFallback("hha.rpg.journey.fail.$reason", reason).string, Ui.BAD)
            }
            MusicDirector.menu("music.tunnel")
            TunnelSound.start()
        }
        RpgNet.connect()
        val old = codeField?.text ?: ""
        val field = TextFieldWidget(textRenderer, 0, -100, 80, 16, Text.translatable("hha.rpg.code"))
        field.setMaxLength(6)
        field.setDrawsBackground(false)
        field.setPlaceholder(Text.literal("ABC123").withColor(Ui.DIM))
        field.setTextPredicate { s -> s.all { it.isLetterOrDigit() } }
        field.setChangedListener { s -> if (s != s.uppercase()) field.text = s.uppercase() }
        field.text = old
        codeField = addDrawableChild(field)
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        if (departing()) return
        if (closingAt < 0) closingAt = System.currentTimeMillis()
    }

    /** Bildschirm verlassen: ohne Reise Musik und Schleife aus (sonst liefen sie im Titelbildschirm weiter). */
    override fun removed() {
        if (!RpgJourney.active) {
            MusicDirector.menu(null)
            TunnelSound.fadeOut()
            TunnelFigures.clearSkins()
        }
    }

    // =============================================================================================
    // Zustand
    // =============================================================================================

    private fun departing(): Boolean = RpgJourney.state == RpgJourney.State.DEPART

    private fun departK(now: Long): Float =
        if (!departing()) 0f else ((now - RpgJourney.stateAt) / RpgJourney.DEPART_MS.toFloat()).coerceIn(0f, 1f)

    /** Oberfläche gleitet weg (400 ms ab Aufbruch). */
    private fun uiOut(now: Long): Float =
        if (!departing()) 0f else ((now - RpgJourney.stateAt) / RpgJourney.UI_OUT_MS.toFloat()).coerceIn(0f, 1f)

    private fun currentPath(): String = RpgNet.lobby?.path ?: path
    private fun currentAddons(): Set<String> = RpgNet.lobby?.addons?.toSet() ?: addons
    private fun canEdit(): Boolean = !departing() && (RpgNet.lobby == null || (RpgNet.isHost() && RpgNet.lobby?.state == "lobby"))
    private fun pathColor(id: String): Int = HhaRpg.path(id)?.color ?: Ui.GOLD
    private fun myUuid(): String? = client?.session?.uuidOrNull?.toString()

    /** Vergleicht die Gruppe mit dem letzten Bild: Neue erscheinen, Gegangene verschwinden, beides als Meldung. */
    private fun syncMembers(now: Long) {
        val lobby = RpgNet.lobby
        val members = RpgJourney.order(lobby?.members ?: emptyList())
        if (lobby?.code != lastCode) {
            lastCode = lobby?.code
            lobbyShownAt = now
        }
        val nowIds = members.mapTo(HashSet()) { it.uuid }
        for (m in members) if (m.uuid !in joinedAt) {
            joinedAt[m.uuid] = if (lastMembers.isEmpty() && m.uuid == myUuid()) openedAt else now
            if (lastMembers.isNotEmpty() && m.uuid != myUuid()) toast(Text.translatable("hha.rpg.toast.joined", m.name).string, Ui.GOOD)
        }
        if (lobby != null) {
            lastMembers.forEachIndexed { i, m ->
                if (m.uuid !in nowIds) {
                    leavers.add(Leaver(m.uuid, m.name, i, shownPath, now))
                    joinedAt.remove(m.uuid)
                    toast(Text.translatable("hha.rpg.toast.left", m.name).string, Ui.MUTED)
                }
            }
        } else {
            joinedAt.clear()
        }
        leavers.removeIf { now - it.at > LEAVE_MS }
        lastMembers = members
        val err = RpgNet.lastError
        if (err != null && err != lastError) toast(Text.translatableWithFallback("hha.rpg.error.$err", err).string, Ui.BAD)
        lastError = err
        // Wegwechsel
        val current = currentPath()
        if (current != shownPath && (pathChangedAt < 0 || now - pathChangedAt > VEIL_MS)) {
            previousPath = shownPath
            shownPath = current
            pathChangedAt = now
        }
    }

    private fun toast(text: String, color: Int) {
        toasts.add(Toast(text, color, System.currentTimeMillis()))
        if (toasts.size > 4) toasts.removeAt(0)
    }

    private fun updateAccent() {
        val target = pathColor(currentPath())
        if (target != accentTarget) {
            accentFrom = accent
            accentTarget = target
            springs.set("accentT", 0f)
        }
        accent = Ui.lerp(accentFrom, accentTarget, springs.get("accentT", 1f, 6f))
    }

    // =============================================================================================
    // Zeichnen
    // =============================================================================================

    private fun fade(now: Long): Float {
        if (closingAt >= 0) return (1f - (now - closingAt) / 160f).coerceIn(0f, 1f)
        return Ui.easeOutCubic((now - openedAt) / 260f)
    }

    /** Wie im Kontrollzentrum: Gruppe für Gruppe einblenden; beim Aufbruch gleitet alles weg. */
    private fun row(alpha: Float, index: Int, now: Long): Float {
        val inT = ((now - openedAt) / 1000f * 5f - index * 0.12f).coerceIn(0f, 1f)
        return alpha * Ui.easeOutCubic(inT) * (1f - uiOut(now))
    }

    /** Wie weit die Oberfläche beim Aufbruch schon weggeglitten ist (Pixel; oben nach oben, unten nach unten). */
    private fun slide(now: Long): Int {
        val k = uiOut(now)
        return (k * k * SLIDE_PX).toInt()
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {}

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val nanos = System.nanoTime()
        springs.dt = if (lastNanos == 0L) 0.016f else ((nanos - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = nanos
        seconds += springs.dt
        val now = System.currentTimeMillis()
        if (closingAt >= 0 && now - closingAt > 160) {
            client?.setScreen(parent)
            return
        }
        hits.clear()
        mx = mouseX.toDouble()
        my = mouseY.toDouble()
        syncMembers(now)
        updateAccent()
        val a = fade(now)

        // 1. Die Szene über den ganzen Bildschirm
        val lookX = springs.get("lookX", if (mouseX >= 0) mouseX / width.toFloat() * 2f - 1f else 0f, 3f)
        val lookY = springs.get("lookY", if (mouseY >= 0) mouseY / height.toFloat() * 2f - 1f else 0f, 3f)
        val scene = sceneFrame(now, lookX, lookY)
        val projection = TunnelView.draw(context, width, height, scene.frame)

        // 2. Ruhige Ränder für die Lesbarkeit (oben und unten dunkler, die Mitte bleibt frei)
        context.fillGradient(0, 0, width, NAV + 46, Ui.alpha(0xB4000000.toInt(), a), 0x00000000)
        context.fillGradient(0, height - BOTTOM_SHADE, width, height, 0x00000000, Ui.alpha(0xC8000000.toInt(), a))
        if (a < 1f) context.fill(0, 0, width, height, Ui.alpha(0xFF000000.toInt(), 1f - a))

        // 3. Namensschilder unter den Figuren, freie Plätze
        tags(context, projection, scene, a, now)

        // 4. Oberfläche
        val s = slide(now)
        nav(context, a, now, s)
        topRight(context, row(a, 1, now), now, s)
        bottom(context, a, now, s)
        val field = codeField
        if (field != null) field.visible = RpgNet.lobby == null && !departing() && a > 0.3f && field.y > NAV
        super.render(context, mouseX, mouseY, deltaTicks)
        context.createNewRootLayer()
        drawToasts(context, a, now)
    }

    // ---------------------------------------------------------------------------------------------
    // Szene
    // ---------------------------------------------------------------------------------------------

    private class SceneInfo(val frame: TunnelView.Frame, val shown: List<Pair<RpgNet.Member, TunnelFigures.Spec>>, val free: List<Int>, val more: Int)

    private fun selfMember(): RpgNet.Member? {
        val session = client?.session ?: return null
        val id = session.uuidOrNull?.toString() ?: return null
        return RpgNet.Member(id, session.username, host = false, ready = false, addons = emptyList())
    }

    private fun sceneFrame(now: Long, lookX: Float, lookY: Float): SceneInfo {
        val lobby = RpgNet.lobby
        val members = RpgJourney.order(lobby?.members ?: emptyList())
        val k = departK(now)
        val facing = smooth(k / 0.6f)
        val fly = smooth((k - 0.15f) / 0.55f)
        val speed = RpgJourney.SPEED_LOBBY + (RpgJourney.SPEED_WARP - RpgJourney.SPEED_LOBBY) * k * k * k
        val color = TunnelView.color(shownPath)
        // Wegwechsel: Schleier 0..1, Kostüm wechselt bei der Hälfte
        val veil = if (pathChangedAt < 0) -1f else ((now - pathChangedAt) / VEIL_MS.toFloat()).let { if (it > 1f) -1f else it }
        val costumePath = if (veil in 0f..0.5f) previousPath.ifEmpty { shownPath } else shownPath
        val list = ArrayList<Pair<RpgNet.Member, TunnelFigures.Spec>>()
        val visible = if (members.isEmpty()) listOfNotNull(selfMember()) else members.take(TunnelFigures.SLOTS.size)
        visible.forEachIndexed { i, m ->
            val since = joinedAt[m.uuid] ?: openedAt
            val appear = ((now - since) / APPEAR_MS.toFloat()).coerceIn(0f, 1f)
            val ring = if (m.host || m.ready) READY_RING else IDLE_RING
            val figureVeil = if (appear < 1f) appear else veil
            list.add(m to TunnelFigures.Spec(m.uuid, m.name, i, costumePath, ring, color, RpgGfx.easeOutBack(appear), facing, fly, veil = figureVeil))
        }
        // Gegangene schrumpfen im Licht weg (nur, wenn ihr Platz jetzt frei ist)
        val leaving = leavers.filter { it.slot >= visible.size && it.slot < TunnelFigures.SLOTS.size }.map { l ->
            val t = ((now - l.at) / LEAVE_MS.toFloat()).coerceIn(0f, 1f)
            TunnelFigures.Spec(l.uuid, l.name, l.slot, l.path, IDLE_RING * (1f - t), color, 1f - Ui.easeOutCubic(t), veil = t)
        }
        val free = if (lobby == null || departing()) emptyList() else {
            val recommended = lobby.recommended
            val wanted = members.size + if (members.size < lobby.max) 1 else 0
            val slots = max(recommended, wanted).coerceIn(1, TunnelFigures.SLOTS.size)
            (visible.size until slots).filter { s -> leaving.none { it.slot == s } }
        }
        val frame = TunnelView.Frame(
            shownPath, speed, list.map { it.second } + leaving, free,
            lookX = lookX, lookY = lookY,
        )
        return SceneInfo(frame, list, free, (members.size - TunnelFigures.SLOTS.size).coerceAtLeast(0))
    }

    /** Name und Etikett unter jeder Figur, „+ FREI“ an freien Plätzen, Chip „+N“ bei mehr als acht. */
    private fun tags(ctx: DrawContext, projection: TunnelView.Projection, scene: SceneInfo, alpha: Float, now: Long) {
        val lobby = RpgNet.lobby
        val a = alpha * (1f - uiOut(now))
        if (a <= 0.01f) return
        val me = myUuid()
        for ((member, spec) in scene.shown) {
            val slot = TunnelFigures.slot(spec.slot)
            val feet = projection.feet(slot) ?: continue
            val head = projection.head(slot) ?: continue
            val since = joinedAt[member.uuid] ?: openedAt
            val na = a * Ui.easeOutCubic((now - since) / 1000f * 1.6f)
            val above = spec.slot in ABOVE_SLOTS
            val cx = feet[0].toInt()
            val figureH = (feet[1] - head[1]).toInt()
            var y = if (above) head[1].toInt() - 24 else feet[1].toInt() + 6
            val name = textRenderer.trimToWidth(member.name, 90)
            val nw = textRenderer.getWidth(name)
            ctx.drawText(textRenderer, name, cx - nw / 2, y, Ui.alpha(Ui.TEXT, na), true)
            y += 11
            val (tag, color) = when {
                member.host -> Text.translatable("hha.rpg.tag.host").string to Ui.GOLD
                lobby == null -> Text.translatable("hha.rpg.tag.you").string to Ui.MUTED
                member.ready -> Text.translatable("hha.rpg.tag.ready").string to Ui.GOOD
                else -> Text.translatable("hha.rpg.tag.waiting").string to Ui.DIM
            }
            val label = if (member.uuid == me && lobby != null && !member.host) tag + " · " + Text.translatable("hha.rpg.tag.you").string else tag
            val tw = Ui.labelWidth(textRenderer, label) + 10
            ctx.fill(cx - tw / 2, y, cx + tw / 2, y + 10, Ui.alpha(0xB0000000.toInt(), na))
            Ui.frame(ctx, cx - tw / 2, y, cx + tw / 2, y + 10, Ui.alpha(Ui.withAlpha(color, 0x77), na))
            Ui.label(ctx, textRenderer, label, cx - tw / 2 + 5, y + 1, Ui.alpha(color, na))
            // Gastgeber darf entfernen (Kreuz neben dem Etikett, sichtbar beim Zeigen auf die Figur)
            val hx1 = cx - max(figureH / 3, 10)
            val hx2 = cx + max(figureH / 3, 10)
            val hover = Ui.inside(mx, my, hx1, head[1].toInt(), hx2, y + 10)
            if (lobby != null && RpgNet.isHost() && lobby.state == "lobby" && !member.host && hover && !departing()) {
                val kx = cx + tw / 2 + 9
                val ky = y + 5
                val hk = Ui.inside(mx, my, kx - 6, ky - 6, kx + 6, ky + 6)
                ctx.fill(kx - 6, ky - 6, kx + 6, ky + 6, Ui.alpha(0xB0000000.toInt(), na))
                Ui.frame(ctx, kx - 6, ky - 6, kx + 6, ky + 6, Ui.alpha(if (hk) Ui.BAD else Ui.LINE_2, na))
                Ui.cross(ctx, kx, ky, Ui.alpha(if (hk) Ui.BAD else Ui.MUTED, na))
                hits.add(Hit(kx - 6, ky - 6, kx + 6, ky + 6) { RpgNet.send("kick") { addProperty("uuid", member.uuid) } })
            }
        }
        // Freie Plätze: zarter Lichtring in der Szene, hier „+“ und „FREI“ (Klick kopiert den Code)
        for (index in scene.free) {
            val slot = TunnelFigures.slot(index)
            val feet = projection.feet(slot) ?: continue
            val head = projection.head(slot) ?: continue
            val cx = feet[0].toInt()
            val cy = ((feet[1] + head[1]) / 2f).toInt()
            val half = ((feet[1] - head[1]) / 3f).toInt().coerceAtLeast(8)
            val hover = lobby != null && Ui.inside(mx, my, cx - half, head[1].toInt(), cx + half, feet[1].toInt() + 12)
            val inv = springs.get("invite$index", if (hover) 1f else 0f, 12f, 0f)
            val breath = 0.55f + 0.45f * sin(seconds * 1.6f + index * 0.9f)
            val c = Ui.alpha(Ui.lerp(Ui.LINE_2, accent, 0.4f + 0.6f * inv), a * max(breath, inv))
            // „+“ in der Mitte der Silhouette
            ctx.fill(cx - 4, cy, cx + 5, cy + 1, c)
            ctx.fill(cx, cy - 4, cx + 1, cy + 5, c)
            val label = Text.translatable(if (inv > 0.5f) "hha.rpg.tag.invite" else "hha.rpg.tag.free").string
            val pw = Ui.labelWidth(textRenderer, label)
            Ui.label(ctx, textRenderer, label, cx - pw / 2, feet[1].toInt() + 6, Ui.alpha(Ui.lerp(Ui.DIM, accent, inv), a))
            if (lobby != null && !departing()) hits.add(Hit(cx - half, head[1].toInt(), cx + half, feet[1].toInt() + 14) { copyCode(lobby.code) })
        }
        // Mehr als acht: Chip „+N“ über der Gruppe
        if (scene.more > 0) {
            val back = projection.head(TunnelFigures.slot(BACK_SLOT))
            if (back != null) {
                val text = "+${scene.more}"
                val w = Ui.labelWidth(textRenderer, text) + 10
                val x = back[0].toInt() + 26
                val y = back[1].toInt() - 30
                ctx.fill(x, y, x + w, y + 11, Ui.alpha(0xC0000000.toInt(), a))
                Ui.frame(ctx, x, y, x + w, y + 11, Ui.alpha(accent, a))
                Ui.label(ctx, textRenderer, text, x + 5, y + 2, Ui.alpha(accent, a))
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Kopfleiste: Logo, „HHA RPG“, Verbindungsstatus; ganz rechts Schließen
    // ---------------------------------------------------------------------------------------------

    private fun nav(ctx: DrawContext, alpha: Float, now: Long, slide: Int) {
        val a = row(alpha, 0, now)
        if (a <= 0.01f) return
        val y0 = -slide
        ctx.fill(0, y0, width, y0 + NAV, Ui.alpha(0x99000000.toInt(), a))
        Ui.hline(ctx, 0, width, y0 + NAV - 1, Ui.alpha(Ui.LINE, a))
        Ui.logo(ctx, PAD, y0 + 5, a)
        val end = Ui.display(ctx, textRenderer, "HHA RPG", PAD + 18, y0 + 7, Ui.alpha(Ui.TEXT, a))
        val (chip, chipColor) = when (RpgNet.status) {
            RpgNet.Status.ONLINE -> Text.translatable("hha.rpg.online") to Ui.GOOD
            RpgNet.Status.CONNECTING -> Text.translatable("hha.rpg.connecting") to Ui.MUTED
            else -> Text.translatable("hha.rpg.offline") to Ui.BAD
        }
        val chipX = end + 10
        val cw = Ui.labelWidth(textRenderer, chip.string) + 16
        Ui.frame(ctx, chipX, y0 + 5, chipX + cw, y0 + 17, Ui.alpha(Ui.LINE_2, a))
        val blink = if (RpgNet.status == RpgNet.Status.CONNECTING) 0.35f + 0.65f * (0.5f + 0.5f * sin(seconds * 6f)) else 1f
        ctx.fill(chipX + 5, y0 + 9, chipX + 8, y0 + 12, Ui.alpha(chipColor, a * blink))
        Ui.label(ctx, textRenderer, chip.string, chipX + 11, y0 + 7, Ui.alpha(Ui.TEXT, a))
        // Weg als Untertitel, wenn Platz ist
        val pathTitle = HhaRpg.path(currentPath())?.let { Text.translatable(it.titleKey).string }
        val cx2 = width - PAD
        val cx1 = cx2 - NAV
        if (pathTitle != null) {
            val pw = Ui.labelWidth(textRenderer, pathTitle)
            if (chipX + cw + 16 + pw < cx1 - 10) Ui.label(ctx, textRenderer, pathTitle, cx1 - 10 - pw, y0 + 7, Ui.alpha(accent, a))
        }
        val hoverClose = !departing() && Ui.inside(mx, my, cx1, y0, cx2, y0 + NAV - 1)
        if (hoverClose) ctx.fill(cx1, y0, cx2, y0 + NAV - 1, Ui.alpha(Ui.SURFACE_2, a))
        Ui.vline(ctx, cx1, y0, y0 + NAV - 1, Ui.alpha(Ui.LINE, a))
        Ui.vline(ctx, cx2 - 1, y0, y0 + NAV - 1, Ui.alpha(Ui.LINE, a))
        Ui.cross(ctx, cx1 + NAV / 2, y0 + NAV / 2, Ui.alpha(if (hoverClose) Ui.TEXT else Ui.MUTED, a))
        hits.add(Hit(cx1, y0, cx2, y0 + NAV - 1) { close() })
    }

    // ---------------------------------------------------------------------------------------------
    // Oben rechts: Lobby erstellen / beitreten, oder der Code
    // ---------------------------------------------------------------------------------------------

    private fun topRight(ctx: DrawContext, a: Float, now: Long, slide: Int) {
        val w = panelW()
        val x = width - PAD - w
        var y = NAV + 8 - slide
        val lobby = RpgNet.lobby
        val online = RpgNet.status == RpgNet.Status.ONLINE
        val field = codeField
        if (a <= 0.01f) {
            field?.y = -100
            return
        }
        if (lobby == null) {
            if (RpgNet.status == RpgNet.Status.FAILED) {
                y = notice(ctx, x, y, w, Text.translatable("hha.rpg.notice.offline"), Ui.BAD, a)
                button(ctx, x, y, w, Text.translatable("hha.rpg.retry"), Kind.GHOST, a, "retry") { RpgNet.connect() }
                y += 22
            }
            button(ctx, x, y, w, Text.translatable("hha.rpg.create"), Kind.PRIMARY, a, "create", enabled = online && !departing(), glow = online) { createLobby() }
            y += 22
            val or = Text.translatable("hha.rpg.or_join").string
            val ow = Ui.labelWidth(textRenderer, or)
            if (ow + 24 < w) {
                Ui.hline(ctx, x, x + (w - ow) / 2 - 6, y + 3, Ui.alpha(Ui.LINE_2, a))
                Ui.hline(ctx, x + (w + ow) / 2 + 6, x + w, y + 3, Ui.alpha(Ui.LINE_2, a))
            }
            Ui.label(ctx, textRenderer, or, x + max(0, (w - ow) / 2), y, Ui.alpha(Ui.MUTED, a))
            y += 12
            val bw = JOIN_W
            if (field != null) {
                val focus = springs.get("codeFocus", if (field.isFocused) 1f else 0f, 14f, 0f)
                ctx.fill(x, y, x + w - bw - 4, y + 16, Ui.alpha(0xE60B0B0E.toInt(), a))
                Ui.frame(ctx, x, y, x + w - bw - 4, y + 16, Ui.alpha(Ui.lerp(Ui.LINE_2, accent, focus), a))
                field.x = x + 6
                field.y = y + 4
                field.width = w - bw - 14
                field.height = 10
            }
            val code = field?.text?.trim().orEmpty()
            button(ctx, x + w - bw, y, bw, Text.translatable("hha.rpg.join"), Kind.GHOST, a, "join", enabled = online && code.length >= 4 && !departing()) { joinLobby() }
            return
        }
        field?.y = -100
        // Code groß (Buchstaben klappen nacheinander auf), „Kopieren“ oben rechts im Feld, Spielerzahl darunter
        ctx.fill(x, y, x + w, y + CODE_H, Ui.alpha(0xCC0B0B0E.toInt(), a))
        Ui.frame(ctx, x, y, x + w, y + CODE_H, Ui.alpha(Ui.LINE_2, a))
        Ui.label(ctx, textRenderer, Text.translatable("hha.rpg.code").string, x + 6, y + 6, Ui.alpha(Ui.DIM, a))
        button(ctx, x + w - 5 - COPY_W, y + 4, COPY_W, Text.translatable("hha.rpg.copy"), Kind.GHOST, a, "copy", h = 12) { copyCode(lobby.code) }
        var cx = x + 6
        for ((i, ch) in lobby.code.withIndex()) {
            val t = Ui.easeOutCubic(((now - lobbyShownAt) / 1000f * 4f - i * 0.12f).coerceIn(0f, 1f))
            val s = ch.toString()
            Ui.display(ctx, textRenderer, s, cx, y + 19 + ((1f - t) * 5f).toInt(), Ui.alpha(Ui.TEXT, a * t), 2)
            cx += Ui.displayWidth(textRenderer, s, 2) + 3
        }
        y += CODE_H + 4
        val members = lobby.members.size
        val count = Text.translatable("hha.rpg.members", members, lobby.recommended).string
        val cw = Ui.labelWidth(textRenderer, count)
        Ui.label(ctx, textRenderer, count, x + w - cw, y, Ui.alpha(if (members > lobby.recommended) Ui.GOLD else Ui.MUTED, a))
        y += 14
        val mine = RpgNet.installedAddons().filter { it.rpg }.mapTo(HashSet()) { it.id }
        val missing = lobby.addons.filter { it !in mine }
        if (missing.isNotEmpty()) y = notice(ctx, x, y, w, Text.translatable("hha.rpg.notice.missing", missing.joinToString(", ")), Ui.BAD, a)
        if (lobby.state != "lobby") notice(ctx, x, y, w, Text.translatable("hha.rpg.notice.running"), Ui.TEAL, a)
    }

    private fun panelW(): Int = (width / 3).coerceIn(PANEL_MIN_W, PANEL_W)

    // ---------------------------------------------------------------------------------------------
    // Unten: Weg-Karten links, Hauptknopf Mitte, Addons rechts
    // ---------------------------------------------------------------------------------------------

    private fun cardW(): Int = ((width - MAIN_W - GAP - LEAVE_W - 4 * PAD) / 2).coerceIn(CARD_MIN_W, CARD_W)

    private fun bottom(ctx: DrawContext, alpha: Float, now: Long, slide: Int) {
        val cardW = cardW()
        pathCards(ctx, PAD, height - PAD + slide, cardW, row(alpha, 2, now))
        addonRow(ctx, width - PAD - cardW, height - PAD + slide, cardW, row(alpha, 3, now))
        mainButtons(ctx, row(alpha, 4, now), slide)
    }

    private fun pathIcon(id: String): ItemStack = when (id) {
        "heaven" -> ItemStack(HhaItems.HEAVENS_SWORD)
        "hell" -> ItemStack(HhaItems.HELLS_SWORD)
        else -> ItemStack(HhaItems.HEAVEN_INGOT)
    }

    /** Weg-Karten von unten nach oben gestapelt, darüber „01 WEG“. [bottomY] = Unterkante. */
    private fun pathCards(ctx: DrawContext, x: Int, bottomY: Int, w: Int, a: Float) {
        if (a <= 0.01f) return
        val visible = HhaRpg.paths().filter { it.addonId == Hha.MOD_ID || it.addonId in currentAddons() }
        val h = CARD_H
        var y = bottomY - visible.size * (h + 1) - 1
        panelHead(ctx, x, y - 12, w, 1, "hha.rpg.section.path", accent, a)
        ctx.fill(x, y, x + w, bottomY, Ui.alpha(Ui.LINE, a))
        val editable = canEdit()
        for (p in visible) {
            val ty = y + 1
            val on = p.id == currentPath()
            val hover = editable && Ui.inside(mx, my, x, ty, x + w, ty + h)
            val hv = springs.get("path:${p.id}", if (hover) 1f else 0f, 16f, 0f)
            val sel = springs.get("pathOn:${p.id}", if (on) 1f else 0f, 12f, if (on) 1f else 0f)
            ctx.fill(x + 1, ty, x + w - 1, ty + h, Ui.alpha(Ui.lerp(0xD90B0B0E.toInt(), 0xE6121217.toInt(), max(hv, sel * 0.7f)), a))
            if (sel > 0.01f) {
                ctx.fillGradient(x + 1, ty, x + w - 1, ty + h, Ui.alpha(p.color, 0.12f * sel * a), Ui.withAlpha(p.color, 0))
                ctx.fill(x + 1, ty, x + 1 + max(1, (2 * sel).roundToInt()), ty + h, Ui.alpha(p.color, a * sel))
            }
            if (a > 0.5f) ctx.drawItem(pathIcon(p.id), x + 6, ty + (h - 16) / 2)
            // Titel einzeilig mit Beschreibung; passt er nicht, zweizeilig ohne Beschreibung (schmale Fenster)
            val tx = x + 27
            val textW = w - 33
            val titleColor = Ui.alpha(Ui.lerp(Ui.TEXT, p.color, sel), a)
            val lines = displayLines(Text.translatable(p.titleKey).string, textW)
            if (lines.size == 1) {
                Ui.display(ctx, textRenderer, lines[0], tx, ty + 5, titleColor)
                val desc = textRenderer.trimToWidth(Text.translatable(p.descKey).string, textW)
                ctx.drawText(textRenderer, desc, tx, ty + 15, Ui.alpha(Ui.MUTED, a), false)
            } else {
                Ui.display(ctx, textRenderer, lines[0], tx, ty + 4, titleColor)
                Ui.display(ctx, textRenderer, lines[1], tx, ty + 14, titleColor)
            }
            if (editable) hits.add(Hit(x, ty, x + w, ty + h) { choosePath(p.id) })
            y += h + 1
        }
    }

    /** Bricht einen Titel in `hha:display` an Leerzeichen auf höchstens zwei Zeilen um (die zweite wird gekürzt). */
    private fun displayLines(text: String, maxW: Int): List<String> {
        if (Ui.displayWidth(textRenderer, text) <= maxW) return listOf(text)
        val words = text.split(' ')
        var first = ""
        var i = 0
        while (i < words.size) {
            val candidate = if (first.isEmpty()) words[i] else first + " " + words[i]
            if (first.isNotEmpty() && Ui.displayWidth(textRenderer, candidate) > maxW) break
            first = candidate
            i++
        }
        var second = words.drop(i).joinToString(" ")
        while (second.isNotEmpty() && Ui.displayWidth(textRenderer, second) > maxW) second = second.dropLast(1)
        return if (second.isEmpty()) listOf(first) else listOf(first, second)
    }

    /** Addons: eingeklappt eine Zeile „Addons (n)“, aufgeklappt wächst die Liste nach oben. */
    private fun addonRow(ctx: DrawContext, x: Int, bottomY: Int, w: Int, a: Float) {
        if (a <= 0.01f) return
        val installed = RpgNet.installedAddons()
        val active = installed.count { it.rpg && it.id in currentAddons() }
        val rowY = bottomY - ADDON_ROW_H
        val open = springs.get("addonsOpen", if (addonsOpen && installed.isNotEmpty()) 1f else 0f, 14f, 0f)
        val listH = ((installed.size * (ADDON_ROW_H + 1)) * open).roundToInt()
        val top = rowY - listH
        panelHead(ctx, x, top - 12, w, 2, "hha.rpg.section.addons", Ui.TEAL, a)
        // Liste (wächst nach oben)
        if (listH > 0) {
            ctx.enableScissor(x, top, x + w, rowY)
            var y = rowY - installed.size * (ADDON_ROW_H + 1)
            for (addon in installed) {
                val on = addon.id in currentAddons()
                val editable = addon.rpg && canEdit() && open > 0.9f
                val hover = editable && Ui.inside(mx, my, x, y, x + w, y + ADDON_ROW_H)
                ctx.fill(x, y, x + w, y + ADDON_ROW_H, Ui.alpha(if (hover) 0xE6121217.toInt() else 0xD90B0B0E.toInt(), a))
                if (on && addon.rpg) ctx.fill(x, y, x + 2, y + ADDON_ROW_H, Ui.alpha(Ui.TEAL, a))
                val unsupported = Text.translatable("hha.rpg.addons.unsupported").string
                val rightW = if (addon.rpg) 30 else Ui.labelWidth(textRenderer, unsupported) + 12
                val name = textRenderer.trimToWidth(addon.name, w - 7 - rightW)
                ctx.drawText(textRenderer, name, x + 7, y + 4, Ui.alpha(if (addon.rpg) Ui.TEXT else Ui.DIM, a), false)
                if (addon.rpg) {
                    switch(ctx, x + w - 24, y + 3, on, Ui.TEAL, a, "addon:${addon.id}", editable)
                    if (editable) hits.add(Hit(x, y, x + w, y + ADDON_ROW_H) { toggleAddon(addon.id) })
                } else {
                    Ui.label(ctx, textRenderer, unsupported, x + w - 6 - Ui.labelWidth(textRenderer, unsupported), y + 5, Ui.alpha(Ui.DIM, a))
                }
                y += ADDON_ROW_H + 1
            }
            ctx.disableScissor()
        }
        // Kopfzeile
        val hover = installed.isNotEmpty() && Ui.inside(mx, my, x, rowY, x + w, bottomY)
        ctx.fill(x, rowY, x + w, bottomY, Ui.alpha(if (hover) 0xE6121217.toInt() else 0xD90B0B0E.toInt(), a))
        Ui.frame(ctx, x, rowY, x + w, bottomY, Ui.alpha(if (hover) Ui.LINE_2 else Ui.LINE, a))
        val text = if (installed.isEmpty()) Text.translatable("hha.rpg.addons.none").string
        else Text.translatable("hha.rpg.lobby.addons", active).string
        Ui.label(ctx, textRenderer, textRenderer.trimToWidth(text, w - 20), x + 7, rowY + 5, Ui.alpha(if (installed.isEmpty()) Ui.DIM else Ui.TEXT, a))
        if (installed.isNotEmpty()) {
            // Pfeil: zeigt nach oben (aufklappen) bzw. unten (zuklappen)
            val ax = x + w - 10
            val ay = rowY + ADDON_ROW_H / 2
            for (i in 0..2) {
                val yy = if (open > 0.5f) ay - 1 + i else ay + 1 - i
                ctx.fill(ax - i, yy, ax + i + 1, yy + 1, Ui.alpha(Ui.MUTED, a))
            }
            hits.add(Hit(x, rowY, x + w, bottomY) { addonsOpen = !addonsOpen })
        }
    }

    /** Hauptknopf unten Mitte und „Verlassen“ daneben; darüber eine Statuszeile. */
    private fun mainButtons(ctx: DrawContext, a: Float, slide: Int) {
        if (a <= 0.01f) return
        val lobby = RpgNet.lobby
        val total = MAIN_W + GAP + LEAVE_W
        val x = (width - total) / 2
        val y = height - PAD - MAIN_H + slide
        if (lobby == null) {
            val hint = textRenderer.wrapLines(Text.translatable("hha.rpg.hint.short"), min(260, width - 2 * cardW() - 4 * PAD))
            var hy = height - PAD - hint.size * 10 + slide
            for (line in hint) {
                val lw = textRenderer.getWidth(line)
                ctx.drawText(textRenderer, line, (width - lw) / 2, hy, Ui.alpha(Ui.MUTED, a), true)
                hy += 10
            }
            return
        }
        val running = lobby.state != "lobby"
        val status: Pair<String, Int>
        if (RpgNet.isHost()) {
            val everyoneReady = lobby.members.all { it.host || it.ready }
            val alone = lobby.members.size <= 1
            val canGo = !running && !departing() && (everyoneReady || alone)
            button(ctx, x, y, MAIN_W, Text.translatable("hha.rpg.lobby.depart"), Kind.PRIMARY, a, "start", enabled = canGo, glow = canGo, h = MAIN_H) {
                RpgNet.send("start")
            }
            val waiting = lobby.members.count { !it.host && !it.ready }
            status = when {
                alone -> Text.translatable("hha.rpg.start.alone").string to Ui.MUTED
                waiting > 0 -> Text.translatable("hha.rpg.start.waiting", waiting).string to Ui.MUTED
                else -> Text.translatable("hha.rpg.start.go").string to Ui.GOOD
            }
        } else {
            val me = lobby.members.firstOrNull { it.uuid == myUuid() }
            val ready = me?.ready == true
            button(
                ctx, x, y, MAIN_W, Text.translatable(if (ready) "hha.rpg.lobby.unready" else "hha.rpg.lobby.ready"),
                if (ready) Kind.GHOST else Kind.PRIMARY, a, "ready", enabled = !running && !departing(), glow = !ready && !running, h = MAIN_H,
            ) {
                RpgNet.send("ready") { addProperty("ready", !ready) }
            }
            val host = lobby.members.firstOrNull { it.host }?.name ?: "?"
            status = Text.translatable("hha.rpg.start.by_host", host).string to Ui.MUTED
        }
        button(ctx, x + MAIN_W + GAP, y, LEAVE_W, Text.translatable("hha.rpg.leave"), Kind.DANGER, a, "leave", enabled = !departing(), h = MAIN_H) {
            RpgNet.send("leave")
        }
        val sw = Ui.labelWidth(textRenderer, status.first)
        Ui.label(ctx, textRenderer, status.first, (width - sw) / 2, y - 11, Ui.alpha(status.second, a))
    }

    // ---------------------------------------------------------------------------------------------
    // Aktionen (wie bisher)
    // ---------------------------------------------------------------------------------------------

    private fun createLobby() {
        RpgNet.send("create") {
            addProperty("path", path)
            add("addons", JsonArray().also { arr -> addons.forEach { arr.add(it) } })
        }
    }

    private fun joinLobby() {
        val code = codeField?.text?.trim()?.uppercase().orEmpty()
        if (code.length >= 4 && RpgNet.status == RpgNet.Status.ONLINE) RpgNet.send("join") { addProperty("code", code) }
    }

    private fun copyCode(code: String) {
        client?.keyboard?.clipboard = code
        toast(Text.translatable("hha.rpg.toast.copied", code).string, Ui.GOLD)
    }

    private fun choosePath(id: String) {
        if (RpgNet.lobby == null) path = id
        else RpgNet.send("set") { addProperty("path", id) }
    }

    private fun toggleAddon(id: String) {
        val set = LinkedHashSet(currentAddons())
        if (!set.add(id)) set.remove(id)
        if (RpgNet.lobby == null) {
            addons.clear()
            addons.addAll(set)
            if (HhaRpg.path(path)?.addonId == id && id !in set) path = "heaven"
        } else {
            RpgNet.send("set") { add("addons", JsonArray().also { arr -> set.forEach { arr.add(it) } }) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Bausteine (wie im Kontrollzentrum)
    // ---------------------------------------------------------------------------------------------

    private enum class Kind { PRIMARY, GHOST, DANGER }

    /** Überschrift eines Felds wie auf der Website: Nummer, Titel, feine Linie bis zum Rand. */
    private fun panelHead(ctx: DrawContext, x: Int, y: Int, w: Int, index: Int, key: String, color: Int, a: Float) {
        val end = Ui.label(ctx, textRenderer, "0$index", x, y, Ui.alpha(color, a))
        val end2 = Ui.label(ctx, textRenderer, Text.translatable(key).string, end + 6, y, Ui.alpha(Ui.TEXT, a))
        Ui.hline(ctx, end2 + 8, x + w, y + 3, Ui.alpha(Ui.LINE_2, a))
    }

    private fun button(
        ctx: DrawContext, x: Int, y: Int, w: Int, label: Text, kind: Kind, a: Float, id: String,
        enabled: Boolean = true, glow: Boolean = false, h: Int = 16, onClick: () -> Unit,
    ) {
        val hover = enabled && Ui.inside(mx, my, x, y, x + w, y + h)
        val hv = springs.get("btn:$id", if (hover) 1f else 0f, 18f, 0f)
        val k = if (enabled) 1f else 0.35f
        val glowing = glow && enabled
        val pulse = if (glowing) 0.5f + 0.5f * sin(seconds * 3.2f) else 0f
        if (glowing) RpgGfx.texture(ctx, RpgGfx.GLOW, x + w / 2, y + h / 2, w + 48, h + 34, 0f, Ui.alpha(accent, (0.22f + 0.22f * pulse) * a))
        val lift = if (kind == Kind.PRIMARY) hv.roundToInt() else 0
        val text = when (kind) {
            Kind.PRIMARY -> {
                ctx.fill(x, y - lift, x + w, y + h - lift, Ui.alpha(Ui.brighten(accent, 0.14f * hv + 0.08f * pulse), a * k))
                if (hv > 0.02f && enabled) {
                    val sx = x + ((seconds * 0.9f % 1f) * (w + 24)).toInt() - 12
                    ctx.fill(max(x, sx), y - lift, min(x + w, sx + 6), y + h - lift, Ui.alpha(0x40FFFFFF, hv * a))
                }
                Ui.ON_ACCENT
            }
            Kind.GHOST -> {
                ctx.fill(x, y, x + w, y + h, Ui.alpha(Ui.lerp(0xC00B0B0E.toInt(), 0xE6121217.toInt(), hv), a))
                Ui.frame(ctx, x, y, x + w, y + h, Ui.alpha(Ui.lerp(Ui.LINE_2, Ui.TEXT, hv * 0.7f), a * k))
                Ui.TEXT
            }
            Kind.DANGER -> {
                ctx.fill(x, y, x + w, y + h, Ui.alpha(0xC00B0B0E.toInt(), a))
                Ui.frame(ctx, x, y, x + w, y + h, Ui.alpha(Ui.lerp(Ui.LINE_2, Ui.BAD, hv), a * k))
                Ui.lerp(Ui.MUTED, Ui.BAD, hv)
            }
        }
        val s = label.string
        val lw = Ui.labelWidth(textRenderer, s)
        Ui.label(ctx, textRenderer, s, x + (w - lw) / 2, y + (h - 6) / 2 - lift, Ui.alpha(text, a * k))
        if (enabled) hits.add(Hit(x, y - lift, x + w, y + h, onClick))
    }

    private fun switch(ctx: DrawContext, x: Int, y: Int, on: Boolean, color: Int, a: Float, id: String, enabled: Boolean) {
        val t = springs.get("sw:$id", if (on) 1f else 0f, 16f)
        val hover = enabled && Ui.inside(mx, my, x - 3, y - 3, x + 21, y + 13)
        val k = if (enabled) 1f else 0.4f
        if (t > 0.01f) ctx.fill(x, y, x + 18, y + 10, Ui.alpha(color, a * k * t))
        Ui.frame(ctx, x, y, x + 18, y + 10, Ui.alpha(Ui.lerp(if (hover) Ui.TEXT else Ui.LINE_2, color, t), a * k))
        val kx = x + 2 + (t * 8).roundToInt()
        ctx.fill(kx, y + 2, kx + 6, y + 8, Ui.alpha(Ui.lerp(if (hover) Ui.TEXT else Ui.DIM, Ui.ON_ACCENT, t), a * k))
    }

    private fun notice(ctx: DrawContext, x: Int, y: Int, w: Int, text: Text, color: Int, a: Float): Int {
        val lines = textRenderer.wrapLines(text, w - 16)
        val h = 8 + lines.size * 10
        ctx.fill(x, y, x + w, y + h, Ui.alpha(0xD90B0B0E.toInt(), a))
        ctx.fill(x, y, x + 2, y + h, Ui.alpha(color, a))
        lines.forEachIndexed { i, line -> ctx.drawText(textRenderer, line, x + 9, y + 5 + i * 10, Ui.alpha(Ui.TEXT, a), false) }
        return y + h + 6
    }

    /** Meldungen oben Mitte: gleiten herein und verblassen nach drei Sekunden. */
    private fun drawToasts(ctx: DrawContext, a: Float, now: Long) {
        toasts.removeIf { now - it.at > 3200 }
        var y = NAV + 8
        for (t in toasts.asReversed()) {
            val age = (now - t.at) / 1000f
            val inT = Ui.easeOutCubic(age / 0.25f)
            val outT = (1f - (age - 2.8f) / 0.4f).coerceIn(0f, 1f)
            val ta = a * inT * outT
            val w = textRenderer.getWidth(t.text) + 22
            val x = (width - w) / 2
            val yy = y - ((1f - inT) * 8f).toInt()
            ctx.fill(x, yy, x + w, yy + 18, Ui.alpha(0xF00B0B0E.toInt(), ta))
            Ui.frame(ctx, x, yy, x + w, yy + 18, Ui.alpha(Ui.LINE_2, ta))
            ctx.fill(x, yy, x + 2, yy + 18, Ui.alpha(t.color, ta))
            ctx.drawText(textRenderer, t.text, x + 12, yy + 5, Ui.alpha(Ui.TEXT, ta), false)
            y += 22
        }
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (departing()) return true
        if (super.mouseClicked(click, doubled)) return true
        codeField?.isFocused = false
        for (hit in hits.asReversed()) {
            if (Ui.inside(click.x(), click.y(), hit.x1, hit.y1, hit.x2, hit.y2)) {
                hit.action()
                return true
            }
        }
        if (addonsOpen) addonsOpen = false
        return false
    }

    override fun keyPressed(input: KeyInput): Boolean {
        val field = codeField
        if (field != null && field.isFocused && (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            joinLobby()
            return true
        }
        return super.keyPressed(input)
    }

    private fun smooth(k: Float): Float {
        val x = k.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    companion object {
        /** Kopfleiste, Rand, Abstand. */
        const val NAV = 22
        const val PAD = 10
        const val GAP = 4

        /** Oben rechts: Breite des Felds (schmal: ein Drittel des Bildschirms, mindestens [PANEL_MIN_W]). */
        const val PANEL_W = 150
        const val PANEL_MIN_W = 128
        const val JOIN_W = 58
        const val COPY_W = 58

        /** Unten: Weg-Karten und Addons ([CARD_MIN_W]..[CARD_W] breit), Hauptknopf, „Verlassen“. */
        const val CARD_W = 150
        const val CARD_MIN_W = 100
        const val CARD_H = 26
        const val ADDON_ROW_H = 16
        const val MAIN_W = 116
        const val MAIN_H = 18
        const val LEAVE_W = 68

        /** Höhe des dunklen Verlaufs unten. */
        const val BOTTOM_SHADE = 110

        /** Aufbruch: so weit gleitet die Oberfläche aus dem Bild. */
        const val SLIDE_PX = 26f

        /** Lichtring: bereit/Gastgeber hell, sonst matt. */
        const val READY_RING = 1f
        const val IDLE_RING = 0.32f

        /** Hinterster Platz (Chip „+N“ daneben). */
        const val BACK_SLOT = 7

        /** Hintere Plätze: Schild über dem Kopf (sonst stoßen sie an die Schilder davor bzw. verdecken die Mitte). */
        val ABOVE_SLOTS = setOf(5, 6, 7)

        /** Höhe des Code-Felds oben rechts. */
        const val CODE_H = 38

        const val APPEAR_MS = 700L
        const val LEAVE_MS = 600L
        const val VEIL_MS = 900L
    }
}
