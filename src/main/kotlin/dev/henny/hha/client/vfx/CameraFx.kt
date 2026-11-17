package dev.henny.hha.client.vfx

import dev.henny.hha.client.HhaClientConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Util
import net.minecraft.util.math.RotationAxis
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kamera-Rütteln und -Ruck als **Drehung** (PLAN 2.2, REF T7, Blood-Bauart `CameraShake`). [apply] läuft am Ende von
 * `GameRenderer.tiltViewWhenHurt` (Mixin) und dreht die Ansicht um Rollen, Nicken und Gieren; die Kamera verlässt
 * dabei nie ihren Platz (kein Blick in die Wand wie beim alten Positions-Wackeln).
 *
 * Alles rechnet in Millisekunden und läuft damit zwischen den Ticks weich. Die Deckel je Aufruf sitzen am Server
 * (`Vfx.shake`, `BossFx.kick`); hier gilt nur die Summen-Grenze [MAX_DEG] und die Skalierung
 * Schalter `screen_shake` (aus = 0) × Vanilla-Option „Verzerrungseffekte“ (`distortionEffectScale`).
 */
object CameraFx {

    /** Höchste Drehung aus Rütteln und allen Rucken zusammen (Grad, Betrag über alle drei Achsen). */
    const val MAX_DEG = 6f

    /** Umrechnung der alten `Vfx.shake`-Stärke: Stärke 5 (Server-Deckel) ≙ 2,4° Spitze, linear. */
    const val DEG_PER_STRENGTH = 2.4f / 5f

    /** Anteil der Ruck-Dauer, in dem die Kamera „hin“ fährt; der Rest ist das weiche Zurück. */
    const val KICK_ATTACK = 0.2f

    /** Höchstens so viele Rucke laufen überlagert; ein neuer verdrängt den ältesten. */
    const val MAX_KICKS = 4

    /** Rütteln: Spitze in Grad, Beginn und Dauer in ms. */
    private var shakePeak = 0f
    private var shakeStart = 0L
    private var shakeDuration = 1L

    private class Kick(val pitch: Float, val yaw: Float, val roll: Float, val start: Long, val duration: Long)

    private val kicks = ArrayList<Kick>(MAX_KICKS)

    /**
     * Zittern mit Stärke [strength] (Maß wie `Vfx.shake`, 5 ≙ 2,4°) über [ticks]. Das stärkere aus laufendem und neuem
     * Rütteln gewinnt; Abfall `(1 − k)²`.
     */
    fun shake(strength: Float, ticks: Int) {
        if (strength <= 0f || ticks <= 0) return
        val now = Util.getMeasuringTimeMs()
        val peak = (strength * DEG_PER_STRENGTH).coerceAtMost(MAX_DEG)
        if (peak >= shakeNow(now)) {
            shakePeak = peak
            shakeStart = now
            shakeDuration = (ticks * 50L).coerceAtLeast(50L)
        }
    }

    /**
     * Gerichteter Ruck in Grad über [ticks]: schnell hin im ersten Fünftel ([KICK_ATTACK]), weich zurück. Positives
     * [pitch] nickt nach unten (wie `Entity.pitch`), positives [yaw] dreht nach rechts. Rucke überlagern sich mit dem
     * Rütteln; die Summe bleibt unter [MAX_DEG].
     */
    fun kick(pitch: Float, yaw: Float = 0f, roll: Float = 0f, ticks: Int = 6) {
        if (ticks <= 0 || (pitch == 0f && yaw == 0f && roll == 0f)) return
        val now = Util.getMeasuringTimeMs()
        kicks.removeIf { now - it.start >= it.duration }
        if (kicks.size >= MAX_KICKS) kicks.removeAt(0)
        kicks.add(Kick(pitch, yaw, roll, now, (ticks * 50L).coerceAtLeast(50L)))
    }

    /** Laufende Rütteln-Spitze (Grad) zum Zeitpunkt [now]. */
    private fun shakeNow(now: Long): Float {
        if (shakePeak <= 0f) return 0f
        val k = (now - shakeStart) / shakeDuration.toFloat()
        if (k >= 1f) return 0f
        return shakePeak * (1f - k) * (1f - k)
    }

    /** Hüllkurve eines Rucks bei Anteil [k] (0..1): schnell hin (`1 − (1 − x)²`), weich zurück (Smoothstep). */
    fun kickEnvelope(k: Float): Float {
        if (k <= 0f || k >= 1f) return 0f
        if (k < KICK_ATTACK) {
            val x = k / KICK_ATTACK
            return 1f - (1f - x) * (1f - x)
        }
        val x = (k - KICK_ATTACK) / (1f - KICK_ATTACK)
        return 1f - x * x * (3f - 2f * x)
    }

    /** Wirksamer Faktor: `screen_shake` aus = 0, sonst `distortionEffectScale` (0..1). */
    fun scale(): Float {
        if (!HhaClientConfig.enabled("screen_shake")) return 0f
        return MinecraftClient.getInstance().options.distortionEffectScale.value.toFloat().coerceIn(0f, 1f)
    }

    /** Dreht die Weltansicht (aus dem Mixin). Ohne Spieler oder Welt (Menü): nichts. */
    @JvmStatic
    fun apply(matrices: MatrixStack, tickProgress: Float) {
        val client = MinecraftClient.getInstance()
        if (client.player == null || client.world == null) return
        if (shakePeak <= 0f && kicks.isEmpty()) return
        val factor = scale()
        if (factor <= 0f) return
        val now = Util.getMeasuringTimeMs()

        var pitch = 0f
        var yaw = 0f
        var roll = 0f

        // Rütteln: zwei gegenläufige Sinus je Achse (Blood), Rollen am stärksten, Gieren am schwächsten.
        val s = shakeNow(now)
        if (s > 0.001f) {
            val t = now / 1000.0
            roll += (sin(t * 47.0) * 0.6 + sin(t * 83.0) * 0.4).toFloat() * s
            pitch += (sin(t * 61.0 + 1.3) * 0.6 + sin(t * 97.0) * 0.4).toFloat() * s * 0.5f
            yaw += sin(t * 53.0 + 2.1).toFloat() * s * 0.35f
        }

        // Rucke: gerichtet, überlagert.
        for (kick in kicks) {
            val e = kickEnvelope((now - kick.start) / kick.duration.toFloat())
            pitch += kick.pitch * e
            yaw += kick.yaw * e
            roll += kick.roll * e
        }

        pitch *= factor
        yaw *= factor
        roll *= factor
        val total = sqrt(pitch * pitch + yaw * yaw + roll * roll)
        if (total < 0.001f) return
        if (total > MAX_DEG) {
            val k = MAX_DEG / total
            pitch *= k
            yaw *= k
            roll *= k
        }
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(roll))
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(pitch))
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw))
    }

    /** Je Client-Tick: abgelaufene Rucke und Rütteln aufräumen (das Bild rechnet ohnehin in ms). */
    fun tick() {
        val now = Util.getMeasuringTimeMs()
        kicks.removeIf { now - it.start >= it.duration }
        if (shakePeak > 0f && now - shakeStart >= shakeDuration) shakePeak = 0f
    }

    /** Verbindungsende: alles sofort aus. */
    fun reset() {
        kicks.clear()
        shakePeak = 0f
    }
}
