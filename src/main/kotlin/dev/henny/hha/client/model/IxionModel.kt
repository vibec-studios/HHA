package dev.henny.hha.client.model

import dev.henny.hha.entity.miniboss.IxionAction
import dev.henny.hha.logic.boss.BossTimings
import net.minecraft.client.model.ModelPart
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin

/**
 * Ixion, ins brennende Rad gespannt (Brief `tmp/overhaul/models/ixion.md`, Plan 4.6).
 *
 * Er IST das Rad: `root` (Schweben) > `tilt` (kippt das Rad, 0 = aufrecht, π/2 = waagerecht, Vorderseite oben) >
 * `wheel` (dreht um die Achse; Felge, Nabe, Flammenflügel, vier Schellen und der Körper drehen mit).
 *
 * Alle Schlüsselposen stehen mit denselben Zahlen in `tools/hha_assets/ixion_model.py` (`POSES`); die Zeiten kommen
 * aus [BossTimings.Ixion]. Drehung, Kippen, Schwebehöhe und Flügel folgen ihren Zielwerten weich nach, damit
 * Aktionswechsel (auch vorzeitige Abbrüche durch den Server) nie springen. Den Zustand hält die Modellinstanz je
 * Wesen (nach Entity-ID); ein Sprung in `age` setzt ihn sauber zurück.
 */
class IxionModel(root: ModelPart) : GuardianModel(root) {
    private val base = root.getChild("root")
    private val tilt = base.getChild("tilt")
    private val wheel = tilt.getChild("wheel")
    private val wingR = wheel.getChild("flame_wing_r")
    private val wingL = wheel.getChild("flame_wing_l")
    private val platesR = List(4) { wingR.getChild("wing_r_$it") }
    private val platesL = List(4) { wingL.getChild("wing_l_$it") }

    /** Reihenfolge, in der sich die Fesseln lockern: rechter Arm, linker Arm, rechtes Bein, (linkes Bein). */
    private val shackles = listOf("shackle_ar", "shackle_al", "shackle_lr", "shackle_ll").map { wheel.getChild(it) }
    private val body = wheel.getChild("body")
    private val head = body.getChild("head")
    private val jaw = head.getChild("jaw")
    private val armR = body.getChild("arm_r")
    private val armL = body.getChild("arm_l")
    private val foreR = armR.getChild("fore_r")
    private val foreL = armL.getChild("fore_l")
    private val legR = body.getChild("leg_r")
    private val legL = body.getChild("leg_l")
    private val shinR = legR.getChild("shin_r")
    private val shinL = legL.getChild("shin_l")

    /** Geglätteter Zustand je Wesen: Ein Modell zeichnet alle Ixions, jeder behält aber seine eigene Drehung. */
    private class Memory(
        var lastAge: Float = Float.NaN, var spin: Float = 0f, var speed: Float = IDLE_SPIN, var sTilt: Float = 0f,
        var sYaw: Float = 0f, var sDy: Float = HOVER, var sFold: Float = 0f, var sSpread: Float = 0f,
        var seen: Long = 0L,
    )

    private val memories = HashMap<Int, Memory>()
    private var lastWorldId = 0

    private fun load(id: Int, age: Float) {
        // Verwaiste Einträge aufräumen: gemessen an der gemeinsamen Uhr des Clients (Spielzeit der Welt), nicht am Alter
        // der Wesen, das für jedes anders läuft. Ein Weltwechsel (Zeit springt zurück) leert die Tabelle.
        val clientWorld = net.minecraft.client.MinecraftClient.getInstance().world
        // Neue Welt (Dimensionswechsel, neu verbunden): Die alten Einträge gehören zu anderen Wesen.
        val worldId = System.identityHashCode(clientWorld)
        if (worldId != lastWorldId) {
            memories.clear()
            lastWorldId = worldId
        }
        val now = clientWorld?.time ?: 0L
        memories.entries.removeIf { now < it.value.seen || now - it.value.seen > 200L }
        val m = memories.getOrPut(id) { Memory() }
        m.seen = now
        lastAge = m.lastAge; spin = m.spin; speed = m.speed; sTilt = m.sTilt; sYaw = m.sYaw; sDy = m.sDy; sFold = m.sFold; sSpread = m.sSpread
    }

    private fun store(id: Int) {
        val m = memories.getOrPut(id) { Memory() }
        m.lastAge = lastAge; m.spin = spin; m.speed = speed; m.sTilt = sTilt; m.sYaw = sYaw; m.sDy = sDy; m.sFold = sFold; m.sSpread = sSpread
    }

    // Geglätteter Zustand des gerade gezeichneten Wesens (aus [memories] geladen).
    private var lastAge = Float.NaN
    private var spin = 0f
    private var speed = IDLE_SPIN
    private var sTilt = 0f
    private var sYaw = 0f
    private var sDy = HOVER
    private var sFold = 0f
    private var sSpread = 0f

    // Zielwerte dieses Bildes.
    private var tTilt = 0f
    private var tYaw = 0f
    private var tDy = HOVER
    private var tFold = 0f
    private var tSpread = 0f
    private var tSpeed = IDLE_SPIN

    /**
     * Gelockerte Fesseln (0–3), vom Server über `IxionEntity.looseFetters` bis in den RenderState gereicht.
     */
    private fun loosened(state: GuardianRenderState): Int = state.fetters

    override fun setAngles(state: GuardianRenderState) {
        super.setAngles(state)
        val t = state.age
        val a = state.actionTime
        val act = state.action

        // ---------------- Zielwerte je Aktion ----------------
        tTilt = 0f; tYaw = 0f; tDy = HOVER; tFold = 0f; tSpread = 0f; tSpeed = IDLE_SPIN + walk(state) * 0.12f
        var press = 0f          // presst sich an die Speichen (Rollen)
        var scream = 0f         // Kopf zurück, Mund weit auf
        var slack = 0f          // hängt schlaff (Kippen)
        var look = 0f           // schaut nach oben (Speichenfeuer)
        var cock = 0f           // Funkenwurf: Kopf zurückgezogen
        var spit = 0f           // Funkenwurf: Kopf vorgerissen
        var fall = 0f           // FALLEN-Haltung 0..1
        var rollBack = 0f       // Zurückrollen vor dem Anziehen
        var snap = 0f           // RISE: Fesseln schnappen zu
        var bounce = 0f
        val spinRate: Float
        when (act) {
            IxionAction.ROLL_WINDUP -> {
                val w = BossTimings.Ixion.ROLL_WINDUP.toFloat()
                tYaw = HALF_PI * step(a, 0f, 10f)
                tDy = lerp(HOVER, ROLL_WINDUP_DY, step(a, 4f, 16f))
                tFold = step(a, 4f, 14f)
                press = step(a, 0f, 8f)
                rollBack = ROLL_BACK * step(a, 0f, 8f) * (1f - step(a, 10f, w))
                tSpeed = ROLL_SPIN * 0.8f * step(a, 10f, w)
            }
            IxionAction.ROLL -> {
                tYaw = HALF_PI; tDy = ROLL_DY; tFold = 1f; tSpeed = ROLL_SPIN
                press = 1f; scream = 1f
                bounce = -abs(sin(a * 0.8f)) * 1.2f
            }
            IxionAction.WOBBLE -> {
                val open = BossTimings.Ixion.WOBBLE_WINDOW.toFloat()
                val right = 1f - step(a, open, open + BossTimings.Ixion.WOBBLE_RIGHT)
                val angle = step(a, 0f, 6f) * (WOBBLE_MID + WOBBLE_AMP * sin(a * 0.45f - HALF_PI)) * right
                tTilt = angle
                tYaw = (HALF_PI + 0.4f * (1f - cos(a * 0.15f))) * right
                tDy = wobbleDy(angle) + (HOVER - wobbleDy(0f)) * (1f - right)
                tFold = 0.4f * right
                tSpeed = ROLL_SPIN * (1f - step(a, 0f, 20f)) + 0.02f
                slack = step(a, 0f, 6f) * right
            }
            IxionAction.SPOKES -> {
                val w = BossTimings.Ixion.SPOKES_WINDUP.toFloat()
                val end = w + BossTimings.Ixion.SPOKES_SWEEP
                val up = step(a, 0f, w) * (1f - step(a, end, end + 10f))
                tTilt = HALF_PI * up
                tDy = lerp(HOVER, SPOKES_DY, up)
                tSpread = step(a, 8f, w) * (1f - step(a, end, end + 10f))
                look = up
                tSpeed = if (a < w) 0.01f else if (a < end) SPOKES_SPIN else IDLE_SPIN * step(a, end, end + 10f)
            }
            IxionAction.SPARKS -> {
                val first = BossTimings.Ixion.SPARK_SHOT_1.toFloat()
                val gap = BossTimings.Ixion.SPARK_GAP.toFloat()
                val last = first + gap * (BossTimings.Ixion.SPARK_COUNT - 1)
                val end = BossTimings.Ixion.SPARKS_END.toFloat()
                for (i in 0 until BossTimings.Ixion.SPARK_COUNT) spit = max(spit, pulse(a - (first + gap * i)))
                cock = step(a, 0f, 8f) * (1f - step(a, last + 4f, end)) * (1f - spit)
                tSpread = 0.6f * cock + 0.7f * spit
                tSpeed = IDLE_SPIN * 0.5f
            }
            IxionAction.FALLEN -> {
                fall = step(a, 0f, BossTimings.Ixion.FALLEN_DROP.toFloat())
                tTilt = HALF_PI; tDy = FALL_DY; tSpeed = 0f
            }
            IxionAction.RISE -> {
                fall = 1f - step(a, 0f, 20f)
                val up = step(a, 4f, 24f)
                tTilt = HALF_PI * (1f - up)
                tDy = lerp(FALL_DY, HOVER, up)
                tSpeed = IDLE_SPIN * step(a, 10f, BossTimings.Ixion.RISE.toFloat())
                snap = step(a, 20f, 22f) * (1f - step(a, 22f, 30f))
            }
        }

        // ---------------- Weich nachführen ----------------
        load(state.entityId, t)
        val dt = t - lastAge
        lastAge = t
        if (dt.isNaN() || dt < 0f || dt > 4f) {
            spin = t * IDLE_SPIN; speed = tSpeed
            sTilt = tTilt; sYaw = tYaw; sDy = tDy; sFold = tFold; sSpread = tSpread
        } else {
            val k = 1f - exp(-FOLLOW * dt)
            val ks = 1f - exp(-SPIN_FOLLOW * dt)
            sTilt += (tTilt - sTilt) * k
            sYaw += (tYaw - sYaw) * k
            sDy += (tDy - sDy) * k
            sFold += (tFold - sFold) * k
            sSpread += (tSpread - sSpread) * k
            speed += (tSpeed - speed) * ks
            if (act == IxionAction.FALLEN) {
                // Liegend: das Rad dreht sich zurück, bis die Knöchel-Schellen vorn liegen (volle Umdrehung).
                val target = round(spin / TWO_PI) * TWO_PI
                spin += (target - spin) * k
            } else {
                spin += speed * dt
            }
            if (abs(spin) > 4000f) spin %= TWO_PI
        }
        spinRate = speed
        store(state.entityId)

        // ---------------- Rad ----------------
        val float = 1f - fall
        base.yaw = sYaw
        base.originY += sDy + bounce + sin(t * 0.06f) * 1.5f * float * (1f - step(abs(sDy - HOVER), 0f, 3f) * 0.6f)
        tilt.pitch = -sTilt + 0.1f * spit - 0.17f * cock          // Funkenwurf: aufbäumen, dann Ruck nach vorn
        wheel.roll = spin + rollBack
        // Im rasenden Rad schlägt der Körper gegen die Speichen.
        body.originZ += abs(sin(t * 1.3f)) * 0.6f * step(spinRate, IDLE_SPIN * 4f, ROLL_SPIN)

        // ---------------- Flammenflügel ----------------
        val wingScale = 1f - fall
        for ((side, wing, plates) in listOf(Triple(-1f, wingR, platesR), Triple(1f, wingL, platesL))) {
            wing.yaw += side * (-0.75f * sFold + 0.45f * sSpread)
            wing.roll += side * (-0.25f * sFold)
            wing.xScale = wingScale; wing.yScale = wingScale; wing.zScale = wingScale
            wing.visible = wingScale > 0.03f
            plates.forEachIndexed { i, p ->
                val fan = (i - 1.5f) * 0.16f * sSpread - 0.12f * sFold * (i - 1.5f)
                val flicker = 0.07f * sin(t * 0.9f + i * 1.3f + side) * (1f - 0.6f * sFold)
                p.roll += side * (fan + flicker)
                p.yaw += 0.05f * sin(t * 0.7f + i * 2.1f)
                p.xScale = 1f + 0.08f * sin(t * 0.6f + i * 1.7f + side)
            }
        }

        // ---------------- Körper: Grundbewegung (Zerren in Wellen, Kopf rollt) ----------------
        val loose = loosened(state)
        val idle = float * (1f - press) * (1f - slack)
        fun tug(i: Int): Float {
            val w = max(0f, sin(t * 0.09f - i * 1.6f))
            return w * w * if (i < loose) 2.5f else 1f
        }
        armR.roll += -0.07f * tug(0) * idle; armR.pitch += -0.05f * tug(0) * idle
        armL.roll += 0.07f * tug(1) * idle; armL.pitch += -0.05f * tug(1) * idle
        legR.roll += -0.05f * tug(2) * idle; legR.pitch += -0.04f * tug(2) * idle
        legL.roll += 0.05f * tug(3) * idle; legL.pitch += -0.04f * tug(3) * idle
        foreR.pitch += 0.06f * tug(0) * idle; foreL.pitch += 0.06f * tug(1) * idle
        body.pitch += -0.04f * sin(t * 0.09f) * idle
        body.originZ += -0.6f * (tug(0) + tug(1)) * 0.5f * idle
        head.roll += (0.12f + 0.18f * sin(t * 0.07f)) * idle
        head.yaw += (0.1f + 0.2f * cos(t * 0.07f)) * idle
        jaw.pitch += (0.1f + 0.12f * sin(t * 0.13f)) * idle

        // ---------------- Aktionsposen (gleiche Werte wie POSES) ----------------
        // roll_windup / roll: an die Speichen gepresst, Kopf eingezogen; im Rollen schreit er.
        body.originZ += 1f * press
        head.pitch += lerp(0.45f, 0.3f, scream) * press
        jaw.pitch += lerp(-0.3f, 0.3f, scream) * press
        armR.pitch += 0.15f * press; armR.roll += -0.06f * press
        armL.pitch += 0.15f * press; armL.roll += 0.06f * press
        legR.pitch += 0.12f * press; legR.roll += 0.05f * press
        legL.pitch += 0.12f * press; legL.roll += -0.05f * press
        foreR.pitch += 0.1f * press * (1f - scream); foreL.pitch += 0.1f * press * (1f - scream)

        // wobble: schlaff.
        head.pitch += 0.55f * slack; head.roll += 0.4f * slack; jaw.pitch += 0.35f * slack
        armR.pitch += -0.1f * slack; armR.roll += 0.1f * slack
        armL.pitch += -0.1f * slack; armL.roll += -0.1f * slack
        foreR.pitch += 0.45f * slack; foreL.pitch += 0.4f * slack
        shinR.pitch += 0.3f * slack; shinL.pitch += 0.25f * slack

        // spokes: schaut in den Himmel und schreit, Arme zerren nach vorn.
        head.pitch += -0.3f * look; jaw.pitch += 0.3f * look
        armR.pitch += -0.12f * look; armR.roll += 0.08f * look
        armL.pitch += -0.12f * look; armL.roll += -0.08f * look

        // sparks: zurückziehen (windup) und vorreißen (spit); jeder Schuss ist ein Ruck durch Körper und Rad.
        body.pitch += -0.08f * cock + 0.12f * spit
        body.originZ += -1f * cock - 2.5f * spit
        head.pitch += -0.45f * cock + 0.75f * spit
        jaw.pitch += -0.35f * cock + 0.55f * spit
        armR.pitch += 0.1f * cock - 0.12f * spit; armL.pitch += 0.1f * cock - 0.12f * spit
        foreR.pitch += 0.2f * spit; foreL.pitch += 0.2f * spit

        // Schläge mit der Hand (Nahkampf): kurzer Ruck nach vorn.
        if (state.swing > 0f) {
            val s = sin(state.swing * PI.toFloat())
            body.originZ += -1.5f * s
            head.pitch += 0.4f * s
        }

        // ---------------- Fesseln ----------------
        shackles.forEachIndexed { i, sh ->
            val side = if (i % 2 == 0) 1f else -1f
            val looseNow = if (i < loose) 1f else 0f
            val broken = if (i < 2) fall else 0f
            sh.pitch += -0.25f * looseNow * (1f - broken) + sin(t * 0.3f + i) * 0.05f * looseNow + -0.6f * broken
            sh.roll += side * 0.3f * broken
            // RISE: die Schellen schnappen zu (kurzer Überschwinger).
            sh.pitch += if (i < 2) 0.25f * snap else 0f
        }

        // ---------------- FALLEN: vom Rad gerissen, hängt nur an den Knöcheln ----------------
        if (fall > 0f) {
            val breath = sin(t * 0.15f)
            body.pitch += PI.toFloat() * fall + 0.04f * breath * fall
            body.originX += FALL_BODY_X * fall
            body.originY += FALL_BODY_Y * fall
            body.originZ += FALL_BODY_Z * fall
            head.pitch += -1.15f * fall
            head.roll += 0.15f * sin(t * 0.11f) * fall
            jaw.pitch += 0.4f * fall
            armR.pitch = lerp(armR.pitch, -HALF_PI, fall); armR.yaw = lerp(armR.yaw, 0f, fall); armR.roll = lerp(armR.roll, -0.15f, fall)
            armL.pitch = lerp(armL.pitch, -HALF_PI, fall); armL.yaw = lerp(armL.yaw, 0f, fall); armL.roll = lerp(armL.roll, 0.15f, fall)
            foreR.pitch = lerp(foreR.pitch, -HALF_PI + 0.1f, fall); foreR.yaw = lerp(foreR.yaw, 0f, fall)
            foreL.pitch = lerp(foreL.pitch, -HALF_PI + 0.1f, fall); foreL.yaw = lerp(foreL.yaw, 0f, fall)
            // Er zerrt an den Knöcheln: alle 25 Ticks ein Ruck.
            val jerk = pulse((t % 25f) - 2f) * fall
            legR.pitch = lerp(legR.pitch, 0.12f, fall) + 0.08f * jerk; legR.roll = lerp(legR.roll, LEG_ROLL, fall)
            legL.pitch = lerp(legL.pitch, 0.12f, fall) + 0.08f * jerk; legL.roll = lerp(legL.roll, -LEG_ROLL, fall)
            shinR.pitch = lerp(shinR.pitch, 0.1f, fall); shinL.pitch = lerp(shinL.pitch, 0.1f, fall)
            armR.roll += -0.05f * breath * fall; armL.roll += 0.05f * breath * fall
        }
    }

    /** Ruck: in 2 Ticks hoch, dann abklingend (Funkenwurf, Zerren). */
    private fun pulse(x: Float): Float = when {
        x < 0f -> 0f
        x < 2f -> x / 2f
        else -> exp(-(x - 2f) / 3f)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        const val HALF_PI = (PI / 2).toFloat()
        const val TWO_PI = (PI * 2).toFloat()

        /** Ruhe: eine Umdrehung in 160 Ticks. */
        const val IDLE_SPIN = TWO_PI / 160f

        /** ROLL: eine Umdrehung in 8 Ticks. */
        const val ROLL_SPIN = TWO_PI / 8f

        /** SPOKES: eine Dreiviertelumdrehung in [BossTimings.Ixion.SPOKES_SWEEP] Ticks. */
        const val SPOKES_SPIN = (PI * 1.5).toFloat() / BossTimings.Ixion.SPOKES_SWEEP

        const val FOLLOW = 0.45f
        const val SPIN_FOLLOW = 0.25f

        // Gleiche Zahlen wie in ixion_model.py.
        const val HOVER = -4f
        const val ROLL_WINDUP_DY = -1f
        const val ROLL_DY = -1.5f
        const val ROLL_BACK = -0.26f
        const val SPOKES_DY = -6f
        const val FALL_DY = 33.5f
        const val FALL_BODY_X = 0f
        const val FALL_BODY_Y = 39.5f
        const val FALL_BODY_Z = 2.5f
        const val LEG_ROLL = 0.93f
        const val WOBBLE_MID = 1.0908f      // 62,5°
        const val WOBBLE_AMP = 0.2182f      // 12,5° → pendelt zwischen 50° und 75°

        /** Senkt das gekippte Rad, bis die Felge den Boden berührt. */
        fun wobbleDy(tilt: Float) = 36f - 37f * cos(tilt) - 3f * sin(tilt)
    }
}
