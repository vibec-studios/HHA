package dev.henny.hha.logic.world

import com.mojang.serialization.Codec
import dev.henny.hha.Hha
import dev.henny.hha.HhaEntities
import dev.henny.hha.entity.miniboss.GuardianEntity
import dev.henny.hha.entity.miniboss.IxionEntity
import dev.henny.hha.entity.miniboss.KampeEntity
import dev.henny.hha.entity.miniboss.SisyphusEntity
import dev.henny.hha.logic.boss.HadesFights
import dev.henny.hha.world.TartarusKind
import dev.henny.hha.world.TartarusPiece
import dev.henny.hha.world.TartarusPlans
import dev.henny.hha.world.TartarusStructure
import net.minecraft.datafixer.DataFixTypes
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnReason
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.PersistentState
import net.minecraft.world.PersistentStateType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Die Wächter der Tartaros-Bauwerke. Kommt ein Spieler einem Bauwerk nahe, erscheint dessen Wächter: der Kerkermeister in der
 * Bronzefeste, Kampe im Titanenkerker (sobald jemand in die Grube steigt), und an den Stätten der Verdammten Sisyphos,
 * Ixion oder Tantalos selbst. Wie sie kämpfen, steht in [GuardianEntity] und den Wächterklassen.
 *
 * Wer einen Wächter besiegt, befreit das Bauwerk für immer; das merkt sich die Welt ([Defeated]). Verschwindet ein Wächter,
 * ohne zu sterben (weil alle weggegangen sind), erscheint er beim nächsten Besuch wieder.
 */
object TartarosMinibosses {

    /** Welche Bauwerke schon befreit sind, als gepackte Blockpositionen ihrer Mitte. */
    class Defeated(val sites: MutableSet<Long> = HashSet()) : PersistentState() {
        companion object {
            private val CODEC: Codec<Defeated> = Codec.LONG.listOf().xmap({ Defeated(HashSet(it)) }, { it.sites.toList() })
            val TYPE = PersistentStateType("hha_tartaros_guardians", { Defeated() }, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE)
        }
    }

    /** Laufende Wächter je Bauwerk. */
    private val active = HashMap<Long, GuardianEntity>()
    private var clock = 0

    private fun state(world: ServerWorld): Defeated = world.persistentStateManager.getOrCreate(Defeated.TYPE)

    fun tick(server: MinecraftServer) {
        val world = server.getWorld(HadesGate.TARTAROS) ?: return
        if (++clock % 40 != 0) return
        active.entries.removeIf { it.value.isRemoved }
        if (HadesFights.of(world) != null) return
        for (player in world.players) {
            if (player.isSpectator || player.isCreative) continue
            val start = world.structureAccessor.getStructureContaining(player.blockPos) { it.value() is TartarusStructure }
            if (!start.hasChildren()) continue
            val piece = start.children.firstOrNull() as? TartarusPiece ?: continue
            if (piece.kind == TartarusKind.PALACE) continue
            val key = piece.middle.asLong()
            if (key in active || key in state(world).sites) continue
            val site = site(world, piece, player)
            if (!site.trigger(player)) continue
            spawn(world, piece, key, site)
        }
    }

    /** Läuft in der Nähe dieses Spielers gerade ein Wächterkampf? (Hermes und die Bewohner halten sich dann zurück.) */
    fun busy(player: ServerPlayerEntity): Boolean =
        active.values.any { !it.isRemoved && it.isAlive && it.entityWorld === player.entityWorld && it.squaredDistanceTo(player) < BUSY_RADIUS * BUSY_RADIUS }

    /** Wo und wie ein Wächter erscheint. */
    private class Site(
        val type: EntityType<out GuardianEntity>,
        /** Standplatz (dort erscheint er, dorthin kehrt er zurück). */
        val spot: Vec3d,
        /** Mitte des Platzes (Leine, Kampfradius). */
        val home: Vec3d,
        val trigger: (ServerPlayerEntity) -> Boolean,
        val setup: (GuardianEntity) -> Unit = {},
    )

    private fun near(spot: Vec3d): (ServerPlayerEntity) -> Boolean = { hypot(it.x - spot.x, it.z - spot.z) <= TRIGGER }

    /**
     * Die Plätze nach den Bauplänen (`TartarusPlans`): Feste mit freiem Hof, Grubenboden des Kerkers, Gipfel des
     * Sisyphos-Hügels, Ring der Ixion-Plattform, Absatz im Tantalos-Becken (auf der Seite des Spielers).
     */
    private fun site(world: ServerWorld, piece: TartarusPiece, player: ServerPlayerEntity): Site {
        val o = piece.middle
        val center = Vec3d(o.x + 0.5, o.y + 1.0, o.z + 0.5)
        val toward = atan2(player.z - center.z, player.x - center.x)
        return when (piece.kind) {
            TartarusKind.FORTRESS -> {
                val spot = standable(world, o.add(0, 1, 16))
                Site(HhaEntities.WARDEN_OF_CHAINS, spot, center, near(spot))
            }
            TartarusKind.PRISON -> {
                // Grubenboden bei o.y − 24, man steht auf o.y − 23. Sie erscheint erst, wenn jemand hinabsteigt.
                val floor = o.y - TartarusPlans.PRISON_PIT_DEPTH + 1.0
                val base = Vec3d(o.x + 0.5, floor, o.z + 0.5)
                val spot = Vec3d(o.x + 0.5, floor, o.z + 15.5)
                val inPit: (ServerPlayerEntity) -> Boolean = { hypot(it.x - base.x, it.z - base.z) < TartarusPlans.PRISON_PIT_RADIUS && it.y < o.y - 6.0 }
                Site(HhaEntities.KAMPE, spot, base, inPit) { (it as KampeEntity).titan = base }
            }
            else -> when (siteVariant(o)) {
                0 -> {
                    val top = TartarusPlans.sisyphusSummit(o)
                    Site(HhaEntities.SISYPHUS, top, top, near(top)) { (it as SisyphusEntity).summit = top }
                }
                1 -> {
                    // Die Plattform ohne das Rad in der Mitte: ein Ring, auf der Seite des Spielers.
                    val r = 13.0
                    val spot = standable(world, BlockPos(o.x + (cos(toward) * r).roundToInt(), o.y + 1, o.z + (sin(toward) * r).roundToInt()))
                    Site(HhaEntities.IXION, spot, center, near(spot)) { (it as IxionEntity).arena(IXION_INNER, IXION_OUTER) }
                }
                else -> {
                    // Absatz bei Radius 10–13 (man steht auf o.y − 1), nicht im Wasser.
                    val r = 11.5
                    val spot = standable(world, BlockPos(o.x + (cos(toward) * r).roundToInt(), o.y - 1, o.z + (sin(toward) * r).roundToInt()))
                    val basin = Vec3d(o.x + 0.5, o.y - 1.0, o.z + 0.5)
                    Site(HhaEntities.TANTALUS, spot, basin, near(spot))
                }
            }
        }
    }

    /** Dieselbe Auswahl wie im Bauplan: Welche Stätte steht hier? */
    private fun siteVariant(o: BlockPos): Int = TartarusPlans.siteVariant(o)

    private fun standable(world: ServerWorld, from: BlockPos): Vec3d {
        var pos = from.down(3)
        repeat(30) {
            if (!world.getBlockState(pos.down()).isAir && world.getBlockState(pos).isAir && world.getBlockState(pos.up()).isAir && world.getBlockState(pos.up(2)).isAir) {
                return Vec3d.ofBottomCenter(pos)
            }
            pos = pos.up()
        }
        return Vec3d.ofBottomCenter(from)
    }

    private fun spawn(world: ServerWorld, piece: TartarusPiece, key: Long, site: Site) {
        val mob = site.type.create(world, SpawnReason.EVENT) ?: return
        val facing = Math.toDegrees(atan2(site.home.z - site.spot.z, site.home.x - site.spot.x)).toFloat() - 90f
        mob.refreshPositionAndAngles(site.spot.x, site.spot.y, site.spot.z, facing, 0f)
        mob.home = site.home
        mob.post = site.spot
        site.setup(mob)
        mob.kit.site = key
        world.spawnEntity(mob)
        mob.kit.announce()
        active[key] = mob
        Hha.LOGGER.info("Tartaros-Wächter {} erscheint bei {}", piece.kind, piece.middle)
    }

    /** Vom Wächter gemeldet: Das Bauwerk ist frei. */
    fun onDefeated(world: ServerWorld, key: Long) {
        if (key == 0L) return
        val defeated = state(world)
        if (defeated.sites.add(key)) defeated.markDirty()
        active.remove(key)
        for (player in world.players) {
            if (player.squaredDistanceTo(Vec3d.of(BlockPos.fromLong(key))) < 90.0 * 90.0) {
                player.sendMessage(Text.translatable("hha.guardian.defeated"), true)
            }
        }
    }

    private const val TRIGGER = 30.0
    private const val BUSY_RADIUS = 64.0

    /** Ixion-Plattform (Radius 19): innen steht das Rad mit Stützen (bis ~10,8), außen die Stufen (ab 17,6). */
    private const val IXION_INNER = 10.8
    private const val IXION_OUTER = 17.4
}
