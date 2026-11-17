package dev.henny.hha.client.anim

import net.minecraft.entity.LivingEntity
import net.minecraft.util.math.MathHelper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Was der Client von einem Wesen zwischen zwei Bildern sieht: Tempo, Steigen/Fallen, Drehrate, Gangphase, Neustart
 * einer Aktion, Landung. [update] einmal je gezeichnetem Bild aufrufen (z. B. im `updateRenderState` des Renderers);
 * liefert den Zeitschritt des Bildes in Ticks (0, wenn das Bild nicht weiterlief). Eine Instanz je Wesen.
 * Port aus HHA Blood `BossAnim.kt` (MIT).
 */
class Motion {
    /** Zeit des letzten Bildes (Alter + Zwischenbild); NaN vor dem ersten [update]. */
    var time = Float.NaN
        private set
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastZ = 0.0
    private var lastYaw = 0f

    /** Waagerechtes Tempo (Blöcke/Tick, geglättet). */
    var speed = 0f
        private set

    /** Senkrechtes Tempo (Blöcke/Tick, geglättet, + = aufwärts). */
    var vy = 0f
        private set

    /** Drehrate des Körpers (Grad/Tick, geglättet, + = nach rechts). */
    var turn = 0f
        private set

    /** Gangphase aus der gelaufenen Strecke (Radiant, siehe [advanceGait]). */
    var gait = 0f

    /** Phase aus dem Drehen auf der Stelle. */
    var turnStep = 0f

    /** Freie, zeitgetriebene Phase (Laufen, Kreiseln). */
    var run = 0f

    /** Aktion des letzten Bildes, die davor, und ob in diesem Bild eine neue Aktion (oder ein Neustart) begann. */
    var action = Int.MIN_VALUE
        private set
    var previous = Int.MIN_VALUE
        private set
    private var lastAge = 0
    var started = false
        private set

    /** Aktionszeit, zu der das Wesen bei einem Sprung wieder aufsetzte (−1 = noch nicht). */
    var landT = -1f
    private var wasFalling = false

    /** War es in dieser Aktion in der Luft? */
    var airborne = false
        private set

    /** Pose des letzten Bildes und Überblendung aus ihr in eine neue Aktion (frei für den Renderer). */
    var lastPose: Any? = null
    var fromPose: Any? = null
    var blendStart = 0f
    var blendLen = 0f

    /** Freie Zusatzwerte je Renderer (z. B. welcher Kopf einen Angriff begann). */
    var mark = 0
    var markTime = -999f

    /** Ein Bild: [action]/[actionAge] aus dem getrackten Zustand des Wesens. Liefert den Zeitschritt in Ticks. */
    fun update(e: LivingEntity, tickDelta: Float, action: Int, actionAge: Int): Float {
        val now = e.age + tickDelta
        val pos = e.getLerpedPos(tickDelta)
        val yaw = MathHelper.lerpAngleDegrees(tickDelta, e.lastBodyYaw, e.bodyYaw)
        started = false
        if (time.isNaN() || now < time - 0.01f || now - time > 40f) {
            time = now
            lastX = pos.x; lastY = pos.y; lastZ = pos.z; lastYaw = yaw
            action0(action, actionAge)
            return 0f
        }
        val dt = (now - time).coerceIn(0f, 4f)
        if (action != this.action || actionAge < lastAge) action0(action, actionAge)
        lastAge = actionAge
        if (dt <= 1e-4f) return 0f
        time = now
        val dx = pos.x - lastX
        val dy = pos.y - lastY
        val dz = pos.z - lastZ
        var dyaw = yaw - lastYaw
        while (dyaw > 180f) dyaw -= 360f
        while (dyaw < -180f) dyaw += 360f
        lastX = pos.x; lastY = pos.y; lastZ = pos.z; lastYaw = yaw
        val k = 1f - exp(-dt / 2.2f)
        val inst = (sqrt(dx * dx + dz * dz) / dt).toFloat()
        speed += (min(inst, 3f) - speed) * k
        vy += ((dy / dt).toFloat().coerceIn(-3f, 3f) - vy) * (1f - exp(-dt / 1.2f))
        turn += ((dyaw / dt).coerceIn(-60f, 60f) - turn) * (1f - exp(-dt / 2.5f))
        if (!e.isOnGround || abs(vy) > 0.08f) airborne = true
        return dt
    }

    private fun action0(a: Int, age: Int) {
        previous = action
        action = a
        lastAge = age
        started = true
        landT = -1f
        wasFalling = false
        airborne = false
    }

    /** Schiebt die Gangphase für einen Schrittzyklus von [cycle] Blöcken weiter (höchstens [maxRate] rad je Tick). */
    fun advanceGait(dt: Float, cycle: Float, maxRate: Float) {
        val rate = min(speed / cycle * 2f * PI.toFloat(), maxRate)
        gait = (gait + rate * dt) % (2f * PI.toFloat() * 64f)
    }

    /**
     * Erkennt die Landung eines Sprungs, der zur Aktionszeit [from] begann: liefert die Aktionszeit der Landung oder −1.
     * [t] = aktuelle Aktionszeit, nach [latest] gilt der Sprung in jedem Fall als gelandet.
     */
    fun landing(e: LivingEntity, t: Float, from: Float, latest: Float): Float {
        if (landT >= 0f) return landT
        if (t < from) return -1f
        if (vy < -0.12f) wasFalling = true
        val down = t > from + 3f && (e.isOnGround && (wasFalling || t > from + 8f) || wasFalling && vy > -0.04f)
        if (down || t > latest) landT = t
        return landT
    }

    companion object {
        /** Dreht (y, z) um [a] Radiant (Knie-/Hüft-Hilfe). */
        fun rot(y: Float, z: Float, a: Float): FloatArray = floatArrayOf(y * cos(a) - z * sin(a), y * sin(a) + z * cos(a))
    }
}
