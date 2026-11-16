package dev.henny.hha.logic.world

import dev.henny.hha.HhaSounds
import dev.henny.hha.logic.Vfx
import dev.henny.hha.world.olymp.OlympusBridges
import dev.henny.hha.world.olymp.OlympusLayout
import dev.henny.hha.world.olymp.SkyCanvas
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.entity.EntityType
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
        val spot = next.altar ?: next.center
        Vfx.farBeacon(player, Vec3d(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5), BEACON_TICKS, false, Vfx.Style.GOLD)
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

    /** Leinwand direkt in der Welt, auf [box] beschränkt (nur geladene Chunks, siehe [loaded]). */
    private class LiveCanvas(private val world: ServerWorld, box: BlockBox) : SkyCanvas() {
        override val minX = box.minX
        override val maxX = box.maxX
        override val minZ = box.minZ
        override val maxZ = box.maxZ
        private val pos = BlockPos.Mutable()
        override fun put(x: Int, y: Int, z: Int, state: BlockState) {
            if (inside(x, z) && y in world.bottomY..world.topYInclusive) world.setBlockState(pos.set(x, y, z), state, Block.NOTIFY_LISTENERS)
        }
        override fun isFree(x: Int, y: Int, z: Int): Boolean = inside(x, z) && world.getBlockState(pos.set(x, y, z)).isAir
        override fun ground(x: Int, z: Int): Int = 0
        override fun chest(x: Int, y: Int, z: Int) {}
        override fun spawner(x: Int, y: Int, z: Int, type: EntityType<*>) {}
    }
}
