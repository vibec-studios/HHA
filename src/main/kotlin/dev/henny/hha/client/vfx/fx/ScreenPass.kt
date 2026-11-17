package dev.henny.hha.client.vfx.fx

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.buffers.Std140SizeCalculator
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.DepthTestFunction
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.TextureFormat
import com.mojang.blaze3d.vertex.VertexFormat
import dev.henny.hha.Hha
import dev.henny.hha.client.HhaClientConfig
import dev.henny.hha.client.vfx.ScreenFx
import dev.henny.hha.logic.boss.BossFx
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gl.UniformType
import net.minecraft.client.render.VertexFormats
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.system.MemoryStack
import java.util.OptionalInt

/**
 * Bildschirm-Pass mit fünf Arten (PLAN 6.3, REF T3, Vorbild Void `VoidLens`): kopiert das fertige Weltbild in eine
 * eigene Textur und zeichnet `fx_screen` als Vollbild-Dreieck darüber. Wird in `VfxManager.render` zwischen Energie-
 * und Über-Phase gezeichnet (`WorldRenderEvents.END_MAIN`, also vor dem Unterwelt-Filter, der eigene Ziele aus dem
 * Pool nutzt – keine geteilte Textur).
 *
 * - Höchstens **ein** Pass ([pass] ist ein einzelnes Feld). Rang [RANK]: `GRAVITY_LENS > TIME_STOP > ECLIPSE >
 *   SHOCK_RIPPLE > HEAT_HAZE`; ein niedrigerer wartet nicht, er entfällt. Gleiche Art ersetzt (ohne neues Einblenden).
 * - Hüllkurve: weich ein über 10 % der Dauer (mindestens [MIN_FADE_TICKS]), ebenso weich aus; [clear] blendet aus.
 * - Stärke mal Vanilla-Option „Verzerrungseffekte“.
 * - **Rückfall** (Schalter `screen_fx` aus, Pipeline ungültig, „Verbesserte Transparenz“/Fabulous, Iris geladen, oder
 *   Verzerrungseffekte auf 0): je Art eine 2D-Entsprechung über [ScreenFx] mit gleicher Dauer.
 * - Die Pipeline ist **nicht global registriert** (ein Shaderfehler träfe sonst den ganzen Ressourcen-Reload) und wird
 *   per `precompilePipeline(...).isValid` geprüft, wie die Portal-Pipelines.
 * - Textur und Uniform-Puffer gibt es nur, solange ein Pass läuft; die Texturgröße folgt dem Fenster.
 */
object ScreenPass {

    /** Mindestlänge der weichen Flanken (Ticks). */
    const val MIN_FADE_TICKS = 3

    /** Anteil der Dauer für Ein- bzw. Ausblenden. */
    const val FADE_SHARE = 0.1f

    /** Unter diesem Faktor „Verzerrungseffekte“ gilt der Pass als abgeschaltet (Rückfall ohne Verzerrung). */
    const val MIN_DISTORTION = 0.01f

    /** Rang der Arten: höher verdrängt niedriger. */
    val RANK: Map<BossFx.Screen, Int> = mapOf(
        BossFx.Screen.GRAVITY_LENS to 5,
        BossFx.Screen.TIME_STOP to 4,
        BossFx.Screen.ECLIPSE to 3,
        BossFx.Screen.SHOCK_RIPPLE to 2,
        BossFx.Screen.HEAT_HAZE to 1,
    )

    /** Farbe je Art, wenn der Aufruf keine mitgibt (ARGB, Paletten aus `Palette`). */
    private val DEFAULT_TINT: Map<BossFx.Screen, Int> = mapOf(
        BossFx.Screen.SHOCK_RIPPLE to 0xFFDFF1FF.toInt(),
        BossFx.Screen.HEAT_HAZE to 0xFFF7841F.toInt(),
        BossFx.Screen.ECLIPSE to 0xFF9A86E8.toInt(),
        BossFx.Screen.TIME_STOP to 0xFFB8C4FF.toInt(),
        BossFx.Screen.GRAVITY_LENS to 0xFF6A7CFF.toInt(),
    )

    // ------------------------------------------------------------------ Rückfall-Werte (2D über ScreenFx)

    /** Finsternis: dunkle Tönung und dunkler Rand, je mal Stärke. */
    const val FALLBACK_ECLIPSE_TINT = 0.32f
    const val FALLBACK_ECLIPSE_EDGE = 0.35f

    /** Zeitstopp: blasse kühle Tönung und Rand. */
    const val FALLBACK_TIME_TINT = 0.12f
    const val FALLBACK_TIME_EDGE = 0.25f

    /** Stoß: ein kurzer Blitz dieser Stärke. */
    const val FALLBACK_SHOCK_FLASH = 0.08f

    /** Linse: dunkler Rand. */
    const val FALLBACK_LENS_EDGE = 0.35f

    // ------------------------------------------------------------------ Pipeline

    private val PIPELINE: RenderPipeline = RenderPipeline.builder(RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET)
        .withLocation(Hha.id("pipeline/fx_screen"))
        .withVertexShader(Hha.id("core/fx_screen"))
        .withFragmentShader(Hha.id("core/fx_screen"))
        .withSampler("SceneSampler")
        .withSampler("DepthSampler")
        .withUniform("HhaScreen", UniformType.UNIFORM_BUFFER)
        .withoutBlend()
        .withDepthWrite(false)
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withColorWrite(true, false)
        .withVertexFormat(VertexFormats.EMPTY, VertexFormat.DrawMode.TRIANGLES)
        .build()

    /** Uniform-Block `HhaScreen` (std140): CenterRadius, Params, Tint, Extra. */
    private val UNIFORM_SIZE = Std140SizeCalculator().putVec4().putVec4().putVec4().putVec4().get()

    /** Shader ließ sich nicht anlegen: einmal geloggt, danach immer Rückfall. */
    private var failed = false
    private var checked = false

    private val irisLoaded: Boolean by lazy { FabricLoader.getInstance().isModLoaded("iris") }

    private var copy: GpuTexture? = null
    private var copyView: GpuTextureView? = null
    private var uniforms: GpuBuffer? = null

    // ------------------------------------------------------------------ Zustand: höchstens ein Pass

    private class Pass(
        val kind: BossFx.Screen,
        /** Mitte aus dem Aufruf; bei der Linse zugleich die Mitte der sicheren Zone. */
        val origin: Vec3d,
        val radius: Float,
        val strength: Float,
        val ticks: Int,
        val safeRadius: Float,
        val follow: Entity?,
        val tint: Int,
        val world: ClientWorld?,
        var fallback: Boolean,
    ) {
        /** Flankenlänge (Ticks): 10 % der Dauer, mindestens [MIN_FADE_TICKS], höchstens die halbe Dauer. */
        val fade: Int = maxOf(MIN_FADE_TICKS, (ticks * FADE_SHARE).toInt()).coerceAtMost(maxOf(1, ticks / 2))
        var age = 0
        /** Ersetzt einen gleichartigen Pass: ohne neues Einblenden. */
        var skipFadeIn = false
        /** Alter, ab dem [clear] ausblendet; −1 = läuft regulär. */
        var releaseAge = -1
        /** Letzte bekannte Mitte (folgt [follow]). */
        var center: Vec3d = origin
        var lastCenter: Vec3d = origin

        val releasing: Boolean get() = releaseAge >= 0

        /** Hüllkurve 0..1 beim Alter [t] (Ticks, mit Zwischenbild). */
        fun envelope(t: Float): Float {
            if (t >= ticks) return 0f
            var e = 1f
            if (!skipFadeIn) e = smooth(t / fade)
            e = minOf(e, smooth((ticks - t) / fade))
            if (releaseAge >= 0) e = minOf(e, smooth(1f - (t - releaseAge) / fade))
            return e.coerceIn(0f, 1f)
        }

        fun finished(): Boolean = age >= ticks || (releaseAge >= 0 && age - releaseAge >= fade)
    }

    private var pass: Pass? = null

    // ------------------------------------------------------------------ Öffentlich

    /**
     * Startet bzw. ersetzt den Pass. [strength] 0..1 (am Server schon gedeckelt), [radius] in Blöcken, [ticks] Dauer.
     * [follow] = die Mitte folgt diesem Wesen (bei der Linse sein Schlund, sonst die Mitte seiner Box); [center] bleibt
     * dann die Mitte der sicheren Zone. [safeRadius] = Radius der sicheren Zone (nur Linse): ein senkrechter Zylinder
     * um [center], in dem nicht verzerrt wird. [tint] = Farbe ARGB (0 = Farbe der Art).
     */
    fun request(
        kind: BossFx.Screen, center: Vec3d, radius: Float, strength: Float, ticks: Int, safeRadius: Float = 0f,
        follow: Entity? = null, tint: Int = 0,
    ) {
        if (ticks <= 0 || strength <= 0f) return
        val current = pass
        // Höchstens einer: ein niedrigerer Rang entfällt, solange der laufende nicht schon ausblendet.
        if (current != null && !current.releasing && !current.finished() && rank(current.kind) > rank(kind)) return
        val world = MinecraftClient.getInstance().world
        val next = Pass(
            kind, center, radius.coerceAtLeast(0.1f), strength.coerceIn(0f, 1f), ticks, safeRadius.coerceAtLeast(0f),
            follow, if (tint == 0) DEFAULT_TINT.getValue(kind) else tint, world, fallback = !shaderAllowed(),
        )
        if (current != null && current.kind == kind && !current.releasing && current.envelope(current.age.toFloat()) > 0.5f) {
            next.skipFadeIn = true
        }
        next.center = next.followCenter(1f) ?: center
        next.lastCenter = next.center
        if (current?.fallback == true) ScreenFx.endPassFallback()
        pass = next
        if (next.fallback) startFallback(next)
    }

    /** Beendet den Pass [kind] weich; `null` = jeden. */
    fun clear(kind: BossFx.Screen? = null) {
        val p = pass ?: return
        if (kind != null && p.kind != kind) return
        if (!p.releasing) p.releaseAge = p.age
        if (p.fallback) ScreenFx.endPassFallback()
    }

    /** Läuft gerade ein Pass (auch als Rückfall)? Welcher? */
    fun active(): BossFx.Screen? = pass?.kind

    /** Je Client-Tick (aus `FxClient.tick`). */
    fun tick() {
        val p = pass ?: return releaseResources()
        if (p.world !== MinecraftClient.getInstance().world) {
            end(p)
            return
        }
        p.age++
        p.lastCenter = p.center
        p.followCenter(1f)?.let { p.center = it }
        if (p.finished()) {
            end(p)
            return
        }
        // Schalter oder Grafik mitten im Pass umgestellt: den Rest als 2D-Ersatz.
        if (!p.fallback && !shaderAllowed()) {
            p.fallback = true
            startFallback(p)
        }
    }

    /** Zeichnet den laufenden Pass (aus `VfxManager.render`, in `FxClient.guarded`). */
    fun render(frame: FxFrame) {
        val p = pass ?: return
        if (p.fallback || p.world !== MinecraftClient.getInstance().world || !shaderAllowed()) return
        val env = p.envelope(p.age + frame.tickDelta)
        if (env <= 0.001f) return
        val framebuffer = MinecraftClient.getInstance().framebuffer
        val colorTexture = framebuffer.colorAttachment ?: return
        val colorView = framebuffer.colorAttachmentView ?: return
        val depthView = framebuffer.depthAttachmentView ?: return

        ensureResources(colorTexture.getWidth(0), colorTexture.getHeight(0))
        val scene = copy ?: return
        val sceneView = copyView ?: return
        val buffer = uniforms ?: return

        val encoder = RenderSystem.getDevice().createCommandEncoder()
        encoder.copyTextureToTexture(colorTexture, scene, 0, 0, 0, 0, 0, scene.getWidth(0), scene.getHeight(0))

        val td = frame.tickDelta
        val center = p.lastCenter.lerp(p.center, td.toDouble()).subtract(frame.camPos)
        val safe = p.origin.subtract(frame.camPos)
        val strength = p.strength * distortionScale()
        val seconds = (p.age + td) / 20f
        val progress = ((p.age + td) / p.ticks).coerceIn(0f, 1f)
        val tint = p.tint
        MemoryStack.stackPush().use { stack ->
            val data = Std140Builder.onStack(stack, UNIFORM_SIZE)
                .putVec4(center.x.toFloat(), center.y.toFloat(), center.z.toFloat(), p.radius)
                .putVec4(p.kind.ordinal.toFloat(), strength, seconds, p.safeRadius)
                .putVec4(((tint shr 16) and 0xFF) / 255f, ((tint shr 8) and 0xFF) / 255f, (tint and 0xFF) / 255f, env)
                .putVec4(safe.x.toFloat(), safe.y.toFloat(), safe.z.toFloat(), progress)
                .get()
            encoder.writeToBuffer(buffer.slice(), data)
        }

        val transforms = RenderSystem.getDynamicUniforms()
            .write(RenderSystem.getModelViewMatrix(), Vector4f(1f, 1f, 1f, 1f), Vector3f(), Matrix4f())
        val sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR)
        val depthSampler = RenderSystem.getSamplerCache().get(FilterMode.NEAREST)

        encoder.createRenderPass({ "hha screen pass" }, colorView, OptionalInt.empty()).use { rp ->
            rp.setPipeline(PIPELINE)
            RenderSystem.bindDefaultUniforms(rp)
            rp.setUniform("DynamicTransforms", transforms)
            rp.setUniform("HhaScreen", buffer)
            rp.bindTexture("SceneSampler", sceneView, sampler)
            rp.bindTexture("DepthSampler", depthView, depthSampler)
            rp.draw(0, 3)
        }
    }

    // ------------------------------------------------------------------ Intern

    private fun rank(kind: BossFx.Screen): Int = RANK[kind] ?: 0

    /** Mitte des gefolgten Wesens: bei der Linse auf Schlundhöhe, sonst Mitte der Box. `null` ohne (gültiges) Wesen. */
    private fun Pass.followCenter(tickDelta: Float): Vec3d? {
        val e = follow ?: return null
        if (e.isRemoved) return null
        val share = if (kind == BossFx.Screen.GRAVITY_LENS) 0.8 else 0.5
        return e.getLerpedPos(tickDelta).add(0.0, e.height * share, 0.0)
    }

    private fun end(p: Pass) {
        if (p.fallback) ScreenFx.endPassFallback()
        if (pass === p) pass = null
        releaseResources()
    }

    /** Faktor der Vanilla-Option „Verzerrungseffekte“ (0..1). */
    private fun distortionScale(): Float =
        MinecraftClient.getInstance().options.distortionEffectScale.value.toFloat().coerceIn(0f, 1f)

    /** Darf der Shader laufen? Sonst Rückfall (PLAN 5). */
    private fun shaderAllowed(): Boolean {
        if (!HhaClientConfig.enabled("screen_fx")) return false
        if (MinecraftClient.usesImprovedTransparency()) return false
        if (irisLoaded) return false
        if (distortionScale() < MIN_DISTORTION) return false
        return pipelineReady()
    }

    /** Einmalige Prüfung der Pipeline (Muster `PortalVfx.ready`). */
    private fun pipelineReady(): Boolean {
        if (failed) return false
        if (checked) return true
        return try {
            val ok = RenderSystem.getDevice().precompilePipeline(PIPELINE).isValid
            if (ok) {
                checked = true
            } else {
                failed = true
                Hha.LOGGER.error("HHA: Shader hha:core/fx_screen ließ sich nicht laden; Bildschirm-Pässe laufen als 2D-Ersatz.")
            }
            ok
        } catch (e: Exception) {
            failed = true
            Hha.LOGGER.error("HHA: Pipeline des Bildschirm-Passes ließ sich nicht anlegen; Bildschirm-Pässe laufen als 2D-Ersatz.", e)
            false
        }
    }

    /** 2D-Entsprechung je Art über [ScreenFx], gleiche (Rest-)Dauer und Flanken. */
    private fun startFallback(p: Pass) {
        val left = (p.ticks - p.age).coerceAtLeast(1)
        val s = p.strength
        val tint = p.tint and 0xFFFFFF
        when (p.kind) {
            BossFx.Screen.ECLIPSE ->
                ScreenFx.passFallback(darken(tint, 0.12f), FALLBACK_ECLIPSE_EDGE * s, 0x07060E, FALLBACK_ECLIPSE_TINT * s, left, p.fade)
            BossFx.Screen.TIME_STOP ->
                ScreenFx.passFallback(darken(tint, 0.25f), FALLBACK_TIME_EDGE * s, 0xC8D0E0, FALLBACK_TIME_TINT * s, left, p.fade)
            BossFx.Screen.SHOCK_RIPPLE -> ScreenFx.flash(tint, FALLBACK_SHOCK_FLASH, left)
            BossFx.Screen.HEAT_HAZE -> Unit
            BossFx.Screen.GRAVITY_LENS -> ScreenFx.passFallback(0x05030C, FALLBACK_LENS_EDGE * s, 0, 0f, left, p.fade)
        }
    }

    private fun darken(rgb: Int, k: Float): Int {
        val r = (((rgb shr 16) and 0xFF) * k).toInt()
        val g = (((rgb shr 8) and 0xFF) * k).toInt()
        val b = ((rgb and 0xFF) * k).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    private fun ensureResources(width: Int, height: Int) {
        val device = RenderSystem.getDevice()
        val existing = copy
        if (existing == null || existing.getWidth(0) != width || existing.getHeight(0) != height) {
            copyView?.close()
            existing?.close()
            val usage = GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING
            val texture = device.createTexture({ "hha screen pass scene" }, usage, TextureFormat.RGBA8, width, height, 1, 1)
            copy = texture
            copyView = device.createTextureView(texture)
        }
        if (uniforms == null) {
            uniforms = device.createBuffer(
                { "hha screen pass uniforms" }, GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST, UNIFORM_SIZE.toLong(),
            )
        }
    }

    /** Ohne laufenden Pass keine Textur und kein Puffer. */
    private fun releaseResources() {
        if (copy == null && uniforms == null) return
        copyView?.close()
        copy?.close()
        uniforms?.close()
        copyView = null
        copy = null
        uniforms = null
    }

    private fun smooth(x: Float): Float {
        val k = x.coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }
}
