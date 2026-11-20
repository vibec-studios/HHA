package dev.henny.hha.logic.boss.sig

import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.logic.boss.HermesFight
import dev.henny.hha.logic.boss.HermesFights
import dev.henny.hha.logic.boss.arena.ArenaDevices
import dev.henny.hha.logic.boss.timings.HermesSigTimings
import dev.henny.hha.world.arena.ArenaGeo
import dev.henny.hha.world.olymp.OlympusArenas
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Signatur Hermes (Überarbeitung 4, PLAN 4.2; Besitzer P14): **Botenglocken und brechende Säulen.**
 *
 * - **Glocken:** Mit der ersten Ansage des Botenlaufs (`HermesAttacks.messengerRun`) werden zwei Glocken golden: je
 *   Seite der dritten Linie die der nächsten stehenden Säule ([State.pickBells]). Schlägt die Gruppe beide (solo eine)
 *   vor dem Treffer der dritten Linie ([State.complete]), läuft statt des dritten Stoßes ein Windstoß ([gust]) die Linie
 *   entlang und wirft Hermes aus der Bahn (`STUMBLE` 40 offen). Danach Zustand 2 bis zum Ende des Angriffs, dann 0.
 * - **Säule bricht:** Führt ein Kurierstoß oder eine Linie des Botenlaufs durch eine stehende Säule
 *   ([State.columnOnLine]), endet die Bahn an ihr; Hermes kracht hinein, die Säule bricht bis auf den Stumpf
 *   ([State.crash]) und er taumelt (`STUMBLE` 40 offen). Je Säule einmal je Stage; ihre Glocke ist bis zum Stage-Beginn
 *   stumm.
 *
 * Bedienung der Glocke: `ArenaDevices` (P05). Seit P24 leitet `ArenaDevices.interact` jede Glocke im Forum eines
 * laufenden Kampfs über den hier angemeldeten Handler, nur für Teilnehmer (Wunsch `requests/P14.md` 1). [State.poll]
 * bleibt als zweiter Weg (erkennt Zustand 1 → 2, z. B. über `/hha device`), ohne doppelt zu zählen.
 */
object HermesBells {

    const val BELL_KIND = "messenger_bell"

    /** Bild des Windstoßes (Client `HermesFx`): pos = Anfang (Glockenseite), dir = Ende (Hermes), a = Breite, b = Laufzeit. */
    const val GUST = "hermes.gust"

    /** Halbe Breite, mit der Hermes in eine Säule stößt (sein Körper), und so weit hält die Bahn vor ihr. */
    const val BODY_HALF_WIDTH = 0.5
    const val CRASH_BACKOFF = 0.25

    /** Die dritte Linie liegt fest: Sehne zwischen zwei Lückenwinkeln (0°, 45°, …) auf diesem Ring (innerhalb der Glocken-Säulen-Reihe). */
    const val THIRD_RING = 19.0

    /** Breite des Windstoß-Bildes und Weite, um die er Hermes zur Seite wirft. */
    const val GUST_WIDTH = 1.6f
    const val GUST_PUSH = 2.5

    /** Hinweisschlüssel (einmal je Kampf). */
    const val HINT_BELLS = "hha.hermes.hint.bells"
    const val HINT_COLUMN = "hha.hermes.hint.column"
    const val HINT_GALLERY = "hha.hermes.hint.gallery"

    fun init() {
        ArenaDevices.on(BELL_KIND) { world, pos, player -> HermesFights.of(world)?.bells?.onStruck(pos, player) ?: false }
    }

    /** Ergebnis von [State.columnOnLine]: getroffene Säule und Endpunkt der Bahn davor. */
    class Crash(val column: Int, val end: Vec3d)

    /** Zustand je Kampf: goldene und geschlagene Glocken, stumme Glocken (gebrochene Säulen), gezeigte Hinweise. */
    class State(private val fight: HermesFight) {
        private val golden = LinkedHashSet<Int>()
        private val struck = LinkedHashSet<Int>()
        private val muted = LinkedHashSet<Int>()
        private val hinted = HashSet<String>()
        private var need = 0

        private val arena get() = fight.arena
        private val world get() = fight.world

        fun bellPos(k: Int): BlockPos = arena.spec.devices[k].pos

        /** Einmal je Kampf ein goldener Hinweis in der Statuszeile. */
        fun hintOnce(key: String) {
            if (hinted.add(key)) fight.hint(key)
        }

        /** Gibt es gerade goldene Glocken? */
        val active: Boolean get() = golden.isNotEmpty()

        /**
         * Macht [bells] golden; nötig sind alle, solo genügt eine (PLAN 4.2). Glocken stummer (gebrochener) Säulen
         * fallen weg.
         */
        fun light(bells: List<Int>) {
            clear()
            val usable = bells.filter { it !in muted && arena.standing(it) }
            if (usable.isEmpty()) return
            golden.addAll(usable)
            need = if (fight.targets().size <= 1) 1 else usable.size
            for (k in usable) ArenaDevices.setMode(world, bellPos(k), ArenaDevices.READY)
            hintOnce(HINT_BELLS)
        }

        /** Sieht nach, welche goldenen Glocken geschlagen wurden (Zustand 1 → 2). @return [complete]. */
        fun poll(): Boolean {
            for (k in golden) {
                if (k in struck) continue
                if (ArenaDevices.mode(world, bellPos(k)) == ArenaDevices.SPENT) markStruck(k)
            }
            return complete()
        }

        /** Genug Glocken geschlagen (Gruppe beide, solo eine)? */
        fun complete(): Boolean = golden.isNotEmpty() && struck.size >= need

        private fun markStruck(k: Int) {
            if (!struck.add(k)) return
            val at = Vec3d.ofCenter(bellPos(k)).add(0.0, 0.6, 0.0)
            fight.scoped { Vfx.flare(world, Vfx.Style.GOLD, at, BELL_FLARE, BELL_FLARE_LIFE) }
        }

        /** Handler aus [ArenaDevices] (Forum mit laufendem Kampf, P24): wirkt nur an goldenen, noch offenen Glocken. */
        fun onStruck(pos: BlockPos, player: ServerPlayerEntity): Boolean {
            if (!fight.isParticipant(player)) return false
            val k = (0 until OlympusArenas.FORUM_COLUMNS).firstOrNull { bellPos(it) == pos } ?: return false
            if (k !in golden || k in struck) return false
            markStruck(k)
            return true
        }

        /** Alle goldenen Glocken auf Zustand 2 (nach der dritten Linie bis zum Ende des Angriffs). */
        fun spendAll() {
            for (k in golden) if (ArenaDevices.mode(world, bellPos(k)) == ArenaDevices.READY) ArenaDevices.setMode(world, bellPos(k), ArenaDevices.SPENT)
        }

        /** Ende des Angriffs: goldene/geschlagene Glocken zurück auf 0. */
        fun clear() {
            for (k in golden) ArenaDevices.setMode(world, bellPos(k), ArenaDevices.IDLE)
            golden.clear()
            struck.clear()
            need = 0
        }

        /** Stage-Wechsel, Abbruch, Kampfende: wie [clear]; die Säulen kommen über `HermesArena.restoreColumns`. */
        fun reset() = clear()

        /** Neue Stage: Die Säulen stehen wieder, keine Glocke ist mehr stumm. */
        fun onStageStart() {
            muted.clear()
        }

        // --- Geometrie -------------------------------------------------------------------------------------------

        /**
         * Die dritte Linie: Sehne zwischen zwei Lückenwinkeln auf Ring [THIRD_RING] (zwischen den Säulen, keine Säule
         * im Weg), gegenüber oder um 45° versetzt. Höhe = Kernboden.
         */
        fun planThird(): Pair<Vec3d, Vec3d> {
            val a = world.random.nextInt(GAPS)
            val b = (a + GAPS / 2 + world.random.nextInt(3) - 1 + GAPS) % GAPS
            fun point(i: Int) = ArenaGeo.polarVec(arena.center, THIRD_RING, i * 360.0 / GAPS, arena.floorY)
            return point(a) to point(b)
        }

        /**
         * Die zwei Glocken zur Linie [from] → [to]: je Seite die der Säule mit dem kleinsten Abstand zur Linie (nur
         * stehende Säulen mit nicht stummer Glocke). Liegt auf einer Seite keine, bleibt es bei einer.
         */
        fun pickBells(from: Vec3d, to: Vec3d): List<Int> {
            var left: Pair<Int, Double>? = null
            var right: Pair<Int, Double>? = null
            val dx = to.x - from.x
            val dz = to.z - from.z
            for (k in 0 until OlympusArenas.FORUM_COLUMNS) {
                if (k in muted || !arena.standing(k)) continue
                val c = arena.spec.cover[k].center
                val side = dx * (c.z - from.z) - dz * (c.x - from.x)
                val d = segmentDistance(c.x, c.z, from, to)
                if (side >= 0.0) {
                    if (left == null || d < left.second) left = k to d
                } else {
                    if (right == null || d < right.second) right = k to d
                }
            }
            return listOfNotNull(left?.first, right?.first)
        }

        /**
         * Führt die Bahn [from] → [to] (waagerecht, mit Hermes' halber Breite) durch eine stehende Säule? Dann die
         * erste getroffene Säule und der Punkt kurz davor (auf der Höhe von [from]); sonst `null`.
         */
        fun columnOnLine(from: Vec3d, to: Vec3d): Crash? {
            var best: Crash? = null
            var bestT = Double.MAX_VALUE
            for (k in 0 until OlympusArenas.FORUM_COLUMNS) {
                if (!arena.standing(k)) continue
                val b = arena.spec.cover[k].box
                val t = enterT(from, to, b.minX - BODY_HALF_WIDTH, b.minZ - BODY_HALF_WIDTH, b.maxX + 1 + BODY_HALF_WIDTH, b.maxZ + 1 + BODY_HALF_WIDTH) ?: continue
                if (t < bestT) {
                    bestT = t
                    val way = Vec3d(to.x - from.x, 0.0, to.z - from.z)
                    val len = way.length()
                    val back = if (len > 1e-6) CRASH_BACKOFF / len else 0.0
                    val s = max(0.0, t - back)
                    best = Crash(k, Vec3d(from.x + way.x * s, from.y, from.z + way.z * s))
                }
            }
            return best
        }

        /** Hermes ist in die Säule [k] gekracht: Säule bricht (Stumpf), ihre Glocke ist bis zum Stage-Beginn stumm. */
        fun crash(k: Int): Boolean {
            if (!arena.breakColumn(k)) return false
            muted.add(k)
            if (k in golden) {
                golden.remove(k)
                struck.remove(k)
                need = minOf(need, golden.size)
                ArenaDevices.setMode(world, bellPos(k), ArenaDevices.IDLE)
            }
            hintOnce(HINT_COLUMN)
            return true
        }

        /** Windstoß: läuft in [ticks] von [from] (Glockenseite) nach [to] (Hermes), golden (die Antwort der Spieler). */
        fun gust(from: Vec3d, to: Vec3d, ticks: Int = HermesSigTimings.GUST_TICKS) {
            fight.scoped {
                BossFx.play(
                    world, GUST, Vfx.Style.GOLD, from.add(0.0, GUST_LIFT, 0.0), to.add(0.0, GUST_LIFT, 0.0),
                    a = GUST_WIDTH, b = ticks.toFloat(), life = ticks + GUST_FADE,
                )
            }
        }

        // --- intern ----------------------------------------------------------------------------------------------

        /** Erster Anteil t ∈ [0, 1] der Strecke im Rechteck; `null`, wenn sie es nicht berührt oder darin beginnt. */
        private fun enterT(from: Vec3d, to: Vec3d, minX: Double, minZ: Double, maxX: Double, maxZ: Double): Double? {
            var t0 = 0.0
            var t1 = 1.0
            val dx = to.x - from.x
            val dz = to.z - from.z
            fun clip(p: Double, q: Double): Boolean {
                if (abs(p) < 1e-9) return q >= 0.0
                val r = q / p
                if (p < 0) {
                    if (r > t1) return false
                    if (r > t0) t0 = r
                } else {
                    if (r < t0) return false
                    if (r < t1) t1 = r
                }
                return true
            }
            if (!clip(-dx, from.x - minX) || !clip(dx, maxX - from.x) || !clip(-dz, from.z - minZ) || !clip(dz, maxZ - from.z)) return null
            if (t0 <= 0.0) return null
            return t0
        }

        private fun segmentDistance(px: Double, pz: Double, a: Vec3d, b: Vec3d): Double {
            val dx = b.x - a.x
            val dz = b.z - a.z
            val len2 = dx * dx + dz * dz
            val t = if (len2 < 1e-9) 0.0 else ((px - a.x) * dx + (pz - a.z) * dz) / len2
            val s = min(1.0, max(0.0, t))
            return kotlin.math.hypot(px - (a.x + dx * s), pz - (a.z + dz * s))
        }

        private companion object {
            /** Lückenwinkel 0°, 45°, … zwischen den acht Säulen. */
            const val GAPS = 8
            const val BELL_FLARE = 1.4f
            const val BELL_FLARE_LIFE = 10
            const val GUST_LIFT = 1.0
            const val GUST_FADE = 10
        }
    }
}
