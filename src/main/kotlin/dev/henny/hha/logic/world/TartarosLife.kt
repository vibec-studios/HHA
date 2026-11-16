package dev.henny.hha.logic.world

import dev.henny.hha.HhaEntities
import dev.henny.hha.entity.ImpEntity
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.HadesFights
import dev.henny.hha.world.HhaWorldgen
import dev.henny.hha.world.TartarosBiomes
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.mob.HostileEntity
import net.minecraft.entity.mob.MobEntity
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.MathHelper
import net.minecraft.world.Heightmap
import kotlin.math.cos
import kotlin.math.sin

/**
 * Die Bewohner des Tartaros. Unter dem offenen Himmel greifen die normalen Spawnregeln nicht, also holt diese Klasse die
 * Toten selbst aus dem Boden, passend zum Biom und in Sichtweite, aber nie direkt neben dem Spieler. Im Kampf gegen Hades und
 * rund um den Palast ist Ruhe.
 */
object TartarosLife {

    private const val PERIOD = 70
    private const val CAP = 7
    private const val SEARCH = 48.0
    private const val IMP_GROUP_RADIUS = 24.0
    private var clock = 0

    fun tick(server: MinecraftServer) {
        if (++clock % PERIOD != 0) return
        val world = server.getWorld(HadesGate.TARTAROS) ?: return
        for (player in world.players) {
            if (player.isSpectator || player.isCreative || HadesFights.isParticipant(player)) continue
            spawnNear(world, player)
        }
    }

    private fun spawnNear(world: ServerWorld, player: ServerPlayerEntity) {
        // Ein Wächterkampf ist ein Duell: Solange er läuft, kommt niemand dazu.
        if (TartarosMinibosses.busy(player)) return
        val random = world.random
        val box = player.boundingBox.expand(SEARCH)
        if (world.getEntitiesByClass(HostileEntity::class.java, box) { true }.size >= CAP) return
        val a = random.nextDouble() * Math.PI * 2.0
        val r = 22.0 + random.nextDouble() * 16.0
        val x = MathHelper.floor(player.x + cos(a) * r)
        val z = MathHelper.floor(player.z + sin(a) * r)
        if (!world.isChunkLoaded(x shr 4, z shr 4)) return
        val y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z)
        val pos = BlockPos(x, y, z)
        if (!world.getBlockState(pos.down()).fluidState.isEmpty) return
        if (world.structureAccessor.getStructureContaining(pos, HhaWorldgen.HADES_PALACE_TAG).hasChildren()) return
        val type = chooseMob(world, pos) ?: return
        if (type == HhaEntities.IMP) {
            spawnImps(world, pos)
            return
        }
        val mob = type.create(world, SpawnReason.NATURAL) as? MobEntity ?: return
        val height = if (mob.type == EntityType.GHAST) 18.0 + random.nextDouble() * 14.0 else 0.0
        mob.refreshPositionAndAngles(x + 0.5, y + height, z + 0.5, random.nextFloat() * 360f, 0f)
        if (!world.isSpaceEmpty(mob)) return
        mob.initialize(world, world.getLocalDifficulty(pos), SpawnReason.NATURAL, null)
        world.spawnEntity(mob)
        if (height == 0.0) {
            Vfx.pillar(world, Vfx.Style.HADES, mob.entityPos, 1.1f, 5f, 16)
            Vfx.rune(world, Vfx.Style.HADES, mob.entityPos.add(0.0, 0.06, 0.0), 1.5f, 24)
        }
    }

    /** Teufelchen kommen allein oder zu zweit, nie mehr als [BossTimings.Imp.GROUP] in einer Gruppe (Plan 4.6). */
    private fun spawnImps(world: ServerWorld, pos: BlockPos) {
        val random = world.random
        val near = world.getEntitiesByClass(ImpEntity::class.java, Box(pos).expand(IMP_GROUP_RADIUS)) { it.isAlive }.size
        val room = BossTimings.Imp.GROUP - near
        if (room <= 0) return
        val count = if (room >= 2 && random.nextInt(3) == 0) 2 else 1
        repeat(count) { i ->
            val imp = HhaEntities.IMP.create(world, SpawnReason.NATURAL) ?: return
            val height = 2.5 + random.nextDouble() * 3.0
            imp.refreshPositionAndAngles(pos.x + 0.5 + i * 1.5, pos.y + height, pos.z + 0.5, random.nextFloat() * 360f, 0f)
            if (!world.isSpaceEmpty(imp)) return
            imp.initialize(world, world.getLocalDifficulty(pos), SpawnReason.NATURAL, null)
            world.spawnEntity(imp)
        }
    }

    private fun chooseMob(world: ServerWorld, pos: BlockPos): EntityType<*>? {
        val biome = world.getBiome(pos)
        val roll = world.random.nextInt(100)
        return when {
            biome.matchesKey(TartarosBiomes.PHLEGETHON) -> when {
                roll < 30 -> dev.henny.hha.HhaEntities.IMP
                roll < 50 -> EntityType.MAGMA_CUBE
                roll < 75 -> EntityType.BLAZE
                roll < 90 -> EntityType.WITHER_SKELETON
                else -> EntityType.GHAST
            }
            biome.matchesKey(TartarosBiomes.KOKYTOS) -> when {
                roll < 70 -> EntityType.STRAY
                else -> EntityType.WITHER_SKELETON
            }
            biome.matchesKey(TartarosBiomes.ASPHODEL) -> when {
                roll < 55 -> EntityType.ZOMBIE
                roll < 85 -> EntityType.SKELETON
                else -> EntityType.PHANTOM
            }
            biome.matchesKey(TartarosBiomes.EREBOS) -> when {
                roll < 20 -> dev.henny.hha.HhaEntities.IMP
                roll < 45 -> EntityType.WITHER_SKELETON
                roll < 75 -> EntityType.ENDERMAN
                roll < 90 -> EntityType.PHANTOM
                else -> EntityType.GHAST
            }
            biome.matchesKey(TartarosBiomes.MARSH) -> when {
                roll < 55 -> EntityType.BOGGED
                roll < 85 -> EntityType.DROWNED
                else -> EntityType.SLIME
            }
            else -> when {
                roll < 22 -> dev.henny.hha.HhaEntities.IMP
                roll < 45 -> EntityType.SKELETON
                roll < 80 -> EntityType.WITHER_SKELETON
                roll < 92 -> EntityType.PIGLIN_BRUTE
                else -> EntityType.GHAST
            }
        }
    }
}
