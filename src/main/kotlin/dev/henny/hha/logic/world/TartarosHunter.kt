package dev.henny.hha.logic.world

import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaSounds
import dev.henny.hha.config.HhaConfig
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.entity.boss.HermesEntity
import dev.henny.hha.entity.boss.HermesPose
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.HadesFights
import dev.henny.hha.logic.boss.HhaVoiceLengths
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha.world.HhaWorldgen
import dev.henny.hha.world.TartarusKind
import dev.henny.hha.world.TartarusPiece
import dev.henny.hha.world.TartarusPlans
import dev.henny.hha.world.TartarusStructure
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.effect.StatusEffectCategory
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.mob.MobEntity
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.Heightmap
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Hermes im Tartaros. Dass ein Sterblicher mit seiner Karte bis hierher gekommen ist, wäre vor Hades eine Blamage, also
 * tut er alles, damit das niemand erfährt: falsche Fährten, Federn, Sturzflüge, die Toten, Diebstahl und Gegenwind.
 * Je näher man dem Palast kommt, desto öfter und verzweifelter wird es.
 *
 * Er ist dabei keine zweite Bossfigur: Wer ihn trifft, vertreibt ihn für eine Weile. Ein tödlicher Treffer von ihm
 * bedeutet nicht den Tod, sondern „Rücksendung“ zurück zur Ankunft, denn genau das will er ja.
 *
 * Drei Entschärfungen (Plan 4.6): Er erscheint nie, solange ein Wächter kämpft oder der Spieler in einem Bauwerk steht
 * (und geht, wenn es dazu kommt); die Schnellleiste mischt er höchstens einmal je 5 Minuten und nur mit
 * [BossTimings.Hunter.STEAL_SWAPS] Vertauschungen; zwischen zwei Auftritten liegen immer mindestens 45 s
 * ([BossTimings.Hunter.MIN_GAP]). Seine Ansagen laufen über [Telegraph] (Gefahr Himmelblau, Gold bleibt er selbst).
 */
object TartarosHunter {

    private const val NEAR_DISTANCE = 115.0
    private const val FAR_DISTANCE = 700.0
    private const val SAFE_START_TICKS = 20 * 12

    private class Stalked(var arrival: Vec3d?) {
        var cooldown = 90
        var task: Iterator<Unit>? = null
        var ghost: HermesEntity? = null
        var attacks = 0
        var saidFirst = false
        var saidClose = false

        /** Letzte bekannte Palastposition; nur neu suchen, wenn sich der Spieler weit bewegt hat. */
        var palace: BlockPos? = null
        var searchedAt: Vec3d? = null
        var beaconIn = 0

        /** Solange gesetzt, zeigt die Säule am Horizont auf Hermes' Lüge statt auf den Palast. */
        var falseBeacon: Vec3d? = null
        var falseTicks = 0
        val minions = ArrayList<MobEntity>()

        /** Wann er zuletzt die Schnellleiste gemischt hat (Welt-Tick). */
        var lastSteal = Long.MIN_VALUE / 2

        /** Ansagen für genau diesen Spieler. */
        var fight: Telegraph.Fight? = null

        /** Kämpft gerade ein Wächter in der Nähe oder steht der Spieler in einem Bauwerk? Dann kein Auftritt. */
        var blocked = false
    }

    private val stalked = HashMap<UUID, Stalked>()

    fun init() {
        // Rücksendung: Hermes tötet nicht, er stellt zurück.
        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, source, _ ->
            val player = entity as? ServerPlayerEntity
            val killer = source.attacker as? HermesEntity
            if (player == null || killer == null || !killer.guide || HadesFights.isParticipant(player)) {
                true
            } else {
                returnToSender(player)
                false
            }
        }
    }

    fun greet(player: ServerPlayerEntity) {
        stalked.getOrPut(player.uuid) { Stalked(player.entityPos) }.apply {
            arrival = player.entityPos
            cooldown = SAFE_START_TICKS
        }
        player.sendMessage(Text.translatable("hha.tartaros.pillar").formatted(Formatting.GRAY, Formatting.ITALIC), false)
    }

    /** Vom Hermes-Geist gemeldet: Ein Treffer vertreibt ihn. */
    fun onStruck(ghost: HermesEntity, world: ServerWorld) {
        val owner = stalked.values.firstOrNull { it.ghost === ghost } ?: run {
            vanish(world, ghost)
            return
        }
        val player = stalked.entries.firstOrNull { it.value === owner }?.let { world.server?.playerManager?.getPlayer(it.key) }
        if (player != null) speak(player, "hunter.struck", 40)
        world.playSound(null, ghost.blockPos, HhaSounds.HIT, SoundCategory.HOSTILE, 1.6f, 1.2f)
        vanish(world, ghost)
        owner.ghost = null
        owner.task = null
        owner.cooldown = max(BossTimings.Hunter.MIN_GAP, 20 * 22 + world.random.nextInt(20 * 8))
    }

    fun tick(server: MinecraftServer) {
        val world = server.getWorld(HadesGate.TARTAROS) ?: return
        val present = HashSet<UUID>()
        for (player in world.players) {
            if (player.isSpectator) continue
            present.add(player.uuid)
            update(world, player, stalked.getOrPut(player.uuid) { Stalked(null) })
        }
        val iterator = stalked.entries.iterator()
        while (iterator.hasNext()) {
            val (id, state) = iterator.next()
            if (id !in present) {
                state.ghost?.discard()
                for (mob in state.minions) mob.discard()
                iterator.remove()
            }
        }
    }

    private fun palaceOf(world: ServerWorld, player: ServerPlayerEntity, state: Stalked): BlockPos? {
        val last = state.searchedAt
        if (state.palace == null || last == null || last.squaredDistanceTo(player.entityPos) > 256.0 * 256.0) {
            state.palace = world.locateStructure(HhaWorldgen.HADES_PALACE_TAG, player.blockPos, 12, false)
            state.searchedAt = player.entityPos
        }
        return state.palace
    }

    private fun update(world: ServerWorld, player: ServerPlayerEntity, state: Stalked) {
        val palace = palaceOf(world, player, state) ?: return
        val target = Vec3d(palace.x + 0.5, player.y, palace.z + 0.5)
        val distance = target.distanceTo(player.entityPos)

        state.minions.removeIf { it.isRemoved || it.isDead }
        for (mob in state.minions.toList()) {
            if (mob.squaredDistanceTo(player) > 80.0 * 80.0) {
                mob.discard()
                state.minions.remove(mob)
            }
        }

        // Die Säule am Horizont: Sie zeigt zum Palast, außer Hermes hat sie gerade verbogen.
        if (--state.beaconIn <= 0) {
            state.beaconIn = 60
            val fake = state.falseBeacon
            Vfx.farBeacon(player, fake ?: Vec3d.ofCenter(palace), 100, fake != null)
        }
        if (state.falseBeacon != null && --state.falseTicks <= 0) {
            state.falseBeacon = null
            state.beaconIn = 0
        }

        // Wächterkampf oder Bauwerk? (alle 10 Ticks neu geprüft)
        if (world.time % 10L == 0L) state.blocked = TartarosMinibosses.busy(player) || inStructure(world, player)
        val blocked = state.blocked
        val running = state.task
        if (running != null) {
            if (blocked) {
                // Ein Wächter kämpft oder der Spieler ist in einem Bauwerk: Er zieht sich sofort zurück.
                leave(world, state)
                state.task = null
                state.cooldown = BossTimings.Hunter.MIN_GAP
                return
            }
            val fight = fightFor(world, player, state)
            if (fight.during { running.hasNext() }) fight.during { running.next() } else state.task = null
            return
        }
        if (HadesFights.isParticipant(player) || player.isCreative && !HhaConfig.enabled("hermes_fight")) return
        if (--state.cooldown > 0) return
        if (blocked) {
            state.cooldown = 40
            return
        }

        // Eine Erscheinung pro Gruppe, sonst wird aus Bedrängnis ein Durcheinander.
        val busy = stalked.values.any { it !== state && it.task != null && (it.ghost?.squaredDistanceTo(player) ?: Double.MAX_VALUE) < 70.0 * 70.0 }
        if (busy) {
            state.cooldown = 40
            return
        }

        val heading = Vec3d(target.x - player.x, 0.0, target.z - player.z).let { if (it.lengthSquared() < 1.0) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
        val urgency = (1.0 - ((distance - NEAR_DISTANCE) / (FAR_DISTANCE - NEAR_DISTANCE))).coerceIn(0.0, 1.0)
        val pick = when {
            distance < NEAR_DISTANCE -> lastStand(world, player, state, palace)
            !state.saidFirst -> {
                state.saidFirst = true
                embarrassed(world, player, state, heading)
            }
            urgency > 0.75 && !state.saidClose -> {
                state.saidClose = true
                closeCall(world, player, state, heading)
            }
            else -> chooseAttack(world, player, state, heading, urgency)
        }
        state.task = pick.iterator()
        // Im freien Ödland hat man Luft, kurz vor dem Palast weniger; unter 45 s zwischen zwei Auftritten nie.
        state.cooldown = max(BossTimings.Hunter.MIN_GAP, (20 * (60.0 - urgency * 15.0)).toInt() + world.random.nextInt(20 * 10))
    }

    /** Ansagen dieses Spielers (über [Telegraph]); mit ihm kämpft nur er selbst. */
    private fun fightFor(world: ServerWorld, player: ServerPlayerEntity, state: Stalked): Telegraph.Fight =
        state.fight ?: run {
            val id = player.uuid
            Telegraph.open(world, "hunter", Vfx.Style.HEAVEN) { listOfNotNull(world.server?.playerManager?.getPlayer(id)) }.also { state.fight = it }
        }

    /** Steht der Spieler in einem Bauwerk des Tartaros (beim Palast: innerhalb der Mauern)? */
    private fun inStructure(world: ServerWorld, player: ServerPlayerEntity): Boolean {
        val start = world.structureAccessor.getStructureContaining(player.blockPos) { it.value() is TartarusStructure }
        if (!start.hasChildren()) return false
        val piece = start.children.firstOrNull() as? TartarusPiece ?: return true
        if (piece.kind != TartarusKind.PALACE) return true
        val o = piece.middle
        return max(abs(player.blockX - o.x), abs(player.blockZ - o.z)) <= TartarusPlans.COURT + 3
    }

    private fun chooseAttack(world: ServerWorld, player: ServerPlayerEntity, state: Stalked, heading: Vec3d, urgency: Double): Sequence<Unit> {
        state.attacks++
        val options = ArrayList<() -> Sequence<Unit>>()
        options.add { volley(world, player, state, heading) }
        options.add { gust(world, player, state, heading) }
        if (state.attacks > 1) options.add { dash(world, player, state, heading) }
        if (state.attacks > 2) options.add { falseTrail(world, player, state, heading) }
        if (urgency > 0.3) options.add { ambush(world, player, state, heading) }
        if (urgency > 0.45 && world.time - state.lastSteal >= BossTimings.Hunter.STEAL_COOLDOWN) options.add { steal(world, player, state, heading) }
        return options[world.random.nextInt(options.size)]()
    }

    // -------------------------------------------------------------------------------------------------
    // Bausteine
    // -------------------------------------------------------------------------------------------------

    private suspend fun SequenceScope<Unit>.wait(ticks: Int) {
        repeat(ticks) { yield(Unit) }
    }

    private fun sound(world: ServerWorld, at: Vec3d, event: SoundEvent, volume: Float = 2.5f, pitch: Float = 1f) {
        world.playSound(null, at.x, at.y, at.z, event, SoundCategory.HOSTILE, volume, pitch)
    }

    private fun speak(player: ServerPlayerEntity, line: String, ticks: Int) {
        val key = "hha.hermes.line.$line"
        Cine.dialog(listOf(player), Cine.SPEAKER_HERMES, key, HhaVoiceLengths.ticks(key, ticks))
    }

    private fun groundAt(world: ServerWorld, x: Double, z: Double): Double? {
        val bx = net.minecraft.util.math.MathHelper.floor(x)
        val bz = net.minecraft.util.math.MathHelper.floor(z)
        if (!world.isChunkLoaded(bx shr 4, bz shr 4)) return null
        return world.getTopY(Heightmap.Type.MOTION_BLOCKING, bx, bz).toDouble()
    }

    private fun appear(world: ServerWorld, player: ServerPlayerEntity, state: Stalked, spot: Vec3d, pose: Int): HermesEntity? {
        val y = groundAt(world, spot.x, spot.z) ?: return null
        val ghost = HhaEntities.HERMES.create(world, SpawnReason.EVENT) ?: return null
        ghost.isIllusion = true
        ghost.guide = true
        ghost.stage = 3
        ghost.pose = pose
        ghost.setPosition(spot.x, y + 0.4, spot.z)
        world.spawnEntity(ghost)
        face(ghost, player.entityPos)
        val at = ghost.entityPos.add(0.0, 1.5, 0.0)
        Vfx.flare(world, Vfx.Style.GOLD, at, 3f, 10)
        Vfx.feathers(world, Vfx.Style.HEAVEN, at, 18, 1.4f, 50)
        sound(world, ghost.entityPos, HhaSounds.BLINK, 3f, 0.9f)
        state.ghost = ghost
        return ghost
    }

    private fun vanish(world: ServerWorld, ghost: HermesEntity) {
        val at = ghost.entityPos.add(0.0, 1.5, 0.0)
        Vfx.flare(world, Vfx.Style.GOLD, at, 2.4f, 8)
        Vfx.feathers(world, Vfx.Style.HEAVEN, at, 24, 1.8f, 70)
        ghost.discard()
    }

    private fun leave(world: ServerWorld, state: Stalked) {
        state.ghost?.let { if (!it.isRemoved) vanish(world, it) }
        state.ghost = null
    }

    private fun face(entity: HermesEntity, target: Vec3d) {
        val d = target.subtract(entity.entityPos)
        val yaw = (Math.toDegrees(atan2(d.z, d.x)) - 90.0).toFloat()
        entity.yaw = yaw
        entity.bodyYaw = yaw
        entity.headYaw = yaw
    }

    /** Schaden wie im Kampf: über die Konfiguration, und die Quelle ist er selbst. */
    private fun hit(world: ServerWorld, ghost: HermesEntity, player: ServerPlayerEntity, base: Float) {
        player.damage(world, world.damageSources.mobAttack(ghost), base * HhaConfig.numF("hermes_damage"))
    }

    private fun aside(world: ServerWorld, heading: Vec3d, spread: Double): Vec3d =
        Vec3d(-heading.z, 0.0, heading.x).multiply((world.random.nextDouble() - 0.5) * spread)

    // -------------------------------------------------------------------------------------------------
    // Seine Tricks
    // -------------------------------------------------------------------------------------------------

    /** Der erste Auftritt: Er ist nicht wütend, nur peinlich berührt. */
    private fun embarrassed(world: ServerWorld, player: ServerPlayerEntity, state: Stalked, heading: Vec3d): Sequence<Unit> = sequence {
        val ghost = appear(world, player, state, player.entityPos.add(heading.multiply(16.0)), HermesPose.TAUNT) ?: return@sequence
        val first = HhaVoiceLengths.ticks("hha.hermes.line.hunter.first", 80)
        speak(player, "hunter.first", 80)
        repeat(first + 6) {
            if (ghost.isRemoved) return@sequence
            face(ghost, player.entityPos)
            yield(Unit)
        }
        ghost.pose = HermesPose.SNAP
        speak(player, "hunter.warn", 66)
        repeat(HhaVoiceLengths.ticks("hha.hermes.line.hunter.warn", 66)) {
            if (ghost.isRemoved) return@sequence
            face(ghost, player.entityPos)
            yield(Unit)
        }
        leave(world, state)
    }

    /** Federn aus sicherer Entfernung, in zwei Salven. */
    private fun volley(world: ServerWorld, player: ServerPlayerEntity, state: Stalked, heading: Vec3d): Sequence<Unit> = sequence {
        val spot = player.entityPos.add(heading.multiply(20.0)).add(aside(world, heading, 22.0))
        val ghost = appear(world, player, state, spot, HermesPose.THROW) ?: return@sequence
        if (world.random.nextInt(3) == 0) speak(player, "hunter.personal", 50)
        val fight = fightFor(world, player, state)
        repeat(2) { wave ->
            if (ghost.isRemoved) return@sequence
            face(ghost, player.entityPos)
            // Ein Fächer aus fünf Federn ohne Zielsuche; der Kegel am Boden zeigt ihn vorher (Plan 3.3).
            val toward = Vec3d(player.x - ghost.x, 0.0, player.z - ghost.z)
            val range = (toward.length() + 4.0).toFloat().coerceAtLeast(BossTimings.Hermes.FAN_RANGE)
            val cone = fight.cone(Vec3d(ghost.x, player.y, ghost.z), toward, range, BossTimings.Hermes.FAN_HALF_ANGLE, BossTimings.Hermes.FAN_THROW_1, Telegraph.Pace.PROJECTILE)
            // Richtung und Zielhöhe gelten ab der Ansage: Wer aus dem Kegel läuft, ist sicher (kein Nachzielen).
            val from = ghost.entityPos.add(0.0, 2.2, 0.0)
            val snapshot = player.eyePos.subtract(from)
            val flatLength = kotlin.math.sqrt(toward.x * toward.x + toward.z * toward.z).coerceAtLeast(1e-6)
            val drop = (snapshot.y / kotlin.math.sqrt(snapshot.x * snapshot.x + snapshot.z * snapshot.z).coerceAtLeast(1.0)).coerceIn(-0.6, 0.6)
            val aim = Vec3d(toward.x / flatLength, drop, toward.z / flatLength)
            wait(cone.ticks)
            if (ghost.isRemoved) return@sequence
            val right = Vec3d(-aim.z, 0.0, aim.x).normalize()
            for (i in -2..2) {
                val bolt = HhaEntities.HERMES_BOLT.create(world, SpawnReason.EVENT) ?: continue
                bolt.kind = HermesBoltEntity.FEATHER
                bolt.damage = 5f * HhaConfig.numF("hermes_damage")
                bolt.homing = 0.0
                bolt.setOwner(ghost)
                bolt.setPosition(from)
                bolt.velocity = aim.normalize().add(right.multiply(i * 0.16)).normalize().multiply(0.9)
                bolt.maxRange = range.toDouble()
                bolt.maxAge = kotlin.math.ceil(range / 0.9).toInt() + 1
                world.spawnEntity(bolt)
            }
            sound(world, from, HhaSounds.FEATHER_VOLLEY, 2.5f, 1f + wave * 0.1f)
            wait(14)
        }
        wait(10)
        leave(world, state)
    }

    /** Zweimal im Tiefflug durch den Spieler, der Warnstreifen zeigt die Bahn. */
    private fun dash(world: ServerWorld, player: ServerPlayerEntity, state: Stalked, heading: Vec3d): Sequence<Unit> = sequence {
        val first = player.entityPos.add(heading.multiply(24.0))
        val ghost = appear(world, player, state, first, HermesPose.CAST) ?: return@sequence
        repeat(2) {
            if (ghost.isRemoved) return@sequence
            val from = ghost.entityPos
            val direction = Vec3d(player.x - from.x, 0.0, player.z - from.z).let { if (it.lengthSquared() < 1.0) heading else it.normalize() }
            val to = player.entityPos.add(direction.multiply(16.0))
            val floorY = player.y
            val line = fightFor(world, player, state).line(Vec3d(from.x, floorY, from.z), Vec3d(to.x, floorY, to.z), BossTimings.Hermes.DASH_WIDTH, 18, Telegraph.Pace.HEAVY)
            sound(world, from, HhaSounds.CHARGE, 2f, 1.2f)
            face(ghost, to)
            wait(line.ticks)
            if (ghost.isRemoved) return@sequence
            ghost.pose = HermesPose.DASH
            sound(world, from, HhaSounds.DASH, 3f, 1f)
            Vfx.trail(world, Vfx.Style.HEAVEN, ghost, 0.8f, 20)
            var struck = false
            val steps = (from.distanceTo(to) / 2.2).toInt().coerceAtLeast(1)
            for (step in 1..steps) {
                if (ghost.isRemoved) return@sequence
                val at = from.lerp(to, step.toDouble() / steps)
                ghost.setPosition(at.x, (groundAt(world, at.x, at.z) ?: at.y) + 0.8, at.z)
                if (!struck && ghost.squaredDistanceTo(player) < 7.0) {
                    struck = true
                    hit(world, ghost, player, 9f)
                    Fx.launchPlayer(player, direction.multiply(0.9).add(0.0, 0.45, 0.0))
                }
                yield(Unit)
            }
            ghost.pose = HermesPose.CAST
            wait(10)
        }
        leave(world, state)
    }

    /** Eine Bö vom Palast her: Der Weg wird länger, ohne dass es wehtut. */
    private fun gust(world: ServerWorld, player: ServerPlayerEntity, state: Stalked, heading: Vec3d): Sequence<Unit> = sequence {
        val ghost = appear(world, player, state, player.entityPos.add(heading.multiply(12.0)), HermesPose.CAST) ?: return@sequence
        val ring = fightFor(world, player, state).ring(player.entityPos, 4f, 22)
        sound(world, ghost.entityPos, HhaSounds.CHARGE, 2f, 1f)
        wait(ring.ticks)
        if (ghost.isRemoved) return@sequence
        ghost.pose = HermesPose.SLAM
        Vfx.tornado(world, Vfx.Style.HEAVEN, ghost.entityPos, heading.multiply(-0.3), 3f, 10f, 40)
        sound(world, ghost.entityPos, HhaSounds.WHOOSH, 3f, 0.7f)
        // Nur wer noch in der angesagten Fläche steht (und noch in dieser Welt ist), wird erfasst.
        if (player.isAlive && !player.isRemoved && player.entityWorld === world && ring.contains(player)) {
            Fx.launchPlayer(player, heading.multiply(-3.0).add(0.0, 0.9, 0.0))
            player.addStatusEffect(StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1))
        }
        wait(24)
        leave(world, state)
    }

    /** Er zeigt selbstsicher in die falsche Richtung, und die Säule am Horizont glaubt ihm für eine Weile. */
    private fun falseTrail(world: ServerWorld, player: ServerPlayerEntity, state: Stalked, heading: Vec3d): Sequence<Unit> = sequence {
        val angle = (if (world.random.nextBoolean()) 1 else -1) * (1.6 + world.random.nextDouble() * 1.2)
        val wrong = Vec3d(heading.x * cos(angle) - heading.z * sin(angle), 0.0, heading.x * sin(angle) + heading.z * cos(angle))
        val ghost = appear(world, player, state, player.entityPos.add(wrong.multiply(14.0)), HermesPose.SNAP) ?: return@sequence
        val length = HhaVoiceLengths.ticks("hha.hermes.line.hunter.wrong", 60)
        speak(player, "hunter.wrong", 60)
        state.falseBeacon = player.entityPos.add(wrong.multiply(500.0))
        state.falseTicks = 20 * 40
        state.beaconIn = 0
        repeat(length) {
            if (ghost.isRemoved) return@sequence
            face(ghost, player.entityPos.add(wrong.multiply(40.0)))
            yield(Unit)
        }
        // Dann fliegt er selbst in die behauptete Richtung, damit es glaubhaft bleibt.
        ghost.pose = HermesPose.DASH
        Vfx.trail(world, Vfx.Style.HEAVEN, ghost, 0.8f, 30)
        repeat(26) {
            if (ghost.isRemoved) return@sequence
            ghost.setPosition(ghost.entityPos.add(wrong.multiply(1.4)).add(0.0, 0.1, 0.0))
            yield(Unit)
        }
        leave(world, state)
    }

    /** Er öffnet kurz den Boden und lässt die Toten raus. Die kann man wenigstens erschlagen. */
    private fun ambush(world: ServerWorld, player: ServerPlayerEntity, state: Stalked, heading: Vec3d): Sequence<Unit> = sequence {
        val ghost = appear(world, player, state, player.entityPos.add(heading.multiply(18.0)), HermesPose.CAST) ?: return@sequence
        speak(player, "hunter.ambush", 56)
        val spots = List(3) { i ->
            val a = world.random.nextDouble() * 6.28 + i * 2.1
            player.entityPos.add(cos(a) * 7.0, 0.0, sin(a) * 7.0)
        }
        // Hier steigen die Toten auf (kein Treffer, darum keine Ansage, nur die Rune).
        for (spot in spots) Vfx.rune(world, Vfx.Style.SOUL, spot.add(0.0, 0.06, 0.0), 1.8f, 30)
        sound(world, ghost.entityPos, HhaSounds.SOUL, 2.5f, 0.8f)
        wait(30)
        for (spot in spots) {
            val y = groundAt(world, spot.x, spot.z) ?: continue
            val mob = EntityType.WITHER_SKELETON.create(world, SpawnReason.EVENT) ?: continue
            mob.refreshPositionAndAngles(spot.x, y, spot.z, 0f, 0f)
            mob.initialize(world, world.getLocalDifficulty(mob.blockPos), SpawnReason.EVENT, null)
            mob.target = player
            world.spawnEntity(mob)
            state.minions.add(mob)
            Vfx.pillar(world, Vfx.Style.SOUL, Vec3d(spot.x, y, spot.z), 1.2f, 6f, 20)
        }
        wait(20)
        leave(world, state)
    }

    /** Dieb bleibt Dieb: nimmt die guten Effekte und mischt die Schnellleiste. Es geht nichts verloren. */
    private fun steal(world: ServerWorld, player: ServerPlayerEntity, state: Stalked, heading: Vec3d): Sequence<Unit> = sequence {
        val look = player.getRotationVec(1f).let { Vec3d(it.x, 0.0, it.z).normalize() }
        val ghost = appear(world, player, state, player.entityPos.subtract(look.multiply(2.5)), HermesPose.SNAP) ?: return@sequence
        val grab = fightFor(world, player, state).ring(player.entityPos, BossTimings.Hermes.SNAP_RADIUS, BossTimings.Hermes.SNAP_WINDUP)
        wait(grab.ticks)
        if (ghost.isRemoved) return@sequence
        Vfx.nova(world, Vfx.Style.HEAVEN, player.entityPos.add(0.0, 1.0, 0.0), 3.2f, 10)
        sound(world, player.entityPos, HhaSounds.BLINK, 2f, 1.3f)
        if (player.isAlive && !player.isRemoved && player.entityWorld === world && grab.contains(player)) {
            for (effect in player.statusEffects.filter { it.effectType.value().category == StatusEffectCategory.BENEFICIAL }.map { it.effectType }) {
                player.removeStatusEffect(effect)
            }
            // Höchstens einmal je 5 Minuten und nur zwei Vertauschungen (Plan 4.6). Es geht nichts verloren.
            val inventory = player.inventory
            repeat(BossTimings.Hunter.STEAL_SWAPS) {
                val a = world.random.nextInt(9)
                val b = (a + 1 + world.random.nextInt(8)) % 9
                val stack = inventory.getStack(a)
                inventory.setStack(a, inventory.getStack(b))
                inventory.setStack(b, stack)
            }
            player.currentScreenHandler.sendContentUpdates()
            state.lastSteal = world.time
            speak(player, "hunter.steal", 44)
        }
        wait(14)
        leave(world, state)
    }

    /** Kurz vor dem Ziel verliert er die Fassung und greift ohne Pause an. */
    private fun closeCall(world: ServerWorld, player: ServerPlayerEntity, state: Stalked, heading: Vec3d): Sequence<Unit> = sequence {
        speak(player, "hunter.close", 44)
        yieldAll(dash(world, player, state, heading))
    }

    /** Vor dem Siegel steht er ein letztes Mal im Weg. */
    private fun lastStand(world: ServerWorld, player: ServerPlayerEntity, state: Stalked, palace: BlockPos): Sequence<Unit> = sequence {
        val door = Vec3d(palace.x + 0.5, player.y, palace.z + 90.5)
        val ghost = appear(world, player, state, door, HermesPose.KNEEL) ?: return@sequence
        val length = HhaVoiceLengths.ticks("hha.hermes.line.hunter.last", 70)
        speak(player, "hunter.last", 70)
        repeat(length) {
            if (ghost.isRemoved) return@sequence
            face(ghost, player.entityPos)
            yield(Unit)
        }
        leave(world, state)
        state.cooldown = 20 * 60
    }

    /** Er hat es geschafft: zurück auf Anfang. Der Spieler behält alles. */
    private fun returnToSender(player: ServerPlayerEntity) {
        val state = stalked.getOrPut(player.uuid) { Stalked(null) }
        val world = player.entityWorld as? ServerWorld ?: return
        player.health = (player.maxHealth * 0.35f).coerceAtLeast(6f)
        player.extinguish()
        player.addStatusEffect(StatusEffectInstance(StatusEffects.REGENERATION, 160, 1))
        player.addStatusEffect(StatusEffectInstance(StatusEffects.RESISTANCE, 100, 4))
        val palace = state.palace
        val back = state.arrival ?: if (palace != null) {
            val away = player.entityPos.subtract(palace.x + 0.5, player.y, palace.z + 0.5).let { Vec3d(it.x, 0.0, it.z).normalize() }
            player.entityPos.add(away.multiply(220.0))
        } else {
            player.entityPos
        }
        world.getChunk(net.minecraft.util.math.MathHelper.floor(back.x) shr 4, net.minecraft.util.math.MathHelper.floor(back.z) shr 4)
        val y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, net.minecraft.util.math.MathHelper.floor(back.x), net.minecraft.util.math.MathHelper.floor(back.z)).toDouble()
        val players = listOf(player)
        Cine.fade(players, 0xFFF6D8, 1.0, 0.0, 40)
        player.teleport(world, back.x, y + 1.0, back.z, emptySet(), player.yaw, player.pitch, false)
        player.velocity = Vec3d.ZERO
        player.velocityDirty = true
        player.fallDistance = 0.0
        Vfx.feathers(world, Vfx.Style.HEAVEN, player.entityPos.add(0.0, 1.0, 0.0), 20, 1.4f, 50)
        speak(player, "hunter.return", 70)
        state.task = null
        state.ghost?.discard()
        state.ghost = null
        state.cooldown = max(BossTimings.Hunter.MIN_GAP, 20 * 40)
    }
}
