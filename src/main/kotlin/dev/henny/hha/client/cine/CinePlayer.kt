package dev.henny.hha.client.cine

import dev.henny.hha.HhaSounds
import dev.henny.hha.client.HudLine
import dev.henny.hha.client.audio.MusicDirector
import dev.henny.hha.client.cine.stage.CameraCraft
import dev.henny.hha.client.cine.stage.StagePlayer
import dev.henny.hha.client.vfx.fx.FxClient
import dev.henny.hha.client.gui.Ui
import dev.henny.hha.client.vfx.SkyMood
import dev.henny.hha.client.vfx.easeInOut
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.net.CinePayload
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.option.Perspective
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.render.Camera
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.text.Text
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin

/**
 * Spielt die vom Server geschickte Cutscene ab: Kamerafahrten, Letterbox, Dialogbox mit Schreibmaschinentext,
 * Blenden und Titelkarten. Die Eingaben des Spielers sind so lange gesperrt (siehe client-Mixins).
 */
object CinePlayer {

    private sealed class Shot(val ticks: Int, val fovFrom: Double, val fovTo: Double, val lookAtId: Int, val lookOffset: Double)

    private class LineShot(
        val from: Vec3d, val to: Vec3d, val lookFrom: Vec3d, val lookTo: Vec3d,
        ticks: Int, fovFrom: Double, fovTo: Double, lookAtId: Int, lookOffset: Double,
    ) : Shot(ticks, fovFrom, fovTo, lookAtId, lookOffset)

    private class OrbitShot(
        val center: Vec3d, val radiusFrom: Double, val radiusTo: Double, val heightFrom: Double, val heightTo: Double,
        val angleFrom: Double, val angleTo: Double,
        ticks: Int, fovFrom: Double, fovTo: Double, lookAtId: Int, lookOffset: Double,
    ) : Shot(ticks, fovFrom, fovTo, lookAtId, lookOffset)

    /** [nameKey]/[color]: optionaler Name und Namensfarbe aus `Cine.dialog` (Überarbeitung 3); leer = Sprecher. */
    private class Line(val speaker: Int, val text: Text, val ticks: Int, val nameKey: String = "", val color: Int = 0) {
        var age = 0
    }

    /** [color] 0xRRGGBB für Titel und Lichtlinie (0 = weiß wie bisher). */
    private class TitleCard(val title: Text, val subtitle: Text, val ticks: Int, val color: Int = 0) {
        var age = 0
    }

    /** Kapitelkarte (Überarbeitung 3): Kicker, Titel, Lichtlinie, Untertitel in der Wegfarbe [color]. */
    private class ChapterCard(val kicker: Text, val title: Text, val subtitle: Text, val ticks: Int, val color: Int) {
        var age = 0
    }

    /** Läuft gerade eine Cutscene? Sperrt Eingaben und ersetzt die Kamera. */
    @JvmStatic
    var active = false
        private set

    /** Stage 4: der Spieler hat Hermes' Feder und darf doppelt springen. */
    @JvmStatic
    var messengerWings = false
        private set

    private var shot: Shot? = null
    private var shotAge = 0
    private var line: Line? = null
    private var title: TitleCard? = null
    private var chapterCard: ChapterCard? = null
    private var previousPerspective: Perspective? = null
    private var bars = 0f

    private var fadeColor = 0
    private var fadeFrom = 0.0
    private var fadeTo = 0.0
    private var fadeTicks = 0
    private var fadeAge = 0

    fun handle(payload: CinePayload) {
        val n = payload.numbers
        when (payload.op) {
            Cine.OP_START -> begin()
            Cine.OP_END -> finish()
            Cine.OP_RESET -> reset()
            Cine.OP_CAMERA -> {
                shot = LineShot(
                    Vec3d(n[0], n[1], n[2]), Vec3d(n[3], n[4], n[5]), Vec3d(n[6], n[7], n[8]), Vec3d(n[9], n[10], n[11]),
                    n[12].toInt(), n[13], n[14], n[15].toInt(), n[16],
                )
                newShot()
            }
            Cine.OP_ORBIT -> {
                shot = OrbitShot(
                    Vec3d(n[0], n[1], n[2]), n[3], n[4], n[5], n[6], n[7], n[8],
                    n[9].toInt(), n[10], n[11], n[12].toInt(), n[13],
                )
                newShot()
            }
            Cine.OP_DIALOG -> showLine(
                n[0].toInt(), payload.texts[0], n[1].toInt(), payload.texts.getOrNull(1) ?: "", n.getOrNull(2)?.toInt() ?: 0,
            )
            Cine.OP_FADE -> {
                stageFade = false
                fadeColor = n[0].toInt()
                fadeFrom = n[1]
                fadeTo = n[2]
                fadeTicks = n[3].toInt().coerceAtLeast(1)
                fadeAge = 0
            }
            Cine.OP_TITLE -> title = TitleCard(
                Text.translatable(payload.texts[0]), Text.translatable(payload.texts[1]), n[0].toInt(), n.getOrNull(1)?.toInt() ?: 0,
            )
            Cine.OP_ATMOSPHERE -> {
                val style = n[0].toInt()
                if (style < 0) SkyMood.clearArena()
                else SkyMood.setArena(Vfx.Style.entries[style], Vec3d(n[1], n[2], n[3]), n[4].toInt())
            }
            Cine.OP_WINGS -> messengerWings = n[0] > 0.5
            Cine.OP_MUSIC -> setTheme(payload.texts[0])
            // Gerüst P00 (Überarbeitung 3): neue Ops an Bühne, Kapitelkarte und Statuszeile weiterreichen.
            Cine.OP_STAGE -> {
                val count = n[5].toInt()
                StagePlayer.play(payload.texts[0], Vec3d(n[0], n[1], n[2]), n[3].toFloat(), List(count) { n[6 + it].toInt() }, n[4].toInt())
            }
            Cine.OP_STAGE_STOP -> StagePlayer.stop()
            Cine.OP_CHAPTER -> chapter(payload.texts[0], payload.texts[1], payload.texts[2], n[0].toInt(), n[1].toInt())
            Cine.OP_HUD_LINE -> HudLine.show(
                Text.translatable(payload.texts[0], *payload.texts.drop(1).toTypedArray()), n[0].toInt(), n[1].toInt(),
            )
            Cine.OP_CLEAR_VFX -> dev.henny.hha.client.vfx.VfxManager.clear()
            Cine.OP_CUT -> cut()
            Cine.OP_PHASE_BAR -> PhaseBar.handle(n, payload.texts)
            Cine.OP_SUB_BARS -> PhaseBar.handleSub(n, payload.texts)
            Cine.OP_SKIP_VOTES -> {
                skipVotes = n[0].toInt()
                skipTotal = n[1].toInt()
            }
        }
    }

    /**
     * Gerüst P00 (Überarbeitung 3): Das Bossthema läuft jetzt über [MusicDirector.force] (dorthin unverändert
     * verschoben: harter Wechsel, Schleife, Vanilla-Musik schweigt). P04 blendet dort über.
     */
    private fun setTheme(id: String) = MusicDirector.force(id)

    // ---------------------------------------------------------------------------------------------
    // Gerüst P00 (Überarbeitung 3, PLAN 6.5): Haken für Bühne, Lobby-Tunnel und Modelle. Besitzer danach P05.
    // ---------------------------------------------------------------------------------------------

    /**
     * Wird in [renderOverlay] vor Letterbox und Text gezeichnet (P18 hängt dort den Tunnel ein). Läuft hinter
     * [FxClient.guarded].
     */
    var underlay: ((DrawContext, Float) -> Unit)? = null

    /** Sperrt Eingaben auch ohne Szene (Halte der RPG-Ankunft). Muss in jedem Ausgang wieder fallen. */
    @JvmStatic
    var externalLock = false

    /** Sind Eingaben gesperrt? Die Eingabe-Mixins fragen das statt [active]. */
    @JvmStatic
    fun isInputLocked(): Boolean = active || externalLock

    private val speakerTypes = HashMap<Int, EntityType<*>>()

    /** Ordnet einem Sprecher (`Cine.SPEAKER_*`) den Entity-Typ zu, dessen Modell den Mund bewegt. */
    fun speakerType(speaker: Int, type: EntityType<*>) {
        speakerTypes[speaker] = type
    }

    /**
     * Mundöffnung 0..1 für das Wesen [entityId] (Blood `mouth`): Spricht gerade der Sprecher, dessen Entity-Typ
     * ([speakerType]) das Wesen hat, öffnen Vokale weit, andere Buchstaben halb, Pausen und Satzzeichen schließen.
     * Sonst 0. Die Modelle bewegen damit Kiefer oder Mund.
     */
    @JvmStatic
    fun mouthOf(entityId: Int): Float {
        val l = line ?: return 0f
        val type = speakerTypes[l.speaker] ?: return 0f
        val entity = MinecraftClient.getInstance().world?.getEntityById(entityId) ?: return 0f
        if (entity.type != type) return 0f
        val full = l.text.string
        val n = visibleChars(l)
        if (n <= 0 || n >= full.length) return 0f
        val c = full[n - 1].lowercaseChar()
        val open = when {
            c in MOUTH_VOWELS -> 1f
            c.isLetter() -> 0.45f
            else -> 0.05f
        }
        return open * (0.8f + 0.2f * sin(l.age * 1.7f))
    }

    /**
     * Kapitelkarte (`Cine.OP_CHAPTER` und Bühnen-Spur `chapter`): Kicker in `hha:label`, Titel in `hha:display`,
     * Lichtlinie und Untertitel, alles in der Wegfarbe [color] (0xRRGGBB, 0 = Gold); Ton `rpg.chapter`. Läuft auch
     * außerhalb von Cutscenes (über dem HUD).
     */
    fun chapter(kickerKey: String, titleKey: String, subKey: String, ticks: Int, color: Int) {
        chapterCard = ChapterCard(
            Text.translatable(kickerKey), Text.translatable(titleKey), Text.translatable(subKey), ticks.coerceAtLeast(20),
            if (color == 0) Ui.GOLD and 0xFFFFFF else color and 0xFFFFFF,
        )
        MinecraftClient.getInstance().soundManager.play(PositionedSoundInstance.ui(HhaSounds.RPG_CHAPTER, 1f, 0.8f))
    }

    // ---------------------------------------------------------------------------------------------
    // Eingänge der Bühne (StagePlayer, P05). Gleiche Anzeige wie die Ops; nur die Quelle ist die Zeitleiste.
    // ---------------------------------------------------------------------------------------------

    /** Zeile aus der Bühnen-Spur `caption` (gleiche Dialogbox, Stimme und Mindestdauer wie `OP_DIALOG`). */
    fun stageLine(speaker: Int, key: String, ticks: Int, nameKey: String, color: Int) = showLine(speaker, key, ticks, nameKey, color)

    /** Titelkarte aus der Bühnen-Spur `card`. */
    fun stageTitle(titleKey: String, subKey: String, ticks: Int, color: Int = 0) {
        title = TitleCard(Text.translatable(titleKey), Text.translatable(subKey), ticks, color)
    }

    /** Blende aus der Bühnen-Spur `fade`; [clearStageFade] nimmt sie beim Szenenende weich weg. */
    fun stageFade(color: Int, from: Float, to: Float, ticks: Int) {
        fadeColor = color
        fadeFrom = from.toDouble()
        fadeTo = to.toDouble()
        fadeTicks = ticks.coerceAtLeast(1)
        fadeAge = 0
        stageFade = true
    }

    /**
     * Fester Endzustand der Bühne: Hängt noch eine Blende der Bühne (z. B. Abbruch mitten im Schwarz), blendet sie in
     * [STAGE_FADE_CLEAR_TICKS] aus. Blenden des Servers (`OP_FADE`) bleiben unberührt.
     */
    fun clearStageFade() {
        if (!stageFade) return
        stageFade = false
        val alpha = currentFade(0f)
        fadeFrom = alpha.toDouble()
        fadeTo = 0.0
        fadeTicks = if (alpha > 0.001f) STAGE_FADE_CLEAR_TICKS else 0
        fadeAge = 0
    }

    /** Stammt die laufende Blende von der Bühne? */
    private var stageFade = false

    private fun currentFade(tickDelta: Float): Float {
        if (fadeTicks <= 0) return 0f
        val t = ((fadeAge + tickDelta) / fadeTicks).coerceIn(0f, 1f).toDouble()
        return MathHelper.lerp(t, fadeFrom, fadeTo).toFloat()
    }

    /** Zeit der Handkamera in Sekunden (stetig, für Bühne und alte Einstellungen gleich). */
    fun craftSeconds(): Double = System.nanoTime() / 1.0e9

    /**
     * Neue Zeile in der Dialogbox: Mindestdauer = so lange, bis der Text getippt ist, plus [LINE_READ_EXTRA] Ticks
     * zum Lesen (Blood), nie kürzer als vom Server verlangt. Dazu die Sprachaufnahme, falls es eine gibt.
     */
    private fun showLine(speaker: Int, key: String, ticks: Int, nameKey: String, color: Int) {
        val text = Text.translatable(key)
        val needed = (text.string.length / CHARS_PER_TICK).toInt() + LINE_READ_EXTRA
        line = Line(speaker, text, maxOf(ticks, needed), nameKey, color)
        speak(key)
    }

    /** Die Taste fürs Überspringen, von HhaClient gesetzt. */
    var skipKey: net.minecraft.client.option.KeyBinding? = null
    private var skipHeld = 0
    private var skipSent = false
    private var skipVotes = 0
    private var skipTotal = 0

    private fun tickSkip() {
        val key = skipKey
        val client = MinecraftClient.getInstance()
        // Der Tastendruck zählt nur über das Halten. Aufgelaufene Klicks verwerfen, sonst löst ein alter Druck später aus.
        while (key?.wasPressed() == true) Unit
        val held = active && key != null && client.currentScreen == null && key.isPressed
        if (!held) {
            skipHeld = 0
            skipSent = false
            return
        }
        if (skipHeld < SKIP_HOLD_TICKS) skipHeld++
        if (skipHeld >= SKIP_HOLD_TICKS && !skipSent) {
            skipSent = true
            net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(dev.henny.hha.net.CineSkipPayload)
        }
    }

    /** Die Szene wurde übersprungen: alles Laufende aus, und aus Schwarz wieder aufblenden. */
    private fun cut() {
        StagePlayer.stop()
        val client = MinecraftClient.getInstance()
        voice?.let { client.soundManager.stop(it) }
        voice = null
        speaking = false
        line = null
        title = null
        skipVotes = 0
        skipTotal = 0
        dev.henny.hha.client.vfx.VfxManager.clear()
        stageFade = false
        fadeColor = 0
        fadeFrom = 1.0
        fadeTo = 0.0
        fadeTicks = 16
        fadeAge = 0
    }

    private fun begin() {
        val client = MinecraftClient.getInstance()
        if (!active) previousPerspective = client.options.perspective
        client.options.perspective = Perspective.THIRD_PERSON_BACK
        active = true
        shot = null
        oldFollow.reset()
        oldClip.reset()
        skipVotes = 0
        skipTotal = 0
    }

    private fun finish() {
        StagePlayer.stop()
        val client = MinecraftClient.getInstance()
        previousPerspective?.let { client.options.perspective = it }
        previousPerspective = null
        val wasActive = active
        active = false
        shot = null
        line = null
        // Was während der Szene gedrückt wurde, soll danach nichts auslösen (Void `VoidCutscene.stop`).
        if (wasActive) KeyBinding.unpressAll()
    }

    fun reset() {
        finish()
        externalLock = false
        // Auch die laufende Sprachzeile und die Effekte der Szene enden (Ausscheiden, Verbindungsende).
        voice?.let { MinecraftClient.getInstance().soundManager.stop(it) }
        voice = null
        speaking = false
        skipVotes = 0
        skipTotal = 0
        dev.henny.hha.client.vfx.VfxManager.clear()
        messengerWings = false
        setTheme("")
        title = null
        chapterCard = null
        stageFade = false
        fadeTicks = 0
        fadeFrom = 0.0
        fadeTo = 0.0
        bars = 0f
        PhaseBar.reset()
    }

    fun tick() {
        PhaseBar.tick()
        // Vanilla-Musik schweigt während eines Bossthemas: jetzt in MusicDirector.tick (Gerüst P00).
        bars = (bars + if (active) 0.08f else -0.08f).coerceIn(0f, 1f)
        tickSkip()
        if (shot != null) shotAge++
        line?.let {
            val before = visibleChars(it)
            it.age++
            val after = visibleChars(it)
            if (!speaking && after > before && after % 2 == 0 && after < it.text.string.length) playVoice(it.speaker)
            if (it.age > it.ticks + 6) line = null
        }
        title?.let {
            it.age++
            if (it.age > it.ticks) title = null
        }
        chapterCard?.let {
            it.age++
            if (it.age > it.ticks) chapterCard = null
        }
        if (fadeAge < fadeTicks) fadeAge++
    }

    private fun visibleChars(l: Line): Int = (l.age * CHARS_PER_TICK).toInt().coerceAtMost(l.text.string.length)

    private var voice: net.minecraft.client.sound.SoundInstance? = null

    /** Läuft gerade eine echte Sprachaufnahme? Dann schweigen die Silben-Blips. */
    private var speaking = false

    /** Spielt die Aufnahme zu einer Zeile, falls es für die Spielsprache eine gibt. Sonst bleibt es bei den Blips. */
    private fun speak(lineKey: String) {
        val client = MinecraftClient.getInstance()
        voice?.let { client.soundManager.stop(it) }
        voice = null
        speaking = false
        val preferred = if (client.options.language.startsWith("de")) "de" else "en"
        val name = lineKey.removePrefix("hha.hermes.line.").lowercase().replace(Regex("[^a-z0-9_]"), "_")
        // Fehlt die Aufnahme in der Spielsprache, ist die andere Sprache immer noch besser als Stille.
        val id = listOf(preferred, if (preferred == "de") "en" else "de")
            .map { dev.henny.hha.Hha.id("voice.$it.$name") }
            .firstOrNull { client.soundManager.get(it) != null } ?: return
        val instance = net.minecraft.client.sound.PositionedSoundInstance(
            id, net.minecraft.sound.SoundCategory.VOICE, 1f, 1f, net.minecraft.util.math.random.Random.create(), false, 0,
            net.minecraft.client.sound.SoundInstance.AttenuationType.NONE, 0.0, 0.0, 0.0, true,
        )
        client.soundManager.play(instance)
        voice = instance
        speaking = true
    }

    private fun playVoice(speaker: Int) {
        val client = MinecraftClient.getInstance()
        val pitch = when (speaker) {
            Cine.SPEAKER_HERMES -> 1.05f + (System.nanoTime() % 5L) * 0.03f
            Cine.SPEAKER_ZEUS -> 0.5f + (System.nanoTime() % 3L) * 0.02f
            Cine.SPEAKER_SHADE -> 0.72f + (System.nanoTime() % 4L) * 0.02f
            Cine.SPEAKER_HADES -> 0.46f + (System.nanoTime() % 3L) * 0.02f
            Cine.SPEAKER_KRONOS -> 0.36f + (System.nanoTime() % 3L) * 0.02f
            Cine.SPEAKER_HEKATE -> 0.8f + (System.nanoTime() % 4L) * 0.02f
            Cine.SPEAKER_BRONTES -> 0.40f + (System.nanoTime() % 3L) * 0.02f
            Cine.SPEAKER_KRATOS -> 0.55f + (System.nanoTime() % 3L) * 0.02f
            else -> return
        }
        client.soundManager.play(PositionedSoundInstance.ui(HhaSounds.VOICE, pitch, if (speaker == Cine.SPEAKER_ZEUS) 0.9f else 0.45f))
    }

    private fun smoothShot(tickDelta: Float): Float {
        val s = shot ?: return 0f
        return easeInOut(((shotAge + tickDelta) / s.ticks.coerceAtLeast(1)))
    }

    /** Wird am Ende von [Camera.update] aufgerufen; liefert Position und Blick, oder null ohne Cutscene. */
    @JvmStatic
    fun cameraPose(tickDelta: Float): CameraPose? {
        // Gerüst P00: Läuft ein Bühnen-Skript, kommt die Kamera von dort.
        if (active && StagePlayer.active) StagePlayer.cameraPose(tickDelta)?.let { return it }
        val s = shot ?: return null
        if (!active) return null
        val world = MinecraftClient.getInstance().world ?: return null
        val t = smoothShot(tickDelta).toDouble()
        val lookEntity: Entity? = if (s.lookAtId >= 0) world.getEntityById(s.lookAtId) else null
        val pos: Vec3d
        val look: Vec3d
        when (s) {
            is LineShot -> {
                pos = s.from.lerp(s.to, t)
                look = lookEntity?.getLerpedPos(tickDelta)?.add(0.0, s.lookOffset, 0.0) ?: s.lookFrom.lerp(s.lookTo, t)
            }
            is OrbitShot -> {
                val a = Math.toRadians(MathHelper.lerp(t, s.angleFrom, s.angleTo))
                val r = MathHelper.lerp(t, s.radiusFrom, s.radiusTo)
                val h = MathHelper.lerp(t, s.heightFrom, s.heightTo)
                pos = s.center.add(cos(a) * r, h, sin(a) * r)
                look = lookEntity?.getLerpedPos(tickDelta)?.add(0.0, s.lookOffset, 0.0) ?: s.center.add(0.0, s.lookOffset, 0.0)
            }
        }
        // Kamera-Handwerk (Überarbeitung 3, wie die Bühne): Der Blick folgt einem bewegten Wesen mit kleiner
        // Verzögerung, die Kamera geht nie durch Säulen, und sie wird ganz leicht von Hand gehalten.
        val aim = if (lookEntity != null) oldFollow.apply(look, CameraCraft.FOLLOW_DEFAULT) else look.also { oldFollow.reset() }
        val cam = oldClip.apply(aim, pos)
        val (dy, dp) = CameraCraft.handheld(craftSeconds(), 1f)
        return CameraPose(cam, CameraCraft.yawOf(cam, aim) + dy, CameraCraft.pitchOf(cam, aim) + dp)
    }

    /** Halter des Handwerks für die alten Einstellungen; jede neue Einstellung beginnt genau auf dem Ziel. */
    private val oldFollow = CameraCraft.Follow()
    private val oldClip = CameraCraft.Clip()

    private fun newShot() {
        shotAge = 0
        oldFollow.reset()
        oldClip.reset()
    }

    class CameraPose(@JvmField val pos: Vec3d, @JvmField val yaw: Float, @JvmField val pitch: Float)

    /** Überschreibt das Sichtfeld, solange eine Kamerafahrt läuft. */
    @JvmStatic
    fun fov(vanilla: Float, tickDelta: Float): Float {
        if (active && StagePlayer.active) StagePlayer.fov(vanilla, tickDelta)?.let { return it }
        val s = shot ?: return vanilla
        if (!active) return vanilla
        return MathHelper.lerp(smoothShot(tickDelta).toDouble(), s.fovFrom, s.fovTo).toFloat()
    }

    /** Letterbox, Dialog, Blende und Titelkarte. */
    @JvmStatic
    fun renderOverlay(context: DrawContext, tickDelta: Float) {
        val client = MinecraftClient.getInstance()
        val width = client.window.scaledWidth
        val height = client.window.scaledHeight
        underlay?.let { draw -> FxClient.guarded("cine.underlay") { draw(context, tickDelta) } }
        if (bars > 0f) {
            val barHeight = (height * 0.11f * easeInOut(bars)).toInt()
            context.fill(0, 0, width, barHeight, 0xFF000000.toInt())
            context.fill(0, height - barHeight, width, height, 0xFF000000.toInt())
        }
        line?.let { renderLine(context, it, width, height) }
        title?.let { renderTitle(context, it, width, height, tickDelta) }
        chapterCard?.let { renderChapter(context, it, width, height, tickDelta) }
        if (active) renderSkipHint(context, width, height, tickDelta)
        if (fadeTicks > 0) {
            val alpha = currentFade(tickDelta)
            if (alpha > 0.001f) {
                context.fill(0, 0, width, height, ((alpha * 255f).toInt().coerceIn(0, 255) shl 24) or (fadeColor and 0xFFFFFF))
            }
        }
        if (active) dev.henny.hha.client.vfx.ScreenFx.renderOverlay(context)
        else PhaseBar.render(context, tickDelta)
    }

    /** Rechts unten in der Letterbox: welche Taste, wie lange noch, und wer schon überspringen will (als Etikett). */
    private fun renderSkipHint(context: DrawContext, width: Int, height: Int, tickDelta: Float) {
        val key = skipKey ?: return
        if (bars < 0.6f || key.isUnbound) return
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val raw = if (skipTotal > 1) Text.translatable("hha.cine.skip.votes", key.boundKeyLocalizedText, skipVotes, skipTotal)
        else Text.translatable("hha.cine.skip", key.boundKeyLocalizedText)
        val text = Ui.labelText(raw.string)
        val textWidth = font.getWidth(text)
        val x = width - textWidth - 10
        val y = height - 14
        context.drawText(font, text, x, y, 0xB0A1A1AA.toInt(), false)
        if (skipHeld > 0) {
            val progress = ((skipHeld + (if (skipHeld < SKIP_HOLD_TICKS) tickDelta else 0f)) / SKIP_HOLD_TICKS).coerceIn(0f, 1f)
            context.fill(x, y + 9, x + textWidth - 1, y + 10, 0x30FFFFFF)
            context.fill(x, y + 9, x + ((textWidth - 1) * progress).toInt(), y + 10, Ui.GOLD)
        }
    }

    /**
     * Untertitel im Stil der Website-Hinweise (.trick): dunkle Platte mit feinem Rand, links ein 2-px-Streifen in der
     * Farbe des Sprechers, darüber der Name als Etikett, darunter der Text in der Spielschrift.
     */
    private fun renderLine(context: DrawContext, l: Line, width: Int, height: Int) {
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val fadeOut = ((l.ticks + 6 - l.age) / 6f).coerceIn(0f, 1f)
        val a = ((l.age / 4f).coerceAtMost(1f) * fadeOut).coerceIn(0.02f, 1f)
        val alpha = (a * 255f).toInt().coerceIn(4, 255) shl 24
        val full = l.text.string
        val shown = full.substring(0, visibleChars(l))
        val boxWidth = (width * 0.72f).toInt().coerceAtMost(420)
        val lines = font.wrapLines(Text.literal(shown), boxWidth)
        val fullLines = font.wrapLines(l.text, boxWidth).size
        val speaker = if (l.nameKey.isNotEmpty()) Text.translatable(l.nameKey) to (if (l.color != 0) l.color and 0xFFFFFF else 0xF4F4F5) else when (l.speaker) {
            Cine.SPEAKER_HERMES -> Text.translatable("hha.hermes.speaker.hermes") to 0xFFD75A
            Cine.SPEAKER_ZEUS -> Text.translatable("hha.hermes.speaker.zeus") to 0xA8D8FF
            Cine.SPEAKER_SHADE -> Text.translatable("hha.hermes.speaker.shade") to 0x62F27A
            Cine.SPEAKER_HADES -> Text.translatable("hha.hermes.speaker.hades") to 0x9CFFB0
            Cine.SPEAKER_KRONOS -> Text.translatable("hha.hermes.speaker.kronos") to 0xFFC86A
            // Neue Bosse: Namensfarbe aus ihrer Gefahrenpalette (nicht Gold: Gold heißt „sicher/offen“).
            Cine.SPEAKER_HEKATE -> Text.translatable("hha.hekate.speaker") to 0xC9B8FF
            Cine.SPEAKER_BRONTES -> Text.translatable("hha.brontes.speaker") to 0xFF8A3D
            Cine.SPEAKER_KRATOS -> Text.translatable("hha.kratos.speaker") to 0xE0483C
            else -> null
        }
        val boxHeight = 10 * fullLines + (if (speaker != null) 22 else 10)
        val x = (width - boxWidth) / 2
        val y = height - (height * 0.11f).toInt() - boxHeight - 6
        val stripe = speaker?.second ?: 0x6B6B75
        context.fill(x - 10, y - 6, x + boxWidth + 8, y + boxHeight, Ui.alpha(0xB0000000.toInt(), a))
        Ui.frame(context, x - 10, y - 6, x + boxWidth + 8, y + boxHeight, Ui.alpha(Ui.LINE, a))
        context.fill(x - 10, y - 6, x - 8, y + boxHeight, alpha or stripe)
        var textY = y
        if (speaker != null) {
            context.drawText(font, Ui.labelText(speaker.first.string), x, textY, alpha or speaker.second, false)
            textY += 12
        }
        val color = when (l.speaker) {
            Cine.SPEAKER_ZEUS -> 0xE6F2FF
            Cine.SPEAKER_HEKATE -> 0xE4DCFF
            Cine.SPEAKER_NARRATOR -> 0xC8C4B8
            else -> 0xF4F4F5
        }
        // Zeus' Worte beben.
        val jitter = l.speaker == Cine.SPEAKER_ZEUS
        for (wrapped in lines) {
            val dx = if (jitter) (l.age * 7 % 3) - 1 else 0
            context.drawTextWithShadow(font, wrapped, x + dx, textY, alpha or color)
            textY += 10
        }
    }

    /**
     * Titelkarte wie eine Überschrift der Website: dunkles Band mit feinen Linien, Titel in der Pixel-Schrift (ganzzahlig
     * skaliert, damit sie scharf bleibt), darunter eine Lichtlinie, die von der Mitte aus wächst, und der Untertitel.
     * Statt zu wachsen steigt der Titel beim Erscheinen ein paar Pixel auf (Website: [data-reveal]).
     */
    private fun renderTitle(context: DrawContext, card: TitleCard, width: Int, height: Int, tickDelta: Float) {
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val age = card.age + tickDelta
        val a = minOf(age / 10f, (card.ticks - age) / 12f).coerceIn(0.02f, 1f)
        val alpha = (a * 255f).toInt().coerceIn(4, 255) shl 24
        val cy = (height * 0.36f).toInt()
        // Dunkles Band, damit der Titel auch vor hellem Marmor lesbar bleibt.
        val bandTop = cy - 22
        context.fill(0, bandTop, width, bandTop + 58, Ui.alpha(0x99000000.toInt(), a))
        Ui.hline(context, 0, width, bandTop, Ui.alpha(Ui.LINE, a))
        Ui.hline(context, 0, width, bandTop + 57, Ui.alpha(Ui.LINE, a))
        val titleText = card.title.string
        val scale = (3 downTo 1).firstOrNull { Ui.displayWidth(font, titleText, it) <= width - 32 } ?: 1
        val rise = ((1f - (age / 14f).coerceIn(0f, 1f)).let { 1f - (1f - it) * (1f - it) } * 6f).toInt()
        val titleWidth = Ui.displayWidth(font, titleText, scale)
        // Versalien sind 5 Schriftpixel hoch und beginnen 2 Pixel unter y: mittig auf cy setzen.
        val titleColor = if (card.color != 0) card.color and 0xFFFFFF else 0xF4F4F5
        Ui.display(context, font, titleText, (width - titleWidth) / 2, cy - (9 * scale) / 2 + rise, alpha or titleColor, scale)
        val lineY = cy + (5 * scale) / 2 + 6
        val grow = Ui.easeOutCubic(age / 16f)
        val half = (titleWidth * 0.5f * grow).toInt()
        // Lichtlinie: in der Mitte hell, zu den Enden hin auslaufend (Website: .feature-sec::before)
        val steps = 12
        for (i in 0 until steps) {
            val x1 = width / 2 - half + half * 2 * i / steps
            val x2 = width / 2 - half + half * 2 * (i + 1) / steps
            val k = 1f - kotlin.math.abs((i + 0.5f) / steps * 2f - 1f)
            context.fill(x1, lineY, x2, lineY + 1, Ui.alpha(0xFF000000.toInt() or titleColor, a * (0.15f + 0.65f * k)))
        }
        val subWidth = font.getWidth(card.subtitle)
        context.drawTextWithShadow(font, card.subtitle, (width - subWidth) / 2, lineY + 6, alpha or 0xA1A1AA)
    }

    /**
     * Kapitelkarte im Website-Stil (Kicker über einer Überschrift, `.feature-sec`): oben der Kicker als Etikett in der
     * Wegfarbe, darunter der Titel in der Pixel-Schrift (steigt beim Erscheinen auf), eine Lichtlinie in der Wegfarbe,
     * die aus der Mitte wächst, und der Untertitel. Kein Band über die ganze Breite: nur ein weicher dunkler Hof, damit
     * die Karte vor hellem Marmor lesbar bleibt. Ein 12 Ticks, aus [CHAPTER_FADE_OUT] Ticks.
     */
    private fun renderChapter(context: DrawContext, card: ChapterCard, width: Int, height: Int, tickDelta: Float) {
        val font = MinecraftClient.getInstance().textRenderer
        val age = card.age + tickDelta
        val a = minOf(age / CHAPTER_FADE_IN, (card.ticks - age) / CHAPTER_FADE_OUT).coerceIn(0f, 1f)
        if (a <= 0.01f) return
        val alpha = (a * 255f).toInt().coerceIn(4, 255) shl 24
        val color = card.color
        val cy = (height * 0.30f).toInt()
        val titleText = card.title.string
        val scale = (3 downTo 1).firstOrNull { Ui.displayWidth(font, titleText, it) <= width - 48 } ?: 1
        val titleWidth = Ui.displayWidth(font, titleText, scale)
        // Weicher Hof: drei gestaffelte, sehr dunkle Flächen statt eines harten Bands.
        val halo = maxOf(titleWidth, font.getWidth(card.subtitle)) / 2 + 36
        for (i in 0 until 3) {
            val grow = i * 10
            context.fill(width / 2 - halo - grow, cy - 30 - grow / 2, width / 2 + halo + grow, cy + 40 + grow / 2, Ui.alpha(0x30000000, a))
        }
        val kicker = Ui.labelText(card.kicker.string)
        val kickerWidth = font.getWidth(kicker)
        context.drawText(font, kicker, (width - kickerWidth) / 2, cy - 24, alpha or color, false)
        val rise = ((1f - (age / 16f).coerceIn(0f, 1f)).let { it * it } * 7f).toInt()
        Ui.display(context, font, titleText, (width - titleWidth) / 2, cy - (9 * scale) / 2 + rise, alpha or 0xF4F4F5, scale)
        val lineY = cy + (5 * scale) / 2 + 7
        val half = (titleWidth * 0.55f * Ui.easeOutCubic((age - 4f) / 18f)).toInt()
        val steps = 16
        for (i in 0 until steps) {
            val x1 = width / 2 - half + half * 2 * i / steps
            val x2 = width / 2 - half + half * 2 * (i + 1) / steps
            val k = 1f - kotlin.math.abs((i + 0.5f) / steps * 2f - 1f)
            context.fill(x1, lineY, x2, lineY + 1, Ui.alpha(0xFF000000.toInt() or color, a * (0.12f + 0.8f * k * k)))
        }
        val subWidth = font.getWidth(card.subtitle)
        context.drawTextWithShadow(font, card.subtitle, (width - subWidth) / 2, lineY + 7, alpha or 0xA1A1AA)
    }

    /** Schreibmaschine: Zeichen je Tick (auch für die Lesedauer der Bühne). */
    const val CHARS_PER_TICK = 1.15f

    /** Zuschlag nach dem Tippen, bis eine Zeile frühestens geht (Blood: `len / CHARS_PER_TICK + 26`). */
    private const val LINE_READ_EXTRA = 26

    /** Vokale öffnen den Mund weit ([mouthOf]). */
    private const val MOUTH_VOWELS = "aeiouäöüy"

    /** Kapitelkarte: Ein- und Ausblenden (Ticks). */
    private const val CHAPTER_FADE_IN = 12f
    private const val CHAPTER_FADE_OUT = 16f

    /** So weich fällt eine hängende Bühnen-Blende am Szenenende weg. */
    private const val STAGE_FADE_CLEAR_TICKS = 10

    /** So lange muss die Taste gehalten werden, damit niemand aus Versehen überspringt. */
    private const val SKIP_HOLD_TICKS = 12
}
