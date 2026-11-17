package dev.henny.hha.client.vfx.fx.lib

import dev.henny.hha.HhaSounds
import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.logic.boss.BossFx
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * `core.crack` / `core.cracks`: glühender Bodensprung, der sich vorfrisst, liegen bleibt und ausglüht (Vorbild Void
 * `GroundCrackEffect`, `VoidRifts.kt:144-171`). Liegt [FxLib.GROUND_LIFT] über dem Boden und folgt dem Gelände: Die
 * Punkte liegen höchstens [STEP] auseinander, jeder bekommt die Bodenhöhe seiner Säule; wo die Höhe springt, beginnt
 * ein neues Stück (kein Band schräg durch eine Stufe). Schmal, damit Ansagen darunter lesbar bleiben.
 *
 * Kodierung `core.crack`: pos = from, dir = Richtung, a = length, b = grow (Ticks), life.
 * Kodierung `core.cracks`: pos = center, a = radius, b = count (≤ 6), life (sternförmig, Wachsen [STAR_GROW]).
 */
class GroundCrackFx private constructor(
    private val palette: Palette, private val branches: List<Branch>, life: Int, private val seed: Int,
) : FxEffect(life) {

    /** Ein Sprung: feste Punkte samt Höhe, Bogenanteile, Start und Wachsdauer in Ticks, Breite. */
    class Branch(val points: List<Vec3d>, val start: Float, val grow: Float, val width: Float) {
        val fractions = FxLib.arcFractions(points)
    }

    /** Leiser Ortston im ersten Tick (nur, wenn der Effekt wirklich läuft). */
    override fun tick(world: ClientWorld) {
        if (age == 1) branches.firstOrNull()?.let { FxLib.sound(world, it.points.first(), HhaSounds.CRACK, SOUND_VOLUME, 0.9f + (seed and 7) * 0.03f) }
    }

    override fun energy(f: FxFrame) {
        val t = time(f)
        val fade = ((life - t) / FADE_TICKS).coerceIn(0f, 1f)
        if (fade <= 0f) return
        for ((index, b) in branches.withIndex()) {
            val local = t - b.start
            if (local <= 0f) continue
            val grown = Ease.outCubic(local / b.grow)
            if (grown <= 0.01f) continue
            val heat = (1f - (local - b.grow) / HEAT_TICKS).coerceIn(0f, 1f)
            val shown = FxLib.partial(b.points, b.fractions, grown)
            drawRuns(f, shown, grown, b.width, heat, fade, seed + index)
        }
    }

    /** Zerlegt die Punkte in Stücke gleicher Bodenhöhe und zeichnet jedes als liegendes Band. */
    private fun drawRuns(f: FxFrame, points: List<Vec3d>, grown: Float, width: Float, heat: Float, fade: Float, seed: Int) {
        val fr = FxLib.arcFractions(points)
        var from = 0
        for (i in 1..points.size) {
            val split = i == points.size || abs(points[i].y - points[i - 1].y) > STEP_HEIGHT
            if (!split) continue
            if (i - from >= 2) {
                val run = points.subList(from, i)
                val s0 = fr[from] * grown
                val s1 = fr[i - 1] * grown
                strips(f, run, { u -> s0 + (s1 - s0) * u }, grown, width, heat, fade, seed)
            }
            from = i
        }
    }

    private fun strips(
        f: FxFrame, run: List<Vec3d>, global: (Float) -> Float, grown: Float, width: Float, heat: Float, fade: Float, seed: Int,
    ) {
        // Breite: dick am Ursprung, spitz zum Kopf; der Kopf selbst läuft weich aus.
        fun w(u: Float): Float {
            val s = global(u)
            val tip = ((grown - s) / TIP_SOFT).coerceIn(0f, 1f)
            return width * (1f - TAPER * s) * tip
        }
        val glowColor = FxLib.mix(palette.edge, palette.main, 0.4f + 0.6f * heat)
        FxDraw.strip(
            f, run, FxLib.UP, width = { u -> w(u) * GLOW_WIDTH },
            color = { _ -> FxLib.alpha(glowColor, fade * (GLOW_ALPHA_COOL + (GLOW_ALPHA_HOT - GLOW_ALPHA_COOL) * heat)) },
            mode = FxMode.CRACK, seed = seed,
        )
        val coreColor = FxLib.mix(palette.main, palette.core, heat)
        FxDraw.strip(
            f, run, FxLib.UP, width = ::w,
            color = { _ -> FxLib.alpha(coreColor, fade * (CORE_ALPHA_COOL + (CORE_ALPHA_HOT - CORE_ALPHA_COOL) * heat)) },
            mode = FxMode.CRACK, seed = seed + 1,
        )
    }

    override fun over(f: FxFrame) {
        val t = time(f)
        for (b in branches) {
            val local = t - b.start
            if (local <= 0f || local >= b.grow) continue
            val head = FxLib.partial(b.points, b.fractions, Ease.outCubic(local / b.grow)).last()
            FxDraw.glow(f, head.add(0.0, 0.05, 0.0), b.width * HEAD_GLOW, FxLib.alpha(palette.core, HEAD_ALPHA * (1f - local / b.grow)))
        }
    }

    companion object {
        /** Abstand der Punkte (Blöcke): höchstens 1 Block, damit der Sprung dem Gelände folgt. */
        const val STEP = 0.5
        /** Höhensprung, ab dem ein neues Stück beginnt. */
        const val STEP_HEIGHT = 0.3
        const val WIDTH = 0.13f
        const val TAPER = 0.6f
        const val TIP_SOFT = 0.08f
        const val GLOW_WIDTH = 2.6f
        const val GLOW_ALPHA_HOT = 0.45f
        const val GLOW_ALPHA_COOL = 0.15f
        const val CORE_ALPHA_HOT = 0.85f
        const val CORE_ALPHA_COOL = 0.4f
        /** Nach dem Wachsen glüht der Sprung so viele Ticks aus (bleibt dann matt bis zum Ausblenden). */
        const val HEAT_TICKS = 18f
        const val FADE_TICKS = 14f
        const val HEAD_GLOW = 4f
        const val HEAD_ALPHA = 0.5f
        /** Seitliches Mäandern (Anteil der Länge) und feiner Zickzack (Blöcke). */
        const val MEANDER = 0.06f
        const val ZIGZAG = 0.07
        const val STAR_GROW = 8f
        const val STAR_STAGGER = 1.5f
        const val STAR_MIN = 0.65f
        const val SOUND_VOLUME = 0.55f
        const val MIN_LIFE = 10

        /** Punkte eines Sprungs von [from] in [dir] über [length] Blöcke, auf den Boden gelegt. */
        fun path(world: ClientWorld, from: Vec3d, dir: Vec3d, length: Float, rnd: Random): List<Vec3d> {
            val d = FxLib.flat(dir)
            val side = Vec3d(-d.z, 0.0, d.x)
            val n = (length / STEP).toInt().coerceIn(2, 96)
            val phase = rnd.nextFloat() * 10f
            return List(n + 1) { i ->
                val k = i / n.toFloat()
                val edge = if (i == 0) 0f else 1f
                val swing = Ease.wobble(k * 18f, phase) * MEANDER * length * edge +
                    (if (i % 2 == 0) 1.0 else -1.0).toFloat() * (ZIGZAG * rnd.nextFloat()).toFloat() * edge
                val p = from.add(d.multiply(length * k.toDouble())).add(side.multiply(swing.toDouble()))
                Vec3d(p.x, FxLib.groundY(world, p.x, from.y + 0.5, p.z) + FxLib.GROUND_LIFT, p.z)
            }
        }

        /** `core.crack`. */
        fun single(args: FxArgs): GroundCrackFx {
            val rnd = Random(args.seed)
            val length = args.a.coerceIn(0.5f, 64f)
            val branch = Branch(path(args.world, args.pos, args.dir, length, rnd), 0f, args.b.coerceIn(1f, 200f), WIDTH)
            return GroundCrackFx(args.palette, listOf(branch), args.life.coerceAtLeast(MIN_LIFE), args.seed.toInt())
        }

        /** `core.cracks`: sternförmig um einen Punkt. */
        fun star(args: FxArgs): GroundCrackFx {
            val rnd = Random(args.seed)
            val radius = args.a.coerceIn(0.5f, 32f)
            val count = args.b.toInt().coerceIn(1, BossFx.CRACKS_PER_CALL)
            val turn = rnd.nextFloat() * Math.PI.toFloat() * 2f
            val branches = List(count) { i ->
                val angle = turn + i * (Math.PI.toFloat() * 2f / count) + (rnd.nextFloat() - 0.5f) * 0.7f
                val dir = Vec3d(cos(angle).toDouble(), 0.0, sin(angle).toDouble())
                val reach = radius * (STAR_MIN + rnd.nextFloat() * (1f - STAR_MIN))
                val start = args.pos.add(dir.multiply(0.25))
                Branch(path(args.world, start, dir, reach, rnd), rnd.nextFloat() * STAR_STAGGER, STAR_GROW, WIDTH * (0.8f + 0.4f * rnd.nextFloat()))
            }
            return GroundCrackFx(args.palette, branches, args.life.coerceAtLeast(MIN_LIFE), args.seed.toInt())
        }
    }
}
