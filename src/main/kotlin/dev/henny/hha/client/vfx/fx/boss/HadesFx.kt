package dev.henny.hha.client.vfx.fx.boss

import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.anim.Spring
import dev.henny.hha.client.model.HadesAnchors
import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.VfxEffect
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxClock
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.client.vfx.fx.FxRegistry
import dev.henny.hha.client.vfx.fx.Tear
import dev.henny.hha.client.vfx.fx.lib.FxLib
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * Client-Effekte von Hades (P12), Präfix `hades.`. Alle weich gezeichnet über [FxDraw] und [Tear] (keine Stufen, kein
 * Blinken); Zeiten und Größen stehen als benannte Konstanten in den Begleitobjekten. Kodierung je ID (Server:
 * `HadesAttacks`/`HadesFight` über `BossFx.play`):
 *
 * | ID | pos | dir | a | b | c | d | life | entity | extra |
 * | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
 * | `hades.bident` | Kegelspitze (Boden) | Hiebrichtung | Radius | halber Winkel (°) | +1 rechts→links, −1 links→rechts, 0 von oben | – | life | Hades | – |
 * | `hades.judge` | Saalmitte (Boden) | – | Höhe der Säulen | Radius einer Säule | Fallzeit (Ticks) | – | life | – | Versätze `dx,dz;dx,dz` |
 * | `hades.gate` | Fuß der Pforte | nach innen | Höhe | Breite | 1 = Zug der Toten tritt aus | – | life (verlängert eine stehende Pforte) | – | – |
 * | `hades.chain_snap` | Rissstelle | – | – | – | – | – | life | Hades | – |
 */
object HadesFx {
    fun init() {
        FxRegistry.register(BidentFx.ID) { BidentFx(it) }
        FxRegistry.register(JudgeFx.ID) { JudgeFx(it) }
        FxRegistry.register(GateFx.ID) { GateFx.create(it) }
        FxRegistry.register(ChainSnapFx.ID) { ChainSnapFx(it) }
    }

    internal val UP = Vec3d(0.0, 1.0, 0.0)

    /** Nur Ziffern, Komma, Punkt, Minus und Strichpunkt: Liste von Versätzen `dx,dz;dx,dz` (Locale.ROOT). */
    internal fun decodeOffsets(extra: String): List<Pair<Double, Double>> = extra.split(';').mapNotNull { part ->
        val xz = part.split(',')
        if (xz.size != 2) return@mapNotNull null
        val x = xz[0].trim().toDoubleOrNull() ?: return@mapNotNull null
        val z = xz[1].trim().toDoubleOrNull() ?: return@mapNotNull null
        x to z
    }

    /** Gegenstück am Server (gleiche Schreibweise, eine Nachkommastelle). */
    fun encodeOffsets(offsets: List<Pair<Double, Double>>): String =
        offsets.joinToString(";") { (x, z) -> String.format(Locale.ROOT, "%.1f,%.1f", x, z) }
}

/**
 * `hades.bident`: Sichelband am Bident. Zeichnet die Bahn, die die Bident-Spitze im Modell gefahren ist (Verlauf aus
 * [HadesAnchors], ab [LEAD_TICKS] vor dem Eintreffen), als weiches Band: außen breite Flammenzunge, innen heller Kern.
 * Das Band folgt der Waffe, solange der Effekt lebt (Hieb und Nachlauf), und blendet dann aus. Ist kein frischer
 * Verlauf da (Wesen nicht im Bild, Rückfall), zeichnet es eine gerechnete Sichel in der Hiebrichtung um seinen Körper.
 */
class BidentFx(args: FxArgs) : FxEffect(args.life.coerceIn(MIN_LIFE, MAX_LIFE)) {
    private val entity: Entity? = args.entity
    private val entityId = args.entity?.id ?: args.entityId
    private val apex = args.pos
    private val dir = FxLib.flat(args.dir)
    private val right = Vec3d(-dir.z, 0.0, dir.x)
    private val radius = args.a.coerceIn(2f, 16f)
    private val half = Math.toRadians(args.b.coerceIn(10f, 120f).toDouble())
    private val side = sign(args.c)
    private val palette: Palette = args.palette
    private val seed = args.seed.toInt()

    /** Alter des Wesens beim ersten Bild (Zeitachse des Verlaufs). */
    private var startAge = Float.NaN

    override fun energy(f: FxFrame) {
        val t = time(f)
        val fade = fade(t)
        if (fade <= 0.01f) return
        val points = trailPoints(f) ?: sickle(f, t)
        if (points.size < 2) return
        val outer = palette.main
        val core = FxLib.mix(palette.core, palette.main, 0.25f)
        FxDraw.ribbon(
            f, points, width = { k -> OUTER_WIDTH * (0.2f + 0.8f * k) * (0.6f + 0.4f * fade) },
            color = { k -> FxLib.alpha(outer, OUTER_ALPHA * fade * k.pow(0.8f)) }, mode = FxMode.FLAME, seed = seed,
        )
        FxDraw.ribbon(
            f, points, width = { k -> CORE_WIDTH * (0.15f + 0.85f * k) },
            color = { k -> FxLib.alpha(core, CORE_ALPHA * fade * k.pow(1.2f)) }, mode = FxMode.RIBBON, seed = seed + 1,
        )
        FxDraw.glow(f, points.last(), HEAD_GLOW, FxLib.alpha(palette.main, HEAD_ALPHA * fade), seed)
    }

    /** Ganz da bis [HOLD] der Lebenszeit, dann weich aus (quadratisch). */
    private fun fade(t: Float): Float {
        val x = ((t / life) - HOLD) / (1f - HOLD)
        return if (x <= 0f) 1f else (1f - x).coerceIn(0f, 1f).let { it * it }
    }

    /** Verlauf der echten Spitze, auf höchstens [MAX_POINTS] Punkte verdünnt; null = keiner da (Rückfall). */
    private fun trailPoints(f: FxFrame): List<Vec3d>? {
        val e = entity ?: return null
        if (e.isRemoved) return null
        val now = e.age + f.tickDelta
        if (startAge.isNaN()) startAge = now - LEAD_TICKS
        val seen = HadesAnchors.seen(entityId) ?: return null
        if (now - seen > STALE_TICKS) return null
        val samples = HadesAnchors.trail(entityId, max(startAge, now - TRAIL_TICKS))
        if (samples.size < 3) return null
        val step = max(1, samples.size / MAX_POINTS)
        val picked = samples.filterIndexed { i, _ -> i % step == 0 || i == samples.lastIndex }.map { it.tip }
        return FxDraw.smooth(picked, 2)
    }

    /**
     * Rückfall: gerechnete Sichel um den Körper (Kopf läuft in [SWEEP_TICKS] durch den Winkel, der Schweif folgt).
     * Waagrecht für die Seitenhiebe, senkrecht von oben für den Schlusshieb.
     */
    private fun sickle(f: FxFrame, t: Float): List<Vec3d> {
        val base = entity?.takeIf { !it.isRemoved }?.getLerpedPos(f.tickDelta) ?: apex
        val head = Ease.outCubic(t / SWEEP_TICKS)
        val tail = Ease.inOut((t - TAIL_DELAY) / (life - TAIL_DELAY).coerceAtLeast(1f)) * 0.9f
        if (head - tail < 0.02f) return emptyList()
        val r = radius * SICKLE_RADIUS
        return List(SICKLE_POINTS) { i ->
            val u = tail + (head - tail) * i / (SICKLE_POINTS - 1f)
            if (side == 0f) {
                // Von oben: hinten oben → vorn unten, in der Ebene aus Hiebrichtung und Senkrechter.
                val a = OVERHEAD_FROM + (OVERHEAD_TO - OVERHEAD_FROM) * u
                base.add(0.0, OVERHEAD_Y, 0.0).add(dir.multiply(sin(a) * r)).add(HadesFx.UP.multiply(cos(a) * r * 0.8))
            } else {
                val a = side * half * (1.0 - 2.0 * u)
                base.add(0.0, SIDE_Y, 0.0).add(dir.multiply(cos(a) * r)).add(right.multiply(sin(a) * r))
            }
        }
    }

    companion object {
        const val ID = "hades.bident"
        const val MIN_LIFE = 6
        const val MAX_LIFE = 30

        /** Verlauf: so viele Ticks vor dem ersten Bild gehören schon zum Hieb; so lang ist das Band höchstens. */
        const val LEAD_TICKS = 3f
        const val TRAIL_TICKS = 5f
        const val STALE_TICKS = 2f
        const val MAX_POINTS = 18

        /** Ganz sichtbar bis zu diesem Anteil der Lebenszeit. */
        const val HOLD = 0.45f

        const val OUTER_WIDTH = 0.55f
        const val OUTER_ALPHA = 0.55f
        const val CORE_WIDTH = 0.16f
        const val CORE_ALPHA = 0.9f
        const val HEAD_GLOW = 0.5f
        const val HEAD_ALPHA = 0.35f

        /** Rückfall-Sichel. */
        const val SWEEP_TICKS = 4f
        const val TAIL_DELAY = 2f
        const val SICKLE_RADIUS = 0.6f
        const val SICKLE_POINTS = 14
        const val SIDE_Y = 1.7
        const val OVERHEAD_Y = 1.4
        const val OVERHEAD_FROM = -0.6
        const val OVERHEAD_TO = 1.9
    }
}

/**
 * `hades.judge`: Das Urteil fährt herab. Je Versatz in `extra` eine Säule aus Licht (Band `BOLT` mit hellem Kern),
 * deren Kopf in `c` Ticks von `a` Blöcken Höhe auf den Boden fällt (`outCubic`), dort ein weicher Stoßring; danach
 * steht sie kurz und verlischt. Die Lagen rechnet der Server so, dass keine Säule in einem goldenen Kreis steht.
 * Der Kopf fällt in der Luft (über der Ansage); den Boden berührt das Licht erst im Treffer-Tick.
 */
class JudgeFx(args: FxArgs) : FxEffect(args.life.coerceIn(MIN_LIFE, MAX_LIFE)) {
    private val center = args.pos
    private val height = args.a.coerceIn(4f, 64f).toDouble()
    private val radius = args.b.coerceIn(0.4f, 4f)
    private val descend = args.c.coerceIn(1f, 20f)
    private val palette: Palette = args.palette
    private val beams: List<Vec3d> = HadesFx.decodeOffsets(args.extra).take(MAX_BEAMS).map { (dx, dz) ->
        val x = center.x + dx
        val z = center.z + dz
        Vec3d(x, FxLib.groundY(args.world, x, center.y + 0.5, z), z)
    }
    private val seed = args.seed.toInt()

    override fun energy(f: FxFrame) {
        val t = time(f)
        val fall = Ease.outCubic(t / descend)
        val after = t - descend
        val glow = if (after <= HOLD) 1f else (1f - (after - HOLD) / (life - descend - HOLD).coerceAtLeast(1f)).coerceIn(0f, 1f).let { it * it }
        if (glow <= 0.01f) return
        val main = palette.main
        val core = palette.core
        for ((i, ground) in beams.withIndex()) {
            val top = ground.add(0.0, height, 0.0)
            val head = top.lerp(ground, fall.toDouble())
            val pts = listOf(top, top.lerp(head, 0.5), head)
            val landed = (after / IMPACT_TICKS).coerceIn(0f, 1f)
            FxDraw.ribbon(
                f, pts, width = { k -> radius * (0.55f + 0.45f * k) * (1f + 0.25f * Ease.bump(landed)) },
                color = { k -> FxLib.alpha(main, BEAM_ALPHA * glow * (0.35f + 0.65f * k)) }, mode = FxMode.BOLT, seed = seed + i,
            )
            FxDraw.ribbon(
                f, pts, width = { k -> radius * CORE_SHARE * (0.5f + 0.5f * k) },
                color = { k -> FxLib.alpha(core, CORE_ALPHA * glow * k) }, mode = FxMode.RIBBON, seed = seed + 31 * i,
            )
            if (after < 0f) {
                FxDraw.glow(f, head, radius * HEAD_GLOW, FxLib.alpha(core, 0.6f), seed + i)
            } else {
                // Stoßring am Boden: wächst in IMPACT_TICKS auf RING_REACH Säulenradien, verblasst dabei.
                val ring = radius * (1f + (RING_REACH - 1f) * Ease.outCubic(landed))
                FxDraw.flat(
                    f, ground.add(0.0, 0.04, 0.0), HadesFx.UP, ring, FxLib.alpha(main, RING_ALPHA * (1f - landed) * glow),
                    FxMode.SHOCK, inner = ring * 0.72f, seed = seed + i,
                )
                FxDraw.flat(
                    f, ground.add(0.0, 0.05, 0.0), HadesFx.UP, radius * 1.6f, FxLib.alpha(main, POOL_ALPHA * glow), FxMode.GLOW,
                    seed = seed + i,
                )
            }
        }
    }

    companion object {
        const val ID = "hades.judge"
        const val MIN_LIFE = 10
        const val MAX_LIFE = 80
        const val MAX_BEAMS = 24

        /** Nach dem Aufschlag so lange voll, dann quadratisch aus. */
        const val HOLD = 6f
        const val IMPACT_TICKS = 8f
        const val RING_REACH = 3f

        const val BEAM_ALPHA = 0.6f
        const val CORE_SHARE = 0.28f
        const val CORE_ALPHA = 0.85f
        const val HEAD_GLOW = 1.4f
        const val RING_ALPHA = 0.55f
        const val POOL_ALPHA = 0.3f
    }
}

/**
 * `hades.gate`: Höllenpforte am Rand, hochkant ([Tear.draw] entlang der Senkrechten, Fläche zur Saalmitte), dunkler
 * Kern, grüner Glutrand, ein schwacher Schein am Boden und ein paar Seelen, die aus dem Riss in den Saal treiben.
 * Die Pforte steht, solange der Server sie erneuert: Ein neuer Aufruf am selben Ort (≤ [SAME_SPOT] Blöcke) verlängert
 * die stehende Pforte statt eine zweite zu öffnen (kein sichtbarer Neustart). `c` = 1 lässt sie aufglühen und weiter
 * aufgehen (der Zug der Toten tritt aus).
 */
class GateFx private constructor(args: FxArgs) : FxEffect(MAX_LIFE) {
    private val foot = args.pos
    private val inward = FxLib.flat(args.dir)
    private val across = Vec3d(inward.z, 0.0, -inward.x)
    private val height = args.a.coerceIn(2f, 24f)
    private val width = args.b.coerceIn(0.5f, 8f)
    private val palette: Palette = args.palette
    private val shape = Tear.Shape(args.seed.toInt(), 17)
    private val souls: List<FloatArray> = Random(args.seed + 3).let { rnd ->
        List(SOULS) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat() * 2f - 1f, rnd.nextFloat()) }
    }

    /** Ende in sichtbaren Ticks (wird beim Erneuern verschoben). */
    private var end = args.life.coerceAtLeast(MIN_LIFE).toFloat()
    private var surgeAt = if (args.c >= 0.5f) 0f else -1000f
    private var lastTick = args.world.time

    override fun tick(world: ClientWorld) {
        lastTick = world.time
    }

    override fun expired(): Boolean = FxClock.age(this, age.toFloat()) >= end || age >= end + FxEffect.MAX_HOLD_TICKS

    private fun extend(newLife: Int, surge: Boolean) {
        end = max(end, age + newLife.coerceAtLeast(MIN_LIFE).toFloat())
        if (surge) surgeAt = age.toFloat()
    }

    /** Öffnung 0..1: in [OPEN_TICKS] mit leichtem Überschießen auf, in den letzten [CLOSE_TICKS] zu. */
    private fun openness(t: Float): Float {
        val opening = Ease.outBack(t / OPEN_TICKS).coerceAtMost(1.08f)
        val closing = ((end - t) / CLOSE_TICKS).coerceIn(0f, 1f)
        return opening * Ease.inOut(closing)
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val open = openness(t)
        if (open <= 0.01f) return
        val surge = Ease.bump(((t - surgeAt) / SURGE_TICKS).coerceIn(0f, 1f))
        val center = foot.add(0.0, height / 2.0 + LIFT, 0.0)
        Tear.draw(
            f, center, HadesFx.UP, across, height, width * (1f + SURGE_WIDEN * surge), open, shape, palette, t,
            hot = BASE_HEAT + (1f - BASE_HEAT) * surge,
        )
        // Schein am Boden vor der Pforte (atmet langsam, ≤ 0,2 Hz).
        val breath = 0.85f + 0.15f * sin(t * BREATH_SPEED)
        FxDraw.flat(
            f, foot.add(inward.multiply(width * 0.6)).add(0.0, 0.04, 0.0), HadesFx.UP, width * 1.5f,
            FxLib.alpha(palette.main, FLOOR_ALPHA * open * breath), FxMode.GLOW, seed = shape.seed,
        )
        // Seelen treiben aus dem Riss nach innen und steigen dabei leicht (stetiger Zyklus, an beiden Enden unsichtbar).
        for ((i, s) in souls.withIndex()) {
            val cycle = ((t * SOUL_SPEED * (0.7f + 0.6f * s[2]) + s[0]) % 1f + 1f) % 1f
            val from = center.add(across.multiply(s[1] * width * 0.25)).add(0.0, (s[2] - 0.5) * height * 0.6, 0.0)
            val at = from.add(inward.multiply(cycle * SOUL_REACH)).add(0.0, cycle * 1.2, 0.0)
            val back = at.subtract(inward.multiply(0.5)).add(0.0, -0.15, 0.0)
            FxDraw.ribbon(
                f, listOf(back, at), width = { k -> SOUL_WIDTH * (0.4f + 0.6f * k) },
                color = { _ -> FxLib.alpha(palette.alt, SOUL_ALPHA * open * Ease.bump(cycle) * (1f + surge)) },
                mode = FxMode.RIBBON, seed = shape.seed + i,
            )
        }
    }

    companion object {
        const val ID = "hades.gate"
        /** Obergrenze der Lebenszeit; die eigentliche Dauer steuert [end]. */
        const val MAX_LIFE = 20 * 60 * 30
        const val MIN_LIFE = 20
        const val SAME_SPOT = 2.0

        const val OPEN_TICKS = 14f
        const val CLOSE_TICKS = 14f
        const val SURGE_TICKS = 40f
        const val SURGE_WIDEN = 0.3f
        const val BASE_HEAT = 0.15f
        const val LIFT = 0.3
        const val BREATH_SPEED = 0.06f
        const val FLOOR_ALPHA = 0.3f

        const val SOULS = 6
        const val SOUL_SPEED = 0.012f
        const val SOUL_REACH = 5.0
        const val SOUL_WIDTH = 0.12f
        const val SOUL_ALPHA = 0.45f

        private val gates = ArrayList<GateFx>()

        /** Fabrik: verlängert eine stehende Pforte am selben Ort oder öffnet eine neue. */
        fun create(args: FxArgs): VfxEffect? {
            val now = args.world.time
            // Was nicht mehr tickt (abgelaufen, `clearVfx`, Weltwechsel), ist fort.
            gates.removeIf { now - it.lastTick > 2 || now < it.lastTick }
            val near = gates.firstOrNull { it.foot.squaredDistanceTo(args.pos) < SAME_SPOT * SAME_SPOT }
            if (near != null) {
                near.extend(args.life, args.c >= 0.5f)
                return null
            }
            return GateFx(args).also { gates.add(it) }
        }
    }
}

/**
 * `hades.chain_snap`: Eine Seelenkette reißt an der Säule. Das freie Ende schnellt über eine Feder ([Spring], leicht
 * unterdämpft) zu seiner Hand zurück, schwingt dabei aus und verblasst. Die Funken an der Rissstelle schickt der
 * Server (`core.sparks`).
 */
class ChainSnapFx(args: FxArgs) : FxEffect(args.life.coerceIn(MIN_LIFE, MAX_LIFE)) {
    private val breakAt = args.pos
    private val entity: Entity? = args.entity
    private val entityId = args.entity?.id ?: args.entityId
    private val palette: Palette = args.palette
    private val seed = args.seed.toInt()
    private val spring = Spring(SPRING_FREQ, SPRING_DAMP).also { it.step(0f, 0f) }
    private var previous = 0f
    private var current = 0f

    override fun tick(world: ClientWorld) {
        previous = current
        current = spring.step(1f, 1f)
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val fade = (1f - k(f)).coerceIn(0f, 1f).pow(1.5f)
        if (fade <= 0.01f) return
        val anchor = HadesAnchors.hand(entityId) ?: entity?.takeIf { !it.isRemoved }?.let { FxLib.body(it, f.tickDelta, 0.6) } ?: return
        val s = (previous + (current - previous) * f.tickDelta).coerceIn(0f, OVERSHOOT_MAX)
        val free = breakAt.lerp(anchor, s.toDouble())
        val span = anchor.subtract(free)
        val length = span.length()
        if (length < 0.2) return
        val side = FxLib.across(span.normalize())
        val slack = (1f - s.coerceAtMost(1f)) + (s - 1f).coerceAtLeast(0f) * 3f
        val points = List(POINTS) { i ->
            val u = i / (POINTS - 1.0)
            val wave = sin(u * PI * 2.0 + t * WAVE_SPEED) * WAVE * slack * sin(u * PI)
            free.lerp(anchor, u).add(side.multiply(wave)).add(0.0, -SAG * slack * sin(u * PI) * min(1.0, length / 6.0), 0.0)
        }
        FxDraw.ribbon(
            f, points, width = { _ -> WIDTH }, color = { k -> FxLib.alpha(palette.main, ALPHA * fade * (0.4f + 0.6f * (1f - k))) },
            mode = FxMode.RIBBON, seed = seed,
        )
        FxDraw.glow(f, free, END_GLOW, FxLib.alpha(palette.core, 0.5f * fade), seed)
    }

    companion object {
        const val ID = "hades.chain_snap"
        const val MIN_LIFE = 8
        const val MAX_LIFE = 40

        /** Feder des freien Endes: ≈ 0,35 s Rückschnellen, ein sichtbares Überschwingen. */
        const val SPRING_FREQ = 0.9f
        const val SPRING_DAMP = 0.35f
        const val OVERSHOOT_MAX = 1.1f

        const val POINTS = 12
        const val WAVE = 0.45
        const val WAVE_SPEED = 0.8f
        const val SAG = 0.7
        const val WIDTH = 0.09f
        const val ALPHA = 0.85f
        const val END_GLOW = 0.35f
    }
}
