package dev.henny.hha.client.vfx.fx

import kotlin.math.floor

/**
 * Zeitfaktor laufender neuer Effekte (`core.time_scale`, Kronos' Zeitstopp; PLAN 6.4). [set] friert ein bzw.
 * verlangsamt für `ticks` Ticks, danach läuft der Faktor in [RELEASE_TICKS] Ticks weich (Smoothstep) auf 1 zurück.
 *
 * Rechnung: Die Uhr zählt die „verlorene Zeit“ `lost` (je Tick 1 − Faktor, zwischen zwei Ticks mit dem laufenden
 * Faktor fortgeschrieben, also stetig). Ein Effekt merkt sich beim ersten Abfragen den Stand `lost` (in
 * `FxEffect.clockBase`); sein sichtbares Alter ist echtes Alter − (lost jetzt − lost beim Start). Ohne Verlangsamung
 * ist das exakt das echte Alter. Eingefrorene Effekte altern nicht und laufen daher auch nicht ab (`FxEffect.expired`).
 * Gilt nur für `FxEffect`; der Bestand läuft weiter in Echtzeit.
 */
object FxClock {

    /** Dauer der Rückkehr auf 1 nach Ablauf der Haltezeit (Ticks). */
    const val RELEASE_TICKS = 6

    /** Faktor während der Haltezeit. */
    private var held = 1f

    /** Verbleibende Haltezeit (Ticks). */
    private var holdLeft = 0

    /** Ticks seit Beginn der Rückkehr (0..RELEASE_TICKS), −1 = keine Rückkehr. */
    private var releaseAge = -1

    /** Verlorene Zeit seit Start (Ticks), nur wachsend. */
    private var lost = 0.0

    /** Aktueller Faktor (1 = normal, 0 = eingefroren). */
    val scale: Float
        get() = when {
            holdLeft > 0 -> held
            releaseAge >= 0 -> {
                val k = (releaseAge.toFloat() / RELEASE_TICKS).coerceIn(0f, 1f)
                held + (1f - held) * (k * k * (3f - 2f * k))
            }
            else -> 1f
        }

    /** Setzt den Faktor [scale] (0..1) für [ticks] Ticks; ein neuer Aufruf ersetzt den alten. */
    fun set(scale: Float, ticks: Int) {
        held = scale.coerceIn(0f, 1f)
        holdLeft = ticks.coerceAtLeast(0)
        releaseAge = if (holdLeft > 0) -1 else 0
    }

    /** Je Client-Tick (aus `FxClient.tick`). */
    fun tick() {
        lost += (1f - scale).toDouble()
        when {
            holdLeft > 0 -> {
                holdLeft--
                if (holdLeft == 0) releaseAge = 0
            }
            releaseAge >= 0 -> {
                releaseAge++
                if (releaseAge >= RELEASE_TICKS) releaseAge = -1
            }
        }
    }

    /** Verbindungsende: zurück auf 1 (die verlorene Zeit bleibt stehen, damit laufende Effekte nicht springen). */
    fun reset() {
        held = 1f
        holdLeft = 0
        releaseAge = -1
    }

    /**
     * Sichtbares Alter des Effekts [effect] bei echtem Alter [age] (Ticks, mit Zwischenbild-Anteil als Nachkomma).
     * Beim ersten Aufruf je Effekt wird dessen Startstand gemerkt.
     */
    fun age(effect: FxEffect, age: Float): Float {
        val partial = age - floor(age)
        val lostNow = lost + partial * (1f - scale)
        if (effect.clockBase.isNaN()) effect.clockBase = lostNow
        return (age - (lostNow - effect.clockBase)).toFloat().coerceAtLeast(0f)
    }
}
