package dev.henny.hha.logic.boss.timings

import dev.henny.hha.world.TartarusArenas

/**
 * Neue Zeiten der Signatur: Kronos (Sanduhr-Strahl, Rückspulung, Alterung der Säulen). Gerüst P00 (Überarbeitung 4, PLAN 7.7), Besitzer P20. `BossTimings.kt` bleibt
 * unverändert; neue Zeiten und Zahlen nur hier, mit Kommentar und Verweis auf PLAN 4.3.
 *
 * Ablauf des Stillstands (PLAN 4.2, Briefing P20): Heben `BossTimings.Kronos.HALT_RAISE` (14) → Ansage [RAY_W] (ROOM,
 * verlängert bis `BossTimings.Min.ESCAPE_MAX`) → Zeit steht `BossTimings.Kronos.HALT_FROZEN` (40, Pass, Langsamkeit I für
 * `HALT_SLOWNESS` 30) und zugleich Schwenk [RAY_SWEEP] → Säulen altern [AGE_TICKS] → Fenster `HALT_WINDOW` (14).
 */
object KronosSigTimings {

    /** Ansage des Strahls (ROOM, Mindestzeit 40): Startkante, überstrichener Halbkreis und Drehsinn. PLAN 4.3 „Strahl“. */
    const val RAY_W = 40

    /** Schwenk über [RAY_ARC] Grad; daraus 3° je Tick ([RAY_DEG_PER_TICK]). */
    const val RAY_SWEEP = 60

    /** Halber Öffnungswinkel des voll aufgezogenen Strahls (Grad). */
    const val RAY_HALF_ANGLE = 30f

    /** Reichweite des Strahls ab Kronos' Hand (waagerecht; erreicht den Umgang r 44 von überall im Kern). */
    const val RAY_RADIUS = 46f

    /** Der überstrichene Bereich (Grad): Die Vorderkante läuft von der Startkante bis hierher, nie weiter. */
    const val RAY_ARC = 180f

    /** Drehgeschwindigkeit der Vorderkante: [RAY_ARC] / [RAY_SWEEP] = 3° je Tick. */
    const val RAY_DEG_PER_TICK = RAY_ARC / RAY_SWEEP

    /** Treffer des Strahls (SKY, magisch), vor × 1,2 × `POWER` 1,5 wie alle Kronos-Treffer. PLAN 4.3: ersetzt 3 × 22 Einschläge. */
    const val RAY_DAMAGE = 22f

    /** Zurückgespult an den Ort von vor so vielen Ticks. */
    const val REWIND_TICKS = 60

    /** Säulenstumpf nach dem Altern (Blöcke über dem Saalboden); Zahl aus dem Vertrag (PLAN 3.6). */
    const val PILLAR_STUMP = TartarusArenas.HALL_PILLAR_STUMP

    /** Die Säule zerfällt von oben nach unten in so vielen Ticks. */
    const val AGE_TICKS = 20

    /** Erster Stillstand des Kampfs: So lange steht der Hinweis, bevor die Statuszeile „gedeckt/gesehen“ übernimmt. */
    const val HINT_HOLD = 30

    /** Die Statuszeile während Ansage und Schwenk: so oft neu bestimmen (Ticks; `Cover.status` drosselt selbst). */
    const val STATUS_EVERY = 5
}
