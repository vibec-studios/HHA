package dev.henny.hha.logic.world

import com.mojang.serialization.Codec
import dev.henny.hha.world.olymp.OlympusLayout
import net.minecraft.datafixer.DataFixTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateType

/**
 * Fortschritt des Olymp-Pfads, weltweit (ein Server, eine Geschichte; PLAN 2). PersistentState `hha_olympus_progress`
 * an der Olymp-Welt; fehlt die Dimension (Datenpaket noch nicht da), an der Oberwelt.
 */
object OlympusProgress {

    const val HERMES = "hermes"
    const val HEKATE = "hekate"
    const val BRONTES = "brontes"
    const val KRATOS = "kratos"
    const val ZEUS = "zeus"

    /** Alle Schlüssel in Pfad-Reihenfolge. */
    val KEYS: List<String> = listOf(HERMES, HEKATE, BRONTES, KRATOS, ZEUS)

    class State(done: Collection<String> = emptyList()) : PersistentState() {
        private val done = LinkedHashSet(done)

        fun isDone(key: String): Boolean = key in done

        fun markDone(key: String) = set(key, true)

        fun set(key: String, value: Boolean) {
            val changed = if (value) done.add(key) else done.remove(key)
            if (changed) markDirty()
        }

        /** Alle erledigten Schlüssel (Kopie). */
        fun done(): Set<String> = LinkedHashSet(done)

        companion object {
            val CODEC: Codec<State> = Codec.STRING.listOf().xmap({ State(it) }, { it.done.toList() })
        }
    }

    val TYPE = PersistentStateType("hha_olympus_progress", { State() }, State.CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE)

    /** Welt, an der der Zustand hängt. */
    fun home(server: MinecraftServer): ServerWorld = server.getWorld(OlympusLayout.WORLD) ?: server.overworld

    fun of(server: MinecraftServer): State = home(server).persistentStateManager.getOrCreate(TYPE)

    fun isDone(server: MinecraftServer, key: String): Boolean = of(server).isDone(key)

    fun markDone(server: MinecraftServer, key: String) = of(server).markDone(key)

    fun set(server: MinecraftServer, key: String, value: Boolean) = of(server).set(key, value)
}
