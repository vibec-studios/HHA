package dev.henny.hha.net

import dev.henny.hha.Hha
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

/** C2S: Das Kontrollzentrum ist offen und möchte den aktuellen Stand vom Server. */
object GuiRequestPayload : CustomPayload {
    val ID = CustomPayload.Id<GuiRequestPayload>(Hha.id("gui_request"))
    val CODEC: PacketCodec<RegistryByteBuf, GuiRequestPayload> = PacketCodec.unit(GuiRequestPayload)
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID
}

/**
 * C2S: eine Aktion aus dem Kontrollzentrum. [action] ist ein fester Name (siehe `logic/GuiServer`), [key] und [value]
 * hängen von der Aktion ab (z. B. Config-Schlüssel und neuer Wert, oder UUID eines Spielers).
 */
data class GuiActionPayload(val action: String, val key: String, val value: String) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<GuiActionPayload>(Hha.id("gui_action"))
        val CODEC: PacketCodec<RegistryByteBuf, GuiActionPayload> = PacketCodec.of(
            { value, buf ->
                buf.writeString(value.action, 64)
                buf.writeString(value.key, 256)
                buf.writeString(value.value, 256)
            },
            { buf -> GuiActionPayload(buf.readString(64), buf.readString(256), buf.readString(256)) },
        )
    }
}

/** Ein Kit, das der Spieler sich holen kann: Schlüssel für `/hha kit`, Icon-Items und ob es ein Addon-Set ist. */
data class GuiKit(val key: String, val icon: String, val weapon: String)

/** Ein vertrauter Spieler: UUID, zuletzt bekannter Name, gerade online. */
data class GuiTrusted(val uuid: String, val name: String, val online: Boolean)

/**
 * S2C: alles, was das Kontrollzentrum anzeigt. Wird auf Anfrage und nach jeder Aktion geschickt. [message] ist ein
 * Übersetzungsschlüssel (leer = keine Meldung), [ok] färbt die Meldung.
 */
data class GuiStatePayload(
    val admin: Boolean,
    val toggles: Map<String, Boolean>,
    val toggleDefaults: Map<String, Boolean>,
    val numbers: Map<String, Double>,
    val numberDefaults: Map<String, Double>,
    val trusted: List<GuiTrusted>,
    val kits: List<GuiKit>,
    /** Bits: 1 = Hermes-Kampf in dieser Welt aktiv, 2 = Hades-Kampf aktiv. */
    val fights: Int,
    val message: String,
    val messageArg: String,
    val ok: Boolean,
) : CustomPayload {
    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<GuiStatePayload>(Hha.id("gui_state"))

        val CODEC: PacketCodec<RegistryByteBuf, GuiStatePayload> = PacketCodec.of(
            { v, buf ->
                buf.writeBoolean(v.admin)
                buf.writeVarInt(v.toggles.size)
                v.toggles.forEach { (k, b) -> buf.writeString(k, 256); buf.writeBoolean(b); buf.writeBoolean(v.toggleDefaults[k] ?: b) }
                buf.writeVarInt(v.numbers.size)
                v.numbers.forEach { (k, d) -> buf.writeString(k, 256); buf.writeDouble(d); buf.writeDouble(v.numberDefaults[k] ?: d) }
                buf.writeVarInt(v.trusted.size)
                v.trusted.forEach { buf.writeString(it.uuid, 64); buf.writeString(it.name, 64); buf.writeBoolean(it.online) }
                buf.writeVarInt(v.kits.size)
                v.kits.forEach { buf.writeString(it.key, 128); buf.writeString(it.icon, 256); buf.writeString(it.weapon, 256) }
                buf.writeVarInt(v.fights)
                buf.writeString(v.message, 256)
                buf.writeString(v.messageArg, 256)
                buf.writeBoolean(v.ok)
            },
            { buf ->
                val admin = buf.readBoolean()
                val toggles = LinkedHashMap<String, Boolean>()
                val toggleDefaults = LinkedHashMap<String, Boolean>()
                repeat(buf.readVarInt()) {
                    val k = buf.readString(256)
                    toggles[k] = buf.readBoolean()
                    toggleDefaults[k] = buf.readBoolean()
                }
                val numbers = LinkedHashMap<String, Double>()
                val numberDefaults = LinkedHashMap<String, Double>()
                repeat(buf.readVarInt()) {
                    val k = buf.readString(256)
                    numbers[k] = buf.readDouble()
                    numberDefaults[k] = buf.readDouble()
                }
                val trusted = List(buf.readVarInt()) { GuiTrusted(buf.readString(64), buf.readString(64), buf.readBoolean()) }
                val kits = List(buf.readVarInt()) { GuiKit(buf.readString(128), buf.readString(256), buf.readString(256)) }
                GuiStatePayload(
                    admin, toggles, toggleDefaults, numbers, numberDefaults, trusted, kits,
                    buf.readVarInt(), buf.readString(256), buf.readString(256), buf.readBoolean(),
                )
            },
        )
    }
}
