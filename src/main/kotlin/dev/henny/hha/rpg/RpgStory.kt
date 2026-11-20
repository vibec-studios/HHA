package dev.henny.hha.rpg

import dev.henny.hha.Hha
import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.BossRewards
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.HadesFights
import dev.henny.hha.logic.boss.HermesFights
import dev.henny.hha.logic.world.Approach
import dev.henny.hha.logic.world.HadesGate
import dev.henny.hha.logic.world.OlympusPath
import dev.henny.hha.logic.world.OlympusProgress
import dev.henny.hha.logic.world.TartarosChain
import dev.henny.hha.logic.world.TartarosGuide
import dev.henny.hha.logic.world.TartarosMinibosses
import dev.henny.hha.net.RpgEventPayload
import dev.henny.hha.world.TartarusArenas
import dev.henny.hha.world.TartarusKind
import dev.henny.hha.world.TartarusPiece
import dev.henny.hha.world.TartarusPlans
import dev.henny.hha.world.TartarusStructure
import dev.henny.hha.world.olymp.OlympusArenas
import dev.henny.hha.world.olymp.OlympusLayout
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.entry.RegistryEntryList
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import net.minecraft.world.chunk.ChunkStatus
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Geschichte in Kapiteln (PLAN 3.3), **abgeleitet aus dem vorhandenen Weltfortschritt** (`OlympusProgress`,
 * `TartarosChain`, `BossRewards.onGrant`, laufende Kämpfe). Kein Kampf kennt das RPG. Alles nur in RPG-Welten.
 *
 * - Kapitel je Weg als Daten ([HEAVEN], [HELL], Addon-Wege: ein freies Kapitel). [current] = erstes nicht fertiges.
 * - Je Kapitel kleine Zustandsfunktion für das Ziel ([goalFor]): Ort erreicht → Kampf läuft → besiegt, dazu der
 *   Zielpunkt für den Richtungspfeil (Inseln aus `OlympusLayout`, im Tartaros das nächste ganze Glied bzw. der Palast).
 * - Orte (`isle.a` … `isle.f`, `tartaros`, `site.<glied>`, `palace`): [onPlace] läuft **einmal je Welt und Ort** (der Ort
 *   steht beim Aufruf schon in `RpgWorld.State.visited`); die Orts-Rast je Spieler macht [RpgRests.onPlace].
 * - Tagebuch: `<boss>` beim ersten Betreten seines Orts, `<boss>.done` beim Sieg (mit `<boss>`, falls der fehlte),
 *   jeweils `RpgEventPayload(JOURNAL)` an alle mit texts = (Eintrag).
 * - Siege über `BossRewards.onGrant` (schreibt `defeated`, Rast) und zusätzlich aus den Zuständen, damit
 *   `/hha progress set` wirkt. `hades` aus `onGrant` heißt „Hades und Kronos“ (Kronos hat keinen eigenen Schlüssel);
 *   Hades allein gilt als besiegt, sobald sein Kampf in Kronos' Phase (Stufe ≥ 4) ist.
 */
object RpgStory {

    /** Ein Kapitel: [id] (`prologue`, `h1_word` …) und die Schlüssel seiner Kapitelkarte. */
    class Chapter(val id: String, val kickerKey: String, val titleKey: String, val subKey: String)

    /** Zielpunkt für den Richtungspfeil. */
    class Target(val world: RegistryKey<World>, val pos: Vec3d)

    /** Ziel des laufenden Kapitels für einen Spieler: Text [key] mit [args], dazu [target] (oder keins). */
    class Goal(val key: String, val args: List<String> = emptyList(), val target: Target? = null)

    private class Def(
        val chapter: Chapter,
        val done: (MinecraftServer) -> Boolean,
        val goal: (MinecraftServer, ServerPlayerEntity) -> Goal?,
    )

    const val PROLOGUE = "prologue"
    const val H1 = "h1_word"
    const val H2 = "h2_work"
    const val H3 = "h3_force"
    const val H4 = "h4_fear"
    const val U0 = "u0_threshold"
    const val U1 = "u1_chain"
    const val U2 = "u2_lord"
    const val U3 = "u3_titan"
    const val EPILOGUE = "epilogue"
    const val FREE = "free"

    /** Wie oft Kapitel, Siege und Ereignisse geprüft werden. */
    const val STORY_TICKS = 20

    /** Wie oft Orte geprüft werden. */
    const val PLACE_TICKS = 10

    /** Insel betreten: so weit unter … (das Fenster selbst steht in `OlympusPath`, Fernsäule und Pfeil teilen es) */
    const val ISLE_BELOW = OlympusPath.ISLE_BELOW

    /** … bis so weit über ihrem Boden. */
    const val ISLE_ABOVE = OlympusPath.ISLE_ABOVE

    /** Suche nach Bauwerken im Tartaros: höchstens alle so viele Ticks je Ziel. */
    const val SEARCH_COOLDOWN = 600

    /** Suchradius in Chunks (wie der Wegweiser). */
    const val SEARCH_CHUNKS = 100

    /** Glieder der Kette insgesamt. */
    private val LINKS = TartarosChain.Link.entries.size

    private val doneListeners = ArrayList<(MinecraftServer, Chapter) -> Unit>()
    private val beginListeners = ArrayList<(MinecraftServer, Chapter) -> Unit>()
    private val placeListeners = ArrayList<(MinecraftServer, String, ServerPlayerEntity) -> Unit>()

    private fun chapter(id: String, kickerSuffix: String = "") =
        Chapter(id, "hha.rpg.story.$id.kicker$kickerSuffix", "hha.rpg.story.$id.title", "hha.rpg.story.$id.sub")

    private fun goalKey(id: String, n: Int) = "hha.rpg.story.$id.goal.$n"

    // ---------------------------------------------------------------------------------------------
    // Kapitel als Daten
    // ---------------------------------------------------------------------------------------------

    private val prologue = Def(chapter(PROLOGUE), { s -> RpgWorld.of(s).arrived && !RpgArrival.isArriving(s) }) { _, _ -> null }

    /**
     * Kapitel einer Insel (Überarbeitung 4, P23): Im Kampf die Mitte; sonst führt der Pfeil **je Spieler** über die
     * Wegpunkte der Brücken (`OlympusPath.wayTo`: Brückenkopf → Engstelle → Kopftor → Inseleingang, auf der Insel der
     * Altar). Der Zieltext ist gemeinsam: Ist die Insel besucht, `goal.1`; sonst der Schritt des am weitesten
     * fortgeschrittenen Spielers – auf der letzten Brücke `way.<n>`, davor `goal.0`.
     */
    private fun isle(id: String, isle: OlympusLayout.Isle, fight: (MinecraftServer) -> Boolean) = Def(
        chapter(id), { s -> OlympusProgress.isDone(s, isle.key!!) },
    ) { s, p ->
        val place = placeOf(isle)
        val step = OlympusPath.wayTo(p, isle)
        val target = Target(OlympusLayout.WORLD, step?.pos ?: OlympusPath.isleGoal(isle))
        when {
            fight(s) -> Goal(goalKey(id, 2), target = Target(OlympusLayout.WORLD, isle.centerVec))
            place in RpgWorld.of(s).visited -> Goal(goalKey(id, 1), target = target)
            else -> Goal(wayText(id, leadStep(s, isle) ?: step), target = target)
        }
    }

    private fun wayKey(id: String, n: Int) = "hha.rpg.story.$id.way.$n"

    /** Zieltext eines Inselschritts: auf der letzten Brücke `way.<n>` (0 … 3), davor `goal.0`. */
    private fun wayText(id: String, step: OlympusPath.WayStep?): String =
        if (step != null && step.final && step.n <= OlympusPath.STEP_INTO) wayKey(id, step.n) else goalKey(id, 0)

    /** Der am weitesten fortgeschrittene Spieler im Olymp auf dem Weg zu [isle] (Mehrspieler, gemeinsamer Zieltext). */
    private fun leadStep(server: MinecraftServer, isle: OlympusLayout.Isle): OlympusPath.WayStep? {
        val state = RpgWorld.of(server)
        val world = server.getWorld(OlympusLayout.WORLD) ?: return null
        return world.players.filter { !it.isSpectator && state.hasStarted(it.uuid) }
            .mapNotNull { OlympusPath.wayTo(it, isle) }.maxByOrNull { it.rank }
    }

    private fun arena(boss: String): (MinecraftServer) -> Boolean = { s -> Arenas.byBoss(s, boss) != null }

    private val hermesFight: (MinecraftServer) -> Boolean = { s -> s.getWorld(OlympusLayout.WORLD)?.let { HermesFights.of(it) } != null }

    private fun chain(suffix: String) = Def(chapter(U1, suffix), ::chainDone) { s, p ->
        val world = p.entityWorld as? ServerWorld
        if (world != null && world.registryKey == HadesGate.TARTAROS) {
            val n = TartarosChain.brokenCount(s)
            Goal(goalKey(U1, 1), listOf(n.toString(), LINKS.toString()), nextLink(world, p)?.let { Target(HadesGate.TARTAROS, it) })
        } else {
            Goal(goalKey(U1, 0), target = gateTarget())
        }
    }

    /**
     * Hades (U2): im Kampf die Saalmitte (`goal.1`); sonst der nächste nicht erreichte Punkt des Palast-Anmarschs
     * (`TartarusArenas.palaceApproach`: Allee → Propylon → Gang der Schatten → Richter → Styx → Hundetor → Siegel) mit
     * `way.<n>`. Der Text folgt dem am weitesten fortgeschrittenen Spieler, der Pfeil jedem selbst.
     */
    private fun lord(suffix: String) = Def(chapter(U2, suffix), ::hadesDone) { s, p ->
        val world = p.entityWorld as? ServerWorld
        if (world != null && world.registryKey == HadesGate.TARTAROS) {
            val o = palace(world, p.blockPos)
            if (HadesFights.of(world) != null) {
                Goal(goalKey(U2, 1), target = o?.let { Target(HadesGate.TARTAROS, Vec3d.ofBottomCenter(it)) })
            } else {
                palaceGoal(s, world, p, U2, o) ?: Goal(goalKey(U2, 0))
            }
        } else {
            Goal(goalKey(U2, 0), target = gateTarget())
        }
    }

    /** Kronos (U3): im Kampf die Saalmitte; sonst (Kampf verloren, zurück zum Siegel) dieselbe Kette mit `u3…way.<n>`. */
    private fun titan(suffix: String) = Def(chapter(U3, suffix), ::kronosDone) { s, p ->
        val world = p.entityWorld as? ServerWorld
        if (world != null && world.registryKey == HadesGate.TARTAROS) {
            val o = palace(world, p.blockPos)
            if (HadesFights.of(world) != null) {
                Goal(goalKey(U3, 0), target = o?.let { Target(HadesGate.TARTAROS, Vec3d.ofBottomCenter(it)) })
            } else {
                palaceGoal(s, world, p, U3, o) ?: Goal(goalKey(U3, 0))
            }
        } else {
            Goal(goalKey(U3, 0), target = gateTarget())
        }
    }

    /** Anzahl der Stufen des Palast-Anmarschs (sechs Abschnitte und das Siegel, `TartarusArenas.palaceApproach`). */
    private fun palaceSteps(o: BlockPos): Int = TartarusArenas.palaceApproach(o).points.size

    /** Stufe von [player] auf dem Anmarsch zum Palast [o]: Index des nächsten nicht erreichten Punkts (alle erreicht = Siegel). */
    private fun palaceStep(player: ServerPlayerEntity, o: BlockPos): Int {
        val spec = TartarusArenas.palaceApproach(o)
        val next = Approach.next(player, spec) ?: return spec.points.size - 1
        return spec.points.indexOf(next).coerceAtLeast(0)
    }

    /**
     * Ziel am Palast [o]: Pfeil auf den Punkt der eigenen Stufe (erreicht alles → `palaceEntrance`, vor dem Siegel), Text
     * `hha.rpg.story.<id>.way.<n>` nach der höchsten Stufe aller Spieler im Tartaros.
     */
    private fun palaceGoal(server: MinecraftServer, world: ServerWorld, player: ServerPlayerEntity, id: String, o: BlockPos?): Goal? {
        o ?: return null
        val spec = TartarusArenas.palaceApproach(o)
        val own = palaceStep(player, o)
        val state = RpgWorld.of(server)
        val lead = world.players.filter { !it.isSpectator && state.hasStarted(it.uuid) }.maxOfOrNull { palaceStep(it, o) } ?: own
        val n = maxOf(own, lead).coerceIn(0, palaceSteps(o) - 1)
        return Goal(wayKey(id, n), target = Target(HadesGate.TARTAROS, spec.points[own.coerceIn(0, spec.points.size - 1)].pos))
    }

    private val epilogue = Def(chapter(EPILOGUE), { s -> RpgWorld.of(s).ended }) { _, _ -> Goal(goalKey(EPILOGUE, 0)) }

    /** Weg des Himmels (Akt I), danach Akt II. */
    private val HEAVEN: List<Def> = listOf(
        prologue,
        isle(H1, OlympusLayout.B, hermesFight),
        isle(H2, OlympusLayout.D, arena(OlympusProgress.BRONTES)),
        isle(H3, OlympusLayout.E, arena(OlympusProgress.KRATOS)),
        isle(H4, OlympusLayout.F, arena(OlympusProgress.ZEUS)),
        isle(U0, OlympusLayout.C, arena(OlympusProgress.HEKATE)),
        chain(""),
        lord(""),
        titan(""),
        epilogue,
    )

    /** Weg der Unterwelt: beginnt mit Akt II (Hekate gilt als besiegt); eigene Kicker („Kapitel I“ …). */
    private val HELL: List<Def> = listOf(prologue, chain(".hell"), lord(".hell"), titan(".hell"), epilogue)

    /** Addon-Wege: ein freies Kapitel ohne Ziel (der Weg erzählt selbst). */
    private val FREE_DEFS: List<Def> = listOf(Def(chapter(FREE), { false }) { _, _ -> null })

    private fun defs(path: String): List<Def> = when (path) {
        "heaven" -> HEAVEN
        "hell" -> HELL
        else -> FREE_DEFS
    }

    private fun chainDone(server: MinecraftServer): Boolean =
        !HhaConfig.enabled("tartaros_chain") || TartarosChain.brokenCount(server) >= LINKS

    private fun hadesDone(server: MinecraftServer): Boolean =
        RpgWorld.of(server).isDefeated(TartarosChain.HADES) || TartarosChain.of(server).hadesDone

    private fun kronosDone(server: MinecraftServer): Boolean =
        TartarosChain.of(server).hadesDone || RpgWorld.of(server).isDefeated(KRONOS)

    private const val KRONOS = "kronos"

    private fun gateTarget(): Target {
        val gate = OlympusLayout.HELL_GATE
        return Target(OlympusLayout.WORLD, Vec3d(gate.x + 0.5, gate.y.toDouble(), gate.z + 0.5))
    }

    // ---------------------------------------------------------------------------------------------
    // Öffentlich
    // ---------------------------------------------------------------------------------------------

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register(::tick)
        ServerLifecycleEvents.SERVER_STOPPED.register { found.clear() }
        BossRewards.onGrant { server, key, players -> onVictory(server, key, players) }
    }

    /** Kapitel des Wegs [path] in Spielreihenfolge. */
    fun chapters(path: String): List<Chapter> = defs(path).map { it.chapter }

    /** Laufendes Kapitel der Welt (das erste nicht fertige; am Ende das letzte). */
    fun current(server: MinecraftServer): Chapter = currentDef(server).chapter

    private fun currentDef(server: MinecraftServer): Def {
        val list = defs(RpgWorld.of(server).path)
        return list.firstOrNull { !it.done(server) } ?: list.last()
    }

    /** Ziel des laufenden Kapitels für [player] (null = keins). Nur in RPG-Welten. */
    fun goalFor(server: MinecraftServer, player: ServerPlayerEntity): Goal? {
        if (!RpgWorld.of(server).enabled) return null
        return currentDef(server).goal(server, player)
    }

    /** Ein Kapitel ist fertig (einmal je Welt). */
    fun onChapterDone(listener: (MinecraftServer, Chapter) -> Unit) {
        doneListeners.add(listener)
    }

    /** Ein Kapitel beginnt (einmal je Welt; auch direkt nach der Ankunft für das erste Kapitel). */
    fun onChapterBegin(listener: (MinecraftServer, Chapter) -> Unit) {
        beginListeners.add(listener)
    }

    /** Ein Ort wird in dieser Welt erstmals betreten: `isle.b`, `tartaros`, `site.warden`, `palace` … */
    fun onPlace(listener: (MinecraftServer, String, ServerPlayerEntity) -> Unit) {
        placeListeners.add(listener)
    }

    /** Endszene gespielt (P17 ruft das nach `rpg.end`): Epilog fertig. */
    fun finishStory(server: MinecraftServer) {
        val state = RpgWorld.of(server)
        if (!state.enabled) return
        state.ended = true
        RpgServer.markAllDirty()
    }

    /**
     * Abspann-Ereignis (P17 schickt es nach `rpg.end`): numbers = (Spielzeit in Ticks seit der Ankunft, Tode der Welt),
     * texts = Namen aller, die angekommen sind (Namens-Cache, sonst die Verbundenen).
     */
    fun credits(server: MinecraftServer): RpgEventPayload {
        val state = RpgWorld.of(server)
        val ticks = if (state.startedAt >= 0) (server.overworld.time - state.startedAt).coerceAtLeast(0) else 0L
        val names = LinkedHashSet<String>()
        for (player in server.playerManager.playerList) if (state.hasStarted(player.uuid)) names.add(player.gameProfile.name)
        for (id in state.rests.keys) {
            val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: continue
            val name = server.playerManager.getPlayer(uuid)?.gameProfile?.name
                ?: runCatching { server.apiServices.nameToIdCache().getByUuid(uuid).map { it.name() }.orElse(null) }.getOrNull()
            if (name != null) names.add(name)
        }
        return RpgEventPayload(RpgEventPayload.CREDITS, listOf(ticks.toDouble(), state.deaths.toDouble()), names.toList())
    }

    /** Ort der Ankunft des Wegs (`isle.a`, Unterwelt `isle.c`) oder null. */
    fun arrivalPlace(server: MinecraftServer): String? {
        val a = RpgArrival.anchor(server)
        if (a.world != OlympusLayout.WORLD) return null
        return OlympusLayout.ISLES.firstOrNull { onIsle(it, a.pos) }?.let(::placeOf)
    }

    /** Bei der Ankunft: Der Ankunftsort gilt als besucht (ohne [onPlace], ohne Orts-Rast; die Ankunft hat ihre eigene). */
    fun markArrivalPlace(server: MinecraftServer) {
        arrivalPlace(server)?.let { RpgWorld.of(server).visit(it) }
    }

    /**
     * `/hha rpg chapter <id>`: setzt die Zustände so, dass [id] das laufende Kapitel ist (alle früheren fertig, dieses
     * und alle späteren offen). Fertig-Meldungen werden dabei nicht ausgelöst; der Beginn von [id] schon.
     */
    fun applyChapter(server: MinecraftServer, id: String): Boolean {
        val state = RpgWorld.of(server)
        if (!state.enabled) return false
        val list = defs(state.path)
        val index = list.indexOfFirst { it.chapter.id == id }
        if (index < 0) return false
        for ((i, def) in list.withIndex()) {
            val done = i < index
            setDone(server, def.chapter.id, done)
            if (done) state.markChapterDone(def.chapter.id) else state.clearChapterDone(def.chapter.id)
        }
        state.currentChapter = ""
        RpgServer.markAllDirty()
        return true
    }

    private fun setDone(server: MinecraftServer, id: String, done: Boolean) {
        val state = RpgWorld.of(server)
        when (id) {
            PROLOGUE -> if (done) state.arrived = true
            H1 -> OlympusProgress.set(server, OlympusProgress.HERMES, done)
            H2 -> OlympusProgress.set(server, OlympusProgress.BRONTES, done)
            H3 -> OlympusProgress.set(server, OlympusProgress.KRATOS, done)
            H4 -> OlympusProgress.set(server, OlympusProgress.ZEUS, done)
            U0 -> OlympusProgress.set(server, OlympusProgress.HEKATE, done)
            U1 -> for (link in TartarosChain.Link.entries) TartarosChain.set(server, link, done)
            U2 -> if (done) state.defeat(TartarosChain.HADES) else {
                state.undefeat(TartarosChain.HADES)
                TartarosChain.of(server).hadesDone = false
            }
            U3 -> {
                TartarosChain.of(server).hadesDone = done
                if (done) state.defeat(KRONOS) else state.undefeat(KRONOS)
            }
            EPILOGUE -> state.ended = done
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------

    private var owner: MinecraftServer? = null

    private fun tick(server: MinecraftServer) {
        if (owner !== server) {
            owner = server
            found.clear()
        }
        val state = RpgWorld.of(server)
        if (!state.enabled) return
        val now = server.ticks
        if (now % PLACE_TICKS == 0) tickPlaces(server, state)
        if (now % STORY_TICKS != 0) return
        syncDefeated(server, state)
        tickChapters(server, state)
    }

    private fun tickChapters(server: MinecraftServer, state: RpgWorld.State) {
        for (def in defs(state.path)) {
            val done = def.done(server)
            if (done && state.markChapterDone(def.chapter.id)) {
                fire(doneListeners, server, def.chapter, "onChapterDone")
                RpgServer.markAllDirty()
            } else if (!done && state.isChapterDone(def.chapter.id)) {
                state.clearChapterDone(def.chapter.id)
                RpgServer.markAllDirty()
            }
        }
        val current = currentDef(server).chapter
        if (state.currentChapter != current.id) {
            state.currentChapter = current.id
            fire(beginListeners, server, current, "onChapterBegin")
            RpgServer.markAllDirty()
        }
    }

    private fun fire(listeners: List<(MinecraftServer, Chapter) -> Unit>, server: MinecraftServer, chapter: Chapter, what: String) {
        for (listener in listeners) {
            try {
                listener(server, chapter)
            } catch (e: Exception) {
                Hha.LOGGER.error("RpgStory.{}({}): Lauscher fehlgeschlagen", what, chapter.id, e)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Siege und Tagebuch
    // ---------------------------------------------------------------------------------------------

    private fun onVictory(server: MinecraftServer, key: String, players: List<ServerPlayerEntity>) {
        val state = RpgWorld.of(server)
        if (!state.enabled) return
        defeated(server, state, key)
        // `hades` aus BossRewards kommt erst nach Kronos' Tod: Beide sind besiegt.
        if (key == TartarosChain.HADES) defeated(server, state, KRONOS)
        RpgRests.onVictory(server, key, players)
        RpgServer.markAllDirty()
    }

    /** Sieg eintragen (einmal): `defeated`, Tagebuch `<boss>` (falls er fehlte) und `<boss>.done`. */
    private fun defeated(server: MinecraftServer, state: RpgWorld.State, key: String) {
        state.defeat(key)
        journal(server, state, key)
        journal(server, state, "$key.done")
    }

    private fun journal(server: MinecraftServer, state: RpgWorld.State, entry: String) {
        if (!state.addJournal(entry)) return
        RpgServer.event(server.playerManager.playerList, RpgEventPayload.JOURNAL, emptyList(), listOf(entry))
        RpgServer.markAllDirty()
    }

    /** Siege aus den vorhandenen Zuständen (damit `/hha progress set` und alte Welten mitzählen). */
    private fun syncDefeated(server: MinecraftServer, state: RpgWorld.State) {
        for (key in OlympusProgress.KEYS) if (OlympusProgress.isDone(server, key) && !state.isDefeated(key)) defeated(server, state, key)
        val chain = TartarosChain.of(server)
        for (link in TartarosChain.Link.entries) if (chain.isBroken(link) && !state.isDefeated(link.key)) defeated(server, state, link.key)
        if (chain.hadesDone) {
            if (!state.isDefeated(TartarosChain.HADES)) defeated(server, state, TartarosChain.HADES)
            if (!state.isDefeated(KRONOS)) defeated(server, state, KRONOS)
        }
        val tartaros = server.getWorld(HadesGate.TARTAROS) ?: return
        val fight = HadesFights.of(tartaros) ?: return
        // Stufe 4 und 5 sind Kronos: Hades ist besiegt, Kronos ist erschienen.
        if (fight.stage >= 4) {
            journal(server, state, KRONOS)
            if (!state.isDefeated(TartarosChain.HADES)) defeated(server, state, TartarosChain.HADES)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Orte
    // ---------------------------------------------------------------------------------------------

    /** Boss eines Orts (für das Tagebuch). */
    private fun bossOf(place: String): String? = when {
        place.startsWith("isle.") -> OlympusLayout.ISLES.firstOrNull { placeOf(it) == place }?.key
        place.startsWith("site.") -> place.removePrefix("site.")
        place == "palace" -> TartarosChain.HADES
        else -> null
    }

    private fun placeOf(isle: OlympusLayout.Isle) = "isle.${isle.letter.lowercaseChar()}"

    /**
     * Insel betreten (P23, Briefing 6 geprüft): Das Höhenfenster −[ISLE_BELOW] … +[ISLE_ABOVE] deckt Terrasse (+2),
     * Bühne (+3), Absatz (+4) und die Kopftore (Krone +9) ab; die Kopftore liegen im Inselradius (Bericht P23).
     */
    private fun onIsle(isle: OlympusLayout.Isle, pos: Vec3d): Boolean = OlympusPath.onIsle(isle, pos)

    /** Ort „Schlachtweg betreten“ (`way.bridge_ab` …): Erzählerzeile einmal je Welt (`RpgScenes`). */
    const val WAY_PLACE = "way."

    /** Ort „Alleeanfang des Palasts“, zählt erst, wenn die Kette gesprengt ist (erster Blick auf den Palast, `RpgScenes`). */
    const val PALACE_GATE_PLACE = "palace.gate"

    /** Orte, an denen [player] gerade ist. */
    private fun placesOf(server: MinecraftServer, player: ServerPlayerEntity): List<String> {
        val world = player.entityWorld as? ServerWorld ?: return emptyList()
        return when (world.registryKey) {
            OlympusLayout.WORLD -> OlympusLayout.ISLES.filter { onIsle(it, player.entityPos) }.map(::placeOf) + wayPlaces(player)
            HadesGate.TARTAROS -> listOf("tartaros") + siteAt(server, world, player)
            else -> emptyList()
        }
    }

    /**
     * Schlachtwege, auf denen [player] gerade steht (`Approach.progress` ≠ null). Nicht in Kampf oder Szene: Die Zeile soll
     * gelesen werden, also zählt der Ort erst danach.
     */
    private fun wayPlaces(player: ServerPlayerEntity): List<String> {
        if (Cine.isPlaying(player) || Arenas.of(player) != null) return emptyList()
        return OlympusArenas.APPROACHES.filter { Approach.progress(player, it) != null }.map { WAY_PLACE + it.id }
    }

    /** Bauwerk des Tartaros an der Lage von [player]: `site.<glied>` oder `palace` (dazu ggf. [PALACE_GATE_PLACE]). */
    private fun siteAt(server: MinecraftServer, world: ServerWorld, player: ServerPlayerEntity): List<String> {
        val pos = player.blockPos
        val start = world.structureAccessor.getStructureContaining(pos) { it.value() is TartarusStructure }
        if (!start.hasChildren()) return emptyList()
        val piece = start.children.firstOrNull() as? TartarusPiece ?: return emptyList()
        if (piece.kind == TartarusKind.PALACE) {
            val avenue = TartarusArenas.palaceApproach(piece.middle).points.first()
            val atGate = chainDone(server) && !Cine.isPlaying(player) &&
                hypot(player.x - avenue.pos.x, player.z - avenue.pos.z) <= avenue.reach && kotlin.math.abs(player.y - avenue.pos.y) <= Approach.HEIGHT_RANGE
            return if (atGate) listOf("palace", PALACE_GATE_PLACE) else listOf("palace")
        }
        return listOfNotNull(siteName(piece))
    }

    private fun siteName(piece: TartarusPiece): String? {
        return when (piece.kind) {
            TartarusKind.PALACE -> "palace"
            TartarusKind.FORTRESS -> "site.${TartarosChain.Link.WARDEN.key}"
            TartarusKind.PRISON -> "site.${TartarosChain.Link.KAMPE.key}"
            TartarusKind.PUNISHMENT -> when (piece.siteVariant()) {
                0 -> "site.${TartarosChain.Link.SISYPHUS.key}"
                1 -> "site.${TartarosChain.Link.IXION.key}"
                else -> "site.${TartarosChain.Link.TANTALUS.key}"
            }
        }
    }

    private fun tickPlaces(server: MinecraftServer, state: RpgWorld.State) {
        val arrival = arrivalPlace(server)
        for (player in server.playerManager.playerList.toList()) {
            if (player.isSpectator || !player.isAlive || !state.hasStarted(player.uuid) || RpgArrival.isProtected(player)) continue
            for (place in placesOf(server, player)) {
                if (state.visit(place)) {
                    bossOf(place)?.let { journal(server, state, it) }
                    for (listener in placeListeners) {
                        try {
                            listener(server, place, player)
                        } catch (e: Exception) {
                            Hha.LOGGER.error("RpgStory.onPlace({}): Lauscher fehlgeschlagen", place, e)
                        }
                    }
                    RpgServer.markAllDirty()
                }
                if (place != arrival) RpgRests.onPlace(player, place)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Ziele im Tartaros (Notlösung bis `TartarosGuide` sein Ziel öffentlich macht, requests/P06.md)
    // ---------------------------------------------------------------------------------------------

    /** Suchergebnis je Ziel: Mitte des Bauwerks (oder nichts) und wann gesucht wurde. */
    private class Found(val pos: BlockPos?, val at: Long)

    private val found = HashMap<String, Found>()

    /**
     * Pfeil zum nächsten ungesprengten Glied (Überarbeitung 4, P23, Briefing 3): erst die Punkte des Zuwegs
     * (`TartarusArenas.siteApproach`: Beginn am Kastenrand → Wegzeichen → Eingang), danach der Platz des Wächters
     * ([standOf]). Das Bauwerk liefert die gründlichere Suche des Wegweisers, sonst die einfache Suche hier.
     */
    private fun nextLink(world: ServerWorld, player: ServerPlayerEntity): Vec3d? {
        val site = TartarosGuide.nextSite(world, player) ?: fallbackSite(world, player) ?: return null
        val (middle, link) = site
        val kind = kindOf(link)
        val variant = variantOf(link)
        // Im Kerker: wer schon innerhalb der Grube (auf der Rampe) ist, braucht nur noch den Grubenboden.
        if (kind == TartarusKind.PRISON && hypot(player.x - (middle.x + 0.5), player.z - (middle.z + 0.5)) < TartarusPlans.PRISON_PIT_RADIUS) {
            return TartarusArenas.prison(middle).centerVec
        }
        val road = TartarusArenas.siteApproach(kind, variant, middle)
        return Approach.next(player, road)?.pos ?: standOf(kind, variant, middle)
    }

    /**
     * Kerker: Rampenkopf im Westen am Grubenrand (Hinweis P12: etwa o + (−22, 1, −3), nicht der Vertragspunkt
     * `entrance` im Süden). Radial: Mitte der Rampe zwischen Grubenrand und Innenkante; z: Beginn des Rampenlaufs.
     */
    const val PRISON_RAMP_HEAD_IN = 2
    const val PRISON_RAMP_HEAD_DZ = -3

    /** Platz des Wächters nach dem Zuweg: Standplatz der Feste, Rampenkopf des Kerkers, Gipfel, sonst die Arena-Mitte. */
    private fun standOf(kind: TartarusKind, variant: Int, o: BlockPos): Vec3d = when (kind) {
        TartarusKind.FORTRESS -> Vec3d.ofBottomCenter(TartarusArenas.fortressSpot(o))
        TartarusKind.PRISON -> Vec3d(o.x - (TartarusPlans.PRISON_PIT_RADIUS - PRISON_RAMP_HEAD_IN) + 0.5, o.y + 1.0, o.z + PRISON_RAMP_HEAD_DZ + 0.5)
        TartarusKind.PUNISHMENT -> if (variant == 0) TartarusPlans.sisyphusSummit(o) else TartarusArenas.spec(kind, variant, o).centerVec
        TartarusKind.PALACE -> TartarusArenas.palaceEntrance(o).let { Vec3d.ofBottomCenter(it) }
    }

    /** Einfache Suche (falls der Wegweiser nichts findet): nächstes passendes, nicht befreites Bauwerk je Glied. */
    private fun fallbackSite(world: ServerWorld, player: ServerPlayerEntity): Pair<BlockPos, TartarosChain.Link>? {
        val server = world.server ?: return null
        var best: Pair<BlockPos, TartarosChain.Link>? = null
        var bestDistance = Double.MAX_VALUE
        for (link in TartarosChain.of(server).missing()) {
            val pos = locate(world, link.key, kindOf(link), variantOf(link), player.blockPos) ?: continue
            val d = hypot(pos.x + 0.5 - player.x, pos.z + 0.5 - player.z)
            if (d < bestDistance) {
                bestDistance = d
                best = pos to link
            }
        }
        return best
    }

    /** Palastmitte: genau, wenn der Spieler im Kasten steht (`Approach.palace`), sonst gesucht. */
    private fun palace(world: ServerWorld, from: BlockPos): BlockPos? =
        Approach.palace(world, from) ?: locate(world, "palace", TartarusKind.PALACE, TartarusPiece.NO_VARIANT, from)

    private fun kindOf(link: TartarosChain.Link): TartarusKind = when (link) {
        TartarosChain.Link.WARDEN -> TartarusKind.FORTRESS
        TartarosChain.Link.KAMPE -> TartarusKind.PRISON
        else -> TartarusKind.PUNISHMENT
    }

    private fun variantOf(link: TartarosChain.Link): Int = when (link) {
        TartarosChain.Link.SISYPHUS -> 0
        TartarosChain.Link.IXION -> 1
        TartarosChain.Link.TANTALUS -> 2
        else -> TartarusPiece.NO_VARIANT
    }

    /**
     * Nächstes Bauwerk der Art [kind] (Variante [variant]) bei [from], höchstens alle [SEARCH_COOLDOWN] Ticks je Ziel neu
     * gesucht. Einfacher als der Wegweiser: nur der erste Treffer von `locateStructure`; passt er nicht (andere
     * Strafstätte, schon befreit), gibt es bis zur nächsten Suche kein Ziel (der HUD-Pfeil fehlt dann, der Text bleibt).
     */
    private fun locate(world: ServerWorld, key: String, kind: TartarusKind, variant: Int, from: BlockPos): BlockPos? {
        val last = found[key]
        if (last != null && world.time - last.at < SEARCH_COOLDOWN) {
            val pos = last.pos
            return if (pos != null && (kind == TartarusKind.PALACE || !TartarosMinibosses.isFreed(world, pos))) pos else null
        }
        var result: BlockPos? = null
        try {
            val registry = world.registryManager.getOrThrow(RegistryKeys.STRUCTURE)
            val entry = registry.getEntry(Hha.id(kind.id)).orElse(null)
            if (entry != null) {
                val placement = world.chunkManager.structurePlacementCalculator.getPlacements(entry)
                    .firstOrNull { it is RandomSpreadStructurePlacement } as? RandomSpreadStructurePlacement
                val rings = if (placement != null) ceil(SEARCH_CHUNKS.toDouble() / placement.spacing).toInt().coerceAtLeast(1) else SEARCH_CHUNKS
                val start = world.chunkManager.chunkGenerator.locateStructure(world, RegistryEntryList.of(entry), from, rings, false)?.first
                if (start != null) {
                    val chunk = world.getChunk(start.x shr 4, start.z shr 4, ChunkStatus.STRUCTURE_STARTS, true)
                    val piece = chunk?.getStructureStart(entry.value())?.children?.firstOrNull() as? TartarusPiece
                    if (piece != null && (variant < 0 || piece.siteVariant() == variant) &&
                        (kind == TartarusKind.PALACE || !TartarosMinibosses.isFreed(world, piece.middle))
                    ) {
                        result = piece.middle
                    }
                }
            }
        } catch (e: RuntimeException) {
            Hha.LOGGER.warn("RPG-Ziel: Suche nach {} fehlgeschlagen", kind.id, e)
        }
        found[key] = Found(result, world.time)
        return result
    }
}
