package dev.henny.hha.client.model

import dev.henny.hha.entity.miniboss.TantalusAction
import dev.henny.hha.logic.boss.BossTimings
import net.minecraft.client.model.ModelPart
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Tantalos: ein überlanger, ausgezehrter König mit dem leeren Kelch in der Linken und dem Dreizack in der Rechten; auf
 * seinem Rücken ist ein verdorrter Feigenast festgebunden, dessen Frucht ihm immer eine Handbreit vor dem Gesicht hängt.
 *
 * Die Schlüsselposen stehen in [TantalusPoses] und werden von `tools/hha_assets/tantalus_model.py` erzeugt (dieselben
 * Winkel wie in der Vorschau). Die Animation mischt sie entlang der Zeitachse der Aktion aus [TantalusAction]; die Zeiten
 * kommen aus [BossTimings.Tantalus]. Ast, Frucht, Haar und Gewand laufen mit einigen Ticks Verzug hinterher.
 */
class TantalusModel(root: ModelPart) : GuardianModel(root) {
    private val parts: Array<ModelPart> = Array(TantalusPoses.PATHS.size) { i ->
        TantalusPoses.PATHS[i].split('/').fold(root) { part, name -> part.getChild(name) }
    }
    private val cur = FloatArray(parts.size * 6)
    private val late = FloatArray(parts.size * 6)

    /** Stärke der Erschütterung (Krone wackelt), wird in [compose] gesetzt. */
    private var jolt = 0f

    /** 1 = steht, 0 = kniet (dann kein Gehen). */
    private var stand = 1f

    override fun setAngles(state: GuardianRenderState) {
        super.setAngles(state)
        val action = state.action
        val t = state.actionTime
        val age = state.age

        // Sekundärteile (Ast, Frucht, Haar, Gewand) folgen derselben Bewegung mit Verzug.
        compose(late, action, t - LAG, age - LAG)
        compose(cur, action, t, age)
        for (i in LAGGED) System.arraycopy(late, i * 6, cur, i * 6, 6)

        val w = walk(state) * stand
        val p = phase(state)

        // Atmen, Gang (schlurfend, Knie bleiben gebeugt, die Füße heben kaum ab).
        cur[TantalusPoses.CHEST * 6] += sin(age * 0.07f) * 0.025f
        cur[TantalusPoses.JAW * 6] += sin(age * 0.07f + 1.2f) * 0.05f
        cur[TantalusPoses.LEG_R * 6] += cos(p) * 0.45f * w
        cur[TantalusPoses.LEG_L * 6] += cos(p + PI.toFloat()) * 0.45f * w
        cur[TantalusPoses.SHIN_R * 6] += max(0f, -sin(p)) * 0.5f * w
        cur[TantalusPoses.SHIN_L * 6] += max(0f, sin(p)) * 0.5f * w
        cur[TantalusPoses.BODY * 6 + 4] += abs(sin(p)) * 1.2f * w
        cur[TantalusPoses.BODY * 6 + 2] += sin(p) * 0.04f * w
        cur[TantalusPoses.ARM_R * 6] += cos(p + PI.toFloat()) * 0.12f * w
        cur[TantalusPoses.ROBE_F * 6] += -abs(sin(p)) * 0.25f * w
        cur[TantalusPoses.ROBE_B * 6] += abs(sin(p)) * 0.2f * w
        cur[TantalusPoses.MANTLE * 6] += w * 0.12f + sin(age * 0.05f) * 0.03f

        // Haar und Frucht pendeln nach; der Ast federt beim Gehen.
        val sway = sin(age * 0.09f)
        cur[TantalusPoses.HAIR_R * 6 + 2] += sway * 0.06f
        cur[TantalusPoses.HAIR_L * 6 + 2] += sway * 0.06f
        cur[TantalusPoses.HAIR_R2 * 6] += sin(age * 0.09f - 0.8f) * 0.08f + w * 0.15f
        cur[TantalusPoses.HAIR_L2 * 6] += sin(age * 0.09f - 0.8f) * 0.08f + w * 0.15f
        cur[TantalusPoses.BRANCH_1 * 6] += sin(p * 2f) * 0.04f * w
        cur[TantalusPoses.FRUIT * 6] += sin(age * 0.11f) * 0.1f + sin(p * 2f - 0.7f) * 0.2f * w
        cur[TantalusPoses.FRUIT * 6 + 2] += sin(age * 0.07f + 0.5f) * 0.08f

        // Blick: im Ruhezustand folgt der Kopf dem Ziel, im Angriff nur noch wenig.
        val look = if (action == TantalusAction.NONE) 0.6f else 0.25f
        cur[TantalusPoses.HEAD * 6 + 1] += state.relativeHeadYaw * DEG * look

        // Die Krone sitzt zu locker: Sie wackelt bei harten Bewegungen.
        cur[TantalusPoses.CROWN * 6 + 2] += sin(age * 1.7f) * 0.09f * jolt
        cur[TantalusPoses.CROWN * 6] += sin(age * 1.3f + 0.4f) * 0.05f * jolt

        apply()

        // Der Kelch: in PARCHED fliegt er aus der Hand und ist dann weg.
        val goblet = parts[TantalusPoses.GOBLET]
        goblet.visible = true
        if (action == TantalusAction.PARCHED) {
            if (t < GOBLET_FLIGHT) {
                goblet.originY -= t * 3.5f
                goblet.originZ -= t * 2.5f
                goblet.originX += t * 1.5f
                goblet.pitch += t * 1.1f
                goblet.roll += t * 0.6f
            } else {
                goblet.visible = false
            }
        }
    }

    private fun apply() {
        for (i in parts.indices) {
            val part = parts[i]
            val o = i * 6
            if (cur[o].isNaN()) continue
            part.pitch = cur[o]
            part.yaw = cur[o + 1]
            part.roll = cur[o + 2]
            part.originX += cur[o + 3]
            part.originY += cur[o + 4]
            part.originZ += cur[o + 5]
        }
    }

    /** Baut die Pose für Aktion [action] zur Zeit [t] (Ticks seit Beginn) und Alter [age] in [out]. */
    private fun compose(out: FloatArray, action: Int, t: Float, age: Float) {
        System.arraycopy(TantalusPoses.IDLE, 0, out, 0, out.size)
        jolt = 0f
        stand = 1f
        when (action) {
            TantalusAction.THRUST3 -> thrust(out, t)
            TantalusAction.GEYSER -> geyser(out, t)
            TantalusAction.WAVE -> wave(out, t)
            TantalusAction.DRINK -> drink(out, t)
            TantalusAction.PARCHED -> parched(out, t)
            TantalusAction.RAGE -> rage(out, t)
            else -> snap(out, age)
        }
    }

    /**
     * Ruhe: Alle [SNAP_CYCLE] Ticks schnappt er mit dem Mund nach der Frucht. Die Frucht weicht [SNAP_LEAD] Ticks früher
     * aus, als der Kopf ankommt, so dass er immer ins Leere beißt.
     */
    private fun snap(out: FloatArray, age: Float) {
        val c = ((age % SNAP_CYCLE) + SNAP_CYCLE) % SNAP_CYCLE
        val head = hold(c - SNAP_START, 6f, 9f, 18f)
        val dodge = hold(c - SNAP_START + SNAP_LEAD, 5f, 12f, 26f)
        mix(out, TantalusPoses.IDLE_SNAP, head, SNAP_BODY)
        mix(out, TantalusPoses.IDLE_SNAP, dodge, BRANCH_BONES)
        jolt = head * 0.6f
    }

    /** THRUST3: Ausholen bis zum ersten Stoß, drei Stöße mit kurzem Zurückziehen, danach weit vorgebeugt (offen). */
    private fun thrust(out: FloatArray, t: Float) {
        val hits = floatArrayOf(T.THRUST_WINDUP.toFloat(), T.THRUST_HIT_2.toFloat(), T.THRUST_HIT_3.toFloat())
        mix(out, TantalusPoses.THRUST_WINDUP, step(t, 0f, SOFT))
        for (k in 0 until T.THRUST_COUNT) {
            val h = hits[k]
            val strike = step(t, h, h + HARD)
            mix(out, TantalusPoses.THRUST_HIT, strike)
            if (k >= 1) mix(out, TantalusPoses.THRUST_HIT_3, strike * (if (k == 1) 0.5f else 1f))
            if (k < T.THRUST_COUNT - 1) {
                // Kurz zurückziehen, bis der nächste Stoß angesagt ist; jedes Mal bleibt er etwas tiefer.
                val next = hits[k + 1]
                mix(out, TantalusPoses.THRUST_WINDUP, step(t, h + 4f, next - 3f) * (0.75f - 0.15f * k))
            }
            jolt = max(jolt, hold(t - h, 1f, 3f, 9f))
        }
        val last = hits[T.THRUST_COUNT - 1]
        mix(out, TantalusPoses.THRUST_RECOVER, step(t, last + 3f, last + 12f))
        mix(out, TantalusPoses.IDLE, step(t, T.THRUST_END - 6f, T.THRUST_END.toFloat()))
    }

    /** GEYSER: Dreizack senkrecht nach oben, drei kurze Stöße in die Luft (die Rufe), Kopf im Nacken. */
    private fun geyser(out: FloatArray, t: Float) {
        mix(out, TantalusPoses.GEYSER_RAISE, step(t, 0f, SOFT))
        var pulse = 0f
        for (k in 0 until T.GEYSER_COUNT) {
            val call = T.GEYSER_RAISE + k * T.GEYSER_GAP.toFloat()
            pulse = max(pulse, hold(t - call, 2f, 3f, 8f))
        }
        out[TantalusPoses.ARM_R * 6 + 4] -= 3f * pulse
        out[TantalusPoses.CHEST * 6] -= 0.12f * pulse
        out[TantalusPoses.HEAD * 6] -= 0.15f * pulse
        out[TantalusPoses.JAW * 6] += 0.3f * pulse
        jolt = pulse
        mix(out, TantalusPoses.IDLE, step(t, T.GEYSER_END - 8f, T.GEYSER_END.toFloat()))
    }

    /** WAVE: beidhändig über den Kopf und zurück, halten (zittern), dann im Bogen senkrecht in den Boden, knien. */
    private fun wave(out: FloatArray, t: Float) {
        val hit = T.WAVE_WINDUP.toFloat()
        mix(out, TantalusPoses.WAVE_WINDUP, step(t, 0f, 10f))
        val tremble = step(t, 10f, hit) * (1f - step(t, hit, hit + 1f))
        out[TantalusPoses.CHEST * 6] += sin(t * 2.6f) * 0.03f * tremble
        out[TantalusPoses.ARM_R * 6] += sin(t * 3.1f) * 0.03f * tremble
        out[TantalusPoses.ARM_L * 6] += sin(t * 3.1f + 0.6f) * 0.03f * tremble
        val slam = step(t, hit, hit + HARD)
        mix(out, TantalusPoses.WAVE_HIT, slam)
        val up = step(t, T.WAVE_END - 8f, T.WAVE_END.toFloat())
        mix(out, TantalusPoses.IDLE, up)
        jolt = hold(t - hit, 1f, 3f, 10f)
        stand = 1f - slam * (1f - up)
    }

    /**
     * DRINK: lässt den Dreizack sinken, fällt auf beide Knie und schöpft im Takt von [BossTimings.Tantalus.DRINK_CYCLE]:
     * schöpfen, Kelch zum Mund, Kopf im Nacken (der verletzliche Moment), wieder hinab.
     */
    private fun drink(out: FloatArray, t: Float) {
        val kneel = T.DRINK_KNEEL.toFloat()
        mix(out, TantalusPoses.DRINK_SCOOP, step(t, 0f, kneel))
        if (t > kneel) {
            val c = (t - kneel) % T.DRINK_CYCLE
            val sip = step(c, 4f, 10f) * (1f - step(c, 15f, 20f))
            mix(out, TantalusPoses.DRINK_SIP, sip)
            val gulp = hold(c - 10f, 1f, 4f, 5f)
            out[TantalusPoses.JAW * 6] += sin(c * 2.2f) * 0.12f * gulp
            out[TantalusPoses.HEAD * 6] -= 0.06f * gulp
        }
        val up = step(t, T.DRINK - 8f, T.DRINK.toFloat())
        mix(out, TantalusPoses.IDLE, up)
        jolt = hold(t - kneel + 2f, 1f, 2f, 6f)
        stand = 1f - step(t, 0f, kneel) * (1f - up)
    }

    /** PARCHED: Kelch weg, beide Hände an der Kehle, Oberkörper weit zurück, Mund weit offen; taumelt rückwärts. */
    private fun parched(out: FloatArray, t: Float) {
        mix(out, TantalusPoses.PARCHED, step(t, 0f, 3f))
        val reel = step(t, 2f, 6f) * (1f - step(t, T.PARCHED - 6f, T.PARCHED.toFloat()))
        val s = sin(t * 0.32f)
        out[TantalusPoses.BODY * 6 + 2] += s * 0.1f * reel
        out[TantalusPoses.BODY * 6 + 5] += (1.5f + s * 1.5f) * reel
        out[TantalusPoses.LEG_R * 6] += s * 0.35f * reel
        out[TantalusPoses.LEG_L * 6] -= s * 0.35f * reel
        out[TantalusPoses.CHEST * 6] += sin(t * 0.32f + 1f) * 0.08f * reel
        out[TantalusPoses.HEAD * 6 + 1] += sin(t * 0.9f) * 0.18f * reel
        out[TantalusPoses.JAW * 6] += sin(t * 0.6f) * 0.12f * reel
        mix(out, TantalusPoses.IDLE, step(t, T.PARCHED - 6f, T.PARCHED.toFloat()))
        jolt = 0.6f * reel + hold(t, 1f, 2f, 6f)
    }

    /** RAGE: reißt den Dreizack quer vor die Brust und schreit die Frucht an. */
    private fun rage(out: FloatArray, t: Float) {
        val pose = step(t, 0f, 5f)
        mix(out, TantalusPoses.RAGE, pose)
        val scream = pose * (1f - step(t, T.RAGE - 5f, T.RAGE.toFloat()))
        out[TantalusPoses.CHEST * 6 + 1] += sin(t * 2.2f) * 0.05f * scream
        out[TantalusPoses.ARM_R * 6] += sin(t * 2.9f) * 0.04f * scream
        out[TantalusPoses.ARM_L * 6] += sin(t * 2.9f + 0.5f) * 0.04f * scream
        out[TantalusPoses.JAW * 6] += sin(t * 1.7f) * 0.12f * scream
        mix(out, TantalusPoses.IDLE, step(t, T.RAGE - 5f, T.RAGE.toFloat()))
        jolt = scream
    }

    /** Mischt [pose] mit Gewicht [w] in [out]; Drehungen auf dem kürzesten Weg. NaN (ausgeblendet) wird übersprungen. */
    private fun mix(out: FloatArray, pose: FloatArray, w: Float, bones: IntArray? = null) {
        if (w <= 0f) return
        if (bones == null) {
            for (i in 0 until parts.size) mixBone(out, pose, w, i)
        } else {
            for (i in bones) mixBone(out, pose, w, i)
        }
    }

    private fun mixBone(out: FloatArray, pose: FloatArray, w: Float, i: Int) {
        val o = i * 6
        if (pose[o].isNaN() || out[o].isNaN()) return
        for (k in 0 until 3) {
            var d = pose[o + k] - out[o + k]
            while (d > PI_F) d -= TWO_PI
            while (d < -PI_F) d += TWO_PI
            out[o + k] += d * w
        }
        for (k in 3 until 6) out[o + k] += (pose[o + k] - out[o + k]) * w
    }

    private companion object {
        val T = BossTimings.Tantalus
        val SOFT = BossTimings.Anim.BLEND_SOFT.toFloat()
        val HARD = BossTimings.Anim.BLEND_HARD.toFloat()
        const val PI_F = 3.1415927f
        const val TWO_PI = 6.2831855f
        const val DEG = 0.017453292f

        /** Verzug der Sekundärteile in Ticks. */
        const val LAG = 3f

        const val SNAP_CYCLE = 110f
        const val SNAP_START = 80f
        const val SNAP_LEAD = 4f
        const val GOBLET_FLIGHT = 6f

        val LAGGED = intArrayOf(
            TantalusPoses.HAIR_R, TantalusPoses.HAIR_R2, TantalusPoses.HAIR_L, TantalusPoses.HAIR_L2,
            TantalusPoses.MANTLE, TantalusPoses.BRANCH, TantalusPoses.BRANCH_1, TantalusPoses.BRANCH_2, TantalusPoses.FRUIT,
            TantalusPoses.ROBE_F, TantalusPoses.ROBE_B, TantalusPoses.ROBE_R, TantalusPoses.ROBE_L,
        )
        val SNAP_BODY = intArrayOf(
            TantalusPoses.CHEST, TantalusPoses.HEAD, TantalusPoses.JAW, TantalusPoses.HAIR_R, TantalusPoses.HAIR_L,
            TantalusPoses.ARM_L, TantalusPoses.FORE_L, TantalusPoses.GOBLET,
        )
        val BRANCH_BONES = intArrayOf(TantalusPoses.BRANCH, TantalusPoses.BRANCH_1, TantalusPoses.BRANCH_2, TantalusPoses.FRUIT)
    }
}

/** Schlüsselposen (Radiant, Versatz in Einheiten), Knochenreihenfolge wie [PATHS]; NaN-pitch = ausgeblendet. */
@Suppress("unused")
private object TantalusPoses {
    // <poses> erzeugt von tantalus_model.py --sync-kotlin, nicht von Hand ändern
    const val BODY = 0
    const val CHEST = 1
    const val HEAD = 2
    const val JAW = 3
    const val CROWN = 4
    const val HAIR_R = 5
    const val HAIR_R2 = 6
    const val HAIR_L = 7
    const val HAIR_L2 = 8
    const val MANTLE = 9
    const val BRANCH = 10
    const val BRANCH_1 = 11
    const val BRANCH_2 = 12
    const val ARM_R = 13
    const val FORE_R = 14
    const val HAND_R = 15
    const val ARM_L = 16
    const val FORE_L = 17
    const val HAND_L = 18
    const val LEG_R = 19
    const val SHIN_R = 20
    const val LEG_L = 21
    const val SHIN_L = 22
    const val ROBE_F = 23
    const val ROBE_B = 24
    const val ROBE_R = 25
    const val ROBE_L = 26
    const val FOOT_R = 27
    const val FOOT_L = 28
    const val TRIDENT = 29
    const val GOBLET = 30
    const val FRUIT = 31
    val PATHS = arrayOf(
        "body",
        "body/chest",
        "body/chest/head",
        "body/chest/head/jaw",
        "body/chest/head/crown",
        "body/chest/head/hair_r",
        "body/chest/head/hair_r/hair_r2",
        "body/chest/head/hair_l",
        "body/chest/head/hair_l/hair_l2",
        "body/chest/mantle",
        "body/chest/branch",
        "body/chest/branch/branch_1",
        "body/chest/branch/branch_1/branch_2",
        "body/chest/arm_r",
        "body/chest/arm_r/fore_r",
        "body/chest/arm_r/fore_r/hand_r",
        "body/chest/arm_l",
        "body/chest/arm_l/fore_l",
        "body/chest/arm_l/fore_l/hand_l",
        "body/leg_r",
        "body/leg_r/shin_r",
        "body/leg_l",
        "body/leg_l/shin_l",
        "body/robe_f",
        "body/robe_b",
        "body/robe_r",
        "body/robe_l",
        "body/leg_r/shin_r/foot_r",
        "body/leg_l/shin_l/foot_l",
        "body/chest/arm_r/fore_r/trident",
        "body/chest/arm_l/fore_l/goblet",
        "body/chest/branch/branch_1/branch_2/fruit",
    )
    val IDLE = floatArrayOf(
        0.1047f, 0f, 0f, 0f, -0.0074f, 0f,  // body
        0.3491f, 0f, 0f, 0f, 0f, 0f,  // chest
        -0.3142f, 0f, 0f, 0f, 0f, 0f,  // head
        0.2443f, 0f, 0f, 0f, 0f, 0f,  // jaw
        0f, 0.1396f, -0.384f, 0f, 0f, 0f,  // crown
        -0.1396f, 0f, 0.1047f, 0f, 0f, 0f,  // hair_r
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_r2
        -0.1396f, 0f, -0.1047f, 0f, 0f, 0f,  // hair_l
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_l2
        0.0698f, 0f, 0f, 0f, 0f, 0f,  // mantle
        -0.2793f, 0f, 0.2618f, 0f, 0f, 0f,  // branch
        1.117f, 0.6632f, 0f, 0f, 0f, 0f,  // branch_1
        1.2217f, 0f, 0f, 0f, 0f, 0f,  // branch_2
        -0.384f, 0f, 0.1047f, 0f, 0f, 0f,  // arm_r
        -0.733f, 0f, 0f, 0f, 0f, 0f,  // fore_r
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_r
        -0.7679f, 0f, -0.1047f, 0f, 0f, 0f,  // arm_l
        -0.5934f, 0f, 0f, 0f, 0f, 0f,  // fore_l
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_l
        -0.2793f, 0f, 0.0524f, 0f, 0f, 0f,  // leg_r
        0.4887f, 0f, 0f, 0f, 0f, 0f,  // shin_r
        -0.2094f, 0f, -0.0524f, 0f, 0f, 0f,  // leg_l
        0.4189f, 0f, 0f, 0f, 0f, 0f,  // shin_l
        -0.2443f, 0f, 0f, 0f, 0f, 0f,  // robe_f
        0.1047f, 0f, 0f, 0f, 0f, 0f,  // robe_b
        -0.0698f, 0f, 0.1047f, 0f, 0f, 0f,  // robe_r
        -0.0698f, 0f, -0.1047f, 0f, 0f, 0f,  // robe_l
        -0.3142f, 0f, 0f, 0f, 0f, 0f,  // foot_r
        -0.3142f, 0f, 0f, 0f, 0f, 0f,  // foot_l
        -2.6162f, 0.0511f, 0.0091f, 0f, 0f, 0f,  // trident
        0.9065f, -0.1024f, 0.0218f, 0f, 0f, 0f,  // goblet
        -2.3655f, 0.1963f, 0.6079f, 0f, 0f, 0f,  // fruit
    )
    val IDLE_SNAP = floatArrayOf(
        0.1047f, 0f, 0f, 0f, -0.0074f, 0f,  // body
        0.5585f, 0f, 0f, 0f, 0f, 0f,  // chest
        -0.7679f, 0f, 0f, 0f, -3f, -6f,  // head
        0.8029f, 0f, 0f, 0f, 0f, 0f,  // jaw
        0f, 0.1396f, -0.384f, 0f, 0f, 0f,  // crown
        0.2443f, 0f, 0.1047f, 0f, 0f, 0f,  // hair_r
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_r2
        0.2443f, 0f, -0.1047f, 0f, 0f, 0f,  // hair_l
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_l2
        0.0698f, 0f, 0f, 0f, 0f, 0f,  // mantle
        -0.384f, 0f, 0.2618f, 0f, 0f, 0f,  // branch
        0.733f, 0.6632f, 0f, 0f, 0f, 0f,  // branch_1
        1.3963f, 0f, 0f, 0f, 0f, 0f,  // branch_2
        -0.384f, 0f, 0.1047f, 0f, 0f, 0f,  // arm_r
        -0.733f, 0f, 0f, 0f, 0f, 0f,  // fore_r
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_r
        -0.9076f, 0f, -0.1047f, 0f, 0f, 0f,  // arm_l
        -0.6981f, 0f, 0f, 0f, 0f, 0f,  // fore_l
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_l
        -0.2793f, 0f, 0.0524f, 0f, 0f, 0f,  // leg_r
        0.4887f, 0f, 0f, 0f, 0f, 0f,  // shin_r
        -0.2094f, 0f, -0.0524f, 0f, 0f, 0f,  // leg_l
        0.4189f, 0f, 0f, 0f, 0f, 0f,  // shin_l
        -0.2443f, 0f, 0f, 0f, 0f, 0f,  // robe_f
        0.1047f, 0f, 0f, 0f, 0f, 0f,  // robe_b
        -0.0698f, 0f, 0.1047f, 0f, 0f, 0f,  // robe_r
        -0.0698f, 0f, -0.1047f, 0f, 0f, 0f,  // robe_l
        -0.3142f, 0f, 0f, 0f, 0f, 0f,  // foot_r
        -0.3142f, 0f, 0f, 0f, 0f, 0f,  // foot_l
        -2.8246f, 0.0635f, 0.0169f, 0f, 0f, 0f,  // trident
        0.9427f, -0.1047f, -0.0037f, 0f, 0f, 0f,  // goblet
        -2.3037f, 0.0712f, 0.6042f, 0f, 0f, 0f,  // fruit
    )
    val THRUST_WINDUP = floatArrayOf(
        0.1745f, 0.384f, 0f, 0f, 3.8059f, 3f,  // body
        0.3142f, 0.2443f, 0f, 0f, 0f, 0f,  // chest
        -0.4538f, -0.5236f, 0f, 0f, 0f, 0f,  // head
        0.1745f, 0f, 0f, 0f, 0f, 0f,  // jaw
        0f, 0.1396f, -0.384f, 0f, 0f, 0f,  // crown
        -0.1396f, 0f, 0.1047f, 0f, 0f, 0f,  // hair_r
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_r2
        -0.1396f, 0f, -0.1047f, 0f, 0f, 0f,  // hair_l
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_l2
        0.0698f, 0f, 0f, 0f, 0f, 0f,  // mantle
        -0.5236f, 0f, 0.2618f, 0f, 0f, 0f,  // branch
        0.9076f, 0.6632f, 0f, 0f, 0f, 0f,  // branch_1
        1.9199f, 0f, 0f, 0f, 0f, 0f,  // branch_2
        0.5934f, 0f, 0.2443f, 0f, 0f, 0f,  // arm_r
        -1.8151f, 0f, 0f, 0f, 0f, 0f,  // fore_r
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_r
        0.9774f, 0f, -0.384f, 0f, 0f, 0f,  // arm_l
        -0.4538f, 0f, 0f, 0f, 0f, 0f,  // fore_l
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_l
        0.5236f, -0.3491f, 0.0698f, 0f, 0f, 0f,  // leg_r
        0.5236f, 0f, 0f, 0f, 0f, 0f,  // shin_r
        -0.8029f, -0.3491f, -0.0698f, 0f, 0f, 0f,  // leg_l
        0.8029f, 0f, 0f, 0f, 0f, 0f,  // shin_l
        -0.5934f, 0f, 0f, 0f, 0f, 0f,  // robe_f
        0.5236f, 0f, 0f, 0f, 0f, 0f,  // robe_b
        -0.1745f, 0f, 0.1396f, 0f, 0f, 0f,  // robe_r
        -0.3491f, 0f, -0.1396f, 0f, 0f, 0f,  // robe_l
        -1.2217f, 0f, 0f, 0f, 0f, 0f,  // foot_r
        -0.1745f, 0f, 0f, 0f, 0f, 0f,  // foot_l
        -0.8581f, -0.2205f, -0.5241f, 0f, 0f, 0f,  // trident
        -0.9916f, -0.1996f, 0.8309f, 0f, 0f, 0f,  // goblet
        -2.305f, 0.8854f, 0.9381f, 0f, 0f, 0f,  // fruit
    )
    val THRUST_HIT = floatArrayOf(
        0.3142f, -0.2443f, 0f, 0f, 4.0786f, -5f,  // body
        0.4538f, -0.3142f, 0f, 0f, 0f, 0f,  // chest
        -0.6632f, 0.3142f, 0f, 0f, 0f, 0f,  // head
        0.5236f, 0f, 0f, 0f, 0f, 0f,  // jaw
        0f, 0.1396f, -0.384f, 0f, 0f, 0f,  // crown
        0.2793f, 0f, 0.1396f, 0f, 0f, 0f,  // hair_r
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_r2
        0.2793f, 0f, -0.1396f, 0f, 0f, 0f,  // hair_l
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_l2
        0.0698f, 0f, 0f, 0f, 0f, 0f,  // mantle
        -0.6632f, 0f, 0.2618f, 0f, 0f, 0f,  // branch
        1.0821f, 0.6632f, 0f, 0f, 0f, 0f,  // branch_1
        2.0944f, 0f, 0f, 0f, 0f, 0f,  // branch_2
        -1.1519f, 0f, 0.1047f, 0f, 0f, 0f,  // arm_r
        -0.3142f, 0f, 0f, 0f, 0f, 0f,  // fore_r
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_r
        1.2566f, 0f, -0.4538f, 0f, 0f, 0f,  // arm_l
        -0.2793f, 0f, 0f, 0f, 0f, 0f,  // fore_l
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_l
        0.6981f, 0.2443f, 0.0698f, 0f, 0f, 0f,  // leg_r
        0.4538f, 0f, 0f, 0f, 0f, 0f,  // shin_r
        -1.0123f, 0.2443f, -0.0698f, 0f, 0f, 0f,  // leg_l
        0.9076f, 0f, 0f, 0f, 0f, 0f,  // shin_l
        -0.8029f, 0f, 0f, 0f, 0f, 0f,  // robe_f
        0.6981f, 0f, 0f, 0f, 0f, 0f,  // robe_b
        -0.2094f, 0f, 0.1396f, 0f, 0f, 0f,  // robe_r
        -0.4538f, 0f, -0.1396f, 0f, 0f, 0f,  // robe_l
        -1.4661f, 0f, 0f, 0f, 0f, 0f,  // foot_r
        -0.2094f, 0f, 0f, 0f, 0f, 0f,  // foot_l
        -0.7588f, 0.4392f, 0.4468f, 0f, 0f, 0f,  // trident
        -1.9173f, 0.3388f, -0.3221f, 0f, 0f, 0f,  // goblet
        3.0951f, -0.05f, 0.1335f, 0f, 0f, 0f,  // fruit
    )
    val THRUST_HIT_3 = floatArrayOf(
        0.4538f, -0.3142f, 0f, 0f, 6.5506f, -8f,  // body
        0.6632f, -0.3491f, 0f, 0f, 0f, 0f,  // chest
        -0.9076f, 0.3491f, 0f, 0f, 0f, 0f,  // head
        0.6283f, 0f, 0f, 0f, 0f, 0f,  // jaw
        0f, 0.1396f, -0.384f, 0f, 0f, 0f,  // crown
        0.3491f, 0f, 0.1396f, 0f, 0f, 0f,  // hair_r
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_r2
        0.3491f, 0f, -0.1396f, 0f, 0f, 0f,  // hair_l
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_l2
        0.0698f, 0f, 0f, 0f, 0f, 0f,  // mantle
        -0.8378f, 0f, 0.2618f, 0f, 0f, 0f,  // branch
        1.2217f, 0.6632f, 0f, 0f, 0f, 0f,  // branch_1
        2.234f, 0f, 0f, 0f, 0f, 0f,  // branch_2
        -1.2217f, 0f, 0.1047f, 0f, 0f, 0f,  // arm_r
        -0.2443f, 0f, 0f, 0f, 0f, 0f,  // fore_r
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_r
        1.3963f, 0f, -0.4887f, 0f, 0f, 0f,  // arm_l
        -0.1745f, 0f, 0f, 0f, 0f, 0f,  // fore_l
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_l
        0.8378f, 0.2443f, 0.0698f, 0f, 0f, 0f,  // leg_r
        0.4189f, 0f, 0f, 0f, 0f, 0f,  // shin_r
        -1.2217f, 0.2443f, -0.0698f, 0f, 0f, 0f,  // leg_l
        1.2217f, 0f, 0f, 0f, 0f, 0f,  // shin_l
        -0.9774f, 0f, 0f, 0f, 0f, 0f,  // robe_f
        0.8029f, 0f, 0f, 0f, 0f, 0f,  // robe_b
        -0.2443f, 0f, 0.1396f, 0f, 0f, 0f,  // robe_r
        -0.5236f, 0f, -0.1396f, 0f, 0f, 0f,  // robe_l
        -1.7104f, 0f, 0f, 0f, 0f, 0f,  // foot_r
        -0.4538f, 0f, 0f, 0f, 0f, 0f,  // foot_l
        -1.0223f, 0.6251f, 0.4045f, 0f, 0f, 0f,  // trident
        -2.4804f, 0.0939f, -0.3794f, 0f, 0f, 0f,  // goblet
        2.6245f, -0.1269f, 0.2118f, 0f, 0f, 0f,  // fruit
    )
    val THRUST_RECOVER = floatArrayOf(
        0.5236f, -0.1745f, 0f, 0f, 5.3547f, -6f,  // body
        0.7679f, -0.1047f, 0f, 0f, 0f, 0f,  // chest
        -0.2443f, 0.1745f, 0.1396f, 0f, 0f, 0f,  // head
        0.5236f, 0f, 0f, 0f, 0f, 0f,  // jaw
        0f, 0.1396f, -0.384f, 0f, 0f, 0f,  // crown
        -0.5236f, 0f, 0.0698f, 0f, 0f, 0f,  // hair_r
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_r2
        -0.5236f, 0f, -0.0698f, 0f, 0f, 0f,  // hair_l
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_l2
        0.0698f, 0f, 0f, 0f, 0f, 0f,  // mantle
        -0.8727f, 0f, 0.2618f, 0f, 0f, 0f,  // branch
        1.3963f, 0.6632f, 0f, 0f, 0f, 0f,  // branch_1
        2.4435f, 0f, 0f, 0f, 0f, 0f,  // branch_2
        -0.8727f, 0f, 0.0698f, 0f, 0f, 0f,  // arm_r
        -0.5236f, 0f, 0f, 0f, 0f, 0f,  // fore_r
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_r
        -0.2094f, 0f, -0.0698f, 0f, 0f, 0f,  // arm_l
        -0.1047f, 0f, 0f, 0f, 0f, 0f,  // fore_l
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_l
        0.5934f, 0.1396f, 0.0698f, 0f, 0f, 0f,  // leg_r
        0.5934f, 0f, 0f, 0f, 0f, 0f,  // shin_r
        -0.9076f, 0.1396f, -0.0698f, 0f, 0f, 0f,  // leg_l
        1.117f, 0f, 0f, 0f, 0f, 0f,  // shin_l
        -0.7679f, 0f, 0f, 0f, 0f, 0f,  // robe_f
        0.6283f, 0f, 0f, 0f, 0f, 0f,  // robe_b
        -0.1745f, 0f, 0.1396f, 0f, 0f, 0f,  // robe_r
        -0.4189f, 0f, -0.1396f, 0f, 0f, 0f,  // robe_l
        -1.7104f, 0f, 0f, 0f, 0f, 0f,  // foot_r
        -0.733f, 0f, 0f, 0f, 0f, 0f,  // foot_l
        -0.3028f, 0.3267f, 0.0704f, 0f, 0f, 0f,  // trident
        -0.2989f, 0.1687f, -0.1264f, 0f, 0f, 0f,  // goblet
        2.1166f, 0.2833f, 0.0481f, 0f, 0f, 0f,  // fruit
    )
    val GEYSER_RAISE = floatArrayOf(
        -0.0349f, 0f, 0f, 0f, -0.7243f, 0f,  // body
        -0.1047f, 0f, 0f, 0f, 0f, 0f,  // chest
        -0.5934f, 0f, 0f, 0f, 0f, 0f,  // head
        0.5934f, 0f, 0f, 0f, 0f, 0f,  // jaw
        0f, 0.1396f, -0.384f, 0f, 0f, 0f,  // crown
        0.1745f, 0f, 0.0698f, 0f, 0f, 0f,  // hair_r
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_r2
        0.1745f, 0f, -0.0698f, 0f, 0f, 0f,  // hair_l
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_l2
        0.0698f, 0f, 0f, 0f, 0f, 0f,  // mantle
        -0.1745f, 0f, 0.2618f, 0f, 0f, 0f,  // branch
        0.8378f, 0.6632f, 0f, 0f, 0f, 0f,  // branch_1
        1.8151f, 0f, 0f, 0f, 0f, 0f,  // branch_2
        -2.9322f, 0f, -0.0698f, 0f, 0f, 0f,  // arm_r
        -0.1396f, 0f, 0f, 0f, 0f, 0f,  // fore_r
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_r
        -0.5236f, 0f, -0.7679f, 0f, 0f, 0f,  // arm_l
        -0.5236f, 0f, 0f, 0f, 0f, 0f,  // fore_l
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_l
        -0.0698f, 0f, 0.1396f, 0f, 0f, 0f,  // leg_r
        0.1396f, 0f, 0f, 0f, 0f, 0f,  // shin_r
        -0.0698f, 0f, -0.1396f, 0f, 0f, 0f,  // leg_l
        0.1396f, 0f, 0f, 0f, 0f, 0f,  // shin_l
        -0.1047f, 0f, 0f, 0f, 0f, 0f,  // robe_f
        0.1047f, 0f, 0f, 0f, 0f, 0f,  // robe_b
        -0.0349f, 0f, 0.1745f, 0f, 0f, 0f,  // robe_r
        -0.0349f, 0f, -0.1745f, 0f, 0f, 0f,  // robe_l
        -0.0349f, 0f, 0f, 0f, 0f, 0f,  // foot_r
        -0.0349f, 0f, 0f, 0f, 0f, 0f,  // foot_l
        0.07f, -0.0049f, -0.0696f, 0f, 0f, 0f,  // trident
        1.0341f, -0.6455f, 0.4498f, 0f, 0f, 0f,  // goblet
        -2.14f, 0.3835f, 0.5537f, 0f, 0f, 0f,  // fruit
    )
    val WAVE_WINDUP = floatArrayOf(
        -0.1745f, 0f, 0f, 0f, -0.1888f, 3f,  // body
        -0.4189f, 0f, 0f, 0f, 0f, 0f,  // chest
        -0.1047f, 0f, 0f, 0f, 0f, 0f,  // head
        0.5236f, 0f, 0f, 0f, 0f, 0f,  // jaw
        0f, 0.1396f, -0.384f, 0f, 0f, 0f,  // crown
        0.4189f, 0f, 0.0698f, 0f, 0f, 0f,  // hair_r
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_r2
        0.4189f, 0f, -0.0698f, 0f, 0f, 0f,  // hair_l
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_l2
        0.0698f, 0f, 0f, 0f, 0f, 0f,  // mantle
        -0.0349f, 0f, 0.2618f, 0f, 0f, 0f,  // branch
        0.7679f, 0.6632f, 0f, 0f, 0f, 0f,  // branch_1
        1.7453f, 0f, 0f, 0f, 0f, 0f,  // branch_2
        -2.618f, 0f, 0.3142f, 0f, 0f, 0f,  // arm_r
        -0.6283f, 0f, 0f, 0f, 0f, 0f,  // fore_r
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_r
        -2.618f, 0f, -0.3142f, 0f, 0f, 0f,  // arm_l
        -0.6283f, 0f, 0f, 0f, 0f, 0f,  // fore_l
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_l
        0.2094f, 0f, 0.1396f, 0f, 0f, 0f,  // leg_r
        0.2443f, 0f, 0f, 0f, 0f, 0f,  // shin_r
        -0.5236f, 0f, -0.1396f, 0f, 0f, 0f,  // leg_l
        0.384f, 0f, 0f, 0f, 0f, 0f,  // shin_l
        -0.3142f, 0f, 0f, 0f, 0f, 0f,  // robe_f
        0.2094f, 0f, 0f, 0f, 0f, 0f,  // robe_b
        -0.0698f, 0f, 0.1396f, 0f, 0f, 0f,  // robe_r
        -0.2094f, 0f, -0.1396f, 0f, 0f, 0f,  // robe_l
        -0.2793f, 0f, 0f, 0f, 0f, 0f,  // foot_r
        0.3142f, 0f, 0f, 0f, 0f, 0f,  // foot_l
        -0.5636f, -0.0323f, 0.3125f, 0f, 0f, 0f,  // trident
        2.4384f, 0.0323f, -0.3125f, 0f, 0f, 0f,  // goblet
        -1.6871f, 0.3292f, 0.6262f, 0f, 0f, 0f,  // fruit
    )
    val WAVE_HIT = floatArrayOf(
        0.3491f, 0f, 0f, 0f, 11.0806f, -4f,  // body
        0.6981f, 0f, 0f, 0f, 0f, 0f,  // chest
        -0.5934f, 0f, 0f, 0f, 0f, 0f,  // head
        0.6283f, 0f, 0f, 0f, 0f, 0f,  // jaw
        0f, 0.1396f, -0.384f, 0f, 0f, 0f,  // crown
        -0.3491f, 0f, 0.0698f, 0f, 0f, 0f,  // hair_r
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_r2
        -0.3491f, 0f, -0.0698f, 0f, 0f, 0f,  // hair_l
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_l2
        0.0698f, 0f, 0f, 0f, 0f, 0f,  // mantle
        -0.8029f, 0f, 0.2618f, 0f, 0f, 0f,  // branch
        1.2566f, 0.6632f, 0f, 0f, 0f, 0f,  // branch_1
        2.3038f, 0f, 0f, 0f, 0f, 0f,  // branch_2
        -1.0821f, 0f, 0.1745f, 0f, 0f, 0f,  // arm_r
        -0.2793f, 0f, 0f, 0f, 0f, 0f,  // fore_r
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_r
        -1.0821f, 0f, -0.1745f, 0f, 0f, 0f,  // arm_l
        -0.2793f, 0f, 0f, 0f, 0f, 0f,  // fore_l
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_l
        0.1745f, 0f, 0.0698f, 0f, 0f, 0f,  // leg_r
        1.3963f, 0f, 0f, 0f, 0f, 0f,  // shin_r
        -1.4312f, 0f, -0.0698f, 0f, 0f, 0f,  // leg_l
        1.501f, 0f, 0f, 0f, 0f, 0f,  // shin_l
        -1.0472f, 0f, 0f, 0f, 0f, 0f,  // robe_f
        0.6981f, 0f, 0f, 0f, 0f, 0f,  // robe_b
        -0.3491f, 0f, 0.1745f, 0f, 0f, 0f,  // robe_r
        -0.6981f, 0f, -0.1745f, 0f, 0f, 0f,  // robe_l
        -1.9199f, 0f, 0f, 0f, 0f, 0f,  // foot_r
        -0.4189f, 0f, 0f, 0f, 0f, 0f,  // foot_l
        0.311f, 0.1707f, -0.0366f, 0f, 0f, 0f,  // trident
        -0.2126f, -0.1707f, 0.0366f, 0f, 0f, 0f,  // goblet
        2.5341f, 0.5034f, -0.0205f, 0f, 0f, 0f,  // fruit
    )
    val DRINK_SCOOP = floatArrayOf(
        0.4189f, 0f, 0f, 0f, 13.8276f, 2f,  // body
        0.5934f, 0f, 0f, 0f, 0f, 0f,  // chest
        -0.6981f, 0f, 0f, 0f, 0f, 0f,  // head
        0.4189f, 0f, 0f, 0f, 0f, 0f,  // jaw
        0f, 0.1396f, -0.384f, 0f, 0f, 0f,  // crown
        -0.5236f, 0f, 0.0698f, 0f, 0f, 0f,  // hair_r
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_r2
        -0.5236f, 0f, -0.0698f, 0f, 0f, 0f,  // hair_l
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_l2
        0.0698f, 0f, 0f, 0f, 0f, 0f,  // mantle
        -0.8727f, 0f, 0.2618f, 0f, 0f, 0f,  // branch
        1.6755f, 0.6632f, 0f, 0f, 0f, 0f,  // branch_1
        2.618f, 0f, 0f, 0f, 0f, 0f,  // branch_2
        -0.0698f, -0.2967f, 0.4887f, 0f, 0f, 0f,  // arm_r
        -1.3265f, 0f, 0f, 0f, 0f, 0f,  // fore_r
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_r
        -0.9076f, 0.2793f, -0.5411f, 0f, 0f, 0f,  // arm_l
        -0.8029f, 0f, 0f, 0f, 0f, 0f,  // fore_l
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_l
        -0.5236f, 0f, 0.0873f, 0f, 0f, 0f,  // leg_r
        1.9199f, 0f, 0f, 0f, 0f, 0f,  // shin_r
        -0.5236f, 0f, -0.0873f, 0f, 0f, 0f,  // leg_l
        1.9199f, 0f, 0f, 0f, 0f, 0f,  // shin_l
        -0.8727f, 0f, 0f, 0f, 0f, 0f,  // robe_f
        0.5236f, 0f, 0f, 0f, 0f, 0f,  // robe_b
        -0.5236f, 0f, 0.1745f, 0f, 0f, 0f,  // robe_r
        -0.5236f, 0f, -0.1745f, 0f, 0f, 0f,  // robe_l
        -0.5934f, 0f, 0f, 0f, 0f, 0f,  // foot_r
        -0.5934f, 0f, 0f, 0f, 0f, 0f,  // foot_l
        -1.2903f, 0.1362f, 0.0548f, 0f, 0f, 0f,  // trident
        1.2349f, -0.4974f, -0.3552f, 0f, 0f, 0f,  // goblet
        1.8445f, 0.3366f, -0.3604f, 0f, 0f, 0f,  // fruit
    )
    val DRINK_SIP = floatArrayOf(
        0f, 0f, 0f, 0f, 9.185f, 1f,  // body
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // chest
        -0.6981f, 0f, 0f, 0f, 0f, 0f,  // head
        0.6981f, 0f, 0f, 0f, 0f, 0f,  // jaw
        0f, 0.1396f, -0.384f, 0f, 0f, 0f,  // crown
        0.4538f, 0f, 0.0698f, 0f, 0f, 0f,  // hair_r
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_r2
        0.4538f, 0f, -0.0698f, 0f, 0f, 0f,  // hair_l
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_l2
        0.0698f, 0f, 0f, 0f, 0f, 0f,  // mantle
        -0.384f, 0f, 0.2618f, 0f, 0f, 0f,  // branch
        0.8378f, 0.6632f, 0f, 0f, 0f, 0f,  // branch_1
        1.6057f, 0f, 0f, 0f, 0f, 0f,  // branch_2
        -0.1745f, 0f, 0.384f, 0f, 0f, 0f,  // arm_r
        -0.4538f, 0f, 0f, 0f, 0f, 0f,  // fore_r
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_r
        -1.7802f, 0.4189f, -0.2094f, 0f, 0f, 0f,  // arm_l
        -1.885f, 0f, 0f, 0f, 0f, 0f,  // fore_l
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_l
        0f, 0f, 0.0873f, 0f, 0f, 0f,  // leg_r
        1.5708f, 0f, 0f, 0f, 0f, 0f,  // shin_r
        0f, 0f, -0.0873f, 0f, 0f, 0f,  // leg_l
        1.5708f, 0f, 0f, 0f, 0f, 0f,  // shin_l
        -0.5236f, 0f, 0f, 0f, 0f, 0f,  // robe_f
        1.0472f, 0f, 0f, 0f, 0f, 0f,  // robe_b
        -0.1745f, 0f, 0.2443f, 0f, 0f, 0f,  // robe_r
        -0.1745f, 0f, -0.2443f, 0f, 0f, 0f,  // robe_l
        -0.3491f, 0f, 0f, 0f, 0f, 0f,  // foot_r
        -0.3491f, 0f, 0f, 0f, 0f, 0f,  // foot_l
        -0.9574f, -0.1119f, -0.5705f, 0f, 0f, 0f,  // trident
        1.95f, 0.4651f, 0.0211f, 0f, 0f, 0f,  // goblet
        -1.836f, 0.2452f, 0.562f, 0f, 0f, 0f,  // fruit
    )
    val PARCHED = floatArrayOf(
        -0.2094f, 0f, 0f, 0f, 0.1112f, 3f,  // body
        -0.4189f, 0f, 0f, 0f, 0f, 0f,  // chest
        -0.5236f, 0f, 0f, 0f, 0f, 0f,  // head
        0.9076f, 0f, 0f, 0f, 0f, 0f,  // jaw
        0f, 0.1396f, -0.384f, 0f, 0f, 0f,  // crown
        0.5236f, 0f, 0.1396f, 0f, 0f, 0f,  // hair_r
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_r2
        0.5236f, 0f, -0.1396f, 0f, 0f, 0f,  // hair_l
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_l2
        0.0698f, 0f, 0f, 0f, 0f, 0f,  // mantle
        0.0698f, 0f, 0.2618f, 0f, 0f, 0f,  // branch
        0.9774f, 0.6632f, 0f, 0f, 0f, 0f,  // branch_1
        1.9199f, 0f, 0f, 0f, 0f, 0f,  // branch_2
        -1.117f, -0.1047f, 0.4887f, 0f, 0f, 0f,  // arm_r
        -2.4435f, 0f, 0f, 0f, 0f, 0f,  // fore_r
        -0.3491f, 0f, 0f, 0f, 0f, 0f,  // hand_r
        -1.117f, 0.1047f, -0.4887f, 0f, 0f, 0f,  // arm_l
        -2.4435f, 0f, 0f, 0f, 0f, 0f,  // fore_l
        -0.3491f, 0f, 0f, 0f, 0f, 0f,  // hand_l
        -0.4538f, 0f, 0.1047f, 0f, 0f, 0f,  // leg_r
        0.5236f, 0f, 0f, 0f, 0f, 0f,  // shin_r
        0.2443f, 0f, -0.1047f, 0f, 0f, 0f,  // leg_l
        0.3142f, 0f, 0f, 0f, 0f, 0f,  // shin_l
        -0.4538f, 0f, 0f, 0f, 0f, 0f,  // robe_f
        0.0349f, 0f, 0f, 0f, 0f, 0f,  // robe_b
        -0.1396f, 0f, 0.1745f, 0f, 0f, 0f,  // robe_r
        0f, 0f, -0.1745f, 0f, 0f, 0f,  // robe_l
        0.1396f, 0f, 0f, 0f, 0f, 0f,  // foot_r
        -0.3491f, 0f, 0f, 0f, 0f, 0f,  // foot_l
        -1.74f, 0.1274f, 0.1449f, 0f, 0f, 0f,  // trident
        Float.NaN, 0f, 0f, 0f, 0f, 0f,  // goblet
        -2.1061f, 0.5567f, 0.4877f, 0f, 0f, 0f,  // fruit
    )
    val RAGE = floatArrayOf(
        -0.0698f, 0f, 0f, 0f, -0.211f, 0f,  // body
        -0.1745f, 0f, 0f, 0f, 0f, 0f,  // chest
        -0.733f, 0f, 0f, 0f, 0f, 0f,  // head
        0.9425f, 0f, 0f, 0f, 0f, 0f,  // jaw
        0f, 0.1396f, -0.384f, 0f, 0f, 0f,  // crown
        0.2793f, 0f, 0.1047f, 0f, 0f, 0f,  // hair_r
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_r2
        0.2793f, 0f, -0.1047f, 0f, 0f, 0f,  // hair_l
        -0.0698f, 0f, 0f, 0f, 0f, 0f,  // hair_l2
        0.0698f, 0f, 0f, 0f, 0f, 0f,  // mantle
        -0.2443f, 0f, 0.2618f, 0f, 0f, 0f,  // branch
        0.8727f, 0.6632f, 0f, 0f, 0f, 0f,  // branch_1
        1.7802f, 0f, 0f, 0f, 0f, 0f,  // branch_2
        -0.1571f, -0.1396f, 0.2618f, 0f, 0f, 0f,  // arm_r
        -1.9897f, 0f, 0f, 0f, 0f, 0f,  // fore_r
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_r
        -0.1571f, 0.1396f, -0.2618f, 0f, 0f, 0f,  // arm_l
        -1.9897f, 0f, 0f, 0f, 0f, 0f,  // fore_l
        0f, 0f, 0f, 0f, 0f, 0f,  // hand_l
        -0.1396f, 0f, 0.1745f, 0f, 0f, 0f,  // leg_r
        0.2443f, 0f, 0f, 0f, 0f, 0f,  // shin_r
        -0.1396f, 0f, -0.1745f, 0f, 0f, 0f,  // leg_l
        0.2443f, 0f, 0f, 0f, 0f, 0f,  // shin_l
        -0.1745f, 0f, 0f, 0f, 0f, 0f,  // robe_f
        0.1396f, 0f, 0f, 0f, 0f, 0f,  // robe_b
        -0.0349f, 0f, 0.2094f, 0f, 0f, 0f,  // robe_r
        -0.0349f, 0f, -0.2094f, 0f, 0f, 0f,  // robe_l
        -0.0349f, 0f, 0f, 0f, 0f, 0f,  // foot_r
        -0.0349f, 0f, 0f, 0f, 0f, 0f,  // foot_l
        -2.9455f, 0.7404f, 1.9633f, 0f, 0f, 0f,  // trident
        2.3915f, -0.1443f, -0.2593f, 0f, 0f, 0f,  // goblet
        -1.9734f, 0.3721f, 0.5385f, 0f, 0f, 0f,  // fruit
    )
    // </poses>
}
