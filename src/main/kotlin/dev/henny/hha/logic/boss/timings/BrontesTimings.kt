package dev.henny.hha.logic.boss.timings

/**
 * Zeiten und Maße für Brontes (PLAN 5.3, Modell-Brief `tmp/overhaul2/models/brontes.md`). **W** = Ansage, **S** =
 * Grundschaden vor Skalierung, **F** = Fenster danach (Ticks bzw. Blöcke). Von P00 angelegt; gehört danach P08.
 */
object BrontesTimings {
    // --- Stärke (PLAN 4.1) ---
    const val HEALTH = 750.0
    const val TOUGHNESS = 2.0f
    const val EFFECTIVE_HEALTH = 1500f
    /** Phase II „Die Esse brennt“ bei 50 %. */
    const val PHASE_2 = 0.5f
    /** Keine Vanilla-Rüstung: Sie würde das effektive Leben verfälschen (Rechnung über Zähigkeit, PLAN 4.1). */
    const val ARMOR = 0.0
    const val ATTACK = 12.0
    const val SPEED = 0.25
    /** Er geht langsam. */
    const val CHASE_SPEED = 0.8
    const val PAUSE = 24

    // --- Konter (PLAN 4.3): TONGS_UP ---
    const val COUNTER_DAMAGE = 16f
    /** Heben bis 12, Schnappen 12–14, Halten bis 40. */
    const val TONGS_RAISE = 12
    const val TONGS_HIT = 12
    const val TONGS_HIT_END = 14
    const val TONGS_END = 40

    // --- 1 HAMMER: Hammerschlag, danach Riss ---
    const val HAMMER_WIDTH = 3f
    const val HAMMER_LENGTH = 10f
    const val HAMMER_W = 20
    const val HAMMER_DAMAGE = 22f
    /** Der Riss läuft bis 18 weiter, eigene Ansage W 8, S 12. */
    const val CRACK_LENGTH = 18f
    const val CRACK_W = 8
    const val CRACK_DAMAGE = 12f
    /** Hammer steckt: F 28. */
    const val HAMMER_F = 28
    /** Breite des Risses (PLAN nennt keine; schmaler als der Hammer). */
    const val CRACK_WIDTH = 2f
    /**
     * Die Riss-Ansage erscheint so viele Ticks **vor** dem Hammertreffer, damit sie die Mindestdauer eines Folgehiebs
     * (10) hat und trotzdem wie im Plan 8 Ticks nach dem Hammer trifft (Ansage 10 = [CRACK_W] + [CRACK_LEAD]).
     */
    const val CRACK_LEAD = 2
    /** Wo der Hammerkopf aufschlägt (Blöcke vor seiner Mitte), für Einschlag und Ton. */
    const val HAMMER_HEAD = 5.0
    /** Animation: Ausholen halten bis 20, Schlag 20–22, steckt bis 50, Ende 56. */
    const val HAMMER_HIT = 20
    const val HAMMER_HIT_END = 22
    const val HAMMER_STUCK_END = 50
    const val HAMMER_END = 56

    // --- 6 DOUBLE_HAMMER (Phase II): zweiter Hieb 14 Ticks später, höchstens ± 25° ---
    const val DOUBLE_GAP = 14
    const val DOUBLE_MAX_TURN = 25.0
    /** Animation: Schlag 20, zweiter Schlag 34, steckt bis 62, Ende 68. */
    const val DOUBLE_HIT = 20
    const val DOUBLE_HIT2 = 34
    const val DOUBLE_STUCK_END = 62
    const val DOUBLE_END = 68

    // --- 2 SWING: Rundschwung (nah) ---
    const val SWING_HALF_ANGLE = 100f
    const val SWING_RADIUS = 7f
    const val SWING_W = 16
    const val SWING_DAMAGE = 18f
    const val SWING_F = 18
    /** Animation: Ausholen bis 16, Schwung 16–19, Nachlauf bis 37. */
    const val SWING_HIT = 16
    const val SWING_HIT_END = 19
    const val SWING_END = 37
    /** Rückstoß des Schwungs (waagerecht, nach oben). */
    const val SWING_KNOCK = 0.8
    const val SWING_LIFT = 0.35

    // --- 3 TOSS: Glutwurf ---
    const val TOSS_RADIUS = 3f
    const val TOSS_W = 22
    const val TOSS_DAMAGE = 16f
    /** Der Rohling bleibt 10 s glühend liegen; die letzten 40 Ticks kühlt er sichtbar ab. */
    const val INGOT_LIFE = 200
    const val INGOT_COOL = 40
    /** Höchstens so viele Rohlinge gleichzeitig, Glutwurf frühestens alle so vielen Angriffe. */
    const val INGOT_MAX = 2
    const val TOSS_EVERY = 3
    /** So lange glüht er (zählt für den Fehlschlag); danach kühlt er [INGOT_COOL] Ticks ab und verschwindet. */
    const val INGOT_GLOW = INGOT_LIFE - INGOT_COOL
    /**
     * Abgekühlter Rohling: Größe des Geschosses `INGOT` unter 1 (Vereinbarung mit `OlympPropsModel`, siehe
     * `tmp/overhaul2/requests/P08.md`: Größe < 1 zeichnet den Knochen `ingot_cold`).
     */
    const val INGOT_COLD_SIZE = 0.9f
    /** Mitte des liegenden Rohlings über dem Boden (Knochen 5 Einheiten hoch, Faktor 0,70). */
    const val INGOT_REST_Y = 0.12
    /** Animation: Greifen bis 10, Zeigen bis 22, Wurf 22–24, Ende 40. */
    const val TOSS_REACH = 10
    const val TOSS_HIT = 22
    const val TOSS_HIT_END = 24
    const val TOSS_END = 40
    /** Der Rohling verlässt die Zange einen Tick nach [TOSS_HIT] (Modell: „Rohling aus ab 23“). */
    const val TOSS_RELEASE = TOSS_HIT + 1
    /** Flug: Blöcke je Tick, Grenzen in Ticks. Die Kreis-Ansage beginnt so spät, dass sie genau [TOSS_W] dauert. */
    const val TOSS_SPEED = 1.6
    const val TOSS_FLIGHT_MIN = 6
    const val TOSS_FLIGHT_MAX = 14

    // --- 4 BELLOWS: Blasebalg (fern) ---
    const val BELLOWS_HALF_ANGLE = 35f
    const val BELLOWS_RADIUS = 16f
    const val BELLOWS_W = 18
    const val BELLOWS_DAMAGE = 8f
    /** 6 Blöcke Schub nach hinten. */
    const val BELLOWS_PUSH = 6.0
    const val BELLOWS_F = 20
    /** Animation: Ausholen bis 18, Stoß 18–24, Ende 44. */
    const val BELLOWS_HIT = 18
    const val BELLOWS_HIT_END = 24
    const val BELLOWS_END = 44
    /** Schub: waagerecht 1,0 und 0,3 nach oben tragen etwa 6 Blöcke weit. */
    const val BELLOWS_KNOCK = 1.0
    const val BELLOWS_LIFT = 0.3

    // --- 5 BLINDED: Fehlschlag (Können) ---
    /** Rohling in der Hammerlinie: Abstand zur Mittellinie höchstens so viel, innerhalb der Länge [HAMMER_LENGTH]. */
    const val MISFIRE_DISTANCE = 2.0
    const val BLINDED_F = 80

    // --- Der Amboss deckt (Linien und Kegel, nicht der Blasebalg) ---
    /** Sichtlinie in dieser Höhe über dem Boden (Amboss 2 hoch; nicht genau auf der Fuge zwischen den Blöcken). */
    const val COVER_HEIGHT = 0.9
    /** Nur wer mit den Füßen tiefer als so viel über dem Boden steht, ist gedeckt (auf dem Amboss nicht). */
    const val COVER_MAX_FEET = 1.5

    // --- Saal-Gefahr „Roste“ (Phase II, alle 14 s) ---
    const val GRATE_INTERVAL = 280
    const val GRATE_HOT = 3
    const val GRATE_W = 40
    const val GRATE_BURN = 80
    const val GRATE_DAMAGE = 8f
    const val GRATE_DAMAGE_INTERVAL = 10
    /** Erste Glut nach dem Übergang in Phase II. */
    const val GRATE_FIRST = 60
    /** Nach dem Keil ruht die Esse mindestens so lange. */
    const val GRATE_AFTER_WEDGE = 60
    /** Goldener Rand der kühlen Roste: Linien so weit innen an beiden Kanten, so breit, von Radius bis Radius. */
    const val GRATE_RIM_OFFSET = 1.2
    const val GRATE_RIM_WIDTH = 0.8f
    const val GRATE_RIM_FROM = 7.0
    const val GRATE_RIM_TO = 21.0

    // --- Höhepunkt „Der Keil“ bei 35 % und 15 % ---
    const val WEDGE_AT_1 = 0.35f
    const val WEDGE_AT_2 = 0.15f
    /** FORGE: drei Schläge je W 20, Ringe bis Radius 8 / 14 / 20 zum Überspringen (S 12, `LOW`). */
    const val FORGE_W = 20
    const val FORGE_RING_1 = 8f
    const val FORGE_RING_2 = 14f
    const val FORGE_RING_3 = 20f
    const val FORGE_RING_DAMAGE = 12f
    /** Animation FORGE: Schläge bei 20, 40, 60, Zeigen des Keils bis 84. */
    const val FORGE_HIT_1 = 20
    const val FORGE_HIT_2 = 40
    const val FORGE_HIT_3 = 60
    const val FORGE_END = 84
    /** WEDGE_THROW: Kreis Radius 6 am Ziel, W 24, S 30, `SKY`; danach leere Hände offen F 40. */
    const val WEDGE_RADIUS = 6f
    const val WEDGE_W = 24
    const val WEDGE_DAMAGE = 30f
    const val WEDGE_F = 40
    /** Animation: Ausholen bis 24, Wurf 24–26, leere Hände bis 66. */
    const val WEDGE_HIT = 24
    const val WEDGE_HIT_END = 26
    const val WEDGE_END = 66
    /** Der Keil verlässt die Faust bei 25 (Modell); ab da ist er offen bis [WEDGE_END] (= [WEDGE_F] + 1). */
    const val WEDGE_RELEASE = WEDGE_HIT + 1
    /** Flug wie ein Speer: Blöcke je Tick, Grenzen in Ticks. Die Kreis-Ansage dauert genau [WEDGE_W]. */
    const val WEDGE_SPEED = 2.2
    const val WEDGE_FLIGHT_MIN = 5
    const val WEDGE_FLIGHT_MAX = 12
    /** Die Glutringe laufen mit so vielen Blöcken je Tick vom Amboss nach außen (man springt, wenn die Front kommt). */
    const val FORGE_WAVE_SPEED = 2.0
    /** Er stellt sich so weit von der Ambossmitte hin; Weg dorthin höchstens so viele Ticks, Tempo-Faktor. */
    const val ANVIL_STAND = 3.3
    const val FORGE_WALK_MAX = 80
    const val FORGE_WALK_SPEED = 1.2

    // --- 10 RISE: Auftritt aus der Esse ---
    const val RISE_END = 40

    // --- Wahl der Angriffe nach Entfernung (Blöcke) und Gewicht ---
    const val SWING_RANGE_MAX = 6.0
    const val HAMMER_RANGE_MIN = 2.0
    const val HAMMER_RANGE_MAX = 11.0
    /** Liegt ein glühender Rohling in seiner Hammerlinie zum Ziel, schlägt er auch aus größerer Entfernung (Köder). */
    const val BAIT_RANGE_MAX = 16.0
    const val BELLOWS_RANGE_MIN = 8.0
    const val BELLOWS_RANGE_MAX = 15.0
    const val TOSS_RANGE_MIN = 6.0
    const val TOSS_RANGE_MAX = 22.0
    const val HAMMER_WEIGHT = 3
    const val BAIT_WEIGHT = 3
    const val SWING_WEIGHT = 3
    const val TOSS_WEIGHT = 2
    const val BELLOWS_WEIGHT = 2

    // --- Textzeilen (Ticks im Bild) ---
    const val LINE_TICKS = 80
    /** Zweite Zeile des Sieges so viele Ticks nach der ersten. */
    const val YIELD_SECOND_LINE = 100
}
