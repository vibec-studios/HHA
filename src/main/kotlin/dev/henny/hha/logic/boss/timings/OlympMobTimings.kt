package dev.henny.hha.logic.boss.timings

/**
 * Zeiten und Maße der normalen Gegner des Olymp (PLAN 5.6, Modell-Brief `tmp/overhaul2/models/olymp_gegner.md`).
 * **W** = Ansage, **S** = Grundschaden vor Skalierung (Ticks bzw. Blöcke). Von P00 angelegt, von P11 gefüllt. Die
 * Regeln der Posten (Abstände, Sperrzeit, Ruhe bei Bosskämpfen) stehen oben in `logic/world/OlympusLife.kt`.
 *
 * Tabelle PLAN 5.6 im Überblick:
 *
 * | Gegner | Leben | Angriffe (W / S) | Trick |
 * | --- | --- | --- | --- |
 * | Kurete | 60, Gruppe ≤ 3 | CLASH 12 → Speerstoß Linie 1,2 × 4 (12 / 8) · Schildschlag Kegel 50°, r 3 (10 / 6) | danach GUARD 30: von vorn × 0,3 |
 * | Greif | 80, ≤ 2 | Sturzflug Linie 2,4 × 14 (16 / 10), Sturz 12 · Prankenhieb Kegel 60°, r 3,5 (10 / 8) | LANDED 30, offen × 1,5; nach 6 s auf |
 * | Wächterin | 140, einzeln | Hammerarm Kreis r 3 (18 / 12), Arm steckt 18 · Dampfstoß Kegel 40°, r 6 (14 / 6) + Schub | Ventil hinten × 1,5 |
 */
object OlympMobTimings {
    // --- Gemeinsam ---
    /** Skalierung nach Spielerzahl in diesem Umkreis. */
    const val SCALE_RADIUS = 24.0
    /** Leine um den Posten: weiter weg bricht er ab, geht zurück und heilt. */
    const val LEASH = 28.0
    /** Wer so nah am Posten ist, kämpft mit (Ansagen, Ziele), sobald der Gegner aufmerksam ist. */
    const val ENGAGE_RADIUS = LEASH + 8.0
    /** Ist so lange kein Spieler im [ENGAGE_RADIUS], wird er wieder ruhig. */
    const val CALM_TICKS = 100
    /** Erholung zwischen zwei Angriffen (plus Zufall bis 5, vor Spielerzahl). */
    const val PAUSE = 20

    // --- Beute (PLAN 5.6) ---
    const val LOOT_NUGGETS_MIN = 2
    const val LOOT_NUGGETS_MAX = 5
    const val LOOT_COPPER_MIN = 1
    const val LOOT_COPPER_MAX = 3
    const val LOOT_INGOT_CHANCE = 0.05f
    const val LOOT_XP_MIN = 5
    const val LOOT_XP_MAX = 8

    /** Stoß, Schub, Sturz: Landet der Spieler dort nicht auf Boden (Inselrand), schiebt es nur so schwach. */
    const val EDGE_SAFE_PUSH = 0.2
    /** So tief darf der Boden hinter ihm liegen, damit der volle Schub gilt. */
    const val EDGE_SAFE_DEPTH = 10.0

    // --- Kurete (Himmelssoldat): 60 Leben, Gruppen bis 3 ---
    const val KURETE_HEALTH = 60.0
    const val KURETE_GROUP = 3
    const val KURETE_SPEED = 0.3
    /** Aufmerksam, wenn ein Spieler näher als so viel kommt (und sichtbar ist); die ganze Gruppe mit ihm. */
    const val KURETE_ALERT = 14.0
    /** Andere Kureten der Gruppe umkreisen in diesem Abstand, solange einer angreift. */
    const val KURETE_CIRCLE_MIN = 5.0
    const val KURETE_CIRCLE_MAX = 6.0
    /** Beim Umkreisen so viel Grad voraus. */
    const val KURETE_ORBIT_STEP = 25.0
    /** Die Marke der Gruppe: genommen, aber noch kein Angriff, verfällt sie nach so vielen Ticks. */
    const val TOKEN_GRACE = 40
    /** Wer gerade angegriffen hat, wartet so lange, bevor er die Marke wieder nehmen darf (wenn andere da sind). */
    const val TOKEN_TURN = 40
    /** CLASH: Speer an den Schild (die Ansage vor jedem Angriff), Schläge bei 3 und 8, Ende 12. */
    const val CLASH_HIT_1 = 3
    const val CLASH_HIT_2 = 8
    const val CLASH_END = 12
    /** Während CLASH dreht er sich höchstens so viel je Tick zum Ziel. */
    const val CLASH_TURN = 10.0
    /** Reichweite des ganzen Angriffs (Speerspitze); näher als [SHIELD_BASH_PICK] wird es der Schildschlag. */
    const val KURETE_REACH = 4.6
    const val SHIELD_BASH_PICK = 2.6
    /** THRUST: Linie 1,2 × 4, W 12, S 8. Animation: Ausholen bis 12, Stoß 12–14, Ende 26. */
    const val THRUST_WIDTH = 1.2f
    const val THRUST_LENGTH = 4f
    const val THRUST_W = 12
    const val THRUST_DAMAGE = 8f
    const val THRUST_HIT_END = 14
    const val THRUST_END = 26
    /**
     * SHIELD_BASH (nah): Kegel 50°, Radius 3, W 10, S 6. PLAN 5.6 schreibt „Kegel 50°“ ohne „halb“: voller
     * Öffnungswinkel 50°, also halber [SHIELD_BASH_HALF_ANGLE] = 25°. Animation: Ausholen bis 10, Stoß 10–12, Ende 22.
     */
    const val SHIELD_BASH_ANGLE = 50f
    const val SHIELD_BASH_HALF_ANGLE = 25f
    const val SHIELD_BASH_RADIUS = 3f
    const val SHIELD_BASH_W = 10
    const val SHIELD_BASH_DAMAGE = 6f
    const val SHIELD_BASH_PUSH = 0.6
    const val SHIELD_BASH_HIT_END = 12
    const val SHIELD_BASH_END = 22
    /** GUARD: nach dem eigenen Angriff 30 Ticks Schild hoch, von vorn × 0,3, Seite und Rücken voll. */
    const val GUARD_TICKS = 30
    const val GUARD_FACTOR = 0.3f
    const val GUARD_HALF_ANGLE = 60.0
    /** In der Deckung dreht er sich nur langsam nach (so lässt er sich flankieren). */
    const val GUARD_TURN = 2.5

    // --- Greif: 80 Leben, höchstens 2 je Posten ---
    const val GRIFFIN_HEALTH = 80.0
    const val GRIFFIN_GROUP = 2
    const val GRIFFIN_SPEED = 0.32
    /** Er bleibt auf seiner Säule, bis ein Spieler näher als so viel kommt. */
    const val GRIFFIN_TRIGGER = 16.0
    /** Wer ihn auf der Säule trifft und näher als so viel ist, wird sofort angeflogen. */
    const val GRIFFIN_RETALIATE = 28.0
    /** Steht der Spieler so nah neben ihm auf gleicher Höhe, kämpft er gleich am Boden statt zu stürzen. */
    const val GRIFFIN_BESIDE = 3.0
    /** DIVE: Linie 2,4 × 14, W 16, S 10, Sturz in 12 Ticks. Animation: Zielen bis 16, Sturz 16–28. */
    const val DIVE_WIDTH = 2.4f
    const val DIVE_LENGTH = 14f
    /** Kürzester Sturz; sonst so lang, dass die Linie 2,5 Blöcke hinter dem Ziel endet. */
    const val DIVE_MIN_LENGTH = 6f
    const val DIVE_OVERSHOOT = 2.5f
    const val DIVE_W = 16
    const val DIVE_DAMAGE = 10f
    const val DIVE_FALL = 12
    const val DIVE_END = 28
    /** Die Front des Sturzes reicht so weit vor seinen Leib: Wer in der Linie steht und davon erreicht ist, wird getroffen. */
    const val DIVE_HIT_RADIUS = 2.2
    const val DIVE_PUSH = 0.5
    /** LANDED: 30 Ticks am Boden, offen × 1,5. */
    const val LANDED_TICKS = 30
    const val LANDED_FACTOR = 1.5f
    /** SWIPE: Kegel 60° (halber Winkel), Radius 3,5, W 10, S 8. Animation: Ausholen bis 10, Hieb 10–12, Ende 24. */
    const val SWIPE_HALF_ANGLE = 60f
    const val SWIPE_RADIUS = 3.5f
    const val SWIPE_REACH = 4.0
    const val SWIPE_W = 10
    const val SWIPE_DAMAGE = 8f
    const val SWIPE_HIT_END = 12
    const val SWIPE_END = 24
    /** Nach 6 s am Boden (bzw. nach [GROUND_SWIPES] Hieben) fliegt er wieder auf. */
    const val GROUND_TICKS = 120
    const val GROUND_SWIPES = 2
    /** TAKEOFF 0–20 (ducken bis 5, dann Schläge); ohne Säule schwebt er so hoch über dem Boden. */
    const val TAKEOFF_DUCK = 5
    const val TAKEOFF_END = 20
    const val TAKEOFF_RISE = 0.25
    const val TAKEOFF_HEIGHT = 8.0
    /** Flug zurück zur Säule: Blöcke je Tick, Flughöhe über dem Ziel, höchstens so lange. */
    const val FLY_SPEED = 0.45
    const val FLY_CLEARANCE = 2.0
    const val FLY_MAX_TICKS = 240
    /** Säule: 6 hoch, oben 3 × 3 frei. */
    const val PERCH_HEIGHT = 6
    /** PERCH ist eine Schleife: vor Ablauf von `MiniBossKit.ACTION_MAX` (320) neu senden. */
    const val PERCH_REFRESH = 200
    /** Schwingenschlag im Flug. */
    const val WINGBEAT_PERIOD = 16

    // --- Goldene Wächterin: 140 Leben, einzeln ---
    const val AUTOMATON_HEALTH = 140.0
    const val AUTOMATON_SPEED = 0.18
    const val AUTOMATON_CHASE = 0.6
    /** Erwacht, wenn jemand näher als so viel kommt (oder beim ersten Treffer); WAKE 0–20. */
    const val WAKE_DISTANCE = 6.0
    const val WAKE_END = 20
    /** Ohne Spieler im Umkreis [SLEEP_DISTANCE] so lange: zurück zum Sockel, erstarren. */
    const val SLEEP_TICKS = 200
    const val SLEEP_DISTANCE = 20.0
    /** Rückweg zum Sockel: so lange gehen, das letzte Stück ([SLEEP_GLIDE_FROM]) gleitet sie mit [SLEEP_GLIDE] je Tick. */
    const val SLEEP_WALK_MAX = 300
    const val SLEEP_GLIDE_FROM = 3.0
    const val SLEEP_GLIDE = 0.12
    /**
     * HAMMER_ARM: Kreis Radius 3 vor ihr ([HAMMER_OFFSET]), W 18, S 12, Arm steckt 18 (sie dreht sich dann nicht:
     * Zeit fürs Ventil). Animation: Ausholen bis 18, Schlag 18–20, steckt bis 38.
     */
    const val HAMMER_RADIUS = 3f
    const val HAMMER_OFFSET = 1.2
    const val HAMMER_REACH = 3.6
    const val HAMMER_W = 18
    const val HAMMER_DAMAGE = 12f
    const val HAMMER_STUCK = 18
    const val HAMMER_HIT_END = 20
    const val HAMMER_END = 38
    /**
     * STEAM: Kegel 40°, Radius 6, W 14, S 6 und Schub. Animation: Ausholen bis 14, Stoß 14–20, Ende 34.
     * Schub 0,9 statt der 1,4 aus dem Gerüst: Auf schwebenden Inseln soll Dampf schieben, nicht über die Kante werfen.
     */
    const val STEAM_ANGLE = 40f
    const val STEAM_HALF_ANGLE = 20f
    const val STEAM_RADIUS = 6f
    const val STEAM_REACH_MIN = 2.5
    const val STEAM_REACH_MAX = 6.5
    const val STEAM_W = 14
    const val STEAM_DAMAGE = 6f
    const val STEAM_PUSH = 0.9
    const val STEAM_UP = 0.3
    const val STEAM_HIT_END = 20
    const val STEAM_END = 34
    /** Ventil auf dem Rücken: Treffer von hinten × 1,5. */
    const val VALVE_FACTOR = 1.5f
    const val VALVE_HALF_ANGLE = 60.0
}
