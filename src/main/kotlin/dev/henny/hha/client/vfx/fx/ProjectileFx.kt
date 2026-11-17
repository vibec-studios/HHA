package dev.henny.hha.client.vfx.fx

import dev.henny.hha.client.anim.Ease
import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.VfxManager
import dev.henny.hha.client.vfx.fx.lib.FxLib
import dev.henny.hha.client.vfx.fx.lib.SparkSet
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.logic.boss.BossFx.Look
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import org.joml.Matrix4f
import kotlin.math.cos
import kotlin.math.sin

/**
 * Mantel und Spur an einem echten Server-Projektil (REF T6, Vorbild Void `MeteorEffect`, `VoidEffects.kt:357-423`;
 * `core.projectile`). Spur = die letzten [TRAIL] Lagen, je [Look] ein eigenes Bild: Staubfahne, Glutspur, Flammenband,
 * Gifttropfen, Blitzspur, Seelenschweif. Verschwindet das Wesen, folgt am letzten Ort ein kleiner eigener Einschlag
 * (bei `impactRadius > 0`: Schein, Stoßring, Funken). Das Wesen selbst zeichnet sein Renderer weiter.
 * Je Wesen höchstens ein Mantel; der Effekt läuft im `VfxManager`, [tick] räumt nur die Liste auf.
 */
object ProjectileFx {

    const val TRAIL = 14
    /** Kennt der Client das Wesen noch nicht, sucht er es so viele Ticks bei der Startlage. */
    const val SEARCH_TICKS = 10
    const val SEARCH_RADIUS = 2.0

    private val attached = HashMap<Int, Mantle>()

    /** Hängt das Bild [look] an das Wesen [entityId]. */
    fun attach(world: ClientWorld, entityId: Int, look: BossFx.Look, palette: Palette, impactRadius: Float = 0f) {
        val e = world.getEntityById(entityId) ?: return
        start(e, e.entityPos, look, palette, impactRadius, BossFx.PROJECTILE_MAX_LIFE)
    }

    /** Fabrik von `core.projectile` (pos = Lage, a = impactRadius, b = Look, life, entity). */
    internal fun fromArgs(args: FxArgs) {
        val look = Look.entries.getOrNull(args.b.toInt()) ?: Look.ROCK_DUST
        start(args.entity, args.pos, look, args.palette, args.a.coerceIn(0f, 12f), args.life, args.entityId)
    }

    private fun start(entity: Entity?, pos: Vec3d, look: Look, palette: Palette, impactRadius: Float, life: Int, entityId: Int = -1) {
        if (!FxLib.enabled()) return
        if (entity != null && attached[entity.id]?.running == true) return
        val m = Mantle(entity, pos, look, palette, impactRadius, life.coerceIn(2, BossFx.PROJECTILE_MAX_LIFE), entityId)
        entity?.let { attached[it.id] = m }
        VfxManager.addEffect(m)
    }

    /** Je Client-Tick (aus `FxClient.tick`): beendete Mäntel vergessen. */
    fun tick(world: ClientWorld) {
        attached.values.removeIf { !it.running }
    }

    /** Verbindungsende (aus `FxClient.reset`). */
    fun clear() {
        attached.clear()
    }

    /** Der Dauer-Mantel eines Projektils. */
    private class Mantle(
        private var entity: Entity?, private var lastPos: Vec3d, private val look: Look, private val palette: Palette,
        private val impactRadius: Float, life: Int, private val entityId: Int = -1,
    ) : FxEffect(life) {
        private val trail = ArrayDeque<Vec3d>()
        private var done = false
        private val seed = (entity?.id ?: lastPos.hashCode()) and 0xFFFF
        var running = true
            private set

        override fun finished(): Boolean = done

        override fun tick(world: ClientWorld) {
            val e = entity ?: find(world)
            if (e == null) {
                if (age > SEARCH_TICKS) end(world, false)
                return
            }
            if (e.isRemoved) {
                end(world, true)
                return
            }
            lastPos = e.entityPos
            trail.addLast(lastPos)
            while (trail.size > TRAIL) trail.removeFirst()
        }

        /** Noch nicht bekanntes Wesen: zuerst über die mitgeschickte Netz-ID, sonst das jüngste Nicht-Spieler-Wesen nahe der Startlage. */
        private fun find(world: ClientWorld): Entity? {
            if (entityId >= 0) {
                world.getEntityById(entityId)?.let { entity = it; return it }
            }
            val found = world.getOtherEntities(null, Box.of(lastPos, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2, SEARCH_RADIUS * 2)) {
                it !is PlayerEntity && it.age <= SEARCH_TICKS + 2
            }.minByOrNull { it.squaredDistanceTo(lastPos) } ?: return null
            entity = found
            return found
        }

        private fun end(world: ClientWorld, impact: Boolean) {
            if (done) return
            done = true
            running = false
            if (impact && impactRadius > 0f) VfxManager.addEffect(Impact(lastPos, impactRadius, palette, look, seed.toLong()))
        }

        private fun current(tickDelta: Float): Vec3d = entity?.takeIf { !it.isRemoved }?.getLerpedPos(tickDelta) ?: lastPos

        override fun energy(f: FxFrame) {
            if (entity == null || done) return
            val t = time(f)
            val head = current(f.tickDelta).add(0.0, (entity?.height ?: 0f) * 0.5, 0.0)
            val lift = Vec3d(0.0, (entity?.height ?: 0f) * 0.5, 0.0)
            val pts = ArrayList<Vec3d>(trail.size + 1)
            for (p in trail) pts += p.add(lift)
            pts += head
            if (pts.size >= 2) ProjectileLooks.trail(f, look, palette, pts, t, seed)
            ProjectileLooks.mantle(f, look, palette, head, t, seed)
        }
    }

    /** Kleiner eigener Einschlag am letzten Ort: Schein, Stoßring, Funken. */
    private class Impact(private val at: Vec3d, private val radius: Float, private val palette: Palette, private val look: Look, seed: Long) :
        FxEffect(IMPACT_LIFE) {
        private val sparks = SparkSet(IMPACT_SPARKS, 0.25f + radius * 0.06f, FxLib.UP, seed)
        private val s = seed.toInt()

        override fun energy(f: FxFrame) {
            val t = time(f)
            val k = k(f)
            val r = radius * (0.3f + 1.2f * Ease.outCubic(k))
            val ground = Vec3d(at.x, at.y + 0.05, at.z)
            val mode = if (look == Look.ROCK_DUST) FxMode.DUST else FxMode.SHOCK
            FxDraw.flat(f, ground, FxLib.UP, r, FxLib.alpha(palette.main, 0.55f * (1f - k)), mode, inner = r * 0.7f, progress = k, seed = s)
            sparks.draw(f, at.add(0.0, 0.2, 0.0), t, life.toFloat(), 1f, palette)
        }

        override fun over(f: FxFrame) {
            val fade = 1f - k(f)
            if (look == Look.ROCK_DUST) return
            FxDraw.glow(f, at.add(0.0, 0.3, 0.0), radius * 1.1f * (0.5f + 0.5f * fade), FxLib.alpha(palette.core, 0.6f * fade * fade * fade), s)
        }
    }

    const val IMPACT_LIFE = 14
    const val IMPACT_SPARKS = 8
}

/** Die sechs Bilder (Spur und Mantel) je [Look]; eine Funktion je Bild, alle stetig über die Zeit. */
internal object ProjectileLooks {

    const val ROCK_DUST_COLOR = 0xFFB8AE9C.toInt()
    const val MANTLE_ALPHA = 0.4f
    /** Durchhängen der Giftspur (Blöcke je Tick²). */
    const val VENOM_SAG = 0.003

    fun trail(f: FxFrame, look: Look, p: Palette, pts: List<Vec3d>, t: Float, seed: Int) = when (look) {
        // Staubfahne: wird zum Ende breiter und blasser.
        Look.ROCK_DUST -> FxDraw.ribbon(
            f, pts, width = { s -> 0.25f + 0.6f * (1f - s) }, color = { s -> FxLib.alpha(FxLib.mix(ROCK_DUST_COLOR, p.edge, 0.2f), 0.35f * s) },
            mode = FxMode.DUST, scroll = t * 0.05f, seed = seed,
        )
        // Glutspur: schmal, Kern weißglühend zum Kopf.
        Look.HOT_IRON -> {
            FxDraw.ribbon(f, pts, width = { s -> 0.28f * s }, color = { s -> FxLib.alpha(FxLib.mix(p.edge, p.main, s), 0.75f * s) }, mode = FxMode.FLAME, seed = seed)
            FxDraw.ribbon(f, pts, width = { s -> 0.1f * s }, color = { s -> FxLib.alpha(p.core, 0.8f * s) }, mode = FxMode.RIBBON, seed = seed + 1)
        }
        // Flammenband: breit und weich, züngelt über den Shader.
        Look.TORCH_FLAME -> FxDraw.ribbon(
            f, pts, width = { s -> 0.5f * s * (0.8f + 0.2f * sin(t * 0.4f + s * 6f)) }, color = { s -> FxLib.alpha(FxLib.mix(p.edge, p.main, s), 0.7f * s) },
            mode = FxMode.FLAME, scroll = t * 0.12f, seed = seed,
        )
        // Gifttropfen: nasse Spur, die mit dem Alter jedes Punktes stetig nach unten durchhängt (tropft ab).
        Look.VENOM -> {
            val n = pts.size
            val sag = pts.mapIndexed { i, q ->
                val age = if (i == n - 1) 0f else (n - 2 - i) + f.tickDelta
                q.add(0.0, -VENOM_SAG * age * age, 0.0)
            }
            FxDraw.ribbon(f, sag, width = { s -> 0.14f * s }, color = { s -> FxLib.alpha(p.main, 0.6f * s) }, mode = FxMode.WATER, seed = seed)
            FxDraw.ribbon(f, sag, width = { s -> 0.05f * s }, color = { s -> FxLib.alpha(p.core, 0.5f * s) }, mode = FxMode.WATER, seed = seed + 1)
        }
        // Blitzspur: stetig zitternd, heller Kern.
        Look.KERAUNOS -> {
            val (u, v) = FxLib.basis(pts.last().subtract(pts.first()))
            val jag = pts.mapIndexed { i, q ->
                val w = 0.12f * (1f - i / pts.size.toFloat())
                q.add(u.multiply((Ease.wobble(t * 1.3f + i, seed.toFloat()) * w).toDouble())).add(v.multiply((Ease.wobble(t * 1.3f + i, seed + 3f) * w).toDouble()))
            }
            FxDraw.ribbon(f, jag, width = { s -> 0.2f * s }, color = { s -> FxLib.alpha(p.main, 0.8f * s) }, mode = FxMode.BOLT, seed = seed)
            FxDraw.ribbon(f, jag, width = { s -> 0.07f * s }, color = { s -> FxLib.alpha(p.core, 0.9f * s) }, mode = FxMode.BOLT, seed = seed + 1)
        }
        // Seelenschweif: zwei umeinander laufende Stränge.
        Look.SOUL -> repeat(2) { strand ->
            val (u, v) = FxLib.basis(pts.last().subtract(pts.first()))
            val helix = pts.mapIndexed { i, q ->
                val a = i * 0.7f - t * 0.35f + strand * Math.PI.toFloat()
                val r = 0.22f * (1f - i / pts.size.toFloat() * 0.3f)
                q.add(u.multiply((cos(a) * r).toDouble())).add(v.multiply((sin(a) * r).toDouble()))
            }
            FxDraw.ribbon(f, helix, width = { s -> 0.14f * s }, color = { s -> FxLib.alpha(FxLib.mix(p.main, p.core, s), 0.7f * s) }, mode = FxMode.RIBBON, seed = seed + strand)
        }
    }

    fun mantle(f: FxFrame, look: Look, p: Palette, at: Vec3d, t: Float, seed: Int) {
        val (size, mode) = when (look) {
            Look.ROCK_DUST -> return
            Look.VENOM -> 0.3f to FxMode.WATER
            Look.KERAUNOS -> 0.4f to FxMode.SHELL
            else -> 0.45f to FxMode.SHELL
        }
        val breath = 1f + 0.05f * sin(t * 0.3f + seed)
        val m = Matrix4f().translation(at.x.toFloat(), at.y.toFloat(), at.z.toFloat()).scale(size * breath)
        FxDraw.mesh(f, FxMeshes.SPHERE, m, FxLib.alpha(p.main, MANTLE_ALPHA), mode, seed = seed)
        FxDraw.glow(f, at, size * 2.2f, FxLib.alpha(p.main, 0.3f), seed)
    }
}
