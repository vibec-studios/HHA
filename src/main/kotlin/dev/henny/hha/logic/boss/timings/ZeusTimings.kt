package dev.henny.hha.logic.boss.timings

/**
 * Zeiten und Maße für Zeus (PLAN 5.5, Modell-Brief `tmp/overhaul2/models/zeus.md`). **W** = Ansage, **S** = Grundschaden
 * vor Skalierung, **F** = Fenster danach (Ticks bzw. Blöcke). Von P00 angelegt; gehört danach P10.
 */
object ZeusTimings {
    // --- Stärke (PLAN 4.1): drei Leisten je 1100 effektiv ---
    const val BAR_HEALTH = 1100f
    const val BARS = 3
    const val EFFECTIVE_HEALTH = BAR_HEALTH * BARS
    /** Lebensattribut; Zähigkeit = [EFFECTIVE_HEALTH] / [HEALTH]. */
    const val HEALTH = 1000.0
    /** Schwellen der Leisten: 66,7 % und 33,3 %. */
    const val PHASE_2 = 2f / 3f
    const val PHASE_3 = 1f / 3f
    /** Keine Vanilla-Rüstung: Sie würde das effektive Leben verfälschen (Rechnung über Zähigkeit, PLAN 4.1). */
    const val ARMOR = 0.0
    const val ATTACK = 14.0
    const val SPEED = 0.27
    const val PAUSE = 24
    const val PAUSE_PHASE_2 = 20
    const val PAUSE_PHASE_3 = 14

    // --- Konter (PLAN 4.3): GUARD_UP „Aigis über dem Haupt“, Radius 4, S 20 ---
    const val COUNTER_DAMAGE = 20f
    const val COUNTER_RADIUS = 4.0
    /** Heben bis 12, Stoß 12–14, Halten (wachsam) bis 40. */
    const val GUARD_RAISE = 12
    const val GUARD_HIT = 12
    const val GUARD_HIT_END = 14
    const val GUARD_END = 40

    // --- Erdung: sechs Pfeiler auf Ring 21 (OlympusLayout) ---
    /** Spieler näher als so viel an einem frischen Pfeiler: Der Himmelsblitz schlägt in den Pfeiler. */
    const val ROD_LURE_DISTANCE = 3.0
    /** Der Pfeiler bleibt so lange geladen. */
    const val ROD_CHARGED = 400
    /** Zeus näher als so viel an einem geladenen Pfeiler: Entladung in ihn. */
    const val ROD_DISCHARGE_DISTANCE = 4.0
    /** Entladung: 40 × H(n) Schaden, STAGGER offen F 90. */
    const val ROD_DAMAGE = 40f
    const val ROD_STAGGER_F = 90
    /** Zu Beginn jeder Phase werden so viele verbrauchte Pfeiler erneuert. */
    const val ROD_RENEW = 2

    // --- Luftregel: länger als 30 Ticks mindestens 3 über dem Boden → eigener Himmelsblitz ---
    const val AIR_HEIGHT = 3.0
    const val AIR_TICKS = 30
    const val AIR_W = 16
    /** Der Kreis folgt dem Spieler bis zu diesem Tick der Ansage. */
    const val AIR_FOLLOW = 10
    const val AIR_COOLDOWN = 60
    /** Eigener Himmelsblitz der Luftregel: Schaden wie der Himmelsblitz (PLAN nennt keinen eigenen Wert). */
    const val AIR_DAMAGE = 26f
    /** Radius des Luft-Blitzes (wie der Himmelsblitz). */
    const val AIR_RADIUS = 3.5f
    /**
     * Der Kreis folgt bis Tick [AIR_LOCK] (Markierung am Spieler), dann steht er [AIR_W] − [AIR_LOCK] Ticks fest. Der
     * feste Kreis braucht die Mindestdauer QUICK (10); darum folgt er bis 6 statt bis [AIR_FOLLOW] (Gesamt-W bleibt 16).
     */
    const val AIR_LOCK = 6

    // --- 1 THROW: Keraunos-Wurf (fern) ---
    const val THROW_WIDTH = 2.4f
    const val THROW_LENGTH = 30f
    const val THROW_W = 18
    const val THROW_DAMAGE = 24f
    /** Der Blitz steckt 30 Ticks: Kreis 2,5, S 6 je 10 Ticks. */
    const val THROW_STUCK = 30
    const val THROW_STUCK_RADIUS = 2.5f
    const val THROW_STUCK_DAMAGE = 6f
    const val THROW_STUCK_INTERVAL = 10
    /** Leere Hand: offen F 30 (Aktion EMPTY). */
    const val THROW_F = 30
    /** Animation: Ausholen halten bis 18, Wurf 18–20, Nachlauf bis 28. */
    const val THROW_HIT = 18
    const val THROW_HIT_END = 20
    const val THROW_END = 28
    /** Flug des Keraunos die Linie entlang (Ticks), Rückflug in die Hand. */
    const val THROW_FLIGHT = 3
    const val THROW_RETURN = 6
    /** Ein Pfeiler auf der Linie hält den Blitz auf (halbe Blockbreite als Spielraum). */
    const val THROW_ROD_MARGIN = 0.6
    /** Reichweite der Wahl: ab so weit wirft er. */
    const val THROW_MIN_RANGE = 9.0

    // --- 2 BASH: Aigis-Stoß (nah) ---
    const val BASH_HALF_ANGLE = 70f
    const val BASH_RADIUS = 7f
    const val BASH_W = 14
    const val BASH_DAMAGE = 20f
    const val BASH_KNOCKBACK = 1.2
    const val BASH_F = 16
    /** Animation: Ausholen bis 14, Stoß 14–16, Nachlauf bis 30. */
    const val BASH_HIT = 14
    const val BASH_HIT_END = 16
    const val BASH_END = 30
    /** Der Stoß trägt ihn ein Stück nach vorn (Geschwindigkeit je Tick, zwei Ticks lang). */
    const val BASH_LUNGE = 0.55
    const val BASH_RANGE = 6.5

    // --- 3 SLAM: Donnerschlag (nah), danach 2 Ringe zum Überspringen ---
    const val SLAM_RADIUS = 8f
    const val SLAM_W = 20
    const val SLAM_DAMAGE = 28f
    const val SLAM_RINGS = 2
    const val SLAM_RING_GAP = 14
    const val SLAM_RING_DAMAGE = 14f
    /** Blitz steckt: offen F 36 (Aktion STUCK). */
    const val SLAM_F = 36
    /** Animation: Ausholen bis 20, Schlag 20–22. */
    const val SLAM_HIT = 20
    const val SLAM_HIT_END = 22
    /** Die Ringe laufen so schnell nach außen (Blöcke je Tick); getroffen wird im Band ± [SLAM_RING_DEPTH]. `LOW`. */
    const val SLAM_RING_SPEED = 0.8
    const val SLAM_RING_DEPTH = 1.0
    const val SLAM_RANGE = 7.0

    // --- 4 CALL: Himmelsblitz (fern), je Spieler ein Kreis ---
    const val CALL_RADIUS = 3.5f
    const val CALL_W = 22
    const val CALL_DAMAGE = 26f
    const val CALL_F = 14
    /** Animation: Halten bis 22, Ruck nach unten 22–24, Ende 36. */
    const val CALL_HIT = 22
    const val CALL_HIT_END = 24
    const val CALL_END = 36
    const val CALL_MIN_RANGE = 7.0

    // --- 5 STEP: Donnerschritt (fern) ---
    const val STEP_W = 20
    const val STEP_RADIUS = 5f
    const val STEP_DAMAGE = 24f
    const val STEP_F = 20
    /** Animation: alle Knochen aus 14–20, Erscheinen 20. */
    const val STEP_VANISH = 14
    const val STEP_APPEAR = 20
    /** Er springt zwischen Verschwinden und Erscheinen (Client-Glättung braucht ein paar Ticks). */
    const val STEP_MOVE = 16
    /** Nie näher als so viel an einem Pfeiler landen (sonst steckt er im Kupfer). */
    const val STEP_ROD_CLEARANCE = 1.8
    const val STEP_MIN_RANGE = 10.0

    // --- 6 GAZE: Gorgonenblick (Phase II, alle 30 s) ---
    const val GAZE_INTERVAL = 600
    const val GAZE_HALF_ANGLE = 60f
    const val GAZE_RADIUS = 26f
    const val GAZE_W = 40
    /** In der Kette „Arges“ kürzer. */
    const val GAZE_W_CHAIN = 30
    const val GAZE_DAMAGE = 30f
    /** Trifft, wer weniger als so viel Grad von ihm wegschaut. */
    const val GAZE_LOOK_ANGLE = 70.0
    const val GAZE_F = 24
    /** Animation: Heben bis 12, Halten bis 40, Blitz 40–42, Ende 64. */
    const val GAZE_RAISE = 12
    const val GAZE_HIT = 40
    const val GAZE_HIT_END = 42
    const val GAZE_END = 64
    /** Erster Blick nach Beginn von Phase II. */
    const val GAZE_FIRST = 200
    /** In der Kette hebt er die Aigis 10 Ticks vor der Ansage (das Modell trifft bei [GAZE_HIT]). */
    const val GAZE_CHAIN_LEAD = GAZE_HIT - GAZE_W_CHAIN

    // --- Saal-Gefahren Phase II (alle 16 s im Wechsel) ---
    const val HAZARD_INTERVAL = 320
    /** Der Adler: Linie 3 breit quer durch den Hof, W 30, S 18; er fliegt sie in 16 Ticks. */
    const val EAGLE_WIDTH = 3f
    const val EAGLE_W = 30
    const val EAGLE_DAMAGE = 18f
    const val EAGLE_FLIGHT = 16
    /** Sturmwand: W 40, goldene Lücke 8 breit, S 24. */
    const val STORM_W = 40
    const val STORM_GAP = 8f
    const val STORM_DAMAGE = 24f
    /** Erste Saal-Gefahr nach Beginn von Phase II; keine neue, wenn der Blick in so vielen Ticks fällig ist. */
    const val HAZARD_FIRST = 40
    const val HAZARD_GAZE_GUARD = 100
    /** Flughöhe des Adlers über dem Boden. */
    const val EAGLE_HEIGHT = 1.6
    /** Die Sturmwand braucht so lange quer durch den Hof; getroffen wird im Band ± [STORM_DEPTH]. */
    const val STORM_TRAVEL = 40
    const val STORM_DEPTH = 1.5

    // --- Phase III: Ketten „Brontes“, „Steropes“, „Arges“ ---
    const val CHAIN_OPEN_F = 30
    const val ARGES_BOLTS = 3
    const val ARGES_GAP = 16
    const val ARGES_F = 24
    /** Reichweiten der Ketten für die Wahl nach Entfernung. */
    const val CHAIN_NEAR = 10.0
    const val CHAIN_FAR_MIN = 6.0

    // --- 7 SCALES: Die Waage (bei 66 % und 33 % der dritten Leiste) ---
    /** Anteile des Gesamtlebens: 66 % bzw. 33 % von [PHASE_3]. */
    const val SCALES_AT_1 = PHASE_3 * 0.66f
    const val SCALES_AT_2 = PHASE_3 * 0.33f
    const val SCALES_W = 40
    /** Goldene Schale: Radius 7, +1 je weiterem Spieler, höchstens 10; 240 Ticks. */
    const val BOWL_RADIUS = 7f
    const val BOWL_RADIUS_PER_PLAYER = 1f
    const val BOWL_RADIUS_MAX = 10f
    const val BOWL_TICKS = 240
    /** Außerhalb der Schale ab Tick 60 Sturm: S 8 je 10 Ticks, `SKY`. */
    const val SCALES_STORM_START = 60
    const val SCALES_STORM_DAMAGE = 8f
    const val SCALES_STORM_INTERVAL = 10
    /** Vier Himmelsblitze in die Schale: Radius 2,6, W 20, alle 50 Ticks. */
    const val SCALES_BOLTS = 4
    const val SCALES_BOLT_RADIUS = 2.6f
    const val SCALES_BOLT_W = 20
    const val SCALES_BOLT_INTERVAL = 50
    /** Schale besetzt: STAGGER offen F 110; leer: Heilung 12 % der Leiste. */
    const val SCALES_STAGGER_F = 110
    const val SCALES_HEAL = 0.12f
    /** Schaden der vier Blitze in die Schale (PLAN nennt keinen Wert; etwas unter dem Himmelsblitz). */
    const val SCALES_BOLT_DAMAGE = 22f
    /** Die Schale liegt am Rand: Mitte so weit innerhalb des Hof-Rands (plus ihr Radius). */
    const val BOWL_EDGE_MARGIN = 1.0
    /** Vor der Waage tritt er zur Mitte (Donnerschritt ohne Schaden), wenn er weiter weg steht. */
    const val SCALES_CENTER_DISTANCE = 3.0
    /** Animation: Heben bis 40, Halten bis 280, Wurf-Rucke bei 60, 110, 160, 210. */
    const val SCALES_RAISE = 40
    const val SCALES_END = 280
    const val SCALES_CAST_1 = 60
    const val SCALES_CAST_2 = 110
    const val SCALES_CAST_3 = 160
    const val SCALES_CAST_4 = 210

    // --- Übrige Aktionen ---
    const val STAGGER_END = 110
    const val EMPTY_END = 30
    const val STUCK_END = 36
    const val RISE_END = 40
    const val PHASE_END = 40
    /** Nach dem Taumeln richtet er sich so lange auf. */
    const val STAGGER_RISE = 14

    // --- Szenen und Bild ---
    /** Cutscenes höchstens 8 s, Phasenwechsel 4 s. */
    const val SCENE_MAX = 160
    const val PHASE_SCENE = 80
    const val SCREEN_FLASH_MAX = 0.25f
    /**
     * Echte (kosmetische) Vanilla-Blitze höchstens einer je Sekunde, und nur beim Laden/Entladen eines Pfeilers und in
     * Szenen (Effekte nicht zu stark: jeder echte Blitz hellt den ganzen Himmel auf).
     */
    const val LIGHTNING_PER_SECOND = 1
    /** Nikes Kranz schwebt so lange über dem Sieger. */
    const val WREATH_TICKS = 200
    /** Der Kranz schwebt so hoch über dem Kopf des Siegers. */
    const val WREATH_HEIGHT = 0.55
    const val WREATH_RADIUS = 0.5f

    // --- Pfeiler: Darstellung ---
    /** Goldene Rune am Fuß eines geladenen Pfeilers: alle [ROD_RUNE_CYCLE] Ticks neu (endet so spätestens kurz nach der Entladung). */
    const val ROD_RUNE_CYCLE = 40
    const val ROD_RUNE_RADIUS = 1.4f

    // --- Szenen (Ticks ab Szenenbeginn) ---
    /** Auftritt: Blick vom Siegel zum Thron, Aufstehen, Donnerschritt in den Hof, Titelkarte. */
    const val INTRO_RISE = 40
    const val INTRO_STEP = 92
    const val INTRO_END = 150
    /** Phasenwechsel: Blitz in ihn, Zeile, Titel. */
    const val PHASE_SCENE_FLASH = 6
    /** Ende: Zeus senkt den Blitz, Nike bringt den Kranz, Zeus zeigt nach unten. */
    const val VICTORY_SCENE = 156
    const val VICTORY_AFTER = 50
}
