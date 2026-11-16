package dev.henny.hha.client.rpg

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/**
 * Bildschirm zwischen „Start“ und dem Laden der Welt: zeigt nur die Startanimation. Die folgenden Lade- und
 * Verbindungsbildschirme von Minecraft bekommen dieselbe Animation darübergelegt ([RpgClient]), damit kein Bruch
 * entsteht.
 */
class RpgTransitionScreen : Screen(Text.translatable("hha.rpg.title")) {
    override fun shouldCloseOnEsc(): Boolean = false
    override fun shouldPause(): Boolean = false

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {}

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        RpgLaunch.drawOverlay(context, width, height)
    }
}
