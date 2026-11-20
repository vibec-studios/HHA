package dev.henny.hha.logic.boss.timings

/**
 * Zeiten und Maße für Kratos und Bia (PLAN 5.4, Modell-Brief `tmp/overhaul2/models/kratos_bia.md`). **W** = Ansage,
 * **S** = Grundschaden vor Skalierung, **F** = Fenster danach (Ticks bzw. Blöcke). Von P00 angelegt; gehört P09.
 *
 * Die Tick-Marken der Animationen (`*_HIT`, `*_END`, `*_GESTURE` …) liest `KratosModel`; nicht umbenennen.
 */
object KratosTimings {
    // --- Stärke (PLAN 4.1) ---
    const val HEALTH = 850.0
    const val TOUGHNESS = 2.0f
    const val EFFECTIVE_HEALTH = 1700f
    /** Phase II „Gewalt ohne Maß“ bei 50 %. */
    const val PHASE_2 = 0.5f
    /** Keine Vanilla-Rüstung: Sie würde das effektive Leben verfälschen (Rechnung über Zähigkeit, PLAN 4.1). */
    const val ARMOR = 0.0
    const val ATTACK = 12.0
    const val SPEED = 0.26
    const val PAUSE = 24

    // --- Schild (Phase I): Treffer von vorn (± 60°) außerhalb offener Fenster × 0,2 ---
    const val SHIELD_HALF_ANGLE = 60.0
    const val SHIELD_FACTOR = 0.2f
    /** Abprall-Ton des Schildes höchstens einmal je so viele Ticks. */
    const val SHIELD_SOUND_COOLDOWN = 10

    // --- Konter (PLAN 4.3): SHIELD_UP ---
    const val COUNTER_DAMAGE = 16f
    /** Heben bis 12, Stoß 12–14, Halten bis 40. */
    const val SHIELD_UP_RAISE = 12
    const val SHIELD_UP_HIT = 12
    const val SHIELD_UP_HIT_END = 14
    const val SHIELD_UP_END = 40

    // --- 1 BASH: Schildstoß (nah) ---
    const val BASH_HALF_ANGLE = 60f
    const val BASH_RADIUS = 6f
    const val BASH_W = 14
    const val BASH_DAMAGE = 16f
    const val BASH_KNOCKBACK = 1.2
    /** Schild hängt: F 20, von vorn voll. */
    const val BASH_F = 20
    /** Animation: Ausholen bis 14, Stoß 14–16, Schildarm hängt (offen) bis 36. */
    const val BASH_HIT = 14
    const val BASH_HIT_END = 16
    const val BASH_END = 36
    /** Auswahl: Schildstoß bis zu diesem Abstand zum Ziel. */
    const val BASH_REACH = 5.5

    // --- 2 SMASH: Hammerfall ---
    /**
     * P13 (Fairness): Radius 5 / W 18 → **3,5 / 28**. Wer mitten im Kreis steht, braucht 3,8 Blöcke; mit Reaktion und
     * `BossTimings.Min.ESCAPE_SPEED` sind das 12 + 16 = 28 Ticks (vorher kaum zu verlassen). Die Marken ziehen mit.
     */
    const val SMASH_RADIUS = 3.5f
    const val SMASH_W = 28
    const val SMASH_DAMAGE = 24f
    /** Hammer steckt: F 26. */
    const val SMASH_F = 26
    /** Animation: Ausholen bis 28, Schlag 28–30, steckt bis 56. */
    const val SMASH_HIT = 28
    const val SMASH_HIT_END = 30
    const val SMASH_END = 56
    /** Auswahl: Hammerfall bis zu diesem Abstand zum Ziel. */
    const val SMASH_MOVE_REACH = 8.0
    /** Der Kreis liegt am Ziel, aber höchstens so weit vor ihm (die zwei Schritte reichen nicht weiter). */
    const val SMASH_REACH = 7.0
    /** Zwei schwere Schritte: so lange geht er während des Ausholens auf den Kreis zu … */
    const val SMASH_STEP_TICKS = 10
    /** … bis er so nah an der Kreismitte steht (dort landet der Hammerkopf). */
    const val SMASH_STAND = 3.2

    // --- 8 DOUBLE_SMASH (Phase II): zweiter Kreis 18 Ticks später am neuen Standort des Ziels ---
    /** P13: 14 → 18, damit man aus der Mitte des zweiten Kreises (3,8 Blöcke) noch im Lauf herauskommt. */
    const val DOUBLE_GAP = 18
    /** Animation: Schlag 28, zweiter 46, steckt bis 72. */
    const val DOUBLE_HIT = 28
    const val DOUBLE_HIT2 = DOUBLE_HIT + DOUBLE_GAP
    const val DOUBLE_END = 72

    // --- Befehle (gemeinsam) ---
    /** Ab diesem Abstand zum Ziel ist es „fern“: Er befiehlt statt herzugehen (PLAN 5.4 Auswahl). */
    const val COMMAND_FAR = 8.5
    /** Das Befehlswort als Titel: Einblenden, Stehen, Ausblenden (Ticks). */
    const val COMMAND_TITLE_IN = 2
    const val COMMAND_TITLE_STAY = 24
    const val COMMAND_TITLE_OUT = 6
    /** Farbe des Befehlsworts: Haupt-Ton von `Vfx.Style.CRIMSON` (PLAN 9.1). */
    const val COMMAND_COLOR = 0xE0483C
    /** Farbe der Zeile darunter (neutral, wie der Untertitel der Titelkarte). */
    const val COMMAND_SUB_COLOR = 0xF0EAD8

    // --- 3 CMD_BACK „WEICHT!“ ---
    /**
     * P13 (Fairness): Radius 10 / W 24 → **7 / 34**. Aus dem Nahkampf (2 Blöcke vor ihm) sind es 5,3 Blöcke bis
     * außerhalb: 12 Ticks Reaktion + 5,3 / 0,24 = 34 Ticks, also ohne Sofort-Sprint zu schaffen (vorher nur mit
     * sofortigem Sprinten). Die Marken ziehen mit.
     */
    const val BACK_RADIUS = 7f
    const val BACK_W = 34
    const val BACK_DAMAGE = 22f
    const val BACK_F = 14
    /** Animation: Geste bis 8, Halten bis 34, Stampfen 34–36, Ende 48. */
    const val BACK_GESTURE = 8
    const val BACK_HIT = 34
    const val BACK_HIT_END = 36
    const val BACK_END = 48
    /** Wer getroffen wird, fliegt so weit nach außen. */
    const val BACK_KNOCKBACK = 0.9

    // --- 4 CMD_COME „HERAN!“: alles außerhalb Radius 7, sofort danach Schildstoß ---
    const val COME_SAFE_RADIUS = 7f
    /**
     * P13 (Fairness): W 30 → **mindestens 40**, und länger, wenn der fernste Mitkämpfer sonst nicht in den sicheren
     * Kreis käme (12 Ticks Reaktion + Weg / 0,24), höchstens [COME_W_MAX]. Bräuchte jemand mehr, wählt Kratos
     * „HERAN!“ nicht. Bei längerer Ansage beginnt die Geste später, damit der Ruck mit dem Treffer fällt.
     */
    const val COME_W = 40
    const val COME_W_MAX = 64
    const val COME_DAMAGE = 22f
    /** Animation: Winken bis 12, Faust halten bis 40, Ruck 40–42. */
    const val COME_WAVE = 12
    const val COME_HIT = 40
    const val COME_HIT_END = 42

    // --- 5 CMD_KNEEL „KNIET!“: Bias Klinge fegt auf Brusthöhe über den ganzen Platz ---
    const val KNEEL_W = 30
    const val KNEEL_DAMAGE = 26f
    const val KNEEL_F = 20
    /** Bia läuft die Klinge in so vielen Ticks quer über den Platz. */
    const val KNEEL_RUN = 30
    /** Animation Kratos: Geste bis 8, Halten bis 30, Ende 50. */
    const val KNEEL_GESTURE = 8
    const val KNEEL_HIT = 30
    const val KNEEL_END = 50
    /** Bia springt in so vielen Ticks vom Sockel an den Startrand ihres Laufs. */
    const val KNEEL_LEAP = 10
    /** Ihre Bahn bleibt so weit innerhalb des Platzrandes. */
    const val KNEEL_EDGE = 1.5
    /** Läuft ihre Bahn näher als so weit an Kratos vorbei, verschiebt sie sich seitlich um [KNEEL_SHIFT]. */
    const val KNEEL_DODGE = 3.0
    const val KNEEL_SHIFT = 4.5
    /**
     * Die Klingenfront: Requisiten `BLADE` in einer Reihe quer zu ihrem Lauf, so hoch über dem Boden (Mitte). Ein
     * Schleichender ist 1,5 hoch: Die Klinge geht knapp über ihn hinweg.
     */
    const val KNEEL_BLADE_HEIGHT = 1.75
    /** Größe je Klinge (1 = 2,6 Blöcke breit, 0,26 hoch) und Abstand der Klingen in der Reihe. */
    const val KNEEL_BLADE_SIZE = 1.8f
    const val KNEEL_BLADE_SPACING = 4.4
    /** Wer getroffen wird, wird so stark in Laufrichtung gestoßen. */
    const val KNEEL_KNOCKBACK = 0.5

    /** Phase II: Befehle paarweise, der zweite wird so viele Ticks nach dem Treffer des ersten angesagt. */
    const val COMMAND_PAIR_GAP = 10
    /** Nach jedem zweiten Angriff ein Befehl. */
    const val COMMAND_EVERY = 2

    // --- Bias Sturm (Saal-Gefahr, alle 14 s) ---
    const val STORM_INTERVAL = 280
    const val STORM_WIDTH = 2.6f
    const val STORM_W = 18
    const val STORM_W_PHASE_2 = 16
    const val STORM_REPEAT_PHASE_2 = 3
    const val STORM_DAMAGE = 20f
    /** Bia läuft die Linie in so vielen Ticks. */
    const val STORM_RUN = 12
    /** Erster Sturm so viele Ticks nach dem Auftritt (erst Kratos kennenlernen). */
    const val STORM_FIRST = 160
    /** Nach jeder Rückkehr auf den Sockel wartet Bia mindestens so lange (auch nach „KNIET!“). */
    const val STORM_MIN_GAP = 60
    /** Die Linie beginnt so weit vor der Sockelmitte (Sockel 3 × 3: auch schräg ganz außerhalb, Ecke bei 2,12). */
    const val STORM_PLINTH_CLEAR = 2.3
    /** Ziel näher als so weit an ihr: Sie stürmt durch die Platzmitte statt aufs Ziel. */
    const val STORM_MIN_AIM = 3.0
    /** Wer getroffen wird, wird so stark zur Seite gestoßen. */
    const val STORM_KNOCKBACK = 0.7

    // --- Der Zusammenstoß (Können): Bias Linie näher als 2 an Kratos ---
    const val CLASH_DISTANCE = 2.0
    const val CLASH_F = 80
    /** Bia kommt so weit vor seiner Mitte zum Stehen. */
    const val CLASH_STOP = 1.6

    // --- 7 DROP_SHIELD: 0–30, Schild aus ab 16 ---
    const val DROP_SHIELD_GONE = 16
    const val DROP_SHIELD_END = 30
    /** Der Schild landet so weit links neben ihm. */
    const val DROP_SHIELD_SIDE = 2.6
    /** Geschossart für den liegenden Schild (`HermesBoltEntity.SHIELD`, gezeichnet von `OlympProps`). */
    const val SHIELD_PROP_KIND = dev.henny.hha.entity.boss.HermesBoltEntity.SHIELD

    // --- 6 STAGGER ---
    const val STAGGER_END = 80

    // --- Auftritt: Bia steht schon, Kratos schreitet die Treppe herab ---
    /** Titelkarte so lange. */
    const val ENTRANCE_TITLE = 80
    /** So lange geht er höchstens die Treppe herab bis zu seinem Platz. */
    const val ENTRANCE_WALK = 60
    /** Er beginnt höchstens so viele Blöcke hinter dem Treppenfuß (auf einem Absatz der Stufen, nur an der Station). */
    const val ENTRANCE_STAIRS = 6

    // --- Sprechzeilen (Text, ohne Ton; PLAN 14 Entscheidung 4) ---
    const val LINE_TICKS = 70

    // --- Bia (PLAN 10.2: 1 STATUE, 2 LEAP, 3 RUSH, 4 SWEEP, 5 STAGGER, 6 RETURN) ---
    /** LEAP: Ausholen bis 18 bzw. 16 (Phase II). */
    const val BIA_LEAP = 18
    const val BIA_LEAP_PHASE_2 = 16
    /** SWEEP („KNIET!“): Ausholen bis 30, Lauf 30–60. */
    const val BIA_SWEEP_WINDUP = 30
    const val BIA_SWEEP_END = 60
    const val BIA_STAGGER_END = 80
    /** RETURN: zurück auf den Sockel 0–20 (Landung ab 5). */
    const val BIA_RETURN_END = 20
    /** Der Sprung zurück auf den Sockel dauert so lange (Pose LEAP), dann landet sie (RETURN). */
    const val BIA_FLIGHT = 14
    /** Bogenhöhe ihrer Sprünge (zum Sockel, an den Startrand). */
    const val BIA_FLIGHT_LIFT = 5.0
    const val BIA_LEAP_LIFT = 2.5
    /** Abprall-Ton höchstens einmal je so viele Ticks. */
    const val BIA_DEFLECT_COOLDOWN = 20

    // =============================================================================================================
    // Überarbeitung 4 (P17, PLAN 4.2/4.3): Bia als Teilziel, Zorn, Standarten, Treppenabsatz
    // =============================================================================================================

    /**
     * Bias Leben = dieser Anteil von [EFFECTIVE_HEALTH] (solo 425 vor Skalierung; die Teilziel-Gruppe rechnet einmalig
     * `× BossScaling.health(n)` wie Kratos' eigenes Leben).
     */
    const val BIA_HEALTH_SHARE = 0.25f
    /** Deckel je Treffer: höchstens dieser Anteil von Bias Leiste (mindestens 9 Treffer). */
    const val BIA_CAP = 0.12f
    /** Bia ist so lange verwundbar (golden), nachdem sie Kratos gerammt hat (= sein Fenster [CLASH_F]). */
    const val BIA_OPEN_CLASH = 80
    /** … bzw. so lange nach dem Ende von „KNIET!“, während sie am Rand steht (sie wartet dort, bevor sie zurückspringt). */
    const val BIA_OPEN_KNEEL = 20

    /** Zorn (ohne Bia): Pausen × dies; zusammen mit anderen Pausenfaktoren nie unter [WRATH_PAUSE_FLOOR] der Grundpause. */
    const val WRATH_PAUSE = 0.85
    const val WRATH_PAUSE_FLOOR = 0.6

    /** Ein Hammerfall, dessen Mitte näher als so weit (waagerecht) an einer stehenden Standarte liegt, zerschlägt sie. */
    const val STANDARD_BREAK_RADIUS = 2.5
    /** Nach Bias Bruch: Zerschlägt sein Hammer eine Standarte, steht er so lange offen (Ersatz für den Zusammenstoß). */
    const val STANDARD_OPEN_F = 40

    /** Höhenband „trifft Kern und Absatz“: bis so weit über dem Kernboden (Absatz +4, dazu Springen). */
    const val BAND_BOTH_ABOVE = 7.0
    /** Höhenband „nur Kern“ (Bias Sturm, Klingenreihe von „KNIET!“): bis so weit über dem Kernboden, der Absatz (+4) nicht. */
    const val BAND_CORE_ABOVE = 3.0

    /** Szene `kratos.bia_break` (Länge und Marken stehen in `KratosStages`); Sprechzeile danach so lange. */
    const val BREAK_LINE_TICKS = 60
}
