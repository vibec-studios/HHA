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
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.logic.boss.BossScaling
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Cover
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha.logic.boss.sig.BiaTarget
import dev.henny.hha.logic.boss.timings.KratosTimings
import dev.henny.hha.stage.scripts.KratosStages
import dev.henny.hha.world.arena.ArenaSpec
import dev.henny.hha.world.olymp.OlympusLayout
import net.minecraft.entity.Entity
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
    /** Nur Auftritt (Überarbeitung 3): Schild heben und vor sich auf den Boden schlagen (`KratosStages.INTRO`). */
    const val SLAM = 11
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
 *
 * **Überarbeitung 4 (P17, Signatur „Bia als Teilziel“, Logik in [BiaTarget]):**
 * - Bia hat eine Unterleiste (25 % seines effektiven Lebens, Deckel 12 % je Treffer). Verwundbar (golden) nur, solange
 *   sie taumelt: 80 Ticks nach dem Zusammenstoß, 20 Ticks nach „KNIET!“ am Rand ([openBia]); sonst prallt alles ab.
 * - Leiste leer → Szene `kratos.bia_break`: Die Statue zerspringt, ihr Sockel wird zu Trümmern ([biaBroken]). Ab dann
 *   gibt es weder Bias Sturm noch „KNIET!“; **Zorn**: statt eines fälligen Befehls der Doppelschlag (auch in Phase I),
 *   Pausen × 0,85 ([basePause]). Zerschlägt sein Hammer dann eine Standarte, steht er 40 Ticks offen.
 * - Standarten: Bias Sturm endet an der ersten ([BiaTarget.stormEnd]); ein Hammerfall näher als 2,5 zerschlägt sie.
 * - Treppenabsatz (+4): Bias Sturm und die Klingenreihe treffen dort nicht (Band Kern + 3); Schildstoß, Hammerfall,
 *   Doppelschlag, „WEICHT!“, „HERAN!“ schon (Band Kern + 7, Zweitform auf dem Absatz); der Hammer landet auf Kern oder
 *   Absatz, nie auf den Stufen.
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

    /**
     * Überarbeitung 3 (PLAN 2.1): Auftritt, Phasenwechsel und Niederlage sind Bühnen-Szenen ([KratosStages]); den
     * allgemeinen Kamera-Rahmen der Basis braucht es nicht.
     */
    override val cinematic: Boolean get() = false

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

    // --- Nur Darstellung (Szenen, Überarbeitung 3) ---

    /** Wohin er im Auftritt schreitet (fester Endzustand der Szene, auch beim Überspringen). */
    private var introGoal: Vec3d? = null

    /** Bia springt im Phasenwechsel vom Sockel und zurück ([biaShowLeap]). */
    private var biaShowing = false

    // --- Überarbeitung 4 (P17): Bia als Teilziel ---

    /** Bias Unterleiste (angelegt mit Kampfbeginn; nach [BiaTarget.Target.close] nie neu). */
    private var biaTarget: BiaTarget.Target? = null

    /** Bias Leiste ist leer: Sturm und „KNIET!“ entfallen, Kratos tobt (Zorn). Bleibt bis zum Kampfende. */
    private var biaBroken = false

    /** Der Bruch kam während einer Szene: Die Bruch-Szene läuft vor dem nächsten Angriff ([phaseEvent]). */
    private var breakDue = false

    /** Statue und Sockel sind schon zersprungen (einmal, auch beim Überspringen der Szene). */
    private var biaShattered = false

    /** Schon zerschlagene Standarten dieses Kampfs (Vertrags-IDs). */
    private val fallenStandards = HashSet<String>()

    /** Schon gezeigt: „Bia ist verwundbar“ (eigener Merker, weil der Zusammenstoß ihn mit seinem Hinweis abdeckt). */
    private var biaHinted = false

    /** So viele Stürme haben begonnen (der Deckungs-Hinweis kommt beim zweiten, der erste zeigt den Sturm selbst). */
    private var stormsBegun = 0

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

    /** Vertrag der Arena E (nur an der Station; sonst `null`: keine Standarten, kein Absatz, kein Sockelbruch). */
    private fun spec(): ArenaSpec? = arena?.spec

    /** Boden der Fläche, auf der [player] steht (Kern oder Absatz), für die Höhenregel der Treffer. */
    private fun floorUnder(player: ServerPlayerEntity): Double = arena?.floorAt(player.entityPos) ?: floorY()

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
        if (over || biaBroken) return null
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

    private fun biaFree(): Boolean = !over && !biaBroken && !biaBusy && bia?.isRemoved == false

    /** Bia ist fort (zersprungen, entfernt) oder der Kampf ist aus: Laufende Bia-Abläufe enden sofort. */
    private fun biaGone(b: BiaEntity): Boolean = over || biaBroken || b.isRemoved

    /** Bias Treffer: Sie ist die Angreiferin, Spielerzahl und Familie wie bei ihm, mit der Höhenregel. */
    private fun biaStrike(player: ServerPlayerEntity, base: Float, reach: BossDefense.Reach): Boolean {
        val b = bia ?: return strike(player, base, reach)
        if (!BossDefense.reaches(player, floorY(), reach, b.height.toDouble())) return false
        return player.damage(sw, sw.damageSources.mobAttack(b), base * damageScale)
    }

    /** Zurück auf den Sockel: Sprung (LEAP), Landung (RETURN), dann wieder Statue. */
    private fun biaReturn(b: BiaEntity): Sequence<Unit> = sequence {
        biaTarget?.shut()
        if (!biaGone(b)) {
            val home = biaHome()
            val from = b.entityPos
            val dir = flatNorm(home.subtract(from))
            b.kit.act(BiaAction.LEAP)
            b.scripted(true)
            for (t in 1..KratosTimings.BIA_FLIGHT) {
                if (biaGone(b)) return@sequence
                b.place(arc(from, home, t.toDouble() / KratosTimings.BIA_FLIGHT, KratosTimings.BIA_FLIGHT_LIFT))
                b.face(dir)
                yield(Unit)
            }
            b.place(home)
            b.face(flatNorm(center().subtract(home)))
            b.scripted(false)
            b.kit.act(BiaAction.RETURN)
            biaLanded(home, onPlinth = true)
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
     * Auftritt als Bühnen-Szene `kratos.intro` (PLAN 2.7): Bia ist Darsteller 1 und muss dafür schon stehen. Der
     * feste Endzustand ([entranceDone]) gilt auch beim Überspringen.
     */
    override fun framedEntrance(): Sequence<Unit> {
        ensureBia()
        return cinematics.staged(KratosStages.INTRO, entrance(), actors = cast(), finish = ::entranceDone)
    }

    /** Darsteller der Szenen: 0 = Kratos, 1 = Bia (falls sie steht). */
    private fun cast(): List<Entity> = listOfNotNull(this, bia?.takeIf { !it.isRemoved })

    /**
     * Auftritt: Bia steht schon als Statue auf ihrem Sockel; Kratos schreitet (an der Station) die Treppe herab bis zu
     * seinem Platz, hebt den Schild und schlägt ihn vor sich auf (Aktion `SLAM`), spricht; dann Titelkarte. Jeder
     * Schritt hier ist ein Tick der Szene, die Zeitmarken stehen in [KratosStages]. Der Kampf (Sturm, erster Angriff)
     * beginnt erst nach der Szene ([entranceDone]).
     */
    override fun entrance(): Sequence<Unit> = sequence {
        calm = true
        ensureBia()
        val players = viewers()
        Cine.music(players, music)
        val goal = post ?: entityPos
        introGoal = goal
        stairsStart()?.let {
            entering = true
            refreshPositionAndAngles(it.x, it.y, it.z, yawOf(flatNorm(goal.subtract(it))), 0f)
        }
        var t = 0
        var walked = 0
        while (walked < KratosTimings.ENTRANCE_WALK && t < KratosStages.INTRO_WALK && flat(entityPos, goal) > 1.2) {
            // Wer schon unten am Siegel steht, dem tritt er nicht auf die Füße.
            if ((nearest()?.let(::flatDistance) ?: 99.0) < ENTRANCE_KEEP) break
            walkTo(goal, 0.9)
            walked++
            t++
            yield(Unit)
        }
        stopMoving()
        entering = false
        while (t < KratosStages.INTRO_LENGTH) {
            when (t) {
                KratosStages.INTRO_SLAM_ACT -> kit.act(KratosAction.SLAM)
                KratosStages.INTRO_SLAM_ACT + KratosStages.SLAM_END -> kit.act(0)
                KratosStages.INTRO_LINE -> say("entrance")
                KratosStages.INTRO_TITLE -> Cine.title(players, "hha.kratos.title", "hha.kratos.title.sub", KratosTimings.ENTRANCE_TITLE)
            }
            if (kit.action == 0) nearest()?.let { lookControl.lookAt(it, 30f, 30f) }
            t++
            yield(Unit)
        }
    }

    /**
     * Fester Endzustand des Auftritts (auch übersprungen, auch ohne Zuschauer): Er steht an seinem Platz, Bia als
     * Statue auf dem Sockel, Musik läuft; jetzt erst beginnt die Uhr bis zum ersten Sturm (wie vorher nach dem Auftritt).
     */
    private fun entranceDone() {
        entering = false
        stopMoving()
        introGoal?.let { goal ->
            if (flat(entityPos, goal) > 1.2) refreshPositionAndAngles(goal.x, goal.y, goal.z, yawOf(flatNorm(center().subtract(goal))), 0f)
        }
        if (kit.action == KratosAction.SLAM) kit.act(0)
        ensureBia()
        biaTarget()
        Cine.music(viewers(), music)
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
        tickBiaTarget()
        if (over) return
        if (age % LANDING_HINT_EVERY == 0) landingHint()
        // Ohne Bia (zersprungen) gibt es keinen Sturm mehr: weder Saal-Gefahr noch Phase-II-Folge.
        if (biaBroken) return
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

    /** Einmal je Kampf, sobald jemand auf dem Treppenabsatz steht: was er dort schützt und was nicht. */
    private fun landingHint() {
        val s = spec() ?: return
        if (fighters().any { BiaTarget.onLanding(s, it.entityPos) }) hint("hha.kratos.hint.landing")
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
        biaTarget?.shut()
        // Abgebrochene Bruch-Szene: Der Endzustand (Bia fort, Sockel in Trümmern) gilt trotzdem.
        if (biaBroken && !biaShattered) shatterBia()
        if (breakDue) {
            breakDue = false
            biaBreakDone()
        }
        resetBia()
        biaBusy = false
        entering = false
        calm = over
        if (phase >= 1) shieldless = true
        nextStorm = age + KratosTimings.STORM_MIN_GAP
    }

    /**
     * Auswahl. Mit Bia wie bisher; ohne Bia (**Zorn**, P17): Wo ein Befehl fällig wäre ([commandDue]), kommt der
     * Doppelschlag ([MOVE_WRATH], auch in Phase I); Befehle („WEICHT!“/„HERAN!“) gibt es nur noch gegen Ferne.
     */
    override fun createMoves(): List<Move> = listOf(
        Move(KratosAction.BASH, 0.0..KratosTimings.BASH_REACH, 3, { !commandDue() }) { bash(it) },
        Move(KratosAction.SMASH, 0.0..KratosTimings.SMASH_MOVE_REACH, 3, { !commandDue() }) {
            if (phase >= 1) doubleSmash(it) else smash(it)
        },
        Move(MOVE_COMMAND, 0.0..COMMAND_ANY, 2, { if (biaBroken) preyFar() else commandDue() || preyFar() }) { commands(it) },
        Move(MOVE_WRATH, 0.0..KratosTimings.SMASH_MOVE_REACH, 3, { biaBroken && commandDue() }) { doubleSmash(it, wrath = true) },
    )

    /** Zorn: Pausen × [KratosTimings.WRATH_PAUSE] (nie unter 60 % der Grundpause, [BiaTarget.wrathPause]). */
    override fun basePause(): Int = if (biaBroken) BiaTarget.wrathPause(super.basePause()) else super.basePause()

    /** P24: Zorn und Letztes Gefecht zusammen, nie unter 60 % der Grundpause ([BiaTarget.wrathPause] mit [rageFactor]). */
    override fun pauseTicks(): Int = if (biaBroken) BiaTarget.wrathPause(super.basePause(), rageFactor) else super.pauseTicks()

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
            // Kurze Fresnel-Schale am Schildarm (höchstens einmal je Abprall-Ton, also je 10 Ticks).
            BossFx.dome(sw, danger, shieldArm(), SHIELD_DOME_RADIUS, SHIELD_DOME_LIFE)
        }
        if (source.attacker is PlayerEntity) hint("hha.kratos.hint.shield")
        return KratosTimings.SHIELD_FACTOR
    }

    /** Mitte des Schilds vor dem linken Arm (nur Bild): vorn, etwas links, auf halber Höhe. */
    private fun shieldArm(): Vec3d {
        val f = facing()
        val left = Vec3d(f.z, 0.0, -f.x)
        return entityPos.add(f.multiply(SHIELD_FRONT)).add(left.multiply(SHIELD_SIDE)).add(0.0, height * SHIELD_UP, 0.0)
    }

    /** Rechte Hand beim Befehlen (nur Bild): Er streckt sie vor oder hält sie vor die Brust. */
    private fun commandHand(): Vec3d {
        val f = facing()
        val right = Vec3d(-f.z, 0.0, f.x)
        return entityPos.add(f.multiply(HAND_FRONT)).add(right.multiply(HAND_SIDE)).add(0.0, height * HAND_UP, 0.0)
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
        // P17: trifft Kern und Absatz (Band Kern + 7), auf dem Absatz zusätzlich gezeigt.
        bandBoth(group)
        val cone = group.cone(ground(entityPos), dir, KratosTimings.BASH_RADIUS, KratosTimings.BASH_HALF_ANGLE, KratosTimings.BASH_W)
        echoOnLanding(group, cone)
        telegraph.sound(HhaSounds.CHARGE, entityPos, 1.8f, 0.6f)
        wait(cone.ticks)
        val struck = cone.hits(fighters()).filter { strike(it, KratosTimings.BASH_DAMAGE, BossDefense.Reach.BODY, floorUnder(it)) }
        for (player in struck) knock(player, dir, KratosTimings.BASH_KNOCKBACK, 0.45)
        // Druckwelle als kurzer Kegel-Schleier in Stoßrichtung (der eine Flächeneffekt, ersetzt die Sichel).
        BossFx.play(
            sw, "kratos.bash", danger, entityPos.add(0.0, BASH_FX_HEIGHT, 0.0), dir,
            a = KratosTimings.BASH_RADIUS, b = KratosTimings.BASH_HALF_ANGLE, life = BASH_FX_LIFE, impact = true,
        )
        telegraph.sound(HhaSounds.IMPACT, entityPos, 2.2f, 0.8f)
        if (struck.isNotEmpty()) bashKick(dir)
        // Der Schild hängt: von vorn zählt jetzt jeder Treffer voll.
        shieldDownUntil = sw.time + KratosTimings.BASH_F
        wait(recover)
        hold(null)
        kit.act(0)
    }

    /**
     * Ruck des Schildstoßes (statt Rütteln): Gieren in Stoßrichtung, je Zuschauer aus seiner Sicht (rechts vom Blick =
     * nach rechts), dazu ein Hauch Nicken. Nur in der Nähe; [BossFx.kick] verwirft ihn während einer laufenden Ansage.
     */
    private fun bashKick(dir: Vec3d) {
        for (player in viewers()) {
            if (player.squaredDistanceTo(entityPos) > KICK_RANGE * KICK_RANGE) continue
            val r = Math.toRadians(player.yaw.toDouble())
            val side = dir.x * -cos(r) + dir.z * -kotlin.math.sin(r)
            BossFx.kick(player, BASH_KICK_PITCH, if (side >= 0.0) BASH_KICK_YAW else -BASH_KICK_YAW, ticks = KICK_TICKS)
        }
    }

    /**
     * Hammerfall: zwei schwere Schritte, Kreis Radius 3,5 am Ziel, W 28, S 24; der Hammer steckt (F 26). P17: Zerschlägt
     * er eine Standarte und ist Bia schon zersprungen, steht er stattdessen [KratosTimings.STANDARD_OPEN_F] offen.
     */
    private fun smash(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        attacksSinceCommand++
        stopMoving()
        val spot = smashSpot(target)
        hold(dirTo(spot))
        kit.act(KratosAction.SMASH)
        val group = attack()
        val ring = smashRing(group, spot, KratosTimings.SMASH_W, null)
        telegraph.sound(HhaSounds.CHARGE, entityPos, 2.0f, 0.55f)
        yieldAll(stepTo(spot, ring.ticks))
        val toppled = hammer(ring)
        done(group)
        if (toppled && biaBroken) yieldAll(stuckInStandard()) else wait(KratosTimings.SMASH_F)
        hold(null)
        kit.act(0)
    }

    /**
     * Doppelschlag (Phase II; ohne Bia auch als **Zorn** statt eines fälligen Befehls, [wrath]): der zweite Kreis 18
     * Ticks später am neuen Standort des Ziels (eigene Ansage). Zahlen wie in Phase II. Steckt der erste Hammer ohne
     * Bia in einer Standarte, entfällt der zweite (er steht offen).
     */
    private fun doubleSmash(target: ServerPlayerEntity, wrath: Boolean = false): Sequence<Unit> = sequence {
        if (wrath) attacksSinceCommand = 0 else attacksSinceCommand++
        stopMoving()
        val first = smashSpot(target)
        hold(dirTo(first))
        kit.act(KratosAction.DOUBLE_SMASH)
        val group = attack()
        val ring = smashRing(group, first, KratosTimings.SMASH_W, null)
        telegraph.sound(HhaSounds.CHARGE, entityPos, 2.0f, 0.5f)
        yieldAll(stepTo(first, ring.ticks))
        if (hammer(ring) && biaBroken) {
            done(group)
            yieldAll(stuckInStandard())
            hold(null)
            kit.act(0)
            return@sequence
        }
        val next = target.takeIf(::present) ?: nearest()
        val second = next?.let(::smashSpot) ?: first
        hold(dirTo(second))
        val again = smashRing(group, second, KratosTimings.DOUBLE_GAP, Telegraph.Pace.FOLLOW_UP)
        wait(again.ticks)
        val toppled = hammer(again, second = true)
        done(group)
        if (toppled && biaBroken) yieldAll(stuckInStandard()) else wait(KratosTimings.SMASH_F)
        hold(null)
        kit.act(0)
    }

    /**
     * Kreis des Hammers am Punkt [spot] (schon auf Kern oder Absatz geklemmt, [smashSpot]). Auf dem Kern: Band bis
     * Kern + 7 (trifft auch Stufen und Absatz) und, falls er über den Absatz reicht, dort eine Zweitform. Auf dem
     * Absatz: Band um den Absatz.
     */
    private fun smashRing(group: Telegraph.Fight.Group, spot: Vec3d, ticks: Int, pace: Telegraph.Pace?): Telegraph.Ring {
        val s = spec()
        val landing = s?.let(BiaTarget::landing)
        if (s != null && landing != null && BiaTarget.onLanding(s, spot)) {
            group.band(landing.floorY.toDouble(), below = 1.0, above = KratosTimings.BAND_CORE_ABOVE)
            return group.ring(spot, KratosTimings.SMASH_RADIUS, ticks, pace = pace)
        }
        bandBoth(group)
        val ring = group.ring(spot, KratosTimings.SMASH_RADIUS, ticks, pace = pace)
        echoOnLanding(group, ring, pace)
        return ring
    }

    /**
     * P17 (PLAN 4.3, Ersatz für den Zusammenstoß): Der Hammer steckt in der zerschlagenen Standarte; er taumelt
     * [KratosTimings.STANDARD_OPEN_F] Ticks offen (goldene Rune an ihm, wie jedes Fenster).
     */
    private fun stuckInStandard(): Sequence<Unit> = sequence {
        hint("hha.kratos.hint.standard_stuck")
        telegraph.sound(HhaSounds.ARENA_STUCK, entityPos, 2.2f, 0.8f)
        kit.act(KratosAction.STAGGER)
        open(KratosTimings.STANDARD_OPEN_F, DuelBossEntity.OPEN_FACTOR)
        wait(KratosTimings.STANDARD_OPEN_F)
        close()
    }

    /** Band „Kern und Absatz“ für die nächsten Formen der Gruppe (Kernboden bis + [KratosTimings.BAND_BOTH_ABOVE]). */
    private fun bandBoth(group: Telegraph.Fight.Group) {
        group.band(floorY(), below = 1.0, above = KratosTimings.BAND_BOTH_ABOVE)
    }

    /**
     * Zweitform auf dem Absatz für [shape], wenn sie ihn waagerecht erreicht ([BiaTarget.landingEcho]; nur Bild,
     * getroffen wird über [shape]). Setzt das Band der Gruppe danach wieder auf „Kern und Absatz“.
     */
    private fun echoOnLanding(group: Telegraph.Fight.Group, shape: Telegraph.Shape, pace: Telegraph.Pace? = null) {
        val s = spec() ?: return
        BiaTarget.landingEcho(group, s, shape.remaining, pace) { x, z -> shape.contains(Vec3d(x, shape.origin.y, z)) }
        bandBoth(group)
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

    /**
     * Kreismitte: am Ziel, aber höchstens [KratosTimings.SMASH_REACH] vor ihm. P17: an der Station auf Kern oder
     * Treppenabsatz geklemmt, nie auf die Stufen dazwischen ([BiaTarget.clampSmash]); steht das Ziel oben, landet der
     * Hammer oben, sofern er so weit reicht.
     */
    private fun smashSpot(target: ServerPlayerEntity): Vec3d {
        val dir = dirTo(target.entityPos)
        val at = if (flatDistance(target) <= KratosTimings.SMASH_REACH) target.entityPos else entityPos.add(dir.multiply(KratosTimings.SMASH_REACH))
        val s = spec() ?: return ground(at)
        return ground(BiaTarget.clampSmash(s, entityPos, at, KratosTimings.SMASH_REACH, BiaTarget.onLanding(s, target.entityPos)))
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

    /**
     * Der Hammer schlägt auf. Bild: `kratos.smash` (der eine Flächeneffekt: Marmor reißt auf, fünf echte Blöcke, Staub,
     * statt der Stoßwelle), Ruck 1,5° (zweiter Schlag [second] 2,5°) für alle in der Nähe statt Rütteln.
     */
    private fun hammer(ring: Telegraph.Ring, second: Boolean = false): Boolean {
        val struck = ring.hits(fighters()).filter { strike(it, KratosTimings.SMASH_DAMAGE, BossDefense.Reach.BODY, floorUnder(it)) }
        for (player in struck) knock(player, player.entityPos.subtract(ring.center), 0.5, 0.4)
        BossFx.play(
            sw, "kratos.smash", danger, ring.center, dirTo(ring.center),
            a = KratosTimings.SMASH_RADIUS, b = if (second) 1f else 0f, life = SMASH_FX_LIFE, impact = true,
        )
        telegraph.sound(HhaSounds.IMPACT, ring.center, 2.6f, 0.7f)
        BossFx.kick(sw, ring.center, if (second) SMASH_KICK_SECOND else SMASH_KICK_FIRST, ticks = KICK_TICKS, radius = KICK_RANGE.toFloat())
        return breakStandards(ring.center)
    }

    /**
     * P17: Stehende Standarten, deren Kasten näher als [KratosTimings.STANDARD_BREAK_RADIUS] an der Kreismitte [impact]
     * liegt, zerbrechen bis auf den Sockel (über `arena.edits`; sie kehren erst mit dem Kampfende zurück). Liefert, ob
     * eine fiel. Ein Schlag auf dem Absatz trifft keine (sie stehen auf dem Kern).
     */
    private fun breakStandards(impact: Vec3d): Boolean {
        val s = spec() ?: return false
        val edits = arena?.edits ?: return false
        if (BiaTarget.onLanding(s, impact)) return false
        val hits = BiaTarget.standardHits(sw, s, fallenStandards, impact)
        for (spot in hits) {
            fallenStandards.add(spot.id)
            BiaTarget.topple(edits, spot)
        }
        return hits.isNotEmpty()
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
        // Wem der Befehl gilt: ein Lichtbogen von seiner Hand zu Bia (ein Strang, 12 Ticks); am Client pulsieren
        // dabei Bias Augen auf (`kratos.command` = `core.arc` + Augen-Puls).
        bia?.takeIf { !it.isRemoved }?.let { b ->
            BossFx.play(
                sw, "kratos.command", danger, commandHand(), b.entityPos.add(0.0, b.height * 0.55, 0.0),
                a = COMMAND_ARC_BEND, b = COMMAND_ARC_WIDTH, c = 1f, life = COMMAND_ARC_LIFE, entity2 = b,
            )
        }
    }

    /** „WEICHT!“: Kreis Radius 7 um ihn, W 34, S 22 (Stampfen); wer getroffen wird, fliegt nach außen. F 14. */
    private fun commandBack(target: ServerPlayerEntity, recover: Int): Sequence<Unit> = sequence {
        stopMoving()
        hold(dirTo(target.entityPos))
        kit.act(KratosAction.CMD_BACK)
        shout("back")
        val group = attack()
        bandBoth(group)
        val ring = group.ring(ground(entityPos), KratosTimings.BACK_RADIUS, KratosTimings.BACK_W)
        echoOnLanding(group, ring)
        wait(ring.ticks)
        val struck = ring.hits(fighters()).filter { strike(it, KratosTimings.BACK_DAMAGE, BossDefense.Reach.BODY, floorUnder(it)) }
        for (player in struck) knock(player, dirTo(player.entityPos), KratosTimings.BACK_KNOCKBACK, 0.4)
        Vfx.shockwave(sw, danger, ring.center, KratosTimings.BACK_RADIUS, 16, 1.2f)
        // Beiwerk des Stampfens: kurze Risse unter seinem Fuß; Ruck statt Rütteln.
        BossFx.cracks(sw, danger, ring.center, STOMP_CRACK_RADIUS, STOMP_CRACKS)
        telegraph.sound(HhaSounds.IMPACT, entityPos, 2.8f, 0.6f)
        if (struck.isNotEmpty()) BossFx.kick(sw, entityPos, STOMP_KICK, ticks = KICK_TICKS, radius = KICK_RANGE.toFloat())
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
        bandBoth(group)
        val ring = group.ring(ground(entityPos), outer, comeTicks(), inner = KratosTimings.COME_SAFE_RADIUS)
        echoOnLanding(group, ring)
        // Längere Ansage: Die Geste beginnt später, der Ruck fällt mit dem Treffer.
        val lead = (ring.ticks - KratosTimings.COME_HIT).coerceAtLeast(0)
        wait(lead)
        kit.act(KratosAction.CMD_COME)
        wait(ring.ticks - lead)
        val struck = ring.hits(fighters()).filter { strike(it, KratosTimings.COME_DAMAGE, BossDefense.Reach.BODY, floorUnder(it)) }
        telegraph.sound(HhaSounds.IMPACT, entityPos, 2.6f, 0.5f)
        if (struck.isNotEmpty()) BossFx.kick(sw, entityPos, COME_KICK, ticks = KICK_TICKS, radius = KICK_RANGE.toFloat())
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
        // P17: Die Klingenreihe trifft nur auf dem Kern (Band Kern + 3); auf dem Treppenabsatz (+4) geht sie unter einem durch.
        group.band(floorY(), below = 1.0, above = KratosTimings.BAND_CORE_ABOVE)
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
        // Landung am Rand (kleiner als die Landung unten im Phasenwechsel: die Ansage liegt schon auf dem Platz).
        biaLanded(path.at(-path.half), onPlinth = false, small = true)
        wait(group.ticks)
        group.hold(KratosTimings.KNEEL_RUN + 2)
        telegraph.sound(HhaSounds.WHOOSH, b.entityPos, 2.6f, 0.7f)
        // Bias Klinge als breites Band auf Brusthöhe quer über den Platz, mit Nachzieh-Schweif; folgt ihr.
        BossFx.play(
            sw, "bia.blade", danger, Vec3d(c.x, floorY(), c.z), path.dir,
            a = r.toFloat(), b = KratosTimings.KNEEL_BLADE_HEIGHT.toFloat(), c = KratosTimings.KNEEL_RUN.toFloat(),
            life = KratosTimings.KNEEL_RUN + BLADE_FX_TAIL, entity = b,
        )
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
        launch(biaKneelRest(b))
        wait(recover ?: 0)
    }

    /**
     * P17: Nach „KNIET!“ steht Bia [KratosTimings.BIA_OPEN_KNEEL] Ticks am Rand und taumelt (verwundbar, golden), dann
     * springt sie zurück. Sie handelt dabei nicht. Zerspringt sie in der Zeit, endet der Ablauf.
     */
    private fun biaKneelRest(b: BiaEntity): Sequence<Unit> = sequence {
        if (!biaGone(b)) {
            b.kit.act(BiaAction.STAGGER)
            openBia(b, KratosTimings.BIA_OPEN_KNEEL, hintKey = "hha.kratos.hint.bia_open")
            for (t in 0 until KratosTimings.BIA_OPEN_KNEEL) {
                if (biaGone(b)) return@sequence
                yield(Unit)
            }
        }
        if (biaGone(b)) return@sequence
        yieldAll(biaReturn(b))
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
        val s = spec()
        // P17: Der Deckungs-Hinweis kommt beim zweiten Sturm (beim ersten zeigt der Sturm-Hinweis, was kommt).
        if (++stormsBegun >= 2 && s != null) hint("hha.kratos.hint.standard_cover")
        // P17: Der Sturm trifft nur auf dem Kern (Band Kern + 3); der Treppenabsatz (+4) liegt darüber.
        val bandMin = floorY() - 1.0
        val bandMax = floorY() + KratosTimings.BAND_CORE_ABOVE
        group.band(floorY(), below = 1.0, above = KratosTimings.BAND_CORE_ABOVE)
        var origin = plinthBase()
        var fromPlinth = true
        for (pass in 0 until passes) {
            if (biaGone(b)) break
            val target = stormTarget() ?: break
            val dir = if (flat(target.entityPos, origin) < KratosTimings.STORM_MIN_AIM) flatNorm(center().subtract(origin)) else flatNorm(target.entityPos.subtract(origin))
            val start = point(origin, dir, if (fromPlinth) KratosTimings.STORM_PLINTH_CLEAR else 0.0)
            // P17: An der Station endet die Linie an der ersten Standarte bzw. am rechten Sockel (echter Raycast).
            val end = edgePoint(start, dir).let { if (s != null) BiaTarget.stormEnd(sw, start, it) else it }
            terrainLine(group, start, end, KratosTimings.STORM_WIDTH, windup)
            group.hold(group.ticks + KratosTimings.STORM_RUN + 4)
            b.kit.act(BiaAction.LEAP)
            b.face(dir)
            telegraph.sound(HhaSounds.CHARGE, b.entityPos, 2.0f, 1.3f)
            val eye = BiaTarget.stormEye(start)
            if (s != null) Cover.mark(telegraph, s, BiaTarget.STORM_ATTACK, eye, group.ticks)
            while (group.ticks > 0) {
                if (biaGone(b)) {
                    group.cancel()
                    return@sequence
                }
                // Statuszeile „gedeckt/gesehen“ nur für den, auf den sie zielt.
                if (s != null && present(target)) Cover.status(target, Cover.shelter(sw, eye, target, s, BiaTarget.STORM_ATTACK) != null)
                yield(Unit)
            }
            if (biaGone(b)) break

            // Der Lauf.
            b.kit.act(BiaAction.RUSH)
            b.scripted(true)
            // Staubspur statt Lichtschweif: Staubwolken auf ihrer Linie (nie breiter als die Linie, nach dem Lauf weg).
            BossFx.play(
                sw, "bia.rush", danger, start, end, a = KratosTimings.STORM_WIDTH, b = KratosTimings.STORM_RUN.toFloat(),
                life = KratosTimings.STORM_RUN + RUSH_FX_TAIL, entity = b,
            )
            telegraph.sound(HhaSounds.DASH, start, 2.4f, 0.9f)
            val length = flat(start, end)
            val side = Vec3d(-dir.z, 0.0, dir.x)
            val struck = HashSet<UUID>()
            var done = 0.0
            var clashAt: Vec3d? = null
            for (k in 1..KratosTimings.STORM_RUN) {
                if (biaGone(b)) {
                    group.cancel()
                    return@sequence
                }
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
                    if (!Telegraph.inBand(player.y, bandMin, bandMax)) continue
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
        // P17: Beide taumeln, beide sind verwundbar; die Spieler entscheiden, auf wen sie schlagen.
        hint("hha.kratos.hint.clash_both")
        biaHinted = true
        openBia(b, KratosTimings.BIA_OPEN_CLASH)
        say("clash")
        Vfx.shockwave(sw, danger, ground(at), 2.5f, 12, 1.0f)
        // Beiwerk: Funken, wo Klinge auf Bronze trifft; Ruck Rollen statt Rütteln.
        BossFx.sparks(sw, danger, at.add(dir.multiply(CLASH_SPARK_AHEAD)).add(0.0, CLASH_SPARK_UP, 0.0), dir.multiply(-1.0).add(0.0, 0.6, 0.0), CLASH_SPARKS, CLASH_SPARK_SPEED)
        telegraph.sound(HhaSounds.IMPACT, at, 2.8f, 0.7f)
        BossFx.kick(sw, at, CLASH_KICK_PITCH, roll = CLASH_KICK_ROLL, ticks = KICK_TICKS, radius = KICK_RANGE.toFloat())
        interrupt(staggered())
        for (t in 0 until KratosTimings.BIA_STAGGER_END) {
            if (biaGone(b)) return@sequence
            yield(Unit)
        }
        yieldAll(biaReturn(b))
    }

    // ---------------------------------------------------------------------------------------------
    // P17: Bia als Teilziel (Logik in BiaTarget)
    // ---------------------------------------------------------------------------------------------

    /** Bias Unterleiste dieses Kampfs (beim ersten Bedarf angelegt; nach dem Schließen nie neu). */
    private fun biaTarget(): BiaTarget.Target? {
        biaTarget?.let { return if (it.closed) null else it }
        if (over || isRemoved) return null
        return BiaTarget.Target(sw, { viewers() }, ::admitBiaHit, ::onBiaBroken).also { biaTarget = it }
    }

    /**
     * Bia taumelt [ticks] Ticks: Leiste golden, goldene Rune unter ihr und Riss-Leuchten an der Statue
     * (`kratos.bia_open`). [hintKey] einmal je Kampf, falls noch kein Hinweis zu Bia kam.
     */
    private fun openBia(b: BiaEntity, ticks: Int, hintKey: String? = null) {
        if (biaGone(b) || cinematics.playing) return
        val t = biaTarget() ?: return
        if (t.broken) return
        t.open(ticks, sw.time)
        Vfx.rune(sw, Vfx.Style.GOLD, b.entityPos.add(0.0, BIA_RUNE_LIFT, 0.0), BIA_RUNE_RADIUS, ticks, b)
        BossFx.play(sw, "kratos.bia_open", Vfx.Style.GOLD, b.entityPos, life = ticks, entity = b)
        // Ton des Fensters (beim Zusammenstoß läutet schon Kratos' eigenes Fenster, darum nur mit eigenem Hinweis-Anlass).
        if (hintKey != null) telegraph.sound(HhaSounds.CHIME, b.entityPos, 1.6f, 1.15f)
        if (hintKey != null && !biaHinted) {
            biaHinted = true
            hint(hintKey)
        }
    }

    /** Jeden Tick: Fenster nach Ablauf zu, in Szenen und nach dem Sieg nie offen, Ort und Leiste nachführen. */
    private fun tickBiaTarget() {
        val t = biaTarget ?: return
        if (t.closed) return
        if (t.open && (over || cinematics.playing)) t.shut()
        val at = bia?.takeIf { !it.isRemoved }?.let { it.entityPos.add(0.0, it.height * 0.5, 0.0) }
        t.tick(sw.time, at)
    }

    /**
     * Zählt der Treffer [source] auf Bia? Nur von Kampfteilnehmern, die auf der Kampffläche stehen (wie Treffer auf
     * Kratos, [acceptsHit]); Tiere, Umgebung und Zuschauer zählen nicht.
     */
    private fun admitBiaHit(source: DamageSource): Boolean {
        val player = source.attacker as? ServerPlayerEntity ?: return false
        if (player !in fighters()) return false
        return acceptsHit(sw, source)
    }

    /** Ist Bia gerade verwundbar? (für [BiaEntity.damage]) */
    internal fun biaVulnerable(): Boolean = !over && !biaBroken && biaTarget?.open == true && !cinematics.playing

    /**
     * Ein Treffer auf Bia (aus [BiaEntity.damage]): nur, solange sie taumelt. Leben und Deckel rechnet die Unterleiste
     * (Teilziel ohne eigenes Wesen). Liefert, ob er gezählt hat; dazu ein paar goldene Funken an ihr.
     */
    internal fun hitBia(source: DamageSource, amount: Float): Boolean {
        if (!biaVulnerable()) return false
        val t = biaTarget ?: return false
        val taken = t.hit(amount, source)
        if (taken <= 0f) return false
        bia?.takeIf { !it.isRemoved }?.let {
            BossFx.sparks(sw, Vfx.Style.GOLD, it.entityPos.add(0.0, it.height * 0.6, 0.0), count = BIA_HIT_SPARKS, speed = BIA_HIT_SPARK_SPEED)
        }
        return true
    }

    /**
     * Bias Leiste ist leer (Rückruf der Unterleiste, mitten in einem Spielertreffer). Ab sofort entfallen Sturm und
     * „KNIET!“. Die Szene `kratos.bia_break` unterbricht, was er gerade tut (laufende Ansage wird zurückgenommen); läuft
     * schon eine Szene, kommt sie vor dem nächsten Angriff ([phaseEvent]).
     */
    private fun onBiaBroken() {
        if (biaBroken) return
        biaBroken = true
        biaTarget?.shut()
        if (over || isRemoved) return
        if (cinematics.playing) breakDue = true else interrupt(breakScene())
    }

    override fun phaseEvent(): Sequence<Unit>? {
        super.phaseEvent()?.let { return it }
        if (breakDue && !over) return breakScene()
        return null
    }

    private fun breakScene(): Sequence<Unit> {
        breakDue = false
        return cinematics.staged(KratosStages.BIA_BREAK, biaBreak(), actors = cast(), finish = ::biaBreakDone)
    }

    /**
     * Szene `kratos.bia_break` (überspringbar): Er hält inne (sein Fenster und jede laufende Ansage enden), Bia
     * zerspringt ([KratosStages.BREAK_SHATTER]), ihr Sockel wird zu Trümmern; dann brüllt er (Faust, `CMD_COME`).
     */
    private fun biaBreak(): Sequence<Unit> = sequence {
        calm = true
        attackGroup?.cancel()
        attackGroup = null
        close()
        clearBlades()
        stopMoving()
        bia?.takeIf { !it.isRemoved }?.let { hold(dirTo(it.entityPos)) }
        kit.act(0)
        var t = 0
        while (t < KratosStages.BREAK_LENGTH) {
            when (t) {
                KratosStages.BREAK_SHATTER -> shatterBia()
                KratosStages.BREAK_ROAR_ACT -> {
                    hold(null)
                    kit.act(KratosAction.CMD_COME)
                    telegraph.sound(HhaSounds.COMMAND, entityPos, 2.8f, 0.6f)
                }
                KratosStages.BREAK_LINE -> say("bia_break")
            }
            t++
            yield(Unit)
        }
        kit.act(0)
    }

    /** Statue und Sockel zerspringen (genau einmal): Effekt, Bia weg, Sockel über `arena.edits` zu Trümmern. */
    private fun shatterBia() {
        if (biaShattered) return
        biaShattered = true
        bia?.takeIf { !it.isRemoved }?.let { b ->
            BossFx.play(sw, "kratos.bia_break", danger, b.entityPos, flatNorm(b.entityPos.subtract(entityPos)), life = BREAK_FX_LIFE, entity = b)
            b.discard()
        }
        bia = null
        biaBusy = false
        clearBlades()
        val s = spec() ?: return
        val edits = arena?.edits ?: return
        BiaTarget.shatterPlinth(edits, s)
        val foot = s.cover(BiaTarget.PLINTH_LEFT).center
        BossFx.debris(sw, foot.add(0.0, 1.0, 0.0), PLINTH_DEBRIS_RADIUS, PLINTH_DEBRIS)
        telegraph.sound(HhaSounds.ARENA_CRUMBLE, foot, 2.4f, 0.8f)
    }

    /**
     * Fester Endzustand nach dem Bruch (auch übersprungen, auch ohne Zuschauer): Bia fort, Sockel in Trümmern, er steht
     * wieder bereit; der Zorn zeigt sich sofort (als Nächstes der Doppelschlag).
     */
    private fun biaBreakDone() {
        shatterBia()
        hold(null)
        if (kit.action == KratosAction.CMD_COME) kit.act(0)
        calm = false
        attacksSinceCommand = KratosTimings.COMMAND_EVERY
        hint("hha.kratos.hint.wrath")
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

    /**
     * Phase II „Gewalt ohne Maß“ als Bühnen-Szene `kratos.phase2`: Er reißt den Schild vom Arm und schleudert ihn weg
     * (Scherben), Bia springt vom Sockel herab und wieder hinauf. Der feste Endzustand ([phaseTwoDone]) gilt auch beim
     * Überspringen; erst danach geht der Kampf weiter (zuerst ein Befehlspaar).
     */
    override fun onPhase(newPhase: Int): Sequence<Unit>? =
        if (newPhase != 1) null else cinematics.staged(if (biaBroken) KratosStages.PHASE2_ALONE else KratosStages.PHASE2, phaseTwo(), actors = cast(), finish = ::phaseTwoDone)

    private fun phaseTwo(): Sequence<Unit> = sequence {
        calm = true
        stopMoving()
        hold(facing())
        kit.act(KratosAction.DROP_SHIELD)
        say("phase2")
        telegraph.sound(HhaSounds.COMMAND, entityPos, 2.6f, 0.7f)
        wait(KratosTimings.DROP_SHIELD_GONE)
        // Der Schild fliegt: Die Fresnel-Schale am Arm springt in Scherben.
        BossFx.dome(sw, danger, shieldArm(), SHIELD_DOME_RADIUS, SHIELD_SHATTER_LIFE, shatter = true)
        shieldless = true
        shieldDownUntil = 0L
        dropShield()
        telegraph.sound(HhaSounds.SHIELD, entityPos, 2.6f, 0.5f)
        Cine.music(viewers(), HhaSounds.THEME_KRATOS_2)
        wait(KratosTimings.DROP_SHIELD_END - KratosTimings.DROP_SHIELD_GONE)
        hold(null)
        kit.act(0)
        wait(KratosStages.PHASE2_BIA_START - KratosTimings.DROP_SHIELD_END)
        val b = bia?.takeIf { !it.isRemoved && !biaBusy }
        if (b != null) yieldAll(biaShowLeap(b))
    }

    /**
     * Nur Szene: Bia springt vom Sockel auf den Platz (Landung `bia.land`), steht kurz und springt rückwärts wieder
     * hinauf. Sie schaut dabei immer zum Platz. Danach ist sie wieder Statue; nichts davon greift an.
     */
    private fun biaShowLeap(b: BiaEntity): Sequence<Unit> = sequence {
        biaBusy = true
        biaShowing = true
        val home = biaHome()
        val toward = flatNorm(center().subtract(home))
        val foot = point(plinthBase(), toward, KratosStages.PHASE2_BIA_DROP)
        b.face(toward)
        b.kit.act(BiaAction.LEAP)
        wait(KratosStages.PHASE2_BIA_CROUCH)
        b.scripted(true)
        for (t in 1..KratosStages.PHASE2_BIA_DROP_FLIGHT) {
            b.place(arc(home, foot, t.toDouble() / KratosStages.PHASE2_BIA_DROP_FLIGHT, KratosStages.PHASE2_BIA_DROP_LIFT))
            b.face(toward)
            yield(Unit)
        }
        b.place(foot)
        b.scripted(false)
        b.kit.act(BiaAction.RETURN)
        biaLanded(foot, onPlinth = false)
        wait(KratosStages.PHASE2_BIA_STAND)
        b.kit.act(BiaAction.LEAP)
        wait(KratosStages.PHASE2_BIA_BACK_CROUCH)
        b.scripted(true)
        for (t in 1..KratosTimings.BIA_FLIGHT) {
            b.place(arc(foot, home, t.toDouble() / KratosTimings.BIA_FLIGHT, KratosTimings.BIA_FLIGHT_LIFT))
            b.face(toward)
            yield(Unit)
        }
        b.place(home)
        b.face(toward)
        b.scripted(false)
        b.kit.act(BiaAction.RETURN)
        biaLanded(home, onPlinth = true)
        wait(KratosTimings.BIA_RETURN_END)
        b.kit.act(BiaAction.STATUE)
        biaShowing = false
        biaBusy = false
    }

    /**
     * Fester Endzustand des Phasenwechsels: kein Schild (der liegt links neben ihm), Bia als Statue auf dem Sockel,
     * Musik der Phase II, und das Befehlspaar ist als Nächstes dran.
     */
    private fun phaseTwoDone() {
        shieldless = true
        shieldDownUntil = 0L
        if (shieldProp?.isRemoved != false) dropShield()
        if (biaShowing) {
            biaShowing = false
            biaBusy = false
            resetBia()
        }
        hold(null)
        if (kit.action == KratosAction.DROP_SHIELD) kit.act(0)
        Cine.music(viewers(), HhaSounds.THEME_KRATOS_2)
        calm = false
        // Die neue Phase zeigt sich sofort: zuerst ein Befehlspaar.
        attacksSinceCommand = KratosTimings.COMMAND_EVERY
    }

    /**
     * Bild einer Landung Bias (`bia.land`, der eine Flächeneffekt): Kuppel, Ring, Risse, echte Blöcke. Auf dem Sockel
     * kleiner ([onPlinth]); [small] = am Platzrand während einer laufenden Ansage (ohne Kuppel, wenige Risse).
     */
    private fun biaLanded(at: Vec3d, onPlinth: Boolean, small: Boolean = false) {
        val radius = when {
            onPlinth -> LAND_RADIUS_PLINTH
            small -> LAND_RADIUS_SMALL
            else -> LAND_RADIUS
        }
        val blocks = if (onPlinth || small) LAND_BLOCKS_SMALL else LAND_BLOCKS
        val cracks = if (onPlinth || small) LAND_CRACKS_SMALL else LAND_CRACKS
        BossFx.play(
            sw, "bia.land", danger, at, a = radius, b = blocks.toFloat(), c = cracks.toFloat(),
            d = if (small) 1f else 0f, life = LAND_FX_LIFE, impact = true,
        )
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

    /**
     * Sieg als Bühnen-Szene `kratos.defeat`: beide geben nach (Kratos `YIELD`: Faust an die Brust, dann aufs Knie; Bia
     * erstarrt als Statue auf ihrem Sockel); Beute über die Basis, danach der Sieg-Stinger des Olymp. Er bleibt so lange
     * stehen, bis die Szene sicher zu Ende ist ([DEFEAT_LINGER]), erst dann verschwindet er.
     */
    override fun victory(): Sequence<Unit> {
        // Bias Leiste verschwindet mit dem Sieg (auch wenn sie nie gebrochen wurde).
        biaTarget?.close()
        return cinematics.staged(if (biaBroken) KratosStages.DEFEAT_ALONE else KratosStages.DEFEAT, defeat(), actors = cast(), finish = ::defeatDone)
    }

    private fun defeat(): Sequence<Unit> = sequence {
        over = true
        calm = true
        biaTarget?.close()
        stopMoving()
        hold(null)
        clearBlades()
        kit.act(KratosAction.YIELD)
        bia?.takeIf { !it.isRemoved && !biaBusy }?.kit?.act(BiaAction.STATUE)
        say("yield")
        finishVictory()
        Cine.music(viewers(), HhaSounds.VICTORY_OLYMP)
        wait(DEFEAT_LINGER)
        discard()
    }

    /** Fester Endzustand der Niederlage: Er kniet (`YIELD`), Bia steht als Statue auf dem Sockel (sofern sie frei ist). */
    private fun defeatDone() {
        if (kit.action != KratosAction.YIELD) kit.act(KratosAction.YIELD)
        if (bia?.isRemoved == false && !biaBusy) resetBia()
    }

    override fun remove(reason: RemovalReason) {
        if (!entityWorld.isClient) {
            // P17: Bias Leiste schließen (Abbruch, `/hha boss stop`, leere Arena, Serverstopp, Entladen, Sieg).
            biaTarget?.close()
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

        /** P17: Kennung des Zorn-Doppelschlags (ohne Bia an der Stelle eines fälligen Befehls). */
        private const val MOVE_WRATH = 101

        /** P17: so oft (Ticks) prüfen, ob jemand auf dem Treppenabsatz steht (einmaliger Hinweis). */
        private const val LANDING_HINT_EVERY = 10

        // --- P17, nur Darstellung (keine Kampfzahl) ---
        /** Goldene Rune unter Bia, solange sie verwundbar ist. */
        private const val BIA_RUNE_RADIUS = 1.6f
        private const val BIA_RUNE_LIFT = 0.08
        /** Goldene Funken je gezähltem Treffer auf Bia. */
        private const val BIA_HIT_SPARKS = 5
        private const val BIA_HIT_SPARK_SPEED = 0.3f
        /** `kratos.bia_break`: Lebenszeit; Trümmer am Sockel. */
        private const val BREAK_FX_LIFE = 70
        private const val PLINTH_DEBRIS_RADIUS = 1.6f
        private const val PLINTH_DEBRIS = 6

        /** Auftritt: Zusatz-Leine für die Treppe; er bleibt so weit vor dem nächsten Spieler stehen. */
        private const val ENTRANCE_LEASH = 14.0
        private const val ENTRANCE_KEEP = 3.0
        private const val ENTRANCE_STAIRS_MIN = 2.0

        /** `PhaseBar`: Themen ab 10 sind die Gefahrenpaletten. */
        private const val STYLE_THEMES = 10

        // --- Nur Darstellung (Überarbeitung 3, PLAN 2.7): Effekte, Rucke, Szenen. Keine Kampfzahl. ---

        /** Kamera-Ruck: Dauer, Reichweite um den Treffer. */
        private const val KICK_TICKS = 6
        private const val KICK_RANGE = 24.0
        /** Schildstoß: Gieren in Stoßrichtung, dazu wenig Nicken (Grad). */
        private const val BASH_KICK_YAW = 2f
        private const val BASH_KICK_PITCH = 0.4f
        /** Hammerfall: Nicken, erster Schlag und zweiter Schlag des Doppelschlags (Grad). */
        private const val SMASH_KICK_FIRST = 1.5f
        private const val SMASH_KICK_SECOND = 2.5f
        /** „WEICHT!“ (Stampfen) und „HERAN!“ (Ruck): Nicken bei einem Treffer (Grad). */
        private const val STOMP_KICK = 1.5f
        private const val COME_KICK = 1.2f
        /** Zusammenstoß: Rollen 2°, wenig Nicken. */
        private const val CLASH_KICK_ROLL = 2f
        private const val CLASH_KICK_PITCH = 0.5f

        /** `kratos.bash`: Höhe des Schleiers über dem Boden und Lebenszeit. */
        private const val BASH_FX_HEIGHT = 1.3
        private const val BASH_FX_LIFE = 12
        /** `kratos.smash`: Lebenszeit (Risse und Staub). */
        private const val SMASH_FX_LIFE = 60
        /** Stampfen: kurze Risse unter seinem Fuß. */
        private const val STOMP_CRACKS = 4
        private const val STOMP_CRACK_RADIUS = 2.6f
        /** Zusammenstoß: Funken an der Stelle, wo Klinge auf Bronze trifft. */
        private const val CLASH_SPARKS = 14
        private const val CLASH_SPARK_SPEED = 0.5f
        private const val CLASH_SPARK_AHEAD = 0.8
        private const val CLASH_SPARK_UP = 1.7

        /** Schild (Phase I): Fresnel-Schale beim Abprall, Scherben beim Wegwerfen. Lage vor dem linken Arm. */
        private const val SHIELD_DOME_RADIUS = 1.6f
        private const val SHIELD_DOME_LIFE = 8
        private const val SHIELD_SHATTER_LIFE = 18
        private const val SHIELD_FRONT = 0.9
        private const val SHIELD_SIDE = 0.55
        private const val SHIELD_UP = 0.45

        /** Befehl: Bogen von der rechten Hand zu Bia (ein Strang, 12 Ticks). */
        private const val COMMAND_ARC_LIFE = 12
        private const val COMMAND_ARC_BEND = 0.22f
        private const val COMMAND_ARC_WIDTH = 0.42f
        private const val HAND_FRONT = 1.1
        private const val HAND_SIDE = 0.8
        private const val HAND_UP = 0.62

        /** `bia.blade` und `bia.rush` laufen so viele Ticks über den Lauf hinaus aus. */
        private const val BLADE_FX_TAIL = 8
        private const val RUSH_FX_TAIL = 14

        /** `bia.land`: Radius auf dem Platz, auf dem Sockel, am Rand während „KNIET!“; Blöcke, Risse, Lebenszeit. */
        private const val LAND_RADIUS = 2.4f
        private const val LAND_RADIUS_PLINTH = 1.4f
        private const val LAND_RADIUS_SMALL = 1.6f
        private const val LAND_BLOCKS = 6
        private const val LAND_BLOCKS_SMALL = 3
        private const val LAND_CRACKS = 6
        private const val LAND_CRACKS_SMALL = 3
        private const val LAND_FX_LIFE = 60

        /** Nach dem Sieg bleibt er stehen, bis die Szene `kratos.defeat` sicher vorbei ist (sonst bricht sie ab). */
        private val DEFEAT_LINGER = maxOf(DuelBossEntity.VICTORY_LINGER, KratosStages.DEFEAT_LENGTH + 20)

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

    /**
     * Überarbeitung 4 (P17): Solange sie taumelt, gehen Treffer an ihre Unterleiste (Teilziel ohne eigenes Wesen, Leben
     * und Deckel rechnet Kratos' [BiaTarget.Target]); ihr eigenes Leben sinkt nie. Sonst prallt alles ab wie bisher.
     */
    override fun damage(world: ServerWorld, source: DamageSource, amount: Float): Boolean {
        val lead = leader
        if (lead != null && !lead.isRemoved && lead.biaVulnerable()) return lead.hitBia(source, amount)
        acceptsHit(world, source)
        return false
    }

    /** Nimmt keinen eigenen Schaden; Abprall-Ton höchstens einmal je Sekunde, einmal der Hinweis, wann sie verwundbar ist. */
    override fun acceptsHit(world: ServerWorld, source: DamageSource): Boolean {
        if (age - lastDeflect >= KratosTimings.BIA_DEFLECT_COOLDOWN && source.attacker != null) {
            lastDeflect = age
            world.playSound(null, x, y, z, HhaSounds.SHIELD, SoundCategory.HOSTILE, 1.2f, 1.1f)
        }
        if (source.attacker is PlayerEntity) hint("hha.kratos.hint.bia_stone")
        return false
    }

    override fun onLonely() = discard()

    companion object {
        private const val UNLEASHED = 1000.0

        fun attributes(): DefaultAttributeContainer.Builder = guardianAttributes()
    }
}
