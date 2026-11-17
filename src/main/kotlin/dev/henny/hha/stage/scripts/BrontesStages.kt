package dev.henny.hha.stage.scripts

import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.stage.EaseKind
import dev.henny.hha.stage.Key
import dev.henny.hha.stage.StageScripts
import dev.henny.hha.stage.stage

/**
 * Bühnen-Skripte von Brontes (P09, PLAN 2.6). Anker = Lage des Kyklopen beim Start, Blick = sein `yaw`, Darsteller 0 =
 * Brontes (`DuelCinematics.staged`). Er ist 2,6 breit und 5,4 hoch, das Auge sitzt bei ~4,6, der Mund bei ~4,2. Was
 * die Szene bewirkt (Musik, Pose, erste Roste), setzt der Server im `finish` der Szene (`BrontesEntity`).
 *
 * `brontes.intro` (200 Ticks):
 * | Ticks | Kamera | Spuren |
 * | --- | --- | --- |
 * | 0–40 | Schwarz; tief vor ihm, steht | Hammerschlag hallt bei 8 (Ton, tiefer Schlag, Ruck 3°), Echo bei 30, Aufblende ab 34 |
 * | 40–124 | von unten an ihm hoch, Blick von den Knien zum Gesicht | er richtet sich aus der Glut auf (Server: `RISE`, Feuer bei 60) |
 * | 124–154 | Schnitt: nah am Auge, schiebt langsam heran | Zeile ab 106 (er steht), das Auge öffnet sich (Modell bei 128) |
 * | 154–178 | zurück in die Weite, Titelkarte | Titel `hha.brontes.title` |
 * | 178–200 | zurück in die Augen | – |
 *
 * `brontes.phase2` (100 Ticks): aus den Augen in einen Bogen, dann hinter ihm auf die Esse, die aufflammt (Musik
 * Phase II bei 40, leichter Ruck), zurück in die Augen.
 *
 * `brontes.defeat` (200 Ticks): aus den Augen tief auf den abgesetzten Hammer (Staub, dumpfer Schlag), Bogen hinauf
 * zu seinem Gesicht während der beiden Zeilen (Server), weite Totale, zurück in die Augen.
 */
object BrontesStages {
    const val INTRO = "brontes.intro"
    const val PHASE2 = "brontes.phase2"
    const val DEFEAT = "brontes.defeat"

    const val INTRO_LENGTH = 200
    const val PHASE2_LENGTH = 100
    const val DEFEAT_LENGTH = 200

    /** Höhen am Modell (Blöcke über den Füßen). */
    private const val KNEE = 1.0
    private const val CHEST = 3.2
    private const val EYE = 4.6

    fun register() {
        StageScripts.register(intro())
        StageScripts.register(phase2())
        StageScripts.register(defeat())
    }

    private fun intro() = stage(INTRO, INTRO_LENGTH) {
        // --- Kamera ---------------------------------------------------------------------------------------------
        shot(0, 40, cam = listOf(Key.ActorRel(0, 8.0, 2.2, 0.45)), look = listOf(Key.Actor(0, KNEE)), fov = 60f to 60f, handheld = 0.6f)
        shot(40, 124,
            cam = listOf(Key.ActorRel(0, 8.0, 2.2, 0.45), Key.ActorRel(0, 6.4, 1.6, 1.1), Key.ActorRel(0, 5.4, 1.0, 2.6)),
            look = listOf(Key.Actor(0, KNEE), Key.Actor(0, CHEST), Key.Actor(0, EYE - 0.2)),
            fov = 60f to 52f, ease = EaseKind.SMOOTH, follow = 0.15f)
        shot(124, 154, cam = listOf(Key.ActorRel(0, 3.6, 0.5, EYE - 0.15), Key.ActorRel(0, 3.1, 0.35, EYE - 0.1)),
            look = listOf(Key.Actor(0, EYE)), fov = 42f to 36f, ease = EaseKind.LINEAR, cut = true, handheld = 0.5f)
        shot(154, 178, cam = listOf(Key.Last, Key.ActorRel(0, 11.0, -3.6, 3.0)), look = listOf(Key.Last, Key.Actor(0, CHEST)),
            fov = 36f to 58f, ease = EaseKind.OUT)
        shot(178, 200, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), ease = EaseKind.SMOOTH, handheld = 0f)

        // --- Spuren ---------------------------------------------------------------------------------------------
        mark(0, "start")
        fade(0, 0x000000, 1f, 1f, 34)
        sound(8, "hha:boss.anvil", volume = 1f, pitch = 0.6f)
        sound(8, "hha:boss.thud", volume = 1f, pitch = 0.8f)
        kick(8, pitch = 3f, ticks = 7)
        sound(30, "hha:boss.anvil", volume = 0.45f, pitch = 0.55f)
        fade(34, 0x000000, 1f, 0f, 24)
        mark(60, "flare")
        kick(60, pitch = 1f, ticks = 6)
        caption(106, Cine.SPEAKER_BRONTES, "hha.brontes.line.rise")
        card(154, "hha.brontes.title", "hha.brontes.title.sub", 44)
        mark(INTRO_LENGTH, "end")
    }

    private fun phase2() = stage(PHASE2, PHASE2_LENGTH) {
        shot(0, 36, cam = listOf(Key.Eye, Key.At(6.0, 4.0, 2.4)), look = listOf(Key.EyeLook, Key.Actor(0, CHEST)),
            fov = 70f to 60f, ease = EaseKind.SMOOTH)
        // Hinter ihm über die Schulter auf das, was er anschaut (die Esse; er dreht sich zu ihr).
        shot(36, 78, cam = listOf(Key.ActorRel(0, -5.5, 2.2, 4.6), Key.ActorRel(0, -4.6, 1.4, 4.2)),
            look = listOf(Key.ActorRel(0, 6.0, 0.0, 1.2)), fov = 58f to 50f, ease = EaseKind.SMOOTH, follow = 0.2f)
        shot(78, 100, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), ease = EaseKind.SMOOTH, handheld = 0f)

        mark(0, "start")
        music(40, "hha:music.brontes_2")
        kick(40, pitch = 1.2f, ticks = 6)
        mark(PHASE2_LENGTH, "end")
    }

    private fun defeat() = stage(DEFEAT, DEFEAT_LENGTH) {
        // Tief auf den Hammer, den er rechts vor sich absetzt
        shot(0, 56, cam = listOf(Key.Eye, Key.ActorRel(0, 6.0, 3.4, 0.9)), look = listOf(Key.EyeLook, Key.ActorRel(0, 1.4, 1.6, 0.5)),
            fov = 70f to 56f, ease = EaseKind.SMOOTH)
        shot(56, 140, cam = listOf(Key.ActorRel(0, 6.0, 3.4, 1.2), Key.ActorRel(0, 6.6, -2.0, 3.2)),
            look = listOf(Key.Actor(0, EYE - 0.3)), fov = 56f to 46f, ease = EaseKind.SMOOTH, arc = true, follow = 0.15f)
        shot(140, 176, cam = listOf(Key.Last, Key.ActorRel(0, 13.0, 4.0, 4.5)), look = listOf(Key.Last, Key.Actor(0, CHEST)),
            fov = 46f to 62f, ease = EaseKind.OUT)
        shot(176, 200, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), ease = EaseKind.SMOOTH, handheld = 0f)

        mark(0, "start")
        mark(16, "hammer_down")
        fx(16, "core.debris", Vfx.Style.FIRE, Key.ActorRel(0, 1.4, 1.6, 0.0), a = 1.4f, b = 3f, c = 0.25f, d = 0.08f)
        sound(16, "hha:boss.thud", volume = 0.7f, pitch = 0.75f)
        kick(16, pitch = 1.2f, ticks = 6)
        mark(DEFEAT_LENGTH, "end")
    }
}
