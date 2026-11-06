package dev.henny.hha.client.cine

import dev.henny.hha.HhaSounds
import dev.henny.hha.client.vfx.SkyMood
import dev.henny.hha.client.vfx.easeInOut
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.net.CinePayload
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.option.Perspective
import net.minecraft.client.sound.AbstractSoundInstance
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.sound.SoundInstance
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.Identifier
import net.minecraft.client.render.Camera
import net.minecraft.entity.Entity
import net.minecraft.text.Text
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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

    private class Line(val speaker: Int, val text: Text, val ticks: Int) {
        var age = 0
    }

    private class TitleCard(val title: Text, val subtitle: Text, val ticks: Int) {
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
            Cine.OP_CAMERA -> {
                shot = LineShot(
                    Vec3d(n[0], n[1], n[2]), Vec3d(n[3], n[4], n[5]), Vec3d(n[6], n[7], n[8]), Vec3d(n[9], n[10], n[11]),
                    n[12].toInt(), n[13], n[14], n[15].toInt(), n[16],
                )
                shotAge = 0
            }
            Cine.OP_ORBIT -> {
                shot = OrbitShot(
                    Vec3d(n[0], n[1], n[2]), n[3], n[4], n[5], n[6], n[7], n[8],
                    n[9].toInt(), n[10], n[11], n[12].toInt(), n[13],
                )
                shotAge = 0
            }
            Cine.OP_DIALOG -> {
                line = Line(n[0].toInt(), Text.translatable(payload.texts[0]), n[1].toInt())
                speak(payload.texts[0])
            }
            Cine.OP_FADE -> {
                fadeColor = n[0].toInt()
                fadeFrom = n[1]
                fadeTo = n[2]
                fadeTicks = n[3].toInt().coerceAtLeast(1)
                fadeAge = 0
            }
            Cine.OP_TITLE -> title = TitleCard(Text.translatable(payload.texts[0]), Text.translatable(payload.texts[1]), n[0].toInt())
            Cine.OP_ATMOSPHERE -> {
                val style = n[0].toInt()
                if (style < 0) SkyMood.clearArena()
                else SkyMood.setArena(Vfx.Style.entries[style], Vec3d(n[1], n[2], n[3]), n[4].toInt())
            }
            Cine.OP_WINGS -> messengerWings = n[0] > 0.5
            Cine.OP_MUSIC -> setTheme(payload.texts[0])
            Cine.OP_CLEAR_VFX -> dev.henny.hha.client.vfx.VfxManager.clear()
        }
    }

    /** Das Bossthema als endlos schleifender, ortsunabhängiger Klang in der Musik-Kategorie. */
    private class ThemeSound(event: SoundEvent) : AbstractSoundInstance(event, SoundCategory.MUSIC, SoundInstance.createRandom()) {
        init {
            repeat = true
            repeatDelay = 0
            relative = true
            attenuationType = SoundInstance.AttenuationType.NONE
            volume = 1f
        }
    }

    private var theme: ThemeSound? = null

    private fun setTheme(id: String) {
        val client = MinecraftClient.getInstance()
        theme?.let { client.soundManager.stop(it) }
        theme = null
        if (id.isEmpty()) return
        val event = Registries.SOUND_EVENT.get(Identifier.of(id)) ?: return
        client.musicTracker.stop()
        theme = ThemeSound(event).also { client.soundManager.play(it) }
    }

    private fun begin() {
        val client = MinecraftClient.getInstance()
        if (!active) previousPerspective = client.options.perspective
        client.options.perspective = Perspective.THIRD_PERSON_BACK
        active = true
        shot = null
    }

    private fun finish() {
        val client = MinecraftClient.getInstance()
        previousPerspective?.let { client.options.perspective = it }
        previousPerspective = null
        active = false
        shot = null
        line = null
    }

    fun reset() {
        finish()
        messengerWings = false
        setTheme("")
        title = null
        fadeTicks = 0
        fadeFrom = 0.0
        fadeTo = 0.0
        bars = 0f
    }

    fun tick() {
        // Vanilla-Musik soll das Bossthema nicht überlagern.
        if (theme != null) MinecraftClient.getInstance().musicTracker.stop()
        bars = (bars + if (active) 0.08f else -0.08f).coerceIn(0f, 1f)
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
        val d = look.subtract(pos)
        val yaw = (Math.toDegrees(atan2(d.z, d.x)) - 90.0).toFloat()
        val pitch = (-Math.toDegrees(atan2(d.y, sqrt(d.x * d.x + d.z * d.z)))).toFloat()
        return CameraPose(pos, yaw, pitch)
    }

    class CameraPose(@JvmField val pos: Vec3d, @JvmField val yaw: Float, @JvmField val pitch: Float)

    /** Überschreibt das Sichtfeld, solange eine Kamerafahrt läuft. */
    @JvmStatic
    fun fov(vanilla: Float, tickDelta: Float): Float {
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
        if (bars > 0f) {
            val barHeight = (height * 0.11f * easeInOut(bars)).toInt()
            context.fill(0, 0, width, barHeight, 0xFF000000.toInt())
            context.fill(0, height - barHeight, width, height, 0xFF000000.toInt())
        }
        line?.let { renderLine(context, it, width, height) }
        title?.let { renderTitle(context, it, width, height, tickDelta) }
        if (fadeTicks > 0) {
            val t = ((fadeAge + tickDelta) / fadeTicks).coerceIn(0f, 1f).toDouble()
            val alpha = MathHelper.lerp(t, fadeFrom, fadeTo).toFloat()
            if (alpha > 0.001f) {
                context.fill(0, 0, width, height, ((alpha * 255f).toInt().coerceIn(0, 255) shl 24) or (fadeColor and 0xFFFFFF))
            }
        }
        if (active) dev.henny.hha.client.vfx.ScreenFx.renderOverlay(context)
    }

    private fun renderLine(context: DrawContext, l: Line, width: Int, height: Int) {
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val fadeOut = ((l.ticks + 6 - l.age) / 6f).coerceIn(0f, 1f)
        val alpha = ((l.age / 4f).coerceAtMost(1f) * fadeOut * 255f).toInt().coerceIn(4, 255) shl 24
        val full = l.text.string
        val shown = full.substring(0, visibleChars(l))
        val boxWidth = (width * 0.72f).toInt().coerceAtMost(420)
        val lines = font.wrapLines(Text.literal(shown), boxWidth)
        val fullLines = font.wrapLines(l.text, boxWidth).size
        val boxHeight = 10 * fullLines + 22
        val x = (width - boxWidth) / 2
        val y = height - (height * 0.11f).toInt() - boxHeight - 6
        context.fill(x - 8, y - 4, x + boxWidth + 8, y + boxHeight, (alpha ushr 25 shl 24))
        val speaker = when (l.speaker) {
            Cine.SPEAKER_HERMES -> Text.translatable("hha.hermes.speaker.hermes") to 0xFFD75A
            Cine.SPEAKER_ZEUS -> Text.translatable("hha.hermes.speaker.zeus") to 0xA8D8FF
            Cine.SPEAKER_SHADE -> Text.translatable("hha.hermes.speaker.shade") to 0x62F27A
            else -> null
        }
        var textY = y + 2
        if (speaker != null) {
            context.drawTextWithShadow(font, speaker.first, x, textY, alpha or speaker.second)
            textY += 12
        }
        val color = when (l.speaker) {
            Cine.SPEAKER_ZEUS -> 0xE6F2FF
            Cine.SPEAKER_NARRATOR -> 0xC8C4B8
            else -> 0xFFFFFF
        }
        // Zeus' Worte beben.
        val jitter = l.speaker == Cine.SPEAKER_ZEUS
        for (wrapped in lines) {
            val dx = if (jitter) (l.age * 7 % 3) - 1 else 0
            context.drawTextWithShadow(font, wrapped, x + dx, textY, alpha or color)
            textY += 10
        }
    }

    private fun renderTitle(context: DrawContext, card: TitleCard, width: Int, height: Int, tickDelta: Float) {
        val client = MinecraftClient.getInstance()
        val font = client.textRenderer
        val age = card.age + tickDelta
        val alpha = (minOf(age / 10f, (card.ticks - age) / 12f).coerceIn(0f, 1f) * 255f).toInt().coerceIn(4, 255) shl 24
        val scale = 3f
        val grow = 1f + (1f - (age / 14f).coerceIn(0f, 1f)) * 0.18f
        // Dunkles Band, damit der Titel auch vor hellem Marmor lesbar bleibt.
        val bandTop = (height * 0.36f - 22f).toInt()
        context.fill(0, bandTop, width, bandTop + 58, (alpha ushr 25 shl 24))
        val matrices = context.matrices
        matrices.pushMatrix()
        matrices.translate(width / 2f, height * 0.36f)
        matrices.scale(scale * grow, scale * grow)
        val titleWidth = font.getWidth(card.title)
        context.drawTextWithShadow(font, card.title, -titleWidth / 2, -4, alpha or 0xFFD75A)
        matrices.popMatrix()
        val lineWidth = (titleWidth * scale * 0.75f * (age / 16f).coerceIn(0f, 1f)).toInt()
        val lineY = (height * 0.36f + 18f).toInt()
        context.fill(width / 2 - lineWidth, lineY, width / 2 + lineWidth, lineY + 1, alpha or 0xFFD75A)
        val subWidth = font.getWidth(card.subtitle)
        context.drawTextWithShadow(font, card.subtitle, (width - subWidth) / 2, lineY + 6, alpha or 0xF0EAD8)
    }

    private const val CHARS_PER_TICK = 1.15f
}
