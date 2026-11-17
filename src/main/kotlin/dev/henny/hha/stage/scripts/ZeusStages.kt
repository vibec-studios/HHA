package dev.henny.hha.stage.scripts

import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.stage.EaseKind
import dev.henny.hha.stage.Key
import dev.henny.hha.stage.StageScripts
import dev.henny.hha.stage.stage

/**
 * Bühnen-Skripte des Zeus (P11, PLAN 2.8): Auftritt, Phasenwechsel II und III, Niederlage. Die Skripte führen nur die
 * Kamera und das Bild (Blitz, Ruck, Stoßwelle, Nike); Zeilen, Aktionen, Musik, echte Blitze und alle Spielzustände
 * stehen im Drehbuch des Servers ([dev.henny.hha.logic.boss.ZeusScenes]), das die Zeiten von hier liest. So bleibt der
 * feste Endzustand beim Überspringen beim Server (das Drehbuch läuft stumm durch).
 *
 * Anker und Darsteller:
 * - `zeus.intro`: Anker = Füße vor dem Thron, Blick zur Hof-Mitte; Darsteller 0 = Zeus.
 * - `zeus.phase2`/`zeus.phase3`: Anker = Zeus, Blick = Zeus; Darsteller 0 = Zeus.
 * - `zeus.defeat`: Anker = Zeus, Blick = Zeus; Darsteller 0 = Zeus, 1 = Sieger (Nikes Kranz).
 *
 * Jede Szene beginnt in den Augen (`Key.Last`) oder hinter einer Blende und endet in den Augen.
 */
object ZeusStages {

    const val INTRO = "zeus.intro"
    const val PHASE2 = "zeus.phase2"
    const val PHASE3 = "zeus.phase3"
    const val DEFEAT = "zeus.defeat"

    // --- Auftritt (Ticks ab Szenenbeginn; Server und Bühne teilen sie) ------------------------------------------

    /** Aufblende aus Schwarz. */
    const val INTRO_FADE = 14
    const val INTRO_LINE_1 = 14
    /** Der Blitz, der das Bild teilt (Einschlag zwischen Kamera und Thron). */
    const val INTRO_SPLIT = 70
    /** Er steht auf (`RISE`, 40 Ticks); bis dahin sitzt er. */
    const val INTRO_RISE = 72
    const val INTRO_LINE_2 = 100
    /** Donnerschritt vom Thron in den Hof: Verschwinden + 14, Versetzen + 16, Erscheinen + 20 (`ZeusTimings.STEP_*`). */
    const val INTRO_STEP = 130
    /** Schnitt auf den Landepunkt, nachdem er versetzt ist (Darsteller-Schlüssel springen sonst mit). */
    const val INTRO_LAND_CUT = 148
    const val INTRO_APPEAR = 150
    const val INTRO_TITLE = 156
    const val INTRO_BACK = 196
    const val INTRO_LENGTH = 236

    // --- Phasenwechsel ------------------------------------------------------------------------------------------

    /** Blitz in ihn, Musik der neuen Phase, Gewitter. */
    const val PHASE_BOLT = 6
    const val PHASE_LINE = 10
    /** Zwei verbrauchte Pfeiler werden frisch (Bögen von ihm zu ihren Spitzen). */
    const val PHASE_RENEW = 30
    const val PHASE_TITLE = 48
    const val PHASE_BACK = 72
    const val PHASE_LENGTH = 100

    // --- Niederlage ---------------------------------------------------------------------------------------------

    const val DEFEAT_LINE_1 = 6
    /** Schnitt auf den Sieger; Nike erscheint als Lichtgestalt über ihm. */
    const val DEFEAT_VICTOR = 60
    const val DEFEAT_NIKE = 62
    const val DEFEAT_LINE_2 = 70
    /** Nike setzt den Kranz auf (das Requisit erscheint am Server genau dann). */
    const val DEFEAT_WREATH = 96
    /** Schnitt zurück: Er zeigt nach unten (`ZeusAction.POINT`). */
    const val DEFEAT_POINT_CUT = 135
    const val DEFEAT_POINT = 140
    const val DEFEAT_LINE_3 = 150
    const val DEFEAT_BACK = 205
    const val DEFEAT_LENGTH = 240

    /** Lebensdauer der Lichtgestalt Nike (`zeus.nike`): Herabschweben, Kranz, Auflösen. */
    const val NIKE_LIFE = 110

    fun register() {
        StageScripts.register(intro())
        StageScripts.register(phase(PHASE2, third = false))
        StageScripts.register(phase(PHASE3, third = true))
        StageScripts.register(defeat())
    }

    /**
     * `zeus.intro` (11,8 s): Thron von unten, er sitzt; ein Blitz teilt das Bild; Schnitt, er steht auf; Donnerschritt in
     * den Hof; Blick vom Siegel auf den Landepunkt, Titel; zurück in die Augen.
     */
    private fun intro() = stage(INTRO, INTRO_LENGTH) {
        // Von unten: tief vor den Stufen, langsam heran; er thront über dem Bild.
        shot(0, INTRO_SPLIT, cam = listOf(Key.At(7.0, 1.6, 1.0), Key.At(5.4, 1.2, 0.9)), look = listOf(Key.Actor(0, 3.0)),
            fov = 58f to 50f, ease = EaseKind.SMOOTH, handheld = 0.6f)
        // Nach dem Blitz: Schnitt zur Seite, Bogen nach vorn, während er sich erhebt.
        shot(INTRO_SPLIT, INTRO_LAND_CUT, cam = listOf(Key.ActorRel(0, 6.5, -3.6, 1.2), Key.ActorRel(0, 6.2, 3.2, 2.0)),
            look = listOf(Key.Actor(0, 3.3)), fov = 54f to 60f, arc = true, follow = 0.18f, cut = true)
        // Vom Siegel her auf den Landepunkt (er ist schon versetzt, sichtbar ab dem Erscheinen).
        shot(INTRO_LAND_CUT, INTRO_BACK, cam = listOf(Key.ActorRel(0, 9.5, 2.6, 0.7), Key.ActorRel(0, 7.8, 1.6, 1.3)),
            look = listOf(Key.Actor(0, 2.8)), fov = 62f to 56f, ease = EaseKind.OUT, cut = true)
        shot(INTRO_BACK, INTRO_LENGTH, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), handheld = 0f)

        fade(0, 0x000000, 1f, 0f, INTRO_FADE)
        // Der Blitz trifft genau `delay + 6` nach dem Aufruf (P03): Aufruf 6 Ticks vorher, ohne Verzögerung.
        fx(INTRO_SPLIT - SKY_LEAD, "core.sky_strike", Vfx.Style.THUNDER, Key.At(2.8, 0.0, 0.0), a = 1.2f, b = 30f, c = 0f,
            life = SKY_LEAD + 14)
        flash(INTRO_SPLIT, 0xE6F0FF, 0.22f, 8)
        kick(INTRO_SPLIT, pitch = 2.2f, roll = 0.6f, ticks = 7)
        screen(INTRO_SPLIT, BossFx.Screen.SHOCK_RIPPLE, Key.At(2.8, 0.0, 0.0), radius = 8f, strength = 0.4f, ticks = 6)
        // Landung im Hof: Stoß durchs Bild, kurzer Ruck.
        screen(INTRO_APPEAR, BossFx.Screen.SHOCK_RIPPLE, Key.Actor(0, 0.1), radius = 10f, strength = 0.45f, ticks = 6)
        kick(INTRO_APPEAR, pitch = 1.8f, ticks = 6)
        mark(INTRO_SPLIT, "split")
        mark(INTRO_APPEAR, "appear")
    }

    /**
     * `zeus.phase2`/`zeus.phase3` (5 s): aus den Augen auf ihn, ein Blitz fährt in ihn (Stoß, Ruck, Blitz ≤ 0,22), Bogen
     * um ihn, während zwei Pfeiler frisch werden; zurück in die Augen. Phase III von höher und enger.
     */
    private fun phase(id: String, third: Boolean) = stage(id, PHASE_LENGTH) {
        val up = if (third) 1.0 else 0.0
        shot(0, 24, cam = listOf(Key.Last, Key.ActorRel(0, 9.0, -4.0, 1.8 + up)), look = listOf(Key.Last, Key.Actor(0, 3.2)),
            fov = 70f to 60f, ease = EaseKind.OUT)
        shot(24, PHASE_BACK,
            cam = listOf(Key.ActorRel(0, 8.5, -4.0, 1.4 + up), Key.ActorRel(0, if (third) 6.5 else 7.5, 3.5, 2.4 + up)),
            look = listOf(Key.Actor(0, 3.3)), fov = 60f to (if (third) 50f else 54f), arc = true, follow = 0.15f)
        shot(PHASE_BACK, PHASE_LENGTH, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), handheld = 0f)

        flash(PHASE_BOLT, 0xE6F0FF, 0.22f, 8)
        kick(PHASE_BOLT, pitch = 2.5f, roll = if (third) -0.8f else 0.8f, ticks = 7)
        screen(PHASE_BOLT, BossFx.Screen.SHOCK_RIPPLE, Key.Actor(0, 0.2), radius = 10f, strength = 0.5f, ticks = 8)
        mark(PHASE_BOLT, "bolt")
    }

    /**
     * `zeus.defeat` (12 s): Er senkt den Blitz; Schnitt auf den Sieger, Nike schwebt als Lichtgestalt herab und setzt
     * den Kranz (goldener Ring und Schein: Gold heißt hier Ziel/Lohn); Schnitt zurück, er zeigt nach unten; in die Augen.
     */
    private fun defeat() = stage(DEFEAT, DEFEAT_LENGTH) {
        shot(0, DEFEAT_VICTOR, cam = listOf(Key.Last, Key.ActorRel(0, 6.5, -2.5, 1.4)), look = listOf(Key.Last, Key.Actor(0, 3.4)),
            fov = 70f to 58f, ease = EaseKind.SMOOTH)
        shot(DEFEAT_VICTOR, DEFEAT_POINT_CUT, cam = listOf(Key.ActorRel(1, 3.8, 1.6, 1.3), Key.ActorRel(1, 3.2, -1.2, 1.9)),
            look = listOf(Key.Actor(1, 2.3)), fov = 52f to 46f, arc = true, follow = 0.2f, cut = true)
        shot(DEFEAT_POINT_CUT, DEFEAT_BACK, cam = listOf(Key.ActorRel(0, 7.0, 2.8, 1.0), Key.ActorRel(0, 6.2, 2.0, 1.6)),
            look = listOf(Key.Actor(0, 2.8)), fov = 58f to 54f, cut = true)
        shot(DEFEAT_BACK, DEFEAT_LENGTH, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), handheld = 0f)

        fx(DEFEAT_NIKE, "zeus.nike", Vfx.Style.GOLD, Key.Actor(1, 0.0), a = (DEFEAT_WREATH - DEFEAT_NIKE).toFloat(), life = NIKE_LIFE)
        mark(DEFEAT_WREATH, "wreath")
    }

    /** Ein Himmelsblitz (`core.sky_strike`) erreicht den Boden so viele Ticks nach seinem `delay` (P03). */
    const val SKY_LEAD = 6
}
