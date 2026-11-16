package dev.henny.hha.entity.boss

import dev.henny.hha.HhaEntities
import dev.henny.hha.entity.miniboss.GuardianEntity
import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.BossRewards
import dev.henny.hha.logic.boss.BossScaling
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.DuelCinematics
import dev.henny.hha.logic.world.OlympusPath
import dev.henny.hha.logic.world.OlympusProgress
import dev.henny.hha.world.olymp.OlympusLayout
import net.minecraft.entity.EntityType
import net.minecraft.entity.Tameable
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.min

/**
 * Gemeinsame Basis der neuen Bosse des Olymp (Hekate, Brontes, Kratos, Zeus; Überarbeitung 2, PLAN 4.4). Erbt das
 * Drehbuch der Wächter ([GuardianEntity]: Angriffe als [Move], Auswahl nach Entfernung, nie zweimal derselbe, offene
 * Fenster) samt Skalierung, Deckel ([BossScaling.Tier.BOSS]: 4 % / 6 %) und Abwehr (Konter mit [antiAirAction] und
 * [antiAirDamage]) und ergänzt:
 *
 * - **Arena** ([Arenas]): Kampffläche aus [OlympusLayout]; Blockschutz, kein Fallschaden, Bö am Rand, K. o. statt Tod.
 *   Verlassen alle die Arena oder gehen k. o., verschwindet der Boss ([abort]), der Altar ist wieder scharf.
 * - **Phasen:** Lebensschwellen [phases] (Anteile, absteigend). Erreicht das Leben eine Schwelle, nimmt er bis zum
 *   Wechsel keinen Schaden mehr; nach dem laufenden Angriff läuft [onPhase]. [phase] ist synchronisiert (RenderState).
 *   Geteilte Lebensleiste über [Cine.phaseBar].
 * - **Leben:** [effectiveHealth] effektiv; das Lebensattribut setzt die Unterklasse über `kit.stats`, die Zähigkeit ist
 *   der Quotient. Er stirbt nie: Bei der letzten Schwelle (1 Leben) folgt [victory].
 * - **Sieg:** [finishVictory] gibt Beute ([BossRewards.grant]), merkt den Fortschritt ([OlympusProgress.markDone]) und
 *   zeigt die goldene Fernsäule zum nächsten Ziel ([OlympusPath.pointNext]).
 * - Start über [startAt] (Altar `hha:boss_altar` oder `/hha boss start <name>`).
 */
abstract class DuelBossEntity(type: EntityType<out DuelBossEntity>, world: World) : GuardianEntity(type, world), Arenas.Owner {

    /** Boss- und Fortschritts-Schlüssel (`hekate`, `brontes`, `kratos`, `zeus`); Sprachschlüssel `hha.<bossId>.*`. */
    abstract val bossId: String

    override val bossKey: String get() = bossId

    /** Effektives Leben solo (PLAN 4.1): Hekate 1300, Brontes 1500, Kratos 1700, Zeus 3300 (drei Leisten je 1100). */
    abstract val effectiveHealth: Float

    /** Lebensschwellen der Phasen als Anteil des Lebens, absteigend (z. B. `listOf(0.66f, 0.33f)`). */
    open val phases: List<Float> get() = emptyList()

    /** Aktion „gibt nach“ für das Ende des Kampfes (PLAN 10.2 `YIELD`). */
    protected abstract val yieldAction: Int

    /** Aktion beim Auftritt (z. B. `RISE`), 0 = keine. */
    protected open val entranceAction: Int get() = 0

    /** Musik des Kampfes, `null` = keine. */
    protected open val music: SoundEvent? get() = null

    /**
     * Farbschema der geteilten Leiste (`Cine.phaseBar`: 0 Grün, 1 Gold, ab 10 die Gefahrenpalette von
     * `Vfx.Style.entries[theme − 10]`, siehe `client/cine/PhaseBar.STYLE_THEMES`). Standard: die Gefahrfarbe des Bosses
     * ([danger]), also Hekate Mond, Brontes Glut, Kratos Karmin, Zeus Donner (Gold heißt „sicher/offen“).
     */
    protected open val barTheme: Int get() = BAR_STYLE_THEMES + danger.ordinal

    /** Bezug für den Deckel: effektives Grundleben (Standard [effectiveHealth]). */
    protected open val capBase: Float get() = effectiveHealth

    override val tier: BossScaling.Tier get() = BossScaling.Tier.BOSS
    override val family: BossScaling.Family get() = BossScaling.Family.OLYMP

    override fun toughness(): Float = effectiveHealth / maxHealth.coerceAtLeast(1f)
    override fun effectiveBase(): Float = capBase
    override fun basePause(): Int = PAUSE
    override val regenerates: Boolean get() = false

    /** Die Arena des laufenden Kampfes (`null`, wenn er ohne Altar erschienen ist, z. B. per `/summon`). */
    var arena: Arenas.Arena? = null
        private set

    /** Laufende Phase ab 0 (synchronisiert, steht im RenderState). */
    var phase: Int
        get() = dataTracker.get(PHASE)
        private set(value) = dataTracker.set(PHASE, value)

    private var phaseDue = false
    private var victoryDue = false
    private var won = false
    private val barViewers = HashSet<UUID>()

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(PHASE, 0)
    }

    // ---------------------------------------------------------------------------------------------
    // Start und Ende
    // ---------------------------------------------------------------------------------------------

    /** Der Kampf beginnt in [arena] (schon über [Arenas.open] geöffnet); [starter] kämpft von Anfang an mit. */
    fun begin(arena: Arenas.Arena, starter: ServerPlayerEntity?) {
        this.arena = arena
        home = arena.center
        post = entityPos
        starter?.let(arena::join)
    }

    /** Ohne Sieg beenden: Leiste weg, Musik aus, Arena zu, Boss weg. Der Altar ist danach wieder scharf. */
    fun abort() {
        if (isRemoved) return
        shutdown()
        discard()
    }

    override fun onArenaEmpty() = abort()
    override fun stopFight() = abort()

    /** Für Tests: Leben auf die nächste Schwelle (bzw. auf 1 vor dem Sieg). */
    override fun skipPhase() {
        if (won) return
        if (phase < phases.size) {
            health = phases[phase] * maxHealth
            phaseDue = true
        } else {
            health = 1f
            victoryDue = true
        }
    }

    override fun onLonely() = abort()

    private fun shutdown() {
        cinematics.abort()
        telegraph.reset()
        val viewers = viewers()
        hideBar()
        if (music != null) Cine.music(viewers, null)
        arena?.let(Arenas::close)
    }

    override fun remove(reason: RemovalReason) {
        if (!entityWorld.isClient) {
            cinematics.abort()
            hideBar()
            arena?.let(Arenas::close)
        }
        super.remove(reason)
    }

    // ---------------------------------------------------------------------------------------------
    // Mitkämpfer, Leine, Ablauf
    // ---------------------------------------------------------------------------------------------

    /** Mitkämpfer: die Teilnehmer der Arena ohne Kreativ-Spieler (ohne Arena wie bei den Wächtern). */
    override fun fighters(): List<ServerPlayerEntity> = arena?.fighters() ?: super.fighters()

    /** Wer die Leiste und Titel sieht: alle Teilnehmer (auch Kreativ-Spieler). */
    protected fun viewers(): List<ServerPlayerEntity> = arena?.players() ?: fighters()

    /** Er bleibt in seiner Arena: Herangehen endet am Rand. */
    override fun leash(): Double = arena?.let { it.radius + 3.0 } ?: super.leash()

    /** Ziel kann jeder Arena-Teilnehmer sein, egal wie weit am Rand (dieselbe Grenze wie die Arena selbst). */
    override fun preyRange(): Double = if (arena != null) Double.MAX_VALUE else super.preyRange()

    override fun defaultFloor(): Double = arena?.floorY ?: super.defaultFloor()

    override fun tick() {
        if (!entityWorld.isClient && kit.barVisible) kit.barVisible = false
        super.tick()
        if (!entityWorld.isClient && isAlive && !barClosed && age % 10 == 0) updateBar()
    }

    /** Pause zwischen zwei Angriffen nach Spielerzahl ([BossScaling.pause]); Ansagen bleiben unverändert. */
    protected fun pause(ticks: Int = basePause()): Sequence<Unit> =
        rest(BossScaling.pause((ticks * if (enraged) RAGE_PAUSE else 1.0).toInt(), crowdSize))

    /**
     * Letztes Gefecht: Ab [RAGE_AT] des Lebens kämpft der Boss verbissener (Titelkarte `hha.<id>.rage`, kürzere Pausen
     * × [RAGE_PAUSE], Treffer × [RAGE_DAMAGE]). Ansagen bleiben gleich lang, damit alles lesbar bleibt.
     */
    private var enraged = false

    private fun checkRage() {
        if (enraged || won || maxHealth <= 0f || health / maxHealth > RAGE_AT) return
        enraged = true
        rageDamage = RAGE_DAMAGE
        val players = viewers()
        Cine.title(players, "hha.boss.rage.title", "hha.$bossId.rage", 50)
        telegraph.sound(dev.henny.hha.HhaSounds.IMPACT, entityPos, 2.4f, 0.55f)
        (entityWorld as? ServerWorld)?.let { dev.henny.hha.logic.Vfx.shockwave(it, danger, entityPos.add(0.0, 0.2, 0.0), 6f, 14) }
    }

    /** Auftritt: Titelkarte `hha.<bossId>.title`, Musik, [entranceAction]. Unterklassen dürfen überschreiben. */
    override fun entrance(): Sequence<Unit> = sequence {
        val players = viewers()
        Cine.title(players, "hha.$bossId.title", "hha.$bossId.title.sub", 70)
        music?.let { Cine.music(players, it) }
        if (entranceAction > 0) kit.act(entranceAction)
        repeat(ENTRANCE) {
            fighters().minByOrNull { it.squaredDistanceTo(self) }?.let { lookControl.lookAt(it, 30f, 30f) }
            yield(Unit)
        }
        if (entranceAction > 0) kit.act(0)
    }

    /**
     * Phasenwechsel und Sieg kommen vor dem nächsten Angriff. Unterklassen mit eigenen Ereignissen (Höhepunkte bei
     * Lebensschwellen) rufen zuerst `super.phaseEvent()`.
     */
    override fun phaseEvent(): Sequence<Unit>? {
        if (victoryDue && !won) {
            won = true
            return if (cinematic) cinematics.defeat(victory(), emptyList()) else victory()
        }
        if (phaseDue) {
            phaseDue = false
            phase += 1
            updateBar()
            val inner = onPhase(phase) ?: emptySequence()
            return if (cinematic) cinematics.phase(inner, emptyList()) else inner
        }
        return null
    }

    /**
     * Filmische Rahmung (Kamerafahrt beim Auftritt, Kamerakreis bei Phasenwechsel und Sieg) um die eigenen Abläufe.
     * Bosse mit eigenem Szenen-Drehbuch (Zeus) schalten sie ab.
     */
    protected open val cinematic: Boolean get() = true

    /** Die Szenen dieses Bosses; während einer Szene nimmt er keinen Schaden. */
    protected val cinematics = DuelCinematics(this) { viewers() }

    override fun framedEntrance(): Sequence<Unit> = if (cinematic) cinematics.intro(entrance(), emptyList()) else entrance()

    /** Neue Phase [newPhase] (1, 2 …) hat begonnen: Übergang (z. B. Schild weg, Cutscene). `null` = keiner. */
    protected open fun onPhase(newPhase: Int): Sequence<Unit>? = null

    /**
     * Das Ende: Er gibt nach ([yieldAction]), Beute und Fortschritt ([finishVictory]), bleibt [VICTORY_LINGER] Ticks
     * stehen und verschwindet. Unterklassen mit Cutscene überschreiben und rufen [finishVictory] an der passenden Stelle.
     */
    protected open fun victory(): Sequence<Unit> = sequence {
        stopMoving()
        kit.act(yieldAction)
        finishVictory()
        wait(VICTORY_LINGER)
        discard()
    }

    /** Beute, Fortschritt, Fernsäule; schließt die Arena. Genau einmal je Sieg aufrufen. */
    protected fun finishVictory() {
        val server = sw.server ?: return
        val players = viewers()
        telegraph.reset()
        BossRewards.grant(bossId, players.filter { !it.isSpectator }, entityPos)
        OlympusProgress.markDone(server, bossId)
        OlympusPath.onProgress(server, bossId)
        for (player in players) OlympusPath.pointNext(player)
        if (music != null) Cine.music(players, null)
        hideBar()
        arena?.let(Arenas::close)
    }

    // ---------------------------------------------------------------------------------------------
    // Treffer und Leiste
    // ---------------------------------------------------------------------------------------------

    /** Vor dem Kampf (ohne Arena gilt: immer), nach dem Sieg und während eines Phasenwechsels nimmt er nichts. */
    override fun acceptsHit(world: ServerWorld, source: DamageSource): Boolean {
        if (won || victoryDue || cinematics.playing) return false
        // Schaden nur von der Kampffläche: Wer vom Rand aus (Arena-Mitgliedschaft reicht bis Radius + 16) schießt,
        // stünde außerhalb aller Angriffe (Review R3 M1). Gilt auch für Pfeile, Dreizacke usw. (Schütze zählt).
        // Haustiere (Wolf …) zählen wie ihr Besitzer, sonst ließe der Wolf vom Rand aus die Phasen abbeißen (Review R4 M1).
        val area = arena ?: return true
        val shooter = when (val behind = controllingPlayer(source)) {
            Controller.None -> return true
            Controller.Absent -> return false
            is Controller.Player -> behind.player
        }
        if (shooter.entityWorld !== entityWorld || GuardianEntity.flat(shooter.entityPos, area.center) > area.radius + OUTSIDE_MARGIN) {
            if (tooFarWarned.add(shooter.uuid)) {
                shooter.sendMessage(Text.translatable("hha.boss.arena.too_far").formatted(Formatting.GRAY), true)
            }
            return false
        }
        return true
    }

    /** Wer hinter einem Treffer steht: kein Spieler, ein Spieler, oder ein Besitzer, der gerade nicht da ist. */
    private sealed interface Controller {
        data object None : Controller
        data object Absent : Controller
        data class Player(val player: ServerPlayerEntity) : Controller
    }

    /**
     * Der Spieler hinter einem Treffer: er selbst (auch als Schütze) oder der Besitzer eines Haustiers. Hat das Tier einen
     * Besitzer, der offline, tot oder in einer anderen Welt ist, zählt der Biss nicht (Review R5 m1); wilde Tiere und
     * Tiere ohne Spieler-Besitzer bleiben unverändert.
     */
    private fun controllingPlayer(source: DamageSource): Controller = when (val attacker = source.attacker) {
        is ServerPlayerEntity -> Controller.Player(attacker)
        is Tameable -> when {
            attacker.getOwnerReference() == null -> Controller.None
            else -> when (val owner = attacker.getOwner()) {
                null -> Controller.Absent
                is ServerPlayerEntity -> if (owner.isAlive && !owner.isRemoved && !owner.isDisconnected) Controller.Player(owner) else Controller.Absent
                else -> Controller.None
            }
        }
        else -> Controller.None
    }

    /** Wer schon den Hinweis „vom Rand aus trifft nichts" bekommen hat (einmal je Kampf). */
    private val tooFarWarned = HashSet<UUID>()

    /** Hält an der Schwelle der laufenden Phase an (bzw. bei 1 Leben) und merkt den Wechsel vor. */
    override fun limitBarDamage(source: DamageSource, bar: Float): Float {
        if (won || phaseDue || victoryDue) return 0f
        val floor = floorHealth()
        if (health - floor <= 0.01f) {
            // Schon an der Schwelle (z. B. aus einem früheren, angenommenen Treffer): jetzt vormerken.
            reachedFloor()
            return 0f
        }
        return min(bar, health - floor)
    }

    /** Vorgemerkt wird erst, wenn das Leben wirklich an der Schwelle steht (Review R2 m1). */
    override fun afterHealthLoss() {
        checkRage()
        if (!won && !phaseDue && !victoryDue && health <= floorHealth() + 0.01f) reachedFloor()
    }

    private fun reachedFloor() {
        if (phase < phases.size) phaseDue = true else victoryDue = true
    }

    private fun floorHealth(): Float = if (phase < phases.size) phases[phase] * maxHealth else 1f

    /** Lebensanteil innerhalb der laufenden Phase (für die Leiste). */
    fun phaseFraction(): Float {
        val upper = if (phase == 0) 1f else phases.getOrElse(phase - 1) { 1f }
        val lower = phases.getOrElse(phase) { 0f }
        val span = (upper - lower).coerceAtLeast(0.001f)
        return ((health / maxHealth - lower) / span).coerceIn(0f, 1f)
    }

    private fun updateBar() {
        val viewers = viewers()
        Cine.phaseBar(
            viewers, true, phases.size + 1, phase + 1, phaseFraction(), barTheme, "entity.hha.$bossId", "hha.$bossId.phase.${phase + 1}",
        )
        val ids = viewers.mapTo(HashSet()) { it.uuid }
        val gone = barViewers.filter { it !in ids }.mapNotNull { sw.getPlayerByUuid(it) as? ServerPlayerEntity }
        if (gone.isNotEmpty()) Cine.phaseBar(gone, false)
        barViewers.clear()
        barViewers.addAll(ids)
    }

    /** Nach Sieg oder Abbruch kommt die Leiste nicht wieder (Review R1 m2). */
    private var barClosed = false

    private fun hideBar() {
        barClosed = true
        val world = entityWorld as? ServerWorld ?: return
        val gone = barViewers.mapNotNull { world.getPlayerByUuid(it) as? ServerPlayerEntity }
        if (gone.isNotEmpty()) Cine.phaseBar(gone, false)
        barViewers.clear()
    }

    // ---------------------------------------------------------------------------------------------
    // Gerüst: Platzhalter-Angriffe, bis die Boss-Pakete ihre echten Angriffe schreiben
    // ---------------------------------------------------------------------------------------------

    /**
     * Zwei einfache Angriffe für das Gerüst (damit `/hha boss start` etwas zeigt): nah ein Kreis um ihn ([near]),
     * fern ein Kreis am Ziel ([far]); je mit der Aktion des Bosses. Die Boss-Pakete ersetzen [createMoves].
     */
    protected fun placeholderMoves(near: Int, far: Int): List<Move> = listOf(
        Move(near, 0.0..7.0, 2) { placeholderSlam(near) },
        Move(far, 5.0..40.0, 2) { placeholderBolt(far, it) },
    )

    private fun placeholderSlam(action: Int): Sequence<Unit> = sequence {
        kit.act(action)
        val group = telegraph.boss()
        group.ring(ground(entityPos), 4.5f, PLACEHOLDER_WINDUP)
        wait(group.ticks)
        for (player in group.hits()) strike(player, PLACEHOLDER_DAMAGE)
        telegraph.hitShake(entityPos, 2f)
        yieldAll(rest(PLACEHOLDER_RECOVER))
        kit.act(0)
    }

    private fun placeholderBolt(action: Int, target: ServerPlayerEntity): Sequence<Unit> = sequence {
        kit.act(action)
        hold(dirTo(target.entityPos))
        val group = telegraph.boss()
        group.ring(ground(target.entityPos), 3f, PLACEHOLDER_WINDUP + 4)
        wait(group.ticks)
        for (player in group.hits()) strike(player, PLACEHOLDER_DAMAGE)
        hold(null)
        yieldAll(rest(PLACEHOLDER_RECOVER))
        kit.act(0)
    }

    companion object {
        private val PHASE: TrackedData<Int> = DataTracker.registerData(DuelBossEntity::class.java, TrackedDataHandlerRegistry.INTEGER)

        /** „offen“ = goldene Rune, Treffer × 1,8 (PLAN 5). Für `open(ticks, OPEN_FACTOR)`. */
        const val OPEN_FACTOR = 1.8f

        /** Pause zwischen Angriffen, wenn der Kampf nichts anderes sagt (PLAN 5). */
        const val PAUSE = 24

        /** Nach dem Sieg bleibt er so lange stehen. */
        const val VICTORY_LINGER = 200

        /** Auftritt vor dem ersten Angriff. */
        const val ENTRANCE = 40

        /** Letztes Gefecht: ab diesem Lebensanteil, mit diesen Faktoren. */
        const val RAGE_AT = 0.2f
        const val RAGE_PAUSE = 0.7
        const val RAGE_DAMAGE = 1.2f

        /** So weit über den Arena-Radius hinaus zählen Treffer von Spielern noch (Fußbreite, Randstufe). */
        const val OUTSIDE_MARGIN = 1.5

        /** Erstes Leisten-Thema mit Stilfarben (gleicher Wert wie `client/cine/PhaseBar.STYLE_THEMES`, nur Server). */
        const val BAR_STYLE_THEMES = 10

        private const val PLACEHOLDER_WINDUP = 20
        private const val PLACEHOLDER_RECOVER = 20
        private const val PLACEHOLDER_DAMAGE = 10f

        /** Boss-Schlüssel → Entity-Typ der neuen Bosse. */
        fun type(boss: String): EntityType<out DuelBossEntity>? = when (boss.lowercase()) {
            "hekate" -> HhaEntities.HEKATE
            "brontes" -> HhaEntities.BRONTES
            "kratos" -> HhaEntities.KRATOS
            "zeus" -> HhaEntities.ZEUS
            else -> null
        }

        /** Alle Schlüssel, die [startAt] kennt. */
        val KEYS: List<String> = listOf("hekate", "brontes", "kratos", "zeus")

        /** Laufender Kampf dieses Bosses auf dem Server, sonst `null`. */
        fun running(server: MinecraftServer, boss: String): DuelBossEntity? = Arenas.byBoss(server, boss)?.owner as? DuelBossEntity

        /**
         * Startet den Kampf gegen [boss] an seiner Station ([OlympusLayout.station]), wenn [world] der Olymp ist und
         * [at] fehlt. Sonst (Test außerhalb des Olymp, Altar per `/setblock`) entsteht die Arena um [at] bzw. um
         * [starter] mit dem Radius der Station. Prüft **keine** Bedingungen (das macht der Altar). Liefert den Boss
         * oder `null` (unbekannt, läuft schon, kein Ort).
         */
        fun startAt(world: ServerWorld, boss: String, starter: ServerPlayerEntity?, at: Vec3d? = null): DuelBossEntity? {
            val type = type(boss) ?: return null
            val isle = OlympusLayout.station(boss) ?: return null
            val server = world.server ?: return null
            if (running(server, boss) != null) {
                starter?.sendMessage(Text.translatable("hha.boss_altar.busy").formatted(Formatting.GOLD), true)
                return null
            }
            val inOlymp = world.registryKey == OlympusLayout.WORLD && at == null
            val center: Vec3d
            val entrance: Vec3d
            val spawn: Vec3d
            if (inOlymp) {
                center = isle.centerVec
                entrance = isle.entrance
                spawn = isle.bossSpawn ?: center
            } else {
                center = at ?: starter?.entityPos ?: return null
                entrance = center.add(0.0, 0.0, isle.arenaRadius + 4.0)
                spawn = center.add(0.0, 0.0, -6.0)
            }
            val entity = type.create(world, SpawnReason.EVENT) ?: return null
            entity.refreshPositionAndAngles(spawn.x, spawn.y, spawn.z, 0f, 0f)
            val arena = Arenas.Arena(world, center, isle.arenaRadius.toDouble(), center.y, entrance, entity)
            if (!Arenas.open(arena)) return null
            world.spawnEntity(entity)
            entity.begin(arena, starter)
            return entity
        }
    }
}
