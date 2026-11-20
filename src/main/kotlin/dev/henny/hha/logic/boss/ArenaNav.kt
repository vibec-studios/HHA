package dev.henny.hha.logic.boss

import dev.henny.hha.world.arena.ArenaSpec
import dev.henny.hha.world.arena.CoverSpot
import dev.henny.hha.world.arena.Disc
import dev.henny.hha.world.arena.Sector
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Wegfindung um Deckung herum (Überarbeitung 4, PLAN 7.4; Besitzer P03): `GuardianEntity.walkTo` und `approach` fragen
 * [route], wenn eine Arena mit `spec` gesetzt ist. Die Vanilla-Pfadsuche findet um kleine Hindernisse selbst herum;
 * [route] gibt ihr bei großen Körpern (Säulen 3 × 3, Hekataion, Titan) einen Eckpunkt vor, damit der Boss nicht an der
 * Deckung entlangschabt, und hält ihn auf der Kernfläche (Bosse betreten keine Ebenen, PLAN 0).
 *
 * Alles rein waagerecht und ohne Welt: Ergebnis hängt nur von `from`, `to` und dem Vertrag ab (prüfbar im Selbsttest).
 */
object ArenaNav {

    /** Deckungskasten für die Wegfindung so weit geweitet (halbe Bossbreite plus Luft). */
    const val COVER_MARGIN = 1.2

    /** Eckpunkte liegen so weit außerhalb des geweiteten Kastens (sonst gälte die Ecke selbst als „im Kasten“). */
    const val CORNER_OUT = 0.3

    /** Eine Ecke gilt als erreicht, wenn er waagerecht näher ist als so viel. */
    const val CORNER_REACHED = 0.75

    /** Schrittweite, mit der die Strecke auf Ebenen-Kanten abgetastet wird. */
    const val SAMPLE_STEP = 0.5

    /** Ziele auf Ebenen werden auf einen Kernpunkt gezogen, der so weit innerhalb des Kerns liegt. */
    const val CORE_INSET = 1

    /**
     * Nächster Zwischenpunkt auf dem Weg von [from] nach [to] (Höhe: Kernboden).
     * 1. Liegt [to] auf einer Ebene (nicht auf dem Kern), wird es auf den nächsten Kernpunkt gezogen.
     * 2. Schneidet die Strecke den um [COVER_MARGIN] geweiteten Kasten einer Deckung, kommt der Eckpunkt zurück, um den
     *    der kürzere Umweg führt (der zuerst geschnittene Kasten zählt).
     * 3. Verlässt die Strecke den Kern über eine Ebene, kommt der Kernpunkt neben der ersten Stelle zurück.
     * Sonst das (gezogene) Ziel.
     */
    fun route(from: Vec3d, to: Vec3d, spec: ArenaSpec): Vec3d {
        val y = spec.coreY.toDouble()
        val goal = onCore(to, spec)
        coverDetour(from, goal, spec.cover)?.let { return Vec3d(it.x, y, it.z) }
        levelDetour(from, goal, spec)?.let { return Vec3d(it.x, y, it.z) }
        return Vec3d(goal.x, y, goal.z)
    }

    /**
     * Liegt [pos] auf einer Ebene (und nicht auf dem Kern), kommt der nächste Kernpunkt ([CORE_INSET] vom Rand) zurück;
     * sonst [pos] unverändert. Ziele außerhalb aller Flächen (Hang des Sisyphos, Innenbecken des Tantalos) bleiben, wie
     * sie sind: Dort führt der Kampf den Wächter selbst hin.
     */
    fun onCore(pos: Vec3d, spec: ArenaSpec): Vec3d {
        if (spec.core.contains(pos.x, pos.z)) return pos
        if (spec.levels.none { it.zone.contains(pos.x, pos.z) }) return pos
        return nearestCore(pos, spec) ?: pos
    }

    /** Nächster Kernpunkt (Zellmitte, [CORE_INSET] vom Rand, nicht in Deckung) zu [pos]; `null`, wenn der Kern leer ist. */
    fun nearestCore(pos: Vec3d, spec: ArenaSpec): Vec3d? {
        val cells = coreCells(spec)
        var best: Long? = null
        var bestD = Double.MAX_VALUE
        for (packed in cells) {
            val dx = Arenas.unpackX(packed) + 0.5 - pos.x
            val dz = Arenas.unpackZ(packed) + 0.5 - pos.z
            val d = dx * dx + dz * dz
            if (d < bestD) {
                bestD = d
                best = packed
            }
        }
        return best?.let { Vec3d(Arenas.unpackX(it) + 0.5, pos.y, Arenas.unpackZ(it) + 0.5) }
    }

    /** Kernzellen mit [CORE_INSET] Abstand zum Rand und außerhalb geweiteter Deckung (je Vertrag zwischengespeichert). */
    private fun coreCells(spec: ArenaSpec): List<Long> = coreCache.getOrPut(spec) {
        val boxes = spec.cover.map { inflate(it.box, COVER_MARGIN) }
        val inner = ArrayList<Long>()
        val any = ArrayList<Long>()
        for ((x, z) in spec.core.cells()) {
            val cx = x + 0.5
            val cz = z + 0.5
            if (boxes.any { it.inside(cx, cz) }) continue
            any.add(Arenas.pack(x, z))
            var ok = true
            for (dx in -CORE_INSET..CORE_INSET) for (dz in -CORE_INSET..CORE_INSET) if (!spec.core.containsCell(x + dx, z + dz)) ok = false
            if (ok) inner.add(Arenas.pack(x, z))
        }
        inner.ifEmpty { any }
    }

    private val coreCache = java.util.WeakHashMap<ArenaSpec, List<Long>>()

    // --- Deckung ----------------------------------------------------------------------------------------------------

    /** Waagerechter Kasten (x, z). */
    class Rect2(val minX: Double, val minZ: Double, val maxX: Double, val maxZ: Double) {
        fun inside(x: Double, z: Double): Boolean = x > minX && x < maxX && z > minZ && z < maxZ

        /** Eintritts-Parameter t ∈ [0, 1] der Strecke a → b in den Kasten (Slab-Verfahren), sonst `null`. */
        fun enter(a: Vec3d, b: Vec3d): Double? {
            var t0 = 0.0
            var t1 = 1.0
            val dx = b.x - a.x
            val dz = b.z - a.z
            for ((p, d, lo, hi) in listOf(Quad(a.x, dx, minX, maxX), Quad(a.z, dz, minZ, maxZ))) {
                if (abs(d) < 1e-9) {
                    if (p <= lo || p >= hi) return null
                } else {
                    var ta = (lo - p) / d
                    var tb = (hi - p) / d
                    if (ta > tb) ta = tb.also { tb = ta }
                    t0 = max(t0, ta)
                    t1 = min(t1, tb)
                    if (t0 >= t1) return null
                }
            }
            return t0
        }

        fun corners(out: Double): List<Vec3d> = listOf(
            Vec3d(minX - out, 0.0, minZ - out), Vec3d(maxX + out, 0.0, minZ - out),
            Vec3d(maxX + out, 0.0, maxZ + out), Vec3d(minX - out, 0.0, maxZ + out),
        )
    }

    private data class Quad(val p: Double, val d: Double, val lo: Double, val hi: Double)

    fun inflate(box: BlockBox, margin: Double): Rect2 =
        Rect2(box.minX - margin, box.minZ - margin, box.maxX + 1.0 + margin, box.maxZ + 1.0 + margin)

    /**
     * Eckpunkt um die erste Deckung auf der Strecke [from] → [to], oder `null` (frei, oder [from]/[to] liegt selbst im
     * geweiteten Kasten: dann hilft kein Umweg, die Pfadsuche löst das). Wahl: Ecke, die von [from] frei zu sehen ist,
     * mit dem kürzesten Weg `from → Ecke → to`.
     */
    fun coverDetour(from: Vec3d, to: Vec3d, cover: List<CoverSpot>): Vec3d? {
        var first: Rect2? = null
        var firstT = Double.MAX_VALUE
        for (spot in cover) {
            val r = inflate(spot.box, COVER_MARGIN)
            if (r.inside(from.x, from.z) || r.inside(to.x, to.z)) continue
            val t = r.enter(from, to) ?: continue
            if (t < firstT) {
                firstT = t
                first = r
            }
        }
        val rect = first ?: return null
        // Die Ecke, an der er schon steht, ist kein Umweg: Von dort führt die nächste Ecke weiter.
        val corners = rect.corners(CORNER_OUT).filter { flat(from, it) > CORNER_REACHED }
        if (corners.isEmpty()) return null
        val visible = corners.filter { c -> rect.enter(from, Vec3d(c.x, from.y, c.z)) == null }
        val pick = (visible.ifEmpty { corners }).minBy { flat(from, it) + flat(it, to) }
        return Vec3d(pick.x, from.y, pick.z)
    }

    // --- Ebenen -----------------------------------------------------------------------------------------------------

    /**
     * Verlässt die Strecke den Kern über eine Ebene (Kante der Ebene), kommt der Kernpunkt neben der ersten solchen Stelle
     * zurück; sonst `null`. Stellen außerhalb aller Flächen (Rasen, Wege außerhalb des Vertrags) zählen nicht.
     */
    fun levelDetour(from: Vec3d, to: Vec3d, spec: ArenaSpec): Vec3d? {
        if (spec.levels.isEmpty()) return null
        val length = flat(from, to)
        val steps = max(1, ceil(length / SAMPLE_STEP).toInt())
        for (i in 1 until steps) {
            val t = i.toDouble() / steps
            val x = from.x + (to.x - from.x) * t
            val z = from.z + (to.z - from.z) * t
            if (spec.core.contains(x, z)) continue
            if (spec.levels.none { it.zone.contains(x, z) }) continue
            val p = nearestCore(Vec3d(x, from.y, z), spec) ?: return null
            return if (flat(p, from) < 1.0) null else p
        }
        return null
    }

    private fun flat(a: Vec3d, b: Vec3d): Double = hypot(a.x - b.x, a.z - b.z)
}

/**
 * Selbsttest des Regelwerks (P03): reine Funktionen, ohne Welt und ohne Spielstart. Leere Liste = bestanden. Läuft im
 * Prüflauf (`runVerifyWorldgen`, Aufruf aus `Arenas.init` bei `-Dhha.verifyWorldgen=true`; Wunsch an die Zusammenführung:
 * Aufruf in `WorldgenVerify` wie `PortalFramesCheck.run()`).
 *
 * Fälle: Höhenband ([Telegraph.inBand]), `Sector.contains` über 360° (auch über 0° hinweg), [ArenaNav.route] um einen
 * Kasten (Umweg nötig, kein Umweg, Ziel auf einer Ebene wird auf den Kern gezogen).
 */
object ArenaRulesCheck {

    /** Zahl der geprüften Fälle (für die Meldung im Prüflauf). */
    const val CASES = 18

    fun run(): List<String> {
        val failures = ArrayList<String>()
        fun expect(name: String, ok: Boolean) {
            if (!ok) failures.add(name)
        }

        // 1. Höhenband: band(floor 100, below 1, above 3) → 99 … 103.
        val inf = Double.POSITIVE_INFINITY
        expect("band.unbegrenzt", Telegraph.inBand(1e6, -inf, inf) && Telegraph.inBand(-1e6, -inf, inf))
        expect("band.boden", Telegraph.inBand(100.0, 99.0, 103.0))
        expect("band.oben_rand", Telegraph.inBand(103.0, 99.0, 103.0))
        expect("band.zu_hoch", !Telegraph.inBand(103.01, 99.0, 103.0))
        expect("band.zu_tief", !Telegraph.inBand(98.9, 99.0, 103.0))

        // 2. Sector über 360°: Ring 10…20 um (0,0) [Mitte 0,5/0,5], Bogen 330° … 390° (über Osten hinweg).
        val c = BlockPos(0, 64, 0)
        val east = Sector.around(c, 10.0, 20.0, 330.0, 390.0)
        fun at(r: Double, deg: Double) = Pair(0.5 + r * kotlin.math.cos(Math.toRadians(deg)), 0.5 + r * kotlin.math.sin(Math.toRadians(deg)))
        val (ex, ez) = at(15.0, 0.0)
        val (nx, nz) = at(15.0, 345.0)
        val (sx, sz) = at(15.0, 25.0)
        val (wx, wz) = at(15.0, 180.0)
        val (ix, iz) = at(5.0, 0.0)
        expect("sector.ost", east.contains(ex, ez))
        expect("sector.345", east.contains(nx, nz))
        expect("sector.25", east.contains(sx, sz))
        expect("sector.west_nicht", !east.contains(wx, wz))
        expect("sector.innen_nicht", !east.contains(ix, iz))
        // Voller Kreis in 30°-Schritten ab 15° (abseits der Grenzen): genau 345° und 15° liegen drin.
        val inside = (0 until 12).count { k -> at(15.0, 15.0 + k * 30.0).let { east.contains(it.first, it.second) } }
        expect("sector.360_zaehlung($inside)", inside == 2)

        // 3. route um einen Kasten: Kern Kreis r 20 um (0, 64, 0), eine Deckung 3 × 3 in der Mitte, eine Ebene außen.
        val pillar = CoverSpot("p", BlockBox(-1, 64, -1, 1, 70, 1), setOf("test"), false)
        val ledge = dev.henny.hha.world.arena.Level("ledge", Disc.around(c, 26.0, 21.0), 67, emptyList())
        val spec = ArenaSpec(
            "selftest", c, 26, Disc.around(c, 20.0), 64, listOf(ledge), listOf(pillar), emptyList(), emptyList(),
            BlockBox(-28, 60, -28, 28, 90, 28),
        )
        val from = Vec3d(-10.5, 64.0, 0.5)
        val to = Vec3d(11.5, 64.0, 0.5)
        val via = ArenaNav.route(from, to, spec)
        val box = ArenaNav.inflate(pillar.box, ArenaNav.COVER_MARGIN)
        expect("route.umweg_ecke", hypot(via.x - to.x, via.z - to.z) > 1.0 && abs(abs(via.z - 0.5) - (2.0 + ArenaNav.COVER_MARGIN + ArenaNav.CORNER_OUT - 0.5)) < 1e-6)
        expect("route.ecke_sichtbar", box.enter(from, via) == null)
        // Von der ersten Ecke führt die zweite weiter (nicht dieselbe), von dort ist der Weg frei.
        val via2 = ArenaNav.route(via, to, spec)
        expect("route.zweite_ecke", hypot(via2.x - via.x, via2.z - via.z) > 1.0 && box.enter(via, via2) == null)
        val done = ArenaNav.route(via2, to, spec)
        expect("route.dann_frei", abs(done.x - to.x) < 1e-6 && abs(done.z - to.z) < 1e-6)
        val clear = ArenaNav.route(Vec3d(-10.5, 64.0, 8.5), Vec3d(11.5, 64.0, 8.5), spec)
        expect("route.frei", abs(clear.x - 11.5) < 1e-6 && abs(clear.z - 8.5) < 1e-6)
        val pulled = ArenaNav.route(Vec3d(0.5, 64.0, 10.5), Vec3d(0.5, 67.0, 24.5), spec)
        expect("route.ebene_auf_kern", spec.core.contains(pulled.x, pulled.z) && pulled.y == 64.0 && pulled.z > 15.0)
        // Ziel außerhalb aller Flächen (Spalt zwischen Kern r 20 und Ebene ab 21) bleibt, wie es ist.
        val outside = ArenaNav.route(Vec3d(0.5, 64.0, 10.5), Vec3d(0.5, 60.0, 20.9), spec)
        expect("route.aussen_bleibt", abs(outside.x - 0.5) < 1e-6 && abs(outside.z - 20.9) < 1e-6)
        return failures
    }
}
