package dev.henny.hha.client.vfx.fx.lib

import dev.henny.hha.client.vfx.fx.BlockDebris
import dev.henny.hha.client.vfx.fx.FxRegistry
import dev.henny.hha.client.vfx.fx.ProjectileFx

/**
 * Registriert die Effekt-Bibliothek `core.*` (PLAN 6.2, Kodierung in `API.md`). Aufgerufen aus `FxClient.init()`.
 * Jede Fabrik liest ihre Parameter genau nach der Kodierungstabelle; je ID eine Datei:
 *
 * | ID | Datei |
 * | --- | --- |
 * | `core.arc` | [ArcFx] |
 * | `core.crack`, `core.cracks` | [GroundCrackFx] |
 * | `core.debris`, `core.debris_pull` | [BlockDebris] (eigene Zeichen-Effekte, Fabrik liefert `null`) |
 * | `core.sparks` | [SparksFx] |
 * | `core.swirl` | [SwirlFx] |
 * | `core.charge` | [ChargeFx] |
 * | `core.pillar_shatter` | [PillarShatterFx] |
 * | `core.wave_wall` | [WaveWallFx] |
 * | `core.sky_strike` | [SkyStrikeFx] |
 * | `core.tear` | [TearFx] (Baustein `Tear`) |
 * | `core.dome` | [DomeFx] |
 * | `core.projectile` | [ProjectileFx] (eigener Effekt, Fabrik liefert `null`) |
 * | `core.drip` | [DripFx] |
 */
object CoreFx {

    /** Alle IDs dieser Datei (für Prüfungen; gleich der P03-Liste in `API.md`). */
    val IDS = listOf(
        "core.arc", "core.crack", "core.cracks", "core.debris", "core.debris_pull", "core.sparks", "core.swirl",
        "core.charge", "core.pillar_shatter", "core.wave_wall", "core.sky_strike", "core.tear", "core.dome",
        "core.projectile", "core.drip",
    )

    /** Trümmer im Ring: innen frei (Anteil des Radius). */
    const val DEBRIS_INNER = 0.35f

    fun init() {
        FxRegistry.register("core.arc") { ArcFx(it) }
        FxRegistry.register("core.crack") { GroundCrackFx.single(it) }
        FxRegistry.register("core.cracks") { GroundCrackFx.star(it) }
        FxRegistry.register("core.debris") { a ->
            val r = a.a.coerceIn(0.5f, 24f)
            BlockDebris.burst(a.world, a.pos, r * DEBRIS_INNER, r, a.b.toInt(), a.c, a.d)
            null
        }
        FxRegistry.register("core.debris_pull") { a ->
            BlockDebris.pull(a.world, a.pos, a.a.coerceIn(0.5f, 32f), a.dir, a.life, a.b.toInt())
            null
        }
        FxRegistry.register("core.sparks") { SparksFx(it) }
        FxRegistry.register("core.swirl") { SwirlFx(it) }
        FxRegistry.register("core.charge") { ChargeFx(it) }
        FxRegistry.register("core.pillar_shatter") { PillarShatterFx(it) }
        FxRegistry.register("core.wave_wall") { WaveWallFx(it) }
        FxRegistry.register("core.sky_strike") { SkyStrikeFx(it) }
        FxRegistry.register("core.tear") { TearFx(it) }
        FxRegistry.register("core.dome") { DomeFx(it) }
        FxRegistry.register("core.projectile") { a ->
            ProjectileFx.fromArgs(a)
            null
        }
        FxRegistry.register("core.drip") { DripFx(it) }
    }
}
