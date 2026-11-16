package dev.henny.hha.entity.boss.olymp

import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.DuelBossEntity
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.entity.miniboss.GuardianEntity
import dev.henny.hha.entity.miniboss.MiniBossKit
import dev.henny.hha.entity.miniboss.ScriptedBolt
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossDefense
import dev.henny.hha.logic.boss.BossScaling
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha.logic.boss.timings.KratosTimings
import dev.henny.hha.world.olymp.OlympusLayout
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket
import net.minecraft.network.packet.s2c.play.TitleS2CPacket
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/** Aktionen des Kratos für `kit.act` (PLAN 10.2). Werte gehen über die Leitung: nicht umnummerieren. */
object KratosAction {
    const val NONE = 0
    const val BASH = 1
    const val SMASH = 2
    const val CMD_BACK = 3
    const val CMD_COME = 4
    const val CMD_KNEEL = 5
    const val STAGGER = 6
    const val DROP_SHIELD = 7
    const val DOUBLE_SMASH = 8
    const val SHIELD_UP = 9
    const val YIELD = 10
}

/** Aktionen der Bia für `kit.act` (PLAN 10.2). */
object BiaAction {
    const val NONE = 0
    const val STATUE = 1
    const val LEAP = 2
    const val RUSH = 3
    const val SWEEP = 4
    const val STAGGER = 5
    const val RETURN = 6
}

/**
 * Kratos, die Macht, Thronwache auf den Stufen (PLAN 5.4). **Der Gegner** mit Lebensleiste; Bia ([BiaEntity]) ist sein
 * angesagter Angriff mit Körper: Sie steht reglos auf dem linken Sockel und kommt nur, wenn er sie schickt.
 *
 * - **Schild** (Phase I): Treffer von vorn (± 60°) zählen außerhalb offener Fenster × 0,2 ([hitFactor], vor Abwehr und
 *   Deckel). Nach dem Schildstoß hängt der Schild ([shieldDownUntil]), im Konter liegt er über dem Kopf.
 * - **Nah** Schildstoß oder Hammerfall, **fern** ein Befehl, nach jedem zweiten Angriff ein Befehl (nie derselbe
 *   zweimal): „WEICHT!“ (Kreis um ihn), „HERAN!“ (alles außerhalb Radius 7, danach Schildstoß), „KNIET!“ (Bias Klinge
 *   fegt über den ganzen Platz; wer schleicht, bleibt heil). Das Wort steht groß als Titel in der Gefahrfarbe.
 * - **Bias Sturm** (Saal-Gefahr, alle 14 s): eine Linie vom Sockel durch das Ziel bis zum Rand; sie läuft sie in 12 Ticks.
 *   **Der Zusammenstoß:** Führt die Linie näher als 2 an Kratos vorbei, rammt sie ihn: beide taumeln, er ist 80 Ticks
 *   offen (golden), der Schild zählt dann nicht.
 * - **Phase II** „Gewalt ohne Maß“ (50 %): Er schleudert den Schild weg ([shieldless]), Hammerfall als Doppelschlag,
 *   Befehle paarweise, Bias Sturm dreimal hintereinander.
 * - Konter gegen Spieler über ihm: `SHIELD_UP` (PLAN 4.3). Kein Statuseffekt auf Spielern, keine Sperren: Befehle sind
 *   Ansagen.
 *
 * Alle Zahlen stehen in [KratosTimings]. Bia wird hier geführt (Ort, Blick, Aktion); sie selbst wählt nichts.
 */
class KratosEntity(type: EntityType<out KratosEntity>, world: World) : DuelBossEntity(type, world) {

    override val kit = MiniBossKit(this, "entity.hha.kratos", BossBar.Color.RED, Vfx.Style.CRIMSON)
    override val fightName = "kratos"
    override val danger = Vfx.Style.CRIMSON
    override val bossId = "kratos"
    override val effectiveHealth = KratosTimings.EFFECTIVE_HEALTH
    override val phases = listOf(KratosTimings.PHASE_2)
    override val antiAirAction get() = KratosAction.SHIELD_UP
    override val antiAirDamage get() = KratosTimings.COUNTER_DAMAGE
    override val yieldAction = KratosAction.YIELD
    override val music: SoundEvent get() = HhaSounds.THEME_KRATOS

    /** Leiste in der Gefahrfarbe (`PhaseBar`: ab 10 die Palette von `Vfx.Style.entries[theme − 10]`). */
    override val barTheme: Int get() = STYLE_THEMES + Vfx.Style.CRIMSON.ordinal

    /** Phase II: Der Schild ist weg (Modell blendet ihn aus, kein Frontschutz mehr). */
    var shieldless: Boolean
        get() = dataTracker.get(SHIELDLESS)
        set(value) = dataTracker.set(SHIELDLESS, value)

    // --- Zustand (nur Server) ---------------------------------------------------------------------

    private var bia: BiaEntity? = null

    /** Bia ist unterwegs (Sturm, „KNIET!“, Taumeln, Rückkehr); dann steht sie für nichts anderes bereit. */
    private var biaBusy = false

    /** Auftritt, Schildwurf, Sieg: kein Sturm beginnt, kein Zusammenstoß unterbricht. */
    private var calm = true

    /** Sieg: Bia kehrt auf den Sockel zurück, nichts Neues beginnt. */
    private var over = false

    /** Er schreitet gerade die Treppe herab (die Stufen liegen außerhalb seiner Leine). */
    private var entering = false

    private var nextStorm = Int.MAX_VALUE
    private var stormTurn = 0
    private var attacksSinceCommand = 0
    private var lastCommand = KratosAction.NONE
    private var shieldDownUntil = 0L
    private var lastShieldSound = -100

    /** Die Boss-Ansage des laufenden Angriffs (wird beim Zusammenstoß freigegeben). */
    private var attackGroup: Telegraph.Fight.Group? = null

    /** Der weggeworfene Schild (nur mit [KratosTimings.SHIELD_PROP_KIND]). */
    private var shieldProp: HermesBoltEntity? = null

    /** Die Klingenfront von „KNIET!“ (Platz in der Reihe → Requisit) und welche Plätze schon durch sind. */
    private val blades = HashMap<Int, ScriptedBolt>()
    private val bladesDone = HashSet<Int>()

    private val spoken = HashSet<String>()
    private val hintedWith = HashSet<String>()

    init {
        kit.stats(KratosTimings.HEALTH, KratosTimings.ARMOR, KratosTimings.ATTACK, KratosTimings.SPEED)
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(SHIELDLESS, false)
    }

    // ---------------------------------------------------------------------------------------------
    // Platz
    // ---------------------------------------------------------------------------------------------

    /** Mitte des Platzes (ohne Arena: wo er zuerst stand, z. B. nach `/summon`). */
    private fun center(): Vec3d = arena?.center ?: anchor()

    private fun radius(): Double = arena?.radius ?: OlympusLayout.E.arenaRadius.toDouble()

    private fun floorY(): Double = defaultFloor()

    /** Kämpft er an seiner Station auf Insel E (dort gibt es Treppe und Sockel)? */
    private fun atStation(): Boolean {
        val a = arena ?: return false
        return sw.registryKey == OlympusLayout.WORLD && flat(a.center, OlympusLayout.E.centerVec) < 1.0
    }

    /** Mitte von Bias Sockel auf Höhe des Platzes; außerhalb der Station an derselben Stelle relativ zur Mitte. */
    private fun plinthBase(): Vec3d {
        val c = center()
        return Vec3d(c.x + PLINTH_DX, floorY(), c.z + PLINTH_DZ)
    }

    /** Wo Bia als Statue steht (oben auf dem Sockel, sonst am Boden). */
    private fun biaHome(): Vec3d {
        val base = plinthBase()
        val top = floorOrNull(base.add(0.0, OlympusLayout.PLINTH_HEIGHT + 2.5, 0.0), OlympusLayout.PLINTH_HEIGHT + 5.0) ?: base.y
        return Vec3d(base.x, top, base.z)
    }

    /** Waagerechter Punkt [s] Blöcke ab [start] in [dir], auf dem Boden. */
    private fun point(start: Vec3d, dir: Vec3d, s: Double): Vec3d {
        val x = start.x + dir.x * s
        val z = start.z + dir.z * s
        return Vec3d(x, floorAt(Vec3d(x, floorY(), z)), z)
    }

    /** Wo eine Linie ab [start] in [dir] den Platzrand trifft (knapp innen). */
    private fun edgePoint(start: Vec3d, dir: Vec3d): Vec3d {
        val c = center()
        val r = radius() - 0.5
        val ox = start.x - c.x
        val oz = start.z - c.z
        val b = ox * dir.x + oz * dir.z
        val disc = b * b - (ox * ox + oz * oz - r * r)
        val length = (if (disc > 0.0) -b + sqrt(disc) else 0.0).coerceAtLeast(6.0)
        return Vec3d(start.x + dir.x * length, floorY(), start.z + dir.z * length)
    }

    private fun yawOf(dir: Vec3d): Float = (Math.toDegrees(atan2(dir.z, dir.x)) - 90.0).toFloat()

    private fun nearest(): ServerPlayerEntity? = fighters().minByOrNull { it.squaredDistanceTo(self) }

    private fun present(player: ServerPlayerEntity): Boolean = player.isAlive && !player.isRemoved && player in fighters()

    // ---------------------------------------------------------------------------------------------
    // Bia
    // ---------------------------------------------------------------------------------------------

    /** Bia steht (oder wird neu aufgestellt, falls sie fehlt). */
    private fun ensureBia(): BiaEntity? {
        bia?.let { if (!it.isRemoved) return it }
        if (over) return null
        val b = HhaEntities.BIA.create(sw, SpawnReason.EVENT) ?: return null
        val home = biaHome()
        val look = flatNorm(center().subtract(home))
        b.leader = this
        b.home = home
        b.post = home
        b.refreshPositionAndAngles(home.x, home.y, home.z, yawOf(look), 0f)
        b.face(look)
        if (!sw.spawnEntity(b)) return null
        bia = b
        biaBusy = false
        return b
    }

    private fun biaFree(): Boolean = !over && !biaBusy && bia?.isRemoved == false

    /** Bias Treffer: Sie ist die Angreiferin, Spielerzahl und Familie wie bei ihm, mit der Höhenregel. */
    private fun biaStrike(player: ServerPlayerEntity, base: Float, reach: BossDefense.Reach): Boolean {
        val b = bia ?: return strike(player, base, reach)
        if (!BossDefense.reaches(player, floorY(), reach, b.height.toDouble())) return false
        return player.damage(sw, sw.damageSources.mobAttack(b), base * damageScale)
    }

    /** Zurück auf den Sockel: Sprung (LEAP), Landung (RETURN), dann wieder Statue. */
    private fun biaReturn(b: BiaEntity): Sequence<Unit> = sequence {
        if (!b.isRemoved) {
            val home = biaHome()
            val from = b.entityPos
            val dir = flatNorm(home.subtract(from))
            b.kit.act(BiaAction.LEAP)
            b.scripted(true)
            for (t in 1..KratosTimings.BIA_FLIGHT) {
                b.place(arc(from, home, t.toDouble() / KratosTimings.BIA_FLIGHT, KratosTimings.BIA_FLIGHT_LIFT))
                b.face(dir)
                yield(Unit)
            }
            b.place(home)
            b.face(flatNorm(center().subtract(home)))
            b.scripted(false)
            b.kit.act(BiaAction.RETURN)
            wait(KratosTimings.BIA_RETURN_END)
            b.kit.act(BiaAction.STATUE)
        }
        biaBusy = false
        nextStorm = max(nextStorm, age + KratosTimings.STORM_MIN_GAP)
    }

    /** Sofort zurück auf den Sockel (Leine, Neustart). */
    private fun resetBia() {
        val b = bia ?: return
        if (b.isRemoved) return
        val home = biaHome()
        b.scripted(false)
        b.place(home)
        b.face(flatNorm(center().subtract(home)))
        b.kit.act(BiaAction.STATUE)
    }

    // ---------------------------------------------------------------------------------------------
    // Ablauf
    // ---------------------------------------------------------------------------------------------

    /**
     * Auftritt: Bia steht schon als Statue auf ihrem Sockel; Kratos schreitet (an der Station) die Treppe herab bis zu
     * seinem Platz. Titelkarte und Musik wie bei allen Bossen des Olymp.
     */
    override fun entrance(): Sequence<Unit> = sequence {
        calm = true
        ensureBia()
        val players = viewers()
        Cine.title(players, "hha.kratos.title", "hha.kratos.title.sub", KratosTimings.ENTRANCE_TITLE)
        Cine.music(players, music)
        val goal = post ?: entityPos
        stairsStart()?.let {
            entering = true
            refreshPositionAndAngles(it.x, it.y, it.z, yawOf(flatNorm(goal.subtract(it))), 0f)
        }
        var walked = 0
        while (walked < KratosTimings.ENTRANCE_WALK && flat(entityPos, goal) > 1.2) {
            // Wer schon unten am Siegel steht, dem tritt er nicht auf die Füße.
            if ((nearest()?.let(::flatDistance) ?: 99.0) < ENTRANCE_KEEP) break
            walkTo(goal, 0.9)
            walked++
            yield(Unit)
        }
        stopMoving()
        entering = false
        say("entrance")
        repeat(KratosTimings.ENTRANCE_TITLE - walked.coerceAtMost(KratosTimings.ENTRANCE_WALK) - 10) {
            nearest()?.let { lookControl.lookAt(it, 30f, 30f) }
            yield(Unit)
        }
        calm = false
        nextStorm = age + KratosTimings.STORM_FIRST
    }

    /**
     * Ein freier Platz ein paar Stufen hinauf (nur an der Station), sonst `null`. Gesucht wird in halben Blöcken vom
     * höchsten Punkt abwärts: Sein Körper (1,8 tief) passt nur dort, wo er nicht in die nächste Stufe ragt (Absatz).
     */
    private fun stairsStart(): Vec3d? {
        if (!atStation()) return null
        val spawn = post ?: return null
        val box = type.dimensions
        var back = KratosTimings.ENTRANCE_STAIRS.toDouble()
        while (back >= ENTRANCE_STAIRS_MIN) {
            val probe = Vec3d(spawn.x, floorY() + 12.0, OlympusLayout.E_STAIRS_FOOT_Z - back)
            val y = floorOrNull(probe, 16.0)
            if (y != null && y >= floorY() - 0.5 && y <= floorY() + 10.0) {
                val at = Vec3d(probe.x, y + 0.01, probe.z)
                if (sw.isSpaceEmpty(this, box.getBoxAt(at))) return at
            }
            back -= 0.5
        }
        return null
    }

    /** Jeder Tick: Bia steht bereit; ist es Zeit, schickt er sie in den Sturm (Saal-Gefahr, kann verweigert werden). */
    override fun tickBody() {
        super.tickBody()
        if (over) return
        val b = ensureBia() ?: return
        if (calm || biaBusy || age < nextStorm) return
        if (fighters().isEmpty() || !telegraph.canStartHazard()) return
        val second = phase >= 1
        biaBusy = true
        nextStorm = age + BossScaling.pause(KratosTimings.STORM_INTERVAL, crowdSize)
        launch(
            storm(
                b,
                if (second) KratosTimings.STORM_REPEAT_PHASE_2 else 1,
                if (second) KratosTimings.STORM_W_PHASE_2 else KratosTimings.STORM_W,
            ),
        )
    }

    /** Während des Auftritts reicht die Leine bis auf die Treppe. */
    override fun leash(): Double = super.leash() + if (entering) ENTRANCE_LEASH else 0.0

    /**
     * Leine oder Neustart: Klingen weg, Bia zurück auf den Sockel. Ein abgebrochener Auftritt oder Schildwurf lässt
     * nichts hängen (Sturm wieder möglich, Phase II ohne Schild).
     */
    override fun onReset() {
        clearBlades()
        attackGroup = null
        shieldDownUntil = 0L
        resetBia()
        biaBusy = false
        entering = false
        calm = over
        if (phase >= 1) shieldless = true
        nextStorm = age + KratosTimings.STORM_MIN_GAP
    }

    override fun createMoves(): List<Move> = listOf(
        Move(KratosAction.BASH, 0.0..KratosTimings.BASH_REACH, 3, { !commandDue() }) { bash(it) },
        Move(KratosAction.SMASH, 0.0..KratosTimings.SMASH_MOVE_REACH, 3, { !commandDue() }) {
            if (phase >= 1) doubleSmash(it) else smash(it)
        },
        Move(MOVE_COMMAND, 0.0..COMMAND_ANY, 2, { commandDue() || preyFar() }) { commands(it) },
    )

    private fun commandDue(): Boolean = attacksSinceCommand >= KratosTimings.COMMAND_EVERY

    private fun preyFar(): Boolean = prey?.let { flatDistance(it) > KratosTimings.COMMAND_FAR } ?: false

    /** Eine neue Boss-Ansage für den laufenden Angriff (wird beim Zusammenstoß freigegeben). */
    private fun attack(): Telegraph.Fight.Group = telegraph.boss().also { attackGroup = it }

    private fun done(group: Telegraph.Fight.Group) {
        if (attackGroup === group) attackGroup = null
    }

    // ---------------------------------------------------------------------------------------------
    // Schild
    // ---------------------------------------------------------------------------------------------

    private fun shieldUp(): Boolean = phase == 0 && !shieldless && sw.time >= shieldDownUntil && !guard.inCounter

    /**
     * Phase I: Treffer von vorn (Winkel zwischen Blickrichtung und Richtung zum Angreifer unter 60°) außerhalb offener
     * Fenster × 0,2, mit Abprall-Ton und einmaligem Hinweis. Flanke und Rücken zählen voll.
     */
    override fun hitFactor(source: DamageSource): Float {
        if (!shieldUp() || isOpen()) return 1f
        val from = source.position ?: source.attacker?.entityPos ?: return 1f
        val to = flatNorm(from.subtract(entityPos))
        val f = facing()
        if (to.x * f.x + to.z * f.z < SHIELD_COS) return 1f
        if (age - lastShieldSound >= KratosTimings.SHIELD_SOUND_COOLDOWN) {
            lastShieldSound = age
            telegraph.sound(HhaSounds.SHIELD, entityPos.add(0.0, height * 0.5, 0.0), 1.6f, 1.0f)
        }
        if (source.attacker is PlayerEntity) hint("hha.kratos.hint.shield")
        return KratosTimings.SHIELD_FACTOR
    }

    // ---------------------------------------------------------------------------------------------
    // Nah: Schildstoß, Hammerfall
    // ---------------------------------------------------------------------------------------------

    /** Schildstoß: Kegel 60° halb, Radius 6, W 14, S 16 und Rückstoß; danach hängt der Schild (F 20, von vorn voll). */
    private fun bash(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        attacksSinceCommand++
        stopMoving()
        val dir = dirTo(target.entityPos)
        hold(dir)
        kit.act(KratosAction.BASH)
        val group = attack()
        yieldAll(shieldBash(group, dir, KratosTimings.BASH_F))
        done(group)
    }

    /** Der Stoß selbst (auch nach „HERAN!“). [recover] = Ticks nach dem Treffer. */
    private fun shieldBash(group: Telegraph.Fight.Group, dir: Vec3d, recover: Int): Sequence<Unit> = sequence {
        val cone = group.cone(ground(entityPos), dir, KratosTimings.BASH_RADIUS, KratosTimings.BASH_HALF_ANGLE, KratosTimings.BASH_W)
        telegraph.sound(HhaSounds.CHARGE, entityPos, 1.8f, 0.6f)
        wait(cone.ticks)
        val struck = cone.hits(fighters()).filter { strike(it, KratosTimings.BASH_DAMAGE) }
        for (player in struck) knock(player, dir, KratosTimings.BASH_KNOCKBACK, 0.45)
        Vfx.crescent(sw, danger, entityPos.add(0.0, 1.4, 0.0), dir, KratosTimings.BASH_RADIUS, 4f, 8)
        telegraph.sound(HhaSounds.IMPACT, entityPos, 2.2f, 0.8f)
        if (struck.isNotEmpty()) telegraph.hitShake(entityPos, 2.5f)
        // Der Schild hängt: von vorn zählt jetzt jeder Treffer voll.
        shieldDownUntil = sw.time + KratosTimings.BASH_F
        wait(recover)
        hold(null)
        kit.act(0)
    }

    /** Hammerfall: zwei schwere Schritte, Kreis Radius 3,5 am Ziel, W 28, S 24; der Hammer steckt (F 26). */
    private fun smash(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        attacksSinceCommand++
        stopMoving()
        val spot = smashSpot(target)
        hold(dirTo(spot))
        kit.act(KratosAction.SMASH)
        val group = attack()
        val ring = group.ring(spot, KratosTimings.SMASH_RADIUS, KratosTimings.SMASH_W)
        telegraph.sound(HhaSounds.CHARGE, entityPos, 2.0f, 0.55f)
        yieldAll(stepTo(spot, ring.ticks))
        hammer(ring)
        done(group)
        wait(KratosTimings.SMASH_F)
        hold(null)
        kit.act(0)
    }

    /** Phase II: Doppelschlag, der zweite Kreis 18 Ticks später am neuen Standort des Ziels (eigene Ansage). */
    private fun doubleSmash(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        attacksSinceCommand++
        stopMoving()
        val first = smashSpot(target)
        hold(dirTo(first))
        kit.act(KratosAction.DOUBLE_SMASH)
        val group = attack()
        val ring = group.ring(first, KratosTimings.SMASH_RADIUS, KratosTimings.SMASH_W)
        telegraph.sound(HhaSounds.CHARGE, entityPos, 2.0f, 0.5f)
        yieldAll(stepTo(first, ring.ticks))
        hammer(ring)
        val next = target.takeIf(::present) ?: nearest()
        val second = next?.let(::smashSpot) ?: first
        hold(dirTo(second))
        val again = group.ring(second, KratosTimings.SMASH_RADIUS, KratosTimings.DOUBLE_GAP, pace = Telegraph.Pace.FOLLOW_UP)
        wait(again.ticks)
        hammer(again)
        done(group)
        wait(KratosTimings.SMASH_F)
        hold(null)
        kit.act(0)
    }

    /**
     * Ansage für „HERAN!“: mindestens [KratosTimings.COME_W], sonst so lange, bis der fernste Mitkämpfer zu Fuß in den
     * sicheren Kreis käme (Reaktion + Weg bei `BossTimings.Min.ESCAPE_SPEED`, wie bei Hekates Schlüssel).
     */
    private fun comeTicks(): Int {
        val farthest = fighters().maxOfOrNull { flatDistance(it) } ?: 0.0
        val walk = (farthest - KratosTimings.COME_SAFE_RADIUS + 0.5).coerceAtLeast(0.0)
        val needed = BossTimings.Min.REACTION + kotlin.math.ceil(walk / BossTimings.Min.ESCAPE_SPEED).toInt()
        return maxOf(KratosTimings.COME_W, needed)
    }

    /** „HERAN!“ nur, wenn es jeder schaffen kann (Ansage nicht länger als [KratosTimings.COME_W_MAX]). */
    private fun comeReachable(): Boolean = comeTicks() <= KratosTimings.COME_W_MAX

    /** Kreismitte: am Ziel, aber höchstens [KratosTimings.SMASH_REACH] vor ihm. */
    private fun smashSpot(target: ServerPlayerEntity): Vec3d {
        val dir = dirTo(target.entityPos)
        val at = if (flatDistance(target) <= KratosTimings.SMASH_REACH) target.entityPos else entityPos.add(dir.multiply(KratosTimings.SMASH_REACH))
        return ground(at)
    }

    /** Die zwei schweren Schritte während des Ausholens; danach steht er bis zum Schlag. */
    private fun stepTo(spot: Vec3d, ticks: Int): Sequence<Unit> = sequence {
        for (t in 0 until ticks) {
            if (t < KratosTimings.SMASH_STEP_TICKS && flat(entityPos, spot) > KratosTimings.SMASH_STAND) walkTo(spot, 1.0) else stopMoving()
            hold(dirTo(spot))
            yield(Unit)
        }
        stopMoving()
    }

    private fun hammer(ring: Telegraph.Ring) {
        val struck = ring.hits(fighters()).filter { strike(it, KratosTimings.SMASH_DAMAGE, BossDefense.Reach.BODY, ring.center.y) }
        for (player in struck) knock(player, player.entityPos.subtract(ring.center), 0.5, 0.4)
        Vfx.shockwave(sw, danger, ring.center, KratosTimings.SMASH_RADIUS + 0.5f, 14, 1.5f)
        telegraph.sound(HhaSounds.IMPACT, ring.center, 2.6f, 0.7f)
        if (struck.isNotEmpty()) telegraph.hitShake(ring.center, 3f)
    }

    // ---------------------------------------------------------------------------------------------
    // Befehle
    // ---------------------------------------------------------------------------------------------

    /** Ein Befehl; in Phase II paarweise (der zweite 10 Ticks nach dem Treffer des ersten, nie derselbe). */
    private fun commands(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        attacksSinceCommand = 0
        val paired = phase >= 1
        val first = pickCommand(lastCommand)
        lastCommand = first
        yieldAll(command(first, target, if (paired) KratosTimings.COMMAND_PAIR_GAP else null))
        if (!paired || over) return@sequence
        val next = target.takeIf(::present) ?: nearest() ?: return@sequence
        val second = pickCommand(first)
        lastCommand = second
        yieldAll(command(second, next, null))
    }

    /**
     * Welcher Befehl: „WEICHT!“ nur, wenn jemand nahe steht, „HERAN!“ vor allem gegen Ferne, aber nur, wenn jeder den
     * sicheren Kreis erreichen kann ([comeReachable]), „KNIET!“ nur mit freier Bia. Geht nichts davon, „WEICHT!“ (trifft
     * dann niemanden, ist aber immer zu schaffen).
     */
    private fun pickCommand(exclude: Int): Int {
        val crowd = fighters()
        val near = crowd.any { flatDistance(it) < KratosTimings.BACK_RADIUS + 1.0 }
        val far = crowd.any { flatDistance(it) > KratosTimings.COME_SAFE_RADIUS + 1.0 }
        val weights = linkedMapOf(
            KratosAction.CMD_BACK to (if (near) 3 else 0),
            KratosAction.CMD_COME to (if (!comeReachable()) 0 else if (far) 3 else 1),
            KratosAction.CMD_KNEEL to (if (biaFree()) 2 else 0),
        )
        weights.remove(exclude)
        val total = weights.values.sum()
        if (total <= 0) {
            return if (exclude != KratosAction.CMD_COME && comeReachable()) KratosAction.CMD_COME else KratosAction.CMD_BACK
        }
        var roll = random.nextInt(total)
        for ((kind, weight) in weights) {
            roll -= weight
            if (roll < 0) return kind
        }
        return weights.keys.last()
    }

    /** [recover]: Ticks nach dem Treffer, bevor es weitergeht (`null` = das Fenster aus der Tabelle). */
    private fun command(kind: Int, target: ServerPlayerEntity, recover: Int?): Sequence<Unit> = when (kind) {
        KratosAction.CMD_BACK -> commandBack(target, recover ?: KratosTimings.BACK_F)
        KratosAction.CMD_KNEEL -> when {
            biaFree() -> commandKneel(target, recover)
            comeReachable() -> commandCome(target, recover ?: KratosTimings.BASH_F)
            else -> commandBack(target, recover ?: KratosTimings.BACK_F)
        }
        else -> commandCome(target, recover ?: KratosTimings.BASH_F)
    }

    /** Das Befehlswort groß als Titel an alle Teilnehmer, darunter kurz das Gegenspiel; dazu der Befehlston. */
    private fun shout(cmd: String, vararg args: Any) {
        val title = Text.translatable("hha.kratos.cmd.$cmd").styled { it.withColor(KratosTimings.COMMAND_COLOR).withBold(true) }
        val sub = Text.translatable("hha.kratos.cmd.$cmd.sub", *args).styled { it.withColor(KratosTimings.COMMAND_SUB_COLOR) }
        for (player in viewers()) {
            player.networkHandler.sendPacket(TitleFadeS2CPacket(KratosTimings.COMMAND_TITLE_IN, KratosTimings.COMMAND_TITLE_STAY, KratosTimings.COMMAND_TITLE_OUT))
            player.networkHandler.sendPacket(SubtitleS2CPacket(sub))
            player.networkHandler.sendPacket(TitleS2CPacket(title))
        }
        telegraph.sound(HhaSounds.COMMAND, entityPos, 3f, 1f)
    }

    /** „WEICHT!“: Kreis Radius 7 um ihn, W 34, S 22 (Stampfen); wer getroffen wird, fliegt nach außen. F 14. */
    private fun commandBack(target: ServerPlayerEntity, recover: Int): Sequence<Unit> = sequence {
        stopMoving()
        hold(dirTo(target.entityPos))
        kit.act(KratosAction.CMD_BACK)
        shout("back")
        val group = attack()
        val ring = group.ring(ground(entityPos), KratosTimings.BACK_RADIUS, KratosTimings.BACK_W)
        wait(ring.ticks)
        val struck = ring.hits(fighters()).filter { strike(it, KratosTimings.BACK_DAMAGE) }
        for (player in struck) knock(player, dirTo(player.entityPos), KratosTimings.BACK_KNOCKBACK, 0.4)
        Vfx.shockwave(sw, danger, ring.center, KratosTimings.BACK_RADIUS, 16, 1.2f)
        telegraph.sound(HhaSounds.IMPACT, entityPos, 2.8f, 0.6f)
        if (struck.isNotEmpty()) telegraph.hitShake(entityPos, 3f)
        done(group)
        wait(recover)
        hold(null)
        kit.act(0)
    }

    /**
     * „HERAN!“: Gefahr außerhalb Radius 7 um ihn bis zum Platzrand, W ≥ 40 (bis der fernste Mitkämpfer den sicheren
     * Kreis erreichen kann, [comeTicks]), S 22; unmittelbar danach Schildstoß in Blickrichtung (gleiche
     * Ansage-Gruppe). Gegenspiel: zu ihm, dann hinter ihn.
     */
    private fun commandCome(target: ServerPlayerEntity, recover: Int): Sequence<Unit> = sequence {
        stopMoving()
        val dir = dirTo(target.entityPos)
        hold(dir)
        shout("come")
        val group = attack()
        // Ein Ring um ihn, der den ganzen Platz bis zum Rand deckt (auch wenn er nicht in der Mitte steht).
        val outer = (flat(entityPos, center()) + radius() + 1.0).toFloat()
        val ring = group.ring(ground(entityPos), outer, comeTicks(), inner = KratosTimings.COME_SAFE_RADIUS)
        // Längere Ansage: Die Geste beginnt später, der Ruck fällt mit dem Treffer.
        val lead = (ring.ticks - KratosTimings.COME_HIT).coerceAtLeast(0)
        wait(lead)
        kit.act(KratosAction.CMD_COME)
        wait(ring.ticks - lead)
        val struck = ring.hits(fighters()).filter { strike(it, KratosTimings.COME_DAMAGE) }
        telegraph.sound(HhaSounds.IMPACT, entityPos, 2.6f, 0.5f)
        if (struck.isNotEmpty()) telegraph.hitShake(entityPos, 2.5f)
        kit.act(KratosAction.BASH)
        yieldAll(shieldBash(group, facing(), recover))
        done(group)
    }

    /**
     * „KNIET!“: Bia springt an den Rand und läuft mit ihrer Klinge quer über den Platz (W 30, Lauf 30 Ticks). Getroffen
     * wird jeder Mitkämpfer auf dem Platz, der **in dem Moment, in dem die Klinge seine Stelle überstreicht**, nicht
     * schleicht (S 26, Höhe egal). Wer schleicht, bekommt nichts. Ihre Bahn führt nicht durch Kratos.
     */
    private fun commandKneel(target: ServerPlayerEntity, recover: Int?): Sequence<Unit> = sequence {
        val b = bia?.takeIf { !it.isRemoved } ?: return@sequence
        biaBusy = true
        stopMoving()
        hold(dirTo(target.entityPos))
        kit.act(KratosAction.CMD_KNEEL)
        val sneak = Text.keybind("key.sneak")
        shout("kneel", sneak)
        hintWith("hha.kratos.hint.kneel", sneak)
        say("kneel")
        val c = center()
        val r = radius()
        val group = attack()
        val ring = group.ring(Vec3d(c.x, floorY() + 0.05, c.z), r.toFloat(), KratosTimings.KNEEL_W)
        val path = sweepPath()
        // Bia springt an den Startrand, die Klingen seitlich ausgestreckt (Modell: Lauf ab 30).
        b.kit.act(BiaAction.SWEEP)
        b.scripted(true)
        val from = b.entityPos
        for (t in 1..KratosTimings.KNEEL_LEAP) {
            b.place(arc(from, path.at(-path.half), t.toDouble() / KratosTimings.KNEEL_LEAP, KratosTimings.BIA_LEAP_LIFT))
            b.face(path.dir)
            yield(Unit)
        }
        wait(group.ticks)
        group.hold(KratosTimings.KNEEL_RUN + 2)
        telegraph.sound(HhaSounds.WHOOSH, b.entityPos, 2.6f, 0.7f)
        val passed = HashSet<UUID>()
        for (k in 1..KratosTimings.KNEEL_RUN) {
            if (over) break
            val before = if (k == 1) -r - 1.0 else -r + 2.0 * r * (k - 1) / KratosTimings.KNEEL_RUN
            val front = -r + 2.0 * r * k / KratosTimings.KNEEL_RUN
            b.place(path.at(front))
            b.face(path.dir)
            moveBlades(path, front, r)
            for (player in fighters()) {
                if (player.uuid in passed) continue
                val at = along(player.entityPos, c, path.dir).coerceIn(-r, r)
                if (at <= before || at > front) continue
                passed.add(player.uuid)
                // „KNIET!“ prüft nur das Schleichen (und ob er auf dem Platz steht, wie die Ansage zeigt).
                if (player.isSneaking || !ring.contains(player)) continue
                if (biaStrike(player, KratosTimings.KNEEL_DAMAGE, BossDefense.Reach.SKY)) {
                    knock(player, path.dir, KratosTimings.KNEEL_KNOCKBACK, 0.25)
                }
            }
            if (k % 10 == 0) telegraph.sound(HhaSounds.WHOOSH, b.entityPos, 2.2f, 0.9f)
            yield(Unit)
        }
        clearBlades()
        group.end()
        done(group)
        hold(null)
        kit.act(0)
        launch(biaReturn(b))
        wait(recover ?: 0)
    }

    /** Bahn für „KNIET!“: von Bias Seite durch die Mitte bis zum anderen Rand; läuft sie an Kratos vorbei, seitlich versetzt. */
    private class SweepPath(val center: Vec3d, val dir: Vec3d, val side: Vec3d, val offset: Double, val half: Double, val floorY: Double) {
        /** Bias Fußpunkt, wenn die Klingenfront bei [front] steht. */
        fun at(front: Double): Vec3d {
            val f = front.coerceIn(-half, half)
            return Vec3d(center.x + side.x * offset + dir.x * f, floorY, center.z + side.z * offset + dir.z * f)
        }
    }

    private fun sweepPath(): SweepPath {
        val c = center()
        val dir = flatNorm(c.subtract(plinthBase()))
        val side = Vec3d(-dir.z, 0.0, dir.x)
        val reach = (radius() - KratosTimings.KNEEL_EDGE).coerceAtLeast(4.0)
        val lateral = (entityPos.x - c.x) * side.x + (entityPos.z - c.z) * side.z
        val offset = when {
            abs(lateral) >= KratosTimings.KNEEL_DODGE -> 0.0
            lateral >= 0.0 -> -KratosTimings.KNEEL_SHIFT
            else -> KratosTimings.KNEEL_SHIFT
        }
        val half = sqrt(max(1.0, reach * reach - offset * offset))
        return SweepPath(c, dir, side, offset, half, floorY())
    }

    /**
     * Die Klingenfront: Requisiten `BLADE` in einer Reihe quer zu ihrem Lauf, auf Kopfhöhe eines Stehenden. Eine Klinge
     * erscheint, sobald ihr Platz in der Reihe den Platzrand erreicht, und verschwindet am anderen Rand.
     */
    private fun moveBlades(path: SweepPath, front: Double, r: Double) {
        val c = path.center
        val spacing = KratosTimings.KNEEL_BLADE_SPACING
        val n = ceil(r / spacing).toInt()
        val y = floorY() + KratosTimings.KNEEL_BLADE_HEIGHT
        val step = path.dir.multiply(2.0 * r / KratosTimings.KNEEL_RUN)
        for (j in -n..n) {
            val lateral = path.offset + j * spacing
            // Die Klinge vor Bia sitzt etwas vor ihr, die übrigen auf der Front.
            val ahead = if (j == 0) 1.0 else 0.0
            val inside = lateral * lateral + front * front <= r * r
            val existing = blades[j]
            if (!inside) {
                if (existing != null) {
                    existing.discard()
                    blades.remove(j)
                }
                if (front > 0.0) bladesDone.add(j)
                continue
            }
            val pos = Vec3d(c.x + path.side.x * lateral + path.dir.x * (front + ahead), y, c.z + path.side.z * lateral + path.dir.z * (front + ahead))
            if (existing != null) {
                existing.moveTo(pos)
                continue
            }
            if (j in bladesDone) continue
            // Einen Schritt zurück erzeugen und vorziehen: So kennt der Client die Laufrichtung sofort.
            val bolt = prop(ScriptedBolt.spawn(sw, self, HermesBoltEntity.BLADE, KratosTimings.KNEEL_BLADE_SIZE, pos.subtract(step))) ?: continue
            bolt.moveTo(pos)
            blades[j] = bolt
        }
    }

    private fun clearBlades() {
        for (bolt in blades.values) bolt.discard()
        blades.clear()
        bladesDone.clear()
    }

    // ---------------------------------------------------------------------------------------------
    // Bias Sturm und der Zusammenstoß
    // ---------------------------------------------------------------------------------------------

    /**
     * Bias Sturm (Saal-Gefahr): Sie hebt den Kopf (`LEAP`), eine Linie 2,6 breit vom Sockel durch das Ziel bis zum Rand,
     * W 18 (Phase II 16); dann läuft sie die Linie in 12 Ticks (`RUSH`) und trifft, wen sie überstreicht (S 20).
     * Phase II: dreimal hintereinander, jeweils neu gezielt (von dort, wo sie gerade steht). Danach zurück auf den Sockel.
     */
    private fun storm(b: BiaEntity, passes: Int, windup: Int): Sequence<Unit> = sequence {
        val group = telegraph.hazard(pace = Telegraph.Pace.HEAVY)
        if (group == null) {
            biaBusy = false
            return@sequence
        }
        say("storm")
        hint("hha.kratos.hint.storm")
        var origin = plinthBase()
        var fromPlinth = true
        for (pass in 0 until passes) {
            if (over || b.isRemoved) break
            val target = stormTarget() ?: break
            val dir = if (flat(target.entityPos, origin) < KratosTimings.STORM_MIN_AIM) flatNorm(center().subtract(origin)) else flatNorm(target.entityPos.subtract(origin))
            val start = point(origin, dir, if (fromPlinth) KratosTimings.STORM_PLINTH_CLEAR else 0.0)
            val end = edgePoint(start, dir)
            terrainLine(group, start, end, KratosTimings.STORM_WIDTH, windup)
            group.hold(group.ticks + KratosTimings.STORM_RUN + 4)
            b.kit.act(BiaAction.LEAP)
            b.face(dir)
            telegraph.sound(HhaSounds.CHARGE, b.entityPos, 2.0f, 1.3f)
            wait(group.ticks)
            if (over || b.isRemoved) break

            // Der Lauf.
            b.kit.act(BiaAction.RUSH)
            b.scripted(true)
            Vfx.trail(sw, danger, b, 0.6f, KratosTimings.STORM_RUN + 8)
            telegraph.sound(HhaSounds.DASH, start, 2.4f, 0.9f)
            val length = flat(start, end)
            val side = Vec3d(-dir.z, 0.0, dir.x)
            val struck = HashSet<UUID>()
            var done = 0.0
            var clashAt: Vec3d? = null
            for (k in 1..KratosTimings.STORM_RUN) {
                var reach = length * k / KratosTimings.STORM_RUN
                // Der Zusammenstoß: Läuft ihre Linie näher als 2 an ihm vorbei, endet ihr Lauf bei ihm.
                val ka = along(entityPos, start, dir)
                val kl = abs((entityPos.x - start.x) * side.x + (entityPos.z - start.z) * side.z)
                // Nur wenn er noch vor ihr steht (wer ihr hinterher in die Bahn tritt, wird nicht gerammt).
                val ahead = ka > 0.0 && ka <= length && ka >= done - 0.5
                if (canClash() && ahead && kl < KratosTimings.CLASH_DISTANCE && reach >= ka - KratosTimings.CLASH_STOP) {
                    reach = max(done, ka - KratosTimings.CLASH_STOP)
                    clashAt = point(start, dir, reach)
                }
                b.place(point(start, dir, reach))
                b.face(dir)
                for (player in fighters()) {
                    if (player.uuid in struck) continue
                    if (!Telegraph.inLine(player.entityPos, start, end, KratosTimings.STORM_WIDTH.toDouble(), player.width * 0.5)) continue
                    val at = along(player.entityPos, start, dir)
                    if (at < done - 0.8 || at > reach + 0.8) continue
                    struck.add(player.uuid)
                    if (biaStrike(player, KratosTimings.STORM_DAMAGE, BossDefense.Reach.BODY)) {
                        val away = if ((player.x - start.x) * side.x + (player.z - start.z) * side.z >= 0.0) side else side.multiply(-1.0)
                        knock(player, away, KratosTimings.STORM_KNOCKBACK, 0.35)
                        telegraph.sound(HhaSounds.HIT, player.entityPos, 1.6f, 0.9f)
                    }
                }
                done = reach
                yield(Unit)
                if (clashAt != null) break
            }
            val rammed = clashAt
            if (rammed != null) {
                group.end()
                yieldAll(clash(b, rammed, dir))
                return@sequence
            }
            origin = b.entityPos
            fromPlinth = false
        }
        group.end()
        yieldAll(biaReturn(b))
    }

    /** Ziel des Sturms: reihum unter den Mitkämpfern auf dem Platz. */
    private fun stormTarget(): ServerPlayerEntity? {
        val all = fighters()
        val crowd = all.filter { arena?.onFloor(it.entityPos) ?: true }.ifEmpty { all }
        if (crowd.isEmpty()) return null
        return crowd[Math.floorMod(stormTurn++, crowd.size)]
    }

    /** Kein Zusammenstoß im Auftritt, im Schildwurf und nach dem Sieg (diese Abläufe werden nie unterbrochen). */
    private fun canClash(): Boolean = !calm && !over && isAlive

    /** Bia rammt Kratos: beide taumeln, er ist [KratosTimings.CLASH_F] Ticks offen; danach springt sie zurück. */
    private fun clash(b: BiaEntity, at: Vec3d, dir: Vec3d): Sequence<Unit> = sequence {
        b.scripted(false)
        b.place(at)
        b.face(dir)
        b.kit.act(BiaAction.STAGGER)
        hint("hha.kratos.hint.clash")
        say("clash")
        Vfx.shockwave(sw, danger, ground(at), 2.5f, 12, 1.0f)
        telegraph.sound(HhaSounds.IMPACT, at, 2.8f, 0.7f)
        telegraph.hitShake(at, 3f)
        interrupt(staggered())
        wait(KratosTimings.BIA_STAGGER_END)
        yieldAll(biaReturn(b))
    }

    /** Kratos taumelt (unterbricht seinen Angriff): `STAGGER`, offen 80 Ticks (golden); der Schild zählt darin nicht. */
    private fun staggered(): Sequence<Unit> = sequence {
        if (guard.inCounter) guard.endCounter()
        attackGroup?.end()
        attackGroup = null
        stopMoving()
        hold(facing())
        kit.act(KratosAction.STAGGER)
        open(KratosTimings.CLASH_F, DuelBossEntity.OPEN_FACTOR)
        wait(KratosTimings.CLASH_F)
        close()
        hold(null)
        kit.act(0)
    }

    // ---------------------------------------------------------------------------------------------
    // Konter, Phasenwechsel, Sieg
    // ---------------------------------------------------------------------------------------------

    /** Konter `SHIELD_UP` (PLAN 4.3): Schild (Phase II Hammer) über den Kopf, Kreis um ihn 12 Ticks, S 16, Abwurf. */
    override fun counter(): Sequence<Unit> = sequence {
        guard.beginCounter()
        stopMoving()
        kit.act(KratosAction.SHIELD_UP)
        val group = attack()
        val ring = group.ring(ground(entityPos), guard.counterRadius().toFloat(), BossDefense.COUNTER_TICKS)
        telegraph.sound(HhaSounds.CHARGE, entityPos, 1.6f, 1.2f)
        wait(ring.ticks)
        for (player in fighters()) {
            if (!ring.contains(player)) continue
            strike(player, KratosTimings.COUNTER_DAMAGE, BossDefense.Reach.SKY)
            guard.counterHit(player)
        }
        telegraph.sound(HhaSounds.SHIELD, entityPos, 2.2f, 0.8f)
        guard.endCounter()
        done(group)
        wait(KratosTimings.SHIELD_UP_END - KratosTimings.SHIELD_UP_HIT)
        kit.act(0)
    }

    /** Phase II „Gewalt ohne Maß“: Er reißt den Schild vom Arm und schleudert ihn weg; dann beginnt er mit Befehlen. */
    override fun onPhase(newPhase: Int): Sequence<Unit>? = if (newPhase != 1) null else sequence {
        calm = true
        stopMoving()
        hold(facing())
        kit.act(KratosAction.DROP_SHIELD)
        say("phase2")
        telegraph.sound(HhaSounds.COMMAND, entityPos, 2.6f, 0.7f)
        wait(KratosTimings.DROP_SHIELD_GONE)
        shieldless = true
        shieldDownUntil = 0L
        dropShield()
        telegraph.sound(HhaSounds.SHIELD, entityPos, 2.6f, 0.5f)
        wait(KratosTimings.DROP_SHIELD_END - KratosTimings.DROP_SHIELD_GONE)
        hold(null)
        kit.act(0)
        calm = false
        // Die neue Phase zeigt sich sofort: zuerst ein Befehlspaar.
        attacksSinceCommand = KratosTimings.COMMAND_EVERY
    }

    /**
     * Der Schild landet links neben ihm und bleibt flach liegen (Requisit [KratosTimings.SHIELD_PROP_KIND]); er
     * verschwindet mit Kratos.
     */
    private fun dropShield() {
        val kind = KratosTimings.SHIELD_PROP_KIND
        if (kind !in HermesBoltEntity.OLYMP_KINDS) return
        val f = facing()
        val left = Vec3d(f.z, 0.0, -f.x)
        val at = ground(entityPos.add(left.multiply(KratosTimings.DROP_SHIELD_SIDE)))
        val bolt = HhaEntities.HERMES_BOLT.create(sw, SpawnReason.EVENT) ?: return
        bolt.kind = kind
        bolt.size = 1f
        bolt.damage = 0f
        bolt.visual = true
        bolt.maxAge = Int.MAX_VALUE
        bolt.setOwner(this)
        bolt.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0f)
        bolt.velocity = Vec3d.ZERO
        if (sw.spawnEntity(bolt)) shieldProp = bolt
    }

    /** Sieg: beide geben nach (Kratos `YIELD`, Bia kehrt als Statue auf ihren Sockel zurück); Beute über die Basis. */
    override fun victory(): Sequence<Unit> = sequence {
        over = true
        calm = true
        stopMoving()
        hold(null)
        clearBlades()
        kit.act(KratosAction.YIELD)
        bia?.takeIf { !it.isRemoved && !biaBusy }?.kit?.act(BiaAction.STATUE)
        say("yield")
        finishVictory()
        wait(VICTORY_LINGER)
        discard()
    }

    override fun remove(reason: RemovalReason) {
        if (!entityWorld.isClient) {
            bia?.discard()
            shieldProp?.discard()
            clearBlades()
        }
        super.remove(reason)
    }

    // ---------------------------------------------------------------------------------------------
    // Text
    // ---------------------------------------------------------------------------------------------

    /** Eine Sprechzeile (nur Text, PLAN 14 Entscheidung 4), jede höchstens einmal je Kampf. */
    private fun say(key: String) {
        if (!spoken.add(key)) return
        Cine.dialog(viewers(), Cine.SPEAKER_KRATOS, "hha.kratos.line.$key", KratosTimings.LINE_TICKS)
    }

    /** Wie [hint], aber mit Platzhaltern (z. B. die Schleich-Taste). */
    private fun hintWith(key: String, vararg args: Any) {
        if (!hintedWith.add(key)) return
        for (player in fighters()) player.sendMessage(Text.translatable(key, *args).formatted(Formatting.GOLD), true)
    }

    companion object {
        private val SHIELDLESS: TrackedData<Boolean> = DataTracker.registerData(KratosEntity::class.java, TrackedDataHandlerRegistry.BOOLEAN)

        /** Kennung des Befehls in der Angriffsauswahl („nie zweimal derselbe Angriff“ gilt für Befehle als Ganzes). */
        private const val MOVE_COMMAND = 100
        private const val COMMAND_ANY = 200.0

        /** Auftritt: Zusatz-Leine für die Treppe; er bleibt so weit vor dem nächsten Spieler stehen. */
        private const val ENTRANCE_LEASH = 14.0
        private const val ENTRANCE_KEEP = 3.0
        private const val ENTRANCE_STAIRS_MIN = 2.0

        /** `PhaseBar`: Themen ab 10 sind die Gefahrenpaletten. */
        private const val STYLE_THEMES = 10

        /** Schild: vorn heißt weniger als 60° zur Blickrichtung. */
        private val SHIELD_COS = cos(Math.toRadians(KratosTimings.SHIELD_HALF_ANGLE))

        /** Bias Sockel relativ zur Platzmitte (`OlympusLayout.PLINTH_LEFT` − Mitte der Insel E: −14, −10). */
        private val PLINTH_DX = (OlympusLayout.PLINTH_LEFT.x - OlympusLayout.E.center.x).toDouble()
        private val PLINTH_DZ = (OlympusLayout.PLINTH_LEFT.z - OlympusLayout.E.center.z).toDouble()

        fun attributes(): DefaultAttributeContainer.Builder = guardianAttributes()
    }
}

/**
 * Bia, die Gewalt (PLAN 5.4): **kein eigener Gegner**, sondern ein angesagter Angriff mit Körper wie die Zwillinge des
 * Caduceus. Nimmt nie Schaden, hat keine Leiste und keinen Namen, wählt nichts selbst ([autonomous] aus) und zählt in
 * keiner Zielsuche. Kratos erzeugt sie, setzt [leader] und führt sie Tick für Tick ([place], [face], [scripted],
 * `kit.act`). Ohne Anführer verschwindet sie. Sie spricht nie.
 */
class BiaEntity(type: EntityType<out BiaEntity>, world: World) : GuardianEntity(type, world) {

    override val kit = MiniBossKit(this, "entity.hha.bia", BossBar.Color.RED, Vfx.Style.CRIMSON)
    override val fightName = "bia"
    override val danger = Vfx.Style.CRIMSON
    override val tier: BossScaling.Tier get() = BossScaling.Tier.MOB
    override val family: BossScaling.Family get() = BossScaling.Family.OLYMP
    override val usesDefense: Boolean get() = false
    override val autonomous: Boolean get() = false
    override val regenerates: Boolean get() = false

    /** Der Kratos, der sie führt; ohne ihn verschwindet sie. */
    var leader: KratosEntity? = null

    private var lastDeflect = -100

    init {
        kit.stats(KratosTimings.HEALTH, KratosTimings.ARMOR, KratosTimings.ATTACK, KratosTimings.SPEED)
    }

    override fun tick() {
        if (!entityWorld.isClient && kit.barVisible) kit.barVisible = false
        super.tick()
        if (!entityWorld.isClient && leader?.isRemoved != false && age > 20) discard()
    }

    /** Spielt [event] (Sprung, Lauf, Rückkehr); danach steht sie still. */
    fun perform(event: Sequence<Unit>) = interrupt(event)

    /** Setzt sie an [at] (Kratos führt ihre Sprünge und Läufe selbst). */
    fun place(at: Vec3d) {
        setPosition(at.x, at.y, at.z)
        velocity = Vec3d.ZERO
    }

    /** Blick waagerecht in [dir] (Körper, Kopf und Gierwinkel gemeinsam). */
    fun face(dir: Vec3d) {
        if (dir.horizontalLengthSquared() < 1e-6) return
        val turn = (Math.toDegrees(atan2(dir.z, dir.x)) - 90.0).toFloat()
        yaw = turn
        bodyYaw = turn
        headYaw = turn
        pitch = 0f
    }

    /** Geführt (Sprung, Lauf): ohne Schwerkraft und ohne Kollision; sonst steht sie normal auf ihrem Platz. */
    fun scripted(on: Boolean) {
        noClip = on
        setNoGravity(on)
        stopMoving()
    }

    /** Kratos führt sie über den ganzen Platz: keine eigene Leine. */
    override fun leash(): Double = UNLEASHED

    override fun fighters(): List<ServerPlayerEntity> = leader?.fighters() ?: emptyList()

    override fun createMoves(): List<Move> = emptyList()

    /** Nimmt nie Schaden; Abprall-Ton höchstens einmal je Sekunde, einmal der Hinweis, dass Kratos der Gegner ist. */
    override fun acceptsHit(world: ServerWorld, source: DamageSource): Boolean {
        if (age - lastDeflect >= KratosTimings.BIA_DEFLECT_COOLDOWN && source.attacker != null) {
            lastDeflect = age
            world.playSound(null, x, y, z, HhaSounds.SHIELD, SoundCategory.HOSTILE, 1.2f, 1.1f)
        }
        if (source.attacker is PlayerEntity) hint("hha.kratos.hint.bia")
        return false
    }

    override fun onLonely() = discard()

    companion object {
        private const val UNLEASHED = 1000.0

        fun attributes(): DefaultAttributeContainer.Builder = guardianAttributes()
    }
}
