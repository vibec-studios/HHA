package dev.henny.hha.client.vfx

import dev.henny.hha.HhaItems
import dev.henny.hha.logic.Vfx
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.EquipmentSlot
import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin

/** Dauerhafte Rüstungs-Details, die ohne Serverpaket aus der sichtbaren Ausrüstung folgen. */
object AmbientVfx {

    private val X = Vec3d(1.0, 0.0, 0.0)
    private val Z = Vec3d(0.0, 0.0, 1.0)
    private val HEAVEN = Palette.of(Vfx.Style.HEAVEN)
    private val HELL = Palette.of(Vfx.Style.HELL)

    fun render(d: VfxDraw) {
        val client = MinecraftClient.getInstance()
        val world = client.world ?: return
        val time = (world.time % 100000L) + d.tickDelta
        for (player in world.players) {
            if (player.isInvisible || player.isSpectator) continue
            if (player === client.cameraEntity && client.options.perspective.isFirstPerson) continue
            if (player.squaredDistanceTo(d.cam) > 96.0 * 96.0) continue
            val head = player.getEquippedStack(EquipmentSlot.HEAD)
            val pos = player.getLerpedPos(d.tickDelta)
            if (head.isOf(HhaItems.HEAVEN_HELMET)) {
                val center = pos.add(0.0, player.height + 0.32 + sin(time * 0.06) * 0.035, 0.0)
                d.ring(VfxLayers.GLOW_ADD, center, X, Z, 0.2, 0.62, withAlpha(HEAVEN.main, 0f), withAlpha(HEAVEN.main, 0.45f), 24)
                d.ring(VfxLayers.ENERGY, center, X, Z, 0.27, 0.40, withAlpha(HEAVEN.core, 0.95f), withAlpha(HEAVEN.main, 0.95f), 28, 2f, time * 0.03f)
            } else if (head.isOf(HhaItems.HELL_HELMET)) {
                // Seelenfeuer-Krone: fünf züngelnde Flammenzacken.
                val center = pos.add(0.0, player.height + 0.02, 0.0)
                val flames = 5
                for (i in 0 until flames) {
                    val a = i.toDouble() / flames * Math.PI * 2.0 + time * 0.03
                    val base = center.add(cos(a) * 0.3, 0.0, sin(a) * 0.3)
                    val h = 0.28 + 0.1 * sin(time * 0.33 + i * 1.7)
                    d.ribbon(
                        VfxLayers.ENERGY,
                        listOf(base, base.add(0.0, h * 0.5, 0.0), base.add(sin(time * 0.2 + i) * 0.03, h, 0.0)),
                        { 0.09 * (1.0 - it * 0.85) }, { withAlpha(HELL.main, 0.95f * (1f - it * 0.3f)) },
                        uScale = 1.2f, uOffset = i * 1.3f,
                    )
                }
            }
        }
    }
}
