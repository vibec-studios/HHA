package dev.henny.hha.client.rpg

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/**
 * Bildschirm zwischen „Aufbrechen“ und den Lade-/Verbindungsbildschirmen: zeigt nur die Reise ([RpgJourney.draw]).
 * Esc bricht die Reise bewusst ab (Rückfall in die Lobby).
 */
class RpgTransitionScreen : Screen(Text.translatable("hha.rpg.title")) {
    override fun shouldPause(): Boolean = false

    override fun close() {
        RpgJourney.cancel()
    }

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {}

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        if (!RpgJourney.active) {
            // Sollte nicht vorkommen (die Reise öffnet sonst die Lobby); nie ein stehendes Schwarzbild.
            client?.setScreen(RpgLobbyScreen(net.minecraft.client.gui.screen.TitleScreen()))
            return
        }
        RpgJourney.draw(context, width, height, mouseX, mouseY)
    }
}
