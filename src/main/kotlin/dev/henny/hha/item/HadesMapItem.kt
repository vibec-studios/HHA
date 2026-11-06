package dev.henny.hha.item

import dev.henny.hha.HhaSounds
import dev.henny.hha.logic.Vfx
import dev.henny.hha.world.HhaWorldgen
import net.minecraft.component.DataComponentTypes
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.FilledMapItem
import net.minecraft.item.Item
import net.minecraft.item.map.MapDecorationTypes
import net.minecraft.item.map.MapState
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World

/**
 * Karte zu Hades: Beim Lesen wird sie zu einer echten Minecraft-Karte, die den Eingang zur nächsten Halle markiert.
 * Eine Seelenspur zeigt kurz in die Richtung, damit man sie auch ohne Karte im Blick findet.
 */
class HadesMapItem(settings: Settings) : Item(settings) {

    override fun use(world: World, user: PlayerEntity, hand: Hand): ActionResult {
        if (user !is ServerPlayerEntity || world !is ServerWorld) return ActionResult.SUCCESS
        if (world.registryKey != World.OVERWORLD) {
            user.sendMessage(Text.translatable("hha.hades.map.wrong_world").formatted(Formatting.RED), true)
            return ActionResult.FAIL
        }
        val target = world.locateStructure(HhaWorldgen.HADES_GATE_TAG, user.blockPos, SEARCH_CHUNKS, false)
        if (target == null) {
            user.sendMessage(Text.translatable("hha.hades.map.nothing").formatted(Formatting.RED), true)
            return ActionResult.FAIL
        }
        val map = FilledMapItem.createMap(world, target.x, target.z, 2.toByte(), true, true)
        FilledMapItem.fillExplorationMap(world, map)
        MapState.addDecorationsNbt(map, target, "hades", MapDecorationTypes.RED_X)
        map.set(DataComponentTypes.CUSTOM_NAME, Text.translatable("item.hha.hades_map").formatted(Formatting.GREEN))

        val stack = user.getStackInHand(hand)
        if (!user.isCreative) stack.decrement(1)
        if (!user.giveItemStack(map)) user.dropItem(map, false)

        val from = user.eyePos.subtract(0.0, 0.2, 0.0)
        val toward = Vec3d(target.x + 0.5 - from.x, 0.0, target.z + 0.5 - from.z).normalize()
        Vfx.beam(world, Vfx.Style.SOUL, from, from.add(toward.multiply(40.0)), 0.5f, 50)
        world.playSound(null, user.blockPos, HhaSounds.SOUL, SoundCategory.PLAYERS, 1.2f, 0.7f)
        val distance = user.blockPos.withY(0).getManhattanDistance(target.withY(0))
        user.sendMessage(Text.translatable("hha.hades.map.read", distance).formatted(Formatting.GREEN, Formatting.ITALIC), false)
        return ActionResult.SUCCESS
    }

    private companion object {
        const val SEARCH_CHUNKS = 100
    }
}
