package dev.henny.hha.client.rpg

import dev.henny.hha.Hha
import dev.henny.hha.api.rpg.HhaRpg
import dev.henny.hha.client.gui.Ui
import dev.henny.hha.rpg.RpgWorld
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen
import net.minecraft.client.network.ServerAddress
import net.minecraft.client.network.ServerInfo
import net.minecraft.resource.DataConfiguration
import net.minecraft.text.Text
import net.minecraft.util.NetworkUtils
import net.minecraft.world.Difficulty
import net.minecraft.world.GameMode
import net.minecraft.world.rule.GameRules
import net.minecraft.world.gen.GeneratorOptions
import net.minecraft.world.gen.WorldPresets
import net.minecraft.world.level.LevelInfo
import net.minecraft.resource.featuretoggle.FeatureFlags
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Ablauf des Starts: Der Host erzeugt nach dem Startsignal seine Welt, öffnet sie für LAN (nur lokal, der Tunnel holt die
 * Mitspieler ab) und meldet „open“; die Mitspieler verbinden sich dann über ihren lokalen Tunnel-Port. Über allen
 * Lade- und Verbindungsbildschirmen liegt dieselbe Animation ([drawOverlay]), danach blendet sie im Spiel weich aus.
 * So sieht keiner einen Ladebalken.
 */
object RpgLaunch {

    enum class Phase { IDLE, PREPARE, LOADING, FADE }

    @Volatile var phase = Phase.IDLE
        private set
    private var host = false
    private var startedAt = 0L
    private var fadeAt = 0L
    private var accent = Ui.GOLD
    private var hostName = ""
    private var pathId = ""
    private var party: List<RpgNet.Member> = emptyList()
    private var worldCreated = false

    /** Ab hier läuft die Startanimation (nach dem Abschied der Lobby, sonst sofort). */
    private var revealAt = 0L
    private var lastFrame = 0L
    private var barShown = 0f

    val active: Boolean get() = phase != Phase.IDLE

    // ---------------------------------------------------------------------------------------------
    // Ereignisse
    // ---------------------------------------------------------------------------------------------

    /** Lobby-Dienst: Der Host hat gestartet. Alle (auch der Host) beginnen die Animation. */
    fun onStarting() {
        val lobby = RpgNet.lobby ?: return
        host = RpgNet.isHost()
        hostName = lobby.members.firstOrNull { it.host }?.name ?: ""
        pathId = lobby.path
        party = lobby.members
        accent = HhaRpg.path(lobby.path)?.color ?: Ui.GOLD
        phase = Phase.PREPARE
        startedAt = System.currentTimeMillis()
        barShown = 0f
        worldCreated = false
        val client = MinecraftClient.getInstance()
        val lobbyScreen = client.currentScreen as? RpgLobbyScreen
        if (lobbyScreen != null) {
            // Die Lobby verabschiedet sich selbst (Licht, Irisblende) und übergibt dann an die Animation
            lobbyScreen.depart()
            revealAt = startedAt + RpgGfx.DEPART_MS
        } else {
            revealAt = startedAt
            client.setScreen(RpgTransitionScreen())
        }
    }

    /** Lobby-Dienst: Die Welt des Hosts ist offen; als Mitspieler jetzt verbinden. */
    fun onGo() {
        if (RpgNet.isHost()) return
        val client = MinecraftClient.getInstance()
        if (phase == Phase.IDLE) {
            // Später beigetreten, während die Welt schon läuft
            val lobby = RpgNet.lobby
            accent = lobby?.let { HhaRpg.path(it.path)?.color } ?: Ui.GOLD
            hostName = lobby?.members?.firstOrNull { it.host }?.name ?: ""
            pathId = lobby?.path ?: ""
            party = lobby?.members ?: emptyList()
            startedAt = System.currentTimeMillis()
            revealAt = startedAt
            barShown = 0f
            host = false
        }
        phase = Phase.LOADING
        val port = try {
            RpgTunnels.startJoinListener()
        } catch (e: Exception) {
            Hha.LOGGER.error("RPG: lokaler Tunnel-Port nicht verfügbar", e)
            fail(); return
        }
        val info = ServerInfo(Text.translatable("hha.rpg.server_name").string, "127.0.0.1:$port", ServerInfo.ServerType.OTHER)
        ConnectScreen.connect(TitleScreen(), client, ServerAddress("127.0.0.1", port), info, false, null)
    }

    /** Im Spiel angekommen (Host oder Mitspieler). Host: Welt für den Tunnel öffnen und „open“ melden. */
    fun onJoined(client: MinecraftClient) {
        if (phase == Phase.IDLE) return
        if (host) {
            val server = client.server
            if (server != null && !server.isRemote) {
                val port = NetworkUtils.findLocalPort()
                if (server.openToLan(GameMode.SURVIVAL, false, port)) {
                    RpgTunnels.lanPort = port
                    RpgNet.send("open")
                } else {
                    Hha.LOGGER.error("RPG: Welt ließ sich nicht für LAN öffnen")
                }
            }
        }
        phase = Phase.FADE
        fadeAt = System.currentTimeMillis()
    }

    fun onDisconnected() {
        if (phase == Phase.FADE || phase == Phase.IDLE) {
            // Spiel verlassen: Lobby verlassen und Tunnel schließen
            if (RpgNet.lobby != null) RpgNet.send("leave")
            RpgTunnels.closeAll()
            phase = Phase.IDLE
        }
    }

    fun onLobbyClosed() {
        if (phase == Phase.PREPARE) fail()
    }

    fun onBackendLost() {
        if (phase == Phase.PREPARE || (phase == Phase.LOADING && !host)) fail()
    }

    /** Start abgebrochen, aber der aktuelle Bildschirm (z. B. die Fehlermeldung) soll sichtbar bleiben. */
    fun abortVisible() {
        phase = Phase.IDLE
        RpgTunnels.closeAll()
    }

    private fun fail() {
        phase = Phase.IDLE
        RpgTunnels.closeAll()
        val client = MinecraftClient.getInstance()
        if (client.world == null) client.setScreen(RpgLobbyScreen(TitleScreen()))
    }

    /** Jeder Client-Tick: Host erzeugt nach dem kurzen Auftakt die Welt. */
    fun tick(client: MinecraftClient) {
        when (phase) {
            Phase.PREPARE -> {
                val since = System.currentTimeMillis() - startedAt
                // Falls die Lobby nicht gezeichnet wurde (Fenster im Hintergrund): Übergabe trotzdem pünktlich
                if (client.currentScreen is RpgLobbyScreen && since >= RpgGfx.DEPART_MS + 120) client.setScreen(RpgTransitionScreen())
                if (host && !worldCreated && since > PREPARE_MS) createWorld(client)
            }
            Phase.FADE -> if (System.currentTimeMillis() - fadeAt > FADE_MS) phase = Phase.IDLE
            else -> {}
        }
    }

    private fun createWorld(client: MinecraftClient) {
        val lobby = RpgNet.lobby ?: return fail()
        worldCreated = true
        phase = Phase.LOADING
        RpgWorld.pending = RpgWorld.Setup(lobby.path, lobby.addons, lobby.code)
        val name = "HHA RPG " + SimpleDateFormat("yyyy-MM-dd HH-mm").format(Date())
        val info = LevelInfo(
            name, GameMode.SURVIVAL, false, Difficulty.NORMAL, false,
            GameRules(FeatureFlags.DEFAULT_ENABLED_FEATURES), DataConfiguration.SAFE_MODE,
        )
        try {
            client.createIntegratedServerLoader().createAndStart(name, info, GeneratorOptions.createRandom(), WorldPresets::createDemoOptions, TitleScreen())
        } catch (e: Exception) {
            Hha.LOGGER.error("RPG: Welt konnte nicht erzeugt werden", e)
            RpgWorld.pending = null
            fail()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Animation
    // ---------------------------------------------------------------------------------------------

    private const val PREPARE_MS = 900L
    private const val FADE_MS = 1500L
    private const val REVEAL_MS = 650L

    /**
     * Deckende Startanimation über Lade- und Verbindungsbildschirmen:
     * Die Iris öffnet sich (dort, wo die Lobby sie geschlossen hat), dahinter ein Portal aus zwei Ringen mit Strahlen,
     * Lichtschlieren, die wie bei einer Reise nach außen fliegen, und Partikeln des Wegs (Himmel: sinkende Lichtfedern,
     * Unterwelt: steigende Glut). Darunter Titel (Buchstabe für Buchstabe), die Gruppe, der aktuelle Schritt und eine
     * Leiste. Im Spiel öffnet sich die Iris ganz und gibt die Welt frei.
     */
    fun drawOverlay(ctx: DrawContext, width: Int, height: Int) {
        if (phase == Phase.IDLE) return
        val now = System.currentTimeMillis()
        if (now < revealAt) {
            ctx.fill(0, 0, width, height, 0xFF000000.toInt())
            return
        }
        val nanos = System.nanoTime()
        val dt = if (lastFrame == 0L) 0.016f else ((nanos - lastFrame) / 1e9f).coerceIn(0f, 0.1f)
        lastFrame = nanos
        val t = (now - revealAt) / 1000f
        val tr = MinecraftClient.getInstance().textRenderer
        val fadeT = if (phase == Phase.FADE) ((now - fadeAt) / FADE_MS.toFloat()).coerceIn(0f, 1f) else 0f
        if (fadeT >= 1f) return
        // Die Szene verblasst in der ersten Hälfte des Ausblendens, die Iris öffnet sich über die ganze Zeit
        val a = 1f - Ui.easeOutCubic((fadeT / 0.5f).coerceIn(0f, 1f))
        val diag = kotlin.math.hypot(width.toDouble(), height.toDouble()).toFloat()
        val cx = width / 2
        val portalSize = (min(width, height) * 0.5f).toInt().coerceAtLeast(60)
        val cy = (height * 0.40f).toInt().coerceAtLeast(portalSize / 2 + 10)

        // Im Spiel: Die Welt erscheint zuerst im Portal, dann weitet sich das Portal über den ganzen Bildschirm
        val step = RpgGfx.easeInOut(fadeT)
        if (phase != Phase.FADE) {
            ctx.fill(0, 0, width, height, 0xFF000000.toInt())
        } else {
            val r0 = portalSize * 0.3f / 0.63f
            val r1 = diag * 0.5f / 0.6f
            RpgGfx.iris(ctx, width, height, cx, cy, (r0 + (r1 - r0) * step).toInt())
        }

        val intro = Ui.easeOutCubic(t / 0.9f)
        val travel = 1f + step * 5f

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
        particles(ctx, width, height, t, a * intro)

        // 4. Portal: zwei Ringe, die gegeneinander drehen, innen ein Kern aus Licht mit dem Logo
        val ringIn = RpgGfx.easeOutBack(((t - 0.1f) / 0.7f).coerceIn(0f, 1f))
        // Beim Ausblenden reitet der Ring auf dem Rand der Iris nach außen
        val ring = (portalSize * ringIn * (1f + step * diag * 1.5f / portalSize)).toInt()
        val ra = 1f - fadeT
        val breathe = 0.5f + 0.5f * sin(t * 2.2f)
        RpgGfx.texture(ctx, RpgGfx.GLOW, cx, cy, (ring * 1.25f).toInt(), (ring * 1.25f).toInt(), 0f, Ui.alpha(accent, (0.35f + 0.15f * breathe) * a))
        RpgGfx.texture(ctx, RpgGfx.RING, cx, cy, ring, ring, t * 0.045f, Ui.alpha(accent, 0.9f * ra))
        RpgGfx.texture(ctx, RpgGfx.RING, cx, cy, (ring * 0.74f).toInt(), (ring * 0.74f).toInt(), -t * 0.07f + 0.05f, Ui.alpha(Ui.brighten(accent, 0.45f), 0.55f * ra))
        RpgGfx.texture(ctx, RpgGfx.GLOW, cx, cy, (ring * 0.55f).toInt(), (ring * 0.55f).toInt(), 0f, Ui.alpha(0xFFFFFFFF.toInt(), (0.30f + 0.2f * breathe) * a * ringIn.coerceIn(0f, 1f)))
        // Logo, dreifach vergrößert, sanft schwebend
        val logoA = a * Ui.easeOutCubic(((t - 0.25f) / 0.5f).coerceIn(0f, 1f))
        if (logoA > 0.01f) {
            val s = (portalSize / 60).coerceIn(2, 4)
            m.pushMatrix()
            m.translate((cx - 6 * s).toFloat(), cy - 5.5f * s + sin(t * 1.6f) * 1.5f)
            m.scale(s.toFloat(), s.toFloat())
            Ui.logo(ctx, 0, 0, logoA)
            m.popMatrix()
        }

        // 5. Text: Weg (klein), Titel Buchstabe für Buchstabe, Ziel des Wegs
        var y = cy + portalSize / 2 + 12
        val kicker = HhaRpg.path(pathId)?.let { Text.translatable(it.titleKey).string } ?: "HHA RPG"
        val kA = a * Ui.easeOutCubic(((t - 0.2f) / 0.4f).coerceIn(0f, 1f))
        val kw = Ui.labelWidth(tr, kicker)
        Ui.label(ctx, tr, kicker, cx - kw / 2, y, Ui.alpha(accent, kA))
        Ui.hline(ctx, cx - kw / 2 - 26, cx - kw / 2 - 8, y + 3, Ui.alpha(accent, kA * 0.6f))
        Ui.hline(ctx, cx + kw / 2 + 8, cx + kw / 2 + 26, y + 3, Ui.alpha(accent, kA * 0.6f))
        y += 12
        val title = Text.translatable(if (host) "hha.rpg.transition.host" else "hha.rpg.transition.join", hostName).string
        staggered(ctx, tr, title, cx, y, t - 0.35f, a)
        y += 24
        val subKey = "hha.rpg.start.$pathId.sub"
        val sub = Text.translatableWithFallback(subKey, Text.translatable("hha.rpg.transition.sub").string).string
        val sA = a * Ui.easeOutCubic(((t - 0.8f) / 0.5f).coerceIn(0f, 1f))
        val sw = tr.getWidth(sub)
        ctx.drawText(tr, sub, cx - sw / 2, y, Ui.alpha(Ui.MUTED, sA), false)
        y += 18

        // 6. Die Gruppe: Namen nacheinander, Host in Gold
        if (party.isNotEmpty() && y + 10 < height - 34) {
            val names = party.take(8)
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
            if (party.size > names.size) {
                Ui.label(ctx, tr, "+${party.size - names.size}", x, y + 1, Ui.alpha(Ui.DIM, a))
            }
        }

        // 7. Schritt und Leiste unten (wie der Seitenwechsel der Website: füllt sich nie ganz, bis die Welt da ist)
        val barW = min(width - 80, 260)
        val bx = cx - barW / 2
        val by = height - 30
        val target = when (phase) {
            Phase.PREPARE -> 0.12f + 0.08f * (1f - 1f / (1f + t))
            Phase.LOADING -> 0.2f + 0.72f * (1f - 1f / (1f + t * 0.35f))
            else -> 1f
        }
        barShown += (target - barShown) * min(1f, dt * 5f)
        val bA = a * Ui.easeOutCubic(((t - 0.5f) / 0.4f).coerceIn(0f, 1f))
        Ui.hline(ctx, bx, bx + barW, by, Ui.alpha(Ui.LINE_2, bA))
        val fillW = (barW * barShown).toInt()
        ctx.fill(bx, by - 1, bx + fillW, by + 1, Ui.alpha(accent, bA))
        if (fillW > 12) {
            val sx = bx + ((t * 0.7f % 1f) * (fillW + 20)).toInt() - 20
            ctx.fill(max(bx, sx), by - 1, min(bx + fillW, sx + 20), by + 1, Ui.alpha(0xFFFFFFFF.toInt(), 0.55f * bA))
        }
        RpgGfx.texture(ctx, RpgGfx.GLOW, bx + fillW, by, 26, 14, 0f, Ui.alpha(accent, 0.6f * bA))
        val stepText = Text.translatable(
            when (phase) {
                Phase.PREPARE -> "hha.rpg.transition.step.prepare"
                Phase.LOADING -> if (host) "hha.rpg.transition.step.world" else "hha.rpg.transition.step.connect"
                else -> "hha.rpg.transition.step.arrive"
            },
            hostName,
        ).string
        val stw = Ui.labelWidth(tr, stepText)
        Ui.label(ctx, tr, stepText, cx - stw / 2, by + 7, Ui.alpha(Ui.DIM, bA))

        // 8. Ränder ruhig halten
        val v = max(10, height / 10)
        ctx.fillGradient(0, 0, width, v, Ui.alpha(0xFF000000.toInt(), 0.7f * a), 0x00000000)
        ctx.fillGradient(0, height - v, width, height, 0x00000000, Ui.alpha(0xFF000000.toInt(), 0.7f * a))

        // 9. Irisblende am Anfang: öffnet sich aus der Mitte, wo die Lobby sie geschlossen hat
        val open = Ui.easeOutCubic((now - revealAt) / REVEAL_MS.toFloat())
        if (phase != Phase.FADE && open < 1f) {
            ctx.createNewRootLayer() // sonst läge der Text derselben Ebene über der Blende
            RpgGfx.iris(ctx, width, height, cx, height / 2, (diag * 0.5f / 0.6f * open).toInt())
        }
    }

    /** Titel, dessen Buchstaben nacheinander von unten einrasten. */
    private fun staggered(ctx: DrawContext, tr: net.minecraft.client.font.TextRenderer, text: String, cx: Int, y: Int, t: Float, a: Float) {
        val total = Ui.displayWidth(tr, text, 2)
        var x = cx - total / 2
        text.forEachIndexed { i, ch ->
            val s = ch.toString()
            val k = Ui.easeOutCubic(((t - i * 0.035f) / 0.35f).coerceIn(0f, 1f))
            if (k > 0f) Ui.display(ctx, tr, s, x, y + ((1f - k) * 7f).toInt(), Ui.alpha(Ui.TEXT, a * k), 2)
            x += Ui.displayWidth(tr, s, 2)
        }
    }

    /** Himmel: Lichtfedern sinken und pendeln; Unterwelt: Glut steigt und flackert; sonst steigende Funken. */
    private fun particles(ctx: DrawContext, width: Int, height: Int, t: Float, a: Float) {
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
                // kleine Feder: drei Pixel schräg, kippt beim Pendeln
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
