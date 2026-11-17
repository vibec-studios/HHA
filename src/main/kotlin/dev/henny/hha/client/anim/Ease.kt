package dev.henny.hha.client.anim

import kotlin.math.PI
import kotlin.math.sin

/*
 * Animations-Helfer der Überarbeitung 3 (PLAN 2.3, REF T13): Port von HHA Blood `client/boss/BossAnim.kt`
 * (gleicher Autor, MIT). Nur Client, ohne Abhängigkeit zu einem Modell. Nach Welle 0 eingefroren.
 */

/** Kurven für Schlüsselbild-Spuren. Alle nehmen x in 0..1 (außerhalb wird geklemmt), außer [wobble] und [every]. */
object Ease {
    private fun c(x: Float) = x.coerceIn(0f, 1f)

    /** Smoothstep. */
    fun inOut(x: Float): Float { val t = c(x); return t * t * (3f - 2f * t) }
    fun linear(x: Float): Float = c(x)
    fun inQuad(x: Float): Float { val t = c(x); return t * t }
    fun inCubic(x: Float): Float { val t = c(x); return t * t * t }
    fun outQuad(x: Float): Float { val t = c(x); return 1f - (1f - t) * (1f - t) }
    fun outCubic(x: Float): Float { val u = 1f - c(x); return 1f - u * u * u }

    /** Schießt um etwa 10 % über das Ziel hinaus und setzt sich (ein Hieb, der durchzieht). */
    fun outBack(x: Float): Float = back(x, 1.70158f)

    /** Stärkeres Überschießen (etwa 20 %). */
    fun outBackBig(x: Float): Float = back(x, 2.8f)

    private fun back(x: Float, s: Float): Float { val u = c(x) - 1f; return 1f + (s + 1f) * u * u * u + s * u * u }

    /** Holt kurz zurück, bevor es losgeht (Ausholen innerhalb eines Abschnitts). */
    fun inBack(x: Float): Float { val t = c(x); val s = 1.4f; return (s + 1f) * t * t * t - s * t * t }

    /** Schwere Landung: fällt hinein, federt einmal, setzt sich. */
    fun outBounce(x: Float): Float {
        val t = c(x)
        return when {
            t < 0.6f -> { val u = t / 0.6f; u * u }
            t < 0.85f -> { val u = (t - 0.6f) / 0.25f; 1f - 0.12f * sin(u * PI.toFloat()) }
            else -> { val u = (t - 0.85f) / 0.15f; 1f - 0.03f * sin(u * PI.toFloat()) }
        }
    }

    /** 0 → 1 → 0 über x in 0..1 (Sinusbogen). */
    fun bump(x: Float): Float = sin(c(x) * PI.toFloat())

    /** Weiches Wackeln aus mehreren Frequenzen in −1..1 (lebendige Ruhe); [seed] entkoppelt Kanäle. [t] in Ticks. */
    fun wobble(t: Float, seed: Float): Float =
        sin(t * 0.131f + seed) * 0.5f + sin(t * 0.0713f + seed * 2.31f) * 0.32f + sin(t * 0.293f + seed * 0.71f) * 0.18f

    /** Ein kurzer Puls alle [period] Ticks (0..1, Spitze in der Mitte von [length]), fest je [seed]. */
    fun every(t: Float, period: Float, length: Float, seed: Float = 0f): Float {
        val k = ((t + seed * period) % period + period) % period
        return if (k < length) bump(k / length) else 0f
    }
}
