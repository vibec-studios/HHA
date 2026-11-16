package dev.henny.hha.world.olymp

import dev.henny.hha.world.Kit
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.enums.DoubleBlockHalf
import net.minecraft.state.property.Properties
import net.minecraft.util.math.Direction
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Woraus ein Inselkörper ist (PLAN 8.3): grüne Kuppe auf hellem Fels oder kahle weiße Klippe. */
enum class IsleKind { MEADOW, MARBLE }

/**
 * Inselkörper, Wolkenbänke und Pflanzen des Olymp (PLAN 8.3). Ein Körper ist oben eine fast ebene Scheibe mit kantigem
 * Rand (ein unregelmäßiges Vieleck), darunter ein umgedrehter, facettierter Kegel aus Bändern von zwei bis drei Blöcken;
 * jedes Band hat sein eigenes, etwas kleineres und leicht gedrehtes Vieleck. So entstehen Kanten und Absätze statt
 * Rauschen. Alles hängt nur von der Position und dem Samen ab.
 */
object OlympusIsles {

    private fun s(b: net.minecraft.block.Block): BlockState = b.defaultState

    /** Zufall aus Position und Samen, 0 bis 1. */
    fun rnd(seed: Int, a: Int, b: Int = 0, c: Int = 0): Double = Kit.noise(a * 31 + seed, b * 17 - seed * 7, c * 13 + seed * 3) / 65536.0

    // ---------------------------------------------------------------------------------------------
    // Vielecke
    // ---------------------------------------------------------------------------------------------

    /** Ein Vieleck in Polarform um (cx, cz): Ecken bei [angles] mit Abstand [radii]. */
    class Rim(val cx: Double, val cz: Double, val angles: DoubleArray, val radii: DoubleArray) {

        /** Abstand des Rands in Richtung [theta] (Bogenmaß), mit geraden Kanten zwischen den Ecken. */
        fun radiusAt(theta: Double): Double {
            val n = angles.size
            var t = theta - angles[0]
            t -= 2 * PI * kotlin.math.floor(t / (2 * PI))
            val step = 2 * PI / n
            val i = (t / step).toInt().coerceIn(0, n - 1)
            val j = (i + 1) % n
            val a0 = i * step
            val a1 = a0 + step
            val r0 = radii[i]
            val r1 = radii[j]
            // Gerade durch zwei Punkte in Polarform.
            return r0 * r1 * sin(a1 - a0) / (r0 * sin(t - a0) + r1 * sin(a1 - t))
        }

        fun contains(x: Double, z: Double): Boolean {
            val dx = x - cx
            val dz = z - cz
            val r = hypot(dx, dz)
            if (r < 0.5) return true
            return r <= radiusAt(atan2(dz, dx))
        }

        fun containsCell(x: Int, z: Int): Boolean = contains(x + 0.5, z + 0.5)

        companion object {
            /** Unregelmäßiges Vieleck mit [count] Ecken zwischen [min] und [max] Abstand. */
            fun irregular(cx: Double, cz: Double, count: Int, min: Double, max: Double, seed: Int, phase: Double = 0.0): Rim {
                val angles = DoubleArray(count) { phase + 2 * PI * it / count }
                val radii = DoubleArray(count) { min + (max - min) * rnd(seed, it, count) }
                return Rim(cx, cz, angles, radii)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Inselkörper
    // ---------------------------------------------------------------------------------------------

    /** Grundmaße eines Körpers; [top] ist der oberste Block (Boden der Füße − 1). */
    class Body(val cx: Int, val top: Int, val cz: Int, val radius: Double, val kind: IsleKind, val seed: Int, val minRim: Double = 0.9, depthFactor: Double = 0.8) {
        val depth: Int = max(4, (radius * depthFactor).roundToInt())
        val corners: Int = (7 + radius / 7).toInt().coerceAtMost(13)
        val rim: Rim = Rim.irregular(cx + 0.5, cz + 0.5, corners, radius * minRim, radius, seed, rnd(seed, 99) * 0.6)
    }

    /**
     * Zeichnet den Körper: oberste Lage, dann Band um Band nach unten, jedes Band kleiner; darunter zwei bis vier
     * hängende Spitzen. [keepTop] = false lässt die Oberfläche weg (für Inseln, deren Boden ein Bau legt).
     */
    fun body(c: SkyCanvas, b: Body, dressRim: Boolean = true) {
        val reach = b.radius.toInt() + 2
        val bands = bands(b)
        // Oberfläche und erstes Band (die Klippe).
        for (x in b.cx - reach..b.cx + reach) {
            for (z in b.cz - reach..b.cz + reach) {
                if (!c.inside(x, z)) continue
                var bottom = Int.MAX_VALUE
                for (band in bands) {
                    if (!band.rim.containsCell(x, z)) break
                    bottom = band.bottom
                }
                if (bottom == Int.MAX_VALUE) continue
                for (y in bottom..b.top) c.put(x, y, z, material(b, x, y, z, bands))
            }
        }
        spires(c, b, bands)
        if (dressRim && b.kind == IsleKind.MEADOW) vines(c, b)
    }

    /** Ein Band des Kegels: Unterkante und Vieleck. */
    class Band(val topY: Int, val bottom: Int, val rim: Rim, val index: Int)

    fun bands(b: Body): List<Band> {
        val list = ArrayList<Band>()
        var y = b.top
        var index = 0
        var previous: Rim = b.rim
        while (true) {
            val height = if (index == 0) 3 else if (rnd(b.seed, index, 5) < 0.5) 2 else 3
            val bottom = y - height + 1
            val t = ((b.top - (y + bottom) / 2.0) / b.depth).coerceIn(0.0, 1.2)
            val scale = (1.0 - 0.9 * t.pow(1.55)).coerceAtLeast(0.06)
            if (index > 0 && scale < 0.1) break
            val rim = if (index == 0) b.rim else {
                // Jede Lage etwas gedreht, die Ecken neu gewürfelt, nie über die Lage darüber hinaus.
                val phase = b.rim.angles[0] + (rnd(b.seed, index, 11) - 0.5) * 0.5
                val n = b.corners
                val angles = DoubleArray(n) { phase + 2 * PI * it / n }
                val radii = DoubleArray(n) {
                    val want = b.radius * scale * (0.8 + 0.2 * rnd(b.seed, it, index))
                    min(want, previous.radiusAt(angles[it]) * 0.97)
                }
                Rim(b.cx + 0.5, b.cz + 0.5, angles, radii)
            }
            list.add(Band(y, bottom, rim, index))
            previous = rim
            y = bottom - 1
            index++
            if (b.top - y > b.depth + 6) break
        }
        return list
    }

    /** Block des Körpers an (x, y, z): oben Gras bzw. polierter Diorit, darunter heller Fels, nach unten grauer. */
    private fun material(b: Body, x: Int, y: Int, z: Int, bands: List<Band>): BlockState {
        val down = b.top - y
        val band = bands.firstOrNull { y in it.bottom..it.topY }?.index ?: bands.size
        val n = Kit.noise(x, y, z) % 100
        val patch = Kit.noise(Math.floorDiv(x, 3), Math.floorDiv(y, 2), Math.floorDiv(z, 3)) % 100
        return when (b.kind) {
            IsleKind.MEADOW -> when {
                down == 0 -> s(Blocks.GRASS_BLOCK)
                down == 1 -> s(Blocks.DIRT)
                band <= 1 -> if (n < 12) s(Blocks.DIORITE) else s(Blocks.CALCITE)
                band <= 3 -> if (patch < 30) s(Blocks.CALCITE) else s(Blocks.DIORITE)
                band <= 5 -> if (patch < 35) s(Blocks.DIORITE) else s(Blocks.ANDESITE)
                else -> if (n < 15) s(Blocks.DIORITE) else s(Blocks.ANDESITE)
            }
            IsleKind.MARBLE -> when {
                down == 0 -> s(Blocks.POLISHED_DIORITE)
                // Ein Band aus Quarzziegeln an jeder zweiten Lagenkante: die Schichtung des Marmors.
                bands.any { it.index % 2 == 1 && y == it.bottom } && band <= 6 -> s(Blocks.QUARTZ_BRICKS)
                band <= 1 -> if (n < 18) s(Blocks.CALCITE) else s(Blocks.SMOOTH_QUARTZ)
                band <= 4 -> if (patch < 30) s(Blocks.SMOOTH_QUARTZ) else s(Blocks.CALCITE)
                band <= 6 -> if (patch < 40) s(Blocks.CALCITE) else s(Blocks.DIORITE)
                else -> if (n < 20) s(Blocks.CALCITE) else s(Blocks.DIORITE)
            }
        }
    }

    /** Zwei bis vier hängende Spitzen unter dem Körper: der Schattenriss einer schwebenden Insel. */
    private fun spires(c: SkyCanvas, b: Body, bands: List<Band>) {
        val count = 2 + (rnd(b.seed, 7) * 3).toInt()
        val bottom = bands.last().bottom
        for (i in 0 until count) {
            val a = 2 * PI * (i + rnd(b.seed, i, 21)) / count
            val off = b.radius * (0.18 + 0.22 * rnd(b.seed, i, 22))
            val sx = b.cx + (cos(a) * off).roundToInt()
            val sz = b.cz + (sin(a) * off).roundToInt()
            // Oberkante: wo der Körper an dieser Stelle endet.
            val start = bands.lastOrNull { it.rim.containsCell(sx, sz) }?.bottom ?: continue
            val length = (b.depth * (0.25 + 0.3 * rnd(b.seed, i, 23))).roundToInt().coerceAtLeast(3)
            val r0 = (b.radius * 0.09 + 1.0).coerceAtMost(3.5)
            for (k in 0 until length) {
                val r = r0 * (1.0 - k.toDouble() / length)
                val y = start - 1 - k
                if (y < bottom - b.depth) break
                c.disc(sx, sz, r.coerceAtLeast(0.5)) { x, z, _ ->
                    c.put(x, y, z, if (k > length / 2) s(Blocks.ANDESITE) else if (b.kind == IsleKind.MARBLE) s(Blocks.DIORITE) else s(Blocks.ANDESITE))
                }
            }
        }
    }

    /** Ranken an der Klippe der Wiesen-Inseln: hängen ein paar Blöcke über den Rand. */
    private fun vines(c: SkyCanvas, b: Body) {
        val reach = b.radius.toInt() + 2
        for (x in b.cx - reach..b.cx + reach) {
            for (z in b.cz - reach..b.cz + reach) {
                if (!c.inside(x, z) || b.rim.containsCell(x, z)) continue
                if (Kit.noise(x, 41, z) % 100 >= 9) continue
                for (d in HORIZONTAL) {
                    val nx = x - d.offsetX
                    val nz = z - d.offsetZ
                    if (!b.rim.containsCell(nx, nz)) continue
                    // (x, z) liegt außen, der Nachbar in Richtung -d innen: Die Ranke hängt an dessen Seitenfläche.
                    val length = 1 + Kit.noise(x, 43, z) % 3
                    val vine = Blocks.VINE.defaultState.with(face(d.opposite), true)
                    for (k in 1..length) c.soft(x, b.top - k, z, vine)
                    break
                }
            }
        }
    }

    private val HORIZONTAL = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)

    private fun face(d: Direction) = when (d) {
        Direction.NORTH -> Properties.NORTH
        Direction.SOUTH -> Properties.SOUTH
        Direction.EAST -> Properties.EAST
        else -> Properties.WEST
    }

    /**
     * Rechteckiger Ausläufer einer Insel (Terrasse, Treppenfuß): Oberfläche bei [top], darunter ein Keil, der nach außen
     * flacher wird. [outward] zeigt vom Inselkern weg.
     */
    fun ledge(c: SkyCanvas, x0: Int, z0: Int, x1: Int, z1: Int, top: Int, kind: IsleKind, outward: Direction, seed: Int) {
        val lo = minOf(x0, x1)
        val hi = maxOf(x0, x1)
        val lz = minOf(z0, z1)
        val hz = maxOf(z0, z1)
        val length = if (outward.axis == Direction.Axis.X) hi - lo else hz - lz
        for (x in lo..hi) for (z in lz..hz) {
            if (!c.inside(x, z)) continue
            val along = when (outward) {
                Direction.EAST -> hi - x
                Direction.WEST -> x - lo
                Direction.SOUTH -> hz - z
                else -> z - lz
            }
            val edge = minOf(x - lo, hi - x, z - lz, hz - z)
            val depth = (3 + along * 0.9 + edge * 0.7 + rnd(seed, x, z) * 2).roundToInt().coerceAtMost(4 + length)
            for (k in 0..depth) {
                val y = top - k
                val state = when {
                    k == 0 -> if (kind == IsleKind.MEADOW) s(Blocks.GRASS_BLOCK) else s(Blocks.POLISHED_DIORITE)
                    kind == IsleKind.MEADOW && k <= 2 -> s(Blocks.DIRT)
                    k > depth - 2 -> s(Blocks.DIORITE)
                    kind == IsleKind.MARBLE && k % 5 == 3 -> s(Blocks.QUARTZ_BRICKS)
                    else -> if (Kit.noise(x, y, z) % 5 == 0) s(Blocks.SMOOTH_QUARTZ) else s(Blocks.CALCITE)
                }
                c.put(x, y, z, state)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Wolken
    // ---------------------------------------------------------------------------------------------

    /**
     * Flache Wolkenbank aus Blöcken (PLAN 8.3): Kern `snow_block`, Oberseite `white_concrete`, Unterseite
     * `light_gray_concrete`, Rand `white_stained_glass`. Mehrere Ballen über einer flachen Unterseite; alles weich, also
     * nie über Fels oder Bau.
     */
    fun cloud(c: SkyCanvas, cx: Int, cy: Int, cz: Int, rx: Double, rz: Double, seed: Int, puff: Double = 3.0) {
        val lumps = 3 + (rnd(seed, 1) * 4).toInt()
        class Lump(val x: Double, val z: Double, val r: Double, val h: Double)
        val list = ArrayList<Lump>()
        list.add(Lump(cx.toDouble(), cz.toDouble(), min(rx, rz) * 0.75, puff))
        for (i in 0 until lumps) {
            val a = 2 * PI * rnd(seed, i, 2)
            val d = 0.55 * rnd(seed, i, 3)
            list.add(Lump(cx + cos(a) * rx * d, cz + sin(a) * rz * d, min(rx, rz) * (0.35 + 0.35 * rnd(seed, i, 4)), puff * (0.6 + 0.6 * rnd(seed, i, 5))))
        }
        val reach = max(rx, rz).toInt() + 2
        for (x in cx - reach..cx + reach) {
            for (z in cz - reach..cz + reach) {
                if (!c.inside(x, z)) continue
                // Ellipse der ganzen Bank als Hülle, die Ballen geben die Höhe.
                val ex = (x - cx) / rx
                val ez = (z - cz) / rz
                val hull = 1.0 - ex * ex - ez * ez
                if (hull <= 0.0) continue
                var t = 0.0
                for (l in list) {
                    val d = hypot(x - l.x, z - l.z) / l.r
                    if (d < 1.0) t = max(t, l.h * sqrt(1.0 - d * d))
                }
                t = min(t, puff * 1.6 * sqrt(hull) + 0.6)
                if (t < 0.5) {
                    if (hull > 0.08 && Kit.noise(x, cy, z) % 3 != 0) c.soft(x, cy, z, s(Blocks.WHITE_STAINED_GLASS))
                    continue
                }
                val height = t.roundToInt().coerceAtLeast(1)
                val lift = if (Kit.noise(x, cy + 1, z) % 7 == 0 && height > 2) 1 else 0
                for (k in lift until height) {
                    val y = cy + k
                    val state = when {
                        height == 1 -> s(Blocks.WHITE_STAINED_GLASS)
                        k == lift && t > 1.4 -> s(Blocks.LIGHT_GRAY_CONCRETE)
                        k == height - 1 -> if (t < 1.4) s(Blocks.WHITE_STAINED_GLASS) else s(Blocks.WHITE_CONCRETE)
                        else -> s(Blocks.SNOW_BLOCK)
                    }
                    c.soft(x, y, z, state)
                }
            }
        }
    }

    /** Kränze aus kleinen Wolken um den Kegel einer Insel, etwa auf halber Tiefe. */
    fun cloudSkirt(c: SkyCanvas, b: Body, count: Int = 4) {
        for (i in 0 until count) {
            val a = 2 * PI * (i + 0.5 * rnd(b.seed, i, 31)) / count
            val off = b.radius * (0.55 + 0.25 * rnd(b.seed, i, 32))
            val x = b.cx + (cos(a) * off).roundToInt()
            val z = b.cz + (sin(a) * off).roundToInt()
            val y = b.top - (b.depth * (0.35 + 0.3 * rnd(b.seed, i, 33))).roundToInt()
            val r = b.radius * (0.22 + 0.16 * rnd(b.seed, i, 34))
            cloud(c, x, y, z, r * 1.4, r, b.seed + i * 101, 2.4)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Bäume und Bewuchs
    // ---------------------------------------------------------------------------------------------

    fun leaves(block: net.minecraft.block.Block): BlockState = block.defaultState.with(Properties.PERSISTENT, true)

    private fun log(block: net.minecraft.block.Block, axis: Direction.Axis = Direction.Axis.Y): BlockState = block.defaultState.with(Properties.AXIS, axis)

    /** Laubballen: eine abgeflachte Kugel mit zerzaustem Rand. */
    fun crown(c: SkyCanvas, x: Int, y: Int, z: Int, rx: Double, ry: Double, seed: Int, leaf: (Int, Int, Int) -> BlockState) {
        val reach = rx.toInt() + 1
        val up = ry.toInt() + 1
        for (dx in -reach..reach) for (dz in -reach..reach) for (dy in -up..up) {
            val d = (dx * dx + dz * dz) / (rx * rx) + (dy * dy) / (ry * ry)
            if (d > 1.0) continue
            if (d > 0.62 && Kit.noise(x + dx, y + dy + seed, z + dz) % 100 < 38) continue
            c.soft(x + dx, y + dy, z + dz, leaf(x + dx, y + dy, z + dz))
        }
    }

    /** Kirschbaum: geschwungener Stamm, zwei, drei Äste, rosa Krone; darunter Blütenblätter. */
    fun cherry(c: SkyCanvas, x: Int, floor: Int, z: Int, seed: Int) {
        val height = 4 + (rnd(seed, x, z) * 2).toInt()
        for (h in 0 until height) c.put(x, floor + h, z, log(Blocks.CHERRY_LOG))
        val branches = 2 + (rnd(seed, z, x) * 2).toInt()
        val pink: (Int, Int, Int) -> BlockState = { _, _, _ -> leaves(Blocks.CHERRY_LEAVES) }
        crown(c, x, floor + height + 1, z, 2.6, 1.7, seed, pink)
        for (i in 0 until branches) {
            val a = 2 * PI * (i + rnd(seed, i, 51)) / branches
            val dx = cos(a)
            val dz = sin(a)
            val axis = if (abs(dx) > abs(dz)) Direction.Axis.X else Direction.Axis.Z
            val length = 2 + (rnd(seed, i, 52) * 2).toInt()
            var bx = x
            var bz = z
            for (k in 1..length) {
                bx = x + (dx * k).roundToInt()
                bz = z + (dz * k).roundToInt()
                c.put(bx, floor + height - 2 + k / 2, bz, log(Blocks.CHERRY_LOG, axis))
            }
            crown(c, bx, floor + height - 1 + length / 2, bz, 2.4 + rnd(seed, i, 53), 1.5, seed + i, pink)
        }
        // Blütenteppich unter der Krone.
        for (dx in -4..4) for (dz in -4..4) {
            if (dx * dx + dz * dz > 16 || Kit.noise(x + dx, floor, z + dz) % 100 > 45) continue
            val facing = HORIZONTAL[Kit.noise(x + dz, floor, z + dx) % 4]
            c.soft(x + dx, floor, z + dz, Blocks.PINK_PETALS.defaultState.with(Properties.FLOWER_AMOUNT, 1 + Kit.noise(x + dx, 3, z + dz) % 4).with(Properties.HORIZONTAL_FACING, facing))
        }
    }

    /** Ölbaum: knorriger, geneigter Stamm aus Eiche, silbriges Laub (Azalee) in flachen Ballen. */
    fun olive(c: SkyCanvas, x: Int, floor: Int, z: Int, seed: Int) {
        val lean = HORIZONTAL[(rnd(seed, x, z) * 4).toInt().coerceIn(0, 3)]
        var tx = x
        var tz = z
        val height = 4
        for (h in 0 until height) {
            if (h == 2) {
                tx += lean.offsetX
                tz += lean.offsetZ
                c.put(tx - lean.offsetX, floor + h, tz - lean.offsetZ, log(Blocks.STRIPPED_OAK_LOG, lean.axis))
            }
            c.put(tx, floor + h, tz, log(if (h == 0) Blocks.OAK_LOG else Blocks.STRIPPED_OAK_LOG))
        }
        val leaf: (Int, Int, Int) -> BlockState = { lx, ly, lz -> if (Kit.noise(lx, ly, lz) % 6 == 0) leaves(Blocks.FLOWERING_AZALEA_LEAVES) else leaves(Blocks.AZALEA_LEAVES) }
        crown(c, tx, floor + height + 1, tz, 3.0, 1.4, seed, leaf)
        crown(c, tx - lean.offsetX * 2, floor + height, tz - lean.offsetZ * 2, 2.0, 1.1, seed + 3, leaf)
    }

    /** Zypresse: schmale dunkle Säule, unten breiter, oben spitz. [height] Blöcke Laub. */
    fun cypress(c: SkyCanvas, x: Int, floor: Int, z: Int, height: Int, seed: Int) {
        c.put(x, floor, z, log(Blocks.SPRUCE_LOG))
        for (h in 1..height) {
            val y = floor + h
            val t = h.toDouble() / height
            if (h < height - 1) c.put(x, y, z, log(Blocks.SPRUCE_LOG))
            else c.put(x, y, z, leaves(Blocks.SPRUCE_LEAVES))
            val r = when {
                t < 0.2 -> 0.9
                t < 0.75 -> 1.45
                t < 0.92 -> 1.0
                else -> 0.0
            }
            if (r <= 0.0) continue
            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val d = hypot(dx.toDouble(), dz.toDouble())
                if (d > r) continue
                if (d > 1.2 && Kit.noise(x + dx, y + seed, z + dz) % 3 == 0) continue
                c.soft(x + dx, y, z + dz, leaves(Blocks.SPRUCE_LEAVES))
            }
        }
        c.soft(x, floor + height + 1, z, leaves(Blocks.SPRUCE_LEAVES))
    }

    /** Lorbeer- oder Myrtenbusch: ein Azaleenstrauch, dazu ein paar Blätter. */
    fun bush(c: SkyCanvas, x: Int, floor: Int, z: Int, seed: Int) {
        val flowering = rnd(seed, x, z) < 0.4
        c.soft(x, floor, z, if (flowering) s(Blocks.FLOWERING_AZALEA) else s(Blocks.AZALEA))
        for (d in HORIZONTAL) if (Kit.noise(x + d.offsetX, floor + seed, z + d.offsetZ) % 3 == 0) c.soft(x + d.offsetX, floor, z + d.offsetZ, leaves(Blocks.AZALEA_LEAVES))
    }

    private val FLOWERS = listOf(Blocks.POPPY, Blocks.DANDELION, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER, Blocks.AZURE_BLUET, Blocks.ALLIUM, Blocks.LILY_OF_THE_VALLEY)

    /** Eine hohe Pflanze aus zwei Blöcken (Flieder, Rosenstrauch, Pfingstrose, hohes Gras). */
    fun tall(c: SkyCanvas, x: Int, y: Int, z: Int, block: net.minecraft.block.Block) {
        if (!c.isFree(x, y, z) || !c.isFree(x, y + 1, z)) return
        c.put(x, y, z, block.defaultState.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER))
        c.put(x, y + 1, z, block.defaultState.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER))
    }

    /**
     * Bewuchs auf einer Wiese: Gras, Blumen, Blütenblätter, ab und zu Moos und ein Busch. [free] sagt, wo nichts
     * wachsen darf (Wege, Plätze). Nur auf Grasblöcken der Höhe [floor] − 1.
     */
    fun meadow(c: SkyCanvas, b: Body, floor: Int, free: (Int, Int) -> Boolean, density: Int = 30) {
        val reach = b.radius.toInt() + 1
        for (x in b.cx - reach..b.cx + reach) {
            for (z in b.cz - reach..b.cz + reach) {
                if (!c.inside(x, z) || !b.rim.containsCell(x, z) || free(x, z)) continue
                if (!c.isFree(x, floor, z)) continue
                val n = Kit.noise(x, floor * 3, z) % 1000
                val patch = Kit.noise(Math.floorDiv(x, 5), 77, Math.floorDiv(z, 5)) % 100
                when {
                    patch < 9 && n < 500 -> c.put(x, floor - 1, z, s(Blocks.MOSS_BLOCK))
                    n < density * 6 -> c.soft(x, floor, z, s(Blocks.SHORT_GRASS))
                    n < density * 6 + 40 -> c.soft(x, floor, z, s(FLOWERS[(patch + n) % FLOWERS.size]))
                    n < density * 6 + 60 && patch > 60 -> c.soft(x, floor, z, Blocks.WILDFLOWERS.defaultState.with(Properties.FLOWER_AMOUNT, 1 + n % 4).with(Properties.HORIZONTAL_FACING, HORIZONTAL[n % 4]))
                    n < density * 6 + 70 -> tall(c, x, floor, z, Blocks.TALL_GRASS)
                    n < density * 6 + 74 -> tall(c, x, floor, z, if (patch % 3 == 0) Blocks.LILAC else if (patch % 3 == 1) Blocks.ROSE_BUSH else Blocks.PEONY)
                    n < density * 6 + 79 -> bush(c, x, floor, z, n)
                }
            }
        }
    }
}
