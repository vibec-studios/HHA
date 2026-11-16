package dev.henny.hha.client

import dev.henny.hha.api.lore.HhaLore
import dev.henny.hha.api.set.HhaSets
import dev.henny.hha.client.gui.Ui
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.entity.EquipmentSlot
import net.minecraft.item.Item
import net.minecraft.text.Text
import java.util.Locale
import kotlin.math.floor

/**
 * Rendert die in [HhaLore] registrierten Lore-Zeilen (eingebaute Items und
 * Addons) mit den echten, vom Server gesyncten Config-Werten — Cooldowns in
 * Sekunden, Schaden in Punkten, Schwellen in Herzen. Ändert ein Admin die
 * Config, stimmt die Lore sofort wieder.
 *
 * Stil wie die Website: ruhiger grauer Text, nur die Werte tragen die Farbe des Sets (Heaven Gold, Hell Blau).
 */
object HhaTooltips {

    fun register() {
        ItemTooltipCallback.EVENT.register { stack, _, _, lines ->
            val lore = HhaLore.linesFor(stack.item) ?: return@register
            val accent = accentOf(stack.item)
            var index = minOf(1, lines.size)
            for (line in lore) {
                lines.add(index, render(line, accent))
                index++
            }
        }
    }

    /** Set-Farbe eines Items (Teil oder Waffe eines Sets), sonst HHA-Gold. */
    private fun accentOf(item: Item): Int {
        for (set in HhaSets.all()) {
            val belongs = set.weapons.contains(item) ||
                listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET).any { set.piece(it) == item }
            if (!belongs) continue
            return when (set.id) {
                HhaSets.HELL_ID -> Ui.SOUL
                else -> Ui.GOLD
            }
        }
        return Ui.GOLD
    }

    private fun render(line: HhaLore.LoreLine, accent: Int): Text {
        val args = line.args.map { arg ->
            val value = ClientConfigCache.num(arg.configKey)
            val shown = when (arg.kind) {
                HhaLore.ValueKind.NUMBER -> format(value)
                HhaLore.ValueKind.SECONDS -> format(value / 20.0)
                HhaLore.ValueKind.HEARTS -> format(value / 2.0)
            }
            Text.literal(shown).styled { it.withColor(accent and 0xFFFFFF) }
        }
        return Text.translatable(line.translationKey, *args.toTypedArray()).styled { it.withColor(Ui.MUTED and 0xFFFFFF) }
    }

    private fun format(value: Double): String =
        if (value == floor(value) && !value.isInfinite()) {
            value.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.1f", value)
        }
}
