package dev.henny.hha.world

import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.entity.EntityType
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.random.Random
import net.minecraft.world.Heightmap
import net.minecraft.world.biome.Biome
import net.minecraft.world.gen.feature.DefaultFeatureConfig
import net.minecraft.world.gen.feature.Feature
import net.minecraft.world.gen.feature.util.FeatureContext
import dev.henny.hha.Hha
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Die Biome des Tartaros. Wer etwas je Biom unterscheiden will, fragt hier nach. */
object TartarosBiomes {
    val ASPHODEL: RegistryKey<Biome> = RegistryKey.of(RegistryKeys.BIOME, Hha.id("asphodel"))
    val PHLEGETHON: RegistryKey<Biome> = RegistryKey.of(RegistryKeys.BIOME, Hha.id("phlegethon"))
    val KOKYTOS: RegistryKey<Biome> = RegistryKey.of(RegistryKeys.BIOME, Hha.id("kokytos"))
    val EREBOS: RegistryKey<Biome> = RegistryKey.of(RegistryKeys.BIOME, Hha.id("erebos"))
    val MARSH: RegistryKey<Biome> = RegistryKey.of(RegistryKeys.BIOME, Hha.id("styx_marsh"))
}

/** Zeichnet über den Baukasten; die Leinwand schneidet auf den Bereich zu, den ein Feature schreiben darf. */
private fun canvas(context: FeatureContext<DefaultFeatureConfig>): BlockCanvas {
    val chunk = ChunkPos(context.origin)
    val box = BlockBox(chunk.centerX - REACH, context.world.bottomY, chunk.centerZ - REACH, chunk.centerX + REACH, context.world.topYInclusive, chunk.centerZ + REACH)
    return WorldCanvas(context.world, box, context.random)
}

private const val REACH = 22

/** Oberster fester Block einer Spalte im Gelände. */
private fun BlockCanvas.surfaceY(x: Int, z: Int): Int = ground(x, z) - 1

/**
 * Was der Wind eines Bioms in eine Ruine trägt: Asche, Moos, Schnee, Schlamm. [onTop] legt den Block als Decke auf,
 * sonst ersetzt er Pflastersteine.
 */
enum class RuinDrift(val state: () -> BlockState, val onTop: Boolean) {
    ASH({ Blocks.LIGHT_GRAY_CONCRETE_POWDER.defaultState }, false),
    MOSS({ Blocks.PALE_MOSS_CARPET.defaultState }, true),
    CINDER({ Blocks.BASALT.defaultState }, false),
    SNOW({ Blocks.SNOW.defaultState }, true),
    SCULK({ Blocks.SCULK.defaultState }, false),
    MUD({ Blocks.MUD.defaultState }, false);

    companion object {
        fun of(biome: RegistryEntry<Biome>): RuinDrift = when {
            biome.matchesKey(TartarosBiomes.ASPHODEL) -> MOSS
            biome.matchesKey(TartarosBiomes.PHLEGETHON) -> CINDER
            biome.matchesKey(TartarosBiomes.KOKYTOS) -> SNOW
            biome.matchesKey(TartarosBiomes.EREBOS) -> SCULK
            biome.matchesKey(TartarosBiomes.MARSH) -> MUD
            else -> ASH
        }
    }
}

/** Ruine einer älteren, hellen Welt: Säulentrommeln aus Quarz, Kalk und Diorit, die im dunklen Ödland auffallen. */
class TartarosRuinFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val world = context.world
        val chunk = ChunkPos(context.origin)
        val x = chunk.centerX
        val z = chunk.centerZ
        val y = world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, x, z) - 1
        if (y < 50) return false
        RuinPlan.draw(canvas(context), x, y, z, context.random, RuinDrift.of(world.getBiome(BlockPos(x, y, z))))
        return true
    }
}

/** Der Bauplan der Ruine, getrennt vom Feature, damit der Prüflauf ihn ohne Welt zeichnen kann. */
object RuinPlan {

    fun draw(c: BlockCanvas, x: Int, y: Int, z: Int, random: Random, drift: RuinDrift) {
        val pal = Palettes.Ruin
        val radius = 8 + random.nextInt(5)
        // Pflaster aus Steinziegeln, zur Hälfte vom Boden gefressen; am Rand ein Band aus gemeißeltem Stein.
        c.disc(x, z, radius + 1.5) { px, pz, r ->
            val n = Kit.noise(px, y, pz)
            if (n % 3 == 0 && r > 3.0) return@disc
            val top = c.surfaceY(px, pz)
            if (abs(top - y) > 3) return@disc
            val state = when {
                !drift.onTop && n % 7 == 1 -> drift.state()
                r > radius -> pal.BORDER(px, top, pz)
                else -> pal.FLOOR(px, top, pz)
            }
            c.put(px, top, pz, state)
            if (drift.onTop && n % 5 == 2) c.put(px, top + 1, pz, drift.state())
        }
        val count = 6 + random.nextInt(4)
        val heights = IntArray(count)
        for (i in 0 until count) {
            val a = i * Math.PI * 2.0 / count
            val px = x + Math.round(cos(a) * radius).toInt()
            val pz = z + Math.round(sin(a) * radius).toInt()
            val top = c.surfaceY(px, pz)
            val full = 7 + random.nextInt(3)
            val height = when (random.nextInt(5)) {
                0 -> 1
                1 -> 2 + random.nextInt(3)
                else -> full
            }
            heights[i] = if (height == full) full else 0
            ruinColumn(c, px, top, pz, height, height == full, pal)
            if (height < full) {
                // Die gestürzten Trommeln liegen in Fallrichtung im Staub, mit Lücken zwischen den Stücken.
                val fall = Direction.Type.HORIZONTAL.random(random)
                var at = 2
                for (drum in 0 until 1 + random.nextInt(3)) {
                    val length = 2 + random.nextInt(2)
                    for (n in 0 until length) {
                        val dx = px + fall.offsetX * (at + n)
                        val dz = pz + fall.offsetZ * (at + n)
                        if (c.inBox(dx, dz)) c.block(dx, c.surfaceY(dx, dz) + 1, dz, Blocks.QUARTZ_PILLAR.defaultState.with(Properties.AXIS, fall.axis))
                    }
                    at += length + 1
                }
                if (drift.onTop) c.block(px, top + height + 1, pz, drift.state())
            }
        }
        // Wo zwei Säulen noch ganz stehen, liegt der Architrav noch auf.
        for (i in 0 until count) {
            val j = (i + 1) % count
            if (heights[i] == 0 || heights[j] == 0 || heights[i] != heights[j]) continue
            val a0 = i * Math.PI * 2.0 / count
            val a1 = j * Math.PI * 2.0 / count
            val x0 = x + Math.round(cos(a0) * radius).toInt()
            val z0 = z + Math.round(sin(a0) * radius).toInt()
            val x1 = x + Math.round(cos(a1) * radius).toInt()
            val z1 = z + Math.round(sin(a1) * radius).toInt()
            val beam = c.surfaceY(x0, z0) + heights[i] + 2
            c.line(x0, beam, z0, x1, beam, z1, Blocks.POLISHED_DIORITE.defaultState, Blocks.CALCITE.defaultState)
            if (drift.onTop) c.line(x0, beam + 1, z0, x1, beam + 1, z1, drift.state())
        }
        // Gebeine der Pilger, die hier zuletzt gebetet haben.
        for (i in 0 until 3 + random.nextInt(3)) {
            val bx = x + random.nextInt(radius * 2 - 1) - radius + 1
            val bz = z + random.nextInt(radius * 2 - 1) - radius + 1
            if (c.inBox(bx, bz)) c.block(bx, c.surfaceY(bx, bz) + 1, bz, Blocks.BONE_BLOCK.defaultState.with(Properties.AXIS, if (random.nextBoolean()) Direction.Axis.X else Direction.Axis.Z))
        }
        if (random.nextInt(3) == 0) {
            // Altar aus gemeißeltem Stein; die Truhe darauf hat noch keiner geholt.
            c.prism(x - 1, y + 1, z - 1, x + 1, y + 1, z + 1) { _, _, _ -> Blocks.CHISELED_STONE_BRICKS.defaultState }
            for (d in Direction.Type.HORIZONTAL) c.block(x + d.offsetX * 2, y + 1, z + d.offsetZ * 2, Kit.stair(Blocks.STONE_BRICK_STAIRS, d.opposite))
            c.chest(x, y + 2, z)
            if (random.nextBoolean()) c.spawner(x, y, z, EntityType.SKELETON)
        } else {
            c.statue(x, y, z, Direction.Type.HORIZONTAL.random(random), 10 + random.nextInt(4), Palettes.Ruin.STATUE)
        }
    }

    /** Kannelierte Säule: Fuß aus Diorit, Schaft aus Quarztrommeln, oben Kalk. Abgebrochene enden in rissigem Diorit. */
    private fun ruinColumn(c: BlockCanvas, x: Int, top: Int, z: Int, height: Int, whole: Boolean, pal: Palettes.Ruin) {
        c.block(x, top, z, Blocks.POLISHED_DIORITE.defaultState)
        for (d in Direction.Type.HORIZONTAL) c.block(x + d.offsetX, top + 1, z + d.offsetZ, Kit.stair(Blocks.POLISHED_DIORITE_STAIRS, d.opposite))
        for (h in 1..height) c.block(x, top + h, z, if (h == 1) Blocks.POLISHED_DIORITE.defaultState else Blocks.QUARTZ_PILLAR.defaultState)
        if (whole) {
            c.block(x, top + height + 1, z, Blocks.CALCITE.defaultState)
            for (d in Direction.Type.HORIZONTAL) c.block(x + d.offsetX, top + height + 1, z + d.offsetZ, Kit.stair(Blocks.POLISHED_DIORITE_STAIRS, d.opposite, top = true))
        } else if (height > 1) {
            c.block(x, top + height, z, pal.BROKEN(x, top + height, z))
        }
    }
}

/** Das Gerippe eines Riesen: Rippenbögen, die aus dem Boden ragen, mit Wirbelsäule und Schädel. Titanen waren groß. */
class TartarosCarcassFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val world = context.world
        val chunk = ChunkPos(context.origin)
        val x = chunk.centerX
        val z = chunk.centerZ
        val y = world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, x, z) - 1
        if (y < 50) return false
        val random = context.random
        val c = canvas(context)
        val alongX = random.nextBoolean()
        val ribs = 6 + random.nextInt(4)
        val span = 7 + random.nextInt(4)
        fun at(along: Int, across: Int) = if (alongX) x + along to z + across else x + across to z + along
        for (i in -ribs..ribs) {
            val along = i * 3
            val (sx, sz) = at(along, 0)
            val spine = y + 1 - (abs(i) / 3)
            c.block(sx, spine, sz, Blocks.BONE_BLOCK.defaultState)
            c.block(if (alongX) sx + 1 else sx, spine, if (alongX) sz else sz + 1, Blocks.BONE_BLOCK.defaultState)
            if (i % 2 == 0 && abs(i) <= ribs - 1) {
                val size = span - abs(i) / 2
                for (side in intArrayOf(-1, 1)) {
                    for (t in 0..16) {
                        val a = t / 16.0 * Math.PI * 0.72
                        val across = side * (sin(a) * size).toInt()
                        val up = (cos(a - 0.5) * size).toInt()
                        val (px, pz) = at(along, across)
                        if (!c.inBox(px, pz)) continue
                        val ground = c.ground(px, pz) - 1
                        val py = spine + up
                        if (py >= ground) c.block(px, py, pz, Kit.BONE(px, py, pz))
                    }
                }
            }
        }
        // Der Schädel am Ende, zwei leere Höhlen und ein Unterkiefer im Staub.
        val (hx, hz) = at(ribs * 3 + 5, 0)
        val hy = (if (c.inBox(hx, hz)) c.ground(hx, hz) else y + 1) + 2
        c.blob(hx, hy, hz, 4.2, 3.6, 4.2, Kit.BONE)
        val (ex1, ez1) = at(ribs * 3 + 8, -2)
        val (ex2, ez2) = at(ribs * 3 + 8, 2)
        c.blob(ex1, hy + 1, ez1, 1.2, 1.2, 1.2, { _, _, _ -> Blocks.CRYING_OBSIDIAN.defaultState })
        c.blob(ex2, hy + 1, ez2, 1.2, 1.2, 1.2, { _, _, _ -> Blocks.CRYING_OBSIDIAN.defaultState })
        return true
    }
}

/** Abgestorbene Blasseiche, deren Äste in Moos hängen: die einzige Vegetation, die hier noch steht. */
class TartarosDeadTreeFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val world = context.world
        val origin = context.origin
        val random = context.random
        val floor = world.getBlockState(origin.down())
        if (floor.isAir || !floor.fluidState.isEmpty) return false
        val chunk = ChunkPos(origin)
        val c = canvas(context)
        val x = chunk.centerX + random.nextInt(9) - 4
        val z = chunk.centerZ + random.nextInt(9) - 4
        val y = world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, x, z)
        if (y < 49 || !world.getBlockState(BlockPos(x, y - 1, z)).fluidState.isEmpty) return false
        val height = 6 + random.nextInt(7)
        val lean = random.nextDouble() * 6.28
        val tipX = x + (cos(lean) * 3.0).toInt()
        val tipZ = z + (sin(lean) * 3.0).toInt()
        val wood: Pal = { _, _, _ -> Blocks.PALE_OAK_WOOD.defaultState }
        c.limb(x, y - 1, z, tipX, y + height, tipZ, 1.1, 0.6, wood)
        for (i in 0 until 3 + random.nextInt(3)) {
            val a = lean + i * 1.7 + random.nextDouble()
            val length = 3 + random.nextInt(5)
            val bx = tipX + (cos(a) * length).toInt()
            val bz = tipZ + (sin(a) * length).toInt()
            val by = y + height - 2 + random.nextInt(4)
            c.limb(tipX, y + height - 1 - i, tipZ, bx, by, bz, 0.5, 0.4, wood)
            for (drop in 1..1 + random.nextInt(3)) c.block(bx, by - drop, bz, Blocks.PALE_HANGING_MOSS.defaultState)
        }
        return true
    }
}

/** Im Kokytos stehen die, die zu lange geklagt haben: Schatten, im Eis eingeschlossen. */
class ColdShadeFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val world = context.world
        val chunk = ChunkPos(context.origin)
        val random = context.random
        val x = chunk.centerX + random.nextInt(9) - 4
        val z = chunk.centerZ + random.nextInt(9) - 4
        val y = world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, x, z)
        if (y < 40) return false
        val c = canvas(context)
        val ice: Pal = { px, py, pz -> if (Kit.noise(px, py, pz) % 5 == 0) Blocks.BLUE_ICE.defaultState else Blocks.PACKED_ICE.defaultState }
        c.blob(x, y + 2, z, 2.4, 4.0, 2.4, ice)
        // Innen die Gestalt, dunkel durch das Eis.
        for (h in 0..2) c.block(x, y + h, z, Blocks.BLACKSTONE.defaultState)
        c.block(x, y + 3, z, Blocks.CRYING_OBSIDIAN.defaultState)
        return true
    }
}

/** Phlegethon: niedrige Basaltschlote in Gruppen, in deren Mündung die Glut sitzt. */
class PhlegethonVentFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val chunk = ChunkPos(context.origin)
        val random = context.random
        val c = canvas(context)
        val basalt: Pal = { px, py, pz ->
            when (Kit.noise(px, py, pz) % 6) {
                0 -> Blocks.SMOOTH_BASALT.defaultState
                1 -> Blocks.BLACKSTONE.defaultState
                else -> Blocks.BASALT.defaultState
            }
        }
        for (i in 0 until 2 + random.nextInt(4)) {
            val x = chunk.centerX + random.nextInt(11) - 5
            val z = chunk.centerZ + random.nextInt(11) - 5
            val y = c.ground(x, z)
            if (y < 50) continue
            val height = 2 + random.nextInt(3)
            c.taper(x, z, y - 1, y + height - 1, 1.9, 1.05, basalt)
            // Die Mündung: ein Block Glut, eingelassen, darüber frei.
            c.block(x, y + height - 1, z, Blocks.MAGMA_BLOCK.defaultState)
            c.block(x, y + height, z, Kit.AIR)
        }
        return true
    }
}

/** Kokytos: Eissplitter, zwei bis fünf Blöcke hoch und schief, als hätte der Frost sie aus dem Boden gedrückt. */
class KokytosShardFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val chunk = ChunkPos(context.origin)
        val random = context.random
        val c = canvas(context)
        val ice: Pal = { px, py, pz -> if (Kit.noise(px, py, pz) % 4 == 0) Blocks.BLUE_ICE.defaultState else Blocks.PACKED_ICE.defaultState }
        for (i in 0 until 1 + random.nextInt(4)) {
            val x = chunk.centerX + random.nextInt(9) - 4
            val z = chunk.centerZ + random.nextInt(9) - 4
            val y = c.ground(x, z)
            if (y < 50) continue
            val height = 2 + random.nextInt(4)
            val lean = random.nextDouble() * Math.PI * 2.0
            val tilt = random.nextDouble() * 0.5
            val tipX = x + Math.round(cos(lean) * height * tilt).toInt()
            val tipZ = z + Math.round(sin(lean) * height * tilt).toInt()
            c.limb(x, y - 1, z, tipX, y + height - 1, tipZ, if (height >= 4) 0.9 else 0.5, 0.2, ice)
        }
        return true
    }
}

/** Erebos: niedrige Stelen aus Obsidian, in Reihen wie Grabsteine ohne Namen. */
class ErebosSteleFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val chunk = ChunkPos(context.origin)
        val random = context.random
        val c = canvas(context)
        val alongX = random.nextBoolean()
        val x0 = chunk.centerX + random.nextInt(7) - 3
        val z0 = chunk.centerZ + random.nextInt(7) - 3
        for (i in 0 until 1 + random.nextInt(3)) {
            val x = if (alongX) x0 + i * 3 else x0
            val z = if (alongX) z0 else z0 + i * 3
            val y = c.ground(x, z)
            if (y < 50) continue
            val height = 2 + random.nextInt(3)
            c.block(x, y - 1, z, Blocks.POLISHED_BLACKSTONE.defaultState)
            for (h in 0 until height) {
                c.block(x, y + h, z, Blocks.OBSIDIAN.defaultState)
                // Zwei Blöcke breit, die Schmalseite zeigt die Reihe entlang.
                if (h < height - 1) c.block(if (alongX) x else x + 1, y + h, if (alongX) z + 1 else z, Blocks.OBSIDIAN.defaultState)
            }
            if (random.nextInt(4) == 0) c.block(x, y + height - 1, z, Blocks.CRYING_OBSIDIAN.defaultState)
        }
        return true
    }
}

/** Sümpfe des Styx: Wurzelstümpfe toter Mangroven, die Wurzeln greifen wie Finger in den getrockneten Schlamm. */
class MarshStumpFeature : Feature<DefaultFeatureConfig>(DefaultFeatureConfig.CODEC) {
    override fun generate(context: FeatureContext<DefaultFeatureConfig>): Boolean {
        val chunk = ChunkPos(context.origin)
        val random = context.random
        val c = canvas(context)
        val x = chunk.centerX + random.nextInt(9) - 4
        val z = chunk.centerZ + random.nextInt(9) - 4
        val y = c.ground(x, z)
        if (y < 50) return false
        val stump = 1 + random.nextInt(3)
        val core: Pal = { _, _, _ -> Blocks.MUDDY_MANGROVE_ROOTS.defaultState }
        val roots: Pal = { _, _, _ -> Blocks.MANGROVE_ROOTS.defaultState }
        c.prism(x, y - 1, z, x + 1, y + stump - 1, z + 1, core)
        val count = 5 + random.nextInt(3)
        for (i in 0 until count) {
            val a = i * Math.PI * 2.0 / count + random.nextDouble() * 0.6
            val reach = 2.5 + random.nextDouble() * 2.0
            val sx = x + if (cos(a) > 0) 1 else 0
            val sz = z + if (sin(a) > 0) 1 else 0
            val ex = sx + Math.round(cos(a) * reach).toInt()
            val ez = sz + Math.round(sin(a) * reach).toInt()
            val mid = 1 + random.nextInt(stump + 1)
            val mx = (sx + ex) / 2
            val mz = (sz + ez) / 2
            c.limb(sx, y + stump - 1, sz, mx, y + mid, mz, 0.3, 0.3, roots)
            c.limb(mx, y + mid, mz, ex, y - 1, ez, 0.3, 0.3, roots)
        }
        return true
    }
}
