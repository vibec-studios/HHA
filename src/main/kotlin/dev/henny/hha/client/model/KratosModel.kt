package dev.henny.hha.client.model

import dev.henny.hha.client.anim.Motion
import dev.henny.hha.client.anim.PoseMemory
import dev.henny.hha.client.anim.Spring
import dev.henny.hha.client.cine.CinePlayer
import dev.henny.hha.client.vfx.fx.boss.KratosFx
import dev.henny.hha.entity.boss.olymp.BiaAction
import dev.henny.hha.entity.boss.olymp.KratosAction
import dev.henny.hha.logic.boss.timings.KratosTimings
import dev.henny.hha.stage.scripts.KratosStages
import net.minecraft.client.MinecraftClient
import net.minecraft.client.model.ModelPart
import net.minecraft.entity.LivingEntity
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Schlüsselpose aus `tools/hha_assets/kratos_model.py`: je Knochen sechs Zahlen (pitch, yaw, roll, Versatz x/y/z;
 * NaN = Grundlage aus Ruhe/Gang). [pins] hält für die Requisiten (Hammer, Schild) eine freie Lage im Modellraum (je
 * sechs Zahlen, NaN = in der Hand): Dann liegt oder steckt das Requisit, auch wenn sich der Körper weiter bewegt.
 */
class ThronePose(val bones: FloatArray, val pins: FloatArray? = null)

/** Ein Schlüsselbild der Zeitleiste: ab [at] Ticks gilt [pose] (null = Ruhe/Gang); [hard] = Schlag (beschleunigt). */
class ThroneKey(val at: Float, val pose: ThronePose?, val hard: Boolean = false)

/**
 * Gemeinsamer Unterbau von Kratos und Bia: Knochen aus einer Tabelle, Zeitleisten aus Schlüsselposen (Ausholen weich,
 * Schlag beschleunigt) und frei liegende Requisiten. Gerechnet wird nur in Python (IK, Bodenkontakt); hier wird nur
 * zwischen den fertigen Posen überblendet.
 *
 * Überarbeitung 3 (PLAN 2.1 Punkt 3): Ein Aktionswechsel blendet aus der zuletzt **gezeigten** Pose über [FADE] Ticks
 * in die neue ([remember], `PoseMemory`), nicht mehr aus „vorige Aktion bei ihrem Alter“. Dazu je Wesen ein [Rig] mit
 * Federn ([springSpec]) und Bewegungsmessung (`Motion`); vergessen wird ein Wesen, das [RIG_FORGET_NS] lang kein Bild
 * bekam.
 */
abstract class ThroneGuardModel(
    root: ModelPart,
    private val names: Array<String>,
    private val parentOf: IntArray,
    propNames: Array<String>,
    /** Federn je Wesen: (Frequenz rad/Tick, Dämpfung), Reihenfolge legt die Unterklasse fest. */
    private val springSpec: Array<Pair<Float, Float>> = emptyArray(),
) : GuardianModel(root) {
    protected val parts: Array<ModelPart>
    private val rest: FloatArray
    private val base: FloatArray
    private val props: IntArray

    /** Gewicht der laufenden Zeitleiste (beim Ausblenden der vorigen Bewegung < 1). */
    private var weight = 1f

    // Freie Requisiten: Ziel im Modellraum, am Ende jedes Bilds gegen die endgültige Elternkette gerechnet.
    private val pinActive: BooleanArray
    private val pinQ: Array<Quaternionf>
    private val pinP: Array<Vector3f>
    private val qa = Quaternionf()
    private val qb = Quaternionf()
    private val qp = Quaternionf()
    private val qt = Quaternionf()
    private val pa = Vector3f()
    private val pb = Vector3f()
    private val pp = Vector3f()
    private val vt = Vector3f()
    private val euler = Vector3f()
    private val scratch = FloatArray(6)
    private val chainBuf = IntArray(names.size)

    init {
        val list = ArrayList<ModelPart>(names.size)
        for (i in names.indices) {
            val parent = parentOf[i]
            list.add(if (parent < 0) root.getChild(names[i]) else list[parent].getChild(names[i]))
        }
        parts = list.toTypedArray()
        rest = FloatArray(parts.size * 3)
        for (i in parts.indices) {
            rest[i * 3] = parts[i].originX
            rest[i * 3 + 1] = parts[i].originY
            rest[i * 3 + 2] = parts[i].originZ
        }
        base = FloatArray(parts.size * 6)
        props = IntArray(propNames.size) { names.indexOf(propNames[it]) }
        pinActive = BooleanArray(props.size)
        pinQ = Array(props.size) { Quaternionf() }
        pinP = Array(props.size) { Vector3f() }
    }

    protected fun bone(name: String): ModelPart = parts[names.indexOf(name)]

    /** Zustand eines Wesens zwischen zwei Bildern (Überblenden, Federn, Bewegung, Stöße). */
    protected class Rig(size: Int, spec: Array<Pair<Float, Float>>) {
        val memory = PoseMemory(size)
        val target = FloatArray(size)
        val shown = FloatArray(size)
        var hasShown = false
        val springs = Array(spec.size) { Spring(spec[it].first, spec[it].second) }
        val motion = Motion()
        /** Zeitschritt dieses Bilds in Ticks (0 beim ersten Bild und beim zweiten Aufruf im selben Bild). */
        var dt = 0f
        var lastAge = Float.NaN
        var lastAction = Int.MIN_VALUE
        var lastActionTime = 0f
        /** Für Stöße an Schlag-Marken: Aktion und Aktionszeit des vorigen Bilds. */
        var kickAction = Int.MIN_VALUE
        var kickTime = 0f
        /** Bia: Landung dieser Aktion schon gestoßen; letzte gemessene Senkrecht-Geschwindigkeit. */
        var landed = false
        var lastVy = 0f
        var seen = 0L
    }

    private val rigs = HashMap<Int, Rig>()
    private var pruneClock = 0

    /** Rig des Wesens aus [state] (neu bei einem neuen oder zurückgesetzten Wesen); setzt [Rig.dt]. */
    protected fun rig(state: GuardianRenderState): Rig {
        val now = System.nanoTime()
        if (++pruneClock >= RIG_PRUNE_EVERY) {
            pruneClock = 0
            rigs.values.removeIf { now - it.seen > RIG_FORGET_NS }
        }
        var r = rigs.getOrPut(state.entityId) { Rig(parts.size * 6, springSpec) }
        if (!r.lastAge.isNaN() && state.age < r.lastAge - 1f) {
            r = Rig(parts.size * 6, springSpec)
            rigs[state.entityId] = r
        }
        r.seen = now
        r.dt = if (r.lastAge.isNaN()) 0f else (state.age - r.lastAge).coerceIn(0f, 4f)
        r.lastAge = state.age
        return r
    }

    /** Das Wesen hinter [state] in der Client-Welt (für `Motion`), sonst `null`. */
    protected fun entity(state: GuardianRenderState): LivingEntity? =
        MinecraftClient.getInstance().world?.getEntityById(state.entityId) as? LivingEntity

    /** Misst die Bewegung des Wesens (Tempo, Fallen, Drehrate, Neustart einer Aktion) für dieses Bild. */
    protected fun measure(state: GuardianRenderState, rig: Rig): LivingEntity? {
        val e = entity(state) ?: return null
        rig.motion.update(e, (state.age - e.age).coerceIn(0f, 1f), state.action, state.actionTime.toInt())
        return e
    }

    /**
     * Überblenden aus der zuletzt gezeigten Pose: liest alle Knochen (nach [settlePins]), mischt sie mit `PoseMemory`
     * und schreibt das Ergebnis zurück. Winkel werden vorher auf die Seite der gezeigten Pose gelegt (kein Umweg über
     * ±π bei frei liegenden Requisiten). Ein Neustart derselben Aktion blendet ebenfalls.
     */
    protected fun remember(state: GuardianRenderState, rig: Rig) {
        val restart = state.action == rig.lastAction && state.actionTime < rig.lastActionTime - 0.5f
        rig.lastAction = state.action
        rig.lastActionTime = state.actionTime
        val tg = rig.target
        for (i in parts.indices) {
            val part = parts[i]
            val o = i * 6
            tg[o] = part.pitch
            tg[o + 1] = part.yaw
            tg[o + 2] = part.roll
            tg[o + 3] = part.originX
            tg[o + 4] = part.originY
            tg[o + 5] = part.originZ
        }
        if (rig.hasShown) {
            for (i in parts.indices) for (k in 0 until 3) {
                val o = i * 6 + k
                var v = tg[o]
                val ref = rig.shown[o]
                while (v - ref > PI_F) v -= TWO_PI_F
                while (v - ref < -PI_F) v += TWO_PI_F
                tg[o] = v
            }
        }
        val out = rig.memory.blend(state.action, tg, state.age, FADE, restart)
        out.copyInto(rig.shown)
        rig.hasShown = true
        for (i in parts.indices) {
            val o = i * 6
            val part = parts[i]
            part.pitch = out[o]
            part.yaw = out[o + 1]
            part.roll = out[o + 2]
            part.originX = out[o + 3]
            part.originY = out[o + 4]
            part.originZ = out[o + 5]
        }
    }

    /** Hat die laufende Aktion seit dem vorigen Bild die Aktionszeit [mark] überschritten? (Stoß an Schlag-Marken.) */
    protected fun crossed(state: GuardianRenderState, rig: Rig, mark: Float): Boolean {
        if (state.action != rig.kickAction) return state.actionTime >= mark && state.actionTime < mark + 1f
        return rig.kickTime < mark && state.actionTime >= mark
    }

    /** Merkt Aktion und Zeit dieses Bilds für [crossed] (am Ende der Stöße aufrufen). */
    protected fun kicked(state: GuardianRenderState, rig: Rig) {
        rig.kickAction = state.action
        rig.kickTime = state.actionTime
    }

    /** Beginn jedes Bilds: keine Requisite liegt frei. */
    protected fun beginPose() {
        pinActive.fill(false)
        weight = 1f
    }

    /** Setzt eine Pose absolut (z. B. die Ruhe ohne Schild), bevor Gang und Atmen dazukommen. */
    protected fun applyPose(pose: ThronePose) {
        val b = pose.bones
        for (i in parts.indices) {
            val o = i * 6
            if (b[o].isNaN()) continue
            write(i, b[o], b[o + 1], b[o + 2], b[o + 3], b[o + 4], b[o + 5])
        }
    }

    /** Merkt sich die Ruhe-/Ganglage aller Knochen (Ziel von `null` in der Zeitleiste). */
    protected fun capture() {
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

    /**
     * Spielt eine Zeitleiste mit dem Gewicht [w] (1 = ganz, kleiner = nur anteilig über der Grundlage). Vor dem
     * ersten Schlüssel passiert nichts; nach dem letzten bleibt dessen Pose stehen.
     */
    protected fun play(time: Float, keys: Array<ThroneKey>, w: Float = 1f) {
        if (keys.isEmpty() || time <= keys[0].at || w <= 0f) return
        weight = w
        var i = 0
        while (i < keys.size - 1 && time >= keys[i + 1].at) i++
        if (i == keys.size - 1) {
            blend(keys[i].pose, keys[i].pose, 1f)
        } else {
            val from = keys[i]
            val to = keys[i + 1]
            val u = ((time - from.at) / (to.at - from.at)).coerceIn(0f, 1f)
            blend(from.pose, to.pose, if (to.hard) u * u else u * u * (3f - 2f * u))
        }
        weight = 1f
    }

    private fun blend(from: ThronePose?, to: ThronePose?, u: Float) {
        for (i in parts.indices) {
            val o = i * 6
            val f = from?.bones
            val t = to?.bones
            val fromSet = f != null && !f[o].isNaN()
            val toSet = t != null && !t[o].isNaN()
            if (!fromSet && !toSet) continue
            val v = scratch
            for (k in 0 until 6) {
                val x = if (fromSet) f!![o + k] else base[o + k]
                val y = if (toSet) t!![o + k] else base[o + k]
                val m = x + (y - x) * u
                v[k] = base[o + k] + (m - base[o + k]) * weight
            }
            write(i, v[0], v[1], v[2], v[3], v[4], v[5])
        }
        // Requisiten, die an einem Ende frei liegen: in Modellraum überblenden.
        for (k in props.indices) {
            val fp = from?.pins?.takeIf { !it[k * 6].isNaN() }
            val tp = to?.pins?.takeIf { !it[k * 6].isNaN() }
            if (fp == null && tp == null) continue
            val i = props[k]
            parentWorld(parentOf[i], qp, pp)
            worldOf(from, fp, k, i, qa, pa)
            worldOf(to, tp, k, i, qb, pb)
            qa.slerp(qb, u)
            pa.lerp(pb, u)
            if (weight < 1f) {
                worldOf(null, null, k, i, qb, pb)
                qb.slerp(qa, weight, qa)
                pb.lerp(pa, weight, pa)
            }
            pinActive[k] = true
            pinQ[k].set(qa)
            pinP[k].set(pa)
        }
    }

    /** Lage eines Requisits im Modellraum: frei aus [pin] oder in der Hand aus [pose] bzw. der Grundlage. */
    private fun worldOf(pose: ThronePose?, pin: FloatArray?, k: Int, i: Int, q: Quaternionf, p: Vector3f) {
        if (pin != null) {
            q.rotationZYX(pin[k * 6 + 2], pin[k * 6 + 1], pin[k * 6])
            p.set(pin[k * 6 + 3], pin[k * 6 + 4], pin[k * 6 + 5])
            return
        }
        val o = i * 6
        val b = pose?.bones?.takeIf { !it[o].isNaN() }
        val pitch = b?.get(o) ?: base[o]
        val yaw = b?.get(o + 1) ?: base[o + 1]
        val roll = b?.get(o + 2) ?: base[o + 2]
        vt.set(rest[i * 3] + (b?.get(o + 3) ?: base[o + 3]), rest[i * 3 + 1] + (b?.get(o + 4) ?: base[o + 4]),
            rest[i * 3 + 2] + (b?.get(o + 5) ?: base[o + 5]))
        qp.transform(vt)
        p.set(pp).add(vt)
        q.set(qp).mul(qt.rotationZYX(roll, yaw, pitch))
    }

    /** Lage des Knochens [index] im Modellraum aus den jetzigen Werten der Kette (wie ModelPart.applyTransform). */
    private fun parentWorld(index: Int, q: Quaternionf, p: Vector3f) {
        q.identity()
        p.set(0f, 0f, 0f)
        if (index < 0) return
        val chain = chainBuf
        var n = 0
        var j = index
        while (j >= 0) {
            chain[n++] = j
            j = parentOf[j]
        }
        for (c in n - 1 downTo 0) {
            val part = parts[chain[c]]
            vt.set(part.originX, part.originY, part.originZ)
            q.transform(vt)
            p.add(vt)
            q.mul(qt.rotationZYX(part.roll, part.yaw, part.pitch))
        }
    }

    /** Ende jedes Bilds: frei liegende Requisiten gegen die endgültige Elternkette zurückrechnen. */
    protected fun settlePins() {
        for (k in props.indices) {
            if (!pinActive[k]) continue
            val i = props[k]
            parentWorld(parentOf[i], qp, pp)
            qp.conjugate()
            vt.set(pinP[k]).sub(pp)
            qp.transform(vt)
            qa.set(qp).mul(pinQ[k])
            val e = qa.getEulerAnglesZYX(euler)
            val part = parts[i]
            part.pitch = e.x
            part.yaw = e.y
            part.roll = e.z
            part.originX = vt.x
            part.originY = vt.y
            part.originZ = vt.z
        }
    }

    private fun write(i: Int, pitch: Float, yaw: Float, roll: Float, dx: Float, dy: Float, dz: Float) {
        val part = parts[i]
        part.pitch = pitch
        part.yaw = yaw
        part.roll = roll
        part.originX = rest[i * 3] + dx
        part.originY = rest[i * 3 + 1] + dy
        part.originZ = rest[i * 3 + 2] + dz
    }

    /** 1 zwischen [from] und [to], mit kurzen weichen Rändern. */
    protected fun window(t: Float, from: Float, to: Float) = step(t, from, from + 3f) * (1f - step(t, to - 3f, to))

    /**
     * Spielt die laufende Bewegung. Den Übergang aus der vorigen macht jetzt [remember] (aus der gezeigten Pose), nicht
     * mehr ein Nachspielen der vorigen Aktion mit ihrer eigenen Uhr.
     */
    protected fun playActions(state: GuardianRenderState, timeline: (Int, Float) -> Array<ThroneKey>?) {
        if (state.action != 0) timeline(state.action, state.actionTime)?.let { play(state.actionTime, it) }
    }

    protected companion object {
        const val N = Float.NaN
        const val FADE = 6f

        /** Rig vergessen nach so langer Zeit ohne Bild (5 s), geprüft alle so viele Aufrufe. */
        const val RIG_FORGET_NS = 5_000_000_000L
        const val RIG_PRUNE_EVERY = 240
        val PI_F = PI.toFloat()
        val TWO_PI_F = (2.0 * PI).toFloat()
    }
}

/**
 * Kratos, die Macht: eine Mauer aus dunkler Bronze. Turmschild mit Faust-Relief links, Kriegshammer mit Dorn rechts,
 * querer Purpurkamm auf dem geschlossenen Helm, kurzer Purpurmantel. Phase II ([GuardianRenderState.shieldless]):
 * kein Schild, die linke Hand hält den Hammerstiel mit.
 *
 * Die Schlüsselposen stehen als Tabelle unten (generiert aus `tools/hha_assets/kratos_model.py --kotlin`, dieselben
 * Winkel wie `POSES` dort). Jede Aktion läuft über `actionTime` selbst durch Ausholen → Schlag → Nachlauf/offen →
 * zurück, mit den Zeiten aus [KratosTimings]. Befehle: Er stellt den Hammer senkrecht ab und befiehlt mit der rechten
 * Hand (flache Hand vor: weichen; Faust an die Brust: heran; Arm gestreckt nach unten: knien).
 *
 * Überarbeitung 3: Umhang (zwei Glieder) und Helmbusch schwingen über Federn nach ([secondary]): Sie folgen der
 * Pose mit Verzug, bekommen an jeder Schlag-Marke einen Stoß und wehen mit dem gemessenen Tempo nach hinten. Der Helm
 * ist geschlossen (kein Kiefer): Beim Sprechen nickt der Kopf leicht im Takt der Silben (`CinePlayer.mouthOf`).
 */
class KratosModel(root: ModelPart) : ThroneGuardModel(root, BONES, PARENTS, PROPS, SPRINGS) {
    private val body = bone("body")
    private val chest = bone("chest")
    private val head = bone("head")
    private val crest = bone("crest")
    private val armR = bone("arm_r")
    private val foreR = bone("fore_r")
    private val fingersR = bone("fingers_r")
    private val armL = bone("arm_l")
    private val shield = bone("shield")
    private val cape = bone("cape")
    private val cape1 = bone("cape_1")
    private val skirtF = bone("skirt_f")
    private val skirtB = bone("skirt_b")
    private val legR = bone("leg_r")
    private val legL = bone("leg_l")
    private val shinR = bone("shin_r")
    private val shinL = bone("shin_l")

    /**
     * Leuchten an den Kampfzustand gekoppelt (PLAN 2.1 Punkt 7): steigt beim Ausholen bis zum Schlag, sinkt, solange
     * er taumelt (offen), sonst ruhig. Alle Übergänge weich, langsamer als 1 Hz.
     */
    override fun glowAlpha(state: GuardianRenderState): Float {
        val a = state.actionTime
        val charge = when (state.action) {
            KratosAction.SMASH -> hold(a, T.SMASH_HIT.f, T.SMASH_HIT_END.f, T.SMASH_HIT_END + GLOW_RELEASE)
            KratosAction.DOUBLE_SMASH -> hold(a, T.DOUBLE_HIT.f, T.DOUBLE_HIT2.f, T.DOUBLE_HIT2 + GLOW_RELEASE)
            KratosAction.BASH -> hold(a, T.BASH_HIT.f, T.BASH_HIT_END.f, T.BASH_HIT_END + GLOW_RELEASE)
            KratosAction.CMD_BACK, KratosAction.CMD_COME, KratosAction.CMD_KNEEL -> hold(a, GLOW_COMMAND_RISE, GLOW_COMMAND_HOLD, GLOW_COMMAND_HOLD + GLOW_RELEASE)
            KratosAction.STAGGER -> -hold(a, GLOW_OPEN_FALL, T.STAGGER_END - GLOW_RELEASE, T.STAGGER_END.f)
            KratosAction.YIELD -> -step(a, 0f, GLOW_YIELD_FALL)
            else -> 0f
        }
        return (GLOW_BASE + (if (charge >= 0f) GLOW_CHARGE else GLOW_OPEN) * charge).coerceIn(0f, 1f)
    }

    override fun setAngles(state: GuardianRenderState) {
        super.setAngles(state)
        beginPose()
        val rig = rig(state)
        val t = state.age
        val w = walk(state)
        val p = phase(state)
        val a = state.actionTime
        val bare = state.shieldless

        // --- Ruhe und Gang: Schild vor dem Leib, Hammer geschultert; marschierend mit Gewicht --------------------------
        if (bare) applyPose(IDLE_SHIELDLESS)
        val breath = sin(t * 0.07f)
        chest.pitch += breath * 0.018f
        armR.roll += breath * 0.01f
        body.originY += breath * 0.25f + abs(sin(p)) * 1.8f * w
        body.roll += sin(p) * 0.035f * w
        chest.yaw += sin(p) * 0.05f * w
        legs(state, legR, legL, shinR, shinL, 0.5f)
        armL.pitch += sin(p) * 0.06f * w
        skirtF.pitch += -abs(cos(p)) * 0.25f * w
        skirtB.pitch += abs(cos(p)) * 0.2f * w
        // Der Mantel schwingt nach: zwei Glieder, phasenversetzt.
        cape.pitch += 0.1f * w + sin(t * 0.05f) * 0.02f + abs(sin(p)) * 0.06f * w
        cape1.pitch += sin(t * 0.05f - 0.8f) * 0.03f + abs(sin(p - 0.7f)) * 0.08f * w
        capture()

        // --- Aktionen ----------------------------------------------------------------------------------------------
        playActions(state) { action, _ -> timeline(action, bare) }

        // --- Zusätze über den Posen --------------------------------------------------------------------------------
        when (state.action) {
            KratosAction.SMASH, KratosAction.DOUBLE_SMASH -> {
                // Zwei schwere Schritte Anlauf in die Ausholpose (so lange er geht, danach steht er bis zum Schlag).
                val run = window(a, 0f, minOf(T.SMASH_HIT - 4f, T.SMASH_STEP_TICKS + 4f))
                body.originY += abs(sin(a * 0.45f)) * -1.5f * run
            }
            KratosAction.CMD_BACK -> {
                // Stampfen (BACK_HIT bis BACK_HIT_END): das rechte Bein hebt sich vorher und kracht hinunter.
                val lift = step(a, T.BACK_HIT - 8f, T.BACK_HIT - 2f) * (1f - step(a, T.BACK_HIT.f, T.BACK_HIT_END.f))
                legR.pitch -= lift * 0.55f
                shinR.pitch += lift * 0.6f
                val jolt = window(a, T.BACK_HIT.f, T.BACK_HIT_END + 4f)
                body.originY += jolt * 1.2f
                head.pitch -= jolt * 0.08f
            }
            KratosAction.CMD_COME -> {
                // Winken: die Finger rufen zweimal heran; Ruck (COME_HIT bis COME_HIT_END): die Faust reißt zurück.
                val wave = window(a, 3f, T.COME_WAVE.f)
                fingersR.pitch += sin(a * 1.1f) * 0.7f * wave
                foreR.pitch += sin(a * 1.1f) * 0.15f * wave
                val jerk = window(a, T.COME_HIT.f, T.COME_HIT_END + 3f)
                chest.pitch -= jerk * 0.12f
                armR.pitch += jerk * 0.15f
            }
            KratosAction.CMD_KNEEL -> {
                // Der Arm stößt beim Treffer noch einmal nach unten.
                val push = window(a, T.KNEEL_HIT - 2f, T.KNEEL_HIT + 4f)
                armR.pitch += push * 0.12f
                chest.pitch += push * 0.05f
            }
            KratosAction.STAGGER -> {
                // Schweres Atmen auf dem Knie.
                chest.pitch += sin(t * 0.3f) * 0.025f * step(a, 6f, 12f)
            }
            KratosAction.SLAM -> {
                // Auftritt: Der Schild kracht auf den Boden, der Körper geht mit ins Knie.
                val jolt = window(a, S.SLAM_HIT.f, S.SLAM_HIT + SLAM_JOLT)
                body.originY += jolt * SLAM_DIP
                chest.pitch += jolt * SLAM_BOW
            }
            KratosAction.YIELD -> {
                // Auf dem Knie: langsames schweres Atmen.
                chest.pitch += sin(t * 0.12f) * 0.02f * step(a, YIELD_KNEEL_AT, YIELD_KNEEL_AT + 10f)
            }
        }
        if (state.action != KratosAction.STAGGER && state.action != KratosAction.YIELD) look(head, state)
        crest.roll = -head.roll * 0.3f
        shield.visible = if (state.action == KratosAction.DROP_SHIELD) a < T.DROP_SHIELD_GONE else !bare
        settlePins()
        remember(state, rig)
        secondary(state, rig)
    }

    /**
     * Nachschwingen: Umhang und Busch folgen ihrer „Weltlage“ (eigene Pose plus Neigung von Rumpf bzw. Kopf) über
     * Federn, das gemessene Tempo weht den Umhang nach hinten, die Drehrate legt den Busch zur Seite. Stöße an den
     * Schlag-Marken jeder Aktion; zuletzt das Nicken beim Sprechen.
     */
    private fun secondary(state: GuardianRenderState, rig: Rig) {
        measure(state, rig)
        val m = rig.motion
        if (hitMark(state, rig)) {
            rig.springs[CAPE].kick(CAPE_KICK)
            rig.springs[CAPE_1].kick(CAPE_KICK * 0.6f)
            rig.springs[CREST_PITCH].kick(CREST_KICK)
        }
        kicked(state, rig)
        val dt = rig.dt
        val torso = body.pitch + chest.pitch
        cape.pitch = rig.springs[CAPE].step(cape.pitch + torso + min(m.speed, SPEED_CAP) * CAPE_SPEED, dt) - torso
        cape1.pitch = rig.springs[CAPE_1].step(cape1.pitch + cape.pitch + torso + min(m.speed, SPEED_CAP) * CAPE_1_SPEED, dt) - cape.pitch - torso
        val crown = torso + head.pitch
        crest.pitch = rig.springs[CREST_PITCH].step(crest.pitch + crown + min(m.speed, SPEED_CAP) * CREST_SPEED, dt) - crown
        crest.roll = rig.springs[CREST_ROLL].step(crest.roll + head.roll - m.turn * CREST_TURN, dt) - head.roll
        head.pitch += CinePlayer.mouthOf(state.entityId) * MOUTH_NOD
    }

    /** Fällt in diesem Bild ein Schlag (Hammer, Schild, Stampfen, Ruck, Taumeln, Wurf, Knie)? */
    private fun hitMark(state: GuardianRenderState, rig: Rig): Boolean = when (state.action) {
        KratosAction.SMASH -> crossed(state, rig, T.SMASH_HIT_END.f)
        KratosAction.DOUBLE_SMASH -> crossed(state, rig, T.DOUBLE_HIT + 2f) || crossed(state, rig, T.DOUBLE_HIT2 + 2f)
        KratosAction.BASH -> crossed(state, rig, T.BASH_HIT_END.f)
        KratosAction.CMD_BACK -> crossed(state, rig, T.BACK_HIT_END.f)
        KratosAction.CMD_COME -> crossed(state, rig, T.COME_HIT.f)
        KratosAction.STAGGER -> crossed(state, rig, STAGGER_HIT)
        KratosAction.DROP_SHIELD -> crossed(state, rig, T.DROP_SHIELD_GONE.f)
        KratosAction.SHIELD_UP -> crossed(state, rig, T.SHIELD_UP_HIT_END.f)
        KratosAction.SLAM -> crossed(state, rig, S.SLAM_HIT.f)
        KratosAction.YIELD -> crossed(state, rig, YIELD_KNEEL_AT)
        else -> false
    }

    /** Zeitleiste jeder Aktion; [bare] wählt in Phase II die Posen ohne Schild. */
    private fun timeline(action: Int, bare: Boolean): Array<ThroneKey>? = when (action) {
        KratosAction.BASH -> if (bare) BASH_BARE else BASH
        KratosAction.SMASH -> if (bare) SMASH_BARE else SMASH
        KratosAction.DOUBLE_SMASH -> DOUBLE
        KratosAction.CMD_BACK -> CMD_BACK_KEYS
        KratosAction.CMD_COME -> CMD_COME_KEYS
        KratosAction.CMD_KNEEL -> CMD_KNEEL_KEYS
        KratosAction.STAGGER -> STAGGER_KEYS
        KratosAction.DROP_SHIELD -> DROP_KEYS
        KratosAction.SHIELD_UP -> if (bare) SHIELD_UP_BARE else SHIELD_UP_KEYS
        KratosAction.YIELD -> YIELD_KEYS
        KratosAction.SLAM -> SLAM_KEYS
        else -> null
    }

    private companion object {
        val T = KratosTimings
        val S = KratosStages
        val Int.f get() = toFloat()

        // --- Federn (Reihenfolge = Index im Rig) ---
        const val CAPE = 0
        const val CAPE_1 = 1
        const val CREST_PITCH = 2
        const val CREST_ROLL = 3
        /** (Frequenz rad/Tick, Dämpfung): Umhang schwer und träge, Glied 2 noch träger, Busch leicht und lebhaft. */
        val SPRINGS = arrayOf(0.34f to 0.42f, 0.27f to 0.36f, 0.5f to 0.3f, 0.45f to 0.32f)
        /** Stoß an Schlag-Marken (rad/Tick, + = Umhang nach hinten oben). */
        const val CAPE_KICK = 0.22f
        const val CREST_KICK = -0.18f
        /** Tempo (Blöcke/Tick) → Wehen nach hinten (rad), gedeckelt. */
        const val SPEED_CAP = 0.4f
        const val CAPE_SPEED = 0.9f
        const val CAPE_1_SPEED = 0.6f
        const val CREST_SPEED = -0.5f
        /** Drehrate (Grad/Tick) → Busch zur Seite (rad). */
        const val CREST_TURN = 0.012f
        /** Nicken beim Sprechen (rad bei vollem Mund). */
        const val MOUTH_NOD = 0.045f
        /** Taumeln: der Schlag aufs Knie (Schlüssel bei 6). */
        const val STAGGER_HIT = 6f

        // --- Leuchten ---
        const val GLOW_BASE = 0.85f
        const val GLOW_CHARGE = 0.15f
        const val GLOW_OPEN = 0.25f
        const val GLOW_RELEASE = 10f
        const val GLOW_COMMAND_RISE = 8f
        const val GLOW_COMMAND_HOLD = 30f
        const val GLOW_OPEN_FALL = 12f
        const val GLOW_YIELD_FALL = 40f

        // --- Auftritt: Schildschlag (Aktion SLAM) ---
        const val SLAM_RAISE = 10f
        const val SLAM_JOLT = 7f
        const val SLAM_DIP = 1.6f
        const val SLAM_BOW = 0.1f

        // --- Niederlage: erst Faust an die Brust, dann aufs Knie ---
        const val YIELD_KNEEL_FROM = 44f
        const val YIELD_KNEEL_AT = 62f

        // KRATOS POSES BEGIN (generiert aus tools/hha_assets/kratos_model.py --kotlin)
        val BONES = arrayOf("body", "chest", "head", "crest", "arm_r", "fore_r", "fingers_r", "hammer", "arm_l", "fore_l", "shield", "cape", "cape_1", "skirt_f", "skirt_b", "leg_r", "shin_r", "leg_l", "shin_l")
        val PARENTS = intArrayOf(-1, 0, 1, 2, 1, 4, 5, 5, 1, 8, 9, 1, 11, 1, 1, 0, 15, 0, 17)
        val PROPS = arrayOf("hammer", "shield")
        val IDLE_SHIELDLESS = ThronePose(floatArrayOf(
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            -0.8300f, -1.0350f, 0.9350f, 0.0000f, 0.0000f, 0.0000f, -0.9200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 1.8046f, 0.2180f, 0.9128f, 0.0000f, 0.0000f, 0.0000f,
            -0.6400f, 0.9050f, -0.1950f, 0.0000f, 0.0000f, 0.0000f, -1.1700f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N,
        ))
        val BASH_WINDUP = ThronePose(floatArrayOf(
            0.0000f, 0.3500f, 0.0000f, 0.0000f, 7.0000f, 3.0000f, 0.3200f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.1000f, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            0.6650f, -2.1000f, 0.6100f, 0.0000f, 0.0000f, 0.0000f, -1.6650f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -2.5103f, 0.7442f, -1.2286f, 0.0000f, 0.0000f, 0.0000f,
            0.5250f, 0.0950f, -0.3150f, 0.0000f, 0.0000f, 0.0000f, -2.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            2.0383f, -0.1022f, -0.6561f, 0.0000f, 0.0000f, 0.0000f, -0.1030f, -0.6119f, 0.2347f, 0.0000f, 0.0000f, 0.0000f,
            -0.0941f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2400f, 1.9200f, -0.1250f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.4500f, -0.4350f, -0.0800f, 0.0000f, 0.0000f, 0.0000f,
            0.0450f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val BASH_HIT = ThronePose(floatArrayOf(
            0.0000f, 0.0500f, 0.0000f, 0.0000f, 4.0000f, -9.0000f, 0.2800f, -0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.2000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -0.6950f, -2.6550f, 0.5550f, 0.0000f, 0.0000f, 0.0000f, 0.0450f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 2.5982f, 0.2683f, -1.3398f, 0.0000f, 0.0000f, 0.0000f,
            -1.1550f, 0.4450f, -0.8500f, 0.0000f, 0.0000f, 0.0000f, -1.4550f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            2.2350f, -0.1770f, -0.8680f, 0.0000f, 0.0000f, 0.0000f, 0.3628f, 0.0673f, -0.0194f, 0.0000f, 0.0000f, 0.0000f,
            -0.1407f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.6900f, -0.1150f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
            0.1150f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.4000f, -0.0950f, -0.0800f, 0.0000f, 0.0000f, 0.0000f,
            0.0450f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val BASH_OPEN = ThronePose(floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 4.0000f, -6.0000f, 0.5000f, -0.0500f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
            0.1500f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -0.4000f, -1.0850f, 0.4800f, 0.0000f, 0.0000f, 0.0000f, -0.0700f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.6113f, 1.3117f, -0.9900f, 0.0000f, 0.0000f, 0.0000f,
            -0.4150f, 0.4100f, -0.4050f, 0.0000f, 0.0000f, 0.0000f, -0.3250f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.6235f, -1.3251f, -0.9394f, 0.0000f, 0.0000f, 0.0000f, -0.3436f, 0.0054f, -0.0942f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.5150f, -0.0800f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
            0.1600f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.3100f, -0.0450f, -0.0800f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val SMASH_WINDUP = ThronePose(floatArrayOf(
            0.0000f, 0.2000f, 0.0000f, 0.0000f, 2.0000f, 2.0000f, -0.3200f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.2500f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -2.3500f, -0.6050f, -0.1450f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.9705f, -0.1744f, 0.1455f, 0.0000f, 0.0000f, 0.0000f,
            -0.3050f, 0.4250f, -0.6150f, 0.0000f, 0.0000f, 0.0000f, -1.2400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            2.2795f, -0.2840f, -1.0865f, 0.0000f, 0.0000f, 0.0000f, 0.6820f, -0.3786f, -0.1322f, 0.0000f, 0.0000f, 0.0000f,
            -0.1393f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, -0.4100f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
            -0.0050f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.4150f, -0.2450f, -0.0800f, 0.0000f, 0.0000f, 0.0000f,
            0.0650f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val SMASH_HIT = ThronePose(floatArrayOf(
            0.0000f, 0.1000f, 0.0000f, 0.0000f, 9.0000f, -4.0000f, 0.8500f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -1.0600f, -1.0450f, 0.9250f, 0.0000f, 0.0000f, 0.0000f, -0.8100f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 3.0712f, 0.5586f, 1.0404f, 0.0000f, 0.0000f, 0.0000f,
            0.2600f, 0.3300f, -0.2850f, 0.0000f, 0.0000f, 0.0000f, -1.5050f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.9813f, -0.9562f, -1.2400f, 0.0000f, 0.0000f, 0.0000f, -0.3919f, -0.0988f, 0.1131f, 0.0000f, 0.0000f, 0.0000f,
            -0.2187f, 0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.7500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0300f, -0.2350f, 0.0850f, 0.0000f, 0.0000f, 0.0000f,
            1.0300f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.4350f, -0.1550f, -0.0800f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val SMASH_STUCK = ThronePose(floatArrayOf(
            -0.1200f, 0.2500f, 0.0000f, 0.0000f, 6.0000f, 5.0000f, 0.2200f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -1.3250f, -1.0600f, 0.8300f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 2.9718f, 0.8156f, 0.2748f, 0.3955f, 17.8207f, -1.3832f,
            -0.0150f, 0.4000f, -0.3200f, 0.0000f, 0.0000f, 0.0000f, -0.4950f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            1.6442f, -1.0752f, -1.5751f, 0.0000f, 0.0000f, 0.0000f, 0.0489f, -0.3950f, 0.0604f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1250f, -0.3500f, 0.0900f, 0.0000f, 0.0000f, 0.0000f,
            1.0500f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.4350f, -0.3900f, -0.0800f, 0.0000f, 0.0000f, 0.0000f,
            0.2300f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ), floatArrayOf(2.2424f, -0.0000f, 0.0000f, -6.5034f, -3.7948f, -19.2359f, N, N, N, N, N, N))
        val SMASH_WINDUP_2H = ThronePose(floatArrayOf(
            0.0000f, 0.2000f, 0.0000f, 0.0000f, 2.0000f, 2.0000f, -0.3200f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.2500f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -2.3500f, -0.6050f, -0.1450f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.9705f, -0.1744f, 0.1455f, 0.0000f, 0.0000f, 0.0000f,
            -1.7650f, 0.8700f, 0.5850f, 0.0000f, 0.0000f, 0.0000f, -0.0350f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.6820f, -0.3786f, -0.1322f, 0.0000f, 0.0000f, 0.0000f,
            -0.1393f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.2500f, -0.4100f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
            -0.0050f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.4150f, -0.2450f, -0.0800f, 0.0000f, 0.0000f, 0.0000f,
            0.0650f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val SMASH_HIT_2H = ThronePose(floatArrayOf(
            0.0000f, 0.1000f, 0.0000f, 0.0000f, 9.0000f, -4.0000f, 0.8500f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -1.0600f, -1.0450f, 0.9250f, 0.0000f, 0.0000f, 0.0000f, -0.8100f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 3.0712f, 0.5586f, 1.0404f, 0.0000f, 0.0000f, 0.0000f,
            -0.2600f, 1.2900f, 0.1400f, 0.0000f, 0.0000f, 0.0000f, -0.7250f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.3919f, -0.0988f, 0.1131f, 0.0000f, 0.0000f, 0.0000f,
            -0.2187f, 0.0000f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.7500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0300f, -0.2350f, 0.0850f, 0.0000f, 0.0000f, 0.0000f,
            1.0300f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.4350f, -0.1550f, -0.0800f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val SMASH_STUCK_2H = ThronePose(floatArrayOf(
            -0.1200f, 0.2500f, 0.0000f, 0.0000f, 6.0000f, 5.0000f, 0.2200f, 0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -1.3250f, -1.0600f, 0.8300f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 2.9718f, 0.8156f, 0.2748f, 0.3955f, 17.8207f, -1.3832f,
            -0.5100f, 0.7700f, 0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.0950f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.0489f, -0.3950f, 0.0604f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.1250f, -0.3500f, 0.0900f, 0.0000f, 0.0000f, 0.0000f,
            1.0500f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.4350f, -0.3900f, -0.0800f, 0.0000f, 0.0000f, 0.0000f,
            0.2300f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ), floatArrayOf(2.2424f, -0.0000f, 0.0000f, -6.5034f, -3.7948f, -19.2359f, N, N, N, N, N, N))
        val CMD_BACK = ThronePose(floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.0000f, 0.0000f, 0.0500f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3000f, -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -1.3850f, -0.4400f, -0.8000f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -1.2105f, 0.7344f, -2.8366f, 0.0000f, 0.0000f, 0.0000f, -1.6861f, -0.7344f, 0.3050f, -29.0092f, -27.1349f, 24.3992f,
            -0.2800f, 1.5450f, -0.4000f, 0.0000f, 0.0000f, 0.0000f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            2.9659f, -0.9154f, -3.0239f, 0.0000f, 0.0000f, 0.0000f, 0.1071f, -0.2497f, 0.0128f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.2200f, 0.1400f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
            0.0700f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.2350f, 0.0450f, -0.0800f, 0.0000f, 0.0000f, 0.0000f,
            0.0150f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ), floatArrayOf(3.1416f, -0.0000f, 0.0000f, -24.0000f, -25.0000f, -14.0000f, N, N, N, N, N, N))
        val CMD_COME_WAVE = ThronePose(floatArrayOf(
            0.0000f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0500f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -1.0950f, -0.1050f, 0.7450f, 0.0000f, 0.0000f, 0.0000f, -0.0650f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -1.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.8516f, 0.8070f, 0.0518f, 17.5331f, -20.4386f, 22.9089f,
            -0.5250f, 1.1800f, -0.5700f, 0.0000f, 0.0000f, 0.0000f, -0.1900f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            1.1297f, -1.2838f, -0.8966f, 0.0000f, 0.0000f, 0.0000f, 0.2103f, 0.2497f, 0.0128f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.0150f, 0.0100f, 0.0650f, 0.0000f, 0.0000f, 0.0000f,
            0.1150f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0050f, 0.0350f, -0.0650f, 0.0000f, 0.0000f, 0.0000f,
            0.0400f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ), floatArrayOf(-3.1416f, 0.0000f, -0.0000f, -24.0000f, -25.0000f, -14.0000f, N, N, N, N, N, N))
        val CMD_COME_FIST = ThronePose(floatArrayOf(
            0.0000f, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 3.0000f, -0.2400f, -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -1.1900f, -0.1700f, 1.7450f, 0.0000f, 0.0000f, 0.0000f, -1.6150f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.1243f, 0.0136f, 1.7089f, 33.8180f, -15.3752f, -6.1220f,
            -0.4900f, 1.0700f, -0.4250f, 0.0000f, 0.0000f, 0.0000f, -0.0950f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.8816f, -1.1035f, -0.4489f, 0.0000f, 0.0000f, 0.0000f, 0.4135f, 0.3396f, 0.0866f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.0100f, 0.0400f, 0.0650f, 0.0000f, 0.0000f, 0.0000f,
            -0.0050f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.0500f, 0.1350f, -0.0750f, 0.0000f, 0.0000f, 0.0000f,
            0.0150f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ), floatArrayOf(-3.1416f, -0.0000f, -0.0000f, -24.0000f, -25.0000f, -14.0000f, N, N, N, N, N, N))
        val CMD_KNEEL = ThronePose(floatArrayOf(
            0.1200f, 0.0000f, 0.0000f, 0.0000f, 4.0000f, 2.0000f, 0.5000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.4500f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -1.1000f, -0.3050f, 0.0450f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.4385f, 0.1048f, 0.1907f, 0.0000f, 0.0000f, 0.0000f, -2.7031f, 0.1048f, 0.1907f, -5.4859f, -22.5462f, 18.3056f,
            0.1900f, 0.3900f, -0.1500f, 0.0000f, 0.0000f, 0.0000f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.2005f, -0.8966f, -0.3482f, 0.0000f, 0.0000f, 0.0000f, -0.4635f, -0.0877f, 0.0481f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.0000f, 0.1000f, 0.1150f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.7200f, -0.0300f, -0.0800f, 0.0000f, 0.0000f, 0.0000f,
            0.8650f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ), floatArrayOf(-3.1416f, -0.0000f, 0.0000f, -26.0000f, -25.0000f, -10.0000f, N, N, N, N, N, N))
        val STAGGER = ThronePose(floatArrayOf(
            0.0000f, 0.2500f, 0.1800f, 0.0000f, 15.0000f, 2.0000f, 0.4500f, 0.1000f, -0.1200f, 0.0000f, 0.0000f, 0.0000f,
            0.3000f, 0.3000f, 0.1000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -0.7300f, -0.7200f, 0.5550f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 1.6170f, -1.0570f, 2.9614f, -10.4250f, 25.0743f, -13.6224f,
            -0.1050f, 0.4350f, -0.3300f, 0.0000f, 0.0000f, 0.0000f, -1.2150f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3197f, -0.6691f, -0.9851f, 7.6102f, -4.6938f, 33.6013f, -0.0325f, -0.4452f, -0.1985f, 0.0000f, 0.0000f, 0.0000f,
            0.2532f, 0.0259f, 0.0904f, 0.0000f, 0.0000f, 0.0000f, -0.9000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.3600f, -0.1950f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
            0.8800f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.4300f, -0.1000f, -0.4950f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ), floatArrayOf(1.5708f, -0.5317f, -3.1416f, -30.0000f, 22.5000f, -26.0000f, -1.5708f, -0.2450f, -0.0000f, 40.0000f, 15.0000f, -14.0000f))
        val DROP_SHIELD_RIP = ThronePose(floatArrayOf(
            0.0000f, -0.3000f, 0.0000f, 0.0000f, 3.0000f, 0.0000f, 0.1500f, -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, 0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            0.5100f, 0.2550f, 0.1150f, 0.0000f, 0.0000f, 0.0000f, -0.4350f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.5140f, 0.4201f, -0.4721f, 0.0000f, 0.0000f, 0.0000f,
            -1.4200f, 1.3950f, -0.1550f, 0.0000f, 0.0000f, 0.0000f, -0.2300f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            1.6398f, 0.4371f, -0.1577f, 0.0000f, 0.0000f, 0.0000f, -0.0450f, 0.7396f, -0.1383f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.0450f, -0.0000f, 0.1400f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.3050f, 0.0800f, -0.1400f, 0.0000f, 0.0000f, 0.0000f,
            0.8350f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val DROP_SHIELD = ThronePose(floatArrayOf(
            0.0000f, 0.3500f, 0.0000f, 0.0000f, 4.0000f, 0.0000f, 0.1000f, 0.5500f, -0.1000f, 0.0000f, 0.0000f, 0.0000f,
            0.0000f, -0.6000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -1.0800f, -0.8950f, 0.6100f, 0.0000f, 0.0000f, 0.0000f, -0.0250f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.3462f, 0.3004f, -0.2854f, 0.0000f, 0.0000f, 0.0000f,
            1.3300f, 0.0550f, -0.7750f, 0.0000f, 0.0000f, 0.0000f, -0.4900f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            1.5626f, -1.3485f, -1.4769f, 0.0000f, 0.0000f, 0.0000f, -0.1237f, -0.7639f, 0.6675f, 0.0000f, 0.0000f, 0.0000f,
            -0.1307f, 0.0179f, 0.0415f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            N, N, N, N, N, N, 0.0550f, -0.0650f, 0.1450f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0700f, -1.0350f, -0.2000f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val SHIELD_UP_WINDUP = ThronePose(floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 9.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            0.2400f, -0.9800f, 0.5250f, 0.0000f, 0.0000f, 0.0000f, -1.4300f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.6447f, 1.0581f, 0.5553f, 0.0000f, 0.0000f, 0.0000f,
            -1.8850f, 1.6350f, 0.3950f, 0.0000f, 0.0000f, 0.0000f, -1.0250f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            2.8935f, -1.3964f, -1.2225f, 0.0000f, 0.0000f, 0.0000f, 0.0387f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3750f, 1.7850f, -0.2400f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.0650f, -0.3750f, -0.1850f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val SHIELD_UP_HIT = ThronePose(floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, -3.0000f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            0.3850f, -1.1300f, 0.5250f, 0.0000f, 0.0000f, 0.0000f, -1.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.8832f, 1.1774f, 0.7543f, 0.0000f, 0.0000f, 0.0000f,
            -2.4650f, 1.2200f, 0.3450f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.6650f, -1.0870f, 2.3396f, 0.0000f, 0.0000f, 0.0000f, 0.2387f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3100f, 1.8000f, -0.2400f, 0.0000f, 0.0000f, 0.0000f,
            0.0100f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.0850f, 0.1250f, -0.1700f, 0.0000f, 0.0000f, 0.0000f,
            0.0750f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val SHIELD_UP_WINDUP_SHIELDLESS = ThronePose(floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 9.0000f, 0.0000f, 0.1200f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -1.6700f, -1.6300f, -0.6750f, 0.0000f, 0.0000f, 0.0000f, -1.0400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -1.8181f, 0.0246f, 3.0499f, 0.0000f, 0.0000f, 0.0000f,
            -1.9350f, 1.6350f, 0.3950f, 0.0000f, 0.0000f, 0.0000f, -0.8950f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.0387f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.5000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.4000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3750f, 1.7850f, -0.2400f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.0650f, -0.3750f, -0.1850f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val SHIELD_UP_HIT_SHIELDLESS = ThronePose(floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, -3.0000f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.4500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -2.2400f, -1.1850f, -0.6250f, 0.0000f, 0.0000f, 0.0000f, 0.0450f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -1.2315f, -0.2491f, 2.9006f, 0.0000f, 0.0000f, 0.0000f,
            -2.4350f, 1.1350f, 0.3450f, 0.0000f, 0.0000f, 0.0000f, 0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, 0.2387f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.0500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.3100f, 1.8000f, -0.2400f, 0.0000f, 0.0000f, 0.0000f,
            0.0100f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, -0.0850f, 0.1250f, -0.1700f, 0.0000f, 0.0000f, 0.0000f,
            0.0750f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val YIELD = ThronePose(floatArrayOf(
            0.0000f, 0.1000f, 0.0000f, 4.0000f, 1.0000f, 2.0000f, 0.1200f, -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, N, N, N, N, N, N,
            -1.4300f, -0.6200f, 1.3300f, 0.0000f, 0.0000f, 0.0000f, -1.7750f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, -0.7135f, -0.2012f, 1.3714f, 12.2357f, -29.1353f, 11.3084f,
            -0.3650f, 1.1200f, -0.3800f, 0.0000f, 0.0000f, 0.0000f, -0.2400f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            1.5974f, -1.2871f, -1.5774f, 0.0000f, 0.0000f, 0.0000f, 0.0387f, -0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            N, N, N, N, N, N, N, N, N, N, N, N,
            N, N, N, N, N, N, -0.0150f, 0.0500f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, -0.0800f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0100f, -0.1400f, 0.0000f, 0.0000f, 0.0000f,
            0.1600f, 0.0000f, 0.0800f, 0.0000f, 0.0000f, 0.0000f,
        ), floatArrayOf(-3.1416f, 0.0000f, -0.0000f, -22.0000f, -25.0000f, -16.0000f, N, N, N, N, N, N))
        // KRATOS POSES END

        /** Schildstoß: Ausholen bis 14, Stoß 14–16, der Schildarm hängt (offen) bis 36. */
        val BASH = arrayOf(
            ThroneKey(0f, null), ThroneKey(7f, BASH_WINDUP), ThroneKey(T.BASH_HIT.f, BASH_WINDUP),
            ThroneKey(T.BASH_HIT_END.f, BASH_HIT, true), ThroneKey(T.BASH_HIT_END + 6f, BASH_OPEN),
            ThroneKey(T.BASH_END.f, BASH_OPEN), ThroneKey(T.BASH_END + 8f, null),
        )

        /** Phase II: derselbe Stoß als Schulterstoß (der Schild ist weg, die Pose bleibt). */
        val BASH_BARE = BASH

        /** Hammerfall: Ausholen bis 18, Schlag 18–20, der Hammer steckt bis 46. */
        val SMASH = arrayOf(
            ThroneKey(0f, null), ThroneKey(8f, SMASH_WINDUP), ThroneKey(T.SMASH_HIT.f, SMASH_WINDUP),
            ThroneKey(T.SMASH_HIT_END.f, SMASH_HIT, true), ThroneKey(T.SMASH_HIT_END + 6f, SMASH_STUCK),
            ThroneKey(T.SMASH_END.f, SMASH_STUCK), ThroneKey(T.SMASH_END + 8f, null),
        )
        val SMASH_BARE = arrayOf(
            ThroneKey(0f, null), ThroneKey(8f, SMASH_WINDUP_2H), ThroneKey(T.SMASH_HIT.f, SMASH_WINDUP_2H),
            ThroneKey(T.SMASH_HIT_END.f, SMASH_HIT_2H, true), ThroneKey(T.SMASH_HIT_END + 6f, SMASH_STUCK_2H),
            ThroneKey(T.SMASH_END.f, SMASH_STUCK_2H), ThroneKey(T.SMASH_END + 8f, null),
        )

        /** Doppelschlag (Phase II, beidhändig): Schlag 18, schnell wieder hoch, zweiter Schlag 32, steckt bis 58. */
        val DOUBLE = arrayOf(
            ThroneKey(0f, null), ThroneKey(8f, SMASH_WINDUP_2H), ThroneKey(T.DOUBLE_HIT.f, SMASH_WINDUP_2H),
            ThroneKey(T.DOUBLE_HIT + 2f, SMASH_HIT_2H, true), ThroneKey(T.DOUBLE_HIT + 6f, SMASH_HIT_2H),
            ThroneKey(T.DOUBLE_HIT2 - 4f, SMASH_WINDUP_2H), ThroneKey(T.DOUBLE_HIT2.f, SMASH_WINDUP_2H),
            ThroneKey(T.DOUBLE_HIT2 + 2f, SMASH_HIT_2H, true), ThroneKey(T.DOUBLE_HIT2 + 8f, SMASH_STUCK_2H),
            ThroneKey(T.DOUBLE_END.f, SMASH_STUCK_2H), ThroneKey(T.DOUBLE_END + 8f, null),
        )

        /** „WEICHT!": Geste bis 8, halten, Stampfen 24–26 (Zusatz in setAngles), Ende 38. */
        val CMD_BACK_KEYS = arrayOf(
            ThroneKey(0f, null), ThroneKey(T.BACK_GESTURE.f, CMD_BACK), ThroneKey(T.BACK_END - 6f, CMD_BACK),
            ThroneKey(T.BACK_END.f, null),
        )

        /** „HERAN!": Winken bis 12, Faust an der Brust halten bis 30, Ruck 30–32. */
        val CMD_COME_KEYS = arrayOf(
            ThroneKey(0f, null), ThroneKey(6f, CMD_COME_WAVE), ThroneKey(T.COME_WAVE.f, CMD_COME_WAVE),
            ThroneKey(T.COME_WAVE + 3f, CMD_COME_FIST, true), ThroneKey(T.COME_HIT_END + 2f, CMD_COME_FIST),
            ThroneKey(T.COME_HIT_END + 8f, null),
        )

        /** „KNIET!": Geste bis 8, halten bis 30 (Bias Klinge), Ende 50. */
        val CMD_KNEEL_KEYS = arrayOf(
            ThroneKey(0f, null), ThroneKey(T.KNEEL_GESTURE.f, CMD_KNEEL), ThroneKey(T.KNEEL_END - 6f, CMD_KNEEL),
            ThroneKey(T.KNEEL_END.f, null),
        )

        /** Gerammt: kippt zur Seite aufs Knie, Schild und Hammer fallen; Schleife bis 80. */
        val STAGGER_KEYS = arrayOf(
            ThroneKey(0f, null), ThroneKey(6f, STAGGER, true), ThroneKey(T.STAGGER_END - 8f, STAGGER),
            ThroneKey(T.STAGGER_END.f, null),
        )

        /** Phasenwechsel: Schild vom Arm reißen (bis 12), zur Seite schleudern (16, danach aus), Hammer beidhändig. */
        val DROP_KEYS = arrayOf(
            ThroneKey(0f, null), ThroneKey(8f, DROP_SHIELD_RIP), ThroneKey(T.DROP_SHIELD_GONE - 4f, DROP_SHIELD_RIP),
            ThroneKey(T.DROP_SHIELD_GONE.f, DROP_SHIELD, true), ThroneKey(T.DROP_SHIELD_GONE + 8f, IDLE_SHIELDLESS),
            ThroneKey(T.DROP_SHIELD_END.f, IDLE_SHIELDLESS), ThroneKey(T.DROP_SHIELD_END + 2f, null),
        )

        /** Konter: Schild heben bis 12, Stoß nach oben 12–14, halten bis 40. */
        val SHIELD_UP_KEYS = arrayOf(
            ThroneKey(0f, null), ThroneKey(T.SHIELD_UP_RAISE - 2f, SHIELD_UP_WINDUP), ThroneKey(T.SHIELD_UP_HIT.f, SHIELD_UP_WINDUP),
            ThroneKey(T.SHIELD_UP_HIT_END.f, SHIELD_UP_HIT, true), ThroneKey(T.SHIELD_UP_END - 4f, SHIELD_UP_HIT),
            ThroneKey(T.SHIELD_UP_END + 4f, null),
        )
        val SHIELD_UP_BARE = arrayOf(
            ThroneKey(0f, null), ThroneKey(T.SHIELD_UP_RAISE - 2f, SHIELD_UP_WINDUP_SHIELDLESS),
            ThroneKey(T.SHIELD_UP_HIT.f, SHIELD_UP_WINDUP_SHIELDLESS), ThroneKey(T.SHIELD_UP_HIT_END.f, SHIELD_UP_HIT_SHIELDLESS, true),
            ThroneKey(T.SHIELD_UP_END - 4f, SHIELD_UP_HIT_SHIELDLESS), ThroneKey(T.SHIELD_UP_END + 4f, null),
        )

        /**
         * Kniend (Niederlage): Beine, Rumpf und liegende Requisiten aus dem Taumeln (Hammer fällt um), dazu der Kopf
         * tief gesenkt. Aus der Tabelle abgeleitet, keine neue Geometrie.
         */
        val KNEEL: ThronePose = STAGGER.bones.copyOf().let { b ->
            val head = BONES.indexOf("head") * 6
            b[head] = 0.42f; b[head + 1] = 0f; b[head + 2] = 0f; b[head + 3] = 0f; b[head + 4] = 0f; b[head + 5] = 0f
            val chest = BONES.indexOf("chest") * 6
            if (!b[chest].isNaN()) b[chest] += 0.12f
            ThronePose(b, STAGGER.pins)
        }

        /** Aufgeben: Hammer abstellen, Faust an die Brust (bis 44), dann sinkt er aufs Knie (62) und bleibt dort. */
        val YIELD_KEYS = arrayOf(
            ThroneKey(0f, null), ThroneKey(12f, YIELD), ThroneKey(YIELD_KNEEL_FROM, YIELD), ThroneKey(YIELD_KNEEL_AT, KNEEL),
        )

        /** Auftritt: Schild über den Kopf (bis 10), halten, dann krachend vor sich auf den Boden (14), zurück bis 34. */
        val SLAM_KEYS = arrayOf(
            ThroneKey(0f, null), ThroneKey(SLAM_RAISE, SHIELD_UP_WINDUP), ThroneKey(S.SLAM_HIT - 2f, SHIELD_UP_WINDUP),
            ThroneKey(S.SLAM_HIT.f, null, true), ThroneKey(S.SLAM_END.f, null),
        )
    }
}

/**
 * Bia, die Gewalt: schmal, kahl, wehende Stirnbinde, zwei lange gerade Klingen. Sie spricht nie und steht die meiste
 * Zeit als Statue auf ihrem Sockel: dann **kein Atmen, keine Sekundärbewegung**. Schlüsselposen wie bei Kratos aus
 * `tools/hha_assets/kratos_model.py` (`POSES_BIA`).
 *
 * Überarbeitung 3: Landestoß aus der gemessenen Bewegung (`Motion`) statt fester Zeit ([landing]): eine Feder senkt
 * Körper und Knie beim Aufsetzen, so stark wie sie fiel. Die Augen pulsieren auf, wenn Kratos ihr befiehlt oder sie
 * erwacht (`KratosFx.eyePulse`).
 */
class BiaModel(root: ModelPart) : ThroneGuardModel(root, BONES, PARENTS, emptyArray(), SPRINGS) {

    /** Brief: Als Statue glimmen die Augen nur, beim Sprung, Sturm und Fegen leuchten sie auf; Befehl/Erwachen = Puls. */
    override fun glowAlpha(state: GuardianRenderState): Float {
        val base = when (state.action) {
            BiaAction.LEAP, BiaAction.RUSH, BiaAction.SWEEP -> 1f
            else -> 0.5f
        }
        return (base + KratosFx.eyePulse(state.entityId, state.age)).coerceIn(0f, 1f)
    }
    private val legR = bone("leg_r")
    private val legL = bone("leg_l")
    private val shinR = bone("shin_r")
    private val shinL = bone("shin_l")
    private val bands = arrayOf(bone("band_l_0"), bone("band_l_1"), bone("band_r_0"), bone("band_r_1"))
    private val body = bone("body")

    override fun setAngles(state: GuardianRenderState) {
        super.setAngles(state)
        beginPose()
        val rig = rig(state)
        val a = state.actionTime
        val t = state.age
        // Statue: die Grundhaltung des Modells, sonst nichts.
        capture()
        playActions(state) { action, _ -> timeline(action) }

        when (state.action) {
            BiaAction.RUSH -> {
                // Gestreckter Lauf: weite Schritte, die Binde flattert gerade nach hinten.
                val run = step(a, 0f, 3f)
                legR.pitch += sin(a * 1.5f) * 0.55f * run
                legL.pitch -= sin(a * 1.5f) * 0.55f * run
                shinR.pitch += max(0f, -sin(a * 1.5f)) * 0.7f * run
                shinL.pitch += max(0f, sin(a * 1.5f)) * 0.7f * run
                flutter(t, 1f)
            }
            BiaAction.SWEEP -> {
                val run = step(a, T.BIA_SWEEP_WINDUP.f, T.BIA_SWEEP_WINDUP + 3f) * (1f - step(a, T.BIA_SWEEP_END - 2f, T.BIA_SWEEP_END + 4f))
                legR.pitch += sin(a * 1.2f) * 0.6f * run
                legL.pitch -= sin(a * 1.2f) * 0.6f * run
                shinR.pitch += max(0f, -sin(a * 1.2f)) * 0.8f * run
                shinL.pitch += max(0f, sin(a * 1.2f)) * 0.8f * run
                body.originY -= abs(sin(a * 1.2f)) * 1.5f * run
                flutter(t, run)
            }
            BiaAction.LEAP -> flutter(t, 0.4f * step(a, 4f, 10f))
        }
        remember(state, rig)
        landing(state, rig)
    }

    /**
     * Landestoß: Rückkehr (`RETURN`) beginnt beim Aufsetzen, „KNIET!“ (`SWEEP`) landet nach dem Sprung irgendwann
     * ([Motion.landing]). Beide stoßen eine Feder an, so stark wie die zuletzt gemessene Fallgeschwindigkeit; die
     * Feder senkt Körper und Knie und setzt weich nach. Als Statue ruht die Feder (reglos).
     */
    private fun landing(state: GuardianRenderState, rig: Rig) {
        val e = measure(state, rig)
        val m = rig.motion
        if (m.started) rig.landed = false
        val down = when (state.action) {
            BiaAction.RETURN -> !rig.landed
            BiaAction.SWEEP -> e != null && m.landing(e, state.actionTime, LAND_FROM, T.KNEEL_LEAP + LAND_LATE) >= 0f && !rig.landed
            else -> false
        }
        if (down) {
            rig.landed = true
            val fall = (-min(rig.lastVy, m.vy)).coerceIn(LAND_MIN_FALL, LAND_MAX_FALL)
            rig.springs[DIP].kick(fall * LAND_KICK)
        }
        rig.lastVy = m.vy
        val dip = if (state.action == BiaAction.STATUE) {
            rig.springs[DIP].reset()
            0f
        } else rig.springs[DIP].step(0f, rig.dt)
        if (dip == 0f) return
        body.originY += dip
        legR.pitch -= dip * LAND_HIP
        legL.pitch -= dip * LAND_HIP
        shinR.pitch += dip * LAND_KNEE
        shinL.pitch += dip * LAND_KNEE
    }

    /** Die zwei Enden der Stirnbinde flattern (zwei Glieder, phasenversetzt). */
    private fun flutter(t: Float, amount: Float) {
        for (i in bands.indices) {
            val seg = i % 2
            bands[i].roll += sin(t * 0.9f + i * 1.3f + seg * 0.9f) * 0.18f * amount
            bands[i].pitch += sin(t * 1.1f + i * 0.7f + seg * 1.1f) * 0.12f * amount
        }
    }

    private fun timeline(action: Int): Array<ThroneKey>? = when (action) {
        BiaAction.LEAP -> LEAP_KEYS
        BiaAction.RUSH -> RUSH_KEYS
        BiaAction.SWEEP -> SWEEP_KEYS
        BiaAction.STAGGER -> STAGGER_KEYS
        BiaAction.RETURN -> RETURN_KEYS
        else -> null
    }

    private companion object {
        val T = KratosTimings
        val Int.f get() = toFloat()

        // --- Landestoß (Feder auf der Körperhöhe, Modell-Pixel; + = tiefer) ---
        const val DIP = 0
        val SPRINGS = arrayOf(0.42f to 0.45f)
        /** Fallgeschwindigkeit (Blöcke/Tick) → Stoß (Pixel/Tick), begrenzt. */
        const val LAND_KICK = 5.5f
        const val LAND_MIN_FALL = 0.2f
        const val LAND_MAX_FALL = 1.2f
        /** Knie und Hüfte je Pixel Senkung (rad). */
        const val LAND_KNEE = 0.09f
        const val LAND_HIP = 0.045f
        /** „KNIET!“: Landung frühestens ab diesem Aktions-Tick suchen, spätestens Sprungdauer + so viel. */
        const val LAND_FROM = 2f
        const val LAND_LATE = 6f

        // BIA POSES BEGIN (generiert aus tools/hha_assets/kratos_model.py --kotlin)
        val BONES = arrayOf("body", "chest", "head", "band_r_0", "band_r_1", "band_l_0", "band_l_1", "arm_r", "fore_r", "blade_r", "arm_l", "fore_l", "blade_l", "leg_r", "shin_r", "leg_l", "shin_l")
        val PARENTS = intArrayOf(-1, 0, 1, 2, 3, 2, 5, 1, 7, 8, 1, 10, 11, 0, 13, 0, 15)
        val LEAP_WINDUP = ThronePose(floatArrayOf(
            0.2500f, 0.0000f, 0.0000f, 0.0000f, 14.0000f, 4.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.9500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.6902f, -0.4935f, -1.5708f, 0.0000f, 0.0000f, 0.0000f,
            0.0634f, 0.0334f, 0.2819f, 0.0000f, 0.0000f, 0.0000f, 1.6902f, 0.4935f, 1.5708f, 0.0000f, 0.0000f, 0.0000f,
            0.0634f, -0.0334f, -0.2819f, 0.0000f, 0.0000f, 0.0000f, -0.5150f, -2.0050f, 0.9650f, 0.0000f, 0.0000f, 0.0000f,
            -0.8250f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.5538f, 0.5317f, 2.6126f, 0.0000f, 0.0000f, 0.0000f,
            -0.4700f, 2.0250f, -0.9150f, 0.0000f, 0.0000f, 0.0000f, -0.8250f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.5392f, -0.5092f, -2.6338f, 0.0000f, 0.0000f, 0.0000f, -0.0600f, 0.1400f, 0.0850f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, -0.0400f, 0.0000f, 0.0000f, 0.0000f, -1.4050f, -0.0750f, -0.0400f, 0.0000f, 0.0000f, 0.0000f,
            1.5250f, 0.0000f, 0.0400f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val RUSH = ThronePose(floatArrayOf(
            0.5500f, 0.0000f, 0.0000f, 0.0000f, 16.0000f, 4.0000f, 0.8500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -1.1500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.6901f, -0.2000f, -1.5708f, 0.0000f, 0.0000f, 0.0000f,
            0.0588f, 0.0036f, 0.0297f, 0.0000f, 0.0000f, 0.0000f, 1.6901f, 0.2000f, 1.5708f, 0.0000f, 0.0000f, 0.0000f,
            0.0588f, -0.0036f, -0.0297f, 0.0000f, 0.0000f, 0.0000f, -2.2900f, -1.6550f, -0.1350f, 0.0000f, 0.0000f, 0.0000f,
            -0.9600f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.2951f, 0.1053f, -3.0620f, 0.0000f, 0.0000f, 0.0000f,
            -2.6450f, 2.1550f, -0.2400f, 0.0000f, 0.0000f, 0.0000f, -0.9900f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3426f, -0.5348f, -2.9381f, 0.0000f, 0.0000f, 0.0000f, 0.2350f, 0.0250f, 0.0400f, 0.0000f, 0.0000f, 0.0000f,
            0.1650f, 0.0000f, -0.0400f, 0.0000f, 0.0000f, 0.0000f, -1.2400f, 0.0000f, -0.1450f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, 0.0400f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val SWEEP_WINDUP = ThronePose(floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 6.0000f, 0.0000f, 0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.1000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.6919f, -1.1391f, -1.5708f, 0.0000f, 0.0000f, 0.0000f,
            0.0603f, 0.0396f, 0.3321f, 0.0000f, 0.0000f, 0.0000f, 1.6919f, 1.1391f, 1.5708f, 0.0000f, 0.0000f, 0.0000f,
            0.0603f, -0.0396f, -0.3321f, 0.0000f, 0.0000f, 0.0000f, 1.1450f, -0.1200f, 1.2750f, 0.0000f, 0.0000f, 0.0000f,
            -1.5250f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.3041f, 0.8115f, 2.1633f, 0.0000f, 0.0000f, 0.0000f,
            -0.0100f, 2.3700f, -1.8500f, 0.0000f, 0.0000f, 0.0000f, -1.5250f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -1.2515f, 0.4060f, 2.2556f, 0.0000f, 0.0000f, 0.0000f, -0.4000f, -0.1700f, 0.1100f, 0.0000f, 0.0000f, 0.0000f,
            1.1000f, 0.0000f, -0.0400f, 0.0000f, 0.0000f, 0.0000f, -0.6200f, -0.2100f, -0.1050f, 0.0000f, 0.0000f, 0.0000f,
            1.1150f, 0.0000f, 0.0400f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val SWEEP_RUN = ThronePose(floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 3.0000f, 0.0000f, 0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.2000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.6879f, -0.2974f, -1.5708f, 0.0000f, 0.0000f, 0.0000f,
            0.0607f, -0.0114f, -0.0971f, 0.0000f, 0.0000f, 0.0000f, 1.6879f, 0.2974f, 1.5708f, 0.0000f, 0.0000f, 0.0000f,
            0.0607f, 0.0114f, 0.0971f, 0.0000f, 0.0000f, 0.0000f, 0.7950f, 0.1050f, 1.4150f, 0.0000f, 0.0000f, 0.0000f,
            -1.6500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -1.5478f, 0.7952f, 1.8341f, 0.0000f, 0.0000f, 0.0000f,
            0.2550f, 1.6950f, -1.8650f, 0.0000f, 0.0000f, 0.0000f, -1.6300f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -1.1105f, 0.1177f, 2.7296f, 0.0000f, 0.0000f, 0.0000f, 0.3000f, 0.1700f, 0.1100f, 0.0000f, 0.0000f, 0.0000f,
            0.3350f, 0.0000f, -0.0400f, 0.0000f, 0.0000f, 0.0000f, -0.8500f, 0.1700f, -0.1050f, 0.0000f, 0.0000f, 0.0000f,
            0.9500f, 0.0000f, 0.0400f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val STAGGER = ThronePose(floatArrayOf(
            -0.3500f, 0.0000f, 0.0000f, 0.0000f, 38.0000f, 6.0000f, -0.2500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            0.3500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.6879f, -1.1234f, -1.5708f, 0.0000f, 0.0000f, 0.0000f,
            0.0632f, 0.0229f, 0.1961f, 0.0000f, 0.0000f, 0.0000f, 1.6879f, 1.1234f, 1.5708f, 0.0000f, 0.0000f, 0.0000f,
            0.0632f, -0.0229f, -0.1961f, 0.0000f, 0.0000f, 0.0000f, 0.2050f, -2.3900f, 0.4950f, 0.0000f, 0.0000f, 0.0000f,
            -1.7050f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -2.3636f, 0.3192f, 2.6983f, 0.0000f, 0.0000f, 0.0000f,
            0.2350f, 2.4400f, -0.4450f, 0.0000f, 0.0000f, 0.0000f, -1.7000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -2.3442f, -0.2819f, -2.7505f, 0.0000f, 0.0000f, 0.0000f, -1.3100f, 0.0150f, 0.1350f, 0.0000f, 0.0000f, 0.0000f,
            0.5600f, 0.0000f, -0.0400f, 0.0000f, 0.0000f, 0.0000f, -1.7850f, -0.1450f, 0.1150f, 0.0000f, 0.0000f, 0.0000f,
            1.4300f, 0.0000f, 0.0400f, 0.0000f, 0.0000f, 0.0000f,
        ))
        val LAND = ThronePose(floatArrayOf(
            0.0000f, 0.0000f, 0.0000f, 0.0000f, 10.0000f, 2.0000f, 0.5500f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.3000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 1.6920f, 0.1682f, -1.5708f, 0.0000f, 0.0000f, 0.0000f,
            0.0564f, 0.0265f, 0.2193f, 0.0000f, 0.0000f, 0.0000f, 1.6920f, -0.1682f, 1.5708f, 0.0000f, 0.0000f, 0.0000f,
            0.0564f, -0.0265f, -0.2193f, 0.0000f, 0.0000f, 0.0000f, 0.3750f, -0.0800f, 0.4550f, 0.0000f, 0.0000f, 0.0000f,
            -1.9550f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, -0.8736f, -0.4093f, 0.5694f, 0.0000f, 0.0000f, 0.0000f,
            0.3750f, 0.0800f, -0.4550f, 0.0000f, 0.0000f, 0.0000f, -1.9550f, 0.0000f, 0.0000f, 0.0000f, 0.0000f, 0.0000f,
            -0.8736f, 0.4093f, -0.5694f, 0.0000f, 0.0000f, 0.0000f, -0.5600f, -0.1800f, 0.0550f, 0.0000f, 0.0000f, 0.0000f,
            1.4450f, 0.0000f, -0.0400f, 0.0000f, 0.0000f, 0.0000f, -0.1750f, -0.2250f, -0.0400f, 0.0000f, 0.0000f, 0.0000f,
            -0.0200f, 0.0000f, 0.0400f, 0.0000f, 0.0000f, 0.0000f,
        ))
        // BIA POSES END

        /** Sprung vom Sockel: Kopf hebt sich, Hocke, Klingen nach hinten; gehalten bis zum Lauf. */
        val LEAP_KEYS = arrayOf(ThroneKey(0f, null), ThroneKey(8f, LEAP_WINDUP))

        /** Bias Sturm: gestreckt, Klingen vorn wie eine Speerspitze, gehalten. */
        val RUSH_KEYS = arrayOf(ThroneKey(0f, LEAP_WINDUP), ThroneKey(3f, RUSH, true))

        /** „KNIET!": Klingen seitlich gestreckt (Ausholen bis 30), Lauf quer über den Platz 30–60. */
        val SWEEP_KEYS = arrayOf(
            ThroneKey(0f, null), ThroneKey(10f, SWEEP_WINDUP), ThroneKey(T.BIA_SWEEP_WINDUP.f, SWEEP_WINDUP),
            ThroneKey(T.BIA_SWEEP_WINDUP + 3f, SWEEP_RUN, true), ThroneKey(T.BIA_SWEEP_END.f, SWEEP_RUN),
            ThroneKey(T.BIA_SWEEP_END + 8f, null),
        )

        /** Abgeprallt: sitzt rücklings, Klingen neben sich; Schleife bis 80. */
        val STAGGER_KEYS = arrayOf(
            ThroneKey(0f, null), ThroneKey(5f, STAGGER, true), ThroneKey(T.BIA_STAGGER_END - 8f, STAGGER),
            ThroneKey(T.BIA_STAGGER_END.f, null),
        )

        /** Zurück auf den Sockel: Landung in der Hocke, dann kreuzt sie die Klingen (endet in der Statue). */
        val RETURN_KEYS = arrayOf(
            ThroneKey(0f, null), ThroneKey(5f, LAND), ThroneKey(T.BIA_RETURN_END - 8f, LAND), ThroneKey(T.BIA_RETURN_END.f, null),
        )
    }
}
