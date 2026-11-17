package dev.henny.hha.rpg

import dev.henny.hha.Hha
import dev.henny.hha.api.rpg.HhaRpg
import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.HadesFights
import dev.henny.hha.logic.boss.HermesFights
import dev.henny.hha.logic.world.TartarosMinibosses
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.world.WorldProperties
import net.minecraft.world.rule.GameRule
import net.minecraft.world.rule.GameRules
import java.util.UUID

/**
 * Regeln der RPG-Welt (PLAN 3.2). Alles nur, wenn `RpgWorld.of(server).enabled`:
 *
 * - [apply] bei `SERVER_STARTED` (nach der Einrichtung durch `RpgServer`): Weltspawn in der Welt des Wegs
 *   (`MinecraftServer.setSpawnPoint`, nur Wege mit `arrival`), Spielregeln ([RULES], Spawn-Radius 0). Bei jedem Start,
 *   also auch für ältere RPG-Welten.
 * - je Tick: Hunger aus (Sättigung jede Sekunde voll, außer mit `rpg_hunger`) und eigene Heilung: [HEAL_DELAY] Ticks
 *   ohne Schaden → [HEAL_AMOUNT] Leben je Sekunde, nicht während eines Bosskampfs ([inFight]) oder einer Szene.
 * - Boss-Stärke: [bossHealthFactor]/[bossDamageFactor] für `BossScaling` (1,0 außerhalb von RPG-Welten).
 */
object RpgRules {

    /** So lange ohne Schaden, bis die eigene Heilung einsetzt (6 s). */
    const val HEAL_DELAY = 120

    /** Heilung je Sekunde (1 Leben = ein halbes Herz). */
    const val HEAL_AMOUNT = 1f

    /** Takt von Hunger und Heilung. */
    const val SECOND = 20

    /** Die Spielregeln der RPG-Welt (Wahrheitswerte). */
    private val RULES: List<Pair<GameRule<Boolean>, Boolean>> = listOf(
        GameRules.ANNOUNCE_ADVANCEMENTS to false,
        GameRules.SHOW_DEATH_MESSAGES to false,
        GameRules.DO_IMMEDIATE_RESPAWN to true,
        GameRules.KEEP_INVENTORY to true,
        GameRules.NATURAL_HEALTH_REGENERATION to false,
        GameRules.LOCATOR_BAR to false,
        GameRules.SPAWN_PHANTOMS to false,
        GameRules.SPAWN_WANDERING_TRADERS to false,
        GameRules.SPAWN_PATROLS to false,
    )

    /** Läuft auf dem (einzigen) Server dieses Prozesses gerade eine RPG-Welt? Für [BossScaling] ohne Server-Bezug. */
    @Volatile
    private var active = false

    /** Letzter Schaden je Spieler (Server-Tick). */
    private val lastHurt = HashMap<UUID, Int>()

    fun init() {
        ServerLifecycleEvents.SERVER_STARTED.register(::apply)
        ServerLifecycleEvents.SERVER_STOPPED.register {
            active = false
            lastHurt.clear()
        }
        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, _, _, taken, _ ->
            val player = entity as? ServerPlayerEntity ?: return@register
            if (taken <= 0f) return@register
            val server = player.entityWorld.server ?: return@register
            if (active) lastHurt[player.uuid] = server.ticks
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> lastHurt.remove(handler.player.uuid) }
        ServerTickEvents.END_SERVER_TICK.register(::tick)
    }

    /** Bei `SERVER_STARTED`: Regeln der RPG-Welt setzen (nur in RPG-Welten). */
    fun apply(server: MinecraftServer) {
        val state = RpgWorld.of(server)
        active = state.enabled
        if (!state.enabled) return
        // Ältere RPG-Welt (vor Überarbeitung 3): Wer schon gestartet ist, ist angekommen – niemand kommt in die Halte.
        if (!state.arrived && state.startedCount() > 0) state.arrived = true

        val path = HhaRpg.path(state.path)
        if (path?.arrival != null) {
            val a = RpgArrival.anchor(server)
            if (server.getWorld(a.world) != null) {
                server.setSpawnPoint(WorldProperties.SpawnPoint.create(a.world, BlockPos.ofFloored(a.pos), a.yaw, 0f))
            } else {
                Hha.LOGGER.warn("RPG: Welt {} des Wegs fehlt, Weltspawn bleibt", a.world.value)
            }
        }
        val rules = server.overworld.gameRules
        for ((rule, value) in RULES) rules.setValue(rule, value, server)
        rules.setValue(GameRules.RESPAWN_RADIUS, 0, server)
        Hha.LOGGER.info("RPG-Regeln gesetzt (Weg {})", state.path)
    }

    /** Zusätzlicher Lebensfaktor der Bosse: `rpg_boss_health` in RPG-Welten, sonst genau 1. */
    fun bossHealthFactor(): Float = if (active) HhaConfig.numF("rpg_boss_health").coerceIn(0.1f, 4f) else 1f

    /** Zusätzlicher Schadensfaktor der Bosse: `rpg_boss_damage` in RPG-Welten, sonst genau 1. */
    fun bossDamageFactor(): Float = if (active) HhaConfig.numF("rpg_boss_damage").coerceIn(0f, 4f) else 1f

    /** Kämpft [player] gerade (Arena, Hermes, Hades/Kronos, Tartaros-Wächter in der Nähe)? */
    fun inFight(player: ServerPlayerEntity): Boolean {
        if (Arenas.of(player) != null) return true
        val world = player.entityWorld as? ServerWorld ?: return false
        if (HermesFights.of(world)?.isParticipant(player) == true) return true
        if (HadesFights.isParticipant(player)) return true
        return TartarosMinibosses.busy(player)
    }

    private fun tick(server: MinecraftServer) {
        if (!active) return
        val now = server.ticks
        if (now % SECOND != 0) return
        val hunger = HhaConfig.enabled("rpg_hunger")
        for (player in server.playerManager.playerList) {
            if (!player.isAlive || player.isSpectator) continue
            if (!hunger) {
                player.hungerManager.foodLevel = 20
                player.hungerManager.saturationLevel = 5f
            }
            if (player.health >= player.maxHealth) continue
            if (now - (lastHurt[player.uuid] ?: Int.MIN_VALUE / 2) < HEAL_DELAY) continue
            if (inFight(player) || Cine.isPlaying(player)) continue
            player.heal(HEAL_AMOUNT)
        }
    }
}
