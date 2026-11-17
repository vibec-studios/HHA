package dev.henny.hha.client.rpg

import dev.henny.hha.Hha
import dev.henny.hha.api.rpg.HhaRpg
import dev.henny.hha.client.gui.Ui
import dev.henny.hha.rpg.RpgWorld
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen
import net.minecraft.client.network.ServerAddress
import net.minecraft.client.network.ServerInfo
import net.minecraft.resource.DataConfiguration
import net.minecraft.resource.featuretoggle.FeatureFlags
import net.minecraft.text.Text
import net.minecraft.util.NetworkUtils
import net.minecraft.world.Difficulty
import net.minecraft.world.GameMode
import net.minecraft.world.gen.GeneratorOptions
import net.minecraft.world.gen.WorldPresets
import net.minecraft.world.level.LevelInfo
import net.minecraft.world.rule.GameRules
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Bausteine des Starts, die seit Überarbeitung 2 unverändert gelten: Welt des Hosts erzeugen, für den Tunnel per LAN
 * öffnen und `open` melden, als Mitspieler über den lokalen Tunnel-Port verbinden. Den Ablauf (Zustände, Zeiten,
 * Rückfälle) führt seit Überarbeitung 3 [RpgJourney]; die Eingänge vom Lobby-Dienst ([RpgNet]) reichen hier durch.
 *
 * [drawOverlay] ist die 2D-Startanimation des Bestands. Sie bleibt als Rückfall, wenn der Tunnel-Shader ungültig ist
 * (PLAN 5).
 */
object RpgLaunch {

    // ---------------------------------------------------------------------------------------------
    // Eingänge vom Lobby-Dienst (RpgNet ruft sie so; der Ablauf liegt in RpgJourney)
    // ---------------------------------------------------------------------------------------------

    /** Lobby-Dienst: Der Host hat gestartet. */
    fun onStarting() = RpgJourney.onStarting()

    /** Lobby-Dienst: Die Welt des Hosts ist offen. */
    fun onGo() = RpgJourney.onGo()

    fun onLobbyClosed() = RpgJourney.onLobbyClosed()

    fun onBackendLost() = RpgJourney.onBackendLost()

    // ---------------------------------------------------------------------------------------------
    // Welt, LAN, Verbindung (Verhalten wie bisher)
    // ---------------------------------------------------------------------------------------------

    /**
     * Host: legt die Wahl samt der erwarteten Spieler ([expected], UUIDs) für den eigenen Server ab und erzeugt die
     * Welt. [parent] erscheint, wenn Minecraft die Erzeugung abbricht. false = sofort gescheitert.
     */
    fun createWorld(client: MinecraftClient, lobby: RpgNet.Lobby, expected: List<String>, parent: Screen): Boolean {
        RpgWorld.pending = RpgWorld.Setup(lobby.path, lobby.addons, lobby.code, expected)
        val name = "HHA RPG " + SimpleDateFormat("yyyy-MM-dd HH-mm").format(Date())
        val info = LevelInfo(
            name, GameMode.SURVIVAL, false, Difficulty.NORMAL, false,
            GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE,
        )
        return try {
            client.createIntegratedServerLoader().createAndStart(name, info, GeneratorOptions.createRandom(), WorldPresets::createDemoOptions, parent)
            true
        } catch (e: Exception) {
            Hha.LOGGER.error("RPG: Welt konnte nicht erzeugt werden", e)
            RpgWorld.pending = null
            false
        }
    }

    /** Host, in der eigenen Welt: für den Tunnel öffnen (nur lokal) und `open` melden. */
    fun openToLan(client: MinecraftClient) {
        val server = client.server ?: return
        if (server.isRemote) return
        val port = NetworkUtils.findLocalPort()
        if (server.openToLan(GameMode.SURVIVAL, false, port)) {
            RpgTunnels.lanPort = port
            RpgNet.send("open")
        } else {
            Hha.LOGGER.error("RPG: Welt ließ sich nicht für LAN öffnen")
        }
    }

    /** Mitspieler: lokalen Tunnel-Port öffnen und verbinden. [parent] erscheint nach Abbruch oder Fehler. */
    fun connect(client: MinecraftClient, parent: Screen): Boolean {
        val port = try {
            RpgTunnels.startJoinListener()
        } catch (e: Exception) {
            Hha.LOGGER.error("RPG: lokaler Tunnel-Port nicht verfügbar", e)
            return false
        }
        val info = ServerInfo(Text.translatable("hha.rpg.server_name").string, "127.0.0.1:$port", ServerInfo.ServerType.OTHER)
        ConnectScreen.connect(parent, client, ServerAddress("127.0.0.1", port), info, false, null)
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // 2D-Rückfall der Startanimation (Bestand)
    // ---------------------------------------------------------------------------------------------

    /** Was die 2D-Animation zeigt; [exit] 0..1 öffnet die Iris über der Welt, [flash] ist der Lichtgipfel. */
    class Overlay(
        val time: Float, val accent: Int, val pathId: String, val title: String, val step: String,
        val party: List<RpgNet.Member>, val progress: Float, val travel: Float, val exit: Float, val flash: Float,
    )

    /**
     * Deckende Startanimation ohne Shader: Portal aus zwei Ringen mit Strahlen, Lichtschlieren wie bei einer Reise,
     * Partikel des Wegs (Himmel: sinkende Lichtfedern, Unterwelt: steigende Glut), Titel Buchstabe für Buchstabe, die
     * Gruppe, der Schritt und eine Leiste. Im Spiel öffnet sich die Iris ganz und gibt die Welt frei.
     */
    fun drawOverlay(ctx: DrawContext, width: Int, height: Int, o: Overlay) {
        if (o.exit >= 1f) return
        val t = o.time
        val tr = MinecraftClient.getInstance().textRenderer
        val a = 1f - Ui.easeOutCubic((o.exit / 0.5f).coerceIn(0f, 1f))
        val diag = kotlin.math.hypot(width.toDouble(), height.toDouble()).toFloat()
        val cx = width / 2
        val portalSize = (min(width, height) * 0.5f).toInt().coerceAtLeast(60)
        val cy = (height * 0.40f).toInt().coerceAtLeast(portalSize / 2 + 10)
        val step = RpgGfx.easeInOut(o.exit)
        if (o.exit <= 0f) {
            ctx.fill(0, 0, width, height, 0xFF000000.toInt())
        } else {
            val r0 = portalSize * 0.3f / 0.63f
            val r1 = diag * 0.5f / 0.6f
            RpgGfx.iris(ctx, width, height, cx, cy, (r0 + (r1 - r0) * step).toInt())
        }
        val intro = Ui.easeOutCubic(t / 0.9f)
        val travel = o.travel + step * 5f
        val accent = o.accent

        // 1. Schein und Strahlen hinter dem Portal
        RpgGfx.texture(ctx, RpgGfx.GLOW, cx, cy, (diag * 0.9f).toInt(), (diag * 0.9f).toInt(), 0f, Ui.alpha(accent, 0.22f * a * intro))
        val raysSize = (max(width, height) * (1.1f + 0.15f * intro)).toInt()
        RpgGfx.texture(ctx, RpgGfx.RAYS, cx, cy, raysSize, raysSize, t * 0.03f, Ui.alpha(accent, 0.30f * a * intro))
        RpgGfx.texture(ctx, RpgGfx.RAYS, cx, cy, (raysSize * 0.7f).toInt(), (raysSize * 0.7f).toInt(), -t * 0.022f + 0.11f, Ui.alpha(Ui.brighten(accent, 0.3f), 0.18f * a * intro))

        // 2. Lichtschlieren: fliegen vom Portal nach außen und werden dabei länger (Reisegefühl)
        val m = ctx.matrices
        for (i in 0 until 56) {
            val angle = RpgGfx.hash(i * 7 + 1) * (2f * PI.toFloat())
            val speed = (0.22f + RpgGfx.hash(i * 7 + 2) * 0.35f) * travel
            val p = ((t * speed + RpgGfx.hash(i * 7 + 3)) % 1f)
            val r = portalSize * 0.32f + p * p * diag * 0.6f
            val len = 2f + p * p * 40f * (0.6f + 0.4f * travel.coerceAtMost(2f))
            val la = (p * (1f - p) * 4f).coerceIn(0f, 1f) * 0.55f * a * intro
            if (la < 0.02f) continue
            m.pushMatrix()
            m.translate(cx.toFloat(), cy.toFloat())
            m.rotate(angle)
            ctx.fill(r.toInt(), 0, (r + len).toInt(), 1, Ui.alpha(if (i % 3 == 0) 0xFFFFFFFF.toInt() else Ui.brighten(accent, 0.35f), la))
            m.popMatrix()
        }

        // 3. Partikel des Wegs
        particles(ctx, width, height, t, a * intro, o.pathId, accent)

        // 4. Portal: zwei Ringe, die gegeneinander drehen, innen ein Kern aus Licht mit dem Logo
        val ringIn = RpgGfx.easeOutBack(((t - 0.1f) / 0.7f).coerceIn(0f, 1f))
        val ring = (portalSize * ringIn * (1f + step * diag * 1.5f / portalSize)).toInt()
        val ra = 1f - o.exit
        val breathe = 0.5f + 0.5f * sin(t * 2.2f)
        RpgGfx.texture(ctx, RpgGfx.GLOW, cx, cy, (ring * 1.25f).toInt(), (ring * 1.25f).toInt(), 0f, Ui.alpha(accent, (0.35f + 0.15f * breathe) * a))
        RpgGfx.texture(ctx, RpgGfx.RING, cx, cy, ring, ring, t * 0.045f, Ui.alpha(accent, 0.9f * ra))
        RpgGfx.texture(ctx, RpgGfx.RING, cx, cy, (ring * 0.74f).toInt(), (ring * 0.74f).toInt(), -t * 0.07f + 0.05f, Ui.alpha(Ui.brighten(accent, 0.45f), 0.55f * ra))
        RpgGfx.texture(ctx, RpgGfx.GLOW, cx, cy, (ring * 0.55f).toInt(), (ring * 0.55f).toInt(), 0f, Ui.alpha(0xFFFFFFFF.toInt(), (0.30f + 0.2f * breathe) * a * ringIn.coerceIn(0f, 1f)))
        val logoA = a * Ui.easeOutCubic(((t - 0.25f) / 0.5f).coerceIn(0f, 1f))
        if (logoA > 0.01f) {
            val s = (portalSize / 60).coerceIn(2, 4)
            m.pushMatrix()
            m.translate((cx - 6 * s).toFloat(), cy - 5.5f * s + sin(t * 1.6f) * 1.5f)
            m.scale(s.toFloat(), s.toFloat())
            Ui.logo(ctx, 0, 0, logoA)
            m.popMatrix()
        }

        // 5. Text: Weg (klein), Titel Buchstabe für Buchstabe
        var y = cy + portalSize / 2 + 12
        val kicker = HhaRpg.path(o.pathId)?.let { Text.translatable(it.titleKey).string } ?: "HHA RPG"
        val kA = a * Ui.easeOutCubic(((t - 0.2f) / 0.4f).coerceIn(0f, 1f))
        val kw = Ui.labelWidth(tr, kicker)
        Ui.label(ctx, tr, kicker, cx - kw / 2, y, Ui.alpha(accent, kA))
        Ui.hline(ctx, cx - kw / 2 - 26, cx - kw / 2 - 8, y + 3, Ui.alpha(accent, kA * 0.6f))
        Ui.hline(ctx, cx + kw / 2 + 8, cx + kw / 2 + 26, y + 3, Ui.alpha(accent, kA * 0.6f))
        y += 12
        staggered(ctx, tr, o.title, cx, y, t - 0.35f, a)
        y += 30

        // 6. Die Gruppe: Namen nacheinander, Host in Gold
        if (o.party.isNotEmpty() && y + 10 < height - 34) {
            val names = o.party.take(8)
            val widths = names.map { tr.getWidth(it.name) + 12 }
            val total = widths.sum() + (names.size - 1) * 10
            var x = cx - total / 2
            names.forEachIndexed { i, member ->
                val na = a * Ui.easeOutCubic(((t - 1.0f - i * 0.09f) / 0.35f).coerceIn(0f, 1f))
                val dot = if (member.host) Ui.GOLD else accent
                val pulse = 0.6f + 0.4f * sin(t * 3f + i)
                ctx.fill(x, y + 3, x + 3, y + 6, Ui.alpha(dot, na * pulse))
                ctx.drawText(tr, member.name, x + 8, y + ((1f - na) * 4f).toInt(), Ui.alpha(Ui.TEXT, na), false)
                x += widths[i] + 10
            }
            if (o.party.size > names.size) Ui.label(ctx, tr, "+${o.party.size - names.size}", x, y + 1, Ui.alpha(Ui.DIM, a))
        }

        // 7. Schritt und Leiste unten
        val barW = min(width - 80, 260)
        val bx = cx - barW / 2
        val by = height - 30
        val bA = a * Ui.easeOutCubic(((t - 0.5f) / 0.4f).coerceIn(0f, 1f))
        Ui.hline(ctx, bx, bx + barW, by, Ui.alpha(Ui.LINE_2, bA))
        val fillW = (barW * o.progress.coerceIn(0f, 1f)).toInt()
        ctx.fill(bx, by - 1, bx + fillW, by + 1, Ui.alpha(accent, bA))
        if (fillW > 12) {
            val sx = bx + ((t * 0.7f % 1f) * (fillW + 20)).toInt() - 20
            ctx.fill(max(bx, sx), by - 1, min(bx + fillW, sx + 20), by + 1, Ui.alpha(0xFFFFFFFF.toInt(), 0.55f * bA))
        }
        RpgGfx.texture(ctx, RpgGfx.GLOW, bx + fillW, by, 26, 14, 0f, Ui.alpha(accent, 0.6f * bA))
        val stw = Ui.labelWidth(tr, o.step)
        Ui.label(ctx, tr, o.step, cx - stw / 2, by + 7, Ui.alpha(Ui.DIM, bA))

        // 8. Ränder ruhig halten, Lichtgipfel als weißgoldene Fläche
        val v = max(10, height / 10)
        ctx.fillGradient(0, 0, width, v, Ui.alpha(0xFF000000.toInt(), 0.7f * a), 0x00000000)
        ctx.fillGradient(0, height - v, width, height, 0x00000000, Ui.alpha(0xFF000000.toInt(), 0.7f * a))
        if (o.flash > 0.01f) ctx.fill(0, 0, width, height, Ui.alpha(0xFFFFF2D6.toInt(), o.flash))
    }

    /** Titel, dessen Buchstaben nacheinander von unten einrasten. */
    fun staggered(ctx: DrawContext, tr: TextRenderer, text: String, cx: Int, y: Int, t: Float, a: Float, color: Int = Ui.TEXT) {
        val total = Ui.displayWidth(tr, text, 2)
        var x = cx - total / 2
        text.forEachIndexed { i, ch ->
            val s = ch.toString()
            val k = Ui.easeOutCubic(((t - i * 0.035f) / 0.35f).coerceIn(0f, 1f))
            if (k > 0f) Ui.display(ctx, tr, s, x, y + ((1f - k) * 7f).toInt(), Ui.alpha(color, a * k), 2)
            x += Ui.displayWidth(tr, s, 2)
        }
    }

    /** Himmel: Lichtfedern sinken und pendeln; Unterwelt: Glut steigt und flackert; sonst steigende Funken. */
    private fun particles(ctx: DrawContext, width: Int, height: Int, t: Float, a: Float, pathId: String, accent: Int) {
        if (a <= 0.01f) return
        val heaven = pathId == "heaven"
        val hell = pathId == "hell"
        for (i in 0 until 46) {
            val h1 = RpgGfx.hash(i * 13 + 5)
            val h2 = RpgGfx.hash(i * 13 + 6)
            val h3 = RpgGfx.hash(i * 13 + 7)
            val speed = 10f + h2 * 26f
            val travel = (t * speed + h3 * (height + 20)) % (height + 20f)
            val x = (h1 * width + sin(t * (0.6f + h2) + i) * (if (heaven) 14f else 5f)).toInt()
            val y = if (heaven) (travel - 10).toInt() else (height + 10 - travel).toInt()
            val flicker = if (hell) 0.4f + 0.6f * (0.5f + 0.5f * sin(t * 9f + i * 2.1f)) else 0.5f + 0.5f * sin(t * 2.5f + i)
            val color = when {
                hell -> Ui.lerp(0xFFFF5A1F.toInt(), 0xFFFFD36B.toInt(), h3)
                heaven -> Ui.lerp(0xFFFFFFFF.toInt(), Ui.brighten(accent, 0.5f), h2)
                else -> Ui.brighten(accent, 0.4f)
            }
            val pa = a * flicker * (0.35f + 0.5f * h2)
            if (heaven && i % 3 == 0) {
                val tilt = if (sin(t * (0.6f + h2) + i) > 0) 1 else -1
                ctx.fill(x, y, x + 1, y + 1, Ui.alpha(color, pa))
                ctx.fill(x + tilt, y + 1, x + tilt + 1, y + 2, Ui.alpha(color, pa * 0.8f))
                ctx.fill(x + 2 * tilt, y + 2, x + 2 * tilt + 1, y + 3, Ui.alpha(color, pa * 0.5f))
            } else {
                val s = if (i % 7 == 0) 2 else 1
                ctx.fill(x, y, x + s, y + s, Ui.alpha(color, pa))
            }
        }
    }
}
