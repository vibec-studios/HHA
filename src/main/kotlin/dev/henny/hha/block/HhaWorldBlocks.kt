package dev.henny.hha.block

import com.mojang.serialization.MapCodec
import dev.henny.hha.HhaBlocks
import dev.henny.hha.logic.world.HadesGate
import dev.henny.hha.logic.world.OlympusPuzzle
import dev.henny.hha.world.olymp.OlympusLayout
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

// HadesPortalBlock und OlympusPortalBlock stehen seit Überarbeitung 2 in PortalBlocks.kt.

/** Dekoblock mit Inschrift: Rechtsklick zeigt einen Text. */
class InscribedBlock(settings: Settings, private val key: String) : Block(settings) {
    override fun onUse(state: BlockState, world: World, pos: BlockPos, player: PlayerEntity, hit: BlockHitResult): ActionResult {
        if (!world.isClient) player.sendMessage(Text.translatable(key).formatted(Formatting.GOLD, Formatting.ITALIC), false)
        return ActionResult.SUCCESS
    }
}

/**
 * Opferstein im Olymp-Tempel. Die vier Steine stehen in den vier Himmelsrichtungen; der Tempel will die Gaben in der
 * Reihenfolge von Hermes' erstem Lebenstag. Die Logik steht in [OlympusPuzzle]. Seit Überarbeitung 2 gilt das Rätsel
 * nur noch im Olymp; alte Opfersteine in der Oberwelt verweisen auf das Wolkentor (`hha.olympus.moved`).
 */
class OfferingPedestalBlock(settings: Settings) : Block(settings) {

    init {
        defaultState = stateManager.defaultState.with(SLOT, 0).with(LIT, false)
    }

    override fun appendProperties(builder: StateManager.Builder<Block, BlockState>) {
        builder.add(SLOT, LIT)
    }

    override fun onUse(state: BlockState, world: World, pos: BlockPos, player: PlayerEntity, hit: BlockHitResult): ActionResult {
        if (world is ServerWorld) {
            if (world.registryKey == OlympusLayout.WORLD) OlympusPuzzle.riddle(state, player) else moved(player)
        }
        return ActionResult.SUCCESS
    }

    override fun onUseWithItem(stack: ItemStack, state: BlockState, world: World, pos: BlockPos, player: PlayerEntity, hand: Hand, hit: BlockHitResult): ActionResult {
        if (stack.isEmpty) return ActionResult.PASS_TO_DEFAULT_BLOCK_ACTION
        if (world is ServerWorld && player is ServerPlayerEntity) {
            if (world.registryKey == OlympusLayout.WORLD) OlympusPuzzle.offer(world, pos, state, stack, player) else moved(player)
        }
        return ActionResult.SUCCESS
    }

    /** Der Tempel der Oberwelt ist verlassen: Hermes wartet jetzt im Olymp hinter dem Wolkentor. */
    private fun moved(player: PlayerEntity) {
        player.sendMessage(Text.translatable("hha.olympus.moved").formatted(Formatting.GOLD, Formatting.ITALIC), false)
    }

    companion object {
        val SLOT: IntProperty = IntProperty.of("slot", 0, 3)
        val LIT: BooleanProperty = BooleanProperty.of("lit")
    }
}

/**
 * Altar in der alten Hades-Halle der Oberwelt. Seit Überarbeitung 2 (PLAN 3) ist der Fährmann fort: Das Tor steht jetzt
 * am Dreiweg im Olymp. Schon offene Tore alter Welten führen weiter in den Tartaros.
 */
class HadesAltarBlock(settings: Settings) : Block(settings) {

    override fun onUse(state: BlockState, world: World, pos: BlockPos, player: PlayerEntity, hit: BlockHitResult): ActionResult {
        if (!world.isClient) player.sendMessage(Text.translatable("hha.hades.altar.moved").formatted(Formatting.GREEN, Formatting.ITALIC), false)
        return ActionResult.SUCCESS
    }

    override fun onUseWithItem(stack: ItemStack, state: BlockState, world: World, pos: BlockPos, player: PlayerEntity, hand: Hand, hit: BlockHitResult): ActionResult {
        if (!stack.isOf(Items.GOLD_NUGGET)) return ActionResult.PASS_TO_DEFAULT_BLOCK_ACTION
        if (world is ServerWorld && player is ServerPlayerEntity) {
            val key = if (HadesGate.isOpen(world, HadesGate.centerFromAltar(pos))) "hha.hades.already_open" else "hha.hades.altar.moved"
            player.sendMessage(Text.translatable(key).formatted(Formatting.GREEN), true)
        }
        return ActionResult.SUCCESS
    }
}

/**
 * Steinerne Tafel an den Bauwerken: Rechtsklick erzählt, wer hier büßt bzw. wacht. [STORY] 0–5 Tartaros
 * (`hha.tartaros.stele.<n>`), 6–15 die Inseln des Olymp (`hha.olympus.stele.<n>`, Belegung durch P04).
 */
class SteleBlock(settings: Settings) : Block(settings) {

    init {
        defaultState = stateManager.defaultState.with(STORY, 0)
    }

    override fun appendProperties(builder: StateManager.Builder<Block, BlockState>) {
        builder.add(STORY)
    }

    override fun getOutlineShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape = SHAPE

    override fun getCollisionShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape = SHAPE

    override fun onUse(state: BlockState, world: World, pos: BlockPos, player: PlayerEntity, hit: BlockHitResult): ActionResult {
        if (!world.isClient) {
            val story = state.get(STORY)
            val key = if (story <= LAST_TARTAROS) "hha.tartaros.stele.$story" else "hha.olympus.stele.$story"
            player.sendMessage(Text.translatable(key).formatted(Formatting.GRAY, Formatting.ITALIC), false)
        }
        return ActionResult.SUCCESS
    }

    companion object {
        val STORY: IntProperty = IntProperty.of("story", 0, 15)

        /** 0–5 gehören dem Tartaros, ab 6 dem Olymp. */
        const val LAST_TARTAROS = 5
        private val SHAPE: VoxelShape = createCuboidShape(3.0, 0.0, 3.0, 13.0, 16.0, 13.0)
    }
}

/** Das Siegel vor dem Thronsaal. Wer es berührt, klopft bei Hades an. */
class HadesSealBlock(settings: Settings) : Block(settings) {
    override fun onUse(state: BlockState, world: World, pos: BlockPos, player: PlayerEntity, hit: BlockHitResult): ActionResult {
        if (world is ServerWorld && player is ServerPlayerEntity) {
            val start = world.structureAccessor.getStructureContaining(pos, dev.henny.hha.world.HhaWorldgen.HADES_PALACE_TAG)
            val piece = if (start.hasChildren()) start.children.firstOrNull() as? dev.henny.hha.world.TartarusPiece else null
            if (piece != null) dev.henny.hha.logic.boss.HadesFights.start(player, piece.middle)
        }
        return ActionResult.SUCCESS
    }
}

/** Kurzer Zugriff für Strukturen und Spielregeln. */
fun pedestalState(slot: Int): BlockState = HhaBlocks.OFFERING_PEDESTAL.defaultState.with(OfferingPedestalBlock.SLOT, slot)
