package dev.henny.hha.logic.boss.timings

/**
 * Neue Zeiten der Signatur: Hades (Fesseln). Gerüst P00 (Überarbeitung 4, PLAN 7.7), Besitzer P19. `BossTimings.kt` bleibt
 * unverändert; neue Zeiten und Zahlen nur hier, mit Kommentar und Verweis auf PLAN 4.3.
 *
 * PLAN 4.3, Zeile „Hades | Seelenketten“: Zug 0,65 für 20 Ticks → Fessel 40 Ticks, Leine r 3, drei Auswege. Die alten
 * Zug-Konstanten (`BossTimings.Hades.CHAINS_*`) bleiben dort stehen, benutzt wird nur noch [FETTER_BREAK_STAGGER].
 */
object HadesSigTimings {

    /** Ansage der Fessel: Die Kette fliegt, Kreis am Ziel (PLAN 4.1 „Greifen“: Ansage ≥ 16). */
    const val FETTER_W = 16

    /** So lange hält die Fessel höchstens (PLAN 4.1: höchstens 40 Ticks). */
    const val FETTER_HOLD = 40

    /** Leine um den Ankerpunkt (PLAN 4.1: Radius 3). */
    const val FETTER_RADIUS = 3.0

    /** Kreis der Ansage am Ziel (Briefing P19: r 1,5). */
    const val FETTER_CATCH_RADIUS = 1.5f

    /** Kerben des Teilziels „Fessel“; jeder Treffer zählt eine (Deckel 1/3). */
    const val FETTER_NOTCHES = 3

    /** Kette gerissen (Säule oder Teilziel): Hades taumelt so lange, offen (wie heute `CHAINS_BREAK_STAGGER`). */
    const val FETTER_BREAK_STAGGER = 30

    /** Reißt die Fessel von selbst: Ansage des Hiebs (Kegel wie Hieb A der Hiebfolge). */
    const val FETTER_HIT_W = 20

    /** Schaden dieses Hiebs (vor Skalierung, wie alle Hades-Zahlen). */
    const val FETTER_HIT_DAMAGE = 20f

    // --- Ergänzt von P19 (Briefing ließ diese Werte offen; keine Kampfzahl aus Ansage, Schaden oder Fenster) ---

    /** Alle so viele Ticks prüft die Fessel, ob eine Säule zwischen Hand und Gefesseltem steht (Briefing: 2). */
    const val FETTER_COVER_EVERY = 2

    /**
     * Grundleben des Teilziels (vor `BossScaling.health`). Jeder Treffer zählt ohnehin den vollen Deckel (1/3), die Zahl
     * bestimmt nur die Leiste.
     */
    const val FETTER_HEALTH = 3f

    /**
     * Das Teilziel hängt in der Mitte der Kette Hand → Anker, aber höchstens so weit vom Anker: Dann erreicht es auch
     * der Gefesselte selbst (Leine 3 + Reichweite 3), und Ausweg (b) bleibt allein nutzbar.
     */
    const val FETTER_KNOT_MAX = 5.0

    /** Reißt die Fessel von selbst, zieht Hades sich in so vielen Ticks (innerhalb der Ansage) an den Anker heran. */
    const val FETTER_HIT_CLOSE = 8

    /** … bis auf so viel Abstand vor den Anker (Kegelspitze; Radius des Kegels 8,5 wie Hieb A). */
    const val FETTER_HIT_REACH = 3.5
}
