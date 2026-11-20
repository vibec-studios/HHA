package dev.henny.hha.logic.world

import dev.henny.hha.HhaSounds
import dev.henny.hha.logic.Vfx
import dev.henny.hha.world.arena.ApproachSpec
import dev.henny.hha.world.arena.Waypoint
import dev.henny.hha.world.olymp.OlympusArenas
import dev.henny.hha.world.olymp.OlympusBridges
import dev.henny.hha.world.olymp.OlympusLayout
import net.minecraft.block.Blocks
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Der Weg durch den Olymp (Überarbeitung 2, PLAN 8.2). Gerüst von P00, gefüllt von P04:
 *
 * - **Siegelbrücken:** In vier Brücken fehlen die mittleren 16 Blöcke. Sobald der Boss davor besiegt ist und ein Spieler
 *   näher als [OlympusLayout.SEAL_RESTORE_DISTANCE] ist, setzt [tick] die Lücke aus demselben Bauplan nach
 *   ([OlympusBridges.draw] mit `Part.GAP`). Ob das schon geschehen ist, verrät ein Markierblock im Kiel unter der Mitte
 *   der Lücke; so passiert es je Welt genau einmal, auch über Neustarts.
 * - **Fernsäule:** [pointNext] zeigt eine Säule zum nächsten Ziel. Sie erscheint nach der Ankunft im Olymp (hier
 *   erkannt), nach jedem Sieg (die Kämpfe rufen [pointNext], [tick] fängt zusätzlich jeden neuen Fortschritt ab) und
 *   nach dem Nachsetzen einer Brücke. Doppelte Aufrufe kurz hintereinander zeigen nur eine Säule.
 * - **Hinweis an der Lücke:** Wer an einer Sperre steht, liest, welcher Boss die Brücke versiegelt.
 *
 * Die Signaturen [pointNext], [onProgress] und [tick] bleiben; [tick] hängt im Server-Tick (`HermesFights.init`).
 */
object OlympusPath {

    /** So lange steht die Fernsäule. */
    const val BEACON_TICKS = 200

    /** Alle so viele Ticks: Siegel und Fortschritt prüfen. */
    private const val CHECK_EVERY = 20

    /** Nach der Ankunft so lange warten, bis das Bild aufgeklart ist. */
    private const val ARRIVAL_DELAY = 40L

    /** Zwei Säulen für denselben Spieler frühestens so viele Ticks nacheinander. */
    private const val BEACON_REPEAT = 60L

    /** Hinweis an der Sperre: Abstand und Pause je Spieler. */
    private const val HINT_RANGE = 4.5
    private const val HINT_COOLDOWN = 120L

    private var server: MinecraftServer? = null
    private val restored = HashSet<String>()
    private val known = HashSet<String>()
    private val present = HashSet<UUID>()
    private val arrivals = HashMap<UUID, Long>()
    private val lastBeacon = HashMap<UUID, Long>()
    private val lastHint = HashMap<UUID, Long>()
    private var pending = false

    /**
     * Goldene Fernsäule für [player] zum nächsten Ziel laut [OlympusProgress] und [OlympusLayout]: Hermes (B), dann
     * Brontes (D), Kratos (E), Zeus (F). Nach Zeus nichts. Nur im Olymp.
     *
     * Überarbeitung 4 (P23, PLAN 5.4): Die Säule steht auf dem nächsten Wegpunkt des Wegs **zur** nächsten Insel (die
     * Brücke, die dort ankommt, [beaconSpot]), erst ab dem Kopftor auf dem Altar. Beide Modi.
     */
    fun pointNext(player: ServerPlayerEntity) {
        val world = player.entityWorld
        if (world.registryKey != OlympusLayout.WORLD) return
        val server = world.server ?: return
        val next = next(server) ?: return
        val now = world.time
        val last = lastBeacon[player.uuid]
        if (last != null && now - last in 0 until BEACON_REPEAT) return
        lastBeacon[player.uuid] = now
        Vfx.farBeacon(player, beaconSpot(player, next), BEACON_TICKS, false, Vfx.Style.GOLD)
    }

    // ---------------------------------------------------------------------------------------------
    // Wegführung über die Wegpunkte der Brücken (Überarbeitung 4, P23)
    // ---------------------------------------------------------------------------------------------

    /** Insel betreten: so weit unter … (gleiches Fenster wie `RpgStory.onIsle`) */
    const val ISLE_BELOW = 16

    /** … bis so weit über ihrem Boden. */
    const val ISLE_ABOVE = 48

    /** Kleiner Vorsprung, ab dem ein Wegpunkt als „vor“ bzw. „hinter“ dem Spieler gilt (Anteil der Weglänge). */
    private const val K_EPS = 1e-3

    /** Textstufe „in die Insel hinein“ (Eingang bzw. beim Rückweg der Altar, solange man noch nicht auf der Insel ist). */
    const val STEP_INTO = 3

    /** Textstufe „auf der Insel“ (Ziel ist der Altar). */
    const val STEP_ON_ISLE = 4

    /**
     * Ein Schritt des Wegs zu einer Insel: Zielpunkt [pos]; [point] = Wegpunkt (oder `null` = Altar bzw. Inselmitte);
     * [final] = auf der letzten Brücke vor der Insel (bzw. schon auf ihr); [n] = Stufe für den Zieltext (0 Brückenkopf,
     * 1 Engstelle, 2 Kopftor, [STEP_INTO], [STEP_ON_ISLE]); [forward] = Brücke in Bauweise gelaufen (Rückweg: false).
     */
    class WayStep(val pos: Vec3d, val bridge: ApproachSpec?, val point: Waypoint?, val n: Int, val final: Boolean, val forward: Boolean) {
        /** Rang für „am weitesten fortgeschritten“ (Mehrspieler-Zieltext): auf der letzten Brücke zählt die Stufe. */
        val rank: Int get() = if (final) 10 + n else 0
    }

    /** Brücken in Pfadreihenfolge (A–B … E–F) mit ihrem Weg aus dem Vertrag. */
    private val ways: List<Pair<OlympusBridges.Bridge, ApproachSpec>> by lazy {
        OlympusBridges.ALL.map { b -> b to OlympusArenas.APPROACHES.first { it.id == "bridge_${b.id}" } }
    }

    /** Ziel auf der Insel selbst: der Altar, sonst die Mitte. */
    fun isleGoal(isle: OlympusLayout.Isle): Vec3d = isle.altar?.let { Vec3d.ofBottomCenter(it) } ?: isle.centerVec

    /** Steht [pos] auf [isle] (waagerecht im Inselradius, Höhe [ISLE_BELOW] … [ISLE_ABOVE] um den Boden)? */
    fun onIsle(isle: OlympusLayout.Isle, pos: Vec3d): Boolean =
        hypot(pos.x - isle.centerVec.x, pos.z - isle.centerVec.z) <= isle.radius &&
            pos.y >= isle.floorY - ISLE_BELOW && pos.y <= isle.floorY + ISLE_ABOVE

    /**
     * Nächster Schritt für [player] auf dem Weg zur Insel [target] (P23, Briefing 1): Brückenkopf → Engstelle → Kopftor →
     * Inseleingang, über jede Brücke dazwischen; auf der Insel der Altar. Vorwärts gilt `Approach.next` (erreichte Punkte
     * merkt `Approach` je Spieler); wer eine Brücke rückwärts läuft (Kapitel U0 von F zurück zum Dreiweg), bekommt die
     * Punkte in umgekehrter Folge nach seinem Fortschritt k. Außerhalb des Olymps oder ohne Lage: `null`.
     *
     * Lage des Spielers: auf einer Brücke (`Approach.progress` ≠ null) → diese; sonst auf einer Insel → die Brücke von
     * dort zum Ziel; sonst die nächste Insel (Flug, Nebeninsel).
     */
    fun wayTo(player: ServerPlayerEntity, target: OlympusLayout.Isle): WayStep? {
        if (player.entityWorld.registryKey != OlympusLayout.WORLD) return null
        val pos = player.entityPos
        val t = OlympusLayout.ISLES.indexOf(target)
        if (t < 0) return null
        if (onIsle(target, pos)) return WayStep(isleGoal(target), null, null, STEP_ON_ISLE, final = true, forward = true)
        // Auf einer Brücke: die, deren Weglinie den Spieler einschließt (zwei nur an einem Inselrand; dann die zum Ziel hin).
        val on = ways.indices.mapNotNull { j -> Approach.progress(player, ways[j].second)?.let { j to it } }
            .minByOrNull { (j, _) -> if (t >= j + 1) t - (j + 1) else j - t }
        if (on != null) {
            val (j, k) = on
            return if (t >= j + 1) forwardOnBridge(player, j, k, t) else reverseOnBridge(player, j, k, t)
        }
        val here = OlympusLayout.ISLES.indexOfFirst { onIsle(it, pos) }.takeIf { it >= 0 }
            ?: OlympusLayout.ISLES.indices.minBy { i -> OlympusLayout.ISLES[i].let { hypot(pos.x - it.centerVec.x, pos.z - it.centerVec.z) - it.radius } }
        return when {
            here == t -> WayStep(isleGoal(target), null, null, STEP_ON_ISLE, final = true, forward = true)
            here < t -> forwardFromIsle(player, here, t)
            else -> reverseFromIsle(player, here - 1, t)
        }
    }

    /** Vorwärts von Insel [j] über Brücke [j]: nächster nicht erreichter Punkt, sonst wieder ab dem Brückenkopf. */
    private fun forwardFromIsle(player: ServerPlayerEntity, j: Int, t: Int): WayStep {
        val spec = ways[j].second
        val p = Approach.next(player, spec) ?: spec.points.first()
        return step(spec, p, final = j + 1 == t, forward = true)
    }

    /** Vorwärts auf Brücke [j] bei Fortschritt [k]. Alles erreicht → nächste Brücke bzw. Altar der Zielinsel. */
    private fun forwardOnBridge(player: ServerPlayerEntity, j: Int, k: Double, t: Int): WayStep {
        val spec = ways[j].second
        val ks = pointKs(spec)
        val p = Approach.next(player, spec)
            ?: spec.points.firstOrNull { (ks[it.id] ?: 1.0) > k + K_EPS && !near(player, it) }
        if (p != null) return step(spec, p, final = j + 1 == t, forward = true)
        if (j + 1 == t) return WayStep(isleGoal(OlympusLayout.ISLES[t]), spec, null, STEP_INTO, final = true, forward = true)
        return forwardFromIsle(player, j + 1, t)
    }

    /** Rückwärts über Brücke [j] (von Insel j + 1 nach j), der Spieler steht noch auf Insel j + 1. */
    private fun reverseFromIsle(player: ServerPlayerEntity, j: Int, t: Int): WayStep = reverseOnBridge(player, j, Double.POSITIVE_INFINITY, t)

    /**
     * Rückwärts auf Brücke [j] bei Fortschritt [k]: Kopftor → Engstelle → Brückenkopf (der Inseleingang liegt auf der
     * Insel, die man verlässt). Ein Punkt ist erledigt, wenn er hinter dem Spieler liegt (k des Punkts ≥ k) oder in
     * Reichweite ist. Alles erledigt → Brücke davor bzw. Altar der Zielinsel.
     */
    private fun reverseOnBridge(player: ServerPlayerEntity, j: Int, k: Double, t: Int): WayStep {
        val spec = ways[j].second
        val ks = pointKs(spec)
        val order = spec.points.dropLast(1).reversed()
        val idx = order.indexOfFirst { (ks[it.id] ?: 0.0) < k - K_EPS && !near(player, it) }
        if (idx >= 0) return WayStep(order[idx].pos, spec, order[idx], idx, final = j == t, forward = false)
        if (j == t) return WayStep(isleGoal(OlympusLayout.ISLES[t]), spec, null, STEP_INTO, final = true, forward = false)
        return reverseFromIsle(player, j - 1, t)
    }

    private fun step(spec: ApproachSpec, p: Waypoint, final: Boolean, forward: Boolean) =
        WayStep(p.pos, spec, p, spec.points.indexOf(p).coerceAtLeast(0), final, forward)

    private fun near(player: ServerPlayerEntity, p: Waypoint): Boolean =
        hypot(player.x - p.pos.x, player.z - p.pos.z) <= p.reach && abs(player.y - p.pos.y) <= Approach.HEIGHT_RANGE

    private val pointKCache = HashMap<String, Map<String, Double>>()

    /** Lage jedes Wegpunkts auf der Weglinie (0 … 1), wie `Approach` sie rechnet. */
    private fun pointKs(spec: ApproachSpec): Map<String, Double> = pointKCache.getOrPut(spec.id) {
        val line = Approach.route(spec)
        spec.points.associate { it.id to (Approach.measure(line, Double.MAX_VALUE, it.pos) ?: 0.0) }
    }

    /**
     * Fuß der Fernsäule zur Insel [isle] (Briefing 5): der nächste nicht erreichte Wegpunkt der Brücke, die auf [isle]
     * ankommt; ab dem Kopftor (nächster Punkt ist der Eingang oder alles erreicht) sowie auf oder hinter der Insel der
     * Altar. Wer noch vor dieser Brücke ist, sieht die Säule an ihrem Brückenkopf in der Ferne.
     */
    fun beaconSpot(player: ServerPlayerEntity, isle: OlympusLayout.Isle): Vec3d {
        val goal = isleGoal(isle)
        val way = ways.firstOrNull { it.first.to === isle }?.second ?: return goal
        if (onIsle(isle, player.entityPos)) return goal
        val p = Approach.next(player, way) ?: return goal
        return if (p === way.points.last()) goal else p.pos
    }

    /** Nächste Station auf dem Pfad (ohne Hekate, die am Dreiweg quer dazu steht), `null` nach Zeus. */
    fun next(server: MinecraftServer): OlympusLayout.Isle? {
        val progress = OlympusProgress.of(server)
        return when {
            !progress.isDone(OlympusProgress.HERMES) -> OlympusLayout.B
            !progress.isDone(OlympusProgress.BRONTES) -> OlympusLayout.D
            !progress.isDone(OlympusProgress.KRATOS) -> OlympusLayout.E
            !progress.isDone(OlympusProgress.ZEUS) -> OlympusLayout.F
            else -> null
        }
    }

    /** Ein Fortschritt ist erreicht (Sieg oder `/hha progress set`): beim nächsten Tick die Siegel prüfen. */
    fun onProgress(server: MinecraftServer, key: String) {
        pending = true
    }

    /** Jeden Server-Tick. */
    fun tick(server: MinecraftServer) {
        if (this.server !== server) reset(server)
        val world = server.getWorld(OlympusLayout.WORLD) ?: return
        val now = world.time
        if (pending || now % CHECK_EVERY == 0L) {
            pending = false
            watchProgress(server, world)
            watchArrivals(world, now)
            restoreSeals(server, world)
        }
        for ((id, at) in arrivals.entries.toList()) {
            if (now < at) continue
            arrivals.remove(id)
            val player = world.getPlayerByUuid(id) as? ServerPlayerEntity ?: continue
            pointNext(player)
        }
        if (now % 10L == 0L) hints(server, world, now)
    }

    private fun reset(server: MinecraftServer) {
        this.server = server
        restored.clear()
        known.clear()
        present.clear()
        arrivals.clear()
        lastBeacon.clear()
        lastHint.clear()
        known.addAll(OlympusProgress.of(server).done())
    }

    /** Jeder neue Fortschritt zeigt allen Spielern im Olymp die Säule zum nächsten Ziel. */
    private fun watchProgress(server: MinecraftServer, world: ServerWorld) {
        val done = OlympusProgress.of(server).done()
        val fresh = done.filter { it !in known }
        known.retainAll(done)
        known.addAll(done)
        if (fresh.isEmpty()) return
        for (player in world.players) if (!player.isSpectator) arrivals[player.uuid] = world.time + ARRIVAL_DELAY
    }

    /** Wer neu im Olymp ist, bekommt nach dem Aufklaren die Säule. */
    private fun watchArrivals(world: ServerWorld, now: Long) {
        val here = world.players.filter { !it.isSpectator }.map { it.uuid }.toSet()
        for (id in here) if (id !in present) arrivals[id] = now + ARRIVAL_DELAY
        present.clear()
        present.addAll(here)
    }

    private fun restoreSeals(server: MinecraftServer, world: ServerWorld) {
        val progress = OlympusProgress.of(server)
        for (bridge in OlympusBridges.ALL) {
            val boss = bridge.sealedBy ?: continue
            if (bridge.id in restored || !progress.isDone(boss)) continue
            val center = bridge.gapCenter() ?: continue
            val marker = bridge.marker() ?: continue
            val near = world.players.any { !it.isSpectator && it.squaredDistanceTo(Vec3d.ofCenter(center)) < DISTANCE_SQ }
            if (!near || !loaded(world, bridge)) continue
            if (!world.getBlockState(marker).isOf(Blocks.CHISELED_QUARTZ_BLOCK)) restore(world, bridge, center)
            restored.add(bridge.id)
        }
    }

    private val DISTANCE_SQ = OlympusLayout.SEAL_RESTORE_DISTANCE.toDouble() * OlympusLayout.SEAL_RESTORE_DISTANCE

    /**
     * Zum Testen (`/hha`, P06): setzt alle noch offenen Siegellücken sofort nach, egal wie der Fortschritt steht. Nur in
     * geladenen Chunks; gibt zurück, wie viele Lücken geschlossen wurden.
     */
    fun forceRestore(server: MinecraftServer): Int {
        val world = server.getWorld(OlympusLayout.WORLD) ?: return 0
        var count = 0
        for (bridge in OlympusBridges.ALL) {
            val center = bridge.gapCenter() ?: continue
            val marker = bridge.marker() ?: continue
            if (!loaded(world, bridge) || world.getBlockState(marker).isOf(Blocks.CHISELED_QUARTZ_BLOCK)) continue
            restore(world, bridge, center)
            restored.add(bridge.id)
            count++
        }
        return count
    }

    private fun gapBox(bridge: OlympusBridges.Bridge): BlockBox? {
        val gap = bridge.gap ?: return null
        val cells = (gap.first - 1..gap.last + 1).map { bridge.cells[it] }
        return BlockBox(cells.minOf { it.x } - 4, bridge.box.minY, cells.minOf { it.z } - 4, cells.maxOf { it.x } + 4, bridge.box.maxY, cells.maxOf { it.z } + 4)
    }

    private fun loaded(world: ServerWorld, bridge: OlympusBridges.Bridge): Boolean {
        val box = gapBox(bridge) ?: return false
        val min = ChunkPos(BlockPos(box.minX, 0, box.minZ))
        val max = ChunkPos(BlockPos(box.maxX, 0, box.maxZ))
        for (x in min.x..max.x) for (z in min.z..max.z) if (!world.isChunkLoaded(x, z)) return false
        return true
    }

    /** Setzt die Lücke aus dem Bauplan nach: ein heller Klang, ein paar goldene Federn, die Säule zum nächsten Ziel. */
    private fun restore(world: ServerWorld, bridge: OlympusBridges.Bridge, center: BlockPos) {
        val box = gapBox(bridge) ?: return
        OlympusBridges.draw(LiveCanvas(world, box), bridge, OlympusBridges.Part.GAP, sealed = false)
        val at = Vec3d.ofCenter(center)
        world.playSound(null, center, HhaSounds.CHIME, SoundCategory.BLOCKS, 2.5f, 0.9f)
        world.playSound(null, center, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.BLOCKS, 1.6f, 0.8f)
        Vfx.feathers(world, Vfx.Style.GOLD, at.add(0.0, 0.5, 0.0), 6, 3f, 40)
        for (player in world.players) {
            if (player.squaredDistanceTo(at) > DISTANCE_SQ) continue
            player.sendMessage(Text.translatable("hha.olympus.bridge.restored").formatted(Formatting.GOLD), true)
            arrivals[player.uuid] = world.time + 30
        }
    }

    /** Hinweis an der Sperre einer noch versiegelten Brücke. */
    private fun hints(server: MinecraftServer, world: ServerWorld, now: Long) {
        if (world.players.isEmpty()) return
        val progress = OlympusProgress.of(server)
        for (bridge in OlympusBridges.ALL) {
            val boss = bridge.sealedBy ?: continue
            if (progress.isDone(boss)) continue
            for (end in bridge.gapEnds()) {
                val at = Vec3d.ofCenter(end)
                for (player in world.players) {
                    if (player.isSpectator || player.squaredDistanceTo(at) > HINT_RANGE * HINT_RANGE) continue
                    val last = lastHint[player.uuid]
                    if (last != null && now - last in 0 until HINT_COOLDOWN) continue
                    lastHint[player.uuid] = now
                    player.sendMessage(Text.translatable("hha.olympus.bridge.sealed", Text.translatable("entity.hha.$boss")).formatted(Formatting.GOLD, Formatting.ITALIC), true)
                }
            }
        }
    }
}
