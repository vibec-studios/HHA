package dev.henny.hha.logic.world

import dev.henny.hha.world.olymp.SkyCanvas
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.entity.EntityType
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos

/**
 * Leinwand direkt in der Welt, auf [box] beschränkt (Überarbeitung 4, P00: aus `OlympusPath` herausgezogen, eingefroren).
 * Der Aufrufer sorgt dafür, dass die Chunks des Kastens geladen sind. Truhen und Spawner zeichnet sie nicht.
 */
class LiveCanvas(private val world: ServerWorld, box: BlockBox) : SkyCanvas() {
    override val minX = box.minX
    override val maxX = box.maxX
    override val minZ = box.minZ
    override val maxZ = box.maxZ
    private val pos = BlockPos.Mutable()
    override fun put(x: Int, y: Int, z: Int, state: BlockState) {
        if (inside(x, z) && y in world.bottomY..world.topYInclusive) world.setBlockState(pos.set(x, y, z), state, Block.NOTIFY_LISTENERS)
    }
    override fun isFree(x: Int, y: Int, z: Int): Boolean = inside(x, z) && world.getBlockState(pos.set(x, y, z)).isAir
    override fun ground(x: Int, z: Int): Int = 0
    override fun chest(x: Int, y: Int, z: Int) {}
    override fun spawner(x: Int, y: Int, z: Int, type: EntityType<*>) {}
}

/**
 * Aufnahme-Leinwand (Überarbeitung 4, P00; PLAN 7.3): zeichnet einen Plan nicht in die Welt, sondern in eine Tabelle.
 * Jede gesetzte Zelle im Kasten wird gemerkt, auch Luft (der Plan sagt „hier ist frei“). Der letzte Aufruf gewinnt, wie
 * in der Welt. [isFree] liest die Tabelle, damit weiche Blöcke (Wolken, Bewuchs) wie beim echten Zeichnen entstehen.
 * Höhen werden nicht beschnitten; [floor] ist der Boden für Pläne, die `ground` fragen (Olymp: Boden des Plans).
 * Truhen werden als Truhe, Spawner als Spawner aufgenommen (die Reparatur entscheidet, ob sie sie setzt).
 */
class RecordingCanvas(val box: BlockBox, private val floor: Int = box.minY) : SkyCanvas() {
    override val minX = box.minX
    override val maxX = box.maxX
    override val minZ = box.minZ
    override val maxZ = box.maxZ

    /** Alle aufgenommenen Zellen (gepackte Position → Zustand, Luft eingeschlossen), in Zeichenreihenfolge der ersten Setzung. */
    val cells = LinkedHashMap<Long, BlockState>()

    override fun put(x: Int, y: Int, z: Int, state: BlockState) {
        if (!inside(x, z)) return
        cells[BlockPos.asLong(x, y, z)] = state
    }

    override fun isFree(x: Int, y: Int, z: Int): Boolean = inside(x, z) && (cells[BlockPos.asLong(x, y, z)]?.isAir ?: true)
    override fun ground(x: Int, z: Int): Int = floor
    override fun chest(x: Int, y: Int, z: Int) = put(x, y, z, Blocks.CHEST.defaultState)
    override fun spawner(x: Int, y: Int, z: Int, type: EntityType<*>) = put(x, y, z, Blocks.SPAWNER.defaultState)

    /** Zustand einer Zelle laut Plan, `null` = der Plan sagt dort nichts. */
    fun at(x: Int, y: Int, z: Int): BlockState? = cells[BlockPos.asLong(x, y, z)]
}
