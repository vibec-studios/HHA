package dev.henny.hha.world

import com.mojang.serialization.MapCodec
import dev.henny.hha.Hha
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.ChestBlock
import net.minecraft.block.entity.MobSpawnerBlockEntity
import net.minecraft.entity.EntityType
import net.minecraft.inventory.LootableInventory
import net.minecraft.loot.LootTable
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.structure.StructureContext
import net.minecraft.structure.StructurePiece
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.random.Random
import net.minecraft.world.Heightmap
import net.minecraft.world.StructureWorldAccess
import net.minecraft.world.gen.StructureAccessor
import net.minecraft.world.gen.chunk.ChunkGenerator
import net.minecraft.world.gen.structure.Structure
import net.minecraft.world.gen.structure.StructureType
import java.util.Optional
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Die Bauwerke des Tartaros nach Hesiod und Homer: bronzene Mauern und Tore, der Kerker der Titanen, die Strafstätten
 * von Sisyphos, Ixion und Tantalos und der Palast des Hades. Die Pläne selbst stehen in [TartarusPlans] und [PalacePlan].
 *
 * Alle Pläne zeichnen auf eine [BlockCanvas] statt direkt in die Welt. In der Welt schneidet die Leinwand auf den
 * gerade erzeugten Chunk zu, im Prüflauf sammelt sie die Blöcke im Speicher, damit sich ein Plan ohne Spiel ansehen lässt.
 */
abstract class BlockCanvas {
    abstract val minX: Int
    abstract val maxX: Int
    abstract val minZ: Int
    abstract val maxZ: Int

    abstract fun put(x: Int, y: Int, z: Int, state: BlockState)
    abstract fun ground(x: Int, z: Int): Int
    abstract fun chest(x: Int, y: Int, z: Int)
    abstract fun spawner(x: Int, y: Int, z: Int, type: EntityType<*>)

    /** Der oberste natürliche Block einer Spalte (für Böschungen, die wie das Gelände aussehen sollen); null, wenn unbekannt. */
    open fun surfaceState(x: Int, z: Int): BlockState? = null

    fun fill(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, state: BlockState) {
        for (x in maxOf(minOf(x1, x2), minX)..minOf(maxOf(x1, x2), maxX)) {
            for (z in maxOf(minOf(z1, z2), minZ)..minOf(maxOf(z1, z2), maxZ)) {
                for (y in minOf(y1, y2)..maxOf(y1, y2)) put(x, y, z, state)
            }
        }
    }

    /** Stützt eine Fläche bis in den Boden, damit nichts im Ödland schwebt. */
    fun foundation(x1: Int, z1: Int, x2: Int, z2: Int, top: Int, state: BlockState) {
        for (x in maxOf(minOf(x1, x2), minX)..minOf(maxOf(x1, x2), maxX)) {
            for (z in maxOf(minOf(z1, z2), minZ)..minOf(maxOf(z1, z2), maxZ)) {
                val floor = ground(x, z)
                var y = top
                while (y >= floor - 1 && y > top - DEPTH) put(x, y--, z, state)
            }
        }
    }

    /** Geht alle Spalten eines Kreises durch: f(x, z, Abstand zur Mitte). */
    fun disc(cx: Int, cz: Int, radius: Double, f: (Int, Int, Double) -> Unit) {
        val reach = radius.toInt() + 1
        for (x in maxOf(cx - reach, minX)..minOf(cx + reach, maxX)) {
            for (z in maxOf(cz - reach, minZ)..minOf(cz + reach, maxZ)) {
                val r = hypot((x - cx).toDouble(), (z - cz).toDouble())
                if (r <= radius) f(x, z, r)
            }
        }
    }

    /** Dicke Linie zwischen zwei Punkten, zum Beispiel Bronzeketten. */
    fun line(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, a: BlockState, b: BlockState = a) {
        val steps = maxOf(abs(x2 - x1), abs(y2 - y1), abs(z2 - z1)).coerceAtLeast(1)
        for (i in 0..steps) {
            val x = x1 + (x2 - x1) * i / steps
            val y = y1 + (y2 - y1) * i / steps
            val z = z1 + (z2 - z1) * i / steps
            if (x in minX..maxX && z in minZ..maxZ) put(x, y, z, if (i % 3 == 0) b else a)
        }
    }

    private companion object {
        const val DEPTH = 60
    }
}

internal class WorldCanvas(
    private val world: StructureWorldAccess, box: BlockBox, private val random: Random,
    private val heightmap: Heightmap.Type = Heightmap.Type.OCEAN_FLOOR_WG,
) : BlockCanvas() {
    override val minX = box.minX
    override val maxX = box.maxX
    override val minZ = box.minZ
    override val maxZ = box.maxZ
    private val pos = BlockPos.Mutable()
    private val loot: RegistryKey<LootTable> = RegistryKey.of(RegistryKeys.LOOT_TABLE, Hha.id("chests/tartarus"))

    private fun inside(x: Int, z: Int) = x in minX..maxX && z in minZ..maxZ

    override fun put(x: Int, y: Int, z: Int, state: BlockState) {
        if (inside(x, z) && y in world.bottomY..world.topYInclusive) world.setBlockState(pos.set(x, y, z), state, 2)
    }

    override fun ground(x: Int, z: Int): Int = world.getTopY(heightmap, x, z)

    override fun surfaceState(x: Int, z: Int): BlockState? {
        if (!inside(x, z)) return null
        val state = world.getBlockState(pos.set(x, ground(x, z) - 1, z))
        return if (state.isAir || !state.fluidState.isEmpty) null else state
    }

    override fun chest(x: Int, y: Int, z: Int) {
        if (!inside(x, z)) return
        pos.set(x, y, z)
        world.setBlockState(pos, Blocks.CHEST.defaultState.with(ChestBlock.FACING, Direction.SOUTH), 2)
        LootableInventory.setLootTable(world, random, pos, loot)
    }

    override fun spawner(x: Int, y: Int, z: Int, type: EntityType<*>) {
        if (!inside(x, z)) return
        pos.set(x, y, z)
        world.setBlockState(pos, Blocks.SPAWNER.defaultState, 2)
        (world.getBlockEntity(pos) as? MobSpawnerBlockEntity)?.setEntityType(type, random)
    }
}

/** Das Bauwerk samt Größe: horizontale Reichweite, sowie wie weit es unter und über dem Mittelpunkt liegt. */
enum class TartarusKind(val id: String, val reach: Int, val below: Int, val above: Int) {
    PALACE("hades_palace", 120, 44, 104),
    FORTRESS("bronze_fortress", 46, 30, 48),
    PRISON("titan_prison", 36, 34, 26),
    PUNISHMENT("punishment_site", 30, 24, 36),
}

class TartarusStructure(config: Config, private val kind: TartarusKind) : Structure(config) {

    override fun getStructurePosition(context: Context): Optional<StructurePosition> {
        val chunk = context.chunkPos()
        val surface = context.chunkGenerator().getHeightOnGround(chunk.centerX, chunk.centerZ, Heightmap.Type.WORLD_SURFACE_WG, context.world(), context.noiseConfig())
        // Auf Gipfeln wäre kein Platz nach oben, in Schluchten nichts zum Draufstellen: Die Höhe bleibt im Rahmen.
        val pos = BlockPos(chunk.centerX, surface.coerceIn(MIN_HEIGHT, MAX_HEIGHT), chunk.centerZ)
        return Optional.of(StructurePosition(pos) { collector -> collector.addPiece(TartarusPiece(kind, pos)) })
    }

    override fun getType(): StructureType<*> = HhaWorldgen.tartarusType(kind)

    companion object {
        private const val MIN_HEIGHT = 60
        private const val MAX_HEIGHT = 118

        fun codec(kind: TartarusKind): MapCodec<TartarusStructure> = createCodec { TartarusStructure(it, kind) }
    }
}

class TartarusPiece : StructurePiece {

    val kind: TartarusKind
    val middle: BlockPos

    constructor(kind: TartarusKind, center: BlockPos) : super(
        HhaWorldgen.TARTARUS_PIECE, 0,
        BlockBox(center.x - kind.reach, center.y - kind.below, center.z - kind.reach, center.x + kind.reach, center.y + kind.above, center.z + kind.reach),
    ) {
        this.kind = kind
        this.middle = center
    }

    constructor(nbt: NbtCompound) : super(HhaWorldgen.TARTARUS_PIECE, nbt) {
        kind = TartarusKind.entries[nbt.getInt("kind", 0)]
        middle = BlockPos(nbt.getInt("cx", 0), nbt.getInt("cy", 0), nbt.getInt("cz", 0))
    }

    override fun writeNbt(context: StructureContext, nbt: NbtCompound) {
        nbt.putInt("kind", kind.ordinal)
        nbt.putInt("cx", middle.x)
        nbt.putInt("cy", middle.y)
        nbt.putInt("cz", middle.z)
    }

    override fun generate(
        world: StructureWorldAccess, structureAccessor: StructureAccessor, chunkGenerator: ChunkGenerator, random: Random,
        chunkBox: BlockBox, chunkPos: ChunkPos, pivot: BlockPos,
    ) {
        TartarusPlans.draw(WorldCanvas(world, chunkBox, random), kind, middle)
    }
}
