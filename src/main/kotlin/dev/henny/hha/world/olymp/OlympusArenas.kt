package dev.henny.hha.world.olymp

import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.HhaSounds
import dev.henny.hha.logic.boss.HermesArena
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
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import kotlin.math.hypot

/**
 * Geometrie-Vertrag der Olymp-Arenen und -Wege (Überarbeitung 4, PLAN 3.1–3.5, 5.1, 7.2; P00, eingefroren). Zahlen aus
 * dem PLAN; Abweichungen stehen in `tmp/overhaul4/API.md`. Höhen „+n“ = Blöcke über den Füßen der Kernfläche.
 */
object OlympusArenas {

    // --- Angriffs-IDs der Deckung (PLAN 7.2) -----------------------------------------------------------------------
    const val HERMES_FEATHERS = "hermes.feathers"
    const val HEKATE_GAZE = "hekate.gaze"
    const val HEKATE_TRIFORM_LINE = "hekate.triform_line"
    const val BRONTES_HAMMER = "brontes.hammer"
    const val BRONTES_SWEEP = "brontes.sweep"
    const val BRONTES_BELLOWS = "brontes.bellows"
    const val KRATOS_BIA_STORM = "kratos.bia_storm"
    const val ZEUS_GAZE = "zeus.gaze"
    const val ZEUS_KERAUNOS = "zeus.keraunos"

    /** Wie weit der Kasten einer Arena über ihren Radius hinaus und über/unter den Kern reicht (wie `Arenas.protects`). */
    const val BOX_MARGIN = 4
    const val BOX_BELOW = 8
    const val BOX_ABOVE = 40

    private fun box(center: BlockPos, radius: Int, coreY: Int, extra: BlockBox? = null): BlockBox {
        val r = radius + BOX_MARGIN
        val b = BlockBox(center.x - r, coreY - BOX_BELOW, center.z - r, center.x + r, coreY + BOX_ABOVE, center.z + r)
        return if (extra == null) b else BlockBox.encompass(listOf(b, extra)).get()
    }

    // =============================================================================================================
    // 3.1 Hermes: Himmelsforum (temporär; Mitte = Bodenblock der Scheibe, Füße eine Reihe höher)
    // =============================================================================================================

    const val FORUM_CORE = 22.0
    const val FORUM_COLUMNS = 8
    const val FORUM_COLUMN_RING = 20.0
    const val FORUM_COLUMN_HEIGHT = 7
    const val FORUM_COLUMN_FIRST = 22.5
    const val FORUM_BELL_RING = 18.0
    const val FORUM_BELL_HEIGHT = 1
    const val FORUM_GALLERY_INNER = 22.0
    const val FORUM_GALLERY_OUTER = 26.0
    const val FORUM_GALLERY_HEIGHT = 2
    val FORUM_STAIR_ANGLES = doubleArrayOf(0.0, 90.0, 180.0, 270.0)
    const val FORUM_STAIR_WIDTH = 5
    /** Die vier Emporenbögen beginnen bei diesen Winkeln und reichen je 90°; Absturz III: 0° und 180°, IV: 90° und 270°. */
    val FORUM_ARCS = doubleArrayOf(0.0, 90.0, 180.0, 270.0)
    val FORUM_ARCS_STAGE3 = listOf("arc_0", "arc_180")
    val FORUM_ARCS_STAGE4 = listOf("arc_90", "arc_270")

    fun forumColumnAngle(k: Int): Double = FORUM_COLUMN_FIRST + k * 45.0

    /** Mitte der Botensäule [k] (Block, Höhe = Füße der Fläche). */
    fun forumColumn(center: BlockPos, k: Int): BlockPos = ArenaGeo.polar(center, FORUM_COLUMN_RING, forumColumnAngle(k), center.y + 1)

    fun FORUM(center: BlockPos): ArenaSpec {
        val coreY = center.y + 1
        val cover = (0 until FORUM_COLUMNS).map { k ->
            val p = forumColumn(center, k)
            CoverSpot("column_$k", ArenaGeo.box3(p.x, p.z, coreY, FORUM_COLUMN_HEIGHT), setOf(HERMES_FEATHERS), breakable = true)
        }
        val devices = (0 until FORUM_COLUMNS).map { k ->
            DeviceSpot("bell_$k", ArenaGeo.polar(center, FORUM_BELL_RING, forumColumnAngle(k), coreY + FORUM_BELL_HEIGHT), { HhaDecoBlocks.MESSENGER_BELL }, facingToward(forumColumnAngle(k)))
        }
        val stairs = FORUM_STAIR_ANGLES.associate { a -> a to ArenaGeo.radialStairs(center, a, FORUM_CORE - 2.0, FORUM_GALLERY_HEIGHT, FORUM_STAIR_WIDTH, coreY) }
        val levels = FORUM_ARCS.map { a ->
            val access = stairs.filterKeys { s -> s == a || s == (a + 90.0) % 360.0 }.values.flatten()
            Level("gallery_${a.toInt()}", ArenaGeo.ring(center, FORUM_GALLERY_INNER, FORUM_GALLERY_OUTER, a, a + 90.0), coreY + FORUM_GALLERY_HEIGHT, access)
        }
        val parts = FORUM_ARCS.map { a ->
            // P24 (Wunsch P14 2): bis zur Brüstung auf der Lauffläche (vorher coreY + FORUM_GALLERY_HEIGHT − 1).
            Part("arc_${a.toInt()}", ArenaGeo.ring(center, FORUM_GALLERY_INNER, FORUM_GALLERY_OUTER, a, a + 90.0), center.y - 2, coreY + FORUM_GALLERY_HEIGHT)
        }
        return ArenaSpec(
            "forum", center, HermesArena.RADIUS, Disc.around(center, FORUM_CORE - 0.5), coreY, levels, cover, parts, devices,
            BlockBox.encompass(listOf(ForumPlan.box(center), BlockBox(center.x - 28, center.y - 3, center.z - 28, center.x + 28, coreY + FORUM_COLUMN_HEIGHT + 2, center.z + 28))).get(),
        )
    }

    // =============================================================================================================
    // 3.2 Hekate: Dreiweg C
    // =============================================================================================================

    const val C_CORE = 19.0
    const val C_WAY_HALF = OlympusLayout.C_PATH_WIDTH / 2.0
    const val C_GATE_RADIUS = 15.0
    /** Pfeiler der Schwellentore: 2 × 2, Mitte quer zum Weg bei ±[C_GATE_PILLAR_OFFSET], lichte Weite 7 (−3 … 3). */
    const val C_GATE_PILLAR_OFFSET = 4.5
    const val C_GATE_HEIGHT = 5
    const val C_GATE_LINTEL = 5
    val C_TOWER_ANGLES = doubleArrayOf(30.0, 150.0, 270.0)
    const val C_TOWER_RADIUS = 12.0
    const val C_TOWER_HEIGHT = 6
    const val C_TOWER_STUMP = 2
    val C_TERRACE_ANGLES = doubleArrayOf(30.0, 150.0, 270.0)
    const val C_TERRACE_HALF_ANGLE = 40.0
    const val C_TERRACE_INNER = 20.0
    const val C_TERRACE_OUTER = 26.0
    const val C_TERRACE_HEIGHT = 3
    const val C_TERRACE_STAIR_FROM = 17.0
    const val C_TERRACE_STAIR_WIDTH = 5

    /** Mitte (Weltpunkt) eines Tor-Pfeilers am Weg mit Winkel [way]; [side] −1 links, +1 rechts. */
    fun cGatePillar(way: Double, side: Int): Vec3d {
        val c = OlympusLayout.C.center
        val d = OlympusLayout.dir(way)
        val r = C_GATE_RADIUS + 0.5
        return Vec3d(c.x + 0.5 + d.x * r - d.z * side * C_GATE_PILLAR_OFFSET, c.y.toDouble(), c.z + 0.5 + d.z * r + d.x * side * C_GATE_PILLAR_OFFSET)
    }

    fun cTower(i: Int): BlockPos = ArenaGeo.polar(OlympusLayout.C.center, C_TOWER_RADIUS, C_TOWER_ANGLES[i])

    /** Fallgitter-Ebene des Tors am Weg [way]: eine Blockreihe bei r 15, quer zum Weg −3,5 … 3,5. */
    fun cPortcullis(way: Double): Strip {
        val c = OlympusLayout.C.center
        val d = OlympusLayout.dir(way)
        val mx = c.x + 0.5 + d.x * C_GATE_RADIUS
        val mz = c.z + 0.5 + d.z * C_GATE_RADIUS
        val h = 3.5
        return Strip(mx + d.z * h, mz - d.x * h, mx - d.z * h, mz + d.x * h, 0.5)
    }

    val C: ArenaSpec by lazy {
        val isle = OlympusLayout.C
        val c = isle.center
        val y = isle.floorY
        val ways = OlympusLayout.C_PATH_ANGLES.map { a ->
            val d = OlympusLayout.dir(a)
            Strip(c.x + 0.5 + d.x * 1.5, c.z + 0.5 + d.z * 1.5, c.x + 0.5 + d.x * isle.arenaRadius, c.z + 0.5 + d.z * isle.arenaRadius, C_WAY_HALF)
        }
        val cover = ArrayList<CoverSpot>()
        val lines = setOf(HEKATE_GAZE, HEKATE_TRIFORM_LINE)
        cover.add(CoverSpot("hekataion", ArenaGeo.box3(c.x, c.z, y, OlympusLayout.HEKATAION_HEIGHT), lines, breakable = false))
        for (a in OlympusLayout.C_PATH_ANGLES) for (side in intArrayOf(-1, 1)) {
            val p = cGatePillar(a, side)
            cover.add(CoverSpot("gate_${a.toInt()}_${if (side < 0) "l" else "r"}", ArenaGeo.box2(p.x, p.z, y, C_GATE_HEIGHT), lines, breakable = false))
        }
        for (i in C_TOWER_ANGLES.indices) {
            val t = cTower(i)
            cover.add(CoverSpot("tower_${C_TOWER_ANGLES[i].toInt()}", ArenaGeo.box3(t.x, t.z, y, C_TOWER_HEIGHT), lines, breakable = true))
        }
        val levels = C_TERRACE_ANGLES.map { a ->
            Level(
                "terrace_${a.toInt()}", ArenaGeo.ring(c, C_TERRACE_INNER, C_TERRACE_OUTER, a - C_TERRACE_HALF_ANGLE, a + C_TERRACE_HALF_ANGLE),
                y + C_TERRACE_HEIGHT, ArenaGeo.radialStairs(c, a, C_TERRACE_STAIR_FROM, C_TERRACE_HEIGHT, C_TERRACE_STAIR_WIDTH, y),
            )
        }
        val parts = OlympusLayout.C_PATH_ANGLES.map { a -> Part("portcullis_${a.toInt()}", cPortcullis(a), y, y + C_GATE_HEIGHT - 1, fills = true) }
        ArenaSpec("olymp_c", c, isle.arenaRadius, Union(listOf(Disc.around(c, C_CORE)) + ways), y, levels, cover, parts, emptyList(), box(c, isle.arenaRadius, y))
    }

    // =============================================================================================================
    // 3.3 Brontes: Schmiede D
    // =============================================================================================================

    val D_MOLD_ANGLES = doubleArrayOf(60.0, 180.0, 300.0)
    const val D_MOLD_RADIUS = 15.0
    const val D_MOLD_HEIGHT = 3
    /** Essebühnen: Sektoren r 18–24 auf +2. */
    val D_STAGES = listOf(232.0 to 256.0, 284.0 to 308.0)
    const val D_STAGE_INNER = 18.0
    const val D_STAGE_OUTER = 24.0
    const val D_STAGE_HEIGHT = 2
    const val D_STAGE_STAIR_WIDTH = 3

    fun dMold(i: Int): Vec3d = ArenaGeo.polarVec(OlympusLayout.D.center, D_MOLD_RADIUS, D_MOLD_ANGLES[i], OlympusLayout.D.floorY.toDouble())

    val D: ArenaSpec by lazy {
        val isle = OlympusLayout.D
        val c = isle.center
        val y = isle.floorY
        val anvil = OlympusLayout.ANVIL
        val cover = ArrayList<CoverSpot>()
        cover.add(CoverSpot("anvil", ArenaGeo.box3(anvil.x, anvil.z, y, OlympusLayout.ANVIL_HEIGHT), setOf(BRONTES_HAMMER, BRONTES_SWEEP), breakable = true))
        for (i in D_MOLD_ANGLES.indices) {
            val m = dMold(i)
            cover.add(CoverSpot("mold_${D_MOLD_ANGLES[i].toInt()}", ArenaGeo.box2(m.x, m.z, y, D_MOLD_HEIGHT), setOf(BRONTES_BELLOWS, BRONTES_SWEEP), breakable = false))
        }
        val levels = D_STAGES.mapIndexed { i, (from, to) ->
            // Treppe am äußeren Ende: bei 232° (westliche Bühne) bzw. 308° (östliche), tangential zur Bühne hin steigend.
            val end = if (i == 0) from else to
            val toward = if (i == 0) end + 90.0 else end - 90.0
            val start = ArenaGeo.polarVec(c, (D_STAGE_INNER + D_STAGE_OUTER) / 2.0, end + (if (i == 0) -6.0 else 6.0), y.toDouble())
            Level(
                "forge_stage_$i", ArenaGeo.ring(c, D_STAGE_INNER, D_STAGE_OUTER, from, to), y + D_STAGE_HEIGHT,
                ArenaGeo.stairCells(start.x, start.z, toward, D_STAGE_HEIGHT, D_STAGE_STAIR_WIDTH, y),
            )
        }
        ArenaSpec("olymp_d", c, isle.arenaRadius, Disc.around(c, isle.arenaRadius.toDouble()), y, levels, cover, emptyList(), emptyList(), box(c, isle.arenaRadius, y))
    }

    // =============================================================================================================
    // 3.4 Kratos und Bia: Stufen der Macht E
    // =============================================================================================================

    /** Standarten (r, Winkel): Pfeiler 2 × 2, 4 hoch. */
    val E_STANDARDS = listOf(14.0 to 20.0, 14.0 to 160.0, 17.0 to 60.0, 17.0 to 120.0)
    const val E_STANDARD_HEIGHT = 4
    /** Treppenabsatz auf +4 (y 196), Kasten x 138 … 162, z −592 … −588. */
    const val E_LANDING_HEIGHT = 4
    val E_LANDING = Rect(138, -592, 162, -588)
    /** Kasten, um den die Arena nach Norden erweitert wird (Freitreppe und Absatz). */
    val E_EXTRA = Rect(138, -592, 162, -584)

    fun eStandard(i: Int): Vec3d = ArenaGeo.polarVec(OlympusLayout.E.center, E_STANDARDS[i].first, E_STANDARDS[i].second, OlympusLayout.E.floorY.toDouble())

    val E: ArenaSpec by lazy {
        val isle = OlympusLayout.E
        val c = isle.center
        val y = isle.floorY
        val cover = ArrayList<CoverSpot>()
        cover.add(CoverSpot("plinth_left", ArenaGeo.box3(OlympusLayout.PLINTH_LEFT.x, OlympusLayout.PLINTH_LEFT.z, y, OlympusLayout.PLINTH_HEIGHT), emptySet(), breakable = true))
        cover.add(CoverSpot("plinth_right", ArenaGeo.box3(OlympusLayout.PLINTH_RIGHT.x, OlympusLayout.PLINTH_RIGHT.z, y, OlympusLayout.PLINTH_HEIGHT), emptySet(), breakable = false))
        for (i in E_STANDARDS.indices) {
            val s = eStandard(i)
            cover.add(CoverSpot("standard_${E_STANDARDS[i].second.toInt()}", ArenaGeo.box2(s.x, s.z, y, E_STANDARD_HEIGHT), setOf(KRATOS_BIA_STORM), breakable = true))
        }
        // Freitreppe: drei Stufen von z −585 bis −587 (y 192 … 194), dann der Absatz ab z −588.
        val access = (0 until 3).flatMap { k -> (c.x - 2..c.x + 2).map { x -> BlockPos(x, y + k, OlympusLayout.E_STAIRS_FOOT_Z - 1 - k) } }
        val levels = listOf(Level("landing", E_LANDING, y + E_LANDING_HEIGHT, access))
        ArenaSpec(
            "olymp_e", c, isle.arenaRadius, Disc.around(c, isle.arenaRadius.toDouble()), y, levels, cover, emptyList(), emptyList(),
            box(c, isle.arenaRadius, y, BlockBox(E_EXTRA.minX, y - BOX_BELOW, E_EXTRA.minZ, E_EXTRA.maxX, y + BOX_ABOVE, E_EXTRA.maxZ)),
            extra = listOf(E_EXTRA),
        )
    }

    // =============================================================================================================
    // 3.5 Zeus: Thron F
    // =============================================================================================================

    const val F_CORE = 19.5
    const val F_TERRACE_INNER = 20.0
    const val F_TERRACE_OUTER = 30.0
    const val F_TERRACE_HEIGHT = 2
    /** Die sechs Schollen (Winkel von, bis). Südaufgang 82°–98° und Thronaufgang 262°–278° gehören keiner. */
    val F_FLOES = listOf(-30.0 to 30.0, 30.0 to 82.0, 98.0 to 150.0, 150.0 to 210.0, 210.0 to 262.0, 278.0 to 330.0)
    /** Zu Beginn von Phase III brechen diese Schollen, bei 50 % der dritten Leiste die zweiten; 2 und 5 bleiben. */
    val F_FLOES_BREAK_FIRST = listOf(0, 3)
    val F_FLOES_BREAK_SECOND = listOf(1, 4)
    const val F_FLOE_STAIR_OFFSET = 9.0
    const val F_FLOE_STAIR_WIDTH = 3
    /** Teil einer Scholle: bis so tief unter und so hoch über den Kern (Pfeiler inklusive). */
    const val F_FLOE_BELOW = 6
    const val F_FLOE_ABOVE = 11
    /** Blitzpfeiler: 3 × 3, Sockel 2 hoch, Kern 5 hoch (Kupfer), Ableiter 1 – zusammen 8 ab der Terrasse. */
    const val F_PILLAR_SOCKET = 2
    const val F_PILLAR_CORE = 5
    const val F_PILLAR_TOTAL = 8
    /** Erdungsgriff in der Terrassenwand: Ring 20, y 233. */
    const val F_ROD_RING = 20.0
    /** Thronaufgang: drei Stufen r 20 … 22, Bühne +3 ab r 23 bis r 36, 21 breit (x −10 … 10). */
    const val F_STAGE_HEIGHT = 3
    const val F_STAGE_FROM = 23
    const val F_STAGE_TO = 36
    const val F_STAGE_HALF_WIDTH = 10
    const val F_THRONE_STAIR_WIDTH = 5

    fun fPillar(i: Int): BlockPos = OlympusLayout.pillar(i)

    fun fRod(i: Int): BlockPos = ArenaGeo.polar(OlympusLayout.F.center, F_ROD_RING, OlympusLayout.PILLAR_ANGLES[i], OlympusLayout.F.floorY + 1)

    /** Thronbühne als Rechteck (Nordseite). */
    val F_STAGE: Rect get() {
        val c = OlympusLayout.F.center
        return Rect(c.x - F_STAGE_HALF_WIDTH, c.z - F_STAGE_TO, c.x + F_STAGE_HALF_WIDTH, c.z - F_STAGE_FROM)
    }

    val F: ArenaSpec by lazy {
        val isle = OlympusLayout.F
        val c = isle.center
        val y = isle.floorY
        val ty = y + F_TERRACE_HEIGHT
        val cover = (0 until OlympusLayout.PILLAR_COUNT).map { i ->
            val p = fPillar(i)
            CoverSpot("pillar_$i", ArenaGeo.box3(p.x, p.z, ty, F_PILLAR_TOTAL), setOf(ZEUS_GAZE, ZEUS_KERAUNOS), breakable = true)
        }
        val devices = (0 until OlympusLayout.PILLAR_COUNT).map { i ->
            DeviceSpot("rod_$i", fRod(i), { HhaDecoBlocks.GROUNDING_ROD }, facingToward(OlympusLayout.PILLAR_ANGLES[i]))
        }
        val levels = ArrayList<Level>()
        val parts = ArrayList<Part>()
        for ((i, floe) in F_FLOES.withIndex()) {
            val (from, to) = floe
            // Die Thronbühne liegt nie auf einer Scholle (bricht eine Scholle, bleibt die Bühne stehen).
            val zone = Cut(ArenaGeo.ring(c, F_TERRACE_INNER, F_TERRACE_OUTER, from, to), listOf(F_STAGE))
            val pillar = OlympusLayout.PILLAR_ANGLES[i]
            val access = listOf(-F_FLOE_STAIR_OFFSET, F_FLOE_STAIR_OFFSET).flatMap { off ->
                ArenaGeo.radialStairs(c, pillar + off, F_TERRACE_INNER - F_TERRACE_HEIGHT, F_TERRACE_HEIGHT, F_FLOE_STAIR_WIDTH, y)
            }
            levels.add(Level("floe_$i", zone, ty, access))
            parts.add(Part("floe_$i", zone, y - F_FLOE_BELOW, y + F_FLOE_ABOVE))
        }
        val stairs = (0 until F_STAGE_HEIGHT).flatMap { k -> (c.x - F_THRONE_STAIR_WIDTH / 2..c.x + F_THRONE_STAIR_WIDTH / 2).map { x -> BlockPos(x, y + k, c.z - 20 - k) } }
        levels.add(Level("throne_stage", F_STAGE, y + F_STAGE_HEIGHT, stairs))
        val stage = F_STAGE
        ArenaSpec(
            "olymp_f", c, isle.arenaRadius, Disc.around(c, F_CORE), y, levels, cover, parts, devices,
            box(c, isle.arenaRadius, y, BlockBox(stage.minX, y - BOX_BELOW, stage.minZ, stage.maxX, y + BOX_ABOVE, stage.maxZ)),
            extra = listOf(stage),
        )
    }

    /** Spec zum Boss-Schlüssel (`hekate`, `brontes`, `kratos`, `zeus`); Hermes über [FORUM] mit der Forumsmitte. */
    fun spec(bossKey: String): ArenaSpec? = when (bossKey) {
        "hekate" -> C
        "brontes" -> D
        "kratos" -> E
        "zeus" -> F
        else -> null
    }

    // =============================================================================================================
    // 5.1 Wege: die fünf Brücken als Schlachtwege
    // =============================================================================================================

    /** Halbe Breite der Weg-Zone (Brücke 5 breit plus Rand). */
    const val APPROACH_HALF_WIDTH = 4.0
    const val WAYPOINT_REACH = 4.0

    /**
     * Die fünf Wege, je Anfang (Brückenkopf der Ausgangsinsel), Engstelle (Siegellücke bzw. Mitte), Kopftor (Brückenkopf
     * der Zielinsel) und Inseleingang (`Isle.entrance`). Berechnet aus den Zellen der Brücken (Verlauf bleibt nach
     * PLAN 5.1); die Werte stehen in `API.md`.
     */
    val APPROACHES: List<ApproachSpec> by lazy {
        val sounds = mapOf(
            "ab" to { HhaSounds.APPROACH_WIND }, "bc" to { HhaSounds.APPROACH_HOWL }, "cd" to { HhaSounds.APPROACH_ANVIL },
            "de" to { HhaSounds.APPROACH_DRUMS }, "ef" to { HhaSounds.APPROACH_THUNDER },
        )
        OlympusBridges.ALL.map { b ->
            fun feet(s: Int) = Vec3d(b.cells[s].x + 0.5, b.level[s] + 1.0, b.cells[s].z + 0.5)
            fun outside(s: Int, isle: OlympusLayout.Isle) = hypot((b.cells[s].x - isle.center.x).toDouble(), (b.cells[s].z - isle.center.z).toDouble()) > isle.radius - 2
            val start = (0 until b.length).first { outside(it, b.from) }
            val head = (0 until b.length).last { outside(it, b.to) }
            val narrow = b.gap?.let { it.first + it.count() / 2 } ?: ((start + head) / 2)
            ApproachSpec(
                "bridge_${b.id}", b.to.key ?: "", OlympusLayout.WORLD,
                listOf(
                    Waypoint("start", feet(start), WAYPOINT_REACH),
                    Waypoint("narrow", feet(narrow), WAYPOINT_REACH),
                    Waypoint("head_gate", feet(head), WAYPOINT_REACH),
                    Waypoint("entrance", b.to.entrance, WAYPOINT_REACH),
                ),
                APPROACH_HALF_WIDTH, sounds.getValue(b.id),
            )
        }
    }

    /** Vorderseite eines Geräts auf dem Ring unter Winkel [deg]: zur Mitte hin. */
    fun facingToward(deg: Double): Direction {
        val inward = (deg + 180.0) % 360.0
        return when (((inward + 45.0) % 360.0 / 90.0).toInt()) {
            0 -> Direction.EAST
            1 -> Direction.SOUTH
            2 -> Direction.WEST
            else -> Direction.NORTH
        }
    }
}
