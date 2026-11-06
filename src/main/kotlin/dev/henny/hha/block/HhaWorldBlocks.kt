package dev.henny.hha.block

import com.mojang.serialization.MapCodec
import dev.henny.hha.HhaBlocks
import dev.henny.hha.logic.boss.HermesFights
import dev.henny.hha.logic.world.HadesGate
import dev.henny.hha.logic.world.OlympusPuzzle
import net.minecraft.block.AbstractFireBlock
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Portal
import net.minecraft.block.ShapeContext
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityCollisionHandler
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.state.StateManager
import net.minecraft.state.property.BooleanProperty
import net.minecraft.state.property.IntProperty
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.random.Random
import net.minecraft.util.shape.VoxelShape
import net.minecraft.world.BlockView
import net.minecraft.world.TeleportTarget
import net.minecraft.world.World
import net.minecraft.world.WorldView
import net.minecraft.world.tick.ScheduledTickView

/** Grüne Flammen des Tartaros: brennen ewig auf jedem festen Boden, wie Seelenfeuer. */
class GreenFireBlock(settings: Settings) : AbstractFireBlock(settings, 3f) {

    private val codec: MapCodec<GreenFireBlock> = createCodec(::GreenFireBlock)

    override fun getCodec(): MapCodec<out AbstractFireBlock> = codec

    override fun isFlammable(state: BlockState): Boolean = true

    override fun canPlaceAt(state: BlockState, world: WorldView, pos: BlockPos): Boolean =
        world.getBlockState(pos.down()).isSideSolidFullSquare(world, pos.down(), Direction.UP)

    override fun getStateForNeighborUpdate(
        state: BlockState, world: WorldView, tickView: ScheduledTickView, pos: BlockPos, direction: Direction,
        neighborPos: BlockPos, neighborState: BlockState, random: Random,
    ): BlockState = if (canPlaceAt(state, world, pos)) defaultState else net.minecraft.block.Blocks.AIR.defaultState

    override fun randomDisplayTick(state: BlockState, world: World, pos: BlockPos, random: Random) {
        super.randomDisplayTick(state, world, pos, random)
        if (random.nextInt(4) == 0) {
            world.addParticleClient(ParticleTypes.HAPPY_VILLAGER, pos.x + random.nextDouble(), pos.y + 0.4 + random.nextDouble() * 0.6, pos.z + random.nextDouble(), 0.0, 0.02, 0.0)
        }
    }
}

/** Das Tor zwischen dem Reich unter der Erde und dem Tartaros; steht immer quer zur z-Achse. */
class HadesPortalBlock(settings: Settings) : Block(settings), Portal {

    override fun getOutlineShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape = SHAPE

    override fun onEntityCollision(state: BlockState, world: World, pos: BlockPos, entity: Entity, handler: EntityCollisionHandler, bl: Boolean) {
        if (entity.canUsePortals(false)) {
            entity.tryUsePortal(this, pos)
            if (entity is ServerPlayerEntity) HadesGate.touching(entity, pos)
        }
    }

    override fun getPortalDelay(world: ServerWorld, entity: Entity): Int = if (entity is PlayerEntity) 30 else 0

    override fun createTeleportTarget(world: ServerWorld, entity: Entity, pos: BlockPos): TeleportTarget? = HadesGate.target(world, entity, pos)

    /** Läuft nur auf dem Client und meldet das Tor beim Effekt-System an: Dort entsteht der Sog. */
    override fun randomDisplayTick(state: BlockState, world: World, pos: BlockPos, random: Random) {
        dev.henny.hha.client.vfx.PortalVfx.note(world, pos)
    }

    private companion object {
        val SHAPE: VoxelShape = createCuboidShape(0.0, 0.0, 6.0, 16.0, 16.0, 10.0)
    }
}

/** Dekoblock mit Inschrift: Rechtsklick zeigt einen Text. */
class InscribedBlock(settings: Settings, private val key: String) : Block(settings) {
    override fun onUse(state: BlockState, world: World, pos: BlockPos, player: PlayerEntity, hit: BlockHitResult): ActionResult {
        if (!world.isClient) player.sendMessage(Text.translatable(key).formatted(Formatting.GOLD, Formatting.ITALIC), false)
        return ActionResult.SUCCESS
    }
}

/**
 * Opferstein im Olymp-Tempel. Die vier Steine stehen in den vier Himmelsrichtungen; der Tempel will die Gaben in der
 * Reihenfolge von Hermes' erstem Lebenstag. Die Logik steht in [OlympusPuzzle].
 */
class OfferingPedestalBlock(settings: Settings) : Block(settings) {

    init {
        defaultState = stateManager.defaultState.with(SLOT, 0).with(LIT, false)
    }

    override fun appendProperties(builder: StateManager.Builder<Block, BlockState>) {
        builder.add(SLOT, LIT)
    }

    override fun onUse(state: BlockState, world: World, pos: BlockPos, player: PlayerEntity, hit: BlockHitResult): ActionResult {
        if (world is ServerWorld) OlympusPuzzle.riddle(state, player)
        return ActionResult.SUCCESS
    }

    override fun onUseWithItem(stack: ItemStack, state: BlockState, world: World, pos: BlockPos, player: PlayerEntity, hand: Hand, hit: BlockHitResult): ActionResult {
        if (stack.isEmpty) return ActionResult.PASS_TO_DEFAULT_BLOCK_ACTION
        if (world is ServerWorld && player is ServerPlayerEntity) OlympusPuzzle.offer(world, pos, state, stack, player)
        return ActionResult.SUCCESS
    }

    companion object {
        val SLOT: IntProperty = IntProperty.of("slot", 0, 3)
        val LIT: BooleanProperty = BooleanProperty.of("lit")
    }
}

/** Altar im Reich des Hades. Der Fährmann nimmt nur eine Währung: den Obolus, eine einzelne Goldmünze. */
class HadesAltarBlock(settings: Settings) : Block(settings) {

    override fun onUse(state: BlockState, world: World, pos: BlockPos, player: PlayerEntity, hit: BlockHitResult): ActionResult {
        if (!world.isClient) player.sendMessage(Text.translatable("hha.hades.altar").formatted(Formatting.GREEN, Formatting.ITALIC), false)
        return ActionResult.SUCCESS
    }

    override fun onUseWithItem(stack: ItemStack, state: BlockState, world: World, pos: BlockPos, player: PlayerEntity, hand: Hand, hit: BlockHitResult): ActionResult {
        if (!stack.isOf(Items.GOLD_NUGGET)) return ActionResult.PASS_TO_DEFAULT_BLOCK_ACTION
        if (world is ServerWorld && player is ServerPlayerEntity) {
            if (HadesGate.isOpen(world, HadesGate.centerFromAltar(pos))) {
                player.sendMessage(Text.translatable("hha.hades.already_open").formatted(Formatting.GREEN), true)
            } else if (HermesFights.startShade(player, HadesGate.centerFromAltar(pos)) && !player.isCreative) {
                stack.decrement(1)
            }
        }
        return ActionResult.SUCCESS
    }
}

/** Kurzer Zugriff für Strukturen und Spielregeln. */
fun pedestalState(slot: Int): BlockState = HhaBlocks.OFFERING_PEDESTAL.defaultState.with(OfferingPedestalBlock.SLOT, slot)
