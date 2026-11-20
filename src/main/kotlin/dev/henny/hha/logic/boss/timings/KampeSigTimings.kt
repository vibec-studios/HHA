package dev.henny.hha.logic.boss.timings

/**
 * Neue Zeiten der Signatur: Kampe (Kettenschlösser). Gerüst P00 (Überarbeitung 4, PLAN 7.7), Besitzer P22. `BossTimings.kt` bleibt
 * unverändert; neue Zeiten und Zahlen nur hier, mit Kommentar und Verweis auf PLAN 4.3.
 *
 * Kampfzahlen (PLAN 4.3, Zeile „Kampe / Kettenschloss“): [SHACKLE_OPEN_F] (80 offen statt 60 nach drei Stößen),
 * [SHACKLE_NOTCHES] (drei Treffer), [SHACKLE_FALL_W] (Ansage des Aufschlags), seit P24 [SECOND_COIL_AT] (zweite Folge bei 25 %). Alles andere hier ist Darstellung oder
 * Geometrie-Abstand (keine Kampfzahl) und so benannt.
 */
object KampeSigTimings {

    // --- Kampfzahlen (PLAN 4.2/4.3, Briefing P22) ------------------------------------------------------------------

    /**
     * Zweite Sturzstoß-Folge bei so viel Leben (Anteil; die erste bleibt bei 50 % in `KampeEntity`). Nachtrag P24 zu
     * PLAN 4.3 (Freigabe A1, Wunsch P22 1): Ohne sie wird je Kampf höchstens ein Schloss golden.
     */
    const val SECOND_COIL_AT = 0.25f

    /** Kerben der Unterleiste eines Schlosses; jeder volle Treffer ist eine Kerbe (PLAN 4.2: „Drei Treffer“). */
    const val SHACKLE_NOTCHES = 3

    /** Kampe liegt nach dem Sturz so lange offen (PLAN 4.3: 80 statt 60). Faktor wie jedes Wächter-Fenster (`OPEN_FACTOR`). */
    const val SHACKLE_OPEN_F = 80

    /** Angesagter Aufschlag unter ihr: Kreis [SHACKLE_FALL_RADIUS], so viele Ticks bis zum Aufschlag (Briefing: 16). */
    const val SHACKLE_FALL_W = 16

    /**
     * Leben eines Schlosses solo **vor** `BossScaling.health` (die Gruppe skaliert beim Anlegen nach Spielerzahl). Deckel je
     * Treffer = 1/[SHACKLE_NOTCHES] der Leiste ([SHACKLE_CAP]); solo zählt jeder Treffer ab 4,9 Schaden voll (Steinschwert
     * aufwärts), also drei Treffer. Schwächere Treffer brauchen entsprechend mehr. Der Deckel hält auch die Mace klein.
     */
    const val SHACKLE_HEALTH = 9f

    /** Deckel je Treffer als Anteil der Leiste: knapp über 1/3, damit genau drei volle Treffer sprengen. */
    const val SHACKLE_CAP = 0.34f

    // --- Aufschlag (Briefing P22 Schritt 4) -----------------------------------------------------------------------

    /** Halbmesser des angesagten Aufschlags. */
    const val SHACKLE_FALL_RADIUS = 3f

    /** Rückstoß im Aufschlag (kein Schaden; Briefing: höchstens 1). */
    const val SHACKLE_FALL_KNOCK = 0.8
    const val SHACKLE_FALL_KNOCK_UP = 0.3

    /** So viele Ticks vor dem Aufschlag löst sie sich vom Titanen und fällt (Darstellung; der Aufschlag bleibt bei [SHACKLE_FALL_W]). */
    const val SHACKLE_FALL_DROP = 7

    /** Höher als so viel über dem Grubenboden gilt sie als „am Titanen“ und fällt; sonst wird sie am Boden umgeworfen. */
    const val SHACKLE_HANG_HEIGHT = 2.0

    // --- Darstellung ---------------------------------------------------------------------------------------------

    /** Effekt `kampe.titan_jolt`: Lebenszeit. */
    const val JOLT_LIFE = 26

    /** Kamera-Ruck beim Zucken des Titanen (Grad, Ticks, Reichweite; im Budget wie die Sturzstöße). */
    const val JOLT_KICK_PITCH = 1.2f
    const val JOLT_KICK_ROLL = 0.8f
    const val JOLT_KICK_TICKS = 6
    const val JOLT_KICK_RADIUS = 40f

    /** Effekt `kampe.shackle_glow`: höchstens so lange; der Client beendet ihn, sobald das Schloss nicht mehr offen ist. */
    const val GLOW_MAX_LIFE = 600

    /** Abbau der Ketten (Blöcke je Tick, oben zuerst, über `ArenaCollapse`): langsam genug, um das Fallen zu sehen. */
    const val CHAIN_FALL_PER_TICK = 6

    /** Ein gebrochenes Schloss bleibt so lange als gebrochene Leiste sichtbar (der Client blendet es nach 60 aus). */
    const val BROKEN_BAR_TICKS = 80

    /** Zeile „Die Ketten!“ so lange. */
    const val LINE_TICKS = 50

    // --- Geometrie-Abstände (keine Kampfzahlen; Maße kommen aus `TartarusArenas`) ---------------------------------

    /** Landepunkte bleiben so weit innerhalb des Grubenbodens (`TartarusArenas.PRISON_CORE`), gemessen ab Kampes Rand. */
    const val CORE_EDGE_MARGIN = 0.6

    /** Kein Landepunkt näher als so viel an einem Schloss (Wesen und Sockel). */
    const val LOCK_CLEARANCE = 2.6

    /** Ausweichsuche für einen freien Landepunkt: Ringe und Schritte je Ring. */
    const val FREE_SEARCH_RINGS = 5
    const val FREE_SEARCH_STEPS = 12

    /** Größe des Schloss-Wesens (`kampe.shackle`, P04-Darstellung). */
    const val LOCK_SIZE = 1.2f
}
