package dev.henny.hha.rpg

import dev.henny.hha.Hha
import dev.henny.hha.api.rpg.HhaRpg
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.world.OlympusProgress
import dev.henny.hha.net.RpgEventPayload
import dev.henny.hha.net.RpgPartyPayload
import dev.henny.hha.net.RpgStatePayload
import dev.henny.hha.world.olymp.OlympusLayout
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.WorldProperties
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Serverseite des RPG-Modus: übernimmt die Wahl aus der Lobby in die neue Welt, startet Addon-Wege ohne eigene Ankunft
 * wie bisher und hält jeden Client über den Stand der Geschichte auf dem Laufenden ([RpgStatePayload],
 * [RpgPartyPayload]). Die eingebauten Wege:
 * - `heaven` – Weg des Himmels: Ankunftsinsel des Olymp, von dort Hermes → Brontes → Kratos & Bia → Zeus, danach Akt II.
 * - `hell` – Weg der Unterwelt: Dreiweg der Hekate vor dem offenen Höllentor, dahinter Tartaros, Wächter, Hades, Kronos.
 *
 * Beide haben seit Überarbeitung 3 eine gemeinsame Ankunft ([RpgArrival]); ihr `start` tut deshalb nichts mehr.
 */
object RpgServer {

    /** Zustand an jeden Client spätestens alle so viele Ticks, auch ohne Änderung (PLAN 3.6). */
    const val STATE_RESEND_TICKS = 100

    /** So oft wird der Zustand je Spieler neu berechnet und bei Änderung geschickt. */
    const val STATE_CHECK_TICKS = 20

    /** Gruppenleisten: so oft geprüft, geschickt nur bei Änderung. */
    const val PARTY_TICKS = 10

    /** Addon-Wege ohne Ankunft: Spieler, die gleich ihren Start bekommen (Ticks bis dahin). */
    private val due = LinkedHashMap<UUID, Int>()

    /** Zuletzt an jeden Spieler geschickter Zustand und wann. */
    private val lastState = HashMap<UUID, RpgStatePayload>()
    private val lastStateTick = HashMap<UUID, Int>()

    /** Spieler, deren Zustand im nächsten Tick sofort neu geschickt wird (null-Eintrag = alle). */
    private val dirty = HashSet<UUID>()
    private var allDirty = false

    private var lastParty: RpgPartyPayload? = null

    fun init() {
        HhaRpg.registerPath(
            HhaRpg.Path(
                id = "heaven", addonId = Hha.MOD_ID, titleKey = "hha.rpg.path.heaven", descKey = "hha.rpg.path.heaven.desc",
                color = 0xFFFFD75E.toInt(), start = {}, arrival = RpgArrival::heavenArrival,
            ),
        )
        HhaRpg.registerPath(
            HhaRpg.Path(
                id = "hell", addonId = Hha.MOD_ID, titleKey = "hha.rpg.path.hell", descKey = "hha.rpg.path.hell.desc",
                color = 0xFF3FD8FF.toInt(), start = {}, arrival = RpgArrival::hellArrival,
            ),
        )

        // Läuft vor RpgRules.apply (beide an SERVER_STARTED; RpgServer.init kommt in Hha.onInitialize zuerst).
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            val setup = RpgWorld.pending ?: return@register
            if (server.isDedicated) return@register
            RpgWorld.pending = null
            RpgWorld.of(server).setup(setup.path, setup.addons, setup.code, setup.expected)
            Hha.LOGGER.info("RPG-Welt eingerichtet: Weg {}, Addons {}, erwartet {}", setup.path, setup.addons, setup.expected.size)
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { clear() }
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            val player = handler.player
            val state = RpgWorld.of(server)
            if (!state.enabled) return@register
            markDirty(player)
            allDirty = true
            val path = HhaRpg.path(state.path)
            // Wege mit eigener Ankunft übernimmt RpgArrival (Halte, Szene, Nachzügler).
            if (path?.arrival == null && !state.hasStarted(player.uuid)) due[player.uuid] = 20
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            due.remove(handler.player.uuid)
            lastState.remove(handler.player.uuid)
            lastStateTick.remove(handler.player.uuid)
            allDirty = true
        }
        ServerTickEvents.END_SERVER_TICK.register(::tick)
    }

    private fun clear() {
        due.clear()
        lastState.clear()
        lastStateTick.clear()
        dirty.clear()
        allDirty = false
        lastParty = null
    }

    // ---------------------------------------------------------------------------------------------
    // Zustand zum Client
    // ---------------------------------------------------------------------------------------------

    /** Der Zustand von [player] hat sich geändert: im nächsten Tick neu schicken. */
    fun markDirty(player: ServerPlayerEntity) {
        dirty.add(player.uuid)
    }

    /** Der Zustand aller hat sich geändert (Kapitel, Tagebuch, Halte …). */
    fun markAllDirty() {
        allDirty = true
    }

    /** Ein Ereignis an [players] (nur in RPG-Welten sinnvoll; die Aufrufer prüfen den Schalter). */
    fun event(players: Iterable<ServerPlayerEntity>, kind: Int, numbers: List<Double> = emptyList(), texts: List<String> = emptyList()) {
        val payload = RpgEventPayload(kind, numbers, texts)
        for (player in players) {
            if (player.isDisconnected) continue
            ServerPlayNetworking.send(player, payload)
        }
    }

    /** Der Zustand, den [player] gerade sehen soll. */
    fun stateFor(server: MinecraftServer, player: ServerPlayerEntity): RpgStatePayload {
        val state = RpgWorld.of(server)
        if (!state.enabled) return RpgStatePayload.DISABLED
        val chapters = RpgStory.chapters(state.path)
        val chapter = RpgStory.current(server)
        val goal = RpgStory.goalFor(server, player)
        val target = goal?.target
        return RpgStatePayload(
            enabled = true,
            path = state.path,
            phase = RpgArrival.phaseOf(player),
            chapter = chapter.id,
            chapterIndex = chapters.indexOfFirst { it.id == chapter.id }.coerceAtLeast(0),
            objectiveKey = goal?.key ?: "",
            objectiveArgs = goal?.args ?: emptyList(),
            hasTarget = target != null,
            targetWorld = target?.world?.value?.toString() ?: "",
            tx = target?.pos?.x ?: 0.0,
            ty = target?.pos?.y ?: 0.0,
            tz = target?.pos?.z ?: 0.0,
            waiting = RpgArrival.waitingNames(server),
            done = chapters.filter { state.isChapterDone(it.id) }.map { it.id },
            journal = state.journal,
            restKey = state.restOf(player.uuid)?.key ?: "",
        )
    }

    private fun tick(server: MinecraftServer) {
        tickDue(server)
        if (!RpgWorld.of(server).enabled) return
        val now = server.ticks
        for (player in server.playerManager.playerList) {
            val id = player.uuid
            val forced = allDirty || id in dirty
            val last = lastStateTick[id]
            val stale = last == null || now - last >= STATE_RESEND_TICKS
            if (!forced && !stale && Math.floorMod(now + id.hashCode(), STATE_CHECK_TICKS) != 0) continue
            val payload = stateFor(server, player)
            if (!forced && !stale && payload == lastState[id]) continue
            ServerPlayNetworking.send(player, payload)
            lastState[id] = payload
            lastStateTick[id] = now
        }
        dirty.clear()
        allDirty = false
        if (now % PARTY_TICKS == 0) sendParty(server)
    }

    /** Gruppenleisten an alle, wenn sich etwas geändert hat (Lage auf halbe Blöcke gerundet, damit Wippen nicht zählt). */
    private fun sendParty(server: MinecraftServer) {
        val players = server.playerManager.playerList
        if (players.isEmpty()) return
        val payload = RpgPartyPayload(
            players.map {
                RpgPartyPayload.Member(
                    it.uuid.toString(), it.gameProfile.name, (it.health * 2f).roundToInt() / 2f, it.maxHealth,
                    !it.isAlive || it.isDead, it.entityWorld.registryKey.value.toString(),
                    half(it.x), half(it.y), half(it.z),
                )
            },
        )
        if (payload == lastParty) return
        lastParty = payload
        for (player in players) ServerPlayNetworking.send(player, payload)
    }

    private fun half(v: Double): Double = Math.round(v * 2.0) / 2.0

    // ---------------------------------------------------------------------------------------------
    // Addon-Wege ohne Ankunft (bisheriges Verhalten)
    // ---------------------------------------------------------------------------------------------

    private fun tickDue(server: MinecraftServer) {
        if (due.isEmpty()) return
        val iterator = due.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.setValue(entry.value - 1)
            if (entry.value > 0) continue
            iterator.remove()
            val player = server.playerManager.getPlayer(entry.key) ?: continue
            val state = RpgWorld.of(server)
            if (!state.enabled || state.hasStarted(player.uuid)) continue
            state.markStarted(player.uuid)
            val path = HhaRpg.path(state.path) ?: HhaRpg.path("heaven") ?: continue
            if (path.arrival != null) {
                // Unbekannter Weg der Welt (Addon fehlt) → Rückfall auf den Himmel, der eine Ankunft hat.
                RpgArrival.startLate(player)
                continue
            }
            // Weg ohne eigene Ankunft: wie vor Überarbeitung 3 (kein Pilger-Gepäck, der Weg gibt selbst aus).
            runCatching { path.start(player) }.onFailure { Hha.LOGGER.error("RPG-Start für {} fehlgeschlagen", player.name.string, it) }
            markDirty(player)
        }
    }

    /** Der Weg der Welt beginnt (einmal bei der Ankunft): Unterwelt → Hekate gilt als besiegt (wie bisher). */
    fun onPathBegin(server: MinecraftServer, path: String) {
        if (path == "hell") OlympusProgress.markDone(server, OlympusProgress.HEKATE)
    }

    /**
     * Rückfall-Teleport an den Anfang des Wegs (ohne Rast): Kurz Sanfter Fall und Widerstand, falls der Bauplan noch
     * lädt. Wird gebraucht, wenn ein Spieler keine Rast hat (alte Welten).
     */
    fun toPathStart(player: ServerPlayerEntity) {
        val server = player.entityWorld.server ?: return
        val a = RpgArrival.anchor(server)
        val world = server.getWorld(a.world) ?: return
        teleportSafe(player, world, a.pos, a.yaw)
    }

    /** Teleport mit Schonfrist; Chunk wird vorher geladen. */
    fun teleportSafe(player: ServerPlayerEntity, world: ServerWorld, at: Vec3d, yaw: Float) {
        world.getChunk(BlockPos.ofFloored(at))
        player.teleport(world, at.x, at.y + 0.05, at.z, emptySet(), yaw, 0f, false)
        player.fallDistance = 0.0
        player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOW_FALLING, 60, 0, false, false))
    }

    /** Erzwungener Wiedereinstiegspunkt (ohne Bett) bei [at]. */
    fun forceSpawn(player: ServerPlayerEntity, world: ServerWorld, at: Vec3d, yaw: Float) {
        val spawn = WorldProperties.SpawnPoint.create(world.registryKey, BlockPos.ofFloored(at), yaw, 0f)
        player.setSpawnPoint(ServerPlayerEntity.Respawn(spawn, true), false)
    }

    /** Titel wie vor Überarbeitung 3 (Rückfall, wenn ein Ankunfts-Skript fehlt). */
    fun legacyTitle(player: ServerPlayerEntity, path: String, ticks: Int) {
        val key = if (path == "hell") "hell" else "heaven"
        Cine.title(listOf(player), "hha.rpg.start.$key", "hha.rpg.start.$key.sub", ticks)
    }

    /** Für [OlympusLayout]-Wege: liegt [player] im Olymp? */
    fun inOlympus(player: ServerPlayerEntity): Boolean = player.entityWorld.registryKey == OlympusLayout.WORLD
}
