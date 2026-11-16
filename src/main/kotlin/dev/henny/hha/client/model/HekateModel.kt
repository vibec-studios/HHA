package dev.henny.hha.client.model

import dev.henny.hha.entity.boss.olymp.HekateAction
import dev.henny.hha.logic.boss.timings.HekateTimings
import net.minecraft.client.MinecraftClient
import net.minecraft.client.model.ModelPart
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.Vec3d
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Hekate, die Dreigestaltige (Brief `tmp/overhaul2/models/hekate.md`): schwebende Säule mit sechseckigem Kopf und drei
 * Gesichtern unter dem Polos, zwei Fackeln gekreuzt vor der Brust, Schlüssel und Dolch am Gürtel, Peplos als
 * dreistufige Glocke ohne Beine. Geometrie, Textur und Schlüsselposen kommen aus `tools/hha_assets/hekate_model.py`.
 *
 * Die Schlüsselposen stehen als Tabelle unten (generiert mit `python tools/hha_assets/hekate_model.py --kotlin`,
 * dieselben Winkel wie `POSES` dort). Die Ruhe ist die Grundhaltung des Modells; Schweben, Saum-Pendeln, Schleier und
 * Flammen kommen als Zusatz darüber. Jede Aktion läuft über `actionTime` selbst durch Ausholen → Schlag → Nachlauf →
 * Erholung, mit den Zeiten aus [HekateTimings]. Ein Wechsel mitten in einer Bewegung blendet über [FADE] Ticks über.
 *
 * Der Kopf zeigt das Gesicht der Phase (`state.phase`: 0 Fackel, 1 Schlüssel, 2 Dolch) und dreht in [TURN] Ticks
 * dorthin; in der Dreigestalt das Gesicht der Gestalt (`state.form`). Die Schleier drehen gegen, damit sie hinten
 * bleiben. Das Trugbild (`state.echo`) unterscheidet sich nur durch die Textur, die der Renderer wählt.
 */
class HekateModel(root: ModelPart) : GuardianModel(root) {
    private val parts: Array<ModelPart>
    private val rest: FloatArray
    private val base: FloatArray
    private val mixed: FloatArray
    private val fadeFrom: FloatArray

    init {
        parts = Array(BONES.size) { i ->
            var p = root
            for (name in BONES[i].split('/')) p = p.getChild(name)
            p
        }
        rest = FloatArray(parts.size * 3)
        for (i in parts.indices) {
            rest[i * 3] = parts[i].originX
            rest[i * 3 + 1] = parts[i].originY
            rest[i * 3 + 2] = parts[i].originZ
        }
        base = FloatArray(parts.size * 6)
        mixed = FloatArray(parts.size * 6)
        fadeFrom = FloatArray(parts.size * 6)
    }

    private fun bone(name: String) = parts[BONES.indexOfFirst { it == name || it.endsWith("/$name") }]

    private val body = bone("body")
    private val chest = bone("chest")
    private val head = bone("head")
    private val veils = arrayOf(bone("veil_l_0"), bone("veil_r_0"))
    private val veilsLow = arrayOf(bone("veil_l_1"), bone("veil_r_1"))
    private val armR = bone("arm_r")
    private val foreR = bone("fore_r")
    private val torchR = bone("torch_r")
    private val armL = bone("arm_l")
    private val torchL = bone("torch_l")
    private val skirt = arrayOf(bone("skirt_0"), bone("skirt_1"), bone("skirt_2"))
    private val flameR = torchR.getChild("torch_r_flame")
    private val flameL = torchL.getChild("torch_l_flame")
    private val mouths = arrayOf(
        head.getChild("mouth_0"),
        head.getChild("head_b").getChild("mouth_1"),
        head.getChild("head_c").getChild("mouth_2"),
    )

    /** Ein Schlüsselbild der Zeitleiste: ab [at] Ticks gilt [pose] (null = Ruhe); [hard] = Schlag (beschleunigt). */
    private class Key(val at: Float, val pose: FloatArray?, val hard: Boolean = false)

    /** Geglätteter Kopf je Wesen: von welchem Gesicht zu welchem und seit wann. */
    private class Memory {
        var face = -1
        var from = 0f
        var to = 0f
        var since = 0f
        var seen = 0L
    }

    private val memories = HashMap<Int, Memory>()
    private var lastWorldId = 0

    override fun setAngles(state: GuardianRenderState) {
        super.setAngles(state)
        val t = state.age
        val w = walk(state)
        val a = state.actionTime
        val act = state.action

        body.visible = true
        torchR.visible = true
        torchL.visible = true
        for (m in mouths) m.visible = false

        // --- Schlüsselposen, beim Wechsel mitten in einer Bewegung weich übergeblendet ----------------------------------
        capture()
        val fade = if (state.previousAction != act && state.previousAction != 0) 1f - step(a, 0f, FADE) else 0f
        if (fade > 0f) {
            timeline(state.previousAction, state.previousTime, state.form)
            snapshot(fadeFrom)
            restore()
        }
        timeline(act, a, state.form)
        if (fade > 0f) mixFrom(fadeFrom, fade)

        // --- Schweben: langsames Auf und Ab (± 2, 60 Ticks), beim Gleiten nach vorn geneigt, Saum schleift nach ------
        val grounded = if (act == HekateAction.STAGGER) window(a, 6f, T.STAGGER_END - 4f) else 0f
        val hover = 1f - grounded
        body.originY += sin(t * TAU / 60f) * 2f * hover
        body.pitch += 0.12f * w * hover
        body.roll += sin(t * 0.045f) * 0.012f * hover
        chest.pitch += sin(t * TAU / 60f + 0.8f) * 0.015f
        for ((i, s) in skirt.withIndex()) {
            val lag = i * 0.7f
            s.pitch += (sin(t * 0.1f - lag) * 0.025f - 0.1f * w * (i + 1) / 3f) * hover
            s.roll += sin(t * 0.07f - lag) * 0.02f * hover
        }
        for ((i, v) in veils.withIndex()) {
            val side = if (i == 0) 1f else -1f
            v.pitch += 0.05f * w + sin(t * 0.06f + i) * 0.02f
            v.roll += side * sin(t * 0.05f + i * 1.3f) * 0.015f
            veilsLow[i].pitch += 0.15f * w + sin(t * 0.06f + i - 0.9f) * 0.04f
        }
        flicker(flameR, t, 0f)
        flicker(flameL, t, 2.1f)

        // --- Kopf: Gesicht der Phase bzw. der Gestalt, in der Dreigestalt einmal ganz herum ---------------------------
        val triform = act == HekateAction.TRIFORM && a < TRIFORM_END
        val face = if (triform) state.form.coerceIn(0, 2) else state.phase.coerceIn(0, 2)
        var turn = faceYaw(state.entityId, face, t)
        if (triform) turn += TAU * step(a, 4f, T.TRIFORM_HIT - 2f)
        head.yaw += turn + state.relativeHeadYaw * DEG * 0.5f
        head.pitch += state.pitch * DEG * 0.4f
        // Die Schleier hängen am Kopf, sollen aber hinten bleiben: um dieselbe Achse zurückdrehen.
        for (v in veils) v.yaw -= turn + state.relativeHeadYaw * DEG * 0.5f

        // --- Sichtbarkeit ----------------------------------------------------------------------------------------------
        when (act) {
            HekateAction.TORCH_THROW -> torchL.visible = a < T.THROW_HIT + 1f || a >= T.THROW_END
            HekateAction.STEP -> body.visible = a < T.STEP_VANISH || a >= T.STEP_APPEAR
            HekateAction.HOWL -> {
                val open = a >= T.HOWL_HIT && a < T.HOWL_END - 4f
                for (m in mouths) m.visible = open
            }
        }
    }

    /** Spielt die Zeitleiste der Aktion [act] zur Zeit [a] (setzt die Knochen absolut, Basis = [base]). */
    private fun timeline(act: Int, a: Float, form: Int) {
        when (act) {
            HekateAction.TORCH_SWEEP -> {
                val h1 = T.SWEEP_HIT.f
                val h2 = T.SWEEP_HIT2.f
                play(a,
                    Key(0f, null), Key(SOFT, SWEEP_WINDUP_R), Key(h1, SWEEP_WINDUP_R),
                    Key(T.SWEEP_HIT_END.f, SWEEP_HIT_R, true), Key(h1 + 5f, SWEEP_HIT_R),
                    Key(h2 - 3f, SWEEP_WINDUP_L), Key(h2, SWEEP_WINDUP_L),
                    Key(T.SWEEP_HIT2_END.f, SWEEP_HIT_L, true), Key(T.SWEEP_END - 10f, SWEEP_HIT_L),
                    Key(T.SWEEP_END.f, null))
            }
            HekateAction.TORCH_THROW -> {
                play(a,
                    Key(0f, null), Key(8f, THROW_WINDUP), Key(T.THROW_HIT.f, THROW_WINDUP),
                    Key(T.THROW_HIT_END.f, THROW_EMPTY, true), Key(T.THROW_END - 8f, THROW_EMPTY),
                    Key(T.THROW_END.f, null))
            }
            HekateAction.PATH -> {
                play(a,
                    Key(0f, null), Key(8f, PATH_WINDUP), Key(T.PATH_HIT.f, PATH_WINDUP),
                    Key(T.PATH_HIT_END.f, PATH_HIT, true), Key(T.PATH_END - 8f, PATH_HIT),
                    Key(T.PATH_END.f, null))
            }
            HekateAction.KEY_TURN -> {
                play(a,
                    Key(0f, null), Key(T.KEY_RAISE.f, KEY_RAISE), Key(T.KEY_HIT.f, KEY_RAISE),
                    Key(T.KEY_HIT_END.f, KEY_TURN, true), Key(T.KEY_END - 8f, KEY_TURN),
                    Key(T.KEY_END.f, null))
            }
            HekateAction.STEP -> {
                // Gewand wickelt sich hoch, sie verschwindet (Sichtbarkeit), erscheint mit vorgestrecktem Dolch und stößt.
                play(a,
                    Key(0f, null), Key(T.STEP_VANISH - 2f, STEP_FOLD), Key(T.STEP_APPEAR.f, STEP_FOLD),
                    Key(T.STEP_APPEAR + 6f, STEP_DRAW), Key(T.STEP_HIT.f, STEP_DRAW),
                    Key(T.STEP_HIT_END.f, STEP_LUNGE, true), Key(T.STEP_HIT_END + T.STEP_F - 8f, STEP_LUNGE),
                    Key(T.STEP_HIT_END + T.STEP_F.f, null))
            }
            HekateAction.TRIFORM -> {
                val strike = when (form) {
                    1 -> TRIFORM_KEY
                    2 -> TRIFORM_DAGGER
                    else -> TRIFORM_TORCH
                }
                play(a,
                    Key(0f, null), Key(SOFT, TRIFORM_WINDUP), Key(T.TRIFORM_HIT.f, TRIFORM_WINDUP),
                    Key(T.TRIFORM_HIT_END.f, strike, true), Key(TRIFORM_END - 10f, strike),
                    Key(TRIFORM_END, null))
            }
            HekateAction.ECLIPSE -> {
                play(a,
                    Key(0f, null), Key(12f, ECLIPSE), Key(T.ECLIPSE_HIT.f, ECLIPSE),
                    Key(T.ECLIPSE_HIT_END.f, null, true))
            }
            HekateAction.STAGGER -> {
                play(a, Key(0f, null), Key(8f, STAGGER), Key(T.STAGGER_END - 8f, STAGGER), Key(T.STAGGER_END.f, null))
            }
            HekateAction.HOWL -> {
                play(a,
                    Key(0f, null), Key(8f, HOWL_WINDUP), Key(T.HOWL_HIT.f, HOWL_WINDUP),
                    Key(T.HOWL_HIT_END.f, HOWL_HIT, true), Key(T.HOWL_END - 8f, HOWL_HIT),
                    Key(T.HOWL_END.f, null))
            }
            HekateAction.YIELD -> play(a, Key(0f, null), Key(20f, YIELD))
        }
    }

    /** Flamme: unruhiges Strecken und Wiegen (Leuchtmaske bleibt gleich, nur die Form lebt). */
    private fun flicker(flame: ModelPart, t: Float, seed: Float) {
        flame.yScale = 1f + 0.09f * sin(t * 0.9f + seed) + 0.05f * sin(t * 2.3f + seed * 1.7f)
        flame.xScale = 1f - 0.05f * sin(t * 1.1f + seed)
        flame.zScale = flame.xScale
        flame.roll += sin(t * 0.7f + seed) * 0.06f
        flame.pitch += sin(t * 0.55f + seed * 0.6f) * 0.05f
    }

    /** Gesicht [face] (0, 1, 2) als Kopfdrehung; dreht in [TURN] Ticks vom letzten Gesicht dorthin (immer vorwärts). */
    private fun faceYaw(id: Int, face: Int, t: Float): Float {
        val clientWorld = MinecraftClient.getInstance().world
        val worldId = System.identityHashCode(clientWorld)
        if (worldId != lastWorldId) {
            memories.clear()
            lastWorldId = worldId
        }
        val now = clientWorld?.time ?: 0L
        memories.entries.removeIf { now < it.value.seen || now - it.value.seen > 200L }
        val m = memories.getOrPut(id) { Memory() }
        m.seen = now
        val target = face * THIRD
        if (m.face < 0 || t < m.since) {
            m.face = face
            m.from = target
            m.to = target
            m.since = t
        } else if (face != m.face) {
            val current = m.from + (m.to - m.from) * step(t, m.since, m.since + TURN)
            var goal = target
            while (goal < current - 1e-3f) goal += TAU
            m.face = face
            m.from = current
            m.to = goal
            m.since = t
        }
        val yaw = m.from + (m.to - m.from) * step(t, m.since, m.since + TURN)
        return yaw % TAU
    }

    /**
     * Anker für `BossGlow` (ein Dauerlicht: Flamme der rechten Fackel). [matrices] steht im Modellraum (wie in den
     * Feature-Renderern). Liefert null, wenn die Fackel nicht zu sehen ist oder das Wesen ein Trugbild ist (erloschen).
     */
    fun flameAnchor(matrices: MatrixStack, cam: Vec3d, state: GuardianRenderState): Vec3d? {
        if (state.echo || !body.visible || !torchR.visible) return null
        val scratch = Vector3f()
        matrices.push()
        for (part in listOf(body, chest, armR, foreR, torchR, flameR)) part.applyTransform(matrices)
        matrices.peek().positionMatrix.transformPosition(FLAME_X / 16f, FLAME_Y / 16f, FLAME_Z / 16f, scratch)
        matrices.pop()
        return Vec3d(scratch.x + cam.x, scratch.y + cam.y, scratch.z + cam.z)
    }

    /** Merkt sich die Ruhelage aller Tabellen-Knochen (Ziel von `null` in der Zeitleiste). */
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

    private fun snapshot(into: FloatArray) {
        for (i in parts.indices) {
            val part = parts[i]
            into[i * 6] = part.pitch
            into[i * 6 + 1] = part.yaw
            into[i * 6 + 2] = part.roll
            into[i * 6 + 3] = part.originX - rest[i * 3]
            into[i * 6 + 4] = part.originY - rest[i * 3 + 1]
            into[i * 6 + 5] = part.originZ - rest[i * 3 + 2]
        }
    }

    private fun restore() = apply(base, 1f, base)

    /** Mischt die aktuelle Lage mit [from] (Gewicht [k] für [from]). */
    private fun mixFrom(from: FloatArray, k: Float) {
        snapshot(mixed)
        apply(from, k, mixed)
    }

    /** Setzt alle Knochen auf `to + (from - to) * k`. */
    private fun apply(from: FloatArray, k: Float, to: FloatArray) {
        for (i in parts.indices) {
            val o = i * 6
            val part = parts[i]
            part.pitch = to[o] + (from[o] - to[o]) * k
            part.yaw = to[o + 1] + (from[o + 1] - to[o + 1]) * k
            part.roll = to[o + 2] + (from[o + 2] - to[o + 2]) * k
            part.originX = rest[i * 3] + to[o + 3] + (from[o + 3] - to[o + 3]) * k
            part.originY = rest[i * 3 + 1] + to[o + 4] + (from[o + 4] - to[o + 4]) * k
            part.originZ = rest[i * 3 + 2] + to[o + 5] + (from[o + 5] - to[o + 5]) * k
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
        val T = HekateTimings
        val Int.f get() = toFloat()
        const val SOFT = 7f
        const val FADE = 6f
        const val TURN = 20f
        const val TAU = (PI * 2).toFloat()
        const val THIRD = (PI * 2 / 3).toFloat()
        const val N = Float.NaN

        /** Nach dem Treffer der Dreigestalt: halten und zurück (Zeiten-Datei, 22 Ticks nach dem Treffer). */
        val TRIFORM_END = HekateTimings.TRIFORM_END.toFloat()

        /** Mitte der Flamme im Raum von `torch_r_flame` (Ursprung am Fackelkopf, Flamme 9 hoch nach -y). */
        const val FLAME_X = 0f
        const val FLAME_Y = -4.5f
        const val FLAME_Z = 0f

        // POSES BEGIN (generiert aus tools/hha_assets/hekate_model.py --kotlin)
        val BONES = arrayOf("body", "body/chest", "body/chest/head", "body/chest/head/veil_l_0", "body/chest/head/veil_l_0/veil_l_1", "body/chest/head/veil_r_0", "body/chest/head/veil_r_0/veil_r_1", "body/chest/arm_r", "body/chest/arm_r/fore_r", "body/chest/arm_r/fore_r/torch_r", "body/chest/arm_l", "body/chest/arm_l/fore_l", "body/chest/arm_l/fore_l/torch_l", "body/chest/key", "body/chest/dagger", "body/skirt_0", "body/skirt_0/skirt_1", "body/skirt_0/skirt_1/skirt_2", "body/skirt_0/skirt_1/skirt_2/skirt_2_p0", "body/skirt_0/skirt_1/skirt_2/skirt_2_p1", "body/skirt_0/skirt_1/skirt_2/skirt_2_p2", "body/skirt_0/skirt_1/skirt_2/skirt_2_p3", "body/skirt_0/skirt_1/skirt_2/skirt_2_p4", "body/skirt_0/skirt_1/skirt_2/skirt_2_p5")
        val SWEEP_WINDUP_R = floatArrayOf(
            0.0000f, 0.2500f, 0.0000f, 0.0000f, -1.0000f, 1.0000f, -0.0800f, 0.5500f, -0.0600f, 0.0000f, 0.0000f, 0.0000f,
            0.0500f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.6450f, 0.2950f, -0.3700f, 0.0000f, 0.0000f, 0.0000f,
            -2.1950f, 0.6300f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5672f, -1.3177f, 2.5736f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0400f, -0.1200f, 0.0200f, 0.0000f, 0.0000f, 0.0000f, 0.0600f, -0.1400f, 0.0300f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
        )
        val SWEEP_HIT_R = floatArrayOf(
            0.0800f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, -1.5000f, 0.1800f, -0.6000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.5100f, -0.3400f, 0.5350f, 0.0000f, 0.0000f, 0.0000f,
            -2.0700f, 0.2350f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.2203f, 0.9816f, 2.4720f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.0000f, 0.1400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0500f, 0.1600f, -0.0300f, 0.0000f, 0.0000f, 0.0000f, -0.0800f, 0.1800f, -0.0500f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
        )
        val SWEEP_WINDUP_L = floatArrayOf(
            0.0000f, -0.2500f, 0.0000f, 0.0000f, -1.0000f, 1.0000f, -0.0800f, -0.5500f, 0.0600f, 0.0000f, 0.0000f, 0.0000f,
            0.0500f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.6450f, -0.2050f, 0.3200f, 0.0000f, 0.0000f, 0.0000f, -2.1950f, -0.7400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.5449f, 1.3085f, -2.5763f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0400f, 0.1200f, -0.0200f, 0.0000f, 0.0000f, 0.0000f, 0.0600f, 0.1400f, -0.0300f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
        )
        val SWEEP_HIT_L = floatArrayOf(
            0.0800f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, -1.5000f, 0.1800f, 0.6000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.5300f, 0.4450f, -0.5850f, 0.0000f, 0.0000f, 0.0000f, -2.0700f, -0.3550f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -1.2250f, -0.9891f, -2.4573f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.0000f, -0.1400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0500f, -0.1600f, 0.0300f, 0.0000f, 0.0000f, 0.0000f, -0.0800f, -0.1800f, 0.0500f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
        )
        val THROW_WINDUP = floatArrayOf(
            -0.1200f, -0.1500f, 0.0000f, 0.0000f, -2.0000f, 2.0000f, -0.2800f, -0.2000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
            -0.1500f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -2.1800f, -0.1050f, 0.0700f, 0.0000f, 0.0000f, 0.0000f, -1.1350f, 0.4800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.2691f, 0.4312f, 2.3399f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
        )
        val THROW_EMPTY = floatArrayOf(
            0.1400f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, -2.0000f, 0.3000f, 0.2500f, -0.0600f, 0.0000f, 0.0000f, 0.0000f,
            0.0500f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            0.1350f, 0.3300f, -0.5100f, 0.0000f, 0.0000f, 0.0000f, -2.2800f, -0.3550f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.0400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
        )
        val PATH_WINDUP = floatArrayOf(
            -0.0600f, 0.0000f, 0.0000f, 0.0000f, -3.0000f, 1.0000f, -0.1600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -1.9950f, 0.7750f, 0.2450f, 0.0000f, 0.0000f, 0.0000f,
            -1.4450f, -0.4800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.8575f, -0.5646f, -1.9837f, 0.0000f, 0.0000f, 0.0000f,
            -1.9250f, -0.8250f, -0.3550f, 0.0000f, 0.0000f, 0.0000f, -1.4450f, 0.4100f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.8815f, 0.5723f, 1.9535f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.0300f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
        )
        val PATH_HIT = floatArrayOf(
            0.1200f, 0.0000f, 0.0000f, 0.0000f, 3.0000f, -1.5000f, 0.4200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.1800f, -0.4100f, 0.5500f, 0.0000f, 0.0000f, 0.0000f,
            -2.0100f, -0.1650f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.7275f, 0.1272f, 0.7787f, 0.0000f, 0.0000f, 0.0000f,
            -0.1850f, 0.4500f, -0.5600f, 0.0000f, 0.0000f, 0.0000f, -2.0100f, 0.1250f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -1.7263f, -0.1277f, -0.7805f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3914f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3914f, 1.0472f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3914f, 2.0944f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3914f, 3.1416f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3914f, 4.1888f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3914f, 5.2360f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val KEY_RAISE = floatArrayOf(
            -0.0400f, 0.0000f, 0.0000f, 0.0000f, -2.0000f, 0.0000f, -0.1000f, 0.1000f, 0.0400f, 0.0000f, 0.0000f, 0.0000f,
            -0.3000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -2.5800f, 0.1200f, -0.2950f, 0.0000f, 0.0000f, 0.0000f,
            -1.0700f, -1.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.1547f, -0.2379f, -1.6925f, 20.0478f, -25.2429f, -25.7335f,
            0.0950f, 0.6800f, -0.3450f, 0.0000f, 0.0000f, 0.0000f, -2.1300f, 0.0450f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            1.0035f, -0.3723f, 2.4551f, 0.0000f, 0.0000f, 0.0000f, -2.9972f, -0.0954f, -0.0500f, -14.9574f, -40.2645f, -5.9814f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
        )
        val KEY_TURN = floatArrayOf(
            -0.0400f, 1.5708f, 0.0000f, 0.0000f, -2.0000f, 0.0000f, -0.1000f, 0.1000f, 0.0400f, 0.0000f, 0.0000f, 0.0000f,
            -0.3000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -2.5800f, 0.1200f, -0.2950f, 0.0000f, 0.0000f, 0.0000f,
            -1.0700f, -1.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.1547f, -0.2379f, -1.6925f, 20.0478f, -25.2429f, -25.7335f,
            0.0950f, 0.6800f, -0.3450f, 0.0000f, 0.0000f, 0.0000f, -2.1300f, 0.0450f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            1.0035f, -0.3723f, 2.4551f, 0.0000f, 0.0000f, 0.0000f, -2.9972f, -0.0954f, -0.0500f, -14.9574f, -40.2645f, -5.9814f,
            N, N, N, N, N, N, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, -0.1800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
        )
        val STEP_FOLD = floatArrayOf(
            0.0000f, 0.3000f, 0.0000f, 0.0000f, -6.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.9400f, -0.9300f, 0.5100f, 0.0000f, 0.0000f, 0.0000f,
            -1.8950f, -0.3950f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.8588f, 0.5937f, -2.2151f, 0.0000f, 0.0000f, 0.0000f,
            -0.5750f, 1.0900f, -0.3850f, 0.0000f, 0.0000f, 0.0000f, -2.1150f, -0.1450f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            1.1998f, -0.8785f, 2.0400f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.6000f, 0.0000f, 0.0000f, -9.0000f, 0.0000f, 0.0000f, 0.7000f, 0.0000f, 0.0000f, -9.5000f, 0.0000f,
            -0.0414f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0414f, 1.0472f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0414f, 2.0944f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0414f, 3.1416f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0414f, 4.1888f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0414f, 5.2360f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val STEP_DRAW = floatArrayOf(
            0.0200f, 0.3000f, 0.0000f, 0.0000f, -1.0000f, 2.0000f, 0.0500f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0500f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.2900f, -0.3900f, 0.6900f, 0.0000f, 0.0000f, 0.0000f,
            -2.1650f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.2154f, -0.3412f, -2.7592f, 24.5780f, 2.1591f, -11.1135f,
            0.9250f, 0.9350f, -0.5450f, 0.0000f, 0.0000f, 0.0000f, -2.2000f, 0.8700f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            2.3627f, 1.1871f, 1.6157f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -1.5308f, 0.6984f, -3.0906f, 3.1037f, -6.4259f, -7.4481f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0300f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
        )
        val STEP_LUNGE = floatArrayOf(
            0.1800f, -0.1500f, 0.0000f, 0.0000f, 1.0000f, -4.0000f, 0.3200f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.3700f, 0.3500f, 1.3900f, 0.0000f, 0.0000f, 0.0000f,
            -2.1650f, 0.1300f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4811f, -0.2712f, -1.8441f, 17.2437f, 4.6575f, -40.6802f,
            0.9650f, 1.4400f, -0.5800f, 0.0000f, 0.0000f, 0.0000f, -2.0150f, 0.3400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -2.8737f, 0.7890f, 2.5623f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -1.1178f, -0.3672f, 2.9833f, -2.4033f, -20.8756f, -9.1158f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.2200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
        )
        val TRIFORM_WINDUP = floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.8550f, -0.2550f, 1.4850f, 0.0000f, 0.0000f, 0.0000f,
            -1.8950f, 0.0750f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.1324f, -0.0158f, 0.1298f, 0.0000f, 0.0000f, 0.0000f,
            0.9850f, 0.4600f, -0.9500f, 0.0000f, 0.0000f, 0.0000f, -1.8950f, -0.6950f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -2.1373f, 0.1313f, 0.0131f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.3414f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3414f, 1.0472f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3414f, 2.0944f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3414f, 3.1416f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3414f, 4.1888f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3414f, 5.2360f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val TRIFORM_TORCH = floatArrayOf(
            0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.0000f, 0.2200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.9800f, 0.1100f, 0.9950f, 0.0000f, 0.0000f, 0.0000f,
            -1.8550f, -0.0450f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.2059f, -0.4934f, -1.5276f, 0.0000f, 0.0000f, 0.0000f,
            -0.9800f, -0.0800f, -1.0000f, 0.0000f, 0.0000f, 0.0000f, -1.8550f, 0.0100f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -1.1863f, 0.4928f, 1.5567f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.0400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
        )
        val TRIFORM_KEY = floatArrayOf(
            0.1200f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, -2.0000f, 0.2200f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -1.3850f, -0.2800f, 0.1400f, 0.0000f, 0.0000f, 0.0000f,
            -1.1650f, -0.6050f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.0076f, 0.0263f, -2.7595f, 33.2917f, -14.3962f, -25.5513f,
            0.2800f, 1.9400f, -0.4200f, 0.0000f, 0.0000f, 0.0000f, -1.8200f, 1.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            2.6405f, 1.3257f, 0.5143f, 0.0000f, 0.0000f, 0.0000f, -1.2180f, 0.2891f, -3.0622f, -3.8809f, -20.6596f, -16.7477f,
            N, N, N, N, N, N, -0.0400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
        )
        val TRIFORM_DAGGER = floatArrayOf(
            0.2000f, 0.0000f, 0.0000f, 0.0000f, 2.0000f, -2.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.5000f, -0.4450f, 0.6650f, 0.0000f, 0.0000f, 0.0000f,
            -1.7950f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.8533f, 0.0550f, -2.0746f, 28.3217f, 2.8764f, -15.0841f,
            1.1150f, 0.5300f, -0.9100f, 0.0000f, 0.0000f, 0.0000f, -2.0100f, 0.7700f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -2.4055f, 0.3870f, -2.8785f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -2.2427f, -0.0000f, 3.1416f, 7.6409f, -5.4201f, -11.3423f, -0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3614f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3614f, 1.0472f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3614f, 2.0944f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3614f, 3.1416f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3614f, 4.1888f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3614f, 5.2360f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val ECLIPSE = floatArrayOf(
            -0.0500f, 0.0000f, 0.0000f, 0.0000f, -8.0000f, 0.0000f, -0.1400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.2350f, 0.6250f, 0.5300f, 0.0000f, 0.0000f, 0.0000f,
            -0.5400f, -0.9300f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.6314f, 0.0826f, 0.2880f, 0.0000f, 0.0000f, 0.0000f,
            0.1550f, -0.0850f, -0.5050f, 0.0000f, 0.0000f, 0.0000f, -0.5650f, 0.7450f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -2.4962f, -0.4104f, -0.4164f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.0200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.1814f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1814f, 1.0472f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.1814f, 2.0944f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1814f, 3.1416f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.1814f, 4.1888f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1814f, 5.2360f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val STAGGER = floatArrayOf(
            0.1000f, 0.1200f, 0.0600f, 0.0000f, 8.0000f, 0.0000f, 0.5000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f,
            0.5500f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.0050f, -0.6850f, 0.8500f, 0.0000f, 0.0000f, 0.0000f,
            -1.6850f, -0.0400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.1164f, -0.2693f, 1.2166f, 0.0000f, 0.0000f, 0.0000f,
            0.6750f, 0.2500f, -0.3800f, 0.0000f, 0.0000f, 0.0000f, -1.9700f, -0.1450f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -2.4729f, 0.4809f, -1.0912f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.0000f, 0.0000f, N, N, N, N, N, N,
            -0.6914f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.6914f, 1.0472f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.6914f, 2.0944f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.6914f, 3.1416f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.6914f, 4.1888f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.6914f, 5.2360f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val HOWL_WINDUP = floatArrayOf(
            0.0800f, 0.0000f, 0.0000f, 0.0000f, 1.5000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.0750f, -0.6350f, 0.3600f, 0.0000f, 0.0000f, 0.0000f,
            -1.9850f, -0.1800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.9393f, -0.3429f, 0.9349f, 0.0000f, 0.0000f, 0.0000f,
            -0.1650f, 0.8500f, -0.6400f, 0.0000f, 0.0000f, 0.0000f, -1.9700f, 0.0400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -1.8000f, 0.2699f, -1.0485f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.1914f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1914f, 1.0472f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.1914f, 2.0944f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1914f, 3.1416f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.1914f, 4.1888f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1914f, 5.2360f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val HOWL_HIT = floatArrayOf(
            -0.0800f, 0.0000f, 0.0000f, 0.0000f, -3.0000f, 1.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -1.7150f, 0.9600f, 0.5000f, 0.0000f, 0.0000f, 0.0000f,
            -1.4450f, -0.4350f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.0629f, -0.7475f, -1.8349f, 0.0000f, 0.0000f, 0.0000f,
            -1.7000f, -0.9550f, -0.5150f, 0.0000f, 0.0000f, 0.0000f, -1.4450f, 0.4200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -1.0530f, 0.7454f, 1.8479f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.4414f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4414f, 1.0472f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.4414f, 2.0944f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4414f, 3.1416f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.4414f, 4.1888f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4414f, 5.2360f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
        )
        val YIELD = floatArrayOf(
            0.0800f, -0.3500f, 0.0000f, 6.0000f, 1.0000f, 0.0000f, 0.3200f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.3000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.0150f, -0.0550f, 1.2950f, 0.0000f, 0.0000f, 0.0000f,
            -1.9950f, 0.2450f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5136f, -0.9907f, -1.8310f, 18.0477f, -5.1262f, -24.2693f,
            0.3800f, 0.9450f, -0.1100f, 0.0000f, 0.0000f, 0.0000f, -2.0850f, 0.0450f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            1.9475f, 0.0057f, 2.3708f, 0.0000f, 0.0000f, 0.0000f, -1.2645f, -0.1799f, 3.0519f, -29.9870f, -14.3535f, -10.8258f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
        )
        // POSES END
    }
}
