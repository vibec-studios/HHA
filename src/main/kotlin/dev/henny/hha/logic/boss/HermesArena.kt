package dev.henny.hha.logic.boss

import dev.henny.hha.HhaSounds
import dev.henny.hha.logic.boss.timings.HermesSigTimings
import dev.henny.hha.world.arena.ArenaGeo
import dev.henny.hha.world.arena.ArenaSpec
import dev.henny.hha.world.olymp.ForumPlan
import dev.henny.hha.world.olymp.OlympusArenas
import dev.henny.hha.world.olymp.SkyCanvas
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.entity.EntityType
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Vec3d
import kotlin.math.hypot

/**
 * Das Himmelsforum: eine schwebende Marmorscheibe hoch über dem Tempel der Insel B (bzw. beim Test über dem Standort).
 * Der Bauplan steht in [ForumPlan] (Überarbeitung 4, P14): Kern r 22, acht Botensäulen mit Glocken, Empore in vier
 * Bögen. Jeder überschriebene Block wird gemerkt ([put]) und am Ende exakt wiederhergestellt ([restore]); dazwischen
 * ändert der Kampf das Forum nur über diese Klasse:
 *
 * - [repaint]: Kernboden der neuen Stage als Welle von innen nach außen ([BLOCKS_PER_TICK] je Tick);
 * - [breakColumn]: Botensäule bis auf den Stumpf ([HermesSigTimings.COLUMN_STUMP]) abtragen, oben zuerst;
 * - [restoreColumns]: alle gebrochenen Säulen und alle Glocken aus dem Plan zurück (Stage-Beginn);
 * - [dropArc]: ein Bogen der Empore stürzt ab, oben zuerst, [HermesSigTimings.ARC_BLOCKS_PER_TICK] je Tick, mit Trümmern;
 * - [finishWork]: alles, was noch in den Warteschlangen steht, sofort (übersprungene Szene, Stage-Beginn).
 *
 * Alles läuft über [put]: Auch abgetragene Säulen, Glocken und abgestürzte Bögen kehren mit [restore] in den Zustand vor
 * dem Kampf zurück (über dem Tempel steht danach kein Block mehr).
 */
class HermesArena(private val world: ServerWorld, val center: BlockPos) {

    /** Innerhalb dieser Zone lässt der Kampf keine Änderung an Blöcken zu. */
    fun contains(pos: BlockPos): Boolean {
        if (pos.y !in (center.y - 14)..(center.y + 60)) return false
        return hypot((pos.x - center.x).toDouble(), (pos.z - center.z).toDouble()) <= RADIUS + 4
    }

    /** Vertrag des Forums um diese Mitte (Deckung, Glocken, Empore, Bögen). */
    val spec: ArenaSpec = OlympusArenas.FORUM(center)

    /** Originalzustand aller veränderten Positionen. */
    private val original = LinkedHashMap<BlockPos, BlockState>()
    private val forcedChunks = ArrayList<ChunkPos>()

    /** Noch umzufärbende Blöcke, nach Abstand zur Mitte sortiert (ergibt die Welle). */
    private val repaintQueue = ArrayDeque<Pair<BlockPos, BlockState>>()

    /** Abzutragende Blöcke der Bögen, oben zuerst. */
    private val dropQueue = ArrayDeque<BlockPos>()

    /** Abzutragende Blöcke gebrochener Säulen, oben zuerst (vor den Bögen: der Bruch soll sofort sichtbar sein). */
    private val columnQueue = ArrayDeque<BlockPos>()

    /** Stage, deren Plan gerade gilt (für Säulen und Glocken). */
    var stage = 1
        private set

    /** Abgestürzte Bögen (`arc_0` …). */
    val lost: Set<String> get() = lostArcs
    private val lostArcs = LinkedHashSet<String>()

    /** Gebrochene Säulen (Index 0 … 7) seit dem letzten [restoreColumns]. */
    val broken: Set<Int> get() = brokenColumns
    private val brokenColumns = LinkedHashSet<Int>()

    val floorY: Double get() = center.y + 1.0

    /** Füße auf der Empore. */
    val galleryY: Double get() = floorY + OlympusArenas.FORUM_GALLERY_HEIGHT

    fun centerPos(): Vec3d = Vec3d(center.x + 0.5, floorY, center.z + 0.5)

    fun build(stage: Int) {
        forceChunks()
        this.stage = stage
        // Überarbeitung 4: Der Bauplan steht in ForumPlan; diese Leinwand merkt jeden Ursprung über [put].
        ForumPlan.draw(ArenaCanvas(), center, stage, emptySet())
    }

    /** Leinwand, die jede Setzung über [put] leitet (Ursprung merken, Block setzen). */
    private inner class ArenaCanvas : SkyCanvas() {
        override val minX = center.x - RADIUS - 2
        override val maxX = center.x + RADIUS + 2
        override val minZ = center.z - RADIUS - 2
        override val maxZ = center.z + RADIUS + 2
        override fun put(x: Int, y: Int, z: Int, state: BlockState) = this@HermesArena.put(BlockPos(x, y, z), state)
        override fun isFree(x: Int, y: Int, z: Int): Boolean = world.getBlockState(BlockPos(x, y, z)).isAir
        override fun ground(x: Int, z: Int): Int = center.y
        override fun chest(x: Int, y: Int, z: Int) {}
        override fun spawner(x: Int, y: Int, z: Int, type: EntityType<*>) {}
    }

    /** Leinwand, die nur sammelt (Plan einer Säule für die Rückkehr). */
    private inner class MemoryCanvas : SkyCanvas() {
        val cells = LinkedHashMap<BlockPos, BlockState>()
        override val minX = center.x - RADIUS - 2
        override val maxX = center.x + RADIUS + 2
        override val minZ = center.z - RADIUS - 2
        override val maxZ = center.z + RADIUS + 2
        override fun put(x: Int, y: Int, z: Int, state: BlockState) {
            cells[BlockPos(x, y, z)] = state
        }
        override fun isFree(x: Int, y: Int, z: Int): Boolean = true
        override fun ground(x: Int, z: Int): Int = center.y
        override fun chest(x: Int, y: Int, z: Int) {}
        override fun spawner(x: Int, y: Int, z: Int, type: EntityType<*>) {}
    }

    /** Färbt den Kernboden der neuen Stage ein; die Welle läuft von der Mitte nach außen. Die Empore bleibt Marmor. */
    fun repaint(stage: Int) {
        this.stage = stage
        repaintQueue.clear()
        val entries = ArrayList<Triple<Double, BlockPos, BlockState>>()
        val reach = ForumPlan.CORE_EDGE_RING
        for (dx in -reach..reach) {
            for (dz in -reach..reach) {
                if (ForumPlan.ringOf(dx, dz) > reach) continue
                val r = hypot(dx.toDouble(), dz.toDouble())
                entries.add(Triple(r, center.add(dx, 0, dz), ForumPlan.floorBlock(stage, dx, dz, r)))
            }
        }
        entries.sortBy { it.first }
        for ((_, pos, state) in entries) repaintQueue.addLast(pos to state)
    }

    // --- Säulen ---------------------------------------------------------------------------------------------------

    /** Steht die Botensäule [k] noch (seit dem letzten [restoreColumns] nicht gebrochen)? */
    fun standing(k: Int): Boolean = k !in brokenColumns

    /**
     * Trägt die Botensäule [k] bis auf den Stumpf ab ([HermesSigTimings.COLUMN_STUMP] Lagen: Sockel und Band), oben
     * zuerst, mit Trümmern und Ton `arena.crumble`. Die Glocke davor bleibt stehen (der Kampf hält sie stumm).
     * @return false, wenn sie schon gebrochen war.
     */
    fun breakColumn(k: Int): Boolean {
        if (!brokenColumns.add(k)) return false
        val box = ForumPlan.columnBox(center, k)
        val from = box.minY + HermesSigTimings.COLUMN_STUMP
        val cells = ArrayList<BlockPos>()
        for (y in box.maxY downTo from) for (x in box.minX..box.maxX) for (z in box.minZ..box.maxZ) {
            val pos = BlockPos(x, y, z)
            if (!world.getBlockState(pos).isAir) cells.add(pos)
        }
        columnQueue.addAll(cells)
        val mid = Vec3d(box.minX + 2.5, box.minY + HermesSigTimings.COLUMN_STUMP + 1.0, box.minZ + 2.5)
        BossFx.debris(world, mid, COLUMN_DEBRIS_RADIUS, COLUMN_DEBRIS)
        world.playSound(null, mid.x, mid.y, mid.z, HhaSounds.ARENA_CRUMBLE, SoundCategory.HOSTILE, 2.4f, 0.9f)
        return true
    }

    /** Alle gebrochenen Säulen und alle Glocken (Zustand 0) zurück aus dem Plan; zum Stage-Beginn, nie in einer Ansage. */
    fun restoreColumns() {
        val plan = MemoryCanvas()
        for (k in brokenColumns) ForumPlan.column(plan, center, k)
        for (k in 0 until OlympusArenas.FORUM_COLUMNS) ForumPlan.bell(plan, center, spec, k)
        columnQueue.removeAll { pos -> plan.cells.containsKey(pos) }
        for ((pos, state) in plan.cells) {
            if (world.getBlockState(pos) != state) put(pos, state)
        }
        brokenColumns.clear()
    }

    // --- Empore ---------------------------------------------------------------------------------------------------

    /**
     * Der Bogen [id] (`arc_0` …) stürzt ab: alle Blöcke seiner Zone von der Brüstung bis zur Schale, oben zuerst, dazu
     * ein Aufgang, dessen beide Bögen dann fehlen. Die Zone kommt aus dem Vertrag (`spec.part(id)`), die Höhe reicht bis
     * zur Brüstung (seit P24 = `part.maxY`). @return false, wenn er schon fehlt.
     */
    fun dropArc(id: String): Boolean {
        if (!lostArcs.add(id)) return false
        val part = spec.part(id)
        val top = center.y + OlympusArenas.FORUM_GALLERY_HEIGHT + 1
        val cells = ArrayList<BlockPos>()
        val columns = part.zone.cells().toList()
        for (y in top downTo part.minY) for ((x, z) in columns) {
            val pos = BlockPos(x, y, z)
            if (!world.getBlockState(pos).isAir) cells.add(pos)
        }
        for (a in OlympusArenas.FORUM_STAIR_ANGLES) {
            if (!ForumPlan.stairGone(a, lostArcs)) continue
            for (cell in ArenaGeo.radialStairs(center, a, OlympusArenas.FORUM_CORE - 2.0, OlympusArenas.FORUM_GALLERY_HEIGHT, OlympusArenas.FORUM_STAIR_WIDTH, spec.coreY).sortedByDescending { it.y }) {
                for (y in cell.y downTo spec.coreY) {
                    val pos = BlockPos(cell.x, y, cell.z)
                    if (!world.getBlockState(pos).isAir) cells.add(pos)
                }
            }
        }
        dropQueue.addAll(cells)
        world.playSound(null, center.x + 0.5, galleryY, center.z + 0.5, HhaSounds.ARENA_COLLAPSE, SoundCategory.HOSTILE, 3f, 0.8f)
        return true
    }

    /** Fehlt die Empore über (x, z) (Spalte in einem abgestürzten Bogen)? */
    fun inLostArc(x: Int, z: Int): Boolean = ForumPlan.inLostArc(spec, lostArcs, x, z)

    /** Steht (x, z) auf einer noch vorhandenen Empore (Blockringe 22 … 26)? */
    fun onGallery(x: Double, z: Double): Boolean {
        val r = hypot(x - (center.x + 0.5), z - (center.z + 0.5))
        if (r <= GALLERY_EDGE || r > RADIUS + 0.5) return false
        return !inLostArc(kotlin.math.floor(x).toInt(), kotlin.math.floor(z).toInt())
    }

    /** Rand der begehbaren Fläche unter dem Winkel [deg]: Außenkante 26,5, an einem verlorenen Bogen die Kernkante 21,5. */
    fun edgeAt(deg: Double): Double {
        val p = ArenaGeo.polar(center, GALLERY_EDGE + 1.0, deg)
        return if (inLostArc(p.x, p.z)) GALLERY_EDGE else RADIUS + 0.5
    }

    /**
     * Höhe, über der ein fliegender Hermes an (x, z) mindestens bleibt: Empore (Kern + 2), Säulenstumpf (Kern + Stumpf)
     * oder Kern. Stehende Säulen zählen nicht: In sie stößt er hinein ([HermesBells]).
     */
    fun flightFloor(x: Double, z: Double): Double {
        if (onGallery(x, z)) return galleryY
        val bx = kotlin.math.floor(x).toInt()
        val bz = kotlin.math.floor(z).toInt()
        for (k in brokenColumns) {
            val box = ForumPlan.columnBox(center, k)
            if (bx in box.minX + 1..box.maxX - 1 && bz in box.minZ + 1..box.maxZ - 1) return floorY + HermesSigTimings.COLUMN_STUMP
        }
        return floorY
    }

    /** Erledigt sofort alles, was noch in den Warteschlangen steht (Szene übersprungen, Stage beginnt). */
    fun finishWork() {
        while (repaintQueue.isNotEmpty()) {
            val (pos, state) = repaintQueue.removeFirst()
            if (world.getBlockState(pos) != state) put(pos, state)
        }
        while (columnQueue.isNotEmpty()) put(columnQueue.removeFirst(), Blocks.AIR.defaultState)
        while (dropQueue.isNotEmpty()) put(dropQueue.removeFirst(), Blocks.AIR.defaultState)
    }

    fun tick() {
        var budget = BLOCKS_PER_TICK
        while (budget-- > 0 && repaintQueue.isNotEmpty()) {
            val (pos, state) = repaintQueue.removeFirst()
            put(pos, state)
        }
        var stones = HermesSigTimings.COLUMN_BLOCKS_PER_TICK
        while (stones-- > 0 && columnQueue.isNotEmpty()) put(columnQueue.removeFirst(), Blocks.AIR.defaultState)
        var drops = HermesSigTimings.ARC_BLOCKS_PER_TICK
        var debrisAt: BlockPos? = null
        while (drops-- > 0 && dropQueue.isNotEmpty()) {
            val pos = dropQueue.removeFirst()
            if (debrisAt == null && !world.getBlockState(pos).isAir) debrisAt = pos
            put(pos, Blocks.AIR.defaultState)
        }
        // Trümmer: alle paar Ticks an der Stelle, die gerade fällt (echte Blöcke des Forums, nur Darstellung).
        val at = debrisAt
        if (at != null && world.time % DEBRIS_EVERY == 0L) BossFx.debris(world, Vec3d.ofCenter(at), ARC_DEBRIS_RADIUS, ARC_DEBRIS)
    }

    private fun put(pos: BlockPos, state: BlockState) {
        val key = pos.toImmutable()
        original.putIfAbsent(key, world.getBlockState(key))
        world.setBlockState(key, state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
    }

    private fun forceChunks() {
        val min = ChunkPos(center.add(-RADIUS - 2, 0, -RADIUS - 2))
        val max = ChunkPos(center.add(RADIUS + 2, 0, RADIUS + 2))
        for (x in min.x..max.x) {
            for (z in min.z..max.z) {
                if (world.setChunkForced(x, z, true)) forcedChunks.add(ChunkPos(x, z))
            }
        }
    }

    /**
     * Stellt alles wieder her, auch wenn der Kampf abbricht: jede Stelle, die je über [put] lief (Scheibe, Säulen samt
     * Stümpfen, Glocken in jedem Zustand, abgestürzte Bögen), bekommt ihren Zustand von vor dem Kampf.
     */
    fun restore() {
        repaintQueue.clear()
        dropQueue.clear()
        columnQueue.clear()
        for ((pos, state) in original) {
            world.setBlockState(pos, state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
        }
        original.clear()
        lostArcs.clear()
        brokenColumns.clear()
        for (chunk in forcedChunks) world.setChunkForced(chunk.x, chunk.z, false)
        forcedChunks.clear()
    }

    companion object {
        const val RADIUS = 26
        const val COLUMNS = 8
        const val COLUMN_HEIGHT = 7
        private const val BLOCKS_PER_TICK = 160

        /** Kante des Kerns (Blockring 21) bzw. Innenkante der Empore. */
        const val GALLERY_EDGE = 21.5

        /** Trümmer: beim Säulenbruch einmal, beim Bogenabsturz alle [DEBRIS_EVERY] Ticks (reine Darstellung). */
        private const val COLUMN_DEBRIS_RADIUS = 1.8f
        private const val COLUMN_DEBRIS = 7
        private const val ARC_DEBRIS_RADIUS = 2.6f
        private const val ARC_DEBRIS = 5
        private const val DEBRIS_EVERY = 4L

        /** Höhe des Forums: deutlich über dem Boden, aber unterhalb der Weltobergrenze. */
        fun chooseHeight(world: ServerWorld, groundY: Int): Int =
            (groundY + 80).coerceAtLeast(190).coerceAtMost(world.topYInclusive - 30)
    }
}
