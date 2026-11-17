package dev.henny.hha.stage.scripts

import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.stage.EaseKind
import dev.henny.hha.stage.Key
import dev.henny.hha.stage.StageScripts
import dev.henny.hha.stage.stage

/**
 * Bühnen-Skripte von Kratos und Bia (P10, PLAN 2.7). Darsteller 0 = Kratos, 1 = Bia (`DuelCinematics.staged(...,
 * actors = [kratos, bia])`). Alle Kamerapunkte hängen an den Darstellern (`ActorRel`), nicht am Anker: So stimmen die
 * Einstellungen an der Station auf Insel E genauso wie bei einem Testkampf irgendwo sonst.
 *
 * Der Server ([dev.henny.hha.entity.boss.olymp.KratosEntity]) liest die Zeitmarken unten (`INTRO_*`, `PHASE2_*`,
 * `DEFEAT_*`) und lässt seine Aktionen genau dann laufen; was nur zu sehen ist (Ruck, Ton, Bodenrisse beim
 * Schildschlag, Riss über der Statue), steht hier in der Zeitleiste.
 *
 * `kratos.intro` (240 Ticks = 12 s):
 * | Ticks | Kamera | Spuren / Server |
 * | --- | --- | --- |
 * | 0–84 | Kurve von unten vor ihm die Treppe hinauf, er schreitet herab | Blende aus Schwarz |
 * | 84–124 | Halbnah von vorn unten | 96 Aktion `SLAM` (Server), 110 Schild schlägt auf: Ruck, Ton, Risse, Trümmer |
 * | 124–172 | nah am Helm, langsame Fahrt | 124 Zeile „entrance“ (Server) |
 * | 172–214 | **Schnitt** auf Bias Sockel | 180 `bia.awake` (Riss über der Statue, Brocken), 196 Titelkarte (Server) |
 * | 214–240 | zurück in die Augen | – |
 *
 * `kratos.phase2` (120 Ticks = 6 s):
 * | Ticks | Kamera | Spuren / Server |
 * | --- | --- | --- |
 * | 0–16 | aus den Augen schräg vor ihn | 0 `DROP_SHIELD`, Zeile „phase2“ (Server) |
 * | 16–36 | **Schnitt** links neben ihn: der Schild fliegt auf die Kamera zu | 16 Schild weg, Scherben, Musik (Server); Ruck |
 * | 36–100 | **Schnitt** auf Bia: sie springt herab und wieder hinauf | 52 / 94 Landungen `bia.land` (Server); Ruck, Ton |
 * | 100–120 | zurück in die Augen | – |
 *
 * `kratos.defeat` (200 Ticks = 10 s):
 * | Ticks | Kamera | Spuren / Server |
 * | --- | --- | --- |
 * | 0–24 | aus den Augen vor ihn | 0 `YIELD` (Faust an die Brust, dann aufs Knie), Zeile „yield“, Sieg-Stinger (Server) |
 * | 24–104 | Bogen um den Knienden | – |
 * | 104–158 | **Schnitt** auf Bia | 112 `bia.awake` als Erstarren, Erzähler-Zeile |
 * | 158–200 | zurück in die Augen | – |
 */
object KratosStages {
    const val INTRO = "kratos.intro"
    const val PHASE2 = "kratos.phase2"
    const val DEFEAT = "kratos.defeat"

    const val INTRO_LENGTH = 240
    const val PHASE2_LENGTH = 120
    const val DEFEAT_LENGTH = 200

    // --- Auftritt: Zeitmarken, die auch der Server liest ---------------------------------------------------------
    /** So lange darf er die Treppe herab schreiten (danach steht er, auch wenn er nicht angekommen ist). */
    const val INTRO_WALK = 70
    /** Der Schild schlägt auf (Treffer-Tick der Aktion `SLAM`, siehe `KratosModel`). */
    const val INTRO_SLAM_HIT = 110
    /** Ticks der Aktion `SLAM` bis zum Aufschlag und bis zum Ende. */
    const val SLAM_HIT = 14
    const val SLAM_END = 34
    const val INTRO_SLAM_ACT = INTRO_SLAM_HIT - SLAM_HIT
    const val INTRO_LINE = 124
    const val INTRO_AWAKE = 180
    const val INTRO_TITLE = 196

    // --- Phasenwechsel ------------------------------------------------------------------------------------------
    /** Bia beginnt ihren Sprung vom Sockel (nach dem Schildwurf). */
    const val PHASE2_BIA_START = 32
    /** Hocke vor dem Sprung, Flug herab, Stehen unten, Hocke vor dem Rücksprung (Ticks). */
    const val PHASE2_BIA_CROUCH = 8
    const val PHASE2_BIA_DROP_FLIGHT = 12
    const val PHASE2_BIA_STAND = 22
    const val PHASE2_BIA_BACK_CROUCH = 6
    /** So weit vor der Sockelmitte (zum Platz hin) landet sie, und so hoch ist der Bogen herab. */
    const val PHASE2_BIA_DROP = 3.4
    const val PHASE2_BIA_DROP_LIFT = 2.0
    /** Erste Landung (unten) im Skript: Start + Hocke + Flug. */
    const val PHASE2_LAND_DOWN = PHASE2_BIA_START + PHASE2_BIA_CROUCH + PHASE2_BIA_DROP_FLIGHT

    // --- Niederlage ---------------------------------------------------------------------------------------------
    const val DEFEAT_PETRIFY = 112

    private const val KEY = "hha.kratos"

    /** Stil der rein bildlichen Spuren (Risse, Staub): Kratos' Farbe. */
    private val STYLE = Vfx.Style.CRIMSON

    fun register() {
        StageScripts.register(intro())
        StageScripts.register(phase2())
        StageScripts.register(defeat())
    }

    private fun intro() = stage(INTRO, INTRO_LENGTH) {
        // --- Kamera ----------------------------------------------------------------------------------------------
        // Eine Kurve die Treppe hinauf (Anker = sein Platz am Treppenfuß, Blick zum Platz; die Treppe liegt bei f < −6):
        // Sie beginnt tief links am Treppenfuß und schaut zu ihm hinauf, steigt neben ihm die Stufen hoch und dreht vor
        // ihn, während er herabschreitet. Ohne Treppe (Testkampf) umkreist sie ihn einfach von links hinten nach vorn.
        shot(0, 84,
            cam = listOf(Key.At(-5.0, -6.0, 1.5), Key.At(-2.0, -4.2, 4.0), Key.At(3.6, -1.6, 4.0)),
            look = listOf(Key.Actor(0, 3.4)), fov = 58f to 54f, ease = EaseKind.SMOOTH, follow = 0.18f)
        shot(84, 124, cam = listOf(Key.ActorRel(0, 5.6, -1.4, 1.0), Key.ActorRel(0, 5.0, -1.0, 1.2)),
            look = listOf(Key.ActorRel(0, 0.8, -0.3, 2.2)), fov = 60f to 60f, ease = EaseKind.LINEAR, cut = true)
        shot(124, 172, cam = listOf(Key.ActorRel(0, 3.9, 1.3, 3.6), Key.ActorRel(0, 3.4, 0.9, 3.7)),
            look = listOf(Key.Actor(0, 3.7)), fov = 50f to 46f, ease = EaseKind.LINEAR, cut = true, handheld = 0.6f)
        shot(172, 214, cam = listOf(Key.ActorRel(1, 4.6, -1.6, 1.0), Key.ActorRel(1, 4.0, -1.0, 1.6)),
            look = listOf(Key.Actor(1, 2.6)), fov = 52f to 48f, ease = EaseKind.LINEAR, cut = true, handheld = 0.5f)
        shot(214, INTRO_LENGTH, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), ease = EaseKind.SMOOTH, handheld = 0f)

        // --- Spuren ----------------------------------------------------------------------------------------------
        fade(0, 0x000000, 1f, 0f, 18)
        // Der Schild schlägt auf (links vor ihm): Ruck, Metall und tiefer Schlag, Marmor reißt auf.
        kick(INTRO_SLAM_HIT, pitch = 2.6f, roll = 0.6f, ticks = 7)
        sound(INTRO_SLAM_HIT, "hha:boss.shield", volume = 1f, pitch = 0.7f)
        sound(INTRO_SLAM_HIT, "hha:boss.thud", volume = 1f)
        fx(INTRO_SLAM_HIT, "core.cracks", STYLE, Key.ActorRel(0, 1.5, -0.4, 0.05), a = 2.6f, b = 4f, life = 70)
        fx(INTRO_SLAM_HIT + 1, "core.debris", STYLE, Key.ActorRel(0, 1.5, -0.4, 0.0), a = 2.0f, b = 3f, c = 0.42f, d = 0.12f)
        // Bia: ein Riss läuft über die Statue, Brocken platzen ab.
        fx(INTRO_AWAKE, "bia.awake", STYLE, Key.Actor(1, 0.0), life = 60)
        sound(INTRO_AWAKE, "hha:boss.crack", volume = 0.9f, pitch = 0.8f)
        mark(INTRO_SLAM_HIT, "slam")
        mark(INTRO_AWAKE, "awake")
    }

    private fun phase2() = stage(PHASE2, PHASE2_LENGTH) {
        shot(0, 16, cam = listOf(Key.Last, Key.ActorRel(0, 5.2, -2.6, 2.4)), look = listOf(Key.Last, Key.Actor(0, 2.8)),
            fov = 70f to 60f, ease = EaseKind.OUT)
        // Der Schild fliegt nach links: die Kamera steht in seiner Flugbahn.
        shot(16, 36, cam = listOf(Key.ActorRel(0, 2.6, -5.4, 1.3), Key.ActorRel(0, 2.9, -5.8, 1.5)),
            look = listOf(Key.ActorRel(0, 0.6, -2.4, 0.6)), fov = 60f to 64f, ease = EaseKind.OUT, cut = true, handheld = 1.4f)
        // Bia springt herab und wieder hinauf; sie schaut dabei immer zum Platz (die Kamera bleibt auf ihrer Seite).
        shot(36, 100, cam = listOf(Key.ActorRel(1, 7.2, -2.6, 1.4), Key.ActorRel(1, 6.4, 1.6, 2.2)),
            look = listOf(Key.Actor(1, 2.0)), fov = 58f to 54f, ease = EaseKind.SMOOTH, cut = true, follow = 0.25f)
        shot(100, PHASE2_LENGTH, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), ease = EaseKind.SMOOTH, handheld = 0f)

        kick(16, pitch = 1.0f, yaw = -2.2f, ticks = 7)
        kick(PHASE2_LAND_DOWN, pitch = 2.0f, ticks = 6)
        sound(PHASE2_LAND_DOWN, "hha:boss.thud", volume = 0.9f)
        mark(16, "shield")
        mark(PHASE2_LAND_DOWN, "land")
    }

    private fun defeat() = stage(DEFEAT, DEFEAT_LENGTH) {
        shot(0, 24, cam = listOf(Key.Last, Key.ActorRel(0, 6.5, 2.8, 2.6)), look = listOf(Key.Last, Key.Actor(0, 2.6)),
            fov = 70f to 60f, ease = EaseKind.OUT)
        shot(24, 104, cam = listOf(Key.ActorRel(0, 6.5, 2.8, 2.6), Key.ActorRel(0, 5.6, -3.0, 1.5)), look = listOf(Key.Actor(0, 1.9)),
            fov = 60f to 54f, arc = true, ease = EaseKind.SMOOTH, handheld = 0.6f)
        shot(104, 158, cam = listOf(Key.ActorRel(1, 4.8, 1.6, 1.0), Key.ActorRel(1, 4.2, 0.7, 1.5)), look = listOf(Key.Actor(1, 2.6)),
            fov = 52f to 48f, ease = EaseKind.LINEAR, cut = true, handheld = 0.5f)
        shot(158, DEFEAT_LENGTH, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), ease = EaseKind.SMOOTH, handheld = 0f)

        fx(DEFEAT_PETRIFY, "bia.awake", STYLE, Key.Actor(1, 0.0), b = 1f, life = 70)
        sound(DEFEAT_PETRIFY, "hha:boss.crack", volume = 0.6f, pitch = 0.6f)
        caption(DEFEAT_PETRIFY + 6, Cine.SPEAKER_NARRATOR, "$KEY.line.defeat_bia")
        mark(DEFEAT_PETRIFY, "petrify")
    }
}
