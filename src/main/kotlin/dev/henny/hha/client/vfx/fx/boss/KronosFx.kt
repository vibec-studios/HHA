package dev.henny.hha.client.vfx.fx.boss

import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaSounds
import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.model.KronosParts
import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxAttach
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMeshes
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.client.vfx.fx.FxRegistry
import dev.henny.hha.client.vfx.fx.FxSolidMode
import dev.henny.hha.client.vfx.fx.Tear
import dev.henny.hha.client.vfx.fx.lib.FxLib
import dev.henny.hha.entity.boss.KronosEntity
import dev.henny.hha.entity.boss.KronosPose
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.timings.KronosSigTimings
import dev.henny.hha.world.TartarusPlans
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.LightmapTextureManager
import net.minecraft.client.render.RenderLayers
import net.minecraft.client.render.WorldRenderer
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Vector3f
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Client-Effekte von Kronos (P13, Präfix `kronos.`, PLAN 2.10). Je Effekt eine Klasse, Zeiten und Größen als benannte
 * Konstanten in ihrem Begleitobjekt. Alles weich: glatte Bänder, Flächen und Meshes, stetige Zeit, keine Stufen.
 *
 * | ID | Klasse | Kodierung (Server: `KronosAttacks`) |
 * | --- | --- | --- |
 * | `kronos.harpe` | [KronosHarpeFx] | pos = Kegelspitze (Füße), dir = Hiebrichtung, a = Reichweite, b = halber Winkel (°), c = +1 rechts→links / −1 Rückhand, d = 0 Sichel / 1 Spalthieb, life |
 * | `kronos.hand` | [KronosHandFx] | pos = Mitte des Kreises am Boden, a = Radius, b = Ticks bis zum Treffer, life |
 * | `kronos.clock` | [KronosClockFx] | pos = Nabe, a = Winkel des Minutenzeigers, b = des Stundenzeigers (Bogenmaß), c = Länge, life |
 * | `kronos.shards` | [KronosShardsFx] | pos = Ziel (Sanduhr), entity = Kronos (Herkunft), life |
 * | `kronos.ghosts` | [KronosGhostsFx] | b = Gestalt, extra = `x,y,z,yaw;…` (neuester zuerst), life |
 * | `kronos.sky` | [KronosSkyFx] | Dauer-Effekt an jedem Kronos (`FxAttach`); per Befehl: pos = Saalmitte, a = Gestalt |
 * | `kronos.death` | [KronosDeathFx] | pos = Füße, entity = Kronos, a = Höhe, life = 200 (feste Absprache mit P12) |
 * | `kronos.ray` | [KronosRayFx] | pos = Boden unter der Hand, dir = Startkante, a = Ansage-Ticks, b = Schwenk-Ticks, c = Drehsinn, d = Reichweite, entity = Kronos, life (Ü4, P20) |
 * | `kronos.age` | [KronosAgeFx] | pos = Säulenfuß, a = halbe Breite, b = Höhe, c = 1 rückwärts (Wiederaufbau), life (Ü4, P20) |
 * | `kronos.rewind_hit` | [KronosRewindHitFx] | pos = Trefferort, dir = Ziel − Treffer, a = Spielerhöhe, life (Ü4, P20) |
 */
object KronosFx {
    fun init() {
        FxRegistry.register("kronos.harpe") { KronosHarpeFx(it) }
        FxRegistry.register("kronos.hand") { KronosHandFx(it) }
        FxRegistry.register("kronos.clock") { KronosClockFx(it) }
        FxRegistry.register("kronos.shards") { KronosShardsFx(it) }
        FxRegistry.register("kronos.ghosts") { KronosGhostsFx(it) }
        FxRegistry.register("kronos.death") { KronosDeathFx(it) }
        // Überarbeitung 4 (P20): Stillstand mit Deckung.
        FxRegistry.register("kronos.ray") { KronosRayFx(it) }
        FxRegistry.register("kronos.age") { KronosAgeFx(it) }
        FxRegistry.register("kronos.rewind_hit") { KronosRewindHitFx(it) }
        // Per Befehl (Vorführung): ein Himmel um den Punkt, ohne Wesen.
        FxRegistry.register("kronos.sky") { a -> KronosSkyFx(null, a.pos.add(0.0, -1.0, 0.0), a.a.toInt().coerceIn(0, 1), KronosSkyFx.DEMO_LIFE) }
        FxAttach.register(HhaEntities.KRONOS) { KronosSkyFx(it, null, 0, KronosSkyFx.ATTACH_LIFE) }
    }

    internal val AEON: Palette get() = Palette.of(Vfx.Style.AEON)
    internal val UP: Vec3d = Vec3d(0.0, 1.0, 0.0)

    internal fun smooth(x: Float): Float = Ease.inOut(x)
    internal fun clamp01(x: Float): Float = x.coerceIn(0f, 1f)

    /** Kamerazugewandte Normale (für Staub- und Scheinflächen, die wie Billboards wirken sollen). */
    internal fun facing(f: FxFrame, at: Vec3d): Vec3d = f.camPos.subtract(at).let { if (it.lengthSquared() < 1e-6) UP else it.normalize() }

    /** Weltlicht an [at], unten auf [minBlock] angehoben (Modellteile im dunklen Tartaros sollen lesbar bleiben). */
    internal fun light(at: Vec3d, minBlock: Int): Int {
        val world = MinecraftClient.getInstance().world ?: return FxDraw.FULL_BRIGHT
        val packed = WorldRenderer.getLightmapCoordinates(world, BlockPos.ofFloored(at))
        val block = maxOf(LightmapTextureManager.getBlockLightCoordinates(packed), minBlock)
        return LightmapTextureManager.pack(block, LightmapTextureManager.getSkyLightCoordinates(packed))
    }
}

// =====================================================================================================================
// kronos.harpe – Sichelband mit einem Nachbild
// =====================================================================================================================

/**
 * Breites Sichelband der Harpe (PLAN 2.10 „Sichel / Spalthieb“): Der Kopf läuft in [SWING] Ticks über den Bogen
 * (`outCubic`), der Schweif folgt verzögert; das Band ist sichelförmig (spitz an beiden Enden, am breitesten kurz vor
 * dem Kopf). Ein Nachbild läuft [GHOST_DELAY] Ticks hinterher, tiefer und blasser. Sichel: waagerechter Bogen in
 * Brusthöhe über den Kegel; Spalthieb: senkrechter Bogen von hinten oben bis in den Boden vor ihm. Liegt über der
 * Ansage nur im Augenblick des Treffers (sie ist dann vorbei).
 */
class KronosHarpeFx(a: FxArgs) : FxEffect(a.life.coerceAtLeast(MIN_LIFE)) {
    private val apex = a.pos
    private val fwd = FxLib.flat(a.dir, Vec3d(0.0, 0.0, 1.0))
    private val right = Vec3d(-fwd.z, 0.0, fwd.x)
    private val reach = a.a.coerceIn(3f, 30f).toDouble()
    private val half = Math.toRadians(a.b.coerceIn(10f, 170f).toDouble())
    private val sweep = if (a.c < 0f) -1.0 else 1.0
    private val cleave = a.d >= 0.5f
    private val pal = a.palette
    private val seed = a.seed.toInt()
    private val swing = if (cleave) CLEAVE_SWING else SWING

    /** Punkt der Bahn bei Anteil [u] (0 Beginn, 1 Ende), [shrink] < 1 zieht nach innen, [drop] senkt (Nachbild). */
    private fun at(u: Double, shrink: Double, drop: Double): Vec3d =
        if (cleave) {
            val phi = Math.toRadians(CLEAVE_FROM + (CLEAVE_TO - CLEAVE_FROM) * u)
            val r = CLEAVE_RADIUS * shrink
            apex.add(0.0, CLEAVE_PIVOT - drop, 0.0).add(fwd.multiply(cos(phi) * r)).add(0.0, sin(phi) * r, 0.0)
        } else {
            val th = sweep * half * (1.0 - 2.0 * u)
            val dir = fwd.multiply(cos(th)).add(right.multiply(sin(th)))
            apex.add(dir.multiply(reach * SICKLE_REACH * shrink)).add(0.0, SICKLE_HEIGHT + SICKLE_RISE * u - drop, 0.0)
        }

    /** Ein Band vom Schweif zum Kopf zur Zeit [t]. [alpha] = Deckkraft, [core] = mit hellem Kern. */
    private fun band(f: FxFrame, t: Float, shrink: Double, drop: Double, alpha: Float, core: Boolean) {
        if (t <= 0f) return
        val head = Ease.outCubic(t / swing)
        val tail = Ease.inOut((t - TAIL_DELAY) / TAIL_TICKS) * head
        if (head - tail < 0.02f) return
        val pts = List(POINTS) { i -> at((tail + (head - tail) * i / (POINTS - 1f)).toDouble(), shrink, drop) }
        val width = (if (cleave) CLEAVE_WIDTH else WIDTH) * (0.6f + 0.4f * (head - tail))
        fun profile(p: Float) = sin(PI.toFloat() * p.pow(0.75f)).coerceAtLeast(0f)
        FxDraw.ribbon(
            f, pts, width = { p -> width * profile(p) + 0.02f },
            color = { p -> FxLib.alpha(FxLib.mix(pal.edge, pal.main, p), alpha * HULL_ALPHA * sqrt(p)) },
            mode = FxMode.RIBBON, seed = seed, scroll = t * 0.4f,
        )
        if (!core) return
        FxDraw.ribbon(
            f, pts, width = { p -> width * CORE_SHARE * profile(p) },
            color = { p -> FxLib.alpha(FxLib.mix(pal.main, pal.core, p), alpha * p) },
            mode = FxMode.RIBBON, seed = seed + 1,
        )
    }

    private fun fade(t: Float): Float = 1f - KronosFx.smooth((t - life * FADE_FROM) / (life * (1f - FADE_FROM)))

    override fun energy(f: FxFrame) {
        val t = time(f)
        band(f, t, 1.0, 0.0, fade(t), core = true)
        band(f, t - GHOST_DELAY, GHOST_SHRINK, GHOST_DROP, fade(t) * GHOST_ALPHA, core = false)
    }

    override fun over(f: FxFrame) {
        val t = time(f)
        if (t > swing + 2f) return
        val k = Ease.outCubic(t / swing).toDouble()
        FxDraw.glow(f, at(k, 1.0, 0.0), HEAD_GLOW, FxLib.alpha(pal.core, HEAD_GLOW_ALPHA * Ease.bump(t / (swing + 2f))))
    }

    companion object {
        const val MIN_LIFE = 10
        const val POINTS = 28
        const val SWING = 4f
        const val CLEAVE_SWING = 3f
        const val TAIL_DELAY = 2f
        const val TAIL_TICKS = 8f
        const val WIDTH = 1.1f
        const val CLEAVE_WIDTH = 0.95f
        const val CORE_SHARE = 0.32f
        const val HULL_ALPHA = 0.55f
        const val SICKLE_REACH = 0.82
        const val SICKLE_HEIGHT = 2.2
        const val SICKLE_RISE = 0.6
        /** Spalthieb: Drehpunkt in Schulterhöhe, Bogen von hinten oben (110°) bis in den Boden vor ihm (−38°). */
        const val CLEAVE_PIVOT = 4.5
        const val CLEAVE_RADIUS = 7.5
        const val CLEAVE_FROM = 110.0
        const val CLEAVE_TO = -38.0
        const val GHOST_DELAY = 3f
        const val GHOST_SHRINK = 0.93
        const val GHOST_DROP = 0.35
        const val GHOST_ALPHA = 0.35f
        const val FADE_FROM = 0.45f
        const val HEAD_GLOW = 0.9f
        const val HEAD_GLOW_ALPHA = 0.5f
    }
}

// =====================================================================================================================
// kronos.hand – Riss über dem Ziel, die Hand schlägt herab
// =====================================================================================================================

/**
 * Hand aus dem Riss (PLAN 2.10, Vorbild Void `RiftFistEffect`): Zu Beginn der Ansage öffnet sich [TEAR_HEIGHT] Blöcke
 * über dem Kreis ein liegender Riss (`Tear.draw`, Äon). In den letzten [PEEK] Ticks schiebt sich die **linke Hand aus
 * `KronosModel`** (eigene Kopie, `FxDraw.part` mit seiner Textur) aus dem Riss, holt kurz aus und schlägt in den letzten
 * [DROP] Ticks herab; die Faust ist so breit wie der Kreis. Am Treffer: sechs Bodenrisse, ein Stoßring, Staub und ein
 * Lichtschein; danach zieht sich die Hand zurück und der Riss schließt. Ein Lichtband verbindet Riss und Handgelenk.
 */
class KronosHandFx(a: FxArgs) : FxEffect((a.b.toInt().coerceIn(4, 80) + AFTER).coerceAtLeast(a.life)) {
    private val ground = a.pos
    private val radius = a.a.coerceIn(0.8f, 8f)
    private val hit = a.b.coerceIn(4f, 80f)
    private val pal = a.palette
    private val rnd = Random(a.seed)
    private val spin = rnd.nextFloat() * 2f * PI.toFloat()
    private val tilt = (rnd.nextFloat() - 0.5f) * 2f * TILT
    private val along = Vec3d(cos(spin.toDouble()), 0.0, sin(spin.toDouble()))
    private val across = Vec3d(-along.z, 0.0, along.x)
    private val tearAt = ground.add(0.0, TEAR_HEIGHT, 0.0)
    private val shape = Tear.Shape(a.seed.toInt())
    private val scale = radius * HAND_SCALE
    private val handLength = KronosParts.HAND_LENGTH / 16f * scale
    private val floorY = FxLib.groundY(a.world, ground.x, ground.y + 0.5, ground.z) + FxLib.GROUND_LIFT
    private val cracks: List<List<Vec3d>> = List(CRACKS) { i ->
        val ang = spin + i * 2f * PI.toFloat() / CRACKS + (rnd.nextFloat() - 0.5f) * 0.6f
        val len = radius * (CRACK_MIN + rnd.nextFloat() * (CRACK_MAX - CRACK_MIN))
        val dir = Vec3d(cos(ang.toDouble()), 0.0, sin(ang.toDouble()))
        val side = Vec3d(-dir.z, 0.0, dir.x)
        val n = (len / CRACK_STEP).toInt().coerceAtLeast(2)
        List(n + 1) { j ->
            val d = radius * 0.25 + len * j / n
            val zig = if (j == 0) 0.0 else (rnd.nextDouble() - 0.5) * 0.35
            val p = ground.add(dir.multiply(d)).add(side.multiply(zig))
            Vec3d(p.x, FxLib.groundY(a.world, p.x, ground.y + 0.5, p.z) + FxLib.GROUND_LIFT, p.z)
        }
    }

    /** Öffnung des Risses: auf in [OPEN_TICKS], hält, schließt in den letzten [CLOSE_TICKS]. */
    private fun open(t: Float): Float = Ease.outBack(t / OPEN_TICKS) * KronosFx.clamp01((life - t) / CLOSE_TICKS)

    /** Handgelenk (Welt) und Größe 0..1 zur Zeit [t], oder null, solange die Hand im Riss ist. */
    private fun hand(t: Float): Pair<Vec3d, Float>? {
        val down = floorY + handLength * IMPACT_DEPTH
        val top = tearAt.y + LIFT
        return when {
            t < hit - PEEK -> null
            t < hit - DROP -> {
                val k = Ease.outCubic((t - (hit - PEEK)) / (PEEK - DROP))
                Vec3d(ground.x, tearAt.y + LIFT * k, ground.z) to (0.3f + 0.7f * k)
            }
            t < hit -> {
                val k = Ease.inCubic((t - (hit - DROP)) / DROP)
                Vec3d(ground.x, top + (down - top) * k, ground.z) to 1f
            }
            t < hit + HOLD -> {
                val rebound = Ease.bump((t - hit) / HOLD) * REBOUND
                Vec3d(ground.x, down + rebound, ground.z) to 1f
            }
            t < hit + HOLD + RISE -> {
                val k = Ease.outQuad((t - hit - HOLD) / RISE)
                Vec3d(ground.x, down + (tearAt.y - down) * k, ground.z) to (1f - 0.7f * k)
            }
            else -> null
        }
    }

    override fun tick(world: ClientWorld) {
        if (age == 1 && soundSlot(world, 0)) FxLib.sound(world, tearAt, HhaSounds.TEAR, 0.5f, 0.8f)
    }

    override fun solid(f: FxFrame) {
        val part = KronosParts.handPart() ?: return
        val (wrist, size) = hand(time(f)) ?: return
        val s = scale * size
        val m = Matrix4f().translation(wrist.x.toFloat(), wrist.y.toFloat(), wrist.z.toFloat())
            .rotateY(-spin).rotateX(tilt).scale(-s, -s, s)
        FxDraw.part(f, part, KronosParts.TEXTURE, m, KronosFx.light(wrist, HAND_LIGHT))
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val o = open(t)
        Tear.draw(f, tearAt, along, across, radius * TEAR_LENGTH, radius * TEAR_WIDTH, o, shape, pal, t, KronosFx.clamp01(1f - t / hit) * 0.6f)
        hand(t)?.let { (wrist, size) ->
            // Arm aus Licht vom Riss zum Handgelenk, darum die Hülle der Faust.
            if (wrist.y < tearAt.y - 0.2) {
                FxDraw.ribbon(
                    f, listOf(tearAt, wrist.add(0.0, 0.2, 0.0)), width = { p -> radius * ARM_WIDTH * (1f - 0.4f * p) * size },
                    color = { p -> FxLib.alpha(pal.main, ARM_ALPHA * o * (0.4f + 0.6f * p)) }, mode = FxMode.RIBBON, seed = shape.seed,
                )
            }
            val c = wrist.add(0.0, -handLength * size * 0.5, 0.0)
            val hull = scale * size
            FxDraw.mesh(
                f, FxMeshes.SPHERE, FxDraw.place(c, scaleXYZ = Vector3f(hull * 0.62f, handLength * size * 0.62f, hull * 0.62f)),
                FxLib.alpha(pal.main, SHELL_ALPHA), FxMode.SHELL, seed = shape.seed,
            )
        }
        if (t < hit) return
        val since = t - hit
        // Sechs Bodenrisse fressen sich vom Einschlag nach außen und glühen aus.
        val grow = Ease.outCubic(since / CRACK_GROW)
        val heat = KronosFx.clamp01(1f - since / CRACK_COOL)
        val fadeOut = KronosFx.clamp01((life - t) / CRACK_FADE)
        for ((i, pts) in cracks.withIndex()) {
            val shown = FxLib.partial(pts, FxLib.arcFractions(pts), grow)
            if (shown.size < 2) continue
            FxDraw.strip(
                f, shown, KronosFx.UP, width = { p -> CRACK_WIDTH * (1f - 0.7f * p) },
                color = { _ -> FxLib.alpha(FxLib.mix(pal.edge, pal.main, heat), fadeOut) }, mode = FxMode.CRACK,
                seed = shape.seed + i, progress = heat,
            )
        }
        val ring = Ease.outCubic(since / RING_TICKS)
        if (since < RING_TICKS) {
            val floor = Vec3d(ground.x, floorY + 0.03, ground.z)
            val outer = radius * (0.4f + 1.1f * ring)
            FxDraw.flat(f, floor, KronosFx.UP, outer, FxLib.alpha(pal.main, RING_ALPHA * (1f - ring) * (1f - ring)), FxMode.SHOCK, inner = outer * RING_INNER, seed = shape.seed)
            FxDraw.flat(f, floor.add(0.0, 0.02, 0.0), KronosFx.UP, radius * (0.9f + 0.6f * ring), FxLib.alpha(pal.edge, DUST_ALPHA * (1f - ring)), FxMode.DUST, progress = 0.7f, seed = shape.seed + 3)
        }
    }

    override fun over(f: FxFrame) {
        val since = time(f) - hit
        if (since < 0f || since > GLOW_TICKS) return
        FxDraw.glow(f, Vec3d(ground.x, floorY + 0.4, ground.z), radius * 0.9f, FxLib.alpha(pal.core, GLOW_ALPHA * Ease.bump(since / GLOW_TICKS)))
    }

    companion object {
        /** Nach dem Treffer noch so lange (Rückzug, Risse, Schließen). */
        const val AFTER = 26
        const val TEAR_HEIGHT = 9.0
        const val TEAR_LENGTH = 1.7f
        const val TEAR_WIDTH = 0.5f
        const val OPEN_TICKS = 6f
        const val CLOSE_TICKS = 6f
        /** Die Hand erscheint so viele Ticks vor dem Treffer, holt aus und fällt in den letzten [DROP] Ticks. */
        const val PEEK = 11f
        const val DROP = 4f
        const val LIFT = 0.8
        const val HOLD = 4f
        const val RISE = 10f
        const val REBOUND = 0.25
        /** Wie tief die Faust beim Einschlag einsinkt (Anteil ihrer Länge über dem Boden). */
        const val IMPACT_DEPTH = 0.82
        /** Maßstab der Hand je Block Radius: Faustbreite ≈ 1,1 × Radius. */
        const val HAND_SCALE = 0.98f
        const val TILT = 0.22f
        const val HAND_LIGHT = 11
        const val ARM_WIDTH = 0.32f
        const val ARM_ALPHA = 0.45f
        const val SHELL_ALPHA = 0.32f
        const val CRACKS = 6
        const val CRACK_MIN = 0.9f
        const val CRACK_MAX = 1.4f
        const val CRACK_STEP = 0.5f
        const val CRACK_WIDTH = 0.16f
        const val CRACK_GROW = 6f
        const val CRACK_COOL = 24f
        const val CRACK_FADE = 10f
        const val RING_TICKS = 10f
        const val RING_ALPHA = 0.7f
        /** Stoßring: Innenradius als Anteil des Außenradius (Ringbreite im Modus SHOCK). */
        const val RING_INNER = 0.55f
        const val DUST_ALPHA = 0.4f
        const val GLOW_TICKS = 7f
        const val GLOW_ALPHA = 0.45f

        private var soundTick = Long.MIN_VALUE
        private var soundKind = -1

        /** Viele Hände im selben Tick: nur die erste spielt ihren Ortston. */
        internal fun soundSlot(world: ClientWorld, kind: Int): Boolean {
            if (soundTick == world.time && soundKind == kind) return false
            soundTick = world.time
            soundKind = kind
            return true
        }
    }
}

// =====================================================================================================================
// kronos.clock – die Uhrzeiger laufen über den Boden
// =====================================================================================================================

/**
 * Uhrzeiger (`HANDS`): Zwei leuchtende Zeiger mit Zeigerform (schmal an der Nabe, breit vor der Spitze, spitz) laufen
 * genau wie die Trefferprüfung am Server über den Boden (`BossTimings.Kronos.HANDS_TURN`, `HANDS_HOUR_SPEED`); ein
 * kurzer weicher Nachlauf zeigt die Drehrichtung. Ersetzt die alten Strahlen, die alle zwei Ticks neu gesendet wurden.
 */
class KronosClockFx(a: FxArgs) : FxEffect(a.life.coerceAtLeast(10)) {
    private val hub = a.pos
    private val minute = a.a.toDouble()
    private val hour = a.b.toDouble()
    private val reach = a.c.coerceIn(2f, 60f).toDouble()
    private val pal = a.palette
    private val floorY = FxLib.groundY(a.world, hub.x, hub.y, hub.z) + FxLib.GROUND_LIFT + 0.02
    private val turn = PI * 2.0 / BossTimings.Kronos.HANDS_TURN

    /** Überarbeitung 4 (P20): Die Zeiger liegen auf dem Boden jeder Ebene auf (Umgang +2, Podest +3). */
    private val ground = KronosGround(a.world, floorY - FxLib.GROUND_LIFT - 0.02)

    private fun shape(p: Float): Float =
        if (p < WIDEST) HUB_SHARE + (1f - HUB_SHARE) * KronosFx.smooth(p / WIDEST) else (1f - (p - WIDEST) / (1f - WIDEST)).coerceAtLeast(0f).pow(0.8f)

    private fun drawHand(f: FxFrame, angle: Double, length: Double, alpha: Float, core: Boolean) {
        val dir = Vec3d(cos(angle), 0.0, sin(angle))
        val floor = List(POINTS) { i ->
            val p = Vec3d(hub.x, floorY, hub.z).add(dir.multiply(HUB_GAP + (length - HUB_GAP) * i / (POINTS - 1.0)))
            Vec3d(p.x, ground.at(p.x, p.z) + FxLib.GROUND_LIFT + 0.02, p.z)
        }
        val half = BossTimings.Kronos.HANDS_WIDTH / 2f
        FxDraw.strip(
            f, floor, KronosFx.UP, width = { p -> half * shape(p) },
            color = { p -> FxLib.alpha(FxLib.mix(pal.edge, pal.main, p), alpha * FLOOR_ALPHA) }, mode = FxMode.RIBBON, seed = 11,
        )
        if (!core) return
        val raised = floor.map { it.add(0.0, hub.y - floorY, 0.0) }
        FxDraw.ribbon(
            f, raised, width = { p -> CORE_WIDTH * shape(p) },
            color = { p -> FxLib.alpha(FxLib.mix(pal.main, pal.core, p), alpha * CORE_ALPHA) }, mode = FxMode.RIBBON, seed = 12,
        )
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val a = KronosFx.smooth(t / FADE_IN) * KronosFx.smooth((life - t) / FADE_OUT)
        if (a <= 0.01f) return
        val hands = listOf(
            Triple(minute, 1.0, reach),
            Triple(hour, -BossTimings.Kronos.HANDS_HOUR_SPEED, reach * 0.7),
        )
        for ((start, speed, length) in hands) {
            for (j in TRAIL downTo 1) {
                val lag = (t - j * TRAIL_GAP).coerceAtLeast(0f)
                drawHand(f, start + speed * lag * turn, length, a * (1f - j / (TRAIL + 1f)).pow(2) * TRAIL_ALPHA, core = false)
            }
            drawHand(f, start + speed * t * turn, length, a, core = true)
        }
        FxDraw.flat(f, Vec3d(hub.x, floorY + 0.01, hub.z), KronosFx.UP, HUB_RING, FxLib.alpha(pal.main, 0.55f * a), FxMode.RUNE, inner = HUB_RING * 0.72f, rotation = -t * 0.02f, seed = 13)
    }

    override fun over(f: FxFrame) {
        val t = time(f)
        val a = KronosFx.smooth(t / FADE_IN) * KronosFx.smooth((life - t) / FADE_OUT)
        FxDraw.glow(f, hub, HUB_GLOW, FxLib.alpha(pal.core, 0.45f * a))
    }

    companion object {
        /** Überarbeitung 4: dichter abgetastet, damit die Stufe zum Umgang kurz bleibt. */
        const val POINTS = 36
        const val HUB_GAP = 1.2
        const val WIDEST = 0.78f
        const val HUB_SHARE = 0.35f
        const val CORE_WIDTH = 0.32f
        const val FLOOR_ALPHA = 0.38f
        const val CORE_ALPHA = 0.85f
        const val TRAIL = 3
        const val TRAIL_GAP = 2.2f
        const val TRAIL_ALPHA = 0.6f
        const val FADE_IN = 4f
        const val FADE_OUT = 8f
        const val HUB_RING = 2.4f
        const val HUB_GLOW = 1.4f
    }
}

// =====================================================================================================================
// kronos.shards – Sanduhr-Splitter fliegen zu den Sanduhren
// =====================================================================================================================

/**
 * Rückspulung (Vorbild Void `ShardEffect`): [COUNT] glatte Kristalle (zwei gespiegelte `FxMeshes.CONE`, schattiert über
 * `fx_solid`, mit leuchtender Hülle) lösen sich versetzt aus dem Stundenglas auf Kronos' Brust und fliegen auf einer
 * Doppelspirale um die gerade Bahn zur Sanduhr. Jeder zieht eine kurze Spur; wo der letzte ankommt, glimmt es kurz.
 */
class KronosShardsFx(a: FxArgs) : FxEffect(a.life.coerceAtLeast(FLIGHT.toInt() + COUNT * STAGGER.toInt() + 8)) {
    private val target = a.pos
    private val source = a.entity?.let { FxLib.body(it, 1f, CHEST) } ?: target.add(0.0, 6.0, 0.0)
    private val axis = FxLib.unit(target.subtract(source))
    private val b1 = FxLib.basis(axis).first
    private val b2 = axis.crossProduct(b1)
    private val pal = a.palette
    private val rnd = Random(a.seed)
    private val phase = FloatArray(COUNT) { rnd.nextFloat() * 2f * PI.toFloat() }
    private val spinRate = FloatArray(COUNT) { 0.2f + rnd.nextFloat() * 0.25f }

    private fun u(i: Int, t: Float): Float = (t - i * STAGGER) / FLIGHT

    private fun pos(i: Int, uRaw: Float): Vec3d {
        val u = Ease.inOut(uRaw)
        val base = source.lerp(target, u.toDouble()).add(0.0, sin(PI * u) * ARC, 0.0)
        val r = SPIRAL * sin(PI * u)
        val sign = if (i % 2 == 0) 1.0 else -1.0
        val ang = phase[i] + sign * u * TURNS * 2.0 * PI
        return base.add(b1.multiply(cos(ang) * r)).add(b2.multiply(sin(ang) * r))
    }

    private fun crystal(f: FxFrame, i: Int, t: Float, solid: Boolean) {
        val k = u(i, t)
        if (k <= 0f || k >= 1f) return
        val at = pos(i, k)
        val dir = FxLib.unit(pos(i, (k + 0.02f).coerceAtMost(1f)).subtract(at), axis)
        val grow = KronosFx.smooth(k / 0.15f) * KronosFx.smooth((1f - k) / 0.1f)
        val w = WIDTH * grow * (if (solid) 1f else HULL)
        val h = LENGTH * grow * (if (solid) 1f else HULL)
        val spin = t * spinRate[i]
        for (up in listOf(dir, dir.negate())) {
            val m = FxDraw.place(at, up = up, spin = spin, scaleXYZ = Vector3f(w, h, w))
            if (solid) FxDraw.mesh(f, FxMeshes.CONE, m, CRYSTAL, FxSolidMode.PLAIN, solid = true, light = FxDraw.FULL_BRIGHT)
            else FxDraw.mesh(f, FxMeshes.CONE, m, FxLib.alpha(pal.main, HULL_ALPHA), FxMode.SHELL, seed = i)
        }
        if (!solid) {
            val trail = List(5) { j -> pos(i, (k - j * TRAIL_STEP).coerceAtLeast(0f)) }
            FxDraw.ribbon(f, trail, width = { p -> TRAIL_WIDTH * (1f - p) * grow }, color = { p -> FxLib.alpha(pal.alt, 0.6f * (1f - p)) }, mode = FxMode.RIBBON, seed = i + 20)
        }
    }

    override fun solid(f: FxFrame) {
        val t = time(f)
        for (i in 0 until COUNT) crystal(f, i, t, solid = true)
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        for (i in 0 until COUNT) crystal(f, i, t, solid = false)
    }

    override fun over(f: FxFrame) {
        val last = time(f) - ((COUNT - 1) * STAGGER + FLIGHT)
        if (last < 0f || last > ARRIVE_GLOW) return
        FxDraw.glow(f, target, 0.9f, FxLib.alpha(pal.core, 0.45f * Ease.bump(last / ARRIVE_GLOW)))
    }

    companion object {
        const val COUNT = 6
        const val STAGGER = 1.6f
        const val FLIGHT = 18f
        /** Stundenglas auf der Brust: Anteil der Körperhöhe. */
        const val CHEST = 0.62
        const val ARC = 3.0
        const val SPIRAL = 1.4
        const val TURNS = 1.25
        const val WIDTH = 0.17f
        const val LENGTH = 0.42f
        const val HULL = 1.4f
        const val HULL_ALPHA = 0.45f
        const val TRAIL_STEP = 0.05f
        const val TRAIL_WIDTH = 0.12f
        const val ARRIVE_GLOW = 8f
        /** Glas der Splitter: blasses Sternweiß-Indigo (kein Gold – Gold ist die Sanduhr selbst). */
        const val CRYSTAL = 0xFFD6DCFF.toInt()
    }
}

// =====================================================================================================================
// kronos.ghosts – Nachbilder laufen rückwärts
// =====================================================================================================================

/**
 * Nachbilder bei der Rückspulung: An den letzten Orten, an denen Kronos stand, erscheint sein ganzer Körper als
 * blasse, leuchtende Schale (zweites Modell aus [KronosParts], `entityTranslucentEmissive`) – vom neuesten zum ältesten,
 * als liefe die Zeit zurück; Atmen und Uhrenkranz laufen dabei rückwärts. Jedes blendet weich ein und aus.
 */
class KronosGhostsFx(a: FxArgs) : FxEffect(a.life.coerceAtLeast(MIN_LIFE)) {
    private class Ghost(val feet: Vec3d, val yaw: Float)

    private val form = a.b.toInt().coerceIn(0, 1)
    private val pal = a.palette
    private val ghosts: List<Ghost> = a.extra.split(';').mapNotNull { e ->
        val v = e.split(',').mapNotNull { it.trim().toDoubleOrNull() }
        if (v.size < 4) null else Ghost(Vec3d(v[0], v[1], v[2]), v[3].toFloat())
    }.take(MAX)

    private fun alpha(i: Int, t: Float): Float {
        val s = t - i * STAGGER
        if (s <= 0f || s >= SHOW) return 0f
        return PEAK * KronosFx.smooth(s / RISE) * KronosFx.smooth((SHOW - s) / (SHOW - RISE))
    }

    override fun solid(f: FxFrame) {
        val t = time(f)
        val layer = RenderLayers.entityTranslucentEmissive(KronosParts.TEXTURE)
        for ((i, g) in ghosts.withIndex()) {
            val a = alpha(i, t)
            if (a <= 0.01f) continue
            val argb = ((a * 255f).toInt() shl 24) or (TINT and 0xFFFFFF)
            // Die Zeit läuft zurück: Atmen und Kranz rückwärts.
            KronosParts.renderGhost(f.consumers, f.camPos, g.feet, g.yaw, form, TIME_BASE - t * 2f, GHOST_ID - i, layer, FxDraw.FULL_BRIGHT, argb)
        }
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        for ((i, g) in ghosts.withIndex()) {
            val a = alpha(i, t)
            if (a <= 0.01f) continue
            FxDraw.glow(f, g.feet.add(0.0, 6.2 * KronosShardsFx.CHEST * (1f + form * KronosEntity.FORM_GROWTH), 0.0), 1.2f, FxLib.alpha(pal.main, a))
        }
    }

    companion object {
        const val MIN_LIFE = 40
        const val MAX = 3
        const val STAGGER = 8f
        const val SHOW = 28f
        const val RISE = 6f
        const val PEAK = 0.34f
        const val TINT = 0xFFB8C4FF.toInt()
        const val TIME_BASE = 4000f
        const val GHOST_ID = -2000
    }
}

// =====================================================================================================================
// kronos.sky – Risse in der Kuppel des Tartaros
// =====================================================================================================================

/**
 * Dauer-Effekt an Kronos (`FxAttach`, Vorbild Void `SkyRiftEffect`): Risse in der Innenseite der Kuppel über dem
 * Thronsaal (Maße aus `TartarusPlans`, wie `PalacePlan.dome`). Phase I drei, Phase II sechs; je weniger Leben, desto
 * breiter und heißer; sie atmen ruhig (`Tear`, ≤ 0,8 Hz) und öffnen sich weich, wenn neue dazukommen. Steht die Zeit,
 * stehen auch sie (`FxClock`). Beim Zerfall schließen sie sich. Ohne bekannte Saalmitte (Szene vor dem Kampf): nichts.
 */
class KronosSkyFx(private val entity: Entity?, private val fixed: Vec3d?, private val demoForm: Int, life: Int) : FxEffect(life) {
    private class Crack(val azimuth: Double, val elevation: Double, val tilt: Double, val length: Float, val width: Float, val shape: Tear.Shape) {
        var open = 0f
    }

    private val pal = KronosFx.AEON
    private val cracks: List<Crack> = Random(SEED.toLong()).let { rnd ->
        List(MAX) { i ->
            Crack(
                azimuth = i * GOLDEN + rnd.nextDouble() * 0.4,
                elevation = Math.toRadians(ELEV_MIN + rnd.nextDouble() * (ELEV_MAX - ELEV_MIN)),
                tilt = (rnd.nextDouble() - 0.5) * 1.2,
                length = LENGTH_MIN + rnd.nextFloat() * (LENGTH_MAX - LENGTH_MIN),
                width = WIDTH_MIN + rnd.nextFloat() * (WIDTH_MAX - WIDTH_MIN),
                shape = Tear.Shape(SEED + i * 31, 17),
            )
        }
    }
    private var lastT = Float.NaN

    private fun hall(): Vec3d? {
        fixed?.let { return it }
        val k = entity as? KronosEntity ?: return null
        val h = k.hall
        if (h == BlockPos.ORIGIN) return null
        return Vec3d(h.x + 0.5, h.y.toDouble(), h.z + 0.5)
    }

    override fun energy(f: FxFrame) {
        val center = hall() ?: return
        val t = time(f)
        val dt = if (lastT.isNaN()) 0f else (t - lastT).coerceIn(0f, 4f)
        lastT = t
        val kronos = entity as? KronosEntity
        val form = kronos?.form ?: demoForm
        val count = if (form > 0) COUNT_2 else COUNT_1
        val hurt = kronos?.let { 1f - (it.health / it.maxHealth).coerceIn(0f, 1f) } ?: 0.5f
        val fade = 1f - (kronos?.let { KronosDeath.progress(it.id) } ?: 0f)
        val cy = center.y + TartarusPlans.DRUM_TOP - 2.0
        val ra = TartarusPlans.HALL.toDouble()
        val rb = TartarusPlans.DOME_RISE - 1.0
        for ((i, c) in cracks.withIndex()) {
            val target = if (i < count) 1f else 0f
            c.open += (target - c.open) * (1f - exp(-dt / OPEN_TAU))
            val o = c.open * fade
            if (o <= 0.01f) continue
            val r = ra * cos(c.elevation)
            val surface = Vec3d(center.x + r * cos(c.azimuth), cy + rb * sin(c.elevation), center.z + r * sin(c.azimuth))
            val n = Vec3d((surface.x - center.x) / (ra * ra), (surface.y - cy) / (rb * rb), (surface.z - center.z) / (ra * ra)).normalize()
            val at = surface.subtract(n.multiply(INSET))
            val meridian = KronosFx.UP.subtract(n.multiply(n.y)).let { if (it.lengthSquared() < 1e-6) Vec3d(1.0, 0.0, 0.0) else it.normalize() }
            val east = n.crossProduct(meridian)
            val along = meridian.multiply(cos(c.tilt)).add(east.multiply(sin(c.tilt)))
            val across = n.crossProduct(along)
            val wide = 0.7f + 0.5f * hurt
            Tear.draw(f, at, along, across, c.length, c.width * wide, o, c.shape, pal, t, HOT_BASE + HOT_HURT * hurt)
            FxDraw.glow(f, at.subtract(n.multiply(0.6)), c.length * 0.45f, FxLib.alpha(pal.main, GLOW_ALPHA * o))
        }
    }

    companion object {
        const val ATTACH_LIFE = 24000
        const val DEMO_LIFE = 400
        const val MAX = 7
        const val COUNT_1 = 3
        const val COUNT_2 = 6
        const val SEED = 0x4B524F
        const val GOLDEN = 2.399963
        const val ELEV_MIN = 22.0
        const val ELEV_MAX = 62.0
        const val LENGTH_MIN = 9f
        const val LENGTH_MAX = 16f
        const val WIDTH_MIN = 1.3f
        const val WIDTH_MAX = 2.2f
        /** So weit unter der Kuppelfläche (Blöcke), damit nichts im Stein steckt. */
        const val INSET = 0.8
        /** Öffnen/Schließen: Zeitkonstante (Ticks). */
        const val OPEN_TAU = 30f
        const val HOT_BASE = 0.2f
        const val HOT_HURT = 0.45f
        const val GLOW_ALPHA = 0.1f
    }
}

// =====================================================================================================================
// kronos.death – er zerfällt von den Händen her in Licht und Sand
// =====================================================================================================================

/**
 * Was der Zerfall gerade macht, je Wesen: Fortschritt für das Modell (`KronosModel` blendet ganze Glieder aus) und die
 * Weltlagen der Glieder, die der Renderer meldet. Nach dem Ende bleibt das Wesen noch [HOLD_TICKS] unsichtbar, dann
 * wird der Eintrag vergessen.
 */
object KronosDeath {
    internal class State {
        var k = 0f
        var done = -1L
        /** Letzter Tick, in dem der Effekt lief (fällt er weg, z. B. durch `Cine.clearVfx`, gilt er als beendet). */
        var beat = -1L
        val anchors = arrayOfNulls<Vec3d>(KronosParts.DISSOLVE_GROUPS.size)
    }

    const val HOLD_TICKS = 200L
    private const val LOST_TICKS = 5L
    private const val MAX_STATES = 8
    private val states = HashMap<Int, State>()

    private fun now(): Long = MinecraftClient.getInstance().world?.time ?: 0L

    internal fun start(id: Int): State {
        if (states.size >= MAX_STATES) states.clear()
        return State().also {
            it.beat = now()
            states[id] = it
        }
    }

    internal fun beat(state: State) {
        state.beat = now()
    }

    internal fun finish(id: Int, state: State) {
        if (states[id] === state) state.done = now()
    }

    /** 0 = ganz, 1 = zerfallen. */
    fun progress(id: Int): Float {
        val s = states[id] ?: return 0f
        if (s.done < 0 && now() - s.beat > LOST_TICKS) s.done = s.beat
        if (s.done >= 0 && (now() - s.done > HOLD_TICKS || now() < s.done)) {
            states.remove(id)
            return 0f
        }
        return s.k
    }

    /** Läuft gerade ein Zerfall, der die Lagen der Glieder braucht? */
    fun wantsAnchors(id: Int): Boolean = states[id]?.let { it.done < 0 } ?: false

    fun anchor(id: Int, group: Int, pos: Vec3d) {
        states[id]?.anchors?.let { if (group in it.indices) it[group] = pos }
    }
}

/**
 * Kronos' Tod (Szene `kronos.end` von P12; feste Parameter: pos = Füße, entity = Kronos, a = Höhe, life = 200). Ab
 * [START] verschwinden seine Glieder gruppenweise von den Händen her (`KronosParts.DISSOLVE_GROUPS`); wo eines geht,
 * steigen Sandbänder auf, Staub treibt nach oben und eine Schale löst sich mit glühender Kante auf. Am Ende bleibt ein
 * stiller Lichtpunkt am Stundenglas, der weich vergeht. Keine Explosion, kein Bildschirmblitz.
 *
 * Ohne Wesen (Bühne ohne `entity`) sucht der Effekt den nächsten Kronos um pos; gibt es keinen (z. B. `/hha fx play`),
 * zerfällt eine eigene Figur an pos (zweites Modell).
 */
class KronosDeathFx(a: FxArgs) : FxEffect(a.life.coerceAtLeast(MIN_LIFE)) {
    private class Burst(val group: Int, val at: Vec3d, val t0: Float, val size: Float, val seed: Int)

    private val world = a.world
    private val kronos: KronosEntity? = (a.entity as? KronosEntity) ?: world.entities.asSequence()
        .filterIsInstance<KronosEntity>().filter { it.squaredDistanceTo(a.pos) < SEARCH * SEARCH }
        .minByOrNull { it.squaredDistanceTo(a.pos) }
    private val ghost = kronos == null
    private val id = kronos?.id ?: (GHOST_ID - (a.seed % 500).toInt())
    private val feet = kronos?.entityPos ?: a.pos
    private val yaw = kronos?.bodyYaw ?: FxLib.flat(a.dir, Vec3d(0.0, 0.0, 1.0)).let { d -> Math.toDegrees(atan2(d.x, -d.z)).toFloat() }
    private val form = kronos?.form ?: 0
    private val height = if (a.a > 1f) a.a else 6.2f * (1f + form * KronosEntity.FORM_GROWTH)
    private val pal = a.palette
    private val state = KronosDeath.start(id)
    private val bursts = ArrayList<Burst>()
    private var passed = 0
    private var chest = feet.add(0.0, height * KronosShardsFx.CHEST, 0.0)

    private fun progressAt(t: Float): Float = KronosFx.clamp01((t - life * START) / (life * (SPAN - START)))

    override fun tick(world: ClientWorld) {
        val t = KronosFxClockAge.of(this)
        KronosDeath.beat(state)
        state.k = progressAt(t)
        val groups = KronosParts.DISSOLVE_GROUPS.size
        while (passed < groups && state.k >= KronosParts.DISSOLVE_AT[passed]) {
            val at = state.anchors[passed] ?: feet.add(0.0, (height * FALLBACK_HEIGHT[passed]).toDouble(), 0.0)
            bursts += Burst(passed, at, t, BURST_SIZE[passed] * height / 6.2f, passed * 97 + id)
            if (passed == 0 && KronosHandFx.soundSlot(world, 1)) FxLib.sound(world, at, HhaSounds.RPG_DISSOLVE, 0.6f, 0.7f)
            passed++
        }
        state.anchors[CHEST_GROUP]?.let { chest = it }
        if (age >= life - 1) KronosDeath.finish(id, state)
    }

    override fun solid(f: FxFrame) {
        if (!ghost) return
        // Eigene Figur (kein Wesen in der Nähe): das zweite Modell, mit demselben Zerfall.
        KronosParts.renderGhost(
            f.consumers, f.camPos, feet, yaw, form, time(f), id, RenderLayers.entityCutoutNoCull(KronosParts.TEXTURE),
            KronosFx.light(feet.add(0.0, 2.0, 0.0), 6), -1,
        ) { g, p -> state.anchors[g] = p }
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        for (b in bursts) {
            val s = t - b.t0
            if (s < 0f || s > BURST_LIFE) continue
            val k = s / BURST_LIFE
            val fade = (1f - k).pow(1.5f)
            // Sandbänder: weiche Spiralen, die vom Glied aufsteigen und sich auflösen.
            for (j in 0 until BANDS) {
                val ph = b.seed * 0.7 + j * 2.0 * PI / BANDS
                val rise = (SAND_RISE * Ease.outQuad(k) + 0.3) * b.size
                val pts = List(9) { q ->
                    val p = q / 8.0
                    val ang = ph + p * 2.4 + s * 0.06
                    val r = b.size * SAND_SWIRL * (0.4 + p)
                    b.at.add(cos(ang) * r, rise * p + s * SAND_LIFT * b.size, sin(ang) * r)
                }
                FxDraw.ribbon(
                    f, pts, width = { p -> SAND_WIDTH * b.size * (1f - p) * (0.5f + 0.5f * Ease.bump(p)) },
                    color = { p -> FxLib.alpha(FxLib.mix(SAND, pal.alt, p * 0.6f), fade * SAND_ALPHA * Ease.bump(p * 0.85f + 0.15f)) },
                    mode = FxMode.RIBBON, seed = b.seed + j, scroll = s * 0.1f,
                )
            }
            // Staub treibt nach oben.
            for (j in 0 until PUFFS) {
                val at = b.at.add((j - 1) * 0.3 * b.size, s * DUST_LIFT * (1 + j * 0.3), (j % 2 - 0.5) * 0.4 * b.size)
                FxDraw.flat(f, at, KronosFx.facing(f, at), b.size * (0.4f + 1.2f * Ease.outCubic(k)), FxLib.alpha(SAND, DUST_ALPHA * fade), FxMode.DUST, progress = 0.6f, seed = b.seed + 40 + j)
            }
            // Die Schale des Glieds löst sich mit glühender Kante.
            if (s < SHELL_TICKS) {
                val e = s / SHELL_TICKS
                val m = FxDraw.place(b.at.add(0.0, e * 0.6, 0.0), scale = b.size * SHELL_SIZE * (1f + 0.5f * e))
                FxDraw.mesh(f, FxMeshes.SPHERE, m, FxLib.alpha(pal.main, SHELL_ALPHA * (1f - e)), FxMode.SHELL, seed = b.seed, progress = 1f - e)
            }
        }
        // Ein ruhiger Strom heller Körnchen, solange er zerfällt.
        val k = progressAt(t)
        if (k > 0f && k < 1f) {
            for (j in 0 until MOTES) {
                val cycle = ((t * MOTE_SPEED + j / MOTES.toFloat()) % 1f)
                val base = chest.add(cos(j * 2.3) * 0.8, -height * 0.3 + cycle * height * 0.9, sin(j * 2.3) * 0.8)
                FxDraw.ribbon(f, listOf(base, base.add(0.0, 0.35, 0.0)), width = { 0.05f }, color = { _ -> FxLib.alpha(pal.core, 0.6f * Ease.bump(cycle) * (1f - k * 0.5f)) }, mode = FxMode.RIBBON, seed = j)
            }
        }
    }

    override fun over(f: FxFrame) {
        val kk = time(f) / life
        if (kk < POINT_FROM) return
        val e = (kk - POINT_FROM) / (1f - POINT_FROM)
        val a = Ease.bump(e)
        FxDraw.glow(f, chest, POINT_SIZE * (0.4f + 0.6f * a), FxLib.alpha(pal.core, POINT_ALPHA * a))
        FxDraw.glow(f, chest, POINT_SIZE * 2.6f, FxLib.alpha(pal.main, POINT_ALPHA * 0.3f * a))
    }

    companion object {
        const val MIN_LIFE = 80
        const val SEARCH = 16.0
        const val GHOST_ID = -3000
        /** Zerfall von [START] bis [SPAN] der Lebenszeit; danach der Lichtpunkt ab [POINT_FROM]. */
        const val START = 0.06f
        const val SPAN = 0.82f
        const val POINT_FROM = 0.78f
        const val POINT_SIZE = 0.5f
        /** Höchste Deckkraft des Lichtpunkts in der Welt (kein Bildschirmblitz). */
        const val POINT_ALPHA = 0.6f
        const val CHEST_GROUP = 8
        val FALLBACK_HEIGHT = floatArrayOf(0.45f, 0.5f, 0.55f, 0.7f, 0.8f, 0.6f, 0.9f, 0.92f, 0.65f, 0.15f, 0.4f)
        val BURST_SIZE = floatArrayOf(0.6f, 0.75f, 0.9f, 1.0f, 1.1f, 1.2f, 0.9f, 1.0f, 1.5f, 1.0f, 1.3f)
        const val BURST_LIFE = 60f
        const val BANDS = 4
        const val SAND_RISE = 4.5
        const val SAND_SWIRL = 0.45
        const val SAND_LIFT = 0.025
        const val SAND_WIDTH = 0.16f
        const val SAND_ALPHA = 0.6f
        const val PUFFS = 3
        const val DUST_LIFT = 0.04
        const val DUST_ALPHA = 0.32f
        const val SHELL_TICKS = 20f
        const val SHELL_SIZE = 0.55f
        const val SHELL_ALPHA = 0.5f
        const val MOTES = 8
        const val MOTE_SPEED = 0.012f
        /** Sand: blasses Grau mit kühlem Stich (bewusst kein Gold). */
        const val SAND = 0xFFDAD6D0.toInt()
    }
}

/** Sichtbares Alter eines Effekts im Tick (ohne Zwischenbild), mit Zeitfaktor – für Zustände, die im Tick fortschreiten. */
private object KronosFxClockAge {
    fun of(effect: FxEffect): Float = dev.henny.hha.client.vfx.fx.FxClock.age(effect, effect.age.toFloat())
}

// =====================================================================================================================
// Überarbeitung 4 (P20): Sanduhr-Strahl, alternde Säulen, Rückspulung durch den Strahl
// =====================================================================================================================

/**
 * Höhe des festen Bodens je Spalte, einmal gesucht und gemerkt (Saal mit Umgang +2 und Podest +3; `FxLib.groundY` sucht
 * zwei Blöcke darüber). So liegen Flächen auch auf den Ebenen auf, statt unsichtbar unter deren Belag.
 */
internal class KronosGround(private val world: ClientWorld, private val baseY: Double) {
    private val cache = HashMap<Long, Double>()

    fun at(x: Double, z: Double): Double {
        val key = BlockPos.asLong(kotlin.math.floor(x).toInt(), 0, kotlin.math.floor(z).toInt())
        return cache.getOrPut(key) { FxLib.groundY(world, x, baseY, z) }
    }
}

/**
 * `kronos.ray` – der Sanduhr-Strahl des Stillstands (PLAN 4.2, Briefing P20 Schritt 1). Ein ruhiger, glatter
 * Lichtfächer aus Sand und Sternenlicht, der von Kronos' erhobener Hand über den Boden schwenkt; ein Bild, kein Flackern.
 *
 * Kodierung (Server `KronosStasis.Run.standstill`): pos = Boden unter der Hand, dir = Startkante, a = Ansage-Ticks,
 * b = Schwenk-Ticks, c = Drehsinn (+1 wachsende Winkel), d = Reichweite, entity = Kronos, life = Ansage + Schwenk + Ausklang.
 * Geometrie wie am Server: Vorderkante `RAY_ARC / Schwenk` je Tick ab der Startkante bis `RAY_ARC`, Fächer
 * 2 · `RAY_HALF_ANGLE` hinter ihr, nie vor die Startkante.
 *
 * - Ansage: der überstrichene Halbkreis blass (liegt auch auf Umgang und Podest), die Startkante hell, ein Schimmer
 *   läuft am Rand in Drehrichtung.
 * - Schwenk: der Strahl als Fächer weicher Lichtstrahlen am Boden, vorn ein niedriger Lichtvorhang; davor bleibt der
 *   restliche Bereich blass sichtbar.
 *
 * Unter dem Zeitstopp-Pass klar lesbar: gezeichnet in der Über-Phase (nach dem Pass, also nicht entsättigt) und in
 * Echtzeit (nicht über `FxClock`, friert also nicht ein; Ablauf ebenfalls in Echtzeit, siehe [expired]).
 */
class KronosRayFx(a: FxArgs) : FxEffect(a.life.coerceAtLeast(MIN_LIFE)) {
    private val apex = a.pos
    private val startDeg = Math.toDegrees(atan2(a.dir.z, a.dir.x))
    private val ansage = a.a.coerceIn(1f, 400f)
    private val sweep = a.b.coerceIn(1f, 200f)
    private val sign = if (a.c < 0f) -1.0 else 1.0
    private val radius = a.d.coerceIn(4f, 64f).toDouble()
    private val entity = a.entity
    private val pal = a.palette
    private val ground = KronosGround(a.world, apex.y)
    private val arc = KronosSigTimings.RAY_ARC.toDouble()
    private val width = 2.0 * KronosSigTimings.RAY_HALF_ANGLE
    private val degPerTick = arc / sweep

    /** Echtzeit (der Strahl läuft im Zeitstopp weiter). */
    private fun now(f: FxFrame): Float = age + f.tickDelta

    /**
     * Verlässt Kronos die Pose `STASIS` (Schwenk vorbei, Phasenwechsel, Szene, Abbruch), blendet der Strahl ab dort in
     * [END_FADE] Ticks aus; −1 = noch nicht.
     */
    private var endAt = -1

    override fun tick(world: ClientWorld) {
        val titan = entity as? KronosEntity
        if (endAt < 0 && age > POSE_GRACE && (titan == null || titan.isRemoved || titan.pose != KronosPose.STASIS)) endAt = age
    }

    override fun expired(): Boolean = age >= life || (endAt >= 0 && age >= endAt + END_FADE)

    private fun ending(t: Float): Float = if (endAt < 0) 1f else KronosFx.clamp01(1f - (t - endAt) / END_FADE)

    private fun point(rel: Double, r: Double): Vec3d {
        val rad = Math.toRadians(startDeg + sign * rel)
        val x = apex.x + cos(rad) * r
        val z = apex.z + sin(rad) * r
        return Vec3d(x, ground.at(x, z) + LIFT, z)
    }

    /** Ein Lichtstrahl am Boden von der Hand bis zur Reichweite, im Winkel [rel] ab der Startkante. */
    private fun spoke(f: FxFrame, rel: Double, alpha: Float, step: Double, seed: Int) {
        if (alpha <= 0.004f) return
        val pts = List(SPOKE_POINTS) { i -> point(rel, START_GAP + (radius - START_GAP) * i / (SPOKE_POINTS - 1.0)) }
        val spread = Math.toRadians(step) * SPOKE_OVERLAP
        FxDraw.strip(
            f, pts, KronosFx.UP, width = { p -> ((START_GAP + (radius - START_GAP) * p) * spread * 0.5).toFloat() + 0.05f },
            color = { p ->
                FxLib.alpha(FxLib.mix(SAND, pal.core, 0.35f + 0.4f * p), alpha * (0.55f + 0.45f * KronosFx.smooth(p * 3f)) * (1f - 0.35f * p))
            },
            mode = FxMode.RIBBON, seed = seed,
        )
    }

    /** Fächer von [from] bis [to] (Grad ab der Startkante), Deckkraft je Winkel aus [alpha]. */
    private fun fan(f: FxFrame, from: Double, to: Double, alpha: (Double) -> Float) {
        if (to - from < 0.2) return
        val count = ceil((to - from) / FAN_STEP).toInt().coerceAtLeast(1)
        val step = (to - from) / count
        for (i in 0..count) {
            val rel = from + step * i
            spoke(f, rel, alpha(rel), step, 40 + i)
        }
    }

    /** Lichtvorhang an der Vorderkante: je Stück gleicher Bodenhöhe eine Wand (liegt auch auf dem Umgang auf). */
    private fun curtain(f: FxFrame, rel: Double, alpha: Float) {
        if (alpha <= 0.01f) return
        val color = FxLib.alpha(FxLib.mix(pal.core, SAND, 0.4f), alpha)
        var runStart: Vec3d? = null
        var last: Vec3d? = null
        var r = START_GAP
        while (r <= radius + 1e-6) {
            val p = point(rel, r)
            val prev = last
            val s = runStart
            if (s == null) {
                runStart = p
            } else if (prev != null && abs(p.y - prev.y) > 0.3) {
                if (s.distanceTo(prev) > 0.5) FxDraw.wall(f, s, prev, CURTAIN_HEIGHT, color, FxMode.RIBBON, seed = 7)
                runStart = p
            }
            last = p
            r += CURTAIN_STEP
        }
        val s = runStart
        val e = last
        if (s != null && e != null && s.distanceTo(e) > 0.5) FxDraw.wall(f, s, e, CURTAIN_HEIGHT, color, FxMode.RIBBON, seed = 7)
    }

    /** Hand: woher das Licht kommt (an Kronos, sonst über dem Fußpunkt). */
    private fun hand(f: FxFrame): Vec3d =
        entity?.takeIf { !it.isRemoved }?.let { FxLib.body(it, f.tickDelta, HAND_SHARE) } ?: apex.add(0.0, HAND_FALLBACK, 0.0)

    override fun over(f: FxFrame) {
        val t = now(f)
        val base = KronosFx.smooth(t / FADE_IN) * KronosFx.clamp01((life - t) / FADE_OUT) * ending(t)
        if (base <= 0.01f) return
        if (t < ansage) {
            // Ansage: blasser Halbkreis, helle Startkante, Schimmer in Drehrichtung.
            val k = t / ansage
            fan(f, 0.0, arc) { AREA_ALPHA * base * (0.6f + 0.4f * k) }
            spoke(f, 0.0, EDGE_ALPHA * base, FAN_STEP * 0.6, 3)
            val glint = (t % GLINT_PERIOD) / GLINT_PERIOD
            spoke(f, glint * GLINT_ARC, GLINT_ALPHA * base * Ease.bump(glint), FAN_STEP, 5)
        } else {
            val lead = ((t - ansage) * degPerTick).coerceAtMost(arc)
            val trail = max(0.0, lead - width)
            val passed = t > ansage + sweep
            // Was noch kommt, bleibt blass sichtbar; der Strahl selbst ist hell, vorn am hellsten.
            if (!passed) fan(f, lead, arc) { AHEAD_ALPHA * base }
            fan(f, trail, lead) { rel -> BEAM_ALPHA * base * (0.45f + 0.55f * KronosFx.smooth(((rel - trail) / width).toFloat())) }
            if (!passed) curtain(f, lead, CURTAIN_ALPHA * base)
        }
        val h = hand(f)
        FxDraw.glow(f, h, HAND_GLOW, FxLib.alpha(FxLib.mix(pal.core, SAND, 0.3f), HAND_GLOW_ALPHA * base))
        FxDraw.ribbon(
            f, listOf(h, Vec3d(apex.x, ground.at(apex.x, apex.z) + LIFT, apex.z)), width = { p -> 0.35f * (1f - 0.5f * p) },
            color = { p -> FxLib.alpha(FxLib.mix(pal.core, SAND, 0.5f), COLUMN_ALPHA * base * (1f - 0.6f * p)) }, mode = FxMode.RIBBON, seed = 9,
        )
    }

    companion object {
        const val MIN_LIFE = 20
        /** Blassweißer Sand (kein Gold: Gold heißt im Kampf „sicher“). */
        const val SAND = 0xFFF2EAD8.toInt()
        const val LIFT = 0.06
        const val START_GAP = 1.2
        const val SPOKE_POINTS = 16
        const val SPOKE_OVERLAP = 1.35
        const val FAN_STEP = 2.5
        const val AREA_ALPHA = 0.10f
        const val EDGE_ALPHA = 0.75f
        const val GLINT_ALPHA = 0.35f
        const val GLINT_PERIOD = 20f
        const val GLINT_ARC = 60.0
        const val AHEAD_ALPHA = 0.06f
        const val BEAM_ALPHA = 0.42f
        const val CURTAIN_ALPHA = 0.38f
        const val CURTAIN_HEIGHT = 2.6f
        const val CURTAIN_STEP = 1.0
        const val HAND_SHARE = 0.75
        const val HAND_FALLBACK = 4.6
        const val HAND_GLOW = 1.6f
        const val HAND_GLOW_ALPHA = 0.55f
        const val COLUMN_ALPHA = 0.3f
        const val FADE_IN = 6f
        const val FADE_OUT = 10f
        /** Ausblenden, sobald er die Pose verlässt (Ticks); so lange nach dem Start wird die Pose noch nicht geprüft. */
        const val END_FADE = 8
        const val POSE_GRACE = 4
    }
}

/**
 * `kronos.age` – eine Säule altert (Briefing P20 Schritt 1): Staub rieselt am Schaft **nach oben**, als liefe die Zeit
 * an ihr schneller; am Fuß liegt ein matter Staubfleck. Mit c = 1 umgekehrt (die Säule kehrt zurück): der Staub sinkt.
 * Kodierung: pos = Fußmitte, a = halbe Breite der Säule, b = Höhe, c = 1 rückwärts, life.
 */
class KronosAgeFx(a: FxArgs) : FxEffect(a.life.coerceAtLeast(MIN_LIFE)) {
    private class Mote(val angle: Double, val start: Float, val speed: Float, val delay: Float, val size: Float)

    private val foot = a.pos
    private val half = a.a.coerceIn(0.5f, 6f).toDouble()
    private val height = a.b.coerceIn(2f, 60f)
    private val back = a.c >= 0.5f
    private val pal = a.palette
    private val floorY = FxLib.groundY(a.world, foot.x, foot.y, foot.z) + FxLib.GROUND_LIFT
    private val motes: List<Mote> = Random(a.seed).let { rnd ->
        List(MOTES) {
            Mote(rnd.nextDouble() * 2 * PI, rnd.nextFloat() * height * 0.8f, 0.6f + rnd.nextFloat() * 0.8f, rnd.nextFloat() * DELAY, 0.12f + rnd.nextFloat() * 0.14f)
        }
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val fade = KronosFx.smooth(t / 4f) * KronosFx.clamp01((life - t) / FADE)
        if (fade <= 0.01f) return
        val color = FxLib.mix(KronosRayFx.SAND, pal.edge, 0.3f)
        for (m in motes) {
            val s = t - m.delay
            if (s <= 0f) continue
            val rise = m.speed * s * RISE_RATE
            val y = if (back) m.start + height * 0.2f - rise else m.start + rise
            if (y < 0f || y > height + 2f) continue
            val r = half + OUTSET + 0.25 * sin(s * 0.2 + m.angle)
            val at = Vec3d(foot.x + cos(m.angle) * r, floorY + y, foot.z + sin(m.angle) * r)
            FxDraw.glow(f, at, m.size, FxLib.alpha(color, MOTE_ALPHA * fade * KronosFx.smooth(s / 5f)))
        }
        FxDraw.flat(
            f, Vec3d(foot.x, floorY + 0.03, foot.z), KronosFx.UP, (half * 2.2).toFloat(), FxLib.alpha(KronosRayFx.SAND, STAIN_ALPHA * fade),
            FxMode.DUST, progress = 0.6f, seed = 21,
        )
    }

    companion object {
        const val MIN_LIFE = 24
        const val MOTES = 48
        const val DELAY = 14f
        const val RISE_RATE = 0.45f
        const val OUTSET = 0.35
        const val MOTE_ALPHA = 0.55f
        const val STAIN_ALPHA = 0.3f
        const val FADE = 10f
    }
}

/**
 * `kronos.rewind_hit` – der Strahl nimmt einen Spieler aus der Zeit (Briefing P20): Ein Sandband zieht vom Trefferort
 * zum Ort von vor 60 Ticks, dort schließt sich ein Ziffernring, am Trefferort verblasst ein Schein. In Echtzeit und in
 * der Über-Phase gezeichnet (läuft im Zeitstopp, nicht entsättigt).
 * Kodierung: pos = Trefferort (Füße), dir = Ziel − Treffer, a = Spielerhöhe, life.
 */
class KronosRewindHitFx(a: FxArgs) : FxEffect(a.life.coerceAtLeast(MIN_LIFE)) {
    private val from = a.pos
    private val to = a.pos.add(a.dir)
    private val chest = a.a.coerceIn(0.5f, 3f) * 0.55
    private val pal = a.palette

    private fun now(f: FxFrame): Float = age + f.tickDelta

    override fun expired(): Boolean = age >= life

    override fun over(f: FxFrame) {
        val t = now(f)
        val fade = KronosFx.clamp01((life - t) / FADE)
        val head = Ease.outCubic(t / TRAVEL)
        val tail = Ease.inOut((t - TAIL_DELAY) / TRAVEL)
        val a = from.add(0.0, chest, 0.0)
        val b = to.add(0.0, chest, 0.0)
        val lift = KronosFx.UP.multiply(a.distanceTo(b) * ARC_LIFT)
        fun at(u: Float): Vec3d = a.lerp(b, u.toDouble()).add(lift.multiply(4.0 * u * (1.0 - u)))
        if (head - tail > 0.02f) {
            val pts = List(POINTS) { i -> at(tail + (head - tail) * i / (POINTS - 1f)) }
            FxDraw.ribbon(
                f, pts, width = { p -> WIDTH * sin(PI.toFloat() * p) + 0.03f },
                color = { p -> FxLib.alpha(FxLib.mix(pal.main, KronosRayFx.SAND, p), BAND_ALPHA * fade) }, mode = FxMode.RIBBON, seed = 31,
            )
        }
        if (t >= RING_FROM) {
            // Der Ring schließt sich (gezogener Winkelanteil wächst) wie ein Zeiger, der eine Runde läuft.
            val ring = KronosFx.clamp01((t - RING_FROM) / RING_TICKS)
            FxDraw.flat(
                f, to.add(0.0, 0.05, 0.0), KronosFx.UP, RING_RADIUS, FxLib.alpha(FxLib.mix(pal.core, KronosRayFx.SAND, 0.4f), RING_ALPHA * fade),
                FxMode.RUNE, inner = RING_RADIUS * 0.78f, rotation = -t * 0.05f, progress = Ease.outCubic(ring).coerceAtLeast(0.02f), seed = 32,
            )
        }
        FxDraw.glow(f, a, GLOW * (1f - KronosFx.clamp01(t / GLOW_TICKS)), FxLib.alpha(pal.core, GLOW_ALPHA * fade))
    }

    companion object {
        const val MIN_LIFE = 20
        const val TRAVEL = 8f
        const val TAIL_DELAY = 5f
        const val POINTS = 20
        const val WIDTH = 0.35f
        const val ARC_LIFT = 0.12
        const val BAND_ALPHA = 0.7f
        const val RING_FROM = 6f
        const val RING_TICKS = 18f
        const val RING_RADIUS = 1.3f
        const val RING_ALPHA = 0.6f
        const val GLOW = 1.1f
        const val GLOW_TICKS = 12f
        const val GLOW_ALPHA = 0.5f
        const val FADE = 10f
    }
}
