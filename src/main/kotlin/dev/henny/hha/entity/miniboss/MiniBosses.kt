package dev.henny.hha.entity.miniboss

import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossDefense
import dev.henny.hha.logic.boss.BossRewards
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha.logic.world.TartarosChain
import dev.henny.hha.logic.world.TartarosMinibosses
import net.minecraft.block.Blocks
import net.minecraft.entity.EntityType
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.boss.ServerBossBar
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.mob.HostileEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.particle.BlockStateParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Aktionen der Wächter für [MiniBossKit.act] (Plan 7). Die Werte gehen über die Leitung, also nur hinten anfügen;
 * 0 beendet die laufende Aktion. Zeiten stehen in [BossTimings] unter dem Namen des Wächters.
 */
object WardenAction {
    const val NONE = 0
    const val CHOP = 1
    const val CHAIN = 2
    const val ROUND = 3
    const val HOOKED = 4
    const val RAGE = 5
    const val DOUBLE_CHOP = 6
    const val STAGGER = 7
}

/** Aktionen des Sisyphos, siehe [WardenAction]. */
object SisyphusAction {
    const val NONE = 0
    const val THROW = 1
    const val BOWL = 2
    const val STOMP = 3
    const val CURSE = 4
    const val REGROW = 5
    const val STAGGER = 6
}

/** Aktionen des Ixion, siehe [WardenAction]. */
object IxionAction {
    const val NONE = 0
    const val ROLL_WINDUP = 1
    const val ROLL = 2
    const val WOBBLE = 3
    const val SPOKES = 4
    const val SPARKS = 5
    const val FALLEN = 6
    const val RISE = 7
}

/** Aktionen des Tantalos, siehe [WardenAction]. */
object TantalusAction {
    const val NONE = 0
    const val THRUST3 = 1
    const val GEYSER = 2
    const val WAVE = 3
    const val DRINK = 4
    const val PARCHED = 5
    const val RAGE = 6
}

/**
 * Was alle Wächter der Tartaros-Bauwerke gemeinsam haben: eine Lebensleiste für alle in der Nähe, ein Auftritt mit Titel,
 * die Sonderbewegungen fürs Modell ([act]), die Beute und die Meldung an [TartarosMinibosses], dass dieses Bauwerk jetzt
 * befreit ist. Keine Aura, keine eigene Zielwahl: Das macht das Drehbuch in [GuardianEntity].
 */
class MiniBossKit(private val mob: HostileEntity, val nameKey: String, color: BossBar.Color, private val style: Vfx.Style) {

    val bar = ServerBossBar(Text.translatable(nameKey), color, BossBar.Style.NOTCHED_10)

    /** Das Bauwerk, zu dem er gehört (siehe [TartarosMinibosses]). */
    var site: Long = 0L

    var age = 0
        private set

    /** Clientseitig: welche Sonderbewegung das Modell gerade zeigt (0 = keine) und seit wie vielen Ticks. */
    var action = 0
        private set

    /** Zählt auch nach dem Ende einer Bewegung weiter (bis [ACTION_MAX]), damit Modelle ausblenden. */
    var actionAge = 0
        private set

    /** Clientseitig: die Bewegung vor der jetzigen und wie weit sie beim Wechsel war (zum weichen Ausblenden). */
    var previousAction = 0
        private set
    var previousActionAge = 0
        private set

    private val world: ServerWorld? get() = mob.entityWorld as? ServerWorld

    /**
     * Zeigt [tick] die eigene Lebensleiste? Die neuen Bosse nehmen stattdessen die geteilte Phasen-Leiste
     * (`Cine.phaseBar`), die Gegner des Olymp und Bia haben keine.
     */
    var barVisible = true
        set(value) {
            field = value
            if (!value) bar.clearPlayers()
        }

    /**
     * Werte setzen: Leben, Rüstung, Schaden, Tempo. Die Größe bleibt 1: Die Trefferbox kommt aus dem Entity-Typ, das
     * Modell hat im Renderer einen festen Faktor (Plan 6.3). Mehr Spieler teilen den Schaden ([GuardianEntity]).
     */
    fun stats(health: Double, armor: Double, damage: Double, speed: Double) {
        mob.getAttributeInstance(EntityAttributes.MAX_HEALTH)?.baseValue = health
        mob.getAttributeInstance(EntityAttributes.ARMOR)?.baseValue = armor
        mob.getAttributeInstance(EntityAttributes.ATTACK_DAMAGE)?.baseValue = damage
        mob.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED)?.baseValue = speed
        mob.getAttributeInstance(EntityAttributes.KNOCKBACK_RESISTANCE)?.baseValue = 1.0
        mob.getAttributeInstance(EntityAttributes.FOLLOW_RANGE)?.baseValue = 48.0
        // Riesen steigen über einen Block, ohne zu springen.
        mob.getAttributeInstance(EntityAttributes.STEP_HEIGHT)?.baseValue = 1.1
        mob.health = mob.maxHealth
        mob.setPersistent()
    }

    /** Nur die Lebensleiste: wer nahe ist, sieht sie. */
    fun tick() {
        val world = world ?: return
        age++
        if (!barVisible) return
        bar.percent = (mob.health / mob.maxHealth).coerceIn(0f, 1f)
        if (age % 10 == 0) {
            for (player in world.players) {
                val near = player.squaredDistanceTo(mob) < 48.0 * 48.0 && player.isAlive
                if (near && player !in bar.players) bar.addPlayer(player)
                if (!near && player in bar.players) bar.removePlayer(player)
            }
        }
    }

    /**
     * Auftritt: Titel für alle in der Nähe, ein Ton und ein Lichtpfeiler (Plan 3.4). [subtitle] ersetzt die Unterzeile
     * (die Tartaros-Wächter nennen dort ihr Glied der Kette, siehe `TartarosMinibosses`).
     */
    fun announce(subtitle: Text = Text.translatable("$nameKey.sub")) {
        val world = world ?: return
        Vfx.pillar(world, style, mob.entityPos, 2.2f, 30f, 30)
        world.playSound(null, mob.x, mob.y, mob.z, SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 1.4f, 0.8f)
        for (player in world.players) {
            if (player.squaredDistanceTo(mob) > 60.0 * 60.0) continue
            player.networkHandler.sendPacket(net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket(8, 50, 16))
            player.networkHandler.sendPacket(net.minecraft.network.packet.s2c.play.SubtitleS2CPacket(subtitle))
            player.networkHandler.sendPacket(net.minecraft.network.packet.s2c.play.TitleS2CPacket(Text.translatable(nameKey)))
        }
    }

    /**
     * Kündigt dem Client eine Sonderbewegung an, damit das Modell ausholt, bevor der Angriff kommt. [kind] reicht von
     * 1 bis [ACTION_KINDS]; 0 beendet die laufende Bewegung. Die Zeit läuft ab diesem Tick.
     */
    fun act(kind: Int) {
        if (kind !in 0..ACTION_KINDS) {
            dev.henny.hha.Hha.LOGGER.warn("Wächter-Aktion {} außerhalb 0..{}", kind, ACTION_KINDS)
            return
        }
        world?.sendEntityStatus(mob, (ACTION_STATUS + kind).toByte())
    }

    /** Vom Client aus `handleStatus`: true, wenn der Status eine Sonderbewegung (oder ihr Ende) war. */
    fun onStatus(status: Byte): Boolean {
        // Ohne Vorzeichen lesen: 100 + 40 passt nicht in ein Byte mit Vorzeichen (Vanilla nutzt nur kleine Werte).
        val kind = (status.toInt() and 0xFF) - ACTION_STATUS
        if (kind !in 0..ACTION_KINDS) return false
        previousAction = action
        previousActionAge = actionAge
        action = kind
        actionAge = 0
        return true
    }

    /**
     * Läuft auf beiden Seiten; zählt nur auf dem Client weiter, auch nach dem Ende (Aktion 0), höchstens bis
     * [ACTION_MAX]. Eine Bewegung, die so lange läuft, fällt von selbst auf 0 zurück.
     */
    fun animTick() {
        if (!mob.entityWorld.isClient) return
        if (actionAge < ACTION_MAX) {
            actionAge++
            return
        }
        if (action != 0) {
            previousAction = action
            previousActionAge = actionAge
            action = 0
            actionAge = 0
        }
    }

    fun onRemoved() {
        bar.clearPlayers()
    }

    /**
     * Ein Wächter der Kette ist gefallen (Überarbeitung 2, PLAN 5.7/6.1): ein goldener Pfeiler (hier hast du gewonnen),
     * die Beute über [BossRewards.grant] (die einzige Beute-Stelle; Schlüssel = Glied) an alle, die am Ende mitkämpfen,
     * dann sprengt [TartarosMinibosses.onDefeated] das Glied und befreit das Bauwerk. Wer kein Glied der Kette ist
     * (Gegner und Bosse des Olymp), bekommt hier nichts: Die haben ihre eigene Beute.
     */
    fun defeated() {
        val world = world ?: return
        bar.clearPlayers()
        val link = (mob as? TartarosGuardian)?.link ?: return
        Vfx.pillar(world, Vfx.Style.GOLD, mob.entityPos, 2f, 26f, 30)
        world.playSound(null, mob.x, mob.y, mob.z, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1f, 1f)
        val players = (mob as? GuardianEntity)?.fighters() ?: players(BossTimings.Guardian.FIGHT_RADIUS)
        BossRewards.grant(link.key, players, mob.entityPos)
        TartarosMinibosses.onDefeated(world, site, link, mob.entityPos)
    }

    // --- Hilfen ------------------------------------------------------------------------------------

    fun players(radius: Double): List<ServerPlayerEntity> {
        val world = world ?: return emptyList()
        return world.players.filter { it.isAlive && !it.isSpectator && !it.isCreative && it.squaredDistanceTo(mob) < radius * radius }
    }

    fun hurt(player: LivingEntity, amount: Float) {
        val world = world ?: return
        player.damage(world, world.damageSources.mobAttack(mob), amount)
    }

    fun sound(event: SoundEvent, volume: Float, pitch: Float) {
        world?.playSound(null, mob.x, mob.y, mob.z, event, SoundCategory.HOSTILE, volume, pitch)
    }

    companion object {
        const val ACTION_STATUS = 100

        /**
         * Höchste Aktionsnummer (Überarbeitung 2, PLAN 4.4): 0–40 für alle Wächter, Bosse und Gegner. Die Status-Bytes
         * 100–140 liest [onStatus] ohne Vorzeichen.
         */
        const val ACTION_KINDS = 40

        /** Nach so vielen Ticks fällt eine Aktion auf dem Client von selbst auf 0 zurück (Zeus' Waage dauert 280). */
        const val ACTION_MAX = 320
    }
}

/** Gemeinsame Schnittstelle, damit Verwalter und Renderer jeden Wächter gleich behandeln können. */
interface MiniBoss {
    val kit: MiniBossKit
}

/** Treffer × so viel, solange er offen ist (Laterne, ohne Fels, Taumeln, Kelch weg, Stachel steckt). */
internal const val OPEN_FACTOR = 1.5f

// =================================================================================================
// Die Kette zu Hades
// =================================================================================================

/**
 * Gemeinsame Basis der fünf Tartaros-Wächter (Überarbeitung 2, PLAN 4.1 und 5.7): Jeder ist ein **Glied der Kette zu
 * Hades** ([link]); sein Sieg sprengt es ([MiniBossKit.defeated]), das Palastsiegel öffnet erst bei fünf.
 *
 * - **Stärke:** Grundleben laut PLAN 4.1 ([baseHealth]), Schaden aller Angriffe × [DAMAGE_FACTOR] ([hit] statt
 *   `strike`), Pausen × [BossTimings.Guardian.TARTAROS_PAUSE_FACTOR]. Spielerzahl, Deckel und Abwehr macht
 *   [GuardianEntity] (Stufe `GUARDIAN`, Familie `TARTAROS` = Regler `hades_damage`).
 * - **Kettenbonus** ([applyChain], beim Erscheinen): je schon gesprengtem Glied + [CHAIN_HEALTH] Leben und
 *   + [CHAIN_DAMAGE] Schaden; ab [BossTimings.Guardian.CHAIN_PAUSE_FROM] Gliedern Pausen ×
 *   [BossTimings.Guardian.CHAIN_PAUSE_FACTOR]; ab [BossTimings.Guardian.CHAIN_DOUBLE_FROM] Gliedern folgt nach jedem
 *   [BossTimings.Guardian.CHAIN_DOUBLE_EVERY]-ten Angriff sofort ein zweiter, anderer (die Erholung kommt danach).
 * - **Abwehr gegen Sprünge:** der Konter der Basis (Aktion [BossDefense.ANTI_AIR]), Schaden [ANTI_AIR_DAMAGE] mit
 *   Kettenbonus. Jeder Angriff nennt seine Reichweite ([BossDefense.Reach]).
 *
 * Die Unterklassen liefern ihre Angriffe über [guardianMoves]; [createMoves] hängt den Doppelangriff an.
 */
abstract class TartarosGuardian(type: EntityType<out TartarosGuardian>, world: World) : GuardianEntity(type, world) {

    /** Das Glied der Kette, das sein Sieg sprengt (zugleich der Beute-Schlüssel für [BossRewards.grant]). */
    abstract val link: TartarosChain.Link

    /** Grundleben solo vor Kettenbonus und Spielerzahl (PLAN 4.1). */
    protected abstract val baseHealth: Double

    /** Auftritt als kurze Szene: Kamera fährt heran, der Erzähler sagt, wer hier wacht. */
    private val cinematics = dev.henny.hha.logic.boss.DuelCinematics(this) { fighters() }

    override fun framedEntrance(): Sequence<Unit> = cinematics.intro(
        entrance(),
        listOf(dev.henny.hha.logic.boss.DuelCinematics.Line(dev.henny.hha.logic.boss.Cine.SPEAKER_NARRATOR, "hha.tartaros.scene.${link.key}", 14, 80)),
        100,
    )

    override fun acceptsHit(world: net.minecraft.server.world.ServerWorld, source: net.minecraft.entity.damage.DamageSource): Boolean =
        !cinematics.playing && super.acceptsHit(world, source)

    override fun remove(reason: RemovalReason) {
        if (!entityWorld.isClient) cinematics.abort()
        super.remove(reason)
    }

    /** Wie viele Glieder bei seinem Erscheinen schon gesprengt waren (0–5). */
    var chainLinks = 0
        private set

    /** Angriffe seit dem Erscheinen (für den Doppelangriff ab vier Gliedern). */
    private var attacks = 0

    /**
     * Kettenbonus beim Erscheinen: Lebensattribut = Grundleben × (1 + [CHAIN_HEALTH] · [links]), volles Leben; Schaden
     * und Pausen lesen [chainLinks] bei jedem Treffer.
     */
    fun applyChain(links: Int) {
        chainLinks = links.coerceIn(0, TartarosChain.Link.entries.size)
        // Vanilla deckelt das Lebensattribut bei 1024: Was darüber liegt, trägt die Zähigkeit (Review R1 M2).
        getAttributeInstance(EntityAttributes.MAX_HEALTH)?.baseValue = intendedHealth().coerceAtMost(MAX_ATTRIBUTE_HEALTH)
        health = maxHealth
    }

    /**
     * Ein anderes Glied ist gesprengt, während er schon steht: Kettenbonus nachziehen, sein Lebensanteil bleibt
     * (kein Vollheilen, laufende Aktion bleibt). Review R2 m3.
     */
    fun raiseChain(links: Int) {
        val target = links.coerceIn(0, TartarosChain.Link.entries.size)
        if (target <= chainLinks || !isAlive) return
        val fraction = (health / maxHealth.coerceAtLeast(1f)).coerceIn(0f, 1f)
        chainLinks = target
        getAttributeInstance(EntityAttributes.MAX_HEALTH)?.baseValue = intendedHealth().coerceAtMost(MAX_ATTRIBUTE_HEALTH)
        health = (maxHealth * fraction).coerceAtLeast(1f)
    }

    /** Gewolltes Leben solo samt Kettenbonus, ohne Vanillas Obergrenze. */
    private fun intendedHealth(): Double = baseHealth * (1.0 + CHAIN_HEALTH * chainLinks)

    /** Zähigkeit = gewolltes Leben / Lebensattribut (≥ 1); so stimmen Leiste, Deckel und Kettenbonus auch über 1024. */
    override fun toughness(): Float = (intendedHealth() / maxHealth.coerceAtLeast(1f)).toFloat().coerceAtLeast(1f)

    /** Schadensfaktor vor Spielerzahl: × [DAMAGE_FACTOR], + [CHAIN_DAMAGE] je gesprengtem Glied. */
    protected fun power(): Float = DAMAGE_FACTOR * (1f + CHAIN_DAMAGE * chainLinks)

    /** Treffer eines Angriffs: [base] × [power], dann wie `strike` (Spielerzahl, Familie, Höhenregel [reach]). */
    protected fun hit(
        player: ServerPlayerEntity, base: Float, reach: BossDefense.Reach = BossDefense.Reach.BODY, floorY: Double = defaultFloor(),
    ): Boolean = strike(player, base * power(), reach, floorY)

    /** Konter (PLAN 4.3: Wächter 12), mit Kettenbonus. */
    override val antiAirDamage: Float get() = ANTI_AIR_DAMAGE * (1f + CHAIN_DAMAGE * chainLinks)

    /** Pausen × 0,8, ab zwei Gliedern zusätzlich × 0,85; danach kürzt [dev.henny.hha.logic.boss.BossScaling.pause] nach Spielerzahl. */
    override fun basePause(): Int {
        var factor = BossTimings.Guardian.TARTAROS_PAUSE_FACTOR
        if (chainLinks >= BossTimings.Guardian.CHAIN_PAUSE_FROM) factor *= BossTimings.Guardian.CHAIN_PAUSE_FACTOR
        return ((BossTimings.Guardian.PAUSE + random.nextInt(8)) * factor).roundToInt()
    }

    /** Die Angriffe des Wächters (ohne Doppelangriff). */
    protected abstract fun guardianMoves(): List<Move>

    final override fun createMoves(): List<Move> {
        val raw = guardianMoves()
        return raw.map { move -> Move(move.id, move.reach, move.weight, move.ready) { target -> combo(move, raw, target) } }
    }

    /**
     * Ein Angriff; ab [BossTimings.Guardian.CHAIN_DOUBLE_FROM] Gliedern hängt er nach jedem dritten sofort einen zweiten,
     * anderen an (passend zur Entfernung, sonst den, der am wenigsten fehlt). Die Erholung (oder der Konter) kommt erst
     * nach dem zweiten; die Fenster innerhalb der Angriffe (Beil steckt, Rad kippt …) bleiben, sie sind die Mechanik.
     */
    private fun combo(first: Move, all: List<Move>, target: ServerPlayerEntity): Sequence<Unit> = sequence {
        yieldAll(first.run(target))
        attacks++
        if (chainLinks < BossTimings.Guardian.CHAIN_DOUBLE_FROM || attacks % BossTimings.Guardian.CHAIN_DOUBLE_EVERY != 0) return@sequence
        if (!isAlive || !target.isAlive || target.isRemoved || target.entityWorld !== entityWorld) return@sequence
        val d = flatDistance(target)
        val options = all.filter { it.id != first.id && it.id != lastMove && it.ready() }.ifEmpty { all.filter { it.id != first.id && it.ready() } }
        if (options.isEmpty()) return@sequence
        val fitting = options.filter { d in it.reach }
        val second = if (fitting.isNotEmpty()) fitting[random.nextInt(fitting.size)] else options.minBy { gapTo(it.reach, d) }
        lastMove = second.id
        yieldAll(second.run(target))
    }

    private fun gapTo(reach: ClosedFloatingPointRange<Double>, d: Double): Double = when {
        d < reach.start -> reach.start - d
        d > reach.endInclusive -> d - reach.endInclusive
        else -> 0.0
    }

    companion object {
        /** Schaden aller Wächter-Angriffe gegenüber der ersten Fassung (PLAN 4.1). */
        const val DAMAGE_FACTOR = 1.4f

        /** Kettenbonus je schon gesprengtem Glied (PLAN 4.1). */
        const val CHAIN_HEALTH = 0.12
        const val CHAIN_DAMAGE = 0.06f

        /** Konter der Wächter vor Skalierung (PLAN 4.3). */
        const val ANTI_AIR_DAMAGE = 12f

        /** Vanillas Obergrenze für `MAX_HEALTH` (`ClampedEntityAttribute`). */
        const val MAX_ATTRIBUTE_HEALTH = 1024.0
    }
}

// =================================================================================================
// Der Kerkermeister
// =================================================================================================

/**
 * Der Kerkermeister der Bronzefeste: ein Hundertarmiger mit Richtbeil, Kette und einer Laterne auf dem Rücken.
 *
 * - **Richtbeil** (nah): Linie 3 × 7, das Beil steckt danach (Fenster). Unter 40 % Leben als **Doppelschlag**.
 * - **Kettenwurf** (fern): Linie bis zur ersten Mauer oder Säule. Erwischt sie einen Spieler, reißt er ihn heran und
 *   schlägt sofort rund. Trifft sie Mauer oder Säule, **hängt der Haken fest** (40 Ticks, Rücken frei).
 * - **Rundschlag** (nah, nach dem Heranziehen): Kegel nach vorn.
 * - **Die Laterne** ist die Schwachstelle: Treffer von hinten × 1,5; drei davon bringen ihn ins Taumeln.
 */
class WardenOfChainsEntity(type: EntityType<out WardenOfChainsEntity>, world: World) : TartarosGuardian(type, world) {

    override val kit = MiniBossKit(this, "entity.hha.warden_of_chains", BossBar.Color.WHITE, Vfx.Style.SHADOW)
    override val fightName = "warden"
    override val danger = Vfx.Style.SHADOW
    override val link get() = TartarosChain.Link.WARDEN
    override val baseHealth get() = HEALTH

    private var enraged = false
    private var lanternHits = 0
    private var staggerDue = false

    init {
        kit.stats(HEALTH, 12.0, 13.0, 0.27)
    }

    override fun guardianMoves() = listOf(
        Move(WardenAction.CHOP, 0.0..6.0, 3) { chop(it) },
        Move(WardenAction.CHAIN, 6.5..BossTimings.Warden.CHAIN_RANGE.toDouble(), 3) { chain(it) },
        Move(WardenAction.ROUND, 0.0..4.5, 2) { round(it) },
    )

    override fun phaseEvent(): Sequence<Unit>? {
        if (staggerDue) return stagger()
        if (!enraged && health < maxHealth * ENRAGE) {
            enraged = true
            return rage()
        }
        return null
    }

    override fun onReset() {
        staggerDue = false
        lanternHits = 0
    }

    /** Kommt der Treffer von hinten (mehr als ~110° aus seiner Blickrichtung)? Dann trifft er die Laterne. */
    private fun fromBehind(source: DamageSource): Boolean {
        val from = source.position ?: source.attacker?.entityPos ?: return false
        val to = flatNorm(from.subtract(entityPos))
        val f = facing()
        return to.x * f.x + to.z * f.z < -0.34
    }

    override fun hitFactor(source: DamageSource): Float = if (source.attacker is PlayerEntity && fromBehind(source)) OPEN_FACTOR else 1f

    override fun onDamaged(source: DamageSource, lost: Float, factor: Float) {
        if (source.attacker !is PlayerEntity || !fromBehind(source)) return
        hint("hha.guardian.hint.lantern")
        if (++lanternHits >= BossTimings.Warden.LANTERN_HITS) {
            lanternHits = 0
            staggerDue = true
        }
    }

    private fun axeLine(dir: Vec3d) = Pair(ground(entityPos), ground(entityPos.add(dir.multiply(BossTimings.Warden.CHOP_LENGTH.toDouble()))))

    /** Richtbeil, unter 40 % als Doppelschlag mit eigener Ansage für den zweiten Hieb. */
    private fun chop(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val w = BossTimings.Warden
        val double = enraged
        var dir = dirTo(target.entityPos)
        hold(dir)
        kit.act(if (double) WardenAction.DOUBLE_CHOP else WardenAction.CHOP)
        val (a, b) = axeLine(dir)
        val first = telegraph.line(a, b, w.CHOP_WIDTH, w.CHOP_WINDUP, Telegraph.Pace.HEAVY)
        telegraph.sound(HhaSounds.CHARGE, entityPos, 2f, 0.55f)
        wait(first.ticks)
        axeFalls(first, dir, shake = !double)
        var stuck = w.CHOP_STUCK_END - w.CHOP_WINDUP
        var tail = w.CHOP_END - w.CHOP_STUCK_END
        if (double) {
            // Er reißt das Beil sofort wieder hoch; der zweite Hieb hat seine eigene Ansage (Plan 4.6).
            dir = dirTo(target.entityPos)
            hold(dir)
            val (c, d) = axeLine(dir)
            val second = telegraph.line(c, d, w.CHOP_WIDTH, w.DOUBLE_CHOP_GAP, Telegraph.Pace.FOLLOW_UP)
            wait(second.ticks)
            axeFalls(second, dir, shake = true)
            stuck = w.DOUBLE_CHOP_STUCK_END - w.DOUBLE_CHOP_SECOND
            tail = w.DOUBLE_CHOP_END - w.DOUBLE_CHOP_STUCK_END
        }
        // Das Beil steckt (F 30): Er zerrt mit allen vier Händen, die Laterne ist frei.
        if (window(stuck) { staggerDue }) {
            yieldAll(stagger())
            return@sequence
        }
        wait(tail)
        kit.act(0)
    }

    private fun axeFalls(line: Telegraph.Line, dir: Vec3d, shake: Boolean) {
        val tip = ground(entityPos.add(dir.multiply(BossTimings.Warden.CHOP_LENGTH * 0.6)))
        val struck = line.hits(fighters())
        for (player in struck) {
            hit(player, 16f)
            knock(player, dir, 0.5, 0.35)
        }
        Vfx.shockwave(sw, danger, tip, 2.6f, 12, 1.6f)
        telegraph.sound(HhaSounds.IMPACT, tip, 2.2f, 0.7f)
        if (shake && struck.isNotEmpty()) telegraph.hitShake(tip, 3f)
    }

    /** Kettenwurf: zieht heran (dann Rundschlag) oder hakt an Mauer und Säule fest (Fenster, Rücken frei). */
    private fun chain(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val w = BossTimings.Warden
        val dir = dirTo(target.entityPos)
        hold(dir)
        kit.act(WardenAction.CHAIN)
        // Wie weit die Kette reicht: bis zur ersten Mauer oder Säule, höchstens CHAIN_RANGE.
        val eye = entityPos.add(0.0, 1.6, 0.0)
        val far = eye.add(dir.multiply(w.CHAIN_RANGE.toDouble()))
        val hit = sw.raycast(RaycastContext(eye, far, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, self))
        val wall = hit.type == HitResult.Type.BLOCK
        val end = if (wall) hit.pos.subtract(dir.multiply(0.3)) else far
        val line = telegraph.line(ground(entityPos), ground(end), w.CHAIN_WIDTH, w.CHAIN_WINDUP, Telegraph.Pace.HEAVY)
        telegraph.sound(HhaSounds.CHAIN, entityPos, 2f, 0.7f)
        wait(line.ticks)
        val caught = line.hits(fighters()).minByOrNull { it.squaredDistanceTo(self) }
        val taut = w.CHAIN_PULL - w.CHAIN_WINDUP
        Vfx.chain(sw, danger, self, caught?.entityPos?.add(0.0, 1.0, 0.0) ?: end, caught, taut + 6)
        telegraph.sound(SoundEvents.BLOCK_CHAIN_BREAK, entityPos, 2f, 0.6f)
        if (caught != null) {
            hit(caught, 6f)
            wait(taut)
            // Der Ruck: heran vor das Beil, dann sofort der Rundschlag.
            yank(caught)
            wait(4)
            lastMove = WardenAction.ROUND
            yieldAll(round(caught))
            return@sequence
        }
        wait(taut)
        if (wall) {
            // Der Haken hängt fest (F 40): Er stemmt sich dagegen, die Laterne auf dem Rücken ist frei.
            kit.act(WardenAction.HOOKED)
            hint("hha.guardian.hint.lantern")
            Vfx.chain(sw, danger, self, end, null, w.HOOKED_WINDOW)
            telegraph.sound(SoundEvents.BLOCK_ANVIL_LAND, end, 1.2f, 0.6f)
            if (window(w.HOOKED_WINDOW) { staggerDue }) {
                yieldAll(stagger())
                return@sequence
            }
            kit.act(0)
            return@sequence
        }
        wait(w.CHAIN_END - w.CHAIN_PULL)
        kit.act(0)
    }

    private fun yank(player: ServerPlayerEntity) {
        // Zwischen Treffer und Ruck liegen Ticks: Wer inzwischen tot, weg oder in einer anderen Welt ist, bleibt, wo er ist.
        if (!player.isAlive || player.isRemoved || player.entityWorld !== entityWorld) return
        val dest = entityPos.add(dirTo(player.entityPos).multiply(2.2 + width * 0.5))
        val pull = Vec3d(dest.x - player.x, 0.0, dest.z - player.z).multiply(0.16)
        val capped = if (pull.length() > 2.4) pull.normalize().multiply(2.4) else pull
        Fx.launchPlayer(player, capped.add(0.0, 0.42, 0.0))
    }

    /** Rundschlag: Kegel nach vorn, danach Nachlauf (F 20). */
    private fun round(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val w = BossTimings.Warden
        val dir = dirTo(target.entityPos)
        hold(dir)
        kit.act(WardenAction.ROUND)
        val cone = telegraph.cone(ground(entityPos), dir, w.ROUND_RADIUS, w.ROUND_HALF_ANGLE, w.ROUND_WINDUP, Telegraph.Pace.QUICK)
        telegraph.sound(HhaSounds.WHOOSH, entityPos, 2f, 0.6f)
        wait(cone.ticks)
        val struck = cone.hits(fighters())
        for (player in struck) {
            hit(player, 14f)
            knock(player, dirTo(player.entityPos), 0.9, 0.4)
        }
        Vfx.crescent(sw, danger, entityPos.add(0.0, 1.4, 0.0), dir, w.ROUND_RADIUS, 4f, 10)
        if (struck.isNotEmpty()) telegraph.hitShake(entityPos, 3f)
        if (window(w.ROUND_END - w.ROUND_WINDUP) { staggerDue }) {
            yieldAll(stagger())
            return@sequence
        }
        kit.act(0)
    }

    /** Unter 40 %: Er brüllt, ab jetzt fällt das Richtbeil zweimal. */
    private fun rage(): Sequence<Unit> = sequence {
        hold(prey?.let { dirTo(it.entityPos) })
        kit.act(WardenAction.RAGE)
        telegraph.sound(HhaSounds.ROAR, entityPos, 3f, 0.6f)
        Vfx.nova(sw, danger, entityPos.add(0.0, height * 0.6, 0.0), 6f, 14)
        telegraph.roomShake(entityPos, 5f)
        wait(BossTimings.Warden.RAGE)
        kit.act(0)
    }

    /** Die Laterne ist zerschlagen: Er sackt auf ein Knie (offen). */
    private fun stagger(): Sequence<Unit> = sequence {
        staggerDue = false
        hold(null)
        kit.act(WardenAction.STAGGER)
        telegraph.sound(SoundEvents.BLOCK_GLASS_BREAK, entityPos, 2f, 0.6f)
        open(BossTimings.Warden.STAGGER, OPEN_FACTOR)
        wait(BossTimings.Warden.STAGGER)
        close()
        kit.act(0)
    }

    companion object {
        /** PLAN 4.1: 360 → 800. */
        const val HEALTH = 800.0
        private const val ENRAGE = 0.4f

        fun attributes(): DefaultAttributeContainer.Builder = guardianAttributes()
    }
}

// =================================================================================================
// Sisyphos
// =================================================================================================

/**
 * Sisyphos auf seinem Hügel. Der Fels im Nacken ist seine Waffe und seine Rüstung zugleich.
 *
 * - **Felswurf** (fern): Kreis am Ziel, der Fels bleibt 8 s liegen. **Rollender Fels** (mittel): Linie 4 × 24.
 *   **Stampfer** (nah): Kreis Radius 6.
 * - **Ohne Fels ist er offen** (× 1,5), bis ein neuer nachgewachsen ist.
 * - Ab 40 %: **„Der Stein rollt zurück“**: Drei Felsen rollen nacheinander vom Gipfel auf die Spieler zu, während er
 *   weiterkämpft. Trifft einer Sisyphos selbst: 30 Schaden und Taumeln.
 */
class SisyphusEntity(type: EntityType<out SisyphusEntity>, world: World) : TartarosGuardian(type, world) {

    override val kit = MiniBossKit(this, "entity.hha.sisyphus", BossBar.Color.YELLOW, Vfx.Style.FIRE)
    override val fightName = "sisyphus"
    override val danger = Vfx.Style.FIRE
    override val link get() = TartarosChain.Link.SISYPHUS
    override val baseHealth get() = HEALTH

    /** Der Gipfel, von dem der Fluch die Felsen schickt (Standard: die Mitte des Platzes). */
    var summit: Vec3d? = null

    private var hasRock = true
    private var cursed = false
    private var nextCurse = 0L
    private var staggering = false

    init {
        kit.stats(HEALTH, 8.0, 12.0, 0.25)
    }

    override fun guardianMoves() = listOf(
        Move(SisyphusAction.THROW, 7.0..22.0, 3, { hasRock }) { throwRock(it) },
        Move(SisyphusAction.BOWL, 3.5..16.0, 3, { hasRock }) { bowl(it) },
        Move(SisyphusAction.STOMP, 0.0..5.0, 2, { hasRock }) { stomp(it) },
    )

    override fun phaseEvent(): Sequence<Unit>? {
        if (!cursed && health < maxHealth * CURSE_AT) {
            cursed = true
            return curse()
        }
        if (cursed && sw.time >= nextCurse && telegraph.canStartHazard()) return curse()
        return null
    }

    override fun onReset() {
        hasRock = true
        staggering = false
    }

    /** Ohne Fels ist er offen (Plan 4.6), ab dem Abwurf und nicht erst, wenn die Erholung beginnt. */
    override fun hitFactor(source: DamageSource): Float = if (!hasRock && source.attacker is PlayerEntity) OPEN_FACTOR else 1f

    /** Felswurf: Ansage am Ziel bis zum Einschlag, Abwurf bei THROW_WINDUP, dann ohne Fels (REGROW, offen). */
    private fun throwRock(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val s = BossTimings.Sisyphus
        val dir = dirTo(target.entityPos)
        hold(dir)
        kit.act(SisyphusAction.THROW)
        val spot = ground(target.entityPos)
        val flight = (flat(entityPos, spot) / 1.1).roundToInt().coerceIn(s.THROW_FLIGHT_MIN, s.THROW_FLIGHT_MAX)
        val ring = telegraph.ring(spot, s.THROW_RADIUS, s.THROW_WINDUP + flight, pace = Telegraph.Pace.PROJECTILE)
        telegraph.sound(SoundEvents.ENTITY_IRON_GOLEM_ATTACK, entityPos, 1.6f, 0.5f)
        wait(s.THROW_WINDUP)
        // Abwurf: Ab hier ist der Fels nicht mehr auf seinem Nacken (das Modell blendet ihn aus).
        hasRock = false
        launch(thrown(spot, ring))
        wait(s.THROW_END - s.THROW_WINDUP)
        // REGROW im selben Tick, in dem THROW endet (Anfrage sisyphus.md), sonst blitzt der Fels einen Tick auf.
        yieldAll(regrow())
    }

    private fun thrown(spot: Vec3d, ring: Telegraph.Ring): Sequence<Unit> = sequence {
        val s = BossTimings.Sisyphus
        val from = entityPos.add(0.0, height + 0.4, 0.0)
        val rock = prop(ScriptedBolt.spawn(sw, self, HermesBoltEntity.BOULDER, 1f, from)) ?: return@sequence
        val goal = spot.add(0.0, ROCK_RADIUS, 0.0)
        val ticks = max(1, ring.remaining)
        val lift = 2.5 + flat(from, goal) * 0.18
        for (i in 1..ticks) {
            rock.moveTo(arc(from, goal, i.toDouble() / ticks, lift))
            yield(Unit)
        }
        // Einschlag: genau ein Flächeneffekt, ein kurzes Rütteln.
        val struck = ring.hits(fighters())
        for (player in struck) {
            // PLAN 4.3: Der Felswurf ist ein Einschlag von oben und trifft auch in der Luft.
            hit(player, 14f, BossDefense.Reach.SKY, spot.y)
            knock(player, player.entityPos.subtract(spot), 0.4, 0.55)
        }
        Vfx.shockwave(sw, danger, spot, s.THROW_RADIUS, 14, 1.6f)
        telegraph.hitShake(spot, 3f)
        telegraph.sound(SoundEvents.ENTITY_GENERIC_EXPLODE.value(), spot, 1.2f, 0.7f)
        rock.rest()
        // Der Fels bleibt 8 s liegen, dann zerfällt er.
        wait(s.BOULDER_LIE)
        crumble(rock)
    }

    /** Ohne Fels: Er richtet sich auf, keucht, ein neuer wächst nach. So lange ist er offen. */
    private fun regrow(): Sequence<Unit> = sequence {
        val s = BossTimings.Sisyphus
        kit.act(SisyphusAction.REGROW)
        hold(null)
        hint("hha.guardian.hint.boulder")
        open(s.REGROW, OPEN_FACTOR)
        wait(s.REGROW)
        close()
        hasRock = true
        kit.act(0)
    }

    /** Rollender Fels: Linie 4 × 24 ab seinen Händen, der Fels rollt sie entlang. */
    private fun bowl(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val s = BossTimings.Sisyphus
        val dir = dirTo(target.entityPos)
        hold(dir)
        kit.act(SisyphusAction.BOWL)
        val from = entityPos.add(dir.multiply(width * 0.5 + 1.0))
        val to = from.add(dir.multiply(s.BOWL_LENGTH.toDouble()))
        val group = telegraph.boss(pace = Telegraph.Pace.HEAVY)
        val ticks = terrainLine(group, from, to, s.BOWL_WIDTH, s.BOWL_WINDUP)
        telegraph.sound(SoundEvents.BLOCK_GRINDSTONE_USE, entityPos, 1.6f, 0.5f)
        wait(ticks)
        hasRock = false
        launch(roll(from, to, 18f, cursedRock = false))
        wait(s.BOWL_END - s.BOWL_WINDUP)
        yieldAll(regrow())
    }

    /** Stampfer: Kreis Radius 6 um ihn. */
    private fun stomp(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val s = BossTimings.Sisyphus
        hold(dirTo(target.entityPos))
        kit.act(SisyphusAction.STOMP)
        val ring = telegraph.ring(ground(entityPos), s.STOMP_RADIUS, s.STOMP_WINDUP)
        telegraph.sound(SoundEvents.ENTITY_RAVAGER_STEP, entityPos, 2f, 0.5f)
        wait(ring.ticks)
        val struck = ring.hits(fighters())
        for (player in struck) {
            hit(player, 12f)
            knock(player, dirTo(player.entityPos), 0.5, 0.8)
        }
        Vfx.shockwave(sw, danger, ground(entityPos), s.STOMP_RADIUS, 14, 2f)
        telegraph.hitShake(entityPos, 3f)
        telegraph.sound(SoundEvents.ENTITY_GENERIC_EXPLODE.value(), entityPos, 1.2f, 0.7f)
        wait(s.STOMP_END - s.STOMP_WINDUP)
        kit.act(0)
    }

    /** „Der Stein rollt zurück“: Er schreit zum Gipfel, von dort kommen drei Felsen (Saal-Gefahr). */
    private fun curse(): Sequence<Unit> = sequence {
        val s = BossTimings.Sisyphus
        nextCurse = sw.time + s.CURSE_INTERVAL
        val hazard = telegraph.hazard(reserve = s.CURSE + s.CURSE_WINDUP)
        if (hazard == null) {
            // Der Platz für eine Saal-Gefahr ist belegt: später erneut.
            nextCurse = sw.time + 100
            return@sequence
        }
        hold(null)
        kit.act(SisyphusAction.CURSE)
        hint("hha.guardian.hint.curse")
        telegraph.sound(HhaSounds.ROAR, entityPos, 3f, 0.7f)
        launch(curseRocks(hazard))
        wait(s.CURSE)
        kit.act(0)
    }

    private fun curseRocks(group: Telegraph.Fight.Group): Sequence<Unit> = sequence {
        val s = BossTimings.Sisyphus
        wait(10)
        repeat(s.CURSE_ROCKS) { i ->
            val crowd = fighters()
            if (crowd.isEmpty() || !isAlive) return@sequence
            val target = crowd[i % crowd.size]
            val top = summit ?: anchor()
            val dir = flatNorm(target.entityPos.subtract(top))
            // Steht der Spieler selbst oben, kommt der Fels vom Rand des Gipfels her.
            val start = if (flat(top, target.entityPos) < 6.0) target.entityPos.subtract(dir.multiply(9.0)) else top
            val length = min(s.CURSE_LENGTH.toDouble(), flat(start, target.entityPos) + 10.0)
            val end = start.add(dir.multiply(length))
            val ticks = terrainLine(group, start, end, s.BOWL_WIDTH, s.CURSE_WINDUP)
            telegraph.sound(SoundEvents.BLOCK_GRINDSTONE_USE, start, 2f, 0.4f)
            group.hold(ticks + ceil(length / s.ROLL_SPEED).toInt() + 4)
            launch(sequence {
                wait(ticks - 1)
                yieldAll(roll(start, end, 16f, cursedRock = true))
            })
            wait(s.CURSE_GAP)
        }
    }

    /**
     * Ein Fels rollt von [from] nach [to] am Boden entlang (folgt dem Gelände, zerschellt an Mauern). Wer in der Linie
     * steht, wenn er vorbeikommt, wird getroffen; ein Fluch-Fels trifft auch Sisyphos selbst.
     */
    private fun roll(from: Vec3d, to: Vec3d, base: Float, cursedRock: Boolean): Sequence<Unit> = sequence {
        val s = BossTimings.Sisyphus
        val dir = flatNorm(to.subtract(from))
        val length = flat(from, to)
        var level = floorAt(from, 8.0)
        val rock = prop(ScriptedBolt.spawn(sw, self, HermesBoltEntity.BOULDER, 1f, Vec3d(from.x, level + ROCK_RADIUS, from.z))) ?: return@sequence
        telegraph.sound(HhaSounds.WHOOSH, from, 2f, 0.5f)
        val struck = HashSet<UUID>()
        var travelled = 0.0
        var crushedHim = false
        while (travelled < length) {
            travelled = min(length, travelled + s.ROLL_SPEED)
            val p = from.add(dir.multiply(travelled))
            val floor = floorOrNull(Vec3d(p.x, level + 3.0, p.z), 14.0) ?: break
            if (floor - level > 1.2) break
            level = floor
            rock.moveTo(Vec3d(p.x, level + ROCK_RADIUS, p.z))
            for (player in fighters()) {
                if (player.uuid in struck) continue
                if (abs(along(player.entityPos, from, dir) - travelled) > 1.6 || abs(player.y - level) > 2.5) continue
                if (!Telegraph.inLine(player.entityPos, from, to, s.BOWL_WIDTH.toDouble(), player.width * 0.5)) continue
                struck.add(player.uuid)
                hit(player, base, BossDefense.Reach.BODY, level)
                knock(player, dir, 1.1, 0.55)
            }
            if (cursedRock && !crushedHim && isAlive && abs(along(entityPos, from, dir) - travelled) < 1.6 &&
                abs(this@SisyphusEntity.y - level) < 3.0 && Telegraph.inLine(entityPos, from, to, s.BOWL_WIDTH.toDouble(), width * 0.5)
            ) {
                crushedHim = true
                crushed()
            }
            yield(Unit)
        }
        crumble(rock)
    }

    private fun crumble(rock: ScriptedBolt) {
        if (!rock.alive) return
        Fx.burst(sw, BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.BLACKSTONE.defaultState), rock.pos, 24, 0.9, 0.15)
        telegraph.sound(SoundEvents.BLOCK_DEEPSLATE_BREAK, rock.pos, 1.4f, 0.7f)
        rock.discard()
    }

    /** Vom eigenen Fels getroffen: 30 Schaden, Taumeln. Der Lohn dafür, ihn in die Bahn gelockt zu haben. */
    private fun crushed() {
        Vfx.flare(sw, Vfx.Style.GOLD, entityPos.add(0.0, height * 0.6, 0.0), 2.4f, 8)
        telegraph.sound(HhaSounds.IMPACT, entityPos, 2.5f, 0.6f)
        // 30 × H(n) effektiv: in Leistenpunkten durch die Zähigkeit teilen (Kettenbonus über 1024, Review R2 m2).
        hurtSelf(SELF_HIT / toughness())
        if (!isAlive || staggering) return
        interrupt(stagger())
    }

    private fun stagger(): Sequence<Unit> = sequence {
        val s = BossTimings.Sisyphus
        staggering = true
        hold(null)
        kit.act(SisyphusAction.STAGGER)
        open(s.STAGGER, OPEN_FACTOR)
        wait(s.STAGGER)
        close()
        // Beim Aufstehen kehrt sein Fels auf den Nacken zurück (Modell).
        hasRock = true
        staggering = false
        kit.act(0)
    }

    companion object {
        /** PLAN 4.1: 340 → 750. */
        const val HEALTH = 750.0
        private const val CURSE_AT = 0.4f

        /**
         * Vom eigenen Fels getroffen: 30 Punkte der Lebensleiste. Weil die Leiste eingehenden Schaden durch `H(n)` teilt,
         * entspricht das 30 · H(n) Schaden: Die Schwelle wächst mit der Spielerzahl (PLAN 4.1).
         */
        private const val SELF_HIT = 30f

        /** Halber Durchmesser des fliegenden Felsens (Knochen `boulder`, Faktor 0,69). */
        private const val ROCK_RADIUS = 0.78

        fun attributes(): DefaultAttributeContainer.Builder = guardianAttributes()
    }
}

// =================================================================================================
// Ixion
// =================================================================================================

/**
 * Ixion, auf sein Feuerrad gebunden; er schwebt und rollt mit ihm über die Plattform.
 *
 * - **Feuerrad**: rollt eine Linie (Breite 3) quer über die Plattform und lässt eine Feuerspur; danach **kippt das Rad**
 *   (30 Ticks). **Speichenfeuer**: Rad waagerecht, sechs Linien, dann drehen sechs Strahlen langsam. **Funkenwurf**:
 *   drei Kreise nacheinander.
 * - **Fesseln:** Jedes genutzte Kipp-Fenster (mindestens ein Treffer) lockert eine Fessel. Nach drei fällt Ixion vom
 *   Rad: 100 Ticks am Boden, Treffer × 2.
 */
class IxionEntity(type: EntityType<out IxionEntity>, world: World) : TartarosGuardian(type, world) {

    override val kit = MiniBossKit(this, "entity.hha.ixion", BossBar.Color.RED, Vfx.Style.FIRE)
    override val fightName = "ixion"
    override val danger = Vfx.Style.FIRE
    override val link get() = TartarosChain.Link.IXION
    override val baseHealth get() = HEALTH

    /** Kampffläche als Ring um [home] (Plattform ohne das Rad in der Mitte); 0 = frei. */
    private var arenaInner = 0.0
    private var arenaOuter = 0.0

    private var hover = BossTimings.Ixion.HOVER
    private var grounded = false
    private var tipping = false
    private var tipHits = 0

    init {
        setNoGravity(true)
        kit.stats(HEALTH, 6.0, 9.0, 0.3)
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(FETTERS_ID, 0)
    }

    /** Gelockerte Fesseln (0–3), für das Modell synchronisiert (`GuardianRenderState.fetters`). */
    var looseFetters: Int
        get() = dataTracker.get(FETTERS_ID)
        set(value) = dataTracker.set(FETTERS_ID, value.coerceIn(0, BossTimings.Ixion.FETTERS))

    /** Setzt die Kampffläche (Ring zwischen [inner] und [outer] um [home]). */
    fun arena(inner: Double, outer: Double) {
        arenaInner = inner
        arenaOuter = outer
    }

    override fun closeRange(): Double = 6.0

    /**
     * Er schwebt und lenkt selbst (die Vanilla-Pfadsuche hält ein Wesen 1,4 Blöcke über dem Boden für „nicht
     * angekommen“). Auf der Plattform bleibt er auf dem Ring und fährt um das Rad in der Mitte herum.
     */
    override fun approach(target: ServerPlayerEntity, distance: Double) {
        hold(dirTo(target.entityPos))
        if (distance <= closeRange()) stopMoving() else steer(target.entityPos, 1.0)
    }

    override fun walkTo(goal: Vec3d, speed: Double) {
        steer(goal, speed)
        if (velocity.horizontalLengthSquared() > 1e-4) hold(velocity)
    }

    /** Setzt die waagerechte Geschwindigkeit auf [goal] zu (die Höhe regelt [tickBody]). */
    private fun steer(goal: Vec3d, speed: Double) {
        if (grounded) return
        var aim = goal
        if (arenaOuter > 0.0) {
            val c = anchor()
            val r = flat(entityPos, c).coerceIn(arenaInner + 1.0, arenaOuter - 1.0)
            val from = kotlin.math.atan2(z - c.z, x - c.x)
            val to = kotlin.math.atan2(goal.z - c.z, goal.x - c.x)
            val turn = Math.IEEEremainder(to - from, 2.0 * Math.PI)
            aim = if (abs(turn) > RING_STEP) {
                // Um die Mitte herum: ein Stück den Ring entlang.
                val a = from + Math.signum(turn) * RING_STEP
                Vec3d(c.x + cos(a) * r, goal.y, c.z + sin(a) * r)
            } else {
                val gr = flat(goal, c).coerceIn(arenaInner + 1.0, arenaOuter - 1.0)
                Vec3d(c.x + cos(to) * gr, goal.y, c.z + sin(to) * gr)
            }
        }
        val d = Vec3d(aim.x - x, 0.0, aim.z - z)
        val length = d.length()
        if (length < 0.3) {
            stopMoving()
            return
        }
        val v = d.multiply(min(length, GLIDE * speed) / length)
        velocity = Vec3d(v.x, velocity.y, v.z)
    }

    override fun guardianMoves() = listOf(
        Move(IxionAction.ROLL, 5.0..24.0, 3) { roll(it) },
        Move(IxionAction.SPOKES, 0.0..9.0, 2) { spokes(it) },
        Move(IxionAction.SPARKS, 6.0..22.0, 2) { sparks(it) },
    )

    override fun onReset() {
        hover = BossTimings.Ixion.HOVER
        grounded = false
        tipping = false
        setNoGravity(true)
    }

    /** Schweben: zieht sich jeden Tick sanft auf die Schwebehöhe über dem Boden. */
    override fun tickBody() {
        if (grounded) return
        val want = floorAt(entityPos, 8.0) + hover
        velocity = Vec3d(velocity.x, ((want - y) * 0.3).coerceIn(-0.35, 0.35), velocity.z)
    }

    override fun onDamaged(source: DamageSource, lost: Float, factor: Float) {
        if (tipping && source.attacker is PlayerEntity) tipHits++
    }

    /** Feuerrad: Linie quer über die Plattform, er rollt sie entlang, die Spur brennt nach; dann kippt das Rad. */
    private fun roll(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val ix = BossTimings.Ixion
        val path = rollPath(target)
        if (path == null) {
            // Kein Platz zum Rollen (Mauer, Rad in der Mitte): dann eben Funken.
            yieldAll(sparks(target))
            return@sequence
        }
        val (from, to) = path
        val dir = flatNorm(to.subtract(from))
        hold(dir)
        kit.act(IxionAction.ROLL_WINDUP)
        hover = 0.1
        val line = telegraph.line(ground(from), ground(to), ix.ROLL_WIDTH, ix.ROLL_WINDUP)
        telegraph.sound(HhaSounds.CHARGE, entityPos, 2f, 0.7f)
        wait(line.ticks)
        kit.act(IxionAction.ROLL)
        telegraph.sound(SoundEvents.ITEM_FIRECHARGE_USE, entityPos, 2f, 0.6f)
        val length = flat(from, to)
        val steps = max(1, ceil(length / ix.ROLL_SPEED).toInt())
        Vfx.trail(sw, danger, self, 1.2f, steps + 10)
        val struck = HashSet<UUID>()
        for (i in 1..steps) {
            val travelled = length * i / steps
            val p = from.add(dir.multiply(travelled))
            setPosition(p.x, floorAt(p, 4.0) + 0.1, p.z)
            velocity = Vec3d.ZERO
            for (player in fighters()) {
                if (player.uuid in struck) continue
                if (abs(along(player.entityPos, from, dir) - travelled) > 1.8 || abs(player.y - y) > 3.0) continue
                if (!Telegraph.inLine(player.entityPos, from, to, ix.ROLL_WIDTH.toDouble(), player.width * 0.5)) continue
                struck.add(player.uuid)
                hit(player, 14f, BossDefense.Reach.BODY, y)
                player.setOnFireFor(3f)
                val side = Vec3d(-dir.z, 0.0, dir.x).multiply(if (along(player.entityPos, from, Vec3d(-dir.z, 0.0, dir.x)) >= 0) 1.0 else -1.0)
                knock(player, side, 0.9, 0.4)
            }
            yield(Unit)
        }
        launch(fireTrail(ground(from), ground(to)))
        yieldAll(wobble())
    }

    /** Die längste freie Bahn auf das Ziel zu (leicht gedreht, falls nötig); `null`, wenn keine lang genug ist. */
    private fun rollPath(target: ServerPlayerEntity): Pair<Vec3d, Vec3d>? {
        val ix = BossTimings.Ixion
        val from = entityPos
        val toward = dirTo(target.entityPos)
        var best: Pair<Vec3d, Vec3d>? = null
        var bestLength = 0.0
        for (turn in doubleArrayOf(0.0, 15.0, -15.0, 30.0, -30.0)) {
            val dir = rotate(toward, turn)
            val length = clearRun(from, dir, ix.ROLL_LENGTH.toDouble())
            if (length >= ix.ROLL_MIN && length > bestLength + 2.0) {
                best = Pair(from, from.add(dir.multiply(length)))
                bestLength = length
                if (turn == 0.0 && length >= flatDistance(target) + 3.0) break
            }
        }
        return best
    }

    private fun clearRun(from: Vec3d, dir: Vec3d, limit: Double): Double {
        val box = boundingBox.offset(-x, -y, -z).contract(0.1)
        val start = floorAt(from, 4.0)
        var s = 0.0
        while (s + 0.5 <= limit) {
            val p = from.add(dir.multiply(s + 0.5))
            if (!inArena(p)) break
            val g = floorAt(p, 4.0)
            if (abs(g - start) > 1.1) break
            if (!entityWorld.isSpaceEmpty(self, box.offset(p.x, g + 0.15, p.z))) break
            s += 0.5
        }
        return s
    }

    private fun inArena(p: Vec3d): Boolean {
        if (arenaOuter <= 0.0) return true
        val r = flat(p, anchor())
        return r in arenaInner..arenaOuter
    }

    /** Die Feuerspur brennt 60 Ticks nach: alle 10 Ticks Schaden für den, der darin steht. */
    private fun fireTrail(from: Vec3d, to: Vec3d): Sequence<Unit> = sequence {
        val ix = BossTimings.Ixion
        for (t in 0 until ix.FIRE_TRAIL) {
            if (t % 2 == 0) {
                repeat(3) {
                    val p = from.lerp(to, random.nextDouble())
                    Fx.spawn(sw, ParticleTypes.FLAME, p.x, p.y + 0.1, p.z, 2, 0.6, 0.05, 0.6, 0.01)
                }
            }
            if (t % ix.FIRE_TICK == 0) {
                for (player in fighters()) {
                    if (abs(player.y - from.y) > 1.6) continue
                    if (!Telegraph.inLine(player.entityPos, from, to, ix.ROLL_WIDTH.toDouble(), player.width * 0.5)) continue
                    hit(player, 3f, BossDefense.Reach.BODY, from.y)
                    player.setOnFireFor(1f)
                }
            }
            yield(Unit)
        }
    }

    /** Das Rad kippt wie eine auslaufende Münze (F 30). Ein genutztes Fenster lockert eine Fessel. */
    private fun wobble(): Sequence<Unit> = sequence {
        val ix = BossTimings.Ixion
        kit.act(IxionAction.WOBBLE)
        hint("hha.guardian.hint.wheel")
        tipping = true
        tipHits = 0
        open(ix.WOBBLE_WINDOW, 1f)
        wait(ix.WOBBLE_WINDOW)
        tipping = false
        close()
        if (tipHits > 0) {
            looseFetters = looseFetters + 1
            telegraph.sound(SoundEvents.BLOCK_CHAIN_BREAK, entityPos, 2.4f, 0.5f)
        }
        if (looseFetters >= ix.FETTERS) {
            // Die letzte Fessel reißt: direkt vom kippenden Rad in FALLEN, ohne Aufrichten.
            yieldAll(fall())
            return@sequence
        }
        wait(ix.WOBBLE_RIGHT)
        hover = ix.HOVER
        hold(null)
        kit.act(0)
    }

    /** Alle Fesseln los: Er stürzt vom Rad und liegt am Boden (× 2), dann reißt ihn das Rad wieder hoch. */
    private fun fall(): Sequence<Unit> = sequence {
        val ix = BossTimings.Ixion
        hold(null)
        kit.act(IxionAction.FALLEN)
        grounded = true
        setNoGravity(false)
        hint("hha.guardian.hint.fallen")
        telegraph.sound(HhaSounds.IMPACT, entityPos, 2.5f, 0.6f)
        open(ix.FALLEN, FALLEN_FACTOR)
        wait(ix.FALLEN)
        close()
        kit.act(IxionAction.RISE)
        looseFetters = 0
        grounded = false
        setNoGravity(true)
        hover = ix.HOVER
        telegraph.sound(SoundEvents.BLOCK_CHAIN_PLACE, entityPos, 2f, 0.6f)
        wait(ix.RISE)
        kit.act(0)
    }

    /** Speichenfeuer: in die Mitte, Rad waagerecht, sechs Linien; dann drehen die Strahlen eine Dreiviertelrunde. */
    private fun spokes(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val ix = BossTimings.Ixion
        val center = spokeCenter(target)
        var travel = 0
        while (flat(entityPos, center) > 1.2 && travel < ix.SPOKES_TRAVEL) {
            walkTo(center, 1.3)
            yield(Unit)
            travel++
        }
        stopMoving()
        hover = ix.HOVER_SPOKES
        val face = facing()
        hold(face)
        kit.act(IxionAction.SPOKES)
        val hub = ground(entityPos)
        val start = Math.toDegrees(kotlin.math.atan2(face.z, face.x))
        val step = 360.0 / ix.SPOKES_LINES
        fun ray(angle: Double): Pair<Vec3d, Vec3d> {
            val a = Math.toRadians(angle)
            val d = Vec3d(cos(a), 0.0, sin(a))
            return Pair(hub.add(d.multiply(ix.SPOKES_INNER.toDouble())), hub.add(d.multiply(ix.SPOKES_LENGTH.toDouble())))
        }
        val group = telegraph.boss(pace = Telegraph.Pace.AREA)
        for (i in 0 until ix.SPOKES_LINES) {
            val (a, b) = ray(start + i * step)
            group.line(a, b, ix.SPOKES_WIDTH, ix.SPOKES_WINDUP)
        }
        telegraph.sound(SoundEvents.ENTITY_BLAZE_BURN, entityPos, 2f, 0.6f)
        wait(group.ticks)
        // Die Strahlen drehen von oben gesehen im Uhrzeigersinn, wie das Rad im Modell (Anfrage ixion.md).
        val lastHit = HashMap<UUID, Long>()
        for (t in 0 until ix.SPOKES_SWEEP) {
            val turn = start + ix.SPOKES_TURN * 360.0 * t / ix.SPOKES_SWEEP
            if (t % 3 == 0) {
                for (i in 0 until ix.SPOKES_LINES) {
                    val (a, b) = ray(turn + i * step)
                    Vfx.beam(sw, danger, a.add(0.0, 0.5, 0.0), b.add(0.0, 0.5, 0.0), 0.8f, 4)
                }
            }
            for (player in fighters()) {
                if (abs(player.y - hub.y) > 2.6) continue
                if (sw.time - (lastHit[player.uuid] ?: Long.MIN_VALUE / 2) < ix.FIRE_TICK) continue
                val inBeam = (0 until ix.SPOKES_LINES).any { i ->
                    val (a, b) = ray(turn + i * step)
                    Telegraph.inLine(player.entityPos, a, b, ix.SPOKES_WIDTH.toDouble(), player.width * 0.5)
                }
                if (!inBeam) continue
                lastHit[player.uuid] = sw.time
                hit(player, 8f, BossDefense.Reach.BODY, hub.y)
                player.setOnFireFor(2f)
            }
            yield(Unit)
        }
        wait(ix.SPOKES_RIGHT)
        hover = ix.HOVER
        hold(null)
        kit.act(0)
        wait(14)
    }

    /** Wo das Speichenfeuer steht: auf dem Ring der Plattform beim Ziel, sonst wo er ist. */
    private fun spokeCenter(target: ServerPlayerEntity): Vec3d {
        if (arenaOuter <= 0.0) return entityPos
        val c = anchor()
        val dir = flatNorm(target.entityPos.subtract(c))
        val r = (arenaInner + arenaOuter) * 0.5
        return Vec3d(c.x + dir.x * r, entityPos.y, c.z + dir.z * r)
    }

    /** Funkenwurf: drei Kreise nacheinander am Ziel, je ein Funke im Bogen. */
    private fun sparks(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val ix = BossTimings.Ixion
        hold(dirTo(target.entityPos))
        kit.act(IxionAction.SPARKS)
        val group = telegraph.boss(pace = Telegraph.Pace.PROJECTILE)
        var tick = 0
        for (n in 0 until ix.SPARK_COUNT) {
            val appear = ix.SPARK_SHOT_1 + n * ix.SPARK_GAP - ix.SPARK_FLIGHT
            wait(appear - tick)
            tick = appear
            hold(dirTo(target.entityPos))
            val spot = ground(target.entityPos)
            launch(spark(group.ring(spot, ix.SPARK_RADIUS, ix.SPARK_WINDUP), spot))
        }
        wait(ix.SPARKS_END - tick)
        hold(null)
        kit.act(0)
    }

    private fun spark(ring: Telegraph.Ring, spot: Vec3d): Sequence<Unit> = sequence {
        val ix = BossTimings.Ixion
        // Der Schuss fällt SPARK_FLIGHT Ticks nach dem Erscheinen des Kreises (wie im Modell).
        wait(ix.SPARK_FLIGHT - 1)
        val mouth = entityPos.add(0.0, height * 0.78, 0.0).add(facing().multiply(0.9))
        val ball = prop(ScriptedBolt.spawn(sw, self, HermesBoltEntity.FIRE, 0.9f, mouth)) ?: return@sequence
        telegraph.sound(SoundEvents.ENTITY_BLAZE_SHOOT, mouth, 1.8f, 0.7f)
        val ticks = max(1, ring.remaining)
        val goal = spot.add(0.0, 0.3, 0.0)
        for (i in 1..ticks) {
            ball.moveTo(arc(mouth, goal, i.toDouble() / ticks, 2.0))
            yield(Unit)
        }
        for (player in ring.hits(fighters())) {
            hit(player, 9f, BossDefense.Reach.BODY, spot.y)
            player.setOnFireFor(2f)
        }
        Vfx.shockwave(sw, danger, spot, ix.SPARK_RADIUS, 10, 1.2f)
        ball.discard()
    }

    companion object {
        /** PLAN 4.1: 300 → 660. */
        const val HEALTH = 660.0
        private const val FALLEN_FACTOR = 2f

        /** Schwebetempo (Blöcke je Tick bei Faktor 1). */
        private const val GLIDE = 0.24

        /** So weit (Bogenmaß) fährt er je Lenkschritt den Ring entlang, wenn das Ziel auf der anderen Seite ist. */
        private const val RING_STEP = 0.5
        private val FETTERS_ID: TrackedData<Int> = DataTracker.registerData(IxionEntity::class.java, TrackedDataHandlerRegistry.INTEGER)

        fun attributes(): DefaultAttributeContainer.Builder = guardianAttributes()
    }
}

// =================================================================================================
// Tantalos
// =================================================================================================

/**
 * Tantalos im ausgetrockneten Becken, mit Dreizack und dem Kelch, aus dem er nie trinken darf.
 *
 * - **Dreizackstoß × 3** (nah): drei Linien, jeder Folgestoß mit eigener Ansage, höchstens ±20° gedreht.
 *   **Geysire** (fern): drei Kreise nacheinander unter dem Ziel, Hochwurf. **Flutwelle**: Ring bis Radius 14, darüber
 *   springen.
 * - **Der Durst:** Bei 66 % und 33 % kniet er und trinkt (heilt). 30 Schaden in dieser Zeit schlagen ihm den Kelch aus
 *   der Hand: Das Wasser weicht zurück, er taumelt (× 1,5).
 */
class TantalusEntity(type: EntityType<out TantalusEntity>, world: World) : TartarosGuardian(type, world) {

    override val kit = MiniBossKit(this, "entity.hha.tantalus", BossBar.Color.BLUE, Vfx.Style.SOUL)
    override val fightName = "tantalus"
    override val danger = Vfx.Style.SOUL
    override val link get() = TartarosChain.Link.TANTALUS
    override val baseHealth get() = HEALTH

    private var draughts = 0
    private var drinking = false
    private var spilled = 0f

    init {
        kit.stats(HEALTH, 8.0, 11.0, 0.27)
    }

    override fun guardianMoves() = listOf(
        Move(TantalusAction.THRUST3, 0.0..6.5, 3) { thrust(it) },
        Move(TantalusAction.GEYSER, 5.0..24.0, 2) { geyser(it) },
        Move(TantalusAction.WAVE, 0.0..9.0, 2) { wave(it) },
    )

    override fun phaseEvent(): Sequence<Unit>? {
        if (draughts < THIRST.size && health < maxHealth * THIRST[draughts]) {
            draughts++
            return drink()
        }
        return null
    }

    override fun onReset() {
        drinking = false
    }

    override fun onDamaged(source: DamageSource, lost: Float, factor: Float) {
        if (drinking) spilled += lost
    }

    /** Dreizackstoß × 3: erster Stoß W 12, jeder weitere mit eigener Ansage, höchstens ±20° gedreht; dann F 24. */
    private fun thrust(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val t = BossTimings.Tantalus
        var dir = dirTo(target.entityPos)
        kit.act(TantalusAction.THRUST3)
        for (n in 0 until t.THRUST_COUNT) {
            if (n > 0) dir = turnToward(dir, dirTo(target.entityPos), t.THRUST_MAX_TURN.toDouble())
            hold(dir)
            val from = ground(entityPos)
            val to = ground(entityPos.add(dir.multiply(t.THRUST_LENGTH.toDouble())))
            val line = if (n == 0) {
                telegraph.line(from, to, t.THRUST_WIDTH, t.THRUST_WINDUP, Telegraph.Pace.QUICK)
            } else {
                telegraph.line(from, to, t.THRUST_WIDTH, t.THRUST_GAP, Telegraph.Pace.FOLLOW_UP)
            }
            if (n == 0) telegraph.sound(HhaSounds.CHARGE, entityPos, 1.8f, 0.9f)
            wait(line.ticks)
            for (player in line.hits(fighters())) {
                hit(player, 12f)
                knock(player, dir, 0.6, 0.3)
            }
            Vfx.crescent(sw, danger, entityPos.add(0.0, 1.5, 0.0), dir, t.THRUST_LENGTH, 1.6f, 8)
            telegraph.sound(SoundEvents.ITEM_TRIDENT_THROW.value(), entityPos, 1.6f, 0.8f)
            // Ausfallschritt nach vorn.
            velocity = velocity.add(dir.multiply(0.35))
        }
        // Weit vorgebeugt über dem Dreizack (F 24).
        wait(t.THRUST_END - t.THRUST_HIT_3)
        hold(null)
        kit.act(0)
    }

    /** Geysire: drei Kreise nacheinander unter dem Ziel, jeder wirft hoch. */
    private fun geyser(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val t = BossTimings.Tantalus
        hold(dirTo(target.entityPos))
        kit.act(TantalusAction.GEYSER)
        val group = telegraph.boss()
        wait(t.GEYSER_RAISE)
        for (n in 0 until t.GEYSER_COUNT) {
            if (n > 0) wait(t.GEYSER_GAP)
            val spot = ground(target.entityPos)
            val ring = group.ring(spot, t.GEYSER_RADIUS, t.GEYSER_WINDUP)
            telegraph.sound(SoundEvents.BLOCK_BUBBLE_COLUMN_UPWARDS_AMBIENT, spot, 1.6f, 0.8f)
            launch(spout(ring, spot))
        }
        wait(t.GEYSER_END - t.GEYSER_RAISE - (t.GEYSER_COUNT - 1) * t.GEYSER_GAP)
        hold(null)
        kit.act(0)
    }

    private fun spout(ring: Telegraph.Ring, spot: Vec3d): Sequence<Unit> = sequence {
        wait(ring.remaining)
        for (player in ring.hits(fighters())) {
            // PLAN 4.3: Geysire sind Säulen und treffen auch in der Luft.
            if (!hit(player, 10f, BossDefense.Reach.SKY, spot.y)) continue
            Fx.launchPlayer(player, Vec3d(player.velocity.x, 1.25, player.velocity.z))
        }
        Vfx.pillar(sw, danger, spot, BossTimings.Tantalus.GEYSER_RADIUS * 0.7f, 8f, 16)
        Fx.spawn(sw, ParticleTypes.SPLASH, spot.x, spot.y + 1.0, spot.z, 24, 0.8, 1.6, 0.8, 0.3)
        telegraph.sound(SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, spot, 1.8f, 0.6f)
    }

    /** Flutwelle: Ring bis Radius 14; die Front läuft nach außen, wer in dem Moment springt, lässt sie unter sich durch. */
    private fun wave(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val t = BossTimings.Tantalus
        hold(dirTo(target.entityPos))
        kit.act(TantalusAction.WAVE)
        val center = ground(entityPos.add(facing().multiply(1.0)))
        val ring = telegraph.ring(center, t.WAVE_RADIUS, t.WAVE_WINDUP)
        telegraph.sound(SoundEvents.AMBIENT_UNDERWATER_ENTER, entityPos, 2f, 0.5f)
        wait(ring.ticks)
        Vfx.shockwave(sw, danger, center, t.WAVE_RADIUS, t.WAVE_TRAVEL + 6, 1.2f)
        telegraph.sound(HhaSounds.FLOOD, center, 2.2f, 0.8f)
        val passed = HashSet<UUID>()
        var anyHit = false
        for (step in 1..t.WAVE_TRAVEL) {
            val front = t.WAVE_RADIUS * step.toDouble() / t.WAVE_TRAVEL
            for (player in fighters()) {
                if (player.uuid in passed) continue
                val d = flat(player.entityPos, center)
                if (d > front || d > t.WAVE_RADIUS + player.width * 0.5) continue
                passed.add(player.uuid)
                if (airborne(player) || abs(player.y - center.y) > 2.0) continue
                // Flutwelle: zum Überspringen (LOW), wie die Prüfung darüber.
                if (!hit(player, 10f, BossDefense.Reach.LOW, center.y)) continue
                anyHit = true
                knock(player, player.entityPos.subtract(center), 0.9, 0.4)
            }
            yield(Unit)
        }
        if (anyHit) telegraph.hitShake(center, 3f)
        wait(t.WAVE_END - t.WAVE_WINDUP - t.WAVE_TRAVEL)
        hold(null)
        kit.act(0)
    }

    /** Der Durst: Er kniet und trinkt (heilt 2 % je 10 Ticks). 30 Schaden schlagen ihm den Kelch aus der Hand. */
    private fun drink(): Sequence<Unit> = sequence {
        val t = BossTimings.Tantalus
        hold(null)
        kit.act(TantalusAction.DRINK)
        hint("hha.guardian.hint.drink")
        telegraph.sound(SoundEvents.ENTITY_GENERIC_DRINK.value(), entityPos, 1.6f, 0.7f)
        drinking = true
        spilled = 0f
        var knocked = false
        for (tick in 1..t.DRINK) {
            // [spilled] zählt Leistenpunkte; × Zähigkeit = effektiver Schaden (Kettenbonus über 1024, Review R2 m2).
            if (spilled * toughness() >= SPILL) {
                knocked = true
                break
            }
            if (tick > t.DRINK_KNEEL && tick % t.DRINK_HEAL_TICK == 0) heal(maxHealth * HEAL_SHARE)
            yield(Unit)
        }
        drinking = false
        if (knocked) {
            // Direkt PARCHED, ohne 0 dazwischen (Anfrage tantalus.md). Das Wasser weicht zurück.
            kit.act(TantalusAction.PARCHED)
            telegraph.sound(SoundEvents.ITEM_BUCKET_EMPTY, entityPos, 2f, 0.6f)
            Fx.spawn(sw, ParticleTypes.SPLASH, x, y + 0.3, z, 24, 1.6, 0.2, 1.6, 0.2)
            open(t.PARCHED, OPEN_FACTOR)
            wait(t.PARCHED)
            close()
            kit.act(0)
        } else {
            kit.act(TantalusAction.RAGE)
            telegraph.sound(HhaSounds.ROAR, entityPos, 2.5f, 0.9f)
            wait(t.RAGE)
            kit.act(0)
        }
    }

    companion object {
        /** PLAN 4.1: 320 → 700. */
        const val HEALTH = 700.0

        /** Bei diesen Lebensanteilen trinkt er. */
        private val THIRST = floatArrayOf(0.66f, 0.33f)

        /**
         * So viel Schaden in der Trinkzeit schlägt ihm den Kelch aus der Hand: 30 Punkte der Lebensleiste. Die Leiste teilt
         * eingehenden Schaden durch `H(n)`, also braucht es 30 · H(n) Schaden (PLAN 4.1: Schwelle × H(n)).
         */
        private const val SPILL = 30f
        private const val HEAL_SHARE = 0.02f

        fun attributes(): DefaultAttributeContainer.Builder = guardianAttributes()
    }
}
