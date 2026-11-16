package dev.henny.hha.world

import com.mojang.serialization.MapCodec
import dev.henny.hha.HhaBlocks
import dev.henny.hha.logic.world.HadesGate
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.nbt.NbtCompound
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

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
        BlockBox(center.x - REACH, center.y - 4, center.z - REACH, center.x + REACH, maxOf(center.y + DOME + 3, surface + 26), center.z + SHAFT_Z + SHAFT_R + SHRINE_EXTRA),
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

    override fun generate(
        world: StructureWorldAccess, structureAccessor: StructureAccessor, chunkGenerator: ChunkGenerator, random: Random,
        chunkBox: BlockBox, chunkPos: ChunkPos, pivot: BlockPos,
    ) {
        HadesHallPlan.draw(WorldCanvas(world, chunkBox, random, Heightmap.Type.WORLD_SURFACE_WG), center, surface)
    }

    companion object {
        const val OUTER = 31
        const val DOME = 34
        const val REACH = 36
        const val SHAFT_Z = 39
        const val SHAFT_R = 5
        const val PITCH = 9
        const val SHRINE_EXTRA = 20
    }
}

/**
 * Der Bauplan der Halle des Fährmanns: Kuppel aus Schwarzstein mit Basaltrippen, ein Ring aus Styx-Wasser mit
 * Prismarinufer, im Boden eine goldene Münze mit Charons Barke, an den acht Säulen Seelenlaternen.
 */
object HadesHallPlan {
    private const val OUTER = HadesHallPiece.OUTER
    private const val DOME = HadesHallPiece.DOME
    private const val REACH = HadesHallPiece.REACH
    private const val SHAFT_Z = HadesHallPiece.SHAFT_Z
    private const val SHAFT_R = HadesHallPiece.SHAFT_R
    private const val PITCH = HadesHallPiece.PITCH
    private const val SHRINE_EXTRA = HadesHallPiece.SHRINE_EXTRA
    private val pal = Palettes.Hall
    private val dark = Palettes.Palace

    /** Die Wächter am Schacht halten keine Flamme. */
    private val unlit = StatueStyle(bowl = null, flame = null)

    fun draw(c: BlockCanvas, center: BlockPos, surface: Int) {
        for (x in maxOf(c.minX, center.x - REACH)..minOf(c.maxX, center.x + REACH)) {
            for (z in maxOf(c.minZ, center.z - REACH)..minOf(c.maxZ, center.z + SHAFT_Z + SHAFT_R + SHRINE_EXTRA)) {
                val dx = x - center.x
                val dz = z - center.z
                hall(c, center, x, z, dx, dz)
                shaft(c, center, surface, x, z, dx, dz)
                tunnel(c, center, x, z, dx, dz)
                gate(c, center, x, z, dx, dz)
            }
        }
        decorate(c, center)
        shrine(c, center, surface)
    }

    /** Was aus dem nackten Rohbau eine Halle macht: Gewölberippen, Kronleuchter, Säulen, Nischen, Torrahmen, Altarweg. */
    private fun decorate(c: BlockCanvas, center: BlockPos) {
        val y = center.y
        // Acht Rippen aus poliertem Basalt laufen im Scheitel zusammen.
        c.disc(center.x, center.z, OUTER.toDouble()) { x, z, r ->
            val a = atan2((z - center.z).toDouble(), (x - center.x).toDouble())
            val rib = abs(Math.IEEEremainder(a * 4.0 / Math.PI, 1.0)) * r * Math.PI / 4.0 < 1.0
            if (rib && r > 4.0) {
                val roof = y + (DOME * sqrt(1.0 - (r / OUTER) * (r / OUTER))).toInt()
                c.put(x, roof, z, pal.RIB(x, roof, z))
            }
        }
        // Kronleuchter im Scheitel: ein goldener Ring an Ketten, darunter Seelenlaternen.
        val ring = y + 18
        c.tube(center.x, center.z, ring, ring, 7.0, 1.4) { _, _, _ -> pal.COIN }
        for (i in 0 until 8) {
            val a = i * Math.PI / 4.0
            val px = center.x + (cos(a) * 6.3).toInt()
            val pz = center.z + (sin(a) * 6.3).toInt()
            for (h in ring + 1..y + DOME - 1) c.block(px, h, pz, Kit.chain())
            for (h in 1..2) c.block(px, ring - h, pz, Kit.chain())
            c.block(px, ring - 3, pz, Kit.lantern())
        }
        // Säulen im Styx-Ring: Sockel, Kapitell, oben grünes Feuer, zur Mitte hin eine Seelenlaterne.
        for (i in 0 until 8) {
            val a = i * Math.PI / 4.0 + Math.PI / 8.0
            val cx = center.x + (cos(a) * 28.5).toInt()
            val cz = center.z + (sin(a) * 28.5).toInt()
            c.column(cx, y, cz, 8)
            c.block(cx, y + 10, cz, HhaBlocks.GREEN_FIRE.defaultState)
            val lx = cx - Math.round(cos(a) * 2.0).toInt()
            val lz = cz - Math.round(sin(a) * 2.0).toInt()
            c.block(lx, y + 7, lz, Kit.lantern())
        }
        // Nischen am Fuß der Kuppel, am Ufer des Styx: In jeder liegt ein Schädel.
        for (i in 0 until 24) {
            val a = i * Math.PI / 12.0 + Math.PI / 24.0
            val nx = center.x + Math.round(cos(a) * (OUTER + 0.6)).toInt()
            val nz = center.z + Math.round(sin(a) * (OUTER + 0.6)).toInt()
            if (abs(nx - center.x) <= 3) continue
            c.block(nx, y + 1, nz, skull(Math.round((Math.toDegrees(a) + 90.0) / 22.5).toInt(), wither = i % 5 == 0))
            c.block(nx, y + 2, nz, Kit.AIR)
            c.block(nx, y + 3, nz, pal.WALL(nx, y + 3, nz))
        }
        val half = HadesGate.GATE_HALF_WIDTH + 1
        val gz = center.z + HadesGate.GATE_OFFSET_Z
        for (dx in -half..half) {
            for (h in 0..HadesGate.GATE_HEIGHT + 1) {
                if (HadesGate.inOpening(dx, h)) continue
                val near = (-1..1).any { ox -> (-1..1).any { oh -> HadesGate.inOpening(dx + ox, h + oh) } }
                if (near) c.block(center.x + dx, y + h, gz + 1, pal.TRIM(center.x + dx, y + h, gz + 1))
            }
        }
        for (side in intArrayOf(-1, 1)) c.statue(center.x + side * 9, y, gz + 3, Direction.SOUTH, 11)
        val az = center.z + HadesGate.ALTAR_OFFSET_Z
        c.disc(center.x, az, 3.6) { x, z, r ->
            c.put(x, y, z, if (r > 2.6) pal.TRIM(x, y, z) else Blocks.POLISHED_DEEPSLATE.defaultState)
        }
        for (side in intArrayOf(-1, 1)) c.brazier(center.x + side * 4, y + 1, az, 2)
        for (dz in (OUTER - 4)..(SHAFT_Z - 2) step 4) {
            val z = center.z + dz
            for (side in intArrayOf(-2, 2)) {
                for (h in 1..4) c.block(center.x + side, y + h, z, dark.SHAFT(center.x + side, y + h, z))
            }
            for (dx in -2..2) c.block(center.x + dx, y + 5, z, if (abs(dx) == 2) pal.TRIM(center.x + dx, y + 5, z) else Blocks.POLISHED_BLACKSTONE_BRICK_SLAB.defaultState)
            c.block(center.x, y + 4, z, Kit.lantern())
        }
    }

    /** Der Eingang an der Oberfläche: ein runder Tempel über dem Schacht, schwarz wie ein Loch in der Landschaft. */
    private fun shrine(c: BlockCanvas, center: BlockPos, surface: Int) {
        val sz = center.z + SHAFT_Z
        val floor = surface
        c.disc(center.x, sz, 11.5) { x, z, r ->
            if (r > SHAFT_R + 0.6) {
                c.footing(x, z, floor - 1, dark.PLINTH, 24)
                c.put(x, floor, z, if (r > 10.4) Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Kit.inward(x - center.x, z - sz)) else if (r > 9.4) pal.TRIM(x, floor, z) else dark.PLINTH(x, floor, z))
                for (h in 1..4) c.put(x, floor + h, z, Kit.AIR)
            }
        }
        for (i in 0 until 8) {
            val a = i * Math.PI / 4.0 + Math.PI / 8.0
            val px = center.x + (cos(a) * 8.5).toInt()
            val pz = sz + (sin(a) * 8.5).toInt()
            c.column(px, floor, pz, 9)
        }
        val beam = floor + 11
        c.disc(center.x, sz, 10.6) { x, z, r ->
            if (r > 7.0) {
                c.put(x, beam, z, if (r > 9.6) Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, Kit.inward(x - center.x, z - sz), top = true) else pal.WALL(x, beam, z))
            }
            if (r > 4.0) {
                val rise = (sqrt((1.0 - (r / 10.6) * (r / 10.6)).coerceAtLeast(0.0)) * 6.0).toInt()
                c.put(x, beam + 1 + rise, z, if (r > 9.0) pal.RIB(x, beam + 1, z) else Blocks.DEEPSLATE_TILES.defaultState)
            }
        }
        for (i in 0 until 8) {
            val a = i * Math.PI / 4.0
            c.block(center.x + (cos(a) * 4.2).toInt(), beam + 7, sz + (sin(a) * 4.2).toInt(), HhaBlocks.GREEN_FIRE.defaultState)
        }
        for (side in intArrayOf(-1, 1)) {
            val sx = center.x + side * 6
            if (!c.inBox(sx, sz + 14)) continue
            val top = c.ground(sx, sz + 14)
            c.statue(sx, top - 1, sz + 14, Direction.SOUTH, 10, unlit)
        }
    }

    private fun hall(c: BlockCanvas, center: BlockPos, x: Int, z: Int, dx: Int, dz: Int) {
        val r = hypot(dx.toDouble(), dz.toDouble())
        if (r > OUTER + 2.5) return
        val floor = center.y
        val inner = if (r < OUTER) DOME * sqrt(1.0 - (r / OUTER) * (r / OUTER)) else 0.0
        val roof = floor + inner.toInt()
        for (y in floor - 3..floor + DOME + 3) {
            val state: BlockState? = when {
                y < floor -> Blocks.POLISHED_BLACKSTONE_BRICKS.defaultState
                r > OUTER -> if (y <= floor + 5) (if (y <= floor + 3) pal.WALL(x, y, z) else pal.SHELL(x, y, z)) else null
                y > roof + 2 -> null
                y > roof -> pal.SHELL(x, y, z)
                y == floor -> floorState(r, dx, dz)
                else -> Blocks.AIR.defaultState
            }
            if (state != null) c.put(x, y, z, state)
        }
        for (i in 0 until 8) {
            val a = i * Math.PI / 4.0 + Math.PI / 8.0
            val cx = (cos(a) * 28.5).toInt()
            val cz = (sin(a) * 28.5).toInt()
            if (abs(dx - cx) <= 1 && abs(dz - cz) <= 1) {
                for (h in 0..7) c.put(x, floor + h, z, Blocks.POLISHED_BASALT.defaultState)
                if (dx == cx && dz == cz) c.put(x, floor + 8, z, HhaBlocks.GREEN_FIRE.defaultState)
            }
        }
        if (dx == 0 && dz == HadesGate.ALTAR_OFFSET_Z) c.put(x, floor + 1, z, HhaBlocks.HADES_ALTAR.defaultState)
    }

    /**
     * Der Boden: Ringe aus poliertem Schwarzstein und Ziegeln im Wechsel, darin die Münze des Fährmanns (Rand, Perlkranz
     * und die Barke mit Charon und seinem Ruder in Gold). Am Rand das Prismarinufer, dahinter der Styx.
     */
    private fun floorState(r: Double, dx: Int, dz: Int): BlockState {
        val angle = atan2(dz.toDouble(), dx.toDouble())
        return when {
            r > 27.0 && abs(dx) > 2 -> Blocks.WATER.defaultState
            r > 26.0 -> pal.SHORE(dx, 0, dz)
            r in 21.0..22.2 -> pal.COIN
            r in 19.3..20.3 && Math.floorMod(Math.round(angle * 36.0 / Math.PI).toInt(), 2) == 0 -> pal.COIN
            r < 18.0 && coinEmblem(dx.toDouble(), dz.toDouble()) -> pal.COIN
            Math.floorMod(Math.floor(r / 2.0).toInt(), 2) == 0 -> Blocks.POLISHED_BLACKSTONE.defaultState
            else -> Blocks.POLISHED_BLACKSTONE_BRICKS.defaultState
        }
    }

    /** Die Prägung der Münze: Charons Barke mit hochgezogenen Steven, der Fährmann aufrecht, das Ruder schräg im Wasser. */
    private fun coinEmblem(x: Double, z: Double): Boolean {
        if (hypot(x - 1.0, z + 7.6) <= 1.3) return true
        for (l in EMBLEM) {
            val vx = l[2] - l[0]
            val vz = l[3] - l[1]
            val t = (((x - l[0]) * vx + (z - l[1]) * vz) / (vx * vx + vz * vz)).coerceIn(0.0, 1.0)
            if (hypot(x - (l[0] + vx * t), z - (l[1] + vz * t)) <= 0.55) return true
        }
        return false
    }

    private val EMBLEM = listOf(
        // Deck und Kiel
        doubleArrayOf(-9.0, 2.0, 9.0, 2.0), doubleArrayOf(-9.0, 2.0, -5.0, 5.0), doubleArrayOf(-5.0, 5.0, 5.0, 5.0), doubleArrayOf(5.0, 5.0, 9.0, 2.0),
        // Steven
        doubleArrayOf(9.0, 2.0, 11.0, -1.0), doubleArrayOf(11.0, -1.0, 10.0, -3.0), doubleArrayOf(-9.0, 2.0, -11.0, 0.0),
        // Charon und sein Ruder
        doubleArrayOf(1.0, 1.0, 1.0, -6.0), doubleArrayOf(1.0, -4.0, -2.0, -2.0), doubleArrayOf(5.0, -8.0, -4.0, 9.0),
        // Wellen unter der Barke
        doubleArrayOf(-8.0, 8.0, -4.0, 7.0), doubleArrayOf(-4.0, 7.0, 0.0, 8.0), doubleArrayOf(0.0, 8.0, 4.0, 7.0), doubleArrayOf(4.0, 7.0, 8.0, 8.0),
    )

    /** Wendeltreppe: Der Boden schraubt sich neun Blöcke pro Umdrehung nach oben. */
    private fun shaft(c: BlockCanvas, center: BlockPos, surface: Int, x: Int, z: Int, dx: Int, dz: Int) {
        val sx = dx.toDouble()
        val sz = (dz - SHAFT_Z).toDouble()
        val r = hypot(sx, sz)
        if (r > SHAFT_R + 1.5) return
        val floor = center.y + 1
        val ground = c.ground(x, z)
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
            if (state != null) c.put(x, y, z, state.defaultState)
        }
    }

    /** Stollen von der Halle in den Schacht. */
    private fun tunnel(c: BlockCanvas, center: BlockPos, x: Int, z: Int, dx: Int, dz: Int) {
        if (dz !in (OUTER - 6)..(SHAFT_Z - 1) || abs(dx) > 3) return
        val floor = center.y
        for (y in floor - 1..floor + 6) {
            val wall = abs(dx) == 3 || y == floor + 6 || y == floor - 1
            val state = when {
                wall -> Blocks.POLISHED_BLACKSTONE_BRICKS
                y == floor -> Blocks.POLISHED_BLACKSTONE
                else -> Blocks.AIR
            }
            c.put(x, y, z, state.defaultState)
        }
    }

    /** Das schwarze Tor: eine Wand mit runder Öffnung; gefüllt wird sie erst, wenn der Schatten fällt. */
    private fun gate(c: BlockCanvas, center: BlockPos, x: Int, z: Int, dx: Int, dz: Int) {
        val floor = center.y
        val half = HadesGate.GATE_HALF_WIDTH
        val top = HadesGate.GATE_HEIGHT + 1
        if (dz == HadesGate.GATE_OFFSET_Z && abs(dx) <= half + 1) {
            for (h in 0..top) {
                val state = if (HadesGate.inOpening(dx, h)) Blocks.AIR else Blocks.POLISHED_BLACKSTONE_BRICKS
                c.put(x, floor + h, z, state.defaultState)
            }
        }
        if (dz in (HadesGate.GATE_OFFSET_Z + 1)..-26 && abs(dx) <= 3) {
            c.put(x, floor, z, Blocks.POLISHED_BLACKSTONE.defaultState)
        }
    }
}
