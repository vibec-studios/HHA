package dev.henny.hha.logic.boss.timings

/**
 * Neue Zeiten der Signatur: Tartaros-Wächter (Kettenpfähle, Fels, Fesseln, Schleusen). Gerüst P00 (Überarbeitung 4, PLAN 7.7), Besitzer P21. `BossTimings.kt` bleibt
 * unverändert; neue Zeiten und Zahlen nur hier, mit Kommentar und Verweis auf PLAN 4.3.
 *
 * Namensregeln wie in `BossTimings`: `_W` Ansage, `_F` Fenster. Alles in Ticks bzw. Blöcken. Jede Zahl gilt nur, wenn
 * der Wächter in seiner Arena kämpft (`GuardianArenas.of`); ohne Arena läuft der Kampf wie vor Überarbeitung 4.
 */
object GuardianSigTimings {

    // --- Kerkermeister: Kettenpfähle (PLAN 4.2, 4.3 „Kette am Pfahl – 80 offen") -------------------------------

    /** So lange hängt die Kette an einem Pfahl (Pfahl golden, Zustand 1), bevor er sie löst. */
    const val POST_HOOK = 60

    /** Nach dem Rechtsklick kippt der Pfahl (Zustand 2); so viele Ticks später reißt es ihn zu Boden. */
    const val POST_PULL = 20

    /** Fenster, wenn ihn der umgelegte Pfahl zu Boden reißt (Faktor `OPEN_FACTOR` der Wächter, 1,5). */
    const val POST_OPEN_F = 80

    /** Läuft die Kettenlinie näher als so weit (waagerecht, Mitte des Pfahls) an einem stehenden Pfahl vorbei, hakt sie ein. */
    const val POST_HOOK_RANGE = 3.0

    /**
     * Rundschlag mit Höhenband über dem Hofboden: so weit nach oben (Blöcke über den Füßen). Die Hofterrasse liegt genau
     * +3 und soll vor ihm schützen (PLAN 3.7); das Standardband (+3 einschließlich) träfe sie noch.
     */
    const val ROUND_BAND_ABOVE = 2.0

    // --- Sisyphos: den Stein zurückrollen (PLAN 4.2, 4.3 „30 Leistenpunkte + 60 Taumeln") -------------------------

    /** Ansage der goldenen Rücklauf-Linie, dann rollt der Fels. */
    const val ROLL_BACK_W = 16

    /** Höchstens ein Rücklauf je so viele Ticks (gezählt ab dem dritten Schlag); sonst zerfällt der Fels wie bisher. */
    const val ROLL_BACK_COOLDOWN = 400

    /** Kerben des liegenden Felsens: jeder gezählte Treffer eine. */
    const val BOULDER_NOTCHES = 3

    /** Leben des Teilziels solo vor Skalierung (gleich, weil jeder Treffer genau eine Kerbe nimmt). */
    const val BOULDER_HEALTH = 3f

    /** Deckel je Treffer: etwas über einem Drittel, damit drei Treffer sicher brechen (Rundung). */
    const val BOULDER_CAP = 0.34f

    /** Größe des Fels-Teilziels (Modell 1,1 Block × Größe ≈ der fliegende Fels, Trefferbox 1,2 × 1,6 × Größe). */
    const val BOULDER_SIZE = 1.4f

    /** Höchstens so viel Grad wird die Richtung „vom Schläger weg" auf Sisyphos zu gedreht. */
    const val ROLL_BACK_TURN = 25.0

    /** Die Linie reicht so weit über Sisyphos hinaus (er bewegt sich in der Ansage). */
    const val ROLL_BACK_OVERSHOOT = 4.0

    /** Längste Rücklauf-Linie (wie der Fluch-Fels). */
    const val ROLL_BACK_MAX = 40.0

    /** Gefahr-Breite der Rücklauf-Linie (wie der rollende Fels) und Breite des goldenen Kerns darin. */
    const val ROLL_BACK_WIDTH = 4f
    const val ROLL_BACK_GOLD_WIDTH = 1.2f

    // --- Ixion: die Fesseln (PLAN 4.2, 4.3 „Kippen 30 → 40 Ticks, 2 Treffer je Fessel") ---------------------------

    /** Kippen des Rads mit Fesseln (vorher `BossTimings.Ixion.WOBBLE_WINDOW` 30). */
    const val TILT_TICKS = 40

    /** Deckel je Treffer an einer Fessel: die Hälfte, also sprengen zwei Treffer sie. */
    const val FETTER_CAP = 0.5f

    /** Leben einer Fessel solo vor Skalierung (gleich, jeder gezählte Treffer nimmt genau den Deckel). */
    const val FETTER_HEALTH = 2f

    /** Kerben je Fessel (zwei Treffer). */
    const val FETTER_NOTCHES = 2

    /** Zahl der Fesseln (wie `BossTimings.Ixion.FETTERS`). */
    const val FETTERS = 3

    /**
     * Die Feuerspur am Boden trifft nur bis so hoch über dem Kampfring (Höhenband); die Randstufe (+1) ist sicher.
     * Vorher: jede Höhe bis 1,6 über der Spur.
     */
    const val TRAIL_BAND_ABOVE = 0.9

    /** Quelle des Speichenfeuers für die Deckung: so hoch über der Nabe (Boden). */
    const val SPOKES_EYE = 1.0

    // --- Tantalos: die Schleusen (PLAN 4.2, 4.3 „Trinken abbrechen: zusätzlich Schleuse schließen") ---------------

    /** Nach dem Rechtsklick dreht das Rad so lange, dann bricht das Trinken ab (Rad schon auf Zustand 2). */
    const val SLUICE_TURN = 20

    /** Fenster, wenn die Schleuse sein Trinken abbricht (wie `BossTimings.Tantalus.PARCHED`, Faktor 1,5). */
    const val SLUICE_OPEN_F = 60

    /** Die oberste Wasserlage verschwindet über so viele Ticks von außen nach innen (ruhiges Absinken statt Sprung). */
    const val WATER_DRAIN = 12

    /**
     * Höhe des Dreizack-Strahls für die Deckung (über dem Absatz, auf dem er steht). Stamm und Felssäulen beginnen erst
     * auf o.y + 1 (über Beckenrand und Rand); tiefer träfe der Strahl die Stufenkanten statt der Deckung.
     */
    const val TRIDENT_EYE = 2.5
}
