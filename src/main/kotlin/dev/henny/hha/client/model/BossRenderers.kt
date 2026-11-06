package dev.henny.hha.client.model

import dev.henny.hha.Hha
import dev.henny.hha.client.vfx.BossGlow
import dev.henny.hha.entity.boss.DivineParcelEntity
import dev.henny.hha.entity.boss.HermaEntity
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.entity.boss.HermesEntity
import dev.henny.hha.entity.boss.SerpentEntity
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.EmptyEntityRenderer
import net.minecraft.client.render.entity.EntityRenderer
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.LivingEntityRenderer
import net.minecraft.client.render.entity.feature.FeatureRenderer
import net.minecraft.client.render.entity.feature.FeatureRendererContext
import net.minecraft.client.render.entity.model.EntityModel
import net.minecraft.client.render.entity.model.EntityModelLayer
import net.minecraft.client.render.entity.state.EntityRenderState
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.client.render.state.CameraRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.RotationAxis
import net.minecraft.util.math.Vec3d
import org.joml.Vector3f
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private fun boss(name: String): Identifier = Hha.id("textures/entity/boss/$name.png")

object BossLayers {
    val HERMES = EntityModelLayer(Hha.id("hermes"), "main")
    val SERPENT = EntityModelLayer(Hha.id("serpent"), "main")
    val HERMA = EntityModelLayer(Hha.id("herma"), "main")
    val PARCEL = EntityModelLayer(Hha.id("parcel"), "main")
    val PROJECTILES = EntityModelLayer(Hha.id("projectiles"), "main")
}

/** Zeichnet eine Leuchtmaske über ein Modell — vollhell, unabhängig vom Licht. */
private class GlowFeature<S : LivingEntityRenderState, M : EntityModel<S>>(
    context: FeatureRendererContext<S, M>,
    private val layer: (S) -> RenderLayer,
    private val tint: (S) -> Int,
) : FeatureRenderer<S, M>(context) {
    override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: S, limbAngle: Float, limbDistance: Float) {
        if (state.invisibleToPlayer) return
        queue.getBatchingQueue(1).submitModel(contextModel, state, matrices, layer(state), FULL_BRIGHT, OverlayTexture.DEFAULT_UV, tint(state), null, 0, null)
    }
}

private const val FULL_BRIGHT = 0xF000F0

// -------------------------------------------------------------------------------------------------
// Hermes
// -------------------------------------------------------------------------------------------------

class HermesRenderer(context: EntityRendererFactory.Context) :
    LivingEntityRenderer<HermesEntity, HermesRenderState, HermesModel>(context, HermesModel(context.getPart(BossLayers.HERMES)), 0.7f) {

    private val texture = boss("hermes")
    private val shadeTexture = boss("hermes_shade")
    private val ghostLayer = RenderLayers.entityTranslucentEmissive(texture)
    private val shadeGhostLayer = RenderLayers.entityTranslucentEmissive(shadeTexture)
    private val glowLayer = RenderLayers.eyes(boss("hermes_glow"))
    private val shadeGlowLayer = RenderLayers.eyes(boss("hermes_shade_glow"))
    private val feathers = FeatherRig(model.rootPart, HhaModelData.hermesFeathers())

    init {
        addFeature(GlowFeature(this, { if (it.corrupted) shadeGlowLayer else glowLayer }, ::glowTint))
        addFeature(object : FeatureRenderer<HermesRenderState, HermesModel>(this) {
            override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: HermesRenderState, limbAngle: Float, limbDistance: Float) {
                if (state.invisibleToPlayer) return
                // Der Schatten trägt rußschwarze Federn; die Leuchtmaske bleibt, nur eben grün.
                val feather = if (state.corrupted) SHADE_FEATHER else -1
                feathers.submit(matrices, queue, FeatherDraw.LAYER, feather, LivingEntityRenderer.getOverlay(state, 0f), light)
                feathers.submit(matrices, queue.getBatchingQueue(1), FeatherDraw.GLOW_LAYER, glowTint(state), OverlayTexture.DEFAULT_UV, FULL_BRIGHT)
                publishGlow(state, matrices)
            }
        })
    }

    /** Leuchten je Stage: Gold, Dämmerviolett, Seelenblau, Sturmweiß; pulsiert und erlischt in der Erschöpfung. */
    private fun glowTint(state: HermesRenderState): Int {
        val power = BossGlow.targetPower(state.stage, state.vulnerable, state.bossPose, state.illusion)
        val alpha = MathHelper.clamp((0.55f + 0.45f * power) * (0.86f + 0.14f * sin(state.age * 0.19f)), 0.2f, 1f)
        val color = if (state.corrupted) 0x7BFF8E else when (state.stage) {
            2 -> 0xF0D2FF
            3 -> 0xB8F2FF
            4 -> 0xFFF4C2
            else -> 0xFFFFFF
        }
        return ((alpha * 255f).toInt() shl 24) or color
    }

    /** Reicht die Knochenpositionen ans Effekt-System weiter; die Matrizen stehen genau wie beim Modell. */
    private fun publishGlow(state: HermesRenderState, matrices: MatrixStack) {
        val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
        val frame = BossGlow.HermesFrame(state.entityId, state.stage, state.illusion, state.bossPose, state.speed, state.age, state.corrupted)
        frame.power = BossGlow.smoothPower(state.entityId, BossGlow.targetPower(state.stage, state.vulnerable, state.bossPose, state.illusion))
        model.readAnchors(matrices, cam, frame)
        BossGlow.submit(frame)
    }

    override fun createRenderState() = HermesRenderState()

    override fun getTexture(state: HermesRenderState): Identifier = if (state.corrupted) shadeTexture else texture

    override fun updateRenderState(entity: HermesEntity, state: HermesRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.bossPose = entity.pose
        state.previousPose = entity.previousPose
        state.poseTime = (entity.age - entity.poseChangedAge) + tickDelta
        state.illusion = entity.isIllusion
        state.entityId = entity.id
        state.corrupted = entity.corrupted
        state.stage = entity.stage
        state.vulnerable = entity.vulnerable
        val history = entity.history
        state.speed = if (history.size >= 2) history.last().distanceTo(history[history.size - 2]).toFloat().coerceAtMost(2f) else 0f
        // Nachbilder nur bei echtem Tempo; relativ zur aktuellen Position.
        val current = entity.getLerpedPos(tickDelta)
        state.afterimages = if (state.speed > 0.6f && history.size >= 4) {
            history.toList().dropLast(1).takeLast(6).reversed().map { it.subtract(current) }
        } else {
            emptyList()
        }
    }

    override fun scale(state: HermesRenderState, matrices: MatrixStack) {
        matrices.scale(SCALE, SCALE, SCALE)
    }

    override fun render(state: HermesRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue, cameraState: CameraRenderState) {
        for ((i, offset) in state.afterimages.withIndex()) {
            matrices.push()
            matrices.translate(offset.x, offset.y, offset.z)
            renderGhost(state, matrices, queue, (0.42f * (1f - i / 6f)))
            matrices.pop()
        }
        if (state.illusion) {
            renderGhost(state, matrices, queue, 0.62f + 0.12f * sin(state.age * 0.4f), publish = true)
            return
        }
        super.render(state, matrices, queue, cameraState)
    }

    /** Durchsichtige, vollhelle Kopie (Nachbild oder Trugbild). */
    private fun renderGhost(state: HermesRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue, alpha: Float, publish: Boolean = false) {
        matrices.push()
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - state.bodyYaw))
        matrices.scale(-SCALE, -SCALE, SCALE)
        matrices.translate(0f, -1.501f, 0f)
        val color = (MathHelper.clamp((alpha * 255f).toInt(), 0, 255) shl 24) or (if (state.corrupted) 0x9CFFA8 else 0xFFE9B0)
        val ghosts = queue.getBatchingQueue(2)
        ghosts.submitModel(model, state, matrices, if (state.corrupted) shadeGhostLayer else ghostLayer, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, color, null, 0, null)
        // Das Modell wird später gezeichnet; für die Federn und Anker muss die Pose schon jetzt stehen.
        model.setAngles(state)
        feathers.submit(matrices, ghosts, FeatherDraw.GHOST_LAYER, color, OverlayTexture.DEFAULT_UV, FULL_BRIGHT)
        if (publish) publishGlow(state, matrices)
        matrices.pop()
    }

    override fun hasLabel(entity: HermesEntity, squaredDistance: Double): Boolean = false

    private companion object {
        /** Das Modell hat doppelte Pixeldichte, daher die halbe Grundskalierung. */
        const val SCALE = 0.675f
        const val SHADE_FEATHER = 0xFF2C3530.toInt()
    }
}

// -------------------------------------------------------------------------------------------------
// Schlange
// -------------------------------------------------------------------------------------------------

class SerpentRenderState : LivingEntityRenderState() {
    var entityId = 0
    var variant = 0
    var biting = false

    /** Neigung des Kopfes aus der Flugbahn (Bogenmaß). */
    var headPitch = 0f

    /** Mittelpunkte der Körperglieder relativ zum Kopf, mit eingerechnetem Schlängeln. */
    var segments: List<Vec3d> = emptyList()
}

class SerpentModel(root: ModelPart) : EntityModel<SerpentRenderState>(root) {
    private val headA = root.getChild("head")
    private val headB = root.getChild("head_b")
    private val jawA = headA.getChild("jaw")
    private val jawB = headB.getChild("jaw_b")

    override fun setAngles(state: SerpentRenderState) {
        super.setAngles(state)
        root.getChild("segment_a").visible = false
        root.getChild("segment_b").visible = false
        root.getChild("tail_a").visible = false
        root.getChild("tail_b").visible = false
        headA.visible = state.variant == 0
        headB.visible = state.variant != 0
        headA.pitch = state.headPitch
        headB.pitch = state.headPitch
        // Zischt im Flug mit halboffenem Maul, reißt es zum Biss weit auf.
        val open = if (state.biting) 0.82f + sin(state.age * 0.8f) * 0.12f else 0.14f + sin(state.age * 0.13f) * 0.1f
        jawA.pitch = open
        jawB.pitch = open
    }
}

class SerpentRenderer(context: EntityRendererFactory.Context) :
    LivingEntityRenderer<SerpentEntity, SerpentRenderState, SerpentModel>(context, SerpentModel(context.getPart(BossLayers.SERPENT)), 0.5f) {

    private val texture = boss("serpent")
    private val layer = RenderLayers.entityCutoutNoCull(texture)
    private val glowLayer = RenderLayers.eyes(boss("serpent_glow"))
    private val segmentRoot = context.getPart(BossLayers.SERPENT)
    private val segmentA = segmentRoot.getChild("segment_a")
    private val segmentB = segmentRoot.getChild("segment_b")
    private val tailA = segmentRoot.getChild("tail_a")
    private val tailB = segmentRoot.getChild("tail_b")
    private val mane = FeatherRig(model.rootPart, HhaModelData.serpentFeathers())

    init {
        addFeature(GlowFeature(this, { glowLayer }) { -1 })
        addFeature(object : FeatureRenderer<SerpentRenderState, SerpentModel>(this) {
            override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: SerpentRenderState, limbAngle: Float, limbDistance: Float) {
                mane.submit(matrices, queue, FeatherDraw.LAYER, tint(state.variant), OverlayTexture.DEFAULT_UV, light)
                mane.submit(matrices, queue.getBatchingQueue(1), FeatherDraw.GLOW_LAYER, glowTint(state.variant), OverlayTexture.DEFAULT_UV, FULL_BRIGHT)
            }
        })
    }

    private fun tint(variant: Int) = if (variant == 0) 0xFFB6F5DC.toInt() else 0xFFB9DCFF.toInt()
    private fun glowTint(variant: Int) = if (variant == 0) 0xC04EEBB6.toInt() else 0xC076B6FF.toInt()

    override fun createRenderState() = SerpentRenderState()

    override fun getTexture(state: SerpentRenderState): Identifier = texture

    /** Der Körper reicht weit über die Kollisionsbox des Kopfes hinaus und darf nicht mit ihr verschwinden. */
    override fun canBeCulled(entity: SerpentEntity): Boolean = false

    override fun updateRenderState(entity: SerpentEntity, state: SerpentRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.entityId = entity.id
        state.variant = entity.variant
        state.biting = entity.biting
        val current = entity.getLerpedPos(tickDelta)
        val count = entity.body.size
        val time = state.age * 0.2f
        var ahead = Vec3d.ZERO
        state.segments = List(count) { i ->
            val raw = entity.bodyPrev[i].lerp(entity.body[i], tickDelta.toDouble()).subtract(current)
            val tangent = ahead.subtract(raw)
            ahead = raw
            val horizontal = Vec3d(tangent.z, 0.0, -tangent.x)
            val side = if (horizontal.lengthSquared() < 1e-5) Vec3d(1.0, 0.0, 0.0) else horizontal.normalize()
            // Schlängeln: wächst vom Hals zum Schwanz hin und läuft als Welle durch den Körper.
            val k = i.toFloat() / max(count - 1, 1)
            val wave = sin(time - i * 0.42f) * (0.08f + 0.42f * k)
            val lift = cos(time * 0.7f - i * 0.3f) * 0.1f * k
            raw.add(side.multiply(wave.toDouble())).add(0.0, lift.toDouble(), 0.0)
        }
        val first = state.segments.firstOrNull { it.lengthSquared() > 0.01 }
        state.headPitch = if (first == null) 0f else {
            val dir = first.multiply(-1.0)
            MathHelper.clamp(-atan2(dir.y, sqrt(dir.x * dir.x + dir.z * dir.z)).toFloat(), -1.1f, 1.1f)
        }
    }

    override fun scale(state: SerpentRenderState, matrices: MatrixStack) {
        matrices.scale(SIZE, SIZE, SIZE)
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
        var previous = Vec3d.ZERO
        val count = state.segments.size
        val glow = queue.getBatchingQueue(1)
        for ((i, pos) in state.segments.withIndex()) {
            val dir = previous.subtract(pos)
            previous = pos
            if (dir.lengthSquared() < 1e-5) continue
            val yaw = Math.toDegrees(atan2(dir.x, dir.z)).toFloat()
            val pitch = Math.toDegrees(atan2(-dir.y, sqrt(dir.x * dir.x + dir.z * dir.z))).toFloat()
            val taper = SIZE * thickness(i, count)
            matrices.push()
            matrices.translate(pos.x, pos.y + HEIGHT, pos.z)
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw + 180f))
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-pitch))
            matrices.scale(-taper, -taper, taper)
            val piece = if (i == count - 1) tail else part
            // Trefferblinken auf dem ganzen Körper: Man soll sehen, dass der Schlag gesessen hat.
            queue.submitModelPart(piece, matrices, layer, state.light, LivingEntityRenderer.getOverlay(state, 0f), null)
            glow.submitModelPart(piece, matrices, glowLayer, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, null)
            when (i) {
                WING_FRONT -> wings(state, matrices, queue, 1f, i)
                WING_REAR -> wings(state, matrices, queue, 0.68f, i)
            }
            if (i == count - 1) tailFan(state, matrices, queue)
            matrices.pop()
        }
        publishGlow(state, matrices)
    }

    /** Ein Flügelpaar aus glatten Federn, das versetzt zum Körper schlägt. */
    private fun wings(state: SerpentRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue, size: Float, index: Int) {
        val flap = sin(state.age * 0.32f - index * 0.42f) * 0.62f + 0.32f
        val color = tint(state.variant)
        val glow = glowTint(state.variant)
        val light = state.light
        val feathers = 7
        for (layerIndex in 0..1) {
            val target = if (layerIndex == 0) queue else queue.getBatchingQueue(1)
            val renderLayer = if (layerIndex == 0) FeatherDraw.LAYER else FeatherDraw.GLOW_LAYER
            val tone = if (layerIndex == 0) color else glow
            val lightValue = if (layerIndex == 0) light else FULL_BRIGHT
            target.submitCustom(matrices, renderLayer) { entry, vc ->
                for (side in intArrayOf(-1, 1)) {
                    val s = side.toFloat()
                    for (i in 0 until feathers) {
                        val k = i.toFloat() / (feathers - 1)
                        // Fächer vom Vorderrand nach hinten; die Spannweite schrumpft zum Körper hin.
                        val back = 0.15f + k * 1.15f
                        val outX = cos(flap) * s * cos(back)
                        val outY = -sin(flap) * cos(back)
                        val outZ = sin(back)
                        val length = size * (58f - k * 22f)
                        val rootX = s * 6f
                        val rootZ = -5f + k * 11f * size
                        FeatherDraw.strip(
                            vc, entry,
                            rootX, -3f, rootZ,
                            outX, outY, outZ,
                            -outZ * s, 0f, outX * s,
                            sin(flap) * s, cos(flap), 0f,
                            length, size * 15f, 0.08f, FeatherDraw.CELL_TINT, side < 0, tone, OverlayTexture.DEFAULT_UV, lightValue,
                        )
                    }
                }
            }
        }
    }

    /** Schwanzflosse aus langen Federn. */
    private fun tailFan(state: SerpentRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue) {
        val sway = sin(state.age * 0.25f) * 0.2f
        val color = tint(state.variant)
        val glow = glowTint(state.variant)
        val count = 7
        for (layerIndex in 0..1) {
            val target = if (layerIndex == 0) queue else queue.getBatchingQueue(1)
            val renderLayer = if (layerIndex == 0) FeatherDraw.LAYER else FeatherDraw.GLOW_LAYER
            val tone = if (layerIndex == 0) color else glow
            val lightValue = if (layerIndex == 0) state.light else FULL_BRIGHT
            target.submitCustom(matrices, renderLayer) { entry, vc ->
                for (i in 0 until count) {
                    val a = (i - (count - 1) / 2f) * 0.26f + sway
                    FeatherDraw.strip(
                        vc, entry, 0f, 0f, 4f,
                        sin(a), 0f, cos(a),
                        0f, 1f, 0f,
                        cos(a), 0f, -sin(a),
                        72f - kotlin.math.abs(i - (count - 1) / 2f) * 9f, 22f, 0.05f, FeatherDraw.CELL_TINT, false, tone, OverlayTexture.DEFAULT_UV, lightValue,
                    )
                }
            }
        }
    }

    private fun publishGlow(state: SerpentRenderState, matrices: MatrixStack) {
        val origin = matrices.peek().positionMatrix.transformPosition(0f, 0f, 0f, Vector3f())
        val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
        val head = Vec3d(origin.x + cam.x, origin.y + cam.y + HEIGHT, origin.z + cam.z)
        val yaw = state.bodyYaw * MathHelper.RADIANS_PER_DEGREE
        val up = -state.headPitch.toDouble()
        val forward = Vec3d(-sin(yaw) * cos(up), kotlin.math.sin(up), cos(yaw) * cos(up))
        val right = Vec3d(cos(yaw).toDouble(), 0.0, sin(yaw).toDouble())
        val unit = SIZE / 16.0
        val frame = BossGlow.SerpentFrame(state.entityId, state.variant, state.biting, state.age)
        val eyes = head.add(forward.multiply(unit * 5.0)).add(0.0, unit * 5.5, 0.0)
        frame.eyeR = eyes.subtract(right.multiply(unit * 8.0))
        frame.eyeL = eyes.add(right.multiply(unit * 8.0))
        frame.mouth = head.add(forward.multiply(unit * 18.0)).add(0.0, -unit * 3.0, 0.0)
        val count = state.segments.size
        frame.spine = state.segments.mapIndexed { i, pos ->
            head.add(pos).add(0.0, unit * 8.2 * thickness(i, count), 0.0)
        }
        BossGlow.submit(frame)
    }

    override fun hasLabel(entity: SerpentEntity, squaredDistance: Double): Boolean = false

    private companion object {
        /** Modell in doppelter Dichte: Einheit = SIZE / 16 Block. */
        const val SIZE = 0.85f
        /** Höhe der Körpermitte über der Entitätsposition; entspricht dem Kopf-Drehpunkt y = 12 im Modell. */
        const val HEIGHT = 0.64
        const val WING_FRONT = 5
        const val WING_REAR = 17
    }
}

// -------------------------------------------------------------------------------------------------
// Herma
// -------------------------------------------------------------------------------------------------

class HermaRenderState : LivingEntityRenderState() {
    var rise = 1f
}

class HermaModel(root: ModelPart) : EntityModel<HermaRenderState>(root) {
    private val bust = root.getChild("root").getChild("bust")

    override fun setAngles(state: HermaRenderState) {
        super.setAngles(state)
        bust.yaw = sin(state.age * 0.03f) * 0.08f
    }
}

class HermaRenderer(context: EntityRendererFactory.Context) :
    LivingEntityRenderer<HermaEntity, HermaRenderState, HermaModel>(context, HermaModel(context.getPart(BossLayers.HERMA)), 0.9f) {

    private val texture = boss("herma")

    init {
        val hermaGlow = RenderLayers.eyes(boss("herma_glow"))
        addFeature(GlowFeature(this, { hermaGlow }) { -1 })
    }

    override fun createRenderState() = HermaRenderState()

    override fun getTexture(state: HermaRenderState): Identifier = texture

    override fun updateRenderState(entity: HermaEntity, state: HermaRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.rise = MathHelper.clamp((entity.age + tickDelta) / 14f, 0f, 1f)
    }

    override fun scale(state: HermaRenderState, matrices: MatrixStack) {
        matrices.scale(1.4f, 1.4f, 1.4f)
        // Wächst beim Erscheinen aus dem Boden.
        val t = 1f - state.rise
        matrices.translate(0f, t * t * 3.2f, 0f)
    }

    override fun hasLabel(entity: HermaEntity, squaredDistance: Double): Boolean = false
}

// -------------------------------------------------------------------------------------------------
// Götterpaket
// -------------------------------------------------------------------------------------------------

class ParcelRenderState : EntityRenderState() {
    var open = 0f
    var yaw = 0f
}

class ParcelModel(root: ModelPart) : net.minecraft.client.model.Model<ParcelRenderState>(root, RenderLayers::entityCutoutNoCull) {
    private val lid = root.getChild("base").getChild("lid")
    private val wingR = lid.getChild("parcel_wing_r")
    private val wingL = lid.getChild("parcel_wing_l")

    override fun setAngles(state: ParcelRenderState) {
        super.setAngles(state)
        lid.pitch = -1.9f * state.open
        val flap = sin(state.age * 0.3f) * 0.3f
        wingR.yaw = flap
        wingL.yaw = -flap
    }
}

class ParcelRenderer(context: EntityRendererFactory.Context) : EntityRenderer<DivineParcelEntity, ParcelRenderState>(context) {

    private val model = ParcelModel(context.getPart(BossLayers.PARCEL))
    private val layer = RenderLayers.entityCutoutNoCull(boss("parcel"))
    private val glow = RenderLayers.eyes(boss("parcel_glow"))
    private val feathers = FeatherRig(model.rootPart, HhaModelData.parcelFeathers())

    override fun createRenderState() = ParcelRenderState()

    override fun updateRenderState(entity: DivineParcelEntity, state: ParcelRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        val age = entity.openAge
        state.open = if (age < 0) 0f else MathHelper.clamp((age + tickDelta) / 18f, 0f, 1f).let { it * it * (3f - 2f * it) }
    }

    override fun render(state: ParcelRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue, cameraState: CameraRenderState) {
        matrices.push()
        matrices.scale(-1.25f, -1.25f, 1.25f)
        matrices.translate(0f, -1.501f, 0f)
        queue.submitModel(model, state, matrices, layer, state.light, OverlayTexture.DEFAULT_UV, -1, null, 0, null)
        queue.getBatchingQueue(1).submitModel(model, state, matrices, glow, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, -1, null, 0, null)
        model.setAngles(state)
        feathers.submit(matrices, queue, FeatherDraw.LAYER, -1, OverlayTexture.DEFAULT_UV, state.light)
        feathers.submit(matrices, queue.getBatchingQueue(1), FeatherDraw.GLOW_LAYER, -1, OverlayTexture.DEFAULT_UV, FULL_BRIGHT)
        matrices.pop()
        super.render(state, matrices, queue, cameraState)
    }
}

// -------------------------------------------------------------------------------------------------
// Geschosse
// -------------------------------------------------------------------------------------------------

class BoltRenderState : EntityRenderState() {
    var kind = 0
    var yaw = 0f
    var pitch = 0f
}

class BoltRenderer(context: EntityRendererFactory.Context) : EntityRenderer<HermesBoltEntity, BoltRenderState>(context) {

    private val root = context.getPart(BossLayers.PROJECTILES)
    private val lance = root.getChild("lance")
    private val layer = RenderLayers.entityCutoutNoCull(boss("projectiles"))
    private val glow = RenderLayers.eyes(boss("projectiles_glow"))

    override fun createRenderState() = BoltRenderState()

    override fun updateRenderState(entity: HermesBoltEntity, state: BoltRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.kind = entity.kind
        state.yaw = entity.getYaw(tickDelta)
        state.pitch = entity.getPitch(tickDelta)
    }

    override fun render(state: BoltRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue, cameraState: CameraRenderState) {
        matrices.push()
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(state.yaw))
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(state.pitch))
        val spin = state.age * if (state.kind == HermesBoltEntity.LANCE) 18f else 9f
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(spin))
        val size = if (state.kind == HermesBoltEntity.LANCE) 1.5f else 1.1f
        matrices.scale(size, size, size)
        if (state.kind == HermesBoltEntity.LANCE) {
            queue.submitModelPart(lance, matrices, layer, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, null)
            queue.getBatchingQueue(1).submitModelPart(lance, matrices, glow, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, null)
        } else {
            submitDart(matrices, queue, FeatherDraw.LAYER, -1)
            submitDart(matrices, queue.getBatchingQueue(1), FeatherDraw.GLOW_LAYER, 0xE6FFFFFF.toInt())
        }
        matrices.pop()
        super.render(state, matrices, queue, cameraState)
    }
}

/** Federpfeil: zwei gekreuzte Federn, damit er aus jedem Winkel Fläche zeigt; die Spitze zeigt nach -z. */
private fun submitDart(matrices: MatrixStack, queue: net.minecraft.client.render.command.RenderCommandQueue, layer: RenderLayer, color: Int) {
    queue.submitCustom(matrices, layer) { entry, vc ->
        FeatherDraw.strip(vc, entry, 0f, 0f, 11f, 0f, 0f, -1f, 1f, 0f, 0f, 0f, 1f, 0f, 24f, 15f, 0.03f, FeatherDraw.CELL_LONG, false, color, OverlayTexture.DEFAULT_UV, FULL_BRIGHT)
        FeatherDraw.strip(vc, entry, 0f, 0f, 11f, 0f, 0f, -1f, 0f, 1f, 0f, 1f, 0f, 0f, 24f, 15f, 0.03f, FeatherDraw.CELL_LONG, false, color, OverlayTexture.DEFAULT_UV, FULL_BRIGHT)
    }
}

/** Registrierung aller Modell-Layer und Renderer. */
object BossClient {
    fun register() {
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.HERMES) { HhaModelData.hermes() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.SERPENT) { HhaModelData.serpent() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.HERMA) { HhaModelData.herma() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.PARCEL) { HhaModelData.parcel() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.PROJECTILES) { HhaModelData.projectiles() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.HERMES, ::HermesRenderer)
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.SERPENT, ::SerpentRenderer)
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.SERPENT_PART, ::EmptyEntityRenderer)
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.HERMA, ::HermaRenderer)
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(dev.henny.hha.HhaBlocks.GREEN_FIRE, net.minecraft.client.render.BlockRenderLayer.CUTOUT)
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(dev.henny.hha.HhaBlocks.HADES_PORTAL, net.minecraft.client.render.BlockRenderLayer.TRANSLUCENT)
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.DIVINE_PARCEL, ::ParcelRenderer)
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.HERMES_BOLT, ::BoltRenderer)
    }
}
