package dev.henny.hha.client

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dev.henny.hha.Hha
import dev.henny.hha.config.HhaPaths
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reine Client-Konfiguration für Darstellung und Effekte — persistiert in
 * `config/hha/client.json`, live änderbar über den `/hhac`-Clientbefehl.
 * Im Gegensatz zu [dev.henny.hha.config.HhaConfig] werden diese Werte nie
 * vom Server überschrieben: sie steuern nur, was der eigene Client rendert.
 */
object HhaClientConfig {

    /** Darstellungs-Toggles (true = aktiv). */
    private val toggleDefaults: LinkedHashMap<String, Boolean> = linkedMapOf(
        "hud_enabled" to true,
        "hud_pulse" to true,
        "hud_background" to true,
        "ultra_bar" to true,
    )

    /** Zahlenwerte: Skalierung, Verschiebung, Partikel-Dichte. */
    private val numberDefaults: LinkedHashMap<String, Double> = linkedMapOf(
        "hud_scale" to 1.0,
        "hud_offset_x" to 0.0,
        "hud_offset_y" to 0.0,
        "particle_density" to 1.0,
    )

    /** Erlaubte Wertebereiche der Zahlenwerte (min..max). */
    private val numberRanges: Map<String, ClosedFloatingPointRange<Double>> = mapOf(
        "hud_scale" to 0.5..2.0,
        "hud_offset_x" to -1000.0..1000.0,
        "hud_offset_y" to -1000.0..1000.0,
        "particle_density" to 0.0..1.0,
    )

    val TOGGLE_DEFAULTS: Map<String, Boolean> get() = toggleDefaults
    val NUMBER_DEFAULTS: Map<String, Double> get() = numberDefaults

    private val toggles = LinkedHashMap<String, Boolean>()
    private val numbers = LinkedHashMap<String, Double>()

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun path(): Path = HhaPaths.file("client.json")

    fun enabled(key: String): Boolean = toggles[key] ?: toggleDefaults[key] ?: true

    fun num(key: String): Double = numbers[key] ?: numberDefaults[key] ?: 0.0

    fun numF(key: String): Float = num(key).toFloat()

    fun setToggle(key: String, value: Boolean): Boolean {
        if (key !in toggleDefaults) return false
        toggles[key] = value
        save()
        return true
    }

    /** Setzt einen Zahlenwert; Werte außerhalb des Bereichs werden geclampt. */
    fun setNumber(key: String, value: Double): Boolean {
        if (key !in numberDefaults) return false
        val range = numberRanges[key]
        numbers[key] = if (range != null) value.coerceIn(range) else value
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
        if (!Files.exists(file)) {
            save()
            return
        }
        try {
            val root = gson.fromJson(Files.readString(file), JsonObject::class.java) ?: return
            root.getAsJsonObject("visuals")?.entrySet()?.forEach { (key, value) ->
                if (key in toggleDefaults) toggles[key] = value.asBoolean
            }
            root.getAsJsonObject("values")?.entrySet()?.forEach { (key, value) ->
                if (key in numberDefaults) setNumberSilently(key, value.asDouble)
            }
            Hha.LOGGER.info(
                "HHA-Client-Konfiguration geladen ({} Toggles, {} Werte angepasst)",
                toggles.size, numbers.size,
            )
        } catch (e: Exception) {
            Hha.LOGGER.error("Konnte config/hha/client.json nicht lesen — nutze Defaults", e)
        }
    }

    /** Wie [setNumber], aber ohne erneutes Speichern (wird beim Load gerufen). */
    private fun setNumberSilently(key: String, value: Double) {
        val range = numberRanges[key]
        numbers[key] = if (range != null) value.coerceIn(range) else value
    }

    fun save() {
        try {
            val root = JsonObject()
            val visuals = JsonObject()
            toggleDefaults.forEach { (key, def) -> visuals.addProperty(key, toggles[key] ?: def) }
            val values = JsonObject()
            numberDefaults.forEach { (key, def) -> values.addProperty(key, numbers[key] ?: def) }
            root.add("visuals", visuals)
            root.add("values", values)
            Files.createDirectories(path().parent)
            Files.writeString(path(), gson.toJson(root))
        } catch (e: Exception) {
            Hha.LOGGER.error("Konnte config/hha/client.json nicht schreiben", e)
        }
    }
}
