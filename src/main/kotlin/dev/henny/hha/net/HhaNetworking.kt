package dev.henny.hha.net

import dev.henny.hha.Hha
import dev.henny.hha.api.ability.AbilitySlot
import dev.henny.hha.api.ability.HhaAbilities
import dev.henny.hha.config.HhaConfig
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.RegistryByteBuf
import net.minecraft.server.MinecraftServer
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.codec.PacketCodecs
import net.minecraft.network.packet.CustomPayload

/** C2S-Payload: Spieler hat eine Ability-Taste gedrückt. */
data class AbilityPayload(val ability: Int) : CustomPayload {

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        // Wire-IDs 0–3 bleiben aus Kompatibilität wie in 0.1.x belegt.
        const val ABILITY_1 = 0
        const val ABILITY_2 = 1
        const val AIR_JUMP = 2
        const val ABILITY_3 = 3
        const val ABILITY_4 = 4
        const val ABILITY_5 = 5
        const val ABILITY_6 = 6

        val ID = CustomPayload.Id<AbilityPayload>(Hha.id("ability"))
        val CODEC: PacketCodec<RegistryByteBuf, AbilityPayload> =
            PacketCodec.tuple(PacketCodecs.VAR_INT, AbilityPayload::ability, ::AbilityPayload)
    }
}

/** S2C-Payload: effektive Config-Zahlenwerte — der Client zeigt sie in der Item-Lore. */
data class ConfigSyncPayload(val numbers: Map<String, Double>) : CustomPayload {

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<ConfigSyncPayload>(Hha.id("config_sync"))

        private val NUMBERS_CODEC: PacketCodec<RegistryByteBuf, MutableMap<String, Double>> =
            PacketCodecs.map(
                java.util.function.IntFunction { size -> HashMap(size) },
                PacketCodecs.STRING,
                PacketCodecs.DOUBLE,
            )

        val CODEC: PacketCodec<RegistryByteBuf, ConfigSyncPayload> = PacketCodec.tuple(
            NUMBERS_CODEC,
            { payload -> HashMap(payload.numbers) },
            { map -> ConfigSyncPayload(map) },
        )
    }
}

/** S2C-Payload: Ultra-Modus-Status fürs HUD (verbleibende Ticks, 0 = aus). */
data class UltraHudPayload(val remainingTicks: Int) : CustomPayload {

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<UltraHudPayload>(Hha.id("ultra_hud"))
        val CODEC: PacketCodec<RegistryByteBuf, UltraHudPayload> =
            PacketCodec.tuple(PacketCodecs.VAR_INT, UltraHudPayload::remainingTicks, ::UltraHudPayload)
    }
}

/** S2C-Payload: startet einen clientseitigen 3D-Effekt (siehe logic/Vfx). */
data class VfxPayload(
    val kind: Int,
    val style: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val dx: Double,
    val dy: Double,
    val dz: Double,
    val a: Float,
    val b: Float,
    val life: Int,
    val entity: Int,
    val entity2: Int,
) : CustomPayload {

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<VfxPayload>(Hha.id("vfx"))
        val CODEC: PacketCodec<RegistryByteBuf, VfxPayload> = PacketCodec.of(
            { value, buf ->
                buf.writeVarInt(value.kind)
                buf.writeVarInt(value.style)
                buf.writeDouble(value.x)
                buf.writeDouble(value.y)
                buf.writeDouble(value.z)
                buf.writeDouble(value.dx)
                buf.writeDouble(value.dy)
                buf.writeDouble(value.dz)
                buf.writeFloat(value.a)
                buf.writeFloat(value.b)
                buf.writeVarInt(value.life)
                buf.writeVarInt(value.entity)
                buf.writeVarInt(value.entity2)
            },
            { buf ->
                VfxPayload(
                    buf.readVarInt(), buf.readVarInt(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                )
            },
        )
    }
}

/**
 * S2C-Payload: ein Befehl der Cutscene-Timeline (Kamerafahrt, Dialog, Blende …). Die Bedeutung von
 * [numbers] und [texts] hängt von [op] ab, siehe `logic/boss/Cine` und `client/cine/CinePlayer`.
 */
data class CinePayload(val op: Int, val numbers: List<Double>, val texts: List<String>) : CustomPayload {

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<CinePayload>(Hha.id("cine"))
        val CODEC: PacketCodec<RegistryByteBuf, CinePayload> = PacketCodec.tuple(
            PacketCodecs.VAR_INT, CinePayload::op,
            PacketCodecs.DOUBLE.collect(PacketCodecs.toList()), CinePayload::numbers,
            PacketCodecs.STRING.collect(PacketCodecs.toList()), CinePayload::texts,
            ::CinePayload,
        )
    }
}

object HhaNetworking {

    fun init() {
        PayloadTypeRegistry.playC2S().register(AbilityPayload.ID, AbilityPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(UltraHudPayload.ID, UltraHudPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(ConfigSyncPayload.ID, ConfigSyncPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(VfxPayload.ID, VfxPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(CinePayload.ID, CinePayload.CODEC)

        ServerPlayConnectionEvents.JOIN.register { _, sender, _ ->
            sender.sendPacket(ConfigSyncPayload(HhaConfig.numbersSnapshot()))
        }

        ServerPlayNetworking.registerGlobalReceiver(AbilityPayload.ID) { payload, context ->
            val slot = when (payload.ability) {
                AbilityPayload.ABILITY_1 -> AbilitySlot.ABILITY_1
                AbilityPayload.ABILITY_2 -> AbilitySlot.ABILITY_2
                AbilityPayload.AIR_JUMP -> AbilitySlot.MOVEMENT
                AbilityPayload.ABILITY_3 -> AbilitySlot.ABILITY_3
                AbilityPayload.ABILITY_4 -> AbilitySlot.ABILITY_4
                AbilityPayload.ABILITY_5 -> AbilitySlot.ABILITY_5
                AbilityPayload.ABILITY_6 -> AbilitySlot.ABILITY_6
                else -> return@registerGlobalReceiver
            }
            HhaAbilities.dispatch(context.player(), slot)
        }
    }

    /** Schickt die aktuellen Config-Zahlen an alle Spieler (nach /hha set|reset|load). */
    fun syncConfig(server: MinecraftServer) {
        val payload = ConfigSyncPayload(HhaConfig.numbersSnapshot())
        for (player in server.playerManager.playerList) {
            ServerPlayNetworking.send(player, payload)
        }
    }
}
