package dev.henny.hha.stage.scripts

import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.stage.EaseKind
import dev.henny.hha.stage.Key
import dev.henny.hha.stage.StageScripts
import dev.henny.hha.stage.stage
import dev.henny.hha.world.olymp.OlympusLayout

/**
 * Bühnen-Skripte der Hekate (P08, PLAN 2.5). Server und Client teilen diese Datei (keine Client-Importe); der Server
 * liest Längen und Marken, die Kampf-Datei (`HekateEntity`) rahmt damit Auftritt, Phasenwechsel und Niederlage über
 * `DuelCinematics.staged`.
 *
 * Anker = Lage der Hekate beim Start der Szene, Blick = ihr `yaw` (vorn f, rechts r, hoch u; API.md). Beim Auftritt
 * stellt der Server sie vorher an die Südseite des Hekataion mit Blick den Südweg hinab (vorn = Süden).
 *
 * | ID | Länge | Ablauf |
 * | --- | --- | --- |
 * | `hekate.intro` | 240 (12 s) | Blende aus Schwarz; Kamera schleicht als Kurve den Südweg hinauf, die Fackelsäulen entzünden sich paarweise (Marken `torch1`–`torch6`, Server spielt `hekate.kindle`); Stimme aus dem Hekataion; ein Wirbel öffnet sich an der Statue (Marke `emerge`), sie gleitet heraus (ab `glide`); Titelkarte; zurück in die Augen |
 * | `hekate.phase2`/`phase3` | 100 (5 s) | Kamera aus den Augen vor sie, halber Bogen um sie (Phase III andersherum), der Kopfkranz dreht (Modell), Titel und Zeile; Phase II setzt `music.hekate_2`; zurück in die Augen |
 * | `hekate.defeat` | 200 (10 s) | sie tritt zur Seite, Schlüssel dreht und rastet ein (`hekate.key_ring`, Nicken 1,5°); Kamera fährt über den Platz zum Höllentor (Weltlage aus [OlympusLayout.HELL_GATE]), das sich ab Marke `gate` Reihe für Reihe füllt; zurück in die Augen |
 *
 * Die Niederlage nutzt feste Weltpunkte am Höllentor und läuft deshalb nur im Olymp; außerhalb (Test per
 * `/hha boss start` an anderem Ort) nimmt der Server die alte Kreisfahrt.
 */
object HekateStages {

    const val INTRO = "hekate.intro"
    const val PHASE2 = "hekate.phase2"
    const val PHASE3 = "hekate.phase3"
    const val DEFEAT = "hekate.defeat"

    const val INTRO_LENGTH = 240
    const val PHASE_LENGTH = 100
    const val DEFEAT_LENGTH = 200

    /** Marken, die der Server liest. */
    const val MARK_EMERGE = "emerge"
    const val MARK_GLIDE = "glide"
    const val MARK_GATE = "gate"

    /** Auftritt: Zündzeitpunkte der sechs Fackelsäulen (Marken `torch1` … `torch6`). */
    val TORCH_TICKS = intArrayOf(26, 36, 70, 80, 104, 114)

    fun torchMark(i: Int): String = "torch${i + 1}"

    /**
     * Feuerschalen der sechs Fackelsäulen am Dreiweg relativ zur Platzmitte (`OlympusLayout.C.centerVec`): x, Höhe über
     * dem Boden, z (wie `OlympusPlans.torchPillar`: Schaft 6 hoch, Seelen-Lagerfeuer darauf). Reihenfolge = Zündfolge:
     * Südweg, Nordostweg, Nordwestweg (zum Höllentor).
     */
    val TORCHES: List<DoubleArray> = listOf(
        doubleArrayOf(-6.0, 6.45, 29.0), doubleArrayOf(6.0, 6.45, 29.0),
        doubleArrayOf(29.0, 6.45, -25.0), doubleArrayOf(29.0, 6.45, -15.0),
        doubleArrayOf(-27.0, 6.45, -22.0), doubleArrayOf(-27.0, 6.45, -8.0),
    )

    /** Auftritt: der Wirbel öffnet sich, sie gleitet von [MARK_GLIDE] an so lange vor. */
    const val EMERGE_TICK = 150
    const val GLIDE_TICK = 156
    const val GLIDE_TICKS = 30

    /** Niederlage: Schlüssel setzt an, rastet nach [KEY_LOCK] Ticks ein; Tor öffnet bei [GATE_TICK]. */
    const val KEY_TICK = 40
    const val KEY_LOCK = 14
    const val GATE_TICK = 108

    private const val LINE = "hha.hekate.line"

    fun register() {
        StageScripts.register(intro())
        StageScripts.register(phase(PHASE2, 2, mirror = false))
        StageScripts.register(phase(PHASE3, 3, mirror = true))
        StageScripts.register(defeat())
    }

    // -----------------------------------------------------------------------------------------------------------------

    private fun intro() = stage(INTRO, INTRO_LENGTH) {
        // Kamera: Weite am Südweg nach kurzer Blende, schleicht als Kurve zwischen den Säulen hinauf zum Platz.
        shot(
            0, EMERGE_TICK,
            cam = listOf(Key.At(36.0, 2.5, 4.2), Key.At(27.0, -1.6, 3.4), Key.At(17.0, 1.8, 2.8), Key.At(10.0, -0.8, 2.5)),
            look = listOf(Key.At(0.0, 0.0, 3.6), Key.At(0.0, 0.0, 2.6)),
            fov = 60f to 54f, ease = EaseKind.SMOOTH, follow = 0.25f, handheld = 0.8f, cut = true,
        )
        // Sie tritt aus dem Hekataion: Halbnahe, Blick folgt ihr.
        shot(
            EMERGE_TICK, 200,
            cam = listOf(Key.Last, Key.At(8.2, 1.7, 2.1)),
            look = listOf(Key.Last, Key.Actor(0, 2.3)),
            fov = 54f to 50f, ease = EaseKind.SMOOTH, follow = 0.15f,
        )
        shot(200, INTRO_LENGTH, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), ease = EaseKind.SMOOTH, handheld = 0f)

        fade(0, 0x000000, 1f, 0f, 18)
        music(0, "hha:music.hekate")
        for ((i, at) in TORCH_TICKS.withIndex()) {
            mark(at, torchMark(i))
            sound(at, "hha:boss.torch", 0.45f, 0.8f + 0.06f * i)
        }
        caption(96, Cine.SPEAKER_HEKATE, "$LINE.entrance")
        mark(EMERGE_TICK, MARK_EMERGE)
        fx(EMERGE_TICK, "core.swirl", Vfx.Style.MOON, Key.At(0.2, 0.0, 2.0), dir = Key.At(1.2, 0.0, 2.0), a = 1.6f, b = 0f, life = 40)
        sound(EMERGE_TICK, "hha:boss.key", 0.8f, 0.6f)
        mark(GLIDE_TICK, MARK_GLIDE)
        card(166, "hha.hekate.title", "hha.hekate.title.sub", 70)
        mark(INTRO_LENGTH, "end")
    }

    /** Phasenwechsel: Kopfkranz dreht (Modell, über `phase`), halber Bogen um sie; [mirror] = andere Richtung. */
    private fun phase(id: String, number: Int, mirror: Boolean) = stage(id, PHASE_LENGTH) {
        val m = if (mirror) -1.0 else 1.0
        val start = Key.At(6.5, -3.5 * m, 2.6)
        shot(0, 16, cam = listOf(Key.Last, start), look = listOf(Key.Last, Key.Actor(0, 2.6)), fov = 70f to 60f)
        shot(
            16, 80, cam = listOf(start, Key.At(-2.5, 6.8 * m, 3.2)), look = listOf(Key.Actor(0, 2.6)),
            fov = 60f to 56f, arc = true, ease = EaseKind.SMOOTH, follow = 0.12f,
        )
        shot(80, PHASE_LENGTH, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), ease = EaseKind.SMOOTH, handheld = 0f)

        if (number == 2) music(0, "hha:music.hekate_2")
        sound(4, "hha:boss.key", 0.8f, if (number == 2) 1f else 0.7f)
        card(6, "hha.hekate.phase.$number", "hha.hekate.phase.$number.sub", 50)
        caption(14, Cine.SPEAKER_HEKATE, "$LINE.phase$number")
        mark(PHASE_LENGTH, "end")
    }

    /** Niederlage: zur Seite, Schlüssel rastet ein, Fahrt zum Höllentor, das sich füllt. */
    private fun defeat() = stage(DEFEAT, DEFEAT_LENGTH) {
        val gate = OlympusLayout.HELL_GATE
        // Mitte der runden Öffnung (HadesGate: Höhe 1 … 10 über dem Fußpunkt, Mitte 5,5) als Blockmitte.
        val gx = gate.x + 0.5
        val gy = gate.y + 6.0
        val gz = gate.z + 0.5
        val gateLook = Key.World(gx, gy, gz)
        // Die Torebene spannt z; der Platz liegt in +x. Kamera schräg davor, leicht erhöht.
        val far = Key.World(gx + 13.0, gy + 1.0, gz + 6.0)
        val near = Key.World(gx + 10.5, gy + 0.6, gz + 4.7)
        val over = Key.World(gx + 24.0, gy + 10.0, gz + 14.0)

        shot(0, 20, cam = listOf(Key.Last, Key.At(5.5, 2.4, 2.3)), look = listOf(Key.Last, Key.Actor(0, 2.3)), fov = 70f to 60f)
        shot(20, 56, cam = listOf(Key.At(5.5, 2.4, 2.3), Key.At(4.4, 3.4, 2.0)), look = listOf(Key.Actor(0, 2.2)), fov = 60f to 52f, follow = 0.15f)
        shot(56, 120, cam = listOf(Key.Last, over, far), look = listOf(Key.Last, gateLook), fov = 52f to 58f, ease = EaseKind.SMOOTH, follow = 0.2f)
        shot(120, 170, cam = listOf(far, near), look = listOf(gateLook), fov = 58f to 54f, ease = EaseKind.SMOOTH, handheld = 0.6f)
        shot(170, DEFEAT_LENGTH, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), ease = EaseKind.SMOOTH, handheld = 0f)

        caption(4, Cine.SPEAKER_HEKATE, "$LINE.victory")
        fx(
            KEY_TICK, "hekate.key_ring", Vfx.Style.MOON, Key.ActorRel(0, 0.9, 0.0, 2.0), dir = Key.ActorRel(0, 2.0, 0.0, 2.0),
            a = 0.9f, b = KEY_LOCK.toFloat(), life = KEY_LOCK + 16,
        )
        sound(KEY_TICK + KEY_LOCK, "hha:boss.key", 1f, 0.7f)
        kick(KEY_TICK + KEY_LOCK, pitch = 1.5f, ticks = 6)
        caption(60, Cine.SPEAKER_HEKATE, "$LINE.victory2")
        mark(GATE_TICK, MARK_GATE)
        mark(DEFEAT_LENGTH, "end")
    }
}
