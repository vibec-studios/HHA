package dev.henny.hha.world.olymp

import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import dev.henny.hha.Hha
import dev.henny.hha.world.BlockCanvas
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.ChestBlock
import net.minecraft.entity.EntityType
import net.minecraft.inventory.LootableInventory
import net.minecraft.loot.LootTable
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.structure.StructureContext
import net.minecraft.structure.StructurePiece
import net.minecraft.structure.StructurePieceType
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3i
import net.minecraft.util.math.random.Random
import net.minecraft.world.StructureWorldAccess
import net.minecraft.world.gen.StructureAccessor
import net.minecraft.world.gen.chunk.ChunkGenerator
import net.minecraft.world.gen.chunk.placement.StructurePlacement
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator
import net.minecraft.world.gen.chunk.placement.StructurePlacementType
import net.minecraft.world.gen.structure.Structure
import net.minecraft.world.gen.structure.StructureType
import java.util.Optional
import kotlin.math.abs
import kotlin.math.hypot

/*
 * Die Olymp-Dimension als Strukturen (PLAN 8.1): Der Inselpfad ist eine Struktur mit fester Platzierung, je Insel und je
 * Brücke ein Start; dazu die zufälligen Nebeninseln. Gezeichnet wird über eine [SkyCanvas], damit der Prüflauf dieselben
 * Pläne ohne Welt ansehen kann.
 *
 * Warum viele Starts und nicht einer: Eine Struktur darf nur Chunks im Umkreis von 8 Chunks um ihren Start-Chunk belegen
 * (Vanillas Strukturverweise). Der Pfad ist rund 770 Blöcke lang, also bekommt jede Insel und jede Brücke ihren eigenen
 * Start; der erste liegt im Chunk der Insel A.
 */

/**
 * Leinwand für den Olymp. Zusätzlich zur [BlockCanvas]: [soft] setzt nur in freie Stellen. Wolken und Bewuchs sind weich,
 * Inseln, Brücken und Bauten hart; so ist das Ergebnis gleich, egal in welcher Reihenfolge benachbarte Starts zeichnen.
 */
abstract class SkyCanvas : BlockCanvas() {

    /** Ist die Stelle leer (Luft)? */
    abstract fun isFree(x: Int, y: Int, z: Int): Boolean

    fun soft(x: Int, y: Int, z: Int, state: BlockState) {
        if (x in minX..maxX && z in minZ..maxZ && isFree(x, y, z)) put(x, y, z, state)
    }

    fun inside(x: Int, z: Int): Boolean = x in minX..maxX && z in minZ..maxZ
}

/** Leinwand in der echten Welt, auf den gerade erzeugten Chunk zugeschnitten. */
internal class SkyWorldCanvas(
    private val world: StructureWorldAccess, box: BlockBox, private val random: Random, private val floor: Int,
    private val flags: Int = Block.NOTIFY_LISTENERS,
) : SkyCanvas() {
    override val minX = box.minX
    override val maxX = box.maxX
    override val minZ = box.minZ
    override val maxZ = box.maxZ
    private val pos = BlockPos.Mutable()

    private fun inHeight(y: Int) = y in world.bottomY..world.topYInclusive

    override fun put(x: Int, y: Int, z: Int, state: BlockState) {
        if (inside(x, z) && inHeight(y)) world.setBlockState(pos.set(x, y, z), state, flags)
    }

    override fun isFree(x: Int, y: Int, z: Int): Boolean = inside(x, z) && inHeight(y) && world.getBlockState(pos.set(x, y, z)).isAir

    /** Der Olymp hat kein Gelände: Der Boden ist die Bodenhöhe des Bauplans. */
    override fun ground(x: Int, z: Int): Int = floor

    override fun chest(x: Int, y: Int, z: Int) {
        if (!inside(x, z)) return
        pos.set(x, y, z)
        world.setBlockState(pos, Blocks.CHEST.defaultState.with(ChestBlock.FACING, Direction.SOUTH), flags)
        LootableInventory.setLootTable(world, random, pos, OlympusStructures.RUIN_LOOT)
    }

    override fun spawner(x: Int, y: Int, z: Int, type: EntityType<*>) {}
}

/**
 * Feste Platzierung `hha:fixed`: Die Struktur beginnt genau in den Chunks der Liste (PLAN 8.1). Keine Häufigkeit, kein
 * Salz, keine Sperrzone; `/locate` findet sie nicht (das braucht niemand, die Koordinaten stehen in [OlympusLayout]).
 */
class FixedStructurePlacement(val chunks: List<ChunkPos>) :
    StructurePlacement(Vec3i.ZERO, FrequencyReductionMethod.DEFAULT, 1f, 0, Optional.empty()) {

    private val starts: Set<Long> = chunks.mapTo(HashSet()) { it.toLong() }

    override fun isStartChunk(calculator: StructurePlacementCalculator, chunkX: Int, chunkZ: Int): Boolean =
        ChunkPos.toLong(chunkX, chunkZ) in starts

    override fun getType(): StructurePlacementType<*> = OlympusStructures.FIXED

    companion object {
        val CODEC: MapCodec<FixedStructurePlacement> = RecordCodecBuilder.mapCodec { instance ->
            instance.group(ChunkPos.CODEC.listOf().fieldOf("chunks").forGetter(FixedStructurePlacement::chunks))
                .apply(instance, ::FixedStructurePlacement)
        }
    }
}

/** Ein Teil des Pfads: eine Insel oder eine Brücke, mit eigenem Start-Chunk und eigenem Kasten. */
class PathElement(val id: String, val box: BlockBox, val floor: Int, val draw: (SkyCanvas) -> Unit) {

    /** Start-Chunk: bei Inseln der Chunk der Inselmitte, bei Brücken der Chunk der Kastenmitte. */
    var start: ChunkPos = ChunkPos(BlockPos(box.center))
        internal set
}

/** Der Inselpfad: sucht im Start-Chunk das zugehörige [PathElement] und legt dafür ein Piece an. */
class OlympusPathStructure(config: Config) : Structure(config) {

    override fun getStructurePosition(context: Context): Optional<StructurePosition> {
        val element = OlympusStructures.elementAt(context.chunkPos()) ?: return Optional.empty()
        val pos = BlockPos(element.box.center.x, element.floor, element.box.center.z)
        return Optional.of(StructurePosition(pos) { collector -> collector.addPiece(OlympusPathPiece(element.id)) })
    }

    override fun getType(): StructureType<*> = OlympusStructures.PATH_TYPE

    companion object {
        val CODEC: MapCodec<OlympusPathStructure> = createCodec { OlympusPathStructure(it) }
    }
}

class OlympusPathPiece : StructurePiece {

    private val id: String

    constructor(id: String) : super(OlympusStructures.PATH_PIECE, 0, OlympusStructures.element(id)!!.box.let { BlockBox(it.minX, it.minY, it.minZ, it.maxX, it.maxY, it.maxZ) }) {
        this.id = id
    }

    constructor(nbt: NbtCompound) : super(OlympusStructures.PATH_PIECE, nbt) {
        id = nbt.getString("element", "")
    }

    override fun writeNbt(context: StructureContext, nbt: NbtCompound) {
        nbt.putString("element", id)
    }

    override fun generate(
        world: StructureWorldAccess, structureAccessor: StructureAccessor, chunkGenerator: ChunkGenerator, random: Random,
        chunkBox: BlockBox, chunkPos: ChunkPos, pivot: BlockPos,
    ) {
        val element = OlympusStructures.element(id) ?: return
        element.draw(SkyWorldCanvas(world, chunkBox, random, element.floor))
    }
}

/** Nebeninseln: `random_spread` 9/4, nie im Kasten um den Pfad; Art und Höhe aus dem Chunk-Zufall. */
class SkyIsletStructure(config: Config) : Structure(config) {

    override fun getStructurePosition(context: Context): Optional<StructurePosition> {
        val chunk = context.chunkPos()
        val random = context.random()
        val kind = SkyIslets.Kind.entries[random.nextInt(SkyIslets.Kind.entries.size)]
        val x = chunk.startX + random.nextInt(16)
        val z = chunk.startZ + random.nextInt(16)
        val y = SkyIslets.MIN_Y + random.nextInt(SkyIslets.MAX_Y - SkyIslets.MIN_Y + 1)
        val seed = random.nextInt()
        val reach = SkyIslets.reach(kind)
        if (SkyIslets.touchesPath(x, z, reach)) return Optional.empty()
        val pos = BlockPos(x, y, z)
        return Optional.of(StructurePosition(pos) { collector -> collector.addPiece(SkyIsletPiece(kind, pos, seed)) })
    }

    override fun getType(): StructureType<*> = OlympusStructures.ISLET_TYPE

    companion object {
        val CODEC: MapCodec<SkyIsletStructure> = createCodec { SkyIsletStructure(it) }
    }
}

class SkyIsletPiece : StructurePiece {

    val kind: SkyIslets.Kind
    val middle: BlockPos
    val seed: Int

    constructor(kind: SkyIslets.Kind, center: BlockPos, seed: Int) : super(OlympusStructures.ISLET_PIECE, 0, SkyIslets.box(kind, center)) {
        this.kind = kind
        this.middle = center
        this.seed = seed
    }

    constructor(nbt: NbtCompound) : super(OlympusStructures.ISLET_PIECE, nbt) {
        kind = SkyIslets.Kind.entries[nbt.getInt("kind", 0).coerceIn(0, SkyIslets.Kind.entries.size - 1)]
        middle = BlockPos(nbt.getInt("cx", 0), nbt.getInt("cy", 0), nbt.getInt("cz", 0))
        seed = nbt.getInt("seed", 0)
    }

    override fun writeNbt(context: StructureContext, nbt: NbtCompound) {
        nbt.putInt("kind", kind.ordinal)
        nbt.putInt("cx", middle.x)
        nbt.putInt("cy", middle.y)
        nbt.putInt("cz", middle.z)
        nbt.putInt("seed", seed)
    }

    override fun generate(
        world: StructureWorldAccess, structureAccessor: StructureAccessor, chunkGenerator: ChunkGenerator, random: Random,
        chunkBox: BlockBox, chunkPos: ChunkPos, pivot: BlockPos,
    ) {
        SkyIslets.draw(SkyWorldCanvas(world, chunkBox, random, middle.y), kind, middle, seed)
    }
}

/** Registrierung und das Verzeichnis der Pfad-Teile. */
object OlympusStructures {

    lateinit var FIXED: StructurePlacementType<FixedStructurePlacement>; private set
    lateinit var PATH_PIECE: StructurePieceType; private set
    lateinit var ISLET_PIECE: StructurePieceType; private set
    lateinit var PATH_TYPE: StructureType<OlympusPathStructure>; private set
    lateinit var ISLET_TYPE: StructureType<SkyIsletStructure>; private set

    val PATH_ID: Identifier = Hha.id("olympus_path")
    val ISLET_ID: Identifier = Hha.id("sky_islet")

    /** Beute der Truhe in der Ruinen-Nebeninsel (Tabelle von P06: Gold, Kupfer, Federn, selten ein goldener Apfel). */
    val RUIN_LOOT: RegistryKey<LootTable> = RegistryKey.of(RegistryKeys.LOOT_TABLE, Hha.id("chests/olympus_islet"))

    fun init() {
        FIXED = Registry.register(Registries.STRUCTURE_PLACEMENT, Hha.id("fixed"), StructurePlacementType { FixedStructurePlacement.CODEC })
        PATH_PIECE = Registry.register(Registries.STRUCTURE_PIECE, PATH_ID, StructurePieceType { _: StructureContext, nbt: NbtCompound -> OlympusPathPiece(nbt) })
        ISLET_PIECE = Registry.register(Registries.STRUCTURE_PIECE, ISLET_ID, StructurePieceType { _: StructureContext, nbt: NbtCompound -> SkyIsletPiece(nbt) })
        PATH_TYPE = Registry.register(Registries.STRUCTURE_TYPE, PATH_ID, StructureType { OlympusPathStructure.CODEC })
        ISLET_TYPE = Registry.register(Registries.STRUCTURE_TYPE, ISLET_ID, StructureType { SkyIsletStructure.CODEC })
    }

    /** Alle Teile des Pfads: sechs Inseln, fünf Brücken. */
    val ELEMENTS: List<PathElement> by lazy {
        val list = ArrayList<PathElement>()
        for (isle in OlympusLayout.ISLES) {
            val element = PathElement("isle_${isle.letter}", OlympusPlans.box(isle), isle.floorY - 1) { c -> OlympusPlans.draw(c, isle) }
            element.start = ChunkPos(isle.center)
            list.add(element)
        }
        for (bridge in OlympusBridges.ALL) {
            list.add(PathElement("bridge_${bridge.id}", bridge.box, bridge.y0) { c -> OlympusBridges.draw(c, bridge, OlympusBridges.Part.OUTSIDE) })
        }
        list
    }

    private val byId: Map<String, PathElement> by lazy { ELEMENTS.associateBy { it.id } }
    private val byChunk: Map<Long, PathElement> by lazy { ELEMENTS.associateBy { it.start.toLong() } }

    fun element(id: String): PathElement? = byId[id]

    fun elementAt(chunk: ChunkPos): PathElement? = byChunk[chunk.toLong()]

    /** Die Start-Chunks, wie sie in `structure_set/olympus_path.json` stehen müssen (der Prüflauf vergleicht). */
    fun startChunks(): List<ChunkPos> = ELEMENTS.map { it.start }

    /** Ein Piece darf nur Chunks im Umkreis von 8 Chunks um seinen Start belegen. */
    fun fitsReferenceRange(element: PathElement): Boolean {
        val min = ChunkPos(BlockPos(element.box.minX, 0, element.box.minZ))
        val max = ChunkPos(BlockPos(element.box.maxX, 0, element.box.maxZ))
        val s = element.start
        return abs(min.x - s.x) <= 8 && abs(max.x - s.x) <= 8 && abs(min.z - s.z) <= 8 && abs(max.z - s.z) <= 8
    }

    /**
     * Gehört die Spalte (x, z) einer Insel? Das entscheidet, wer an Brückenköpfen zeichnet: Was in einer Insel liegt,
     * zeichnet das Insel-Piece (auch das Brückenstück dort), alles andere das Brücken-Piece. So schreiben zwei Starts
     * nie auf dieselbe Spalte.
     */
    fun claim(x: Int, z: Int): OlympusLayout.Isle? {
        for (isle in OlympusLayout.ISLES) {
            if (hypot((x - isle.center.x).toDouble(), (z - isle.center.z).toDouble()) <= isle.radius + 0.5) return isle
        }
        for ((isle, box) in OlympusPlans.EXTRA_CLAIMS) {
            if (x in box.minX..box.maxX && z in box.minZ..box.maxZ) return isle
        }
        return null
    }
}
