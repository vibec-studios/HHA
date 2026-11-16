package dev.henny.hha.world

import com.mojang.serialization.MapCodec
import dev.henny.hha.HhaBlocks
import net.minecraft.block.Blocks
import net.minecraft.nbt.NbtCompound
import net.minecraft.state.property.Properties
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
import net.minecraft.world.gen.feature.DefaultFeatureConfig
import net.minecraft.world.gen.feature.Feature
import net.minecraft.world.gen.feature.util.FeatureContext
import net.minecraft.world.gen.structure.Structure
import net.minecraft.world.gen.structure.StructureType
import java.util.Optional
import kotlin.math.abs
import kotlin.math.hypot

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
 * dem echten Gelände holen und passt auf jeden Gipfel. Süden (+z) ist der Eingang. Gezeichnet wird über
 * [OlympusTemplePlan], damit der Prüflauf denselben Plan ohne Welt ansehen kann.
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
        OlympusTemplePlan.draw(WorldCanvas(world, chunkBox, random, Heightmap.Type.WORLD_SURFACE_WG), center)
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

/**
 * Der Bauplan des Tempels: ein heller Peripteros auf drei Stufen. Kannelierte Säulen mit Kapitell, darüber Architrav,
 * Fries im Rhythmus von Triglyphen und Metopen, Gesims, ein Bronzedach mit Giebeln, im vorderen Giebel ein Adler als
 * Relief und Gold nur an den Akroterien. Das Licht sitzt verdeckt im Gebälk. Die Opfersteine und der Altar bleiben,
 * wo das Rätsel sie sucht.
 */
object OlympusTemplePlan {
    private const val RADIUS = OlympusTemplePiece.RADIUS
    private const val REACH = OlympusTemplePiece.REACH
    private const val HEIGHT = OlympusTemplePiece.HEIGHT
    private const val DEPTH = OlympusTemplePiece.DEPTH
    private const val STAIRS = OlympusTemplePiece.STAIRS
    private const val HALF_X = OlympusTemplePiece.HALF_X
    private const val HALF_Z = OlympusTemplePiece.HALF_Z
    private const val COLUMN = OlympusTemplePiece.COLUMN
    private const val STONE_X = OlympusTemplePiece.STONE_X
    private const val STONE_Z = OlympusTemplePiece.STONE_Z

    private val pal = Palettes.Temple

    /** Radius des Oberlichts in Decke und Dach. */
    private const val OCULUS = 4.5

    /** Säulenstellung: an den Langseiten fünf, an den Fronten vier (die Ecken zählen doppelt). */
    private val COLUMNS: List<Pair<Int, Int>> = buildList {
        for (z in intArrayOf(-14, -7, 0, 7, 14)) for (x in intArrayOf(-10, 10)) add(x to z)
        for (x in intArrayOf(-3, 3)) for (z in intArrayOf(-14, 14)) add(x to z)
    }

    fun draw(c: BlockCanvas, center: BlockPos) {
        val base = center.y
        // Plateau, Fundament bis in den Fels und alles darüber frei.
        c.disc(center.x, center.z, RADIUS + 0.5) { x, z, r ->
            val ground = c.ground(x, z)
            var y = base - 1
            while (y >= ground - 1 && y > base - DEPTH) {
                val depth = base - y
                val state = when {
                    depth <= 2 -> Blocks.SMOOTH_QUARTZ.defaultState
                    r > RADIUS - 2.0 && depth % 6 == 0 -> Blocks.CHISELED_QUARTZ_BLOCK.defaultState
                    else -> pal.BASE(x, y, z)
                }
                c.put(x, y, z, state)
                y--
            }
            for (up in 1..HEIGHT) c.put(x, base + up, z, Kit.AIR)
            c.put(x, base, z, when {
                r > RADIUS - 1.0 -> Blocks.CHISELED_QUARTZ_BLOCK.defaultState
                r > RADIUS - 2.5 -> pal.FLOOR(x, base, z)
                r > 17.0 && Math.floorMod(Math.round(r).toInt(), 4) == 0 -> pal.BAND(x, base, z)
                else -> pal.SMOOTH(x, base, z)
            })
        }
        stairs(c, center)
        krepis(c, center)
        val floor = base + 3
        for ((dx, dz) in COLUMNS) column(c, center.x + dx, floor, center.z + dz)
        entablature(c, center, floor)
        roof(c, center, floor)
        // Altar, vier Opfersteine und vier Lichtstäbe um den Altar.
        c.block(center.x, floor + 1, center.z, HhaBlocks.OLYMPUS_ALTAR.defaultState)
        c.block(center.x + STONE_X, floor + 1, center.z, dev.henny.hha.block.pedestalState(0))
        c.block(center.x, floor + 1, center.z + STONE_Z, dev.henny.hha.block.pedestalState(1))
        c.block(center.x - STONE_X, floor + 1, center.z, dev.henny.hha.block.pedestalState(2))
        c.block(center.x, floor + 1, center.z - STONE_Z, dev.henny.hha.block.pedestalState(3))
        for (sx in intArrayOf(-2, 2)) for (sz in intArrayOf(-2, 2)) {
            c.block(center.x + sx, floor + 1, center.z + sz, Blocks.CALCITE.defaultState)
            c.block(center.x + sx, floor + 2, center.z + sz, Blocks.END_ROD.defaultState)
        }
        // Verwitterung: eine gestürzte Trommel auf dem Plateau, Moos auf den Bruchstücken.
        val fx = center.x - 17
        val fz = center.z + 9
        for (i in 0..2) c.block(fx + i, base + 1, fz, Blocks.QUARTZ_PILLAR.defaultState.with(Properties.AXIS, Direction.Axis.X))
        c.block(fx + 1, base + 2, fz, Blocks.MOSS_CARPET.defaultState)
        c.block(fx + 4, base + 1, fz + 1, Blocks.DIORITE.defaultState)
        c.block(fx + 4, base + 2, fz + 1, Blocks.MOSS_CARPET.defaultState)
        c.block(center.x + 18, base + 1, center.z - 8, Blocks.CHISELED_QUARTZ_BLOCK.defaultState)
        c.block(center.x + 18, base + 2, center.z - 8, Blocks.MOSS_CARPET.defaultState)
    }

    /** Marmortreppe vom Plateau den Berg hinab, damit der Weg hinauf nicht nur mit Enderperlen geht. */
    private fun stairs(c: BlockCanvas, center: BlockPos) {
        for (dx in -2..2) {
            for (step in 0 until STAIRS) {
                val x = center.x + dx
                val z = center.z + RADIUS + 1 + step
                if (!c.inBox(x, z)) continue
                val ground = c.ground(x, z)
                val y = center.y - 1 - step
                if (y < ground - 2) break
                var below = y - 1
                while (below >= ground - 1 && below > center.y - DEPTH) c.put(x, below--, z, Blocks.SMOOTH_QUARTZ.defaultState)
                c.put(x, y, z, if (abs(dx) == 2) Blocks.CALCITE.defaultState else Kit.stair(Blocks.QUARTZ_STAIRS, Direction.NORTH))
                for (up in 1..4) c.put(x, y + up, z, Kit.AIR)
            }
        }
    }

    /** Drei Stufen, jede Kante als Treppe; oben der Boden aus poliertem Diorit mit Kalkbändern. */
    private fun krepis(c: BlockCanvas, center: BlockPos) {
        val base = center.y
        for (level in 1..3) {
            val hx = HALF_X + 3 - level
            val hz = HALF_Z + 3 - level
            for (dx in -hx..hx) for (dz in -hz..hz) {
                val x = center.x + dx
                val z = center.z + dz
                val edge = abs(dx) == hx || abs(dz) == hz
                val y = base + level
                val state = when {
                    level == 3 && !edge -> if (abs(dz) % 4 == 2 || hypot(dx.toDouble(), dz.toDouble()) in 6.5..7.5) pal.BAND(x, y, z) else pal.FLOOR(x, y, z)
                    edge && level < 3 -> Kit.stair(Blocks.QUARTZ_STAIRS, Kit.inward(dx, dz))
                    else -> pal.BASE(x, y, z)
                }
                c.block(x, y, z, state)
            }
        }
        c.block(center.x, base + 3, center.z, Blocks.CHISELED_QUARTZ_BLOCK.defaultState)
        // Die Rampe in der Mitte der Front, damit man ohne Springen hinaufkommt.
        for (dx in -1..1) {
            for (level in 1..3) {
                c.block(center.x + dx, base + level, center.z + HALF_Z + 3 - level, Kit.stair(Blocks.SMOOTH_QUARTZ_STAIRS, Direction.NORTH))
            }
        }
    }

    /** Kannelierte Säule: Plinthe, Schaft im Kreuzquerschnitt (wirkt rund und gerillt), Kapitell mit Wulst. */
    private fun column(c: BlockCanvas, x: Int, floor: Int, z: Int) {
        for (ox in -1..1) for (oz in -1..1) c.block(x + ox, floor + 1, z + oz, if (ox == 0 || oz == 0) Blocks.CHISELED_QUARTZ_BLOCK.defaultState else Kit.stair(Blocks.QUARTZ_STAIRS, Kit.inward(ox, oz)))
        for (h in 2 until COLUMN) {
            c.block(x, floor + h, z, Blocks.QUARTZ_PILLAR.defaultState)
            for (d in Direction.Type.HORIZONTAL) c.block(x + d.offsetX, floor + h, z + d.offsetZ, Blocks.QUARTZ_PILLAR.defaultState)
        }
        for (ox in -1..1) for (oz in -1..1) {
            c.block(x + ox, floor + COLUMN, z + oz, if (ox != 0 && oz != 0) Kit.stair(Blocks.QUARTZ_STAIRS, Kit.inward(ox, oz), top = true) else Blocks.CHISELED_QUARTZ_BLOCK.defaultState)
        }
    }

    /** Architrav, Fries mit Triglyphen und Metopen, Gesims; innen eine Kassettendecke, darin verdeckt das Licht. */
    private fun entablature(c: BlockCanvas, center: BlockPos, floor: Int) {
        val beam = floor + COLUMN + 1
        for (dx in -HALF_X - 1..HALF_X + 1) {
            for (dz in -HALF_Z - 1..HALF_Z + 1) {
                val x = center.x + dx
                val z = center.z + dz
                val adx = abs(dx)
                val adz = abs(dz)
                val r = hypot(dx.toDouble(), dz.toDouble())
                val outside = adx > HALF_X || adz > HALF_Z
                val face = adx == HALF_X || adz == HALF_Z
                if (outside) {
                    // Gesims: umgedrehte Treppen, einen Block vor dem Fries.
                    c.block(x, beam + 2, z, Kit.stair(Blocks.QUARTZ_STAIRS, Kit.inward(dx, dz), top = true))
                    continue
                }
                val ring = adx >= HALF_X - 2 || adz >= HALF_Z - 2
                // Das Oberlicht über dem Altar: Durch die Öffnung fährt die Kamera der Beschwörung in den Himmel.
                if (r < OCULUS) continue
                c.block(x, beam, z, if (ring && face) pal.BAND(x, beam, z) else if (ring) Blocks.SMOOTH_QUARTZ.defaultState else Kit.AIR)
                val along = if (adx == HALF_X) dz else dx
                c.block(x, beam + 1, z, when {
                    face && Math.floorMod(along, 4) == 0 -> Blocks.QUARTZ_PILLAR.defaultState
                    face && Math.floorMod(along, 4) == 2 -> Blocks.CHISELED_QUARTZ_BLOCK.defaultState
                    face -> Blocks.SMOOTH_QUARTZ.defaultState
                    // Innen am Fries, von außen nicht zu sehen: das Licht.
                    (adx == HALF_X - 2 || adz == HALF_Z - 2) && ring && Math.floorMod(dx + dz, 4) == 0 -> pal.LIGHT
                    else -> Blocks.SMOOTH_QUARTZ.defaultState
                })
                c.block(x, beam + 2, z, if (face) Blocks.SMOOTH_QUARTZ.defaultState else if (r < OCULUS + 1.0) Blocks.CHISELED_QUARTZ_BLOCK.defaultState else Kit.slab(Blocks.SMOOTH_QUARTZ_SLAB, top = true))
            }
        }
    }

    /** Bronzedach mit First entlang der Längsachse, vorn und hinten ein Giebel; Gold an First und Traufecken. */
    private fun roof(c: BlockCanvas, center: BlockPos, floor: Int) {
        val eave = floor + COLUMN + 4
        val span = HALF_X + 1
        for (dx in -span..span) {
            val adx = abs(dx)
            val rise = (span - adx) / 2
            val half = (span - adx) % 2 == 1
            for (dz in -HALF_Z - 1..HALF_Z + 1) {
                val x = center.x + dx
                val z = center.z + dz
                val adz = abs(dz)
                if (hypot(dx.toDouble(), dz.toDouble()) < OCULUS) continue
                val gable = adz == HALF_Z
                val rake = adz == HALF_Z + 1
                for (h in 0 until rise) {
                    c.block(x, eave + h, z, if (gable) pal.BAND(x, eave + h, z) else if (rake) Kit.AIR else pal.ROOF)
                }
                val top = eave + rise
                when {
                    rake -> c.block(x, top, z, Kit.stair(Blocks.SMOOTH_QUARTZ_STAIRS, if (dx < 0) Direction.EAST else Direction.WEST))
                    dx == 0 -> c.block(x, top, z, pal.ROOF)
                    half -> c.block(x, top, z, Kit.stair(pal.ROOF_STAIR, if (dx < 0) Direction.EAST else Direction.WEST))
                    else -> c.block(x, top, z, Kit.slab(pal.ROOF_SLAB))
                }
            }
        }
        val ridge = eave + span / 2
        for (sz in intArrayOf(-1, 1)) {
            c.block(center.x, ridge + 1, center.z + sz * (HALF_Z + 1), pal.GOLD)
            for (sx in intArrayOf(-1, 1)) c.block(center.x + sx * span, eave, center.z + sz * (HALF_Z + 1), pal.GOLD)
        }
        // Relief im vorderen Giebel: der Adler des Zeus mit ausgebreiteten Schwingen.
        val gz = center.z + HALF_Z
        val gy = eave + 1
        val relief = listOf(0 to 0, 0 to 1, 0 to 2, 0 to 3, -1 to 1, 1 to 1, -2 to 2, 2 to 2, -3 to 2, 3 to 2, -4 to 1, 4 to 1, -5 to 1, 5 to 1)
        for ((rx, ry) in relief) c.block(center.x + rx, gy + ry, gz + 1, Blocks.CHISELED_QUARTZ_BLOCK.defaultState)
        c.block(center.x, gy + 4, gz + 1, pal.GOLD)
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
