package dev.henny.hha.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dev.henny.hha.Hha
import java.nio.file.Files
import java.nio.file.Path

/**
 * Developer-Konfiguration: alle Fähigkeiten lassen sich abschalten, Schadenswerte
 * und Trigger-Schwellen anpassen. Persistiert in config/hha/hha.json, live änderbar
 * über den /hha-Befehl.
 */
object HhaConfig {

    /** Feature-Toggles (true = aktiv). */
    private val toggleDefaults: LinkedHashMap<String, Boolean> = linkedMapOf(
        "undying_rage" to true,
        "warlords_barrier" to true,
        "lava_walking" to true,
        "magma_stomp" to true,
        "volcanic" to true,
        "ember_trail" to true,
        "fire_camp" to true,
        "lava_beam" to true,
        "hellforged" to true,
        "sword_buff" to true,
        "hell_crit_absorption" to true,
        "mace_pull" to true,
        "grapple" to true,
        "halo" to true,
        "divine_shield" to true,
        "air_jumps" to true,
        "shockwave" to true,
        "light_trail" to true,
        "light_beam" to true,
        "purify" to true,
        "grace" to true,
        "combo_crit" to true,
        "light_wave" to true,
        "mace_throw" to true,
        "fall_immunity" to true,
        "set_health_bonus" to true,
        "ultra_mode" to true,
        "set_flames" to true,
        "hell_ingot_recipe" to true,
        "heaven_ingot_recipe" to true,
        "template_recipe" to true,
        "kit_mode" to true,
        "hermes_fight" to true,
        "heaven_ingot_free_recipe" to false,
    )

    /** Zahlenwerte: Schaden und Trigger-Schwellen. */
    private val numberDefaults: LinkedHashMap<String, Double> = linkedMapOf(
        "particle_player" to 1.0,
        "particle_effects" to 1.0,
        "stomp_min_fall" to 10.0,
        "stomp_lava_lifetime" to 40.0,
        "shockwave_min_fall" to 6.0,
        "trail_max_health" to 10.0,
        "undying_rage_health" to 6.0,
        "barrier_range" to 16.0,
        "combo_hits" to 2.0,
        "air_jumps" to 2.0,
        "stomp_damage" to 60.0,
        "shockwave_base_damage" to 12.0,
        "shockwave_max_damage" to 5000.0,
        "beam_damage" to 60.0,
        "purify_heal" to 16.0,
        "light_wave_damage" to 20.0,
        "light_wave_knockback" to 5.0,
        "ember_damage" to 3.0,
        "pull_arrival_damage" to 40.0,
        "bounce_damage" to 16.0,
        "pull_range" to 16.0,
        "grapple_range" to 32.0,
        "hell_absorption_duration" to 2400.0,
        "divine_shield_cooldown" to 1600.0,
        "beam_cooldown" to 1000.0,
        "fire_camp_cooldown" to 1200.0,
        "sword_buff_cooldown" to 1200.0,
        "light_wave_cooldown" to 700.0,
        "mace_throw_damage" to 5.0,
        "mace_throw_cooldown" to 420.0,
        "chain_charges" to 2.0,
        "chain_cooldown" to 40.0,
        "chain_recharge" to 400.0,
        "ultra_duration" to 1200.0,
        "ultra_cooldown" to 6000.0,
        "hermes_damage" to 1.0,
        "hermes_toughness" to 1.0,
        "hermes_max_hit" to 40.0,
        "hermes_health_per_player" to 0.6,
        "hermes_damage_per_player" to 0.2,
    )

    /** Alle bekannten Toggles (eingebaute + von Addons registrierte). */
    val TOGGLE_DEFAULTS: Map<String, Boolean> get() = toggleDefaults

    /** Alle bekannten Zahlenwerte (eingebaute + von Addons registrierte). */
    val NUMBER_DEFAULTS: Map<String, Double> get() = numberDefaults

    private val toggles = LinkedHashMap<String, Boolean>()
    private val numbers = LinkedHashMap<String, Double>()

    /**
     * Registriert einen Addon-Toggle. Muss vor [load] passieren (der
     * Addon-Entrypoint läuft früh genug), damit gespeicherte Werte greifen.
     */
    fun registerToggle(key: String, default: Boolean) {
        toggleDefaults.putIfAbsent(key, default)
    }

    /** Registriert einen Addon-Zahlenwert; siehe [registerToggle]. */
    fun registerNumber(key: String, default: Double) {
        numberDefaults.putIfAbsent(key, default)
    }

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun path(): Path = HhaPaths.file("hha.json")

    @JvmStatic
    fun enabled(key: String): Boolean = toggles[key] ?: TOGGLE_DEFAULTS[key] ?: true

    @JvmStatic
    fun num(key: String): Double = numbers[key] ?: NUMBER_DEFAULTS[key] ?: 0.0

    fun numF(key: String): Float = num(key).toFloat()

    /** Alle effektiven Zahlenwerte — für den S2C-Sync (Lore-Anzeige auf dem Client). */
    fun numbersSnapshot(): Map<String, Double> =
        numberDefaults.keys.associateWith { num(it) }

    fun setToggle(key: String, value: Boolean): Boolean {
        if (key !in TOGGLE_DEFAULTS) return false
        toggles[key] = value
        save()
        return true
    }

    fun setNumber(key: String, value: Double): Boolean {
        if (key !in NUMBER_DEFAULTS) return false
        numbers[key] = value
        save()
        return true
    }

    fun reset() {
        toggles.clear()
        numbers.clear()
        save()
    }

    fun load() {
        toggles.clear()
        numbers.clear()
        val file = path()
        HhaPaths.migrate("hha.json", file)
        if (!Files.exists(file)) {
            save()
            return
        }
        try {
            val root = gson.fromJson(Files.readString(file), JsonObject::class.java) ?: return
            root.getAsJsonObject("abilities")?.entrySet()?.forEach { (key, value) ->
                if (key in TOGGLE_DEFAULTS) toggles[key] = value.asBoolean
            }
            root.getAsJsonObject("values")?.entrySet()?.forEach { (key, value) ->
                if (key in NUMBER_DEFAULTS) numbers[key] = value.asDouble
            }
            Hha.LOGGER.info("HHA-Konfiguration geladen ({} Toggles, {} Werte angepasst)", toggles.size, numbers.size)
        } catch (e: Exception) {
            Hha.LOGGER.error("Konnte config/hha.json nicht lesen — nutze Defaults", e)
        }
    }

    fun save() {
        try {
            val root = JsonObject()
            val abilities = JsonObject()
            TOGGLE_DEFAULTS.forEach { (key, def) -> abilities.addProperty(key, toggles[key] ?: def) }
            val values = JsonObject()
            NUMBER_DEFAULTS.forEach { (key, def) -> values.addProperty(key, numbers[key] ?: def) }
            root.add("abilities", abilities)
            root.add("values", values)
            Files.createDirectories(path().parent)
            Files.writeString(path(), gson.toJson(root))
        } catch (e: Exception) {
            Hha.LOGGER.error("Konnte config/hha.json nicht schreiben", e)
        }
    }
}
