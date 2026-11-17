package dev.henny.hha.stage.scripts

import dev.henny.hha.entity.boss.HermesPose
import dev.henny.hha.entity.boss.olymp.HekateAction
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.stage.EaseKind
import dev.henny.hha.stage.Key
import dev.henny.hha.stage.Pose
import dev.henny.hha.stage.StageBuilder
import dev.henny.hha.stage.StageScript
import dev.henny.hha.stage.StageScripts
import dev.henny.hha.stage.stage
import dev.henny.hha.world.olymp.OlympusLayout
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Bühnen-Skripte der RPG-Geschichte (P17, PLAN 3.4). Hier: Bezugsrahmen ([SceneAnchor]), gemeinsame Helfer und die
 * drei Ankunftsszenen; Inseln, Tartaros, Palast, Akt-Ende und Ende in [RpgStoryStages]. Registriert aus
 * `StageScripts.init()` (Server und Client), also ohne Client-Importe.
 *
 * **Bezugsrahmen:** Jedes Skript ist relativ zu einem festen Anker geschrieben (`Key.At`). Die Ankunftsszenen startet
 * P06 (`RpgArrival`) mit `OlympusLayout.ARRIVAL_SPOT`/Blick 180 bzw. vor dem Höllentor/Blick 90; die übrigen startet
 * `rpg/RpgScenes.kt` mit genau den Ankern aus [ISLE_ANCHORS] und [ACT_END_ANCHOR] (Tartaros, Palast, Ende: Lage und
 * Blick des auslösenden Spielers). Landschaftspunkte rechnet [SceneAnchor.at] aus `OlympusLayout` in Anker-Koordinaten
 * um, so stimmt die Vorschau (`stage_preview.py`, Anker = Ursprung) mit dem Spiel überein.
 *
 * **Ankunft des Himmels** (`rpg.arrival.heaven`, 520 Ticks):
 * | Ticks | Kamera | Spuren |
 * | --- | --- | --- |
 * | 0–70 | hoch über der Gruppe, Blick steil hinab (schließt an das Tunnelende an), Kurve hinab vor die Gruppe, FOV 84 → 68 | Aufblende aus Licht, Kostüm an, `FLOAT`, Lichtsäule, Federn |
 * | 70–130 | niedriger Bogen um die Gruppe | `LAND` → `KNEEL` → `RISE`, Lichtring, Ruck, `rpg.arrive`; Hermes-Puppe erscheint hoch über der Herme |
 * | 130–200 | über die Schulter der Gruppe auf Hermes, der auf der Herme landet | Landeton, Federn, Hermes Zeile 1 |
 * | 200–262 | Nahaufnahme Darsteller 0 | Hermes Zeile 2 |
 * | 262–330 | Bogen über der Gruppe | `LOOK_HANDS`, Erzähler, **Kostüm löst sich in Licht auf (300, 30 Ticks)**, Hermes fliegt fort |
 * | 330–460 | Kurvenfahrt über die Brücke zum Tempel des Boten | goldene Fernsäule am Tempel, Kapitelkarte I (380) |
 * | 460–520 | Schnitt hinter Darsteller 0, zurück in die Augen | Lichtblende über den Schnitt |
 */
object RpgStages {

    // ---------------------------------------------------------------------------------------------
    // Bezugsrahmen
    // ---------------------------------------------------------------------------------------------

    /**
     * Anker einer Szene: Lage und Blick (Grad wie `Entity.yaw`). Vorn = `(−sin yaw, 0, cos yaw)`, rechts =
     * `(−cos yaw, 0, −sin yaw)` (API.md). [at] rechnet einen Weltpunkt in `Key.At` dieses Ankers um.
     */
    class SceneAnchor(val x: Double, val y: Double, val z: Double, val yaw: Float) {
        private val fx = -sin(Math.toRadians(yaw.toDouble()))
        private val fz = cos(Math.toRadians(yaw.toDouble()))
        private val rx = -cos(Math.toRadians(yaw.toDouble()))
        private val rz = -sin(Math.toRadians(yaw.toDouble()))

        /** Weltpunkt ([wx], [wy], [wz]) als `Key.At` (vor, rechts, hoch) relativ zu diesem Anker. */
        fun at(wx: Double, wy: Double, wz: Double, up: Double = 0.0): Key.At {
            val dx = wx - x
            val dz = wz - z
            return Key.At(dx * fx + dz * fz, dx * rx + dz * rz, wy - y + up)
        }

        /** Blick (Puppen-`yaw`, relativ zum Anker) zu einem Weltpunkt von ([fromX], [fromZ]) aus. */
        fun yawToward(fromX: Double, fromZ: Double, toX: Double, toZ: Double): Float = (yawOf(toX - fromX, toZ - fromZ) - yaw)
    }

    /** Minecraft-Blick (Grad) in Richtung ([dx], [dz]). */
    fun yawOf(dx: Double, dz: Double): Float = Math.toDegrees(atan2(-dx, dz)).toFloat()

    /** Anker vom Punkt ([x], [y], [z]) mit Blick zum Punkt ([tx], [tz]). */
    fun anchorToward(x: Double, y: Double, z: Double, tx: Double, tz: Double) = SceneAnchor(x, y, z, yawOf(tx - x, tz - z))

    /** Ankunft des Himmels: wie `RpgArrival.heavenArrival` (P06). */
    val HEAVEN_ANCHOR = SceneAnchor(OlympusLayout.ARRIVAL_SPOT.x, OlympusLayout.ARRIVAL_SPOT.y, OlympusLayout.ARRIVAL_SPOT.z, OlympusLayout.ARRIVAL_YAW)

    /** Ankunft der Unterwelt: wie `RpgArrival.hellArrival` (P06), 6,5 Blöcke vor dem Höllentor, Blick 90 (Westen). */
    val HELL_ANCHOR = SceneAnchor(OlympusLayout.HELL_GATE.x + 6.5, OlympusLayout.HELL_GATE.y.toDouble(), OlympusLayout.HELL_GATE.z + 0.5, 90f)

    /** Anker der Inselszenen: Inseleingang am Brückenkopf, Blick zum Ziel der Station. */
    val ISLE_ANCHORS: Map<Char, SceneAnchor> by lazy {
        mapOf(
            'B' to isleAnchor(OlympusLayout.B, OlympusLayout.B.centerVec.x, OlympusLayout.B.centerVec.z),
            'C' to isleAnchor(OlympusLayout.C, OlympusLayout.C.centerVec.x, OlympusLayout.C.centerVec.z),
            'D' to isleAnchor(OlympusLayout.D, OlympusLayout.D.centerVec.x, OlympusLayout.D.centerVec.z),
            'E' to isleAnchor(OlympusLayout.E, OlympusLayout.E.centerVec.x, OlympusLayout.E.centerVec.z),
            'F' to isleAnchor(OlympusLayout.F, OlympusLayout.THRONE.x + 0.5, OlympusLayout.THRONE.z + 0.5),
        )
    }

    private fun isleAnchor(isle: OlympusLayout.Isle, tx: Double, tz: Double) =
        anchorToward(isle.entrance.x, isle.entrance.y, isle.entrance.z, tx, tz)

    /** Anker des Akt-Endes: Mitte des Thronhofs, Blick nach Süden (vom Thron über den Hof zum Dreiweg). */
    val ACT_END_ANCHOR: SceneAnchor by lazy { SceneAnchor(OlympusLayout.F.centerVec.x, OlympusLayout.F.centerVec.y, OlympusLayout.F.centerVec.z, 0f) }

    // ---------------------------------------------------------------------------------------------
    // IDs, Farben, Schlüssel
    // ---------------------------------------------------------------------------------------------

    const val ARRIVAL_HEAVEN = "rpg.arrival.heaven"
    const val ARRIVAL_HELL = "rpg.arrival.hell"
    const val ARRIVAL_LATE = "rpg.arrival.late"

    /** Wegfarben der Kapitelkarten (wie `RpgClientState.color`). */
    const val HEAVEN_COLOR = 0xFFD75E
    const val HELL_COLOR = 0x3FD8FF

    /** Warmes Licht für Blenden. */
    const val LIGHT = 0xFFF6E0

    /** Nachtblau für die Stimmung am Dreiweg. */
    const val NIGHT = 0x060A1C

    /** Stärke der Nacht-Tönung (Blende, kein Bildschirm-Pass). */
    const val NIGHT_STRENGTH = 0.32f

    const val KEY = "hha.rpg.scene"

    /**
     * Schwebe-Höhe der Darsteller als Marke `pose.lift:<von>:<bis>:<ticks>` (Blöcke, weich `OUT`). Die Spieler stehen
     * auf dem Server fest auf ihren Marken; die Hebung ist reine Darstellung am Client. **Wunsch an P05/P19**
     * (`requests/P17.md` Punkt 1): `StagePlayer` liest Marken mit diesem Präfix und hebt Modell und `Key.Actor`/`Group`
     * mit. Bis dahin ist die Marke ohne Wirkung (die Gruppe schwebt dann in `FLOAT` am Boden, die Kamera fällt).
     */
    fun liftMark(from: Double, to: Double, ticks: Int): String = "$LIFT_PREFIX$from:$to:$ticks"

    const val LIFT_PREFIX = "pose.lift:"

    /** Hebung zu Beginn der Ankünfte (Blöcke über der Marke). */
    const val HEAVEN_LIFT = 14.0
    const val HELL_LIFT = 12.0
    const val LATE_LIFT = 10.0

    /** Ticks der Blende vor dem Schnitt … */
    const val DIP_IN = 8

    /** … und danach. */
    const val DIP_OUT = 16

    // ---------------------------------------------------------------------------------------------
    // Registrierung
    // ---------------------------------------------------------------------------------------------

    fun register() {
        StageScripts.register(arrivalHeaven())
        StageScripts.register(arrivalHell())
        StageScripts.register(arrivalLate())
        RpgStoryStages.register()
    }

    // ---------------------------------------------------------------------------------------------
    // rpg.arrival.heaven
    // ---------------------------------------------------------------------------------------------

    const val ARRIVAL_HEAVEN_LENGTH = 520

    /** Landung der Gruppe (Pose `LAND`, Lichtring, Ruck). */
    const val HEAVEN_TOUCHDOWN = 64

    /** Kostüm löst sich auf: Beginn und Dauer (Briefing). */
    const val HEAVEN_DISSOLVE_AT = 300
    const val HEAVEN_DISSOLVE_TICKS = 30

    /** Kapitelkarte I. */
    const val HEAVEN_CHAPTER_AT = 380

    /** Hermes landet auf der östlichen Herme am Weg zur Brücke (Herme x 4, z −20, 4,5 hoch). */
    private val HERMES_PERCH by lazy { HEAVEN_ANCHOR.at(4.5, OlympusLayout.A.floorY + 4.5, -19.5) }

    /** Goldene Fernsäule am Tempel des Boten (Treppe im Süden des Tempels). */
    private val TEMPLE_BEACON by lazy { HEAVEN_ANCHOR.at(OlympusLayout.B.centerVec.x, OlympusLayout.B.floorY.toDouble(), OlympusLayout.B.center.z + 26.0) }

    /** Blickziel der Brückenfahrt: Front des Tempels. */
    private val TEMPLE_FRONT by lazy { HEAVEN_ANCHOR.at(OlympusLayout.B.centerVec.x, OlympusLayout.B.floorY + 8.0, OlympusLayout.B.center.z + 18.0) }

    private fun arrivalHeaven(): StageScript = stage(ARRIVAL_HEAVEN, ARRIVAL_HEAVEN_LENGTH) {
        val k = "$KEY.arrival"
        val perch = HERMES_PERCH
        // --- Kamera ---------------------------------------------------------------------------------
        // Hoch über der Gruppe, steil hinab (das Tunnelende öffnet sich darüber), dann in einer Kurve vor die Gruppe:
        // Die Kamera fällt mit ihr und bremst ab (OUT).
        shot(
            0, 70, cam = listOf(Key.At(-3.0, 0.6, 34.0), Key.At(2.0, 1.4, 17.0), Key.At(7.5, 2.4, 4.2)),
            look = listOf(Key.Group(0.5), Key.Group(1.1)), fov = 84f to 68f, ease = EaseKind.OUT, follow = 0.08f, handheld = 0.35f,
        )
        shot(70, 130, cam = listOf(Key.Last, Key.At(6.0, -3.2, 1.4)), look = listOf(Key.Group(1.0)), fov = 68f to 60f, arc = true, handheld = 0.6f)
        shot(
            130, 200, cam = listOf(Key.At(-0.6, -1.1, 2.4), Key.At(-0.3, -0.8, 2.6)), look = listOf(Key.Puppet("hermes", 1.7)),
            fov = 58f to 52f, follow = 0.25f, cut = true,
        )
        shot(
            200, 262, cam = listOf(Key.ActorRel(0, 2.3, 0.7, 1.75), Key.ActorRel(0, 1.9, -0.5, 1.7)), look = listOf(Key.Actor(0, 1.55)),
            fov = 50f to 46f, cut = true,
        )
        shot(262, 330, cam = listOf(Key.At(3.6, 2.8, 3.6), Key.At(3.8, -2.8, 3.2)), look = listOf(Key.Group(1.0)), fov = 60f to 64f, arc = true, cut = true)
        shot(
            330, 460,
            cam = listOf(Key.Last, Key.At(5.0, 1.2, 9.0), Key.At(28.0, -3.0, 12.5), Key.At(58.0, 2.5, 14.5), Key.At(80.0, -1.0, 15.0)),
            look = listOf(Key.Last, Key.At(26.0, 0.0, 4.0), TEMPLE_FRONT), fov = 64f to 72f, follow = 0.15f, handheld = 0.6f,
        )
        backToEyes(460, ARRIVAL_HEAVEN_LENGTH)

        // --- Ankunft --------------------------------------------------------------------------------
        mark(0, "start")
        mark(0, liftMark(HEAVEN_LIFT, 0.0, HEAVEN_TOUCHDOWN))
        fade(0, LIGHT, 1f, 0f, 30)
        costume(0, on = true)
        pose(0, actor = -1, pose = Pose.FLOAT, ticks = HEAVEN_TOUCHDOWN)
        fx(0, "rpg.light_shaft", Vfx.Style.HEAVEN, Key.Group(0.0), a = 6.5f, b = 60f, c = 0f, life = 96)
        fx(6, "rpg.feathers", Vfx.Style.HEAVEN, Key.Group(7.0), a = 4f, b = 12f, c = 6.5f, life = 90)
        sound(0, "minecraft:item.elytra.flying", volume = 0.25f, pitch = 1.2f)

        // --- Landung --------------------------------------------------------------------------------
        pose(HEAVEN_TOUCHDOWN, actor = -1, pose = Pose.LAND, ticks = 14)
        pose(HEAVEN_TOUCHDOWN + 14, actor = -1, pose = Pose.KNEEL, ticks = 38)
        pose(HEAVEN_TOUCHDOWN + 52, actor = -1, pose = Pose.RISE, ticks = 24)
        sound(HEAVEN_TOUCHDOWN + 2, "hha:rpg.arrive", volume = 0.9f)
        fx(HEAVEN_TOUCHDOWN + 2, "rpg.land_ring", Vfx.Style.HEAVEN, Key.Group(0.0), a = 7f, life = 26)
        kick(HEAVEN_TOUCHDOWN + 2, pitch = 1.4f, roll = 0.3f, ticks = 6)

        // --- Hermes ---------------------------------------------------------------------------------
        puppet(118, "hermes", "hha:hermes", Key.At(perch.f, perch.r, perch.u + 8.5), yaw = 180f)
        puppetAct(118, "hermes", HermesPose.ASCEND)
        puppetMove(122, 148, "hermes", listOf(perch), lift = 0.0)
        puppetAct(148, "hermes", HermesPose.IDLE)
        sound(146, "minecraft:entity.breeze.land", volume = 0.6f, pitch = 1.2f)
        fx(147, "rpg.feathers", Vfx.Style.HEAVEN, Key.At(perch.f, perch.r, perch.u + 1.6), a = 1.3f, b = 10f, c = 3f, life = 60)
        // Neue Zeilen ohne Aufnahme: Text mit Stimm-Lauten (Sprecher Hermes).
        caption(150, Cine.SPEAKER_HERMES, "$k.hermes.1")
        puppetAct(154, "hermes", HermesPose.TAUNT)
        caption(212, Cine.SPEAKER_HERMES, "$k.hermes.2")
        puppetAct(214, "hermes", HermesPose.CAST)

        // --- Vision der Rüstung ---------------------------------------------------------------------
        pose(262, actor = -1, pose = Pose.LOOK_HANDS, ticks = 64)
        caption(292, Cine.SPEAKER_NARRATOR, "$k.vision")
        costume(HEAVEN_DISSOLVE_AT, on = false, dissolve = HEAVEN_DISSOLVE_TICKS)
        puppetAct(296, "hermes", HermesPose.ASCEND)
        puppetMove(300, 330, "hermes", listOf(Key.At(perch.f, perch.r, perch.u + 4.0), Key.At(perch.f + 8.0, perch.r - 3.0, perch.u + 12.0)))
        puppetRemove(334, "hermes")

        // --- Der Weg --------------------------------------------------------------------------------
        fx(336, "rpg.beacon", Vfx.Style.GOLD, TEMPLE_BEACON, a = 0.7f, b = 52f, life = 170)
        chapterCard(HEAVEN_CHAPTER_AT, "h1_word", HEAVEN_COLOR)
        mark(ARRIVAL_HEAVEN_LENGTH, "end")
    }

    // ---------------------------------------------------------------------------------------------
    // rpg.arrival.hell
    // ---------------------------------------------------------------------------------------------

    /**
     * **Ankunft der Unterwelt** (480 Ticks), Terrasse vor dem Höllentor am Dreiweg, Nacht-Tönung als Blende:
     * | Ticks | Kamera | Spuren |
     * | --- | --- | --- |
     * | 0–80 | hoch über der Gruppe, Kurve hinab hinter sie | Aufblende, Nacht, Kostüm an, `FLOAT` → `LAND` → `KNEEL`, Seelenglut, Lichtring |
     * | 80–150 | über die Schulter auf Hekate (Puppe, Fackeln) | Hekate erscheint in Glut, Zeile 1 |
     * | 150–205 | vor Hekate | Zeile 2, `RISE` |
     * | 205–250 | Nahaufnahme Darsteller 0 | `LOOK_HANDS`, Erzähler, **Kostüm zerfällt in Glut (236, 36 Ticks)** |
     * | 250–300 | Bogen um die Gruppe | Hekate dreht den Schlüssel |
     * | 300–400 | Fahrt auf das offene Höllentor zu, Blick hinein | Wirbel im Tor, Glut, Kapitelkarte I (Unterwelt) |
     * | 400–480 | Schnitt hinter Darsteller 0, zurück in die Augen | Blende hebt die Nacht auf |
     */
    const val ARRIVAL_HELL_LENGTH = 480
    const val HELL_TOUCHDOWN = 60
    const val HELL_DISSOLVE_AT = 236
    const val HELL_DISSOLVE_TICKS = 36
    const val HELL_CHAPTER_AT = 330

    /** Mitte der Toröffnung (Fuß `HELL_GATE`, Öffnung um 5,5 hoch, Ebene spannt z). */
    private val GATE_CENTER by lazy { HELL_ANCHOR.at(OlympusLayout.HELL_GATE.x + 0.5, OlympusLayout.HELL_GATE.y + 5.5, OlympusLayout.HELL_GATE.z + 0.5) }

    /** Hekate steht rechts zwischen Gruppe und Tor, zur Gruppe gewandt. */
    private val HEKATE_SPOT = Key.At(3.5, 3.5, 0.0)

    private fun arrivalHell(): StageScript = stage(ARRIVAL_HELL, ARRIVAL_HELL_LENGTH) {
        val k = "$KEY.arrival"
        val gate = GATE_CENTER
        // --- Kamera ---------------------------------------------------------------------------------
        shot(
            0, 80, cam = listOf(Key.At(-6.0, -3.0, 22.0), Key.At(-7.5, -2.4, 14.0), Key.At(-5.5, -1.6, 8.0)),
            look = listOf(Key.Group(0.6), Key.Group(1.2)), fov = 80f to 66f, ease = EaseKind.OUT, follow = 0.08f, handheld = 0.35f,
        )
        shot(80, 150, cam = listOf(Key.At(-1.0, -1.4, 2.4), Key.At(-0.6, -0.9, 2.6)), look = listOf(Key.Puppet("hekate", 2.6)), fov = 56f to 52f, follow = 0.2f, cut = true)
        shot(
            150, 205, cam = listOf(Key.PuppetRel("hekate", 3.2, 1.0, 2.2), Key.PuppetRel("hekate", 2.8, -0.6, 2.4)),
            look = listOf(Key.Puppet("hekate", 2.4)), fov = 54f to 50f, cut = true,
        )
        shot(
            205, 250, cam = listOf(Key.ActorRel(0, 2.3, -0.7, 1.75), Key.ActorRel(0, 1.9, 0.5, 1.7)), look = listOf(Key.Actor(0, 1.55)),
            fov = 50f to 46f, cut = true,
        )
        shot(250, 300, cam = listOf(Key.At(3.5, -2.6, 3.2), Key.At(3.8, 2.6, 3.0)), look = listOf(Key.Group(1.0)), fov = 60f to 62f, arc = true, cut = true)
        shot(
            300, 400, cam = listOf(Key.At(-2.5, 0.6, 2.4), Key.At(gate.f - 3.2, 0.2, gate.u - 1.3)),
            look = listOf(Key.At(gate.f, 0.0, gate.u - 0.5), Key.At(gate.f + 2.0, 0.0, gate.u - 0.2)), fov = 62f to 50f, cut = true, follow = 0.15f,
        )
        backToEyes(400, ARRIVAL_HELL_LENGTH, color = NIGHT, holdFrom = NIGHT_STRENGTH)

        // --- Ankunft bei Nacht ----------------------------------------------------------------------
        mark(0, "start")
        mark(0, liftMark(HELL_LIFT, 0.0, HELL_TOUCHDOWN))
        fade(0, NIGHT, 1f, NIGHT_STRENGTH, 40)
        music(0, "hha:music.tartaros")
        costume(0, on = true)
        pose(0, actor = -1, pose = Pose.FLOAT, ticks = HELL_TOUCHDOWN)
        fx(0, "rpg.embers", Vfx.Style.SOUL, Key.Group(0.0), a = 5f, b = 20f, c = 0.05f, life = 300)
        pose(HELL_TOUCHDOWN, actor = -1, pose = Pose.LAND, ticks = 14)
        pose(HELL_TOUCHDOWN + 14, actor = -1, pose = Pose.KNEEL, ticks = 70)
        sound(HELL_TOUCHDOWN + 2, "hha:rpg.arrive", volume = 0.9f, pitch = 0.8f)
        fx(HELL_TOUCHDOWN + 2, "rpg.land_ring", Vfx.Style.SOUL, Key.Group(0.0), a = 6f, life = 26)
        kick(HELL_TOUCHDOWN + 2, pitch = 1.2f, roll = -0.3f, ticks = 6)

        // --- Hekate ---------------------------------------------------------------------------------
        puppet(72, "hekate", "hha:hekate", HEKATE_SPOT, yaw = 225f)
        fx(72, "rpg.embers", Vfx.Style.FIRE, HEKATE_SPOT, a = 1.2f, b = 14f, c = 0.08f, life = 60)
        sound(72, "hha:boss.torch", volume = 0.7f)
        caption(96, Cine.SPEAKER_HEKATE, "$k.hekate.1")
        pose(150, actor = -1, pose = Pose.RISE, ticks = 24)
        caption(172, Cine.SPEAKER_HEKATE, "$k.hekate.2")
        puppetAct(176, "hekate", HekateAction.TORCH_SWEEP)

        // --- Das Kostüm zerfällt in Glut ------------------------------------------------------------
        pose(210, actor = -1, pose = Pose.LOOK_HANDS, ticks = 60)
        costume(HELL_DISSOLVE_AT, on = false, dissolve = HELL_DISSOLVE_TICKS)
        caption(252, Cine.SPEAKER_NARRATOR, "$k.vision")

        // --- Das offene Tor -------------------------------------------------------------------------
        puppetAct(292, "hekate", HekateAction.KEY_TURN)
        sound(294, "hha:boss.key", volume = 0.8f)
        sound(302, "minecraft:block.conduit.activate", volume = 0.6f, pitch = 0.7f)
        fx(300, "rpg.gate_glow", Vfx.Style.SOUL, gate, dir = Key.At(gate.f + 1.0, gate.r, gate.u), a = 4.2f, life = 170)
        fx(304, "rpg.embers", Vfx.Style.SOUL, Key.At(gate.f, gate.r, 0.2), a = 2.5f, b = 18f, c = 0.07f, life = 160)
        chapterCard(HELL_CHAPTER_AT, "u1_chain", HELL_COLOR, hell = true)
        puppetRemove(398, "hekate")
        mark(ARRIVAL_HELL_LENGTH, "end")
    }

    // ---------------------------------------------------------------------------------------------
    // rpg.arrival.late
    // ---------------------------------------------------------------------------------------------

    /**
     * **Nachzügler** (120 Ticks; auch der lokale Rückfall von P18 ohne Server): Kamera vom Himmel herab vor die eigene
     * Figur, Landung, Schwenk den Weg entlang, zurück in die Augen. Kein Kostüm, keine Zeilen, keine Musik.
     */
    const val ARRIVAL_LATE_LENGTH = 120
    const val LATE_TOUCHDOWN = 40

    /** Neutrales Weiß (beide Wege). */
    const val PALE = 0xE8ECF4

    private fun arrivalLate(): StageScript = stage(ARRIVAL_LATE, ARRIVAL_LATE_LENGTH) {
        shot(
            0, 60, cam = listOf(Key.ActorRel(0, -1.0, 0.5, 17.0), Key.ActorRel(0, 2.5, 1.0, 9.0), Key.ActorRel(0, 3.6, 1.4, 2.0)),
            look = listOf(Key.Actor(0, 0.6), Key.Actor(0, 1.4)), fov = 78f to 62f, ease = EaseKind.OUT, follow = 0.08f, handheld = 0.3f,
        )
        shot(
            60, ARRIVAL_LATE_LENGTH, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.At(12.0, 0.0, 2.0), Key.EyeLook),
            ease = EaseKind.SMOOTH, handheld = 0f,
        )
        mark(0, "start")
        mark(0, liftMark(LATE_LIFT, 0.0, LATE_TOUCHDOWN))
        fade(0, PALE, 1f, 0f, 24)
        pose(0, actor = 0, pose = Pose.FLOAT, ticks = LATE_TOUCHDOWN)
        pose(LATE_TOUCHDOWN, actor = 0, pose = Pose.LAND, ticks = 12)
        pose(LATE_TOUCHDOWN + 14, actor = 0, pose = Pose.RISE, ticks = 16)
        sound(LATE_TOUCHDOWN + 2, "hha:rpg.arrive", volume = 0.7f)
        fx(LATE_TOUCHDOWN + 2, "rpg.land_ring", Vfx.Style.STORM, Key.Actor(0, 0.0), a = 3.5f, life = 22)
        mark(ARRIVAL_LATE_LENGTH, "end")
    }
}

// Gemeinsame Bausteine der RPG-Skripte (auch für RpgStoryStages).

/** Kapitelkarte des Kapitels [id] (Schlüssel wie `RpgStory.chapter`, P06); [hell] = Kicker des Unterweltwegs. */
internal fun StageBuilder.chapterCard(at: Int, id: String, color: Int, hell: Boolean = false, ticks: Int = 100) =
    chapter(at, "hha.rpg.story.$id.kicker" + if (hell) ".hell" else "", "hha.rpg.story.$id.title", "hha.rpg.story.$id.sub", ticks, color)

/**
 * Rückkehr in die Augen mit Schnitt: kurze Blende in [color] über den Schnitt bei [from] ([RpgStages.DIP_IN] Ticks vorher zu, dann
 * in [RpgStages.DIP_OUT] Ticks auf), Kamera hinter Darsteller 0, dann `Key.Eye`. Endet bei [to] (= Skriptlänge).
 */
internal fun StageBuilder.backToEyes(from: Int, to: Int, color: Int = RpgStages.LIGHT, dip: Float = 1f, holdFrom: Float = 0f) {
    fade(from - RpgStages.DIP_IN, color, holdFrom, dip, RpgStages.DIP_IN)
    fade(from, color, dip, 0f, RpgStages.DIP_OUT)
    shot(
        from, to, cam = listOf(Key.ActorRel(0, -1.8, 0.9, 2.0), Key.Eye), look = listOf(Key.Actor(0, 1.5), Key.EyeLook),
        fov = 64f to 70f, ease = EaseKind.SMOOTH, handheld = 0f, cut = true,
    )
}
