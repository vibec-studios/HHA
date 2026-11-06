package dev.henny.hha.logic.boss

import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.entity.boss.HermesEntity
import dev.henny.hha.entity.boss.HermesPose
import dev.henny.hha.entity.boss.SerpentEntity
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.effect.StatusEffectCategory
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Das Kampfrepertoire: je Stage eine Rotation von Attacken. Jede Attacke kündigt sich am Boden an
 * (Runenkreis oder Korridor) und ist damit fair lesbar, bevor sie zuschlägt.
 */
class HermesAttacks(private val fight: HermesFight) {

    private val world get() = fight.world
    private val center get() = fight.center
    private val boss: HermesEntity get() = fight.boss!!
    private var rotation = 0
    private var serpentTaskRunning = false
    private var staggered = false

    private suspend fun SequenceScope<Unit>.wait(ticks: Int) {
        repeat(ticks) { yield(Unit) }
    }

    private fun sound(event: SoundEvent, at: Vec3d = boss.entityPos, volume: Float = 2f, pitch: Float = 1f) {
        world.playSound(null, at.x, at.y, at.z, event, SoundCategory.HOSTILE, volume, pitch)
    }

    private fun hint(key: String) {
        for (player in fight.players()) player.sendMessage(Text.translatable(key).formatted(Formatting.GOLD), true)
    }

    fun reset() {
        rotation = 0
        serpentTaskRunning = false
        staggered = false
    }

    /** Die nächste Attacke der Stage samt kurzer Atempause. */
    fun next(stage: Int): Sequence<Unit> {
        val list: List<() -> Sequence<Unit>> = when (stage) {
            1 -> listOf(::courierDash, ::featherVolley, ::galeSlam, ::courierDash, ::gustWall)
            2 -> listOf(::mirrorDash, ::stickyFingers, ::crossroads, ::hermaGates, ::featherVolley, ::mirrorDash)
            3 -> listOf(::summonSerpents, ::soulPillars, ::lanceRain, ::styxRise, ::courierDash)
            else -> listOf(::thunderDive, ::skySpears, ::lastWord)
        }
        val attack = list[rotation++ % list.size]()
        return sequence {
            yieldAll(attack)
            boss.pose = HermesPose.IDLE
            wait(if (stage == 4) 16 else 26)
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Treffer-Reaktionen
    // -------------------------------------------------------------------------------------------------

    /** Trugbilder zerplatzen beim ersten Treffer in einer Federwolke. */
    fun onIllusionStruck(entity: HermesEntity, source: DamageSource) {
        val pos = entity.entityPos
        Vfx.feathers(world, Vfx.Style.HEAVEN, pos.add(0.0, 1.5, 0.0), 26, 1.4f, 40)
        Vfx.nova(world, Vfx.Style.HEAVEN, pos.add(0.0, 1.5, 0.0), 2.6f, 10)
        sound(HhaSounds.BLINK, pos, 1.5f, 1.3f)
        (source.attacker as? ServerPlayerEntity)?.let { it.sendMessage(Text.translatable("hha.hermes.hint.fake").formatted(Formatting.GOLD), true) }
        fight.illusions.remove(entity)
        entity.discard()
    }

    /** Ein Treffer auf den echten Hermes lässt alle Trugbilder verpuffen. */
    fun onBossStruck(entity: HermesEntity) {
        sound(HhaSounds.HIT, entity.entityPos.add(0.0, 1.5, 0.0), 1.6f, 0.9f + world.random.nextFloat() * 0.25f)
        if (fight.illusions.isEmpty()) return
        for (illusion in fight.illusions) {
            Vfx.feathers(world, Vfx.Style.HEAVEN, illusion.entityPos.add(0.0, 1.5, 0.0), 18, 1.2f, 40)
            illusion.discard()
        }
        fight.illusions.clear()
        Vfx.nova(world, Vfx.Style.GOLD, entity.entityPos.add(0.0, 1.5, 0.0), 5f, 12)
    }

    fun onMinionDied() {
        if (fight.serpents.isEmpty() && fight.stones.isEmpty()) staggered = true
    }

    // -------------------------------------------------------------------------------------------------
    // Hilfen
    // -------------------------------------------------------------------------------------------------

    private fun hoverY(height: Double = 3.0): Double = center.y + height

    private suspend fun SequenceScope<Unit>.flyTo(to: Vec3d, ticks: Int) {
        val from = boss.entityPos
        for (i in 1..ticks) {
            val raw = i.toFloat() / ticks
            val t = raw * raw * (3f - 2f * raw)
            boss.setPosition(from.lerp(to, t.toDouble()))
            yield(Unit)
        }
    }

    /** Blinzelt per Federwolke an einen neuen Ort. */
    private fun blink(entity: HermesEntity, to: Vec3d) {
        Vfx.feathers(world, Vfx.Style.GOLD, entity.entityPos.add(0.0, 1.5, 0.0), 14, 1.2f, 30)
        Vfx.flare(world, Vfx.Style.GOLD, entity.entityPos.add(0.0, 1.5, 0.0), 3f, 8)
        entity.setPosition(to)
        Vfx.flare(world, Vfx.Style.GOLD, to.add(0.0, 1.5, 0.0), 3f, 8)
        sound(HhaSounds.BLINK, to, 1.8f, 1f)
    }

    private fun shootBolt(kind: Int, from: Vec3d, direction: Vec3d, speed: Double, damage: Float, homing: Double, target: ServerPlayerEntity?) {
        val bolt = HhaEntities.HERMES_BOLT.create(world, SpawnReason.EVENT) ?: return
        bolt.kind = kind
        bolt.damage = fight.scaledDamage(damage)
        bolt.homing = homing
        bolt.target = target
        bolt.setOwner(boss)
        bolt.setPosition(from)
        bolt.velocity = direction.normalize().multiply(speed)
        world.spawnEntity(bolt)
    }

    /** Spieler nahe der Strecke von [a] nach [b]. */
    private fun playersOnSegment(a: Vec3d, b: Vec3d, radius: Double): List<ServerPlayerEntity> = fight.targets().filter { player ->
        val p = player.entityPos.add(0.0, 0.9, 0.0)
        val ab = b.subtract(a)
        val lenSq = ab.lengthSquared()
        val t = if (lenSq < 1e-6) 0.0 else (p.subtract(a).dotProduct(ab) / lenSq).coerceIn(0.0, 1.0)
        a.add(ab.multiply(t)).squaredDistanceTo(p) <= radius * radius
    }

    /** Dash eines beliebigen Hermes-Körpers mit Schaden auf der Bahn; Illusionen treffen schwächer. */
    private fun dashTask(entity: HermesEntity, from: Vec3d, to: Vec3d, speed: Double, damage: Float): Sequence<Unit> = sequence {
        entity.pose = HermesPose.DASH
        fight.face(entity, to)
        Vfx.trail(world, Vfx.Style.GOLD, entity, 0.8f, 30)
        val dir = to.subtract(from)
        val length = dir.length()
        val step = dir.normalize().multiply(speed)
        val alreadyHit = HashSet<ServerPlayerEntity>()
        var travelled = 0.0
        var position = from
        while (travelled < length && entity.isAlive) {
            val next = position.add(step)
            for (player in playersOnSegment(position.add(0.0, 1.0, 0.0), next.add(0.0, 1.0, 0.0), 1.7)) {
                if (alreadyHit.add(player)) {
                    fight.hurt(player, if (entity.isIllusion) damage * 0.4f else damage)
                    Fx.launchPlayer(player, step.normalize().multiply(1.1).add(0.0, 0.45, 0.0))
                    Vfx.flare(world, Vfx.Style.GOLD, player.entityPos.add(0.0, 1.0, 0.0), 2.2f, 7)
                }
            }
            entity.setPosition(next)
            position = next
            travelled += speed
            yield(Unit)
        }
        if (entity.isAlive) entity.pose = HermesPose.IDLE
    }

    // -------------------------------------------------------------------------------------------------
    // Stage 1: Der Bote
    // -------------------------------------------------------------------------------------------------

    private fun courierDash(): Sequence<Unit> = sequence {
        val hits = if (fight.stage >= 3) 4 else 3
        repeat(hits) {
            val player = fight.randomTarget() ?: return@sequence
            val from = boss.entityPos.let { Vec3d(it.x, center.y + 0.3, it.z) }
            val direction = player.entityPos.subtract(from).let { Vec3d(it.x, 0.0, it.z) }.let { if (it.lengthSquared() < 1.0) Vec3d(1.0, 0.0, 0.0) else it.normalize() }
            val to = fight.clampToArena(player.entityPos.add(direction.multiply(9.0)).let { Vec3d(it.x, center.y + 0.3, it.z) })
            boss.pose = HermesPose.CAST
            fight.face(boss, to)
            boss.setPosition(from)
            Vfx.telegraphLine(world, Vfx.Style.GOLD, from, to, 1.6f, TELEGRAPH_DASH)
            sound(HhaSounds.CHARGE, from, 1.8f, 1f)
            wait(TELEGRAPH_DASH)
            sound(HhaSounds.DASH, from)
            Vfx.shake(world, from, 1.5f, 6, 24f)
            yieldAll(dashTask(boss, from, to, 2.3, 11f))
            Vfx.shockwave(world, Vfx.Style.GOLD, to, 4f, 12, 1f)
            wait(10)
        }
    }

    private fun featherVolley(): Sequence<Unit> = sequence {
        val player = fight.randomTarget() ?: return@sequence
        val hover = center.add(fight.randomHorizontal(12.0)).add(0.0, 5.0, 0.0)
        flyTo(hover, 14)
        fight.face(boss, player.entityPos)
        boss.pose = HermesPose.THROW
        repeat(3) { wave ->
            val target = fight.randomTarget() ?: return@sequence
            val aim = target.eyePos.subtract(boss.entityPos.add(0.0, 2.2, 0.0))
            val (right, _) = dev.henny.hha.logic.boss.basisFlat(aim)
            for (i in -4..4) {
                val dir = aim.normalize().add(right.multiply(i * 0.13)).add(0.0, abs(i) * 0.015, 0.0)
                shootBolt(HermesBoltEntity.FEATHER, boss.entityPos.add(0.0, 2.2, 0.0), dir, 0.95, 5f, 0.035, target)
            }
            Vfx.crescent(world, Vfx.Style.HEAVEN, boss.entityPos.add(0.0, 2.2, 0.0), aim, 7f, 2.6f, 9)
            sound(HhaSounds.FEATHER_VOLLEY, boss.entityPos, 2f, 1f + wave * 0.08f)
            wait(16)
        }
    }

    private fun galeSlam(): Sequence<Unit> = sequence {
        val player = fight.randomTarget() ?: return@sequence
        boss.pose = HermesPose.ASCEND
        val spot = fight.clampToArena(Vec3d(player.x, center.y, player.z))
        flyTo(spot.add(0.0, 14.0, 0.0), 16)
        boss.pose = HermesPose.SLAM
        val radius = 7f
        Vfx.telegraphRing(world, Vfx.Style.GOLD, spot, radius, 22)
        sound(HhaSounds.RIFT, spot, 1.5f, 1.5f)
        wait(22)
        boss.setPosition(spot.add(0.0, 0.1, 0.0))
        Vfx.shockwave(world, Vfx.Style.GOLD, spot, 24f, 26, 2.2f)
        Vfx.nova(world, Vfx.Style.GOLD, spot.add(0.0, 1.0, 0.0), 6f, 12)
        Vfx.shake(world, spot, 5f, 16, 40f)
        sound(HhaSounds.IMPACT, spot, 3f, 1f)
        for (target in fight.targets()) {
            if (target.entityPos.squaredDistanceTo(spot) < radius * radius) {
                fight.hurt(target, 14f)
                Fx.launchPlayer(target, target.entityPos.subtract(spot).normalize().multiply(1.3).add(0.0, 0.7, 0.0))
            }
        }
        // Die Welle läuft über den Boden: wer springt, entkommt.
        fight.runTask(groundWave(spot, 24.0, 0.9, 8f))
        wait(24)
    }

    private fun groundWave(from: Vec3d, reach: Double, speed: Double, damage: Float): Sequence<Unit> = sequence {
        val hit = HashSet<ServerPlayerEntity>()
        var radius = 0.0
        while (radius < reach) {
            for (player in fight.targets()) {
                val d = Vec3d(player.x - from.x, 0.0, player.z - from.z).length()
                if (abs(d - radius) < 1.2 && fight.grounded(player) && hit.add(player)) {
                    fight.hurt(player, damage)
                    Fx.launchPlayer(player, Vec3d(0.0, 0.5, 0.0))
                }
            }
            radius += speed
            yield(Unit)
        }
    }

    private fun gustWall(): Sequence<Unit> = sequence {
        boss.pose = HermesPose.CAST
        flyTo(center.add(0.0, 4.0, 0.0), 12)
        sound(HhaSounds.RIFT, center, 2f, 1.2f)
        val count = 3
        for (i in 0 until count) {
            val a = i.toDouble() / count * Math.PI * 2.0 + world.random.nextDouble()
            val start = center.add(cos(a) * 20.0, 0.0, sin(a) * 20.0)
            val aim = fight.randomTarget()?.entityPos?.let { Vec3d(it.x, center.y, it.z) } ?: center
            val velocity = aim.subtract(start).normalize().multiply(0.3)
            Vfx.tornado(world, Vfx.Style.GOLD, start, velocity, 3.2f, 13f, 110)
            fight.runTask(tornadoTask(start, velocity, 110))
        }
        wait(60)
    }

    private fun tornadoTask(start: Vec3d, velocity: Vec3d, life: Int): Sequence<Unit> = sequence {
        var pos = start
        repeat(life) { tick ->
            pos = pos.add(velocity)
            for (player in fight.targets()) {
                val offset = pos.subtract(player.entityPos).let { Vec3d(it.x, 0.0, it.z) }
                if (offset.length() < 3.4) {
                    if (tick % 10 == 0) fight.hurt(player, 3f, magic = true)
                    Fx.launchPlayer(player, offset.normalize().multiply(0.24).add(0.0, 0.42, 0.0))
                }
            }
            yield(Unit)
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Stage 2: Der Dieb
    // -------------------------------------------------------------------------------------------------

    private fun spawnIllusion(pos: Vec3d): HermesEntity {
        val ghost = HhaEntities.HERMES.create(world, SpawnReason.EVENT)!!
        ghost.isIllusion = true
        ghost.corrupted = boss.corrupted
        ghost.stage = boss.stage
        ghost.setPosition(pos)
        world.spawnEntity(ghost)
        fight.illusions.add(ghost)
        return ghost
    }

    private fun mirrorDash(): Sequence<Unit> = sequence {
        val player = fight.randomTarget() ?: return@sequence
        val ring = 15.0
        val phase = world.random.nextDouble() * Math.PI * 2.0
        val slots = List(5) { i ->
            val a = phase + i * Math.PI * 2.0 / 5.0
            fight.clampToArena(Vec3d(player.x + cos(a) * ring, center.y + 0.3, player.z + sin(a) * ring))
        }
        val realSlot = world.random.nextInt(slots.size)
        Vfx.nova(world, Vfx.Style.HEAVEN, boss.entityPos.add(0.0, 1.5, 0.0), 4f, 10)
        sound(HhaSounds.CHARGE, player.entityPos, 2.2f, 1.2f)
        for (old in fight.illusions) old.discard()
        fight.illusions.clear()
        blink(boss, slots[realSlot])
        val bodies = ArrayList<HermesEntity>()
        for ((i, slot) in slots.withIndex()) {
            if (i == realSlot) bodies.add(boss) else bodies.add(spawnIllusion(slot))
        }
        // Verrät sich nur durch den goldenen Kreis unter dem Echten.
        Vfx.rune(world, Vfx.Style.GOLD, slots[realSlot].add(0.0, -0.2, 0.0), 2.2f, 44, boss)
        val aim = player.entityPos.let { Vec3d(it.x, center.y + 0.3, it.z) }
        for ((i, body) in bodies.withIndex()) {
            body.pose = HermesPose.CAST
            fight.face(body, aim)
            val to = fight.clampToArena(aim.add(aim.subtract(slots[i]).normalize().multiply(10.0)))
            Vfx.telegraphLine(world, Vfx.Style.HEAVEN, slots[i], to, 1.2f, 30)
        }
        wait(30)
        sound(HhaSounds.DASH, aim, 3f, 1f)
        for ((i, body) in bodies.withIndex()) {
            val to = fight.clampToArena(aim.add(aim.subtract(slots[i]).normalize().multiply(10.0)))
            fight.runTask(dashTask(body, slots[i], to, 2.0, 12f))
        }
        wait(34)
        // Die Trugbilder bleiben kurz stehen und spotten — Zeit, den Echten zu finden.
        for (ghost in fight.illusions) ghost.pose = HermesPose.TAUNT
        wait(30)
        for (ghost in fight.illusions.toList()) {
            Vfx.feathers(world, Vfx.Style.HEAVEN, ghost.entityPos.add(0.0, 1.5, 0.0), 12, 1f, 30)
            ghost.discard()
        }
        fight.illusions.clear()
    }

    private fun stickyFingers(): Sequence<Unit> = sequence {
        val player = fight.randomTarget() ?: return@sequence
        val look = player.getRotationVec(1f).let { Vec3d(it.x, 0.0, it.z).normalize() }
        val behind = fight.clampToArena(Vec3d(player.x, center.y, player.z).subtract(look.multiply(2.2)))
        boss.pose = HermesPose.SNAP
        blink(boss, behind)
        fight.face(boss, player.entityPos)
        Vfx.telegraphRing(world, Vfx.Style.HEAVEN, player.entityPos, 2.4f, 16)
        wait(16)
        Vfx.nova(world, Vfx.Style.HEAVEN, player.entityPos.add(0.0, 1.0, 0.0), 3.4f, 10)
        sound(HhaSounds.BLINK, player.entityPos, 2f, 0.8f)
        if (player.entityPos.squaredDistanceTo(behind) < 16.0) steal(player)
        wait(8)
        boss.pose = HermesPose.TAUNT
        blink(boss, center.add(fight.randomHorizontal(16.0)).add(0.0, 3.0, 0.0))
        wait(18)
    }

    /** Dieb bleibt Dieb: nimmt alle positiven Effekte und mischt die Schnellleiste. Es geht kein Gegenstand verloren. */
    private fun steal(player: ServerPlayerEntity) {
        val beneficial = player.statusEffects
            .filter { it.effectType.value().category == StatusEffectCategory.BENEFICIAL }
            .map { it.effectType }
        for (effect in beneficial) player.removeStatusEffect(effect)
        val inv = player.inventory
        for (i in 0 until 4) {
            val a = world.random.nextInt(9)
            val b = world.random.nextInt(9)
            val stackA = inv.getStack(a)
            inv.setStack(a, inv.getStack(b))
            inv.setStack(b, stackA)
        }
        player.currentScreenHandler.syncState()
        player.sendMessage(Text.translatable("hha.hermes.hint.stolen").formatted(Formatting.GOLD), true)
        fight.hurt(player, 6f)
    }

    private fun crossroads(): Sequence<Unit> = sequence {
        boss.pose = HermesPose.CAST
        flyTo(center.add(0.0, 7.0, 0.0), 12)
        sound(HhaSounds.RIFT, center, 2f, 0.9f)
        repeat(4) { wave ->
            val base = world.random.nextDouble() * Math.PI
            val lines = 2 + wave / 2
            val rays = List(lines) { i ->
                val a = base + i * Math.PI / lines
                val offset = Vec3d(cos(a), 0.0, sin(a)).multiply(HermesArena.RADIUS - 1.0)
                center.add(offset) to center.subtract(offset)
            }
            for ((from, to) in rays) Vfx.telegraphLine(world, Vfx.Style.HEAVEN, from, to, 1.5f, 24)
            wait(24)
            for ((from, to) in rays) {
                Vfx.beam(world, Vfx.Style.HEAVEN, from.add(0.0, 0.8, 0.0), to.add(0.0, 0.8, 0.0), 0.8f, 14)
                for (player in playersOnSegment(from, to, 2.0)) {
                    if (fight.grounded(player)) fight.hurt(player, 11f)
                }
            }
            Vfx.shake(world, center, 3f, 8, 40f)
            sound(HhaSounds.DASH, center, 2.5f, 1.3f)
            wait(14)
        }
    }

    private fun hermaGates(): Sequence<Unit> = sequence {
        boss.pose = HermesPose.CAST
        flyTo(center.add(0.0, 2.5, 0.0), 14)
        fight.bossInvulnerable = true
        staggered = false
        hint("hha.hermes.hint.stones")
        val spots = List(4) { i ->
            val a = i * Math.PI / 2.0 + Math.PI / 4.0
            center.add(cos(a) * 15.0, 0.0, sin(a) * 15.0)
        }
        for (spot in spots) {
            val stone = HhaEntities.HERMA.create(world, SpawnReason.EVENT)!!
            stone.setPosition(spot)
            fight.face(stone, center)
            world.spawnEntity(stone)
            fight.scaleMinion(stone, 50.0)
            fight.stones.add(stone)
            Vfx.shockwave(world, Vfx.Style.GOLD, spot, 5f, 14, 1.4f)
            sound(SoundEvents.BLOCK_STONE_PLACE, spot, 2f, 0.6f)
            wait(5)
        }
        // Zwischen den Steinen fließt Licht; wer durchläuft, verbrennt sich.
        var time = 0
        while (fight.stones.isNotEmpty() && time < 300) {
            val stones = fight.stones.toList()
            if (time % 8 == 0 && stones.size > 1) {
                for (i in stones.indices) {
                    val a = stones[i].entityPos.add(0.0, 2.6, 0.0)
                    val b = stones[(i + 1) % stones.size].entityPos.add(0.0, 2.6, 0.0)
                    Vfx.beam(world, Vfx.Style.GOLD, a, b, 0.32f, 10)
                }
            }
            if (time % 6 == 0 && stones.size > 1) {
                for (i in stones.indices) {
                    val a = stones[i].entityPos.add(0.0, 1.0, 0.0)
                    val b = stones[(i + 1) % stones.size].entityPos.add(0.0, 1.0, 0.0)
                    for (player in playersOnSegment(a, b, 1.1)) fight.hurt(player, 5f, magic = true)
                }
            }
            if (time % 70 == 35) {
                val target = fight.randomTarget()
                if (target != null) shootBolt(HermesBoltEntity.FEATHER, boss.entityPos.add(0.0, 2.4, 0.0), target.eyePos.subtract(boss.entityPos.add(0.0, 2.4, 0.0)), 1.0, 5f, 0.04, target)
            }
            time++
            yield(Unit)
        }
        fight.bossInvulnerable = false
        if (fight.stones.isEmpty()) {
            // Alle Steine zerstört: Hermes taumelt und ist offen für einen Konter.
            boss.pose = HermesPose.STAGGER
            boss.setPosition(center.add(0.0, 0.1, 0.0))
            Vfx.nova(world, Vfx.Style.GOLD, center.add(0.0, 1.5, 0.0), 7f, 14)
            sound(HhaSounds.IMPACT, center, 3f, 1.5f)
            hint("hha.hermes.hint.stagger")
            wait(90)
        } else {
            // Zu langsam: die Steine entladen sich.
            for (stone in fight.stones.toList()) {
                Vfx.pillar(world, Vfx.Style.GOLD, stone.entityPos, 2.4f, 22f, 26)
                Vfx.shockwave(world, Vfx.Style.GOLD, stone.entityPos, 11f, 18, 2f)
                for (player in fight.targets()) {
                    if (player.entityPos.squaredDistanceTo(stone.entityPos) < 121.0) fight.hurt(player, 12f, magic = true)
                }
                stone.discard()
            }
            fight.stones.clear()
            Vfx.shake(world, center, 6f, 18, 50f)
            sound(HhaSounds.IMPACT, center, 3f, 0.8f)
            wait(30)
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Stage 3: Der Seelenführer
    // -------------------------------------------------------------------------------------------------

    private fun summonSerpents(): Sequence<Unit> = sequence {
        boss.pose = HermesPose.CAST
        flyTo(center.add(0.0, 6.0, 0.0), 12)
        if (fight.serpents.isEmpty()) {
            for (serpent in fight.spawnSerpents()) {
                fight.runTask(serpentTask(serpent))
            }
            hint("hha.hermes.hint.serpents")
            sound(HhaSounds.ROAR, center, 3f, 1f)
        } else if (!serpentTaskRunning) {
            for (serpent in fight.serpents) fight.runTask(serpentTask(serpent))
        }
        serpentTaskRunning = true
        wait(30)
    }

    /** Eine Schlange kreist um ihr Ziel, setzt zum Biss an und stößt zu. */
    private fun serpentTask(serpent: SerpentEntity): Sequence<Unit> = sequence {
        var phase = world.random.nextDouble() * 6.28
        while (serpent.isAlive && !serpent.isRemoved) {
            val target = fight.randomTarget() ?: return@sequence
            // Umkreisen.
            repeat(70 + world.random.nextInt(40)) {
                if (!serpent.isAlive) return@sequence
                phase += 0.08
                val goal = target.entityPos.add(cos(phase) * 9.0, 4.0 + sin(phase * 1.7) * 2.5, sin(phase) * 9.0)
                moveSerpent(serpent, goal, 0.62)
                yield(Unit)
            }
            // Anpirschen: kurz stoppen und aufbäumen.
            serpent.biting = true
            sound(HhaSounds.HISS, serpent.entityPos, 2f, 1f)
            val aimed = target.entityPos.add(0.0, 1.0, 0.0)
            Vfx.telegraphLine(world, Vfx.Style.SOUL, Vec3d(serpent.x, center.y, serpent.z), Vec3d(aimed.x, center.y, aimed.z), 1.1f, 14)
            repeat(14) {
                if (!serpent.isAlive) return@sequence
                fight.face(serpent, aimed)
                yield(Unit)
            }
            // Zustoßen.
            val hit = HashSet<ServerPlayerEntity>()
            repeat(14) {
                if (!serpent.isAlive) return@sequence
                moveSerpent(serpent, aimed, 1.5)
                for (player in fight.targets()) {
                    // Gemessen wird am Maul, das gut einen Block vor dem Kopf-Mittelpunkt sitzt.
                    val yaw = Math.toRadians(serpent.yaw.toDouble())
                    val mouth = serpent.entityPos.add(-sin(yaw) * 1.1, 0.6, cos(yaw) * 1.1)
                    if (player.entityPos.add(0.0, 1.0, 0.0).squaredDistanceTo(mouth) < 5.0 && hit.add(player)) {
                        fight.hurt(player, 8f)
                        sound(HhaSounds.BITE, player.entityPos, 1.8f, 1f)
                        player.addStatusEffect(StatusEffectInstance(StatusEffects.POISON, 50, 1))
                    }
                }
                yield(Unit)
            }
            serpent.biting = false
        }
    }

    private fun moveSerpent(serpent: SerpentEntity, goal: Vec3d, speed: Double) {
        val offset = goal.subtract(serpent.entityPos)
        val distance = offset.length()
        if (distance < 0.05) return
        val next = serpent.entityPos.add(offset.multiply((speed / distance).coerceAtMost(1.0)))
        // Nicht unter den Boden tauchen.
        serpent.setPosition(next.x, next.y.coerceAtLeast(center.y + 0.6), next.z)
        fight.face(serpent, goal)
    }

    private fun soulPillars(): Sequence<Unit> = sequence {
        boss.pose = HermesPose.CAST
        flyTo(center.add(0.0, 8.0, 0.0), 12)
        repeat(7) {
            for (player in fight.targets()) {
                val lead = Vec3d(player.x, center.y, player.z).add(player.velocity.multiply(10.0))
                val spot = fight.clampToArena(lead)
                Vfx.telegraphRing(world, Vfx.Style.SOUL, spot, 3f, 18)
                fight.runTask(sequence {
                    repeat(18) { yield(Unit) }
                    Vfx.pillar(world, Vfx.Style.SOUL, spot, 2.2f, 20f, 22)
                    sound(HhaSounds.SOUL, spot, 1.6f, 1.2f)
                    for (target in fight.targets()) {
                        if (Vec3d(target.x, center.y, target.z).squaredDistanceTo(spot) < 9.0) {
                            fight.hurt(target, 11f, magic = true)
                            Fx.launchPlayer(target, Vec3d(0.0, 0.9, 0.0))
                        }
                    }
                })
            }
            wait(12)
        }
        wait(20)
    }

    private fun lanceRain(): Sequence<Unit> = sequence {
        boss.pose = HermesPose.THROW
        flyTo(center.add(fight.randomHorizontal(10.0)).add(0.0, 12.0, 0.0), 14)
        sound(HhaSounds.SOUL, boss.entityPos, 2f, 0.9f)
        repeat(18) {
            val player = fight.randomTarget() ?: return@sequence
            val spot = fight.clampToArena(Vec3d(player.x, center.y, player.z).add(fight.randomHorizontal(world.random.nextDouble() * 5.0)))
            val origin = spot.add(fight.randomHorizontal(6.0)).add(0.0, 24.0, 0.0)
            Vfx.telegraphRing(world, Vfx.Style.SOUL, spot, 1.6f, 16)
            fight.runTask(sequence {
                repeat(11) { yield(Unit) }
                shootBolt(HermesBoltEntity.LANCE, origin, spot.subtract(origin), 4.2, 9f, 0.0, null)
                sound(HhaSounds.SPEAR, spot, 1.6f, 0.9f + world.random.nextFloat() * 0.25f)
            })
            wait(4)
        }
        wait(20)
    }

    private fun styxRise(): Sequence<Unit> = sequence {
        boss.pose = HermesPose.CAST
        flyTo(center.add(0.0, 9.0, 0.0), 14)
        hint("hha.hermes.hint.styx")
        sound(HhaSounds.FLOOD, center, 3.5f, 1f)
        sound(HhaSounds.SOUL, center, 2f, 0.7f)
        // Erst flutet der Rand (rette dich in die Mitte), dann die Mitte (rette dich nach außen).
        val radius = HermesArena.RADIUS.toFloat()
        val phases = listOf(radius to 8f, 13f to 0f, radius to 9f)
        for ((outer, inner) in phases) {
            Vfx.telegraphRing(world, Vfx.Style.SOUL, center, if (inner > 0f) inner else outer, 30)
            wait(30)
            Vfx.flood(world, Vfx.Style.SOUL, center, outer, inner, 70)
            Vfx.shake(world, center, 2f, 40, 40f)
            repeat(60) { tick ->
                if (tick % 10 == 0) {
                    for (player in fight.targets()) {
                        val d = Vec3d(player.x - center.x, 0.0, player.z - center.z).length()
                        if (d in inner.toDouble()..outer.toDouble() && fight.grounded(player)) {
                            fight.hurt(player, 7f, magic = true)
                        }
                    }
                }
                yield(Unit)
            }
            wait(10)
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Stage 4: Der Götterbote — nur in der Erschöpfung verwundbar
    // -------------------------------------------------------------------------------------------------

    private suspend fun SequenceScope<Unit>.exhausted(ticks: Int) {
        val spot = fight.clampToArena(boss.entityPos.let { Vec3d(it.x, center.y + 0.1, it.z) }, 6.0)
        flyTo(spot, 10)
        boss.pose = HermesPose.KNEEL
        boss.vulnerable = true
        Vfx.nova(world, Vfx.Style.GOLD, spot.add(0.0, 1.5, 0.0), 5f, 12)
        Vfx.rune(world, Vfx.Style.GOLD, spot.add(0.0, 0.05, 0.0), 4f, ticks)
        Vfx.shockwave(world, Vfx.Style.GOLD, spot, 10f, 18, 1.4f)
        sound(HhaSounds.CHIME, spot, 3f, 1.4f)
        hint("hha.hermes.hint.exhausted")
        wait(ticks)
        boss.vulnerable = false
        boss.pose = HermesPose.ASCEND
        Vfx.shockwave(world, Vfx.Style.STORM, spot, 12f, 16, 2.4f)
        for (player in fight.targets()) {
            if (player.entityPos.squaredDistanceTo(spot) < 36.0) {
                Fx.launchPlayer(player, player.entityPos.subtract(spot).normalize().multiply(1.2).add(0.0, 0.7, 0.0))
            }
        }
    }

    private fun thunderDive(): Sequence<Unit> = sequence {
        repeat(5) {
            val player = fight.randomTarget() ?: return@sequence
            boss.pose = HermesPose.ASCEND
            val spot = fight.clampToArena(Vec3d(player.x, center.y, player.z))
            flyTo(spot.add(0.0, 20.0, 0.0), 7)
            Vfx.telegraphRing(world, Vfx.Style.STORM, spot, 5f, 14)
            boss.pose = HermesPose.SLAM
            wait(14)
            boss.pose = HermesPose.DASH
            Vfx.trail(world, Vfx.Style.GOLD, boss, 0.8f, 12)
            flyTo(spot.add(0.0, 0.2, 0.0), 3)
            fight.lightning(spot)
            Vfx.bolt(world, Vfx.Style.STORM, spot.add(0.0, 40.0, 0.0), spot.add(0.0, 0.3, 0.0), 0.9f, 8)
            Vfx.shockwave(world, Vfx.Style.STORM, spot, 12f, 16, 2.4f)
            Vfx.shake(world, spot, 4f, 10, 40f)
            for (target in fight.targets()) {
                if (target.entityPos.squaredDistanceTo(spot) < 25.0) {
                    fight.hurt(target, 13f)
                    Fx.launchPlayer(target, target.entityPos.subtract(spot).normalize().multiply(1.1).add(0.0, 0.7, 0.0))
                }
            }
            fight.runTask(groundWave(spot, 12.0, 0.8, 6f))
            wait(8)
        }
        exhausted(100)
    }

    private fun skySpears(): Sequence<Unit> = sequence {
        boss.pose = HermesPose.CAST
        flyTo(center.add(0.0, 16.0, 0.0), 14)
        hint("hha.hermes.hint.spear")
        sound(HhaSounds.RIFT, center, 3f, 0.7f)
        val durations = listOf(50, 42, 34)
        for (duration in durations) {
            val player = fight.randomTarget() ?: return@sequence
            val spot = fight.clampToArena(Vec3d(player.x, center.y, player.z))
            Vfx.spear(world, Vfx.Style.GOLD, spot, 60f, 1f, duration + 8)
            Vfx.telegraphRing(world, Vfx.Style.GOLD, spot, 10f, duration)
            wait(duration)
            wait(8)
            fight.lightning(spot)
            Vfx.nova(world, Vfx.Style.GOLD, spot.add(0.0, 2.0, 0.0), 12f, 18)
            Vfx.shockwave(world, Vfx.Style.GOLD, spot, 24f, 26, 4f)
            Vfx.shake(world, spot, 7f, 20, 60f)
            sound(HhaSounds.IMPACT, spot, 4f, 0.7f)
            for (target in fight.targets()) {
                if (Vec3d(target.x, center.y, target.z).squaredDistanceTo(spot) < 100.0) {
                    fight.hurt(target, 24f)
                    Fx.launchPlayer(target, target.entityPos.subtract(spot).normalize().multiply(1.3).add(0.0, 0.9, 0.0))
                }
            }
            fight.runTask(groundWave(spot, 24.0, 1.0, 7f))
            wait(8)
        }
        exhausted(110)
    }

    private fun lastWord(): Sequence<Unit> = sequence {
        boss.pose = HermesPose.CAST
        flyTo(center.add(0.0, 1.1, 0.0), 14)
        hint("hha.hermes.hint.jump")
        sound(HhaSounds.RIFT, center, 3f, 1.4f)
        val length = HermesArena.RADIUS - 1.0
        val origin = center.add(0.0, 1.0, 0.0)
        repeat(130) { tick ->
            val angle = tick * 0.05 + (tick * tick) * 0.00012
            for (i in 0 until 2) {
                val a = angle + i * Math.PI
                val tip = origin.add(cos(a) * length, 0.0, sin(a) * length)
                if (tick % 2 == 0) Vfx.beam(world, Vfx.Style.GOLD, origin, tip, 0.5f, 3)
                if (tick >= 10) {
                    for (player in playersOnSegment(origin, tip, 1.3)) {
                        if (fight.grounded(player)) {
                            fight.hurt(player, 9f, magic = true)
                            Fx.launchPlayer(player, Vec3d(0.0, 0.6, 0.0))
                        }
                    }
                }
            }
            if (tick % 12 == 0) sound(HhaSounds.WHOOSH, center, 2f, 1.4f)
            yield(Unit)
        }
        exhausted(100)
    }

    companion object {
        private const val TELEGRAPH_DASH = 18
    }
}

/** Waagerechte Seitenachse quer zu einer Richtung. */
internal fun basisFlat(direction: Vec3d): Pair<Vec3d, Vec3d> {
    val flat = Vec3d(direction.x, 0.0, direction.z).let { if (it.lengthSquared() < 1e-4) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
    val right = flat.crossProduct(Vec3d(0.0, 1.0, 0.0)).normalize()
    return right to flat
}
