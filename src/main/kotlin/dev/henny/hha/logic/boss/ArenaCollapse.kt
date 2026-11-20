package dev.henny.hha.logic.boss

import dev.henny.hha.HhaSounds
import dev.henny.hha.logic.boss.arena.ArenaEdits
import dev.henny.hha.world.arena.Part
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.FluidBlock
import net.minecraft.server.MinecraftServer
import net.minecraft.sound.SoundCategory
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.WorldEvents

/**
 * Einsturz und Zerfall (Überarbeitung 4, PLAN 7.4; Besitzer P03; Vorbild Void-Absturz, REF_VOID 2.3, und
 * `HadesAttacks.crumble`). Blöcke ändern sich nur über [ArenaEdits], damit [Arenas.close] bzw. der Bauplan sie
 * wiederherstellt. Kein echtes Fallblock-Wesen, kein Drop: Die Fall-Darstellung sind Client-Trümmer (`core.debris`).
 *
 * Rezept „Teil abwerfen“ (nur an Lebensschwellen/Phasenbeginn, vorher Ansage ROOM ≥ 40):
 * ```
 * val part = spec.part("floe_0")
 * ArenaCollapse.start(arena, part) { /* Fläche ist weg, Bö und Beitritt kennen sie nicht mehr */ }
 * ```
 */
object ArenaCollapse {

    /** Jede so vielte Zahl entfernter Blöcke zählt als „Fall-Darstellung“ (PLAN: ein Drittel). */
    const val FALL_SHARE = 3

    /** Trümmer höchstens alle so viele Ticks (der Client zeigt höchstens 24 gleichzeitig, `BlockDebris.MAX_LIVE`). */
    const val DEBRIS_EVERY = 6

    /** Trümmer je Aufruf (≤ `BossFx.DEBRIS_PER_CALL`). */
    const val DEBRIS_COUNT = 6

    /** Halbmesser, aus dem der Client die Trümmer-Blöcke nimmt. */
    const val DEBRIS_RADIUS = 3f

    /** Bruchpartikel höchstens so oft je Tick (je eine Vanilla-Bruchwolke; mehr wäre Geflacker und Last). */
    const val BREAK_FX_PER_TICK = 4

    /** Lautstärke und Tonhöhe des Einsturz-Tons (einmal je Teil). */
    const val COLLAPSE_VOLUME = 4f
    const val COLLAPSE_PITCH = 0.7f

    /** Lautstärke und Tonhöhe des Bröckel-Tons (einmal je Säule). */
    const val CRUMBLE_VOLUME = 3f
    const val CRUMBLE_PITCH = 0.85f

    private val FLAGS = Block.NOTIFY_LISTENERS or Block.FORCE_STATE

    /** Ein laufender Abbau: Liste von oben nach unten, [perTick] je Tick. */
    private class Job(
        val edits: () -> ArenaEdits?,
        val alive: () -> Boolean,
        val order: List<BlockPos>,
        val perTick: Int,
        val onDone: () -> Unit,
        val withBlockEntities: Boolean = false,
    ) {
        var next = 0
        var removed = 0
        var age = 0

        /** Zuletzt über das Register gesetzte Stelle: Fehlt sie dort, wurde zurückgespielt (Arena zu) → Abbau endet. */
        var last: BlockPos? = null
    }

    private val jobs = ArrayList<Job>()

    /**
     * Teil [part] stürzt ab: alle Blöcke der Zone zwischen `minY` und `maxY`, von oben nach unten, [perTick] je Tick über
     * `arena.edits`. Jeder [FALL_SHARE]-te zählt zur Fall-Darstellung (`core.debris` in Wellen), der Rest zeigt (gedeckelt)
     * Bruchpartikel. Ton `arena.collapse` einmal. Am Ende `arena.lose(part)` und [onDone].
     *
     * Ohne Edit-Register (Arena ohne Vertrag) oder ohne Blöcke: sofort `lose` und [onDone], die Welt bleibt, wie sie ist.
     * Schließt die Arena vorher, endet der Abbau ohne `onDone` (die Wiederherstellung läuft über [Arenas.close]).
     * Spieler auf dem Teil fallen; die Bö der Arena holt sie zurück (sie kennt das Teil ab `lose` nicht mehr als Fläche,
     * darum wird `lose` **vor** dem ersten Block gemeldet).
     *
     * Blöcke mit Block-Entity (Lagerfeuer, Schalen, Altar, Truhen) bleiben **standardmäßig stehen** und hängen danach
     * womöglich in der Luft. [withBlockEntities] = true nimmt sie mit ab (P24, Wunsch P15 2). Die Wiederherstellung über
     * [ArenaEdits] setzt den gemerkten Blockzustand zurück, also eine frische Block-Entity ohne ihren alten Inhalt: nur für
     * Bau-Deko ohne Inhalt verwenden (Feuer, Lagerfeuer), nie für Geräte mit Daten oder gefüllte Behälter. Der Parameter
     * steht vor [onDone], damit `start(arena, part) { … }` weiter geht.
     */
    fun start(arena: Arenas.Arena, part: Part, perTick: Int = 40, withBlockEntities: Boolean = false, onDone: () -> Unit = {}) {
        arena.lose(part)
        val edits = arena.edits
        val order = if (edits == null) emptyList() else collect(arena, part, withBlockEntities)
        if (order.isEmpty()) {
            onDone()
            return
        }
        val center = centerOf(order)
        Telegraph.current?.sound(HhaSounds.ARENA_COLLAPSE, center, COLLAPSE_VOLUME, COLLAPSE_PITCH)
            ?: arena.world.playSound(null, center.x, center.y, center.z, HhaSounds.ARENA_COLLAPSE, SoundCategory.HOSTILE, COLLAPSE_VOLUME, COLLAPSE_PITCH)
        jobs.add(Job({ arena.edits }, { arena.open }, order, perTick.coerceAtLeast(1), onDone, withBlockEntities))
    }

    /**
     * Säule/Turm im Kasten [box] zerbröselt von oben bis auf die Höhe [keepBelow] (Blöcke mit `y < keepBelow` bleiben als
     * Stumpf), [perTick] je Tick über [edits]. Trümmer und Ton `arena.crumble` am Fuß. Läuft, solange [edits] in Gebrauch
     * ist; nach `restoreAll` (Arena zu) setzt der Abbau nichts mehr.
     *
     * Blöcke mit Block-Entity bleiben standardmäßig stehen (etwa das Lagerfeuer oben auf einem Fackelturm); mit
     * [withBlockEntities] = true fallen sie mit (P24). Wiederherstellung und Vorsicht wie bei [start].
     */
    fun crumble(edits: ArenaEdits, box: BlockBox, keepBelow: Int, perTick: Int = 24, withBlockEntities: Boolean = false) {
        val world = edits.world
        val order = ArrayList<BlockPos>()
        for (y in box.maxY downTo maxOf(box.minY, keepBelow)) {
            for (x in box.minX..box.maxX) for (z in box.minZ..box.maxZ) {
                val pos = BlockPos(x, y, z)
                if (removable(world.getBlockState(pos), withBlockEntities)) order.add(pos)
            }
        }
        if (order.isEmpty()) return
        val foot = Vec3d((box.minX + box.maxX + 1) / 2.0, keepBelow.toDouble(), (box.minZ + box.maxZ + 1) / 2.0)
        BossFx.debris(world, foot.add(0.0, 1.0, 0.0), DEBRIS_RADIUS, DEBRIS_COUNT)
        Telegraph.current?.sound(HhaSounds.ARENA_CRUMBLE, foot, CRUMBLE_VOLUME, CRUMBLE_PITCH)
            ?: world.playSound(null, foot.x, foot.y, foot.z, HhaSounds.ARENA_CRUMBLE, SoundCategory.HOSTILE, CRUMBLE_VOLUME, CRUMBLE_PITCH)
        // Gehört das Register einer offenen Arena, endet der Abbau mit ihr; sonst, sobald es zurückgespielt wurde.
        val arena = Arenas.all().firstOrNull { it.edits === edits }
        val alive: () -> Boolean = if (arena != null) ({ arena.open && arena.edits === edits }) else ({ true })
        jobs.add(Job({ edits }, alive, order, perTick.coerceAtLeast(1), {}, withBlockEntities))
    }

    /** Bricht alle laufenden Abbauten ab (Server hält an; die Arenen stellen beim Schließen wieder her). */
    internal fun finishAll() {
        jobs.clear()
    }

    /** Aus [Arenas.init] jeden Server-Tick. */
    internal fun tick(server: MinecraftServer) {
        if (jobs.isEmpty()) return
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val job = iterator.next()
            val edits = job.edits()
            if (edits == null || !job.alive() || job.last?.let { it !in edits.changed } == true) {
                iterator.remove()
                continue
            }
            if (edits.world.server !== server) continue
            step(job, edits)
            if (job.next >= job.order.size) {
                iterator.remove()
                job.onDone()
            }
        }
    }

    private fun step(job: Job, edits: ArenaEdits) {
        val world = edits.world
        var breakFx = 0
        var fall = 0
        var fallCenter = Vec3d.ZERO
        val end = minOf(job.order.size, job.next + job.perTick)
        while (job.next < end) {
            val pos = job.order[job.next++]
            val state = world.getBlockState(pos)
            if (!removable(state, job.withBlockEntities)) continue
            job.removed++
            if (job.removed % FALL_SHARE == 0) {
                fall++
                fallCenter = fallCenter.add(Vec3d.ofCenter(pos))
            } else if (breakFx < BREAK_FX_PER_TICK) {
                breakFx++
                world.syncWorldEvent(WorldEvents.BLOCK_BROKEN, pos, Block.getRawIdFromState(state))
            }
            // Wasser im Block (Waterlogged) bleibt als Wasser, sonst Luft.
            edits.set(pos, state.fluidState.blockState.takeIf { !it.isAir } ?: Blocks.AIR.defaultState)
            job.last = pos
        }
        // Trümmer vor den Block-Updates schicken wäre schöner; der Client nimmt sie aus der Umgebung, darum am Schwerpunkt.
        if (fall > 0 && job.age % DEBRIS_EVERY == 0) {
            BossFx.debris(world, fallCenter.multiply(1.0 / fall), DEBRIS_RADIUS, DEBRIS_COUNT)
        }
        job.age++
    }

    /** Alle Blöcke von [part] in der Welt, oben zuerst (je Schicht in Zellen-Reihenfolge). */
    private fun collect(arena: Arenas.Arena, part: Part, withBlockEntities: Boolean): List<BlockPos> {
        val cells = part.zone.cells().toList()
        val order = ArrayList<BlockPos>()
        for (y in part.maxY downTo part.minY) {
            for ((x, z) in cells) {
                val pos = BlockPos(x, y, z)
                if (removable(arena.world.getBlockState(pos), withBlockEntities)) order.add(pos)
            }
        }
        return order
    }

    /**
     * Was wegfällt: feste und lose Blöcke; Luft und reine Flüssigkeit bleiben, Blöcke mit Block-Entity (Altar, Lagerfeuer)
     * nur ohne [withBlockEntities].
     */
    private fun removable(state: BlockState, withBlockEntities: Boolean = false): Boolean =
        !state.isAir && (withBlockEntities || !state.hasBlockEntity()) && state.block !is FluidBlock

    private fun centerOf(order: List<BlockPos>): Vec3d {
        var sum = Vec3d.ZERO
        val step = maxOf(1, order.size / 64)
        var n = 0
        for (i in order.indices step step) {
            sum = sum.add(Vec3d.ofCenter(order[i]))
            n++
        }
        return sum.multiply(1.0 / n)
    }
}
