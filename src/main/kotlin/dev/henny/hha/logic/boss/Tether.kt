package dev.henny.hha.logic.boss

import dev.henny.hha.logic.Fx
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.math.Vec3d
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Leine statt Fixierung (Überarbeitung 4, PLAN 4.1 „Greifen“, 7.6; Besitzer P04): [player] hängt höchstens [ticks]
 * Ticks an [anchor] (wird jeden Tick neu gelesen, darf also wandern).
 *
 * Wirkung je [tick], nur **waagerecht** (Springen und Fallen bleiben frei):
 * - innerhalb von [radius]: nichts;
 * - jenseits von [radius]: der nach außen gerichtete Anteil der Geschwindigkeit fällt weg, dazu ein kleiner Zug nach
 *   innen `min(PULL_MAX; (Abstand − radius) · PULL_PER_BLOCK)`;
 * - jenseits von `radius + SNAP_MARGIN` (z. B. ein Rückstoß, ein Sprint-Sprung): **einmal** je Leine zurück an den Rand
 *   gesetzt, auf der eigenen Höhe und mit der senkrechten Geschwindigkeit nicht schneller als 0 nach unten (keine
 *   Fallhöhe). Danach greift nur noch die Dämpfung.
 *
 * Kein Statuseffekt, keine Eingabesperre, Schlagen und Springen bleiben. Löst sich von selbst, wenn die Zeit um ist oder
 * der Spieler tot, k. o. (`Fx.canAffect`), abgemeldet oder in einer anderen Welt ist. Die sichtbare Kette zeichnet der
 * Kampf (`Vfx.chain`).
 */
class Tether(val player: ServerPlayerEntity, val anchor: () -> Vec3d, val radius: Double, val ticks: Int) {

    private var left = ticks
    private val world = player.entityWorld
    private var snapped = false

    var released: Boolean = false
        private set

    /** Verbleibende Ticks (0, wenn gelöst). */
    val remaining: Int get() = if (released) 0 else left

    /** Ein Server-Tick; `false`, wenn die Leine abgelaufen oder gelöst ist (dann wirkt sie nicht mehr). */
    fun tick(): Boolean {
        if (released) return false
        if (--left < 0 || !Fx.canAffect(player) || player.entityWorld !== world || player.isSpectator) {
            release()
            return false
        }
        hold()
        return true
    }

    /** Beendet die Leine sofort. Mehrfaches Lösen schadet nicht. */
    fun release() {
        released = true
        left = 0
    }

    private fun hold() {
        val a = anchor()
        val pos = player.entityPos
        val dx = pos.x - a.x
        val dz = pos.z - a.z
        val dist = sqrt(dx * dx + dz * dz)
        if (dist <= radius || dist < 1e-6) return
        val nx = dx / dist
        val nz = dz / dist
        if (dist > radius + SNAP_MARGIN && !snapped) {
            // Einmaliges Zurücksetzen an den Rand: gleiche Höhe, kein Sturz aus der Bewegung heraus.
            snapped = true
            val edge = radius - SNAP_INSET
            player.requestTeleport(a.x + nx * edge, pos.y, a.z + nz * edge)
            Fx.launchPlayer(player, Vec3d(0.0, clientVelocity().y.coerceAtLeast(0.0), 0.0))
            player.fallDistance = 0.0
            return
        }
        val v = clientVelocity()
        val outward = v.x * nx + v.z * nz
        val pull = min(PULL_MAX, (dist - radius) * PULL_PER_BLOCK)
        // Nur der Anteil nach außen fällt weg; seitlich und nach innen bleibt die Bewegung frei.
        val cut = if (outward > 0.0) outward else 0.0
        Fx.launchPlayer(player, Vec3d(v.x - nx * (cut + pull), v.y, v.z - nz * (cut + pull)))
    }

    /**
     * Die Geschwindigkeit, die der Client gerade hat. Am Server ist `velocity` eines Spielers veraltet; `movement` ist
     * der Weg des letzten Ticks (vor Reibung und Schwerkraft). Daraus: waagerecht × Reibung, senkrecht wie Vanilla
     * (`(dy − 0,08) · 0,98`), am Boden 0.
     */
    private fun clientVelocity(): Vec3d {
        val m = player.movement
        val friction = if (player.isOnGround) GROUND_FRICTION else AIR_FRICTION
        val vy = if (player.isOnGround) 0.0 else (m.y - GRAVITY) * DRAG
        return Vec3d(m.x * friction, vy, m.z * friction)
    }

    companion object {
        /** Höchster Zug nach innen je Tick (Blöcke/Tick). */
        const val PULL_MAX = 0.25

        /** Zug nach innen je Block jenseits des Radius. */
        const val PULL_PER_BLOCK = 0.15

        /** Ab `radius + SNAP_MARGIN` einmal zurück an den Rand. */
        const val SNAP_MARGIN = 2.0

        /** So weit innerhalb des Rands landet der Spieler beim Zurücksetzen. */
        const val SNAP_INSET = 0.2

        private const val GROUND_FRICTION = 0.546
        private const val AIR_FRICTION = 0.91
        private const val GRAVITY = 0.08
        private const val DRAG = 0.98
    }
}
