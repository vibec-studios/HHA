package dev.henny.hha.gametest

/**
 * Ausnahmeliste des Schwebe-Zählers (Überarbeitung 4, PLAN 7.11; P00): die Blöcke, die in den Bestandsplänen nach
 * Welle 0 schon ohne festen Nachbarn stehen. Nur diese Stellen dürfen schweben; jede neue ist ein Fehler. Die Bau-Pakete
 * räumen ihre Einträge ab (Regel „Fuß auf Grund oder gar nicht“); P25 verlangt die leere Liste.
 *
 * Format je Plan: Stellen „x y z“, getrennt durch „;“.
 */
internal object FloatBaseline {

    private val RAW: Map<String, String> = mapOf(
        "palace" to "-24 95 45;-45 95 -24;24 95 -45;45 95 24",
        "hall" to "-5 83 30;-5 83 48;-5 84 31;-5 84 47;-6 83 31;-6 83 47;-6 85 33;-6 85 45;-8 83 33;-8 83 45;-8 84 34;-8 84 44;-9 83 34;-9 83 44;5 83 30;5 83 48;5 84 31;5 84 47;6 83 31;6 83 47;6 85 33;6 85 45;8 83 33;8 83 45;8 84 34;8 84 44;9 83 34;9 83 44",
        "olymp_D" to "119 163 -394",
    )

    private val cache = HashMap<String, Set<String>>()

    fun allowed(plan: String): Set<String> = cache.getOrPut(plan) {
        RAW[plan]?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    }
}
