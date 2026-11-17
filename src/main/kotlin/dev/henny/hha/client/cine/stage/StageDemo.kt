package dev.henny.hha.client.cine.stage

import dev.henny.hha.entity.boss.HermesPose
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.stage.EaseKind
import dev.henny.hha.stage.Key
import dev.henny.hha.stage.Pose
import dev.henny.hha.stage.StageScript
import dev.henny.hha.stage.StageScripts
import dev.henny.hha.stage.stage

/**
 * Vorführ-Skript `stage.demo` (P05-Abnahme): nutzt **jede Spur einmal** und jede Fahrt-Art (fest, Gerade, Bogen,
 * Catmull-Rom, Schnitt, Rückfahrt in die Augen). Anker = Spieler, Darsteller 0 = Spieler (`/hha stage play stage.demo`).
 *
 * Registriert am Client aus `StagePlayer.init()`; im Einzelspieler teilt sich der eingebaute Server dieselbe
 * `StageScripts`-Tabelle, deshalb kennt `/hha stage play` das Skript dort. Auf einem dedizierten Server fehlt es
 * (gewollt: ein Vorführstück, keine Spielszene).
 *
 * Zeitleiste (Ticks, 20 = 1 s):
 * | Ticks | Kamera | Spuren |
 * | --- | --- | --- |
 * | 0–40 | aus den Augen (`Last`) auf eine Halbnahe vor dem Spieler | Blende aus Schwarz, Musik, Kostüm an, Hermes-Puppe |
 * | 40–120 | Bogen um den Spieler, FOV 70 → 58 | Pose `LOOK_HANDS`, Zeile 1 (Erzähler) |
 * | 120–200 | Catmull-Rom über drei Punkte relativ zur Puppe, Blick folgt ihr | Puppe läuft mit Sprung, Aktion, Zeile 2 |
 * | 200–260 | **Schnitt** auf eine Weite, starke Handkamera | Blitz, Ruck, Ton, Funken, Stoßwelle, Rütteln, Auflösen, Titel |
 * | 260–310 | Gerade vom letzten Bild nach hinten oben | Spieler-Puppe mit Kostüm und Jubel, Kapitelkarte, Hermes geht |
 * | 310–360 | zurück in die Augen | Musik aus, Puppe weg, Marke `end` |
 */
object StageDemo {

    const val ID = "stage.demo"
    const val LENGTH = 360

    private const val KEY = "hha.stage.demo"

    fun register() {
        if (StageScripts.get(ID) != null) return
        StageScripts.register(build())
    }

    fun build(): StageScript = stage(ID, LENGTH) {
        // --- Kamera -----------------------------------------------------------------------------
        shot(0, 40, cam = listOf(Key.Last, Key.At(4.0, 1.5, 2.2)), look = listOf(Key.Last, Key.Actor(0, 1.5)), fov = 70f to 70f)
        shot(40, 120, cam = listOf(Key.At(4.0, 1.5, 2.2), Key.At(-1.0, 4.5, 2.6)), look = listOf(Key.Actor(0, 1.4)),
            fov = 70f to 58f, arc = true)
        shot(120, 200,
            cam = listOf(Key.PuppetRel("hermes", 4.0, 3.0, 2.0), Key.PuppetRel("hermes", 3.5, -1.0, 1.4), Key.PuppetRel("hermes", 2.5, -3.5, 2.4)),
            look = listOf(Key.Puppet("hermes", 1.9)), fov = 58f to 52f, ease = EaseKind.LINEAR, follow = 0.15f)
        shot(200, 260, cam = listOf(Key.At(-2.0, 7.0, 3.2)), look = listOf(Key.Group(1.2)), fov = 64f to 64f, cut = true, handheld = 2f)
        shot(260, 310, cam = listOf(Key.Last, Key.At(-8.0, -2.0, 6.5)), look = listOf(Key.Last, Key.Actor(0, 1.0)),
            fov = 64f to 72f, ease = EaseKind.OUT)
        shot(310, 360, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), ease = EaseKind.SMOOTH, handheld = 0f)

        // --- Spuren -----------------------------------------------------------------------------
        mark(0, "start")
        fade(0, 0x000000, 1f, 0f, 20)
        music(0, "hha:music.olympus")
        costume(0, on = true)
        puppet(10, "hermes", "hha:hermes", Key.At(6.0, -2.0, 0.0), yaw = 180f)
        pose(40, actor = 0, pose = Pose.LOOK_HANDS, ticks = 80)
        caption(50, Cine.SPEAKER_NARRATOR, "$KEY.line1")

        puppetMove(120, 190, "hermes", listOf(Key.At(5.0, 1.0, 0.0), Key.At(3.0, 3.0, 0.0)), lift = 0.6)
        puppetAct(150, "hermes", HermesPose.CAST)
        caption(130, Cine.SPEAKER_HERMES, "$KEY.line2", nameKey = "$KEY.name", color = 0xFFD75A)

        flash(200, 0xFFFFFF, 0.2f, 8)
        kick(200, pitch = 2f, roll = 0.6f, ticks = 6)
        sound(200, "hha:boss.thud", volume = 0.9f)
        pose(200, actor = -1, pose = Pose.ARMS_WIDE, ticks = 50)
        fx(202, "core.sparks", Vfx.Style.HEAVEN, Key.Actor(0, 1.0), dir = Key.Actor(0, 3.0), a = 10f, b = 0.4f, life = 20)
        screen(205, BossFx.Screen.SHOCK_RIPPLE, Key.Actor(0, 0.2), radius = 6f, strength = 0.4f, ticks = 10)
        shake(210, 230, 1.5f)
        costume(215, on = false, dissolve = 30)
        card(232, "$KEY.title", "$KEY.sub", 60)

        puppet(262, "pilgrim", "minecraft:player", Key.At(3.0, -3.0, 0.0), yaw = 180f, costume = "hell")
        puppetAct(270, "pilgrim", Pose.CHEER.ordinal)
        chapter(270, "$KEY.chapter.kicker", "$KEY.chapter.title", "$KEY.chapter.sub", 80, 0x3FD8FF)
        puppetRemove(300, "hermes")
        caption(318, Cine.SPEAKER_NARRATOR, "$KEY.line3")

        music(345, "")
        puppetRemove(350, "pilgrim")
        mark(LENGTH, "end")
    }
}
