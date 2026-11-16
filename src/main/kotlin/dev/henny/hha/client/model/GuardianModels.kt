package dev.henny.hha.client.model

import dev.henny.hha.Hha
import dev.henny.hha.entity.miniboss.IxionEntity
import dev.henny.hha.entity.miniboss.MiniBoss
import dev.henny.hha.entity.miniboss.SisyphusEntity
import dev.henny.hha.entity.miniboss.TantalusEntity
import dev.henny.hha.entity.miniboss.WardenOfChainsEntity
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.MobEntityRenderer
import net.minecraft.client.render.entity.feature.FeatureRenderer
import net.minecraft.client.render.entity.model.EntityModel
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.entity.mob.MobEntity
import net.minecraft.util.Identifier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class GuardianRenderState : LivingEntityRenderState() {
    /** Für Modelle, die je Wesen einen geglätteten Zustand führen (Ixion). */
    var entityId = 0
    /** Sonderbewegung aus [dev.henny.hha.entity.miniboss.MiniBossKit.action], 0 = keine. */
    var action = 0
    /** Ticks seit Beginn der Bewegung; läuft auch nach ihrem Ende (Aktion 0) weiter. */
    var actionTime = 0f
    var swing = 0f

    /** Die Bewegung davor und wie weit sie beim Wechsel war: zum weichen Ausblenden über die ersten Ticks. */
    var previousAction = 0
    var previousTime = 0f

    /** Nur Ixion: gelockerte Fesseln (0–3), siehe [IxionEntity.looseFetters]. */
    var fetters = 0
}

/** Gemeinsame Bausteine der Wächter-Modelle: Gang, Blick und weiche Hüllkurven für die Sonderbewegungen. */
abstract class GuardianModel(root: ModelPart) : EntityModel<GuardianRenderState>(root) {

    protected fun phase(state: GuardianRenderState) = state.limbSwingAnimationProgress * 0.6662f
    protected fun walk(state: GuardianRenderState) = min(state.limbSwingAmplitude, 1f)

    /** 0 vor [from], 1 nach [to], dazwischen weich. */
    protected fun step(t: Float, from: Float, to: Float): Float {
        val x = ((t - from) / (to - from)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** Ausholen bis [peak], halten bis [release], dann weich zurück bis [end]. */
    protected fun hold(t: Float, peak: Float, release: Float, end: Float): Float = step(t, 0f, peak) * (1f - step(t, release, end))

    protected fun look(head: ModelPart, state: GuardianRenderState) {
        head.yaw += state.relativeHeadYaw * DEG
        head.pitch += state.pitch * DEG
    }

    protected fun legs(state: GuardianRenderState, legR: ModelPart, legL: ModelPart, shinR: ModelPart?, shinL: ModelPart?, swing: Float = 0.8f) {
        val p = phase(state)
        val w = walk(state)
        legR.pitch += cos(p) * swing * w
        legL.pitch += cos(p + PI.toFloat()) * swing * w
        shinR?.let { it.pitch += kotlin.math.max(0f, -sin(p)) * 0.9f * w }
        shinL?.let { it.pitch += kotlin.math.max(0f, sin(p)) * 0.9f * w }
    }

    protected companion object {
        const val DEG = 0.017453292f
    }
}

/**
 * Ein Renderer für alle Wächter: Modell, Textur, Leuchtmaske und die Sonderbewegung aus dem Kit. [modelScale] ist der
 * feste Modellfaktor aus [GuardianScale] (Plan 6.3); die Trefferbox kommt allein aus dem Entity-Typ.
 */
class GuardianRenderer<T>(
    context: EntityRendererFactory.Context,
    model: GuardianModel,
    name: String,
    shadow: Float,
    private val modelScale: Float,
) : MobEntityRenderer<T, GuardianRenderState, GuardianModel>(context, model, shadow) where T : MobEntity, T : MiniBoss {

    private val texture: Identifier = Hha.id("textures/entity/boss/$name.png")
    private val glowLayer = RenderLayers.eyes(Hha.id("textures/entity/boss/${name}_glow.png"))

    init {
        addFeature(object : FeatureRenderer<GuardianRenderState, GuardianModel>(this) {
            override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: GuardianRenderState, limbAngle: Float, limbDistance: Float) {
                val pulse = 0.82f + 0.18f * sin(state.age * 0.12f)
                val alpha = (pulse * 255f).toInt()
                queue.getBatchingQueue(1).submitModel(contextModel, state, matrices, glowLayer, 0xF000F0, OverlayTexture.DEFAULT_UV, (alpha shl 24) or 0xFFFFFF, null, 0, null)
            }
        })
    }

    override fun createRenderState() = GuardianRenderState()

    override fun getTexture(state: GuardianRenderState): Identifier = texture

    override fun scale(state: GuardianRenderState, matrices: MatrixStack) {
        matrices.scale(modelScale, modelScale, modelScale)
    }

    override fun updateRenderState(entity: T, state: GuardianRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.entityId = entity.id
        state.action = entity.kit.action
        state.actionTime = entity.kit.actionAge + tickDelta
        state.swing = entity.getHandSwingProgress(tickDelta)
        state.previousAction = entity.kit.previousAction
        state.previousTime = entity.kit.previousActionAge.toFloat()
        state.fetters = (entity as? IxionEntity)?.looseFetters ?: 0
    }
}
