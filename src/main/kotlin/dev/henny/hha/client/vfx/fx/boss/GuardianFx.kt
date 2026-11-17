package dev.henny.hha.client.vfx.fx.boss

import dev.henny.hha.HhaEntities
import dev.henny.hha.HhaSounds
import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.anim.Spring
import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxAttach
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMesh
import dev.henny.hha.client.vfx.fx.FxMeshes
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.client.vfx.fx.FxRegistry
import dev.henny.hha.client.vfx.fx.FxSolidMode
import dev.henny.hha.client.vfx.fx.lib.FxLib
import dev.henny.hha.entity.miniboss.IxionAction
import dev.henny.hha.entity.miniboss.MiniBoss
import dev.henny.hha.entity.miniboss.TantalusAction
import dev.henny.hha.entity.miniboss.WardenAction
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossDefense
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.entity.LivingEntity
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Client-Effekte der Tartaros-Wächter (Überarbeitung 3, P14, PLAN 2.11). Vier Wächter, vier eigene Bilder: Kette,
 * Fels, Feuerrad, Wasser. Alle Zeiten in Ticks, alle Längen in Blöcken, jede Größe als benannte Konstante im
 * `companion` ihrer Klasse; jedes Bild ist eine Klasse, ohne versteckte Zustände außer der eigenen Uhr.
 *
 * | ID | Klasse | Kodierung (Server: `MiniBosses.kt`) |
 * | --- | --- | --- |
 * | `warden.tension` | [WardenTension] | pos = Hakenende, entity = Kerkermeister, a = Haltefenster, life = Fenster + Nachfedern |
 * | `warden.link` | [WardenLinkBurst] | pos = Mitte des Glieds, a = Tick des Zerspringens (Szene `guardian.defeat`), life |
 * | `sisyphus.regrow` | [SisyphusRegrow] | pos = Boden unter ihm, entity = Sisyphos, a = Umkreis, b = Zahl der Brocken, life = REGROW |
 * | `ixion.spokes` | [IxionSpokes] | pos = Nabe, a = Startwinkel (Grad, atan2-Rahmen), b = Anteil der Runde, c = innen, d = außen, life, extra = `Zahl:Breite` |
 * | `tantalus.flood` | [TantalusFlood] | pos = Mitte, a = Radius, b = Höhe, c = Laufzeit (Ticks), life = Laufzeit + Ausklang |
 * | `tantalus.ebb` | [TantalusEbb] | pos = Boden unter ihm, entity = Tantalos, a = Radius, b = Art (0 zu ihm hin, 1 weicht, 2 steht c Ticks, dann weicht), life |
 *
 * Dauer-Effekte ([FxAttach], einer je Wesen): [WardenLantern] (Laterne, Leuchten steigt in der Raserei),
 * [IxionWheel] (Glut-Torus und Funken am Feuerrad, Stärke aus der Aktion).
 *
 * Gold kommt nirgends vor außer im Sieg-Glied ([WardenLinkBurst], „Ziel“). Kein Blinken: Helligkeiten laufen stetig, Pulse
 * höchstens 1 Hz.
 */
object GuardianFx {

    fun init() {
        FxRegistry.register("warden.tension") { WardenTension(it) }
        FxRegistry.register("warden.link") { WardenLinkBurst(it) }
        FxRegistry.register("sisyphus.regrow") { SisyphusRegrow(it) }
        FxRegistry.register("ixion.spokes") { IxionSpokes(it) }
        FxRegistry.register("tantalus.flood") { TantalusFlood(it) }
        FxRegistry.register("tantalus.ebb") { TantalusEbb(it) }
        FxAttach.register(HhaEntities.WARDEN_OF_CHAINS) { WardenLantern(it) }
        FxAttach.register(HhaEntities.IXION) { IxionWheel(it) }
    }

    /** Laufende Sonderbewegung eines Wächters am Client (0 = keine). */
    internal fun action(e: Entity?): Int = (e as? MiniBoss)?.kit?.action ?: 0

    internal fun actionAge(e: Entity?): Int = (e as? MiniBoss)?.kit?.actionAge ?: 0

    /** Waagerechte Blickrichtung des Körpers (zwischen zwei Ticks). */
    internal fun facing(e: Entity, tickDelta: Float): Vec3d {
        val yaw = if (e is LivingEntity) MathHelper.lerpAngleDegrees(tickDelta, e.lastBodyYaw, e.bodyYaw) else e.yaw
        val r = Math.toRadians(yaw.toDouble())
        return Vec3d(-sin(r), 0.0, cos(r))
    }

    internal fun smooth(x: Float): Float {
        val k = x.coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }
}

// =====================================================================================================================
// Kerkermeister
// =====================================================================================================================

/**
 * `warden.tension`: Der Haken hängt fest, die Kette spannt sich. Über der 3D-Kette des Bestands (`Vfx.chain`) liegt
 * eine helle Kante mit feinem Zittern; Lichtpulse laufen ruhig zum Haken. Sobald die Kette sich löst (die Kette des
 * Bestands holt ab 65 % des Fensters ein, oder er verlässt die Haltung „gehakt“), bekommt eine Feder einen Stoß: Die
 * Kante schwingt gedämpft quer aus und erlischt. Anfang der Kette wie im Bestand: 65 % seiner Höhe über der Mitte.
 */
class WardenTension(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val owner = args.entity
    private val end = args.pos
    private val window = args.a.coerceIn(4f, life.toFloat())
    private val palette = args.palette
    private val seed = args.seed.toInt()
    private val spring = Spring(SPRING_FREQ, SPRING_DAMP)
    private var releasedAt = -1f

    /** Einholen der Bestandskette: 0 bis 65 % des Fensters, dann bis 1. */
    private fun retract(t: Float): Float = ((t / window - RETRACT_FROM) / (1f - RETRACT_FROM)).coerceIn(0f, 1f)

    override fun tick(world: ClientWorld) {
        val t = age.toFloat()
        val hooked = GuardianFx.action(owner) == WardenAction.HOOKED || age < 3
        if (releasedAt < 0f && (retract(t) > 0f || !hooked)) {
            releasedAt = t
            spring.kick(RELEASE_KICK)
            FxLib.sound(world, end, HhaSounds.CHAIN, 0.5f, 1.3f)
        }
        spring.step(0f, 1f)
    }

    override fun energy(f: FxFrame) {
        val e = owner ?: return
        if (e.isRemoved) return
        val t = time(f)
        val start = e.getLerpedPos(f.tickDelta).add(0.0, e.height * START_UP, 0.0)
        val reach = Ease.outCubic(t / SHOOT_TICKS) * (1f - retract(t))
        if (reach <= 0.02f) return
        val tip = start.add(end.subtract(start).multiply(reach.toDouble()))
        val len = tip.distanceTo(start)
        if (len < 0.3) return
        val fadeIn = GuardianFx.smooth(t / FADE_IN)
        val fadeOut = if (releasedAt < 0f) 1f else 1f - GuardianFx.smooth((t - releasedAt) / FADE_OUT)
        val a = fadeIn * fadeOut
        if (a <= 0.01f) return

        val dir = tip.subtract(start).multiply(1.0 / len)
        val side = FxLib.flat(dir.crossProduct(FxLib.UP), Vec3d(1.0, 0.0, 0.0))
        val sag = SAG * len * (if (releasedAt < 0f) 1f else 1f + GuardianFx.smooth((t - releasedAt) / FADE_OUT))
        val tremor = TREMOR * sin(t * TREMOR_SPEED) * (if (releasedAt < 0f) 1f else 0f)
        val swing = spring.x + spring.v * f.tickDelta
        val pts = List(POINTS) { i ->
            val s = i / (POINTS - 1.0)
            val bell = sin(PI * s)
            start.add(tip.subtract(start).multiply(s))
                .add(0.0, -sag * 4.0 * s * (1.0 - s), 0.0)
                .add(side.multiply(bell * (swing + tremor)))
        }
        val hot = FxLib.mix(palette.main, palette.core, HOT_MIX)
        FxDraw.ribbon(f, pts, width = { HALO_WIDTH }, color = { u -> FxLib.alpha(palette.main, HALO_ALPHA * a * (0.6f + 0.4f * u)) }, seed = seed)
        // Ruhige Lichtpulse laufen zum Haken (0,6 Durchläufe je Sekunde, Helligkeit bleibt unter 1 Hz).
        FxDraw.ribbon(
            f, pts, width = { CORE_WIDTH },
            color = { u ->
                val run = 0.5f + 0.5f * sin((u * PULSE_WAVES - t * PULSE_SPEED) * 2f * PI.toFloat())
                FxLib.alpha(hot, CORE_ALPHA * a * (0.7f + 0.3f * run))
            },
            scroll = t * 0.05f, seed = seed + 1,
        )
        FxDraw.glow(f, tip, TIP_GLOW, FxLib.alpha(palette.main, TIP_ALPHA * a), seed + 2)
    }

    companion object {
        const val MIN_LIFE = 8
        const val START_UP = 0.65
        const val SHOOT_TICKS = 3f
        const val RETRACT_FROM = 0.65f
        const val FADE_IN = 6f
        const val FADE_OUT = 12f
        const val POINTS = 18
        /** Durchhang als Anteil der Länge (die Bestandskette hat 6 % Spiel). */
        const val SAG = 0.06
        const val TREMOR = 0.035
        const val TREMOR_SPEED = 0.9f
        const val SPRING_FREQ = 0.55f
        const val SPRING_DAMP = 0.16f
        /** Stoß der Feder beim Lösen (Blöcke je Tick quer). */
        const val RELEASE_KICK = 0.32f
        const val HALO_WIDTH = 0.16f
        const val HALO_ALPHA = 0.3f
        const val CORE_WIDTH = 0.05f
        const val CORE_ALPHA = 0.9f
        const val HOT_MIX = 0.6f
        const val PULSE_WAVES = 2.5f
        const val PULSE_SPEED = 0.03f
        const val TIP_GLOW = 0.45f
        const val TIP_ALPHA = 0.35f
    }
}

/**
 * Dauer-Effekt am Kerkermeister: die Laterne auf seinem Rücken (die Schwachstelle) als weicher Schein. Ruhig und
 * schwach; in der Raserei (Leben unter 40 %, wie der Server) steigt das Leuchten und atmet mit 0,5 Hz. Taumelt er
 * (Laterne zerschlagen, offen), erlischt sie, im Konter und beim Brüllen glüht sie kurz auf.
 */
class WardenLantern(private val entity: Entity) : FxEffect(LIFE) {
    private val palette = Palette.danger(Vfx.Style.SHADOW)
    private val fire = Palette.danger(Vfx.Style.FIRE)
    private var shown = 0f

    private fun target(): Float {
        val living = entity as? LivingEntity ?: return BASE
        val rage = living.health < living.maxHealth * RAGE_AT
        return when (GuardianFx.action(entity)) {
            WardenAction.STAGGER -> 0f
            WardenAction.RAGE, BossDefense.ANTI_AIR -> PEAK
            else -> if (rage) RAGE else BASE
        }
    }

    override fun tick(world: ClientWorld) {
        shown += (target() - shown) * FOLLOW
    }

    override fun energy(f: FxFrame) {
        if (shown <= 0.01f) return
        val t = time(f)
        val living = entity as? LivingEntity
        val rage = living != null && living.health < living.maxHealth * RAGE_AT
        val breath = if (rage) 1f + BREATH * sin(t * BREATH_SPEED) else 1f
        val front = GuardianFx.facing(entity, f.tickDelta)
        val at = entity.getLerpedPos(f.tickDelta).add(front.multiply(-BACK)).add(0.0, entity.height * UP, 0.0)
        val color = FxLib.mix(fire.main, palette.core, 0.35f)
        FxDraw.glow(f, at, SIZE * (0.8f + 0.2f * shown), FxLib.alpha(color, shown * breath), 7)
        FxDraw.glow(f, at, SIZE * 0.35f, FxLib.alpha(fire.core, shown * 0.8f * breath), 8)
    }

    companion object {
        const val LIFE = 72_000
        const val BASE = 0.22f
        const val RAGE = 0.5f
        const val PEAK = 0.7f
        const val RAGE_AT = 0.4f
        const val FOLLOW = 0.12f
        const val BREATH = 0.12f
        /** 0,5 Hz (rad/Tick). */
        const val BREATH_SPEED = 0.157f
        const val BACK = 0.95
        const val UP = 0.42
        const val SIZE = 0.9f
    }
}

/**
 * `warden.link` (Szene `guardian.defeat`, alle vier Wächter): ein schweres Kettenglied aus zwei verschränkten Ringen
 * schwebt über dem Ort des Sieges, glüht von innen auf und springt bei [burst] auseinander – die Hälften fliegen
 * weg und verglühen, ein goldener Schein (Ziel erreicht). Funken und Ton liefert das Skript im selben Tick.
 */
class WardenLinkBurst(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val center = args.pos
    private val burst = args.a.coerceIn(4f, life - 8f)
    private val gold = Palette.of(Vfx.Style.GOLD)
    private val seed = args.seed.toInt()

    private fun ring(f: FxFrame, at: Vec3d, axis: Vec3d, spin: Float, scale: Float, heat: Float) {
        val m = FxDraw.place(at, RING_SCALE * scale, axis, spin, Vector3f(RING_SCALE * scale, RING_SCALE * scale * STRETCH, RING_SCALE * scale))
        FxDraw.mesh(f, FxMeshes.TORUS, m, FxLib.alpha(IRON, heat), FxSolidMode.EMBER, solid = true, seed = seed)
    }

    override fun solid(f: FxFrame) {
        val t = time(f)
        val appear = Ease.outBack(t / APPEAR)
        if (appear <= 0.01f) return
        val bob = sin(t * BOB_SPEED) * BOB
        val heat = (HEAT_MIN + (1f - HEAT_MIN) * GuardianFx.smooth(t / burst)).coerceIn(0f, 1f)
        if (t < burst) {
            val at = center.add(0.0, bob, 0.0)
            val spin = t * SPIN
            ring(f, at.add(0.0, OFFSET, 0.0), Vec3d(1.0, 0.0, 0.0), spin, appear, heat)
            ring(f, at.add(0.0, -OFFSET, 0.0), Vec3d(0.0, 0.0, 1.0), -spin, appear, heat)
            return
        }
        // Gesprengt: zwei Hälften fliegen ballistisch auseinander, schrumpfen weich und kühlen ab.
        val k = t - burst
        val shrink = 1f - GuardianFx.smooth(k / FLY)
        if (shrink <= 0.01f) return
        for (side in intArrayOf(-1, 1)) {
            val out = Vec3d(side * FLY_SPEED * k, FLY_UP * k - 0.5 * GRAVITY * k * k, side * 0.3 * FLY_SPEED * k)
            val axis = if (side < 0) Vec3d(1.0, 0.4 * k, 0.0) else Vec3d(0.0, 0.4 * k, 1.0)
            ring(f, center.add(out).add(0.0, side * OFFSET, 0.0), axis, k * SPIN * 3f * side, shrink, heat * shrink)
        }
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val fade = ((life - t) / 10f).coerceIn(0f, 1f)
        if (t < burst) {
            val grow = GuardianFx.smooth(t / burst)
            FxDraw.glow(f, center, GLOW * (0.6f + 0.4f * grow), FxLib.alpha(gold.main, GLOW_ALPHA * grow * fade), seed)
            return
        }
        val k = (t - burst) / FLASH
        if (k < 1f) {
            val drop = (1f - k).let { it * it * it }
            FxDraw.glow(f, center, GLOW * (1f + 1.5f * k), FxLib.alpha(gold.core, FLASH_ALPHA * drop), seed + 1)
            FxDraw.flat(f, center, FxLib.UP, RING_OUT * Ease.outCubic(k), FxLib.alpha(gold.main, 0.5f * drop), FxMode.SHOCK, inner = 0.6f, seed = seed + 2)
        }
    }

    companion object {
        const val MIN_LIFE = 20
        const val APPEAR = 14f
        /** Dunkles Eisen; Alpha = Hitze der Glutadern. */
        const val IRON = 0xFF2A2420.toInt()
        const val HEAT_MIN = 0.25f
        const val RING_SCALE = 0.55f
        /** Glieder sind länglich. */
        const val STRETCH = 1.5f
        const val OFFSET = 0.42
        const val SPIN = 0.04f
        const val BOB = 0.12
        const val BOB_SPEED = 0.08f
        const val FLY = 18f
        const val FLY_SPEED = 0.18
        const val FLY_UP = 0.16
        const val GRAVITY = 0.02
        const val GLOW = 1.1f
        const val GLOW_ALPHA = 0.3f
        const val FLASH = 14f
        /** Höchstens 0,25 Bildschirmanteil wie ein Blitz: hier ein Schein in der Welt, kubisch abfallend. */
        const val FLASH_ALPHA = 0.55f
        const val RING_OUT = 3.5f
    }
}

// =====================================================================================================================
// Sisyphos
// =====================================================================================================================

/**
 * `sisyphus.regrow`: Der Fels wächst nach. Brocken lösen sich versetzt aus dem Boden im Umkreis (Staubwölkchen) und
 * fliegen auf der rückwärts gespielten Wurfbahn zu seinem Nacken: schnell vom Boden weg, langsam am Ziel, also genau
 * umgekehrt wie ein fallender Stein. Dort schrumpfen sie weich und werden zum Fels, den das Modell ab
 * `REGROW_START` (36) wachsen lässt. Ziel = Lage am Nacken im jeweiligen Bild (er darf sich dabei drehen).
 */
class SisyphusRegrow(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private class Chunk(val from: Vec3d, val launch: Float, val flight: Float, val size: Vector3f, val spinAxis: Vector3f, val spin: Float, val lift: Float)

    private val owner = args.entity
    private val palette = args.palette
    private val fallbackTarget = args.pos.add(0.0, NECK_UP, 0.0)
    private val chunks: List<Chunk> = Random(args.seed).let { rnd ->
        val n = args.b.toInt().coerceIn(1, MAX_CHUNKS)
        val r = args.a.coerceIn(1f, MAX_RADIUS)
        List(n) { i ->
            val ang = (i + rnd.nextFloat() * 0.6f) / n * 2.0 * PI
            val d = r * (0.45 + 0.55 * rnd.nextDouble())
            val x = args.pos.x + cos(ang) * d
            val z = args.pos.z + sin(ang) * d
            val y = FxLib.groundY(args.world, x, args.pos.y, z)
            val s = SIZE_MIN + rnd.nextFloat() * SIZE_RANGE
            Chunk(
                Vec3d(x, y + s * 0.4, z),
                LAUNCH_FROM + (LAUNCH_TO - LAUNCH_FROM) * i / max(1f, n - 1f) + rnd.nextFloat() * 2f,
                FLIGHT_MIN + rnd.nextFloat() * FLIGHT_RANGE,
                Vector3f(s * (0.8f + 0.4f * rnd.nextFloat()), s * (0.7f + 0.3f * rnd.nextFloat()), s * (0.8f + 0.4f * rnd.nextFloat())),
                Vector3f(rnd.nextFloat() - 0.5f, 1f, rnd.nextFloat() - 0.5f).normalize(),
                SPIN_MIN + rnd.nextFloat() * SPIN_RANGE,
                LIFT_MIN + rnd.nextFloat() * LIFT_RANGE,
            )
        }
    }

    private fun target(tickDelta: Float): Vec3d {
        val e = owner ?: return fallbackTarget
        if (e.isRemoved) return fallbackTarget
        val back = GuardianFx.facing(e, tickDelta).multiply(-NECK_BACK)
        return e.getLerpedPos(tickDelta).add(back).add(0.0, NECK_UP, 0.0)
    }

    /** Lage eines Brockens bei Flug-Anteil [u]: rückwärts gespielter Wurf (schnell am Boden, sanft am Ziel). */
    private fun at(c: Chunk, goal: Vec3d, u: Float): Vec3d {
        val k = Ease.outCubic(u).toDouble()
        val arc = 4.0 * u * (1.0 - u) * c.lift
        return c.from.lerp(goal, k).add(0.0, arc, 0.0)
    }

    override fun tick(world: ClientWorld) {
        if (age == LAUNCH_FROM.toInt() + 1) FxLib.sound(world, fallbackTarget, HhaSounds.DEBRIS, 0.45f, 0.8f)
    }

    override fun solid(f: FxFrame) {
        val t = time(f)
        val goal = target(f.tickDelta)
        for ((i, c) in chunks.withIndex()) {
            val u = (t - c.launch) / c.flight
            if (u < 0f || u >= 1f) continue
            val merge = 1f - GuardianFx.smooth((u - MERGE_FROM) / (1f - MERGE_FROM)) * (1f - MERGE_SIZE)
            val p = at(c, goal, u)
            val m = Matrix4f().translation(p.x.toFloat(), p.y.toFloat(), p.z.toFloat())
                .rotate(Quaternionf().rotationAxis(t * c.spin, c.spinAxis))
                .scale(c.size.x * merge, c.size.y * merge, c.size.z * merge)
            FxDraw.mesh(f, FxMeshes.SPHERE, m, ROCK, FxSolidMode.STONE, solid = true, seed = i)
        }
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val goal = target(f.tickDelta)
        for ((i, c) in chunks.withIndex()) {
            // Staubwölkchen beim Lösen.
            val puff = (t - c.launch) / PUFF
            if (puff in 0f..1f) {
                FxDraw.flat(
                    f, c.from.add(0.0, FxLib.GROUND_LIFT - c.size.y * 0.4, 0.0), FxLib.UP, PUFF_RADIUS * (0.5f + Ease.outCubic(puff)),
                    FxLib.alpha(DUST, PUFF_ALPHA * (1f - puff)), FxMode.DUST, progress = 0.6f, seed = 30 + i,
                )
            }
            // Feine Staubspur hinter dem Brocken.
            val u = (t - c.launch) / c.flight
            if (u in 0f..1f) {
                val trail = List(4) { k -> at(c, goal, (u - k * TRAIL_STEP).coerceAtLeast(0f)) }.reversed()
                FxDraw.ribbon(f, trail, width = { w -> c.size.x * 0.35f * w }, color = { w -> FxLib.alpha(DUST, TRAIL_ALPHA * w) }, seed = 60 + i)
            }
            // Ankunft: kurzer warmer Schein am Nacken.
            val arrive = (t - c.launch - c.flight) / ARRIVE
            if (arrive in 0f..1f) {
                FxDraw.glow(f, goal, ARRIVE_GLOW, FxLib.alpha(palette.main, ARRIVE_ALPHA * (1f - arrive).let { it * it }), 90 + i)
            }
        }
    }

    companion object {
        const val MIN_LIFE = 30
        const val MAX_CHUNKS = 10
        const val MAX_RADIUS = 8f
        /** Nacken über den Füßen und nach hinten (Fels im Modell, Höhe 3,8). */
        const val NECK_UP = 3.3
        const val NECK_BACK = 0.35
        /** Starts verteilt über diese Ticks; Flugdauer; zusammen landen sie zwischen ~30 und 54 (Modell: 36–60). */
        const val LAUNCH_FROM = 10f
        const val LAUNCH_TO = 30f
        const val FLIGHT_MIN = 18f
        const val FLIGHT_RANGE = 6f
        const val SIZE_MIN = 0.26f
        const val SIZE_RANGE = 0.16f
        const val SPIN_MIN = 0.15f
        const val SPIN_RANGE = 0.2f
        const val LIFT_MIN = 1.2f
        const val LIFT_RANGE = 1.2f
        const val MERGE_FROM = 0.75f
        const val MERGE_SIZE = 0.35f
        const val ROCK = 0xFF4B4038.toInt()
        const val DUST = 0xFF8A7A68.toInt()
        const val PUFF = 14f
        const val PUFF_RADIUS = 0.8f
        const val PUFF_ALPHA = 0.35f
        const val TRAIL_STEP = 0.06f
        const val TRAIL_ALPHA = 0.22f
        const val ARRIVE = 8f
        const val ARRIVE_GLOW = 0.6f
        const val ARRIVE_ALPHA = 0.25f
    }
}

// =====================================================================================================================
// Ixion
// =====================================================================================================================

/**
 * Dauer-Effekt am Feuerrad: ein Glut-Torus (Modus FLAME auf [FxMeshes.TORUS]) um den Radkranz, ein schwacher Schein an
 * der Nabe und wenige ballistische Funken, die vom Kranz abspringen. Stärke und Lage folgen weich der Aktion
 * (Rollen hell und schnell, Speichenfeuer waagerecht, Kippen schwach und schräg, gestürzt nur noch Glut am Boden), mit
 * denselben Winkeln wie `IxionModel` (Rad quer beim Rollen, waagerecht beim Speichenfeuer).
 */
class IxionWheel(private val entity: Entity) : FxEffect(LIFE) {
    private class Spark(var p: Vec3d, var v: Vec3d, var age: Float, val end: Float)

    private val palette = Palette.danger(Vfx.Style.FIRE)
    private val rnd = Random(entity.id.toLong() * 31L + 7L)
    private val sparks = ArrayList<Spark>()
    private var heat = 0f
    private var tilt = 0f
    private var turn = 0f
    private var drop = 0f
    private var spin = 0f
    private var spawn = 0f

    /** Zielwerte je Aktion: Hitze, Kippung (0 aufrecht … π/2 waagerecht), Querstellung (0 … π/2), Absenkung 0..1. */
    private fun targets(): FloatArray {
        val a = GuardianFx.action(entity)
        val age = GuardianFx.actionAge(entity).toFloat()
        return when (a) {
            IxionAction.ROLL_WINDUP -> floatArrayOf(0.85f, 0f, HALF_PI, 0f)
            IxionAction.ROLL -> floatArrayOf(1f, 0f, HALF_PI, 0f)
            IxionAction.WOBBLE -> floatArrayOf(0.45f, WOBBLE_TILT * GuardianFx.smooth(age / 6f), HALF_PI, 0f)
            IxionAction.SPOKES, BossDefense.ANTI_AIR -> floatArrayOf(0.9f, HALF_PI, 0f, 0f)
            IxionAction.SPARKS -> floatArrayOf(0.6f, 0f, 0f, 0f)
            IxionAction.FALLEN -> floatArrayOf(0.15f, HALF_PI, 0f, 1f)
            IxionAction.RISE -> floatArrayOf(0.35f, 0f, 0f, 0f)
            else -> floatArrayOf(IDLE_HEAT, 0f, 0f, 0f)
        }
    }

    private fun center(tickDelta: Float): Vec3d = entity.getLerpedPos(tickDelta).add(0.0, CENTER_UP - (CENTER_UP - FALLEN_UP) * drop, 0.0)

    /** Achse des Rads (Normale der Radebene) aus Blick, Querstellung und Kippung. */
    private fun axis(tickDelta: Float): Vec3d {
        val front = GuardianFx.facing(entity, tickDelta)
        val right = Vec3d(-front.z, 0.0, front.x)
        val upright = front.multiply(cos(turn.toDouble())).add(right.multiply(sin(turn.toDouble())))
        return upright.multiply(cos(tilt.toDouble())).add(FxLib.UP.multiply(sin(tilt.toDouble()))).normalize()
    }

    override fun tick(world: ClientWorld) {
        val t = targets()
        heat += (t[0] - heat) * HEAT_FOLLOW
        tilt += (t[1] - tilt) * POSE_FOLLOW
        turn += (t[2] - turn) * POSE_FOLLOW
        drop += (t[3] - drop) * POSE_FOLLOW
        spin += SPIN_IDLE + SPIN_HOT * heat
        // Funken: höchstens [MAX_SPARKS], Rate wächst mit der Hitze (gestürzt keine neuen).
        spawn += SPARK_RATE * heat * heat * (1f - drop)
        while (spawn >= 1f && sparks.size < MAX_SPARKS) {
            spawn -= 1f
            sparks.add(newSpark())
        }
        if (spawn > 1f) spawn = 1f
        val it = sparks.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.age += 1f
            s.v = s.v.multiply(DRAG).add(0.0, -GRAVITY, 0.0)
            s.p = s.p.add(s.v)
            if (s.age >= s.end) it.remove()
        }
    }

    private fun newSpark(): Spark {
        val c = center(1f)
        val n = axis(1f)
        val (u, w) = FxLib.basis(n)
        val ang = rnd.nextDouble() * 2.0 * PI
        val radial = u.multiply(cos(ang)).add(w.multiply(sin(ang)))
        val tangent = n.crossProduct(radial).normalize()
        val p = c.add(radial.multiply(RADIUS.toDouble()))
        val v = tangent.multiply(SPARK_SPEED * (0.6 + 0.6 * rnd.nextDouble())).add(radial.multiply(0.08)).add(0.0, 0.06 + 0.06 * rnd.nextDouble(), 0.0)
        return Spark(p, v, 0f, SPARK_LIFE_MIN + rnd.nextFloat() * SPARK_LIFE_RANGE)
    }

    override fun energy(f: FxFrame) {
        if (heat <= 0.02f) return
        val t = time(f)
        val c = center(f.tickDelta)
        val n = axis(f.tickDelta)
        val m = FxDraw.place(c, RADIUS, n, spin + f.tickDelta * (SPIN_IDLE + SPIN_HOT * heat))
        // Glut-Torus: atmet mit 0,4 Hz, nie flackernd.
        val breath = 1f + 0.06f * sin(t * 0.126f)
        FxDraw.mesh(f, FxMeshes.TORUS, m, FxLib.alpha(FxLib.mix(palette.main, palette.core, 0.3f * heat), (TORUS_ALPHA_MIN + TORUS_ALPHA * heat) * breath), FxMode.FLAME, seed = entity.id)
        FxDraw.glow(f, c, RADIUS * HUB_GLOW, FxLib.alpha(palette.main, HUB_ALPHA * heat), entity.id + 1)
        for ((i, s) in sparks.withIndex()) {
            val k = s.age / s.end
            val head = s.p.add(s.v.multiply(f.tickDelta.toDouble()))
            val tail = head.subtract(s.v.multiply(SPARK_TRAIL.toDouble()))
            val life = (1f - k).let { it * sqrt(it) }
            FxDraw.ribbon(
                f, listOf(tail, tail.lerp(head, 0.5), head), width = { u -> SPARK_WIDTH * (0.3f + 0.7f * u) },
                color = { u -> FxLib.alpha(FxLib.mix(palette.main, palette.core, u * (1f - k)), life * (0.3f + 0.7f * u)) }, seed = i,
            )
        }
    }

    companion object {
        const val LIFE = 72_000
        const val HALF_PI = (PI / 2).toFloat()
        /** Mitte des Rads über seinen Füßen, aufrecht bzw. gestürzt (Modell: Durchmesser 3,2, FALL_DY 33,5). */
        const val CENTER_UP = 1.8
        const val FALLEN_UP = 0.3
        /** Mittelkreis des Torus (Rohr = 0,22 × das). */
        const val RADIUS = 1.5f
        const val WOBBLE_TILT = 1.09f
        const val IDLE_HEAT = 0.3f
        const val HEAT_FOLLOW = 0.12f
        const val POSE_FOLLOW = 0.3f
        const val SPIN_IDLE = 0.02f
        const val SPIN_HOT = 0.18f
        const val TORUS_ALPHA_MIN = 0.15f
        const val TORUS_ALPHA = 0.55f
        const val HUB_GLOW = 0.8f
        const val HUB_ALPHA = 0.14f
        const val MAX_SPARKS = 10
        /** Neue Funken je Tick bei voller Hitze. */
        const val SPARK_RATE = 0.5f
        const val SPARK_SPEED = 0.22
        const val SPARK_LIFE_MIN = 10f
        const val SPARK_LIFE_RANGE = 8f
        const val DRAG = 0.93
        const val GRAVITY = 0.03
        const val SPARK_TRAIL = 2.2f
        const val SPARK_WIDTH = 0.05f
    }
}

/**
 * `ixion.spokes`: Speichenfeuer. Sechs Feuerbalken liegen am Boden und drehen stetig um die Nabe, genau mit dem
 * Winkel, mit dem der Server trifft (`start + Anteil · 360 · t / Dauer`, Richtung `(cos, 0, sin)`). Jeder Balken ist
 * ein liegendes Glutband mit niedriger Flammenwand darüber und einem schwachen Nachleuchten dahinter. Kein Blinken:
 * Ein- und Ausblenden über [RAMP] bzw. [FADE] Ticks.
 */
class IxionSpokes(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val hub = args.pos
    private val start = args.a
    private val turn = args.b
    private val inner = args.c.toDouble().coerceAtLeast(0.0)
    private val outer = args.d.toDouble().coerceAtLeast(inner + 0.5)
    private val lines = args.extra.substringBefore(':').toIntOrNull()?.coerceIn(1, 12) ?: 6
    private val width = args.extra.substringAfter(':', "").toFloatOrNull()?.coerceIn(0.2f, 4f) ?: 1.6f
    private val palette = args.palette
    private val seed = args.seed.toInt()

    private fun angle(t: Float): Float = start + turn * 360f * t / life

    private fun beam(f: FxFrame, deg: Float, a: Float, flame: Boolean, i: Int) {
        val r = Math.toRadians(deg.toDouble())
        val d = Vec3d(cos(r), 0.0, sin(r))
        val from = hub.add(d.multiply(inner)).add(0.0, FxLib.GROUND_LIFT * 3, 0.0)
        val to = hub.add(d.multiply(outer)).add(0.0, FxLib.GROUND_LIFT * 3, 0.0)
        FxDraw.strip(
            f, listOf(from, from.lerp(to, 0.5), to), FxLib.UP, width = { u -> width * 0.5f * (0.75f + 0.25f * sin(u * PI.toFloat())) },
            color = { u -> FxLib.alpha(FxLib.mix(palette.main, palette.core, 0.35f), a * BAND_ALPHA * (0.55f + 0.45f * sin(u * PI.toFloat()))) },
            mode = FxMode.RIBBON, seed = seed + i,
        )
        if (flame) FxDraw.wall(f, from, to, FLAME_HEIGHT * a, FxLib.alpha(palette.main, a * FLAME_ALPHA), FxMode.FLAME, seed = seed + 20 + i)
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val a = GuardianFx.smooth(t / RAMP) * (1f - GuardianFx.smooth((t - (life - FADE)) / FADE))
        if (a <= 0.01f) return
        val now = angle(t)
        val step = 360f / lines
        for (i in 0 until lines) {
            val deg = now + i * step
            beam(f, deg, a, true, i)
            // Nachleuchten: zwei schwache Kopien hinter der Drehrichtung.
            for (k in 1..TRAILS) beam(f, deg - k * TRAIL_DEG * (if (turn >= 0f) 1f else -1f), a * TRAIL_ALPHA / k, false, 40 + i * 3 + k)
        }
        FxDraw.glow(f, hub.add(0.0, 0.4, 0.0), inner.toFloat() * 1.4f, FxLib.alpha(palette.main, HUB_ALPHA * a), seed + 90)
    }

    companion object {
        const val MIN_LIFE = 10
        const val RAMP = 4f
        const val FADE = 6f
        const val BAND_ALPHA = 0.75f
        const val FLAME_HEIGHT = 0.9f
        const val FLAME_ALPHA = 0.6f
        const val TRAILS = 2
        const val TRAIL_DEG = 5f
        const val TRAIL_ALPHA = 0.35f
        const val HUB_ALPHA = 0.25f
    }
}

// =====================================================================================================================
// Tantalos
// =====================================================================================================================

/**
 * `tantalus.flood`: Flutwelle als niedrige Wasserwand, die ringförmig nach außen läuft – Radius genau wie die
 * Trefferfront des Servers (`Radius · t / Laufzeit`). Die Wand ist ein geschlossener Ring aus einem Stück (eigenes
 * Mesh je Bild, Modus WATER: Fuß dunkel, Krone mit Schaum), darunter ein nasser Saum am Boden. Danach fällt sie in
 * [life] − Laufzeit Ticks in sich zusammen. Lücken gibt es nicht: Über diese Welle springt man.
 */
class TantalusFlood(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val center = args.pos
    private val radius = args.a.coerceIn(1f, MAX_RADIUS)
    private val height = args.b.coerceIn(0.3f, MAX_HEIGHT)
    private val travel = args.c.coerceIn(1f, life.toFloat())
    private val palette = args.palette
    private val seed = args.seed.toInt()
    private val pos = FloatArray(SEGMENTS * 4 * 3)
    private val nrm = FloatArray(SEGMENTS * 4 * 3)
    private val uv = FloatArray(SEGMENTS * 4 * 2)
    private val mesh = FxMesh(pos, nrm, uv)

    override fun tick(world: ClientWorld) {
        if (age == 1) FxLib.sound(world, center, HhaSounds.WAVE, 0.6f, 0.9f)
    }

    /** Baut den Wandring mit Radius [r] und Höhe [h] (Weltlage, Normalen nach außen, u = Weg in Blöcken). */
    private fun buildRing(r: Float, h: Float, t: Float) {
        for (i in 0 until SEGMENTS) {
            val o = i * 12
            val ou = i * 8
            for ((k, corner) in intArrayOf(0, 1, 1, 0).withIndex()) {
                val seg = i + corner
                val ang = seg.toDouble() / SEGMENTS * 2.0 * PI
                val cx = cos(ang).toFloat()
                val cz = sin(ang).toFloat()
                val top = k >= 2
                // Krone wogt leicht (stetig), der Fuß liegt fest.
                val swell = if (top) 1f + CREST_ROLL * sin(seg * CREST_WAVES + t * CREST_SPEED) else 0f
                pos[o + k * 3] = (center.x + cx * r).toFloat()
                pos[o + k * 3 + 1] = (center.y + (if (top) h * swell else 0f)).toFloat()
                pos[o + k * 3 + 2] = (center.z + cz * r).toFloat()
                nrm[o + k * 3] = cx
                nrm[o + k * 3 + 1] = 0f
                nrm[o + k * 3 + 2] = cz
                uv[ou + k * 2] = (seg.toDouble() / SEGMENTS * 2.0 * PI * r).toFloat()
                uv[ou + k * 2 + 1] = if (top) 1f else 0f
            }
        }
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val run = (t / travel).coerceIn(0f, 1f)
        val r = max(MIN_FRONT, radius * run)
        val build = Ease.outCubic(t / BUILD)
        val fall = 1f - GuardianFx.smooth((t - travel) / (life - travel).coerceAtLeast(1f))
        val h = height * build * fall
        val a = min(1f, fall * 1.4f)
        if (a <= 0.01f) return
        if (h > 0.05f) {
            buildRing(r, h, t)
            FxDraw.mesh(f, mesh, Matrix4f(), FxLib.alpha(palette.main, WALL_ALPHA * a), FxMode.WATER, seed = seed, progress = FOAM)
        }
        // Nasser Saum hinter der Front (weich nach innen), schwächer werdend.
        FxDraw.flat(
            f, center.add(0.0, FxLib.GROUND_LIFT, 0.0), FxLib.UP, r + 0.2f, FxLib.alpha(palette.edge, WET_ALPHA * a),
            FxMode.SHOCK, inner = min(r, WET_BAND), seed = seed + 1,
        )
    }

    companion object {
        const val MIN_LIFE = 8
        const val MAX_RADIUS = 32f
        const val MAX_HEIGHT = 4f
        const val SEGMENTS = 64
        const val MIN_FRONT = 0.6f
        const val BUILD = 4f
        const val CREST_ROLL = 0.08f
        const val CREST_WAVES = 0.9f
        const val CREST_SPEED = 0.3f
        const val WALL_ALPHA = 0.6f
        const val FOAM = 0.7f
        const val WET_ALPHA = 0.35f
        const val WET_BAND = 1.6f
    }
}

/**
 * `tantalus.ebb`: Das Wasser des Beckens um Tantalos, als flache, leicht bewegte Lache mit hellem Rand.
 * - Art 0 (Trinken): Die Lache zieht sich über die Dauer zu ihm zusammen (er trinkt). Verlässt er die Haltung, ohne
 *   fertig zu sein (Kelch aus der Hand: PARCHED), blendet sie in [CUT] Ticks aus.
 * - Art 1 (ausgedörrt): Das Wasser weicht von ihm zurück nach außen und versickert.
 * - Art 2 (Auftritt): Die Lache liegt still um ihn, ab Tick c weicht sie zurück wie Art 1.
 */
class TantalusEbb(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val owner = args.entity
    private val center = args.pos
    private val radius = args.a.coerceIn(1f, MAX_RADIUS)
    private val mode = args.b.toInt()
    private val still = if (mode == 2) args.c.coerceIn(0f, life.toFloat()) else 0f
    private val palette = args.palette
    private val seed = args.seed.toInt()
    private var cutAt = -1f

    override fun tick(world: ClientWorld) {
        if (mode == 0 && cutAt < 0f && age > 4 && GuardianFx.action(owner) != TantalusAction.DRINK) cutAt = age.toFloat()
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val appear = GuardianFx.smooth(t / APPEAR)
        val cut = if (cutAt < 0f) 1f else 1f - GuardianFx.smooth((t - cutAt) / CUT)
        // Außen- und Innenradius der Lache je Art.
        val outer: Float
        val inner: Float
        val a: Float
        when (mode) {
            0 -> {
                val k = Ease.inOut(t / life)
                outer = radius * (1f - 0.85f * k)
                inner = 0f
                a = appear * cut * (1f - GuardianFx.smooth((t - life + 6f) / 6f))
            }
            else -> {
                val k = Ease.outCubic(((t - still) / (life - still).coerceAtLeast(1f)).coerceIn(0f, 1f))
                outer = radius * (0.85f + 0.45f * k)
                inner = radius * 1.1f * k
                a = (if (mode == 1) 1f else appear) * (1f - k * k)
            }
        }
        if (a <= 0.01f || outer <= inner + 0.05f) return
        val at = center.add(0.0, FxLib.GROUND_LIFT * 2, 0.0)
        FxDraw.flat(f, at, FxLib.UP, outer, FxLib.alpha(palette.main, POOL_ALPHA * a), FxMode.WATER, inner = inner, rotation = t * 0.004f, seed = seed)
        // Heller Saum an der Wasserkante (weich, zur Mitte hin auslaufend).
        FxDraw.flat(f, at.add(0.0, 0.005, 0.0), FxLib.UP, outer, FxLib.alpha(palette.core, RIM_ALPHA * a), FxMode.SHOCK, inner = min(outer - inner, RIM_BAND), seed = seed + 1)
    }

    companion object {
        const val MIN_LIFE = 10
        const val MAX_RADIUS = 16f
        const val APPEAR = 8f
        const val CUT = 6f
        const val POOL_ALPHA = 0.5f
        const val RIM_ALPHA = 0.3f
        const val RIM_BAND = 0.9f
    }
}
