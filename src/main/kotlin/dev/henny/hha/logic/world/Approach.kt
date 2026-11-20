package dev.henny.hha.logic.world

import dev.henny.hha.HhaSounds
import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.rpg.RpgWorld
import dev.henny.hha.world.TartarusArenas
import dev.henny.hha.world.TartarusKind
import dev.henny.hha.world.TartarusPiece
import dev.henny.hha.world.TartarusStructure
import dev.henny.hha.world.arena.ApproachSpec
import dev.henny.hha.world.arena.Waypoint
import dev.henny.hha.world.olymp.OlympusArenas
import dev.henny.hha.world.olymp.OlympusBridges
import dev.henny.hha.world.olymp.OlympusLayout
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Anmarsch: Fortschritt und Klang der Wege (Überarbeitung 4, PLAN 5.4, 7.9; Besitzer P06). Zonen aus
 * `OlympusArenas.APPROACHES` (fünf Brücken) und je Tartaros-Bauwerk in der Nähe eines Spielers aus
 * `TartarusArenas.palaceApproach`/`siteApproach`.
 *
 * **Fortschritt k** (0 … 1): Projektion der Spielerposition auf die Weglinie, nur innerhalb der halben Breite und
 * ±[HEIGHT_RANGE] in der Höhe. Die Weglinie ist die Kette der Wegpunkte; bei den Brücken läuft sie zusätzlich über die
 * Knicke ([route]), sonst schnitte sie die Ecke ab und der Puls bräche am Knick ab.
 *
 * **Puls** (Vorbild Bloods Herzschlag, `HollowsClient`): nächster Schlag nach `34 − 20 · k` Ticks, Lautstärke
 * `0,07 + 0,38 · k²`, Tonhöhe `0,92 + 0,08 · k`, nur an diesen Spieler und an seinem Ort (wirkt nicht positional).
 * Kein Netzpaket, keine Client-Datei.
 *
 * **Stumm**, wenn der Spieler in einem Kampf oder einer Szene ist oder der Boss des Wegs besiegt ist
 * ([OlympusProgress], [TartarosMinibosses.isFreed]); Palast: nach Hades' Sieg tickt das Uhrwerk (Kronos), nach Kronos
 * ist er stumm. Liegen zwei Wege übereinander (Insel zwischen zwei Brücken), gilt der mit dem größeren k.
 *
 * Randfälle (Stellen): Überlappung → [best]; Spieler hoch über dem Weg → [HEIGHT_RANGE] in [measure]; Boss besiegt →
 * [silenced]/[soundFor]; Kampf oder Szene → [busy].
 */
object Approach {

    /** Alle so viele Ticks wird der Fortschritt je Spieler neu bestimmt. */
    const val INTERVAL = 5

    /** Höhenfenster um die Weglinie (Blöcke). */
    const val HEIGHT_RANGE = 12.0

    /** Pulsabstand in Ticks: [PULSE_SLOW] am Anfang, [PULSE_SLOW] − [PULSE_GAIN] am Ziel. */
    const val PULSE_SLOW = 34.0
    const val PULSE_GAIN = 20.0

    /** Lautstärke `VOLUME_BASE + VOLUME_GAIN · k²`, Tonhöhe `PITCH_BASE + PITCH_GAIN · k`. */
    const val VOLUME_BASE = 0.07
    const val VOLUME_GAIN = 0.38
    const val PITCH_BASE = 0.92
    const val PITCH_GAIN = 0.08

    /** Erster Schlag nach dem Betreten eines Wegs. */
    private const val FIRST_BEAT = 10

    /** So viele Bauwerke merkt sich der Tartaros-Suchspeicher, bevor er geleert wird. */
    private const val CACHE_LIMIT = 4096

    fun pulseTicks(k: Double): Int = (PULSE_SLOW - PULSE_GAIN * k.coerceIn(0.0, 1.0)).roundToInt()
    fun volume(k: Double): Float = (VOLUME_BASE + VOLUME_GAIN * k * k).toFloat()
    fun pitch(k: Double): Float = (PITCH_BASE + PITCH_GAIN * k).toFloat()

    /** Stand eines Spielers: aktueller Weg, k, nächster Schlag, erreichte Punkte je Weg. */
    private class Walker {
        var spec: ApproachSpec? = null
        var k = 0.0
        var nextBeat = 0L
        val reached = HashMap<String, MutableSet<String>>()
    }

    private val walkers = HashMap<UUID, Walker>()

    /** Tartaros: Chunk → Bauwerksmitte (oder keine); Bauwerksmitte → Weg. */
    private val siteAt = HashMap<Long, BlockPos?>()
    private val siteSpecs = HashMap<Long, ApproachSpec>()
    private val siteKinds = HashMap<Long, TartarusKind>()

    private var clock = 0L

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register(::tick)
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> walkers.remove(handler.player.uuid) }
        ServerLifecycleEvents.SERVER_STOPPED.register { _ ->
            walkers.clear()
            siteAt.clear()
            siteSpecs.clear()
            siteKinds.clear()
            siteBoxes.clear()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Vertrag (PLAN 7.9)
    // ---------------------------------------------------------------------------------------------

    /** Fortschritt 0–1 auf dem Weg [spec], `null` = nicht auf dem Weg (außerhalb der Breite, zu hoch, andere Welt). */
    fun progress(player: ServerPlayerEntity, spec: ApproachSpec): Double? {
        if (player.entityWorld.registryKey != spec.world) return null
        return measure(route(spec), spec.halfWidth, player.entityPos)
    }

    /** Nächster noch nicht erreichter Wegpunkt (in Reihenfolge), `null` = alle erreicht. */
    fun next(player: ServerPlayerEntity, spec: ApproachSpec): Waypoint? {
        val done = reached(player, spec)
        return spec.points.firstOrNull { it.id !in done }
    }

    /** IDs der erreichten Wegpunkte (je Spieler, bis Verbindungsende). */
    fun reached(player: ServerPlayerEntity, spec: ApproachSpec): Set<String> {
        val walker = walkers[player.uuid] ?: return emptySet()
        mark(walker, player, spec)
        return walker.reached[spec.id]?.toSet() ?: emptySet()
    }

    /** Mitte des Palasts, in dessen Kasten [near] liegt, oder `null`. */
    fun palace(world: ServerWorld, near: BlockPos): BlockPos? {
        val middle = siteOf(world, near) ?: return null
        return if (siteKinds[middle.asLong()] == TartarusKind.PALACE) middle else null
    }

    // ---------------------------------------------------------------------------------------------
    // Weglinie und Projektion
    // ---------------------------------------------------------------------------------------------

    private val routes = HashMap<String, List<Vec3d>>()

    /**
     * Weglinie eines Wegs: die Wegpunkte in Reihenfolge; bei einer Olymp-Brücke dazu die Knicke der Brücke an der
     * richtigen Stelle (zwischen den Punkten, deren Zellen sie einschließen).
     */
    fun route(spec: ApproachSpec): List<Vec3d> {
        if (!spec.id.startsWith("bridge_")) return spec.points.map { it.pos }
        return routes.getOrPut(spec.id) {
            val bridge = OlympusBridges.ALL.firstOrNull { "bridge_${it.id}" == spec.id } ?: return@getOrPut spec.points.map { it.pos }
            fun cellOf(p: Vec3d): Int = bridge.cells.indices.minByOrNull { hypot(bridge.cells[it].x + 0.5 - p.x, bridge.cells[it].z + 0.5 - p.z) } ?: 0
            val out = ArrayList<Vec3d>()
            var last = -1
            for (p in spec.points) {
                val s = cellOf(p.pos)
                for (cs in bridge.cornerS) if (cs in last + 1 until s) {
                    out.add(Vec3d(bridge.cells[cs].x + 0.5, bridge.level[cs] + 1.0, bridge.cells[cs].z + 0.5))
                }
                out.add(p.pos)
                last = maxOf(last, s)
            }
            out
        }
    }

    /**
     * Fortschritt auf der Linie [line] für die Position [pos]: kleinster waagerechter Abstand zu einem Abschnitt, nur
     * wenn er ≤ [halfWidth] ist und die Höhe innerhalb ±[HEIGHT_RANGE] der Linie liegt (sonst `null`, z. B. wer hoch
     * über dem Weg fliegt). Ergebnis: zurückgelegte Länge bis zum Lot / Gesamtlänge.
     */
    fun measure(line: List<Vec3d>, halfWidth: Double, pos: Vec3d): Double? {
        if (line.size < 2) return null
        val lengths = DoubleArray(line.size - 1) { hypot(line[it + 1].x - line[it].x, line[it + 1].z - line[it].z) }
        val total = lengths.sum().takeIf { it > 0.0 } ?: return null
        var best: Double? = null
        var bestDistance = Double.MAX_VALUE
        var walked = 0.0
        for (i in lengths.indices) {
            val a = line[i]
            val b = line[i + 1]
            val dx = b.x - a.x
            val dz = b.z - a.z
            val len2 = dx * dx + dz * dz
            val t = if (len2 <= 0.0) 0.0 else (((pos.x - a.x) * dx + (pos.z - a.z) * dz) / len2).coerceIn(0.0, 1.0)
            val distance = hypot(pos.x - (a.x + dx * t), pos.z - (a.z + dz * t))
            val lineY = a.y + (b.y - a.y) * t
            if (distance <= halfWidth && abs(pos.y - lineY) <= HEIGHT_RANGE && distance < bestDistance) {
                bestDistance = distance
                best = (walked + lengths[i] * t) / total
            }
            walked += lengths[i]
        }
        return best
    }

    /** Lage jedes Wegpunkts auf der Linie (0 … 1). */
    private fun pointK(spec: ApproachSpec): Map<String, Double> {
        val line = route(spec)
        return spec.points.associate { p -> p.id to (measure(line, Double.MAX_VALUE, p.pos) ?: 0.0) }
    }

    private val pointKs = HashMap<String, Map<String, Double>>()

    /** Markiert erreichte Punkte: in Reichweite (waagerecht) oder schon überholt (k des Spielers ≥ k des Punkts). */
    private fun mark(walker: Walker, player: ServerPlayerEntity, spec: ApproachSpec, k: Double? = progress(player, spec)) {
        if (player.entityWorld.registryKey != spec.world) return
        val set = walker.reached.getOrPut(spec.id) { LinkedHashSet() }
        val ks = if (spec.id.startsWith("site_") || spec.id == "palace") pointK(spec) else pointKs.getOrPut(spec.id) { pointK(spec) }
        val pos = player.entityPos
        for (p in spec.points) {
            if (p.id in set) continue
            val near = hypot(pos.x - p.pos.x, pos.z - p.pos.z) <= p.reach && abs(pos.y - p.pos.y) <= HEIGHT_RANGE
            if (near || (k != null && k + 1e-6 >= (ks[p.id] ?: 1.0))) set.add(p.id)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Takt
    // ---------------------------------------------------------------------------------------------

    private fun tick(server: MinecraftServer) {
        clock++
        val measure = clock % INTERVAL == 0L
        for (world in server.worlds) {
            val olymp = world.registryKey == OlympusLayout.WORLD
            val tartaros = world.registryKey == HadesGate.TARTAROS
            if (!olymp && !tartaros) continue
            for (player in world.players) {
                if (player.isSpectator) continue
                val walker = walkers.getOrPut(player.uuid) { Walker() }
                if (measure) update(server, world, player, walker)
                val spec = walker.spec ?: continue
                if (clock < walker.nextBeat) continue
                val sound = soundFor(server, world, spec) ?: continue
                beat(player, sound, walker.k)
                walker.nextBeat = clock + pulseTicks(walker.k)
            }
        }
    }

    /** Bestimmt Weg und k des Spielers neu (alle [INTERVAL] Ticks). */
    private fun update(server: MinecraftServer, world: ServerWorld, player: ServerPlayerEntity, walker: Walker) {
        val candidates = specsNear(world, player)
        val (spec, k) = best(player, candidates) ?: (null to 0.0)
        for (c in candidates) mark(walker, player, c)
        val active = spec != null && !busy(world, player) && soundFor(server, world, spec) != null
        if (!active) {
            walker.spec = null
            return
        }
        if (walker.spec?.id != spec!!.id) walker.nextBeat = clock + FIRST_BEAT
        walker.spec = spec
        walker.k = k
    }

    /** Überlappung zweier Wege (Insel zwischen zwei Brücken): der mit dem größeren k gewinnt. */
    private fun best(player: ServerPlayerEntity, specs: List<ApproachSpec>): Pair<ApproachSpec, Double>? =
        specs.mapNotNull { s -> progress(player, s)?.let { s to it } }.maxByOrNull { it.second }

    /** Kampf oder Szene: kein Puls. */
    private fun busy(world: ServerWorld, player: ServerPlayerEntity): Boolean =
        Cine.isPlaying(player) || Arenas.of(player) != null || Arenas.of(world, player.entityPos) != null

    private fun specsNear(world: ServerWorld, player: ServerPlayerEntity): List<ApproachSpec> {
        if (world.registryKey == OlympusLayout.WORLD) return OlympusArenas.APPROACHES
        val middle = siteOf(world, player.blockPos) ?: return emptyList()
        return listOfNotNull(siteSpecs[middle.asLong()])
    }

    /** Tartaros-Bauwerk am Ort (über `structureAccessor`), Ergebnis je Chunk und je Bauwerk gemerkt. */
    private fun siteOf(world: ServerWorld, pos: BlockPos): BlockPos? {
        if (world.registryKey != HadesGate.TARTAROS) return null
        val key = ChunkPos.toLong(pos.x shr 4, pos.z shr 4)
        if (siteAt.size > CACHE_LIMIT) siteAt.clear()
        if (!siteAt.containsKey(key)) {
            val start = world.structureAccessor.getStructureContaining(pos) { it.value() is TartarusStructure }
            val piece = if (start.hasChildren()) start.children.firstOrNull() as? TartarusPiece else null
            siteAt[key] = piece?.middle
            if (piece != null) {
                val id = piece.middle.asLong()
                siteKinds[id] = piece.kind
                siteBoxes[id] = start.boundingBox
                siteSpecs.getOrPut(id) { TartarusArenas.siteApproach(piece.kind, piece.siteVariant(), piece.middle) }
            }
        }
        val middle = siteAt[key] ?: return null
        // Der Chunk kann am Rand des Bauwerks liegen: nur wer wirklich im Kasten steht, zählt.
        return middle.takeIf { siteBoxes[it.asLong()]?.contains(pos) != false }
    }

    private val siteBoxes = HashMap<Long, net.minecraft.util.math.BlockBox>()

    /** Klang des Wegs, `null` = stumm (Boss besiegt). Palast: Herz, nach Hades das Uhrwerk, nach Kronos nichts. */
    fun soundFor(server: MinecraftServer, world: ServerWorld, spec: ApproachSpec): SoundEvent? {
        if (silenced(server, world, spec)) return null
        if (spec.id == "palace" && RpgWorld.of(server).isDefeated(TartarosChain.HADES)) return HhaSounds.APPROACH_TICKING
        return spec.sound()
    }

    /** Ist der Boss dieses Wegs besiegt? */
    private fun silenced(server: MinecraftServer, world: ServerWorld, spec: ApproachSpec): Boolean = when {
        spec.world == OlympusLayout.WORLD -> OlympusProgress.isDone(server, spec.bossKey)
        spec.id == "palace" -> TartarosChain.of(server).hadesDone || RpgWorld.of(server).isDefeated(KRONOS)
        else -> siteSpecs.entries.firstOrNull { it.value === spec }?.let { TartarosMinibosses.isFreed(world, BlockPos.fromLong(it.key)) } ?: false
    }

    private const val KRONOS = "kronos"

    /** Ein Schlag nur für diesen Spieler, an seinem Ort (also ohne Richtung). */
    private fun beat(player: ServerPlayerEntity, sound: SoundEvent, k: Double) {
        val pos = player.entityPos
        player.networkHandler.sendPacket(
            PlaySoundS2CPacket(Registries.SOUND_EVENT.getEntry(sound), SoundCategory.AMBIENT, pos.x, pos.y, pos.z, volume(k), pitch(k), player.random.nextLong()),
        )
    }

    /** Für `/hha approach` und Tests: der Weg, auf dem der Spieler gerade gehört wird, mit k. */
    fun current(player: ServerPlayerEntity): Pair<String, Double>? = walkers[player.uuid]?.let { w -> w.spec?.let { it.id to w.k } }
}
