package dev.henny.hha.world.olymp

import dev.henny.hha.Hha
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Der feste Inselpfad des Olymp (Überarbeitung 2, PLAN 8.2/8.3). **Verbindlich für alle:** Der Bauplan (P04) baut genau
 * hierhin, die Kämpfe (P07 bis P11), Portale (P03) und Befehle (P06) lesen nur hier. Eingefroren nach Welle 0: Passt
 * eine Zahl nicht, Wunsch nach `tmp/overhaul2/requests/`, nicht abweichen.
 *
 * Vereinbarungen:
 * - Koordinaten in der Dimension [WORLD]. **`floorY` ist die Höhe der Füße** auf dem Boden (Oberkante des Bodens);
 *   der Bodenblock liegt bei `floorY − 1`.
 * - **Winkel** in Grad von oben gesehen: 0° = Osten (+x), 90° = Süden (+z), 180° = Westen, 270° = Norden; Richtung
 *   `(cos a, 0, sin a)` ([dir]).
 * - Alle Kampfflächen sind im Radius `arenaRadius` völlig eben (außer Hekataion, Amboss, Sockel, Pfeiler).
 */
object OlympusLayout {

    /** Die Olymp-Dimension. */
    val WORLD: RegistryKey<World> = RegistryKey.of(RegistryKeys.WORLD, Hha.id("olympus"))

    /** Eine Insel des Pfads. */
    class Isle(
        /** Kennbuchstabe A–F. */
        val letter: Char,
        /** Fortschritts- bzw. Boss-Schlüssel (`hermes` …), `null` bei der Ankunftsinsel. */
        val key: String?,
        /** Inselmitte (x, z) und Bodenhöhe (y = Füße). */
        val center: BlockPos,
        /** Radius des Inselkörpers. */
        val radius: Int,
        /** Radius der ebenen Kampffläche um [center] (0 = keine). */
        val arenaRadius: Int,
        /** Funktionsblock, der den Kampf startet (`boss_altar` bzw. bei B der Olymp-Altar des Tempels). */
        val altar: BlockPos?,
        /** Wohin ein K. o. führt (Inseleingang, außerhalb der Kampffläche, am Brückenkopf). */
        val entrance: Vec3d,
        /** Wo der Boss erscheint. */
        val bossSpawn: Vec3d?,
    ) {
        val floorY: Int get() = center.y

        /** Mitte als Vektor (y = Füße). */
        val centerVec: Vec3d get() = Vec3d(center.x + 0.5, center.y.toDouble(), center.z + 0.5)
    }

    // ---------------------------------------------------------------------------------------------
    // A Wolkentor der Horen (Ankunft)
    // ---------------------------------------------------------------------------------------------

    val A = Isle('A', null, BlockPos(0, 120, 0), 24, 0, null, Vec3d(0.5, 120.0, 4.5), null)

    /**
     * Ankunftstor der Insel A: untere Ecke der **Öffnung** (kleinstes x, unterste Reihe); die Öffnung ist
     * [ARRIVAL_GATE_WIDTH] × [ARRIVAL_GATE_HEIGHT] und spannt die x-Achse ([ARRIVAL_GATE_AXIS]) bei z = −8.
     * Der Glowstone-Rahmen liegt eine Reihe außen herum (untere Rahmenreihe im Boden bei y = 119).
     */
    val ARRIVAL_GATE = BlockPos(-2, 120, -8)
    const val ARRIVAL_GATE_WIDTH = 4
    const val ARRIVAL_GATE_HEIGHT = 5
    val ARRIVAL_GATE_AXIS: Direction.Axis = Direction.Axis.X

    /**
     * Wo man nach dem Wolkentor ankommt: 2,5 Blöcke vor dem Tor auf der **Nordseite**, Blick nach Norden (vom Tor weg,
     * den Weg zur Brücke nach B hinauf; B liegt bei z −130). P13 nach dem Vorschlag von P04 (vorher Südseite mit Blick
     * nach Süden, also mit der Brücke im Rücken). Minecraft-Gier: 0 = Süden, 180 = Norden.
     */
    val ARRIVAL_SPOT = Vec3d(0.0, 120.0, -10.5)
    const val ARRIVAL_YAW = 180f

    // ---------------------------------------------------------------------------------------------
    // B Tempel des Boten (Hermes)
    // ---------------------------------------------------------------------------------------------

    /**
     * Insel B. Der Tempel (`OlympusTemplePiece`, Radius 24) steht mit seiner Mitte auf [Isle.center], Plateau-Block bei
     * `floorY − 1` (`center.y` des Tempelbaus = 127). Altar = Olymp-Altar im Tempel (Plateau + 4); Stufen nach Süden.
     */
    val B = Isle('B', "hermes", BlockPos(0, 128, -130), 34, 0, BlockPos(0, 131, -130), Vec3d(0.5, 128.0, -100.5), null)

    // ---------------------------------------------------------------------------------------------
    // C Dreiweg der Hekate
    // ---------------------------------------------------------------------------------------------

    /** Insel C; Hekataion (3 × 3) in der Mitte, Altar `boss_altar[boss=0]` davor (Süden). */
    val C = Isle('C', "hekate", BlockPos(0, 140, -280), 40, 26, BlockPos(0, 140, -276), Vec3d(0.5, 140.0, -246.5), Vec3d(0.5, 140.0, -274.5))

    /** Die drei Wege aus der Mitte (6 breit, bis zum Rand der Kampffläche): Süd (von B), Nordwest (Höllentor), Nordost (nach D). */
    val C_PATH_ANGLES = doubleArrayOf(90.0, 210.0, 330.0)
    const val C_PATH_WIDTH = 6
    const val C_PATH_SOUTH = 0
    const val C_PATH_NORTHWEST = 1
    const val C_PATH_NORTHEAST = 2

    /** Hekataion: Statue 3 × 3 (Mitte = [C].center), so hoch. Überarbeitung 4 (PLAN 3.2): 5 → 7, Deckung gegen den Blick. */
    const val HEKATAION_HALF = 1
    const val HEKATAION_HEIGHT = 7

    /**
     * Höllentor am Ende des Nordwest-Wegs, auf der 6 Blöcke tieferen Terrasse (Füße y = 134). Fußpunkt der Tormitte
     * wie bei `HadesGate.openRow`: Öffnungsblöcke bei `HELL_GATE + (0, h, d)` mit `HadesGate.inOpening(d, h)`,
     * d entlang z ([HELL_GATE_AXIS], die Ebene spannt z).
     */
    val HELL_GATE = BlockPos(-36, 134, -301)
    val HELL_GATE_AXIS: Direction.Axis = Direction.Axis.Z

    /** Höhe der Terrasse vor dem Höllentor (Füße). */
    const val HELL_TERRACE_Y = 134

    // ---------------------------------------------------------------------------------------------
    // D Schmiede der Kyklopen (Brontes)
    // ---------------------------------------------------------------------------------------------

    /** Insel D; Amboss in der Mitte (3 × 2 × 3), Altar `boss_altar[boss=1]` südlich am Amboss, Esse im Norden. */
    val D = Isle('D', "brontes", BlockPos(150, 160, -390), 38, 24, BlockPos(150, 160, -387), Vec3d(126.5, 160.0, -371.5), Vec3d(150.5, 160.0, -409.5))

    /** Amboss: Mitte [D].center, halbe Breite 1 (3 × 3), Höhe 2. Steht im Kampf und deckt Linien und Kegel. */
    val ANVIL: BlockPos get() = D.center
    const val ANVIL_HALF = 1
    const val ANVIL_HEIGHT = 2

    /** Esse im Norden (Mitte der Feueröffnung am Boden); dort steigt Brontes auf. */
    val FORGE = BlockPos(150, 160, -411)

    /** Sechs Bodenroste als Sektoren: Sektor i reicht von `i · 60°` bis `(i + 1) · 60°`, Radius 6 bis 22. */
    const val GRATE_SECTORS = 6
    const val GRATE_SECTOR_DEGREES = 60.0
    const val GRATE_INNER = 6.0
    const val GRATE_OUTER = 22.0

    /** Mittelwinkel des Sektors [i] (30°, 90°, …). */
    fun grateAngle(i: Int): Double = (i + 0.5) * GRATE_SECTOR_DEGREES

    /** Liegt (x, z) im Rost-Sektor [i]? */
    fun inGrate(i: Int, x: Double, z: Double): Boolean {
        val dx = x - (D.center.x + 0.5)
        val dz = z - (D.center.z + 0.5)
        val r = kotlin.math.hypot(dx, dz)
        if (r < GRATE_INNER || r > GRATE_OUTER) return false
        val a = (Math.toDegrees(kotlin.math.atan2(dz, dx)) + 360.0) % 360.0
        return (a / GRATE_SECTOR_DEGREES).toInt().coerceIn(0, GRATE_SECTORS - 1) == i
    }

    // ---------------------------------------------------------------------------------------------
    // E Stufen der Macht (Kratos und Bia)
    // ---------------------------------------------------------------------------------------------

    /** Insel E; Freitreppe nach Norden, Siegel `boss_altar[boss=2]` am Treppenfuß. */
    val E = Isle('E', "kratos", BlockPos(150, 192, -560), 36, 24, BlockPos(150, 192, -581), Vec3d(150.5, 192.0, -529.5), Vec3d(150.5, 192.0, -577.5))

    /** Sockel relativ zur Mitte (± 14, 0, −10); je 3 × 3, 2 hoch. Links (Westen, vom Platz zur Treppe gesehen) steht Bia. */
    val PLINTH_LEFT = BlockPos(136, 192, -570)
    val PLINTH_RIGHT = BlockPos(164, 192, -570)
    const val PLINTH_HALF = 1
    const val PLINTH_HEIGHT = 2

    /** Wo Bia auf ihrem Sockel steht (Füße). */
    val BIA_SPOT: Vec3d get() = Vec3d(PLINTH_LEFT.x + 0.5, (PLINTH_LEFT.y + PLINTH_HEIGHT).toDouble(), PLINTH_LEFT.z + 0.5)

    /** Treppenfuß (Nordrand der Kampffläche) und Treppenkopf. */
    const val E_STAIRS_FOOT_Z = -584
    const val E_STAIRS_TOP_Z = -600

    // ---------------------------------------------------------------------------------------------
    // F Thron des Zeus
    // ---------------------------------------------------------------------------------------------

    /**
     * Insel F; Thron im Norden außerhalb der Fläche, Siegel `boss_altar[boss=3]` davor. Überarbeitung 4 (PLAN 3.5): Altar
     * und Erscheinungsort liegen auf der Thronbühne (+3, y 235); der Kern bleibt auf y 232, die Terrasse liegt auf +2.
     */
    val F = Isle('F', "zeus", BlockPos(0, 232, -680), 50, 30, BlockPos(0, 235, -707), Vec3d(31.5, 232.0, -654.5), Vec3d(0.5, 235.0, -703.5))

    /** Thron (Sitzfläche). Überarbeitung 4 (PLAN 3.5): auf der Thronbühne, Sitz bei y 236. */
    val THRONE = BlockPos(0, 236, -714)

    /** Sechs Blitzableiter-Pfeiler auf Ring 21, Winkel 0°, 60° … 300°; je 5 Blöcke gewachstes Kupfer, oben ein Blitzableiter. */
    const val PILLAR_RING = 21.0
    const val PILLAR_COUNT = 6
    const val PILLAR_HEIGHT = 5
    val PILLAR_ANGLES = doubleArrayOf(0.0, 60.0, 120.0, 180.0, 240.0, 300.0)

    /**
     * Fuß des Pfeilers [i]. Überarbeitung 4 (PLAN 3.5): Der Fuß steht auf der Terrasse (+2, y 234) statt auf dem Kernboden;
     * Sockel, Kern und Ableiter beschreibt `OlympusArenas` (Blitzpfeiler 3 × 3).
     */
    fun pillar(i: Int): BlockPos {
        val a = Math.toRadians(PILLAR_ANGLES[i])
        return BlockPos((F.center.x + PILLAR_RING * cos(a)).roundToInt(), F.center.y + 2, (F.center.z + PILLAR_RING * sin(a)).roundToInt())
    }

    val PILLARS: List<BlockPos> = (0 until PILLAR_COUNT).map(::pillar)

    /** Die Gewitterwolke über dem Hof beginnt so hoch über dem Boden. */
    const val STORM_CLOUD_HEIGHT = 24

    // ---------------------------------------------------------------------------------------------
    // Pfad und Brücken
    // ---------------------------------------------------------------------------------------------

    val ISLES: List<Isle> = listOf(A, B, C, D, E, F)

    /** Brücken in Reihenfolge (von, nach); 5 breit. */
    val BRIDGES: List<Pair<Isle, Isle>> = listOf(A to B, B to C, C to D, D to E, E to F)
    const val BRIDGE_WIDTH = 5

    /** Alle so viele Blöcke ein kleiner Pfeilerfels unter der Brücke. */
    const val BRIDGE_PIER_SPACING = 32

    /** Siegelbrücken: In diesen Brücken fehlen die mittleren [SEAL_GAP] Blöcke, bis der genannte Boss besiegt ist. */
    const val SEAL_GAP = 16
    val SEALED_BRIDGES: Map<Pair<Char, Char>, String> = mapOf(
        ('B' to 'C') to "hermes",
        ('C' to 'D') to "hermes",
        ('D' to 'E') to "brontes",
        ('E' to 'F') to "kratos",
    )

    /** Siegelbrücken werden nachgesetzt, wenn ein Spieler näher als so viele Blöcke ist. */
    const val SEAL_RESTORE_DISTANCE = 96

    /** Kasten um den Pfad: Hier entstehen keine Nebeninseln (x −140 … 290, z −800 … 100). */
    const val PATH_MIN_X = -140
    const val PATH_MAX_X = 290
    const val PATH_MIN_Z = -800
    const val PATH_MAX_Z = 100

    fun inPathBox(x: Int, z: Int): Boolean = x in PATH_MIN_X..PATH_MAX_X && z in PATH_MIN_Z..PATH_MAX_Z

    /** Wer im Olymp unter diese Höhe fällt, stürzt in die Oberwelt (PLAN 7.1). */
    const val FALL_Y = 8

    // ---------------------------------------------------------------------------------------------
    // Hilfen
    // ---------------------------------------------------------------------------------------------

    /** Richtung zum Winkel [degrees] (Vereinbarung oben). */
    fun dir(degrees: Double): Vec3d {
        val a = Math.toRadians(degrees)
        return Vec3d(cos(a), 0.0, sin(a))
    }

    /**
     * Station nach Boss- oder Ortsname: `hermes`/`tempel` → B, `hekate`/`dreiweg` → C, `brontes`/`schmiede` → D,
     * `kratos`/`stufen` → E, `zeus`/`thron` → F, `olymp`/`ankunft` → A. Unbekannt: `null`.
     */
    fun station(name: String): Isle? = when (name.lowercase()) {
        "olymp", "ankunft", "a" -> A
        "hermes", "tempel", "b" -> B
        "hekate", "dreiweg", "c" -> C
        "brontes", "schmiede", "d" -> D
        "kratos", "bia", "stufen", "e" -> E
        "zeus", "thron", "f" -> F
        else -> null
    }

    /** Station zum Altar-Wert `boss_altar[boss=n]` (0 Hekate, 1 Brontes, 2 Kratos, 3 Zeus, 4 frei). */
    fun altarStation(boss: Int): Isle? = when (boss) {
        0 -> C
        1 -> D
        2 -> E
        3 -> F
        else -> null
    }
}
