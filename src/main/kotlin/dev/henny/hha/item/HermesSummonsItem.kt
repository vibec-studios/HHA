package dev.henny.hha.item

import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.Item
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.world.World

/**
 * Der Botenbrief (Überarbeitung 2, PLAN 3): Hermes wird nicht mehr herbeigerufen, er wartet im Olymp. Rechtsklick zeigt
 * die Bauanleitung des Wolkentors (Glowstone-Rahmen plus Wassereimer). Das Rezept entfällt; das Item bleibt registriert.
 */
class HermesSummonsItem(settings: Settings) : Item(settings) {

    override fun use(world: World, user: PlayerEntity, hand: Hand): ActionResult {
        if (!world.isClient) user.sendMessage(Text.translatable("hha.olympus.portal_hint").formatted(Formatting.GOLD, Formatting.ITALIC), false)
        return ActionResult.SUCCESS
    }
}
