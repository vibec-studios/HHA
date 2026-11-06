package dev.henny.hha.client.vfx

import dev.henny.hha.HhaSounds
import dev.henny.hha.logic.Vfx
import net.minecraft.client.MinecraftClient
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Eigene 3D-Wolken: Jede Wolkenmasse besteht aus vielen weichen Bäuschen an eigenen Raumpositionen. Sie überlagern
 * sich, verschieben sich beim Bewegen der Kamera gegeneinander und werden von hinten nach vorn gezeichnet; dadurch
 * entsteht echtes Volumen. Oben hell, unten dunkel, und Blitze hellen die Bäusche in ihrer Nähe auf.
 */
private class Puff(
    val angle: Double, val radius: Double, val y: Double, val size: Double,
    val rotation: Double, val phase: Double, val height: Float, val norm: Float,
)

private class Flash(val pos: Vec3d, val reach: Double, val life: Int) {
    var age = 0
    val strength: Float get() = (1f - age.toFloat() / life).coerceAtLeast(0f)
}

/** Baut eine Wolkenschicht als Ring: viele Massen, um die sich jeweils mehrere Bäusche gruppieren. */
private fun buildBank(
    seed: Long, masses: Int, puffsPerMass: Int, inner: Double, outer: Double,
    yLow: Double, yHigh: Double, sizeLow: Double, sizeHigh: Double,
): List<Puff> {
    val rnd = Random(seed)
    val out = ArrayList<Puff>(masses * puffsPerMass)
    repeat(masses) {
        val massAngle = rnd.nextDouble() * PI * 2.0
        // Gleichmäßig über die Fläche verteilt statt zur Mitte gehäuft.
        val massRadius = sqrt(inner * inner + rnd.nextDouble() * (outer * outer - inner * inner))
        val massY = yLow + rnd.nextDouble() * (yHigh - yLow)
        repeat(puffsPerMass) {
            val spread = sizeHigh * 1.7
            val dx = (rnd.nextDouble() - 0.5) * spread
            val dz = (rnd.nextDouble() - 0.5) * spread
            val up = rnd.nextDouble()
            val x = cos(massAngle) * massRadius + dx
            val z = sin(massAngle) * massRadius + dz
            val r = sqrt(x * x + z * z)
            out.add(
                Puff(
                    Math.atan2(z, x), r, massY + (up - 0.35) * sizeHigh * 0.75,
                    sizeLow + rnd.nextDouble() * (sizeHigh - sizeLow), rnd.nextDouble() * PI * 2.0, rnd.nextDouble() * PI * 2.0,
                    up.toFloat(), (((r - inner) / (outer - inner)).toFloat()).coerceIn(0f, 1f),
                ),
            )
        }
    }
    return out
}

/** Zeichnet Bäusche von hinten nach vorn; [alphaOf] kann einzelne Bäusche ausblenden (z. B. fürs Heranziehen). */
private fun drawPuffs(
    d: VfxDraw, puffs: List<Puff>, center: Vec3d, time: Float, swirl: Double,
    top: Int, bottom: Int, flashes: List<Flash>, alphaOf: (Puff) -> Float,
) {
    class Item(val puff: Puff, val pos: Vec3d, val distance: Double, val alpha: Float)

    val items = ArrayList<Item>(puffs.size)
    for (p in puffs) {
        val alpha = alphaOf(p)
        if (alpha <= 0.01f) continue
        val a = p.angle + time * swirl / (p.radius + 25.0)
        val pos = center.add(cos(a) * p.radius, p.y + sin(time * 0.012 + p.phase) * 0.6, sin(a) * p.radius)
        items.add(Item(p, pos, pos.squaredDistanceTo(d.cam), alpha))
    }
    items.sortByDescending { it.distance }

    val vc = d.buffer(VfxLayers.CLOUD_ALPHA)
    with(d) {
        for (item in items) {
            val p = item.puff
            var color = lerpColor(bottom, top, p.height)
            for (flash in flashes) {
                val near = (1.0 - item.pos.distanceTo(flash.pos) / flash.reach).coerceAtLeast(0.0).toFloat()
                if (near > 0f) color = lerpColor(color, 0xFFF2F4FF.toInt(), near * flash.strength)
            }
            val rotation = p.rotation + time * 0.0015
            val c = cos(rotation) * p.size
            val s = sin(rotation) * p.size
            val ax = camRight.multiply(c).add(camUp.multiply(s))
            val ay = camRight.multiply(-s).add(camUp.multiply(c))
            val col = withAlpha(color, item.alpha)
            vc.put(item.pos.subtract(ax).subtract(ay), 0f, 1f, col)
            vc.put(item.pos.add(ax).subtract(ay), 1f, 1f, col)
            vc.put(item.pos.add(ax).add(ay), 1f, 0f, col)
            vc.put(item.pos.subtract(ax).add(ay), 0f, 0f, col)
        }
    }
}

private fun smooth(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }

/** Wetterleuchten: gezackter Blitz zwischen zwei Punkten in der Wolkendecke. */
private class CloudArc(val path: List<Vec3d>, val life: Int) {
    var age = 0
}

private fun jaggedPath(rnd: Random, from: Vec3d, to: Vec3d): List<Vec3d> {
    val spread = from.distanceTo(to) * 0.14
    return List(10) { i ->
        val base = from.lerp(to, i / 9.0)
        if (i == 0 || i == 9) base
        else base.add((rnd.nextDouble() - 0.5) * spread, (rnd.nextDouble() - 0.5) * spread * 0.6, (rnd.nextDouble() - 0.5) * spread)
    }
}

// -------------------------------------------------------------------------------------------------
// Wolken der Bossarena
// -------------------------------------------------------------------------------------------------

/**
 * Die Wolken rund um das Himmelsforum: ein Meer darunter, eine Decke darüber und Nebelwände am Rand. Je Stage
 * ändern sich Farbe und Bedeckung; bei Zeus zieht die Decke vom Horizont her über das Forum und schließt sich.
 */
object ArenaClouds {

    private class Look(
        val seaTop: Int, val seaBottom: Int, val upperTop: Int, val upperBottom: Int,
        val coverage: Float, val rim: Int, val rimAlpha: Float, val storm: Float,
    )

    private val LOOKS = mapOf(
        Vfx.Style.GOLD to Look(0xFFFFFFFF.toInt(), 0xFFC2D0E6.toInt(), 0xFFFFFFFF.toInt(), 0xFFD6DCE8.toInt(), 0.3f, 0xFFFFFFFF.toInt(), 0f, 0f),
        Vfx.Style.HEAVEN to Look(0xFFFFCCA4.toInt(), 0xFF8C4C7A.toInt(), 0xFFFFB48C.toInt(), 0xFF5A3A78.toInt(), 0.42f, 0xFFFFB48C.toInt(), 0.12f, 0f),
        Vfx.Style.SOUL to Look(0xFF1E4A58.toInt(), 0xFF04101A.toInt(), 0xFF173440.toInt(), 0xFF030A10.toInt(), 0.65f, 0xFF2A8C9C.toInt(), 0.7f, 0f),
        Vfx.Style.STORM to Look(0xFF565664.toInt(), 0xFF14141C.toInt(), 0xFF6E6E80.toInt(), 0xFF101018.toInt(), 1f, 0xFF3A3A48.toInt(), 0.35f, 1f),
    )

    private var active = false
    private var center = Vec3d.ZERO
    private var lookFrom = LOOKS.getValue(Vfx.Style.GOLD)
    private var lookTo = lookFrom
    private var blend = 1f
    private var speed = 0.02f
    private var strength = 0f
    private var clock = 0
    private val rnd = Random()

    private var sea: List<Puff> = emptyList()
    private var upper: List<Puff> = emptyList()
    private var rim: List<Puff> = emptyList()
    private val flashes = ArrayList<Flash>()
    private val arcs = ArrayList<CloudArc>()
    private var nextFlash = 0

    private fun lerpLook(a: Look, b: Look, t: Float) = Look(
        lerpColor(a.seaTop, b.seaTop, t), lerpColor(a.seaBottom, b.seaBottom, t),
        lerpColor(a.upperTop, b.upperTop, t), lerpColor(a.upperBottom, b.upperBottom, t),
        a.coverage + (b.coverage - a.coverage) * t, lerpColor(a.rim, b.rim, t),
        a.rimAlpha + (b.rimAlpha - a.rimAlpha) * t, a.storm + (b.storm - a.storm) * t,
    )

    private fun current(): Look = lerpLook(lookFrom, lookTo, smooth(blend))

    fun set(style: Vfx.Style, pos: Vec3d, ticks: Int) {
        val next = LOOKS[style] ?: return
        if (sea.isEmpty()) {
            sea = buildBank(7L, 22, 8, 24.0, 190.0, -28.0, -14.0, 11.0, 22.0)
            upper = buildBank(21L, 28, 8, 22.0, 170.0, 44.0, 62.0, 10.0, 21.0)
            rim = buildBank(33L, 14, 6, 34.0, 80.0, -6.0, 8.0, 7.0, 14.0)
        }
        lookFrom = if (strength > 0f) current() else next
        lookTo = next
        blend = if (strength > 0f) 0f else 1f
        speed = 1f / ticks.coerceAtLeast(1)
        center = pos
        active = true
    }

    fun clear() {
        active = false
    }

    fun reset() {
        active = false
        strength = 0f
        flashes.clear()
        arcs.clear()
    }

    fun tick(world: ClientWorld?) {
        strength = (strength + if (active) 0.04f else -0.1f).coerceIn(0f, 1f)
        blend = (blend + speed).coerceAtMost(1f)
        clock++
        flashes.removeAll { ++it.age >= it.life }
        arcs.removeAll { ++it.age >= it.life }
        if (world != null && strength > 0.5f && current().storm > 0.7f && --nextFlash <= 0) {
            nextFlash = 14 + rnd.nextInt(50)
            lightUp(world)
        }
    }

    /** Wetterleuchten: Ein Teil der Decke flammt auf, oft zuckt ein Blitz durch die Wolken. */
    private fun lightUp(world: ClientWorld) {
        val pick = upper[rnd.nextInt(upper.size)]
        val a = rnd.nextDouble() * PI * 2.0
        val pos = center.add(cos(a) * pick.radius, pick.y, sin(a) * pick.radius)
        flashes.add(Flash(pos, 34.0 + rnd.nextDouble() * 30.0, 5 + rnd.nextInt(5)))
        if (rnd.nextFloat() < 0.65f) {
            val b = a + (rnd.nextDouble() - 0.5) * 1.1
            val r = (pick.radius + (rnd.nextDouble() - 0.5) * 50.0).coerceAtLeast(16.0)
            arcs.add(CloudArc(jaggedPath(rnd, pos, center.add(cos(b) * r, pick.y - 4.0, sin(b) * r)), 5))
        }
        world.setLightningTicksLeft(2)
        if (rnd.nextFloat() < 0.5f) {
            MinecraftClient.getInstance().soundManager.play(
                PositionedSoundInstance.ambient(HhaSounds.THUNDER, 0.6f + rnd.nextFloat() * 0.3f, 0.35f + rnd.nextFloat() * 0.35f),
            )
        }
    }

    fun render(d: VfxDraw) {
        if (strength <= 0f || sea.isEmpty()) return
        val look = current()
        val time = clock + d.tickDelta
        val a = strength * 0.92f
        drawPuffs(d, sea, center, time, 1.4, look.seaTop, look.seaBottom, flashes) { a }
        drawPuffs(d, rim, center, time, -2.2, look.rim, look.rim, flashes) { a * look.rimAlpha }
        // Die Decke schließt sich vom Horizont nach innen, je höher die Bedeckung.
        val edge = 1f - look.coverage
        drawPuffs(d, upper, center, time, 3.0 + look.storm * 7.0, look.upperTop, look.upperBottom, flashes) { puff ->
            a * smooth((puff.norm - edge) * 7f)
        }
        for (arc in arcs) {
            val fade = 1f - arc.age.toFloat() / arc.life
            d.ribbon(VfxLayers.GLOW_ADD, arc.path, { 0.9 }, { withAlpha(0xFFBFD4FF.toInt(), 0.8f * fade) })
            d.ribbon(VfxLayers.GLOW_ADD, arc.path, { 0.28 }, { withAlpha(0xFFFFFFFF.toInt(), fade) })
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Einzelne Wolkenbank (Prolog, Ultra …)
// -------------------------------------------------------------------------------------------------

/** Wolkenbank über einem Ort oder Wesen, die heranzieht, eine Weile bleibt und sich wieder auflöst. */
class CloudBankEffect(
    private val center: Vec3d, style: Vfx.Style, radius: Float, height: Float, life: Int,
    private val follow: Entity?, seed: Long,
) : VfxEffect(life) {

    private val colors = when (style) {
        Vfx.Style.FIRE -> 0xFF8A3014.toInt() to 0xFF1E0A06.toInt()
        Vfx.Style.GOLD -> 0xFFFFF4D8.toInt() to 0xFFC9A46A.toInt()
        Vfx.Style.HEAVEN -> 0xFFFFD8B0.toInt() to 0xFF7A5A8C.toInt()
        Vfx.Style.SOUL, Vfx.Style.HELL -> 0xFF1E5A68.toInt() to 0xFF04141C.toInt()
        else -> 0xFF62626F.toInt() to 0xFF121218.toInt()
    }
    private val stormy = style == Vfx.Style.STORM
    private val rnd = Random(seed)
    private val puffs = buildBank(
        seed, (radius * 0.45f).toInt().coerceIn(10, 22), 7, radius * 0.25, radius.toDouble(),
        height.toDouble(), height + radius * 0.22, radius * 0.1, radius * 0.24,
    )
    private val flashes = ArrayList<Flash>()
    private val arcs = ArrayList<CloudArc>()
    private var nextFlash = 10

    private fun origin(tickDelta: Float): Vec3d = follow?.takeIf { it.isAlive }?.getLerpedPos(tickDelta) ?: center

    override fun tick(world: ClientWorld) {
        flashes.removeAll { ++it.age >= it.life }
        arcs.removeAll { ++it.age >= it.life }
        if (stormy && age > 30 && --nextFlash <= 0) {
            nextFlash = 10 + rnd.nextInt(35)
            val p = puffs[rnd.nextInt(puffs.size)]
            val a = rnd.nextDouble() * PI * 2.0
            val base = origin(1f)
            val pos = base.add(cos(a) * p.radius, p.y, sin(a) * p.radius)
            flashes.add(Flash(pos, 26.0, 6))
            arcs.add(CloudArc(jaggedPath(rnd, pos, base.add(cos(a + 0.6) * p.radius, p.y - 3.0, sin(a + 0.6) * p.radius)), 4))
        }
    }

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val alpha = 0.9f * ((1f - t) * life / 40f).coerceAtMost(1f)
        val coverage = smooth(time / 50f)
        drawPuffs(d, puffs, origin(d.tickDelta), time, 5.0, colors.first, colors.second, flashes) { puff ->
            alpha * smooth((puff.norm - (1f - coverage)) * 7f + 0.05f)
        }
        for (arc in arcs) {
            val fade = 1f - arc.age.toFloat() / arc.life
            d.ribbon(VfxLayers.GLOW_ADD, arc.path, { 0.5 }, { withAlpha(0xFFBFD4FF.toInt(), 0.8f * fade) })
        }
    }
}

/** Lichtstrahlen, die schräg aus den Wolken auf einen Ort fallen. */
class GodRaysEffect(
    private val center: Vec3d, private val pal: Palette, private val radius: Float,
    private val height: Float, life: Int, seed: Long,
) : VfxEffect(life) {

    private class Ray(val angle: Double, val dist: Double, val width: Double, val phase: Double)

    private val rays = Random(seed).let { rnd ->
        List(9) { Ray(rnd.nextDouble() * PI * 2.0, rnd.nextDouble() * radius, 1.5 + rnd.nextDouble() * radius * 0.16, rnd.nextDouble() * 6.28) }
    }

    override fun render(d: VfxDraw, time: Float) {
        val alpha = envelope(progress(time), 0.2f, 0.3f)
        val slant = Vec3d(0.32, 1.0, 0.18).normalize().multiply(height.toDouble())
        for (ray in rays) {
            val base = center.add(cos(ray.angle) * ray.dist, 0.1, sin(ray.angle) * ray.dist)
            val shimmer = 0.75f + 0.25f * sin(time * 0.05f + ray.phase.toFloat())
            d.ribbon(
                VfxLayers.ENERGY, listOf(base, base.add(slant.multiply(0.5)), base.add(slant)),
                { ray.width * (0.6 + it) }, { withAlpha(pal.main, alpha * 0.34f * shimmer * (1f - it * 0.5f)) },
                uScale = 0.02f, uOffset = ray.phase.toFloat(),
            )
        }
    }
}

/**
 * Zeus sieht hin: zwei riesige, gleißende Augen öffnen sich zwischen den Wolken, umrahmt von einer Andeutung aus
 * Stirn und Bart. Sie zeigen immer zur Kamera, schauen also jeden Spieler an, egal wo er steht.
 */
class ZeusEyesEffect(
    center: Vec3d, private val pal: Palette, height: Float, life: Int, seed: Long,
) : VfxEffect(life) {

    private val anchor = center.add(0.0, height.toDouble(), 0.0)

    /** Wolkenbausch im Gesicht: x/y relativ zu den Augen, Größe, Dunkelheit. */
    private class Puff(val x: Double, val y: Double, val size: Double, val dark: Float, val phase: Double)

    private val puffs = Random(seed).let { rnd ->
        val out = ArrayList<Puff>()
        // Zornige Stirn: zwei Wülste, die zur Mitte hin abfallen.
        for (i in 0 until 16) {
            val k = i / 15.0 * 2.0 - 1.0
            out.add(Puff(k * 34.0, 8.0 + kotlin.math.abs(k) * 7.0 + rnd.nextDouble() * 2.0, 15.0 + rnd.nextDouble() * 7.0, 0.1f, rnd.nextDouble() * 6.0))
        }
        // Bart: fällt in Strähnen unter die Augen und wird nach unten heller.
        for (i in 0 until 26) {
            val k = rnd.nextDouble() * 2.0 - 1.0
            val down = rnd.nextDouble()
            out.add(Puff(k * (30.0 - down * 18.0), -13.0 - down * 34.0, 13.0 + rnd.nextDouble() * 8.0, 0.22f + down.toFloat() * 0.4f, rnd.nextDouble() * 6.0))
        }
        out
    }

    private fun eyelid(t: Float): Float {
        val open = easeOut((t / 0.14f).coerceIn(0f, 1f))
        val close = easeInOut(((t - 0.9f) / 0.1f).coerceIn(0f, 1f))
        // Ein kurzes Zusammenkneifen in der Mitte: Er ist nicht erfreut.
        val squint = 1f - 0.28f * (sin((t - 0.42f).coerceIn(0f, 0.2f) / 0.2f * PI.toFloat()))
        return open * (1f - close) * squint
    }

    override fun render(d: VfxDraw, time: Float) {
        val t = progress(time)
        val lid = eyelid(t)
        val fade = envelope(t, 0.1f, 0.08f)
        val right = d.camRight
        val up = d.camUp

        // Das Gesicht aus Wolken, von den Augen von unten beleuchtet.
        for (puff in puffs) {
            val at = anchor.add(right.multiply(puff.x + sin(time * 0.02 + puff.phase) * 1.2)).add(up.multiply(puff.y))
            val value = (puff.dark * 255f).toInt().coerceIn(0, 255)
            val color = (0xFF shl 24) or (value shl 16) or (value shl 8) or ((value * 1.25f).toInt().coerceAtMost(255))
            d.billboard(VfxLayers.CLOUD_ALPHA, at, puff.size, withAlpha(color, 0.72f * fade), puff.phase)
        }

        // Schein hinter den Augen.
        val pulse = 0.82f + 0.18f * sin(time * 0.31f)
        d.billboard(VfxLayers.SKY_GLOW, anchor, 120.0, withAlpha(pal.main, 0.3f * fade * pulse))

        for (side in intArrayOf(-1, 1)) {
            val eye = anchor.add(right.multiply(side * 18.0))
            d.billboard(VfxLayers.SKY_GLOW, eye, 54.0, withAlpha(pal.main, 0.62f * lid * pulse))
            d.billboard(VfxLayers.SKY_GLOW, eye, 24.0, withAlpha(pal.core, 0.8f * lid))
            // Das Auge selbst: mandelförmig, außen nach oben gezogen, damit es zornig wirkt.
            val tilt = side * 0.24
            val axisX = right.multiply(cos(tilt) * side * 15.0).add(up.multiply(sin(tilt) * side * 15.0))
            val axisY = right.multiply(-sin(tilt) * 7.5 * lid).add(up.multiply(cos(tilt) * 7.5 * lid))
            val vc = d.buffer(VfxLayers.SKY_EYE)
            val color = withAlpha(pal.core, lid.coerceAtLeast(0f))
            with(d) {
                vc.put(eye.subtract(axisX).subtract(axisY), 0f, 1f, color)
                vc.put(eye.add(axisX).subtract(axisY), 1f, 1f, color)
                vc.put(eye.add(axisX).add(axisY), 1f, 0f, color)
                vc.put(eye.subtract(axisX).add(axisY), 0f, 0f, color)
            }
            d.billboard(VfxLayers.SKY_FLARE, eye, 30.0 * lid, withAlpha(pal.core, 0.85f * lid), time * 0.01 * side)
            d.billboard(VfxLayers.SKY_FLARE, eye, 18.0 * lid, withAlpha(pal.alt, 0.5f * lid), -time * 0.02 * side)
        }

        // Entladungen laufen über das Gesicht und wechseln alle paar Ticks.
        val rnd = Random(kotlin.math.floor(time / 3f).toLong())
        repeat(3) {
            val side = if (rnd.nextBoolean()) 1 else -1
            val from = anchor.add(right.multiply(side * 18.0))
            val to = anchor.add(right.multiply(side * (10.0 + rnd.nextDouble() * 36.0))).add(up.multiply(-10.0 - rnd.nextDouble() * 34.0))
            val path = List(8) { i ->
                val k = i / 7.0
                val jitter = if (i == 0 || i == 7) 0.0 else 3.2
                from.lerp(to, k).add(right.multiply((rnd.nextDouble() - 0.5) * jitter * 2.0)).add(up.multiply((rnd.nextDouble() - 0.5) * jitter * 2.0))
            }
            d.ribbon(VfxLayers.SKY_GLOW, path, { 1.5 }, { withAlpha(pal.main, 0.55f * lid) }, 0f, 0.5f)
            d.ribbon(VfxLayers.ENERGY, path, { 0.32 }, { withAlpha(pal.core, lid) }, 0.1f)
        }
    }
}
