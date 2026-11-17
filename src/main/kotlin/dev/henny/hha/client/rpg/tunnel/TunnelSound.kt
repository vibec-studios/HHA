package dev.henny.hha.client.rpg.tunnel

import dev.henny.hha.HhaSounds
import net.minecraft.client.MinecraftClient
import net.minecraft.client.sound.MovingSoundInstance
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.sound.SoundInstance
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import kotlin.math.max
import kotlin.math.min

/**
 * Leise Schleife des Tunnels (`rpg.tunnel_loop`, nahtlos, 8 s) für Lobby und Reise, dazu die einmaligen Töne
 * `rpg.depart`/`rpg.arrive`. Fehlt der `sounds.json`-Eintrag (bis P19 die Schnipsel einspielt), startet nichts.
 */
object TunnelSound {

    /** Pegel der Schleife in Lobby und Reise. */
    const val LOOP_VOLUME = 0.35f

    /** Ein- und Ausblenden je Tick. */
    private const val FADE_IN = 0.02f
    private const val FADE_OUT = 0.025f

    private class Loop : MovingSoundInstance(HhaSounds.RPG_TUNNEL_LOOP, SoundCategory.AMBIENT, SoundInstance.createRandom()) {
        var target = LOOP_VOLUME
        private var level = 0.001f

        init {
            repeat = true
            repeatDelay = 0
            relative = true
            attenuationType = SoundInstance.AttenuationType.NONE
            volume = level
        }

        override fun tick() {
            level = if (level < target) min(target, level + FADE_IN) else max(target, level - FADE_OUT)
            volume = max(0.001f, level)
            if (target <= 0f && level <= 0.001f) setDone()
        }

        fun finish() = setDone()

        override fun shouldAlwaysPlay(): Boolean = true
    }

    private var loop: Loop? = null

    /** Schleife an (falls aus) und auf den vollen Pegel. */
    fun start() {
        val client = MinecraftClient.getInstance()
        val current = loop
        if (current != null && !current.isDone && client.soundManager.isPlaying(current)) {
            current.target = LOOP_VOLUME
            return
        }
        if (client.soundManager.get(HhaSounds.RPG_TUNNEL_LOOP.id()) == null) return
        val sound = Loop()
        loop = sound
        client.soundManager.play(sound)
    }

    /** Schleife weich aus. */
    fun fadeOut() {
        loop?.target = 0f
    }

    /** Sofort aus (Fehler, Verbindungsende). */
    fun stop() {
        loop?.let { MinecraftClient.getInstance().soundManager.stop(it) }
        loop = null
    }

    /** Einmaliger Ton ohne Ort (Aufbruch, Ankunft). */
    fun once(event: SoundEvent, volume: Float = 0.9f) {
        val client = MinecraftClient.getInstance()
        if (client.soundManager.get(event.id()) == null) return
        client.soundManager.play(PositionedSoundInstance.ui(event, 1f, volume))
    }
}
