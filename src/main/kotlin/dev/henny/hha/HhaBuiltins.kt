package dev.henny.hha

import dev.henny.hha.api.ability.AbilitySlot
import dev.henny.hha.api.ability.HhaAbilities
import dev.henny.hha.api.ability.SimpleAbility
import dev.henny.hha.api.lore.HhaLore
import dev.henny.hha.api.set.HhaSets
import dev.henny.hha.api.set.armorSetBuilder
import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.Abilities
import dev.henny.hha.logic.AirJumps
import dev.henny.hha.logic.HeavenSet
import dev.henny.hha.logic.HellSet
import dev.henny.hha.logic.UltraMode
import net.minecraft.entity.EquipmentSlot

/**
 * Registriert die eingebauten Heaven/Hell-Sets, -Fähigkeiten und -Lore über
 * dieselben Registries, die auch Addons nutzen. Jede Ability folgt dem Muster
 * **Ability-Taste → Ausrüstungs-Check → Funktion** (z. B. Ability 1 + volles
 * Hell-Set → Lavastrahl). Die Passiveffekte der beiden Sets bleiben in
 * [dev.henny.hha.logic.PassiveEffects].
 */
object HhaBuiltins {

    fun init() {
        registerSets()
        registerAbilities()
        registerLore()
    }

    private fun registerSets() {
        HhaSets.register(
            armorSetBuilder(HhaSets.HELL_ID)
                .helmet(HhaItems.HELL_HELMET)
                .chestplate(HhaItems.HELL_CHESTPLATE)
                .leggings(HhaItems.HELL_LEGGINGS)
                .boots(HhaItems.HELL_BOOTS)
                .weapon(HhaItems.HELLS_SWORD)
                .weapon(HhaItems.HELLS_MACE)
                .build()
        )
        HhaSets.register(
            armorSetBuilder(HhaSets.HEAVEN_ID)
                .helmet(HhaItems.HEAVEN_HELMET)
                .chestplate(HhaItems.HEAVEN_CHESTPLATE)
                .leggings(HhaItems.HEAVEN_LEGGINGS)
                .boots(HhaItems.HEAVEN_BOOTS)
                .weapon(HhaItems.HEAVENS_SWORD)
                .weapon(HhaItems.HEAVENS_MACE)
                .build()
        )
    }

    private fun registerAbilities() {
        // Ability 1: volles Heaven-Set → Lichtstrahl; volles Hell-Set → Lavastrahl.
        // Reihenfolge = Prüfreihenfolge beim Dispatch (Heaven vor Hell wie bisher).
        HhaAbilities.register(
            SimpleAbility(
                Hha.id("light_beam"), AbilitySlot.ABILITY_1, HeavenSet::hasFullSet,
                { player -> if (HhaConfig.enabled("light_beam")) Abilities.lightBeam(player) },
                EquipmentSlot.CHEST,
            )
        )
        HhaAbilities.register(
            SimpleAbility(
                Hha.id("lava_beam"), AbilitySlot.ABILITY_1, HellSet::hasFullSet,
                { player -> if (HhaConfig.enabled("lava_beam")) Abilities.lavaBeam(player) },
                EquipmentSlot.CHEST,
            )
        )
        // Ability 2: Hell-Leggings → Fire Camp.
        HhaAbilities.register(
            SimpleAbility(
                Hha.id("fire_camp"), AbilitySlot.ABILITY_2, HellSet::hasLeggings,
                { player -> if (HhaConfig.enabled("fire_camp")) Abilities.fireCamp(player) },
                EquipmentSlot.LEGS,
            )
        )
        // Ability 3: volles Set → Ultra-Modus (prüft die Voraussetzungen selbst).
        HhaAbilities.register(
            SimpleAbility(
                Hha.id("ultra"), AbilitySlot.ABILITY_3, { true }, UltraMode::tryActivate,
                EquipmentSlot.HEAD,
            )
        )
        // Bewegung: Sprungtaste in der Luft → Doppelsprung (Heaven-Leggings).
        HhaAbilities.register(
            SimpleAbility(
                Hha.id("air_jump"), AbilitySlot.MOVEMENT, { true }, AirJumps::tryJump,
                EquipmentSlot.LEGS,
            )
        )
    }

    private fun registerLore() {
        HhaLore.register(HhaItems.HERMES_SUMMONS) {
            line("item.hha.hermes_summons.lore1")
            line("item.hha.hermes_summons.lore2")
        }
        HhaLore.register(HhaItems.HADES_MAP) {
            line("item.hha.hades_map.lore1")
            line("item.hha.hades_map.lore2")
        }
        HhaLore.register(HhaItems.HERMES_FEATHER) {
            line("item.hha.hermes_feather.lore1")
            line("item.hha.hermes_feather.lore2")
        }
        HhaLore.register(HhaItems.HELL_HELMET) {
            line("item.hha.hell_helmet.lore1")
            line("item.hha.hell_helmet.lore2", hearts("undying_rage_health"))
        }
        HhaLore.register(HhaItems.HELL_CHESTPLATE) {
            line("item.hha.hell_chestplate.lore1")
            line("item.hha.hell_chestplate.lore2")
            line("item.hha.hell_chestplate.lore3")
            line("item.hha.hell_chestplate.lore4", num("beam_damage"), seconds("beam_cooldown"))
        }
        HhaLore.register(HhaItems.HELL_LEGGINGS) {
            line("item.hha.hell_leggings.lore1")
            line("item.hha.hell_leggings.lore2", seconds("fire_camp_cooldown"))
            line("item.hha.hell_leggings.lore3", num("stomp_min_fall"), num("stomp_damage"))
        }
        HhaLore.register(HhaItems.HELL_BOOTS) {
            line("item.hha.hell_boots.lore1")
            line("item.hha.hell_boots.lore2", hearts("trail_max_health"), num("ember_damage"))
            line("item.hha.hell_boots.lore3")
        }
        HhaLore.register(HhaItems.HELLS_SWORD) {
            line("item.hha.hells_sword.lore1")
            line("item.hha.hells_sword.lore2", seconds("sword_buff_cooldown"))
            line("item.hha.hells_sword.lore3")
        }
        HhaLore.register(HhaItems.HELLS_MACE) {
            line("item.hha.hells_mace.lore1")
            line("item.hha.hells_mace.lore2", num("pull_arrival_damage"), num("chain_charges"), seconds("chain_recharge"))
            line("item.hha.hells_mace.lore3", num("grapple_range"))
            line("item.hha.hells_mace.lore4", num("bounce_damage"))
        }
        HhaLore.register(HhaItems.HEAVEN_HELMET) {
            line("item.hha.heaven_helmet.lore1")
            line("item.hha.heaven_helmet.lore2")
        }
        HhaLore.register(HhaItems.HEAVEN_CHESTPLATE) {
            line("item.hha.heaven_chestplate.lore1")
            line("item.hha.heaven_chestplate.lore2", seconds("divine_shield_cooldown"))
            line("item.hha.heaven_chestplate.lore3")
            line(
                "item.hha.heaven_chestplate.lore4",
                num("beam_damage"), hearts("purify_heal"), seconds("beam_cooldown"),
            )
        }
        HhaLore.register(HhaItems.HEAVEN_LEGGINGS) {
            line("item.hha.heaven_leggings.lore1")
            line("item.hha.heaven_leggings.lore2", num("shockwave_base_damage"))
            line("item.hha.heaven_leggings.lore3", num("shockwave_min_fall"))
        }
        HhaLore.register(HhaItems.HEAVEN_BOOTS) {
            line("item.hha.heaven_boots.lore1")
            line("item.hha.heaven_boots.lore2", hearts("trail_max_health"))
            line("item.hha.heaven_boots.lore3")
        }
        HhaLore.register(HhaItems.HEAVENS_SWORD) {
            line("item.hha.heavens_sword.lore1")
            line("item.hha.heavens_sword.lore2", num("light_wave_damage"), seconds("light_wave_cooldown"))
        }
        HhaLore.register(HhaItems.HEAVENS_MACE) {
            line("item.hha.heavens_mace.lore1")
            line("item.hha.heavens_mace.lore2", num("mace_throw_damage"), seconds("mace_throw_cooldown"))
        }
    }
}
