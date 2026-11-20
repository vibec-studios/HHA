package dev.henny.hha.block

import com.mojang.serialization.MapCodec
import net.minecraft.block.BlockState
import net.minecraft.block.ShapeContext
import net.minecraft.block.TranslucentBlock
import net.minecraft.entity.ai.pathing.NavigationType
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.shape.VoxelShape
import net.minecraft.util.shape.VoxelShapes
import net.minecraft.world.BlockView

/**
 * Gewitterwolke `hha:storm_cloud` (PLAN 2.1 O9, 7.1; P05): halb durchsichtig (Render-Layer TRANSLUCENT), ohne Drop,
 * Härte 0,2 (Einstellungen in `HhaDecoBlocks`: ohne Kollision, erstickt nicht, blockiert keine Sicht).
 *
 * - Flächen zwischen zwei Wolkenblöcken werden nicht gezeichnet ([isSideInvisible]): Ein Wolkenfeld wirkt wie ein
 *   Körper, nicht wie gestapelte Scheiben.
 * - Dämpft kein Licht: [getOpacity] 0 und [isTransparent] (Himmelslicht fällt durch), kein Umgebungsschatten
 *   ([getAmbientOcclusionLightLevel] 1). Die Prüfung `isOpaqueFullCube` greift dank `nonOpaque` ohnehin nicht.
 * - Kein Hindernis: kein Kollisions-, Kamera- und Sichtstrahl-Körper (Mobs sehen durch, Kamera der dritten Person
 *   wird nicht weggeschoben), Wegfindung geht hindurch. Der Umriss bleibt ein voller Würfel, damit man sie abbauen kann.
 */
class StormCloudBlock(settings: Settings) : TranslucentBlock(settings) {

    override fun getCodec(): MapCodec<out TranslucentBlock> = CODEC

    override fun isSideInvisible(state: BlockState, stateFrom: BlockState, direction: Direction): Boolean =
        stateFrom.isOf(this) || super.isSideInvisible(state, stateFrom, direction)

    override fun getOpacity(state: BlockState): Int = 0

    override fun isTransparent(state: BlockState): Boolean = true

    override fun getAmbientOcclusionLightLevel(state: BlockState, world: BlockView, pos: BlockPos): Float = 1.0f

    override fun getCollisionShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape =
        VoxelShapes.empty()

    override fun getCameraCollisionShape(state: BlockState, world: BlockView, pos: BlockPos, context: ShapeContext): VoxelShape =
        VoxelShapes.empty()

    override fun canPathfindThrough(state: BlockState, type: NavigationType): Boolean = true

    companion object {
        val CODEC: MapCodec<StormCloudBlock> = createCodec(::StormCloudBlock)
    }
}
