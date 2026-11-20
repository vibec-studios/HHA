package dev.henny.hha.client.vfx.fx.boss

import dev.henny.hha.HhaEntities
import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.model.BrontesAnchors
import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxAttach
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMesh
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.client.vfx.fx.FxRegistry
import dev.henny.hha.client.vfx.fx.lib.SparkSet
import dev.henny.hha.client.vfx.lerpColor
import dev.henny.hha.client.vfx.withAlpha
import dev.henny.hha.entity.boss.DuelBossEntity
import dev.henny.hha.logic.Vfx
import dev.henny.hha.world.olymp.OlympusLayout
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.registry.Registries
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Client-Effekte von Brontes (P09, Präfix `brontes.`). Jeder Angriff hat sein eigenes Bild (PLAN 2.6); alle Formen
 * weich (Flächen, Bänder, Röhren, glatte Meshes), Farben aus der Gefahrenpalette des Kampfes (Glut), nie Gold.
 *
 * | ID | Server (BrontesEntity) | Kodierung | Bild |
 * | --- | --- | --- | --- |
 * | `brontes.hammer` | Hammertreffer, `impact` | pos Kopf am Boden, dir Schlag, a Ring-Radius | Stoßring, Marmorstaub, Brandfleck mit Glut, kurzer Kernschein |
 * | `brontes.splash` | Glutwurf-Einschlag, `impact` | pos Ziel, a Radius | wie Hammer, kleiner, mit Glutspritzern |
 * | `brontes.swing` | Rundschwung, `impact` | pos Boss + Höhe, dir Mitte, a Radius, b halber Winkel, entity Boss | Glutband am Hammerkopf (FLAME), Funken am Ende |
 * | `brontes.bellows` | Blasebalg, `impact` | pos Mund (Rückfall), dir Mitte, a Reichweite, b halber Winkel, entity Boss | Funkenstrom im Kegel aus dem Mund, schwache Hitzewolke |
 * | `brontes.ingot` | liegender Rohling | pos Lage, entity Rohling, life Glühdauer | leises Hitzeflimmern, atmender Schein (kein Pass) |
 * | `brontes.grates` | Roste (Phase II) | pos Mitte, a außen, b innen, c Ansage, d Brand, extra Sektoren `0,2,4` | Glut unter den Gittern, wächst unter der Ansage |
 * | `brontes.ring` | Glutring des Keils, `impact` | pos Mitte, a Radius, b Laufzeit | niedrige Flammenwand an der Front + Bodenwelle |
 * | `brontes.wedge` | Keil-Einschlag, `impact` | pos Ziel, a Radius | Glutsäule, Stoßring, Brandfleck, Funken |
 * | `brontes.stuck` | Hammer steckt (Ü4, P16) | pos Hammerkopf, dir Schlag, a Splitter-Radius, life Fenster | ruhiger Staubschleier, vier atmende Glutadern im Boden |
 * | `brontes.anvil_crack` | Riss im Amboss (Ü4, P16) | pos Ambossoberkante, a Zahl der Risse | glühender Sprung über die Oberkante, Staubstoß, wenige Funken |
 * | `brontes.anvil_break` | Amboss bricht (Ü4, P16), `impact` | pos frühere Oberkante, a Trümmer-Radius | Stoßring am Stumpf, dichter Staub, Funken, Glutfleck auf den Trümmern |
 * | Esse (`FxAttach`) | – | aus dem Wesen (Leben, Phase) | Glutschein und aufsteigende Funken an der Esse |
 */
object BrontesFx {
    const val HAMMER = "brontes.hammer"
    const val SPLASH = "brontes.splash"
    const val SWING = "brontes.swing"
    const val BELLOWS = "brontes.bellows"
    const val INGOT = "brontes.ingot"
    const val GRATES = "brontes.grates"
    const val RING = "brontes.ring"
    const val WEDGE = "brontes.wedge"

    fun init() {
        FxRegistry.register(HAMMER) { BrontesHammerImpactFx(it, splash = false) }
        FxRegistry.register(SPLASH) { BrontesHammerImpactFx(it, splash = true) }
        FxRegistry.register(SWING) { BrontesSwingTrailFx(it) }
        FxRegistry.register(BELLOWS) { BrontesBellowsFx(it) }
        FxRegistry.register(INGOT) { BrontesIngotShimmerFx(it) }
        FxRegistry.register(GRATES) { BrontesGratesFx(it) }
        FxRegistry.register(RING) { BrontesForgeRingFx(it) }
        FxRegistry.register(WEDGE) { BrontesWedgeImpactFx(it) }
        FxRegistry.register(STUCK) { BrontesStuckFx(it) }
        FxRegistry.register(ANVIL_CRACK) { BrontesAnvilCrackFx(it, broken = false) }
        FxRegistry.register(ANVIL_BREAK) { BrontesAnvilCrackFx(it, broken = true) }
        FxAttach.register(HhaEntities.BRONTES) { BrontesForgeGlowFx(it) }
    }

    // --- Überarbeitung 4 (P16): Hammer steckt, Amboss bricht -------------------------------------------------------
    /** Hammer steckt: pos Hammerkopf am Boden, dir Schlag, a Splitter-Radius, life Fenster. */
    const val STUCK = "brontes.stuck"
    /** Riss im Amboss: pos Mitte der Ambossoberkante, a Zahl der Risse (1, 2). */
    const val ANVIL_CRACK = "brontes.anvil_crack"
    /** Amboss bricht: pos Mitte der (früheren) Oberkante, a Trümmer-Radius, `impact`. */
    const val ANVIL_BREAK = "brontes.anvil_break"
}

// ---------------------------------------------------------------------------------------------------------------------
// Gemeinsame Helfer
// ---------------------------------------------------------------------------------------------------------------------

private val UP = Vec3d(0.0, 1.0, 0.0)

/** Marmor- bzw. Steinstaub (neutral, keine Palette) und Brandfleck. */
private const val DUST = 0xFFD9D2C6.toInt()
private const val SCORCH = 0xFF1C0E07.toInt()

private fun smooth(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun alpha(argb: Int, a: Float): Int = withAlpha(argb, a)

private fun mix(a: Int, b: Int, t: Float): Int = lerpColor(a, b, t)

/** Oberkante des Bodens unter [p] (wie `FxLib.groundY`: 2 darüber bis 4 darunter), sonst [p].y. */
private fun ground(world: ClientWorld, p: Vec3d): Double {
    val base = BlockPos.ofFloored(p.x, p.y + 0.5, p.z)
    for (dy in 2 downTo -4) {
        val pos = base.up(dy)
        val shape = world.getBlockState(pos).getCollisionShape(world, pos)
        if (shape.isEmpty) continue
        val above = pos.up()
        if (!world.getBlockState(above).getCollisionShape(world, above).isEmpty) continue
        return pos.y + shape.getMax(Direction.Axis.Y)
    }
    return p.y
}

/** Vorn/rechts aus einer waagerechten Richtung (Konvention API.md: rechts = (−f.z, 0, f.x)). */
private fun flatDir(v: Vec3d): Vec3d {
    val h = Vec3d(v.x, 0.0, v.z)
    return if (h.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else h.normalize()
}

private fun rightOf(f: Vec3d): Vec3d = Vec3d(-f.z, 0.0, f.x)

/**
 * Offener Zylindermantel um +Y (Mitte unten im Ursprung) als [FxMesh] für Band-Modi (FLAME): u = Weg in Blöcken
 * rundherum, v = 0 Fuß … 1 Krone. So wird eine runde Flammenwand ohne Nähte (FxDraw.wall blendet Enden aus).
 */
private fun ringMesh(radius: Float, height: Float, segments: Int): FxMesh {
    val n = segments.coerceIn(8, 96)
    val pos = FloatArray(n * 4 * 3)
    val nrm = FloatArray(n * 4 * 3)
    val uv = FloatArray(n * 4 * 2)
    val step = (2.0 * PI / n).toFloat()
    var p = 0
    var q = 0
    var w = 0
    fun corner(angle: Float, y: Float, v: Float, along: Float) {
        val cx = cos(angle)
        val cz = sin(angle)
        pos[p++] = cx * radius; pos[p++] = y; pos[p++] = cz * radius
        nrm[q++] = cx; nrm[q++] = 0f; nrm[q++] = cz
        uv[w++] = along; uv[w++] = v
    }
    for (i in 0 until n) {
        val a0 = i * step
        val a1 = (i + 1) * step
        val u0 = a0 * radius
        val u1 = a1 * radius
        corner(a0, 0f, 0f, u0)
        corner(a1, 0f, 0f, u1)
        corner(a1, height, 1f, u1)
        corner(a0, height, 1f, u0)
    }
    return FxMesh(pos, nrm, uv)
}

/** Runde Flammenwand mit Fuß in [center]. */
private fun flameRing(f: FxFrame, center: Vec3d, radius: Float, height: Float, argb: Int, seed: Int, progress: Float = 1f) {
    if (radius < 0.05f || height < 0.02f || (argb ushr 24) < 2) return
    val mesh = ringMesh(radius, height, (radius * RING_SEGMENTS_PER_BLOCK).toInt() + 12)
    FxDraw.mesh(f, mesh, FxDraw.place(center), argb, FxMode.FLAME, seed = seed, progress = progress)
}

private const val RING_SEGMENTS_PER_BLOCK = 3f

// ---------------------------------------------------------------------------------------------------------------------
// brontes.hammer / brontes.splash
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Einschlag des Hammers (bzw. des glühenden Rohlings): Stoßring läuft in [RING_TICKS] aus, Marmorstaub hebt sich und
 * sinkt, ein Brandfleck mit Glut kühlt in [HEAT_TICKS] ab, ein kurzer Kernschein (kubisch). Die Risse und echten
 * Trümmer schickt der Server getrennt (`core.cracks`, `core.debris`). Beim Spritzer fliegen Glutfunken.
 */
private class BrontesHammerImpactFx(args: FxArgs, private val splash: Boolean) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val pal = args.palette
    private val radius = args.a.coerceIn(0.8f, 6f)
    private val seed = args.seed.toInt()
    private val at = Vec3d(args.pos.x, ground(args.world, args.pos) + 0.03, args.pos.z)
    private val sparks = if (splash) SparkSet(SPLASH_SPARKS, SPLASH_SPARK_SPEED, UP, args.seed) else null

    override fun energy(f: FxFrame) {
        val t = time(f)
        val end = 1f - smooth(life - END_FADE, life.toFloat(), t)
        // Brandfleck (dunkelt ab) und Glut darin
        val spread = Ease.outCubic(t / STAIN_GROW)
        FxDraw.flat(f, at, UP, radius * STAIN_SCALE, alpha(SCORCH, STAIN_ALPHA * end), FxMode.STAIN, progress = spread, seed = seed + 2)
        val heat = (1f - t / HEAT_TICKS).coerceIn(0f, 1f)
        if (heat > 0f) {
            FxDraw.flat(f, at.add(0.0, 0.01, 0.0), UP, radius * HEAT_SCALE, alpha(mix(pal.edge, pal.main, heat), HEAT_ALPHA * heat * heat), FxMode.GLOW, seed = seed + 3)
        }
        // Stoßring
        val kr = (t / RING_TICKS).coerceIn(0f, 1f)
        if (kr < 1f) {
            val outer = radius * (0.25f + 0.75f * Ease.outCubic(kr))
            FxDraw.flat(f, at.add(0.0, 0.02, 0.0), UP, outer, alpha(pal.main, RING_ALPHA * (1f - kr) * (1f - kr)), FxMode.SHOCK, inner = RING_WIDTH, seed = seed)
        }
        // Staub: hebt sich, breitet sich aus, sinkt
        val kd = (t / DUST_TICKS).coerceIn(0f, 1f)
        if (kd < 1f) {
            val lift = DUST_LIFT * Ease.bump(kd)
            FxDraw.flat(f, at.add(0.0, 0.1 + lift, 0.0), UP, radius * (0.55f + 0.6f * Ease.outQuad(kd)),
                alpha(DUST, DUST_ALPHA * (1f - kd).pow(1.5f)), FxMode.DUST, progress = 0.85f * (1f - kd), seed = seed + 1)
        }
        sparks?.draw(f, at.add(0.0, 0.2, 0.0), t, SPARK_SPAN, end, pal)
    }

    override fun over(f: FxFrame) {
        val k = (1f - time(f) / FLASH_TICKS).coerceIn(0f, 1f)
        if (k <= 0f) return
        val size = (if (splash) SPLASH_FLASH else HAMMER_FLASH) * (0.6f + 0.4f * k)
        FxDraw.glow(f, at.add(0.0, 0.6, 0.0), size, alpha(pal.core, FLASH_ALPHA * k * k * k), seed)
    }

    companion object {
        const val MIN_LIFE = 30
        const val RING_TICKS = 10f
        const val RING_WIDTH = 0.8f
        const val RING_ALPHA = 0.7f
        const val DUST_TICKS = 28f
        const val DUST_LIFT = 0.6
        const val DUST_ALPHA = 0.42f
        const val STAIN_GROW = 6f
        const val STAIN_SCALE = 0.6f
        const val STAIN_ALPHA = 0.5f
        const val HEAT_TICKS = 34f
        const val HEAT_SCALE = 0.5f
        const val HEAT_ALPHA = 0.5f
        const val END_FADE = 14f
        const val FLASH_TICKS = 7f
        const val FLASH_ALPHA = 0.8f
        const val HAMMER_FLASH = 1.8f
        const val SPLASH_FLASH = 1.3f
        const val SPLASH_SPARKS = 10
        const val SPLASH_SPARK_SPEED = 0.42f
        const val SPARK_SPAN = 20f
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// brontes.swing
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Glutband am Hammerkopf: Der Effekt nimmt je Bild die Lage des Hammerkopfs aus dem Modell ([BrontesAnchors]) und
 * zieht daraus ein FLAME-Band mit hellem Kern (Spur der letzten [TRAIL_TICKS]). Ohne frische Anker (Auge zu, Modell
 * nicht gezeichnet) läuft ein gerechneter Bogen von seiner rechten zur linken Seite. Funken am Ende des Schwungs.
 */
private class BrontesSwingTrailFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val pal = args.palette
    private val seed = args.seed.toInt()
    private val boss: Entity? = args.entity
    private val origin = args.pos
    private val forward = flatDir(args.dir)
    private val right = rightOf(forward)
    private val reach = args.a.coerceIn(2f, 10f) * REACH_FACTOR
    private val half = Math.toRadians(args.b.coerceIn(10f, 170f).toDouble())
    private val points = ArrayList<Vec3d>()
    private val stamps = ArrayList<Float>()
    private var fromModel: Boolean? = null
    private var sparkAt: Vec3d? = null
    private var sparkTime = 0f
    private val sparks = SparkSet(SPARKS, SPARK_SPEED, forward, args.seed)

    private fun head(f: FxFrame, t: Float): Vec3d? {
        val e = boss
        val anchor = if (e != null) BrontesAnchors.fresh(e.id, e.age + f.tickDelta) else null
        if (fromModel == null) fromModel = anchor != null
        if (fromModel == true) return anchor?.hammer
        // Rückfall: gerechneter Bogen (rechts → links) in Hammerhöhe
        val k = Ease.outCubic(t / SWEEP_TICKS)
        val angle = half * (1.0 - 2.0 * k)
        val dir = forward.multiply(cos(angle)).add(right.multiply(sin(angle)))
        return origin.add(dir.multiply(reach.toDouble()))
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        if (t < SAMPLE_TICKS) {
            head(f, t)?.let { p ->
                if (points.isEmpty() || points.last().squaredDistanceTo(p) > MIN_STEP * MIN_STEP) {
                    points.add(p)
                    stamps.add(t)
                }
            }
        }
        if (sparkAt == null && t >= SPARK_AT && points.isNotEmpty()) {
            sparkAt = points.last()
            sparkTime = t
        }
        while (stamps.size > 2 && t - stamps[0] > TRAIL_TICKS) {
            stamps.removeAt(0)
            points.removeAt(0)
        }
        val fade = (1f - smooth(SAMPLE_TICKS, SAMPLE_TICKS + TRAIL_TICKS, t)) * (1f - smooth(life - 3f, life.toFloat(), t))
        if (points.size >= 2 && fade > 0.01f) {
            val line = if (points.size >= 3) FxDraw.smooth(points, 3) else points
            FxDraw.ribbon(f, line, width = { u -> WIDTH * (0.15f + 0.85f * u) },
                color = { u -> alpha(mix(pal.edge, pal.main, u), BAND_ALPHA * (0.15f + 0.85f * u) * fade) },
                mode = FxMode.FLAME, scroll = t * 0.4f, seed = seed)
            FxDraw.ribbon(f, line, width = { u -> CORE_WIDTH * u },
                color = { u -> alpha(mix(pal.main, pal.core, u), CORE_ALPHA * u * fade) }, mode = FxMode.RIBBON, seed = seed + 1)
        }
        sparkAt?.let { sparks.draw(f, it, t - sparkTime, SPARK_SPAN, 1f, pal) }
    }

    companion object {
        const val MIN_LIFE = 18
        const val SAMPLE_TICKS = 9f
        const val TRAIL_TICKS = 5f
        const val SWEEP_TICKS = 4f
        const val MIN_STEP = 0.05
        const val REACH_FACTOR = 0.62f
        const val WIDTH = 0.55f
        const val CORE_WIDTH = 0.16f
        const val BAND_ALPHA = 0.75f
        const val CORE_ALPHA = 0.6f
        const val SPARK_AT = 3f
        const val SPARKS = 8
        const val SPARK_SPEED = 0.35f
        const val SPARK_SPAN = 16f
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// brontes.bellows
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Funkenstrom aus dem Mund in den Kegel: [STREAKS] Lichtstriche lösen sich über [EMIT_TICKS], fliegen mit stetigem
 * Luftwiderstand bis 55–100 % der Reichweite und sinken zum Boden; dazu eine schwache Hitzewolke entlang der Achse.
 * Ursprung = Mund aus dem Modell, sonst Brusthöhe vor ihm (Server).
 */
private class BrontesBellowsFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private class Streak(val emit: Float, val dir: Vec3d, val speed: Float, val span: Float, val width: Float) {
        var origin: Vec3d? = null
    }

    private val pal = args.palette
    private val seed = args.seed.toInt()
    private val boss: Entity? = args.entity
    private val fallback = args.pos
    private val forward = flatDir(args.dir)
    private val right = rightOf(forward)
    private val range = args.a.coerceIn(4f, 24f)
    private val half = Math.toRadians(args.b.coerceIn(5f, 80f).toDouble())
    private var mouth = args.pos
    private val streaks: List<Streak>

    init {
        val rnd = Random(args.seed)
        val drop = (fallback.y - ground(args.world, fallback) - LAND_HEIGHT).coerceAtLeast(0.5)
        streaks = List(STREAKS) { i ->
            val yaw = half * SPREAD * (rnd.nextFloat() * 2f - 1f)
            val distance = range * (0.55f + 0.45f * rnd.nextFloat())
            val pitch = -atan(drop / distance) + Math.toRadians((rnd.nextFloat() * 2f - 1f) * PITCH_JITTER)
            val flat = forward.multiply(cos(yaw)).add(right.multiply(sin(yaw)))
            val dir = flat.multiply(cos(pitch)).add(0.0, sin(pitch), 0.0)
            Streak(EMIT_TICKS * i / STREAKS + rnd.nextFloat() * 0.6f, dir, distance * DRAG, SPAN_MIN + rnd.nextFloat() * SPAN_RANGE,
                WIDTH * (0.7f + 0.6f * rnd.nextFloat()))
        }
    }

    private fun at(s: Streak, origin: Vec3d, tau: Float): Vec3d {
        val travel = ((1f - exp(-DRAG * tau)) / DRAG * s.speed).toDouble()
        return origin.add(s.dir.multiply(travel)).add(0.0, RISE * tau * tau, 0.0)
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        if (t <= EMIT_TICKS + 1f) {
            val e = boss
            if (e != null) BrontesAnchors.fresh(e.id, e.age + f.tickDelta)?.let { mouth = it.mouth }
        }
        for ((i, s) in streaks.withIndex()) {
            val tau = t - s.emit
            if (tau <= 0f || tau >= s.span) continue
            val o = s.origin ?: mouth.also { s.origin = it }
            val k = tau / s.span
            val life = (1f - k).pow(1.3f)
            val head = at(s, o, tau)
            val mid = at(s, o, (tau - TRAIL * 0.5f).coerceAtLeast(0f))
            val tail = at(s, o, (tau - TRAIL).coerceAtLeast(0f))
            val hot = (1f - k * 1.3f).coerceIn(0f, 1f)
            FxDraw.ribbon(f, listOf(tail, mid, head), width = { u -> s.width * (0.25f + 0.75f * u) },
                color = { u -> alpha(mix(pal.edge, mix(pal.main, pal.core, hot), u), life * (0.25f + 0.75f * u)) },
                mode = FxMode.RIBBON, seed = seed + i)
        }
        // Hitzewolke: weicher, schwacher Schein entlang der Achse, wandert mit dem Strom hinaus
        val cloud = Ease.bump(t / CLOUD_TICKS)
        if (cloud > 0.01f) {
            for (j in 0 until CLOUD_PUFFS) {
                val d = range * (0.15f + 0.22f * j) * (0.6f + 0.4f * Ease.outQuad(t / CLOUD_TICKS))
                val p = mouth.add(forward.multiply(d.toDouble())).add(0.0, -0.35 * j, 0.0)
                FxDraw.glow(f, p, CLOUD_SIZE * (1f + 0.35f * j), alpha(pal.main, CLOUD_ALPHA * cloud), seed + 40 + j)
            }
        }
    }

    companion object {
        const val MIN_LIFE = 26
        const val STREAKS = 30
        const val EMIT_TICKS = 8f
        const val SPREAD = 0.85
        const val PITCH_JITTER = 4.0
        const val LAND_HEIGHT = 0.8
        const val DRAG = 0.12f
        const val RISE = 0.004
        const val SPAN_MIN = 13f
        const val SPAN_RANGE = 6f
        const val WIDTH = 0.07f
        const val TRAIL = 2.6f
        const val CLOUD_TICKS = 22f
        const val CLOUD_PUFFS = 4
        const val CLOUD_SIZE = 1.1f
        const val CLOUD_ALPHA = 0.14f
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// brontes.ingot
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Der liegende glühende Rohling flimmert leise: kleine Flammenwand als Hitzeschliere, ein atmender Schein (≤ 1 Hz),
 * warmer Fleck am Boden. Kühlt über das letzte Viertel ab; endet, sobald der Rohling (Wesen) weg ist.
 */
private class BrontesIngotShimmerFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val pal = args.palette
    private val seed = args.seed.toInt()
    private val ingot: Entity? = args.entity
    private val rest = args.pos
    private var gone = false

    override fun energy(f: FxFrame) {
        val e = ingot
        if (e != null && e.isRemoved) gone = true
        if (gone) return
        val t = time(f)
        val heat = (1f - smooth(life * COOL_FROM, life.toFloat(), t)) * smooth(0f, 6f, t)
        if (heat <= 0.01f) return
        val at = e?.getLerpedPos(f.tickDelta) ?: rest
        val breathe = 0.85f + 0.15f * sin(t * BREATH)
        FxDraw.flat(f, at.add(0.0, 0.02, 0.0), UP, FLOOR_RADIUS, alpha(pal.main, FLOOR_ALPHA * heat), FxMode.GLOW, seed = seed)
        FxDraw.glow(f, at.add(0.0, 0.2, 0.0), GLOW_SIZE * breathe, alpha(mix(pal.main, pal.core, 0.3f), GLOW_ALPHA * heat * breathe), seed)
        flameRing(f, at, HAZE_RADIUS, HAZE_HEIGHT * (0.8f + 0.2f * breathe), alpha(pal.main, HAZE_ALPHA * heat), seed + 1)
    }

    companion object {
        const val MIN_LIFE = 40
        const val COOL_FROM = 0.75f
        const val BREATH = 0.2f
        const val FLOOR_RADIUS = 1.0f
        const val FLOOR_ALPHA = 0.22f
        const val GLOW_SIZE = 0.55f
        const val GLOW_ALPHA = 0.32f
        const val HAZE_RADIUS = 0.32f
        const val HAZE_HEIGHT = 0.55f
        const val HAZE_ALPHA = 0.2f
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// brontes.grates
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Glühende Roste: In jedem heißen Sektor glüht es **unter** dem Gitter (die Kupfergitter der Schmiede lassen es durch
 * die Löcher sehen, die Ansage liegt darüber). Unter der Ansage wärmt es auf die Hälfte, im Brand voll mit langsamem
 * Atmen (0,4 Hz), am Ende kühlt es ab. Liegt kein Gitter, glüht es schwach auf dem Boden.
 */
private class BrontesGratesFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val pal = args.palette
    private val seed = args.seed.toInt()
    private val outer = args.a.coerceIn(4f, 40f)
    private val inner = args.b.coerceIn(0f, outer - 1f)
    private val warm = args.c.coerceAtLeast(1f)
    private val sectors = args.extra.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0 until OlympusLayout.GRATE_SECTORS }
    private val floorY = ground(args.world, args.pos)
    private val underGrate: Boolean
    private val bands: List<List<Vec3d>>

    init {
        val probe = OlympusLayout.dir(OlympusLayout.grateAngle(sectors.firstOrNull() ?: 0)).multiply(((inner + outer) / 2f).toDouble())
        val below = BlockPos.ofFloored(args.pos.x + probe.x, floorY - 0.5, args.pos.z + probe.z)
        underGrate = Registries.BLOCK.getId(args.world.getBlockState(below).block).path.contains("grate")
        val y = if (underGrate) floorY - UNDER_DEPTH else floorY + 0.03
        val mid = (inner + outer) / 2.0
        bands = sectors.map { i ->
            val a0 = i * OlympusLayout.GRATE_SECTOR_DEGREES + EDGE_DEGREES
            val a1 = (i + 1) * OlympusLayout.GRATE_SECTOR_DEGREES - EDGE_DEGREES
            List(ARC_POINTS) { k ->
                val d = OlympusLayout.dir(a0 + (a1 - a0) * k / (ARC_POINTS - 1))
                Vec3d(args.pos.x + d.x * mid, y, args.pos.z + d.z * mid)
            }
        }
    }

    private fun heat(t: Float): Float {
        val burnEnd = life.toFloat()
        return when {
            t < warm -> WARM_LEVEL * smooth(0f, warm, t)
            t < burnEnd - COOL_TICKS -> WARM_LEVEL + (1f - WARM_LEVEL) * smooth(warm, warm + 8f, t) * (0.92f + 0.08f * sin((t - warm) * BREATH))
            else -> (1f - smooth(burnEnd - COOL_TICKS, burnEnd, t))
        }
    }

    override fun energy(f: FxFrame) {
        val h = heat(time(f))
        if (h <= 0.01f) return
        val strength = if (underGrate) UNDER_ALPHA else OPEN_ALPHA
        val halfWidth = (outer - inner) / 2f - EDGE_INSET
        for ((index, band) in bands.withIndex()) {
            FxDraw.strip(f, band, UP, width = { halfWidth }, color = { alpha(mix(pal.edge, pal.main, h), strength * h) },
                mode = FxMode.FLAME, seed = seed + index, scroll = time(f) * 0.05f)
        }
    }

    companion object {
        const val MIN_LIFE = 40
        const val ARC_POINTS = 12
        const val EDGE_DEGREES = 3.0
        const val EDGE_INSET = 0.6f
        const val UNDER_DEPTH = 0.45
        const val WARM_LEVEL = 0.5f
        const val COOL_TICKS = 16f
        const val BREATH = 0.12f
        const val UNDER_ALPHA = 0.6f
        const val OPEN_ALPHA = 0.28f
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// brontes.ring
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Glutring des Keils: Eine niedrige Flammenwand läuft genau mit der Trefferfront (Radius · t / Laufzeit, wie der
 * Server) vom Amboss nach außen, darunter eine Bodenwelle. Man springt, wenn die Wand kommt.
 */
private class BrontesForgeRingFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val pal = args.palette
    private val seed = args.seed.toInt()
    private val center = Vec3d(args.pos.x, ground(args.world, args.pos) + 0.03, args.pos.z)
    private val radius = args.a.coerceIn(1f, 40f)
    private val travel = args.b.coerceAtLeast(1f)

    override fun energy(f: FxFrame) {
        val t = time(f)
        val front = radius * (t / travel).coerceIn(0f, 1f)
        val env = 1f - smooth(travel, travel + TAIL, t)
        if (env <= 0.01f || front < 0.4f) return
        FxDraw.flat(f, center, UP, front + 0.3f, alpha(pal.main, FLOOR_ALPHA * env), FxMode.SHOCK, inner = FLOOR_WIDTH, seed = seed)
        flameRing(f, center, front, WALL_HEIGHT * (0.7f + 0.3f * env), alpha(mix(pal.main, pal.core, 0.25f), WALL_ALPHA * env), seed + 1)
    }

    companion object {
        const val MIN_LIFE = 8
        const val TAIL = 8f
        const val FLOOR_ALPHA = 0.55f
        const val FLOOR_WIDTH = 1.4f
        const val WALL_HEIGHT = 0.85f
        const val WALL_ALPHA = 0.6f
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// brontes.wedge
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Einschlag des Keils: Eine Glutsäule schießt in [RISE_TICKS] hoch, hält, sinkt in sich zusammen; am Boden ein
 * Stoßring bis zum Radius, ein Brandfleck, Funken; heller Kern kurz in `over`. Den Stoß-Pass schickt der Server.
 */
private class BrontesWedgeImpactFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val pal = args.palette
    private val seed = args.seed.toInt()
    private val at = Vec3d(args.pos.x, ground(args.world, args.pos) + 0.03, args.pos.z)
    private val radius = args.a.coerceIn(2f, 12f)
    private val sparks = SparkSet(SPARKS, SPARK_SPEED, UP, args.seed)

    override fun energy(f: FxFrame) {
        val t = time(f)
        val rise = Ease.outCubic(t / RISE_TICKS)
        val sink = smooth(HOLD_UNTIL, life - 6f, t)
        val height = COLUMN_HEIGHT * rise * (1f - 0.55f * sink)
        val strength = 1f - sink
        if (height > 0.1f && strength > 0.01f) {
            val pts = List(6) { i -> at.add(0.0, height * i / 5.0, 0.0) }
            FxDraw.tube(f, pts, radius = { u -> COLUMN_RADIUS * (1f - 0.5f * u) * (0.8f + 0.2f * rise) },
                color = { u -> alpha(mix(pal.main, pal.edge, u), COLUMN_ALPHA * strength * (1f - 0.6f * u)) }, mode = FxMode.FLAME, sides = 10, seed = seed)
            FxDraw.tube(f, pts, radius = { u -> CORE_RADIUS * (1f - 0.6f * u) },
                color = { u -> alpha(mix(pal.core, pal.main, u), CORE_ALPHA * strength * (1f - u)) }, mode = FxMode.RIBBON, sides = 8, seed = seed + 1)
        }
        val kr = (t / RING_TICKS).coerceIn(0f, 1f)
        if (kr < 1f) {
            FxDraw.flat(f, at.add(0.0, 0.02, 0.0), UP, radius * (0.2f + 0.8f * Ease.outCubic(kr)), alpha(pal.main, RING_ALPHA * (1f - kr) * (1f - kr)),
                FxMode.SHOCK, inner = RING_WIDTH, seed = seed + 2)
        }
        val end = 1f - smooth(life - 10f, life.toFloat(), t)
        FxDraw.flat(f, at, UP, STAIN_RADIUS, alpha(SCORCH, 0.5f * end), FxMode.STAIN, progress = Ease.outCubic(t / 6f), seed = seed + 3)
        sparks.draw(f, at.add(0.0, 0.4, 0.0), t, SPARK_SPAN, end, pal)
    }

    override fun over(f: FxFrame) {
        val k = (1f - time(f) / FLASH_TICKS).coerceIn(0f, 1f)
        if (k > 0f) FxDraw.glow(f, at.add(0.0, 1.2, 0.0), FLASH_SIZE * (0.6f + 0.4f * k), alpha(pal.core, 0.85f * k * k * k), seed)
    }

    companion object {
        const val MIN_LIFE = 30
        const val RISE_TICKS = 5f
        const val HOLD_UNTIL = 12f
        const val COLUMN_HEIGHT = 7f
        const val COLUMN_RADIUS = 1.3f
        const val COLUMN_ALPHA = 0.7f
        const val CORE_RADIUS = 0.45f
        const val CORE_ALPHA = 0.7f
        const val RING_TICKS = 9f
        const val RING_WIDTH = 1.1f
        const val RING_ALPHA = 0.75f
        const val STAIN_RADIUS = 2.6f
        const val SPARKS = 12
        const val SPARK_SPEED = 0.55f
        const val SPARK_SPAN = 22f
        const val FLASH_TICKS = 6f
        const val FLASH_SIZE = 3f
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// brontes.stuck (Überarbeitung 4, P16)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Der Hammer steckt im Boden: Um den Kopf liegt ein ruhiger Staubschleier, der langsam aufsteigt, und vier kurze
 * Glutadern laufen aus dem Einschlag in den Boden (CRACK-Band, wachsen in [GROW] Ticks, atmen mit [BREATH] ≤ 0,5 Hz).
 * Keine Funken, kein Blitz: Das Fenster zeigt die goldene Rune des Servers; die Glut sagt nur „hier steckt er“. Die
 * letzten [FADE] Ticks verblassen die Adern (das Herausreißen bringt Server-Trümmer und Funken).
 */
private class BrontesStuckFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val pal = args.palette
    private val seed = args.seed.toInt()
    private val at = Vec3d(args.pos.x, ground(args.world, args.pos) + 0.03, args.pos.z)
    private val forward = flatDir(args.dir)
    private val radius = args.a.coerceIn(1f, 4f)
    private val veins: List<List<Vec3d>> = Random(args.seed).let { rnd ->
        List(VEINS) { i ->
            // Je Ader ein leicht gezackter Weg, gleichmäßig verteilt, leicht gegen die Schlagrichtung gedreht.
            val base = Math.atan2(forward.z, forward.x) + i * (2.0 * PI / VEINS) + (rnd.nextDouble() - 0.5) * 0.5
            val length = radius * (VEIN_MIN + rnd.nextFloat() * VEIN_RANGE)
            List(VEIN_POINTS) { k ->
                val u = k / (VEIN_POINTS - 1.0)
                val wobble = (rnd.nextDouble() - 0.5) * 0.35 * u
                val a = base + wobble
                at.add(cos(a) * length * u, 0.02, sin(a) * length * u)
            }
        }
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val grow = Ease.outCubic(t / GROW)
        val end = 1f - smooth(life - FADE, life.toFloat(), t)
        val breathe = 0.8f + 0.2f * sin(t * BREATH)
        val heat = grow * end * breathe
        for ((i, vein) in veins.withIndex()) {
            val n = (vein.size * grow).toInt().coerceIn(2, vein.size)
            FxDraw.strip(f, vein.subList(0, n), UP, width = { u -> VEIN_WIDTH * (1f - 0.6f * u) },
                color = { u -> alpha(mix(pal.core, pal.main, u), VEIN_ALPHA * heat * (1f - 0.5f * u)) }, mode = FxMode.CRACK, seed = seed + i, progress = heat)
        }
        FxDraw.flat(f, at.add(0.0, 0.01, 0.0), UP, radius * HEAT_SCALE, alpha(pal.main, HEAT_ALPHA * heat), FxMode.GLOW, seed = seed + 7)
        // Staubschleier: hebt sich langsam, wird dünner.
        val kd = (t / life).coerceIn(0f, 1f)
        FxDraw.flat(f, at.add(0.0, 0.15 + DUST_LIFT * kd, 0.0), UP, radius * (0.7f + 0.3f * kd), alpha(DUST, DUST_ALPHA * (1f - kd) * end),
            FxMode.DUST, progress = 0.6f * (1f - kd), seed = seed + 9)
    }

    companion object {
        const val MIN_LIFE = 30
        const val VEINS = 4
        const val VEIN_POINTS = 6
        const val VEIN_MIN = 0.55f
        const val VEIN_RANGE = 0.35f
        const val VEIN_WIDTH = 0.16f
        const val VEIN_ALPHA = 0.75f
        const val GROW = 8f
        const val FADE = 12f
        const val BREATH = 0.15f
        const val HEAT_SCALE = 0.45f
        const val HEAT_ALPHA = 0.3f
        const val DUST_LIFT = 0.9
        const val DUST_ALPHA = 0.3f
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// brontes.anvil_crack / brontes.anvil_break (Überarbeitung 4, P16)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Riss im Amboss: Über die Oberkante läuft ein glühender Sprung (CRACK-Band, je Riss einer mehr), dazu ein kurzer
 * Staubstoß und ein schwacher Kernschein (kubisch). Die Blöcke selbst wechseln am Server (`ember_basalt` in den Fugen),
 * darum klingt die Glut hier in [HEAT_TICKS] ab. **Bruch** ([broken]): Stoßring am Boden um den Stumpf, dichter
 * Staub, Funken nach oben, ein Glutfleck auf den Trümmern, der langsam abkühlt.
 */
private class BrontesAnvilCrackFx(args: FxArgs, private val broken: Boolean) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val pal = args.palette
    private val seed = args.seed.toInt()
    private val top = args.pos
    private val floor = Vec3d(args.pos.x, args.pos.y - BREAK_DROP, args.pos.z)
    private val count = if (broken) 0 else args.a.toInt().coerceIn(1, 2)
    private val radius = if (broken) args.a.coerceIn(1f, 4f) else 1.5f
    private val sparks = if (broken) SparkSet(BREAK_SPARKS, BREAK_SPARK_SPEED, UP, args.seed) else SparkSet(CRACK_SPARKS, CRACK_SPARK_SPEED, UP, args.seed)
    private val lines: List<List<Vec3d>> = Random(args.seed).let { rnd ->
        List(count) { i ->
            // Quer über die 3 × 3-Oberkante, je Riss in anderer Richtung, leicht gezackt.
            val a = (i * 0.9 + rnd.nextDouble() * 0.6) * PI
            val dx = cos(a)
            val dz = sin(a)
            List(CRACK_POINTS) { k ->
                val u = k / (CRACK_POINTS - 1.0) * 2.0 - 1.0
                val side = (rnd.nextDouble() - 0.5) * 0.3
                top.add(dx * u * CRACK_HALF - dz * side, 0.03, dz * u * CRACK_HALF + dx * side)
            }
        }
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val end = 1f - smooth(life - END_FADE, life.toFloat(), t)
        val heat = (1f - t / HEAT_TICKS).coerceIn(0f, 1f)
        for ((i, line) in lines.withIndex()) {
            val n = (line.size * Ease.outCubic(t / CRACK_GROW)).toInt().coerceIn(2, line.size)
            FxDraw.strip(f, line.subList(0, n), UP, width = { u -> CRACK_WIDTH * (1f - 0.5f * kotlin.math.abs(u * 2f - 1f)) },
                color = { alpha(mix(pal.main, pal.core, heat), CRACK_ALPHA * (0.35f + 0.65f * heat) * end) }, mode = FxMode.CRACK, seed = seed + i,
                progress = 0.4f + 0.6f * heat)
        }
        val base = if (broken) floor else top
        if (broken) {
            val kr = (t / RING_TICKS).coerceIn(0f, 1f)
            if (kr < 1f) {
                FxDraw.flat(f, floor.add(0.0, 0.03, 0.0), UP, radius * (0.4f + 0.6f * Ease.outCubic(kr)), alpha(pal.main, RING_ALPHA * (1f - kr) * (1f - kr)),
                    FxMode.SHOCK, inner = RING_WIDTH, seed = seed + 5)
            }
            FxDraw.flat(f, floor.add(0.0, 0.52, 0.0), UP, POOL_RADIUS, alpha(mix(pal.edge, pal.main, heat), POOL_ALPHA * (0.4f + 0.6f * heat) * end),
                FxMode.GLOW, seed = seed + 6)
        }
        val kd = (t / DUST_TICKS).coerceIn(0f, 1f)
        if (kd < 1f) {
            val size = if (broken) radius * 1.2f else 1.6f
            FxDraw.flat(f, base.add(0.0, 0.2 + DUST_LIFT * Ease.bump(kd), 0.0), UP, size * (0.6f + 0.5f * Ease.outQuad(kd)),
                alpha(DUST, (if (broken) BREAK_DUST_ALPHA else DUST_ALPHA) * (1f - kd).pow(1.5f)), FxMode.DUST, progress = 0.8f * (1f - kd), seed = seed + 1)
        }
        sparks.draw(f, base.add(0.0, 0.3, 0.0), t, SPARK_SPAN, end, pal)
    }

    override fun over(f: FxFrame) {
        val k = (1f - time(f) / FLASH_TICKS).coerceIn(0f, 1f)
        if (k <= 0f) return
        val size = if (broken) BREAK_FLASH else CRACK_FLASH
        FxDraw.glow(f, (if (broken) floor else top).add(0.0, 0.5, 0.0), size * (0.6f + 0.4f * k), alpha(pal.core, FLASH_ALPHA * k * k * k), seed)
    }

    companion object {
        const val MIN_LIFE = 30
        /** Oberkante → Boden des Ambosses (2 hoch). */
        const val BREAK_DROP = 2.0
        const val CRACK_POINTS = 7
        const val CRACK_HALF = 1.35
        const val CRACK_WIDTH = 0.14f
        const val CRACK_ALPHA = 0.85f
        const val CRACK_GROW = 5f
        const val HEAT_TICKS = 28f
        const val END_FADE = 10f
        const val RING_TICKS = 10f
        const val RING_WIDTH = 0.9f
        const val RING_ALPHA = 0.65f
        const val POOL_RADIUS = 1.6f
        const val POOL_ALPHA = 0.35f
        const val DUST_TICKS = 30f
        const val DUST_LIFT = 0.6
        const val DUST_ALPHA = 0.3f
        const val BREAK_DUST_ALPHA = 0.45f
        const val CRACK_SPARKS = 5
        const val CRACK_SPARK_SPEED = 0.3f
        const val BREAK_SPARKS = 12
        const val BREAK_SPARK_SPEED = 0.5f
        const val SPARK_SPAN = 20f
        const val FLASH_TICKS = 6f
        const val FLASH_ALPHA = 0.6f
        const val CRACK_FLASH = 1.0f
        const val BREAK_FLASH = 2.2f
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Lebendige Esse (FxAttach)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Die Glut der Esse lebt mit ihm (REF T18): Helligkeit folgt seinem Lebensanteil (getrackt), in Phase II heller; ein
 * langsamer Puls (0,35 Hz, schwächer werdend bis 0,8 Hz, immer ≤ 1 Hz), weicher Schein an der Feueröffnung, warmer
 * Boden und aufsteigende Funken. Nur an der Schmiede im Olymp; kein Block wird geändert.
 */
private class BrontesForgeGlowFx(entity: Entity) : FxEffect(Int.MAX_VALUE / 4) {
    private val boss = entity as? LivingEntity
    private val forge = Vec3d(OlympusLayout.FORGE.x + 0.5, OlympusLayout.FORGE.y.toDouble(), OlympusLayout.FORGE.z + 0.5)
    private val pal: Palette = Palette.of(Vfx.Style.FIRE)
    private var bright = -1f
    private var pulse = 0f
    private var last = Float.NaN
    private val embers = Random(EMBER_SEED).let { rnd -> List(EMBERS) { Ember(rnd.nextFloat(), PERIOD_MIN + rnd.nextFloat() * PERIOD_RANGE, rnd.nextFloat() * 6.28f) } }

    private class Ember(val offset: Float, val period: Float, val phase: Float)

    private fun atForge(e: LivingEntity): Boolean =
        e.entityWorld.registryKey == OlympusLayout.WORLD && e.squaredDistanceTo(OlympusLayout.D.centerVec) < RANGE * RANGE

    override fun energy(f: FxFrame) {
        val e = boss ?: return
        if (!atForge(e)) return
        val t = time(f)
        val dt = if (last.isNaN()) 0f else (t - last).coerceIn(0f, 4f)
        last = t
        val life = if (e.maxHealth > 0f) (e.health / e.maxHealth).coerceIn(0f, 1f) else 1f
        val phase = (e as? DuelBossEntity)?.phase ?: 0
        val target = (BASE + LIFE_SHARE * life) * if (phase >= 1) PHASE2_BOOST else 1f
        bright = if (bright < 0f) target else bright + (target - bright) * (1f - exp(-dt / BRIGHT_TAU))
        val hz = PULSE_HZ_MIN + (PULSE_HZ_MAX - PULSE_HZ_MIN) * (1f - life)
        pulse += (2f * PI.toFloat() * hz / 20f) * dt
        val b = bright * (1f + PULSE_DEPTH * sin(pulse))
        FxDraw.flat(f, forge.add(0.0, 0.03, 0.0), UP, FLOOR_RADIUS * (0.8f + 0.2f * b), alpha(pal.main, FLOOR_ALPHA * b), FxMode.GLOW, seed = 7)
        FxDraw.glow(f, forge.add(0.0, GLOW_HEIGHT, 0.0), GLOW_SIZE * (0.7f + 0.3f * b), alpha(mix(pal.main, pal.core, 0.35f), GLOW_ALPHA * b), 11)
        for ((i, em) in embers.withIndex()) {
            val k = ((t / em.period + em.offset) % 1f)
            val fade = Ease.bump(k) * (b * EMBERS - i).coerceIn(0f, 1f)
            if (fade <= 0.02f) continue
            val y = EMBER_RISE * k
            val sway = 0.35 * sin(em.phase + t * 0.05f + k * 3f)
            val p = forge.add(sway, 0.5 + y, 0.3 * cos(em.phase + t * 0.04f))
            val tail = p.add(0.0, -EMBER_TAIL, 0.0)
            FxDraw.ribbon(f, listOf(tail, p), width = { u -> EMBER_WIDTH * (0.3f + 0.7f * u) },
                color = { u -> alpha(mix(pal.edge, pal.core, u * (1f - k)), fade * (0.3f + 0.7f * u)) }, mode = FxMode.RIBBON, seed = 20 + i)
        }
    }

    companion object {
        const val RANGE = 52.0
        const val BASE = 0.45f
        const val LIFE_SHARE = 0.4f
        const val PHASE2_BOOST = 1.35f
        const val BRIGHT_TAU = 30f
        const val PULSE_HZ_MIN = 0.35f
        const val PULSE_HZ_MAX = 0.8f
        const val PULSE_DEPTH = 0.12f
        const val FLOOR_RADIUS = 3.2f
        const val FLOOR_ALPHA = 0.28f
        const val GLOW_HEIGHT = 0.9
        const val GLOW_SIZE = 2.2f
        const val GLOW_ALPHA = 0.45f
        const val EMBERS = 9
        const val EMBER_SEED = 0x6B70L
        const val PERIOD_MIN = 40f
        const val PERIOD_RANGE = 34f
        const val EMBER_RISE = 5.5
        const val EMBER_TAIL = 0.35
        const val EMBER_WIDTH = 0.06f
    }
}
