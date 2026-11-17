package dev.henny.hha.client.vfx.fx

import dev.henny.hha.client.vfx.CameraFx
import dev.henny.hha.client.vfx.ScreenFx
import dev.henny.hha.logic.boss.BossFx
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.Vec3d

/**
 * Registriert die Kamera- und Bildschirm-IDs `core.kick`, `core.screen`, `core.screen_clear`, `core.vignette`,
 * `core.time_scale` in [FxRegistry]. Die Fabriken liefern `null` (kein 3D-Effekt), sie lösen nur aus; sie laufen
 * deshalb auch mit `vfx_3d` aus. Kodierung: `API.md` 6.2. Aufgerufen aus `FxClient.init()`.
 */
object CoreScreen {

    fun init() {
        FxRegistry.register("core.kick") { a -> kick(a); null }
        FxRegistry.register("core.screen") { a -> screen(a); null }
        FxRegistry.register("core.screen_clear") { a -> screenClear(a); null }
        FxRegistry.register("core.vignette") { a -> vignette(a); null }
        FxRegistry.register("core.time_scale") { a -> timeScale(a); null }
    }

    /**
     * `core.kick`: pos = Mitte, a/b/c = Nicken/Gieren/Rollen (°, am Server gedeckelt), d = Radius (0 = kein Abfall),
     * life = Ticks. Abfall mit der Entfernung linear wie beim Rütteln (`Vfx.Kind.SHAKE`).
     */
    fun kick(a: FxArgs) {
        val falloff = falloff(a.pos, a.d)
        if (falloff <= 0f) return
        CameraFx.kick(a.a * falloff, a.b * falloff, a.c * falloff, a.life)
    }

    /**
     * `core.screen`: pos = Mitte (bei der Linse mit `follow` zugleich Mitte der sicheren Zone), a = Radius,
     * b = Stärke, c = sicherer Radius, d = `BossFx.Screen`-Ordinal, life = Ticks, entity = follow. Farbe = Palette des
     * Stils (bei Gegnern die Gefahrenpalette).
     */
    fun screen(a: FxArgs) {
        val kind = BossFx.Screen.entries.getOrNull(a.d.toInt()) ?: return
        ScreenPass.request(kind, a.pos, a.a, a.b, a.life, safeRadius = a.c, follow = a.entity, tint = a.palette.main)
    }

    /** `core.screen_clear`: d = `BossFx.Screen`-Ordinal. */
    fun screenClear(a: FxArgs) {
        val kind = BossFx.Screen.entries.getOrNull(a.d.toInt()) ?: return
        ScreenPass.clear(kind)
    }

    /**
     * `core.vignette`: a = Stärke (≤ 0,35), b = 1 Herzschlag, life = Ticks. Farbe = dunkler Ton der Palette
     * (`Palette.edge`), damit der Rand abdunkelt statt zu leuchten.
     */
    fun vignette(a: FxArgs) {
        ScreenFx.vignette(a.palette.edge, a.a, a.life, beat = a.b >= 0.5f)
    }

    /** `core.time_scale`: a = Faktor (0 = eingefroren, 1 = normal), life = Ticks; läuft über [FxClock] (P01). */
    fun timeScale(a: FxArgs) {
        FxClock.set(a.a.coerceIn(0f, 1f), a.life)
    }

    /** 1 bei [radius] ≤ 0, sonst `1 − Abstand/radius` (0..1) zum eigenen Spieler. */
    private fun falloff(center: Vec3d, radius: Float): Float {
        if (radius <= 0f) return 1f
        val player = MinecraftClient.getInstance().player ?: return 0f
        return (1.0 - player.entityPos.distanceTo(center) / radius).coerceIn(0.0, 1.0).toFloat()
    }
}
