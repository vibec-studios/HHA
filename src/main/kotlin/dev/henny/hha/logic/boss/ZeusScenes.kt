package dev.henny.hha.logic.boss

import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.olymp.ZeusAction
import dev.henny.hha.entity.boss.olymp.ZeusEntity
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.timings.ZeusTimings as Z
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.math.Vec3d
import kotlin.math.atan2

/**
 * Die Szenen des Zeus-Kampfes (PLAN 5.5): Auftritt, Phasenwechsel II und III, „Anerkennung“. Jede höchstens 8 s,
 * überspringbar wie alle (alle Teilnehmer halten die Taste, dann spult das Drehbuch vor; die Spielzustände stehen
 * danach wie nach der Szene). Text ohne Tonaufnahmen: Zeus spricht über [Cine.SPEAKER_ZEUS] mit Stimm-Lauten wie im
 * „BEENDE ES“ der Hermes-Szene; Nike über den Erzähler. Die Zeilen stehen unter `hha.zeus.line.*`.
 *
 * Die Szenen laufen im Drehbuch des Bosses ([ZeusEntity]): Solange eine läuft, nimmt er keinen Schaden, die Luftregel,
 * Saal-Gefahren und Pfeiler ruhen, und die Leine greift nicht (der Thron steht außerhalb der Fläche).
 */
class ZeusScenes(private val zeus: ZeusEntity) {

    private val skip = CineSkip()
    private var current: Iterator<Unit>? = null

    /** Wer die laufende Szene sieht (für das Ende und den Abbruch). */
    private var audience: List<ServerPlayerEntity> = emptyList()

    /** Läuft gerade eine Szene (zwischen [begin] und [finish])? */
    var playing = false
        private set

    // ---------------------------------------------------------------------------------------------
    // Ablauf und Überspringen
    // ---------------------------------------------------------------------------------------------

    /** Spielt [scene] als Teil des Drehbuchs ab; stimmen alle fürs Überspringen, wird bis zum Szenenende vorgespult. */
    private fun play(scene: Sequence<Unit>): Sequence<Unit> = sequence {
        register()
        val iterator = scene.iterator()
        current = iterator
        active.add(this@ZeusScenes)
        while (true) {
            if (skip.pending) skip.fastForward(zeus.sceneViewers(), ::advance)
            val running = current ?: break
            if (!running.hasNext()) break
            running.next()
            yield(Unit)
        }
        current = null
        active.remove(this@ZeusScenes)
        skip.reset()
    }

    private fun advance(): Boolean {
        val running = current ?: return false
        if (!running.hasNext()) {
            current = null
            return false
        }
        running.next()
        return true
    }

    private fun wants(player: ServerPlayerEntity): Boolean = playing && audience.any { it.uuid == player.uuid }

    private fun vote(player: ServerPlayerEntity): Boolean {
        if (!wants(player)) return false
        skip.vote(player, zeus.sceneViewers())
        return true
    }

    private fun begin(players: List<ServerPlayerEntity>) {
        audience = players
        playing = true
        Cine.start(players)
    }

    private fun finish() {
        val everyone = (audience + zeus.sceneViewers()).distinctBy { it.uuid }
        Cine.end(everyone)
        playing = false
        audience = emptyList()
    }

    /** Der Kampf endet mitten in einer Szene (alle weg, Befehl, Entfernen): Clients vollständig zurücksetzen. */
    fun abort() {
        if (playing) {
            val everyone = (audience + zeus.sceneViewers()).distinctBy { it.uuid }
            Cine.reset(everyone)
        }
        playing = false
        audience = emptyList()
        current = null
        active.remove(this)
        skip.reset()
    }

    private suspend fun SequenceScope<Unit>.wait(ticks: Int) {
        repeat(ticks) { yield(Unit) }
    }

    private fun say(players: List<ServerPlayerEntity>, speaker: Int, key: String, ticks: Int) {
        Cine.dialog(players, speaker, key, HhaVoiceLengths.ticks(key, ticks))
    }

    private fun front(): Double {
        val r = Math.toRadians(zeus.yaw.toDouble())
        return Math.toDegrees(atan2(kotlin.math.cos(r), -kotlin.math.sin(r)))
    }

    private fun flash(players: List<ServerPlayerEntity>) {
        for (player in players) Vfx.screenFlash(player, Vfx.Style.THUNDER, Z.SCREEN_FLASH_MAX, 8)
    }

    // ---------------------------------------------------------------------------------------------
    // Auftritt
    // ---------------------------------------------------------------------------------------------

    /**
     * Auftritt (7,5 s): Blick vom Siegel zum Thron, auf dem Zeus sitzt; zwei Zeilen; er erhebt sich (`RISE`) und tritt
     * mit einem Donnerschritt in den Hof; Titelkarte `hha.zeus.title`, dann Musik.
     */
    fun entrance(): Sequence<Unit> {
        val stage = zeus.sceneStage() ?: return simpleEntrance()
        return play(sequence {
            val viewers = zeus.sceneViewers()
            begin(viewers)
            Cine.fade(viewers, 0x000000, 1.0, 0.0, 14)
            Cine.music(viewers, null)
            zeus.scenePlace(stage.throne, stage.center)
            zeus.sceneAct(ZeusAction.RISE)
            val away = flat(stage.seal.subtract(stage.throne))
            val side = Vec3d(-away.z, 0.0, away.x)
            Cine.camera(
                viewers,
                stage.seal.add(away.multiply(5.0)).add(side.multiply(2.5)).add(0.0, 2.4, 0.0),
                stage.throne.add(away.multiply(9.0)).add(side.multiply(1.5)).add(0.0, 3.2, 0.0),
                stage.throne.add(0.0, 2.5, 0.0), stage.throne.add(0.0, 3.0, 0.0), Z.INTRO_STEP + 12, 70.0, 58.0, zeus, 3.0,
            )
            Vfx.clouds(zeus.entityWorld as net.minecraft.server.world.ServerWorld, Vfx.Style.STORM, stage.center, 50f, 24f, Z.INTRO_END + 20)
            for (t in 0 until Z.INTRO_END) {
                // Bis er aufsteht, sitzt er: RISE beginnt sitzend, also immer wieder von vorn.
                if (t < Z.INTRO_RISE && t % 3 == 0) zeus.sceneAct(ZeusAction.RISE)
                when (t) {
                    14 -> say(viewers, Cine.SPEAKER_ZEUS, "hha.zeus.line.intro.1", 66)
                    Z.INTRO_RISE -> {
                        zeus.sceneAct(ZeusAction.RISE)
                        zeus.sceneSound(HhaSounds.THUNDER, stage.throne, 2.4f, 0.6f)
                    }
                    84 -> say(viewers, Cine.SPEAKER_ZEUS, "hha.zeus.line.intro.2", 66)
                    Z.INTRO_STEP -> zeus.sceneAct(ZeusAction.STEP)
                    Z.INTRO_STEP + Z.STEP_VANISH -> {
                        zeus.sceneSkyBolt(stage.throne)
                        // Schnitt: Blick vom Siegel über den Hof, dort erscheint er.
                        Cine.camera(
                            viewers,
                            stage.seal.subtract(away.multiply(3.0)).add(0.0, 4.5, 0.0),
                            stage.seal.subtract(away.multiply(1.0)).add(side.multiply(1.5)).add(0.0, 3.6, 0.0),
                            stage.landing.add(0.0, 2.5, 0.0), stage.landing.add(0.0, 2.5, 0.0), Z.INTRO_END - t, 64.0, 56.0, zeus, 3.0,
                        )
                    }
                    Z.INTRO_STEP + Z.STEP_MOVE -> zeus.scenePlace(stage.landing, stage.seal)
                    Z.INTRO_STEP + Z.STEP_APPEAR -> {
                        zeus.sceneSkyBolt(stage.landing)
                        zeus.lightning(stage.landing)
                        zeus.sceneSound(HhaSounds.THUNDER, stage.landing, 3f, 0.8f)
                    }
                    Z.INTRO_STEP + Z.STEP_APPEAR + 4 -> Cine.title(viewers, "hha.zeus.title", "hha.zeus.title.sub", 50)
                }
                yield(Unit)
            }
            finish()
            zeus.sceneAct(0)
            zeus.sceneMusic(zeus.sceneViewers())
        })
    }

    /** Ohne Arena (`/summon`): nur Titel, Musik und Aufstehen. */
    private fun simpleEntrance(): Sequence<Unit> = sequence {
        val viewers = zeus.sceneViewers()
        Cine.title(viewers, "hha.zeus.title", "hha.zeus.title.sub", 70)
        zeus.sceneMusic(viewers)
        zeus.sceneAct(ZeusAction.RISE)
        wait(Z.RISE_END)
        zeus.sceneAct(0)
    }

    // ---------------------------------------------------------------------------------------------
    // Phasenwechsel
    // ---------------------------------------------------------------------------------------------

    /**
     * Phasenwechsel II und III (4 s): `PHASE`, ein Blitz fährt in ihn, der Himmel wird dunkler (Gewitter über dem Hof,
     * in III einmal noch dunkler), ein Bildschirmblitz ≤ 0,25; eine Zeile; zwei verbrauchte Pfeiler werden frisch.
     */
    fun phase(newPhase: Int): Sequence<Unit> = play(sequence {
        val viewers = zeus.sceneViewers()
        val number = newPhase + 1
        begin(viewers)
        zeus.sceneAct(ZeusAction.PHASE)
        val at = zeus.entityPos
        val stage = zeus.sceneStage()
        val center = stage?.center ?: at
        val facing = front()
        Cine.orbit(viewers, at, 9.0, 2.4, facing - 40.0, facing + 15.0, Z.PHASE_SCENE, 64.0, 56.0, zeus, 3.4, 7.5, 3.0)
        for (t in 0 until Z.PHASE_SCENE) {
            when (t) {
                Z.PHASE_SCENE_FLASH -> {
                    zeus.sceneSkyBolt(at)
                    zeus.lightning(at)
                    flash(viewers)
                    zeus.sceneSound(HhaSounds.THUNDER, at, 3.2f, 0.7f)
                    val world = zeus.entityWorld as net.minecraft.server.world.ServerWorld
                    if (newPhase == 1) zeus.sceneStorm(viewers, true, 60) else Vfx.skyMood(world, Vfx.Style.STORM, center, 0.9f, 120, 64f)
                }
                10 -> say(viewers, Cine.SPEAKER_ZEUS, "hha.zeus.line.phase.$number", 66)
                24 -> {
                    val world = zeus.entityWorld as net.minecraft.server.world.ServerWorld
                    for (tip in zeus.sceneRenewRods()) Vfx.flare(world, Vfx.Style.THUNDER, tip, 1.2f, 10)
                }
                40 -> Cine.title(viewers, "hha.zeus.phase.$number", "hha.zeus.phase.$number.sub", 40)
            }
            yield(Unit)
        }
        finish()
        zeus.sceneAct(0)
    })

    // ---------------------------------------------------------------------------------------------
    // Ende: Anerkennung
    // ---------------------------------------------------------------------------------------------

    /**
     * „Anerkennung“ (7,8 s): Er senkt den Blitz (`YIELD`). Nike setzt dem Spieler mit dem meisten Schaden den Kranz auf
     * (Requisit `HermesBoltEntity.WREATH`, schwebt über dem Kopf und folgt ihm, bis Zeus geht, höchstens
     * [ZeusTimings.WREATH_TICKS]). Drei Zeilen, dann Beute und Fortschritt; danach geht er.
     */
    fun victory(): Sequence<Unit> = play(sequence {
        val viewers = zeus.sceneViewers()
        val world = zeus.entityWorld as net.minecraft.server.world.ServerWorld
        val stage = zeus.sceneStage()
        val center = stage?.center ?: zeus.entityPos
        begin(viewers)
        Cine.music(viewers, null)
        zeus.sceneAct(ZeusAction.YIELD)
        val victor = zeus.sceneVictor()
        victor?.let { zeus.sceneFace(it.entityPos) }
        if (stage != null) Cine.atmosphere(viewers, Vfx.Style.GOLD, center, 80)
        val facing = front()
        Cine.orbit(viewers, zeus.entityPos, 7.0, 1.2, facing - 25.0, facing + 5.0, 60, 60.0, 54.0, zeus, 3.4, 6.0, 1.6)
        // Nikes Kranz: folgt dem Kopf des Siegers Tick für Tick.
        var wreath: dev.henny.hha.entity.miniboss.ScriptedBolt? = null
        var wreathAge = 0
        fun follow() {
            val w = wreath ?: return
            val v = victor
            if (v == null || v.isRemoved || ++wreathAge > Z.WREATH_TICKS) {
                w.discard()
                wreath = null
                return
            }
            w.moveTo(v.entityPos.add(0.0, v.height + Z.WREATH_HEIGHT, 0.0))
        }
        for (t in 0 until Z.VICTORY_SCENE) {
            when (t) {
                6 -> say(viewers, Cine.SPEAKER_ZEUS, "hha.zeus.line.end.1", 56)
                60 -> if (victor != null) {
                    Cine.orbit(viewers, victor.entityPos, 3.6, 1.5, 20.0, 70.0, 46, 50.0, 44.0, victor, 1.9)
                }
                62 -> {
                    if (victor != null) {
                        val head = victor.entityPos.add(0.0, victor.height + Z.WREATH_HEIGHT, 0.0)
                        wreath = dev.henny.hha.entity.miniboss.ScriptedBolt.spawn(
                            world, zeus, dev.henny.hha.entity.boss.HermesBoltEntity.WREATH, 1f, head,
                        )
                        // Ohne Requisit (Spawn fehlgeschlagen) wie früher ein goldener Ring.
                        if (wreath == null) Vfx.rune(world, Vfx.Style.GOLD, head, Z.WREATH_RADIUS, Z.WREATH_TICKS)
                        Vfx.feathers(world, Vfx.Style.GOLD, head, 10, 0.8f, 50)
                        zeus.sceneSound(HhaSounds.CHIME, victor.entityPos, 2f, 1.2f)
                    }
                    say(viewers, Cine.SPEAKER_NARRATOR, "hha.zeus.line.end.2", 40)
                }
                106 -> {
                    val facingNow = front()
                    Cine.orbit(viewers, zeus.entityPos, 6.5, 1.6, facingNow - 10.0, facingNow + 20.0, 50, 58.0, 52.0, zeus, 2.8)
                    say(viewers, Cine.SPEAKER_ZEUS, "hha.zeus.line.end.3", 50)
                }
            }
            follow()
            yield(Unit)
        }
        finish()
        zeus.sceneFinish()
        repeat(Z.VICTORY_AFTER) {
            follow()
            yield(Unit)
        }
        wreath?.discard()
        if (stage != null) zeus.sceneStorm(zeus.sceneViewers(), false, 1)
        zeus.sceneVanish()
    })

    private fun flat(v: Vec3d): Vec3d = Vec3d(v.x, 0.0, v.z).let { if (it.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else it.normalize() }

    private companion object {
        /** Laufende Szenen auf dem Server (für die Überspringen-Taste). */
        private val active = HashSet<ZeusScenes>()
        private var registered = false

        /** Einmal je Server-Lebenszeit beim Kampfsystem für die Überspringen-Taste anmelden. */
        private fun register() {
            if (registered) return
            registered = true
            Cine.onSkip { player -> active.firstOrNull { it.wants(player) }?.vote(player) == true }
        }
    }
}
