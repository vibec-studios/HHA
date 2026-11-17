package dev.henny.hha.logic.world

import dev.henny.hha.Hha
import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaSounds
import dev.henny.hha.config.HhaConfig
import dev.henny.hha.entity.boss.HermesEntity
import dev.henny.hha.entity.boss.HermesPose
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.HadesFights
import dev.henny.hha.net.VfxPayload
import dev.henny.hha.world.TartarusKind
import dev.henny.hha.world.TartarusPiece
import dev.henny.hha.world.TartarusPlans
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.SpawnReason
import net.minecraft.registry.RegistryKeys
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.registry.entry.RegistryEntryList
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraft.world.Heightmap
import net.minecraft.world.chunk.ChunkStatus
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement
import net.minecraft.world.gen.structure.Structure
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Hermes als Seelenführer im Tartaros (Überarbeitung 2, PLAN 5.7); ersetzt den jagenden Hermes der ersten Fassung.
 *
 * Bei der Ankunft ([onArrive]) und nach jedem Wächter-Sieg ([onLinkBroken]) erscheint Hermes als Geist
 * [BossTimings.Guide.GHOST] Ticks lang [BossTimings.Guide.GHOST_DISTANCE] Blöcke vor dem Spieler in Blickrichtung und zeigt
 * (Pose [HermesPose.CAST], die freie Hand weist den Weg) zum **nächsten ungesprengten Glied der Kette**, nach dem fünften
 * zum Palast. Dazu eine goldene Fernsäule zum Ziel und eine Textzeile `hha.guide.<ziel>` ohne Ton. Er ist nicht
 * angreifbar, greift nicht an, tauscht nichts und weht niemanden weg.
 *
 * Gesucht wird mit `locateStructure` (Radius [BossTimings.Guide.SEARCH_CHUNKS] Chunks) je Glied; Strafstätten so lange,
 * bis die Variante passt (höchstens [BossTimings.Guide.SEARCH_TRIES] Bauwerke), befreite Bauwerke werden übersprungen.
 * Das Ergebnis gilt je Glied und Welt; neu gesucht wird höchstens alle [BossTimings.Guide.SEARCH_COOLDOWN] Ticks.
 *
 * Erst wenn alle fünf Glieder gesprengt sind (oder `tartaros_chain` aus ist), steht dauerhaft die grüne Säule zum
 * Palast am Horizont, wie früher.
 */
object TartarosGuide {

    private val G = BossTimings.Guide

    /** Ein Wegweiser ist fällig: Spieler → Ticks bis zum Auftritt. */
    private val pending = HashMap<UUID, Int>()

    /** Ein Auftritt des Geistes (einer je Gruppe). */
    private class Ghost(val entity: HermesEntity, val owner: UUID, var life: Int)

    private val ghosts = ArrayList<Ghost>()

    /** Wohin die goldene Säule eines Spielers zeigt, bis wann, und welches Glied sie meint (`null` = Palast). */
    private class Beacon(val pos: Vec3d, val until: Long, val link: TartarosChain.Link?)

    private val beacons = HashMap<UUID, Beacon>()

    /** Suchergebnis je Ziel: Mitte des Bauwerks (oder nichts), wann und von wo gesucht wurde. */
    private class Found(val pos: BlockPos?, val searchedAt: Long, val origin: BlockPos)

    private val found = HashMap<String, Found>()

    /** Ein Ziel des Wegweisers: Ort (oder `null` = nichts gefunden), Sprachschlüssel und Glied. */
    private class Target(val pos: Vec3d?, val key: String, val link: TartarosChain.Link?)

    private var clock = 0
    private var server: MinecraftServer? = null

    fun init() {}

    fun tick(server: MinecraftServer) {
        // Alles Gemerkte gehört zu einem Server (Einzelspieler: andere Welt geöffnet → neu anfangen).
        if (this.server !== server) {
            this.server = server
            pending.clear()
            ghosts.clear()
            beacons.clear()
            found.clear()
        }
        val world = server.getWorld(HadesGate.TARTAROS) ?: return
        clock++
        tickGhosts(world)
        if (pending.isNotEmpty()) {
            val due = ArrayList<UUID>()
            val iterator = pending.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value > 1) {
                    entry.setValue(entry.value - 1)
                } else {
                    due.add(entry.key)
                    iterator.remove()
                }
            }
            for (id in due) (world.getPlayerByUuid(id) as? ServerPlayerEntity)?.let { show(world, it) }
        }
        if (clock % G.BEACON_REFRESH == 0) refreshBeacons(world)
    }

    /** Ein Spieler ist im Tartaros angekommen: Gleich zeigt Hermes ihm den Weg. */
    fun onArrive(player: ServerPlayerEntity) {
        pending[player.uuid] = G.ARRIVE_DELAY
    }

    /** Ein Glied der Kette ist gesprengt (bei [at]): Wer dabei war, bekommt den Weg zum nächsten. */
    fun onLinkBroken(world: ServerWorld, at: Vec3d) {
        for (player in world.players) {
            if (player.isSpectator || player.squaredDistanceTo(at) > G.VICTORY_RADIUS * G.VICTORY_RADIUS) continue
            beacons.remove(player.uuid)
            pending[player.uuid] = G.VICTORY_DELAY
        }
    }

    /**
     * Jemand hat einen Hermes-Geist getroffen, und es war nicht der aus Hades' Kampf. Der Seelenführer ist nicht
     * angreifbar: Der Schlag geht durch ihn hindurch. Ein verwaister Geist (keiner von hier) verschwindet.
     */
    fun onStruck(ghost: HermesEntity, world: ServerWorld) {
        if (ghosts.any { it.entity === ghost }) return
        ghost.discard()
    }

    // -------------------------------------------------------------------------------------------------
    // Auftritt
    // -------------------------------------------------------------------------------------------------

    private fun show(world: ServerWorld, player: ServerPlayerEntity) {
        if (player.isSpectator || !player.isAlive || player.entityWorld !== world || HadesFights.isParticipant(player)) return
        val target = nextTarget(world, player)
        val pos = target.pos
        if (pos == null) {
            player.sendMessage(Text.translatable("hha.guide.lost").formatted(Formatting.GOLD, Formatting.ITALIC), false)
        } else {
            val distance = ((hypot(pos.x - player.x, pos.z - player.z) / 10.0).roundToInt() * 10).coerceAtLeast(10)
            player.sendMessage(Text.translatable("hha.guide.${target.key}", distance).formatted(Formatting.GOLD, Formatting.ITALIC), false)
            beacons[player.uuid] = Beacon(pos, world.time + G.BEACON, target.link)
            sendBeacon(player, pos, Vfx.Style.GOLD)
        }
        appear(world, player, pos)
    }

    /**
     * Öffentliche, nebenwirkungsfreie Abfrage für das RPG-HUD: Lage und Schlüssel (Glied-Schlüssel bzw. `palace`) des
     * nächsten Ziels, oder `null`, wenn keins gefunden wird. Nutzt denselben Zwischenspeicher wie der Wegweiser.
     */
    fun target(world: ServerWorld, player: ServerPlayerEntity): Pair<Vec3d, String>? {
        val t = nextTarget(world, player)
        return t.pos?.let { it to t.key }
    }

    /** Das nächste ungesprengte Glied (nächstes Bauwerk), nach dem fünften der Palast. */
    private fun nextTarget(world: ServerWorld, player: ServerPlayerEntity): Target {
        val server = world.server ?: return Target(null, "lost", null)
        if (chainOpen(server)) {
            val palace = palace(world, player.blockPos) ?: return Target(null, "lost", null)
            return Target(Vec3d.ofBottomCenter(palace), "palace", null)
        }
        var best: Target? = null
        var bestDistance = Double.MAX_VALUE
        for (link in TartarosChain.of(server).missing()) {
            val site = site(world, link, player.blockPos) ?: continue
            val d = hypot(site.x + 0.5 - player.x, site.z + 0.5 - player.z)
            if (d < bestDistance) {
                bestDistance = d
                best = Target(Vec3d.ofBottomCenter(site), link.key, link)
            }
        }
        return best ?: Target(null, "lost", null)
    }

    /** Hermes erscheint vor dem Spieler und zeigt zum Ziel; steht schon einer bei der Gruppe, bleibt es bei dem. */
    private fun appear(world: ServerWorld, player: ServerPlayerEntity, toward: Vec3d?) {
        if (ghosts.any { !it.entity.isRemoved && it.entity.squaredDistanceTo(player) < G.GROUP_RADIUS * G.GROUP_RADIUS }) return
        val look = player.rotationVector.let { Vec3d(it.x, 0.0, it.z) }.let { if (it.lengthSquared() < 1e-4) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
        val spot = player.entityPos.add(look.multiply(G.GHOST_DISTANCE))
        val ground = groundAt(world, spot.x, spot.z)
        // Auf Dächern oder in Schluchten bleibt er auf Augenhöhe des Spielers.
        val y = if (ground != null && abs(ground - player.y) <= 6.0) ground else player.y
        val ghost = HhaEntities.HERMES.create(world, SpawnReason.EVENT) ?: return
        ghost.isIllusion = true
        ghost.guide = true
        ghost.stage = 3
        ghost.pose = HermesPose.CAST
        ghost.setPosition(spot.x, y + 0.1, spot.z)
        face(ghost, toward ?: player.entityPos)
        world.spawnEntity(ghost)
        val at = ghost.entityPos.add(0.0, 1.5, 0.0)
        Vfx.flare(world, Vfx.Style.GOLD, at, 2.4f, 10)
        Vfx.feathers(world, Vfx.Style.HEAVEN, at, 12, 1.2f, 40)
        world.playSound(null, ghost.x, ghost.y, ghost.z, HhaSounds.BLINK, SoundCategory.NEUTRAL, 0.8f, 1.1f)
        ghosts.add(Ghost(ghost, player.uuid, G.GHOST))
    }

    private fun tickGhosts(world: ServerWorld) {
        if (ghosts.isEmpty()) return
        val iterator = ghosts.iterator()
        while (iterator.hasNext()) {
            val ghost = iterator.next()
            if (ghost.entity.isRemoved) {
                iterator.remove()
                continue
            }
            val owner = world.getPlayerByUuid(ghost.owner)
            if (--ghost.life <= 0 || owner == null) {
                vanish(world, ghost.entity)
                iterator.remove()
            }
        }
    }

    private fun vanish(world: ServerWorld, ghost: HermesEntity) {
        val at = ghost.entityPos.add(0.0, 1.5, 0.0)
        Vfx.feathers(world, Vfx.Style.HEAVEN, at, 16, 1.4f, 50)
        ghost.discard()
    }

    private fun face(entity: HermesEntity, target: Vec3d) {
        val d = target.subtract(entity.entityPos)
        if (d.x * d.x + d.z * d.z < 1e-4) return
        val yaw = (Math.toDegrees(atan2(d.z, d.x)) - 90.0).toFloat()
        entity.yaw = yaw
        entity.bodyYaw = yaw
        entity.headYaw = yaw
    }

    private fun groundAt(world: ServerWorld, x: Double, z: Double): Double? {
        val bx = MathHelper.floor(x)
        val bz = MathHelper.floor(z)
        if (!world.isChunkLoaded(bx shr 4, bz shr 4)) return null
        return world.getTopY(Heightmap.Type.MOTION_BLOCKING, bx, bz).toDouble()
    }

    // -------------------------------------------------------------------------------------------------
    // Fernsäulen
    // -------------------------------------------------------------------------------------------------

    /** Ist die Kette gesprengt (5/5) oder die Kette abgeschaltet? Dann führt der Weg zum Palast. */
    private fun chainOpen(server: MinecraftServer): Boolean =
        !HhaConfig.enabled("tartaros_chain") || TartarosChain.brokenCount(server) >= TartarosChain.Link.entries.size

    /**
     * Alle [BossTimings.Guide.BEACON_REFRESH] Ticks: die goldene Säule zum gewiesenen Ziel (bis sie abläuft oder das
     * Glied gesprengt ist), sonst ab 5/5 die grüne Säule zum Palast.
     */
    private fun refreshBeacons(world: ServerWorld) {
        val server = world.server ?: return
        val present = HashSet<UUID>()
        val open = chainOpen(server)
        for (player in world.players) {
            if (player.isSpectator) continue
            present.add(player.uuid)
            val beacon = beacons[player.uuid]
            if (beacon != null) {
                val stale = world.time >= beacon.until || (beacon.link != null && TartarosChain.isBroken(server, beacon.link))
                if (!stale) {
                    sendBeacon(player, beacon.pos, Vfx.Style.GOLD)
                    continue
                }
                beacons.remove(player.uuid)
            }
            if (!open || HadesFights.isParticipant(player)) continue
            val palace = palace(world, player.blockPos) ?: continue
            if (hypot(palace.x + 0.5 - player.x, palace.z + 0.5 - player.z) < G.PALACE_NEAR) continue
            sendBeacon(player, Vec3d.ofBottomCenter(palace), Vfx.Style.HADES)
        }
        beacons.keys.retainAll(present)
    }

    /**
     * Die Säule am Horizont (`Vfx.Kind.FAR_BEACON`), nur für [player], in der Farbe [style]. Der Stil steht in der
     * Sendung; solange der Client die Säule noch fest in Hades-Grün zeichnet (Wunsch von P00 an P02), erscheint auch die
     * goldene Säule grün.
     */
    private fun sendBeacon(player: ServerPlayerEntity, pos: Vec3d, style: Vfx.Style) {
        ServerPlayNetworking.send(
            player,
            VfxPayload(Vfx.Kind.FAR_BEACON.ordinal, style.ordinal, pos.x, pos.y, pos.z, 0.0, 0.0, 0.0, 0f, 0f, G.BEACON_LIFE, -1, -1),
        )
    }

    // -------------------------------------------------------------------------------------------------
    // Suche
    // -------------------------------------------------------------------------------------------------

    private fun kindOf(link: TartarosChain.Link): TartarusKind = when (link) {
        TartarosChain.Link.WARDEN -> TartarusKind.FORTRESS
        TartarosChain.Link.KAMPE -> TartarusKind.PRISON
        else -> TartarusKind.PUNISHMENT
    }

    /** Variante der Strafstätte (0 Sisyphos, 1 Ixion, 2 Tantalos); −1 für Feste und Kerker. */
    private fun variantOf(link: TartarosChain.Link): Int = when (link) {
        TartarosChain.Link.SISYPHUS -> 0
        TartarosChain.Link.IXION -> 1
        TartarosChain.Link.TANTALUS -> 2
        else -> TartarusPiece.NO_VARIANT
    }

    /** Das nächste noch nicht befreite Bauwerk des Glieds [link] (Mitte), zwischengespeichert je Welt. */
    private fun site(world: ServerWorld, link: TartarosChain.Link, from: BlockPos): BlockPos? =
        cached(world, link.key, from) { search(world, kindOf(link), variantOf(link), from) }

    private fun palace(world: ServerWorld, from: BlockPos): BlockPos? =
        cached(world, "palace", from) { search(world, TartarusKind.PALACE, TartarusPiece.NO_VARIANT, from) }

    /**
     * Ergebnis je Ziel und Welt: Ein gültiges Ergebnis (Bauwerk nicht befreit, Suche nicht zu weit weg) gilt weiter; neu
     * gesucht wird höchstens alle [BossTimings.Guide.SEARCH_COOLDOWN] Ticks.
     */
    private fun cached(world: ServerWorld, target: String, from: BlockPos, search: () -> BlockPos?): BlockPos? {
        val key = "${world.registryKey.value}|$target"
        val last = found[key]
        if (last != null) {
            val pos = last.pos
            val valid = pos != null && !TartarosMinibosses.isFreed(world, pos)
            val near = last.origin.getSquaredDistance(from) < G.SEARCH_MOVE * G.SEARCH_MOVE
            if (valid && near) return pos
            if (world.time - last.searchedAt < G.SEARCH_COOLDOWN) return if (valid) pos else null
        }
        val pos = search()
        found[key] = Found(pos, world.time, from)
        return pos
    }

    /**
     * Sucht das nächste passende Bauwerk der Art [kind]: zuerst `locateStructure` im Umkreis von
     * [BossTimings.Guide.SEARCH_CHUNKS] Chunks. Passt es nicht (falsche Strafstätte, schon befreit), wird Zelle für Zelle
     * des Platzierungsrasters weitergesucht, nächste zuerst, höchstens [BossTimings.Guide.SEARCH_TRIES] Bauwerke. Zellen,
     * deren Variante laut beiden Formeln (neu: Rasterzelle, alt: Streuformel) nicht passt, kosten keinen Versuch.
     */
    private fun search(world: ServerWorld, kind: TartarusKind, variant: Int, from: BlockPos): BlockPos? {
        val registry = world.registryManager.getOrThrow(RegistryKeys.STRUCTURE)
        val entry: RegistryEntry<Structure> = registry.getEntry(Hha.id(kind.id)).orElse(null) ?: return null
        val list = RegistryEntryList.of(entry)
        val generator = world.chunkManager.chunkGenerator
        val calculator = world.chunkManager.structurePlacementCalculator
        val placement = calculator.getPlacements(entry).firstOrNull { it is RandomSpreadStructurePlacement } as? RandomSpreadStructurePlacement
        val rings = if (placement != null) ceil(G.SEARCH_CHUNKS.toDouble() / placement.spacing).toInt().coerceAtLeast(1) else G.SEARCH_CHUNKS
        try {
            var tries = 0
            val first = generator.locateStructure(world, list, from, rings, false)?.first
            if (first != null) {
                tries++
                accept(world, entry.value(), first, variant)?.let { return it }
            }
            if (placement == null) return null
            val spacing = placement.spacing
            val seed = calculator.structureSeed
            val cellX = Math.floorDiv(from.x shr 4, spacing)
            val cellZ = Math.floorDiv(from.z shr 4, spacing)
            val starts = ArrayList<ChunkPos>()
            for (dx in -rings..rings) {
                for (dz in -rings..rings) starts.add(placement.getStartChunk(seed, (cellX + dx) * spacing, (cellZ + dz) * spacing))
            }
            starts.sortBy { val dx = it.centerX - from.x; val dz = it.centerZ - from.z; dx.toLong() * dx + dz.toLong() * dz }
            val skip = first?.let { ChunkPos(it) }
            for (start in starts) {
                if (tries >= G.SEARCH_TRIES) break
                if (start == skip || (variant >= 0 && !mayBe(start, variant))) continue
                tries++
                val pos = generator.locateStructure(world, list, start.startPos, 0, false)?.first ?: continue
                accept(world, entry.value(), pos, variant)?.let { return it }
            }
        } catch (e: RuntimeException) {
            Hha.LOGGER.warn("Wegweiser: Suche nach {} fehlgeschlagen", kind.id, e)
        }
        return null
    }

    /** Könnte in der Zelle mit Start-Chunk [start] die Strafstätte [variant] stehen (neue oder alte Formel)? */
    private fun mayBe(start: ChunkPos, variant: Int): Boolean =
        TartarusPlans.cellVariant(start.x, start.z) == variant || TartarusPlans.siteVariant(BlockPos(start.centerX, 0, start.centerZ)) == variant

    /** Das Bauwerk bei [pos], wenn es passt (richtige Variante, nicht befreit): seine Mitte, sonst `null`. */
    private fun accept(world: ServerWorld, structure: Structure, pos: BlockPos, variant: Int): BlockPos? {
        val chunk = world.getChunk(pos.x shr 4, pos.z shr 4, ChunkStatus.STRUCTURE_STARTS, true) ?: return null
        val start = chunk.getStructureStart(structure) ?: return null
        val piece = start.children.firstOrNull() as? TartarusPiece ?: return null
        if (variant >= 0 && piece.siteVariant() != variant) return null
        if (piece.kind != TartarusKind.PALACE && TartarosMinibosses.isFreed(world, piece.middle)) return null
        return piece.middle
    }
}
