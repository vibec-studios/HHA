package dev.henny.hha.client.gui

import net.minecraft.text.Text
import kotlin.math.roundToInt

/**
 * Wie das Kontrollzentrum Config-Schlüssel gruppiert, beschriftet und als Regler anbietet. Unbekannte Schlüssel
 * (z. B. von Addons) landen in einer eigenen Gruppe je Addon und bekommen einen Regler aus ihrem Standardwert.
 */
object ConfigMeta {

    enum class Unit { NONE, TICKS, HALF_HEARTS, BLOCKS, MULTIPLIER, FRACTION }

    class Range(val min: Double, val max: Double, val step: Double, val unit: Unit)

    class Group(val id: String, val color: Int, val keys: List<String>)

    val TOGGLE_GROUPS = listOf(
        Group("heaven", Ui.GOLD, listOf(
            "halo", "divine_shield", "air_jumps", "shockwave", "light_trail", "light_beam", "purify", "grace",
            "combo_crit", "light_wave", "mace_throw",
        )),
        Group("hell", Ui.SOUL, listOf(
            "undying_rage", "warlords_barrier", "lava_walking", "magma_stomp", "volcanic", "ember_trail", "fire_camp",
            "lava_beam", "hellforged", "sword_buff", "hell_crit_absorption", "mace_pull", "grapple",
        )),
        Group("set", Ui.BRAND, listOf("fall_immunity", "set_health_bonus", "ultra_mode", "set_flames", "kit_mode")),
        // Überarbeitung 2 (PLAN 10.1): Kämpfe und Welt getrennt von den Rezepten
        Group("fights", Ui.TEAL, listOf("hermes_fight", "boss_anti_air", "olympus_portal", "tartaros_chain", "relic_drops")),
        Group("recipes", Ui.MUTED, listOf(
            "relic_crafting", "hell_ingot_recipe", "heaven_ingot_recipe", "template_recipe", "heaven_ingot_free_recipe",
        )),
    )

    val NUMBER_GROUPS = listOf(
        Group("heaven", Ui.GOLD, listOf(
            "shockwave_min_fall", "shockwave_base_damage", "shockwave_max_damage", "combo_hits", "air_jumps",
            "purify_heal", "light_wave_damage", "light_wave_knockback", "light_wave_cooldown", "mace_throw_damage",
            "mace_throw_cooldown", "divine_shield_cooldown",
        )),
        Group("hell", Ui.SOUL, listOf(
            "stomp_min_fall", "stomp_lava_lifetime", "stomp_damage", "undying_rage_health", "barrier_range",
            "ember_damage", "pull_arrival_damage", "bounce_damage", "pull_range", "grapple_range",
            "hell_absorption_duration", "fire_camp_cooldown", "sword_buff_cooldown", "chain_charges",
            "chain_cooldown", "chain_recharge",
        )),
        Group("set", Ui.BRAND, listOf("beam_damage", "beam_cooldown", "trail_max_health", "ultra_duration", "ultra_cooldown")),
        // Die alten Schlüssel `hermes_*` gelten seit Überarbeitung 2 für alle Bosse (PLAN 14, Annahme 7).
        Group("boss_scaling", Ui.TEAL, listOf(
            "boss_strength", "hermes_health_per_player", "hermes_damage_per_player", "boss_damage_player_cap",
            "boss_tempo_per_player", "hermes_damage", "hades_damage", "hermes_toughness", "hermes_max_hit", "boss_smash_decay",
        )),
        Group("particles", Ui.MUTED, listOf("particle_player", "particle_effects")),
    )

    private val RANGES: Map<String, Range> = buildMap {
        fun r(min: Double, max: Double, step: Double, unit: Unit, vararg keys: String) = keys.forEach { put(it, Range(min, max, step, unit)) }
        r(0.0, 64.0, 1.0, Unit.BLOCKS, "stomp_min_fall", "shockwave_min_fall", "barrier_range", "pull_range", "grapple_range")
        r(0.0, 200.0, 1.0, Unit.NONE, "stomp_damage", "shockwave_base_damage", "beam_damage", "light_wave_damage", "ember_damage",
            "pull_arrival_damage", "bounce_damage", "mace_throw_damage", "hermes_max_hit")
        r(0.0, 10000.0, 50.0, Unit.NONE, "shockwave_max_damage")
        r(0.0, 40.0, 1.0, Unit.HALF_HEARTS, "trail_max_health", "undying_rage_health", "purify_heal")
        r(0.0, 10.0, 1.0, Unit.NONE, "combo_hits", "air_jumps", "chain_charges")
        r(0.0, 20.0, 0.5, Unit.NONE, "light_wave_knockback")
        r(0.0, 400.0, 10.0, Unit.TICKS, "stomp_lava_lifetime", "chain_cooldown")
        r(0.0, 12000.0, 20.0, Unit.TICKS, "hell_absorption_duration", "divine_shield_cooldown", "beam_cooldown", "fire_camp_cooldown",
            "sword_buff_cooldown", "light_wave_cooldown", "mace_throw_cooldown", "chain_recharge", "ultra_duration", "ultra_cooldown")
        r(0.0, 5.0, 0.05, Unit.MULTIPLIER, "hermes_damage", "hermes_toughness", "hermes_health_per_player", "hermes_damage_per_player", "hades_damage")
        // Überarbeitung 2: Stärke nie 0 (teilt den Schaden), Deckel nie unter 1, Smash-Erholung mindestens 1 s
        r(0.25, 4.0, 0.05, Unit.MULTIPLIER, "boss_strength")
        r(1.0, 4.0, 0.05, Unit.MULTIPLIER, "boss_damage_player_cap")
        r(0.0, 0.25, 0.01, Unit.FRACTION, "boss_tempo_per_player")
        r(20.0, 600.0, 10.0, Unit.TICKS, "boss_smash_decay")
        r(0.0, 1.0, 0.05, Unit.FRACTION, "particle_player", "particle_effects")
    }

    /** Bereich eines Schlüssels; unbekannte (Addon-)Schlüssel bekommen Einheit und Bereich aus Name und Standardwert. */
    fun range(key: String, default: Double): Range = RANGES[key] ?: run {
        val name = key.substringAfter('.')
        val unit = when {
            listOf("_cooldown", "_duration", "_ticks", "_time", "_recharge", "_lifetime").any { name.endsWith(it) } -> Unit.TICKS
            listOf("_range", "_radius", "_height", "_distance").any { name.endsWith(it) } -> Unit.BLOCKS
            "chance" in name && default <= 1.0 -> Unit.FRACTION
            "multiplier" in name -> Unit.MULTIPLIER
            else -> Unit.NONE
        }
        val max = when (unit) {
            Unit.FRACTION -> 1.0
            Unit.MULTIPLIER -> maxOf(5.0, default * 3)
            else -> when {
                default <= 1.0 -> 5.0
                default <= 20.0 -> 100.0
                else -> default * 4
            }
        }
        val step = when {
            unit == Unit.FRACTION -> 0.01
            unit == Unit.MULTIPLIER || max <= 5.0 -> 0.05
            unit == Unit.TICKS -> if (max >= 2000) 20.0 else 5.0
            max <= 100 -> if (default != Math.floor(default)) 0.5 else 1.0
            else -> 10.0
        }
        Range(0.0, max, step, unit)
    }

    /** Anzeige eines Werts mit Einheit, z. B. „50 s", „3 ♥", „×1.20". */
    fun format(value: Double, unit: Unit): String = when (unit) {
        Unit.TICKS -> trim(value / 20.0) + " s"
        Unit.HALF_HEARTS -> trim(value / 2.0) + " ♥"
        Unit.BLOCKS -> trim(value) + " m"
        Unit.MULTIPLIER -> "×" + "%.2f".format(value)
        Unit.FRACTION -> "${(value * 100).roundToInt()} %"
        Unit.NONE -> trim(value)
    }

    fun trim(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.', ',')

    /** Ungruppierte Schlüssel (Addons) nach Präfix gruppiert, damit nichts verloren geht. */
    fun extraGroups(keys: Collection<String>, known: List<Group>): List<Group> {
        val listed = known.flatMap { it.keys }.toSet()
        return keys.filter { it !in listed }
            .groupBy { if ('.' in it) it.substringBefore('.') else "other" }
            .map { (id, list) -> Group(id, Ui.TEAL, list) }
    }

    /** Gruppenname: eigener Übersetzungsschlüssel, sonst der Name der Mod (z. B. „HHA Blood“), sonst die ID. */
    fun groupTitle(id: String): Text {
        GROUP_FALLBACK[id]?.let { return Text.translatableWithFallback("hha.gui.group.$id", it) }
        val modName = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(id).map { it.metadata.name }.orElse(null)
        return Text.translatableWithFallback("hha.gui.group.$id", modName ?: "Addon: $id")
    }

    /** Ersatz, solange die Sprachdateien die neuen Gruppen noch nicht kennen. */
    private val GROUP_FALLBACK = mapOf("fights" to "Bosse & Welt", "recipes" to "Rezepte", "boss_scaling" to "Bosse")

    fun name(key: String): Text = Text.translatableWithFallback("hha.gui.cfg.$key", pretty(key.substringAfter('.')))

    fun desc(key: String): Text = Text.translatableWithFallback("hha.gui.cfg.$key.desc", "")

    private fun pretty(key: String): String = key.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
}
