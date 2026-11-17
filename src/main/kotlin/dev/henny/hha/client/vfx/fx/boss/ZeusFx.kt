package dev.henny.hha.client.vfx.fx.boss

import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaSounds
import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.fx.BlockDebris
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxAttach
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMeshes
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.client.vfx.fx.FxRegistry
import dev.henny.hha.client.vfx.fx.Tear
import dev.henny.hha.client.vfx.fx.lib.FxLib
import dev.henny.hha.entity.boss.olymp.ZeusAction
import dev.henny.hha.entity.boss.olymp.ZeusEntity
import dev.henny.hha.logic.Vfx
import dev.henny.hha.world.olymp.OlympusLayout
import net.minecraft.block.Blocks
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Client-Effekte des Zeus (P11, PLAN 2.8), Präfix `zeus.`. Alles weich über [FxDraw] (Bänder, Flächen, glatte Meshes),
 * stetige Zeit, kein Blinken; Gold nur an der Lücke der Sturmwand (sicher) und an Nike (Ziel/Lohn).
 *
 * | ID | Server-Stelle | Kodierung | Bild |
 * | --- | --- | --- | --- |
 * | `zeus.slam` | Donnerschlag, Treffer (Flächeneffekt) | pos = Mitte, dir = Blitzspitze (absolut), a = Radius, life | 6 Bodenrisse + 8 echte Trümmer, Lichtring, Brandfleck, Schein an der Spitze |
 * | `zeus.stuck` | Keraunos steckt | pos = Boden am Blitz, a = Radius der Zone, life | stehende Bögen vom Blitz in den Boden, ruhig |
 * | `zeus.gaze` | Gorgonenblick, Beginn der Ansage | pos = Aigis, dir = Blick, a = Reichweite, b = halber Winkel, c = Ansage, entity = Zeus | Aigis lädt auf, im Treffer ein gerichteter Lichtkegel |
 * | `zeus.star` | Donnerschritt, Erscheinen | pos = Landepunkt, a = Radius, life | Lichtstern |
 * | `zeus.storm_wall` | Sturmwand (`extra = line`) / Waage (`extra = ring`) | siehe [ZeusStormWallFx] | Regenwand mit Lücke bzw. Regen außerhalb der Schale |
 * | `zeus.rod` | geladener Pfeiler, je Runen-Zyklus | pos = Fuß, life | kleiner ruhiger Schein, ein Funke kriecht hoch (0,5 Hz) |
 * | `zeus.nike` | Bühne `zeus.defeat` | pos = Füße des Siegers, a = Ticks bis zum Kranz, life | Lichtgestalt mit Flügeln und goldenem Kranz |
 * | `zeus.sky` | Dauer-Effekt am Zeus (`FxAttach`) | – | Lichtrisse in der Himmelskuppel: Zahl aus der Phase, Breite aus dem Leben |
 */
object ZeusFx {

    const val SLAM = "zeus.slam"
    const val STUCK = "zeus.stuck"
    const val GAZE = "zeus.gaze"
    const val STAR = "zeus.star"
    const val STORM_WALL = "zeus.storm_wall"
    const val ROD = "zeus.rod"
    const val NIKE = "zeus.nike"
    const val SKY = "zeus.sky"

    fun init() {
        FxRegistry.register(SLAM) { ZeusSlamFx(it) }
        FxRegistry.register(STUCK) { ZeusStuckFx(it) }
        FxRegistry.register(GAZE) { ZeusGazeFx(it) }
        FxRegistry.register(STAR) { ZeusStarFx(it) }
        FxRegistry.register(STORM_WALL) { ZeusStormWallFx(it) }
        FxRegistry.register(ROD) { ZeusRodFx(it) }
        FxRegistry.register(NIKE) { ZeusNikeFx(it) }
        // `zeus.sky` hat keine Netz-Fabrik: Er hängt am Wesen und leitet alles aus dessen Zustand ab.
        FxAttach.register(HhaEntities.ZEUS) { ZeusSkyFx(it) }
    }

    /** Hülle: weich ein über [fadeIn], weich aus über die letzten [fadeOut] Ticks von [life]. */
    internal fun envelope(t: Float, life: Int, fadeIn: Float, fadeOut: Float): Float =
        Ease.inOut(t / fadeIn) * Ease.inOut((life - t) / fadeOut)

    /** Kubischer Abfall 1 → 0 über [span] Ticks (Blitz-Regel: hell nur kurz, weich aus). */
    internal fun cubicFade(t: Float, span: Float): Float = (1f - (t / span).coerceIn(0f, 1f)).pow(3)

    /** Querschnitt eines spitz zulaufenden Bandes (0 an den Enden, 1 in der Mitte). */
    internal fun lens(k: Float): Float = sin(k.coerceIn(0f, 1f) * PI.toFloat()).coerceAtLeast(0f).pow(0.7f)

    internal fun horizontal(angle: Double): Vec3d = Vec3d(cos(angle), 0.0, sin(angle))

    internal val UP = Vec3d(0.0, 1.0, 0.0)
}

// =====================================================================================================================
// zeus.slam – Donnerschlag
// =====================================================================================================================

/**
 * Donnerschlag: Er rammt den Keraunos in den Marmor. Im ersten Tick sechs Bodenrisse (`core.cracks`) und acht echte
 * Trümmer ([BlockDebris]), dazu ein tiefer Schlag; gezeichnet ein weicher Lichtring, der bis zum Ansage-Radius läuft und
 * vergeht, ein dunkler Brandfleck in der Mitte und ein kurzer Schein an der Blitzspitze. Die Risse bleiben innerhalb von
 * [CRACK_SHARE] × Radius, also innerhalb der Ringe, die danach nach außen laufen (deren Ansage bleibt frei).
 */
private class ZeusSlamFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val center = args.pos
    private val tip = if (args.dir.lengthSquared() > 1e-6) args.dir else args.pos
    private val radius = args.a.coerceIn(1f, MAX_RADIUS)
    private val palette = args.palette
    private val style = args.style
    private val seed = args.seed.toInt()
    private var started = false

    override fun tick(world: ClientWorld) {
        if (started) return
        started = true
        FxRegistry.play(world, "core.cracks", style, center, a = radius * CRACK_SHARE, b = CRACKS.toFloat(), life = CRACK_LIFE)
        BlockDebris.burst(world, center, radius * DEBRIS_INNER, radius * DEBRIS_OUTER, DEBRIS, DEBRIS_UP, DEBRIS_OUT)
        FxLib.sound(world, center, HhaSounds.THUD, THUD_VOLUME, THUD_PITCH)
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        // Lichtring: läuft in RING_TICKS bis zum Ansage-Radius, verliert dabei Licht.
        val ring = Ease.outCubic(t / RING_TICKS)
        if (ring < 1f) {
            val outer = radius * (RING_START + (RING_END - RING_START) * ring)
            FxDraw.flat(
                f, center.add(0.0, LIFT * 2, 0.0), ZeusFx.UP, outer, FxLib.alpha(palette.main, RING_ALPHA * (1f - ring) * (1f - ring)),
                FxMode.SHOCK, inner = outer * RING_INNER, progress = ring, seed = seed,
            )
        }
        // Brandfleck: breitet sich schnell aus und vergeht langsam.
        val stain = ZeusFx.envelope(t, life, STAIN_IN, STAIN_OUT)
        if (stain > 0.01f) {
            FxDraw.flat(
                f, center.add(0.0, LIFT, 0.0), ZeusFx.UP, radius * STAIN_SHARE, FxLib.alpha(palette.edge, STAIN_ALPHA * stain),
                FxMode.STAIN, progress = Ease.outCubic(t / STAIN_IN), seed = seed + 1,
            )
        }
    }

    override fun over(f: FxFrame) {
        val glow = ZeusFx.cubicFade(time(f), GLOW_TICKS)
        if (glow <= 0.01f) return
        FxDraw.glow(f, tip.add(0.0, GLOW_UP, 0.0), GLOW_SIZE * (0.6f + 0.4f * glow), FxLib.alpha(palette.core, GLOW_ALPHA * glow), seed)
    }

    companion object {
        const val MIN_LIFE = 30
        const val MAX_RADIUS = 14f
        const val CRACKS = 6
        /** Risse höchstens bis 0,85 × Radius (die Ringe danach beginnen bei Radius − 1). */
        const val CRACK_SHARE = 0.85f
        const val CRACK_LIFE = 70
        const val DEBRIS = 8
        const val DEBRIS_INNER = 0.25f
        const val DEBRIS_OUTER = 0.7f
        const val DEBRIS_UP = 0.55f
        const val DEBRIS_OUT = 0.22f
        const val THUD_VOLUME = 0.9f
        const val THUD_PITCH = 0.8f
        const val LIFT = 0.02
        const val RING_TICKS = 12f
        const val RING_START = 0.08f
        const val RING_END = 1.02f
        const val RING_INNER = 0.78f
        const val RING_ALPHA = 0.5f
        const val STAIN_SHARE = 0.42f
        const val STAIN_IN = 6f
        const val STAIN_OUT = 26f
        const val STAIN_ALPHA = 0.32f
        const val GLOW_TICKS = 10f
        const val GLOW_UP = 0.5
        const val GLOW_SIZE = 1.5f
        const val GLOW_ALPHA = 0.6f
    }
}

// =====================================================================================================================
// zeus.stuck – der steckende Keraunos
// =====================================================================================================================

/**
 * Der Blitz steckt im Boden: drei stehende, leise kriechende Bögen vom Blitz in den Boden der Zone. Helligkeit bleibt
 * gleich (die Zone trifft alle 10 Ticks; ein Puls dazu wäre 2 Hz); nur Form und Fußpunkte wandern stetig.
 */
private class ZeusStuckFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(10)) {
    private val ground = args.pos
    private val bolt = args.pos.add(0.0, BOLT_UP, 0.0)
    private val radius = args.a.coerceIn(0.5f, 6f)
    private val palette = args.palette
    private val seed = args.seed.toInt()
    private val base = Random(args.seed).nextDouble() * PI * 2

    override fun energy(f: FxFrame) {
        val t = time(f)
        val env = ZeusFx.envelope(t, life, FADE_IN, FADE_OUT)
        if (env <= 0.01f) return
        for (i in 0 until ARCS) {
            val angle = base + i * (PI * 2 / ARCS) + t * DRIFT
            val reach = radius * (REACH_MIN + (REACH_MAX - REACH_MIN) * (0.5f + 0.5f * sin(t * 0.05f + i * 2.3f)))
            val foot = ground.add(ZeusFx.horizontal(angle).multiply(reach.toDouble())).add(0.0, 0.04, 0.0)
            val side = ZeusFx.horizontal(angle + PI / 2)
            val points = List(POINTS) { j ->
                val k = j / (POINTS - 1f)
                val wobble = sin(t * WOBBLE_SPEED + j * 1.7f + i * 2.1f) * WOBBLE * lensOf(k)
                FxLib.bezier(bolt, bolt.add(foot.subtract(bolt).multiply(0.5)).add(0.0, BEND, 0.0), foot, k)
                    .add(side.multiply(wobble.toDouble()))
            }
            FxDraw.ribbon(
                f, points, width = { k -> WIDTH * (1f - 0.6f * k) },
                color = { k -> FxLib.alpha(FxLib.mix(palette.core, palette.main, k), ALPHA * env) }, mode = FxMode.BOLT, seed = seed + i,
            )
        }
        FxDraw.glow(f, bolt, GLOW_SIZE, FxLib.alpha(palette.main, GLOW_ALPHA * env), seed)
    }

    private fun lensOf(k: Float) = sin(k * PI.toFloat())

    companion object {
        const val BOLT_UP = 0.9
        const val ARCS = 3
        const val POINTS = 7
        const val REACH_MIN = 0.45f
        const val REACH_MAX = 0.8f
        /** Fußpunkte wandern langsam um den Blitz (rad/Tick). */
        const val DRIFT = 0.03
        const val BEND = 0.5
        const val WOBBLE_SPEED = 0.35f
        const val WOBBLE = 0.12f
        const val WIDTH = 0.09f
        const val ALPHA = 0.55f
        const val GLOW_SIZE = 0.75f
        const val GLOW_ALPHA = 0.22f
        const val FADE_IN = 3f
        const val FADE_OUT = 6f
    }
}

// =====================================================================================================================
// zeus.gaze – Aigis-Schein
// =====================================================================================================================

/**
 * Gorgonenblick: Über die Ansage sammelt sich an der Aigis ein Schein (wächst weich); im Treffer fächert ein
 * gerichteter Lichtkegel aus [RAYS] weichen Strahlen über den halben Winkel nach vorn und vergeht kubisch. Kein
 * Bildschirm-Pass; die kühle Vignette schickt der Server nur Spielern, die hinsehen.
 */
private class ZeusGazeFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(10)) {
    private val zeus: Entity? = args.entity
    private val start = args.pos
    private val dir = FxLib.flat(args.dir, Vec3d(0.0, 0.0, 1.0))
    private val reach = args.a.coerceIn(4f, 40f)
    private val half = Math.toRadians(args.b.coerceIn(5f, 89f).toDouble())
    private val windup = args.c.coerceIn(0f, (life - 1).toFloat())
    private val palette = args.palette
    private val seed = args.seed.toInt()

    /** Lage der Aigis: folgt dem Wesen (Höhe und Vorhalt wie `ZeusEntity.gazeEye`). */
    private fun eye(f: FxFrame): Vec3d {
        val e = zeus ?: return start
        if (e.isRemoved) return start
        return e.getLerpedPos(f.tickDelta).add(0.0, e.height * EYE_UP, 0.0).add(dir.multiply(EYE_FORWARD))
    }

    private fun feetY(f: FxFrame): Double = zeus?.takeIf { !it.isRemoved }?.getLerpedPos(f.tickDelta)?.y ?: (start.y - EYE_FALLBACK_DROP)

    override fun energy(f: FxFrame) {
        val t = time(f)
        val eye = eye(f)
        if (t < windup) {
            val k = Ease.inQuad(t / windup.coerceAtLeast(1f))
            FxDraw.glow(f, eye, CHARGE_SIZE_MIN + (CHARGE_SIZE_MAX - CHARGE_SIZE_MIN) * k, FxLib.alpha(palette.main, CHARGE_ALPHA * k), seed)
            return
        }
        val s = t - windup
        val fade = ZeusFx.cubicFade(s, life - windup)
        if (fade <= 0.01f) return
        val spread = Ease.outCubic(s / SPREAD_TICKS)
        val ground = feetY(f) + RAY_GROUND_LIFT
        for (i in 0 until RAYS) {
            val u = i / (RAYS - 1f) * 2f - 1f
            val a = u * half * spread
            val ray = Vec3d(dir.x * cos(a) - dir.z * sin(a), 0.0, dir.x * sin(a) + dir.z * cos(a))
            val far = eye.add(ray.multiply((reach * RAY_REACH).toDouble())).let { Vec3d(it.x, ground, it.z) }
            val points = List(RAY_POINTS) { j -> FxLib.lerp(eye, far, j / (RAY_POINTS - 1f)) }
            val centerWeight = 1f - RAY_SIDE_DIM * abs(u)
            FxDraw.ribbon(
                f, points, width = { k -> RAY_WIDTH_NEAR + (RAY_WIDTH_FAR - RAY_WIDTH_NEAR) * k },
                color = { k -> FxLib.alpha(FxLib.mix(palette.core, palette.main, k), RAY_ALPHA * fade * centerWeight * (1f - k).pow(1.3f)) },
                mode = FxMode.RIBBON, scroll = t * 0.1f, seed = seed + i,
            )
        }
    }

    override fun over(f: FxFrame) {
        val s = time(f) - windup
        if (s < 0f) return
        val flash = ZeusFx.cubicFade(s, FLASH_TICKS)
        if (flash <= 0.01f) return
        FxDraw.glow(f, eye(f), FLASH_SIZE, FxLib.alpha(palette.core, FLASH_ALPHA * flash), seed + 99)
    }

    companion object {
        const val EYE_UP = 0.82
        const val EYE_FORWARD = 0.6
        const val EYE_FALLBACK_DROP = 4.1
        const val CHARGE_SIZE_MIN = 0.25f
        const val CHARGE_SIZE_MAX = 0.95f
        const val CHARGE_ALPHA = 0.45f
        const val RAYS = 9
        const val RAY_POINTS = 6
        const val RAY_REACH = 0.9f
        const val RAY_GROUND_LIFT = 0.4
        const val RAY_WIDTH_NEAR = 0.12f
        const val RAY_WIDTH_FAR = 1.6f
        const val RAY_ALPHA = 0.32f
        const val RAY_SIDE_DIM = 0.45f
        const val SPREAD_TICKS = 5f
        const val FLASH_TICKS = 8f
        const val FLASH_SIZE = 1.8f
        const val FLASH_ALPHA = 0.55f
    }
}

// =====================================================================================================================
// zeus.star – Lichtstern des Donnerschritts
// =====================================================================================================================

/** Lichtstern beim Erscheinen: sechs flache und zwei steigende Strahlen schießen aus und vergehen kubisch. */
private class ZeusStarFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(6)) {
    private val center = args.pos.add(0.0, CENTER_UP, 0.0)
    private val radius = args.a.coerceIn(0.5f, 8f)
    private val palette = args.palette
    private val seed = args.seed.toInt()
    private val turn = Random(args.seed).nextDouble() * PI

    override fun energy(f: FxFrame) {
        val t = time(f)
        val fade = ZeusFx.cubicFade(t, life.toFloat())
        if (fade <= 0.01f) return
        val grow = Ease.outCubic(t / GROW_TICKS)
        for (i in 0 until FLAT_RAYS + RISING_RAYS) {
            val rising = i >= FLAT_RAYS
            val angle = turn + if (rising) (i - FLAT_RAYS) * PI + PI / 2 else i * (PI * 2 / FLAT_RAYS)
            val length = radius * grow * (if (rising) RISING_SHARE else 1f)
            val end = center.add(ZeusFx.horizontal(angle).multiply(length.toDouble() * (if (rising) 0.45 else 1.0)))
                .add(0.0, if (rising) length.toDouble() else length * FLAT_TILT, 0.0)
            FxDraw.ribbon(
                f, listOf(center, FxLib.lerp(center, end, 0.5f), end), width = { k -> WIDTH * (1f - k) + 0.02f },
                color = { k -> FxLib.alpha(FxLib.mix(palette.core, palette.main, k), ALPHA * fade) }, mode = FxMode.RIBBON, seed = seed + i,
            )
        }
    }

    override fun over(f: FxFrame) {
        val fade = ZeusFx.cubicFade(time(f), life * 0.6f)
        if (fade <= 0.01f) return
        FxDraw.glow(f, center, GLOW_SIZE * (0.5f + 0.5f * fade), FxLib.alpha(palette.core, GLOW_ALPHA * fade), seed)
    }

    companion object {
        const val CENTER_UP = 0.5
        const val FLAT_RAYS = 6
        const val RISING_RAYS = 2
        const val RISING_SHARE = 0.8f
        const val FLAT_TILT = 0.12
        const val GROW_TICKS = 5f
        const val WIDTH = 0.3f
        const val ALPHA = 0.7f
        const val GLOW_SIZE = 1.6f
        const val GLOW_ALPHA = 0.55f
    }
}

// =====================================================================================================================
// zeus.storm_wall – Sturmwand und Regen der Waage
// =====================================================================================================================

/**
 * Zwei Formen (`extra`):
 * - `line` (Sturmwand, Phase II): pos = Hof-Mitte (Boden), dir = Laufachse, a = reach, b = Mitte der Lücke (quer),
 *   c = halbe Lückenbreite, d = Höhe, life = Lauf + 1. Die Wand steht quer zur Achse und läuft in `life − 1` Ticks von
 *   −reach nach +reach (genau wie der Treffer am Server), auf die Sehne des Hofs gekürzt. Regenwand (`WATER`, wenig
 *   Schaum), fallende Regenstriche, an den Kanten der Lücke feine goldene Pfosten (Gold = sicher).
 * - `ring` (Waage, Phase III): pos = Mitte der Schale, a = ihr Radius, b = Hof-Radius, d = Regenhöhe. Regen fällt
 *   **außerhalb** der Schale, ein weicher Regenschleier über Kopfhöhe am Rand und Dunst am Boden außen; die goldene
 *   Schale (Ansage) bleibt frei.
 */
private class ZeusStormWallFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(4)) {
    private val ring = args.extra == "ring"
    private val origin = args.pos
    private val axis = FxLib.flat(args.dir, Vec3d(1.0, 0.0, 0.0))
    private val across = Vec3d(-axis.z, 0.0, axis.x)
    private val reach = args.a.coerceIn(1f, 64f)
    private val gap = args.b
    private val half = args.c.coerceAtLeast(0f)
    private val height = args.d.coerceIn(1f, 16f)
    private val bowlRadius = args.a.coerceIn(1f, 20f)
    private val palette = args.palette
    private val gold = Palette.of(Vfx.Style.GOLD)
    private val seed = args.seed.toInt()
    private val rain = Random(args.seed).let { r -> List(if (ring) RING_STREAKS else LINE_STREAKS) { floatArrayOf(r.nextFloat(), r.nextFloat(), r.nextFloat()) } }
    private val color = FxLib.mix(palette.edge, STORM_GREY, 0.45f)

    private fun point(at: Double, side: Double, up: Double = 0.0): Vec3d =
        origin.add(axis.multiply(at)).add(across.multiply(side)).add(0.0, up, 0.0)

    override fun energy(f: FxFrame) {
        if (ring) drawRing(f) else drawLine(f)
    }

    private fun drawLine(f: FxFrame) {
        val t = time(f)
        val env = ZeusFx.envelope(t, life, LINE_FADE_IN, LINE_FADE_OUT)
        if (env <= 0.01f) return
        val travel = (life - 1).coerceAtLeast(1).toFloat()
        val at = -reach + 2.0 * reach * (t / travel).coerceIn(0f, 1f)
        // Sehne des Hofs an dieser Stelle (Kreis vom Radius reach um die Mitte).
        val chord = sqrt((reach.toDouble() * reach - at * at).coerceAtLeast(0.0))
        if (chord < 1.0) return
        val from = point(at, -chord)
        val to = point(at, chord)
        val s = ((gap - half + chord) / (2 * chord)).toFloat().coerceIn(0f, 1f)
        val e = ((gap + half + chord) / (2 * chord)).toFloat().coerceIn(0f, 1f)
        FxDraw.wall(f, from, to, height, FxLib.alpha(color, LINE_ALPHA * env), FxMode.WATER, gaps = listOf(s to e), seed = seed, progress = LINE_FOAM)
        // Regenstriche dicht an der Front, außerhalb der Lücke.
        for ((i, r) in rain.withIndex()) {
            val side = -chord + r[0] * 2 * chord
            if (abs(side - gap) < half + 0.4) continue
            val fall = frac(t * RAIN_SPEED + r[1])
            val y = height * (1f - fall)
            val depth = (r[2] - 0.5f) * LINE_RAIN_DEPTH
            val top = point(at + depth, side, y.toDouble())
            val bottom = top.add(axis.multiply(RAIN_SLANT)).add(0.0, -RAIN_LENGTH, 0.0)
            val a = RAIN_ALPHA * env * sin(fall * PI.toFloat())
            if (a > 0.01f) streak(f, top, bottom, a, seed + i)
        }
        // Goldene Pfosten an den Kanten der Lücke (sicher).
        for (edge in listOf(gap - half, gap + half)) {
            if (abs(edge) >= chord) continue
            val foot = point(at, edge.toDouble(), 0.05)
            FxDraw.ribbon(
                f, listOf(foot, foot.add(0.0, height * 0.5, 0.0), foot.add(0.0, height * POST_SHARE.toDouble(), 0.0)),
                width = { k -> POST_WIDTH * (1f - 0.5f * k) }, color = { k -> FxLib.alpha(gold.main, POST_ALPHA * env * (1f - 0.6f * k)) },
                mode = FxMode.RIBBON, seed = seed + 7,
            )
        }
    }

    private fun drawRing(f: FxFrame) {
        val t = time(f)
        val env = ZeusFx.envelope(t, life, RING_FADE_IN, RING_FADE_OUT)
        if (env <= 0.01f) return
        val r0 = bowlRadius + RING_RAIN_GAP
        // Regenschleier über Kopfhöhe am Rand der Schale (verdeckt die Schale am Boden nicht).
        for (i in 0 until VEIL_SEGMENTS) {
            val a0 = i * PI * 2 / VEIL_SEGMENTS
            val a1 = (i + 1) * PI * 2 / VEIL_SEGMENTS
            val p0 = origin.add(ZeusFx.horizontal(a0).multiply((bowlRadius + VEIL_GAP).toDouble())).add(0.0, VEIL_BASE, 0.0)
            val p1 = origin.add(ZeusFx.horizontal(a1).multiply((bowlRadius + VEIL_GAP).toDouble())).add(0.0, VEIL_BASE, 0.0)
            FxDraw.wall(f, p0, p1, VEIL_HEIGHT, FxLib.alpha(color, VEIL_ALPHA * env), FxMode.WATER, seed = seed + i, progress = 0.05f)
        }
        // Regen außerhalb.
        val wind = t * WIND_TURN
        val slant = ZeusFx.horizontal(wind.toDouble()).multiply(RAIN_SLANT)
        for ((i, r) in rain.withIndex()) {
            val angle = r[0] * PI * 2
            val dist = r0 + r[2] * RING_RAIN_WIDTH
            val fall = frac(t * RAIN_SPEED + r[1])
            val y = height * (1f - fall)
            val top = origin.add(ZeusFx.horizontal(angle).multiply(dist.toDouble())).add(0.0, y.toDouble(), 0.0)
            val bottom = top.add(slant).add(0.0, -RAIN_LENGTH, 0.0)
            val a = RAIN_ALPHA * env * sin(fall * PI.toFloat())
            if (a > 0.01f) streak(f, top, bottom, a, seed + i)
        }
        // Dunst am Boden außen.
        for (i in 0 until MIST_PATCHES) {
            val angle = i * PI * 2 / MIST_PATCHES + t * MIST_TURN
            val at = origin.add(ZeusFx.horizontal(angle).multiply((bowlRadius + MIST_DISTANCE).toDouble())).add(0.0, 0.04, 0.0)
            FxDraw.flat(f, at, ZeusFx.UP, MIST_SIZE, FxLib.alpha(color, MIST_ALPHA * env), FxMode.DUST, rotation = t * 0.004f + i, progress = 0.6f, seed = seed + 40 + i)
        }
    }

    private fun streak(f: FxFrame, top: Vec3d, bottom: Vec3d, alpha: Float, seed: Int) {
        FxDraw.ribbon(
            f, listOf(top, bottom), width = { k -> RAIN_WIDTH * (0.4f + 0.6f * k) },
            color = { k -> FxLib.alpha(palette.alt, alpha * (0.4f + 0.6f * k)) }, mode = FxMode.RIBBON, seed = seed,
        )
    }

    /** Nachkommaanteil (die Striche blenden an der Naht auf 0 aus, kein Sprung zu sehen). */
    private fun frac(x: Float): Float = ((x % 1f) + 1f) % 1f

    companion object {
        const val STORM_GREY = 0xFF9AA6B8.toInt()
        const val LINE_ALPHA = 0.42f
        const val LINE_FOAM = 0.3f
        const val LINE_FADE_IN = 3f
        const val LINE_FADE_OUT = 5f
        const val LINE_STREAKS = 44
        const val LINE_RAIN_DEPTH = 2.4f
        const val RAIN_SPEED = 0.09f
        const val RAIN_LENGTH = 1.3
        const val RAIN_SLANT = 0.35
        const val RAIN_WIDTH = 0.045f
        const val RAIN_ALPHA = 0.38f
        const val POST_SHARE = 0.9f
        const val POST_WIDTH = 0.11f
        const val POST_ALPHA = 0.7f
        const val RING_STREAKS = 72
        const val RING_FADE_IN = 20f
        const val RING_FADE_OUT = 14f
        const val RING_RAIN_GAP = 1.2f
        const val RING_RAIN_WIDTH = 16f
        const val VEIL_SEGMENTS = 32
        const val VEIL_GAP = 0.6f
        const val VEIL_BASE = 3.0
        const val VEIL_HEIGHT = 5f
        const val VEIL_ALPHA = 0.18f
        const val WIND_TURN = 0.004f
        const val MIST_PATCHES = 10
        const val MIST_TURN = 0.002
        const val MIST_DISTANCE = 4f
        const val MIST_SIZE = 3.4f
        const val MIST_ALPHA = 0.26f
    }
}

// =====================================================================================================================
// zeus.rod – geladener Pfeiler
// =====================================================================================================================

/**
 * Kleiner, ruhiger Schein am Fuß eines geladenen Pfeilers (die goldene Rune am Boden bleibt, wie sie ist; der Schein
 * liegt nicht auf ihr, sondern vor der Kupferwand zur Kamera hin). Atmet mit 0,5 Hz; alle 2 s kriecht ein feiner Funke
 * die Säule hinauf (ein- und ausgeblendet, kein Sprung). Der Server schickt den Effekt je Runen-Zyklus neu (überlappend,
 * Ein- und Ausblenden gleich lang). Wird der Pfeiler oxidiert (Entladung), blendet der Schein sofort weich aus.
 */
private class ZeusRodFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(6)) {
    private val foot = args.pos
    private val block = BlockPos.ofFloored(args.pos.x, args.pos.y + 0.5, args.pos.z)
    private val palette = args.palette
    private val seed = args.seed.toInt()
    private var dying = -1f

    override fun tick(world: ClientWorld) {
        if (dying < 0f && world.getBlockState(block).isOf(Blocks.WAXED_OXIDIZED_COPPER)) dying = age.toFloat()
    }

    override fun finished(): Boolean = dying >= 0f && age - dying > DIE_TICKS

    private fun env(t: Float): Float {
        val die = if (dying < 0f) 1f else Ease.inOut(1f - (t - dying) / DIE_TICKS)
        return ZeusFx.envelope(t, life, CROSS_FADE, CROSS_FADE) * die
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val env = env(t)
        if (env <= 0.01f) return
        val toCam = FxLib.flat(f.camPos.subtract(foot), Vec3d(1.0, 0.0, 0.0))
        val breath = 1f + BREATH * sin(t * BREATH_RATE)
        FxDraw.glow(f, foot.add(toCam.multiply(FACE_OFFSET)).add(0.0, GLOW_UP, 0.0), GLOW_SIZE * breath, FxLib.alpha(palette.main, GLOW_ALPHA * env), seed)
        // Ein feiner Funke kriecht die Säule hinauf.
        val cycle = t / CRAWL_TICKS
        val round = cycle.toInt()
        // Der Funke ist an der Naht zweier Runden unsichtbar (sin(π · phase) = 0): kein Sprung.
        val phase = cycle - round
        val side = ZeusFx.horizontal(Math.atan2(toCam.z, toCam.x) + (if (round % 2 == 0) 0.7 else -0.7))
        val head = CRAWL_LOW + (CRAWL_HIGH - CRAWL_LOW) * Ease.inOut(phase)
        val a = CRAWL_ALPHA * env * sin(phase * PI.toFloat())
        if (a <= 0.01f) return
        val points = List(CRAWL_POINTS) { j ->
            val y = head - j * CRAWL_STEP
            foot.add(side.multiply(FACE_OFFSET)).add(0.0, y.coerceAtLeast(0.05), 0.0)
                .add(side.crossProduct(ZeusFx.UP).multiply(sin(t * 0.4f + j * 1.9f + round).toDouble() * CRAWL_WOBBLE))
        }
        FxDraw.ribbon(
            f, points, width = { k -> CRAWL_WIDTH * (1f - 0.7f * k) }, color = { k -> FxLib.alpha(palette.core, a * (1f - k)) },
            mode = FxMode.BOLT, seed = seed + round,
        )
    }

    companion object {
        /** Gleich lang wie die Überlappung zweier Zyklen am Server (Rune 40 + 4 Ticks): die Summe bleibt gleich hell. */
        const val CROSS_FADE = 4f
        const val DIE_TICKS = 8f
        const val FACE_OFFSET = 0.62
        const val GLOW_UP = 0.55
        const val GLOW_SIZE = 0.85f
        const val GLOW_ALPHA = 0.3f
        const val BREATH = 0.12f
        /** 0,5 Hz in rad/Tick. */
        const val BREATH_RATE = (PI * 2 * 0.5 / 20).toFloat()
        const val CRAWL_TICKS = 40f
        const val CRAWL_LOW = 0.3
        const val CRAWL_HIGH = 4.6
        const val CRAWL_POINTS = 5
        const val CRAWL_STEP = 0.22
        const val CRAWL_WOBBLE = 0.05
        const val CRAWL_WIDTH = 0.06f
        const val CRAWL_ALPHA = 0.42f
    }
}

// =====================================================================================================================
// zeus.nike – Nike als Lichtgestalt (Bühne zeus.defeat)
// =====================================================================================================================

/**
 * Nike schwebt als Lichtgestalt über den Sieger herab (Kopf, Gewand als weiches Band, zwei Flügel aus Licht, die ruhig
 * mit 0,25 Hz schlagen), hält den goldenen Kranz und senkt ihn in [args].a Ticks auf seinen Kopf; dort übernimmt das
 * Kranz-Requisit des Servers (der Ring blendet in [RING_HANDOVER] Ticks aus). Danach steigt sie auf und löst sich auf.
 * Gold ist hier Ziel/Lohn. Kein neues Modell.
 */
private class ZeusNikeFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(40)) {
    private val feet = args.pos
    private val wreathAt = args.a.coerceIn(10f, (life - 10).toFloat())
    private val gold = Palette.of(Vfx.Style.GOLD)
    private val seed = args.seed.toInt()
    private val head = feet.add(0.0, VICTOR_HEAD, 0.0)

    /** Höhe der Gestalt (Mitte) über den Füßen: schwebt herab, hält, steigt nach dem Kranz wieder auf. */
    private fun lift(t: Float): Double {
        val down = Ease.outCubic(t / DESCEND_TICKS)
        val up = Ease.inCubic((t - wreathAt - RISE_DELAY) / (life - wreathAt - RISE_DELAY))
        return HIGH + (HOVER - HIGH) * down + RISE * up
    }

    private fun presence(t: Float): Float {
        val fadeIn = Ease.inOut(t / FADE_IN)
        val fadeOut = 1f - Ease.inOut((t - wreathAt - RISE_DELAY) / (life - wreathAt - RISE_DELAY))
        return fadeIn * fadeOut
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val p = presence(t)
        if (p <= 0.01f) return
        val center = feet.add(0.0, lift(t), 0.0)
        val right = Vec3d(f.right.x.toDouble(), 0.0, f.right.z.toDouble()).let { if (it.lengthSquared() < 1e-6) Vec3d(1.0, 0.0, 0.0) else it.normalize() }
        val back = Vec3d(f.forward.x.toDouble(), 0.0, f.forward.z.toDouble()).let { if (it.lengthSquared() < 1e-6) Vec3d.ZERO else it.normalize() }
        // Gewand: weiches Band von den Schultern zum Saum.
        val shoulders = center.add(0.0, BODY_HALF * 0.6, 0.0)
        val hem = center.add(0.0, -BODY_HALF, 0.0).add(0.0, sin(t * 0.06f) * 0.05, 0.0)
        FxDraw.ribbon(
            f, List(5) { FxLib.lerp(shoulders, hem, it / 4f) }, width = { k -> ROBE_TOP + (ROBE_HEM - ROBE_TOP) * k },
            color = { k -> FxLib.alpha(FxLib.mix(gold.core, gold.main, k), ROBE_ALPHA * p * (1f - 0.5f * k)) }, mode = FxMode.RIBBON,
            scroll = t * 0.05f, seed = seed,
        )
        // Flügel: zwei Bögen aus Licht, ruhiger Schlag.
        val beat = sin(t * WING_RATE) * WING_BEAT
        for (sideSign in listOf(-1.0, 1.0)) {
            val side = right.multiply(sideSign)
            val points = List(WING_POINTS) { j ->
                val k = j / (WING_POINTS - 1.0)
                shoulders.add(side.multiply(WING_ROOT + WING_SPAN * k))
                    .add(0.0, WING_LIFT * sin(k * PI * 0.85) + beat * k, 0.0)
                    .add(back.multiply(WING_BACK * k))
            }
            FxDraw.ribbon(
                f, points, width = { k -> WING_WIDTH * (1f - 0.75f * k) + 0.03f },
                color = { k -> FxLib.alpha(FxLib.mix(gold.core, gold.main, k), WING_ALPHA * p * (1f - 0.4f * k)) },
                mode = FxMode.RIBBON, scroll = t * 0.04f, seed = seed + if (sideSign < 0) 1 else 2,
            )
        }
        // Schein über dem Sieger.
        FxDraw.glow(f, head.add(0.0, 0.3, 0.0), HALO_SIZE, FxLib.alpha(gold.main, HALO_ALPHA * p), seed + 3)
        // Der Kranz: zwischen ihren Händen, wird auf den Kopf gesenkt.
        val ringK = Ease.inOut((t - (wreathAt - RING_LOWER)) / RING_LOWER)
        val hands = center.add(0.0, -BODY_HALF * 0.35, 0.0).add(back.multiply(-0.2))
        val ringAt = FxLib.lerp(hands, head, ringK)
        val ringFade = (1f - ((t - wreathAt) / RING_HANDOVER).coerceIn(0f, 1f)) * Ease.inOut(t / FADE_IN)
        if (ringFade > 0.01f) {
            FxDraw.mesh(f, FxMeshes.TORUS, FxDraw.place(ringAt, RING_RADIUS), FxLib.alpha(gold.main, RING_ALPHA * ringFade), FxMode.SHELL, seed = seed + 4)
        }
    }

    override fun over(f: FxFrame) {
        val t = time(f)
        val p = presence(t)
        if (p <= 0.01f) return
        val center = feet.add(0.0, lift(t), 0.0)
        FxDraw.glow(f, center.add(0.0, BODY_HALF * 0.95, 0.0), HEAD_SIZE, FxLib.alpha(gold.core, HEAD_ALPHA * p), seed + 5)
        FxDraw.glow(f, center, BODY_GLOW, FxLib.alpha(gold.core, BODY_GLOW_ALPHA * p), seed + 6)
    }

    companion object {
        /** Kopf des Siegers über den Füßen (Spielerhöhe + Kranz-Höhe aus `ZeusTimings`). */
        const val VICTOR_HEAD = 1.8 + 0.55
        const val HIGH = 6.0
        const val HOVER = 3.9
        const val RISE = 3.0
        const val DESCEND_TICKS = 28f
        const val RISE_DELAY = 6f
        const val FADE_IN = 12f
        const val BODY_HALF = 0.9
        const val ROBE_TOP = 0.22f
        const val ROBE_HEM = 0.55f
        const val ROBE_ALPHA = 0.42f
        const val WING_POINTS = 7
        const val WING_ROOT = 0.15
        const val WING_SPAN = 1.25
        const val WING_LIFT = 0.55
        const val WING_BACK = 0.3
        const val WING_WIDTH = 0.38f
        const val WING_ALPHA = 0.38f
        /** 0,25 Hz in rad/Tick. */
        const val WING_RATE = (PI * 2 * 0.25 / 20).toFloat()
        const val WING_BEAT = 0.16
        const val HALO_SIZE = 2.2f
        const val HALO_ALPHA = 0.2f
        const val HEAD_SIZE = 0.42f
        const val HEAD_ALPHA = 0.6f
        const val BODY_GLOW = 1.3f
        const val BODY_GLOW_ALPHA = 0.25f
        const val RING_RADIUS = 0.42f
        const val RING_ALPHA = 0.85f
        const val RING_LOWER = 14f
        const val RING_HANDOVER = 6f
    }
}

// =====================================================================================================================
// zeus.sky – der Himmel gehört dem Kampf (Dauer-Effekt am Wesen)
// =====================================================================================================================

/**
 * Lichtrisse in einer Kuppel über dem Thronhof (Vorbild Void `SkyRiftEffect`): **Zahl aus der Phase** (I: 1, II: 3,
 * III: 5), **Breite aus dem Lebensanteil der laufenden Leiste** (voll: 1, leer: 1 + [WIDEN]); beim Sieg (`YIELD`)
 * schließen sie sich. Alles aus getrackten Daten (Phase, Leben, Aktion), Nachzügler sehen sofort dasselbe. Ruhig:
 * Öffnen und Breite folgen weich (Zeitkonstante [OPEN_RATE]), Atmen ≤ [BREATH_HZ] Hz, der Baustein [Tear] läuft mit
 * [TEAR_TIME] verlangsamter Zeit (sein Atmen damit < 0,3 Hz), kein Flackern. Rand und Schein in `THUNDER`, nur der feine
 * Kern ist weißgold. Darunter eine kaum sichtbare Kuppel (`SKY_DOME`, Modus `SHELL`); `VfxClouds`/`SkyMood` bleiben.
 * Im Olymp sitzt die Kuppel fest über der Mitte des Thronhofs, sonst über der ersten Lage des Wesens (folgt langsam).
 */
private class ZeusSkyFx(private val zeus: Entity) : FxEffect(LIFE) {
    private val palette = Palette.of(Vfx.Style.THUNDER)
    private val shapes = List(RIFTS) { Tear.Shape(SEED + it * 31, 17) }
    private val opens = FloatArray(RIFTS)
    private var width = 1f
    private var fade = 0f
    private var anchor: Vec3d = zeus.entityPos

    override fun tick(world: ClientWorld) {
        val court = OlympusLayout.F.centerVec
        anchor = if (world.registryKey == OlympusLayout.WORLD && flatDistance(zeus.entityPos, court) < COURT_RANGE) {
            court
        } else {
            anchor.lerp(zeus.entityPos, FOLLOW)
        }
        val boss = zeus as? ZeusEntity
        val phase = (boss?.phase ?: 0).coerceIn(0, 2)
        val yielding = boss?.kit?.action == ZeusAction.YIELD || boss?.kit?.action == ZeusAction.POINT
        val count = if (yielding) 0 else COUNT_BY_PHASE[phase]
        for (i in 0 until RIFTS) {
            val target = if (i < count) 1f else 0f
            opens[i] += (target - opens[i]) * OPEN_RATE
        }
        val living = zeus as? net.minecraft.entity.LivingEntity
        val share = if (living != null && living.maxHealth > 0f) living.health / living.maxHealth else 1f
        val bar = ((share - BAR_FLOOR[phase]) * BARS).coerceIn(0f, 1f)
        width += (1f + WIDEN * (1f - bar) - width) * WIDTH_RATE
        fade += (1f - fade) * FADE_RATE
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        if (fade <= 0.01f) return
        // Kuppel nur von innen (von außen würde der Fresnel-Rand zur Blase).
        if (flatDistance(f.camPos, anchor) < DOME_RADIUS * DOME_INSIDE) {
            val storm = opens.sum() / RIFTS
            FxDraw.mesh(
                f, FxMeshes.SKY_DOME, FxDraw.place(anchor, DOME_RADIUS), FxLib.alpha(palette.edge, (DOME_ALPHA + DOME_STORM * storm) * fade),
                FxMode.SHELL, seed = SEED,
            )
        }
        for (i in 0 until RIFTS) {
            val o = opens[i] * fade
            if (o < 0.01f) continue
            val r = rift(i, t)
            val breath = 1f + BREATH * sin(t * BREATH_RATE + i * 1.7f)
            FxDraw.glow(f, r.center, r.length * GLOW_SHARE, FxLib.alpha(palette.main, GLOW_ALPHA * o), SEED + i)
            Tear.draw(f, r.center, r.along, r.across, r.length, r.width * breath, o, shapes[i], palette, t * TEAR_TIME, HOT + HOT_WIDE * (width - 1f))
        }
    }

    /** Weißgoldener Kern jedes Risses (nach dem Bildschirm-Pass, damit er scharf bleibt). */
    override fun over(f: FxFrame) {
        val t = time(f)
        for (i in 0 until RIFTS) {
            val o = opens[i] * fade
            if (o < 0.05f) continue
            val r = rift(i, t)
            val points = Tear.points(r.center, r.along, r.across, r.length, o, shapes[i], t * TEAR_TIME)
            FxDraw.ribbon(
                f, points, width = { k -> r.width * CORE_SHARE * o * ZeusFx.lens(k) }, color = { k -> FxLib.alpha(CORE_COLOR, CORE_ALPHA * o * ZeusFx.lens(k)) },
                mode = FxMode.RIBBON, seed = SEED + 50 + i,
            )
        }
    }

    private class Rift(val center: Vec3d, val along: Vec3d, val across: Vec3d, val length: Float, val width: Float)

    /** Lage des Risses [i] auf der Kuppel: feste Höhe und Neigung, Richtung wandert sehr langsam. */
    private fun rift(i: Int, t: Float): Rift {
        val az = AZIMUTH[i] + t * DRIFT
        val el = ELEVATION[i]
        val n = Vec3d(cos(az) * cos(el), sin(el), sin(az) * cos(el))
        val center = anchor.add(n.multiply(RIFT_RADIUS))
        val tangent = Vec3d(-sin(az), 0.0, cos(az))
        val up = n.crossProduct(tangent).normalize()
        val along = tangent.multiply(cos(TILT[i])).add(up.multiply(sin(TILT[i]))).normalize()
        val across = n.crossProduct(along).normalize()
        val length = (LENGTH[i] * (0.6f + 0.4f * opens[i]) * width)
        return Rift(center, along, across, length, RIFT_WIDTH * width)
    }

    private fun flatDistance(a: Vec3d, b: Vec3d): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    companion object {
        /** Lebt praktisch so lange wie das Wesen (FxAttach entfernt den Effekt mit ihm). */
        const val LIFE = 20 * 60 * 120
        const val SEED = 7331
        const val RIFTS = 5
        val COUNT_BY_PHASE = intArrayOf(1, 3, 5)
        /** Untergrenze der Leiste je Phase (Anteil des ganzen Lebens) und Leistenzahl (`ZeusTimings.PHASE_2/3`). */
        val BAR_FLOOR = floatArrayOf(2f / 3f, 1f / 3f, 0f)
        const val BARS = 3f
        const val WIDEN = 0.8f
        const val OPEN_RATE = 0.03f
        const val WIDTH_RATE = 0.02f
        const val FADE_RATE = 0.025f
        const val COURT_RANGE = 70.0
        const val FOLLOW = 0.01
        const val DOME_RADIUS = 58f
        const val DOME_INSIDE = 0.9
        const val DOME_ALPHA = 0.1f
        const val DOME_STORM = 0.08f
        const val RIFT_RADIUS = 46.0
        val AZIMUTH = doubleArrayOf(4.71, 3.55, 5.9, 2.4, 0.9)
        val ELEVATION = doubleArrayOf(1.05, 0.82, 0.88, 0.7, 0.76)
        val TILT = doubleArrayOf(0.35, -0.5, 0.6, -0.25, 0.15)
        val LENGTH = floatArrayOf(13f, 10f, 11f, 9f, 9.5f)
        const val RIFT_WIDTH = 1.25f
        /** Sehr langsames Wandern der Risse (rad/Tick, eine Runde in rund 2,6 h). */
        const val DRIFT = 0.00004
        const val BREATH = 0.08f
        const val BREATH_HZ = 0.22f
        const val BREATH_RATE = (PI * 2 * BREATH_HZ / 20).toFloat()
        const val TEAR_TIME = 0.3f
        const val HOT = 0.2f
        const val HOT_WIDE = 0.4f
        const val GLOW_SHARE = 0.55f
        const val GLOW_ALPHA = 0.1f
        const val CORE_SHARE = 0.22f
        const val CORE_ALPHA = 0.75f
        /** Weißgold, nur der feine Kern. */
        const val CORE_COLOR = 0xFFFFF4DC.toInt()
    }
}

