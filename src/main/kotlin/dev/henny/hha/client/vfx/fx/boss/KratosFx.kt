package dev.henny.hha.client.vfx.fx.boss

import dev.henny.hha.HhaSounds
import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.VfxEffect
import dev.henny.hha.client.vfx.VfxManager
import dev.henny.hha.client.vfx.fx.BlockDebris
import dev.henny.hha.client.vfx.fx.FxArgs
import dev.henny.hha.client.vfx.fx.FxDraw
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxMeshes
import dev.henny.hha.client.vfx.fx.FxMode
import dev.henny.hha.client.vfx.fx.FxRegistry
import dev.henny.hha.client.vfx.fx.FxSolidMode
import dev.henny.hha.client.vfx.fx.lib.ArcFx
import dev.henny.hha.client.vfx.fx.lib.FxLib
import dev.henny.hha.client.vfx.fx.lib.GroundCrackFx
import dev.henny.hha.client.vfx.fx.lib.SparkSet
import dev.henny.hha.entity.boss.olymp.BiaEntity
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import org.joml.Vector3f
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Client-Effekte von Kratos und Bia (P10, PLAN 2.7), Präfix `kratos.`/`bia.`. Jede ID hat eine eigene Funktion bzw.
 * Klasse; Zeiten in Ticks, Größen in Blöcken, alles als benannte Konstante unten. Server-Kodierung (`BossFx.play`):
 *
 * | ID | pos | dir | a | b | c | d | life | entity | Bild |
 * | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
 * | `kratos.smash` | Kreismitte (Boden) | Richtung Kratos → Kreis | Radius | 1 = zweiter Schlag | – | – | 60 | – | Stoßring, Staub, 5 Risse (zweiter 6), 5 echte Blöcke, Funken, kurzer Schein; Ton `boss.thud` |
 * | `kratos.bash` | Brust | Stoßrichtung | Radius | halber Winkel (°) | – | – | 12 | – | Kegel-Schleier: liegendes Band + Front auf einem Kreisbogen, wandert hinaus, Staub am Boden |
 * | `kratos.command` | Hand | Bia (absolut) | Biegung | Breite | Stränge | – | 12 | entity2 = Bia | `core.arc` zur Bia, Bias Augen pulsieren auf |
 * | `bia.land` | Landepunkt | – | Radius | Blöcke | Risse | 1 = klein (ohne Kuppel) | 60 | – | Staubkuppel, Ring, Risse, echte Blöcke, Staub; Ton `boss.thud` |
 * | `bia.blade` | Platzmitte (Boden) | Laufrichtung | Platzradius | Höhe der Klinge | Laufdauer | – | Lauf + 8 | Bia | breites Band quer über den Platz an Bias Front, Schweif dahinter |
 * | `bia.rush` | Linienanfang | Linienende (absolut) | Linienbreite | Laufdauer | – | – | Lauf + 14 | Bia | Staubwolken auf der Linie (schmaler als sie), kurzer Schweif |
 * | `bia.awake` | Bias Füße | – | – | 1 = erstarrt | – | – | 60 | (gesucht) | Riss läuft über die Statue, Steinbrocken und Sockelblöcke platzen ab; erstarrt: Riss kühlt und schließt |
 *
 * | `kratos.bia_open` (P17) | Bias Füße | – | – | – | – | – | Fenster (20/80) | Bia | goldene Risse vorn und hinten über die Statue, ruhiges Atmen 0,8 Hz, schwacher Schein an der Brust |
 * | `kratos.bia_break` (P17) | Bias Füße | weg von Kratos | – | – | – | – | 70 | Bia | Rissnetz glüht auf, Statue birst: 14 Steinbrocken, Staubkuppel, 6 echte Blöcke, kurzer Schein; Ton `boss.thud` |
 *
 * Gold kommt nur in `kratos.bia_open` vor (Server schickt dort `Style.GOLD`: Bia ist offen). Sonst Gefahrenfarbe über die Palette des Kampfes (Crimson). Bodeneffekte beginnen erst mit
 * dem Treffer bzw. der Landung (die Ansage ist dann vorbei oder liegt darunter).
 */
object KratosFx {

    fun init() {
        FxRegistry.register("kratos.smash") { smash(it) }
        FxRegistry.register("kratos.bash") { BashFx(it) }
        FxRegistry.register("kratos.command") { command(it) }
        FxRegistry.register("bia.land") { land(it) }
        FxRegistry.register("bia.blade") { BladeFx(it) }
        FxRegistry.register("bia.rush") { RushFx(it) }
        FxRegistry.register("bia.awake") { awake(it) }
        // Überarbeitung 4 (P17): Bia als Teilziel.
        FxRegistry.register("kratos.bia_open") { BiaOpenFx(it) }
        FxRegistry.register("kratos.bia_break") { breakFx(it) }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Augen-Puls Bias (liest `BiaModel.glowAlpha`): nach einem Befehl auf, beim Erwachen hell, beim Erstarren dunkler
    // ---------------------------------------------------------------------------------------------------------------

    private class Pulse(val start: Float, val ticks: Float, val peak: Float)

    private val pulses = HashMap<Int, Pulse>()

    /** Merkt einen Puls für [entity]: über [ticks] Ticks ein Sinusbogen bis [peak] (negativ = dunkler). */
    private fun pulse(entity: Entity, ticks: Float, peak: Float) {
        if (pulses.size > PULSE_MAX_ENTRIES) pulses.clear()
        pulses[entity.id] = Pulse(entity.age.toFloat(), ticks, peak)
    }

    /** Zusatz zur Leuchtstärke des Wesens [entityId] bei Alter [age] (mit Zwischenbild); 0 ohne Puls. */
    fun eyePulse(entityId: Int, age: Float): Float {
        val p = pulses[entityId] ?: return 0f
        val k = (age - p.start) / p.ticks
        if (k < 0f || k > 1f) {
            if (k > 1f || k < PULSE_FORGET) pulses.remove(entityId)
            return 0f
        }
        return p.peak * Ease.bump(k)
    }

    /** Verbindungsende o. Ä.: alle Pulse vergessen (Wesen-IDs gelten nicht mehr). */
    fun reset() = pulses.clear()

    // ---------------------------------------------------------------------------------------------------------------
    // Gemeinsame Helfer
    // ---------------------------------------------------------------------------------------------------------------

    /** Aufruf eines Bibliothekseffekts mit Palette und Gefahr des eigenen Aufrufs (nur am Client, ohne Netz). */
    private fun sub(
        args: FxArgs, id: String, pos: Vec3d, dir: Vec3d = Vec3d.ZERO, a: Float = 0f, b: Float = 0f, c: Float = 0f,
        d: Float = 0f, life: Int = 20, entity: Entity? = null, entity2: Entity? = null,
    ) = FxArgs(id, args.style, args.palette, args.danger, pos, dir, a, b, c, d, life, entity, entity2, "", args.world, args.seed * 31L + id.hashCode())

    /** Ein zusätzlicher Effekt dieses Aufrufs (der eigentliche geht über `FxRegistry`, der hier selbst). */
    private fun extra(effect: VfxEffect) {
        if (FxLib.enabled()) VfxManager.addEffect(effect)
    }

    private fun thud(world: ClientWorld, at: Vec3d, volume: Float, pitch: Float) {
        if (FxLib.enabled()) FxLib.sound(world, at, HhaSounds.THUD, volume, pitch)
    }

    /** Weiches Auslaufen an beiden Enden eines Bandes (u in 0..1). */
    private fun edge(u: Float): Float = sin(u * PI.toFloat()).coerceAtLeast(0f).pow(EDGE_POWER)

    /** Staubfarbe (warmer Marmorstaub, nicht Gefahr, nicht Gold). */
    private const val DUST = 0xFFCDBFA8.toInt()
    private const val DUST_COLD = 0xFFB2AEA6.toInt()
    private const val EDGE_POWER = 0.8f
    private const val PULSE_MAX_ENTRIES = 32
    private const val PULSE_FORGET = -40f
    private val UP = FxLib.UP

    // ---------------------------------------------------------------------------------------------------------------
    // kratos.smash – Hammerfall
    // ---------------------------------------------------------------------------------------------------------------

    private fun smash(args: FxArgs): VfxEffect {
        val second = args.b >= 0.5f
        val radius = args.a.coerceIn(1f, 8f)
        extra(GroundCrackFx.star(sub(args, "core.cracks", args.pos, a = radius * SMASH_CRACK_REACH,
            b = (if (second) SMASH_CRACKS_SECOND else SMASH_CRACKS).toFloat(), life = args.life.coerceAtLeast(SMASH_CRACK_LIFE))))
        if (FxLib.enabled()) {
            BlockDebris.burst(args.world, args.pos, SMASH_DEBRIS_INNER, radius * SMASH_DEBRIS_OUTER, SMASH_BLOCKS, SMASH_DEBRIS_UP, SMASH_DEBRIS_OUT)
        }
        thud(args.world, args.pos, SMASH_THUD_VOLUME, if (second) 0.75f else 0.85f)
        return SmashFx(args, radius, second)
    }

    /** Stoßring und Staub am Boden, Funken, ein kurzer Schein (Risse und Blöcke laufen getrennt). */
    private class SmashFx(args: FxArgs, private val radius: Float, private val second: Boolean) : FxEffect(SMASH_LIFE) {
        private val at = args.pos
        private val palette = args.palette
        private val seed = args.seed.toInt()
        private val sparks = SparkSet(
            if (second) SMASH_SPARKS_SECOND else SMASH_SPARKS, SMASH_SPARK_SPEED, UP.add(FxLib.flat(args.dir).multiply(0.35)), args.seed,
        )

        override fun energy(f: FxFrame) {
            val t = time(f)
            val rk = (t / SMASH_RING_TICKS).coerceIn(0f, 1f)
            if (rk < 1f) {
                val outer = SMASH_RING_START + (radius + SMASH_RING_OVER - SMASH_RING_START) * Ease.outCubic(rk)
                FxDraw.flat(f, at.add(0.0, GROUND_LIFT, 0.0), UP, outer, FxLib.alpha(palette.main, SMASH_RING_ALPHA * (1f - rk) * (1f - rk)),
                    FxMode.SHOCK, inner = outer * RING_INNER, seed = seed)
            }
            val dk = (t / SMASH_DUST_TICKS).coerceIn(0f, 1f)
            if (dk < 1f) {
                FxDraw.flat(f, at.add(0.0, DUST_LIFT, 0.0), UP, radius * (SMASH_DUST_R0 + SMASH_DUST_GROW * Ease.outQuad(dk)),
                    FxLib.alpha(DUST, SMASH_DUST_ALPHA * (1f - dk).pow(1.5f)), FxMode.DUST, rotation = seed * 0.37f, progress = 1f - 0.5f * dk, seed = seed)
            }
            sparks.draw(f, at.add(0.0, SPARK_LIFT, 0.0), t, SMASH_SPARK_SPAN, 1f, palette)
        }

        override fun over(f: FxFrame) {
            val t = time(f)
            if (t > SMASH_FLASH_TICKS) return
            val k = 1f - t / SMASH_FLASH_TICKS
            FxDraw.glow(f, at.add(0.0, FLASH_LIFT, 0.0), SMASH_FLASH_SIZE * (if (second) 1.25f else 1f), FxLib.alpha(palette.core, SMASH_FLASH_ALPHA * k * k * k), seed)
        }
    }

    private const val SMASH_LIFE = 34
    private const val SMASH_CRACKS = 5
    private const val SMASH_CRACKS_SECOND = 6
    private const val SMASH_CRACK_REACH = 1.15f
    private const val SMASH_CRACK_LIFE = 60
    private const val SMASH_BLOCKS = 5
    private const val SMASH_DEBRIS_INNER = 0.5f
    private const val SMASH_DEBRIS_OUTER = 0.75f
    private const val SMASH_DEBRIS_UP = 0.5f
    private const val SMASH_DEBRIS_OUT = 0.16f
    private const val SMASH_THUD_VOLUME = 0.9f
    private const val SMASH_RING_TICKS = 9f
    private const val SMASH_RING_START = 0.6f
    private const val SMASH_RING_OVER = 0.7f
    private const val SMASH_RING_ALPHA = 0.55f
    private const val SMASH_DUST_TICKS = 30f
    private const val SMASH_DUST_R0 = 0.75f
    private const val SMASH_DUST_GROW = 0.45f
    private const val SMASH_DUST_ALPHA = 0.4f
    private const val SMASH_SPARKS = 8
    private const val SMASH_SPARKS_SECOND = 10
    private const val SMASH_SPARK_SPEED = 0.42f
    private const val SMASH_SPARK_SPAN = 18f
    private const val SMASH_FLASH_TICKS = 6f
    private const val SMASH_FLASH_SIZE = 1.3f
    private const val SMASH_FLASH_ALPHA = 0.35f
    private const val RING_INNER = 0.72f
    private const val GROUND_LIFT = 0.06
    private const val DUST_LIFT = 0.1
    private const val SPARK_LIFT = 0.25
    private const val FLASH_LIFT = 0.35

    // ---------------------------------------------------------------------------------------------------------------
    // kratos.bash – Schildstoß
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Kurzer Kegel-Schleier: Auf einem Kreisbogen (± halber Winkel) um die Brust wandert ein liegendes Band mit einer
     * helleren Front bis zum Radius hinaus; darunter heben sich drei Staubwolken. Nie weiter als der Kegel der Ansage.
     */
    private class BashFx(args: FxArgs) : FxEffect(args.life.coerceIn(BASH_MIN_LIFE, BASH_MAX_LIFE)) {
        private val apex = args.pos
        private val dir = FxLib.flat(args.dir)
        private val side = Vec3d(-dir.z, 0.0, dir.x)
        private val radius = args.a.coerceIn(1f, 12f)
        private val half = Math.toRadians(args.b.coerceIn(10f, 90f).toDouble()) * BASH_SPAN
        private val floorY = FxLib.groundY(args.world, apex.x, apex.y, apex.z)
        private val palette = args.palette
        private val seed = args.seed.toInt()

        private fun arc(d: Double, y: Double): List<Vec3d> = List(BASH_POINTS) { i ->
            val ang = (i / (BASH_POINTS - 1.0) * 2.0 - 1.0) * half
            val v = dir.multiply(cos(ang)).add(side.multiply(sin(ang)))
            Vec3d(apex.x + v.x * d, y, apex.z + v.z * d)
        }

        override fun energy(f: FxFrame) {
            val t = time(f)
            val travel = Ease.outCubic(t / BASH_TRAVEL_TICKS)
            val d = BASH_START + (radius - BASH_START) * travel
            val fade = Ease.inOut(t / BASH_FADE_IN) * (1f - Ease.inOut((t - (life - BASH_FADE_OUT)) / BASH_FADE_OUT))
            if (fade <= 0.01f) return
            val pts = arc(d.toDouble(), apex.y)
            val veil = BASH_VEIL_MIN + BASH_VEIL_GROW * travel
            FxDraw.strip(f, pts, UP, width = { u -> veil * edge(u) },
                color = { u -> FxLib.alpha(FxLib.mix(palette.main, palette.core, 0.25f), BASH_VEIL_ALPHA * fade * edge(u)) }, mode = FxMode.RIBBON, seed = seed)
            FxDraw.ribbon(f, pts, width = { u -> BASH_FRONT_WIDTH * edge(u) },
                color = { u -> FxLib.alpha(FxLib.mix(palette.main, palette.core, 0.6f), BASH_FRONT_ALPHA * fade * edge(u)) }, mode = FxMode.RIBBON, seed = seed + 1)
            if (d < BASH_DUST_FROM) return
            val ground = arc(d.toDouble() * BASH_DUST_LAG, floorY + DUST_LIFT)
            for (k in BASH_DUST_AT) {
                val p = ground[(k * (BASH_POINTS - 1)).toInt()]
                FxDraw.flat(f, p, UP, BASH_DUST_RADIUS * (0.7f + 0.5f * travel), FxLib.alpha(DUST, BASH_DUST_ALPHA * fade), FxMode.DUST,
                    rotation = k * 2.1f + seed, progress = 0.7f, seed = seed + (k * 10).toInt())
            }
        }
    }

    private const val BASH_MIN_LIFE = 8
    private const val BASH_MAX_LIFE = 30
    private const val BASH_POINTS = 13
    /** Anteil des halben Kegelwinkels, den der Schleier überdeckt (bleibt in der Ansage). */
    private const val BASH_SPAN = 0.92
    private const val BASH_START = 0.9f
    private const val BASH_TRAVEL_TICKS = 9f
    private const val BASH_FADE_IN = 2f
    private const val BASH_FADE_OUT = 4f
    private const val BASH_VEIL_MIN = 0.35f
    private const val BASH_VEIL_GROW = 0.9f
    private const val BASH_VEIL_ALPHA = 0.32f
    private const val BASH_FRONT_WIDTH = 0.28f
    private const val BASH_FRONT_ALPHA = 0.5f
    private const val BASH_DUST_FROM = 2f
    private const val BASH_DUST_LAG = 0.85
    private val BASH_DUST_AT = floatArrayOf(0.2f, 0.5f, 0.8f)
    private const val BASH_DUST_RADIUS = 0.75f
    private const val BASH_DUST_ALPHA = 0.28f

    // ---------------------------------------------------------------------------------------------------------------
    // kratos.command – Befehl an Bia
    // ---------------------------------------------------------------------------------------------------------------

    /** `core.arc` von der Hand zu Bia (Ende folgt ihr) und ein Augen-Puls, damit man sieht, wem der Befehl gilt. */
    private fun command(args: FxArgs): VfxEffect {
        args.entity2?.let { pulse(it, COMMAND_PULSE_TICKS, COMMAND_PULSE) }
        return ArcFx(sub(args, "core.arc", args.pos, args.dir, args.a, args.b, args.c.coerceAtLeast(1f), life = args.life, entity2 = args.entity2))
    }

    private const val COMMAND_PULSE_TICKS = 20f
    private const val COMMAND_PULSE = 0.5f

    // ---------------------------------------------------------------------------------------------------------------
    // bia.land – Landung
    // ---------------------------------------------------------------------------------------------------------------

    private fun land(args: FxArgs): VfxEffect {
        val radius = args.a.coerceIn(0.8f, 6f)
        val cracks = args.c.toInt().coerceIn(0, 6)
        if (cracks > 0) extra(GroundCrackFx.star(sub(args, "core.cracks", args.pos, a = radius * LAND_CRACK_REACH, b = cracks.toFloat(), life = args.life.coerceAtLeast(LAND_CRACK_LIFE))))
        if (FxLib.enabled()) BlockDebris.burst(args.world, args.pos, LAND_DEBRIS_INNER, radius * LAND_DEBRIS_OUTER, args.b.toInt(), LAND_DEBRIS_UP, LAND_DEBRIS_OUT)
        thud(args.world, args.pos, LAND_THUD_VOLUME, 1.05f)
        return LandFx(args, radius, args.d >= 0.5f)
    }

    /** Flache Staubkuppel (nicht bei [small]), Stoßring, Bodenstaub. */
    private class LandFx(args: FxArgs, private val radius: Float, private val small: Boolean) : FxEffect(LAND_LIFE) {
        private val at = args.pos
        private val palette = args.palette
        private val seed = args.seed.toInt()

        override fun energy(f: FxFrame) {
            val t = time(f)
            val ok = (t / LAND_DOME_TICKS).coerceIn(0f, 1f)
            if (!small && ok < 1f) {
                val r = radius * (LAND_DOME_R0 + LAND_DOME_GROW * Ease.outCubic(ok))
                val m = FxDraw.place(at, scaleXYZ = Vector3f(r, r * LAND_DOME_FLAT, r))
                FxDraw.mesh(f, FxMeshes.DOME, m, FxLib.alpha(FxLib.mix(DUST, palette.main, 0.35f), LAND_DOME_ALPHA * (1f - ok) * (1f - ok)), FxMode.DOME, seed = seed)
            }
            val rk = (t / LAND_RING_TICKS).coerceIn(0f, 1f)
            if (rk < 1f) {
                val outer = radius * (LAND_RING_R0 + LAND_RING_GROW * Ease.outCubic(rk))
                FxDraw.flat(f, at.add(0.0, GROUND_LIFT, 0.0), UP, outer, FxLib.alpha(palette.main, LAND_RING_ALPHA * (1f - rk) * (1f - rk)),
                    FxMode.SHOCK, inner = outer * RING_INNER, seed = seed)
            }
            val dk = (t / LAND_DUST_TICKS).coerceIn(0f, 1f)
            if (dk < 1f) {
                FxDraw.flat(f, at.add(0.0, DUST_LIFT, 0.0), UP, radius * (0.9f + 0.4f * Ease.outQuad(dk)),
                    FxLib.alpha(DUST, LAND_DUST_ALPHA * (1f - dk).pow(1.5f)), FxMode.DUST, rotation = seed * 0.61f, progress = 1f - 0.5f * dk, seed = seed)
            }
        }
    }

    private const val LAND_LIFE = 30
    private const val LAND_CRACK_REACH = 1.15f
    private const val LAND_CRACK_LIFE = 50
    private const val LAND_DEBRIS_INNER = 0.4f
    private const val LAND_DEBRIS_OUTER = 0.9f
    private const val LAND_DEBRIS_UP = 0.42f
    private const val LAND_DEBRIS_OUT = 0.18f
    private const val LAND_THUD_VOLUME = 0.7f
    private const val LAND_DOME_TICKS = 12f
    private const val LAND_DOME_R0 = 0.35f
    private const val LAND_DOME_GROW = 0.75f
    private const val LAND_DOME_FLAT = 0.6f
    private const val LAND_DOME_ALPHA = 0.45f
    private const val LAND_RING_TICKS = 9f
    private const val LAND_RING_R0 = 0.4f
    private const val LAND_RING_GROW = 1.25f
    private const val LAND_RING_ALPHA = 0.5f
    private const val LAND_DUST_TICKS = 26f
    private const val LAND_DUST_ALPHA = 0.35f

    // ---------------------------------------------------------------------------------------------------------------
    // bia.blade – „KNIET!“
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Bias Klinge als breites Band auf Brusthöhe: eine helle Front quer über den ganzen Platz (Sehne des Kreises an
     * ihrer Front, folgt ihr) und dahinter ein liegender Schweif. Ohne Wesen läuft die Front nach der Laufdauer.
     */
    private class BladeFx(args: FxArgs) : FxEffect(args.life.coerceIn(BLADE_MIN_LIFE, BLADE_MAX_LIFE)) {
        private val center = args.pos
        private val dir = FxLib.flat(args.dir)
        private val side = Vec3d(-dir.z, 0.0, dir.x)
        private val r = args.a.coerceIn(2f, 40f).toDouble()
        private val y = center.y + args.b.coerceIn(0.5f, 4f)
        private val run = args.c.coerceIn(1f, life.toFloat())
        private val bia = args.entity
        private val palette = args.palette
        private val seed = args.seed.toInt()

        private fun front(tickDelta: Float, t: Float): Double {
            val at = bia?.takeIf { !it.isRemoved }?.getLerpedPos(tickDelta)
                ?: return -r + 2.0 * r * (t / run).coerceIn(0f, 1f)
            return ((at.x - center.x) * dir.x + (at.z - center.z) * dir.z).coerceIn(-r, r)
        }

        private fun chord(along: Double, half: Double): List<Vec3d> = List(BLADE_POINTS) { i ->
            val s = (i / (BLADE_POINTS - 1.0) * 2.0 - 1.0) * half
            Vec3d(center.x + dir.x * along + side.x * s, y, center.z + dir.z * along + side.z * s)
        }

        override fun energy(f: FxFrame) {
            val t = time(f)
            val fade = Ease.inOut(t / BLADE_FADE_IN) * (1f - Ease.inOut((t - run) / (life - run).coerceAtLeast(1f)))
            if (fade <= 0.01f) return
            val fr = front(f.tickDelta, t)
            val half = sqrt((r * r - fr * fr).coerceAtLeast(0.0)) * BLADE_CHORD
            if (half < BLADE_MIN_HALF) return
            val trail = min(BLADE_TRAIL, fr + r).coerceAtLeast(0.0)
            if (trail > BLADE_MIN_TRAIL) {
                FxDraw.strip(f, chord(fr - trail * 0.5, half), UP, width = { _ -> (trail * 0.5).toFloat() },
                    color = { u -> FxLib.alpha(palette.main, BLADE_VEIL_ALPHA * fade * edge(u)) }, mode = FxMode.RIBBON, seed = seed)
            }
            FxDraw.ribbon(f, chord(fr, half), width = { u -> BLADE_EDGE_WIDTH * (0.6f + 0.4f * edge(u)) },
                color = { u -> FxLib.alpha(FxLib.mix(palette.main, palette.core, 0.55f), BLADE_EDGE_ALPHA * fade * edge(u)) }, mode = FxMode.RIBBON, seed = seed + 1)
        }
    }

    private const val BLADE_MIN_LIFE = 10
    private const val BLADE_MAX_LIFE = 120
    private const val BLADE_POINTS = 17
    private const val BLADE_FADE_IN = 3f
    /** Die Sehne bleibt knapp im Kreis (Rand der Ansage). */
    private const val BLADE_CHORD = 0.97
    private const val BLADE_MIN_HALF = 0.3
    private const val BLADE_TRAIL = 2.8
    private const val BLADE_MIN_TRAIL = 0.15
    private const val BLADE_VEIL_ALPHA = 0.26f
    private const val BLADE_EDGE_WIDTH = 0.2f
    private const val BLADE_EDGE_ALPHA = 0.7f

    // ---------------------------------------------------------------------------------------------------------------
    // bia.rush – Bias Sturm
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Staubspur: Wo Bia läuft, steigen in festen Abständen Staubwolken auf (Halbmesser höchstens 85 % der halben
     * Linienbreite, weg nach [RUSH_PUFF_LIFE] Ticks), dazu ein kurzer schmaler Schweif hinter ihr, solange sie läuft.
     */
    private class RushFx(args: FxArgs) : FxEffect(args.life.coerceIn(RUSH_MIN_LIFE, RUSH_MAX_LIFE)) {
        private class Puff(val at: Vec3d, val born: Float, val spin: Float)

        private val bia = args.entity
        private val halfWidth = (args.a.coerceIn(0.5f, 6f) * 0.5f)
        private val run = args.b.coerceIn(1f, life.toFloat())
        private val palette = args.palette
        private val seed = args.seed.toInt()
        private val rnd = Random(args.seed)
        private val puffs = ArrayList<Puff>()
        private val streak = ArrayDeque<Vec3d>()
        private var last: Vec3d? = null

        override fun tick(world: ClientWorld) {
            val e = bia?.takeIf { !it.isRemoved } ?: return
            if (age > run + 1) return
            val p = e.entityPos
            streak.addLast(p.add(0.0, RUSH_STREAK_UP, 0.0))
            while (streak.size > RUSH_STREAK_POINTS) streak.removeFirst()
            val from = last
            if (from == null) {
                addPuff(world, p)
                last = p
                return
            }
            val gap = from.distanceTo(p)
            if (gap < RUSH_PUFF_STEP) return
            val steps = (gap / RUSH_PUFF_STEP).toInt().coerceIn(1, RUSH_PUFF_FILL)
            for (i in 1..steps) addPuff(world, from.lerp(p, i.toDouble() / steps))
            last = p
        }

        private fun addPuff(world: ClientWorld, p: Vec3d) {
            if (puffs.size >= RUSH_PUFF_MAX) return
            val gy = FxLib.groundY(world, p.x, p.y + 0.5, p.z)
            puffs += Puff(Vec3d(p.x, gy + DUST_LIFT, p.z), age.toFloat(), rnd.nextFloat() * 6.28f)
        }

        override fun energy(f: FxFrame) {
            val t = time(f)
            for ((i, puff) in puffs.withIndex()) {
                val a = t - puff.born
                if (a < 0f || a > RUSH_PUFF_LIFE) continue
                val k = a / RUSH_PUFF_LIFE
                val rad = min(halfWidth * RUSH_PUFF_SHARE, RUSH_PUFF_R0 + RUSH_PUFF_GROW * Ease.outQuad(k))
                val alpha = RUSH_PUFF_ALPHA * (1f - k).pow(1.5f) * Ease.inOut(a / RUSH_PUFF_IN)
                FxDraw.flat(f, puff.at, UP, rad, FxLib.alpha(DUST, alpha), FxMode.DUST, rotation = puff.spin + a * 0.02f, progress = 0.8f - 0.4f * k, seed = seed + i)
            }
            val e = bia?.takeIf { !it.isRemoved } ?: return
            if (t > run + RUSH_STREAK_AFTER || streak.size < 2) return
            val fade = 1f - Ease.inOut((t - run) / RUSH_STREAK_AFTER)
            val pts = ArrayList<Vec3d>(streak.size + 1)
            pts.addAll(streak)
            pts.add(e.getLerpedPos(f.tickDelta).add(0.0, RUSH_STREAK_UP, 0.0))
            FxDraw.ribbon(f, pts, width = { u -> RUSH_STREAK_WIDTH * u }, color = { u -> FxLib.alpha(palette.main, RUSH_STREAK_ALPHA * u * fade) },
                mode = FxMode.RIBBON, seed = seed)
        }
    }

    private const val RUSH_MIN_LIFE = 8
    private const val RUSH_MAX_LIFE = 80
    private const val RUSH_PUFF_STEP = 0.9
    private const val RUSH_PUFF_FILL = 6
    private const val RUSH_PUFF_MAX = 48
    private const val RUSH_PUFF_LIFE = 12f
    private const val RUSH_PUFF_IN = 2f
    private const val RUSH_PUFF_R0 = 0.5f
    private const val RUSH_PUFF_GROW = 0.5f
    /** Staubwolken höchstens so viel der halben Linienbreite (die Linie wird nie breiter, als ihre Ansage war). */
    private const val RUSH_PUFF_SHARE = 0.85f
    private const val RUSH_PUFF_ALPHA = 0.42f
    private const val RUSH_STREAK_UP = 1.1
    private const val RUSH_STREAK_POINTS = 5
    private const val RUSH_STREAK_WIDTH = 0.22f
    private const val RUSH_STREAK_ALPHA = 0.35f
    private const val RUSH_STREAK_AFTER = 4f

    // ---------------------------------------------------------------------------------------------------------------
    // bia.awake – die Statue erwacht (bzw. erstarrt wieder)
    // ---------------------------------------------------------------------------------------------------------------

    private fun awake(args: FxArgs): VfxEffect {
        val p = args.pos
        val bia = args.world.getEntitiesByClass(BiaEntity::class.java, Box(p.x - 3.0, p.y - 3.0, p.z - 3.0, p.x + 3.0, p.y + 5.0, p.z + 3.0)) { it.isAlive }
            .minByOrNull { it.squaredDistanceTo(p) }
        val petrify = args.b >= 0.5f
        if (bia != null) pulse(bia, if (petrify) PETRIFY_PULSE_TICKS else AWAKE_PULSE_TICKS, if (petrify) PETRIFY_PULSE else AWAKE_PULSE)
        if (!petrify && FxLib.enabled()) {
            val feet = bia?.entityPos ?: p
            BlockDebris.burst(args.world, feet, AWAKE_DEBRIS_INNER, AWAKE_DEBRIS_OUTER, AWAKE_BLOCKS, AWAKE_DEBRIS_UP, AWAKE_DEBRIS_OUT)
        }
        return AwakeFx(args, bia, petrify)
    }

    /**
     * Ein glühender Riss läuft von den Füßen über den Körper zum Kopf (liegendes Band im Modus `CRACK` vor ihrer
     * Vorderseite), Steinbrocken platzen ab und fallen (feste Meshes im Stein-Modus), Staub am Sockel. Erstarren
     * ([petrify]): der Riss ist ganz da, kühlt aus und schließt sich, kalter Staub, keine Brocken.
     */
    private class AwakeFx(args: FxArgs, bia: BiaEntity?, private val petrify: Boolean) : FxEffect(args.life.coerceIn(AWAKE_MIN_LIFE, AWAKE_MAX_LIFE)) {
        private class Chip(val start: Vec3d, val v: Vec3d, val size: Float, val spin: Float)

        private val feet = bia?.entityPos ?: args.pos
        private val facing: Vec3d
        private val points: List<Vec3d>
        private val fractions: FloatArray
        private val chips: List<Chip>
        private val palette = args.palette
        private val seed = args.seed.toInt()

        init {
            val yaw = Math.toRadians((bia?.bodyYaw ?: 0f).toDouble())
            facing = Vec3d(-sin(yaw), 0.0, cos(yaw))
            val right = Vec3d(-cos(yaw), 0.0, -sin(yaw))
            val height = (bia?.height ?: AWAKE_DEFAULT_HEIGHT).toDouble()
            val rnd = Random(args.seed)
            points = List(AWAKE_CRACK_POINTS) { i ->
                val k = i / (AWAKE_CRACK_POINTS - 1.0)
                val jitter = if (i == 0) 0.0 else (if (i % 2 == 0) 1.0 else -1.0) * AWAKE_JITTER * (0.5 + rnd.nextDouble())
                feet.add(facing.multiply(AWAKE_FRONT)).add(right.multiply(jitter + AWAKE_LEAN * k)).add(0.0, AWAKE_FOOT + (height * AWAKE_TOP - AWAKE_FOOT) * k, 0.0)
            }
            fractions = FxLib.arcFractions(points)
            chips = if (petrify) emptyList() else List(AWAKE_CHIPS) {
                val h = AWAKE_CHIP_LOW + rnd.nextDouble() * (height * AWAKE_TOP - AWAKE_CHIP_LOW)
                val s = if (rnd.nextBoolean()) 1.0 else -1.0
                val start = feet.add(facing.multiply(AWAKE_CHIP_FRONT)).add(right.multiply(s * AWAKE_CHIP_SIDE * rnd.nextDouble())).add(0.0, h, 0.0)
                val speed = AWAKE_CHIP_SPEED * (0.8 + 0.5 * rnd.nextDouble())
                val v = facing.multiply(speed).add(right.multiply(s * speed * 0.6 * rnd.nextDouble())).add(0.0, AWAKE_CHIP_UP * rnd.nextDouble(), 0.0)
                Chip(start, v, AWAKE_CHIP_MIN + rnd.nextFloat() * AWAKE_CHIP_RANGE, rnd.nextFloat() * 6.28f)
            }
        }

        private fun ending(t: Float): Float = 1f - Ease.inOut((t - (life - AWAKE_FADE_OUT)) / AWAKE_FADE_OUT)

        override fun solid(f: FxFrame) {
            if (chips.isEmpty()) return
            val t = time(f)
            val shrink = ending(t)
            if (shrink <= 0.02f) return
            for ((i, c) in chips.withIndex()) {
                val y = c.start.y + c.v.y * t - 0.5 * AWAKE_CHIP_GRAVITY * t * t
                val landed = y <= feet.y + c.size
                val tt = if (landed) landTime(c) else t
                val at = Vec3d(c.start.x + c.v.x * tt, if (landed) feet.y + c.size * 0.6 else y, c.start.z + c.v.z * tt)
                val s = c.size * shrink
                val m = FxDraw.place(at, up = UP, spin = c.spin + tt * AWAKE_CHIP_SPIN, scaleXYZ = Vector3f(s * 1.2f, s * 0.8f, s))
                FxDraw.mesh(f, FxMeshes.SPHERE, m, STONE, FxSolidMode.STONE, solid = true, seed = seed + i)
            }
        }

        /** Wann ein Brocken den Sockel erreicht (Lösung der Wurfparabel). */
        private fun landTime(c: Chip): Float {
            val drop = c.start.y - (feet.y + c.size)
            val g = AWAKE_CHIP_GRAVITY.toDouble()
            return ((c.v.y + sqrt(c.v.y * c.v.y + 2.0 * g * drop.coerceAtLeast(0.0))) / g).toFloat()
        }

        override fun energy(f: FxFrame) {
            val t = time(f)
            val end = ending(t)
            if (end <= 0.01f) return
            val grow = if (petrify) 1f else Ease.outCubic(t / AWAKE_GROW_TICKS)
            val heat = if (petrify) PETRIFY_HEAT * (1f - Ease.inOut(t / PETRIFY_COOL_TICKS))
            else 1f - AWAKE_COOL * Ease.inOut((t - AWAKE_GROW_TICKS) / AWAKE_COOL_TICKS)
            val pts = FxLib.partial(points, fractions, grow)
            if (pts.size >= 2) {
                FxDraw.strip(f, pts, facing, width = { _ -> AWAKE_GLOW_WIDTH }, color = { _ -> FxLib.alpha(palette.main, AWAKE_GLOW_ALPHA * end * heat) },
                    mode = FxMode.CRACK, seed = seed, progress = heat)
                FxDraw.strip(f, pts, facing, width = { u -> AWAKE_CORE_WIDTH * (0.5f + 0.5f * edge(u)) },
                    color = { _ -> FxLib.alpha(FxLib.mix(palette.main, palette.core, heat), AWAKE_CORE_ALPHA * end) }, mode = FxMode.CRACK, seed = seed + 1, progress = heat)
            }
            val dk = (t / AWAKE_DUST_TICKS).coerceIn(0f, 1f)
            if (dk < 1f) {
                FxDraw.flat(f, feet.add(0.0, DUST_LIFT, 0.0), UP, AWAKE_DUST_R0 + AWAKE_DUST_GROW * Ease.outQuad(dk),
                    FxLib.alpha(if (petrify) DUST_COLD else DUST, AWAKE_DUST_ALPHA * (1f - dk).pow(1.5f)), FxMode.DUST, rotation = seed * 0.29f, progress = 0.8f, seed = seed)
            }
        }

        override fun over(f: FxFrame) {
            if (petrify) return
            val t = time(f)
            if (t > AWAKE_GROW_TICKS) return
            val tip = FxLib.partial(points, fractions, Ease.outCubic(t / AWAKE_GROW_TICKS)).last()
            FxDraw.glow(f, tip, AWAKE_TIP_SIZE, FxLib.alpha(palette.core, AWAKE_TIP_ALPHA * (1f - t / AWAKE_GROW_TICKS)), seed)
        }
    }

    private const val AWAKE_MIN_LIFE = 20
    private const val AWAKE_MAX_LIFE = 120
    private const val AWAKE_DEFAULT_HEIGHT = 4f
    private const val AWAKE_CRACK_POINTS = 11
    /** Der Riss liegt so weit vor ihrer Mitte (vor der Vorderseite), vom Fuß bis zu diesem Anteil ihrer Höhe. */
    private const val AWAKE_FRONT = 0.5
    private const val AWAKE_FOOT = 0.25
    private const val AWAKE_TOP = 0.9
    private const val AWAKE_JITTER = 0.12
    private const val AWAKE_LEAN = 0.18
    private const val AWAKE_GROW_TICKS = 14f
    private const val AWAKE_COOL = 0.6f
    private const val AWAKE_COOL_TICKS = 30f
    private const val AWAKE_FADE_OUT = 12f
    private const val AWAKE_GLOW_WIDTH = 0.16f
    private const val AWAKE_GLOW_ALPHA = 0.35f
    private const val AWAKE_CORE_WIDTH = 0.06f
    private const val AWAKE_CORE_ALPHA = 0.95f
    private const val AWAKE_TIP_SIZE = 0.32f
    private const val AWAKE_TIP_ALPHA = 0.5f
    private const val AWAKE_DUST_TICKS = 30f
    private const val AWAKE_DUST_R0 = 1.1f
    private const val AWAKE_DUST_GROW = 0.6f
    private const val AWAKE_DUST_ALPHA = 0.32f
    private const val AWAKE_CHIPS = 6
    private const val AWAKE_CHIP_LOW = 0.8
    private const val AWAKE_CHIP_FRONT = 0.32
    private const val AWAKE_CHIP_SIDE = 0.4
    private const val AWAKE_CHIP_SPEED = 0.07
    private const val AWAKE_CHIP_UP = 0.08
    private const val AWAKE_CHIP_GRAVITY = 0.035f
    private const val AWAKE_CHIP_SPIN = 0.2f
    private const val AWAKE_CHIP_MIN = 0.11f
    private const val AWAKE_CHIP_RANGE = 0.1f
    private const val STONE = 0xFFB9B2A6.toInt()
    /** Echte Blöcke ihres Sockels (Ring um die Füße, innerhalb der 3 × 3 Oberseite). */
    private const val AWAKE_BLOCKS = 4
    private const val AWAKE_DEBRIS_INNER = 0.6f
    private const val AWAKE_DEBRIS_OUTER = 1.35f
    private const val AWAKE_DEBRIS_UP = 0.35f
    private const val AWAKE_DEBRIS_OUT = 0.07f
    private const val AWAKE_PULSE_TICKS = 50f
    private const val AWAKE_PULSE = 0.5f
    private const val PETRIFY_PULSE_TICKS = 80f
    private const val PETRIFY_PULSE = -0.35f
    private const val PETRIFY_HEAT = 0.8f
    private const val PETRIFY_COOL_TICKS = 40f

    // ---------------------------------------------------------------------------------------------------------------
    // Überarbeitung 4 (P17): kratos.bia_open – Bia taumelt und ist verwundbar (Gold = offen)
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Goldene Risse leuchten über Vorder- und Rückseite der Statue, so lange sie verwundbar ist (`life` = Fenster).
     * Ruhiges Atmen mit [OPEN_BREATH_HZ] (unter 2 Hz), weich ein- und ausgeblendet, dazu ein schwacher Schein an der
     * Brust. Folgt ihr (`entity`); ist sie fort, endet das Bild. Farbe: Palette des Aufrufs (Server schickt `GOLD`).
     */
    private class BiaOpenFx(args: FxArgs) : FxEffect(args.life.coerceIn(OPEN_MIN_LIFE, OPEN_MAX_LIFE)) {
        private val bia = args.entity
        private val fallback = args.pos
        private val palette = args.palette
        private val seed = args.seed.toInt()
        /** Zwei Risse (vorn, hinten) als Bruchteile ihrer Höhe und seitliche Versätze, fest je Aufruf. */
        private val jitter: DoubleArray

        init {
            val rnd = Random(args.seed)
            jitter = DoubleArray(OPEN_POINTS * 2) { (if (it % 2 == 0) 1.0 else -1.0) * OPEN_JITTER * (0.4 + rnd.nextDouble()) }
        }

        override fun energy(f: FxFrame) {
            val e = bia
            if (e != null && e.isRemoved) return
            val t = time(f)
            val fade = Ease.inOut((t / OPEN_FADE_IN).coerceIn(0f, 1f)) * (1f - Ease.inOut(((t - (life - OPEN_FADE_OUT)) / OPEN_FADE_OUT).coerceIn(0f, 1f)))
            if (fade <= 0.01f) return
            val breath = OPEN_BREATH_LOW + (1f - OPEN_BREATH_LOW) * (0.5f + 0.5f * sin(t / 20f * 2f * PI.toFloat() * OPEN_BREATH_HZ))
            val feet = e?.getLerpedPos(f.tickDelta) ?: fallback
            val yaw = Math.toRadians(((e as? BiaEntity)?.bodyYaw ?: 0f).toDouble())
            val facing = Vec3d(-sin(yaw), 0.0, cos(yaw))
            val right = Vec3d(-cos(yaw), 0.0, -sin(yaw))
            val height = (e?.height ?: AWAKE_DEFAULT_HEIGHT).toDouble()
            for (side in 0..1) {
                val normal = if (side == 0) facing else facing.multiply(-1.0)
                val pts = List(OPEN_POINTS) { i ->
                    val k = i / (OPEN_POINTS - 1.0)
                    feet.add(normal.multiply(OPEN_FRONT)).add(right.multiply(jitter[side * OPEN_POINTS + i] * (if (i == 0) 0.0 else 1.0)))
                        .add(0.0, OPEN_FOOT + (height * OPEN_TOP - OPEN_FOOT) * k, 0.0)
                }
                FxDraw.strip(f, pts, normal, width = { _ -> OPEN_GLOW_WIDTH }, color = { _ -> FxLib.alpha(palette.main, OPEN_GLOW_ALPHA * fade * breath) },
                    mode = FxMode.CRACK, seed = seed + side, progress = 1f)
                FxDraw.strip(f, pts, normal, width = { u -> OPEN_CORE_WIDTH * (0.5f + 0.5f * edge(u)) },
                    color = { _ -> FxLib.alpha(palette.core, OPEN_CORE_ALPHA * fade * breath) }, mode = FxMode.CRACK, seed = seed + 2 + side, progress = 1f)
            }
            FxDraw.glow(f, feet.add(0.0, height * OPEN_GLOW_UP, 0.0), OPEN_HALO_SIZE, FxLib.alpha(palette.main, OPEN_HALO_ALPHA * fade * breath), seed)
        }
    }

    private const val OPEN_MIN_LIFE = 10
    private const val OPEN_MAX_LIFE = 200
    private const val OPEN_POINTS = 9
    private const val OPEN_JITTER = 0.14
    private const val OPEN_FRONT = 0.48
    private const val OPEN_FOOT = 0.3
    private const val OPEN_TOP = 0.85
    private const val OPEN_FADE_IN = 6f
    private const val OPEN_FADE_OUT = 8f
    /** Atmen: so oft je Sekunde (ruhig, deutlich unter 2 Hz), nie dunkler als dieser Anteil. */
    private const val OPEN_BREATH_HZ = 0.8f
    private const val OPEN_BREATH_LOW = 0.55f
    private const val OPEN_GLOW_WIDTH = 0.18f
    private const val OPEN_GLOW_ALPHA = 0.4f
    private const val OPEN_CORE_WIDTH = 0.07f
    private const val OPEN_CORE_ALPHA = 0.95f
    private const val OPEN_GLOW_UP = 0.55
    private const val OPEN_HALO_SIZE = 1.1f
    private const val OPEN_HALO_ALPHA = 0.18f

    // ---------------------------------------------------------------------------------------------------------------
    // Überarbeitung 4 (P17): kratos.bia_break – die Statue zerspringt
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Bias Bruch: ein Rissnetz glüht in einem Augenblick auf (Gefahrenfarbe), dann fliegen große Steinbrocken nach außen
     * und fallen, Staubkuppel am Boden, echte Blöcke springen. `dir` = weg von Kratos (die meisten Brocken fliegen so).
     * Bia selbst entfernt der Server zwei Ticks später; das Bild steht an ihren Füßen (`pos`).
     */
    private fun breakFx(args: FxArgs): VfxEffect {
        if (FxLib.enabled()) BlockDebris.burst(args.world, args.pos, BREAK_DEBRIS_INNER, BREAK_DEBRIS_OUTER, BREAK_BLOCKS, BREAK_DEBRIS_UP, BREAK_DEBRIS_OUT)
        thud(args.world, args.pos, BREAK_THUD_VOLUME, BREAK_THUD_PITCH)
        return BiaBreakFx(args)
    }

    private class BiaBreakFx(args: FxArgs) : FxEffect(args.life.coerceIn(BREAK_MIN_LIFE, BREAK_MAX_LIFE)) {
        private class Chip(val start: Vec3d, val v: Vec3d, val size: Float, val spin: Float)

        private val feet = args.pos
        private val palette = args.palette
        private val seed = args.seed.toInt()
        private val height = ((args.entity as? BiaEntity)?.height ?: AWAKE_DEFAULT_HEIGHT).toDouble()
        private val cracks: List<List<Vec3d>>
        private val chips: List<Chip>

        init {
            val rnd = Random(args.seed)
            val away = Vec3d(args.dir.x, 0.0, args.dir.z).let { if (it.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
            val side = Vec3d(-away.z, 0.0, away.x)
            // Ein Stamm-Riss je Seite um den Körper, schräg nach oben, mit Zacken.
            cracks = List(BREAK_CRACKS) { c ->
                val a = c * 2.0 * PI / BREAK_CRACKS + rnd.nextDouble() * 0.4
                val n = Vec3d(cos(a), 0.0, sin(a))
                List(BREAK_CRACK_POINTS) { i ->
                    val k = i / (BREAK_CRACK_POINTS - 1.0)
                    val wobble = (if (i % 2 == 0) 1.0 else -1.0) * 0.1 * rnd.nextDouble()
                    feet.add(n.multiply(BREAK_CRACK_OUT + wobble)).add(0.0, BREAK_CRACK_FOOT + (height * BREAK_CRACK_TOP - BREAK_CRACK_FOOT) * k, 0.0)
                }
            }
            chips = List(BREAK_CHIPS) {
                val h = BREAK_CHIP_LOW + rnd.nextDouble() * (height * 0.9 - BREAK_CHIP_LOW)
                val ang = (rnd.nextDouble() - 0.5) * PI * 1.6
                val out = away.multiply(cos(ang)).add(side.multiply(sin(ang)))
                val speed = BREAK_CHIP_SPEED * (0.7 + 0.6 * rnd.nextDouble())
                val start = feet.add(out.multiply(0.3)).add(0.0, h, 0.0)
                Chip(start, out.multiply(speed).add(0.0, BREAK_CHIP_UP * (0.4 + rnd.nextDouble()), 0.0), BREAK_CHIP_MIN + rnd.nextFloat() * BREAK_CHIP_RANGE, rnd.nextFloat() * 6.28f)
            }
        }

        private fun ending(t: Float): Float = 1f - Ease.inOut((t - (life - BREAK_FADE_OUT)) / BREAK_FADE_OUT)

        override fun solid(f: FxFrame) {
            val t = time(f)
            if (t < BREAK_BURST) return
            val tt = t - BREAK_BURST
            val shrink = ending(t)
            if (shrink <= 0.02f) return
            for ((i, c) in chips.withIndex()) {
                val y = c.start.y + c.v.y * tt - 0.5 * BREAK_CHIP_GRAVITY * tt * tt
                val floor = feet.y + c.size * 0.5
                val landed = y <= floor
                val at = if (landed) {
                    val lt = landTime(c, floor)
                    Vec3d(c.start.x + c.v.x * lt, floor, c.start.z + c.v.z * lt)
                } else {
                    Vec3d(c.start.x + c.v.x * tt, y, c.start.z + c.v.z * tt)
                }
                val s = c.size * shrink
                val m = FxDraw.place(at, up = UP, spin = c.spin + (if (landed) landTime(c, floor) else tt) * BREAK_CHIP_SPIN, scaleXYZ = Vector3f(s * 1.25f, s * 0.85f, s))
                FxDraw.mesh(f, FxMeshes.SPHERE, m, STONE, FxSolidMode.STONE, solid = true, seed = seed + i)
            }
        }

        private fun landTime(c: Chip, floor: Double): Float {
            val drop = c.start.y - floor
            val g = BREAK_CHIP_GRAVITY.toDouble()
            return ((c.v.y + sqrt(c.v.y * c.v.y + 2.0 * g * drop.coerceAtLeast(0.0))) / g).toFloat()
        }

        override fun energy(f: FxFrame) {
            val t = time(f)
            // Rissnetz: wächst bis zum Bersten, glüht kurz nach und erlischt.
            val grow = Ease.outCubic((t / BREAK_BURST).coerceIn(0f, 1f))
            val heat = 1f - Ease.inOut(((t - BREAK_BURST) / BREAK_COOL).coerceIn(0f, 1f))
            if (heat > 0.01f) {
                for ((ci, pts) in cracks.withIndex()) {
                    val shown = FxLib.partial(pts, FxLib.arcFractions(pts), grow)
                    if (shown.size < 2) continue
                    val normal = Vec3d(pts[0].x - feet.x, 0.0, pts[0].z - feet.z).let { if (it.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
                    FxDraw.strip(f, shown, normal, width = { _ -> BREAK_GLOW_WIDTH }, color = { _ -> FxLib.alpha(palette.main, BREAK_GLOW_ALPHA * heat) },
                        mode = FxMode.CRACK, seed = seed + ci, progress = heat)
                    FxDraw.strip(f, shown, normal, width = { u -> BREAK_CORE_WIDTH * (0.5f + 0.5f * edge(u)) },
                        color = { _ -> FxLib.alpha(FxLib.mix(palette.main, palette.core, heat), BREAK_CORE_ALPHA * heat) }, mode = FxMode.CRACK, seed = seed + 20 + ci, progress = heat)
                }
            }
            // Staub: eine flache Kuppel am Boden nach dem Bersten.
            val dk = ((t - BREAK_BURST) / BREAK_DUST_TICKS).coerceIn(0f, 1f)
            if (t >= BREAK_BURST && dk < 1f) {
                FxDraw.flat(f, feet.add(0.0, DUST_LIFT, 0.0), UP, BREAK_DUST_R0 + BREAK_DUST_GROW * Ease.outQuad(dk),
                    FxLib.alpha(DUST, BREAK_DUST_ALPHA * (1f - dk).pow(1.4f)), FxMode.DUST, rotation = seed * 0.31f, progress = 0.8f, seed = seed)
            }
        }

        override fun over(f: FxFrame) {
            val t = time(f)
            if (t > BREAK_BURST + BREAK_FLASH) return
            val k = (t / (BREAK_BURST + BREAK_FLASH)).coerceIn(0f, 1f)
            FxDraw.glow(f, feet.add(0.0, height * 0.55, 0.0), BREAK_FLASH_SIZE, FxLib.alpha(palette.core, BREAK_FLASH_ALPHA * Ease.bump(k)), seed)
        }
    }

    private const val BREAK_MIN_LIFE = 30
    private const val BREAK_MAX_LIFE = 120
    /** Das Rissnetz wächst so lange, dann birst die Statue. */
    private const val BREAK_BURST = 6f
    private const val BREAK_COOL = 14f
    private const val BREAK_FLASH = 4f
    private const val BREAK_FLASH_SIZE = 1.6f
    private const val BREAK_FLASH_ALPHA = 0.55f
    private const val BREAK_FADE_OUT = 14f
    private const val BREAK_CRACKS = 4
    private const val BREAK_CRACK_POINTS = 8
    private const val BREAK_CRACK_OUT = 0.5
    private const val BREAK_CRACK_FOOT = 0.2
    private const val BREAK_CRACK_TOP = 0.92
    private const val BREAK_GLOW_WIDTH = 0.2f
    private const val BREAK_GLOW_ALPHA = 0.4f
    private const val BREAK_CORE_WIDTH = 0.07f
    private const val BREAK_CORE_ALPHA = 0.95f
    private const val BREAK_CHIPS = 14
    private const val BREAK_CHIP_LOW = 0.4
    private const val BREAK_CHIP_SPEED = 0.14
    private const val BREAK_CHIP_UP = 0.12
    private const val BREAK_CHIP_GRAVITY = 0.04f
    private const val BREAK_CHIP_SPIN = 0.25f
    private const val BREAK_CHIP_MIN = 0.16f
    private const val BREAK_CHIP_RANGE = 0.18f
    private const val BREAK_DUST_TICKS = 34f
    private const val BREAK_DUST_R0 = 1.4f
    private const val BREAK_DUST_GROW = 1.4f
    private const val BREAK_DUST_ALPHA = 0.38f
    private const val BREAK_BLOCKS = 6
    private const val BREAK_DEBRIS_INNER = 0.5f
    private const val BREAK_DEBRIS_OUTER = 1.6f
    private const val BREAK_DEBRIS_UP = 0.45f
    private const val BREAK_DEBRIS_OUT = 0.1f
    private const val BREAK_THUD_VOLUME = 0.9f
    private const val BREAK_THUD_PITCH = 0.75f
}
