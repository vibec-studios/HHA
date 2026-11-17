package dev.henny.hha.client

import dev.henny.hha.Hha
import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaItems
import dev.henny.hha.net.AbilityPayload
import dev.henny.hha.net.UltraHudPayload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.entity.EquipmentSlot
import net.minecraft.util.math.Vec3d
import org.lwjgl.glfw.GLFW

class HhaClient : ClientModInitializer {

    /** „Ability 1–6" in der Reihenfolge der Slots; 4–6 starten unbelegt (für Addons). */
    private lateinit var abilityKeys: List<KeyBinding>
    private var jumpWasPressed = false
    private var airJumpsUsedClient = 0

    /** Erst nach Loslassen der Sprungtaste in der Luft darf der Doppelsprung zünden. */
    private var airJumpArmed = false

    override fun onInitializeClient() {
        HhaClientConfig.load()
        // Kettenphysik des Hells Mace (Item-Modelltyp hha:flail), muss vor dem ersten Laden der Modelle stehen
        dev.henny.hha.client.model.FlailModelRenderer.register()
        HhaClientCommands.register()
        // RPG-Modus: Knopf im Hauptmenü, Lobby, Tunnel, Startanimation
        dev.henny.hha.client.rpg.RpgClient.init()

        EntityRendererRegistry.register(HhaEntities.HEAVENS_MACE) { context ->
            HeavensMaceEntityRenderer(context)
        }

        dev.henny.hha.client.particle.HhaParticleFactories.register()

        dev.henny.hha.client.vfx.VfxLayers.init()
        dev.henny.hha.client.vfx.PortalVfx.init()
        dev.henny.hha.client.model.BossClient.register()
        ClientPlayNetworking.registerGlobalReceiver(dev.henny.hha.net.CinePayload.ID) { payload, _ ->
            dev.henny.hha.client.cine.CinePlayer.handle(payload)
        }
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.END_MAIN.register { context ->
            dev.henny.hha.client.vfx.VfxManager.render(context.consumers())
        }
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register { context, _ ->
            dev.henny.hha.client.vfx.ScreenFx.renderOverlay(context)
        }
        // Überarbeitung 3 (P00): Phase 1 der neuen Effekte und die Statuszeile.
        net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents.AFTER_ENTITIES.register { context ->
            dev.henny.hha.client.vfx.VfxManager.renderSolid(context)
        }
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register { context, _ ->
            HudLine.render(context)
        }
        ClientPlayNetworking.registerGlobalReceiver(dev.henny.hha.net.VfxPayload.ID) { payload, context ->
            val world = context.client().world ?: return@registerGlobalReceiver
            dev.henny.hha.client.vfx.VfxManager.handle(payload, world)
        }

        // Überarbeitung 3 (P00): Effekte per Text-ID, Musik, Bühne, RPG-Zustand und -HUD.
        dev.henny.hha.client.vfx.fx.FxClient.init()
        dev.henny.hha.client.audio.MusicDirector.init()
        dev.henny.hha.client.cine.stage.StagePlayer.init()
        dev.henny.hha.client.rpg.game.RpgClientState.init()
        dev.henny.hha.client.rpg.game.RpgHud.init()
        ClientPlayNetworking.registerGlobalReceiver(dev.henny.hha.net.FxPayload.ID) { payload, context ->
            val world = context.client().world ?: return@registerGlobalReceiver
            dev.henny.hha.client.vfx.fx.FxClient.handle(payload, world)
        }
        ClientPlayNetworking.registerGlobalReceiver(dev.henny.hha.net.RpgStatePayload.ID) { payload, _ ->
            dev.henny.hha.client.rpg.game.RpgClientState.handleState(payload)
        }
        ClientPlayNetworking.registerGlobalReceiver(dev.henny.hha.net.RpgPartyPayload.ID) { payload, _ ->
            dev.henny.hha.client.rpg.game.RpgClientState.handleParty(payload)
        }
        ClientPlayNetworking.registerGlobalReceiver(dev.henny.hha.net.RpgEventPayload.ID) { payload, _ ->
            dev.henny.hha.client.rpg.game.RpgClientState.handleEvent(payload)
        }

        CooldownHud.register()
        HhaTooltips.register()
        dev.henny.hha.client.gui.GuiClient.register()

        loadClientAddons()

        ClientPlayNetworking.registerGlobalReceiver(UltraHudPayload.ID) { payload, _ ->
            CooldownHud.ultraRemainingTicks = payload.remainingTicks
        }
        ClientPlayNetworking.registerGlobalReceiver(dev.henny.hha.net.ConfigSyncPayload.ID) { payload, _ ->
            ClientConfigCache.update(payload.numbers)
        }
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            ClientConfigCache.clear()
            dev.henny.hha.client.vfx.VfxManager.clear()
            dev.henny.hha.client.vfx.SkyMood.reset()
            dev.henny.hha.client.vfx.ScreenFx.reset()
            dev.henny.hha.client.cine.CinePlayer.reset()
            // Überarbeitung 3 (P00)
            dev.henny.hha.client.vfx.fx.FxClient.reset()
            dev.henny.hha.client.vfx.CameraFx.reset()
            dev.henny.hha.client.audio.MusicDirector.reset()
            dev.henny.hha.client.cine.stage.StagePlayer.reset()
            HudLine.reset()
            dev.henny.hha.client.rpg.game.RpgClientState.reset()
            dev.henny.hha.client.rpg.game.RpgHud.reset()
            dev.henny.hha.client.rpg.game.RpgDeath.reset()
        }

        val category = KeyBinding.Category.create(Hha.id("hells_set"))

        val defaultKeys = intArrayOf(
            GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_U,
            GLFW.GLFW_KEY_UNKNOWN, GLFW.GLFW_KEY_UNKNOWN, GLFW.GLFW_KEY_UNKNOWN,
        )
        abilityKeys = defaultKeys.mapIndexed { index, key ->
            KeyBindingHelper.registerKeyBinding(
                KeyBinding("key.hha.ability_${index + 1}", InputUtil.Type.KEYSYM, key, category)
            )
        }

        dev.henny.hha.client.cine.CinePlayer.skipKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.hha.skip_cutscene", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_ENTER, category)
        )
        val menuKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.hha.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, category)
        )
        dev.henny.hha.client.gui.GuiClient.menuKey = menuKey
        // Überarbeitung 3 (P00): Tagebuch im RPG-Modus (Wirkung P16).
        dev.henny.hha.client.rpg.game.RpgHud.journalKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.hha.journal", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, category)
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            CooldownHud.clientTick()
            client.world?.let { dev.henny.hha.client.vfx.VfxManager.tick(it) }
            dev.henny.hha.client.vfx.ScreenFx.tick()
            dev.henny.hha.client.vfx.SkyMood.tick(client.world)
            dev.henny.hha.client.cine.CinePlayer.tick()
            // Überarbeitung 3 (P00)
            client.world?.let { dev.henny.hha.client.vfx.fx.FxClient.tick(it) }
            dev.henny.hha.client.vfx.CameraFx.tick()
            dev.henny.hha.client.audio.MusicDirector.tick(client)
            dev.henny.hha.client.cine.stage.StagePlayer.tick()
            HudLine.tick()
            while (menuKey.wasPressed()) {
                if (client.currentScreen == null) dev.henny.hha.client.gui.GuiClient.open()
            }
            dev.henny.hha.client.gui.GuiClient.tick(client)

            val player = client.player ?: run {
                jumpWasPressed = false
                airJumpsUsedClient = 0
                CooldownHud.ultraRemainingTicks = 0
                return@register
            }

            val payloadIds = intArrayOf(
                AbilityPayload.ABILITY_1, AbilityPayload.ABILITY_2, AbilityPayload.ABILITY_3,
                AbilityPayload.ABILITY_4, AbilityPayload.ABILITY_5, AbilityPayload.ABILITY_6,
            )
            abilityKeys.forEachIndexed { index, key ->
                while (key.wasPressed()) {
                    ClientPlayNetworking.send(AbilityPayload(payloadIds[index]))
                }
            }

            val jumpPressed = client.options.jumpKey.isPressed
            if (player.isOnGround || player.isTouchingWater) {
                airJumpsUsedClient = 0
                airJumpArmed = false
            } else if (!jumpPressed) {
                airJumpArmed = true
            }
            if (jumpPressed && !jumpWasPressed && airJumpArmed &&
                !player.isOnGround && !player.isTouchingWater &&
                !player.abilities.flying && !player.hasVehicle() &&
                airJumpsUsedClient < 2 &&
                (player.getEquippedStack(EquipmentSlot.LEGS).isOf(HhaItems.HEAVEN_LEGGINGS) ||
                    dev.henny.hha.client.cine.CinePlayer.messengerWings)
            ) {
                airJumpsUsedClient++
                val look = player.getRotationVec(1.0f)
                val v = player.velocity
                player.velocity = Vec3d(
                    v.x * 0.9 + look.x * 0.35,
                    0.62,
                    v.z * 0.9 + look.z * 0.35,
                )
                ClientPlayNetworking.send(AbilityPayload(AbilityPayload.AIR_JUMP))
            }
            jumpWasPressed = jumpPressed
        }
    }

    /** Lädt alle Mods mit `"hha_client"`-Entrypoint (Addon-Client-API). */
    private fun loadClientAddons() {
        val containers = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getEntrypointContainers("hha_client", dev.henny.hha.api.client.HhaClientAddon::class.java)
        for (container in containers) {
            val addonId = container.provider.metadata.id
            Hha.LOGGER.info("Lade HHA-Client-Addon: {}", addonId)
            container.entrypoint.onInitializeClient(ClientAddonContextImpl(addonId))
        }
    }

    private class ClientAddonContextImpl(
        override val addonId: String,
    ) : dev.henny.hha.api.client.HhaClientAddonContext {

        override fun registerHudCooldown(
            icon: net.minecraft.util.Identifier,
            accentColor: Int,
            provider: (net.minecraft.entity.player.PlayerEntity) -> net.minecraft.item.ItemStack?,
        ) {
            dev.henny.hha.api.client.HudCooldowns.register(
                dev.henny.hha.api.client.HudCooldowns.Entry(icon, accentColor, provider)
            )
        }
    }
}
