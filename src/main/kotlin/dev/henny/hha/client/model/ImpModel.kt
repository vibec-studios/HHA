package dev.henny.hha.client.model

import dev.henny.hha.Hha
import dev.henny.hha.entity.ImpEntity
import dev.henny.hha.logic.boss.BossTimings
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
import net.minecraft.entity.LivingEntity
import net.minecraft.util.Identifier
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ImpRenderState : LivingEntityRenderState() {
    /** Lädt einen Feuerball in der erhobenen Hand (`BossTimings.Imp.FIREBALL_WINDUP`). */
    var charging = false

    /** Ticks seit dem letzten Wechsel von [charging]. */
    var chargeTime = 0f

    /** Sturzangriff des Vex (alte Bewegung, entfällt mit Paket K4). */
    var dashing = false

    /** Zeit in der Feuerball-Animation, 0 … [BossTimings.Imp.FIREBALL_END]; negativ = keine. */
    var throwTime = -1f

    /** Zeit im Stich, 0 … [BossTimings.Imp.STAB_END]; negativ = keiner. */
    var stabTime = -1f

    /** Fortschritt des Getroffen-Zuckens 0 … 1 über [BossTimings.Imp.HURT] Ticks; negativ = keins. */
    var hurtProgress = -1f
}

/**
 * Das Teufelchen (Brief tmp/overhaul/models/teufelchen.md): ein geduckter Aschekobold, der vornübergebeugt in der Luft
 * hängt. Alle Schlüsselposen stehen in [ImpPoses] und mit denselben Zahlen in `tools/hha_assets/imp_model.py`
 * (`POSES`); die Animation mischt nur zwischen ihnen und legt Sekundärbewegung darüber.
 *
 * - Fliegen: Flügelschlag in 8 Ticks (`fly_up` ↔ `fly_down`), die Flügelspitzen schlagen nach, der Körper hüpft im Takt,
 *   Beine baumeln, Ohren zucken einzeln, der Schwanz ringelt.
 * - Stich ([ImpRenderState.stabTime]): Ausholen bis 7 weich, halten bis [BossTimings.Imp.STAB_WINDUP], Stoß in 2 Ticks,
 *   Nachlauf bis 13, zurück bis [BossTimings.Imp.STAB_END].
 * - Feuerball ([ImpRenderState.throwTime]): Hand hoch bis 7, die Glut wächst bis zum Wurf bei
 *   [BossTimings.Imp.FIREBALL_WINDUP], Schleuder in 2 Ticks, danach Salto rückwärts bis [BossTimings.Imp.FIREBALL_END].
 * - Getroffen ([ImpRenderState.hurtProgress]): Flügel verkrampfen, Ohren legen sich an, Trudler.
 */
class ImpModel(root: ModelPart) : EntityModel<ImpRenderState>(root) {
    private val body = root.getChild("body")
    private val head = body.getChild("head")
    private val jaw = head.getChild("jaw")
    private val earR = head.getChild("ear_r")
    private val earL = head.getChild("ear_l")
    private val armR = body.getChild("arm_r")
    private val armL = body.getChild("arm_l")
    private val fork = armR.getChild("fork")
    private val ember = armR.getChild("ember")
    private val legR = body.getChild("leg_r")
    private val legL = body.getChild("leg_l")
    private val wingR = body.getChild("wing_r")
    private val tipR = wingR.getChild("wing_r_tip")
    private val wingL = body.getChild("wing_l")
    private val tipL = wingL.getChild("wing_l_tip")
    private val tail0 = body.getChild("tail_0")
    private val tail1 = tail0.getChild("tail_1")
    private val tail2 = tail1.getChild("tail_2")

    /** Gleiche Reihenfolge wie [ImpPoses.BONES]. */
    private val bones = arrayOf(body, head, jaw, earR, earL, armR, armL, fork, legR, legL, wingR, tipR, wingL, tipL, tail0, tail1, tail2)
    private val cur = FloatArray(bones.size * 6)
    private val target = FloatArray(bones.size * 6)

    override fun setAngles(state: ImpRenderState) {
        super.setAngles(state)
        val age = state.age

        // 1. Fliegen: Flügelschlag zwischen den beiden Endlagen, die Spitzen (und nur sie) phasenverschoben.
        val phase = age * FLAP
        val f = 0.5f - 0.5f * cos(phase)
        val fTip = 0.5f - 0.5f * cos(phase - TIP_LAG)
        mix(cur, ImpPoses.FLY_UP, ImpPoses.FLY_DOWN, f)
        mixBone(cur, ImpPoses.FLY_UP, ImpPoses.FLY_DOWN, fTip, TIP_R)
        mixBone(cur, ImpPoses.FLY_UP, ImpPoses.FLY_DOWN, fTip, TIP_L)

        // 2. Stich: Ausholen weich hinein, halten, harter Stoß, Nachlauf, zurück in den Flug.
        val stab = state.stabTime
        if (stab >= 0f) {
            val w = step(stab, 0f, STAB_IN) * (1f - step(stab, STAB_FOLLOW, BossTimings.Imp.STAB_END.toFloat()))
            val k = step(stab, BossTimings.Imp.STAB_WINDUP - BossTimings.Anim.BLEND_HARD.toFloat(), BossTimings.Imp.STAB_WINDUP.toFloat())
            mix(target, ImpPoses.STAB_WINDUP, ImpPoses.STAB_HIT, k)
            // Nachlauf: der Stoß schießt noch ein Stück über das Ziel hinaus.
            val over = bump(stab, BossTimings.Imp.STAB_WINDUP.toFloat(), STAB_FOLLOW)
            target[BODY * 6] += 0.18f * over
            target[BODY * 6 + 5] -= 2.5f * over
            toward(cur, target, w)
        } else if (state.dashing) {
            // Alter Vex-Sturzangriff: zeigt die Stoßhaltung, solange er läuft (entfällt mit Paket K4).
            toward(cur, ImpPoses.STAB_HIT, 0.85f)
        }

        // 3. Feuerball: aufladen, schleudern, Salto rückwärts.
        val tt = state.throwTime
        var flip = 0f
        if (tt >= 0f) {
            val windup = BossTimings.Imp.FIREBALL_WINDUP.toFloat()
            val end = BossTimings.Imp.FIREBALL_END.toFloat()
            val w = step(tt, 0f, THROW_IN) * (1f - step(tt, end - 3f, end))
            val k = step(tt, windup - BossTimings.Anim.BLEND_HARD, windup)
            mix(target, ImpPoses.THROW_CHARGE, ImpPoses.THROW_RELEASE, k)
            toward(cur, target, w)
            flip = step(tt, windup + 2f, end - 1f)
            val tuck = sin(flip * PI.toFloat())
            // Im Salto zieht er sich zusammen: Beine an, Flügel nach hinten.
            cur[LEG_R * 6] -= 0.9f * tuck
            cur[LEG_L * 6] -= 0.9f * tuck
            cur[WING_R * 6 + 1] += 0.7f * tuck
            cur[WING_L * 6 + 1] -= 0.7f * tuck
        }

        // 4. Getroffen.
        val hp = state.hurtProgress
        var tumble = 0f
        if (hp >= 0f) {
            toward(cur, ImpPoses.HURT, step(hp, 0f, 0.15f) * (1f - step(hp, 0.5f, 1f)))
            tumble = sin(hp * PI.toFloat() * 2f) * (1f - hp) * 0.5f
        }

        apply()

        // 5. Sekundärbewegung (immer obendrauf).
        val idle = if (tt >= 0f || stab >= 0f) 0.4f else 1f
        body.pitch -= flip * 2f * PI.toFloat()
        body.roll += tumble + sin(age * 0.11f) * 0.06f * idle
        head.yaw += state.relativeHeadYaw.coerceIn(-50f, 50f) * DEG * 0.8f
        head.pitch += state.pitch * DEG * 0.8f
        earR.roll += twitch(age, 47f, 0f) * 0.45f
        earL.roll -= twitch(age, 61f, 23f) * 0.45f
        legR.pitch += sin(phase + 1.3f) * 0.10f * idle
        legL.pitch += sin(phase + 1.9f) * 0.12f * idle
        tail0.yaw += sin(age * 0.33f) * 0.30f
        tail1.yaw += sin(age * 0.33f - 0.9f) * 0.40f
        tail2.yaw += sin(age * 0.33f - 1.8f) * 0.50f
        tail2.pitch += sin(age * 0.21f) * 0.15f

        // Glutball: nur beim Aufladen, wächst von 0 auf 1 und flackert.
        val charge = tt >= 0f && tt < BossTimings.Imp.FIREBALL_WINDUP
        ember.visible = charge
        if (charge) {
            val s = step(tt, 1f, BossTimings.Imp.FIREBALL_WINDUP - 2f) * (1f + sin(age * 1.7f) * 0.08f)
            ember.xScale = s
            ember.yScale = s
            ember.zScale = s
            ember.yaw = age * 0.45f
            ember.pitch = age * 0.3f
        }
    }

    private fun apply() {
        for (i in bones.indices) {
            val p = bones[i]
            val o = i * 6
            p.pitch = cur[o]
            p.yaw = cur[o + 1]
            p.roll = cur[o + 2]
            p.originX += cur[o + 3]
            p.originY += cur[o + 4]
            p.originZ += cur[o + 5]
        }
    }

    private fun mix(out: FloatArray, a: FloatArray, b: FloatArray, k: Float) {
        for (i in out.indices) out[i] = a[i] + (b[i] - a[i]) * k
    }

    private fun mixBone(out: FloatArray, a: FloatArray, b: FloatArray, k: Float, bone: Int) {
        for (i in bone * 6 until bone * 6 + 6) out[i] = a[i] + (b[i] - a[i]) * k
    }

    private fun toward(out: FloatArray, to: FloatArray, w: Float) {
        if (w <= 0f) return
        for (i in out.indices) out[i] += (to[i] - out[i]) * w
    }

    private fun step(t: Float, from: Float, to: Float): Float {
        val x = ((t - from) / (to - from)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** 0 → 1 → 0 zwischen [from] und [to]. */
    private fun bump(t: Float, from: Float, to: Float): Float =
        if (t <= from || t >= to) 0f else sin((t - from) / (to - from) * PI.toFloat())

    /** Kurzes Ohrzucken alle [period] Ticks (3 Ticks lang). */
    private fun twitch(age: Float, period: Float, offset: Float): Float {
        val t = (age + offset) % period
        return if (t < 3f) sin(t / 3f * PI.toFloat()) else 0f
    }

    private companion object {
        const val DEG = 0.017453292f

        /** Ein Flügelschlag dauert 8 Ticks. */
        const val FLAP = 0.7853982f
        const val TIP_LAG = 1.0f

        /** Ausholen: weich hinein (Plan 6.4: 6–8 Ticks), danach gehalten bis zum Stoß. */
        const val STAB_IN = 7f

        /** Ende des Nachlaufs nach dem Stoß. */
        const val STAB_FOLLOW = 13f
        const val THROW_IN = 7f

        // Indizes in ImpPoses.BONES
        const val BODY = 0
        const val LEG_R = 8
        const val LEG_L = 9
        const val WING_R = 10
        const val TIP_R = 11
        const val WING_L = 12
        const val TIP_L = 13
    }
}

/** Schlüsselposen: je Knochen (pitch, yaw, roll, dx, dy, dz) in der Reihenfolge von [BONES]. */
private object ImpPoses {
    // <POSES> generiert von tools/hha_assets/imp_model.py (--kotlin); nicht von Hand ändern.
    val BONES = arrayOf("body", "head", "jaw", "ear_r", "ear_l", "arm_r", "arm_l", "fork", "leg_r", "leg_l", "wing_r", "wing_r_tip", "wing_l", "wing_l_tip", "tail_0", "tail_1", "tail_2")
    val FLY_UP = floatArrayOf(0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.7000f, 0.0000f, -0.3600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2094f, 0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2094f, -0.6981f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.0000f, -0.3800f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, -0.4200f, 0.0000f, 0.0000f, 0.0000f, -0.1200f, 0.0000f, 0.1800f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, -0.2200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5500f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.9500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val FLY_DOWN = floatArrayOf(0.5000f, 0.0000f, 0.0000f, 0.0000f, -0.7000f, 0.0000f, -0.3600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2094f, 0.6981f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2094f, -0.6981f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.0000f, -0.3800f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, -0.4200f, 0.0000f, 0.0000f, 0.0000f, -0.1200f, 0.0000f, 0.1800f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, -0.2200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.9500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val STAB_WINDUP = floatArrayOf(-0.2500f, 0.2500f, 0.0000f, 0.0000f, -1.0000f, 2.5000f, 0.1000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.6000f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.6000f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 2.7500f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 2.1000f, 0.0000f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, -0.9300f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.9500f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, -0.7000f, 0.0000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.2500f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.2000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.2500f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.2000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val STAB_HIT = floatArrayOf(1.1500f, -0.1000f, 0.0000f, 0.0000f, 0.5000f, -4.0000f, -0.9500f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.7500f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.7500f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 3.5600f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 3.3500f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, -3.1400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.0000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.7500f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.8500f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.8000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.8500f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.8000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, -1.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val THROW_CHARGE = floatArrayOf(-0.1200f, -0.2000f, 0.0000f, 0.0000f, -1.5000f, 0.0000f, -0.1000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.8500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, -0.8500f, 0.0000f, 0.0000f, 0.0000f, 3.0500f, 0.0000f, -0.6200f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, -1.1000f, 0.0000f, 0.0000f, 0.0000f, 0.9500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.7500f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.9500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val THROW_RELEASE = floatArrayOf(0.5500f, 0.1500f, 0.0000f, 0.0000f, -0.5000f, -1.0000f, -0.5500f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4500f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4500f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, 4.9800f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.8000f, 0.0000f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 2.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, -0.8000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val HURT = floatArrayOf(-0.2000f, 0.0000f, 0.4500f, 0.0000f, -0.5000f, 1.0000f, 0.2500f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.1500f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.1500f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, -0.8000f, 0.0000f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, -0.6000f, 0.0000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, -0.4200f, 0.0000f, 0.0000f, 0.0000f, -0.5500f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5500f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.9500f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.9500f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    // </POSES>
}

class ImpRenderer(context: EntityRendererFactory.Context) :
    MobEntityRenderer<ImpEntity, ImpRenderState, ImpModel>(context, ImpModel(context.getPart(BossLayers.IMP)), 0.3f) {

    private val texture: Identifier = Hha.id("textures/entity/boss/imp.png")
    private val glowLayer = RenderLayers.eyes(Hha.id("textures/entity/boss/imp_glow.png"))

    /** Was der Renderer je Teufelchen über die Zeit wissen muss (Beginn von Stich und Wurf). Nur Render-Thread. */
    private class Track {
        var wasSwinging = false
        var lastSwingTicks = 0
        var stabStart = Int.MIN_VALUE / 2
        var wasCharging = false
        var chargeStart = 0
        var releaseAge = Int.MIN_VALUE / 2
    }

    private val tracks = WeakHashMap<LivingEntity, Track>()

    init {
        addFeature(object : FeatureRenderer<ImpRenderState, ImpModel>(this) {
            override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: ImpRenderState, limbAngle: Float, limbDistance: Float) {
                queue.getBatchingQueue(1).submitModel(contextModel, state, matrices, glowLayer, 0xF000F0, OverlayTexture.DEFAULT_UV, -1, null, 0, null)
            }
        })
    }

    override fun createRenderState() = ImpRenderState()

    override fun getTexture(state: ImpRenderState): Identifier = texture

    override fun updateRenderState(entity: ImpEntity, state: ImpRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.charging = entity.fireballCharging
        state.chargeTime = (entity.age - entity.chargeChangedAge) + tickDelta

        val track = tracks.getOrPut(entity) { Track() }
        val now = entity.age + tickDelta

        // Stich: beginnt mit dem Ausholen, sobald der Server swingHand meldet (Treffer bei STAB_WINDUP).
        if (entity.handSwinging && (!track.wasSwinging || entity.handSwingTicks < track.lastSwingTicks)) {
            track.stabStart = entity.age
        }
        track.wasSwinging = entity.handSwinging
        track.lastSwingTicks = entity.handSwingTicks
        val stab = now - track.stabStart
        state.stabTime = if (stab >= 0f && stab < BossTimings.Imp.STAB_END) stab else -1f

        // Feuerball: Aufladen solange charging; der Wurf liegt bei FIREBALL_WINDUP. Schaltet der Server charging beim
        // Wurf ab, läuft die Animation von dort aus weiter; bricht er vorher ab, blendet sie nur aus.
        val charging = entity.fireballCharging
        if (charging && !track.wasCharging) track.chargeStart = entity.chargeChangedAge
        if (!charging && track.wasCharging) {
            val held = entity.chargeChangedAge - track.chargeStart
            track.releaseAge = if (held >= BossTimings.Imp.FIREBALL_WINDUP - 2) entity.chargeChangedAge - BossTimings.Imp.FIREBALL_WINDUP else Int.MIN_VALUE / 2
        }
        track.wasCharging = charging
        state.throwTime = when {
            charging -> (now - track.chargeStart).coerceAtMost(BossTimings.Imp.FIREBALL_END - 0.01f)
            else -> (now - track.releaseAge).let { if (it >= BossTimings.Imp.FIREBALL_WINDUP && it < BossTimings.Imp.FIREBALL_END) it else -1f }
        }

        state.hurtProgress = if (entity.hurtTime > 0) {
            ((BossTimings.Imp.HURT - entity.hurtTime + tickDelta) / BossTimings.Imp.HURT).coerceIn(0f, 1f)
        } else -1f
    }

    override fun scale(state: ImpRenderState, matrices: MatrixStack) {
        matrices.scale(SCALE, SCALE, SCALE)
    }

    private companion object {
        /** 38 Einheiten × 0,40 = 0,95 Blöcke (Plan 6.3). */
        const val SCALE = 0.40f
    }
}
