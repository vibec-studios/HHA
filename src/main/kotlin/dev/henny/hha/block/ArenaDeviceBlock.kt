package dev.henny.hha.block

import com.mojang.serialization.MapCodec
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.HorizontalFacingBlock
import net.minecraft.block.ShapeContext
import net.minecraft.entity.ai.pathing.NavigationType
import net.minecraft.item.ItemPlacementContext
import net.minecraft.state.StateManager
import net.minecraft.state.property.IntProperty
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.shape.VoxelShape
import net.minecraft.util.shape.VoxelShapes
import net.minecraft.world.BlockView

/**
 * Arena-Gerät (PLAN 7.8, P05): Botenglocke, Erdungsstab, Kettenpfahl, Schleusenrad.
 *
 * - Eigenschaften [MODE] (0 still, 1 golden/bereit, 2 verbraucht) und `FACING` (Vorderseite; die Modelle sind nach
 *   Norden gebaut, der Blockstate dreht sie um 90°-Schritte).
 * - Unzerstörbar, ohne Drop, Kolben blockiert, kein Spawnen, Licht 9 nur im Zustand 1 (Einstellungen in `HhaDecoBlocks`).
 * - Bedienung: Rechtsklick (Glocke auch Schlag) läuft über `ArenaDevices.init` (`UseBlockCallback`/`AttackBlockCallback`),
 *   damit es mit jedem Gegenstand in jeder Hand gleich wirkt; der Block selbst hat kein eigenes `onUse`.
 * - Umriss und Kollision je Gerät nach dem Modell ([Shapes]): Glocke 12 × 14 × 12; Erdungsstab flach an der Wand, 4 tief;
 *   Kettenpfahl Säule 6 × 16 × 6 auf einem Fuß, Kollision 1,5 hoch wie eine Mauer, umgelegt (Zustand 2) flach;
 *   Schleusenrad 14 × 14 × 4 an der Wand.
 *
 * [kind] ist die Block-ID ohne Namensraum (`messenger_bell`, …).
 */
class ArenaDeviceBlock(settings: Settings, val kind: String) : HorizontalFacingBlock(settings) {

    private val codec: MapCodec<ArenaDeviceBlock> = createCodec { ArenaDeviceBlock(it, kind) }

    init {
        defaultState = stateManager.defaultState.with(FACING, Direction.NORTH).with(MODE, IDLE)
    }

    override fun getCodec(): MapCodec<out HorizontalFacingBlock> = codec

    override fun appendProperties(builder: StateManager.Builder<Block, BlockState>) {
        builder.add(FACING, MODE)
    }

    /** Die Vorderseite zeigt zum Spieler, der es setzt. */
    override fun getPlacementState(ctx: ItemPlacementContext): BlockState =
        defaultState.with(FACING, ctx.horizontalPlayerFacing.opposite)

    override fun getOutlineShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape =
        Shapes.outline(kind, state.get(MODE), state.get(FACING))

    override fun getCollisionShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape =
        Shapes.collision(kind, state.get(MODE), state.get(FACING))

    override fun canPathfindThrough(state: BlockState, type: NavigationType): Boolean = false

    /**
     * Umrisse und Kollision je Gerät, Zustand und Richtung. Maße in Modell-Einheiten (1/16 Block) für die
     * Blickrichtung Norden: Vorderseite bei z 0, Wand bei z 16; [rotate] dreht sie wie der Blockstate (`y` im Uhrzeigersinn).
     */
    object Shapes {
        /** Ein Kasten (x0, y0, z0, x1, y1, z1) in Modell-Einheiten. */
        private class Box(val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double)

        private fun box(x0: Number, y0: Number, z0: Number, x1: Number, y1: Number, z1: Number) =
            Box(x0.toDouble(), y0.toDouble(), z0.toDouble(), x1.toDouble(), y1.toDouble(), z1.toDouble())

        // Glocke: Marmorsockel 12 × 12, Bügel bis 14 hoch.
        private val BELL = listOf(box(2, 0, 2, 14, 14, 14))

        // Erdungsstab: Platte und Hebel flach an der Wand, 4 tief.
        private val ROD = listOf(box(2, 1, 12, 14, 15, 16))

        // Kettenpfahl stehend: Fuß 8 × 8, Schaft 6 × 6; Kollision wie eine Mauer 24 hoch (über dem Pfahl kein Sprung).
        private val POST_OUTLINE = listOf(box(4, 0, 4, 12, 2.5, 12), box(5, 0, 5, 11, 16, 11))
        private val POST_COLLISION = listOf(box(4, 0, 4, 12, 2.5, 12), box(5, 0, 5, 11, 24, 11))

        // Kettenpfahl umgelegt: liegt in Richtung der Vorderseite (z 0), Fuß an der Rückseite; flach (begehbar).
        private val POST_DOWN_OUTLINE = listOf(box(4, 0, 0, 12, 8, 16))
        private val POST_DOWN_COLLISION = listOf(box(4, 0, 0, 12, 6, 16))

        // Schleusenrad: Rad 14 × 14 vor der Basaltplatte, zusammen 4 tief.
        private val WHEEL = listOf(box(1, 1, 12, 15, 15, 16))

        private val outlines = HashMap<String, VoxelShape>()
        private val collisions = HashMap<String, VoxelShape>()

        fun outline(kind: String, mode: Int, facing: Direction): VoxelShape =
            outlines.getOrPut("$kind/$mode/$facing") { build(outlineBoxes(kind, mode), facing) }

        fun collision(kind: String, mode: Int, facing: Direction): VoxelShape =
            collisions.getOrPut("$kind/$mode/$facing") { build(collisionBoxes(kind, mode), facing) }

        private fun outlineBoxes(kind: String, mode: Int): List<Box> = when (kind) {
            "messenger_bell" -> BELL
            "grounding_rod" -> ROD
            "chain_post" -> if (mode == SPENT) POST_DOWN_OUTLINE else POST_OUTLINE
            "sluice_wheel" -> WHEEL
            else -> listOf(box(0, 0, 0, 16, 16, 16))
        }

        private fun collisionBoxes(kind: String, mode: Int): List<Box> = when (kind) {
            "chain_post" -> if (mode == SPENT) POST_DOWN_COLLISION else POST_COLLISION
            else -> outlineBoxes(kind, mode)
        }

        private fun build(boxes: List<Box>, facing: Direction): VoxelShape =
            boxes.map { rotate(it, facing) }
                .map { Block.createCuboidShape(it.x0, it.y0, it.z0, it.x1, it.y1, it.z1) }
                .reduce { a, b -> VoxelShapes.union(a, b) }
                .simplify()

        /** Dreht einen Kasten von Norden auf [facing] (wie Blockstate `y` 90/180/270: Norden → Osten → Süden → Westen). */
        private fun rotate(b: Box, facing: Direction): Box = when (facing) {
            Direction.EAST -> Box(16 - b.z1, b.y0, b.x0, 16 - b.z0, b.y1, b.x1)
            Direction.SOUTH -> Box(16 - b.x1, b.y0, 16 - b.z1, 16 - b.x0, b.y1, 16 - b.z0)
            Direction.WEST -> Box(b.z0, b.y0, 16 - b.x1, b.z1, b.y1, 16 - b.x0)
            else -> b
        }
    }

    companion object {
        val MODE: IntProperty = IntProperty.of("mode", 0, 2)
        const val IDLE = 0
        const val READY = 1
        const val SPENT = 2
    }
}
