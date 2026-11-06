package dev.henny.hha.logic.boss

import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.HermesEntity
import dev.henny.hha.entity.boss.HermesPose
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.world.HadesGate
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d

/**
 * Das Drehbuch zum Kampf gegen den Schatten des Boten in der Halle unter dem Hades-Biom.
 *
 * Hermes hat den schwarzen Brief verbrannt. Die Asche hatte Hände: Was daraus aufstand, sieht aus wie er, arbeitet aber
 * für Hades. Alle Effekte des Kampfes werden über [Vfx.styleOverride] grün, deshalb steht hier überall der Originalstil.
 */
class ShadeCutscenes(private val fight: HermesFight) {

    private val world get() = fight.world
    private val center get() = fight.center
    private val players get() = fight.players()
    private val boss: HermesEntity get() = fight.boss!!

    private suspend fun SequenceScope<Unit>.wait(ticks: Int) {
        repeat(ticks) { yield(Unit) }
    }

    private suspend fun SequenceScope<Unit>.say(speaker: Int, key: String, ticks: Int = 70) {
        val full = "hha.hermes.line.shade.$key"
        val length = HhaVoiceLengths.ticks(full, ticks)
        Cine.dialog(players, speaker, full, length)
        wait(length + 4)
    }

    private suspend fun SequenceScope<Unit>.glide(to: Vec3d, ticks: Int) {
        val from = boss.entityPos
        for (i in 1..ticks) {
            val raw = i.toFloat() / ticks
            boss.setPosition(from.lerp(to, (raw * raw * (3f - 2f * raw)).toDouble()))
            yield(Unit)
        }
    }

    private fun sound(event: SoundEvent, volume: Float = 2f, pitch: Float = 1f, at: Vec3d = center) {
        world.playSound(null, at.x, at.y, at.z, event, SoundCategory.HOSTILE, volume, pitch)
    }

    private fun faceBossToPlayers() {
        val target = fight.targets().firstOrNull() ?: return
        fight.face(boss, target.entityPos)
    }

    /** Der Obolus fällt, die Flammen wachsen, und der Schatten landet. */
    fun prologue(): Sequence<Unit> = sequence {
        val everyone = players
        Cine.start(everyone)
        Cine.fade(everyone, 0x000000, 1.0, 0.0, 30)
        fight.setMusic(null)
        fight.arena.build(0)
        fight.arenaBuilt = true
        fight.gatherPlayers()

        Cine.orbit(everyone, center, 22.0, 9.0, 20.0, 80.0, 150, 66.0, 60.0, null, 0.0, 18.0, 6.0)
        sound(HhaSounds.CHIME, 2f, 0.5f)
        wait(10)
        say(Cine.SPEAKER_NARRATOR, "coin", 74)
        for (i in 0 until 8) {
            val angle = i * Math.PI / 4.0 + Math.PI / 8.0
            val at = center.add(kotlin.math.cos(angle) * 28.5, 8.0, kotlin.math.sin(angle) * 28.5)
            Vfx.pillar(world, Vfx.Style.SOUL, at, 1.2f, 16f, 40)
            sound(HhaSounds.SOUL, 1.2f, 0.7f + i * 0.04f, at)
            wait(5)
        }

        // Der Schatten stürzt durch die Kuppel.
        fight.spawnBoss(center.add(0.0, 30.0, 0.0)).apply { pose = HermesPose.DASH }
        Cine.camera(everyone, center.add(13.0, 2.0, 13.0), center.add(10.0, 1.0, 10.0), center.add(0.0, 26.0, 0.0), center.add(0.0, 1.0, 0.0), 26, 72.0, 58.0)
        Vfx.trail(world, Vfx.Style.GOLD, boss, 0.9f, 30)
        sound(HhaSounds.DASH, 3f, 0.6f, center.add(0.0, 26.0, 0.0))
        glide(center.add(0.0, 0.2, 0.0), 16)
        boss.pose = HermesPose.SLAM
        Vfx.shockwave(world, Vfx.Style.GOLD, center, 22f, 26, 5f)
        Vfx.nova(world, Vfx.Style.GOLD, center.add(0.0, 1.0, 0.0), 8f, 16)
        Vfx.feathers(world, Vfx.Style.SHADOW, center.add(0.0, 1.0, 0.0), 40, 2.4f, 80)
        Vfx.shake(world, center, 7f, 26, 60f)
        sound(HhaSounds.IMPACT, 4f, 0.6f)
        wait(24)

        faceBossToPlayers()
        boss.pose = HermesPose.TAUNT
        Cine.orbit(everyone, center, 4.2, 1.5, 75.0, 110.0, 200, 50.0, 44.0, boss, 2.3)
        say(Cine.SPEAKER_SHADE, "who", 66)
        say(Cine.SPEAKER_SHADE, "ash", 84)
        boss.pose = HermesPose.READ
        Cine.orbit(everyone, center, 3.2, 2.2, 110.0, 135.0, 150, 46.0, 40.0, boss, 2.6)
        say(Cine.SPEAKER_SHADE, "contract", 78)
        boss.pose = HermesPose.CAST
        Cine.camera(everyone, boss.entityPos.add(0.0, 2.6, 3.4), boss.entityPos.add(0.0, 2.4, 2.4), boss.entityPos, boss.entityPos, 60, 44.0, 36.0, boss, 2.9)
        say(Cine.SPEAKER_SHADE, "stay", 50)

        Cine.title(everyone, "hha.shade.stage.2", "hha.shade.stage.2.sub", 60)
        sound(HhaSounds.STING, 2f, 0.8f)
        Cine.end(players)
        fight.setMusic(HhaSounds.THEME_SHADE)
        boss.pose = HermesPose.IDLE
        glide(center.add(0.0, 4.0, 0.0), 12)
        fight.startStage(2)
    }

    /** Zweite Phase: Er ruft die Schlangen. */
    fun rising(): Sequence<Unit> = sequence {
        val everyone = players
        Cine.start(everyone)
        Cine.fade(everyone, 0x061006, 1.0, 0.0, 14)
        fight.gatherPlayers()
        boss.setPosition(center.add(0.0, 0.2, 0.0))
        faceBossToPlayers()
        boss.pose = HermesPose.STAGGER
        sound(HhaSounds.IMPACT, 2.5f, 0.7f)
        Cine.orbit(everyone, boss.entityPos, 4.0, 1.2, 40.0, 70.0, 120, 48.0, 44.0, boss, 2.4)
        say(Cine.SPEAKER_SHADE, "wings", 80)
        boss.pose = HermesPose.CAST
        Vfx.shockwave(world, Vfx.Style.SOUL, center, 26f, 34, 6f)
        Vfx.shake(world, center, 5f, 30, 60f)
        sound(HhaSounds.ROAR, 3f, 0.8f)
        say(Cine.SPEAKER_SHADE, "snakes", 62)
        Cine.title(everyone, "hha.shade.stage.3", "hha.shade.stage.3.sub", 60)
        sound(HhaSounds.STING, 2f, 0.72f)
        Cine.end(players)
        boss.pose = HermesPose.IDLE
        fight.startStage(3)
    }

    /** Letzte Phase: Der Boden selbst spricht, und der Schatten wird unberührbar. */
    fun last(): Sequence<Unit> = sequence {
        val everyone = players
        Cine.start(everyone)
        Cine.fade(everyone, 0x000000, 1.0, 0.0, 20)
        fight.gatherPlayers()
        boss.setPosition(center.add(0.0, 0.1, 0.0))
        faceBossToPlayers()
        boss.stage = 4
        boss.pose = HermesPose.KNEEL
        Cine.orbit(everyone, center, 15.0, 4.0, 200.0, 240.0, 120, 66.0, 60.0, boss, 1.0)
        Vfx.shake(world, center, 3f, 90, 60f)
        sound(HhaSounds.RIFT, 3f, 0.55f)
        wait(16)
        say(Cine.SPEAKER_NARRATOR, "voice", 74)
        boss.pose = HermesPose.ASCEND
        Vfx.pillar(world, Vfx.Style.GOLD, center, 2.6f, 30f, 50)
        Vfx.orbitRings(world, Vfx.Style.GOLD, boss, 2f, 20 * 900)
        Cine.orbit(everyone, center, 11.0, 1.0, 240.0, 280.0, 70, 60.0, 72.0, boss, 2.0)
        glide(center.add(0.0, 10.0, 0.0), 50)
        say(Cine.SPEAKER_SHADE, "breath", 56)
        Cine.title(everyone, "hha.shade.stage.4", "hha.shade.stage.4.sub", 60)
        sound(HhaSounds.STING, 3f, 0.8f)
        Cine.end(players)
        boss.pose = HermesPose.IDLE
        boss.vulnerable = false
        fight.startStage(4)
    }

    /** Sieg: Der Schatten zerfällt und das schwarze Tor füllt sich mit grünem Licht. */
    fun finale(): Sequence<Unit> = sequence {
        val everyone = players
        fight.markFinale()
        Cine.clearVfx(everyone)
        Cine.start(everyone)
        Cine.fade(everyone, 0x061006, 1.0, 0.0, 26)
        fight.gatherPlayers()
        fight.setMusic(null)
        boss.setPosition(center.add(0.0, 0.1, 0.0))
        faceBossToPlayers()
        boss.pose = HermesPose.KNEEL
        sound(HhaSounds.IMPACT, 3f, 0.8f)
        Cine.orbit(everyone, center, 3.6, 0.8, 80.0, 105.0, 200, 46.0, 40.0, boss, 1.3)
        wait(14)
        say(Cine.SPEAKER_SHADE, "contract_end", 56)
        say(Cine.SPEAKER_SHADE, "gate", 80)
        say(Cine.SPEAKER_SHADE, "warning", 74)

        // Er zerfällt zu Asche und schwarzen Federn.
        Vfx.feathers(world, Vfx.Style.SHADOW, boss.entityPos.add(0.0, 1.5, 0.0), 70, 3f, 100)
        Vfx.nova(world, Vfx.Style.GOLD, boss.entityPos.add(0.0, 1.5, 0.0), 7f, 16)
        sound(HhaSounds.SOUL, 2f, 0.6f)
        boss.discard()
        wait(20)

        // Das Tor öffnet sich Reihe für Reihe.
        val hall = BlockPos(fight.arena.center)
        val gate = center.add(0.0, 4.0, HadesGate.GATE_OFFSET_Z.toDouble())
        Cine.camera(everyone, gate.add(1.0, 0.0, 24.0), gate.add(0.0, 0.0, 11.0), gate, gate, 110, 66.0, 54.0)
        Vfx.shake(world, gate, 5f, 80, 60f)
        sound(HhaSounds.RIFT, 3.5f, 0.9f, gate)
        for (row in 0 until HadesGate.GATE_HEIGHT) {
            HadesGate.openRow(world, hall, row)
            Vfx.flare(world, Vfx.Style.GOLD, gate.add(0.0, row - 4.0, 0.0), 4f, 10)
            sound(HhaSounds.IMPACT, 1.6f, 1.3f - row * 0.05f, gate)
            wait(7)
        }
        Vfx.godRays(world, Vfx.Style.GOLD, gate, 9f, 18f, 90)
        say(Cine.SPEAKER_NARRATOR, "open", 74)
        Cine.title(everyone, "hha.shade.title.open", "hha.shade.title.open.sub", 70)
        sound(HhaSounds.STING, 2.5f, 0.75f)
        fight.grantShadeRewards()
        wait(40)
        Cine.end(players)
        fight.finish(success = true)
    }
}
