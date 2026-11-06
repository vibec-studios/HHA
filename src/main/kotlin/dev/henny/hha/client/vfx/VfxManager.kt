package dev.henny.hha.client.vfx

import dev.henny.hha.client.HhaClientConfig
import dev.henny.hha.logic.Vfx
import dev.henny.hha.net.VfxPayload
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.Vec3d

/** Verwaltet alle laufenden 3D-Effekte: nimmt Pakete an, tickt und zeichnet sie. */
object VfxManager {

    /** Obergrenze, damit ein Spam-Server keinen Client in die Knie zwingt. */
    private const val MAX_EFFECTS = 256

    private val effects = ArrayList<VfxEffect>()
    private var seed = 0L

    fun tick(world: ClientWorld) {
        BossGlow.tick(world)
        PortalVfx.tick(world)
        val iterator = effects.iterator()
        while (iterator.hasNext()) {
            if (!iterator.next().advance(world)) iterator.remove()
        }
    }

    fun clear() {
        effects.clear()
    }

    /** Kameraschütteln und Blitz laufen immer; 3D-Meshes hängen am Client-Schalter `vfx_3d`. */
    fun handle(payload: VfxPayload, world: ClientWorld) {
        val kind = Vfx.Kind.entries.getOrNull(payload.kind) ?: return
        val style = Vfx.Style.entries.getOrNull(payload.style) ?: Vfx.Style.GOLD
        val pal = Palette.of(style)
        val pos = Vec3d(payload.x, payload.y, payload.z)
        val dir = Vec3d(payload.dx, payload.dy, payload.dz)
        val life = payload.life.coerceAtLeast(1)
        val a = payload.a
        val b = payload.b
        val e1 = if (payload.entity >= 0) world.getEntityById(payload.entity) else null
        val e2 = if (payload.entity2 >= 0) world.getEntityById(payload.entity2) else null
        seed++

        when (kind) {
            Vfx.Kind.SHAKE -> {
                val player = MinecraftClient.getInstance().player ?: return
                val falloff = (1.0 - player.entityPos.distanceTo(pos) / b.toDouble()).coerceIn(0.0, 1.0).toFloat()
                if (falloff > 0f) ScreenFx.shake(a * falloff, life)
                return
            }
            Vfx.Kind.SCREEN_FLASH -> {
                ScreenFx.flash(pal.main, a, life)
                return
            }
            else -> {}
        }
        if (!HhaClientConfig.enabled("vfx_3d")) return
        if (kind == Vfx.Kind.SKY_MOOD) {
            SkyMood.pulse(style, pos, a, b, life)
            return
        }

        val effect: VfxEffect? = when (kind) {
            Vfx.Kind.BEAM -> BeamEffect(pos, dir, pal, a.coerceAtLeast(0.1f), life)
            Vfx.Kind.SHOCKWAVE -> ShockwaveEffect(pos, pal, a, if (b > 0f) b else 2.4f, life, seed)
            Vfx.Kind.CRESCENT -> CrescentEffect(pos, dir, pal, a, b, life)
            Vfx.Kind.BOLT -> BoltEffect(pos, dir, pal, a.coerceAtLeast(0.1f), life, seed)
            Vfx.Kind.PILLAR -> PillarEffect(pos, pal, a, b, life)
            Vfx.Kind.TELEGRAPH_RING -> TelegraphRingEffect(pos, pal, a, life)
            Vfx.Kind.TELEGRAPH_LINE -> TelegraphLineEffect(pos, dir, pal, a, life)
            Vfx.Kind.SHIELD -> ShieldEffect(e1, pos, pal, a, life, seed)
            Vfx.Kind.WINGS -> e1?.let { WingsEffect(it, pal, a, life, style == Vfx.Style.HELL || style == Vfx.Style.FIRE) }
            Vfx.Kind.CHAIN -> ChainEffect(e1, pos, e2, dir, pal, life)
            Vfx.Kind.TRAIL -> e1?.let { TrailEffect(it, pal, a, life) }
            Vfx.Kind.RIFT -> RiftEffect(pos, dir, pal, a, life)
            Vfx.Kind.NOVA -> NovaEffect(pos, pal, a, life)
            Vfx.Kind.RUNE -> RuneEffect(pos, dir, pal, a, life, e1)
            Vfx.Kind.TORNADO -> TornadoEffect(pos, dir, pal, a, b, life)
            Vfx.Kind.AURA -> e1?.let { AuraEffect(it, pal, a, life) }
            Vfx.Kind.FLOOD -> FloodEffect(pos, pal, a, b, life)
            Vfx.Kind.SPEAR -> SpearEffect(pos, pal, a, b, life)
            Vfx.Kind.FEATHERS -> FeathersEffect(pos, pal, a.toInt(), b, life, seed)
            Vfx.Kind.FLARE -> FlareEffect(pos, pal, a, life)
            Vfx.Kind.ORBIT_RINGS -> e1?.let { OrbitRingsEffect(it, pal, a, life) }
            Vfx.Kind.FALLING_FEATHER -> FallingFeatherEffect(pos, pal, a, life)
            Vfx.Kind.CLOUDS -> CloudBankEffect(pos, style, a, b, life, e1, seed)
            Vfx.Kind.GOD_RAYS -> GodRaysEffect(pos, pal, a, b, life, seed)
            Vfx.Kind.ZEUS_EYES -> ZeusEyesEffect(pos, pal, a, life, seed)
            Vfx.Kind.SHAKE, Vfx.Kind.SCREEN_FLASH, Vfx.Kind.SKY_MOOD -> null
        }
        if (effect != null) {
            if (effects.size >= MAX_EFFECTS) effects.removeAt(0)
            effects.add(effect)
        }
    }

    fun render(consumers: VertexConsumerProvider) {
        val client = MinecraftClient.getInstance()
        if (client.world == null) return
        val tickDelta = client.renderTickCounter.getTickProgress(false)
        val draw = VfxDraw(consumers, client.gameRenderer.camera, tickDelta)
        if (HhaClientConfig.enabled("vfx_3d")) {
            ArenaClouds.render(draw)
            AmbientVfx.render(draw)
            BossGlow.render(draw)
            PortalVfx.render(draw)
        } else {
            BossGlow.clear()
        }
        for (effect in effects) {
            effect.render(draw, effect.age + tickDelta)
        }
    }
}
