package dev.henny.hha.logic

import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Vec3d

/**
 * Vorschau aller 3D-Effekte für `/hha vfx <name>` — praktisch, um Partikel-/Effekt-Einstellungen zu
 * prüfen oder als Addon-Entwickler die vorhandenen Effekte anzusehen.
 */
object VfxDemo {

    private val ACTIONS: Map<String, (ServerWorld, ServerPlayerEntity, Vec3d, Vec3d) -> Unit> = linkedMapOf(
        "light_beam" to { w, p, front, dir ->
            Vfx.beam(w, Vfx.Style.HEAVEN, p.eyePos.add(dir.multiply(0.8)).add(0.0, -0.25, 0.0), p.eyePos.add(dir.multiply(20.0)), 0.9f, 30)
            Vfx.rune(w, Vfx.Style.HEAVEN, p.entityPos.add(0.0, 0.06, 0.0), 2.6f, 30, p)
            Vfx.shockwave(w, Vfx.Style.HEAVEN, front.add(dir.multiply(14.0)), 6.5f, 30, 2.2f)
        },
        "lava_beam" to { w, p, front, dir ->
            Vfx.beam(w, Vfx.Style.HELL, p.eyePos.add(dir.multiply(0.8)).add(0.0, -0.25, 0.0), p.eyePos.add(dir.multiply(20.0)), 0.95f, 30)
            Vfx.rune(w, Vfx.Style.FIRE, p.entityPos.add(0.0, 0.06, 0.0), 2.6f, 30, p)
            Vfx.shockwave(w, Vfx.Style.FIRE, front.add(dir.multiply(14.0)), 6.5f, 30, 2.2f)
        },
        "light_wave" to { w, p, _, dir ->
            Vfx.crescent(w, Vfx.Style.HEAVEN, p.eyePos.add(0.0, -0.35, 0.0), dir, 12f, 3.2f, 24)
            Vfx.crescent(w, Vfx.Style.GOLD, p.eyePos.add(0.0, 0.15, 0.0), dir, 9f, 2.2f, 26)
        },
        "shockwave" to { w, _, front, _ -> Vfx.shockwave(w, Vfx.Style.HEAVEN, front, 10f, 30, 3f) },
        "magma_stomp" to { w, _, front, _ ->
            Vfx.shockwave(w, Vfx.Style.FIRE, front, 9f, 30, 3f)
            Vfx.pillar(w, Vfx.Style.FIRE, front, 1.6f, 7f, 30)
        },
        "bolt" to { w, _, front, _ ->
            Vfx.bolt(w, Vfx.Style.STORM, front.add(1.0, 22.0, -1.0), front.add(0.0, 0.2, 0.0), 0.6f, 20)
            Vfx.shockwave(w, Vfx.Style.STORM, front, 5f, 20, 1.6f)
        },
        "pillar" to { w, _, front, _ -> Vfx.pillar(w, Vfx.Style.HEAVEN, front, 2.2f, 26f, 50) },
        "soul_pillar" to { w, _, front, _ -> Vfx.pillar(w, Vfx.Style.SOUL, front, 2.2f, 18f, 50) },
        "shield" to { w, p, _, _ -> Vfx.shield(w, Vfx.Style.HEAVEN, p, 2.2f, 50) },
        "wings" to { w, p, _, _ -> Vfx.wings(w, Vfx.Style.HEAVEN, p, 100, 1.15f) },
        "fire_wings" to { w, p, _, _ -> Vfx.wings(w, Vfx.Style.FIRE, p, 100, 1.15f) },
        "chain" to { w, p, front, _ -> Vfx.chain(w, Vfx.Style.FIRE, p, front.add(0.0, 2.0, 0.0), null, 40) },
        "rift" to { w, _, front, _ -> Vfx.rift(w, Vfx.Style.GOLD, front.add(0.0, 8.0, 0.0), Vec3d(0.0, -1.0, 0.0), 7f, 60) },
        "nova" to { w, _, front, _ -> Vfx.nova(w, Vfx.Style.HEAVEN, front.add(0.0, 2.0, 0.0), 8f, 30) },
        "rune" to { w, _, front, _ -> Vfx.rune(w, Vfx.Style.HELL, front.add(0.0, 0.06, 0.0), 6f, 60) },
        "tornado" to { w, _, front, _ -> Vfx.tornado(w, Vfx.Style.GOLD, front, Vec3d.ZERO, 3.5f, 14f, 70) },
        "aura" to { w, p, _, _ -> Vfx.aura(w, Vfx.Style.FIRE, p, 80, 1f) },
        "flood" to { w, p, _, _ -> Vfx.flood(w, Vfx.Style.SOUL, p.entityPos, 18f, 3f, 80) },
        "spear" to { w, _, front, _ -> Vfx.spear(w, Vfx.Style.GOLD, front, 40f, 1f, 80) },
        "feathers" to { w, _, front, _ -> Vfx.feathers(w, Vfx.Style.HEAVEN, front.add(0.0, 1.5, 0.0), 40, 1.5f, 60) },
        "telegraph" to { w, p, front, dir ->
            Vfx.telegraphRingRaw(w, Vfx.Style.GOLD, front, 5f, 0f, 60)
            Vfx.telegraphLineRaw(w, Vfx.Style.HEAVEN, p.entityPos.add(dir.multiply(2.0)).add(3.0, 0.0, 0.0), front.add(dir.multiply(10.0)).add(3.0, 0.0, 0.0), 0.8f, 60)
        },
        // Ansagen (Plan 3.2): Rand ab Tick 0, Füllung wächst bis zum Treffer, die letzten 4 Ticks weiß.
        "telegraph_ring" to { w, _, front, _ -> Vfx.telegraphRingRaw(w, Vfx.Style.HADES, front, 6f, 0f, 40) },
        "telegraph_wave" to { w, p, _, _ -> Vfx.telegraphRingRaw(w, Vfx.Style.HEAVEN, p.entityPos, 14f, 11f, 40) },
        "telegraph_line" to { w, p, front, flat ->
            Vfx.telegraphLineRaw(w, Vfx.Style.SOUL, p.entityPos.add(flat.multiply(2.0)), front.add(flat.multiply(10.0)), 1.1f, 40)
        },
        "telegraph_cone" to { w, p, _, flat -> Vfx.telegraphConeRaw(w, Vfx.Style.AEON, p.entityPos.add(flat.multiply(1.5)), flat, 11f, 50f, 50) },
        "telegraph_safe" to { w, _, front, _ ->
            Vfx.telegraphRingRaw(w, Vfx.Style.HADES, front, 12f, 3f, 60)
            Vfx.telegraphRingRaw(w, Vfx.Style.GOLD, front, 3f, 0f, 60, forceStyle = true)
        },
        // Kronos' Farbe: Kegel und Kreis als Ansage, daneben ein Einschlag in Äon-Indigo.
        "aeon" to { w, p, front, flat ->
            Vfx.telegraphConeRaw(w, Vfx.Style.AEON, p.entityPos.add(flat.multiply(1.5)), flat, 9f, 100f, 40)
            Vfx.telegraphRingRaw(w, Vfx.Style.AEON, front.add(flat.multiply(6.0)), 4f, 0f, 40)
            Vfx.shockwave(w, Vfx.Style.AEON, front.add(flat.multiply(6.0)).add(flat.crossProduct(Vec3d(0.0, 1.0, 0.0)).multiply(7.0)), 6f, 24, 2f)
        },
        "rings" to { w, p, _, _ -> Vfx.orbitRings(w, Vfx.Style.GOLD, p, 1.25f, 80) },
        "storm_clouds" to { w, p, _, _ ->
            Vfx.clouds(w, Vfx.Style.STORM, p.entityPos, 60f, 30f, 300)
            Vfx.skyMood(w, Vfx.Style.STORM, p.entityPos, 1f, 300)
        },
        "fire_clouds" to { w, p, _, _ -> Vfx.clouds(w, Vfx.Style.FIRE, p.entityPos, 44f, 26f, 200, p) },
        "god_rays" to { w, _, front, _ -> Vfx.godRays(w, Vfx.Style.GOLD, front, 12f, 60f, 140) },
        "sky_fire" to { w, p, _, _ -> Vfx.skyMood(w, Vfx.Style.FIRE, p.entityPos, 0.85f, 300) },
        "sky_soul" to { w, p, _, _ -> Vfx.skyMood(w, Vfx.Style.HELL, p.entityPos, 0.85f, 300) },
        "sky_gold" to { w, p, _, _ -> Vfx.skyMood(w, Vfx.Style.GOLD, p.entityPos, 0.85f, 300) },
        "sky_storm" to { w, p, _, _ -> Vfx.skyMood(w, Vfx.Style.STORM, p.entityPos, 0.85f, 300) },
    )

    val NAMES: Set<String> get() = ACTIONS.keys

    /** Stilnamen für die Vorschau mit Stil, klein geschrieben. */
    val STYLES: List<String> get() = Vfx.Style.entries.map { it.name.lowercase() }

    /**
     * Spielt die Vorschau [name] ab.
     *
     * Mit [style] läuft sie **wie im Kampf**: alles in diesem Stil, Gefahrenfarben auf dem Client und die
     * Effekt-Regeln aus Plan 3.3 (ein Flächeneffekt je Treffer, Rütteln und Blitz begrenzt). Ohne [style] sieht man
     * den Effekt so, wie ihn die Fähigkeiten der Spieler zeigen. Ansagen tragen immer ihre Gefahrenfarbe.
     * Die Ansage-Vorschauen bekommen die flache Blickrichtung, die anderen den vollen Blick.
     *
     * @return false, wenn es den Effekt nicht gibt.
     */
    fun play(player: ServerPlayerEntity, name: String, style: Vfx.Style? = null): Boolean {
        val action = ACTIONS[name] ?: return false
        val world = player.entityWorld as? ServerWorld ?: return false
        val rotation = player.getRotationVec(1.0f)
        val flat = Vec3d(rotation.x, 0.0, rotation.z).let { if (it.lengthSquared() < 1e-4) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
        val front = player.entityPos.add(flat.multiply(8.0))
        val dir = if (name.startsWith("telegraph_") || name == "aeon") flat else rotation
        if (style == null) {
            action(world, player, front, dir)
            return true
        }
        val previous = Vfx.styleOverride
        Vfx.styleOverride = style
        try {
            dev.henny.hha.logic.boss.Telegraph.open(world, "vfx", style) { listOf(player) }.during { action(world, player, front, dir) }
        } finally {
            Vfx.styleOverride = previous
        }
        return true
    }
}
