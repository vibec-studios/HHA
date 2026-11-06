package dev.henny.hha.client.vfx

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.vertex.VertexFormat
import dev.henny.hha.Hha
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderSetup
import net.minecraft.client.render.VertexFormats
import net.minecraft.util.Identifier

/**
 * Eigene Render-Pipelines der Effekte: Position + UV + Farbe, keine Tiefenschreibung, beidseitig.
 * Additive Varianten leuchten wie Licht; die Alpha-Varianten sind für Rauch, Federn und Trümmer.
 */
object VfxLayers {

    val NOISE: Identifier = tex("noise")
    val GLOW: Identifier = tex("glow")
    val FLARE: Identifier = tex("flare")
    val RAYS: Identifier = tex("rays")
    val FEATHER: Identifier = tex("feather")
    val RUNE_CIRCLE: Identifier = tex("rune_circle")
    val RUNE_RING: Identifier = tex("rune_ring")
    val SMOKE: Identifier = tex("smoke")
    val SHARD: Identifier = tex("shard")
    val CHAIN: Identifier = tex("chain")
    val CLOUD: Identifier = tex("cloud")

    private fun tex(name: String): Identifier = Hha.id("textures/vfx/$name.png")

    private val BASE: RenderPipeline.Snippet = RenderPipeline.builder(
        RenderPipelines.TRANSFORMS_PROJECTION_FOG_SNIPPET,
        RenderPipelines.GLOBALS_SNIPPET,
    )
        .withVertexShader(Hha.id("core/vfx"))
        .withFragmentShader(Hha.id("core/vfx"))
        .withSampler("Sampler0")
        .withCull(false)
        .withDepthWrite(false)
        .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
        .buildSnippet()

    /** [occlude]: 0 = rein additiv, 1 = deckt wie Farbe ab; dazwischen leuchtet es und behält den Farbton. */
    private fun glowing(name: String, occlude: Float, define: String? = null): RenderPipeline {
        val builder = RenderPipeline.builder(BASE)
            .withLocation(Hha.id("pipeline/vfx_$name"))
            .withBlend(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)
            .withShaderDefine("VFX_GLOW", occlude)
        if (define != null) builder.withShaderDefine(define)
        return RenderPipelines.register(builder.build())
    }

    /** Leuchtet ohne Nebel: für Dinge weit oben im Himmel, die sonst im Sturmnebel verschwinden. */
    private fun skyGlow(name: String): RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(BASE)
            .withLocation(Hha.id("pipeline/vfx_$name"))
            .withBlend(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)
            .withShaderDefine("VFX_GLOW", 0.25f)
            .withShaderDefine("VFX_NO_FOG")
            .build(),
    )

    private fun blended(name: String, define: String? = null, depthWrite: Boolean = false): RenderPipeline {
        val builder = RenderPipeline.builder(BASE)
            .withLocation(Hha.id("pipeline/vfx_$name"))
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthWrite(depthWrite)
        if (define != null) builder.withShaderDefine(define)
        return RenderPipelines.register(builder.build())
    }

    private val ENERGY_PIPELINE = glowing("energy", 0.75f, "VFX_ENERGY")
    private val FLOW_ADD_PIPELINE = glowing("flow_add", 0.6f, "VFX_FLOW")
    private val FLOW_ALPHA_PIPELINE = blended("flow_alpha", "VFX_FLOW")
    private val SPRITE_ADD_PIPELINE = glowing("sprite_add", 0.5f)
    private val SPRITE_ALPHA_PIPELINE = blended("sprite_alpha")
    private val SOLID_PIPELINE = blended("solid", depthWrite = true)
    private val CLOUD_PIPELINE = blended("cloud", "VFX_NO_FOG")
    private val SKY_GLOW_PIPELINE = skyGlow("sky_glow")

    private fun repeating(name: String, pipeline: RenderPipeline, texture: Identifier): RenderLayer = RenderLayer.of(
        "hha_vfx_$name",
        RenderSetup.builder(pipeline)
            .texture("Sampler0", texture) { RenderSystem.getSamplerCache().getRepeated(FilterMode.LINEAR) }
            .expectedBufferSize(65536)
            .build(),
    )

    private fun clamped(name: String, pipeline: RenderPipeline, texture: Identifier): RenderLayer = RenderLayer.of(
        "hha_vfx_$name",
        RenderSetup.builder(pipeline)
            .texture("Sampler0", texture) { RenderSystem.getSamplerCache().get(FilterMode.LINEAR) }
            .expectedBufferSize(16384)
            .build(),
    )

    /** Fließendes Plasma; UV.x entlang der Form, UV.y quer (0..1). */
    val ENERGY: RenderLayer = repeating("energy", ENERGY_PIPELINE, NOISE)
    val FLOW_ADD: RenderLayer = repeating("flow_add", FLOW_ADD_PIPELINE, NOISE)
    val FLOW_ALPHA: RenderLayer = repeating("flow_alpha", FLOW_ALPHA_PIPELINE, NOISE)
    val RAYS_ADD: RenderLayer = repeating("rays", SPRITE_ADD_PIPELINE, RAYS)
    val RING_ADD: RenderLayer = repeating("rune_ring", SPRITE_ADD_PIPELINE, RUNE_RING)
    val GLOW_ADD: RenderLayer = clamped("glow", SPRITE_ADD_PIPELINE, GLOW)
    val FLARE_ADD: RenderLayer = clamped("flare", SPRITE_ADD_PIPELINE, FLARE)
    val RUNE_ADD: RenderLayer = clamped("rune_circle", SPRITE_ADD_PIPELINE, RUNE_CIRCLE)
    val FEATHER_ALPHA: RenderLayer = clamped("feather", SPRITE_ALPHA_PIPELINE, FEATHER)
    val FEATHER_ADD: RenderLayer = clamped("feather_add", SPRITE_ADD_PIPELINE, FEATHER)
    val SMOKE_ALPHA: RenderLayer = clamped("smoke", SPRITE_ALPHA_PIPELINE, SMOKE)

    /** Wolkenbausch: bleibt auch in der Ferne sichtbar, statt im Nebel zu verschwinden. */
    val SKY_EYE: RenderLayer = clamped("sky_eye", SKY_GLOW_PIPELINE, tex("zeus_eye"))
    val SKY_GLOW: RenderLayer = clamped("sky_glow", SKY_GLOW_PIPELINE, GLOW)
    val SKY_FLARE: RenderLayer = clamped("sky_flare", SKY_GLOW_PIPELINE, FLARE)
    val CLOUD_ALPHA: RenderLayer = clamped("cloud", CLOUD_PIPELINE, CLOUD)
    val SHARD_ALPHA: RenderLayer = repeating("shard", SOLID_PIPELINE, SHARD)
    val CHAIN_ALPHA: RenderLayer = repeating("chain", SOLID_PIPELINE, CHAIN)

    /** Sorgt dafür, dass die Pipelines beim Client-Start registriert sind. */
    fun init() {
        ENERGY.toString()
    }
}
