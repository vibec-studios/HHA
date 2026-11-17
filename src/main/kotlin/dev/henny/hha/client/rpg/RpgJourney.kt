package dev.henny.hha.client.rpg

import dev.henny.hha.Hha
import dev.henny.hha.HhaSounds
import dev.henny.hha.api.rpg.HhaRpg
import dev.henny.hha.client.audio.MusicDirector
import dev.henny.hha.client.cine.CinePlayer
import dev.henny.hha.client.cine.stage.StagePlayer
import dev.henny.hha.client.gui.Ui
import dev.henny.hha.client.rpg.game.RpgClientState
import dev.henny.hha.client.rpg.tunnel.TunnelFigures
import dev.henny.hha.client.rpg.tunnel.TunnelSound
import dev.henny.hha.client.rpg.tunnel.TunnelView
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.net.CinePayload
import dev.henny.hha.net.RpgClientPayload
import dev.henny.hha.net.RpgStatePayload
import dev.henny.hha.stage.StageScripts
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.DisconnectedScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.input.AbstractInput
import net.minecraft.client.input.KeyInput
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Die Reise aus der Lobby in die Welt (PLAN 4.3), ein Zustand je Client. Sie lebt hier und nicht im Bildschirm: Nach
 * `DEPART` gibt es keinen [RpgLobbyScreen] mehr, die Szene läuft über dem Übergangsbildschirm, über allen Vanilla-Lade-
 * und Verbindungsbildschirmen ([RpgScreens]) und in der Welt als Überlagerung (`CinePlayer.underlay`).
 *
 * | Zustand | Auslöser | Ende |
 * | --- | --- | --- |
 * | [State.DEPART] | Relay `starting` (bzw. `go` für Nachzügler) | nach [DEPART_MS] |
 * | [State.WARP] | Ende `DEPART`; Host: Lichtgipfel, dann `createAndStart`; Mitspieler: verbinden nach `go` | eigener Weltbeitritt |
 * | [State.HOLD] | eigener Weltbeitritt; `READY` nach geschlossenem Ladebildschirm und erstem Weltbild | Server `ARRIVE`, höchstens [HOLD_TIMEOUT_MS] |
 * | [State.ARRIVE] | Server startet die Ankunftsszene | `exitOpen` in [EXIT_TICKS] Ticks auf 1, dann PLAY (= [State.IDLE]) |
 *
 * Jede Wartestelle hat eine Frist und einen Rückfall (PLAN 5); `CinePlayer.externalLock` fällt in jedem Ausgang
 * ([finish], [fail], [onDisconnected]).
 */
object RpgJourney {

    enum class State { IDLE, DEPART, WARP, HOLD, ARRIVE }

    // ---------------------------------------------------------------------------------------------
    // Zeiten und Tempo (PLAN 4.3)
    // ---------------------------------------------------------------------------------------------

    /** Aufbruch: Oberfläche gleitet weg ([UI_OUT_MS]), Figuren drehen sich in Flugrichtung, Tempo 0,3 → 6. */
    const val DEPART_MS = 1200L
    const val UI_OUT_MS = 400L

    /** Host: Anstieg des Lichtgipfels vor `createAndStart`. */
    const val PEAK_MS = 420L

    /** So lange ohne neues Bild gilt als Standbild des Datapack-Ladens; danach klingt der Gipfel ab. */
    const val PEAK_GAP_MS = 200L

    /** Ohne Standbild klingt der Gipfel spätestens so lange nach dem Weltauftrag ab. */
    const val PEAK_MAX_HOLD_MS = 700L
    const val PEAK_RELEASE_MS = 900L

    /** Wird nichts gezeichnet (Fenster im Hintergrund), startet die Welt trotzdem so lange nach `WARP`. */
    const val PEAK_NO_FRAME_MS = 2500L

    /** Rasen: ab hier beruhigt sich das Tempo auf [SPEED_CRUISE]. */
    const val WARP_EASE_AFTER_MS = 2500L
    const val WARP_EASE_MS = 3000L

    /** Mitspieler: Frist bis `go`. Host: Frist bis zum Weltbeitritt. */
    const val GO_TIMEOUT_MS = 120_000L
    const val WORLD_TIMEOUT_MS = 180_000L

    /** Zeigt Minecraft in `WARP` einen fremden Bildschirm (Fehlermeldung), endet die Reise nach dieser Zeit. */
    const val FOREIGN_SCREEN_MS = 1500L

    /** Halte: höchstens so lange, dann öffnet der Client selbst und spielt `rpg.arrival.late` (PLAN 5). */
    const val HOLD_TIMEOUT_MS = 30_000L

    /** Meldet der Server nach `READY` dauerhaft `PLAY` (Welt schon angekommen), öffnet der Tunnel danach. */
    const val PLAY_GRACE_MS = 2_000L

    /** Kommt nach `READY` gar kein RPG-Zustand, öffnet der Tunnel danach. */
    const val NO_STATE_MS = 6_000L

    /** Austritt: `exitOpen` 0 → 1 in 30 Ticks (1,5 s). */
    const val EXIT_TICKS = 30
    const val EXIT_MS = EXIT_TICKS * 50L

    /** Austritt: Die Figuren schießen ins Licht (Blöcke Vorflug, Dauer). */
    const val LEAVE_FORWARD = 40f
    const val LEAVE_MS = 450L

    /** Austritt: kurzer Lichtstoß (≤ 0,25, Leitlinie 14). */
    const val ARRIVE_FLASH = 0.2f
    const val ARRIVE_FLASH_MS = 500L

    const val SPEED_LOBBY = 0.3f
    const val SPEED_WARP = 6f
    const val SPEED_CRUISE = 3.5f
    const val SPEED_HOLD = 2.5f

    /** Lichtring der Figuren während der Reise (alle an Bord). */
    private const val RING = 0.7f

    // ---------------------------------------------------------------------------------------------
    // Zustand
    // ---------------------------------------------------------------------------------------------

    @Volatile
    var state = State.IDLE
        private set

    /** Beginn des laufenden Zustands (Systemzeit ms). */
    var stateAt = 0L
        private set

    private var host = false

    /** Beginn der ganzen Reise (Text- und 2D-Animation laufen durch, auch über Zustandswechsel). */
    private var journeyAt = 0L
    private var lobby: RpgNet.Lobby? = null
    private var members: List<RpgNet.Member> = emptyList()
    var pathId = "heaven"
        private set
    private var hostName = ""

    private var goPending = false
    private var connected = false
    private var worldRequested = false
    private var peakShown = false
    private var peakRequestedAt = -1L
    private var peakReleaseAt = -1L
    private var lastDrawAt = 0L
    private var lastCtx: DrawContext? = null
    private var foreignSince = -1L

    private var readySent = false
    private var readyAt = 0L
    private var worldFrameSeen = false
    private var localSceneEndAt = -1L

    /** Schlüssel der letzten Fehlermeldung; die Lobby zeigt sie einmal rot an ([takeFailure]). */
    private var failure: String? = null

    /** Läuft die Reise (dann zeichnen die Ladebildschirme nichts, [RpgScreens])? */
    val active: Boolean get() = state != State.IDLE

    private fun now() = System.currentTimeMillis()

    private val underlayHook: (DrawContext, Float) -> Unit = { ctx, _ ->
        if (state == State.HOLD || state == State.ARRIVE) draw(ctx, ctx.scaledWindowWidth, ctx.scaledWindowHeight, -1, -1)
    }

    /** Lobby-Reihenfolge der Plätze: Gastgeber zuerst, sonst wie vom Lobby-Dienst (dieselbe Reihenfolge wie die Marken). */
    fun order(members: List<RpgNet.Member>): List<RpgNet.Member> = members.filter { it.host } + members.filter { !it.host }

    /** Holt die letzte Fehlermeldung (einmal). */
    fun takeFailure(): String? = failure.also { failure = null }

    // ---------------------------------------------------------------------------------------------
    // Eingänge
    // ---------------------------------------------------------------------------------------------

    /** Relay `starting`: alle (auch der Host) brechen auf. */
    fun onStarting() {
        val current = RpgNet.lobby ?: return
        begin(current)
    }

    /** Relay `go`: die Welt des Hosts ist offen. Nachzügler brechen erst jetzt auf. */
    fun onGo() {
        if (RpgNet.isHost()) return
        when (state) {
            State.IDLE -> {
                val current = RpgNet.lobby ?: return
                begin(current)
                goPending = true
            }
            State.DEPART -> goPending = true
            State.WARP -> if (!connected) connect(MinecraftClient.getInstance())
            else -> {}
        }
    }

    private fun begin(current: RpgNet.Lobby) {
        if (state != State.IDLE) return
        val client = MinecraftClient.getInstance()
        host = RpgNet.isHost()
        lobby = current
        members = order(current.members)
        pathId = current.path
        hostName = current.members.firstOrNull { it.host }?.name ?: ""
        goPending = false
        connected = false
        worldRequested = false
        peakShown = false
        peakRequestedAt = -1L
        peakReleaseAt = -1L
        readySent = false
        worldFrameSeen = false
        foreignSince = -1L
        failure = null
        enter(State.DEPART)
        journeyAt = stateAt
        TunnelSound.start()
        TunnelSound.once(HhaSounds.RPG_DEPART)
        MusicDirector.menu("music.tunnel")
        // Die Lobby spielt den Abschied ihrer Oberfläche selbst; sonst übernimmt sofort der Übergangsbildschirm.
        if (client.currentScreen !is RpgLobbyScreen) client.setScreen(RpgTransitionScreen())
    }

    /** Eigener Weltbeitritt (Host oder Mitspieler). Host: Welt für den Tunnel öffnen und `open` melden. */
    fun onJoined(client: MinecraftClient) {
        if (state != State.DEPART && state != State.WARP) return
        if (host) RpgLaunch.openToLan(client)
        enter(State.HOLD)
        CinePlayer.externalLock = true
        CinePlayer.underlay = underlayHook
        // Die Menümusik klingt in der Welt ohnehin nicht mehr; der Wunsch wird hier sauber gelöscht.
        MusicDirector.menu(null)
    }

    /** Verbindung zur Welt beendet (auch: Host verlässt die Welt in der Halte). */
    fun onDisconnected() {
        val during = state
        if (during == State.HOLD || during == State.ARRIVE) clear()
        if (during == State.IDLE || during == State.HOLD || during == State.ARRIVE) {
            // Spiel verlassen: Lobby verlassen und Tunnel schließen (wie bisher)
            if (RpgNet.lobby != null) RpgNet.send("leave")
            RpgTunnels.closeAll()
        }
    }

    fun onLobbyClosed() {
        if (state == State.DEPART || (state == State.WARP && !host && !connected)) fail("closed")
    }

    fun onBackendLost() {
        if (state == State.DEPART || (state == State.WARP && !host)) fail("relay")
    }

    /** Ein Bildschirm wurde geöffnet (`ScreenEvents.AFTER_INIT`). Fehler- und Titelbildschirm beenden die Reise. */
    fun onScreen(screen: Screen) {
        if (state == State.IDLE) return
        when {
            screen is DisconnectedScreen -> fail(if (host) "world" else "connect", keepScreen = true)
            state == State.WARP && (screen is TitleScreen || screen is RpgLobbyScreen) -> fail(if (host) "world" else "connect")
            state == State.DEPART && screen is TitleScreen -> fail("cancelled")
        }
    }

    /** Ein Bild der Welt wurde gezeichnet (`WorldRenderEvents.END_MAIN`). */
    fun onWorldFrame() {
        if (state == State.HOLD && !RpgScreens.isLoadingScreen(MinecraftClient.getInstance().currentScreen)) worldFrameSeen = true
    }

    /** Esc auf dem Übergangs- oder Verbindungsbildschirm: bewusster Abbruch (Rückfall in die Lobby). */
    fun cancel(input: AbstractInput? = null) {
        if (state != State.DEPART && state != State.WARP) return
        val client = MinecraftClient.getInstance()
        val screen = client.currentScreen
        if (screen is ConnectScreen) {
            // Der Abbrechen-Knopf des Verbindungsbildschirms bricht die Verbindung sauber ab (und öffnet die Lobby).
            val cancelButton = Screens.getButtons(screen).filterIsInstance<ButtonWidget>().firstOrNull()
            cancelButton?.onPress(input ?: KeyInput(GLFW.GLFW_KEY_ESCAPE, 0, 0))
        }
        // Gastgeber bricht ab: Die Relay-Lobby steht sonst dauerhaft auf „starting“ und kann nicht neu aufbrechen.
        // `leave` schließt sie; Mitspieler bekommen `closed` und fallen ebenfalls zurück (Prüfung R1-m2).
        if (host && RpgNet.lobby != null) RpgNet.send("leave")
        fail("cancelled")
    }

    // ---------------------------------------------------------------------------------------------
    // Ablauf je Client-Tick
    // ---------------------------------------------------------------------------------------------

    fun tick(client: MinecraftClient) {
        val now = now()
        val since = now - stateAt
        when (state) {
            State.IDLE -> {}
            State.DEPART -> if (since >= DEPART_MS) {
                enter(State.WARP)
                if (client.currentScreen is RpgLobbyScreen) client.setScreen(RpgTransitionScreen())
            }
            State.WARP -> tickWarp(client, now, since)
            State.HOLD -> tickHold(client, now, since)
            State.ARRIVE -> if (since >= EXIT_MS) finish()
        }
        if (state == State.HOLD || state == State.ARRIVE) {
            // Sicherheitsnetz: ein Reset von CinePlayer darf die Überlagerung nicht stehen lassen oder verlieren.
            if (CinePlayer.underlay !== underlayHook) CinePlayer.underlay = underlayHook
            if (state == State.HOLD) CinePlayer.externalLock = true
        }
        if (localSceneEndAt in 1..now) {
            localSceneEndAt = -1L
            if (RpgClientState.phase != RpgStatePayload.PHASE_ARRIVE) CinePlayer.handle(CinePayload(Cine.OP_END, emptyList(), emptyList()))
        }
    }

    private fun tickWarp(client: MinecraftClient, now: Long, since: Long) {
        if (host) {
            if (!worldRequested && ((since >= PEAK_MS && peakShown) || since >= PEAK_NO_FRAME_MS)) createWorld(client)
            if (since > WORLD_TIMEOUT_MS) return fail("timeout")
        } else {
            if (goPending && !connected) connect(client)
            if (!connected && since > GO_TIMEOUT_MS) return fail("timeout")
        }
        // Zeigt Minecraft etwas anderes als unsere Szene (z. B. eine Fehlermeldung), endet die Reise sichtbar.
        val screen = client.currentScreen
        val ours = screen is RpgTransitionScreen || RpgScreens.isLoadingScreen(screen) || (screen == null && client.world != null)
        if (ours || !(worldRequested || connected)) {
            foreignSince = -1L
        } else {
            if (foreignSince < 0) foreignSince = now
            if (now - foreignSince > FOREIGN_SCREEN_MS) fail(if (host) "world" else "connect", keepScreen = true)
        }
    }

    private fun tickHold(client: MinecraftClient, now: Long, since: Long) {
        if (!readySent) {
            val loading = RpgScreens.isLoadingScreen(client.currentScreen)
            if (client.world != null && client.player != null && !loading) {
                if (worldFrameSeen) sendReady(now)
            } else {
                worldFrameSeen = false
            }
        }
        val enabled = RpgClientState.enabled
        val phase = RpgClientState.phase
        when {
            enabled && phase == RpgStatePayload.PHASE_ARRIVE -> toArrive()
            readySent && enabled && phase == RpgStatePayload.PHASE_PLAY && now - readyAt > PLAY_GRACE_MS -> toArrive()
            readySent && !enabled && now - readyAt > NO_STATE_MS -> toArrive()
            since > HOLD_TIMEOUT_MS -> {
                Hha.LOGGER.warn("RPG: Halte dauert über {} s; der Tunnel öffnet sich lokal", HOLD_TIMEOUT_MS / 1000)
                playLocalLate(client)
                toArrive()
            }
        }
    }

    private fun sendReady(now: Long) {
        readySent = true
        readyAt = now
        if (ClientPlayNetworking.canSend(RpgClientPayload.ID)) {
            ClientPlayNetworking.send(RpgClientPayload(RpgClientPayload.READY, 0))
        } else {
            Hha.LOGGER.warn("RPG: Server kennt hha:rpg_client nicht; READY nicht gesendet")
        }
    }

    private fun toArrive() {
        if (state != State.HOLD) return
        enter(State.ARRIVE)
        TunnelSound.once(HhaSounds.RPG_ARRIVE)
        TunnelSound.fadeOut()
    }

    /** Rückfall nach 30 s Halte: Szene `rpg.arrival.late` lokal am Spieler (falls es das Skript gibt). */
    private fun playLocalLate(client: MinecraftClient) {
        val player = client.player ?: return
        val script = StageScripts.get(LATE_STAGE) ?: return
        CinePlayer.handle(CinePayload(Cine.OP_START, emptyList(), emptyList()))
        StagePlayer.play(LATE_STAGE, player.entityPos, player.yaw, listOf(player.id))
        localSceneEndAt = now() + script.length * 50L + 500L
    }

    private const val LATE_STAGE = "rpg.arrival.late"

    private fun createWorld(client: MinecraftClient) {
        val current = lobby ?: return fail("world")
        worldRequested = true
        peakRequestedAt = now()
        val expected = members.map { it.uuid }
        if (!RpgLaunch.createWorld(client, current, expected, RpgLobbyScreen(TitleScreen()))) fail("world")
    }

    private fun connect(client: MinecraftClient) {
        connected = true
        if (!RpgLaunch.connect(client, RpgLobbyScreen(TitleScreen()))) fail("tunnel")
    }

    private fun enter(next: State) {
        state = next
        stateAt = now()
    }

    /** Ankunft fertig: Überlagerung weg, Sperre auf, PLAY. */
    private fun finish() {
        clear()
    }

    /**
     * Rückfall (PLAN 5): Überlagerung sofort weg, Sperre auf, zurück in die Lobby (falls keine Welt läuft und keine
     * Fehlermeldung offen ist), rote Meldung dort. [keepScreen] = der aktuelle Bildschirm (Fehlermeldung) bleibt.
     */
    fun fail(reason: String, keepScreen: Boolean = false) {
        if (state == State.IDLE) return
        Hha.LOGGER.warn("RPG: Reise abgebrochen ({})", reason)
        val wasHost = host
        clear()
        failure = reason
        MusicDirector.menu(null)
        RpgTunnels.closeAll()
        // Nie eine Wahl liegen lassen: sonst würde die nächste normale Einzelspielerwelt zur RPG-Welt.
        if (wasHost) dev.henny.hha.rpg.RpgWorld.pending = null
        val client = MinecraftClient.getInstance()
        if (!keepScreen && client.world == null && client.currentScreen !is RpgLobbyScreen && client.currentScreen !is DisconnectedScreen) {
            client.setScreen(RpgLobbyScreen(TitleScreen()))
        }
    }

    /** Alles zurück auf „keine Reise“ (ohne Bildschirmwechsel). */
    private fun clear() {
        state = State.IDLE
        stateAt = now()
        CinePlayer.externalLock = false
        if (CinePlayer.underlay === underlayHook) CinePlayer.underlay = null
        TunnelSound.stop()
        lastCtx = null
        goPending = false
    }

    // ---------------------------------------------------------------------------------------------
    // Zeichnen (Übergangsbildschirm, ersetzte Ladebildschirme, Überlagerung in der Welt)
    // ---------------------------------------------------------------------------------------------

    /** Gesamte Reise-Szene über [width] × [height]; im selben Bild nur einmal (HUD-Überlagerung und Ladebildschirm). */
    fun draw(ctx: DrawContext, width: Int, height: Int, mouseX: Int, mouseY: Int) {
        if (state == State.IDLE || ctx === lastCtx) return
        lastCtx = ctx
        val now = now()
        val gap = now - lastDrawAt
        lastDrawAt = now
        val t = now - stateAt
        var speed = SPEED_HOLD
        var fly = 1f
        var facing = 1f
        var forward = 0f
        var exit = 0f
        var figures = true
        var flash = peakFlash(now, gap)
        when (state) {
            State.DEPART -> {
                val k = (t / DEPART_MS.toFloat()).coerceIn(0f, 1f)
                speed = SPEED_LOBBY + (SPEED_WARP - SPEED_LOBBY) * k * k * k
                facing = smooth(k / 0.6f)
                fly = smooth((k - 0.15f) / 0.55f)
            }
            State.WARP -> speed = SPEED_WARP - (SPEED_WARP - SPEED_CRUISE) * smooth((t - WARP_EASE_AFTER_MS) / WARP_EASE_MS.toFloat())
            State.HOLD -> speed = SPEED_CRUISE + (SPEED_HOLD - SPEED_CRUISE) * smooth(t / 1500f)
            State.ARRIVE -> {
                speed = SPEED_HOLD + 3.5f * smooth(t / 600f)
                val leave = (t / LEAVE_MS.toFloat()).coerceIn(0f, 1f)
                forward = LEAVE_FORWARD * leave * leave
                figures = t < LEAVE_MS
                exit = RpgGfx.easeInOut(t / EXIT_MS.toFloat())
                flash = max(flash, ARRIVE_FLASH * sin(PI.toFloat() * (t / ARRIVE_FLASH_MS.toFloat()).coerceIn(0f, 1f)))
            }
            State.IDLE -> return
        }
        if (flash >= 0.999f) peakShown = true
        val look = mouseX >= 0 && state != State.HOLD && state != State.ARRIVE
        val color = TunnelView.color(pathId)
        val specs = if (!figures) emptyList() else members.take(TunnelFigures.SLOTS.size).mapIndexed { i, m ->
            TunnelFigures.Spec(m.uuid, m.name, i, pathId, RING, color, facing = facing, fly = fly, forward = forward)
        }
        if (TunnelView.threeD()) {
            TunnelView.draw(
                ctx, width, height,
                TunnelView.Frame(
                    pathId, speed, specs, exitOpen = exit, flash = flash,
                    lookX = if (look) (mouseX / width.toFloat()) * 2f - 1f else 0f,
                    lookY = if (look) (mouseY / height.toFloat()) * 2f - 1f else 0f,
                    opaqueBase = state != State.ARRIVE,
                ),
            )
            drawText(ctx, width, height, now - journeyAt, flash, exit)
        } else {
            // Rückfall: die 2D-Startanimation des Bestands (PLAN 5)
            RpgLaunch.drawOverlay(
                ctx, width, height,
                RpgLaunch.Overlay(
                    (now - journeyAt) / 1000f, 0xFF000000.toInt() or color, pathId, title(), step(),
                    members, progress(t), 1f + speed / 3f, exit, flash,
                ),
            )
        }
    }

    private fun progress(t: Long): Float = when (state) {
        State.DEPART -> 0.12f
        State.WARP -> 0.2f + 0.6f * (1f - 1f / (1f + t / 3000f))
        State.HOLD -> 0.85f + 0.1f * (1f - 1f / (1f + t / 4000f))
        else -> 1f
    }

    /** Lichtgipfel des Hosts: steigt vor `createAndStart`, hält bis nach dem Standbild, klingt dann ab. */
    private fun peakFlash(now: Long, gap: Long): Float {
        if (!host) return 0f
        if (!worldRequested) {
            if (state != State.WARP) return 0f
            val k = ((now - stateAt) / PEAK_MS.toFloat()).coerceIn(0f, 1f)
            return k.pow(2)
        }
        if (peakReleaseAt < 0) {
            if (gap > PEAK_GAP_MS || now - peakRequestedAt > PEAK_MAX_HOLD_MS) peakReleaseAt = now
            return 1f
        }
        return 1f - Ui.easeOutCubic((now - peakReleaseAt) / PEAK_RELEASE_MS.toFloat())
    }

    private fun title(): String = if (host) Text.translatable("hha.rpg.journey.title.host").string
    else Text.translatable("hha.rpg.journey.title.join", hostName).string

    private fun step(): String = when (state) {
        State.DEPART -> Text.translatable("hha.rpg.journey.step.depart").string
        State.WARP -> when {
            host -> Text.translatable("hha.rpg.journey.step.world").string
            connected -> Text.translatable("hha.rpg.journey.step.connect", hostName).string
            else -> Text.translatable("hha.rpg.journey.step.wait_host", hostName).string
        }
        State.HOLD -> {
            val total = max(1, members.size)
            val missing = if (RpgClientState.enabled) RpgClientState.waiting.size else total - 1
            if (RpgClientState.enabled && missing == 0) Text.translatable("hha.rpg.journey.step.arrive").string
            else Text.translatable("hha.rpg.journey.wait", (total - missing).coerceIn(0, total), total).string
        }
        else -> Text.translatable("hha.rpg.journey.step.arrive").string
    }

    /** Reise-Text unten: Weg (Etikett), Titel (Buchstabe für Buchstabe), Schritt; in der Halte die Wartezeile. */
    private fun drawText(ctx: DrawContext, width: Int, height: Int, since: Long, flash: Float, exit: Float) {
        if (state == State.DEPART && MinecraftClient.getInstance().currentScreen is RpgLobbyScreen) return
        val tr = MinecraftClient.getInstance().textRenderer
        val a = (1f - flash) * (1f - (exit / 0.4f).coerceIn(0f, 1f)) * Ui.easeOutCubic((since - UI_OUT_MS) / 500f)
        if (a <= 0.01f) return
        val accent = 0xFF000000.toInt() or TunnelView.color(pathId)
        val cx = width / 2
        var y = (height * 0.74f).toInt().coerceAtMost(height - 52)
        // Lesbarkeit: weicher dunkler Verlauf unter dem Text
        ctx.fillGradient(0, y - 18, width, height, 0x00000000, Ui.alpha(0xB0000000.toInt(), a))
        val kicker = HhaRpg.path(pathId)?.let { Text.translatable(it.titleKey).string } ?: "HHA RPG"
        val kw = Ui.labelWidth(tr, kicker)
        Ui.label(ctx, tr, kicker, cx - kw / 2, y, Ui.alpha(accent, a))
        Ui.hline(ctx, cx - kw / 2 - 26, cx - kw / 2 - 8, y + 3, Ui.alpha(accent, a * 0.6f))
        Ui.hline(ctx, cx + kw / 2 + 8, cx + kw / 2 + 26, y + 3, Ui.alpha(accent, a * 0.6f))
        y += 12
        RpgLaunch.staggered(ctx, tr, title(), cx, y, (since - UI_OUT_MS) / 1000f, a)
        y += 26
        val stepText = step()
        val sw = Ui.labelWidth(tr, stepText)
        val pulse = 0.55f + 0.45f * sin(since / 1000f * 3.2f)
        ctx.fill(cx - sw / 2 - 9, y + 2, cx - sw / 2 - 6, y + 5, Ui.alpha(accent, a * pulse))
        Ui.label(ctx, tr, stepText, cx - sw / 2, y, Ui.alpha(if (state == State.HOLD) Ui.TEXT else Ui.MUTED, a))
        y += 12
        if (state == State.HOLD && RpgClientState.waiting.isNotEmpty()) {
            val names = tr.trimToWidth(RpgClientState.waiting.joinToString(" · "), width - 40)
            val nw = tr.getWidth(names)
            ctx.drawText(tr, names, cx - nw / 2, y, Ui.alpha(Ui.DIM, a), false)
        }
    }

    private fun smooth(k: Float): Float {
        val x = k.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}
