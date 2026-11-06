package dev.henny.hha.client.vfx

import dev.henny.hha.HhaBlocks
import dev.henny.hha.HhaSounds
import dev.henny.hha.logic.Vfx
import net.minecraft.client.MinecraftClient
import net.minecraft.client.sound.AbstractSoundInstance
import net.minecraft.client.sound.SoundInstance
import net.minecraft.client.world.ClientWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.util.math.random.Random
import net.minecraft.world.World
import java.util.Random as JavaRandom
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Macht aus den flachen Portalblöcken ein Tor, in das man hineinschaut: ein Tunnel aus Lichtringen, deren Perspektive
 * zur Kamera mitgerechnet wird, Spiralarme, die in die Mitte gesogen werden, Blitze am Rand und treibende Funken.
 *
 * Es gibt dafür keinen Block-Entity-Renderer: Die Blöcke melden sich selbst über randomDisplayTick, sobald ein Spieler
 * in der Nähe ist, und ein Tor verschwindet von hier, wenn sein mittlerer Block nicht mehr da ist.
 */
object PortalVfx {

    private class Hum(pos: Vec3d) : AbstractSoundInstance(HhaSounds.PORTAL_HUM, SoundCategory.BLOCKS, SoundInstance.createRandom()) {
        init {
            repeat = true
            repeatDelay = 0
            volume = 1.6f
            attenuationType = SoundInstance.AttenuationType.LINEAR
            x = pos.x
            y = pos.y
            z = pos.z
        }
    }

    private class Gate(val center: Vec3d, val block: BlockPos, val radius: Double) {
        var age = 0
        var hum: Hum? = null
    }

    private val gates = ArrayList<Gate>()

    private val PAL = Palette.of(Vfx.Style.HADES)
    private val X = Vec3d(1.0, 0.0, 0.0)
    private val Y = Vec3d(0.0, 1.0, 0.0)
    private val Z = Vec3d(0.0, 0.0, 1.0)
    private const val RINGS = 9
    private const val ARMS = 5
    private const val MOTES = 34

    /** Vom Block aufgerufen. Findet die Ausdehnung des Tors in seiner Ebene und merkt es sich. */
    fun note(world: World, pos: BlockPos) {
        if (gates.any { abs(it.block.z - pos.z) == 0 && abs(it.block.x - pos.x) <= 8 && abs(it.block.y - pos.y) <= 8 }) return
        var minX = pos.x
        var maxX = pos.x
        var minY = pos.y
        var maxY = pos.y
        // Spalte und Zeile des gemeldeten Blocks abgehen, dann über die breiteste Stelle die volle Größe bestimmen.
        while (pos.y - minY < 12 && world.getBlockState(BlockPos(pos.x, minY - 1, pos.z)).isOf(HhaBlocks.HADES_PORTAL)) minY--
        while (maxY - pos.y < 12 && world.getBlockState(BlockPos(pos.x, maxY + 1, pos.z)).isOf(HhaBlocks.HADES_PORTAL)) maxY++
        val middleY = (minY + maxY) / 2
        while (pos.x - minX < 12 && world.getBlockState(BlockPos(minX - 1, middleY, pos.z)).isOf(HhaBlocks.HADES_PORTAL)) minX--
        while (maxX - pos.x < 12 && world.getBlockState(BlockPos(maxX + 1, middleY, pos.z)).isOf(HhaBlocks.HADES_PORTAL)) maxX++
        val centerX = (minX + maxX) / 2
        while (world.getBlockState(BlockPos(centerX, minY - 1, pos.z)).isOf(HhaBlocks.HADES_PORTAL)) minY--
        while (world.getBlockState(BlockPos(centerX, maxY + 1, pos.z)).isOf(HhaBlocks.HADES_PORTAL)) maxY++
        val block = BlockPos(centerX, (minY + maxY) / 2, pos.z)
        val radius = ((maxX - minX + 1).coerceAtLeast(maxY - minY + 1)) / 2.0
        gates.add(Gate(Vec3d(centerX + 0.5, (minY + maxY + 1) / 2.0, pos.z + 0.5), block, radius))
    }

    fun tick(world: ClientWorld) {
        val client = MinecraftClient.getInstance()
        val player = client.player
        val iterator = gates.iterator()
        while (iterator.hasNext()) {
            val gate = iterator.next()
            gate.age++
            val far = player == null || player.squaredDistanceTo(gate.center) > 110.0 * 110.0
            val gone = world.isChunkLoaded(gate.block) && !world.getBlockState(gate.block).isOf(HhaBlocks.HADES_PORTAL)
            if (far || gone) {
                gate.hum?.let { client.soundManager.stop(it) }
                iterator.remove()
                continue
            }
            val hum = gate.hum
            if (hum == null || !client.soundManager.isPlaying(hum)) {
                if (player != null && player.squaredDistanceTo(gate.center) < 34.0 * 34.0) {
                    gate.hum = Hum(gate.center).also { client.soundManager.play(it) }
                }
            }
        }
    }

    fun render(d: VfxDraw) {
        val client = MinecraftClient.getInstance()
        val world = client.world ?: return
        val time = (world.time % 100000L).toDouble() + d.tickDelta
        for (gate in gates) drawGate(d, gate, time)
    }

    private fun drawGate(d: VfxDraw, gate: Gate, t: Double) {
        val fade = ((gate.age + d.tickDelta) / 24f).coerceIn(0f, 1f)
        val r = gate.radius
        // Knapp vor die Portalblöcke auf der Seite der Kamera, sonst verdeckt sie der Blockkörper.
        val toward = d.cam.subtract(gate.center)
        val front = if (toward.z >= 0.0) 1.0 else -1.0
        val c = gate.center.add(Z.multiply(front * 0.55))
        val beat = (0.85 + 0.15 * sin(t * 0.13)).toFloat()

        // Licht fällt aus dem Tor auf Boden und Wand.
        d.billboard(VfxLayers.GLOW_ADD, c, r * 5.2, withAlpha(PAL.main, 0.3f * fade * beat))
        d.disc(VfxLayers.GLOW_ADD, c.add(0.0, -r + 0.08, front * 2.4), X, Z, r * 1.7, withAlpha(PAL.main, 0.5f * fade * beat))

        // Dunkle Tiefe in der Mitte: So wirkt der Tunnel lang statt flach.
        d.disc(VfxLayers.SMOKE_ALPHA, c, X, Y, r * 0.78, withAlpha(0xFF041008.toInt(), 0.85f * fade), t * 0.01)

        tunnel(d, c, r, t, fade)
        arms(d, c, r, t, fade, front)
        core(d, c, r, t, fade, beat)
        rim(d, c, r, t, fade, gate)
        motes(d, c, r, t, fade, front)
    }

    /** Lichtringe rasen aus der Tiefe heran. Größe und Versatz folgen der echten Perspektive zur Kamera. */
    private fun tunnel(d: VfxDraw, c: Vec3d, r: Double, t: Double, fade: Float) {
        val offset = d.cam.subtract(c)
        val distance = abs(offset.z).coerceAtLeast(1.5)
        val phase = (t * 0.03) % 1.0
        for (i in 0 until RINGS) {
            val depth = (i + 1.0 - phase) * 3.2
            val scale = distance / (distance + depth)
            val shift = depth / (distance + depth)
            val radius = r * 0.93 * scale
            if (radius < 0.12) continue
            // Der Versatz bleibt im Tor: Ringe dürfen nicht über den Rahmen hinauslaufen.
            var ox = offset.x * shift
            var oy = offset.y * shift
            val room = r * 0.93 - radius
            val length = sqrt(ox * ox + oy * oy)
            if (length > room && length > 0.0) {
                ox *= room / length
                oy *= room / length
            }
            val center = c.add(ox, oy, 0.0)
            val deep = 1f - i.toFloat() / RINGS
            val alpha = fade * (0.28f + deep * 0.55f)
            val width = 0.05 + radius * 0.035
            d.ring(VfxLayers.ENERGY, center, X, Y, radius - width, radius + width, withAlpha(PAL.core, alpha), withAlpha(PAL.main, alpha * 0.8f), 40, 2f, (t * 0.02).toFloat())
        }
    }

    /** Spiralarme, die sich zur Mitte hin eindrehen. */
    private fun arms(d: VfxDraw, c: Vec3d, r: Double, t: Double, fade: Float, front: Double) {
        for (arm in 0 until ARMS) {
            val start = arm * PI * 2.0 / ARMS + t * 0.045
            val path = List(22) { i ->
                val k = i / 21.0
                val radius = r * 0.95 * (1.0 - k).let { it * it * 0.4 + it * 0.6 }
                val angle = start + k * 4.4
                c.add(cos(angle) * radius, sin(angle) * radius, front * 0.05)
            }
            d.ribbon(VfxLayers.ENERGY, path, { k -> 0.03 + (1.0 - k) * 0.11 }, { k -> withAlpha(PAL.main, fade * (0.2f + k * 0.65f)) }, 0.25f, (t * -0.05).toFloat())
        }
    }

    private fun core(d: VfxDraw, c: Vec3d, r: Double, t: Double, fade: Float, beat: Float) {
        d.billboard(VfxLayers.GLOW_ADD, c, r * 1.25, withAlpha(PAL.core, 0.55f * fade * beat))
        d.billboard(VfxLayers.FLARE_ADD, c, r * 1.5 * beat, withAlpha(PAL.core, 0.7f * fade), t * 0.02)
        d.billboard(VfxLayers.FLARE_ADD, c, r * 0.9, withAlpha(PAL.alt, 0.55f * fade), -t * 0.035)
    }

    /** Glühender Rand, an dem alle paar Ticks neue Blitze entlangspringen. */
    private fun rim(d: VfxDraw, c: Vec3d, r: Double, t: Double, fade: Float, gate: Gate) {
        d.ring(VfxLayers.GLOW_ADD, c, X, Y, r * 0.82, r * 1.1, withAlpha(PAL.main, 0f), withAlpha(PAL.main, 0.65f * fade), 48)
        d.ring(VfxLayers.ENERGY, c, X, Y, r * 0.93, r * 0.99, withAlpha(PAL.core, fade), withAlpha(PAL.main, fade), 48, 3f, (t * 0.04).toFloat())
        val random = JavaRandom(floor(t / 3.0).toLong() * 53L + gate.block.asLong())
        repeat(3) {
            val from = random.nextDouble() * PI * 2.0
            val span = 0.5 + random.nextDouble() * 0.9
            val path = List(9) { i ->
                val a = from + span * i / 8.0
                val jitter = if (i == 0 || i == 8) 0.0 else (random.nextDouble() - 0.5) * 0.5
                val radius = r * 0.96 + jitter
                c.add(cos(a) * radius, sin(a) * radius, 0.05)
            }
            d.ribbon(VfxLayers.GLOW_ADD, path, { 0.14 }, { withAlpha(PAL.main, 0.6f * fade) }, 0f, 0.5f)
            d.ribbon(VfxLayers.ENERGY, path, { 0.035 }, { withAlpha(PAL.core, fade) }, 0.4f)
        }
    }

    /** Funken treiben von außen heran und verschwinden im Sog. */
    private fun motes(d: VfxDraw, c: Vec3d, r: Double, t: Double, fade: Float, front: Double) {
        for (i in 0 until MOTES) {
            val seed = i * 2.399963
            val progress = (t * 0.008 + i.toDouble() / MOTES) % 1.0
            val pull = progress * progress
            val radius = (1.0 - pull) * (r + 3.2 + sin(seed) * 1.6)
            val angle = seed + pull * 5.0
            val out = (1.0 - pull) * (2.6 + cos(seed * 1.7) * 1.4) * front
            val at = c.add(cos(angle) * radius, sin(angle) * radius, out)
            val alpha = min(progress * 5.0, 1.0).toFloat() * (1f - pull.toFloat() * 0.3f) * fade
            d.billboard(VfxLayers.GLOW_ADD, at, 0.2 + (i % 3) * 0.06, withAlpha(if (i % 4 == 0) PAL.alt else PAL.core, alpha))
        }
    }
}
