package dev.henny.hha.client.vfx

import dev.henny.hha.HhaParticles
import dev.henny.hha.client.model.BrontesModel
import dev.henny.hha.client.model.GuardianRenderState
import dev.henny.hha.client.model.HekateModel
import dev.henny.hha.client.model.ZeusModel
import dev.henny.hha.entity.boss.HermesPose
import dev.henny.hha.entity.miniboss.KampePose
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossTimings
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.entity.model.EntityModel
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.Vec3d
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dauerlicht an den Boss-Modellen. Die Renderer lesen Ankerpunkte direkt aus den Knochen und reichen sie pro Bild
 * hierher; gezeichnet wird danach im normalen Effekt-Durchlauf mit denselben Shadern wie alle 3D-Effekte.
 *
 * **Budget (Überarbeitung 2, PLAN 9.1): höchstens zwei Dauer-Elemente je Boss, Alpha × [DIM]**, jedes Element ein
 * einziges Sprite (bei Paaren wie Augen oder Bident-Spitzen eines je Punkt). Der Rest lebt in der Leuchtmaske der
 * Textur:
 * - Hermes: Kugel des Stabs, Augen. Hält er die goldene Beute-Kugel, leuchtet sie statt der Stabkugel.
 * - Hades: Brustlaterne, Bident-Spitzen.
 * - Kronos: Stundenglas im Leib, Schneide der Harpe (Äon-Indigo, kein Bernstein).
 * - Kampe: Stachel (golden, solange er steckt), Augen; beim Speien das Maul statt der Augen.
 * - Neue Bosse je **ein** Element ([AnchorFrame], gefüllt über [collect]): Zeus der Blitz in der Hand, Hekate die
 *   Flamme der rechten Fackel, Brontes das Auge. Kratos hat keines.
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

    /** Welches Ein-Punkt-Licht ein [AnchorFrame] trägt. */
    enum class Anchor {
        /** Zeus: Mitte des Keraunos in der rechten Hand (`ZeusModel.keraunosAnchor`). */
        ZEUS_KERAUNOS,

        /** Hekate: Flamme von `torch_r` (`HekateModel.flameAnchor`). */
        HEKATE_FLAME,

        /** Brontes: Auge (`BrontesModel.eyeAnchor`, nur wenn `eyeOpen`). */
        BRONTES_EYE,
    }

    /**
     * Ein Dauerlicht an einem Punkt eines Modells (neue Bosse, je eines). Die Modell-Pakete liefern den Punkt in
     * Weltkoordinaten ([at]); am einfachsten über [collect] aus dem Feature-Renderer heraus.
     */
    class AnchorFrame(val id: Int, val time: Float, val anchor: Anchor) {
        var at: Vec3d = Vec3d.ZERO
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
    private val anchorFrames = ArrayList<AnchorFrame>()
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

    /** Alle Dauerlichter am Modell × 0,6 gegenüber dem Stand vor der Überarbeitung 2 (PLAN 9.1). */
    const val DIM = 0.6f

    /** Zeus' Blitz: Elektrischblau, Kern weiß (THUNDER). */
    private val THUNDER = Palette.of(Vfx.Style.THUNDER)

    /** Hekates Fackel: fahles Mondviolett (MOON). */
    private val MOON = Palette.of(Vfx.Style.MOON)

    /** Brontes' Auge: Schmiedeglut, bewusst nicht golden (Gold heißt „sicher/offen“). */
    private val FORGE_EYE = Palette(0xFFFFD6A0.toInt(), 0xFFFF7628.toInt(), 0xFF9C2A08.toInt(), 0xFFFFB070.toInt())

    private fun dim(alpha: Float): Float = alpha * DIM

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

    fun submit(frame: AnchorFrame) {
        anchorFrames.add(frame)
    }

    /**
     * Für den gemeinsamen `GuardianRenderer` (Feature-Renderer, nach dem Leuchtmasken-Durchgang, [matrices] im
     * Modellraum wie beim Zeichnen): liest den Anker der neuen Bosse aus dem Modell und reicht ihn ein. Für alle
     * anderen Modelle tut es nichts. Ein Aufruf je Bild und Wesen.
     */
    fun collect(model: EntityModel<*>, matrices: MatrixStack, state: GuardianRenderState) {
        val cam = MinecraftClient.getInstance().gameRenderer.camera.cameraPos
        when (model) {
            is ZeusModel -> {
                val out = Vector3f()
                if (model.keraunosAnchor(matrices, out)) {
                    submit(AnchorFrame(state.entityId, state.age, Anchor.ZEUS_KERAUNOS).also { it.at = Vec3d(out.x + cam.x, out.y + cam.y, out.z + cam.z) })
                }
            }
            is HekateModel -> model.flameAnchor(matrices, cam, state)?.let { pos ->
                submit(AnchorFrame(state.entityId, state.age, Anchor.HEKATE_FLAME).also { it.at = pos })
            }
            is BrontesModel -> if (model.eyeOpen) {
                submit(AnchorFrame(state.entityId, state.age, Anchor.BRONTES_EYE).also { it.at = model.eyeAnchor(matrices, cam) })
            }
            else -> {}
        }
    }

    fun clear() {
        hermesFrames.clear()
        serpentFrames.clear()
        hadesFrames.clear()
        orbFrames.clear()
        kronosFrames.clear()
        coreFrames.clear()
        kampeFrames.clear()
        anchorFrames.clear()
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
        for (frame in anchorFrames) drawAnchor(d, frame)
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
            // P02: halb so viele Federn und Funken wie früher.
            val rate = if (f.speed > 0.3f) 12 else 44
            if (random.nextInt(rate) == 0) {
                val wing = if (random.nextBoolean()) f.wingR else f.wingL
                val tip = if (wing === f.wingR) f.tipR else f.tipL
                val root = wing[1 + random.nextInt(wing.size - 1)]
                val at = root.lerp(tip, random.nextDouble())
                world.addParticleClient(HhaParticles.FEATHER, at.x, at.y, at.z, 0.0, 0.0, 0.0)
            }
            if (!f.corrupted && random.nextInt(8) == 0) {
                val a = random.nextDouble() * Math.PI * 2.0
                world.addParticleClient(HhaParticles.HOLY_SPARK, f.orb.x + cos(a) * 0.5, f.orb.y + (random.nextDouble() - 0.5), f.orb.z + sin(a) * 0.5, 0.0, 0.01, 0.0)
            }
        }
        for (seen in lastSerpents.values) {
            val spine = seen.frame.spine
            if (spine.isNotEmpty() && random.nextInt(8) == 0) {
                val at = spine[random.nextInt(spine.size)]
                world.addParticleClient(HhaParticles.HOLY_SPARK, at.x, at.y + 0.3, at.z, 0.0, 0.015, 0.0)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Hermes: Kugel (oder Beute-Kugel), Augen
    // ---------------------------------------------------------------------------------------------

    private fun drawHermes(d: VfxDraw, f: HermesFrame) {
        val pal = if (f.corrupted) SHADE_PALETTE else STAGE_PALETTES[f.stage] ?: STAGE_PALETTES.getValue(1)
        val t = f.time.toDouble()
        // P02: ruhiges Glimmen statt Flackern (früher ± 12 %).
        val p = f.power * (0.95f + 0.05f * sin(f.time * 0.19f))
        // Rauchschritt des Schattens: In der Säule glimmen nur die Augen (der Renderer liefert erst ab SMOKE_EYES
        // überhaupt einen Frame). Die Kugel würde die echte Säule verraten.
        val smoke = f.corrupted && f.pose == HermesPose.SMOKE
        if (!smoke) {
            if (f.loot) drawLoot(d, f, t) else drawOrb(d, f, pal, p)
        }
        for (eye in listOf(f.eyeR, f.eyeL)) {
            d.billboard(VfxLayers.FLARE_ADD, eye, 0.34, withAlpha(pal.core, dim(0.9f * p)), t * 0.03)
        }
    }

    /** Die Kugel des Caduceus: ein weicher Lichtpunkt (Ring und Strahlenstern leben in der Leuchtmaske). */
    private fun drawOrb(d: VfxDraw, f: HermesFrame, pal: Palette, p: Float) {
        d.billboard(VfxLayers.GLOW_ADD, f.orb, 1.3, withAlpha(lerpColor(pal.main, pal.core, 0.4f), dim(0.9f * p)))
    }

    /** Die Beute-Kugel in seiner Linken: golden, denn wer ihn jetzt trifft, holt sich seine Effekte zurück. */
    private fun drawLoot(d: VfxDraw, f: HermesFrame, t: Double) {
        val pulse = 0.92 + 0.08 * sin(t * 0.3)
        d.billboard(VfxLayers.GLOW_ADD, f.lootOrb, 1.2 * pulse, withAlpha(lerpColor(GOLD.main, GOLD.core, 0.3f), dim(0.95f)))
    }

    // ---------------------------------------------------------------------------------------------
    // Hades: Brustlaterne, Bident-Spitzen
    // ---------------------------------------------------------------------------------------------

    private fun drawHades(d: VfxDraw, f: HadesFrame) {
        val pal = HADES
        val t = f.time.toDouble()
        // Am Boden und verhüllt erlischt alles (die Augen leben in der Leuchtmaske). Das gehört zum Köder.
        if (f.fallen || f.cloaked) return
        val grown = 1.0 + f.form * 0.2
        // Beim Taumeln flackert die Laterne: Das ist das Zeichen zum Zuschlagen (Spielinformation, bleibt).
        val weak = if (f.staggered) (0.55f + 0.45f * sin(t * 1.1).toFloat()) else 1f
        d.billboard(VfxLayers.GLOW_ADD, f.chest, 2.0 * grown, withAlpha(lerpColor(pal.main, pal.core, 0.3f), dim(0.8f * weak)))
        // Fliegt der Bident, gibt es an der Hand nichts zu leuchten.
        if (f.bidentOut) return
        for (tip in listOf(f.tipA, f.tipB)) {
            d.billboard(VfxLayers.FLARE_ADD, tip, 0.75, withAlpha(pal.core, dim(0.8f)), t * 0.05)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Kronos: Stundenglas im Leib, Schneide (Äon-Indigo)
    // ---------------------------------------------------------------------------------------------

    private fun drawKronos(d: VfxDraw, f: KronosFrame) {
        val pal = AEON
        val t = f.time.toDouble()
        val power = if (f.form > 0) 1.2f else 1f
        val dead = f.pose == dev.henny.hha.entity.boss.KronosPose.BOUND
        val p = power * (if (dead) 0.45f else 1f)
        // Der Sand im Stundenglas: ein ruhiges Pulsieren, in der zweiten Gestalt rinnt die Zeit schneller.
        val unit = f.haloUp.length().takeIf { it > 1e-4 } ?: 0.06
        val sand = 0.9f + 0.1f * sin(t * (if (f.form > 0) 0.5 else 0.22)).toFloat()
        d.billboard(VfxLayers.GLOW_ADD, f.chest, (unit * 14.0).coerceIn(0.6, 2.2), withAlpha(lerpColor(pal.main, pal.core, 0.35f), dim(0.8f * p * sand)))
        // Die Schneide der Harpe, solange sie in der Hand ist.
        if (!f.harpeOut && f.blade.size >= 2) {
            d.ribbon(VfxLayers.ENERGY, f.blade, { k -> 0.2 * (1.0 - k * 0.6) }, { k -> withAlpha(pal.core, dim((0.9f - 0.3f * k) * p)) }, 0.35f, (-t * 0.06).toFloat())
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Kampe: Stachel, Augen (beim Speien das Maul statt der Augen)
    // ---------------------------------------------------------------------------------------------

    private fun drawKampe(d: VfxDraw, f: KampeFrame) {
        val pal = HADES
        val t = f.time.toDouble()
        if (f.pose == KampePose.DEATH) return
        if (f.pose == KampePose.SPIT) {
            d.billboard(VfxLayers.GLOW_ADD, f.mouth, 1.1, withAlpha(lerpColor(pal.main, pal.core, 0.3f), dim(0.8f)))
        } else {
            for (eye in listOf(f.eyeR, f.eyeL)) {
                d.billboard(VfxLayers.FLARE_ADD, eye, 0.4, withAlpha(pal.core, dim(0.9f)), t * 0.03)
            }
        }
        // Der Stachel: grün, beim Ausholen heller. Steckt er im Boden (offen, Treffer × 1,5), glüht er golden.
        val stung = f.pose == KampePose.STING
        val stuckFrom = (BossTimings.Kampe.STING_WINDUP + BossTimings.Kampe.STING_STRIKE).toFloat()
        val stuck = stung && f.poseTime >= stuckFrom && f.poseTime <= BossTimings.Kampe.STING_STUCK_END
        val sting = if (stuck) GOLD else pal
        val strength = when {
            stuck -> 0.9f
            stung -> 0.95f
            else -> 0.45f
        }
        d.billboard(VfxLayers.GLOW_ADD, f.stingTip, if (stung) 1.1 else 0.7, withAlpha(lerpColor(sting.main, sting.core, 0.3f), dim(strength)))
    }

    // ---------------------------------------------------------------------------------------------
    // Neue Bosse: je ein Element
    // ---------------------------------------------------------------------------------------------

    private fun drawAnchor(d: VfxDraw, f: AnchorFrame) {
        val t = f.time.toDouble()
        when (f.anchor) {
            // Der Blitz in Zeus' Hand: ein Kern, kein Lichthof (die Ader lebt in der Leuchtmaske).
            Anchor.ZEUS_KERAUNOS -> d.billboard(VfxLayers.FLARE_ADD, f.at, 0.9, withAlpha(THUNDER.core, dim(0.85f)), t * 0.04)
            // Hekates Fackelflamme: klein und fahl, ruhig flackernd.
            Anchor.HEKATE_FLAME -> {
                val flicker = 0.93f + 0.07f * sin(f.time * 0.7f)
                d.billboard(VfxLayers.GLOW_ADD, f.at, 0.7, withAlpha(lerpColor(MOON.main, MOON.core, 0.4f), dim(0.85f * flicker)))
            }
            // Brontes' Auge: Glut der Schmiede.
            Anchor.BRONTES_EYE -> d.billboard(VfxLayers.GLOW_ADD, f.at, 0.6, withAlpha(lerpColor(FORGE_EYE.main, FORGE_EYE.core, 0.4f), dim(0.85f)))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Requisiten (kein Boss-Budget, aber gleich gedämpft)
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
            d.wall(VfxLayers.FLOW_ADD, center.add(0.0, -1.2, 0.0), a, b, up, 0.95, 0.08, 1.2, withAlpha(pal.main, dim(0.55f * life)), withAlpha(pal.core, dim(0.8f * life)), 24, 2f, (t * 0.02).toFloat())
            d.wall(VfxLayers.FLOW_ADD, center, a, b, up, 0.08, 0.95, 1.2, withAlpha(pal.core, dim(0.8f * life)), withAlpha(pal.main, dim(0.55f * life)), 24, 2f, (t * 0.02).toFloat())
            d.ribbon(VfxLayers.ENERGY, listOf(center.add(0.0, 0.9, 0.0), center, center.add(0.0, -1.0, 0.0)), { 0.06 }, { withAlpha(pal.core, dim(0.95f)) }, 0.6f, (-t * 0.2).toFloat())
            d.billboard(VfxLayers.GLOW_ADD, center, 1.8, withAlpha(pal.main, dim(0.35f * life)))
        } else {
            val pal = HADES
            // Eine Pforte in die Tiefe: dunkles Auge, darum ein wirbelnder Ring und grünes Feuer.
            d.billboard(VfxLayers.SMOKE_ALPHA, center, 1.7, 0xF0020604.toInt(), t * 0.03)
            d.billboard(VfxLayers.GLOW_ADD, center, 2.4, withAlpha(pal.main, dim(0.4f * life)))
            d.ring(VfxLayers.RING_ADD, center, d.camRight, d.camUp, 1.1, 1.6, withAlpha(pal.main, dim(0.7f * life)), withAlpha(pal.edge, 0f), 40, 2f, (t * 0.03).toFloat())
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
        d.billboard(VfxLayers.GLOW_ADD, f.pos, 1.2 * s, withAlpha(pal.main, dim(0.6f)))
        d.billboard(VfxLayers.GLOW_ADD, f.pos, 0.45 * s, withAlpha(pal.core, dim(1f)))
        // Schweif: zwei kleiner werdende Lichter entgegen der Flugrichtung (früher vier).
        val back = if (f.velocity.lengthSquared() < 1e-6) Vec3d.ZERO else f.velocity.normalize().multiply(-0.6 * s)
        for (i in 1..2) {
            val k = i / 2f
            d.billboard(VfxLayers.GLOW_ADD, f.pos.add(back.multiply(i.toDouble())), 0.9 * s * (1.0 - k * 0.6), withAlpha(pal.edge, dim(0.45f * (1f - k * 0.7f))))
        }
    }

    /** Caduceus-Schlange: Augen und ein Rückengrat-Streifen; beim Biss leuchtet das Maul statt des Streifens. */
    private fun drawSerpent(d: VfxDraw, f: SerpentFrame) {
        val pal = when {
            f.corrupted -> SHADE_SNAKE
            f.variant == 0 -> SNAKE_A
            else -> SNAKE_B
        }
        for (eye in listOf(f.eyeR, f.eyeL)) {
            d.billboard(VfxLayers.FLARE_ADD, eye, if (f.biting) 0.6 else 0.45, withAlpha(pal.core, dim(0.9f)), f.time * 0.04)
        }
        if (f.biting) {
            d.billboard(VfxLayers.GLOW_ADD, f.mouth, 1.4, withAlpha(pal.main, dim(0.8f)))
        } else if (f.spine.size >= 3) {
            d.ribbon(VfxLayers.ENERGY, f.spine, { k -> 0.08 * (1.0 - k * 0.7) }, { k -> withAlpha(pal.core, dim(0.9f - 0.5f * k)) }, 0.3f, -f.time * 0.05f)
        }
    }
}
