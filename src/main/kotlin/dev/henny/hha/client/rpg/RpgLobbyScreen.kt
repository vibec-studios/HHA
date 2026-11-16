package dev.henny.hha.client.rpg

import com.google.gson.JsonArray
import com.mojang.authlib.GameProfile
import dev.henny.hha.Hha
import dev.henny.hha.HhaItems
import dev.henny.hha.api.rpg.HhaRpg
import dev.henny.hha.client.gui.Ui
import dev.henny.hha.client.rpg.RpgGfx.GLOW
import dev.henny.hha.client.rpg.RpgGfx.RAYS
import dev.henny.hha.client.rpg.RpgGfx.SPOT
import dev.henny.hha.client.rpg.RpgGfx.texture
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.input.KeyInput
import net.minecraft.client.render.entity.model.EntityModelLayer
import net.minecraft.client.render.entity.model.EntityModelLayers
import net.minecraft.client.render.entity.model.PlayerEntityModel
import net.minecraft.client.util.DefaultSkinHelper
import net.minecraft.entity.player.PlayerSkinType
import net.minecraft.entity.player.SkinTextures
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW
import java.util.UUID
import java.util.function.Supplier
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Lobby des RPG-Modus im Stil des Kontrollzentrums und der Website:
 *
 * - **Bühne:** Die Gruppe steht als 3D-Figuren mit ihrem Skin auf Lichtflecken nebeneinander, dahinter drehen sich
 *   Lichtstrahlen in der Farbe des Wegs. Wer beitritt, erscheint in einer Lichtsäule und federt auf, wer geht,
 *   schrumpft im Licht weg. Freie Plätze zeigen eine gestrichelte, atmende Silhouette (Klick kopiert den Code).
 * - **Darunter drei Felder:** Weg (Kacheln mit Waffe), Addons (Schalter), Gruppe (Code als Kacheln, die nacheinander
 *   aufklappen, Knöpfe).
 * - **Start:** Die Felder sinken Reihe für Reihe weg, die Figuren steigen ins anschwellende Licht, dann schließt sich
 *   eine Irisblende. Die Startanimation ([RpgLaunch.drawOverlay]) öffnet sie wieder.
 */
class RpgLobbyScreen(private val parent: Screen?) : Screen(Text.translatable("hha.rpg.title")) {

    private class Hit(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val action: () -> Unit)
    private class Toast(val text: String, val color: Int, val at: Long)
    private class Leaver(val uuid: String, val name: String, val slot: Int, val at: Long)

    private val hits = ArrayList<Hit>()
    private val springs = Ui.Springs()
    private var mx = -1.0
    private var my = -1.0
    private var openedAt = 0L
    private var closingAt = -1L
    private var departAt = -1L
    private var lastNanos = 0L
    private var seconds = 0f
    private var codeField: TextFieldWidget? = null

    /** Wahl vor dem Erstellen (danach gilt, was die Lobby sagt). */
    private var path = "heaven"
    private val addons = LinkedHashSet<String>()

    // Bühne und Meldungen
    private val skins = HashMap<String, Supplier<SkinTextures>>()
    private val joinedAt = HashMap<String, Long>()
    private val leavers = ArrayList<Leaver>()
    private var lastMembers: List<RpgNet.Member> = emptyList()
    private var lobbyShownAt = 0L
    private var lastCode: String? = null
    private val toasts = ArrayList<Toast>()
    private var lastError: String? = null

    // Scrollen, falls das Fenster klein ist
    private var scrollTarget = 0f
    private var scroll = 0f
    private var contentHeight = 0
    private var panelsHeight = 0

    // Akzent gleitet weich zur Farbe des Wegs
    private var accent = Ui.GOLD
    private var accentFrom = Ui.GOLD
    private var accentTarget = Ui.GOLD

    private val wideModel by lazy { PlayerEntityModel(client!!.loadedEntityModels.getModelPart(EntityModelLayers.PLAYER), false) }
    private val slimModel by lazy { PlayerEntityModel(client!!.loadedEntityModels.getModelPart(PLAYER_SLIM), true) }

    override fun init() {
        if (openedAt == 0L) {
            openedAt = System.currentTimeMillis()
            addons.addAll(RpgNet.installedAddons().filter { it.rpg }.map { it.id })
            accent = pathColor(currentPath())
            accentFrom = accent
            accentTarget = accent
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
        if (departAt >= 0) return
        if (closingAt < 0) closingAt = System.currentTimeMillis()
    }

    /** Startsignal: Abschied mit Lichtwelle und Irisblende, danach der Bildschirm der Startanimation. */
    fun depart() {
        if (departAt < 0) {
            departAt = System.currentTimeMillis()
            codeField?.isFocused = false
        }
    }

    val departed: Boolean get() = departAt >= 0 && System.currentTimeMillis() - departAt >= RpgGfx.DEPART_MS

    // =============================================================================================
    // Zustand
    // =============================================================================================

    private fun currentPath(): String = RpgNet.lobby?.path ?: path
    private fun currentAddons(): Set<String> = RpgNet.lobby?.addons?.toSet() ?: addons
    private fun canEdit(): Boolean = departAt < 0 && (RpgNet.lobby == null || (RpgNet.isHost() && RpgNet.lobby?.state == "lobby"))
    private fun pathColor(id: String): Int = HhaRpg.path(id)?.color ?: Ui.GOLD
    private fun myUuid(): String? = client?.session?.uuidOrNull?.toString()

    /** Vergleicht die Gruppe mit dem letzten Bild: Neue erscheinen, Gegangene verschwinden, beides als Meldung. */
    private fun syncMembers(now: Long) {
        val lobby = RpgNet.lobby
        val members = lobby?.members ?: emptyList()
        if (lobby?.code != lastCode) {
            lastCode = lobby?.code
            lobbyShownAt = now
        }
        val nowIds = members.mapTo(HashSet()) { it.uuid }
        for (m in members) if (m.uuid !in joinedAt) {
            joinedAt[m.uuid] = now
            if (lastMembers.isNotEmpty() && m.uuid != myUuid()) toast(Text.translatable("hha.rpg.toast.joined", m.name).string, Ui.GOOD)
        }
        if (lobby != null) {
            lastMembers.forEachIndexed { i, m ->
                if (m.uuid !in nowIds) {
                    leavers.add(Leaver(m.uuid, m.name, i, now))
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
    }

    private fun toast(text: String, color: Int) {
        toasts.add(Toast(text, color, System.currentTimeMillis()))
        if (toasts.size > 4) toasts.removeAt(0)
    }

    // =============================================================================================
    // Zeichnen
    // =============================================================================================

    private fun fade(now: Long): Float {
        if (closingAt >= 0) return (1f - (now - closingAt) / 160f).coerceIn(0f, 1f)
        return Ui.easeOutCubic((now - openedAt) / 260f)
    }

    /** Wie im Kontrollzentrum: Reihe für Reihe einblenden; beim Abschied sinken die Reihen von unten nach oben weg. */
    private fun row(alpha: Float, index: Int, now: Long): Float {
        val inT = ((now - openedAt) / 1000f * 5f - index * 0.12f).coerceIn(0f, 1f)
        return alpha * Ui.easeOutCubic(inT) * (1f - outT(index, now))
    }

    private fun outT(index: Int, now: Long): Float {
        if (departAt < 0) return 0f
        return ((now - departAt) / 1000f * 7f - (6 - index).coerceAtLeast(0) * 0.10f).coerceIn(0f, 1f)
    }

    /** Wie weit eine Reihe beim Abschied schon abgesunken ist (Pixel). */
    private fun sink(index: Int, now: Long): Int {
        val t = outT(index, now)
        return (t * t * 14f).toInt()
    }

    private fun departT(now: Long): Float = if (departAt < 0) 0f else ((now - departAt) / RpgGfx.DEPART_MS.toFloat()).coerceIn(0f, 1f)

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        super.renderBackground(context, mouseX, mouseY, deltaTicks)
        val now = System.currentTimeMillis()
        val a = fade(now)
        updateAccent()
        context.fill(0, 0, width, height, Ui.alpha(0xE0000000.toInt(), a))
        context.fillGradient(0, 0, width, height * 2 / 3, Ui.alpha(accent, (0.14f + 0.2f * departT(now)) * a), Ui.withAlpha(accent, 0))
        val wrap = wrapW()
        val x0 = (width - wrap) / 2
        if (width - wrap >= 8) {
            Ui.vline(context, x0 - 1, 0, height, Ui.alpha(Ui.LINE, a))
            Ui.vline(context, x0 + wrap, 0, height, Ui.alpha(Ui.LINE, a))
        }
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

    private fun wrapW(): Int = min(width - (if (width < 520) 24 else 48), 860)

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
        if (departed) {
            // Abschied fertig: die Startanimation übernimmt und öffnet die Iris wieder
            if (RpgLaunch.active) {
                client?.setScreen(RpgTransitionScreen())
                return
            }
            departAt = -1
        }
        hits.clear()
        mx = mouseX.toDouble()
        my = mouseY.toDouble()
        syncMembers(now)
        val a = fade(now)
        val wrap = wrapW()
        val x0 = (width - wrap) / 2
        val top = NAV
        val view = height - top

        scrollTarget = scrollTarget.coerceIn(0f, max(0f, (contentHeight - view).toFloat()))
        scroll = springs.get("scroll", scrollTarget, 18f)
        val rise = ((1f - Ui.easeOutCubic((now - openedAt) / 320f)) * 8f).roundToInt()
        val y0 = top + 12 + rise - scroll.roundToInt()

        context.enableScissor(0, top, width, height)
        // Kopf der Seite
        var y = y0
        val ha = row(a, 0, now)
        val kicker = Text.translatable("hha.rpg.kicker").string
        val kw = Ui.label(context, textRenderer, kicker, x0, y + 2, Ui.alpha(accent, ha))
        Ui.hline(context, kw + 8, x0 + wrap, y + 5, Ui.alpha(Ui.LINE, ha))
        y += 14
        val path = HhaRpg.path(currentPath())
        val pathTitle = path?.let { Text.translatable(it.titleKey).string } ?: "HHA RPG"
        Ui.display(context, textRenderer, pathTitle, x0, y, Ui.alpha(Ui.TEXT, ha), 2)
        if (path != null) {
            val desc = textRenderer.trimToWidth(Text.translatable(path.descKey).string, wrap / 2)
            val dw = textRenderer.getWidth(desc)
            if (Ui.displayWidth(textRenderer, pathTitle, 2) + dw + 24 < wrap) {
                context.drawText(textRenderer, desc, x0 + wrap - dw, y + 6, Ui.alpha(Ui.MUTED, ha), false)
            }
        }
        y += 24

        // Bühne mit der Gruppe: nimmt den Platz, den die Felder darunter übrig lassen
        val estimate = if (panelsHeight > 0) panelsHeight else if (wrap >= WIDE) 112 else 290
        val stageH = (view - (y - y0) - estimate - 34).coerceIn(96, 240)
        stage(context, x0, y, wrap, stageH, a, now)
        y += stageH + 12

        // Drei Felder darunter
        val panelsTop = y
        y = panels(context, x0, y, wrap, a, now)
        panelsHeight = y - panelsTop
        contentHeight = y - y0 + 16 + rise
        context.disableScissor()

        // Scrollleiste rechts neben der Spalte
        if (contentHeight > view) {
            val bar = max(16, view * view / contentHeight)
            val pos = ((view - bar) * (scroll / max(1, contentHeight - view))).toInt()
            context.fill(x0 + wrap + 3, top + pos, x0 + wrap + 5, top + pos + bar, Ui.alpha(Ui.LINE_2, a))
        }

        val field = codeField
        if (field != null) {
            field.visible = RpgNet.lobby == null && departAt < 0 && a > 0.3f && field.y > top - 4 && field.y < height
        }
        super.render(context, mouseX, mouseY, deltaTicks)

        // Kopf, Meldungen und Abschied in einer Ebene über allem (auch über den 3D-Figuren)
        context.createNewRootLayer()
        nav(context, x0, wrap, a)
        drawToasts(context, a, now)
        // Eigene Ebene, sonst läge Text derselben Ebene über der Irisblende
        if (departAt >= 0) {
            context.createNewRootLayer()
            departure(context, now)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Kopfleiste (wie .nav der Website)
    // ---------------------------------------------------------------------------------------------

    private fun nav(ctx: DrawContext, x: Int, w: Int, a: Float) {
        ctx.fill(0, 0, width, NAV, Ui.alpha(0xD1000000.toInt(), a))
        Ui.hline(ctx, 0, width, NAV - 1, Ui.alpha(Ui.LINE, a))
        Ui.logo(ctx, x, 5, a)
        val end = Ui.display(ctx, textRenderer, "HHA RPG", x + 18, 7, Ui.alpha(Ui.TEXT, a))
        val (chip, chipColor) = when (RpgNet.status) {
            RpgNet.Status.ONLINE -> Text.translatable("hha.rpg.online") to Ui.GOOD
            RpgNet.Status.CONNECTING -> Text.translatable("hha.rpg.connecting") to Ui.MUTED
            else -> Text.translatable("hha.rpg.offline") to Ui.BAD
        }
        val cx2 = x + w
        val cx1 = cx2 - NAV
        val hoverClose = departAt < 0 && Ui.inside(mx, my, cx1, 0, cx2, NAV - 1)
        if (hoverClose) ctx.fill(cx1, 0, cx2, NAV - 1, Ui.alpha(Ui.SURFACE_2, a))
        Ui.vline(ctx, cx1, 0, NAV - 1, Ui.alpha(Ui.LINE, a))
        Ui.vline(ctx, cx2 - 1, 0, NAV - 1, Ui.alpha(Ui.LINE, a))
        Ui.cross(ctx, cx1 + NAV / 2, NAV / 2, Ui.alpha(if (hoverClose) Ui.TEXT else Ui.MUTED, a))
        hits.add(Hit(cx1, 0, cx2, NAV - 1) { close() })
        val cw = Ui.labelWidth(textRenderer, chip.string) + 16
        val chipX = cx1 - 8 - cw
        Ui.frame(ctx, chipX, 5, chipX + cw, 17, Ui.alpha(Ui.LINE_2, a))
        val blink = if (RpgNet.status == RpgNet.Status.CONNECTING) 0.35f + 0.65f * (0.5f + 0.5f * sin(seconds * 6f)) else 1f
        ctx.fill(chipX + 5, 9, chipX + 8, 12, Ui.alpha(chipColor, a * blink))
        Ui.label(ctx, textRenderer, chip.string, chipX + 11, 7, Ui.alpha(Ui.TEXT, a))
        val sub = Text.translatable("hha.rpg.subtitle").string
        if (end + 10 + Ui.labelWidth(textRenderer, sub) < chipX - 10) Ui.label(ctx, textRenderer, sub, end + 10, 7, Ui.alpha(Ui.DIM, a))
    }

    // ---------------------------------------------------------------------------------------------
    // Bühne
    // ---------------------------------------------------------------------------------------------

    private fun stage(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int, a: Float, now: Long) {
        val sa = row(a, 1, now)
        val lobby = RpgNet.lobby
        val depE = RpgGfx.easeInOut(departT(now))

        // Grund: dunkle Fläche mit feiner Kante, Boden als Verlauf
        ctx.fill(x, y, x + w, y + h, Ui.alpha(0x73000000, a))
        Ui.frame(ctx, x, y, x + w, y + h, Ui.alpha(Ui.lerp(Ui.LINE, accent, 0.25f + 0.5f * depE), a))
        ctx.fillGradient(x + 1, y + h / 2, x + w - 1, y + h - 1, Ui.withAlpha(accent, 0), Ui.alpha(accent, (0.10f + 0.25f * depE) * sa))
        ctx.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1)

        // Lichtstrahlen und Schein hinter der Gruppe; beim Abschied schwellen sie an
        val cx = x + w / 2
        val cy = y + h * 2 / 5
        val size = (max(w, h) * (1.15f + depE * 0.9f)).toInt()
        texture(ctx, RAYS, cx, cy, size, size, seconds * 0.025f, Ui.alpha(accent, (0.20f + depE * 0.55f) * sa))
        texture(ctx, RAYS, cx, cy, (size * 0.66f).toInt(), (size * 0.66f).toInt(), -seconds * 0.018f + 0.13f, Ui.alpha(accent, (0.12f + depE * 0.45f) * sa))
        texture(ctx, GLOW, cx, cy + h / 5, (w * 0.95f).toInt(), (h * 1.5f).toInt(), 0f, Ui.alpha(accent, (0.16f + depE * 0.7f) * sa))
        // Bodenraster wie auf der Website: feine Linien, die nach hinten dichter werden
        val feetY = y + h - 30
        for (i in 1..4) {
            val gy = feetY + 4 + i * i * 2
            if (gy < y + h - 1) Ui.hline(ctx, x + 1, x + w - 1, gy, Ui.alpha(accent, 0.05f * (5 - i) * sa))
        }
        // Schwebende Funken
        for (i in 0 until 28) {
            val px = x + (RpgGfx.hash(i) * w).toInt() + (sin(seconds * 0.6f + i) * 6f).toInt()
            val speed = 5f + RpgGfx.hash(i + 40) * 12f
            val py = y + h - ((seconds * speed * (1f + depE * 4f) + RpgGfx.hash(i + 80) * h) % (h + 10f)).toInt()
            val tw = 0.3f + 0.7f * (0.5f + 0.5f * sin(seconds * 3f + i * 1.7f))
            val s = if (i % 5 == 0) 2 else 1
            ctx.fill(px, py, px + s, py + s, Ui.alpha(Ui.brighten(accent, 0.4f), tw * 0.65f * sa))
        }

        // Plätze: so viele wie empfohlen, bei größeren Gruppen einer mehr als belegt (bis acht nebeneinander)
        val members = lobby?.members ?: emptyList()
        val recommended = lobby?.recommended ?: 4
        val wanted = members.size + if (lobby != null && members.size < lobby.max) 1 else 0
        val slots = max(recommended, wanted).coerceIn(4, 8)
        val slotW = w / slots
        val modelH = (h - 52).coerceIn(36, 150)
        for (i in 0 until slots) {
            val sx = x + i * slotW
            val scx = sx + slotW / 2
            val la = row(a, 1 + min(i, 4), now)
            val member = members.getOrNull(i)
            val shown = member ?: if (lobby == null && i == 0) selfMember() else null
            val hover = departAt < 0 && Ui.inside(mx, my, sx, y, sx + slotW, y + h)
            if (shown == null) {
                texture(ctx, SPOT, scx, feetY, (slotW * 0.8f).toInt(), 12, 0f, Ui.alpha(accent, 0.14f * la))
                emptySlot(ctx, scx, feetY, modelH, la, i, hover && lobby != null)
                if (lobby != null && departAt < 0) hits.add(Hit(sx, y, sx + slotW, y + h) { copyCode(lobby.code) })
                continue
            }
            val since = if (member != null) joinedAt[member.uuid] ?: openedAt else openedAt
            val appearT = ((now - since) / APPEAR_MS.toFloat()).coerceIn(0f, 1f)
            val pop = RpgGfx.easeOutBack(appearT)
            // Lichtsäule beim Erscheinen
            if (appearT < 1f) beam(ctx, scx, y, feetY, slotW, 1f - appearT, la)
            // Lichtfleck, bei Host/Bereit ein pulsierender Ring in Gold/Grün
            texture(ctx, SPOT, scx, feetY, (slotW * 0.95f).toInt(), 16, 0f, Ui.alpha(accent, (0.55f + 0.4f * depE) * la))
            if (member != null && (member.ready || member.host)) {
                val pulse = 0.5f + 0.5f * sin(seconds * 2.4f + i)
                val ring = if (member.host) Ui.GOLD else Ui.GOOD
                texture(ctx, SPOT, scx, feetY, (slotW * (0.55f + 0.1f * pulse)).toInt(), 9, 0f, Ui.alpha(ring, (0.45f + 0.3f * pulse) * la))
            }
            // Figur: dreht sich zur Kamera, wenn man auf sie zeigt; beim Abschied steigt sie ins Licht
            val face = springs.get("face$i", if (hover) 1f else 0f, 8f, 0f)
            val sway = sin(seconds * 0.7f + i * 1.3f) * 10f
            val yaw = (26f + sway) * (1f - face) + sin(seconds * 2f) * 4f * face
            val lift = (depE * depE * h * 0.35f).toInt()
            val bob = (sin(seconds * 1.4f + i * 0.8f) * 0.8f).roundToInt()
            val mh = (modelH * pop).toInt()
            if (mh > 6 && la > 0.05f) {
                val skin = skinOf(shown.uuid, shown.name)
                val model = if (skin.model() == PlayerSkinType.SLIM) slimModel else wideModel
                val half = max(slotW / 2 - 2, mh / 3)
                ctx.addPlayerSkin(
                    model, skin.body().texturePath(), 0.97f * mh / 2.125f, -6f, yaw, -1.0625f,
                    scx - half, feetY - mh - lift + bob, scx + half, feetY - lift + bob,
                )
            }
            // Namensschild und Zustand
            val na = la * Ui.easeOutCubic(appearT * 1.6f) * (1f - depE)
            val name = textRenderer.trimToWidth(shown.name, slotW - 10)
            val nw = textRenderer.getWidth(name)
            ctx.drawText(textRenderer, name, scx - nw / 2, feetY + 7, Ui.alpha(Ui.TEXT, na), false)
            val (tag, color) = when {
                shown.host -> Text.translatable("hha.rpg.tag.host").string to Ui.GOLD
                member == null -> Text.translatable("hha.rpg.tag.you").string to Ui.MUTED
                shown.ready -> Text.translatable("hha.rpg.tag.ready").string to Ui.GOOD
                else -> Text.translatable("hha.rpg.tag.waiting").string to Ui.DIM
            }
            val tw = Ui.labelWidth(textRenderer, tag) + 10
            Ui.frame(ctx, scx - tw / 2, feetY + 17, scx + tw / 2, feetY + 27, Ui.alpha(Ui.withAlpha(color, 0x66), na))
            Ui.label(ctx, textRenderer, tag, scx - tw / 2 + 5, feetY + 18, Ui.alpha(color, na))
            // Host darf entfernen
            if (member != null && !member.host && RpgNet.isHost() && lobby?.state == "lobby" && hover) {
                val kx = sx + slotW - 10
                val ky = y + 10
                val hk = Ui.inside(mx, my, kx - 6, ky - 6, kx + 6, ky + 6)
                Ui.frame(ctx, kx - 6, ky - 6, kx + 6, ky + 6, Ui.alpha(if (hk) Ui.BAD else Ui.LINE_2, la))
                Ui.cross(ctx, kx, ky, Ui.alpha(if (hk) Ui.BAD else Ui.MUTED, la))
                hits.add(Hit(kx - 6, ky - 6, kx + 6, ky + 6) { RpgNet.send("kick") { addProperty("uuid", member.uuid) } })
            }
        }
        // Wer gerade gegangen ist, schrumpft in einer Lichtsäule weg (nur, wenn der Platz jetzt frei ist)
        for (l in leavers) {
            if (l.slot >= slots) continue
            val t = ((now - l.at) / LEAVE_MS.toFloat()).coerceIn(0f, 1f)
            val scx = x + l.slot * slotW + slotW / 2
            beam(ctx, scx, y, feetY, slotW, 1f - t, sa)
            val mh = (modelH * (1f - Ui.easeOutCubic(t))).toInt()
            if (mh > 6 && l.slot >= members.size) {
                val skin = skinOf(l.uuid, l.name)
                val model = if (skin.model() == PlayerSkinType.SLIM) slimModel else wideModel
                val half = max(slotW / 2 - 2, mh / 3)
                ctx.addPlayerSkin(model, skin.body().texturePath(), 0.97f * mh / 2.125f, -6f, 26f + t * 220f, -1.0625f, scx - half, feetY - mh, scx + half, feetY)
            }
        }
        ctx.disableScissor()

        // Ecken: Gruppengröße links, Lobby-Code rechts
        val countText = if (lobby == null) Text.translatable("hha.rpg.stage.solo").string
        else Text.translatable("hha.rpg.members", members.size, recommended).string
        val over = lobby != null && members.size > recommended
        Ui.label(ctx, textRenderer, countText, x + 8, y + 7, Ui.alpha(if (over) Ui.GOLD else Ui.DIM, sa))
        if (lobby != null) {
            val codeText = Text.translatable("hha.rpg.code").string + " " + lobby.code
            val cw = Ui.labelWidth(textRenderer, codeText)
            Ui.label(ctx, textRenderer, codeText, x + w - 8 - cw, y + 7, Ui.alpha(Ui.MUTED, sa))
        }
    }

    /** Senkrechte Lichtsäule (Erscheinen/Verschwinden), [k] 1 = voll. */
    private fun beam(ctx: DrawContext, cx: Int, top: Int, feetY: Int, slotW: Int, k: Float, a: Float) {
        if (k <= 0f) return
        val bw = (slotW * (0.18f + 0.35f * k)).toInt().coerceAtLeast(2)
        ctx.fillGradient(cx - bw / 2, top, cx + bw / 2, feetY, Ui.withAlpha(accent, 0), Ui.alpha(Ui.brighten(accent, 0.3f), 0.55f * k * a))
        val core = max(1, bw / 6)
        ctx.fillGradient(cx - core, top, cx + core, feetY, 0x00FFFFFF, Ui.alpha(0xFFFFFFFF.toInt(), 0.5f * k * a))
        texture(ctx, GLOW, cx, feetY, slotW, 30, 0f, Ui.alpha(0xFFFFFFFF.toInt(), 0.5f * k * a))
    }

    /** Freier Platz: gestrichelte Silhouette, die leise atmet, darunter „frei“ (mit Lobby: Klick kopiert den Code). */
    private fun emptySlot(ctx: DrawContext, cx: Int, feetY: Int, modelH: Int, a: Float, i: Int, invite: Boolean) {
        val breath = 0.5f + 0.5f * (0.5f + 0.5f * sin(seconds * 1.6f + i * 0.9f))
        val inv = springs.get("invite$i", if (invite) 1f else 0f, 12f, 0f)
        val c = Ui.alpha(Ui.lerp(Ui.LINE_2, accent, inv), a * max(breath, inv))
        val u = max(1, modelH / 32)
        val head = 8 * u
        val body = 12 * u
        val legs = 12 * u
        val bw = 8 * u
        val arm = 4 * u
        val topY = feetY - head - body - legs
        dashed(ctx, cx - head / 2, topY, cx + head / 2, topY + head, c)
        dashed(ctx, cx - bw / 2, topY + head + 1, cx + bw / 2, topY + head + body, c)
        dashed(ctx, cx - bw / 2 - arm - 1, topY + head + 1, cx - bw / 2 - 1, topY + head + body, c)
        dashed(ctx, cx + bw / 2 + 1, topY + head + 1, cx + bw / 2 + arm + 1, topY + head + body, c)
        dashed(ctx, cx - bw / 2, topY + head + body + 1, cx - 1, feetY, c)
        dashed(ctx, cx + 1, topY + head + body + 1, cx + bw / 2, feetY, c)
        val label = Text.translatable(if (inv > 0.5f) "hha.rpg.tag.invite" else "hha.rpg.tag.free").string
        val pw = Ui.labelWidth(textRenderer, label)
        Ui.label(ctx, textRenderer, label, cx - pw / 2, feetY + 12, Ui.alpha(Ui.lerp(Ui.DIM, accent, inv), a))
    }

    private fun dashed(ctx: DrawContext, x1: Int, y1: Int, x2: Int, y2: Int, color: Int) {
        var x = x1
        while (x < x2) {
            ctx.fill(x, y1, min(x + 2, x2), y1 + 1, color)
            ctx.fill(x, y2 - 1, min(x + 2, x2), y2, color)
            x += 4
        }
        var y = y1
        while (y < y2) {
            ctx.fill(x1, y, x1 + 1, min(y + 2, y2), color)
            ctx.fill(x2 - 1, y, x2, min(y + 2, y2), color)
            y += 4
        }
    }

    private fun selfMember(): RpgNet.Member? {
        val session = client?.session ?: return null
        val id = session.uuidOrNull?.toString() ?: return null
        return RpgNet.Member(id, session.username, host = false, ready = false, addons = emptyList())
    }

    private fun skinOf(uuid: String, name: String): SkinTextures {
        val supplier = skins.getOrPut(uuid) {
            val id = runCatching { UUID.fromString(uuid) }.getOrNull()
            val mc = client
            when {
                id == null || mc == null -> Supplier { DefaultSkinHelper.getSteve() }
                id == mc.session.uuidOrNull -> runCatching { mc.skinProvider.supplySkinTextures(mc.gameProfile, true) }
                    .getOrElse { Supplier { DefaultSkinHelper.getSkinTextures(id) } }
                else -> runCatching { mc.skinProvider.supplySkinTextures(GameProfile(id, name), false) }
                    .getOrElse { Supplier { DefaultSkinHelper.getSkinTextures(id) } }
            }
        }
        return supplier.get()
    }

    // ---------------------------------------------------------------------------------------------
    // Felder: Weg, Addons, Gruppe
    // ---------------------------------------------------------------------------------------------

    private fun panels(ctx: DrawContext, x: Int, y: Int, w: Int, a: Float, now: Long): Int {
        if (w >= WIDE) {
            val pw = (w * 0.40f).toInt()
            val aw = (w * 0.25f).toInt()
            val lw = w - pw - aw - 20
            val b1 = pathPanel(ctx, x, y + sink(2, now), pw, row(a, 2, now))
            val b2 = addonPanel(ctx, x + pw + 10, y + sink(3, now), aw, row(a, 3, now))
            val b3 = lobbyPanel(ctx, x + pw + aw + 20, y + sink(4, now), lw, row(a, 4, now), now)
            return max(b1, max(b2, b3))
        }
        var yy = pathPanel(ctx, x, y + sink(2, now), w, row(a, 2, now))
        yy = addonPanel(ctx, x, yy + 10 + sink(3, now), w, row(a, 3, now))
        return lobbyPanel(ctx, x, yy + 10 + sink(4, now), w, row(a, 4, now), now)
    }

    /** Überschrift eines Felds wie auf der Website: Nummer, Titel, feine Linie bis zum Rand. */
    private fun panelHead(ctx: DrawContext, x: Int, y: Int, w: Int, index: Int, key: String, color: Int, a: Float): Int {
        val end = Ui.label(ctx, textRenderer, "0$index", x, y, Ui.alpha(color, a))
        val end2 = Ui.label(ctx, textRenderer, Text.translatable(key).string, end + 6, y, Ui.alpha(Ui.TEXT, a))
        Ui.hline(ctx, end2 + 8, x + w, y + 3, Ui.alpha(Ui.LINE, a))
        return y + 14
    }

    private fun pathIcon(id: String): ItemStack = when (id) {
        "heaven" -> ItemStack(HhaItems.HEAVENS_SWORD)
        "hell" -> ItemStack(HhaItems.HELLS_SWORD)
        else -> ItemStack(HhaItems.HEAVEN_INGOT)
    }

    private fun pathPanel(ctx: DrawContext, x: Int, y0: Int, w: Int, a: Float): Int {
        var y = panelHead(ctx, x, y0, w, 1, "hha.rpg.section.path", accent, a)
        val visible = HhaRpg.paths().filter { it.addonId == Hha.MOD_ID || it.addonId in currentAddons() }
        val h = 46
        ctx.fill(x, y, x + w, y + visible.size * (h + 1) + 1, Ui.alpha(Ui.LINE, a))
        for (p in visible) {
            val ty = y + 1
            val on = p.id == currentPath()
            val editable = canEdit()
            val hover = editable && Ui.inside(mx, my, x, ty, x + w, ty + h)
            val hv = springs.get("path:${p.id}", if (hover) 1f else 0f, 16f, 0f)
            val sel = springs.get("pathOn:${p.id}", if (on) 1f else 0f, 12f, if (on) 1f else 0f)
            ctx.fill(x + 1, ty, x + w - 1, ty + h, Ui.alpha(Ui.lerp(Ui.SURFACE, Ui.SURFACE_2, max(hv, sel * 0.7f)), a))
            if (sel > 0.01f) {
                ctx.fillGradient(x + 1, ty, x + w - 1, ty + h, Ui.alpha(p.color, 0.10f * sel * a), Ui.withAlpha(p.color, 0))
                ctx.fill(x + 1, ty, x + 1 + max(1, (3 * sel).roundToInt()), ty + h, Ui.alpha(p.color, a * sel))
            }
            // Waffe als Symbol mit Schein, die gewählte schwebt leicht
            val ix = x + 10
            val iy = ty + 7
            texture(ctx, GLOW, ix + 16, iy + 16, 56, 56, 0f, Ui.alpha(p.color, (0.12f + 0.28f * sel + 0.1f * hv) * a))
            val m = ctx.matrices
            m.pushMatrix()
            m.translate(ix.toFloat(), iy + (sin(seconds * 2f + p.id.length) * 1.5f * sel) - hv * 1.5f)
            m.scale(2f, 2f)
            if (a > 0.5f) ctx.drawItem(pathIcon(p.id), 0, 0)
            m.popMatrix()
            val tx = x + 52
            Ui.display(ctx, textRenderer, Text.translatable(p.titleKey).string, tx, ty + 8, Ui.alpha(Ui.lerp(Ui.TEXT, p.color, sel), a))
            textRenderer.wrapLines(Text.translatable(p.descKey), w - (tx - x) - 24).take(2).forEachIndexed { i, line ->
                ctx.drawText(textRenderer, line, tx, ty + 21 + i * 10, Ui.alpha(Ui.MUTED, a), false)
            }
            // Auswahlpunkt rechts
            val rx = x + w - 14
            val ry = ty + h / 2
            Ui.frame(ctx, rx - 4, ry - 4, rx + 4, ry + 4, Ui.alpha(Ui.lerp(Ui.LINE_2, p.color, sel), a))
            if (sel > 0.05f) {
                val s = max(1, (2 * sel).roundToInt())
                ctx.fill(rx - s, ry - s, rx + s, ry + s, Ui.alpha(p.color, a * sel))
            }
            if (editable) hits.add(Hit(x, ty, x + w, ty + h) { choosePath(p.id) })
            y += h + 1
        }
        return y + 1
    }

    private fun addonPanel(ctx: DrawContext, x: Int, y0: Int, w: Int, a: Float): Int {
        val y = panelHead(ctx, x, y0, w, 2, "hha.rpg.section.addons", Ui.TEAL, a)
        val installed = RpgNet.installedAddons()
        if (installed.isEmpty()) {
            val lines = textRenderer.wrapLines(Text.translatable("hha.rpg.addons.none"), w - 16)
            val h = 12 + lines.size * 10
            ctx.fill(x, y, x + w, y + h, Ui.alpha(Ui.LINE, a))
            ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, Ui.alpha(Ui.SURFACE, a))
            lines.forEachIndexed { i, line -> ctx.drawText(textRenderer, line, x + 8, y + 7 + i * 10, Ui.alpha(Ui.DIM, a), false) }
            return y + h
        }
        val h = 32
        var ty = y + 1
        ctx.fill(x, y, x + w, y + installed.size * (h + 1) + 1, Ui.alpha(Ui.LINE, a))
        for (addon in installed) {
            val on = addon.id in currentAddons()
            val editable = addon.rpg && canEdit()
            val hover = editable && Ui.inside(mx, my, x, ty, x + w, ty + h)
            val hv = springs.get("addon:${addon.id}", if (hover) 1f else 0f, 16f, 0f)
            val active = on && addon.rpg
            val onT = springs.get("addonOn:${addon.id}", if (active) 1f else 0f, 14f, if (active) 1f else 0f)
            ctx.fill(x + 1, ty, x + w - 1, ty + h, Ui.alpha(Ui.lerp(Ui.SURFACE, Ui.SURFACE_2, max(hv, onT * 0.5f)), a))
            if (onT > 0.01f) ctx.fill(x + 1, ty, x + 2, ty + h, Ui.alpha(Ui.TEAL, onT * a))
            val nameW = w - 46
            ctx.drawText(textRenderer, textRenderer.trimToWidth(addon.name, nameW), x + 9, ty + 7, Ui.alpha(if (addon.rpg) Ui.TEXT else Ui.DIM, a), false)
            val sub = when {
                !addon.rpg -> Text.translatable("hha.rpg.addons.unsupported").string
                on -> Text.translatable("hha.rpg.addons.on").string
                else -> Text.translatable("hha.rpg.addons.off").string
            }
            Ui.label(ctx, textRenderer, sub, x + 9, ty + 19, Ui.alpha(if (active) Ui.TEAL else Ui.DIM, a))
            if (addon.rpg) {
                switch(ctx, x + w - 28, ty + 11, on, Ui.TEAL, a, "addon:${addon.id}", editable)
                if (editable) hits.add(Hit(x, ty, x + w, ty + h) { toggleAddon(addon.id) })
            }
            ty += h + 1
        }
        return ty
    }

    private fun lobbyPanel(ctx: DrawContext, x: Int, y0: Int, w: Int, a: Float, now: Long): Int {
        var y = panelHead(ctx, x, y0, w, 3, "hha.rpg.section.party", Ui.GOLD, a)
        val lobby = RpgNet.lobby
        val online = RpgNet.status == RpgNet.Status.ONLINE
        val field = codeField
        if (lobby == null) {
            if (RpgNet.status == RpgNet.Status.FAILED) {
                y = notice(ctx, x, y, w, Text.translatable("hha.rpg.notice.offline"), Ui.BAD, a)
                button(ctx, x, y, w, Text.translatable("hha.rpg.retry"), Kind.GHOST, a, "retry") { RpgNet.connect() }
                y += 22
            }
            button(ctx, x, y, w, Text.translatable("hha.rpg.create"), Kind.PRIMARY, a, "create", enabled = online && departAt < 0) { createLobby() }
            y += 25
            // Trenner „oder mit Code beitreten“
            val or = Text.translatable("hha.rpg.or_join").string
            val ow = Ui.labelWidth(textRenderer, or)
            if (ow + 24 < w) {
                Ui.hline(ctx, x, x + (w - ow) / 2 - 6, y + 3, Ui.alpha(Ui.LINE, a))
                Ui.hline(ctx, x + (w + ow) / 2 + 6, x + w, y + 3, Ui.alpha(Ui.LINE, a))
            }
            Ui.label(ctx, textRenderer, or, x + max(0, (w - ow) / 2), y, Ui.alpha(Ui.DIM, a))
            y += 13
            val bw = min(78, w / 3)
            if (field != null) {
                // Rahmen im Website-Stil um das Feld (das Feld selbst zeichnet nur Text und Cursor)
                val focus = springs.get("codeFocus", if (field.isFocused) 1f else 0f, 14f, 0f)
                ctx.fill(x, y, x + w - bw - 5, y + 16, Ui.alpha(Ui.SURFACE, a))
                Ui.frame(ctx, x, y, x + w - bw - 5, y + 16, Ui.alpha(Ui.lerp(Ui.LINE_2, accent, focus), a))
                field.x = x + 6
                field.y = y + 4
                field.width = w - bw - 16
                field.height = 10
            }
            val code = field?.text?.trim().orEmpty()
            button(ctx, x + w - bw, y, bw, Text.translatable("hha.rpg.join"), Kind.GHOST, a, "join", enabled = online && code.length >= 4 && departAt < 0) { joinLobby() }
            y += 24
            val lines = textRenderer.wrapLines(Text.translatable("hha.rpg.hint.short"), w)
            lines.forEachIndexed { i, line -> ctx.drawText(textRenderer, line, x, y + i * 10, Ui.alpha(Ui.DIM, a), false) }
            return y + lines.size * 10
        }
        // Code als Kacheln, die nacheinander aufklappen
        val code = lobby.code
        val copyW = min(70, w / 3)
        val tile = ((w - copyW - 8 - (code.length - 1) * 3) / max(1, code.length)).coerceIn(12, 22)
        for ((i, ch) in code.withIndex()) {
            val t = Ui.easeOutCubic(((now - lobbyShownAt) / 1000f * 4f - i * 0.12f).coerceIn(0f, 1f))
            val tx = x + i * (tile + 3)
            val hh = max(1, (tile * t).roundToInt())
            val ty = y + (tile - hh) / 2
            ctx.fill(tx, ty, tx + tile, ty + hh, Ui.alpha(Ui.SURFACE_2, a))
            Ui.frame(ctx, tx, ty, tx + tile, ty + hh, Ui.alpha(Ui.lerp(accent, Ui.LINE_2, t), a))
            if (t > 0.6f) {
                val s = ch.toString()
                val cw = Ui.displayWidth(textRenderer, s)
                Ui.display(ctx, textRenderer, s, tx + (tile - cw + 1) / 2, y + (tile - 7) / 2, Ui.alpha(Ui.TEXT, a * ((t - 0.6f) / 0.4f)))
            }
        }
        button(ctx, x + w - copyW, y + (tile - 16) / 2, copyW, Text.translatable("hha.rpg.copy"), Kind.GHOST, a, "copy") { copyCode(code) }
        y += tile + 8
        val mine = RpgNet.installedAddons().filter { it.rpg }.mapTo(HashSet()) { it.id }
        val missing = lobby.addons.filter { it !in mine }
        if (missing.isNotEmpty()) y = notice(ctx, x, y, w, Text.translatable("hha.rpg.notice.missing", missing.joinToString(", ")), Ui.BAD, a)
        if (lobby.state != "lobby") y = notice(ctx, x, y, w, Text.translatable("hha.rpg.notice.running"), Ui.TEAL, a)
        val everyoneReady = lobby.members.size > 1 && lobby.members.all { it.host || it.ready }
        if (RpgNet.isHost()) {
            val label = Text.translatable(if (everyoneReady) "hha.rpg.start.all_ready" else "hha.rpg.start")
            button(ctx, x, y, w, label, Kind.PRIMARY, a, "start", enabled = lobby.state == "lobby" && departAt < 0, glow = everyoneReady) {
                RpgNet.send("start")
            }
            y += 20
            val waiting = lobby.members.count { !it.host && !it.ready }
            val sub = when {
                lobby.members.size <= 1 -> Text.translatable("hha.rpg.start.alone")
                waiting > 0 -> Text.translatable("hha.rpg.start.waiting", waiting)
                else -> Text.translatable("hha.rpg.start.go")
            }
            Ui.label(ctx, textRenderer, sub.string, x, y, Ui.alpha(if (everyoneReady) Ui.GOOD else Ui.DIM, a))
            y += 12
        } else {
            val me = lobby.members.firstOrNull { it.uuid == myUuid() }
            val ready = me?.ready == true
            button(
                ctx, x, y, w, Text.translatable(if (ready) "hha.rpg.unready" else "hha.rpg.ready"), if (ready) Kind.GHOST else Kind.PRIMARY,
                a, "ready", enabled = lobby.state == "lobby" && departAt < 0, glow = !ready && lobby.state == "lobby",
            ) {
                RpgNet.send("ready") { addProperty("ready", !ready) }
            }
            y += 20
            val host = lobby.members.firstOrNull { it.host }?.name ?: "?"
            Ui.label(ctx, textRenderer, Text.translatable("hha.rpg.start.by_host", host).string, x, y, Ui.alpha(Ui.DIM, a))
            y += 12
        }
        button(ctx, x, y, w, Text.translatable("hha.rpg.leave"), Kind.DANGER, a, "leave", enabled = departAt < 0) { RpgNet.send("leave") }
        return y + 16
    }

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

    private fun button(
        ctx: DrawContext, x: Int, y: Int, w: Int, label: Text, kind: Kind, a: Float, id: String,
        enabled: Boolean = true, glow: Boolean = false, onClick: () -> Unit,
    ) {
        val h = 16
        val hover = enabled && Ui.inside(mx, my, x, y, x + w, y + h)
        val hv = springs.get("btn:$id", if (hover) 1f else 0f, 18f, 0f)
        val k = if (enabled) 1f else 0.35f
        val glowing = glow && enabled
        val pulse = if (glowing) 0.5f + 0.5f * sin(seconds * 3.2f) else 0f
        if (glowing) texture(ctx, GLOW, x + w / 2, y + h / 2, w + 48, h + 34, 0f, Ui.alpha(accent, (0.22f + 0.22f * pulse) * a))
        val lift = if (kind == Kind.PRIMARY) hv.roundToInt() else 0
        val text = when (kind) {
            Kind.PRIMARY -> {
                ctx.fill(x, y - lift, x + w, y + h - lift, Ui.alpha(Ui.brighten(accent, 0.14f * hv + 0.08f * pulse), a * k))
                // Glanzkante, die beim Zeigen über den Knopf läuft
                if (hv > 0.02f && enabled) {
                    val sx = x + ((seconds * 0.9f % 1f) * (w + 24)).toInt() - 12
                    ctx.fill(max(x, sx), y - lift, min(x + w, sx + 6), y + h - lift, Ui.alpha(0x40FFFFFF, hv * a))
                }
                Ui.ON_ACCENT
            }
            Kind.GHOST -> {
                if (hv > 0.01f) ctx.fill(x, y, x + w, y + h, Ui.alpha(Ui.SURFACE_2, hv * a))
                Ui.frame(ctx, x, y, x + w, y + h, Ui.alpha(Ui.lerp(Ui.LINE_2, Ui.TEXT, hv * 0.7f), a * k))
                Ui.TEXT
            }
            Kind.DANGER -> {
                Ui.frame(ctx, x, y, x + w, y + h, Ui.alpha(Ui.lerp(Ui.LINE_2, Ui.BAD, hv), a * k))
                Ui.lerp(Ui.MUTED, Ui.BAD, hv)
            }
        }
        val s = label.string
        val lw = Ui.labelWidth(textRenderer, s)
        Ui.label(ctx, textRenderer, s, x + (w - lw) / 2, y + 5 - lift, Ui.alpha(text, a * k))
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
        ctx.fill(x, y, x + w, y + h, Ui.alpha(0x0DFFFFFF, a))
        ctx.fill(x, y, x + 2, y + h, Ui.alpha(color, a))
        lines.forEachIndexed { i, line -> ctx.drawText(textRenderer, line, x + 9, y + 5 + i * 10, Ui.alpha(Ui.TEXT, a), false) }
        return y + h + 6
    }

    /** Meldungen unten rechts: gleiten herein und verblassen nach drei Sekunden. */
    private fun drawToasts(ctx: DrawContext, a: Float, now: Long) {
        toasts.removeIf { now - it.at > 3200 }
        var y = height - 10
        for (t in toasts.asReversed()) {
            val age = (now - t.at) / 1000f
            val inT = Ui.easeOutCubic(age / 0.25f)
            val outT = (1f - (age - 2.8f) / 0.4f).coerceIn(0f, 1f)
            val ta = a * inT * outT
            val w = textRenderer.getWidth(t.text) + 22
            val x = width - 10 - w + ((1f - inT) * 24f).toInt()
            y -= 22
            ctx.fill(x, y, x + w, y + 18, Ui.alpha(0xF00B0B0E.toInt(), ta))
            Ui.frame(ctx, x, y, x + w, y + 18, Ui.alpha(Ui.LINE_2, ta))
            ctx.fill(x, y, x + 2, y + 18, Ui.alpha(t.color, ta))
            ctx.drawText(textRenderer, t.text, x + 12, y + 5, Ui.alpha(Ui.TEXT, ta), false)
        }
    }

    /** Abschied: erst schwillt das Licht an (Bühne), dann schließt sich die Irisblende zur Mitte. */
    private fun departure(ctx: DrawContext, now: Long) {
        if (departAt < 0) return
        val t = departT(now)
        // Heller Schein, der kurz vor dem Schließen aufleuchtet
        val flash = (1f - abs((t - 0.45f) / 0.2f)).coerceIn(0f, 1f)
        if (flash > 0f) ctx.fill(0, 0, width, height, Ui.alpha(Ui.brighten(accent, 0.6f), 0.3f * flash))
        val closeT = ((t - 0.3f) / 0.7f).coerceIn(0f, 1f)
        if (closeT <= 0f) return
        val r = (max(width, height) * 0.9f * (1f - RpgGfx.easeInOut(closeT))).toInt()
        RpgGfx.iris(ctx, width, height, width / 2, height / 2, r)
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (departAt >= 0) return true
        if (super.mouseClicked(click, doubled)) return true
        codeField?.isFocused = false
        for (hit in hits.asReversed()) {
            if (Ui.inside(click.x(), click.y(), hit.x1, hit.y1, hit.x2, hit.y2)) {
                hit.action()
                return true
            }
        }
        return false
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        scrollTarget -= (verticalAmount * 26).toFloat()
        return true
    }

    override fun keyPressed(input: KeyInput): Boolean {
        val field = codeField
        if (field != null && field.isFocused && (input.key() == GLFW.GLFW_KEY_ENTER || input.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            joinLobby()
            return true
        }
        return super.keyPressed(input)
    }

    companion object {
        private const val NAV = 22
        private const val WIDE = 560
        private const val APPEAR_MS = 520L
        private const val LEAVE_MS = 480L

        /** In Yarn heißen zwei Felder `PLAYER_SLIM` (Modell und Rüstung); Kotlin kann sie nicht unterscheiden. */
        private val PLAYER_SLIM = EntityModelLayer(Identifier.ofVanilla("player_slim"), "main")
    }
}
