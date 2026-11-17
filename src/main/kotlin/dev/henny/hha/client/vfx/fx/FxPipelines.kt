package dev.henny.hha.client.vfx.fx

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexFormat
import dev.henny.hha.Hha
import dev.henny.hha.client.vfx.VfxLayers
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderSetup
import net.minecraft.client.render.VertexFormats

/**
 * Eigene Zeichen-Ebenen der neuen Effekte (PLAN 6.4, REF T1/T20).
 *
 * - [ENERGY]: Shader `hha:core/fx_energy`, vormultipliziert gemischt (`TRANSLUCENT_PREMULTIPLIED_ALPHA` wie die
 *   `VFX_GLOW`-Ebenen), kein Tiefenschreiben, kein Culling. 14 Formen über den Modus im Vertex ([FxMode]).
 * - [SOLID]: Shader `hha:core/fx_solid`, schreibt Tiefe, weich schattiert, mit Lichtkarte der Welt ([FxSolidMode]).
 *
 * Beide sind **nicht** global angemeldet (kein `register` in der Vanilla-Pipelineliste): Ein Shaderfehler kippt so nicht den Ressourcen-Reload,
 * sondern nur diese Ebenen. [ready] prüft einmal per `precompilePipeline(...).isValid`, loggt einmal und merkt sich das
 * Ergebnis; bis dahin bzw. bei Fehler liefern [ENERGY]/[SOLID] die Bestands-Ebenen aus `VfxLayers` und [FxDraw] zeichnet
 * den vereinfachten Rückfall (PLAN 5). Vertexformat und Belegung: Kopf von `FxDraw.kt`.
 */
object FxPipelines {

    /** Abdeckung leuchtender Teile (wie `VFX_GLOW` 0,5–0,75 im Bestand): leuchtet wie Licht, behält am Tag die Farbe. */
    const val GLOW = 0.6f

    /** Dämpfer des ganzen Lichts (wie `VFX_GAIN`, PLAN 2.2 „ruhig“). */
    const val GAIN = 0.7f

    /** Puffergröße je Ebene (Bytes) als Startwert; der gemeinsame Puffer im `VfxManager` hat 2 MiB. */
    private const val ENERGY_BUFFER = 1 shl 18
    private const val SOLID_BUFFER = 1 shl 16

    /** Vertexformat beider Pipelines: Position, Farbe, UV0, UV1, UV2, Normale (Belegung: `FxDraw.kt`). */
    val FORMAT: VertexFormat = VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL

    private val ENERGY_PIPELINE: RenderPipeline = RenderPipeline.builder(
        RenderPipelines.TRANSFORMS_PROJECTION_FOG_SNIPPET,
        RenderPipelines.GLOBALS_SNIPPET,
    )
        .withLocation(Hha.id("pipeline/fx_energy"))
        .withVertexShader(Hha.id("core/fx_energy"))
        .withFragmentShader(Hha.id("core/fx_energy"))
        .withShaderDefine("FX_GLOW", GLOW)
        .withShaderDefine("FX_GAIN", GAIN)
        .withBlend(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)
        .withCull(false)
        .withDepthWrite(false)
        .withVertexFormat(FORMAT, VertexFormat.DrawMode.QUADS)
        .build()

    private val SOLID_PIPELINE: RenderPipeline = RenderPipeline.builder(
        RenderPipelines.TRANSFORMS_PROJECTION_FOG_SNIPPET,
        RenderPipelines.GLOBALS_SNIPPET,
    )
        .withLocation(Hha.id("pipeline/fx_solid"))
        .withVertexShader(Hha.id("core/fx_solid"))
        .withFragmentShader(Hha.id("core/fx_solid"))
        .withSampler("Sampler2")
        .withoutBlend()
        .withCull(false)
        .withDepthWrite(true)
        .withVertexFormat(FORMAT, VertexFormat.DrawMode.QUADS)
        .build()

    private val ENERGY_LAYER: RenderLayer = RenderLayer.of(
        "hha_fx_energy",
        RenderSetup.builder(ENERGY_PIPELINE).expectedBufferSize(ENERGY_BUFFER).build(),
    )

    private val SOLID_LAYER: RenderLayer = RenderLayer.of(
        "hha_fx_solid",
        RenderSetup.builder(SOLID_PIPELINE).useLightmap().expectedBufferSize(SOLID_BUFFER).build(),
    )

    /** Energie-Ebene; ohne gültige Pipeline die Bestands-Ebene `VfxLayers.ENERGY` (anderes Vertexformat!). */
    val ENERGY: RenderLayer get() = if (ready()) ENERGY_LAYER else VfxLayers.ENERGY

    /** Feste Ebene; ohne gültige Pipeline `VfxLayers.FLOW_ALPHA` (anderes Vertexformat!). */
    val SOLID: RenderLayer get() = if (ready()) SOLID_LAYER else VfxLayers.FLOW_ALPHA

    /** null = noch nicht geprüft. */
    private var valid: Boolean? = null

    /**
     * Sind die eigenen Pipelines gültig? Prüft beim ersten Aufruf mit Grafikgerät (am Render-Thread) beide Pipelines
     * einmal und merkt sich das Ergebnis; ein Fehler wird einmal geloggt. Ohne Grafikgerät (zu früh) `false`, ohne
     * sich das zu merken. `VfxManager.tick` ruft das beim ersten Tick in einer Welt, damit das Übersetzen nicht beim
     * ersten Treffer ruckelt.
     */
    fun ready(): Boolean {
        valid?.let { return it }
        if (!RenderSystem.isOnRenderThread()) return false
        val device = RenderSystem.tryGetDevice() ?: return false
        val ok = try {
            val energy = device.precompilePipeline(ENERGY_PIPELINE).isValid
            val solid = device.precompilePipeline(SOLID_PIPELINE).isValid
            if (!energy || !solid) {
                Hha.LOGGER.error(
                    "HHA: FX-Shader ließen sich nicht übersetzen (fx_energy {}, fx_solid {}); neue Effekte zeichnen vereinfacht über die Bestands-Ebenen.",
                    if (energy) "ok" else "FEHLER", if (solid) "ok" else "FEHLER",
                )
            } else {
                Hha.LOGGER.info("HHA: FX-Pipelines bereit (fx_energy, fx_solid).")
            }
            energy && solid
        } catch (e: Exception) {
            Hha.LOGGER.error("HHA: FX-Pipelines ließen sich nicht anlegen; neue Effekte zeichnen vereinfacht über die Bestands-Ebenen.", e)
            false
        }
        valid = ok
        return ok
    }
}

/**
 * Zeichenmodus im Vertex (Weiche im Shader `fx_energy`). Nur hinten anfügen. In Klammern, womit man ihn zeichnet
 * (siehe [FxDraw]); „Band“ = `ribbon`/`strip`/`tube`/`wall`, „Fläche“ = `flat`/`glow`, „Mesh“ = `mesh`.
 * Fortschritt = Parameter `progress` der Zeichner (Standard 1).
 */
object FxMode {
    /** Fließendes Energieband mit weißlichem Kern (Band). */
    const val RIBBON = 0
    /** Hülle um ein Wesen: Fresnel-Rand, fließendes Rauschen; Fortschritt < 1 löst sie mit Glutkante auf (Mesh). */
    const val SHELL = 1
    /** Weicher runder Schein (Fläche, meist [FxDraw.glow]). */
    const val GLOW = 2
    /** Wirbel mit Spiralarmen; Innenradius = dunkles Auge, Fortschritt = Öffnung (Fläche). */
    const val SWIRL = 3
    /** Stoßring mit weicher Front außen, Schweif nach innen; Innenradius = Ringbreite (Fläche). */
    const val SHOCK = 4
    /** Flammenzungen, steigen von v = 0 nach oben (Band, meist [FxDraw.wall]). */
    const val FLAME = 5
    /** Runenring: Randlinien, drehende Schlitze, Zeichen; Innenradius, Fortschritt = gezogener Winkelanteil (Fläche). */
    const val RUNE = 6
    /** Fleck am Boden, dunkelt ab; Fortschritt = Ausbreitung (Fläche). */
    const val STAIN = 7
    /** Riss im Raum: dunkler Kern, Glutrand, Haarrisse; Fortschritt = Öffnung (Band). */
    const val TEAR = 8
    /** Schutzkuppel: Fresnel-Rand, Bänder, heller Fuß; Fortschritt < 1 = Zerspringen (Mesh, [FxMeshes.DOME]). */
    const val DOME = 9
    /** Bodensprung: gezackt, dunkler Saum, Glutkern; Fortschritt = Hitze (Band, [FxDraw.strip]). */
    const val CRACK = 10
    /** Wasserwand mit laufender Krone; Fortschritt = Schaummenge (Band, [FxDraw.wall]). */
    const val WATER = 11
    /** Weicher Staub, matt; Fortschritt = Dichte (Fläche). */
    const val DUST = 12
    /** Blitzkanal: weißer Kern, weicher Hof (Band). */
    const val BOLT = 13

    /** Modi, die als Band gezeichnet werden (Querlage projektiv, siehe `FxDraw.kt`). */
    fun isBand(mode: Int): Boolean =
        mode == RIBBON || mode == FLAME || mode == TEAR || mode == CRACK || mode == WATER || mode == BOLT
}

/** Modi des Shaders `fx_solid` (für `FxDraw.mesh(..., solid = true)`). */
object FxSolidMode {
    /** Glatt schattiert in der Vertexfarbe. */
    const val PLAIN = 0
    /** Fels mit Glutadern; Alpha der Farbe = Hitze (0..1). */
    const val EMBER = 1
    /** Stein: weich gefleckt. */
    const val STONE = 2
}
