package dev.henny.hha.client.gui

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.render.entity.state.EntityRenderState
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.entity.Entity
import net.minecraft.entity.ItemEntity
import net.minecraft.entity.LivingEntity
import net.minecraft.item.ItemStack
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.sin

/**
 * Echte 3D-Modelle im Menü: Minecraft rendert Entities über `DrawContext.addEntity` in ein GUI-Rechteck. So
 * zeigt das Kontrollzentrum den Spieler mit seiner Rüstung und sich drehende Waffen wie fallengelassene Items.
 */
object Gui3d {

    private const val FULL_BRIGHT = 15728880

    private fun <T : Entity> stateOf(entity: T): EntityRenderState {
        val dispatcher = MinecraftClient.getInstance().entityRenderDispatcher
        val renderer = dispatcher.getRenderer(entity)
        val state = renderer.getAndUpdateRenderState(entity, 1.0f)
        state.light = FULL_BRIGHT
        state.shadowPieces.clear()
        state.outlineColor = 0
        return state
    }

    /**
     * Zeichnet ein Lebewesen mit eigener Drehung. [yawDeg] dreht den Körper (0 = Blick zum Betrachter),
     * [tiltDeg] neigt die Kamera, [walk] lässt Arme und Beine schwingen (0 = still).
     */
    fun living(ctx: DrawContext, entity: LivingEntity, x1: Int, y1: Int, x2: Int, y2: Int, size: Float, yawDeg: Float, tiltDeg: Float, walk: Float, time: Float) {
        val state = stateOf(entity)
        if (state is LivingEntityRenderState) {
            state.bodyYaw = 180f + yawDeg
            state.relativeHeadYaw = sin(time * 0.05f) * 12f
            state.pitch = sin(time * 0.037f) * 6f
            state.width = state.width / state.baseScale
            state.height = state.height / state.baseScale
            state.baseScale = 1f
            if (walk > 0f) {
                state.limbSwingAnimationProgress = time * 0.35f
                state.limbSwingAmplitude = walk
            }
        }
        val camera = Quaternionf().rotateX(Math.toRadians(tiltDeg.toDouble()).toFloat())
        val rotation = Quaternionf().rotateZ(Math.PI.toFloat()).mul(camera)
        ctx.addEntity(state, size, Vector3f(0f, state.height / 2f + 0.0625f, 0f), rotation, camera, x1, y1, x2, y2)
    }

    private val items = HashMap<String, ItemEntity>()

    /** Ein sich drehendes, schwebendes Item wie auf dem Boden, nur im Menü. */
    fun item(ctx: DrawContext, stack: ItemStack, id: String, x1: Int, y1: Int, x2: Int, y2: Int, size: Float, time: Float, tiltDeg: Float = 12f) {
        val world = MinecraftClient.getInstance().world ?: return
        if (stack.isEmpty) return
        val entity = items.getOrPut(id) { ItemEntity(world, 0.0, 0.0, 0.0, stack.copy()) }
        if (entity.entityWorld !== world || !ItemStack.areEqual(entity.stack, stack)) {
            items[id] = ItemEntity(world, 0.0, 0.0, 0.0, stack.copy())
            return item(ctx, stack, id, x1, y1, x2, y2, size, time, tiltDeg)
        }
        val state = stateOf(entity)
        state.age = time
        val camera = Quaternionf().rotateX(Math.toRadians(tiltDeg.toDouble()).toFloat())
        val rotation = Quaternionf().rotateZ(Math.PI.toFloat()).mul(camera)
        ctx.addEntity(state, size, Vector3f(0f, 0.3f, 0f), rotation, camera, x1, y1, x2, y2)
    }

    fun clear() = items.clear()
}
