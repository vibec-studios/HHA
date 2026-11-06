package dev.henny.hha.client.model

import dev.henny.hha.entity.boss.HermesPose
import dev.henny.hha.client.vfx.BossGlow
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.entity.model.EntityModel
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import org.joml.Vector3f
import kotlin.math.sin

/** Render-Zustand von Hermes; zusätzlich zum Standard die Pose und die Geister-Positionen. */
class HermesRenderState : LivingEntityRenderState() {
    var bossPose = HermesPose.IDLE
    var previousPose = HermesPose.IDLE
    var poseTime = 0f
    var illusion = false
    var corrupted = false
    var entityId = 0
    var stage = 1
    var vulnerable = true
    var speed = 0f
    var afterimages: List<net.minecraft.util.math.Vec3d> = emptyList()
}

/**
 * Hermes' Skelett. Jede Pose ist eine Funktion der Zeit, die Winkel für alle Knochen liefert; zwischen zwei
 * Posen wird weich überblendet. Die großen Flügel haben eigene Gelenke (Oberarm, Unterarm, Hand) und falten
 * sich je Pose auf oder zu; Atmen, Flügelschlag und Umhang laufen in jeder Pose automatisch mit.
 */
class HermesModel(root: ModelPart) : EntityModel<HermesRenderState>(root) {

    private val body = root.getChild("root")
    private val torso = body.getChild("torso")
    private val head = torso.getChild("head")
    private val wingHeadR = head.getChild("wing_head_r")
    private val wingHeadL = head.getChild("wing_head_l")
    private val armR = torso.getChild("arm_r")
    private val foreR = armR.getChild("fore_r")
    private val caduceus = foreR.getChild("caduceus")
    private val cadWingR = caduceus.getChild("caduceus_wing_r")
    private val cadWingL = caduceus.getChild("caduceus_wing_l")
    private val armL = torso.getChild("arm_l")
    private val foreL = armL.getChild("fore_l")
    private val scroll = foreL.getChild("scroll")
    private val letter = foreL.getChild("letter")
    private val cape1 = torso.getChild("cape1")
    private val cape2 = cape1.getChild("cape2")
    private val cape3 = cape2.getChild("cape3")
    private val legR = body.getChild("leg_r")
    private val shinR = legR.getChild("shin_r")
    private val wingFootR = shinR.getChild("wing_foot_r")
    private val legL = body.getChild("leg_l")
    private val shinL = legL.getChild("shin_l")
    private val wingFootL = shinL.getChild("wing_foot_l")
    private val backR = torso.getChild("wing_back_r")
    private val backForeR = backR.getChild("wing_fore_r")
    private val backHandR = backForeR.getChild("wing_hand_r")
    private val backL = torso.getChild("wing_back_l")
    private val backForeL = backL.getChild("wing_fore_l")
    private val backHandL = backForeL.getChild("wing_hand_l")

    /** Winkelsatz einer Pose (Bogenmaß) plus Höhenversatz des Körpers. */
    private class Angles {
        val v = FloatArray(COUNT)
        operator fun get(i: Int) = v[i]
        operator fun set(i: Int, value: Float) {
            v[i] = value
        }

        fun clear() = v.fill(0f)
    }

    private val from = Angles()
    private val to = Angles()

    override fun setAngles(state: HermesRenderState) {
        super.setAngles(state)
        val t = state.age
        computePose(state.previousPose, t, from)
        computePose(state.bossPose, t, to, state.poseTime)
        val blend = MathHelper.clamp(state.poseTime / 6f, 0f, 1f).let { it * it * (3f - 2f * it) }
        val a = FloatArray(COUNT) { MathHelper.lerp(blend, from[it], to[it]) }

        body.pitch = a[ROOT_RX]
        body.yaw = a[ROOT_RY]
        body.roll = a[ROOT_RZ]
        // Das Modell hat doppelte Pixeldichte: Längenangaben zählen hier doppelt.
        body.originY += a[ROOT_Y] * 2f
        torso.pitch = a[TORSO_RX]
        torso.yaw = a[TORSO_RY]
        head.pitch = a[HEAD_RX] + state.pitch * MathHelper.RADIANS_PER_DEGREE * 0.6f
        head.yaw = a[HEAD_RY] + state.relativeHeadYaw * MathHelper.RADIANS_PER_DEGREE * 0.6f
        armR.pitch = a[ARM_R_RX]
        armR.yaw = a[ARM_R_RY]
        armR.roll = a[ARM_R_RZ]
        foreR.pitch = a[FORE_R_RX]
        caduceus.pitch = a[CAD_RX]
        caduceus.roll = a[CAD_RZ]
        armL.pitch = a[ARM_L_RX]
        armL.yaw = a[ARM_L_RY]
        armL.roll = a[ARM_L_RZ]
        foreL.pitch = a[FORE_L_RX]
        legR.pitch = a[LEG_R_RX]
        legR.roll = a[LEG_R_RZ]
        shinR.pitch = a[SHIN_R_RX]
        legL.pitch = a[LEG_L_RX]
        legL.roll = a[LEG_L_RZ]
        shinL.pitch = a[SHIN_L_RX]

        // Kleine Flügel an Helm, Sandalen und Stab schlagen je nach Tempo schneller.
        val flapSpeed = 0.55f + state.speed * 0.6f
        val flap = sin(t * flapSpeed) * (0.32f + state.speed * 0.12f)
        wingHeadR.yaw = -0.14f + flap
        wingHeadL.yaw = 0.14f - flap
        wingFootR.yaw = 0.1f + flap * 1.2f
        wingFootL.yaw = -0.1f - flap * 1.2f
        cadWingR.yaw = flap * 0.6f
        cadWingL.yaw = -flap * 0.6f

        // Große Flügel: langsamer, kräftiger Schlag; die Hand schwingt dem Oberarm hinterher.
        val beatSpeed = 0.14f + state.speed * 0.28f + a[WING_BEAT] * 0.32f
        val beatAmp = 0.1f + a[WING_BEAT] * 0.34f + state.speed * 0.08f
        val beat = sin(t * beatSpeed) * beatAmp
        val beatLate = sin(t * beatSpeed - 0.9f) * beatAmp
        applyWing(backR, backForeR, backHandR, 1f, a, beat, beatLate)
        applyWing(backL, backForeL, backHandL, -1f, a, beat, beatLate)

        // Umhang: Grundwinkel aus der Pose, darüber eine laufende Welle.
        val wave = 0.09f + state.speed * 0.05f
        cape1.pitch = a[CAPE_RX] + sin(t * 0.13f) * wave
        cape2.pitch = 0.1f + sin(t * 0.17f + 0.9f) * wave * 1.3f
        cape3.pitch = 0.12f + sin(t * 0.21f + 1.8f) * wave * 1.6f

        scroll.visible = state.bossPose == HermesPose.READ || (state.previousPose == HermesPose.READ && blend < 0.5f)
        letter.visible = state.bossPose == HermesPose.LETTER
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
                        bone.add(at(side * 16f, 0f, 0f))
                        tip = at(side * 26f, 47f, 1f)
                    }
                }
            }
            return bone to tip
        }

        inside(root) {
            inside(body) {
                inside(legR) { inside(shinR) { out.footR = at(-2.8f, 12f, 2.5f) } }
                inside(legL) { inside(shinL) { out.footL = at(2.8f, 12f, 2.5f) } }
                inside(torso) {
                    out.chest = at(0f, -16f, -5.3f)
                    inside(head) {
                        out.eyeR = at(-1.5f, -6.5f, -5.1f)
                        out.eyeL = at(1.5f, -6.5f, -5.1f)
                        out.halo = at(0f, -7f, 7.5f)
                        out.haloRight = at(1f, -7f, 7.5f).subtract(out.halo).normalize()
                        out.haloUp = at(0f, -8f, 7.5f).subtract(out.halo).normalize()
                    }
                    inside(armR) {
                        inside(foreR) {
                            out.handR = at(0f, 13f, 0f)
                            inside(caduceus) {
                                out.orb = at(0f, -43f, 0f)
                                out.staffTop = at(0f, -36f, 0f)
                                out.staffBottom = at(0f, 34f, 0f)
                            }
                        }
                    }
                    inside(armL) { inside(foreL) { out.handL = at(0f, 13f, 0f) } }
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

    /** [side]: +1 rechter Flügel (zeigt nach -x), -1 linker. Spiegelt Heben und Anlegen entsprechend. */
    private fun applyWing(back: ModelPart, fore: ModelPart, hand: ModelPart, side: Float, a: FloatArray, beat: Float, beatLate: Float) {
        val fold = a[WING_FOLD]
        back.roll = side * (a[WING_RAISE] + beat)
        back.yaw = side * a[WING_BACK]
        fore.roll = side * (-0.35f * fold + beatLate * 0.5f)
        fore.yaw = side * (1.25f * fold)
        hand.roll = side * (0.2f * fold + beatLate * 0.7f)
        hand.yaw = side * (1.3f * fold)
    }

    /** [elapsed] = Ticks seit Beginn dieser Pose; steuert die zeitgebundenen Bewegungen wie den Schlag. */
    private fun computePose(pose: Int, t: Float, o: Angles, elapsed: Float = 100f) {
        o.clear()
        // Grundhaltung: schwebend, Stab aufrecht in der rechten Hand, Flügel halb geöffnet.
        val bob = sin(t * 0.09f)
        o[ROOT_Y] = bob * 0.9f
        o[TORSO_RX] = 0.03f + bob * 0.015f
        // Die Stabwinkel sind so gewählt, dass der Stab in keiner Pose vor oder durch den Kopf läuft. Über dem Kopf
        // muss die Armrolle das Vorzeichen wechseln, damit die Hand nach außen statt zum Gesicht wandert.
        o[ARM_R_RX] = -0.28f + bob * 0.03f
        o[ARM_R_RZ] = 0.82f
        o[FORE_R_RX] = -0.62f
        o[CAD_RX] = 0.55f
        o[CAD_RZ] = -1.2f
        o[ARM_L_RX] = 0.12f
        o[ARM_L_RZ] = -0.32f - bob * 0.04f
        o[FORE_L_RX] = -0.25f
        o[LEG_R_RX] = 0.12f + bob * 0.03f
        o[LEG_R_RZ] = 0.04f
        o[SHIN_R_RX] = 0.34f
        o[LEG_L_RX] = -0.14f - bob * 0.03f
        o[LEG_L_RZ] = -0.04f
        o[SHIN_L_RX] = 0.42f
        o[CAPE_RX] = 0.22f
        o[WING_RAISE] = 0.28f
        o[WING_BACK] = 0.55f
        o[WING_FOLD] = 0.45f
        o[WING_BEAT] = 0.25f

        when (pose) {
            HermesPose.DASH -> {
                o[ROOT_RX] = 1.32f
                o[ARM_R_RX] = -2.7f
                o[ARM_R_RZ] = -0.9f
                o[FORE_R_RX] = -0.1f
                o[CAD_RX] = 3f
                o[CAD_RZ] = -0.8f
                o[ARM_L_RX] = 0.65f
                o[ARM_L_RZ] = -0.2f
                o[FORE_L_RX] = -0.1f
                o[HEAD_RX] = -1.05f
                o[LEG_R_RX] = 0.08f
                o[SHIN_R_RX] = 0.12f
                o[LEG_L_RX] = 0.3f
                o[SHIN_L_RX] = 0.5f
                o[CAPE_RX] = 1.15f
                o[ROOT_Y] = 0f
                // Flügel eng nach hinten angelegt, wie ein stürzender Falke.
                o[WING_RAISE] = 0.12f
                o[WING_BACK] = 1.05f
                o[WING_FOLD] = 0.5f
                o[WING_BEAT] = 0f
            }
            HermesPose.CAST -> {
                o[ROOT_RX] = -0.1f
                o[ARM_R_RX] = -2.6f
                o[ARM_R_RZ] = -0.9f
                o[FORE_R_RX] = -0.1f
                o[CAD_RX] = 2.9f
                o[CAD_RZ] = -0.6f
                o[ARM_L_RX] = -0.5f
                o[ARM_L_RZ] = -1.05f
                o[FORE_L_RX] = -0.5f
                o[HEAD_RX] = -0.28f
                o[CAPE_RX] = 0.42f
                // Weit aufgespannt.
                o[WING_RAISE] = 0.6f
                o[WING_BACK] = 0.18f
                o[WING_FOLD] = 0.06f
                o[WING_BEAT] = 0.5f
            }
            HermesPose.THROW -> {
                val swing = sin(elapsed * 0.4f)
                o[TORSO_RY] = 0.45f * swing
                o[ARM_L_RX] = -1.7f
                o[ARM_L_RY] = -0.6f * swing
                o[ARM_L_RZ] = -0.25f
                o[FORE_L_RX] = -0.2f
                o[ROOT_RX] = 0.12f
                o[CAPE_RX] = 0.5f
                o[WING_RAISE] = 0.35f
                o[WING_BACK] = 0.35f
                o[WING_FOLD] = 0.2f
                o[WING_BEAT] = 0.6f
            }
            HermesPose.SLAM -> {
                val strike = MathHelper.clamp((elapsed - 8f) / 4f, 0f, 1f)
                o[ROOT_RX] = MathHelper.lerp(strike, -0.3f, 0.55f)
                o[ARM_R_RX] = MathHelper.lerp(strike, -2.7f, -1.1f)
                o[ARM_R_RZ] = MathHelper.lerp(strike, -0.9f, 0.8f)
                o[FORE_R_RX] = -0.3f
                o[CAD_RX] = MathHelper.lerp(strike, 2.2f, 3.28f)
                o[CAD_RZ] = MathHelper.lerp(strike, -0.6f, -0.3f)
                o[ARM_L_RX] = MathHelper.lerp(strike, -2.9f, -1.0f)
                o[ARM_L_RZ] = 0.28f
                o[FORE_L_RX] = -0.45f
                o[LEG_R_RX] = -0.7f
                o[SHIN_R_RX] = 1.0f
                o[LEG_L_RX] = 0.5f
                o[SHIN_L_RX] = 0.7f
                o[CAPE_RX] = MathHelper.lerp(strike, 0.2f, 0.8f)
                // Hoch hinauf, dann mit dem Schlag herunter.
                o[WING_RAISE] = MathHelper.lerp(strike, 0.95f, -0.3f)
                o[WING_BACK] = 0.25f
                o[WING_FOLD] = MathHelper.lerp(strike, 0.1f, 0.3f)
                o[WING_BEAT] = 0f
            }
            HermesPose.READ -> {
                o[ARM_L_RX] = -0.75f
                o[ARM_L_RZ] = -0.42f
                o[FORE_L_RX] = -0.95f
                o[HEAD_RX] = 0.3f
                o[HEAD_RY] = -0.12f
                o[ARM_R_RX] = -0.1f
                o[ARM_R_RZ] = 0.75f
                o[FORE_R_RX] = -0.35f
                o[CAD_RX] = 0.35f
                o[CAD_RZ] = -0.9f
                o[WING_RAISE] = 0.15f
                o[WING_BACK] = 0.9f
                o[WING_FOLD] = 0.85f
                o[WING_BEAT] = 0f
            }
            HermesPose.LETTER -> {
                // Hält den Brief zwischen zwei Fingern hoch und schaut den Spieler darüber hinweg an.
                o[ARM_L_RX] = -1.3f
                o[ARM_L_RZ] = -0.22f
                o[FORE_L_RX] = -0.95f
                o[HEAD_RX] = 0.05f
                o[HEAD_RY] = 0.18f
                o[TORSO_RY] = -0.12f
                o[ARM_R_RX] = -0.1f
                o[ARM_R_RZ] = 0.75f
                o[FORE_R_RX] = -0.35f
                o[CAD_RX] = 0.35f
                o[CAD_RZ] = -0.9f
                o[WING_RAISE] = 0.1f
                o[WING_BACK] = 0.95f
                o[WING_FOLD] = 0.9f
                o[WING_BEAT] = 0f
            }
            HermesPose.TAUNT -> {
                val laugh = sin(t * 0.85f)
                o[TORSO_RX] = -0.06f + laugh * 0.05f
                o[HEAD_RX] = -0.24f + laugh * 0.07f
                o[ARM_L_RX] = 0.15f
                o[ARM_L_RZ] = -0.85f
                o[FORE_L_RX] = -2.0f
                o[ROOT_RZ] = 0.08f
                o[LEG_R_RX] = -0.15f
                o[LEG_L_RX] = 0.08f
                o[SHIN_R_RX] = 0.2f
                o[WING_RAISE] = 0.3f + laugh * 0.05f
                o[WING_BACK] = 0.6f
                o[WING_FOLD] = 0.6f
                o[WING_BEAT] = 0.1f
            }
            HermesPose.KNEEL -> {
                o[ROOT_Y] = 7f
                o[ROOT_RX] = 0.2f
                o[LEG_R_RX] = -1.5f
                o[SHIN_R_RX] = 1.5f
                o[LEG_L_RX] = 0.3f
                o[SHIN_L_RX] = 1.45f
                o[HEAD_RX] = 0.42f
                o[ARM_R_RX] = -0.8f
                o[ARM_R_RZ] = 0.9f
                o[FORE_R_RX] = -0.5f
                o[CAD_RX] = 0.4f
                o[CAD_RZ] = -1.6f
                o[ARM_L_RX] = -0.8f
                o[ARM_L_RZ] = -0.16f
                o[FORE_L_RX] = -0.7f
                o[CAPE_RX] = 0.08f
                // Erschöpft hängende Flügel.
                o[WING_RAISE] = -0.3f
                o[WING_BACK] = 0.75f
                o[WING_FOLD] = 0.8f
                o[WING_BEAT] = 0f
            }
            HermesPose.STAGGER -> {
                val shake = sin(t * 2.3f) * 0.05f
                o[ROOT_RX] = -0.38f + shake
                o[ROOT_RZ] = shake
                o[ARM_R_RX] = -0.1f
                o[ARM_R_RZ] = 0.9f
                o[FORE_R_RX] = -0.4f
                o[CAD_RX] = 0f
                o[CAD_RZ] = -1.35f
                o[ARM_L_RX] = -0.1f
                o[ARM_L_RZ] = -1.05f
                o[HEAD_RX] = 0.25f
                o[LEG_R_RX] = -0.45f
                o[SHIN_R_RX] = 0.5f
                o[LEG_L_RX] = 0.42f
                o[SHIN_L_RX] = 0.25f
                o[CAPE_RX] = 0.72f
                // Reißt die Flügel auf, um sich zu fangen.
                o[WING_RAISE] = 0.5f + shake * 3f
                o[WING_BACK] = 0.2f
                o[WING_FOLD] = 0.25f
                o[WING_BEAT] = 0.8f
            }
            HermesPose.ASCEND -> {
                val hover = sin(t * 0.2f) * 0.05f
                o[ROOT_RX] = -0.1f
                o[ARM_R_RX] = -0.2f
                o[ARM_R_RZ] = 1.3f + hover
                o[FORE_R_RX] = -0.15f
                o[CAD_RX] = 0f
                o[CAD_RZ] = -1.7f
                o[ARM_L_RX] = -0.2f
                o[ARM_L_RZ] = -1.3f - hover
                o[FORE_L_RX] = -0.15f
                o[HEAD_RX] = -0.36f
                o[LEG_R_RX] = 0.05f
                o[SHIN_R_RX] = 0.15f
                o[LEG_L_RX] = 0.05f
                o[SHIN_L_RX] = 0.15f
                o[CAPE_RX] = 0.5f
                // Volle Spannweite mit kräftigen Schlägen.
                o[WING_RAISE] = 0.45f
                o[WING_BACK] = 0.08f
                o[WING_FOLD] = 0f
                o[WING_BEAT] = 1f
            }
            HermesPose.SNAP -> {
                val snap = if ((elapsed % 26f) < 3f) -0.3f else 0f
                o[ARM_L_RX] = -1.8f
                o[ARM_L_RZ] = -0.25f
                o[FORE_L_RX] = -0.9f + snap
                o[HEAD_RX] = -0.1f
                o[HEAD_RY] = 0.2f
                o[TORSO_RY] = -0.15f
                o[WING_RAISE] = 0.35f
                o[WING_BACK] = 0.5f
                o[WING_FOLD] = 0.5f
            }
            HermesPose.FALL -> {
                o[ROOT_RX] = -1.35f
                o[ROOT_Y] = 0f
                o[ARM_R_RX] = -0.3f
                o[ARM_R_RZ] = 1.1f
                o[FORE_R_RX] = -0.75f
                o[CAD_RX] = -0.1f
                o[CAD_RZ] = -1.45f
                o[ARM_L_RX] = -0.3f
                o[ARM_L_RZ] = -1.2f
                o[HEAD_RX] = 0.35f
                o[LEG_R_RX] = -0.2f
                o[LEG_L_RX] = 0.1f
                o[CAPE_RX] = 0.05f
                // Flügel treiben ausgebreitet im Fall.
                o[WING_RAISE] = 0.1f
                o[WING_BACK] = -0.15f
                o[WING_FOLD] = 0.15f
                o[WING_BEAT] = 0.15f
            }
        }
    }

    companion object {
        private const val ROOT_RX = 0
        private const val ROOT_RY = 1
        private const val ROOT_RZ = 2
        private const val ROOT_Y = 3
        private const val TORSO_RX = 4
        private const val TORSO_RY = 5
        private const val HEAD_RX = 6
        private const val HEAD_RY = 7
        private const val ARM_R_RX = 8
        private const val ARM_R_RY = 9
        private const val ARM_R_RZ = 10
        private const val FORE_R_RX = 11
        private const val CAD_RX = 12
        private const val CAD_RZ = 13
        private const val ARM_L_RX = 14
        private const val ARM_L_RY = 15
        private const val ARM_L_RZ = 16
        private const val FORE_L_RX = 17
        private const val LEG_R_RX = 18
        private const val LEG_R_RZ = 19
        private const val SHIN_R_RX = 20
        private const val LEG_L_RX = 21
        private const val LEG_L_RZ = 22
        private const val SHIN_L_RX = 23
        private const val CAPE_RX = 24
        private const val WING_RAISE = 25
        private const val WING_BACK = 26
        private const val WING_FOLD = 27
        private const val WING_BEAT = 28
        private const val COUNT = 29
    }
}
