package dev.henny.hha.logic.world

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.datafixer.DataFixTypes
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Identifier
import net.minecraft.util.Uuids
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateType
import net.minecraft.world.World
import java.util.UUID

/**
 * Gemerkte Tore (PLAN 7.1/7.4). PersistentState `hha_portal_links` an der Oberwelt:
 * - je Spieler sein **Heimtor**: das Wolkentor, durch das er zuletzt in den Olymp ging (Dimension, Zelle, Achse, Seite);
 * - je **Rücktor im Tartaros** (x/z der Tormitte) das **Herkunftstor**, durch das man hinunterkam.
 */
object PortalLinks {

    /**
     * Ein Tor: [dimension] und eine Zelle [pos] der Öffnung, [axis] der Ebene; [side] = +1/−1, auf welcher Seite der
     * Ebene (entlang der Normalen) man ankommen soll.
     */
    data class Gate(val dimension: Identifier, val pos: BlockPos, val axis: Direction.Axis, val side: Int) {

        val worldKey: RegistryKey<World> get() = RegistryKey.of(RegistryKeys.WORLD, dimension)

        companion object {
            val CODEC: Codec<Gate> = RecordCodecBuilder.create { i ->
                i.group(
                    Identifier.CODEC.fieldOf("dimension").forGetter(Gate::dimension),
                    BlockPos.CODEC.fieldOf("pos").forGetter(Gate::pos),
                    Codec.STRING.xmap({ if (it == "z") Direction.Axis.Z else Direction.Axis.X }, { it.asString() })
                        .fieldOf("axis").forGetter(Gate::axis),
                    Codec.INT.optionalFieldOf("side", 1).forGetter(Gate::side),
                ).apply(i, ::Gate)
            }

            fun of(world: World, pos: BlockPos, axis: Direction.Axis, side: Int): Gate =
                Gate(world.registryKey.value, pos.toImmutable(), axis, if (side < 0) -1 else 1)
        }
    }

    /** Schlüssel eines Rücktors im Tartaros: nur x/z der Tormitte (die Höhe sucht `HadesGate` selbst). */
    private fun returnKey(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    class State(homes: Map<UUID, Gate> = emptyMap(), returns: Map<Long, Gate> = emptyMap()) : PersistentState() {
        private val homes = HashMap(homes)
        private val returns = HashMap(returns)

        fun home(player: UUID): Gate? = homes[player]

        fun setHome(player: UUID, gate: Gate) {
            if (homes[player] != gate) {
                homes[player] = gate
                markDirty()
            }
        }

        /** Herkunftstor zum Rücktor mit der Mitte ([x], [z]) im Tartaros. */
        fun origin(x: Int, z: Int): Gate? = returns[returnKey(x, z)]

        fun setOrigin(x: Int, z: Int, gate: Gate) {
            val key = returnKey(x, z)
            if (returns[key] != gate) {
                returns[key] = gate
                markDirty()
            }
        }

        companion object {
            private data class ReturnEntry(val key: Long, val gate: Gate)

            private val RETURN_CODEC: Codec<ReturnEntry> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.LONG.fieldOf("key").forGetter(ReturnEntry::key),
                    Gate.CODEC.fieldOf("gate").forGetter(ReturnEntry::gate),
                ).apply(i, ::ReturnEntry)
            }

            val CODEC: Codec<State> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.unboundedMap(Uuids.STRING_CODEC, Gate.CODEC).optionalFieldOf("homes", emptyMap())
                        .forGetter { it.homes },
                    RETURN_CODEC.listOf().optionalFieldOf("returns", emptyList())
                        .forGetter { s -> s.returns.map { (k, v) -> ReturnEntry(k, v) } },
                ).apply(i) { homes, returns -> State(homes, returns.associate { it.key to it.gate }) }
            }
        }
    }

    val TYPE = PersistentStateType("hha_portal_links", { State() }, State.CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE)

    fun of(server: MinecraftServer): State = server.overworld.persistentStateManager.getOrCreate(TYPE)
}
