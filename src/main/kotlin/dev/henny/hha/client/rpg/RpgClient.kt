package dev.henny.hha.client.rpg

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.screen.TitleScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/** Client-Seite des RPG-Modus: Knopf im Hauptmenü, Startanimation über allen Ladebildschirmen, Ereignisse. */
object RpgClient {

    private var pingTicks = 0

    fun init() {
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
            // Fehler- und Titelbildschirm beenden den Start sichtbar (sonst läge die Animation über der Fehlermeldung)
            if (RpgLaunch.active && RpgLaunch.phase != RpgLaunch.Phase.PREPARE &&
                (screen is net.minecraft.client.gui.screen.DisconnectedScreen || screen is TitleScreen)
            ) RpgLaunch.abortVisible()
            // Während des Starts liegt über jedem Lade- und Verbindungsbildschirm dieselbe Animation
            if (RpgLaunch.active && screen !is RpgTransitionScreen && screen !is RpgLobbyScreen) {
                ScreenEvents.afterRender(screen).register { s, ctx, _, _, _ -> RpgLaunch.drawOverlay(ctx, s.width, s.height) }
            }
        }
        HudRenderCallback.EVENT.register { ctx, _ ->
            if (RpgLaunch.phase == RpgLaunch.Phase.FADE) RpgLaunch.drawOverlay(ctx, ctx.scaledWindowWidth, ctx.scaledWindowHeight)
        }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            RpgLaunch.tick(client)
            // Cloudflare trennt stille WebSockets nach etwa 100 s: in der Lobby alle 30 s ein Lebenszeichen
            if (++pingTicks >= 600) {
                pingTicks = 0
                if (RpgNet.status == RpgNet.Status.ONLINE) RpgNet.send("ping")
            }
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, client -> client.execute { RpgLaunch.onJoined(client) } }
        ClientPlayConnectionEvents.DISCONNECT.register { _, client -> client.execute { RpgLaunch.onDisconnected() } }
    }
}
