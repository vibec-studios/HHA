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
            Vfx.telegraphRing(w, Vfx.Style.GOLD, front, 5f, 60)
            Vfx.telegraphLine(w, Vfx.Style.HEAVEN, p.entityPos.add(dir.multiply(2.0)).add(3.0, 0.0, 0.0), front.add(dir.multiply(10.0)).add(3.0, 0.0, 0.0), 1.4f, 60)
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

    /** @return false, wenn es den Effekt nicht gibt. */
    fun play(player: ServerPlayerEntity, name: String): Boolean {
        val action = ACTIONS[name] ?: return false
        val world = player.entityWorld as? ServerWorld ?: return false
        val rotation = player.getRotationVec(1.0f)
        val flat = Vec3d(rotation.x, 0.0, rotation.z).let { if (it.lengthSquared() < 1e-4) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
        val front = player.entityPos.add(flat.multiply(8.0))
        action(world, player, front, rotation)
        return true
    }
}
