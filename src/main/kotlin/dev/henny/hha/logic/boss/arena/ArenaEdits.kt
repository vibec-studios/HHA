package dev.henny.hha.logic.boss.arena

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.henny.hha.Hha
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.datafixer.DataFixTypes
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateType

/**
 * Edit-Register einer Arena (PLAN 7.3, Leitlinie 21; Besitzer P02): Jede Blockänderung im Kampf läuft hierüber.
 *
 * - [set] merkt die Stelle samt Urzustand im `PersistentState` `hha_arena_edits` (an der Oberwelt; Eintrag je Welt,
 *   Bauplan und Ursprung) und setzt den Block (ohne Drops, ohne Nachbar-Formänderung).
 * - [restore] setzt je Stelle zurück auf den Bauplan (`ArenaBlueprints.design`), wo der Plan schweigt auf den gemerkten
 *   Urzustand. Wer an einer zurückgesetzten Stelle steht, wird nach oben gesetzt ([ArenaRescue]) – nie eingemauert.
 *   Stellen in nicht geladenen Chunks bleiben im Register.
 * - [restoreAll] leert den Eintrag. Bleibt wegen nicht geladener Chunks etwas übrig, setzt [tickOrphans] es zurück,
 *   sobald die Chunks geladen sind.
 * - [recover]: nach einem Neustart alle offenen Einträge wiederherstellen (die Arena war mitten im Kampf).
 *
 * Schreiblast: [set] markiert den Zustand nur als geändert; Vanilla speichert ihn mit der Welt (Autosave, Beenden).
 * Kein anderes Paket schreibt in `hha_arena_edits`.
 */
class ArenaEdits private constructor(
    val world: ServerWorld,
    val blueprintId: String,
    val origin: BlockPos,
    private val entry: EditStore.Entry,
) {

    private val access = WorldAccess(world)

    /** Alle Stellen, die seit dem Öffnen verändert und noch nicht zurückgesetzt wurden. */
    val changed: Set<BlockPos> get() = entry.cells.keys.mapTo(LinkedHashSet()) { BlockPos.fromLong(it) }

    /** Anzahl der offenen Stellen (additiv, ohne Kopie). */
    val size: Int get() = entry.cells.size

    /** Ist [pos] im Register? (additiv) */
    fun isChanged(pos: BlockPos): Boolean = entry.cells.containsKey(pos.asLong())

    /** Merkt die Stelle (persistent, nur beim ersten Mal den Urzustand) und setzt den Block. */
    fun set(pos: BlockPos, state: BlockState) {
        if (BlueprintLogic.edit(entry.cells, access, pos.x, pos.y, pos.z, state)) store(world.server!!).markDirty()
    }

    fun clear(pos: BlockPos) = set(pos, Blocks.AIR.defaultState)

    /** Zurück auf den Bauplan bzw. den gemerkten Zustand für alle Stellen, auf die [filter] passt. */
    fun restore(filter: (BlockPos) -> Boolean = { true }) {
        val done = restoreEntry(world, entry) { filter(BlockPos.fromLong(it)) }
        if (done > 0) store(world.server!!).markDirty()
    }

    /** Alles zurück; der Eintrag wird geleert (Rest in nicht geladenen Chunks folgt später, siehe [tickOrphans]). */
    fun restoreAll() {
        restore { true }
        val server = world.server!!
        live.remove(entry.key)
        if (entry.cells.isEmpty()) {
            store(server).entries.remove(entry.key)
            store(server).markDirty()
        } else {
            Hha.LOGGER.warn("Arena {} an {}: {} Stellen in nicht geladenen Chunks – werden zurückgesetzt, sobald sie geladen sind", blueprintId, origin.toShortString(), entry.cells.size)
        }
    }

    companion object {

        /** Offene Register dieses Serverlaufs (Schlüssel wie im Speicherstand). */
        private val live = HashMap<String, ArenaEdits>()

        /** Verwaiste Einträge (nach Absturz oder mit Rest in ungeladenen Chunks) so oft prüfen. */
        const val ORPHAN_EVERY = 100

        /**
         * Öffnet das Register für den Plan [blueprintId] am Ursprung [origin]. Ein offener oder verwaister Eintrag für
         * dieselbe Arena wird übernommen (seine Urzustände bleiben gültig).
         */
        fun open(world: ServerWorld, blueprintId: String, origin: BlockPos): ArenaEdits {
            val origin = ArenaBlueprints.canonicalOrigin(blueprintId, origin)
            val key = EditStore.key(world, blueprintId, origin)
            live[key]?.let { if (it.world === world) return it }
            val store = store(world.server!!)
            val entry = store.entries.getOrPut(key) {
                store.markDirty()
                EditStore.Entry(world.registryKey.value.toString(), blueprintId, origin.asLong(), LinkedHashMap())
            }
            val edits = ArenaEdits(world, blueprintId, origin.toImmutable(), entry)
            live[key] = edits
            return edits
        }

        /**
         * Nach einem Neustart (`SERVER_STARTED`): alle offenen Einträge wiederherstellen. Die betroffenen Chunks werden
         * dafür einmal geladen (die Stellen lagen in erzeugten Chunks). Was trotzdem scheitert, bleibt verwaist und
         * folgt in [tickOrphans].
         */
        fun recover(server: MinecraftServer) {
            live.clear()
            val store = store(server)
            if (store.entries.isEmpty()) return
            var cells = 0
            for (entry in store.entries.values.toList()) {
                val world = worldOf(server, entry)
                if (world == null) {
                    Hha.LOGGER.warn("Arena {}: Welt {} fehlt – Edits bleiben gemerkt", entry.blueprint, entry.world)
                    continue
                }
                val before = entry.cells.size
                try {
                    loadChunks(world, entry)
                    restoreEntry(world, entry)
                } catch (e: Exception) {
                    Hha.LOGGER.warn("Arena {}: Wiederherstellung nach Neustart gescheitert ({}), neuer Versuch später", entry.blueprint, e.toString())
                }
                cells += before - entry.cells.size
                if (entry.cells.isEmpty()) store.entries.remove(entry.key)
            }
            store.markDirty()
            Hha.LOGGER.info("Arenen nach Neustart: {} Stellen nach Bauplan zurückgesetzt, {} Einträge offen", cells, store.entries.size)
        }

        /** Alle [ORPHAN_EVERY] Ticks (aufgerufen aus `WorldRetrofit.init`): verwaiste Einträge zurücksetzen, wo die Chunks geladen sind. */
        internal fun tickOrphans(server: MinecraftServer) {
            if (server.ticks % ORPHAN_EVERY != 0) return
            val store = store(server)
            if (store.entries.isEmpty()) return
            var changed = false
            for (entry in store.entries.values.toList()) {
                if (entry.key in live) continue
                val world = worldOf(server, entry) ?: continue
                if (restoreEntry(world, entry) > 0) changed = true
                if (entry.cells.isEmpty()) {
                    store.entries.remove(entry.key)
                    changed = true
                }
            }
            if (changed) store.markDirty()
        }

        /** Server endet: offene Register vergessen (der Speicherstand behält sie für [recover]). */
        internal fun reset() {
            live.clear()
        }

        /** Offene Einträge im Speicherstand (für Status und Prüfung). */
        fun pending(server: MinecraftServer): Int = store(server).entries.values.sumOf { it.cells.size }

        /** Setzt einen Eintrag zurück (nur Stellen in geladenen Chunks), hebt Spieler heraus; liefert die Zahl. */
        private fun restoreEntry(world: ServerWorld, entry: EditStore.Entry, filter: (Long) -> Boolean = { true }): Int {
            if (entry.cells.isEmpty()) return 0
            val design = if (ArenaBlueprints.get(entry.blueprint) != null) ArenaBlueprints.design(entry.blueprint, BlockPos.fromLong(entry.origin)) else null
            val done = BlueprintLogic.restore(entry.cells, design, WorldAccess(world), filter)
            if (done.isEmpty) return 0
            ArenaRescue.lift(world, bounds(done))
            return done.size
        }

        private fun bounds(cells: it.unimi.dsi.fastutil.longs.LongArrayList): BlockBox {
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
            for (i in 0 until cells.size) {
                val k = cells.getLong(i)
                val x = BlockPos.unpackLongX(k); val y = BlockPos.unpackLongY(k); val z = BlockPos.unpackLongZ(k)
                if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
            }
            return BlockBox(minX, minY, minZ, maxX, maxY, maxZ)
        }

        private fun loadChunks(world: ServerWorld, entry: EditStore.Entry) {
            val chunks = HashSet<Long>()
            for (k in entry.cells.keys) chunks.add(net.minecraft.util.math.ChunkPos.toLong(BlockPos.unpackLongX(k) shr 4, BlockPos.unpackLongZ(k) shr 4))
            for (c in chunks) world.getChunk(net.minecraft.util.math.ChunkPos.getPackedX(c), net.minecraft.util.math.ChunkPos.getPackedZ(c))
        }

        private fun worldOf(server: MinecraftServer, entry: EditStore.Entry): ServerWorld? {
            val id = Identifier.tryParse(entry.world) ?: return null
            return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id))
        }

        private fun store(server: MinecraftServer): EditStore = server.overworld.persistentStateManager.getOrCreate(EditStore.TYPE)
    }
}

/** Speicherstand `hha_arena_edits`: je Arena (Welt, Bauplan, Ursprung) die geänderten Stellen mit Urzustand. */
internal class EditStore(list: List<Entry> = emptyList()) : PersistentState() {

    class Entry(val world: String, val blueprint: String, val origin: Long, val cells: LinkedHashMap<Long, BlockState>) {
        val key: String get() = "$world|$blueprint|$origin"
    }

    private class Cell(val pos: Long, val state: BlockState)

    val entries = LinkedHashMap<String, Entry>().apply { for (e in list) put(e.key, e) }

    companion object {
        fun key(world: ServerWorld, blueprint: String, origin: BlockPos): String = "${world.registryKey.value}|$blueprint|${origin.asLong()}"

        private val CELL: Codec<Cell> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.LONG.fieldOf("pos").forGetter(Cell::pos),
                BlockState.CODEC.fieldOf("state").forGetter(Cell::state),
            ).apply(i, ::Cell)
        }

        private val ENTRY: Codec<Entry> = RecordCodecBuilder.create { i ->
            i.group(
                Codec.STRING.fieldOf("world").forGetter(Entry::world),
                Codec.STRING.fieldOf("blueprint").forGetter(Entry::blueprint),
                Codec.LONG.fieldOf("origin").forGetter(Entry::origin),
                CELL.listOf().fieldOf("cells").forGetter { e -> e.cells.map { (k, v) -> Cell(k, v) } },
            ).apply(i) { w, b, o, c -> Entry(w, b, o, c.associateTo(LinkedHashMap()) { it.pos to it.state }) }
        }

        val CODEC: Codec<EditStore> = ENTRY.listOf().xmap({ EditStore(it) }, { it.entries.values.toList() })

        val TYPE = PersistentStateType("hha_arena_edits", { EditStore() }, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE)
    }
}
