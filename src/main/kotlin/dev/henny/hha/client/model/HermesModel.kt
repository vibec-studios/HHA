package dev.henny.hha.client.model

import dev.henny.hha.entity.boss.HermesEntity
import dev.henny.hha.entity.boss.HermesPose
import dev.henny.hha.client.anim.PoseMemory
import dev.henny.hha.client.anim.Spring
import dev.henny.hha.client.cine.CinePlayer
import dev.henny.hha.client.vfx.BossGlow
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
import kotlin.math.cos
import kotlin.math.sin

/** Render-Zustand von Hermes; zusätzlich zum Standard die Pose und die Geister-Positionen. */
class HermesRenderState : LivingEntityRenderState() {
    var bossPose = HermesPose.IDLE
    var previousPose = HermesPose.IDLE
    var poseTime = 0f
    var illusion = false

    /** Hermes im Dienst des Hades (siehe [HermesEntity.corrupted]): grün getöntes Trugbild, grünes Licht. */
    var corrupted = false
    var entityId = 0
    var stage = 1
    var vulnerable = true

    /** Hält er die Beute-Kugel in der Linken (siehe [HermesEntity.loot])? */
    var loot = false
    var speed = 0f
    var afterimages: List<net.minecraft.util.math.Vec3d> = emptyList()

    /** Überarbeitung 3 (P07): Animations-Gedächtnis dieses Wesens (Posen-Überblendung, Federn); vom Renderer gesetzt. */
    internal var anim: HermesAnim? = null

    /** Schräglage-Ziel aus der Drehrate (Grad, gedeckelt), vom Renderer gemessen. */
    var turnRate = 0f
}

/**
 * Animations-Zustand je Hermes-Wesen (Überarbeitung 3, PLAN 2.1 Punkt 3 / 2.4): zuletzt gezeigte Pose ([memory]),
 * Posenuhr ab dem ersten gezeigten Bild ([clockStart], REF T14 Punkt 2) und vier weiche Teile mit [Spring]:
 * Schwungfedern ([wing]), Anlegen der Flügel im Stoß ([fold]), Chlamys ([cape]) und Schräglage ([bank]); dazu das
 * Hängen der Flügel in der Erschöpfung ([droop]). Nur Darstellung.
 */
internal class HermesAnim {
    val memory = PoseMemory(HermesModel.BLEND_SIZE)
    val target = FloatArray(HermesModel.BLEND_SIZE)
    val wing = Spring(HermesModel.WING_FREQ, HermesModel.WING_DAMP)
    val fold = Spring(HermesModel.FOLD_FREQ, HermesModel.FOLD_DAMP)
    val cape = Spring(HermesModel.CAPE_FREQ, HermesModel.CAPE_DAMP)
    val bank = Spring(HermesModel.BANK_FREQ, HermesModel.BANK_DAMP)
    val droop = Spring(HermesModel.DROOP_FREQ, HermesModel.DROOP_DAMP)

    /** Posenuhr: welche Pose (samt Wechsel-Alter) zuletzt gesehen und ab welcher Zeit (Alter + Zwischenbild) sie läuft. */
    var clockPose = Int.MIN_VALUE
    var clockChanged = Int.MIN_VALUE
    var clockStart = 0f

    /** Letzte Zeit, zu der die Federn liefen, und die Pose, die dabei stand (für Stöße beim Wechsel). */
    var springTime = Float.NaN
    var springPose = Int.MIN_VALUE

    /** Drehrate: letzter Körper-Blick und Zeit. */
    var lastYaw = Float.NaN
    var lastYawTime = 0f
    var lastSeen = 0L
}

/**
 * Hermes (Geometrie: tools/hha_assets/hermes_model.py). Die Teile des früheren Schattens stehen noch im generierten
 * Modell, bleiben aber immer verborgen (Überarbeitung 2: Der Schatten des Boten entfällt).
 *
 * Jede Pose ist eine Zeitachse aus Schlüsselposen ([HermesKeys], generiert aus `POSES` der Python-Datei, also exakt
 * dieselben Winkel wie in der Vorschau). Die Zeitmarken kommen aus [BossTimings.Hermes].
 * Beim Posenwechsel wird von der Endhaltung der alten Pose weich (Ausholen) oder hart (Schlag, Stoß) übergeblendet.
 * Darüber laufen Schweben, Atmen, Flügelschlag, Chlamys-Welle und das Zucken der kleinen Flügel.
 */
class HermesModel(root: ModelPart) : EntityModel<HermesRenderState>(root) {

    private fun path(names: Array<String>): ModelPart {
        var part = root
        for (n in names) part = part.getChild(n)
        return part
    }

    /** Knochen in der Reihenfolge der Schlüsselposen. */
    private val bones: Array<ModelPart> = Array(HermesKeys.BONES.size) { path(HermesKeys.BONES[it]) }
    private val lightParts = HermesKeys.LIGHT.map(::path)
    private val shadeParts = HermesKeys.SHADE.map(::path)

    private val body = bones[B_ROOT]
    private val torso = bones[B_TORSO]
    private val head = bones[B_HEAD]
    private val armR = bones[B_ARM_R]
    private val foreR = bones[B_FORE_R]
    private val caduceus = bones[B_CADUCEUS]
    private val armL = bones[B_ARM_L]
    private val foreL = bones[B_FORE_L]
    private val legR = bones[B_LEG_R]
    private val shinR = bones[B_SHIN_R]
    private val legL = bones[B_LEG_L]
    private val shinL = bones[B_SHIN_L]
    private val backR = bones[B_WING_BACK_R]
    private val backForeR = bones[B_WING_FORE_R]
    private val backHandR = bones[B_WING_HAND_R]
    private val backL = bones[B_WING_BACK_L]
    private val backForeL = bones[B_WING_FORE_L]
    private val backHandL = bones[B_WING_HAND_L]
    private val lootOrb = bones[B_LOOT]
    private val wingHeadR = head.getChild("wing_head_r")
    private val wingHeadL = head.getChild("wing_head_l")
    private val cadWingR = caduceus.getChild("caduceus_wing_r")
    private val cadWingL = caduceus.getChild("caduceus_wing_l")
    private val wingFootR = shinR.getChild("wing_foot_r")
    private val wingFootL = shinL.getChild("wing_foot_l")
    private val scroll = foreL.getChild("scroll")
    private val letter = foreL.getChild("letter")
    private val cape1 = bones[B_CAPE1]

    private val from = FloatArray(SIZE)
    private val to = FloatArray(SIZE)
    private val auxFrom = FloatArray(AUX)
    private val auxTo = FloatArray(AUX)

    /** Ergebnis der Überblendung (Knochen + Hilfswerte), wiederverwendet je Bild. */
    private val shownLocal = FloatArray(BLEND_SIZE)

    override fun setAngles(state: HermesRenderState) {
        super.setAngles(state)
        val t = state.age
        val e = state.poseTime
        val blend = smooth(e / enter(state.bossPose))
        val shown = blendPose(state, e)
        for (i in 0 until HermesKeys.BONES.size) {
            val part = bones[i]
            val o = i * 6
            part.pitch = shown[o]
            part.yaw = shown[o + 1]
            part.roll = shown[o + 2]
            part.originX += shown[o + 3]
            part.originY += shown[o + 4]
            part.originZ += shown[o + 5]
        }
        val hover = shown[SIZE + A_HOVER]
        val beatAmp = shown[SIZE + A_BEAT]
        val look = shown[SIZE + A_LOOK]
        val breath = shown[SIZE + A_BREATH]

        // Schweben und Atmen: Ruhe ist nie starr.
        val bob = sin(t * 0.09f)
        body.originY += bob * 0.9f * hover
        torso.pitch += sin(t * (0.12f + breath * 0.18f)) * (0.015f + breath * 0.05f)
        armR.roll += bob * 0.03f * hover
        armL.roll -= bob * 0.04f * hover
        legL.pitch -= bob * 0.04f * hover
        legR.pitch += bob * 0.03f * hover

        // Blick folgt dem Ziel, nur in ruhigen Posen.
        head.pitch += state.pitch * MathHelper.RADIANS_PER_DEGREE * 0.5f * look
        head.yaw += MathHelper.clamp(state.relativeHeadYaw, -60f, 60f) * MathHelper.RADIANS_PER_DEGREE * 0.6f * look

        // Langsames Mitschwingen der Beine, wenn er sich am Boden bewegt.
        val swing = sin(state.limbSwingAnimationProgress * 0.6662f) * state.limbSwingAmplitude * 0.5f * hover
        legR.pitch += swing
        legL.pitch -= swing

        // Großer Flügelschlag (40er Zyklus in Ruhe, schneller im Flug); die Hand schwingt dem Oberarm hinterher.
        val beatSpeed = MathHelper.TAU / 40f * (1f + beatAmp * 0.8f + state.speed * 0.6f)
        val amp = beatAmp * 0.32f + state.speed * 0.05f
        val beat = sin(t * beatSpeed) * amp
        val late = sin(t * beatSpeed - 0.9f) * amp
        backR.roll += beat
        backForeR.roll += late * 0.5f
        backHandR.roll += late * 0.7f
        backL.roll -= beat
        backForeL.roll -= late * 0.5f
        backHandL.roll -= late * 0.7f

        // Kleine Flügel an Hut, Sandalen und Stab zucken schneller als die großen.
        val flap = sin(t * (0.7f + state.speed * 0.6f)) * (0.22f + state.speed * 0.12f)
        wingHeadR.yaw += flap
        wingHeadL.yaw -= flap
        wingFootR.yaw += flap * 1.3f
        wingFootL.yaw -= flap * 1.3f
        cadWingR.yaw += flap * 0.6f
        cadWingL.yaw -= flap * 0.6f

        // Chlamys: Welle läuft phasenversetzt nach unten, stärker bei Tempo.
        val wave = 0.06f + state.speed * 0.06f
        bones[B_CAPE1].pitch += sin(t * 0.13f) * wave
        bones[B_CAPE2].pitch += sin(t * 0.17f + 0.9f) * wave * 1.4f
        bones[B_CAPE3].pitch += sin(t * 0.21f + 1.8f) * wave * 1.8f

        secondary(state, t, hover)
        extras(state, t, e, blend)
        visibility(state, blend)
    }

    /**
     * Überblendung aus der zuletzt **gezeigten** Pose ([PoseMemory], PLAN 2.1 Punkt 3): Zwei Wechsel kurz hintereinander
     * springen nicht mehr. Ohne Gedächtnis (z. B. Vorschau ohne Wesen) wie bisher aus der Endhaltung der vorigen Pose.
     */
    private fun blendPose(state: HermesRenderState, e: Float): FloatArray {
        val anim = state.anim
        sample(state.bossPose, e, to)
        aux(state.bossPose, auxTo)
        if (anim != null) {
            System.arraycopy(to, 0, anim.target, 0, SIZE)
            System.arraycopy(auxTo, 0, anim.target, SIZE, AUX)
            // Die Pose läuft am Client seit [e] Ticks: Beginn der Überblendung = jetzt − e (auch für Nachzügler richtig).
            val now = state.age
            return anim.memory.blend(state.bossPose, anim.target, now, enter(state.bossPose), restart = false)
        }
        val blend = smooth(e / enter(state.bossPose))
        sample(state.previousPose, 1000f, from)
        aux(state.previousPose, auxFrom)
        for (i in 0 until SIZE) {
            shownLocal[i] = if (i % 6 == 1 || i % 6 == 2) lerpAngle(blend, from[i], to[i]) else MathHelper.lerp(blend, from[i], to[i])
        }
        for (i in 0 until AUX) shownLocal[SIZE + i] = MathHelper.lerp(blend, auxFrom[i], auxTo[i])
        return shownLocal
    }

    /**
     * Nachschwingen (PLAN 2.4): Schwungfedern federn beim Abbremsen (SKID) und bei Landungen nach, legen sich im Stoß
     * an, die Chlamys zieht dem Tempo nach, im Flug neigt er sich in die Kurve (höchstens [BANK_MAX_DEG]) und in der
     * Erschöpfung hängen die Flügel. Beim Sprechen nickt der Kopf leicht im Takt des Textes (kein Kiefer im Modell).
     * Alles über [Spring] je Wesen; reine Darstellung.
     */
    private fun secondary(state: HermesRenderState, t: Float, hover: Float) {
        val anim = state.anim ?: return
        val dt = if (anim.springTime.isNaN()) 0f else (t - anim.springTime).coerceIn(0f, 4f)
        val first = anim.springTime.isNaN() || t < anim.springTime - 0.01f
        anim.springTime = t
        if (first) {
            anim.wing.reset(); anim.fold.reset(); anim.cape.reset(); anim.bank.reset(); anim.droop.reset()
            anim.springPose = state.bossPose
        }
        val pose = state.bossPose
        if (pose != anim.springPose) {
            when (pose) {
                HermesPose.SKID -> { anim.wing.kick(SKID_WING_KICK); anim.cape.kick(SKID_CAPE_KICK) }
                HermesPose.DASH -> anim.fold.kick(DASH_FOLD_KICK)
                HermesPose.STUCK, HermesPose.STUMBLE, HermesPose.KNEEL, HermesPose.STAGGER -> anim.wing.kick(LAND_WING_KICK)
            }
            anim.springPose = pose
        }
        val dashing = pose == HermesPose.DASH || pose == HermesPose.DASH_WINDUP || pose == HermesPose.DIVE
        val exhausted = pose == HermesPose.KNEEL && state.stage >= 4 && state.vulnerable && !state.illusion
        val wing = anim.wing.step(-state.speed * WING_SPEED_SWEEP, dt)
        val fold = anim.fold.step(if (dashing) FOLD_TARGET else 0f, dt)
        val cape = anim.cape.step((state.speed * CAPE_SPEED).coerceAtMost(CAPE_MAX), dt)
        val flying = smooth(state.speed / BANK_SPEED_FULL) * (0.4f + 0.6f * hover.coerceIn(0f, 1f))
        val bankTarget = (state.turnRate * BANK_GAIN * flying).coerceIn(-BANK_MAX_DEG, BANK_MAX_DEG) * MathHelper.RADIANS_PER_DEGREE
        val bank = anim.bank.step(bankTarget, dt).coerceIn(-BANK_MAX_DEG * MathHelper.RADIANS_PER_DEGREE, BANK_MAX_DEG * MathHelper.RADIANS_PER_DEGREE)
        val droop = anim.droop.step(if (exhausted) DROOP_TARGET else 0f, dt)

        // Schwungfedern (Hand) am stärksten, der Unterarm halb; links gespiegelt wie der Flügelschlag.
        backHandR.roll += wing + fold * FOLD_HAND
        backForeR.roll += wing * 0.45f + fold
        backHandL.roll -= wing + fold * FOLD_HAND
        backForeL.roll -= wing * 0.45f + fold
        // Erschöpfung: die großen Flügel sinken am Schulteransatz, die Hand hängt nach.
        backR.roll -= droop
        backL.roll += droop
        backHandR.roll -= droop * DROOP_HAND
        backHandL.roll += droop * DROOP_HAND
        // Chlamys zieht nach hinten (positives Nicken schwingt den Saum nach hinten), unten stärker.
        bones[B_CAPE1].pitch += cape * 0.5f
        bones[B_CAPE2].pitch += cape * 0.8f
        bones[B_CAPE3].pitch += cape
        body.roll += bank * BANK_SIGN
        // Lippen: kein Kiefer im Modell, daher ein leichtes Nicken im Takt des Textes (≤ 3°).
        if (!state.illusion) head.pitch += CinePlayer.mouthOf(state.entityId) * MOUTH_NOD_DEG * MathHelper.RADIANS_PER_DEGREE
    }

    /** Bewegungen, die nur in einer Pose laufen (Zeichnen, Werfen, Zerren, Taumeln). */
    private fun extras(state: HermesRenderState, t: Float, e: Float, blend: Float) {
        when (state.bossPose) {
            HermesPose.CAST -> {
                // Die freie Hand zeichnet einen Kreis in die Luft.
                armL.pitch += sin(t * 0.3f) * 0.22f * blend
                armL.yaw += cos(t * 0.3f) * 0.22f * blend
            }
            HermesPose.TAUNT -> {
                // Wirft die Beute-Kugel hoch und fängt sie wieder (alle 16 Ticks).
                val k = (t % 16f) / 16f
                lootOrb.originY -= 4f * k * (1f - k) * 9f * blend
                torso.pitch += sin(t * 0.85f) * 0.04f
                head.pitch += sin(t * 0.85f) * 0.05f
            }
            HermesPose.STUCK -> {
                val tug = abs(sin(t * 0.45f))
                torso.pitch -= tug * 0.14f * blend
                armR.pitch += tug * 0.1f * blend
                armL.pitch += tug * 0.1f * blend
            }
            HermesPose.STAGGER -> {
                val shake = sin(t * 2.3f) * 0.05f
                body.roll += shake
                head.roll += shake * 1.5f
            }
            HermesPose.STUMBLE -> {
                // Abstehende Federn: die Flügel zittern nach dem Sturz.
                val quiver = sin(t * 3.1f) * 0.06f * MathHelper.clamp(1f - (e - 8f) / 30f, 0f, 1f)
                backR.roll += quiver
                backL.roll += quiver
            }
        }
    }

    private fun visibility(state: HermesRenderState, blend: Float) {
        for (p in lightParts) p.visible = true
        // Teile des früheren Schattens (Kapuze, Rauchbeine, Münzen …): immer verborgen.
        for (p in shadeParts) p.visible = false
        cadWingL.visible = true
        lootOrb.visible = state.loot
        scroll.visible = state.bossPose == HermesPose.READ || (state.previousPose == HermesPose.READ && blend < 0.5f)
        letter.visible = state.bossPose == HermesPose.MESSAGE
    }

    /** Endhaltung bzw. aktuelle Haltung einer Pose nach [e] Ticks. */
    private fun sample(pose: Int, e: Float, out: FloatArray) {
        val H = BossTimings.Hermes
        val hard = BossTimings.Anim.BLEND_HARD.toFloat()
        when (pose) {
            HermesPose.DASH_WINDUP -> copy(HermesKeys.DASH_WINDUP, out)
            HermesPose.DASH -> copy(HermesKeys.DASH, out)
            // 0–6 bremsen (Fersen voraus), dann auf ein Knie rutschen und gehalten (offen).
            HermesPose.SKID -> seg(e, H.SKID_BRAKE.toFloat(), H.SKID_BRAKE + 6f, HermesKeys.SKID_BRAKE, HermesKeys.SKID_KNEEL, out)
            // 0–8 Sturz, dann auf der Hand gefangen und gehalten (offen).
            HermesPose.STUMBLE -> seg(e, H.STUMBLE_FALL * 0.5f, H.STUMBLE_FALL.toFloat(), HermesKeys.STUMBLE_FALL, HermesKeys.STUMBLE, out)
            HermesPose.FAN -> {
                // Ausholen, Wurf 1 (Treffer bei FAN_THROW_1), neu ausholen, Wurf 2, danach offene Erholung.
                val t1 = H.FAN_THROW_1.toFloat()
                val t2 = H.FAN_THROW_2.toFloat()
                when {
                    e < t1 - hard -> copy(HermesKeys.FAN_WINDUP, out)
                    e < t1 -> seg(e, t1 - hard, t1, HermesKeys.FAN_WINDUP, HermesKeys.FAN_THROW, out)
                    e < t1 + 4f -> copy(HermesKeys.FAN_THROW, out)
                    e < t1 + 10f -> seg(e, t1 + 4f, t1 + 10f, HermesKeys.FAN_THROW, HermesKeys.FAN_WINDUP, out)
                    e < t2 - hard -> copy(HermesKeys.FAN_WINDUP, out)
                    e < t2 -> seg(e, t2 - hard, t2, HermesKeys.FAN_WINDUP, HermesKeys.FAN_THROW, out)
                    else -> seg(e, t2 + 4f, t2 + 10f, HermesKeys.FAN_THROW, HermesKeys.FAN_RECOVER, out)
                }
            }
            HermesPose.ASCEND -> copy(HermesKeys.ASCEND, out)
            HermesPose.SLAM -> copy(HermesKeys.SLAM_WINDUP, out)
            HermesPose.STUCK -> copy(HermesKeys.STUCK, out)
            HermesPose.CAST -> copy(HermesKeys.CAST, out)
            HermesPose.PLANT -> {
                // Stab hoch, Stoß in den Boden bei TWINS_PLANT_STRIKE, dann halb kniend dahinter halten.
                val strike = H.TWINS_PLANT_STRIKE.toFloat()
                seg(e, strike - hard, strike, HermesKeys.PLANT_RAISE, HermesKeys.PLANT_HOLD, out)
            }
            HermesPose.SNAP -> {
                val grab = H.SNAP_WINDUP.toFloat()
                seg(e, grab - hard, grab, HermesKeys.SNAP_WINDUP, HermesKeys.SNAP_GRAB, out)
            }
            HermesPose.TAUNT -> copy(HermesKeys.TAUNT, out)
            HermesPose.THROW -> {
                // Speerwurf-Impuls alle 6 Ticks: 2 Ticks vorschnellen, 4 Ticks neu ausholen.
                val k = e % 6f
                if (k < 2f) seg(k, 0f, 2f, HermesKeys.THROW, HermesKeys.THROW_RELEASE, out)
                else seg(k, 2f, 6f, HermesKeys.THROW_RELEASE, HermesKeys.THROW, out)
            }
            HermesPose.DIVE -> {
                val fall = H.DIVE_WINDUP.toFloat()
                seg(e, fall - hard, fall, HermesKeys.DIVE_WINDUP, HermesKeys.DIVE, out)
            }
            HermesPose.KNEEL -> copy(HermesKeys.KNEEL, out)
            HermesPose.STAGGER -> copy(HermesKeys.STAGGER, out)
            HermesPose.READ -> copy(HermesKeys.READ, out)
            HermesPose.MESSAGE -> copy(HermesKeys.LETTER, out)
            HermesPose.FALL -> copy(HermesKeys.FALL, out)
            else -> copy(HermesKeys.IDLE, out)
        }
    }

    /** Überblendzeit beim Eintritt in eine Pose: Ausholen weich, Stoß und Treffer hart. */
    private fun enter(pose: Int): Float = when (pose) {
        HermesPose.DASH_WINDUP -> BossTimings.Hermes.DASH_WINDUP_BLEND.toFloat()
        HermesPose.SLAM -> BossTimings.Hermes.SLAM_WINDUP_BLEND.toFloat()
        HermesPose.DASH, HermesPose.SKID, HermesPose.STUMBLE, HermesPose.THROW -> BossTimings.Anim.BLEND_HARD.toFloat()
        HermesPose.STUCK -> BossTimings.Hermes.STUCK_IMPACT.toFloat()
        HermesPose.FAN, HermesPose.SNAP, HermesPose.PLANT, HermesPose.DIVE -> BossTimings.Anim.BLEND_SOFT_MIN.toFloat()
        else -> BossTimings.Anim.BLEND_SOFT.toFloat()
    }

    /** Hilfswerte je Pose: Schweben, Stärke des Flügelschlags, Blick zum Ziel, schwerer Atem. */
    private fun aux(pose: Int, o: FloatArray) {
        o[A_HOVER] = 1f
        o[A_BEAT] = 0.35f
        o[A_LOOK] = 1f
        o[A_BREATH] = 0f
        when (pose) {
            HermesPose.DASH, HermesPose.DASH_WINDUP, HermesPose.DIVE -> { o[A_HOVER] = 0f; o[A_BEAT] = 0f; o[A_LOOK] = 0f }
            HermesPose.ASCEND -> { o[A_BEAT] = 1.4f; o[A_LOOK] = 0f }
            HermesPose.CAST -> o[A_BEAT] = 0.7f
            HermesPose.SLAM, HermesPose.FAN, HermesPose.THROW, HermesPose.SNAP -> { o[A_BEAT] = 0.15f; o[A_LOOK] = 0f }
            HermesPose.SKID, HermesPose.STUCK, HermesPose.PLANT, HermesPose.KNEEL -> {
                o[A_HOVER] = 0f; o[A_BEAT] = 0.05f; o[A_LOOK] = 0.3f; o[A_BREATH] = 1f
            }
            HermesPose.STUMBLE -> { o[A_HOVER] = 0f; o[A_BEAT] = 0.1f; o[A_LOOK] = 0f; o[A_BREATH] = 1f }
            HermesPose.STAGGER -> { o[A_BEAT] = 0.9f; o[A_LOOK] = 0f; o[A_BREATH] = 0.6f }
            HermesPose.FALL -> { o[A_HOVER] = 0f; o[A_BEAT] = 0.2f; o[A_LOOK] = 0f }
            HermesPose.READ, HermesPose.MESSAGE -> o[A_BEAT] = 0.1f
        }
    }

    private fun copy(key: FloatArray, out: FloatArray) = System.arraycopy(key, 0, out, 0, SIZE)

    /** Von [a] nach [b] zwischen den Ticks [t0] und [t1], weich; davor a, danach b. */
    private fun seg(e: Float, t0: Float, t1: Float, a: FloatArray, b: FloatArray, out: FloatArray) {
        val k = smooth((e - t0) / (t1 - t0))
        for (i in 0 until SIZE) out[i] = if (i % 6 == 1 || i % 6 == 2) lerpAngle(k, a[i], b[i]) else MathHelper.lerp(k, a[i], b[i])
    }

    /**
     * Liest die Weltpositionen für das Leuchten aus den gestellten Knochen. Die Koordinaten stammen aus
     * tools/hha_assets/hermes_model.py. [matrices] steht wie beim Zeichnen der Modellteile, [cam] ist die Kameraposition.
     */
    fun readAnchors(matrices: MatrixStack, cam: Vec3d, out: BossGlow.HermesFrame) {
        val scratch = Vector3f()
        fun at(x: Float, y: Float, z: Float): Vec3d {
            matrices.peek().positionMatrix.transformPosition(x / 16f, y / 16f, z / 16f, scratch)
            return Vec3d(scratch.x + cam.x, scratch.y + cam.y, scratch.z + cam.z)
        }
        fun inside(part: ModelPart, block: () -> Unit) {
            matrices.push()
            part.applyTransform(matrices)
            block()
            matrices.pop()
        }
        fun wing(back: ModelPart, fore: ModelPart, hand: ModelPart, side: Float): Pair<List<Vec3d>, Vec3d> {
            val bone = ArrayList<Vec3d>(4)
            var tip = Vec3d.ZERO
            inside(back) {
                bone.add(at(0f, 0f, 0f))
                inside(fore) {
                    bone.add(at(0f, 0f, 0f))
                    inside(hand) {
                        bone.add(at(0f, 0f, 0f))
                        bone.add(at(side * 14f, 0f, 0f))
                        tip = at(side * 22f, 42f, 1f)
                    }
                }
            }
            return bone to tip
        }

        inside(root) {
            inside(body) {
                inside(legR) { inside(shinR) { out.footR = at(-2.4f, 10.5f, 1f) } }
                inside(legL) { inside(shinL) { out.footL = at(2.4f, 10.5f, 1f) } }
                inside(torso) {
                    out.chest = at(0f, -8f, -5.4f)
                    inside(head) {
                        out.eyeR = at(-1.5f, -5.5f, -4.7f)
                        out.eyeL = at(1.5f, -5.5f, -4.7f)
                        out.halo = at(0f, -6f, 7f)
                        out.haloRight = at(1f, -6f, 7f).subtract(out.halo).normalize()
                        out.haloUp = at(0f, -7f, 7f).subtract(out.halo).normalize()
                    }
                    inside(armR) {
                        inside(foreR) {
                            out.handR = at(0f, 12f, 0f)
                            inside(caduceus) {
                                out.orb = at(0f, -36.5f, 0f)
                                out.staffTop = at(0f, -34f, 0f)
                                out.staffBottom = at(0f, 23f, 0f)
                            }
                        }
                    }
                    inside(armL) {
                        inside(foreL) {
                            out.handL = at(0f, 12f, 0f)
                            inside(lootOrb) { out.lootOrb = at(0f, 0f, 0f) }
                        }
                    }
                    // Der rechte Flügel zeigt nach -x, der linke nach +x (siehe back_wing im Generator).
                    val right = wing(backR, backForeR, backHandR, -1f)
                    out.wingR = right.first
                    out.tipR = right.second
                    val left = wing(backL, backForeL, backHandL, 1f)
                    out.wingL = left.first
                    out.tipL = left.second
                }
            }
        }
    }

    companion object {
        // Indizes in HermesKeys.BONES (Reihenfolge wie BONES in hermes_model.py).
        private const val B_ROOT = 0
        private const val B_TORSO = 1
        private const val B_HEAD = 2
        private const val B_ARM_R = 3
        private const val B_FORE_R = 4
        private const val B_CADUCEUS = 5
        private const val B_ARM_L = 6
        private const val B_FORE_L = 7
        private const val B_LEG_R = 8
        private const val B_SHIN_R = 9
        private const val B_LEG_L = 10
        private const val B_SHIN_L = 11
        private const val B_CAPE1 = 12
        private const val B_CAPE2 = 13
        private const val B_CAPE3 = 14
        private const val B_WING_BACK_R = 15
        private const val B_WING_FORE_R = 16
        private const val B_WING_HAND_R = 17
        private const val B_WING_BACK_L = 18
        private const val B_WING_FORE_L = 19
        private const val B_WING_HAND_L = 20
        private const val B_LOOT = 21
        private const val SIZE = 22 * 6

        /** Knochen und Hilfswerte zusammen (für [PoseMemory]). */
        const val BLEND_SIZE = SIZE + 4

        // Nachschwingen (Überarbeitung 3, P07). Frequenz in rad/Tick, Dämpfung 1 = kritisch. Winkel in Bogenmaß.
        const val WING_FREQ = 0.42f
        const val WING_DAMP = 0.28f
        const val FOLD_FREQ = 0.55f
        const val FOLD_DAMP = 0.55f
        const val CAPE_FREQ = 0.3f
        const val CAPE_DAMP = 0.4f
        const val BANK_FREQ = 0.22f
        const val BANK_DAMP = 1f
        const val DROOP_FREQ = 0.16f
        const val DROOP_DAMP = 0.6f

        /** Stoß auf die Schwungfedern beim Abbremsen (rad/Tick). */
        const val SKID_WING_KICK = 0.16f
        const val SKID_CAPE_KICK = -0.1f
        /** Landungen, Abfangen, Taumeln: kleiner Stoß. */
        const val LAND_WING_KICK = 0.09f
        /** Im Stoß legen sich die Flügel an: Ziel und Anschub. */
        const val FOLD_TARGET = -0.18f
        const val DASH_FOLD_KICK = -0.12f
        const val FOLD_HAND = 1.3f
        /** Bei Tempo ziehen die Schwungfedern etwas nach hinten. */
        const val WING_SPEED_SWEEP = 0.06f
        const val CAPE_SPEED = 0.22f
        const val CAPE_MAX = 0.35f
        /** Schräglage: Grad je Grad/Tick Drehrate, ab diesem Tempo voll, Deckel (PLAN 2.4 / REF T14: 14°). */
        const val BANK_GAIN = 0.8f
        const val BANK_SPEED_FULL = 0.5f
        const val BANK_MAX_DEG = 14f
        /** Vorzeichen der Schräglage am Wurzelknochen (ungeprüft im Spiel: nach rechts drehen = rechte Schulter sinkt). */
        const val BANK_SIGN = -1f
        /** Erschöpfung: so tief sinken die Flügel (rad), die Hand folgt anteilig. */
        const val DROOP_TARGET = 0.32f
        const val DROOP_HAND = 0.6f
        /** Kopfnicken beim Sprechen, höchstens (Briefing: 3°). */
        const val MOUTH_NOD_DEG = 3f

        private const val A_HOVER = 0
        private const val A_BEAT = 1
        private const val A_LOOK = 2
        private const val A_BREATH = 3
        private const val AUX = 4

        private fun smooth(x: Float): Float {
            val k = MathHelper.clamp(x, 0f, 1f)
            return k * k * (3f - 2f * k)
        }

        private fun lerpAngle(k: Float, a: Float, b: Float): Float = a + MathHelper.wrapDegrees((b - a) * MathHelper.DEGREES_PER_RADIAN) * MathHelper.RADIANS_PER_DEGREE * k
    }
}

// -------------------------------------------------------------------------------------------------
// Renderer
// -------------------------------------------------------------------------------------------------

class HermesRenderer(context: EntityRendererFactory.Context) :
    LivingEntityRenderer<HermesEntity, HermesRenderState, HermesModel>(context, HermesModel(context.getPart(BossLayers.HERMES)), 0.7f) {

    private val texture = boss("hermes")
    private val ghostLayer = RenderLayers.entityTranslucentEmissive(texture)
    private val glowLayer = RenderLayers.eyes(boss("hermes_glow"))
    private val feathers = FeatherRig(model.rootPart, HhaModelData.hermesFeathers())

    /** Leuchtmaske der Federn nur an den Sandalenflügeln (schmale Kante), nicht an den großen Flügeln. */
    private val sandalFeathers = FeatherRig(model.rootPart, HhaModelData.hermesFeathers().filter { it.path.last().startsWith("wing_foot_") })

    init {
        addFeature(GlowFeature(this, { glowLayer }, ::glowTint))
        addFeature(object : FeatureRenderer<HermesRenderState, HermesModel>(this) {
            override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: HermesRenderState, limbAngle: Float, limbDistance: Float) {
                if (state.invisibleToPlayer) return
                feathers.submit(matrices, queue, FeatherDraw.LAYER, -1, LivingEntityRenderer.getOverlay(state, 0f), light)
                sandalFeathers.submit(matrices, queue.getBatchingQueue(1), FeatherDraw.GLOW_LAYER, sandalTint(state), OverlayTexture.DEFAULT_UV, FULL_BRIGHT)
                publishGlow(state, matrices)
            }
        })
    }

    /** Leuchten je Stage: Weiß, Dämmerviolett, Seelenblau, Sturmweiß; pulsiert und erlischt in der Erschöpfung. */
    private fun glowTint(state: HermesRenderState): Int {
        val power = BossGlow.targetPower(state.stage, state.vulnerable, state.bossPose, state.illusion)
        val alpha = MathHelper.clamp((0.55f + 0.45f * power) * (0.86f + 0.14f * sin(state.age * 0.19f)), 0.2f, 1f)
        val color = if (state.corrupted) 0x7BFF8E else when (state.stage) {
            2 -> 0xF0D2FF
            3 -> 0xB8F2FF
            4 -> 0xFFF4C2
            else -> 0xFFFFFF
        }
        return ((alpha * 255f).toInt() shl 24) or color
    }

    /** Die Federkanten an den Sandalen glimmen gedämpft (die Feder-Leuchtmaske ist sonst zu hell). */
    private fun sandalTint(state: HermesRenderState): Int {
        val c = glowTint(state)
        val alpha = ((c ushr 24) * 0.55f).toInt()
        return (alpha shl 24) or (c and 0xFFFFFF)
    }

    /** Reicht die Knochenpositionen ans Effekt-System weiter; die Matrizen stehen genau wie beim Modell. */
    private fun publishGlow(state: HermesRenderState, matrices: MatrixStack) {
        val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
        val frame = BossGlow.HermesFrame(state.entityId, state.stage, state.illusion, state.bossPose, state.speed, state.age, state.corrupted)
        frame.power = BossGlow.smoothPower(state.entityId, BossGlow.targetPower(state.stage, state.vulnerable, state.bossPose, state.illusion))
        frame.loot = state.loot
        model.readAnchors(matrices, cam, frame)
        BossGlow.submit(frame)
    }

    override fun createRenderState() = HermesRenderState()

    override fun getTexture(state: HermesRenderState): Identifier = texture

    override fun updateRenderState(entity: HermesEntity, state: HermesRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.bossPose = entity.pose
        state.previousPose = entity.previousPose
        val anim = animOf(entity)
        state.anim = anim
        state.poseTime = poseClock(anim, entity, tickDelta)
        state.turnRate = turnRate(anim, entity, tickDelta)
        state.illusion = entity.isIllusion
        state.entityId = entity.id
        state.corrupted = entity.corrupted
        state.stage = entity.stage
        state.vulnerable = entity.vulnerable
        state.loot = entity.loot
        val history = entity.history
        state.speed = if (history.size >= 2) history.last().distanceTo(history[history.size - 2]).toFloat().coerceAtMost(2f) else 0f
        // Nachbilder nur bei echtem Tempo; relativ zur aktuellen Position.
        val current = entity.getLerpedPos(tickDelta)
        state.afterimages = if (state.speed > 0.6f && history.size >= 4) {
            history.toList().dropLast(1).takeLast(6).reversed().map { it.subtract(current) }
        } else {
            emptyList()
        }
    }

    override fun scale(state: HermesRenderState, matrices: MatrixStack) {
        matrices.scale(SCALE, SCALE, SCALE)
    }

    override fun render(state: HermesRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue, cameraState: CameraRenderState) {
        for ((i, offset) in state.afterimages.withIndex()) {
            matrices.push()
            matrices.translate(offset.x, offset.y, offset.z)
            renderGhost(state, matrices, queue, (0.42f * (1f - i / 6f)))
            matrices.pop()
        }
        if (state.illusion) {
            renderGhost(state, matrices, queue, 0.62f + 0.12f * sin(state.age * 0.4f), publish = true)
            return
        }
        super.render(state, matrices, queue, cameraState)
    }

    /** Durchsichtige, vollhelle Kopie (Nachbild oder Trugbild). */
    private fun renderGhost(state: HermesRenderState, matrices: MatrixStack, queue: OrderedRenderCommandQueue, alpha: Float, publish: Boolean = false) {
        matrices.push()
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180f - state.bodyYaw))
        matrices.scale(-SCALE, -SCALE, SCALE)
        matrices.translate(0f, -1.501f, 0f)
        val color = (MathHelper.clamp((alpha * 255f).toInt(), 0, 255) shl 24) or (if (state.corrupted) 0x9CFFA8 else 0xFFE9B0)
        val ghosts = queue.getBatchingQueue(2)
        ghosts.submitModel(model, state, matrices, ghostLayer, FULL_BRIGHT, OverlayTexture.DEFAULT_UV, color, null, 0, null)
        // Das Modell wird später gezeichnet; für die Federn und Anker muss die Pose schon jetzt stehen.
        model.setAngles(state)
        feathers.submit(matrices, ghosts, FeatherDraw.GHOST_LAYER, color, OverlayTexture.DEFAULT_UV, FULL_BRIGHT)
        if (publish) publishGlow(state, matrices)
        matrices.pop()
    }

    override fun hasLabel(entity: HermesEntity, squaredDistance: Double): Boolean = false

    /**
     * Sichtbarkeits-Box (REF T14): Die ausgebreiteten Flügel reichen weit über die Trefferbox hinaus; ohne Zugabe
     * verschwände er am Bildrand, obwohl die Flügel noch im Bild sind. Nur fürs Zeichnen, die Trefferbox bleibt.
     */
    override fun getBoundingBox(entity: HermesEntity): net.minecraft.util.math.Box =
        super.getBoundingBox(entity).expand(CULL_SIDE, CULL_UP, CULL_SIDE)

    /** Gedächtnis je Wesen; Wesen, die es nicht mehr gibt, werden alle [PRUNE_EVERY] Aufrufe vergessen. */
    private fun animOf(entity: HermesEntity): HermesAnim {
        val world = entity.entityWorld
        if (++calls % PRUNE_EVERY == 0) anims.keys.removeIf { id -> world.getEntityById(id) == null }
        return anims.getOrPut(entity.id) { HermesAnim() }.also { it.lastSeen = calls.toLong() }
    }

    /**
     * Posenuhr (REF T14 Punkt 2): Pose und Wechsel-Alter setzt `HermesEntity.onTrackedDataSet` zusammen, also läuft
     * nie die neue Pose mit dem Alter der alten. Das Paket kommt aber mitten zwischen zwei Ticks an; `age −
     * poseChangedAge + tickDelta` begann deshalb bei bis zu einem Tick statt bei 0 (Sprung in die neue Pose). Jetzt
     * beginnt die Uhr beim ersten Bild, das die neue Pose zeigt, und läuft von dort stetig.
     */
    private fun poseClock(anim: HermesAnim, entity: HermesEntity, tickDelta: Float): Float {
        val now = entity.age + tickDelta
        if (entity.pose != anim.clockPose || entity.poseChangedAge != anim.clockChanged) {
            val fresh = anim.clockPose != Int.MIN_VALUE
            anim.clockPose = entity.pose
            anim.clockChanged = entity.poseChangedAge
            // Erstes Bild eines Wesens (Nachzügler, neu in Sicht): die Pose läuft schon seit dem Wechsel.
            anim.clockStart = if (fresh) now else entity.poseChangedAge.toFloat()
        }
        return (now - anim.clockStart).coerceAtLeast(0f)
    }

    /** Drehrate des Körpers in Grad je Tick (für die Schräglage), aus dem gezeichneten Körperblick gemessen. */
    private fun turnRate(anim: HermesAnim, entity: HermesEntity, tickDelta: Float): Float {
        val now = entity.age + tickDelta
        val yaw = MathHelper.lerpAngleDegrees(tickDelta, entity.lastBodyYaw, entity.bodyYaw)
        val last = anim.lastYaw
        val dt = now - anim.lastYawTime
        anim.lastYaw = yaw
        anim.lastYawTime = now
        if (last.isNaN() || dt <= 1e-3f || dt > 4f) return 0f
        return MathHelper.wrapDegrees(yaw - last) / dt
    }

    private val anims = HashMap<Int, HermesAnim>()
    private var calls = 0

    private companion object {
        /** Modellhöhe 64 Einheiten → 2,7 Blöcke. */
        const val SCALE = 0.675f

        /** Alle so viele Render-Aufrufe werden entfernte Wesen aus dem Gedächtnis gelöscht. */
        const val PRUNE_EVERY = 400

        /** Zugabe der Sichtbarkeits-Box: Spannweite der Flügel zur Seite, Flügelspitzen nach oben (Blöcke). */
        const val CULL_SIDE = 1.8
        const val CULL_UP = 1.2
    }
}

// <hermes-keys>
// Generiert von tools/hha_assets/hermes_model.py --sync-kotlin aus KEYS (= POSES); nicht von Hand ändern.
internal object HermesKeys {
    /** Knochen in Datenreihenfolge: root, torso, head, arm_r, fore_r, caduceus, arm_l, fore_l, leg_r, shin_r, leg_l, shin_l, cape1, cape2, cape3, wing_back_r, wing_fore_r, wing_hand_r, wing_back_l, wing_fore_l, wing_hand_l, loot_orb. */
    val BONES: Array<Array<String>> = arrayOf(arrayOf("root"), arrayOf("root", "torso"), arrayOf("root", "torso", "head"), arrayOf("root", "torso", "arm_r"), arrayOf("root", "torso", "arm_r", "fore_r"), arrayOf("root", "torso", "arm_r", "fore_r", "caduceus"), arrayOf("root", "torso", "arm_l"), arrayOf("root", "torso", "arm_l", "fore_l"), arrayOf("root", "leg_r"), arrayOf("root", "leg_r", "shin_r"), arrayOf("root", "leg_l"), arrayOf("root", "leg_l", "shin_l"), arrayOf("root", "torso", "cape1"), arrayOf("root", "torso", "cape1", "cape2"), arrayOf("root", "torso", "cape1", "cape2", "cape3"), arrayOf("root", "torso", "wing_back_r"), arrayOf("root", "torso", "wing_back_r", "wing_fore_r"), arrayOf("root", "torso", "wing_back_r", "wing_fore_r", "wing_hand_r"), arrayOf("root", "torso", "wing_back_l"), arrayOf("root", "torso", "wing_back_l", "wing_fore_l"), arrayOf("root", "torso", "wing_back_l", "wing_fore_l", "wing_hand_l"), arrayOf("root", "torso", "arm_l", "fore_l", "loot_orb"))
    /** Nur Hermes (aus bei corrupted). */
    val LIGHT: Array<Array<String>> = arrayOf(arrayOf("root", "torso", "head", "hat"), arrayOf("root", "torso", "head", "wing_head_r"), arrayOf("root", "torso", "head", "wing_head_l"), arrayOf("root", "leg_r", "shin_r", "light_calf_r"), arrayOf("root", "leg_l", "shin_l", "light_calf_l"), arrayOf("root", "leg_r", "shin_r", "wing_foot_r"), arrayOf("root", "leg_l", "shin_l", "wing_foot_l"), arrayOf("root", "torso", "arm_r", "fore_r", "caduceus", "light_serpent"), arrayOf("root", "torso", "arm_r", "fore_r", "caduceus", "light_orb"), arrayOf("root", "torso", "arm_r", "fore_r", "caduceus", "caduceus_wing_l"), arrayOf("root", "torso", "arm_l", "fore_l", "light_hand_l"), arrayOf("root", "torso", "wing_back_r", "light_feathers_back_r"), arrayOf("root", "torso", "wing_back_r", "wing_fore_r", "light_feathers_fore_r"), arrayOf("root", "torso", "wing_back_r", "wing_fore_r", "wing_hand_r", "light_feathers_hand_r"), arrayOf("root", "torso", "wing_back_l", "light_feathers_back_l"), arrayOf("root", "torso", "wing_back_l", "wing_fore_l", "light_feathers_fore_l"), arrayOf("root", "torso", "wing_back_l", "wing_fore_l", "wing_hand_l", "light_feathers_hand_l"))
    /** Nur der Schatten. */
    val SHADE: Array<Array<String>> = arrayOf(arrayOf("root", "torso", "head", "shade_cowl"), arrayOf("root", "leg_r", "shin_r", "shade_smoke_r"), arrayOf("root", "leg_l", "shin_l", "shade_smoke_l"), arrayOf("root", "shade_coins"), arrayOf("root", "torso", "arm_r", "fore_r", "caduceus", "shade_orb"), arrayOf("root", "torso", "arm_l", "fore_l", "shade_hand_l"), arrayOf("root", "torso", "wing_back_r", "shade_feathers_back_r"), arrayOf("root", "torso", "wing_back_r", "wing_fore_r", "shade_feathers_fore_r"), arrayOf("root", "torso", "wing_back_r", "wing_fore_r", "wing_hand_r", "shade_feathers_hand_r"), arrayOf("root", "torso", "wing_back_l", "shade_feathers_back_l"), arrayOf("root", "torso", "wing_back_l", "wing_fore_l", "shade_feathers_fore_l"), arrayOf("root", "torso", "wing_back_l", "wing_fore_l", "wing_hand_l", "shade_feathers_hand_l"))

    val IDLE = floatArrayOf(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0400f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, -0.1000f, 0.0400f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.1800f, 0.0000f, 0.0000f, 0.0000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.9670f, 0.0280f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, -0.2200f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0300f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val OPEN = floatArrayOf(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0400f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, -0.1000f, 0.0400f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.1800f, 0.0000f, 0.0000f, 0.0000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.9670f, 0.0280f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, -0.2200f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0300f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0800f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0800f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val DASH_WINDUP = floatArrayOf(0.6200f, 0.0000f, 0.0000f, 0.0000f, 7.0000f, 0.0000f, 0.3000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.7500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.3500f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.8070f, -1.5040f, -1.2080f, 0.0000f, 0.0000f, 0.0000f, 0.8500f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, -1.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.5500f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 1.7500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 1.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.8500f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4500f, -1.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.8500f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4500f, 1.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val DASH = floatArrayOf(1.3800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.9500f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.6540f, -1.4010f, -1.3900f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, -0.1800f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0200f, 0.0000f, 0.0400f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1800f, 0.0000f, -0.0400f, 0.0000f, 0.0000f, 0.0000f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, -0.7500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5500f, 0.7500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val SKID_BRAKE = floatArrayOf(-0.5500f, 0.0000f, 0.0000f, 0.0000f, 3.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.8500f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.6770f, 0.1210f, -1.4400f, 0.0000f, 0.0000f, 0.0000f, -1.2000f, 0.0000f, -0.7500f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.0500f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5500f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.7500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4500f, 0.8500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4500f, -0.8500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val SKID_KNEEL = floatArrayOf(0.1200f, 0.0000f, 0.0000f, 0.0000f, 15.0000f, 0.0000f, 0.4200f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.8500f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4000f, 0.3130f, -1.4700f, 0.0000f, 0.0000f, 0.0000f, -0.7500f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.6900f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 1.6600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2200f, 0.0000f, -0.0600f, 0.0000f, 0.0000f, 0.0000f, 1.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val STUMBLE_FALL = floatArrayOf(1.9500f, 0.0000f, 0.4500f, 0.0000f, 6.0000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, -0.6000f, 0.0000f, 1.4000f, 0.0000f, 0.0000f, 0.0000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.3670f, -1.2570f, -0.4090f, 0.0000f, 0.0000f, 0.0000f, -2.4000f, 0.0000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.6000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.9000f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.6000f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val STUMBLE = floatArrayOf(0.9500f, 0.0000f, 0.1800f, 0.0000f, 16.0000f, 0.0000f, 0.1000f, 0.2500f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, -0.7500f, 0.2000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 1.2500f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3570f, -0.8990f, 2.7560f, 0.0000f, 0.0000f, 0.0000f, -1.1500f, 0.0000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, 0.0000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 1.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.9500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.8000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.7000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.6000f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val FAN_WINDUP = floatArrayOf(-0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1200f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.2500f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 2.7240f, 0.2360f, 0.5980f, 0.0000f, 0.0000f, 0.0000f, -2.7500f, 0.0000f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, -1.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5500f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.7500f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val FAN_THROW = floatArrayOf(0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2800f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0390f, -0.2130f, -0.3360f, 0.0000f, 0.0000f, 0.0000f, -1.4500f, 0.0000f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.6000f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.7500f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val FAN_RECOVER = floatArrayOf(0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0370f, -0.0800f, -0.1150f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0300f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val ASCEND = floatArrayOf(-0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.6500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1600f, 0.0050f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0400f, 0.0000f, 0.0200f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0800f, 0.0000f, -0.0200f, 0.0000f, 0.0000f, 0.0000f, 0.1800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0300f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0300f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0300f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.7500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, -0.7500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val SLAM_WINDUP = floatArrayOf(-0.3200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.8500f, 0.0000f, -0.3200f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.5060f, 0.0180f, -0.3190f, 0.0000f, 0.0000f, 0.0000f, -2.8500f, 0.0000f, 0.3200f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 1.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4500f, 0.0000f, -0.0600f, 0.0000f, 0.0000f, 0.0000f, 1.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 1.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, -1.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val STUCK = floatArrayOf(0.0500f, 0.0000f, 0.0000f, 0.0000f, 15.0000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.6200f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.9250f, -0.1730f, 0.1810f, 0.0000f, 0.0000f, 0.0000f, -0.6800f, 0.0000f, 0.2600f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.6900f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 1.6900f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, -0.0600f, 0.0000f, 0.0000f, 0.0000f, 1.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val CAST = floatArrayOf(-0.0400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.7500f, 0.0000f, -0.3200f, 0.0000f, 0.0000f, 0.0000f, -1.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.8920f, -0.3110f, -0.0750f, 0.0000f, 0.0000f, 0.0000f, -1.3500f, 0.0000f, -0.7500f, 0.0000f, 0.0000f, 0.0000f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, 0.0300f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, -0.0300f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0800f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0800f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val PLANT_RAISE = floatArrayOf(0.1000f, 0.0000f, 0.0000f, 0.0000f, 4.0000f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.4500f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, -1.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 2.7290f, -0.2070f, -0.2850f, 0.0000f, 0.0000f, 0.0000f, -1.4500f, 0.0000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, -1.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.9500f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 1.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, 0.8500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4000f, -0.8500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val PLANT_HOLD = floatArrayOf(0.2500f, 0.0000f, 0.0000f, 0.0000f, 12.0000f, 0.0000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.0000f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.9490f, -0.3000f, 0.0060f, 0.0000f, 0.0000f, 0.0000f, -1.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.6000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 1.7500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, 1.6500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val SNAP_WINDUP = floatArrayOf(0.3000f, 0.0000f, 0.0000f, 0.0000f, 4.0000f, 0.0000f, 0.3500f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4500f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.3460f, 0.7960f, 0.2330f, 0.0000f, 0.0000f, 0.0000f, 0.7500f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, -1.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.1500f, 0.0000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 1.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4500f, 0.0000f, -0.0600f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.7000f, 0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4500f, -1.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.7000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4500f, 1.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val SNAP_GRAB = floatArrayOf(0.4500f, 0.0000f, 0.0000f, 0.0000f, 4.0000f, 0.0000f, 0.3500f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.7500f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.6500f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.4320f, 0.1820f, 0.0380f, 0.0000f, 0.0000f, 0.0000f, -2.1500f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.8000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.1000f, 0.0000f, -0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.7500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, -0.7500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val TAUNT = floatArrayOf(-0.3000f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, -0.2000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, -0.5000f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, -1.9500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 2.7150f, 0.4870f, 1.2920f, 0.0000f, 0.0000f, 0.0000f, -0.8500f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, -1.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5500f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val THROW = floatArrayOf(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.4000f, 0.0000f, 0.4200f, 0.0000f, 0.0000f, 0.0000f, -1.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.7410f, 0.5090f, -1.0860f, 0.0000f, 0.0000f, 0.0000f, -1.4500f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5500f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val THROW_RELEASE = floatArrayOf(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.5500f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.3700f, -1.4750f, -0.4680f, 0.0000f, 0.0000f, 0.0000f, -0.6000f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5500f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val DIVE_WINDUP = floatArrayOf(0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.7500f, 0.0000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, -1.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.6100f, -0.1940f, -0.1590f, 0.0000f, 0.0000f, 0.0000f, -0.7500f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, -1.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.9500f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 2.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.8500f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, 2.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.7500f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.6000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.7500f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.6000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val DIVE = floatArrayOf(2.8500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.9500f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0030f, 0.0210f, 2.9930f, 0.0000f, 0.0000f, 0.0000f, -2.9500f, 0.0000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0400f, 0.0000f, 0.0200f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, -0.0200f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4500f, -0.8000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4500f, 0.8000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val KNEEL = floatArrayOf(0.1800f, 0.0000f, 0.0000f, 0.0000f, 15.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.4200f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.6320f, -0.1570f, -0.0410f, 0.0000f, 0.0000f, 0.0000f, -1.4200f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.7500f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 1.6600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2200f, 0.0000f, -0.0600f, 0.0000f, 0.0000f, 0.0000f, 1.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, -0.9500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.9500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val STAGGER = floatArrayOf(-0.3000f, 0.0000f, 0.1800f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, -0.2000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.2500f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.9500f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.8880f, -0.0240f, 2.6800f, 0.0000f, 0.0000f, 0.0000f, -2.3500f, 0.0000f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, -1.8500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5000f, 0.0000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.8000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5000f, 0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val SMOKE_EMERGE = floatArrayOf(0.2500f, 0.0000f, 0.0000f, 0.0000f, 5.0000f, 0.0000f, 0.2500f, 0.6500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, -0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.7500f, 0.0000f, 0.7000f, 0.0000f, 0.0000f, 0.0000f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2080f, -0.8760f, -2.3070f, 0.0000f, 0.0000f, 0.0000f, -0.9000f, 0.0000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 1.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.0500f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, 1.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.7500f, 0.6500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4500f, -1.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.7500f, -0.6500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4500f, 1.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val SMOKE_SLASH = floatArrayOf(0.3500f, 0.0000f, 0.0000f, 0.0000f, 5.0000f, 0.0000f, 0.3000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.4500f, 0.0000f, -0.8500f, 0.0000f, 0.0000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, -0.3000f, 2.6660f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.0000f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 1.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val COIN_FLIP = floatArrayOf(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.1800f, 0.0000f, 0.0000f, 0.0000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.0430f, 0.1700f, 0.0570f, 0.0000f, 0.0000f, 0.0000f, -1.0500f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, -1.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0300f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val COIN_LOOK = floatArrayOf(-0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.8500f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.1800f, 0.0000f, 0.0000f, 0.0000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.1620f, 0.1650f, 0.0490f, 0.0000f, 0.0000f, 0.0000f, -1.2500f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, -1.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0300f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val COIN_CATCH = floatArrayOf(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.1800f, 0.0000f, 0.0000f, 0.0000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.8860f, 0.0130f, -0.1250f, 0.0000f, 0.0000f, 0.0000f, -0.8500f, 0.0000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, -1.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0300f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3500f, 0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val READ = floatArrayOf(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0400f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4200f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.1800f, 0.0000f, 0.0000f, 0.0000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.9670f, 0.0280f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, -0.7500f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, -1.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0300f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, -1.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5000f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5000f, 1.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val LETTER = floatArrayOf(0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.1800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.1800f, 0.0000f, 0.0000f, 0.0000f, -0.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.0370f, 0.1520f, 0.0320f, 0.0000f, 0.0000f, 0.0000f, -1.7500f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, -0.8500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0300f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2500f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, 0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.5000f, -1.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5000f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5000f, 1.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
    val FALL = floatArrayOf(-1.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0400f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 1.2000f, 0.0000f, 0.0000f, 0.0000f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.4000f, 0.5530f, 1.9130f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, -1.2500f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3000f, 0.0000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1000f, 0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.1500f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f)
}
// </hermes-keys>
