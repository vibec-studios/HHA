package dev.henny.hha.entity.miniboss

import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.HermesBoltEntity
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossDefense
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Cover
import dev.henny.hha.logic.boss.SceneKit
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha.logic.boss.sig.KampeShackles
import dev.henny.hha.logic.boss.timings.KampeSigTimings
import dev.henny.hha.logic.world.TartarosChain
import dev.henny.hha.stage.scripts.KampeStages
import dev.henny.hha.world.TartarusArenas
import dev.henny.hha.world.arena.ArenaSpec
import dev.henny.hha.world.arena.Level
import net.minecraft.entity.EntityStatuses
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.attribute.DefaultAttributeContainer
import net.minecraft.entity.boss.BossBar
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.data.DataTracker
import net.minecraft.entity.data.TrackedData
import net.minecraft.entity.data.TrackedDataHandlerRegistry
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundEvents
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import net.minecraft.world.World
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Animationszustände der Kampe; die Werte gehen über die Leitung, also nur hinten anfügen. Zeiten: `BossTimings.Kampe`. */
object KampePose {
    const val IDLE = 0

    /** Schwanzfeger flach über den Boden. */
    const val TAIL_SWEEP = 1

    /** Giftfächer. */
    const val SPIT = 2

    /** Skorpionstich; danach steckt der Stachel (offen). */
    const val STING = 3

    /** Windet sich am angeketteten Titanen hoch. */
    const val COIL_UP = 4

    /** Sturzstoß vom Titanen herab. */
    const val DIVE = 5

    /** Benommen am Boden (offen). */
    const val DAZED = 6

    const val ROAR = 7
    const val DEATH = 8

    /**
     * Konter gegen Spieler über ihr (Überarbeitung 2, PLAN 4.3): Sie reißt den Stachel senkrecht nach oben. Gespielt
     * zusammen mit der gemeinsamen Aktion `BossDefense.ANTI_AIR`; Zeiten `BossTimings.Guardian.ANTI_AIR_*`.
     */
    const val ANTI_AIR = 9
}

/**
 * Kampe, die Kerkermeisterin der Titanen: Frau, Schlangenleib und Skorpionschwanz (Plan 4.6, Brief `kampe.md`). Sie
 * kämpft am **Boden der Grube** (kein Kreisen mehr außer Reichweite):
 *
 * - **Schwanzfeger** gegen alle, die hinter oder neben ihr stehen: Kegel 120° halb nach hinten, Radius 8, darüber springen.
 * - **Giftfächer**: Kegel 30° halb, fünf Geschosse, Gift nur 2 s.
 * - **Skorpionstich**: Kreis am Ziel; danach **steckt der Stachel** (36 Ticks), Treffer zählen × 1,5.
 * - Bei 50 % (seit P24 noch einmal bei 25 %): Sie windet sich am angeketteten Titanen hoch und stößt **dreimal** herab (Linien). Wen sie von oben
 *   nicht sieht, den trifft sie nicht: **Der Titan gibt Deckung.** Nach dem dritten Stoß liegt sie benommen (offen).
 *
 * Überarbeitung 4 (P22, PLAN 4.2): **Die Kettenschlösser** ([KampeShackles]). Solange die Sturzstoß-Folge läuft, glüht
 * das heile Schloss am Grubenboden golden, das am weitesten von ihrem Platz am Titanen liegt. Drei Treffer sprengen es:
 * Der Titan zuckt, seine Kette fällt, sie stürzt herab und liegt [KampeSigTimings.SHACKLE_OPEN_F] offen ([shackleFall]).
 * Sonst bleibt alles wie oben. Höhenbänder für den Sims (+5): Schwanzfeger nur Grubenboden, Giftfächer, Stich und
 * Sturzstöße bis +8 ([BAND_REACH]); der Stich reckt sich auf den Sims-Rand. Ohne Kerker-Arena wie bisher.
 *
 * Wie bei den Bossen setzt der Server eine Pose je Angriff zu Beginn des Ausholens ([pose]); der Client merkt sich die
 * vorige Pose und den Zeitpunkt des Wechsels für weiche Übergänge.
 */
class KampeEntity(type: EntityType<out KampeEntity>, world: World) : TartarosGuardian(type, world) {

    override val kit = MiniBossKit(this, "entity.hha.kampe", BossBar.Color.GREEN, Vfx.Style.HADES)
    override val fightName = "kampe"
    override val danger = Vfx.Style.HADES
    override val link get() = TartarosChain.Link.KAMPE
    override val baseHealth get() = HEALTH

    /** Fußpunkt des angeketteten Titanen (Mitte des Grubenbodens); Standard: die Mitte ihres Platzes. */
    var titan: Vec3d? = null

    /** Nur auf dem Client: Pose vor dem letzten Wechsel und wann gewechselt wurde. */
    var previousPose = KampePose.IDLE
        private set
    var poseChangedAge = 0
        private set
    private var lastPoseSeen = KampePose.IDLE

    /** Wie viele Sturzstoß-Folgen dieser Kampf schon hatte (P24: bei 50 % und bei [KampeSigTimings.SECOND_COIL_AT]). */
    private var coils = 0

    /** P22: die Kettenschlösser dieses Kampfs (nur im Kerker mit Arena), sonst `null`. */
    private var shackles: KampeShackles.Shackles? = null

    /** In diesem Kampf gesprengte Schlösser (bleiben über einen Neustart an der Leine gesprengt; die Ketten fehlen ja). */
    private val brokenLocks = HashSet<Int>()

    /** Die laufende Sturzstoß-Ansage; ein Schlossbruch bricht sie sauber ab ([Telegraph.Fight.Group.cancel]). */
    private var diveGroup: Telegraph.Fight.Group? = null

    init {
        kit.stats(HEALTH, 10.0, 14.0, 0.3)
    }

    override fun initDataTracker(builder: DataTracker.Builder) {
        super.initDataTracker(builder)
        builder.add(POSE_ID, KampePose.IDLE)
        builder.add(RESTART_ID, 0)
    }

    var pose: Int
        get() = dataTracker.get(POSE_ID)
        set(value) = dataTracker.set(POSE_ID, value)

    /** Setzt die Pose und startet sie auch neu, wenn sie schon läuft (drei Sturzstöße hintereinander). */
    private fun play(value: Int) {
        if (pose == value) dataTracker.set(RESTART_ID, dataTracker.get(RESTART_ID) + 1) else pose = value
    }

    override fun onTrackedDataSet(data: TrackedData<*>) {
        super.onTrackedDataSet(data)
        if (!entityWorld.isClient) return
        if (data == POSE_ID) {
            previousPose = lastPoseSeen
            lastPoseSeen = pose
            poseChangedAge = age
        } else if (data == RESTART_ID) {
            previousPose = pose
            poseChangedAge = age
        }
    }

    override fun guardianMoves() = listOf(
        Move(KampePose.TAIL_SWEEP, 0.0..BossTimings.Kampe.SWEEP_RADIUS.toDouble(), 3, { flanker() != null }) { sweep() },
        Move(KampePose.SPIT, 5.0..BossTimings.Kampe.SPIT_RANGE.toDouble(), 2) { spit(it) },
        Move(KampePose.STING, 2.0..7.0, 3) { sting(it) },
    )

    override fun closeRange(): Double = 3.2

    override fun entrance(): Sequence<Unit> = sequence {
        play(KampePose.ROAR)
        telegraph.sound(HhaSounds.HISS, entityPos, 2.6f, 0.7f)
        wait(BossTimings.Kampe.ROAR)
        play(KampePose.IDLE)
    }

    // ---------------------------------------------------------------------------------------------
    // Überarbeitung 3 (P15): Auftritt als Bühnen-Szene `kampe.intro`
    // ---------------------------------------------------------------------------------------------

    /**
     * Auftritt (PLAN 2.12, Skript `kampe.intro` über `TartarosGuardian.introScript`): Sie gleitet am angeketteten Titanen
     * herab, die Kamera folgt von oben nach unten, dann brüllt sie und die Titelkarte kommt (8 s). Vor dem Start dreht
     * sie sich vom Titanen weg ([beforeIntro]), damit „vorn" im Skript vom Titanen fortzeigt und die Kamerapunkte
     * seitlich vor ihr liegen (nie im Titanen). Ohne Titanen (z. B. `/summon`) bleibt sie stehen und brüllt nur. Fester
     * Endzustand ([introFinish]): an ihrem Platz, am Boden, Ruhepose. Thema und Erzählerzeile kommen aus der Basis.
     */
    private var introHome: Vec3d? = null

    override fun beforeIntro() {
        val home = entityPos
        introHome = home
        val base = titan
        if (base != null && flat(home, base) > 1.0) face(flatNorm(home.subtract(base)))
    }

    override fun introInner(): Sequence<Unit> = introScene(introHome ?: entityPos, titan)

    override fun introFinish() {
        endIntro(introHome ?: entityPos)
        hold(null)
    }

    /** Das Drehbuch zur Szene; die Zeiten stehen in [KampeStages] (dieselben Zahlen wie die Kamera). */
    private fun introScene(home: Vec3d, base: Vec3d?): Sequence<Unit> = sequence {
        val perch = base?.let { b -> PERCHES.map { b.add(it) }.minByOrNull { it.squaredDistanceTo(home) } }
        if (perch != null) {
            scripted(true)
            place(perch)
        }
        play(KampePose.IDLE)
        for (t in 0 until KampeStages.INTRO_DESCEND) {
            if (t == KampeStages.INTRO_HISS) telegraph.sound(HhaSounds.HISS, entityPos, 2.2f, 0.6f)
            yield(Unit)
        }
        val descent = KampeStages.INTRO_LAND - KampeStages.INTRO_DESCEND
        if (perch != null) {
            play(KampePose.COIL_UP)
            yieldAll(SceneKit.glide(self, home, descent))
            scripted(false)
            place(home)
            // Aufsetzen: Risse und echte Blöcke unter ihr (der Ruck steht im Skript).
            BossFx.debris(sw, ground(home), INTRO_DEBRIS_RADIUS, 6)
            BossFx.cracks(sw, danger, ground(home), INTRO_CRACK_RADIUS, 4)
        } else {
            wait(descent)
        }
        // Wie der Auftritt des Bestands: Brüllen und Zischen.
        play(KampePose.ROAR)
        telegraph.sound(HhaSounds.HISS, entityPos, 2.6f, 0.7f)
        wait(BossTimings.Kampe.ROAR)
        play(KampePose.IDLE)
    }

    /** Fester Endzustand des Auftritts, auch beim Überspringen. */
    private fun endIntro(home: Vec3d) {
        scripted(false)
        if (entityPos.squaredDistanceTo(home) > 0.25) place(home)
        play(KampePose.IDLE)
    }

    /** Sofort in Richtung [dir] schauen (Körper und Kopf) und dort halten. */
    private fun face(dir: Vec3d) {
        val y = Math.toDegrees(atan2(-dir.x, dir.z)).toFloat()
        yaw = y
        bodyYaw = y
        headYaw = y
        hold(dir)
    }

    override fun phaseEvent(): Sequence<Unit>? {
        // P24 (Freigabe A1): eine zweite Folge bei 25 %, damit je Kampf zwei Schlösser gesprengt werden können. Das
        // zweite Mal glüht wieder das heile Schloss, das am weitesten von ihrem Sitz liegt ([KampeShackles.Shackles.openFarthest]).
        val at = when (coils) {
            0 -> COIL_AT
            1 -> KampeSigTimings.SECOND_COIL_AT
            else -> return null
        }
        if (health < maxHealth * at) {
            coils++
            return titanDives()
        }
        return null
    }

    override fun onReset() {
        super.onReset()
        scripted(false)
        play(KampePose.IDLE)
        // P22: Ausgang „Leine“: Schlösser und Leisten weg (die Basis hat die Ansagen schon vergessen). Der nächste Tick
        // legt die heilen Schlösser neu an; gesprengte bleiben gesprengt ([brokenLocks]), ihre Ketten fehlen weiter.
        endShackles()
    }

    // ---------------------------------------------------------------------------------------------
    // Überarbeitung 4 (P22): Kettenschlösser
    // ---------------------------------------------------------------------------------------------

    /**
     * Jeder Kampf-Tick vor dem Drehbuch: Schlösser anlegen (Kampfbeginn, sobald die Kerker-Arena offen ist), halten und
     * einen Bruch abholen. Der Bruch kommt aus dem Trefferweg eines Spielers; gehandelt wird erst hier, im eigenen Tick.
     */
    override fun tickBody() {
        super.tickBody()
        val s = shackles ?: openShackles() ?: return
        s.tick()
        val broken = s.takeBroken() ?: return
        interrupt(shackleFall(broken))
    }

    /** Legt die heilen Schlösser an, wenn sie im Kerker-Vertrag stehen; sonst bleibt alles wie vor der Überarbeitung. */
    private fun openShackles(): KampeShackles.Shackles? {
        if (brokenLocks.size >= TartarusArenas.PRISON_LOCK_ANGLES.size) return null
        val arena = arenaOrNull() ?: return null
        return KampeShackles.create(sw, arena, { fighters() }, brokenLocks, BAR_THEME_BASE + danger.ordinal)?.also { shackles = it }
    }

    /** Alle Ausgänge (Leine, Tod, Verschwinden): Gruppe schließen, laufende Sturz-Ansage vergessen. */
    private fun endShackles() {
        shackles?.close()
        shackles = null
        diveGroup = null
    }

    /** Der Kerker-Vertrag dieses Kampfs, falls sie in einer Kerker-Arena kämpft. */
    private fun prisonSpec(): ArenaSpec? = arenaOrNull()?.spec?.takeIf { it.id == TartarusArenas.PRISON_ID }

    /** Der Sims, wenn [player] gerade darauf steht (Füße höchstens 1 unter seinem Boden). */
    private fun ledgeOf(player: ServerPlayerEntity): Level? {
        val level = arenaOrNull()?.levelAt(player.entityPos) ?: return null
        return level.takeIf { it.id == LEDGE_ID && player.y >= it.floorY - 1.0 }
    }

    /** Grubenboden (Füße) des Vertrags. */
    private fun coreFloor(spec: ArenaSpec): Double = spec.coreY.toDouble()

    /**
     * Zweite Form einer Ansage auf Sims-Höhe (der Client zeichnet flach): nur, wenn der Kreis um [center] mit [radius]
     * den Sims waagerecht berührt. Gehört zur selben Gruppe, trifft also mit demselben Band.
     */
    private fun ringOnLedge(group: Telegraph.Fight.Group, spec: ArenaSpec, center: Vec3d, radius: Float, ticks: Int) {
        val ledge = spec.levels.firstOrNull { it.id == LEDGE_ID } ?: return
        val touches = ledge.zone.contains(center.x, center.z) || (0 until LEDGE_PROBES).any { j ->
            val a = 2.0 * Math.PI * j / LEDGE_PROBES
            ledge.zone.contains(center.x + cos(a) * radius, center.z + sin(a) * radius)
        }
        if (touches) group.ring(Vec3d(center.x, ledge.floorY + 0.05, center.z), radius, ticks)
    }

    /** Berührt der Kegel ([apex], [dir], [radius], halber Winkel [halfAngle] in Grad) den Sims waagerecht? Abgetastet je Block. */
    private fun coneTouches(ledge: Level, apex: Vec3d, dir: Vec3d, radius: Double, halfAngle: Double): Boolean {
        for (j in 0..LEDGE_PROBES) {
            val ray = rotate(dir, (j.toDouble() / LEDGE_PROBES - 0.5) * 2.0 * halfAngle)
            var d = 1.0
            while (d <= radius) {
                if (ledge.zone.contains(apex.x + ray.x * d, apex.z + ray.z * d)) return true
                d += 1.0
            }
        }
        return false
    }

    /** Zeile einer Sprecherin ohne eigenen Sprecher-Platz: Stimmlage der Schatten, Name „Kampe“. */
    private fun say(key: String) {
        Cine.dialog(fighters(), Cine.SPEAKER_SHADE, key, KampeSigTimings.LINE_TICKS, kit.nameKey)
    }

    /** Brustmitte des Titanen: Mitte zwischen den beiden Schulter-Ketten (Vertrag), Fallback über dem Fußpunkt. */
    private fun titanChest(base: Vec3d): Vec3d {
        val s = shackles ?: return base.add(0.0, TITAN_CHEST_FALLBACK, 0.0)
        val a = s.chainTarget(SHOULDER_LOCKS.first)
        val b = s.chainTarget(SHOULDER_LOCKS.second)
        return a.add(b).multiply(0.5)
    }

    /**
     * Ein Schloss ist gesprengt (PLAN 4.2): Der Titan zuckt, die Kette fällt, Kampe stürzt. Die laufende Folge (Sturzstöße)
     * ist mit [interrupt] beendet; ihre Ansage wird hier sauber abgebrochen. Aufschlag: Kreis r 3 unter ihr,
     * [KampeSigTimings.SHACKLE_FALL_W] Ticks, kein Schaden (es gab keinen Sturz-Schaden), nur Rückstoß ≤ 1. Danach
     * [KampeSigTimings.SHACKLE_OPEN_F] offen. Steht sie schon am Boden (Bruch im Gleiten der letzten Linie), wird sie an
     * Ort und Stelle umgeworfen; Ansage und Fenster gleich.
     */
    private fun shackleFall(index: Int): Sequence<Unit> = sequence {
        val t = KampeSigTimings
        val base = titan ?: anchor()
        diveGroup?.cancel()
        diveGroup = null
        shackles?.shut()
        // Der Titan zuckt: Ruck durch den Leib, Staub von den Schultern, kurzer Kamera-Ruck.
        val limb = shackles?.chainTarget(index) ?: base.add(0.0, TITAN_CHEST_FALLBACK, 0.0)
        BossFx.play(sw, KampeShackles.FX_JOLT, danger, limb, dir = titanChest(base), a = TITAN_SHOULDER_HALF, life = t.JOLT_LIFE)
        BossFx.kick(sw, base.add(0.0, TITAN_CHEST_FALLBACK * 0.5, 0.0), t.JOLT_KICK_PITCH, roll = t.JOLT_KICK_ROLL, ticks = t.JOLT_KICK_TICKS, radius = t.JOLT_KICK_RADIUS)
        shackles?.dropChains(index)
        say(KampeShackles.LINE_KEY)
        // Sie verliert den Halt.
        val hanging = entityPos.y > base.y + t.SHACKLE_HANG_HEIGHT
        val land = if (hanging) landing(base, Vec3d(x, base.y, z), flatNorm(entityPos.subtract(base)), 0.0) else freeSpot(base, Vec3d(x, base.y, z))
        scripted(hanging)
        hold(null)
        play(KampePose.ROAR)
        telegraph.sound(HhaSounds.HISS, entityPos, 2.6f, 1.25f)
        val group = telegraph.boss()
        prisonSpec()?.let { group.band(coreFloor(it)) }
        val ring = group.ring(Vec3d(land.x, base.y + 0.05, land.z), t.SHACKLE_FALL_RADIUS, t.SHACKLE_FALL_W)
        val drop = if (hanging) min(t.SHACKLE_FALL_DROP, ring.ticks) else 0
        wait(ring.ticks - drop)
        val from = entityPos
        if (drop > 0) play(KampePose.DIVE)
        for (i in 1..drop) {
            val e = i.toDouble() / drop
            // Waagerecht weich, senkrecht beschleunigt (sie fällt).
            place(Vec3d(from.x + (land.x - from.x) * e, from.y + (land.y - from.y) * e * e, from.z + (land.z - from.z) * e))
            yield(Unit)
        }
        scripted(false)
        place(land)
        // Aufschlag: kein Schaden, nur Rückstoß vom Aufschlagpunkt weg.
        for (player in ring.hits(fighters())) knock(player, player.entityPos.subtract(land), t.SHACKLE_FALL_KNOCK, t.SHACKLE_FALL_KNOCK_UP)
        Vfx.shockwave(sw, danger, ground(land), t.SHACKLE_FALL_RADIUS, 12, 1.6f)
        BossFx.debris(sw, ground(land), DIVE_DEBRIS_RADIUS, 6)
        BossFx.cracks(sw, danger, ground(land), DIVE_CRACK_RADIUS, 5)
        BossFx.kick(sw, land, DIVE_KICK_PITCH, ticks = KICK_TICKS, radius = KICK_RADIUS)
        telegraph.sound(HhaSounds.IMPACT, land, 2.6f, 0.6f)
        // Offen (PLAN 4.3: 80 statt 60): benommen am Aufschlagpunkt, goldener Sternenring und Rune an ihr.
        play(KampePose.DAZED)
        BossFx.play(sw, "kampe.daze", Vfx.Style.GOLD, entityPos.add(0.0, height * 0.6, 0.0), life = t.SHACKLE_OPEN_F, entity = self)
        open(t.SHACKLE_OPEN_F, OPEN_FACTOR)
        wait(t.SHACKLE_OPEN_F)
        close()
        play(KampePose.IDLE)
    }

    /**
     * Freier Stand für sie am Grubenboden nahe [p]: Platz für ihren Körper, innerhalb des Grubenbodens ([clampPit]), nicht
     * an einem Schloss. Sucht in Ringen um [p]; findet sich nichts, bleibt es bei [p] (wie bisher).
     */
    private fun freeSpot(base: Vec3d, p: Vec3d): Vec3d {
        val start = clampPit(base, p)
        if (standsFree(base, start)) return start
        for (ring in 1..KampeSigTimings.FREE_SEARCH_RINGS) {
            for (j in 0 until KampeSigTimings.FREE_SEARCH_STEPS) {
                val a = 2.0 * Math.PI * j / KampeSigTimings.FREE_SEARCH_STEPS
                val q = clampPit(base, start.add(cos(a) * ring, 0.0, sin(a) * ring))
                if (standsFree(base, q)) return q
            }
        }
        return start
    }

    /** Ihr Körper passt bei [p] auf den Grubenboden und steht nicht an einem Schloss. */
    private fun standsFree(base: Vec3d, p: Vec3d): Boolean {
        val box = boundingBox.offset(-x, -y, -z).contract(0.2)
        if (!entityWorld.isSpaceEmpty(self, box.offset(p.x, base.y + 0.1, p.z))) return false
        return shackles?.nearLock(p, KampeSigTimings.LOCK_CLEARANCE) != true
    }

    /** Der Konter der Basis, dazu ihre eigene Pose: der Stachel senkrecht nach oben. */
    override fun counter(): Sequence<Unit> {
        val base = super.counter()
        return sequence {
            play(KampePose.ANTI_AIR)
            yieldAll(base)
            play(KampePose.IDLE)
        }
    }

    /** Wer in Reichweite des Schwanzes hinter oder neben ihr steht (außerhalb der vorderen ±60°). */
    private fun flanker(): ServerPlayerEntity? {
        val f = facing()
        val reach = BossTimings.Kampe.SWEEP_RADIUS
        return fighters().firstOrNull { p ->
            val to = flatNorm(p.entityPos.subtract(entityPos))
            flatDistance(p) <= reach && to.x * f.x + to.z * f.z < FRONT_COS
        }
    }

    /** Schwanzfeger: Kegel nach hinten und zur Seite (vorn ±60° frei), Radius 8; darüber springen. */
    private fun sweep(): Sequence<Unit> = sequence {
        val k = BossTimings.Kampe
        val dir = facing()
        hold(dir)
        play(KampePose.TAIL_SWEEP)
        val back = dir.multiply(-1.0)
        // P22: Bodenangriff, Band um den Grubenboden: trifft den Sims (+5) nicht (Sims schützt vor dem Feger, PLAN 3.7).
        val group = telegraph.boss()
        prisonSpec()?.let { group.band(coreFloor(it)) }
        val cone = group.cone(ground(entityPos), back, k.SWEEP_RADIUS, k.SWEEP_HALF_ANGLE, k.SWEEP_WINDUP, Telegraph.Pace.HEAVY)
        telegraph.sound(HhaSounds.HISS, entityPos, 2.2f, 0.7f)
        wait(cone.ticks)
        // Schwanzfeger flach über den Boden: zum Überspringen (LOW).
        val struck = cone.hits(fighters()).filter { !airborne(it) && hit(it, 14f, BossDefense.Reach.LOW) }
        for (player in struck) knock(player, dirTo(player.entityPos), 1.0, 0.5)
        // Überarbeitung 3: Staubband hinter der Schwanzspitze statt der Sichel (der eine Flächeneffekt des Treffers).
        BossFx.play(sw, "kampe.sweep", danger, ground(entityPos), back, a = k.SWEEP_RADIUS, b = k.SWEEP_HALF_ANGLE, life = SWEEP_FX_LIFE, impact = true)
        telegraph.sound(HhaSounds.WHOOSH, entityPos, 2f, 0.6f)
        // Seitlicher Ruck in Schlagrichtung statt Rütteln (nur wenn jemand getroffen ist, wie bisher).
        if (struck.isNotEmpty()) BossFx.kick(sw, entityPos, SWEEP_KICK_PITCH, SWEEP_KICK_YAW, ticks = KICK_TICKS, radius = KICK_RADIUS)
        wait(k.SWEEP_END - k.SWEEP_WINDUP)
        hold(null)
        play(KampePose.IDLE)
    }

    /** Giftfächer: Kegel 30° halb, fünf Geschosse, ohne Zielsuche; das Gift wirkt nur 2 s (`SPIT_POISON`, im Geschoss). */
    private fun spit(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val k = BossTimings.Kampe
        val dir = dirTo(target.entityPos)
        // Die Richtung wird beim Ansagen festgelegt: Wer aus dem Kegel läuft, ist sicher (kein Nachzielen).
        val aimPoint = target.eyePos.subtract(0.0, 0.3, 0.0)
        hold(dir)
        play(KampePose.SPIT)
        // P22: Die Geschosse zielen auf die Augen und erreichen auch den Sims; die Ansage zeigt das mit Band bis +8 und,
        // wenn der Kegel den Sims berührt, einer zweiten Form auf Sims-Höhe.
        val group = telegraph.boss()
        val spec = prisonSpec()
        val apex = ground(entityPos)
        spec?.let { group.band(coreFloor(it), above = BAND_REACH) }
        val cone = group.cone(apex, dir, k.SPIT_RANGE, k.SPIT_HALF_ANGLE, k.SPIT_WINDUP, Telegraph.Pace.PROJECTILE)
        spec?.levels?.firstOrNull { it.id == LEDGE_ID }?.let { ledge ->
            if (coneTouches(ledge, apex, dir, k.SPIT_RANGE.toDouble(), k.SPIT_HALF_ANGLE.toDouble())) {
                group.cone(Vec3d(apex.x, ledge.floorY + 0.05, apex.z), dir, k.SPIT_RANGE, k.SPIT_HALF_ANGLE, k.SPIT_WINDUP, Telegraph.Pace.PROJECTILE)
            }
        }
        telegraph.sound(HhaSounds.HISS, entityPos, 2.4f, 1.1f)
        // Gift sammelt sich im Maul (am Kopf, nicht auf der Ansage).
        BossFx.play(sw, "kampe.venom", danger, entityPos.add(0.0, height * 0.82, 0.0).add(dir), life = k.SPIT_WINDUP, entity = self)
        wait(cone.ticks)
        val mouth = entityPos.add(0.0, height * 0.82, 0.0).add(dir.multiply(1.0))
        val flat = kotlin.math.sqrt(dir.x * dir.x + dir.z * dir.z).coerceAtLeast(1e-6)
        val horizontal = aimPoint.subtract(mouth).let { kotlin.math.sqrt(it.x * it.x + it.z * it.z) }.coerceAtLeast(1.0)
        val drop = ((aimPoint.y - mouth.y) / horizontal).coerceIn(-0.6, 0.6)
        val aim = Vec3d(dir.x / flat, drop, dir.z / flat).normalize()
        // Die Geschosse treffen nur, so weit der Kegel angesagt war: Die Mündung liegt 1 Block vor der Kegelspitze.
        val range = k.SPIT_RANGE - 1.0
        val life = kotlin.math.ceil(range / SPIT_SPEED).toInt() + 1
        val spread = k.SPIT_HALF_ANGLE * 0.8 / ((k.SPIT_SHOTS - 1) / 2.0)
        for (i in 0 until k.SPIT_SHOTS) {
            val turn = Math.toRadians((i - (k.SPIT_SHOTS - 1) / 2.0) * spread)
            val v = Vec3d(aim.x * kotlin.math.cos(turn) - aim.z * kotlin.math.sin(turn), aim.y, aim.x * kotlin.math.sin(turn) + aim.z * kotlin.math.cos(turn))
            val bolt = HhaEntities.HERMES_BOLT.create(sw, SpawnReason.EVENT) ?: continue
            bolt.kind = HermesBoltEntity.VENOM
            bolt.size = 0.9f
            bolt.damage = 6f * damageScale * power()
            bolt.fight = telegraph
            bolt.setOwner(self)
            bolt.setPosition(mouth)
            bolt.velocity = v.normalize().multiply(SPIT_SPEED)
            bolt.maxAge = life
            bolt.maxRange = range
            sw.spawnEntity(bolt)
        }
        telegraph.sound(SoundEvents.ENTITY_LLAMA_SPIT, mouth, 2f, 0.5f)
        // Der Fächer kommt aus dem Maul des Modells; Tropfen bilden Lachen (nur Optik, kein Schaden).
        BossFx.play(sw, "kampe.spit", danger, mouth, aim, a = range.toFloat(), b = k.SPIT_HALF_ANGLE * 0.8f, c = k.SPIT_SHOTS.toFloat(), life = SPIT_FX_LIFE, entity = self)
        BossFx.drip(sw, danger, mouth.add(dir.multiply(SPIT_DRIP_AHEAD)), SPIT_DRIP_RADIUS, SPIT_DRIPS, SPIT_DRIP_LIFE, BossFx.Look.VENOM)
        wait(k.SPIT_END - k.SPIT_WINDUP)
        hold(null)
        play(KampePose.IDLE)
    }

    /** Skorpionstich: Kreis am Ziel; der Stachel steckt danach (F 36, × 1,5). */
    private fun sting(target: ServerPlayerEntity): Sequence<Unit> = sequence {
        val k = BossTimings.Kampe
        val dir = dirTo(target.entityPos)
        hold(dir)
        play(KampePose.STING)
        val spec = prisonSpec()
        val ledge = if (spec != null) ledgeOf(target) else null
        val group = telegraph.boss()
        spec?.let { group.band(coreFloor(it), above = BAND_REACH) }
        val spot: Vec3d
        if (ledge != null && spec != null) {
            // P22: Ziel auf dem Sims: Sie reckt sich, der Stich geht auf den Sims-Rand vor dem Ziel (Kreis auf Sims-Höhe),
            // dazu dieselbe Form auf dem Grubenboden darunter (Fuß der Simswand).
            val c = spec.centerVec
            val a = atan2(target.z - c.z, target.x - c.x)
            val rim = Vec3d(c.x + cos(a) * LEDGE_RIM, 0.0, c.z + sin(a) * LEDGE_RIM)
            spot = Vec3d(rim.x, ledge.floorY + 0.05, rim.z)
            hold(dirTo(rim))
            group.ring(Vec3d(rim.x, coreFloor(spec) + 0.05, rim.z), k.STING_RADIUS, k.STING_WINDUP, pace = Telegraph.Pace.HEAVY)
            group.ring(spot, k.STING_RADIUS, k.STING_WINDUP, pace = Telegraph.Pace.HEAVY)
        } else {
            val reach = flatDistance(target).coerceIn(2.5, k.STING_REACH.toDouble())
            spot = ground(entityPos.add(dir.multiply(reach)))
            group.ring(spot, k.STING_RADIUS, k.STING_WINDUP, pace = Telegraph.Pace.HEAVY)
            // Reicht der Kreis an den Sims, zeigt ihn eine zweite Form dort (gleiches Band, trifft beide Höhen).
            spec?.let { ringOnLedge(group, it, spot, k.STING_RADIUS, k.STING_WINDUP) }
        }
        telegraph.sound(HhaSounds.STING, entityPos, 2.2f, 0.8f)
        wait(group.ticks)
        // Höhenregel je Spieler von seiner Fläche aus (Grubenboden oder Sims); ohne Arena wie bisher vom Einstich.
        val struck = group.hits(fighters()).filter { hit(it, 18f, BossDefense.Reach.BODY, if (spec != null) floorOf(it.entityPos) else spot.y) }
        for (player in struck) knock(player, dir, 0.6, 0.45)
        Vfx.shockwave(sw, danger, spot, k.STING_RADIUS + 0.5f, 12, 1.4f)
        BossFx.cracks(sw, danger, spot, k.STING_RADIUS + 0.3f, STING_CRACKS)
        telegraph.sound(HhaSounds.IMPACT, spot, 2f, 0.9f)
        if (struck.isNotEmpty()) BossFx.kick(sw, spot, STING_KICK_PITCH, ticks = KICK_TICKS, radius = KICK_RADIUS)
        // Der Stachel steckt im Boden: Sie zerrt, Treffer zählen mehr (der Stachel glüht golden, BossGlow).
        hint("hha.guardian.hint.sting")
        open(k.STING_STUCK_END - k.STING_WINDUP, OPEN_FACTOR, at = spot)
        wait(k.STING_STUCK_END - k.STING_WINDUP)
        close()
        wait(k.STING_END - k.STING_STUCK_END)
        hold(null)
        play(KampePose.IDLE)
    }

    // ---------------------------------------------------------------------------------------------
    // Bei 50 %: am Titanen hoch, drei Sturzstöße
    // ---------------------------------------------------------------------------------------------

    /** Geführte Bewegung am Titanen: durch seine Blöcke hindurch, ohne Schwerkraft. */
    private fun scripted(on: Boolean) {
        noClip = on
        setNoGravity(on)
        if (on) stopMoving()
    }

    private fun place(at: Vec3d) {
        setPosition(at.x, at.y, at.z)
        velocity = Vec3d.ZERO
    }

    private fun titanDives(): Sequence<Unit> = sequence {
        val k = BossTimings.Kampe
        val base = titan ?: anchor()
        hold(null)
        play(KampePose.ROAR)
        hint("hha.guardian.hint.titan")
        telegraph.sound(HhaSounds.ROAR, entityPos, 3f, 0.8f)
        telegraph.roomShake(entityPos, 5f)
        wait(k.ROAR)
        var seat = random.nextInt(PERCHES.size)
        val spec = prisonSpec()
        for (n in 0 until k.DIVE_COUNT) {
            // Hinauf an den Titanen.
            var perch = perchAt(base, seat)
            play(KampePose.COIL_UP)
            scripted(true)
            val from = entityPos
            // Hochwinden: ein Wirbel, wo sie sich vom Boden löst.
            BossFx.swirl(sw, danger, ground(from), UP, COIL_SWIRL_RADIUS, k.COIL)
            // P22: Deckung markieren, solange sie sich hochwindet (goldene Rune am Titanen für jeden, den er gerade deckt).
            spec?.let { Cover.mark(telegraph, it, TartarusArenas.KAMPE_DIVE, perchEye(perch), k.COIL) }
            for (i in 1..k.COIL) {
                val t = i.toDouble() / k.COIL
                val e = t * t * (3.0 - 2.0 * t)
                place(Vec3d(from.x + (perch.x - from.x) * e, from.y + (perch.y - from.y) * t, from.z + (perch.z - from.z) * e))
                hold(flatNorm(perch.subtract(from)))
                if (spec != null) coverStatus(perch)
                yield(Unit)
            }
            // P22: Sie hängt am Titanen. Jetzt glüht das heile Schloss, das am weitesten von ihrem Sitz liegt, und bleibt
            // golden, bis die Folge endet (Bruch → [shackleFall], sonst unten [KampeShackles.Shackles.shut]).
            if (n == 0 && shackles?.openFarthest(perch) != null) hint(KampeShackles.HINT_KEY)
            // Wen sieht sie von oben? Wer hinter dem Titanen steht, ist gedeckt.
            var target = visibleFrom(perch)
            var tries = 0
            while (target == null && tries < 2 && isAlive) {
                tries++
                telegraph.sound(HhaSounds.HISS, perch, 2.4f, 0.6f)
                spec?.let { Cover.mark(telegraph, it, TartarusArenas.KAMPE_DIVE, perchEye(perch), k.DIVE_SEARCH) }
                repeat(k.DIVE_SEARCH) {
                    if (spec != null) coverStatus(perch)
                    yield(Unit)
                }
                seat = (seat + 1) % PERCHES.size
                val next = perchAt(base, seat)
                for (i in 1..12) {
                    place(perch.lerp(next, i / 12.0))
                    yield(Unit)
                }
                perch = next
                target = visibleFrom(perch)
            }
            val foot = Vec3d(perch.x, base.y, perch.z)
            val aim = target?.entityPos ?: foot.add(flatNorm(foot.subtract(base)).multiply(14.0))
            val dir = flatNorm(aim.subtract(foot))
            val g0 = landing(base, foot, dir, max(6.0, flat(foot, aim) - 6.0))
            val g1 = clampPit(base, g0.add(dir.multiply(k.DIVE_LENGTH.toDouble())))
            play(KampePose.DIVE)
            hold(dir)
            // P22: Stoß von oben, Band bis +8 (trifft Grubenboden und Sims); eigene Gruppe, damit ein Schlossbruch sie abbricht.
            val group = telegraph.boss()
            spec?.let { group.band(coreFloor(it), above = BAND_REACH) }
            diveGroup = group
            val line = group.line(ground(g0), ground(g1), k.DIVE_WIDTH, k.DIVE_WINDUP, Telegraph.Pace.HEAVY)
            telegraph.sound(HhaSounds.CHARGE, perch, 2.4f, 0.6f)
            wait(line.ticks)
            // Sturz: vom Titanen an den Anfang der Linie, dann die Linie entlang.
            for (i in 1..k.DIVE_DROP) {
                place(perch.lerp(g0, i.toDouble() / k.DIVE_DROP))
                yield(Unit)
            }
            Vfx.shockwave(sw, danger, ground(g0), 3f, 12, 1.6f)
            // Einschlag: echte Blöcke, Risse und ein Ruck (ersetzt das Rütteln am Ende der Linie).
            BossFx.debris(sw, ground(g0), DIVE_DEBRIS_RADIUS, 6)
            BossFx.cracks(sw, danger, ground(g0), DIVE_CRACK_RADIUS, 5)
            BossFx.kick(sw, g0, DIVE_KICK_PITCH, ticks = KICK_TICKS, radius = KICK_RADIUS)
            telegraph.sound(HhaSounds.IMPACT, g0, 2.6f, 0.7f)
            val struck = HashSet<UUID>()
            for (i in 0..k.DIVE_SLIDE) {
                val p = g0.lerp(g1, i.toDouble() / k.DIVE_SLIDE)
                place(p)
                val travelled = flat(g0, p)
                for (player in fighters()) {
                    // Der Stoß kommt von oben (PLAN 4.3: SKY): Wer über ihr in der Luft hängt, wird mitgerissen; nur wer
                    // tief unter ihr ist (Grubenrand, Treppe), bleibt verschont.
                    if (player.uuid in struck || player.y < p.y - 3.0) continue
                    if (abs(along(player.entityPos, g0, dir) - travelled) > 2.4) continue
                    if (!line.contains(player)) continue
                    struck.add(player.uuid)
                    if (!hit(player, 16f, BossDefense.Reach.SKY, base.y)) continue
                    knock(player, dir, 1.0, 0.5)
                }
                yield(Unit)
            }
            diveGroup = null
        }
        // P22: Die Folge ist vorbei, das Schloss wird wieder gedämpft (kein Bruch).
        shackles?.shut()
        // Nicht in einer Kette oder an einem Schloss liegen bleiben (Ende der letzten Linie).
        if (spec != null) place(freeSpot(base, Vec3d(x, base.y, z)))
        // Nach dem dritten Stoß liegt sie benommen am Boden (F 60, offen).
        scripted(false)
        play(KampePose.DAZED)
        // Kleiner goldener Sternenring über dem Kopf: benommen = offen.
        BossFx.play(sw, "kampe.daze", Vfx.Style.GOLD, entityPos.add(0.0, height * 0.6, 0.0), life = k.DAZED, entity = self)
        open(k.DAZED, OPEN_FACTOR)
        wait(k.DAZED)
        close()
        play(KampePose.IDLE)
    }

    private fun perchAt(base: Vec3d, index: Int): Vec3d {
        val p = PERCHES[index % PERCHES.size]
        return base.add(p.x, p.y, p.z)
    }

    /** Ihre Augen am Sitz [perch] (Quelle der Sicht- und Deckungsprüfung). */
    private fun perchEye(perch: Vec3d): Vec3d = perch.add(0.0, 2.0, 0.0)

    /**
     * Der nächste Spieler, den sie von [perch] aus sieht. Im Kerker mit Vertrag (P22) über [Cover.sheltered]: verdeckt
     * heißt Füße und Augen hinter Blöcken (derselbe Raycast wie bisher, nun auch zu den Füßen). Ohne Vertrag wie bisher
     * (nur Augen).
     */
    private fun visibleFrom(perch: Vec3d): ServerPlayerEntity? {
        val eye = perchEye(perch)
        val withCover = prisonSpec() != null
        return fighters().filter { p ->
            if (withCover) !Cover.sheltered(sw, eye, p)
            else sw.raycast(RaycastContext(eye, p.eyePos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, self)).type == HitResult.Type.MISS
        }.minByOrNull { it.squaredDistanceTo(perch) }
    }

    /** Statuszeile „gedeckt“/„gesehen“ für jeden Mitkämpfer, von [perch] aus (nur bei Wechsel, gedrosselt in [Cover.status]). */
    private fun coverStatus(perch: Vec3d) {
        val eye = perchEye(perch)
        for (p in fighters()) Cover.status(p, Cover.sheltered(sw, eye, p))
    }

    /**
     * Landepunkt am Boden, außerhalb des Titanen: von [foot] aus [distance] in [dir], weiter hinaus, bis Platz ist. P22: und
     * nicht an einem Schloss (Wesen und Sockel); findet sich so nichts, sucht [freeSpot] in der Nähe.
     */
    private fun landing(base: Vec3d, foot: Vec3d, dir: Vec3d, distance: Double): Vec3d {
        var d = distance
        var p = clampPit(base, foot.add(dir.multiply(d)))
        repeat(12) {
            if (standsFree(base, p)) return p
            d += 1.0
            p = clampPit(base, foot.add(dir.multiply(d)))
        }
        return if (shackles != null) freeSpot(base, p) else p
    }

    /**
     * Hält [p] auf dem Grubenboden. Mit Kerker-Vertrag (P22) innerhalb seines Kerns (`TartarusArenas.PRISON_CORE`, die
     * Wendelrampe beginnt dahinter) abzüglich ihrer halben Breite; ohne Vertrag Radius [PIT] wie bisher.
     */
    private fun clampPit(base: Vec3d, p: Vec3d): Vec3d {
        val limit = if (prisonSpec() != null) TartarusArenas.PRISON_CORE - width * 0.5 - KampeSigTimings.CORE_EDGE_MARGIN else PIT
        val d = flat(p, base)
        val q = if (d <= limit) p else Vec3d(base.x + (p.x - base.x) * limit / d, p.y, base.z + (p.z - base.z) * limit / d)
        return Vec3d(q.x, base.y, q.z)
    }

    // ---------------------------------------------------------------------------------------------

    override fun onDeath(damageSource: DamageSource) {
        if (!entityWorld.isClient) {
            scripted(false)
            pose = KampePose.DEATH
            // P22: Ausgang „Sieg“: Schlösser und Leisten weg; die Ketten stellt `Arenas.close` beim Schließen der Arena her.
            endShackles()
        }
        super.onDeath(damageSource)
    }

    /** P22: Ausgang „verschwindet“ (Leine zu lang allein, `boss stop`, Entladen, Serverstopp): Gruppe schließen. */
    override fun remove(reason: RemovalReason) {
        if (!entityWorld.isClient) endShackles()
        super.remove(reason)
    }

    /**
     * Die Todesbewegung braucht 40 Ticks, nicht 20. Die Niederlage-Szene `kampe.defeat` spielt die Basis
     * (`TartarosGuardian.onDefeated` über `defeatScript`) unabhängig vom Körper weiter.
     */
    override fun updatePostDeath() {
        deathTime++
        if (deathTime >= BossTimings.Kampe.DEATH && !entityWorld.isClient && !isRemoved) {
            entityWorld.sendEntityStatus(this, EntityStatuses.ADD_DEATH_PARTICLES)
            remove(RemovalReason.KILLED)
        }
    }

    companion object {
        /** PLAN 4.1: 420 → 920. */
        const val HEALTH = 920.0

        // --- Überarbeitung 3: reine Darstellung (keine Kampfzahl) ---

        /** Flächeneffekt des Schwanzfegers (`kampe.sweep`) und des Giftfächers (`kampe.spit`), Ticks. */
        private const val SWEEP_FX_LIFE = 20
        private const val SPIT_FX_LIFE = 16

        /** Gifttropfen nach dem Speien: so weit vor dem Maul, Umkreis, Zahl, Lebenszeit der Lachen (nur Optik). */
        private const val SPIT_DRIP_AHEAD = 2.4
        private const val SPIT_DRIP_RADIUS = 2.2f
        private const val SPIT_DRIPS = 8
        private const val SPIT_DRIP_LIFE = 60

        /** Bodenrisse am Einstich. */
        private const val STING_CRACKS = 4

        /** Kamera-Ruck (Grad, Ticks, Reichweite): Feger seitlich, Stich und Sturzstoß nach unten. */
        private const val SWEEP_KICK_PITCH = 0.6f
        private const val SWEEP_KICK_YAW = 1.5f
        private const val STING_KICK_PITCH = 1.5f
        private const val DIVE_KICK_PITCH = 2f
        private const val KICK_TICKS = 6
        private const val KICK_RADIUS = 28f

        /** Wirbel beim Hochwinden, Trümmer und Risse am Einschlag des Sturzstoßes und beim Aufsetzen im Auftritt. */
        private const val COIL_SWIRL_RADIUS = 2.6f
        private const val DIVE_DEBRIS_RADIUS = 2.6f
        private const val DIVE_CRACK_RADIUS = 3.2f
        private const val INTRO_DEBRIS_RADIUS = 2.4f
        private const val INTRO_CRACK_RADIUS = 3f

        private val UP = Vec3d(0.0, 1.0, 0.0)

        /** Fluggeschwindigkeit der Giftgeschosse (Blöcke je Tick). */
        private const val SPIT_SPEED = 0.85
        private const val COIL_AT = 0.5f

        /** cos 60°: Vor ihr (±60°) trifft der Schwanzfeger nicht. */
        private const val FRONT_COS = 0.5

        /** Grubenboden: so weit von der Mitte reichen die Sturzlinien (Grubenradius 24 minus Rand). */
        private const val PIT = 22.5

        /**
         * Sitze am Titanen (vom Fußpunkt der Grubenmitte): linke und rechte Schulter, der gebeugte Rücken. Abgelesen aus
         * `TartarusPlans.titan` (Schultern bei ±9 / +21, Rücken bis +25).
         */
        private val PERCHES = listOf(Vec3d(-9.0, 25.0, -1.0), Vec3d(9.0, 25.0, -1.0), Vec3d(0.0, 26.0, 3.5))

        // --- Überarbeitung 4 (P22): Geometrie und Darstellung (keine Kampfzahl) ---

        /** Ebene „Sims“ im Kerker-Vertrag (`TartarusArenas.prison`). */
        private const val LEDGE_ID = "ledge"

        /**
         * Höhenband der Angriffe, die beide Höhen treffen (Giftfächer, Stich, Sturzstöße): bis so hoch über dem
         * Grubenboden (Briefing: +8; der Sims liegt bei +5).
         */
        private const val BAND_REACH = 8.0

        /** Stichpunkt am Sims-Rand: Ring so weit von der Mitte (Sims = Blockringe 22 … 24, innere Kante ≈ 21,5). */
        private const val LEDGE_RIM = TartarusArenas.PRISON_LEDGE_INNER + 0.5

        /** Abtastungen für „berührt die Form den Sims?“. */
        private const val LEDGE_PROBES = 12

        /** Thema der ruhenden Schloss-Leiste: `10 + Vfx.Style.ordinal`, wie `Cine.phaseBar` (P04). */
        private const val BAR_THEME_BASE = 10

        /** Die zwei Schlösser, deren Ketten an den Schultern hängen (225°, 315°; `TartarusArenas.prisonLockChainTarget`). */
        private val SHOULDER_LOCKS = 2 to 3

        /** Halber Schulterabstand des Titanen (Vertrag: Schulterketten bei x ±9); für `kampe.titan_jolt`. */
        private const val TITAN_SHOULDER_HALF = 9f

        /** Brusthöhe des Titanen über dem Grubenboden, wenn es keine Schlösser gibt (Vertrag: Schultern bei +20). */
        private const val TITAN_CHEST_FALLBACK = 20.0

        private val POSE_ID: TrackedData<Int> = DataTracker.registerData(KampeEntity::class.java, TrackedDataHandlerRegistry.INTEGER)
        private val RESTART_ID: TrackedData<Int> = DataTracker.registerData(KampeEntity::class.java, TrackedDataHandlerRegistry.INTEGER)

        fun attributes(): DefaultAttributeContainer.Builder = guardianAttributes()
    }
}
