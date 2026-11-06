package dev.henny.hha.logic.world

import dev.henny.hha.HhaBlocks
import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.HermesEntity
import dev.henny.hha.entity.boss.HermesPose
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.HhaVoiceLengths
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.entity.SpawnReason
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.Heightmap
import java.util.Random
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Hermes ist auch der Seelenführer, und genau das tut er hier: Im Ödland taucht er immer wieder als Erscheinung vor dem
 * Spieler auf, wartet, und fliegt weiter, sobald man nah genug ist. Der Weg führt zum versiegelten Tor des Hades.
 *
 * Tore liegen auf einem festen Raster, das nur vom Weltseed abhängt. Es gibt also nichts zu speichern, und jeder Spieler
 * wird immer zum nächsten Tor geführt, egal wo er gerade steht.
 */
object TartarosGuide {

    private const val GRID = 1536
    private const val SPAWN_AHEAD = 36.0
    private const val WAIT_TICKS = 20 * 25
    private const val NEAR = 15.0
    private const val FLIGHT_TICKS = 34

    private enum class Phase { WAITING, FLYING, FINAL }

    private class Sighting(val entity: HermesEntity, val heading: Vec3d) {
        var phase = Phase.WAITING
        var ticks = 0
    }

    private class Trail {
        var cooldown = 60
        var sighting: Sighting? = null
        var lines = 0
        var finished = false
    }

    private val trails = HashMap<UUID, Trail>()

    /** Beim Betreten: Er lässt nicht lange auf sich warten. */
    fun greet(player: ServerPlayerEntity) {
        trails.getOrPut(player.uuid) { Trail() }.apply {
            cooldown = 70
            finished = false
        }
    }

    fun gateNear(world: ServerWorld, x: Double, z: Double): BlockPos {
        val cellX = Math.floorDiv(x.toInt() + GRID / 2, GRID)
        val cellZ = Math.floorDiv(z.toInt() + GRID / 2, GRID)
        val random = Random(world.seed xor (cellX * 341873128712L) xor (cellZ * 132897987541L))
        val ox = random.nextInt(GRID / 3) - GRID / 6
        val oz = random.nextInt(GRID / 3) - GRID / 6
        return BlockPos(cellX * GRID + ox, 0, cellZ * GRID + oz)
    }

    fun tick(server: MinecraftServer) {
        val world = server.getWorld(HadesGate.TARTAROS) ?: return
        val present = HashSet<UUID>()
        for (player in world.players) {
            if (player.isSpectator) continue
            present.add(player.uuid)
            update(world, player, trails.getOrPut(player.uuid) { Trail() })
        }
        val iterator = trails.entries.iterator()
        while (iterator.hasNext()) {
            val (id, trail) = iterator.next()
            if (id !in present) {
                trail.sighting?.entity?.discard()
                iterator.remove()
            }
        }
    }

    private fun update(world: ServerWorld, player: ServerPlayerEntity, trail: Trail) {
        val gate = gateNear(world, player.x, player.z)
        val toGate = Vec3d(gate.x + 0.5 - player.x, 0.0, gate.z + 0.5 - player.z)
        val distance = toGate.length()
        if (distance < 110.0) buildGate(world, gate)

        val sighting = trail.sighting
        if (sighting != null) {
            if (sighting.entity.isRemoved) {
                trail.sighting = null
            } else {
                advance(world, player, trail, sighting, gate)
            }
            return
        }
        if (trail.finished) {
            // Erst wer das Tor wieder weit hinter sich lässt, wird noch einmal abgeholt.
            if (distance > 300.0) trail.finished = false
            return
        }
        if (--trail.cooldown > 0) return

        // Mehrere Spieler auf einem Haufen teilen sich eine Erscheinung.
        val shared = trails.values.any { it !== trail && it.sighting?.entity?.squaredDistanceTo(player) ?: Double.MAX_VALUE < 80.0 * 80.0 }
        if (shared) {
            trail.cooldown = 40
            return
        }
        val final = distance < 60.0
        val heading = if (distance < 1.0) Vec3d(0.0, 0.0, 1.0) else toGate.normalize()
        val sideways = Vec3d(-heading.z, 0.0, heading.x).multiply((world.random.nextDouble() - 0.5) * 16.0)
        val spot = if (final) {
            Vec3d(gate.x + 0.5, 0.0, gate.z + 12.5)
        } else {
            player.entityPos.add(heading.multiply(SPAWN_AHEAD)).add(sideways)
        }
        val x = spot.x.toInt()
        val z = spot.z.toInt()
        if (!world.isChunkLoaded(x shr 4, z shr 4)) {
            trail.cooldown = 40
            return
        }
        val y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) + if (final) 0.2 else 1.2
        val ghost = HhaEntities.HERMES.create(world, SpawnReason.EVENT) ?: return
        ghost.isIllusion = true
        ghost.guide = true
        ghost.stage = 1
        ghost.pose = if (final) HermesPose.KNEEL else HermesPose.SNAP
        ghost.setPosition(spot.x, y, spot.z)
        world.spawnEntity(ghost)
        face(ghost, player.entityPos)
        val at = ghost.entityPos.add(0.0, 1.5, 0.0)
        Vfx.flare(world, Vfx.Style.GOLD, at, 3f, 10)
        Vfx.feathers(world, Vfx.Style.HEAVEN, at, 18, 1.4f, 50)
        world.playSound(null, ghost.blockPos, HhaSounds.BLINK, SoundCategory.NEUTRAL, 3f, 0.9f)
        trail.sighting = Sighting(ghost, heading).also { if (final) it.phase = Phase.FINAL }

        if (final) {
            speak(player, "guide.end", 70)
            player.sendMessage(Text.translatable("hha.tartaros.palace").formatted(Formatting.GRAY, Formatting.ITALIC), false)
        } else {
            // Nicht bei jeder Erscheinung ein Satz, sonst nutzt sich der Witz ab.
            when (trail.lines++) {
                0 -> speak(player, "guide.follow", 66)
                3 -> speak(player, "guide.back", 56)
                7 -> speak(player, "guide.slow", 70)
            }
        }
    }

    private fun speak(player: ServerPlayerEntity, line: String, ticks: Int) {
        val key = "hha.hermes.line.$line"
        Cine.dialog(listOf(player), Cine.SPEAKER_HERMES, key, HhaVoiceLengths.ticks(key, ticks))
    }

    private fun advance(world: ServerWorld, player: ServerPlayerEntity, trail: Trail, sighting: Sighting, gate: BlockPos) {
        val ghost = sighting.entity
        sighting.ticks++
        when (sighting.phase) {
            Phase.WAITING -> {
                face(ghost, player.entityPos)
                if (sighting.ticks % 30 == 0) Vfx.feathers(world, Vfx.Style.HEAVEN, ghost.entityPos.add(0.0, 1.5, 0.0), 2, 0.6f, 50)
                if (ghost.squaredDistanceTo(player) < NEAR * NEAR || sighting.ticks > WAIT_TICKS) {
                    sighting.phase = Phase.FLYING
                    sighting.ticks = 0
                    ghost.pose = HermesPose.DASH
                    Vfx.trail(world, Vfx.Style.GOLD, ghost, 0.8f, FLIGHT_TICKS + 10)
                    world.playSound(null, ghost.blockPos, HhaSounds.DASH, SoundCategory.NEUTRAL, 2.5f, 1.15f)
                }
            }
            Phase.FLYING -> {
                val direction = Vec3d(gate.x + 0.5 - ghost.x, 0.0, gate.z + 0.5 - ghost.z).let { if (it.lengthSquared() < 1.0) sighting.heading else it.normalize() }
                ghost.setPosition(ghost.entityPos.add(direction.multiply(1.5)).add(0.0, 0.14, 0.0))
                face(ghost, ghost.entityPos.add(direction))
                if (sighting.ticks >= FLIGHT_TICKS) {
                    vanish(world, ghost)
                    trail.sighting = null
                    trail.cooldown = 80 + world.random.nextInt(140)
                }
            }
            Phase.FINAL -> {
                face(ghost, player.entityPos)
                if (sighting.ticks > 20 * 12) {
                    vanish(world, ghost)
                    trail.sighting = null
                    trail.finished = true
                }
            }
        }
    }

    private fun vanish(world: ServerWorld, ghost: HermesEntity) {
        val at = ghost.entityPos.add(0.0, 1.5, 0.0)
        Vfx.flare(world, Vfx.Style.GOLD, at, 2.4f, 8)
        Vfx.feathers(world, Vfx.Style.HEAVEN, at, 24, 1.8f, 70)
        ghost.discard()
    }

    private fun face(entity: HermesEntity, target: Vec3d) {
        val d = target.subtract(entity.entityPos)
        val yaw = (Math.toDegrees(kotlin.math.atan2(d.z, d.x)) - 90.0).toFloat()
        entity.yaw = yaw
        entity.bodyYaw = yaw
        entity.headYaw = yaw
    }

    /**
     * Das versiegelte Tor des Hades. Vorerst nur ein Ziel am Ende des Weges; dahinter kommt, was als Nächstes entsteht.
     * Einmal gebaut bleibt es stehen, erkannt wird es am Stein im Sockel.
     */
    private fun buildGate(world: ServerWorld, gate: BlockPos) {
        world.getChunk(gate.x shr 4, gate.z shr 4)
        val floor = world.getTopY(Heightmap.Type.MOTION_BLOCKING, gate.x, gate.z)
        for (y in floor downTo (floor - 40).coerceAtLeast(world.bottomY)) {
            if (world.getBlockState(BlockPos(gate.x, y, gate.z)).isOf(HhaBlocks.GATE_STONE)) return
        }
        fun put(pos: BlockPos, state: BlockState) = world.setBlockState(pos, state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
        val radius = 20
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                val r = hypot(dx.toDouble(), dz.toDouble())
                if (r > radius + 0.5) continue
                val column = BlockPos(gate.x + dx, floor, gate.z + dz)
                for (dy in 1..26) put(column.up(dy), Blocks.AIR.defaultState)
                val state = when {
                    r < 1.0 -> HhaBlocks.GATE_STONE
                    r > radius - 1.5 -> HhaBlocks.PALE_CRYSTAL
                    (abs(dx) + abs(dz)) % 4 == 0 -> HhaBlocks.GATE_STONE
                    else -> HhaBlocks.DEEP_ASH
                }
                put(column, state.defaultState)
                for (dy in 1..((radius - r) * 0.5).toInt() + 2) put(column.down(dy), HhaBlocks.DEEP_ASH.defaultState)
            }
        }
        // Hohe Wand mit einem runden, dicht verschlossenen Bogen aus Kristall.
        for (dx in -11..11) {
            for (h in 1..22) {
                val arch = hypot(dx.toDouble(), h - 10.0)
                val pos = BlockPos(gate.x + dx, floor + h, gate.z - 4)
                val top = 22 - abs(dx) / 2
                if (h > top) continue
                put(pos, (if (arch < 7.5) HhaBlocks.PALE_CRYSTAL else HhaBlocks.GATE_STONE).defaultState)
            }
        }
        for ((dx, dz) in listOf(-14 to 2, 14 to 2, -14 to 12, 14 to 12)) {
            for (h in 1..7) put(BlockPos(gate.x + dx, floor + h, gate.z + dz), HhaBlocks.GATE_STONE.defaultState)
            put(BlockPos(gate.x + dx, floor + 8, gate.z + dz), HhaBlocks.GREEN_FIRE.defaultState)
        }
    }
}
