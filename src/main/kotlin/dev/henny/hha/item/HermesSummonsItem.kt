package dev.henny.hha.item

import dev.henny.hha.logic.boss.HermesFights
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.Item
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.world.World

/** Versiegelter Brief: Rechtsklick unter freiem Himmel ruft Hermes zur Zustellung herbei. */
class HermesSummonsItem(settings: Settings) : Item(settings) {

    override fun use(world: World, user: PlayerEntity, hand: Hand): ActionResult {
        if (user !is ServerPlayerEntity) return ActionResult.SUCCESS
        if (!HermesFights.start(user)) return ActionResult.FAIL
        val stack = user.getStackInHand(hand)
        if (!user.isCreative) stack.decrement(1)
        return ActionResult.SUCCESS
    }
}
