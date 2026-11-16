package dev.henny.hha.client.model

import dev.henny.hha.entity.miniboss.WardenAction
import dev.henny.hha.logic.boss.BossTimings
import net.minecraft.client.model.ModelPart
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Der Kerkermeister: vierarmiger Hundertarmiger in Bronze. Oben das Richtbeil (beidhändig), unten links die Kette mit
 * Haken, unten rechts der Schlüsselring; auf dem Rücken der Hände-Fächer und darunter die Laterne (Schwachstelle).
 *
 * Die Schlüsselposen stehen als Tabelle unten (generiert aus `tools/hha_assets/warden_model.py --kotlin`, dieselben
 * Winkel wie `POSES` dort). Die Ruhe ist die Grundhaltung des Modells; Gang, Atmen und Nachschwingen kommen als
 * Zusatz dazu. Jede Aktion läuft über `actionTime` selbst durch Ausholen → Schlag → Nachlauf/offen → zurück, mit den
 * Zeiten aus [BossTimings.Warden].
 */
class WardenModel(root: ModelPart) : GuardianModel(root) {
    private val parts: Array<ModelPart>
    private val rest: FloatArray
    private val base: FloatArray
    private val mixed: FloatArray

    init {
        val list = ArrayList<ModelPart>(BONES.size)
        for (i in BONES.indices) {
            val parent = PARENTS[i]
            list.add(if (parent < 0) root.getChild(BONES[i]) else list[parent].getChild(BONES[i]))
        }
        parts = list.toTypedArray()
        rest = FloatArray(parts.size * 3)
        for (i in parts.indices) {
            rest[i * 3] = parts[i].originX
            rest[i * 3 + 1] = parts[i].originY
            rest[i * 3 + 2] = parts[i].originZ
        }
        base = FloatArray(parts.size * 6)
        mixed = FloatArray(parts.size * 6)
    }

    private fun bone(name: String) = parts[BONES.indexOf(name)]

    private val body = bone("body")
    private val chest = bone("chest")
    private val head = bone("head")
    private val armUR = bone("arm_ur")
    private val armUL = bone("arm_ul")
    private val armLR = bone("arm_lr")
    private val armLL = bone("arm_ll")
    private val keys = bone("keys")
    private val links = arrayOf(bone("chain_0"), bone("chain_1"), bone("chain_2"), bone("chain_3"), bone("chain_4"), bone("hook"))
    private val fanRoot = bone("hands_fan")
    private val fan = Array(7) { bone("fan_$it") }
    private val lantern = bone("lantern")
    private val legR = bone("leg_r")
    private val legL = bone("leg_l")
    private val shinR = bone("shin_r")
    private val shinL = bone("shin_l")
    private val apron = bone("apron")
    private val drape = bone("drape")

    /** Ein Schlüsselbild der Zeitleiste: ab [at] Ticks gilt [pose] (null = Ruhe/Gang); [hard] = Schlag (beschleunigt). */
    private class Key(val at: Float, val pose: FloatArray?, val hard: Boolean = false)

    override fun setAngles(state: GuardianRenderState) {
        super.setAngles(state)
        val t = state.age
        val w = walk(state)
        val p = phase(state)
        val a = state.actionTime

        // --- Ruhe und Gang: breitbeinig stapfend, das Beil ruht auf der Schulter -------------------------------------
        val breath = sin(t * 0.08f)
        chest.pitch += breath * 0.02f
        body.originY += breath * 0.3f + abs(sin(p)) * 1.6f * w
        body.roll += sin(p) * 0.05f * w
        chest.yaw += sin(p) * 0.07f * w
        chest.roll -= sin(p) * 0.03f * w
        legs(state, legR, legL, shinR, shinL, 0.55f)
        val stepR = cos(p) * 0.55f * w
        val stepL = -stepR
        armUR.pitch += sin(p) * 0.04f * w
        armUL.pitch += sin(p) * 0.04f * w
        armLR.pitch += -cos(p) * 0.22f * w
        armLL.pitch += cos(p) * 0.18f * w
        // Die Kette schleift links nach: jedes Glied etwas später.
        for (i in links.indices) {
            links[i].pitch += w * 0.1f - cos(p - (i + 1) * 0.55f) * 0.1f * w + sin(t * 0.07f - i * 0.6f) * 0.03f
        }
        keys.roll += sin(t * 0.18f) * 0.05f + sin(p * 2f) * 0.18f * w
        keys.pitch += cos(p * 2f + 0.6f) * 0.1f * w
        apron.pitch += min(stepR, stepL) * 0.9f
        drape.pitch += 0.12f * w + max(stepR, stepL) * 0.4f + sin(t * 0.06f) * 0.03f
        fanRoot.pitch += sin(t * 0.05f) * 0.02f + abs(sin(p)) * 0.04f * w
        for (i in fan.indices) fan[i].roll += sin(t * 0.06f + i * 0.9f) * 0.015f

        capture()

        // --- Aktionen ----------------------------------------------------------------------------------------------
        var tug = 0f
        var tremble = 0f
        when (state.action) {
            WardenAction.CHOP -> {
                play(a,
                    Key(0f, null), Key(SOFT, CHOP_WINDUP), Key(T.CHOP_WINDUP.f, CHOP_WINDUP),
                    Key(T.CHOP_WINDUP.f + T.CHOP_STRIKE, CHOP_HIT, true), Key(T.CHOP_WINDUP.f + 6f, CHOP_STUCK),
                    Key(T.CHOP_STUCK_END.f, CHOP_STUCK), Key(T.CHOP_END.f, null))
                tug = window(a, T.CHOP_WINDUP.f + 6f, T.CHOP_STUCK_END.f)
                tremble = window(a, 10f, T.CHOP_WINDUP.f)
            }
            WardenAction.DOUBLE_CHOP -> {
                val first = T.CHOP_WINDUP.f
                val second = T.DOUBLE_CHOP_SECOND.f
                play(a,
                    Key(0f, null), Key(SOFT, CHOP_WINDUP), Key(first, CHOP_WINDUP),
                    Key(first + T.CHOP_STRIKE, CHOP_HIT, true), Key(second - 6f, CHOP_WINDUP), Key(second, CHOP_WINDUP),
                    Key(second + T.CHOP_STRIKE, CHOP_HIT, true), Key(second + 6f, CHOP_STUCK),
                    Key(T.DOUBLE_CHOP_STUCK_END.f, CHOP_STUCK), Key(T.DOUBLE_CHOP_END.f, null))
                tug = window(a, second + 6f, T.DOUBLE_CHOP_STUCK_END.f)
                tremble = window(a, 10f, first) + window(a, second - 6f, second)
            }
            WardenAction.CHAIN -> {
                val throwAt = T.CHAIN_WINDUP.f
                val pull = T.CHAIN_PULL.f
                play(a,
                    Key(0f, null), Key(6f, CHAIN_WINDUP), Key(throwAt, CHAIN_WINDUP),
                    Key(throwAt + 2f, CHAIN_THROW, true), Key(pull, CHAIN_THROW),
                    Key(pull + 3f, CHAIN_PULL, true), Key(pull + 5f, CHAIN_PULL), Key(T.CHAIN_END.f, null))
                // Einmal über dem Kopf herum, genau bis zum Wurf.
                links[0].yaw += step(a, 2f, throwAt) * TAU
            }
            WardenAction.HOOKED -> {
                play(a, Key(0f, null), Key(SOFT, HOOKED))
                tremble = step(a, SOFT, SOFT + 4f) * 1.4f
            }
            WardenAction.ROUND -> {
                val hit = T.ROUND_WINDUP.f
                play(a,
                    Key(0f, null), Key(7f, ROUND_WINDUP), Key(hit, ROUND_WINDUP),
                    Key(hit + T.ROUND_STRIKE, ROUND_HIT, true), Key(hit + 12f, ROUND_HIT), Key(T.ROUND_END.f, null))
                tremble = window(a, 7f, hit) * 0.6f
            }
            WardenAction.RAGE -> {
                play(a, Key(0f, null), Key(6f, RAGE), Key(T.RAGE - 6f, RAGE), Key(T.RAGE.f, null))
                tremble = window(a, 6f, T.RAGE - 6f) * 1.6f
            }
            WardenAction.STAGGER -> {
                play(a, Key(0f, null), Key(8f, STAGGER))
            }
        }

        // --- Nachschwingen über den Posen --------------------------------------------------------------------------
        if (tug > 0f) {
            // Er zerrt mit allen vier Händen am steckenden Beil: kurze Rucke nach hinten.
            val jerk = max(0f, sin(a * 0.75f)) * tug
            chest.pitch -= jerk * 0.09f
            body.originZ += jerk * 0.8f
        }
        if (tremble > 0f) {
            chest.pitch += sin(t * 2.3f) * 0.012f * tremble
            chest.yaw += sin(t * 1.9f) * 0.01f * tremble
            body.originX += sin(t * 2.7f) * 0.15f * tremble
        }
        look(head, state)
        // Die Laterne pendelt bei jedem Schritt und schwingt nach, wenn er sich bewegt.
        lantern.pitch += sin(p * 2f) * 0.12f * w + sin(t * 0.07f) * 0.04f
        lantern.roll += sin(p) * 0.15f * w + sin(t * 0.05f) * 0.03f
    }

    /** Merkt sich die Ruhe-/Ganglage aller Knochen (Ziel von `null` in der Zeitleiste). */
    private fun capture() {
        for (i in parts.indices) {
            val part = parts[i]
            base[i * 6] = part.pitch
            base[i * 6 + 1] = part.yaw
            base[i * 6 + 2] = part.roll
            base[i * 6 + 3] = part.originX - rest[i * 3]
            base[i * 6 + 4] = part.originY - rest[i * 3 + 1]
            base[i * 6 + 5] = part.originZ - rest[i * 3 + 2]
        }
    }

    /** Zeitleiste: weich zwischen zwei Schlüsselbildern, beschleunigt in ein hartes (Schlag). */
    private fun play(time: Float, vararg keys: Key) {
        if (time <= keys[0].at) return
        var i = 0
        while (i < keys.size - 1 && time >= keys[i + 1].at) i++
        if (i == keys.size - 1) {
            blend(keys[i].pose, keys[i].pose, 1f)
            return
        }
        val from = keys[i]
        val to = keys[i + 1]
        val u = ((time - from.at) / (to.at - from.at)).coerceIn(0f, 1f)
        blend(from.pose, to.pose, if (to.hard) u * u else u * u * (3f - 2f * u))
    }

    private fun blend(from: FloatArray?, to: FloatArray?, u: Float) {
        for (i in parts.indices) {
            val o = i * 6
            val fromSet = from != null && !from[o].isNaN()
            val toSet = to != null && !to[o].isNaN()
            if (!fromSet && !toSet) continue
            for (k in 0 until 6) {
                val x = if (fromSet) from!![o + k] else base[o + k]
                val y = if (toSet) to!![o + k] else base[o + k]
                mixed[o + k] = x + (y - x) * u
            }
            val part = parts[i]
            part.pitch = mixed[o]
            part.yaw = mixed[o + 1]
            part.roll = mixed[o + 2]
            part.originX = rest[i * 3] + mixed[o + 3]
            part.originY = rest[i * 3 + 1] + mixed[o + 4]
            part.originZ = rest[i * 3 + 2] + mixed[o + 5]
        }
    }

    /** 1 zwischen [from] und [to], mit kurzen weichen Rändern. */
    private fun window(t: Float, from: Float, to: Float) = step(t, from, from + 3f) * (1f - step(t, to - 3f, to))

    private companion object {
        val T = BossTimings.Warden
        val Int.f get() = toFloat()
        const val SOFT = 7f
        const val TAU = (PI * 2).toFloat()
        const val N = Float.NaN

        // POSES BEGIN (generiert aus tools/hha_assets/warden_model.py --kotlin)
        val BONES = arrayOf("body", "chest", "head", "arm_ur", "fore_ur", "axe", "arm_ul", "fore_ul", "arm_lr", "fore_lr", "keys", "arm_ll", "fore_ll", "chain_0", "chain_1", "chain_2", "chain_3", "chain_4", "hook", "hands_fan", "fan_0", "fan_1", "fan_2", "fan_3", "fan_4", "fan_5", "fan_6", "lantern", "leg_r", "shin_r", "leg_l", "shin_l", "apron", "drape")
        val PARENTS = intArrayOf(-1, 0, 1, 1, 3, 4, 1, 6, 1, 8, 9, 1, 11, 12, 13, 14, 15, 16, 17, 1, 19, 19, 19, 19, 19, 19, 19, 1, 0, 28, 0, 30, 0, 0)
        val CHOP_WINDUP = floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 2.0000f, 1.0000f, -0.3800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.5100f, 0.7450f, 0.7550f, 0.0000f, 0.0000f, 0.0000f,
            -0.2550f, 0.0000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, 3.0868f, 0.7475f, 0.9033f, 0.0000f, 0.0000f, 0.0000f,
            -2.0700f, 0.4100f, 0.2850f, 0.0000f, 0.0000f, 0.0000f, -0.1100f, 0.0000f, 0.8500f, 0.0000f, 0.0000f, 0.0000f,
            -0.3000f, 0.0000f, 1.0500f, 0.0000f, 0.0000f, 0.0000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.3000f, 0.0000f, -1.0500f, 0.0000f, 0.0000f, 0.0000f,
            -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.0158f, 0.3538f, 1.9209f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.1387f, 0.0329f, 0.0941f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, -1.5180f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.0120f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, -0.5060f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            0.0000f, 0.0000f, 0.5060f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.0120f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 1.5180f, 0.0000f, 0.0000f, 0.0000f, 0.3800f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3000f, 0.0000f, 0.2200f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f,
            0.2500f, 0.0000f, -0.2200f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.0000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f,
            -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val CHOP_HIT = floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 6.0000f, -3.0000f, 1.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.0700f, -0.6300f, 0.6850f, 0.0000f, 0.0000f, 0.0000f,
            -1.4050f, 0.0000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, -2.8500f, -0.1174f, 0.9441f, 0.0000f, 0.0000f, 0.0000f,
            -0.6950f, 1.0650f, -0.3650f, 0.0000f, 0.0000f, 0.0000f, -1.7400f, 0.0000f, 0.8500f, 0.0000f, 0.0000f, 0.0000f,
            -0.6000f, 0.0000f, 0.7000f, 0.0000f, 0.0000f, 0.0000f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.6000f, 0.0000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f,
            -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5155f, 0.8532f, 0.8314f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -1.0500f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.7500f, 0.0000f, 0.1800f, 0.0000f, 0.0000f, 0.0000f, 0.7500f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f,
            0.4500f, 0.0000f, -0.1800f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f,
            -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val CHOP_STUCK = floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 6.0000f, 1.0000f, 0.6200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.5450f, -0.4950f, 1.0200f, 0.0000f, 0.0000f, 0.0000f,
            -0.4250f, 0.0000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, -2.5343f, 0.7002f, 1.0353f, 0.0000f, 0.0000f, 0.0000f,
            -0.7400f, 0.1250f, 0.2050f, 0.0000f, 0.0000f, 0.0000f, -0.4150f, 0.0000f, 0.8500f, 0.0000f, 0.0000f, 0.0000f,
            -0.7950f, -0.7950f, 0.4200f, 0.0000f, 0.0000f, 0.0000f, -1.8600f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.6600f, 0.8600f, -0.1450f, 0.0000f, 0.0000f, 0.0000f,
            -1.7650f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 2.1337f, -0.0768f, 0.7525f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.0000f, -0.0000f, 0.2663f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.0000f, 0.0000f, 0.8157f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, -1.3110f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.8740f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, -0.4370f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            0.0000f, 0.0000f, 0.4370f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.8740f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 1.3110f, 0.0000f, 0.0000f, 0.0000f, -0.6200f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.5500f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.6000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f,
            0.4500f, 0.0000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f,
            -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val CHAIN_WINDUP = floatArrayOf(
            0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.1500f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            0.2500f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -2.6000f, 0.0000f, -0.7500f, 0.0000f, 0.0000f, 0.0000f,
            -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.9478f, -0.1291f, -2.5991f, 0.0000f, 0.0000f, 0.0000f,
            0.6592f, 0.0010f, -0.0030f, 0.0000f, 0.0000f, 0.0000f, 2.4088f, -0.4773f, -2.9353f, 0.0000f, 0.0000f, 0.0000f,
            -0.6578f, -0.0176f, -0.0341f, 0.0000f, 0.0000f, 0.0000f, -0.6464f, -0.0299f, 0.1636f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.1951f, -0.6906f, -0.1252f, 0.0000f, 0.0000f, 0.0000f,
            0.2500f, 0.0000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f,
            -0.2500f, 0.0000f, -0.1800f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val CHAIN_THROW = floatArrayOf(
            0.0000f, -0.3000f, 0.0000f, 0.0000f, 2.0000f, -2.0000f, 0.3000f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.2000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            0.4500f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -1.7500f, 0.3500f, -0.0500f, 0.0000f, 0.0000f, 0.0000f,
            -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.8717f, 1.4349f, 2.2719f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.4000f, 0.7092f, -0.2687f, 0.0000f, 0.0000f, 0.0000f,
            0.4500f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f,
            -0.7000f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.6000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f,
            -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val CHAIN_PULL = floatArrayOf(
            0.0000f, 0.3500f, 0.0000f, 0.0000f, 2.0000f, 2.0000f, -0.3000f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.2000f, 0.0000f, 0.7000f, 0.0000f, 0.0000f, 0.0000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.7500f, 0.0000f, -0.5500f, 0.0000f, 0.0000f, 0.0000f,
            -1.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3378f, 0.8777f, -0.5123f, 0.0000f, 0.0000f, 0.0000f,
            -0.0497f, 0.0350f, 0.1411f, 0.0000f, 0.0000f, 0.0000f, -0.0978f, 0.0337f, 0.1747f, 0.0000f, 0.0000f, 0.0000f,
            -0.0475f, 0.0107f, 0.1171f, 0.0000f, 0.0000f, 0.0000f, -0.0426f, 0.0044f, 0.1030f, 0.0000f, 0.0000f, 0.0000f,
            -0.0000f, -0.0000f, -0.1799f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.4888f, -0.8900f, -0.3919f, 0.0000f, 0.0000f, 0.0000f,
            -0.4500f, 0.0000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f,
            0.4000f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val HOOKED = floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 5.0000f, 6.0000f, -0.5000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.2500f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.0700f, -0.0450f, 1.0500f, 0.0000f, 0.0000f, 0.0000f,
            -1.2300f, 0.0000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, 2.5263f, -0.0761f, 2.1006f, 0.0000f, 0.0000f, 0.0000f,
            -0.9000f, 0.0000f, -0.7500f, 0.0000f, 0.0000f, 0.0000f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -1.0250f, -0.6000f, 0.0050f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.9150f, -0.1500f, 0.3500f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, -2.8901f, 1.2154f, 3.1111f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, -1.2420f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.8280f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, -0.4140f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            0.0000f, 0.0000f, 0.4140f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.8280f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 1.2420f, 0.0000f, 0.0000f, 0.0000f, 0.5048f, -0.1315f, -0.0723f, 0.0000f, 0.0000f, 0.0000f,
            0.5500f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f,
            -0.8500f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.9500f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val ROUND_WINDUP = floatArrayOf(
            0.0000f, 0.4500f, 0.0000f, 0.0000f, 7.0000f, 2.0000f, 0.2500f, 0.7500f, 0.1200f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, -0.9500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.7400f, -0.8150f, 1.5800f, 0.0000f, 0.0000f, 0.0000f,
            -1.6000f, 0.0000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, -1.5721f, 0.6289f, -2.1319f, 0.0000f, 0.0000f, 0.0000f,
            -1.0250f, 0.6250f, -0.1250f, 0.0000f, 0.0000f, 0.0000f, -0.1650f, 0.0000f, 0.8500f, 0.0000f, 0.0000f, 0.0000f,
            0.3500f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, -1.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.6000f, 0.0000f, -1.1000f, 0.0000f, 0.0000f, 0.0000f,
            -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.9580f, -0.5987f, 0.9785f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.2481f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.4552f, -1.1822f, 0.3364f, 0.0000f, 0.0000f, 0.0000f,
            -0.5500f, 0.0000f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.8500f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f,
            -0.1500f, 0.0000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.7500f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val ROUND_HIT = floatArrayOf(
            0.0000f, -0.4500f, 0.0000f, 0.0000f, 6.0000f, -2.0000f, 0.2500f, -0.8500f, -0.1000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0300f, -0.3650f, 0.3150f, 0.0000f, 0.0000f, 0.0000f,
            -1.7650f, 0.0000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, 2.3949f, 1.5293f, 0.1152f, 0.0000f, 0.0000f, 0.0000f,
            -1.3450f, 0.8000f, -0.3150f, 0.0000f, 0.0000f, 0.0000f, -0.0900f, 0.0000f, 0.8500f, 0.0000f, 0.0000f, 0.0000f,
            -0.8000f, 0.0000f, 1.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.3000f, 0.0000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f,
            -1.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.1320f, 0.1947f, 2.8361f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.2033f, -0.0977f, 0.2060f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            0.0000f, 0.0000f, -1.4490f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.9660f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, -0.4830f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            0.0000f, 0.0000f, 0.4830f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.9660f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 1.4490f, 0.0000f, 0.0000f, 0.0000f, -0.6066f, 1.2616f, -0.5171f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.0000f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.6000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f,
            -0.6500f, 0.0000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.7000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f,
            -0.5000f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.4500f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val RAGE = floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.6500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4000f, 0.0000f, 1.3500f, 0.0000f, 0.0000f, 0.0000f,
            -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.3221f, 0.9612f, -0.5841f, 0.0000f, 0.0000f, 0.0000f,
            -0.4000f, 0.0000f, -1.3500f, 0.0000f, 0.0000f, 0.0000f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.1000f, 0.0000f, 1.1500f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.1000f, 0.0000f, -1.1500f, 0.0000f, 0.0000f, 0.0000f,
            -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.2956f, 0.9403f, 2.0460f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.1722f, 0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, -1.7940f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.1960f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, -0.5980f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            0.0000f, 0.0000f, 0.5980f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.1960f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 1.7940f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 0.2800f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, -0.2800f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val STAGGER = floatArrayOf(
            0.0000f, 0.1500f, 0.0000f, 0.0000f, 15.0000f, 2.0000f, 0.7000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f,
            0.3500f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5000f, 0.0000f, 0.8500f, 0.0000f, 0.0000f, 0.0000f,
            -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.5846f, -0.2952f, 2.4541f, 32.0682f, 2.2657f, 10.7446f,
            -0.2000f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.7900f, -0.0950f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.5000f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.2300f, 0.2350f, -0.5750f, 0.0000f, 0.0000f, 0.0000f,
            -1.2000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, -0.4200f, 0.7167f, -1.3742f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.2664f, -0.0368f, -0.0926f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, -1.1730f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.7820f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, -0.3910f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            0.0000f, 0.0000f, 0.3910f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.7820f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 1.1730f, 0.0000f, 0.0000f, 0.0000f, -0.7094f, -0.1788f, 0.0211f, 0.0000f, 0.0000f, 0.0000f,
            -1.4500f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 1.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 1.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -1.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        // POSES END
    }
}
