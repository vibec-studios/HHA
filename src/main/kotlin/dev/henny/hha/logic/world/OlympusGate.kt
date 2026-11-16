package dev.henny.hha.logic.world

import dev.henny.hha.Hha
import dev.henny.hha.HhaBlocks
import dev.henny.hha.HhaSounds
import dev.henny.hha.block.OlympusPortalBlock
import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.world.olymp.OlympusLayout
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.entity.Entity
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.BucketItem
import net.minecraft.item.Items
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.Heightmap
import net.minecraft.world.TeleportTarget
import net.minecraft.world.World
import java.util.UUID

/**
 * Das Wolkentor zwischen Oberwelt und Olymp (PLAN 7.1, 7.3):
 * - **Zünden:** Wassereimer auf eine Innenfläche eines Glowstone-Rahmens ([PortalFrames]); nur in der Oberwelt, im
 *   Nether verdampft das Wasser mit Hinweis. Abschaltbar über `olympus_portal`.
 * - **Hinweg:** immer zum Ankunftstor der Insel A ([OlympusLayout.ARRIVAL_SPOT]); [PortalLinks] merkt sich das Heimtor.
 * - **Rückweg:** zum gemerkten Heimtor; fehlt es, ein Wolkentor im Umkreis 16, sonst wird an sicherer Stelle eines
 *   gebaut. Ohne Eintrag zum Welt-Spawn.
 * - **Sturz:** Wer im Olymp unter [OlympusLayout.FALL_Y] fällt (und in keiner Arena steht), fällt in den Himmel der
 *   Oberwelt über seinem Heimtor, mit Sanftem Fall.
 *
 * [init] hängt sich an `UseBlockCallback` und den Server-Tick. Aufgerufen wird es beim Laden der Klasse
 * [OlympusPortalBlock] (Registrierung, auf Client und Server), mehrfacher Aufruf schadet nicht.
 */
object OlympusGate {

    /** Torfarbe des Übergangs (PLAN 7.3). */
    const val CLOUD_COLOR = 0xEAF4FF

    /** Ankunftshöhe nach einem Sturz und Mindestdauer des Sanften Falls. */
    private const val FALL_ARRIVAL_Y = 300.0
    private const val SLOW_FALL_TICKS = 600

    /** Umkreis der Torsuche und des Ersatzbaus auf dem Rückweg. */
    private const val SEARCH_RADIUS = 16

    /** Abstand des Ankunftspunkts vor einem Wolkentor (Rückweg). */
    private const val FRONT_DISTANCE = 1.5

    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        UseBlockCallback.EVENT.register(::onUse)
        ServerTickEvents.END_SERVER_TICK.register(::tick)
    }

    // -------------------------------------------------------------------------------------------------
    // Zünden
    // -------------------------------------------------------------------------------------------------

    private fun onUse(player: PlayerEntity, world: World, hand: Hand, hit: BlockHitResult): ActionResult {
        if (player.isSpectator) return ActionResult.PASS
        val stack = player.getStackInHand(hand)
        if (!stack.isOf(Items.WATER_BUCKET)) return ActionResult.PASS
        if (!HhaConfig.enabled("olympus_portal")) return ActionResult.PASS
        val dimension = world.registryKey
        if (dimension != World.OVERWORLD && dimension != World.NETHER) return ActionResult.PASS
        val inner = hit.blockPos.offset(hit.side)
        // Ein schon brennendes Tor schluckt den Klick, ohne das Wasser zu verbrauchen.
        if (world.getBlockState(inner).isOf(HhaBlocks.OLYMPUS_PORTAL)) return ActionResult.SUCCESS
        val frame = PortalFrames.find(world, inner) ?: return ActionResult.PASS
        if (world.isClient) return ActionResult.SUCCESS
        val server = (world as? ServerWorld)?.server ?: return ActionResult.PASS
        val center = Vec3d(frame.centerX(), frame.corner.y + frame.height / 2.0, frame.centerZ())
        if (dimension == World.NETHER) {
            // Wie Wasser im Nether: Es verdampft, der Eimer ist leer.
            (world as ServerWorld).spawnParticles(ParticleTypes.LARGE_SMOKE, center.x, center.y, center.z, 14, frame.width * 0.3, frame.height * 0.3, 0.3, 0.02)
            world.playSound(null, BlockPos.ofFloored(center), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.6f, 1.6f)
            player.sendMessage(Text.translatable("hha.olympus.portal_nether"), true)
            player.setStackInHand(hand, BucketItem.getEmptiedStack(stack, player))
            return ActionResult.SUCCESS
        }
        if (server.getWorld(OlympusLayout.WORLD) == null) {
            player.sendMessage(Text.translatable("hha.olympus.portal_missing"), true)
            return ActionResult.SUCCESS
        }
        PortalFrames.fill(world, frame)
        world.playSound(null, BlockPos.ofFloored(center), HhaSounds.OLYMPUS_PORTAL_ENTER, SoundCategory.BLOCKS, 0.5f, 1.15f)
        player.setStackInHand(hand, BucketItem.getEmptiedStack(stack, player))
        return ActionResult.SUCCESS
    }

    // -------------------------------------------------------------------------------------------------
    // Reise
    // -------------------------------------------------------------------------------------------------

    /** Ziel eines Wolkentors bei [pos] in [world] (vom Block aufgerufen). */
    fun target(world: ServerWorld, entity: Entity, pos: BlockPos): TeleportTarget? {
        val server = world.server ?: return null
        val olympus = server.getWorld(OlympusLayout.WORLD)
        if (olympus == null) {
            if (entity is ServerPlayerEntity) hintOnce(entity, "hha.olympus.portal_missing")
            return null
        }
        val axis = axisAt(world, pos)
        if (world.registryKey != OlympusLayout.WORLD) {
            if (entity is ServerPlayerEntity) {
                PortalLinks.of(server).setHome(entity.uuid, PortalLinks.Gate.of(world, pos, axis, cameFrom(entity, axis)))
            }
            ensureArrival(olympus)
            val after = TeleportTarget.PostDimensionTransition { arrived ->
                TeleportTarget.ADD_PORTAL_CHUNK_TICKET.onTransition(arrived)
                if (arrived is ServerPlayerEntity) arriveOlympus(arrived)
            }
            return TeleportTarget(olympus, OlympusLayout.ARRIVAL_SPOT, Vec3d.ZERO, OlympusLayout.ARRIVAL_YAW, 0f, after)
        }
        val after = TeleportTarget.PostDimensionTransition { arrived ->
            TeleportTarget.ADD_PORTAL_CHUNK_TICKET.onTransition(arrived)
            if (arrived is ServerPlayerEntity) GateFade.arrived(arrived, CLOUD_COLOR)
        }
        val home = (entity as? ServerPlayerEntity)?.let { PortalLinks.of(server).home(it.uuid) }
        return homeTarget(server, entity, home, after)
    }

    private fun arriveOlympus(player: ServerPlayerEntity) {
        GateFade.arrived(player, CLOUD_COLOR)
        val players = listOf(player)
        Cine.title(players, "hha.olympus.title", "hha.olympus.title.sub", 70)
        (player.entityWorld as? ServerWorld)?.playSound(null, player.blockPos, HhaSounds.CHIME, SoundCategory.BLOCKS, 0.6f, 1.2f)
        OlympusPath.pointNext(player)
    }

    /**
     * Fehlt die Ankunftsinsel (Bauplan noch nicht da oder kaputt), entsteht eine schlichte Notplattform samt Tor,
     * damit niemand ins Leere fällt und der Rückweg bleibt.
     */
    private fun ensureArrival(olympus: ServerWorld) {
        val spot = BlockPos.ofFloored(OlympusLayout.ARRIVAL_SPOT)
        olympus.getChunk(spot.x shr 4, spot.z shr 4)
        if (!olympus.getBlockState(spot.down()).isAir) return
        Hha.LOGGER.warn("Ankunftsinsel des Olymp fehlt bei {}: baue Notplattform", spot)
        val gate = OlympusLayout.ARRIVAL_GATE
        for (x in gate.x - 3..gate.x + OlympusLayout.ARRIVAL_GATE_WIDTH + 2) {
            // Plattform über Tor und Ankunftspunkt (der liegt nördlich des Tors, also bei kleinerem z).
            for (z in minOf(gate.z, spot.z) - 2..maxOf(gate.z, spot.z) + 3) {
                olympus.setBlockState(BlockPos(x, spot.y - 1, z), Blocks.SMOOTH_QUARTZ.defaultState, Block.NOTIFY_ALL)
            }
        }
        if (!olympus.getBlockState(gate).isOf(HhaBlocks.OLYMPUS_PORTAL)) {
            PortalFrames.build(olympus, gate, OlympusLayout.ARRIVAL_GATE_AXIS, OlympusLayout.ARRIVAL_GATE_WIDTH, OlympusLayout.ARRIVAL_GATE_HEIGHT)
        }
    }

    /** Rückweg: gemerktes Tor → Wolkentor im Umkreis → neues Tor; ohne Eintrag Welt-Spawn. */
    private fun homeTarget(server: MinecraftServer, entity: Entity, home: PortalLinks.Gate?, after: TeleportTarget.PostDimensionTransition): TeleportTarget {
        if (home == null) return spawnTarget(server, entity, after)
        val world = server.getWorld(home.worldKey)?.takeIf { it.registryKey != OlympusLayout.WORLD } ?: return spawnTarget(server, entity, after)
        world.getChunk(home.pos.x shr 4, home.pos.z shr 4)
        var side = home.side
        val frame = existingFrame(world, home.pos, home.axis)
            ?: nearestGate(world, home.pos)
            ?: buildReturnGate(world, home.pos, home.axis, side).also { side = it.second }.first
        val (spot, yaw) = frontSpot(world, frame, side)
        return TeleportTarget(world, spot, Vec3d.ZERO, yaw, 0f, after)
    }

    private fun spawnTarget(server: MinecraftServer, entity: Entity, after: TeleportTarget.PostDimensionTransition): TeleportTarget {
        val world = server.spawnWorld
        val pos = entity.getWorldSpawnPos(world, world.spawnPoint.pos)
        return TeleportTarget(world, Vec3d.ofBottomCenter(pos), Vec3d.ZERO, 0f, 0f, after)
    }

    private fun existingFrame(world: ServerWorld, pos: BlockPos, axis: Direction.Axis): PortalFrames.Frame? {
        val state = world.getBlockState(pos)
        if (!state.isOf(HhaBlocks.OLYMPUS_PORTAL)) return null
        return PortalFrames.find(world, pos, state.get(OlympusPortalBlock.AXIS)) ?: PortalFrames.find(world, pos, axis)
    }

    /** Nächstes Wolkentor im Würfel mit Kante 2 × [SEARCH_RADIUS] um [center]. */
    private fun nearestGate(world: ServerWorld, center: BlockPos): PortalFrames.Frame? {
        var best: BlockPos? = null
        var bestDistance = Double.MAX_VALUE
        val mutable = BlockPos.Mutable()
        val minY = maxOf(world.bottomY, center.y - SEARCH_RADIUS)
        val maxY = minOf(world.topYInclusive, center.y + SEARCH_RADIUS)
        for (dx in -SEARCH_RADIUS..SEARCH_RADIUS) {
            for (dz in -SEARCH_RADIUS..SEARCH_RADIUS) {
                for (y in minY..maxY) {
                    mutable.set(center.x + dx, y, center.z + dz)
                    if (!world.getBlockState(mutable).isOf(HhaBlocks.OLYMPUS_PORTAL)) continue
                    val distance = mutable.getSquaredDistance(center)
                    if (distance < bestDistance) {
                        bestDistance = distance
                        best = mutable.toImmutable()
                    }
                }
            }
        }
        val found = best ?: return null
        return PortalFrames.find(world, found, axisAt(world, found))
    }

    /**
     * Baut ein 2 × 3-Tor im 4 × 5-Glowstone-Rahmen an sicherer Stelle nahe [near]: fester Boden, drei Blöcke frei,
     * nicht in Flüssigkeit; Suche in einer Spirale bis [SEARCH_RADIUS], zuerst um die alte Höhe, dann an der
     * Oberfläche. Findet sich nichts, wird an [near] freigeräumt. Liefert Rahmen und Ankunftsseite.
     */
    private fun buildReturnGate(world: ServerWorld, near: BlockPos, axis: Direction.Axis, side: Int): Pair<PortalFrames.Frame, Int> {
        for ((dx, dz) in spiral(SEARCH_RADIUS)) {
            val x = near.x + dx
            val z = near.z + dz
            world.getChunk(x shr 4, z shr 4)
            val surface = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z)
            val heights = (near.y - 3..near.y + 3).sortedBy { kotlin.math.abs(it - near.y) } + surface
            for (y in heights) {
                for (s in intArrayOf(side, -side)) {
                    val corner = BlockPos(x, y, z)
                    if (fitsGate(world, corner, axis, s)) {
                        Hha.LOGGER.info("Wolkentor neu gebaut bei {} (altes Tor fehlte)", corner)
                        return PortalFrames.build(world, corner, axis) to s
                    }
                }
            }
        }
        // Notlösung: an der alten Stelle freiräumen und den Stand davor sichern.
        Hha.LOGGER.info("Wolkentor an alter Stelle {} erzwungen", near)
        val frame = PortalFrames.build(world, near, axis)
        val normal = normalOf(axis)
        for (a in 0..1) {
            val ground = near.offset(alongOf(axis), a).offset(normal, side * 2).down()
            if (world.getBlockState(ground).isAir || !world.getFluidState(ground).isEmpty) {
                world.setBlockState(ground, Blocks.SMOOTH_QUARTZ.defaultState, Block.NOTIFY_ALL)
            }
        }
        return frame to side
    }

    /** Passt ein 4 × 5-Rahmen mit Öffnungsecke [corner] hierher, mit Stand 2 vor dem Tor auf Seite [side]? */
    private fun fitsGate(world: ServerWorld, corner: BlockPos, axis: Direction.Axis, side: Int): Boolean {
        val along = alongOf(axis)
        val normal = normalOf(axis)
        for (a in -1..2) {
            val column = corner.offset(along, a)
            if (!solidGround(world, column.down())) return false
            for (h in 0..3) if (!free(world, column.up(h))) return false
        }
        for (a in 0..1) {
            val stand = corner.offset(along, a).offset(normal, side * 2)
            if (!solidGround(world, stand.down())) return false
            for (h in 0..2) if (!free(world, stand.up(h))) return false
        }
        return true
    }

    private fun solidGround(world: ServerWorld, pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        return world.getFluidState(pos).isEmpty && state.isSideSolidFullSquare(world, pos, Direction.UP)
    }

    private fun free(world: ServerWorld, pos: BlockPos): Boolean {
        val state = world.getBlockState(pos)
        return world.getFluidState(pos).isEmpty && (state.isAir || state.isReplaceable)
    }

    /** Ankunftspunkt [FRONT_DISTANCE] vor dem Rahmen, bevorzugt auf [side], Blick vom Tor weg. */
    private fun frontSpot(world: ServerWorld, frame: PortalFrames.Frame, side: Int): Pair<Vec3d, Float> {
        val center = Vec3d(frame.centerX(), frame.corner.y.toDouble(), frame.centerZ())
        return standInFront(world, center, frame.axis, side, FRONT_DISTANCE)
    }

    // -------------------------------------------------------------------------------------------------
    // Sturz vom Olymp und Server-Tick
    // -------------------------------------------------------------------------------------------------

    private fun tick(server: MinecraftServer) {
        GateFade.tick(server)
        HadesGate.tickOlympus(server)
        if (server.ticks % 5 != 0) return
        val olympus = server.getWorld(OlympusLayout.WORLD) ?: return
        for (player in olympus.players.toList()) {
            if (player.y >= OlympusLayout.FALL_Y || player.isSpectator || player.abilities.flying) continue
            if (Arenas.of(player) != null) continue
            fallHome(server, player)
        }
    }

    private fun fallHome(server: MinecraftServer, player: ServerPlayerEntity) {
        val home = PortalLinks.of(server).home(player.uuid)
        val overworld = server.overworld
        val column = if (home != null && home.worldKey == World.OVERWORLD) home.pos else overworld.spawnPoint.pos
        overworld.getChunk(column.x shr 4, column.z shr 4)
        val ground = overworld.getTopY(Heightmap.Type.MOTION_BLOCKING, column.x, column.z)
        // Mit Sanftem Fall sinkt man etwa 0,45 Blöcke je Tick; reicht die Mindestdauer nicht, wird sie verlängert.
        val ticks = maxOf(SLOW_FALL_TICKS, ((FALL_ARRIVAL_Y - ground) / 0.45).toInt() + 80)
        val after = TeleportTarget.PostDimensionTransition { arrived ->
            if (arrived is ServerPlayerEntity) {
                arrived.fallDistance = 0.0
                arrived.addStatusEffect(StatusEffectInstance(StatusEffects.SLOW_FALLING, ticks, 0, false, false, true))
                Cine.title(listOf(arrived), "hha.olympus.fall", "hha.olympus.fall.sub", 60)
            }
        }
        val spot = Vec3d(column.x + 0.5, FALL_ARRIVAL_Y, column.z + 0.5)
        player.teleportTo(TeleportTarget(overworld, spot, Vec3d.ZERO, player.yaw, 0f, after))
    }

    // -------------------------------------------------------------------------------------------------
    // Hilfen (auch für HadesGate)
    // -------------------------------------------------------------------------------------------------

    private val hinted = HashMap<UUID, Long>()

    /** Hinweis in der Aktionsleiste, höchstens alle 5 s je Spieler (das Tor fragt jeden Tick). */
    private fun hintOnce(player: ServerPlayerEntity, key: String) {
        val now = player.entityWorld.server?.ticks?.toLong() ?: 0L
        val last = hinted[player.uuid]
        if (last != null && now - last < 100) return
        hinted[player.uuid] = now
        player.sendMessage(Text.translatable(key), true)
    }

    fun axisAt(world: World, pos: BlockPos): Direction.Axis {
        val state: BlockState = world.getBlockState(pos)
        return if (state.contains(OlympusPortalBlock.AXIS)) state.get(OlympusPortalBlock.AXIS) else Direction.Axis.X
    }

    /** Richtung entlang der Torebene. */
    fun alongOf(axis: Direction.Axis): Direction = if (axis == Direction.Axis.X) Direction.EAST else Direction.SOUTH

    /** Normale der Torebene (positive Seite). */
    fun normalOf(axis: Direction.Axis): Direction = if (axis == Direction.Axis.X) Direction.SOUTH else Direction.EAST

    /** Von welcher Seite der Ebene kam [entity]? Gegen die Blickrichtung: Wer nach +n schaut, kam von −n. */
    fun cameFrom(entity: Entity, axis: Direction.Axis): Int {
        val look = entity.rotationVector
        val toward = if (axis == Direction.Axis.X) look.z else look.x
        return if (toward > 0.0) -1 else 1
    }

    /**
     * Ein Stand [distance] Blöcke vor der Torebene durch [bottomCenter] (x/z = Mitte, y = unterste Zelle), bevorzugt auf
     * [side], sonst auf der anderen Seite; der Boden wird bis zu vier Blöcke darunter gesucht. Blick vom Tor weg.
     */
    fun standInFront(world: ServerWorld, bottomCenter: Vec3d, axis: Direction.Axis, side: Int, distance: Double): Pair<Vec3d, Float> {
        fun spotAt(s: Int): Vec3d? {
            val offset = Vec3d.of(normalOf(axis).vector).multiply(s * distance)
            val base = bottomCenter.add(offset)
            val pos = BlockPos.ofFloored(base.x, bottomCenter.y + 0.5, base.z)
            for (drop in 0..4) {
                val feet = pos.down(drop)
                if (solidGround(world, feet.down()) && free(world, feet) && free(world, feet.up())) {
                    return Vec3d(base.x, feet.y.toDouble(), base.z)
                }
            }
            return null
        }
        val preferred = if (side < 0) -1 else 1
        val chosen = spotAt(preferred)?.let { it to preferred }
            ?: spotAt(-preferred)?.let { it to -preferred }
            ?: (bottomCenter.add(Vec3d.of(normalOf(axis).vector).multiply(preferred * distance)) to preferred)
        return chosen.first to yawAway(axis, chosen.second)
    }

    /** Blickwinkel vom Tor weg: Normale +z = Süden (0°), −z = Norden (180°), +x = Osten (−90°), −x = Westen (90°). */
    fun yawAway(axis: Direction.Axis, side: Int): Float = if (axis == Direction.Axis.X) {
        if (side > 0) 0f else 180f
    } else {
        if (side > 0) -90f else 90f
    }

    private fun spiral(radius: Int): Sequence<Pair<Int, Int>> = sequence {
        yield(0 to 0)
        for (r in 1..radius) {
            for (i in -r until r) yield(i to -r)
            for (i in -r until r) yield(r to i)
            for (i in -r until r) yield(-i to r)
            for (i in -r until r) yield(-r to -i)
        }
    }
}

/**
 * Der Übergang durch beide Tore (PLAN 7.3): Solange man im Tor steht, blendet das Bild in [PULL_TICKS] bis höchstens
 * [MAX] in die Torfarbe, dazu der Eintritts-Ton; nach der Ankunft klart es in [ARRIVE_TICKS] auf. Kein Kameraschütteln.
 */
object GateFade {

    const val PULL_TICKS = 40
    const val MAX = 0.85
    const val ARRIVE_TICKS = 30
    private const val RELEASE_TICKS = 14

    private class Touch(var lastTick: Long, val startTick: Long, val color: Int)

    private val touching = HashMap<UUID, Touch>()

    fun touching(player: ServerPlayerEntity, pos: BlockPos, color: Int, sound: SoundEvent) {
        val world = player.entityWorld as? ServerWorld ?: return
        val now = world.server?.overworld?.time ?: return
        val touch = touching[player.uuid]
        if (touch != null) {
            touch.lastTick = now
            return
        }
        touching[player.uuid] = Touch(now, now, color)
        Cine.fade(listOf(player), color, 0.0, MAX, PULL_TICKS)
        world.playSound(null, pos, sound, SoundCategory.BLOCKS, 1.0f, 1f)
    }

    /** Wer vor Ablauf wieder heraustritt, bekommt die freie Sicht zurück. */
    fun tick(server: MinecraftServer) {
        if (touching.isEmpty()) return
        val now = server.overworld.time
        val iterator = touching.entries.iterator()
        while (iterator.hasNext()) {
            val (id, touch) = iterator.next()
            val player = server.playerManager.getPlayer(id)
            if (player == null) {
                iterator.remove()
                continue
            }
            if (now - touch.lastTick > 4) {
                val elapsed = ((now - touch.startTick).toDouble() / PULL_TICKS * MAX).coerceIn(0.0, MAX)
                Cine.fade(listOf(player), touch.color, elapsed, 0.0, RELEASE_TICKS)
                iterator.remove()
            }
        }
    }

    fun arrived(player: ServerPlayerEntity, color: Int) {
        touching.remove(player.uuid)
        Cine.fade(listOf(player), color, MAX, 0.0, ARRIVE_TICKS)
    }
}
