package dev.henny.hha.client.vfx

import dev.henny.hha.HhaParticles
import dev.henny.hha.entity.boss.HermesPose
import dev.henny.hha.entity.miniboss.KampePose
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossTimings
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dauerlicht an den Boss-Modellen. Die Renderer lesen Ankerpunkte direkt aus den Knochen und reichen sie pro Bild
 * hierher; gezeichnet wird danach im normalen Effekt-Durchlauf mit denselben Shadern wie alle 3D-Effekte.
 *
 * **Budget (Plan 3.3/3.4): höchstens drei Dauer-Elemente je Boss**, der Rest lebt in der Leuchtmaske der Textur:
 * - Hermes: Kugel des Stabs, Augen, Halo nur in Stage IV; dazu die Spur nur während Stoß/Sturzflug und die goldene
 *   Beute-Kugel nur, solange er sie hält.
 * - Hades: Brustlaterne, Bident-Spitzen, Augen.
 * - Kronos: Stundenglas im Leib, Schneide der Harpe, Augen (Äon-Indigo, kein Bernstein).
 * - Kampe: Augen, Stachel (golden, solange er steckt), Maul nur beim Speien.
 * Gold heißt auch hier „gut für dich“: Sanduhren, steckender Stachel, Beute-Kugel.
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

        /** Hält er die Beute-Kugel (Diebesgriff)? Dann ist [lootOrb] ihr Mittelpunkt (Knochen `loot_orb`). */
        var loot = false
        var lootOrb = Vec3d.ZERO
    }

    class HadesFrame(
        val id: Int, val stage: Int, val cloaked: Boolean, val time: Float, val form: Int = 0, val fallen: Boolean = false,
        val bidentOut: Boolean = false, val staggered: Boolean = false,
    ) {
        var hem = Vec3d.ZERO
        var chest = Vec3d.ZERO
        var eyeR = Vec3d.ZERO
        var eyeL = Vec3d.ZERO
        var crown = Vec3d.ZERO
        var tipA = Vec3d.ZERO
        var tipB = Vec3d.ZERO
        var fork = Vec3d.ZERO
        var handL = Vec3d.ZERO
        var handR = Vec3d.ZERO

        /** Aktuelle Pose ([dev.henny.hha.entity.boss.HadesPose]), z. B. für das hochgeschobene Visier im Blick. */
        var pose = 0
    }

    class SerpentFrame(val id: Int, val variant: Int, val biting: Boolean, val time: Float) {
        /** Schlange am Stab des Schattens: grünes statt himmelblaues Licht. */
        var corrupted = false
        var eyeR = Vec3d.ZERO
        var eyeL = Vec3d.ZERO
        var mouth = Vec3d.ZERO
        var spine: List<Vec3d> = emptyList()
    }

    /** Kronos: Augen, Glut in der Brust, der Uhrenkranz hinter dem Kopf und die Schneide der Harpe. */
    class KronosFrame(val id: Int, val time: Float, val form: Int, val pose: Int) {
        var chest = Vec3d.ZERO
        var eyeR = Vec3d.ZERO
        var eyeL = Vec3d.ZERO
        var halo = Vec3d.ZERO
        var haloUp = Vec3d(0.0, 0.1, 0.0)
        var haloSide = Vec3d(0.1, 0.0, 0.0)
        var blade: List<Vec3d> = emptyList()
        var mouth = Vec3d.ZERO
        var handL = Vec3d.ZERO

        /** Fliegt die Harpe? Dann gibt es an der Hand keine Schneide zu zeichnen. */
        var harpeOut = false
    }

    /**
     * Kampe: Stachel (Spitze und Ansatz), Maul, Augen und die Mitte des Leibs (Glied 5). Die Anker liest
     * `KampeModel.readAnchors`; gezeichnet wird höchstens, was Plan 3.4 erlaubt.
     */
    class KampeFrame(val id: Int, val time: Float, val pose: Int) {
        var stingTip = Vec3d.ZERO
        var stingBase = Vec3d.ZERO
        var mouth = Vec3d.ZERO
        var eyeR = Vec3d.ZERO
        var eyeL = Vec3d.ZERO
        var tailMid = Vec3d.ZERO

        /** Ticks seit Beginn der Pose (`poseTime`), falls der Renderer sie setzt; sonst -1. Zeigt „Stachel steckt“. */
        var poseTime = -1f
    }

    /** Höllenpforte oder Sanduhr: [health] dimmt das Licht, je näher sie dem Zerbrechen ist. */
    class CoreFrame(val pos: Vec3d, val kind: Int, val health: Float, val time: Float)

    /** Leuchtkugel eines Geschosses: Gift, Feuer oder Zeitsand. [velocity] zieht den Schweif nach. */
    class OrbFrame(val pos: Vec3d, val velocity: Vec3d, val kind: Int, val size: Float, val time: Float)

    private class Seen<T>(val frame: T) {
        var ticks = 0
    }

    private val hermesFrames = ArrayList<HermesFrame>()
    private val serpentFrames = ArrayList<SerpentFrame>()
    private val hadesFrames = ArrayList<HadesFrame>()
    private val orbFrames = ArrayList<OrbFrame>()
    private val kronosFrames = ArrayList<KronosFrame>()
    private val coreFrames = ArrayList<CoreFrame>()
    private val kampeFrames = ArrayList<KampeFrame>()
    private val lastHermes = HashMap<Int, Seen<HermesFrame>>()
    private val lastSerpents = HashMap<Int, Seen<SerpentFrame>>()
    private val trails = HashMap<Int, ArrayDeque<Vec3d>>()
    private val powers = HashMap<Int, Float>()

    private val STAGE_PALETTES = mapOf(
        1 to Palette.of(Vfx.Style.HEAVEN),
        2 to Palette(0xFFFFF0D8.toInt(), 0xFFE0A2FF.toInt(), 0xFF7A3FC0.toInt(), 0xFFFFD75A.toInt()),
        3 to Palette.of(Vfx.Style.HELL),
        4 to Palette.of(Vfx.Style.STORM),
    )

    private val SHADE_PALETTE = Palette.of(Vfx.Style.HADES)
    private val GOLD = Palette.of(Vfx.Style.GOLD)
    private val HADES = Palette.of(Vfx.Style.HADES)
    private val AEON = Palette.of(Vfx.Style.AEON)

    private val SNAKE_A = Palette(0xFFF4FBFF.toInt(), 0xFFDFF4FF.toInt(), 0xFF5F8FC0.toInt(), 0xFFFFFFFF.toInt())
    private val SNAKE_B = Palette(0xFFE2F4FF.toInt(), 0xFF62A3F2.toInt(), 0xFF28479E.toInt(), 0xFFB8E2FF.toInt())
    private val SHADE_SNAKE = Palette(0xFFE8FFE8.toInt(), 0xFF78FF8C.toInt(), 0xFF0E7A2C.toInt(), 0xFFB4FFC0.toInt())

    /** Wie stark Hermes gerade strahlt: in der Erschöpfung fast erloschen, beim Zaubern gleißend. */
    fun targetPower(stage: Int, vulnerable: Boolean, pose: Int, illusion: Boolean): Float {
        var power = when (pose) {
            HermesPose.CAST, HermesPose.ASCEND -> 1.45f
            HermesPose.SLAM, HermesPose.THROW -> 1.2f
            HermesPose.KNEEL, HermesPose.FALL -> 0.28f
            HermesPose.STAGGER -> 0.45f
            else -> 1f
        }
        // Stage IV: nur die Erschöpfung (KNEEL) dimmt. Der Schatten ist dort immer verwundbar und soll trotzdem glühen.
        if (stage == 4) power *= if (pose == HermesPose.KNEEL) 0.4f else 1.25f
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

    fun submit(frame: HadesFrame) {
        hadesFrames.add(frame)
    }

    fun submit(frame: OrbFrame) {
        orbFrames.add(frame)
    }

    fun submit(frame: KronosFrame) {
        kronosFrames.add(frame)
    }

    fun submit(frame: CoreFrame) {
        coreFrames.add(frame)
    }

    fun submit(frame: SerpentFrame) {
        serpentFrames.add(frame)
    }

    fun submit(frame: KampeFrame) {
        kampeFrames.add(frame)
    }

    fun clear() {
        hermesFrames.clear()
        serpentFrames.clear()
        hadesFrames.clear()
        orbFrames.clear()
        kronosFrames.clear()
        coreFrames.clear()
        kampeFrames.clear()
    }

    fun render(d: VfxDraw) {
        for (frame in hermesFrames) {
            drawHermes(d, frame)
            lastHermes[frame.id] = Seen(frame)
        }
        for (frame in hadesFrames) drawHades(d, frame)
        for (frame in orbFrames) drawOrbBolt(d, frame)
        for (frame in kronosFrames) drawKronos(d, frame)
        for (frame in coreFrames) drawCore(d, frame)
        for (frame in serpentFrames) {
            drawSerpent(d, frame)
            lastSerpents[frame.id] = Seen(frame)
        }
        for (frame in kampeFrames) drawKampe(d, frame)
        clear()
    }

    /**
     * Partikel lassen sich nur im Spieltakt sauber dosieren. Je Hermes höchstens zwei leise Emitter (Federn von den
     * Flügeln, Funken an der Kugel), je Schlange einer: weit unter Plan 3.3 (≤ 4 je Tick, ≤ 2 Emitter).
     */
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
            // Im Rauchschritt keine Federn oder Funken: Sie würden die echte Rauchsäule verraten.
            if (f.corrupted && f.pose == HermesPose.SMOKE) continue
            val rate = if (f.speed > 0.3f) 6 else 22
            if (random.nextInt(rate) == 0) {
                val wing = if (random.nextBoolean()) f.wingR else f.wingL
                val tip = if (wing === f.wingR) f.tipR else f.tipL
                val root = wing[1 + random.nextInt(wing.size - 1)]
                val at = root.lerp(tip, random.nextDouble())
                world.addParticleClient(HhaParticles.FEATHER, at.x, at.y, at.z, 0.0, 0.0, 0.0)
            }
            if (!f.corrupted && random.nextInt(4) == 0) {
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

    // ---------------------------------------------------------------------------------------------
    // Hermes: Kugel, Augen, Halo nur Stage IV (+ Spur im Stoß, Beute-Kugel solange er sie hält)
    // ---------------------------------------------------------------------------------------------

    private fun drawHermes(d: VfxDraw, f: HermesFrame) {
        val pal = if (f.corrupted) SHADE_PALETTE else STAGE_PALETTES[f.stage] ?: STAGE_PALETTES.getValue(1)
        val t = f.time.toDouble()
        val p = f.power * (0.88f + 0.12f * sin(f.time * 0.19f))
        // Rauchschritt des Schattens: In der Säule glimmen nur die Augen (der Renderer liefert erst ab SMOKE_EYES
        // überhaupt einen Frame). Kugel, Halo oder Spur würden die echte Säule verraten.
        val smoke = f.corrupted && f.pose == HermesPose.SMOKE

        if (!smoke) drawOrb(d, f, pal, p, t)
        for (eye in listOf(f.eyeR, f.eyeL)) {
            d.billboard(VfxLayers.GLOW_ADD, eye, 0.42, withAlpha(pal.main, 0.85f * p))
            d.billboard(VfxLayers.FLARE_ADD, eye, 0.34, withAlpha(pal.core, 0.9f * p), t * 0.03)
        }
        if (smoke) return
        if (f.stage == 4 && !f.corrupted) drawHalo(d, f, pal, p, t)
        if (!f.illusion) drawDashTrail(d, f, pal, p)
        if (f.loot) drawLoot(d, f, t)
    }

    /** Die Kugel des Caduceus: weiches Licht, heller Kern und ein schräger Lichtring. */
    private fun drawOrb(d: VfxDraw, f: HermesFrame, pal: Palette, p: Float, t: Double) {
        d.billboard(VfxLayers.GLOW_ADD, f.orb, 1.9, withAlpha(pal.main, 0.6f * p))
        d.billboard(VfxLayers.GLOW_ADD, f.orb, 0.9, withAlpha(pal.core, 0.95f * p))
        d.billboard(VfxLayers.FLARE_ADD, f.orb, 2.0, withAlpha(pal.core, 0.8f * p), t * 0.02)
        val axis = f.staffTop.subtract(f.staffBottom)
        val up = if (axis.lengthSquared() < 1e-6) Vec3d(0.0, 1.0, 0.0) else axis.normalize()
        val (a, b) = basisOf(up)
        val angle = t * 0.05
        val tiltA = a.multiply(cos(angle)).add(b.multiply(sin(angle)))
        val tiltB = up.multiply(0.55).add(a.multiply(-sin(angle)).add(b.multiply(cos(angle))).multiply(0.83))
        d.ring(VfxLayers.ENERGY, f.orb, tiltA, tiltB, 0.6, 0.67, withAlpha(pal.core, 0.9f * p), withAlpha(pal.main, 0.75f * p), 32)
    }

    private fun drawHalo(d: VfxDraw, f: HermesFrame, pal: Palette, p: Float, t: Double) {
        d.billboard(VfxLayers.GLOW_ADD, f.halo, 2.5, withAlpha(pal.main, 0.22f * p))
        d.disc(VfxLayers.RUNE_ADD, f.halo, f.haloRight, f.haloUp, 0.95, withAlpha(pal.main, 0.55f * p), t * 0.012)
        d.ring(VfxLayers.ENERGY, f.halo, f.haloRight, f.haloUp, 0.6, 0.67, withAlpha(pal.core, 0.9f * p), withAlpha(pal.main, 0.75f * p), 40)
    }

    /** Eine Lichtbahn hinter dem Körper, nur im Kurierstoß und im Sturzflug. Danach verglimmt sie. */
    private fun drawDashTrail(d: VfxDraw, f: HermesFrame, pal: Palette, p: Float) {
        val slot = trails.getOrPut(f.id) { ArrayDeque() }
        val dashing = f.pose == HermesPose.DASH || f.pose == HermesPose.DIVE
        if (dashing && f.speed > 0.25f) {
            if (slot.isEmpty() || slot.last().squaredDistanceTo(f.chest) > 0.09) slot.addLast(f.chest)
            while (slot.size > 24) slot.removeFirst()
        } else if (slot.isNotEmpty()) {
            slot.removeFirst()
        }
        if (slot.size >= 3) {
            val path = slot.toList() + f.chest
            d.ribbon(VfxLayers.GLOW_ADD, path, { k -> 0.42 * k }, { k -> withAlpha(pal.main, k * 0.6f * p) }, 0f, 0.5f)
            d.ribbon(VfxLayers.ENERGY, path, { k -> 0.1 * k }, { k -> withAlpha(pal.core, k * 0.9f * p) }, 0.2f)
        }
    }

    /** Die Beute-Kugel in seiner Linken: golden, denn wer ihn jetzt trifft, holt sich seine Effekte zurück. */
    private fun drawLoot(d: VfxDraw, f: HermesFrame, t: Double) {
        val pulse = 0.85 + 0.15 * sin(t * 0.3)
        d.billboard(VfxLayers.GLOW_ADD, f.lootOrb, 1.2 * pulse, withAlpha(GOLD.main, 0.7f))
        d.billboard(VfxLayers.FLARE_ADD, f.lootOrb, 0.8, withAlpha(GOLD.core, 0.9f), t * 0.04)
    }

    // ---------------------------------------------------------------------------------------------
    // Hades: Brustlaterne, Bident-Spitzen, Augen
    // ---------------------------------------------------------------------------------------------

    private fun drawHades(d: VfxDraw, f: HadesFrame) {
        val pal = HADES
        val t = f.time.toDouble()
        // Am Boden erlischt alles. Das gehört zum Köder.
        if (f.fallen) return
        for (eye in listOf(f.eyeR, f.eyeL)) {
            d.billboard(VfxLayers.GLOW_ADD, eye, 0.6, withAlpha(pal.main, 0.95f))
            d.billboard(VfxLayers.FLARE_ADD, eye, 0.5, withAlpha(pal.core, 0.95f), t * 0.03)
        }
        if (f.cloaked) return
        val grown = 1.0 + f.form * 0.2
        // Beim Taumeln flackert die Laterne: Das ist das Zeichen zum Zuschlagen.
        val weak = if (f.staggered) (0.55f + 0.45f * sin(t * 1.1).toFloat()) else 1f
        d.billboard(VfxLayers.GLOW_ADD, f.chest, 2.4 * grown, withAlpha(pal.main, 0.5f * weak))
        d.billboard(VfxLayers.FLARE_ADD, f.chest, 1.6 * grown, withAlpha(pal.core, 0.6f * weak), t * 0.012)
        // Fliegt der Bident, gibt es an der Hand nichts zu leuchten.
        if (f.bidentOut) return
        for (tip in listOf(f.tipA, f.tipB)) {
            d.billboard(VfxLayers.GLOW_ADD, tip, 0.9, withAlpha(pal.main, 0.75f))
            d.billboard(VfxLayers.FLARE_ADD, tip, 0.75, withAlpha(pal.core, 0.75f), t * 0.05)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Kronos: Stundenglas im Leib, Schneide, Augen (Äon-Indigo)
    // ---------------------------------------------------------------------------------------------

    private fun drawKronos(d: VfxDraw, f: KronosFrame) {
        val pal = AEON
        val t = f.time.toDouble()
        val power = if (f.form > 0) 1.3f else 1f
        val dead = f.pose == dev.henny.hha.entity.boss.KronosPose.BOUND
        val p = power * (if (dead) 0.45f else 1f) * (0.9f + 0.1f * sin(f.time * 0.13f))
        for (eye in listOf(f.eyeR, f.eyeL)) {
            d.billboard(VfxLayers.GLOW_ADD, eye, 0.9, withAlpha(pal.main, 0.9f * p))
            d.billboard(VfxLayers.FLARE_ADD, eye, 0.8, withAlpha(pal.core, 0.9f * p), t * 0.03)
        }
        // Der Sand im Stundenglas: ein ruhiges Pulsieren, in der zweiten Gestalt rinnt die Zeit schneller.
        val unit = f.haloUp.length().takeIf { it > 1e-4 } ?: 0.06
        val sand = 0.82f + 0.18f * sin(t * (if (f.form > 0) 0.5 else 0.22)).toFloat()
        d.billboard(VfxLayers.GLOW_ADD, f.chest, (unit * 16.0).coerceIn(0.6, 2.6), withAlpha(pal.main, 0.5f * p * sand))
        d.billboard(VfxLayers.FLARE_ADD, f.chest, (unit * 7.0).coerceIn(0.35, 1.3), withAlpha(pal.core, 0.85f * p * sand), t * 0.02)
        // Die Schneide der Harpe, solange sie in der Hand ist.
        if (!f.harpeOut && f.blade.size >= 2) {
            d.ribbon(VfxLayers.ENERGY, f.blade, { k -> 0.22 * (1.0 - k * 0.6) }, { k -> withAlpha(pal.core, (0.9f - 0.3f * k) * p) }, 0.35f, (-t * 0.06).toFloat())
            d.billboard(VfxLayers.GLOW_ADD, f.blade.last(), 1.1, withAlpha(pal.main, 0.55f * p))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Kampe: Augen, Stachel, Maul beim Speien
    // ---------------------------------------------------------------------------------------------

    private fun drawKampe(d: VfxDraw, f: KampeFrame) {
        val pal = HADES
        val t = f.time.toDouble()
        if (f.pose == KampePose.DEATH) return
        for (eye in listOf(f.eyeR, f.eyeL)) {
            d.billboard(VfxLayers.GLOW_ADD, eye, 0.5, withAlpha(pal.main, 0.9f))
            d.billboard(VfxLayers.FLARE_ADD, eye, 0.4, withAlpha(pal.core, 0.9f), t * 0.03)
        }
        // Der Stachel: grün, beim Ausholen heller. Steckt er im Boden (offen, Treffer × 1,5), glüht er golden.
        val stung = f.pose == KampePose.STING
        val stuckFrom = (BossTimings.Kampe.STING_WINDUP + BossTimings.Kampe.STING_STRIKE).toFloat()
        val stuck = stung && f.poseTime >= stuckFrom && f.poseTime <= BossTimings.Kampe.STING_STUCK_END
        val sting = if (stuck) GOLD else pal
        val strength = when {
            stuck -> 0.85f
            stung -> 0.95f
            else -> 0.45f
        }
        d.billboard(VfxLayers.GLOW_ADD, f.stingTip, if (stung) 1.3 else 0.8, withAlpha(sting.main, strength))
        d.billboard(VfxLayers.FLARE_ADD, f.stingTip, if (stung) 0.9 else 0.5, withAlpha(sting.core, strength), t * 0.05)
        if (f.pose == KampePose.SPIT) {
            d.billboard(VfxLayers.GLOW_ADD, f.mouth, 1.3, withAlpha(pal.main, 0.7f))
            d.billboard(VfxLayers.FLARE_ADD, f.mouth, 0.9, withAlpha(pal.core, 0.7f), t * 0.07)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Requisiten
    // ---------------------------------------------------------------------------------------------

    private fun drawCore(d: VfxDraw, f: CoreFrame) {
        val t = f.time.toDouble()
        val center = f.pos.add(0.0, 1.3, 0.0)
        val life = 0.45f + 0.55f * f.health
        if (f.kind == dev.henny.hha.entity.boss.CoreEntity.HOURGLASS) {
            // Die Sanduhren der Rückspulung sind ein lohnendes Ziel: Gold (Plan 3.1).
            val pal = GOLD
            val up = Vec3d(0.0, 1.0, 0.0)
            val a = Vec3d(1.0, 0.0, 0.0)
            val b = Vec3d(0.0, 0.0, 1.0)
            // Zwei gläserne Kegel, Spitze an Spitze, und dazwischen rinnt der Sand.
            d.wall(VfxLayers.FLOW_ADD, center.add(0.0, -1.2, 0.0), a, b, up, 0.95, 0.08, 1.2, withAlpha(pal.main, 0.55f * life), withAlpha(pal.core, 0.8f * life), 24, 2f, (t * 0.02).toFloat())
            d.wall(VfxLayers.FLOW_ADD, center, a, b, up, 0.08, 0.95, 1.2, withAlpha(pal.core, 0.8f * life), withAlpha(pal.main, 0.55f * life), 24, 2f, (t * 0.02).toFloat())
            d.ribbon(VfxLayers.ENERGY, listOf(center.add(0.0, 0.9, 0.0), center, center.add(0.0, -1.0, 0.0)), { 0.06 }, { withAlpha(pal.core, 0.95f) }, 0.6f, (-t * 0.2).toFloat())
            d.billboard(VfxLayers.GLOW_ADD, center, 2.8, withAlpha(pal.main, 0.35f * life))
            d.billboard(VfxLayers.FLARE_ADD, center, 1.2, withAlpha(pal.core, 0.9f * life), t * 0.04)
        } else {
            val pal = HADES
            // Eine Pforte in die Tiefe: dunkles Auge, darum ein wirbelnder Ring und grünes Feuer.
            d.billboard(VfxLayers.SMOKE_ALPHA, center, 1.7, 0xF0020604.toInt(), t * 0.03)
            d.billboard(VfxLayers.GLOW_ADD, center, 3.4, withAlpha(pal.main, 0.4f * life))
            d.ring(VfxLayers.RING_ADD, center, d.camRight, d.camUp, 1.1, 1.6, withAlpha(pal.main, 0.7f * life), withAlpha(pal.edge, 0f), 40, 2f, (t * 0.03).toFloat())
            d.billboard(VfxLayers.FLARE_ADD, center, 2.2, withAlpha(pal.core, 0.55f * life), -t * 0.05)
        }
    }

    /** Leuchtkugel eines Geschosses in der Gefahrenfarbe seines Werfers (nie Gold). */
    private fun drawOrbBolt(d: VfxDraw, f: OrbFrame) {
        val pal = when (f.kind) {
            dev.henny.hha.entity.boss.HermesBoltEntity.VENOM -> HADES
            dev.henny.hha.entity.boss.HermesBoltEntity.FIRE, dev.henny.hha.entity.boss.HermesBoltEntity.ROCK -> Palette.of(Vfx.Style.FIRE)
            dev.henny.hha.entity.boss.HermesBoltEntity.SAND -> AEON
            else -> Palette.danger(Vfx.Style.HEAVEN)
        }
        val s = f.size.toDouble()
        val pulse = 0.9 + 0.1 * sin(f.time * 0.6)
        d.billboard(VfxLayers.GLOW_ADD, f.pos, 1.6 * s * pulse, withAlpha(pal.main, 0.55f))
        d.billboard(VfxLayers.FLARE_ADD, f.pos, 0.9 * s, withAlpha(pal.core, 0.95f), f.time * 0.12)
        d.billboard(VfxLayers.GLOW_ADD, f.pos, 0.45 * s, withAlpha(pal.core, 1f))
        // Schweif: ein paar kleiner werdende Lichter entgegen der Flugrichtung.
        val back = if (f.velocity.lengthSquared() < 1e-6) Vec3d.ZERO else f.velocity.normalize().multiply(-0.42 * s)
        for (i in 1..4) {
            val k = i / 4f
            d.billboard(VfxLayers.GLOW_ADD, f.pos.add(back.multiply(i.toDouble())), 1.0 * s * (1.0 - k * 0.75), withAlpha(pal.edge, 0.45f * (1f - k)))
        }
    }

    /** Caduceus-Schlange: Augen, Licht im Maul vor dem Biss, ein leuchtender Rückengrat-Streifen. */
    private fun drawSerpent(d: VfxDraw, f: SerpentFrame) {
        val pal = when {
            f.corrupted -> SHADE_SNAKE
            f.variant == 0 -> SNAKE_A
            else -> SNAKE_B
        }
        val rage = if (f.biting) 1.4f else 1f
        for (eye in listOf(f.eyeR, f.eyeL)) {
            d.billboard(VfxLayers.GLOW_ADD, eye, 0.62 * rage, withAlpha(pal.main, 0.9f))
            d.billboard(VfxLayers.FLARE_ADD, eye, 0.5 * rage, withAlpha(pal.core, 0.9f), f.time * 0.04)
        }
        d.billboard(VfxLayers.GLOW_ADD, f.mouth, if (f.biting) 1.9 else 0.8, withAlpha(pal.main, if (f.biting) 0.8f else 0.28f))
        if (f.biting) d.billboard(VfxLayers.FLARE_ADD, f.mouth, 1.7, withAlpha(pal.core, 0.75f), f.time * 0.07)
        if (f.spine.size >= 3) {
            d.ribbon(VfxLayers.ENERGY, f.spine, { k -> 0.09 * (1.0 - k * 0.7) }, { k -> withAlpha(pal.core, 0.9f - 0.5f * k) }, 0.3f, -f.time * 0.05f)
        }
    }
}
