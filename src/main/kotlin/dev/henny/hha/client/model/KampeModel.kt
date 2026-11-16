package dev.henny.hha.client.model

import dev.henny.hha.client.vfx.BossGlow
import dev.henny.hha.entity.miniboss.KampeEntity
import dev.henny.hha.entity.miniboss.KampePose
import dev.henny.hha.logic.boss.BossTimings
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
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Render-Zustand der Kampe: wie bei den Bossen Pose, vorige Pose und Zeit seit dem Wechsel. */
class KampeRenderState : LivingEntityRenderState() {
    var entityId = 0
    var bossPose = KampePose.IDLE
    var previousPose = KampePose.IDLE
    var poseTime = 0f
}

/**
 * Kampe: Frau, Schlangenleib, Skorpionschwanz (Brief `tmp/overhaul/models/kampe.md`, Modell `tools/hha_assets/kampe_model.py`).
 *
 * Die Schlüsselposen stehen nicht hier, sondern als [KampeKeys] im generierten Block am Ende dieser Datei: dieselben
 * Winkel wie `POSES` in der Python-Datei, die `preview.py` zeigt. Jede Pose wird über `poseTime` aus diesen Schlüsseln
 * überblendet (Ausholen weich hinein und halten, Schlag hart, Nachlauf, Erholung); die Zeiten kommen aus
 * [BossTimings.Kampe]. Darüber liegt die Dauerbewegung: Welle durch den Leib, Kobrawiegen, züngelnde Haar-Vipern,
 * pendelnder Stachel, zitternde Schwingen.
 */
class KampeModel(root: ModelPart) : EntityModel<KampeRenderState>(root) {

    /** Alle Knochen in der Reihenfolge von [KampeKeys.BONES]. */
    private val bones: Array<ModelPart> = Array(KampeKeys.BONES.size) { i ->
        KampeKeys.BONES[i].split('/').fold(root) { part, name -> part.getChild(name) }
    }
    private val count = bones.size

    /** Kopf und Stachel für den Konter (Index in [KampeKeys.BONES], −1 = nicht gefunden). */
    private val headBone = KampeKeys.BONES.indexOfFirst { it.endsWith("/head") }
    private val stingBone = KampeKeys.BONES.indexOfFirst { it.endsWith("/sting") }
    private val base = FloatArray(count * 6)
    private val current = FloatArray(count * 6)
    private val previous = FloatArray(count * 6)

    /** Reihenfolge beim Zusammensacken im Tod: 0 zuerst. */
    private val collapseOrder = IntArray(count)

    private val body = root.getChild("root")

    // Aufsteigender Teil: trägt den Oberkörper.
    private val tail0 = body.getChild("tail_0")
    private val tail1 = tail0.getChild("tail_1")
    private val tail2 = tail1.getChild("tail_2")
    private val hips = tail2.getChild("hips")
    private val chest = hips.getChild("chest")
    private val head = chest.getChild("head")
    private val jaw = head.getChild("jaw")
    private val hair = List(HAIR) { head.getChild("hair_$it") }
    private val hairTips = List(HAIR) { hair[it].getChild("hair_${it}_1") }
    private val armR = chest.getChild("arm_r")
    private val foreR = armR.getChild("fore_r")
    private val clawR = foreR.getChild("claw_r")
    private val armL = chest.getChild("arm_l")
    private val foreL = armL.getChild("fore_l")
    private val clawL = foreL.getChild("claw_l")
    private val wingR = chest.getChild("wing_r")
    private val wingL = chest.getChild("wing_l")

    /** Liegender Teil und Skorpionschwanz: tail_3 … tail_9, jedes Glied Kind des vorigen. */
    private val tail: List<ModelPart> = buildList {
        var part = body.getChild("tail_3")
        add(part)
        for (i in 4..9) {
            part = part.getChild("tail_$i")
            add(part)
        }
    }
    private val sting = tail.last().getChild("sting")
    private val rising = listOf(tail0, tail1, tail2)

    init {
        for ((i, path) in KampeKeys.BONES.withIndex()) {
            val name = path.substringAfterLast('/')
            collapseOrder[i] = when {
                name == "sting" -> 0
                name.length == 6 && name.startsWith("tail_") -> {
                    val n = name[5] - '0'
                    if (n >= 3) 9 - n else 4 + (2 - n)            // Skorpion zuerst, der aufsteigende Leib zuletzt
                }
                name == "hips" -> 3
                else -> 1
            }
        }
    }

    override fun setAngles(state: KampeRenderState) {
        super.setAngles(state)
        capture(base)
        val t = state.poseTime
        evaluate(state.bossPose, t, current)
        if (state.previousPose != state.bossPose && t < BossTimings.Anim.BLEND_SOFT) {
            // Weich aus der Endhaltung der vorigen Pose heraus (z. B. vom Hochwinden in den Sturzstoß).
            evaluate(state.previousPose, settle(state.previousPose), previous)
            val w = step(t, 0f, BossTimings.Anim.BLEND_SOFT.toFloat())
            for (i in current.indices) current[i] = MathHelper.lerp(w, previous[i], current[i])
        }
        apply(current)
        secondary(state)
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Schlüsselposen
    // ---------------------------------------------------------------------------------------------------------------

    private fun capture(out: FloatArray) {
        for (i in 0 until count) {
            val p = bones[i]
            val o = i * 6
            out[o] = p.pitch; out[o + 1] = p.yaw; out[o + 2] = p.roll
            out[o + 3] = p.originX; out[o + 4] = p.originY; out[o + 5] = p.originZ
        }
    }

    private fun apply(src: FloatArray) {
        for (i in 0 until count) {
            val p = bones[i]
            val o = i * 6
            p.pitch = src[o]; p.yaw = src[o + 1]; p.roll = src[o + 2]
            p.originX = src[o + 3]; p.originY = src[o + 4]; p.originZ = src[o + 5]
        }
    }

    /**
     * Blendet die Knochen eines Schlüssels ein (Winkel absolut, Versatz relativ zur Grundlage). Mit [stagger] > 0 hat
     * jeder Knochen sein eigenes Gewicht: er beginnt bei `from + Reihenfolge * stagger` und braucht [span] Ticks.
     */
    private fun mix(out: FloatArray, key: FloatArray, w: Float, stagger: Float = 0f, t: Float = 0f, from: Float = 0f, span: Float = 1f) {
        if (w <= 0f) return
        var k = 0
        while (k < key.size) {
            val i = key[k].toInt()
            val start = from + collapseOrder[i] * stagger
            val weight = if (stagger > 0f) step(t, start, start + span) * w else w
            if (weight > 0f) {
                val o = i * 6
                out[o] = MathHelper.lerp(weight, out[o], key[k + 1])
                out[o + 1] = MathHelper.lerp(weight, out[o + 1], key[k + 2])
                out[o + 2] = MathHelper.lerp(weight, out[o + 2], key[k + 3])
                out[o + 3] = MathHelper.lerp(weight, out[o + 3], base[o + 3] + key[k + 4])
                out[o + 4] = MathHelper.lerp(weight, out[o + 4], base[o + 4] + key[k + 5])
                out[o + 5] = MathHelper.lerp(weight, out[o + 5], base[o + 5] + key[k + 6])
            }
            k += 7
        }
    }

    /** Zurück in die Grundhaltung. */
    private fun rest(out: FloatArray, w: Float) {
        if (w <= 0f) return
        for (i in out.indices) out[i] = MathHelper.lerp(w, out[i], base[i])
    }

    /** Zeitpunkt, an dem eine Pose „angekommen" ist; von dort blendet die nächste Pose weich heraus. */
    private fun settle(pose: Int): Float {
        val k = BossTimings.Kampe
        return when (pose) {
            KampePose.TAIL_SWEEP -> k.SWEEP_END.toFloat()
            KampePose.SPIT -> k.SPIT_END.toFloat()
            KampePose.STING -> k.STING_END.toFloat()
            KampePose.COIL_UP -> k.COIL.toFloat()
            KampePose.DIVE -> k.DIVE_WINDUP + 6f
            KampePose.DAZED -> k.DAZED.toFloat()
            KampePose.ROAR -> k.ROAR.toFloat()
            KampePose.DEATH -> k.DEATH.toFloat()
            KampePose.ANTI_AIR -> BossTimings.Guardian.ANTI_AIR_END.toFloat()
            else -> 0f
        }
    }

    private fun evaluate(pose: Int, t: Float, out: FloatArray) {
        base.copyInto(out)
        val k = BossTimings.Kampe
        val soft = BossTimings.Anim.BLEND_SOFT.toFloat()
        val hard = BossTimings.Anim.BLEND_HARD.toFloat()
        when (pose) {
            KampePose.TAIL_SWEEP -> {
                // Ausholen weich hinein und halten bis 18, Feger 18–22, ausgeschwungen gehalten, zurück bis 40.
                val hit = k.SWEEP_WINDUP.toFloat()
                mix(out, KampeKeys.SWEEP_WINDUP, step(t, 0f, soft))
                mix(out, KampeKeys.SWEEP_HIT, step(t, hit, hit + k.SWEEP_STRIKE))
                rest(out, step(t, k.SWEEP_END - 10f, k.SWEEP_END.toFloat()))
            }
            KampePose.SPIT -> {
                // Aufrichten, Kopf zurück, Haube, Kiefer auf bis 16; Speien 16 (Kopfstoß in 2 Ticks); zurück bis 30.
                val hit = k.SPIT_WINDUP.toFloat()
                mix(out, KampeKeys.SPIT_WINDUP, step(t, 0f, soft))
                mix(out, KampeKeys.SPIT_RELEASE, step(t, hit, hit + hard))
                rest(out, step(t, k.SPIT_END - 8f, k.SPIT_END.toFloat()))
            }
            KampePose.STING -> {
                // Stachel hoch zurück bis 20; Stich 20–22; steckt bis 56 (sie zerrt, siehe secondary); zurück bis 64.
                val hit = k.STING_WINDUP.toFloat()
                val struck = hit + k.STING_STRIKE
                mix(out, KampeKeys.STING_WINDUP, step(t, 0f, soft))
                mix(out, KampeKeys.STING_HIT, step(t, hit, struck))
                mix(out, KampeKeys.STING_STUCK, step(t, struck + 2f, struck + 10f))
                rest(out, step(t, k.STING_STUCK_END.toFloat(), k.STING_END.toFloat()))
            }
            KampePose.COIL_UP -> mix(out, KampeKeys.COIL, step(t, 0f, 12f))
            KampePose.DIVE -> {
                // Oben zurückgelehnt bis 24, dann gestreckt Kopf voran (bleibt, bis der Server die Pose wechselt).
                val hit = k.DIVE_WINDUP.toFloat()
                mix(out, KampeKeys.DIVE_WINDUP, step(t, 0f, soft))
                mix(out, KampeKeys.DIVE, step(t, hit, hit + hard))
            }
            KampePose.DAZED -> mix(out, KampeKeys.DAZED, step(t, 0f, 10f))
            KampePose.ANTI_AIR -> {
                // Konter (Überarbeitung 2, PLAN 4.3): Sie reißt den Stachel aus der Ausholhaltung des Stichs (STING_WINDUP)
                // senkrecht nach oben, beim Ende der Ansage bäumt sie sich auf und schaut hinauf; dann zurück.
                val g = BossTimings.Guardian
                val raise = step(t, 0f, g.ANTI_AIR_RAISE.toFloat())
                mix(out, KampeKeys.STING_WINDUP, raise)
                val jab = hold(t - (g.ANTI_AIR_HIT - 1f), 2f, 5f, 10f)
                out[4] -= 2f * jab
                if (headBone >= 0) out[headBone * 6] -= 0.35f * raise
                if (stingBone >= 0) out[stingBone * 6] -= 0.3f * jab
                rest(out, step(t, g.ANTI_AIR_END - 8f, g.ANTI_AIR_END.toFloat()))
            }
            KampePose.ROAR -> {
                mix(out, KampeKeys.ROAR, step(t, 0f, 6f))
                rest(out, step(t, k.ROAR - 6f, k.ROAR.toFloat()))
            }
            KampePose.DEATH -> {
                // Aufbäumen bis 8, dann sackt der Leib Glied für Glied zusammen (Stachel zuerst, Oberkörper zuletzt).
                mix(out, KampeKeys.DEATH_REAR, step(t, 0f, 8f))
                mix(out, KampeKeys.DEATH, 1f, stagger = 2.5f, t = t, from = 12f, span = 7f)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Dauerbewegung
    // ---------------------------------------------------------------------------------------------------------------

    private fun secondary(state: KampeRenderState) {
        val t = state.age
        val pose = state.bossPose
        val pt = state.poseTime
        val walk = min(state.limbSwingAmplitude, 1f)
        val limp = pose == KampePose.DAZED || pose == KampePose.DEATH
        // Wie stark die Ruhebewegung unter einer Pose noch durchkommt.
        val calm = when (pose) {
            KampePose.IDLE, KampePose.ROAR, KampePose.COIL_UP -> 1f
            KampePose.DAZED, KampePose.DEATH -> 0f
            else -> 0.35f
        }

        // Welle durch den Leib: Phasenversatz je Glied, zum Schwanz hin größer; beim Gleiten schneller und stärker.
        val speed = 0.07f + walk * 0.22f
        for ((i, part) in tail.withIndex()) {
            val amp = (0.025f + i * 0.012f) * calm * (1f + walk * 0.8f)
            part.yaw += sin(t * speed - i * 0.75f) * amp
            if (i >= 2) part.pitch += sin(t * speed * 0.8f - i * 0.6f + 1f) * 0.02f * calm
        }
        for ((i, part) in rising.withIndex()) {
            part.roll += sin(t * speed * 0.8f - i * 0.9f) * 0.035f * calm * (1f + walk)
            part.pitch += sin(t * speed * 0.6f - i * 1.1f) * 0.02f * calm
        }
        // Gleiten: leichtes Auf und Ab, der Oberkörper neigt sich in die Bewegung.
        body.originY += sin(t * speed * 2f) * walk * 0.8f
        hips.pitch += walk * 0.12f

        // Kobra: der Oberkörper wiegt, der Kopf folgt dem Ziel, der Stachel pendelt und zeigt mit.
        if (!limp) {
            val look = MathHelper.clamp(state.relativeHeadYaw, -60f, 60f) * DEG
            chest.yaw += sin(t * 0.045f + 1f) * 0.07f * calm + look * 0.3f
            chest.roll += sin(t * 0.045f) * 0.03f * calm
            chest.pitch += sin(t * 0.09f) * 0.015f                        // Atmen
            head.yaw += look * 0.6f
            head.pitch += MathHelper.clamp(state.pitch, -40f, 40f) * DEG * 0.6f
            tail[2].yaw += look * 0.35f * calm
            sting.pitch += sin(t * 0.05f) * 0.08f * calm
            tail[6].pitch += sin(t * 0.05f - 0.8f) * 0.04f * calm
        } else {
            chest.pitch += sin(t * 0.08f) * 0.03f
            head.roll += sin(t * 0.05f) * 0.04f
        }

        // Haar-Vipern züngeln einzeln, der Kiefer arbeitet leise, die Krallen zucken.
        val alive = if (limp) 0.3f else 1f
        for (i in 0 until HAIR) {
            hair[i].yaw += sin(t * 0.19f + i * 1.3f) * 0.12f * alive
            hair[i].pitch += sin(t * 0.13f + i * 2.1f) * 0.06f * alive
            hairTips[i].pitch += sin(t * 0.27f + i * 0.9f) * 0.16f * alive
            hairTips[i].yaw += sin(t * 0.31f + i * 1.7f) * 0.1f * alive
        }
        if (!limp) jaw.pitch += (0.5f + 0.5f * sin(t * 0.1f)) * 0.04f
        clawR.pitch += sin(t * 0.07f) * 0.06f
        clawL.pitch += sin(t * 0.07f + 1.1f) * 0.06f
        armR.pitch += sin(t * 0.05f) * 0.03f * calm
        armL.pitch += sin(t * 0.05f + 2f) * 0.03f * calm
        foreR.pitch += sin(t * 0.05f + 0.6f) * 0.03f * calm
        foreL.pitch += sin(t * 0.05f + 2.6f) * 0.03f * calm

        // Schwingen zittern leicht.
        val shiver = if (limp) 0f else 1f
        wingR.yaw += sin(t * 0.9f) * 0.02f * shiver
        wingL.yaw += sin(t * 0.9f + 1.7f) * 0.02f * shiver
        wingR.roll += sin(t * 1.3f) * 0.012f * shiver
        wingL.roll += sin(t * 1.3f + 0.6f) * 0.012f * shiver

        val k = BossTimings.Kampe
        when (pose) {
            KampePose.STING -> {
                // Ausholen: der gespannte Bogen bebt. Steckt der Stachel, zerrt sie und windet den Oberkörper.
                val hit = k.STING_WINDUP.toFloat()
                val tense = step(pt, 6f, hit) * (1f - step(pt, hit, hit + 1f))
                tail[5].pitch += sin(t * 1.7f) * 0.025f * tense
                val stuck = step(pt, hit + 4f, hit + 10f) * (1f - step(pt, k.STING_STUCK_END - 4f, k.STING_STUCK_END.toFloat()))
                val tug = sin(pt * 0.42f)
                chest.yaw += tug * 0.28f * stuck
                hips.yaw += sin(pt * 0.42f + 0.6f) * 0.16f * stuck
                tail2.roll += sin(pt * 0.42f + 1.2f) * 0.12f * stuck
                tail1.roll += sin(pt * 0.42f + 1.9f) * 0.08f * stuck
                armR.pitch += sin(pt * 0.5f) * 0.25f * stuck
                armL.pitch += sin(pt * 0.5f + PI.toFloat()) * 0.25f * stuck
                tail[1].yaw += tug * 0.05f * stuck
                tail[2].roll += tug * 0.04f * stuck
            }
            KampePose.COIL_UP -> {
                // Sie schraubt sich hoch: eine volle Drehung über die Aufstiegszeit, die Spirale pulsiert.
                body.yaw += step(pt, 0f, k.COIL.toFloat()) * 2f * PI.toFloat()
                for ((i, part) in tail.withIndex()) part.pitch += sin(pt * 0.5f - i * 0.9f) * 0.05f
            }
            KampePose.DIVE -> {
                val dive = step(pt, k.DIVE_WINDUP.toFloat(), k.DIVE_WINDUP + 2f)
                for ((i, part) in tail.withIndex()) part.yaw += sin(t * 0.8f - i * 0.9f) * 0.05f * dive
                val wind = step(pt, 0f, k.DIVE_WINDUP.toFloat()) * (1f - dive)
                wingR.roll += sin(t * 0.6f) * 0.08f * wind
                wingL.roll -= sin(t * 0.6f) * 0.08f * wind
            }
            KampePose.ROAR -> {
                val roar = step(pt, 2f, 6f) * (1f - step(pt, k.ROAR - 6f, k.ROAR.toFloat()))
                chest.pitch += sin(t * 2.1f) * 0.02f * roar
                jaw.pitch += sin(t * 2.7f) * 0.05f * roar
            }
            KampePose.DEATH -> {
                // Während des Aufbäumens sticht der Stachel zweimal ins Leere.
                val jab = step(pt, 2f, 4f) * (1f - step(pt, 10f, 12f))
                val stab = sin(pt * 1.1f).coerceAtLeast(0f)
                sting.pitch += stab * 0.7f * jab
                tail[6].pitch += stab * 0.3f * jab
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Anker
    // ---------------------------------------------------------------------------------------------------------------

    /** Anker in Weltkoordinaten (Brief: sting_tip, sting_base, mouth, eye_r, eye_l, tail_mid); Maße aus kampe_model.py. */
    fun readAnchors(matrices: MatrixStack, cam: Vec3d, out: BossGlow.KampeFrame) {
        val scratch = Vector3f()
        fun at(x: Float, y: Float, z: Float): Vec3d {
            matrices.peek().positionMatrix.transformPosition(x / 16f, y / 16f, z / 16f, scratch)
            return Vec3d(scratch.x + cam.x, scratch.y + cam.y, scratch.z + cam.z)
        }
        matrices.push()
        body.applyTransform(matrices)
        matrices.push()
        for (part in listOf(tail0, tail1, tail2, hips, chest, head)) part.applyTransform(matrices)
        out.eyeR = at(-2.5f, -7.5f, -5.6f)
        out.eyeL = at(2.5f, -7.5f, -5.6f)
        jaw.applyTransform(matrices)
        out.mouth = at(0f, 0.5f, -7f)
        matrices.pop()
        matrices.push()
        for ((i, part) in tail.withIndex()) {
            part.applyTransform(matrices)
            if (i == MID_SEGMENT) out.tailMid = at(0f, 0f, 14f)
        }
        sting.applyTransform(matrices)
        out.stingBase = at(0f, 0f, 4.5f)
        out.stingTip = at(0f, -7f, 23f)
        matrices.pop()
        matrices.pop()
    }

    private companion object {
        const val DEG = 0.017453292f
        const val HAIR = 6

        /** tail_5 ist das dritte Glied der Liste (tail_3 = 0). */
        const val MID_SEGMENT = 2

        fun step(t: Float, from: Float, to: Float): Float {
            val x = ((t - from) / (to - from)).coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }

        /** Hoch bis [peak], halten bis [release], weich zurück bis [end] (wie bei den anderen Wächtern). */
        fun hold(t: Float, peak: Float, release: Float, end: Float): Float = step(t, 0f, peak) * (1f - step(t, release, end))
    }
}

class KampeRenderer(context: EntityRendererFactory.Context) :
    LivingEntityRenderer<KampeEntity, KampeRenderState, KampeModel>(context, KampeModel(context.getPart(BossLayers.KAMPE)), 1.3f) {

    private val texture = boss("kampe")
    private val glowLayer = RenderLayers.eyes(boss("kampe_glow"))

    init {
        addFeature(object : FeatureRenderer<KampeRenderState, KampeModel>(this) {
            override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: KampeRenderState, limbAngle: Float, limbDistance: Float) {
                if (state.invisibleToPlayer) return
                queue.getBatchingQueue(1).submitModel(contextModel, state, matrices, glowLayer, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, -1, null, 0, null)
                val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
                val frame = BossGlow.KampeFrame(state.entityId, state.age, state.bossPose)
                frame.poseTime = state.poseTime
                model.readAnchors(matrices, cam, frame)
                BossGlow.submit(frame)
            }
        })
    }

    override fun createRenderState() = KampeRenderState()

    override fun getTexture(state: KampeRenderState): Identifier = texture

    /** Keine Vanilla-Todesdrehung zur Seite: Kampe hat eine eigene Todespose (`KampePose.DEATH`, 40 Ticks). */
    override fun getLyingPositionRotationDegrees(): Float = 0f

    override fun updateRenderState(entity: KampeEntity, state: KampeRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.entityId = entity.id
        state.bossPose = entity.pose
        state.previousPose = entity.previousPose
        state.poseTime = (entity.age - entity.poseChangedAge) + tickDelta
    }

    override fun scale(state: KampeRenderState, matrices: MatrixStack) {
        matrices.scale(GuardianScale.KAMPE, GuardianScale.KAMPE, GuardianScale.KAMPE)
    }

    /** Der Schwanz reicht weit über die Trefferbox hinaus und darf nicht mit ihr verschwinden. */
    override fun canBeCulled(entity: KampeEntity): Boolean = false

    override fun hasLabel(entity: KampeEntity, squaredDistance: Double): Boolean = false
}

// <kampe-poses> GENERIERT von tools/hha_assets/kampe_model.py (POSES) — nicht von Hand ändern.
internal object KampeKeys {
    /** Pfade aller Knochen ab der Modellwurzel. */
    val BONES = arrayOf("root", "root/tail_0", "root/tail_0/tail_1", "root/tail_0/tail_1/tail_2", "root/tail_0/tail_1/tail_2/hips", "root/tail_0/tail_1/tail_2/hips/chest", "root/tail_0/tail_1/tail_2/hips/chest/head", "root/tail_0/tail_1/tail_2/hips/chest/head/jaw", "root/tail_0/tail_1/tail_2/hips/chest/head/hair_0", "root/tail_0/tail_1/tail_2/hips/chest/head/hair_0/hair_0_1", "root/tail_0/tail_1/tail_2/hips/chest/head/hair_1", "root/tail_0/tail_1/tail_2/hips/chest/head/hair_1/hair_1_1", "root/tail_0/tail_1/tail_2/hips/chest/head/hair_2", "root/tail_0/tail_1/tail_2/hips/chest/head/hair_2/hair_2_1", "root/tail_0/tail_1/tail_2/hips/chest/head/hair_3", "root/tail_0/tail_1/tail_2/hips/chest/head/hair_3/hair_3_1", "root/tail_0/tail_1/tail_2/hips/chest/head/hair_4", "root/tail_0/tail_1/tail_2/hips/chest/head/hair_4/hair_4_1", "root/tail_0/tail_1/tail_2/hips/chest/head/hair_5", "root/tail_0/tail_1/tail_2/hips/chest/head/hair_5/hair_5_1", "root/tail_0/tail_1/tail_2/hips/chest/arm_r", "root/tail_0/tail_1/tail_2/hips/chest/arm_r/fore_r", "root/tail_0/tail_1/tail_2/hips/chest/arm_r/fore_r/claw_r", "root/tail_0/tail_1/tail_2/hips/chest/arm_l", "root/tail_0/tail_1/tail_2/hips/chest/arm_l/fore_l", "root/tail_0/tail_1/tail_2/hips/chest/arm_l/fore_l/claw_l", "root/tail_0/tail_1/tail_2/hips/chest/wing_r", "root/tail_0/tail_1/tail_2/hips/chest/wing_r/wing_r_0", "root/tail_0/tail_1/tail_2/hips/chest/wing_r/wing_r_1", "root/tail_0/tail_1/tail_2/hips/chest/wing_r/wing_r_2", "root/tail_0/tail_1/tail_2/hips/chest/wing_l", "root/tail_0/tail_1/tail_2/hips/chest/wing_l/wing_l_0", "root/tail_0/tail_1/tail_2/hips/chest/wing_l/wing_l_1", "root/tail_0/tail_1/tail_2/hips/chest/wing_l/wing_l_2", "root/tail_3", "root/tail_3/tail_4", "root/tail_3/tail_4/tail_5", "root/tail_3/tail_4/tail_5/tail_6", "root/tail_3/tail_4/tail_5/tail_6/tail_7", "root/tail_3/tail_4/tail_5/tail_6/tail_7/tail_8", "root/tail_3/tail_4/tail_5/tail_6/tail_7/tail_8/tail_9", "root/tail_3/tail_4/tail_5/tail_6/tail_7/tail_8/tail_9/sting")

    /** Je Knochen sieben Zahlen: Index, pitch, yaw, roll (Radiant, absolut), dx, dy, dz (Einheiten). */
    val SWEEP_WINDUP = floatArrayOf(
        1f, -0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        2f, 0.4189f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        3f, 0.2443f, -0.2443f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        4f, 0.1047f, -0.2443f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        5f, 0.2793f, -0.4189f, -0.1047f, 0.0000f, 0.0000f, 0.0000f,
        6f, -0.3142f, 0.5236f, 0.1047f, 0.0000f, 0.0000f, 0.0000f,
        20f, -0.6981f, 0.0000f, 0.6981f, 0.0000f, 0.0000f, 0.0000f,
        21f, -0.8727f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        23f, -0.8727f, 0.0000f, -0.3491f, 0.0000f, 0.0000f, 0.0000f,
        24f, -1.0472f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        34f, 0.0000f, 1.3963f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        35f, -0.0524f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        36f, -0.0349f, 0.1396f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        37f, 0.0000f, 0.1396f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        38f, 0.0000f, 0.1396f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        39f, 0.0000f, 0.1396f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        40f, 0.0000f, 0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        41f, -0.1396f, 0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        26f, 0.3630f, -0.6423f, 0.0349f, 0.0000f, 0.0000f, 0.0000f,
        30f, 0.3630f, 0.6423f, -0.0349f, 0.0000f, 0.0000f, 0.0000f,
        27f, 0.4747f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        28f, -0.2304f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        29f, -0.8866f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        31f, 0.4747f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        32f, -0.2304f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        33f, -0.8866f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
    )
    val SWEEP_HIT = floatArrayOf(
        1f, -0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        2f, 0.3840f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        3f, 0.1745f, 0.3142f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        4f, 0.0698f, 0.2793f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        5f, 0.1745f, 0.5236f, 0.1396f, 0.0000f, 0.0000f, 0.0000f,
        6f, -0.1745f, -0.4189f, -0.1047f, 0.0000f, 0.0000f, 0.0000f,
        20f, -1.0472f, 0.0000f, 0.5236f, 0.0000f, 0.0000f, 0.0000f,
        21f, -0.8727f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        23f, -0.5236f, 0.0000f, -0.8727f, 0.0000f, 0.0000f, 0.0000f,
        24f, -1.0472f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        34f, 0.0000f, -1.3963f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        35f, -0.0524f, 0.3491f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        36f, -0.0349f, 0.2443f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        37f, 0.0000f, 0.2094f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        38f, 0.0000f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        39f, 0.0000f, 0.1396f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        40f, 0.0000f, 0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        41f, -0.1396f, 0.0698f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        26f, 0.3630f, -0.6423f, 0.0349f, 0.0000f, 0.0000f, 0.0000f,
        30f, 0.3630f, 0.6423f, -0.0349f, 0.0000f, 0.0000f, 0.0000f,
        27f, 0.4747f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        28f, -0.2304f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        29f, -0.8866f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        31f, 0.4747f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        32f, -0.2304f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        33f, -0.8866f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
    )
    val SPIT_WINDUP = floatArrayOf(
        1f, -0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        2f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        3f, -0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        4f, -0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        5f, -0.2443f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        6f, -0.5934f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        7f, 0.5934f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        20f, -0.5236f, 0.0000f, 0.5236f, 0.0000f, 0.0000f, 0.0000f,
        21f, -1.2217f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        23f, -0.5236f, 0.0000f, -0.5236f, 0.0000f, 0.0000f, 0.0000f,
        24f, -1.2217f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        36f, 1.2217f, 0.0000f, -0.1396f, 0.0000f, 0.0000f, 0.0000f,
        38f, 0.1396f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        39f, 0.2443f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        40f, 0.2793f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        41f, 0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        8f, 1.8326f, 0.0000f, 1.0472f, 0.0000f, 0.0000f, 0.0000f,
        9f, -0.4189f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        10f, 1.9373f, 0.0000f, 0.6283f, 0.0000f, 0.0000f, 0.0000f,
        11f, -0.4189f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        12f, 2.0420f, 0.0000f, 0.2094f, 0.0000f, 0.0000f, 0.0000f,
        13f, -0.4189f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        14f, 2.0420f, 0.0000f, -0.2094f, 0.0000f, 0.0000f, 0.0000f,
        15f, -0.4189f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        16f, 1.9373f, 0.0000f, -0.6283f, 0.0000f, 0.0000f, 0.0000f,
        17f, -0.4189f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        18f, 1.8326f, 0.0000f, -1.0472f, 0.0000f, 0.0000f, 0.0000f,
        19f, -0.4189f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        26f, 0.2950f, -0.7837f, 0.0916f, 0.0000f, 0.0000f, 0.0000f,
        30f, 0.2950f, 0.7837f, -0.0916f, 0.0000f, 0.0000f, 0.0000f,
        27f, 0.5166f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        28f, -0.2199f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        29f, -0.8709f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        31f, 0.5166f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        32f, -0.2199f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        33f, -0.8709f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
    )
    val SPIT_RELEASE = floatArrayOf(
        1f, -0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        2f, 0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        3f, 0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        4f, 0.0698f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        5f, 0.3840f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        6f, 0.4538f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        7f, 0.8029f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        20f, -0.3491f, 0.0000f, 0.6981f, 0.0000f, 0.0000f, 0.0000f,
        21f, -1.2217f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        23f, -0.3491f, 0.0000f, -0.6981f, 0.0000f, 0.0000f, 0.0000f,
        24f, -1.2217f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        36f, 1.2217f, 0.0000f, -0.1396f, 0.0000f, 0.0000f, 0.0000f,
        38f, 0.1396f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        39f, 0.2443f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        40f, 0.2793f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        41f, 0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        8f, 1.4835f, 0.0000f, 1.1345f, 0.0000f, 0.0000f, 0.0000f,
        9f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        10f, 1.5882f, 0.0000f, 0.6807f, 0.0000f, 0.0000f, 0.0000f,
        11f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        12f, 1.6930f, 0.0000f, 0.2269f, 0.0000f, 0.0000f, 0.0000f,
        13f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        14f, 1.6930f, 0.0000f, -0.2269f, 0.0000f, 0.0000f, 0.0000f,
        15f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        16f, 1.5882f, 0.0000f, -0.6807f, 0.0000f, 0.0000f, 0.0000f,
        17f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        18f, 1.4835f, 0.0000f, -1.1345f, 0.0000f, 0.0000f, 0.0000f,
        19f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        26f, 0.2950f, -0.7837f, 0.0916f, 0.0000f, 0.0000f, 0.0000f,
        30f, 0.2950f, 0.7837f, -0.0916f, 0.0000f, 0.0000f, 0.0000f,
        27f, 0.5166f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        28f, -0.2199f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        29f, -0.8709f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        31f, 0.5166f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        32f, -0.2199f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        33f, -0.8709f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
    )
    val STING_WINDUP = floatArrayOf(
        1f, -0.2094f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        2f, 0.3840f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        3f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        4f, -0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        5f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        6f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        20f, -0.3491f, 0.0000f, 1.2217f, 0.0000f, 0.0000f, 0.0000f,
        21f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        22f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        23f, -0.3491f, 0.0000f, -1.2217f, 0.0000f, 0.0000f, 0.0000f,
        24f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        25f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        34f, 0.0000f, 1.3090f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        35f, -0.0524f, -1.0472f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        36f, 1.5359f, 0.0000f, -0.0698f, 0.0000f, 0.0000f, 0.0000f,
        37f, 0.0698f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        38f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        39f, 0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        40f, 0.4538f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        41f, 1.0472f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        26f, 0.3176f, -0.7365f, 0.0524f, 0.0000f, 0.0000f, 0.0000f,
        30f, 0.3176f, 0.7365f, -0.0524f, 0.0000f, 0.0000f, 0.0000f,
        27f, 0.5027f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        28f, -0.2234f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        29f, -0.8762f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        31f, 0.5027f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        32f, -0.2234f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        33f, -0.8762f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
    )
    val STING_HIT = floatArrayOf(
        1f, -0.1396f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        2f, 0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        3f, 0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        4f, 0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        5f, 0.2793f, -0.2094f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        6f, 0.1745f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        20f, -0.8727f, 0.0000f, 0.6981f, 0.0000f, 0.0000f, 0.0000f,
        21f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        22f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        23f, -0.5236f, 0.0000f, -0.8727f, 0.0000f, 0.0000f, 0.0000f,
        24f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        25f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        34f, 0.0000f, 1.2217f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        35f, -0.0524f, -1.2217f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        36f, 1.9548f, 0.0000f, 0.0349f, 0.0000f, 0.0000f, 0.0000f,
        37f, 0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        38f, 0.7679f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        39f, 0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        40f, 0.8029f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        41f, 0.5934f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        26f, 0.3176f, -0.7365f, 0.0524f, 0.0000f, 0.0000f, 0.0000f,
        30f, 0.3176f, 0.7365f, -0.0524f, 0.0000f, 0.0000f, 0.0000f,
        27f, 0.5027f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        28f, -0.2234f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        29f, -0.8762f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        31f, 0.5027f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        32f, -0.2234f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        33f, -0.8762f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
    )
    val STING_STUCK = floatArrayOf(
        1f, -0.2094f, 0.0000f, -0.1047f, 0.0000f, 0.0000f, 0.0000f,
        2f, 0.4538f, 0.0000f, 0.1396f, 0.0000f, 0.0000f, 0.0000f,
        3f, 0.0000f, 0.2443f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        4f, -0.0698f, 0.3142f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        5f, -0.1047f, 0.4189f, -0.1396f, 0.0000f, 0.0000f, 0.0000f,
        6f, -0.2443f, -0.3491f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        20f, -1.3963f, 0.0000f, 0.3491f, 0.0000f, 0.0000f, 0.0000f,
        21f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        22f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        23f, -1.3963f, 0.0000f, -0.3491f, 0.0000f, 0.0000f, 0.0000f,
        24f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        25f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        34f, 0.0000f, 1.2217f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        35f, -0.0524f, -1.2217f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        36f, 1.9548f, 0.0000f, 0.0349f, 0.0000f, 0.0000f, 0.0000f,
        37f, 0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        38f, 0.7679f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        39f, 0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        40f, 0.8029f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        41f, 0.5934f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        26f, 0.3176f, -0.7365f, 0.0524f, 0.0000f, 0.0000f, 0.0000f,
        30f, 0.3176f, 0.7365f, -0.0524f, 0.0000f, 0.0000f, 0.0000f,
        27f, 0.5027f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        28f, -0.2234f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        29f, -0.8762f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        31f, 0.5027f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        32f, -0.2234f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        33f, -0.8762f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        7f, 0.4189f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
    )
    val COIL = floatArrayOf(
        1f, -0.0698f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        2f, 0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        3f, -0.0349f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        4f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        5f, 0.0698f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        6f, -0.2443f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        20f, -1.9199f, 0.0000f, 0.3491f, 0.0000f, 0.0000f, 0.0000f,
        21f, -0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        23f, -2.0944f, 0.0000f, -0.3491f, 0.0000f, 0.0000f, 0.0000f,
        24f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        34f, 0.1396f, 1.5708f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        35f, 0.3142f, 1.0821f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        36f, 0.3142f, 1.0821f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        37f, 0.3142f, 1.0821f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        38f, 0.3142f, 1.0821f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        39f, 0.3142f, 1.0123f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        40f, 0.3491f, 0.8727f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        41f, 0.6981f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
    )
    val DIVE_WINDUP = floatArrayOf(
        1f, -0.2094f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        2f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        3f, -0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        4f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        5f, -0.4189f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        6f, -0.2793f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        7f, 0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        20f, -2.7925f, 0.0000f, 0.5236f, 0.0000f, 0.0000f, 0.0000f,
        21f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        22f, -0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        23f, -2.7925f, 0.0000f, -0.5236f, 0.0000f, 0.0000f, 0.0000f,
        24f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        25f, -0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        34f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        35f, -0.0524f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        36f, 0.4538f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        37f, 0.2793f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        38f, 0.2443f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        39f, 0.2443f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        40f, 0.2793f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        41f, 0.4189f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        26f, 0.0000f, -1.3963f, 0.5236f, 0.0000f, 0.0000f, 0.0000f,
        30f, 0.0000f, 1.3963f, -0.5236f, 0.0000f, 0.0000f, 0.0000f,
        27f, 0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        28f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        29f, -0.8029f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        31f, 0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        32f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        33f, -0.8029f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        8f, 0.8727f, -1.1345f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        9f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        10f, 0.9425f, -0.6807f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        11f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        12f, 1.0123f, -0.2269f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        13f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        14f, 1.0123f, 0.2269f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        15f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        16f, 0.9425f, 0.6807f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        17f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        18f, 0.8727f, 1.1345f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        19f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
    )
    val DIVE = floatArrayOf(
        1f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        2f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        3f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        4f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        5f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        6f, -1.0472f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        7f, 0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        20f, -2.9671f, 0.0000f, 0.1396f, 0.0000f, 0.0000f, 0.0000f,
        21f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        22f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        23f, -2.9671f, 0.0000f, -0.1396f, 0.0000f, 0.0000f, 0.0000f,
        24f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        25f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        34f, -1.5708f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        35f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        36f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        37f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        38f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        39f, 0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        40f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        41f, 0.2443f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        26f, 0.1815f, -1.0193f, -0.1047f, 0.0000f, 0.0000f, 0.0000f,
        30f, 0.1815f, 1.0193f, 0.1047f, 0.0000f, 0.0000f, 0.0000f,
        27f, 0.5864f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        28f, -0.2025f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        29f, -0.8447f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        31f, 0.5864f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        32f, -0.2025f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        33f, -0.8447f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        8f, -0.5236f, -0.3491f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        9f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        10f, -0.4538f, -0.2094f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        11f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        12f, -0.3840f, -0.0698f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        13f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        14f, -0.3840f, 0.0698f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        15f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        16f, -0.4538f, 0.2094f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        17f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        18f, -0.5236f, 0.3491f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        19f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        0f, 1.9548f, 0.0000f, 0.0000f, 0.0000f, -72.0000f, 59.0000f,
    )
    val DAZED = floatArrayOf(
        1f, 1.1170f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        2f, 0.3840f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        3f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        4f, -0.3491f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        5f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        6f, 0.6981f, 0.0000f, 0.1745f, 0.0000f, 0.0000f, 0.0000f,
        7f, 0.2793f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        20f, -0.8727f, 0.0000f, 0.3491f, 0.0000f, 0.0000f, 0.0000f,
        21f, -1.0472f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        22f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        23f, -0.8727f, 0.0000f, -0.3491f, 0.0000f, 0.0000f, 0.0000f,
        24f, -1.0472f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        25f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        34f, 0.0000f, 0.3491f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        35f, -0.0524f, -0.2793f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        36f, -0.0698f, -0.4189f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        37f, 0.0000f, -0.4538f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        38f, 0.0000f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        39f, 0.0000f, -0.5934f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        40f, 0.0000f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        41f, -0.1047f, -0.3491f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        8f, -0.6981f, -0.4363f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        9f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        10f, -0.6283f, -0.2618f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        11f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        12f, -0.5585f, -0.0873f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        13f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        14f, -0.5585f, 0.0873f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        15f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        16f, -0.6283f, 0.2618f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        17f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        18f, -0.6981f, 0.4363f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        19f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
    )
    val ROAR = floatArrayOf(
        1f, -0.1396f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        2f, 0.2793f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        3f, -0.1396f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        4f, -0.1396f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        5f, -0.2793f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        6f, -0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        7f, 0.8727f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        20f, -0.6981f, 0.0000f, 1.5708f, 0.0000f, 0.0000f, 0.0000f,
        21f, -0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        22f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        23f, -0.6981f, 0.0000f, -1.5708f, 0.0000f, 0.0000f, 0.0000f,
        24f, -0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        25f, -0.5236f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        36f, 1.3265f, 0.0000f, -0.1396f, 0.0000f, 0.0000f, 0.0000f,
        38f, 0.1396f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        39f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        40f, 0.2094f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        41f, 0.2793f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        26f, 0.0000f, -1.3963f, 0.3491f, 0.0000f, 0.0000f, 0.0000f,
        30f, 0.0000f, 1.3963f, -0.3491f, 0.0000f, 0.0000f, 0.0000f,
        27f, 0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        28f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        29f, -0.8029f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        31f, 0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        32f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        33f, -0.8029f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        8f, 2.1817f, 0.0000f, 1.3090f, 0.0000f, 0.0000f, 0.0000f,
        9f, -0.4189f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        10f, 2.2864f, 0.0000f, 0.7854f, 0.0000f, 0.0000f, 0.0000f,
        11f, -0.4189f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        12f, 2.3911f, 0.0000f, 0.2618f, 0.0000f, 0.0000f, 0.0000f,
        13f, -0.4189f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        14f, 2.3911f, 0.0000f, -0.2618f, 0.0000f, 0.0000f, 0.0000f,
        15f, -0.4189f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        16f, 2.2864f, 0.0000f, -0.7854f, 0.0000f, 0.0000f, 0.0000f,
        17f, -0.4189f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        18f, 2.1817f, 0.0000f, -1.3090f, 0.0000f, 0.0000f, 0.0000f,
        19f, -0.4189f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
    )
    val DEATH_REAR = floatArrayOf(
        1f, -0.2443f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        2f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        3f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        4f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        5f, -0.4887f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        6f, -0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        7f, 0.8727f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        20f, -2.6180f, 0.0000f, 0.6981f, 0.0000f, 0.0000f, 0.0000f,
        23f, -2.6180f, 0.0000f, -0.6981f, 0.0000f, 0.0000f, 0.0000f,
        21f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        24f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        36f, 1.5708f, 0.3491f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        37f, 0.0698f, 0.0000f, 0.0698f, 0.0000f, 0.0000f, 0.0000f,
        38f, -0.0698f, -0.1047f, 0.0698f, 0.0000f, 0.0000f, 0.0000f,
        39f, -0.0698f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        40f, -0.0698f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        41f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        26f, 0.0000f, -1.3963f, 0.6981f, 0.0000f, 0.0000f, 0.0000f,
        30f, 0.0000f, 1.3963f, -0.6981f, 0.0000f, 0.0000f, 0.0000f,
        27f, 0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        28f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        29f, -0.8029f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        31f, 0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        32f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        33f, -0.8029f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        8f, 1.7453f, -1.1345f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        9f, -0.3491f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        10f, 1.8151f, -0.6807f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        11f, -0.3491f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        12f, 1.8850f, -0.2269f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        13f, -0.3491f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        14f, 1.8850f, 0.2269f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        15f, -0.3491f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        16f, 1.8151f, 0.6807f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        17f, -0.3491f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        18f, 1.7453f, 1.1345f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        19f, -0.3491f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
    )
    val DEATH = floatArrayOf(
        1f, -1.2217f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        2f, -0.2443f, 0.0000f, -0.1745f, 0.0000f, 0.0000f, 0.0000f,
        3f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        4f, -0.1047f, 0.0000f, 0.1745f, 0.0000f, 0.0000f, 0.0000f,
        5f, 0.1745f, 0.0000f, 0.1047f, 0.0000f, 0.0000f, 0.0000f,
        6f, 0.5236f, 0.3491f, 0.3491f, 0.0000f, 0.0000f, 0.0000f,
        7f, 0.3491f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        20f, 0.1745f, 0.0000f, 0.8727f, 0.0000f, 0.0000f, 0.0000f,
        23f, 0.0000f, 0.0000f, -1.0472f, 0.0000f, 0.0000f, 0.0000f,
        21f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        24f, -0.3491f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        36f, -0.0349f, -0.3491f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        37f, 0.0000f, -0.2443f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        38f, 0.0000f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        39f, 0.0000f, -0.2793f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        40f, 0.0000f, -0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        41f, -0.1047f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        26f, 0.2723f, -0.8308f, -0.2094f, 0.0000f, 0.0000f, 0.0000f,
        30f, 0.2723f, 0.8308f, 0.2094f, 0.0000f, 0.0000f, 0.0000f,
        27f, 0.5306f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        28f, -0.2164f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        29f, -0.8657f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        31f, 0.5306f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        32f, -0.2164f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        33f, -0.8657f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        8f, -0.3491f, -1.3090f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        9f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        10f, -0.2793f, -0.7854f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        11f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        12f, -0.2094f, -0.2618f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        13f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        14f, -0.2094f, 0.2618f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        15f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        16f, -0.2793f, 0.7854f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        17f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        18f, -0.3491f, 1.3090f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        19f, 0.1745f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        34f, 0.0000f, 0.3840f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        35f, -0.0524f, -0.4189f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
    )
}
// </kampe-poses>
