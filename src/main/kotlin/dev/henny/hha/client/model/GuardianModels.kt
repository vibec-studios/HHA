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

    // --- Überarbeitung 2: neue Bosse und Gegner des Olymp ---

    /** Laufende Phase der neuen Bosse (0, 1, 2 …), siehe [dev.henny.hha.entity.boss.DuelBossEntity.phase]. */
    var phase = 0

    /** Hekate: Trugbild der Dreigestalt (andere Textur). */
    var echo = false

    /** Hekate: Gestalt der Dreigestalt (0 Fackel, 1 Schlüssel, 2 Dolch). */
    var form = 0

    /** Kratos: Phase II ohne Schild. */
    var shieldless = false

    /** Goldene Wächterin: wach (Augen glühen) statt Statue. */
    var awake = true

    /** Greif: im Flug. */
    var flying = false
}

/** Gemeinsame Bausteine der Wächter-Modelle: Gang, Blick und weiche Hüllkurven für die Sonderbewegungen. */
abstract class GuardianModel(root: ModelPart) : EntityModel<GuardianRenderState>(root) {

    /** Stärke der Leuchtmaske (0–1) je Bild, Standard voll. Z. B. Bia: als Statue gedämpft, beim Sprung hell. */
    open fun glowAlpha(state: GuardianRenderState): Float = 1f

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
 * feste Modellfaktor aus [GuardianScale] (Plan 6.3); die Trefferbox kommt allein aus dem Entity-Typ. [variant] wählt
 * je Zustand eine andere Textur (Name unter `textures/entity/boss/`, z. B. Hekates Trugbild), `null` = Standard.
 */
class GuardianRenderer<T>(
    context: EntityRendererFactory.Context,
    model: GuardianModel,
    name: String,
    shadow: Float,
    private val modelScale: Float,
    private val variant: (GuardianRenderState) -> String? = { null },
) : MobEntityRenderer<T, GuardianRenderState, GuardianModel>(context, model, shadow) where T : MobEntity, T : MiniBoss {

    private val texture: Identifier = Hha.id("textures/entity/boss/$name.png")
    private val glowLayer = RenderLayers.eyes(Hha.id("textures/entity/boss/${name}_glow.png"))
    private val variantTextures = HashMap<String, Identifier>()
    private val variantGlows = HashMap<String, net.minecraft.client.render.RenderLayer>()

    init {
        addFeature(object : FeatureRenderer<GuardianRenderState, GuardianModel>(this) {
            override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: GuardianRenderState, limbAngle: Float, limbDistance: Float) {
                // Ruhiger Puls (Effekte nicht zu stark): nur noch ±6 % statt ±18 %.
                val pulse = 0.94f + 0.06f * sin(state.age * 0.12f)
                val alpha = (pulse * contextModel.glowAlpha(state).coerceIn(0f, 1f) * 255f).toInt()
                val layer = variant(state)?.let { v -> variantGlows.getOrPut(v) { RenderLayers.eyes(Hha.id("textures/entity/boss/${v}_glow.png")) } } ?: glowLayer
                queue.getBatchingQueue(1).submitModel(contextModel, state, matrices, layer, 0xF000F0, OverlayTexture.DEFAULT_UV, (alpha shl 24) or 0xFFFFFF, null, 0, null)
                // Dauerlicht der neuen Bosse (Zeus: Blitz, Hekate: Fackel, Brontes: Auge); für andere Modelle tut es nichts.
                dev.henny.hha.client.vfx.BossGlow.collect(contextModel, matrices, state)
            }
        })
    }

    override fun createRenderState() = GuardianRenderState()

    override fun getTexture(state: GuardianRenderState): Identifier =
        variant(state)?.let { v -> variantTextures.getOrPut(v) { Hha.id("textures/entity/boss/$v.png") } } ?: texture

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
        state.phase = (entity as? dev.henny.hha.entity.boss.DuelBossEntity)?.phase ?: 0
        val hekate = entity as? dev.henny.hha.entity.boss.olymp.HekateEntity
        state.echo = hekate?.echo ?: false
        state.form = hekate?.form ?: 0
        state.shieldless = (entity as? dev.henny.hha.entity.boss.olymp.KratosEntity)?.shieldless ?: false
        state.awake = (entity as? dev.henny.hha.entity.mob.AutomatonEntity)?.awake ?: true
        state.flying = (entity as? dev.henny.hha.entity.mob.GriffinEntity)?.flying ?: false
    }
}
