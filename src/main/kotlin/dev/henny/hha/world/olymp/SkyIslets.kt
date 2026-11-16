package dev.henny.hha.world.olymp

import dev.henny.hha.world.Kit
import dev.henny.hha.world.block
import net.minecraft.block.Blocks
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.world.ServerWorld
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Direction
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Die kleinen Himmelsinseln um den Pfad (PLAN 8.3): `random_spread` 9/4, Höhe 96 bis 190 aus dem Chunk-Zufall, nie im
 * Kasten um den Pfad. Vier Arten: Wiese mit Baum, Ruine mit drei Säulen und Truhe, Wolkenbank, Posten der Kureten.
 * Die Mitte ist die Höhe der Füße auf der Insel.
 */
object SkyIslets {

    enum class Kind { MEADOW, RUIN, CLOUD, POST }

    const val MIN_Y = 96
    const val MAX_Y = 190

    /** Wie weit eine Nebeninsel samt Wolken höchstens von ihrer Mitte reicht. */
    fun reach(kind: Kind): Int = when (kind) {
        Kind.MEADOW -> 16
        Kind.RUIN -> 18
        Kind.CLOUD -> 22
        Kind.POST -> 16
    }

    /** Berührt eine Nebeninsel mit dieser Reichweite den Kasten um den Pfad? */
    fun touchesPath(x: Int, z: Int, reach: Int): Boolean =
        x + reach >= OlympusLayout.PATH_MIN_X && x - reach <= OlympusLayout.PATH_MAX_X &&
            z + reach >= OlympusLayout.PATH_MIN_Z && z - reach <= OlympusLayout.PATH_MAX_Z

    fun box(kind: Kind, center: BlockPos): BlockBox {
        val r = reach(kind)
        return BlockBox(center.x - r, center.y - 26, center.z - r, center.x + r, center.y + 16, center.z + r)
    }

    private fun radius(kind: Kind, seed: Int): Double {
        val t = OlympusIsles.rnd(seed, 401)
        return when (kind) {
            Kind.MEADOW -> 7.0 + 4.0 * t
            Kind.RUIN -> 8.0 + 3.0 * t
            Kind.CLOUD -> 12.0 + 6.0 * t
            Kind.POST -> 8.0 + 2.0 * t
        }
    }

    fun draw(c: SkyCanvas, kind: Kind, center: BlockPos, seed: Int) {
        val r = radius(kind, seed)
        val fy = center.y
        when (kind) {
            Kind.CLOUD -> {
                OlympusIsles.cloud(c, center.x, fy - 2, center.z, r * 1.3, r, seed, 3.4)
                OlympusIsles.cloud(c, center.x + (r * 0.6).roundToInt(), fy - 6, center.z - (r * 0.4).roundToInt(), r * 0.7, r * 0.5, seed + 7, 2.4)
            }
            Kind.MEADOW -> meadow(c, center, r, seed)
            Kind.RUIN -> ruin(c, center, r, seed)
            Kind.POST -> post(c, center, r, seed)
        }
    }

    private fun meadow(c: SkyCanvas, center: BlockPos, r: Double, seed: Int) {
        val fy = center.y
        val body = OlympusIsles.Body(center.x, fy - 1, center.z, r, IsleKind.MEADOW, seed, 0.85)
        OlympusIsles.body(c, body)
        val a = OlympusIsles.rnd(seed, 402) * Math.PI * 2
        val tx = center.x + (cos(a) * r * 0.25).roundToInt()
        val tz = center.z + (sin(a) * r * 0.25).roundToInt()
        if (OlympusIsles.rnd(seed, 403) < 0.5) OlympusIsles.cherry(c, tx, fy, tz, seed) else OlympusIsles.olive(c, tx, fy, tz, seed)
        OlympusIsles.meadow(c, body, fy, { x, z -> abs(x - tx) <= 0 && abs(z - tz) <= 0 }, 34)
        OlympusIsles.cloudSkirt(c, body, 2)
    }

    /** Ruine eines älteren Himmels: Stylobat, drei Säulen (eine gestürzt), ein Stück Gebälk, darunter eine Truhe. */
    private fun ruin(c: SkyCanvas, center: BlockPos, r: Double, seed: Int) {
        val fy = center.y
        val cx = center.x
        val cz = center.z
        val body = OlympusIsles.Body(cx, fy - 1, cz, r, IsleKind.MARBLE, seed, 0.85)
        OlympusIsles.body(c, body)
        val alongX = OlympusIsles.rnd(seed, 404) < 0.5
        fun at(a: Int, b: Int): Pair<Int, Int> = if (alongX) (cx + a) to (cz + b) else (cx + b) to (cz + a)
        // Stylobat aus Steinziegeln, an den Rändern gerissen.
        for (a in -6..6) for (b in -3..3) {
            val (x, z) = at(a, b)
            val edge = abs(a) == 6 || abs(b) == 3
            if (edge && Kit.noise(x, fy, z) % 3 == 0) continue
            c.block(x, fy - 1, z, when {
                edge -> Blocks.CHISELED_STONE_BRICKS.defaultState
                Kit.noise(x, fy - 1, z) % 4 == 0 -> Blocks.CRACKED_STONE_BRICKS.defaultState
                Kit.noise(x, fy - 1, z) % 5 == 0 -> Blocks.MOSSY_STONE_BRICKS.defaultState
                else -> Blocks.POLISHED_DIORITE.defaultState
            })
        }
        // Zwei Säulen stehen, darüber ein Rest des Architravs; die dritte liegt in Trommeln am Boden.
        for (a in intArrayOf(-4, 0)) {
            val (x, z) = at(a, -1)
            OlympusPlans.column(c, x, fy, z, 8)
        }
        for (a in -5..1) {
            val (x, z) = at(a, -1)
            if (a == 1 && Kit.noise(x, 3, z) % 2 == 0) continue
            c.block(x, fy + 8, z, if (a == -5 || a == 1) Kit.stair(Blocks.SMOOTH_QUARTZ_STAIRS, if (alongX) (if (a < 0) Direction.EAST else Direction.WEST) else (if (a < 0) Direction.SOUTH else Direction.NORTH), top = true) else Blocks.SMOOTH_QUARTZ.defaultState)
            c.block(x, fy + 9, z, if (Math.floorMod(a, 2) == 0) Blocks.CHISELED_QUARTZ_BLOCK.defaultState else Blocks.SMOOTH_QUARTZ.defaultState)
        }
        val axis = if (alongX) Direction.Axis.X else Direction.Axis.Z
        for (a in 2..5) {
            val (x, z) = at(a, 1)
            c.block(x, fy, z, Blocks.QUARTZ_PILLAR.defaultState.with(Properties.AXIS, axis))
            if (a == 3) c.block(x, fy + 1, z, Blocks.MOSS_CARPET.defaultState)
        }
        val (bx, bz) = at(4, -1)
        OlympusPlans.column(c, bx, fy, bz, 2)
        c.block(bx, fy + 2, bz, Blocks.MOSS_CARPET.defaultState)
        // Die Truhe unter einem umgestürzten Kapitell.
        val (kx, kz) = at(-2, 1)
        c.chest(kx, fy, kz)
        val (lx, lz) = at(-1, 2)
        c.block(lx, fy, lz, Blocks.CHISELED_QUARTZ_BLOCK.defaultState)
        c.block(lx, fy + 1, lz, Blocks.MOSS_CARPET.defaultState)
        val (sx, sz) = at(-5, 2)
        OlympusPlans.stele(c, sx, fy, sz, 14, if (alongX) Direction.SOUTH else Direction.EAST)
        OlympusIsles.cloudSkirt(c, body, 2)
    }

    /** Posten der Kureten: runde Plattform mit Brüstung, ein kleiner Säulenbau mit flachem Dach, Feuerschale, Herme. */
    private fun post(c: SkyCanvas, center: BlockPos, r: Double, seed: Int) {
        val fy = center.y
        val cx = center.x
        val cz = center.z
        val body = OlympusIsles.Body(cx, fy - 1, cz, r, IsleKind.MARBLE, seed, 0.9)
        OlympusIsles.body(c, body)
        c.disc(cx, cz, 6.5) { x, z, d ->
            c.put(x, fy - 1, z, if (d > 5.5) Blocks.QUARTZ_BRICKS.defaultState else if (Kit.noise(x, 1, z) % 5 == 0) Blocks.CALCITE.defaultState else Blocks.POLISHED_DIORITE.defaultState)
            if (d > 5.6 && Kit.noise(x, 2, z) % 4 != 0) c.put(x, fy, z, if (Kit.noise(x, 3, z) % 3 == 0) Blocks.QUARTZ_PILLAR.defaultState else Blocks.SMOOTH_QUARTZ_SLAB.defaultState)
        }
        for ((ox, oz) in listOf(-2 to -2, 2 to -2, -2 to 2, 2 to 2)) OlympusPlans.slimColumn(c, cx + ox, fy, cz + oz, 5)
        for (x in cx - 3..cx + 3) for (z in cz - 3..cz + 3) {
            val edge = abs(x - cx) == 3 || abs(z - cz) == 3
            c.block(x, fy + 5, z, if (edge) Kit.stair(Blocks.SMOOTH_QUARTZ_STAIRS, Kit.inward(x - cx, z - cz), top = true) else Blocks.SMOOTH_QUARTZ.defaultState)
            if (!edge) c.block(x, fy + 6, z, Kit.slab(Blocks.SMOOTH_QUARTZ_SLAB))
        }
        c.block(cx, fy + 4, cz, Kit.lantern(hanging = true, soul = false))
        // Bronzeschilde an zwei Säulen, die Speere der Kureten daneben.
        for ((ox, oz) in listOf(-2 to -3, 2 to 3)) c.block(cx + ox, fy + 2, cz + oz, Blocks.WAXED_COPPER_BLOCK.defaultState)
        for (h in 0..2) c.block(cx + 4, fy + h, cz - 1, OlympusPlans.wall(Blocks.DIORITE_WALL, alongX = true, post = true))
        c.block(cx + 4, fy + 3, cz - 1, Blocks.WAXED_LIGHTNING_ROD.defaultState)
        OlympusPlans.brazier(c, cx - 4, fy, cz + 1)
        OlympusPlans.herm(c, cx, fy, cz + 5, Direction.SOUTH)
        OlympusPlans.stele(c, cx + 2, fy, cz + 5, 15, Direction.SOUTH)
        OlympusIsles.cloudSkirt(c, body, 2)
    }

    /** Posten einer Nebeninsel (nur die Art [Kind.POST]): drei Kureten auf der Plattform. */
    fun posts(kind: Kind, center: BlockPos): List<Post> =
        if (kind == Kind.POST) listOf(Post(center, PostKind.KURETE, 3, null)) else emptyList()

    /**
     * Posten aller Nebeninseln in geladenen Chunks um [center] (P11): liest die Struktur-Starts der Chunks, also nur,
     * was die Welt wirklich erzeugt hat.
     */
    fun postsAround(world: ServerWorld, center: BlockPos, chunkRadius: Int = 8): List<Post> {
        if (world.registryKey != OlympusLayout.WORLD) return emptyList()
        val structure = world.registryManager.getOrThrow(RegistryKeys.STRUCTURE).get(OlympusStructures.ISLET_ID) ?: return emptyList()
        val origin = ChunkPos(center)
        val list = ArrayList<Post>()
        for (dx in -chunkRadius..chunkRadius) for (dz in -chunkRadius..chunkRadius) {
            val x = origin.x + dx
            val z = origin.z + dz
            if (!world.isChunkLoaded(x, z)) continue
            val start = world.getChunk(x, z).getStructureStart(structure) ?: continue
            if (!start.hasChildren()) continue
            for (piece in start.children) if (piece is SkyIsletPiece) list.addAll(posts(piece.kind, piece.middle))
        }
        return list
    }
}
