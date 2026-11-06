package dev.henny.hha.client.vfx

import dev.henny.hha.logic.Vfx
import dev.henny.hha.mixin.client.ClientWorldAccessor
import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraft.world.attribute.EnvironmentAttribute
import net.minecraft.world.attribute.EnvironmentAttributes

/**
 * Stimmung über den echten Minecraft-Himmel: Himmels- und Nebelfarbe, Sonnenstand, Sterne, Tageslicht und Wetter
 * werden direkt an der Quelle überblendet (siehe EnvironmentAttributeInterpolatorMixin). Sonne, Mond, Sterne und
 * Regen bleiben dadurch original; die Wolken über dem Forum kommen aus [ArenaClouds].
 *
 * Zwei Ebenen: die Stimmung der Bossarena (mit Nebel, Wolkenhöhe und Wetter) und kurze Stimmungen von Fähigkeiten.
 */
object SkyMood {

    private class Mood(
        val sky: Int, val fog: Int, val clouds: Int, val sunset: Int,
        val sun: Float, val stars: Float, val light: Float, val rain: Float, val thunder: Float,
        val fogStart: Float, val fogEnd: Float,
    )

    private val MOODS = mapOf(
        // Stage 1: heller Vormittag über den Wolken.
        Vfx.Style.GOLD to Mood(0xFF6FA8F0.toInt(), 0xFFFFE6BC.toInt(), 0xE6FFFFFF.toInt(), 0, 25f, 0f, 1f, 0f, 0f, 70f, 210f),
        // Stage 2: Abendrot, die Sonne steht tief.
        Vfx.Style.HEAVEN to Mood(0xFF4A3F86.toInt(), 0xFFF08A4F.toInt(), 0xE6FFB48C.toInt(), 0xC0FF6A28.toInt(), 86f, 0.25f, 0.85f, 0f, 0f, 50f, 180f),
        // Stage 3: Mitternacht, Sterne über einem Totenreich aus kaltem Nebel.
        Vfx.Style.SOUL to Mood(0xFF04121C.toInt(), 0xFF0B3A48.toInt(), 0xCC1E4A58.toInt(), 0, 180f, 1f, 0.45f, 0f, 0f, 24f, 120f),
        // Stage 4: Gewitter, Regen und Blitze.
        Vfx.Style.STORM to Mood(0xFF23222E.toInt(), 0xFF6C5C38.toInt(), 0xE6454552.toInt(), 0, 40f, 0f, 0.6f, 1f, 1f, 30f, 130f),
        // Fähigkeiten: glutroter und seelenblauer Himmel.
        Vfx.Style.HADES to Mood(0xFF04160A.toInt(), 0xFF0E5A22.toInt(), 0xE6123A1C.toInt(), 0, 170f, 0.7f, 0.65f, 0f, 0f, 0f, 0f),
        Vfx.Style.FIRE to Mood(0xFF5A1408.toInt(), 0xFFA83A10.toInt(), 0xE66A2A18.toInt(), 0, 95f, 0f, 0.75f, 0f, 0f, 0f, 0f),
        Vfx.Style.HELL to Mood(0xFF06202A.toInt(), 0xFF0F5A6A.toInt(), 0xE6123A44.toInt(), 0, 170f, 0.7f, 0.65f, 0f, 0f, 0f, 0f),
    )

    private fun mix(a: Mood, b: Mood, t: Float) = Mood(
        lerpColor(a.sky, b.sky, t), lerpColor(a.fog, b.fog, t), lerpColor(a.clouds, b.clouds, t), lerpColor(a.sunset, b.sunset, t),
        MathHelper.lerp(t, a.sun, b.sun), MathHelper.lerp(t, a.stars, b.stars), MathHelper.lerp(t, a.light, b.light),
        MathHelper.lerp(t, a.rain, b.rain), MathHelper.lerp(t, a.thunder, b.thunder),
        MathHelper.lerp(t, a.fogStart, b.fogStart), MathHelper.lerp(t, a.fogEnd, b.fogEnd),
    )

    // --- Bossarena ---------------------------------------------------------------------------------
    private var arenaActive = false
    private var arenaFrom: Mood? = null
    private var arenaTo: Mood? = null
    private var arenaBlend = 1f
    private var arenaSpeed = 0.02f
    private var arenaStrength = 0f
    private var arena: Mood? = null
    private var savedRain = 0f
    private var savedThunder = 0f
    private var weatherHeld = false

    // --- Fähigkeit ---------------------------------------------------------------------------------
    private var pulse: Mood? = null
    private var pulsePos = Vec3d.ZERO
    private var pulseRadius = 0f
    private var pulseAge = 0
    private var pulseLife = 1
    private var pulsePeak = 0f
    private var pulseMovesSun = false
    private var pulseStrength = 0f

    /** Stimmung der Bossarena samt ihren 3D-Wolken. */
    fun setArena(style: Vfx.Style, center: Vec3d, transitionTicks: Int) {
        val next = MOODS[style] ?: return
        arenaFrom = if (arenaStrength > 0f) arena else next
        arenaTo = next
        arenaBlend = 0f
        arenaSpeed = 1f / transitionTicks.coerceAtLeast(1)
        arenaActive = true
        ArenaClouds.set(style, center, transitionTicks)
    }

    fun clearArena() {
        arenaActive = false
        ArenaClouds.clear()
    }

    /** Kurze Stimmung einer Fähigkeit um [pos]. Erst lange Stimmungen dürfen auch die Sonne bewegen. */
    fun pulse(style: Vfx.Style, pos: Vec3d, strength: Float, radius: Float, ticks: Int) {
        pulse = MOODS[style] ?: return
        pulsePos = pos
        pulseRadius = radius
        pulseAge = 0
        pulseLife = ticks.coerceAtLeast(1)
        pulsePeak = strength
        pulseMovesSun = ticks >= 200
    }

    fun reset() {
        val world = MinecraftClient.getInstance().world
        if (world != null && weatherHeld) restoreWeather(world)
        weatherHeld = false
        arenaActive = false
        arenaStrength = 0f
        arena = null
        pulse = null
        pulseStrength = 0f
        ArenaClouds.reset()
    }

    fun tick(world: ClientWorld?) {
        ArenaClouds.tick(world)
        arenaStrength = (arenaStrength + if (arenaActive) 0.03f else -0.1f).coerceIn(0f, 1f)
        arenaBlend = (arenaBlend + arenaSpeed).coerceAtMost(1f)
        val from = arenaFrom
        val to = arenaTo
        if (from != null && to != null) arena = mix(from, to, arenaBlend)

        val currentPulse = pulse
        if (currentPulse != null) {
            pulseAge++
            val player = MinecraftClient.getInstance().player
            val near = player != null && player.entityPos.squaredDistanceTo(pulsePos) <= pulseRadius * pulseRadius
            val envelope = minOf(pulseAge / 10f, (pulseLife - pulseAge) / 20f, 1f).coerceAtLeast(0f)
            pulseStrength = if (near) envelope * pulsePeak else 0f
            if (pulseAge >= pulseLife) {
                pulse = null
                pulseStrength = 0f
            }
        }

        if (world != null) tickWeather(world)
    }

    /** Das Wetter gibt es nur auf dem eigenen Client; am Ende wird der echte Stand zurückgesetzt. */
    private fun tickWeather(world: ClientWorld) {
        val mood = arena
        if (arenaStrength > 0f && mood != null) {
            if (!weatherHeld) {
                savedRain = world.getRainGradient(1f)
                savedThunder = world.getThunderGradient(1f)
                weatherHeld = true
            }
            world.setRainGradient(MathHelper.lerp(arenaStrength, savedRain, maxOf(savedRain * (1f - arenaBlend), mood.rain)))
            world.setThunderGradient(MathHelper.lerp(arenaStrength, savedThunder, maxOf(savedThunder * (1f - arenaBlend), mood.thunder)))
        } else if (weatherHeld) {
            restoreWeather(world)
            weatherHeld = false
        }
    }

    private fun restoreWeather(world: ClientWorld) {
        world.setRainGradient(savedRain)
        world.setThunderGradient(savedThunder)
    }

    private inline fun color(value: Any?, pick: (Mood) -> Int): Any? {
        var c = value as? Int ?: return value
        val a = arena
        if (a != null && arenaStrength > 0f) c = lerpColor(c, pick(a), arenaStrength)
        val p = pulse
        if (p != null && pulseStrength > 0f) c = lerpColor(c, pick(p), pulseStrength)
        return c
    }

    private inline fun number(value: Any?, pulseToo: Boolean, pick: (Mood) -> Float): Any? {
        var v = value as? Float ?: return value
        val a = arena
        if (a != null && arenaStrength > 0f) v = MathHelper.lerp(arenaStrength, v, pick(a))
        val p = pulse
        if (pulseToo && p != null && pulseStrength > 0f) v = MathHelper.lerp(pulseStrength, v, pick(p))
        return v
    }

    private fun arenaFog(value: Any?, pick: (Mood) -> Float): Any? {
        val v = value as? Float ?: return value
        val a = arena ?: return value
        return if (arenaStrength > 0f) MathHelper.lerp(arenaStrength, v, pick(a)) else value
    }

    private fun hideVanillaClouds(value: Any?): Any? {
        val c = value as? Int ?: return value
        val alpha = ((c ushr 24) * (1f - arenaStrength)).toInt()
        return (alpha shl 24) or (c and 0xFFFFFF)
    }

    /** Vom Mixin gerufen: ersetzt einen Himmelswert, falls gerade eine Stimmung läuft. */
    @JvmStatic
    fun override(attribute: EnvironmentAttribute<*>, value: Any?): Any? {
        if (arenaStrength <= 0f && pulseStrength <= 0f) return value
        // Ein Blitz soll den Himmel weiterhin wie gewohnt aufhellen.
        val flash = ((MinecraftClient.getInstance().world as? ClientWorldAccessor)?.`hha$getLightningTicksLeft`() ?: 0) > 0
        return when (attribute) {
            EnvironmentAttributes.SKY_COLOR_VISUAL -> if (flash) value else color(value) { it.sky }
            EnvironmentAttributes.FOG_COLOR_VISUAL -> color(value) { it.fog }
            EnvironmentAttributes.CLOUD_COLOR_VISUAL -> hideVanillaClouds(color(value) { it.clouds })
            EnvironmentAttributes.SUNRISE_SUNSET_COLOR_VISUAL -> color(value) { it.sunset }
            EnvironmentAttributes.SKY_LIGHT_FACTOR_VISUAL -> if (flash) value else number(value, true) { it.light }
            EnvironmentAttributes.STAR_BRIGHTNESS_VISUAL -> number(value, pulseMovesSun) { it.stars }
            EnvironmentAttributes.SUN_ANGLE_VISUAL, EnvironmentAttributes.STAR_ANGLE_VISUAL -> number(value, pulseMovesSun) { it.sun }
            EnvironmentAttributes.MOON_ANGLE_VISUAL -> number(value, pulseMovesSun) { it.sun + 180f }
            EnvironmentAttributes.FOG_START_DISTANCE_VISUAL -> arenaFog(value) { it.fogStart }
            EnvironmentAttributes.FOG_END_DISTANCE_VISUAL -> arenaFog(value) { it.fogEnd }
            else -> value
        }
    }
}
