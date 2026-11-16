package dev.henny.hha.client.model

import dev.henny.hha.entity.mob.AutomatonAction
import dev.henny.hha.entity.mob.GriffinAction
import dev.henny.hha.entity.mob.KureteAction
import dev.henny.hha.logic.boss.timings.OlympMobTimings
import net.minecraft.client.model.ModelPart
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/*
 * Die normalen Gegner des Olymp (Brief `tmp/overhaul2/models/olymp_gegner.md`): Kurete, Greif, Goldene Wächterin.
 *
 * Geometrie, Textur und die Schlüsselposen entstehen in `tools/hha_assets/kurete_model.py`, `griffin_model.py` und
 * `automaton_model.py`. Die Posentabellen unten (zwischen den Marken) schreibt `python tools/hha_assets/<name>_model.py
 * --kotlin`; es sind dieselben Winkel wie `POSES` dort. Die Grundhaltung des Modells ist die Ruhepose (Kurete, Greif)
 * bzw. die Statue (Wächterin). Jede Aktion läuft über `actionTime` selbst durch Ausholen → Schlag → Nachlauf →
 * Erholung, mit den Zeiten aus [OlympMobTimings].
 */

private val Int.f get() = toFloat()

/** Ein Schlüsselbild der Zeitleiste: ab [at] Ticks gilt [pose] (null = Ruhe/Gang), [hard] = Schlag (beschleunigt), [weight] = Anteil der Pose. */
class PoseKey(val at: Float, val pose: FloatArray?, val hard: Boolean = false, val weight: Float = 1f)

/**
 * Gemeinsame Basis der drei Modelle: Knochen aus der generierten Tabelle, Ruhe/Gang als Grundlage, Zeitleisten aus
 * Schlüsselbildern (wie beim Kerkermeister) und ein kurzes Überblenden von der vorigen Aktion in die neue.
 */
abstract class PoseTableModel(root: ModelPart, bones: Array<String>, parents: IntArray) : GuardianModel(root) {
    protected val parts: Array<ModelPart>
    private val names = bones
    private val rest: FloatArray
    private val base: FloatArray
    private val mixed: FloatArray
    private val previous: FloatArray

    init {
        val list = ArrayList<ModelPart>(bones.size)
        for (i in bones.indices) {
            val parent = parents[i]
            list.add(if (parent < 0) root.getChild(bones[i]) else list[parent].getChild(bones[i]))
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
        previous = FloatArray(parts.size * 6)
    }

    protected fun bone(name: String): ModelPart = parts[names.indexOf(name)]

    /** Ruhe, Gang und Atmen (auf der Grundhaltung). */
    protected abstract fun ambient(state: GuardianRenderState)

    /** Zeitleiste einer Aktion zur Zeit [time]; Aktion 0 lässt die Ruhe stehen. */
    protected abstract fun act(action: Int, time: Float, state: GuardianRenderState)

    /** Nachschwingen und Blick über der fertigen Pose. */
    protected open fun secondary(state: GuardianRenderState) {}

    override fun setAngles(state: GuardianRenderState) {
        super.setAngles(state)
        ambient(state)
        capture()
        val fade = state.previousAction != 0 && state.action != state.previousAction && state.actionTime < FADE
        if (fade) {
            act(state.previousAction, state.previousTime, state)
            store(previous)
            restore()
        }
        act(state.action, state.actionTime, state)
        if (fade) {
            val u = step(state.actionTime, 0f, FADE)
            for (i in parts.indices) {
                val o = i * 6
                val part = parts[i]
                part.pitch = previous[o] + (part.pitch - previous[o]) * u
                part.yaw = previous[o + 1] + (part.yaw - previous[o + 1]) * u
                part.roll = previous[o + 2] + (part.roll - previous[o + 2]) * u
                part.originX = previous[o + 3] + (part.originX - previous[o + 3]) * u
                part.originY = previous[o + 4] + (part.originY - previous[o + 4]) * u
                part.originZ = previous[o + 5] + (part.originZ - previous[o + 5]) * u
            }
        }
        secondary(state)
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

    private fun store(into: FloatArray) {
        for (i in parts.indices) {
            val part = parts[i]
            into[i * 6] = part.pitch
            into[i * 6 + 1] = part.yaw
            into[i * 6 + 2] = part.roll
            into[i * 6 + 3] = part.originX
            into[i * 6 + 4] = part.originY
            into[i * 6 + 5] = part.originZ
        }
    }

    private fun restore() = blend(null, null, 1f, 1f, 1f, force = true)

    /** Setzt eine Pose direkt (vor [capture], z. B. als Grundlage, bevor der Gang dazukommt); NaN bleibt, wie es ist. */
    protected fun apply(pose: FloatArray) = mixDirect(pose, pose, 1f, null)

    /** Mischt zwei vollständige Posen direkt, ohne die Ruhe (für [ambient]); [only] begrenzt auf Knochen-Indizes. */
    protected fun mixDirect(from: FloatArray, to: FloatArray, u: Float, only: IntArray?) {
        val count = only?.size ?: parts.size
        for (n in 0 until count) {
            val i = only?.get(n) ?: n
            val o = i * 6
            if (from[o].isNaN() || to[o].isNaN()) continue
            val part = parts[i]
            part.pitch = from[o] + (to[o] - from[o]) * u
            part.yaw = from[o + 1] + (to[o + 1] - from[o + 1]) * u
            part.roll = from[o + 2] + (to[o + 2] - from[o + 2]) * u
            part.originX = rest[i * 3] + from[o + 3] + (to[o + 3] - from[o + 3]) * u
            part.originY = rest[i * 3 + 1] + from[o + 4] + (to[o + 4] - from[o + 4]) * u
            part.originZ = rest[i * 3 + 2] + from[o + 5] + (to[o + 5] - from[o + 5]) * u
        }
    }

    /** Zeitleiste: weich zwischen zwei Schlüsselbildern, beschleunigt in ein hartes (Schlag). */
    protected fun play(time: Float, vararg keys: PoseKey) {
        if (time < keys[0].at) return
        var i = 0
        while (i < keys.size - 1 && time >= keys[i + 1].at) i++
        if (i == keys.size - 1) {
            blend(keys[i].pose, keys[i].pose, 1f, keys[i].weight, keys[i].weight)
            return
        }
        val from = keys[i]
        val to = keys[i + 1]
        val u = ((time - from.at) / (to.at - from.at)).coerceIn(0f, 1f)
        blend(from.pose, to.pose, if (to.hard) u * u else u * u * (3f - 2f * u), from.weight, to.weight)
    }

    /**
     * Mischt zwei Posen der Tabelle (NaN = Knochen bleibt in Ruhe/Gang). [only] begrenzt auf einen Knochenbereich
     * (Indizes), z. B. ein einzelnes Schwingenglied mit eigener Phase.
     */
    protected fun blend(from: FloatArray?, to: FloatArray?, u: Float, wFrom: Float = 1f, wTo: Float = 1f, only: IntArray? = null, force: Boolean = false) {
        val count = only?.size ?: parts.size
        for (n in 0 until count) {
            val i = only?.get(n) ?: n
            val o = i * 6
            val fromSet = from != null && !from[o].isNaN()
            val toSet = to != null && !to[o].isNaN()
            if (!fromSet && !toSet && !force) continue
            for (k in 0 until 6) {
                val b = base[o + k]
                val x = if (fromSet) b + (from!![o + k] - b) * wFrom else b
                val y = if (toSet) b + (to!![o + k] - b) * wTo else b
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
    protected fun window(t: Float, from: Float, to: Float, edge: Float = 3f) = step(t, from, from + edge) * (1f - step(t, to - edge, to))

    /** Kurzer Ausschlag um [at] (Gauß, Breite [width] Ticks). */
    protected fun pulse(t: Float, at: Float, width: Float = 1.2f): Float {
        val x = (t - at) / width
        return exp(-x * x)
    }

    protected companion object {
        const val FADE = 4f
        const val TAU = (PI * 2).toFloat()
    }
}

// =====================================================================================================================
// Kurete
// =====================================================================================================================

/**
 * Kurete, Himmelssoldat: Achteckschild links, Speer rechts, offener Helm mit blauem Busch.
 *
 * Knochen: body ─ chest ─ head ── plume; chest ─ arm_r ── fore_r ── spear; chest ─ arm_l ── fore_l ── shield;
 * body ─ leg_r ── shin_r; body ─ leg_l ── shin_l.
 *
 * - Ruhe: federnder Stand, wippt von Fuß zu Fuß; Gehen als Tanzschritt mit hohen Knien.
 * - CLASH (0–12): Speerschaft quer an den Schildrand, Schläge bei 3 und 8 (der Ton ist die Ansage).
 * - THRUST: Speer waagerecht zurück, Schild vor (Ausholen bis 12), Ausfallschritt und Stoß 12–14, zurück bis 26.
 * - SHIELD_BASH: Schild an die Schulter (bis 10), kurzer Schritt und Stoß 10–12, zurück bis 22.
 * - GUARD (30, Schleife): Schild hoch vor Kopf und Rumpf, Speer aufgestellt.
 */
class KureteModel(root: ModelPart) : PoseTableModel(root, BONES, PARENTS) {
    private val body = bone("body")
    private val chest = bone("chest")
    private val head = bone("head")
    private val plume = bone("plume")
    private val armR = bone("arm_r")
    private val armL = bone("arm_l")
    private val foreR = bone("fore_r")
    private val legR = bone("leg_r")
    private val legL = bone("leg_l")
    private val shinR = bone("shin_r")
    private val shinL = bone("shin_l")

    override fun ambient(state: GuardianRenderState) {
        apply(IDLE)
        val t = state.age
        val w = walk(state)
        val p = phase(state)
        val still = 1f - w
        // Federnder Stand: das Gewicht wandert langsam von einem Fuß auf den anderen.
        val sway = sin(t * 0.11f)
        body.originX += sway * 0.5f * still
        body.roll += sway * 0.035f * still
        body.originY += (0.3f + 0.3f * cos(t * 0.22f)) * still
        chest.roll -= sway * 0.03f * still
        chest.pitch += sin(t * 0.08f) * 0.02f
        legR.roll -= sway * 0.04f * still
        legL.roll -= sway * 0.04f * still
        // Tanzschritt: hohe Knie, Oberkörper dreht gegen, der Speer wippt mit.
        legR.pitch += cos(p) * 0.75f * w - max(0f, -sin(p)) * 0.45f * w
        legL.pitch += cos(p + PI.toFloat()) * 0.75f * w - max(0f, sin(p)) * 0.45f * w
        shinR.pitch += max(0f, -sin(p)) * 1.2f * w
        shinL.pitch += max(0f, sin(p)) * 1.2f * w
        body.originY -= abs(sin(p)) * 1.4f * w
        chest.yaw += sin(p) * 0.12f * w
        armR.pitch += sin(p) * 0.08f * w
        armL.pitch -= sin(p) * 0.1f * w
    }

    override fun act(action: Int, time: Float, state: GuardianRenderState) {
        val a = time
        when (action) {
            KureteAction.CLASH -> {
                play(a, PoseKey(0f, null), PoseKey(2.5f, CLASH), PoseKey(T.CLASH_END - 2f, CLASH), PoseKey(T.CLASH_END.f, null))
                // Zwei Schläge: der Speerarm holt kurz aus und schlägt auf den Schildrand.
                val knock = beat(a, T.CLASH_HIT_1.f) + beat(a, T.CLASH_HIT_2.f)
                foreR.pitch -= knock * 0.45f
                armR.roll += knock * 0.12f
            }
            KureteAction.THRUST -> play(a,
                PoseKey(0f, null), PoseKey(7f, THRUST_WINDUP), PoseKey(T.THRUST_W.f, THRUST_WINDUP),
                PoseKey(T.THRUST_HIT_END.f, THRUST_HIT, true), PoseKey(T.THRUST_HIT_END + 5f, THRUST_HIT),
                PoseKey(T.THRUST_END.f, null))
            KureteAction.SHIELD_BASH -> play(a,
                PoseKey(0f, null), PoseKey(6f, BASH_WINDUP), PoseKey(T.SHIELD_BASH_W.f, BASH_WINDUP),
                PoseKey(T.SHIELD_BASH_HIT_END.f, BASH_HIT, true), PoseKey(T.SHIELD_BASH_HIT_END + 4f, BASH_HIT),
                PoseKey(T.SHIELD_BASH_END.f, null))
            KureteAction.GUARD -> {
                play(a, PoseKey(0f, null), PoseKey(4f, GUARD))
                // Hinter dem Schild federt er leicht in den Knien.
                val bob = sin(a * 0.35f) * step(a, 4f, 8f)
                body.originY += bob * 0.4f
            }
        }
    }

    /** Ausholen kurz vor dem Schlag, harter Treffer genau bei [at], kurzes Nachfedern. */
    private fun beat(t: Float, at: Float): Float =
        if (t < at) -step(t, at - 1.5f, at) * 0.6f else pulse(t, at + 0.3f, 0.8f)

    override fun secondary(state: GuardianRenderState) {
        look(head, state)
        // Der Busch schwingt nach (zwei Komponenten, phasenversetzt).
        val t = state.age
        val w = walk(state)
        plume.pitch += sin(t * 0.13f - 0.8f) * 0.04f + sin(phase(state) * 2f - 0.6f) * 0.1f * w
        plume.roll += sin(t * 0.09f) * 0.03f
    }

    private companion object {
        val T = OlympMobTimings
        const val N = Float.NaN

        // KURETE POSES BEGIN (generiert aus tools/hha_assets/kurete_model.py --kotlin)
        val BONES = arrayOf("body", "chest", "head", "plume", "arm_r", "fore_r", "spear", "arm_l", "fore_l", "shield", "leg_r", "shin_r", "leg_l", "shin_l")
        val PARENTS = intArrayOf(-1, 0, 1, 2, 1, 4, 5, 1, 7, 8, 0, 10, 0, 12)
        val IDLE = floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4284f, 0.0000f, 0.0400f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3500f, 0.0000f, 0.1800f, 0.0000f, 0.0000f, 0.0000f, -0.7500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            2.0220f, -0.1001f, 2.9545f, 0.0000f, 0.0000f, 0.0000f, -0.1200f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f,
            -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3875f, -0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0800f, 0.0000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1200f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val CLASH = floatArrayOf(
            0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0162f, 0.0000f, -0.1000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0800f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -0.9000f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, -1.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            2.2105f, 0.4084f, -1.6371f, 0.0000f, 0.0000f, 0.0000f, -1.0000f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f,
            -0.8000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -3.1416f, -0.9881f, -3.1416f, 0.0000f, 0.0000f, 0.0000f,
            -0.2500f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.3000f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val THRUST_WINDUP = floatArrayOf(
            0.0000f, 0.4500f, 0.0000f, 0.0000f, 1.5019f, 2.0000f, -0.1200f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0500f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -1.0000f, 0.0000f, 1.2500f, 0.0000f, 0.0000f, 0.0000f, -1.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -3.0751f, 1.4302f, -0.1654f, 0.0000f, 0.0000f, 0.0000f, -1.2500f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f,
            -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 3.1416f, -1.2077f, 3.1416f, 0.0000f, 0.0000f, 0.0000f,
            0.3500f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.5500f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val THRUST_HIT = floatArrayOf(
            0.1800f, -0.2500f, 0.0000f, 0.0000f, 5.0107f, -5.0000f, 0.3000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.4500f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -1.6000f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            2.8793f, 0.3720f, 0.6359f, 0.0000f, 0.0000f, 0.0000f, -0.4000f, 0.0000f, -0.5500f, 0.0000f, 0.0000f, 0.0000f,
            -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.0951f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.7500f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -1.1500f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val BASH_WINDUP = floatArrayOf(
            0.0000f, -0.6000f, 0.0000f, 0.0000f, 0.5862f, 1.5000f, 0.0500f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.8500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            0.3500f, 0.0000f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -2.8694f, -1.1080f, 2.8622f, 0.0000f, 0.0000f, 0.0000f, -0.5500f, 0.0000f, -0.9500f, 0.0000f, 0.0000f, 0.0000f,
            -1.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 3.1416f, -0.2396f, -3.1416f, 0.0000f, 0.0000f, 0.0000f,
            0.3500f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.4000f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val BASH_HIT = floatArrayOf(
            0.1200f, 0.3000f, 0.0000f, 0.0000f, 3.7278f, -4.0000f, 0.2500f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            0.6000f, 0.0000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -1.7182f, 0.6618f, -3.0486f, 0.0000f, 0.0000f, 0.0000f, -0.5100f, -0.0300f, -0.5350f, 0.0000f, 0.0000f, 0.0000f,
            -2.5300f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 3.1416f, -0.8691f, -3.1416f, 0.0000f, 0.0000f, 0.0000f,
            0.5500f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.9500f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.8500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val GUARD = floatArrayOf(
            0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0721f, 0.5000f, 0.1200f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.2200f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -0.4000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            2.1268f, 0.0642f, 2.5583f, 0.0000f, 0.0000f, 0.0000f, -1.3650f, 0.1300f, -0.7700f, 0.0000f, 0.0000f, 0.0000f,
            -2.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -3.1416f, -0.7913f, 3.1416f, 0.0000f, 0.0000f, 0.0000f,
            -0.3000f, 0.0000f, 0.1800f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        // KURETE POSES END
    }
}

// =====================================================================================================================
// Greif
// =====================================================================================================================

/**
 * Greif: Löwenleib, Adlerkopf, zwei dreieckige Schwingen aus je drei Gliedern.
 *
 * Knochen: body ─ chest ─ neck ── head ── beak_lower; chest ─ wing_l_0 ── wing_l_1 ── wing_l_2 (ebenso rechts);
 * chest ─ fore_l ── fore_l_low (ebenso rechts); body ─ hind_l ── hind_l_low (ebenso rechts);
 * body ─ tail_0 ── tail_1 ── tail_2.
 *
 * - Ruhe/Gang auf allen vieren (Kreuzgang), Schwingen angelegt, der Schwanz pendelt.
 * - Im Flug (`state.flying`): Schwingenschlag mit [OlympMobTimings.WINGBEAT_PERIOD] Ticks, die drei Glieder je
 *   Seite phasenversetzt.
 * - PERCH (Schleife): sitzt aufrecht, Schwingen als V, der Kopf dreht ruckweise.
 * - DIVE: Schwingen ganz offen, der Kopf zielt (bis 16); Sturz mit angelegten Schwingen, Krallen voraus (16–28).
 * - LANDED (30, Schleife): liegt flach, Schwingen am Boden, schüttelt den Kopf (offen).
 * - SWIPE: richtet sich auf, rechte Kralle hoch (bis 10), Hieb quer 10–12, zurück bis 24.
 * - TAKEOFF (0–20): duckt sich, zwei kräftige Schwingenschläge.
 */
class GriffinModel(root: ModelPart) : PoseTableModel(root, BONES, PARENTS) {
    private val body = bone("body")
    private val chest = bone("chest")
    private val neck = bone("neck")
    private val head = bone("head")
    private val beak = bone("beak_lower")
    private val foreL = bone("fore_l")
    private val foreR = bone("fore_r")
    private val foreLowL = bone("fore_l_low")
    private val foreLowR = bone("fore_r_low")
    private val hindL = bone("hind_l")
    private val hindR = bone("hind_r")
    private val hindLowL = bone("hind_l_low")
    private val hindLowR = bone("hind_r_low")
    private val tail = arrayOf(bone("tail_0"), bone("tail_1"), bone("tail_2"))
    private val wingL = arrayOf(bone("wing_l_0"), bone("wing_l_1"), bone("wing_l_2"))
    private val wingR = arrayOf(bone("wing_r_0"), bone("wing_r_1"), bone("wing_r_2"))

    /** Indizes der Schwingenglieder je Stufe (0 innen … 2 außen), beide Seiten. */
    private val wingSeg = Array(3) { k -> intArrayOf(BONES.indexOf("wing_l_$k"), BONES.indexOf("wing_r_$k")) }

    override fun ambient(state: GuardianRenderState) {
        apply(IDLE)
        val t = state.age
        val w = walk(state)
        val p = phase(state)
        val breath = sin(t * 0.07f)
        chest.pitch += breath * 0.015f
        neck.pitch += breath * 0.02f
        // Kreuzgang: links vorn mit rechts hinten.
        foreL.pitch += cos(p) * 0.5f * w
        hindR.pitch += cos(p) * 0.45f * w
        foreR.pitch += cos(p + PI.toFloat()) * 0.5f * w
        hindL.pitch += cos(p + PI.toFloat()) * 0.45f * w
        foreLowL.pitch -= max(0f, sin(p)) * 0.6f * w
        foreLowR.pitch -= max(0f, -sin(p)) * 0.6f * w
        hindLowL.pitch += max(0f, -sin(p)) * 0.5f * w
        hindLowR.pitch += max(0f, sin(p)) * 0.5f * w
        body.originY -= abs(sin(p)) * 0.8f * w
        body.roll += sin(p) * 0.03f * w
        neck.pitch += sin(p * 2f) * 0.04f * w
        // Der Löwenschwanz pendelt langsam, jedes Glied später.
        for (i in tail.indices) tail[i].yaw += sin(t * 0.09f - i * 0.7f) * (0.12f + 0.05f * i) + sin(p - i * 0.6f) * 0.1f * w
        if (state.flying) fly(t)
    }

    /** Flug: Pose zwischen FLY_UP und FLY_DOWN, je Schwingenglied später (16 Ticks Periode). */
    private fun fly(t: Float) {
        apply(FLY_UP)
        val period = OlympMobTimings.WINGBEAT_PERIOD.toFloat()
        for (k in 0 until 3) {
            val u = 0.5f - 0.5f * cos((t / period) * TAU - k * 0.7f)
            mixDirect(FLY_UP, FLY_DOWN, u, wingSeg[k])
        }
        body.originY += sin((t / period) * TAU - 0.4f) * 1.2f
    }

    override fun act(action: Int, time: Float, state: GuardianRenderState) {
        val a = time
        when (action) {
            GriffinAction.PERCH -> {
                play(a, PoseKey(0f, null), PoseKey(6f, PERCH))
                // Der Kopf dreht ruckweise: 2 Ticks drehen, dann halten.
                val slot = floor(a / 22f)
                val from = LOOK[(slot.toInt() + LOOK.size - 1) % LOOK.size]
                val to = LOOK[slot.toInt() % LOOK.size]
                val u = step(a - slot * 22f, 0f, 2f)
                head.yaw += from + (to - from) * u
            }
            GriffinAction.DIVE -> play(a,
                PoseKey(0f, null), PoseKey(8f, DIVE_WINDUP), PoseKey(T.DIVE_W.f, DIVE_WINDUP),
                PoseKey(T.DIVE_W + 3f, DIVE_FALL, true), PoseKey(T.DIVE_END.f, DIVE_FALL))
            GriffinAction.LANDED -> {
                play(a, PoseKey(0f, DIVE_FALL), PoseKey(3f, LANDED, true))
                // Benommen: schüttelt den Kopf, erst heftig, dann schwächer.
                val shake = sin(a * 1.3f) * (0.35f * exp(-a / 14f) + 0.08f) * step(a, 3f, 5f)
                head.yaw += shake
                head.roll += shake * 0.4f
                beak.pitch += 0.15f * step(a, 3f, 6f)
            }
            GriffinAction.SWIPE -> play(a,
                PoseKey(0f, null), PoseKey(7f, SWIPE_WINDUP), PoseKey(T.SWIPE_W.f, SWIPE_WINDUP),
                PoseKey(T.SWIPE_HIT_END.f, SWIPE_HIT, true), PoseKey(T.SWIPE_HIT_END + 4f, SWIPE_HIT),
                PoseKey(T.SWIPE_END.f, null))
            GriffinAction.TAKEOFF -> play(a,
                PoseKey(0f, null), PoseKey(5f, TAKEOFF), PoseKey(9f, FLY_DOWN, true), PoseKey(12.5f, FLY_UP),
                PoseKey(16.5f, FLY_DOWN, true), PoseKey(T.TAKEOFF_END.f, FLY_UP))
        }
    }

    override fun secondary(state: GuardianRenderState) {
        if (state.action != GriffinAction.LANDED && state.action != GriffinAction.PERCH) look(head, state)
    }

    private companion object {
        val T = OlympMobTimings
        const val N = Float.NaN

        /** Blickrichtungen auf der Säule (Gieren in Radiant), ruckweise nacheinander. */
        val LOOK = floatArrayOf(0f, 0.55f, 0.15f, -0.5f, -0.2f, 0.35f)

        // GRIFFIN POSES BEGIN (generiert aus tools/hha_assets/griffin_model.py --kotlin)
        val BONES = arrayOf("body", "chest", "neck", "head", "beak_lower", "wing_l_0", "wing_l_1", "wing_l_2", "wing_r_0", "wing_r_1", "wing_r_2", "fore_l", "fore_l_low", "fore_r", "fore_r_low", "hind_l", "hind_l_low", "hind_r", "hind_r_low", "tail_0", "tail_1", "tail_2")
        val PARENTS = intArrayOf(-1, 0, 1, 2, 3, 1, 5, 6, 1, 8, 9, 1, 11, 1, 13, 0, 15, 0, 17, 0, 19, 20)
        val IDLE = floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0348f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.5502f, -1.2146f, -0.6327f, 0.0000f, 0.0000f, 0.0000f,
            -0.0308f, -0.2357f, -0.3731f, 0.0000f, 0.0000f, 0.0000f, -0.0099f, -0.1349f, -0.2666f, 0.0000f, 0.0000f, 0.0000f,
            -1.5502f, 1.2146f, 0.6327f, 0.0000f, 0.0000f, 0.0000f, -0.0308f, 0.2357f, 0.3731f, 0.0000f, 0.0000f, 0.0000f,
            -0.0099f, 0.1349f, 0.2666f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val PERCH = floatArrayOf(
            -0.5500f, 0.0000f, 0.0000f, 0.0000f, -3.6987f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0041f, 0.1978f, -0.9184f, 0.0000f, 0.0000f, 0.0000f,
            -0.0691f, -0.3868f, -0.0469f, 0.0000f, 0.0000f, 0.0000f, -0.3187f, -0.8205f, -0.1795f, 0.0000f, 0.0000f, 0.0000f,
            0.0041f, -0.1978f, 0.9184f, 0.0000f, 0.0000f, 0.0000f, -0.0691f, 0.3868f, 0.0469f, 0.0000f, 0.0000f, 0.0000f,
            -0.3187f, 0.8205f, 0.1795f, 0.0000f, 0.0000f, 0.0000f, 0.6200f, 0.0000f, -0.0400f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.6200f, 0.0000f, 0.0400f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.8500f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f,
            1.9500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.8500f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f,
            1.9500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.9500f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.2500f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val DIVE_WINDUP = floatArrayOf(
            0.2000f, 0.0000f, 0.0000f, 0.0000f, -2.2527f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2981f, -0.0723f, -0.1073f, 0.0000f, 0.0000f, 0.0000f,
            0.0022f, -0.1056f, 0.0589f, 0.0000f, 0.0000f, 0.0000f, -0.0417f, -0.1997f, 0.1051f, 0.0000f, 0.0000f, 0.0000f,
            -0.2981f, 0.0723f, 0.1073f, 0.0000f, 0.0000f, 0.0000f, 0.0022f, 0.1056f, -0.0589f, 0.0000f, 0.0000f, 0.0000f,
            -0.0417f, 0.1997f, -0.1051f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val DIVE_FALL = floatArrayOf(
            0.4500f, 0.0000f, 0.0000f, 0.0000f, -6.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.7980f, -1.1596f, 0.6516f, 0.0000f, 0.0000f, 0.0000f,
            -0.0357f, -0.0533f, -0.2873f, 0.0000f, 0.0000f, 0.0000f, 0.0541f, -0.0527f, -0.2045f, 0.0000f, 0.0000f, 0.0000f,
            -2.7980f, 1.1596f, -0.6516f, 0.0000f, 0.0000f, 0.0000f, -0.0357f, 0.0533f, 0.2873f, 0.0000f, 0.0000f, 0.0000f,
            0.0541f, 0.0527f, 0.2045f, 0.0000f, 0.0000f, 0.0000f, -1.5000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f,
            -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.5000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f,
            -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val LANDED = floatArrayOf(
            0.0400f, 0.0000f, 0.0600f, 0.0000f, 9.2313f, 0.0000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.3000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f,
            0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2480f, -0.1724f, 0.2031f, 0.0000f, 0.0000f, 0.0000f,
            -0.0608f, -0.1397f, 0.0678f, 0.0000f, 0.0000f, 0.0000f, -0.0588f, -0.1885f, -0.0852f, 0.0000f, 0.0000f, 0.0000f,
            -0.2448f, 0.1608f, -0.3219f, 0.0000f, 0.0000f, 0.0000f, -0.0608f, 0.1397f, -0.0678f, 0.0000f, 0.0000f, 0.0000f,
            -0.0588f, 0.1885f, 0.0852f, 0.0000f, 0.0000f, 0.0000f, -1.4500f, 0.2500f, -0.2000f, 0.0000f, 0.0000f, 0.0000f,
            0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.4000f, -0.2000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f,
            0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.3500f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.4000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val SWIPE_WINDUP = floatArrayOf(
            -0.8500f, 0.2500f, 0.0000f, 0.0000f, -9.8449f, 0.0000f, -0.1500f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.6000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5698f, 0.3088f, -0.9478f, 0.0000f, 0.0000f, 0.0000f,
            -0.0691f, -0.3868f, -0.0469f, 0.0000f, 0.0000f, 0.0000f, -0.3187f, -0.8205f, -0.1795f, 0.0000f, 0.0000f, 0.0000f,
            0.0052f, -0.7557f, 0.7428f, 0.0000f, 0.0000f, 0.0000f, -0.0691f, 0.3868f, 0.0469f, 0.0000f, 0.0000f, 0.0000f,
            -0.3187f, 0.8205f, 0.1795f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f,
            -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.4000f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f,
            -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f,
            1.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f,
            1.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val SWIPE_HIT = floatArrayOf(
            -0.3000f, -0.3500f, 0.0000f, 0.0000f, -5.5323f, 0.0000f, 0.1500f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.3000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4645f, 0.4346f, -0.6624f, 0.0000f, 0.0000f, 0.0000f,
            -0.1597f, -0.4768f, 0.1747f, 0.0000f, 0.0000f, 0.0000f, -0.7446f, -0.7728f, -0.0802f, 0.0000f, 0.0000f, 0.0000f,
            0.3469f, 0.5934f, 0.7705f, 0.0000f, 0.0000f, 0.0000f, -0.1597f, 0.4768f, -0.1747f, 0.0000f, 0.0000f, 0.0000f,
            -0.7446f, 0.7728f, 0.0802f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.0000f, -0.4000f, -0.5500f, 0.0000f, 0.0000f, 0.0000f,
            -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f,
            0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f,
            0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.2000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val TAKEOFF = floatArrayOf(
            0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.8918f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2231f, -0.2270f, -1.1403f, 0.0000f, 0.0000f, 0.0000f,
            -0.0935f, -0.1892f, -0.1276f, 0.0000f, 0.0000f, 0.0000f, -0.1452f, -0.1639f, -0.1038f, 0.0000f, 0.0000f, 0.0000f,
            0.2231f, 0.2270f, 1.1403f, 0.0000f, 0.0000f, 0.0000f, -0.0935f, 0.1892f, 0.1276f, 0.0000f, 0.0000f, 0.0000f,
            -0.1452f, 0.1639f, 0.1038f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -1.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -1.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            1.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            1.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val FLY_UP = floatArrayOf(
            0.1500f, 0.0000f, 0.0000f, 0.0000f, -4.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2014f, -0.2724f, -1.1350f, 0.0000f, 0.0000f, 0.0000f,
            -0.0935f, -0.1892f, -0.1276f, 0.0000f, 0.0000f, 0.0000f, -0.1452f, -0.1639f, -0.1038f, 0.0000f, 0.0000f, 0.0000f,
            0.2014f, 0.2724f, 1.1350f, 0.0000f, 0.0000f, 0.0000f, -0.0935f, 0.1892f, 0.1276f, 0.0000f, 0.0000f, 0.0000f,
            -0.1452f, 0.1639f, 0.1038f, 0.0000f, 0.0000f, 0.0000f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            1.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            1.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val FLY_DOWN = floatArrayOf(
            0.1500f, 0.0000f, 0.0000f, 0.0000f, -4.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1317f, 0.0721f, 0.4981f, 0.0000f, 0.0000f, 0.0000f,
            0.1520f, -0.1239f, 0.3413f, 0.0000f, 0.0000f, 0.0000f, 0.1928f, -0.1455f, 0.3601f, 0.0000f, 0.0000f, 0.0000f,
            -0.1317f, -0.0721f, -0.4981f, 0.0000f, 0.0000f, 0.0000f, 0.1520f, 0.1239f, -0.3413f, 0.0000f, 0.0000f, 0.0000f,
            0.1928f, 0.1455f, -0.3601f, 0.0000f, 0.0000f, 0.0000f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            1.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            1.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        // GRIFFIN POSES END
    }
}

// =====================================================================================================================
// Goldene Wächterin
// =====================================================================================================================

/**
 * Goldene Wächterin, Automat des Hephaistos: kannelierter Säulenrock (sie gleitet), Hammerarm rechts, Maske ohne Mund,
 * Ventilrad auf dem Rücken. Sie bewegt sich mechanisch: Übergänge in 2 Ticks, dann Halten, keine weiche
 * Sekundärbewegung.
 *
 * Knochen: body (Rock) ─ chest ─ head; chest ─ arm_r ── fore_r ── hammer; chest ─ arm_l ── fore_l; chest ─ valve.
 *
 * - STATUE bzw. nicht wach: völlig reglos, die Grundhaltung des Modells.
 * - WAKE (0–20): Kopf ruckt zum Spieler (0–2), Arme lösen sich in zwei Rucken (8–10, 14–16), dann wach.
 * - Wach (keine Aktion): gleitet ohne Auf und Ab, Oberkörper leicht vorgeneigt, das Ventil dreht langsam.
 * - HAMMER_ARM: Arm in zwei Rucken senkrecht hoch, Rumpf 40° aufgedreht (bis 18), Schlag 18–20, der Arm steckt bis
 *   38 (zerrt in Rucken), dann zurück.
 * - STEAM: vorgebeugt, Hand an der Brust, Kopf in den Nacken (bis 14), Stoß 14–20 (Rumpf ruckt zurück), zurück bis 34.
 */
class AutomatonModel(root: ModelPart) : PoseTableModel(root, BONES, PARENTS) {
    private val chest = bone("chest")
    private val head = bone("head")
    private val valve = bone("valve")

    override fun ambient(state: GuardianRenderState) {
        if (!state.awake || state.action == AutomatonAction.STATUE) return
        apply(IDLE)
        // Gleiten: kein Auf und Ab, nur ein leichtes Vorneigen mit der Geschwindigkeit.
        chest.pitch += walk(state) * 0.1f
        valve.roll += state.age * 0.035f
    }

    override fun act(action: Int, time: Float, state: GuardianRenderState) {
        if (!state.awake && action != AutomatonAction.WAKE) return
        val a = time
        when (action) {
            AutomatonAction.WAKE -> play(a,
                PoseKey(0f, STATUE), PoseKey(2f, WAKE_HEAD, true), PoseKey(8f, WAKE_HEAD), PoseKey(10f, WAKE_HALF, true),
                PoseKey(14f, WAKE_HALF), PoseKey(16f, WAKE, true), PoseKey(T.WAKE_END - 2f, WAKE), PoseKey(T.WAKE_END.f, null))
            AutomatonAction.HAMMER_ARM -> {
                val hit = T.HAMMER_W.f
                play(a,
                    PoseKey(0f, null), PoseKey(2f, HAMMER_WINDUP, weight = 0.5f), PoseKey(7f, HAMMER_WINDUP, weight = 0.5f),
                    PoseKey(9f, HAMMER_WINDUP), PoseKey(hit, HAMMER_WINDUP), PoseKey(T.HAMMER_HIT_END.f, HAMMER_HIT, true),
                    PoseKey(T.HAMMER_END - 2f, HAMMER_HIT), PoseKey(T.HAMMER_END.f, null))
                // Der Arm steckt: sie zerrt in kurzen Rucken daran.
                val stuck = window(a, T.HAMMER_HIT_END + 3f, T.HAMMER_END - 2f, 1f)
                val jerk = if ((a.toInt() / 4) % 2 == 0) 1f else 0f
                chest.pitch -= stuck * jerk * 0.05f
                chest.originZ += stuck * jerk * 0.6f
            }
            AutomatonAction.STEAM -> play(a,
                PoseKey(0f, null), PoseKey(2f, STEAM_WINDUP, weight = 0.5f), PoseKey(6f, STEAM_WINDUP, weight = 0.5f),
                PoseKey(8f, STEAM_WINDUP), PoseKey(T.STEAM_W.f, STEAM_WINDUP), PoseKey(T.STEAM_W + 2f, STEAM_HIT, true),
                PoseKey(T.STEAM_HIT_END.f, STEAM_HIT), PoseKey(T.STEAM_HIT_END + 2f, STEAM_HIT, weight = 0.5f),
                PoseKey(T.STEAM_END - 2f, STEAM_HIT, weight = 0.5f), PoseKey(T.STEAM_END.f, null))
        }
    }

    override fun secondary(state: GuardianRenderState) {
        if (!state.awake || state.action == AutomatonAction.STATUE || state.action == AutomatonAction.WAKE) return
        // Der Kopf folgt dem Ziel in Rucken (auf 0,15 Radiant gerastet), nicht weich.
        val yaw = state.relativeHeadYaw * DEG
        head.yaw += Math.round(yaw / 0.15f) * 0.15f
    }

    private companion object {
        val T = OlympMobTimings
        const val N = Float.NaN

        // AUTOMATON POSES BEGIN (generiert aus tools/hha_assets/automaton_model.py --kotlin)
        val BONES = arrayOf("body", "chest", "head", "arm_r", "fore_r", "hammer", "arm_l", "fore_l", "valve")
        val PARENTS = intArrayOf(-1, 0, 1, 1, 3, 4, 1, 6, 1)
        val STATUE = floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val IDLE = floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1200f, 0.0000f, 0.2200f, 0.0000f, 0.0000f, 0.0000f,
            -0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.2000f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, -0.6500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val WAKE_HEAD = floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val WAKE_HALF = floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0250f, 0.0000f, 0.2600f, 0.0000f, 0.0000f, 0.0000f,
            -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0500f, 0.0000f, -0.2900f, 0.0000f, 0.0000f, 0.0000f, -0.3250f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val WAKE = floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, 0.4200f, 0.0000f, 0.0000f, 0.0000f,
            -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.1000f, 0.0000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val HAMMER_WINDUP = floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0500f, -0.6500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -3.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f,
            -0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.4000f, 0.0000f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val HAMMER_HIT = floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.2500f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -1.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.2100f, -0.0050f, -0.1400f, 0.0000f, 0.0000f, 0.0000f,
            -0.1150f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0730f, 0.1647f, 0.0609f, 0.0000f, 0.0000f, 0.0000f,
            0.5000f, 0.0000f, -0.7500f, 0.0000f, 0.0000f, 0.0000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val STEAM_WINDUP = floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.7500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f,
            -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.7500f, -0.5500f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, -1.8500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val STEAM_HIT = floatArrayOf(
            -0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.7000f, 0.0000f, 0.0000f, 0.0000f,
            -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0500f, 0.0000f, -0.7500f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        // AUTOMATON POSES END
    }
}
