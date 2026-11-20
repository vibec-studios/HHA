package dev.henny.hha.world.palace

import dev.henny.hha.HhaBlocks
import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.world.BlockCanvas
import dev.henny.hha.world.DecoPalettes
import dev.henny.hha.world.Kit
import dev.henny.hha.world.Pal
import dev.henny.hha.world.PalacePlan.pal
import dev.henny.hha.world.PalacePlan.stele
import dev.henny.hha.world.TartarusPlans.HALL
import dev.henny.hha.world.TartarusPlans.SEAL_HALF_WIDTH
import dev.henny.hha.world.TartarusPlans.SEAL_HEIGHT
import dev.henny.hha.world.TartarusPlans.SEAL_Z
import dev.henny.hha.world.TartarusPlans.inSealOpening
import dev.henny.hha.world.blob
import dev.henny.hha.world.block
import dev.henny.hha.world.brazier
import dev.henny.hha.world.footing
import dev.henny.hha.world.heavyChain
import dev.henny.hha.world.limb
import dev.henny.hha.world.prism
import dev.henny.hha.world.skullFacing
import dev.henny.hha.world.taper
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Der Anmarsch zum Palast (Überarbeitung 4, PLAN 5.2, Vorbild Bloods Schlachtweg): auf der Mittelachse nach Süden sechs
 * Abschnitte, alle `dz` relativ zur Palastmitte, Boden auf `o.y` (Füße `o.y + 1`).
 *
 * | Abschnitt | dz | lichte Breite / Höhe | Funktion |
 * | --- | --- | --- | --- |
 * | 0 Obeliskenallee | 101 … 118 | 15, offen | [approach]: sechs Obelisken, zwei Kolosse, die letzten warmen Laternen |
 * | 1 Schwelle | 88 … 100 | 15 / 10 (außen 12 + Giebel) | [propylon]: drei Durchgänge, Giebel mit Bident |
 * | 2 Gang der Schatten | 78 … 87 | 5 / 5, geschlossen | [shadows]: durch das Torhaus, Nischen mit Schatten und grünen Laternen |
 * | 3 Halle der Richter | 65 … 77 | 13 / 9, geschlossen | [judges]: Säulenschiff, Minos, Rhadamanthys, Aiakos, vier Feuerschalen |
 * | 4 Styx-Brücke | 55 … 64 | Becken 15, Brücke 5 | [styx]: gemauertes Becken, Brücke mit vier Rippenbögen, Brüstung 4 hoch |
 * | 5 Hundetor | 47 … 54 | 19 / 15 | [portico]: Kerberos-Fassade, das Siegel im Maul |
 *
 * Die Hülle ist geschlossen: Wer am Propylon hineingeht, kommt nur am Siegel wieder heraus (Endwände dort, wo der nächste
 * Abschnitt schmaler ist; Brüstung und Fassade zum Becken 4 hoch bzw. massiv). Das Licht wird zum Tor hin seltener und
 * grüner: vier warme Laternen in der Allee, danach nur grünes Feuer, Seelenlaternen und Seelenadern.
 */
internal object PalaceApproach {

    // --- Abschnittsgrenzen (dz) und lichte Maße; die Wegpunkte in TartarusArenas.palaceApproach liegen darauf ---------

    const val AVENUE_FROM = 101
    const val AVENUE_TO = 118
    const val AVENUE_HALF = 7

    const val THRESHOLD_FROM = 88
    const val THRESHOLD_TO = 100
    const val THRESHOLD_HALF = 7
    const val THRESHOLD_HEIGHT = 12

    const val SHADOWS_FROM = 78
    const val SHADOWS_TO = 87
    const val SHADOWS_HALF = 2
    const val SHADOWS_HEIGHT = 5

    const val JUDGES_FROM = 65
    const val JUDGES_TO = 77
    const val JUDGES_HALF = 6
    const val JUDGES_HEIGHT = 9

    const val STYX_FROM = 55
    const val STYX_TO = 64
    const val BASIN_HALF = 7
    const val BRIDGE_HALF = 2
    const val PARAPET = 4

    /** Wasserspiegel und Grund des Beckens unter dem Boden (der Ringgraben bleibt randvoll, Wehre trennen beide). */
    const val BASIN_WATER = 2
    const val BASIN_FLOOR = 6

    const val GATE_FROM = 47
    const val GATE_TO = 54
    const val GATE_HALF = 9
    const val GATE_HEIGHT = 15

    /** Die Vorderseite der Kerberos-Fassade (zugleich Nordrand des Beckens). */
    const val GATE_FACE = 55

    /** Leitpfeile aus Seelenader in der Mittellinie: Spitzen bei diesen dz (alle 6 Blöcke, Spitze zum Tor). */
    val ARROWS = intArrayOf(114, 108, 102, 96, 90, 84, 78, 72, 60)

    // --- Material ---------------------------------------------------------------------------------------------------

    private fun s(b: Block): BlockState = b.defaultState
    private val BRICKS: BlockState get() = s(HhaDecoBlocks.TARTARUS_BASALT_BRICKS)
    private val POLISHED: BlockState get() = s(HhaDecoBlocks.POLISHED_TARTARUS_BASALT)
    private val CHISELED: BlockState get() = s(HhaDecoBlocks.CHISELED_TARTARUS_BASALT)
    private val PILLAR: BlockState get() = s(HhaDecoBlocks.TARTARUS_BASALT_PILLAR)
    private val VEIN: BlockState get() = s(HhaDecoBlocks.SOUL_VEIN_BASALT)
    private val BRONZE: BlockState get() = s(HhaDecoBlocks.TARTARUS_BRONZE)
    private val GILDED: BlockState get() = s(Blocks.GILDED_BLACKSTONE)
    private val BONE: BlockState get() = s(Blocks.BONE_BLOCK)

    /** Innenwände des Anmarschs: Basaltziegel mit Schwarzstein-Flecken. */
    private val INNER: Pal = DecoPalettes.basaltWall(0.08)

    /** Gewand der Kolosse: senkrechte Falten aus poliertem Basalt und Schwarzstein. */
    private val ROBE: Pal = { x, _, z -> if (Math.floorMod(x + z, 2) == 0) s(Blocks.POLISHED_BASALT) else s(Blocks.BLACKSTONE) }

    /** Fassade des Hundetors: Basaltziegel (heller als die Köpfe), damit sich die Köpfe abheben. */
    private val FACADE: Pal = DecoPalettes.basaltWall(0.04)

    /** Hundeköpfe: Schwarzsteinziegel, rissig, dazwischen grober Schwarzstein. */
    private val HOUND: Pal = { x, y, z ->
        when (Kit.noise(x, y, z) % 7) {
            0 -> s(Blocks.BLACKSTONE)
            1 -> s(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS)
            else -> s(Blocks.POLISHED_BLACKSTONE_BRICKS)
        }
    }

    private fun at(c: BlockCanvas, o: BlockPos, dx: Int, dy: Int, dz: Int, state: BlockState) = c.block(o.x + dx, o.y + dy, o.z + dz, state)

    /** Leitpfeil aus Seelenader im Boden: Spitze bei [tip], die Flügel nach Süden (zum Betrachter). */
    private fun arrow(c: BlockCanvas, o: BlockPos, tip: Int) {
        at(c, o, 0, 0, tip, VEIN)
        for (side in intArrayOf(-1, 1)) {
            at(c, o, side, 0, tip + 1, VEIN)
            at(c, o, side * 2, 0, tip + 2, VEIN)
        }
    }

    private fun arrows(c: BlockCanvas, o: BlockPos, from: Int, to: Int) {
        for (tip in ARROWS) if (tip in from..to) arrow(c, o, tip)
    }

    // =================================================================================================================
    // Abschnitt 0: Obeliskenallee
    // =================================================================================================================

    /** Abschnitt 0 (dz 101 … 118): offene Allee mit Leitpfeilen, sechs Obelisken, vier warmen Laternen und zwei Kolossen. */
    internal fun approach(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        // Unterbau: eine gemauerte Fläche auf Bodenhöhe, Fundament bis ins Gelände, darüber frei.
        for (dz in AVENUE_FROM - 1..AVENUE_TO + 1) {
            for (dx in -23..23) {
                val x = o.x + dx
                val z = o.z + dz
                c.footing(x, z, y - 1, pal.PLINTH)
                for (h in 1..32) c.block(x, y + h, z, Kit.AIR)
                c.block(x, y, z, avenueFloor(dx, dz, x, y, z))
            }
        }
        // Eine Stufe am Südende, damit der Weg aus dem Gelände sichtbar beginnt.
        for (dx in -AVENUE_HALF..AVENUE_HALF) at(c, o, dx, 0, AVENUE_TO + 2, Kit.stair(HhaDecoBlocks.TARTARUS_BASALT_BRICK_STAIRS, Direction.NORTH))
        arrows(c, o, AVENUE_FROM, AVENUE_TO)
        // Sechs Obelisken (die bisherigen aus dem Hof), drei je Seite.
        for (dz in intArrayOf(103, 109, 115)) for (side in intArrayOf(-1, 1)) obelisk(c, o.x + side * 11, y, o.z + dz, 22)
        // Die letzten warmen Laternen des Wegs.
        for (dz in intArrayOf(106, 112)) for (side in intArrayOf(-1, 1)) lampPost(c, o.x + side * 8, y, o.z + dz)
        // Wahrzeichen: zwei kolossale Totenwächter am Beginn.
        for (side in intArrayOf(-1, 1)) colossus(c, o, o.x + side * 19, o.z + 116, side)
    }

    private fun avenueFloor(dx: Int, dz: Int, x: Int, y: Int, z: Int): BlockState {
        val adx = abs(dx)
        return when {
            adx == AVENUE_HALF -> POLISHED
            adx < AVENUE_HALF -> if (Kit.noise(x, y, z) % 9 == 0) s(HhaDecoBlocks.TARTARUS_BASALT) else BRICKS
            adx == AVENUE_HALF + 1 -> s(Blocks.POLISHED_DEEPSLATE)
            Math.floorMod(dz, 6) == 0 -> s(Blocks.POLISHED_DEEPSLATE)
            Kit.noise(x, y, z) % 11 == 0 -> s(Blocks.CRACKED_DEEPSLATE_TILES)
            else -> s(Blocks.DEEPSLATE_TILES)
        }
    }

    internal fun obelisk(c: BlockCanvas, x: Int, y: Int, z: Int, height: Int) {
        c.prism(x - 2, y + 1, z - 2, x + 2, y + 1, z + 2, pal.PLINTH)
        c.prism(x - 2, y + 2, z - 2, x + 2, y + 2, z + 2) { px, py, pz -> if (abs(px - x) == 2 || abs(pz - z) == 2) Kit.stair(Blocks.POLISHED_DEEPSLATE_STAIRS, Kit.inward(px - x, pz - z)) else pal.TRIM(px, py, pz) }
        c.taper(x, z, y + 3, y + height, 1.8, 0.6, pal.FACE)
        c.block(x, y + height + 1, z, pal.GOLD)
        c.block(x, y + height + 2, z, Kit.FIRE)
    }

    /** Laternenpfosten: Mauerstab mit einer normalen (warmen) Laterne. */
    private fun lampPost(c: BlockCanvas, x: Int, y: Int, z: Int) {
        c.block(x, y + 1, z, CHISELED)
        c.block(x, y + 2, z, s(HhaDecoBlocks.TARTARUS_BASALT_BRICK_WALL))
        c.block(x, y + 3, z, s(HhaDecoBlocks.TARTARUS_BASALT_BRICK_WALL))
        c.block(x, y + 4, z, Kit.lantern(hanging = false, soul = false))
    }

    /**
     * Kolossaler Totenwächter (24 hoch über dem Boden), blickt nach Süden auf die Ankommenden. Sockel 7 × 7, Gewand als
     * Kegel, Schultern und Kapuze als Körper, Gesicht aus weinendem Obsidian; die äußere Hand hält einen Stab mit einem
     * Lagerfeuer als Fackel, die innere ruht auf einem Bronzeschwert. [side] = −1 West, +1 Ost.
     */
    private fun colossus(c: BlockCanvas, o: BlockPos, cx: Int, cz: Int, side: Int) {
        val y = o.y
        for (x in cx - 3..cx + 3) {
            for (z in cz - 3..cz + 3) {
                val ring = maxOf(abs(x - cx), abs(z - cz))
                c.footing(x, z, y, pal.PLINTH)
                c.block(x, y + 1, z, pal.PLINTH(x, y + 1, z))
                c.block(x, y + 2, z, if (ring == 3 && abs(x - cx) == 3 && abs(z - cz) == 3) CHISELED else if (ring == 3) BRICKS else pal.FACE(x, y + 2, z))
                c.block(x, y + 3, z, when {
                    abs(x - cx) == 3 && abs(z - cz) == 3 -> CHISELED
                    ring == 3 -> Kit.stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, Kit.inward(x - cx, z - cz))
                    ring == 2 -> CHISELED
                    else -> POLISHED
                })
            }
        }
        // Gewand, Schultern, Kapuze.
        c.taper(cx, cz, y + 4, y + 16, 2.7, 1.8, ROBE)
        c.blob(cx, y + 17, cz, 3.3, 1.2, 2.0, ROBE)
        c.blob(cx, y + 20, cz, 1.7, 2.2, 1.8, ROBE)
        for (h in 19..20) c.block(cx, y + h, cz + 1, s(Blocks.CRYING_OBSIDIAN))
        c.block(cx, y + 21, cz + 2, Kit.stair(Blocks.BLACKSTONE_STAIRS, Direction.NORTH, top = true))
        for (a in intArrayOf(-1, 1)) c.block(cx + a, y + 21, cz + 1, Kit.stair(Blocks.BLACKSTONE_STAIRS, Direction.NORTH, top = true))
        // Äußerer Arm mit dem Fackelstab.
        val sx = cx + side * 3
        c.limb(cx + side * 2, y + 17, cz, sx, y + 14, cz + 2, 1.0, 0.8, ROBE)
        for (h in 4..22) c.block(sx, y + h, cz + 3, s(Blocks.POLISHED_BLACKSTONE_WALL))
        c.block(sx, y + 23, cz + 3, GILDED)
        c.block(sx, y + 24, cz + 3, s(Blocks.CAMPFIRE))
        // Innerer Arm auf dem Schwert.
        val ix = cx - side
        c.limb(cx - side * 2, y + 17, cz, ix, y + 12, cz + 2, 0.9, 0.7, ROBE)
        for (h in 4..11) c.block(ix, y + h, cz + 3, BRONZE)
        c.block(ix, y + 12, cz + 3, GILDED)
        for (a in intArrayOf(-1, 1)) c.block(ix + a, y + 11, cz + 3, s(HhaDecoBlocks.TARTARUS_BRONZE_SLAB))
    }

    // =================================================================================================================
    // Abschnitt 1: Schwelle (Propylon)
    // =================================================================================================================

    /**
     * Abschnitt 1 (dz 88 … 100): das Propylon als geschlossener Vorbau, außen 19 breit und 12 hoch, mit Giebel. Vorn drei
     * Durchgänge, der mittlere als offener Rundbogen (das erste Tor); hinten führt nur der Gang der Schatten weiter. Ab
     * hier gibt es nur noch grünes Licht.
     */
    internal fun propylon(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val outer = THRESHOLD_HALF + 2
        val front = THRESHOLD_TO
        // Fundament und Freiraum.
        for (dz in THRESHOLD_FROM..front) {
            for (dx in -outer..outer) {
                val x = o.x + dx
                val z = o.z + dz
                c.footing(x, z, y - 1, pal.PLINTH)
                for (h in 1..22) c.block(x, y + h, z, Kit.AIR)
                c.block(x, y, z, if (abs(dx) <= THRESHOLD_HALF) (if (abs(dx) == THRESHOLD_HALF) BRICKS else POLISHED) else pal.PLINTH(x, y, z))
            }
        }
        // Mauern: 2 dick, außen Schwarzstein mit Sockel, innen Basaltziegel; Fries und Gesims.
        for (dz in THRESHOLD_FROM..front) {
            for (dx in -outer..outer) {
                val adx = abs(dx)
                val wall = adx > THRESHOLD_HALF || dz >= front - 1
                if (!wall) continue
                for (dy in 1..THRESHOLD_HEIGHT) {
                    val outside = adx == outer || dz == front
                    val state = when {
                        dy == 1 && outside -> pal.PLINTH(o.x + dx, y + dy, o.z + dz)
                        dy == THRESHOLD_HEIGHT - 1 && outside -> CHISELED
                        outside -> pal.FACE(o.x + dx, y + dy, o.z + dz)
                        else -> INNER(o.x + dx, y + dy, o.z + dz)
                    }
                    at(c, o, dx, dy, dz, state)
                }
            }
        }
        // Innenraum: Decke mit Querbalken, Seelenlaternen, Wandpfeiler, die Stele.
        for (dz in THRESHOLD_FROM..front - 2) {
            for (dx in -THRESHOLD_HALF..THRESHOLD_HALF) {
                at(c, o, dx, THRESHOLD_HEIGHT - 1, dz, pal.FACE(o.x + dx, y + THRESHOLD_HEIGHT - 1, o.z + dz))
                at(c, o, dx, THRESHOLD_HEIGHT, dz, pal.FACE(o.x + dx, y + THRESHOLD_HEIGHT, o.z + dz))
                if (dz == 90 || dz == 93 || dz == 96) at(c, o, dx, THRESHOLD_HEIGHT - 2, dz, CHISELED)
            }
        }
        for (side in intArrayOf(-1, 1)) {
            for (dz in intArrayOf(91, 95)) {
                for (dy in 1..THRESHOLD_HEIGHT - 3) at(c, o, side * (THRESHOLD_HALF + 1), dy, dz, PILLAR)
                at(c, o, side * (THRESHOLD_HALF + 1), THRESHOLD_HEIGHT - 2, dz, CHISELED)
            }
            for (dz in intArrayOf(92, 95)) {
                at(c, o, side * 4, THRESHOLD_HEIGHT - 2, dz, Kit.chain())
                at(c, o, side * 4, THRESHOLD_HEIGHT - 3, dz, Kit.lantern())
            }
        }
        at(c, o, 6, 1, THRESHOLD_FROM + 1, stele(5))
        arrows(c, o, THRESHOLD_FROM, front)
        // Die Front: Mittelbogen 5 breit (das erste Tor, offen), zwei Seitendurchgänge 3 breit, Pfeiler aus Basaltsäulen.
        for (dz in front - 1..front) {
            for (dx in -outer..outer) {
                val adx = abs(dx)
                for (dy in 1..9) {
                    val middle = adx <= 2 && (dy <= 6 || hypot(dx.toDouble(), dy - 6.0) <= 2.6)
                    val sidePass = adx in 5..7 && (dy <= 5 || (dy == 6 && adx == 6))
                    when {
                        middle || sidePass -> at(c, o, dx, dy, dz, Kit.AIR)
                        dz == front && adx <= 3 && dy >= 6 && hypot(dx.toDouble(), dy - 6.0) <= 3.7 -> at(c, o, dx, dy, dz, CHISELED)
                        dz == front && adx in 3..4 -> at(c, o, dx, dy, dz, PILLAR)
                        dz == front && adx == 8 && dy % 3 == 0 -> at(c, o, dx, dy, dz, POLISHED)
                    }
                }
                if (adx <= 7) at(c, o, dx, 0, dz, POLISHED)
            }
        }
        for (dx in intArrayOf(-4, -3, 3, 4)) at(c, o, dx, 1, front + 1, Kit.stair(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_STAIRS, Direction.NORTH))
        // Gesims vorn und an den Seiten.
        for (dx in -outer - 1..outer + 1) at(c, o, dx, THRESHOLD_HEIGHT, front + 1, Kit.stair(HhaDecoBlocks.TARTARUS_BASALT_BRICK_STAIRS, Direction.NORTH, top = true))
        for (dz in THRESHOLD_FROM..front) for (side in intArrayOf(-1, 1)) {
            at(c, o, side * (outer + 1), THRESHOLD_HEIGHT, dz, Kit.stair(HhaDecoBlocks.TARTARUS_BASALT_BRICK_STAIRS, if (side > 0) Direction.WEST else Direction.EAST, top = true))
        }
        // Giebeldach in Längsrichtung, vorn das Giebelfeld mit dem Bident als Relief.
        val eave = y + THRESHOLD_HEIGHT + 1
        for (dx in -outer - 1..outer + 1) {
            val up = (10 - abs(dx)) * 6 / 10
            for (dz in THRESHOLD_FROM..front) {
                val x = o.x + dx
                val z = o.z + dz
                for (h in 0 until up) c.block(x, eave + h, z, if (dz == front && h == 0) CHISELED else pal.FACE(x, eave + h, z))
                c.block(x, eave + up, z, Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, if (dx < 0) Direction.EAST else Direction.WEST))
            }
        }
        at(c, o, 0, THRESHOLD_HEIGHT + 8, THRESHOLD_FROM, pal.GOLD)
        at(c, o, 0, THRESHOLD_HEIGHT + 8, front, pal.GOLD)
        val relief = front + 1
        for (dy in 13..15) at(c, o, 0, dy, relief, CHISELED)
        for (dx in -1..1) at(c, o, dx, 15, relief, CHISELED)
        for (dx in intArrayOf(-1, 1)) for (dy in 16..17) at(c, o, dx, dy, relief, CHISELED)
        for (dx in intArrayOf(-outer - 1, outer + 1)) at(c, o, dx, THRESHOLD_HEIGHT + 1, front, GILDED)
    }

    // =================================================================================================================
    // Abschnitt 2: Gang der Schatten
    // =================================================================================================================

    /**
     * Abschnitt 2 (dz 78 … 87): 5 breit, 5 hoch, geschlossen, durch das Torhaus der Hofmauer und als gedeckter Gang in den
     * Hof. Wände 2 dick, flache Decke mit Rippen alle 3 Blöcke; in den Wänden abwechselnd Nischen mit einem Schatten in
     * Ketten und mit einer grünen Laterne.
     */
    internal fun shadows(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val wall = SHADOWS_HALF + 2
        for (dz in SHADOWS_FROM..SHADOWS_TO) {
            for (dx in -wall..wall) {
                val adx = abs(dx)
                at(c, o, dx, 0, dz, if (adx <= SHADOWS_HALF) POLISHED else BRICKS)
                for (dy in 1..SHADOWS_HEIGHT) {
                    at(c, o, dx, dy, dz, if (adx <= SHADOWS_HALF) Kit.AIR else if (adx == SHADOWS_HALF + 1) INNER(o.x + dx, y + dy, o.z + dz) else pal.FACE(o.x + dx, y + dy, o.z + dz))
                }
                at(c, o, dx, SHADOWS_HEIGHT + 1, dz, if (adx <= SHADOWS_HALF) BRICKS else pal.FACE(o.x + dx, y + SHADOWS_HEIGHT + 1, o.z + dz))
                // Rippen: unter der Decke eine Lage obere Stufen quer über den Gang.
                if (Math.floorMod(dz, 3) == 0 && adx <= SHADOWS_HALF) at(c, o, dx, SHADOWS_HEIGHT, dz, Kit.slab(HhaDecoBlocks.TARTARUS_BASALT_BRICK_SLAB, top = true))
            }
        }
        // Außenhaut des gedeckten Gangs im Hof: Sockel, Abdeckung aus Platten.
        for (dz in SHADOWS_FROM..SHADOWS_FROM + 2) {
            for (dx in -wall..wall) at(c, o, dx, SHADOWS_HEIGHT + 2, dz, Kit.slab(Blocks.POLISHED_BLACKSTONE_BRICK_SLAB))
            for (side in intArrayOf(-1, 1)) {
                at(c, o, side * (wall + 1), 1, dz, Kit.stair(Blocks.POLISHED_DEEPSLATE_STAIRS, if (side > 0) Direction.WEST else Direction.EAST))
                at(c, o, side * wall, SHADOWS_HEIGHT + 1, dz, CHISELED)
            }
        }
        // Nischen: Schatten bei k = 0, Laterne bei k = 1, gegenüber vertauscht.
        for (dz in SHADOWS_FROM + 1..SHADOWS_TO - 2 step 2) {
            val k = (dz - SHADOWS_FROM - 1) / 2 % 2
            for (side in intArrayOf(-1, 1)) {
                val shade = (k == 0) == (side > 0)
                val nx = side * (SHADOWS_HALF + 1)
                if (shade) shadeNiche(c, o, nx, dz, side) else lanternNiche(c, o, nx, dz)
            }
        }
        arrows(c, o, SHADOWS_FROM, SHADOWS_TO)
        // Portal zur Schwelle: Rahmen aus gemeißeltem Basalt in der Front des Torhauses.
        for (dy in 1..SHADOWS_HEIGHT + 1) for (side in intArrayOf(-1, 1)) at(c, o, side * (SHADOWS_HALF + 1), dy, SHADOWS_TO, CHISELED)
        for (dx in -SHADOWS_HALF - 1..SHADOWS_HALF + 1) at(c, o, dx, SHADOWS_HEIGHT + 1, SHADOWS_TO, CHISELED)
        at(c, o, 0, SHADOWS_HEIGHT + 2, SHADOWS_TO, CHISELED)
    }

    /** Ein Schatten in Ketten: Fußfessel aus Bronze, dunkler Leib, Schädel, Kette zur Bronzeöse in der Decke. */
    private fun shadeNiche(c: BlockCanvas, o: BlockPos, nx: Int, dz: Int, side: Int) {
        at(c, o, nx, 1, dz, BRONZE)
        at(c, o, nx, 2, dz, s(Blocks.POLISHED_BASALT))
        at(c, o, nx, 3, dz, skullFacing(if (side > 0) Direction.WEST else Direction.EAST, wither = Kit.noise(nx, 3, dz) % 2 == 0))
        at(c, o, nx, 4, dz, Kit.chain())
        at(c, o, nx, 5, dz, BRONZE)
    }

    /** Eine grüne Laterne auf einem Sockel, darüber ein Sturz. */
    private fun lanternNiche(c: BlockCanvas, o: BlockPos, nx: Int, dz: Int) {
        at(c, o, nx, 1, dz, CHISELED)
        at(c, o, nx, 2, dz, Kit.lantern(hanging = false))
        at(c, o, nx, 3, dz, Kit.AIR)
        at(c, o, nx, 4, dz, POLISHED)
    }

    // =================================================================================================================
    // Abschnitt 3: Halle der Richter
    // =================================================================================================================

    /** Scheitel des Tonnengewölbes über der Mittellinie: so hoch ist die Halle bei |dx| = [adx] (Füße bis Decke). */
    fun vaultTop(adx: Int): Int = 5 + (4.0 * sqrt((1.0 - (adx / 7.0) * (adx / 7.0)).coerceAtLeast(0.0))).roundToInt()

    private const val JUDGE_NICHE = 71
    private val JUDGE_COLUMNS = intArrayOf(68, 71, 74)

    /**
     * Abschnitt 3 (dz 65 … 77): Säulenschiff 13 breit, 9 hoch, Tonnengewölbe mit Gurtbögen auf drei Säulenpaaren. Minos
     * (West) und Rhadamanthys (Ost) stehen in Seitennischen, Aiakos über dem Ausgang. Vier Feuerschalen; die Seelenadern
     * in den Wänden werden zum Ausgang (Norden) hin dichter.
     */
    internal fun judges(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        val wall = JUDGES_HALF + 2
        val inside = JUDGES_FROM + 2..JUDGES_TO - 1
        // Masse der Halle bis unter das Dach.
        for (dz in JUDGES_FROM..JUDGES_TO) {
            for (dx in -wall..wall) {
                at(c, o, dx, 0, dz, BRICKS)
                for (dy in 1..11) at(c, o, dx, dy, dz, if (abs(dx) == wall || dz == JUDGES_TO || dz == JUDGES_FROM) pal.FACE(o.x + dx, y + dy, o.z + dz) else INNER(o.x + dx, y + dy, o.z + dz))
            }
        }
        // Innenraum mit Tonnengewölbe; Gurtbögen über den Säulen.
        for (dz in inside) {
            for (dx in -JUDGES_HALF..JUDGES_HALF) {
                val adx = abs(dx)
                val top = vaultTop(adx)
                at(c, o, dx, 0, dz, if (adx <= 3) POLISHED else BRICKS)
                for (dy in 1..top) at(c, o, dx, dy, dz, Kit.AIR)
                at(c, o, dx, top + 1, dz, BRICKS)
                if (dz in JUDGE_COLUMNS) at(c, o, dx, top, dz, CHISELED)
            }
        }
        // Säulenpaare aus Basaltsäulen.
        for (dz in JUDGE_COLUMNS) for (side in intArrayOf(-1, 1)) {
            at(c, o, side * 4, 1, dz, POLISHED)
            for (dy in 2 until vaultTop(4)) at(c, o, side * 4, dy, dz, PILLAR)
        }
        // Türen: Süd zum Gang der Schatten, Nord zur Brücke.
        for (dz in intArrayOf(JUDGES_TO, JUDGES_FROM, JUDGES_FROM + 1)) {
            for (dx in -2..2) {
                at(c, o, dx, 0, dz, POLISHED)
                for (dy in 1..5) at(c, o, dx, dy, dz, Kit.AIR)
            }
            for (dx in -3..3) at(c, o, dx, 6, dz, CHISELED)
        }
        // Lisenen, Sockel und Fries außen.
        for (dz in JUDGES_FROM..JUDGES_TO) for (side in intArrayOf(-1, 1)) {
            val x = o.x + side * (wall + 1)
            val z = o.z + dz
            if ((dz - JUDGES_FROM) % 3 == 0) {
                for (dy in 1..10) c.block(x, y + dy, z, pal.PIER(x, y + dy, z))
            } else {
                c.block(x, y + 1, z, pal.PLINTH(x, y + 1, z))
                c.block(x, y + 2, z, Kit.stair(Blocks.POLISHED_DEEPSLATE_STAIRS, if (side > 0) Direction.WEST else Direction.EAST))
            }
            at(c, o, side * wall, 9, dz, CHISELED)
        }
        // Dach: Gesims, dann ein flaches Satteldach.
        val eave = y + 12
        for (dz in JUDGES_FROM..JUDGES_TO) {
            for (dx in -wall - 1..wall + 1) {
                val up = (wall + 1 - abs(dx)) / 2
                val x = o.x + dx
                val z = o.z + dz
                for (h in 0 until up) c.block(x, eave + h, z, pal.FACE(x, eave + h, z))
                c.block(x, eave + up, z, Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, if (dx < 0) Direction.EAST else Direction.WEST))
            }
            for (side in intArrayOf(-1, 1)) at(c, o, side * (wall + 2), 11, dz, Kit.stair(Blocks.POLISHED_BLACKSTONE_BRICK_STAIRS, if (side > 0) Direction.WEST else Direction.EAST, top = true))
        }
        // Seitennischen mit Minos und Rhadamanthys.
        for (side in intArrayOf(-1, 1)) judgeNiche(c, o, side)
        // Aiakos über dem Ausgang, in einer Nische der Nordwand.
        aiakos(c, o)
        // Nur vier Feuerschalen.
        for (dz in intArrayOf(JUDGES_FROM + 2, JUDGES_TO - 2)) for (side in intArrayOf(-1, 1)) c.brazier(o.x + side * 5, y + 1, o.z + dz, 2)
        veins(c, o)
        arrows(c, o, JUDGES_FROM + 2, JUDGES_TO)
    }

    /** Nische in der Seitenwand (dz 69 … 73, 3 tief) mit einem Richter auf dem Sockel. West: Minos mit Stab, Ost: Rhadamanthys mit Waage. */
    private fun judgeNiche(c: BlockCanvas, o: BlockPos, side: Int) {
        val y = o.y
        val wall = JUDGES_HALF + 1
        for (dz in JUDGE_NICHE - 2..JUDGE_NICHE + 2) {
            val bay = abs(dz - JUDGE_NICHE) <= 1
            for (d in 0..3) {
                val dx = side * (wall + d)
                for (dy in 0..9) {
                    val state = when {
                        dy == 0 -> BRICKS
                        d == 3 || !bay -> if (d >= 1 && dy in 1..9) pal.FACE(o.x + dx, y + dy, o.z + dz) else null
                        dy <= 7 -> Kit.AIR
                        dy == 8 -> CHISELED
                        else -> null
                    } ?: continue
                    at(c, o, dx, dy, dz, state)
                }
            }
            // Außenhaut der Nische (springt eine Lage vor) mit Abdeckung.
            for (dy in 1..9) at(c, o, side * (wall + 3), dy, dz, pal.FACE(o.x + side * (wall + 3), y + dy, o.z + dz))
            at(c, o, side * (wall + 3), 10, dz, Kit.slab(Blocks.POLISHED_BLACKSTONE_BRICK_SLAB))
        }
        val fx = side * (wall + 1)
        val facing = if (side > 0) Direction.WEST else Direction.EAST
        judge(c, o, fx, JUDGE_NICHE, facing)
        val hand = side * wall
        if (side < 0) {
            // Minos: Stab mit goldener Spitze, in der Hand.
            for (dy in 1..6) at(c, o, hand, dy, JUDGE_NICHE + 1, s(Blocks.POLISHED_BLACKSTONE_WALL))
            at(c, o, hand, 7, JUDGE_NICHE + 1, GILDED)
            at(c, o, hand, 4, JUDGE_NICHE, BRONZE)
        } else {
            // Rhadamanthys: Waage; Hände, Pfosten, Drehpunkt, Balken, zwei Schalen an Ketten.
            at(c, o, hand, 4, JUDGE_NICHE, BRONZE)
            at(c, o, hand, 5, JUDGE_NICHE, s(Blocks.POLISHED_BLACKSTONE_WALL))
            at(c, o, hand, 6, JUDGE_NICHE, GILDED)
            for (d in intArrayOf(-1, 1)) {
                at(c, o, hand, 6, JUDGE_NICHE + d, Kit.chain(Direction.Axis.Z))
                at(c, o, hand, 5, JUDGE_NICHE + d, Kit.chain())
                at(c, o, hand, 4, JUDGE_NICHE + d, Kit.slab(HhaDecoBlocks.TARTARUS_BRONZE_SLAB, top = true))
            }
        }
    }

    /** Ein Richter: Sockel, Gewand, Schultern, Kopf unter der Kapuze; blickt nach [facing]. */
    private fun judge(c: BlockCanvas, o: BlockPos, dx: Int, dz: Int, facing: Direction) {
        val side = facing.rotateYClockwise()
        fun put(a: Int, f: Int, h: Int, state: BlockState) = at(c, o, dx + side.offsetX * a + facing.offsetX * f, h, dz + side.offsetZ * a + facing.offsetZ * f, state)
        put(0, 0, 1, CHISELED)
        for (a in -1..1) put(a, 0, 2, if (a == 0) s(Blocks.POLISHED_BASALT) else s(Blocks.BLACKSTONE))
        for (a in -1..1) put(a, 0, 3, if (a == 0) s(Blocks.POLISHED_BASALT) else s(Blocks.BLACKSTONE))
        for (a in -1..1) put(a, 0, 4, s(Blocks.POLISHED_BLACKSTONE))
        put(0, 0, 5, s(Blocks.CRYING_OBSIDIAN))
        put(0, 0, 6, Kit.stair(Blocks.BLACKSTONE_STAIRS, facing.opposite))
        for (a in intArrayOf(-1, 1)) put(a, 0, 5, Kit.stair(Blocks.BLACKSTONE_STAIRS, facing.opposite, top = true))
    }

    /** Aiakos mit dem Schlüssel des Hades, über dem Ausgang in einer Nische der Nordwand (blickt nach Süden in die Halle). */
    private fun aiakos(c: BlockCanvas, o: BlockPos) {
        val dz = JUDGES_FROM + 1
        for (dx in -2..2) for (dy in 7..9) at(c, o, dx, dy, dz, Kit.AIR)
        for (dx in -2..2) at(c, o, dx, 7, dz + 1, Kit.slab(HhaDecoBlocks.POLISHED_TARTARUS_BASALT_SLAB, top = true))
        at(c, o, 0, 7, dz, CHISELED)
        at(c, o, 0, 8, dz, s(Blocks.POLISHED_BASALT))
        for (a in intArrayOf(-1, 1)) at(c, o, a, 8, dz, s(Blocks.POLISHED_BLACKSTONE))
        at(c, o, 0, 9, dz, s(Blocks.CRYING_OBSIDIAN))
        // Der Schlüssel: Bart aus Gold, Schaft aus Bronze, in der rechten Hand.
        at(c, o, 2, 8, dz, BRONZE)
        at(c, o, 2, 9, dz, GILDED)
        at(c, o, 2, 7, dz, GILDED)
    }

    /** Seelenadern in den Innenwänden: geschwungene Linien, deren Zahl und Dichte zum Ausgang (Norden) hin wächst. */
    private fun veins(c: BlockCanvas, o: BlockPos) {
        val wall = JUDGES_HALF + 1
        for (dz in JUDGES_FROM + 2..JUDGES_TO - 1) {
            if (abs(dz - JUDGE_NICHE) <= 2) continue
            val t = (JUDGES_TO - 1 - dz).toDouble() / (JUDGES_TO - JUDGES_FROM - 3)
            for (side in intArrayOf(-1, 1)) {
                val x = o.x + side * wall
                for ((k, base) in intArrayOf(2, 4, 6).withIndex()) {
                    if (t < k * 0.33) continue
                    val dy = base + (1.4 * sin(dz * 0.7 + k * 2.1 + side)).roundToInt()
                    if (dy in 1 until vaultTop(JUDGES_HALF)) c.block(x, o.y + dy, o.z + dz, VEIN)
                }
                for (dy in 1 until vaultTop(JUDGES_HALF)) {
                    if (Kit.noise(x, o.y + dy, o.z + dz) % 100 < (4 + 22 * t * t).toInt()) c.block(x, o.y + dy, o.z + dz, VEIN)
                }
            }
        }
        // Die Nordwand innen: ein Kranz aus Adern um die Tür.
        for (dx in -4..4) for (dy in 1..6) {
            val r = hypot(dx.toDouble(), dy - 1.0)
            if (r in 3.6..4.4 && (abs(dx) > 2 || dy > 5)) at(c, o, dx, dy, JUDGES_FROM + 1, VEIN)
        }
    }

    // =================================================================================================================
    // Abschnitt 4: Styx-Brücke
    // =================================================================================================================

    /** Zellen des Beckens (|dx| ≤ 8 samt Wehr, dz 55 … 65 samt Rändern): Hier zeichnet [styx], nicht der Ringgraben. */
    fun inBasin(dx: Int, dz: Int): Boolean = abs(dx) <= BASIN_HALF + 1 && dz in STYX_FROM..JUDGES_FROM

    /**
     * Abschnitt 4 (dz 55 … 64): Der Graben weitet sich zu einem gemauerten Becken (15 breit, Wasser 2 unter dem Boden,
     * Wehre aus Kupfergitter zum randvollen Ringgraben). Brücke 5 breit auf zwei Pfeilern, Brüstung 4 hoch mit Gittern
     * (man sieht hinaus), vier Rippenbögen mit leuchtendem Schlussstein; nach oben offen. An jeder Seite eine
     * Fluchttreppe aus dem Becken in den Hof. Zwei Pfosten im Becken tragen die Ketten des Kerberos.
     */
    internal fun styx(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        for (dz in STYX_FROM..JUDGES_FROM) {
            for (dx in -BASIN_HALF - 1..BASIN_HALF + 1) {
                val adx = abs(dx)
                val x = o.x + dx
                val z = o.z + dz
                c.footing(x, z, y - BASIN_FLOOR - 1, pal.PLINTH)
                val edge = adx == BASIN_HALF + 1 || dz == STYX_FROM || dz == JUDGES_FROM
                if (edge) {
                    val r = hypot(dx.toDouble(), dz.toDouble())
                    val weir = adx == BASIN_HALF + 1 && r in 58.5..63.5
                    for (h in y - BASIN_FLOOR..y) {
                        c.block(x, h, z, when {
                            weir && h >= y - 2 -> s(Blocks.WAXED_WEATHERED_COPPER_GRATE)
                            h == y -> CHISELED
                            else -> BRICKS
                        })
                    }
                } else {
                    c.block(x, y - BASIN_FLOOR, z, if (Kit.noise(x, 6, z) % 4 == 0) s(Blocks.SOUL_SOIL) else s(Blocks.BLACKSTONE))
                    for (h in y - BASIN_FLOOR + 1..y - BASIN_WATER) c.block(x, h, z, s(Blocks.WATER))
                    for (h in y - BASIN_WATER + 1..y) c.block(x, h, z, Kit.AIR)
                }
            }
        }
        // Fluchttreppen an der Ost- und Westwand: vom Grund bis zum Rand bei dz 64, von dort in den Hof.
        for (side in intArrayOf(-1, 1)) {
            for (step in 0..5) {
                val dz = STYX_TO - step
                val top = -step
                for (h in -BASIN_FLOOR until top) at(c, o, side * BASIN_HALF, h, dz, BRICKS)
                at(c, o, side * BASIN_HALF, top, dz, Kit.stair(HhaDecoBlocks.TARTARUS_BASALT_BRICK_STAIRS, Direction.SOUTH))
            }
        }
        // Brücke: Deck, zwei Pfeiler bis zum Grund.
        val rail = BRIDGE_HALF + 1
        for (dz in STYX_FROM + 1..STYX_TO) {
            for (dx in -rail..rail) {
                at(c, o, dx, 0, dz, if (abs(dx) == rail) BRICKS else POLISHED)
                at(c, o, dx, -1, dz, if (dz == 58 || dz == 62) BRICKS else Kit.slab(HhaDecoBlocks.TARTARUS_BASALT_BRICK_SLAB, top = true))
                if (dz == 58 || dz == 62) for (h in -BASIN_FLOOR..-2) at(c, o, dx, h, dz, BRICKS)
            }
        }
        // Brüstung (4 hoch) mit Gittern; Rippenbögen über dem Deck.
        val ribs = intArrayOf(57, 59, 61, 63)
        for (dz in STYX_FROM + 1..STYX_TO) {
            val rib = dz in ribs
            for (side in intArrayOf(-1, 1)) {
                val dx = side * rail
                for (dy in 1..PARAPET) {
                    at(c, o, dx, dy, dz, when {
                        rib || dy == 1 -> BRICKS
                        dy == PARAPET -> POLISHED
                        else -> Kit.bars(false)
                    })
                }
            }
            if (rib) {
                for (dx in -rail..rail) for (dy in PARAPET + 1..PARAPET + 4) {
                    val r = hypot(dx.toDouble(), dy - PARAPET.toDouble())
                    if (r in 2.3..3.7) at(c, o, dx, dy, dz, if (dx == 0) VEIN else BRICKS)
                }
            }
        }
        arrows(c, o, STYX_FROM + 1, STYX_TO)
        // Pfosten im Becken für die Ketten des Kerberos.
        for (side in intArrayOf(-1, 1)) {
            for (h in -BASIN_FLOOR + 1..6) at(c, o, side * 6, h, CHAIN_POST_Z, if (h == 6) CHISELED else PILLAR)
            at(c, o, side * 6, 7, CHAIN_POST_Z, BRONZE)
        }
    }

    private const val CHAIN_POST_Z = 58

    // =================================================================================================================
    // Abschnitt 5: Hundetor
    // =================================================================================================================

    /**
     * Abschnitt 5 (dz 47 … 54): die Vorhalle als Kerberos-Fassade. Vorn (dz 55) das Maul als Durchgang, darüber der Sturz
     * mit einer Zinnenreihe aus Zähnen und die drei Köpfe (der mittlere größer), Augen aus grünem Feuer hinter Gittern,
     * Ketten vom Kiefer zu den Pfosten im Becken. Dahinter die Halle (19 breit, 15 hoch) und in der Trommelwand das
     * Siegel; zwei Seelenadern laufen im Boden in das Maul.
     */
    internal fun portico(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        seal(c, o)
        val wall = GATE_HALF + 2
        // Rückwand: was zwischen Trommel und Halle offen wäre, wird Mauer (nicht im Saal, nicht in der Siegelöffnung).
        for (dz in GATE_FROM + 1..GATE_FROM + 3) {
            for (dx in -wall..wall) {
                if (hypot(dx.toDouble(), dz.toDouble()) <= HALL) continue
                for (dy in 1..GATE_HEIGHT + 1) {
                    if (abs(dx) <= SEAL_HALF_WIDTH && inSealOpening(dx, dy)) continue
                    at(c, o, dx, dy, dz, pal.FACE(o.x + dx, y + dy, o.z + dz))
                }
            }
        }
        // Rahmen des Siegels an der Rückwand (wie bisher, eine Lage weiter innen).
        for (dx in -SEAL_HALF_WIDTH - 2..SEAL_HALF_WIDTH + 2) {
            for (dy in 1..SEAL_HEIGHT + 3) {
                if (inSealOpening(dx, dy)) continue
                val near = dy <= 10 || hypot(dx.toDouble(), dy - 10.0) <= SEAL_HALF_WIDTH + 2.4
                if (near) at(c, o, dx, dy, GATE_FROM + 3, if ((dx + dy) and 1 == 0) GILDED else s(Blocks.CHISELED_POLISHED_BLACKSTONE))
            }
        }
        // Seitenwände und Decke der Halle.
        for (dz in GATE_FROM + 4..GATE_FACE) {
            for (dx in -wall..wall) {
                val adx = abs(dx)
                at(c, o, dx, 0, dz, if (adx <= GATE_HALF) POLISHED else pal.PLINTH(o.x + dx, y, o.z + dz))
                for (dy in 1..GATE_HEIGHT + 1) {
                    at(c, o, dx, dy, dz, if (adx > GATE_HALF || dy > GATE_HEIGHT) (if (adx == wall) pal.FACE(o.x + dx, y + dy, o.z + dz) else INNER(o.x + dx, y + dy, o.z + dz)) else Kit.AIR)
                }
            }
        }
        // Die Fassade: Ebene dz 55, 23 breit, 20 hoch, mit Sockel, Bändern und Gesims.
        val top = 20
        for (dx in -wall..wall) {
            for (dy in 1..top) {
                val state = when {
                    dy == 1 -> pal.PLINTH(o.x + dx, y + dy, o.z + GATE_FACE)
                    dy == top || dy == 16 -> CHISELED
                    abs(dx) == wall || abs(dx) == wall - 4 -> PILLAR
                    dy == 2 -> POLISHED
                    else -> FACADE(o.x + dx, y + dy, o.z + GATE_FACE)
                }
                at(c, o, dx, dy, GATE_FACE, state)
            }
            at(c, o, dx, top + 1, GATE_FACE, Kit.slab(Blocks.POLISHED_BLACKSTONE_BRICK_SLAB))
        }
        // Das Maul: unten so breit wie die Brücke, darüber aufgerissen, oben der Sturz.
        for (dx in -6..6) for (dy in 1..11) if (maw(dx, dy)) at(c, o, dx, dy, GATE_FACE, Kit.AIR)
        // Lippe: gemeißelter Rand um das Maul.
        for (dx in -7..7) for (dy in 1..12) {
            if (maw(dx, dy)) continue
            if (maw(dx - 1, dy) || maw(dx + 1, dy) || maw(dx, dy - 1) || maw(dx, dy + 1)) at(c, o, dx, dy, GATE_FACE, CHISELED)
        }
        for (dx in -7..7) at(c, o, dx, 12, GATE_FACE + 1, CHISELED)
        // Zähne als Zinnenreihe über dem Sturz, Reißzähne hängen in das Maul.
        for (dx in -6..6 step 2) at(c, o, dx, 13, GATE_FACE + 1, BONE)
        for (dx in intArrayOf(-5, -3, 3, 5)) at(c, o, dx, maxOf(5, mawTop(dx)), GATE_FACE, BONE)
        // Die drei Köpfe.
        houndHead(c, o, 0, 20, GATE_FACE + 1, 4.5, 4.5, 3.6, big = true)
        for (side in intArrayOf(-1, 1)) houndHead(c, o, side * 8, 17, GATE_FACE + 1, 2.8, 3.0, 2.6, big = false)
        // Halle innen nach den Köpfen noch einmal freiräumen, Boden mit den zwei Adern in das Maul.
        for (dz in GATE_FROM + 4..GATE_FACE - 1) for (dx in -GATE_HALF..GATE_HALF) for (dy in 1..GATE_HEIGHT) at(c, o, dx, dy, dz, Kit.AIR)
        for (dz in GATE_FROM + 1..STYX_FROM + 3) for (side in intArrayOf(-1, 1)) at(c, o, side * (if (dz <= GATE_FROM + 4) 1 else 2), 0, dz, VEIN)
        // Ketten vom Unterkiefer zu den Pfosten im Becken.
        for (side in intArrayOf(-1, 1)) c.heavyChain(o.x + side * 2, y + 14, o.z + 60, o.x + side * 6, y + 8, o.z + CHAIN_POST_Z, 1.0, Kit.chain(), Kit.chain())
        // Licht in der Halle: zwei Feuerschalen am Siegel.
        for (side in intArrayOf(-1, 1)) c.brazier(o.x + side * 7, y + 1, o.z + GATE_FROM + 5, 2)
    }

    /**
     * Öffnung des Mauls in der Fassade: unten nur so breit wie die Brücke (|dx| ≤ 2), darüber aufgerissen bis |dx| 6, aber
     * erst oberhalb der Brüstungshöhe, damit niemand vom Becken oder von der Grabeninsel hineinspringen kann.
     */
    fun maw(dx: Int, dy: Int): Boolean = if (abs(dx) <= BRIDGE_HALF) dy in 1..mawTop(dx) else dy in PARAPET + 1..mawTop(dx)

    /** Oberkante des Mauls über [dx]: in der Mitte 11, zu den Mundwinkeln flacher. */
    private fun mawTop(dx: Int): Int = when (abs(dx)) {
        0, 1 -> 11
        2 -> 10
        3, 4 -> 9
        5 -> 8
        6 -> 7
        else -> 0
    }

    /** Siegel im Tor der Trommel (Maße unverändert): Öffnung freiräumen, außerhalb des Saalzylinders. */
    private fun seal(c: BlockCanvas, o: BlockPos) {
        val y = o.y
        for (dx in -SEAL_HALF_WIDTH..SEAL_HALF_WIDTH) {
            for (dy in 1..SEAL_HEIGHT) {
                if (!inSealOpening(dx, dy)) continue
                for (dz in HALL - 1..GATE_FROM + 4) if (hypot(dx.toDouble(), dz.toDouble()) > HALL) c.block(o.x + dx, y + dy, o.z + dz, Kit.AIR)
                c.block(o.x + dx, y + dy, o.z + SEAL_Z, HhaBlocks.HADES_SEAL.defaultState)
            }
        }
    }

    /**
     * Ein Hundekopf an der Fassade: Schädel, Schnauze, Ohren, Nase; Augen aus grünem Feuer hinter Gittern. Mitte
     * ([hx], [hy], [hz]) relativ zu o.
     */
    private fun houndHead(c: BlockCanvas, o: BlockPos, hx: Int, hy: Int, hz: Int, rx: Double, ry: Double, rz: Double, big: Boolean) {
        val y = o.y
        val x = o.x + hx
        val z = o.z + hz
        c.blob(x, y + hy, z, rx, ry, rz, HOUND)
        val snoutZ = z + (rz * 0.8).roundToInt()
        val snoutY = y + hy - (ry * 0.85).roundToInt()
        c.blob(x, snoutY, snoutZ, rx * 0.58, ry * 0.48, rz * 0.78, HOUND)
        // Nase.
        val noseZ = snoutZ + (rz * 0.78).toInt()
        c.block(x, snoutY + 1, noseZ, s(Blocks.POLISHED_BLACKSTONE))
        c.block(x, snoutY, noseZ, s(Blocks.POLISHED_BLACKSTONE))
        // Ohren.
        for (side in intArrayOf(-1, 1)) {
            val ex = x + side * (rx * 0.7).roundToInt()
            val tip = y + hy + (ry * 1.6).roundToInt()
            c.limb(ex, y + hy + (ry * 0.6).roundToInt(), z - 1, ex + side, tip, z - 2, if (big) 1.3 else 0.9, 0.4, HOUND)
            c.block(ex + side, tip + 1, z - 2, GILDED)
        }
        // Halsband aus Bronze mit Dornen unter den Seitenköpfen (beim mittleren sitzt dort das Gebiss).
        if (!big) {
            val collar = y + hy - ry.roundToInt() - 1
            for (dx in -2..2) {
                if (abs(hx + dx) < 7) continue
                c.block(x + dx, collar, z, BRONZE)
                if (dx % 2 == 0) c.block(x + dx, collar, z + 1, Kit.stair(HhaDecoBlocks.TARTARUS_BRONZE_STAIRS, Direction.NORTH, top = true))
            }
        }
        // Augen: Gitter an der Oberfläche, dahinter grünes Feuer auf Gold.
        for (side in intArrayOf(-1, 1)) {
            for (ox in if (big) intArrayOf(2, 3) else intArrayOf(1)) {
                val ez = z + eyeDepth(ox / rx, rz, ry)
                val bx = x + side * ox
                c.block(bx, y + hy + 1, ez, Kit.bars(true))
                c.block(bx, y + hy + 1, ez - 1, Kit.FIRE)
                c.block(bx, y + hy, ez - 1, GILDED)
            }
        }
    }

    /** Wie weit vor der Kopfmitte die Oberfläche in Augenhöhe (eine Lage über der Mitte) liegt, so wie [blob] füllt. */
    private fun eyeDepth(nx: Double, rz: Double, ry: Double): Int {
        for (k in rz.toInt() + 1 downTo 0) {
            val rest = 1.0 - nx * nx - (k / rz) * (k / rz)
            if (rest >= 0.0 && (ry * sqrt(rest)).toInt() >= 1) return k
        }
        return 0
    }
}
