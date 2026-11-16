package dev.henny.hha.logic.boss.timings

/**
 * Zeiten und Maße für Hekate (PLAN 5.2, Modell-Brief `tmp/overhaul2/models/hekate.md`). **W** = Ansage, **S** =
 * Grundschaden vor Skalierung, **F** = Fenster danach, alles in Ticks bzw. Blöcken. Tick-Marken der Animation zählen ab
 * `kit.act(...)`. Von P00 angelegt; gehört danach P07.
 */
object HekateTimings {
    // --- Stärke (PLAN 4.1) ---
    /** Lebensattribut; × [TOUGHNESS] = [EFFECTIVE_HEALTH]. */
    const val HEALTH = 650.0
    const val TOUGHNESS = 2.0f
    const val EFFECTIVE_HEALTH = 1300f
    /** Phasen bei 66 % und 33 %. */
    const val PHASE_2 = 0.66f
    const val PHASE_3 = 0.33f
    /** Keine Vanilla-Rüstung: Sie würde das effektive Leben verfälschen (Rechnung über Zähigkeit, PLAN 4.1). */
    const val ARMOR = 0.0
    const val ATTACK = 10.0
    const val SPEED = 0.25
    /** Sie gleitet (keine Schritte). */
    const val CHASE_SPEED = 0.9
    /** Schwebt so hoch über dem Boden (Modell: 6 Einheiten). */
    const val HOVER = 0.375

    // --- Platz (Maße aus OlympusLayout: Radius 26, Wege 6 breit) ---
    const val PATH_LENGTH = 26f
    const val PATH_WIDTH = 6f
    /** Die Wege beginnen so weit von der Mitte (Rand des Hekataion, 3 × 3). */
    const val PATH_START = 2f

    // --- Pausen ---
    const val PAUSE = 24
    const val PAUSE_PHASE_3 = 18

    // --- Auftritt, Phasen, Sieg (P07 Teil B) ---
    /** Auftritt: Sie gleitet so lange aus dem Hekataion an ihren Platz … */
    const val ENTRANCE_GLIDE = 30
    /** … und schaut danach so lange in die Runde (zusammen höchstens 60 Ticks, PLAN 5.2). */
    const val ENTRANCE_HOLD = 20
    /** Start der Gleitbahn: so weit von der Mitte des Hekataion (am Rand der Statue). */
    const val ENTRANCE_FROM = 1.8
    /** Phasenwechsel: Der Kopfkranz dreht (Modell 20 Ticks), sie verharrt so lange. */
    const val PHASE_TRANSITION = 30
    /** Sieg: Sie tritt so lange zur Seite … */
    const val YIELD_STEP = 20
    /** … so weit (Blöcke). */
    const val YIELD_STEP_DISTANCE = 2.5
    /** Das Höllentor füllt sich Reihe für Reihe, je Reihe so viele Ticks. */
    const val GATE_ROW_TICKS = 6

    // --- Konter (PLAN 4.3) ---
    const val COUNTER_DAMAGE = 14f
    /** HOWL: Heben bis 12, Stoß 12–14, Halten bis 40. */
    const val HOWL_RAISE = 12
    const val HOWL_HIT = 12
    const val HOWL_HIT_END = 14
    const val HOWL_END = 40

    // --- 1 TORCH_SWEEP: Fackelschwung × 2 ---
    const val SWEEP_HALF_ANGLE = 60f
    const val SWEEP_RADIUS = 7f
    const val SWEEP_W = 14
    const val SWEEP_W2 = 10
    const val SWEEP_DAMAGE = 14f
    const val SWEEP_F = 22
    /** Animation: Ausholen bis 14, Schwung 14–17, zweites Ausholen bis 27, Schwung 27–30, Ende 52. */
    const val SWEEP_HIT = 14
    const val SWEEP_HIT_END = 17
    const val SWEEP_HIT2 = 27
    const val SWEEP_HIT2_END = 30
    const val SWEEP_END = 52
    /** Herangehen: Den Schwung beginnt sie erst so nah am Ziel (Kegel Radius 7) … */
    const val SWEEP_REACH = 5.5
    /** … und steht das Ziel weiter weg als so viel, überspringt sie ihn und wirft stattdessen. */
    const val SWEEP_SKIP_DISTANCE = 14.0
    /** Der zweite Schwung zielt neu, dreht dabei aber höchstens so weit (Grad). */
    const val SWEEP_RETARGET = 35.0

    // --- 2 TORCH_THROW: Fackelwurf ---
    const val THROW_RADIUS = 3f
    const val THROW_W = 20
    const val THROW_DAMAGE = 12f
    /** Bodenfeuer: bleibt 60 Ticks, S 3 je 10 Ticks, `LOW`. */
    const val THROW_FIRE_TICKS = 60
    const val THROW_FIRE_DAMAGE = 3f
    const val THROW_FIRE_INTERVAL = 10
    /** Eine Fackel fehlt: offen F 30, bis sie zurückkehrt. */
    const val THROW_F = 30
    /** Animation: Ausholen bis 8, halten bis 20, Wurf 20–22, danach linke Hand leer bis [THROW_END]. */
    const val THROW_HIT = 20
    const val THROW_HIT_END = 22
    /** Die beiden Kreise erscheinen bei diesem Aktions-Tick (Ende des Ausholens); W 20 bis zum Einschlag. */
    const val THROW_TELEGRAPH = 8
    /** Einschlag der Fackel = [THROW_TELEGRAPH] + [THROW_W]; geworfen wird bei [THROW_HIT], sie fliegt also 8 Ticks. */
    const val THROW_IMPACT = THROW_TELEGRAPH + THROW_W
    const val THROW_FLIGHT = THROW_IMPACT - THROW_HIT
    /** Rückflug in die Hand, endet mit dem offenen Fenster. */
    const val THROW_RETURN = 8
    /** Fackel wieder in der Hand (Modell): Einschlag + F 30. Vorher 52 (Brief), angepasst an das offene Fenster. */
    const val THROW_END = THROW_IMPACT + THROW_F
    /** Vorhalt des zweiten Kreises: Bewegung des Ziels über so viele Ticks … */
    const val THROW_LEAD_TICKS = THROW_W
    /** … höchstens so weit; steht das Ziel fast still, liegt der zweite Kreis so weit in Blickrichtung. */
    const val THROW_LEAD_MAX = 6.0
    const val THROW_LEAD_MIN = 2.5
    const val THROW_LEAD_STILL = 4.0

    // --- 3 PATH: Feuerpfad ---
    const val PATH_W = 24
    const val PATH_DAMAGE = 16f
    const val PATH_F = 16
    /** Animation: Ausholen bis 24, Stoß 24–26, Ende 42. */
    const val PATH_HIT = 24
    const val PATH_HIT_END = 26
    const val PATH_END = 42

    // --- 4 KEY_TURN: Schlüsseldrehung (ab Phase II, alle 28 s) ---
    const val KEY_INTERVAL = 560
    /** Die erste Schlüsseldrehung einer Phase kommt so viele Ticks nach ihrem Beginn (dann alle [KEY_INTERVAL]). */
    const val KEY_FIRST = 160
    /**
     * W 50; reicht das nicht, um den goldenen Weg zu Fuß zu erreichen, wird die Ansage verlängert (bis
     * `BossTimings.Min.ESCAPE_MAX`) und die Aktion entsprechend später gesetzt, damit die Drehung mit dem Treffer fällt.
     */
    const val KEY_W = 50
    const val KEY_DAMAGE = 22f
    const val KEY_F = 30
    /** Animation: Heben bis 16, Halten bis 50, Drehung 50–54, Ende 84. */
    const val KEY_RAISE = 16
    const val KEY_HIT = 50
    const val KEY_HIT_END = 54
    const val KEY_END = 84

    // --- 5 STEP: Schwellentritt (ab Phase II) ---
    const val STEP_LINE_WIDTH = 2.4f
    const val STEP_W = 20
    const val STEP_DAMAGE = 18f
    const val STEP_F = 24
    /** Animation: Knochen aus 10–18, Erscheinen 18, dann Ausholen [STEP_W] und Stoß 3 Ticks. */
    const val STEP_VANISH = 10
    const val STEP_APPEAR = 18
    const val STEP_HIT = STEP_APPEAR + STEP_W
    const val STEP_HIT_END = STEP_HIT + 3
    /** Mitten im Verschwinden wechselt sie an das Wegende. */
    const val STEP_TELEPORT = 14
    /** Die Linie reicht so weit über das Ziel hinaus, mindestens [STEP_MIN_LENGTH], höchstens [PATH_LENGTH]. */
    const val STEP_OVERSHOOT = 4.0
    const val STEP_MIN_LENGTH = 10.0

    // --- 6 TRIFORM: Dreigestalt (Phase III, nach jedem dritten Angriff) ---
    const val TRIFORM_EVERY = 3
    const val TRIFORM_CONE_HALF_ANGLE = 60f
    const val TRIFORM_CONE_RADIUS = 12f
    const val TRIFORM_LINE_WIDTH = 2.4f
    const val TRIFORM_LINE_LENGTH = 26f
    const val TRIFORM_CIRCLE_RADIUS = 4f
    const val TRIFORM_W = 30
    const val TRIFORM_DAMAGE = 16f
    /** Treffer auf die Echte in der Ansage: STAGGER, offen. */
    const val TRIFORM_STAGGER_F = 70
    /** Animation: Ausholen bis 30, Treffer 30–33. */
    const val TRIFORM_HIT = 30
    const val TRIFORM_HIT_END = 33
    /** Ende der Dreigestalt: 22 Ticks nach dem Treffer halten und zurück (Wunsch aus Teil A). */
    const val TRIFORM_END = TRIFORM_HIT_END + 22
    /** Zwischen dem Erscheinen der drei Körper und der Aktion (damit die Trugbilder beim Client angekommen sind). */
    const val TRIFORM_SETUP = 2

    // --- 7 ECLIPSE: Mondfinsternis (Saal-Gefahr, Phase III, alle 20 s) ---
    const val ECLIPSE_INTERVAL = 400
    /** Die erste Mondfinsternis kommt so viele Ticks nach Beginn der Phase III. */
    const val ECLIPSE_FIRST = 120
    const val ECLIPSE_SAFE_COUNT = 2
    const val ECLIPSE_SAFE_RADIUS = 5f
    /** Die goldenen Kreise liegen so weit von der Mitte … */
    const val ECLIPSE_SAFE_MIN = 10.0
    const val ECLIPSE_SAFE_MAX = 19.0
    /** … und mindestens so weit auseinander. */
    const val ECLIPSE_SAFE_SPREAD = 12.0
    /** W 50; wie bei der Schlüsseldrehung verlängert, wenn ein Spieler den nächsten Kreis sonst nicht erreicht. */
    const val ECLIPSE_W = 50
    const val ECLIPSE_DAMAGE = 24f
    /** Stimmung: so dunkel (0–1) wird der Himmel, keine Blindheit. */
    const val ECLIPSE_MOOD = 0.7f

    /** Zwischen Schlüsseldrehung und Mondfinsternis liegen mindestens so viele Ticks (nie zwei Saal-Gefahren am Stück). */
    const val HAZARD_GAP = 120
    /** Animation: Halten bis 50, Senken 50–54; sie steigt 8 Einheiten höher. */
    const val ECLIPSE_HIT = 50
    const val ECLIPSE_HIT_END = 54

    // --- 8 STAGGER ---
    const val STAGGER_END = 70
}
