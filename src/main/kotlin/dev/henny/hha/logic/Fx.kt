package dev.henny.hha.logic

import dev.henny.hha.config.HhaConfig
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket
import net.minecraft.particle.ParticleEffect
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin

/** Gemeinsame Effekt- und Bewegungs-Helfer. */
object Fx {

    /** Vanilla-Partikel je Stoß im Kampf (Plan 3.3); [spawn] kappt im Kampf-Scope darauf, [burst] immer. */
    const val BURST_MAX = 24

    /** Dauer-Emitter im Kampf: höchstens so viele Partikel je Tick … (Plan 3.3, vom Kampf selbst einzuhalten) */
    const val EMITTER_MAX_PER_TICK = 4

    /** … und höchstens so viele Emitter gleichzeitig. */
    const val EMITTERS_MAX = 2

    /**
     * Effekt-Partikel (Fähigkeiten, Treffer, Explosionen): skaliert mit dem
     * Config-Wert `particle_effects` (1 = alles, 0 = aus). Bei Werten unter 1
     * werden kleine Counts probabilistisch gerundet, damit Einzelpartikel-
     * Effekte anteilig ausgedünnt werden statt komplett zu verschwinden.
     */
    fun spawn(
        world: ServerWorld,
        particle: ParticleEffect,
        x: Double,
        y: Double,
        z: Double,
        count: Int,
        dx: Double,
        dy: Double,
        dz: Double,
        speed: Double,
    ) {
        if (dev.henny.hha.logic.boss.Cine.muted) return
        // Im Kampf (Effekte des Gegners) höchstens [BURST_MAX] je Stoß; Cutscenes dürfen mehr.
        val fight = dev.henny.hha.logic.boss.Telegraph.current
        val capped = if (fight != null && !fight.inCutscene()) count.coerceAtMost(BURST_MAX) else count
        val scaled = scaledCount(world, capped, "particle_effects")
        if (scaled > 0) {
            world.spawnParticles(particle, x, y, z, scaled, dx, dy, dz, speed)
        }
    }

    /**
     * Ein Partikelstoß eines Gegners (Treffer, Schritt, Sog): immer höchstens [BURST_MAX] Partikel, skaliert mit
     * `particle_effects`. [spread] ist die Streuung in alle Richtungen.
     */
    fun burst(world: ServerWorld, particle: ParticleEffect, at: Vec3d, count: Int, spread: Double = 0.4, speed: Double = 0.02) =
        spawn(world, particle, at.x, at.y, at.z, count.coerceAtMost(BURST_MAX), spread, spread, spread, speed)

    /** Idle-Partikel am Spieler (Orbit-Glühen, Rüstungsflammen, Halo …): skaliert mit `particle_player`. */
    fun spawnAmbient(
        world: ServerWorld,
        particle: ParticleEffect,
        x: Double,
        y: Double,
        z: Double,
        count: Int,
        dx: Double,
        dy: Double,
        dz: Double,
        speed: Double,
    ) {
        val scaled = scaledCount(world, count, "particle_player")
        if (scaled > 0) {
            world.spawnParticles(particle, x, y, z, scaled, dx, dy, dz, speed)
        }
    }

    /** Unskaliert — für funktionale Visuals (z. B. die Grapple-Kette), die lesbar bleiben müssen. */
    fun spawnRaw(
        world: ServerWorld,
        particle: ParticleEffect,
        x: Double,
        y: Double,
        z: Double,
        count: Int,
        dx: Double,
        dy: Double,
        dz: Double,
        speed: Double,
    ) {
        world.spawnParticles(particle, x, y, z, count, dx, dy, dz, speed)
    }

    private fun scaledCount(world: ServerWorld, count: Int, configKey: String): Int {
        val multiplier = HhaConfig.num(configKey)
        if (multiplier >= 1.0) return count
        if (multiplier <= 0.0) return 0
        val exact = count * multiplier
        val base = exact.toInt()
        val fraction = exact - base
        return base + if (world.random.nextDouble() < fraction) 1 else 0
    }

    /**
     * Setzt die Velocity eines Spielers UND synchronisiert sie zum Client.
     * `velocityDirty` allein schickt das Paket nur an andere Spieler — der
     * eigene Client würde die Bewegung sonst nie erfahren.
     */
    /**
     * Spieler, die gerade k.o. aus einem Kampf geworfen wurden: Der tödliche Treffer läuft im selben Aufruf weiter
     * (Rückstoß, Wither, Slowness …). Das soll sie am Rückkehrort nicht mehr erreichen. Wert: der Server und dessen
     * Tick-Zähler, bis zu dem das gilt. Ein anderer Server (neue Welt im selben Spiel) oder ein Zähler, der nicht passt,
     * hebt den Schutz auf; Logout und Server-Stopp leeren die Tabelle ([forget], [clearShields]).
     */
    private class Shield(val server: net.minecraft.server.MinecraftServer, val until: Int)

    private val shielded = HashMap<java.util.UUID, Shield>()

    /** Schützt [player] für [ticks] Server-Ticks vor Folge-Effekten von Angriffen (vom Kampf beim K.o. aufgerufen). */
    fun shield(player: ServerPlayerEntity, ticks: Int = 10) {
        val server = (player.entityWorld as? ServerWorld)?.server ?: return
        shielded[player.uuid] = Shield(server, server.ticks + ticks)
    }

    /** Darf ein Angriff [player] noch Rückstoß oder Statuseffekte geben (lebt, verbunden, nicht gerade k.o. gegangen)? */
    fun canAffect(player: ServerPlayerEntity): Boolean {
        if (!player.isAlive || player.isRemoved || player.isDisconnected) return false
        val shield = shielded[player.uuid] ?: return true
        val server = (player.entityWorld as? ServerWorld)?.server
        // Restzeit als Differenz: bleibt auch beim Überlauf des Int-Zählers richtig.
        val remaining = if (server === shield.server) shield.until - server.ticks else -1
        if (remaining !in 0..40) {
            shielded.remove(player.uuid)
            return true
        }
        return false
    }

    /** Logout: Schutz vergessen. */
    fun forget(player: ServerPlayerEntity) {
        shielded.remove(player.uuid)
    }

    /** Server fährt herunter: alle Schutz-Einträge vergessen. */
    fun clearShields() {
        shielded.clear()
    }

    fun launchPlayer(player: ServerPlayerEntity, velocity: Vec3d) {
        // Gemerkte Ziele können inzwischen tot, ausgeloggt, k.o. oder in einer anderen Welt sein (Abläufe warten oft Ticks).
        if (!canAffect(player)) return
        player.velocity = velocity
        player.velocityDirty = true
        player.networkHandler.sendPacket(EntityVelocityUpdateS2CPacket(player))
    }

    /** Horizontaler Partikelring um einen Mittelpunkt. */
    fun ring(
        world: ServerWorld,
        center: Vec3d,
        radius: Double,
        particle: ParticleEffect,
        points: Int = 24,
        speed: Double = 0.02,
    ) {
        for (i in 0 until points) {
            val angle = (i.toDouble() / points) * Math.PI * 2.0
            world.spawnParticles(
                particle,
                center.x + cos(angle) * radius,
                center.y,
                center.z + sin(angle) * radius,
                1, 0.02, 0.05, 0.02, speed
            )
        }
    }

    /**
     * Partikelring in einer beliebig orientierten Ebene — aufgespannt durch
     * zwei orthonormale Achsen (z. B. quer zu einem Strahl).
     */
    fun orientedRing(
        world: ServerWorld,
        center: Vec3d,
        axisA: Vec3d,
        axisB: Vec3d,
        radius: Double,
        particle: ParticleEffect,
        points: Int = 12,
    ) {
        for (i in 0 until points) {
            val angle = (i.toDouble() / points) * Math.PI * 2.0
            val offset = axisA.multiply(cos(angle) * radius).add(axisB.multiply(sin(angle) * radius))
            world.spawnParticles(
                particle,
                center.x + offset.x,
                center.y + offset.y,
                center.z + offset.z,
                1, 0.0, 0.0, 0.0, 0.0
            )
        }
    }

    /** Aufsteigende Partikelspirale (2 Umdrehungen). */
    fun spiral(
        world: ServerWorld,
        center: Vec3d,
        radius: Double,
        height: Double,
        particle: ParticleEffect,
        points: Int = 30,
    ) {
        for (i in 0 until points) {
            val t = i.toDouble() / points
            val angle = t * Math.PI * 4.0
            world.spawnParticles(
                particle,
                center.x + cos(angle) * radius * (1.0 - t * 0.5),
                center.y + t * height,
                center.z + sin(angle) * radius * (1.0 - t * 0.5),
                1, 0.0, 0.0, 0.0, 0.0
            )
        }
    }
}
