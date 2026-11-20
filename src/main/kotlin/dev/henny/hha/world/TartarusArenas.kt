package dev.henny.hha.world

import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.HhaSounds
import dev.henny.hha.logic.world.HadesGate
import dev.henny.hha.world.arena.ApproachSpec
import dev.henny.hha.world.arena.ArenaGeo
import dev.henny.hha.world.arena.ArenaSpec
import dev.henny.hha.world.arena.CoverSpot
import dev.henny.hha.world.arena.Cut
import dev.henny.hha.world.arena.DeviceSpot
import dev.henny.hha.world.arena.Disc
import dev.henny.hha.world.arena.Level
import dev.henny.hha.world.arena.Part
import dev.henny.hha.world.arena.Rect
import dev.henny.hha.world.arena.Sector
import dev.henny.hha.world.arena.Strip
import dev.henny.hha.world.arena.Union
import dev.henny.hha.world.arena.Waypoint
import dev.henny.hha.world.olymp.OlympusArenas
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Geometrie-Vertrag der Tartaros-Arenen und -Wege (Überarbeitung 4, PLAN 3.6, 3.7, 5.2, 5.3, 7.2; P00, eingefroren).
 * Alles relativ zur gespeicherten Bauwerksmitte `o` (`TartarusPiece.middle`, Bodenblock bei `o.y`). Die Kernmaße passen
 * in alte Bauten; geprüft gegen die Bestandspläne (Abweichungen vom PLAN in `tmp/overhaul4/API.md`).
 */
object TartarusArenas {

    // --- Angriffs-IDs der Deckung (PLAN 7.2) -----------------------------------------------------------------------
    const val HADES_GAZE = "hades.gaze"
    const val HADES_LUNGE = "hades.lunge"
    const val HADES_FETTER = "hades.fetter"
    const val KRONOS_STASIS = "kronos.stasis"
    const val WARDEN_CHAIN = "warden.chain"
    const val WARDEN_SWEEP = "warden.sweep"
    const val KAMPE_DIVE = "kampe.dive"
    const val SISYPHUS_BOULDER = "sisyphus.boulder"
    const val IXION_SPOKES = "ixion.spokes"
    const val TANTALUS_TRIDENT = "tantalus.trident"

    /** Bauplan-IDs (PLAN 7.3) der Tartaros-Kerne. */
    const val HALL_ID = "palace_hall"
    const val FORTRESS_ID = "fortress_court"
    const val PRISON_ID = "prison_pit"
    const val SISYPHUS_ID = "sisyphus_hill"
    const val IXION_ID = "ixion_wheel"
    const val TANTALUS_ID = "tantalus_basin"

    private fun dev(id: String, pos: BlockPos, block: () -> net.minecraft.block.Block, o: BlockPos): DeviceSpot =
        DeviceSpot(id, pos, block, OlympusArenas.facingToward(ArenaGeo.angleOf(pos.x - o.x.toDouble(), pos.z - o.z.toDouble())))

    // =============================================================================================================
    // 3.6 Hades und Kronos: Thronsaal
    // =============================================================================================================

    const val HALL_ARENA = 44
    const val HALL_CORE = 38.0
    const val HALL_WALK_INNER = 39.0
    const val HALL_WALK_OUTER = 44.0
    const val HALL_WALK_HEIGHT = 2
    /** Die zwei Bögen des Umgangs (von, bis; Grad, über 360 hinweg): Süd bleibt für das Siegel frei, Nord für den Thron. */
    val HALL_WALKS = listOf(290.0 to 438.0, 102.0 to 250.0)
    val HALL_WALK_STAIRS = doubleArrayOf(45.0, 135.0, 225.0, 315.0)
    const val HALL_WALK_STAIR_FROM = 36.0
    const val HALL_WALK_STAIR_WIDTH = 5
    /** Thronpodest: drei Stufen um den Thron (Radien 15 / 12,6 / 10,2), oben 3 über dem Saal. */
    const val HALL_DAIS_TOP = 9.2
    const val HALL_DAIS_FOOT = 16.0
    /** Einsturzringe (Hades Phase III): 44 → 36 → 28. */
    val HALL_COLLAPSE = listOf(44.5 to 36.0, 36.0 to 28.0)
    /** Kronos: Säule, die gedeckt hat, zerfällt auf diese Höhe. */
    const val HALL_PILLAR_STUMP = 3

    fun hallCenter(o: BlockPos): BlockPos = o
    fun throneCenter(o: BlockPos): BlockPos = o.add(0, 0, TartarusPlans.THRONE_Z)

    fun hall(o: BlockPos): ArenaSpec {
        val y = o.y + 1
        val throne = throneCenter(o)
        val cover = TartarusPlans.coverPillars().mapIndexed { i, (dx, dz) ->
            CoverSpot("pillar_$i", ArenaGeo.boxN(o.x + dx, o.z + dz, 2 * TartarusPlans.PILLAR_RADIUS + 1, y, TartarusPlans.PILLAR_HEIGHT), setOf(HADES_GAZE, HADES_LUNGE, HADES_FETTER, KRONOS_STASIS), breakable = true)
        }
        // Treppe von r 36 nach r 39: Stufen auf den Blockringen 37 und 38, der Umgang beginnt bei 39.
        val stairs = HALL_WALK_STAIRS.associate { a -> a to ArenaGeo.radialStairs(o, a, HALL_WALK_STAIR_FROM + 1.0, HALL_WALK_HEIGHT, HALL_WALK_STAIR_WIDTH, y) }
        val levels = ArrayList<Level>()
        for ((i, arc) in HALL_WALKS.withIndex()) {
            val zone = ArenaGeo.ring(o, HALL_WALK_INNER, HALL_WALK_OUTER, arc.first, arc.second)
            levels.add(Level(if (i == 0) "walk_east" else "walk_west", zone, y + HALL_WALK_HEIGHT, stairs.filterKeys { zone.angleIn(it) }.values.flatten()))
        }
        val tz = throne.z
        levels.add(Level("dais", Disc.around(throne, HALL_DAIS_TOP), y + TartarusPlans.DAIS_HEIGHT, listOf(BlockPos(o.x, o.y + 1, tz + 15), BlockPos(o.x, o.y + 2, tz + 12), BlockPos(o.x, o.y + 3, tz + 10))))
        val parts = HALL_COLLAPSE.mapIndexed { i, (outer, inner) ->
            Part(if (i == 0) "ring_outer" else "ring_middle", Disc.around(o, outer, inner), o.y - 2, o.y + TartarusPlans.DAIS_CLEAR_HEIGHT)
        }
        return ArenaSpec(
            HALL_ID, o, HALL_ARENA, Cut(Disc.around(o, HALL_CORE), listOf(Disc.around(throne, HALL_DAIS_FOOT))), y, levels, cover, parts, emptyList(),
            BlockBox(o.x - TartarusPlans.HALL, o.y - 3, o.z - TartarusPlans.HALL, o.x + TartarusPlans.HALL, o.y + TartarusPlans.OCULUS_Y + 3, o.z + TartarusPlans.HALL),
        )
    }

    // =============================================================================================================
    // 3.7 Kerkermeister: Bronzefeste (Hof)
    // =============================================================================================================

    /** Die vier Hofpfeiler (aus dem Bestandsplan `FortressPlan.arenaCore`): Mitte relativ zu o, 3 × 3, 7 hoch. */
    val FORTRESS_PILLARS = listOf(-13 to 17, 13 to 17, -21 to 3, 21 to 3)
    const val FORTRESS_PILLAR_HEIGHT = 7
    /** Kettenpfähle bei (±10, +1, 9) und (±10, +1, 23). */
    val FORTRESS_POSTS = listOf(-10 to 9, 10 to 9, -10 to 23, 10 to 23)
    /** Standplatz des Kerkermeisters (wie `TartarosMinibosses`): o + (0, 1, 16). */
    val FORTRESS_SPOT = BlockPos(0, 1, 16)
    /** Hofterrasse +3 vor dem Bergfried: z 7 … 8, x −12 … 12, ohne den Kreis r 9 um den Standplatz (40 Felder). */
    const val FORTRESS_TERRACE_HEIGHT = 3
    const val FORTRESS_TERRACE_HALF = 12
    const val FORTRESS_TERRACE_Z0 = 7
    const val FORTRESS_TERRACE_Z1 = 8
    const val FORTRESS_SPOT_CLEAR = 9.0
    /** Die zwei Käfige im Hof (Bestand, Mitte relativ zu o, 5 × 5): gehören nicht zum Kern. */
    val FORTRESS_CAGES = listOf(-21 to 20, 21 to 20)
    /** Fuß der südlichen Ecktürme im Hof (Bestand): x ±22 … 25, z 22 … 24. */
    val FORTRESS_TOWER_FEET = listOf(Rect(-25, 22, -22, 24), Rect(22, 22, 25, 24))

    fun fortressSpot(o: BlockPos): BlockPos = o.add(FORTRESS_SPOT)

    fun fortress(o: BlockPos): ArenaSpec {
        val y = o.y + 1
        val cover = FORTRESS_PILLARS.mapIndexed { i, (dx, dz) ->
            CoverSpot("pillar_$i", ArenaGeo.box3(o.x + dx, o.z + dz, y, FORTRESS_PILLAR_HEIGHT), setOf(WARDEN_CHAIN), breakable = false)
        }
        val devices = FORTRESS_POSTS.mapIndexed { i, (dx, dz) -> dev("post_$i", o.add(dx, 1, dz), { HhaDecoBlocks.CHAIN_POST }, o) }
        val spot = fortressSpot(o)
        val terrace = Cut(
            Rect(o.x - FORTRESS_TERRACE_HALF, o.z + FORTRESS_TERRACE_Z0, o.x + FORTRESS_TERRACE_HALF, o.z + FORTRESS_TERRACE_Z1),
            listOf(Disc.around(spot, FORTRESS_SPOT_CLEAR)),
        )
        // Treppe an beiden Enden: drei Stufen bei |x| = 15, 14, 13 (y +0, +1, +2), dann die Terrasse.
        val access = intArrayOf(-1, 1).flatMap { side ->
            (0 until FORTRESS_TERRACE_HEIGHT).flatMap { k -> (FORTRESS_TERRACE_Z0..FORTRESS_TERRACE_Z1).map { z -> BlockPos(o.x + side * (FORTRESS_TERRACE_HALF + 3 - k), y + k, o.z + z) } }
        }
        val levels = listOf(Level("terrace", terrace, y + FORTRESS_TERRACE_HEIGHT, access))
        // Hof: vor dem Bergfried (z 7 … 24) und die beiden Seitenhöfe neben ihm (x ±15 … 25, z −21 … 24), ohne die zwei
        // Käfige des Bestands (Mitte (±21, 20), 5 × 5).
        val court = Union(listOf(Rect(o.x - 25, o.z + 7, o.x + 25, o.z + 24), Rect(o.x - 25, o.z - 21, o.x - 15, o.z + 24), Rect(o.x + 15, o.z - 21, o.x + 25, o.z + 24)))
        val holes = FORTRESS_CAGES.map { (dx, dz) -> Rect(o.x + dx - 2, o.z + dz - 2, o.x + dx + 2, o.z + dz + 2) } +
            FORTRESS_TOWER_FEET.map { Rect(o.x + it.minX, o.z + it.minZ, o.x + it.maxX, o.z + it.maxZ) }
        val core = Cut(court, holes)
        return ArenaSpec(
            FORTRESS_ID, o, TartarusPlans.FORTRESS_COURT - 3, core, y, levels, cover, emptyList(), devices,
            BlockBox(o.x - TartarusPlans.FORTRESS_COURT, o.y - 2, o.z - TartarusPlans.FORTRESS_COURT, o.x + TartarusPlans.FORTRESS_COURT, o.y + 24, o.z + TartarusPlans.FORTRESS_COURT),
        )
    }

    // =============================================================================================================
    // 3.7 Kampe: Titanenkerker (Grube)
    // =============================================================================================================

    /** Der Titan als Deckung (aus dem Bestandsplan abgelesen): Rumpf und Knie x −10 … 10, z −6 … 8, bis +23. */
    val PRISON_TITAN = BlockBox(-10, 0, -6, 10, 23, 8)
    /** Sims an der Nordwand: 3 breit an der Wand, also Blockringe 22 … 24, 200° … 340°, +5. */
    const val PRISON_LEDGE_INNER = 22.0
    const val PRISON_LEDGE_OUTER = 24.0
    const val PRISON_LEDGE_FROM = 200.0
    const val PRISON_LEDGE_TO = 340.0
    const val PRISON_LEDGE_HEIGHT = 5
    /** Kettenschlösser (Teilziele) am Grubenboden: r 17 bei 45°, 135°, 225°, 315°. */
    const val PRISON_LOCK_RADIUS = 17.0
    /** Grubenboden innerhalb der Wendelrampe (die Rampe liegt bei r 20,4 … 24). */
    const val PRISON_CORE = 20.0
    /** Halbe Breite des Teils „schwere Kette“ (die Kette ist zwei Glieder dick). */
    const val PRISON_CHAIN_HALF_WIDTH = 2.2
    val PRISON_LOCK_ANGLES = doubleArrayOf(45.0, 135.0, 225.0, 315.0)

    fun prisonFloor(o: BlockPos): Int = o.y - TartarusPlans.PRISON_PIT_DEPTH + 1

    /** Kettenschloss [i] (Füße auf dem Grubenboden). */
    fun prisonLock(o: BlockPos, i: Int): Vec3d = ArenaGeo.polarVec(o, PRISON_LOCK_RADIUS, PRISON_LOCK_ANGLES[i], prisonFloor(o).toDouble())

    /** Die schwere Kette [i] vom Pylon (r 24 bei 45° + 90° · i) zum Handgelenk des Titanen, ohne Pylon- und Handenden. */
    fun prisonChain(o: BlockPos, i: Int): Strip {
        val a = Math.PI / 4.0 + i * Math.PI / 2.0
        val tx = Math.round(cos(a) * 24.0).toDouble()
        val tz = Math.round(sin(a) * 24.0).toDouble()
        val wx = (if (cos(a) > 0) 20 else -20).toDouble()
        val wz = -3.0
        val len = hypot(wx - tx, wz - tz)
        val dx = (wx - tx) / len
        val dz = (wz - tz) / len
        return Strip(o.x + 0.5 + tx + dx * 4.0, o.z + 0.5 + tz + dz * 4.0, o.x + 0.5 + wx - dx * 2.5, o.z + 0.5 + wz - dz * 2.5, PRISON_CHAIN_HALF_WIDTH)
    }

    /** Halbe Breite der Schlosskette (ein Glied dick). (Nachtrag PZ1, Wunsch P12 1.) */
    const val PRISON_LOCK_CHAIN_HALF_WIDTH = 1.2
    /**
     * Das Teil `lock_chain_i` endet so weit (waagerecht) vor dem Ziel, dass es den Titanen nicht berührt: Handgelenk-
     * ketten treffen 3,5 vor dem Ziel den Arm, Schulterketten 0,9 vor dem Ziel den Rumpf (nachgemessen am Plan-Dump).
     * Die letzten Glieder bleiben am Titanen hängen.
     */
    const val PRISON_LOCK_CHAIN_SHORT_WRIST = 4.0
    const val PRISON_LOCK_CHAIN_SHORT_SHOULDER = 1.5

    /**
     * Ziel der Kette vom Schloss [i] (wie `PrisonPlan.lockChain`): die zwei südlichen (45°, 135°) zum Handgelenk
     * (±19, Boden + 20, −3), die zwei nördlichen (225°, 315°) zur Schulter (±9, Boden + 20, −4).
     */
    fun prisonLockChainTarget(o: BlockPos, i: Int): BlockPos {
        val deg = PRISON_LOCK_ANGLES[i]
        val side = if (cos(Math.toRadians(deg)) > 0) 1 else -1
        val ty = prisonFloor(o)
        return if (sin(Math.toRadians(deg)) > 0) BlockPos(o.x + side * 19, ty + 20, o.z - 3) else BlockPos(o.x + side * 9, ty + 20, o.z - 4)
    }

    /**
     * Die Kette vom Schloss [i] zum Titanen (Teil `lock_chain_i`): Start 2 Blöcke vom Schloss Richtung Ziel auf
     * Bodenhöhe, Ende [PRISON_LOCK_CHAIN_SHORT_WRIST] bzw. [PRISON_LOCK_CHAIN_SHORT_SHOULDER] vor dem Ziel.
     */
    fun prisonLockChain(o: BlockPos, i: Int): Strip {
        val lock = BlockPos.ofFloored(prisonLock(o, i))
        val target = prisonLockChainTarget(o, i)
        val vx = (target.x - lock.x).toDouble()
        val vz = (target.z - lock.z).toDouble()
        val len = hypot(vx, vz)
        val sx = lock.x + Math.round(vx / len * 2.0).toInt() + 0.5
        val sz = lock.z + Math.round(vz / len * 2.0).toInt() + 0.5
        val wrist = sin(Math.toRadians(PRISON_LOCK_ANGLES[i])) > 0
        val tx = target.x + 0.5
        val tz = target.z + 0.5
        val keep = 1.0 - (if (wrist) PRISON_LOCK_CHAIN_SHORT_WRIST else PRISON_LOCK_CHAIN_SHORT_SHOULDER) / hypot(tx - sx, tz - sz)
        return Strip(sx, sz, sx + (tx - sx) * keep, sz + (tz - sz) * keep, PRISON_LOCK_CHAIN_HALF_WIDTH)
    }

    fun prison(o: BlockPos): ArenaSpec {
        val y = prisonFloor(o)
        val t = PRISON_TITAN
        val cover = listOf(CoverSpot("titan", BlockBox(o.x + t.minX, y + t.minY, o.z + t.minZ, o.x + t.maxX, y + t.maxY, o.z + t.maxZ), setOf(KAMPE_DIVE), breakable = false))
        val ledge = Level(
            "ledge", ArenaGeo.ring(o, PRISON_LEDGE_INNER, PRISON_LEDGE_OUTER, PRISON_LEDGE_FROM, PRISON_LEDGE_TO), y + PRISON_LEDGE_HEIGHT,
            // Zugang von der Wendelrampe am Ostende des Simses (340°).
            listOf(ArenaGeo.polar(o, 22.5, PRISON_LEDGE_TO + 4.0, y + PRISON_LEDGE_HEIGHT - 1)),
        )
        // Teile je Schloss [i]: `chain_i` schwere Kette Pylon → Handgelenk (o.y + 2 … + 13), `lock_chain_i` die Kette vom
        // Schloss zum Titanen (Grubenboden … o.y + 13). Zwei Teile, weil ein gemeinsamer y-Bereich unter der schweren Kette
        // Rampe und Arm mit abräumen würde. Wird Schloss [i] gesprengt, fallen beide. (PZ1, Wunsch P12 1.)
        val parts = (0 until 4).map { i -> Part("chain_$i", prisonChain(o, i), o.y + 2, o.y + 13) } +
            (0 until 4).map { i -> Part("lock_chain_$i", prisonLockChain(o, i), y, o.y + 13) }
        return ArenaSpec(
            PRISON_ID, o, TartarusPlans.PRISON_PIT_RADIUS, Disc.around(o, PRISON_CORE), y, listOf(ledge), cover, parts, emptyList(),
            BlockBox(o.x - 30, y - 2, o.z - 30, o.x + 30, o.y + 16, o.z + 30),
        )
    }

    // =============================================================================================================
    // 3.7 Sisyphos: Hügel (Gipfel)
    // =============================================================================================================

    /** Gipfelaltar am Nordrand (r 6): 3 breit (x −1 … 1), 2 tief (z −7 … −6), 2 hoch. */
    val SISYPHUS_ALTAR = BlockBox(-1, 0, -7, 1, 1, -6)

    fun sisyphusFloor(o: BlockPos): Int = o.y + TartarusPlans.SISYPHUS_HEIGHT + 1

    fun sisyphus(o: BlockPos): ArenaSpec {
        val y = sisyphusFloor(o)
        val a = SISYPHUS_ALTAR
        val cover = listOf(CoverSpot("altar", BlockBox(o.x + a.minX, y + a.minY, o.z + a.minZ, o.x + a.maxX, y + a.maxY, o.z + a.maxZ), setOf(SISYPHUS_BOULDER), breakable = false))
        return ArenaSpec(
            SISYPHUS_ID, o, TartarusPlans.SISYPHUS_SUMMIT, Disc.around(o, TartarusPlans.SISYPHUS_SUMMIT - 0.5), y, emptyList(), cover, emptyList(), emptyList(),
            BlockBox(o.x - 30, o.y - 2, o.z - 30, o.x + 30, y + 18, o.z + 30),
        )
    }

    // =============================================================================================================
    // 3.7 Ixion: Rad (Plattform)
    // =============================================================================================================

    const val IXION_INNER = 10.8
    const val IXION_OUTER = 17.4
    const val IXION_STEP_INNER = 17.6
    const val IXION_STEP_OUTER = 19.0
    val IXION_BUMPER_ANGLES = doubleArrayOf(30.0, 150.0, 270.0)
    const val IXION_BUMPER_RADIUS = 18.5
    const val IXION_BUMPER_HEIGHT = 2

    fun ixion(o: BlockPos): ArenaSpec {
        val y = o.y + 1
        val cover = IXION_BUMPER_ANGLES.map { a ->
            val p = ArenaGeo.polarVec(o, IXION_BUMPER_RADIUS, a, y.toDouble())
            CoverSpot("bumper_${a.toInt()}", ArenaGeo.box2(p.x, p.z, y, IXION_BUMPER_HEIGHT), setOf(IXION_SPOKES), breakable = false)
        }
        val step = Level(
            "rim_step", Disc.around(o, IXION_STEP_OUTER, IXION_STEP_INNER), y + 1,
            doubleArrayOf(90.0, 210.0, 330.0).map { a -> ArenaGeo.polar(o, 17.0, a, y) },
        )
        return ArenaSpec(
            IXION_ID, o, TartarusPlans.IXION_PLATFORM, Disc.around(o, IXION_OUTER, IXION_INNER), y, listOf(step), cover, emptyList(), emptyList(),
            BlockBox(o.x - 22, o.y - 2, o.z - 22, o.x + 22, o.y + 36, o.z + 22),
        )
    }

    // =============================================================================================================
    // 3.7 Tantalos: Becken
    // =============================================================================================================

    /** Absatz (Kern, Füße o.y − 1): r 10 … 13. Beckenrand (o.y): r 13 … 15,8. Rand (o.y + 1): r 15,8 … 19,2. */
    const val TANTALUS_LEDGE_INNER = 10.0
    const val TANTALUS_LEDGE_OUTER = 13.0
    const val TANTALUS_RIM_OUTER = 15.8
    const val TANTALUS_EDGE_OUTER = 19.2
    /** Schleusenräder am Rand, r 16,5. PLAN: 45°, 135°, 225°, 315° – 45° und 315° lägen in den Felssäulen, daher 65° und 295°. */
    const val TANTALUS_SLUICE_RADIUS = 16.5
    val TANTALUS_SLUICE_ANGLES = doubleArrayOf(65.0, 135.0, 225.0, 295.0)
    /** Deckung gegen den Dreizack: Stamm des Obstbaums und die zwei Felssäulen (aus dem Bestandsplan). */
    val TANTALUS_TREE = BlockBox(-18, 1, -5, -14, 8, -3)
    val TANTALUS_COLUMNS = listOf(BlockBox(11, 1, -13, 13, 17, -11), BlockBox(11, 1, 11, 13, 17, 13))

    fun tantalus(o: BlockPos): ArenaSpec {
        val y = o.y - 1
        fun rel(b: BlockBox) = BlockBox(o.x + b.minX, o.y + b.minY, o.z + b.minZ, o.x + b.maxX, o.y + b.maxY, o.z + b.maxZ)
        val trident = setOf(TANTALUS_TRIDENT)
        val cover = listOf(CoverSpot("tree", rel(TANTALUS_TREE), trident, breakable = false)) +
            TANTALUS_COLUMNS.mapIndexed { i, b -> CoverSpot(if (i == 0) "column_n" else "column_s", rel(b), trident, breakable = false) }
        val devices = TANTALUS_SLUICE_ANGLES.mapIndexed { i, a -> dev("sluice_$i", ArenaGeo.polar(o, TANTALUS_SLUICE_RADIUS, a, o.y + 1), { HhaDecoBlocks.SLUICE_WHEEL }, o) }
        val levels = listOf(
            Level("rim", Disc.around(o, TANTALUS_RIM_OUTER, TANTALUS_LEDGE_OUTER), o.y, doubleArrayOf(0.0, 180.0).map { a -> ArenaGeo.polar(o, TANTALUS_LEDGE_OUTER, a, o.y - 1) }),
            Level("edge", Disc.around(o, TANTALUS_EDGE_OUTER, TANTALUS_RIM_OUTER), o.y + 1, doubleArrayOf(0.0, 180.0).map { a -> ArenaGeo.polar(o, TANTALUS_RIM_OUTER - 0.3, a, o.y) }),
        )
        // Teil `water`: oberste Wasserlage im Innenbecken (eine geschlossene Schleuse senkt das Wasser um eine Lage).
        val parts = listOf(Part("water", Disc.around(o, TANTALUS_LEDGE_INNER), o.y - 2, o.y - 2))
        return ArenaSpec(
            TANTALUS_ID, o, TartarusPlans.TANTALUS_BASIN, Disc.around(o, TANTALUS_LEDGE_OUTER, TANTALUS_LEDGE_INNER), y, levels, cover, parts, devices,
            BlockBox(o.x - 22, o.y - 5, o.z - 22, o.x + 22, o.y + 20, o.z + 22),
        )
    }

    /** Spec eines Bauwerks: Palast → Saal, Feste, Kerker, Strafstätte nach [variant] (0 Sisyphos, 1 Ixion, 2 Tantalos). */
    fun spec(kind: TartarusKind, variant: Int, o: BlockPos): ArenaSpec = when (kind) {
        TartarusKind.PALACE -> hall(o)
        TartarusKind.FORTRESS -> fortress(o)
        TartarusKind.PRISON -> prison(o)
        TartarusKind.PUNISHMENT -> when (variant) {
            0 -> sisyphus(o)
            1 -> ixion(o)
            else -> tantalus(o)
        }
    }

    // =============================================================================================================
    // 5.2 Palast: Anmarsch in fünf Abschnitten; 5.3 Wächter: Zuweg
    // =============================================================================================================

    /** Abschnittsgrenzen des Palast-Anmarschs (dz zur Palastmitte, von außen nach innen). */
    val PALACE_SECTIONS = listOf(
        "avenue" to (101..118), "propylon" to (88..100), "shadows" to (78..87), "judges" to (65..77), "styx" to (55..64), "hound_gate" to (47..54),
    )
    /** Halbe Breite der Klangzone: die Halle des Hundetors ist innen 19 breit (|dx| ≤ 9). (PZ1, Wunsch P10 2.) */
    const val PALACE_APPROACH_HALF_WIDTH = 9.5
    const val PALACE_WAYPOINT_REACH = 5.0

    /** Eingang des Palasts: vor dem Siegel (Füße), bis das Siegel offen ist Ziel von Pfeil und Hermes-Geist. */
    fun palaceEntrance(o: BlockPos): BlockPos = BlockPos(o.x, o.y + 1, o.z + TartarusPlans.SEAL_Z + 2)

    fun palaceApproach(o: BlockPos): ApproachSpec {
        val points = ArrayList<Waypoint>()
        for ((id, range) in PALACE_SECTIONS) points.add(Waypoint(id, Vec3d(o.x + 0.5, o.y + 1.0, o.z + range.last + 0.5), PALACE_WAYPOINT_REACH))
        val gate = palaceEntrance(o)
        points.add(Waypoint("entrance", Vec3d(gate.x + 0.5, gate.y.toDouble(), gate.z + 0.5), PALACE_WAYPOINT_REACH))
        return ApproachSpec("palace", "hades", HadesGate.TARTAROS, points, PALACE_APPROACH_HALF_WIDTH) { HhaSounds.APPROACH_HEART }
    }

    const val SITE_ROAD_HALF_WIDTH = 2.5
    const val SITE_WAYPOINT_REACH = 4.0

    /**
     * Zuweg eines Wächter-Bauwerks: vom Rand des Bauwerkskastens geradeaus nach Süden (+z) bis zum Eingang; beim Sisyphos
     * zum Fuß der Bahn. Punkte: Anfang, Wegzeichen (Mitte), Eingang.
     */
    fun siteApproach(kind: TartarusKind, variant: Int, o: BlockPos): ApproachSpec {
        val (key, entry) = when (kind) {
            TartarusKind.FORTRESS -> "warden_of_chains" to Vec3d(o.x + 0.5, o.y + 1.0, o.z + 41.5)
            TartarusKind.PRISON -> "kampe" to Vec3d(o.x + 0.5, o.y + 1.0, o.z + TartarusPlans.PRISON_PIT_RADIUS + 4.5)
            TartarusKind.PUNISHMENT -> when (variant) {
                0 -> "sisyphus" to TartarusPlans.sisyphusTrack(o, 0.0)
                1 -> "ixion" to Vec3d(o.x + 0.5, o.y + 1.0, o.z + TartarusPlans.IXION_PLATFORM + 1.5)
                else -> "tantalus" to Vec3d(o.x + 0.5, o.y + 1.0, o.z + 21.5)
            }
            TartarusKind.PALACE -> return palaceApproach(o)
        }
        val dx = entry.x - (o.x + 0.5)
        val dz = entry.z - (o.z + 0.5)
        val len = hypot(dx, dz).coerceAtLeast(1.0)
        val reach = kind.reach - 2.0
        val start = Vec3d(o.x + 0.5 + dx / len * reach, entry.y, o.z + 0.5 + dz / len * reach)
        val mid = Vec3d((start.x + entry.x) / 2.0, entry.y, (start.z + entry.z) / 2.0)
        val points = listOf(Waypoint("start", start, SITE_WAYPOINT_REACH), Waypoint("sign", mid, SITE_WAYPOINT_REACH), Waypoint("entrance", entry, SITE_WAYPOINT_REACH))
        val sound = if (kind == TartarusKind.PUNISHMENT) ({ HhaSounds.APPROACH_DREAD }) else ({ HhaSounds.APPROACH_CHAINS })
        return ApproachSpec("site_$key", key, HadesGate.TARTAROS, points, SITE_ROAD_HALF_WIDTH, sound)
    }
}
