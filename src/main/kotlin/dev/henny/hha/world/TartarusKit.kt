package dev.henny.hha.world

import dev.henny.hha.HhaBlocks
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.LanternBlock
import net.minecraft.block.SlabBlock
import net.minecraft.block.StairsBlock
import net.minecraft.block.enums.BlockHalf
import net.minecraft.block.enums.SlabType
import net.minecraft.state.property.Properties
import net.minecraft.util.math.Direction
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Eine Palette entscheidet je Position, welcher Block dort sitzt. So entstehen Patina und Verwitterung statt glatter Flächen. */
typealias Pal = (Int, Int, Int) -> BlockState

/**
 * Baukasten für die Bauwerke: Formen wie Wand mit Tiefe, eckiger Turm, Säule, Kuppel, Feuerschale und Statue. Die Blöcke
 * kommen aus den [Palettes]. Alles ist aus der Position abgeleitet, also passen benachbarte Chunks zusammen.
 */
object Kit {

    fun noise(x: Int, y: Int, z: Int): Int {
        var h = (x * 73856093) xor (y * 19349663) xor (z * 83492791)
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
        return h and 0xFFFF
    }

    /**
     * Verwitterung mit Richtung: oben ([t] → 1) und an Ecken ([edge] → 0) bröckelt mehr als unten in der Wandmitte.
     * Gibt an, ob an dieser Stelle ein verwitterter Stein sitzt.
     */
    fun wear(x: Int, y: Int, z: Int, t: Double, edge: Double, strength: Double = 1.0): Boolean {
        val chance = (0.04 + 0.30 * t * t + 0.22 * (1.0 - edge.coerceIn(0.0, 1.0))) * strength
        return noise(x, y, z) % 1000 < chance * 1000
    }

    val AIR: BlockState get() = Blocks.AIR.defaultState
    val FIRE: BlockState get() = HhaBlocks.GREEN_FIRE.defaultState

    val BONE: Pal = { x, y, z -> if (noise(x, y, z) % 6 == 0) Blocks.DRIPSTONE_BLOCK.defaultState else Blocks.BONE_BLOCK.defaultState }

    fun stair(block: Block, facing: Direction, top: Boolean = false): BlockState =
        block.defaultState.with(StairsBlock.FACING, facing).with(StairsBlock.HALF, if (top) BlockHalf.TOP else BlockHalf.BOTTOM)

    fun slab(block: Block, top: Boolean = false): BlockState =
        block.defaultState.with(SlabBlock.TYPE, if (top) SlabType.TOP else SlabType.BOTTOM)

    /** Zeigt zur Mitte: Treppen, die außen um etwas herum liegen, steigen dorthin an. */
    fun inward(dx: Int, dz: Int): Direction =
        if (abs(dx) >= abs(dz)) (if (dx > 0) Direction.WEST else Direction.EAST) else (if (dz > 0) Direction.NORTH else Direction.SOUTH)

    fun chain(axis: Direction.Axis = Direction.Axis.Y): BlockState = Blocks.IRON_CHAIN.defaultState.with(Properties.AXIS, axis)

    fun lantern(hanging: Boolean = true, soul: Boolean = true): BlockState =
        (if (soul) Blocks.SOUL_LANTERN else Blocks.LANTERN).defaultState.with(LanternBlock.HANGING, hanging)

    /** Gitterstäbe, die tatsächlich zu beiden Seiten verbunden sind. */
    fun bars(alongX: Boolean, block: Block = Blocks.IRON_BARS): BlockState =
        block.defaultState.with(Properties.EAST, alongX).with(Properties.WEST, alongX).with(Properties.NORTH, !alongX).with(Properties.SOUTH, !alongX)
}

/** Woraus eine Säule ist. */
class ColumnStyle(
    val base: BlockState, val baseStair: Block, val shaft: Pal, val flute: BlockState,
    val capital: BlockState, val capitalStair: Block, val abacus: BlockState, val abacusSlab: Block,
) {
    companion object {
        /** Schwarz mit goldenem Kapitell: Palast und Halle. */
        val PALACE = ColumnStyle(
            Blocks.POLISHED_DEEPSLATE.defaultState, Blocks.POLISHED_DEEPSLATE_STAIRS, Palettes.Palace.SHAFT, Blocks.POLISHED_BLACKSTONE_WALL.defaultState,
            Blocks.GILDED_BLACKSTONE.defaultState, Blocks.POLISHED_BLACKSTONE_STAIRS, Blocks.POLISHED_BLACKSTONE.defaultState, Blocks.POLISHED_BLACKSTONE_SLAB,
        )
    }
}

fun BlockCanvas.inBox(x: Int, z: Int): Boolean = x in minX..maxX && z in minZ..maxZ

fun BlockCanvas.prism(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, pal: Pal) {
    for (x in maxOf(minOf(x1, x2), minX)..minOf(maxOf(x1, x2), maxX)) {
        for (z in maxOf(minOf(z1, z2), minZ)..minOf(maxOf(z1, z2), maxZ)) {
            for (y in minOf(y1, y2)..maxOf(y1, y2)) put(x, y, z, pal(x, y, z))
        }
    }
}

fun BlockCanvas.block(x: Int, y: Int, z: Int, state: BlockState) {
    if (x in minX..maxX && z in minZ..maxZ) put(x, y, z, state)
}

fun BlockCanvas.cyl(cx: Int, cz: Int, y0: Int, y1: Int, r: Double, pal: Pal) {
    disc(cx, cz, r) { x, z, _ -> for (y in y0..y1) put(x, y, z, pal(x, y, z)) }
}

/** Hohler Zylinder mit Wandstärke [wall]. */
fun BlockCanvas.tube(cx: Int, cz: Int, y0: Int, y1: Int, r: Double, wall: Double, pal: Pal) {
    disc(cx, cz, r) { x, z, d -> if (d >= r - wall) for (y in y0..y1) put(x, y, z, pal(x, y, z)) }
}

/** Ellipsoid für alles Organische: Felsen, Körper, Köpfe. */
fun BlockCanvas.blob(cx: Int, cy: Int, cz: Int, rx: Double, ry: Double, rz: Double, pal: Pal) {
    val reach = maxOf(rx, rz)
    disc(cx, cz, reach + 1.0) { x, z, _ ->
        val nx = (x - cx) / rx
        val nz = (z - cz) / rz
        val rest = 1.0 - nx * nx - nz * nz
        if (rest >= 0.0) {
            val span = (ry * sqrt(rest)).toInt()
            for (y in cy - span..cy + span) put(x, y, z, pal(x, y, z))
        }
    }
}

/** Kegelstumpf über die Höhe: vom Radius [r0] unten auf [r1] oben. Gibt Tiefe statt Quadern. */
fun BlockCanvas.taper(cx: Int, cz: Int, y0: Int, y1: Int, r0: Double, r1: Double, pal: Pal) {
    val span = (y1 - y0).coerceAtLeast(1)
    disc(cx, cz, maxOf(r0, r1) + 1.0) { x, z, d ->
        for (y in y0..y1) {
            val r = r0 + (r1 - r0) * (y - y0).toDouble() / span
            if (d <= r) put(x, y, z, pal(x, y, z))
        }
    }
}

/** Dicker Strang zwischen zwei Punkten, zum Beispiel für Arme, Äste und Stützen. */
fun BlockCanvas.limb(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, r0: Double, r1: Double, pal: Pal) {
    val length = maxOf(abs(x2 - x1), abs(y2 - y1), abs(z2 - z1)).coerceAtLeast(1)
    val steps = length * 2
    for (i in 0..steps) {
        val t = i.toDouble() / steps
        val r = r0 + (r1 - r0) * t
        val cx = x1 + (x2 - x1) * t
        val cy = y1 + (y2 - y1) * t
        val cz = z1 + (z2 - z1) * t
        val reach = r.toInt() + 1
        for (ox in -reach..reach) {
            for (oz in -reach..reach) {
                for (oy in -reach..reach) {
                    if (ox * ox + oy * oy + oz * oz <= r * r + 0.3) {
                        val x = Math.round(cx).toInt() + ox
                        val y = Math.round(cy).toInt() + oy
                        val z = Math.round(cz).toInt() + oz
                        if (x in minX..maxX && z in minZ..maxZ) put(x, y, z, pal(x, y, z))
                    }
                }
            }
        }
    }
}

/** Schwere Kette als hängende Linie mit Durchhang, aus zwei Gliedarten im Wechsel. */
fun BlockCanvas.heavyChain(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, sag: Double, a: BlockState, b: BlockState, thick: Boolean = false) {
    val length = maxOf(abs(x2 - x1), abs(y2 - y1), abs(z2 - z1)).coerceAtLeast(1)
    // Quer zur Kette (waagrecht) sitzen bei dicken Ketten die Wangen jedes zweiten Glieds.
    val acrossX = abs(z2 - z1) >= abs(x2 - x1)
    var lastY = Int.MIN_VALUE
    for (i in 0..length) {
        val t = i.toDouble() / length
        val x = x1 + (x2 - x1) * i / length
        val z = z1 + (z2 - z1) * i / length
        val y = y1 + ((y2 - y1) * t - 4.0 * t * (1.0 - t) * sag).roundToInt()
        // Lücken in steilen Stücken schließen, damit die Kette durchgehend hängt.
        if (lastY != Int.MIN_VALUE) for (fill in minOf(lastY, y) + 1 until maxOf(lastY, y)) block(x, fill, z, if (fill % 2 == 0) a else b)
        block(x, y, z, if (i % 2 == 0) a else b)
        if (thick && i % 2 == 0) {
            if (acrossX) {
                block(x - 1, y, z, a)
                block(x + 1, y, z, a)
            } else {
                block(x, y, z - 1, a)
                block(x, y, z + 1, a)
            }
        }
        lastY = y
    }
}

/** Runde Säule: Sockel mit Treppenkranz, kannelierter Schaft und ein auskragendes Kapitell. [r] = 1 ist drei Blöcke breit. */
fun BlockCanvas.column(x: Int, y: Int, z: Int, height: Int, r: Int = 1, style: ColumnStyle = ColumnStyle.PALACE) {
    disc(x, z, r + 1.6) { px, pz, d ->
        put(px, y, pz, if (d > r + 0.9) Kit.stair(style.baseStair, Kit.inward(px - x, pz - z)) else style.base)
    }
    disc(x, z, r + 0.6) { px, pz, d ->
        val dx = abs(px - x)
        val dz = abs(pz - z)
        for (h in 1 until height) {
            val state = when {
                r == 1 && dx == 1 && dz == 1 -> style.flute
                r > 1 && d > r - 0.2 && (dx + dz) % 2 == 0 -> style.abacus
                else -> style.shaft(px, y + h, pz)
            }
            put(px, y + h, pz, state)
        }
    }
    val capital = y + height
    disc(x, z, r + 1.6) { px, pz, d ->
        val dx = px - x
        val dz = pz - z
        put(px, capital, pz, if (d > r + 0.9) Kit.stair(style.capitalStair, Kit.inward(dx, dz), top = true) else style.capital)
        put(px, capital + 1, pz, if (d > r + 0.9) Kit.slab(style.abacusSlab) else style.abacus)
    }
}

/** Feuerschale auf Dreibein: Das ist das, was den Bauwerken des Hades ihr grünes Licht gibt. [stem] Blöcke hoch. */
fun BlockCanvas.brazier(x: Int, y: Int, z: Int, stem: Int = 2, fire: BlockState = Kit.FIRE) {
    for (h in 0 until stem) block(x, y + h, z, Blocks.POLISHED_BLACKSTONE_WALL.defaultState)
    val top = y + stem
    block(x, top, z, Blocks.GILDED_BLACKSTONE.defaultState)
    block(x, top + 1, z, fire)
    for (d in listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
        block(x + d.offsetX, top, z + d.offsetZ, Kit.stair(Blocks.POLISHED_BLACKSTONE_STAIRS, d.opposite, top = true))
    }
}

/** Verhüllter Totenwächter auf einem Sockel; die Hände halten eine Feuerschale, wenn der Stil eine hat. Blickt nach [facing]. */
fun BlockCanvas.statue(x: Int, y: Int, z: Int, facing: Direction, height: Int = 12, style: StatueStyle = Palettes.Palace.STATUE) {
    val side = facing.rotateYClockwise()
    fun at(a: Int, f: Int, h: Int, state: BlockState) = block(x + side.offsetX * a + facing.offsetX * f, y + h, z + side.offsetZ * a + facing.offsetZ * f, state)
    // Sockel: unten eine Stufe, darauf der Block.
    for (a in -2..2) for (f in -2..2) at(a, f, 0, if (abs(a) == 2 || abs(f) == 2) style.plinth else style.base)
    for (a in -1..1) for (f in -1..1) at(a, f, 1, style.base)
    for (a in -2..2) {
        at(a, 2, 1, Kit.stair(style.plinthStair, facing.opposite))
        at(a, -2, 1, Kit.stair(style.plinthStair, facing))
    }
    val base = 2
    val shoulder = height - 4
    // Gewand: unten breit, nach oben schmaler.
    for (h in 0 until shoulder) {
        val spread = if (h < shoulder / 2) 1 else 0
        for (a in -1..1) {
            for (f in -1..spread - 1) {
                if (abs(a) == 1 && f == -1 && h > shoulder / 2) continue
                at(a, f, base + h, if ((a + h) % 3 == 0) style.body else style.robe)
            }
        }
    }
    // Schultern und ein Kopf unter der Kapuze.
    for (a in -2..2) at(a, 0, base + shoulder, style.body)
    for (a in -1..1) {
        at(a, 0, base + shoulder + 1, style.robe)
        at(a, 0, base + shoulder + 2, style.robe)
        at(a, -1, base + shoulder + 2, style.robe)
    }
    at(0, 1, base + shoulder + 1, style.face)
    at(-1, 1, base + shoulder + 1, Kit.stair(style.hood, facing.opposite, top = true))
    at(1, 1, base + shoulder + 1, Kit.stair(style.hood, facing.opposite, top = true))
    at(0, 1, base + shoulder + 2, Kit.stair(style.hood, facing.opposite))
    // Arme nach vorn, die Schale in den Händen.
    for (a in intArrayOf(-2, 2)) {
        at(a, 1, base + shoulder - 1, Kit.stair(style.hood, side, top = true))
        at(a, 1, base + shoulder - 2, style.robe)
    }
    if (style.bowl != null) {
        for (a in -1..1) at(a, 2, base + shoulder - 2, style.bowl)
        if (style.flame != null) at(0, 2, base + shoulder - 1, style.flame)
    }
}

/** Schädel für Nischen, Podeste und Zäune; [rotation] 0..15 wie in Vanilla. */
fun skull(rotation: Int, wither: Boolean = false): BlockState =
    (if (wither) Blocks.WITHER_SKELETON_SKULL else Blocks.SKELETON_SKULL).defaultState.with(Properties.ROTATION, rotation and 15)

/** Schädel, der aus einer Richtung schaut (für Nischen). */
fun skullFacing(facing: Direction, wither: Boolean = false): BlockState = skull(
    when (facing) {
        Direction.SOUTH -> 0
        Direction.WEST -> 4
        Direction.NORTH -> 8
        else -> 12
    },
    wither,
)

/** Unterirdische Wurzelhöhe einer Stütze: So viel Bodenkontakt braucht ein Pfeiler, damit er nicht schwebt. */
fun BlockCanvas.footing(x: Int, z: Int, top: Int, pal: Pal, deepest: Int = 60) {
    if (x !in minX..maxX || z !in minZ..maxZ) return
    val floor = ground(x, z)
    var y = top
    var count = 0
    while (y >= floor - 1 && count++ < deepest) put(x, y--, z, pal(x, y + 1, z))
}

/**
 * Ebnet das Gelände für ein Bauwerk: Innerhalb von [half] (Quadrat oder Kreis) wird bis [y] - 1 aufgefüllt und darüber
 * [clear] Blöcke freigeräumt; im Rand [apron] geht die Fläche weich in den echten Boden über, als Böschung oder Anschnitt.
 */
fun BlockCanvas.pad(cx: Int, cz: Int, half: Double, y: Int, apron: Int, fill: Pal, square: Boolean, clear: Int = 40, top: Pal? = null) {
    val reach = half + apron
    for (x in maxOf(cx - reach.toInt() - 1, minX)..minOf(cx + reach.toInt() + 1, maxX)) {
        for (z in maxOf(cz - reach.toInt() - 1, minZ)..minOf(cz + reach.toInt() + 1, maxZ)) {
            val dx = (x - cx).toDouble()
            val dz = (z - cz).toDouble()
            val d = if (square) maxOf(abs(dx), abs(dz)) else hypot(dx, dz)
            if (d > reach) continue
            val natural = ground(x, z) - 1
            if (d <= half) {
                footing(x, z, y - 1, fill)
                if (top != null) put(x, y - 1, z, top(x, y - 1, z))
                for (h in y..y + clear) put(x, h, z, Kit.AIR)
                continue
            }
            val t = (d - half) / apron
            val target = (y - 1 + (natural - (y - 1)) * (t * t * (3 - 2 * t))).roundToInt()
            val skin = surfaceState(x, z)
            if (target > natural) {
                for (h in natural + 1..target) put(x, h, z, if (h == target && skin != null) skin else fill(x, h, z))
            } else if (target < natural) {
                for (h in target + 1..natural) put(x, h, z, Kit.AIR)
                if (skin != null) put(x, target, z, skin)
            }
        }
    }
}

/** Schutt am Fuß einer Mauer: Treppen und Platten im Staub, an Ecken häufiger. */
fun BlockCanvas.rubble(x: Int, z: Int, edge: Double, style: WallStyle, limitY: Int) {
    if (!inBox(x, z)) return
    val n = Kit.noise(x, 7, z)
    val chance = 0.08 + 0.22 * (1.0 - edge.coerceIn(0.0, 1.0))
    if (n % 1000 >= chance * 1000) return
    val y = ground(x, z)
    if (y > limitY + 1) return
    val facing = Direction.fromHorizontalQuarterTurns(n / 1000 % 4)
    put(x, y, z, if ((n / 7) % 3 == 0) Kit.slab(style.rubbleSlab) else Kit.stair(style.rubbleStair, facing))
}

/**
 * Gerade Wand mit Tiefe: Sockel, Fläche mit Verwitterung, alle [pierEvery] Blöcke ein Pfeiler (oder Strebepfeiler),
 * dazwischen Nischen, oben ein Gesims aus umgedrehten Treppen und Zinnen, am Fuß Schutt.
 *
 * Läuft entlang x ([alongX]) oder z von [a0] bis [a1], Mittellinie [line], [thick] Blöcke dick (ungerade), Boden [y],
 * Höhe [height]. [sides] sagt, welche Seiten außen liegen (-1 = zur kleineren Koordinate, +1 = zur größeren).
 */
fun BlockCanvas.wall(
    alongX: Boolean, a0: Int, a1: Int, line: Int, y: Int, height: Int, thick: Int, style: WallStyle,
    sides: IntArray = intArrayOf(-1, 1), pierEvery: Int = 10, crestSides: IntArray = sides, bandAt: Int = -1,
    niche: ((Int, Int, Int, Direction) -> Unit)? = null,
) {
    val half = thick / 2
    val lo = minOf(a0, a1)
    val hi = maxOf(a0, a1)
    val length = hi - lo
    fun px(a: Int, d: Int) = if (alongX) a else line + d
    fun pz(a: Int, d: Int) = if (alongX) line + d else a
    fun normal(s: Int) = if (alongX) (if (s > 0) Direction.SOUTH else Direction.NORTH) else (if (s > 0) Direction.EAST else Direction.WEST)
    for (a in lo..hi) {
        val i = a - lo
        val edge = minOf(i, length - i) / 5.0
        for (d in -half..half) {
            val x = px(a, d)
            val z = pz(a, d)
            if (!inBox(x, z)) continue
            footing(x, z, y, style.plinth)
            val outer = abs(d) == half
            for (h in 1..height) {
                val t = h.toDouble() / height
                put(x, y + h, z, if (outer && Kit.wear(x, y + h, z, t, edge)) style.worn(x, y + h, z) else style.face(x, y + h, z))
            }
            if (outer && bandAt > 0 && style.band != null) put(x, y + bandAt, z, style.band.invoke(x, y + bandAt, z))
        }
        for (s in sides) {
            val toWall = normal(s).opposite
            val ox = px(a, s * (half + 1))
            val oz = pz(a, s * (half + 1))
            val fx = px(a, s * half)
            val fz = pz(a, s * half)
            val pier = pierEvery > 0 && i % pierEvery == 0
            footing(ox, oz, y, style.plinth)
            if (pier) {
                for (h in 1..height - 2) block(ox, y + h, oz, style.pier(ox, y + h, oz))
                if (style.pierDepth >= 2) {
                    // Strebepfeiler: eine zweite Lage bis zwei Drittel der Höhe, oben abgeschrägt.
                    val bx = px(a, s * (half + 2))
                    val bz = pz(a, s * (half + 2))
                    footing(bx, bz, y, style.plinth)
                    val top = height * 2 / 3
                    for (h in 1..top) block(bx, y + h, bz, style.pier(bx, y + h, bz))
                    block(bx, y + top + 1, bz, Kit.stair(style.plinthStair, toWall))
                }
            } else {
                block(ox, y + 1, oz, style.plinth(ox, y + 1, oz))
                if (height >= 10) {
                    block(ox, y + 2, oz, style.plinth(ox, y + 2, oz))
                    block(ox, y + 3, oz, Kit.stair(style.plinthStair, toWall))
                } else {
                    block(ox, y + 2, oz, Kit.stair(style.plinthStair, toWall))
                }
                // Laufspuren unter dem Gesims, wo das Wasser die Bronze grün gefärbt hat.
                if (style.streak != null && Kit.noise(fx, 3, fz) % 7 == 0) {
                    for (h in height - 4..height - 2) block(fx, y + h, fz, style.streak.invoke(fx, y + h, fz))
                }
                // Nische mitten zwischen zwei Pfeilern.
                if (niche != null && pierEvery > 0 && i % pierEvery == pierEvery / 2 && thick >= 3 && height >= 9 && inBox(fx, fz)) {
                    for (h in 3..5) put(fx, y + h, fz, Kit.AIR)
                    put(fx, y + 6, fz, Kit.stair(style.cornice, toWall, top = true))
                    niche(fx, y + 3, fz, normal(s))
                }
            }
            block(ox, y + height - 1, oz, Kit.stair(style.cornice, toWall, top = true))
            if (s in crestSides && inBox(fx, fz) && i % 3 != 2 && !Kit.wear(fx, y + height + 1, fz, 0.6, edge)) {
                put(fx, y + height + 1, fz, style.merlon(fx, y + height + 1, fz))
                val cap = if (i % 3 == 1 && style.merlonCapAlt != null) style.merlonCapAlt else style.merlonCap
                if (cap != null) put(fx, y + height + 2, fz, Kit.slab(cap))
            }
            val reach = half + 2 + if (pier && style.pierDepth >= 2) 1 else 0
            rubble(px(a, s * reach), pz(a, s * reach), edge, style, y)
            rubble(px(a, s * (reach + 1)), pz(a, s * (reach + 1)), edge + 0.6, style, y)
        }
    }
}

/**
 * Eckiger Turm mit geböschtem Fuß, Eckquadern, Bändern, Schlitzen und einer flachen Zinnenkrone auf Kragsteinen.
 * [half] ist die halbe Seitenlänge des Schafts, [batter] wie weit der Fuß vorspringt.
 */
fun BlockCanvas.squareTower(
    cx: Int, cz: Int, y: Int, half: Int, height: Int, style: WallStyle, batter: Int = 2, hollow: Boolean = true,
    bandEvery: Int = 8, crown: Boolean = true, slits: Boolean = true, grounded: Boolean = true,
) {
    val foot = half + batter
    if (grounded) for (x in cx - foot..cx + foot) for (z in cz - foot..cz + foot) footing(x, z, y, style.plinth)
    // Der geböschte Fuß: Stufe um Stufe zurückgesetzt, jede Stufe oben mit einer Schräge.
    for (k in 0 until batter) {
        val r = foot - k
        for (h in 1 + 2 * k..2 + 2 * k) {
            for (x in cx - r..cx + r) for (z in cz - r..cz + r) {
                if (maxOf(abs(x - cx), abs(z - cz)) > half) block(x, y + h, z, style.plinth(x, y + h, z))
            }
        }
        val capY = y + 3 + 2 * k
        for (x in cx - r..cx + r) for (z in cz - r..cz + r) {
            if (maxOf(abs(x - cx), abs(z - cz)) == r) block(x, capY, z, Kit.stair(style.plinthStair, Kit.inward(x - cx, z - cz)))
        }
    }
    // Schaft: zwei Blöcke Wand, an den Ecken Quader, alle paar Lagen ein Band.
    for (x in cx - half..cx + half) {
        for (z in cz - half..cz + half) {
            val dx = abs(x - cx)
            val dz = abs(z - cz)
            val ring = maxOf(dx, dz)
            val corner = dx == half && dz == half
            val edge = (half - minOf(dx, dz)) / 3.0
            for (h in 1..height) {
                val state = when {
                    hollow && ring < half - 1 && h < height -> Kit.AIR
                    corner -> if ((h / 2) % 2 == 0) style.pier(x, y + h, z) else style.face(x, y + h, z)
                    ring == half && bandEvery > 0 && h % bandEvery == 0 && h <= height - 2 && style.band != null -> style.band.invoke(x, y + h, z)
                    ring == half && Kit.wear(x, y + h, z, h.toDouble() / height, edge) -> style.worn(x, y + h, z)
                    else -> style.face(x, y + h, z)
                }
                block(x, y + h, z, state)
            }
        }
    }
    if (slits && hollow) {
        for (d in Direction.Type.HORIZONTAL) {
            for (level in intArrayOf(height / 3, height * 2 / 3)) {
                for (h in level - 1..level + 1) block(cx + d.offsetX * half, y + h, cz + d.offsetZ * half, Kit.AIR)
                block(cx + d.offsetX * half, y + level + 2, cz + d.offsetZ * half, style.pier(cx, y + level + 2, cz))
            }
        }
    }
    if (!crown) return
    // Krone: Kragsteine, eine vorspringende Brüstung und flache Zinnen; die Ecken stehen höher.
    val top = y + height
    val r = half + 1
    for (x in cx - r..cx + r) {
        for (z in cz - r..cz + r) {
            val dx = abs(x - cx)
            val dz = abs(z - cz)
            if (maxOf(dx, dz) != r) continue
            block(x, top - 1, z, Kit.stair(style.cornice, Kit.inward(x - cx, z - cz), top = true))
            block(x, top, z, style.face(x, top, z))
            val corner = dx == r && dz == r
            val along = if (dx == r) dz else dx
            if (corner || along % 2 == 0) {
                if (!corner && Kit.wear(x, top + 1, z, 0.7, 1.0)) continue
                block(x, top + 1, z, style.merlon(x, top + 1, z))
                if (corner) block(x, top + 2, z, style.merlon(x, top + 2, z))
                if (style.merlonCap != null) block(x, top + if (corner) 3 else 2, z, Kit.slab(style.merlonCap))
            }
        }
    }
}

/** Heller Abstand in der Ebene, ohne dass überall Math.hypot steht. */
internal fun dist(dx: Int, dz: Int): Double = hypot(dx.toDouble(), dz.toDouble())
