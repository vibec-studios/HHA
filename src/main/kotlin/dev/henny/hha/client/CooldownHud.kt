package dev.henny.hha.client

import dev.henny.hha.Hha
import dev.henny.hha.HhaItems
import dev.henny.hha.api.client.HudCooldowns
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gui.DrawContext
import net.minecraft.entity.EquipmentSlot
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import kotlin.math.sin

/**
 * Cooldown-HUD: zentrierte Slot-Leiste über der Hotbar. Bereite Fähigkeiten
 * pulsieren golden/feurig, laufende Cooldowns dunkeln von oben ab und zeigen
 * einen Fortschrittsbalken. Oben mittig der Ultra-Status.
 */
object CooldownHud {

    private const val SLOT = 22
    private const val GOLD = 0x00FFD75E
    private const val FIRE = 0x00FF7B1F

    private val ICON_BEAM_LIGHT: Identifier = Hha.id("hud/beam_light")
    private val ICON_BEAM_LAVA: Identifier = Hha.id("hud/beam_lava")
    private val ICON_CAMP: Identifier = Hha.id("hud/camp")
    private val ICON_SWORD: Identifier = Hha.id("hud/sword")
    private val ICON_WAVE: Identifier = Hha.id("hud/wave")
    private val ICON_MACE: Identifier = Hha.id("hud/mace")
    private val ICON_CHAIN: Identifier = Hha.id("hud/chain")
    private val ICON_ULTRA: Identifier = Hha.id("hud/ultra")

    /** Vom Server gemeldeter Ultra-Rest (Ticks); tickt clientseitig herunter. */
    var ultraRemainingTicks = 0

    fun register() {
        HudRenderCallback.EVENT.register { context, _ ->
            render(MinecraftClient.getInstance(), context)
        }
    }

    fun clientTick() {
        if (ultraRemainingTicks > 0) ultraRemainingTicks--
    }

    private fun isHeaven(stack: ItemStack): Boolean =
        stack.isOf(HhaItems.HEAVEN_HELMET) || stack.isOf(HhaItems.HEAVEN_CHESTPLATE) ||
            stack.isOf(HhaItems.HEAVEN_LEGGINGS) || stack.isOf(HhaItems.HEAVEN_BOOTS) ||
            stack.isOf(HhaItems.HEAVENS_SWORD) || stack.isOf(HhaItems.HEAVENS_MACE)

    /** Ein Eintrag in der Cooldown-Leiste: Stack, Icon, Akzentfarbe (0xRRGGBB). */
    private class Slot(val stack: ItemStack, val icon: Identifier, val color: Int)

    private fun builtin(stack: ItemStack, icon: Identifier): Slot =
        Slot(stack, icon, if (isHeaven(stack)) GOLD else FIRE)

    private fun render(client: MinecraftClient, context: DrawContext) {
        val player = client.player ?: return
        if (client.options.hudHidden) return
        if (!HhaClientConfig.enabled("hud_enabled")) return

        val manager = player.itemCooldownManager

        val candidates = ArrayList<Slot>()
        val chest = player.getEquippedStack(EquipmentSlot.CHEST)
        val legs = player.getEquippedStack(EquipmentSlot.LEGS)
        if (chest.isOf(HhaItems.HEAVEN_CHESTPLATE)) {
            candidates.add(builtin(chest, ICON_BEAM_LIGHT))
        } else if (chest.isOf(HhaItems.HELL_CHESTPLATE)) {
            candidates.add(builtin(chest, ICON_BEAM_LAVA))
        }
        if (legs.isOf(HhaItems.HELL_LEGGINGS)) candidates.add(builtin(legs, ICON_CAMP))
        for (stack in listOf(player.mainHandStack, player.offHandStack)) {
            when {
                stack.isOf(HhaItems.HELLS_SWORD) -> candidates.add(builtin(stack, ICON_SWORD))
                stack.isOf(HhaItems.HEAVENS_SWORD) -> candidates.add(builtin(stack, ICON_WAVE))
                stack.isOf(HhaItems.HELLS_MACE) -> candidates.add(builtin(stack, ICON_CHAIN))
                stack.isOf(HhaItems.HEAVENS_MACE) -> candidates.add(builtin(stack, ICON_MACE))
            }
        }
        for (entry in HudCooldowns.all()) {
            val stack = entry.provider(player) ?: continue
            if (!stack.isEmpty) candidates.add(Slot(stack, entry.icon, entry.accentColor and 0xFFFFFF))
        }

        val time = client.world?.time ?: 0L

        // Client-Visuals: Skalierung läuft über die Matrix, das Layout rechnet
        // im skalierten Koordinatenraum; Offsets verschieben die ganze Leiste.
        val scale = HhaClientConfig.numF("hud_scale")
        val offsetX = HhaClientConfig.num("hud_offset_x").toInt()
        val offsetY = HhaClientConfig.num("hud_offset_y").toInt()
        val pulseOn = HhaClientConfig.enabled("hud_pulse")
        val backgroundOn = HhaClientConfig.enabled("hud_background")

        val matrices = context.matrices
        matrices.pushMatrix()
        try {
            matrices.scale(scale, scale)
            val viewW = context.scaledWindowWidth / scale
            val viewH = context.scaledWindowHeight / scale

            if (candidates.isNotEmpty()) {
                val total = candidates.size * SLOT - (SLOT - 18)
                var x = ((viewW - total) / 2).toInt() + offsetX
                val y = (viewH - 82).toInt() + offsetY

                for (slot in candidates) {
                    val stack = slot.stack
                    val icon = slot.icon
                    val base = slot.color
                    val progress = manager.getCooldownProgress(stack, 0f)

                    if (backgroundOn) {
                        context.fill(x - 1, y - 1, x + 17, y + 17, 0x90000000.toInt())
                    }

                    val border = if (progress <= 0f) {
                        val alpha = if (pulseOn) {
                            val pulse = sin(time * 0.35) * 0.5 + 0.5
                            (140 + pulse * 115).toInt()
                        } else {
                            220
                        }
                        (alpha shl 24) or base
                    } else {
                        0xFF3A3A3A.toInt()
                    }
                    context.fill(x - 2, y - 2, x + 18, y - 1, border)
                    context.fill(x - 2, y + 17, x + 18, y + 18, border)
                    context.fill(x - 2, y - 1, x - 1, y + 17, border)
                    context.fill(x + 17, y - 1, x + 18, y + 17, border)

                    context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, icon, x, y, 16, 16)

                    if (progress > 0f) {
                        val covered = (progress * 16f).toInt().coerceIn(0, 16)
                        context.fill(x, y, x + 16, y + covered, 0xB0101010.toInt())
                        context.fill(x - 2, y + 19, x + 18, y + 22, 0xAA000000.toInt())
                        val w = ((1f - progress) * 20f).toInt()
                        context.fill(x - 2, y + 20, x - 2 + w, y + 21, 0xFF000000.toInt() or base)
                    }
                    x += SLOT
                }
            }

            if (ultraRemainingTicks > 0 && HhaClientConfig.enabled("ultra_bar")) {
                val cx = (viewW / 2).toInt() + offsetX
                val seconds = (ultraRemainingTicks + 19) / 20
                val pulse = sin(time * 0.5) * 0.5 + 0.5
                val titleColor = if (pulse > 0.5) 0xFFFFD75E.toInt() else 0xFFFF7B1F.toInt()
                val title = Text.translatable("hha.hud.ultra_active", seconds)
                    .formatted(Formatting.BOLD)
                val titleWidth = client.textRenderer.getWidth(title)
                val icon = 16
                val gap = 3
                val left = cx - (icon + gap + titleWidth) / 2
                context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, ICON_ULTRA, left, 8 + offsetY, icon, icon)
                context.drawTextWithShadow(
                    client.textRenderer, title, left + icon + gap, 12 + offsetY, titleColor,
                )

                val total = 1200f
                val fraction = (ultraRemainingTicks / total).coerceIn(0f, 1f)
                val barWidth = 140
                val barX = cx - barWidth / 2
                context.fill(barX - 2, 23 + offsetY, barX + barWidth + 2, 32 + offsetY, 0xAA000000.toInt())
                context.fill(barX - 1, 24 + offsetY, barX + barWidth + 1, 31 + offsetY, 0xFF1A120B.toInt())
                val fill = (barWidth * fraction).toInt()
                context.fill(barX, 25 + offsetY, barX + fill, 30 + offsetY, 0xFFFF7B1F.toInt())
                if (fill > 2) {
                    context.fill(barX, 25 + offsetY, barX + fill - 2, 27 + offsetY, 0xFFFFD75E.toInt())
                }
            }
        } finally {
            matrices.popMatrix()
        }
    }
}
