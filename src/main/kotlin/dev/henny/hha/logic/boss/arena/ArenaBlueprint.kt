package dev.henny.hha.logic.boss.arena

import dev.henny.hha.Hha
import dev.henny.hha.logic.world.RecordingCanvas
import dev.henny.hha.world.BlockCanvas
import dev.henny.hha.world.arena.ArenaSpec
import dev.henny.hha.world.olymp.OlympusStructures
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import java.util.BitSet

/*
 * Überarbeitung 4, PLAN 7.3 / Leitlinie 21 („Der Bauplan gilt“), Besitzer P02.
 *
 * Aufbau dieser Datei (je Bauteil eine Stelle):
 * - [ArenaBlueprint]: ID, Geometrie, Zeichenfunktion, [ArenaBlueprint.airIsDesign].
 * - [Design]: die Aufnahme (Zelle → Zustand), `at` liefert `null`, wo der Plan schweigt.
 * - [BlockAccess] mit [WorldAccess] (echte Welt) und [MapAccess] (Prüflauf): die kleine Welt-Schnittstelle `get/set`.
 * - [BlueprintLogic]: Vergleich und Rücksetzen als reine Funktionen über [BlockAccess] – dieselben Funktionen laufen im
 *   Spiel und im Selbsttest des Prüflaufs.
 * - [ArenaBlueprints]: Verzeichnis, Aufnahme mit Zwischenspeicher (LRU 6), Reparatur (sofort oder als Tick-Job in
 *   Scheiben von [ArenaBlueprints.CELLS_PER_TICK] Zellen), Stichproben, Selbsttest.
 * - [ArenaRescue]: Spieler, die in einem entstehenden Block stecken, an die nächste freie Stelle darüber setzen.
 */

/**
 * Ein Arena-Bauplan (PLAN 7.3): [spec] liefert die Geometrie zur Bauwerksmitte bzw. Forumsmitte `origin`, [draw]
 * zeichnet den Plan auf eine Leinwand (Olymp: die ganze Insel, zugeschnitten auf `spec.box`; Tartaros: `arenaCore`
 * der Plan-Datei). Der Bauplan ist die einzige Quelle für Reparatur und Rückbau.
 *
 * [airIsDesign]: Zellen im Kasten, die der Plan nicht setzt, sind Luft (Olymp: der Himmel gehört zum Plan) – sonst
 * „Plan schweigt“ (Tartaros-Kerne: Gelände und Fassade bleiben, wie sie sind). Ohne Angabe gilt [airIsDesignFor].
 */
class ArenaBlueprint(
    val id: String,
    val spec: (origin: BlockPos) -> ArenaSpec,
    val airIsDesign: Boolean,
    val draw: (BlockCanvas, origin: BlockPos) -> Unit,
) {
    /** Quellkompatibel zum Vertrag aus Welle 0: `ArenaBlueprint(id, spec) { c, o -> … }`. */
    constructor(id: String, spec: (origin: BlockPos) -> ArenaSpec, draw: (BlockCanvas, origin: BlockPos) -> Unit) :
        this(id, spec, airIsDesignFor(id), draw)

    companion object {
        /** Olymp-Pläne (`forum`, `olymp_*`) nehmen Luft mit auf, Tartaros-Kerne nicht. */
        fun airIsDesignFor(id: String): Boolean = id == "forum" || id.startsWith("olymp_")
    }
}

/**
 * Aufnahme eines Bauplans im Kasten [box]: Zelle → Zustand laut Plan (gesetzte Luft eingeschlossen).
 *
 * [at] liefert `null`, wo der Plan schweigt (dort bleibt die Welt, wie sie ist). Bei Plänen mit `airIsDesign` gilt eine
 * nicht gesetzte Zelle in einer **Luftspalte** als Luft; welche Spalten das sind, sagt [airColumn] (Olymp: die Spalten,
 * die der Insel gehören – Brückenspalten am Kastenrand bleiben unberührt). [cells] liefert nur die aufgenommenen Zellen.
 */
class Design(
    val box: BlockBox,
    table: Map<Long, BlockState> = emptyMap(),
    airColumn: ((x: Int, z: Int) -> Boolean)? = null,
) {
    private val table = Long2ObjectOpenHashMap<BlockState>(table.size.coerceAtLeast(16))
    private val sizeX = box.maxX - box.minX + 1
    private val sizeZ = box.maxZ - box.minZ + 1

    /** Luftspalten als Bitfeld (einmal ausgewertet, nie je Zelle neu gerechnet). */
    private val airColumns: BitSet? = airColumn?.let { test ->
        val bits = BitSet(sizeX * sizeZ)
        for (x in box.minX..box.maxX) for (z in box.minZ..box.maxZ) if (test(x, z)) bits.set(columnIndex(x, z))
        bits
    }

    init {
        for ((k, v) in table) this.table.put(k, v)
    }

    /** Nimmt der Plan nicht gesetzte Zellen als Luft (irgendwo im Kasten)? */
    val airIsDesign: Boolean get() = airColumns != null

    private fun columnIndex(x: Int, z: Int): Int = (x - box.minX) * sizeZ + (z - box.minZ)

    /** Ist (x, z) eine Luftspalte? */
    fun isAirColumn(x: Int, z: Int): Boolean =
        airColumns != null && x in box.minX..box.maxX && z in box.minZ..box.maxZ && airColumns.get(columnIndex(x, z))

    fun at(pos: BlockPos): BlockState? = at(pos.x, pos.y, pos.z)

    fun at(x: Int, y: Int, z: Int): BlockState? {
        if (!box.contains(x, y, z)) return null
        return table.get(BlockPos.asLong(x, y, z)) ?: if (isAirColumn(x, z)) AIR else null
    }

    fun inBox(pos: BlockPos): Boolean = box.contains(pos)

    fun cells(): Sequence<Pair<BlockPos, BlockState>> = table.long2ObjectEntrySet().asSequence().map { BlockPos.fromLong(it.longKey) to it.value }

    /** Anzahl der aufgenommenen Zellen. */
    val size: Int get() = table.size

    /** Zellen, die eine Reparatur prüfen muss: ganzer Kasten bei `airIsDesign`, sonst nur die aufgenommenen. */
    val repairCells: Long get() = if (airIsDesign) box.blockCountX.toLong() * box.blockCountY * box.blockCountZ else table.size.toLong()

    /** Aufgenommene Positionen, nach Spalte sortiert (für Stichproben und die Reparatur ohne Luft). */
    val keys: LongArray by lazy {
        val array = table.keys.toLongArray()
        // Nach Chunk, dann Spalte, dann Höhe: eine Scheibe berührt wenige Chunks.
        array.sortedWith(compareBy<Long>({ BlockPos.unpackLongX(it) shr 4 }, { BlockPos.unpackLongZ(it) shr 4 }, { BlockPos.unpackLongX(it) }, { BlockPos.unpackLongZ(it) }, { BlockPos.unpackLongY(it) })).toLongArray()
    }

    /** Zelle Nummer [i] der Reparatur-Reihenfolge (0 ≤ i < [repairCells]). */
    internal fun repairCell(i: Long): Long {
        if (!airIsDesign) return keys[i.toInt()]
        val sizeY = box.blockCountY
        val column = (i / sizeY).toInt()
        val y = box.minY + (i % sizeY).toInt()
        // Spalte für Spalte (x, dann z), in der Spalte von unten nach oben.
        val x = box.minX + column / sizeZ
        val z = box.minZ + column % sizeZ
        return BlockPos.asLong(x, y, z)
    }

    companion object {
        private val AIR: BlockState = Blocks.AIR.defaultState
    }
}

/** Die kleine Welt-Schnittstelle (PLAN 7.3, Briefing P02 Schritt 5): im Spiel die [ServerWorld], im Prüflauf eine Map. */
interface BlockAccess {
    fun get(x: Int, y: Int, z: Int): BlockState
    fun set(x: Int, y: Int, z: Int, state: BlockState)

    /** Ist die Spalte bereit (Chunk voll geladen)? Eine Map ist immer bereit. */
    fun ready(x: Int, z: Int): Boolean = true
}

/** Echte Welt: setzt ohne Drops und ohne Nachbar-Formänderung; „bereit“ = Chunk voll geladen (`getWorldChunk`). */
class WorldAccess(val world: ServerWorld) : BlockAccess {
    private val pos = BlockPos.Mutable()
    private var lastChunk = Long.MIN_VALUE
    private var lastReady = false

    override fun get(x: Int, y: Int, z: Int): BlockState = world.getBlockState(pos.set(x, y, z))

    override fun set(x: Int, y: Int, z: Int, state: BlockState) {
        if (y < world.bottomY || y > world.topYInclusive) return
        world.setBlockState(pos.set(x, y, z), state, ArenaBlueprints.SET_FLAGS)
    }

    override fun ready(x: Int, z: Int): Boolean {
        val chunk = ChunkPos.toLong(x shr 4, z shr 4)
        if (chunk != lastChunk) {
            lastChunk = chunk
            lastReady = chunkReady(world, x shr 4, z shr 4)
        }
        return lastReady
    }

    companion object {
        /** Chunk voll erzeugt und geladen (`isChunkLoaded` ist schon während der Erzeugung wahr; Vorbild Blood `ready`). */
        fun chunkReady(world: ServerWorld, cx: Int, cz: Int): Boolean = world.chunkManager.getWorldChunk(cx, cz) != null
    }
}

/** Welt im Speicher für den Prüflauf: fehlende Zellen sind [fallback] (Luft). */
class MapAccess(val cells: MutableMap<Long, BlockState>, private val fallback: BlockState = Blocks.AIR.defaultState) : BlockAccess {
    override fun get(x: Int, y: Int, z: Int): BlockState = cells[BlockPos.asLong(x, y, z)] ?: fallback
    override fun set(x: Int, y: Int, z: Int, state: BlockState) {
        cells[BlockPos.asLong(x, y, z)] = state
    }
}

/**
 * Vergleich und Rücksetzen als reine Funktionen (Briefing P02 Schritt 5). Keine versteckten Zustände: alles, was eine
 * Funktion braucht, kommt als Parameter.
 */
object BlueprintLogic {

    /**
     * Was eine Reparatur an (x, y, z) setzen soll, `null` = nichts tun. Regeln:
     * - wo der Plan schweigt, bleibt die Welt;
     * - Blöcke mit BlockEntity setzt die Reparatur nie (keine neuen Truhen, keine Spawner – Dupe- und Spawner-Gefahr);
     * - eine BlockEntity in der Welt, die der Plan dort nicht kennt (anderer Block), bleibt stehen;
     * - gleiche Zustände werden nicht neu gesetzt.
     */
    fun repairTarget(want: BlockState?, current: BlockState, currentHasEntity: Boolean): BlockState? {
        if (want == null || want.hasBlockEntity()) return null
        if (current == want) return null
        if (currentHasEntity && !current.isOf(want.block)) return null
        return want
    }

    /** Eine Zelle reparieren; `true`, wenn gesetzt. */
    fun repairCell(design: Design, access: BlockAccess, x: Int, y: Int, z: Int): Boolean {
        val want = design.at(x, y, z) ?: return false
        val current = access.get(x, y, z)
        val target = repairTarget(want, current, current.hasBlockEntity()) ?: return false
        access.set(x, y, z, target)
        return true
    }

    /**
     * Repariert die Zellen [from] … [until] − 1 der Reparatur-Reihenfolge (`Design.repairCell`). Zellen in Chunks, die
     * nicht bereit sind, landen in [deferred]. Liefert die Zahl gesetzter Blöcke.
     */
    fun repairRange(design: Design, access: BlockAccess, from: Long, until: Long, deferred: LongArrayList?): Int {
        var set = 0
        var i = from
        while (i < until) {
            val k = design.repairCell(i++)
            val x = BlockPos.unpackLongX(k)
            val z = BlockPos.unpackLongZ(k)
            if (!access.ready(x, z)) {
                deferred?.add(k)
                continue
            }
            if (repairCell(design, access, x, BlockPos.unpackLongY(k), z)) set++
        }
        return set
    }

    /** Ganze Reparatur auf einmal (Prüflauf, kleine Arenen). */
    fun repairAll(design: Design, access: BlockAccess, deferred: LongArrayList? = null): Int = repairRange(design, access, 0, design.repairCells, deferred)

    /**
     * Ziel beim Rücksetzen einer Edit-Stelle: der Plan, wo er spricht (aber nie ein Block mit BlockEntity), sonst der
     * gemerkte Urzustand.
     */
    fun restoreTarget(design: Design?, x: Int, y: Int, z: Int, saved: BlockState): BlockState {
        val want = design?.at(x, y, z)
        return if (want == null || want.hasBlockEntity()) saved else want
    }

    /**
     * Setzt alle Stellen aus [log] (gepackte Position → Urzustand) zurück, auf die [filter] passt und deren Chunk bereit
     * ist. Erledigte Stellen werden aus [log] entfernt und in der Rückgabe gesammelt.
     */
    fun restore(log: MutableMap<Long, BlockState>, design: Design?, access: BlockAccess, filter: (Long) -> Boolean = { true }): LongArrayList {
        val done = LongArrayList()
        val iterator = log.entries.iterator()
        while (iterator.hasNext()) {
            val (k, saved) = iterator.next()
            if (!filter(k)) continue
            val x = BlockPos.unpackLongX(k)
            val y = BlockPos.unpackLongY(k)
            val z = BlockPos.unpackLongZ(k)
            if (!access.ready(x, z)) continue
            val target = restoreTarget(design, x, y, z, saved)
            if (access.get(x, y, z) != target) access.set(x, y, z, target)
            iterator.remove()
            done.add(k)
        }
        return done
    }

    /** Merkt den Urzustand (nur beim ersten Mal) und setzt den Block. `true`, wenn die Stelle neu im Register ist. */
    fun edit(log: MutableMap<Long, BlockState>, access: BlockAccess, x: Int, y: Int, z: Int, state: BlockState): Boolean {
        val k = BlockPos.asLong(x, y, z)
        val fresh = !log.containsKey(k)
        if (fresh) log[k] = access.get(x, y, z)
        access.set(x, y, z, state)
        return fresh
    }

    /** Stimmt die Welt in jeder Zelle, zu der der Plan spricht, mit dem Plan überein? Liefert die erste Abweichung. */
    fun firstMismatch(design: Design, access: BlockAccess): Long? {
        var i = 0L
        val n = design.repairCells
        while (i < n) {
            val k = design.repairCell(i++)
            val x = BlockPos.unpackLongX(k)
            val y = BlockPos.unpackLongY(k)
            val z = BlockPos.unpackLongZ(k)
            val want = design.at(x, y, z) ?: continue
            if (want.hasBlockEntity()) continue
            if (access.get(x, y, z) != want) return k
        }
        return null
    }
}

/** Spieler, die in einem entstehenden Block stecken, an die nächste freie Stelle darüber setzen. Nie einmauern. */
object ArenaRescue {

    /** So weit sucht [lift] nach oben. */
    const val SEARCH_UP = 64

    /**
     * Prüft alle Spieler in [world], deren Füße im Kasten [box] (um 1 erweitert) stehen. Wer festsitzt, kommt an die
     * nächste freie Stelle darüber, mit Hinweis [message] im Chat. Liefert die Zahl der umgesetzten Spieler.
     */
    fun lift(world: ServerWorld, box: BlockBox, message: Text? = Text.translatable("hha.arena.retrofit.lifted").formatted(Formatting.GOLD)): Int {
        var moved = 0
        for (player in world.players.toList()) {
            if (player.isSpectator) continue
            val p = player.blockPos
            if (p.x < box.minX - 1 || p.x > box.maxX + 1 || p.z < box.minZ - 1 || p.z > box.maxZ + 1 || p.y < box.minY - 2 || p.y > box.maxY + 1) continue
            if (liftOne(world, player, message)) moved++
        }
        return moved
    }

    /** Ein Spieler: steckt er fest, wird er nach oben gesetzt. */
    fun liftOne(world: ServerWorld, player: ServerPlayerEntity, message: Text?): Boolean {
        if (world.isSpaceEmpty(player)) return false
        val base = player.boundingBox
        val startY = kotlin.math.floor(player.y)
        for (dy in 1..SEARCH_UP) {
            val y = startY + dy
            val shifted = base.offset(0.0, y - player.y, 0.0)
            if (!world.isSpaceEmpty(player, shifted)) continue
            player.requestTeleport(player.x, y, player.z)
            player.fallDistance = 0.0
            if (message != null) player.sendMessage(message, false)
            return true
        }
        return false
    }
}

/** Ergebnis des Bauplan-Selbsttests (PLAN 7.11). */
class BlueprintSelfTest(
    val id: String,
    val cells: Int,
    val changed: Int,
    val viaEdits: Int,
    val repaired: Int,
    val ok: Boolean,
    val message: String,
)

object ArenaBlueprints {

    /** Setz-Flags für Reparatur, Edits und Nachrüstung: Klienten benachrichtigen, Zustand erzwingen, keine Drops. */
    const val SET_FLAGS = Block.NOTIFY_LISTENERS or Block.FORCE_STATE or Block.SKIP_DROPS

    /** Reparatur im Hintergrund: so viele Zellen je Tick. */
    const val CELLS_PER_TICK = 4000

    /** Zwischenspeicher der Aufnahmen: höchstens so viele (ID, Ursprung). */
    const val CACHE_SIZE = 6

    /** Stichproben für [matches]. */
    const val SAMPLES = 400

    private val blueprints = LinkedHashMap<String, ArenaBlueprint>()

    /** LRU: zuletzt benutzte Aufnahme bleibt, die älteste fällt bei mehr als [CACHE_SIZE] heraus. */
    private val designs = object : LinkedHashMap<Pair<String, Long>, Design>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Long>, Design>?): Boolean = size > CACHE_SIZE
    }

    fun register(blueprint: ArenaBlueprint) {
        blueprints[blueprint.id] = blueprint
        designs.keys.removeIf { it.first == blueprint.id }
    }

    fun get(id: String): ArenaBlueprint? = blueprints[id]

    /** Alle registrierten IDs (Reihenfolge der Registrierung). */
    fun ids(): List<String> = blueprints.keys.toList()

    // ---------------------------------------------------------------------------------------------------------------
    // Aufnahme
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Ursprung, unter dem eine Arena geführt wird (Zwischenspeicher, Edit-Register): Olymp-Pläne `olymp_*` liegen fest und
     * ignorieren den Ursprung – dort gilt immer die Arenamitte der Spec, damit `BlockPos.ORIGIN` und `spec.center`
     * dieselbe Arena meinen. Sonst der Ursprung selbst. (additiv)
     */
    fun canonicalOrigin(id: String, origin: BlockPos): BlockPos {
        if (!id.startsWith("olymp_")) return origin
        return blueprints[id]?.spec?.invoke(origin)?.center ?: origin
    }

    /**
     * Aufnahme-Leinwand: zeichnet den Plan einmal in eine Tabelle (zwischengespeichert je Plan und Ursprung, LRU 6).
     * Nur Zellen im Kasten der Spec zählen. Bei `airIsDesign` sind die Luftspalten die Spalten, die der Insel unter der
     * Arenamitte gehören (`OlympusStructures.claim`); liegt die Mitte auf keiner Insel, der ganze Kasten.
     */
    fun design(id: String, origin: BlockPos): Design {
        val key = id to canonicalOrigin(id, origin).asLong()
        designs[key]?.let { return it }
        val blueprint = blueprints[id] ?: error("Unbekannter Arena-Bauplan: $id")
        val started = System.nanoTime()
        val spec = blueprint.spec(origin)
        val canvas = RecordingCanvas(spec.box, spec.coreY - 1)
        blueprint.draw(canvas, origin)
        val table = HashMap<Long, BlockState>(canvas.cells.size * 2)
        for ((k, state) in canvas.cells) if (spec.box.contains(BlockPos.unpackLongX(k), BlockPos.unpackLongY(k), BlockPos.unpackLongZ(k))) table[k] = state
        val airColumn: ((Int, Int) -> Boolean)? = if (!blueprint.airIsDesign) null else {
            val isle = OlympusStructures.claim(spec.center.x, spec.center.z)
            if (isle == null) { _, _ -> true } else { x, z -> OlympusStructures.claim(x, z) === isle }
        }
        val design = Design(spec.box, table, airColumn)
        designs[key] = design
        Hha.LOGGER.debug("Bauplan {}: {} Zellen aufgenommen in {} ms", id, design.size, (System.nanoTime() - started) / 1_000_000)
        return design
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Reparatur
    // ---------------------------------------------------------------------------------------------------------------

    /** Ein laufender Reparatur-Auftrag (Hintergrund, [CELLS_PER_TICK] Zellen je Tick). */
    private class RepairJob(val world: ServerWorld, val id: String, val origin: BlockPos, val design: Design, val onDone: ((Int) -> Unit)?) {
        var cursor = 0L
        var set = 0
        val deferred = LongArrayList()
        var reportedWaiting = false
        val key: Triple<ServerWorld, String, Long> get() = Triple(world, id, origin.asLong())
    }

    private val jobs = ArrayList<RepairJob>()

    /**
     * Vergleicht Plan und Welt im Kasten und setzt Abweichungen (ohne Drops; Regeln in [BlueprintLogic.repairTarget]).
     *
     * - [instant] = `true`: sofort (Kampfbeginn kleiner Arenen, Befehl); Rückgabe = gesetzte Blöcke. Zellen in Chunks,
     *   die nicht voll geladen sind, gehen als Hintergrund-Auftrag weiter und werden im Log gemeldet.
     * - [instant] = `false`: Hintergrund-Auftrag in Scheiben von [CELLS_PER_TICK] Zellen je Tick; Rückgabe 0, die Zahl
     *   meldet [onDone] (additiv zum Vertrag). Ein laufender Auftrag für dieselbe Arena wird ersetzt.
     *
     * Wer in einem entstehenden Block steckt, wird nach oben gesetzt ([ArenaRescue]).
     */
    fun repair(world: ServerWorld, id: String, origin: BlockPos, instant: Boolean = false, onDone: ((Int) -> Unit)? = null): Int {
        if (blueprints[id] == null) return 0
        val home = canonicalOrigin(id, origin)
        val design = design(id, home)
        jobs.removeIf { it.world === world && it.id == id && it.origin == home }
        if (!instant) {
            jobs.add(RepairJob(world, id, home, design, onDone))
            return 0
        }
        val access = WorldAccess(world)
        val deferred = LongArrayList()
        val set = BlueprintLogic.repairAll(design, access, deferred)
        ArenaRescue.lift(world, design.box)
        if (deferred.isEmpty) {
            onDone?.invoke(set)
        } else {
            Hha.LOGGER.warn("Bauplan {} an {}: {} Zellen in nicht geladenen Chunks – Reparatur läuft weiter, sobald sie geladen sind", id, home.toShortString(), deferred.size)
            val job = RepairJob(world, id, home, design, onDone)
            job.cursor = design.repairCells
            job.set = set
            job.deferred.addAll(deferred)
            jobs.add(job)
        }
        return set
    }

    /** Läuft für diese Arena noch eine Reparatur im Hintergrund? (additiv) */
    fun isRepairing(world: ServerWorld, id: String, origin: BlockPos): Boolean {
        val home = canonicalOrigin(id, origin)
        return jobs.any { it.world === world && it.id == id && it.origin == home }
    }

    /** Jeden Server-Tick (aufgerufen aus `WorldRetrofit.init`): eine Scheibe je laufendem Auftrag. */
    internal fun tick(server: MinecraftServer) {
        if (jobs.isEmpty()) return
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val job = iterator.next()
            if (job.world.server !== server) {
                iterator.remove()
                continue
            }
            if (step(job)) {
                iterator.remove()
                Hha.LOGGER.info("Bauplan {} an {}: Reparatur fertig, {} Blöcke gesetzt", job.id, job.origin.toShortString(), job.set)
                job.onDone?.invoke(job.set)
            }
        }
    }

    /** Eine Scheibe; `true`, wenn der Auftrag fertig ist. */
    private fun step(job: RepairJob): Boolean {
        val access = WorldAccess(job.world)
        val total = job.design.repairCells
        var budget = CELLS_PER_TICK.toLong()
        if (job.cursor < total) {
            val until = minOf(total, job.cursor + budget)
            job.set += BlueprintLogic.repairRange(job.design, access, job.cursor, until, job.deferred)
            budget -= until - job.cursor
            job.cursor = until
        }
        if (job.cursor >= total && !job.deferred.isEmpty && budget > 0) {
            // Zurückgestellte Zellen: nur die, deren Chunk jetzt bereit ist; der Rest bleibt liegen.
            val still = LongArrayList()
            var i = 0
            while (i < job.deferred.size) {
                val k = job.deferred.getLong(i++)
                val x = BlockPos.unpackLongX(k)
                val z = BlockPos.unpackLongZ(k)
                if (budget <= 0 || !access.ready(x, z)) {
                    still.add(k)
                    continue
                }
                budget--
                if (BlueprintLogic.repairCell(job.design, access, x, BlockPos.unpackLongY(k), z)) job.set++
            }
            job.deferred.clear()
            job.deferred.addAll(still)
            if (!still.isEmpty && !job.reportedWaiting) {
                job.reportedWaiting = true
                Hha.LOGGER.warn("Bauplan {} an {}: wartet auf {} Zellen in nicht geladenen Chunks", job.id, job.origin.toShortString(), still.size)
            }
        }
        ArenaRescue.lift(job.world, job.design.box)
        return job.cursor >= total && job.deferred.isEmpty
    }

    /** Server endet: Aufträge verwerfen (beim nächsten Kampfbeginn repariert die Arena erneut). */
    internal fun reset() {
        jobs.clear()
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Stichproben
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Anteil (0–1) der Stichproben, die dem Plan entsprechen (gleicher Block): [samples] feste Stichproben aus den
     * aufgenommenen Zellen (Seed aus ID und Ursprung) plus alle Deckungs- und Geräte-Zellen der Spec. Zellen in nicht
     * geladenen Chunks und Blöcke mit BlockEntity zählen nicht. Ohne prüfbare Zelle: 1.
     */
    fun matches(world: ServerWorld, id: String, origin: BlockPos, samples: Int = SAMPLES): Double {
        val blueprint = blueprints[id] ?: return 1.0
        val design = design(id, origin)
        val cells = sampleCells(design, blueprint.spec(origin), id, origin, samples)
        val access = WorldAccess(world)
        var total = 0
        var same = 0
        for (k in cells) {
            val x = BlockPos.unpackLongX(k)
            val y = BlockPos.unpackLongY(k)
            val z = BlockPos.unpackLongZ(k)
            val want = design.at(x, y, z) ?: continue
            if (want.hasBlockEntity() || !access.ready(x, z)) continue
            total++
            if (access.get(x, y, z).isOf(want.block)) same++
        }
        return if (total == 0) 1.0 else same.toDouble() / total
    }

    /** Die festen Stichproben-Zellen: Seed aus ID und Ursprung, dazu Deckung und Geräte. */
    fun sampleCells(design: Design, spec: ArenaSpec, id: String, origin: BlockPos, samples: Int): LongArrayList {
        val out = LongArrayList()
        val keys = design.keys
        if (keys.isNotEmpty()) {
            val random = java.util.Random(id.hashCode().toLong() * 31L xor origin.asLong())
            repeat(samples) { out.add(keys[random.nextInt(keys.size)]) }
        }
        for (cover in spec.cover) {
            val b = cover.box
            for (x in b.minX..b.maxX) for (y in b.minY..b.maxY) for (z in b.minZ..b.maxZ) out.add(BlockPos.asLong(x, y, z))
        }
        for (device in spec.devices) out.add(device.pos.asLong())
        return out
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Selbsttest (PLAN 7.11)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Bauplan-Selbsttest ohne Welt: Aufnahme → Welt als Map aus der Aufnahme → [changes] Zellen verändern (ein Fünftel
     * davon über das Edit-Register [BlueprintLogic.edit], bei `airIsDesign` zusätzlich Zellen in Luftspalten außerhalb
     * der Aufnahme) → Edits zurücksetzen ([BlueprintLogic.restore]) → Rest reparieren ([BlueprintLogic.repairAll]) →
     * jede Zelle, zu der der Plan spricht, muss wieder stimmen. Dieselben Funktionen laufen im Spiel.
     */
    fun selfTest(id: String, origin: BlockPos, changes: Int = 500, seed: Long = 4242L): BlueprintSelfTest {
        val design = design(id, origin)
        if (design.size == 0) return BlueprintSelfTest(id, 0, 0, 0, 0, true, "leer (Arena-Kern noch ohne Inhalt), übersprungen")
        val world = HashMap<Long, BlockState>(design.size * 2)
        for ((pos, state) in design.cells()) world[pos.asLong()] = state
        val access = MapAccess(world)
        val keys = design.keys
        val random = java.util.Random(seed)
        val stone = Blocks.STONE.defaultState
        val air = Blocks.AIR.defaultState
        val log = LinkedHashMap<Long, BlockState>()
        var viaEdits = 0
        repeat(changes) { n ->
            // Zielzelle: meist aus der Aufnahme, bei Luft-Plänen jede zehnte irgendwo in einer Luftspalte.
            val k = if (design.airIsDesign && n % 10 == 9) {
                val b = design.box
                val x = b.minX + random.nextInt(b.blockCountX)
                val y = b.minY + random.nextInt(b.blockCountY)
                val z = b.minZ + random.nextInt(b.blockCountZ)
                BlockPos.asLong(x, y, z)
            } else keys[random.nextInt(keys.size)]
            val x = BlockPos.unpackLongX(k)
            val y = BlockPos.unpackLongY(k)
            val z = BlockPos.unpackLongZ(k)
            val next = if (access.get(x, y, z).isOf(Blocks.STONE)) air else stone
            if (n % 5 == 0) {
                BlueprintLogic.edit(log, access, x, y, z, next)
                viaEdits++
            } else access.set(x, y, z, next)
        }
        BlueprintLogic.restore(log, design, access)
        if (log.isNotEmpty()) return BlueprintSelfTest(id, design.size, changes, viaEdits, 0, false, "Edit-Register nicht geleert (${log.size} Stellen)")
        val repaired = BlueprintLogic.repairAll(design, access)
        val mismatch = BlueprintLogic.firstMismatch(design, access)
        val ok = mismatch == null
        val message = if (ok) "bestanden" else "Zelle ${BlockPos.fromLong(mismatch!!).toShortString()} weicht nach dem Zurücksetzen ab"
        return BlueprintSelfTest(id, design.size, changes, viaEdits, repaired, ok, message)
    }
}

/**
 * Bauplan-Selbsttest im Prüflauf (nur mit `-Dhha.verifyWorldgen`, aus `WorldRetrofit.init`): läuft vor
 * `WorldgenVerify` und benutzt die echte [Design]-Klasse und die Speicher-Variante der Wiederherstellung
 * ([ArenaBlueprints.selfTest]). Schreibt je Bauplan `plan_design_<id>.txt` (alles, was die Reparatur setzen würde, ohne
 * Luft) und `blueprint_report.txt` (Zellen, Prüfzellen, Dauer in Ticks bei [ArenaBlueprints.CELLS_PER_TICK]).
 * Scheitert ein Test, bricht der Serverstart ab (der Prüflauf wird rot). `-Dhha.verify.only=<id>` gilt auch hier.
 */
internal object BlueprintVerify {

    /** Prüfmitten wie in `WorldgenVerify` (API §0): Forum, Olymp fest, Tartaros (0, 72, 0), Ixion z 32, Tantalos z 16. */
    fun testOrigin(id: String): BlockPos = when (id) {
        "forum" -> BlockPos(0, 208, -130)
        "ixion_wheel" -> BlockPos(0, 72, 32)
        "tantalus_basin" -> BlockPos(0, 72, 16)
        else -> if (id.startsWith("olymp_")) BlockPos.ORIGIN else BlockPos(0, 72, 0)
    }

    fun run() {
        val only = System.getProperty("hha.verify.only")?.takeIf { it.isNotBlank() && it != "true" }
        val report = StringBuilder("id | Zellen aufgenommen | Prüfzellen | Ticks bei ${ArenaBlueprints.CELLS_PER_TICK} | Aufnahme ms | Luft = Plan\n")
        val failed = ArrayList<String>()
        for (id in ArenaBlueprints.ids()) {
            if (only != null && id != only) continue
            val origin = testOrigin(id)
            val started = System.nanoTime()
            val design = ArenaBlueprints.design(id, origin)
            val ms = (System.nanoTime() - started) / 1_000_000
            val result = ArenaBlueprints.selfTest(id, origin)
            println("HHA-VERIFY Bauplan-Logik $id: ${result.cells} Zellen, ${result.changed} verändert (${result.viaEdits} über Edits), ${result.repaired} repariert – ${result.message}")
            if (!result.ok) failed.add("$id: ${result.message}")
            val ticks = (design.repairCells + ArenaBlueprints.CELLS_PER_TICK - 1) / ArenaBlueprints.CELLS_PER_TICK
            report.append("$id | ${design.size} | ${design.repairCells} | $ticks | $ms | ${design.airIsDesign}\n")
            java.io.File("plan_design_$id.txt").bufferedWriter().use { out ->
                for ((pos, state) in design.cells()) {
                    if (state.isAir) continue
                    out.write("${pos.x} ${pos.y} ${pos.z} ${net.minecraft.registry.Registries.BLOCK.getId(state.block).path}")
                    out.newLine()
                }
            }
        }
        java.io.File("blueprint_report.txt").writeText(report.toString())
        print(report.lineSequence().filter { it.isNotBlank() }.joinToString("") { "HHA-VERIFY   $it\n" })
        check(failed.isEmpty()) { "Bauplan-Selbsttest gescheitert: $failed" }
    }
}
