package dev.henny.hha.stage.scripts

import dev.henny.hha.entity.boss.olymp.ZeusAction
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.stage.EaseKind
import dev.henny.hha.stage.Key
import dev.henny.hha.stage.Pose
import dev.henny.hha.stage.StageScript
import dev.henny.hha.stage.StageScripts
import dev.henny.hha.stage.stage
import dev.henny.hha.stage.scripts.RpgStages.SceneAnchor
import dev.henny.hha.world.olymp.OlympusLayout

/**
 * Szenen der Geschichte nach der Ankunft (P17, PLAN 3.4): Inselszenen B–F, Tartaros, Palast, Akt-Ende, Ende. Gestartet
 * von `rpg/RpgScenes.kt` mit den Ankern aus [RpgStages] (Inseln, Akt-Ende) bzw. mit Lage und Blick des auslösenden
 * Spielers (Tartaros, Palast, Ende). Darsteller = alle Spieler, die die Szene sehen.
 *
 * **Inselszenen** (`rpg.isle.b` … `.f`, je [ISLE_LENGTH] Ticks):
 * | Ticks | Kamera | Spuren |
 * | --- | --- | --- |
 * | 0–[ISLE_FLIGHT] | aus den Augen (`Last`) hinauf und in einer Kurve über die Insel zum Ziel der Station | goldene Fernsäule am Ziel (6), Erzähler (12), Karte (52) |
 * | [ISLE_FLIGHT]–[ISLE_LENGTH] | Schnitt hinter Darsteller 0, zurück in die Augen | Lichtblende über den Schnitt |
 *
 * Karte: D, E, F zeigen die Kapitelkarte (II, III, IV; die Insel gehört immer zu genau diesem Kapitel, die Brücken
 * davor sind versiegelt). B und C zeigen eine Ortskarte: Kapitel I trägt schon die Ankunft, und den Dreiweg erreicht
 * der Himmelsweg im Kapitel II, sein eigenes Kapitel (Zwischenspiel) kommt erst nach Zeus.
 */
object RpgStoryStages {

    const val ISLE_LENGTH = 160
    const val ISLE_FLIGHT = 104
    const val ISLE_LINE_AT = 12
    const val ISLE_CARD_AT = 52
    const val ISLE_BEACON_AT = 6
    const val ISLE_BEACON_LIFE = 120

    /** Schmiede: Die Fahrt endet so weit vor dem Amboss, auf der Linie von der Esse durch den Amboss. */
    const val FORGE_VIEW = 10.0

    const val TARTAROS = "rpg.tartaros"
    const val TARTAROS_LENGTH = 200

    /** Der Seelenführer (`TartarosGuide`, P12) erscheint so weit vor dem Spieler in Blickrichtung (`BossTimings.Guide.GHOST_DISTANCE`). */
    const val GUIDE_DISTANCE = 8.0

    const val PALACE = "rpg.palace"
    const val PALACE_LENGTH = 160

    /** Tick, an dem `RpgScenes` die Kapitelkarte des laufenden Kapitels (VI bzw. II) schickt. */
    const val PALACE_CARD_AT = 96

    /** Blickziel zum Palast: so weit voraus (Anker blickt zum Palast; Fernblicke über 12 Chunks zeigen Leere). */
    const val PALACE_GLOW_DISTANCE = 48.0

    const val ACT_END = "rpg.act_end"
    const val ACT_END_LENGTH = 300

    const val END = "rpg.end"
    const val END_LENGTH = 600

    /** Ab hier läuft im Ende der Abspann-Musik (`music.credits`, 90 s, keine Schleife). */
    const val END_MUSIC_AT = 180

    /** `ECLIPSE` im Ende: dunkelt den stillen Tartaros ab und blendet aus, während das Licht kommt („es wird hell“). */
    const val END_ECLIPSE_AT = 100
    const val END_ECLIPSE_TICKS = 160
    const val END_ECLIPSE_STRENGTH = 0.45f

    /** Ende: so hoch steigt die Gruppe in der Darstellung (Blöcke). */
    const val END_ASCENT = 16.0

    /** Ende: ab hier weiß, ab [END_RETURN] zurück. */
    const val END_WHITE_AT = 400
    const val END_RETURN = 556

    fun isleId(letter: Char) = "rpg.isle.${letter.lowercaseChar()}"

    fun register() {
        for (letter in listOf('B', 'C', 'D', 'E', 'F')) StageScripts.register(isle(letter))
        StageScripts.register(tartaros())
        StageScripts.register(palace())
        StageScripts.register(actEnd())
        StageScripts.register(end())
    }

    // ---------------------------------------------------------------------------------------------
    // Inseln
    // ---------------------------------------------------------------------------------------------

    /** Fahrt einer Insel: Kamera- und Blickpunkte nach `Last`, Fuß der Fernsäule, Kapitel (oder `null` = Ortskarte). */
    private class Flight(val cam: List<Key>, val look: List<Key>, val beacon: Key.At, val chapter: String?)

    private fun flight(letter: Char): Flight {
        val a: SceneAnchor = RpgStages.ISLE_ANCHORS.getValue(letter)
        return when (letter) {
            // Tempel des Boten: über den Platz, seitlich vor die Front; die Säule steht am Olymp-Altar im Tempel.
            'B' -> Flight(
                listOf(Key.At(-4.0, 3.0, 5.0), Key.At(3.0, -9.0, 10.0), Key.At(7.0, -12.0, 12.0)),
                listOf(Key.At(12.0, 0.0, 5.0), Key.At(16.0, 0.0, 8.0)),
                OlympusLayout.B.altar!!.let { a.at(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }, null,
            )
            // Dreiweg: hoch über die Kreuzung, Blick hinab; die Säule steht am Nordost-Weg (nach D, das Ziel von Kapitel II).
            'C' -> {
                val ne = OlympusLayout.C.centerVec.add(OlympusLayout.dir(OlympusLayout.C_PATH_ANGLES[OlympusLayout.C_PATH_NORTHEAST]).multiply(OlympusLayout.C.arenaRadius.toDouble()))
                val beacon = a.at(ne.x, OlympusLayout.C.floorY.toDouble(), ne.z)
                val center = a.at(OlympusLayout.C.centerVec.x, OlympusLayout.C.floorY.toDouble(), OlympusLayout.C.centerVec.z)
                Flight(
                    listOf(Key.At(-4.0, 2.5, 6.0), Key.At(center.f - 20.0, 4.0, 16.0), Key.At(center.f - 9.0, 2.0, 22.0)),
                    listOf(Key.At(center.f, center.r, 2.0), Key.At((center.f + beacon.f) / 2, (center.r + beacon.r) / 2, 0.0)),
                    beacon, null,
                )
            }
            // Schmiede: über die Roste zum Amboss, die Esse dahinter.
            'D' -> {
                val anvil = a.at(OlympusLayout.ANVIL.x + 0.5, OlympusLayout.D.floorY + 1.5, OlympusLayout.ANVIL.z + 0.5)
                val forge = a.at(OlympusLayout.FORGE.x + 0.5, OlympusLayout.D.floorY + 3.0, OlympusLayout.FORGE.z + 0.5)
                // Ende auf der Linie Esse → Amboss, [FORGE_VIEW] Blöcke vor dem Amboss: Amboss vorn, Esse dahinter.
                val df = anvil.f - forge.f
                val dr = anvil.r - forge.r
                val n = kotlin.math.hypot(df, dr).coerceAtLeast(1e-3)
                val endF = anvil.f + df / n * FORGE_VIEW
                val endR = anvil.r + dr / n * FORGE_VIEW
                Flight(
                    listOf(Key.At(-4.0, 2.5, 6.0), Key.At((endF - 4.0) / 2.0, endR / 2.0, 9.0), Key.At(endF, endR, 6.0)),
                    listOf(Key.At(anvil.f - 6.0, 0.0, 2.0), anvil, Key.At((anvil.f + forge.f) / 2, (anvil.r + forge.r) / 2, forge.u)),
                    OlympusLayout.D.altar!!.let { a.at(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }, "h2_work",
                )
            }
            // Stufen der Macht: flach über den Platz zwischen den Sockeln, dann die Freitreppe hinauf.
            'E' -> {
                val seal = a.at(OlympusLayout.E.altar!!.x + 0.5, OlympusLayout.E.floorY.toDouble(), OlympusLayout.E.altar.z + 0.5)
                val top = a.at(OlympusLayout.E.centerVec.x, OlympusLayout.E.floorY + 12.0, OlympusLayout.E_STAIRS_TOP_Z + 0.5)
                Flight(
                    listOf(Key.At(-4.0, 2.0, 5.0), Key.At(seal.f - 35.0, -3.0, 6.0), Key.At(seal.f - 15.0, 2.0, 10.0), Key.At(seal.f - 5.0, 0.0, 15.0)),
                    listOf(Key.At(seal.f - 11.0, 0.0, 3.0), Key.At(seal.f, seal.r, 4.0), top),
                    seal, "h3_force",
                )
            }
            // Thron des Zeus: zwischen den Blitzpfeilern über den Hof auf den Thron zu.
            else -> {
                val center = a.at(OlympusLayout.F.centerVec.x, OlympusLayout.F.floorY + 3.0, OlympusLayout.F.centerVec.z)
                val seal = a.at(OlympusLayout.F.altar!!.x + 0.5, OlympusLayout.F.floorY.toDouble(), OlympusLayout.F.altar.z + 0.5)
                val throne = a.at(OlympusLayout.THRONE.x + 0.5, OlympusLayout.THRONE.y + 2.0, OlympusLayout.THRONE.z + 0.5)
                Flight(
                    listOf(Key.At(-4.0, 2.5, 7.0), Key.At(center.f - 12.0, -5.0, 12.0), Key.At(center.f + 10.0, 3.0, 11.0), Key.At(seal.f - 12.0, 0.0, 9.0)),
                    listOf(center, Key.At(seal.f, seal.r, 2.0), throne),
                    seal, "h4_fear",
                )
            }
        }
    }

    private fun isle(letter: Char): StageScript = stage(isleId(letter), ISLE_LENGTH) {
        val key = "${RpgStages.KEY}.isle.${letter.lowercaseChar()}"
        val fl = flight(letter)
        shot(
            0, ISLE_FLIGHT, cam = listOf(Key.Last) + fl.cam, look = listOf(Key.Last) + fl.look, fov = 66f to 72f,
            ease = EaseKind.SMOOTH, follow = 0.15f, handheld = 0.5f,
        )
        backToEyes(ISLE_FLIGHT, ISLE_LENGTH)
        mark(0, "start")
        fx(ISLE_BEACON_AT, "rpg.beacon", Vfx.Style.GOLD, fl.beacon, a = 0.6f, b = 44f, life = ISLE_BEACON_LIFE)
        sound(ISLE_BEACON_AT, "minecraft:block.beacon.activate", volume = 0.45f, pitch = 1.3f)
        caption(ISLE_LINE_AT, Cine.SPEAKER_NARRATOR, "$key.line")
        if (fl.chapter != null) chapterCard(ISLE_CARD_AT, fl.chapter, RpgStages.HEAVEN_COLOR, ticks = 90)
        else card(ISLE_CARD_AT, "$key.card", "$key.card.sub", 90)
        mark(ISLE_LENGTH, "end")
    }

    // ---------------------------------------------------------------------------------------------
    // rpg.tartaros
    // ---------------------------------------------------------------------------------------------

    /**
     * **Tartaros** (200 Ticks), erste Ankunft. Anker = Spieler, Blick = sein Blick: Der Seelenführer des Bestands
     * (`TartarosGuide`) erscheint [GUIDE_DISTANCE] Blöcke voraus und zeigt mit goldener Fernsäule zum nächsten Glied.
     * Die Szene filmt ihn dort, statt eine zweite Hermes-Figur zu stellen.
     * | Ticks | Kamera | Spuren |
     * | --- | --- | --- |
     * | 0–70 | Kran hinter der Gruppe hinauf, Blick weit über das Land | Glut, Erzähler |
     * | 70–140 | seitlich auf den Platz des Seelenführers | Hermes-Zeile (Text mit Stimm-Lauten) |
     * | 140–200 | zurück in die Augen | Ortskarte „Tartaros“ (128) |
     */
    private fun tartaros(): StageScript = stage(TARTAROS, TARTAROS_LENGTH) {
        val key = "${RpgStages.KEY}.tartaros"
        shot(
            0, 70, cam = listOf(Key.Last, Key.At(-3.5, 1.2, 3.0), Key.At(-6.5, 2.0, 11.5)),
            look = listOf(Key.Last, Key.Group(1.4), Key.At(36.0, 0.0, 4.0)), fov = 70f to 74f, follow = 0.15f, handheld = 0.5f,
        )
        shot(
            70, 140, cam = listOf(Key.At(2.5, -3.6, 2.2), Key.At(3.6, -3.0, 2.0)), look = listOf(Key.At(GUIDE_DISTANCE, 0.0, 1.9)),
            fov = 58f to 54f, follow = 0.2f, cut = true,
        )
        shot(140, TARTAROS_LENGTH, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), ease = EaseKind.SMOOTH, handheld = 0f)
        mark(0, "start")
        fx(0, "rpg.embers", Vfx.Style.FIRE, Key.Group(0.0), a = 7f, b = 22f, c = 0.045f, life = TARTAROS_LENGTH)
        caption(10, Cine.SPEAKER_NARRATOR, "$key.line")
        caption(78, Cine.SPEAKER_HERMES, "$key.hermes")
        card(128, "$key.card", "$key.card.sub", 80)
        mark(TARTAROS_LENGTH, "end")
    }

    // ---------------------------------------------------------------------------------------------
    // rpg.palace
    // ---------------------------------------------------------------------------------------------

    /**
     * **Palast** (160 Ticks), nach dem fünften Glied. Anker = Spieler, Blick zum Palast (`RpgScenes` fragt
     * `TartarosGuide.target`; ohne Treffer der Blick des Spielers).
     * | Ticks | Kamera | Spuren |
     * | --- | --- | --- |
     * | 0–60 | Blick hinauf, folgt der fallenden Kette | Kette (`rpg.chain_fall`), Kettenklang, Schlag und Ruck beim Aufprall |
     * | 60–120 | Teleblick zum Palast | Siegel glüht auf (`rpg.rune_glow`, Hades-Grün), Erzähler; Kapitelkarte vom Server (96) |
     * | 120–160 | zurück in die Augen | – |
     */
    private fun palace(): StageScript = stage(PALACE, PALACE_LENGTH) {
        val key = "${RpgStages.KEY}.palace"
        shot(
            0, 60, cam = listOf(Key.Last, Key.At(-4.5, 1.5, 1.8), Key.At(-5.0, 1.8, 2.4)),
            look = listOf(Key.Last, Key.At(6.0, 0.0, 16.0), Key.At(6.0, 0.0, 3.0)), fov = 64f to 60f, follow = 0.12f,
        )
        shot(
            60, 120, cam = listOf(Key.Last, Key.At(-2.0, 0.8, 3.2)), look = listOf(Key.Last, Key.At(PALACE_GLOW_DISTANCE, 0.0, 9.0)),
            fov = 60f to 46f, follow = 0.15f,
        )
        shot(120, PALACE_LENGTH, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), ease = EaseKind.SMOOTH, handheld = 0f)
        mark(0, "start")
        fx(0, "rpg.chain_fall", Vfx.Style.FIRE, Key.At(6.0, 0.0, 22.0), a = 10f, b = 16f, c = 22f, life = 110)
        sound(4, "minecraft:block.chain.break", volume = 1f, pitch = 0.6f)
        sound(50, "hha:boss.thud", volume = 0.8f)
        kick(50, pitch = 1.2f, ticks = 6)
        fx(62, "rpg.rune_glow", Vfx.Style.HADES, Key.At(PALACE_GLOW_DISTANCE, 0.0, 9.0), dir = Key.At(0.0, 0.0, 9.0), a = 7f, life = 98)
        sound(62, "minecraft:block.respawn_anchor.charge", volume = 0.7f, pitch = 0.8f)
        caption(70, Cine.SPEAKER_NARRATOR, "$key.line")
        mark(PALACE_CARD_AT, "card")
        mark(PALACE_LENGTH, "end")
    }

    // ---------------------------------------------------------------------------------------------
    // rpg.act_end
    // ---------------------------------------------------------------------------------------------

    /**
     * **Ende des ersten Akts** (300 Ticks), nach Zeus. Anker = Mitte des Thronhofs, Blick nach Süden
     * ([RpgStages.ACT_END_ANCHOR]); Zeus als Puppe vor dem Thron.
     * | Ticks | Kamera | Spuren |
     * | --- | --- | --- |
     * | 0–80 | vor Zeus, leicht von unten | Zeus Zeile 1 |
     * | 80–150 | über seine Schulter nach Süden | Zeus weist hinab, Zeile 2 |
     * | 150–230 | über die Südkante, Blick hinab in die Tiefe Richtung Dreiweg | Seelenschein in der Tiefe, Musik des Tartaros, Karte „Ende des ersten Akts“ |
     * | 230–300 | zurück in die Augen | Schwarzblende über den Schnitt; danach Kapitelkarte vom Server |
     *
     * Der Dreiweg selbst liegt 400 Blöcke südlich (außer Sicht, Briefing-Stolperfalle): Der Schwenk zeigt die Richtung
     * und die Tiefe, nicht den Ort.
     */
    private fun actEnd(): StageScript = stage(ACT_END, ACT_END_LENGTH) {
        val key = "${RpgStages.KEY}.act_end"
        val a = RpgStages.ACT_END_ANCHOR
        val zeus = a.at(OlympusLayout.THRONE.x + 0.5, OlympusLayout.F.floorY.toDouble(), OlympusLayout.THRONE.z + 4.5)
        val edge = OlympusLayout.F.radius.toDouble()
        shot(
            0, 80, cam = listOf(Key.At(zeus.f + 13.5, 3.5, 4.0), Key.At(zeus.f + 11.0, -3.0, 4.8)), look = listOf(Key.Puppet("zeus", 4.4)),
            fov = 52f to 48f, follow = 0.2f,
        )
        shot(
            80, 150, cam = listOf(Key.PuppetRel("zeus", -2.0, 2.2, 6.4), Key.PuppetRel("zeus", -1.4, 1.4, 6.8)),
            look = listOf(Key.At(24.0, 0.0, -2.0), Key.At(edge + 2.0, 0.0, -24.0)), fov = 60f to 64f, cut = true,
        )
        shot(
            150, 230, cam = listOf(Key.Last, Key.At(edge - 20.0, 0.0, 9.0), Key.At(edge, 1.0, 2.0)),
            look = listOf(Key.Last, Key.At(edge + 12.0, 0.0, -20.0), Key.At(edge + 42.0, 0.0, -60.0)), fov = 64f to 70f, follow = 0.15f,
        )
        backToEyes(230, ACT_END_LENGTH, color = 0x000000)
        mark(0, "start")
        puppet(0, "zeus", "hha:zeus", zeus, yaw = 0f)
        puppetAct(8, "zeus", ZeusAction.GAZE)
        caption(12, Cine.SPEAKER_ZEUS, "$key.zeus.1")
        puppetAct(84, "zeus", ZeusAction.CALL)
        caption(96, Cine.SPEAKER_ZEUS, "$key.zeus.2")
        music(150, "hha:music.tartaros")
        fx(150, "rpg.rune_glow", Vfx.Style.SOUL, Key.At(edge + 50.0, 0.0, -72.0), dir = Key.At(edge, 0.0, 2.0), a = 16f, life = 150)
        fx(156, "rpg.embers", Vfx.Style.SOUL, Key.At(edge + 4.0, 0.0, -6.0), a = 6f, b = 16f, c = 0.05f, life = 80)
        card(196, "$key.card", "$key.card.sub", 90)
        puppetRemove(228, "zeus")
        mark(ACT_END_LENGTH, "end")
    }

    // ---------------------------------------------------------------------------------------------
    // rpg.end
    // ---------------------------------------------------------------------------------------------

    /**
     * **Ende** (600 Ticks), nach Kronos. Anker = Spieler, Blick = sein Blick. Danach schickt `RpgScenes` den Abspann
     * (`RpgEventPayload(CREDITS)`, P16 zeigt die Rolle) und schließt den Epilog.
     * | Ticks | Kamera | Spuren |
     * | --- | --- | --- |
     * | 0–110 | weite, stille Einstellung über der Gruppe, langsam näher | Asche sinkt, Erzähler; ab 100 `ECLIPSE` (≤ 0,45): der Tartaros dunkelt ab |
     * | 110–200 | Nahaufnahme von unten | `LOOK_UP`; 160 Licht öffnet sich über der Gruppe; 180 Abspann-Musik |
     * | 200–280 | Bogen über der Gruppe im Licht | `FLOAT`; `ECLIPSE` blendet aus (bis 260): es wird hell |
     * | 280–440 | Aufstieg durch einen Tunnel aus Licht, der Blick hebt sich ins Licht | Lichttunnel, Kapitelkarte Epilog (330), Weißblende ab 400 |
     * | 440–600 | im Licht; ab 556 zurück in die Augen | Erzähler (452), Blende löst sich |
     */
    private fun end(): StageScript = stage(END, END_LENGTH) {
        val key = "${RpgStages.KEY}.end"
        shot(0, 110, cam = listOf(Key.At(-13.0, 7.0, 7.5), Key.At(-10.0, 5.0, 5.5)), look = listOf(Key.Group(0.9)), fov = 62f to 58f, ease = EaseKind.LINEAR, handheld = 0.3f)
        shot(
            110, 200, cam = listOf(Key.ActorRel(0, 2.2, 0.9, 1.25), Key.ActorRel(0, 1.9, -0.5, 1.15)), look = listOf(Key.Actor(0, 1.75)),
            fov = 48f to 44f, cut = true,
        )
        shot(200, 280, cam = listOf(Key.At(4.5, 3.5, 3.0), Key.At(4.0, -3.5, 3.6)), look = listOf(Key.Group(1.2)), fov = 60f to 64f, arc = true, cut = true)
        shot(
            280, 440, cam = listOf(Key.Last, Key.At(-0.8, 0.4, 9.0), Key.At(-0.2, 0.2, 30.0), Key.At(0.4, 0.1, 54.0)),
            look = listOf(Key.Group(1.0), Key.At(1.5, 0.0, 80.0)), fov = 64f to 84f, ease = EaseKind.IN, handheld = 0.2f,
        )
        shot(440, END_RETURN, cam = listOf(Key.Last), look = listOf(Key.Last), handheld = 0f)
        shot(
            END_RETURN, END_LENGTH, cam = listOf(Key.ActorRel(0, -2.2, 0.8, 2.1), Key.Eye), look = listOf(Key.Actor(0, 1.5), Key.EyeLook),
            ease = EaseKind.SMOOTH, handheld = 0f, cut = true,
        )

        mark(0, "start")
        music(0, "")
        fx(0, "rpg.embers", Vfx.Style.MOON, Key.Group(0.0), a = 8f, b = 24f, c = -0.03f, life = 260)
        caption(20, Cine.SPEAKER_NARRATOR, "$key.line.1")
        screen(END_ECLIPSE_AT, BossFx.Screen.ECLIPSE, Key.Group(1.0), radius = 40f, strength = END_ECLIPSE_STRENGTH, ticks = END_ECLIPSE_TICKS)

        pose(140, actor = -1, pose = Pose.LOOK_UP, ticks = 90)
        fx(160, "rpg.light_shaft", Vfx.Style.HEAVEN, Key.Group(0.0), a = 5.5f, b = 70f, c = 1f, life = 300)
        sound(160, "minecraft:block.beacon.activate", volume = 0.8f, pitch = 0.9f)
        music(END_MUSIC_AT, "hha:music.credits")

        pose(236, actor = -1, pose = Pose.FLOAT, ticks = 300)
        // Aufstieg ins Licht: Darstellung hebt die Gruppe (Wunsch requests/P17.md Punkt 1), vor der Rückkehr wieder unten.
        mark(280, RpgStages.liftMark(0.0, END_ASCENT, 160))
        mark(540, RpgStages.liftMark(END_ASCENT, 0.0, 1))
        fx(270, "rpg.light_tunnel", Vfx.Style.HEAVEN, Key.Group(6.0), a = 4.5f, b = 64f, c = 0.018f, life = 200)
        chapterCard(330, "epilogue", RpgStages.HEAVEN_COLOR)
        fade(END_WHITE_AT, RpgStages.LIGHT, 0f, 1f, 40)
        caption(452, Cine.SPEAKER_NARRATOR, "$key.line.2")
        pose(540, actor = -1, pose = Pose.NONE, ticks = 0)
        fade(END_RETURN, RpgStages.LIGHT, 1f, 0f, 40)
        mark(END_LENGTH, "end")
    }
}
