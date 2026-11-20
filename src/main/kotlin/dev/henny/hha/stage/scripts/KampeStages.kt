package dev.henny.hha.stage.scripts

import dev.henny.hha.logic.Vfx
import dev.henny.hha.stage.EaseKind
import dev.henny.hha.stage.Key
import dev.henny.hha.stage.StageScripts
import dev.henny.hha.stage.stage

/**
 * Bühnen-Skripte der Kampe (P15, PLAN 2.12): Auftritt `kampe.intro` und Niederlage `kampe.defeat`.
 *
 * Die Zeitpunkte, an denen sich Kampe auf dem Server bewegt (Sitz am Titanen, Herabgleiten, Landung, Brüllen), stehen
 * hier als Konstanten; `KampeEntity` liest dieselben Zahlen, damit Bild und Drehbuch zusammenpassen. Diese Datei
 * importiert nichts vom Client (sie wird auch auf dem dedizierten Server geladen).
 *
 * Anker beider Szenen = Kampes Lage beim Start, Blick = ihr `yaw`. Beim Auftritt dreht der Server sie vorher vom
 * Titanen weg: „vorn" zeigt also vom Titanen fort, die Kamera steht seitlich vor ihr und fährt nie durch seinen Leib.
 * Im Auftritt ist Darsteller 0 = Kampe; die Niederlage hat keine Darsteller.
 */
object KampeStages {

    const val INTRO = "kampe.intro"
    const val DEFEAT = "kampe.defeat"

    /** Auftritt: 8 s. */
    const val INTRO_LENGTH = 160

    /** Bis hier liegt sie zusammengerollt am Titanen (zischt bei [INTRO_HISS]). */
    const val INTRO_DESCEND = 34
    const val INTRO_HISS = 10

    /** Herabgleiten am Titanen [INTRO_DESCEND] … [INTRO_LAND] (Schraube der Pose `COIL_UP`). */
    const val INTRO_LAND = 104

    /** Ab hier zurück in die Augen. */
    const val INTRO_RETURN = 140

    /**
     * Überarbeitung 4 (P22): So weit vor ihrem Platz steht die Kamera bei Landung und Brüllen. Ihr Platz liegt bei
     * o + (0, −23, 15,5), die Wendelrampe des Kerkers beginnt bei r 20,4 (`TartarusArenas.PRISON_CORE`): Mit 6,5 bzw. 7
     * (vorher) stand die Kamera bei r 21,6 … 22,2 knapp unter dem untersten Rampenlauf (Decke 0,9 über der Linse).
     * Mit 4,5 steht sie bei r ≈ 19,7 frei über dem Grubenboden (Prüfung gegen `plan_prison.txt`, Bericht P22).
     */
    const val LAND_CAM_FORWARD = 4.5

    /** Titelkarte. */
    const val INTRO_CARD = 112

    /** Niederlage: 6 s. */
    const val DEFEAT_LENGTH = 120

    /** Ab hier ist ihr Leib fort (Todesbewegung `BossTimings.Kampe.DEATH` = 40) und ein Wirbel zieht das Gift ein. */
    const val DEFEAT_VANISH = 40

    /** Ab hier zurück in die Augen. */
    const val DEFEAT_RETURN = 96

    private const val NAME = "entity.hha.kampe"

    fun register() {
        StageScripts.register(intro())
        StageScripts.register(defeat())
    }

    /**
     * `kampe.intro` (160 Ticks):
     * | Ticks | Kamera | Spuren |
     * | --- | --- | --- |
     * | 0–34 | halbnah seitlich vor ihr, oben am Titanen | Blende aus Schwarz (deckt das Umsetzen auf den Sitz) |
     * | 34–104 | folgt ihr am Titanen herab: von über ihr bis knapp über dem Boden | – |
     * | 104–140 | tiefe Frontale beim Brüllen (4,5 vor ihr, vor der Rampe) | Ruck 1,5° bei der Landung, Titelkarte |
     * | 140–160 | zurück in die Augen | – |
     */
    private fun intro() = stage(INTRO, INTRO_LENGTH) {
        shot(
            0, INTRO_DESCEND,
            cam = listOf(Key.ActorRel(0, 9.0, 7.0, 3.0), Key.ActorRel(0, 8.0, 5.5, 2.6)),
            look = listOf(Key.Actor(0, 2.6)), fov = 58f to 54f, follow = 0.1f, handheld = 0.6f,
        )
        shot(
            INTRO_DESCEND, INTRO_LAND,
            cam = listOf(Key.Last, Key.ActorRel(0, 7.5, 5.0, 4.0), Key.ActorRel(0, LAND_CAM_FORWARD, 3.0, 1.0)),
            look = listOf(Key.Actor(0, 2.8)), fov = 54f to 60f, ease = EaseKind.SMOOTH, follow = 0.2f, handheld = 0.8f,
        )
        shot(
            INTRO_LAND, INTRO_RETURN,
            cam = listOf(Key.Last, Key.At(LAND_CAM_FORWARD, 1.5, 1.3)),
            look = listOf(Key.Last, Key.Actor(0, 3.0)), fov = 60f to 52f, ease = EaseKind.OUT, follow = 0.15f,
        )
        shot(
            INTRO_RETURN, INTRO_LENGTH,
            cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), ease = EaseKind.SMOOTH, handheld = 0f,
        )

        mark(0, "perch")
        fade(0, 0x000000, 1f, 0f, 16)
        mark(INTRO_DESCEND, "descend")
        mark(INTRO_LAND, "land")
        kick(INTRO_LAND, pitch = 1.5f, ticks = 6)
        card(INTRO_CARD, NAME, "$NAME.sub", 60)
        mark(INTRO_LENGTH, "end")
    }

    /**
     * `kampe.defeat` (120 Ticks):
     * | Ticks | Kamera | Spuren |
     * | --- | --- | --- |
     * | 0–30 | aus den Augen seitlich vor sie | Erzählerzeile |
     * | 30–70 | halber Bogen um den fallenden Leib | ab 40: Wirbel schließt sich, Funken steigen auf (ihr Leib ist fort) |
     * | 70–96 | steigt langsam auf und blickt auf die Stelle | – |
     * | 96–120 | zurück in die Augen | – |
     * Gespielt von `TartarosGuardian.onDefeated` (P14) am Ort des Todes **ohne Darsteller** (der Körper verschwindet nach
     * der Todesbewegung): alle Punkte hängen am Anker (Lage und Blick beim Tod). Sieg-Stinger und Ende des Themas kommen
     * aus dem Wächter-Kit.
     */
    private fun defeat() = stage(DEFEAT, DEFEAT_LENGTH) {
        shot(
            0, 30,
            cam = listOf(Key.Eye, Key.At(7.0, 5.0, 2.6)), look = listOf(Key.EyeLook, Key.At(0.0, 0.0, 2.4)),
            fov = 70f to 60f, ease = EaseKind.SMOOTH, follow = 0.12f,
        )
        shot(
            30, 70,
            cam = listOf(Key.Last, Key.At(1.5, 7.5, 3.0)), look = listOf(Key.At(0.0, 0.0, 1.6)),
            fov = 60f to 58f, arc = true, ease = EaseKind.SMOOTH, handheld = 0.6f,
        )
        shot(
            70, DEFEAT_RETURN,
            cam = listOf(Key.Last, Key.At(-2.0, 6.0, 6.5)), look = listOf(Key.Last, Key.At(0.0, 0.0, 0.5)),
            fov = 58f to 66f, ease = EaseKind.SMOOTH, handheld = 0.5f,
        )
        shot(
            DEFEAT_RETURN, DEFEAT_LENGTH,
            cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), ease = EaseKind.SMOOTH, handheld = 0f,
        )

        caption(12, SPEAKER_NARRATOR, "hha.kampe.stage.defeat")
        mark(DEFEAT_VANISH, "vanish")
        fx(DEFEAT_VANISH, "core.swirl", Vfx.Style.HADES, Key.At(0.0, 0.0, 0.15), dir = Key.At(0.0, 0.0, 1.15), a = 2.4f, b = 1f, life = 34)
        fx(DEFEAT_VANISH + 2, "core.sparks", Vfx.Style.HADES, Key.At(0.0, 0.0, 0.6), dir = Key.At(0.0, 0.0, 2.6), a = 8f, b = 0.25f, life = 26)
        mark(DEFEAT_LENGTH, "end")
    }

    /** = `Cine.SPEAKER_NARRATOR` (hier als Zahl, damit die Datei ohne Server-Logik auskommt). */
    private const val SPEAKER_NARRATOR = 0
}
