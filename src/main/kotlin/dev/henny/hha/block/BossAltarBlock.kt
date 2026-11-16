package dev.henny.hha.block

import dev.henny.hha.HhaItems
import dev.henny.hha.entity.boss.DuelBossEntity
import dev.henny.hha.logic.world.OlympusProgress
import dev.henny.hha.world.olymp.OlympusLayout
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.state.StateManager
import net.minecraft.state.property.IntProperty
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import kotlin.math.hypot

/**
 * Altar bzw. Siegel, das einen Kampf des Olymp-Pfads startet (Überarbeitung 2, PLAN 4.4, 5.2–5.5). [BOSS]: 0 Hekate,
 * 1 Brontes, 2 Kratos, 3 Zeus, 4 frei (ohne Wirkung). Bedingungen:
 *
 * - **Hekate:** Rechtsklick mit dem Schwarzen Brief (`hha:hades_map`, wird nicht verbraucht); ohne Brief ein Hinweis,
 *   dass der Bote ihn hat.
 * - **Brontes:** Hermes besiegt. **Kratos:** Brontes besiegt. **Zeus:** Hermes, Brontes und Kratos besiegt.
 *
 * Fehlt etwas, nennt der Altar es; läuft der Kampf schon, auch. Der Altar prüft den Fortschritt selbst: Wer die Lücke
 * einer Siegelbrücke überfliegt, gewinnt nichts. Steht der Altar nicht an seiner Station (anderer Ort oder außerhalb
 * des Olymp, z. B. per `/setblock` zum Testen), entsteht die Arena um den Altar herum.
 */
class BossAltarBlock(settings: Settings) : Block(settings) {

    init {
        defaultState = stateManager.defaultState.with(BOSS, 0)
    }

    override fun appendProperties(builder: StateManager.Builder<Block, BlockState>) {
        builder.add(BOSS)
    }

    override fun onUse(state: BlockState, world: World, pos: BlockPos, player: PlayerEntity, hit: BlockHitResult): ActionResult {
        if (world is ServerWorld && player is ServerPlayerEntity) use(world, pos, state.get(BOSS), player, letter = holdsLetter(player))
        return ActionResult.SUCCESS
    }

    override fun onUseWithItem(
        stack: ItemStack, state: BlockState, world: World, pos: BlockPos, player: PlayerEntity, hand: Hand, hit: BlockHitResult,
    ): ActionResult {
        if (world is ServerWorld && player is ServerPlayerEntity) use(world, pos, state.get(BOSS), player, letter = stack.isOf(HhaItems.HADES_MAP))
        return ActionResult.SUCCESS
    }

    private fun holdsLetter(player: PlayerEntity): Boolean =
        player.mainHandStack.isOf(HhaItems.HADES_MAP) || player.offHandStack.isOf(HhaItems.HADES_MAP)

    private fun use(world: ServerWorld, pos: BlockPos, boss: Int, player: ServerPlayerEntity, letter: Boolean) {
        val isle = OlympusLayout.altarStation(boss)
        val key = isle?.key
        if (isle == null || key == null) {
            hint(player, "hha.boss_altar.idle")
            return
        }
        val server = world.server ?: return
        if (DuelBossEntity.running(server, key) != null) {
            hint(player, "hha.boss_altar.busy")
            return
        }
        val progress = OlympusProgress.of(server)
        val missing = when (boss) {
            0 -> if (letter) null else "hha.boss_altar.need_letter"
            1 -> if (progress.isDone(OlympusProgress.HERMES)) null else "hha.boss_altar.need_hermes"
            2 -> if (progress.isDone(OlympusProgress.BRONTES)) null else "hha.boss_altar.need_brontes"
            3 -> if (REQUIRED_FOR_ZEUS.all(progress::isDone)) null else "hha.boss_altar.need_zeus"
            else -> "hha.boss_altar.idle"
        }
        if (missing != null) {
            hint(player, missing)
            return
        }
        val atStation = world.registryKey == OlympusLayout.WORLD &&
            hypot(pos.x - isle.center.x.toDouble(), pos.z - isle.center.z.toDouble()) <= isle.radius + STATION_MARGIN
        val at = if (atStation) null else Vec3d(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        DuelBossEntity.startAt(world, key, player, at)
    }

    private fun hint(player: ServerPlayerEntity, key: String) {
        player.sendMessage(Text.translatable(key).formatted(Formatting.GOLD, Formatting.ITALIC), true)
    }

    companion object {
        /** 0 Hekate, 1 Brontes, 2 Kratos, 3 Zeus, 4 frei. */
        val BOSS: IntProperty = IntProperty.of("boss", 0, 4)

        /** Zeus will die drei anderen Stationen des Pfads besiegt sehen. */
        val REQUIRED_FOR_ZEUS = listOf(OlympusProgress.HERMES, OlympusProgress.BRONTES, OlympusProgress.KRATOS)

        /** So weit über den Inselrand hinaus gilt ein Altar noch als „an seiner Station“. */
        private const val STATION_MARGIN = 16.0
    }
}
