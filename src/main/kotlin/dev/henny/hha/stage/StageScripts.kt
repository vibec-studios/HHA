package dev.henny.hha.stage

import dev.henny.hha.Hha
import dev.henny.hha.stage.scripts.BrontesStages
import dev.henny.hha.stage.scripts.GuardianStages
import dev.henny.hha.stage.scripts.HadesStages
import dev.henny.hha.stage.scripts.HekateStages
import dev.henny.hha.stage.scripts.HermesStages
import dev.henny.hha.stage.scripts.KampeStages
import dev.henny.hha.stage.scripts.KratosStages
import dev.henny.hha.stage.scripts.RpgStages
import dev.henny.hha.stage.scripts.ZeusStages

/**
 * Verzeichnis aller Bühnen-Skripte (PLAN 6.5). Läuft auf Server und Client gleich: [init] wird aus `Hha.onInitialize`
 * gerufen (beide Seiten) und ruft die `register()` jeder Skript-Datei unter `stage/scripts/` auf. Nach Welle 0
 * eingefroren; neue Skripte kommen nur in die Datei des jeweiligen Pakets.
 */
object StageScripts {

    private val scripts = LinkedHashMap<String, StageScript>()
    private val warned = HashSet<String>()
    private var initialized = false

    /** Ruft alle Skript-Dateien auf (einmal). Ein fehlerhaftes Skript wirft hier und bricht den Start (gewollt). */
    fun init() {
        if (initialized) return
        initialized = true
        HermesStages.register()
        HekateStages.register()
        BrontesStages.register()
        KratosStages.register()
        ZeusStages.register()
        HadesStages.register()
        GuardianStages.register()
        KampeStages.register()
        RpgStages.register()
        Hha.LOGGER.info("Bühne: {} Skripte registriert", scripts.size)
    }

    /** Registriert [script]. Eine doppelte ID ist ein Fehler im Drehbuch: [IllegalArgumentException]. */
    fun register(script: StageScript) {
        require(script.id !in scripts) { "Bühnen-Skript '${script.id}' ist doppelt registriert" }
        scripts[script.id] = script
    }

    fun get(id: String): StageScript? = scripts[id]

    /** Länge des Skripts [id] in Ticks; 0 (und eine Warnung je ID), wenn es nicht registriert ist. */
    fun length(id: String): Int {
        val script = scripts[id]
        if (script == null) {
            if (warned.add(id)) Hha.LOGGER.warn("Bühne: unbekanntes Skript '{}'", id)
            return 0
        }
        return script.length
    }

    /** Alle registrierten IDs in Registrierungs-Reihenfolge. */
    fun ids(): List<String> = scripts.keys.toList()
}
