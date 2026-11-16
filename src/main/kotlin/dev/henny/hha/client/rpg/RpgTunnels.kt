package dev.henny.hha.client.rpg

import dev.henny.hha.Hha
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * Tunnel der Minecraft-Verbindungen über den Lobby-Dienst.
 *
 * - **Host:** Für jeden Strom (`tcp_open`) öffnet er eine Verbindung zu seinem eigenen LAN-Port auf 127.0.0.1 und
 *   reicht die Bytes in beide Richtungen weiter. Daten, die vor dem Verbinden ankommen, werden gepuffert.
 * - **Mitspieler:** Ein lokaler Port auf 127.0.0.1 nimmt die Verbindung seines Minecraft an; jede bekommt einen
 *   Strom zum Host.
 *
 * Die Gegenseite ist immer nur der eigene Rechner: Der Host verbindet ausschließlich zu seinem LAN-Port.
 */
object RpgTunnels {

    /** LAN-Port der eigenen Welt (Host), `-1` solange nicht offen. */
    @Volatile var lanPort = -1

    private class Stream(val sid: Int) {
        @Volatile var socket: Socket? = null
        val pending = ByteArrayOutputStream()
        @Volatile var closed = false
    }

    private val streams = ConcurrentHashMap<Int, Stream>()
    private var listener: ServerSocket? = null

    // ---------------------------------------------------------------------------------------------
    // Host
    // ---------------------------------------------------------------------------------------------

    fun hostOpen(sid: Int) {
        val port = lanPort
        if (port <= 0) {
            RpgNet.send("tcp_fail") { addProperty("sid", sid) }
            return
        }
        val stream = Stream(sid)
        streams[sid] = stream
        Thread({
            try {
                val socket = Socket(InetAddress.getLoopbackAddress(), port)
                socket.tcpNoDelay = true
                synchronized(stream) {
                    stream.socket = socket
                    val early = stream.pending.toByteArray()
                    if (early.isNotEmpty()) socket.getOutputStream().write(early)
                    stream.pending.reset()
                }
                RpgNet.send("tcp_ok") { addProperty("sid", sid) }
                pump(stream, socket)
            } catch (e: IOException) {
                Hha.LOGGER.warn("RPG-Tunnel {}: LAN-Port nicht erreichbar: {}", sid, e.message)
                close(sid, notify = true)
            }
        }, "HHA-RPG-Host-$sid").apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------------------------------------
    // Mitspieler
    // ---------------------------------------------------------------------------------------------

    /** Lokaler Port für das eigene Minecraft (nur 127.0.0.1); jede angenommene Verbindung wird ein Strom. */
    fun startJoinListener(): Int {
        listener?.let { return it.localPort }
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        listener = server
        Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                socket.tcpNoDelay = true
                val sid = ThreadLocalRandom.current().nextInt(1, Int.MAX_VALUE)
                val stream = Stream(sid)
                stream.socket = socket
                streams[sid] = stream
                RpgNet.send("tcp_open") { addProperty("sid", sid) }
                Thread({ pump(stream, socket) }, "HHA-RPG-Join-$sid").apply { isDaemon = true }.start()
            }
        }, "HHA-RPG-Listener").apply { isDaemon = true }.start()
        return server.localPort
    }

    /** Antwort des Hosts: verbunden. (Daten fließen schon, der Host puffert bis dahin.) */
    fun joinAccepted(@Suppress("UNUSED_PARAMETER") sid: Int) {}

    // ---------------------------------------------------------------------------------------------
    // Gemeinsam
    // ---------------------------------------------------------------------------------------------

    private fun pump(stream: Stream, socket: Socket) {
        val buf = ByteArray(32 * 1024)
        try {
            val input = socket.getInputStream()
            while (!stream.closed) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) RpgNet.sendBinary(stream.sid, buf, n)
            }
        } catch (_: IOException) {
        }
        close(stream.sid, notify = true)
    }

    /** Daten vom Dienst für Strom [sid]. */
    fun onData(sid: Int, data: ByteArray, off: Int, len: Int) {
        val stream = streams[sid] ?: return
        synchronized(stream) {
            val socket = stream.socket
            if (socket == null) {
                stream.pending.write(data, off, len)
                return
            }
            try {
                socket.getOutputStream().write(data, off, len)
            } catch (_: IOException) {
                close(sid, notify = true)
            }
        }
    }

    fun close(sid: Int, notify: Boolean) {
        val stream = streams.remove(sid) ?: return
        stream.closed = true
        runCatching { stream.socket?.close() }
        if (notify) RpgNet.send("tcp_close") { addProperty("sid", sid) }
    }

    fun closeAll() {
        for (sid in streams.keys.toList()) close(sid, notify = false)
        runCatching { listener?.close() }
        listener = null
        lanPort = -1
    }
}
