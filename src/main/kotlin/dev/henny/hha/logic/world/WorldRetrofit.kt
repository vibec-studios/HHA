package dev.henny.hha.logic.world

import com.mojang.serialization.Codec
import dev.henny.hha.Hha
import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.HermesFights
import dev.henny.hha.logic.boss.arena.ArenaBlueprints
import dev.henny.hha.logic.boss.arena.ArenaEdits
import dev.henny.hha.logic.boss.arena.ArenaRescue
import dev.henny.hha.logic.boss.arena.BlueprintLogic
import dev.henny.hha.logic.boss.arena.BlueprintVerify
import dev.henny.hha.logic.boss.arena.WorldAccess
import dev.henny.hha.world.olymp.OlympusArenas
import dev.henny.hha.world.olymp.OlympusBridges
import dev.henny.hha.world.olymp.OlympusLayout
import dev.henny.hha.world.olymp.OlympusStructures
import dev.henny.hha.world.olymp.PathElement
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.LongArrayList
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.datafixer.DataFixTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateType
import java.util.Locale
import kotlin.math.sqrt

/**
 * Nachrüstung des Olymp-Pfads in alten Welten (Überarbeitung 4, PLAN 6.3 Punkt 1; Besitzer P02).
 *
 * Ablauf je Pfad-Teil (`OlympusStructures.ELEMENTS`: sechs Inseln, fünf Brücken):
 * 1. **Stand:** Bauversion [REV] je Teil im `PersistentState` `hha_build_rev` (an der Olymp-Welt). Steht ein Teil auf
 *    [REV], passiert nichts mehr – auch spätere Spielerbauten bleiben.
 * 2. **Prüfung** ([check]) höchstens einmal je Serverlauf und Teil, sobald ein Spieler näher als [NEAR] am Kasten ist,
 *    alle Chunks des Kastens voll geladen sind ([WorldAccess.chunkReady]) und im Kasten kein Kampf offen ist
 *    ([fightOpen]): [SAMPLES] Stichproben aus dem Plan des Teils (Seed aus der ID) plus Signaturzellen (Deckung und
 *    Geräte der Arena-Spec). Übereinstimmung ≥ [MATCH] und weniger als [SIGNATURE_WRONG] falsche Signaturen → Stand
 *    setzen, fertig. Sonst Neubau.
 * 3. **Neubau** ([Job]) in Scheiben von [COLUMNS_PER_TICK] Spalten je Tick, nur Spalten, die dem Teil gehören
 *    ([Region.owner]: Inselspalten laut `claim`, Brückenspalten außerhalb der Inselkreise), je Spalte über die ganze
 *    Kastenhöhe: Ziel = Plan, sonst Luft. Blöcke mit BlockEntity, die der Plan nicht kennt, bleiben
 *    ([BlueprintLogic.repairTarget]). Spieler in entstehenden Blöcken kommen nach oben, mit Chat-Hinweis. OPs lesen
 *    Beginn und Ende. Ein Kampf im Kasten oder ein entladener Chunk hält den Neubau an, bis es weitergeht.
 * 4. **Siegelbrücken** werden in dem Zustand gezeichnet, den der Fortschritt verlangt: offen nach dem Sieg (oder wenn
 *    der Markierblock schon liegt), sonst versiegelt.
 *
 * Schalter [TOGGLE] (Standard an) stellt nur die Automatik ab; die Befehle `/hha world retrofit status|run <teil>|all`
 * rufen [status] und [run] und wirken immer.
 */
object WorldRetrofit {

    const val TOGGLE = "world_retrofit"

    /** Bauversion der Überarbeitung 4. Höher setzen, wenn sich ein Pfad-Plan wieder grundlegend ändert. */
    const val REV = 4

    /** Spieler näher als so viele Blöcke am Kasten → das Teil wird geprüft. */
    const val NEAR = 160.0

    /** Mindestanteil gleicher Stichproben. */
    const val MATCH = 0.97

    /** Ab so vielen falschen Signaturzellen wird neu gebaut (Vorbild Blood `checkUpgrade`). */
    const val SIGNATURE_WRONG = 3

    /** Stichproben je Prüfung. */
    const val SAMPLES = 400

    /** Neubau: Spalten je Tick. */
    const val COLUMNS_PER_TICK = 350

    /** Automatik: so oft prüfen (Ticks). */
    const val CHECK_EVERY = 20

    // ---------------------------------------------------------------------------------------------------------------
    // Stand je Welt
    // ---------------------------------------------------------------------------------------------------------------

    /** `hha_build_rev`: Pfad-Teil → Bauversion. */
    class RevState(map: Map<String, Int> = emptyMap()) : PersistentState() {
        private val revs = HashMap(map)

        fun rev(id: String): Int = revs[id] ?: 0

        fun set(id: String, rev: Int) {
            if (revs.put(id, rev) != rev) markDirty()
        }

        companion object {
            val CODEC: Codec<RevState> = Codec.unboundedMap(Codec.STRING, Codec.INT).xmap({ RevState(it) }, { HashMap(it.revs) })
        }
    }

    val TYPE = PersistentStateType("hha_build_rev", { RevState() }, RevState.CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE)

    private fun revs(world: ServerWorld): RevState = world.persistentStateManager.getOrCreate(TYPE)

    // ---------------------------------------------------------------------------------------------------------------
    // Laufzeit (je Serverlauf)
    // ---------------------------------------------------------------------------------------------------------------

    private var server: MinecraftServer? = null

    /** In diesem Serverlauf schon geprüft. */
    private val checked = HashSet<String>()

    /** Letzte Statuszeile je Teil (Ergebnis der Prüfung, Grund des Wartens). */
    private val notes = HashMap<String, String>()

    /** Der laufende Neubau (höchstens einer). */
    private var job: Job? = null

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            ArenaBlueprints.tick(server)
            ArenaEdits.tickOrphans(server)
            tick(server)
        }
        if (System.getProperty("hha.verifyWorldgen") != null) {
            // Prüflauf: Bauplan-Selbsttest vor WorldgenVerify (das hält den Server danach an).
            val phase = Hha.id("blueprint_selftest")
            ServerLifecycleEvents.SERVER_STARTING.addPhaseOrdering(phase, Event.DEFAULT_PHASE)
            ServerLifecycleEvents.SERVER_STARTING.register(phase) { _ -> BlueprintVerify.run() }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { _ ->
            server = null
            reset()
            ArenaBlueprints.reset()
            ArenaEdits.reset()
        }
    }

    private fun reset() {
        checked.clear()
        notes.clear()
        job = null
    }

    /** Jeden Server-Tick: laufenden Neubau fortsetzen, sonst alle [CHECK_EVERY] Ticks ein fälliges Teil prüfen. */
    fun tick(server: MinecraftServer) {
        if (this.server !== server) {
            this.server = server
            reset()
        }
        if (!HhaConfig.enabled(TOGGLE)) return
        val world = server.getWorld(OlympusLayout.WORLD) ?: return
        job?.let { current ->
            if (step(world, current)) finish(world, current)
            return
        }
        if (server.ticks % CHECK_EVERY != 0) return
        if (world.players.isEmpty()) return
        val state = revs(world)
        for (element in OlympusStructures.ELEMENTS) {
            if (element.id in checked || state.rev(element.id) >= REV) continue
            if (!playerNear(world, element.box)) continue
            if (fightOpen(world, element.box)) { notes[element.id] = "wartet: Kampf im Kasten"; continue }
            if (!chunksReady(world, element.box)) { notes[element.id] = "wartet: Chunks noch nicht voll geladen"; continue }
            checked.add(element.id)
            val region = Region(world, element)
            val result = check(world, region)
            notes[element.id] = result.line
            Hha.LOGGER.info("Olymp-Nachrüstung {}: {}", element.id, result.line)
            if (result.fits) {
                state.set(element.id, REV)
            } else {
                start(world, region)
            }
            return   // höchstens eine Prüfung je Durchgang (die Aufnahme kostet einige Millisekunden)
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Region: Plan des Teils samt Nachbarn, Spalten-Besitz
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Plan eines Pfad-Teils im Kasten: eigene Aufnahme ([own], über `PathElement.draw` bzw. bei Siegelbrücken im
     * verlangten Zustand) und – erst für den Neubau – die Aufnahmen der Nachbarteile, deren Kasten diesen schneidet
     * (weiche Wolken einer Insel ragen in Brückenspalten). Ziel einer Zelle in einer eigenen Spalte: eigene Aufnahme,
     * sonst Nachbar, sonst Luft.
     */
    internal class Region(val world: ServerWorld, val element: PathElement) {
        val box: BlockBox = element.box

        /** Eigene Aufnahme (gepackte Position → Zustand, gesetzte Luft eingeschlossen). */
        val own: Long2ObjectOpenHashMap<BlockState> = record(element, box, sealedFor(world, element))

        /** Nachbar-Aufnahmen, zusammengeführt (erst beim Neubau). */
        val neighbours: Long2ObjectOpenHashMap<BlockState> by lazy {
            val merged = Long2ObjectOpenHashMap<BlockState>()
            for (other in OlympusStructures.ELEMENTS) {
                if (other === element || !intersects2d(other.box, box)) continue
                for (e in record(other, box, sealedFor(world, other)).long2ObjectEntrySet()) merged.putIfAbsent(e.longKey, e.value)
            }
            merged
        }

        /** Spalten des Kastens, die diesem Teil gehören (x, z gepackt mit y = 0), in Zeilenfolge. */
        val columns: LongArray by lazy {
            val out = LongArrayList()
            for (x in box.minX..box.maxX) for (z in box.minZ..box.maxZ) if (owner(x, z) === element) out.add(BlockPos.asLong(x, 0, z))
            out.toLongArray()
        }

        /** Soll-Zustand in einer eigenen Spalte. */
        fun target(x: Int, y: Int, z: Int): BlockState {
            val k = BlockPos.asLong(x, y, z)
            return own.get(k) ?: neighbours.get(k) ?: AIR
        }

        /** Eigene aufgenommene Zellen in eigenen Spalten (Grundlage der Stichproben). */
        fun sampleKeys(): LongArray {
            val out = LongArrayList()
            for (e in own.long2ObjectEntrySet()) {
                val k = e.longKey
                if (owner(BlockPos.unpackLongX(k), BlockPos.unpackLongZ(k)) === element) out.add(k)
            }
            val array = out.toLongArray()
            array.sort()
            return array
        }

        companion object {
            /**
             * Wem gehört die Spalte (x, z)? Inselspalten laut `OlympusStructures.claim`; sonst der ersten Brücke, deren
             * Kasten sie enthält (Brücke außerhalb der Inselkreise); sonst der Insel, in deren Kasten sie liegt.
             */
            fun owner(x: Int, z: Int): PathElement? {
                OlympusStructures.claim(x, z)?.let { isle -> return OlympusStructures.element("isle_${isle.letter}") }
                val elements = OlympusStructures.ELEMENTS
                for (e in elements) if (e.id.startsWith("bridge_") && inside2d(e.box, x, z)) return e
                for (e in elements) if (e.id.startsWith("isle_") && inside2d(e.box, x, z)) return e
                return null
            }
        }
    }

    private val AIR: BlockState = Blocks.AIR.defaultState

    /** Brücke zu einem Pfad-Teil (`bridge_<id>`), sonst `null`. */
    private fun bridgeOf(element: PathElement): OlympusBridges.Bridge? =
        OlympusBridges.ALL.firstOrNull { "bridge_${it.id}" == element.id }

    /**
     * Zustand, in dem eine Siegelbrücke gezeichnet wird: versiegelt, solange der Boss davor lebt und der Markierblock
     * der nachgesetzten Lücke fehlt. `null` = keine Siegelbrücke (Standard-Zeichnung).
     */
    private fun sealedFor(world: ServerWorld, element: PathElement): Boolean? {
        val bridge = bridgeOf(element) ?: return null
        val boss = bridge.sealedBy ?: return null
        if (OlympusProgress.isDone(world.server!!, boss)) return false
        val marker = bridge.marker()
        if (marker != null && WorldAccess.chunkReady(world, marker.x shr 4, marker.z shr 4) && world.getBlockState(marker).isOf(Blocks.CHISELED_QUARTZ_BLOCK)) return false
        return true
    }

    /** Aufnahme eines Teils, auf [clip] zugeschnitten. */
    private fun record(element: PathElement, clip: BlockBox, sealed: Boolean?): Long2ObjectOpenHashMap<BlockState> {
        val canvas = RecordingCanvas(BlockBox(clip.minX, element.box.minY, clip.minZ, clip.maxX, element.box.maxY, clip.maxZ), element.floor)
        val bridge = bridgeOf(element)
        if (bridge != null && sealed != null) OlympusBridges.draw(canvas, bridge, OlympusBridges.Part.OUTSIDE, sealed)
        else element.draw(canvas)
        val out = Long2ObjectOpenHashMap<BlockState>(canvas.cells.size)
        for ((k, state) in canvas.cells) if (BlockPos.unpackLongY(k) in clip.minY..clip.maxY) out.put(k, state)
        return out
    }

    private fun inside2d(box: BlockBox, x: Int, z: Int): Boolean = x in box.minX..box.maxX && z in box.minZ..box.maxZ

    private fun intersects2d(a: BlockBox, b: BlockBox): Boolean = a.minX <= b.maxX && a.maxX >= b.minX && a.minZ <= b.maxZ && a.maxZ >= b.minZ

    // ---------------------------------------------------------------------------------------------------------------
    // Prüfung
    // ---------------------------------------------------------------------------------------------------------------

    private class CheckResult(val ratio: Double, val samples: Int, val wrong: Int, val signatures: Int) {
        val fits: Boolean get() = ratio >= MATCH && wrong < SIGNATURE_WRONG
        val line: String
            get() = String.format(Locale.ROOT, "geprüft: %.1f %% von %d Stichproben gleich, %d/%d Signaturzellen falsch → %s",
                ratio * 100, samples, wrong, signatures, if (fits) "passt" else "Neubau")
    }

    /** Stichproben und Signaturzellen gegen den Plan (gleicher Block). */
    private fun check(world: ServerWorld, region: Region): CheckResult {
        val keys = region.sampleKeys()
        val random = java.util.Random(region.element.id.hashCode().toLong() * 7919L)
        var total = 0
        var same = 0
        if (keys.isNotEmpty()) repeat(SAMPLES) {
            val k = keys[random.nextInt(keys.size)]
            val want = region.own.get(k) ?: return@repeat
            if (want.hasBlockEntity()) return@repeat
            total++
            if (world.getBlockState(BlockPos.fromLong(k)).isOf(want.block)) same++
        }
        val signatures = signatureCells(region.element)
        var wrong = 0
        var counted = 0
        for (k in signatures) {
            val x = BlockPos.unpackLongX(k)
            val z = BlockPos.unpackLongZ(k)
            if (Region.owner(x, z) !== region.element) continue
            val want = region.target(x, BlockPos.unpackLongY(k), z)
            counted++
            if (!world.getBlockState(BlockPos.fromLong(k)).isOf(want.block)) wrong++
        }
        return CheckResult(if (total == 0) 1.0 else same.toDouble() / total, total, wrong, counted)
    }

    /**
     * Signaturzellen: nur der neue Bau hat sie. Inseln C–F: alle Zellen der Deckung und die Geräte aus der Arena-Spec
     * (`OlympusArenas`). Andere Teile haben keine (dort entscheiden die Stichproben).
     */
    private fun signatureCells(element: PathElement): LongArray {
        val isle = OlympusLayout.ISLES.firstOrNull { "isle_${it.letter}" == element.id } ?: return LongArray(0)
        val spec = isle.key?.let { OlympusArenas.spec(it) } ?: return LongArray(0)
        val out = LongArrayList()
        for (cover in spec.cover) {
            val b = cover.box
            for (x in b.minX..b.maxX) for (y in b.minY..b.maxY) for (z in b.minZ..b.maxZ) out.add(BlockPos.asLong(x, y, z))
        }
        for (device in spec.devices) out.add(device.pos.asLong())
        return out.toLongArray()
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Neubau
    // ---------------------------------------------------------------------------------------------------------------

    /** Laufender Neubau eines Teils: Spalte [next] von `region.columns`, gesetzte Blöcke, Grund einer Pause. */
    internal class Job(val region: Region) {
        var next = 0
        var set = 0
        var paused: String? = null
        val total: Int get() = region.columns.size
    }

    private fun start(world: ServerWorld, region: Region) {
        val job = Job(region)
        this.job = job
        notes[region.element.id] = notes[region.element.id].orEmpty() + ", Neubau ${job.total} Spalten"
        Hha.LOGGER.info("Olymp-Nachrüstung {}: Neubau von {} Spalten", region.element.id, job.total)
        tellOps(world.server!!, Text.translatable("hha.arena.retrofit.rebuilding", label(region.element)).formatted(Formatting.GOLD))
    }

    /** Eine Scheibe; `true`, wenn fertig. Pausiert bei offenem Kampf oder entladenem Chunk. */
    private fun step(world: ServerWorld, job: Job): Boolean {
        val box = job.region.box
        if (fightOpen(world, box)) {
            pause(job, "Kampf im Kasten")
            return false
        }
        val access = WorldAccess(world)
        var n = 0
        while (job.next < job.total && n < COLUMNS_PER_TICK) {
            val column = job.region.columns[job.next]
            val x = BlockPos.unpackLongX(column)
            val z = BlockPos.unpackLongZ(column)
            if (!access.ready(x, z)) {
                pause(job, "Chunk ${x shr 4} ${z shr 4} nicht geladen")
                break
            }
            job.set += rebuildColumn(job.region, access, x, z)
            job.next++
            n++
        }
        if (n > 0) {
            job.paused = null
            ArenaRescue.lift(world, box)
        }
        notes[job.region.element.id] = "Neubau ${job.next}/${job.total} Spalten, ${job.set} Blöcke" + (job.paused?.let { " (Pause: $it)" } ?: "")
        return job.next >= job.total
    }

    private fun pause(job: Job, reason: String) {
        if (job.paused != reason) Hha.LOGGER.info("Olymp-Nachrüstung {}: Pause ({})", job.region.element.id, reason)
        job.paused = reason
    }

    /** Eine Spalte über die ganze Kastenhöhe: Ziel = Plan, sonst Luft. Liefert die gesetzten Blöcke. */
    private fun rebuildColumn(region: Region, access: WorldAccess, x: Int, z: Int): Int {
        var set = 0
        val world = access.world
        val minY = maxOf(region.box.minY, world.bottomY)
        val maxY = minOf(region.box.maxY, world.topYInclusive)
        for (y in minY..maxY) {
            val want = region.target(x, y, z)
            val current = access.get(x, y, z)
            val target = BlueprintLogic.repairTarget(want, current, current.hasBlockEntity()) ?: continue
            access.set(x, y, z, target)
            set++
        }
        return set
    }

    private fun finish(world: ServerWorld, job: Job) {
        this.job = null
        val id = job.region.element.id
        revs(world).set(id, REV)
        notes[id] = "nachgerüstet: ${job.total} Spalten, ${job.set} Blöcke gesetzt"
        Hha.LOGGER.info("Olymp-Nachrüstung {}: fertig, {} Blöcke gesetzt", id, job.set)
        tellOps(world.server!!, Text.translatable("hha.arena.retrofit.done", label(job.region.element)).formatted(Formatting.GOLD))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Bedingungen
    // ---------------------------------------------------------------------------------------------------------------

    /** Ein Spieler näher als [NEAR] am Kasten (Abstand zum Kasten, nicht zur Mitte). */
    private fun playerNear(world: ServerWorld, box: BlockBox): Boolean = world.players.any { p ->
        if (p.isSpectator) return@any false
        val dx = maxOf(box.minX - p.x, 0.0, p.x - (box.maxX + 1))
        val dy = maxOf(box.minY - p.y, 0.0, p.y - (box.maxY + 1))
        val dz = maxOf(box.minZ - p.z, 0.0, p.z - (box.maxZ + 1))
        sqrt(dx * dx + dy * dy + dz * dz) < NEAR
    }

    /** Alle Chunks des Kastens voll geladen? */
    private fun chunksReady(world: ServerWorld, box: BlockBox): Boolean {
        for (cx in (box.minX shr 4)..(box.maxX shr 4)) for (cz in (box.minZ shr 4)..(box.maxZ shr 4)) {
            if (!WorldAccess.chunkReady(world, cx, cz)) return false
        }
        return true
    }

    /** Ist im Kasten (waagerecht, samt Arenarand) ein Kampf offen? (`Arenas` und `HermesFights`) */
    private fun fightOpen(world: ServerWorld, box: BlockBox): Boolean {
        for (arena in Arenas.all()) {
            if (!arena.open || arena.world !== world) continue
            val r = arena.radius + 4
            if (arena.center.x + r >= box.minX && arena.center.x - r <= box.maxX + 1 && arena.center.z + r >= box.minZ && arena.center.z - r <= box.maxZ + 1) return true
        }
        val hermes = HermesFights.of(world) ?: return false
        val c = hermes.arena.center
        val r = 32
        return c.x + r >= box.minX && c.x - r <= box.maxX && c.z + r >= box.minZ && c.z - r <= box.maxZ
    }

    private fun tellOps(server: MinecraftServer, message: Text) {
        for (player in server.playerManager.playerList) {
            if (server.playerManager.isOperator(player.playerConfigEntry)) player.sendMessage(message, false)
        }
    }

    /** Anzeigename eines Teils: „Insel C“ bzw. „Brücke B–C“. */
    private fun label(element: PathElement): Text {
        val bridge = bridgeOf(element)
        if (bridge != null) return Text.translatable("hha.arena.retrofit.bridge", bridge.from.letter.toString(), bridge.to.letter.toString())
        return Text.translatable("hha.arena.retrofit.isle", element.id.removePrefix("isle_"))
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Befehle
    // ---------------------------------------------------------------------------------------------------------------

    /** Eine Zeile je Pfad-Teil (6 Inseln, 5 Brücken): Stand, letzte Prüfung bzw. Fortschritt des Neubaus. */
    fun status(server: MinecraftServer): List<String> {
        val world = server.getWorld(OlympusLayout.WORLD) ?: return listOf("Keine Olymp-Dimension in dieser Welt – nichts nachzurüsten.")
        val state = revs(world)
        val on = HhaConfig.enabled(TOGGLE)
        val lines = ArrayList<String>()
        for (element in OlympusStructures.ELEMENTS) {
            val rev = state.rev(element.id)
            val note = notes[element.id] ?: when {
                rev >= REV -> "aktuell"
                element.id in checked -> "geprüft"
                else -> "noch nicht geprüft (Spieler näher als ${NEAR.toInt()} Blöcke und alle Chunks geladen)"
            }
            lines.add("${element.id}: Stand $rev/$REV – $note")
        }
        if (!on) lines.add("Automatik aus (Schalter $TOGGLE); /hha world retrofit run wirkt trotzdem.")
        if (ArenaEdits.pending(server) > 0) lines.add("Offene Arena-Edits im Speicherstand: ${ArenaEdits.pending(server)} Stellen")
        return lines
    }

    /**
     * Teil [elementId] (bzw. alle bei `null`) jetzt nachrüsten, ohne Stichproben-Schwelle und in einem Zug: setzt nur
     * abweichende Blöcke, also auch auf aktuellem Stand harmlos. Teile mit ungeladenen Chunks oder offenem Kampf werden
     * übersprungen (Grund in [status]). Liefert die Zahl gesetzter Blöcke.
     */
    fun run(server: MinecraftServer, elementId: String?): Int {
        val world = server.getWorld(OlympusLayout.WORLD) ?: return 0
        val targets = if (elementId == null) OlympusStructures.ELEMENTS else listOfNotNull(OlympusStructures.element(elementId))
        var total = 0
        for (element in targets) {
            if (fightOpen(world, element.box)) { notes[element.id] = "übersprungen: Kampf im Kasten"; continue }
            if (!chunksReady(world, element.box)) { notes[element.id] = "übersprungen: Chunks nicht voll geladen (näher herangehen)"; continue }
            if (job?.region?.element === element) job = null
            val region = Region(world, element)
            val access = WorldAccess(world)
            var set = 0
            for (column in region.columns) set += rebuildColumn(region, access, BlockPos.unpackLongX(column), BlockPos.unpackLongZ(column))
            ArenaRescue.lift(world, element.box)
            revs(world).set(element.id, REV)
            checked.add(element.id)
            notes[element.id] = "von Hand nachgerüstet: ${region.columns.size} Spalten, $set Blöcke gesetzt"
            Hha.LOGGER.info("Olymp-Nachrüstung {} (Befehl): {} Blöcke gesetzt", element.id, set)
            if (set > 0) tellOps(server, Text.translatable("hha.arena.retrofit.done", label(element)).formatted(Formatting.GOLD))
            total += set
        }
        return total
    }
}
