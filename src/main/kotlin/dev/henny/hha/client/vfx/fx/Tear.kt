package dev.henny.hha.client.vfx.fx

import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.fx.lib.FxLib
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Riss in der Luft als Baustein (REF 4.3, weiche Fassung von Void `VoidTear`, `VoidRifts.kt:19-78`): Glutrand, dunkler
 * Kern, Zickzack, Haarrisse. Für `core.tear` und für Boss-Pakete (Kronos' Hände, Höllenpforten, Himmelsschlag).
 * Alles stetig: Das Kriechen der Ränder und das Atmen der Glut sind langsame Sinus (≤ 0,1 Hz bzw. ≤ 0,8 Hz).
 */
object Tear {

    /** Feste Form eines Risses: Zickzack mit [points] Punkten und vier Haarrissen, aus [seed] abgeleitet. */
    class Shape(val seed: Int, val points: Int = 15) {
        internal val count = points.coerceAtLeast(5)
        private val rnd = Random(seed.toLong())
        internal val zigzag = FloatArray(count) { i -> (if (i % 2 == 0) 1f else -1f) * (0.2f + rnd.nextFloat() * 0.8f) }
        internal val branches: List<Branch> = List(BRANCHES) {
            Branch(2 + rnd.nextInt(count - 4), if (rnd.nextBoolean()) 1f else -1f, 0.1f + rnd.nextFloat() * 0.12f)
        }
    }

    internal class Branch(val at: Int, val side: Float, val reach: Float)

    const val BRANCHES = 4
    /** Seitlicher Ausschlag des Zickzacks (Anteil der Länge bei voller Öffnung). */
    const val ZIG = 0.07f
    const val CRAWL = 0.012f
    const val CRAWL_SPEED = 0.17f
    const val BREATH_SPEED = 0.25f
    const val BREATH = 0.12f
    /** Kurzer Strich, wenn [draw] mit kleiner Öffnung läuft (Anteil der Länge). */
    const val CLOSED_LENGTH = 0.35f
    const val RIM_WIDTH = 1.5f
    const val RIM_ALPHA = 0.7f
    const val BODY_WIDTH = 1.05f
    const val CORE_WIDTH = 0.7f
    /** Dunkler Kern (fast schwarz, Farbe des Nichts). */
    const val VOID_COLOR = 0xFF07040C.toInt()
    const val HAIR_WIDTH = 0.16f

    private fun profile(k: Float): Float = sin(k * PI.toFloat()).coerceAtLeast(0f).pow(0.6f)

    /** Punkte des Risses bei Öffnung [open] (für Effekte, die etwas aus dem Riss kommen lassen). */
    fun points(center: Vec3d, along: Vec3d, across: Vec3d, length: Float, open: Float, shape: Shape, time: Float): List<Vec3d> {
        val shown = length * (CLOSED_LENGTH + (1f - CLOSED_LENGTH) * open.coerceIn(0f, 1f))
        return List(shape.count) { i ->
            val k = i / (shape.count - 1f) - 0.5f
            val crawl = sin(time * CRAWL_SPEED + i * 1.9f) * CRAWL * length
            val side = shape.zigzag[i] * ZIG * length * open + crawl
            center.add(along.multiply((k * shown).toDouble())).add(across.multiply(side.toDouble()))
        }
    }

    /**
     * Zeichnet einen Riss bei [center] entlang [along], quer [across] (Einheitsvektoren), [length] × [width] Blöcke,
     * zu [open] (0..1) geöffnet. [time] in Ticks, [hot] 0..1 = zusätzliche Glut. Nur in `FxEffect.energy`.
     * Aufbau: weicher Glutrand (RIBBON), Körper im Modus TEAR (Glutkante + dunkler Kern im Shader), dunkler Kern als
     * eigenes Band (deckt über die vormultiplizierte Mischung ab), vier Haarrisse.
     */
    fun draw(
        f: FxFrame, center: Vec3d, along: Vec3d, across: Vec3d, length: Float, width: Float, open: Float, shape: Shape,
        palette: Palette, time: Float, hot: Float = 0f,
    ) {
        if (open <= 0.01f) return
        val pts = points(center, along, across, length, open, shape, time)
        val w = width * open
        val breath = 1f - BREATH + BREATH * sin(time * BREATH_SPEED + shape.seed)
        val h = hot.coerceIn(0f, 1f)
        val rim = FxLib.mix(palette.main, palette.core, h * 0.5f)
        val edge = FxLib.mix(palette.main, palette.core, 0.3f + h * 0.6f)
        FxDraw.ribbon(
            f, pts, width = { k -> w * profile(k) * RIM_WIDTH + 0.08f },
            color = { _ -> FxLib.alpha(rim, RIM_ALPHA * breath) }, mode = FxMode.RIBBON, seed = shape.seed,
        )
        FxDraw.ribbon(
            f, pts, width = { k -> w * profile(k) * BODY_WIDTH + 0.05f },
            color = { _ -> FxLib.alpha(edge, 1f) }, mode = FxMode.TEAR, seed = shape.seed,
        )
        FxDraw.ribbon(
            f, pts, width = { k -> w * profile(k) * CORE_WIDTH },
            color = { _ -> FxLib.alpha(VOID_COLOR, 0.95f) }, mode = FxMode.RIBBON, seed = shape.seed + 1,
        )
        // Haarrisse, die vom Hauptriss in die Welt laufen.
        for (b in shape.branches) {
            val base = pts[b.at]
            val slant = (b.at - shape.count / 2) * 0.02f
            val hair = List(4) { j ->
                val k = j / 3f
                base.add(across.multiply((b.side * k * b.reach * length * open).toDouble()))
                    .add(along.multiply((k * slant * length + (if (j % 2 == 0) 0f else 0.012f * length)).toDouble()))
            }
            FxDraw.ribbon(
                f, hair, width = { k -> (w * HAIR_WIDTH + 0.04f) * (1f - k * 0.8f) },
                color = { k -> FxLib.alpha(edge, breath * (1f - k * 0.5f)) }, mode = FxMode.RIBBON, seed = shape.seed + b.at,
            )
        }
    }
}
