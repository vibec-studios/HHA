package dev.henny.hha.logic.boss

import dev.henny.hha.config.HhaConfig
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.tag.DamageTypeTags
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.world.Heightmap
import net.minecraft.world.World
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d

/** Alle laufenden Hermes-Kämpfe (höchstens einer pro Dimension) und ihre Anbindung an die Spielereignisse. */
object HermesFights {

    private val active = HashMap<RegistryKey<World>, HermesFight>()

    /** Kleine Nachklänge, die nach dem Ende eines Kampfes noch laufen: verbleibende Ticks und Aktion. */
    private class Delayed(var ticks: Int, val action: () -> Unit)

    private val delayed = ArrayList<Delayed>()

    fun after(ticks: Int, action: () -> Unit) {
        delayed.add(Delayed(ticks, action))
    }

    /** Sind Blöcke an dieser Stelle gerade unantastbar? Gilt für laufende Arenen und immer für die Hades-Hallen. */
    @JvmStatic
    fun isProtected(world: net.minecraft.world.World, pos: BlockPos): Boolean {
        val server = world as? ServerWorld ?: return false
        if (active[server.registryKey]?.arena?.contains(pos) == true) return true
        if (server.registryKey != World.OVERWORLD) return false
        if (pos.y !in (dev.henny.hha.logic.world.HadesGate.FLOOR_Y - 6)..(dev.henny.hha.logic.world.HadesGate.FLOOR_Y + 40)) return false
        return server.structureAccessor.getStructureContaining(pos, dev.henny.hha.world.HhaWorldgen.HADES_GATE_TAG).hasChildren()
    }

    /** Alle Wege, auf denen Spieler Blöcke ändern können. Explosionen fängt ExplosionImplMixin ab. */
    private fun registerProtection() {
        net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents.BEFORE.register { world, _, pos, _, _ -> !isProtected(world, pos) }
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register { player, world, hand, hit ->
            val item = player.getStackInHand(hand).item
            val placing = item is net.minecraft.item.BlockItem || item is net.minecraft.item.BucketItem
            if (placing && !world.isClient && (isProtected(world, hit.blockPos) || isProtected(world, hit.blockPos.offset(hit.side)))) {
                player.sendMessage(Text.translatable("hha.hermes.hint.protected").formatted(Formatting.GOLD), true)
                net.minecraft.util.ActionResult.FAIL
            } else {
                net.minecraft.util.ActionResult.PASS
            }
        }
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register { player, world, hand ->
            if (!world.isClient && player.getStackInHand(hand).item is net.minecraft.item.BucketItem && isProtected(world, player.blockPos)) {
                net.minecraft.util.ActionResult.FAIL
            } else {
                net.minecraft.util.ActionResult.PASS
            }
        }
    }

    fun init() {
        registerProtection()
        ServerTickEvents.END_SERVER_TICK.register { server ->
            dev.henny.hha.logic.world.TartarosGuide.tick(server)
            dev.henny.hha.logic.world.HadesGate.tick(server)
            // Kopie, weil ein Kampf sich im Tick selbst beenden darf.
            for (fight in active.values.toList()) fight.tick()
            if (delayed.isNotEmpty()) {
                val due = delayed.filter { --it.ticks <= 0 }
                delayed.removeAll(due)
                due.forEach { it.action() }
            }
        }
        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, source, _ ->
            if (entity is ServerPlayerEntity) {
                val fight = active[entity.entityWorld.registryKey]
                !(fight != null && fight.tryKnockOut(entity, source))
            } else {
                true
            }
        }
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, _ ->
            if (entity is ServerPlayerEntity) {
                val fight = active[entity.entityWorld.registryKey]
                // Hermes wirbelt alle ständig durch die Luft: Landungen sollen nicht zusätzlich strafen.
                !(fight != null && fight.isParticipant(entity) &&
                    (fight.inCutscene || source.isIn(DamageTypeTags.IS_FALL)))
            } else {
                true
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val player = handler.player
            active[player.entityWorld.registryKey]?.onPlayerLeft(player)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            for (fight in active.values.toList()) fight.finish(success = false)
        }
    }

    fun of(world: ServerWorld): HermesFight? = active[world.registryKey]

    /** Messenger-Flügel aus Stage 4: gelten wie Heaven-Leggings für den Doppelsprung. */
    @JvmStatic
    fun hasMessengerWings(player: ServerPlayerEntity): Boolean {
        val fight = active[player.entityWorld.registryKey] ?: return false
        return fight.stage == 4 && fight.isParticipant(player)
    }

    fun onFinished(fight: HermesFight) {
        active.remove(fight.world.registryKey)
    }

    /**
     * Startet den Kampf am Standort des Spielers.
     * @return true, wenn der Brief verbraucht werden soll.
     */
    fun start(player: ServerPlayerEntity): Boolean {
        val world = player.entityWorld as? ServerWorld ?: return false
        if (!HhaConfig.enabled("hermes_fight")) {
            player.sendMessage(Text.translatable("hha.hermes.msg.disabled").formatted(Formatting.RED), true)
            return false
        }
        if (active.containsKey(world.registryKey)) {
            player.sendMessage(Text.translatable("hha.hermes.msg.busy").formatted(Formatting.RED), true)
            return false
        }
        // Das Forum braucht freien Himmel über dem Spieler: kein Nether-Dach, keine Höhle.
        val top = world.getTopY(Heightmap.Type.MOTION_BLOCKING, player.blockX, player.blockZ)
        if (!world.dimension.hasSkyLight() || player.blockY < top - 1) {
            player.sendMessage(Text.translatable("hha.hermes.msg.need_sky").formatted(Formatting.RED), true)
            return false
        }
        val fight = HermesFight(world, player.entityPos, player)
        active[world.registryKey] = fight
        fight.begin()
        return true
    }

    /** Start aus dem Olymp-Tempel: Das Rätsel ersetzt den Brief, darum gibt es bei einer Niederlage nichts zurück. */
    fun startTemple(player: ServerPlayerEntity, altar: BlockPos): Boolean {
        val world = player.entityWorld as? ServerWorld ?: return false
        if (active.containsKey(world.registryKey)) return false
        val fight = HermesFight(world, Vec3d.ofBottomCenter(altar.up()), player, HermesFight.Mode.TEMPLE)
        active[world.registryKey] = fight
        fight.begin()
        return true
    }

    /** Start in der Hades-Halle: Wer k.o. geht, landet am Eingang an der Oberfläche. */
    fun startShade(player: ServerPlayerEntity, center: BlockPos): Boolean {
        val world = player.entityWorld as? ServerWorld ?: return false
        if (!HhaConfig.enabled("hermes_fight")) {
            player.sendMessage(Text.translatable("hha.hermes.msg.disabled").formatted(Formatting.RED), true)
            return false
        }
        if (active.containsKey(world.registryKey)) {
            player.sendMessage(Text.translatable("hha.hermes.msg.busy").formatted(Formatting.RED), true)
            return false
        }
        val top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, center.add(4, 0, dev.henny.hha.world.HadesHallPiece.SHAFT_Z))
        val fight = HermesFight(world, Vec3d.ofBottomCenter(top), player, HermesFight.Mode.SHADE, center)
        active[world.registryKey] = fight
        fight.begin()
        return true
    }

    fun stop(world: ServerWorld): Boolean {
        val fight = active[world.registryKey] ?: return false
        fight.finish(success = false)
        return true
    }
}
