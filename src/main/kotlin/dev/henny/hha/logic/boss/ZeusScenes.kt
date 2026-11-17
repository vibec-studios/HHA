package dev.henny.hha.logic.boss

import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.entity.boss.olymp.ZeusAction
import dev.henny.hha.entity.boss.olymp.ZeusEntity
import dev.henny.hha.entity.miniboss.ScriptedBolt
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.timings.ZeusTimings as Z
import dev.henny.hha.stage.scripts.ZeusStages
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Vec3d
import kotlin.math.atan2

/**
 * Die Szenen des Zeus-Kampfes (PLAN 5.5; Überarbeitung 3: PLAN 2.8): Auftritt, Phasenwechsel II und III, „Anerkennung“.
 * Kamera und Bild kommen aus den Bühnen-Skripten [ZeusStages] (`zeus.intro`, `zeus.phase2`, `zeus.phase3`,
 * `zeus.defeat`; Auftritt und Ende bis 12 s, Phasenwechsel 5 s); hier stehen Zeilen, Aktionen, Orte, Musik und echte
 * Blitze. Überspringbar wie alle (alle Teilnehmer halten die Taste, dann spult das Drehbuch stumm vor; die Spielzustände
 * stehen danach wie nach der Szene). Text ohne Tonaufnahmen: Zeus spricht über [Cine.SPEAKER_ZEUS] mit Stimm-Lauten wie
 * im „BEENDE ES“ der Hermes-Szene; Nike über den Erzähler. Die Zeilen stehen unter `hha.zeus.line.*`.
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

    /** Blickrichtung als Gierwinkel (Grad, wie `Entity.yaw`; vorn = (−sin, 0, cos)). */
    private fun yawOf(dir: Vec3d): Float = Math.toDegrees(atan2(-dir.x, dir.z)).toFloat()

    // ---------------------------------------------------------------------------------------------
    // Auftritt
    // ---------------------------------------------------------------------------------------------

    /**
     * Auftritt `zeus.intro` (11,8 s): Thron von unten, er sitzt; zwei Zeilen; ein Blitz teilt das Bild, er erhebt sich
     * (`RISE`) und tritt mit einem Donnerschritt in den Hof; Titelkarte `hha.zeus.title`, dann Musik.
     */
    fun entrance(): Sequence<Unit> {
        val stage = zeus.sceneStage() ?: return simpleEntrance()
        return play(sequence {
            val viewers = zeus.sceneViewers()
            val world = zeus.entityWorld as ServerWorld
            begin(viewers)
            Cine.music(viewers, null)
            zeus.scenePlace(stage.throne, stage.center)
            zeus.sceneAct(ZeusAction.RISE)
            val away = flat(stage.center.subtract(stage.throne))
            Cine.stage(viewers, ZeusStages.INTRO, stage.throne, yawOf(away), listOf(zeus))
            Vfx.clouds(world, Vfx.Style.STORM, stage.center, 50f, 24f, ZeusStages.INTRO_LENGTH + 20)
            for (t in 0 until ZeusStages.INTRO_LENGTH) {
                // Bis er aufsteht, sitzt er: RISE beginnt sitzend, also immer wieder von vorn.
                if (t < ZeusStages.INTRO_RISE && t % 3 == 0) zeus.sceneAct(ZeusAction.RISE)
                when (t) {
                    ZeusStages.INTRO_LINE_1 -> say(viewers, Cine.SPEAKER_ZEUS, "hha.zeus.line.intro.1", 66)
                    // Der Blitz, der das Bild teilt, ist Bild der Bühne; hier nur der Donner.
                    ZeusStages.INTRO_SPLIT -> zeus.sceneSound(HhaSounds.THUNDER, stage.throne, 2.6f, 0.6f)
                    ZeusStages.INTRO_RISE -> zeus.sceneAct(ZeusAction.RISE)
                    ZeusStages.INTRO_LINE_2 -> say(viewers, Cine.SPEAKER_ZEUS, "hha.zeus.line.intro.2", 66)
                    ZeusStages.INTRO_STEP -> zeus.sceneAct(ZeusAction.STEP)
                    ZeusStages.INTRO_STEP + Z.STEP_VANISH -> zeus.sceneStepOut(stage.throne)
                    ZeusStages.INTRO_STEP + Z.STEP_MOVE -> zeus.scenePlace(stage.landing, stage.seal)
                    ZeusStages.INTRO_APPEAR -> {
                        zeus.sceneStepIn(stage.landing)
                        zeus.lightning(stage.landing)
                        zeus.sceneSound(HhaSounds.THUNDER, stage.landing, 3f, 0.8f)
                    }
                    ZeusStages.INTRO_TITLE -> Cine.title(viewers, "hha.zeus.title", "hha.zeus.title.sub", 50)
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
     * Phasenwechsel `zeus.phase2`/`zeus.phase3` (5 s): `PHASE`, ein Blitz fährt in ihn (Stoß, Ruck und Blitz ≤ 0,22
     * kommen von der Bühne), der Himmel wird dunkler (Gewitter über dem Hof, in III einmal noch dunkler), das Thema der
     * neuen Phase setzt ein; eine Zeile; zwei verbrauchte Pfeiler werden frisch (Bogen von ihm zu ihrer Spitze).
     */
    fun phase(newPhase: Int): Sequence<Unit> = play(sequence {
        val viewers = zeus.sceneViewers()
        val world = zeus.entityWorld as ServerWorld
        val number = newPhase + 1
        begin(viewers)
        zeus.sceneAct(ZeusAction.PHASE)
        val at = zeus.entityPos
        val stage = zeus.sceneStage()
        val center = stage?.center ?: at
        Cine.stage(viewers, if (newPhase >= 2) ZeusStages.PHASE3 else ZeusStages.PHASE2, at, zeus.yaw, listOf(zeus))
        for (t in 0 until ZeusStages.PHASE_LENGTH) {
            when (t) {
                ZeusStages.PHASE_BOLT -> {
                    zeus.sceneSkyBolt(at)
                    zeus.lightning(at)
                    zeus.sceneSound(HhaSounds.THUNDER, at, 3.2f, 0.7f)
                    zeus.scenePhaseMusic(viewers, newPhase)
                    if (newPhase == 1) zeus.sceneStorm(viewers, true, 60) else Vfx.skyMood(world, Vfx.Style.STORM, center, 0.9f, 120, 64f)
                }
                ZeusStages.PHASE_LINE -> say(viewers, Cine.SPEAKER_ZEUS, "hha.zeus.line.phase.$number", 66)
                ZeusStages.PHASE_RENEW -> {
                    val chest = zeus.entityPos.add(0.0, zeus.height * 0.6, 0.0)
                    for (tip in zeus.sceneRenewRods()) {
                        Vfx.flare(world, Vfx.Style.THUNDER, tip, 1.2f, 10)
                        BossFx.arc(world, Vfx.Style.THUNDER, chest, tip, bend = RENEW_BEND, width = RENEW_WIDTH, life = RENEW_LIFE, fromEntity = zeus)
                    }
                }
                ZeusStages.PHASE_TITLE -> Cine.title(viewers, "hha.zeus.phase.$number", "hha.zeus.phase.$number.sub", 40)
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
     * „Anerkennung“ `zeus.defeat` (12 s): Er senkt den Blitz (`YIELD`), der Sieg-Stinger des Olymp klingt. Nike (Licht
     * der Bühne, `zeus.nike`) setzt dem Spieler mit dem meisten Schaden den Kranz auf (Requisit `HermesBoltEntity.WREATH`,
     * schwebt über dem Kopf und folgt ihm, bis Zeus geht, höchstens [Z.WREATH_TICKS]). Er zeigt nach unten (`POINT`).
     * Drei Zeilen, dann Beute und Fortschritt; danach geht er.
     */
    fun victory(): Sequence<Unit> = play(sequence {
        val viewers = zeus.sceneViewers()
        val world = zeus.entityWorld as ServerWorld
        val stage = zeus.sceneStage()
        val center = stage?.center ?: zeus.entityPos
        begin(viewers)
        // Sieg-Stinger: läuft am Client als Stinger über allem, das Bossthema blendet aus (P04).
        Cine.music(viewers, HhaSounds.VICTORY_OLYMP)
        zeus.sceneAct(ZeusAction.YIELD)
        val victor = zeus.sceneVictor()
        victor?.let { zeus.sceneFace(it.entityPos) }
        if (stage != null) Cine.atmosphere(viewers, Vfx.Style.GOLD, center, 80)
        Cine.stage(viewers, ZeusStages.DEFEAT, zeus.entityPos, zeus.yaw, listOfNotNull(zeus, victor))
        // Nikes Kranz: folgt dem Kopf des Siegers Tick für Tick.
        var wreath: ScriptedBolt? = null
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
        for (t in 0 until ZeusStages.DEFEAT_LENGTH) {
            when (t) {
                ZeusStages.DEFEAT_LINE_1 -> say(viewers, Cine.SPEAKER_ZEUS, "hha.zeus.line.end.1", 56)
                ZeusStages.DEFEAT_LINE_2 -> say(viewers, Cine.SPEAKER_NARRATOR, "hha.zeus.line.end.2", 40)
                ZeusStages.DEFEAT_WREATH -> if (victor != null) {
                    val head = victor.entityPos.add(0.0, victor.height + Z.WREATH_HEIGHT, 0.0)
                    wreath = ScriptedBolt.spawn(world, zeus, HermesBoltEntity.WREATH, 1f, head)
                    // Ohne Requisit (Spawn fehlgeschlagen) wie früher ein goldener Ring.
                    if (wreath == null) Vfx.rune(world, Vfx.Style.GOLD, head, Z.WREATH_RADIUS, Z.WREATH_TICKS)
                    Vfx.feathers(world, Vfx.Style.GOLD, head, 10, 0.8f, 50)
                    zeus.sceneSound(HhaSounds.CHIME, victor.entityPos, 2f, 1.2f)
                }
                ZeusStages.DEFEAT_POINT -> zeus.sceneAct(ZeusAction.POINT)
                ZeusStages.DEFEAT_LINE_3 -> say(viewers, Cine.SPEAKER_ZEUS, "hha.zeus.line.end.3", 50)
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
        /** Bogen von Zeus zur Spitze eines erneuerten Pfeilers (Phasenwechsel). */
        const val RENEW_BEND = 0.35f
        const val RENEW_WIDTH = 0.45f
        const val RENEW_LIFE = 14

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
