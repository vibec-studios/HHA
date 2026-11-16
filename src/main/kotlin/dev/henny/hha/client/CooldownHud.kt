package dev.henny.hha.client

import dev.henny.hha.Hha
import dev.henny.hha.HhaItems
import dev.henny.hha.api.client.HudCooldowns
import dev.henny.hha.client.gui.Ui
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gui.DrawContext
import net.minecraft.entity.EquipmentSlot
import net.minecraft.item.ItemStack
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Cooldown-HUD im Stil der Studio-Website: zentrierte Reihe eckiger Felder über der Hotbar (wie die Hotbar der
 * Website: dunkler Grund, 1-px-Rahmen, Akzent-Unterkante). Bereite Fähigkeiten tragen Rahmen und Unterkante in der
 * Set-Farbe wie auf der Website (Gold = Heaven, Seelenblau = Hell), laufende Cooldowns dunkeln von oben ab und füllen
 * die Unterkante grau.
 * Oben mittig der Ultra-Status auf einer dunklen Platte mit zehn Segmenten. Die Menü-Vorschau zeichnet über
 * [drawSlot] genau dasselbe.
 */
object CooldownHud {

    /** Feld: Symbol 16 px plus je 2 px Rand. */
    const val SLOT = 20

    /** Abstand von Feld zu Feld wie bisher (22), damit `hud_offset_x/y` ihre Bedeutung behalten. */
    const val STEP = 22

    const val GOLD_COLOR = Ui.GOLD
    const val SOUL_COLOR = Ui.SOUL

    private const val ULTRA_W = 132
    private const val ULTRA_H = 24

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

    /** Ein Eintrag in der Cooldown-Leiste: Stack, Icon, Akzentfarbe (ARGB). */
    private class Slot(val stack: ItemStack, val icon: Identifier, val color: Int)

    private fun builtin(stack: ItemStack, icon: Identifier): Slot =
        Slot(stack, icon, if (isHeaven(stack)) GOLD_COLOR else SOUL_COLOR)

    /**
     * Ein Feld bei ([x], [y]) mit Größe [SLOT]. [progress] 0 = bereit, sonst Anteil der Restzeit; [pulse] 0…1 lässt
     * den Rahmen bereiter Felder atmen (nur wenn `hud_pulse` an ist); [a] blendet alles aus.
     */
    fun drawSlot(context: DrawContext, x: Int, y: Int, icon: Identifier, color: Int, progress: Float, pulse: Float, a: Float = 1f) {
        val s = SLOT
        if (HhaClientConfig.enabled("hud_background")) context.fill(x, y, x + s, y + s, Ui.alpha(0xA0000000.toInt(), a))
        val ready = progress <= 0f
        val frame = if (ready) {
            Ui.alpha(color, (if (HhaClientConfig.enabled("hud_pulse")) 0.55f + 0.45f * pulse else 0.9f) * a)
        } else {
            Ui.alpha(Ui.LINE_2, a)
        }
        Ui.frame(context, x, y, x + s, y + s, frame)
        context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, icon, x + 2, y + 2, 16, 16, a)
        if (ready) {
            context.fill(x + 1, y + s - 3, x + s - 1, y + s - 1, Ui.alpha(color, a))
        } else {
            val covered = (progress * 16f).toInt().coerceIn(0, 16)
            context.fill(x + 2, y + 2, x + 18, y + 2 + covered, Ui.alpha(0xB0101010.toInt(), a))
            context.fill(x + 1, y + s - 3, x + s - 1, y + s - 1, Ui.alpha(0x30FFFFFF, a))
            context.fill(x + 1, y + s - 3, x + 1 + ((s - 2) * (1f - progress)).roundToInt(), y + s - 1, Ui.alpha(Ui.MUTED, a))
        }
    }

    /** Ultra-Status oben mittig (Website: .bossbar), auf dunkler Platte, damit er vor hellem Himmel lesbar bleibt. */
    private fun drawUltra(client: MinecraftClient, context: DrawContext, cx: Int, y: Int) {
        val tr = client.textRenderer
        val x = cx - ULTRA_W / 2
        val x2 = x + ULTRA_W
        context.fill(x, y, x2, y + ULTRA_H, 0xB4000000.toInt())
        Ui.frame(context, x, y, x2, y + ULTRA_H, Ui.LINE_2)
        context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, ICON_ULTRA, x + 4, y + 4, 16, 16)
        Ui.label(context, tr, Text.translatableWithFallback("hha.hud.ultra", "Ultra").string, x + 25, y + 4, Ui.GOLD)
        val secs = "${(ultraRemainingTicks + 19) / 20} s"
        Ui.label(context, tr, secs, x2 - 6 - Ui.labelWidth(tr, secs), y + 4, Ui.TEXT)
        val total = ClientConfigCache.num("ultra_duration").coerceAtLeast(1.0).toFloat()
        val frac = (ultraRemainingTicks / total).coerceIn(0f, 1f)
        val segs = 10
        val gap = 2
        val bx = x + 25
        val bw = x2 - 6 - bx
        val by = y + 14
        val sw = (bw - (segs - 1) * gap) / segs.toFloat()
        for (i in 0 until segs) {
            val sx = (bx + i * (sw + gap)).roundToInt()
            val ex = (bx + i * (sw + gap) + sw).roundToInt()
            val f = (frac * segs - i).coerceIn(0f, 1f)
            context.fill(sx, by, ex, by + 4, 0x40FFFFFF)
            if (f > 0f) context.fill(sx, by, sx + ((ex - sx) * f).roundToInt(), by + 4, Ui.GOLD)
        }
    }

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
            if (!stack.isEmpty) candidates.add(Slot(stack, entry.icon, 0xFF000000.toInt() or (entry.accentColor and 0xFFFFFF)))
        }

        val time = client.world?.time ?: 0L

        // Client-Visuals: Skalierung läuft über die Matrix, das Layout rechnet
        // im skalierten Koordinatenraum; Offsets verschieben die ganze Leiste.
        val scale = HhaClientConfig.numF("hud_scale")
        val offsetX = HhaClientConfig.num("hud_offset_x").toInt()
        val offsetY = HhaClientConfig.num("hud_offset_y").toInt()

        val matrices = context.matrices
        matrices.pushMatrix()
        try {
            matrices.scale(scale, scale)
            val viewW = context.scaledWindowWidth / scale
            val viewH = context.scaledWindowHeight / scale

            if (candidates.isNotEmpty()) {
                val total = candidates.size * STEP - (STEP - SLOT)
                var x = ((viewW - total) / 2).toInt() + offsetX
                // Rahmen oben bei viewH − 84: dieselbe Lage wie die alte Leiste (Symbol bei viewH − 82)
                val y = (viewH - 84).toInt() + offsetY
                val pulse = (sin(time * 0.35) * 0.5 + 0.5).toFloat()
                for (slot in candidates) {
                    drawSlot(context, x, y, slot.icon, slot.color, manager.getCooldownProgress(slot.stack, 0f), pulse)
                    x += STEP
                }
            }

            if (ultraRemainingTicks > 0 && HhaClientConfig.enabled("ultra_bar")) {
                // Läuft ein Bosskampf, steht oben mittig die Phasenleiste; Ultra rückt dann darunter.
                val bossBottom = dev.henny.hha.client.cine.PhaseBar.bottom()
                val top = if (bossBottom > 0) ((bossBottom + 4) / scale).toInt() else 6
                drawUltra(client, context, (viewW / 2).toInt() + offsetX, top + offsetY)
            }
        } finally {
            matrices.popMatrix()
        }
    }
}
