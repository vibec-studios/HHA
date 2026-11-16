package dev.henny.hha.rpg

import dev.henny.hha.Hha
import dev.henny.hha.api.rpg.HhaRpg
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.world.OlympusProgress
import dev.henny.hha.world.olymp.OlympusLayout
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import net.minecraft.world.WorldProperties
import java.util.UUID

/**
 * Serverseite des RPG-Modus: übernimmt die Wahl aus der Lobby in die neue Welt und schickt jeden Spieler beim ersten
 * Betreten an den Anfang seines Wegs. Die eingebauten Wege:
 * - `heaven` – Weg des Himmels: Ankunftsinsel des Olymp, von dort Hermes → Brontes → Kratos & Bia → Zeus.
 * - `hell` – Weg der Unterwelt: Dreiweg der Hekate vor dem offenen Höllentor, dahinter Tartaros, Wächter, Hades, Kronos.
 */
object RpgServer {

    /** Spieler, die im nächsten Tick ihren Start bekommen (ein Tick Abstand, damit die Welt fertig geladen ist). */
    private val due = LinkedHashMap<UUID, Int>()

    fun init() {
        HhaRpg.registerPath(HhaRpg.Path("heaven", Hha.MOD_ID, "hha.rpg.path.heaven", "hha.rpg.path.heaven.desc", 0xFFFFD75E.toInt(), ::startHeaven))
        HhaRpg.registerPath(HhaRpg.Path("hell", Hha.MOD_ID, "hha.rpg.path.hell", "hha.rpg.path.hell.desc", 0xFF3FD8FF.toInt(), ::startHell))

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            val setup = RpgWorld.pending ?: return@register
            if (server.isDedicated) return@register
            RpgWorld.pending = null
            RpgWorld.of(server).setup(setup.path, setup.addons, setup.code)
            // Kein Schummeln in der Gruppenwelt, aber Wetter/Tageszeit wie gewohnt.
            Hha.LOGGER.info("RPG-Welt eingerichtet: Weg {}, Addons {}", setup.path, setup.addons)
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { due.clear() }
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            val player = handler.player
            val state = RpgWorld.of(server)
            if (state.enabled && !state.hasStarted(player.uuid)) due[player.uuid] = 20
        }
        ServerTickEvents.END_SERVER_TICK.register(::tick)
        // Tod im RPG-Modus: zurück an den Anfang des Wegs statt an den Weltspawn der Oberwelt. Normal erledigt das der
        // beim Ankommen gesetzte Wiedereinstiegspunkt; das hier fängt alte Welten und versperrte Punkte ab.
        ServerPlayerEvents.AFTER_RESPAWN.register { _, player, alive ->
            if (alive) return@register
            val server = player.entityWorld.server ?: return@register
            val state = RpgWorld.of(server)
            if (!state.enabled || !state.hasStarted(player.uuid)) return@register
            if (player.respawn != null || player.entityWorld.registryKey != World.OVERWORLD) return@register
            respawnAtPath(player, state.path)
        }
    }

    /** Spieler ohne eigenes Bett/Anker an den Anfang seines Wegs bringen (eingebaute Wege). */
    private fun respawnAtPath(player: ServerPlayerEntity, path: String) {
        val server = player.entityWorld.server ?: return
        val olympus = server.getWorld(OlympusLayout.WORLD) ?: return
        when (path) {
            "heaven" -> arrive(player, olympus, OlympusLayout.ARRIVAL_SPOT.add(0.5, 0.0, 0.5), OlympusLayout.ARRIVAL_YAW)
            "hell" -> arrive(player, olympus, hellStart(), 90f)
        }
    }

    private fun hellStart(): Vec3d {
        val gate = OlympusLayout.HELL_GATE
        return Vec3d(gate.x + 6.5, gate.y.toDouble(), gate.z + 0.5)
    }

    private fun tick(server: MinecraftServer) {
        if (due.isEmpty()) return
        val iterator = due.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.setValue(entry.value - 1)
            if (entry.value > 0) continue
            iterator.remove()
            val player = server.playerManager.getPlayer(entry.key) ?: continue
            val state = RpgWorld.of(server)
            if (state.hasStarted(player.uuid)) continue
            state.markStarted(player.uuid)
            val path = HhaRpg.path(state.path) ?: HhaRpg.path("heaven") ?: continue
            runCatching { path.start(player) }.onFailure { Hha.LOGGER.error("RPG-Start für {} fehlgeschlagen", player.name.string, it) }
        }
    }

    private fun startHeaven(player: ServerPlayerEntity) {
        val server = player.entityWorld.server ?: return
        val olympus = server.getWorld(OlympusLayout.WORLD) ?: return
        arrive(player, olympus, OlympusLayout.ARRIVAL_SPOT.add(0.5, 0.0, 0.5), OlympusLayout.ARRIVAL_YAW)
        Cine.title(listOf(player), "hha.rpg.start.heaven", "hha.rpg.start.heaven.sub", 80)
    }

    private fun startHell(player: ServerPlayerEntity) {
        val server = player.entityWorld.server ?: return
        val olympus = server.getWorld(OlympusLayout.WORLD) ?: return
        // Der Weg der Unterwelt beginnt vor dem offenen Höllentor: Hekate gilt als besiegt.
        OlympusProgress.markDone(server, OlympusProgress.HEKATE)
        arrive(player, olympus, hellStart(), 90f)
        Cine.title(listOf(player), "hha.rpg.start.hell", "hha.rpg.start.hell.sub", 80)
    }

    /**
     * Teleport mit kurzer Schonfrist (Sanfter Fall, Widerstand), falls der Bauplan beim Ankommen noch lädt. Setzt
     * zugleich den Wiedereinstiegspunkt dorthin (erzwungen, also ohne Bett), solange der Spieler keinen eigenen hat.
     */
    private fun arrive(player: ServerPlayerEntity, world: ServerWorld, at: Vec3d, yaw: Float) {
        world.getChunk(at.x.toInt() shr 4, at.z.toInt() shr 4)
        if (player.respawn == null || player.respawn?.forced == true) {
            val spawn = WorldProperties.SpawnPoint.create(world.registryKey, BlockPos.ofFloored(at), yaw, 0f)
            player.setSpawnPoint(ServerPlayerEntity.Respawn(spawn, true), false)
        }
        player.teleport(world, at.x, at.y + 0.2, at.z, emptySet(), yaw, 0f, false)
        player.fallDistance = 0.0
        player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOW_FALLING, 200, 0, false, false))
        player.addStatusEffect(StatusEffectInstance(StatusEffects.RESISTANCE, 100, 4, false, false))
    }
}
