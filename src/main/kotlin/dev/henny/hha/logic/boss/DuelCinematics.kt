package dev.henny.hha.logic.boss

import net.minecraft.entity.LivingEntity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Vec3d
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Filmische Rahmung für Bosse und Wächter ohne eigenes Szenen-Drehbuch (Hekate, Brontes, Kratos, die fünf Wächter des
 * Tartaros). Sie legt sich um die vorhandenen Abläufe des Bosses (Auftritt, Phasenwechsel, Sieg): Kamera fährt heran
 * bzw. kreist, Blende aus Schwarz, ein bis zwei Zeilen, danach geht der Kampf normal weiter.
 *
 * Wie bei Zeus ([ZeusScenes]) sind die Szenen überspringbar (alle halten die Taste, dann spult der Ablauf vor) und
 * währenddessen nimmt der Boss keinen Schaden ([playing]). Ohne Zuschauer läuft nur der innere Ablauf.
 */
class DuelCinematics(
    private val boss: LivingEntity,
    private val viewers: () -> List<ServerPlayerEntity>,
) {
    /** Eine Zeile einer Szene: Sprecher ([Cine.SPEAKER_NARRATOR] usw.), Sprachschlüssel, Tick in der Szene. */
    class Line(val speaker: Int, val key: String, val at: Int, val ticks: Int = 60)

    private val skip = CineSkip()
    private var current: Iterator<Unit>? = null
    private var audience: List<ServerPlayerEntity> = emptyList()

    /** Läuft gerade eine Szene? Dann nimmt der Boss keinen Schaden. */
    var playing = false
        private set

    // ---------------------------------------------------------------------------------------------
    // Szenen
    // ---------------------------------------------------------------------------------------------

    /**
     * Auftritt: Blende aus Schwarz, die Kamera fährt von weit vorn bis vor sein Gesicht, während [inner] (der eigene
     * Auftritt des Bosses: Titel, Musik, Aufstehen …) läuft; dazu [lines]. Die Szene dauert mindestens [minTicks].
     */
    fun intro(inner: Sequence<Unit>, lines: List<Line>, minTicks: Int = 110): Sequence<Unit> = framed(inner, lines, minTicks, Int.MAX_VALUE) { players ->
        Cine.fade(players, 0x000000, 1.0, 0.0, 16)
        val front = facing()
        val eye = boss.height * 0.85
        val far = boss.entityPos.add(front.multiply(16.0 + boss.width * 2)).add(0.0, eye + 5.0, 0.0)
        val near = boss.entityPos.add(front.multiply(4.5 + boss.width * 1.5)).add(side().multiply(1.2)).add(0.0, eye * 0.85, 0.0)
        Cine.camera(players, far, near, boss.entityPos.add(0.0, eye * 0.6, 0.0), boss.entityPos.add(0.0, eye, 0.0), minTicks, 62.0, 52.0, boss, eye)
    }

    /** Phasenwechsel: Kamera kreist halb um den Boss, während [inner] (Titel, Zeile, Umbau) läuft; höchstens [maxTicks]. */
    fun phase(inner: Sequence<Unit>, lines: List<Line>, minTicks: Int = 70, maxTicks: Int = 120): Sequence<Unit> = framed(inner, lines, minTicks, maxTicks) { players ->
        val yaw = yawDeg()
        val r = 6.0 + boss.width * 1.6
        Cine.orbit(players, boss.entityPos, r, boss.height * 0.55, yaw - 50.0, yaw + 30.0, maxOf(minTicks, 60), 66.0, 56.0, boss, boss.height * 0.75, r * 0.85)
    }

    /**
     * Niederlage: Kamera kreist langsam, der Boss gibt nach; [inner] (Beute, Fortschritt, Verschwinden) läuft weiter, die
     * Szene selbst endet spätestens nach [maxTicks], danach läuft [inner] ohne Kamera zu Ende.
     */
    fun defeat(inner: Sequence<Unit>, lines: List<Line>, minTicks: Int = 90, maxTicks: Int = 130): Sequence<Unit> = framed(inner, lines, minTicks, maxTicks) { players ->
        val yaw = yawDeg()
        val r = 5.5 + boss.width * 1.4
        Cine.orbit(players, boss.entityPos, r, boss.height * 0.45, yaw - 20.0, yaw + 25.0, maxTicks, 60.0, 50.0, boss, boss.height * 0.7, r * 0.8)
    }

    // ---------------------------------------------------------------------------------------------
    // Ablauf
    // ---------------------------------------------------------------------------------------------

    private fun framed(
        inner: Sequence<Unit>, lines: List<Line>, minTicks: Int, maxTicks: Int, camera: (List<ServerPlayerEntity>) -> Unit,
    ): Sequence<Unit> = sequence {
        val players = viewers().filter { !it.isRemoved && !it.isDisconnected }
        if (players.isEmpty()) {
            yieldAll(inner)
            return@sequence
        }
        register()
        audience = players
        playing = true
        active.add(this@DuelCinematics)
        Cine.start(players)
        camera(players)
        val innerIt = inner.iterator()
        val scene = sequence {
            var t = 0
            var innerDone = false
            while (t < maxTicks && (t < minTicks || !innerDone)) {
                for (line in lines) if (line.at == t) Cine.dialog(audience, line.speaker, line.key, HhaVoiceLengths.ticks(line.key, line.ticks))
                if (!innerDone) {
                    if (innerIt.hasNext()) innerIt.next() else innerDone = true
                }
                t++
                yield(Unit)
            }
        }.iterator()
        current = scene
        while (true) {
            if (skip.pending) skip.fastForward(audience, ::advance)
            val running = current ?: break
            if (!running.hasNext()) break
            running.next()
            yield(Unit)
        }
        finish()
        // Was vom inneren Ablauf noch übrig ist (z. B. Verschwinden nach dem Sieg), läuft ohne Kamera weiter.
        while (innerIt.hasNext()) {
            innerIt.next()
            yield(Unit)
        }
    }

    private fun advance(): Boolean {
        val running = current ?: return false
        if (!running.hasNext()) {
            current = null
            return false
        }
        running.next()
        return true
    }

    private fun finish() {
        if (playing) Cine.end((audience + viewers()).distinctBy { it.uuid })
        playing = false
        audience = emptyList()
        current = null
        active.remove(this)
        skip.reset()
    }

    /** Der Kampf endet mitten in einer Szene: Clients vollständig zurücksetzen. */
    fun abort() {
        if (playing) Cine.reset((audience + viewers()).distinctBy { it.uuid })
        playing = false
        audience = emptyList()
        current = null
        active.remove(this)
        skip.reset()
    }

    private fun wants(player: ServerPlayerEntity): Boolean = playing && audience.any { it.uuid == player.uuid }

    private fun vote(player: ServerPlayerEntity): Boolean {
        if (!wants(player)) return false
        skip.vote(player, audience)
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Blickrichtung
    // ---------------------------------------------------------------------------------------------

    private fun yawDeg(): Double {
        val r = Math.toRadians(boss.yaw.toDouble())
        return Math.toDegrees(atan2(cos(r), -sin(r)))
    }

    /** Waagerechte Blickrichtung des Bosses (dorthin steht die Kamera). */
    private fun facing(): Vec3d {
        val r = Math.toRadians(boss.yaw.toDouble())
        return Vec3d(-sin(r), 0.0, cos(r))
    }

    private fun side(): Vec3d = facing().let { Vec3d(-it.z, 0.0, it.x) }

    @Suppress("unused")
    private val world: ServerWorld? get() = boss.entityWorld as? ServerWorld

    private companion object {
        private val active = HashSet<DuelCinematics>()
        private var registered = false

        private fun register() {
            if (registered) return
            registered = true
            Cine.onSkip { player -> active.firstOrNull { it.wants(player) }?.vote(player) == true }
        }
    }
}
