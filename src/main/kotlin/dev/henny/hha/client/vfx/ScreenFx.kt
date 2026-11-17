package dev.henny.hha.client.vfx

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Util
import net.minecraft.util.math.Vec3d
import kotlin.math.PI
import kotlin.math.exp

/**
 * Bildschirmweite 2D-Effekte über dem HUD (REF T8): Blitz, Vignette und Tönung. Das Rütteln ist seit Überarbeitung 3
 * eine Drehung in [CameraFx]; [shake] leitet nur weiter, [shakeOffset] verschiebt nichts mehr.
 *
 * Alles rechnet in Millisekunden (weich zwischen den Ticks). Die Deckel je Aufruf sitzen am Server (`Vfx.flash` 0,25,
 * `BossFx.vignette` 0,35); hier gelten nur die Skalierung mit „Verzerrungseffekte“ und die Obergrenze der Vignette.
 */
object ScreenFx {

    /** Höchste Deckkraft der Vignette (PLAN 2.2 / `BossFx.VIGNETTE_MAX`). */
    const val VIGNETTE_MAX = 0.35f

    /** Bänder des weichen Randes (je Band ein Verlauf, also ohne sichtbare Stufen). */
    const val VIGNETTE_BANDS = 14

    /** Tiefe des Randes als Anteil der kürzeren Bildseite. */
    const val VIGNETTE_DEPTH = 0.17f

    /** Periode des Herzschlags (Doppelschlag „lub-dub“, Vorbild Blood `HuntedOverlay`). */
    const val BEAT_PERIOD_MS = 2000L

    /** Anteil der Vignette, der mit dem Herzschlag schwingt (der Rest steht ruhig). */
    const val BEAT_DEPTH = 0.4f

    /** Ein- und Ausblenden einer Vignette höchstens so lang (ms), sonst ein Viertel bzw. 40 % der Dauer. */
    const val VIGNETTE_IN_MS = 400L
    const val VIGNETTE_OUT_MS = 800L

    /**
     * Eine zeitlich begrenzte Ebene: Farbe, Stärke, Beginn, Dauer und weiche Flanken (ms). [releaseAt] ≥ 0 = vorzeitig
     * beendet, blendet ab da über [fadeOut] aus.
     */
    private class Layer(
        val color: Int, val strength: Float, val start: Long, val duration: Long,
        val fadeIn: Long, val fadeOut: Long, val beat: Boolean,
    ) {
        var releaseAt = -1L

        /** Hüllkurve 0..1 zum Zeitpunkt [now] (Smoothstep-Flanken). */
        fun envelope(now: Long): Float {
            val t = now - start
            if (t < 0 || t >= duration) return 0f
            var e = 1f
            if (fadeIn > 0 && t < fadeIn) e = smooth(t / fadeIn.toFloat())
            val left = duration - t
            if (fadeOut > 0 && left < fadeOut) e = minOf(e, smooth(left / fadeOut.toFloat()))
            if (releaseAt >= 0) {
                val r = now - releaseAt
                e = if (r >= fadeOut) 0f else minOf(e, smooth(1f - r / fadeOut.coerceAtLeast(1L).toFloat()))
            }
            return e
        }

        fun done(now: Long): Boolean = envelope(now) <= 0f && now - start > 0
    }

    // Blitz (eigene Felder, Abfall kubisch)
    private var flashColor = 0
    private var flashStrength = 0f
    private var flashStart = 0L
    private var flashDuration = 1L

    /** Vignette der Spielaufrufe (`core.vignette`). */
    private var edge: Layer? = null

    /** Ersatzbild des Bildschirm-Passes (`ScreenPass` im Rückfall): Rand und Tönung. */
    private var passEdge: Layer? = null
    private var passTint: Layer? = null

    // ------------------------------------------------------------------ Rütteln (nur Weiterleitung)

    /** Rütteln in der alten Stärke-Einheit; läuft als Drehung über [CameraFx]. */
    fun shake(strength: Float, ticks: Int) = CameraFx.shake(strength, ticks)

    /** Früher der Positions-Versatz der Kamera (Camera-Mixin); seit Überarbeitung 3 immer [Vec3d.ZERO]. */
    @Suppress("UNUSED_PARAMETER")
    fun shakeOffset(tickDelta: Float): Vec3d = Vec3d.ZERO

    // ------------------------------------------------------------------ Blitz

    /**
     * Blitz in [color] (0xRRGGBB) mit Stärke [strength] über [ticks]: harter Anfang, kubischer Abfall `(1 − k)³`. Der
     * stärkere aus laufendem und neuem Blitz gewinnt. Gezeichnet mit `0,35 + 0,65 · distortionEffectScale`.
     */
    fun flash(color: Int, strength: Float, ticks: Int) {
        if (strength <= 0f || ticks <= 0) return
        val now = Util.getMeasuringTimeMs()
        if (strength >= flashNow(now)) {
            flashColor = color and 0xFFFFFF
            flashStrength = strength.coerceIn(0f, 1f)
            flashStart = now
            flashDuration = (ticks * 50L).coerceAtLeast(50L)
        }
    }

    private fun flashNow(now: Long): Float {
        if (flashStrength <= 0f) return 0f
        val k = (now - flashStart) / flashDuration.toFloat()
        if (k >= 1f) return 0f
        val r = 1f - k
        return flashStrength * r * r * r
    }

    // ------------------------------------------------------------------ Vignette und Tönung

    /**
     * Weicher Rand in [color] (0xRRGGBB) mit Deckkraft [strength] (≤ [VIGNETTE_MAX]) über [ticks]. Mit [beat] schlägt er
     * im Doppelschlag alle 2 s (ruhig, kein Blinken). Die stärkere aus laufender und neuer Vignette gewinnt.
     */
    fun vignette(color: Int, strength: Float, ticks: Int, beat: Boolean = false) {
        val now = Util.getMeasuringTimeMs()
        val layer = edgeLayer(color, strength, ticks, beat, now) ?: return
        val current = edge
        if (current == null || current.done(now) || layer.strength >= current.strength * current.envelope(now)) edge = layer
    }

    /** Beendet die Spiel-Vignette weich. */
    fun endVignette() {
        edge?.let { if (it.releaseAt < 0) it.releaseAt = Util.getMeasuringTimeMs() }
    }

    /**
     * Ersatzbild des Bildschirm-Passes (nur `ScreenPass`): Rand [edgeStrength] in [edgeColor] und Tönung
     * [tintStrength] in [tintColor] über [ticks], Flanken [fadeTicks] (wie die Hüllkurve des Passes). 0 = Teil entfällt.
     */
    fun passFallback(edgeColor: Int, edgeStrength: Float, tintColor: Int, tintStrength: Float, ticks: Int, fadeTicks: Int) {
        val now = Util.getMeasuringTimeMs()
        val duration = (ticks * 50L).coerceAtLeast(50L)
        val fade = (fadeTicks * 50L).coerceIn(0L, duration / 2)
        passEdge = if (edgeStrength > 0f) {
            Layer(edgeColor and 0xFFFFFF, edgeStrength.coerceIn(0f, VIGNETTE_MAX), now, duration, fade, fade, false)
        } else null
        passTint = if (tintStrength > 0f) {
            Layer(tintColor and 0xFFFFFF, tintStrength.coerceIn(0f, VIGNETTE_MAX), now, duration, fade, fade, false)
        } else null
    }

    /** Beendet das Ersatzbild des Passes weich. */
    fun endPassFallback() {
        val now = Util.getMeasuringTimeMs()
        passEdge?.let { if (it.releaseAt < 0) it.releaseAt = now }
        passTint?.let { if (it.releaseAt < 0) it.releaseAt = now }
    }

    private fun edgeLayer(color: Int, strength: Float, ticks: Int, beat: Boolean, now: Long): Layer? {
        if (strength <= 0f || ticks <= 0) return null
        val duration = (ticks * 50L).coerceAtLeast(50L)
        val fadeIn = minOf(VIGNETTE_IN_MS, duration / 4)
        val fadeOut = minOf(VIGNETTE_OUT_MS, duration * 2 / 5)
        return Layer(color and 0xFFFFFF, strength.coerceIn(0f, VIGNETTE_MAX), now, duration, fadeIn, fadeOut, beat)
    }

    // ------------------------------------------------------------------ Tick und Zeichnen

    fun tick() {
        val now = Util.getMeasuringTimeMs()
        if (edge?.done(now) == true) edge = null
        if (passEdge?.done(now) == true) passEdge = null
        if (passTint?.done(now) == true) passTint = null
        if (flashStrength > 0f && now - flashStart >= flashDuration) flashStrength = 0f
    }

    /** Aus dem HUD (und in Szenen aus `CinePlayer.renderOverlay`): Tönung, Rand, dann Blitz. */
    fun renderOverlay(context: DrawContext) {
        val now = Util.getMeasuringTimeMs()
        val w = context.scaledWindowWidth
        val h = context.scaledWindowHeight

        passTint?.let { layer ->
            val a = layer.strength * layer.envelope(now)
            if (a > 0.003f) context.fill(0, 0, w, h, withAlpha(layer.color or 0xFF000000.toInt(), a))
        }
        passEdge?.let { layer -> drawEdge(context, w, h, layer.color, layer.strength * layer.envelope(now)) }
        edge?.let { layer ->
            var a = layer.strength * layer.envelope(now)
            if (layer.beat) a *= (1f - BEAT_DEPTH) + BEAT_DEPTH * beat(now)
            drawEdge(context, w, h, layer.color, a)
        }

        val s = flashNow(now)
        if (s > 0.003f) {
            val scale = 0.35f + 0.65f * MinecraftClient.getInstance().options.distortionEffectScale.value.toFloat().coerceIn(0f, 1f)
            context.fill(0, 0, w, h, withAlpha(flashColor or 0xFF000000.toInt(), s * scale))
        }
    }

    /**
     * Doppelschlag 0..1 je [BEAT_PERIOD_MS]: zwei breite Gauß-Spitzen (die zweite schwächer). Breiter als bei Blood,
     * damit die Helligkeit nur langsam schwingt (PLAN 2.2: nichts über 2 Hz).
     */
    fun beat(now: Long): Float {
        val t = (now % BEAT_PERIOD_MS) / BEAT_PERIOD_MS.toFloat()
        val a = (t - 0.10f) * 9f
        val b = (t - 0.36f) * 9f
        return (exp(-(a * a)) + 0.6f * exp(-(b * b))).coerceIn(0f, 1f)
    }

    /**
     * Weicher Rand mit Spitzen-Deckkraft [alpha] am Bildrand, nach innen quadratisch abfallend: [VIGNETTE_BANDS] Bänder
     * je Seite, jedes ein Verlauf zwischen den Werten an seinen Kanten (glatt, keine Stufen). Ecken überlagern sich zu
     * einer runden Abdunklung.
     */
    private fun drawEdge(ctx: DrawContext, w: Int, h: Int, color: Int, alpha: Float) {
        if (alpha <= 0.003f) return
        val depth = minOf(w, h) * VIGNETTE_DEPTH
        val m = ctx.matrices
        // Oben, rechts, unten, links: jeweils ein Streifen „oben“ im gedrehten Koordinatensystem.
        for (side in 0 until 4) {
            m.pushMatrix()
            when (side) {
                1 -> { m.translate(w.toFloat(), 0f); m.rotate((PI / 2).toFloat()) }
                2 -> { m.translate(w.toFloat(), h.toFloat()); m.rotate(PI.toFloat()) }
                3 -> { m.translate(0f, h.toFloat()); m.rotate((-PI / 2).toFloat()) }
            }
            val length = if (side % 2 == 0) w else h
            for (i in 0 until VIGNETTE_BANDS) {
                val x0 = i / VIGNETTE_BANDS.toFloat()
                val x1 = (i + 1) / VIGNETTE_BANDS.toFloat()
                val y0 = (depth * x0).toInt()
                val y1 = (depth * x1).toInt()
                if (y1 <= y0) continue
                val a0 = alpha * (1f - x0) * (1f - x0)
                val a1 = alpha * (1f - x1) * (1f - x1)
                ctx.fillGradient(0, y0, length, y1, withAlpha(color or 0xFF000000.toInt(), a0), withAlpha(color or 0xFF000000.toInt(), a1))
            }
            m.popMatrix()
        }
    }

    fun reset() {
        flashStrength = 0f
        edge = null
        passEdge = null
        passTint = null
        CameraFx.reset()
    }

    private fun smooth(x: Float): Float {
        val k = x.coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }
}
