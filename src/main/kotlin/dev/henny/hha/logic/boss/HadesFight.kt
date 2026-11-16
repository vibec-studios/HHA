package dev.henny.hha.logic.boss

import dev.henny.hha.Hha
import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaItems
import dev.henny.hha.HhaSounds
import dev.henny.hha.config.HhaConfig
import dev.henny.hha.entity.boss.HadesEntity
import dev.henny.hha.entity.boss.HadesPose
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.entity.boss.HermesEntity
import dev.henny.hha.entity.boss.HermesPose
import dev.henny.hha.entity.boss.KronosEntity
import dev.henny.hha.entity.boss.KronosPose
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.world.TartarusPlans
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.tag.DamageTypeTags
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Laufende Hades-Kämpfe (höchstens einer pro Dimension) und ihre Anbindung an die Spielereignisse. */
object HadesFights {

    private val active = HashMap<RegistryKey<World>, HadesFight>()

    fun init() {
        Cine.onSkip { player -> active[player.entityWorld.registryKey]?.voteSkip(player) == true }
        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, _, _ ->
            if (entity is ServerPlayerEntity) {
                val fight = active[entity.entityWorld.registryKey]
                !(fight != null && fight.tryKnockOut(entity))
            } else {
                true
            }
        }
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, _ ->
            if (entity is ServerPlayerEntity) {
                val fight = active[entity.entityWorld.registryKey]
                // Hades und Kronos schleudern ständig durch den Saal: Die Landung soll nicht zusätzlich strafen.
                !(fight != null && fight.isParticipant(entity) && (fight.inCutscene || source.isIn(DamageTypeTags.IS_FALL)))
            } else {
                true
            }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val player = handler.player
            active[player.entityWorld.registryKey]?.onPlayerLeft(player)
        }
        // Wer per Portal oder Befehl die Dimension wechselt, scheidet aus; der Client verlässt Szene, Musik und Leiste.
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register { player, origin, _ ->
            active[origin.registryKey]?.onPlayerChangedWorld(player)
        }
        // Der Kampf lebt nur im Speicher: Vor dem Herunterfahren muss der Saal wieder so aussehen wie vorher.
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            for (fight in active.values.toList()) fight.finish(false)
        }
    }

    fun tick(server: MinecraftServer) {
        for (fight in active.values.toList()) Cine.within(fight) { fight.tick() }
    }

    fun of(world: ServerWorld): HadesFight? = active[world.registryKey]

    fun isParticipant(player: ServerPlayerEntity): Boolean = active[player.entityWorld.registryKey]?.isParticipant(player) == true

    fun onFinished(fight: HadesFight) {
        active.remove(fight.world.registryKey)
    }

    /** Vom Siegel aufgerufen. [center] ist die Mitte des Hallenbodens. */
    fun start(player: ServerPlayerEntity, center: BlockPos): Boolean {
        val world = player.entityWorld as? ServerWorld ?: return false
        if (active.containsKey(world.registryKey)) {
            player.sendMessage(Text.translatable("hha.hermes.msg.busy").formatted(Formatting.RED), true)
            return false
        }
        val fight = HadesFight(world, center, player)
        active[world.registryKey] = fight
        fight.begin()
        return true
    }
}

/**
 * Der Kampf im Thronsaal, in fünf Phasen auf zwei Leisten (Plan 4.4 und 4.5):
 *
 * 1. Der Gastgeber: Duell mit dem Bident, dazu sein Blick, vor dem nur die Säulen schützen. Er fällt, der Sieg wird
 *    verkündet, eine Truhe steht bereit. Das ist der Köder.
 * 2. Die Pforten der Hölle: Am Rand stehen zwei Pforten offen (Kulisse), und der Saal greift mit an: Zug der Toten,
 *    Höllenfeuer, Spalten. Dazu Seelenketten, die hinter einer Säule reißen, und der Schattenschritt.
 * 3. Das Bündnis: Hermes greift als Geist nur zusammen mit ihm an (Zangenangriff), dazu das Urteil, und an den
 *    Lebensschwellen stürzt der Boden Ring für Ring in die Tiefe.
 *
 * Dann gibt Hades auf, und genau das reißt den Tartaros auf: Kronos bricht durch die Kuppel.
 *
 * 4. Der Titan erwacht: vier feste Ketten (Ernte, Äon, Wurf, Zeitschritt), Uhrzeiger, Stillstand und die Rückspulung,
 *    bei der man seine Sanduhren zerschlagen muss.
 * 5. Das Ende der Zeit: Er wächst, verschlingt, der Saal altert oder lässt Zeitsand fallen. Hades steht auf und kämpft
 *    diesmal an deiner Seite: Seine Ketten halten den Vater immer wieder fest.
 *
 * Dieses Objekt hält Zustand und Szenen; die Attacken stehen in [HadesAttacks] und [KronosAttacks]. Alle Ansagen laufen
 * über [Telegraph] ([hadesCalls], [kronosCalls]), der ganze Kampf-Tick im Scope von [Telegraph.Fight.during]. Alle
 * Blockänderungen merkt sich [edit], am Ende steht der Saal wieder wie vorher.
 */
class HadesFight(val world: ServerWorld, val floor: BlockPos, summoner: ServerPlayerEntity) {

    /** Mitte des Hallenbodens (Oberkante). */
    val center: Vec3d = Vec3d(floor.x + 0.5, floor.y + 1.0, floor.z + 0.5)

    /** Vor dem Siegel, draußen auf dem Vorplatz: Dorthin geht es bei Niederlage und nach dem Sieg. */
    private val outside: Vec3d = center.add(0.0, 0.0, TartarusPlans.SEAL_Z + 9.5)

    private val participants = LinkedHashSet<UUID>().apply {
        add(summoner.uuid)
        Cine.rejoin(summoner, this@HadesFight)
    }
    private val attacks = HadesAttacks(this)
    private val titan = KronosAttacks(this)
    private val skip = CineSkip()

    /** Ansagen und Effekt-Regeln (Plan 3): Hades in Giftgrün, Kronos in Äon. */
    val hadesCalls: Telegraph.Fight = Telegraph.open(world, "hades", Vfx.Style.HADES) { targets() }
    val kronosCalls: Telegraph.Fight = Telegraph.open(world, "kronos", Vfx.Style.AEON) { targets() }

    var boss: HadesEntity? = null
        private set
    var kronos: KronosEntity? = null
        private set
    var hermes: HermesEntity? = null
    var hermesAway = 0

    /** 1 bis 3 sind Hades, 4 und 5 Kronos, 6 das Ende. */
    var stage = 0
        private set
    var inCutscene = true
        private set

    /** Solange er zählt, steht der Boss offen: Schläge treffen härter und werfen ihn weiter aus dem Tritt. */
    var openingFor = 0

    /** Angesammelter Schaden; wird es zu viel, bricht seine Haltung und er taumelt. */
    private var poise = 0f

    private var script: Iterator<Unit>? = null
    private val tasks = ArrayList<Iterator<Unit>>()
    private var transitionQueued = false
    private var noPlayersFor = 0
    var healthScale = 1f
        private set
    private var damageScale = 1f
    private var ended = false

    /** Ticks seit Beginn der laufenden Phase. */
    var stageAge = 0
        private set

    val isEnded: Boolean get() = ended

    /** Radius des Bodens, der noch steht. In der dritten Phase schrumpft er. */
    var safeRadius = ARENA.toDouble()

    /** Säulen, die noch stehen und vor seinem Blick schützen: Versatz zur Mitte. */
    val intactPillars = ArrayList(TartarusPlans.coverPillars())

    /** Originalzustand aller Blöcke, die der Kampf verändert hat. */
    private val edits = LinkedHashMap<BlockPos, BlockState>()

    /** Was eine laufende Attacke verändert hat, mit dem Zustand direkt davor. */
    private val scars = LinkedHashMap<BlockPos, BlockState>()

    /** Reißt gerade eine Attacke den Boden auf? Dann wartet die nächste, sonst stellt das Aufräumen falsche Blöcke zurück. */
    var floorBusy = false

    /** Die Lebensleiste wird nur geschickt, wenn sich etwas sichtbar ändert. */
    private var barVisible = false
    private var lastBar = ""

    fun begin() {
        for (player in world.players) {
            if (player.squaredDistanceTo(center) <= 70.0 * 70.0 && !player.isSpectator) {
                participants.add(player.uuid)
                Cine.rejoin(player, this)
            }
        }
        scale()
        script = intro().iterator()
    }

    fun players(): List<ServerPlayerEntity> = participants.mapNotNull { world.getPlayerByUuid(it) as? ServerPlayerEntity }.filter { it.isAlive }
    fun targets(): List<ServerPlayerEntity> = players().filter { !it.isSpectator }
    fun isParticipant(player: ServerPlayerEntity): Boolean = player.uuid in participants
    fun randomTarget(): ServerPlayerEntity? = targets().let { if (it.isEmpty()) null else it[world.random.nextInt(it.size)] }
    fun nearestTarget(from: Vec3d): ServerPlayerEntity? = targets().minByOrNull { it.squaredDistanceTo(from) }
    fun addTask(task: Sequence<Unit>) {
        tasks.add(task.iterator())
    }

    private fun scale() {
        val crowd = max(1, targets().size)
        healthScale = 1f + HhaConfig.numF("hermes_health_per_player") * (crowd - 1)
        damageScale = 1f + HhaConfig.numF("hermes_damage_per_player") * (crowd - 1)
    }

    fun scaledDamage(base: Float): Float = base * HhaConfig.numF("hades_damage") * damageScale

    /** Schwere Treffer: über `hades_damage` einstellbar. Mit [magic] hilft keine Rüstung. */
    fun hurt(player: ServerPlayerEntity, base: Float, magic: Boolean = false) {
        if (inCutscene) return
        // Ziele, die ein Ablauf früher gemerkt hat, können inzwischen ausgeschieden oder in einer anderen Welt sein.
        if (player.uuid !in participants || player.entityWorld !== world) return
        val attacker = if (stage >= 4) kronos else boss
        val source: DamageSource = if (magic || attacker == null) world.damageSources.magic() else world.damageSources.mobAttack(attacker)
        player.damage(world, source, scaledDamage(base))
    }

    fun grounded(player: ServerPlayerEntity): Boolean = player.y <= center.y + 0.7

    fun sound(event: SoundEvent, volume: Float = 2.5f, pitch: Float = 1f, at: Vec3d = center) {
        if (Cine.muted) return
        world.playSound(null, at.x, at.y, at.z, event, SoundCategory.HOSTILE, volume, pitch)
    }

    fun hint(key: String, color: Formatting = Formatting.GREEN) {
        for (player in targets()) player.sendMessage(Text.translatable(key).formatted(color), true)
    }

    fun face(target: Vec3d) {
        val entity = boss ?: return
        val d = target.subtract(entity.entityPos)
        if (d.x * d.x + d.z * d.z < 0.01) return
        entity.yaw = (Math.toDegrees(atan2(d.z, d.x)) - 90.0).toFloat()
    }

    fun faceTitan(target: Vec3d) {
        val entity = kronos ?: return
        val d = target.subtract(entity.entityPos)
        if (d.x * d.x + d.z * d.z < 0.01) return
        entity.yaw = (Math.toDegrees(atan2(d.z, d.x)) - 90.0).toFloat()
    }

    fun edit(pos: BlockPos, state: BlockState) {
        val key = pos.toImmutable()
        edits.putIfAbsent(key, world.getBlockState(key))
        world.setBlockState(key, state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
    }

    fun scar(pos: BlockPos, state: BlockState) {
        val key = pos.toImmutable()
        scars.putIfAbsent(key, world.getBlockState(key))
        edit(key, state)
    }

    /** Schließt Spalten und löscht Feuer. Wer noch unten steckt, wird vorher herausgehoben. */
    fun closeScars() {
        floorBusy = false
        if (scars.isEmpty()) return
        for (player in targets()) {
            if (player.y < center.y - 0.4 && player.y > center.y - 4.5) {
                player.teleport(world, player.x, center.y + 0.2, player.z, emptySet(), player.yaw, player.pitch, false)
                player.fallDistance = 0.0
            }
        }
        for ((pos, state) in scars) {
            // Was schon in die Tiefe gestürzt ist, kommt nicht wieder.
            if (Vec3d(pos.x + 0.5 - center.x, 0.0, pos.z + 0.5 - center.z).length() <= safeRadius) {
                world.setBlockState(pos, state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
            }
        }
        scars.clear()
    }

    private fun restoreEdits() {
        scars.clear()
        for ((pos, state) in edits) world.setBlockState(pos, state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
        edits.clear()
    }

    /** Vor dem Titanenkampf: Boden, Säulen und Spalten stehen wieder, nur das Siegel bleibt zu. */
    private fun healHall() {
        restoreEdits()
        floorBusy = false
        safeRadius = ARENA.toDouble()
        intactPillars.clear()
        intactPillars.addAll(TartarusPlans.coverPillars())
        sealShut()
    }

    fun clampToHall(pos: Vec3d, margin: Double = 4.0): Vec3d {
        val flat = Vec3d(pos.x - center.x, 0.0, pos.z - center.z)
        val limit = min(ARENA.toDouble(), safeRadius) - margin
        val clamped = if (flat.length() > limit) flat.normalize().multiply(limit) else flat
        return Vec3d(center.x + clamped.x, center.y, center.z + clamped.z)
    }

    private fun toughness(): Float = HhaConfig.numF("hermes_toughness") * healthScale * TOUGHNESS

    /** Vom Boss gemeldet: wie viel Schaden tatsächlich ankommt. Jede Phase hat ihren eigenen vollen Abschnitt der Leiste. */
    fun onDamaged(entity: HadesEntity, source: DamageSource, amount: Float): Float {
        if (inCutscene || stage !in 1..3) return 0f
        val open = openingFor > 0
        sound(HhaSounds.HIT, 1.8f, if (open) 1.05f else 0.75f, entity.entityPos)
        if (open) Vfx.flare(world, Vfx.Style.GOLD, entity.entityPos.add(0.0, 2.4, 0.0), 2.2f, 5)
        val raw = amount / toughness()
        val dealt = (if (open) raw * OPENING_BONUS else raw).coerceAtMost(HhaConfig.numF("hermes_max_hit") * (if (open) 1.5f else 1f))
        val allowed = min(dealt, entity.health - 1f)
        if (allowed <= 0f) return 0f
        if (entity.health - allowed <= 1.01f) transitionQueued = true
        if (!open) {
            poise += allowed
            if (poise >= POISE_LIMIT) {
                poise = 0f
                attacks.breakPoise()
            }
        }
        attacks.onHealth((entity.health - allowed) / entity.maxHealth)
        return allowed
    }

    /** Kronos: härter, aber er bleibt ehrlich. Offene Momente lohnen sich bei ihm noch mehr. */
    fun onKronosDamaged(entity: KronosEntity, source: DamageSource, amount: Float): Float {
        if (inCutscene || stage !in 4..5) return 0f
        val open = openingFor > 0
        sound(HhaSounds.HIT, 2f, if (open) 0.9f else 0.6f, entity.entityPos)
        if (open) Vfx.flare(world, Vfx.Style.GOLD, entity.entityPos.add(0.0, 3.4, 0.0), 2.8f, 5)
        val raw = amount / (toughness() * KRONOS_TOUGHNESS)
        val dealt = (if (open) raw * OPENING_BONUS else raw).coerceAtMost(HhaConfig.numF("hermes_max_hit") * (if (open) 1.5f else 1f))
        val allowed = min(dealt, entity.health - 1f)
        if (allowed <= 0f) return 0f
        if (entity.health - allowed <= 1.01f) transitionQueued = true
        if (!open) {
            poise += allowed
            if (poise >= POISE_LIMIT * 2.2f) {
                poise = 0f
                titan.breakPoise()
            }
        }
        titan.onHealth((entity.health - allowed) / entity.maxHealth)
        return allowed
    }

    /** Ersetzt die laufende Attacke; Gefahren, die schon auf dem Boden liegen, laufen als eigene Aufgaben weiter. */
    fun interrupt(with: Sequence<Unit>) {
        if (inCutscene) return
        script = with.iterator()
    }

    /** Hermes als Geist lässt sich nicht töten; ein Treffer vertreibt ihn kurz. */
    fun onHermesStruck(ghost: HermesEntity): Boolean {
        if (ghost !== hermes) return false
        if (stage != 3 || inCutscene) return true
        Vfx.feathers(world, Vfx.Style.HADES, ghost.entityPos.add(0.0, 1.5, 0.0), 16, 1.6f, 40)
        sound(HhaSounds.HIT, 1.8f, 1.2f, ghost.entityPos)
        ghost.discard()
        hermes = null
        hermesAway = BossTimings.Hades.HERMES_BANISH
        return true
    }

    /** Auch Kronos kann Säulen einreißen. */
    fun crumblePillar(pillar: Pair<Int, Int>) = attacks.crumblePillar(pillar)

    fun tryKnockOut(player: ServerPlayerEntity): Boolean {
        if (player.uuid !in participants) return false
        // Der tödliche Angriff läuft nach diesem Aufruf weiter: Seine Folge-Effekte sollen den Rückkehrort nicht erreichen.
        Fx.shield(player)
        player.health = max(4f, player.maxHealth * 0.25f)
        player.extinguish()
        for (effect in player.statusEffects.map { it.effectType }) player.removeStatusEffect(effect)
        drop(player)
        // Vollständig zurücksetzen (auch eine laufende Abblende) und alte Sendungen dieses Kampfes an ihn sperren.
        Cine.depart(player, this)
        skip.recheck(players())
        send(player)
        player.addStatusEffect(StatusEffectInstance(StatusEffects.REGENERATION, 200, 1))
        player.addStatusEffect(StatusEffectInstance(StatusEffects.RESISTANCE, 100, 4))
        player.sendMessage(Text.translatable(if (stage >= 4) "hha.kronos.fail" else "hha.hades.fail").formatted(Formatting.GREEN, Formatting.ITALIC), false)
        return true
    }

    fun onPlayerLeft(player: ServerPlayerEntity) {
        if (participants.contains(player.uuid)) {
            drop(player)
            skip.recheck(players().filter { it.uuid != player.uuid })
            player.refreshPositionAndAngles(outside.x, outside.y, outside.z, player.yaw, player.pitch)
        }
    }

    /** Dimensionswechsel: ausscheiden ohne Rückversetzung (die Position gehört schon zur neuen Welt). */
    fun onPlayerChangedWorld(player: ServerPlayerEntity) {
        if (!participants.contains(player.uuid)) return
        drop(player)
        Cine.depart(player, this)
        skip.recheck(players())
    }

    private fun drop(player: ServerPlayerEntity) {
        participants.remove(player.uuid)
        val list = listOf(player)
        Cine.end(list)
        Cine.music(list, null)
        Cine.phaseBar(list, false)
    }

    private fun send(player: ServerPlayerEntity) {
        player.teleport(world, outside.x, outside.y, outside.z, emptySet(), player.yaw, player.pitch, false)
        player.velocity = Vec3d.ZERO
        player.velocityDirty = true
        player.fallDistance = 0.0
    }

    fun tick() {
        if (ended) return
        // Hades färbt alles grün; Kronos bringt sein eigenes Äon mit. Gold bleibt immer Gold („gut für dich“).
        Vfx.styleOverride = if (stage >= 4) null else Vfx.Style.HADES
        Vfx.spareGold = true
        val calls = if (stage >= 4) kronosCalls else hadesCalls
        try {
            calls.during { step() }
        } finally {
            Vfx.styleOverride = null
            Vfx.spareGold = false
        }
    }

    /** Vom Netzwerk: ein Teilnehmer will die laufende Szene überspringen. */
    fun voteSkip(player: ServerPlayerEntity): Boolean {
        if (player.uuid !in participants) return false
        skip.vote(player, players())
        return true
    }

    private fun advanceScript(): Boolean {
        val running = script ?: return false
        if (!running.hasNext()) {
            script = null
            return false
        }
        running.next()
        return !ended
    }

    /** Die geteilte Leiste: drei Abschnitte für Hades, zwei für Kronos. */
    private fun updateBar() {
        val show = barVisible && !inCutscene
        val key: String
        if (!show) {
            key = "hidden"
            if (key != lastBar) Cine.phaseBar(players(), false)
        } else if (stage in 4..5) {
            val titanEntity = kronos ?: return
            val fraction = (titanEntity.health / titanEntity.maxHealth).coerceIn(0f, 1f)
            key = "k$stage:${(fraction * 400).toInt()}"
            if (key != lastBar) Cine.phaseBar(players(), true, 2, stage - 3, fraction, 1, "entity.hha.kronos", "hha.kronos.stage.${stage - 3}")
        } else {
            val entity = boss ?: return
            val fraction = (entity.health / entity.maxHealth).coerceIn(0f, 1f)
            key = "h$stage:${(fraction * 400).toInt()}"
            if (key != lastBar) Cine.phaseBar(players(), true, 3, stage.coerceIn(1, 3), fraction, 0, "entity.hha.hades", "hha.hades.stage.$stage")
        }
        lastBar = key
    }

    private fun step() {
        if (skip.pending) {
            skip.fastForward(players(), ::advanceScript)
            if (ended) return
        }
        updateBar()
        stageAge++
        if (openingFor > 0) openingFor--
        scale()
        if (!inCutscene) {
            when (stage) {
                in 1..3 -> attacks.tick()
                in 4..5 -> titan.tick()
            }
        }
        val iterator = tasks.iterator()
        while (iterator.hasNext()) {
            val task = iterator.next()
            if (task.hasNext()) task.next() else iterator.remove()
        }
        rescueFallers()

        if (transitionQueued && !inCutscene) {
            transitionQueued = false
            beginTransition()
        }
        val running = script
        if (running != null) {
            if (running.hasNext()) running.next() else script = null
        } else if (!inCutscene) {
            when (stage) {
                in 1..3 -> script = attacks.next().iterator()
                in 4..5 -> script = titan.next().iterator()
            }
        }
        if (targets().isEmpty()) {
            if (++noPlayersFor > 60) finish(false)
        } else {
            noPlayersFor = 0
        }
    }

    /** Wer in die Tiefe stürzt oder aus dem Saal entkommt, wird vom Haus zurückgeholt. Der Sturz kostet etwas. */
    private fun rescueFallers() {
        for (player in targets()) {
            val flat = Vec3d(player.x - center.x, 0.0, player.z - center.z)
            val fell = player.y < center.y - 4.5
            if (!fell && flat.length() <= TartarusPlans.SEAL_Z + 8.0) continue
            val spot = clampToHall(player.entityPos, 3.0)
            player.teleport(world, spot.x, spot.y + 0.2, spot.z, emptySet(), player.yaw, player.pitch, false)
            player.velocity = Vec3d.ZERO
            player.velocityDirty = true
            player.fallDistance = 0.0
            if (fell) {
                Vfx.nova(world, Vfx.Style.SOUL, spot.add(0.0, 1.0, 0.0), 3f, 10)
                sound(HhaSounds.SOUL, 2f, 0.7f, spot)
                hurt(player, 12f, magic = true)
                player.sendMessage(Text.translatable("hha.hades.hint.fall").formatted(Formatting.GREEN), true)
            }
        }
    }

    private fun clearField() {
        tasks.clear()
        closeScars()
        openingFor = 0
        poise = 0f
        boss?.bidentOut = false
        boss?.cloaked = false
        kronos?.harpeOut = false
        attacks.reset()
        titan.reset()
        hadesCalls.reset()
        kronosCalls.reset()
        val hall = net.minecraft.util.math.Box.of(center, 90.0, 70.0, 90.0)
        for (bolt in world.getEntitiesByClass(HermesBoltEntity::class.java, hall) { true }) bolt.discard()
    }

    private fun beginTransition() {
        inCutscene = true
        clearField()
        script = when (stage) {
            1 -> falseVictory()
            2 -> secondDeath()
            3 -> fatherRises()
            4 -> endOfTime()
            else -> titanBound()
        }.iterator()
    }

    private fun startStage(number: Int) {
        stage = number
        stageAge = 0
        if (number <= 3) boss?.stage = number
        inCutscene = false
        barVisible = true
        lastBar = ""
        poise = 0f
        attacks.reset()
        titan.reset()
        hadesCalls.reset()
        kronosCalls.reset()
        // Jede Hades-Phase beginnt mit allen acht Säulen: Sie sind seine Mechanik, kein Vorrat.
        if (number in 2..3) restorePillars()
        if (number <= 3) attacks.onStageStart(number) else titan.onStageStart(number)
    }

    /** Stellt eingestürzte Deckungssäulen wieder her (nur ihre Blöcke über dem Boden). */
    private fun restorePillars() {
        val reach = TartarusPlans.PILLAR_RADIUS + 1
        val all = TartarusPlans.coverPillars()
        for (pillar in all) {
            if (pillar in intactPillars) continue
            val iterator = edits.entries.iterator()
            while (iterator.hasNext()) {
                val (pos, state) = iterator.next()
                if (pos.y <= floor.y) continue
                if (abs(pos.x - floor.x - pillar.first) > reach || abs(pos.z - floor.z - pillar.second) > reach) continue
                world.setBlockState(pos, state, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
                iterator.remove()
            }
        }
        intactPillars.clear()
        intactPillars.addAll(all)
    }

    /**
     * Eine fliegende Waffe oder ein fallendes Geschoss, das der Kampf selbst lenkt (Bident, Harpe, Feuerball, Zeitsand):
     * ohne eigene Bewegung und ohne eigene Treffer. Den Schaden prüft die Ansage; [steer] setzt Ort und Richtung.
     */
    fun puppet(kind: Int, at: Vec3d, size: Float = 1f): HermesBoltEntity? {
        val bolt = HhaEntities.HERMES_BOLT.create(world, SpawnReason.EVENT) ?: return null
        bolt.kind = kind
        bolt.size = size
        bolt.damage = 0f
        bolt.fall = 0.0
        bolt.homing = 0.0
        bolt.setOwner(if (stage >= 4) kronos else boss)
        bolt.setPosition(at)
        bolt.velocity = Vec3d.ZERO
        world.spawnEntity(bolt)
        return bolt
    }

    /**
     * Setzt ein [puppet]-Geschoss an [at] und dreht es in Richtung [facing]. Die Geschwindigkeit bleibt null, damit das
     * Geschoss weder selbst weiterfliegt noch an Spielern oder Säulen zerplatzt; der Renderer liest die Richtung dann aus
     * Gier- und Nickwinkel.
     */
    fun steer(bolt: HermesBoltEntity, at: Vec3d, facing: Vec3d) {
        bolt.velocity = Vec3d.ZERO
        bolt.setPosition(at)
        if (facing.lengthSquared() < 1e-8) return
        val d = facing.normalize()
        bolt.yaw = Math.toDegrees(atan2(d.x, -d.z)).toFloat()
        bolt.pitch = Math.toDegrees(asin(d.y.coerceIn(-1.0, 1.0))).toFloat()
    }

    fun finish(success: Boolean) {
        if (ended) return
        ended = true
        // Ein einziger Aufräumweg für Sieg, Niederlage, Verlassen und Server-Stopp: laufende Abläufe beenden, beide
        // Angriffs-Steuerungen zurücksetzen (Sanduhren, Requisiten, gelenkte Waffen) und alle Ansagen freigeben.
        script = null
        clearField()
        val everyone = players()
        Cine.end(everyone)
        // Abbruch (Niederlage, Verlassen, Server-Stopp) kann mitten in einer Abblende kommen: alles zurücksetzen.
        if (!success) Cine.reset(everyone)
        Cine.music(everyone, null)
        Cine.phaseBar(everyone, false)
        hermes?.discard()
        boss?.discard()
        kronos?.discard()
        for (player in everyone) send(player)
        restoreEdits()
        // Nach dem Sieg bleibt der Weg frei; sonst bleibt das Siegel, und man kann es erneut versuchen.
        if (success) sealOpen(true)
        Cine.encounterEnded(this)
        HadesFights.onFinished(this)
        Hha.LOGGER.info("Hades-Kampf beendet (Erfolg: {})", success)
    }

    private fun sealOpen(permanently: Boolean) {
        val half = TartarusPlans.SEAL_HALF_WIDTH
        for (dx in -half..half) {
            for (dy in 1..TartarusPlans.SEAL_HEIGHT) {
                val pos = floor.add(dx, dy, TartarusPlans.SEAL_Z)
                if (!TartarusPlans.inSealOpening(dx, dy)) continue
                if (permanently) world.setBlockState(pos, Blocks.AIR.defaultState, Block.NOTIFY_LISTENERS or Block.FORCE_STATE)
                else edit(pos, Blocks.AIR.defaultState)
            }
        }
    }

    private fun sealShut() {
        val half = TartarusPlans.SEAL_HALF_WIDTH
        for (dx in -half..half) {
            for (dy in 1..TartarusPlans.SEAL_HEIGHT) {
                if (TartarusPlans.inSealOpening(dx, dy)) edit(floor.add(dx, dy, TartarusPlans.SEAL_Z), dev.henny.hha.HhaBlocks.HADES_SEAL.defaultState)
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Szenen
    // -------------------------------------------------------------------------------------------------

    private suspend fun SequenceScope<Unit>.wait(ticks: Int) {
        repeat(ticks) { yield(Unit) }
    }

    private suspend fun SequenceScope<Unit>.glide(to: Vec3d, ticks: Int) {
        val entity = boss ?: return
        val from = entity.entityPos
        for (i in 1..ticks) {
            val raw = i.toFloat() / ticks
            entity.setPosition(from.lerp(to, (raw * raw * (3f - 2f * raw)).toDouble()))
            face(to)
            yield(Unit)
        }
    }

    private suspend fun SequenceScope<Unit>.say(speaker: Int, key: String, ticks: Int) {
        val full = "hha.hermes.line.$key"
        val length = HhaVoiceLengths.ticks(full, ticks)
        Cine.dialog(players(), speaker, full, length)
        wait(length + 4)
    }

    private fun gather() {
        val list = players()
        for ((i, player) in list.withIndex()) {
            val spread = (i - (list.size - 1) / 2.0) * 2.5
            val pos = center.add(spread, 0.0, 15.0)
            player.teleport(world, pos.x, pos.y + 0.2, pos.z, emptySet(), 180f, 0f, false)
            player.velocity = Vec3d.ZERO
            player.velocityDirty = true
            player.fallDistance = 0.0
            player.extinguish()
        }
    }

    /** Er sitzt auf dem Podest; die Sitzfläche liegt einen Block höher, und die Füße stehen auf der obersten Stufe. */
    private val throneSeat: Vec3d get() = center.add(0.0, TartarusPlans.DAIS_HEIGHT.toDouble(), TartarusPlans.THRONE_Z.toDouble())

    private fun gatherPoint(): Vec3d = center.add(0.0, 0.0, 15.0)

    fun spawnGhostHermes(pos: Vec3d): HermesEntity {
        val ghost = HhaEntities.HERMES.create(world, SpawnReason.EVENT)!!
        ghost.isIllusion = true
        ghost.guide = true
        ghost.corrupted = true
        ghost.stage = 3
        ghost.setPosition(pos)
        world.spawnEntity(ghost)
        Vfx.flare(world, Vfx.Style.HADES, pos.add(0.0, 1.5, 0.0), 3f, 10)
        sound(HhaSounds.BLINK, 2f, 0.8f, pos)
        hermes = ghost
        return ghost
    }

    fun faceGhost(ghost: HermesEntity, target: Vec3d) {
        val d = target.subtract(ghost.entityPos)
        val yaw = (Math.toDegrees(atan2(d.z, d.x)) - 90.0).toFloat()
        ghost.yaw = yaw
        ghost.bodyYaw = yaw
        ghost.headYaw = yaw
    }

    private fun intro(): Sequence<Unit> = sequence {
        val everyone = players()
        Cine.start(everyone)
        Cine.fade(everyone, 0x000000, 1.0, 0.0, 36)
        Cine.music(everyone, null)
        gather()
        sound(HhaSounds.IMPACT, 3f, 0.5f)

        val entity = HhaEntities.HADES.create(world, SpawnReason.EVENT)!!
        entity.setPosition(throneSeat)
        entity.yaw = 0f
        entity.pose = HadesPose.SIT
        world.spawnEntity(entity)
        boss = entity
        entity.health = entity.maxHealth

        // Hermes kniet neben dem Thron und möchte gerade sehr woanders sein.
        val messenger = HhaEntities.HERMES.create(world, SpawnReason.EVENT)!!
        messenger.isIllusion = true
        messenger.guide = true
        messenger.stage = 3
        messenger.pose = HermesPose.KNEEL
        messenger.setPosition(center.add(5.0, 0.2, TartarusPlans.THRONE_Z + 9.0))
        messenger.yaw = 0f
        messenger.bodyYaw = 0f
        messenger.headYaw = 0f
        world.spawnEntity(messenger)
        hermes = messenger

        // Lange Fahrt durch den Saal auf den Thron zu: Erst hier zeigt sich, wie groß das Haus ist.
        Cine.camera(everyone, center.add(0.0, 9.0, 24.0), center.add(0.0, 4.0, 2.0), throneSeat.add(0.0, 3.0, 0.0), throneSeat.add(0.0, 2.4, 0.0), 130, 70.0, 46.0)
        wait(14)
        say(Cine.SPEAKER_NARRATOR, "hades.enter", 70)
        Cine.camera(everyone, throneSeat.add(-3.5, 1.6, 8.0), throneSeat.add(-1.8, 2.2, 5.6), throneSeat.add(0.0, 2.6, 0.0), throneSeat.add(0.0, 2.8, 0.0), 100, 44.0, 38.0)
        say(Cine.SPEAKER_HADES, "hades.host", 86)
        Cine.camera(everyone, messenger.entityPos.add(-2.4, 1.2, 3.2), messenger.entityPos.add(-1.8, 1.4, 2.4), messenger.entityPos.add(0.0, 1.2, 0.0), messenger.entityPos.add(0.0, 1.2, 0.0), 70, 44.0, 38.0)
        say(Cine.SPEAKER_HERMES, "hades.excuse", 70)
        Cine.camera(everyone, throneSeat.add(2.2, 1.6, 7.0), throneSeat.add(1.2, 2.0, 5.0), throneSeat.add(0.0, 2.6, 0.0), throneSeat.add(0.0, 2.6, 0.0), 70, 44.0, 38.0)
        say(Cine.SPEAKER_HADES, "hades.dismiss", 58)
        Vfx.feathers(world, Vfx.Style.SHADOW, messenger.entityPos.add(0.0, 1.0, 0.0), 24, 1.4f, 60)
        sound(HhaSounds.BLINK, 2f, 0.7f, messenger.entityPos)
        messenger.discard()
        hermes = null

        // Er steht auf, und der Bident schlägt auf.
        entity.pose = HadesPose.CAST
        Cine.camera(everyone, center.add(9.0, 2.6, -9.0), center.add(6.0, 1.6, -6.0), throneSeat.add(0.0, 3.0, 0.0), center.add(0.0, 3.0, -8.0), 86, 66.0, 56.0)
        say(Cine.SPEAKER_HADES, "hades.rise", 54)
        glide(center.add(0.0, 0.0, -8.0), 28)
        entity.pose = HadesPose.SLAM
        wait(BossTimings.Hades.SLAM_WINDUP)
        Vfx.shockwave(world, Vfx.Style.SOUL, center.add(0.0, 0.0, -6.0), 26f, 26, 5f)
        Vfx.shake(world, center, 8f, 24, 60f)
        sound(HhaSounds.IMPACT, 4f, 0.6f)
        entity.pose = HadesPose.ROAR
        sound(HhaSounds.ROAR, 3.5f, 0.45f)
        Cine.camera(everyone, entity.entityPos.add(-2.5, 0.6, 9.0), entity.entityPos.add(-2.0, 1.0, 7.0), entity.entityPos.add(0.0, 3.0, 0.0), entity.entityPos.add(0.0, 3.2, 0.0), 56, 58.0, 50.0)
        Cine.title(everyone, "hha.hades.stage.1", "hha.hades.stage.1.sub", 56)
        sound(HhaSounds.STING, 2.5f, 0.7f)
        wait(34)
        Cine.end(players())
        Cine.music(players(), HhaSounds.THEME_HADES)
        entity.pose = HadesPose.IDLE
        startStage(1)
    }

    /**
     * Der Köder: Er fällt, der Sieg wird verkündet, die Musik schweigt, das Tor steht offen und eine Truhe wartet. Kein
     * Letterbox, keine Leiste, die Spieler können sich frei bewegen. Wer die Truhe anfasst oder zu lange wartet, erfährt es.
     */
    private fun falseVictory(): Sequence<Unit> = sequence {
        val entity = boss ?: return@sequence
        val everyone = players()
        barVisible = false
        Cine.phaseBar(everyone, false)
        lastBar = "hidden"
        Cine.music(everyone, null)
        Cine.clearVfx(everyone)
        entity.pose = HadesPose.KNEEL
        Vfx.nova(world, Vfx.Style.SOUL, entity.entityPos.add(0.0, 1.5, 0.0), 7f, 16)
        sound(HhaSounds.IMPACT, 3.5f, 0.7f, entity.entityPos)
        wait(26)
        entity.pose = HadesPose.FALLEN
        Vfx.shockwave(world, Vfx.Style.SOUL, entity.entityPos, 8f, 18, 2f)
        sound(HhaSounds.IMPACT, 2.5f, 0.5f, entity.entityPos)
        wait(20)
        Cine.title(everyone, "hha.hades.title.fake", "hha.hades.title.fake.sub", 90)
        sound(HhaSounds.STING, 2.5f, 1.1f)
        sealOpen(false)
        sound(HhaSounds.RIFT, 2.5f, 1.4f, center.add(0.0, 3.0, TartarusPlans.SEAL_Z.toDouble()))
        val chestPos = floor.add(0, 1, 8)
        scar(chestPos, Blocks.CHEST.defaultState)
        Vfx.godRays(world, Vfx.Style.GOLD, Vec3d.ofBottomCenter(chestPos), 3f, 30f, 240)

        var waited = 0
        while (waited < 20 * 12) {
            waited++
            // Die Truhe anfassen oder durchs Tor gehen wollen: Beides beendet die Hoffnung.
            val tempted = targets().any { it.squaredDistanceTo(Vec3d.ofCenter(chestPos)) < 12.0 || it.z - center.z > TartarusPlans.SEAL_Z + 2.0 }
            if (tempted && waited > 20) break
            yield(Unit)
        }

        // Der Deckel klemmt. Unter dem Boden lacht jemand.
        closeScars()
        sealShut()
        val again = players()
        Cine.start(again)
        gather()
        Cine.fade(again, 0x000000, 1.0, 0.0, 10)
        sound(HhaSounds.IMPACT, 4f, 0.4f, Vec3d.ofCenter(chestPos))
        Vfx.nova(world, Vfx.Style.SOUL, Vec3d.ofCenter(chestPos), 8f, 14)
        Cine.camera(again, entity.entityPos.add(3.0, 1.2, 7.0), entity.entityPos.add(1.5, 1.4, 5.0), entity.entityPos.add(0.0, 0.8, 0.0), entity.entityPos.add(0.0, 2.8, 0.0), 120, 56.0, 44.0)
        wait(10)
        say(Cine.SPEAKER_HADES, "hades.bait", 80)

        // Der Boden färbt sich, und er steht auf. Größer als vorher, mit den ersten Schwingen im Rücken.
        entity.pose = HadesPose.ROAR
        entity.form = 1
        entity.health = entity.maxHealth
        repaint()
        Vfx.pillar(world, Vfx.Style.SOUL, entity.entityPos, 4f, 50f, 50)
        Vfx.shockwave(world, Vfx.Style.SOUL, entity.entityPos, 26f, 34, 7f)
        Vfx.shake(world, center, 10f, 40, 60f)
        sound(HhaSounds.ROAR, 4f, 0.5f)
        sound(HhaSounds.THUNDER, 4f, 0.5f)
        wait(16)
        Cine.camera(again, entity.entityPos.add(2.0, 2.0, 8.0), entity.entityPos.add(1.0, 2.6, 6.5), entity.entityPos.add(0.0, 3.4, 0.0), entity.entityPos.add(0.0, 3.4, 0.0), 100, 50.0, 42.0)
        say(Cine.SPEAKER_HADES, "hades.gates", 80)
        // Die Pforten reißen schon in der Szene auf: Ab jetzt greift der Saal mit an. Sie selbst sind nur Kulisse.
        Cine.orbit(again, center, 24.0, 9.0, 90.0, 200.0, 90, 64.0, 58.0, entity, 3.0)
        attacks.openGates()
        wait(50)
        Cine.title(again, "hha.hades.stage.2", "hha.hades.stage.2.sub", 60)
        sound(HhaSounds.STING, 3f, 0.7f)
        wait(30)
        Cine.end(players())
        Cine.music(players(), HhaSounds.THEME_HADES)
        entity.pose = HadesPose.IDLE
        startStage(2)
    }

    /** Boden und Säulen des Saals werden dunkler und glühen, jetzt ist er wirklich zu Hause. */
    private fun repaint() {
        for (dx in -ARENA..ARENA) {
            for (dz in -ARENA..ARENA) {
                val r = Vec3d(dx.toDouble(), 0.0, dz.toDouble()).length()
                if (r > ARENA - 1.0) continue
                val pos = floor.add(dx, 0, dz)
                val state = when {
                    r in 10.0..11.0 || r in 21.0..22.0 || r in 32.0..33.0 -> Blocks.VERDANT_FROGLIGHT
                    (dx * dx + dz * dz) % 7 == 0 -> Blocks.CRYING_OBSIDIAN
                    else -> Blocks.OBSIDIAN
                }
                edit(pos, state.defaultState)
            }
        }
    }

    /** Der zweite Köder: Er fällt erneut, und Hermes kommt mit dem Lieferschein. Dann hebt sich eine Hand. */
    private fun secondDeath(): Sequence<Unit> = sequence {
        val entity = boss ?: return@sequence
        val everyone = players()
        barVisible = false
        Cine.music(everyone, null)
        Cine.clearVfx(everyone)
        Cine.start(everyone)
        gather()
        entity.setPosition(center.add(0.0, 0.0, -6.0))
        entity.yaw = 0f
        entity.pose = HadesPose.FALLEN
        Cine.fade(everyone, 0x000000, 1.0, 0.0, 26)
        Cine.title(everyone, "hha.hades.title.fake", "hha.hades.title.fake2.sub", 70)
        sound(HhaSounds.STING, 2.5f, 1.1f)
        Cine.camera(everyone, center.add(8.0, 3.0, 4.0), center.add(5.0, 2.0, 1.0), entity.entityPos, entity.entityPos, 100, 56.0, 48.0)
        wait(70)

        val messenger = spawnGhostHermes(center.add(4.0, 0.2, 7.0))
        messenger.corrupted = false
        messenger.stage = 1
        messenger.pose = HermesPose.READ
        faceGhost(messenger, gatherPoint())
        Cine.camera(everyone, messenger.entityPos.add(2.4, 1.6, -3.0), messenger.entityPos.add(1.6, 1.8, -2.2), messenger.entityPos.add(0.0, 1.5, 0.0), messenger.entityPos.add(0.0, 1.5, 0.0), 80, 44.0, 38.0)
        say(Cine.SPEAKER_HERMES, "hades.sign", 56)

        // Hinter ihm regt sich etwas. Er merkt es als Erster.
        Vfx.shake(world, center, 4f, 30, 60f)
        sound(HhaSounds.ROAR, 3.5f, 0.45f)
        faceGhost(messenger, entity.entityPos)
        messenger.pose = HermesPose.STAGGER
        wait(20)
        entity.pose = HadesPose.ROAR
        entity.form = 2
        entity.health = entity.maxHealth
        Vfx.pillar(world, Vfx.Style.SOUL, entity.entityPos, 5f, 60f, 60)
        Vfx.shockwave(world, Vfx.Style.SOUL, entity.entityPos, 26f, 34, 8f)
        Vfx.shake(world, center, 12f, 40, 60f)
        sound(HhaSounds.THUNDER, 4f, 0.45f)
        Cine.camera(everyone, entity.entityPos.add(2.0, 2.0, 11.0), entity.entityPos.add(1.0, 3.0, 8.5), entity.entityPos.add(0.0, 3.6, 0.0), entity.entityPos.add(0.0, 4.2, 0.0), 80, 54.0, 42.0)
        say(Cine.SPEAKER_HADES, "hades.bait2", 66)
        say(Cine.SPEAKER_HADES, "hades.wealth", 90)

        // Das Angebot: Hermes kniet, wird grün und steht als Geist wieder auf.
        messenger.pose = HermesPose.KNEEL
        Vfx.chain(world, Vfx.Style.SOUL, entity, messenger.entityPos, messenger, 50)
        sound(HhaSounds.CHAIN, 3f, 0.8f, messenger.entityPos)
        wait(30)
        messenger.corrupted = true
        messenger.stage = 3
        Vfx.nova(world, Vfx.Style.SOUL, messenger.entityPos.add(0.0, 1.5, 0.0), 5f, 14)
        sound(HhaSounds.SOUL, 3f, 0.7f, messenger.entityPos)
        messenger.pose = HermesPose.TAUNT
        faceGhost(messenger, gatherPoint())
        Cine.camera(everyone, messenger.entityPos.add(2.0, 1.6, 3.0), messenger.entityPos.add(1.4, 1.8, 2.2), messenger.entityPos.add(0.0, 1.6, 0.0), messenger.entityPos.add(0.0, 1.6, 0.0), 70, 44.0, 38.0)
        say(Cine.SPEAKER_SHADE, "hades.pact", 72)
        Cine.orbit(everyone, center, 18.0, 4.0, 60.0, 110.0, 80, 62.0, 56.0, entity, 3.2)
        say(Cine.SPEAKER_HADES, "hades.together", 60)
        Cine.title(everyone, "hha.hades.stage.3", "hha.hades.stage.3.sub", 60)
        sound(HhaSounds.STING, 3f, 0.6f)
        wait(20)
        Cine.end(players())
        Cine.music(players(), HhaSounds.THEME_HADES)
        entity.pose = HadesPose.IDLE
        messenger.pose = HermesPose.IDLE
        startStage(3)
    }

    /** Wo das Auge der Kuppel sitzt: Dort bricht der Titan durch. */
    private val oculus: Vec3d get() = center.add(0.0, TartarusPlans.OCULUS_Y + 1.0, 0.0)

    /** Reißt das Auge der Kuppel und den Kronleuchter darunter weg. Alles kommt am Ende zurück. */
    private fun breakDome() {
        val lamp = TartarusPlans.CHANDELIER_Y
        val eye = TartarusPlans.OCULUS_Y
        for (dx in -14..14) {
            for (dz in -14..14) {
                val r = hypot(dx.toDouble(), dz.toDouble())
                for (dy in lamp - 6..eye + 18) {
                    val inChandelier = dy in lamp - 6..lamp + 4 && r < 13.5
                    val inEye = dy > lamp + 4 && r < TartarusPlans.EYE + 4.5 - abs(dy - (eye + 2)) * 0.05
                    if (!inChandelier && !inEye) continue
                    val pos = floor.add(dx, dy, dz)
                    if (!world.getBlockState(pos).isAir) edit(pos, Blocks.AIR.defaultState)
                }
            }
        }
    }

    /**
     * Hermes flieht, Hades gibt auf, und genau das lässt den Tartaros reißen: Der Vater, den er einst hier unten
     * angekettet hat, bricht durch die Kuppel.
     */
    private fun fatherRises(): Sequence<Unit> = sequence {
        val everyone = players()
        val entity = boss ?: return@sequence
        barVisible = false
        Cine.clearVfx(everyone)
        Cine.start(everyone)
        Cine.music(everyone, null)
        gather()
        stage = 4
        entity.setPosition(center.add(0.0, 0.0, -6.0))
        entity.yaw = 0f
        entity.pose = HadesPose.KNEEL
        sound(HhaSounds.IMPACT, 3f, 0.7f)
        Vfx.nova(world, Vfx.Style.SOUL, entity.entityPos.add(0.0, 1.0, 0.0), 8f, 16)

        val messenger = hermes?.takeIf { !it.isRemoved } ?: spawnGhostHermes(center.add(5.0, 0.2, -2.0))
        messenger.corrupted = false
        messenger.stage = 1
        messenger.pose = HermesPose.TAUNT
        faceGhost(messenger, gatherPoint())
        messenger.setPosition(center.add(5.0, 0.2, -2.0))
        Cine.camera(everyone, messenger.entityPos.add(2.0, 1.6, 3.0), messenger.entityPos.add(1.4, 1.8, 2.2), messenger.entityPos.add(0.0, 1.6, 0.0), messenger.entityPos.add(0.0, 1.6, 0.0), 80, 44.0, 38.0)
        wait(10)
        say(Cine.SPEAKER_HERMES, "hades.never", 60)
        Vfx.feathers(world, Vfx.Style.HEAVEN, messenger.entityPos.add(0.0, 1.0, 0.0), 30, 1.8f, 60)
        sound(HhaSounds.DASH, 2.5f, 1.2f, messenger.entityPos)
        messenger.discard()
        hermes = null

        Cine.camera(everyone, entity.entityPos.add(2.0, 1.8, 7.5), entity.entityPos.add(1.0, 2.0, 5.6), entity.entityPos.add(0.0, 2.6, 0.0), entity.entityPos.add(0.0, 2.6, 0.0), 120, 46.0, 40.0)
        say(Cine.SPEAKER_HADES, "hades.enough", 74)

        // Er hat den Namen gesagt. Die Tiefe antwortet.
        Vfx.styleOverride = null
        Vfx.shake(world, center, 5f, 90, 80f)
        sound(HhaSounds.THUNDER, 4f, 0.35f)
        sound(HhaSounds.RIFT, 4f, 0.4f)
        Cine.camera(everyone, center.add(0.0, 2.0, 16.0), center.add(0.0, 3.0, 14.0), center.add(0.0, 10.0, 0.0), oculus, 90, 70.0, 62.0)
        say(Cine.SPEAKER_KRONOS, "kronos.wake", 70)
        entity.pose = HadesPose.STAGGER
        healHall()
        Vfx.godRays(world, Vfx.Style.AEON, oculus.add(0.0, -48.0, 0.0), 10f, 70f, 140)
        Vfx.flare(world, Vfx.Style.AEON, oculus, 18f, 30)
        Vfx.shake(world, center, 14f, 30, 90f)
        sound(HhaSounds.IMPACT, 4f, 0.3f)
        sound(HhaSounds.THUNDER, 4f, 0.5f, oculus)
        breakDome()
        Vfx.nova(world, Vfx.Style.AEON, oculus, 22f, 20)
        // Trümmer der Kuppel: nur Bild, kein Schaden (die Szene läuft).
        for (i in 0 until 10) {
            val spot = center.add((world.random.nextDouble() - 0.5) * 30.0, 0.0, (world.random.nextDouble() - 0.5) * 30.0)
            Vfx.rune(world, Vfx.Style.AEON, spot.add(0.0, 0.06, 0.0), 2.4f, 30)
            Vfx.pillar(world, Vfx.Style.AEON, spot, 1.2f, 40f, 30)
        }

        // Der Titan sinkt herab, die Arme ausgebreitet, der Uhrenkranz dreht sich hinter ihm.
        val titanEntity = HhaEntities.KRONOS.create(world, SpawnReason.EVENT)!!
        titanEntity.setPosition(oculus.add(0.0, 14.0, 0.0))
        titanEntity.yaw = 0f
        titanEntity.pose = KronosPose.DESCEND
        world.spawnEntity(titanEntity)
        titanEntity.health = titanEntity.maxHealth
        kronos = titanEntity
        val land = center.add(0.0, 0.0, -2.0)
        Cine.camera(everyone, center.add(-10.0, 1.5, 18.0), center.add(-6.0, 2.0, 13.0), oculus, land.add(0.0, 4.0, 0.0), 120, 72.0, 54.0, titanEntity, 4.0)
        val top = titanEntity.entityPos
        for (i in 1..110) {
            val k = i / 110.0
            val eased = 1.0 - (1.0 - k) * (1.0 - k)
            titanEntity.setPosition(top.lerp(land, eased))
            titanEntity.yaw = (1.0 - eased).toFloat() * 180f
            if (i % 8 == 0) Vfx.nova(world, Vfx.Style.AEON, titanEntity.entityPos.add(0.0, 3.0, 0.0), 4f, 10)
            if (i % 20 == 0) sound(HhaSounds.CHIME, 3f, 0.4f + i * 0.004f, titanEntity.entityPos)
            yield(Unit)
        }
        titanEntity.yaw = 0f
        titanEntity.pose = KronosPose.SLAM
        wait(BossTimings.Kronos.SLAM_WINDUP)
        Vfx.shockwave(world, Vfx.Style.AEON, land, 30f, 30, 7f)
        Vfx.shake(world, center, 16f, 36, 80f)
        sound(HhaSounds.IMPACT, 4f, 0.35f)
        sound(HhaSounds.THUNDER, 4f, 0.4f)
        // Hades wird zum Thron geschleudert und bleibt dort auf den Knien.
        entity.setPosition(throneSeat.add(0.0, -0.2, 2.0))
        entity.yaw = 0f
        entity.pose = HadesPose.KNEEL
        Vfx.nova(world, Vfx.Style.SOUL, entity.entityPos.add(0.0, 1.0, 0.0), 5f, 12)

        titanEntity.pose = KronosPose.IDLE
        Cine.camera(everyone, titanEntity.entityPos.add(-4.0, 3.0, 13.0), titanEntity.entityPos.add(-2.5, 4.5, 10.5), titanEntity.entityPos.add(0.0, 5.0, 0.0), titanEntity.entityPos.add(0.0, 5.4, 0.0), 120, 58.0, 48.0)
        say(Cine.SPEAKER_KRONOS, "kronos.son", 80)
        Cine.camera(everyone, entity.entityPos.add(-2.0, 1.4, 5.0), entity.entityPos.add(-1.4, 1.8, 4.0), entity.entityPos.add(0.0, 2.0, 0.0), entity.entityPos.add(0.0, 2.0, 0.0), 60, 46.0, 40.0)
        say(Cine.SPEAKER_HADES, "hades.father", 40)
        titanEntity.pose = KronosPose.ROAR
        Cine.camera(everyone, titanEntity.entityPos.add(0.0, 2.0, 14.0), titanEntity.entityPos.add(0.0, 3.0, 11.0), titanEntity.entityPos.add(0.0, 5.5, 0.0), titanEntity.entityPos.add(0.0, 6.0, 0.0), 110, 60.0, 50.0)
        Vfx.shockwave(world, Vfx.Style.AEON, titanEntity.entityPos, 20f, 24, 5f)
        sound(HhaSounds.ROAR, 4f, 0.3f)
        say(Cine.SPEAKER_KRONOS, "kronos.devour", 80)
        Cine.title(everyone, "hha.kronos.stage.1", "hha.kronos.stage.1.sub", 70)
        sound(HhaSounds.STING, 3f, 0.5f)
        wait(30)
        Cine.end(players())
        Cine.music(players(), HhaSounds.THEME_HADES)
        titanEntity.pose = KronosPose.IDLE
        startStage(4)
    }

    /** Die zweite Gestalt des Titanen, und Hades steht auf: Diesmal kämpft er an deiner Seite. */
    private fun endOfTime(): Sequence<Unit> = sequence {
        val everyone = players()
        val titanEntity = kronos ?: return@sequence
        val ally = boss
        barVisible = false
        Cine.clearVfx(everyone)
        Cine.start(everyone)
        Cine.music(everyone, null)
        gather()
        titanEntity.setPosition(center.add(0.0, 0.0, -4.0))
        titanEntity.yaw = 0f
        titanEntity.pose = KronosPose.STAGGER
        Vfx.nova(world, Vfx.Style.AEON, titanEntity.entityPos.add(0.0, 3.0, 0.0), 10f, 18)
        sound(HhaSounds.IMPACT, 4f, 0.4f)
        Cine.camera(everyone, titanEntity.entityPos.add(6.0, 2.0, 12.0), titanEntity.entityPos.add(4.0, 3.0, 10.0), titanEntity.entityPos.add(0.0, 4.0, 0.0), titanEntity.entityPos.add(0.0, 5.0, 0.0), 100, 56.0, 50.0)
        wait(30)
        titanEntity.pose = KronosPose.CAST
        titanEntity.form = 1
        titanEntity.health = titanEntity.maxHealth
        Vfx.pillar(world, Vfx.Style.AEON, titanEntity.entityPos, 6f, 70f, 60)
        Vfx.shockwave(world, Vfx.Style.AEON, titanEntity.entityPos, 30f, 34, 8f)
        Vfx.shake(world, center, 14f, 40, 80f)
        sound(HhaSounds.THUNDER, 4f, 0.35f)
        say(Cine.SPEAKER_KRONOS, "kronos.shatter", 90)

        // Hades erhebt sich hinter ihm.
        if (ally != null) {
            ally.pose = HadesPose.ROAR
            ally.setPosition(center.add(-10.0, 0.0, 6.0))
            face(titanEntity.entityPos)
            Vfx.pillar(world, Vfx.Style.SOUL, ally.entityPos, 3f, 30f, 40)
            Vfx.nova(world, Vfx.Style.SOUL, ally.entityPos.add(0.0, 2.0, 0.0), 6f, 14)
            sound(HhaSounds.ROAR, 3.5f, 0.5f, ally.entityPos)
            Cine.camera(everyone, ally.entityPos.add(-3.0, 1.8, 6.0), ally.entityPos.add(-2.0, 2.4, 4.5), ally.entityPos.add(0.0, 3.0, 0.0), ally.entityPos.add(0.0, 3.0, 0.0), 90, 50.0, 42.0)
            say(Cine.SPEAKER_HADES, "hades.ally", 90)
            ally.pose = HadesPose.IDLE
        }
        Cine.orbit(everyone, center, 20.0, 6.0, 120.0, 220.0, 80, 64.0, 58.0, titanEntity, 5.0)
        Cine.title(everyone, "hha.kronos.stage.2", "hha.kronos.stage.2.sub", 60)
        sound(HhaSounds.STING, 3f, 0.45f)
        wait(40)
        Cine.end(players())
        Cine.music(players(), HhaSounds.THEME_HADES)
        titanEntity.pose = KronosPose.IDLE
        startStage(5)
    }

    /** Der Titan fällt: Ketten aus dem Boden zerren ihn zurück in die Tiefe. Dann gibt Hades, was er versprochen hat. */
    private fun titanBound(): Sequence<Unit> = sequence {
        val everyone = players()
        val titanEntity = kronos ?: return@sequence
        val ally = boss
        stage = 6
        barVisible = false
        Cine.clearVfx(everyone)
        Cine.start(everyone)
        Cine.music(everyone, null)
        gather()
        titanEntity.setPosition(center.add(0.0, 0.0, -4.0))
        titanEntity.yaw = 0f
        titanEntity.pose = KronosPose.BOUND
        sound(HhaSounds.IMPACT, 4f, 0.35f)
        Vfx.shockwave(world, Vfx.Style.AEON, titanEntity.entityPos, 16f, 24, 4f)
        Cine.camera(everyone, titanEntity.entityPos.add(5.0, 2.0, 11.0), titanEntity.entityPos.add(3.0, 2.5, 9.0), titanEntity.entityPos.add(0.0, 3.0, 0.0), titanEntity.entityPos.add(0.0, 3.0, 0.0), 120, 56.0, 46.0)
        wait(20)
        say(Cine.SPEAKER_KRONOS, "kronos.fall", 80)

        if (ally != null) {
            ally.setPosition(center.add(-7.0, 0.0, 4.0))
            ally.pose = HadesPose.CAST
            face(titanEntity.entityPos)
            Cine.camera(everyone, ally.entityPos.add(-3.0, 1.6, 6.0), ally.entityPos.add(-2.0, 2.0, 4.5), ally.entityPos.add(0.0, 2.6, 0.0), titanEntity.entityPos.add(0.0, 3.0, 0.0), 90, 50.0, 46.0)
            say(Cine.SPEAKER_HADES, "hades.seal", 70)
        }
        // Acht Ketten schießen aus dem Boden und ziehen ihn hinab.
        for (i in 0 until 8) {
            val a = i * Math.PI / 4.0
            val anchor = titanEntity.entityPos.add(kotlin.math.cos(a) * 7.0, 0.0, kotlin.math.sin(a) * 7.0)
            Vfx.pillar(world, Vfx.Style.SOUL, anchor, 0.8f, 6f, 20)
        }
        sound(HhaSounds.CHAIN, 4f, 0.6f)
        Cine.camera(everyone, center.add(0.0, 8.0, 18.0), center.add(0.0, 5.0, 14.0), titanEntity.entityPos.add(0.0, 3.0, 0.0), titanEntity.entityPos.add(0.0, -2.0, 0.0), 110, 60.0, 54.0)
        val start = titanEntity.entityPos
        for (i in 1..100) {
            if (i % 10 == 1) {
                for (k in 0 until 8) {
                    val a = k * Math.PI / 4.0
                    Vfx.chain(world, Vfx.Style.SOUL, titanEntity, titanEntity.entityPos.add(kotlin.math.cos(a) * 7.0, -6.0, kotlin.math.sin(a) * 7.0), null, 12)
                }
            }
            if (i > 30) titanEntity.setPosition(start.add(0.0, -(i - 30) * 0.13, 0.0))
            if (i % 15 == 0) {
                Vfx.shake(world, center, 4f, 12, 60f)
                sound(HhaSounds.CHAIN, 3f, 0.5f + i * 0.004f)
            }
            yield(Unit)
        }
        Vfx.nova(world, Vfx.Style.GOLD, start.add(0.0, 1.0, 0.0), 14f, 20)
        Vfx.shockwave(world, Vfx.Style.SOUL, start, 20f, 26, 5f)
        sound(HhaSounds.THUNDER, 4f, 0.6f)
        titanEntity.discard()
        kronos = null
        wait(30)

        if (ally != null) {
            ally.pose = HadesPose.IDLE
            face(gatherPoint())
            Cine.camera(everyone, ally.entityPos.add(2.0, 1.8, 7.5), ally.entityPos.add(1.0, 2.0, 5.6), ally.entityPos.add(0.0, 2.6, 0.0), ally.entityPos.add(0.0, 2.6, 0.0), 140, 46.0, 40.0)
            say(Cine.SPEAKER_HADES, "hades.gift", 80)
        }
        grantRewards()
        Cine.title(everyone, "hha.kronos.title.end", "hha.kronos.title.end.sub", 80)
        sound(HhaSounds.STING, 2.5f, 0.85f)
        wait(80)
        Cine.fade(players(), 0x000000, 0.0, 1.0, 20)
        wait(22)
        val left = players()
        finish(success = true)
        Cine.fade(left, 0x000000, 1.0, 0.0, 30)
    }

    /** Die Rüstung der Hölle für den Sieg über Hades, und was ein Titan so bei sich trägt. */
    private fun grantRewards() {
        for (player in players()) {
            for (item in listOf(HhaItems.HELL_HELMET, HhaItems.HELL_CHESTPLATE, HhaItems.HELL_LEGGINGS, HhaItems.HELL_BOOTS)) give(player, ItemStack(item))
            give(player, ItemStack(HhaItems.HELL_INGOT, 8))
            give(player, ItemStack(Items.NETHER_STAR, 1))
            give(player, ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 2))
        }
    }

    private fun give(player: ServerPlayerEntity, stack: ItemStack) {
        if (!player.giveItemStack(stack)) player.dropItem(stack, false)
    }

    companion object {
        /** Radius des Kampffelds. */
        const val ARENA = 44

        /** Je größer, desto länger hält jede Phase. */
        private const val TOUGHNESS = 1.15f

        /** Kronos ist noch einmal zäher als sein Sohn. */
        private const val KRONOS_TOUGHNESS = 2.0f

        /** Offene Stellen sollen sich richtig lohnen. */
        private const val OPENING_BONUS = 1.8f

        /** So viel Schaden hält seine Haltung aus, bevor er taumelt. */
        private const val POISE_LIMIT = 190f
    }
}
