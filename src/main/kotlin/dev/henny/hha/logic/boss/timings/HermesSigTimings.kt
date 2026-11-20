package dev.henny.hha.logic.boss.timings

import dev.henny.hha.logic.boss.BossTimings

/**
 * Neue Zeiten der Signatur: Hermes (Botenlauf, Glocken, Säulenbruch, Empore). Besitzer P14 (Überarbeitung 4, PLAN 4.2,
 * 4.3). `BossTimings.kt` bleibt unverändert: Ansage, Abstand, Breite und Schaden des Botenlaufs sind die der
 * Wegkreuzungen (`BossTimings.Hermes.CROSS_*`), das Fenster nach Windstoß und Säulenbruch ist `STUMBLE_WINDOW` (40).
 */
object HermesSigTimings {

    /** Botenlauf: drei Stoßlinien (PLAN 4.3: „3 Linien + Glocken“ statt 2/2/3 Linien). */
    const val RUN_LINES = 3

    /**
     * Die Glocken sind golden ab der ersten Ansage bis zum Treffer der dritten Linie: drei Ansagen und zwei Abstände
     * der Wegkreuzungen (3 × 24 + 2 × 14 = 100 Ticks).
     */
    const val BELL_WINDOW = BossTimings.Hermes.CROSS_WINDUP * RUN_LINES + BossTimings.Hermes.CROSS_GAP * (RUN_LINES - 1)

    /** So lange läuft der Windstoß die dritte Linie entlang, bevor er Hermes aus der Bahn wirft. */
    const val GUST_TICKS = 12

    /** So lange (Ticks) läuft Hermes eine Linie des Botenlaufs ab (nur Bild: Der Treffer fällt mit dem Ende der Ansage). */
    const val RUN_TICKS = 6

    /** Gebrochene Säule: so viele Lagen bleiben als Stumpf (Sockel und Band). */
    const val COLUMN_STUMP = 2

    /** Säulenbruch: so viele Blöcke je Tick (oben zuerst; 66 Blöcke sind nach zwei Ticks fort). */
    const val COLUMN_BLOCKS_PER_TICK = 40

    /** Empore stürzt ab: Ansage ROOM (PLAN 3.1) vor dem Absturz; sie wird bei weitem Weg verlängert (Erreichbarkeit). */
    const val ARC_WARN = 60

    /** Empore stürzt ab: so viele Blöcke je Tick, oben zuerst (Briefing P14). */
    const val ARC_BLOCKS_PER_TICK = 40
}
