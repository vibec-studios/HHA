package dev.henny.hha.net

import dev.henny.hha.Hha
import net.minecraft.network.RegistryByteBuf
import net.minecraft.network.codec.PacketCodec
import net.minecraft.network.packet.CustomPayload

/*
 * Netz des RPG-Modus (Überarbeitung 3, PLAN 6.1 und 3.6). Server: Paket `rpg` (P06), Client: `client/rpg/game/RpgClientState`.
 * Alle Aufzählungen über die Leitung (Phasen, Ereignis-Arten, Client-Arten) wachsen **nur hinten**.
 */

private fun RegistryByteBuf.writeStrings(list: List<String>) {
    writeVarInt(list.size)
    for (s in list) writeString(s)
}

private fun RegistryByteBuf.readStrings(): List<String> {
    val n = readVarInt()
    return List(n) { readString() }
}

/**
 * S2C: Zustand der RPG-Welt für diesen Spieler (beim Beitritt, bei Änderung, sonst alle 100 Ticks).
 * [journal] enthält `<boss>` (Erstkontakt) und `<boss>.done` (Sieg). [objectiveKey] mit [objectiveArgs] ergibt den
 * Zieltext; [hasTarget] sagt, ob [tx]/[ty]/[tz] in [targetWorld] ein Ziel für den Richtungspfeil sind.
 */
data class RpgStatePayload(
    val enabled: Boolean,
    val path: String,
    /** [PHASE_PLAY], [PHASE_HOLD], [PHASE_ARRIVE]. */
    val phase: Int,
    val chapter: String,
    val chapterIndex: Int,
    val objectiveKey: String,
    val objectiveArgs: List<String>,
    val hasTarget: Boolean,
    val targetWorld: String,
    val tx: Double,
    val ty: Double,
    val tz: Double,
    /** Namen derer, auf die die Halte noch wartet. */
    val waiting: List<String>,
    /** Erledigte Kapitel-IDs. */
    val done: List<String>,
    val journal: List<String>,
    /** Sprachschlüssel der letzten Rast (leer = keine). */
    val restKey: String,
) : CustomPayload {

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        const val PHASE_PLAY = 0
        const val PHASE_HOLD = 1
        const val PHASE_ARRIVE = 2

        /** Zustand außerhalb jeder RPG-Welt (normaler Modus). */
        val DISABLED = RpgStatePayload(
            false, "", PHASE_PLAY, "", 0, "", emptyList(), false, "", 0.0, 0.0, 0.0,
            emptyList(), emptyList(), emptyList(), "",
        )

        val ID = CustomPayload.Id<RpgStatePayload>(Hha.id("rpg_state"))
        val CODEC: PacketCodec<RegistryByteBuf, RpgStatePayload> = PacketCodec.of(
            { v, buf ->
                buf.writeBoolean(v.enabled)
                buf.writeString(v.path)
                buf.writeVarInt(v.phase)
                buf.writeString(v.chapter)
                buf.writeVarInt(v.chapterIndex)
                buf.writeString(v.objectiveKey)
                buf.writeStrings(v.objectiveArgs)
                buf.writeBoolean(v.hasTarget)
                buf.writeString(v.targetWorld)
                buf.writeDouble(v.tx)
                buf.writeDouble(v.ty)
                buf.writeDouble(v.tz)
                buf.writeStrings(v.waiting)
                buf.writeStrings(v.done)
                buf.writeStrings(v.journal)
                buf.writeString(v.restKey)
            },
            { buf ->
                RpgStatePayload(
                    buf.readBoolean(), buf.readString(), buf.readVarInt(), buf.readString(), buf.readVarInt(),
                    buf.readString(), buf.readStrings(), buf.readBoolean(), buf.readString(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readStrings(), buf.readStrings(), buf.readStrings(), buf.readString(),
                )
            },
        )
    }
}

/** S2C: Gruppenleisten (alle 10 Ticks, wenn sich etwas geändert hat). */
data class RpgPartyPayload(val members: List<Member>) : CustomPayload {

    /** Ein Mitspieler; [world] ist die Registry-ID der Dimension, [down] = gefallen. */
    data class Member(
        val uuid: String,
        val name: String,
        val health: Float,
        val maxHealth: Float,
        val down: Boolean,
        val world: String,
        val x: Double,
        val y: Double,
        val z: Double,
    )

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        val ID = CustomPayload.Id<RpgPartyPayload>(Hha.id("rpg_party"))
        val CODEC: PacketCodec<RegistryByteBuf, RpgPartyPayload> = PacketCodec.of(
            { v, buf ->
                buf.writeVarInt(v.members.size)
                for (m in v.members) {
                    buf.writeString(m.uuid)
                    buf.writeString(m.name)
                    buf.writeFloat(m.health)
                    buf.writeFloat(m.maxHealth)
                    buf.writeBoolean(m.down)
                    buf.writeString(m.world)
                    buf.writeDouble(m.x)
                    buf.writeDouble(m.y)
                    buf.writeDouble(m.z)
                }
            },
            { buf ->
                val n = buf.readVarInt()
                RpgPartyPayload(
                    List(n) {
                        Member(
                            buf.readString(), buf.readString(), buf.readFloat(), buf.readFloat(), buf.readBoolean(),
                            buf.readString(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                        )
                    },
                )
            },
        )
    }
}

/** S2C: ein Ereignis für Oberflächen (Toast, Tagebuch, Tod, Rast, Abspann). Bedeutung von [numbers]/[texts] je [kind]. */
data class RpgEventPayload(val kind: Int, val numbers: List<Double>, val texts: List<String>) : CustomPayload {

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        const val TOAST = 0
        const val JOURNAL = 1
        const val FALLEN = 2
        const val REST = 3
        const val CREDITS = 4

        val ID = CustomPayload.Id<RpgEventPayload>(Hha.id("rpg_event"))
        val CODEC: PacketCodec<RegistryByteBuf, RpgEventPayload> = PacketCodec.of(
            { v, buf ->
                buf.writeVarInt(v.kind)
                buf.writeVarInt(v.numbers.size)
                for (n in v.numbers) buf.writeDouble(n)
                buf.writeStrings(v.texts)
            },
            { buf ->
                val kind = buf.readVarInt()
                val n = buf.readVarInt()
                val numbers = List(n) { buf.readDouble() }
                RpgEventPayload(kind, numbers, buf.readStrings())
            },
        )
    }
}

/** C2S: Meldung des Clients an die RPG-Welt (z. B. „mein Ladebildschirm ist zu“). */
data class RpgClientPayload(val kind: Int, val value: Int) : CustomPayload {

    override fun getId(): CustomPayload.Id<out CustomPayload> = ID

    companion object {
        /** Der Client ist in der Welt und sein Ladebildschirm ist geschlossen (Halte, PLAN 4.3/4.4). */
        const val READY = 0

        val ID = CustomPayload.Id<RpgClientPayload>(Hha.id("rpg_client"))
        val CODEC: PacketCodec<RegistryByteBuf, RpgClientPayload> = PacketCodec.of(
            { v, buf ->
                buf.writeVarInt(v.kind)
                buf.writeVarInt(v.value)
            },
            { buf -> RpgClientPayload(buf.readVarInt(), buf.readVarInt()) },
        )
    }
}
