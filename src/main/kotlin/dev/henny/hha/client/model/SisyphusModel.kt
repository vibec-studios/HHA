package dev.henny.hha.client.model

import dev.henny.hha.entity.miniboss.SisyphusAction
import dev.henny.hha.logic.boss.BossDefense
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.BossTimings.Sisyphus as S
import net.minecraft.client.model.ModelPart
import org.joml.Matrix3f
import org.joml.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Sisyphos: tief gebeugt unter dem Felsen, der größer ist als sein Oberkörper. Nacken und rechte Schulter sind zu
 * Stein geworden. Brief: tmp/overhaul/models/sisyphos.md, Zeiten aus [BossTimings.Sisyphus], Aktionen aus
 * [SisyphusAction].
 *
 * Jede Schlüsselpose steht mit denselben Winkeln in `POSES` von tools/hha_assets/sisyphus_model.py; die Tabelle
 * [SisyphusPoses] erzeugt `python tools/hha_assets/sisyphus_model.py --sync-kotlin`. Die Animation mischt diese
 * absoluten Posen über `actionTime` (Ausholen weich, Schlag hart, Erholung gehalten) und legt danach Gang, Atem,
 * Zittern und die Schwerkraft von Ketten, Bart und Lendentuch darüber.
 *
 * Der Knochen `boulder` ist in sich geschlossen (Ursprung in der Mitte), damit der Fels im Flug als Modell gezeichnet
 * werden kann (Plan 6.5). Nach THROW und BOWL ist er weg, bis REGROW ihn ab [BossTimings.Sisyphus.REGROW_START]
 * nachwachsen lässt.
 */
class SisyphusModel(root: ModelPart) : GuardianModel(root) {
    private val bones: Array<ModelPart>
    private val baseX: FloatArray
    private val baseY: FloatArray
    private val baseZ: FloatArray
    private val cur = FloatArray(SisyphusPoses.BONES.size * 6)

    init {
        val found = HashMap<String, ModelPart>()
        bones = Array(SisyphusPoses.BONES.size) { i ->
            val parent = SisyphusPoses.PARENTS[i]
            val part = (if (parent.isEmpty()) root else found.getValue(parent)).getChild(SisyphusPoses.BONES[i])
            found[SisyphusPoses.BONES[i]] = part
            part
        }
        baseX = FloatArray(bones.size) { bones[it].originX }
        baseY = FloatArray(bones.size) { bones[it].originY }
        baseZ = FloatArray(bones.size) { bones[it].originZ }
    }

    private fun bone(i: Int) = bones[i]
    private val hips get() = bone(HIPS)
    private val body get() = bone(BODY)
    private val head get() = bone(HEAD)
    private val jaw get() = bone(JAW)
    private val beard get() = bone(BEARD)
    private val beardTip get() = bone(BEARD_TIP)
    private val boulder get() = bone(BOULDER)
    private val armR get() = bone(ARM_R)
    private val foreR get() = bone(FORE_R)
    private val chainR get() = bone(CHAIN_R)
    private val armL get() = bone(ARM_L)
    private val foreL get() = bone(FORE_L)
    private val chainL get() = bone(CHAIN_L)
    private val loin get() = bone(LOIN)
    private val loinBack get() = bone(LOIN_BACK)
    private val legR get() = bone(LEG_R)
    private val shinR get() = bone(SHIN_R)
    private val legL get() = bone(LEG_L)
    private val shinL get() = bone(SHIN_L)

    private val world = Matrix3f()
    private val tmp = Matrix3f()
    private val euler = Vector3f()

    private fun start(pose: FloatArray) = pose.copyInto(cur)

    /** Setzt [part] so, dass es trotz Hüfte, Rumpf, [arm] und [fore] senkrecht hängt. */
    private fun hangDown(part: ModelPart, arm: ModelPart, fore: ModelPart) {
        world.rotationZYX(hips.roll, hips.yaw, hips.pitch)
        world.mul(tmp.rotationZYX(body.roll, body.yaw, body.pitch))
        world.mul(tmp.rotationZYX(arm.roll, arm.yaw, arm.pitch))
        world.mul(tmp.rotationZYX(fore.roll, fore.yaw, fore.pitch))
        world.transpose().getEulerAnglesZYX(euler)
        part.pitch = euler.x
        part.yaw = euler.y
        part.roll = euler.z
    }

    /** Mischt eine absolute Pose mit Gewicht [w] über die bisherige. */
    private fun blend(pose: FloatArray, w: Float) {
        if (w <= 0f) return
        val k = min(w, 1f)
        for (i in cur.indices) cur[i] += (pose[i] - cur[i]) * k
    }

    private fun apply() {
        for (i in bones.indices) {
            val b = bones[i]
            val o = i * 6
            b.pitch = cur[o]
            b.yaw = cur[o + 1]
            b.roll = cur[o + 2]
            b.originX = baseX[i] + cur[o + 3]
            b.originY = baseY[i] + cur[o + 4]
            b.originZ = baseZ[i] + cur[o + 5]
        }
    }

    /** 0 → 1 → 0 über [from]..[to], für Bahnen, die zwischen zwei Schlüsselposen ausweichen müssen. */
    private fun arc(t: Float, from: Float, to: Float): Float {
        val x = ((t - from) / (to - from)).coerceIn(0f, 1f)
        return sin(x * PI.toFloat())
    }

    override fun setAngles(state: GuardianRenderState) {
        super.setAngles(state)
        val t = state.age
        val a = state.actionTime
        var w = walk(state)
        val p = phase(state)
        var tremble = 0f
        var pant = 0f
        var shout = 0f
        var boulderShown = true
        var boulderScale = 1f
        // Ausweichbahn des Felsens zwischen zwei Posen (im Rumpfraum): dy nach oben, dz nach hinten.
        var liftArc = 0f
        var gaze = 0f
        var backArc = 0f
        val rig = RIGS.of(state.entityId)
        rig.begin(t, state.action, a)

        start(SisyphusPoses.IDLE)
        when (state.action) {
            SisyphusAction.THROW -> {
                // Stemmen bis 14, halten bis 24 (Knie zittern), Wurf 24 hart, er fällt fast auf die Knie,
                // ausgelaufen bis 44 in der offenen Haltung ohne Fels.
                val lift = step(a, 0f, S.THROW_LIFT.toFloat())
                val release = step(a, S.THROW_WINDUP - 2f, S.THROW_WINDUP + 1f)
                val recover = step(a, S.THROW_END - 10f, S.THROW_END.toFloat())
                blend(SisyphusPoses.THROW_LIFT, lift)
                blend(SisyphusPoses.THROW_RELEASE, release)
                blend(SisyphusPoses.REGROW_EMPTY, recover)
                tremble = lift * (1f - release)
                pant = recover
                if (a >= S.THROW_WINDUP) boulderShown = false
            }
            SisyphusAction.BOWL -> {
                // Absetzen bis 10 (der Fels geht über den Kopf nach vorn), halten bis 22, Stoß 22, Ende 40.
                val set = step(a, 0f, S.BOWL_SET.toFloat())
                val push = step(a, S.BOWL_WINDUP - 2f, S.BOWL_WINDUP + 1f)
                val recover = step(a, S.BOWL_END - 10f, S.BOWL_END.toFloat())
                blend(SisyphusPoses.BOWL_SET, set)
                blend(SisyphusPoses.BOWL_PUSH, push)
                blend(SisyphusPoses.REGROW_EMPTY, recover)
                liftArc = arc(a, 0f, S.BOWL_SET.toFloat()) * 16f
                tremble = set * (1f - push) * 0.5f
                pant = recover
                if (a >= S.BOWL_WINDUP) boulderShown = false
            }
            SisyphusAction.STOMP -> {
                // Fels auf die linke Schulter, Knie zur Brust (weich 8 Ticks, gehalten), Tritt 20 hart, Ende 38.
                val windup = step(a, 0f, 8f)
                val hit = step(a, S.STOMP_WINDUP - 2f, S.STOMP_WINDUP.toFloat())
                val recover = step(a, S.STOMP_END - 10f, S.STOMP_END.toFloat())
                blend(SisyphusPoses.STOMP_WINDUP, windup)
                blend(SisyphusPoses.STOMP_HIT, hit)
                blend(SisyphusPoses.IDLE, recover)
                // Kurz vor dem Tritt holt das Knie noch ein Stück höher aus.
                cur[LEG_R * 6] -= 0.12f * arc(a, 10f, S.STOMP_WINDUP - 2f)
                tremble = hit * (1f - recover) * 0.6f
            }
            SisyphusAction.CURSE -> {
                // Lässt den Fels hinter sich fallen, Arme weit, Kopf in den Nacken, schreit; am Ende lädt er ihn wieder auf.
                val open = step(a, 0f, 10f)
                val back = step(a, S.CURSE - 8f, S.CURSE.toFloat())
                blend(SisyphusPoses.CURSE, open)
                blend(SisyphusPoses.IDLE, back)
                backArc = (arc(a, 0f, 10f) + arc(a, S.CURSE - 8f, S.CURSE.toFloat())) * 12f
                shout = open * (1f - back)
            }
            SisyphusAction.REGROW -> {
                // Steht ohne Fels, aufgerichtet, Arme hängen, keucht (offen). Ab 36 wächst der neue Fels auf dem
                // Nacken (Maßstab 0 → 1) und drückt ihn zurück in die Beuge.
                val grow = step(a, S.REGROW_START.toFloat(), S.REGROW.toFloat())
                blend(SisyphusPoses.REGROW_EMPTY, 1f - grow)
                pant = 1f - grow
                if (a < S.REGROW_START) boulderShown = false else boulderScale = max(grow, 0.02f)
            }
            BossDefense.ANTI_AIR -> {
                // Konter (Überarbeitung 2, PLAN 4.3): Er stemmt den Fels wie zum Wurf über den Kopf (THROW_LIFT) und stößt
                // ihn beim Ende der Ansage ein Stück nach oben; der Blick geht hinauf.
                val g = BossTimings.Guardian
                val lift = step(a, 0f, g.ANTI_AIR_RAISE.toFloat())
                val back = step(a, g.ANTI_AIR_END - 8f, g.ANTI_AIR_END.toFloat())
                blend(SisyphusPoses.THROW_LIFT, lift)
                blend(SisyphusPoses.IDLE, back)
                liftArc = 3f * hold(a - (g.ANTI_AIR_HIT - 1f), 2f, 5f, 10f)
                tremble = lift * (1f - back) * (1f - step(a, g.ANTI_AIR_HIT - 1f, g.ANTI_AIR_HIT + 1f))
                gaze = lift * (1f - back)
            }
            SisyphusAction.STAGGER -> {
                // Vom eigenen Fels getroffen: liegt halb auf der Seite, ein Arm schützt den Kopf; steht am Ende auf.
                val fall = step(a, 0f, 5f)
                val rise = step(a, S.STAGGER - 10f, S.STAGGER.toFloat())
                blend(SisyphusPoses.STAGGER, fall)
                blend(SisyphusPoses.IDLE, rise)
                liftArc = arc(a, 0f, 5f) * 8f + arc(a, S.STAGGER - 10f, S.STAGGER.toFloat()) * 8f
                cur[BODY * 6 + 2] += sin(t * 0.3f) * 0.05f * fall * (1f - rise)
            }
        }
        if (state.action != SisyphusAction.NONE) w *= 0.25f
        // Überarbeitung 3: aus der zuletzt gezeigten Pose überblenden (nicht aus der vorigen Aktion bei ihrem Alter).
        rig.blend(cur, state.action, t, BLEND)
        apply()

        // --- Ausweichbahn des Felsens, damit er nicht durch Kopf oder Rücken wandert ---
        boulder.originY -= liftArc
        boulder.originZ += backArc
        head.pitch -= 0.4f * gaze

        // --- Gang unter Last: kurze, schwere Schritte, bei jedem sackt er ein ---
        val stepSag = abs(sin(p)) * w
        legR.pitch += cos(p) * 0.45f * w
        legL.pitch += cos(p + PI.toFloat()) * 0.45f * w
        shinR.pitch += max(0f, -sin(p)) * 0.55f * w
        shinL.pitch += max(0f, sin(p)) * 0.55f * w
        hips.originY += stepSag * 1.6f
        hips.roll += sin(p) * 0.05f * w
        body.pitch += stepSag * 0.06f
        armR.pitch += cos(p + PI.toFloat()) * 0.3f * w
        armL.pitch += cos(p) * 0.05f * w

        // --- Atem, Keuchen, Zittern unter der Last ---
        val breath = sin(t * 0.08f)
        body.pitch += breath * 0.015f
        jaw.pitch += 0.03f + breath * 0.03f
        if (pant > 0f) {
            val heave = sin(t * 0.55f)
            body.pitch += heave * 0.05f * pant
            head.pitch += heave * 0.04f * pant
            jaw.pitch += (0.5f + 0.5f * heave) * 0.16f * pant
            armR.roll += heave * 0.03f * pant
            armL.roll -= heave * 0.03f * pant
        }
        if (tremble > 0f) {
            val q = sin(t * 2.6f) * 0.035f * tremble
            shinR.pitch += q
            shinL.pitch -= q
            hips.originY += abs(q) * 8f
            boulder.originX += sin(t * 3.1f) * 0.25f * tremble
        }
        if (shout > 0f) {
            head.roll += sin(t * 1.7f) * 0.04f * shout
            jaw.pitch += sin(t * 2.3f) * 0.05f * shout
        }

        // --- Blick und Nahkampf-Faustschlag (ohne Aktion) ---
        head.yaw += state.relativeHeadYaw * DEG * 0.6f
        head.pitch += state.pitch * DEG * 0.4f
        if (state.swing > 0f && state.action == SisyphusAction.NONE) {
            val s = sin(state.swing * PI.toFloat())
            armR.pitch += -1.7f * s
            foreR.pitch += 0.5f * s
            body.pitch += 0.18f * s
        }

        // --- Schwerkraft: Ketten, Bart und Lendentuch heben die Neigung der Eltern auf (wie hang() in Python) ---
        // Ketten hängen exakt senkrecht: inverse Weltdrehung von Hüfte, Rumpf, Ober- und Unterarm (wie _hanging()).
        hangDown(chainR, armR, foreR)
        hangDown(chainL, armL, foreL)
        chainR.pitch += sin(t * 0.11f) * 0.12f + sin(p) * 0.25f * w
        chainR.roll += cos(t * 0.09f) * 0.08f
        chainL.pitch += sin(t * 0.11f + 1.3f) * 0.12f + sin(p + 1f) * 0.25f * w
        chainL.roll += cos(t * 0.09f + 0.7f) * 0.08f
        beard.pitch = -(hips.pitch + body.pitch + head.pitch) * 0.85f + sin(t * 0.07f + 0.4f) * 0.05f + stepSag * 0.08f
        beard.roll = -(hips.roll + body.roll + head.roll) * 0.8f
        beardTip.pitch += sin(t * 0.07f - 0.6f) * 0.06f
        val lean = -(hips.pitch + body.pitch)
        loin.pitch = lean + min(0f, min(legR.pitch, legL.pitch)) * 0.6f + sin(t * 0.06f) * 0.03f
        loin.roll = -body.roll
        loinBack.pitch = lean + max(0f, max(legR.pitch, legL.pitch)) * 0.6f + 4f * DEG
        loinBack.roll = -body.roll

        // --- Überarbeitung 3: Bart und Lendentuch schwingen nach harten Bewegungen nach, Mund zum Text ---
        for (hit in hitTimes(state.action)) {
            if (!rig.crossed(hit)) continue
            rig.springs[BEARD_SPRING].kick(BEARD_KICK)
            rig.springs[LOIN_SPRING].kick(LOIN_KICK)
        }
        val beardSwing = rig.spring(BEARD_SPRING)
        val loinSwing = rig.spring(LOIN_SPRING)
        beard.pitch += beardSwing
        beardTip.pitch += beardSwing * 1.4f
        loin.pitch += loinSwing
        loinBack.pitch -= loinSwing * 0.8f
        jaw.pitch += dev.henny.hha.client.cine.CinePlayer.mouthOf(state.entityId) * MOUTH_OPEN

        // --- Fels: weg nach dem Wurf, wächst während REGROW nach ---
        boulder.visible = boulderShown
        boulder.xScale = boulderScale
        boulder.yScale = boulderScale
        boulder.zScale = boulderScale
    }

    /** Zeitpunkte harter Bewegungen je Aktion (Stoß für Bart und Lendentuch), aus [BossTimings.Sisyphus]. */
    private fun hitTimes(action: Int): FloatArray = when (action) {
        SisyphusAction.THROW -> floatArrayOf(S.THROW_WINDUP.toFloat())
        SisyphusAction.BOWL -> floatArrayOf(S.BOWL_SET.toFloat(), S.BOWL_WINDUP.toFloat())
        SisyphusAction.STOMP -> floatArrayOf(S.STOMP_WINDUP.toFloat())
        SisyphusAction.CURSE -> floatArrayOf(8f)
        SisyphusAction.REGROW -> floatArrayOf(S.REGROW.toFloat() - 4f)
        SisyphusAction.STAGGER -> floatArrayOf(4f)
        BossDefense.ANTI_AIR -> floatArrayOf(BossTimings.Guardian.ANTI_AIR_HIT.toFloat())
        else -> NO_HITS
    }

    private val RIGS = GuardianRigs { GuardianRig(SisyphusPoses.BONES.size * 6, 2, SPRING_FREQ, SPRING_DAMP) }

    private companion object {
        // --- Überarbeitung 3 (P14) ---
        /** Überblendung aus der gezeigten Pose bei jedem Aktionswechsel (Ticks). */
        const val BLEND = 6f
        const val BEARD_SPRING = 0
        const val LOIN_SPRING = 1
        const val SPRING_FREQ = 0.4f
        const val SPRING_DAMP = 0.22f
        /** Stoß (rad/Tick) für Bart und Lendentuch. */
        const val BEARD_KICK = 0.1f
        const val LOIN_KICK = 0.08f
        const val MOUTH_OPEN = 0.35f
        val NO_HITS = FloatArray(0)

        val HIPS = SisyphusPoses.index("hips")
        val BODY = SisyphusPoses.index("body")
        val HEAD = SisyphusPoses.index("head")
        val JAW = SisyphusPoses.index("jaw")
        val BEARD = SisyphusPoses.index("beard")
        val BEARD_TIP = SisyphusPoses.index("beard_tip")
        val BOULDER = SisyphusPoses.index("boulder")
        val ARM_R = SisyphusPoses.index("arm_r")
        val FORE_R = SisyphusPoses.index("fore_r")
        val CHAIN_R = SisyphusPoses.index("chain_r")
        val ARM_L = SisyphusPoses.index("arm_l")
        val FORE_L = SisyphusPoses.index("fore_l")
        val CHAIN_L = SisyphusPoses.index("chain_l")
        val LOIN = SisyphusPoses.index("loin")
        val LOIN_BACK = SisyphusPoses.index("loin_back")
        val LEG_R = SisyphusPoses.index("leg_r")
        val SHIN_R = SisyphusPoses.index("shin_r")
        val LEG_L = SisyphusPoses.index("leg_l")
        val SHIN_L = SisyphusPoses.index("shin_l")
    }
}

/**
 * Schlüsselposen aus `POSES` in tools/hha_assets/sisyphus_model.py: je Knochen pitch, yaw, roll (Radiant, absolut)
 * und Versatz dx, dy, dz (Einheiten, zur Grundlage des Knochens). Nicht von Hand ändern.
 */
internal object SisyphusPoses {
    fun index(name: String) = BONES.indexOf(name).also { check(it >= 0) { "Sisyphos-Knochen $name fehlt" } }

    // <poses> erzeugt von sisyphus_model.py --sync-kotlin, nicht von Hand ändern
    val BONES = arrayOf("hips", "body", "head", "jaw", "beard", "beard_tip", "boulder", "arm_r", "fore_r", "hand_r", "chain_r", "arm_l", "fore_l", "hand_l", "chain_l", "loin", "loin_back", "leg_r", "shin_r", "foot_r", "leg_l", "shin_l", "foot_l")
    val PARENTS = arrayOf("", "hips", "body", "head", "head", "beard", "body", "body", "arm_r", "fore_r", "fore_r", "body", "arm_l", "fore_l", "fore_l", "body", "body", "hips", "leg_r", "shin_r", "hips", "leg_l", "shin_l")
    val IDLE = floatArrayOf(
        0.0000f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hips
        0.6981f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // body
        -0.9076f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // head
        0.1047f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // jaw
        0.1780f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard
        0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard_tip
        0.0000f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // boulder
        -0.6632f, 0.0000f, 0.2443f, 0.00f, 0.00f, 0.00f,  // arm_r
        -0.4887f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_r
        -0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_r
        0.4425f, 0.2228f, -0.1011f, 0.00f, 0.00f, 0.00f,  // chain_r
        -2.2689f, -0.3491f, -1.2217f, 0.00f, 0.00f, 0.00f,  // arm_l
        -1.7453f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_l
        -0.5236f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_l
        3.1033f, 0.7006f, -1.1369f, 0.00f, 0.00f, 0.00f,  // chain_l
        -1.0332f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin
        -0.6283f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin_back
        -0.5585f, 0.0000f, 0.1047f, 0.00f, 0.00f, 0.00f,  // leg_r
        0.8029f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_r
        -0.2443f, 0.0000f, -0.1047f, 0.00f, 0.00f, 0.00f,  // foot_r
        -0.4189f, 0.0000f, -0.0873f, 0.00f, 0.00f, 0.00f,  // leg_l
        0.6632f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_l
        -0.2443f, 0.0000f, 0.0873f, 0.00f, 0.00f, 0.00f,  // foot_l
    )
    val THROW_LIFT = floatArrayOf(
        0.0000f, 0.0000f, 0.0000f, 0.00f, -1.90f, 0.00f,  // hips
        -0.2443f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // body
        -0.4538f, 0.0000f, 0.0000f, 0.00f, -3.00f, 2.00f,  // head
        0.3491f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // jaw
        0.5934f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard
        0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard_tip
        0.2443f, 0.3491f, 0.0000f, 0.00f, -38.86f, -28.96f,  // boulder
        -2.9322f, 0.0000f, -0.2094f, 0.00f, 0.00f, 0.00f,  // arm_r
        -0.1047f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_r
        -0.6981f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_r
        -2.9997f, -0.0217f, -0.2083f, 0.00f, 0.00f, 0.00f,  // chain_r
        -2.9322f, 0.0000f, 0.2094f, 0.00f, 0.00f, 0.00f,  // arm_l
        -0.1047f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_l
        -0.6981f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_l
        -2.9997f, 0.0217f, 0.2083f, 0.00f, 0.00f, 0.00f,  // chain_l
        0.1815f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin
        0.3142f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin_back
        -0.1047f, 0.0000f, 0.1222f, 0.00f, 0.00f, 0.00f,  // leg_r
        0.1047f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_r
        0.0000f, 0.0000f, -0.1047f, 0.00f, 0.00f, 0.00f,  // foot_r
        -0.0698f, 0.0000f, -0.1222f, 0.00f, 0.00f, 0.00f,  // leg_l
        0.0873f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_l
        -0.0175f, 0.0000f, 0.0873f, 0.00f, 0.00f, 0.00f,  // foot_l
    )
    val THROW_RELEASE = floatArrayOf(
        0.0000f, 0.0000f, 0.0000f, 0.00f, 6.50f, 0.00f,  // hips
        1.2915f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // body
        -1.0123f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // head
        0.2793f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // jaw
        -0.2374f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard
        0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard_tip
        0.0000f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // boulder
        -2.0595f, 0.0000f, 0.1745f, 0.00f, 0.00f, 0.00f,  // arm_r
        -0.2094f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_r
        -0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_r
        0.9849f, 0.1334f, 0.1129f, 0.00f, 0.00f, 0.00f,  // chain_r
        -2.0595f, 0.0000f, -0.1745f, 0.00f, 0.00f, 0.00f,  // arm_l
        -0.2094f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_l
        -0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_l
        0.9849f, -0.1334f, -0.1129f, 0.00f, 0.00f, 0.00f,  // chain_l
        -2.1502f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin
        -1.2217f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin_back
        -0.2443f, 0.0000f, 0.1047f, 0.00f, 0.00f, 0.00f,  // leg_r
        1.6755f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_r
        -1.0472f, 0.0000f, -0.1047f, 0.00f, 0.00f, 0.00f,  // foot_r
        -1.4312f, 0.0000f, -0.1047f, 0.00f, 0.00f, 0.00f,  // leg_l
        1.4661f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_l
        -0.0349f, 0.0000f, 0.0873f, 0.00f, 0.00f, 0.00f,  // foot_l
    )
    val BOWL_SET = floatArrayOf(
        0.0000f, 0.0000f, 0.0000f, 0.00f, 15.23f, 0.00f,  // hips
        0.9774f, -0.2443f, 0.0000f, 0.00f, 0.00f, 0.00f,  // body
        -0.6981f, 0.1745f, 0.0000f, 0.00f, 0.00f, 0.00f,  // head
        0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // jaw
        -0.2374f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard
        0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard_tip
        -0.5236f, 0.1745f, 0.0000f, -5.80f, 5.94f, -39.28f,  // boulder
        -1.0472f, 0.1745f, 0.1745f, 0.00f, 0.00f, 0.00f,  // arm_r
        -0.6981f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_r
        -0.5236f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_r
        0.8208f, 0.3737f, 0.0401f, 0.00f, 0.00f, 0.00f,  // chain_r
        -1.4312f, -0.3491f, -0.4189f, 0.00f, 0.00f, 0.00f,  // arm_l
        -0.5934f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_l
        -0.5236f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_l
        0.9842f, -0.4016f, 0.3541f, 0.00f, 0.00f, 0.00f,  // chain_l
        -2.0665f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin
        -0.9076f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin_back
        -1.8151f, 0.0000f, 0.1745f, 0.00f, 0.00f, 0.00f,  // leg_r
        2.2340f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_r
        -0.4189f, 0.0000f, -0.1047f, 0.00f, 0.00f, 0.00f,  // foot_r
        -1.4661f, 0.0000f, -0.2094f, 0.00f, 0.00f, 0.00f,  // leg_l
        2.0595f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_l
        -0.5934f, 0.0000f, 0.0873f, 0.00f, 0.00f, 0.00f,  // foot_l
    )
    val BOWL_PUSH = floatArrayOf(
        0.0000f, 0.0000f, 0.0000f, 0.00f, 5.59f, 0.00f,  // hips
        1.0821f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // body
        -0.9076f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // head
        0.3142f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // jaw
        -0.1484f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard
        0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard_tip
        0.0000f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // boulder
        -2.6180f, 0.0000f, 0.1745f, 0.00f, 0.00f, 0.00f,  // arm_r
        -0.1047f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_r
        -0.6981f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_r
        1.6463f, 0.0707f, 0.1597f, 0.00f, 0.00f, 0.00f,  // chain_r
        -2.6180f, 0.0000f, -0.1745f, 0.00f, 0.00f, 0.00f,  // arm_l
        -0.1047f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_l
        -0.6981f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_l
        1.6463f, -0.0707f, -0.1597f, 0.00f, 0.00f, 0.00f,  // chain_l
        -1.8151f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin
        -0.5096f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin_back
        0.8378f, 0.0000f, 0.1047f, 0.00f, 0.00f, 0.00f,  // leg_r
        0.1396f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_r
        -0.5934f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // foot_r
        -1.2217f, 0.0000f, -0.1047f, 0.00f, 0.00f, 0.00f,  // leg_l
        1.3265f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_l
        -0.1047f, 0.0000f, 0.0873f, 0.00f, 0.00f, 0.00f,  // foot_l
    )
    val STOMP_WINDUP = floatArrayOf(
        0.0000f, 0.0000f, 0.0000f, 0.00f, -1.48f, 0.00f,  // hips
        0.3840f, 0.0000f, 0.2094f, 0.00f, 0.00f, 0.00f,  // body
        -0.5236f, 0.0000f, -0.1396f, 0.00f, 0.00f, 0.00f,  // head
        0.2094f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // jaw
        0.1187f, 0.0000f, -0.0559f, 0.00f, 0.00f, 0.00f,  // beard
        0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard_tip
        0.0000f, 0.0000f, -0.2793f, 0.86f, -5.57f, -6.17f,  // boulder
        -0.5236f, 0.0000f, 1.0821f, 0.00f, 0.00f, 0.00f,  // arm_r
        -0.5236f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_r
        0.0000f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_r
        0.0431f, 0.9065f, -1.0829f, 0.00f, 0.00f, 0.00f,  // chain_r
        -2.9322f, 0.0000f, -0.7330f, 0.00f, 0.00f, 0.00f,  // arm_l
        -1.6755f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_l
        -0.3491f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_l
        -2.0225f, 0.5429f, 0.0281f, 0.00f, 0.00f, 0.00f,  // chain_l
        -1.5568f, 0.0000f, -0.2094f, 0.00f, 0.00f, 0.00f,  // loin
        -0.3142f, 0.0000f, -0.2094f, 0.00f, 0.00f, 0.00f,  // loin_back
        -1.9548f, 0.0000f, 0.1396f, 0.00f, 0.00f, 0.00f,  // leg_r
        1.8151f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_r
        0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // foot_r
        -0.2094f, 0.0000f, -0.1047f, 0.00f, 0.00f, 0.00f,  // leg_l
        0.3142f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_l
        -0.1047f, 0.0000f, 0.0873f, 0.00f, 0.00f, 0.00f,  // foot_l
    )
    val STOMP_HIT = floatArrayOf(
        0.0000f, 0.0000f, 0.0000f, 0.00f, 2.50f, 0.00f,  // hips
        0.9076f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // body
        -0.8029f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // head
        0.3840f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // jaw
        -0.0890f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard
        0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard_tip
        0.0000f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // boulder
        -0.3491f, 0.0000f, 0.8727f, 0.00f, 0.00f, 0.00f,  // arm_r
        -0.3491f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_r
        -0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_r
        -0.4129f, 0.5148f, -0.7399f, 0.00f, 0.00f, 0.00f,  // chain_r
        -2.2689f, -0.3491f, -1.2217f, 0.00f, 0.00f, 0.00f,  // arm_l
        -1.7453f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_l
        -0.5236f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_l
        2.8938f, 0.7006f, -1.1369f, 0.00f, 0.00f, 0.00f,  // chain_l
        -1.5568f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin
        -0.8378f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin_back
        -1.0821f, 0.0000f, 0.1396f, 0.00f, 0.00f, 0.00f,  // leg_r
        1.1519f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_r
        -0.0698f, 0.0000f, -0.1047f, 0.00f, 0.00f, 0.00f,  // foot_r
        -0.6981f, 0.0000f, -0.1396f, 0.00f, 0.00f, 0.00f,  // leg_l
        0.9774f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_l
        -0.2793f, 0.0000f, 0.0873f, 0.00f, 0.00f, 0.00f,  // foot_l
    )
    val REGROW_EMPTY = floatArrayOf(
        0.0000f, 0.0000f, 0.0000f, 0.00f, -1.50f, 0.00f,  // hips
        0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // body
        -0.4887f, 0.0000f, 0.0000f, 0.00f, -3.00f, 2.00f,  // head
        0.4189f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // jaw
        0.2670f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard
        0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard_tip
        0.0000f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // boulder
        -0.1047f, 0.0000f, 0.2094f, 0.00f, 0.00f, 0.00f,  // arm_r
        -0.2094f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_r
        0.0000f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_r
        0.1332f, 0.0643f, -0.1995f, 0.00f, 0.00f, 0.00f,  // chain_r
        -0.0698f, 0.0000f, -0.1745f, 0.00f, 0.00f, 0.00f,  // arm_l
        -0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_l
        0.0000f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_l
        0.0662f, -0.0420f, 0.1694f, 0.00f, 0.00f, 0.00f,  // chain_l
        -0.3211f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin
        -0.1047f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin_back
        -0.2443f, 0.0000f, 0.1047f, 0.00f, 0.00f, 0.00f,  // leg_r
        0.3840f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_r
        -0.1396f, 0.0000f, -0.1047f, 0.00f, 0.00f, 0.00f,  // foot_r
        -0.1745f, 0.0000f, -0.0873f, 0.00f, 0.00f, 0.00f,  // leg_l
        0.3142f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_l
        -0.1396f, 0.0000f, 0.0873f, 0.00f, 0.00f, 0.00f,  // foot_l
    )
    val CURSE = floatArrayOf(
        0.0000f, 0.0000f, 0.0000f, 0.00f, -1.69f, 0.00f,  // hips
        -0.2094f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // body
        -0.9774f, 0.0000f, 0.0000f, 0.00f, -3.00f, 3.00f,  // head
        0.6632f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // jaw
        1.0088f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard
        0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard_tip
        0.5934f, 0.5236f, 0.2094f, 6.00f, 42.23f, 7.99f,  // boulder
        -0.4887f, 0.0000f, 2.0595f, 0.00f, 0.00f, 0.00f,  // arm_r
        -0.1396f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_r
        0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_r
        -0.1193f, 0.5457f, -2.1522f, 0.00f, 0.00f, 0.00f,  // chain_r
        -0.4887f, 0.0000f, -2.0595f, 0.00f, 0.00f, 0.00f,  // arm_l
        -0.1396f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_l
        0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_l
        -0.1193f, -0.5457f, 2.1522f, 0.00f, 0.00f, 0.00f,  // chain_l
        0.1047f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin
        0.2793f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin_back
        -0.1745f, 0.0000f, 0.2443f, 0.00f, 0.00f, 0.00f,  // leg_r
        0.2094f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_r
        -0.0349f, 0.0000f, -0.1047f, 0.00f, 0.00f, 0.00f,  // foot_r
        -0.1047f, 0.0000f, -0.2443f, 0.00f, 0.00f, 0.00f,  // leg_l
        0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_l
        -0.0698f, 0.0000f, 0.0873f, 0.00f, 0.00f, 0.00f,  // foot_l
    )
    val STAGGER = floatArrayOf(
        0.0000f, 0.0000f, 1.2566f, 0.00f, 14.29f, 0.00f,  // hips
        0.5236f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // body
        -0.3491f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // head
        0.2443f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // jaw
        -0.1484f, 0.0000f, -1.0053f, 0.00f, 0.00f, 0.00f,  // beard
        0.1745f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // beard_tip
        0.3491f, 0.6981f, 0.5236f, -6.69f, 60.03f, -25.79f,  // boulder
        -2.7925f, 0.0000f, -0.6109f, 0.00f, 0.00f, 0.00f,  // arm_r
        -1.8326f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_r
        -0.3491f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_r
        -2.1444f, -0.4714f, 0.6192f, 0.00f, 0.00f, 0.00f,  // chain_r
        -1.3963f, 0.0000f, 0.1745f, 0.00f, 0.00f, 0.00f,  // arm_l
        -0.5236f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // fore_l
        0.0000f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // hand_l
        0.7175f, 1.3480f, -0.7527f, 0.00f, 0.00f, 0.00f,  // chain_l
        -1.1519f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin
        -0.4538f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // loin_back
        -1.0472f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // leg_r
        1.3963f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_r
        -0.2443f, 0.0000f, -0.1047f, 0.00f, 0.00f, 0.00f,  // foot_r
        -0.6981f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // leg_l
        1.2217f, 0.0000f, 0.0000f, 0.00f, 0.00f, 0.00f,  // shin_l
        -0.2443f, 0.0000f, 0.0873f, 0.00f, 0.00f, 0.00f,  // foot_l
    )
    // </poses>
}
