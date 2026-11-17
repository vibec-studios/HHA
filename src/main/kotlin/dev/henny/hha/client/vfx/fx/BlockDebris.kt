package dev.henny.hha.client.vfx.fx

import dev.henny.hha.HhaSounds
import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.vfx.VfxManager
import dev.henny.hha.client.vfx.fx.lib.FxLib
import net.minecraft.block.BlockRenderType
import net.minecraft.block.BlockState
import net.minecraft.client.render.WorldRenderer
import net.minecraft.client.world.ClientWorld
import net.minecraft.particle.BlockStateParticleEffect
import net.minecraft.particle.ParticleTypes
import net.minecraft.registry.Registries
import net.minecraft.sound.SoundCategory
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.Random
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Echte Blöcke als fliegende Trümmer (REF T5, Vorbild Void `BlackHoleEffect.kt:164-378`): sichtbare Oberflächenblöcke
 * werden **nur optisch** herausgerissen und geworfen, die Welt bleibt unverändert (hier wird nie ein Block gesetzt).
 * Nur Blöcke mit `renderType == MODEL`, ohne Block-Entity, nicht aus der Mod (`hha:`), ohne Flüssigkeit.
 * Höchstens [MAX_LIVE] gleichzeitig; überzählige entfallen. Die Bewegung rechnet [tick] (mit `FxClock`), gezeichnet
 * wird je Aufruf über einen [FxEffect] im `VfxManager` (Phase `solid`).
 */
object BlockDebris {

    /** Höchstens so viele Trümmerblöcke gleichzeitig am Client (PLAN 2.2). */
    const val MAX_LIVE = 24

    const val GRAVITY = 0.045
    const val DRAG = 0.985
    const val MAX_AGE = 120
    const val SHRINK_TICKS = 10f
    const val SIZE_MIN = 0.45f
    const val SIZE_RANGE = 0.25f
    const val SPIN_MIN = 0.18f
    const val SPIN_RANGE = 0.22f
    const val LAND_PARTICLES = 10
    const val RIP_PARTICLES = 3
    /** Bruchklänge beim Landen je Client-Tick höchstens (Töne-Budget). */
    const val LAND_SOUNDS_PER_TICK = 2
    const val LAND_VOLUME = 0.55f
    const val DEBRIS_VOLUME = 0.5f
    /** Sog: Anteil der Dauer, über den die Starts verteilt sind, und Anhebung des Bogens (Blöcke). */
    const val PULL_STAGGER = 0.35f
    const val PULL_LIFT = 2.0
    const val PULL_SWIRL = 1.2

    /** Ein Stück: echter Blockzustand, Lage, Drehung. Sog-Stücke folgen einer festen Kurve statt der Schwerkraft. */
    class Piece(
        val state: BlockState, var pos: Vec3d, var velocity: Vec3d, val axis: Vector3f, val spin: Float, val size: Float,
        val pull: Pull? = null,
    ) {
        var previous: Vec3d = pos
        var age = 0f
        var angle = 0f
        var done = false
    }

    /** Sog: von [from] über einen gehobenen Bogen zu [target], ab [delay] in [ticks] Ticks. */
    class Pull(val from: Vec3d, val target: Vec3d, val delay: Float, val ticks: Float, val swirl: Float)

    private val live = ArrayList<Piece>()
    private var soundsThisTick = 0

    /** Bis zu [count] Blöcke im Ring [r0]..[r1] um [ground] hochwerfen ([up] senkrecht, [out] nach außen, Blöcke/Tick). */
    fun burst(world: ClientWorld, ground: Vec3d, r0: Float, r1: Float, count: Int, up: Float, out: Float) {
        if (!FxLib.enabled()) return
        val rnd = Random(ground.hashCode().toLong() xor world.time)
        val pieces = ArrayList<Piece>()
        val n = count.coerceAtMost(MAX_LIVE - live.size)
        val used = HashSet<BlockPos>()
        val turn = rnd.nextFloat() * Math.PI.toFloat() * 2f
        for (i in 0 until n) {
            val angle = turn + i * (Math.PI.toFloat() * 2f / n.coerceAtLeast(1)) + (rnd.nextFloat() - 0.5f) * 0.6f
            val dist = r0 + (r1 - r0).coerceAtLeast(0f) * sqrt(rnd.nextFloat())
            val (pos, state) = surface(world, ground.x + cos(angle) * dist, ground.y, ground.z + sin(angle) * dist, used) ?: continue
            val outward = FxLib.flat(Vec3d(cos(angle).toDouble(), 0.0, sin(angle).toDouble()))
            val v = outward.multiply(out * (0.6 + 0.6 * rnd.nextDouble())).add(0.0, up * (0.75 + 0.4 * rnd.nextDouble()), 0.0)
            pieces += piece(world, state, pos, v, rnd, null)
        }
        start(world, ground, pieces)
    }

    /** Bis zu [count] Blöcke im Umkreis [radius] um [ground] über [ticks] zu [target] ziehen (Sog). */
    fun pull(world: ClientWorld, ground: Vec3d, radius: Float, target: Vec3d, ticks: Int, count: Int) {
        if (!FxLib.enabled()) return
        val rnd = Random(ground.hashCode().toLong() xor world.time)
        val pieces = ArrayList<Piece>()
        val n = count.coerceAtMost(MAX_LIVE - live.size)
        val used = HashSet<BlockPos>()
        val span = ticks.coerceAtLeast(4).toFloat()
        for (i in 0 until n) {
            val angle = rnd.nextFloat() * Math.PI.toFloat() * 2f
            val dist = radius * (0.35f + 0.65f * sqrt(rnd.nextFloat()))
            val (pos, state) = surface(world, ground.x + cos(angle) * dist, ground.y, ground.z + sin(angle) * dist, used) ?: continue
            val start = Vec3d(pos.x + 0.5, pos.y + 0.9, pos.z + 0.5)
            val delay = rnd.nextFloat() * span * PULL_STAGGER
            val swirl = (if (rnd.nextBoolean()) 1f else -1f) * (0.6f + 0.4f * rnd.nextFloat())
            pieces += piece(world, state, pos, Vec3d.ZERO, rnd, Pull(start, target, delay, span - delay, swirl))
        }
        start(world, ground, pieces)
    }

    private fun piece(world: ClientWorld, state: BlockState, pos: BlockPos, v: Vec3d, rnd: Random, pull: Pull?): Piece {
        val start = Vec3d(pos.x + 0.5, pos.y + 0.9, pos.z + 0.5)
        val dust = BlockStateParticleEffect(ParticleTypes.BLOCK, state)
        repeat(RIP_PARTICLES) { world.addParticleClient(dust, start.x, start.y + 0.1, start.z, 0.0, 0.08, 0.0) }
        val axis = Vector3f(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f).let {
            if (it.lengthSquared() < 1e-4f) Vector3f(0f, 1f, 0f) else it.normalize()
        }
        return Piece(state, start, v, axis, SPIN_MIN + rnd.nextFloat() * SPIN_RANGE, SIZE_MIN + rnd.nextFloat() * SIZE_RANGE, pull)
    }

    private fun start(world: ClientWorld, ground: Vec3d, pieces: List<Piece>) {
        if (pieces.isEmpty()) return
        live += pieces
        FxLib.sound(world, ground, HhaSounds.DEBRIS, DEBRIS_VOLUME, 0.9f + world.random.nextFloat() * 0.2f)
        VfxManager.addEffect(Draw(pieces))
    }

    /**
     * Oberflächenblock bei ([x], [z]) nahe der Höhe [y]: fester Block mit freiem Raum darüber, der als Modell gezeichnet
     * wird, keine Block-Entity hat und nicht aus der Mod stammt. Schon benutzte Lagen in [used] entfallen.
     */
    private fun surface(world: ClientWorld, x: Double, y: Double, z: Double, used: MutableSet<BlockPos>): Pair<BlockPos, BlockState>? {
        val top = FxLib.groundY(world, x, y, z)
        val pos = BlockPos.ofFloored(x, top - 0.5, z)
        if (!used.add(pos)) return null
        val state = world.getBlockState(pos)
        if (state.isAir || !state.fluidState.isEmpty || state.renderType != BlockRenderType.MODEL || state.hasBlockEntity()) return null
        if (Registries.BLOCK.getId(state.block).namespace == "hha") return null
        if (state.getCollisionShape(world, pos).isEmpty) return null
        return pos to state
    }

    /** Je Client-Tick (aus `FxClient.tick`): Flug, Kollision, Landung, Sog. Zeitfaktor aus [FxClock]. */
    fun tick(world: ClientWorld) {
        soundsThisTick = 0
        val dt = FxClock.scale
        val it = live.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.done) {
                it.remove()
                continue
            }
            p.previous = p.pos
            p.age += dt
            p.angle += p.spin * dt
            val pull = p.pull
            if (pull != null) stepPull(p, pull) else stepFlight(world, p, dt)
            if (p.age > MAX_AGE) p.done = true
            if (p.done) it.remove()
        }
    }

    private fun stepFlight(world: ClientWorld, p: Piece, dt: Float) {
        p.velocity = p.velocity.multiply(DRAG.pow(dt.toDouble())).add(0.0, -GRAVITY * dt, 0.0)
        val next = p.pos.add(p.velocity.multiply(dt.toDouble()))
        if (p.velocity.y < 0.0) {
            val bp = BlockPos.ofFloored(next)
            if (!world.getBlockState(bp).getCollisionShape(world, bp).isEmpty) {
                land(world, p)
                return
            }
        }
        p.pos = next
    }

    private fun stepPull(p: Piece, pull: Pull) {
        val k = Ease.inCubic((p.age - pull.delay) / pull.ticks)
        if (p.age >= pull.delay + pull.ticks) {
            p.done = true
            return
        }
        val lift = pull.from.add(0.0, PULL_LIFT, 0.0)
        val ctl = FxLib.lerp(lift, pull.target, 0.3f)
        val base = FxLib.bezier(pull.from, ctl, pull.target, k)
        // Wirbel um die Achse zum Ziel, der zum Ende hin enger wird.
        val (u, v) = FxLib.basis(pull.target.subtract(pull.from))
        val a = k * 6f * pull.swirl
        val r = PULL_SWIRL * (1f - k) * k * 4f
        p.pos = base.add(u.multiply(cos(a) * r)).add(v.multiply(sin(a) * r))
    }

    private fun land(world: ClientWorld, p: Piece) {
        p.done = true
        val dust = BlockStateParticleEffect(ParticleTypes.BLOCK, p.state)
        repeat(LAND_PARTICLES) {
            world.addParticleClient(
                dust, p.pos.x, p.pos.y + 0.2, p.pos.z,
                (world.random.nextDouble() - 0.5) * 0.5, world.random.nextDouble() * 0.4, (world.random.nextDouble() - 0.5) * 0.5,
            )
        }
        if (soundsThisTick++ < LAND_SOUNDS_PER_TICK) {
            world.playSoundClient(
                p.pos.x, p.pos.y, p.pos.z, p.state.soundGroup.breakSound, SoundCategory.BLOCKS, LAND_VOLUME, 0.75f + world.random.nextFloat() * 0.2f, false,
            )
        }
    }

    /** Wie groß ist ein Stück gerade? Am Ende seiner Zeit bzw. kurz vor dem Sog-Ziel schrumpft es weich. */
    private fun scale(p: Piece): Float {
        val pull = p.pull
        val end = if (pull != null) pull.delay + pull.ticks else MAX_AGE.toFloat()
        val shrink = ((end - p.age) / SHRINK_TICKS).coerceIn(0f, 1f)
        return p.size * Ease.outCubic(shrink)
    }

    /** Verbindungsende (aus `FxClient.reset`). */
    fun clear() {
        live.clear()
    }

    /** Lebende Stücke (für Prüfungen und Berichte). */
    fun liveCount(): Int = live.size

    /** Zeichnet die Stücke eines Aufrufs als echte Blockmodelle; endet, wenn alle gelandet oder angekommen sind. */
    private class Draw(private val pieces: List<Piece>) : FxEffect(MAX_AGE + 2) {
        override fun finished(): Boolean = pieces.all { it.done }

        override fun solid(f: FxFrame) {
            val world = net.minecraft.client.MinecraftClient.getInstance().world ?: return
            for (p in pieces) {
                if (p.done) continue
                val size = scale(p)
                if (size <= 0.02f) continue
                val at = FxLib.lerp(p.previous, p.pos, f.tickDelta)
                val angle = p.angle + p.spin * f.tickDelta * FxClock.scale
                val m = Matrix4f().translation(at.x.toFloat(), at.y.toFloat(), at.z.toFloat())
                    .rotate(Quaternionf().rotationAxis(angle, p.axis))
                    .scale(size)
                    .translate(-0.5f, -0.5f, -0.5f)
                FxDraw.block(f, p.state, m, WorldRenderer.getLightmapCoordinates(world, BlockPos.ofFloored(at)))
            }
        }
    }
}
