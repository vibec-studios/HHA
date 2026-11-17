package dev.henny.hha.client.vfx.fx.lib

import dev.henny.hha.HhaSounds
import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossFx
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.Vec3d
import kotlin.math.sin

/**
 * `core.wave_wall`: Eine Wand läuft quer über den Saal (Vorbild Void `WaveEffect`, `LeviathanEffects.kt:46-93`), als
 * glatte Wasserwand (`FxDraw.wall`, Modus WATER) mit laufender Krone. **Die Lücken haben einen goldenen Rand**: Gold
 * heißt hier „sicher“, die Farbe ist fest `Palette.of(GOLD)`, unabhängig vom Stil.
 *
 * Laufrichtung: Zeigt from → to nach rechts, läuft die Wand nach vorn, also `(along.z, 0, −along.x)` (gleiche
 * Konvention wie `yaw` in `API.md`). Zeit: [BUILD_TICKS] Aufbau am Start, dann gleichmäßiger Lauf über `travel`
 * Blöcke bis [FADE_TICKS] vor Schluss, dann ausblenden.
 *
 * Kodierung: pos = from, dir = to (absolut), a = travel, b = height, life, extra = Lücken `s:e;s:e` (Anteile 0..1).
 */
class WaveWallFx(args: FxArgs) : FxEffect(args.life.coerceAtLeast(MIN_LIFE)) {
    private val from = args.pos
    private val to = Vec3d(args.dir.x, args.pos.y, args.dir.z)
    private val along = FxLib.flat(to.subtract(from))
    private val forward = Vec3d(along.z, 0.0, -along.x)
    private val travel = args.a.coerceIn(-MAX_TRAVEL, MAX_TRAVEL)
    private val height = args.b.coerceIn(0.5f, MAX_HEIGHT)
    private val gaps: List<Pair<Float, Float>> = BossFx.decodeGaps(args.extra)
        .map { (s, e) -> minOf(s, e).coerceIn(0f, 1f) to maxOf(s, e).coerceIn(0f, 1f) }
        .filter { it.second - it.first > 1e-3f }.sortedBy { it.first }
    private val palette = args.palette
    private val gold = Palette.of(Vfx.Style.GOLD)
    private val seed = args.seed.toInt()

    /** Vorschub 0..1 entlang [travel]. */
    private fun front(t: Float): Float = ((t - BUILD_TICKS) / (life - BUILD_TICKS - FADE_TICKS).coerceAtLeast(1f)).coerceIn(0f, 1f)

    /** Stücke der Wand zwischen den Lücken (Anteile entlang from → to). */
    private val solids: List<Pair<Float, Float>> = buildList {
        var start = 0f
        for ((s, e) in gaps) {
            if (s > start) add(start to s)
            start = maxOf(start, e)
        }
        if (start < 1f) add(start to 1f)
    }

    override fun tick(world: ClientWorld) {
        if (age == 1) FxLib.sound(world, FxLib.lerp(from, to, 0.5f), HhaSounds.WAVE, SOUND_VOLUME, 1f)
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val build = Ease.outCubic(t / BUILD_TICKS)
        val fade = ((life - t) / FADE_TICKS).coerceIn(0f, 1f)
        if (build <= 0.01f || fade <= 0f) return
        val shift = forward.multiply((travel * front(t)).toDouble())
        val a = from.add(shift)
        val b = to.add(shift)
        val swell = 1f + SWELL * sin(t * SWELL_SPEED + seed)
        val h = height * build * swell

        FxDraw.wall(f, a, b, h, FxLib.alpha(palette.main, WALL_ALPHA * fade), FxMode.WATER, gaps, seed)

        // Krone je Wandstück: kippt nach vorn über, heller Saum.
        for ((index, piece) in solids.withIndex()) {
            val pts = List(CREST_POINTS) { i ->
                val s = piece.first + (piece.second - piece.first) * i / (CREST_POINTS - 1f)
                val roll = sin(s * CREST_WAVES + t * CREST_SPEED) * CREST_ROLL
                FxLib.lerp(a, b, s).add(0.0, (h * (1f + roll)).toDouble(), 0.0).add(forward.multiply((CREST_LEAN * h).toDouble()))
            }
            FxDraw.ribbon(
                f, pts, width = { _ -> CREST_WIDTH * h },
                color = { _ -> FxLib.alpha(FxLib.mix(palette.main, palette.core, 0.5f), CREST_ALPHA * fade) },
                mode = FxMode.WATER, scroll = t * 0.15f, seed = seed + 1 + index,
            )
            // Nasse Spur am Boden hinter der Wand (flach, durchscheinend).
            val back = forward.multiply((-TRAIL_LENGTH).toDouble())
            val foot = List(2) { i -> FxLib.lerp(a, b, if (i == 0) piece.first else piece.second).add(back.multiply(0.5)).add(0.0, FxLib.GROUND_LIFT, 0.0) }
            FxDraw.strip(
                f, foot, FxLib.UP, width = { _ -> TRAIL_LENGTH }, color = { _ -> FxLib.alpha(palette.edge, TRAIL_ALPHA * fade * build) },
                mode = FxMode.WATER, seed = seed + 20 + index,
            )
        }

        // Goldene Ränder der Lücken: Pfosten an beiden Kanten und ein feiner Saum am Boden (sicher).
        for ((index, gap) in gaps.withIndex()) {
            for (edge in listOf(gap.first, gap.second)) {
                if (edge <= 0f || edge >= 1f) continue
                val foot = FxLib.lerp(a, b, edge)
                FxDraw.ribbon(
                    f, listOf(foot, foot.add(0.0, (h * RIM_HEIGHT).toDouble(), 0.0)), width = { _ -> RIM_WIDTH },
                    color = { u -> FxLib.alpha(FxLib.mix(gold.main, gold.core, u), RIM_ALPHA * fade) }, mode = FxMode.RIBBON, seed = seed + 40 + index,
                )
            }
            FxDraw.strip(
                f, listOf(FxLib.lerp(a, b, gap.first), FxLib.lerp(a, b, gap.second)).map { it.add(0.0, FxLib.GROUND_LIFT * 2, 0.0) },
                FxLib.UP, width = { _ -> GAP_LINE }, color = { _ -> FxLib.alpha(gold.main, GAP_ALPHA * fade * build) },
                mode = FxMode.RIBBON, seed = seed + 60 + index,
            )
        }
    }

    companion object {
        const val MIN_LIFE = 12
        const val MAX_TRAVEL = 96f
        const val MAX_HEIGHT = 16f
        const val BUILD_TICKS = 8f
        const val FADE_TICKS = 10f
        const val SWELL = 0.08f
        const val SWELL_SPEED = 0.2f
        const val WALL_ALPHA = 0.6f
        const val CREST_POINTS = 16
        const val CREST_WAVES = 14f
        const val CREST_SPEED = 0.25f
        const val CREST_ROLL = 0.05f
        const val CREST_LEAN = 0.18f
        const val CREST_WIDTH = 0.16f
        const val CREST_ALPHA = 0.7f
        const val TRAIL_LENGTH = 1.6f
        const val TRAIL_ALPHA = 0.3f
        const val RIM_HEIGHT = 1.25f
        const val RIM_WIDTH = 0.22f
        const val RIM_ALPHA = 0.95f
        const val GAP_LINE = 0.12f
        const val GAP_ALPHA = 0.5f
        const val SOUND_VOLUME = 0.6f
    }
}
