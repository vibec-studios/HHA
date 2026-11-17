package dev.henny.hha.rpg

import dev.henny.hha.Hha
import dev.henny.hha.api.rpg.HhaRpg
import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.CineSkip
import dev.henny.hha.net.RpgClientPayload
import dev.henny.hha.net.RpgStatePayload
import dev.henny.hha.stage.StageScripts
import dev.henny.hha.world.olymp.OlympusLayout
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/** Ein Ankunfts- bzw. Rastort: Welt, Lage, Blick (Grad wie `Entity.yaw`). */
class Arrival(val world: RegistryKey<World>, val pos: Vec3d, val yaw: Float)

/**
 * Warten auf alle und gemeinsame Ankunft (PLAN 4.4), nur für Wege mit `HhaRpg.Path.arrival`.
 *
 * Ablauf:
 * 1. Wer eine RPG-Welt **vor** der Ankunft betritt, kommt in die **Halte** ([holds]): auf seine Marke (Formation PLAN
 *    6.7, auf festen Boden gesetzt), unverwundbar ([isProtected] über `ALLOW_DAMAGE`/`ALLOW_DEATH`, also **ohne**
 *    gespeichertes Flag), Geschwindigkeit null; der Client bekommt `phase = HOLD` und sperrt selbst die Eingaben.
 * 2. Die Frist beginnt mit dem `READY` des Hosts: `rpg_wait_ticks` (Standard 400). Ist sie um und steht noch jemand
 *    ohne `READY` in der Halte, verlängert sie sich **einmal** um [EXTEND_TICKS]. Rückfall: Meldet der Host nie
 *    `READY`, beginnt sie [HOST_READY_FALLBACK] Ticks nach seinem Beitritt trotzdem, dann ohne Verlängerung
 *    (zusammen 28 s, unter der 30-s-Grenze des Clients).
 * 3. Sind alle Erwarteten (Lobby-Liste `RpgWorld.State.expected`, ohne Getrennte) und alle in der Halte `READY`, oder
 *    ist die Frist um: `arrived = true`, Gepäck, erste Rast, `Cine.start` + `Cine.stage(<Skript des Wegs>)` mit allen
 *    als Darstellern. Nach `StageScripts.length(id)` Ticks: `Cine.end`, `phase = PLAY`, [onArrived].
 *    Fehlt das Skript: [FALLBACK_TITLE_TICKS] Ticks der Titel von früher.
 * 4. **Nachzügler** (Welt schon angekommen, Spieler noch nie): kurze eigene Halte bis `READY` (höchstens
 *    [LATE_READY_TIMEOUT]), dann Marke, Gepäck, Rast und `rpg.arrival.late`. Wer schon einmal angekommen ist, spawnt
 *    normal an seiner Rast, ohne Szene.
 *
 * Randfälle (PLAN 4.4 Punkt 6): siehe [onLeave] (Wartender trennt / Host geht), [isProtected] (kein Tod in der Halte),
 * [finishScene] (Überspringen → fester Endzustand), [onJoin] (Wiederstart: `arrived` gespeichert → keine Halte),
 * [SERVER_STOPPING][init] (alles im Speicher fällt weg, nichts Gespeichertes bleibt hängen).
 */
object RpgArrival {

    /**
     * Ankunfts-Marken (PLAN 6.7) relativ zum Anker als (rechts, vor) in Blöcken. Platz 0 = Gastgeber; Plätze 8–15 als
     * zweite Reihe [SECOND_ROW] Blöcke hinter dem jeweiligen Platz der ersten Reihe.
     */
    private val MARKS = arrayOf(
        0.0 to 0.0, -2.2 to -1.4, 2.2 to -1.4, -4.4 to -2.8, 4.4 to -2.8, -6.6 to -4.2, 6.6 to -4.2, 0.0 to -5.6,
    )

    /** Abstand der zweiten Reihe nach hinten. */
    private const val SECOND_ROW = 3.0

    /** Höchstzahl der Marken (zwei Reihen). */
    const val MAX_MARKS = 16

    /** Meldet der Host kein `READY`, beginnt die Frist trotzdem so viele Ticks nach seinem Beitritt. */
    const val HOST_READY_FALLBACK = 160

    /** Einmalige Verlängerung der Frist, wenn noch jemand ohne `READY` in der Halte steht. */
    const val EXTEND_TICKS = 100

    /** Nachzügler warten höchstens so lange auf ihr `READY`. */
    const val LATE_READY_TIMEOUT = 100

    /** Ohne Ankunfts-Skript: so lange der alte Titel. */
    const val FALLBACK_TITLE_TICKS = 60

    /** Weiter als so viele Blöcke von der Marke weg → zurückgesetzt. */
    private const val HOLD_DRIFT = 0.5

    /** In der Szene etwas großzügiger (Posen dürfen die Figur nicht zurückreißen). */
    private const val SCENE_DRIFT = 1.5

    /** Bodensuche um die Marke: so weit hinauf … */
    private const val GROUND_UP = 6

    /** … und so weit hinab. */
    private const val GROUND_DOWN = 14

    /** Skript der Nachzügler. */
    const val LATE_STAGE = "rpg.arrival.late"

    /** Ein Spieler in der Halte; [late] = Nachzügler (eigene kurze Halte). */
    private class Hold(val id: UUID, val index: Int, val mark: Vec3d, val joinedAt: Int, val late: Boolean)

    /** Eine laufende Ankunftsszene (Gruppe oder ein Nachzügler). */
    private class Scene(
        val players: MutableList<UUID>,
        val marks: Map<UUID, Vec3d>,
        val stageId: String,
        val scripted: Boolean,
        var left: Int,
        val late: Boolean,
    ) {
        val skip = CineSkip()
    }

    private val holds = LinkedHashMap<UUID, Hold>()
    private val ready = HashSet<UUID>()

    /** Erwartete, die sich in der Halte getrennt haben: Sie zählen nicht mehr (PLAN 4.4 Punkt 6). */
    private val dropped = HashSet<UUID>()

    /** Server-Tick, an dem die Frist abläuft; −1 = noch nicht begonnen. */
    private var deadline = -1
    private var extended = false

    /** Hat das `READY` des Hosts die Frist gestartet? Nur dann gibt es die Verlängerung (sonst Rückfall ohne sie). */
    private var deadlineByHost = false

    /** Server-Tick, an dem der Host die Halte betrat; −1 = noch nicht. */
    private var hostJoinedAt = -1

    private var group: Scene? = null
    private val lateScenes = LinkedHashMap<UUID, Scene>()

    private val arrivedListeners = ArrayList<(MinecraftServer, List<ServerPlayerEntity>) -> Unit>()

    /** Alles Gemerkte gehört zu einem Server (Einzelspieler: andere Welt geöffnet → neu anfangen). */
    private var owner: MinecraftServer? = null

    fun init() {
        ServerPlayConnectionEvents.JOIN.register { handler, _, server -> onJoin(server, handler.player) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server -> onLeave(server, handler.player) }
        ServerTickEvents.END_SERVER_TICK.register(::tick)
        // Host verlässt die Welt in der Halte → der integrierte Server endet; die Clients fallen zurück (P18, PLAN 5).
        // Hier bleibt nichts hängen: Unverwundbarkeit und Halte leben nur im Speicher.
        ServerLifecycleEvents.SERVER_STOPPING.register { clear() }
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, _, _ ->
            val player = entity as? ServerPlayerEntity ?: return@register true
            !isProtected(player)
        }
        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, _, _ ->
            val player = entity as? ServerPlayerEntity ?: return@register true
            if (!isProtected(player)) return@register true
            player.health = player.maxHealth.coerceAtLeast(1f)
            false
        }
        Cine.onSkip { player ->
            val scene = sceneOf(player.uuid) ?: return@onSkip false
            val server = player.entityWorld.server ?: return@onSkip false
            scene.skip.vote(player, online(server, scene.players))
            true
        }
    }

    private fun clear() {
        holds.clear()
        ready.clear()
        dropped.clear()
        deadline = -1
        extended = false
        deadlineByHost = false
        hostJoinedAt = -1
        group = null
        lateScenes.clear()
    }

    // ---------------------------------------------------------------------------------------------
    // Öffentlich
    // ---------------------------------------------------------------------------------------------

    /** Ankunft des Himmelswegs: `OlympusLayout.ARRIVAL_SPOT`, Blick `ARRIVAL_YAW`. */
    fun heavenArrival(server: MinecraftServer): Arrival = Arrival(OlympusLayout.WORLD, OlympusLayout.ARRIVAL_SPOT, OlympusLayout.ARRIVAL_YAW)

    /** Ankunft des Unterweltwegs: vor dem Höllentor auf der Terrasse, Blick 90 (wie bisher `hellStart`). */
    fun hellArrival(server: MinecraftServer): Arrival {
        val gate = OlympusLayout.HELL_GATE
        return Arrival(OlympusLayout.WORLD, Vec3d(gate.x + 6.5, gate.y.toDouble(), gate.z + 0.5), 90f)
    }

    /** Vom Netz: Meldung eines Clients ([RpgClientPayload.READY] …). */
    fun onClient(player: ServerPlayerEntity, payload: RpgClientPayload) {
        val server = player.entityWorld.server ?: return
        if (!RpgWorld.of(server).enabled) return
        if (payload.kind != RpgClientPayload.READY) return
        val hold = holds[player.uuid] ?: return
        if (!ready.add(player.uuid)) return
        if (!hold.late && deadline < 0 && isHost(server, player)) {
            deadline = server.ticks + waitTicks()
            deadlineByHost = true
            Hha.LOGGER.info("RPG-Ankunft: Host bereit, Frist {} Ticks", waitTicks())
        }
        RpgServer.markAllDirty()
    }

    /** Anker der Ankunft des laufenden Wegs (`Path.arrival`, sonst Himmel; Unterwelt vor dem Höllentor, Blick 90). */
    fun anchor(server: MinecraftServer): Arrival {
        val state = RpgWorld.of(server)
        val path = HhaRpg.path(state.path)
        path?.arrival?.let { return it(server) }
        return if (state.path == "hell") hellArrival(server) else heavenArrival(server)
    }

    /**
     * [count] (höchstens 16) Marken um den Anker (Formation PLAN 6.7), noch nicht auf festen Boden gesetzt.
     * Vorn = Blickrichtung `(−sin yaw, 0, cos yaw)`, rechts = `(−cos yaw, 0, −sin yaw)`.
     */
    fun marks(server: MinecraftServer, count: Int): List<Vec3d> {
        val a = anchor(server)
        val rad = Math.toRadians(a.yaw.toDouble())
        val fx = -sin(rad)
        val fz = cos(rad)
        val rx = -cos(rad)
        val rz = -sin(rad)
        return List(count.coerceIn(0, MAX_MARKS)) { i ->
            val (right, forward0) = MARKS[i % MARKS.size]
            val forward = forward0 - if (i >= MARKS.size) SECOND_ROW else 0.0
            a.pos.add(rx * right + fx * forward, 0.0, rz * right + fz * forward)
        }
    }

    /** Marke [index] auf festem Boden (Suche [GROUND_UP] hinauf bis [GROUND_DOWN] hinab; ohne Boden die Ankerhöhe). */
    fun groundedMark(server: MinecraftServer, index: Int): Vec3d {
        val a = anchor(server)
        val raw = marks(server, MAX_MARKS)[Math.floorMod(index, MAX_MARKS)]
        val world = server.getWorld(a.world) ?: return raw
        return ground(world, raw)
    }

    /** Steht [player] gerade in der Halte (unverwundbar, unbeweglich)? Auch Nachzügler vor ihrer Szene. */
    fun isHolding(player: ServerPlayerEntity): Boolean = player.uuid in holds

    /** Steht [player] in der Halte oder in einer Ankunftsszene (unverwundbar)? */
    fun isProtected(player: ServerPlayerEntity): Boolean = player.uuid in holds || sceneOf(player.uuid) != null

    /** Läuft gerade die gemeinsame Ankunftsszene? (Prolog erst danach fertig.) */
    fun isArriving(server: MinecraftServer): Boolean = group != null && owner === server

    /** Phase für [RpgStatePayload]: Halte, Ankunftsszene oder Spiel. */
    fun phaseOf(player: ServerPlayerEntity): Int = when {
        player.uuid in holds -> RpgStatePayload.PHASE_HOLD
        sceneOf(player.uuid) != null -> RpgStatePayload.PHASE_ARRIVE
        else -> RpgStatePayload.PHASE_PLAY
    }

    /** Die Gruppe ist angekommen: gerufen **am Ende** der Ankunftsszene (auch nach Überspringen) mit allen Darstellern. */
    fun onArrived(listener: (MinecraftServer, List<ServerPlayerEntity>) -> Unit) {
        arrivedListeners.add(listener)
    }

    /**
     * Namen derer, auf die die gemeinsame Halte noch wartet (verbundene Spieler mit Namen, noch nicht verbundene
     * Lobby-Mitglieder über den Namens-Cache, sonst als UUID-Text). Leer, wenn keine Halte läuft.
     */
    fun waitingNames(server: MinecraftServer): List<String> {
        if (holds.values.none { !it.late }) return emptyList()
        return waitingIds(server).map { id ->
            server.playerManager.getPlayer(id)?.gameProfile?.name
                ?: runCatching { server.apiServices.nameToIdCache().getByUuid(id).map { it.name() }.orElse(null) }.getOrNull()
                ?: id.toString()
        }
    }

    /** Zustand für `/hha rpg state`. */
    fun describe(server: MinecraftServer): List<String> {
        val now = server.ticks
        val lines = ArrayList<String>()
        val state = RpgWorld.of(server)
        lines.add("arrived=${state.arrived}, erwartet=${state.expected.size}, getrennt=${dropped.size}, " +
            "Frist=${if (deadline < 0) "–" else "${deadline - now} Ticks"}${if (extended) " (verlängert)" else ""}")
        for (hold in holds.values) {
            val name = server.playerManager.getPlayer(hold.id)?.gameProfile?.name ?: hold.id.toString()
            lines.add("Halte: $name Platz ${hold.index}${if (hold.late) " (Nachzügler)" else ""}${if (hold.id in ready) " READY" else ""}")
        }
        group?.let { lines.add("Szene ${it.stageId}: noch ${it.left} Ticks, ${it.players.size} Darsteller") }
        for (scene in lateScenes.values) lines.add("Nachzügler-Szene ${scene.stageId}: noch ${scene.left} Ticks")
        val waiting = waitingNames(server)
        if (waiting.isNotEmpty()) lines.add("wartet auf: ${waiting.joinToString(", ")}")
        return lines
    }

    /**
     * `/hha rpg arrive`: Läuft eine Halte, beginnt die Ankunft sofort. Sonst spielt die Ankunftsszene noch einmal für
     * alle, die gerade in der Welt sind (zum Prüfen; Gepäck wird nicht doppelt vergeben, die Rast liegt danach an der
     * Marke).
     */
    fun arriveNow(server: MinecraftServer): Boolean {
        if (!hasArrival(server)) return false
        bind(server)
        if (holds.values.any { !it.late }) {
            startGroup(server, "Befehl")
            return true
        }
        if (group != null) return false
        val players = server.playerManager.playerList.filter { it.isAlive && !it.isSpectator && sceneOf(it.uuid) == null }
        if (players.isEmpty()) return false
        for (player in players) holds.remove(player.uuid)
        ensureArrived(server)
        val marks = LinkedHashMap<UUID, Vec3d>()
        players.forEachIndexed { i, p -> marks[p.uuid] = groundedMark(server, i) }
        place(server, players, marks)
        playScene(server, players, marks, late = false)
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Beitritt und Abschied
    // ---------------------------------------------------------------------------------------------

    /** Hat der Weg der Welt eine eigene Ankunft? (Addon-Wege ohne `arrival`: bisheriges Verhalten in RpgServer.) */
    private fun hasArrival(server: MinecraftServer): Boolean {
        val state = RpgWorld.of(server)
        return state.enabled && HhaRpg.path(state.path)?.arrival != null
    }

    private fun bind(server: MinecraftServer) {
        if (owner !== server) {
            clear()
            owner = server
        }
    }

    /**
     * Beitritt. **Wiederstart einer bestehenden RPG-Welt** (Einzelspieler-Menü, `RpgWorld.pending` leer): `arrived` und
     * `started` sind gespeichert → wer schon angekommen ist, kommt nicht in die Halte und bekommt nichts doppelt.
     */
    private fun onJoin(server: MinecraftServer, player: ServerPlayerEntity) {
        if (!hasArrival(server)) return
        bind(server)
        val state = RpgWorld.of(server)
        if (state.hasStarted(player.uuid)) return
        enterHold(server, player, late = state.arrived)
    }

    /**
     * Abschied. **Ein Wartender trennt** → er zählt nicht mehr als erwartet (kommt er wieder, steht er erneut in der
     * Halte). **Host verlässt die Welt in der Halte** → der integrierte Server endet ([clear] bei `SERVER_STOPPING`).
     * In einer laufenden Szene fällt er aus der Darstellerliste; die übrigen spielen weiter.
     */
    private fun onLeave(server: MinecraftServer, player: ServerPlayerEntity) {
        if (owner !== server) return
        val id = player.uuid
        val hold = holds.remove(id)
        ready.remove(id)
        if (hold != null && !hold.late) dropped.add(id)
        group?.let { scene ->
            scene.players.remove(id)
            scene.skip.recheck(online(server, scene.players).filter { it.uuid != id })
        }
        lateScenes.remove(id)
        RpgServer.markAllDirty()
    }

    private fun enterHold(server: MinecraftServer, player: ServerPlayerEntity, late: Boolean) {
        val now = server.ticks
        val state = RpgWorld.of(server)
        val host = isHost(server, player) || (server.hostProfile == null && holds.isEmpty())
        val index = if (late) Math.floorMod(state.startedCount(), MAX_MARKS) else freeIndex(server, player, host)
        val mark = groundedMark(server, index)
        holds[player.uuid] = Hold(player.uuid, index, mark, now, late)
        dropped.remove(player.uuid)
        ready.remove(player.uuid)
        if (!late && host && hostJoinedAt < 0) hostJoinedAt = now
        pin(server, player, mark, 0.0)
        RpgServer.markAllDirty()
    }

    /** Platz in der Formation: Host 0, sonst nach der Lobby-Reihenfolge, sonst der nächste freie. */
    private fun freeIndex(server: MinecraftServer, player: ServerPlayerEntity, host: Boolean): Int {
        val taken = holds.values.filter { !it.late }.map { it.index }.toHashSet()
        if (host && 0 !in taken) return 0
        val hostId = server.hostProfile?.id?.toString()
        val order = RpgWorld.of(server).expected.filter { it != hostId }
        val wanted = order.indexOf(player.uuid.toString()).let { if (it >= 0) it + 1 else -1 }
        if (wanted in 1 until MAX_MARKS && wanted !in taken) return wanted
        return (1 until MAX_MARKS).firstOrNull { it !in taken } ?: Math.floorMod(holds.size, MAX_MARKS)
    }

    private fun isHost(server: MinecraftServer, player: ServerPlayerEntity): Boolean =
        server.hostProfile?.id == player.uuid

    private fun waitTicks(): Int = HhaConfig.num("rpg_wait_ticks").toInt().coerceIn(20, 20 * 120)

    /** Wer noch fehlt: Erwartete ohne `READY` (außer Getrennten) und jeder in der Halte ohne `READY`. */
    private fun waitingIds(server: MinecraftServer): List<UUID> {
        val result = LinkedHashSet<UUID>()
        for (text in RpgWorld.of(server).expected) {
            val id = runCatching { UUID.fromString(text) }.getOrNull() ?: continue
            if (id in dropped || id in ready) continue
            // Ein Erwarteter, der schon angekommen ist (z. B. nach `/hha rpg chapter`), wartet nicht.
            if (RpgWorld.of(server).hasStarted(id)) continue
            result.add(id)
        }
        for (hold in holds.values) if (!hold.late && hold.id !in ready) result.add(hold.id)
        return result.toList()
    }

    // ---------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------

    private fun tick(server: MinecraftServer) {
        if (owner !== server) {
            if (holds.isEmpty() && group == null && lateScenes.isEmpty()) return
            bind(server)
        }
        if (!RpgWorld.of(server).enabled) return
        val now = server.ticks

        // Halte: auf der Marke festhalten (Geschwindigkeit null, kein Fall).
        val gone = ArrayList<UUID>()
        for (hold in holds.values) {
            val player = server.playerManager.getPlayer(hold.id)
            if (player == null) gone.add(hold.id) else pin(server, player, hold.mark, HOLD_DRIFT)
        }
        for (id in gone) holds.remove(id)

        tickGroupHold(server, now)

        // Nachzügler: nach READY oder spätestens nach der Frist.
        for (hold in holds.values.filter { it.late }) {
            if (hold.id !in ready && now - hold.joinedAt < LATE_READY_TIMEOUT) continue
            val player = server.playerManager.getPlayer(hold.id) ?: continue
            startLate(player)
        }

        group?.let { tickScene(server, it) }
        for (scene in lateScenes.values.toList()) tickScene(server, scene)
    }

    private fun tickGroupHold(server: MinecraftServer, now: Int) {
        val groupHolds = holds.values.filter { !it.late }
        if (groupHolds.isEmpty()) return
        // Rückfall: Host meldet nie READY (oder ist nicht unter den Haltenden) → Frist beginnt trotzdem.
        val since = if (hostJoinedAt >= 0) hostJoinedAt else groupHolds.minOf { it.joinedAt }
        if (deadline < 0 && now - since >= HOST_READY_FALLBACK) {
            deadline = now + waitTicks()
            Hha.LOGGER.info("RPG-Ankunft: kein READY vom Host, Frist beginnt trotzdem")
        }
        val waiting = waitingIds(server)
        if (waiting.isEmpty()) {
            startGroup(server, "alle bereit")
            return
        }
        if (deadline in 0..now) {
            val unready = groupHolds.any { it.id !in ready }
            if (deadlineByHost && !extended && unready) {
                extended = true
                deadline = now + EXTEND_TICKS
                RpgServer.markAllDirty()
                return
            }
            startGroup(server, "Frist um, fehlt: ${waiting.size}")
        }
    }

    /** Hält [player] auf [mark]: zurücksetzen, wenn weiter als [drift] weg, Geschwindigkeit null, kein Fall. */
    private fun pin(server: MinecraftServer, player: ServerPlayerEntity, mark: Vec3d, drift: Double) {
        val a = anchor(server)
        val world = server.getWorld(a.world) ?: return
        if (player.entityWorld !== world || player.entityPos.squaredDistanceTo(mark) > drift * drift) {
            world.getChunk(BlockPos.ofFloored(mark))
            player.teleport(world, mark.x, mark.y, mark.z, emptySet(), a.yaw, 0f, false)
        }
        player.velocity = Vec3d.ZERO
        player.velocityDirty = true
        player.fallDistance = 0.0
    }

    // ---------------------------------------------------------------------------------------------
    // Ankunft
    // ---------------------------------------------------------------------------------------------

    private fun startGroup(server: MinecraftServer, reason: String) {
        val state = RpgWorld.of(server)
        val groupHolds = holds.values.filter { !it.late }.sortedBy { it.index }
        val players = groupHolds.mapNotNull { server.playerManager.getPlayer(it.id) }
        val marks = LinkedHashMap<UUID, Vec3d>()
        for (hold in groupHolds) marks[hold.id] = hold.mark
        for (hold in groupHolds) holds.remove(hold.id)
        ready.clear()
        deadline = -1
        extended = false
        deadlineByHost = false
        hostJoinedAt = -1
        Hha.LOGGER.info("RPG-Ankunft beginnt ({}): {} Spieler", reason, players.size)
        ensureArrived(server)
        dropped.clear()
        if (players.isEmpty()) return
        place(server, players, marks)
        playScene(server, players, marks, late = false)
    }

    /** Nachzügler oder Rückfall eines unbekannten Wegs: eigene Marke, Gepäck, Rast, kurze Szene `rpg.arrival.late`. */
    fun startLate(player: ServerPlayerEntity) {
        val server = player.entityWorld.server ?: return
        bind(server)
        val state = RpgWorld.of(server)
        val hold = holds.remove(player.uuid)
        ready.remove(player.uuid)
        ensureArrived(server)
        val mark = hold?.mark ?: groundedMark(server, state.startedCount())
        val marks = mapOf(player.uuid to mark)
        place(server, listOf(player), marks)
        playScene(server, listOf(player), marks, late = true)
    }

    /** Die Welt gilt ab jetzt als angekommen (einmal): Startzeit, Beginn des Wegs, Ankunftsort als besucht. */
    private fun ensureArrived(server: MinecraftServer) {
        val state = RpgWorld.of(server)
        if (state.arrived) return
        state.arrived = true
        state.startedAt = server.overworld.time
        RpgServer.onPathBegin(server, state.path)
        RpgStory.markArrivalPlace(server)
    }

    /** Fester Zustand vor der Szene: Start gemerkt, Gepäck, Rast an der Marke (ohne Meldung), auf der Marke. */
    private fun place(server: MinecraftServer, players: List<ServerPlayerEntity>, marks: Map<UUID, Vec3d>) {
        val state = RpgWorld.of(server)
        val a = anchor(server)
        val world = server.getWorld(a.world) ?: return
        for (player in players) {
            val mark = marks[player.uuid] ?: continue
            state.markStarted(player.uuid)
            pin(server, player, mark, 0.0)
            RpgKit.give(player)
            RpgRests.setRest(player, RpgRests.arrivalKey(state.path), mark, a.yaw, world, notify = false)
        }
    }

    private fun stageIdFor(server: MinecraftServer, late: Boolean): String {
        if (late) return LATE_STAGE
        val state = RpgWorld.of(server)
        return HhaRpg.path(state.path)?.arrivalStage ?: "rpg.arrival.${state.path}"
    }

    private fun playScene(server: MinecraftServer, players: List<ServerPlayerEntity>, marks: Map<UUID, Vec3d>, late: Boolean) {
        val id = stageIdFor(server, late)
        val length = if (StageScripts.get(id) != null) StageScripts.length(id) else 0
        val a = anchor(server)
        val scene: Scene
        if (length > 0) {
            Cine.start(players)
            Cine.stage(players, id, a.pos, a.yaw, players)
            scene = Scene(players.map { it.uuid }.toMutableList(), marks, id, true, length, late)
        } else {
            // Rückfall: kein Skript (P17 fehlt oder Addon-Weg ohne Szene) → Titel wie vor Überarbeitung 3.
            val path = RpgWorld.of(server).path
            for (player in players) RpgServer.legacyTitle(player, path, FALLBACK_TITLE_TICKS)
            scene = Scene(players.map { it.uuid }.toMutableList(), marks, id, false, FALLBACK_TITLE_TICKS, late)
        }
        if (late) {
            for (player in players) lateScenes[player.uuid] = scene
        } else {
            group = scene
        }
        RpgServer.markAllDirty()
    }

    private fun tickScene(server: MinecraftServer, scene: Scene) {
        val players = online(server, scene.players)
        scene.players.retainAll(players.map { it.uuid }.toSet())
        if (players.isEmpty()) {
            finishScene(server, scene, skipped = false)
            return
        }
        for (player in players) scene.marks[player.uuid]?.let { pin(server, player, it, SCENE_DRIFT) }
        if (scene.skip.pending) {
            finishScene(server, scene, skipped = true)
            return
        }
        if (--scene.left <= 0) finishScene(server, scene, skipped = false)
    }

    /**
     * Ende der Szene, natürlich oder **übersprungen**: Darauf folgt immer derselbe feste Endzustand (PLAN 3.4): alle
     * auf ihrer Marke, Kamera frei (`OP_STAGE_STOP`/`OP_END`; Kostüm und Puppen räumt der Client damit ab), Gepäck
     * vergeben (schon in [place]), Unverwundbarkeit vorbei (die Szene ist aus der Liste), `phase = PLAY`, volle
     * Heilung an der Rast.
     */
    private fun finishScene(server: MinecraftServer, scene: Scene, skipped: Boolean) {
        if (scene.late) {
            lateScenes.values.removeIf { it === scene }
        } else if (group === scene) {
            group = null
        }
        val players = online(server, scene.players)
        if (scene.scripted && players.isNotEmpty()) {
            if (skipped) Cine.stageStop(players)
            Cine.end(players)
            if (skipped) Cine.cut(players)
        }
        val state = RpgWorld.of(server)
        val path = HhaRpg.path(state.path)
        for (player in players) {
            scene.marks[player.uuid]?.let { pin(server, player, it, SCENE_DRIFT) }
            RpgRests.restHere(player)
            path?.let { p -> runCatching { p.start(player) }.onFailure { Hha.LOGGER.error("RPG-Weg {}: start für {} fehlgeschlagen", p.id, player.name.string, it) } }
        }
        RpgServer.markAllDirty()
        if (!scene.late && players.isNotEmpty()) {
            for (listener in arrivedListeners) {
                try {
                    listener(server, players)
                } catch (e: Exception) {
                    Hha.LOGGER.error("RpgArrival.onArrived: Lauscher fehlgeschlagen", e)
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Hilfen
    // ---------------------------------------------------------------------------------------------

    private fun sceneOf(id: UUID): Scene? = group?.takeIf { id in it.players } ?: lateScenes[id]

    private fun online(server: MinecraftServer, ids: Collection<UUID>): List<ServerPlayerEntity> =
        ids.mapNotNull { server.playerManager.getPlayer(it) }.filter { !it.isDisconnected }

    private fun ground(world: ServerWorld, pos: Vec3d): Vec3d = RpgRests.ground(world, pos, GROUND_UP, GROUND_DOWN)
}
