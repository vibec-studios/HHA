package dev.henny.hha.logic.boss

import dev.henny.hha.HhaSounds
import dev.henny.hha.Hha
import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaItems
import dev.henny.hha.config.HhaConfig
import dev.henny.hha.entity.boss.DivineParcelEntity
import dev.henny.hha.entity.boss.HermaEntity
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.entity.boss.HermesEntity
import dev.henny.hha.entity.boss.SerpentEntity
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.boss.ServerBossBar
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Der Bossfight „Die letzte Zustellung" (und der Schatten des Boten): hält Arena, Teilnehmer, Lebensbalken und die
 * laufenden Skripte.
 *
 * Attacken und Cutscenes sind Kotlin-Sequenzen, die pro Tick genau einen Schritt weiterlaufen ([script] plus
 * beliebig viele parallele [tasks]). Dadurch liest sich ein Ablauf von oben nach unten wie ein Drehbuch; die
 * Inhalte stehen in [HermesAttacks], [HermesCutscenes] und [ShadeCutscenes].
 *
 * Der ganze Kampf-Tick und alle Treffer-Rückmeldungen laufen im Scope von [telegraph] (Plan 3): Ansagen, Budget,
 * Mindestdauern und die Effekt-Regeln kommen von dort. Offene Fenster ([markOpen], goldene Rune) lassen Treffer
 * × [OPENING_BONUS] zählen.
 */
class HermesFight(
    val world: ServerWorld,
    val origin: Vec3d,
    summoner: ServerPlayerEntity,
    /** Wie der Kampf begann: Brief, Rätsel im Tempel oder Obolus in der Hades-Halle. */
    val mode: Mode = Mode.LETTER,
    hallCenter: BlockPos? = null,
) {

    enum class Mode { LETTER, TEMPLE, SHADE }

    val shade: Boolean get() = mode == Mode.SHADE

    val arena = if (hallCenter != null) {
        HermesArena(world, hallCenter, permanent = true)
    } else {
        HermesArena(
            world,
            BlockPos(
                MathHelper.floor(origin.x),
                HermesArena.chooseHeight(world, MathHelper.floor(origin.y)),
                MathHelper.floor(origin.z),
            ),
        )
    }
    val center: Vec3d get() = arena.centerPos()

    /**
     * Ansagen und Effekt-Budget dieses Kampfes. Gefahrenfarbe je Stage HEAVEN / HEAVEN / SOUL / STORM, beim Schatten
     * immer HADES (dazu färbt [Vfx.styleOverride] alles grün; Gold bleibt im Scope Gold).
     */
    val telegraph: Telegraph.Fight = Telegraph.open(world, if (shade) "shade" else "hermes", stageStyle(1)) { targets() }

    private val participants = LinkedHashSet<UUID>().apply {
        add(summoner.uuid)
        Cine.rejoin(summoner, this@HermesFight)
    }
    private val summonerId: UUID = summoner.uuid
    private val bossBar = ServerBossBar(Text.translatable("entity.hha.hermes"), BossBar.Color.YELLOW, BossBar.Style.NOTCHED_10)

    var boss: HermesEntity? = null
        private set
    val illusions = ArrayList<HermesEntity>()
    val serpents = ArrayList<SerpentEntity>()
    val stones = ArrayList<HermaEntity>()
    var parcel: DivineParcelEntity? = null

    /** 0 = Prolog, 1–4 = Stages, 5 = Finale. */
    var stage = 0
        private set
    var inCutscene = true
    var bossInvulnerable = true

    /** Solange das Forum nicht steht, stehen alle noch am Boden: dort gibt es nichts zu retten. */
    var arenaBuilt = false
    private var parcelOpened = false
    private var letterReturned = false
    private var ended = false

    private var script: Iterator<Unit>? = null
    private val tasks = ArrayList<Iterator<Unit>>()
    private val attacks = HermesAttacks(this)
    private val cutscenes = HermesCutscenes(this)
    private val shadeScenes = ShadeCutscenes(this)
    private val skip = CineSkip()

    /** Wie stark Hermes durch die Zahl der Mitspieler gerade skaliert ist. */
    var healthScale = 1f
        private set
    var damageScale = 1f
        private set
    private var scaledFor = 1
    private var hitsSinceBlock = 0
    private var transitionQueued = false
    private var noPlayersFor = 0
    private var ticks = 0

    /** Restzeit des offenen Fensters (goldene Rune unter ihm): Treffer zählen × [OPENING_BONUS]. */
    var openFor = 0
        private set

    /** Diebesgriff: gestohlene Effekte je Spieler, solange er die Beute-Kugel hält ([lootFor] Ticks). */
    private val loot = LinkedHashMap<UUID, MutableList<StatusEffectInstance>>()
    private var lootFor = 0

    /** Letzte Positionen je Spieler für den Vorhalt (Seelensäulen); Spieler-Geschwindigkeit ist auf dem Server unzuverlässig. */
    private val tracks = HashMap<UUID, ArrayDeque<Vec3d>>()

    /** Lebensgrenze jeder Stage. Die Zahlen bleiben fest, mehr Spieler machen Hermes über [healthScale] zäher. */
    private fun stageFloor(s: Int): Float = if (shade) {
        when (s) {
            2 -> 650f
            3 -> 300f
            else -> 1f
        }
    } else {
        when (s) {
            1 -> 700f
            2 -> 400f
            3 -> 150f
            else -> 1f
        }
    }

    /**
     * Passt Hermes an die aktuelle Spielerzahl an: Jeder weitere Spieler gibt ihm mehr Leben und mehr Schlagkraft
     * (`hermes_health_per_player`, `hermes_damage_per_player`).
     *
     * Die Leben wachsen nicht über das Attribut, denn Minecraft deckelt maximale Leben bei 1024. Stattdessen teilt
     * [healthScale] den eingehenden Schaden, was dieselbe Wirkung hat und mit jeder Spielerzahl funktioniert.
     */
    private fun updateScaling(force: Boolean = false) {
        val crowd = max(1, targets().size)
        if (!force && crowd == scaledFor) return
        val announce = !force && crowd > scaledFor
        scaledFor = crowd
        healthScale = 1f + HhaConfig.numF("hermes_health_per_player") * (crowd - 1)
        damageScale = 1f + HhaConfig.numF("hermes_damage_per_player") * (crowd - 1)
        for (stone in stones) scaleMinion(stone, HERMA_HEALTH)
        if (announce) for (player in players()) player.sendMessage(Text.translatable("hha.hermes.hint.crowd", crowd).formatted(Formatting.GOLD), true)
    }

    /** Auch die Herma-Steine halten bei mehr Spielern entsprechend mehr aus. */
    fun scaleMinion(entity: net.minecraft.entity.LivingEntity, base: Double) {
        val attribute = entity.getAttributeInstance(net.minecraft.entity.attribute.EntityAttributes.MAX_HEALTH) ?: return
        val fraction = entity.health / entity.maxHealth
        attribute.baseValue = (base * healthScale).coerceAtMost(1024.0)
        entity.health = (fraction * entity.maxHealth).coerceAtLeast(1f)
    }

    /** Boss-Schaden nach Konfiguration und Spielerzahl. */
    fun scaledDamage(base: Float): Float = base * HhaConfig.numF("hermes_damage") * damageScale

    fun begin() {
        // Alle in der Nähe des Briefs bzw. Obolus und alle, die schon in der Halle stehen, sehen die Cutscene mit.
        val hall = center
        for (player in world.players) {
            if (player.isSpectator) continue
            val nearOrigin = player.squaredDistanceTo(origin) <= PICKUP_RADIUS * PICKUP_RADIUS
            val nearHall = shade && player.squaredDistanceTo(hall) <= HALL_PICKUP_RADIUS * HALL_PICKUP_RADIUS
            if (nearOrigin || nearHall) {
                participants.add(player.uuid)
                Cine.rejoin(player, this)
            }
        }
        for (player in players()) bossBar.addPlayer(player)
        bossBar.isVisible = false
        script = (if (shade) shadeScenes.prologue() else cutscenes.prologue()).iterator()
    }

    fun players(): List<ServerPlayerEntity> = participants.mapNotNull { world.getPlayerByUuid(it) as? ServerPlayerEntity }.filter { it.isAlive }

    fun isParticipant(player: ServerPlayerEntity): Boolean = player.uuid in participants

    /** Spieler, die gerade kämpfen können. */
    fun targets(): List<ServerPlayerEntity> = players().filter { !it.isSpectator }

    fun randomTarget(): ServerPlayerEntity? = targets().let { if (it.isEmpty()) null else it[world.random.nextInt(it.size)] }

    fun randomHorizontal(radius: Double): Vec3d {
        val a = world.random.nextDouble() * Math.PI * 2.0
        return Vec3d(cos(a) * radius, 0.0, sin(a) * radius)
    }

    /** Gefahrenfarbe einer Stage (Plan 3.1). */
    fun stageStyle(number: Int): Vfx.Style = when {
        shade -> Vfx.Style.HADES
        number == 3 -> Vfx.Style.SOUL
        number >= 4 -> Vfx.Style.STORM
        else -> Vfx.Style.HEAVEN
    }

    /** Gefahrenfarbe der laufenden Stage. */
    val style: Vfx.Style get() = telegraph.style

    /**
     * Führt [block] im Kampf-Scope aus: Effekt-Regeln aus [telegraph], beim Schatten alles in HADES. Für alles, was
     * außerhalb des Kampf-Ticks passiert (Treffer auf Hermes, Steine, Trugbilder).
     */
    fun <T> scoped(block: () -> T): T {
        val previous = Vfx.styleOverride
        if (shade) Vfx.styleOverride = Vfx.Style.HADES
        try {
            return telegraph.during(block)
        } finally {
            Vfx.styleOverride = previous
        }
    }

    /** Ton des Kampfes mit Budget (höchstens zwei je Tick). */
    fun sound(event: SoundEvent, at: Vec3d, volume: Float = 2f, pitch: Float = 1f) {
        telegraph.sound(event, at, volume, pitch)
    }

    /** Hinweis in der Aktionsleiste. */
    fun hint(key: String, who: Collection<ServerPlayerEntity> = players()) {
        for (player in who) player.sendMessage(Text.translatable(key).formatted(Formatting.GOLD), true)
    }

    /** Waagerechte Bewegung eines Spielers je Tick, gemittelt über die letzten Ticks. */
    fun motion(player: ServerPlayerEntity): Vec3d {
        val track = tracks[player.uuid] ?: return Vec3d.ZERO
        if (track.size < 2) return Vec3d.ZERO
        val d = track.last().subtract(track.first()).multiply(1.0 / (track.size - 1))
        return Vec3d(d.x, 0.0, d.z)
    }

    private fun trackPlayers() {
        val present = HashSet<UUID>()
        for (player in targets()) {
            present.add(player.uuid)
            val track = tracks.getOrPut(player.uuid) { ArrayDeque() }
            track.addLast(player.entityPos)
            while (track.size > MOTION_SPAN + 1) track.removeFirst()
        }
        tracks.keys.retainAll(present)
    }

    /**
     * Er steht offen: goldene Rune unter ihm, Treffer zählen × [OPENING_BONUS] (Plan 4: „offen“). Für SKID, STUCK,
     * STUMBLE, STAGGER und die Erschöpfung.
     */
    fun markOpen(ticks: Int) {
        val entity = boss ?: return
        openFor = ticks
        Vfx.rune(world, Vfx.Style.GOLD, entity.entityPos.add(0.0, 0.06, 0.0), OPEN_RUNE, ticks, entity)
        sound(HhaSounds.CHIME, entity.entityPos, 1.8f, 1.35f)
    }

    /** Beendet das offene Fenster vorzeitig (Phasenwechsel). */
    fun closeOpening() {
        openFor = 0
    }

    // ---------------------------------------------------------------------------------------------
    // Diebesgriff: die Beute-Kugel
    // ---------------------------------------------------------------------------------------------

    /** Er nimmt [effects] von [victim] in die Beute-Kugel; sie bleibt [BossTimings.Hermes.LOOT_HOLD] Ticks sichtbar. */
    fun takeLoot(victim: ServerPlayerEntity, effects: List<StatusEffectInstance>) {
        loot.getOrPut(victim.uuid) { ArrayList() }.addAll(effects)
        lootFor = BossTimings.Hermes.LOOT_HOLD
        boss?.loot = true
    }

    /** Hält er gerade Beute? */
    val holdingLoot: Boolean get() = lootFor > 0

    /**
     * Gibt alle gestohlenen Effekte ihren Besitzern zurück. Wer ihn dafür getroffen hat ([hitter]), bekommt dazu
     * Tempo I für [BossTimings.Hermes.LOOT_SPEED] Ticks.
     */
    private fun returnLoot(hitter: ServerPlayerEntity?) {
        if (lootFor <= 0 && loot.isEmpty()) return
        for ((id, effects) in loot) {
            val owner = world.getPlayerByUuid(id) as? ServerPlayerEntity ?: continue
            if (!owner.isAlive) continue
            for (effect in effects) owner.addStatusEffect(StatusEffectInstance(effect))
        }
        loot.clear()
        lootFor = 0
        val entity = boss
        if (entity != null) {
            entity.loot = false
            if (hitter != null) {
                Vfx.flare(world, Vfx.Style.GOLD, entity.entityPos.add(0.0, 1.8, 0.0), 2.2f, 10)
                sound(HhaSounds.CHIME, entity.entityPos, 2.2f, 1.6f)
            }
        }
        if (hitter != null) {
            hitter.addStatusEffect(StatusEffectInstance(StatusEffects.SPEED, BossTimings.Hermes.LOOT_SPEED, 0))
            hint("hha.hermes.hint.returned", listOf(hitter))
        }
    }

    /** Zu langsam: Die Beute-Kugel zerfällt, die Effekte sind fort. */
    private fun dropLoot() {
        loot.clear()
        lootFor = 0
        val entity = boss ?: return
        entity.loot = false
        Vfx.feathers(world, style, entity.entityPos.add(0.0, 1.8, 0.0), 8, 0.8f, 24)
    }

    /** Schaden skaliert über `hermes_damage`; Spieler in einer Cutscene sind immer sicher. */
    fun hurt(player: ServerPlayerEntity, base: Float, magic: Boolean = false) {
        if (inCutscene) return
        // Ziele, die ein Ablauf früher gemerkt hat, können inzwischen ausgeschieden oder in einer anderen Welt sein.
        if (player.uuid !in participants || player.entityWorld !== world) return
        val scaled = scaledDamage(base)
        val attacker = boss
        val source: DamageSource = if (magic || attacker == null) world.damageSources.magic() else world.damageSources.mobAttack(attacker)
        player.damage(world, source, scaled)
    }

    /** Spieler mit den Füßen auf dem Forum (für Bodenwellen, über die man springen kann). */
    fun grounded(player: ServerPlayerEntity): Boolean = player.y <= arena.floorY + 0.7

    fun clampToArena(pos: Vec3d, margin: Double = 3.0): Vec3d {
        val flat = pos.subtract(center).let { Vec3d(it.x, 0.0, it.z) }
        val limit = HermesArena.RADIUS - margin
        val clamped = if (flat.length() > limit) flat.normalize().multiply(limit) else flat
        return Vec3d(center.x + clamped.x, pos.y, center.z + clamped.z)
    }

    fun spawnBoss(pos: Vec3d): HermesEntity {
        val entity = HhaEntities.HERMES.create(world, SpawnReason.EVENT)!!
        entity.setPosition(pos)
        entity.yaw = 0f
        entity.corrupted = shade
        world.spawnEntity(entity)
        boss = entity
        updateScaling(force = true)
        entity.health = entity.maxHealth
        return entity
    }

    /**
     * Löst die beiden Schlangen vom Caduceus (Variante 0 golden, 1 silberblau; beim Schatten verkohlt). Sie sind reine
     * Attacke ohne Trefferzonen und ohne Lebensleiste; [anchor] ist der Stab, Standard über Hermes' Kopf.
     */
    fun spawnSerpents(anchor: Vec3d? = null): List<SerpentEntity> {
        val spawned = ArrayList<SerpentEntity>()
        val at = anchor ?: (boss?.entityPos?.add(0.0, 2.4, 0.0) ?: center)
        for (variant in 0..1) {
            val serpent = HhaEntities.SERPENT.create(world, SpawnReason.EVENT)!!
            serpent.variant = variant
            serpent.corrupted = shade
            serpent.setPosition(at.add(if (variant == 0) 0.4 else -0.4, 0.0, 0.0))
            world.spawnEntity(serpent)
            serpents.add(serpent)
            spawned.add(serpent)
        }
        return spawned
    }

    /** Die Schlangen kehren in den Stab zurück. */
    fun dismissSerpents(list: Collection<SerpentEntity> = serpents.toList()) {
        for (serpent in list) {
            serpent.discard()
            serpents.remove(serpent)
        }
    }

    fun face(entity: Entity, target: Vec3d) {
        val d = target.subtract(entity.entityPos)
        entity.yaw = (Math.toDegrees(atan2(d.z, d.x)) - 90.0).toFloat()
        entity.pitch = 0f
    }

    /** Echter Vanilla-Blitz samt Donner und Himmelsflackern, ohne Schaden und Feuer. */
    fun lightning(pos: Vec3d) {
        if (Cine.muted) return
        val bolt = EntityType.LIGHTNING_BOLT.create(world, SpawnReason.EVENT) ?: return
        bolt.refreshPositionAfterTeleport(pos.x, pos.y, pos.z)
        bolt.setCosmetic(true)
        world.spawnEntity(bolt)
    }

    fun runTask(task: Sequence<Unit>) {
        tasks.add(task.iterator())
    }

    fun clearTasks() {
        tasks.clear()
    }

    fun clearMinions() {
        for (entity in illusions) entity.discard()
        illusions.clear()
        boss?.loot = false
        for (entity in serpents) entity.discard()
        serpents.clear()
        for (entity in stones) entity.discard()
        stones.clear()
    }

    /** Wird nach jeder Cutscene aufgerufen: Stage läuft, Balken und Attacken kommen zurück. */
    fun startStage(number: Int) {
        stage = number
        boss?.stage = number
        inCutscene = false
        bossInvulnerable = false
        transitionQueued = false
        telegraph.reset()
        telegraph.style = stageStyle(number)
        bossBar.isVisible = true
        val prefix = if (shade) "hha.shade" else "hha.hermes"
        bossBar.setName(Text.translatable("$prefix.bar", Text.translatable("$prefix.stage.$number")))
        bossBar.color = if (shade) BossBar.Color.GREEN else when (number) {
            2 -> BossBar.Color.PINK
            3 -> BossBar.Color.BLUE
            4 -> BossBar.Color.RED
            else -> BossBar.Color.YELLOW
        }
    }

    /** Wechselt das Stage-Thema; `null` stoppt die Musik. */
    fun setMusic(event: SoundEvent?) {
        Cine.music(players(), event)
    }

    /** Stellt die Spieler für eine Cutscene im Kreis um die Mitte auf und friert das Geschehen ein. */
    fun gatherPlayers() {
        val list = players()
        for ((i, player) in list.withIndex()) {
            val angle = i.toDouble() / max(1, list.size) * Math.PI * 2.0 + Math.PI * 0.5
            val pos = center.add(cos(angle) * 11.0, 0.0, sin(angle) * 11.0)
            val yaw = (Math.toDegrees(atan2(center.z - pos.z, center.x - pos.x)) - 90.0).toFloat()
            player.teleport(world, pos.x, pos.y, pos.z, emptySet(), yaw, 0f, false)
            player.velocity = Vec3d.ZERO
            player.velocityDirty = true
            player.fallDistance = 0.0
            player.extinguish()
        }
    }

    fun onBossDamaged(entity: HermesEntity, source: DamageSource, amount: Float): Float = scoped { damageBoss(entity, source, amount) }

    private fun damageBoss(entity: HermesEntity, source: DamageSource, amount: Float): Float {
        if (entity.isIllusion) {
            attacks.onIllusionStruck(entity, source)
            return 0f
        }
        if (inCutscene || stage !in 1..4) return 0f
        val attacker = source.attacker
        // Nur wer wirklich auf dem Forum steht, kämpft mit. Wer k.o. gegangen ist, bleibt draußen.
        if (attacker is ServerPlayerEntity && attacker.squaredDistanceTo(center) < JOIN_RADIUS * JOIN_RADIUS && participants.add(attacker.uuid)) {
            Cine.rejoin(attacker, this)
            bossBar.addPlayer(attacker)
        }
        if (bossInvulnerable || !entity.vulnerable) {
            Vfx.flare(world, style, entity.entityPos.add(0.0, 1.6, 0.0), 1.6f, 6)
            sound(HhaSounds.DEFLECT, entity.entityPos, 1.6f, 0.9f + world.random.nextFloat() * 0.25f)
            if (attacker is ServerPlayerEntity) attacker.sendMessage(Text.translatable("hha.hermes.hint.invulnerable").formatted(Formatting.GOLD), true)
            return 0f
        }
        // Abfangen (× 1,5), Rauchschritt durchschauen, Trugbilder verpuffen lassen.
        val bonus = attacks.onBossStruck(entity, source)
        // Wer ihn trifft, während er die Beute hält, holt sie zurück.
        if (attacker is ServerPlayerEntity && lootFor > 0) returnLoot(attacker)
        val open = openFor > 0
        // Mehr Spieler bedeuten mehr Leben: Jeder Treffer zählt entsprechend weniger (siehe updateScaling).
        val toughness = HhaConfig.numF("hermes_toughness") * healthScale
        val raw = amount * bonus * (if (open) OPENING_BONUS else 1f) / toughness
        val cap = HhaConfig.numF("hermes_max_hit") * (if (open || bonus > 1f) 1.5f else 1f)
        val capped = raw.coerceAtMost(cap)
        val floor = stageFloor(stage)
        val allowed = min(capped, entity.health - floor)
        if (allowed <= 0f) return 0f
        if (entity.health - allowed <= floor + 0.01f) queueTransition()
        return allowed
    }

    private fun queueTransition() {
        transitionQueued = true
    }

    /** `/hha hermes stage`: springt für Tests an das Ende der aktuellen Stage. */
    fun forceNextStage() {
        val pending = parcel
        if (stage == 5 && pending != null && !inCutscene) {
            val opener = targets().firstOrNull() ?: return
            onParcelOpened(pending, opener)
            return
        }
        val entity = boss ?: return
        if (stage !in 1..4) return
        entity.health = stageFloor(stage)
        queueTransition()
    }

    fun canHurtMinions(): Boolean = !inCutscene && stage in 1..4

    /** Ein Herma-Stein ist zerschlagen (lohnendes Ziel: Gold). */
    fun onHermaDestroyed(entity: HermaEntity) = scoped {
        stones.remove(entity)
        entity.lit = false
        Vfx.nova(world, Vfx.Style.GOLD, entity.entityPos.add(0.0, 2.0, 0.0), 3.5f, 12)
        sound(SoundEvents.BLOCK_STONE_BREAK, entity.entityPos, 2f, 0.7f)
        attacks.onStoneBroken(entity)
    }

    fun onParcelOpened(entity: DivineParcelEntity, opener: PlayerEntity) {
        if (parcelOpened || inCutscene) return
        parcelOpened = true
        entity.openAge = 0
        script = cutscenes.parcelOpened(entity, opener).iterator()
    }

    /** Verteilt die Belohnung an alle, die bis zum Ende dabei waren. */
    fun grantRewards() {
        for (player in players()) {
            give(player, ItemStack(HhaItems.HEAVEN_HELMET))
            give(player, ItemStack(HhaItems.HEAVEN_CHESTPLATE))
            give(player, ItemStack(HhaItems.HEAVEN_LEGGINGS))
            give(player, ItemStack(HhaItems.HEAVEN_BOOTS))
            give(player, ItemStack(HhaItems.HERMES_FEATHER, 4))
            // Zwischen den Federn steckt noch etwas: das, was vom schwarzen Brief übrig blieb.
            give(player, ItemStack(HhaItems.HADES_MAP))
            player.sendMessage(Text.translatable("hha.hades.map.found").formatted(Formatting.GREEN, Formatting.ITALIC), false)
        }
    }

    /** Beute aus der Hades-Halle: Der Weg durchs Tor ist die eigentliche Belohnung. */
    fun grantShadeRewards() {
        for (player in players()) {
            // Der Schatten trug noch Hermes' Waffen: Wer ihn besiegt, nimmt sie ihm ab.
            give(player, ItemStack(HhaItems.HEAVENS_SWORD))
            give(player, ItemStack(HhaItems.HEAVENS_MACE))
            give(player, ItemStack(HhaItems.HERMES_FEATHER, 8))
            give(player, ItemStack(net.minecraft.item.Items.NETHERITE_SCRAP, 4))
            give(player, ItemStack(net.minecraft.item.Items.ENCHANTED_GOLDEN_APPLE, 2))
        }
    }

    private fun give(player: ServerPlayerEntity, stack: ItemStack) {
        if (!player.giveItemStack(stack)) player.dropItem(stack, false)
    }

    /**
     * Tödliche Treffer enden im Forum nie mit dem Tod: Hermes stellt nur zu. Der Spieler landet angeschlagen
     * wieder am Beschwörungsort und behält sein Inventar.
     * @return true, wenn der Tod abgefangen wurde.
     */
    fun tryKnockOut(player: ServerPlayerEntity, cause: DamageSource): Boolean {
        if (player.uuid !in participants) return false
        Hha.LOGGER.info("Hermes-Kampf: {} ist k.o. ({})", player.name.string, cause.name)
        // Der tödliche Angriff läuft nach diesem Aufruf weiter: Seine Folge-Effekte sollen den Rückkehrort nicht erreichen.
        Fx.shield(player)
        player.health = max(4f, player.maxHealth * 0.25f)
        player.extinguish()
        for (effect in player.statusEffects.map { it.effectType }) player.removeStatusEffect(effect)
        participants.remove(player.uuid)
        bossBar.removePlayer(player)
        Cine.end(listOf(player))
        Cine.atmosphere(listOf(player), null, center, 40)
        Cine.wings(listOf(player), false)
        Cine.music(listOf(player), null)
        // Vollständig zurücksetzen (auch eine laufende Abblende) und spätere Sendungen dieses Kampfes aus gemerkten
        // Empfängerlisten sperren: Er ist nicht mehr im Aufräum-Kreis von players().
        Cine.depart(player, this)
        skip.recheck(players())
        Vfx.feathers(world, Vfx.Style.HEAVEN, player.entityPos.add(0.0, 1.0, 0.0), 16, 1.2f, 40)
        sendBack(player)
        player.addStatusEffect(StatusEffectInstance(StatusEffects.REGENERATION, 200, 1))
        player.addStatusEffect(StatusEffectInstance(StatusEffects.RESISTANCE, 100, 4))
        player.sendMessage(Text.translatable("hha.hermes.fail.card").formatted(Formatting.GOLD, Formatting.ITALIC), false)
        return true
    }

    /** Ausgeloggt: zurück an den Beschwörungsort, damit niemand später hoch über der Welt aufwacht. */
    fun onPlayerLeft(player: ServerPlayerEntity) {
        if (participants.remove(player.uuid)) {
            bossBar.removePlayer(player)
            skip.recheck(players().filter { it.uuid != player.uuid })
            if (arenaBuilt) player.refreshPositionAndAngles(origin.x, origin.y, origin.z, player.yaw, player.pitch)
            // Offline bekäme der Beschwörer den Brief am Ende nicht mehr zurück.
            if (mode == Mode.LETTER && player.uuid == summonerId && !letterReturned && stage < 5) {
                letterReturned = true
                give(player, ItemStack(HhaItems.HERMES_SUMMONS))
            }
        }
    }

    /**
     * Ein Teilnehmer ist in eine andere Dimension gewechselt. Anders als [onPlayerLeft] wird er nicht zurückgesetzt
     * (seine Position gehört jetzt zur neuen Welt); nur sein Zustand im Kampf und am Client wird beendet.
     */
    fun onPlayerChangedWorld(player: ServerPlayerEntity) {
        if (!participants.remove(player.uuid)) return
        bossBar.removePlayer(player)
        val list = listOf(player)
        Cine.end(list)
        Cine.atmosphere(list, null, center, 40)
        Cine.wings(list, false)
        Cine.music(list, null)
        Cine.depart(player, this)
        skip.recheck(players())
        // Gestohlene Effekte gehören ihm weiter.
        loot.remove(player.uuid)?.let { effects -> for (effect in effects) player.addStatusEffect(StatusEffectInstance(effect)) }
        if (mode == Mode.LETTER && player.uuid == summonerId && !letterReturned && stage < 5) {
            letterReturned = true
            give(player, ItemStack(HhaItems.HERMES_SUMMONS))
        }
    }

    private fun sendBack(player: ServerPlayerEntity) {
        player.teleport(world, origin.x, origin.y, origin.z, emptySet(), player.yaw, player.pitch, false)
        player.velocity = Vec3d.ZERO
        player.velocityDirty = true
        player.fallDistance = 0.0
    }

    fun tick() {
        if (ended) return
        // Im Reich des Hades brennt alles grün, ohne dass jede Attacke davon wissen muss; alles läuft im Kampf-Scope.
        scoped { step() }
    }

    /** Vom Netzwerk: ein Teilnehmer will die laufende Szene überspringen. */
    fun voteSkip(player: ServerPlayerEntity): Boolean {
        if (player.uuid !in participants) return false
        skip.vote(player, players())
        return true
    }

    /** Ein Schritt im Drehbuch ohne die Kampflogik drumherum; false, wenn nichts mehr läuft. */
    private fun advanceScript(): Boolean {
        arena.tick()
        val iterator = tasks.iterator()
        while (iterator.hasNext()) {
            val task = iterator.next()
            if (task.hasNext()) task.next() else iterator.remove()
        }
        val running = script ?: return false
        if (!running.hasNext()) {
            script = null
            return false
        }
        running.next()
        return !ended
    }

    private fun step() {
        if (skip.pending) skip.fastForward(players(), ::advanceScript)
        ticks++
        arena.tick()
        if (ticks % 20 == 0 && !inCutscene) updateScaling()
        syncBar()
        rescueFallers()
        trackPlayers()
        if (openFor > 0) openFor--
        if (lootFor > 0 && --lootFor == 0) dropLoot()

        val iterator = tasks.iterator()
        while (iterator.hasNext()) {
            val task = iterator.next()
            if (task.hasNext()) task.next() else iterator.remove()
        }

        if (transitionQueued && !inCutscene) {
            transitionQueued = false
            beginTransition()
        }

        val running = script
        if (running != null) {
            if (running.hasNext()) running.next() else script = null
        } else if (stage in 1..4 && !inCutscene) {
            script = attacks.next(stage).iterator()
        }

        if (stage in 0..5) checkAbandoned()
    }

    private fun beginTransition() {
        val next = stage + 1
        inCutscene = true
        bossInvulnerable = true
        clearTasks()
        // Beute zurück: Wer bestohlen wurde, verliert sie nicht durch den Stagewechsel.
        returnLoot(null)
        clearMinions()
        attacks.reset()
        telegraph.reset()
        closeOpening()
        bossBar.isVisible = next <= 4
        boss?.vulnerable = true
        val scene: Sequence<Unit> = if (shade) {
            when (next) {
                3 -> shadeScenes.rising()
                4 -> shadeScenes.last()
                else -> shadeScenes.finale()
            }
        } else {
            when (next) {
                2 -> cutscenes.feather()
                3 -> cutscenes.descent()
                4 -> cutscenes.zeus()
                else -> cutscenes.finale()
            }
        }
        script = scene.iterator()
    }

    fun markFinale() {
        stage = 5
        bossBar.isVisible = false
    }

    private fun syncBar() {
        val entity = boss ?: return
        bossBar.percent = (entity.health / entity.maxHealth).coerceIn(0f, 1f)
    }

    /** Wer vom Forum stürzt, wird von einer Bö zurückgeworfen — das Forum ist für alle der einzige Ausweg. */
    private fun rescueFallers() {
        if (!arenaBuilt) return
        for (player in players()) {
            val horizontal = Vec3d(player.x - center.x, 0.0, player.z - center.z).length()
            if (player.y < arena.floorY - 14.0 || horizontal > HermesArena.RADIUS + 70.0) {
                val spot = center.add(randomHorizontal(6.0))
                player.teleport(world, spot.x, spot.y + 1.0, spot.z, emptySet(), player.yaw, player.pitch, false)
                player.velocity = Vec3d.ZERO
                player.velocityDirty = true
                player.fallDistance = 0.0
                if (!inCutscene) {
                    hurt(player, 4f, magic = true)
                    player.sendMessage(Text.translatable("hha.hermes.hint.fall").formatted(Formatting.GOLD), true)
                }
                Vfx.shockwave(world, Vfx.Style.GOLD, spot, 3f, 10, 1.2f)
            }
        }
    }

    private fun checkAbandoned() {
        if (targets().isEmpty()) {
            noPlayersFor++
            if (noPlayersFor > 60) finish(success = false)
        } else {
            noPlayersFor = 0
        }
    }

    /** Beendet alles und stellt die Welt wieder her. */
    fun finish(success: Boolean) {
        if (ended) return
        ended = true
        // Ein Aufräumweg für jedes Ende: Abläufe abbrechen, Angriffe und Ansagen zurücksetzen, eigene Geschosse entfernen.
        script = null
        clearTasks()
        attacks.reset()
        telegraph.reset()
        for (bolt in world.getEntitiesByClass(HermesBoltEntity::class.java, net.minecraft.util.math.Box.of(center, 140.0, 90.0, 140.0)) { it.getOwner() == null || it.getOwner() == boss }) bolt.discard()
        val everyone = players()
        Cine.end(everyone)
        // Abbruch (Niederlage, /hha hermes stop, Server-Stopp) kann mitten in einer Abblende kommen: alles zurücksetzen.
        if (!success) Cine.reset(everyone)
        Cine.atmosphere(everyone, null, center, 40)
        Cine.wings(everyone, false)
        Cine.music(everyone, null)
        // Nach dem Sieg in der Halle bleiben alle vor dem offenen Tor stehen.
        if (!(shade && success)) {
            for (player in everyone) sendBack(player)
        }
        bossBar.clearPlayers()
        returnLoot(null)
        clearMinions()
        boss?.discard()
        parcel?.discard()
        arena.restore()
        if (!success && !letterReturned && mode == Mode.LETTER) {
            // Der Brief kommt zurück: nichts verloren außer etwas Stolz.
            val owner = world.getPlayerByUuid(summonerId) as? ServerPlayerEntity
            if (owner != null) give(owner, ItemStack(HhaItems.HERMES_SUMMONS))
        }
        Cine.encounterEnded(this)
        HermesFights.onFinished(this)
        Hha.LOGGER.info("Hermes-Kampf beendet (Erfolg: {})", success)
    }

    companion object {
        const val PICKUP_RADIUS = 32.0
        const val HALL_PICKUP_RADIUS = 56.0
        const val JOIN_RADIUS = 60.0

        /** Treffer in einem offenen Fenster zählen so viel mehr (wie `OPENING_BONUS` bei Hades). */
        const val OPENING_BONUS = 1.8f

        /** Leben eines Herma-Steins (Plan 4.2), wächst mit der Spielerzahl. */
        const val HERMA_HEALTH = 30.0

        /** Radius der goldenen Rune im offenen Fenster. */
        private const val OPEN_RUNE = 2.4f

        /** Über so viele Ticks wird die Spielerbewegung gemittelt. */
        private const val MOTION_SPAN = 6
    }
}
