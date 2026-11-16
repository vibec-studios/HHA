package dev.henny.hha.client.model

import dev.henny.hha.Hha
import dev.henny.hha.client.vfx.BossGlow
import dev.henny.hha.entity.boss.CoreEntity
import dev.henny.hha.entity.boss.DivineParcelEntity
import dev.henny.hha.entity.boss.HermaEntity
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.entity.boss.HermesEntity
import net.minecraft.client.MinecraftClient
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.EntityRenderer
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.render.entity.LivingEntityRenderer
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
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/*
 * Kleinteile rund um die Bosse: Herma, Götterpaket, Geschosse (samt der fliegenden Waffen aus Plan 6.5) und die Kerne
 * (Höllenpforte, Sanduhr), die ganz aus Licht bestehen.
 * Geometrie und Texturen: tools/hha_assets/hermes_props_model.py.
 */

// -------------------------------------------------------------------------------------------------
// Herma
// -------------------------------------------------------------------------------------------------

class HermaRenderState : LivingEntityRenderState() {
    var rise = 1f

    /** Ist dieser Stein das nächste Ziel von Hermes (siehe [HermaEntity.lit])? */
    var lit = false

    /** Ticks seit dem letzten Wechsel von [lit]. */
    var litTime = 0f

    /** Blickrichtung der Büste zum Ziel (Hermes, sonst der nächste Spieler), relativ zum Stein, in Grad. */
    var targetYaw = 0f
}

/** Zeiten und Grenzen der Herma-Animation (Vorschau: `POSES` in hermes_props_model.py). */
object HermaPose {
    /** Die Büste dreht sich in 6 Ticks zum Ziel (und ebenso schnell zurück). */
    const val TURN_TICKS = 6f

    /** Weiter dreht ein Steinhals nicht (Grad). */
    const val TURN_MAX = 75f

    /** Das leuchtende Relief pulsiert zwischen Maßstab 1,0 und 1,06. */
    const val PULSE = 0.06f
    const val PULSE_SPEED = 0.45f
}

class HermaModel(root: ModelPart) : EntityModel<HermaRenderState>(root) {
    private val stone = root.getChild("root")
    private val bust = stone.getChild("bust")
    private val sigil = stone.getChild("sigil")

    override fun setAngles(state: HermaRenderState) {
        super.setAngles(state)
        val t = MathHelper.clamp(state.litTime / HermaPose.TURN_TICKS, 0f, 1f)
        val eased = t * t * (3f - 2f * t)
        val turn = if (state.lit) eased else 1f - eased
        val idle = sin(state.age * 0.03f) * 0.05f
        bust.yaw = MathHelper.lerp(turn, idle, state.targetYaw * MathHelper.RADIANS_PER_DEGREE)
        // Das Caduceus-Relief leuchtet nur am angesagten Stein und pulsiert dort; sonst bleibt die Rille dunkel.
        sigil.visible = state.lit
        val pulse = 1f + HermaPose.PULSE * 0.5f * (1f - cos(state.litTime * HermaPose.PULSE_SPEED))
        sigil.xScale = pulse
        sigil.yScale = pulse
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
        state.lit = entity.lit
        state.litTime = (entity.age - entity.litChangedAge) + tickDelta
        // Ziel der Büste: Hermes (er springt zu diesem Stein), sonst der nächste Spieler.
        val world = entity.entityWorld
        val pos = entity.getLerpedPos(tickDelta)
        val target = world.getEntitiesByClass(HermesEntity::class.java, entity.boundingBox.expand(48.0)) { it.isAlive }
            .minByOrNull { it.squaredDistanceTo(pos) }
            ?: world.getClosestPlayer(pos.x, pos.y, pos.z, 32.0, false)
        state.targetYaw = if (target == null) 0f else {
            val to = target.getLerpedPos(tickDelta).subtract(pos)
            val yaw = (MathHelper.atan2(to.z, to.x) * MathHelper.DEGREES_PER_RADIAN).toFloat() - 90f
            MathHelper.clamp(MathHelper.wrapDegrees(yaw - state.bodyYaw), -HermaPose.TURN_MAX, HermaPose.TURN_MAX)
        }
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
    var size = 1f
    var velocity: Vec3d = Vec3d.ZERO

    /** Flugrichtung (normiert): aus der Geschwindigkeit, sonst aus yaw/pitch. */
    var direction: Vec3d = Vec3d(0.0, 0.0, 1.0)
}

/**
 * Eine geworfene Waffe aus einer fremden Modellschicht (Plan 6.5): der Knochen samt Kindern, ohne seine Lage im Körper,
 * ausgerichtet an seinem eigenen Umriss. Fehlt der Knochen (das Modell wird gerade umgebaut), bleibt [part] leer, der
 * Renderer zeichnet nichts und es gibt einmal eine Warnung im Log.
 */
private class FlyingWeapon(
    context: EntityRendererFactory.Context, modelLayer: EntityModelLayer, bone: String, texture: String, val scale: Float, val mode: Int,
) {
    val center = Vector3f()

    /** Dreht den Knochenraum in den Flugrahmen (x rechts, y oben, -z vorn). */
    val align = Quaternionf()

    /** Halbe größte Ausdehnung in Blöcken (vor [scale]). */
    var radius = 0.5f
    val layer: RenderLayer = RenderLayers.entityCutoutNoCull(boss(texture))
    val glow: RenderLayer = RenderLayers.eyes(boss("${texture}_glow"))
    val part: ModelPart? = try {
        measure(context.getPart(modelLayer).createPartGetter().apply(bone))
    } catch (e: Exception) {
        null
    }

    init {
        if (part == null) Hha.LOGGER.warn("BoltRenderer: Knochen '{}' fehlt in {}; die fliegende Waffe bleibt unsichtbar", bone, modelLayer)
    }

    private fun measure(found: ModelPart?): ModelPart? {
        if (found == null) return null
        // Eigene Kopie der Schicht: Lage im Körper ablegen, damit der Knochen um seinen eigenen Ursprung liegt.
        found.setOrigin(0f, 0f, 0f)
        found.setAngles(0f, 0f, 0f)
        found.xScale = 1f
        found.yScale = 1f
        found.zScale = 1f
        found.visible = true
        val points = ArrayList<Vector3f>()
        found.collectVertices(MatrixStack()) { points.add(Vector3f(it)) }
        if (points.isEmpty()) return null
        val min = Vector3f(Float.MAX_VALUE)
        val max = Vector3f(-Float.MAX_VALUE)
        for (p in points) {
            min.min(p)
            max.max(p)
        }
        Vector3f(max).add(min).mul(0.5f, center)
        val extent = Vector3f(max).sub(min)
        radius = maxOf(extent.x, extent.y, extent.z) * 0.5f
        // Achsen nach Ausdehnung: lang, mittel.
        val order = (0..2).sortedByDescending { extent[it] }
        val long = axis(order[0])
        val mid = axis(order[1])
        when (mode) {
            BIDENT -> {
                // Das Ende mit der größten Breite quer zur Längsachse trägt die Zinken; es fliegt voran.
                fun spread(atMax: Boolean): Float {
                    val lo = min[order[0]]
                    val hi = max[order[0]]
                    val cut = if (atMax) hi - (hi - lo) * 0.2f else lo + (hi - lo) * 0.2f
                    val near = points.filter { if (atMax) it[order[0]] >= cut else it[order[0]] <= cut }
                    return if (near.isEmpty()) 0f else near.maxOf { it[order[1]] } - near.minOf { it[order[1]] }
                }
                if (spread(false) > spread(true)) long.negate()
                // Zinken nach vorn (-z), Zinkenebene waagerecht (x).
                align.set(basis(long, mid, Vector3f(0f, 0f, -1f), Vector3f(1f, 0f, 0f)))
            }
            // Schaft waagerecht (x), Klingenebene flach (x-z): dreht wie ein Bumerang um die Hochachse.
            HARPE -> align.set(basis(long, mid, Vector3f(1f, 0f, 0f), Vector3f(0f, 0f, 1f)))
        }
        return found
    }

    private fun axis(i: Int) = Vector3f(if (i == 0) 1f else 0f, if (i == 1) 1f else 0f, if (i == 2) 1f else 0f)

    /** Drehung, die die Quellachsen a, b auf die Zielachsen ta, tb legt (rechtshändig ergänzt). */
    private fun basis(a: Vector3f, b: Vector3f, ta: Vector3f, tb: Vector3f): Quaternionf {
        val src = Matrix3f().set(a, b, Vector3f(a).cross(b))
        val dst = Matrix3f().set(ta, tb, Vector3f(ta).cross(tb))
        return Quaternionf().setFromNormalized(dst.mul(src.transpose()))
    }

    companion object {
        const val BIDENT = 0
        const val HARPE = 1
        const val BOULDER = 2
    }
}

class BoltRenderer(context: EntityRendererFactory.Context) : EntityRenderer<HermesBoltEntity, BoltRenderState>(context) {

    private val root = context.getPart(BossLayers.PROJECTILES)
    private val lance = root.getChild("lance")
    private val layer = RenderLayers.entityCutoutNoCull(boss("projectiles"))
    private val glow = RenderLayers.eyes(boss("projectiles_glow"))
    private val lanceWings = FeatherRig(root, HhaModelData.projectilesFeathers())

    /** Bident (Maßstab wie Hades 0,72), Harpe (Kronos 0,69), Fels (Sisyphos 0,69); Knochennamen laut Plan 6.5. */
    private val bident = FlyingWeapon(context, BossLayers.HADES, "bident", "hades", 0.72f, FlyingWeapon.BIDENT)
    private val harpe = FlyingWeapon(context, BossLayers.KRONOS, "harpe", "kronos", 0.69f, FlyingWeapon.HARPE)
    private val boulder = FlyingWeapon(context, BossLayers.SISYPHUS, "boulder", "sisyphus", 0.69f, FlyingWeapon.BOULDER)

    /** Requisiten des Olymp (Keraunos, Adler, Rohling, Fackel, Donnerkeil, Klingenbogen): Modell `olymp_props`. */
    private val olymp = OlympProps(context)

    override fun createRenderState() = BoltRenderState()

    override fun updateRenderState(entity: HermesBoltEntity, state: BoltRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.kind = entity.kind
        state.yaw = entity.getYaw(tickDelta)
        state.pitch = entity.getPitch(tickDelta)
        state.size = entity.size
        state.velocity = entity.velocity
        state.direction = if (entity.velocity.lengthSquared() > 1e-4) entity.velocity.normalize() else {
            // Wie ProjectileUtil.setRotationFromVelocity: yaw = atan2(z, x) + 90°.
            val y = state.yaw * MathHelper.RADIANS_PER_DEGREE
            val p = state.pitch * MathHelper.RADIANS_PER_DEGREE
            Vec3d((sin(y) * cos(p)).toDouble(), sin(p).toDouble(), (-cos(y) * cos(p)).toDouble())
        }
    }

    override fun render(state: BoltRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue, cameraState: CameraRenderState) {
        if (state.kind in HermesBoltEntity.OLYMP_KINDS) {
            olymp.submit(state, matrices, queue)
            super.render(state, matrices, queue, cameraState)
            return
        }
        when (state.kind) {
            HermesBoltEntity.ROCK -> {
                // Ein echter Felsblock, der sich im Flug überschlägt.
                matrices.push()
                val s = state.size
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(state.age * 7f))
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(state.age * 11f))
                matrices.scale(s, s, s)
                matrices.translate(-0.5, -0.5, -0.5)
                queue.submitBlock(matrices, net.minecraft.block.Blocks.BLACKSTONE.defaultState, state.light, OverlayTexture.DEFAULT_UV, 0)
                matrices.pop()
                super.render(state, matrices, queue, cameraState)
                return
            }
            HermesBoltEntity.VENOM, HermesBoltEntity.FIRE, HermesBoltEntity.SAND -> {
                val origin = matrices.peek().positionMatrix.transformPosition(0f, 0f, 0f, Vector3f())
                val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
                BossGlow.submit(BossGlow.OrbFrame(Vec3d(origin.x + cam.x, origin.y + cam.y, origin.z + cam.z), state.velocity, state.kind, state.size, state.age))
                super.render(state, matrices, queue, cameraState)
                return
            }
            HermesBoltEntity.BIDENT, HermesBoltEntity.HARPE, HermesBoltEntity.BOULDER -> {
                val weapon = when (state.kind) {
                    HermesBoltEntity.BIDENT -> bident
                    HermesBoltEntity.HARPE -> harpe
                    else -> boulder
                }
                submitWeapon(weapon, state, matrices, queue)
                super.render(state, matrices, queue, cameraState)
                return
            }
        }
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
            // Die zwei kurzen Flügel am Schaftende (Federn, türkis getönt).
            lanceWings.submit(matrices, queue, FeatherDraw.LAYER, LANCE_WING, OverlayTexture.DEFAULT_UV, FULL_BRIGHT)
            lanceWings.submit(matrices, queue.getBatchingQueue(1), FeatherDraw.GLOW_LAYER, LANCE_WING_GLOW, OverlayTexture.DEFAULT_UV, FULL_BRIGHT)
        } else {
            submitDart(matrices, queue, FeatherDraw.LAYER, -1)
            submitDart(matrices, queue.getBatchingQueue(1), FeatherDraw.GLOW_LAYER, 0xE6FFFFFF.toInt())
        }
        matrices.pop()
        super.render(state, matrices, queue, cameraState)
    }

    /**
     * Bident: fliegt mit den Zinken voran und dreht sich nicht. Harpe: liegt flach und dreht wie ein Bumerang um die
     * Hochachse (eine Umdrehung in [HARPE_TURN_TICKS] Ticks). Fels: rollt um die Querachse passend zur Geschwindigkeit.
     */
    private fun submitWeapon(w: FlyingWeapon, state: BoltRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue) {
        val part = w.part ?: return
        val f = state.direction
        val flat = Vec3d(f.x, 0.0, f.z).let { if (it.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
        matrices.push()
        when (w.mode) {
            FlyingWeapon.BIDENT -> matrices.multiply(frame(f))
            FlyingWeapon.HARPE -> {
                matrices.multiply(frame(flat))
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(state.age * 360f / HARPE_TURN_TICKS))
            }
            else -> {
                matrices.multiply(frame(flat))
                // Abrollen: Weg / Radius; im Flugrahmen kippt die Oberseite nach vorn (-z).
                val speed = state.velocity.length().toFloat()
                val roll = state.age * speed / max(w.radius * w.scale, 0.1f)
                matrices.multiply(RotationAxis.NEGATIVE_X.rotation(roll))
            }
        }
        matrices.scale(w.scale, w.scale, w.scale)
        matrices.multiply(w.align)
        matrices.translate(-w.center.x, -w.center.y, -w.center.z)
        queue.submitModelPart(part, matrices, w.layer, state.light, OverlayTexture.DEFAULT_UV, null)
        queue.getBatchingQueue(1).submitModelPart(part, matrices, w.glow, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, null)
        matrices.pop()
    }

    /** Flugrahmen: -z zeigt in Flugrichtung [f], x bleibt waagerecht. */
    private fun frame(f: Vec3d): Quaternionf {
        val forward = Vector3f(f.x.toFloat(), f.y.toFloat(), f.z.toFloat()).normalize()
        val right = Vector3f(forward).cross(0f, 1f, 0f)
        if (right.lengthSquared() < 1e-6f) right.set(1f, 0f, 0f) else right.normalize()
        val up = Vector3f(right).cross(forward)
        return Quaternionf().setFromNormalized(Matrix3f().set(right, up, Vector3f(forward).negate()))
    }

    private companion object {
        const val LANCE_WING = 0xFF8FEAF5.toInt()
        const val LANCE_WING_GLOW = 0xC04EE0F5.toInt()

        /** Harpe im Flug: eine Umdrehung in 8 Ticks. */
        const val HARPE_TURN_TICKS = 8f
    }
}

/** Federpfeil: zwei gekreuzte Federn, damit er aus jedem Winkel Fläche zeigt; die Spitze zeigt nach -z. */
private fun submitDart(matrices: MatrixStack, queue: net.minecraft.client.render.command.RenderCommandQueue, layer: RenderLayer, color: Int) {
    queue.submitCustom(matrices, layer) { entry, vc ->
        FeatherDraw.strip(vc, entry, 0f, 0f, 11f, 0f, 0f, -1f, 1f, 0f, 0f, 0f, 1f, 0f, 24f, 15f, 0.03f, FeatherDraw.CELL_LONG, false, color, OverlayTexture.DEFAULT_UV, FULL_BRIGHT)
        FeatherDraw.strip(vc, entry, 0f, 0f, 11f, 0f, 0f, -1f, 0f, 1f, 0f, 1f, 0f, 0f, 24f, 15f, 0.03f, FeatherDraw.CELL_LONG, false, color, OverlayTexture.DEFAULT_UV, FULL_BRIGHT)
    }
}

// -------------------------------------------------------------------------------------------------
// Kerne: Höllenpforte und Sanduhr
// -------------------------------------------------------------------------------------------------

class CoreRenderState : EntityRenderState() {
    var kind = 0
    var health = 1f
}

/** Höllenpforte und Sanduhr haben kein Modell: Sie bestehen ganz aus Licht, das [BossGlow] zeichnet. */
class CoreRenderer(context: EntityRendererFactory.Context) : EntityRenderer<CoreEntity, CoreRenderState>(context) {

    override fun createRenderState() = CoreRenderState()

    override fun updateRenderState(entity: CoreEntity, state: CoreRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.kind = entity.kind
        state.health = (entity.health / entity.maxHealth).coerceIn(0f, 1f)
    }

    override fun render(state: CoreRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue, cameraState: CameraRenderState) {
        val origin = matrices.peek().positionMatrix.transformPosition(0f, 0f, 0f, Vector3f())
        val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
        BossGlow.submit(BossGlow.CoreFrame(Vec3d(origin.x + cam.x, origin.y + cam.y, origin.z + cam.z), state.kind, state.health, state.age))
        super.render(state, matrices, queue, cameraState)
    }
}
