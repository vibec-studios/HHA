package dev.henny.hha.logic.world

import dev.henny.hha.Hha
import dev.henny.hha.HhaBlocks
import dev.henny.hha.HhaSounds
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
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
import net.minecraft.util.math.Vec3d
import net.minecraft.world.Heightmap
import net.minecraft.world.TeleportTarget
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Das schwarze Tor in der Halle unter dem Hades-Biom und der Weg in den Tartaros.
 *
 * Eine Halle liegt immer auf derselben Höhe. Deshalb braucht das Tor keinen Speicher: Die Ankunft im Tartaros hat
 * dieselben x/z-Koordinaten wie das Tor, und der Rückweg rechnet einfach zurück.
 */
object HadesGate {

    val TARTAROS: RegistryKey<World> = RegistryKey.of(RegistryKeys.WORLD, Hha.id("tartaros"))

    /** Oberkante des Hallenbodens. */
    const val FLOOR_Y = -32
    const val ALTAR_OFFSET_Z = 20
    const val GATE_OFFSET_Z = -29
    const val GATE_HALF_WIDTH = 4
    const val GATE_HEIGHT = 9

    /** Das Tor ist rund: Öffnung um die Mitte der Wand, [h] zählt vom Hallenboden aufwärts. */
    private const val OPENING_RADIUS = 4.6
    private const val OPENING_CENTER = 5.5

    /** So lange braucht das Tor, bis es einen Spieler mitnimmt. Muss zu getPortalDelay im Block passen. */
    private const val PULL_TICKS = 30
    private const val GREEN = 0x0A4018

    fun inOpening(dx: Int, h: Int): Boolean = hypot(dx.toDouble(), h - OPENING_CENTER) <= OPENING_RADIUS && h >= 1

    fun centerFromAltar(altar: BlockPos): BlockPos = BlockPos(altar.x, FLOOR_Y, altar.z - ALTAR_OFFSET_Z)

    fun isOpen(world: ServerWorld, center: BlockPos): Boolean =
        world.getBlockState(center.add(0, OPENING_CENTER.toInt(), GATE_OFFSET_Z)).isOf(HhaBlocks.HADES_PORTAL)

    /**
     * Füllt das Tor von unten nach oben; [row] läuft von 0 bis [GATE_HEIGHT] - 1. Was nicht zur runden Öffnung gehört,
     * wird zu Torstein, so bekommen auch Hallen aus älteren Spielständen die runde Form.
     */
    fun openRow(world: ServerWorld, center: BlockPos, row: Int) {
        val h = 1 + row
        for (dx in -GATE_HALF_WIDTH..GATE_HALF_WIDTH) {
            val block = if (inOpening(dx, h)) HhaBlocks.HADES_PORTAL else HhaBlocks.GATE_STONE
            world.setBlockState(center.add(dx, h, GATE_OFFSET_Z), block.defaultState, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Der Übergang: Das Bild färbt sich grün, solange man im Tor steht, und klart auf der anderen Seite auf.
    // -------------------------------------------------------------------------------------------------

    private class Touch(var lastTick: Long, val startTick: Long)

    private val touching = HashMap<UUID, Touch>()

    fun touching(player: ServerPlayerEntity, pos: BlockPos) {
        val world = player.entityWorld as? ServerWorld ?: return
        val now = world.time
        val touch = touching[player.uuid]
        if (touch != null) {
            touch.lastTick = now
            return
        }
        touching[player.uuid] = Touch(now, now)
        val players = listOf(player)
        Cine.fade(players, GREEN, 0.0, 0.92, PULL_TICKS)
        world.playSound(null, pos, HhaSounds.PORTAL_ENTER, SoundCategory.BLOCKS, 1.6f, 1f)
        Vfx.shake(world, player.entityPos, 1.6f, PULL_TICKS, 6f)
    }

    /** Wer vor Ablauf wieder heraustritt, bekommt die freie Sicht zurück. */
    fun tick(server: MinecraftServer) {
        if (touching.isEmpty()) return
        val iterator = touching.entries.iterator()
        while (iterator.hasNext()) {
            val (id, touch) = iterator.next()
            val player = server.playerManager.getPlayer(id)
            if (player == null) {
                iterator.remove()
                continue
            }
            val world = player.entityWorld as? ServerWorld ?: continue
            if (world.time - touch.lastTick > 4) {
                val elapsed = ((world.time - touch.startTick).toDouble() / PULL_TICKS).coerceIn(0.0, 0.92)
                Cine.fade(listOf(player), GREEN, elapsed, 0.0, 14)
                iterator.remove()
            }
        }
    }

    /** Sucht zu einem Portalblock die Mitte der Reihe. */
    private fun rowCenterX(world: ServerWorld, pos: BlockPos): Int {
        var west = pos.x
        var east = pos.x
        while (pos.x - west < 12 && world.getBlockState(BlockPos(west - 1, pos.y, pos.z)).isOf(HhaBlocks.HADES_PORTAL)) west--
        while (east - pos.x < 12 && world.getBlockState(BlockPos(east + 1, pos.y, pos.z)).isOf(HhaBlocks.HADES_PORTAL)) east++
        return (west + east) / 2
    }

    fun target(world: ServerWorld, entity: Entity, pos: BlockPos): TeleportTarget? {
        val server = world.server ?: return null
        val after = TeleportTarget.PostDimensionTransition { arrived ->
            TeleportTarget.ADD_PORTAL_CHUNK_TICKET.onTransition(arrived)
            if (arrived is ServerPlayerEntity) arrive(arrived)
        }
        if (world.registryKey == TARTAROS) {
            val home = server.getWorld(World.OVERWORLD) ?: return null
            val spot = Vec3d(pos.x + 0.5, FLOOR_Y + 1.0, pos.z + 6.5)
            return TeleportTarget(home, spot, Vec3d.ZERO, 0f, 0f, after)
        }
        val tartaros = server.getWorld(TARTAROS) ?: return null
        val cx = rowCenterX(world, pos)
        val floor = buildArrival(tartaros, cx, pos.z)
        return TeleportTarget(tartaros, Vec3d(cx + 0.5, floor + 1.0, pos.z + 8.5), Vec3d.ZERO, 180f, 0f, after)
    }

    private fun arrive(player: ServerPlayerEntity) {
        touching.remove(player.uuid)
        val players = listOf(player)
        Cine.fade(players, GREEN, 1.0, 0.0, 50)
        val world = player.entityWorld as? ServerWorld ?: return
        world.playSound(null, player.blockPos, HhaSounds.IMPACT, SoundCategory.BLOCKS, 1.4f, 0.6f)
        Vfx.shake(world, player.entityPos, 2.5f, 18, 8f)
        if (world.registryKey == TARTAROS) {
            Cine.title(players, "hha.tartaros.title", "hha.tartaros.title.sub", 70)
            TartarosGuide.greet(player)
        }
    }

    /** Ankunftsplattform im Tartaros samt Rücktor; liefert die Bodenhöhe. Einmal gebaut bleibt sie stehen. */
    private fun buildArrival(world: ServerWorld, cx: Int, gateZ: Int): Int {
        // Ein schon gebautes Tor erkennt man an seinem Scheitelstein.
        for (y in world.topYInclusive downTo world.bottomY) {
            if (world.getBlockState(BlockPos(cx, y, gateZ)).isOf(HhaBlocks.HADES_PORTAL)) {
                var floor = y
                while (floor > world.bottomY && world.getBlockState(BlockPos(cx, floor, gateZ)).isOf(HhaBlocks.HADES_PORTAL)) floor--
                return floor - 1
            }
        }
        val centerZ = gateZ + 8
        // Holt den Chunk samt Gelände, sonst läge die Plattform auf einer leeren Höhenkarte.
        world.getChunk(cx shr 4, centerZ shr 4)
        val floor = world.getTopY(Heightmap.Type.MOTION_BLOCKING, cx, centerZ) + 1
        fun put(pos: BlockPos, state: BlockState) = world.setBlockState(pos, state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
        val radius = 12
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                val r = hypot(dx.toDouble(), dz.toDouble())
                if (r > radius + 0.5) continue
                val column = BlockPos(cx + dx, floor, centerZ + dz)
                for (dy in 1..14) put(column.up(dy), Blocks.AIR.defaultState)
                val state = when {
                    r > radius - 1.5 -> HhaBlocks.PALE_CRYSTAL
                    r in 5.0..6.0 -> Blocks.VERDANT_FROGLIGHT
                    (abs(dx) + abs(dz)) % 2 == 0 -> HhaBlocks.GATE_STONE
                    else -> HhaBlocks.DEEP_ASH
                }
                put(column, state.defaultState)
                // Der Sockel läuft spitz nach unten aus.
                for (dy in 1..((radius - r) * 0.7).toInt() + 2) put(column.down(dy), HhaBlocks.DEEP_ASH.defaultState)
            }
        }
        // Rücktor: dieselbe runde Form wie unten in der Halle.
        val half = GATE_HALF_WIDTH + 1
        for (dx in -half..half) {
            for (h in 0..GATE_HEIGHT + 1) {
                val pos = BlockPos(cx + dx, floor + h, gateZ)
                put(pos, if (inOpening(dx, h)) HhaBlocks.HADES_PORTAL.defaultState else HhaBlocks.GATE_STONE.defaultState)
            }
        }
        // Kristallzacken, die das Tor umrahmen, und vier Feuerschalen.
        for ((dx, height) in listOf(-7 to 8, 7 to 8, -9 to 5, 9 to 5)) {
            for (h in 1..height) put(BlockPos(cx + dx, floor + h, gateZ + 1), HhaBlocks.PALE_CRYSTAL.defaultState)
        }
        for ((dx, dz) in listOf(-8 to -3, 8 to -3, -8 to 6, 8 to 6)) {
            val bowl = BlockPos(cx + dx, floor + 1, centerZ + dz)
            put(bowl, HhaBlocks.GATE_STONE.defaultState)
            put(bowl.up(), HhaBlocks.GREEN_FIRE.defaultState)
        }
        return floor
    }
}
