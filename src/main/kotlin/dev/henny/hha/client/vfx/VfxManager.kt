package dev.henny.hha.client.vfx

import dev.henny.hha.client.HhaClientConfig
import dev.henny.hha.client.vfx.fx.FxAttach
import dev.henny.hha.client.vfx.fx.FxClient
import dev.henny.hha.client.vfx.fx.FxEffect
import dev.henny.hha.client.vfx.fx.FxFrame
import dev.henny.hha.client.vfx.fx.FxPipelines
import dev.henny.hha.client.vfx.fx.ScreenPass
import dev.henny.hha.logic.Vfx
import dev.henny.hha.net.VfxPayload
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.BufferAllocator
import net.minecraft.client.world.ClientWorld
import net.minecraft.util.math.Vec3d

/** Verwaltet alle laufenden 3D-Effekte: nimmt Pakete an, tickt und zeichnet sie. */
object VfxManager {

    /**
     * Obergrenze gleichzeitiger Effekte (PLAN 9.1: 96 → 64). Ist sie erreicht, fällt der älteste Effekt weg, der keine
     * Ansage ist; Ansagen sind Spielinformation und gehen nur verloren, wenn nichts anderes mehr übrig ist.
     */
    const val MAX_EFFECTS = 64

    private val effects = ArrayList<VfxEffect>()
    private var seed = 0L

    /**
     * Schutz gegen gleichzeitiges Ändern: Effekte dürfen beim Ticken oder Zeichnen neue Effekte anstoßen (Aufschlag
     * eines Projektils, Trümmer eines Bruchs). Solange [effects] durchlaufen wird, landen neue Effekte und ein
     * `clear` erst hier und werden danach übernommen. Ohne das gab es eine `ConcurrentModificationException`
     * (Absturz im Zeus-Kampf, 19.11.2026).
     */
    private var iterating = 0
    private val pending = ArrayList<VfxEffect>()
    private var clearPending = false
    private val pendingRemovals = ArrayList<(VfxEffect) -> Boolean>()

    private inline fun iterate(block: () -> Unit) {
        iterating++
        try {
            block()
        } finally {
            iterating--
            if (iterating == 0) flushPending()
        }
    }

    private fun flushPending() {
        if (clearPending) {
            clearPending = false
            effects.clear()
            FxAttach.clear()
        }
        if (pendingRemovals.isNotEmpty()) {
            val removals = ArrayList(pendingRemovals)
            pendingRemovals.clear()
            for (match in removals) effects.removeIf(match)
        }
        if (pending.isEmpty()) return
        val queued = ArrayList(pending)
        pending.clear()
        for (effect in queued) add(effect)
    }

    /** Größe des eigenen Puffers der neuen Effekte (PLAN 6.4, REF T2). */
    const val FX_BUFFER_BYTES = 1 shl 21

    /**
     * Eigener Puffer der neuen Effekte (`FxEffect`), geleert am Ende jeder Phase. Nicht `context.consumers()`: Was vor
     * dem Bildschirm-Pass liegen muss, muss auch vor ihm gezeichnet sein.
     */
    val fxBuffers: VertexConsumerProvider.Immediate by lazy { VertexConsumerProvider.immediate(BufferAllocator(FX_BUFFER_BYTES)) }

    /** Pipelines beim ersten Tick in einer Welt übersetzen (nicht erst beim ersten Treffer, sonst ruckelt es dort). */
    private var warmed = false

    fun tick(world: ClientWorld) {
        if (!warmed) {
            warmed = true
            FxClient.guarded("pipelines") { FxPipelines.ready() }
        }
        BossGlow.tick(world)
        PortalVfx.tick(world)
        FarBeacon.tick()
        iterate {
            val iterator = effects.iterator()
            while (iterator.hasNext()) {
                if (!iterator.next().advance(world)) iterator.remove()
            }
        }
    }

    fun clear() {
        if (iterating > 0) {
            pending.clear()
            pendingRemovals.clear()
            clearPending = true
            return
        }
        effects.clear()
        FxAttach.clear()
    }

    /** Kameraschütteln, Blitz und Ansagen (Telegraphs) laufen immer; alle übrigen 3D-Meshes hängen am Client-Schalter `vfx_3d`. */
    fun handle(payload: VfxPayload, world: ClientWorld) {
        val kind = Vfx.Kind.entries.getOrNull(payload.kind) ?: return
        val style = Vfx.Style.entries.getOrNull(payload.style) ?: Vfx.Style.GOLD
        // Effekte eines Gegners tragen seine Gefahrenfarbe (HEAVEN = Himmelblau-Weiß statt Gold-Weiß usw.).
        val pal = if (payload.flags and Vfx.FLAG_DANGER != 0) Palette.danger(style) else Palette.of(style)
        val safe = style == Vfx.Style.GOLD
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
            Vfx.Kind.TELEGRAPH_CLEAR -> {
                // Nur die Ansagen, die genau hier begonnen haben (abgebrochener Angriff); alles andere bleibt.
                val match = { e: VfxEffect -> e.telegraph && (e.telegraphOrigin?.squaredDistanceTo(pos) ?: Double.MAX_VALUE) < 1.0e-4 }
                if (iterating > 0) {
                    pending.removeIf(match)
                    pendingRemovals.add(match)
                } else {
                    effects.removeIf(match)
                }
                return
            }
            else -> {}
        }
        // Ansagen sind Spielinformation (wo trifft es gleich?) und werden deshalb auch ohne 3D-Effekte gezeichnet.
        val telegraph = kind == Vfx.Kind.TELEGRAPH_RING || kind == Vfx.Kind.TELEGRAPH_LINE || kind == Vfx.Kind.TELEGRAPH_CONE
        if (!telegraph && !HhaClientConfig.enabled("vfx_3d")) return
        if (kind == Vfx.Kind.FAR_BEACON) {
            // Die Säule steht in der Palette ihres Stils (HADES grün, GOLD = nächstes Ziel im Olymp).
            FarBeacon.set(pos, life, Palette.of(style))
            return
        }
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
            Vfx.Kind.TELEGRAPH_RING -> TelegraphRingEffect(pos, pal, a, b, life, safe)
            Vfx.Kind.TELEGRAPH_LINE -> TelegraphLineEffect(pos, dir, pal, a, life, safe)
            Vfx.Kind.TELEGRAPH_CONE -> TelegraphConeEffect(pos, dir, pal, a, b, life)
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
            Vfx.Kind.FAR_BEACON -> null
            Vfx.Kind.SHAKE, Vfx.Kind.SCREEN_FLASH, Vfx.Kind.SKY_MOOD -> null
        }
        if (effect != null) add(effect)
    }

    /**
     * Gerüst P00 (Überarbeitung 3): nimmt einen fertigen Effekt an, auch einen [FxEffect] aus `FxRegistry`. Gleicher
     * Deckel [MAX_EFFECTS] wie die Bestandseffekte.
     */
    fun addEffect(effect: VfxEffect) = add(effect)

    private fun add(effect: VfxEffect) {
        if (iterating > 0) {
            pending.add(effect)
            return
        }
        if (effects.size >= MAX_EFFECTS) {
            val oldest = effects.indexOfFirst { !it.telegraph }
            effects.removeAt(if (oldest >= 0) oldest else 0)
        }
        effects.add(effect)
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
            FarBeacon.render(draw)
        } else {
            BossGlow.clear()
        }
        iterate {
            for (effect in effects) {
                effect.render(draw, effect.age + tickDelta)
            }
            // Überarbeitung 3 (PLAN 6.4, REF T2): Bestand → Energie → Bildschirm-Pass → darüber, je Phase eigener Puffer.
            val frame = FxFrame.of(client.gameRenderer.camera, tickDelta, fxBuffers, FxFrame.Phase.ENERGY)
            forEachFx { effect -> FxClient.guarded("energy:${effect.javaClass.simpleName}") { effect.energy(frame) } }
            FxClient.guarded("energy.draw") { fxBuffers.draw() }
            FxClient.guarded("screen_pass") { ScreenPass.render(frame) }
            val over = frame.inPhase(FxFrame.Phase.OVER)
            forEachFx { effect -> FxClient.guarded("over:${effect.javaClass.simpleName}") { effect.over(over) } }
            FxClient.guarded("over.draw") { fxBuffers.draw() }
        }
    }

    /**
     * Phase 1 der neuen Effekte an `WorldRenderEvents.AFTER_ENTITIES`: schreibt Tiefe (Blöcke, Modellteile, Fels).
     * Der Puffer wird sofort geleert, damit alles vor `END_MAIN` steht (REF 6.4).
     */
    fun renderSolid(context: WorldRenderContext) {
        val client = MinecraftClient.getInstance()
        if (client.world == null) return
        val frame = FxFrame.of(client.gameRenderer.camera, client.renderTickCounter.getTickProgress(false), fxBuffers, FxFrame.Phase.SOLID)
        iterate { forEachFx { effect -> FxClient.guarded("solid:${effect.javaClass.simpleName}") { effect.solid(frame) } } }
        FxClient.guarded("solid.draw") { fxBuffers.draw() }
    }

    /**
     * Alle neuen Effekte (laufende `FxEffect` und die Dauer-Effekte aus [FxAttach]). Der Schalter `vfx_3d` gilt wie
     * für den Bestand: aus = nur Ansagen (falls ein neuer Effekt eine ist).
     */
    private inline fun forEachFx(action: (FxEffect) -> Unit) {
        val show = HhaClientConfig.enabled("vfx_3d")
        for (effect in effects) {
            if (effect is FxEffect && (show || effect.telegraph)) action(effect)
        }
        if (show) for (effect in FxAttach.effects()) action(effect)
    }
}
