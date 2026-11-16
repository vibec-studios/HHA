package dev.henny.hha.client.vfx

import dev.henny.hha.logic.Vfx
import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Die schwarzgrüne Säule am Horizont des Tartaros. Sie steht über dem Palast, oder dort, wo Hermes sie gerade hinlügt.
 * Weil das Ziel weit jenseits der Sichtweite liegt, wird sie in fester Entfernung auf die Sichtlinie gezeichnet.
 */
object FarBeacon {

    private const val SHOWN_AT = 300.0
    private const val NEAR_FADE = 160.0
    private val PAL = Palette.of(Vfx.Style.HADES)

    private var target: Vec3d? = null
    private var ticks = 0
    private var strength = 0f

    fun set(pos: Vec3d, life: Int) {
        target = pos
        ticks = life
    }

    fun tick() {
        if (ticks > 0) ticks--
        strength = (strength + if (ticks > 0) 0.03f else -0.03f).coerceIn(0f, 1f)
    }

    fun render(d: VfxDraw) {
        val goal = target ?: return
        if (strength <= 0f) return
        val dx = goal.x - d.cam.x
        val dz = goal.z - d.cam.z
        val distance = sqrt(dx * dx + dz * dz)
        if (distance < 1.0) return
        // Aus der Nähe sieht man den echten Palast, da wird die Säule durchsichtig.
        val near = ((distance - 40.0) / (NEAR_FADE - 40.0)).coerceIn(0.0, 1.0).toFloat()
        val alpha = strength * (0.25f + 0.75f * near)
        val shown = minOf(distance, SHOWN_AT)
        val scale = shown / SHOWN_AT
        val x = d.cam.x + dx / distance * shown
        val z = d.cam.z + dz / distance * shown
        val base = Vec3d(x, 40.0, z)
        val time = (System.nanoTime() / 50_000_000L).toDouble()
        val top = Vec3d(x, 300.0, z)
        d.ribbon(VfxLayers.SKY_GLOW, listOf(base, top), { 22.0 * scale + 3.0 }, { withAlpha(PAL.main, 0.32f * alpha) }, 0f, 0.5f)
        d.ribbon(VfxLayers.SKY_GLOW, listOf(base, top), { 8.0 * scale + 1.2 }, { withAlpha(PAL.core, 0.62f * alpha) }, 0f, 0.5f)
        for (strand in 0..1) {
            val path = List(26) { i ->
                val k = i / 25.0
                val a = k * 18.0 + time * 0.09 + strand * Math.PI
                base.lerp(top, k).add(cos(a) * 7.0 * scale, 0.0, sin(a) * 7.0 * scale)
            }
            d.ribbon(VfxLayers.SKY_GLOW, path, { 2.2 * scale + 0.4 }, { k -> withAlpha(PAL.main, (0.75f - k * 0.45f) * alpha) }, 0f, 0.5f)
        }
    }
}
