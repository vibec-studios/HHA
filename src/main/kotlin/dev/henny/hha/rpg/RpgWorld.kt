package dev.henny.hha.rpg

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.datafixer.DataFixTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateType
import java.util.UUID

/**
 * Zustand einer RPG-Welt (an der Oberwelt): ob es eine ist, der gewählte Weg, die gewählten Addons, der Lobby-Code
 * und wer seinen Start schon bekommen hat. Normale Welten haben `enabled = false`.
 */
object RpgWorld {

    class State(
        enabled: Boolean = false,
        path: String = "",
        addons: List<String> = emptyList(),
        code: String = "",
        started: List<String> = emptyList(),
    ) : PersistentState() {
        var enabled = enabled
            private set
        var path = path
            private set
        var addons: List<String> = addons
            private set
        var code = code
            private set
        private val started = LinkedHashSet(started)

        fun setup(path: String, addons: List<String>, code: String) {
            enabled = true
            this.path = path
            this.addons = addons.toList()
            this.code = code
            markDirty()
        }

        fun hasStarted(id: UUID): Boolean = id.toString() in started

        fun markStarted(id: UUID) {
            if (started.add(id.toString())) markDirty()
        }

        companion object {
            val CODEC: Codec<State> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.BOOL.optionalFieldOf("enabled", false).forGetter { it.enabled },
                    Codec.STRING.optionalFieldOf("path", "").forGetter { it.path },
                    Codec.STRING.listOf().optionalFieldOf("addons", emptyList()).forGetter { it.addons },
                    Codec.STRING.optionalFieldOf("code", "").forGetter { it.code },
                    Codec.STRING.listOf().optionalFieldOf("started", emptyList()).forGetter { it.started.toList() },
                ).apply(i) { e, p, a, c, s -> State(e, p, a, c, s) }
            }
        }
    }

    val TYPE = PersistentStateType("hha_rpg", { State() }, State.CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE)

    fun of(server: MinecraftServer): State = server.overworld.persistentStateManager.getOrCreate(TYPE)

    /**
     * Übergabe vom Client des Hosts an seinen eigenen integrierten Server: Der Lobby-Bildschirm legt die Wahl hier ab,
     * bevor er die Welt erzeugt; beim Serverstart wird sie in den Weltzustand geschrieben.
     */
    @Volatile
    var pending: Setup? = null

    class Setup(val path: String, val addons: List<String>, val code: String)
}
