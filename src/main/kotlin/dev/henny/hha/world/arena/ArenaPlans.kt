package dev.henny.hha.world.arena

import dev.henny.hha.logic.boss.arena.ArenaBlueprint
import dev.henny.hha.logic.boss.arena.ArenaBlueprints
import dev.henny.hha.world.TartarusArenas
import dev.henny.hha.world.olymp.ForumPlan
import dev.henny.hha.world.olymp.OlympusArenas
import dev.henny.hha.world.olymp.OlympusLayout
import dev.henny.hha.world.olymp.OlympusPlans
import dev.henny.hha.world.olymp.SkyCanvas
import dev.henny.hha.world.palace.PalaceHall
import dev.henny.hha.world.sites.FortressPlan
import dev.henny.hha.world.sites.IxionPlan
import dev.henny.hha.world.sites.PrisonPlan
import dev.henny.hha.world.sites.SisyphusPlan
import dev.henny.hha.world.sites.TantalusPlan

/**
 * Die elf Arena-Baupläne (Überarbeitung 4, PLAN 7.3; P00, eingefroren). Olymp-Pläne zeichnen die ganze Insel; die
 * Aufnahme schneidet sie auf `spec.box` zu. Tartaros-Pläne zeichnen den Arena-Kern (`arenaCore`) der Plan-Datei, den
 * auch der volle Plan aufruft. Das Forum wird in Stage I aufgenommen; andere Stages zeichnet `ForumPlan.draw` direkt.
 */
object ArenaPlans {

    const val FORUM = "forum"
    const val OLYMP_C = "olymp_c"
    const val OLYMP_D = "olymp_d"
    const val OLYMP_E = "olymp_e"
    const val OLYMP_F = "olymp_f"

    /** Alle IDs in Registrierungsreihenfolge. */
    val IDS = listOf(
        FORUM, OLYMP_C, OLYMP_D, OLYMP_E, OLYMP_F,
        TartarusArenas.HALL_ID, TartarusArenas.FORTRESS_ID, TartarusArenas.PRISON_ID,
        TartarusArenas.SISYPHUS_ID, TartarusArenas.IXION_ID, TartarusArenas.TANTALUS_ID,
    )

    private fun isle(c: dev.henny.hha.world.BlockCanvas, isle: OlympusLayout.Isle) {
        // Olymp-Pläne brauchen eine SkyCanvas (weiche Blöcke); die Aufnahme-Leinwand ist eine.
        OlympusPlans.draw(c as SkyCanvas, isle)
    }

    fun init() {
        if (ArenaBlueprints.get(FORUM) != null) return
        ArenaBlueprints.register(ArenaBlueprint(FORUM, { OlympusArenas.FORUM(it) }) { c, o -> ForumPlan.draw(c, o, 1, emptySet()) })
        ArenaBlueprints.register(ArenaBlueprint(OLYMP_C, { OlympusArenas.C }) { c, _ -> isle(c, OlympusLayout.C) })
        ArenaBlueprints.register(ArenaBlueprint(OLYMP_D, { OlympusArenas.D }) { c, _ -> isle(c, OlympusLayout.D) })
        ArenaBlueprints.register(ArenaBlueprint(OLYMP_E, { OlympusArenas.E }) { c, _ -> isle(c, OlympusLayout.E) })
        ArenaBlueprints.register(ArenaBlueprint(OLYMP_F, { OlympusArenas.F }) { c, _ -> isle(c, OlympusLayout.F) })
        ArenaBlueprints.register(ArenaBlueprint(TartarusArenas.HALL_ID, TartarusArenas::hall) { c, o -> PalaceHall.arenaCore(c, o) })
        ArenaBlueprints.register(ArenaBlueprint(TartarusArenas.FORTRESS_ID, TartarusArenas::fortress) { c, o -> FortressPlan.arenaCore(c, o) })
        ArenaBlueprints.register(ArenaBlueprint(TartarusArenas.PRISON_ID, TartarusArenas::prison) { c, o -> PrisonPlan.arenaCore(c, o) })
        ArenaBlueprints.register(ArenaBlueprint(TartarusArenas.SISYPHUS_ID, TartarusArenas::sisyphus) { c, o -> SisyphusPlan.arenaCore(c, o) })
        ArenaBlueprints.register(ArenaBlueprint(TartarusArenas.IXION_ID, TartarusArenas::ixion) { c, o -> IxionPlan.arenaCore(c, o) })
        ArenaBlueprints.register(ArenaBlueprint(TartarusArenas.TANTALUS_ID, TartarusArenas::tantalus) { c, o -> TantalusPlan.arenaCore(c, o) })
    }
}
