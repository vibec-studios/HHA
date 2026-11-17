package dev.henny.hha.rpg

import dev.henny.hha.Hha
import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.BossRewards
import dev.henny.hha.logic.boss.HadesFights
import dev.henny.hha.logic.boss.HermesFights
import dev.henny.hha.logic.world.HadesGate
import dev.henny.hha.logic.world.OlympusProgress
import dev.henny.hha.logic.world.TartarosChain
import dev.henny.hha.logic.world.TartarosGuide
import dev.henny.hha.logic.world.TartarosMinibosses
import dev.henny.hha.net.RpgEventPayload
import dev.henny.hha.world.TartarusKind
import dev.henny.hha.world.TartarusPiece
import dev.henny.hha.world.TartarusStructure
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

    /** Insel betreten: so weit unter … */
    const val ISLE_BELOW = 16

    /** … bis so weit über ihrem Boden. */
    const val ISLE_ABOVE = 48

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

    private fun isle(id: String, isle: OlympusLayout.Isle, fight: (MinecraftServer) -> Boolean) = Def(
        chapter(id), { s -> OlympusProgress.isDone(s, isle.key!!) },
    ) { s, _ ->
        val place = placeOf(isle)
        val altar = isle.altar?.let { Vec3d.ofBottomCenter(it) } ?: isle.centerVec
        when {
            fight(s) -> Goal(goalKey(id, 2), target = Target(OlympusLayout.WORLD, isle.centerVec))
            place in RpgWorld.of(s).visited -> Goal(goalKey(id, 1), target = Target(OlympusLayout.WORLD, altar))
            else -> Goal(goalKey(id, 0), target = Target(OlympusLayout.WORLD, isle.entrance))
        }
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

    private fun lord(suffix: String) = Def(chapter(U2, suffix), ::hadesDone) { s, p ->
        val world = p.entityWorld as? ServerWorld
        if (world != null && world.registryKey == HadesGate.TARTAROS) {
            val step = if (HadesFights.of(world) != null) 1 else 0
            Goal(goalKey(U2, step), target = palace(world, p.blockPos)?.let { Target(HadesGate.TARTAROS, Vec3d.ofBottomCenter(it)) })
        } else {
            Goal(goalKey(U2, 0), target = gateTarget())
        }
    }

    private fun titan(suffix: String) = Def(chapter(U3, suffix), ::kronosDone) { _, p ->
        val world = p.entityWorld as? ServerWorld
        if (world != null && world.registryKey == HadesGate.TARTAROS) {
            Goal(goalKey(U3, 0), target = palace(world, p.blockPos)?.let { Target(HadesGate.TARTAROS, Vec3d.ofBottomCenter(it)) })
        } else {
            Goal(goalKey(U3, 0), target = gateTarget())
        }
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

    private fun onIsle(isle: OlympusLayout.Isle, pos: Vec3d): Boolean =
        hypot(pos.x - isle.centerVec.x, pos.z - isle.centerVec.z) <= isle.radius &&
            pos.y >= isle.floorY - ISLE_BELOW && pos.y <= isle.floorY + ISLE_ABOVE

    /** Orte, an denen [player] gerade ist. */
    private fun placesOf(player: ServerPlayerEntity): List<String> {
        val world = player.entityWorld as? ServerWorld ?: return emptyList()
        return when (world.registryKey) {
            OlympusLayout.WORLD -> OlympusLayout.ISLES.filter { onIsle(it, player.entityPos) }.map(::placeOf)
            HadesGate.TARTAROS -> listOf("tartaros") + listOfNotNull(siteAt(world, player.blockPos))
            else -> emptyList()
        }
    }

    /** Bauwerk des Tartaros an [pos]: `site.<glied>` oder `palace`. */
    private fun siteAt(world: ServerWorld, pos: BlockPos): String? {
        val start = world.structureAccessor.getStructureContaining(pos) { it.value() is TartarusStructure }
        if (!start.hasChildren()) return null
        val piece = start.children.firstOrNull() as? TartarusPiece ?: return null
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
            for (place in placesOf(player)) {
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

    /** Das nächste ungesprengte Glied (nächstes passendes, nicht befreites Bauwerk) zu [player]. */
    private fun nextLink(world: ServerWorld, player: ServerPlayerEntity): Vec3d? {
        // Bevorzugt die gründlichere Suche des Wegweisers (sucht weiter, wenn der erste Treffer nicht passt).
        TartarosGuide.target(world, player)?.let { (pos, key) -> if (key != "palace") return pos }
        val server = world.server ?: return null
        var best: Vec3d? = null
        var bestDistance = Double.MAX_VALUE
        for (link in TartarosChain.of(server).missing()) {
            val pos = locate(world, link.key, kindOf(link), variantOf(link), player.blockPos) ?: continue
            val d = hypot(pos.x + 0.5 - player.x, pos.z + 0.5 - player.z)
            if (d < bestDistance) {
                bestDistance = d
                best = Vec3d.ofBottomCenter(pos)
            }
        }
        return best
    }

    private fun palace(world: ServerWorld, from: BlockPos): BlockPos? =
        locate(world, "palace", TartarusKind.PALACE, TartarusPiece.NO_VARIANT, from)

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
