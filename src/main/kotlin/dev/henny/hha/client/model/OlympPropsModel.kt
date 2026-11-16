package dev.henny.hha.client.model

import dev.henny.hha.entity.boss.HermesBoltEntity
import net.minecraft.client.MinecraftClient
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.EntityRendererFactory
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.RotationAxis
import net.minecraft.util.math.Vec3d
import org.joml.Matrix3f
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.max
import kotlin.math.sin

/**
 * Requisiten des Olymp (Modell `olymp_props` aus `tools/hha_assets/olymp_props_model.py`, Brief
 * `tmp/overhaul2/models/olymp_props.md`): Keraunos, Adler, Rohling, Fackel, Donnerkeil und Klingenbogen fliegen als
 * [HermesBoltEntity]-Arten; Schild, Kranz und der abgekühlte Rohling sind Requisiten, die Kampfszenen über
 * [submitBone] zeichnen. `BoltRenderer` ruft [submit] für alle Arten aus [HermesBoltEntity.OLYMP_KINDS] auf.
 *
 * Jeder Knochen hat seinen Ursprung in sich (der Keraunos am Griff). Für den Flug wird jeder Knochen um seine
 * gemessene Mitte gelegt (aus den Eckpunkten, wie bei `FlyingWeapon`) und im Flugrahmen ausgerichtet:
 *
 * | Art | Knochen | Maßstab | Lage im Flug |
 * | --- | --- | --- | --- |
 * | KERAUNOS | `keraunos` | 0,69 (Zeus) | Spitze voran, dreht langsam um die Längsachse; steht er still (steckt), keine Drehung |
 * | EAGLE | `eagle` | 1,0 (Spannweite 3 Blöcke = Linie 3) | Kopf voran, Flügelschlag mit 10 Ticks; je steiler der Sturz, desto mehr angelegt |
 * | INGOT | `ingot` | 0,70 (Brontes) | überschlägt sich um die Querachse |
 * | TORCH | `torch` | 0,69 (Hekate) | überschlägt sich um die Querachse (Flamme leuchtet) |
 * | HAMMER_BOLT | `wedge` | 0,70 (Brontes) | Spitze voran, dreht um die Längsachse |
 * | BLADE | `blade` | 0,70 (Bia) | bleibt waagerecht, die Wölbung zeigt in Flugrichtung |
 * | SHIELD | `shield` | 0,70 (Kratos) | liegt flach, Schauseite oben, Gier des Geschosses, keine Drehung |
 * | WREATH | `wreath` | 1,0 | waagerecht, dreht langsam ([wreathSpin]) |
 *
 * Die Größe des Geschosses ([BoltRenderState.size], Standard 1) multipliziert den Maßstab. Ausnahme: Ein Rohling mit
 * Größe unter 1 ist abgekühlt (`BrontesTimings.INGOT_COLD_SIZE`) und wird als `ingot_cold` (ohne Glut) in normaler
 * Größe gezeichnet; Schild und Kranz ignorieren die Größe.
 */
class OlympProps(private val root: ModelPart) {

    constructor(context: EntityRendererFactory.Context) : this(context.getPart(BossLayers.OLYMP_PROPS))

    private val layer = RenderLayers.entityCutoutNoCull(boss("olymp_props"))
    private val glow = RenderLayers.eyes(boss("olymp_props_glow"))

    private val wingR: ModelPart? = child("eagle")?.let { if (it.hasChild("eagle_wing_r")) it.getChild("eagle_wing_r") else null }
    private val wingL: ModelPart? = child("eagle")?.let { if (it.hasChild("eagle_wing_l")) it.getChild("eagle_wing_l") else null }

    /** Mitte je Knochen (Einheiten im Knochenraum, aus den Eckpunkten gemessen). */
    private val centers = HashMap<String, Vector3f>()

    private fun child(name: String): ModelPart? = if (root.hasChild(name)) root.getChild(name) else null

    /** Knochen je Art (Namen aus dem Brief). */
    fun bone(kind: Int): String = when (kind) {
        HermesBoltEntity.KERAUNOS -> "keraunos"
        HermesBoltEntity.EAGLE -> "eagle"
        HermesBoltEntity.INGOT -> "ingot"
        HermesBoltEntity.TORCH -> "torch"
        HermesBoltEntity.HAMMER_BOLT -> "wedge"
        HermesBoltEntity.BLADE -> "blade"
        HermesBoltEntity.SHIELD -> "shield"
        HermesBoltEntity.WREATH -> "wreath"
        else -> "keraunos"
    }

    /** Grundmaßstab je Art (Renderer-Faktor des Bosses, zu dem das Requisit gehört). */
    private fun scale(kind: Int): Float = when (kind) {
        HermesBoltEntity.KERAUNOS, HermesBoltEntity.TORCH -> 0.69f
        HermesBoltEntity.EAGLE, HermesBoltEntity.WREATH -> 1.0f
        else -> 0.70f
    }

    /** Mitte eines Knochens: Eckpunkte in Grundhaltung, ohne die Lage des Knochens im Modell. */
    private fun center(name: String, part: ModelPart): Vector3f = centers.getOrPut(name) {
        val saved = part.getTransform()
        part.resetTransform()
        part.setOrigin(0f, 0f, 0f)
        part.setAngles(0f, 0f, 0f)
        val min = Vector3f(Float.MAX_VALUE)
        val max = Vector3f(-Float.MAX_VALUE)
        part.collectVertices(MatrixStack()) { min.min(it); max.max(it) }
        part.setTransform(saved)
        if (min.x > max.x) Vector3f() else Vector3f(max).add(min).mul(0.5f)
    }

    fun submit(state: BoltRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue) {
        // Abgekühlter Rohling (Brontes tauscht ihn gegen ein Geschoss mit Größe < 1): ohne Glut, sofort erkennbar.
        val cold = state.kind == HermesBoltEntity.INGOT && state.size < 1f
        val name = if (cold) "ingot_cold" else bone(state.kind)
        val part = child(name) ?: return
        val f = state.direction
        val flat = Vec3d(f.x, 0.0, f.z).let { if (it.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
        val moving = state.velocity.lengthSquared() > 0.0025
        val age = state.age

        matrices.push()
        when (state.kind) {
            HermesBoltEntity.KERAUNOS, HermesBoltEntity.HAMMER_BOLT -> {
                // Längsachse (-y) in Flugrichtung, dann Drehung um die Längsachse.
                matrices.multiply(frame(f))
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90f))
                if (moving) matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(age * if (state.kind == HermesBoltEntity.KERAUNOS) 14f else 24f))
            }
            HermesBoltEntity.EAGLE -> {
                matrices.multiply(frame(f))
                // Flügelschlag (Periode 10 Ticks); im steilen Sturz angelegt.
                val dive = MathHelper.clamp((-f.y.toFloat() - 0.25f) / 0.5f, 0f, 1f)
                val beat = sin(age * MathHelper.TAU / EAGLE_BEAT) * 0.55f * (1f - dive)
                wingR?.let { it.resetTransform(); it.roll = -beat + EAGLE_TUCK_ROLL * dive; it.yaw = -EAGLE_TUCK_YAW * dive }
                wingL?.let { it.resetTransform(); it.roll = beat - EAGLE_TUCK_ROLL * dive; it.yaw = EAGLE_TUCK_YAW * dive }
            }
            HermesBoltEntity.INGOT, HermesBoltEntity.TORCH -> {
                matrices.multiply(frame(flat))
                if (moving) matrices.multiply(RotationAxis.NEGATIVE_X.rotationDegrees(age * if (state.kind == HermesBoltEntity.TORCH) 26f else 18f))
            }
            HermesBoltEntity.BLADE -> matrices.multiply(frame(flat))
            HermesBoltEntity.SHIELD -> {
                // Liegt flach auf dem Boden (Schauseite -z nach oben), halbe Dicke angehoben, damit er nicht einsinkt.
                matrices.translate(0.0, SHIELD_LIFT, 0.0)
                matrices.multiply(frame(flat))
                matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90f))
            }
            HermesBoltEntity.WREATH -> matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(wreathSpin(age)))
        }
        val fixedSize = cold || state.kind == HermesBoltEntity.SHIELD || state.kind == HermesBoltEntity.WREATH
        val s = scale(state.kind) * if (fixedSize) 1f else max(state.size, 0.05f)
        matrices.scale(s, s, s)
        val c = center(name, part)
        matrices.translate(-c.x / 16f, -c.y / 16f, -c.z / 16f)
        draw(part, matrices, queue, state.light)
        matrices.pop()
    }

    /**
     * Zeichnet einen Requisiten-Knochen an der aktuellen Stelle des Stapels, um seinen Ursprung, im Maßstab 1
     * (Einheiten des Modells): `shield` (Vorderseite -z, für „liegt am Boden“ vorher um 90° um x drehen), `wreath`
     * (waagerecht; [wreathSpin] dreht ihn langsam), `ingot_cold` und alle Flug-Knochen.
     */
    fun submitBone(name: String, matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int) {
        val part = child(name) ?: return
        matrices.push()
        if (name == "eagle") {
            wingR?.resetTransform()
            wingL?.resetTransform()
        }
        draw(part, matrices, queue, light)
        matrices.pop()
    }

    private fun draw(part: ModelPart, matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int) {
        // Der Knochen liegt im Modell an seinem Ursprung (0, 0, 0); sicherheitshalber ohne eigene Lage zeichnen.
        val saved = part.getTransform()
        part.setOrigin(0f, 0f, 0f)
        part.setAngles(0f, 0f, 0f)
        queue.submitModelPart(part, matrices, layer, light, OverlayTexture.DEFAULT_UV, null)
        queue.getBatchingQueue(1).submitModelPart(part, matrices, glow, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, null)
        part.setTransform(saved)
    }

    /** Flugrahmen: -z zeigt in Flugrichtung [f], x bleibt waagerecht. */
    private fun frame(f: Vec3d): Quaternionf {
        val forward = Vector3f(f.x.toFloat(), f.y.toFloat(), f.z.toFloat())
        if (forward.lengthSquared() < 1e-6f) forward.set(0f, 0f, 1f) else forward.normalize()
        val right = Vector3f(forward).cross(0f, 1f, 0f)
        if (right.lengthSquared() < 1e-6f) right.set(1f, 0f, 0f) else right.normalize()
        val up = Vector3f(right).cross(forward)
        return Quaternionf().setFromNormalized(Matrix3f().set(right, up, Vector3f(forward).negate()))
    }

    companion object {
        /** Flügelschlag des Adlers in Ticks (Brief). */
        const val EAGLE_BEAT = 10f

        /** Angelegte Flügel im Sturz; dieselben Winkel wie `EAGLE_TUCK` in olymp_props_model.py. */
        const val EAGLE_TUCK_YAW = 0.55f
        const val EAGLE_TUCK_ROLL = 0.35f

        /** Halbe Dicke des Schilds (3 Einheiten × 0,70 / 16 / 2) plus etwas Luft: liegt auf, sinkt nicht ein. */
        const val SHIELD_LIFT = 0.075

        /** Drehung des Kranzes in Grad je Tick (für Szenen, die ihn zeichnen). */
        fun wreathSpin(age: Float): Float = age * 1.5f

        /** Für Szenen ohne Renderer-Kontext (z. B. Nikes Kranz über dem Sieger). */
        fun fromClient(): OlympProps =
            OlympProps(MinecraftClient.getInstance().loadedEntityModels.getModelPart(BossLayers.OLYMP_PROPS))
    }
}
