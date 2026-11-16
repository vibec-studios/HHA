package dev.henny.hha.logic.world

import dev.henny.hha.Hha
import dev.henny.hha.HhaBlocks
import dev.henny.hha.HhaSounds
import dev.henny.hha.block.HadesPortalBlock
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.world.olymp.OlympusLayout
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.entity.Entity
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.Heightmap
import net.minecraft.world.TeleportTarget
import net.minecraft.world.World
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Das Höllentor (PLAN 7.4) und der Weg in den Tartaros.
 *
 * - **Neu:** Das Tor steht fest am Dreiweg des Olymp ([OlympusLayout.HELL_GATE], Ebene spannt z) und ist zu, bis
 *   Hekate besiegt ist. [openOlympusGate] (Aufruf nach dem Sieg) bzw. das Nachholen in [tickOlympus] füllen die runde
 *   Öffnung Reihe für Reihe ([openAt]).
 * - **Ziel:** die Ankunftsplattform im Tartaros an denselben x/z ([buildArrival]); das Rücktor dort wird in
 *   [PortalLinks] mit dem Herkunftstor verknüpft. Rücktore ohne Eintrag führen zum Dreiweg (Hekate besiegt) bzw. wie
 *   früher in die Hades-Halle der Oberwelt.
 * - **Altlast:** Die feste Halle unter dem Hades-Biom (Oberwelt) entsteht nicht mehr; schon offene Tore alter Welten
 *   führen weiter in den Tartaros ([openRow], [isOpen], [centerFromAltar] bleiben dafür).
 */
object HadesGate {

    val TARTAROS: RegistryKey<World> = RegistryKey.of(RegistryKeys.WORLD, Hha.id("tartaros"))

    /** Oberkante des Hallenbodens (Altlast Oberwelt-Halle). */
    const val FLOOR_Y = -32
    const val ALTAR_OFFSET_Z = 20
    const val GATE_OFFSET_Z = -29
    const val GATE_HALF_WIDTH = 4
    const val GATE_HEIGHT = 9

    /** Das Tor ist rund: Öffnung um die Mitte, [h] zählt vom Fußpunkt aufwärts. */
    private const val OPENING_RADIUS = 4.6
    private const val OPENING_CENTER = 5.5

    /** So viele Reihen hat die runde Öffnung höchstens (h = 1 … 10, siehe [inOpening]). */
    const val OPENING_ROWS = 10

    /** Torfarbe des Übergangs (PLAN 7.3). */
    const val GREEN = 0x0A4018

    /** Abstand des Ankunftspunkts vor dem Tor am Dreiweg bzw. vor einem Herkunftstor (hinter der Schwelle). */
    private const val FRONT_DISTANCE = 4.0

    /** Die Ankunftsplattform steht immer über den Flüssen. */
    private const val MIN_ARRIVAL_Y = 58
    private const val MAX_CAUSEWAY = 90

    /** Nachholen am Dreiweg: so nah muss ein Spieler kommen, so oft wird geprüft. */
    private const val CATCH_UP_DISTANCE = 64.0
    private const val CATCH_UP_INTERVAL = 40

    /** Ticks zwischen zwei Reihen beim Öffnen. */
    private const val ROW_TICKS = 3

    fun inOpening(dx: Int, h: Int): Boolean = hypot(dx.toDouble(), h - OPENING_CENTER) <= OPENING_RADIUS && h >= 1

    fun centerFromAltar(altar: BlockPos): BlockPos = BlockPos(altar.x, FLOOR_Y, altar.z - ALTAR_OFFSET_Z)

    /** Altlast: Ist das Tor der Oberwelt-Halle mit der Mitte [center] offen? */
    fun isOpen(world: ServerWorld, center: BlockPos): Boolean =
        world.getBlockState(center.add(0, OPENING_CENTER.toInt(), GATE_OFFSET_Z)).isOf(HhaBlocks.HADES_PORTAL)

    /** Ist das Tor mit dem Fußpunkt [gateCenter] offen (Mitte der Öffnung ist Tor)? */
    fun isOpenAt(world: World, gateCenter: BlockPos): Boolean =
        world.getBlockState(gateCenter.up(OPENING_CENTER.toInt())).isOf(HhaBlocks.HADES_PORTAL)

    /**
     * Altlast Oberwelt-Halle: füllt die Reihe [row] (0 … [GATE_HEIGHT] − 1) des Tors bei `center + (dx, h, GATE_OFFSET_Z)`;
     * was nicht zur runden Öffnung gehört, wird zu Schwarzsteinziegeln (ältere Hallen bekommen so die runde Form).
     */
    fun openRow(world: ServerWorld, center: BlockPos, row: Int) {
        val h = 1 + row
        for (dx in -GATE_HALF_WIDTH..GATE_HALF_WIDTH) {
            val block = if (inOpening(dx, h)) HhaBlocks.HADES_PORTAL else Blocks.POLISHED_BLACKSTONE_BRICKS
            world.setBlockState(center.add(dx, h, GATE_OFFSET_Z), block.defaultState, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
        }
    }

    /**
     * Füllt die Reihe [row] (0 … [OPENING_ROWS] − 1) der runden Öffnung um den Fußpunkt [gateCenter] mit Torblöcken der
     * Achse [axis] (`gateCenter + d · entlang + (0, h, 0)` mit [inOpening]). Der Rahmen bleibt, wie er ist.
     */
    fun openRowAt(world: ServerWorld, gateCenter: BlockPos, axis: Direction.Axis, row: Int) {
        val h = 1 + row
        val along = OlympusGate.alongOf(axis)
        val state = HhaBlocks.HADES_PORTAL.defaultState.with(HadesPortalBlock.AXIS, axis)
        for (d in -GATE_HALF_WIDTH - 1..GATE_HALF_WIDTH + 1) {
            if (!inOpening(d, h)) continue
            world.setBlockState(gateCenter.offset(along, d).up(h), state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Das Tor am Dreiweg
    // -------------------------------------------------------------------------------------------------

    private class Opening(val world: RegistryKey<World>, val center: BlockPos, val axis: Direction.Axis) {
        var row = 0
        var wait = 0
    }

    private val openings = ArrayList<Opening>()

    /** Öffnet das Tor mit Fußpunkt [gateCenter] Reihe für Reihe (von unten nach oben). */
    fun openAt(world: ServerWorld, gateCenter: BlockPos, axis: Direction.Axis) {
        if (openings.any { it.world == world.registryKey && it.center == gateCenter }) return
        openings.add(Opening(world.registryKey, gateCenter.toImmutable(), axis))
        world.playSound(null, gateCenter.up(OPENING_CENTER.toInt()), HhaSounds.RIFT, SoundCategory.BLOCKS, 1.0f, 0.7f)
    }

    /** Nach Hekates Sieg (P07): öffnet das Höllentor am Dreiweg. [world] = irgendeine Welt des Servers. */
    fun openOlympusGate(world: ServerWorld) {
        val olympus = world.server?.getWorld(OlympusLayout.WORLD) ?: return
        if (isOpenAt(olympus, OlympusLayout.HELL_GATE)) return
        openAt(olympus, OlympusLayout.HELL_GATE, OlympusLayout.HELL_GATE_AXIS)
    }

    /** Server-Tick (über [OlympusGate]): laufende Öffnungen weiterführen und das Tor am Dreiweg nachholen. */
    fun tickOlympus(server: MinecraftServer) {
        if (openings.isNotEmpty()) {
            val iterator = openings.iterator()
            while (iterator.hasNext()) {
                val opening = iterator.next()
                val world = server.getWorld(opening.world)
                if (world == null) {
                    iterator.remove()
                    continue
                }
                if (opening.wait-- > 0) continue
                openRowAt(world, opening.center, opening.axis, opening.row)
                opening.row++
                opening.wait = ROW_TICKS
                if (opening.row >= OPENING_ROWS) iterator.remove()
            }
        }
        if (server.ticks % CATCH_UP_INTERVAL != 0) return
        val olympus = server.getWorld(OlympusLayout.WORLD) ?: return
        val gate = OlympusLayout.HELL_GATE
        val near = olympus.players.any { hypot(it.x - (gate.x + 0.5), it.z - (gate.z + 0.5)) < CATCH_UP_DISTANCE }
        if (!near) return
        if (!OlympusProgress.isDone(server, OlympusProgress.HEKATE)) return
        if (isOpenAt(olympus, gate)) return
        openAt(olympus, gate, OlympusLayout.HELL_GATE_AXIS)
    }

    // -------------------------------------------------------------------------------------------------
    // Der Übergang
    // -------------------------------------------------------------------------------------------------

    /** Vom Block bei jeder Berührung: Bild färbt sich grün (PLAN 7.3), kein Schütteln. */
    fun touching(player: ServerPlayerEntity, pos: BlockPos) {
        GateFade.touching(player, pos, GREEN, HhaSounds.PORTAL_ENTER)
    }

    // -------------------------------------------------------------------------------------------------
    // Reise
    // -------------------------------------------------------------------------------------------------

    private fun axisAt(world: World, pos: BlockPos): Direction.Axis {
        val state = world.getBlockState(pos)
        return if (state.contains(HadesPortalBlock.AXIS)) state.get(HadesPortalBlock.AXIS) else Direction.Axis.X
    }

    /** Mitte der Torreihe durch [pos] entlang der Ebene (x bei Achse X, z bei Achse Z). */
    private fun rowCenter(world: World, pos: BlockPos, axis: Direction.Axis): BlockPos {
        val along = OlympusGate.alongOf(axis)
        var low = 0
        var high = 0
        while (low > -12 && world.getBlockState(pos.offset(along, low - 1)).isOf(HhaBlocks.HADES_PORTAL)) low--
        while (high < 12 && world.getBlockState(pos.offset(along, high + 1)).isOf(HhaBlocks.HADES_PORTAL)) high++
        return pos.offset(along, (low + high) / 2)
    }

    /** Unterste Torzelle unter [pos] in derselben Spalte. */
    private fun bottomOf(world: World, pos: BlockPos): BlockPos {
        var bottom = pos
        var steps = 0
        while (steps++ < 16 && world.getBlockState(bottom.down()).isOf(HhaBlocks.HADES_PORTAL)) bottom = bottom.down()
        return bottom
    }

    fun target(world: ServerWorld, entity: Entity, pos: BlockPos): TeleportTarget? {
        val server = world.server ?: return null
        val after = TeleportTarget.PostDimensionTransition { arrived ->
            TeleportTarget.ADD_PORTAL_CHUNK_TICKET.onTransition(arrived)
            if (arrived is ServerPlayerEntity) arrive(arrived)
        }
        if (world.registryKey == TARTAROS) return returnTarget(world, entity, pos, after)
        val tartaros = server.getWorld(TARTAROS) ?: return null
        val axis = axisAt(world, pos)
        val center = rowCenter(world, pos, axis)
        val floor = buildArrival(tartaros, center.x, center.z)
        // Das Rücktor dort merkt sich, woher man kam (Zelle am Fuß der Mittelspalte, Seite gegen die Blickrichtung).
        val origin = PortalLinks.Gate.of(world, bottomOf(world, center), axis, OlympusGate.cameFrom(entity, axis))
        PortalLinks.of(server).setOrigin(center.x, center.z, origin)
        return TeleportTarget(tartaros, Vec3d(center.x + 0.5, floor + 1.0, center.z + 8.5), Vec3d.ZERO, 180f, 0f, after)
    }

    /** Vom Rücktor im Tartaros zurück: Herkunft laut [PortalLinks], sonst Dreiweg bzw. Oberwelt-Halle. */
    private fun returnTarget(world: ServerWorld, entity: Entity, pos: BlockPos, after: TeleportTarget.PostDimensionTransition): TeleportTarget? {
        val server = world.server ?: return null
        val key = rowCenter(world, pos, Direction.Axis.X)
        val origin = PortalLinks.of(server).origin(key.x, key.z)
        if (origin != null) {
            val home = server.getWorld(origin.worldKey)
            if (home != null) {
                home.getChunk(origin.pos.x shr 4, origin.pos.z shr 4)
                val state = home.getBlockState(origin.pos)
                val axis = if (state.contains(HadesPortalBlock.AXIS)) state.get(HadesPortalBlock.AXIS) else origin.axis
                val bottom = if (state.isOf(HhaBlocks.HADES_PORTAL)) bottomOf(home, origin.pos) else origin.pos
                val (spot, yaw) = OlympusGate.standInFront(home, Vec3d.ofBottomCenter(bottom), axis, origin.side, FRONT_DISTANCE)
                return TeleportTarget(home, spot, Vec3d.ZERO, yaw, 0f, after)
            }
        }
        val olympus = server.getWorld(OlympusLayout.WORLD)
        if (olympus != null && OlympusProgress.isDone(server, OlympusProgress.HEKATE)) {
            val gate = OlympusLayout.HELL_GATE
            olympus.getChunk(gate.x shr 4, gate.z shr 4)
            // Der Weg zum Tor kommt von Osten (+x): dort ist die Terrasse.
            val bottom = Vec3d(gate.x + 0.5, gate.y + 1.0, gate.z + 0.5)
            val (spot, yaw) = OlympusGate.standInFront(olympus, bottom, OlympusLayout.HELL_GATE_AXIS, 1, FRONT_DISTANCE)
            return TeleportTarget(olympus, spot, Vec3d.ZERO, yaw, 0f, after)
        }
        val home = server.getWorld(World.OVERWORLD) ?: return null
        val spot = Vec3d(pos.x + 0.5, FLOOR_Y + 1.0, pos.z + 6.5)
        return TeleportTarget(home, spot, Vec3d.ZERO, 0f, 0f, after)
    }

    private fun arrive(player: ServerPlayerEntity) {
        GateFade.arrived(player, GREEN)
        val players = listOf(player)
        val world = player.entityWorld as? ServerWorld ?: return
        world.playSound(null, player.blockPos, HhaSounds.IMPACT, SoundCategory.BLOCKS, 0.8f, 0.6f)
        if (world.registryKey == TARTAROS) {
            Cine.title(players, "hha.tartaros.title", "hha.tartaros.title.sub", 70)
            TartarosGuide.onArrive(player)
        }
    }

    /**
     * Ankunftsplattform im Tartaros samt Rücktor (Ebene spannt x, Mitte [cx], bei z = [gateZ]); liefert die Bodenhöhe.
     * Einmal gebaut bleibt sie stehen. Licht nur aus den vier Feuerschalen, Rand aus vergoldetem Schwarzstein.
     */
    /** Höhe der untersten Torzelle in der Mittelspalte über dem Boden (passt zu [buildArrival]). */
    private fun firstOpening(): Int = (0..GATE_HEIGHT + 1).firstOrNull { inOpening(0, it) } ?: 1

    private fun buildArrival(world: ServerWorld, cx: Int, gateZ: Int): Int {
        // Ein schon gebautes Tor erkennt man an seinem Scheitelstein.
        for (y in world.topYInclusive downTo world.bottomY) {
            if (world.getBlockState(BlockPos(cx, y, gateZ)).isOf(HhaBlocks.HADES_PORTAL)) {
                var lowest = y
                while (lowest - 1 > world.bottomY && world.getBlockState(BlockPos(cx, lowest - 1, gateZ)).isOf(HhaBlocks.HADES_PORTAL)) lowest--
                // Die unterste Torzelle der Mittelspalte liegt [firstOpening] Blöcke über dem Boden (Review R1 M4:
                // vorher landete man beim zweiten Besuch einen Block tief im Boden).
                return lowest - firstOpening()
            }
        }
        val centerZ = gateZ + 8
        // Holt den Chunk samt Gelände, sonst läge die Plattform auf einer leeren Höhenkarte.
        world.getChunk(cx shr 4, centerZ shr 4)
        val floor = maxOf(world.getTopY(Heightmap.Type.MOTION_BLOCKING, cx, centerZ) + 1, MIN_ARRIVAL_Y)
        fun put(pos: BlockPos, state: BlockState) = world.setBlockState(pos, state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
        val radius = 12
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                val r = hypot(dx.toDouble(), dz.toDouble())
                if (r > radius + 0.5) continue
                val column = BlockPos(cx + dx, floor, centerZ + dz)
                for (dy in 1..14) put(column.up(dy), Blocks.AIR.defaultState)
                val state = when {
                    r > radius - 1.5 -> Blocks.GILDED_BLACKSTONE
                    r in 5.0..6.0 -> Blocks.CHISELED_POLISHED_BLACKSTONE
                    (abs(dx) + abs(dz)) % 2 == 0 -> Blocks.POLISHED_BLACKSTONE_BRICKS
                    else -> Blocks.BLACKSTONE
                }
                put(column, state.defaultState)
                // Der Sockel läuft spitz nach unten aus.
                for (dy in 1..((radius - r) * 0.7).toInt() + 2) put(column.down(dy), Blocks.BLACKSTONE.defaultState)
            }
        }
        // Hängt die Plattform über einer Schlucht oder einem Fluss, führen Dammwege zum festen Boden.
        for ((dirX, dirZ) in listOf(1 to 0, -1 to 0, 0 to 1)) {
            var step = radius
            while (step < MAX_CAUSEWAY) {
                val x = cx + dirX * step
                val z = centerZ + dirZ * step
                world.getChunk(x shr 4, z shr 4)
                if (world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) >= floor - 1 && world.getBlockState(BlockPos(x, world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1, z)).fluidState.isEmpty) break
                step++
            }
            if (step >= MAX_CAUSEWAY) continue
            for (n in radius..step) {
                for (w in -2..2) {
                    val x = cx + dirX * n + dirZ * w
                    val z = centerZ + dirZ * n + dirX * w
                    put(BlockPos(x, floor, z), (if (abs(w) == 2) Blocks.POLISHED_BLACKSTONE_BRICKS else Blocks.BLACKSTONE).defaultState)
                    if (abs(w) == 2) put(BlockPos(x, floor + 1, z), Blocks.POLISHED_BLACKSTONE_WALL.defaultState)
                    for (d in 1..3) put(BlockPos(x, floor - d, z), Blocks.BLACKSTONE.defaultState)
                }
            }
        }
        // Rücktor: dieselbe runde Form wie oben am Dreiweg.
        val half = GATE_HALF_WIDTH + 1
        for (dx in -half..half) {
            for (h in 0..GATE_HEIGHT + 1) {
                val pos = BlockPos(cx + dx, floor + h, gateZ)
                put(pos, if (inOpening(dx, h)) HhaBlocks.HADES_PORTAL.defaultState else Blocks.POLISHED_BLACKSTONE_BRICKS.defaultState)
            }
        }
        // Dunkle Pfeiler mit Goldkappe rahmen das Tor; Licht geben nur die vier Feuerschalen.
        for ((dx, height) in listOf(-7 to 8, 7 to 8, -9 to 5, 9 to 5)) {
            for (h in 1 until height) put(BlockPos(cx + dx, floor + h, gateZ + 1), Blocks.POLISHED_BLACKSTONE_BRICKS.defaultState)
            put(BlockPos(cx + dx, floor + height, gateZ + 1), Blocks.GILDED_BLACKSTONE.defaultState)
        }
        for ((dx, dz) in listOf(-8 to -3, 8 to -3, -8 to 6, 8 to 6)) {
            val bowl = BlockPos(cx + dx, floor + 1, centerZ + dz)
            put(bowl, Blocks.POLISHED_BLACKSTONE_BRICKS.defaultState)
            put(bowl.up(), HhaBlocks.GREEN_FIRE.defaultState)
        }
        return floor
    }
}
