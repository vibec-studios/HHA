package dev.henny.hha.client.anim

import kotlin.math.min

/**
 * Gedämpfte Feder für Nachschwingen (Umhang, Bart, Kette, Schwanz, Federn, Kiefer). [freq] in rad/Tick, [damp] 1 =
 * kritisch gedämpft, kleiner als 1 schwingt nach. Port aus HHA Blood `BossAnim.kt` (MIT). Eine Instanz je Wesen und Teil.
 */
class Spring(private val freq: Float, private val damp: Float) {
    /** Aktuelle Lage. */
    var x = 0f
        private set

    /** Aktuelle Geschwindigkeit (Einheiten je Tick). */
    var v = 0f
        private set
    private var started = false

    /**
     * Läuft [dt] Ticks (höchstens 4) auf [target] zu und liefert die neue Lage. Der erste Aufruf springt auf [target].
     * Intern in Schritten von höchstens 0,25 Ticks (stabil auch bei großen Bildabständen).
     */
    fun step(target: Float, dt: Float): Float {
        if (!started) { x = target; v = 0f; started = true; return x }
        var left = dt.coerceIn(0f, 4f)
        while (left > 1e-4f) {
            val h = min(left, 0.25f)
            val a = freq * freq * (target - x) - 2f * damp * freq * v
            v += a * h
            x += v * h
            left -= h
        }
        return x
    }

    /** Stoß: ändert die Geschwindigkeit um [dv] (Treffer, Landung, Stopp). */
    fun kick(dv: Float) { v += dv }

    /** Vergisst den Zustand; der nächste [step] springt wieder auf sein Ziel. */
    fun reset() { started = false; x = 0f; v = 0f }
}
