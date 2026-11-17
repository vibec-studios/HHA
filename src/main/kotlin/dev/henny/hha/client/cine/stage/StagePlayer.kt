package dev.henny.hha.client.cine.stage

import dev.henny.hha.Hha
import dev.henny.hha.client.audio.MusicDirector
import dev.henny.hha.client.cine.CinePlayer
import dev.henny.hha.client.pose.HhaPose
import dev.henny.hha.client.rpg.game.RpgClientState
import dev.henny.hha.client.vfx.CameraFx
import dev.henny.hha.client.vfx.ScreenFx
import dev.henny.hha.client.vfx.fx.FxClient
import dev.henny.hha.client.vfx.fx.FxRegistry
import dev.henny.hha.client.vfx.fx.ScreenPass
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.stage.Caption
import dev.henny.hha.stage.Card
import dev.henny.hha.stage.Chapter
import dev.henny.hha.stage.Costume
import dev.henny.hha.stage.Cue
import dev.henny.hha.stage.Fade
import dev.henny.hha.stage.Flash
import dev.henny.hha.stage.FxCue
import dev.henny.hha.stage.Key
import dev.henny.hha.stage.Kick
import dev.henny.hha.stage.Mark
import dev.henny.hha.stage.Music
import dev.henny.hha.stage.Pose
import dev.henny.hha.stage.PoseCue
import dev.henny.hha.stage.PuppetAct
import dev.henny.hha.stage.PuppetMove
import dev.henny.hha.stage.PuppetRemove
import dev.henny.hha.stage.PuppetSpawn
import dev.henny.hha.stage.ScreenCue
import dev.henny.hha.stage.Shake
import dev.henny.hha.stage.Shot
import dev.henny.hha.stage.Sound
import dev.henny.hha.stage.StageScript
import dev.henny.hha.stage.StageScripts
import dev.henny.hha.stage.scripts.RpgStages
import net.minecraft.client.MinecraftClient
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.sound.SoundInstance
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.sound.SoundCategory
import net.minecraft.util.Identifier
import net.minecraft.util.math.Vec3d
import net.minecraft.util.math.random.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Spielt genau ein Bühnen-Skript (`stage/StageScripts`, PLAN 6.5) am Client: Kamera, FOV, Spuren, Puppen, Posen.
 * Kommt über `Cine.OP_STAGE` aus [CinePlayer]; Dialog, Stimmen, Titel, Abstimmung und Phasenleiste bleiben dort.
 *
 * **Zeit:** [clock] zählt Ticks ab Szenenbeginn (Start bei `startTick`, für Nachzügler). Spuren feuern in [tick], wenn
 * ihr Tick erreicht ist; die Kamera rechnet je Bild mit `clock + tickDelta`.
 *
 * **Kamera:** Die laufende Einstellung ([StageScript.shotAt]) löst ihre [Key]s je Bild neu auf (Darsteller bewegen sich).
 * 1 Punkt fest, 2 Punkte Gerade oder Bogen um den Blickpunkt (`arc`), ab 3 Catmull-Rom mit Bogenlänge; Kurve aus
 * `ease`. Dazu das [CameraCraft]-Handwerk: Folge-Blick (`follow`), Handkamera (`handheld`), Kollisionsschutz.
 * `Key.Last` ist das zuletzt gezeigte Bild beim Beginn der Einstellung.
 *
 * **Feste Endzustände** (Rückfälle PLAN 5):
 * - Skript unbekannt, keine Welt, Fehler beim Rechnen → [abort]: Kamera frei, Puppen weg; `OP_END` räumt den Rest.
 * - Ende der Zeitleiste → das letzte Bild bleibt stehen (Schlüssel weiter live), bis `OP_END`/`OP_STAGE_STOP`
 *   kommt, höchstens [HOLD_MAX_TICKS]; ohne laufende Cutscene endet die Bühne sofort.
 * - Blick-Darsteller länger als [MISSING_TICKS] weg → in [RETURN_TICKS] zurück in die Augen, dann Halten dort.
 * - Dimensionswechsel, Tod des Spielers, Verbindungsende → [stop] bzw. [reset].
 * - [stop] räumt immer: Puppen, Posen, Kostüme, eine Bühnen-Blende (fällt weich weg), Bildschirm-Pass der Bühne.
 */
object StagePlayer {

    /** So lange hält das letzte Bild nach dem Ende der Zeitleiste höchstens (wartet auf `OP_END`). */
    const val HOLD_MAX_TICKS = 100

    /** Fehlt der Blick-Darsteller länger als so viele Ticks, fährt die Kamera zurück in die Augen (PLAN 5, wie Void). */
    const val MISSING_TICKS = 20

    /** Dauer der Rückfahrt in die Augen. */
    const val RETURN_TICKS = 15

    /** Lesedauer einer Bühnen-Zeile mit `ticks = 0`: Zeichen je Tick, Zuschlag, Untergrenze (wie `CinePlayer`). */
    const val CAPTION_EXTRA_TICKS = 26
    const val CAPTION_MIN_TICKS = 40

    /** Deckel der Bühnen-Spuren: Blitz-Stärke und Kamera-Ruck (Grad je Achse). */
    const val FLASH_MAX = 0.25f
    const val KICK_MAX = BossFx.KICK_MAX_CINE

    /** Effekt-IDs, deren `dir` ein absoluter Zielpunkt ist (API.md 6.2); sonst geht eine Einheitsrichtung hinaus. */
    private val ABSOLUTE_DIR = setOf("core.arc", "core.debris_pull", "core.wave_wall")

    /** Läuft gerade ein Skript? Dann kommen Kamera und FOV von hier. */
    @JvmStatic
    var active = false
        private set

    private var script: StageScript? = null
    private var world: ClientWorld? = null
    private var anchor: Vec3d = Vec3d.ZERO
    private var forward: Vec3d = Vec3d(0.0, 0.0, 1.0)
    private var right: Vec3d = Vec3d(-1.0, 0.0, 0.0)
    private var actors: List<Int> = emptyList()
    private val lastActorPos = HashMap<Int, Vec3d>()
    private val lastActorYaw = HashMap<Int, Float>()
    private val lastPuppetPos = HashMap<String, Vec3d>()
    private val lastPuppetYaw = HashMap<String, Float>()

    /** Ticks ab Szenenbeginn. */
    var clock = 0
        private set
    private var nextCue = 0
    private var holding = 0

    // Kamera
    private var shot: Shot? = null
    private var snapCam: Vec3d = Vec3d.ZERO
    private var snapLook: Vec3d = Vec3d.ZERO
    private var snapFov = 70f
    private var hasFrame = false
    private var lastCam: Vec3d = Vec3d.ZERO
    private var lastLook: Vec3d = Vec3d.ZERO
    private var lastFov = 70f
    private val follow = CameraCraft.Follow()
    private val clip = CameraCraft.Clip()

    // Rückfahrt in die Augen
    private var lookSeen = false
    private var lookMissing = 0
    private var returning = -1

    // Aufräumen
    private val posed = HashSet<Int>()
    private val dressed = HashSet<Int>()
    private val lifted = HashSet<Int>()
    private var screen: BossFx.Screen? = null

    fun init() {
        StagePuppets.init()
        StageDemo.register()
        // Sprecher → Wesen für CinePlayer.mouthOf (die Boss-Pakete dürfen überschreiben).
        CinePlayer.speakerType(dev.henny.hha.logic.boss.Cine.SPEAKER_HERMES, dev.henny.hha.HhaEntities.HERMES)
        CinePlayer.speakerType(dev.henny.hha.logic.boss.Cine.SPEAKER_HADES, dev.henny.hha.HhaEntities.HADES)
        CinePlayer.speakerType(dev.henny.hha.logic.boss.Cine.SPEAKER_KRONOS, dev.henny.hha.HhaEntities.KRONOS)
        CinePlayer.speakerType(dev.henny.hha.logic.boss.Cine.SPEAKER_HEKATE, dev.henny.hha.HhaEntities.HEKATE)
        CinePlayer.speakerType(dev.henny.hha.logic.boss.Cine.SPEAKER_BRONTES, dev.henny.hha.HhaEntities.BRONTES)
        CinePlayer.speakerType(dev.henny.hha.logic.boss.Cine.SPEAKER_KRATOS, dev.henny.hha.HhaEntities.KRATOS)
        CinePlayer.speakerType(dev.henny.hha.logic.boss.Cine.SPEAKER_ZEUS, dev.henny.hha.HhaEntities.ZEUS)
    }

    // ---------------------------------------------------------------------------------------------
    // Start und Ende
    // ---------------------------------------------------------------------------------------------

    /**
     * Startet das Skript [id] mit Anker [anchor] und Blick [yaw] (Grad), Darstellern [actorIds] (Index = Reihenfolge),
     * ab Tick [startTick]. Ein unbekanntes Skript oder fehlende Welt bricht ab (Kamera frei).
     */
    fun play(id: String, anchor: Vec3d, yaw: Float, actorIds: List<Int>, startTick: Int = 0) {
        // Zwei Skripte hintereinander (P20): Das letzte Bild des ersten bleibt `Key.Last` des zweiten.
        val chained = active && hasFrame
        if (active) stop()
        val found = StageScripts.get(id)
        val world = MinecraftClient.getInstance().world
        if (found == null || world == null) {
            Hha.LOGGER.warn("Bühne: Skript '{}' {} – Kamera bleibt frei", id, if (found == null) "ist am Client unbekannt" else "ohne Welt")
            return
        }
        script = found
        this.world = world
        this.anchor = anchor
        val rad = Math.toRadians(yaw.toDouble())
        forward = Vec3d(-sin(rad), 0.0, cos(rad))
        right = Vec3d(-cos(rad), 0.0, -sin(rad))
        actors = actorIds
        lastActorPos.clear(); lastActorYaw.clear(); lastPuppetPos.clear(); lastPuppetYaw.clear()
        clock = startTick.coerceIn(0, found.length)
        nextCue = 0
        holding = 0
        shot = null
        hasFrame = chained
        lookSeen = false
        lookMissing = 0
        returning = -1
        follow.reset()
        clip.reset()
        active = true
        // Nachzügler: Zustands-Spuren vor dem Starttick nachholen (Puppen, Kostüm, Musik, Posen), flüchtige auslassen.
        if (clock > 0) catchUp(found, clock)
        val ok = FxClient.guarded("stage.start") { fire(found); true }
        if (ok == null) abort()
    }

    /** Bricht ab: Kamera frei, Puppen weg, Posen und Kostüme aus. */
    fun stop() {
        if (!active && script == null) return
        active = false
        script = null
        shot = null
        StagePuppets.clear()
        for (id in posed) HhaPose.pose(id, Pose.NONE)
        posed.clear()
        for (id in dressed) HhaPose.costume(id, "")
        dressed.clear()
        for (id in lifted) HhaPose.lift(id, 0f, 0f, 0)
        lifted.clear()
        screen?.let { ScreenPass.clear(it) }
        screen = null
        CinePlayer.clearStageFade()
        world = null
    }

    /** Fehler oder fehlende Voraussetzung: wie [stop], einmal geloggt über die Schutzschicht. */
    private fun abort() {
        Hha.LOGGER.warn("Bühne: Skript '{}' abgebrochen, Kamera frei", script?.id)
        stop()
    }

    /** Verbindungsende: die Welt ist fort. */
    fun reset() {
        active = false
        script = null
        shot = null
        posed.clear()
        dressed.clear()
        lifted.clear()
        screen = null
        world = null
        StagePuppets.reset()
        HhaPose.reset()
    }

    // ---------------------------------------------------------------------------------------------
    // Takt
    // ---------------------------------------------------------------------------------------------

    /** Je Client-Tick. */
    fun tick() {
        val client = MinecraftClient.getInstance()
        HhaPose.tick(client.world)
        val s = script ?: return
        // Dimensionswechsel oder Tod: fester Endzustand, Kamera frei.
        val player = client.player
        if (client.world == null || client.world !== world || player == null || player.isDead) {
            stop()
            return
        }
        val ok = FxClient.guarded("stage.tick") { advance(s); true }
        if (ok == null) abort()
    }

    private fun advance(s: StageScript) {
        if (clock >= s.length) {
            // Zeitleiste zu Ende: letztes Bild halten, bis der Server die Szene beendet.
            holding++
            if (!CinePlayer.active || holding > HOLD_MAX_TICKS) stop()
            return
        }
        clock++
        StagePuppets.tick(clock)
        if (returning < 0) {
            fire(s)
            watchLookActor(s)
        }
    }

    /** Feuert alle Spuren bis einschließlich [clock]. */
    private fun fire(s: StageScript) {
        val world = world ?: return
        while (nextCue < s.cues.size && s.cues[nextCue].at <= clock) {
            val cue = s.cues[nextCue++]
            FxClient.guarded("stage.cue.${cue::class.simpleName}") { run(cue, world, s) }
        }
    }

    /** Spuren vor [start] für Nachzügler: nur, was einen Zustand hinterlässt. */
    private fun catchUp(s: StageScript, start: Int) {
        val world = world ?: return
        var music: Music? = null
        while (nextCue < s.cues.size && s.cues[nextCue].at < start) {
            val cue = s.cues[nextCue++]
            when (cue) {
                is PuppetSpawn, is PuppetRemove, is PuppetAct -> FxClient.guarded("stage.catchup") { run(cue, world, s) }
                is Costume -> if (cue.dissolve <= 0 || cue.at + cue.dissolve * HhaPose.DISSOLVE_PEAK > start) {
                    FxClient.guarded("stage.catchup") { costume(cue.on, 0) }
                } else FxClient.guarded("stage.catchup") { costume(false, 0) }
                is PoseCue -> if (cue.ticks <= 0 || cue.at + cue.ticks > start) {
                    FxClient.guarded("stage.catchup") { pose(cue.actor, cue.pose, if (cue.ticks <= 0) 0 else cue.at + cue.ticks - start) }
                }
                is PuppetMove -> if (cue.until > start) FxClient.guarded("stage.catchup") { run(cue, world, s) }
                is Music -> music = cue
                is Mark -> FxClient.guarded("stage.catchup") { liftMark(cue.name, start - cue.at) }
                else -> {}
            }
        }
        music?.let { MusicDirector.force(it.id) }
    }

    private fun run(cue: Cue, world: ClientWorld, s: StageScript) {
        val client = MinecraftClient.getInstance()
        when (cue) {
            is Caption -> {
                val length = net.minecraft.text.Text.translatable(cue.key).string.length
                val ticks = if (cue.ticks > 0) cue.ticks else maxOf((length / CinePlayer.CHARS_PER_TICK).toInt() + CAPTION_EXTRA_TICKS, CAPTION_MIN_TICKS)
                CinePlayer.stageLine(cue.speaker, cue.key, ticks, cue.nameKey, cue.color)
            }
            is Card -> CinePlayer.stageTitle(cue.titleKey, cue.subKey, cue.ticks)
            is Chapter -> CinePlayer.chapter(cue.kickerKey, cue.titleKey, cue.subKey, cue.ticks, cue.color)
            is Fade -> CinePlayer.stageFade(cue.color, cue.from, cue.to, cue.ticks)
            is Flash -> ScreenFx.flash(cue.color, cue.strength.coerceIn(0f, FLASH_MAX), cue.ticks)
            is Kick -> CameraFx.kick(
                cue.pitch.coerceIn(-KICK_MAX, KICK_MAX), cue.yaw.coerceIn(-KICK_MAX, KICK_MAX), cue.roll.coerceIn(-KICK_MAX, KICK_MAX),
                cue.ticks.coerceAtLeast(1),
            )
            is Shake -> CameraFx.shake(cue.strength, (cue.until - cue.at).coerceAtLeast(1))
            is Sound -> {
                val id = Identifier.tryParse(cue.id) ?: return
                client.soundManager.play(
                    PositionedSoundInstance(
                        id, SoundCategory.MASTER, cue.volume, cue.pitch, Random.create(), false, 0,
                        SoundInstance.AttenuationType.NONE, 0.0, 0.0, 0.0, true,
                    ),
                )
            }
            is Music -> MusicDirector.force(cue.id)
            is FxCue -> {
                val pos = resolve(cue.pos, 1f, Vec3d.ZERO)
                val dir = cue.dir?.let { resolve(it, 1f, Vec3d.ZERO) }?.let { target ->
                    if (cue.id in ABSOLUTE_DIR) target else target.subtract(pos).let { if (it.lengthSquared() < 1.0e-8) Vec3d(0.0, 1.0, 0.0) else it.normalize() }
                } ?: Vec3d.ZERO
                FxRegistry.play(world, cue.id, cue.style, pos, dir, cue.a, cue.b, cue.c, cue.d, cue.life, extra = cue.extra)
            }
            is ScreenCue -> {
                val center = resolve(cue.pos, 1f, Vec3d.ZERO)
                ScreenPass.request(
                    cue.kind, center, cue.radius, cue.strength.coerceIn(0f, BossFx.screenMaxStrength(cue.kind)),
                    cue.ticks.coerceIn(1, BossFx.screenMaxTicks(cue.kind)),
                )
                screen = cue.kind
            }
            is PoseCue -> pose(cue.actor, cue.pose, cue.ticks)
            is Costume -> costume(cue.on, cue.dissolve)
            is PuppetSpawn -> StagePuppets.spawn(world, cue.name, cue.entityType, resolve(cue.pos, 1f, Vec3d.ZERO), cue.yaw + yawOfAnchor(), cue.costume)
            is PuppetMove -> StagePuppets.move(cue.name, cue.path.map { resolve(it, 1f, Vec3d.ZERO) }, clock, cue.until, cue.lift)
            is PuppetAct -> StagePuppets.act(cue.name, cue.action)
            is PuppetRemove -> StagePuppets.remove(cue.name)
            is Mark -> liftMark(cue.name, 0)
        }
    }

    /**
     * Marke `pose.lift:<von>:<bis>:<ticks>` (P20, Wunsch 1 in `requests/P17.md`): hebt alle Spieler-Darsteller in der
     * Darstellung von `von` nach `bis` Blöcken über `ticks` (Kurve OUT, `ticks ≤ 1` = sofort); [elapsed] Ticks sind
     * schon vorbei (Nachzügler). Andere Marken gehören dem Server und bleiben hier ohne Wirkung. [stop] setzt auf 0.
     */
    private fun liftMark(name: String, elapsed: Int) {
        if (!name.startsWith(RpgStages.LIFT_PREFIX)) return
        val parts = name.removePrefix(RpgStages.LIFT_PREFIX).split(':')
        val from = parts.getOrNull(0)?.toFloatOrNull()
        val to = parts.getOrNull(1)?.toFloatOrNull()
        val ticks = parts.getOrNull(2)?.toIntOrNull()
        if (from == null || to == null || ticks == null) {
            Hha.LOGGER.warn("Bühne: Marke '{}' in '{}' nicht lesbar", name, script?.id)
            return
        }
        for (id in playerActors(-1)) {
            HhaPose.lift(id, from, to, ticks, elapsed)
            lifted.add(id)
        }
    }

    /** Blick des Ankers in Grad: Puppen-Blick im Skript ist relativ dazu (0 = wie der Anker). */
    private fun yawOfAnchor(): Float = Math.toDegrees(kotlin.math.atan2(-forward.x, forward.z)).toFloat()

    /** Pose für Darsteller [actor] (−1 = alle Spieler-Darsteller). Nur Spieler lassen sich posieren. */
    private fun pose(actor: Int, pose: Pose, ticks: Int) {
        for (id in playerActors(actor)) {
            HhaPose.pose(id, pose, ticks)
            posed.add(id)
        }
    }

    /** Kostüm aller Spieler-Darsteller; das Set kommt aus dem RPG-Weg (sonst Himmel). */
    private fun costume(on: Boolean, dissolve: Int) {
        val set = RpgClientState.path.ifEmpty { "heaven" }
        for (id in playerActors(-1)) {
            if (on) {
                HhaPose.costume(id, set)
                dressed.add(id)
            } else if (dissolve > 0) {
                HhaPose.dissolve(id, dissolve)
            } else {
                HhaPose.costume(id, "")
            }
        }
    }

    private fun playerActors(actor: Int): List<Int> {
        val world = world ?: return emptyList()
        val ids = if (actor < 0) actors else listOfNotNull(actors.getOrNull(actor))
        return ids.filter { world.getEntityById(it) is PlayerEntity }
    }

    /** Fehlt der Blick-Darsteller der laufenden Einstellung zu lange, fährt die Kamera in die Augen zurück. */
    private fun watchLookActor(s: StageScript) {
        val current = s.shotAt(clock.coerceAtMost(s.length - 1)) ?: return
        val refs = current.look.mapNotNull { lookRef(it) }
        if (refs.isEmpty()) {
            lookMissing = 0
            return
        }
        val present = refs.any { it() != null }
        if (present) {
            lookSeen = true
            lookMissing = 0
        } else if (lookSeen && ++lookMissing > MISSING_TICKS) {
            Hha.LOGGER.info("Bühne: Darsteller in '{}' verschwunden, Kamera zurück in die Augen", s.id)
            returning = clock
            snapCam = lastCam
            snapLook = lastLook
            snapFov = lastFov
        }
    }

    private fun lookRef(key: Key): (() -> Entity?)? = when (key) {
        is Key.Actor -> { { actorEntity(key.index) } }
        is Key.ActorRel -> { { actorEntity(key.index) } }
        is Key.Puppet -> { { StagePuppets.find(key.name) } }
        is Key.PuppetRel -> { { StagePuppets.find(key.name) } }
        else -> null
    }

    private fun actorEntity(index: Int): Entity? = actors.getOrNull(index)?.let { world?.getEntityById(it) }

    // ---------------------------------------------------------------------------------------------
    // Kamera
    // ---------------------------------------------------------------------------------------------

    /** Kamera des laufenden Skripts oder null. Ohne Welt (Menü) spielt die Bühne nicht. */
    fun cameraPose(tickDelta: Float): CinePlayer.CameraPose? {
        if (!active || MinecraftClient.getInstance().world == null) return null
        val result = FxClient.guarded("stage.camera") { Box(computeCamera(tickDelta)) }
        if (result == null) {
            abort()
            return null
        }
        return result.value
    }

    private class Box<T>(val value: T)

    /** Sichtfeld des laufenden Skripts oder null (dann gilt das bisherige). */
    fun fov(vanilla: Float, tickDelta: Float): Float? {
        if (!active || !hasFrame) return null
        eyeFov = vanilla
        return lastFov
    }

    /** Sichtfeld des Spielers (für Fahrten zurück in die Augen); vom letzten [fov]-Aufruf. */
    private var eyeFov = 70f

    private fun computeCamera(tickDelta: Float): CinePlayer.CameraPose? {
        val s = script ?: return null
        val client = MinecraftClient.getInstance()
        val t = (clock + if (clock < s.length) tickDelta else 0f).coerceAtMost(s.length.toFloat())

        if (returning >= 0) return returnToEyes(t, tickDelta)

        val current = s.shotAt(t.toInt().coerceAtMost(s.length - 1)) ?: return null
        if (current !== shot) {
            shot = current
            val eye = client.player?.getCameraPosVec(tickDelta) ?: anchor
            snapCam = if (hasFrame) lastCam else eye
            snapLook = if (hasFrame) lastLook else eye.add(forward)
            snapFov = if (hasFrame) lastFov else eyeFov
            if (current.cut || !hasFrame) {
                follow.reset()
                clip.reset()
            }
        }
        val raw = ((t - current.from) / (current.to - current.from).toFloat()).coerceIn(0f, 1f)
        val k = CameraCraft.ease(current.ease, raw).toDouble()
        val lookRaw = path(current.look, k, tickDelta, snapLook, null, false)
        var cam = path(current.cam, k, tickDelta, snapCam, lookRaw, current.arc)
        val look = follow.apply(lookRaw, current.follow)
        // Augen und Rückweg gehören dem Spieler: dort kein Kollisionsschutz.
        if (current.cam.none { it == Key.Eye }) cam = clip.apply(look, cam)

        val toEye = current.cam.last() == Key.Eye
        val fovFrom = if (current.cam.first() == Key.Last) snapFov else current.fov.first
        val fovTo = if (toEye) eyeFov else current.fov.second
        val fov = fovFrom + (fovTo - fovFrom) * k.toFloat()
        return frame(cam, look, fov, current.handheld)
    }

    private fun returnToEyes(t: Float, tickDelta: Float): CinePlayer.CameraPose {
        val client = MinecraftClient.getInstance()
        val k = CameraCraft.ease(dev.henny.hha.stage.EaseKind.SMOOTH, ((t - returning) / RETURN_TICKS).coerceIn(0f, 1f)).toDouble()
        val eye = client.player?.getCameraPosVec(tickDelta) ?: snapCam
        val eyeLook = client.player?.let { it.getCameraPosVec(tickDelta).add(it.getRotationVec(tickDelta).multiply(16.0)) } ?: snapLook
        val cam = snapCam.lerp(eye, k)
        val look = snapLook.lerp(eyeLook, k)
        return frame(cam, look, snapFov + (eyeFov - snapFov) * k.toFloat(), 0f)
    }

    private fun frame(cam: Vec3d, look: Vec3d, fov: Float, handheld: Float): CinePlayer.CameraPose {
        lastCam = cam
        lastLook = look
        lastFov = fov
        hasFrame = true
        val (dy, dp) = CameraCraft.handheld(CinePlayer.craftSeconds(), handheld)
        return CinePlayer.CameraPose(cam, CameraCraft.yawOf(cam, look) + dy, CameraCraft.pitchOf(cam, look) + dp)
    }

    /**
     * Punkt auf einer Fahrt durch [keys] bei [k]: 1 Punkt fest, 2 Punkte Gerade (mit [arc] Bogen um [center]), ab 3
     * Catmull-Rom mit Bogenlänge (k auf 0..1 begrenzt; `OUT_BACK` schießt nur bei Geraden und Bögen über).
     */
    private fun path(keys: List<Key>, k: Double, tickDelta: Float, snapshot: Vec3d, center: Vec3d?, arc: Boolean): Vec3d {
        val points = keys.map { resolve(it, tickDelta, snapshot) }
        return when (points.size) {
            1 -> points[0]
            2 -> if (arc && center != null) CameraCraft.arcAround(points[0], points[1], center, k) else points[0].lerp(points[1], k)
            else -> CameraCraft.spline(points, k.coerceIn(0.0, 1.0))
        }
    }

    /** Löst einen [Key] zur Zeit des Bildes auf. Fehlende Darsteller/Puppen: zuletzt bekannte Lage, sonst Anker. */
    private fun resolve(key: Key, tickDelta: Float, snapshot: Vec3d): Vec3d {
        val client = MinecraftClient.getInstance()
        return when (key) {
            is Key.At -> at(anchor, forward, right, key.f, key.r, key.u)
            is Key.World -> Vec3d(key.x, key.y, key.z)
            is Key.Actor -> actorPos(key.index, tickDelta).add(0.0, key.up, 0.0)
            is Key.ActorRel -> {
                val base = actorPos(key.index, tickDelta)
                val (f, r) = axes(lastActorYaw[key.index])
                at(base, f, r, key.f, key.r, key.u)
            }
            is Key.Puppet -> puppetPos(key.name, tickDelta).add(0.0, key.up, 0.0)
            is Key.PuppetRel -> {
                val base = puppetPos(key.name, tickDelta)
                val (f, r) = axes(lastPuppetYaw[key.name])
                at(base, f, r, key.f, key.r, key.u)
            }
            is Key.Group -> group(tickDelta).add(0.0, key.up, 0.0)
            Key.Eye -> client.player?.getCameraPosVec(tickDelta) ?: snapshot
            Key.EyeLook -> client.player?.let { it.getCameraPosVec(tickDelta).add(it.getRotationVec(tickDelta).multiply(16.0)) } ?: snapshot
            Key.Last -> snapshot
        }
    }

    private fun at(base: Vec3d, f: Vec3d, r: Vec3d, df: Double, dr: Double, du: Double): Vec3d =
        base.add(f.multiply(df)).add(r.multiply(dr)).add(0.0, du, 0.0)

    /** Vorn/rechts aus einem Blick (Grad); ohne Blick die Achsen des Ankers. */
    private fun axes(yaw: Float?): Pair<Vec3d, Vec3d> {
        if (yaw == null) return forward to right
        val rad = Math.toRadians(yaw.toDouble())
        return Vec3d(-sin(rad), 0.0, cos(rad)) to Vec3d(-cos(rad), 0.0, -sin(rad))
    }

    private fun actorPos(index: Int, tickDelta: Float): Vec3d {
        val entity = actorEntity(index)
        if (entity != null) {
            // Schwebende Darsteller (pose.lift): die Kamera filmt die Figur, nicht den Boden darunter.
            lastActorPos[index] = entity.getLerpedPos(tickDelta).add(0.0, HhaPose.liftOf(entity.id, tickDelta).toDouble(), 0.0)
            lastActorYaw[index] = facing(entity, tickDelta)
        }
        return lastActorPos[index] ?: anchor
    }

    private fun puppetPos(name: String, tickDelta: Float): Vec3d {
        val entity = StagePuppets.find(name)
        if (entity != null) {
            lastPuppetPos[name] = entity.getLerpedPos(tickDelta)
            lastPuppetYaw[name] = facing(entity, tickDelta)
        }
        return lastPuppetPos[name] ?: anchor
    }

    /** Blick eines Wesens: Körperdrehung bei Lebewesen (der Kopf schaut oft zur Seite), sonst `yaw`. */
    private fun facing(entity: Entity, tickDelta: Float): Float =
        if (entity is net.minecraft.entity.LivingEntity) net.minecraft.util.math.MathHelper.lerpAngleDegrees(tickDelta, entity.lastBodyYaw, entity.bodyYaw)
        else entity.getYaw(tickDelta)

    /** Schwerpunkt der Spieler-Darsteller; ohne Spieler Darsteller 0, sonst der Anker. */
    private fun group(tickDelta: Float): Vec3d {
        val world = world ?: return anchor
        var sum = Vec3d.ZERO
        var n = 0
        for (id in actors) {
            val e = world.getEntityById(id) as? PlayerEntity ?: continue
            sum = sum.add(e.getLerpedPos(tickDelta)).add(0.0, HhaPose.liftOf(id, tickDelta).toDouble(), 0.0)
            n++
        }
        if (n > 0) return sum.multiply(1.0 / n)
        return if (actors.isNotEmpty()) actorPos(0, tickDelta) else anchor
    }

    /** Für die Prüfung von außen (Befehle, Bericht): ID des laufenden Skripts. */
    fun currentId(): String? = script?.id

    @Suppress("unused")
    private fun near(a: Float, b: Float) = abs(a - b) < 1.0e-3f
}
