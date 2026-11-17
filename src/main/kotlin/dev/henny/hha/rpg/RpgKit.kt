package dev.henny.hha.rpg

import dev.henny.hha.config.HhaConfig
import dev.henny.hha.net.RpgEventPayload
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.Enchantments
import net.minecraft.entity.EquipmentSlot
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * Start-Gepäck „Pilger“ (PLAN 3.2), **einmal je Spieler** und Welt (Liste `kit` in [RpgWorld]): Diamantrüstung
 * (Schutz II), Diamantschwert (Schärfe III), Bogen (Stärke III, Unendlichkeit) mit einem Pfeil, Schild, sechs goldene
 * Äpfel, 16 Brot (nur mit `rpg_hunger`), dazu ein Tagebuch-Hinweis im Chat. Rüstung und Schild werden angelegt, wenn
 * der Platz frei ist, sonst ins Inventar gelegt. Reliquien und Fortschritt bleiben unberührt.
 */
object RpgKit {

    const val PROTECTION = 2
    const val SHARPNESS = 3
    const val POWER = 3
    const val APPLES = 6
    const val BREAD = 16

    /** Gibt [player] das Gepäck, falls noch nicht geschehen (nur in RPG-Welten). */
    fun give(player: ServerPlayerEntity) {
        val server = player.entityWorld.server ?: return
        val state = RpgWorld.of(server)
        if (!state.enabled) return
        if (!state.markKit(player.uuid)) return

        wear(player, EquipmentSlot.HEAD, enchanted(player, Items.DIAMOND_HELMET, Enchantments.PROTECTION to PROTECTION))
        wear(player, EquipmentSlot.CHEST, enchanted(player, Items.DIAMOND_CHESTPLATE, Enchantments.PROTECTION to PROTECTION))
        wear(player, EquipmentSlot.LEGS, enchanted(player, Items.DIAMOND_LEGGINGS, Enchantments.PROTECTION to PROTECTION))
        wear(player, EquipmentSlot.FEET, enchanted(player, Items.DIAMOND_BOOTS, Enchantments.PROTECTION to PROTECTION))
        wear(player, EquipmentSlot.OFFHAND, ItemStack(Items.SHIELD))
        offer(player, enchanted(player, Items.DIAMOND_SWORD, Enchantments.SHARPNESS to SHARPNESS))
        offer(player, enchanted(player, Items.BOW, Enchantments.POWER to POWER, Enchantments.INFINITY to 1))
        offer(player, ItemStack(Items.ARROW, 1))
        offer(player, ItemStack(Items.GOLDEN_APPLE, APPLES))
        if (HhaConfig.enabled("rpg_hunger")) offer(player, ItemStack(Items.BREAD, BREAD))
        player.currentScreenHandler.sendContentUpdates()

        player.sendMessage(
            Text.translatable("hha.rpg.kit.hint", Text.keybind("key.hha.journal")).formatted(Formatting.GOLD, Formatting.ITALIC),
            false,
        )
        RpgServer.event(listOf(player), RpgEventPayload.TOAST, emptyList(), listOf("hha.rpg.kit.toast"))
    }

    private fun wear(player: ServerPlayerEntity, slot: EquipmentSlot, stack: ItemStack) {
        if (player.getEquippedStack(slot).isEmpty) player.equipStack(slot, stack) else offer(player, stack)
    }

    private fun offer(player: ServerPlayerEntity, stack: ItemStack) {
        player.inventory.offerOrDrop(stack)
    }

    private fun enchanted(player: ServerPlayerEntity, item: Item, vararg enchantments: Pair<RegistryKey<Enchantment>, Int>): ItemStack {
        val stack = ItemStack(item)
        val registry = player.entityWorld.registryManager.getOrThrow(RegistryKeys.ENCHANTMENT)
        for ((key, level) in enchantments) stack.addEnchantment(registry.getOrThrow(key), level)
        return stack
    }
}
