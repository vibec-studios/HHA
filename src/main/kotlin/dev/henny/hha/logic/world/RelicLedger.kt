package dev.henny.hha.logic.world

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.datafixer.DataFixTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateType

/**
 * Reliquien-Register (Überarbeitung 2, PLAN 6.2): Jedes Rüstungs- und Waffenteil der beiden Sets fällt je Welt genau
 * einmal. PersistentState `hha_relics` an der Oberwelt. Geschrieben nur von `BossRewards` und den `/hha relics`-Befehlen;
 * Kits berühren es nie.
 */
object RelicLedger {

    /** Die zwölf Reliquien-IDs (gleich den Item-Pfaden). */
    val IDS: List<String> = listOf(
        "heaven_boots", "heaven_leggings", "heaven_helmet", "heaven_chestplate", "heavens_sword", "heavens_mace",
        "hell_boots", "hell_leggings", "hell_helmet", "hell_chestplate", "hells_sword", "hells_mace",
    )

    /** Ein gefallenes Teil: Zeitpunkt (Welt-Tick der Oberwelt), Boss-Schlüssel, Name des nächststehenden Spielers. */
    data class Entry(val time: Long, val boss: String, val player: String) {
        companion object {
            val CODEC: Codec<Entry> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.fieldOf("time").forGetter(Entry::time),
                    Codec.STRING.fieldOf("boss").forGetter(Entry::boss),
                    Codec.STRING.fieldOf("player").forGetter(Entry::player),
                ).apply(i, ::Entry)
            }
        }
    }

    class State(entries: Map<String, Entry> = emptyMap()) : PersistentState() {
        private val entries = LinkedHashMap(entries)

        /** Ist die Reliquie [id] in dieser Welt schon gefallen? */
        fun isDropped(id: String): Boolean = id in entries

        /** Trägt [id] als gefallen ein (Boss [boss], Spieler [player]); [time] = Welt-Tick. */
        fun mark(id: String, boss: String, player: String, time: Long) {
            entries[id] = Entry(time, boss, player)
            markDirty()
        }

        /** Löscht den Eintrag: Die Reliquie kann wieder fallen. */
        fun clear(id: String) {
            if (entries.remove(id) != null) markDirty()
        }

        /** Alle Einträge löschen. */
        fun clearAll() {
            if (entries.isNotEmpty()) {
                entries.clear()
                markDirty()
            }
        }

        /** Alle Einträge (Kopie), Schlüssel = Reliquien-ID. */
        fun all(): Map<String, Entry> = LinkedHashMap(entries)

        fun entry(id: String): Entry? = entries[id]

        companion object {
            val CODEC: Codec<State> = Codec.unboundedMap(Codec.STRING, Entry.CODEC).xmap({ State(it) }, { it.entries })
        }
    }

    val TYPE = PersistentStateType("hha_relics", { State() }, State.CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE)

    /** Das Register dieser Welt (immer an der Oberwelt). */
    fun of(server: MinecraftServer): State = server.overworld.persistentStateManager.getOrCreate(TYPE)

    fun isDropped(server: MinecraftServer, id: String): Boolean = of(server).isDropped(id)

    fun mark(server: MinecraftServer, id: String, boss: String, player: String) =
        of(server).mark(id, boss, player, server.overworld.time)

    fun clear(server: MinecraftServer, id: String) = of(server).clear(id)

    fun all(server: MinecraftServer): Map<String, Entry> = of(server).all()
}
