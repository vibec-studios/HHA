package dev.henny.hha.client.vfx

import dev.henny.hha.Hha
import dev.henny.hha.client.HhaClientConfig
import dev.henny.hha.logic.world.HadesGate
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.DefaultFramebufferSet
import net.minecraft.client.util.memory.ObjectPool

/**
 * Der Bildfilter der Unterwelt (`assets/hha/post_effect/tartaros.json`): Farbgebung, Aschedunst, Flimmern, Schein und
 * Vignette. Läuft nur im Tartaros und lässt sich mit `/hhac toggle tartaros_shader` abschalten.
 */
object UnderworldShader {

    private val ID = Hha.id("tartaros")

    /** Schlägt das Laden einmal fehl (etwa ein Treiber ohne passende Shader), versuchen wir es nicht in jedem Bild neu. */
    private var broken = false

    @JvmStatic
    fun render(pool: ObjectPool) {
        if (broken) return
        val client = MinecraftClient.getInstance()
        val world = client.world ?: return
        if (world.registryKey != HadesGate.TARTAROS || !HhaClientConfig.enabled("tartaros_shader")) return
        try {
            val processor = client.shaderLoader.loadPostEffect(ID, DefaultFramebufferSet.MAIN_ONLY) ?: return
            processor.render(client.framebuffer, pool)
        } catch (e: Exception) {
            broken = true
            Hha.LOGGER.error("Unterwelt-Shader abgeschaltet", e)
        }
    }
}
