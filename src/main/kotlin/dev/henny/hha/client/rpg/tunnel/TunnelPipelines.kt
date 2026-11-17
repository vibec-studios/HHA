package dev.henny.hha.client.rpg.tunnel

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat
import dev.henny.hha.Hha
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderSetup
import net.minecraft.client.render.VertexFormats

/**
 * Eigene Pipelines des Lichttunnels (Shader `hha:core/tunnel`, PLAN 4.1). **Nicht global registriert** (Muster der
 * Portal-Pipelines, Regel 9): Ein fehlerhafter Shader trifft so nur den Tunnel, nie den Ressourcen-Reload. Vor dem
 * ersten Zeichnen prüft [ready] alle Varianten mit `precompilePipeline(...).isValid`; schlägt das fehl (oder wirft das
 * Zeichnen), steht [failed] und die Lobby zeigt den 2D-Rückfall (PLAN 5).
 */
object TunnelPipelines {

    /** Wegvariante des Tunnels (Shader-Define). */
    enum class Variant(val define: String) { HEAVEN("TUNNEL_HEAVEN"), HELL("TUNNEL_HELL"), TINT("TUNNEL_TINT") }

    /** Vorgesehene Puffergröße: Röhre und Deckel ([TunnelRenderer.QUADS] Vierecke × 4 Ecken × 28 Byte). */
    private const val TUNNEL_BUFFER = 1 shl 19

    private val BASE: RenderPipeline.Snippet = RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
        .withVertexShader(Hha.id("core/tunnel"))
        .withFragmentShader(Hha.id("core/tunnel"))
        .withCull(false)
        .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR_LIGHT, VertexFormat.DrawMode.QUADS)
        .buildSnippet()

    private fun wallPipeline(variant: Variant): RenderPipeline = RenderPipeline.builder(BASE)
        .withLocation(Hha.id("pipeline/tunnel_" + variant.name.lowercase()))
        .withShaderDefine(variant.define)
        .withoutBlend()
        .withDepthWrite(true)
        .build()

    private val WALLS: Map<Variant, RenderPipeline> = Variant.entries.associateWith { wallPipeline(it) }

    /** Lichtringe und Schleier: additiv (Alpha bleibt, wie es die Wand geschrieben hat), ohne Tiefe zu schreiben. */
    private val GLOW_PIPELINE: RenderPipeline = RenderPipeline.builder(BASE)
        .withLocation(Hha.id("pipeline/tunnel_glow"))
        .withShaderDefine("TUNNEL_GLOW")
        .withBlend(BlendFunction.ADDITIVE)
        .withDepthWrite(false)
        .build()

    private val ALL = WALLS.values + GLOW_PIPELINE

    private val WALL_LAYERS: Map<Variant, RenderLayer> = WALLS.mapValues { (variant, pipeline) ->
        RenderLayer.of("hha_tunnel_" + variant.name.lowercase(), RenderSetup.builder(pipeline).expectedBufferSize(TUNNEL_BUFFER).build())
    }

    val GLOW: RenderLayer = RenderLayer.of("hha_tunnel_glow", RenderSetup.builder(GLOW_PIPELINE).expectedBufferSize(16384).build())

    fun wall(variant: Variant): RenderLayer = WALL_LAYERS.getValue(variant)

    /** Shader oder Zeichnen gescheitert: einmal geloggt, danach nur noch der 2D-Rückfall. */
    @Volatile
    var failed = false
        private set

    private var checked = false

    /** Sind alle Tunnel-Pipelines gültig? Prüft beim ersten Aufruf (Render-Thread), danach gemerkt. */
    fun ready(): Boolean {
        if (failed) return false
        if (checked) return true
        return try {
            val device = RenderSystem.getDevice()
            val ok = ALL.all { device.precompilePipeline(it).isValid }
            checked = true
            if (!ok) fail("Tunnel-Shader hha:core/tunnel ließ sich nicht übersetzen", null)
            ok
        } catch (e: Exception) {
            fail("Tunnel-Pipelines ließen sich nicht anlegen", e)
            false
        }
    }

    /** Ab jetzt Rückfall (PLAN 5); [why] erscheint einmal im Log. */
    fun fail(why: String, error: Throwable?) {
        if (failed) return
        failed = true
        if (error != null) Hha.LOGGER.error("HHA: {}; die RPG-Lobby zeigt den 2D-Hintergrund.", why, error)
        else Hha.LOGGER.error("HHA: {}; die RPG-Lobby zeigt den 2D-Hintergrund.", why)
    }
}
