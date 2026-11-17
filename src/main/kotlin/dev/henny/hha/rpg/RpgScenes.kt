package dev.henny.hha.rpg

import dev.henny.hha.Hha
import dev.henny.hha.HhaSounds
import dev.henny.hha.api.rpg.HhaRpg
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.CineSkip
import dev.henny.hha.logic.boss.SceneRun
import dev.henny.hha.logic.world.HadesGate
import dev.henny.hha.logic.world.TartarosGuide
import dev.henny.hha.stage.StageScripts
import dev.henny.hha.stage.scripts.RpgStages
import dev.henny.hha.stage.scripts.RpgStoryStages
import dev.henny.hha.world.olymp.OlympusLayout
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.UUID

/**
 * Szenen der RPG-Geschichte auf dem Server (P17, PLAN 3.4). Nur in RPG-Welten der eingebauten Wege (`heaven`, `hell`);
 * im normalen Modus und auf Addon-Wegen tut nichts hier etwas. Die Ankunftsszenen startet P06 (`RpgArrival`); hier
 * hängen nur ihr Ende (Kapitelkarte gilt als gezeigt, Musik zurück) und alle übrigen Szenen.
 *
 * Auslöser:
 * - `RpgStory.onPlace("isle.b" … "isle.f")` (nur Himmelsweg) → `rpg.isle.<x>` für alle Spieler im Umkreis [NEAR] um den
 *   Auslöser, die nicht kämpfen und in keiner Szene stecken; alle übrigen bekommen nur die Kapitelkarte (D, E, F).
 *   Einmal je Welt und Ort, das sichert `RpgStory` (der Ort steht schon in `RpgWorld.visited`).
 * - `onPlace("tartaros")` → `rpg.tartaros` (beide Wege).
 * - `onChapterDone("h4_fear")` → `rpg.act_end`; `onChapterDone("u1_chain")` → `rpg.palace`; `onChapterDone("u3_titan")`
 *   → `rpg.end`, danach `RpgStory.credits` als `CREDITS`-Ereignis und `RpgStory.finishStory`. Diese drei warten, bis
 *   niemand mehr in einer Szene steckt (Sieg-Szene des Bosses vorbei), dann noch [AFTER_QUIET] Ticks ([Pending]).
 * - Kapitelkarten ohne eigene Szene ([queueCard]): sobald es ruhig ist (keine Szene, kein Kampf) [CARD_QUIET] Ticks lang,
 *   und nur, solange das Kapitel noch läuft.
 *
 * **Fester Endzustand** jeder Szene ([finish], über `SceneRun.finish`, genau einmal): natürlich zu Ende, übersprungen
 * (Abstimmung über `CineSkip` wie `DuelCinematics`), alle Zuschauer getrennt (dann sofort), Server hält an
 * (`SERVER_STOPPING`). Danach: Kamera frei (`Cine.stageStop` beim Überspringen, `Cine.end`, `Cine.cut`), Musik der
 * Szene aus (außer der Abspann-Musik des Endes), jeder Zuschauer auf seinem Platz vom Szenenbeginn ([Run.homes],
 * Geschwindigkeit und Fallstrecke null), Unverwundbarkeit vorbei, die Folgen der Szene ([after]) sind
 * eingetreten. Während der Szene sind die Zuschauer unverwundbar (`ALLOW_DAMAGE`/`ALLOW_DEATH`, nichts gespeichert) und
 * werden auf ihrem Platz gehalten ([DRIFT]); wer die Verbindung trennt, fällt aus der Liste (die Abstimmung zählt neu).
 */
object RpgScenes {

    /** Umkreis um den Auslöser einer Inselszene (Briefing). */
    const val NEAR = 48.0

    /** Nach dem Ende der letzten Szene noch so lange warten, bevor Akt-Ende, Palast oder Ende beginnen. */
    const val AFTER_QUIET = 60

    /** Kämpft noch ein Zuschauer, wartet eine wartende Szene höchstens so lange auf ihn, dann ohne ihn. */
    const val FIGHT_WAIT_MAX = 600

    /** So lange muss es ruhig sein, bevor eine nachgereichte Kapitelkarte kommt. */
    const val CARD_QUIET = 40

    /** Weiter als so viele Blöcke vom Platz weg → zurückgesetzt (wie die Ankunft von P06). */
    const val DRIFT = 1.5

    /** Kapitelkarte: Anzeigedauer. */
    const val CARD_TICKS = 100

    /** Bodensuche für den Platz eines Zuschauers: so weit hinauf … */
    private const val GROUND_UP = 2

    /** … und so weit hinab. */
    private const val GROUND_DOWN = 6

    /** Welche Szene, wie sie ihre Zuschauer findet und was nach ihr gilt. */
    private enum class Kind(val stageId: String) {
        ISLE_B(RpgStoryStages.isleId('B')),
        ISLE_C(RpgStoryStages.isleId('C')),
        ISLE_D(RpgStoryStages.isleId('D')),
        ISLE_E(RpgStoryStages.isleId('E')),
        ISLE_F(RpgStoryStages.isleId('F')),
        TARTAROS(RpgStoryStages.TARTAROS),
        PALACE(RpgStoryStages.PALACE),
        ACT_END(RpgStoryStages.ACT_END),
        END(RpgStoryStages.END),
    }

    /** Kapitel, deren Karte eine Inselszene trägt. */
    private val ISLE_CHAPTERS = mapOf(Kind.ISLE_D to "h2_work", Kind.ISLE_E to "h3_force", Kind.ISLE_F to "h4_fear")

    /** Kapitel, deren Karte eine Szene zeigt (dann reicht [queueCard] sie nicht nach). */
    private val COVERED = setOf("prologue", "h1_word", "h2_work", "h3_force", "h4_fear", "u0_threshold", "u2_lord", "epilogue", "free")

    /** Eine laufende Szene. */
    private class Run(
        val kind: Kind,
        val world: RegistryKey<World>,
        val players: MutableList<UUID>,
        val homes: Map<UUID, Vec3d>,
        val length: Int,
    ) {
        val skip = CineSkip()
        var skipped = false
        var age = 0
        var cardSent = false
        lateinit var scene: SceneRun
    }

    /** Eine Szene, die auf Ruhe wartet. */
    private class Pending(val kind: Kind) {
        var quiet = 0
        var waited = 0
    }

    private val runs = ArrayList<Run>()
    private val pending = ArrayList<Pending>()

    /** Nachzureichende Kapitelkarte (Kapitel-ID) und wie lange es schon ruhig ist. */
    private var queuedCard: String? = null
    private var cardQuiet = 0

    /** Schon gezeigte Kapitelkarten (im Speicher; nach einem Neustart reicht der Server keine alte Karte nach). */
    private val shownCards = HashSet<String>()

    private var owner: MinecraftServer? = null

    fun init() {
        RpgStory.onPlace(::onPlace)
        RpgStory.onChapterDone { server, chapter ->
            when (chapter.id) {
                "h4_fear" -> defer(server, Kind.ACT_END)
                "u1_chain" -> defer(server, Kind.PALACE)
                "u3_titan" -> defer(server, Kind.END)
            }
        }
        RpgStory.onChapterBegin(::queueCard)
        RpgArrival.onArrived { server, players ->
            bind(server)
            val path = RpgWorld.of(server).path
            // Die Ankunft trägt die erste Kapitelkarte; die Musik der Unterwelt-Ankunft gibt an die Gebietsmusik zurück.
            when (path) {
                "heaven" -> shownCards.add("h1_word")
                "hell" -> {
                    shownCards.add("u1_chain")
                    Cine.music(players, null)
                }
            }
        }
        ServerTickEvents.END_SERVER_TICK.register(::tick)
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server -> onLeave(server, handler.player) }
        ServerLifecycleEvents.SERVER_STOPPING.register(::stopAll)
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, _, _ ->
            val player = entity as? ServerPlayerEntity ?: return@register true
            runOf(player.uuid) == null
        }
        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, _, _ ->
            val player = entity as? ServerPlayerEntity ?: return@register true
            if (runOf(player.uuid) == null) return@register true
            player.health = player.maxHealth.coerceAtLeast(1f)
            false
        }
        Cine.onSkip { player ->
            val run = runOf(player.uuid) ?: return@onSkip false
            val server = player.entityWorld.server ?: return@onSkip false
            run.skip.vote(player, online(server, run.players))
            true
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Öffentlich (Prüfen)
    // ---------------------------------------------------------------------------------------------

    /** Läuft gerade eine RPG-Szene mit [player]? */
    fun isWatching(player: ServerPlayerEntity): Boolean = runOf(player.uuid) != null

    /** Zustand für Prüfbefehle. */
    fun describe(): List<String> {
        val lines = ArrayList<String>()
        for (run in runs) lines.add("Szene ${run.kind.stageId}: ${run.age}/${run.length} Ticks, ${run.players.size} Zuschauer")
        for (p in pending) lines.add("wartet: ${p.kind.stageId} (ruhig seit ${p.quiet}, gewartet ${p.waited})")
        queuedCard?.let { lines.add("Kapitelkarte wartet: $it") }
        return lines
    }

    /**
     * Spielt die Szene [stageId] sofort für [player] (und wer nach den Regeln mitsieht), mit dem Anker, den sie im
     * Spiel hätte. Für einen Prüfbefehl (`requests/P17.md`); ohne RPG-Welt und für Ankunftsszenen `false`.
     */
    fun playNow(player: ServerPlayerEntity, stageId: String): Boolean {
        val server = player.entityWorld.server ?: return false
        if (!RpgWorld.of(server).enabled) return false
        val kind = Kind.entries.firstOrNull { it.stageId == stageId } ?: return false
        bind(server)
        // Nur in der Welt der Szene: Ein Ende ohne Zuschauer schlösse sonst den Epilog.
        if (worldOf(kind, player) != player.entityWorld.registryKey) return false
        return start(server, kind, player)
    }

    // ---------------------------------------------------------------------------------------------
    // Auslöser
    // ---------------------------------------------------------------------------------------------

    private fun builtIn(server: MinecraftServer): String? = RpgWorld.of(server).let { s -> if (s.enabled && (s.path == "heaven" || s.path == "hell")) s.path else null }

    private fun onPlace(server: MinecraftServer, place: String, player: ServerPlayerEntity) {
        val path = builtIn(server) ?: return
        bind(server)
        val kind = when (place) {
            "isle.b" -> Kind.ISLE_B
            "isle.c" -> Kind.ISLE_C
            "isle.d" -> Kind.ISLE_D
            "isle.e" -> Kind.ISLE_E
            "isle.f" -> Kind.ISLE_F
            "tartaros" -> Kind.TARTAROS
            else -> return
        }
        // Inselszenen erzählen den Himmelsweg; der Unterweltweg kommt an keine Insel außer dem Dreiweg (Ankunft).
        if (kind != Kind.TARTAROS && path != "heaven") return
        start(server, kind, player)
    }

    private fun defer(server: MinecraftServer, kind: Kind) {
        if (builtIn(server) == null) return
        bind(server)
        if (kind == Kind.ACT_END && RpgWorld.of(server).path != "heaven") return
        if (pending.any { it.kind == kind } || runs.any { it.kind == kind }) return
        pending.add(Pending(kind))
    }

    /** Ein Kapitel beginnt: Trägt keine Szene seine Karte, kommt sie nach, sobald es ruhig ist. */
    private fun queueCard(server: MinecraftServer, chapter: RpgStory.Chapter) {
        val path = builtIn(server) ?: return
        bind(server)
        if (chapter.id in COVERED) return
        if (path == "hell" && chapter.id == "u1_chain") return
        if (chapter.id in shownCards) return
        queuedCard = chapter.id
        cardQuiet = 0
    }

    // ---------------------------------------------------------------------------------------------
    // Start
    // ---------------------------------------------------------------------------------------------

    /** Wer sieht mit: gestartet, lebendig, kein Zuschauer, kein Kampf, keine andere Szene, in [world]. */
    private fun eligible(server: MinecraftServer, player: ServerPlayerEntity, world: ServerWorld): Boolean {
        val state = RpgWorld.of(server)
        return player.entityWorld === world && player.isAlive && !player.isSpectator && !player.isDisconnected &&
            state.hasStarted(player.uuid) && !RpgRules.inFight(player) && !Cine.isPlaying(player) &&
            !RpgArrival.isProtected(player) && runOf(player.uuid) == null
    }

    /**
     * Startet [kind] mit [trigger] als Auslöser (bei wartenden Szenen: der erste passende Spieler oder `null`). Ohne
     * Zuschauer oder ohne Skript treten nur die Folgen ein ([after]) und die Karte geht an alle.
     */
    private fun start(server: MinecraftServer, kind: Kind, trigger: ServerPlayerEntity?): Boolean {
        val worldKey = worldOf(kind, trigger)
        val world = server.getWorld(worldKey)
        val viewers = if (world == null) emptyList() else when (kind) {
            Kind.ISLE_B, Kind.ISLE_C, Kind.ISLE_D, Kind.ISLE_E, Kind.ISLE_F, Kind.TARTAROS -> {
                val origin = trigger?.entityPos ?: Vec3d.ZERO
                world.players.filter { eligible(server, it, world) && it.entityPos.squaredDistanceTo(origin) <= NEAR * NEAR }
            }
            else -> world.players.filter { eligible(server, it, world) }
        }.sortedBy { if (it === trigger) 0 else 1 }
        val length = if (StageScripts.get(kind.stageId) != null) StageScripts.length(kind.stageId) else 0
        if (viewers.isEmpty() || world == null || length <= 0) {
            if (length <= 0) Hha.LOGGER.warn("RPG-Szene {}: kein Skript, nur die Folgen", kind.stageId)
            cardForOthers(server, kind, emptyList())
            after(server, kind, emptyList(), skipped = true, card = true)
            return false
        }
        val lead = viewers.first()
        val (anchor, yaw) = anchorOf(kind, lead, world)
        val homes = LinkedHashMap<UUID, Vec3d>()
        for (p in viewers) homes[p.uuid] = RpgRests.ground(world, p.entityPos, GROUND_UP, GROUND_DOWN)
        val run = Run(kind, worldKey, viewers.map { it.uuid }.toMutableList(), homes, length)
        run.scene = SceneRun("rpg:${kind.stageId}", steps(server, run, viewers, anchor, yaw), skippable = true) { finish(server, run) }
        runs.add(run)
        cardForOthers(server, kind, viewers)
        Hha.LOGGER.info("RPG-Szene {} für {} Spieler", kind.stageId, viewers.size)
        return true
    }

    private fun worldOf(kind: Kind, trigger: ServerPlayerEntity?): RegistryKey<World> = when (kind) {
        Kind.TARTAROS, Kind.PALACE, Kind.END -> HadesGate.TARTAROS
        else -> trigger?.entityWorld?.registryKey?.takeIf { kind != Kind.ACT_END } ?: OlympusLayout.WORLD
    }

    /** Anker der Szene: feste Anker für Inseln und Akt-Ende, sonst Lage und Blick des ersten Zuschauers. */
    private fun anchorOf(kind: Kind, lead: ServerPlayerEntity, world: ServerWorld): Pair<Vec3d, Float> {
        val fixed = when (kind) {
            Kind.ISLE_B -> RpgStages.ISLE_ANCHORS['B']
            Kind.ISLE_C -> RpgStages.ISLE_ANCHORS['C']
            Kind.ISLE_D -> RpgStages.ISLE_ANCHORS['D']
            Kind.ISLE_E -> RpgStages.ISLE_ANCHORS['E']
            Kind.ISLE_F -> RpgStages.ISLE_ANCHORS['F']
            Kind.ACT_END -> RpgStages.ACT_END_ANCHOR
            else -> null
        }
        if (fixed != null && world.registryKey == OlympusLayout.WORLD) return Vec3d(fixed.x, fixed.y, fixed.z) to fixed.yaw
        val at = RpgRests.ground(world, lead.entityPos, GROUND_UP, GROUND_DOWN)
        if (kind == Kind.PALACE) {
            // Blick zum Palast (das Ziel nach dem fünften Glied), sonst der Blick des Spielers.
            val target = TartarosGuide.target(world, lead)
            if (target != null && target.second == "palace") return at to RpgStages.yawOf(target.first.x - at.x, target.first.z - at.z)
        }
        return at to lead.yaw
    }

    /** Die Zeitleiste auf dem Server: Szene starten, je Tick halten, nach [Run.length] Ticks Ende. */
    private fun steps(server: MinecraftServer, run: Run, viewers: List<ServerPlayerEntity>, anchor: Vec3d, yaw: Float): Sequence<Unit> = sequence {
        Cine.start(viewers)
        Cine.stage(viewers, run.kind.stageId, anchor, yaw, viewers)
        while (run.age < run.length) {
            if (run.kind == Kind.PALACE && run.age == RpgStoryStages.PALACE_CARD_AT) {
                run.cardSent = true
                // Zuschauer und alle, die gerade in keiner anderen Szene stecken.
                currentCard(server, started(server).filter { it.uuid in run.players || !Cine.isPlaying(it) })
            }
            yield(Unit)
            run.age++
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Tick
    // ---------------------------------------------------------------------------------------------

    private fun bind(server: MinecraftServer) {
        if (owner !== server) {
            runs.clear()
            pending.clear()
            queuedCard = null
            cardQuiet = 0
            shownCards.clear()
            owner = server
        }
    }

    private fun tick(server: MinecraftServer) {
        if (owner !== server) return
        if (builtIn(server) == null) return
        for (run in runs.toList()) tickRun(server, run)
        tickPending(server)
        tickCard(server)
    }

    private fun tickRun(server: MinecraftServer, run: Run) {
        val players = online(server, run.players)
        run.players.retainAll(players.map { it.uuid }.toSet())
        if (players.isEmpty()) {
            run.skipped = true
            run.scene.skip()
            return
        }
        for (p in players) hold(server, run, p)
        if (run.skip.pending) {
            run.skipped = true
            run.scene.skip()
            return
        }
        run.scene.tick()
    }

    /** Ruhig = niemand in einer Szene (Boss, Ankunft, unsere), niemand in der Halte. */
    private fun quiet(server: MinecraftServer): Boolean =
        runs.isEmpty() && server.playerManager.playerList.none { Cine.isPlaying(it) || RpgArrival.isProtected(it) }

    private fun tickPending(server: MinecraftServer) {
        if (pending.isEmpty()) return
        if (!quiet(server)) {
            pending.forEach { it.quiet = 0 }
            return
        }
        val next = pending.first()
        if (++next.quiet < AFTER_QUIET) return
        // Kämpft noch jemand in der Welt der Szene (Arena noch offen), kurz auf ihn warten.
        val world = server.getWorld(worldOf(next.kind, null))
        val fighting = world?.players?.any { RpgWorld.of(server).hasStarted(it.uuid) && RpgRules.inFight(it) } == true
        if (fighting && ++next.waited < FIGHT_WAIT_MAX) return
        pending.remove(next)
        start(server, next.kind, null)
    }

    private fun tickCard(server: MinecraftServer) {
        val id = queuedCard ?: return
        val current = RpgStory.current(server)
        if (current.id != id || id in shownCards) {
            queuedCard = null
            return
        }
        val calm = quiet(server) && pending.isEmpty() && server.playerManager.playerList.none { RpgRules.inFight(it) }
        cardQuiet = if (calm) cardQuiet + 1 else 0
        if (cardQuiet < CARD_QUIET) return
        queuedCard = null
        currentCard(server, started(server))
    }

    // ---------------------------------------------------------------------------------------------
    // Ende
    // ---------------------------------------------------------------------------------------------

    /** Fester Endzustand (genau einmal, aus `SceneRun.finish`). */
    private fun finish(server: MinecraftServer, run: Run) {
        runs.remove(run)
        val players = online(server, run.players)
        if (players.isNotEmpty()) {
            if (run.skipped) Cine.stageStop(players)
            Cine.end(players)
            if (run.skipped) Cine.cut(players)
            if (run.kind == Kind.END) {
                // Die Abspann-Musik läuft in die Rolle hinein; übersprungen beginnt sie hier.
                if (run.skipped && run.age < RpgStoryStages.END_MUSIC_AT) Cine.music(players, HhaSounds.THEME_CREDITS)
            } else {
                Cine.music(players, null)
            }
        }
        // Sicher zurück: wer vom Platz gerückt ist, steht wieder dort; keine Geschwindigkeit, keine Fallstrecke.
        for (player in players) hold(server, run, player)
        RpgServer.markAllDirty()
        after(server, run.kind, players, run.skipped, card = !run.cardSent)
    }

    /** Was nach der Szene gilt (auch ohne Zuschauer): Karten, Abspann, Epilog. */
    private fun after(server: MinecraftServer, kind: Kind, viewers: List<ServerPlayerEntity>, skipped: Boolean, card: Boolean) {
        when (kind) {
            Kind.ISLE_D, Kind.ISLE_E, Kind.ISLE_F -> ISLE_CHAPTERS[kind]?.let { shownCards.add(it) }
            Kind.ACT_END -> currentCard(server, started(server))
            Kind.PALACE -> if (card) currentCard(server, started(server))
            Kind.END -> {
                RpgStory.finishStory(server)
                shownCards.add("epilogue")
                val credits = RpgStory.credits(server)
                RpgServer.event(started(server), credits.kind, credits.numbers, credits.texts)
            }
            else -> {}
        }
        if (skipped && viewers.isEmpty()) Hha.LOGGER.info("RPG-Szene {}: ohne Zuschauer, Folgen eingetreten", kind.stageId)
    }

    /** Wer nicht zusieht, bekommt nur die Kapitelkarte der Insel (D, E, F). */
    private fun cardForOthers(server: MinecraftServer, kind: Kind, viewers: List<ServerPlayerEntity>) {
        val id = ISLE_CHAPTERS[kind] ?: return
        val chapter = RpgStory.chapters(RpgWorld.of(server).path).firstOrNull { it.id == id } ?: return
        val ids = viewers.map { it.uuid }.toSet()
        val others = started(server).filter { it.uuid !in ids && !Cine.isPlaying(it) }
        if (others.isNotEmpty()) Cine.chapter(others, chapter.kickerKey, chapter.titleKey, chapter.subKey, CARD_TICKS, color(server))
        shownCards.add(id)
    }

    /** Kapitelkarte des laufenden Kapitels an [players]; gilt danach als gezeigt. */
    private fun currentCard(server: MinecraftServer, players: List<ServerPlayerEntity>) {
        val chapter = RpgStory.current(server)
        if (chapter.id in shownCards) return
        shownCards.add(chapter.id)
        if (players.isNotEmpty()) Cine.chapter(players, chapter.kickerKey, chapter.titleKey, chapter.subKey, CARD_TICKS, color(server))
    }

    private fun stopAll(server: MinecraftServer) {
        if (owner !== server) return
        // Fester Endzustand auch beim Anhalten: Laufende Szenen enden mit ihren Folgen; ein wartendes Ende schließt
        // den Epilog trotzdem (sonst bliebe die Welt ohne Abschluss, `onChapterDone` meldet `u3_titan` nie wieder).
        for (run in runs.toList()) {
            run.skipped = true
            run.scene.skip()
        }
        if (pending.any { it.kind == Kind.END }) RpgStory.finishStory(server)
        runs.clear()
        pending.clear()
        queuedCard = null
        owner = null
    }

    private fun onLeave(server: MinecraftServer, player: ServerPlayerEntity) {
        if (owner !== server) return
        val run = runOf(player.uuid) ?: return
        run.players.remove(player.uuid)
        run.skip.recheck(online(server, run.players))
    }

    // ---------------------------------------------------------------------------------------------
    // Hilfen
    // ---------------------------------------------------------------------------------------------

    /** Hält [player] auf seinem Platz: zurücksetzen, wenn weiter als [drift] weg, Geschwindigkeit null, kein Fall. */
    private fun hold(server: MinecraftServer, run: Run, player: ServerPlayerEntity, drift: Double = DRIFT) {
        val home = run.homes[player.uuid] ?: return
        val world = server.getWorld(run.world) ?: return
        if (player.entityWorld !== world) return
        if (player.entityPos.squaredDistanceTo(home) > drift * drift) {
            world.getChunk(BlockPos.ofFloored(home))
            player.teleport(world, home.x, home.y, home.z, emptySet(), player.yaw, player.pitch, false)
        }
        player.velocity = Vec3d.ZERO
        player.velocityDirty = true
        player.fallDistance = 0.0
    }

    private fun runOf(id: UUID): Run? = runs.firstOrNull { id in it.players }

    private fun online(server: MinecraftServer, ids: Collection<UUID>): List<ServerPlayerEntity> =
        ids.mapNotNull { server.playerManager.getPlayer(it) }.filter { !it.isDisconnected }

    private fun started(server: MinecraftServer): List<ServerPlayerEntity> {
        val state = RpgWorld.of(server)
        return server.playerManager.playerList.filter { state.hasStarted(it.uuid) && !it.isDisconnected }
    }

    private fun color(server: MinecraftServer): Int = when (val path = RpgWorld.of(server).path) {
        "heaven" -> RpgStages.HEAVEN_COLOR
        "hell" -> RpgStages.HELL_COLOR
        else -> HhaRpg.path(path)?.color?.and(0xFFFFFF) ?: RpgStages.HEAVEN_COLOR
    }
}
