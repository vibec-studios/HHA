package dev.henny.hha.world.arena

import net.minecraft.block.Block
import net.minecraft.registry.RegistryKey
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Geometrie-Vertrag der Arenen (Überarbeitung 4, PLAN 7.2; P00 vollständig, danach eingefroren). Bau-Pakete bauen
 * genau dorthin, Kampf-Pakete lesen nur hier bzw. in `OlympusArenas` und `TartarusArenas`.
 *
 * Vereinbarungen:
 * - Zonen rechnen in **Weltkoordinaten** (x, z als Double). Eine Blockspalte (x, z) gehört zur Zone, wenn ihre Mitte
 *   (x + 0,5, z + 0,5) darin liegt ([Zone.cells]). Kreise um eine Arenamitte `BlockPos c` haben die Mitte
 *   (c.x + 0,5, c.z + 0,5); so stimmen sie mit den Bauplänen überein, die mit Blockabständen rechnen.
 * - Winkel in Grad: 0° = Osten (+x), 90° = Süden (+z), 180° = Westen, 270° = Norden (wie `OlympusLayout`).
 * - `floorY`/`coreY` = Höhe der **Füße** (Oberkante des Bodens); der Bodenblock liegt eine Reihe tiefer.
 * - Ringmaße „r a–b“ aus dem PLAN meinen die Blockringe a … b (Blockabstand zur Mitte): `Sector(inner = a − 0,5,
 *   outer = b + 0,5)` bzw. [ArenaGeo.ring]. Ein Kern „r k“ endet am Blockring k.
 */

/** Eine Fläche von oben gesehen. */
sealed interface Zone {
    fun contains(x: Double, z: Double): Boolean

    /** Alle Blockspalten, deren Mitte in der Zone liegt. */
    fun cells(): Sequence<Pair<Int, Int>>

    /** Umschließender Kasten der Spalten (minX, minZ, maxX, maxZ). */
    fun bounds(): IntArray

    fun containsCell(x: Int, z: Int): Boolean = contains(x + 0.5, z + 0.5)
}

private fun scan(minX: Int, minZ: Int, maxX: Int, maxZ: Int, test: (Double, Double) -> Boolean): Sequence<Pair<Int, Int>> = sequence {
    for (x in minX..maxX) for (z in minZ..maxZ) if (test(x + 0.5, z + 0.5)) yield(x to z)
}

/** Kreis bzw. Ring um (cx, cz): `inner < r ≤ radius` (bei inner = 0 inklusive Mitte). */
class Disc(val cx: Double, val cz: Double, val radius: Double, val inner: Double = 0.0) : Zone {
    override fun contains(x: Double, z: Double): Boolean {
        val r = hypot(x - cx, z - cz)
        return r <= radius && (inner <= 0.0 || r > inner)
    }
    override fun bounds(): IntArray = intArrayOf(floor(cx - radius).toInt(), floor(cz - radius).toInt(), floor(cx + radius).toInt(), floor(cz + radius).toInt())
    override fun cells(): Sequence<Pair<Int, Int>> { val b = bounds(); return scan(b[0], b[1], b[2], b[3], ::contains) }

    companion object {
        /** Kreis um die Mitte des Blocks [c]. */
        fun around(c: BlockPos, radius: Double, inner: Double = 0.0) = Disc(c.x + 0.5, c.z + 0.5, radius, inner)
    }
}

/**
 * Ringstück um (cx, cz): `inner < r ≤ outer` und Winkel von [from] bis [to] (Grad, im Uhrzeigersinn von oben, also in
 * Richtung wachsender Winkel). [to] darf über 360 hinausgehen (290 … 438 = 290° … 78°).
 */
class Sector(val cx: Double, val cz: Double, val inner: Double, val outer: Double, val from: Double, val to: Double) : Zone {
    fun angleIn(deg: Double): Boolean {
        val span = to - from
        if (span >= 360.0) return true
        val d = ((deg - from) % 360.0 + 360.0) % 360.0
        return d <= span
    }
    override fun contains(x: Double, z: Double): Boolean {
        val r = hypot(x - cx, z - cz)
        if (r > outer || r <= inner) return false
        return angleIn(ArenaGeo.angleOf(x - cx, z - cz))
    }
    override fun bounds(): IntArray = intArrayOf(floor(cx - outer).toInt(), floor(cz - outer).toInt(), floor(cx + outer).toInt(), floor(cz + outer).toInt())
    override fun cells(): Sequence<Pair<Int, Int>> { val b = bounds(); return scan(b[0], b[1], b[2], b[3], ::contains) }

    companion object {
        fun around(c: BlockPos, inner: Double, outer: Double, from: Double, to: Double) = Sector(c.x + 0.5, c.z + 0.5, inner, outer, from, to)
    }
}

/** Rechteck aus Blockspalten, Grenzen eingeschlossen. */
class Rect(val minX: Int, val minZ: Int, val maxX: Int, val maxZ: Int) : Zone {
    override fun contains(x: Double, z: Double): Boolean = x >= minX && x < maxX + 1 && z >= minZ && z < maxZ + 1
    override fun bounds(): IntArray = intArrayOf(minX, minZ, maxX, maxZ)
    override fun cells(): Sequence<Pair<Int, Int>> = scan(minX, minZ, maxX, maxZ) { _, _ -> true }
}

/**
 * Streifen entlang der Strecke (ax, az) → (bx, bz) mit halber Breite [halfWidth], gerade Enden (kein Halbkreis).
 * Für Wege, Ketten und Fallgitter. (Zusatz von P00 zu PLAN 7.2.)
 */
class Strip(val ax: Double, val az: Double, val bx: Double, val bz: Double, val halfWidth: Double) : Zone {
    private val len2 = (bx - ax) * (bx - ax) + (bz - az) * (bz - az)
    /** Lage entlang der Strecke (0 am Anfang, 1 am Ende) und Querabstand. */
    fun along(x: Double, z: Double): Double = if (len2 <= 0.0) 0.0 else ((x - ax) * (bx - ax) + (z - az) * (bz - az)) / len2
    fun across(x: Double, z: Double): Double {
        val t = along(x, z)
        return hypot(x - (ax + (bx - ax) * t), z - (az + (bz - az) * t))
    }
    override fun contains(x: Double, z: Double): Boolean {
        val t = along(x, z)
        return t in 0.0..1.0 && across(x, z) <= halfWidth
    }
    override fun bounds(): IntArray = intArrayOf(
        floor(minOf(ax, bx) - halfWidth).toInt(), floor(minOf(az, bz) - halfWidth).toInt(),
        floor(maxOf(ax, bx) + halfWidth).toInt(), floor(maxOf(az, bz) + halfWidth).toInt(),
    )
    override fun cells(): Sequence<Pair<Int, Int>> { val b = bounds(); return scan(b[0], b[1], b[2], b[3], ::contains) }
}

/** Vereinigung mehrerer Zonen (Kern aus Platz und Wegen). (Zusatz von P00.) */
class Union(val parts: List<Zone>) : Zone {
    override fun contains(x: Double, z: Double): Boolean = parts.any { it.contains(x, z) }
    override fun bounds(): IntArray {
        val all = parts.map { it.bounds() }
        return intArrayOf(all.minOf { it[0] }, all.minOf { it[1] }, all.maxOf { it[2] }, all.maxOf { it[3] })
    }
    override fun cells(): Sequence<Pair<Int, Int>> { val b = bounds(); return scan(b[0], b[1], b[2], b[3], ::contains) }
}

/** [base] ohne die [holes] (Saal ohne Thronpodest). (Zusatz von P00.) */
class Cut(val base: Zone, val holes: List<Zone>) : Zone {
    override fun contains(x: Double, z: Double): Boolean = base.contains(x, z) && holes.none { it.contains(x, z) }
    override fun bounds(): IntArray = base.bounds()
    override fun cells(): Sequence<Pair<Int, Int>> { val b = bounds(); return scan(b[0], b[1], b[2], b[3], ::contains) }
}

/**
 * Begehbare Ebene: [zone] auf Höhe [floorY] (Füße). [access] sind die Stellen, an denen Treppen- oder Stufenblöcke
 * hinauf führen (der Prüflauf verlangt an mindestens einer einen solchen Block).
 */
class Level(val id: String, val zone: Zone, val floorY: Int, val access: List<BlockPos>)

/** Deckung: fester Körper im Kasten [box]; schützt gegen die Angriffe [protects] (IDs aus PLAN 7.2). */
class CoverSpot(val id: String, val box: BlockBox, val protects: Set<String>, val breakable: Boolean) {
    val center: Vec3d get() = Vec3d((box.minX + box.maxX + 1) / 2.0, box.minY.toDouble(), (box.minZ + box.maxZ + 1) / 2.0)
}

/**
 * Bewegliches Teil (Scholle, Emporenbogen, Kette): alle Blöcke in [zone] zwischen [minY] und [maxY]. [fills] = im Plan
 * leer, der Kampf füllt es (Fallgitter).
 */
class Part(val id: String, val zone: Zone, val minY: Int, val maxY: Int, val fills: Boolean = false)

/** Gerät (`ArenaDeviceBlock`) an [pos], Vorderseite nach [facing] (zur Arenamitte). */
class DeviceSpot(val id: String, val pos: BlockPos, val block: () -> Block, val facing: Direction)

/** Wegpunkt: „erreicht“, sobald ein Spieler waagerecht näher als [reach] ist. */
class Waypoint(val id: String, val pos: Vec3d, val reach: Double)

/** Ein Anmarsch: Wegpunkte in Laufrichtung, halbe Breite der Zone, Klang (wird zum Ziel hin lauter). */
class ApproachSpec(
    val id: String, val bossKey: String, val world: RegistryKey<World>, val points: List<Waypoint>,
    val halfWidth: Double, val sound: () -> SoundEvent,
)

class ArenaSpec(
    val id: String,
    /** Mitte (Block); `coreY` gilt als Füße-Höhe der Kernfläche. */
    val center: BlockPos,
    /** Radius der Arena (Schutz, Beitritt) um [center]. */
    val radius: Int,
    /** Ebene Kernfläche, auf der der Boss läuft. */
    val core: Zone,
    val coreY: Int,
    val levels: List<Level>,
    val cover: List<CoverSpot>,
    val parts: List<Part>,
    val devices: List<DeviceSpot>,
    /** Kasten für Bauplan, Schutz und Reparatur. */
    val box: BlockBox,
    /** Flächen außerhalb des Kreises, die zur Arena zählen (Treppe auf E, Thronbühne auf F). */
    val extra: List<Zone> = emptyList(),
) {
    fun level(id: String): Level = levels.first { it.id == id }
    fun cover(id: String): CoverSpot = cover.first { it.id == id }
    fun part(id: String): Part = parts.first { it.id == id }

    /** Alle Böden: Kern zuerst, dann die Ebenen. */
    fun floors(): List<Pair<Zone, Int>> = listOf(core to coreY) + levels.map { it.zone to it.floorY }

    /** Mitte als Vektor (Füße auf der Kernfläche). */
    val centerVec: Vec3d get() = Vec3d(center.x + 0.5, coreY.toDouble(), center.z + 0.5)

    /** Liegt (x, z) in der Arena (Kreis [radius] oder eine der [extra]-Flächen)? */
    fun inArena(x: Double, z: Double): Boolean =
        hypot(x - (center.x + 0.5), z - (center.z + 0.5)) <= radius || extra.any { it.contains(x, z) }
}

/** Kleine Rechenhilfen für die Verträge (auch für Bau-Pakete, damit Bau und Vertrag gleich runden). */
object ArenaGeo {

    /** Ringstück der Blockringe [from] … [to] (PLAN-Maß „r from–to“) unter den Winkeln [a0] … [a1] um den Block [c]. */
    fun ring(c: BlockPos, from: Double, to: Double, a0: Double, a1: Double): Sector = Sector.around(c, from - 0.5, to + 0.5, a0, a1)

    /** Winkel von (dx, dz) in Grad, 0 … 360 (0° = Osten, 90° = Süden). */
    fun angleOf(dx: Double, dz: Double): Double = (Math.toDegrees(atan2(dz, dx)) + 360.0) % 360.0

    /** Block im Abstand [r] unter dem Winkel [deg] um die Mitte des Blocks [c] (gerundet). */
    fun polar(c: BlockPos, r: Double, deg: Double, y: Int = c.y): BlockPos {
        val a = Math.toRadians(deg)
        return BlockPos(floor(c.x + 0.5 + r * cos(a)).toInt(), y, floor(c.z + 0.5 + r * sin(a)).toInt())
    }

    /** Weltpunkt im Abstand [r] unter [deg] um die Mitte des Blocks [c]. */
    fun polarVec(c: BlockPos, r: Double, deg: Double, y: Double): Vec3d {
        val a = Math.toRadians(deg)
        return Vec3d(c.x + 0.5 + r * cos(a), y, c.z + 0.5 + r * sin(a))
    }

    /** Kasten 3 × 3 um den Block (x, z), von [y0] an [height] hoch. */
    fun box3(x: Int, z: Int, y0: Int, height: Int): BlockBox = BlockBox(x - 1, y0, z - 1, x + 1, y0 + height - 1, z + 1)

    /** Kasten n × n um den Block (x, z) (n ungerade), von [y0] an [height] hoch. */
    fun boxN(x: Int, z: Int, n: Int, y0: Int, height: Int): BlockBox {
        val h = n / 2
        return BlockBox(x - h, y0, z - h, x + h, y0 + height - 1, z + h)
    }

    /** Kasten 2 × 2 um den Weltpunkt (wx, wz) (Mitte an einer Blockecke, gerundet), von [y0] an [height] hoch. */
    fun box2(wx: Double, wz: Double, y0: Int, height: Int): BlockBox {
        val x = wx.roundToInt()
        val z = wz.roundToInt()
        return BlockBox(x - 1, y0, z - 1, x, y0 + height - 1, z)
    }

    /**
     * Treppenzellen: [steps] Stufen, die vom Weltpunkt ([sx], [sz]) (Mitte der ersten Stufe) in Richtung [deg] je einen
     * Block weiter und einen höher führen ([y0] = Höhe des ersten Treppenblocks), [width] Blöcke breit quer zur Richtung.
     */
    fun stairCells(sx: Double, sz: Double, deg: Double, steps: Int, width: Int, y0: Int): List<BlockPos> {
        val a = Math.toRadians(deg)
        val dx = cos(a)
        val dz = sin(a)
        val out = LinkedHashSet<BlockPos>()
        for (s in 0 until steps) {
            for (j in 0 until width) {
                val l = j - (width - 1) / 2.0
                val x = sx + dx * s - dz * l
                val z = sz + dz * s + dx * l
                out.add(BlockPos(floor(x).toInt(), y0 + s, floor(z).toInt()))
            }
        }
        return out.toList()
    }

    /** Radial nach außen steigende Treppe um die Mitte des Blocks [c]: erste Stufe auf Blockring [r0] unter [deg]. */
    fun radialStairs(c: BlockPos, deg: Double, r0: Double, steps: Int, width: Int, y0: Int): List<BlockPos> {
        val a = Math.toRadians(deg)
        return stairCells(c.x + 0.5 + r0 * cos(a), c.z + 0.5 + r0 * sin(a), deg, steps, width, y0)
    }
}
