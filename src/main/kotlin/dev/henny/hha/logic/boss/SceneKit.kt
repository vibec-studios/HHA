package dev.henny.hha.logic.boss

import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Language
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Server-Bausteine für Drehbücher (PLAN 6.5, REF T12, Blood `PitScenes`): Schritte als `Sequence<Unit>` (ein `yield`
 * je Tick), wie die bestehenden Abläufe der Kämpfe. Alle Bewegungen setzen Lage und Blick des echten Wesens je Tick;
 * der Client sieht sie über das normale Nachführen (Kopf und Körper drehen mit, damit die Modelle Schritte zeigen).
 *
 * Jede Bewegung endet **genau** am Ziel (letzter Tick setzt den Endpunkt) und stellt `noGravity` wieder her, so dass
 * ein vorgespultes oder übersprungenes Drehbuch dasselbe Ergebnis hinterlässt wie ein abgespieltes.
 */
object SceneKit {

    /** Lesedauer einer Zeile ohne Aufnahme und ohne lesbaren Text. */
    const val DEFAULT_LINE_TICKS = 60

    /** Lesedauer aus der Textlänge: Zeichen je Tick und Zuschlag (wie die Dialogbox, `CinePlayer`). */
    const val CHARS_PER_TICK = 1.15f
    const val READ_EXTRA_TICKS = 26
    const val MIN_LINE_TICKS = 40

    /** Anteil der Laufzeit, über den [prowl] anfährt bzw. bremst. */
    const val PROWL_RAMP = 0.2

    /** Stützpunkte je Kurvenabschnitt in [prowl]. */
    private const val PROWL_SAMPLES = 24

    /** [turnTo] gibt nach so vielen Ticks auf (fester Endzustand: dann steht der Blick genau). */
    const val TURN_MAX_TICKS = 60

    /** Wartet [ticks] Ticks. */
    fun wait(ticks: Int): Sequence<Unit> = sequence { repeat(ticks.coerceAtLeast(0)) { yield(Unit) } }

    /**
     * Zeile [key] von [speaker] an [players] und wartet ihre Lesedauer: [ticks] > 0 gilt fest, sonst das Größere aus
     * `HhaVoiceLengths` (Aufnahme) und der Textlänge ([readTicks]).
     */
    fun say(players: List<ServerPlayerEntity>, speaker: Int, key: String, ticks: Int = 0): Sequence<Unit> = sequence {
        val length = if (ticks > 0) ticks else HhaVoiceLengths.ticks(key, readTicks(key))
        Cine.dialog(players, speaker, key, length)
        yieldAll(wait(length))
    }

    /**
     * Lesedauer der Zeile [key] aus ihrem Text (Server-Sprache, Fabric lädt die `en_us` der Mods auch auf dem
     * Server): `max(Länge / CHARS_PER_TICK + READ_EXTRA_TICKS, MIN_LINE_TICKS)`; unbekannter Schlüssel →
     * [DEFAULT_LINE_TICKS].
     */
    fun readTicks(key: String): Int {
        val language = Language.getInstance()
        if (!language.hasTranslation(key)) return DEFAULT_LINE_TICKS
        val length = language.get(key).length
        return maxOf((length / CHARS_PER_TICK).toInt() + READ_EXTRA_TICKS, MIN_LINE_TICKS)
    }

    /**
     * [entity] gleitet in [ticks] nach [to] (smoothstep), [lift] = Höhe des Sprungbogens (Blood `glide`). Schwerkraft
     * ist dabei aus und danach wie vorher.
     */
    fun glide(entity: Entity, to: Vec3d, ticks: Int, lift: Double = 0.0): Sequence<Unit> = sequence {
        val from = entity.entityPos
        val gravity = entity.hasNoGravity()
        entity.setNoGravity(true)
        try {
            val n = ticks.coerceAtLeast(1)
            for (i in 1..n) {
                val raw = i.toDouble() / n
                val t = raw * raw * (3 - 2 * raw)
                place(entity, from.lerp(to, t).add(0.0, lift * 4 * raw * (1 - raw), 0.0))
                if (i < n) yield(Unit)
            }
            place(entity, to)
            yield(Unit)
        } finally {
            entity.setNoGravity(gravity)
        }
    }

    /**
     * [entity] dreht sich in Schritten von höchstens [maxStep]° je Tick zu [yaw] (Grad), Körper und Kopf mit (der Client
     * zeigt Schritte). Fertig, sobald der Blick steht; spätestens nach [TURN_MAX_TICKS] wird er gesetzt.
     */
    fun turnTo(entity: Entity, yaw: Float, maxStep: Float = 12f): Sequence<Unit> = sequence {
        var guard = 0
        while (abs(MathHelper.wrapDegrees(yaw - entity.yaw)) > 0.5f && guard++ < TURN_MAX_TICKS) {
            turn(entity, MathHelper.stepUnwrappedAngleTowards(entity.yaw, yaw, maxStep))
            yield(Unit)
        }
        turn(entity, yaw)
    }

    /**
     * [entity] schreitet über [path] in [ticks] (Blood `prowl`): Catmull-Rom-Kurve von der jetzigen Lage durch alle
     * Punkte, nach Weglänge gleichmäßig, Trapez-Tempo (anfahren, halten, bremsen), dreht mit höchstens [maxTurn]° je
     * Tick in die Laufrichtung, kein Einrasten an Ecken. Endet genau am letzten Punkt.
     */
    fun prowl(entity: Entity, path: List<Vec3d>, ticks: Int, maxTurn: Float = 12f): Sequence<Unit> = sequence {
        if (path.isEmpty()) return@sequence
        val ctrl = listOf(entity.entityPos) + path
        val samples = ArrayList<Vec3d>()
        for (seg in 0 until ctrl.size - 1) {
            val p0 = ctrl[maxOf(seg - 1, 0)]
            val p3 = ctrl[minOf(seg + 2, ctrl.size - 1)]
            for (k in 0 until PROWL_SAMPLES) samples += catmull(p0, ctrl[seg], ctrl[seg + 1], p3, k / PROWL_SAMPLES.toDouble())
        }
        samples += ctrl.last()
        val acc = DoubleArray(samples.size)
        for (i in 1 until samples.size) acc[i] = acc[i - 1] + samples[i].distanceTo(samples[i - 1])
        val total = acc.last()
        val gravity = entity.hasNoGravity()
        entity.setNoGravity(true)
        try {
            val n = ticks.coerceAtLeast(1)
            var j = 0
            for (i in 1..n) {
                val s = total * trapezoid(i.toDouble() / n, PROWL_RAMP)
                while (j < samples.size - 2 && acc[j + 1] < s) j++
                val len = (acc[j + 1] - acc[j]).coerceAtLeast(1e-6)
                val p = samples[j].lerp(samples[j + 1], ((s - acc[j]) / len).coerceIn(0.0, 1.0))
                val ahead = samples[minOf(j + 4, samples.size - 1)]
                if (ahead.squaredDistanceTo(p.x, ahead.y, p.z) > 0.04) {
                    turn(entity, MathHelper.stepUnwrappedAngleTowards(entity.yaw, yawTo(p, ahead), maxTurn))
                }
                place(entity, if (i == n) ctrl.last() else p)
                yield(Unit)
            }
        } finally {
            entity.setNoGravity(gravity)
        }
    }

    private fun place(entity: Entity, p: Vec3d) {
        entity.setPosition(p)
        entity.velocity = Vec3d.ZERO
    }

    private fun turn(entity: Entity, yaw: Float) {
        entity.yaw = yaw
        entity.headYaw = yaw
        if (entity is LivingEntity) entity.bodyYaw = yaw
    }

    private fun yawTo(from: Vec3d, to: Vec3d): Float = (Math.toDegrees(atan2(to.z - from.z, to.x - from.x)) - 90.0).toFloat()

    private fun catmull(p0: Vec3d, p1: Vec3d, p2: Vec3d, p3: Vec3d, u: Double): Vec3d {
        val u2 = u * u
        val u3 = u2 * u
        fun c(a: Double, b: Double, c: Double, d: Double) =
            0.5 * (2 * b + (-a + c) * u + (2 * a - 5 * b + 4 * c - d) * u2 + (-a + 3 * b - 3 * c + d) * u3)
        return Vec3d(c(p0.x, p1.x, p2.x, p3.x), c(p0.y, p1.y, p2.y, p3.y), c(p0.z, p1.z, p2.z, p3.z))
    }

    /** Wegprofil 0..1: beschleunigt über den Anteil [r], hält das Tempo, bremst über den letzten Anteil [r]. */
    private fun trapezoid(x: Double, r: Double): Double {
        val v = 1.0 / (1.0 - r)
        return when {
            x < r -> 0.5 * v * x * x / r
            x > 1 - r -> 1.0 - 0.5 * v * (1 - x) * (1 - x) / r
            else -> 0.5 * v * r + v * (x - r)
        }
    }
}

/**
 * Ein Drehbuch-Lauf mit festem Endzustand (Blood `SceneRun`): [steps] läuft tickweise, [finish] setzt das Ergebnis
 * (Ort, Pose, Phase, Musik) – genau einmal, ob der Lauf zu Ende geht oder übersprungen wird ([skip], nur wenn
 * [skippable]). [abort] beendet ohne [finish] (der Kampf selbst ist fort).
 */
class SceneRun(val name: String, steps: Sequence<Unit>, val skippable: Boolean = true, private val finish: () -> Unit = {}) {
    private val iterator = steps.iterator()
    private var done = false

    /** Ein Tick; false, wenn der Lauf zu Ende ist (dann wurde [finish] gerufen). */
    fun tick(): Boolean {
        if (done) return false
        if (iterator.hasNext()) {
            iterator.next()
            return true
        }
        end()
        return false
    }

    /** Überspringen: sofort zum Endzustand. Bei `skippable = false` ohne Wirkung. */
    fun skip() {
        if (skippable && !done) end()
    }

    /** Abbrechen ohne Endzustand (Kampf vorbei, Arena entladen). */
    fun abort() {
        done = true
    }

    val finished: Boolean get() = done

    private fun end() {
        done = true
        finish()
    }
}
