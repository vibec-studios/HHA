package dev.henny.hha.client.rpg

import dev.henny.hha.client.rpg.tunnel.TunnelRenderer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

/**
 * Client-Seite des RPG-Modus: Knopf im Hauptmenü, Tunnelbühne (GUI-Sonderelement), Reise über allen Ladebildschirmen,
 * Ping, Join/Disconnect-Hooks. [init] ruft `HhaClient.onInitializeClient` (Client-Start, also früh genug für die
 * Registrierung des Sonderelements).
 */
object RpgClient {

    private var pingTicks = 0

    fun init() {
        // Tunnelbühne als GUI-Sonderelement; muss vor dem Aufbau des GuiRenderer registriert sein.
        SpecialGuiElementRegistry.register { context -> TunnelRenderer(context.vertexConsumers()) }

        ScreenEvents.AFTER_INIT.register { client, screen, width, height ->
            if (screen is TitleScreen) {
                // Rechts neben „Einzelspieler“: der Einstieg in den RPG-Modus
                Screens.getButtons(screen).add(
                    ButtonWidget.builder(Text.translatable("hha.rpg.button")) { client.setScreen(RpgLobbyScreen(screen)) }
                        .dimensions(width / 2 + 104, height / 4 + 48, 72, 20)
                        .tooltip(net.minecraft.client.gui.tooltip.Tooltip.of(Text.translatable("hha.rpg.button.tooltip")))
                        .build(),
                )
            }
            // Fehler- und Titelbildschirm beenden die Reise sichtbar (nie liegt die Animation über einer Fehlermeldung)
            RpgJourney.onScreen(screen)
            if (RpgJourney.active && RpgScreens.isLoadingScreen(screen)) {
                // Die ersetzten Bildschirme sind unsichtbar: keine versehentlichen Klicks (z. B. auf „Abbrechen“)
                ScreenMouseEvents.allowMouseClick(screen).register { _, _ -> !RpgJourney.active }
                if (screen is ConnectScreen) {
                    // Esc bricht bewusst ab (Rückfall in die Lobby)
                    ScreenKeyboardEvents.allowKeyPress(screen).register { _, input ->
                        if (RpgJourney.active && input.key() == GLFW.GLFW_KEY_ESCAPE) {
                            RpgJourney.cancel(input)
                            false
                        } else {
                            true
                        }
                    }
                }
            }
        }
        WorldRenderEvents.END_MAIN.register { _ -> RpgJourney.onWorldFrame() }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            RpgJourney.tick(client)
            // Cloudflare trennt stille WebSockets nach etwa 100 s: in der Lobby alle 30 s ein Lebenszeichen
            if (++pingTicks >= 600) {
                pingTicks = 0
                if (RpgNet.status == RpgNet.Status.ONLINE) RpgNet.send("ping")
            }
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, client -> client.execute { RpgJourney.onJoined(client) } }
        ClientPlayConnectionEvents.DISCONNECT.register { _, client -> client.execute { RpgJourney.onDisconnected() } }
    }
}
