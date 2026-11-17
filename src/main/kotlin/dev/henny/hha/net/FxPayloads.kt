package dev.henny.hha.net

import dev.henny.hha.Hha
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

/**
 * S2C-Payload der Überarbeitung 3 (PLAN 6.1): startet einen Client-Effekt über eine **Text-ID** (`core.arc`,
 * `brontes.hammer` …) statt über `Vfx.Kind`. So wächst keine gemeinsame Aufzählung, wenn ein Boss-Paket einen Effekt
 * dazubaut. Server: `logic/boss/BossFx`, Client: `client/vfx/fx/FxClient.handle` → `FxRegistry`.
 *
 * Die Bedeutung von [dx]..[dz], [a]..[d], [entity], [entity2] und [extra] hängt von der ID ab; die Kodierung der
 * `core.*`-IDs steht in `tmp/overhaul3/API.md` (Tabelle „BossFx-Kodierung“).
 */
data class FxPayload(
    val id: String,
    /** Ordinal von `Vfx.Style` (nach `styleOverride`). */
    val style: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val dx: Double,
    val dy: Double,
    val dz: Double,
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val life: Int,
    /** Entity-ID oder −1. */
    val entity: Int,
    /** Zweite Entity-ID oder −1. */
    val entity2: Int,
    /** Bits: [dev.henny.hha.logic.Vfx.FLAG_DANGER] (1) = Gefahrenpalette, [FLAG_IMPACT] (2) = Flächeneffekt des Treffers. */
    val flags: Int,
    val extra: String,
) : CustomPayload {

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        /** Bit in [flags]: Der Effekt ist der eine Flächeneffekt seines Treffers (nur zur Auskunft am Client). */
        const val FLAG_IMPACT = 2

        /** Reservierte ID: Der Client schreibt die registrierten Effekt-IDs in den Chat (`/hha fx list`). */
        const val ID_LIST = "@list"

        val ID = CustomPayload.Id<FxPayload>(Hha.id("fx"))
        val CODEC: PacketCodec<RegistryByteBuf, FxPayload> = PacketCodec.of(
            { value, buf ->
                buf.writeString(value.id)
                buf.writeVarInt(value.style)
                buf.writeDouble(value.x)
                buf.writeDouble(value.y)
                buf.writeDouble(value.z)
                buf.writeDouble(value.dx)
                buf.writeDouble(value.dy)
                buf.writeDouble(value.dz)
                buf.writeFloat(value.a)
                buf.writeFloat(value.b)
                buf.writeFloat(value.c)
                buf.writeFloat(value.d)
                buf.writeVarInt(value.life)
                buf.writeVarInt(value.entity)
                buf.writeVarInt(value.entity2)
                buf.writeVarInt(value.flags)
                buf.writeString(value.extra)
            },
            { buf ->
                FxPayload(
                    buf.readString(), buf.readVarInt(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readString(),
                )
            },
        )
    }
}
