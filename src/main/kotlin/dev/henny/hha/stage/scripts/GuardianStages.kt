package dev.henny.hha.stage.scripts

import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.stage.EaseKind
import dev.henny.hha.stage.Key
import dev.henny.hha.stage.StageScripts
import dev.henny.hha.stage.stage

/**
 * Bühnen-Skripte der Tartaros-Wächter (Überarbeitung 3, P14, PLAN 2.11): je ein Auftritt (6–8 s) und eine gemeinsame
 * Niederlage (6 s). Gespielt über `DuelCinematics.staged` aus `TartarosGuardian` (`MiniBosses.kt`): Anker = Lage des
 * Wächters beim Start, Blick = sein `yaw`, Darsteller 0 = der Wächter (nur im Auftritt; die Niederlage hat keine
 * Darsteller, weil der Körper nach der Todesbewegung verschwindet, und rechnet alles relativ zum Anker).
 *
 * Was der Wächter selbst tut (Bewegung, Haltung, Ton), steht im Server-Drehbuch (`introInner`); die Tick-Zahlen hier
 * passen zu dessen Konstanten (`INTRO_*`). Die Erzählerzeile des Bestands (`hha.tartaros.scene.<glied>`) kommt vom
 * Server bei Tick 14. Jede Szene beginnt in den Augen des Spielers und endet dort.
 *
 * | Skript | Länge | Ablauf |
 * | --- | --- | --- |
 * | `warden.intro` | 150 | aus den Augen hoch vor ihn, Bogen um ihn herum, Nahe aufs Gesicht beim Brüllen (88), zurück |
 * | `sisyphus.intro` | 160 | hinter ihm am Fuß des Hangs, die Kamera schleicht mit, während er den Fels hinaufschiebt; oben von vorn beim Absetzen (132) |
 * | `ixion.intro` | 140 | weite Ansicht, flacher Bogen um das Rad, das ab 40 andreht und glüht; Nahe auf ihn |
 * | `tantalus.intro` | 140 | er kniet und greift nach dem Wasser (20), es weicht (50), Hitzeflimmern, er richtet sich brüllend auf (86) |
 * | `guardian.defeat` | 120 | halber Bogen um den Ort des Sieges; das Kettenglied glüht auf und springt bei 70 (Funken, `boss.chain`) |
 */
object GuardianStages {

    const val WARDEN = "warden.intro"
    const val SISYPHUS = "sisyphus.intro"
    const val IXION = "ixion.intro"
    const val TANTALUS = "tantalus.intro"
    const val DEFEAT = "guardian.defeat"

    /** Tick, an dem das Kettenglied der Niederlage springt. */
    const val LINK_BURST = 70

    /** Höhe des Kettenglieds über dem Ort des Sieges (über dem Kopf des fallenden Wächters). */
    const val LINK_UP = 4.8

    fun register() {
        StageScripts.register(warden())
        StageScripts.register(sisyphus())
        StageScripts.register(ixion())
        StageScripts.register(tantalus())
        StageScripts.register(defeat())
    }

    private fun warden() = stage(WARDEN, 150) {
        shot(0, 30, cam = listOf(Key.Eye, Key.ActorRel(0, 10.0, 4.0, 3.5)), look = listOf(Key.EyeLook, Key.Actor(0, 3.0)), fov = 70f to 62f)
        shot(30, 85, cam = listOf(Key.ActorRel(0, 10.0, 4.0, 3.5), Key.ActorRel(0, 7.0, -3.0, 1.8)), look = listOf(Key.Actor(0, 3.2)),
            fov = 62f to 54f, arc = true, follow = 0.2f)
        shot(85, 118, cam = listOf(Key.Last, Key.ActorRel(0, 5.5, -1.2, 2.6)), look = listOf(Key.Last, Key.Actor(0, 3.7)),
            fov = 54f to 50f, ease = EaseKind.OUT, handheld = 1.4f)
        shot(118, 150, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), handheld = 0f)

        sound(20, "minecraft:block.chain.step", volume = 0.8f, pitch = 0.6f)
        kick(90, pitch = 1.5f, roll = 0.4f, ticks = 7)
        sound(90, "hha:boss.thud", volume = 0.7f)
        mark(150, "end")
    }

    private fun sisyphus() = stage(SISYPHUS, 160) {
        shot(0, 25, cam = listOf(Key.Eye, Key.ActorRel(0, -5.0, 3.5, 2.2)), look = listOf(Key.EyeLook, Key.Actor(0, 2.8)), fov = 70f to 64f)
        // Er schiebt den Fels hinauf; die Punkte hängen an ihm, die Kamera schleicht also mit.
        shot(25, 110,
            cam = listOf(Key.ActorRel(0, -5.0, 3.5, 2.2), Key.ActorRel(0, -2.0, 5.5, 2.8), Key.ActorRel(0, 1.5, 5.0, 3.4)),
            look = listOf(Key.Actor(0, 3.0)), fov = 64f to 60f, ease = EaseKind.LINEAR, follow = 0.25f)
        shot(110, 135, cam = listOf(Key.Last, Key.ActorRel(0, 6.5, -2.0, 2.4)), look = listOf(Key.Last, Key.Actor(0, 3.2)),
            fov = 60f to 54f, ease = EaseKind.SMOOTH)
        shot(135, 160, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), handheld = 0f)

        sound(30, "minecraft:block.grindstone.use", volume = 0.7f, pitch = 0.5f)
        sound(70, "minecraft:block.grindstone.use", volume = 0.7f, pitch = 0.45f)
        kick(132, pitch = 1.2f, ticks = 7)
        sound(132, "hha:boss.thud", volume = 0.8f)
        fx(132, "core.sparks", Vfx.Style.FIRE, Key.ActorRel(0, 0.0, 0.0, 0.3), dir = Key.ActorRel(0, 0.0, 0.0, 3.0), a = 6f, b = 0.3f, life = 16)
        mark(160, "end")
    }

    private fun ixion() = stage(IXION, 140) {
        shot(0, 30, cam = listOf(Key.Eye, Key.ActorRel(0, 9.0, 3.0, 2.0)), look = listOf(Key.EyeLook, Key.Actor(0, 2.0)), fov = 70f to 66f)
        shot(30, 95, cam = listOf(Key.ActorRel(0, 9.0, 3.0, 2.0), Key.ActorRel(0, 6.0, -4.5, 1.0)), look = listOf(Key.Actor(0, 1.9)),
            fov = 66f to 56f, arc = true, follow = 0.2f)
        shot(95, 118, cam = listOf(Key.Last, Key.ActorRel(0, 4.5, -1.0, 2.4)), look = listOf(Key.Last, Key.Actor(0, 2.2)),
            fov = 56f to 52f, ease = EaseKind.OUT, handheld = 1.3f)
        shot(118, 140, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), handheld = 0f)

        sound(42, "minecraft:entity.blaze.burn", volume = 0.8f, pitch = 0.7f)
        fx(62, "core.sparks", Vfx.Style.FIRE, Key.ActorRel(0, 0.0, 0.0, 1.8), dir = Key.ActorRel(0, 0.0, 0.0, 4.0), a = 10f, b = 0.4f, life = 18)
        fx(84, "core.sparks", Vfx.Style.FIRE, Key.ActorRel(0, 0.0, 0.0, 1.8), dir = Key.ActorRel(0, 0.0, 0.0, 4.0), a = 8f, b = 0.35f, life = 18)
        kick(96, pitch = 0.8f, ticks = 6)
        mark(140, "end")
    }

    private fun tantalus() = stage(TANTALUS, 140) {
        shot(0, 30, cam = listOf(Key.Eye, Key.ActorRel(0, 8.0, 3.0, 3.0)), look = listOf(Key.EyeLook, Key.Actor(0, 2.6)), fov = 70f to 64f)
        shot(30, 80, cam = listOf(Key.ActorRel(0, 8.0, 3.0, 3.0), Key.ActorRel(0, 5.5, -3.5, 1.3)), look = listOf(Key.Actor(0, 1.4)),
            fov = 64f to 56f, arc = true, follow = 0.2f)
        shot(80, 112, cam = listOf(Key.Last, Key.ActorRel(0, 6.5, -1.0, 3.6)), look = listOf(Key.Last, Key.Actor(0, 3.2)),
            fov = 56f to 60f, ease = EaseKind.OUT, handheld = 1.4f)
        shot(112, 140, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), handheld = 0f)

        sound(50, "minecraft:block.water.ambient", volume = 0.9f, pitch = 0.8f)
        screen(56, BossFx.Screen.HEAT_HAZE, Key.ActorRel(0, 0.0, 0.0, 1.5), radius = 9f, strength = 0.2f, ticks = 50)
        kick(90, pitch = 1.2f, ticks = 7)
        mark(140, "end")
    }

    private fun defeat() = stage(DEFEAT, 120) {
        shot(0, 40, cam = listOf(Key.Eye, Key.At(7.5, 3.5, 3.5)), look = listOf(Key.EyeLook, Key.At(0.0, 0.0, LINK_UP - 1.2)), fov = 70f to 60f)
        shot(40, 96, cam = listOf(Key.At(7.5, 3.5, 3.5), Key.At(-6.0, -6.0, 3.0)), look = listOf(Key.At(0.0, 0.0, LINK_UP - 0.8)),
            fov = 60f to 56f, arc = true)
        shot(96, 120, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), handheld = 0f)

        val link = Key.At(0.0, 0.0, LINK_UP)
        fx(6, "warden.link", Vfx.Style.GOLD, link, a = (LINK_BURST - 6).toFloat(), life = 100)
        fx(LINK_BURST, "core.sparks", Vfx.Style.GOLD, link, dir = Key.At(0.0, 0.0, LINK_UP + 3.0), a = 14f, b = 0.5f, life = 22)
        sound(LINK_BURST, "hha:boss.chain", volume = 1f, pitch = 0.8f)
        sound(LINK_BURST, "hha:boss.thud", volume = 0.6f)
        kick(LINK_BURST, pitch = 1.5f, roll = 0.5f, ticks = 7)
        mark(LINK_BURST, "burst")
        mark(120, "end")
    }
}
