package dev.henny.hha.client.rpg

import dev.henny.hha.client.rpg.game.RpgDeath
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.MessageScreen
import net.minecraft.client.gui.screen.ProgressScreen
import net.minecraft.client.gui.screen.ReconfiguringScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen
import net.minecraft.client.gui.screen.world.LevelLoadingScreen

/**
 * Ersatz-Zeichnung für Vanilla-Lade- und Verbindungsbildschirme während der RPG-Reise (PLAN 4.3, STAND 2.3). Das Mixin
 * an `Screen.renderWithTooltip` (HEAD, abbrechbar) fragt [replaceRender]; die Logik der Bildschirme läuft weiter (sie
 * schließen sich selbst, das „geladen“-Signal sendet der Netzwerk-Handler). Fehler- und Titelbildschirme werden nie
 * ersetzt: Nie liegt die Animation über einer Fehlermeldung (PLAN 5).
 */
object RpgScreens {

    /** Die Bildschirme, die während der Reise nichts zeigen (ihre Logik läuft). */
    fun isLoadingScreen(screen: Screen?): Boolean =
        screen is MessageScreen || screen is ProgressScreen || screen is LevelLoadingScreen || screen is ConnectScreen ||
            screen is ReconfiguringScreen

    /** true = Vanilla zeichnet [screen] in diesem Bild nicht (es wurde hier gezeichnet). Ohne Reise und Tod false. */
    @JvmStatic
    fun replaceRender(screen: Screen, ctx: DrawContext, mouseX: Int, mouseY: Int, delta: Float): Boolean {
        if (RpgJourney.active && isLoadingScreen(screen)) {
            RpgJourney.draw(ctx, screen.width, screen.height, mouseX, mouseY)
            return true
        }
        if (RpgDeath.active && screen is LevelLoadingScreen) {
            RpgDeath.drawOver(ctx, screen.width, screen.height)
            return true
        }
        return false
    }
}
