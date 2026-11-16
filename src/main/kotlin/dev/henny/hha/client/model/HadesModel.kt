package dev.henny.hha.client.model

import dev.henny.hha.Hha
import dev.henny.hha.client.vfx.BossGlow
import dev.henny.hha.entity.boss.HadesEntity
import dev.henny.hha.entity.boss.HadesPose
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
import net.minecraft.client.render.state.CameraRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.RotationAxis
import net.minecraft.util.math.Vec3d
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import dev.henny.hha.client.model.HadesKeys as K

class HadesRenderState : LivingEntityRenderState() {
    var entityId = 0
    var bossPose = HadesPose.IDLE
    var previousPose = HadesPose.IDLE
    var poseTime = 0f
    var cloaked = false
    var stage = 1
    var form = 0
    var bidentOut = false

    /** Konter gegen Spieler über ihm (Überarbeitung 2): [HadesPose.SLAM] läuft rückwärts, der Bident fährt nach oben. */
    var antiAir = false
    var walk = 0f
    var walkPhase = 0f
    var lookYaw = 0f
    var lookPitch = 0f
}

/**
 * Hades, König der Toten. Geometrie, Textur und alle Schlüsselposen kommen aus `tools/hha_assets/hades_model.py`;
 * die Posen stehen als Kanalwerte im generierten Objekt [HadesKeys] am Ende dieser Datei (dieselben Zahlen wie
 * `POSES` in Python). Hier läuft jede Pose über ihre eigene Zeitleiste (Zeiten aus [BossTimings.Hades]):
 * Ausholen weich hinein und halten, Schlag hart, Nachlauf, Erholung gehalten. Darüber liegen Atem, Gang, Blick und
 * die Sekundärbewegung von Mantel, Schwingen, Rock, Bart und Helmbusch.
 */
class HadesModel(root: ModelPart) : EntityModel<HadesRenderState>(root) {

    private val body = root.getChild("root")
    private val torso = body.getChild("torso")
    private val chest = torso.getChild("chest")
    private val head = chest.getChild("head")
    private val visor = head.getChild("visor")
    private val beardA = head.getChild("beard_a")
    private val beardB = beardA.getChild("beard_b")
    private val beardC = beardB.getChild("beard_c")
    private val crest: List<ModelPart> = buildList {
        var part = head.getChild("crest")
        add(part)
        for (i in 1 until 6) {
            part = part.getChild("crest_$i")
            add(part)
        }
    }
    private val lantern = chest.getChild("lantern")
    private val armR = chest.getChild("arm_r")
    private val foreR = armR.getChild("fore_r")
    private val handR = foreR.getChild("hand_r")
    private val fingersR = handR.getChild("fingers_r")
    private val fingersRTip = fingersR.getChild("fingers_r_tip")
    private val thumbR = handR.getChild("thumb_r")
    private val bident = foreR.getChild("bident")
    private val prongA = bident.getChild("prong_a").getChild("prong_a_1").getChild("prong_a_2")
    private val prongB = bident.getChild("prong_b").getChild("prong_b_1").getChild("prong_b_2")
    private val armL = chest.getChild("arm_l")
    private val foreL = armL.getChild("fore_l")
    private val handL = foreL.getChild("hand_l")
    private val fingersL = handL.getChild("fingers_l")
    private val fingersLTip = fingersL.getChild("fingers_l_tip")
    private val thumbL = handL.getChild("thumb_l")
    private val thighR = body.getChild("thigh_r")
    private val shinR = thighR.getChild("shin_r")
    private val footR = shinR.getChild("foot_r")
    private val thighL = body.getChild("thigh_l")
    private val shinL = thighL.getChild("shin_l")
    private val footL = shinL.getChild("foot_l")

    /** Mantel: je Bahn die Glieder von oben nach unten. */
    private val cape: List<List<ModelPart>> = List(K.CAPE_STRIPS) { strip ->
        val chain = ArrayList<ModelPart>()
        var part = chest.getChild("cape_${strip}_0")
        chain.add(part)
        for (link in 1 until K.CAPE_LINKS) {
            part = part.getChild("cape_${strip}_$link")
            chain.add(part)
        }
        chain
    }

    private class Wing(val parts: List<ModelPart>, val sign: Float, val index: Int)

    private val wings: List<Wing> = buildList {
        for ((side, sign) in listOf("r" to -1f, "l" to 1f)) {
            for (i in 0 until K.WING_COUNT) {
                val first = chest.getChild("wing_${side}_$i")
                val second = first.getChild("wing_${side}_${i}_1")
                add(Wing(listOf(first, second, second.getChild("wing_${side}_${i}_2")), sign, i))
            }
        }
    }

    private class Panel(val upper: ModelPart, val lower: ModelPart, val theta: Float, val index: Int)

    private val skirt: List<Panel> = (1 until K.SKIRT_COUNT).map { i ->
        val upper = body.getChild("skirt_$i")
        Panel(upper, upper.getChild("skirt_${i}_low"), i * MathHelper.TAU / K.SKIRT_COUNT, i)
    }

    /** Je Entity: welche Pose zuletzt lief und wie er zuletzt stand, damit jeder Wechsel von dort aus weich beginnt. */
    private class Memory {
        var pose = -1
        var previous = HadesPose.IDLE
        val from = K.IDLE.copyOf()
        val last = K.IDLE.copyOf()
        var seen = 0f
    }

    private val memory = HashMap<Int, Memory>()
    private val ch = FloatArray(K.COUNT)

    // --- Zeitleisten ----------------------------------------------------------------------------------------

    private class Track(private val t: Float, from: FloatArray, private val out: FloatArray) {
        private var prev = from
        private var start = 0f
        private var done = false

        fun to(end: Float, key: FloatArray, ease: Int = SOFT): Track {
            if (done) return this
            if (t < end) {
                val x = ((t - start) / max(end - start, 0.001f)).coerceIn(0f, 1f)
                val k = when (ease) {
                    HARD -> 1f - (1f - x) * (1f - x) * (1f - x)
                    IN -> x * x
                    else -> x * x * (3f - 2f * x)
                }
                for (i in out.indices) out[i] = prev[i] + (key[i] - prev[i]) * k
                done = true
            } else {
                prev = key
                start = end
            }
            return this
        }

        fun hold(end: Float): Track {
            if (done) return this
            if (t < end) {
                prev.copyInto(out)
                done = true
            } else {
                start = end
            }
            return this
        }

        fun end() {
            if (!done) prev.copyInto(out)
            done = true
        }
    }

    private fun mix(a: FloatArray, b: FloatArray, k: Float) {
        for (i in ch.indices) ch[i] = a[i] + (b[i] - a[i]) * k
    }

    /** Schreibt die Kanäle der laufenden Pose nach [ch]. */
    private fun timeline(state: HadesRenderState, mem: Memory) {
        val t = state.poseTime
        val from = mem.from
        val soft = BossTimings.Anim.BLEND_SOFT.toFloat()
        when (state.bossPose) {
            HadesPose.SWEEP -> Track(t, from, ch).to(7f, K.SWEEP_A_WINDUP).hold(H.SWEEP_A_WINDUP.toFloat())
                .to((H.SWEEP_A_WINDUP + H.SWEEP_STRIKE).toFloat(), K.SWEEP_A_HIT, HARD)
                .to(H.SWEEP_A_END.toFloat(), K.SWEEP_A_FOLLOW).end()
            HadesPose.SWEEP_B -> Track(t, from, ch).to(6f, K.SWEEP_B_WINDUP).hold(H.SWEEP_B_WINDUP.toFloat())
                .to((H.SWEEP_B_WINDUP + H.SWEEP_STRIKE).toFloat(), K.SWEEP_B_HIT, HARD)
                .to(H.SWEEP_B_END.toFloat(), K.SWEEP_B_FOLLOW).end()
            HadesPose.SWEEP_C -> Track(t, from, ch).to(8f, K.SWEEP_C_WINDUP).hold(H.SWEEP_C_WINDUP.toFloat())
                .to((H.SWEEP_C_WINDUP + H.SWEEP_STRIKE).toFloat(), K.SWEEP_C_HIT, HARD)
                .to((H.SWEEP_C_WINDUP + H.SWEEP_STRIKE + 8).toFloat(), K.SWEEP_C_RECOVER).end()
            HadesPose.THRUST -> {
                Track(t, from, ch).to(8f, K.THRUST_WINDUP).hold(H.THRUST_WINDUP.toFloat())
                    .to((H.THRUST_WINDUP + 2).toFloat(), K.THRUST, HARD).end()
                // Solange er gleitet, wippt der gestreckte Stoß leicht.
                if (t > H.THRUST_WINDUP + 2) ch[K.ROOT_Y] += sin(t * 0.9f) * 0.6f
            }
            HadesPose.SLAM -> if (state.antiAir) {
                // Konter (PLAN 4.3): der Bodenschlag rückwärts. Er duckt sich in die Schlaghaltung (SLAM_HIT) und reißt den
                // Bident beim Treffer hart senkrecht nach oben in die Ausholhaltung (SLAM_WINDUP); der Blick geht nach oben.
                val raise = BossTimings.Guardian.ANTI_AIR_RAISE.toFloat()
                val hit = BossTimings.Guardian.ANTI_AIR_HIT.toFloat()
                Track(t, from, ch).to(raise, K.SLAM_HIT).hold(hit - 2f).to(hit, K.SLAM_WINDUP, HARD).end()
                val up = (t / raise).coerceIn(0f, 1f) * (1f - ((t - BossTimings.Guardian.ANTI_AIR_END + 6f) / 6f).coerceIn(0f, 1f))
                ch[K.HEAD_RX] -= 0.4f * up
                ch[K.CHEST_RX] -= 0.12f * up
            } else {
                Track(t, from, ch).to(8f, K.SLAM_WINDUP).hold((H.SLAM_WINDUP - 2).toFloat())
                    .to(H.SLAM_WINDUP.toFloat(), K.SLAM_HIT, IN).end()
            }
            HadesPose.STUCK -> {
                if (t < 6f) {
                    Track(t, from, ch).to(6f, K.STUCK).end()
                } else {
                    // Alle STUCK_TUG Ticks ein Ruck: schnell zurück, langsam wieder nachgeben.
                    val phase = ((t - 6f) % H.STUCK_TUG) / H.STUCK_TUG
                    val tug = if (phase < 0.2f) phase / 0.2f else 1f - (phase - 0.2f) / 0.8f
                    mix(K.STUCK, K.STUCK_TUG, tug * tug * (3f - 2f * tug))
                }
            }
            HadesPose.THROW -> Track(t, from, ch).to(8f, K.THROW_WINDUP).hold((H.THROW_RELEASE - 2).toFloat())
                .to(H.THROW_RELEASE.toFloat(), K.THROW_RELEASE, HARD)
                .to((H.THROW_RELEASE + 6).toFloat(), K.THROW_FOLLOW).end()
            HadesPose.EMPTY -> {
                if (t < soft) {
                    Track(t, from, ch).to(soft, K.EMPTY).end()
                } else {
                    // Die Finger krümmen sich langsam: Er ruft den Bident zurück.
                    mix(K.EMPTY, K.EMPTY_CURL, 0.5f - 0.5f * cos((t - soft) * 0.24f))
                }
            }
            HadesPose.GAZE -> Track(t, from, ch).to(16f, K.GAZE_REACH).to((H.GAZE_WINDUP - 10).toFloat(), K.GAZE_CHARGE)
                .hold((H.GAZE_WINDUP - 1).toFloat()).to((H.GAZE_WINDUP + 1).toFloat(), K.GAZE_FIRE, HARD)
                .hold((H.GAZE_WINDUP + 8).toFloat()).to(H.GAZE_END.toFloat(), K.IDLE).end()
            HadesPose.CHAINS -> Track(t, from, ch).to((H.CHAINS_CAST - 4).toFloat(), K.CHAINS_CAST, HARD).hold(H.CHAINS_CAST.toFloat())
                .to((H.CHAINS_CAST + 6).toFloat(), K.CHAINS_PULL).end()
            HadesPose.VANISH -> Track(t, from, ch).to(H.VANISH_ANIM.toFloat(), K.VANISH).end()
            HadesPose.AMBUSH -> Track(t, from, ch).to((H.AMBUSH_HIT - 2).toFloat(), K.SWEEP_A_WINDUP, HARD)
                .to(H.AMBUSH_HIT.toFloat(), K.AMBUSH_HIT, HARD).to((H.AMBUSH_HIT + 8).toFloat(), K.AMBUSH_RECOVER).end()
            HadesPose.JUDGE -> Track(t, from, ch).to(H.JUDGE_RAISE.toFloat(), K.JUDGE_RAISE).hold((H.JUDGE_HIT - 2).toFloat())
                .to(H.JUDGE_HIT.toFloat(), K.JUDGE_SLAM, IN).to((H.JUDGE_HIT + 10).toFloat(), K.JUDGE_SPENT).end()
            HadesPose.STAGGER -> Track(t, from, ch).to(6f, if (state.bidentOut) K.STAGGER_EMPTY else K.STAGGER).end()
            HadesPose.ROAR -> Track(t, from, ch).to(soft, K.ROAR).end()
            HadesPose.CAST -> Track(t, from, ch).to(soft, K.CAST).end()
            HadesPose.WINDUP -> Track(t, from, ch).to(soft, K.WINDUP).end()
            HadesPose.SIT -> Track(t, from, ch).to(soft, K.SIT).end()
            HadesPose.KNEEL -> Track(t, from, ch).to(soft, K.KNEEL).end()
            HadesPose.FALLEN -> Track(t, from, ch).to(12f, K.FALLEN).end()
            else -> {
                if (mem.previous == HadesPose.THRUST) {
                    // Nach dem Stoß: einen Moment vornübergebeugt und offen, dann zurück in die Ruhe.
                    Track(t, from, ch).to(5f, K.THRUST_RECOVER).hold(H.THRUST_RECOVER.toFloat())
                        .to((H.THRUST_RECOVER + 8).toFloat(), K.IDLE).end()
                } else {
                    Track(t, from, ch).to(soft, K.IDLE).end()
                }
            }
        }
        overlays(state)
    }

    /** Atem, Zittern in gehaltenen Posen, schwerer Atem in den offenen Fenstern. */
    private fun overlays(state: HadesRenderState) {
        val t = state.poseTime
        val age = state.age
        val pose = state.bossPose
        val breath = sin(age * 0.07f)
        ch[K.CHEST_RX] += breath * 0.018f
        ch[K.HEAD_RX] -= breath * 0.008f
        ch[K.ARM_L_RZ] += breath * 0.01f
        val tremble = when (pose) {
            HadesPose.SWEEP -> window(t, 7f, H.SWEEP_A_WINDUP.toFloat())
            HadesPose.SWEEP_C -> window(t, 8f, H.SWEEP_C_WINDUP.toFloat())
            HadesPose.THRUST -> window(t, 8f, H.THRUST_WINDUP.toFloat())
            HadesPose.SLAM -> window(t, 8f, H.SLAM_WINDUP - 2f)
            HadesPose.GAZE -> window(t, 30f, H.GAZE_WINDUP - 1f)
            HadesPose.JUDGE -> window(t, 40f, H.JUDGE_HIT - 2f)
            HadesPose.CHAINS -> window(t, H.CHAINS_CAST + 6f, H.CHAINS_PULL_END.toFloat())
            HadesPose.ROAR -> 0.7f
            else -> 0f
        }
        if (tremble > 0f) {
            val shake = sin(age * 1.9f) * 0.012f * tremble
            ch[K.CHEST_RX] += shake
            ch[K.ARM_R_RX] += shake * 1.5f
            ch[K.ARM_L_RX] -= shake * 1.5f
            ch[K.HEAD_RY] += sin(age * 1.3f) * 0.01f * tremble
        }
        val panting = when (pose) {
            HadesPose.SWEEP_C -> if (t > H.SWEEP_C_WINDUP + 4) 1f else 0f
            HadesPose.JUDGE -> if (t > H.JUDGE_HIT + 4) 1f else 0f
            HadesPose.STAGGER, HadesPose.STUCK, HadesPose.KNEEL -> 1f
            HadesPose.AMBUSH -> if (t > H.AMBUSH_HIT + 8) 0.7f else 0f
            else -> 0f
        }
        if (panting > 0f) {
            val heave = sin(age * 0.32f) * panting
            ch[K.CHEST_RX] += heave * 0.035f
            ch[K.HEAD_RX] += heave * 0.03f
            ch[K.ROOT_Y] += heave * 0.4f
        }
        if (state.cloaked) ch[K.VISOR_DY] = max(ch[K.VISOR_DY], 4f)
    }

    private fun window(t: Float, from: Float, to: Float) = if (t in from..to) 1f else 0f

    override fun setAngles(state: HadesRenderState) {
        super.setAngles(state)
        val mem = memory.getOrPut(state.entityId) { Memory() }
        if (memory.size > 24) memory.entries.removeIf { it.value !== mem && state.age - it.value.seen > 200f }
        mem.seen = state.age
        if (mem.pose != state.bossPose) {
            mem.previous = if (mem.pose < 0) state.previousPose else mem.pose
            mem.pose = state.bossPose
            mem.last.copyInto(mem.from)
        }
        timeline(state, mem)
        ch.copyInto(mem.last)
        walkAndLook(state)
        applyChannels()
        secondary(state)
        bident.visible = !bidentHidden(state)
    }

    private fun bidentHidden(state: HadesRenderState) = state.bidentOut || state.bossPose == HadesPose.EMPTY ||
        (state.bossPose == HadesPose.THROW && state.poseTime >= H.THROW_RELEASE)

    /** Gang aus walk/walkPhase: langsam und gerade, mit Gewicht; die Linke bleibt hinter dem Rücken. */
    private fun walkAndLook(state: HadesRenderState) {
        val walkWeight = when (state.bossPose) {
            HadesPose.IDLE -> 1f
            HadesPose.EMPTY -> 0.7f
            HadesPose.CAST, HadesPose.WINDUP, HadesPose.ROAR -> 0.5f
            else -> 0f
        }
        val walk = state.walk * walkWeight
        if (walk > 0.01f) {
            val p = state.walkPhase
            val stride = sin(p) * walk
            val liftR = max(0f, sin(p + 1.2f)) * walk
            val liftL = max(0f, -sin(p + 1.2f)) * walk
            ch[K.THIGH_R_RX] += stride * 0.42f
            ch[K.THIGH_L_RX] -= stride * 0.42f
            ch[K.SHIN_R_RX] += liftR * 0.7f
            ch[K.SHIN_L_RX] += liftL * 0.7f
            ch[K.FOOT_R_RX] -= liftR * 0.25f
            ch[K.FOOT_L_RX] -= liftL * 0.25f
            ch[K.ROOT_Y] += abs(cos(p)) * walk * 1.4f - walk * 0.6f
            ch[K.ROOT_RZ] += stride * 0.03f
            ch[K.CHEST_RY] -= stride * 0.07f
            ch[K.TORSO_RX] += walk * 0.05f
            ch[K.ARM_R_RX] -= stride * 0.12f
            ch[K.BID_RX] += stride * 0.1f
            ch[K.CAPE] += walk * 0.25f
        }
        val lookWeight = when (state.bossPose) {
            HadesPose.IDLE, HadesPose.EMPTY, HadesPose.CAST, HadesPose.WINDUP, HadesPose.STUCK, HadesPose.CHAINS -> 1f
            HadesPose.SWEEP, HadesPose.SWEEP_B, HadesPose.SWEEP_C, HadesPose.THRUST, HadesPose.THROW -> if (state.poseTime < 8f) 0.6f else 0.3f
            HadesPose.STAGGER -> 0.4f
            else -> 0f
        }
        ch[K.HEAD_RY] += state.lookYaw * lookWeight
        ch[K.HEAD_RX] += state.lookPitch * lookWeight
    }

    private fun applyChannels() {
        body.originX += ch[K.ROOT_X]
        body.originY += ch[K.ROOT_Y]
        body.originZ += ch[K.ROOT_Z]
        set(body, K.ROOT_RX, K.ROOT_RY, K.ROOT_RZ)
        set(torso, K.TORSO_RX, K.TORSO_RY, K.TORSO_RZ)
        set(chest, K.CHEST_RX, K.CHEST_RY, K.CHEST_RZ)
        set(head, K.HEAD_RX, K.HEAD_RY, K.HEAD_RZ)
        visor.pitch = ch[K.VISOR_RX]
        visor.originY += ch[K.VISOR_DY]
        set(armR, K.ARM_R_RX, K.ARM_R_RY, K.ARM_R_RZ)
        foreR.pitch = ch[K.FORE_R_RX]
        foreR.yaw = ch[K.FORE_R_RY]
        handR.pitch = ch[K.HAND_R_RX]
        handR.roll = ch[K.HAND_R_RZ]
        set(bident, K.BID_RX, K.BID_RY, K.BID_RZ)
        set(armL, K.ARM_L_RX, K.ARM_L_RY, K.ARM_L_RZ)
        foreL.pitch = ch[K.FORE_L_RX]
        foreL.yaw = ch[K.FORE_L_RY]
        handL.pitch = ch[K.HAND_L_RX]
        handL.roll = ch[K.HAND_L_RZ]
        set(thighR, K.THIGH_R_RX, K.THIGH_R_RY, K.THIGH_R_RZ)
        shinR.pitch = ch[K.SHIN_R_RX]
        footR.pitch = ch[K.FOOT_R_RX]
        set(thighL, K.THIGH_L_RX, K.THIGH_L_RY, K.THIGH_L_RZ)
        shinL.pitch = ch[K.SHIN_L_RX]
        footL.pitch = ch[K.FOOT_L_RX]
    }

    private fun set(part: ModelPart, rx: Int, ry: Int, rz: Int) {
        part.pitch = ch[rx]
        part.yaw = ch[ry]
        part.roll = ch[rz]
    }

    /** Dieselben Formeln wie `secondary()` in hades_model.py, dazu die Zeit-Terme (Wellen, Nachschwingen). */
    private fun secondary(state: HadesRenderState) {
        val t = state.age
        val walk = state.walk
        val g = ch[K.ROOT_RX] + ch[K.TORSO_RX] + ch[K.CHEST_RX]
        val gc = g.coerceIn(-0.6f, 1f)
        val lift = ch[K.CAPE]
        val wrap = ch[K.CAPE_WRAP]
        // Mantel: hängt nach, hebt sich, die Welle läuft vom Kragen zum Saum und wird pro Glied stärker.
        for ((strip, chain) in cape.withIndex()) {
            val across = strip - (K.CAPE_STRIPS - 1) / 2f
            for ((link, part) in chain.withIndex()) {
                val wave = sin(t * 0.13f - link * 0.8f + strip * 0.9f) * (0.015f + link * 0.014f) * (1f + walk)
                if (link == 0) {
                    part.pitch = 0.06f + lift * 0.6f - gc * 0.85f - abs(across) * wrap * 0.12f + wave
                    part.yaw = across * wrap * 0.55f
                    part.roll = -across * (0.085f - wrap * 0.05f) + sin(t * 0.09f + strip * 1.7f) * 0.012f
                } else {
                    part.pitch = 0.03f - lift * 0.13f + walk * (0.05f - link * 0.015f) + wave
                    part.roll = sin(t * 0.1f + strip * 1.3f + link) * 0.01f * link
                }
            }
        }
        // Schwingen aus Leichentuch: nach Form sichtbar, gefächert, die Spitzen peitschen nach.
        val form = state.form.coerceIn(0, 2)
        val base = K.WING_FORM_BASE[form]
        val w = ch[K.WINGS]
        val sp = base * (1f + min(0f, w)) + max(0f, w) * (1f - base)
        for (wing in wings) {
            val visible = form >= 2 || (form == 1 && wing.index in 1..3)
            wing.parts[0].visible = visible
            if (!visible) continue
            val i = wing.index
            val roll = K.WING_FOLD[i] + (K.WING_SPREAD[i] - K.WING_FOLD[i]) * sp
            val beat = sin(t * 0.06f + i * 0.5f) * 0.03f * (0.5f + sp)
            wing.parts[0].pitch = 0.22f + 0.12f * sp - g * 0.3f + beat + walk * 0.1f
            wing.parts[0].yaw = -wing.sign * 0.2f * sp
            wing.parts[0].roll = -wing.sign * (roll + beat * 0.5f)
            for (k in 1..2) {
                val whip = sin(t * 0.1f - k * 0.9f + i * 0.7f) * 0.035f * k
                wing.parts[k].pitch = (if (k == 1) 0.06f else 0.08f) + whip + walk * 0.06f * k
                wing.parts[k].roll = wing.sign * (if (k == 1) 0.2f else 0.26f) * sp + wing.sign * whip * 0.5f
            }
        }
        // Rock: vorn hebt er sich mit dem Oberschenkel, hinten schwingt er aus.
        for (panel in skirt) {
            val front = cos(panel.theta)
            val side = -sin(panel.theta)
            val thigh = when {
                side < -0.1f -> ch[K.THIGH_R_RX]
                side > 0.1f -> ch[K.THIGH_L_RX]
                else -> (ch[K.THIGH_R_RX] + ch[K.THIGH_L_RX]) / 2f
            }
            var follow = if (front > 0f) front * min(0f, thigh) else front * max(0f, thigh)
            follow *= 0.75f + 0.25f * abs(front)
            val sway = sin(t * 0.12f + panel.index * 1.4f) * 0.02f
            panel.upper.pitch = -10f * MathHelper.RADIANS_PER_DEGREE + follow + sway
            panel.upper.yaw = panel.theta
            panel.upper.roll = 0f
            panel.lower.pitch = 4f * MathHelper.RADIANS_PER_DEGREE + max(0f, -follow) * 0.35f + sway * 0.8f
        }
        // Bart pendelt der Schwerkraft nach.
        beardA.pitch = -0.38f - (g + ch[K.HEAD_RX]) * 0.7f + sin(t * 0.08f) * 0.035f
        beardB.pitch += sin(t * 0.08f - 0.7f) * 0.045f
        beardC.pitch += sin(t * 0.08f - 1.4f) * 0.055f
        // Helmbusch: das Ende im Nacken schwingt leicht nach.
        crest[4].pitch += sin(t * 0.09f) * 0.02f + walk * 0.03f
        crest[5].pitch += sin(t * 0.09f - 0.8f) * 0.035f + walk * 0.05f
        // Finger: grip 0 = offen, 1 = Faust.
        grip(fingersR, fingersRTip, thumbR, ch[K.GRIP_R], -1f)
        grip(fingersL, fingersLTip, thumbL, ch[K.GRIP_L], 1f)
        // Die Seelenlaterne pulsiert langsam; im Taumeln flackert sie.
        val pulse = if (state.bossPose == HadesPose.STAGGER) {
            0.86f + 0.14f * sin(t * 1.7f) * sin(t * 0.63f + 1f)
        } else {
            1f + 0.05f * sin(t * 0.09f)
        }
        lantern.xScale = pulse
        lantern.yScale = pulse
        lantern.zScale = pulse
    }

    private fun grip(fingers: ModelPart, tips: ModelPart, thumb: ModelPart, g: Float, sign: Float) {
        fingers.pitch = 0f
        fingers.roll = sign * g * 1.45f
        tips.roll = sign * g * 1.4f
        thumb.pitch = -0.3f - g * 0.5f
        thumb.roll = sign * (0.35f + g * 0.5f)
    }

    /** Liest die Weltpositionen für das Leuchten (BossGlow.HadesFrame). Koordinaten aus hades_model.py. */
    fun readAnchors(matrices: MatrixStack, cam: Vec3d, out: BossGlow.HadesFrame) {
        val scratch = Vector3f()
        fun at(path: Array<ModelPart>, x: Float, y: Float, z: Float): Vec3d {
            matrices.push()
            for (part in path) part.applyTransform(matrices)
            matrices.peek().positionMatrix.transformPosition(x / 16f, y / 16f, z / 16f, scratch)
            matrices.pop()
            return Vec3d(scratch.x + cam.x, scratch.y + cam.y, scratch.z + cam.z)
        }
        val chestPath = arrayOf(root, body, torso, chest)
        val headPath = chestPath + head
        val fore = chestPath + armR + foreR
        out.hem = at(chestPath + cape[2], 0f, K.CAPE_LEN_F, 1f)
        out.chest = at(chestPath + lantern, 0f, 0f, -2f)
        out.eyeR = at(headPath, -3.5f, -10.5f, -8f)
        out.eyeL = at(headPath, 3.5f, -10.5f, -8f)
        out.crown = at(headPath + crest[0] + crest[1] + crest[2], 0f, -9f, 3f)
        out.tipA = at(fore + bident + bident.getChild("prong_a") + bident.getChild("prong_a").getChild("prong_a_1") + prongA, 0f, -7f, 0f)
        out.tipB = at(fore + bident + bident.getChild("prong_b") + bident.getChild("prong_b").getChild("prong_b_1") + prongB, 0f, -7f, 0f)
        out.fork = at(fore + bident, 0f, -22.5f, 0f)
        out.handR = at(fore, 0f, K.GRIP_Y, 0f)
        out.handL = at(chestPath + armL + foreL, 0f, K.GRIP_Y, 0f)
    }

    companion object {
        private val H = BossTimings.Hades
        private const val SOFT = 0
        private const val HARD = 1
        private const val IN = 2

        /** Der Bident-Knochen aus der Hades-Modellschicht (für den fliegenden Bident, Plan 6.5). Ursprung am Griff. */
        fun bident(layerRoot: ModelPart): ModelPart =
            layerRoot.getChild("root").getChild("torso").getChild("chest").getChild("arm_r").getChild("fore_r").getChild("bident")
    }
}

class HadesRenderer(context: EntityRendererFactory.Context) :
    LivingEntityRenderer<HadesEntity, HadesRenderState, HadesModel>(context, HadesModel(context.getPart(BossLayers.HADES)), 0.9f) {

    private val texture = Hha.id("textures/entity/boss/hades.png")
    private val glowLayer = RenderLayers.eyes(Hha.id("textures/entity/boss/hades_glow.png"))
    private val ghostLayer = RenderLayers.entityTranslucentEmissive(texture)

    init {
        addFeature(object : FeatureRenderer<HadesRenderState, HadesModel>(this) {
            override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: HadesRenderState, limbAngle: Float, limbDistance: Float) {
                val alpha = (0.8f + 0.2f * sin(state.age * 0.17f)).let { (it * 255f).toInt() }
                queue.getBatchingQueue(1).submitModel(contextModel, state, matrices, glowLayer, 0xF000F0, OverlayTexture.DEFAULT_UV, (alpha shl 24) or 0xFFFFFF, null, 0, null)
                publish(state, matrices)
            }
        })
    }

    private fun publish(state: HadesRenderState, matrices: MatrixStack) {
        val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
        val frame = BossGlow.HadesFrame(state.entityId, state.stage, state.cloaked, state.age, state.form, state.bossPose == HadesPose.FALLEN, state.bidentOut, state.bossPose == HadesPose.STAGGER)
        frame.pose = state.bossPose
        model.readAnchors(matrices, cam, frame)
        BossGlow.submit(frame)
    }

    override fun createRenderState() = HadesRenderState()
    override fun getTexture(state: HadesRenderState): Identifier = texture

    override fun updateRenderState(entity: HadesEntity, state: HadesRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.entityId = entity.id
        state.bossPose = entity.pose
        state.previousPose = entity.previousPose
        state.poseTime = (entity.age - entity.poseChangedAge) + tickDelta
        state.cloaked = entity.cloaked
        state.stage = entity.stage
        state.form = entity.form
        state.bidentOut = entity.bidentOut
        state.antiAir = entity.antiAir
        state.walk = entity.walkAmount
        state.walkPhase = entity.walkPhase
        updateLook(entity, state, tickDelta)
    }

    /** Der Kopf folgt dem eigenen Spieler; wie stark, entscheidet die Pose im Modell. */
    private fun updateLook(entity: HadesEntity, state: HadesRenderState, tickDelta: Float) {
        state.lookYaw = 0f
        state.lookPitch = 0f
        if (entity.pose == HadesPose.FALLEN || entity.pose == HadesPose.SIT) return
        val player = MinecraftClient.getInstance().player ?: return
        val eye = entity.getLerpedPos(tickDelta).add(0.0, entity.height * 0.9, 0.0)
        val target = player.getLerpedPos(tickDelta).add(0.0, player.standingEyeHeight.toDouble(), 0.0)
        val dx = target.x - eye.x
        val dy = target.y - eye.y
        val dz = target.z - eye.z
        val yaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        val relative = MathHelper.wrapDegrees(yaw - entity.getLerpedYaw(tickDelta))
        if (abs(relative) > 110f) return
        state.lookYaw = relative.coerceIn(-55f, 55f) * MathHelper.RADIANS_PER_DEGREE
        val flat = sqrt(dx * dx + dz * dz)
        state.lookPitch = (-Math.toDegrees(atan2(dy, flat)).toFloat()).coerceIn(-30f, 32f) * MathHelper.RADIANS_PER_DEGREE
    }

    override fun scale(state: HadesRenderState, matrices: MatrixStack) {
        val size = SCALE * (1f + state.form * HadesEntity.FORM_GROWTH)
        matrices.scale(size, size, size)
    }

    override fun render(state: HadesRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue, cameraState: CameraRenderState) {
        if (!state.cloaked) {
            super.render(state, matrices, queue, cameraState)
            return
        }
        // Mit dem Helm bleibt nur ein Flimmern in der Luft, und die Augen.
        matrices.push()
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - state.bodyYaw))
        val size = SCALE * (1f + state.form * HadesEntity.FORM_GROWTH)
        matrices.scale(-size, -size, size)
        matrices.translate(0f, -1.501f, 0f)
        val alpha = (0.1f + 0.05f * sin(state.age * 0.9f)).let { (it * 255f).toInt() }
        queue.getBatchingQueue(2).submitModel(model, state, matrices, ghostLayer, 0xF000F0, OverlayTexture.DEFAULT_UV, (alpha shl 24) or 0x9CFFA8, null, 0, null)
        model.setAngles(state)
        publish(state, matrices)
        matrices.pop()
    }

    override fun hasLabel(entity: HadesEntity, squaredDistance: Double): Boolean = false

    private companion object {
        const val SCALE = 0.72f
    }
}

// region HadesKeys (GENERIERT von tools/hha_assets/hades_model.py, nicht von Hand ändern)
/** Kanäle und Schlüsselposen von Hades; dieselben Zahlen stehen in `POSES` der Python-Datei. */
internal object HadesKeys {
    const val ROOT_X = 0
    const val ROOT_Y = 1
    const val ROOT_Z = 2
    const val ROOT_RX = 3
    const val ROOT_RY = 4
    const val ROOT_RZ = 5
    const val TORSO_RX = 6
    const val TORSO_RY = 7
    const val TORSO_RZ = 8
    const val CHEST_RX = 9
    const val CHEST_RY = 10
    const val CHEST_RZ = 11
    const val HEAD_RX = 12
    const val HEAD_RY = 13
    const val HEAD_RZ = 14
    const val VISOR_RX = 15
    const val VISOR_DY = 16
    const val ARM_R_RX = 17
    const val ARM_R_RY = 18
    const val ARM_R_RZ = 19
    const val FORE_R_RX = 20
    const val FORE_R_RY = 21
    const val HAND_R_RX = 22
    const val HAND_R_RZ = 23
    const val GRIP_R = 24
    const val BID_RX = 25
    const val BID_RY = 26
    const val BID_RZ = 27
    const val ARM_L_RX = 28
    const val ARM_L_RY = 29
    const val ARM_L_RZ = 30
    const val FORE_L_RX = 31
    const val FORE_L_RY = 32
    const val HAND_L_RX = 33
    const val HAND_L_RZ = 34
    const val GRIP_L = 35
    const val THIGH_R_RX = 36
    const val THIGH_R_RY = 37
    const val THIGH_R_RZ = 38
    const val SHIN_R_RX = 39
    const val FOOT_R_RX = 40
    const val THIGH_L_RX = 41
    const val THIGH_L_RY = 42
    const val THIGH_L_RZ = 43
    const val SHIN_L_RX = 44
    const val FOOT_L_RX = 45
    const val CAPE = 46
    const val CAPE_WRAP = 47
    const val WINGS = 48
    const val COUNT = 49
    const val CAPE_STRIPS = 5
    const val CAPE_LINKS = 4
    const val WING_COUNT = 5
    const val SKIRT_COUNT = 8
    const val GRIP_Y = 17.5f
    const val CAPE_LEN_F = 16f
    @JvmField val WING_FORM_BASE = floatArrayOf(0.0f, 0.45f, 0.85f)
    @JvmField val WING_FOLD = floatArrayOf(0.1f, 0.16f, 0.22f, 0.28f, 0.34f)
    @JvmField val WING_SPREAD = floatArrayOf(2.25f, 2.02f, 1.8f, 1.58f, 1.36f)
    @JvmField val IDLE = floatArrayOf(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0200f, 0.0000f, 0.0000f, -0.0300f, 0.0000f, 0.0000f, 0.0400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0380f, -0.2110f, 0.1200f, -0.3170f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 0.3870f, 0.2390f, -0.0410f, 0.5750f, -0.1430f, 0.1340f, -0.7210f, 0.6000f, 0.0000f, 0.0000f, 0.7000f, -0.0400f, 0.0000f, 0.0400f, 0.0400f, 0.0000f, 0.0500f, 0.0000f, -0.0400f, 0.0000f, 0.0000f, 0.2200f, 0.0000f, 0.0000f)
    @JvmField val SWEEP_A_WINDUP = floatArrayOf(0.0000f, 3.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.0200f, 0.3500f, 0.0000f, 0.0500f, 0.5500f, -0.0600f, 0.0500f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.3050f, -0.4580f, 0.2500f, -2.0100f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, -1.3020f, 1.3810f, -2.4020f, -0.4690f, 0.9780f, 0.8500f, -0.0080f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.3500f, 0.0000f, 0.1200f, 0.3500f, -0.3000f, -0.6500f, 0.0000f, -0.1000f, 0.4500f, 0.2000f, 0.3000f, 0.0000f, 0.0000f)
    @JvmField val SWEEP_A_HIT = floatArrayOf(0.0000f, 4.0000f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, 0.0200f, -0.3500f, 0.0000f, 0.0500f, -0.6500f, 0.0500f, 0.0500f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.1070f, -0.9900f, 0.9520f, -2.1440f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, -0.5770f, 0.9580f, -2.3530f, -1.4670f, 0.9980f, -0.2740f, -0.0080f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.4000f, 0.0000f, 0.1200f, 0.3000f, -0.3000f, -0.7000f, 0.0000f, -0.1000f, 0.4000f, 0.2000f, 0.7500f, 0.0000f, 0.0000f)
    @JvmField val SWEEP_A_FOLLOW = floatArrayOf(0.0000f, 4.0000f, 0.0000f, 0.0000f, -0.5500f, 0.0000f, 0.0200f, -0.5000f, 0.0000f, 0.0500f, -0.8500f, 0.0500f, 0.0500f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.1400f, -0.9740f, 0.7020f, -2.0100f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, -0.1050f, 1.0980f, -2.5010f, -1.0710f, 0.7640f, 0.0270f, -0.0320f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.4000f, 0.0000f, 0.1200f, 0.3000f, -0.3000f, -0.7000f, 0.0000f, -0.1000f, 0.4000f, 0.2000f, 0.6000f, 0.0000f, 0.0000f)
    @JvmField val SWEEP_B_WINDUP = floatArrayOf(0.0000f, 3.0000f, 0.0000f, 0.0000f, -0.4500f, 0.0000f, 0.0200f, -0.4000f, 0.0000f, -0.0500f, -0.7500f, 0.0500f, 0.0500f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, -0.9300f, -1.0040f, -0.0500f, -2.1150f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, -2.3270f, -0.8560f, -0.2890f, -1.5020f, 0.6410f, 0.6000f, -0.9350f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, -0.5500f, 0.0000f, 0.1200f, 0.4000f, 0.1500f, 0.3000f, 0.0000f, -0.1000f, 0.3500f, -0.2500f, 0.4500f, 0.0000f, 0.0000f)
    @JvmField val SWEEP_B_HIT = floatArrayOf(0.0000f, 4.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.0200f, 0.3500f, 0.0000f, -0.0500f, 0.6000f, 0.0500f, 0.0500f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, -1.6200f, -1.0570f, 0.7080f, -0.3290f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, -2.6700f, -1.0590f, 0.4360f, -1.0980f, -0.0300f, 0.0030f, -0.0750f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, -0.5500f, 0.0000f, 0.1200f, 0.4000f, 0.1500f, 0.3000f, 0.0000f, -0.1000f, 0.3500f, -0.2500f, 0.8000f, 0.0000f, 0.0000f)
    @JvmField val SWEEP_B_FOLLOW = floatArrayOf(0.0000f, 4.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0200f, 0.5000f, 0.0000f, -0.0500f, 0.8000f, 0.0500f, 0.0500f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, -1.2770f, -1.0410f, 0.6180f, -0.4900f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, -2.1540f, -0.8980f, 0.5280f, -0.8840f, 0.2100f, 0.3220f, -0.0130f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, -0.5500f, 0.0000f, 0.1200f, 0.4000f, 0.1500f, 0.3000f, 0.0000f, -0.1000f, 0.3500f, -0.2500f, 0.6000f, 0.0000f, 0.0000f)
    @JvmField val SWEEP_C_WINDUP = floatArrayOf(0.0000f, -2.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, -0.1800f, 0.0000f, 0.0000f, -0.2200f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.5380f, -0.3310f, 0.2000f, -0.0420f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 2.4560f, -0.0660f, 0.3270f, -1.9520f, -0.0480f, -0.5130f, -1.0800f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.1500f, 0.0000f, 0.0400f, 0.0400f, -0.3500f, -0.3500f, 0.0000f, -0.0400f, 0.1500f, 0.2000f, 0.3500f, 0.0000f, 0.0000f)
    @JvmField val SWEEP_C_HIT = floatArrayOf(0.0000f, 8.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, 0.0000f, 0.0000f, 0.4500f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4620f, -1.0760f, -0.0500f, -2.2560f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 3.7890f, -0.9600f, 0.5960f, -1.7450f, 1.0570f, -1.0110f, -2.0660f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.4500f, 0.0000f, 0.0400f, 0.5500f, -0.5000f, -0.9500f, 0.0000f, -0.0400f, 0.6500f, 0.3000f, 0.9000f, 0.0000f, 0.0000f)
    @JvmField val SWEEP_C_RECOVER = floatArrayOf(0.0000f, 11.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.6200f, 0.0000f, 0.0000f, 0.4500f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0920f, -1.6200f, 0.2000f, -2.3000f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 3.3040f, -0.6190f, 1.6310f, -1.2800f, 0.5670f, -1.0060f, -1.9360f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.3500f, 0.0000f, 0.0400f, 0.8500f, -0.4000f, -0.8500f, 0.0000f, -0.0400f, 0.9500f, -0.1000f, 0.0500f, 0.0000f, -0.3000f)
    @JvmField val THRUST_WINDUP = floatArrayOf(0.0000f, 8.0000f, 4.0000f, 0.0000f, 0.3000f, 0.0000f, 0.1000f, 0.1500f, 0.0000f, 0.1000f, 0.3000f, 0.0000f, -0.0500f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 1.1050f, -0.8060f, 0.0500f, -2.3770f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 1.8430f, 1.1070f, -1.3400f, -0.3080f, 0.3590f, -0.1320f, -1.7150f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.5000f, 0.0000f, 0.0400f, 0.5500f, -0.5500f, -0.8000f, 0.0000f, -0.0400f, 0.7000f, 0.1000f, 0.3000f, 0.0000f, 0.0000f)
    @JvmField val THRUST = floatArrayOf(0.0000f, 10.0000f, -3.0000f, 0.0000f, 0.1500f, 0.0000f, 0.3000f, 0.1500f, 0.0000f, 0.2000f, 0.1500f, 0.0000f, -0.1500f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.4670f, -1.1260f, -0.1400f, -2.3890f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 1.5830f, 1.5650f, -1.0900f, -0.7640f, -0.0650f, 0.2220f, -1.6690f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.7500f, 0.0000f, 0.0400f, 0.3000f, -0.5500f, -1.1000f, 0.0000f, -0.0400f, 0.9000f, 0.2000f, 1.0500f, 0.0000f, 0.0000f)
    @JvmField val THRUST_RECOVER = floatArrayOf(0.0000f, 9.0000f, -1.0000f, 0.0000f, 0.1500f, 0.0000f, 0.4500f, 0.1500f, 0.0000f, 0.3000f, 0.1500f, 0.0000f, 0.2000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.6770f, -0.7550f, -0.1280f, -2.2590f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 2.6800f, 1.1310f, -0.1780f, 0.5090f, 0.8120f, -0.2780f, -1.7590f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.7500f, 0.0000f, 0.0400f, 0.3000f, -0.5500f, -1.1000f, 0.0000f, -0.0400f, 0.9000f, 0.2000f, 0.2000f, 0.0000f, 0.0000f)
    @JvmField val SLAM_WINDUP = floatArrayOf(0.0000f, -3.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, -0.2500f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.3820f, -0.1830f, 0.2500f, -0.0080f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, -0.4670f, 0.0400f, 0.3060f, -2.2800f, 0.4000f, -0.5060f, -0.1400f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.1000f, 0.0000f, 0.0400f, 0.0400f, -0.4500f, -0.1500f, 0.0000f, -0.0400f, 0.0000f, -0.4500f, 0.5000f, 0.0000f, 0.0000f)
    @JvmField val SLAM_HIT = floatArrayOf(0.0000f, 10.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5510f, -1.0500f, 0.2410f, -2.2630f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, -1.8800f, -0.8080f, 0.7840f, -0.7950f, 0.4050f, -0.2700f, -1.6600f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.4500f, 0.0000f, 0.0400f, 0.7000f, -0.3000f, -1.0000f, 0.0000f, -0.0400f, 0.8000f, 0.2000f, 1.0000f, 0.0000f, 0.0000f)
    @JvmField val STUCK = floatArrayOf(0.0000f, 8.0000f, 4.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4210f, -0.5510f, -0.0030f, -1.3040f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, -2.1610f, -0.0840f, 0.5440f, -0.8470f, 0.3930f, 0.2180f, -0.2320f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.2500f, 0.0000f, 0.0400f, 0.6000f, -0.3500f, -1.2500f, 0.0000f, 0.1000f, 0.9000f, 0.4000f, 0.2000f, 0.0000f, 0.0000f)
    @JvmField val STUCK_TUG = floatArrayOf(0.0000f, 7.0000f, 7.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.6500f, -0.5680f, 0.1010f, -0.6480f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, -2.0800f, 0.2430f, 0.5260f, -0.7550f, 0.4130f, 0.2250f, -0.0100f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.2500f, 0.0000f, 0.0400f, 0.6000f, -0.3500f, -1.2500f, 0.0000f, 0.1000f, 0.9000f, 0.4000f, 0.2000f, 0.0000f, 0.0000f)
    @JvmField val THROW_WINDUP = floatArrayOf(0.0000f, 2.0000f, 0.0000f, 0.0000f, 0.4500f, 0.0000f, 0.0200f, 0.3000f, 0.0000f, -0.1500f, 0.4500f, 0.0000f, 0.0400f, -0.7500f, 0.0000f, 0.0000f, 0.0000f, -1.1780f, 0.3440f, 0.2000f, -2.0520f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 0.0550f, -0.3060f, 2.0020f, -1.5000f, -0.1430f, -0.3500f, -0.1000f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.0400f, 0.3500f, -0.3000f, -0.5500f, 0.0000f, -0.0400f, 0.3000f, 0.0000f, 0.4000f, 0.0000f, 0.0000f)
    @JvmField val THROW_RELEASE = floatArrayOf(0.0000f, 5.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.2500f, -0.3000f, 0.0000f, 0.3500f, -0.4000f, 0.0000f, -0.1000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, -1.0280f, 2.7300f, -0.7940f, -2.5750f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, -0.1580f, -0.4430f, 2.8200f, 0.4000f, -0.1430f, -0.4000f, -0.6000f, 0.6000f, 0.0000f, 0.0000f, 0.8000f, 0.5500f, 0.0000f, 0.0400f, 0.4000f, -0.3000f, -0.8500f, 0.0000f, -0.0400f, 0.5000f, 0.0000f, 0.8500f, 0.0000f, 0.0000f)
    @JvmField val THROW_FOLLOW = floatArrayOf(0.0000f, 7.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.4500f, -0.3000f, 0.0000f, 0.3500f, -0.4000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, -0.6000f, 2.7300f, -0.2000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, -0.1580f, -0.4430f, 2.8200f, 0.6000f, -0.1430f, -0.4000f, -0.4000f, 0.6000f, 0.0000f, 0.0000f, 0.8000f, 0.5500f, 0.0000f, 0.0400f, 0.4000f, -0.3000f, -0.8500f, 0.0000f, -0.0400f, 0.5000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f)
    @JvmField val EMPTY = floatArrayOf(0.0000f, 1.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0400f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0400f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, -1.4500f, -0.2110f, 0.2500f, -0.1000f, 0.0000f, 0.2500f, 0.0000f, 0.1500f, 0.3870f, 0.2390f, -0.0410f, 0.5750f, -0.1430f, 0.1340f, -0.7210f, 0.6000f, 0.0000f, 0.0000f, 0.7000f, -0.2500f, 0.0000f, 0.0400f, 0.1500f, 0.0000f, 0.2000f, 0.0000f, -0.0400f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0000f)
    @JvmField val EMPTY_CURL = floatArrayOf(0.0000f, 1.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0400f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0400f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, -1.3500f, -0.2110f, 0.2500f, -0.1000f, 0.0000f, -0.1000f, 0.0000f, 0.7500f, 0.3870f, 0.2390f, -0.0410f, 0.5750f, -0.1430f, 0.1340f, -0.7210f, 0.6000f, 0.0000f, 0.0000f, 0.7000f, -0.2500f, 0.0000f, 0.0400f, 0.1500f, 0.0000f, 0.2000f, 0.0000f, -0.0400f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0000f)
    @JvmField val GAZE_REACH = floatArrayOf(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0200f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0380f, -0.2110f, 0.1200f, -0.3170f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 0.3870f, 0.2390f, -0.0410f, -0.7950f, 0.6350f, 1.1940f, -1.4300f, 0.6000f, 0.0000f, 0.0000f, 0.5000f, -0.0400f, 0.0000f, 0.0400f, 0.0400f, 0.0000f, 0.0500f, 0.0000f, -0.0400f, 0.0000f, 0.0000f, 0.4000f, 0.0000f, 0.0000f)
    @JvmField val GAZE_CHARGE = floatArrayOf(0.0000f, -1.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, -0.1600f, 0.0000f, 0.0000f, -0.3200f, 0.0000f, 0.0000f, -1.2500f, -1.0000f, -0.0380f, -0.2110f, 0.1200f, -0.3170f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 0.3870f, 0.2390f, -0.0410f, -1.4120f, 0.8320f, 1.2210f, -0.1810f, 0.6000f, 0.0000f, 0.0000f, 0.6000f, -0.0400f, 0.0000f, 0.0400f, 0.0400f, 0.0000f, 0.0500f, 0.0000f, -0.0400f, 0.0000f, 0.0000f, 0.9000f, 0.0000f, 0.0000f)
    @JvmField val GAZE_FIRE = floatArrayOf(0.0000f, -1.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.1800f, 0.0000f, 0.0000f, -1.2500f, -1.0000f, -0.0380f, -0.2110f, 0.1200f, -0.3170f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 0.3870f, 0.2390f, -0.0410f, -0.6000f, 0.8320f, -0.9000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, -0.0400f, 0.0000f, 0.0400f, 0.0400f, 0.0000f, 0.0500f, 0.0000f, -0.0400f, 0.0000f, 0.0000f, 1.2000f, 0.0000f, 0.0000f)
    @JvmField val CHAINS_CAST = floatArrayOf(0.0000f, 3.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.0200f, -0.1500f, 0.0000f, 0.1500f, -0.3500f, 0.0000f, -0.0500f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, -0.0380f, -0.2110f, 0.1200f, -0.3170f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 0.3870f, 0.2390f, -0.0410f, -1.6000f, -0.1430f, -0.1000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.0400f, 0.3000f, -0.3000f, -0.6000f, 0.0000f, -0.0400f, 0.4000f, 0.0000f, 0.7000f, 0.0000f, 0.0000f)
    @JvmField val CHAINS_PULL = floatArrayOf(0.0000f, 5.0000f, 4.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1590f, -0.2110f, -0.1280f, -1.6660f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 1.3090f, 0.2540f, -1.1990f, -0.3910f, 0.5470f, -0.1320f, -1.4260f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 0.5500f, 0.0000f, 0.0400f, 0.3000f, -0.3000f, -0.7500f, 0.0000f, -0.0400f, 0.7500f, 0.1500f, 0.3500f, 0.0000f, 0.0000f)
    @JvmField val VANISH = floatArrayOf(0.0000f, 2.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0200f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 4.0000f, -0.0380f, -0.2110f, 0.1200f, -0.3170f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 0.3870f, 0.2390f, -0.0410f, 0.2610f, 0.6620f, 0.9500f, -1.4230f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, -0.0400f, 0.0000f, 0.0400f, 0.0400f, 0.0000f, 0.0500f, 0.0000f, -0.0400f, 0.0000f, 0.0000f, 0.6000f, 1.0000f, 0.0000f)
    @JvmField val AMBUSH_HIT = floatArrayOf(0.0000f, 6.0000f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, 0.0200f, -0.3500f, 0.0000f, 0.1500f, -0.6500f, 0.0500f, 0.0500f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.1070f, -0.9900f, 0.9520f, -2.1440f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, -0.5770f, 0.9580f, -2.3530f, -1.4670f, 0.9980f, -0.2740f, -0.0080f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.4000f, 0.0000f, 0.1200f, 0.3000f, -0.3000f, -0.7000f, 0.0000f, -0.1000f, 0.4000f, 0.2000f, 1.1000f, 0.0000f, 0.0000f)
    @JvmField val AMBUSH_RECOVER = floatArrayOf(2.0000f, 7.0000f, 0.0000f, 0.0000f, -0.7500f, -0.0600f, 0.3000f, -0.4000f, 0.0000f, 0.2500f, -0.8500f, 0.0500f, 0.0500f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.1660f, -1.2980f, 0.7020f, -1.9850f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, -0.5460f, 1.1450f, -2.1480f, 0.2890f, 0.5300f, 0.0270f, -2.0470f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.6000f, 0.0000f, 0.1200f, 0.5000f, -0.4000f, -0.4500f, 0.0000f, -0.1000f, 0.6000f, 0.2000f, 0.3000f, 0.0000f, 0.0000f)
    @JvmField val JUDGE_RAISE = floatArrayOf(0.0000f, -3.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, -0.1400f, 0.0000f, 0.0000f, -0.3800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.6490f, 0.1970f, 0.2000f, -0.0160f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 2.1010f, 1.2570f, 3.7870f, -2.5310f, -0.5970f, -0.4500f, -0.1680f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, -0.0400f, 0.0000f, 0.0400f, 0.0500f, -0.5500f, 0.0500f, 0.0000f, -0.0400f, 0.0500f, -0.5500f, 0.7000f, 0.0000f, 0.0000f)
    @JvmField val JUDGE_SLAM = floatArrayOf(0.0000f, 10.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4200f, 0.0000f, 0.0000f, 0.4000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.4660f, -2.3690f, 0.3700f, -1.8910f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, -0.7670f, -0.7890f, 2.8240f, -2.9790f, 0.4780f, -0.6060f, -1.3440f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.4000f, 0.0000f, 0.0400f, 0.8500f, -0.3500f, -0.9500f, 0.0000f, -0.0400f, 0.8500f, 0.1500f, 1.0000f, 0.0000f, 0.0000f)
    @JvmField val JUDGE_SPENT = floatArrayOf(0.0000f, 13.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.6200f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.7510f, -2.3740f, 0.2090f, -1.9330f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, -0.5220f, -0.5400f, 2.5330f, -3.4350f, 0.1100f, -0.8500f, -1.4060f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.2500f, 0.0000f, 0.0400f, 1.2000f, -0.6500f, -1.0500f, 0.0000f, -0.0400f, 1.2500f, -0.2000f, 0.0000f, 0.0000f, -0.4000f)
    @JvmField val STAGGER = floatArrayOf(0.0000f, 15.0000f, 2.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4200f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.9950f, -0.7560f, -0.0700f, -2.8650f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 1.2180f, -0.3310f, 0.7390f, 0.4910f, 0.4840f, 0.2000f, -2.0150f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, 0.1500f, 0.0000f, 0.0400f, 1.5000f, -0.1500f, -1.3500f, 0.0000f, -0.0400f, 1.3000f, 0.1000f, 0.0000f, 0.0000f, -0.7000f)
    @JvmField val STAGGER_EMPTY = floatArrayOf(0.0000f, 15.0000f, 2.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.3800f, 0.0000f, 0.0000f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0270f, -0.8760f, 0.5980f, -1.9400f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, 1.2180f, -0.3310f, 0.7390f, 0.4910f, 0.4840f, 0.2000f, -2.0150f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, 0.1500f, 0.0000f, 0.0400f, 1.5000f, -0.1500f, -1.3500f, 0.0000f, -0.0400f, 1.3000f, 0.1000f, 0.0000f, 0.0000f, -0.7000f)
    @JvmField val ROAR = floatArrayOf(0.0000f, 1.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, -0.2800f, 0.0000f, 0.0000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4000f, -0.2110f, 1.2500f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 0.4420f, 1.0790f, -1.3710f, -0.3500f, -0.1430f, -1.3500f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, -0.0400f, 0.0000f, 0.1000f, 0.0400f, 0.0000f, 0.0500f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.9000f, 0.0000f, 1.0000f)
    @JvmField val CAST = floatArrayOf(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0200f, 0.0000f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0380f, -0.2110f, 0.1200f, -0.3170f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 0.3870f, 0.2390f, -0.0410f, -1.5500f, -0.1430f, -0.4500f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, -0.0400f, 0.0000f, 0.0400f, 0.0400f, 0.0000f, 0.0500f, 0.0000f, -0.0400f, 0.0000f, 0.0000f, 0.4500f, 0.0000f, 0.5000f)
    @JvmField val WINDUP = floatArrayOf(0.0000f, 3.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0200f, 0.2000f, 0.0000f, 0.0800f, 0.3500f, 0.0000f, 0.0400f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.1760f, -0.3540f, -0.3920f, -1.9010f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, -0.3650f, -3.7530f, 1.5990f, 0.1070f, -0.1110f, 0.6640f, -2.2410f, 0.6000f, 0.0000f, 0.0000f, 1.0000f, 0.3000f, 0.0000f, 0.0400f, 0.3000f, -0.2500f, -0.5000f, 0.0000f, -0.0400f, 0.3500f, 0.0000f, 0.3500f, 0.0000f, 0.0000f)
    @JvmField val SIT = floatArrayOf(0.0000f, 17.0000f, 2.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0600f, 0.0000f, 0.0000f, 0.0200f, 0.0000f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1470f, 0.1540f, -0.1300f, -1.8280f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 1.7690f, -0.1130f, -0.1660f, -0.2460f, 0.1640f, 0.1000f, -0.7720f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, -1.5000f, 0.0000f, 0.0800f, 1.4500f, 0.0000f, -1.5000f, 0.0000f, -0.0800f, 1.5000f, 0.0000f, 0.0000f, 0.0000f, -0.5000f)
    @JvmField val KNEEL = floatArrayOf(0.0000f, 15.0000f, 2.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.2200f, 0.0000f, 0.0000f, 0.4200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.6350f, -0.0860f, -0.1300f, -2.5500f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 1.5470f, -0.1990f, 0.0390f, 0.1830f, 0.4100f, 0.1000f, -1.6400f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, 0.1500f, 0.0000f, 0.0400f, 1.5000f, -0.1500f, -1.3500f, 0.0000f, -0.0400f, 1.3000f, 0.1000f, 0.0000f, 0.0000f, -0.7000f)
    @JvmField val FALLEN = floatArrayOf(0.0000f, 33.0000f, 0.0000f, -1.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, -0.2110f, 1.1000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 1.4000f, 0.0000f, 0.4000f, -0.1000f, -0.1430f, -1.1500f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, -0.0800f, 0.0000f, 0.0800f, 0.0400f, 0.0000f, 0.1000f, 0.0000f, -0.0600f, 0.0000f, 0.0000f, -0.9500f, 0.0000f, -0.9000f)
}
// endregion HadesKeys
