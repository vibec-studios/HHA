package dev.henny.hha.client.vfx

import dev.henny.hha.HhaParticles
import dev.henny.hha.entity.boss.HermesPose
import dev.henny.hha.logic.Vfx
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Das Leuchten von Hermes und den Schlangen. Die Renderer lesen Ankerpunkte direkt aus den Knochen und reichen sie
 * pro Bild hierher; gezeichnet wird danach im normalen Effekt-Durchlauf, damit Hermes dieselben Shader und Mischungen
 * nutzt wie alle anderen 3D-Effekte.
 */
object BossGlow {

    class HermesFrame(val id: Int, val stage: Int, val illusion: Boolean, val pose: Int, val speed: Float, val time: Float, val corrupted: Boolean = false) {
        var power = 1f
        var orb = Vec3d.ZERO
        var staffTop = Vec3d.ZERO
        var staffBottom = Vec3d.ZERO
        var chest = Vec3d.ZERO
        var halo = Vec3d.ZERO
        var haloRight = Vec3d.ZERO
        var haloUp = Vec3d.ZERO
        var eyeR = Vec3d.ZERO
        var eyeL = Vec3d.ZERO
        var handR = Vec3d.ZERO
        var handL = Vec3d.ZERO
        var footR = Vec3d.ZERO
        var footL = Vec3d.ZERO
        var wingR: List<Vec3d> = emptyList()
        var wingL: List<Vec3d> = emptyList()
        var tipR = Vec3d.ZERO
        var tipL = Vec3d.ZERO
    }

    class SerpentFrame(val id: Int, val variant: Int, val biting: Boolean, val time: Float) {
        var eyeR = Vec3d.ZERO
        var eyeL = Vec3d.ZERO
        var mouth = Vec3d.ZERO
        var spine: List<Vec3d> = emptyList()
    }

    private class Seen<T>(val frame: T) {
        var ticks = 0
    }

    private val hermesFrames = ArrayList<HermesFrame>()
    private val serpentFrames = ArrayList<SerpentFrame>()
    private val lastHermes = HashMap<Int, Seen<HermesFrame>>()
    private val lastSerpents = HashMap<Int, Seen<SerpentFrame>>()
    private val trails = HashMap<Int, Array<ArrayDeque<Vec3d>>>()
    private val powers = HashMap<Int, Float>()

    private val STAGE_PALETTES = mapOf(
        1 to Palette.of(Vfx.Style.HEAVEN),
        2 to Palette(0xFFFFF0D8.toInt(), 0xFFE0A2FF.toInt(), 0xFF7A3FC0.toInt(), 0xFFFFD75A.toInt()),
        3 to Palette.of(Vfx.Style.HELL),
        4 to Palette.of(Vfx.Style.STORM),
    )

    private val SHADE_PALETTE = Palette.of(Vfx.Style.HADES)

    private val SNAKE_A = Palette(0xFFDFFFF2.toInt(), 0xFF39E0A8.toInt(), 0xFF0F6A52.toInt(), 0xFFFFD75A.toInt())
    private val SNAKE_B = Palette(0xFFE2F4FF.toInt(), 0xFF62A3F2.toInt(), 0xFF28479E.toInt(), 0xFFFFD75A.toInt())
    private val SHADE_SNAKE_A = Palette(0xFFE8FFD0.toInt(), 0xFF4CFF5A.toInt(), 0xFF0E7A2C.toInt(), 0xFF9CFF3C.toInt())
    private val SHADE_SNAKE_B = Palette(0xFFF2FFC0.toInt(), 0xFFB2FF3C.toInt(), 0xFF4A7A0E.toInt(), 0xFF4CFF5A.toInt())
    private const val SNAKE_EYE = 0xFFFFE27A.toInt()
    private const val SNAKE_TONGUE = 0xFFFF5A7A.toInt()
    private const val SNAKE_POINTS = 36
    private const val SNAKE_LENGTH = 0.58
    private const val SNAKE_PERIOD = 170.0

    /** Wie stark Hermes gerade strahlt: in der Erschöpfung fast erloschen, beim Zaubern gleißend. */
    fun targetPower(stage: Int, vulnerable: Boolean, pose: Int, illusion: Boolean): Float {
        var power = when (pose) {
            HermesPose.CAST, HermesPose.ASCEND -> 1.45f
            HermesPose.SLAM, HermesPose.THROW -> 1.2f
            HermesPose.KNEEL, HermesPose.FALL -> 0.28f
            HermesPose.STAGGER -> 0.45f
            else -> 1f
        }
        if (stage == 4) power *= if (vulnerable) 0.4f else 1.25f
        if (illusion) power *= 0.45f
        return power
    }

    /** Weiche Überblendung je Entität, damit der Wechsel nicht springt. */
    fun smoothPower(id: Int, target: Float): Float {
        val now = powers.getOrDefault(id, target)
        val next = now + (target - now) * 0.08f
        powers[id] = next
        return next
    }

    fun submit(frame: HermesFrame) {
        hermesFrames.add(frame)
    }

    fun submit(frame: SerpentFrame) {
        serpentFrames.add(frame)
    }

    fun clear() {
        hermesFrames.clear()
        serpentFrames.clear()
    }

    fun render(d: VfxDraw) {
        for (frame in hermesFrames) {
            drawHermes(d, frame)
            lastHermes[frame.id] = Seen(frame)
        }
        for (frame in serpentFrames) {
            drawSerpent(d, frame)
            lastSerpents[frame.id] = Seen(frame)
        }
        clear()
    }

    /** Partikel lassen sich nur im Spieltakt sauber dosieren: wenige Federn und Funken aus den letzten Ankerpunkten. */
    fun tick(world: ClientWorld) {
        val random = world.random
        lastHermes.values.removeIf { ++it.ticks > 3 }
        lastSerpents.values.removeIf { ++it.ticks > 3 }
        if (lastHermes.isEmpty()) {
            trails.clear()
            powers.clear()
        }
        for (seen in lastHermes.values) {
            val f = seen.frame
            if (f.illusion || f.wingR.size < 4) continue
            val rate = if (f.speed > 0.3f) 6 else 22
            if (random.nextInt(rate) == 0) {
                val wing = if (random.nextBoolean()) f.wingR else f.wingL
                val tip = if (wing === f.wingR) f.tipR else f.tipL
                val root = wing[1 + random.nextInt(wing.size - 1)]
                val at = root.lerp(tip, random.nextDouble())
                world.addParticleClient(HhaParticles.FEATHER, at.x, at.y, at.z, 0.0, 0.0, 0.0)
            }
            if (!f.corrupted && random.nextInt(3) == 0) {
                val a = random.nextDouble() * Math.PI * 2.0
                world.addParticleClient(HhaParticles.HOLY_SPARK, f.orb.x + cos(a) * 0.5, f.orb.y + (random.nextDouble() - 0.5), f.orb.z + sin(a) * 0.5, 0.0, 0.01, 0.0)
            }
        }
        for (seen in lastSerpents.values) {
            val spine = seen.frame.spine
            if (spine.isNotEmpty() && random.nextInt(4) == 0) {
                val at = spine[random.nextInt(spine.size)]
                world.addParticleClient(HhaParticles.HOLY_SPARK, at.x, at.y + 0.3, at.z, 0.0, 0.015, 0.0)
            }
        }
    }

    private fun drawHermes(d: VfxDraw, f: HermesFrame) {
        val pal = if (f.corrupted) SHADE_PALETTE else STAGE_PALETTES[f.stage] ?: STAGE_PALETTES.getValue(1)
        val t = f.time.toDouble()
        val p = f.power * (0.88f + 0.12f * sin(f.time * 0.19f))

        // Weiches Licht um den ganzen Körper.
        d.billboard(VfxLayers.GLOW_ADD, f.chest, 6.0, withAlpha(pal.main, 0.14f * p))
        d.billboard(VfxLayers.GLOW_ADD, f.chest, 3.2, withAlpha(pal.core, 0.2f * p))

        drawOrb(d, f, pal, p, t)
        drawStaff(d, f, pal, p, t)
        drawHalo(d, f, pal, p, t)

        // Sonne auf dem Panzer, glühende Augen, Licht an Händen und Sandalen.
        d.billboard(VfxLayers.FLARE_ADD, f.chest, 1.5 + 0.2 * sin(t * 0.16), withAlpha(pal.core, 0.7f * p), t * 0.012)
        d.billboard(VfxLayers.GLOW_ADD, f.chest, 1.3, withAlpha(pal.main, 0.6f * p))
        for (eye in listOf(f.eyeR, f.eyeL)) {
            d.billboard(VfxLayers.GLOW_ADD, eye, 0.42, withAlpha(pal.main, 0.85f * p))
            d.billboard(VfxLayers.FLARE_ADD, eye, 0.34, withAlpha(pal.core, 0.9f * p), t * 0.03)
        }
        d.billboard(VfxLayers.GLOW_ADD, f.handL, 0.7, withAlpha(pal.main, 0.35f * p))
        d.billboard(VfxLayers.GLOW_ADD, f.footR, 0.8, withAlpha(pal.main, 0.32f * p))
        d.billboard(VfxLayers.GLOW_ADD, f.footL, 0.8, withAlpha(pal.main, 0.32f * p))

        drawWing(d, f.wingR, f.tipR, pal, p)
        drawWing(d, f.wingL, f.tipL, pal, p)

        when (f.stage) {
            3 -> drawSoulMotes(d, f, pal, p, t)
            4 -> if (!f.illusion) drawArcs(d, f, pal, p)
        }
        if (!f.illusion) drawTrails(d, f, pal, p)
    }

    private fun drawOrb(d: VfxDraw, f: HermesFrame, pal: Palette, p: Float, t: Double) {
        d.billboard(VfxLayers.GLOW_ADD, f.orb, 1.9, withAlpha(pal.main, 0.6f * p))
        d.billboard(VfxLayers.GLOW_ADD, f.orb, 0.9, withAlpha(pal.core, 0.95f * p))
        d.billboard(VfxLayers.FLARE_ADD, f.orb, 2.2, withAlpha(pal.core, 0.85f * p), t * 0.02)
        d.billboard(VfxLayers.FLARE_ADD, f.orb, 1.4, withAlpha(pal.main, 0.6f * p), -t * 0.035)

        // Zwei schräge Lichtringe und kreisende Funken.
        val (a, b) = basisOf(f.staffTop.subtract(f.staffBottom))
        for (i in 0..1) {
            val angle = t * (0.05 + i * 0.03) + i * 1.9
            val tiltA = a.multiply(cos(angle)).add(b.multiply(sin(angle)))
            val up = f.staffTop.subtract(f.staffBottom).normalize()
            val tiltB = up.multiply(0.55).add(a.multiply(-sin(angle)).add(b.multiply(cos(angle))).multiply(0.83))
            val radius = 0.62 + i * 0.16
            d.ring(VfxLayers.ENERGY, f.orb, tiltA, tiltB, radius - 0.035, radius + 0.035, withAlpha(pal.core, 0.95f * p), withAlpha(pal.main, 0.8f * p), 32)
        }
        for (i in 0 until 5) {
            val angle = t * 0.11 + i * 1.2566
            val spark = f.orb.add(a.multiply(cos(angle) * 0.85)).add(b.multiply(sin(angle) * 0.85)).add(f.staffTop.subtract(f.staffBottom).normalize().multiply(sin(angle * 2.0) * 0.25))
            d.billboard(VfxLayers.GLOW_ADD, spark, 0.26, withAlpha(pal.core, 0.9f * p))
        }
    }

    private fun drawStaff(d: VfxDraw, f: HermesFrame, pal: Palette, p: Float, t: Double) {
        d.ribbon(VfxLayers.GLOW_ADD, listOf(f.staffTop, f.staffBottom), { 0.2 }, { withAlpha(pal.main, 0.22f * p) }, 0f, 0.5f)
        drawSerpent(d, f, p, t, 0, if (f.corrupted) SHADE_SNAKE_A else SNAKE_A)
        drawSerpent(d, f, p, t, 1, if (f.corrupted) SHADE_SNAKE_B else SNAKE_B)
    }

    /**
     * Eine Lichtschlange am Caduceus. Sie klettert in langen Windungen den Schaft hinauf und wieder hinab; durch die
     * laufende Drehung und eine Welle im Radius scheint sich der Körper wirklich zu winden. Oben löst sich der Kopf vom
     * Stab, bäumt sich auf und züngelt. Die beiden Schlangen klettern gegenläufig, begegnen sich also in der Mitte.
     */
    private fun drawSerpent(d: VfxDraw, f: HermesFrame, p: Float, t: Double, strand: Int, pal: Palette) {
        val axis = f.staffBottom.subtract(f.staffTop)
        val (sideA, sideB) = basisOf(axis)
        val climb = ((t / SNAKE_PERIOD + strand * 0.5) % 1.0) * Math.PI * 2.0
        val headAt = 0.05 + 0.62 * (0.5 + 0.5 * cos(climb))
        val rearing = smoothstep(0.24, 0.07, headAt)
        val hurry = (p - 1f).coerceIn(0f, 0.6f).toDouble()
        val path = List(SNAKE_POINTS) { i ->
            val s = i.toDouble() / (SNAKE_POINTS - 1)
            val k = (headAt + SNAKE_LENGTH * s).coerceAtMost(1.02)
            val phase = k * 24.0 + t * (0.15 + hurry * 0.2) + strand * Math.PI
            val radius = 0.125 + hurry * 0.12 + sin(k * 27.0 - t * 0.31) * 0.035
            val out = sideA.multiply(cos(phase)).add(sideB.multiply(sin(phase)))
            var point = f.staffTop.add(axis.multiply(k)).add(out.multiply(radius))
            if (s < 0.24 && rearing > 0.0) {
                // Der vordere Teil des Körpers löst sich vom Stab und richtet sich auf.
                val lift = (1.0 - s / 0.24).let { it * it } * rearing
                val sway = sin(t * 0.12 + strand * 2.0) * 0.1
                val away = if (strand == 0) sideA else sideA.multiply(-1.0)
                point = point.add(away.multiply(lift * (0.38 + sway))).subtract(axis.normalize().multiply(lift * 0.3))
            }
            point
        }
        fun thickness(s: Float): Double = (if (s < 0.08f) 0.72 + s * 3.5 else 1.0) * (1.0 - s * s * s * 0.86)

        d.ribbon(VfxLayers.GLOW_ADD, path, { 0.15 * thickness(it) }, { withAlpha(pal.main, (0.6f - it * 0.35f) * p) }, 0f, 0.5f)
        d.ribbon(VfxLayers.ENERGY, path, { 0.052 * thickness(it) }, { withAlpha(pal.core, (1f - it * 0.5f) * p) }, 0.45f, (t * -0.06).toFloat())

        // Schuppenlichter wandern als Wellen vom Schwanz zum Kopf.
        for (i in 2 until SNAKE_POINTS step 2) {
            val s = i.toFloat() / (SNAKE_POINTS - 1)
            val wave = sin(i * 0.8 - t * 0.42).toFloat().coerceAtLeast(0f)
            val glint = wave * wave * wave
            if (glint > 0.05f) d.billboard(VfxLayers.GLOW_ADD, path[i], 0.17 * thickness(s), withAlpha(pal.core, glint * (1f - s * 0.5f) * p))
        }

        // Kopf: spitz zulaufend, mit Augen und Zunge.
        val head = path[0]
        val forward = head.subtract(path[2]).let { if (it.lengthSquared() < 1e-6) axis.normalize().multiply(-1.0) else it.normalize() }
        val across = forward.crossProduct(d.cam.subtract(head)).let { if (it.lengthSquared() < 1e-6) sideA else it.normalize() }
        val snout = head.add(forward.multiply(0.15))
        d.ribbon(VfxLayers.ENERGY, listOf(head.subtract(forward.multiply(0.1)), head, head.add(forward.multiply(0.07)), snout), { k -> if (k < 0.5f) 0.06 + k * 0.07 else 0.095 - (k - 0.5) * 0.17 }, { withAlpha(pal.core, p) }, 0.3f)
        d.billboard(VfxLayers.GLOW_ADD, head, 0.46, withAlpha(pal.main, 0.75f * p))
        for (side in intArrayOf(-1, 1)) {
            val eye = head.add(forward.multiply(0.02)).add(across.multiply(side * 0.05))
            d.billboard(VfxLayers.FLARE_ADD, eye, 0.16, withAlpha(SNAKE_EYE, p), t * 0.05)
        }
        val flick = (t / 38.0 + strand * 0.37) % 1.0
        if (flick < 0.2) {
            val reach = sin(flick / 0.2 * Math.PI) * 0.2
            val root = snout.add(forward.multiply(reach * 0.65))
            d.ribbon(VfxLayers.ENERGY, listOf(snout, root), { 0.012 }, { withAlpha(SNAKE_TONGUE, p) }, 0.3f)
            for (side in intArrayOf(-1, 1)) {
                d.ribbon(VfxLayers.ENERGY, listOf(root, root.add(forward.multiply(reach * 0.35)).add(across.multiply(side * reach * 0.22))), { 0.009 }, { withAlpha(SNAKE_TONGUE, p) }, 0.3f)
            }
        }
    }

    private fun smoothstep(from: Double, to: Double, value: Double): Double {
        val k = ((value - from) / (to - from)).coerceIn(0.0, 1.0)
        return k * k * (3.0 - 2.0 * k)
    }

    private fun drawHalo(d: VfxDraw, f: HermesFrame, pal: Palette, p: Float, t: Double) {
        d.billboard(VfxLayers.GLOW_ADD, f.halo, 2.5, withAlpha(pal.main, 0.22f * p))
        d.disc(VfxLayers.RUNE_ADD, f.halo, f.haloRight, f.haloUp, 0.95, withAlpha(pal.main, 0.55f * p), t * 0.012)
        d.ring(VfxLayers.ENERGY, f.halo, f.haloRight, f.haloUp, 0.6, 0.67, withAlpha(pal.core, 0.9f * p), withAlpha(pal.main, 0.75f * p), 40)
    }

    private fun drawWing(d: VfxDraw, bone: List<Vec3d>, tip: Vec3d, pal: Palette, p: Float) {
        if (bone.size < 2) return
        d.ribbon(VfxLayers.GLOW_ADD, bone, { k -> 0.2 - 0.11 * k }, { withAlpha(pal.core, 0.55f * p) }, 0f, 0.5f)
        d.ribbon(VfxLayers.GLOW_ADD, listOf(bone.last(), tip), { k -> 0.12 - 0.08 * k }, { withAlpha(pal.main, 0.45f * p) }, 0f, 0.5f)
        d.billboard(VfxLayers.GLOW_ADD, tip, 0.7, withAlpha(pal.main, 0.5f * p))
        d.billboard(VfxLayers.FLARE_ADD, tip, 0.65, withAlpha(pal.core, 0.6f * p))
    }

    /** Stage 3: Seelenlichter steigen in einer Spirale um ihn auf. */
    private fun drawSoulMotes(d: VfxDraw, f: HermesFrame, pal: Palette, p: Float, t: Double) {
        for (i in 0 until 10) {
            val cycle = (t * 0.012 + i * 0.1) % 1.0
            val angle = i * 2.4 + t * 0.03 + cycle * 3.0
            val radius = 1.1 + 0.35 * sin(i * 1.3)
            val at = f.chest.add(cos(angle) * radius, -1.3 + cycle * 3.6, sin(angle) * radius)
            d.billboard(VfxLayers.GLOW_ADD, at, 0.34, withAlpha(pal.main, 0.8f * p * sin(cycle * Math.PI).toFloat()))
        }
    }

    /** Stage 4: Blitze springen vom Stab auf Flügel und Hände und wechseln alle paar Ticks. */
    private fun drawArcs(d: VfxDraw, f: HermesFrame, pal: Palette, p: Float) {
        val targets = listOf(f.tipR, f.tipL, f.handL, f.footR, f.footL)
        val seed = floor(f.time / 2.5f).toLong() * 31L + f.id
        val random = Random(seed)
        repeat(3) {
            val end = targets[random.nextInt(targets.size)]
            val path = ArrayList<Vec3d>()
            val steps = 9
            for (i in 0..steps) {
                val k = i.toDouble() / steps
                val jitter = if (i == 0 || i == steps) 0.0 else 0.38
                path.add(f.orb.lerp(end, k).add((random.nextDouble() - 0.5) * jitter * 2.0, (random.nextDouble() - 0.5) * jitter * 2.0, (random.nextDouble() - 0.5) * jitter * 2.0))
            }
            d.ribbon(VfxLayers.GLOW_ADD, path, { 0.2 }, { withAlpha(pal.main, 0.5f * p) }, 0f, 0.5f)
            d.ribbon(VfxLayers.ENERGY, path, { 0.045 }, { withAlpha(pal.core, 1f * p) }, 0.4f)
        }
    }

    /** Lichtbahnen hinter Stab und Flügelspitzen, sobald er Tempo aufnimmt. */
    private fun drawTrails(d: VfxDraw, f: HermesFrame, pal: Palette, p: Float) {
        val slots = trails.getOrPut(f.id) { Array(3) { ArrayDeque() } }
        val points = arrayOf(f.orb, f.tipR, f.tipL)
        for (i in slots.indices) {
            val slot = slots[i]
            if (f.speed > 0.25f) {
                if (slot.isEmpty() || slot.last().squaredDistanceTo(points[i]) > 0.09) slot.addLast(points[i])
                while (slot.size > 26) slot.removeFirst()
            } else if (slot.isNotEmpty()) {
                slot.removeFirst()
            }
            if (slot.size >= 3) {
                val path = slot.toList() + points[i]
                d.ribbon(VfxLayers.GLOW_ADD, path, { k -> 0.2 * k }, { k -> withAlpha(pal.main, k * 0.65f * p) }, 0f, 0.5f)
                d.ribbon(VfxLayers.ENERGY, path, { k -> 0.05 * k }, { k -> withAlpha(pal.core, k * 0.9f * p) }, 0.2f)
            }
        }
    }

    private fun drawSerpent(d: VfxDraw, f: SerpentFrame) {
        val pal = if (f.variant == 0) {
            Palette(0xFFDFFFF2.toInt(), 0xFF39E0A8.toInt(), 0xFF0F6A52.toInt(), 0xFFFFD75A.toInt())
        } else {
            Palette(0xFFE2F4FF.toInt(), 0xFF62A3F2.toInt(), 0xFF28479E.toInt(), 0xFFFFD75A.toInt())
        }
        val rage = if (f.biting) 1.4f else 1f
        for (eye in listOf(f.eyeR, f.eyeL)) {
            d.billboard(VfxLayers.GLOW_ADD, eye, 0.62 * rage, withAlpha(pal.alt, 0.9f))
            d.billboard(VfxLayers.FLARE_ADD, eye, 0.5 * rage, withAlpha(pal.core, 0.9f), f.time * 0.04)
        }
        // Im Maul sammelt sich vor dem Biss Licht.
        d.billboard(VfxLayers.GLOW_ADD, f.mouth, if (f.biting) 1.9 else 0.8, withAlpha(pal.main, if (f.biting) 0.8f else 0.28f))
        if (f.biting) d.billboard(VfxLayers.FLARE_ADD, f.mouth, 1.7, withAlpha(pal.core, 0.75f), f.time * 0.07)

        // Leuchtender Rückengrat-Streifen und ein schwacher Schein um den Leib.
        if (f.spine.size >= 3) {
            val flow = f.time * 0.05f
            d.ribbon(VfxLayers.ENERGY, f.spine, { k -> 0.09 * (1.0 - k * 0.7) }, { k -> withAlpha(pal.core, 0.9f - 0.5f * k) }, 0.3f, -flow)
            for (i in f.spine.indices step 3) {
                val k = i.toFloat() / f.spine.size
                d.billboard(VfxLayers.GLOW_ADD, f.spine[i], 1.5 * (1.0 - k * 0.6), withAlpha(pal.main, 0.1f))
            }
        }
    }
}
