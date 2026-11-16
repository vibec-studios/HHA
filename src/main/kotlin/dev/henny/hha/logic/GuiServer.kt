package dev.henny.hha.logic

import dev.henny.hha.api.set.ArmorSet
import dev.henny.hha.api.set.HhaSets
import dev.henny.hha.config.CustomRecipePack
import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.boss.HadesFights
import dev.henny.hha.logic.boss.HermesFights
import dev.henny.hha.net.GuiActionPayload
import dev.henny.hha.net.GuiKit
import dev.henny.hha.net.GuiRequestPayload
import dev.henny.hha.net.GuiStatePayload
import dev.henny.hha.net.GuiTrusted
import dev.henny.hha.net.HhaNetworking
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.EquipmentSlot
import net.minecraft.registry.Registries
import net.minecraft.server.command.CommandManager
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import java.util.UUID

/**
 * Serverseite des Kontrollzentrums (`client/gui/HhaMenuScreen`): schickt den Stand und führt Aktionen aus.
 * Alles, was Config oder Kämpfe ändert, braucht dieselben Rechte wie `/hha` (OP-Stufe 2); Trust und Kits stehen
 * jedem offen, genau wie `/trust` und `/hha kit` (Kits mit `kit_mode` oder im Kreativmodus, siehe [Kits.allowed]).
 */
object GuiServer {

    private val ADMIN_ACTIONS = setOf(
        "toggle", "set", "hermes_start", "hermes_stop", "hermes_skip", "hades_stop",
        "recipes_reload", "config_save", "config_reload", "config_reset",
    )

    fun init() {
        ServerPlayNetworking.registerGlobalReceiver(GuiRequestPayload.ID) { _, context ->
            val player = context.player()
            ServerPlayNetworking.send(player, state(player))
        }
        ServerPlayNetworking.registerGlobalReceiver(GuiActionPayload.ID) { payload, context ->
            handle(context.player(), payload)
        }
    }

    fun isAdmin(player: ServerPlayerEntity): Boolean = CommandManager.GAMEMASTERS_CHECK.allows(player.permissions)

    private fun setKey(set: ArmorSet): String =
        if (set.id.namespace == "hha") set.id.path else "${set.id.namespace}.${set.id.path}"

    private fun itemId(item: net.minecraft.item.Item?): String = item?.let { Registries.ITEM.getId(it).toString() } ?: ""

    fun state(player: ServerPlayerEntity, message: String = "", arg: String = "", ok: Boolean = true): GuiStatePayload {
        val server = player.entityWorld.server
        val toggles = LinkedHashMap<String, Boolean>()
        HhaConfig.TOGGLE_DEFAULTS.keys.forEach { toggles[it] = HhaConfig.enabled(it) }
        val numbers = LinkedHashMap<String, Double>()
        HhaConfig.NUMBER_DEFAULTS.keys.forEach { numbers[it] = HhaConfig.num(it) }
        val trusted = Trust.listFor(player.uuid).map { uuid ->
            val online = server?.playerManager?.getPlayer(uuid)
            GuiTrusted(uuid.toString(), online?.gameProfile?.name ?: uuid.toString().substring(0, 8), online != null)
        }.sortedBy { it.name.lowercase() }
        val kits = ArrayList<GuiKit>()
        // Wie `/hha kit`: mit `kit_mode` für alle, im Kreativmodus immer (PLAN 6.3)
        if (Kits.allowed(player)) {
            for (set in HhaSets.all()) {
                kits.add(GuiKit(setKey(set), itemId(set.piece(EquipmentSlot.CHEST)), itemId(set.weapons.firstOrNull())))
            }
            for (kit in Kits.VanillaKit.entries) {
                kits.add(GuiKit(kit.key, itemId(kit.armor[EquipmentSlot.CHEST]), itemId(kit.sword)))
            }
        }
        val world = player.entityWorld as? ServerWorld
        var fights = 0
        if (world != null && HermesFights.of(world) != null) fights = fights or 1
        if (world != null && HadesFights.of(world) != null) fights = fights or 2
        return GuiStatePayload(
            isAdmin(player), toggles, HhaConfig.TOGGLE_DEFAULTS, numbers, HhaConfig.NUMBER_DEFAULTS,
            trusted, kits, fights, message, arg, ok,
        )
    }

    private fun reply(player: ServerPlayerEntity, message: String, arg: String = "", ok: Boolean = true) {
        ServerPlayNetworking.send(player, state(player, message, arg, ok))
    }

    private fun handle(player: ServerPlayerEntity, payload: GuiActionPayload) {
        val server = player.entityWorld.server ?: return
        val action = payload.action
        if (action in ADMIN_ACTIONS && !isAdmin(player)) {
            reply(player, "hha.gui.msg.no_permission", ok = false)
            return
        }
        val world = player.entityWorld as? ServerWorld
        when (action) {
            "toggle" -> {
                val value = payload.value.toBooleanStrictOrNull()
                if (value == null || !HhaConfig.setToggle(payload.key, value)) {
                    reply(player, "hha.gui.msg.unknown_key", payload.key, false)
                    return
                }
                // Rezept-Schalter greifen erst nach einem Datapack-Reload, genau wie bei /hha toggle.
                if (payload.key.endsWith("_recipe") || payload.key == "relic_crafting") {
                    server.commandManager.parseAndExecute(server.commandSource, "reload")
                }
                reply(player, if (value) "hha.gui.msg.toggled_on" else "hha.gui.msg.toggled_off", payload.key)
            }
            "set" -> {
                val value = payload.value.toDoubleOrNull()?.takeIf { it.isFinite() }
                val limit = maxOf(10000.0, (HhaConfig.NUMBER_DEFAULTS[payload.key] ?: 0.0) * 4)
                if (value == null || !HhaConfig.setNumber(payload.key, value.coerceIn(0.0, limit))) {
                    reply(player, "hha.gui.msg.unknown_key", payload.key, false)
                    return
                }
                HhaNetworking.syncConfig(server)
                reply(player, "hha.gui.msg.value_set", payload.key)
            }
            "trust_add" -> {
                val target = parseUuid(payload.key)?.let { server.playerManager.getPlayer(it) }
                when {
                    target == null -> reply(player, "hha.gui.msg.player_offline", ok = false)
                    target == player -> reply(player, "hha.msg.trust_self", ok = false)
                    Trust.add(player.uuid, target.uuid) -> reply(player, "hha.msg.trust_added", target.gameProfile.name)
                    else -> reply(player, "hha.msg.trust_already", target.gameProfile.name, false)
                }
            }
            "trust_remove" -> {
                val uuid = parseUuid(payload.key)
                val name = payload.value.ifEmpty { payload.key.take(8) }
                if (uuid != null && Trust.remove(player.uuid, uuid)) reply(player, "hha.msg.trust_removed", name)
                else reply(player, "hha.msg.trust_not_found", name, false)
            }
            "kit" -> giveKit(player, payload.key)
            "hermes_start" -> {
                val started = HermesFights.start(player)
                reply(player, if (started) "hha.gui.msg.hermes_started" else "hha.gui.msg.hermes_failed", ok = started)
            }
            "hermes_stop" -> {
                val stopped = world != null && HermesFights.stop(world)
                reply(player, if (stopped) "hha.gui.msg.fight_stopped" else "hha.gui.msg.no_fight", ok = stopped)
            }
            "hermes_skip" -> {
                val fight = world?.let { HermesFights.of(it) }
                fight?.forceNextStage()
                reply(player, if (fight != null) "hha.gui.msg.stage_skipped" else "hha.gui.msg.no_fight", ok = fight != null)
            }
            "hades_stop" -> {
                val fight = world?.let { HadesFights.of(it) }
                fight?.finish(false)
                reply(player, if (fight != null) "hha.gui.msg.fight_stopped" else "hha.gui.msg.no_fight", ok = fight != null)
            }
            "recipes_reload" -> {
                CustomRecipePack.resync(server)
                reply(player, "hha.gui.msg.recipes_reloaded")
            }
            "config_save" -> {
                HhaConfig.save()
                reply(player, "hha.gui.msg.config_saved")
            }
            "config_reload" -> {
                HhaConfig.load()
                CustomRecipePack.resync(server)
                HhaNetworking.syncConfig(server)
                reply(player, "hha.gui.msg.config_reloaded")
            }
            "config_reset" -> {
                HhaConfig.reset()
                HhaNetworking.syncConfig(server)
                reply(player, "hha.gui.msg.config_reset")
            }
            else -> reply(player, "hha.gui.msg.unknown_action", action, false)
        }
    }

    private fun parseUuid(text: String): UUID? = try { UUID.fromString(text) } catch (e: IllegalArgumentException) { null }

    private fun giveKit(player: ServerPlayerEntity, key: String) {
        if (!Kits.allowed(player)) {
            reply(player, "hha.msg.kit_disabled", ok = false)
            return
        }
        val vanilla = Kits.VanillaKit.entries.firstOrNull { it.key == key }
        if (vanilla != null) {
            Kits.give(player, vanilla)
            reply(player, "hha.gui.msg.kit_given", key)
            return
        }
        val set = runCatching {
            HhaSets.get(Identifier.of("hha", key))
                ?: if ('.' in key) HhaSets.get(Identifier.of(key.substringBefore('.'), key.substringAfter('.'))) else null
        }.getOrNull()
        if (set == null) {
            reply(player, "hha.gui.msg.unknown_key", key, false)
            return
        }
        Kits.give(player, set)
        reply(player, "hha.gui.msg.kit_given", key)
    }
}
