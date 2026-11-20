package dev.henny.hha.stage.scripts

import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.stage.EaseKind
import dev.henny.hha.stage.Key
import dev.henny.hha.stage.StageScripts
import dev.henny.hha.stage.stage
import dev.henny.hha.world.olymp.OlympusArenas
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
 * | `hekate.intro` | 240 (12 s) | Blende aus Schwarz; Kamera schleicht als Kurve den Südweg hinauf (durch die Öffnung des Südtors, unter dem Sturz), die Feuer entzünden sich (Marken `torch1`–`torch4`: die Fackeltürme 30° und 150° beiderseits des Südwegs, der Turm 270°, zuletzt das Seelenfeuer auf dem Hekataion; Server spielt `hekate.kindle`); Stimme aus dem Hekataion; ein Wirbel öffnet sich an der Statue (Marke `emerge`), sie gleitet heraus (ab `glide`); Titelkarte; zurück in die Augen |
 * | `hekate.phase2`/`phase3` | 100 (5 s) | Kamera aus den Augen vor sie, halber Bogen um sie (Phase III andersherum), der Kopfkranz dreht (Modell), Titel und Zeile; Phase II setzt `music.hekate_2`; zurück in die Augen. Der Server dreht sie vorher so, dass kein Punkt aus [phaseCameraPoints] in einem Block liegt (Tore, Türme, Terrassen stehen auf dem Platz) |
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

    /**
     * Auftritt: Zündzeitpunkte der vier Feuer (Marken `torch1` … `torch4`). Überarbeitung 4 (P15): Die sechs alten
     * Fackelsäulen am Rand gibt es seit P07 nicht mehr (zwei stehen noch an der Höllentreppe, außer Sicht); es zünden
     * die drei Fackeltürme und das Hekataion.
     */
    val TORCH_TICKS = intArrayOf(26, 36, 80, 140)

    fun torchMark(i: Int): String = "torch${i + 1}"

    /**
     * Feuer am Dreiweg relativ zur Platzmitte (`OlympusLayout.C.centerVec`): x, Höhe über dem Boden, z. Aus dem Vertrag
     * (`OlympusArenas.cTower`, Lagerfeuer in der obersten Lage des Turms; Seelenfeuer auf der Spitze des Hekataion bei
     * `HEKATAION_HEIGHT` + 1, Bericht P07). Reihenfolge = Zündfolge: Turm 30° (Südost) und 150° (Südwest) beiderseits des
     * Südwegs, Turm 270° (Nord, hinter dem Hekataion), Hekataion kurz vor dem Hervortreten.
     */
    val TORCHES: List<DoubleArray> by lazy {
        val c = OlympusLayout.C.centerVec
        val towers = OlympusArenas.C_TOWER_ANGLES.indices.map { i ->
            val t = OlympusArenas.cTower(i)
            doubleArrayOf(t.x + 0.5 - c.x, OlympusArenas.C_TOWER_HEIGHT - 1 + FIRE_LIFT, t.z + 0.5 - c.z)
        }
        towers + listOf(doubleArrayOf(0.0, OlympusLayout.HEKATAION_HEIGHT + 1 + FIRE_LIFT, 0.0))
    }

    /** Das Bild sitzt so weit über der Unterkante des Feuerblocks. */
    private const val FIRE_LIFT = 0.45

    /** Phasen-Szene: Kamera beginnt bei (vorn, rechts, hoch) relativ zu Anker und Blick … */
    val PHASE_CAM_START = doubleArrayOf(6.5, -3.5, 2.6)

    /** … und endet ihren Bogen hier (rechts gespiegelt in Phase III). */
    val PHASE_CAM_END = doubleArrayOf(-2.5, 6.8, 3.2)

    /** Stichproben auf dem Bogen zwischen Anfang und Ende (für die Prüfung auf dem Server). */
    private const val PHASE_ARC_SAMPLES = 6

    /**
     * Kamerapunkte der Phasen-Szene als (vorn, rechts, hoch) relativ zu Anker und Blick: Anfang, Stichproben auf dem
     * Bogen um sie (kürzerer Winkel, Radius und Höhe linear), Ende. Der Server prüft damit vor der Szene, ob ein Punkt in
     * einem Block läge.
     */
    fun phaseCameraPoints(mirror: Boolean): List<Triple<Double, Double, Double>> {
        val m = if (mirror) -1.0 else 1.0
        val a = PHASE_CAM_START
        val b = PHASE_CAM_END
        val a0 = kotlin.math.atan2(a[1] * m, a[0])
        val a1 = kotlin.math.atan2(b[1] * m, b[0])
        var d = a1 - a0
        while (d > Math.PI) d -= 2 * Math.PI
        while (d < -Math.PI) d += 2 * Math.PI
        val r0 = kotlin.math.hypot(a[0], a[1])
        val r1 = kotlin.math.hypot(b[0], b[1])
        val list = ArrayList<Triple<Double, Double, Double>>()
        for (i in 0..PHASE_ARC_SAMPLES + 1) {
            val t = i.toDouble() / (PHASE_ARC_SAMPLES + 1)
            val ang = a0 + d * t
            val r = r0 + (r1 - r0) * t
            list += Triple(r * kotlin.math.cos(ang), r * kotlin.math.sin(ang), a[2] + (b[2] - a[2]) * t)
        }
        return list
    }

    /** Auftritt: erster Kamerapunkt so weit vor ihr (den Südweg hinab, vor dem Kopftor der Brücke). */
    const val INTRO_CAM_FROM = 33.0

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
        // Kamera: Weite am Südweg nach kurzer Blende, schleicht als Kurve hinauf zum Platz und durch das Südtor (unter
        // dem Sturz, y 145). Überarbeitung 4 (P15): Start 33 statt 36 vor ihr, sonst begänne die Handkamera 0,3 unter dem
        // Bogen des Brücken-Kopftors (P06, z −242).
        shot(
            0, EMERGE_TICK,
            cam = listOf(Key.At(INTRO_CAM_FROM, 2.5, 4.2), Key.At(27.0, -1.6, 3.4), Key.At(17.0, 1.8, 2.8), Key.At(10.0, -0.8, 2.5)),
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
        val start = Key.At(PHASE_CAM_START[0], PHASE_CAM_START[1] * m, PHASE_CAM_START[2])
        shot(0, 16, cam = listOf(Key.Last, start), look = listOf(Key.Last, Key.Actor(0, 2.6)), fov = 70f to 60f)
        shot(
            16, 80, cam = listOf(start, Key.At(PHASE_CAM_END[0], PHASE_CAM_END[1] * m, PHASE_CAM_END[2])), look = listOf(Key.Actor(0, 2.6)),
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
