package dev.henny.hha.logic.world

import dev.henny.hha.HhaBlocks
import dev.henny.hha.HhaSounds
import dev.henny.hha.block.OfferingPedestalBlock
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.HermesFights
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d

/**
 * Das Rätsel im Olymp-Tempel. Die homerische Hymne erzählt Hermes' ersten Lebenstag: bei Sonnenaufgang geboren,
 * mittags die Leier erfunden, abends Apollons Rinder gestohlen, nachts den Göttern geopfert. Vier Steine stehen in den
 * Himmelsrichtungen, und wer der Sonne folgt, legt die Gaben in der richtigen Reihenfolge ab.
 *
 * Reihenfolge: Osten (Feder), Süden (Notenblock), Westen (Leder oder Rindfleisch), Norden (Papier).
 * Der Fortschritt steckt in den Blöcken selbst, also überlebt er Neustarts und braucht keinen eigenen Speicher.
 */
object OlympusPuzzle {

    private val GIFTS: List<Set<Item>> = listOf(
        setOf(Items.FEATHER),
        setOf(Items.NOTE_BLOCK),
        setOf(Items.LEATHER, Items.BEEF, Items.COOKED_BEEF),
        setOf(Items.PAPER),
    )

    /** So weit stehen die Steine höchstens auseinander (siehe OlympusTemplePiece). */
    private const val SEARCH = 12

    fun riddle(state: BlockState, player: PlayerEntity) {
        val slot = state.get(OfferingPedestalBlock.SLOT)
        player.sendMessage(Text.translatable("hha.olympus.riddle.$slot").formatted(Formatting.AQUA, Formatting.ITALIC), false)
    }

    fun offer(world: ServerWorld, pos: BlockPos, state: BlockState, stack: ItemStack, player: ServerPlayerEntity) {
        if (state.get(OfferingPedestalBlock.LIT)) {
            player.sendMessage(Text.translatable("hha.olympus.taken").formatted(Formatting.GRAY), true)
            return
        }
        val slot = state.get(OfferingPedestalBlock.SLOT)
        if (stack.item !in GIFTS[slot]) {
            reject(world, pos, player, "hha.olympus.wrong_gift")
            return
        }
        val stones = findStones(world, pos)
        val done = stones.count { it.second.get(OfferingPedestalBlock.LIT) }
        if (slot != done) {
            reject(world, pos, player, "hha.olympus.wrong_order")
            return
        }
        if (!player.isCreative) stack.decrement(1)
        world.setBlockState(pos, state.with(OfferingPedestalBlock.LIT, true), Block.NOTIFY_ALL)
        val at = Vec3d.ofCenter(pos).add(0.0, 0.8, 0.0)
        Vfx.flare(world, Vfx.Style.GOLD, at, 1.8f, 12)
        Vfx.pillar(world, Vfx.Style.GOLD, Vec3d.ofBottomCenter(pos), 0.7f, 9f, 34)
        Vfx.feathers(world, Vfx.Style.HEAVEN, at, 8, 0.8f, 40)
        world.playSound(null, pos, HhaSounds.CHIME, SoundCategory.BLOCKS, 2f, 0.8f + slot * 0.14f)
        player.sendMessage(Text.translatable("hha.olympus.accepted.$slot").formatted(Formatting.GOLD), true)
        if (done + 1 >= GIFTS.size) complete(world, pos, stones, player)
    }

    private fun findStones(world: ServerWorld, around: BlockPos): List<Pair<BlockPos, BlockState>> {
        val found = ArrayList<Pair<BlockPos, BlockState>>(GIFTS.size)
        for (pos in BlockPos.iterate(around.add(-SEARCH * 2, 0, -SEARCH * 2), around.add(SEARCH * 2, 0, SEARCH * 2))) {
            val state = world.getBlockState(pos)
            if (state.isOf(HhaBlocks.OFFERING_PEDESTAL)) found.add(pos.toImmutable() to state)
        }
        return found
    }

    /** Falsche Gabe: Hermes lacht, ein Blitz schlägt ein, und alles beginnt von vorn. */
    private fun reject(world: ServerWorld, pos: BlockPos, player: ServerPlayerEntity, key: String) {
        for ((stone, state) in findStones(world, pos)) {
            if (state.get(OfferingPedestalBlock.LIT)) world.setBlockState(stone, state.with(OfferingPedestalBlock.LIT, false), Block.NOTIFY_ALL)
        }
        val bolt = EntityType.LIGHTNING_BOLT.create(world, SpawnReason.EVENT)
        if (bolt != null) {
            val at = Vec3d.ofBottomCenter(pos.up())
            bolt.refreshPositionAfterTeleport(at.x, at.y, at.z)
            bolt.setCosmetic(true)
            world.spawnEntity(bolt)
        }
        Vfx.shake(world, Vec3d.ofCenter(pos), 1.5f, 10, 20f)
        world.playSound(null, pos, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, SoundCategory.BLOCKS, 1.6f, 1.4f)
        player.sendMessage(Text.translatable(key).formatted(Formatting.RED, Formatting.ITALIC), false)
    }

    /** Alle vier Gaben liegen: der Altar glüht, die Gaben verschwinden und der Bote kommt persönlich. */
    private fun complete(world: ServerWorld, pos: BlockPos, stones: List<Pair<BlockPos, BlockState>>, player: ServerPlayerEntity) {
        val altar = BlockPos.iterate(pos.add(-SEARCH * 2, 0, -SEARCH * 2), pos.add(SEARCH * 2, 0, SEARCH * 2))
            .firstOrNull { world.getBlockState(it).isOf(HhaBlocks.OLYMPUS_ALTAR) }?.toImmutable() ?: pos
        // Der Tempel setzt sich zurück; das Rätsel ist nach einem verlorenen Kampf wieder lösbar.
        for ((stone, _) in stones) {
            val current = world.getBlockState(stone)
            world.setBlockState(stone, current.with(OfferingPedestalBlock.LIT, false), Block.NOTIFY_ALL)
            Vfx.beam(world, Vfx.Style.GOLD, Vec3d.ofCenter(stone).add(0.0, 0.8, 0.0), Vec3d.ofCenter(altar).add(0.0, 1.0, 0.0), 0.5f, 26)
        }
        if (!HermesFights.startTemple(player, altar)) {
            player.sendMessage(Text.translatable("hha.hermes.msg.busy").formatted(Formatting.RED), true)
        }
    }
}
