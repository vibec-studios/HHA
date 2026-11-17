package dev.henny.hha.client.vfx.fx.lib

import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.logic.boss.BossFx
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `core.drip`: Teilchen mit Lebenslauf als ein Effekt (Vorbild Blood `BloodDripParticle`/`BloodPuddleParticle`, REF
 * 1.8; keine neuen Partikel-Typen). Tropfen lösen sich nacheinander bei `pos`, fallen auf ihren Platz im Umkreis und
 * bilden flache Lachen, die langsam vergehen. Alles wird aus Zeit und Seed berechnet (keine Zustände): Fallzeit aus der
 * beim Start gemessenen Bodenhöhe.
 * - `HOT_IRON`, `TORCH_FLAME`, `KERAUNOS`: Glut, die am Boden von hell nach dunkel verglüht (keine Lache).
 * - `VENOM`: Giftlache (nur Optik; die Gefahr zeigt der Telegraph).
 * - `ROCK_DUST`: Staubflecken. Sonst (`SOUL`): Wasser mit Spritzring.
 * Lachen bleiben durchscheinend (≤ [PUDDLE_ALPHA]), damit Ansagen darunter lesbar bleiben.
 *
 * Kodierung: pos = center (Quelle der Tropfen), a = radius, b = count, c = `Look`-Ordinal, life.
 */
class DripFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val look = BossFx.Look.entries.getOrNull(args.c.toInt()) ?: BossFx.Look.HOT_IRON
    private val palette = args.palette
    private val seed = args.seed.toInt()
    private val ember = look == BossFx.Look.HOT_IRON || look == BossFx.Look.TORCH_FLAME || look == BossFx.Look.KERAUNOS
    private val liquid = look == BossFx.Look.VENOM || look == BossFx.Look.SOUL

    /** Ein Tropfen: Start, Landeplatz (mit Bodenhöhe), Startzeit, Fallzeit, Größe der Lache. */
    private class Drop(val start: Vec3d, val land: Vec3d, val spawn: Float, val fall: Float, val size: Float)

    private val drops: List<Drop> = Random(args.seed).let { rnd ->
        val radius = args.a.coerceIn(0.2f, MAX_RADIUS)
        val n = args.b.toInt().coerceIn(0, MAX_DROPS)
        List(n) {
            val a = rnd.nextFloat() * Math.PI.toFloat() * 2f
            val d = radius * sqrt(rnd.nextFloat())
            val x = args.pos.x + cos(a) * d
            val z = args.pos.z + sin(a) * d
            val ground = FxLib.groundY(args.world, x, args.pos.y, z) + FxLib.GROUND_LIFT
            val drop = (args.pos.y - ground).coerceAtLeast(0.0)
            val fall = sqrt(2.0 * drop / GRAVITY).toFloat().coerceAtLeast(1f)
            val spawn = rnd.nextFloat() * life * SPAWN_SHARE
            Drop(args.pos.add((rnd.nextFloat() - 0.5) * 0.3, 0.0, (rnd.nextFloat() - 0.5) * 0.3), Vec3d(x, ground, z), spawn, fall, PUDDLE_MIN + rnd.nextFloat() * PUDDLE_RANGE)
        }
    }

    /** Lage eines Tropfens [s] Ticks nach dem Lösen: fällt quadratisch und treibt dabei weich zu seinem Platz. */
    private fun fallPos(d: Drop, s: Float): Vec3d {
        val k = (s / d.fall).coerceIn(0f, 1f)
        val side = FxLib.lerp(d.start, d.land, Ease.outQuad(k))
        val y = d.start.y - 0.5 * GRAVITY * s * s
        return Vec3d(side.x, maxOf(y, d.land.y), side.z)
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val end = ((life - t) / FADE_TICKS).coerceIn(0f, 1f)
        for ((index, d) in drops.withIndex()) {
            val s = t - d.spawn
            if (s <= 0f) continue
            if (s < d.fall) {
                // Fallender Tropfen: kurzer weicher Strich.
                val head = fallPos(d, s)
                val tail = fallPos(d, (s - TRAIL).coerceAtLeast(0f))
                FxDraw.ribbon(
                    f, listOf(tail, head), width = { u -> DROP_WIDTH * (0.4f + 0.6f * u) },
                    color = { u -> FxLib.alpha(FxLib.mix(palette.main, palette.core, u), DROP_ALPHA * end) },
                    mode = if (ember) FxMode.RIBBON else FxMode.WATER, seed = seed + index,
                )
                continue
            }
            val lie = s - d.fall
            val spread = Ease.outCubic(lie / SPREAD_TICKS)
            val vanish = ((life - d.spawn - d.fall - lie) / (life - d.spawn - d.fall).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val fade = Ease.inOut(vanish) * end
            if (fade <= 0.01f) continue
            when {
                ember -> {
                    // Glut verglüht: Farbe Kern → Haupt → Rand, Helligkeit fällt.
                    val cool = Ease.outQuad(lie / COOL_TICKS)
                    val c = if (cool < 0.5f) FxLib.mix(palette.core, palette.main, cool * 2f) else FxLib.mix(palette.main, palette.edge, cool * 2f - 1f)
                    FxDraw.flat(f, d.land, FxLib.UP, d.size * EMBER_SIZE * spread, FxLib.alpha(c, EMBER_ALPHA * fade * (1f - 0.6f * cool)), FxMode.GLOW, seed = seed + index)
                }
                look == BossFx.Look.ROCK_DUST -> FxDraw.flat(
                    f, d.land, FxLib.UP, d.size * spread, FxLib.alpha(DUST_COLOR, DUST_ALPHA * fade), FxMode.DUST, seed = seed + index,
                )
                else -> {
                    val mode = if (look == BossFx.Look.VENOM) FxMode.STAIN else FxMode.WATER
                    FxDraw.flat(f, d.land, FxLib.UP, d.size * spread, FxLib.alpha(palette.main, PUDDLE_ALPHA * fade), mode, rotation = index * 1.3f, seed = seed + index)
                }
            }
            // Spritzring beim Aufschlagen (nur Flüssiges, kurz).
            if (liquid && lie < SPLASH_TICKS) {
                val k = lie / SPLASH_TICKS
                val r = d.size * (0.4f + SPLASH_GROW * Ease.outCubic(k))
                FxDraw.flat(f, d.land.add(0.0, 0.01, 0.0), FxLib.UP, r, FxLib.alpha(palette.core, SPLASH_ALPHA * (1f - k)), FxMode.SHOCK, inner = r * 0.75f, seed = seed + 50 + index)
            }
        }
    }

    companion object {
        const val MIN_LIFE = 10
        const val MAX_RADIUS = 12f
        const val MAX_DROPS = 24
        const val GRAVITY = 0.04
        /** Tropfen lösen sich über diesen Anteil der Lebenszeit. */
        const val SPAWN_SHARE = 0.4f
        const val TRAIL = 2f
        const val DROP_WIDTH = 0.07f
        const val DROP_ALPHA = 0.85f
        const val SPREAD_TICKS = 6f
        const val FADE_TICKS = 8f
        const val COOL_TICKS = 40f
        const val PUDDLE_MIN = 0.25f
        const val PUDDLE_RANGE = 0.3f
        /** Lachen bleiben durchscheinend (Briefing: ≤ 0,6). */
        const val PUDDLE_ALPHA = 0.5f
        const val EMBER_SIZE = 0.55f
        const val EMBER_ALPHA = 0.6f
        const val DUST_COLOR = 0xFFB4AA98.toInt()
        const val DUST_ALPHA = 0.35f
        const val SPLASH_TICKS = 6f
        const val SPLASH_GROW = 1.2f
        const val SPLASH_ALPHA = 0.45f
    }
}
