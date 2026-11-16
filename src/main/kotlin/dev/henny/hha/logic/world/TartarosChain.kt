package dev.henny.hha.logic.world

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.datafixer.DataFixTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateType

/**
 * Die Kette zu Hades (Überarbeitung 2, PLAN 5.7): fünf Glieder, jeder besiegte Wächter sprengt eins; das Palastsiegel
 * öffnet erst bei fünf. Dazu, ob Hades (samt Kronos) besiegt ist. PersistentState `hha_tartaros_chain` an der
 * Tartaros-Welt (Rückfall Oberwelt). Die Liste befreiter Bauwerke in `TartarosMinibosses` bleibt daneben bestehen.
 * Wegweisung macht `TartarosGuide` (P12).
 */
object TartarosChain {

    /** Die Glieder; [key] ist der Fortschritts- und Boss-Schlüssel (`/hha progress set`, `BossRewards.grant`). */
    enum class Link(val key: String) {
        WARDEN("warden"),
        KAMPE("kampe"),
        SISYPHUS("sisyphus"),
        IXION("ixion"),
        TANTALUS("tantalus"),
        ;

        companion object {
            fun byKey(key: String): Link? = entries.firstOrNull { it.key == key.lowercase() }
        }
    }

    /** Schlüssel für Hades und Kronos in `/hha progress set`. */
    const val HADES = "hades"

    class State(broken: Collection<String> = emptyList(), hadesDone: Boolean = false) : PersistentState() {
        private val broken = LinkedHashSet(broken)

        /** Sind Hades und Kronos besiegt? */
        var hadesDone: Boolean = hadesDone
            set(value) {
                if (field != value) {
                    field = value
                    markDirty()
                }
            }

        fun isBroken(link: Link): Boolean = link.key in broken

        fun breakLink(link: Link) = set(link, true)

        fun set(link: Link, value: Boolean) {
            val changed = if (value) broken.add(link.key) else broken.remove(link.key)
            if (changed) markDirty()
        }

        /** Wie viele Glieder gesprengt sind (0–5). */
        fun brokenCount(): Int = Link.entries.count { isBroken(it) }

        /** Die noch ganzen Glieder. */
        fun missing(): List<Link> = Link.entries.filter { !isBroken(it) }

        companion object {
            val CODEC: Codec<State> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.STRING.listOf().fieldOf("broken").forGetter { it.broken.toList() },
                    Codec.BOOL.optionalFieldOf("hades", false).forGetter { it.hadesDone },
                ).apply(i) { b, h -> State(b, h) }
            }
        }
    }

    val TYPE = PersistentStateType("hha_tartaros_chain", { State() }, State.CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE)

    /** Welt, an der der Zustand hängt. */
    fun home(server: MinecraftServer): ServerWorld = server.getWorld(HadesGate.TARTAROS) ?: server.overworld

    fun of(server: MinecraftServer): State = home(server).persistentStateManager.getOrCreate(TYPE)

    fun isBroken(server: MinecraftServer, link: Link): Boolean = of(server).isBroken(link)

    fun breakLink(server: MinecraftServer, link: Link) = of(server).breakLink(link)

    fun set(server: MinecraftServer, link: Link, value: Boolean) = of(server).set(link, value)

    fun brokenCount(server: MinecraftServer): Int = of(server).brokenCount()
}
