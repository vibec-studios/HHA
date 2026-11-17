package dev.henny.hha.stage.scripts

import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.HhaVoiceLengths
import dev.henny.hha.stage.EaseKind
import dev.henny.hha.stage.Key
import dev.henny.hha.stage.StageBuilder
import dev.henny.hha.stage.StageScripts
import dev.henny.hha.stage.stage
import dev.henny.hha.world.TartarusPlans

/**
 * Bühnen-Skripte von P12: die Szenen des Doppelkampfs im Thronsaal (Hades und Kronos, PLAN 2.9/2.10).
 *
 * **Anker** ist immer die Mitte des Hallenbodens (`HadesFight.center`) mit Blick [ANCHOR_YAW] = 180°: Dann zeigt „vorn“
 * zum Thron (−Z) und „rechts“ nach +X, und [w] schreibt Punkte genau wie die alten Kamera-Aufrufe als Versatz zur
 * Saalmitte (dx, dy, dz). Darsteller 0 ist Hades; in `hades.intro` und `hades.yield` ist Darsteller 1 Hermes, in
 * `kronos.wake` und `kronos.end` Kronos (feste Absprache mit P13).
 *
 * **Zeitleiste = Ablauf am Server.** Die Szenen laufen weiter als eigene Abläufe in `HadesFight` (Zeilen über
 * `Cine.dialog`, Wartezeiten, Posen, Blöcke); die Skripte liefern nur Kamera, Rucke, Klänge, Effekte und Musik. Damit
 * beides genau zusammenpasst, rechnen Server und Skript ihre Zeitpunkte aus denselben Objekten ([Intro], [Phase2],
 * [Phase3], [Yield], [Wake], [End]): Zeilenlänge über [line] (Aufnahme aus `HhaVoiceLengths`, sonst die Grundzeit, plus
 * [LINE_GAP]) – genau so, wie `HadesFight.say` wartet. Jeder Zeitpunkt ist zusätzlich eine Marke; der Server prüft im
 * Ablauf, ob er sie im selben Tick erreicht (sonst eine Warnung im Log).
 *
 * Jede Szene beginnt mit Blende oder Schnitt und endet in den Augen (`Key.Eye`), außer `hades.yield`: Daran schließt
 * `kronos.wake` im selben Tick mit einem Schnitt an.
 */
object HadesStages {

    /** Blick des Ankers (Grad): „vorn“ = zum Thron. */
    const val ANCHOR_YAW = 180f

    /** Pause nach jeder Zeile (wie `HadesFight.say`). */
    const val LINE_GAP = 4

    /** Ticks, die `HadesFight.say(…, key, base)` wartet: Lesedauer der Zeile plus [LINE_GAP]. */
    fun line(key: String, base: Int): Int = HhaVoiceLengths.ticks("hha.hermes.line.$key", base) + LINE_GAP

    /** Punkt als Versatz zur Saalmitte (wie die alten Kamera-Aufrufe), bei Anker-Blick [ANCHOR_YAW]. */
    private fun w(dx: Double, dy: Double, dz: Double): Key = Key.At(-dz, dx, dy)

    private const val HADES = 0
    private const val SECOND = 1

    /** Thronsitz (Versatz zur Mitte), wie `HadesFight.throneSeat`. */
    private val SEAT_Y = TartarusPlans.DAIS_HEIGHT.toDouble()
    private val SEAT_Z = TartarusPlans.THRONE_Z.toDouble()

    private const val MUSIC_HADES = "hha:music.hades"
    private const val MUSIC_HADES_2 = "hha:music.hades_2"
    private const val MUSIC_KRONOS = "hha:music.kronos"
    private const val THUD = "hha:boss.thud"

    // ------------------------------------------------------------------------------------------------------------
    // Zeitpunkte (Ticks ab Szenenbeginn). Werte und Reihenfolge = Ablauf in HadesFight, keine eigenen Zeiten.
    // ------------------------------------------------------------------------------------------------------------

    /** `hades.intro` (`HadesFight.intro`): Fahrt zum Thron, Hermes wird entlassen, Hades steht auf und schlägt auf. */
    object Intro {
        const val ID = "hades.intro"
        const val LEAD = 14
        val HOST = LEAD + line("hades.enter", 70)
        val EXCUSE = HOST + line("hades.host", 86)
        val DISMISS = EXCUSE + line("hades.excuse", 70)
        val RISE = DISMISS + line("hades.dismiss", 58)
        val GLIDE = RISE + line("hades.rise", 54)
        const val GLIDE_TICKS = 28
        val SLAM = GLIDE + GLIDE_TICKS
        val HIT = SLAM + BossTimings.Hades.SLAM_WINDUP
        const val AFTER = 34
        val LENGTH = HIT + AFTER

        /** Hermes kniet hier (Versatz zur Mitte), wie in `HadesFight.intro`. */
        val MESSENGER = Triple(5.0, 0.2, SEAT_Z + 9.0)
    }

    /** `hades.phase2` (`HadesFight.falseVictory`, ab dem zweiten `Cine.start`): Er steht wieder auf, die Pforten reißen. */
    object Phase2 {
        const val ID = "hades.phase2"
        const val LEAD = 10
        val RISE = LEAD + line("hades.bait", 80)
        const val RISE_HOLD = 16
        val SPEAK = RISE + RISE_HOLD
        val GATES = SPEAK + line("hades.gates", 80)
        const val GATES_HOLD = 50
        val TITLE = GATES + GATES_HOLD
        const val AFTER = 30
        val LENGTH = TITLE + AFTER
    }

    /** `hades.phase3` (`HadesFight.secondDeath`): Hermes kommt mit dem Lieferschein und wird sein Seelenführer. */
    object Phase3 {
        const val ID = "hades.phase3"
        const val HERMES = 70
        val STIR = HERMES + line("hades.sign", 56)
        const val STIR_HOLD = 20
        val RISE = STIR + STIR_HOLD
        val WEALTH = RISE + line("hades.bait2", 66)
        val OFFER = WEALTH + line("hades.wealth", 90)
        const val OFFER_HOLD = 30
        val PACT = OFFER + OFFER_HOLD
        val ORBIT = PACT + line("hades.pact", 72)
        val TITLE = ORBIT + line("hades.together", 60)
        const val AFTER = 20
        val LENGTH = TITLE + AFTER

        /** Hermes erscheint hier (Versatz zur Mitte), Hades liegt bei (0, 0, −6). */
        val MESSENGER = Triple(4.0, 0.2, 7.0)
    }

    /** `hades.yield` (`HadesFight.fatherRises`, erster Teil): Hermes flieht, Hades gibt auf. */
    object Yield {
        const val ID = "hades.yield"
        const val LEAD = 10
        val FLEE = LEAD + line("hades.never", 60)
        val LENGTH = FLEE + line("hades.enough", 74)
        val MESSENGER = Triple(5.0, 0.2, -2.0)
    }

    /** `kronos.wake` (`HadesFight.fatherRises`, zweiter Teil): Die Kuppel reißt, Kronos sinkt herab und landet. */
    object Wake {
        const val ID = "kronos.wake"
        val BREAK = line("kronos.wake", 70)
        const val DESCENT_TICKS = 110
        val SLAM = BREAK + DESCENT_TICKS
        val LAND = SLAM + BossTimings.Kronos.SLAM_WINDUP
        val FATHER = LAND + line("kronos.son", 80)
        val ROAR = FATHER + line("hades.father", 40)
        val TITLE = ROAR + line("kronos.devour", 80)
        const val AFTER = 30
        val LENGTH = TITLE + AFTER

        /** Hier landet er (Versatz zur Mitte). */
        const val LAND_Z = -2.0

        /** Auge der Kuppel (Höhe über dem Boden), wie `HadesFight.oculus`. */
        const val OCULUS = TartarusPlans.OCULUS_Y + 1.0

        /** Linse am Riss: schwach, kurz (PLAN-Ausnahme: nur in dieser Szene). */
        const val LENS_RADIUS = 10f
        const val LENS_STRENGTH = 0.4f
        const val LENS_TICKS = 30
        const val SKY_KICK = 3f
        const val LAND_KICK = 3.5f
    }

    /** `kronos.end` (`HadesFight.titanBound`): Hades bindet ihn, er zerfällt in Licht und Sand, dann das Geschenk. */
    object End {
        const val ID = "kronos.end"
        const val LEAD = 20
        val SEAL = LEAD + line("kronos.fall", 80)
        val BIND = SEAL + line("hades.seal", 70)
        const val BIND_TICKS = 100
        val FREED = BIND + BIND_TICKS
        const val FREED_HOLD = 30
        val GIFT = FREED + FREED_HOLD
        val GRANT = GIFT + line("hades.gift", 80)
        const val TITLE_HOLD = 80
        val FADE = GRANT + TITLE_HOLD
        const val FADE_TICKS = 22
        val LENGTH = FADE + FADE_TICKS

        /** Kronos steht hier gebunden, Hades bei (−7, 0, 4). */
        const val TITAN_Z = -4.0

        /** `kronos.death` (P13): Lebenszeit; der Körper verschwindet am Server erst danach. */
        const val DEATH_LIFE = 200
    }

    fun register() {
        StageScripts.register(intro())
        StageScripts.register(phase2())
        StageScripts.register(phase3())
        StageScripts.register(yieldScene())
        StageScripts.register(wake())
        StageScripts.register(end())
    }

    /** Fahrt zurück in die Augen über [from] … [to] (letzte Einstellung jeder Szene). */
    private fun StageBuilder.toEyes(from: Int, to: Int) {
        shot(from, to, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), ease = EaseKind.SMOOTH, handheld = 0f)
    }

    private fun intro() = with(Intro) {
        val (mx, my, mz) = MESSENGER
        stage(ID, LENGTH) {
            // Lange Fahrt durch den Saal auf den Thron zu (aus der Schwarzblende des Servers).
            shot(0, HOST, cam = listOf(w(0.0, 9.0, 24.0), w(0.0, 6.5, 10.0), w(0.0, 4.6, -4.0)),
                look = listOf(w(0.0, SEAT_Y + 3.0, SEAT_Z), w(0.0, SEAT_Y + 2.4, SEAT_Z)), fov = 70f to 46f, handheld = 0.6f)
            shot(HOST, EXCUSE, cam = listOf(w(-3.5, SEAT_Y + 1.6, SEAT_Z + 8.0), w(-1.8, SEAT_Y + 2.2, SEAT_Z + 5.6)),
                look = listOf(Key.Actor(HADES, 2.7)), fov = 44f to 38f, cut = true)
            shot(EXCUSE, DISMISS, cam = listOf(w(mx - 2.4, my + 1.2, mz + 3.2), w(mx - 1.8, my + 1.4, mz + 2.4)),
                look = listOf(Key.Actor(SECOND, 1.2)), fov = 44f to 38f, cut = true)
            shot(DISMISS, RISE, cam = listOf(w(2.2, SEAT_Y + 1.6, SEAT_Z + 7.0), w(1.2, SEAT_Y + 2.0, SEAT_Z + 5.0)),
                look = listOf(Key.Actor(HADES, 2.6)), fov = 44f to 38f, cut = true)
            // Er steht auf: weite, tiefe Einstellung, der Blick folgt ihm.
            shot(RISE, GLIDE, cam = listOf(w(9.0, 2.6, -9.0), w(6.0, 1.8, -6.0)), look = listOf(Key.Actor(HADES, 3.0)),
                fov = 66f to 58f, follow = 0.3f, cut = true)
            // Er schreitet herab; die Kamera weicht tief nach vorn zurück.
            shot(GLIDE, HIT, cam = listOf(Key.Last, w(4.5, 1.2, -1.5)), look = listOf(Key.Last, Key.Actor(HADES, 3.2)),
                fov = 58f to 62f, follow = 0.2f)
            // Der Bident schlägt auf: nah, von unten.
            shot(HIT, HIT + 16, cam = listOf(w(-2.5, 0.6, 1.0), w(-2.2, 0.9, -0.4)), look = listOf(Key.Actor(HADES, 3.2)),
                fov = 58f to 50f, cut = true, handheld = 1.4f)
            toEyes(HIT + 16, LENGTH)

            mark(HOST, "host"); mark(EXCUSE, "excuse"); mark(DISMISS, "dismiss"); mark(RISE, "rise"); mark(GLIDE, "glide")
            mark(SLAM, "slam"); mark(HIT, "hit")
            kick(HIT, pitch = 3f, ticks = 8)
            sound(HIT, THUD, volume = 1f, pitch = 0.8f)
            fx(HIT, "core.cracks", Vfx.Style.HADES, w(0.0, 0.0, -6.0), a = 7f, b = 6f, life = 60)
            fx(HIT, "core.debris", Vfx.Style.HADES, w(0.0, 0.0, -6.0), a = 4f, b = 6f, c = 0.55f, d = 0.25f, life = 80)
            music(HIT, MUSIC_HADES)
        }
    }

    private fun phase2() = with(Phase2) {
        stage(ID, LENGTH) {
            // Er liegt noch: vor seinem Gesicht (relativ zu seiner Lage und seinem Blick), aus der Blende des Servers.
            shot(0, RISE, cam = listOf(Key.ActorRel(HADES, 7.0, 3.0, 1.2), Key.ActorRel(HADES, 5.0, 1.5, 1.4)),
                look = listOf(Key.Actor(HADES, 0.8), Key.Actor(HADES, 1.8)), fov = 56f to 46f)
            // Er steht auf, größer als vorher: die Kamera weicht zurück und hebt sich mit ihm.
            shot(RISE, SPEAK, cam = listOf(Key.Last, Key.ActorRel(HADES, 8.0, 2.2, 1.8)), look = listOf(Key.Last, Key.Actor(HADES, 3.4)),
                fov = 46f to 50f, ease = EaseKind.OUT)
            shot(SPEAK, GATES, cam = listOf(Key.Last, Key.ActorRel(HADES, 6.5, 1.0, 2.6)), look = listOf(Key.Actor(HADES, 3.4)),
                fov = 50f to 42f, follow = 0.2f)
            // Die Pforten reißen auf: hoch und weit, ein Bogen um ihn durch den Saal.
            shot(GATES, TITLE, cam = listOf(w(18.0, 9.0, 16.0), w(-6.0, 10.0, 23.0)), look = listOf(Key.Actor(HADES, 3.0)),
                fov = 64f to 58f, arc = true, cut = true, follow = 0.25f)
            toEyes(TITLE, LENGTH)

            mark(RISE, "rise"); mark(SPEAK, "speak"); mark(GATES, "gates"); mark(TITLE, "title")
            kick(RISE, pitch = 2.5f, ticks = 8)
            sound(RISE, THUD, volume = 0.9f, pitch = 0.75f)
            fx(RISE, "core.cracks", Vfx.Style.HADES, Key.Actor(HADES, 0.0), a = 6f, b = 5f, life = 70)
            music(GATES, MUSIC_HADES_2)
        }
    }

    private fun phase3() = with(Phase3) {
        val (mx, my, mz) = MESSENGER
        stage(ID, LENGTH) {
            shot(0, HERMES, cam = listOf(w(8.0, 3.0, 4.0), w(5.0, 2.0, 1.0)), look = listOf(Key.Actor(HADES, 0.4)), fov = 56f to 48f)
            // Hermes liest den Lieferschein.
            shot(HERMES, STIR, cam = listOf(w(mx + 2.4, my + 1.6, mz - 3.0), w(mx + 1.6, my + 1.8, mz - 2.2)),
                look = listOf(w(mx, my + 1.5, mz)), fov = 44f to 38f, cut = true)
            // Hinter ihm regt sich etwas: über seine Schulter auf Hades.
            shot(STIR, RISE, cam = listOf(Key.Last, w(mx + 1.5, my + 2.0, mz + 2.5)), look = listOf(Key.Last, Key.Actor(HADES, 2.0)),
                fov = 44f to 52f)
            // Er steht auf, mit allen Schwingen.
            shot(RISE, WEALTH, cam = listOf(w(2.0, 2.0, 5.0), w(1.0, 3.0, 2.5)), look = listOf(Key.Actor(HADES, 3.6), Key.Actor(HADES, 4.2)),
                fov = 54f to 42f, cut = true, handheld = 1.2f)
            shot(WEALTH, OFFER, cam = listOf(Key.Last, w(-2.2, 3.2, 1.8)), look = listOf(Key.Last, Key.Actor(HADES, 4.0)),
                fov = 42f to 40f, arc = true)
            // Das Angebot: die Kette zu Hermes, beide im Bild.
            shot(OFFER, PACT, cam = listOf(w(9.0, 3.5, 3.0), w(8.5, 3.2, 2.0)), look = listOf(w(2.0, 2.0, 0.5)), fov = 58f to 56f, cut = true)
            // Hermes als Geist.
            shot(PACT, ORBIT, cam = listOf(w(mx + 2.0, my + 1.6, mz + 3.0), w(mx + 1.4, my + 1.8, mz + 2.2)),
                look = listOf(w(mx, my + 1.6, mz)), fov = 44f to 38f, cut = true)
            // „Gemeinsam“: Bogen um Hades.
            shot(ORBIT, TITLE, cam = listOf(w(14.0, 4.0, 9.0), w(5.0, 4.5, 16.0)), look = listOf(Key.Actor(HADES, 3.2)),
                fov = 62f to 56f, arc = true, cut = true)
            toEyes(TITLE, LENGTH)

            mark(HERMES, "hermes"); mark(STIR, "stir"); mark(RISE, "rise"); mark(WEALTH, "wealth"); mark(OFFER, "offer")
            mark(PACT, "pact"); mark(ORBIT, "orbit"); mark(TITLE, "title")
            shake(STIR, RISE, 0.5f)
            kick(RISE, pitch = 3f, ticks = 8)
            sound(RISE, THUD, volume = 1f, pitch = 0.7f)
            fx(RISE, "core.cracks", Vfx.Style.HADES, Key.Actor(HADES, 0.0), a = 7f, b = 6f, life = 70)
            music(TITLE, MUSIC_HADES_2)
        }
    }

    private fun yieldScene() = with(Yield) {
        val (mx, my, mz) = MESSENGER
        stage(ID, LENGTH) {
            // Hermes lehnt ab (kurze Blende aus dem Kampf).
            shot(0, FLEE, cam = listOf(w(mx + 2.0, my + 1.6, mz + 3.0), w(mx + 1.4, my + 1.8, mz + 2.2)),
                look = listOf(Key.Actor(SECOND, 1.6)), fov = 44f to 38f)
            // Hades auf den Knien: „genug“.
            shot(FLEE, LENGTH, cam = listOf(w(2.0, 1.8, 1.5), w(1.0, 2.0, -0.4)), look = listOf(Key.Actor(HADES, 2.6)),
                fov = 46f to 40f, cut = true)
            fade(0, 0x000000, 1f, 0f, 12)
            mark(FLEE, "flee")
        }
    }

    private fun wake() = with(Wake) {
        val land = w(0.0, 0.0, LAND_Z)
        stage(ID, LENGTH) {
            // Er hat den Namen gesagt: Schnitt in die Weite, der Blick geht die Kuppel hinauf.
            shot(0, BREAK, cam = listOf(w(0.0, 2.0, 16.0), w(0.0, 3.0, 14.0)),
                look = listOf(w(0.0, 10.0, 0.0), w(0.0, OCULUS * 0.5, 0.0), w(0.0, OCULUS, 0.0)), fov = 70f to 62f, cut = true)
            // Die Kuppel reißt; er sinkt herab, der Blick folgt ihm.
            shot(BREAK, SLAM, cam = listOf(w(-10.0, 1.5, 18.0), w(-6.0, 2.0, 13.0)), look = listOf(Key.Last, Key.Actor(SECOND, 4.0)),
                fov = 72f to 54f, follow = 0.25f, cut = true)
            shot(SLAM, LAND, cam = listOf(Key.Last, w(-5.0, 1.4, 10.0)), look = listOf(Key.Last, Key.Actor(SECOND, 4.5)), fov = 54f to 56f)
            // Gelandet: nah und tief, Handkamera etwas stärker.
            shot(LAND, FATHER, cam = listOf(w(-4.0, 3.0, LAND_Z + 13.0), w(-2.5, 4.5, LAND_Z + 10.5)),
                look = listOf(Key.Actor(SECOND, 5.0), Key.Actor(SECOND, 5.4)), fov = 58f to 48f, cut = true, handheld = 1.3f)
            // Hades, an den Thron geschleudert: „Vater“.
            shot(FATHER, ROAR, cam = listOf(w(-2.0, SEAT_Y + 1.2, SEAT_Z + 7.0), w(-1.4, SEAT_Y + 1.6, SEAT_Z + 6.0)),
                look = listOf(Key.Actor(HADES, 2.0)), fov = 46f to 40f, cut = true)
            shot(ROAR, TITLE, cam = listOf(w(0.0, 2.0, LAND_Z + 14.0), w(0.0, 3.0, LAND_Z + 11.0)),
                look = listOf(Key.Actor(SECOND, 5.5), Key.Actor(SECOND, 6.0)), fov = 60f to 50f, cut = true)
            toEyes(TITLE, LENGTH)

            mark(BREAK, "break"); mark(SLAM, "slam"); mark(LAND, "land"); mark(FATHER, "father"); mark(ROAR, "roar"); mark(TITLE, "title")
            shake(0, BREAK, 0.5f)
            // Die Kuppel reißt: Riss am Himmel, Trümmer stürzen, ein kurzer schwacher Linsen-Pass, Ruck.
            fx(BREAK - 1, "core.debris_pull", Vfx.Style.AEON, w(0.0, TartarusPlans.CHANDELIER_Y.toDouble(), 0.0), dir = land,
                a = 12f, b = 12f, life = 46)
            fx(BREAK, "core.tear", Vfx.Style.AEON, w(0.0, OCULUS - 1.0, 0.0), dir = w(1.0, OCULUS - 1.0, 0.0),
                a = 20f, b = 7f, c = 6f, life = DESCENT_TICKS + 10)
            screen(BREAK, BossFx.Screen.GRAVITY_LENS, w(0.0, OCULUS - 1.0, 0.0), radius = LENS_RADIUS, strength = LENS_STRENGTH, ticks = LENS_TICKS)
            kick(BREAK, pitch = SKY_KICK, ticks = 8)
            sound(BREAK, "hha:boss.tear", volume = 1f, pitch = 0.7f)
            // Er landet: Risse, echte Blöcke, Ruck, sein Thema übernimmt.
            kick(LAND, pitch = LAND_KICK, ticks = 8)
            sound(LAND, THUD, volume = 1f, pitch = 0.6f)
            fx(LAND, "core.cracks", Vfx.Style.AEON, land, a = 10f, b = 6f, life = 80)
            fx(LAND, "core.debris", Vfx.Style.AEON, land, a = 6f, b = 8f, c = 0.6f, d = 0.3f, life = 80)
            music(LAND, MUSIC_KRONOS)
        }
    }

    private fun end() = with(End) {
        stage(ID, LENGTH) {
            shot(0, SEAL, cam = listOf(w(5.0, 2.0, TITAN_Z + 11.0), w(3.0, 2.5, TITAN_Z + 9.0)), look = listOf(Key.Actor(SECOND, 3.0)),
                fov = 56f to 46f)
            // Hades hebt die Hand.
            shot(SEAL, BIND, cam = listOf(w(-10.0, 1.6, 10.0), w(-9.0, 2.0, 8.5)), look = listOf(Key.Actor(HADES, 2.6), Key.Actor(SECOND, 3.0)),
                fov = 50f to 46f, cut = true)
            // Gebunden zerfällt er in Licht und Sand: die Kamera kreist um ihn.
            shot(BIND, GIFT, cam = listOf(w(10.0, 4.0, TITAN_Z + 8.0), w(0.0, 5.5, TITAN_Z + 14.0), w(-10.0, 4.5, TITAN_Z + 7.0)),
                look = listOf(Key.Actor(SECOND, 3.0)), fov = 56f to 50f, ease = EaseKind.SMOOTH, follow = 0.2f, cut = true, handheld = 0.6f)
            // Das Geschenk.
            shot(GIFT, GRANT, cam = listOf(w(-5.0, 1.8, 11.5), w(-6.0, 2.0, 9.6)), look = listOf(Key.Actor(HADES, 2.6)), fov = 46f to 40f, cut = true)
            // Titel: langsam hoch und weit über den Saal.
            shot(GRANT, FADE, cam = listOf(Key.Last, w(0.0, 12.0, 24.0)), look = listOf(Key.Last, w(0.0, 2.0, TITAN_Z)), fov = 40f to 60f)
            toEyes(FADE, LENGTH)

            fade(0, 0x000000, 1f, 0f, 12)
            mark(SEAL, "seal"); mark(BIND, "bind"); mark(FREED, "freed"); mark(GIFT, "gift"); mark(GRANT, "grant"); mark(FADE, "fade")
            shake(BIND, FREED, 0.35f)
            sound(BIND, "hha:boss.swirl", volume = 0.8f, pitch = 0.6f)
        }
    }
}
