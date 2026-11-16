package dev.henny.hha.client.rpg

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.henny.hha.Hha
import dev.henny.hha.api.rpg.HhaRpg
import dev.henny.hha.config.HhaPaths
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicLong

/**
 * Verbindung zum RPG-Lobby-Dienst (`backend/rpg-lobby`): eine WebSocket-Verbindung für Lobby-Nachrichten (JSON) und
 * die Tunnel-Daten (Binär: 4 Byte Strom-ID + Nutzdaten). Sendungen laufen streng nacheinander (Java-WebSocket
 * verlangt das); die Lobby-Ansicht lebt in [lobby], Ereignisse für den Start in [RpgLaunch].
 */
object RpgNet {

    enum class Status { OFFLINE, CONNECTING, ONLINE, FAILED }

    class Member(val uuid: String, val name: String, val host: Boolean, val ready: Boolean, val addons: List<String>)

    class Lobby(
        val code: String, val host: String, val path: String, val addons: List<String>, val state: String,
        val recommended: Int, val max: Int, val members: List<Member>,
    )

    /** Standard-Adresse; überschreibbar in `config/hha/rpg.json` (`{"backend": "wss://…/ws"}`) oder `-Dhha.rpgBackend=`. */
    private const val DEFAULT_BACKEND = "wss://hha-rpg.henny.dev/ws"

    @Volatile var status = Status.OFFLINE
        private set
    @Volatile var lobby: Lobby? = null
        private set
    @Volatile var lastError: String? = null

    private var socket: WebSocket? = null
    private val lock = Any()
    private var chain: CompletableFuture<*> = CompletableFuture.completedFuture(null)
    private val pendingBytes = AtomicLong()
    private val textPart = StringBuilder()
    private var binPart: ByteArray? = null

    fun backendUrl(): String {
        System.getProperty("hha.rpgBackend")?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching {
            val file = HhaPaths.file("rpg.json")
            if (!Files.exists(file)) return@runCatching null
            JsonParser.parseString(Files.readString(file)).asJsonObject.get("backend")?.asString
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_BACKEND
    }

    /** Verbindet (falls noch nicht) und stellt sich vor (Name, UUID, Mod-Version, Addons mit RPG-Fähigkeit). */
    fun connect() {
        synchronized(lock) {
            if (socket != null || status == Status.CONNECTING) return
            status = Status.CONNECTING
            lastError = null
        }
        val url = backendUrl()
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
            .newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8))
            .buildAsync(URI.create(url), Listener)
            .whenComplete { ws, err ->
                if (err != null || ws == null) {
                    Hha.LOGGER.warn("RPG-Lobby nicht erreichbar ({}): {}", url, err?.message)
                    synchronized(lock) { socket = null; status = Status.FAILED; lastError = "offline" }
                    return@whenComplete
                }
                synchronized(lock) {
                    socket = ws
                    chain = CompletableFuture.completedFuture(null)
                    status = Status.ONLINE
                }
                sendJson(hello())
            }
    }

    private fun hello(): JsonObject {
        val client = MinecraftClient.getInstance()
        val session = client.session
        val o = JsonObject()
        o.addProperty("t", "hello")
        o.addProperty("name", session.username)
        o.addProperty("uuid", session.uuidOrNull?.toString() ?: java.util.UUID.nameUUIDFromBytes(session.username.toByteArray()).toString())
        o.addProperty("version", FabricLoader.getInstance().getModContainer(Hha.MOD_ID).map { it.metadata.version.friendlyString }.orElse("?"))
        val addons = JsonArray()
        for (addon in installedAddons()) {
            val a = JsonObject()
            a.addProperty("id", addon.id)
            a.addProperty("name", addon.name)
            a.addProperty("rpg", addon.rpg)
            addons.add(a)
        }
        o.add("addons", addons)
        return o
    }

    class AddonInfo(val id: String, val name: String, val rpg: Boolean)

    /** Installierte HHA-Addons (Einstiegspunkt `hha`), ohne HHA selbst. */
    fun installedAddons(): List<AddonInfo> = FabricLoader.getInstance()
        .getEntrypointContainers("hha", dev.henny.hha.api.HhaAddon::class.java)
        .map { it.provider.metadata }
        .filter { it.id != Hha.MOD_ID }
        .distinctBy { it.id }
        .map { AddonInfo(it.id, it.name, HhaRpg.supports(it.id)) }

    fun disconnect() {
        val ws = synchronized(lock) { socket.also { socket = null } }
        runCatching { ws?.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }
        status = Status.OFFLINE
        lobby = null
        RpgTunnels.closeAll()
    }

    // ---------------------------------------------------------------------------------------------
    // Senden
    // ---------------------------------------------------------------------------------------------

    fun send(type: String, build: JsonObject.() -> Unit = {}) {
        val o = JsonObject()
        o.addProperty("t", type)
        o.build()
        sendJson(o)
    }

    private fun sendJson(o: JsonObject) {
        val text = o.toString()
        synchronized(lock) {
            val ws = socket ?: return
            chain = chain.handle { _, _ -> null }.thenCompose { ws.sendText(text, true) }
        }
    }

    /** Tunnel-Daten an den Dienst; blockiert kurz, wenn schon sehr viel unterwegs ist (Gegendruck). */
    fun sendBinary(sid: Int, data: ByteArray, len: Int) {
        val buf = ByteBuffer.allocate(4 + len)
        buf.putInt(sid)
        buf.put(data, 0, len)
        buf.flip()
        var waited = 0
        while (pendingBytes.get() > MAX_PENDING && waited < 2000) {
            Thread.sleep(2)
            waited += 2
        }
        pendingBytes.addAndGet(len.toLong())
        synchronized(lock) {
            val ws = socket ?: return
            chain = chain.handle { _, _ -> null }.thenCompose { ws.sendBinary(buf, true) }
                .whenComplete { _, _ -> pendingBytes.addAndGet(-len.toLong()) }
        }
    }

    private const val MAX_PENDING = 8L * 1024 * 1024

    // ---------------------------------------------------------------------------------------------
    // Empfangen
    // ---------------------------------------------------------------------------------------------

    private object Listener : WebSocket.Listener {
        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            textPart.append(data)
            if (last) {
                val text = textPart.toString()
                textPart.setLength(0)
                runCatching { handle(JsonParser.parseString(text).asJsonObject) }
                    .onFailure { Hha.LOGGER.warn("RPG: kaputte Nachricht", it) }
            }
            webSocket.request(1)
            return null
        }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            val chunk = ByteArray(data.remaining())
            data.get(chunk)
            val all = binPart?.let { it + chunk } ?: chunk
            if (last) {
                binPart = null
                if (all.size >= 4) {
                    val sid = ((all[0].toInt() and 0xFF) shl 24) or ((all[1].toInt() and 0xFF) shl 16) or
                        ((all[2].toInt() and 0xFF) shl 8) or (all[3].toInt() and 0xFF)
                    RpgTunnels.onData(sid, all, 4, all.size - 4)
                }
            } else {
                binPart = all
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            closed("closed")
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            Hha.LOGGER.warn("RPG-Lobby: Verbindung verloren: {}", error.message)
            closed("offline")
        }
    }

    private fun closed(reason: String) {
        synchronized(lock) { socket = null }
        status = Status.FAILED
        lastError = reason
        lobby = null
        RpgTunnels.closeAll()
        MinecraftClient.getInstance().execute { RpgLaunch.onBackendLost() }
    }

    private fun handle(m: JsonObject) {
        val type = m.get("t")?.asString ?: return
        when (type) {
            "lobby" -> lobby = parseLobby(m)
            "error" -> lastError = m.get("msg")?.asString
            "closed" -> {
                lobby = null
                lastError = "closed_" + (m.get("reason")?.asString ?: "?")
                MinecraftClient.getInstance().execute { RpgLaunch.onLobbyClosed() }
            }
            "kicked" -> {
                lobby = null
                lastError = "kicked"
            }
            "starting" -> MinecraftClient.getInstance().execute { RpgLaunch.onStarting() }
            "go" -> MinecraftClient.getInstance().execute { RpgLaunch.onGo() }
            "tcp_open" -> RpgTunnels.hostOpen(m.get("sid").asInt)
            "tcp_ok" -> RpgTunnels.joinAccepted(m.get("sid").asInt)
            "tcp_fail", "tcp_close" -> RpgTunnels.close(m.get("sid").asInt, notify = false)
        }
    }

    private fun parseLobby(m: JsonObject): Lobby {
        val members = m.getAsJsonArray("members").map { e ->
            val o = e.asJsonObject
            Member(
                o.get("uuid").asString, o.get("name").asString, o.get("host").asBoolean, o.get("ready").asBoolean,
                o.getAsJsonArray("addons")?.map { it.asString } ?: emptyList(),
            )
        }
        return Lobby(
            m.get("code").asString, m.get("host").asString, m.get("path").asString,
            m.getAsJsonArray("addons")?.map { it.asString } ?: emptyList(), m.get("state").asString,
            m.get("recommended")?.asInt ?: 4, m.get("max")?.asInt ?: 16, members,
        )
    }

    /** Bin ich der Host der aktuellen Lobby? */
    fun isHost(): Boolean {
        val l = lobby ?: return false
        val me = MinecraftClient.getInstance().session.uuidOrNull?.toString() ?: return false
        return l.host == me
    }
}
