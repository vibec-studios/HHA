package dev.henny.hha.client.anim

/**
 * Überblenden aus der zuletzt **gezeigten** Pose (PLAN 2.1 Punkt 3, REF T14): Ein Modell liefert je Bild die Zielwerte
 * seiner Aktion (z. B. Winkel der Glieder) als Feld der Länge [size]; [blend] merkt sich die zuletzt gelieferten Werte
 * und blendet bei einem Aktionswechsel über `fadeTicks` weich (Smoothstep) von dort in die neuen Ziele – nicht aus der
 * vorigen Aktion „bei ihrem Alter“. Keine Abhängigkeit zu einem Modell; eine Instanz je Wesen.
 *
 * Werte werden linear gemischt (keine Winkel-Umbrüche): für Gliederwinkel in Radiant gedacht, die nicht über ±π springen.
 */
class PoseMemory(val size: Int) {

    private val shown = FloatArray(size)
    private val from = FloatArray(size)
    private val out = FloatArray(size)
    private var action = Int.MIN_VALUE
    private var start = 0f
    private var lastNow = Float.NaN
    private var has = false

    /**
     * Mischt in [target] (mindestens [size] Werte) und liefert das gezeigte Ergebnis. [action] = laufende Aktion,
     * [now] = Zeit in Ticks (z. B. Alter + Zwischenbild), [fadeTicks] = Dauer der Überblendung. [restart] = dieselbe
     * Aktion beginnt neu (ebenfalls aus der gezeigten Pose überblenden). Das gelieferte Feld wird beim nächsten Aufruf
     * wiederverwendet: sofort lesen, nicht aufheben.
     */
    fun blend(action: Int, target: FloatArray, now: Float, fadeTicks: Float, restart: Boolean = false): FloatArray {
        require(target.size >= size) { "PoseMemory: Ziel hat ${target.size} statt $size Werte" }
        val timeJumped = !lastNow.isNaN() && (now < lastNow - 0.01f || now - lastNow > 40f)
        lastNow = now
        if (!has || timeJumped) {
            // Erstes Bild (oder das Wesen kam neu): direkt in die Pose, keine Überblendung aus Nullen.
            has = true
            this.action = action
            start = now - fadeTicks
            target.copyInto(shown, 0, 0, size)
            shown.copyInto(out)
            return out
        }
        if (action != this.action || restart) {
            shown.copyInto(from)
            this.action = action
            start = now
        }
        val k = if (fadeTicks <= 0f) 1f else Ease.inOut((now - start) / fadeTicks)
        for (i in 0 until size) out[i] = from[i] + (target[i] - from[i]) * k
        out.copyInto(shown)
        return out
    }

    /** Läuft gerade eine Überblendung? */
    fun blending(now: Float, fadeTicks: Float): Boolean = has && now - start < fadeTicks

    /** Vergisst alles; das nächste [blend] springt direkt in sein Ziel. */
    fun reset() {
        has = false
        action = Int.MIN_VALUE
        lastNow = Float.NaN
    }
}
