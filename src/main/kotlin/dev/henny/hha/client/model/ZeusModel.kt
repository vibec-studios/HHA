package dev.henny.hha.client.model

import dev.henny.hha.client.anim.PoseMemory
import dev.henny.hha.client.anim.Spring
import dev.henny.hha.client.cine.CinePlayer
import dev.henny.hha.entity.boss.olymp.ZeusAction
import dev.henny.hha.logic.boss.timings.ZeusTimings
import net.minecraft.client.model.ModelPart
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.MathHelper
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Eine Schlüsselpose: je Knochen (pitch, yaw, roll, dx, dy, dz), siehe [ZeusModel.Keys]. */
class ZeusKey(val v: FloatArray, val hidden: IntArray) {
    companion object {
        fun of(data: String, hidden: IntArray) = ZeusKey(data.split(',').map { it.toFloat() }.toFloatArray(), hidden)
    }
}

/**
 * Zeus, König des Himmels (Brief `tmp/overhaul2/models/zeus.md`). Geometrie, Textur und alle Schlüsselposen stammen
 * aus `tools/hha_assets/zeus_model.py`; der Block [Keys] wird dort erzeugt (`python tools/hha_assets/zeus_model.py
 * --kotlin`), damit Vorschau und Spiel dieselben Winkel benutzen. Hier steht nur der Ablauf: welche Pose wann, mit
 * welcher Hüllkurve, plus Atmen, Gehen und Nachschwingen (Mantel, Rock, Bart, Kranz, Waagschalen).
 *
 * Er hat es nicht eilig: weich ins Ausholen (8–12 Ticks), halten bis zur Trefferzeit aus [ZeusTimings], dann ein harter
 * Schlag mit dem ganzen Körper (2 Ticks), Nachlauf, Erholung. Aktion und Zeit kommen über [GuardianRenderState.action]
 * und [GuardianRenderState.actionTime]; die Bewegung davor blendet aus ihrer Stellung beim Wechsel herüber.
 */
class ZeusModel(private val rootPart: ModelPart) : GuardianModel(rootPart) {

    private val parts: Array<ModelPart> = Array(Keys.COUNT) { i ->
        Keys.BONES[i].split('/').fold(rootPart) { p, n -> p.getChild(n) }
    }
    private fun part(i: Int) = parts[i]
    private val body = part(Keys.BODY)
    private val chest = part(Keys.CHEST)
    private val head = part(Keys.HEAD)
    private val armR = part(Keys.ARM_R)
    private val armL = part(Keys.ARM_L)
    private val scales = part(Keys.SCALES)
    private val keraunos: ModelPart = Keys.KERAUNOS_PATH.split('/').fold(rootPart) { p, n -> p.getChild(n) }

    // --- Hüllkurven ---------------------------------------------------------------------------------------------

    private fun smooth(t: Float) = t * t * (3f - 2f * t)
    private fun easeIn(t: Float) = t * t
    private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t)

    private fun mix(a: FloatArray, b: FloatArray, t: Float): FloatArray = FloatArray(a.size) { a[it] + (b[it] - a[it]) * t }

    /** Zeitleiste: hält die zuletzt erreichte Pose und blendet in den Abschnitten [t0, t1] zur nächsten über. */
    private class Track(var cur: FloatArray, private val t: Float) {
        var out: FloatArray? = null
        fun to(t0: Float, t1: Float, target: ZeusKey, ease: (Float) -> Float): Track {
            if (out != null) return this
            when {
                t >= t1 -> cur = target.v
                t > t0 -> out = FloatArray(cur.size) { cur[it] + (target.v[it] - cur[it]) * ease((t - t0) / (t1 - t0)) }
                else -> out = cur
            }
            return this
        }
        fun get(): FloatArray = out ?: cur
    }

    private fun track(start: FloatArray, t: Float) = Track(start, t)

    /** Puls für einen Wurf-Ruck bei der Waage: 0 → 1 in 3 Ticks, 3 Ticks halten, in 8 Ticks zurück. */
    private fun pulse(t: Float, at: Float): Float = when {
        t < at || t > at + 14f -> 0f
        t < at + 3f -> easeOut((t - at) / 3f)
        t < at + 6f -> 1f
        else -> 1f - smooth((t - at - 6f) / 8f)
    }

    /** Der Ablauf jeder Aktion ab ihrem Beginn (t = actionTime). [from] ist die Haltung, aus der er kommt. */
    private fun evaluate(action: Int, t: Float, from: FloatArray): FloatArray {
        val z = ZeusTimings
        val s: (Float) -> Float = ::smooth
        val i: (Float) -> Float = ::easeIn
        val o: (Float) -> Float = ::easeOut
        return when (action) {
            ZeusAction.THROW -> track(from, t).to(0f, 8f, Keys.THROW_WINDUP, s)
                .to(z.THROW_HIT.toFloat(), z.THROW_HIT_END.toFloat(), Keys.THROW_RELEASE, i)
                .to(z.THROW_END.toFloat(), z.THROW_END + 8f, Keys.EMPTY, s).get()
            ZeusAction.EMPTY -> track(from, t).to(0f, 8f, Keys.EMPTY, s).get()
            ZeusAction.BASH -> track(from, t).to(0f, 8f, Keys.BASH_WINDUP, s)
                .to(z.BASH_HIT.toFloat(), z.BASH_HIT_END.toFloat(), Keys.BASH_HIT, i)
                .to(z.BASH_HIT_END + 4f, z.BASH_END.toFloat(), Keys.IDLE, s).get()
            ZeusAction.SLAM -> track(from, t).to(0f, 10f, Keys.SLAM_WINDUP, s)
                .to(z.SLAM_HIT.toFloat(), z.SLAM_HIT_END.toFloat(), Keys.SLAM_HIT, i)
                // Folgt kein STUCK (z. B. in einer Kette), richtet er sich wieder auf.
                .to(z.SLAM_HIT_END + 14f, z.SLAM_HIT_END + 26f, Keys.IDLE, s).get()
            ZeusAction.STUCK -> track(from, t).to(0f, 6f, Keys.STUCK, o).get()
            ZeusAction.CALL -> track(from, t).to(0f, 8f, Keys.CALL_WINDUP, s)
                .to(z.CALL_HIT.toFloat(), z.CALL_HIT_END.toFloat(), Keys.CALL_HIT, i)
                .to(z.CALL_HIT_END + 4f, z.CALL_END.toFloat(), Keys.IDLE, s).get()
            ZeusAction.STEP -> {
                val appear = z.STEP_APPEAR.toFloat()
                if (t < appear) track(from, t).to(0f, 8f, Keys.STEP_CROUCH, s).get()
                else track(Keys.STEP_ARRIVE.v, t).to(appear + 6f, appear + 18f, Keys.IDLE, s).get()
            }
            ZeusAction.GAZE -> track(from, t).to(0f, z.GAZE_RAISE.toFloat(), Keys.GAZE_WINDUP, s)
                .to(z.GAZE_HIT.toFloat(), z.GAZE_HIT_END.toFloat(), Keys.GAZE_HIT, i)
                .to(z.GAZE_HIT_END + 8f, z.GAZE_END.toFloat(), Keys.IDLE, s).get()
            ZeusAction.SCALES -> {
                val end = z.SCALES_END.toFloat()
                val base = track(from, t).to(0f, z.SCALES_RAISE.toFloat(), Keys.SCALES_HOLD, s).to(end, end + 12f, Keys.IDLE, s).get()
                val k = if (t >= end) 0f else max(
                    max(pulse(t, z.SCALES_CAST_1.toFloat()), pulse(t, z.SCALES_CAST_2.toFloat())),
                    max(pulse(t, z.SCALES_CAST_3.toFloat()), pulse(t, z.SCALES_CAST_4.toFloat())),
                )
                if (k > 0f) mix(base, Keys.SCALES_CAST.v, k) else base
            }
            ZeusAction.STAGGER -> track(from, t).to(0f, 10f, Keys.STAGGER, i)
                .to(z.STAGGER_END.toFloat(), z.STAGGER_END + 14f, Keys.IDLE, s).get()
            ZeusAction.GUARD_UP -> track(from, t).to(0f, z.GUARD_RAISE.toFloat(), Keys.GUARD_WINDUP, s)
                .to(z.GUARD_HIT.toFloat(), z.GUARD_HIT_END.toFloat(), Keys.GUARD_HIT, i)
                .to(z.GUARD_END.toFloat(), z.GUARD_END + 10f, Keys.IDLE, s).get()
            // Auftritt: beginnt sitzend (auf dem Thron) und steht in 40 Ticks auf; der Mantel fällt dabei.
            ZeusAction.RISE -> track(Keys.RISE.v, t).to(4f, z.RISE_END.toFloat(), Keys.IDLE, s).get()
            ZeusAction.PHASE -> track(from, t).to(0f, 12f, Keys.PHASE, s).to(30f, z.PHASE_END.toFloat(), Keys.IDLE, s).get()
            ZeusAction.YIELD -> track(from, t).to(0f, 20f, Keys.YIELD, s).get()
            // Niederlage: Er zeigt mit dem gesenkten Blitz nach unten (Szene `zeus.defeat`).
            ZeusAction.POINT -> track(from, t).to(0f, POINT_RAISE, POINT_KEY, s).get()
            else -> track(from, t).to(0f, 10f, Keys.IDLE, s).get()
        }
    }

    /** Ist der Blitz in der Hand? Nicht nach dem Wurf und nicht mit leerer Hand. */
    private fun keraunosShown(action: Int, t: Float) = when (action) {
        ZeusAction.THROW -> t < ZeusTimings.THROW_HIT
        ZeusAction.EMPTY -> false
        else -> true
    }

    /**
     * Zustand je Wesen (Überarbeitung 3): zuletzt gezeigte Pose ([PoseMemory]) und die Federn für Mantel, Bart, Kranz
     * und Keraunos. Nach `entityId`; Wesen, die [FORGET_NANOS] lang nicht gezeichnet wurden, werden vergessen.
     */
    private class Anim {
        val memory = PoseMemory(Keys.COUNT * 6)
        val shown = FloatArray(Keys.COUNT * 6)
        val from = FloatArray(Keys.COUNT * 6)
        var hasShown = false
        var action = Int.MIN_VALUE
        var lastT = 0f
        var lastAge = Float.NaN
        var yaw = 0f
        var lastBodyYaw = Float.NaN
        var seen = 0L
        val mantle = Spring(MANTLE_FREQ, MANTLE_DAMP)
        val mantleTurn = Spring(TURN_FREQ, TURN_DAMP)
        val beard = Spring(BEARD_FREQ, BEARD_DAMP)
        val crown = Spring(CROWN_FREQ, CROWN_DAMP)
        val bolt = Spring(BOLT_FREQ, BOLT_DAMP)
    }

    private val anims = HashMap<Int, Anim>()
    private var lastPrune = 0L

    private fun animOf(id: Int): Anim {
        val now = System.nanoTime()
        if (now - lastPrune > FORGET_NANOS) {
            lastPrune = now
            anims.values.removeIf { now - it.seen > FORGET_NANOS }
        }
        return anims.getOrPut(id) { Anim() }.also { it.seen = now }
    }

    override fun setAngles(state: GuardianRenderState) {
        super.setAngles(state)
        val action = state.action
        val t = state.actionTime
        val age = state.age
        val anim = animOf(state.entityId)
        // Überblenden aus der zuletzt gezeigten Pose: Beim Wechsel (oder Neustart derselben Aktion) beginnt die Bahn der
        // neuen Aktion dort, wo das Bild gerade stand – nicht bei der vorigen Aktion „bei ihrem Alter“.
        val restarted = action == anim.action && t + 0.5f < anim.lastT
        if (action != anim.action || restarted) {
            if (anim.hasShown) anim.shown.copyInto(anim.from) else Keys.IDLE.v.copyInto(anim.from)
        }
        val crossedFrom = if (action == anim.action && !restarted) anim.lastT else -1f
        anim.action = action
        anim.lastT = t
        val v = anim.memory.blend(action, evaluate(action, t, anim.from), age, BLEND_TICKS, restarted)
        v.copyInto(anim.shown)
        anim.hasShown = true
        val dt = if (anim.lastAge.isNaN()) 0f else (age - anim.lastAge).coerceIn(0f, 4f)
        anim.lastAge = age
        for (b in 0 until Keys.COUNT) {
            val p = parts[b]
            val o = b * 6
            p.pitch = v[o]
            p.yaw = v[o + 1]
            p.roll = v[o + 2]
            p.originX += v[o + 3]
            p.originY += v[o + 4]
            p.originZ += v[o + 5]
        }

        // Sichtbarkeit: Blitz, Waage und beim Donnerschritt der ganze Körper.
        keraunos.visible = keraunosShown(action, t) &&
            !(state.previousAction == ZeusAction.THROW && action == ZeusAction.NONE && t < 2f)
        scales.visible = action == ZeusAction.SCALES && t >= 4f && t < ZeusTimings.SCALES_END + 4f
        rootPart.visible = !(action == ZeusAction.STEP && t >= ZeusTimings.STEP_VANISH && t < ZeusTimings.STEP_APPEAR)

        // Atmen: langsam, die Brust hebt sich, der Kopf gleicht aus.
        val breath = sin(age * 0.055f)
        chest.pitch += breath * 0.018f
        body.originY += breath * 0.3f
        head.pitch -= breath * 0.01f

        // Gehen: schreitend und langsam; nur in Ruhe und mit leerer Hand voll, sonst gedämpft.
        val calm = action == ZeusAction.NONE || action == ZeusAction.EMPTY || action == ZeusAction.YIELD
        val w = walk(state) * if (calm) 1f else 0.35f
        val ph = phase(state)
        if (w > 0.01f) {
            val stride = sin(ph)
            part(Keys.LEG_R).pitch += stride * 0.5f * w
            part(Keys.LEG_L).pitch -= stride * 0.5f * w
            part(Keys.SHIN_R).pitch += max(0f, -sin(ph - 0.6f)) * 0.7f * w
            part(Keys.SHIN_L).pitch += max(0f, sin(ph - 0.6f)) * 0.7f * w
            body.originY += abs(stride) * 1.2f * w - 0.6f * w
            body.yaw += stride * 0.06f * w
            chest.yaw -= stride * 0.08f * w
            armL.pitch += stride * 0.12f * w
            // Der Rock folgt dem vorderen Bein.
            part(Keys.SKIRT_F).pitch -= abs(stride) * 0.32f * w
            part(Keys.SKIRT_B).pitch += abs(stride) * 0.22f * w
        }
        // Kopf folgt dem Blick, aber nur in Ruhe und gedämpft (er schaut nicht herum, er blickt herab).
        if (calm) {
            head.yaw += MathHelper.clamp(state.relativeHeadYaw * DEG * 0.5f, -0.4f, 0.4f)
            head.pitch += MathHelper.clamp(state.pitch * DEG * 0.4f, -0.25f, 0.3f)
        }

        // Spannung beim Halten des Ausholens: kaum sichtbares Zittern in Armen und Brust.
        val holding = when (action) {
            ZeusAction.THROW -> t > 8f && t < ZeusTimings.THROW_HIT
            ZeusAction.BASH -> t > 8f && t < ZeusTimings.BASH_HIT
            ZeusAction.SLAM -> t > 10f && t < ZeusTimings.SLAM_HIT
            ZeusAction.CALL -> t > 8f && t < ZeusTimings.CALL_HIT
            ZeusAction.GAZE -> t > ZeusTimings.GAZE_RAISE && t < ZeusTimings.GAZE_HIT
            else -> false
        }
        if (holding) {
            val shake = sin(age * 2.1f) * 0.01f
            armR.roll += shake
            armL.roll -= shake
            chest.pitch += sin(age * 1.6f) * 0.005f
        }

        when (action) {
            ZeusAction.STUCK -> {
                // Er zerrt am Blitz: alle 12 Ticks ein Ruck nach hinten.
                val tug = max(0f, sin(t * 0.52f)).let { it * it * it }
                chest.pitch -= tug * 0.1f
                armR.pitch += tug * 0.08f
                armL.pitch += tug * 0.08f
                body.originZ += tug * 1.2f
            }
            ZeusAction.EMPTY -> part(Keys.HAND_R).roll += sin(age * 0.18f) * 0.08f
            ZeusAction.STAGGER -> {
                chest.roll += sin(t * 0.08f) * 0.04f
                head.roll += sin(t * 0.08f + 0.6f) * 0.03f
            }
            ZeusAction.SCALES -> {
                // Die Schalen pendeln gegeneinander, der Balken wiegt sich langsam.
                val sway = sin(age * 0.07f)
                scales.roll += sway * 0.05f
                part(Keys.SCALES_PAN_R).roll -= sway * 0.05f
                part(Keys.SCALES_PAN_L).roll -= sway * 0.05f
            }
        }
        // Fängt er den Blitz wieder, reißt es den Arm mit einem Ruck herunter.
        if (state.previousAction == ZeusAction.EMPTY && action != ZeusAction.EMPTY && t < 8f) {
            val jolt = (1f - t / 8f).let { it * it }
            armR.pitch += jolt * 0.35f
            part(Keys.FORE_R).pitch -= jolt * 0.2f
        }

        // Nachschwingen: Mantelbahnen mit zwei Gliedern Verzögerung, Bart, Kranz kaum.
        val walkSwing = state.limbSwingAmplitude.coerceAtMost(1f) * sin(ph - 0.8f)
        for (m in 0..2) {
            part(Keys.MANTLE_0 + m).pitch += sin(age * 0.06f - m * 0.7f) * (0.015f + m * 0.012f) + abs(walkSwing) * 0.05f * (m + 1)
            part(Keys.MANTLE_0 + m).roll += sin(age * 0.045f - m * 0.9f) * 0.012f * (m + 1)
        }
        part(Keys.BEARD).pitch += sin(age * 0.07f - 0.5f) * 0.02f
        part(Keys.SKIRT_F).pitch += sin(age * 0.05f) * 0.015f
        part(Keys.SKIRT_B).pitch += sin(age * 0.05f + 1f) * 0.015f
        part(Keys.CROWN).pitch += sin(age * 0.04f) * 0.01f + abs(walkSwing) * 0.01f

        springs(state, anim, v, action, t, crossedFrom, dt, w)
    }

    /**
     * Weiche Teile (PLAN 2.1 Punkt 3): Mantel, Bart, Kranz und der Keraunos in der Hand folgen der Bewegung über
     * gedämpfte Federn. Jede Feder läuft hinter einem Zielwinkel her (Brust/Körper, Kopf, Arm, Drehung); die
     * Abweichung `Feder − Ziel` dreht das Teil gegen die Bewegung, es schwingt also nach. Stöße bei Treffer, Landung,
     * Erscheinen. Der Bart öffnet sich zum Text (`CinePlayer.mouthOf`): Das Modell hat keinen Kiefer, der Bart sitzt
     * unter dem Mund und trägt die Bewegung.
     */
    private fun springs(state: GuardianRenderState, anim: Anim, v: FloatArray, action: Int, t: Float, from: Float, dt: Float, walking: Float) {
        fun crossed(at: Int) = from >= 0f && from < at && t >= at
        // Stöße: harter Schlag, Landung des Donnerschritts, Griff zurück zum Blitz.
        when (action) {
            ZeusAction.SLAM -> if (crossed(ZeusTimings.SLAM_HIT_END)) { anim.mantle.kick(-KICK_HIT); anim.beard.kick(KICK_BEARD) }
            ZeusAction.BASH -> if (crossed(ZeusTimings.BASH_HIT_END)) anim.mantle.kick(-KICK_HIT * 0.7f)
            ZeusAction.CALL -> if (crossed(ZeusTimings.CALL_HIT_END)) anim.bolt.kick(KICK_BOLT)
            ZeusAction.STEP -> if (crossed(ZeusTimings.STEP_APPEAR)) { anim.mantle.kick(KICK_LAND); anim.crown.kick(KICK_BEARD) }
            ZeusAction.STAGGER -> if (crossed(2)) { anim.mantle.kick(KICK_LAND); anim.beard.kick(-KICK_BEARD) }
        }
        val bodyPitch = v[Keys.BODY * 6] + v[Keys.CHEST * 6] + walking * WALK_LEAN
        val mantleLag = (anim.mantle.step(bodyPitch, dt) - bodyPitch).coerceIn(-LAG_MAX, LAG_MAX)
        // Drehung: der Körper dreht (oder er wendet sich), der Mantel bleibt kurz zurück.
        if (!anim.lastBodyYaw.isNaN()) anim.yaw += MathHelper.wrapDegrees((state.bodyYaw - anim.lastBodyYaw)) * DEG
        anim.lastBodyYaw = state.bodyYaw
        if (abs(anim.yaw) > YAW_RESET) { anim.mantleTurn.reset(); anim.yaw = 0f }
        val turnLag = (anim.mantleTurn.step(anim.yaw, dt) - anim.yaw).coerceIn(-LAG_MAX, LAG_MAX)
        part(Keys.MANTLE_0).pitch -= mantleLag * 0.55f
        part(Keys.MANTLE_1).pitch -= mantleLag * 0.3f
        part(Keys.MANTLE_2).pitch -= mantleLag * 0.2f
        part(Keys.MANTLE_0).roll += turnLag * 0.35f
        part(Keys.MANTLE_1).roll += turnLag * 0.25f
        part(Keys.MANTLE_2).roll += turnLag * 0.2f
        part(Keys.SKIRT_B).pitch -= mantleLag * 0.25f
        part(Keys.SKIRT_B).roll += turnLag * 0.15f

        val headPitch = v[Keys.HEAD * 6] + v[Keys.CHEST * 6]
        val beardLag = (anim.beard.step(headPitch, dt) - headPitch).coerceIn(-LAG_MAX, LAG_MAX)
        val mouth = CinePlayer.mouthOf(state.entityId).coerceIn(0f, 1f)
        part(Keys.BEARD).pitch -= beardLag * 0.8f
        part(Keys.BEARD).pitch += mouth * MOUTH_OPEN
        part(Keys.BEARD).originY += mouth * MOUTH_DROP
        head.pitch -= mouth * MOUTH_HEAD

        val crownLag = (anim.crown.step(headPitch, dt) - headPitch).coerceIn(-LAG_MAX, LAG_MAX)
        part(Keys.CROWN).pitch -= crownLag * 0.4f

        // Der Keraunos zieht hinter dem Arm her (Nachzieh-Feder), am stärksten beim Ausholen und Schlagen.
        val armPitch = v[Keys.ARM_R * 6] + v[Keys.FORE_R * 6]
        val boltLag = (anim.bolt.step(armPitch, dt) - armPitch).coerceIn(-BOLT_LAG_MAX, BOLT_LAG_MAX)
        keraunos.pitch -= boltLag * BOLT_FOLLOW
        keraunos.roll += turnLag * 0.2f
    }

    /** Ist der Blitz in der Hand zu sehen (nach dem letzten [setAngles])? */
    val keraunosVisible: Boolean get() = keraunos.visible && rootPart.visible

    /**
     * Anker für `BossGlow` (ein Element: der Blitz in der Hand): Mitte des Keraunos in Modellkoordinaten des
     * Renderers (vor dem Kamera-Versatz). [matrices] muss so stehen wie beim Zeichnen des Modells (nach `scale`).
     * Liefert false, wenn der Blitz gerade nicht zu sehen ist.
     */
    fun keraunosAnchor(matrices: MatrixStack, out: Vector3f): Boolean {
        if (!keraunosVisible) return false
        // Kette ablaufen: body → chest → arm_r → fore_r → hand_r → keraunos.
        matrices.push()
        rootPart.applyTransform(matrices)
        var cur: ModelPart = rootPart
        for (name in Keys.KERAUNOS_PATH.split('/')) {
            cur = cur.getChild(name)
            cur.applyTransform(matrices)
        }
        matrices.peek().positionMatrix.transformPosition(0f, KERAUNOS_MID / 16f, 0f, out)
        matrices.pop()
        return true
    }

    private companion object {
        /** Mitte des Blitzes entlang seiner Achse (Einheiten vom Griff, -y = zur Spitze); siehe olymp_props_model.py. */
        const val KERAUNOS_MID = -21f

        // --- Überarbeitung 3: Überblenden, Federn, Mund (nur Darstellung) -----------------------------------------

        /** Kurze Glättung über [PoseMemory] zusätzlich zur Bahn jeder Aktion (Ticks). */
        const val BLEND_TICKS = 3f
        /** Zustand nicht mehr gezeichneter Wesen vergessen (10 s). */
        const val FORGET_NANOS = 10_000_000_000L
        /** Federn: Frequenz (rad/Tick) und Dämpfung (1 = kritisch). Mantel schwer und weich, Bart leichter. */
        const val MANTLE_FREQ = 0.32f
        const val MANTLE_DAMP = 0.35f
        const val TURN_FREQ = 0.28f
        const val TURN_DAMP = 0.4f
        const val BEARD_FREQ = 0.5f
        const val BEARD_DAMP = 0.38f
        const val CROWN_FREQ = 0.8f
        const val CROWN_DAMP = 0.5f
        const val BOLT_FREQ = 0.6f
        const val BOLT_DAMP = 0.3f
        /** Größte Auslenkung einer Feder (rad), damit Sprünge (Donnerschritt, Wenden) nichts umklappen. */
        const val LAG_MAX = 0.45f
        const val BOLT_LAG_MAX = 0.35f
        const val BOLT_FOLLOW = 0.6f
        /** Größere Drehsprünge (Versetzen, Szene) setzen die Drehfeder zurück statt nachzuschwingen. */
        const val YAW_RESET = 3f
        const val WALK_LEAN = 0.08f
        /** Stöße (Geschwindigkeit in rad/Tick). */
        const val KICK_HIT = 0.09f
        const val KICK_LAND = 0.07f
        const val KICK_BEARD = 0.06f
        const val KICK_BOLT = 0.08f
        /** Bart zum Text: Öffnen (rad), Absenken (Modell-Pixel), Kopf hebt sich leicht. */
        const val MOUTH_OPEN = 0.14f
        const val MOUTH_DROP = 0.6f
        const val MOUTH_HEAD = 0.03f

        /** Zeigen (`POINT`): Heben in so vielen Ticks. */
        const val POINT_RAISE = 14f

        /**
         * Zeige-Pose: wie `YIELD` (Blitz gesenkt), aber der rechte Arm hebt sich nach vorn, der Blitz zeigt schräg nach
         * unten (Summe der Arm-Neigungen ≈ −0,8 rad), der Kopf blickt hinab.
         */
        val POINT_KEY: ZeusKey = Keys.YIELD.v.copyOf().let { p ->
            fun set(bone: Int, pitch: Float, yaw: Float, roll: Float) {
                p[bone * 6] = pitch; p[bone * 6 + 1] = yaw; p[bone * 6 + 2] = roll
            }
            set(Keys.ARM_R, -1.2f, 0.1f, 0.05f)
            set(Keys.FORE_R, -0.1f, 0f, 0f)
            set(Keys.HAND_R, 0.5f, 0f, 0f)
            set(Keys.HEAD, 0.32f, 0.05f, 0f)
            p[Keys.CHEST * 6] = 0.08f
            ZeusKey(p, Keys.YIELD.hidden)
        }
    }

    // <keys> erzeugt von tools/hha_assets/zeus_model.py --kotlin; nicht von Hand ändern
    object Keys {
        /** Knochenpfade in Kanalreihenfolge (je 6 Werte: pitch, yaw, roll, dx, dy, dz). */
        val BONES = arrayOf("body", "body/chest", "body/chest/head", "body/chest/head/beard", "body/chest/head/crown", "body/chest/head/crown/crown_fan/crown_0", "body/chest/head/crown/crown_fan/crown_1", "body/chest/head/crown/crown_fan/crown_2", "body/chest/head/crown/crown_fan/crown_3", "body/chest/head/crown/crown_fan/crown_4", "body/chest/arm_r", "body/chest/arm_r/fore_r", "body/chest/arm_r/fore_r/hand_r", "body/chest/arm_l", "body/chest/arm_l/fore_l", "body/chest/arm_l/fore_l/hand_l", "body/chest/arm_l/fore_l/hand_l/aegis", "body/chest/mantle_0", "body/chest/mantle_0/mantle_1", "body/chest/mantle_0/mantle_1/mantle_2", "body/skirt_f", "body/skirt_b", "body/leg_r", "body/leg_r/shin_r", "body/leg_l", "body/leg_l/shin_l", "scales", "scales/scales_pan_r", "scales/scales_pan_l")
        const val KERAUNOS_PATH = "body/chest/arm_r/fore_r/hand_r/keraunos"
        const val BODY = 0
        const val CHEST = 1
        const val HEAD = 2
        const val BEARD = 3
        const val CROWN = 4
        const val CROWN_0 = 5
        const val CROWN_1 = 6
        const val CROWN_2 = 7
        const val CROWN_3 = 8
        const val CROWN_4 = 9
        const val ARM_R = 10
        const val FORE_R = 11
        const val HAND_R = 12
        const val ARM_L = 13
        const val FORE_L = 14
        const val HAND_L = 15
        const val AEGIS = 16
        const val MANTLE_0 = 17
        const val MANTLE_1 = 18
        const val MANTLE_2 = 19
        const val SKIRT_F = 20
        const val SKIRT_B = 21
        const val LEG_R = 22
        const val SHIN_R = 23
        const val LEG_L = 24
        const val SHIN_L = 25
        const val SCALES = 26
        const val SCALES_PAN_R = 27
        const val SCALES_PAN_L = 28
        const val COUNT = 29
        /** Schlüsselposen: je Knochen 6 Werte; die Liste nennt Knochen, die in der Pose ausgeblendet sind. */
        val IDLE = ZeusKey.of("0,0,0,0,0,0,-0.04,0.05,0,0,0,0,0.04,-0.05,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,0.12,0,0.24,0,0,0,-1.55,0.1,0,0,0,0,0.05,0,-0.1,0,0,0,-0.12,0,-0.16,0,0,0,-0.42,0,0,0,0,0,0,0,0,0,0,0,0.22,0.28,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.02,0,0.05,0,0,0,0,0,0,0,0,0,-0.08,-0.12,-0.05,0,0,0,0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val THROW_WINDUP = ZeusKey.of("0.05,0.42,0,0,2,0,-0.16,0.3,0.08,0,0,0,-0.05,-0.62,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,-2.2,0.3,0.55,0,0,0,-1.25,0,0,0,0,0,0.5,0,0,0,0,0,-1.35,0.2,-0.25,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,1.2,0.3,0,0,0,0,0.1,0,0,0,0,0,0.05,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.42,0,0.12,0,0,0,0.25,0,0,0,0,0,-0.62,0,-0.1,0,0,0,0.35,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val THROW_RELEASE = ZeusKey.of("0.28,-0.42,0,0,3,-2,0.22,-0.38,-0.06,0,0,0,-0.15,0.55,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,-1.25,-0.2,0.15,0,0,0,-0.15,0,0,0,0,0,0.2,0,0,0,0,0,0.55,0,-0.35,0,0,0,-0.5,0,0,0,0,0,0,0,0,0,0,0,0.4,0.3,0,0,0,0,-0.2,0,0,0,0,0,-0.15,0,0,0,0,0,-0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.55,0,0.1,0,0,0,0.2,0,0,0,0,0,-0.75,0,-0.08,0,0,0,0.55,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val EMPTY = ZeusKey.of("0.08,0,0,0,1,0,0.18,0,0,0,0,0,0.3,0.15,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,-0.7,0.1,0.12,0,0,0,-0.75,0,0,0,0,0,-0.3,0,0.2,0,0,0,0.12,0,-0.1,0,0,0,-0.12,0,0,0,0,0,0,0,0,0,0,0,0.55,0.4,0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.02,0,0.05,0,0,0,0,0,0,0,0,0,-0.08,-0.12,-0.05,0,0,0,0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val BASH_WINDUP = ZeusKey.of("0.22,-0.3,0,0,6,2,0.12,-0.25,0,0,0,0,-0.12,0.35,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,0.45,0,0.35,0,0,0,-0.9,0,0,0,0,0,0.3,0,0,0,0,0,-0.75,0.45,0.1,0,0,0,-1.15,0,0,0,0,0,0,0,0,0,0,0,1.4781,0.1847,-0.1994,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.55,0.06,0.08,0,0,0,0.95,0,0,0,0,0,-0.4,-0.06,-0.08,0,0,0,0.8,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val BASH_HIT = ZeusKey.of("0.38,0.1,0,0,4,-6,0.15,0.15,0,0,0,0,-0.3,-0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,0.6,0,0.3,0,0,0,-0.7,0,0,0,0,0,0.3,0,0,0,0,0,-1.5,0.15,-0.05,0,0,0,-0.2,0,0,0,0,0,0,0,0,0,0,0,1.1234,-0.0333,-0.3052,0,0,0,0.35,0,0,0,0,0,0.2,0,0,0,0,0,0.15,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.45,0,0.08,0,0,0,0.35,0,0,0,0,0,-0.95,0,-0.06,0,0,0,0.75,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val SLAM_WINDUP = ZeusKey.of("-0.02,0,0,0,-1,0,-0.08,0,0,0,0,0,-0.22,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-2,0,0,0,-1.6609,0,0,0,0,0,-0.8304,0,0,0,0,0,0,0,0,0,0,0,0.8304,0,0,0,0,0,1.6609,0,0,0,-2.95,0,0.16,0,0,0,-0.25,0,0,0,0,0,1.5,0,0,0,0,0,-2.95,0,-0.24,0,0,0,-0.25,0,0,0,0,0,0,0,0,0,0,0,-1.9154,1.4042,0.9799,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.05,0,0.1,0,0,0,0,0,0,0,0,0,0.05,0,-0.1,0,0,0,0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val SLAM_HIT = ZeusKey.of("0.3,0,0,0,7,-2,0.28,0,0,0,0,0,-0.15,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-2,0,0,0,-1.6609,0,0,0,0,0,-0.8304,0,0,0,0,0,0,0,0,0,0,0,0.8304,0,0,0,0,0,1.6609,0,0,0,-0.8,0.3,0,0,0,0,-0.15,0,0,0,0,0,1.5,0,0,0,0,0,-0.8,-0.38,0,0,0,0,-0.15,0,0,0,0,0,0,0,0,0,0,0,1.011,-1.012,-0.5066,0,0,0,0.3,0,0,0,0,0,0.18,0,0,0,0,0,0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.55,0.06,0.08,0,0,0,0.95,0,0,0,0,0,-0.4,-0.06,-0.08,0,0,0,0.8,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val STUCK = ZeusKey.of("0.22,0,0,0,15,0,0.22,0,0,0,0,0,0.3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,-0.75,0,-0.2,0,0,0,-0.2,0,0,0,0,0,1.5,0,0.2,0,0,0,-0.3,0,-0.15,0,0,0,-0.9,0,0,0,0,0,0,0,0,0,0,0,0.558,0.1528,0.3285,0,0,0,0.3,0,0,0,0,0,0.1,0,0,0,0,0,0,0,0,0,0,0,-0.5,0,0,0,0,0,0,0,0,0,0,0,0.25,0,0.08,0,0,0,1.35,0,0,0,0,0,-1.25,0,-0.05,0,0,0,1.25,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val CALL_WINDUP = ZeusKey.of("-0.06,0,0,0,0,0,-0.22,0.1,0.05,0,0,0,-0.55,0.05,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1,0,0,0,-1.5247,0,0,0,0,0,-0.7624,0,0,0,0,0,0,0,0,0,0,0,0.7624,0,0,0,0,0,1.5247,0,0,0,-2.95,0,0.22,0,0,0,-0.12,0,0,0,0,0,1.5,0,0,0,0,0,0.12,0,-0.32,0,0,0,-0.15,0,0,0,0,0,0,0,0,0,0,0,0.3,0.6,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.02,0,0.05,0,0,0,0,0,0,0,0,0,-0.08,-0.12,-0.05,0,0,0,0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val CALL_HIT = ZeusKey.of("0.12,0,0,0,2,-1,0.18,-0.1,0,0,0,0,0.12,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,-1.45,0.05,0.12,0,0,0,-0.1,0,0,0,0,0,1.5,0,0,0,0,0,0.2,0,-0.32,0,0,0,-0.2,0,0,0,0,0,0,0,0,0,0,0,0.3,0.6,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.2,0,0.06,0,0,0,0,0,0,0,0,0,-0.4,0,-0.06,0,0,0,0.3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val STEP_CROUCH = ZeusKey.of("0.35,0,0,0,7,0,0.45,0,0,0,0,0,0.35,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,-0.7,0.4,-0.55,0,0,0,-1.5,0,0,0,0,0,0.3,0,0,0,0,0,-0.7,-0.4,0.55,0,0,0,-1.5,0,0,0,0,0,0,0,0,0,0,0,0.2,-0.8,0,0,0,0,-0.25,0,0,0,0,0,-0.2,0,0,0,0,0,-0.15,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.55,0.06,0.08,0,0,0,0.95,0,0,0,0,0,-0.4,-0.06,-0.08,0,0,0,0.8,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val STEP_ARRIVE = ZeusKey.of("-0.05,0,0,0,-1,0,-0.15,0,0,0,0,0,-0.2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1,0,0,0,-1.5656,0,0,0,0,0,-0.7828,0,0,0,0,0,0,0,0,0,0,0,0.7828,0,0,0,0,0,1.5656,0,0,0,-0.3,0,1.25,0,0,0,-0.35,0,0,0,0,0,0.4,0,0,0,0,0,-0.3,0,-1.25,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,0.2,0.9,0,0,0,0,0.55,0,0,0,0,0,0.35,0,0,0,0,0,0.25,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.05,0,0.18,0,0,0,0,0,0,0,0,0,0.05,0,-0.18,0,0,0,0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val GAZE_WINDUP = ZeusKey.of("0,0,0,0,1,0,-0.06,0,0,0,0,0,0.25,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,-1.1,-0.5,-0.2,0,0,0,-1.25,0,0,0,0,0,0.6,0,0,0,0,0,-1.25,0.85,0.25,0,0,0,-1.05,0,0,0,0,0,0,0,0,0,0,0,2.2143,0.7335,-0.5338,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.15,0,0.1,0,0,0,0,0,0,0,0,0,-0.3,0,-0.08,0,0,0,0.2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val GAZE_HIT = ZeusKey.of("0.1,0,0,0,2,-2,0.08,0,0,0,0,0,0.25,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,-1.25,-0.5,-0.2,0,0,0,-1.05,0,0,0,0,0,0.6,0,0,0,0,0,-1.4,0.85,0.25,0,0,0,-0.85,0,0,0,0,0,0,0,0,0,0,0,1.8477,0.7073,-0.5712,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.25,0,0.1,0,0,0,0,0,0,0,0,0,-0.45,0,-0.08,0,0,0,0.3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val SCALES_HOLD = ZeusKey.of("0,0,0,0,0,0,-0.08,-0.08,0,0,0,0,-0.3,0.2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,-0.12,0,0.42,0,0,0,-0.35,0,0,0,0,0,1.5,0,0,0,0,0,-2.75,0,0.42,0,0,0,-0.2,0,0,0,0,0,0,0,0,0,0,0,2.4801,1.4157,-0.235,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.02,0,0.05,0,0,0,0,0,0,0,0,0,-0.08,-0.12,-0.05,0,0,0,0.1,0,0,0,0,0,0,0,0,19.723,-16.407,11.188,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf())
        val SCALES_CAST = ZeusKey.of("0,0,0,0,0,0,-0.04,-0.2,0,0,0,0,-0.2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,-1.7,-0.2,0.25,0,0,0,-0.1,0,0,0,0,0,1.5,0,0,0,0,0,-2.75,0,0.42,0,0,0,-0.2,0,0,0,0,0,0,0,0,0,0,0,2.6269,1.2941,-0.0745,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.02,0,0.05,0,0,0,0,0,0,0,0,0,-0.08,-0.12,-0.05,0,0,0,0.1,0,0,0,0,0,0,0,0.06,20.683,-16.016,12.757,0,0,-0.06,0,0,0,0,0,-0.06,0,0,0", intArrayOf())
        val STAGGER = ZeusKey.of("-0.12,0.15,0.08,0,14,3,0.45,0.1,0.12,0,0,0,0.6,-0.2,0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,-0.55,0,0.25,0,0,0,-0.6,0,0,0,0,0,1.2,0,0,0,0,0,0.25,0,-0.55,0,0,0,-0.1,0,0,0,0,0,0,0,0,0,0,0,-0.447,-1.2719,0.5616,0,0,0,0.2,0,0,0,0,0,0.15,0,0,0,0,0,0,0,0,0,0,0,-0.4,0,0,0,0,0,0,0,0,0,0,0,0.35,0,0.12,0,0,0,1.45,0,0,0,0,0,-1.05,0,-0.15,0,0,0,1.2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val GUARD_WINDUP = ZeusKey.of("0.05,0,0,0,5,0,-0.05,0,0,0,0,0,-0.5,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,0.3,0,0.45,0,0,0,-0.8,0,0,0,0,0,0.3,0,0,0,0,0,-2.35,0.4,0.35,0,0,0,-1,0,0,0,0,0,0,0,0,0,0,0,1.9877,0.291,0.4436,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.55,0.06,0.08,0,0,0,0.95,0,0,0,0,0,-0.4,-0.06,-0.08,0,0,0,0.8,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val GUARD_HIT = ZeusKey.of("-0.05,0,0,0,-2,0,-0.12,0,0,0,0,0,-0.55,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,0.2,0,0.5,0,0,0,-0.6,0,0,0,0,0,0.3,0,0,0,0,0,-2.95,0.3,0.12,0,0,0,-0.3,0,0,0,0,0,0,0,0,0,0,0,1.8906,0.2825,0.1576,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.12,0,0,0,0,0,0,0,0,0,0,0,-0.12,0,0,0,0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val RISE = ZeusKey.of("-0.08,0,0,0,20,4,0.06,0,0,0,0,0,0.3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,-0.55,0,0.18,0,0,0,-0.85,0,0,0,0,0,0.4,0,0,0,0,0,-0.6,0,-0.15,0,0,0,-0.4,0,0,0,0,0,0,0,0,0,0,0,1.1,0.2,0,0,0,0,0.1,0,0,0,0,0,-0.25,0,0,0,0,0,0,0,0,0,0,0,-1.2,0,0,0,0,0,0,0,0,0,0,0,-1.5,0,0.12,0,0,0,1.45,0,0,0,0,0,-1.5,0,-0.12,0,0,0,1.45,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val PHASE = ZeusKey.of("-0.05,0,0,0,0,0,-0.2,0,0,0,0,0,-0.3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-3,0,0,0,-1.7017,0,0,0,0,0,-0.8508,0,0,0,0,0,0,0,0,0,0,0,0.8508,0,0,0,0,0,1.7017,0,0,0,-0.5,0,1.75,0,0,0,-0.3,0,0,0,0,0,0.6,0,0,0,0,0,-0.5,0,-1.75,0,0,0,-0.3,0,0,0,0,0,0,0,0,0,0,0,0.3,0.9,0,0,0,0,0.45,0,0,0,0,0,0.25,0,0,0,0,0,0.2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.14,0,0,0,0,0,0,0,0,0,0,0,-0.14,0,0,0,0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
        val YIELD = ZeusKey.of("0,0,0,0,0,0,0.06,0,0,0,0,0,0.38,0.12,0.06,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.3614,0,0,0,0,0,-0.6807,0,0,0,0,0,0,0,0,0,0,0,0.6807,0,0,0,0,0,1.3614,0,0,0,-0.28,0,0.12,0,0,0,-0.3,0,0,0,0,0,1.5,0,0,0,0,0,0.05,0,-0.1,0,0,0,-0.15,0,0,0,0,0,0,0,0,0,0,0,0.4,0.3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.02,0,0.05,0,0,0,0,0,0,0,0,0,-0.08,-0.12,-0.05,0,0,0,0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0", intArrayOf(26))
    }
    // </keys>
}
