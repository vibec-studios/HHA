package dev.henny.hha.logic.boss

/**
 * Zeit- und Posenvertrag zwischen Kampfcode (Server) und Animationen (Client), Plan Abschnitt 4 und 7.
 *
 * Beide Seiten lesen ihre Zeiten **nur** von hier: Der Server setzt zu Beginn des Ausholens genau eine Pose bzw. Aktion
 * und zählt die Ticks bis zum Treffer mit denselben Konstanten, mit denen das Modell über `poseTime`/`actionTime` durch
 * Ausholen, Schlag, Nachlauf und Erholung läuft. Fehlt eine Zeit, wird sie hier ergänzt (nicht lokal im Kampf- oder
 * Modellcode) und im Bericht genannt.
 *
 * Namensregeln (alle Zeiten in Ticks, gezählt ab Beginn der Pose/Aktion, sofern nicht anders vermerkt):
 * - `*_WINDUP` = W: Ansage/Ausholen bis zum Treffer. So lange steht auch die Ansage am Boden.
 * - `*_HIT_n` / `*_THROW_n` = weitere Treffer- bzw. Wurfzeitpunkte innerhalb derselben Pose.
 * - `*_STRIKE` = Dauer des Schlags selbst (2–4 Ticks, harter Übergang).
 * - `*_END` = Ende der Animation (Nachlauf fertig).
 * - `*_WINDOW` = F: Fenster danach, in dem er steht (offen bzw. Erholung).
 * - `*_GAP` = Abstand zwischen Wellen oder Folgetreffern, `*_COUNT` = Anzahl.
 * - `*_RADIUS`, `*_WIDTH`, `*_LENGTH`, `*_RANGE` in Blöcken, `*_HALF_ANGLE` in Grad: Form der Ansage.
 *
 * Schadenswerte, Lebenspunkte und Schadensfaktoren stehen bewusst **nicht** hier; die setzen die Kämpfe.
 *
 * Wo der Plan einen Wert unter den Mindestwerten aus [Min] nennt, steht hier der angehobene Wert; der Planwert ist im
 * Kommentar vermerkt („Plan: …“).
 */
object BossTimings {

    /**
     * Mindestdauer einer Ansage (Plan 3.2). Keine Phase und kein Tempo-Faktor darf sie unterschreiten.
     *
     * Einordnung, nach der die Werte unten geprüft sind:
     * - Kegel und kurze Linien am Körper sind Hiebe ([QUICK] bzw. [HEAVY]), auch bei großem Radius.
     * - Kreise, Ringe und Flächen ab [AREA_RADIUS] sind Fläche ([AREA]); laufende Wellen und überstreichende Strahlen
     *   zählen ebenfalls als Fläche.
     * - Würfe und Geschosse ([PROJECTILE]) zählen bis zum Abwurf bzw. bis zum Einschlag am Ziel, wenn die Ansage am
     *   Ziel liegt.
     * - Saalweit ([ROOM]) ist, was im Treffermoment die ganze Arena bis auf markierte sichere Stellen trifft (Blick,
     *   Urteil, Einsturz, Flut, Münzwurf).
     * - Folgehiebe einer Kette bekommen eine eigene Ansage, sobald der vorige Hieb gefallen ist ([FOLLOW_UP]).
     */
    object Min {
        /** Schneller Nahkampfhieb. */
        const val QUICK = 10

        /** Schwerer Hieb oder Stoß. */
        const val HEAVY = 16

        /** Wurf oder Geschoss bis zum Einschlag. */
        const val PROJECTILE = 16

        /** Fläche ab [AREA_RADIUS]. */
        const val AREA = 20
        const val AREA_RADIUS = 6f

        /** Saalweite Gefahr. */
        const val ROOM = 40

        /** Folgehieb einer Kette, ab dem Fallen des vorigen Hiebs. */
        const val FOLLOW_UP = 10

        /** Ein Treffer dauert 2–4 Ticks. */
        const val STRIKE_MIN = 2
        const val STRIKE_MAX = 4

        /** Jede Ansage blitzt in ihren letzten Ticks weiß auf („jetzt“). */
        const val FLASH = 4

        /**
         * Erreichbarkeit: Wer zu einem sicheren Bereich laufen muss, schafft ohne Hilfsmittel so viele Blöcke je Tick
         * (zwischen Gehen 0,22 und Sprinten 0,28). Saalweite Gefahren mit sicheren Stellen wählen ihre Lage danach und
         * verlängern die Ansage, wenn ein Spieler sonst nicht rechtzeitig ankäme.
         */
        const val ESCAPE_SPEED = 0.24

        /** Reaktionszeit, die vor dem Loslaufen eingerechnet wird. */
        const val REACTION = 12

        /** Längste Ansage, auf die eine solche Verlängerung gehen darf. */
        const val ESCAPE_MAX = 140
    }

    /** Übergänge zwischen Posen (Plan 6.4): in Ausholposen weich, in Schläge hart. */
    object Anim {
        const val BLEND_SOFT_MIN = 6
        const val BLEND_SOFT = 8
        const val BLEND_HARD = 2
    }

    // ---------------------------------------------------------------------------------------------
    // Hermes auf dem Olymp (Plan 4.2, Brief hermes.md)
    // ---------------------------------------------------------------------------------------------

    object Hermes {
        /** Pause zwischen zwei Angriffen. */
        const val PAUSE = 26
        const val PAUSE_STAGE4 = 16

        // --- Abfangen (alle Stages): Treffer während DASH lässt ihn stolpern ---
        /** STUMBLE: 0–8 Sturz, danach gehalten und offen bis [STUMBLE_WINDOW]. */
        const val STUMBLE_FALL = 8
        const val STUMBLE_WINDOW = 40

        // --- Stage I: Kurierstoß × 3 (DASH_WINDUP → DASH) ---
        const val DASH_COUNT = 3
        const val DASH_COUNT_STAGE3 = 2
        /** Erster Stoß einer Folge. */
        const val DASH_WINDUP_FIRST = 20
        /** Jeder weitere Stoß. */
        const val DASH_WINDUP = 16
        /** DASH_WINDUP: 0–8 hinein, dann halten bis zum Stoß. */
        const val DASH_WINDUP_BLEND = 8
        const val DASH_WIDTH = 1.6f

        /**
         * Kurierstoß-Flug (Pose DASH): so viele Blöcke je Tick. Die Flugdauer ist damit Bahnlänge / Tempo, bei den
         * üblichen 12–28 Blöcken also 8–18 Ticks; langsam genug, dass man ihn im Vorbeiflug abfangen kann.
         */
        const val DASH_SPEED = 1.6
        /** So weit schießt die Bahn über das Ziel hinaus. */
        const val DASH_OVERSHOOT = 9.0
        /** Kürzeste Bahn (steht das Ziel direkt vor ihm). */
        const val DASH_MIN_LENGTH = 8.0
        /** Vor einem Stoß aus der Luft landet er erst (Ausholen am Boden). */
        const val LAND = 8

        /** SKID nach dem letzten Stoß: 0–6 bremsen, gehalten und offen bis [SKID_WINDOW]. */
        const val SKID_BRAKE = 6
        const val SKID_WINDOW = 30

        // --- Federfächer × 2 (FAN) ---
        /** Erster Wurf. Plan: 14 (unter [Min.PROJECTILE]), darum 16. */
        const val FAN_THROW_1 = 16
        /** Zweiter Wurf, je Wurf eine volle Ansage. Plan: 30. */
        const val FAN_THROW_2 = 32
        /** Ende der Pose = F 14 nach dem zweiten Wurf. Plan: 44. */
        const val FAN_END = 46
        const val FAN_WINDOW = 14
        const val FAN_HALF_ANGLE = 30f
        const val FAN_RANGE = 18f
        const val FAN_FEATHERS = 5
        /** Federn fliegen so schnell (Blöcke je Tick) und verschwinden am Ende des Kegels. */
        const val FAN_FEATHER_SPEED = 1.1
        /** Aufstellen vor dem Fächer (Flug an seinen Wurfplatz). */
        const val FAN_MOVE = 12

        // --- Sturmschlag (ASCEND → SLAM → STUCK) ---
        /** ASCEND: er steigt so lange, bevor SLAM beginnt. */
        const val SLAM_ASCEND = 16
        /** SLAM: Ausholen 0–8, halten bis zum Einschlag. */
        const val SLAM_WINDUP = 24
        const val SLAM_WINDUP_BLEND = 8
        /** Die letzten so vielen Ticks vor dem Einschlag stürzt er aus der Höhe herab. */
        const val SLAM_DROP = 3
        const val SLAM_RADIUS = 7f
        /** Die Bodenwelle danach (eigene Ansage, Ring) läuft bis zu diesem Radius. */
        const val SLAM_WAVE_RADIUS = 20f
        /** Ansage der Bodenwelle ab dem Einschlag (Ring ab Radius 6 = Fläche) … */
        const val SLAM_WAVE_WINDUP = 20
        /** … danach läuft die Welle so lange vom Trefferkreis nach außen. */
        const val SLAM_WAVE_TRAVEL = 14
        /** STUCK: 0–3 Aufprall, gehalten und offen bis [STUCK_WINDOW]. */
        const val STUCK_IMPACT = 3
        const val STUCK_WINDOW = 24

        // --- Stage II: Spiegel-Stoß ---
        /** Körper insgesamt (der Echte und zwei Trugbilder). */
        const val MIRROR_BODIES = 3
        const val MIRROR_WINDUP = 30
        /** So lange spotten die Trugbilder nach dem Stoß; der Echte steht in SKID ([SKID_WINDOW]). */
        const val MIRROR_TAUNT = 30
        /** Die Körper erscheinen auf diesem Kreis um das Ziel. */
        const val MIRROR_RING = 15.0

        // --- Diebesgriff (SNAP) ---
        const val SNAP_WINDUP = 18
        const val SNAP_END = 30
        const val SNAP_RADIUS = 2.4f
        /** So weit hinter dem Ziel taucht er auf. */
        const val SNAP_BEHIND = 2.2
        /** Danach spottet er so lange mit der Beute-Kugel auf der Stelle (TAUNT). */
        const val LOOT_TAUNT = 20
        /** So lange hält er die Beute-Kugel (12 s). */
        const val LOOT_HOLD = 240
        /** Tempo I für den, der ihm die Beute abjagt (5 s). */
        const val LOOT_SPEED = 100

        // --- Wegkreuzungen ---
        @JvmField val CROSS_LINES = intArrayOf(2, 2, 3)
        const val CROSS_WINDUP = 24
        /** Abstand vom Treffer einer Welle bis zur Ansage der nächsten. */
        const val CROSS_GAP = 14
        const val CROSS_WINDOW = 20
        const val CROSS_WIDTH = 3f

        // --- Herma-Sprung ---
        const val HERMA_STONES = 3
        const val HERMA_JUMPS = 3
        /** Die Steine stehen auf diesem Kreis um die Mitte. */
        const val HERMA_RING = 14.0
        /** So lange wachsen sie aus dem Boden (wie die Animation im Renderer). */
        const val HERMA_RISE = 14
        /** So lange glüht der Zielstein, bevor er abspringt (Ziel für den Spieler, keine Trefferansage) … */
        const val HERMA_GLOW = 14
        /** … und so lange fliegt er im Bogen hinüber; bis zur Landung kann man den Stein noch zerschlagen. */
        const val HERMA_LEAP = 12
        /** Zielstein rechtzeitig zerschlagen: Er stürzt so lange herab … */
        const val HERMA_CRASH = 6
        /** … und taumelt danach (STAGGER, offen). */
        const val HERMA_STAGGER = 80
        /** Am Ende zerfallen die übrigen Steine nacheinander im Abstand von so vielen Ticks. */
        const val HERMA_DECAY = 8

        // --- Stage III: Die Zwillinge (PLANT) ---
        /** PLANT: 0–20 hinein, der Stab trifft den Boden bei [TWINS_PLANT_STRIKE], dann halten. */
        const val TWINS_PLANT = 20
        const val TWINS_PLANT_STRIKE = 12
        /** Ansage der beiden Bahnen, bevor die Schlangen losschnellen. */
        const val TWINS_WINDUP = 30
        const val TWINS_OUT = 30
        const val TWINS_BACK = 20
        /** Zweiter Durchgang gespiegelt. */
        const val TWINS_PASSES = 2
        /** Pause zwischen den Durchgängen (Schlangen zurück am Stab bis zur nächsten Ansage). */
        const val TWINS_PASS_GAP = 10
        const val TWINS_WIDTH = 2.4f
        /** Die Bahnen enden so weit vom Rand der Arena. */
        const val TWINS_EDGE = 2.5f

        // --- Seelensäulen ---
        const val PILLAR_COUNT = 4
        const val PILLAR_WINDUP = 20
        /** Abstand von Welle zu Welle (Beginn bis Beginn); die Ansagen überlappen also kurz. */
        const val PILLAR_GAP = 14
        const val PILLAR_RADIUS = 3f
        const val PILLAR_WINDOW = 20
        /** Die letzten so vielen Wellen zielen mit Vorhalt … */
        const val PILLAR_LEAD_WAVES = 2
        /** … höchstens so weit (Blöcke) vor den Spieler. */
        const val PILLAR_LEAD_MAX = 6.0

        // --- Lanzenreihe ---
        const val LANCE_COUNT = 8
        const val LANCE_WINDUP = 16
        const val LANCE_GAP = 6
        const val LANCE_RADIUS = 1.6f
        const val LANCE_WINDOW = 20
        /** Abstand der Kreise in der Reihe (Blöcke); die Reihe beginnt so weit vor dem Spieler. */
        const val LANCE_SPACING = 2.8
        const val LANCE_LEAD_IN = 11.0
        /** Die Lanze fällt so lange vom Himmel, sie schlägt genau mit dem Ende der Ansage ein. */
        const val LANCE_FALL = 6

        // --- Styx-Flut ---
        const val STYX_PHASES = 2
        /** Saalweit (alles außer dem goldenen Bereich). Plan: 30 (unter [Min.ROOM]), darum 40. */
        const val STYX_WINDUP = 40
        /** Schaden am Boden alle so viele Ticks … */
        const val STYX_TICK = 10
        /** … so lange steht die Flut. */
        const val STYX_FLOOD = 60
        /** Pause zwischen den beiden Phasen. */
        const val STYX_GAP = 10
        /** Phase 1: Der Rand flutet, die Mitte bis zu diesem Radius ist sicher. */
        const val STYX_SAFE_RADIUS = 8f
        /** Phase 2: Die Mitte flutet bis zu diesem Radius, außen ist es sicher. */
        const val STYX_CENTER_RADIUS = 13f

        // --- Stage IV ---
        const val DIVE_COUNT = 3
        /** Aufsteigen über das Ziel, bevor DIVE beginnt. */
        const val DIVE_RISE = 8
        /** DIVE: Ausholen bis hier, ab hier Sturz. */
        const val DIVE_WINDUP = 16
        /** Sturz bis zum Einschlag; der Kreis steht also [DIVE_WINDUP] + [DIVE_DROP] Ticks. */
        const val DIVE_DROP = 3
        const val DIVE_RADIUS = 5f
        /** Die kleine Bodenwelle läuft vom Kreis bis hierher … */
        const val DIVE_WAVE_RADIUS = 9f
        /** … in so vielen Ticks ab dem Einschlag (ihr Ring wird mit dem Kreis zusammen angesagt). */
        const val DIVE_WAVE_TRAVEL = 6
        /** Pause nach jedem Sturzflug. */
        const val DIVE_GAP = 8
        /** Erschöpfung (KNEEL, offen) nach den Sturzflügen. */
        const val DIVE_EXHAUST = 100

        const val SPEAR_COUNT = 2
        const val SPEAR_WINDUP_1 = 50
        const val SPEAR_WINDUP_2 = 40
        const val SPEAR_RADIUS = 8f
        /** Bodenwelle nach jedem Speer: eigene Ring-Ansage ab dem Einschlag, dann Lauf bis [SPEAR_WAVE_RADIUS]. */
        const val SPEAR_WAVE_RADIUS = 18f
        const val SPEAR_WAVE_WINDUP = 20
        const val SPEAR_WAVE_TRAVEL = 10
        const val SPEAR_EXHAUST = 110

        /** Letztes Wort: Anlauf der zwei gegenläufigen Strahlen. Plan: 10 (unter [Min.AREA]), darum 20. */
        const val FINAL_WINDUP = 20
        const val FINAL_SWEEP = 100
        const val FINAL_EXHAUST = 100
        /** Drehung je Tick (Bogenmaß) am Anfang … */
        const val FINAL_TURN = 0.075
        /** … und Zuwachs je Tick², damit es zum Ende hin schneller wird (etwa drei Sprünge je Strahl). */
        const val FINAL_TURN_GAIN = 0.00035
        const val FINAL_WIDTH = 1.4f

        /** Erschöpfung endet: so lange steigt er wieder auf. */
        const val EXHAUST_RISE = 10
    }

    // ---------------------------------------------------------------------------------------------
    // Der Schatten des Boten (Plan 4.3). Spiegel-Stoß, Griff, Kreuzungen, Säulen, Lanzen, Sturzflug und Letztes Wort
    // laufen mit den Zeiten aus [Hermes].
    // ---------------------------------------------------------------------------------------------

    object Shade {
        /** Er ist immer verwundbar, dafür schneller: kürzere Pause zwischen den Angriffen. */
        const val PAUSE = 18

        /** Die Bahnen des Spiegel-Stoßes brennen so lange als Aschespur nach … */
        const val ASH_TRAIL = 60
        /** … und schaden alle so viele Ticks. */
        const val ASH_TICK = 10

        // --- Rauchschritt (SMOKE) ---
        const val SMOKE_COLUMNS = 3
        const val SMOKE_SPACING = 6f
        /** In der echten Säule glimmen ab hier zwei Augen. */
        const val SMOKE_EYES = 12
        /** Er tritt heraus und schlägt. */
        const val SMOKE_WINDUP = 24
        const val SMOKE_END = 36
        const val SMOKE_RADIUS = 3f
        /** Danach SKID, offen. */
        const val SMOKE_WINDOW = 24
        /** Ein Treffer in den letzten so vielen Ticks vor dem Heraustreten lässt ihn stolpern. */
        const val SMOKE_INTERCEPT = 12

        // --- Die Zwillinge: Am Ende jeder Bahn schnappt die Schlange einmal zu ---
        const val TWINS_SNAP_WINDUP = 12
        const val TWINS_SNAP_RADIUS = 3f

        // --- Fährmanns Welle ---
        /** Ansage: Ringband am Wasserrand (Ring ab Radius 6 = Fläche). */
        const val FERRY_WINDUP = 30
        /** Breite des angesagten Ringbands. */
        const val FERRY_BAND = 2.5f
        /** So lange läuft der Ring vom Wasserrand zur Mitte. */
        const val FERRY_TRAVEL = 40
        const val FERRY_WAVES = 2
        /** Abstand der beiden Wellen (Beginn der Ansage bis Beginn der nächsten). */
        const val FERRY_GAP = 30

        // --- Münzwurf (COIN) ---
        /** COIN: Schnippen 0–6, Blick nach oben, Fangen bei [COIN_AIR]. */
        const val COIN_FLICK = 6
        const val COIN_AIR = 30
        /** Danach füllt sich die unsichere Fläche so lange, dann der Treffer. */
        const val COIN_FILL = 60
        const val COIN_SAFE_RADIUS = 12f

        /** Phase III: Erschöpfung nach jedem Muster (offen). */
        const val EXHAUST = 80
    }

    // ---------------------------------------------------------------------------------------------
    // Hades (Plan 4.4, Brief hades.md)
    // ---------------------------------------------------------------------------------------------

    object Hades {
        /** Pausen je Phase; spätere Phasen werden nur hier schneller, nie in den Ansagen. */
        const val PAUSE_1 = 22
        const val PAUSE_2 = 18
        const val PAUSE_3 = 14

        // --- Hiebfolge (SWEEP → SWEEP_B → SWEEP_C) ---
        const val SWEEP_A_WINDUP = 14
        const val SWEEP_A_END = 22
        const val SWEEP_B_WINDUP = 10
        const val SWEEP_B_END = 18
        const val SWEEP_C_WINDUP = 16
        /** Vornüber auf den Bident gestützt bis hier (= Schlusshieb + [SWEEP_WINDOW]). */
        const val SWEEP_C_END = 42
        const val SWEEP_STRIKE = 2
        const val SWEEP_WINDOW = 26
        const val SWEEP_HALF_ANGLE = 75f
        const val SWEEP_RADIUS = 8.5f

        // --- Ausfallschritt (THRUST) ---
        const val THRUST_WINDUP = 18
        /** Erholung ohne Säulentreffer. */
        const val THRUST_RECOVER = 12
        /** In eine Säule gelaufen: STAGGER. */
        const val THRUST_PILLAR_STAGGER = 80
        const val THRUST_WIDTH = 2.2f

        // --- Bodenschlag (SLAM → STUCK) ---
        /** Kreis Radius 9. Plan: 16 (unter [Min.AREA]), darum 20. */
        const val SLAM_WINDUP = 20
        const val SLAM_RADIUS = 9f
        const val STUCK_WINDOW = 48
        /** STUCK: Ruck am Bident alle so viele Ticks. */
        const val STUCK_TUG = 12

        // --- Bident-Wurf (THROW → EMPTY) ---
        /** Abwurf aus der Hand (ab hier ist sie leer). */
        const val THROW_RELEASE = 14
        /** Einschlag am Ziel; so lange steht der Kreis. */
        const val THROW_WINDUP = 22
        const val THROW_RADIUS = 6f
        const val EMPTY_WINDOW = 40
        /** Rückflug mit eigener Linie. Plan: 10 (unter [Min.PROJECTILE]), darum 16. */
        const val RETURN_WINDUP = 16

        /** Haltung gebrochen: STAGGER, offen. */
        const val POISE_STAGGER = 90

        // --- Phase I: Blick (GAZE) ---
        const val GAZE_INTERVAL = 700
        const val GAZE_PER_PHASE = 2
        const val GAZE_WINDUP = 60
        const val GAZE_WINDOW = 24
        const val GAZE_END = 84

        // --- Phase II ---
        /** CHAINS: Wurf der Ketten (kein Schadenstreffer, nur Zug). */
        const val CHAINS_CAST = 14
        const val CHAINS_PULL = 20
        /** Ende des Zugs, danach SWEEP_C. */
        const val CHAINS_PULL_END = 34
        /** Kette an einer Säule gerissen: er strauchelt so lange. */
        const val CHAINS_BREAK_STAGGER = 30

        /** Schattenschritt: VANISH (Visier senken) dauert so lange … */
        const val VANISH_ANIM = 10
        /** … die Rune unter dem Ziel steht so lange bis zum Treffer … */
        const val AMBUSH_WINDUP = 24
        /** … und in AMBUSH fällt der Hieb bei diesem Tick (er taucht also bei AMBUSH_WINDUP − AMBUSH_HIT auf). */
        const val AMBUSH_HIT = 4
        const val AMBUSH_RADIUS = 5f
        const val AMBUSH_WINDOW = 60
        const val AMBUSH_END = 64

        /** Saal-Gefahr: alle so viele Ticks eine, im Wechsel. */
        const val HAZARD_INTERVAL = 280
        const val DEAD_MARCH_WINDUP = 34
        const val DEAD_MARCH_GAP = 8f
        const val HELLFIRE_WINDUP = 20
        const val HELLFIRE_WAVES = 3
        const val HELLFIRE_RADIUS = 3f
        const val RIFT_BEAMS = 4
        const val RIFT_WINDUP = 24
        const val RIFT_OPEN = 90

        // --- Phase III ---
        const val PINCER_INTERVAL = 240
        /** (a) Hermes-Linie, dann nach [PINCER_DELAY] Hades' Ausfallschritt quer dazu. */
        const val PINCER_LINE_WINDUP = 18
        const val PINCER_DELAY = 10
        /** (b) Hermes-Fächer, dann Bodenschlag. Plan: 14, angeglichen an [Hermes.FAN_THROW_1]. */
        const val PINCER_FAN_WINDUP = Hermes.FAN_THROW_1
        /** Ein Treffer vertreibt Hermes so lange. */
        const val HERMES_BANISH = 240

        /** Urteil (JUDGE): Heben bis [JUDGE_RAISE], Stoß nach unten bei [JUDGE_HIT] (= Treffer, so lange steht die Ansage). */
        const val JUDGE_INTERVAL = 800
        const val JUDGE_RAISE = 60
        const val JUDGE_HIT = 71
        const val JUDGE_WINDOW = 50
        const val JUDGE_END = 121
        const val JUDGE_SAFE_CIRCLES = 3

        /** Einsturz an den Lebensschwellen der Phase. */
        const val COLLAPSE_WINDUP = 80
        @JvmField val COLLAPSE_RADII = floatArrayOf(44f, 36f, 28f)

        /** Seelenkette: so viel Zeit bis zum goldenen Kreis. */
        const val SOUL_CHAIN_TIME = 120
        const val SOUL_CHAIN_DISTANCE = 16f

        // --- Ergänzt von K3 (Plan ließ diese Werte offen) ---
        /** Er geht höchstens so lange auf sein Ziel zu, bevor er angreift. */
        const val STRIDE_MAX = 40
        /** Nach einem Taumeln richtet er sich so lange auf (ROAR), ohne zuzuschlagen. */
        const val RISE = 12
        /** Ausfallschritt: so weit gleitet er je Tick (Blöcke). */
        const val THRUST_SPEED = 1.9
        /** Bident-Wurf: Flugzeit vom Abwurf bis zum Einschlag (= [THROW_WINDUP] − [THROW_RELEASE]) und zurück. */
        const val BIDENT_FLIGHT = THROW_WINDUP - THROW_RELEASE
        const val BIDENT_RETURN = 8
        /** Volle Breite der Rückflug-Linie. */
        const val RETURN_WIDTH = 2f
        /** Blick und Urteil kommen erstmals nach so vielen Ticks der Phase. */
        const val GAZE_FIRST = GAZE_INTERVAL
        const val JUDGE_FIRST = JUDGE_INTERVAL
        /** Erster Zangenangriff, erste Saal-Gefahr einer Phase. */
        const val PINCER_FIRST = 120
        const val HAZARD_FIRST = 140
        /** (a) Hermes stößt in so vielen Ticks durch seine Linie. */
        const val PINCER_DASH = 6
        /** (b) Der Bodenschlag beginnt so viele Ticks nach dem Fächer (Fächer W 16, Schlag W 20: Treffer bei 16 und 24). */
        const val PINCER_SLAM_DELAY = 4
        /** Abstand, aus dem Hermes zur Zange ansetzt. */
        const val PINCER_HERMES_RANGE = 11.0

        /** Zug der Toten: So lange läuft die Wand quer durch den Saal; sie trifft, wo sie vorbeizieht. */
        const val DEAD_MARCH_TRAVEL = 38
        /** Halbe Dicke der Wand, in der getroffen wird. */
        const val DEAD_MARCH_DEPTH = 2.2
        /** Höllenfeuer: Abstand der Wellen (Beginn zu Beginn). */
        const val HELLFIRE_GAP = 16
        /** Spalten: volle Breite und Länge eines Strahls; Schaden alle [RIFT_TICK] Ticks, solange man drin steckt. */
        const val RIFT_WIDTH = 3f
        const val RIFT_LENGTH = 34f
        const val RIFT_TICK = 10
        /** Seelenkette: Radius des goldenen Kreises. */
        const val SOUL_CHAIN_RADIUS = 2.4f
        /** Urteil: Radius der sicheren Kreise. */
        const val JUDGE_SAFE_RADIUS = 3.2f
        /** Einsturz: So viele Ticks brauchen die Ringe, um ganz in die Tiefe zu stürzen. */
        const val COLLAPSE_FALL = 30
        @JvmField val COLLAPSE_AT = floatArrayOf(0.66f, 0.33f)

        /** Die Pforten (Kulisse, Phase II und III): so lange lebt ein Bild, erneuert wird es vorher. */
        const val GATE_LIFE = 200
        const val GATE_REFRESH = 170
        const val GATE_RING = 40.0
    }

    // ---------------------------------------------------------------------------------------------
    // Kronos (Plan 4.5, Brief kronos.md)
    // ---------------------------------------------------------------------------------------------

    object Kronos {
        // --- Kette A „Ernte“: SWEEP → SWEEP_L → CLEAVE → STUCK ---
        const val SWEEP_WINDUP = 22
        const val SWEEP_END = 34
        const val SWEEP_L_WINDUP = 16
        const val SWEEP_L_END = 28
        const val SWEEP_STRIKE = 3
        const val SWEEP_HALF_ANGLE = 100f
        const val SWEEP_RADIUS = 13f
        const val CLEAVE_WINDUP = 24
        const val CLEAVE_STRIKE = 3
        const val CLEAVE_WIDTH = 3f
        const val CLEAVE_LENGTH = 22f
        const val STUCK_WINDOW = 36

        // --- Kette B „Äon“: SLAM → Ringe → SWEEP ---
        /** Kreis Radius 8. Plan: 18 (unter [Min.AREA]), darum 20. */
        const val SLAM_WINDUP = 20
        /** Er kniet bis hier (Brief: Einschlag + 30). */
        const val SLAM_END = 50
        const val SLAM_RADIUS = 8f
        const val SLAM_RINGS = 3
        const val SLAM_RINGS_PHASE2 = 4
        const val SLAM_RING_GAP = 16
        /** F nach Kette B. */
        const val AEON_WINDOW = 20

        // --- Kette C „Wurf“: THROW → EMPTY ---
        const val THROW_WINDUP = 22
        const val THROW_WIDTH = 3f
        /** Die Harpe fliegt hin, steht, fliegt zurück (zweimal dieselbe Linie). */
        const val HARPE_OUT = 20
        const val HARPE_HOVER = 10
        const val HARPE_BACK = 20
        const val EMPTY_WINDOW = 40

        // --- Kette D „Zeitschritt“: STEP → SWEEP_L → CLEAVE → STUCK ---
        const val STEP_WINDUP = 20

        // --- Uhrzeiger (HANDS) ---
        const val HANDS_INTERVAL = 900
        const val HANDS_WINDUP = 34
        const val HANDS_SWEEP = 120
        const val HANDS_WINDOW = 40

        // --- Stillstand (HALT) ---
        const val HALT_INTERVAL = 1200
        /** Heben, dann steht die Zeit … */
        const val HALT_RAISE = 14
        const val HALT_FROZEN = 40
        /** … bis hier (Faust schließt sich), dann schlagen die Kreise nach [HALT_IMPACT_DELAY] ein. */
        const val HALT_RESUME = 54
        const val HALT_IMPACT_DELAY = 16
        const val HALT_HIT = 70
        /** Slowness II für die Spieler, so lange. */
        const val HALT_SLOWNESS = 30
        const val HALT_RADIUS = 2.8f
        /** Kreise neben jedem Spieler zusätzlich zu dem unter ihm. */
        const val HALT_EXTRA_CIRCLES = 2
        const val HALT_WINDOW = 14

        // --- Rückspulung (REWIND) ---
        const val REWIND_TIME = 240
        const val REWIND_STAGGER = 110
        const val REWIND_GLASSES_SOLO = 2
        const val REWIND_GLASSES_GROUP = 3

        // --- Phase II ---
        /** Pausen zwischen den Ketten × diesem Faktor. */
        const val PHASE2_PAUSE_FACTOR = 0.7f

        /** Verschlingen (DEVOUR) nach jeder [DEVOUR_EVERY]-ten Kette: Sog, Biss, Erholung (offen). */
        const val DEVOUR_EVERY = 2
        const val DEVOUR_PULL = 50
        const val DEVOUR_BITE = 60
        const val DEVOUR_WINDOW = 36
        const val DEVOUR_END = 96
        const val DEVOUR_RADIUS = 10f
        const val DEVOUR_WITHER = 80

        /** Saal-Gefahr im Wechsel. */
        const val HAZARD_INTERVAL = 320
        const val AGING_WINDUP = 60
        const val AGING_COLLAPSE = 110
        const val SAND_WINDUP = 22
        const val SAND_WAVES = 3

        /** Hades hilft: Ketten halten ihn (CHAINED, offen). */
        const val CHAINED_INTERVAL = 800
        const val CHAINED = 45

        // --- Ergänzt von K3 (Plan ließ diese Werte offen) ---
        /** Grundpause zwischen zwei Ketten (Phase II × [PHASE2_PAUSE_FACTOR]). */
        const val PAUSE = 20
        /** Er schreitet höchstens so lange auf sein Ziel zu; alle [STRIDE_STEP] Ticks ein leiser Schritt. */
        const val STRIDE_MAX = 46
        const val STRIDE_STEP = 14
        /** Taumeln, wenn seine Haltung bricht (POISE_LIMIT × 2,2), danach [RISE] Ticks Aufrichten. */
        const val POISE_STAGGER = 60
        const val RISE = 14
        /** Äonenschlag: Die Ringe laufen so schnell nach außen (Blöcke je Tick); getroffen wird im Band ± [SLAM_RING_DEPTH]. */
        const val SLAM_RING_SPEED = 0.85
        const val SLAM_RING_DEPTH = 1.1
        /** Wurf: Ansage des Rückflugs auf derselben Linie (beginnt, solange die Harpe noch hinfliegt). */
        const val HARPE_RETURN_WINDUP = 16
        /** Zeitschritt: Rune, an der er erscheint. */
        const val STEP_RUNE_RADIUS = 4.5f
        /** Uhrzeiger: der lange Zeiger braucht so lange für eine Umdrehung, der kurze läuft mit [HANDS_HOUR_SPEED] gegen. */
        const val HANDS_TURN = 150
        const val HANDS_HOUR_SPEED = 0.55
        const val HANDS_WIDTH = 2f
        /** Ein Spieler wird von demselben Zeiger höchstens alle so viele Ticks getroffen. */
        const val HANDS_REHIT = 16
        /** Erste Uhrzeiger und erster Stillstand einer Phase (versetzt). */
        const val HANDS_FIRST = 400
        const val HALT_FIRST = 840
        /** Stillstand: Abstand der zwei Nebenkreise vom Spieler. */
        const val HALT_SPREAD = 5.0
        /** Rückspulung bei diesen Lebensanteilen (Phase I, Phase II). */
        @JvmField val REWIND_AT_1 = floatArrayOf(0.7f, 0.35f)
        @JvmField val REWIND_AT_2 = floatArrayOf(0.5f)
        /** Sanduhren stehen auf diesem Ring um die Mitte. */
        const val REWIND_RING = 16.0
        /** Verschlingen: Zug zur Mitte alle [DEVOUR_TUG] Ticks. */
        const val DEVOUR_TUG = 4
        /** Alternder Boden: halber Öffnungswinkel des Keils, Schaden im Loch alle [AGING_TICK] Ticks. */
        const val AGING_HALF_ANGLE = 37.5f
        const val AGING_TICK = 10
        /** Zeitsand: Abstand der Wellen (Beginn zu Beginn) und Radius je Kreis. */
        const val SAND_GAP = 18
        const val SAND_RADIUS = 2.6f
        /** Erste Saal-Gefahr und erste Hilfe des Hades in Phase II. */
        const val HAZARD_FIRST = 200
        const val CHAINED_FIRST = 300
    }

    // ---------------------------------------------------------------------------------------------
    // Die Wächter (Plan 4.6, Briefs der Wächter). Aktionen über `MiniBossKit.act`, Zeit über `actionTime`.
    // ---------------------------------------------------------------------------------------------

    object Guardian {
        /** Höchste Aktionsnummer; 0 beendet die laufende Aktion. */
        const val ACTION_KINDS = 20

        /** Nach so vielen Ticks fällt eine Aktion auf dem Client von selbst auf 0 zurück. */
        const val ACTION_MAX = 200

        // --- Ergänzt von K4 (Plan 4.6 nennt sie nicht als Zahl) ---
        /** Leine: Weiter als so viele Blöcke von der Mitte seines Platzes geht er zurück und heilt. */
        const val LEASH = 34.0
        /** Wer so nah an der Mitte ist, kämpft mit (Lebensleiste, Ansagen, Spielerzahl). */
        const val FIGHT_RADIUS = 48.0
        /** Erholung zwischen zwei Angriffen (zusätzlich zum Fenster des Angriffs). */
        const val PAUSE = 16
        /** Herangehen: mindestens so lange (plus Zufall bis zum Doppelten), höchstens [APPROACH_MAX]. */
        const val APPROACH_MIN = 12
        const val APPROACH_MAX = 80
        /** Ist so lange niemand in der Nähe, zieht er sich zurück (erscheint beim nächsten Besuch neu). */
        const val LONELY = 600
        /** Auftritt: Brüllen, bevor der erste Angriff kommt. */
        const val ENTRANCE = 30
    }

    /** Der Kerkermeister (`WardenAction`). */
    object Warden {
        /** CHOP: Ausholen halten bis zum Hieb, Beil steckt bis [CHOP_STUCK_END] (offen), Ende [CHOP_END]. */
        const val CHOP_WINDUP = 20
        const val CHOP_STRIKE = 2
        const val CHOP_WINDOW = 30
        const val CHOP_STUCK_END = 50
        const val CHOP_END = 56
        const val CHOP_WIDTH = 3f
        const val CHOP_LENGTH = 7f

        /** DOUBLE_CHOP (unter 40 % Leben): zweiter Hieb [DOUBLE_CHOP_GAP] nach dem ersten. */
        const val DOUBLE_CHOP_GAP = 14
        const val DOUBLE_CHOP_SECOND = 34
        const val DOUBLE_CHOP_STUCK_END = 64
        const val DOUBLE_CHOP_END = 70

        /** CHAIN: Schwung bis zum Wurf, gestreckt bis zum Ruck, Ende. */
        const val CHAIN_WINDUP = 16
        const val CHAIN_PULL = 26
        const val CHAIN_END = 36
        const val CHAIN_RANGE = 20f
        /** HOOKED: Haken hängt fest (offen). */
        const val HOOKED_WINDOW = 40

        /** ROUND: Ausholen, Hieb, Nachlauf. */
        const val ROUND_WINDUP = 14
        const val ROUND_STRIKE = 3
        const val ROUND_END = 34
        const val ROUND_WINDOW = 20
        const val ROUND_HALF_ANGLE = 90f
        const val ROUND_RADIUS = 5f

        const val RAGE = 30
        const val STAGGER = 60

        // --- Ergänzt von K4 ---
        /** Volle Breite der Kettenlinie. */
        const val CHAIN_WIDTH = 1.4f
        /** So viele Treffer auf die Laterne (von hinten) bringen ihn ins Taumeln ([STAGGER]). */
        const val LANTERN_HITS = 3
    }

    /** Kampe (`KampePose`; RenderState wie bei den Bossen mit `poseTime`). */
    object Kampe {
        /** TAIL_SWEEP: Ausholen halten, Feger, ausgeschwungen. */
        const val SWEEP_WINDUP = 18
        const val SWEEP_STRIKE = 4
        const val SWEEP_END = 40
        const val SWEEP_HALF_ANGLE = 120f
        const val SWEEP_RADIUS = 8f

        /** SPIT: Giftfächer. */
        const val SPIT_WINDUP = 16
        const val SPIT_END = 30
        const val SPIT_HALF_ANGLE = 30f
        const val SPIT_SHOTS = 5
        /** Gift nur so lange. */
        const val SPIT_POISON = 40

        /** STING: Ausholen halten, Stich, Stachel steckt bis [STING_STUCK_END] (offen), Ende. */
        const val STING_WINDUP = 20
        const val STING_STRIKE = 2
        const val STING_WINDOW = 36
        const val STING_STUCK_END = 56
        const val STING_END = 64
        const val STING_RADIUS = 2.5f

        /** COIL_UP: windet sich am Titanen hoch. */
        const val COIL = 30

        /** DIVE: dreimal herab, dann DAZED. */
        const val DIVE_COUNT = 3
        const val DIVE_WINDUP = 24
        const val DIVE_WIDTH = 3f
        const val DAZED = 60

        const val ROAR = 30
        const val DEATH = 40

        // --- Ergänzt von K4 ---
        /** Reichweite des Giftfächers (Kegel). */
        const val SPIT_RANGE = 16f
        /** Der Stachel sticht höchstens so weit vor ihr ein. */
        const val STING_REACH = 6f
        /** Sturzstoß: vom Titanen bis zum Boden, dann gleitet sie die Linie entlang. */
        const val DIVE_DROP = 6
        const val DIVE_SLIDE = 8
        /** Länge der Sturzlinie am Boden. */
        const val DIVE_LENGTH = 16f
        /** Sieht sie von oben niemanden (alle hinter dem Titanen), wartet sie so lange und sucht erneut. */
        const val DIVE_SEARCH = 20
    }

    /** Sisyphos (`SisyphusAction`). */
    object Sisyphus {
        /** THROW: Stemmen bis [THROW_LIFT], halten, Wurf (ab da kein Fels), ausgelaufen bis [THROW_END]. */
        const val THROW_LIFT = 14
        const val THROW_WINDUP = 24
        const val THROW_END = 44
        const val THROW_RADIUS = 3.2f
        /** Der geworfene Fels bleibt so lange liegen (8 s). */
        const val BOULDER_LIE = 160

        /** BOWL: Absetzen, halten, Stoß, Ende. */
        const val BOWL_SET = 10
        const val BOWL_WINDUP = 22
        const val BOWL_END = 40
        const val BOWL_WIDTH = 4f
        const val BOWL_LENGTH = 24f

        /** STOMP: Kreis Radius 6. Plan: 16 (unter [Min.AREA]), darum 20; Ende entsprechend 34 → 38. */
        const val STOMP_WINDUP = 20
        const val STOMP_END = 38
        const val STOMP_RADIUS = 6f

        /** REGROW: ohne Fels (offen); der neue wächst ab [REGROW_START]. */
        const val REGROW = 60
        const val REGROW_START = 36

        /** CURSE „Der Stein rollt zurück“: drei Felsen nacheinander, je mit eigener Linie. */
        const val CURSE = 40
        const val CURSE_ROCKS = 3
        const val CURSE_WINDUP = 30

        /** Vom eigenen Fels getroffen. */
        const val STAGGER = 60

        // --- Ergänzt von K4 (W0 ließ den Felsabstand offen) ---
        /** „Der Stein rollt zurück“: Abstand der Felsen (Ansage zu Ansage). */
        const val CURSE_GAP = 26
        /** Nach dem ersten Mal kehrt der Fluch als Saal-Gefahr so oft wieder. */
        const val CURSE_INTERVAL = 520
        /** So weit rollt ein Fluch-Fels höchstens. */
        const val CURSE_LENGTH = 40f
        /** Rollender Fels: Blöcke je Tick. */
        const val ROLL_SPEED = 1.1
        /** Felswurf: Flugzeit nach dem Abwurf (aus der Entfernung, in diesen Grenzen). */
        const val THROW_FLIGHT_MIN = 10
        const val THROW_FLIGHT_MAX = 20
    }

    /** Ixion (`IxionAction`). */
    object Ixion {
        /** ROLL_WINDUP 0–20, dann ROLL über die Linie. */
        const val ROLL_WINDUP = 20
        const val ROLL_WIDTH = 3f
        const val FIRE_TRAIL = 60
        const val FIRE_TICK = 10
        /** WOBBLE: das Rad kippt (offen), dann richtet es sich in [WOBBLE_RIGHT] auf. */
        const val WOBBLE_WINDOW = 30
        const val WOBBLE_RIGHT = 8

        /** SPOKES: Kippen = Ansage der Linien, danach drehen die Strahlen. */
        const val SPOKES_WINDUP = 24
        const val SPOKES_LINES = 6
        const val SPOKES_SWEEP = 60

        /**
         * SPARKS: Schüsse bei [SPARK_SHOT_1] + n · [SPARK_GAP]; jeder Kreis steht [SPARK_WINDUP] bis zum Einschlag,
         * erscheint also [SPARK_FLIGHT] Ticks vor dem Schuss.
         */
        const val SPARK_COUNT = 3
        const val SPARK_SHOT_1 = 12
        const val SPARK_GAP = 8
        const val SPARK_WINDUP = 20
        const val SPARK_FLIGHT = SPARK_WINDUP - SPARK_SHOT_1
        const val SPARKS_END = 40
        const val SPARK_RADIUS = 2.5f

        /** FALLEN: nach drei genutzten Kipp-Fenstern; Fallen 0–10, liegen bis [FALLEN] (offen). */
        const val FETTERS = 3
        const val FALLEN_DROP = 10
        const val FALLEN = 100
        const val RISE = 30

        // --- Ergänzt von K4 ---
        /** Schwebehöhe in Ruhe (Blöcke über dem Boden), beim Rollen und in SPOKES. */
        const val HOVER = 1.4
        const val HOVER_SPOKES = 2.6
        /** Feuerrad: Blöcke je Tick, Länge der Bahn (höchstens, mindestens). */
        const val ROLL_SPEED = 1.0
        const val ROLL_LENGTH = 26f
        const val ROLL_MIN = 8f
        /** Speichenfeuer: Strahl von [SPOKES_INNER] bis [SPOKES_LENGTH] ab der Nabe, volle Breite [SPOKES_WIDTH]. */
        const val SPOKES_INNER = 2.2f
        const val SPOKES_LENGTH = 11f
        const val SPOKES_WIDTH = 1.6f
        /** In [SPOKES_SWEEP] Ticks dreht das Rad so viele Umdrehungen (von oben im Uhrzeigersinn, wie das Modell). */
        const val SPOKES_TURN = 0.75
        /** Danach richtet es sich so lange auf (Modell gleitet zurück). */
        const val SPOKES_RIGHT = 10
        /** So lange schwebt er höchstens zur Mitte, bevor das Speichenfeuer beginnt. */
        const val SPOKES_TRAVEL = 50
    }

    /** Tantalos (`TantalusAction`). */
    object Tantalus {
        /** THRUST3: Stöße bei 12, 26, 40, danach vorgebeugt bis [THRUST_END] (offen). */
        const val THRUST_COUNT = 3
        const val THRUST_WINDUP = 12
        const val THRUST_GAP = 14
        const val THRUST_HIT_2 = 26
        const val THRUST_HIT_3 = 40
        const val THRUST_WINDOW = 24
        const val THRUST_END = 64
        const val THRUST_WIDTH = 2f
        const val THRUST_LENGTH = 7f
        /** Folgestöße weichen höchstens so weit ab (Grad). */
        const val THRUST_MAX_TURN = 20f

        /** GEYSER: Heben bis zum ersten Ruf, Rufe im Abstand [GEYSER_GAP]; jeder Kreis steht [GEYSER_WINDUP]. */
        const val GEYSER_COUNT = 3
        const val GEYSER_RAISE = 10
        const val GEYSER_GAP = 10
        const val GEYSER_WINDUP = 22
        const val GEYSER_END = 44
        const val GEYSER_RADIUS = 2.6f

        /** WAVE: Ring bis Radius 14. Plan: 18 (unter [Min.AREA]), darum 20; Knien entsprechend bis 42. */
        const val WAVE_WINDUP = 20
        const val WAVE_END = 42
        const val WAVE_RADIUS = 14f

        /** DRINK: Hinknien, dann Schöpfen im Zyklus; heilt alle [DRINK_HEAL_TICK]. */
        const val DRINK_KNEEL = 12
        const val DRINK_CYCLE = 20
        const val DRINK = 80
        const val DRINK_HEAL_TICK = 10

        /** PARCHED: Kelch aus der Hand geschlagen (offen). */
        const val PARCHED = 60
        const val RAGE = 20

        // --- Ergänzt von K4 ---
        /** Flutwelle: So viele Ticks läuft die Front vom Dreizack bis [WAVE_RADIUS] (darüber springen). */
        const val WAVE_TRAVEL = 7
    }

    /** Teufelchen: Stich über `handSwing`, Feuerball über `charging`/`chargeTime`. */
    object Imp {
        const val STAB_WINDUP = 10
        const val STAB_END = 18
        /** Aufladen bis zum Wurf. Plan: 10 (unter [Min.PROJECTILE]), darum 16; Ende 20 → 26. */
        const val FIREBALL_WINDUP = 16
        const val FIREBALL_END = 26
        const val HURT = 10

        // --- Ergänzt von K4 ---
        /** Stich trifft bis zu dieser Entfernung (Mitte zu Mitte). */
        const val STAB_REACH = 2.6
        /** Pause nach einem Angriff (plus Zufall bis zum Doppelten). */
        const val REST = 30
        /** Höchstens so viele Teufelchen in einer Gruppe. */
        const val GROUP = 2
    }

    /** Hermes als Jäger im Tartaros (Plan 4.6). */
    object Hunter {
        /** Schnellleiste höchstens einmal je 5 Minuten … */
        const val STEAL_COOLDOWN = 6000
        /** … und nur so viele Vertauschungen. */
        const val STEAL_SWAPS = 2

        /** Mindestabstand zwischen zwei Auftritten (45 s). */
        const val MIN_GAP = 900
    }
}
