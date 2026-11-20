package dev.henny.hha.logic.boss.sig

import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.olymp.ZeusAction
import dev.henny.hha.entity.boss.olymp.ZeusEntity
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.ArenaCollapse
import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Cover
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha.logic.boss.arena.ArenaDevices
import dev.henny.hha.logic.boss.timings.ZeusTimings as Z
import dev.henny.hha.world.arena.ArenaSpec
import dev.henny.hha.world.arena.Disc
import dev.henny.hha.world.arena.Part
import dev.henny.hha.world.olymp.OlympusArenas
import dev.henny.hha.world.olymp.OlympusLayout
import net.minecraft.block.BlockState
import net.minecraft.network.packet.s2c.play.PositionFlag
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Signatur des Zeus (Überarbeitung 4, PLAN 3.5 und 4.2, Paket P18): **Die Insel bricht** und der **Erdungsgriff**.
 *
 * - **Erdungsgriff:** Jeder Blitzpfeiler hat in der Terrassenwand einen `grounding_rod` (Vertrag `rod_i`). Ist der
 *   Pfeiler geladen, steht der Griff auf Zustand 1 (golden), sonst auf 0; nach einem Griff auf 2, bis der Pfeiler
 *   erneuert wird. Rechtsklick ([grip]): Kampf läuft, Teilnehmer (prüft [ArenaDevices]), Zeus höchstens
 *   [Z.ROD_REACH] von der Pfeilerachse und in Sichtlinie zum Pfeilerkopf, letzter Griff mindestens [Z.ROD_COOLDOWN]
 *   her. Dann eine goldene Linie (Ziel) vom Pfeiler zu Zeus, [Z.ROD_W] Ticks, fest. Steht Zeus beim Treffer noch in
 *   der Linie (Breite [Z.ROD_LINE_WIDTH]): Entladung wie heute (40 Schaden, 90 offen, Pfeiler oxidiert). Sonst
 *   verpufft der Blitz (Pfeiler oxidiert, kein Fenster). Fehlt eine Bedingung, bleibt der Griff golden, die
 *   Statuszeile sagt warum.
 * - **Die Insel bricht:** Zu Beginn von Phase III die Schollen 0 und 3, bei [Z.SCHOLLE_SECOND_AT] der dritten Leiste
 *   die Schollen 1 und 4 ([breakFloes]). Ansage ROOM mit Erreichbarkeitsrechnung, rote Kreise über der Scholle,
 *   goldener Kern und goldene bleibende Schollen, Risse an den Fugen; dann `ArenaCollapse.start` je Teil. Der Pfeiler
 *   der Scholle (samt Griff) gehört ab der Ansage nicht mehr zum Kampf: Seine Ladung erlischt, er erdet und entlädt
 *   nicht mehr, und mit dem Bruch ist er weg (Entscheidung „Stab verbraucht“, Bericht P18).
 * - **Sturz:** Die Bö der Arena holt Fallende auf die nächste Fläche (4 Schaden). Als Rückfall setzt [tick] jeden, der
 *   tiefer als [Z.FALL_RESCUE_DEPTH] unter den Kern fällt, ohne Schaden dorthin (die Insel ist unter den Schollen hohl).
 *
 * Dazu die Hilfen für das Höhenband (Formen auf Kern, Terrasse und Bühne) und für verlorene Flächen, die
 * [ZeusEntity] bei jedem Angriff benutzt.
 */
object ZeusIsland {

    /** Geräteart des Erdungsgriffs (`HhaDecoBlocks.GROUNDING_ROD`). */
    const val DEVICE = "grounding_rod"

    /** Bild: Bogen vom Pfeilerkopf zu Zeus (`extra` = [ARC_CHARGE] beim Griff, [ARC_STRIKE] beim Treffer). */
    const val FX_ROD_ARC = "zeus.rod_arc"
    const val ARC_CHARGE = "charge"
    const val ARC_STRIKE = "strike"

    /** Bild: Staubkranz und Trümmer, wenn eine Scholle bricht. */
    const val FX_BREAK = "zeus.scholle_break"

    const val HINT_GRIP = "hha.zeus.hint.grip"
    const val HINT_BREAK = "hha.zeus.hint.break"
    const val STATUS_FAR = "hha.zeus.rod.far"
    const val STATUS_SIGHT = "hha.zeus.rod.sight"
    const val STATUS_COOLDOWN = "hha.zeus.rod.cooldown"
    const val STATUS_BUSY = "hha.zeus.rod.busy"
    const val STATUS_FIZZLE = "hha.zeus.rod.fizzle"
    const val LINE_BREAK_1 = "hha.zeus.line.break.1"
    const val LINE_BREAK_2 = "hha.zeus.line.break.2"
    const val LINE_GRIP = "hha.zeus.line.grip"

    /** Formen liegen so weit über dem Boden (wie `spot` im Kampf). */
    private const val LIFT = 0.05

    /** Abtasthöhe für „welche Fläche liegt hier“ (über allen Flächen). */
    private const val PROBE_Y = 10_000.0

    /** Der Sichtstrahl zum Pfeilerkopf beginnt so weit (waagerecht) neben der Pfeilerachse, außerhalb der Krone. */
    private const val SIGHT_START = 2.0

    /** Pfeilerkopf so hoch über der Oberkante des Pfeilers. */
    private const val HEAD_LIFT = 0.2

    /** Umwege um Pfeiler und Treppen bei der Erreichbarkeitsrechnung (wie im Kampf). */
    private const val DETOUR = 1.15

    /** Schritt beim Abtasten von Linien bzw. beim Kürzen über verlorenen Flächen. */
    private const val LINE_STEP = 0.5

    /** Halbe Breite des gebauten Pfeilers (3 × 3, PLAN 3.5) bzw. eines Kupferstabs (1 × 1, Test-Arena). */
    const val BIG_HALF = 1.5
    const val SMALL_HALF = 0.5

    /** Sturmwand: so viele Abtastpunkte der Lücke müssen auf vorhandener Fläche liegen. */
    const val GAP_SURFACE_MIN = 8

    fun init() {
        ArenaDevices.on(DEVICE) { world, pos, player ->
            val zeus = ArenaDevices.arenaAt(world, pos)?.owner as? ZeusEntity ?: return@on false
            zeus.island.grip(pos, player)
        }
    }

    // =================================================================================================================
    // Pfeiler
    // =================================================================================================================

    enum class RodState { FRESH, CHARGED, SPENT }

    /**
     * Ein Blitzableiter-Pfeiler. [foot] ist der unterste Kupferblock (auf F: y 234 auf der Terrasse, `pillar(i)`),
     * [index] die Pfeilernummer (= Scholle), [device] der Griff (nur auf F), [big] = gebauter 3 × 3-Pfeiler mit Sockel
     * (sonst ein Kupferstab 1 × 1). [temporary] = Leihgabe des Kampfs.
     */
    class Rod(val index: Int, val foot: BlockPos, val temporary: Boolean, val device: BlockPos?, val big: Boolean) {
        var state = RodState.FRESH
        var chargedUntil = 0L
        var nextRune = 0L

        /** Die Griff-Linie läuft gerade (nicht verglühen, nicht anders entladen). */
        var gripped = false

        /** Zuletzt durch einen Griff verbraucht: Griff auf Zustand 2, bis der Pfeiler erneuert wird. */
        var gripSpent = false

        /** Seine Scholle bricht: Er erdet und entlädt nicht mehr, der Griff ist dunkel. */
        var doomed = false

        /** Vorher dort stehende Blöcke (nur Leihgaben ohne Edit-Register, Test-Arenen): am Kampfende zurück. */
        val saved = ArrayList<Pair<BlockPos, BlockState>>()

        /** Halbe Breite des Sockels (Abstände zählen ab seinem Rand). */
        val half: Double get() = if (big) BIG_HALF else SMALL_HALF

        val center: Vec3d get() = Vec3d(foot.x + 0.5, foot.y.toDouble(), foot.z + 0.5)
        val tip: Vec3d get() = Vec3d(foot.x + 0.5, foot.y + OlympusLayout.PILLAR_HEIGHT + 1.0, foot.z + 0.5)

        /** Pfeilerkopf: über der Krone (gebauter Pfeiler) bzw. über dem Ableiter. */
        val head: Vec3d get() {
            val top = if (big) OlympusArenas.F_PILLAR_TOTAL else OlympusLayout.PILLAR_HEIGHT + 1
            return Vec3d(foot.x + 0.5, foot.y + top + HEAD_LIFT, foot.z + 0.5)
        }

        /** Geladen und frei verfügbar (kein Griff läuft, Scholle steht). */
        val charged: Boolean get() = state == RodState.CHARGED && !gripped && !doomed

        /** Waagerechter Abstand von [pos] zum Sockelrand (0 im Sockel). */
        fun socketDistance(pos: Vec3d): Double {
            val dx = max(abs(pos.x - center.x) - half, 0.0)
            val dz = max(abs(pos.z - center.z) - half, 0.0)
            return sqrt(dx * dx + dz * dz)
        }

        /** Gewünschter Zustand des Griffs (PLAN 4.1 „Arena-Gerät“: golden nur, wenn er jetzt etwas bewirkt). */
        fun deviceMode(): Int = when {
            doomed -> ArenaDevices.IDLE
            gripped -> ArenaDevices.SPENT
            state == RodState.CHARGED -> ArenaDevices.READY
            gripSpent && state == RodState.SPENT -> ArenaDevices.SPENT
            else -> ArenaDevices.IDLE
        }
    }

    // =================================================================================================================
    // Höhenband und verlorene Flächen (Hilfen für alle Angriffe)
    // =================================================================================================================

    /** Boden (Füße) der vorhandenen Vertragsfläche bei (x, z); `null` ohne Fläche dort (Gasse, Abgrund, verloren). */
    fun surfaceY(arena: Arenas.Arena, x: Double, z: Double): Double? = arena.floorAtOrNull(Vec3d(x, PROBE_Y, z))

    /** [pos] auf den Boden der Fläche darunter (für Bilder: Himmelsblitz, Bögen); ohne Fläche der Kern. */
    fun top(arena: Arenas.Arena?, pos: Vec3d): Vec3d {
        val spec = arena?.spec ?: return pos
        val y = surfaceY(arena, pos.x, pos.z) ?: spec.coreY.toDouble()
        return Vec3d(pos.x, y + LIFT, pos.z)
    }

    /** Liegt (x, z) auf einer verlorenen Fläche, auf der man nicht mehr stehen kann? */
    fun lostAt(arena: Arenas.Arena?, x: Double, z: Double): Boolean = arena != null && arena.isLost(x, z) && !arena.standable(x, z)

    /** Zielpunkt [pos] auf eine vorhandene Fläche ziehen (Bö-Ziel der Arena), Höhe bleibt. Ohne Verlust unverändert. */
    fun toSurface(arena: Arenas.Arena?, pos: Vec3d): Vec3d {
        if (arena == null || !lostAt(arena, pos.x, pos.z)) return pos
        val spot = arena.gustSpot(pos)
        return Vec3d(spot.x, pos.y, spot.z)
    }

    /** Punkt [pos] in den Kern ziehen (höchstens Kernradius − [margin] von der Mitte); ohne Vertrag unverändert. */
    fun clampToCore(spec: ArenaSpec?, pos: Vec3d, margin: Double): Vec3d {
        val core = spec?.core as? Disc ?: return pos
        val limit = (core.radius - margin).coerceAtLeast(0.0)
        val dx = pos.x - core.cx
        val dz = pos.z - core.cz
        val d = hypot(dx, dz)
        if (d <= limit || d < 1e-6) return pos
        return Vec3d(core.cx + dx * limit / d, pos.y, core.cz + dz * limit / d)
    }

    /** Linie [a]–[b] an beiden Enden kürzen, solange das Ende über verlorener Fläche liegt; `null`, wenn nichts bleibt. */
    fun trimLost(arena: Arenas.Arena?, a: Vec3d, b: Vec3d): Pair<Vec3d, Vec3d>? {
        if (arena == null || arena.lost.isEmpty()) return a to b
        val length = flat(a, b)
        if (length < 1e-6) return if (lostAt(arena, a.x, a.z)) null else a to b
        val steps = ceil(length / LINE_STEP).toInt()
        var i0 = 0
        while (i0 <= steps && lostAt(arena, lerp(a, b, i0.toDouble() / steps).x, lerp(a, b, i0.toDouble() / steps).z)) i0++
        var i1 = steps
        while (i1 >= i0 && lostAt(arena, lerp(a, b, i1.toDouble() / steps).x, lerp(a, b, i1.toDouble() / steps).z)) i1--
        if (i1 - i0 < 2) return null
        return lerp(a, b, i0.toDouble() / steps) to lerp(a, b, i1.toDouble() / steps)
    }

    /** So viele Abtastpunkte (Abstand 1) der Strecke [a]–[b] liegen auf einer vorhandenen Vertragsfläche. */
    fun surfaceSamples(arena: Arenas.Arena?, a: Vec3d, b: Vec3d): Int {
        if (arena?.spec == null) return Int.MAX_VALUE
        val steps = max(1, ceil(flat(a, b)).toInt())
        var n = 0
        for (i in 0..steps) {
            val p = lerp(a, b, i.toDouble() / steps)
            if (surfaceY(arena, p.x, p.z) != null) n++
        }
        return n
    }

    /**
     * Kreis (Ring mit [inner] > 0) über allen Flächen, die er schneidet: die Hauptform auf Kernhöhe (für Treffer und das
     * Bild auf dem Kern), dazu je erhöhter Fläche (Terrasse +2, Bühne +3) eine zweite Form derselben Gruppe auf deren
     * Höhe (der Client zeichnet flach). Ohne Vertrag nur die Form bei [center].
     */
    fun ring(arena: Arenas.Arena?, group: Telegraph.Fight.Group, center: Vec3d, radius: Float, ticks: Int, inner: Float = 0f, pace: Telegraph.Pace? = null): Telegraph.Ring {
        val spec = arena?.spec ?: return group.ring(center, radius, ticks, inner, pace)
        val core = spec.coreY + LIFT
        val main = group.ring(Vec3d(center.x, core, center.z), radius, ticks, inner, pace)
        val r = radius.toDouble()
        val floors = raised(arena, spec, center.x - r, center.x + r, center.z - r, center.z + r) { p ->
            Telegraph.inRing(p, center, r, inner.toDouble())
        }
        for (y in floors) group.ring(Vec3d(center.x, y + LIFT, center.z), radius, main.ticks, inner, pace)
        return main
    }

    /** Kegel über allen Flächen (wie [ring]). */
    fun cone(arena: Arenas.Arena?, group: Telegraph.Fight.Group, apex: Vec3d, dir: Vec3d, radius: Float, halfAngle: Float, ticks: Int, pace: Telegraph.Pace? = null): Telegraph.Cone {
        val spec = arena?.spec ?: return group.cone(apex, dir, radius, halfAngle, ticks, pace)
        val core = spec.coreY + LIFT
        val main = group.cone(Vec3d(apex.x, core, apex.z), dir, radius, halfAngle, ticks, pace)
        val r = radius.toDouble()
        val floors = raised(arena, spec, apex.x - r, apex.x + r, apex.z - r, apex.z + r) { p ->
            Telegraph.inCone(p, apex, main.direction, r, halfAngle.toDouble())
        }
        for (y in floors) group.cone(Vec3d(apex.x, y + LIFT, apex.z), main.direction, radius, halfAngle, main.ticks, pace)
        return main
    }

    /**
     * Linie über allen Flächen: die ganze Linie auf Kernhöhe (unter erhöhten Flächen verdeckt), dazu die Stücke über
     * Terrasse und Bühne auf deren Höhe. [safe] = goldene sichere Bahn (bzw. Ziel). Ohne Vertrag nur die Linie.
     */
    fun line(
        arena: Arenas.Arena?, group: Telegraph.Fight.Group, from: Vec3d, to: Vec3d, width: Float, ticks: Int,
        pace: Telegraph.Pace? = null, safe: Boolean = false,
    ): Telegraph.Line {
        fun draw(a: Vec3d, b: Vec3d, t: Int): Telegraph.Line = if (safe) group.safeLine(a, b, width, t) else group.line(a, b, width, t, pace)
        val spec = arena?.spec ?: return draw(from, to, ticks)
        val core = spec.coreY + LIFT
        val main = draw(Vec3d(from.x, core, from.z), Vec3d(to.x, core, to.z), ticks)
        val steps = max(1, ceil(flat(from, to) / LINE_STEP).toInt())
        var runStart = -1
        var runY = Double.NaN
        fun close(end: Int) {
            if (runStart < 0 || end - runStart < 1) return
            val a = lerp(from, to, runStart.toDouble() / steps)
            val b = lerp(from, to, end.toDouble() / steps)
            draw(Vec3d(a.x, runY + LIFT, a.z), Vec3d(b.x, runY + LIFT, b.z), main.ticks)
        }
        for (i in 0..steps) {
            val p = lerp(from, to, i.toDouble() / steps)
            val y = surfaceY(arena, p.x, p.z)?.takeIf { it > spec.coreY + 0.5 } ?: Double.NaN
            if (y.isNaN() || y != runY) {
                close(i - 1)
                runStart = if (y.isNaN()) -1 else i
                runY = y
            }
        }
        close(steps)
        return main
    }

    /** Erhöhte Böden (über dem Kern), auf denen ein Teil der Fläche [inside] liegt (Raster 1 im Kasten). */
    private fun raised(arena: Arenas.Arena, spec: ArenaSpec, minX: Double, maxX: Double, minZ: Double, maxZ: Double, inside: (Vec3d) -> Boolean): Set<Double> {
        val result = LinkedHashSet<Double>()
        val probe = Vec3d.ZERO
        var x = floor(minX) + 0.5
        while (x <= maxX) {
            var z = floor(minZ) + 0.5
            while (z <= maxZ) {
                val y = surfaceY(arena, x, z)
                if (y != null && y > spec.coreY + 0.5 && y !in result && inside(probe.add(x, 0.0, z))) result.add(y)
                z += 1.0
            }
            x += 1.0
        }
        return result
    }

    internal fun flat(a: Vec3d, b: Vec3d): Double = hypot(a.x - b.x, a.z - b.z)

    private fun lerp(a: Vec3d, b: Vec3d, t: Double): Vec3d = a.lerp(b, t)

    private fun flatDir(from: Vec3d, to: Vec3d): Vec3d {
        val v = Vec3d(to.x - from.x, 0.0, to.z - from.z)
        return if (v.lengthSquared() < 1e-9) Vec3d(1.0, 0.0, 0.0) else v.normalize()
    }

    private fun polar(spec: ArenaSpec, r: Double, deg: Double, y: Double): Vec3d {
        val c = spec.centerVec
        val a = Math.toRadians(deg)
        return Vec3d(c.x + r * cos(a), y, c.z + r * sin(a))
    }

    // =================================================================================================================
    // Je Kampf
    // =================================================================================================================

    /** Zustand der Signatur in einem Zeus-Kampf (ein Objekt je [ZeusEntity]). */
    class Fight(private val zeus: ZeusEntity) {

        /** Eine laufende Griff-Linie. */
        private class Grip(
            val rod: Rod, val player: UUID, val from: Vec3d, val to: Vec3d, val aim: Vec3d, val hitTick: Long,
            val group: Telegraph.Fight.Group, val previousGrip: Long,
        )

        private var grip: Grip? = null
        private var lastGrip = Long.MIN_VALUE / 2
        private var firstCharge = -1L
        private var gripHits = 0

        /** Bis zu diesem Welt-Tick läuft ein Bruch (Ansage oder Nachlauf); Sicherung gegen ein liegengebliebenes Flag. */
        private var breakUntil = 0L

        /** Schollen, deren Bruch schon begonnen hat. */
        private val started = HashSet<Int>()

        /** Eigene Ansage-Buchhaltung der Griff-Linien (Gold = Ziel, nicht Teil der Angriffe des Bosses). */
        private var rodCallsState: Telegraph.Fight? = null
        private val rodCalls: Telegraph.Fight
            get() = rodCallsState ?: Telegraph.open(world(), "zeus_rod", Vfx.Style.GOLD) { zeus.islandFighters() }.also { rodCallsState = it }

        private fun world(): ServerWorld = zeus.entityWorld as ServerWorld

        private fun now(): Long = world().time

        /** Läuft eine Griff-Linie? */
        val gripPending: Boolean get() = grip != null

        /** Bricht gerade eine Scholle (Ansage bis kurz nach dem Bruch)? Dann kein Griff, keine Entladung. */
        val breaking: Boolean get() = now() < breakUntil

        /** Ist der zweite Bruch fällig (Phase III, Leben auf [Z.SCHOLLE_SECOND_HEALTH])? */
        fun secondBreakDue(): Boolean {
            if (zeus.phase != 2 || OlympusArenas.F_FLOES_BREAK_SECOND.all { it in started }) return false
            if (zeus.islandArena()?.spec?.id != OlympusArenas.F.id) return false
            return zeus.health <= Z.SCHOLLE_SECOND_HEALTH * zeus.maxHealth + 0.01f
        }

        /** Schollen, die zu Beginn von Phase [newPhase] brechen werden (für die Erneuerung der Pfeiler im Wechsel). */
        fun breaksAt(newPhase: Int): List<Int> = if (newPhase == 2) OlympusArenas.F_FLOES_BREAK_FIRST else emptyList()

        // --- Griff -----------------------------------------------------------------------------------------------

        /**
         * Rechtsklick auf den goldenen Griff an [pos] (aus [ArenaDevices], nur Zustand 1 und Teilnehmer). `true` = die
         * Linie läuft (das Gerät geht auf 2); `false` = Bedingung fehlt, die Statuszeile sagt warum, golden bleibt er.
         */
        fun grip(pos: BlockPos, player: ServerPlayerEntity): Boolean = rodCalls.during {
            val rod = zeus.islandRods().firstOrNull { it.device == pos }
            when {
                rod == null || !rod.charged -> false
                !zeus.islandCanGround() || breaking || grip != null -> refuse(player, STATUS_BUSY)
                flat(rod.center, zeus.entityPos) > Z.ROD_REACH ->
                    refuse(player, STATUS_FAR, number(flat(rod.center, zeus.entityPos)), number(Z.ROD_REACH))
                !sees(rod) -> refuse(player, STATUS_SIGHT)
                now() < lastGrip + Z.ROD_COOLDOWN ->
                    refuse(player, STATUS_COOLDOWN, ceil((lastGrip + Z.ROD_COOLDOWN - now()) / 20.0).toInt().toString())
                else -> {
                    startGrip(rod, player)
                    true
                }
            }
        }

        private fun refuse(player: ServerPlayerEntity, key: String, vararg args: Any): Boolean {
            player.sendMessage(Text.translatable(key, *args).formatted(Formatting.GRAY, Formatting.ITALIC), true)
            return false
        }

        private fun number(v: Double): String = String.format(Locale.ROOT, "%.0f", v)

        /** Sichtlinie vom Pfeilerkopf zu Brust oder Kopf des Zeus (der Strahl beginnt neben der eigenen Krone). */
        private fun sees(rod: Rod): Boolean {
            val head = rod.head
            val targets = listOf(zeus.entityPos.add(0.0, zeus.height * 0.6, 0.0), zeus.entityPos.add(0.0, zeus.height * 0.9, 0.0))
            return targets.any { target ->
                val d = flat(head, target)
                val start = if (d <= SIGHT_START) head else head.lerp(target, SIGHT_START / d)
                !Cover.blocked(world(), start, target)
            }
        }

        /** Die Griff-Linie: golden vom Pfeiler zu Zeus (folgt ihm nicht), Bogen lädt am Pfeilerkopf auf. */
        private fun startGrip(rod: Rod, player: ServerPlayerEntity) {
            val arena = zeus.islandArena()
            val body = zeus.entityPos
            val dir = flatDir(rod.center, body)
            val from = Vec3d(rod.center.x, rod.foot.y + LIFT, rod.center.z)
            val end = body.add(dir.multiply(zeus.width * 0.5))
            val group = rodCalls.boss()
            val line = line(arena, group, from, Vec3d(end.x, body.y + LIFT, end.z), Z.ROD_LINE_WIDTH, Z.ROD_W, safe = true)
            val aim = Vec3d(body.x, body.y + zeus.height * 0.6, body.z)
            BossFx.play(world(), FX_ROD_ARC, Vfx.Style.GOLD, rod.head, aim, a = line.ticks.toFloat(), life = line.ticks, extra = ARC_CHARGE)
            zeus.islandSound(HhaSounds.CHARGE, rod.head, 2.2f, 1.5f)
            grip = Grip(rod, player.uuid, line.from, line.to, aim, line.hitTick, group, lastGrip)
            rod.gripped = true
            lastGrip = now()
            syncDevice(rod)
        }

        /** Treffer der Griff-Linie: steht Zeus noch darin → Entladung wie heute; sonst verpufft der Blitz. */
        private fun resolveGrip() {
            val g = grip ?: return
            if (now() < g.hitTick) return
            grip = null
            g.group.end()
            val rod = g.rod
            rod.gripped = false
            if (rod !in zeus.islandRods() || rod.state != RodState.CHARGED || rod.doomed) return
            if (!zeus.islandCanGround() || breaking) {
                // Szene, Waage oder Bruch kam dazwischen: Der Griff fällt aus, der Pfeiler bleibt geladen, der Abstand zählt nicht.
                lastGrip = g.previousGrip
                syncDevice(rod)
                return
            }
            rod.gripSpent = true
            val hit = Telegraph.inLine(zeus.entityPos, g.from, g.to, Z.ROD_LINE_WIDTH.toDouble(), zeus.width * 0.5)
            val target = if (hit) zeus.entityPos.add(0.0, zeus.height * 0.6, 0.0) else g.aim
            rodCalls.during {
                BossFx.play(
                    world(), FX_ROD_ARC, Vfx.Style.THUNDER, rod.head, target, a = 0f, life = Z.ROD_ARC_AFTER, extra = ARC_STRIKE,
                    entity2 = if (hit) zeus else null,
                )
            }
            if (hit) {
                if (gripHits++ == 0) Cine.dialog(zeus.islandViewers(), Cine.SPEAKER_ZEUS, LINE_GRIP, 44)
                zeus.islandDischarge(rod)
            } else {
                zeus.islandOxidize(rod)
                zeus.islandSound(HhaSounds.THUNDER, g.aim, 2f, 1.6f)
                (world().getPlayerByUuid(g.player) as? ServerPlayerEntity)?.let { refuse(it, STATUS_FIZZLE) }
            }
            syncDevice(rod)
        }

        // --- Tick ------------------------------------------------------------------------------------------------

        /** Jeden Tick aus `ZeusEntity.tickBody` (auch in Szenen): Griff, Geräte, Hinweis, Sturz-Rückfall. */
        fun tick() {
            resolveGrip()
            // Fiel eine Scholle anders weg (z. B. `/hha arena part floe_i`), gehört ihr Pfeiler auch nicht mehr zum Kampf.
            zeus.islandArena()?.let { arena ->
                val gone = zeus.islandRods().filter { rod -> rod.device != null && arena.lost.any { it.id == "floe_${rod.index}" } }
                if (gone.isNotEmpty()) zeus.islandRemoveRods(gone.map { it.index })
            }
            val rods = zeus.islandRods()
            for (rod in rods) syncDevice(rod)
            if (firstCharge < 0L && rods.any { it.charged }) firstCharge = now()
            if (firstCharge >= 0L && now() >= firstCharge + Z.ROD_GRIP_HINT_DELAY && rods.any { it.device != null }) zeus.islandHint(HINT_GRIP)
            rescueFallers()
        }

        /** Griff auf den Zustand des Pfeilers (über `ArenaEdits`, damit das Kampfende ihn auf den Bauplan setzt). */
        private fun syncDevice(rod: Rod) {
            val pos = rod.device ?: return
            val world = world()
            val want = rod.deviceMode()
            if (ArenaDevices.mode(world, pos) != want) ArenaDevices.setMode(world, pos, want)
        }

        /** Rückfall unter der Bö: Wer tief unter den Kern fällt (hohle Insel), kommt ohne Schaden auf die nächste Fläche. */
        private fun rescueFallers() {
            val arena = zeus.islandArena() ?: return
            val spec = arena.spec ?: return
            if (arena.lost.isEmpty() || !arena.rules.gust) return
            val limit = spec.coreY - Z.FALL_RESCUE_DEPTH
            for (player in arena.players()) {
                if (player.isSpectator || player.y >= limit) continue
                val spot = arena.gustSpot(player.entityPos)
                player.fallDistance = 0.0
                player.teleport(world(), spot.x, spot.y, spot.z, emptySet<PositionFlag>(), player.yaw, player.pitch, false)
                player.velocity = Vec3d.ZERO
                player.velocityDirty = true
                player.sendMessage(Text.translatable("hha.boss.arena.gust").formatted(Formatting.GOLD), true)
            }
        }

        /** Ausgänge des Kampfs: offene Linie vergessen; Geräte stellt `Arenas.close` über `ArenaEdits` zurück. */
        fun close() {
            grip?.group?.end()
            grip = null
            breakUntil = 0L
            val spec = zeus.islandArena()?.spec ?: return
            // Nur falls kein Register mehr offen ist (Arena schon zu): direkt auf 0, sonst macht es die Wiederherstellung.
            if (zeus.islandArena()?.edits == null) ArenaDevices.reset(world(), spec)
        }

        // --- Die Insel bricht ------------------------------------------------------------------------------------

        /**
         * Szene `zeus.phase3`: Risse an den Fugen der Schollen, die gleich brechen, und ein paar Trümmer (nur Bild).
         * [life] = so lange stehen die Risse (bis in die Ansage hinein).
         */
        fun omen(indices: List<Int>, life: Int) {
            val spec = zeus.islandArena()?.spec ?: return
            if (spec.id != OlympusArenas.F.id) return
            for (i in indices) {
                jointCracks(spec, i, life, (life * 0.6f).toInt())
                if (i == indices.first()) {
                    val (from, to) = OlympusArenas.F_FLOES[i]
                    BossFx.debris(world(), polar(spec, Z.SCHOLLE_MARK_RING, (from + to) / 2.0, floeY(spec)), 4f, 5, up = 0.35f, out = 0.2f)
                }
            }
            zeus.islandSound(HhaSounds.ARENA_CRUMBLE, spec.centerVec, 3f, 0.6f)
        }

        /**
         * Die Schollen [indices] brechen (ROOM-Ansage, dann Abbau über `ArenaCollapse`). Läuft im Drehbuch des Bosses
         * als Höhepunkt; nie in eine laufende Boss-Ansage (wartet höchstens [Z.SCHOLLE_WAIT_MAX]); Zeus tritt vorher in
         * den Kern. Ohne Vertrag F (Test-Arena) geschieht nichts.
         */
        fun breakFloes(indices: List<Int>): Sequence<Unit> = sequence {
            val arena = zeus.islandArena() ?: return@sequence
            val spec = arena.spec ?: return@sequence
            if (spec.id != OlympusArenas.F.id) return@sequence
            val todo = indices.filter { it !in started && arena.lost.none { p -> p.id == "floe_$it" } }
            if (todo.isEmpty()) return@sequence
            started.addAll(todo)
            breakUntil = now() + Z.SCHOLLE_WAIT_MAX + BossTimings.Min.ESCAPE_MAX + Z.SCHOLLE_AFTER + 60
            // 1. Keine laufende Ansage (Ringe, Luftblitz, Griff-Linie).
            var waited = 0
            while (waited++ < Z.SCHOLLE_WAIT_MAX && (zeus.islandBusy() || grip != null)) yield(Unit)
            if (!zeus.islandFighting()) {
                breakUntil = 0L
                return@sequence
            }
            yieldAll(zeus.islandToCore())
            // 2. Die Pfeiler der Scholle gehören ab jetzt nicht mehr zum Kampf (Ladung erlischt, Griff dunkel).
            for (rod in zeus.islandRods()) if (rod.index in todo) {
                rod.doomed = true
                syncDevice(rod)
            }
            val parts = todo.map { spec.part("floe_$it") }
            val windup = windupFor(arena, parts)
            val group = zeus.islandTelegraph().boss(pace = Telegraph.Pace.ROOM)
            val y = floeY(spec)
            for (i in todo) {
                val (from, to) = OlympusArenas.F_FLOES[i]
                var a = from + Z.SCHOLLE_MARK_STEP / 2.0
                while (a <= to - Z.SCHOLLE_MARK_STEP / 2.0 + 1e-6) {
                    group.ring(polar(spec, Z.SCHOLLE_MARK_RING, a, y), Z.SCHOLLE_MARK_RADIUS, windup)
                    a += Z.SCHOLLE_MARK_STEP
                }
            }
            // Gold = sicher: der Kern und jede Scholle, die stehen bleibt.
            val core = spec.core as? Disc
            group.safe(Vec3d(spec.centerVec.x, spec.coreY + LIFT, spec.centerVec.z), (core?.radius ?: OlympusArenas.F_CORE).toFloat(), windup)
            for ((j, floe) in OlympusArenas.F_FLOES.withIndex()) {
                if (j in started || arena.lost.any { it.id == "floe_$j" }) continue
                group.safe(polar(spec, Z.SCHOLLE_MARK_RING, (floe.first + floe.second) / 2.0, y), Z.SCHOLLE_SAFE_RADIUS, windup)
            }
            zeus.islandHint(HINT_BREAK)
            Cine.dialog(zeus.islandViewers(), Cine.SPEAKER_ZEUS, if (todo.any { it in OlympusArenas.F_FLOES_BREAK_FIRST }) LINE_BREAK_1 else LINE_BREAK_2, 60)
            zeus.islandSound(HhaSounds.ARENA_CRUMBLE, spec.centerVec, 3.5f, 0.7f)
            val ticks = group.ticks
            for (i in todo) jointCracks(spec, i, ticks + Z.SCHOLLE_AFTER, (ticks * 0.8f).toInt())
            for (t in 0 until ticks) {
                if (!zeus.islandFighting()) {
                    group.end()
                    breakUntil = 0L
                    return@sequence
                }
                if (t == ticks - Z.SCHOLLE_SLAM_LEAD) zeus.islandAct(ZeusAction.SLAM)
                yield(Unit)
            }
            // 3. Bruch: Bö und Beitritt kennen die Fläche ab sofort nicht mehr (`lose` vor dem ersten Block).
            for ((n, i) in todo.withIndex()) {
                val (from, to) = OlympusArenas.F_FLOES[i]
                BossFx.play(
                    world(), FX_BREAK, Vfx.Style.THUNDER, Vec3d(spec.centerVec.x, y, spec.centerVec.z), Vec3d(from, to, 0.0),
                    a = OlympusArenas.F_TERRACE_INNER.toFloat(), b = (OlympusArenas.F_TERRACE_OUTER + 0.5).toFloat(), life = BREAK_FX_LIFE,
                )
                ArenaCollapse.start(arena, parts[n], Z.SCHOLLE_PER_TICK)
            }
            zeus.islandRemoveRods(todo)
            BossFx.kick(world(), spec.centerVec, BREAK_KICK, ticks = BREAK_KICK_TICKS, radius = BREAK_KICK_RADIUS)
            zeus.islandSound(HhaSounds.THUNDER, spec.centerVec, 3.5f, 0.55f)
            group.end()
            breakUntil = now() + Z.SCHOLLE_AFTER
            for (t in 0 until Z.SCHOLLE_AFTER) yield(Unit)
            zeus.islandAct(0)
            breakUntil = 0L
        }

        /** Ansage-Dauer: mindestens [Z.SCHOLLE_W], länger, wenn jemand auf der Scholle weiter vom Kern steht (Reaktion + Weg). */
        private fun windupFor(arena: Arenas.Arena, parts: List<Part>): Int {
            val spec = arena.spec ?: return Z.SCHOLLE_W
            val core = (spec.core as? Disc)?.radius ?: OlympusArenas.F_CORE
            var need = Z.SCHOLLE_W
            for (player in zeus.islandFighters()) {
                if (parts.none { it.zone.contains(player.x, player.z) }) continue
                val walk = (flat(player.entityPos, spec.centerVec) - core + 0.6).coerceAtLeast(0.0) * DETOUR
                need = max(need, BossTimings.Min.REACTION + ceil(walk / BossTimings.Min.ESCAPE_SPEED).toInt())
            }
            return min(need, BossTimings.Min.ESCAPE_MAX)
        }

        /** Risse an den beiden Fugen der Scholle [i] (Kern → außen), wachsen über [grow] Ticks. */
        private fun jointCracks(spec: ArenaSpec, i: Int, life: Int, grow: Int) {
            val (from, to) = OlympusArenas.F_FLOES[i]
            val y = floeY(spec)
            val length = (OlympusArenas.F_TERRACE_OUTER - OlympusArenas.F_TERRACE_INNER).toFloat()
            for (a in listOf(from, to)) {
                val start = polar(spec, OlympusArenas.F_TERRACE_INNER, a, y)
                val out = flatDir(spec.centerVec, start)
                BossFx.crack(world(), Vfx.Style.THUNDER, start, out, length, life = life, grow = grow.coerceAtLeast(1))
            }
        }

        private fun floeY(spec: ArenaSpec): Double = spec.coreY + OlympusArenas.F_TERRACE_HEIGHT + LIFT
    }

    /** Lebensdauer des Bruch-Bilds (Staub sinkt mit den Blöcken). */
    private const val BREAK_FX_LIFE = 70

    /** Ein Ruck beim Bruch statt Rütteln. */
    private const val BREAK_KICK = 2.2f
    private const val BREAK_KICK_TICKS = 7
    private const val BREAK_KICK_RADIUS = 48f
}
