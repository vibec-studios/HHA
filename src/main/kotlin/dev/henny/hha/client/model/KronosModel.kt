package dev.henny.hha.client.model

import dev.henny.hha.Hha
import dev.henny.hha.client.vfx.BossGlow
import dev.henny.hha.entity.boss.KronosEntity
import dev.henny.hha.entity.boss.KronosPose
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
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class KronosRenderState : LivingEntityRenderState() {
    var entityId = 0
    var bossPose = KronosPose.IDLE
    var previousPose = KronosPose.IDLE
    var poseTime = 0f
    var form = 0

    /** Fliegt die Harpe gerade (siehe [KronosEntity.harpeOut])? */
    var harpeOut = false

    /** Konter gegen Spieler über ihm (Überarbeitung 2): [KronosPose.SWEEP_L] als Aufwärtshieb, siehe [KronosEntity.antiAir]. */
    var antiAir = false
    var walk = 0f
    var walkPhase = 0f
}

/** Eine Schlüsselpose: je Knochen (pitch, yaw, roll, dx, dy, dz), siehe [KronosModel.Keys]. */
class KronosKey(val v: FloatArray, val hidden: IntArray)

private fun key(data: String, hidden: IntArray) = KronosKey(data.split(',').map { it.toFloat() }.toFloatArray(), hidden)

/**
 * Kronos, der Titan der Zeit. Geometrie, Textur und alle Schlüsselposen stammen aus tools/hha_assets/kronos_model.py;
 * der Block [Keys] wird dort erzeugt (`python tools/hha_assets/kronos_model.py --kotlin`), damit Vorschau und Spiel
 * dieselben Winkel benutzen. Hier steht nur der Ablauf: welche Pose wann, mit welcher Hüllkurve, plus Atmen, Gehen,
 * Nachschwingen (Ketten, Mantel, Bart), Himmelsring und Sand.
 *
 * Alles an ihm ist schwer und spät: weich ins Ausholen (8 Ticks), halten bis zur Trefferzeit aus [BossTimings.Kronos],
 * dann ein harter Schlag mit dem ganzen Oberkörper (2–3 Ticks), Nachlauf, Erholung.
 */
class KronosModel(root: ModelPart) : EntityModel<KronosRenderState>(root) {

    private val parts: Array<ModelPart> = Array(Keys.COUNT) { i ->
        Keys.BONES[i].split('/').fold(root) { p, n -> p.getChild(n) }
    }
    private fun part(i: Int) = parts[i]
    private val hips = part(Keys.HIPS)
    private val torso = part(Keys.TORSO)
    private val chest = part(Keys.CHEST)
    private val head = part(Keys.HEAD)
    private val ring = part(Keys.RING)
    private val glass = part(Keys.GLASS)
    private val armR = part(Keys.ARM_R)
    private val foreR = part(Keys.FORE_R)
    private val harpe = part(Keys.HARPE)
    private val armL = part(Keys.ARM_L)
    private val foreL = part(Keys.FORE_L)
    private val handL = part(Keys.HAND_L)
    private val sand = arrayOf(glass.getChild("sand_0"), glass.getChild("sand_1"))
    private val blade: Array<ModelPart> = run {
        val list = ArrayList<ModelPart>()
        var p = harpe.getChild("blade")
        list.add(p)
        for (i in 1 until Keys.BLADE_LEN.size) {
            p = p.getChild("blade_$i")
            list.add(p)
        }
        list.toTypedArray()
    }

    // --- Hüllkurven ---------------------------------------------------------------------------------------------

    private fun smooth(t: Float) = t * t * (3f - 2f * t)
    private fun easeIn(t: Float) = t * t
    private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t)

    private fun mix(a: FloatArray, b: FloatArray, t: Float): FloatArray = FloatArray(a.size) { a[it] + (b[it] - a[it]) * t }

    /** Zeitleiste: hält die zuletzt erreichte Pose und blendet in den Abschnitten [t0, t1] zur nächsten über. */
    private class Track(var cur: FloatArray, private val t: Float) {
        var out: FloatArray? = null
        fun to(t0: Float, t1: Float, target: KronosKey, ease: (Float) -> Float): Track {
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

    /** Der Ablauf jeder Pose ab ihrem Beginn (t = poseTime). [from] ist die Haltung, aus der er kommt. */
    private fun evaluate(pose: Int, t: Float, from: FloatArray): FloatArray {
        val k = BossTimings.Kronos
        val soft = BossTimings.Anim.BLEND_SOFT.toFloat()
        val hard = BossTimings.Anim.BLEND_HARD.toFloat()
        val s: (Float) -> Float = ::smooth
        val i: (Float) -> Float = ::easeIn
        val o: (Float) -> Float = ::easeOut
        return when (pose) {
            KronosPose.SWEEP -> {
                val hit = k.SWEEP_WINDUP.toFloat()
                track(from, t).to(0f, soft, Keys.SWEEP_R_WINDUP, s).to(hit, hit + k.SWEEP_STRIKE, Keys.SWEEP_R_HIT, i)
                    .to(hit + k.SWEEP_STRIKE, hit + k.SWEEP_STRIKE + 5f, Keys.SWEEP_R_FOLLOW, o)
                    .to(k.SWEEP_END.toFloat(), k.SWEEP_END + 12f, Keys.IDLE, s).get()
            }
            KronosPose.SWEEP_L -> {
                val hit = k.SWEEP_L_WINDUP.toFloat()
                track(from, t).to(0f, soft, Keys.SWEEP_L_WINDUP, s).to(hit, hit + k.SWEEP_STRIKE, Keys.SWEEP_L_HIT, i)
                    .to(hit + k.SWEEP_STRIKE, hit + k.SWEEP_STRIKE + 5f, Keys.SWEEP_L_FOLLOW, o)
                    .to(k.SWEEP_L_END.toFloat(), k.SWEEP_L_END + 12f, Keys.IDLE, s).get()
            }
            KronosPose.CLEAVE -> {
                // Er richtet sich langsam zur vollen Größe auf, dann fällt alles auf einmal.
                val hit = k.CLEAVE_WINDUP.toFloat()
                track(from, t).to(0f, 12f, Keys.CLEAVE_WINDUP, s).to(hit, hit + k.CLEAVE_STRIKE, Keys.CLEAVE_HIT, i).get()
            }
            KronosPose.STUCK -> track(from, t).to(0f, 6f, Keys.STUCK, s).get()
            KronosPose.SLAM -> {
                val hit = k.SLAM_WINDUP.toFloat()
                track(from, t).to(0f, soft, Keys.SLAM_WINDUP, s).to(hit - hard, hit, Keys.SLAM_HIT, i)
                    .to(k.SLAM_END.toFloat(), k.SLAM_END + 14f, Keys.IDLE, s).get()
            }
            KronosPose.THROW -> {
                val hit = k.THROW_WINDUP.toFloat()
                track(from, t).to(0f, soft, Keys.THROW_WINDUP, s).to(hit - hard, hit, Keys.THROW_RELEASE, i)
                    .to(hit + 6f, hit + 16f, Keys.EMPTY, s).get()
            }
            KronosPose.EMPTY -> track(from, t).to(0f, soft, Keys.EMPTY, s).get()
            KronosPose.STEP -> track(from, t).to(0f, 5f, Keys.STEP, o).get()
            KronosPose.HANDS -> track(from, t).to(0f, 16f, Keys.HANDS, s).get()
            KronosPose.HALT -> {
                val raise = k.HALT_RAISE.toFloat()
                val close = k.HALT_RESUME.toFloat()
                track(from, t).to(0f, raise - 4f, Keys.HALT_RAISE, s).to(close, close + hard + 1f, Keys.HALT_CLOSE, i)
                    .to(k.HALT_HIT + 4f, k.HALT_HIT + 16f, Keys.IDLE, s).get()
            }
            KronosPose.REWIND -> {
                val base = track(from, t).to(0f, soft, Keys.REWIND, s).get()
                // Beide Hände drehen die Harpe wie einen Uhrschlüssel hin und her.
                val turn = if (t < soft) 0f else 0.5f - 0.5f * kotlin.math.cos((t - soft) * 0.22f)
                mix(base, Keys.REWIND_B.v, turn)
            }
            KronosPose.DEVOUR -> {
                val bite = k.DEVOUR_BITE.toFloat()
                track(from, t).to(0f, 12f, Keys.DEVOUR_INHALE, s).to(bite - 3f, bite, Keys.DEVOUR_BITE, i)
                    .to(bite + 6f, bite + 16f, Keys.DEVOUR_RECOVER, o)
                    .to(k.DEVOUR_END.toFloat(), k.DEVOUR_END + 14f, Keys.IDLE, s).get()
            }
            KronosPose.CHAINED -> track(from, t).to(0f, 4f, Keys.CHAINED, o).get()
            KronosPose.STAGGER -> track(from, t).to(0f, 10f, Keys.STAGGER, i).get()
            KronosPose.ROAR -> track(from, t).to(0f, soft, Keys.ROAR, s).get()
            KronosPose.CAST -> track(from, t).to(0f, soft, Keys.CAST, s).get()
            KronosPose.WINDUP -> track(from, t).to(0f, soft, Keys.WINDUP, s).get()
            KronosPose.DESCEND -> track(from, t).to(0f, 4f, Keys.DESCEND, s).get()
            KronosPose.BOUND -> track(from, t).to(0f, 10f, Keys.BOUND, s).get()
            else -> track(from, t).to(0f, 10f, Keys.IDLE, s).get()
        }
    }

    /** Wie lange eine Pose läuft, bis sie in ihrer Endhaltung steht (für den Übergang in die nächste). */
    private fun settled(pose: Int): Float = when (pose) {
        KronosPose.SWEEP -> BossTimings.Kronos.SWEEP_END.toFloat()
        KronosPose.SWEEP_L -> BossTimings.Kronos.SWEEP_L_END.toFloat()
        KronosPose.SLAM -> BossTimings.Kronos.SLAM_END.toFloat()
        KronosPose.HALT -> BossTimings.Kronos.HALT_HIT.toFloat()
        KronosPose.DEVOUR -> BossTimings.Kronos.DEVOUR_END.toFloat()
        KronosPose.THROW -> BossTimings.Kronos.THROW_WINDUP + 20f
        else -> 40f
    }

    /** Zusätzlicher Winkel des Himmelsrings je Pose: steht im Stillstand, läuft beim Zurückspulen rückwärts. */
    private fun ringOffset(pose: Int, t: Float, speed: Float): Float = when (pose) {
        KronosPose.HALT -> {
            val k = BossTimings.Kronos
            val frozen = MathHelper.clamp(t - k.HALT_RAISE, 0f, k.HALT_FROZEN.toFloat())
            // Danach holt er die verlorene Zeit in 8 Ticks nach.
            val catchUp = MathHelper.clamp((t - k.HALT_RESUME) / 8f, 0f, 1f)
            -speed * frozen * (1f - smooth(catchUp))
        }
        KronosPose.REWIND -> -3f * speed * t
        KronosPose.STEP -> -speed * min(t, 20f)
        KronosPose.STAGGER, KronosPose.BOUND -> -speed * t * 0.8f
        else -> 0f
    }

    /**
     * Zeit im SWEEP_L-Ablauf während des Konters: bis zum Treffer gestaucht (Ansage [BossTimings.Guardian.ANTI_AIR_HIT]
     * statt [BossTimings.Kronos.SWEEP_L_WINDUP]), danach im normalen Takt weiter.
     */
    private fun counterTime(t: Float): Float {
        val hit = BossTimings.Guardian.ANTI_AIR_HIT.toFloat()
        val full = BossTimings.Kronos.SWEEP_L_WINDUP.toFloat()
        return if (t <= hit) t * full / hit else t + (full - hit)
    }

    /** Steht die Zeit (Sand, Atem, Ring)? */
    private fun frozenTicks(pose: Int, t: Float): Float = when (pose) {
        KronosPose.HALT -> MathHelper.clamp(t - BossTimings.Kronos.HALT_RAISE, 0f, BossTimings.Kronos.HALT_FROZEN.toFloat())
        KronosPose.STEP -> min(t, BossTimings.Kronos.STEP_WINDUP.toFloat())
        else -> 0f
    }

    override fun setAngles(state: KronosRenderState) {
        super.setAngles(state)
        val pose = state.bossPose
        val t = state.poseTime
        val age = state.age
        val start = evaluate(state.previousPose, settled(state.previousPose), Keys.IDLE.v)
        // Konter (Überarbeitung 2, PLAN 4.3): SWEEP_L läuft so gestaucht, dass der Hieb auf das Ende der Konter-Ansage fällt.
        val counter = state.antiAir && pose == KronosPose.SWEEP_L
        val v = evaluate(pose, if (counter) counterTime(t) else t, start)
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
        harpe.visible = !(state.harpeOut || pose == KronosPose.EMPTY || pose == KronosPose.BOUND ||
            (pose == KronosPose.THROW && t >= BossTimings.Kronos.THROW_WINDUP))

        val form = state.form > 0
        val clock = age - frozenTicks(pose, t)
        val still = pose == KronosPose.HALT && t > BossTimings.Kronos.HALT_RAISE && t < BossTimings.Kronos.HALT_RESUME

        // Atmen: die Brust hebt sich langsam (nicht, solange die Zeit steht).
        val breath = sin(clock * 0.06f)
        chest.pitch += breath * 0.02f
        hips.originY += breath * 0.35f
        head.pitch -= breath * 0.012f

        // Gehen: er stampft. Bei jedem Aufsetzen sinkt der ganze Leib ein; die Arme schwingen gegengleich.
        val w = if (pose == KronosPose.IDLE || pose == KronosPose.WINDUP || pose == KronosPose.CAST || pose == KronosPose.EMPTY) state.walk else state.walk * 0.4f
        if (w > 0.01f) {
            val ph = state.walkPhase
            val stride = sin(ph)
            val liftR = max(0f, sin(ph + 1.3f))
            val liftL = max(0f, -sin(ph + 1.3f))
            val stomp = abs(sin(ph))
            part(Keys.THIGH_R).pitch += stride * 0.42f * w
            part(Keys.THIGH_L).pitch -= stride * 0.42f * w
            part(Keys.SHIN_R).pitch += liftR * 0.75f * w
            part(Keys.SHIN_L).pitch += liftL * 0.75f * w
            part(Keys.FOOT_R).pitch -= (stride * 0.42f + liftR * 0.75f) * w * 0.8f
            part(Keys.FOOT_L).pitch -= (-stride * 0.42f + liftL * 0.75f) * w * 0.8f
            hips.originY += (1f - stomp) * -1.2f * w + stomp * stomp * 3.2f * w
            hips.roll += stride * 0.035f * w
            torso.yaw -= stride * 0.07f * w
            chest.roll -= stride * 0.03f * w
            armR.pitch -= stride * 0.16f * w
            armL.pitch += stride * 0.2f * w
            part(Keys.LOIN_F).pitch -= abs(stride) * 0.25f * w
            part(Keys.LOIN_B).pitch += abs(stride) * 0.2f * w
        }

        if (counter) {
            // Aufwärtshieb: Beim Treffer fährt der Harpenarm über den Kopf, der Oberkörper lehnt zurück, der Blick geht nach oben.
            val hit = BossTimings.Guardian.ANTI_AIR_HIT.toFloat()
            val end = BossTimings.Guardian.ANTI_AIR_END.toFloat()
            val fade = 1f - MathHelper.clamp((t - end + 6f) / 6f, 0f, 1f)
            val swing = MathHelper.clamp((t - hit + 3f) / 4f, 0f, 1f) * fade
            val look = MathHelper.clamp(t / BossTimings.Guardian.ANTI_AIR_RAISE, 0f, 1f) * fade
            armR.pitch -= 1.25f * swing
            foreR.pitch -= 0.35f * swing
            chest.pitch -= 0.18f * swing
            head.pitch -= 0.45f * look
        }

        // Spannung beim Halten des Ausholens: ein kaum sichtbares Zittern in Armen und Brust.
        val holding = when (pose) {
            KronosPose.SWEEP -> t > 8f && t < BossTimings.Kronos.SWEEP_WINDUP
            KronosPose.SWEEP_L -> t > 8f && t < BossTimings.Kronos.SWEEP_L_WINDUP
            KronosPose.CLEAVE -> t > 12f && t < BossTimings.Kronos.CLEAVE_WINDUP
            KronosPose.SLAM -> t > 8f && t < BossTimings.Kronos.SLAM_WINDUP
            KronosPose.THROW -> t > 8f && t < BossTimings.Kronos.THROW_WINDUP
            KronosPose.DEVOUR -> t > 12f && t < BossTimings.Kronos.DEVOUR_BITE - 3
            else -> false
        }
        if (holding) {
            val shake = sin(age * 2.3f) * 0.012f
            armR.roll += shake
            armL.roll -= shake
            chest.pitch += sin(age * 1.7f) * 0.006f
        }

        when (pose) {
            KronosPose.STUCK -> {
                // Er zerrt an der Harpe: alle 12 Ticks ein Ruck nach hinten.
                val tug = max(0f, sin(t * 0.52f)).let { it * it * it }
                chest.pitch -= tug * 0.12f
                torso.pitch -= tug * 0.06f
                armR.pitch += tug * 0.1f
                armL.pitch += tug * 0.1f
                hips.originZ += tug * 1.5f
            }
            KronosPose.HANDS -> {
                // Er dreht den Oberkörper langsam wie ein Uhrwerk.
                val turn = MathHelper.clamp((t - BossTimings.Kronos.HANDS_WINDUP) / 20f, 0f, 1f)
                torso.yaw += sin((t - BossTimings.Kronos.HANDS_WINDUP) * 0.035f) * 0.55f * turn
            }
            KronosPose.DEVOUR -> {
                // Während des Sogs klappt der Kiefer immer weiter auf.
                val pull = MathHelper.clamp((t - 12f) / (BossTimings.Kronos.DEVOUR_PULL - 12f), 0f, 1f)
                val open = if (t < BossTimings.Kronos.DEVOUR_BITE - 3) pull * 0.22f else 0f
                part(Keys.JAW).pitch += open
                part(Keys.BEARD).pitch += open
                chest.pitch += sin(age * 0.9f) * 0.01f * pull
            }
            KronosPose.CHAINED -> {
                val heave = sin(t * 0.33f)
                chest.pitch -= max(0f, heave) * 0.1f
                armR.pitch += abs(heave) * 0.12f
                armL.pitch += abs(sin(t * 0.33f + 1.1f)) * 0.12f
                head.pitch -= max(0f, heave) * 0.15f
            }
            KronosPose.STAGGER -> chest.roll += sin(t * 0.08f) * 0.04f
            KronosPose.ROAR -> head.yaw += sin(age * 2.1f) * 0.02f
            KronosPose.EMPTY -> part(Keys.FINGERS_R).roll += sin(age * 0.2f) * 0.06f
            KronosPose.DESCEND -> {
                armR.roll += sin(age * 0.5f) * 0.08f
                armL.roll -= sin(age * 0.5f + 0.6f) * 0.08f
            }
        }
        // Fängt er die Harpe wieder, reißt es ihm den Arm mit einem Ruck herunter.
        if (state.previousPose == KronosPose.EMPTY && pose != KronosPose.EMPTY && t < 8f) {
            val jolt = (1f - t / 8f).let { it * it }
            armR.pitch += jolt * 0.45f
            foreR.pitch -= jolt * 0.2f
        }
        // Einschlag nach dem Stillstand: die Faust zuckt nach unten.
        if (pose == KronosPose.HALT) {
            val since = t - BossTimings.Kronos.HALT_HIT
            if (since in 0f..6f) armL.pitch += (1f - since / 6f) * 0.25f
        }

        // Himmelsring: dreht stetig (Form 1 doppelt so schnell), neigt sich mit der Brust.
        val ringSpeed = if (form) 0.024f else 0.012f
        ring.roll += age * ringSpeed + ringOffset(pose, t, ringSpeed)
        ring.pitch += sin(clock * 0.03f) * 0.03f

        // Stundenglas pendelt leicht; der Sand fällt (Form 1 schneller) und steht, solange die Zeit steht.
        glass.roll += sin(clock * 0.05f) * 0.035f - chest.roll * 0.5f
        glass.pitch += sin(clock * 0.037f + 1f) * 0.025f
        val fall = clock * (if (form) 0.7f else 0.35f)
        for ((n, grain) in sand.withIndex()) {
            val p = ((fall + n * 4f) % 8f + 8f) % 8f
            grain.originY = -3f + p
            grain.visible = !still || n == 0
        }

        // Nachschwingen: Ketten, Mantel, Bart und Lendentuch folgen träge und phasenversetzt.
        val walkSwing = state.walk * sin(state.walkPhase - 0.8f)
        part(Keys.CHAIN_R).pitch += sin(clock * 0.09f) * 0.07f + walkSwing * 0.25f
        part(Keys.CHAIN_R_1).pitch += sin(clock * 0.09f - 0.7f) * 0.09f + walkSwing * 0.2f
        part(Keys.CHAIN_L).pitch += sin(clock * 0.085f + 1.3f) * 0.07f - walkSwing * 0.25f
        part(Keys.CHAIN_L_1).pitch += sin(clock * 0.085f + 0.6f) * 0.09f - walkSwing * 0.2f
        part(Keys.CHAIN_R).roll += sin(clock * 0.07f) * 0.05f
        part(Keys.CHAIN_L).roll += sin(clock * 0.07f + 2f) * 0.05f
        for (m in 0..3) {
            part(Keys.MANTLE_0 + m).pitch += sin(clock * 0.06f - m * 0.6f) * (0.02f + m * 0.015f) + state.walk * 0.06f * m
        }
        part(Keys.BEARD_1).pitch += sin(clock * 0.07f - 0.5f) * 0.05f
        part(Keys.LOIN_F).pitch += sin(clock * 0.05f) * 0.02f
        part(Keys.LOIN_B).pitch += sin(clock * 0.05f + 1f) * 0.02f
    }

    /** Ist die Harpe in der Hand zu sehen (nach dem letzten [setAngles])? */
    val harpeShown: Boolean get() = harpe.visible

    /** Lichtanker in Weltkoordinaten; die Maße stammen aus tools/hha_assets/kronos_model.py. */
    fun readAnchors(matrices: MatrixStack, cam: Vec3d, out: BossGlow.KronosFrame) {
        val scratch = Vector3f()
        fun at(x: Float, y: Float, z: Float): Vec3d {
            matrices.peek().positionMatrix.transformPosition(x / 16f, y / 16f, z / 16f, scratch)
            return Vec3d(scratch.x + cam.x, scratch.y + cam.y, scratch.z + cam.z)
        }
        fun inside(vararg path: ModelPart, block: () -> Unit) {
            matrices.push()
            for (p in path) p.applyTransform(matrices)
            block()
            matrices.pop()
        }
        inside(root) {
            inside(hips, torso, glass) { out.chest = at(0f, 0f, 0f) }
            inside(hips, torso, chest) {
                inside(head) {
                    out.eyeR = at(-4f, -11f, -11.2f)
                    out.eyeL = at(4f, -11f, -11.2f)
                    out.mouth = at(0f, -6f, -9f)
                }
                inside(ring) {
                    out.halo = at(0f, 0f, 0f)
                    out.haloUp = at(0f, -1f, 0f).subtract(out.halo)
                    out.haloSide = at(1f, 0f, 0f).subtract(out.halo)
                }
                inside(armL, foreL, handL) { out.handL = at(0f, 6f, 0f) }
                if (harpe.visible) {
                    inside(armR, foreR, harpe) {
                        val edge = ArrayList<Vec3d>(blade.size + 1)
                        matrices.push()
                        blade[0].applyTransform(matrices)
                        // Die Klingenglieder laufen an der Schneide entlang (lokal z = 0).
                        edge.add(at(0f, 0f, 0f))
                        for (i in blade.indices) {
                            if (i > 0) blade[i].applyTransform(matrices)
                            edge.add(at(0f, -Keys.BLADE_LEN[i], 0f))
                        }
                        matrices.pop()
                        out.blade = edge
                    }
                } else {
                    out.blade = emptyList()
                }
            }
        }
    }

    // <keys> erzeugt von tools/hha_assets/kronos_model.py --kotlin; nicht von Hand ändern
    object Keys {
        /** Knochenpfade in Kanalreihenfolge (je 6 Werte: pitch, yaw, roll, dx, dy, dz). */
        val BONES = arrayOf("hips", "hips/torso", "hips/torso/chest", "hips/torso/chest/head", "hips/torso/chest/head/jaw", "hips/torso/chest/head/crown", "hips/torso/chest/head/beard", "hips/torso/chest/head/beard/beard_1", "hips/torso/chest/ring", "hips/torso/glass", "hips/torso/chest/arm_r", "hips/torso/chest/arm_r/fore_r", "hips/torso/chest/arm_r/fore_r/hand_r", "hips/torso/chest/arm_r/fore_r/hand_r/fingers_r", "hips/torso/chest/arm_r/fore_r/harpe", "hips/torso/chest/arm_r/fore_r/chain_r", "hips/torso/chest/arm_r/fore_r/chain_r/chain_r_1", "hips/torso/chest/arm_l", "hips/torso/chest/arm_l/fore_l", "hips/torso/chest/arm_l/fore_l/hand_l", "hips/torso/chest/arm_l/fore_l/hand_l/fingers_l", "hips/torso/chest/arm_l/fore_l/chain_l", "hips/torso/chest/arm_l/fore_l/chain_l/chain_l_1", "hips/torso/chest/mantle_0", "hips/torso/chest/mantle_0/mantle_1", "hips/torso/chest/mantle_0/mantle_1/mantle_2", "hips/torso/chest/mantle_0/mantle_1/mantle_2/mantle_3", "hips/thigh_r", "hips/thigh_r/shin_r", "hips/thigh_r/shin_r/foot_r", "hips/thigh_l", "hips/thigh_l/shin_l", "hips/thigh_l/shin_l/foot_l", "hips/loin_f", "hips/loin_b", "hips/loin_r", "hips/loin_l")
        const val HIPS = 0
        const val TORSO = 1
        const val CHEST = 2
        const val HEAD = 3
        const val JAW = 4
        const val CROWN = 5
        const val BEARD = 6
        const val BEARD_1 = 7
        const val RING = 8
        const val GLASS = 9
        const val ARM_R = 10
        const val FORE_R = 11
        const val HAND_R = 12
        const val FINGERS_R = 13
        const val HARPE = 14
        const val CHAIN_R = 15
        const val CHAIN_R_1 = 16
        const val ARM_L = 17
        const val FORE_L = 18
        const val HAND_L = 19
        const val FINGERS_L = 20
        const val CHAIN_L = 21
        const val CHAIN_L_1 = 22
        const val MANTLE_0 = 23
        const val MANTLE_1 = 24
        const val MANTLE_2 = 25
        const val MANTLE_3 = 26
        const val THIGH_R = 27
        const val SHIN_R = 28
        const val FOOT_R = 29
        const val THIGH_L = 30
        const val SHIN_L = 31
        const val FOOT_L = 32
        const val LOIN_F = 33
        const val LOIN_B = 34
        const val LOIN_R = 35
        const val LOIN_L = 36
        const val COUNT = 37
        /** Klingenglieder der Harpe: Länge und Breite (für die Schneiden-Anker). */
        val BLADE_LEN = floatArrayOf(12f, 12f, 12f, 12f, 11f, 11f, 10f, 9f)
        val BLADE_W = floatArrayOf(10f, 10f, 9f, 8f, 7f, 6f, 4f, 3f)
        /** Schlüsselposen: je Knochen 6 Werte; die Liste nennt Knochen, die in der Pose ausgeblendet sind. */
        val IDLE = key("0.1,0,0,0,0.2079,0,0.34,0,0,0,0,0,0.3,0.05,0.03,0,0,0,-0.5,-0.05,0,0,3,-2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1677,0,0,0,0,0,-0.42,0,0,0,0,0,0,0,0,0,0,0,-0.82,0,0.08,0,0,0,-0.3,0,0,0,0,0,-0.0506,0.201,-0.0051,0,0,0,0,0,-1.1,0,0,0,1.4977,0.194,-0.1197,-0.03,-0.0074,-0.2972,0.3812,0,-0.089,0,0,0,-0.0038,0,0,0,0,0,-0.86,0,-0.06,0,0,0,-0.12,0,0,0,0,0,0,0,0,0,0,0,0,0,1.3,0,0,0,0.2417,0,-0.0198,0,0,0,0.0016,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.5947,0,0,0,0,0,-0.1491,0,0,0,0,0,-0.32,0,0.04,0,0,0,0.38,0,0,0,0,0,-0.16,0,0,0,0,0,-0.12,0,-0.04,0,0,0,0.22,0,0,0,0,0,-0.2001,0,0,0,0,0,-0.32,0,0,0,0,0,-0.02,0,0,0,0,0,0,0,0.13,0,0,0,0,0,-0.08,0,0,0", intArrayOf())
        val SWEEP_R_WINDUP = key("0.1,0.3,0,0,5.2192,0,0.15,0.25,0,0,0,0,0.15,0.4,-0.12,0,0,0,-0.3,-0.65,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0305,0,0,0,0,0,-0.25,0,0,0,0,0,0,0,0,0,0,0,0.0117,0.0547,1.55,0,0,0,-0.675,0,0,0,0,0,-1.202,-0.1592,0.1092,0,0,0,0,0,-1.1,0,0,0,-1.6442,-1.2058,-1.3218,0.6459,-3.753,-5.5257,-0.9713,0,-1.1996,0,0,0,0.5316,0,0,0,0,0,-1,0,-0.25,0,0,0,-0.7,0,0,0,0,0,0,0,0,0,0,0,0,0,0.3,0,0,0,1.0681,0,-1.1094,0,0,0,0.4642,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.2042,0,0,0,0,0,-0.1066,0,0,0,0,0,0.3,0,0.14,0,0,0,0.35,0,0,0,0,0,-0.7565,0,0,0,0,0,-0.8,0,-0.1,0,0,0,0.55,0,0,0,0,0,0.1729,0,0,0,0,0,-0.68,0,0,0,0,0,0.205,0,0,0,0,0,0,0,0.05,0,0,0,0,0,-0.25,0,0,0", intArrayOf())
        val SWEEP_R_HIT = key("0.12,-0.3,0,0,5.6675,0,0.18,-0.25,0,0,0,0,0.18,-0.45,0.08,0,0,0,-0.35,0.5,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.0108,0,0,0,0,0,-0.25,0,0,0,0,0,0,0,0,0,0,0,-2,0.2578,-1.0437,0,0,0,-0.35,0,0,0,0,0,1.5705,-0.146,-0.1459,0,0,0,0,0,-1.1,0,0,0,3.1411,0.8172,-0.1463,-0.8632,-5.8712,5.9362,1.3708,0,0.7713,0,0,0,-0.1406,0,0,0,0,0,0.35,0,-0.5,0,0,0,-0.4,0,0,0,0,0,0,0,0,0,0,0,0,0,1.3,0,0,0,-0.7056,0,-0.0026,0,0,0,-0.1702,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.2611,0,0,0,0,0,-0.1439,0,0,0,0,0,0.3,0,0.1,0,0,0,0.35,0,0,0,0,0,-0.7317,0,0,0,0,0,-0.85,0,-0.12,0,0,0,0.6,0,0,0,0,0,0.0915,0,0,0,0,0,-0.7335,0,0,0,0,0,0.189,0,0,0,0,0,0,0,0.05,0,0,0,0,0,-0.2625,0,0,0", intArrayOf())
        val SWEEP_R_FOLLOW = key("0.2,-0.5,0,0,5.1128,0,0.24,-0.4,0,0,0,0,0.24,-0.5,0.12,0,0,0,-0.35,0.6,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.0154,0,0,0,0,0,-0.25,0,0,0,0,0,0,0,0,0,0,0,-1.5312,0,-0.9961,0,0,0,-0.8172,0,0,0,0,0,-0.7758,0.3548,-0.1463,0,0,0,0,0,-1.1,0,0,0,-2.2765,0.4961,0.0227,-0.82,-1.5497,-3.9398,1.2741,0,1.149,0,0,0,-0.4001,0,0,0,0,0,0.55,0,-0.6,0,0,0,-0.5,0,0,0,0,0,0,0,0,0,0,0,0,0,1.3,0,0,0,-0.9494,0,-0.5886,0,0,0,-0.6377,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.1988,0,0,0,0,0,-0.1625,0,0,0,0,0,0.35,0,0.1,0,0,0,0.5,0,0,0,0,0,-0.975,0,0,0,0,0,-0.9,0,-0.12,0,0,0,0.8,0,0,0,0,0,-0.1442,0,0,0,0,0,-0.835,0,0,0,0,0,0.1625,0,0,0,0,0,0,0,0.05,0,0,0,0,0,-0.275,0,0,0", intArrayOf())
        val SWEEP_L_WINDUP = key("0.05,-0.35,0,0,4.2482,0,0.06,-0.3,0,0,0,0,0.02,-0.5,0.12,0,0,0,-0.2,0.7,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.1051,0,0,0,0,0,-0.15,0,0,0,0,0,0,0,0,0,0,0,-1.6414,-0.9844,0.0039,0,0,0,-1.2531,0,0,0,0,0,-0.8277,0.2776,-0.1226,0,0,0,0,0,-1.1,0,0,0,-1.7688,-0.8452,-1.6779,-0.7054,-1.8232,-4.2493,-3.026,0,2.4881,0,0,0,-2.062,0,0,0,0,0,-1.9594,-0.4102,-0.2711,0,0,0,-1.2016,0,0,0,0,0,-0.4431,0.8787,-0.211,0,0,0,0,0,1,0,0,0,2.8646,0,2.7955,0,0,0,-2.4626,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.0189,0,0,0,0,0,-0.0128,0,0,0,0,0,-0.7,0,0.12,0,0,0,0.5,0,0,0,0,0,0.1823,0,0,0,0,0,0.3,0,-0.14,0,0,0,0.3,0,0,0,0,0,-0.6568,0,0,0,0,0,-0.565,0,0,0,0,0,0.245,0,0,0,0,0,0,0,0.225,0,0,0,0,0,-0.05,0,0,0", intArrayOf())
        val SWEEP_L_HIT = key("0.12,0.35,0,0,4.8054,0,0.16,0.3,0,0,0,0,0.16,0.5,-0.08,0,0,0,-0.35,-0.6,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0294,0,0,0,0,0,-0.25,0,0,0,0,0,0,0,0,0,0,0,-1.95,-0.3594,0.9234,0,0,0,-0.4,0,0,0,0,0,1.5702,0.0728,0.0728,0,0,0,0,0,-1.1,0,0,0,3.1404,-0.9942,0.0738,0.4351,-5.9645,5.9841,1.4266,0,-1.0864,0,0,0,-0.1976,0,0,0,0,0,-1.4953,0,0.2852,0,0,0,-0.5625,0,0,0,0,0,1.4743,-0.5986,-0.5462,0,0,0,0,0,1,0,0,0,1.6376,0,-1.4511,0,0,0,-0.2797,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.198,0,0,0,0,0,-0.1297,0,0,0,0,0,-0.8,0,0.12,0,0,0,0.55,0,0,0,0,0,0.0839,0,0,0,0,0,0.3,0,-0.12,0,0,0,0.35,0,0,0,0,0,-0.7168,0,0,0,0,0,-0.696,0,0,0,0,0,0.189,0,0,0,0,0,0,0,0.25,0,0,0,0,0,-0.05,0,0,0", intArrayOf())
        val SWEEP_L_FOLLOW = key("0.2,0.5,0,0,4.5269,0,0.22,0.4,0,0,0,0,0.22,0.55,-0.12,0,0,0,-0.35,-0.6,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.0073,0,0,0,0,0,-0.25,0,0,0,0,0,0,0,0,0,0,0,-1.2328,0.0078,0.5219,0,0,0,-0.8492,0,0,0,0,0,-1.1722,-0.2902,0.1934,0,0,0,0,0,-1.1,0,0,0,-2.5183,-0.8801,-0.1927,1.1051,-3.4107,-5.2985,1.1006,0,-1.255,0,0,0,-0.1274,0,0,0,0,0,-0.6406,0.5,1.1328,0,0,0,-0.8461,0,0,0,0,0,1.5684,-0.1913,-0.1909,0,0,0,0,0,1,0,0,0,-1.0728,0,-1.3214,0,0,0,0.9977,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.1688,0,0,0,0,0,-0.1474,0,0,0,0,0,-0.85,0,0.12,0,0,0,0.8,0,0,0,0,0,-0.1884,0,0,0,0,0,0.35,0,-0.12,0,0,0,0.5,0,0,0,0,0,-0.9721,0,0,0,0,0,-0.7975,0,0,0,0,0,0.1625,0,0,0,0,0,0,0,0.2625,0,0,0,0,0,-0.05,0,0,0", intArrayOf())
        val CLEAVE_WINDUP = key("-0.06,0,0,0,-0.3674,0,-0.1,0,0,0,0,0,-0.16,0,0,0,0,0,-0.2,0,0,0,-2,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.364,0,0,0,0,0,0.1,0,0,0,0,0,0,0,0,0,0,0,-2.7039,-0.7656,0.1339,0,0,0,-0.55,0,0,0,0,0,1.5162,0.0246,0.0233,0,0,0,0,0,-1.1,0,0,0,3.0654,-0.7718,0.0778,0.1397,-5.6695,5.9893,-2.7406,0,2.773,0,0,0,-1.7633,0,0,0,0,0,-2.3687,0.5977,-0.3878,0,0,0,-1.0836,0,0,0,0,0,1.57,-0.256,-0.2558,0,0,0,0,0,1,0,0,0,-2.5606,0,-2.6111,0,0,0,-1.4293,0,0,0,0,0,0.06,0,0,0,0,0,0.13,0,0,0,0,0,0.1324,0,0,0,0,0,0.0331,0,0,0,0,0,0.08,0,0.06,0,0,0,0,0,0,0,0,0,-0.0201,0,0,0,0,0,-0.25,0,-0.06,0,0,0,0.1,0,0,0,0,0,0.2103,0,0,0,0,0,-0.1395,0,0,0,0,0,0.168,0,0,0,0,0,0,0,0.05,0,0,0,0,0,-0.1125,0,0,0", intArrayOf())
        val CLEAVE_HIT = key("0.25,0,0,0,6.3222,0,0.35,0,0,0,0,0,0.42,0,0,0,0,0,-0.6,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.294,0,0,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,-1.5969,-0.25,0.0227,0,0,0,-0.6102,0,0,0,0,0,1.0928,0.1328,0.0808,0,0,0,0,0,-1.1,0,0,0,2.6638,-0.1291,0.2159,0.4801,-3.1919,5.2806,1.1984,0,0.4847,0,0,0,-0.0337,0,0,0,0,0,-1.3117,0.5,-0.1398,0,0,0,-0.9187,0,0,0,0,0,1.0287,-0.3337,-0.1898,0,0,0,0,0,1,0,0,0,1.2249,0,-0.8928,0,0,0,-0.0995,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.8051,0,0,0,0,0,-0.2013,0,0,0,0,0,0.3,0,0.08,0,0,0,0.45,0,0,0,0,0,-1.0016,0,0,0,0,0,-0.95,0,-0.06,0,0,0,0.95,0,0,0,0,0,-0.25,0,0,0,0,0,-0.9125,0,0,0,0,0,0.085,0,0,0,0,0,0,0,0.05,0,0,0,0,0,-0.2875,0,0,0", intArrayOf())
        val STUCK = key("0.18,0.12,0,0,4.8072,0,0.25,0.05,0,0,0,0,0.3,0.05,0,0,0,0,-0.35,-0.15,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.2586,0,0,0,0,0,-0.25,0,0,0,0,0,0,0,0,0,0,0,-0.7648,-0.25,-0.682,0,0,0,-0.8297,0,0,0,0,0,1.1824,-0.3872,-0.2617,0,0,0,0,0,-1.1,0,0,0,2.6214,-0.7876,-0.0294,-1.4373,-3.2625,5.142,1.0236,0,-0.0358,0,0,0,0.1503,0,0,0,0,0,-0.2852,0.25,0.5511,0,0,0,-1.3945,0,0,0,0,0,0.9504,-0.026,-0.0134,0,0,0,0,0,1,0,0,0,1.0438,0,-0.5713,0,0,0,-0.1327,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.5805,0,0,0,0,0,-0.1519,0,0,0,0,0,0.5,0,0.12,0,0,0,0.35,0,0,0,0,0,-1.0353,0,0,0,0,0,-0.7,0,-0.08,0,0,0,0.95,0,0,0,0,0,-0.4195,0,0,0,0,0,-0.669,0,0,0,0,0,0.291,0,0,0,0,0,0,0,0.05,0,0,0,0,0,-0.225,0,0,0", intArrayOf())
        val SLAM_WINDUP = key("-0.04,0,0,0,0.623,0,-0.15,0,0,0,0,0,-0.22,0,0,0,0,0,-0.4,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.567,0,0,0,0,0,0.15,0,0,0,0,0,0,0,0,0,0,0,-2.3914,0.3906,0.25,0,0,0,-0.8016,0,0,0,0,0,0.093,0.5447,0.026,0,0,0,0,0,-1.1,0,0,0,1.3022,-0.4881,-2.763,0.1334,-0.0204,0.4766,-2.6823,0,2.5586,0,0,0,-1.8321,0,0,0,0,0,-2.3992,-0.3828,-0.25,0,0,0,-0.7781,0,0,0,0,0,0,0,0,0,0,0,0,0,1.3,0,0,0,-2.6972,0,-2.5664,0,0,0,-1.8631,0,0,0,0,0,0.06,0,0,0,0,0,0.175,0,0,0,0,0,0.1662,0,0,0,0,0,0.0415,0,0,0,0,0,-0.25,0,0.2,0,0,0,0.4,0,0,0,0,0,-0.113,0,0,0,0,0,-0.25,0,-0.2,0,0,0,0.4,0,0,0,0,0,-0.113,0,0,0,0,0,-0.1555,0,0,0,0,0,0.092,0,0,0,0,0,0,0,0.1125,0,0,0,0,0,-0.1125,0,0,0", intArrayOf())
        val SLAM_HIT = key("0.3,0,0,0,17.2087,0,0.42,0,0,0,0,0,0.45,0,0,0,0,0,-0.5,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.469,0,0,0,0,0,-0.3,0,0,0,0,0,0,0,0,0,0,0,-1.2766,-0.1914,-0.0105,0,0,0,-0.625,0,0,0,0,0,-1.2763,1.1308,-0.8797,0,0,0,0,0,-1.1,0,0,0,3.0077,-0.3859,2.0447,-1.969,-0.887,-2.4453,-0.7009,0,0.2142,0,0,0,-0.0107,0,0,0,0,0,-1.2102,0.1797,0.0144,0,0,0,-0.7148,0,0,0,0,0,0,0,0,0,0,0,0,0,1.3,0,0,0,-0.6774,0,-0.2017,0,0,0,-0.0103,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.9176,0,0,0,0,0,-0.2294,0,0,0,0,0,-1.5,0,0.12,0,0,0,1.6,0,0,0,0,0,-0.4007,0,0,0,0,0,-0.15,0,-0.1,0,0,0,1.55,0,0,0,0,0,-1.7008,0,0,0,0,0,-1.365,0,0,0,0,0,-0.18,0,0,0,0,0,0,0,0.425,0,0,0,0,0,-0.0875,0,0,0", intArrayOf())
        val THROW_WINDUP = key("0.15,0.5,0,0,1.939,0,0.15,0.4,0,0,0,0,0.15,0.55,-0.12,0,0,0,-0.35,-1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.1217,0,0,0,0,0,-0.2,0,0,0,0,0,0,0,0,0,0,0,-0.0484,0.1016,0.9078,0,0,0,-0.7531,0,0,0,0,0,-1.1667,-0.4693,0.313,0,0,0,0,0,-1.1,0,0,0,-0.3583,0.0194,-2.6449,1.6476,-2.9875,-4.9204,-0.8047,0,-0.7045,0,0,0,0.4467,0,0,0,0,0,-1.45,0,0.3,0,0,0,-0.15,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1,0,0,0,0.1885,0,-1.055,0,0,0,0.6702,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.0882,0,0,0,0,0,-0.0789,0,0,0,0,0,0.15,0,0.18,0,0,0,0.75,0,0,0,0,0,-1.0307,0,0,0,0,0,-0.7,0,-0.14,0,0,0,0.6,0,0,0,0,0,0.0242,0,0,0,0,0,-0.645,0,0,0,0,0,0.0525,0,0,0,0,0,0,0,0.05,0,0,0,0,0,-0.225,0,0,0", intArrayOf())
        val THROW_RELEASE = key("0.12,-0.45,0,0,4.801,0,0.15,-0.35,0,0,0,0,0.18,-0.55,0.1,0,0,0,-0.3,0.45,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0045,0,0,0,0,0,-0.2,0,0,0,0,0,0,0,0,0,0,0,-2.2016,0.4688,-0.95,0,0,0,-0.4484,0,0,0,0,0,-1.381,0.2053,-0.1698,0,0,0,0,0,-0.2,0,0,0,-2.7752,1.0292,0.1088,-0.9924,-4.6814,-5.7686,1.6301,0,1.5646,0,0,0,-0.1376,0,0,0,0,0,0.35,0,-0.6,0,0,0,-0.4,0,0,0,0,0,0,0,0,0,0,0,0,0,1.3,0,0,0,-0.7593,0,-0.1972,0,0,0,-0.4635,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.123,0,0,0,0,0,-0.0989,0,0,0,0,0,0.4,0,0.12,0,0,0,0.4,0,0,0,0,0,-0.8468,0,0,0,0,0,-0.8,0,-0.1,0,0,0,0.55,0,0,0,0,0,0.076,0,0,0,0,0,-0.696,0,0,0,0,0,0.264,0,0,0,0,0,0,0,0.05,0,0,0,0,0,-0.25,0,0,0", intArrayOf())
        val EMPTY = key("0.05,-0.1,0,0,0.0595,0,0.12,0,0,0,0,0,0.12,-0.15,0,0,0,0,-0.35,0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0424,0,0,0,0,0,-0.15,0,0,0,0,0,0,0,0,0,0,0,-2.35,0,0.35,0,0,0,-0.25,0,0,0,0,0,-0.3,0,0,0,0,0,0,0,0.15,0,0,0,0,0,0,0,0,0,2.356,0,2.2739,0,0,0,0.8117,0,0,0,0,0,-0.45,0,-0.1,0,0,0,-0.2,0,0,0,0,0,0,0,0,0,0,0,0,0,1.3,0,0,0,0.3308,0,0.1787,0,0,0,0.0269,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.2522,0,0,0,0,0,-0.0685,0,0,0,0,0,-0.2,0,0.06,0,0,0,0.3,0,0,0,0,0,-0.1435,0,0,0,0,0,-0.2,0,-0.06,0,0,0,0.3,0,0,0,0,0,-0.1553,0,0,0,0,0,-0.19,0,0,0,0,0,0.02,0,0,0,0,0,0,0,0.1,0,0,0,0,0,-0.1,0,0,0", intArrayOf(14))
        val STEP = key("0.15,0,0,0,2.8364,0,0.3,0,0,0,0,0,0.4,0,0,0,0,0,-0.45,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.28,0,0,0,0,0,-0.4,0,0,0,0,0,0,0,0,0,0,0,-0.3,0,-0.12,0,0,0,-1.35,0,0,0,0,0,-1.1258,-0.1846,0.1167,0,0,0,0,0,-1.1,0,0,0,0.4491,-0.2256,0.0966,0.6868,-3.3192,-5.3237,0.7994,0,-0.0122,0,0,0,0.0039,0,0,0,0,0,-0.3,0,0.12,0,0,0,-1.45,0,0,0,0,0,0,0,0,0,0,0,0,0,1.3,0,0,0,0.8987,0,0.0308,0,0,0,0.0051,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.6776,0,0,0,0,0,-0.1694,0,0,0,0,0,0.1,0,0.04,0,0,0,0.2,0,0,0,0,0,-0.4502,0,0,0,0,0,-1.15,0,-0.04,0,0,0,1.35,0,0,0,0,0,-0.3502,0,0,0,0,0,-0.9825,0,0,0,0,0,0.015,0,0,0,0,0,0,0,0.05,0,0,0,0,0,-0.3375,0,0,0", intArrayOf())
        val HANDS = key("0,0,0,0,-0.5289,0,0,0,0,0,0,0,-0.05,0,0,0,0,0,-0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.105,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1.55,0,0,0,0,0,0,0,0,0,-1.5708,-0.0208,0.0208,0,0,0,0,0,-1.1,0,0,0,3.1416,1.5208,0.0208,0.1247,-5.9974,-5.9987,0.05,0,-1.395,0,0,0,-0.0005,0,0,0,0,0,0,0,-1.55,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1,0,0,0,0.05,0,1.395,0,0,0,-0.0005,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.0026,0,0,0,0,0,-0.0006,0,0,0,0,0,0,0,0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.06,0,0,0,0,0,0,0,0.05,0,0,0,0,0,-0.05,0,0,0", intArrayOf())
        val HALT_RAISE = key("0.06,-0.15,0,0,1.444,0,0.1,-0.1,0,0,0,0,0.06,-0.25,0,0,0,0,-0.25,0.3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.0253,0,0,0,0,0,-0.1,0,0,0,0,0,0,0,0,0,0,0,-0.4,0,0.3,0,0,0,-0.35,0,0,0,0,0,0.3811,0.8659,0.1778,0,0,0,0,0,-1.1,0,0,0,1.9643,0.8615,0.1942,0.6877,-0.2176,1.446,0.5739,0,0.0964,0,0,0,0.1118,0,0,0,0,0,-1.6,0,0.05,0,0,0,-0.05,0,0,0,0,0,-1.2,0,0,0,0,0,0,0,-0.1,0,0,0,1.4179,0,1.1701,0,0,0,0.0049,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.1883,0,0,0,0,0,-0.0637,0,0,0,0,0,0.25,0,0.08,0,0,0,0.2,0,0,0,0,0,-0.4969,0,0,0,0,0,-0.45,0,-0.06,0,0,0,0.35,0,0,0,0,0,0.0308,0,0,0,0,0,-0.3855,0,0,0,0,0,0.1995,0,0,0,0,0,0,0,0.05,0,0,0,0,0,-0.1625,0,0,0", intArrayOf())
        val HALT_CLOSE = key("0.1,-0.15,0,0,1.5713,0,0.12,-0.1,0,0,0,0,0.15,-0.3,0,0,0,0,-0.3,0.3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.0367,0,0,0,0,0,-0.15,0,0,0,0,0,0,0,0,0,0,0,-0.4,0,0.3,0,0,0,-0.35,0,0,0,0,0,0.0231,0.9415,0.0117,0,0,0,0,0,-1.1,0,0,0,1.6492,0.9395,0.0802,0.0415,-0.0007,0.0815,0.4292,0,0.0748,0,0,0,0.1064,0,0,0,0,0,-1.35,0,0.05,0,0,0,-0.65,0,0,0,0,0,-0.2,0,0,0,0,0,0,0,1.45,0,0,0,1.6568,0,1.5371,0,0,0,-0.0006,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.2888,0,0,0,0,0,-0.1017,0,0,0,0,0,0.3,0,0.08,0,0,0,0.25,0,0,0,0,0,-0.6378,0,0,0,0,0,-0.5,0,-0.06,0,0,0,0.45,0,0,0,0,0,-0.0583,0,0,0,0,0,-0.455,0,0,0,0,0,0.205,0,0,0,0,0,0,0,0.05,0,0,0,0,0,-0.175,0,0,0", intArrayOf())
        val REWIND = key("0.05,0,0,0,-0.1441,0,0.12,0,0,0,0,0,0.14,0,0,0,0,0,-0.3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.007,0,0,0,0,0,-0.15,0,0,0,0,0,0,0,0,0,0,0,-0.0789,-0.2539,-0.5164,0,0,0,-1.9234,0,0,0,0,0,-0.3057,1.3656,-0.2493,0,0,0,0,0,-1.1,0,0,0,-0.2099,-0.3689,1.7101,-0.3017,-0.0189,-0.3681,1.7574,0,-2.788,0,0,0,0.3121,0,0,0,0,0,-0.4187,0.5,0.95,0,0,0,-0.4898,0,0,0,0,0,-0.8877,-0.168,0.08,0,0,0,0,0,1,0,0,0,1.1885,0,-1.1031,0,0,0,-0.4449,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.2726,0,0,0,0,0,-0.0681,0,0,0,0,0,-0.15,0,0.1,0,0,0,0.25,0,0,0,0,0,-0.1505,0,0,0,0,0,-0.15,0,-0.1,0,0,0,0.25,0,0,0,0,0,-0.1505,0,0,0,0,0,-0.1525,0,0,0,0,0,0.02,0,0,0,0,0,0,0,0.0875,0,0,0,0,0,-0.0875,0,0,0", intArrayOf())
        val REWIND_B = key("0.05,0,0,0,-0.1441,0,0.12,0,0,0,0,0,0.14,0,0,0,0,0,-0.3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.007,0,0,0,0,0,-0.15,0,0,0,0,0,0,0,0,0,0,0,-0.1375,-0.3281,-0.5398,0,0,0,-2.0406,0,0,0,0,0,0.2243,0.6198,0.0721,0,0,0,0,0,-1.1,0,0,0,-1.0713,-0.4417,1.9332,0.3518,-0.1097,1.0864,1.9384,0,-2.6628,0,0,0,0.6009,0,0,0,0,0,-0.1219,0.2539,0.5945,0,0,0,-1.9508,0,0,0,0,0,0.0114,-1.262,-0.0084,0,0,0,0,0,1,0,0,0,1.8122,0,2.5121,0,0,0,0.4034,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.2726,0,0,0,0,0,-0.0681,0,0,0,0,0,-0.15,0,0.1,0,0,0,0.25,0,0,0,0,0,-0.1505,0,0,0,0,0,-0.15,0,-0.1,0,0,0,0.25,0,0,0,0,0,-0.1505,0,0,0,0,0,-0.1525,0,0,0,0,0,0.02,0,0,0,0,0,0,0,0.0875,0,0,0,0,0,-0.0875,0,0,0", intArrayOf())
        val DEVOUR_INHALE = key("0.25,0,0,0,2.9088,0,0.35,0,0,0,0,0,0.4,0,0,0,0,0,-0.65,0,0,0,0,0,1.05,0,0,0,0,0,0,0,0,0,0,0,1.05,0,0,0,0,0,-0.98,0,0,0,0,0,-0.5,0,0,0,0,0,0,0,0,0,0,0,-1,0,1.25,0,0,0,-0.5,0,0,0,0,0,-0.3841,-0.8296,0.1708,0,0,0,0,0,-1.1,0,0,0,-0.8727,0.6143,-2.5031,0.6887,-0.2362,-1.5181,0.5484,0,-0.0708,0,0,0,0.2265,0,0,0,0,0,-1,0,-1.25,0,0,0,-0.5,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1,0,0,0,0.5484,0,0.0708,0,0,0,0.2265,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.7901,0,0,0,0,0,-0.1975,0,0,0,0,0,-0.55,0,0.18,0,0,0,0.75,0,0,0,0,0,-0.4532,0,0,0,0,0,-0.55,0,-0.18,0,0,0,0.75,0,0,0,0,0,-0.4532,0,0,0,0,0,-0.6125,0,0,0,0,0,-0.14,0,0,0,0,0,0,0,0.1875,0,0,0,0,0,-0.1875,0,0,0", intArrayOf())
        val DEVOUR_BITE = key("0.3,0,0,0,3.4878,0,0.4,0,0,0,0,0,0.45,0,0,0,0,0,-0.35,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.56,0,0,0,0,0,-0.5,0,0,0,0,0,0,0,0,0,0,0,-1.35,0,-0.35,0,0,0,-0.6,0,0,0,0,0,1.2836,-0.7658,-0.5848,0,0,0,0,0,-1.1,0,0,0,2.7429,1.0169,-1.1307,-2.3874,-2.3814,4.148,0.299,0,-0.1601,0,0,0,0.0592,0,0,0,0,0,-1.35,0,0.35,0,0,0,-0.6,0,0,0,0,0,0,0,0,0,0,0,0,0,1.3,0,0,0,0.059,0,0.1601,0,0,0,0.0592,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.9026,0,0,0,0,0,-0.2256,0,0,0,0,0,-0.6,0,0.14,0,0,0,0.8,0,0,0,0,0,-0.5019,0,0,0,0,0,-0.6,0,-0.14,0,0,0,0.8,0,0,0,0,0,-0.5019,0,0,0,0,0,-0.69,0,0,0,0,0,-0.18,0,0,0,0,0,0,0,0.2,0,0,0,0,0,-0.2,0,0,0", intArrayOf())
        val DEVOUR_RECOVER = key("0.3,0,0,0,3.2504,0,0.42,0,0,0,0,0,0.5,0,0.06,0,0,0,-0.2,0,0.08,0,0,0,0.4,0,0,0,0,0,0,0,0,0,0,0,0.4,0,0,0,0,0,-0.9972,0,0,0,0,0,-0.55,0,0.15,0,0,0,0,0,0,0,0,0,-0.7,0,0.12,0,0,0,-0.15,0,0,0,0,0,-1.0384,0.3501,-0.2015,0,0,0,0,0,-1.1,0,0,0,0.501,0.1215,-0.3346,-1.1279,-2.6611,-4.8559,-0.3642,0,-0.1303,0,0,0,-0.0132,0,0,0,0,0,-0.75,0,-0.1,0,0,0,-0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0.4,0,0,0,-1.0883,0,0.0093,0,0,0,-0.0008,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.9557,0,0,0,0,0,-0.2379,0,0,0,0,0,-0.6,0,0.14,0,0,0,0.85,0,0,0,0,0,-0.5524,0,0,0,0,0,-0.45,0,-0.14,0,0,0,0.7,0,0,0,0,0,-0.5524,0,0,0,0,0,-0.69,0,0,0,0,0,-0.18,0,0,0,0,0,0,0,0.2,0,0,0,0,0,-0.1625,0,0,0", intArrayOf())
        val CHAINED = key("-0.1,0,0,0,0.4403,0,-0.2,0,0,0,0,0,-0.25,0,0,0,0,0,-0.55,0,0,0,0,0,0.45,0,0,0,0,0,0,0,0,0,0,0,0.45,0,0,0,0,0,0.455,0,0,0,0,0,0.2,0,0.2,0,0,0,0,0,0,0,0,0,0.75,0,0.45,0,0,0,-0.1,0,0,0,0,0,0.1289,-0.6032,-0.0402,0,0,0,0,0,-1.1,0,0,0,-1.9888,0.4654,2.5459,-0.1984,-0.037,0.6354,-0.1511,0,-0.3216,0,0,0,0.0672,0,0,0,0,0,0.75,0,-0.45,0,0,0,-0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1,0,0,0,-0.1511,0,0.3216,0,0,0,0.0672,0,0,0,0,0,0.06,0,0,0,0,0,0.245,0,0,0,0,0,0.2187,0,0,0,0,0,0.0547,0,0,0,0,0,-0.2,0,0.12,0,0,0,0.35,0,0,0,0,0,-0.0511,0,0,0,0,0,0.1,0,-0.12,0,0,0,0.3,0,0,0,0,0,-0.3026,0,0,0,0,0,-0.07,0,0,0,0,0,0.215,0,0,0,0,0,0,0,0.1,0,0,0,0,0,-0.05,0,0,0", intArrayOf())
        val STAGGER = key("0.25,0.15,0.05,0,13.5294,0,0.35,0,0,0,0,0,0.45,0.1,0.1,0,0,0,-0.2,0,0.1,0,0,0,0.3,0,0,0,0,0,0,0,0,0,0,0,0.3,0,0,0,0,0,-0.8158,0,0,0,0,0,-0.2,0.35,0.6,0,0,0,0,0,0,0,0,0,-0.3,0,0.35,0,0,0,-0.3,0,0,0,0,0,-0.5385,0.9059,-0.267,0,0,0,0,0,-0.3,0,0,0,0.5589,0.0489,-1.1579,-0.9768,-0.3928,-1.8986,-0.5056,0,-0.3833,0,0,0,0.0153,0,0,0,0,0,-0.9023,-0.25,-0.0883,0,0,0,-0.8063,0,0,0,0,0,0,0,0,0,0,0,0,0,1.3,0,0,0,-0.7548,0,-0.0273,0,0,0,0.0069,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.8207,0,0,0,0,0,-0.2232,0,0,0,0,0,-1.25,0,0.12,0,0,0,1.45,0,0,0,0,0,-0.4645,0,0,0,0,0,0.25,0,-0.08,0,0,0,1.45,0,0,0,0,0,-1.9464,0,0,0,0,0,-1.1375,0,0,0,0,0,0.0475,0,0,0,0,0,0,0,0.3125,0,0,0,0,0,-0.1,0,0,0", intArrayOf())
        val ROAR = key("-0.05,0,0,0,0.2059,0,-0.15,0,0,0,0,0,-0.2,0,0,0,0,0,-0.75,0,0,0,0,0,1,0,0,0,0,0,0,0,0,0,0,0,1,0,0,0,0,0,0.105,0,0,0,0,0,0.15,0,0,0,0,0,0,0,0,0,0,0,-0.55,0,1,0,0,0,-0.5,0,0,0,0,0,1.4224,1.3342,1.1924,0,0,0,0,0,-1.1,0,0,0,0.1229,1.2841,-1.687,1.307,-0.3117,1.391,1.6703,0,-1.6046,0,0,0,-0.4069,0,0,0,0,0,-0.55,0,-1,0,0,0,-0.5,0,0,0,0,0,0,0,0,0,0,0,0,0,0.3,0,0,0,1.6703,0,1.6046,0,0,0,-0.4069,0,0,0,0,0,0.06,0,0,0,0,0,0.17,0,0,0,0,0,0.1624,0,0,0,0,0,0.0406,0,0,0,0,0,-0.2,0,0.14,0,0,0,0.3,0,0,0,0,0,-0.051,0,0,0,0,0,-0.2,0,-0.14,0,0,0,0.3,0,0,0,0,0,-0.051,0,0,0,0,0,-0.11,0,0,0,0,0,0.1,0,0,0,0,0,0,0,0.1,0,0,0,0,0,-0.1,0,0,0", intArrayOf())
        val CAST = key("0.05,0,0,0,-0.2704,0,0.1,0,0,0,0,0,0.05,0,0,0,0,0,-0.3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0.07,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.6,0,0.35,0,0,0,-0.9,0,0,0,0,0,-0.4403,0.3281,-0.074,0,0,0,0,0,-1.1,0,0,0,1.1466,0.3491,-0.0258,-0.4202,-0.5261,-2.4207,1.3043,0,-0.0827,0,0,0,0.0044,0,0,0,0,0,-1.25,0,-0.6,0,0,0,-0.95,0,0,0,0,0,-0.5,0,0,0,0,0,0,0,-0.1,0,0,0,1.9118,0,-2.0971,0,0,0,0.4568,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.1901,0,0,0,0,0,-0.0475,0,0,0,0,0,-0.12,0,0.1,0,0,0,0.2,0,0,0,0,0,-0.1304,0,0,0,0,0,-0.12,0,-0.1,0,0,0,0.2,0,0,0,0,0,-0.1304,0,0,0,0,0,-0.13,0,0,0,0,0,0.02,0,0,0,0,0,0,0,0.08,0,0,0,0,0,-0.08,0,0,0", intArrayOf())
        val WINDUP = key("0.1,0.15,0,0,1.1988,0,0.2,0.1,0,0,0,0,0.2,0.2,0,0,0,0,-0.3,-0.25,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1253,0,0,0,0,0,-0.2,0,0,0,0,0,0,0,0,0,0,0,-1.6,0,0.45,0,0,0,-1.5,0,0,0,0,0,-0.2546,0.2746,-0.0354,0,0,0,0,0,-1.1,0,0,0,1.7132,-0.3452,-2.8127,-0.2042,-0.1826,-1.4548,2.4414,0,2.5426,0,0,0,1.2178,0,0,0,0,0,-0.4,0,-0.35,0,0,0,-0.45,0,0,0,0,0,0,0,0,0,0,0,0,0,1.3,0,0,0,0.416,0,-0.009,0,0,0,0.0664,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.3914,0,0,0,0,0,-0.1229,0,0,0,0,0,0.15,0,0.12,0,0,0,0.25,0,0,0,0,0,-0.5123,0,0,0,0,0,-0.5,0,-0.1,0,0,0,0.4,0,0,0,0,0,0.0154,0,0,0,0,0,-0.455,0,0,0,0,0,0.0925,0,0,0,0,0,0,0,0.05,0,0,0,0,0,-0.175,0,0,0", intArrayOf())
        val DESCEND = key("-0.1,0,0,0,0,0,-0.15,0,0,0,0,0,-0.1,0,0,0,0,0,0.1,0,0,0,0,0,0.35,0,0,0,0,0,0,0,0,0,0,0,0.35,0,0,0,0,0,-0.07,0,0,0,0,0,0.3,0,0.3,0,0,0,0,0,0,0,0,0,-2.3,0,1.1,0,0,0,-0.3,0,0,0,0,0,1.3183,1.3722,1.1306,0,0,0,0,0,-1.1,0,0,0,3.0873,0.432,1.3556,1.0708,-0.2086,1.1461,2.5668,0,1.9691,0,0,0,0.8743,0,0,0,0,0,-2.3,0,-1.1,0,0,0,-0.3,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.1,0,0,0,2.5668,0,-1.9691,0,0,0,0.8743,0,0,0,0,0,0.06,0,0,0,0,0,0.145,0,0,0,0,0,0.1437,0,0,0,0,0,0.0359,0,0,0,0,0,-0.5,0,0.12,0,0,0,0.9,0,0,0,0,0,-0.3026,0,0,0,0,0,0.2,0,-0.1,0,0,0,0.4,0,0,0,0,0,-0.5023,0,0,0,0,0,-0.295,0,0,0,0,0,0.29,0,0,0,0,0,0,0,0.175,0,0,0,0,0,-0.05,0,0,0", intArrayOf())
        val BOUND = key("0.1,0,0,0,17.1286,0,0.3,0,0,0,0,0,0.35,0,0,0,0,0,0.1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-0.595,0,0,0,0,0,-0.6,0.2,0.4,0,0,0,0,0,0,0,0,0,0.6,0,0.3,0,0,0,-0.3,0,0,0,0,0,0,0,0,0,0,0,0,0,-1.1,0,0,0,0,0,0,0,0,0,-1.0631,0,-0.4899,0,0,0,0.0296,0,0,0,0,0,0.6,0,-0.3,0,0,0,-0.3,0,0,0,0,0,0,0,0,0,0,0,0,0,1.3,0,0,0,-1.0631,0,0.4899,0,0,0,0.0296,0,0,0,0,0,0.06,0,0,0,0,0,0.04,0,0,0,0,0,-0.6026,0,0,0,0,0,-0.1506,0,0,0,0,0,-0.25,0,0.1,0,0,0,1.6,0,0,0,0,0,-1.4511,0,0,0,0,0,-0.25,0,-0.1,0,0,0,1.6,0,0,0,0,0,-1.4511,0,0,0,0,0,-0.2675,0,0,0,0,0,-0.02,0,0,0,0,0,0,0,0.1125,0,0,0,0,0,-0.1125,0,0,0", intArrayOf(14))
    }
    // </keys>
}

class KronosRenderer(context: EntityRendererFactory.Context) :
    LivingEntityRenderer<KronosEntity, KronosRenderState, KronosModel>(context, KronosModel(context.getPart(BossLayers.KRONOS)), 1.6f) {

    private val texture = Hha.id("textures/entity/boss/kronos.png")
    private val glowLayer = RenderLayers.eyes(Hha.id("textures/entity/boss/kronos_glow.png"))

    init {
        addFeature(object : FeatureRenderer<KronosRenderState, KronosModel>(this) {
            override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: KronosRenderState, limbAngle: Float, limbDistance: Float) {
                val pulse = 0.82f + 0.18f * sin(state.age * (if (state.form > 0) 0.22f else 0.11f))
                val alpha = (pulse * 255f).toInt()
                queue.getBatchingQueue(1).submitModel(contextModel, state, matrices, glowLayer, 0xF000F0, OverlayTexture.DEFAULT_UV, (alpha shl 24) or 0xFFFFFF, null, 0, null)
                val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
                val frame = BossGlow.KronosFrame(state.entityId, state.age, state.form, state.bossPose)
                frame.harpeOut = !model.harpeShown
                model.readAnchors(matrices, cam, frame)
                BossGlow.submit(frame)
            }
        })
    }

    override fun createRenderState() = KronosRenderState()
    override fun getTexture(state: KronosRenderState): Identifier = texture

    override fun updateRenderState(entity: KronosEntity, state: KronosRenderState, tickDelta: Float) {
        super.updateRenderState(entity, state, tickDelta)
        state.entityId = entity.id
        state.bossPose = entity.pose
        state.previousPose = entity.previousPose
        state.poseTime = (entity.age - entity.poseChangedAge) + tickDelta
        state.form = entity.form
        state.harpeOut = entity.harpeOut
        state.antiAir = entity.antiAir
        state.walk = entity.walkAmount
        state.walkPhase = entity.walkPhase
    }

    override fun scale(state: KronosRenderState, matrices: MatrixStack) {
        val size = SCALE * (1f + state.form * KronosEntity.FORM_GROWTH)
        matrices.scale(size, size, size)
    }

    override fun canBeCulled(entity: KronosEntity): Boolean = false

    override fun hasLabel(entity: KronosEntity, squaredDistance: Double): Boolean = false

    private companion object {
        /** 144 Einheiten Modellhöhe × 0,69 = 6,2 Blöcke (Plan 6.3). */
        const val SCALE = 0.69f
    }
}
