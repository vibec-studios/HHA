package dev.henny.hha.world

import com.mojang.datafixers.util.Pair
import com.mojang.serialization.MapCodec
import dev.henny.hha.Hha
import dev.henny.hha.HhaBlocks
import dev.henny.hha.logic.world.HadesGate
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.nbt.NbtCompound
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.tag.TagKey
import net.minecraft.structure.StructureContext
import net.minecraft.structure.StructurePiece
import net.minecraft.structure.StructurePieceType
import net.minecraft.util.BlockRotation
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.random.Random
import net.minecraft.world.Heightmap
import net.minecraft.world.StructureWorldAccess
import net.minecraft.world.biome.Biome
import net.minecraft.world.biome.BiomeKeys
import net.minecraft.world.biome.source.util.MultiNoiseUtil
import net.minecraft.world.gen.StructureAccessor
import net.minecraft.world.gen.chunk.ChunkGenerator
import net.minecraft.world.gen.feature.DefaultFeatureConfig
import net.minecraft.world.gen.feature.Feature
import net.minecraft.world.gen.feature.util.FeatureContext
import net.minecraft.world.gen.structure.Structure
import net.minecraft.world.gen.structure.StructureType
import java.util.Optional
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Biom-Einträge für die Oberwelt; wird von VanillaBiomeParametersMixin benutzt und muss ohne Mod-Start laufen. */
object HhaBiomes {

    @JvmField val OLYMPUS: RegistryKey<Biome> = RegistryKey.of(RegistryKeys.BIOME, Hha.id("olympus"))
    @JvmField val HADES: RegistryKey<Biome> = RegistryKey.of(RegistryKeys.BIOME, Hha.id("hades"))

    /**
     * Die warmen, steinigen Gipfel der Oberwelt werden zum Olymp: Berge formt in Minecraft das Gelände-Rauschen, nicht
     * das Biom, und genau diese Einträge sitzen auf den höchsten warmen Graten. Das Hades-Biom kommt als eigener
     * Höhlen-Eintrag dazu, in der Tiefe zwischen Tiefenhöhle und Oberfläche, nur in trockenen und nicht zu kalten Gegenden.
     */
    @JvmStatic
    fun wrap(original: Consumer<Pair<MultiNoiseUtil.NoiseHypercube, RegistryKey<Biome>>>): Consumer<Pair<MultiNoiseUtil.NoiseHypercube, RegistryKey<Biome>>> {
        original.accept(
            Pair.of(
                MultiNoiseUtil.createNoiseHypercube(
                    MultiNoiseUtil.ParameterRange.of(-0.45f, 1f),
                    MultiNoiseUtil.ParameterRange.of(-1f, 0.1f),
                    MultiNoiseUtil.ParameterRange.of(-0.19f, 0.8f),
                    MultiNoiseUtil.ParameterRange.of(-1f, 1f),
                    MultiNoiseUtil.ParameterRange.of(0.55f, 1.05f),
                    MultiNoiseUtil.ParameterRange.of(-1f, 1f),
                    0f,
                ),
                HADES,
            ),
        )
        return Consumer { pair ->
            if (pair.second == BiomeKeys.STONY_PEAKS) original.accept(Pair.of(pair.first, OLYMPUS)) else original.accept(pair)
        }
    }
}

/** Registriert Strukturen und Weltgenerierungs-Features; die Daten selbst liegen unter data/hha/worldgen. */
object HhaWorldgen {

    val HADES_GATE_TAG: TagKey<Structure> = TagKey.of(RegistryKeys.STRUCTURE, Hha.id("hades_gate"))

    lateinit var TEMPLE_PIECE: StructurePieceType; private set
    lateinit var HALL_PIECE: StructurePieceType; private set
    lateinit var TEMPLE_TYPE: StructureType<OlympusTempleStructure>; private set
    lateinit var HALL_TYPE: StructureType<HadesHallStructure>; private set

    fun init() {
        TEMPLE_PIECE = Registry.register(Registries.STRUCTURE_PIECE, Hha.id("olympus_temple"), StructurePieceType { _: StructureContext, nbt: NbtCompound -> OlympusTemplePiece(nbt) })
        HALL_PIECE = Registry.register(Registries.STRUCTURE_PIECE, Hha.id("hades_hall"), StructurePieceType { _: StructureContext, nbt: NbtCompound -> HadesHallPiece(nbt) })
        TEMPLE_TYPE = Registry.register(Registries.STRUCTURE_TYPE, Hha.id("olympus_temple"), StructureType { OlympusTempleStructure.CODEC })
        HALL_TYPE = Registry.register(Registries.STRUCTURE_TYPE, Hha.id("hades_hall"), StructureType { HadesHallStructure.CODEC })
        Registry.register(Registries.FEATURE, Hha.id("olympus_surface"), OlympusSurfaceFeature())
        Registry.register(Registries.FEATURE, Hha.id("ruined_column"), RuinedColumnFeature())
        Registry.register(Registries.FEATURE, Hha.id("hades_surface"), HadesSurfaceFeature())
        Registry.register(Registries.FEATURE, Hha.id("green_spire"), GreenSpireFeature())
    }
}

// -------------------------------------------------------------------------------------------------
// Olymp
// -------------------------------------------------------------------------------------------------

/** Tempel auf dem höchsten erreichbaren Punkt rund um seinen Chunk. */
class OlympusTempleStructure(config: Config) : Structure(config) {

    /**
     * Diese Funktion läuft auch für jeden Kandidaten, den `/locate` prüft, und zwar auf dem Server-Thread. Eine
     * Geländehöhe zu berechnen ist teuer, das Biom kaum. Darum fliegen Orte ohne Olymp sofort raus, und für den Rest
     * reichen wenige Höhen im groben Raster plus ein feines Raster um den höchsten Punkt.
     */
    override fun getStructurePosition(context: Context): Optional<StructurePosition> {
        val chunk = context.chunkPos()
        val sampler = context.noiseConfig().multiNoiseSampler
        fun inBiome(x: Int, y: Int, z: Int) = context.biomePredicate().test(context.biomeSource().getBiome(x shr 2, y shr 2, z shr 2, sampler))

        var bestX = 0
        var bestZ = 0
        var bestY = Int.MIN_VALUE
        fun consider(x: Int, z: Int) {
            val y = context.chunkGenerator().getHeightOnGround(x, z, Heightmap.Type.WORLD_SURFACE_WG, context.world(), context.noiseConfig())
            if (y > bestY && inBiome(x, y, z)) {
                bestX = x
                bestY = y
                bestZ = z
            }
        }
        // Biom-Proben kosten fast nichts, also weit streuen; Höhen nur für einen Teil der Treffer berechnen.
        val hits = ArrayList<BlockPos>()
        for (dx in -3..3) {
            for (dz in -3..3) {
                val x = chunk.centerX + dx * COARSE
                val z = chunk.centerZ + dz * COARSE
                if (inBiome(x, PROBE_Y, z)) hits.add(BlockPos(x, 0, z))
            }
        }
        val stride = maxOf(1, hits.size / MAX_HEIGHTS)
        for (i in hits.indices step stride) consider(hits[i].x, hits[i].z)
        if (bestY == Int.MIN_VALUE) return Optional.empty()
        val peakX = bestX
        val peakZ = bestZ
        for (dx in -1..1) {
            for (dz in -1..1) {
                if (dx != 0 || dz != 0) consider(peakX + dx * FINE, peakZ + dz * FINE)
            }
        }
        val pos = BlockPos(bestX, (bestY + 1).coerceAtMost(context.world().topYInclusive - OlympusTemplePiece.HEIGHT), bestZ)
        return Optional.of(StructurePosition(pos) { collector -> collector.addPiece(OlympusTemplePiece(pos)) })
    }

    override fun getType(): StructureType<*> = HhaWorldgen.TEMPLE_TYPE

    companion object {
        private const val COARSE = 12
        private const val FINE = 6
        private const val MAX_HEIGHTS = 9

        /** Höhe für die schnelle Biom-Probe: Das Rauschen entscheidet hier fast wie an der Oberfläche eines Gipfels. */
        private const val PROBE_Y = 160
        val CODEC: MapCodec<OlympusTempleStructure> = createCodec { OlympusTempleStructure(it) }
    }
}

/**
 * Der Tempel wird Spalte für Spalte aus Code erzeugt, statt als NBT-Vorlage: So kann er sich Fundament und Treppe aus
 * dem echten Gelände holen und passt auf jeden Gipfel. Süden (+z) ist der Eingang.
 */
class OlympusTemplePiece : StructurePiece {

    private val center: BlockPos

    constructor(center: BlockPos) : super(
        HhaWorldgen.TEMPLE_PIECE, 0,
        BlockBox(center.x - REACH, center.y - DEPTH, center.z - REACH, center.x + REACH, center.y + HEIGHT, center.z + REACH + STAIRS),
    ) {
        this.center = center
    }

    constructor(nbt: NbtCompound) : super(HhaWorldgen.TEMPLE_PIECE, nbt) {
        center = BlockPos(nbt.getInt("cx", 0), nbt.getInt("cy", 0), nbt.getInt("cz", 0))
    }

    override fun writeNbt(context: StructureContext, nbt: NbtCompound) {
        nbt.putInt("cx", center.x)
        nbt.putInt("cy", center.y)
        nbt.putInt("cz", center.z)
    }

    override fun generate(
        world: StructureWorldAccess, structureAccessor: StructureAccessor, chunkGenerator: ChunkGenerator, random: Random,
        chunkBox: BlockBox, chunkPos: ChunkPos, pivot: BlockPos,
    ) {
        val pos = BlockPos.Mutable()
        for (x in maxOf(chunkBox.minX, center.x - REACH)..minOf(chunkBox.maxX, center.x + REACH)) {
            for (z in maxOf(chunkBox.minZ, center.z - REACH)..minOf(chunkBox.maxZ, center.z + REACH + STAIRS)) {
                val dx = x - center.x
                val dz = z - center.z
                val ground = world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, x, z)
                if (dz > RADIUS && abs(dx) <= 2) {
                    stairs(world, pos, x, z, dz - RADIUS - 1, dx, ground)
                } else if (hypot(dx.toDouble(), dz.toDouble()) <= RADIUS + 0.5) {
                    column(world, pos, x, z, dx, dz, ground)
                }
            }
        }
    }

    private fun put(world: StructureWorldAccess, pos: BlockPos.Mutable, x: Int, y: Int, z: Int, state: BlockState) {
        if (y in world.bottomY..world.topYInclusive) world.setBlockState(pos.set(x, y, z), state, 2)
    }

    /** Marmortreppe vom Plateau den Berg hinab, damit der Weg hinauf nicht nur mit Enderperlen geht. */
    private fun stairs(world: StructureWorldAccess, pos: BlockPos.Mutable, x: Int, z: Int, step: Int, dx: Int, ground: Int) {
        if (step >= STAIRS) return
        val y = center.y - 1 - step
        // Unter sich: Stützmauer bis zum Boden.
        var below = y - 1
        while (below >= ground - 1 && below > center.y - DEPTH) {
            put(world, pos, x, below, z, Blocks.SMOOTH_QUARTZ.defaultState)
            below--
        }
        val state = if (abs(dx) == 2) Blocks.CHISELED_QUARTZ_BLOCK.defaultState
        else Blocks.QUARTZ_STAIRS.defaultState.with(net.minecraft.block.StairsBlock.FACING, Direction.NORTH)
        put(world, pos, x, y, z, state)
        for (up in 1..4) put(world, pos, x, y + up, z, Blocks.AIR.defaultState)
    }

    private fun column(world: StructureWorldAccess, pos: BlockPos.Mutable, x: Int, z: Int, dx: Int, dz: Int, ground: Int) {
        val r = hypot(dx.toDouble(), dz.toDouble())
        val adx = abs(dx)
        val adz = abs(dz)
        val base = center.y

        // Fundament bis in den Fels und alles darüber freiräumen (auch Felsnadeln, die über den Gipfel ragen).
        var y = base - 1
        while (y >= ground - 1 && y > base - DEPTH) {
            val depth = base - y
            val state = when {
                depth <= 2 -> Blocks.SMOOTH_QUARTZ
                r > RADIUS - 2.0 && depth % 6 == 0 -> Blocks.CHISELED_QUARTZ_BLOCK
                (x + z + y) % 7 == 0 -> Blocks.CALCITE
                else -> Blocks.QUARTZ_BRICKS
            }
            put(world, pos, x, y, z, state.defaultState)
            y--
        }
        for (up in 1..HEIGHT) put(world, pos, x, base + up, z, Blocks.AIR.defaultState)

        // Plateau.
        val plaza = when {
            r > RADIUS - 1.0 -> Blocks.GOLD_BLOCK
            r > RADIUS - 2.5 -> Blocks.CHISELED_QUARTZ_BLOCK
            (adx + adz) % 6 == 0 && r > 18.0 -> Blocks.SEA_LANTERN
            else -> Blocks.SMOOTH_QUARTZ
        }
        put(world, pos, x, base, z, plaza.defaultState)
        if (adx > HALF_X + 2 || adz > HALF_Z + 2) return

        // Stufenbau.
        put(world, pos, x, base + 1, z, Blocks.QUARTZ_BRICKS.defaultState)
        if (adx > HALF_X + 1 || adz > HALF_Z + 1) return
        put(world, pos, x, base + 2, z, Blocks.QUARTZ_BRICKS.defaultState)
        val floor = base + 3
        if (adx > HALF_X || adz > HALF_Z) return

        // Boden der Cella: Goldring und die Sonnenbahn, die vom Ostein ums Rund führt.
        val ring = r in 6.5..7.6
        val inlay = when {
            ring -> Blocks.GOLD_BLOCK
            r < 2.6 -> Blocks.CHISELED_QUARTZ_BLOCK
            (adx == 0 || adz == 0) && r > 2.0 && r < 6.5 -> Blocks.SEA_LANTERN
            (adx + adz) % 2 == 0 -> Blocks.QUARTZ_BRICKS
            else -> Blocks.SMOOTH_QUARTZ
        }
        put(world, pos, x, floor, z, inlay.defaultState)

        // Säulen rundum.
        val onX = adx == HALF_X && adz % 5 == 0
        val onZ = adz == HALF_Z && (adx == 3 || adx == 7 || adx == HALF_X)
        if (onX || onZ) {
            for (h in 1..COLUMN) {
                val state = when (h) {
                    1 -> Blocks.CHISELED_QUARTZ_BLOCK.defaultState
                    COLUMN -> Blocks.GOLD_BLOCK.defaultState
                    else -> Blocks.QUARTZ_PILLAR.defaultState
                }
                put(world, pos, x, floor + h, z, state)
            }
        }

        // Altar und vier Opfersteine.
        if (dx == 0 && dz == 0) put(world, pos, x, floor + 1, z, HhaBlocks.OLYMPUS_ALTAR.defaultState)
        val slot = when {
            dx == STONE_X && dz == 0 -> 0
            dx == 0 && dz == STONE_Z -> 1
            dx == -STONE_X && dz == 0 -> 2
            dx == 0 && dz == -STONE_Z -> 3
            else -> -1
        }
        if (slot >= 0) put(world, pos, x, floor + 1, z, dev.henny.hha.block.pedestalState(slot))

        // Gebälk, Decke mit Oculus über dem Altar, Dachschräge.
        val beam = floor + COLUMN + 1
        val edge = adx == HALF_X || adz == HALF_Z
        put(world, pos, x, beam, z, (if (edge && (adx + adz) % 2 == 0) Blocks.GOLD_BLOCK else Blocks.SMOOTH_QUARTZ).defaultState)
        val oculus = r < 3.0
        if (oculus) {
            put(world, pos, x, beam, z, Blocks.AIR.defaultState)
        } else {
            put(world, pos, x, beam + 1, z, Blocks.QUARTZ_BRICKS.defaultState)
        }
        val rise = (HALF_X - adx) / 2
        for (h in 0..rise) {
            val roofY = beam + 2 + h
            val top = h == rise
            when {
                oculus -> Unit
                adz == HALF_Z -> put(world, pos, x, roofY, z, (if (top) Blocks.GOLD_BLOCK else Blocks.SMOOTH_QUARTZ).defaultState)
                top -> put(world, pos, x, roofY, z, (if (adx <= 1) Blocks.GOLD_BLOCK else Blocks.WAXED_CUT_COPPER).defaultState)
            }
        }
    }

    companion object {
        const val RADIUS = 24
        const val REACH = RADIUS + 2
        const val HEIGHT = 34
        const val DEPTH = 72
        const val STAIRS = 44
        const val HALF_X = 11
        const val HALF_Z = 15
        const val COLUMN = 9
        const val STONE_X = 8
        const val STONE_Z = 9
    }
}

/** Färbt die Olymp-Hänge in hellen Kalkstein und Marmor und lässt den Schnee dort oben weg. */
class OlympusSurfaceFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val world = context.world
        val chunk = ChunkPos(context.origin)
        val pos = BlockPos.Mutable()
        val mix = context.random
        for (x in chunk.startX..chunk.endX) {
            for (z in chunk.startZ..chunk.endZ) {
                val top = world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, x, z) - 1
                if (!world.getBiome(pos.set(x, top, z)).matchesKey(HhaBiomes.OLYMPUS)) continue
                for (depth in 0..3) {
                    val y = top - depth
                    pos.set(x, y, z)
                    val state = world.getBlockState(pos)
                    val swap = state.isOf(Blocks.GRASS_BLOCK) || state.isOf(Blocks.DIRT) || state.isOf(Blocks.STONE) ||
                        state.isOf(Blocks.SNOW_BLOCK) || state.isOf(Blocks.ANDESITE) || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.COARSE_DIRT)
                    if (!swap) continue
                    val choice = mix.nextInt(10)
                    val next = when {
                        depth == 0 && choice == 0 -> Blocks.DIORITE
                        choice <= 2 -> Blocks.SMOOTH_QUARTZ
                        else -> Blocks.CALCITE
                    }
                    world.setBlockState(pos, next.defaultState, 2)
                }
                val above = pos.set(x, top + 1, z)
                if (world.getBlockState(above).isOf(Blocks.SNOW)) world.setBlockState(above, Blocks.AIR.defaultState, 2)
            }
        }
        return true
    }
}

/** Halb zerfallene Säulen an den Hängen, Spuren älterer Tempel. */
class RuinedColumnFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val world = context.world
        val random = context.random
        val origin = context.origin
        val floor = world.getBlockState(origin.down())
        if (!floor.isOf(Blocks.CALCITE) && !floor.isOf(Blocks.SMOOTH_QUARTZ) && !floor.isOf(Blocks.DIORITE)) return false
        val height = 2 + random.nextInt(6)
        for (y in 0 until height) {
            val state = when {
                y == 0 -> Blocks.CHISELED_QUARTZ_BLOCK
                y == height - 1 && random.nextBoolean() -> Blocks.GOLD_BLOCK
                else -> Blocks.QUARTZ_PILLAR
            }
            world.setBlockState(origin.up(y), state.defaultState, 2)
        }
        return true
    }
}

// -------------------------------------------------------------------------------------------------
// Hades
// -------------------------------------------------------------------------------------------------

/** Überzieht die Wände des Hades-Bioms mit Schwarzstein, Basalt und Seelenerde. */
class HadesSurfaceFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val world = context.world
        val chunk = ChunkPos(context.origin)
        val pos = BlockPos.Mutable()
        val side = BlockPos.Mutable()
        val random = context.random
        for (x in chunk.startX..chunk.endX) {
            for (z in chunk.startZ..chunk.endZ) {
                for (y in world.bottomY + 5..24) {
                    pos.set(x, y, z)
                    val state = world.getBlockState(pos)
                    val rock = state.isOf(Blocks.STONE) || state.isOf(Blocks.DEEPSLATE) || state.isOf(Blocks.TUFF) ||
                        state.isOf(Blocks.ANDESITE) || state.isOf(Blocks.GRANITE) || state.isOf(Blocks.DIORITE) || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.DIRT)
                    if (!rock) continue
                    val open = world.isAir(side.set(x, y + 1, z))
                    val roof = world.isAir(side.set(x, y - 1, z))
                    val wall = !open && !roof && (world.isAir(side.set(x + 1, y, z)) || world.isAir(side.set(x - 1, y, z)) || world.isAir(side.set(x, y, z + 1)) || world.isAir(side.set(x, y, z - 1)))
                    if (!open && !roof && !wall) continue
                    if (!world.getBiome(pos).matchesKey(HhaBiomes.HADES)) continue
                    val roll = random.nextInt(100)
                    val next = when {
                        open && roll < 62 -> Blocks.SOUL_SOIL
                        open && roll < 72 -> Blocks.SOUL_SAND
                        roof && roll < 45 -> Blocks.BASALT
                        roll < 3 -> Blocks.CRYING_OBSIDIAN
                        roll < 60 -> Blocks.BLACKSTONE
                        else -> Blocks.DEEPSLATE
                    }
                    world.setBlockState(pos, next.defaultState, 2)
                }
            }
        }
        return true
    }
}

/** Die Halle tief unter dem Hades-Biom; oben führt ein Wendelschacht ans Tageslicht. */
class HadesHallStructure(config: Config) : Structure(config) {

    override fun getStructurePosition(context: Context): Optional<StructurePosition> {
        val chunk = context.chunkPos()
        val pos = BlockPos(chunk.centerX, HadesGate.FLOOR_Y, chunk.centerZ)
        val surface = context.chunkGenerator().getHeightOnGround(
            pos.x, pos.z + HadesHallPiece.SHAFT_Z, Heightmap.Type.WORLD_SURFACE_WG, context.world(), context.noiseConfig(),
        )
        return Optional.of(StructurePosition(pos) { collector -> collector.addPiece(HadesHallPiece(pos, surface)) })
    }

    override fun getType(): StructureType<*> = HhaWorldgen.HALL_TYPE

    companion object {
        val CODEC: MapCodec<HadesHallStructure> = createCodec { HadesHallStructure(it) }
    }
}

class HadesHallPiece : StructurePiece {

    private val center: BlockPos
    private val surface: Int

    constructor(center: BlockPos, surface: Int) : super(
        HhaWorldgen.HALL_PIECE, 0,
        BlockBox(center.x - REACH, center.y - 4, center.z - REACH, center.x + REACH, maxOf(center.y + DOME + 3, surface + 10), center.z + SHAFT_Z + SHAFT_R + 1),
    ) {
        this.center = center
        this.surface = surface
    }

    constructor(nbt: NbtCompound) : super(HhaWorldgen.HALL_PIECE, nbt) {
        center = BlockPos(nbt.getInt("cx", 0), nbt.getInt("cy", 0), nbt.getInt("cz", 0))
        surface = nbt.getInt("surface", 64)
    }

    override fun writeNbt(context: StructureContext, nbt: NbtCompound) {
        nbt.putInt("cx", center.x)
        nbt.putInt("cy", center.y)
        nbt.putInt("cz", center.z)
        nbt.putInt("surface", surface)
    }

    private fun put(world: StructureWorldAccess, pos: BlockPos.Mutable, x: Int, y: Int, z: Int, state: BlockState) {
        world.setBlockState(pos.set(x, y, z), state, 2)
    }

    override fun generate(
        world: StructureWorldAccess, structureAccessor: StructureAccessor, chunkGenerator: ChunkGenerator, random: Random,
        chunkBox: BlockBox, chunkPos: ChunkPos, pivot: BlockPos,
    ) {
        val pos = BlockPos.Mutable()
        for (x in maxOf(chunkBox.minX, center.x - REACH)..minOf(chunkBox.maxX, center.x + REACH)) {
            for (z in maxOf(chunkBox.minZ, center.z - REACH)..minOf(chunkBox.maxZ, center.z + SHAFT_Z + SHAFT_R + 1)) {
                val dx = x - center.x
                val dz = z - center.z
                hall(world, pos, x, z, dx, dz)
                shaft(world, pos, x, z, dx, dz)
                tunnel(world, pos, x, z, dx, dz)
                gate(world, pos, x, z, dx, dz)
            }
        }
    }

    private fun hall(world: StructureWorldAccess, pos: BlockPos.Mutable, x: Int, z: Int, dx: Int, dz: Int) {
        val r = hypot(dx.toDouble(), dz.toDouble())
        if (r > OUTER + 2.5) return
        val floor = center.y
        val inner = if (r < OUTER) DOME * sqrt(1.0 - (r / OUTER) * (r / OUTER)) else 0.0
        val roof = floor + inner.toInt()
        val shell = (x * 31 + z * 17) and 15
        for (y in floor - 3..floor + DOME + 3) {
            val state: BlockState? = when {
                y < floor -> Blocks.POLISHED_BLACKSTONE_BRICKS.defaultState
                r > OUTER -> if (y <= floor + 5) (if (shell == 0) Blocks.CRYING_OBSIDIAN else Blocks.BLACKSTONE).defaultState else null
                y > roof + 2 -> null
                y > roof -> (if (shell == 0) Blocks.CRYING_OBSIDIAN else Blocks.BLACKSTONE).defaultState
                y == floor -> floorState(r, dx, dz)
                else -> Blocks.AIR.defaultState
            }
            if (state != null) put(world, pos, x, y, z, state)
        }

        // Acht Säulen im Styx-Ring, oben brennt grünes Feuer.
        for (i in 0 until 8) {
            val a = i * Math.PI / 4.0 + Math.PI / 8.0
            val cx = (kotlin.math.cos(a) * 28.5).toInt()
            val cz = (kotlin.math.sin(a) * 28.5).toInt()
            if (abs(dx - cx) <= 1 && abs(dz - cz) <= 1) {
                for (h in 0..7) put(world, pos, x, floor + h, z, Blocks.POLISHED_BASALT.defaultState)
                if (dx == cx && dz == cz) put(world, pos, x, floor + 8, z, HhaBlocks.GREEN_FIRE.defaultState)
            }
        }
        if (dx == 0 && dz == HadesGate.ALTAR_OFFSET_Z) put(world, pos, x, floor + 1, z, HhaBlocks.HADES_ALTAR.defaultState)
    }

    private fun floorState(r: Double, dx: Int, dz: Int): BlockState {
        val angle = atan2(dz.toDouble(), dx.toDouble())
        val spoke = kotlin.math.abs(kotlin.math.sin(angle * 4.0)) * r < 0.9 && r > 6.0
        return when {
            // Der Styx: ein flacher Ring um das Kampffeld.
            r > 27.0 && abs(dx) > 2 -> Blocks.WATER
            r > 26.0 -> Blocks.CRYING_OBSIDIAN
            r in 13.0..14.0 && (abs(dx) + abs(dz)) % 4 == 0 -> Blocks.VERDANT_FROGLIGHT
            r in 4.5..5.5 || spoke -> Blocks.WARPED_WART_BLOCK
            r in 13.0..14.0 -> Blocks.GILDED_BLACKSTONE
            (abs(dx) + abs(dz)) % 2 == 0 -> Blocks.POLISHED_BLACKSTONE
            else -> Blocks.POLISHED_BLACKSTONE_BRICKS
        }.defaultState
    }

    /** Wendeltreppe: Der Boden schraubt sich neun Blöcke pro Umdrehung nach oben. */
    private fun shaft(world: StructureWorldAccess, pos: BlockPos.Mutable, x: Int, z: Int, dx: Int, dz: Int) {
        val sx = dx.toDouble()
        val sz = (dz - SHAFT_Z).toDouble()
        val r = hypot(sx, sz)
        if (r > SHAFT_R + 1.5) return
        val floor = center.y + 1
        val ground = world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, x, z)
        val top = surface + 5
        val frac = (atan2(sz, sx) + Math.PI) / (Math.PI * 2.0)
        val step = (frac * PITCH).toInt()
        for (y in floor - 1..top) {
            val state = when {
                r < 1.5 -> if (y <= surface + 2) (if ((y - floor) % 9 == 4) Blocks.SHROOMLIGHT else Blocks.POLISHED_BASALT) else Blocks.AIR
                r > SHAFT_R -> when {
                    y > ground -> null
                    (y + x + z) and 15 == 0 -> Blocks.CRYING_OBSIDIAN
                    else -> Blocks.BLACKSTONE
                }
                y == floor - 1 -> Blocks.POLISHED_BLACKSTONE_BRICKS
                (y - floor).mod(PITCH) == step -> Blocks.POLISHED_BLACKSTONE_BRICKS
                else -> Blocks.AIR
            }
            if (state != null) put(world, pos, x, y, z, state.defaultState)
        }
        // Eingang über der Erde: vier Pfeiler und Seelenfeuer.
        if (abs(sx) == 5.0 && abs(sz) == 5.0) {
            for (h in 1..6) put(world, pos, x, surface + h, z, (if (h == 6) Blocks.GILDED_BLACKSTONE else Blocks.POLISHED_BLACKSTONE_BRICKS).defaultState)
            put(world, pos, x, surface + 7, z, Blocks.SOUL_FIRE.defaultState)
        }
    }

    /** Stollen von der Halle in den Schacht. */
    private fun tunnel(world: StructureWorldAccess, pos: BlockPos.Mutable, x: Int, z: Int, dx: Int, dz: Int) {
        if (dz !in (OUTER - 6)..(SHAFT_Z - 1) || abs(dx) > 3) return
        val floor = center.y
        for (y in floor - 1..floor + 6) {
            val wall = abs(dx) == 3 || y == floor + 6 || y == floor - 1
            val state = when {
                wall -> Blocks.POLISHED_BLACKSTONE_BRICKS
                y == floor -> Blocks.POLISHED_BLACKSTONE
                else -> Blocks.AIR
            }
            put(world, pos, x, y, z, state.defaultState)
        }
    }

    /** Das schwarze Tor: eine Wand aus Torstein mit runder Öffnung; gefüllt wird sie erst, wenn der Schatten fällt. */
    private fun gate(world: StructureWorldAccess, pos: BlockPos.Mutable, x: Int, z: Int, dx: Int, dz: Int) {
        val floor = center.y
        val half = HadesGate.GATE_HALF_WIDTH
        val top = HadesGate.GATE_HEIGHT + 1
        if (dz == HadesGate.GATE_OFFSET_Z && abs(dx) <= half + 1) {
            for (h in 0..top) {
                val state = if (HadesGate.inOpening(dx, h)) Blocks.AIR else HhaBlocks.GATE_STONE
                put(world, pos, x, floor + h, z, state.defaultState)
            }
        }
        // Steg zum Tor über den Styx.
        if (dz in (HadesGate.GATE_OFFSET_Z + 1)..-26 && abs(dx) <= 3) {
            put(world, pos, x, floor, z, Blocks.POLISHED_BLACKSTONE.defaultState)
        }
    }

    companion object {
        const val OUTER = 31
        const val DOME = 34
        const val REACH = 36
        const val SHAFT_Z = 39
        const val SHAFT_R = 5
        const val PITCH = 9
    }
}

// -------------------------------------------------------------------------------------------------
// Tartaros
// -------------------------------------------------------------------------------------------------

/**
 * Riesige, schräg aus dem Boden wachsende Fahlkristalle mit einer Flechtenscheibe am Fuß. Manchmal kreuzen sich zwei,
 * manchmal schwebt ein abgebrochener Splitter darüber.
 */
class GreenSpireFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {

    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val world = context.world
        val random = context.random
        // Von der Chunkmitte aus reicht der erlaubte Bereich rundum gleich weit; so bleibt die ganze Nadel darin.
        val chunk = ChunkPos(context.origin)
        val x = chunk.centerX
        val z = chunk.centerZ
        val ground = world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, x, z)
        if (ground <= world.bottomY + 2) return false
        val base = BlockPos(x, ground, z)
        lichen(world, base, 5 + random.nextInt(4), chunk)
        val angle = random.nextDouble() * Math.PI * 2.0
        spire(world, base, angle, 18 + random.nextInt(14), 2.6 + random.nextDouble() * 1.6, 0.18 + random.nextDouble() * 0.3, chunk)
        if (random.nextInt(3) == 0) {
            // Zweite Nadel, die die erste kreuzt.
            val other = BlockPos(x + kotlin.math.cos(angle).times(4).toInt(), ground, z + kotlin.math.sin(angle).times(4).toInt())
            spire(world, other, angle + Math.PI, 14 + random.nextInt(9), 2.0 + random.nextDouble(), 0.42, chunk)
        }
        if (random.nextInt(4) == 0) {
            val shard = base.add(random.nextInt(11) - 5, 12 + random.nextInt(10), random.nextInt(11) - 5)
            spire(world, shard, random.nextDouble() * 6.28, 4 + random.nextInt(3), 1.1, 0.5, chunk)
        }
        return true
    }

    private fun allowed(chunk: ChunkPos, x: Int, z: Int) = abs(x - chunk.centerX) <= REACH && abs(z - chunk.centerZ) <= REACH

    private fun spire(world: StructureWorldAccess, base: BlockPos, angle: Double, length: Int, radius: Double, tilt: Double, chunk: ChunkPos) {
        val dx = kotlin.math.cos(angle) * tilt
        val dz = kotlin.math.sin(angle) * tilt
        val pos = BlockPos.Mutable()
        var step = -2.0
        while (step <= length) {
            val k = (step / length).coerceAtLeast(0.0)
            // Fast gleich dick bis kurz vor die Spitze, dann ein harter Schliff wie bei einem echten Kristall.
            val width = radius * (if (k < 0.72) 1.0 - k * 0.2 else 0.86 * (1.0 - (k - 0.72) / 0.28))
            val cx = base.x + dx * step
            val cy = base.y + step
            val cz = base.z + dz * step
            val reach = kotlin.math.ceil(width).toInt()
            for (ox in -reach..reach) {
                for (oz in -reach..reach) {
                    // Sechseckiger Querschnitt statt Kreis: wirkt geschliffen.
                    val hex = maxOf(abs(ox).toDouble(), (abs(ox) + 2.0 * abs(oz)) / 2.0)
                    if (hex > width) continue
                    val wx = kotlin.math.floor(cx).toInt() + ox
                    val wz = kotlin.math.floor(cz).toInt() + oz
                    if (allowed(chunk, wx, wz)) world.setBlockState(pos.set(wx, kotlin.math.floor(cy).toInt(), wz), HhaBlocks.PALE_CRYSTAL.defaultState, 2)
                }
            }
            step += 0.5
        }
    }

    /** Flache, leicht unregelmäßige Scheibe aus Kristallflechte, die dem Gelände folgt. */
    private fun lichen(world: StructureWorldAccess, center: BlockPos, radius: Int, chunk: ChunkPos) {
        val pos = BlockPos.Mutable()
        for (ox in -radius..radius) {
            for (oz in -radius..radius) {
                val wobble = radius * (0.72 + 0.28 * kotlin.math.sin(kotlin.math.atan2(oz.toDouble(), ox.toDouble()) * 3.0 + center.x))
                if (hypot(ox.toDouble(), oz.toDouble()) > wobble) continue
                val x = center.x + ox
                val z = center.z + oz
                if (!allowed(chunk, x, z)) continue
                val top = world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, x, z) - 1
                world.setBlockState(pos.set(x, top, z), HhaBlocks.CRYSTAL_MOSS.defaultState, 2)
            }
        }
    }

    private companion object {
        const val REACH = 23
    }
}
