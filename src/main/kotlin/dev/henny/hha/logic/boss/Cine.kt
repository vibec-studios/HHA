package dev.henny.hha.logic.boss

import dev.henny.hha.logic.Vfx
import dev.henny.hha.net.CinePayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.Entity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.Vec3d
import java.util.UUID

/**
 * Befehle an die Cutscene-Wiedergabe des Clients (`client/cine/CinePlayer`). Alle Inhalte kommen vom
 * Server; der Client spielt nur ab. Die Opcodes sind das Protokoll, deshalb nur hinten anfügen.
 */
object Cine {

    const val OP_START = 0
    const val OP_CAMERA = 1
    const val OP_DIALOG = 2
    const val OP_FADE = 3
    const val OP_TITLE = 4
    const val OP_END = 5
    const val OP_ATMOSPHERE = 6
    const val OP_WINGS = 7
    const val OP_ORBIT = 8
    const val OP_MUSIC = 9
    const val OP_CLEAR_VFX = 10
    const val OP_CUT = 11
    const val OP_SKIP_VOTES = 12
    const val OP_PHASE_BAR = 13

    /** Alles am Client zurücksetzen (Kamera, Text, Stimme, Abblende, Titel, Musik, Leiste), z. B. nach dem Ausscheiden. */
    const val OP_RESET = 14

    /** Sprecher für die Textbox und die Stimmlage der Dialog-Laute. */
    const val SPEAKER_NARRATOR = 0
    const val SPEAKER_HERMES = 1
    const val SPEAKER_ZEUS = 2
    const val SPEAKER_SHADE = 3
    const val SPEAKER_HADES = 4
    const val SPEAKER_KRONOS = 5

    /** Neue Bosse des Olymp (sprechen vorerst nur als Text mit Stimm-Lauten, PLAN 14 Entscheidung 4). */
    const val SPEAKER_HEKATE = 6
    const val SPEAKER_BRONTES = 7
    const val SPEAKER_KRATOS = 8

    /** Wer gerade in einer Cutscene steckt. Nur dann darf er sie überspringen. */
    private val playing = HashSet<UUID>()

    /**
     * Wer welchen Kampf verlassen hat (Dimensionswechsel), je Spieler die Kämpfe. Drehbücher merken sich ihre
     * Empfängerlisten oft für eine ganze Szene; Sendungen **dieses** Kampfes ([encounter] beim Senden) an ihn werden
     * deshalb verworfen. Andere Kämpfe und Sendungen außerhalb eines Kampfes (z. B. der Jäger im Tartaros) erreichen ihn
     * weiter. Erst ein erneuter Beitritt zu genau diesem Kampf ([rejoin]) oder sein Ende ([encounterEnded]) hebt das auf.
     */
    private val departed = HashMap<UUID, MutableSet<Any>>()

    /**
     * Der Kampf, dessen Ablauf gerade läuft (die Register setzen ihn um jeden Kampf-Tick). Dient nur dazu, Sendungen
     * aus alten Empfängerlisten eines verlassenen Kampfes zu erkennen.
     */
    @JvmField
    var encounter: Any? = null

    /** Führt [block] als Teil des Kampfes [fight] aus (siehe [encounter]). */
    inline fun <T> within(fight: Any, block: () -> T): T {
        val previous = encounter
        encounter = fight
        try {
            return block()
        } finally {
            encounter = previous
        }
    }

    private fun blocked(player: ServerPlayerEntity): Boolean {
        val current = encounter ?: return false
        return departed[player.uuid]?.contains(current) == true
    }

    /** Zähler je Spieler: jede neue Szene ([start]) bekommt eine neue Nummer. Stimmen zum Überspringen gelten nur für sie. */
    private val scene = HashMap<UUID, Int>()

    fun sceneOf(id: UUID): Int = scene[id] ?: 0

    /** Läuft ein Vorspulen, verschwinden Kamera, Text, Effekte und Klänge. Die Spielzustände ändern sich trotzdem. */
    @JvmField
    var muted = false

    /** Die Kämpfe melden sich hier an: true heißt, der Spieler gehört zu dieser Cutscene. */
    private val skipHandlers = ArrayList<(ServerPlayerEntity) -> Boolean>()

    fun onSkip(handler: (ServerPlayerEntity) -> Boolean) {
        skipHandlers.add(handler)
    }

    /** Vom Netzwerk: Der Spieler hat die Überspringen-Taste lange genug gehalten. */
    fun requestSkip(player: ServerPlayerEntity) {
        if (player.uuid !in playing) return
        for (handler in skipHandlers) {
            if (handler(player)) return
        }
    }

    fun isPlaying(player: ServerPlayerEntity): Boolean = player.uuid in playing

    fun start(players: Iterable<ServerPlayerEntity>) {
        for (player in players) {
            if (blocked(player)) continue
            playing.add(player.uuid)
            scene[player.uuid] = sceneOf(player.uuid) + 1
        }
        send(players, OP_START)
    }

    /**
     * Ein Spieler scheidet aus einem Kampf aus, ohne die Verbindung zu verlieren (z. B. Dimensionswechsel): Sein Client
     * wird vollständig zurückgesetzt, und spätere Sendungen aus alten Empfängerlisten erreichen ihn nicht mehr.
     */
    fun depart(player: ServerPlayerEntity, from: Any) {
        playing.remove(player.uuid)
        ServerPlayNetworking.send(player, CinePayload(OP_RESET, emptyList(), emptyList()))
        departed.getOrPut(player.uuid) { HashSet() }.add(from)
    }

    /**
     * Setzt die Clients von [players] komplett zurück (Kamera, Text/Stimme, Titel, **Abblende**, Musik, Leiste, Effekte).
     * Für Abbrüche: Ein Drehbuch, das mitten in einer Abblende endet, schickt sonst nie die Gegenblende.
     */
    fun reset(players: Iterable<ServerPlayerEntity>) {
        for (player in players) playing.remove(player.uuid)
        send(players, OP_RESET, force = true)
    }

    /** Ein Spieler tritt dem Kampf [to] (wieder) bei: Dessen Sendungen erreichen ihn wieder. Andere Sperren bleiben. */
    fun rejoin(player: ServerPlayerEntity, to: Any) {
        val set = departed[player.uuid] ?: return
        set.remove(to)
        if (set.isEmpty()) departed.remove(player.uuid)
    }

    /** Ein Kampf ist vorbei: Seine Sperren werden nicht mehr gebraucht. */
    fun encounterEnded(fight: Any) {
        val iterator = departed.values.iterator()
        while (iterator.hasNext()) {
            val set = iterator.next()
            set.remove(fight)
            if (set.isEmpty()) iterator.remove()
        }
    }

    fun end(players: Iterable<ServerPlayerEntity>) {
        for (player in players) playing.remove(player.uuid)
        send(players, OP_END)
    }

    /** Vergisst einen Spieler, der ausgeloggt hat. */
    fun forget(player: ServerPlayerEntity) {
        playing.remove(player.uuid)
        departed.remove(player.uuid)
        scene.remove(player.uuid)
    }

    /** Harter Schnitt nach dem Überspringen: Stimme und Text aus, kurzes Aufblenden aus Schwarz. */
    fun cut(players: Iterable<ServerPlayerEntity>) = send(players, OP_CUT, emptyList(), emptyList(), force = true)

    /**
     * Die geteilte Lebensleiste der großen Bosse: [segments] Phasen, [current] ist die laufende (ab 1), [fraction] ihr
     * Lebensanteil. [theme] 0 ist Hades (grün), 1 Kronos (gold). Ohne [visible] verschwindet sie.
     */
    fun phaseBar(
        players: Iterable<ServerPlayerEntity>, visible: Boolean, segments: Int = 3, current: Int = 1, fraction: Float = 1f,
        theme: Int = 0, nameKey: String = "", phaseKey: String = "",
    ) = send(
        players, OP_PHASE_BAR,
        listOf(if (visible) 1.0 else 0.0, segments.toDouble(), current.toDouble(), fraction.toDouble(), theme.toDouble()),
        listOf(nameKey, phaseKey), force = true,
    )

    /** Zeigt an, wie viele Mitspieler schon überspringen wollen. */
    fun skipVotes(players: Iterable<ServerPlayerEntity>, votes: Int, total: Int) =
        send(players, OP_SKIP_VOTES, listOf(votes.toDouble(), total.toDouble()), emptyList(), force = true)

    /** Gerade Kamerafahrt von [from] nach [to]; der Blick wandert von [lookFrom] nach [lookTo] oder folgt [lookAt]. */
    fun camera(
        players: Iterable<ServerPlayerEntity>, from: Vec3d, to: Vec3d, lookFrom: Vec3d, lookTo: Vec3d, ticks: Int,
        fovFrom: Double = 70.0, fovTo: Double = 70.0, lookAt: Entity? = null, lookOffset: Double = 1.6,
    ) = send(
        players, OP_CAMERA,
        listOf(
            from.x, from.y, from.z, to.x, to.y, to.z,
            lookFrom.x, lookFrom.y, lookFrom.z, lookTo.x, lookTo.y, lookTo.z,
            ticks.toDouble(), fovFrom, fovTo, (lookAt?.id ?: -1).toDouble(), lookOffset,
        ),
    )

    /** Kamerakreis um [center]; Winkel in Grad, [lookAt] wird angeschaut (sonst die Mitte). */
    fun orbit(
        players: Iterable<ServerPlayerEntity>, center: Vec3d, radius: Double, height: Double, angleFrom: Double, angleTo: Double,
        ticks: Int, fovFrom: Double = 70.0, fovTo: Double = 70.0, lookAt: Entity? = null, lookOffset: Double = 1.6,
        radiusTo: Double = radius, heightTo: Double = height,
    ) = send(
        players, OP_ORBIT,
        listOf(
            center.x, center.y, center.z, radius, radiusTo, height, heightTo, angleFrom, angleTo,
            ticks.toDouble(), fovFrom, fovTo, (lookAt?.id ?: -1).toDouble(), lookOffset,
        ),
    )

    fun dialog(players: Iterable<ServerPlayerEntity>, speaker: Int, lineKey: String, ticks: Int) =
        send(players, OP_DIALOG, listOf(speaker.toDouble(), ticks.toDouble()), listOf(lineKey))

    /** Blende: Farbe (0xRRGGBB) von Deckkraft [from] nach [to]. */
    fun fade(players: Iterable<ServerPlayerEntity>, color: Int, from: Double, to: Double, ticks: Int) =
        send(players, OP_FADE, listOf(color.toDouble(), from, to, ticks.toDouble()))

    fun title(players: Iterable<ServerPlayerEntity>, titleKey: String, subtitleKey: String, ticks: Int) =
        send(players, OP_TITLE, listOf(ticks.toDouble()), listOf(titleKey, subtitleKey))

    fun atmosphere(players: Iterable<ServerPlayerEntity>, style: Vfx.Style?, center: Vec3d, ticks: Int) = send(
        players, OP_ATMOSPHERE,
        listOf((style?.ordinal ?: -1).toDouble(), center.x, center.y, center.z, ticks.toDouble()),
    )

    /** Startet ein Musikstück in Dauerschleife, `null` stoppt die Musik. */
    fun music(players: Iterable<ServerPlayerEntity>, event: SoundEvent?) =
        send(players, OP_MUSIC, emptyList(), listOf(event?.id()?.toString() ?: ""))

    /** Beendet alle laufenden 3D-Effekte (z. B. Flügel und Auren, die eigentlich länger halten). */
    fun clearVfx(players: Iterable<ServerPlayerEntity>) = send(players, OP_CLEAR_VFX)

    fun wings(players: Iterable<ServerPlayerEntity>, on: Boolean) =
        send(players, OP_WINGS, listOf(if (on) 1.0 else 0.0))

    private fun send(players: Iterable<ServerPlayerEntity>, op: Int, numbers: List<Double> = emptyList(), texts: List<String> = emptyList(), force: Boolean = false) {
        if (muted && !force && op in SILENT_WHEN_MUTED) return
        val payload = CinePayload(op, numbers, texts)
        for (player in players) {
            if (player.isDisconnected || blocked(player)) continue
            ServerPlayNetworking.send(player, payload)
        }
    }

    private val SILENT_WHEN_MUTED = setOf(OP_CAMERA, OP_ORBIT, OP_DIALOG, OP_FADE, OP_TITLE)
}

/**
 * Überspringen für einen Kampf: Jeder Spieler stimmt per Taste ab, sind alle dafür, spult der Kampf die Szene im
 * nächsten Tick vor. Das Drehbuch läuft dabei wirklich durch, damit am Ende alles so dasteht wie nach der Szene.
 */
class CineSkip {
    /** Stimme je Spieler, mit der Szenennummer ([Cine.sceneOf]), in der sie abgegeben wurde. */
    private val votes = HashMap<UUID, Int>()

    /** Beim nächsten Tick vorspulen? */
    var pending = false
        private set

    fun vote(player: ServerPlayerEntity, everyone: List<ServerPlayerEntity>) {
        votes[player.uuid] = Cine.sceneOf(player.uuid)
        recheck(everyone)
    }

    /**
     * Zählt neu, wer in der laufenden Szene schon gestimmt hat. Stimmen aus einer früheren Szene zählen nicht mehr (eine
     * Szene, die von selbst zu Ende ging, nimmt ihre Stimmen nicht mit). Auch aufrufen, wenn jemand ausscheidet.
     */
    fun recheck(everyone: List<ServerPlayerEntity>) {
        val watching = everyone.filter { Cine.isPlaying(it) }
        val ids = watching.map { it.uuid }.toSet()
        votes.entries.removeIf { (id, scene) -> id !in ids || scene != Cine.sceneOf(id) }
        if (votes.isEmpty()) return
        if (watching.isNotEmpty() && votes.size >= watching.size) pending = true else Cine.skipVotes(watching, votes.size, watching.size)
    }

    fun reset() {
        votes.clear()
        pending = false
    }

    /**
     * Führt [advance] so lange aus, bis niemand mehr in der Szene steckt. [advance] liefert false, wenn das Drehbuch vorher
     * zu Ende ist. Die Obergrenze fängt ein Drehbuch ab, das auf Spieler wartet und nie von selbst endet.
     */
    fun fastForward(everyone: List<ServerPlayerEntity>, advance: () -> Boolean) {
        reset()
        Cine.muted = true
        try {
            var guard = 0
            while (everyone.any { Cine.isPlaying(it) } && guard++ < MAX_TICKS) {
                if (!advance()) break
            }
        } finally {
            Cine.muted = false
        }
        Cine.cut(everyone)
    }

    private companion object {
        /** Zehn Minuten Spielzeit: länger ist keine Szene. */
        const val MAX_TICKS = 20 * 60 * 10
    }
}
