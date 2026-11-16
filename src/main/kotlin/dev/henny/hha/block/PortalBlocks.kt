package dev.henny.hha.block

import dev.henny.hha.HhaSounds
import dev.henny.hha.logic.world.GateFade
import dev.henny.hha.logic.world.HadesGate
import dev.henny.hha.logic.world.OlympusGate
import dev.henny.hha.logic.world.PortalFrames
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.Portal
import net.minecraft.block.ShapeContext
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityCollisionHandler
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.state.StateManager
import net.minecraft.state.property.EnumProperty
import net.minecraft.state.property.Properties
import net.minecraft.util.BlockRotation
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.random.Random
import net.minecraft.util.shape.VoxelShape
import net.minecraft.world.BlockView
import net.minecraft.world.TeleportTarget
import net.minecraft.world.World
import net.minecraft.world.WorldView
import net.minecraft.world.tick.ScheduledTickView

/*
 * Die beiden Tore (Überarbeitung 2, PLAN 7). Rahmen und Reise stehen in `logic/world` (PortalFrames, OlympusGate,
 * HadesGate, PortalLinks), die Darstellung in `client/vfx/PortalVfx`.
 */

/** Scheibe 4/16 dick, wie bisher; [Direction.Axis.X] = die Ebene spannt x (dünn in z). */
private val SHAPE_X: VoxelShape = Block.createCuboidShape(0.0, 0.0, 6.0, 16.0, 16.0, 10.0)
private val SHAPE_Z: VoxelShape = Block.createCuboidShape(6.0, 0.0, 0.0, 10.0, 16.0, 16.0)

private fun shapeFor(state: BlockState): VoxelShape = if (state.get(Properties.HORIZONTAL_AXIS) == Direction.Axis.Z) SHAPE_Z else SHAPE_X

/** Dreht die Achse mit, wenn ein Bauplan gedreht gesetzt wird. */
private fun rotated(state: BlockState, rotation: BlockRotation): BlockState = when (rotation) {
    BlockRotation.CLOCKWISE_90, BlockRotation.COUNTERCLOCKWISE_90 -> state.with(
        Properties.HORIZONTAL_AXIS,
        if (state.get(Properties.HORIZONTAL_AXIS) == Direction.Axis.X) Direction.Axis.Z else Direction.Axis.X,
    )
    else -> state
}

/** Verweilzeit beider Tore (PLAN 7.1/7.3): 40 Ticks, im Kreativmodus 1; passt zum Einblenden in [GateFade]. */
private fun playerDelay(entity: Entity): Int =
    if (entity is PlayerEntity) (if (entity.isCreative) 1 else GateFade.PULL_TICKS) else 0

/**
 * Das Höllentor zwischen dem Dreiweg des Olymp (bzw. alten Oberwelt-Hallen) und dem Tartaros. [AXIS] (Standard x wie
 * alle alten Tore: quer zur z-Achse); runde Öffnung nach `HadesGate.inOpening`.
 */
class HadesPortalBlock(settings: Settings) : Block(settings), Portal {

    init {
        defaultState = stateManager.defaultState.with(AXIS, Direction.Axis.X)
    }

    override fun appendProperties(builder: StateManager.Builder<Block, BlockState>) {
        builder.add(AXIS)
    }

    override fun getOutlineShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape = shapeFor(state)

    override fun rotate(state: BlockState, rotation: BlockRotation): BlockState = rotated(state, rotation)

    override fun onEntityCollision(state: BlockState, world: World, pos: BlockPos, entity: Entity, handler: EntityCollisionHandler, bl: Boolean) {
        if (entity.canUsePortals(false)) {
            entity.tryUsePortal(this, pos)
            if (entity is ServerPlayerEntity) HadesGate.touching(entity, pos)
        }
    }

    override fun getPortalDelay(world: ServerWorld, entity: Entity): Int = playerDelay(entity)

    override fun createTeleportTarget(world: ServerWorld, entity: Entity, pos: BlockPos): TeleportTarget? = HadesGate.target(world, entity, pos)

    /** Kein Übelkeits-Wabern; das Bild färbt sich stattdessen grün. */
    override fun getPortalEffect(): Portal.Effect = Portal.Effect.NONE

    /** Läuft nur auf dem Client und meldet das Tor beim Darstellungs-Register an. */
    override fun randomDisplayTick(state: BlockState, world: World, pos: BlockPos, random: Random) {
        dev.henny.hha.client.vfx.PortalVfx.note(world, pos)
    }

    override fun getPickStack(world: WorldView, pos: BlockPos, state: BlockState, includeData: Boolean): ItemStack = ItemStack.EMPTY

    companion object {
        /** Achse der Torebene (x/z). */
        val AXIS: EnumProperty<Direction.Axis> = Properties.HORIZONTAL_AXIS
    }
}

/**
 * Das Wolkentor (Oberwelt ↔ Olymp, PLAN 7.1): Achse, keine Kollision, Licht 11, unzerstörbar von Hand; zerfällt wie das
 * Netherportal, sobald sein Glowstone-Rahmen nicht mehr vollständig ist.
 */
class OlympusPortalBlock(settings: Settings) : Block(settings), Portal {

    init {
        defaultState = stateManager.defaultState.with(AXIS, Direction.Axis.X)
    }

    override fun appendProperties(builder: StateManager.Builder<Block, BlockState>) {
        builder.add(AXIS)
    }

    override fun getOutlineShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape = shapeFor(state)

    override fun rotate(state: BlockState, rotation: BlockRotation): BlockState = rotated(state, rotation)

    /** Wie `NetherPortalBlock`: Ändert sich ein Nachbar in der Torebene, wird der Rahmen geprüft. */
    override fun getStateForNeighborUpdate(
        state: BlockState,
        world: WorldView,
        tickView: ScheduledTickView,
        pos: BlockPos,
        direction: Direction,
        neighborPos: BlockPos,
        neighborState: BlockState,
        random: Random,
    ): BlockState {
        val axis = state.get(AXIS)
        val beside = direction.axis != axis && direction.axis.isHorizontal
        if (!beside && !neighborState.isOf(this) && PortalFrames.find(world, pos, axis) == null) return Blocks.AIR.defaultState
        return super.getStateForNeighborUpdate(state, world, tickView, pos, direction, neighborPos, neighborState, random)
    }

    override fun onEntityCollision(state: BlockState, world: World, pos: BlockPos, entity: Entity, handler: EntityCollisionHandler, bl: Boolean) {
        if (entity.canUsePortals(false)) {
            entity.tryUsePortal(this, pos)
            if (entity is ServerPlayerEntity) GateFade.touching(entity, pos, OlympusGate.CLOUD_COLOR, HhaSounds.OLYMPUS_PORTAL_ENTER)
        }
    }

    override fun getPortalDelay(world: ServerWorld, entity: Entity): Int = playerDelay(entity)

    override fun createTeleportTarget(world: ServerWorld, entity: Entity, pos: BlockPos): TeleportTarget? = OlympusGate.target(world, entity, pos)

    /** Kein Übelkeits-Wabern. */
    override fun getPortalEffect(): Portal.Effect = Portal.Effect.NONE

    /** Läuft nur auf dem Client und meldet das Tor beim Darstellungs-Register an. */
    override fun randomDisplayTick(state: BlockState, world: World, pos: BlockPos, random: Random) {
        dev.henny.hha.client.vfx.PortalVfx.note(world, pos)
    }

    override fun getPickStack(world: WorldView, pos: BlockPos, state: BlockState, includeData: Boolean): ItemStack = ItemStack.EMPTY

    companion object {
        /** Achse der Torebene (x/z). */
        val AXIS: EnumProperty<Direction.Axis> = Properties.HORIZONTAL_AXIS
    }
}
