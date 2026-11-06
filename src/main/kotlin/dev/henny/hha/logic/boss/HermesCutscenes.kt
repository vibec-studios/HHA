package dev.henny.hha.logic.boss

import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaParticles
import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.DivineParcelEntity
import dev.henny.hha.entity.boss.HermesEntity
import dev.henny.hha.entity.boss.HermesPose
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin

/**
 * Das Drehbuch: Jede Funktion ist eine Szene, die Tick für Tick abläuft. `wait` wartet, `say` zeigt eine
 * Dialogzeile an und wartet, bis sie gelesen ist. Die Texte stehen in den Sprachdateien unter `hha.hermes.line.*`.
 */
class HermesCutscenes(private val fight: HermesFight) {

    private val world get() = fight.world
    private val center get() = fight.center
    private val players get() = fight.players()
    private val boss: HermesEntity get() = fight.boss!!

    private suspend fun SequenceScope<Unit>.wait(ticks: Int) {
        repeat(ticks) { yield(Unit) }
    }

    private suspend fun SequenceScope<Unit>.say(speaker: Int, key: String, ticks: Int = 70) {
        val full = "hha.hermes.line.$key"
        val length = HhaVoiceLengths.ticks(full, ticks)
        Cine.dialog(players, speaker, full, length)
        wait(length + 4)
    }

    /** Bewegt ein Wesen in [ticks] weich von der aktuellen Position nach [to]. */
    private suspend fun SequenceScope<Unit>.glide(to: Vec3d, ticks: Int, smooth: Boolean = true) {
        val from = boss.entityPos
        for (i in 1..ticks) {
            val raw = i.toFloat() / ticks
            val t = if (smooth) raw * raw * (3f - 2f * raw) else raw
            boss.setPosition(from.lerp(to, t.toDouble()))
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

    /** Ungefähre Position des Briefs in Hermes' linker Hand. */
    private fun letterPosition(): Vec3d {
        val rad = Math.toRadians(boss.yaw.toDouble())
        val forward = Vec3d(-sin(rad), 0.0, cos(rad))
        val left = Vec3d(cos(rad), 0.0, sin(rad))
        return boss.entityPos.add(forward.multiply(0.62)).add(left.multiply(0.5)).add(0.0, 2.0, 0.0)
    }

    private fun shoulderPoint(radius: Double, angle: Double, height: Double): Vec3d =
        boss.entityPos.add(cos(Math.toRadians(angle)) * radius, height, sin(Math.toRadians(angle)) * radius)

    // ---------------------------------------------------------------------------------------------
    // Prolog: Der Brief
    // ---------------------------------------------------------------------------------------------

    fun prologue(): Sequence<Unit> = sequence {
        val origin = fight.origin
        val summoner = fight.targets().firstOrNull()
        val everyone = players
        Cine.start(everyone)
        Cine.fade(everyone, 0x000000, 1.0, 0.0, 20)
        fight.setMusic(null)

        // Der Brief verbrennt am Boden, der Himmel öffnet sich.
        Cine.orbit(everyone, origin, 7.0, 2.4, 30.0, 95.0, 110, 62.0, 56.0, summoner, 1.2, 4.5, 1.6)
        Vfx.flare(world, Vfx.Style.GOLD, origin.add(0.0, 1.3, 0.0), 2.4f, 18)
        Vfx.feathers(world, Vfx.Style.HEAVEN, origin.add(0.0, 1.5, 0.0), 24, 1.4f, 90)
        sound(HhaSounds.CHIME, 2f, 0.8f, origin)
        wait(10)
        say(Cine.SPEAKER_NARRATOR, if (fight.mode == HermesFight.Mode.TEMPLE) "prologue.altar" else "prologue.seal", 56)
        Vfx.rift(world, Vfx.Style.GOLD, origin.add(0.0, 26.0, 0.0), Vec3d(0.0, -1.0, 0.0), 13f, 120)
        Vfx.skyMood(world, Vfx.Style.STORM, origin, 1f, 130, 64f)
        Vfx.clouds(world, Vfx.Style.STORM, origin, 60f, 30f, 150)
        fight.lightning(origin.add(18.0, 0.0, -12.0))
        Vfx.shake(world, origin, 2.5f, 60, 40f)
        sound(HhaSounds.RIFT, 3f, 0.8f, origin)

        // Der Himmel saugt uns hinauf — Schnitt von unten.
        Cine.camera(
            everyone, origin.add(5.0, 0.6, 5.0), origin.add(1.5, 16.0, 1.5),
            origin.add(0.0, 26.0, 0.0), origin.add(0.0, 26.0, 0.0), 70, 66.0, 96.0,
        )
        say(Cine.SPEAKER_NARRATOR, "prologue.pull", 44)
        Vfx.pillar(world, Vfx.Style.GOLD, origin, 4.5f, 40f, 40)
        Cine.fade(everyone, 0xFFF6D8, 0.0, 1.0, 18)
        wait(20)

        // Während das Bild weiß ist: Forum bauen und alle hinauf.
        fight.arena.build(1)
        fight.arenaBuilt = true
        Cine.atmosphere(everyone, Vfx.Style.GOLD, center, 1)
        fight.gatherPlayers()
        fight.spawnBoss(center.add(0.0, 52.0, 0.0)).apply { pose = HermesPose.DASH }
        fight.setMusic(HhaSounds.THEME_MESSENGER)
        wait(8)

        // Totale über dem Forum.
        Cine.fade(everyone, 0xFFF6D8, 1.0, 0.0, 24)
        Cine.orbit(everyone, center, 40.0, 16.0, 200.0, 240.0, 62, 72.0, 60.0, null, 0.0, 30.0, 8.0)
        Cine.title(everyone, "hha.hermes.title.forum", "hha.hermes.title.forum.sub", 56)
        Vfx.godRays(world, Vfx.Style.GOLD, center, 26f, 80f, 120)
        wait(58)

        // Hermes stürzt wie ein Komet aufs Forum.
        Cine.camera(everyone, center.add(12.0, 2.0, 12.0), center.add(9.0, 1.0, 9.0), center.add(0.0, 30.0, 0.0), center.add(0.0, 1.0, 0.0), 30, 72.0, 58.0)
        Vfx.trail(world, Vfx.Style.GOLD, boss, 0.9f, 40)
        sound(HhaSounds.DASH, 3f, 0.7f, center.add(0.0, 30.0, 0.0))
        glide(center.add(0.0, 0.2, 0.0), 18, smooth = false)
        boss.pose = HermesPose.SLAM
        Vfx.shockwave(world, Vfx.Style.GOLD, center, 22f, 26, 5f)
        Vfx.nova(world, Vfx.Style.GOLD, center.add(0.0, 1.0, 0.0), 8f, 16)
        Vfx.bolt(world, Vfx.Style.STORM, center.add(2.0, 40.0, -3.0), center.add(0.0, 0.5, 0.0), 0.9f, 10)
        Vfx.feathers(world, Vfx.Style.HEAVEN, center.add(0.0, 1.0, 0.0), 40, 2.4f, 80)
        Vfx.shake(world, center, 7f, 26, 60f)
        sound(HhaSounds.IMPACT, 4f, 0.8f)
        wait(26)

        // Er richtet sich auf und liest die Zustellung vor.
        faceBossToPlayers()
        boss.pose = HermesPose.READ
        Cine.orbit(everyone, center, 3.8, 1.6, 75.0, 105.0, 200, 52.0, 48.0, boss, 2.3, 3.4, 1.8)
        say(Cine.SPEAKER_HERMES, "prologue.delivery", 54)
        say(Cine.SPEAKER_HERMES, "prologue.sender", 84)
        Cine.orbit(everyone, center, 3.0, 2.3, 110.0, 130.0, 80, 46.0, 40.0, boss, 2.6)
        say(Cine.SPEAKER_HERMES, "prologue.condition", 74)
        boss.pose = HermesPose.TAUNT
        Cine.orbit(everyone, center, 5.5, 1.2, 150.0, 190.0, 120, 58.0, 54.0, boss, 2.0)
        say(Cine.SPEAKER_HERMES, "prologue.apollo", 104)
        boss.pose = HermesPose.CAST
        Cine.camera(everyone, boss.entityPos.add(0.0, 2.6, 3.4), boss.entityPos.add(0.0, 2.4, 2.4), boss.entityPos, boss.entityPos, 50, 44.0, 36.0, boss, 2.9)
        say(Cine.SPEAKER_HERMES, "prologue.catch", 46)

        // Los geht's: Titelkarte, Kamera zurück zum Spieler.
        Cine.title(everyone, "hha.hermes.stage.1", "hha.hermes.stage.1.sub", 60)
        sound(HhaSounds.STING, 2f, 1f)
        Vfx.nova(world, Vfx.Style.GOLD, boss.entityPos.add(0.0, 1.5, 0.0), 6f, 12)
        Cine.end(players)
        boss.pose = HermesPose.IDLE
        glide(center.add(0.0, 4.0, 0.0), 12)
        fight.startStage(1)
    }

    // ---------------------------------------------------------------------------------------------
    // Stage 1 → 2: Die Feder
    // ---------------------------------------------------------------------------------------------

    fun feather(): Sequence<Unit> = sequence {
        val everyone = players
        Cine.start(everyone)
        Cine.fade(everyone, 0xFFFFFF, 0.8, 0.0, 12)
        fight.gatherPlayers()
        boss.setPosition(center.add(0.0, 2.2, 0.0))
        faceBossToPlayers()
        boss.pose = HermesPose.STAGGER
        fight.setMusic(null)
        sound(HhaSounds.IMPACT, 2f, 1.5f)

        // Nahaufnahme: eine einzelne Feder löst sich und sinkt.
        val shin = boss.entityPos.add(0.3, 0.5, 0.0)
        Vfx.fallingFeather(world, Vfx.Style.HEAVEN, shin, 2.4f, 120)
        Cine.camera(everyone, shin.add(1.8, 0.6, 1.8), shin.add(1.3, -1.6, 1.3), shin, shin.add(0.0, -2.4, 0.0), 120, 34.0, 28.0)
        say(Cine.SPEAKER_NARRATOR, "feather.falls", 110)

        boss.pose = HermesPose.IDLE
        Cine.orbit(everyone, boss.entityPos, 3.4, 0.6, 60.0, 80.0, 70, 46.0, 42.0, boss, 2.7)
        say(Cine.SPEAKER_HERMES, "feather.moment", 30)
        say(Cine.SPEAKER_HERMES, "feather.touched", 80)

        boss.pose = HermesPose.TAUNT
        sound(HhaSounds.CHIME, 2f, 1.6f)
        Cine.orbit(everyone, boss.entityPos, 4.6, 0.3, 80.0, 120.0, 80, 58.0, 52.0, boss, 2.2)
        say(Cine.SPEAKER_HERMES, "feather.other_job", 70)

        boss.pose = HermesPose.SNAP
        Cine.orbit(everyone, boss.entityPos, 7.0, 1.6, 120.0, 160.0, 96, 62.0, 66.0, boss, 1.6)
        say(Cine.SPEAKER_HERMES, "feather.thief", 76)

        // Fingerschnipp: Trugbilder klappen aus ihm heraus.
        sound(HhaSounds.BLINK, 3f, 0.9f)
        Vfx.nova(world, Vfx.Style.HEAVEN, boss.entityPos.add(0.0, 1.6, 0.0), 7f, 14)
        Cine.atmosphere(everyone, Vfx.Style.HEAVEN, center, 60)
        val ghosts = ArrayList<HermesEntity>()
        for (i in 0 until 4) {
            val ghost = HhaEntities.HERMES.create(world, SpawnReason.EVENT)!!
            ghost.isIllusion = true
            ghost.setPosition(boss.entityPos)
            ghost.yaw = boss.yaw
            world.spawnEntity(ghost)
            ghosts.add(ghost)
        }
        for (step in 1..24) {
            val t = step / 24.0
            for ((i, ghost) in ghosts.withIndex()) {
                val a = i * Math.PI / 2.0 + t * 1.2
                ghost.setPosition(boss.entityPos.add(cos(a) * 7.0 * t, 0.0, sin(a) * 7.0 * t))
            }
            yield(Unit)
        }
        wait(16)
        for (ghost in ghosts) ghost.discard()
        Vfx.feathers(world, Vfx.Style.HEAVEN, boss.entityPos.add(0.0, 1.5, 0.0), 30, 2f, 50)

        Cine.title(everyone, "hha.hermes.stage.2", "hha.hermes.stage.2.sub", 60)
        sound(HhaSounds.STING, 2f, 1.12f)
        Cine.end(players)
        fight.setMusic(HhaSounds.THEME_THIEF)
        boss.pose = HermesPose.IDLE
        fight.startStage(2)
    }

    // ---------------------------------------------------------------------------------------------
    // Stage 2 → 3: Der Abstieg
    // ---------------------------------------------------------------------------------------------

    fun descent(): Sequence<Unit> = sequence {
        val everyone = players
        Cine.start(everyone)
        Cine.fade(everyone, 0x000000, 1.0, 0.0, 16)
        fight.gatherPlayers()
        boss.setPosition(center.add(0.0, 0.1, 0.0))
        faceBossToPlayers()
        boss.pose = HermesPose.IDLE
        fight.setMusic(null)

        Cine.camera(everyone, boss.entityPos.add(0.0, 2.2, 5.0), boss.entityPos.add(0.0, 2.5, 3.0), boss.entityPos, boss.entityPos, 60, 46.0, 38.0, boss, 2.9)
        wait(10)
        say(Cine.SPEAKER_HERMES, "descent.enough", 44)
        Cine.orbit(everyone, boss.entityPos, 3.4, 1.0, 100.0, 70.0, 150, 44.0, 40.0, boss, 2.6)
        say(Cine.SPEAKER_HERMES, "descent.dead_know", 76)
        say(Cine.SPEAKER_HERMES, "descent.every_soul", 80)

        // Der Stab schlägt auf den Boden: der Marmor bricht, das Reich der Toten steigt auf.
        boss.pose = HermesPose.SLAM
        Cine.orbit(everyone, center, 18.0, 6.0, 40.0, 70.0, 100, 64.0, 74.0, boss, 1.0)
        wait(8)
        Vfx.shockwave(world, Vfx.Style.SOUL, center, 26f, 40, 6f)
        Vfx.pillar(world, Vfx.Style.SOUL, center, 3f, 34f, 50)
        Vfx.shake(world, center, 6f, 50, 60f)
        sound(HhaSounds.IMPACT, 4f, 0.55f)
        sound(HhaSounds.SOUL, 3f, 0.8f)
        fight.arena.repaint(3)
        Cine.atmosphere(everyone, Vfx.Style.SOUL, center, 60)
        wait(90)

        boss.pose = HermesPose.CAST
        Cine.orbit(everyone, boss.entityPos, 4.4, 0.6, 200.0, 230.0, 90, 52.0, 48.0, boss, 2.4)
        say(Cine.SPEAKER_HERMES, "descent.way_short", 62)

        // Die Schlangen lösen sich vom Stab.
        val serpents = fight.spawnSerpents()
        Cine.orbit(everyone, boss.entityPos.add(0.0, 5.0, 0.0), 11.0, 2.0, 230.0, 280.0, 100, 56.0, 52.0, serpents.firstOrNull(), 0.4)
        sound(HhaSounds.ROAR, 2.5f, 1f)
        // Die Szene läuft mindestens so lange, wie der Satz gesprochen wird.
        for (t in 0 until maxOf(90, 14 + HhaVoiceLengths.ticks("hha.hermes.line.descent.serpents", 70))) {
            for ((i, serpent) in serpents.withIndex()) {
                val a = t * 0.11 + i * Math.PI
                val r = 2.0 + t * 0.09
                serpent.setPosition(boss.entityPos.add(cos(a) * r, 2.0 + t * 0.09, sin(a) * r))
                fight.face(serpent, boss.entityPos.add(cos(a + 0.4) * r, 0.0, sin(a + 0.4) * r))
            }
            if (t == 12) Cine.dialog(players, Cine.SPEAKER_HERMES, "hha.hermes.line.descent.serpents", HhaVoiceLengths.ticks("hha.hermes.line.descent.serpents", 70))
            yield(Unit)
        }

        Cine.title(everyone, "hha.hermes.stage.3", "hha.hermes.stage.3.sub", 60)
        sound(HhaSounds.STING, 2f, 0.84f)
        Cine.end(players)
        fight.setMusic(HhaSounds.THEME_GUIDE)
        boss.pose = HermesPose.IDLE
        fight.startStage(3)
    }

    // ---------------------------------------------------------------------------------------------
    // Stage 3 → 4: Zeus' Wort
    // ---------------------------------------------------------------------------------------------

    fun zeus(): Sequence<Unit> = sequence {
        val everyone = players
        Cine.start(everyone)
        Cine.fade(everyone, 0xFFFFFF, 1.0, 0.0, 14)
        fight.gatherPlayers()
        boss.setPosition(center.add(0.0, 0.1, 0.0))
        faceBossToPlayers()
        boss.pose = HermesPose.KNEEL
        boss.stage = 4
        fight.setMusic(null)

        // Der Himmel antwortet.
        Cine.atmosphere(everyone, Vfx.Style.STORM, center, 90)
        fight.arena.repaint(4)
        fight.lightning(center.add(5.0, 0.0, 3.0))
        Vfx.rift(world, Vfx.Style.STORM, center.add(0.0, 60.0, 0.0), Vec3d(0.0, -1.0, 0.0), 30f, 170)
        Vfx.bolt(world, Vfx.Style.STORM, center.add(0.0, 60.0, 0.0), center.add(0.0, 0.3, 0.0), 1.2f, 12)
        Vfx.shake(world, center, 8f, 30, 60f)
        sound(HhaSounds.THUNDER, 4f, 0.7f)
        Vfx.clouds(world, Vfx.Style.STORM, center, 90f, 66f, 260)

        // Zwei Augen öffnen sich im Himmel. Die Kamera blickt von ganz unten hinauf.
        val eyesHeight = 84.0
        val eyesLife = 40 + HhaVoiceLengths.ticks("hha.hermes.line.zeus.name", 46) + HhaVoiceLengths.ticks("hha.hermes.line.zeus.end_it", 64) + 30
        Vfx.zeusEyes(world, center, eyesHeight.toFloat(), eyesLife)
        val sky = center.add(0.0, eyesHeight, 0.0)
        Cine.camera(everyone, center.add(9.0, 0.9, 13.0), center.add(4.0, 1.3, 7.0), sky.add(0.0, -30.0, 0.0), sky, 90, 78.0, 62.0)
        wait(40)
        sound(HhaSounds.IMPACT, 4f, 0.5f)
        say(Cine.SPEAKER_ZEUS, "zeus.name", 46)
        fight.lightning(center.add(-14.0, 0.0, 8.0))
        Vfx.bolt(world, Vfx.Style.STORM, sky.add(-16.0, -2.0, 0.0), center.add(-6.0, 0.3, 2.0), 1.6f, 12)
        Vfx.bolt(world, Vfx.Style.STORM, sky.add(16.0, -2.0, 0.0), center.add(6.0, 0.3, -2.0), 1.6f, 12)
        for (player in players) Vfx.screenFlash(player, Vfx.Style.STORM, 0.5f, 8)
        Vfx.shake(world, center, 9f, 24, 60f)
        sound(HhaSounds.THUNDER, 4f, 0.85f)
        Cine.camera(everyone, boss.entityPos.add(-5.0, 0.6, 6.0), boss.entityPos.add(-3.4, 0.9, 4.0), sky, boss.entityPos.add(0.0, 8.0, 0.0), 80, 70.0, 58.0)
        say(Cine.SPEAKER_ZEUS, "zeus.end_it", 64)

        Cine.camera(everyone, boss.entityPos.add(1.8, 0.8, 2.6), boss.entityPos.add(1.2, 1.0, 2.0), boss.entityPos, boss.entityPos, 60, 42.0, 36.0, boss, 1.1)
        say(Cine.SPEAKER_HERMES, "zeus.yes_father", 44)

        boss.pose = HermesPose.IDLE
        faceBossToPlayers()
        Cine.orbit(everyone, boss.entityPos, 3.2, 1.6, 95.0, 70.0, 180, 44.0, 40.0, boss, 2.4)
        say(Cine.SPEAKER_HERMES, "zeus.fun", 80)
        say(Cine.SPEAKER_HERMES, "zeus.rule", 88)

        // Die Feder kehrt zurück und schenkt dem Spieler Flügel.
        val hero = fight.targets().firstOrNull()
        if (hero != null) {
            Vfx.fallingFeather(world, Vfx.Style.HEAVEN, hero.eyePos.add(0.0, 2.6, 0.0), 2.2f, 90)
            Cine.orbit(everyone, hero.entityPos, 3.8, 1.4, 20.0, 60.0, 92, 50.0, 44.0, hero, 1.3)
            say(Cine.SPEAKER_NARRATOR, "zeus.feather", 84)
        }
        for (player in fight.targets()) {
            Vfx.wings(world, Vfx.Style.HEAVEN, player, 90, 1f)
            Vfx.nova(world, Vfx.Style.HEAVEN, player.entityPos.add(0.0, 1.0, 0.0), 4f, 14)
        }
        Cine.wings(players, true)
        sound(HhaSounds.CHIME, 2f, 1.2f)

        // Hermes steigt auf: die letzte Form.
        boss.pose = HermesPose.ASCEND
        Vfx.wings(world, Vfx.Style.HEAVEN, boss, 20 * 900, 1.35f)
        Vfx.orbitRings(world, Vfx.Style.GOLD, boss, 2f, 20 * 900)
        Cine.orbit(everyone, center, 12.0, 1.0, 10.0, 50.0, 80, 60.0, 76.0, boss, 2.0, 12.0, 0.6)
        Vfx.pillar(world, Vfx.Style.GOLD, center, 2.6f, 60f, 60)
        sound(HhaSounds.RIFT, 3f, 1.3f)
        glide(center.add(0.0, 12.0, 0.0), 60)
        say(Cine.SPEAKER_HERMES, "zeus.breath", 56)

        Cine.title(everyone, "hha.hermes.stage.4", "hha.hermes.stage.4.sub", 60)
        sound(HhaSounds.STING, 3f, 1f)
        Cine.end(players)
        fight.setMusic(HhaSounds.THEME_FINALE)
        boss.pose = HermesPose.IDLE
        boss.vulnerable = false
        fight.startStage(4)
        for (player in fight.targets()) {
            player.addStatusEffect(StatusEffectInstance(StatusEffects.SPEED, 20 * 600, 1, true, false, true))
            player.addStatusEffect(StatusEffectInstance(StatusEffects.JUMP_BOOST, 20 * 600, 1, true, false, true))
            Vfx.aura(world, Vfx.Style.GOLD, player, 20 * 600, 0.8f)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Finale: Zustellung erfolgt
    // ---------------------------------------------------------------------------------------------

    fun finale(): Sequence<Unit> = sequence {
        val everyone = players
        fight.markFinale()
        Cine.clearVfx(everyone)
        Cine.wings(everyone, false)
        for (player in fight.targets()) {
            player.removeStatusEffect(StatusEffects.SPEED)
            player.removeStatusEffect(StatusEffects.JUMP_BOOST)
        }
        Cine.start(everyone)
        Cine.fade(everyone, 0xFFFFFF, 1.0, 0.0, 30)
        fight.gatherPlayers()
        fight.setMusic(null)
        boss.setPosition(center.add(0.0, 14.0, 0.0))
        boss.pose = HermesPose.FALL
        Cine.atmosphere(everyone, Vfx.Style.GOLD, center, 200)
        Vfx.godRays(world, Vfx.Style.HEAVEN, center, 30f, 90f, 280)
        sound(HhaSounds.IMPACT, 3f, 1.4f)

        Cine.orbit(everyone, center, 9.0, 3.0, 60.0, 100.0, 96, 50.0, 56.0, boss, 1.0)
        Vfx.feathers(world, Vfx.Style.HEAVEN, boss.entityPos.add(0.0, 1.0, 0.0), 60, 2.6f, 120)
        say(Cine.SPEAKER_NARRATOR, "finale.catch", 92)
        glide(center.add(0.0, 0.1, 0.0), 40)
        Vfx.shockwave(world, Vfx.Style.GOLD, center, 8f, 18, 2f)
        sound(SoundEvents.ITEM_MACE_SMASH_GROUND, 3f, 1f)

        faceBossToPlayers()
        boss.pose = HermesPose.KNEEL
        Cine.orbit(everyone, center, 3.4, 0.6, 80.0, 100.0, 120, 46.0, 42.0, boss, 1.2)
        wait(16)
        say(Cine.SPEAKER_HERMES, "finale.caught", 56)

        boss.pose = HermesPose.READ
        Cine.camera(everyone, boss.entityPos.add(0.0, 2.2, 3.6), boss.entityPos.add(0.0, 2.2, 2.6), boss.entityPos, boss.entityPos, 60, 46.0, 40.0, boss, 2.4)
        say(Cine.SPEAKER_HERMES, "finale.sign", 50)

        // Das Paket kommt vom Himmel.
        val parcelEntity = HhaEntities.DIVINE_PARCEL.create(world, SpawnReason.EVENT)!!
        val landing = center.add(0.0, 0.0, -6.0)
        parcelEntity.setPosition(landing.add(0.0, 46.0, 0.0))
        world.spawnEntity(parcelEntity)
        fight.parcel = parcelEntity
        Vfx.pillar(world, Vfx.Style.GOLD, landing, 3f, 50f, 110)
        Vfx.godRays(world, Vfx.Style.GOLD, landing, 10f, 70f, 170)
        Vfx.rift(world, Vfx.Style.GOLD, landing.add(0.0, 48.0, 0.0), Vec3d(0.0, -1.0, 0.0), 9f, 110)
        sound(HhaSounds.CHIME, 3f, 0.7f)
        Cine.orbit(everyone, landing, 14.0, 1.0, 320.0, 360.0, 110, 62.0, 52.0, parcelEntity, 0.6, 7.0, 1.4)
        for (i in 1..96) {
            val t = 1.0 - (1.0 - i / 96.0).let { it * it }
            parcelEntity.setPosition(landing.add(0.0, 46.0 * (1.0 - t), 0.0))
            yield(Unit)
        }
        Vfx.shockwave(world, Vfx.Style.GOLD, landing, 7f, 16, 2f)
        Vfx.feathers(world, Vfx.Style.HEAVEN, landing.add(0.0, 1.0, 0.0), 30, 1.8f, 70)
        Vfx.shake(world, landing, 3f, 12, 40f)
        sound(SoundEvents.BLOCK_ANVIL_LAND, 1.5f, 1.3f)
        wait(10)

        boss.pose = HermesPose.TAUNT
        faceBossToPlayers()
        Cine.orbit(everyone, boss.entityPos, 4.0, 1.0, 100.0, 130.0, 170, 52.0, 48.0, boss, 2.1)
        say(Cine.SPEAKER_HERMES, "finale.delivered", 84)
        // Der zweite Brief: Es wird kurz kalt auf dem Forum.
        boss.pose = HermesPose.LETTER
        val letter = letterPosition()
        var smoldering = true
        fight.runTask(sequence {
            var tick = 0
            while (smoldering) {
                if (tick % 2 == 0) Fx.spawn(world, HhaParticles.SOUL_MOTE, letter.x, letter.y + 0.2, letter.z, 1, 0.12, 0.08, 0.12, 0.01)
                if (tick % 5 == 0) Fx.spawn(world, HhaParticles.SOUL_FLAME, letter.x, letter.y - 0.1, letter.z, 1, 0.1, 0.05, 0.1, 0.005)
                if (tick % 9 == 0) Fx.spawn(world, HhaParticles.SOOT, letter.x, letter.y + 0.3, letter.z, 1, 0.1, 0.1, 0.1, 0.005)
                tick++
                yield(Unit)
            }
        })
        Vfx.flare(world, Vfx.Style.SOUL, letter, 1.1f, 12)
        Vfx.skyMood(world, Vfx.Style.HELL, center, 0.55f, 190, 64f)
        sound(SoundEvents.ENTITY_WARDEN_HEARTBEAT, 2.5f, 0.7f, letter)
        sound(HhaSounds.SOUL, 1.4f, 0.55f, letter)
        val toward = fight.targets().firstOrNull()?.entityPos?.subtract(boss.entityPos)?.let { Vec3d(it.x, 0.0, it.z).normalize() } ?: Vec3d(0.0, 0.0, 1.0)
        val eye = letter.add(toward.multiply(2.8)).add(0.2, 0.1, 0.0)
        Cine.camera(everyone, eye, letter.add(toward.multiply(1.9)).add(0.2, 0.05, 0.0), letter, letter, 150, 44.0, 34.0)
        say(Cine.SPEAKER_HERMES, "finale.second_letter", 74)
        sound(SoundEvents.ENTITY_WARDEN_HEARTBEAT, 3f, 0.6f, letter)
        Vfx.rune(world, Vfx.Style.HELL, boss.entityPos.add(0.0, 0.06, 0.0), 2.4f, 70)
        say(Cine.SPEAKER_HERMES, "finale.hades_seal", 58)

        // Er lässt ihn in der Hand verglühen und tut, als wäre nichts gewesen.
        smoldering = false
        boss.pose = HermesPose.TAUNT
        Vfx.flare(world, Vfx.Style.SOUL, letter, 1.8f, 10)
        Fx.spawn(world, HhaParticles.SOUL_FLAME, letter.x, letter.y, letter.z, 12, 0.18, 0.2, 0.18, 0.02)
        Fx.spawn(world, HhaParticles.EMBER_SPARK, letter.x, letter.y, letter.z, 8, 0.15, 0.2, 0.15, 0.03)
        Fx.spawn(world, HhaParticles.SOOT, letter.x, letter.y + 0.2, letter.z, 6, 0.15, 0.15, 0.15, 0.01)
        sound(SoundEvents.BLOCK_FIRE_EXTINGUISH, 1.2f, 0.8f, letter)
        Cine.orbit(everyone, boss.entityPos, 4.0, 1.0, 130.0, 150.0, 70, 52.0, 50.0, boss, 2.1)
        say(Cine.SPEAKER_HERMES, "finale.another_day", 56)

        // Abgang: ein Windstoß und ein paar Federn.
        boss.pose = HermesPose.DASH
        fight.face(boss, boss.entityPos.add(0.0, 0.0, 10.0))
        Vfx.trail(world, Vfx.Style.GOLD, boss, 0.8f, 40)
        sound(HhaSounds.DASH, 3f, 1.1f)
        val leave = boss.entityPos.add(0.0, 60.0, 90.0)
        val stay = boss.entityPos
        glide(stay.lerp(leave, 0.5), 8, smooth = false)
        Vfx.feathers(world, Vfx.Style.HEAVEN, stay.add(0.0, 1.5, 0.0), 40, 2f, 90)
        boss.discard()
        wait(30)

        Cine.title(everyone, "hha.hermes.title.delivered", "hha.hermes.title.delivered.sub", 70)
        Cine.end(players)
        fight.inCutscene = false
        // Wer nicht öffnet, bekommt das Paket spätestens nach fünf Minuten geöffnet.
        wait(20 * 300)
        val pending = fight.parcel
        if (pending != null && pending.openAge < 0) {
            fight.onParcelOpened(pending, fight.targets().firstOrNull() ?: return@sequence)
        }
    }

    /** Öffnen: Licht, Belohnung, dann sinkt alles zurück zur Erde. */
    fun parcelOpened(entity: DivineParcelEntity, opener: PlayerEntity): Sequence<Unit> = sequence {
        val pos = entity.entityPos
        Vfx.pillar(world, Vfx.Style.GOLD, pos, 2.4f, 36f, 80)
        Vfx.nova(world, Vfx.Style.GOLD, pos.add(0.0, 1.0, 0.0), 7f, 18)
        Vfx.feathers(world, Vfx.Style.HEAVEN, pos.add(0.0, 1.6, 0.0), 50, 2f, 90)
        sound(HhaSounds.CHIME, 3f, 1f, pos)
        sound(SoundEvents.ITEM_TOTEM_USE, 1.2f, 1.2f, pos)
        fight.grantRewards()
        for (player in fight.targets()) Vfx.wings(world, Vfx.Style.HEAVEN, player, 60, 1f)
        wait(100)
        Cine.fade(players, 0xFFF6D8, 0.0, 1.0, 24)
        wait(26)
        val everyone = players
        fight.finish(success = true)
        Cine.fade(everyone, 0xFFF6D8, 1.0, 0.0, 30)
        // Letzter Nachklapp, sobald alle wieder am Boden stehen.
        val level = world
        HermesFights.after(46) {
            val alive = everyone.filter { it.isAlive && !it.isRemoved }
            Cine.dialog(alive, Cine.SPEAKER_NARRATOR, "hha.hermes.line.finale.stinger", HhaVoiceLengths.ticks("hha.hermes.line.finale.stinger", 66))
            for (player in alive) {
                val feet = player.entityPos
                level.playSound(null, feet.x, feet.y, feet.z, SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 1.6f, 0.55f)
                Fx.spawn(level, HhaParticles.SOUL_MOTE, feet.x, feet.y + 0.15, feet.z, 14, 1.4, 0.05, 1.4, 0.015)
                Fx.spawn(level, HhaParticles.SOUL_FLAME, feet.x, feet.y + 0.1, feet.z, 5, 1.1, 0.02, 1.1, 0.005)
                Vfx.skyMood(level, Vfx.Style.HELL, feet, 0.35f, 70, 24f)
                Vfx.shake(level, feet, 0.7f, 20, 8f)
            }
        }
    }
}
