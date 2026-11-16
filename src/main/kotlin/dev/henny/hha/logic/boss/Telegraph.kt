package dev.henny.hha.logic.boss

import dev.henny.hha.Hha
import dev.henny.hha.logic.Vfx
import net.minecraft.entity.Entity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * **Der eine Weg für Ansagen** (Plan 3.2). Jede Bodenform, die einen Treffer ankündigt, läuft hierüber; in `logic/boss`,
 * `logic/world` und `entity/` ruft niemand mehr `Vfx.telegraphRing`/`Vfx.telegraphLine` direkt auf.
 *
 * Was der Helfer übernimmt:
 * - **Form = Trefferfläche.** Jede Ansage liefert eine [Shape] zurück, die genau die gezeigte Fläche prüft
 *   ([Shape.contains], [Shape.hits]). Wer damit prüft, trifft nie neben der Ansage.
 * - **Mindestdauer** aus [BossTimings.Min]: zu kurze Werte werden angehoben und einmal je Kampf im Log gemeldet.
 *   Darum immer auf [Shape.ticks] bzw. [Group.ticks] warten, nie auf die eigene Konstante.
 * - **Budget je Spieler:** gleichzeitig höchstens 1 Boss-Ansage + 1 Saal-Gefahr, im ganzen Kampf höchstens
 *   [MAX_GROUPS] Gruppen. Boss-Ansagen ([Fight.boss]) gehen immer durch, Saal-Gefahren ([Fight.hazard]) werden
 *   verweigert (`null`), dann verschiebt der Kampf die Gefahr und versucht es später erneut.
 * - **Gruppen:** Was zur selben Attacke gehört (drei Kreuzweg-Linien, ein Kreis je Spieler, die Wellen eines
 *   Höllenfeuers), kommt in **eine** [Group] und zählt als eins, auch wenn Formen später nachkommen.
 * - **Effekt-Regeln (Plan 3.3)** für alles, was im Kampf-Scope ([Fight.during]) entsteht, setzt [Vfx] zentral durch:
 *   Rütteln ≤ [Vfx.SHAKE_MAX] und ≤ [Vfx.SHAKE_MAX_TICKS], kein Rütteln während einer laufenden Ansage, Blitz ≤
 *   [Vfx.FLASH_MAX], ein Flächeneffekt je Treffer, Gold bleibt Gold, Gefahrenfarben auf dem Client. Cutscenes sind
 *   ausgenommen.
 *
 * Darstellung (Client): Der Rand steht ab Tick 0 voll, die Füllung wächst gleichmäßig bis zum Treffer, und in den
 * letzten [BossTimings.Min.FLASH] Ticks wird die Form weiß („jetzt“). Gold ([safe]) heißt immer „hier bist du sicher“.
 *
 * ### Einbau in einen Kampf
 * ```kotlin
 * class HadesFight(val world: ServerWorld, …) {
 *     // Einmal je Kampf: Name fürs Log, Gefahrenfarbe, wer mitkämpft.
 *     val telegraph = Telegraph.open(world, "hades", Vfx.Style.HADES) { players() }
 *
 *     // Der ganze Kampf-Tick läuft im Scope, damit die Effekt-Regeln greifen (auch für alte Vfx-Aufrufe).
 *     fun tick() = telegraph.during { step() }
 *
 *     // Phasenwechsel, Tod, Neustart: offene Ansagen vergessen.
 *     private fun nextPhase() { telegraph.reset(); … }
 * }
 * ```
 * Effekte aus Entity-Ticks (Geschosse, Wächter) laufen außerhalb des Kampf-Ticks; die packt man ebenfalls in
 * `telegraph.during { … }` oder nimmt die Helfer [Fight.hitShake]/[Fight.roomShake]/[Fight.sound].
 *
 * ### Boss-Ansage mit einer Form
 * ```kotlin
 * val slam = fight.telegraph.ring(spot, BossTimings.Hades.SLAM_RADIUS, BossTimings.Hades.SLAM_WINDUP)
 * entity.pose = HadesPose.SLAM
 * fight.sound(HhaSounds.CHARGE, 2.5f, 0.7f, spot)        // genau ein Ansageton
 * wait(slam.ticks)                                         // angehobene Dauer, nicht die Konstante!
 * for (player in slam.hits(fight.targets())) if (fight.grounded(player)) fight.hurt(player, 24f)
 * Vfx.shockwave(world, Vfx.Style.HADES, spot, BossTimings.Hades.SLAM_RADIUS)   // genau ein Flächeneffekt
 * fight.telegraph.hitShake(spot, 3f)                       // optional ein Rütteln (≤ 3)
 * ```
 *
 * ### Kegel und Linie
 * ```kotlin
 * val sweep = fight.telegraph.cone(entity.entityPos, facing, BossTimings.Kronos.SWEEP_RADIUS,
 *     BossTimings.Kronos.SWEEP_HALF_ANGLE, BossTimings.Kronos.SWEEP_WINDUP, Telegraph.Pace.HEAVY)
 * val dash = fight.telegraph.line(from, to, BossTimings.Hermes.DASH_WIDTH, BossTimings.Hermes.DASH_WINDUP_FIRST)
 * ```
 *
 * ### Mehrere Formen einer Attacke = eine Gruppe
 * ```kotlin
 * val cross = fight.telegraph.boss()
 * for ((a, b) in lanes) cross.line(a, b, 2f, BossTimings.Hermes.CROSS_WINDUP)
 * wait(cross.ticks)
 * val struck = cross.hits(fight.players())
 * ```
 *
 * ### Saal-Gefahr (kann verweigert werden)
 * ```kotlin
 * val fire = fight.telegraph.hazard() ?: return@sequence      // belegt: Gefahr verschieben, später erneut
 * repeat(BossTimings.Hades.HELLFIRE_WAVES) {
 *     val circles = fight.targets().map { fire.ring(it.entityPos, BossTimings.Hades.HELLFIRE_RADIUS, BossTimings.Hades.HELLFIRE_WINDUP) }
 *     wait(fire.ticks)
 *     for (c in circles) for (p in c.hits(fight.targets())) fight.hurt(p, 12f)
 * }
 * fire.end()                                                   // Platz sofort frei geben
 *
 * // Gefahr, die nach dem Treffer liegen bleibt (Spalten 90 Ticks offen):
 * val rifts = fight.telegraph.hazard() ?: return@sequence
 * val beams = spots.map { rifts.line(center, it, 2.5f, BossTimings.Hades.RIFT_WINDUP) }
 * rifts.hold(BossTimings.Hades.RIFT_WINDUP + BossTimings.Hades.RIFT_OPEN)
 * ```
 *
 * ### Sichere Flächen (Gold)
 * ```kotlin
 * val judge = fight.telegraph.boss(pace = Telegraph.Pace.ROOM)
 * val safe = spots.map { judge.safe(it, 3f, BossTimings.Hades.JUDGE_HIT) }
 * wait(judge.ticks)
 * for (p in fight.targets()) if (safe.none { it.contains(p) }) fight.hurt(p, 34f)
 * ```
 */
object Telegraph {

    /** Höchstens so viele Ansage-Gruppen gleichzeitig in einem Kampf (Boss-Ansagen und Saal-Gefahren zusammen). */
    const val MAX_GROUPS = 4

    /** Töne je Tick und Kampf (Plan 3.3: Ansageton + Trefferton). */
    const val SOUNDS_PER_TICK = 2

    /** Zwei Flächeneffekte, die im selben Tick näher als so viele Blöcke beieinander liegen, sind derselbe Treffer. */
    const val IMPACT_MERGE = 3.0

    /** Dauer von [Fight.hitShake] und [Fight.roomShake]. */
    const val HIT_SHAKE_TICKS = 8

    /** Der Kampf, dessen Code gerade läuft (siehe [Fight.during]); `null` heißt: kein Kampf, keine Kampfregeln. */
    @JvmStatic
    var current: Fight? = null
        private set

    /** Wer eine Ansage auslöst: der Boss selbst (geht immer durch) oder der Saal (unterliegt dem Budget). */
    enum class Source { BOSS, HAZARD }

    /**
     * Art der Ansage = Mindestdauer (Plan 3.2, [BossTimings.Min]). Ohne Angabe gilt: Kreise und Ringe ab
     * [BossTimings.Min.AREA_RADIUS] sind [AREA], alles andere [QUICK]; Saal-Gefahren mindestens [AREA].
     */
    enum class Pace(val minTicks: Int) {
        /** Schneller Nahkampfhieb (10). */
        QUICK(BossTimings.Min.QUICK),

        /** Schwerer Hieb oder Stoß (16). */
        HEAVY(BossTimings.Min.HEAVY),

        /** Wurf oder Geschoss bis zum Einschlag (16). */
        PROJECTILE(BossTimings.Min.PROJECTILE),

        /** Fläche ab Radius 6 (20). */
        AREA(BossTimings.Min.AREA),

        /** Saalweite Gefahr: trifft alles außer markierten sicheren Stellen (40). */
        ROOM(BossTimings.Min.ROOM),

        /** Folgehieb einer Kette, ab dem Fallen des vorigen Hiebs (10). */
        FOLLOW_UP(BossTimings.Min.FOLLOW_UP),
    }

    /**
     * Legt die Ansage-Buchhaltung eines Kampfes an.
     *
     * @param name kurzer Name fürs Log (`"hermes"`, `"hades"`, `"warden"` …)
     * @param style Gefahrenfarbe des Gegners (Plan 3.1), gilt für alle Formen ohne eigenen Stil; später änderbar
     *   über [Fight.style] (z. B. je Hermes-Stage)
     * @param players alle Spieler, die mitkämpfen; Standard für „wen betrifft die Ansage“
     */
    fun open(world: ServerWorld, name: String, style: Vfx.Style, players: () -> Collection<ServerPlayerEntity>): Fight =
        Fight(world, name, style, players)

    // ---------------------------------------------------------------------------------------------
    // Geometrie (waagerecht; die Höhe, z. B. „überspringen“, prüft der Kampf selbst)
    // ---------------------------------------------------------------------------------------------

    /** Liegt [pos] im Kreis bzw. Ring um [center]? [inner] > 0: nur das Band zwischen [inner] und [radius]. */
    fun inRing(pos: Vec3d, center: Vec3d, radius: Double, inner: Double = 0.0, margin: Double = 0.0): Boolean {
        val dx = pos.x - center.x
        val dz = pos.z - center.z
        val d = sqrt(dx * dx + dz * dz)
        return d <= radius + margin && (inner <= 0.0 || d >= inner - margin)
    }

    /** Liegt [pos] auf der Linie von [from] nach [to] mit der vollen Breite [width]? */
    fun inLine(pos: Vec3d, from: Vec3d, to: Vec3d, width: Double, margin: Double = 0.0): Boolean {
        val vx = to.x - from.x
        val vz = to.z - from.z
        val len2 = vx * vx + vz * vz
        val wx = pos.x - from.x
        val wz = pos.z - from.z
        val t = if (len2 < 1e-9) 0.0 else ((wx * vx + wz * vz) / len2).coerceIn(0.0, 1.0)
        val dx = wx - vx * t
        val dz = wz - vz * t
        val reach = width * 0.5 + margin
        return dx * dx + dz * dz <= reach * reach
    }

    /** Liegt [pos] im Kegel ab [apex] in Richtung [direction], Reichweite [radius], halber Öffnungswinkel [halfAngle] (Grad)? */
    fun inCone(pos: Vec3d, apex: Vec3d, direction: Vec3d, radius: Double, halfAngle: Double, margin: Double = 0.0): Boolean {
        val dx = pos.x - apex.x
        val dz = pos.z - apex.z
        val d = sqrt(dx * dx + dz * dz)
        if (d > radius + margin) return false
        if (d <= margin + 0.5 || halfAngle >= 180.0) return true
        val flat = flat(direction)
        val cos = ((dx * flat.x + dz * flat.z) / d).coerceIn(-1.0, 1.0)
        val angle = Math.toDegrees(acos(cos))
        return angle <= halfAngle + Math.toDegrees(atan2(margin, d))
    }

    private fun flat(direction: Vec3d): Vec3d =
        Vec3d(direction.x, 0.0, direction.z).let { if (it.lengthSquared() < 1e-9) Vec3d(0.0, 0.0, 1.0) else it.normalize() }

    // ---------------------------------------------------------------------------------------------
    // Formen
    // ---------------------------------------------------------------------------------------------

    /**
     * Eine gezeigte Ansage-Form. [ticks] ist die **tatsächliche** Dauer bis zum Treffer (nach Anhebung auf die
     * Mindestdauer), [hitTick] der Welt-Tick des Treffers. [safe] = goldene sichere Fläche statt Trefferfläche.
     */
    sealed class Shape(val ticks: Int, val start: Long, val safe: Boolean, internal val world: ServerWorld) {
        /** Welt-Tick (`world.time`), in dem der Treffer fällt. */
        val hitTick: Long get() = start + ticks

        /** Noch so viele Ticks bis zum Treffer (0 = jetzt oder vorbei). */
        val remaining: Int get() = (hitTick - world.time).coerceAtLeast(0L).toInt()

        /** Läuft die Ansage noch (Treffer nicht gefallen)? */
        val running: Boolean get() = world.time < hitTick

        /** Liegt [pos] in der Form? [margin] weitet sie um so viele Blöcke (z. B. halbe Spielerbreite). */
        abstract fun contains(pos: Vec3d, margin: Double = 0.0): Boolean

        /** Steht [entity] in der Form (mit halber Breite als Spielraum)? */
        fun contains(entity: Entity): Boolean = contains(entity.entityPos, entity.width * 0.5)

        /** Alle aus [candidates], die in der Form stehen. */
        fun <T : Entity> hits(candidates: Iterable<T>): List<T> = candidates.filter { contains(it) }
    }

    /** Kreis ([inner] = 0) oder Ring um [center]. */
    class Ring internal constructor(
        val center: Vec3d, val radius: Double, val inner: Double,
        ticks: Int, start: Long, safe: Boolean, world: ServerWorld,
    ) : Shape(ticks, start, safe, world) {
        override fun contains(pos: Vec3d, margin: Double) = inRing(pos, center, radius, inner, margin)
    }

    /** Linie von [from] nach [to], [width] = volle Breite. */
    class Line internal constructor(
        val from: Vec3d, val to: Vec3d, val width: Double,
        ticks: Int, start: Long, safe: Boolean, world: ServerWorld,
    ) : Shape(ticks, start, safe, world) {
        override fun contains(pos: Vec3d, margin: Double) = inLine(pos, from, to, width, margin)
    }

    /** Kegel ab [apex] in [direction] (waagerecht, normiert), Reichweite [radius], halber Öffnungswinkel [halfAngle] in Grad. */
    class Cone internal constructor(
        val apex: Vec3d, val direction: Vec3d, val radius: Double, val halfAngle: Double,
        ticks: Int, start: Long, world: ServerWorld,
    ) : Shape(ticks, start, false, world) {
        override fun contains(pos: Vec3d, margin: Double) = inCone(pos, apex, direction, radius, halfAngle, margin)
    }

    // ---------------------------------------------------------------------------------------------
    // Kampf
    // ---------------------------------------------------------------------------------------------

    /**
     * Ansage-Buchhaltung eines Kampfes (anlegen mit [open]). Hält die laufenden [Group]s, setzt Budget und
     * Mindestdauern durch und ist der Scope für die zentralen Effekt-Regeln ([during]).
     */
    class Fight internal constructor(
        val world: ServerWorld,
        val name: String,
        /** Gefahrenfarbe für alle Formen ohne eigenen Stil (Plan 3.1). */
        var style: Vfx.Style,
        private val roster: () -> Collection<ServerPlayerEntity>,
    ) {
        private val groups = ArrayList<Group>()
        private val warned = HashSet<String>()
        private var impactTick = Long.MIN_VALUE
        private val impacts = ArrayList<Vec3d>()
        private var soundTick = Long.MIN_VALUE
        private var soundCount = 0

        private fun now(): Long = world.time

        /** Alle Spieler des Kampfes (wie beim Anlegen übergeben). */
        fun players(): List<ServerPlayerEntity> = roster().toList()

        /**
         * Führt [block] im Kampf-Scope aus: Alle [Vfx]-Effekte darin gelten als Effekte dieses Gegners und unterliegen
         * den Regeln aus Plan 3.3 (siehe [Telegraph]). Verschachteln ist erlaubt; danach gilt wieder der vorige Scope.
         */
        fun <T> during(block: () -> T): T {
            val previous = current
            current = this
            try {
                return block()
            } finally {
                current = previous
            }
        }

        // --- Gruppen -------------------------------------------------------------------------------

        /**
         * Neue Boss-Ansage (eine Attacke des Gegners). Geht **immer** durch.
         *
         * @param players wen die Ansage betrifft (Standard: alle Mitkämpfer); zählt fürs Budget und für die
         *   Rüttel-Sperre
         * @param pace Mindest-Art für alle Formen der Gruppe, z. B. [Pace.HEAVY] für einen Stoß
         * @param style Gefahrenfarbe, Standard [Fight.style]
         * @param reserve hält die Gruppe so viele Ticks aktiv, auch bevor eine Form da ist
         */
        fun boss(
            players: Collection<ServerPlayerEntity>? = null, pace: Pace? = null, style: Vfx.Style = this.style, reserve: Int = 0,
        ): Group {
            prune()
            val who = (players ?: roster()).map { it.uuid }.toSet()
            if (groups.any { it.source == Source.BOSS && it.players.any(who::contains) }) {
                note("boss.overlap", "zwei Boss-Ansagen gleichzeitig für denselben Spieler; eine Attacke = eine Gruppe")
            }
            if (groups.size >= MAX_GROUPS) note("groups.max", "mehr als $MAX_GROUPS Ansage-Gruppen gleichzeitig (nur Boss-Ansagen dürfen das)")
            return Group(Source.BOSS, who, pace, style, reserve).also { groups.add(it) }
        }

        /**
         * Neue Saal-Gefahr. **Verweigert** (`null`), wenn einer der betroffenen Spieler schon eine Saal-Gefahr hat
         * oder im Kampf schon [MAX_GROUPS] Gruppen laufen. Dann die Gefahr verschieben und später erneut versuchen.
         * Ohne [pace] gilt für ihre Formen mindestens [Pace.AREA].
         *
         * @param reserve hält den Platz so viele Ticks, auch bevor eine Form da ist (z. B. Kulisse vor der Ansage)
         */
        fun hazard(
            players: Collection<ServerPlayerEntity>? = null, pace: Pace? = null, style: Vfx.Style = this.style, reserve: Int = 0,
        ): Group? {
            if (!canStartHazard(players)) return null
            val who = (players ?: roster()).map { it.uuid }.toSet()
            return Group(Source.HAZARD, who, pace ?: Pace.AREA, style, reserve).also { groups.add(it) }
        }

        /** Würde [hazard] jetzt durchgehen? */
        fun canStartHazard(players: Collection<ServerPlayerEntity>? = null): Boolean {
            prune()
            if (groups.size >= MAX_GROUPS) return false
            val who = (players ?: roster()).map { it.uuid }.toSet()
            return groups.none { it.source == Source.HAZARD && it.players.any(who::contains) }
        }

        /** Hat [player] gerade eine laufende Saal-Gefahr? */
        fun hasHazard(player: ServerPlayerEntity): Boolean {
            prune()
            return groups.any { it.source == Source.HAZARD && player.uuid in it.players }
        }

        /** Hat [player] gerade eine laufende Boss-Ansage? */
        fun hasBossTelegraph(player: ServerPlayerEntity): Boolean {
            prune()
            return groups.any { it.source == Source.BOSS && player.uuid in it.players }
        }

        /** Laufende Gruppen (Boss + Saal). */
        val activeGroups: Int
            get() {
                prune()
                return groups.size
            }

        /** Vergisst alle laufenden Gruppen (Phasenwechsel, Tod, Neustart). Gezeigte Formen laufen auf dem Client aus. */
        fun reset() {
            groups.clear()
        }

        // --- Kurzformen: eine Form = eine Boss-Ansage ------------------------------------------------

        /** Boss-Ansage: Kreis (oder Ring mit [inner] > 0). Siehe [Group.ring]. */
        fun ring(
            center: Vec3d, radius: Float, ticks: Int, inner: Float = 0f, pace: Pace? = null,
            players: Collection<ServerPlayerEntity>? = null, style: Vfx.Style = this.style,
        ): Ring = boss(players, style = style).ring(center, radius, ticks, inner, pace)

        /** Boss-Ansage: Linie mit voller Breite [width]. Siehe [Group.line]. */
        fun line(
            from: Vec3d, to: Vec3d, width: Float, ticks: Int, pace: Pace? = null,
            players: Collection<ServerPlayerEntity>? = null, style: Vfx.Style = this.style,
        ): Line = boss(players, style = style).line(from, to, width, ticks, pace)

        /** Boss-Ansage: Kegel. Siehe [Group.cone]. */
        fun cone(
            apex: Vec3d, direction: Vec3d, radius: Float, halfAngle: Float, ticks: Int, pace: Pace? = null,
            players: Collection<ServerPlayerEntity>? = null, style: Vfx.Style = this.style,
        ): Cone = boss(players, style = style).cone(apex, direction, radius, halfAngle, ticks, pace)

        /** Goldene sichere Fläche als eigene Boss-Ansage. Gehört sie zu einer Attacke, besser [Group.safe] nehmen. */
        fun safe(center: Vec3d, radius: Float, ticks: Int, players: Collection<ServerPlayerEntity>? = null): Ring =
            boss(players).safe(center, radius, ticks)

        // --- Treffer-Helfer (Plan 3.3) ---------------------------------------------------------------

        /** Rütteln eines normalen Treffers: höchstens [Vfx.SHAKE_HIT_MAX] (3), [HIT_SHAKE_TICKS] (8) Ticks. */
        fun hitShake(at: Vec3d, strength: Float = 3f, radius: Float = 32f) = during {
            Vfx.shake(world, at, strength.coerceAtMost(Vfx.SHAKE_HIT_MAX), HIT_SHAKE_TICKS, radius)
        }

        /** Rütteln für Phasenwechsel und Saalweites: höchstens [Vfx.SHAKE_MAX] (5), [Vfx.SHAKE_MAX_TICKS] (8) Ticks. */
        fun roomShake(at: Vec3d, strength: Float = 4f, radius: Float = 60f) = during {
            Vfx.shake(world, at, strength.coerceAtMost(Vfx.SHAKE_MAX), Vfx.SHAKE_MAX_TICKS, radius)
        }

        /**
         * Ton des Gegners mit Budget: höchstens [SOUNDS_PER_TICK] je Tick und Kampf (Ansageton + Trefferton).
         * @return false, wenn der Ton dem Budget zum Opfer fiel (oder gerade vorgespult wird)
         */
        fun sound(event: SoundEvent, at: Vec3d, volume: Float = 2.5f, pitch: Float = 1f, category: SoundCategory = SoundCategory.HOSTILE): Boolean {
            if (Cine.muted) return false
            val now = now()
            if (soundTick != now) {
                soundTick = now
                soundCount = 0
            }
            if (soundCount >= SOUNDS_PER_TICK && !inCutscene()) {
                note("sound.max", "mehr als $SOUNDS_PER_TICK Töne im selben Tick")
                return false
            }
            soundCount++
            world.playSound(null, at.x, at.y, at.z, event, category, volume, pitch)
            return true
        }

        // --- intern: für Vfx ------------------------------------------------------------------------

        /** Sieht gerade jemand aus dem Kampf eine Cutscene? Dann gelten die Effekt-Regeln nicht. */
        internal fun inCutscene(): Boolean = roster().any { Cine.isPlaying(it) }

        /** Höchstens ein Flächeneffekt je Treffer: gleicher Tick und näher als [IMPACT_MERGE] = derselbe Treffer. */
        internal fun allowImpact(pos: Vec3d): Boolean {
            val now = now()
            if (impactTick != now) {
                impactTick = now
                impacts.clear()
            }
            if (impacts.any { it.squaredDistanceTo(pos) < IMPACT_MERGE * IMPACT_MERGE }) {
                note("impact.stack", "mehr als ein Flächeneffekt je Treffer (shockwave/nova/pillar/crescent), weitere verworfen")
                return false
            }
            impacts.add(pos)
            return true
        }

        /**
         * Darf [player] jetzt gerüttelt werden? Nein, solange für ihn eine Ansage läuft (Treffer noch nicht gefallen),
         * und nein für ein Warn-Rütteln, das im selben Tick wie eine Ansage beginnt und mindestens so lang ist.
         */
        internal fun allowShake(player: ServerPlayerEntity, ticks: Int): Boolean {
            val now = now()
            for (group in groups) {
                if (player.uuid !in group.players) continue
                for (shape in group.shapes) {
                    if (shape.start < now && now < shape.hitTick - 1) {
                        note("shake.during", "Rütteln während einer laufenden Ansage verworfen")
                        return false
                    }
                    if (shape.start == now && ticks >= shape.ticks) {
                        note("shake.warn", "Warn-Rütteln über die ganze Ansage verworfen")
                        return false
                    }
                }
            }
            return true
        }

        // --- Buchhaltung ----------------------------------------------------------------------------

        private fun prune() {
            val now = now()
            groups.removeIf { it.until <= now }
        }

        /** Mindestdauer durchsetzen; zu kurze Werte anheben und einmal je Fall melden. */
        internal fun enforce(form: String, ticks: Int, auto: Pace, groupPace: Pace?, shapePace: Pace?): Int {
            val pace = listOfNotNull(auto, groupPace, shapePace).maxBy { it.minTicks }
            if (ticks >= pace.minTicks) return ticks
            note("min.$form.$ticks.${pace.name}", "$form mit $ticks Ticks unter der Mindestdauer ${pace.minTicks} (${pace.name}), angehoben")
            return pace.minTicks
        }

        internal fun note(key: String, message: String) {
            if (warned.add(key)) Hha.LOGGER.warn("[Telegraph:{}] {}", name, message)
        }

        /**
         * Eine Gruppe von Formen, die zu **einer** Attacke gehören; zählt im Budget als eins. Formen dürfen auch später
         * dazukommen (Wellen). Aktiv bis zum letzten Treffer bzw. bis [hold]/[end].
         */
        inner class Group internal constructor(
            val source: Source,
            /** Wen die Gruppe betrifft (UUIDs). */
            val players: Set<UUID>,
            private val pace: Pace?,
            val style: Vfx.Style,
            reserve: Int,
        ) {
            private val list = ArrayList<Shape>()

            /** Alle Formen der Gruppe, in Reihenfolge. */
            val shapes: List<Shape> get() = list

            /** Welt-Tick, bis zu dem die Gruppe im Budget zählt (ausschließlich). */
            var until: Long = now() + max(1, reserve)
                private set

            /** Zählt die Gruppe noch? */
            val active: Boolean get() = now() < until

            /** Ticks bis zum spätesten Treffer der Gruppe (0, wenn alle gefallen sind). Darauf warten. */
            val ticks: Int get() = (list.maxOfOrNull { it.hitTick } ?: now()).minus(now()).coerceAtLeast(0L).toInt()

            /** Nimmt die Form auf und hält die Gruppe bis zu ihrem Treffer im Budget (auch nach einer Pause zwischen Wellen). */
            private fun add(shape: Shape): Shape {
                list.add(shape)
                until = max(until, shape.hitTick)
                if (groups.none { it === this }) groups.add(this)
                return shape
            }

            /**
             * Kreis mit [radius] um [center]; mit [inner] > 0 ein Ring (nur das Band ist Trefferfläche, z. B. eine
             * Bodenwelle zum Überspringen). Ab Radius 6 gilt mindestens [Pace.AREA].
             */
            fun ring(center: Vec3d, radius: Float, ticks: Int, inner: Float = 0f, pace: Pace? = null): Ring {
                val auto = if (radius >= BossTimings.Min.AREA_RADIUS) Pace.AREA else Pace.QUICK
                val life = enforce("ring", ticks, auto, this.pace, pace)
                Vfx.telegraphRingRaw(world, style, center, radius, inner.coerceIn(0f, radius), life)
                return add(Ring(center, radius.toDouble(), inner.toDouble().coerceIn(0.0, radius.toDouble()), life, now(), false, world)) as Ring
            }

            /** Linie von [from] nach [to]; [width] ist die **volle** Breite (Plan: „Linie Breite 1,6“). */
            fun line(from: Vec3d, to: Vec3d, width: Float, ticks: Int, pace: Pace? = null): Line {
                val life = enforce("line", ticks, Pace.QUICK, this.pace, pace)
                Vfx.telegraphLineRaw(world, style, from, to, width * 0.5f, life)
                return add(Line(from, to, width.toDouble(), life, now(), false, world)) as Line
            }

            /**
             * Kegel ab [apex] in Richtung [direction] (nur waagerecht zählt), Reichweite [radius], halber
             * Öffnungswinkel [halfAngle] in Grad (180 = voller Kreis). Kegel sind Hiebe: ohne [pace] gilt [Pace.QUICK].
             */
            fun cone(apex: Vec3d, direction: Vec3d, radius: Float, halfAngle: Float, ticks: Int, pace: Pace? = null): Cone {
                val life = enforce("cone", ticks, Pace.QUICK, this.pace, pace)
                val dir = flat(direction)
                val half = halfAngle.coerceIn(1f, 180f)
                Vfx.telegraphConeRaw(world, style, apex, dir, radius, half, life)
                return add(Cone(apex, dir, radius.toDouble(), half.toDouble(), life, now(), world)) as Cone
            }

            /**
             * Goldene **sichere** Fläche (Kreis). Immer Gold, auch unter `Vfx.styleOverride`. [Shape.contains] heißt hier
             * „ist sicher“. Blitzt am Ende ebenfalls auf, damit „jetzt“ überall gleich aussieht.
             */
            fun safe(center: Vec3d, radius: Float, ticks: Int): Ring {
                val life = enforce("safe", ticks, Pace.QUICK, this.pace, null)
                Vfx.telegraphRingRaw(world, Vfx.Style.GOLD, center, radius, 0f, life, forceStyle = true)
                return add(Ring(center, radius.toDouble(), 0.0, life, now(), true, world)) as Ring
            }

            /**
             * Goldene **sichere Ringfläche** zwischen [inner] und [radius] (z. B. „der Rand ist sicher“ beim Münzwurf
             * oder in der zweiten Styx-Flut). Immer Gold; [Shape.contains] heißt „steht im sicheren Band“.
             */
            fun safeRing(center: Vec3d, radius: Float, inner: Float, ticks: Int): Ring {
                val life = enforce("safe", ticks, Pace.QUICK, this.pace, null)
                val band = inner.coerceIn(0f, radius)
                Vfx.telegraphRingRaw(world, Vfx.Style.GOLD, center, radius, band, life, forceStyle = true)
                return add(Ring(center, radius.toDouble(), band.toDouble(), life, now(), true, world)) as Ring
            }

            /** Goldene sichere Bahn (z. B. die Lücke im Zug der Toten); [width] = volle Breite. */
            fun safeLine(from: Vec3d, to: Vec3d, width: Float, ticks: Int): Line {
                val life = enforce("safe", ticks, Pace.QUICK, this.pace, null)
                Vfx.telegraphLineRaw(world, Vfx.Style.GOLD, from, to, width * 0.5f, life, forceStyle = true)
                return add(Line(from, to, width.toDouble(), life, now(), true, world)) as Line
            }

            /** Alle aus [candidates] (Standard: Mitkämpfer), die in mindestens einer Gefahr-Form der Gruppe stehen. */
            fun hits(candidates: Iterable<ServerPlayerEntity> = roster()): List<ServerPlayerEntity> =
                candidates.filter { p -> list.any { !it.safe && it.contains(p) } }

            /** Steht [entity] in einer der sicheren Formen der Gruppe? */
            fun isSafe(entity: Entity): Boolean = list.any { it.safe && it.contains(entity) }

            /** Hält die Gruppe noch [ticks] Ticks ab jetzt im Budget (Gefahr, die nach dem Treffer liegen bleibt). */
            fun hold(ticks: Int): Group {
                until = max(until, now() + ticks)
                if (groups.none { it === this }) groups.add(this)
                return this
            }

            /** Gibt den Platz sofort frei (Attacke vorbei oder abgebrochen). */
            fun end() {
                until = now()
                groups.remove(this)
            }

            /**
             * Bricht die Gruppe ab: Ihre Formen verschwinden beim Client (nur diese, andere Ansagen und Effekte bleiben),
             * dann wie [end]. Für Angriffe, die mitten in der Ansage enden (z. B. Hekates Dreigestalt, wenn die Echte
             * getroffen wird).
             */
            fun cancel() {
                for (shape in list) {
                    val origin = when (shape) {
                        is Ring -> shape.center
                        is Line -> shape.from
                        is Cone -> shape.apex
                    }
                    Vfx.telegraphClear(world, origin)
                }
                end()
            }
        }
    }
}
