package dev.henny.hha.client.model

import dev.henny.hha.client.vfx.BossGlow
import dev.henny.hha.entity.boss.SerpentEntity
import net.minecraft.client.MinecraftClient
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.LivingEntityRenderer
import net.minecraft.client.render.entity.feature.FeatureRenderer
import net.minecraft.client.render.entity.model.EntityModel
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.client.render.state.CameraRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.RotationAxis
import net.minecraft.util.math.Vec3d
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// -------------------------------------------------------------------------------------------------
// Die Caduceus-Schlangen (Modell und Renderer; Modellschicht [BossLayers.SERPENT])
//
// Zwei schlanke Metallschlangen (A golden, B silberblau) mit einem Flügelpaar hinter dem Kopf; am Stab des Schattens
// verkohlt mit giftgrünem Licht. Sie sind eine Attacke („Die Zwillinge"), kein Gegner: Man sieht sie 2–3 Sekunden in
// schneller Bewegung, der leuchtende Rückengrat zeichnet die Bahn nach.
// Geometrie, Texturen und Vorschau-Posen: tools/hha_assets/serpent_model.py (Winkel dort identisch, siehe POSES).
// -------------------------------------------------------------------------------------------------

class SerpentRenderState : LivingEntityRenderState() {
    var entityId = 0
    var variant = 0
    var biting = false

    /** Ticks seit dem letzten Wechsel von [biting] (Auf- und Zuklappen des Mauls). */
    var biteTime = 100f

    /** Schlange am Stab des Schattens (siehe [SerpentEntity.corrupted]). */
    var corrupted = false
    var girth = 1f

    /** Neigung des Kopfes aus der Flugbahn (Bogenmaß). */
    var headPitch = 0f

    /** Mittelpunkte der Körperglieder relativ zum Kopf, mit eingerechneter Körperwelle. */
    var segments: List<Vec3d> = emptyList()

    /** Schattenfarbe statt Gold/Silber. Auch für die entfallenen Varianten Höllenschlange/Kampe (2, 3). */
    val shade: Boolean get() = corrupted
}

/** Schlüsselposen, identisch mit `POSES` in serpent_model.py. */
object SerpentPose {
    const val JAW_REST = 0.06f
    const val JAW_BITE = 0.873f // 50°
    const val FANG_REST = 1.40f
    const val FANG_BITE = -0.15f

    /** Versatz der Zunge nach vorn (Einheiten), wenn sie ganz draußen ist. */
    const val TONGUE_OUT = -11f

    /** Rechter Kopfflügel (pitch, yaw, roll); links gespiegelt. Im Flug angelegt bzw. beim Zuschnappen gespreizt. */
    val FIN_FOLD = floatArrayOf(-1.25f, 1.2f, 0.1f)
    val FIN_OPEN = floatArrayOf(0f, 0.55f, 0.85f)

    /** Fächer der zwei unteren Federn (roll relativ zum Flügel). */
    val FAN_FOLD = floatArrayOf(-0.06f, -0.12f)
    val FAN_OPEN = floatArrayOf(-0.4f, -0.8f)

    /** Maul öffnet in 3 Ticks, schließt in 5. */
    const val OPEN_TICKS = 3f
    const val CLOSE_TICKS = 5f

    /** Die Zunge zuckt ohne Biss etwa alle 30 Ticks für 6 Ticks heraus. */
    const val FLICK_PERIOD = 30
    const val FLICK_TICKS = 6f
}

class SerpentModel(root: ModelPart) : EntityModel<SerpentRenderState>(root) {

    private class Head(val head: ModelPart, sfx: String) {
        val jaw: ModelPart = head.getChild(if (sfx.isEmpty()) "jaw" else "jaw_b")
        val fangR: ModelPart = head.getChild("fang_r$sfx")
        val fangL: ModelPart = head.getChild("fang_l$sfx")
        val tongue: ModelPart = head.getChild("tongue$sfx")
        val finR: ModelPart = head.getChild("fin_r$sfx")
        val finL: ModelPart = head.getChild("fin_l$sfx")
        val fanR = listOf(finR.getChild("fin_r${sfx}_1"), finR.getChild("fin_r${sfx}_2"))
        val fanL = listOf(finL.getChild("fin_l${sfx}_1"), finL.getChild("fin_l${sfx}_2"))
    }

    private val a = Head(root.getChild("head"), "")
    private val b = Head(root.getChild("head_b"), "_b")

    override fun setAngles(state: SerpentRenderState) {
        super.setAngles(state)
        root.getChild("segment_a").visible = false
        root.getChild("segment_b").visible = false
        root.getChild("tail_a").visible = false
        root.getChild("tail_b").visible = false
        a.head.visible = state.variant == 0
        b.head.visible = state.variant != 0
        pose(if (state.variant == 0) a else b, state)
    }

    private fun pose(h: Head, state: SerpentRenderState) {
        h.head.pitch = state.headPitch
        // Öffnungsgrad: in 3 Ticks auf, in 5 Ticks zu (weich, ohne Sprung beim Wechsel mitten in der Bewegung).
        val open = if (state.biting) {
            ease(state.biteTime / SerpentPose.OPEN_TICKS)
        } else {
            1f - ease(state.biteTime / SerpentPose.CLOSE_TICKS)
        }
        // Beim Biss zittert der Kiefer leicht nach, als würde er gegen etwas drücken.
        val tremble = if (state.biting && state.biteTime > SerpentPose.OPEN_TICKS) sin(state.age * 1.7f) * 0.04f else 0f
        h.jaw.pitch = MathHelper.lerp(open, SerpentPose.JAW_REST, SerpentPose.JAW_BITE) + tremble
        val fang = MathHelper.lerp(open, SerpentPose.FANG_REST, SerpentPose.FANG_BITE)
        h.fangR.pitch = fang
        h.fangL.pitch = fang

        // Kopfflügel: im Flug angelegt und leicht flatternd, beim Zuschnappen gespreizt.
        val flutter = sin(state.age * 0.9f + state.entityId) * 0.06f * (1f - open)
        val fin = FloatArray(3) { MathHelper.lerp(open, SerpentPose.FIN_FOLD[it], SerpentPose.FIN_OPEN[it]) }
        h.finR.setAngles(fin[0], fin[1] + flutter, fin[2])
        h.finL.setAngles(fin[0], -fin[1] - flutter, -fin[2])
        for (i in 0..1) {
            val fan = MathHelper.lerp(open, SerpentPose.FAN_FOLD[i], SerpentPose.FAN_OPEN[i])
            h.fanR[i].roll = fan
            h.fanL[i].roll = -fan
        }

        // Zunge: beim Biss schnellt sie ganz heraus und züngelt; sonst zuckt sie etwa alle 30 Ticks kurz.
        val out: Float
        val lick: Float
        if (state.biting || open > 0.05f) {
            out = open
            lick = sin(state.age * 2.3f) * 0.18f
        } else {
            val phase = (state.age + state.entityId * 11) % SerpentPose.FLICK_PERIOD
            out = if (phase < SerpentPose.FLICK_TICKS) sin(phase / SerpentPose.FLICK_TICKS * PI.toFloat()) * 0.8f else 0f
            lick = sin(state.age * 3.1f) * 0.12f * out
        }
        h.tongue.originZ += SerpentPose.TONGUE_OUT * out
        h.tongue.yaw = lick
        h.tongue.pitch = -0.05f * out
    }

    private fun ease(t: Float): Float {
        val c = MathHelper.clamp(t, 0f, 1f)
        return c * c * (3f - 2f * c)
    }
}

class SerpentRenderer(context: EntityRendererFactory.Context) :
    LivingEntityRenderer<SerpentEntity, SerpentRenderState, SerpentModel>(context, SerpentModel(context.getPart(BossLayers.SERPENT)), 0.5f) {

    private val texture = boss("serpent")
    private val shadeTexture = boss("serpent_shade")
    private val layer = RenderLayers.entityCutoutNoCull(texture)
    private val shadeLayer = RenderLayers.entityCutoutNoCull(shadeTexture)
    private val glowLayer = RenderLayers.eyes(boss("serpent_glow"))
    private val shadeGlowLayer = RenderLayers.eyes(boss("serpent_shade_glow"))
    private val segmentRoot = context.getPart(BossLayers.SERPENT)
    private val segmentA = segmentRoot.getChild("segment_a")
    private val segmentB = segmentRoot.getChild("segment_b")
    private val tailA = segmentRoot.getChild("tail_a")
    private val tailB = segmentRoot.getChild("tail_b")
    private val fins = FeatherRig(model.rootPart, HhaModelData.serpentFeathers())

    /** Letzter Biss-Zustand je Schlange und wann er wechselte (Alter in Ticks). */
    private val biteChanges = HashMap<Int, Pair<Boolean, Float>>()

    init {
        addFeature(GlowFeature(this, { if (it.shade) shadeGlowLayer else glowLayer }) { -1 })
        addFeature(object : FeatureRenderer<SerpentRenderState, SerpentModel>(this) {
            override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: SerpentRenderState, limbAngle: Float, limbDistance: Float) {
                // Die Kopfflügel sind Metall wie der Leib: getönte Federn ohne Leuchten.
                fins.submit(matrices, queue, FeatherDraw.LAYER, featherTint(state), OverlayTexture.DEFAULT_UV, light)
            }
        })
    }

    private fun featherTint(state: SerpentRenderState): Int = when {
        state.shade -> if (state.variant == 0) 0xFF5A5A5E.toInt() else 0xFF52555C.toInt()
        state.variant == 0 -> 0xFFF4CF6A.toInt()
        else -> 0xFFC9D6F2.toInt()
    }

    override fun createRenderState() = SerpentRenderState()

    override fun getTexture(state: SerpentRenderState): Identifier = if (state.shade) shadeTexture else texture

    /** Der Körper reicht weit über die Kollisionsbox des Kopfes hinaus und darf nicht mit ihr verschwinden. */
    override fun canBeCulled(entity: SerpentEntity): Boolean = false

    override fun updateRenderState(entity: SerpentEntity, state: SerpentRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.entityId = entity.id
        state.variant = entity.variant
        state.biting = entity.biting
        state.corrupted = entity.corrupted
        state.girth = entity.girth
        val now = entity.age + tickDelta
        val last = biteChanges[entity.id]
        if (last == null || last.first != entity.biting) {
            // Erster Blick auf diese Schlange: so tun, als stünde der Zustand schon lange.
            biteChanges[entity.id] = entity.biting to (if (last == null) now - 100f else now)
        }
        state.biteTime = now - (biteChanges[entity.id]?.second ?: now)
        if (biteChanges.size > 64) biteChanges.keys.retainAll { it == entity.id || entity.entityWorld.getEntityById(it) != null }

        val current = entity.getLerpedPos(tickDelta)
        val count = entity.body.size
        val time = state.age * WAVE_SPEED
        var ahead = Vec3d.ZERO
        state.segments = List(count) { i ->
            val raw = entity.bodyPrev[i].lerp(entity.body[i], tickDelta.toDouble()).subtract(current)
            val tangent = ahead.subtract(raw)
            ahead = raw
            val horizontal = Vec3d(tangent.z, 0.0, -tangent.x)
            val side = if (horizontal.lengthSquared() < 1e-5) Vec3d(1.0, 0.0, 0.0) else horizontal.normalize()
            // Körperwelle: drei Wellen über die Länge, 0,15 Blöcke, läuft zum Schwanz. Am Hals eingeblendet, damit
            // der Kopf genau auf der angesagten Bahn bleibt.
            val neck = MathHelper.clamp(i / 4f, 0f, 1f)
            val wave = sin(time - i * WAVES * 2f * PI.toFloat() / max(count, 1)) * WAVE_AMPLITUDE * state.girth * neck
            raw.add(side.multiply(wave.toDouble()))
        }
        val first = state.segments.firstOrNull { it.lengthSquared() > 0.01 }
        state.headPitch = if (first == null) 0f else {
            val dir = first.multiply(-1.0)
            MathHelper.clamp(-atan2(dir.y, sqrt(dir.x * dir.x + dir.z * dir.z)).toFloat(), -1.1f, 1.1f)
        }
    }

    override fun scale(state: SerpentRenderState, matrices: MatrixStack) {
        val s = SIZE * state.girth
        matrices.scale(s, s, s)
    }

    /** Hals schmal, dann voller Leib, zum Schwanz hin spitz. */
    private fun thickness(i: Int, count: Int): Float {
        val k = i.toFloat() / max(count - 1, 1)
        val neck = MathHelper.clamp(0.74f + i / 6f * 0.26f, 0.74f, 1f)
        return neck * (1f - Math.pow(k.toDouble(), 1.7).toFloat() * 0.78f)
    }

    override fun render(state: SerpentRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue, cameraState: CameraRenderState) {
        super.render(state, matrices, queue, cameraState)
        val part = if (state.variant == 0) segmentA else segmentB
        val tail = if (state.variant == 0) tailA else tailB
        val bodyLayer = if (state.shade) shadeLayer else layer
        val bodyGlow = if (state.shade) shadeGlowLayer else glowLayer
        val size = SIZE * state.girth
        val height = HEIGHT * state.girth
        var previous = Vec3d.ZERO
        val count = state.segments.size
        val glow = queue.getBatchingQueue(1)
        for ((i, pos) in state.segments.withIndex()) {
            val dir = previous.subtract(pos)
            previous = pos
            if (dir.lengthSquared() < 1e-5) continue
            val yaw = Math.toDegrees(atan2(dir.x, dir.z)).toFloat()
            val pitch = Math.toDegrees(atan2(-dir.y, sqrt(dir.x * dir.x + dir.z * dir.z))).toFloat()
            val taper = size * thickness(i, count)
            // Die Länge schrumpft nicht mit: Sonst klaffen am dünnen Schwanz Lücken zwischen den Gliedern.
            val length = size * max(thickness(i, count), MIN_LENGTH)
            matrices.push()
            matrices.translate(pos.x, pos.y + height, pos.z)
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw + 180f))
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-pitch))
            matrices.scale(-taper, -taper, length)
            val piece = if (i == count - 1) tail else part
            queue.submitModelPart(piece, matrices, bodyLayer, state.light, LivingEntityRenderer.getOverlay(state, 0f), null)
            glow.submitModelPart(piece, matrices, bodyGlow, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, null)
            matrices.pop()
        }
        publishGlow(state, matrices)
    }

    private fun publishGlow(state: SerpentRenderState, matrices: MatrixStack) {
        val origin = matrices.peek().positionMatrix.transformPosition(0f, 0f, 0f, Vector3f())
        val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
        val head = Vec3d(origin.x + cam.x, origin.y + cam.y + HEIGHT * state.girth, origin.z + cam.z)
        val yaw = state.bodyYaw * MathHelper.RADIANS_PER_DEGREE
        val up = -state.headPitch.toDouble()
        val forward = Vec3d(-sin(yaw) * cos(up), sin(up), cos(yaw) * cos(up))
        val right = Vec3d(cos(yaw).toDouble(), 0.0, sin(yaw).toDouble())
        val unit = SIZE * state.girth / 16.0
        val frame = BossGlow.SerpentFrame(state.entityId, state.variant, state.biting, state.age)
        frame.corrupted = state.shade
        // Augen: Modell (±3,2 | −1,5 | −7) – seitlich am Keil, 7 Einheiten vor dem Drehpunkt, knapp über der Achse.
        val eyes = head.add(forward.multiply(unit * 7.0)).add(0.0, unit * 1.5, 0.0)
        frame.eyeR = eyes.subtract(right.multiply(unit * 3.2))
        frame.eyeL = eyes.add(right.multiply(unit * 3.2))
        // Maul: zwischen den Kiefern, 11 Einheiten vor dem Drehpunkt.
        frame.mouth = head.add(forward.multiply(unit * 11.0)).add(0.0, -unit * 0.5, 0.0)
        // Rückgrat: Oberkante der Glieder (y = −4 im Modell), mit der Verjüngung.
        val count = state.segments.size
        frame.spine = state.segments.mapIndexed { i, pos ->
            head.add(pos).add(0.0, unit * 4.2 * thickness(i, count), 0.0)
        }
        BossGlow.submit(frame)
    }

    override fun hasLabel(entity: SerpentEntity, squaredDistance: Double): Boolean = false

    private companion object {
        /** Modelleinheit = SIZE / 16 Block (× girth). */
        const val SIZE = 0.85f

        /** Höhe der Körperachse über der Entitätsposition; entspricht dem Kopf-Drehpunkt y = 12 im Modell. */
        const val HEIGHT = 0.64

        /** Körperwelle: Amplitude in Blöcken, Zahl der Wellen über die Länge, Laufgeschwindigkeit. */
        const val WAVE_AMPLITUDE = 0.15f
        const val WAVES = 3f
        const val WAVE_SPEED = 0.45f

        /** Kleinster Längenfaktor eines Glieds (10 Einheiten × 0,85 > Gliederabstand 7,9). */
        const val MIN_LENGTH = 0.85f
    }
}
