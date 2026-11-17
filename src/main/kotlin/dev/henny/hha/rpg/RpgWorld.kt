package dev.henny.hha.rpg

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.datafixer.DataFixTypes
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Identifier
import net.minecraft.util.math.Vec3d
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateType
import net.minecraft.world.World
import java.util.UUID

/**
 * Zustand einer RPG-Welt (an der Oberwelt): ob es eine ist, der gewählte Weg, die gewählten Addons, der Lobby-Code
 * und wer seinen Start schon bekommen hat. Normale Welten haben `enabled = false`.
 *
 * Überarbeitung 3 (P06): dazu die Geschichte der Welt ([Story], im Speicherstand unter `story`, **ganz optional** –
 * ältere RPG-Welten laden mit leerer Geschichte): erwartete Lobby-Mitglieder, ob die Gruppe angekommen ist, besuchte
 * Orte, Rast je Spieler, Tagebuch, besiegte Bosse, Tode, Startzeit, Gepäck, gemeldete Kapitel.
 * Fortschritt gilt je Welt; Rast, Gepäck und „schon gerastet an“ je Spieler.
 */
object RpgWorld {

    /** Eine Rast: Sprachschlüssel des Orts ([key], `hha.rpg.rest.*`), Welt, Lage (Füße) und Blick. */
    class Rest(val key: String, val world: Identifier, val pos: Vec3d, val yaw: Float) {
        val worldKey: RegistryKey<World> get() = RegistryKey.of(RegistryKeys.WORLD, world)

        fun toArrival(): Arrival = Arrival(worldKey, pos, yaw)

        companion object {
            val CODEC: Codec<Rest> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.STRING.optionalFieldOf("key", "").forGetter { it.key },
                    Identifier.CODEC.fieldOf("world").forGetter { it.world },
                    Vec3d.CODEC.fieldOf("pos").forGetter { it.pos },
                    Codec.FLOAT.optionalFieldOf("yaw", 0f).forGetter { it.yaw },
                ).apply(i) { k, w, p, y -> Rest(k, w, p, y) }
            }
        }
    }

    /**
     * Unveränderliche Momentaufnahme der Geschichte (nur für den Codec; der laufende Zustand liegt in [State]).
     * Jedes Feld ist optional.
     */
    class Story(
        val expected: List<String> = emptyList(),
        val arrived: Boolean = false,
        val visited: List<String> = emptyList(),
        val rests: Map<String, Rest> = emptyMap(),
        val seen: Map<String, List<String>> = emptyMap(),
        val journal: List<String> = emptyList(),
        val defeated: List<String> = emptyList(),
        val deaths: Int = 0,
        val startedAt: Long = -1L,
        val kit: List<String> = emptyList(),
        val chapters: List<String> = emptyList(),
        val current: String = "",
        val ended: Boolean = false,
    ) {
        companion object {
            val CODEC: Codec<Story> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.STRING.listOf().optionalFieldOf("expected", emptyList()).forGetter { it.expected },
                    Codec.BOOL.optionalFieldOf("arrived", false).forGetter { it.arrived },
                    Codec.STRING.listOf().optionalFieldOf("visited", emptyList()).forGetter { it.visited },
                    Codec.unboundedMap(Codec.STRING, Rest.CODEC).optionalFieldOf("rests", emptyMap()).forGetter { it.rests },
                    Codec.unboundedMap(Codec.STRING, Codec.STRING.listOf()).optionalFieldOf("seen", emptyMap()).forGetter { it.seen },
                    Codec.STRING.listOf().optionalFieldOf("journal", emptyList()).forGetter { it.journal },
                    Codec.STRING.listOf().optionalFieldOf("defeated", emptyList()).forGetter { it.defeated },
                    Codec.INT.optionalFieldOf("deaths", 0).forGetter { it.deaths },
                    Codec.LONG.optionalFieldOf("started_at", -1L).forGetter { it.startedAt },
                    Codec.STRING.listOf().optionalFieldOf("kit", emptyList()).forGetter { it.kit },
                    Codec.STRING.listOf().optionalFieldOf("chapters", emptyList()).forGetter { it.chapters },
                    Codec.STRING.optionalFieldOf("current", "").forGetter { it.current },
                    Codec.BOOL.optionalFieldOf("ended", false).forGetter { it.ended },
                ).apply(i) { e, a, v, r, s, j, d, de, st, k, c, cu, en -> Story(e, a, v, r, s, j, d, de, st, k, c, cu, en) }
            }
        }
    }

    class State(
        enabled: Boolean = false,
        path: String = "",
        addons: List<String> = emptyList(),
        code: String = "",
        started: List<String> = emptyList(),
        story: Story = Story(),
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

        // ---- Geschichte (Überarbeitung 3) -------------------------------------------------------

        private val expectedSet = LinkedHashSet(story.expected)
        private val visitedSet = LinkedHashSet(story.visited)
        private val restMap = LinkedHashMap(story.rests)
        private val seenMap = LinkedHashMap<String, LinkedHashSet<String>>().apply {
            for ((k, v) in story.seen) put(k, LinkedHashSet(v))
        }
        private val journalList = ArrayList(story.journal.distinct())
        private val defeatedSet = LinkedHashSet(story.defeated)
        private val kitSet = LinkedHashSet(story.kit)
        private val chapterSet = LinkedHashSet(story.chapters)

        /** UUIDs (Text) aller Lobby-Mitglieder, auf die die Ankunft wartet (vom Host-Client, PLAN 4.4). */
        val expected: List<String> get() = expectedSet.toList()

        /** Ist die Gruppe angekommen (Ankunftsszene gestartet)? Danach kommt niemand mehr in die Halte. */
        var arrived: Boolean = story.arrived
            set(value) {
                if (field != value) {
                    field = value
                    markDirty()
                }
            }

        /** Orte, die in dieser Welt schon jemand betreten hat (`isle.b`, `tartaros`, `site.warden`, `palace` …). */
        val visited: Set<String> get() = LinkedHashSet(visitedSet)

        /** Rast je Spieler (UUID als Text). */
        val rests: Map<String, Rest> get() = LinkedHashMap(restMap)

        /** Tagebuch: `<boss>` beim ersten Betreten seines Orts, `<boss>.done` beim Sieg (Reihenfolge des Eintrags). */
        val journal: List<String> get() = journalList.toList()

        /** Besiegte Bosse (Schlüssel wie `BossRewards`, dazu `kronos`). */
        val defeated: Set<String> get() = LinkedHashSet(defeatedSet)

        /** Wie oft in dieser Welt jemand gefallen ist. */
        var deaths: Int = story.deaths
            private set

        /** Weltzeit (`ServerWorld.time` der Oberwelt) bei der Ankunft; −1 = noch nicht. */
        var startedAt: Long = story.startedAt
            set(value) {
                if (field != value) {
                    field = value
                    markDirty()
                }
            }

        /** Schon gemeldete fertige Kapitel (damit `onChapterDone` je Welt einmal läuft). */
        val chaptersDone: Set<String> get() = LinkedHashSet(chapterSet)

        /** Zuletzt begonnenes Kapitel (damit `onChapterBegin` nach einem Neustart nicht erneut läuft). */
        var currentChapter: String = story.current
            set(value) {
                if (field != value) {
                    field = value
                    markDirty()
                }
            }

        /** Endszene gespielt (Epilog fertig, `RpgStory.finishStory`). */
        var ended: Boolean = story.ended
            set(value) {
                if (field != value) {
                    field = value
                    markDirty()
                }
            }

        fun setup(path: String, addons: List<String>, code: String) = setup(path, addons, code, emptyList())

        fun setup(path: String, addons: List<String>, code: String, expected: List<String>) {
            enabled = true
            this.path = path
            this.addons = addons.toList()
            this.code = code
            expectedSet.clear()
            expectedSet.addAll(expected.filter { it.isNotBlank() })
            markDirty()
        }

        fun hasStarted(id: UUID): Boolean = id.toString() in started

        fun markStarted(id: UUID) {
            if (started.add(id.toString())) markDirty()
        }

        /** Wie viele Spieler ihren Start schon bekommen haben. */
        fun startedCount(): Int = started.size

        fun isExpected(id: UUID): Boolean = id.toString() in expectedSet

        /** Erstes Betreten von [place] in dieser Welt? (true = neu eingetragen) */
        fun visit(place: String): Boolean = visitedSet.add(place).also { if (it) markDirty() }

        fun forgetVisit(place: String) {
            if (visitedSet.remove(place)) markDirty()
        }

        fun restOf(id: UUID): Rest? = restMap[id.toString()]

        fun setRest(id: UUID, rest: Rest) {
            restMap[id.toString()] = rest
            markDirty()
        }

        /** Hat [id] an [place] schon seine Orts-Rast bekommen? */
        fun hasRested(id: UUID, place: String): Boolean = seenMap[id.toString()]?.contains(place) == true

        /** Merkt die Orts-Rast; true = neu. */
        fun markRested(id: UUID, place: String): Boolean =
            seenMap.getOrPut(id.toString()) { LinkedHashSet() }.add(place).also { if (it) markDirty() }

        /** Tagebuch-Eintrag; true = neu. */
        fun addJournal(entry: String): Boolean {
            if (entry in journalList) return false
            journalList.add(entry)
            markDirty()
            return true
        }

        fun removeJournal(entry: String) {
            if (journalList.remove(entry)) markDirty()
        }

        fun isDefeated(key: String): Boolean = key in defeatedSet

        /** Sieg eintragen; true = neu. */
        fun defeat(key: String): Boolean = defeatedSet.add(key).also { if (it) markDirty() }

        fun undefeat(key: String) {
            if (defeatedSet.remove(key)) markDirty()
        }

        fun addDeath() {
            deaths++
            markDirty()
        }

        fun hasKit(id: UUID): Boolean = id.toString() in kitSet

        /** Gepäck vergeben; true = neu (war noch nicht). */
        fun markKit(id: UUID): Boolean = kitSet.add(id.toString()).also { if (it) markDirty() }

        fun isChapterDone(id: String): Boolean = id in chapterSet

        fun markChapterDone(id: String): Boolean = chapterSet.add(id).also { if (it) markDirty() }

        fun clearChapterDone(id: String) {
            if (chapterSet.remove(id)) markDirty()
        }

        internal fun story(): Story = Story(
            expectedSet.toList(), arrived, visitedSet.toList(), LinkedHashMap(restMap),
            seenMap.mapValues { it.value.toList() }, journalList.toList(), defeatedSet.toList(), deaths, startedAt,
            kitSet.toList(), chapterSet.toList(), currentChapter, ended,
        )

        companion object {
            val CODEC: Codec<State> = RecordCodecBuilder.create { i ->
                i.group(
                    Codec.BOOL.optionalFieldOf("enabled", false).forGetter { it.enabled },
                    Codec.STRING.optionalFieldOf("path", "").forGetter { it.path },
                    Codec.STRING.listOf().optionalFieldOf("addons", emptyList()).forGetter { it.addons },
                    Codec.STRING.optionalFieldOf("code", "").forGetter { it.code },
                    Codec.STRING.listOf().optionalFieldOf("started", emptyList()).forGetter { it.started.toList() },
                    Story.CODEC.optionalFieldOf("story", Story()).forGetter { it.story() },
                ).apply(i) { e, p, a, c, s, st -> State(e, p, a, c, s, st) }
            }
        }
    }

    val TYPE = PersistentStateType("hha_rpg", { State() }, State.CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE)

    fun of(server: MinecraftServer): State = server.overworld.persistentStateManager.getOrCreate(TYPE)

    /** Kurz für `of(server).enabled` – der RPG-Schalter der Serverseite. */
    fun enabled(server: MinecraftServer): Boolean = of(server).enabled

    /**
     * Übergabe vom Client des Hosts an seinen eigenen integrierten Server: Der Lobby-Bildschirm legt die Wahl hier ab,
     * bevor er die Welt erzeugt; beim Serverstart wird sie in den Weltzustand geschrieben.
     */
    @Volatile
    var pending: Setup? = null

    /** [expected]: UUIDs aller Lobby-Mitglieder (Text), auf die die Ankunft wartet (Überarbeitung 3, PLAN 4.4). */
    class Setup @JvmOverloads constructor(
        val path: String,
        val addons: List<String>,
        val code: String,
        val expected: List<String> = emptyList(),
    )
}
