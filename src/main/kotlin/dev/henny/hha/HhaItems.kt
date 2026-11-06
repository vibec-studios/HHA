package dev.henny.hha

import dev.henny.hha.item.HeavensMaceItem
import dev.henny.hha.item.HeavensSwordItem
import dev.henny.hha.item.HellsMaceItem
import dev.henny.hha.item.HellsSwordItem
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup
import net.minecraft.component.DataComponentTypes
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.SmithingTemplateItem
import net.minecraft.item.ToolMaterial
import net.minecraft.item.equipment.ArmorMaterial
import net.minecraft.item.equipment.EquipmentAsset
import net.minecraft.item.equipment.EquipmentAssetKeys
import net.minecraft.item.equipment.EquipmentType
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.block.entity.BannerPattern
import net.minecraft.registry.tag.BlockTags
import net.minecraft.registry.tag.TagKey
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.Formatting
import net.minecraft.util.Rarity

object HhaItems {

    private const val MACE_ATTACK_DAMAGE = 2.0f

    val HELL_REPAIR_TAG: TagKey<Item> = TagKey.of(RegistryKeys.ITEM, Hha.id("hell_repair"))
    val HEAVEN_REPAIR_TAG: TagKey<Item> = TagKey.of(RegistryKeys.ITEM, Hha.id("heaven_repair"))

    val HELL_PATTERN_TAG: TagKey<BannerPattern> = TagKey.of(RegistryKeys.BANNER_PATTERN, Hha.id("hell"))
    val HEAVEN_PATTERN_TAG: TagKey<BannerPattern> = TagKey.of(RegistryKeys.BANNER_PATTERN, Hha.id("heaven"))

    val HELL_EQUIPMENT_ASSET: RegistryKey<EquipmentAsset> =
        RegistryKey.of(EquipmentAssetKeys.REGISTRY_KEY, Hha.id("hell"))
    val HEAVEN_EQUIPMENT_ASSET: RegistryKey<EquipmentAsset> =
        RegistryKey.of(EquipmentAssetKeys.REGISTRY_KEY, Hha.id("heaven"))

    private val DEFENSE = mapOf(
        EquipmentType.HELMET to 3,
        EquipmentType.CHESTPLATE to 8,
        EquipmentType.LEGGINGS to 6,
        EquipmentType.BOOTS to 3,
    )

    val HELL_ARMOR_MATERIAL = ArmorMaterial(
        40, DEFENSE, 17,
        SoundEvents.ITEM_ARMOR_EQUIP_NETHERITE,
        3.5f, 0.1f, HELL_REPAIR_TAG, HELL_EQUIPMENT_ASSET,
    )

    val HEAVEN_ARMOR_MATERIAL = ArmorMaterial(
        40, DEFENSE, 17,
        SoundEvents.ITEM_ARMOR_EQUIP_DIAMOND,
        3.5f, 0.1f, HEAVEN_REPAIR_TAG, HEAVEN_EQUIPMENT_ASSET,
    )

    val HELL_TOOL_MATERIAL = ToolMaterial(
        BlockTags.INCORRECT_FOR_NETHERITE_TOOL,
        2531, 9.0f, 5.0f, 17, HELL_REPAIR_TAG,
    )

    val HEAVEN_TOOL_MATERIAL = ToolMaterial(
        BlockTags.INCORRECT_FOR_NETHERITE_TOOL,
        2531, 9.0f, 5.0f, 17, HEAVEN_REPAIR_TAG,
    )

    lateinit var HELL_INGOT: Item; private set
    lateinit var HEAVEN_INGOT: Item; private set
    lateinit var HELL_BANNER_PATTERN: Item; private set
    lateinit var HEAVEN_BANNER_PATTERN: Item; private set
    lateinit var JOTUNHEIMS_UPGRADE_TEMPLATE: Item; private set

    lateinit var HELL_HELMET: Item; private set
    lateinit var HELL_CHESTPLATE: Item; private set
    lateinit var HELL_LEGGINGS: Item; private set
    lateinit var HELL_BOOTS: Item; private set
    lateinit var HELLS_SWORD: Item; private set
    lateinit var HELLS_MACE: Item; private set

    lateinit var HEAVEN_HELMET: Item; private set
    lateinit var HEAVEN_CHESTPLATE: Item; private set
    lateinit var HEAVEN_LEGGINGS: Item; private set
    lateinit var HEAVEN_BOOTS: Item; private set
    lateinit var HEAVENS_SWORD: Item; private set
    lateinit var HEAVENS_MACE: Item; private set

    /** Versiegelter Brief: ruft Hermes herbei. */
    lateinit var HERMES_SUMMONS: Item; private set

    /** Feder des Götterboten: Beute von Hermes und Zutat für Heavens Barren. */
    lateinit var HERMES_FEATHER: Item; private set

    /** Karte zu Hades: Beute von Hermes, führt zum Eingang der Halle unter dem Hades-Biom. */
    lateinit var HADES_MAP: Item; private set

    fun init() {
        HERMES_SUMMONS = register("hermes_summons") { s ->
            dev.henny.hha.item.HermesSummonsItem(s.maxCount(1).rarity(Rarity.EPIC))
        }
        HERMES_FEATHER = register("hermes_feather") { s -> Item(s.rarity(Rarity.EPIC)) }
        HADES_MAP = register("hades_map") { s -> dev.henny.hha.item.HadesMapItem(s.maxCount(1).rarity(Rarity.EPIC)) }
        HELL_INGOT = register("hell_ingot") { s -> Item(s.fireproof().rarity(Rarity.RARE)) }
        HEAVEN_INGOT = register("heaven_ingot") { s -> Item(s.rarity(Rarity.RARE)) }
        HELL_BANNER_PATTERN = register("hell_banner_pattern") { s ->
            Item(
                s.maxCount(1).rarity(Rarity.RARE)
                    .component(DataComponentTypes.PROVIDES_BANNER_PATTERNS, HELL_PATTERN_TAG)
            )
        }
        HEAVEN_BANNER_PATTERN = register("heaven_banner_pattern") { s ->
            Item(
                s.maxCount(1).rarity(Rarity.RARE)
                    .component(DataComponentTypes.PROVIDES_BANNER_PATTERNS, HEAVEN_PATTERN_TAG)
            )
        }

        JOTUNHEIMS_UPGRADE_TEMPLATE = registerUpgradeTemplate("jotunheims_upgrade_template")

        HELL_HELMET = registerArmor("hell_helmet", HELL_ARMOR_MATERIAL, EquipmentType.HELMET)
        HELL_CHESTPLATE = registerArmor("hell_chestplate", HELL_ARMOR_MATERIAL, EquipmentType.CHESTPLATE)
        HELL_LEGGINGS = registerArmor("hell_leggings", HELL_ARMOR_MATERIAL, EquipmentType.LEGGINGS)
        HELL_BOOTS = registerArmor("hell_boots", HELL_ARMOR_MATERIAL, EquipmentType.BOOTS)
        HELLS_SWORD = register("hells_sword") { s ->
            HellsSwordItem(
                s.sword(HELL_TOOL_MATERIAL, 5.0f, -2.4f)
                    .fireproof().rarity(Rarity.EPIC)
                    .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false)
            )
        }
        HELLS_MACE = register("hells_mace") { s ->
            HellsMaceItem(
                s.sword(HELL_TOOL_MATERIAL, MACE_ATTACK_DAMAGE, -2.8f)
                    .fireproof().rarity(Rarity.EPIC)
                    .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false)
            )
        }

        HEAVEN_HELMET = registerArmor("heaven_helmet", HEAVEN_ARMOR_MATERIAL, EquipmentType.HELMET)
        HEAVEN_CHESTPLATE = registerArmor("heaven_chestplate", HEAVEN_ARMOR_MATERIAL, EquipmentType.CHESTPLATE)
        HEAVEN_LEGGINGS = registerArmor("heaven_leggings", HEAVEN_ARMOR_MATERIAL, EquipmentType.LEGGINGS)
        HEAVEN_BOOTS = registerArmor("heaven_boots", HEAVEN_ARMOR_MATERIAL, EquipmentType.BOOTS)
        HEAVENS_SWORD = register("heavens_sword") { s ->
            HeavensSwordItem(
                s.sword(HEAVEN_TOOL_MATERIAL, 5.0f, -2.4f)
                    .rarity(Rarity.EPIC)
                    .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false)
            )
        }
        HEAVENS_MACE = register("heavens_mace") { s ->
            HeavensMaceItem(
                s.sword(HEAVEN_TOOL_MATERIAL, MACE_ATTACK_DAMAGE, -2.8f)
                    .rarity(Rarity.EPIC)
                    .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false)
            )
        }

        registerItemGroup()
    }

    /**
     * Schmiedevorlage im Netherite-Style: Template + Basis-Item + Set-Barren im
     * Schmiedetisch. Die leeren Slot-Icons sind Vanilla-GUI-Sprites.
     */
    private fun registerUpgradeTemplate(name: String): Item = register(name) { s ->
        SmithingTemplateItem(
            Text.translatable("item.hha.$name.applies_to").formatted(Formatting.BLUE),
            Text.translatable("item.hha.$name.ingredients").formatted(Formatting.BLUE),
            Text.translatable("item.hha.$name.base_slot_description"),
            Text.translatable("item.hha.$name.additions_slot_description"),
            listOf(
                Identifier.ofVanilla("container/slot/helmet"),
                Identifier.ofVanilla("container/slot/chestplate"),
                Identifier.ofVanilla("container/slot/leggings"),
                Identifier.ofVanilla("container/slot/boots"),
                Identifier.ofVanilla("container/slot/sword"),
            ),
            listOf(Identifier.ofVanilla("container/slot/ingot")),
            s.rarity(Rarity.RARE)
        )
    }

    private fun registerArmor(
        name: String,
        material: ArmorMaterial,
        type: EquipmentType,
        factory: (Item.Settings) -> Item = ::Item,
    ): Item = register(name) { s ->
        factory(
            s.armor(material, type)
                .fireproof().rarity(Rarity.EPIC)
                .component(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false)
        )
    }

    private fun register(name: String, factory: (Item.Settings) -> Item): Item {
        val key = RegistryKey.of(RegistryKeys.ITEM, Hha.id(name))
        return Registry.register(Registries.ITEM, key, factory(Item.Settings().registryKey(key)))
    }

    private fun registerItemGroup() {
        val key = RegistryKey.of(RegistryKeys.ITEM_GROUP, Hha.id("hells_set"))
        Registry.register(
            Registries.ITEM_GROUP, key,
            FabricItemGroup.builder()
                .icon { ItemStack(HELLS_SWORD) }
                .displayName(Text.translatable("itemGroup.hha.hells_set"))
                .entries { _, entries ->
                    entries.add(HERMES_SUMMONS)
                    entries.add(HERMES_FEATHER)
                    entries.add(HADES_MAP)
                    HhaBlocks.BUILDING.forEach { entries.add(it) }
                    entries.add(HELL_INGOT)
                    entries.add(HEAVEN_INGOT)
                    entries.add(HELL_BANNER_PATTERN)
                    entries.add(HEAVEN_BANNER_PATTERN)
                    entries.add(JOTUNHEIMS_UPGRADE_TEMPLATE)
                    entries.add(HELL_HELMET)
                    entries.add(HELL_CHESTPLATE)
                    entries.add(HELL_LEGGINGS)
                    entries.add(HELL_BOOTS)
                    entries.add(HELLS_SWORD)
                    entries.add(HELLS_MACE)
                    entries.add(HEAVEN_HELMET)
                    entries.add(HEAVEN_CHESTPLATE)
                    entries.add(HEAVEN_LEGGINGS)
                    entries.add(HEAVEN_BOOTS)
                    entries.add(HEAVENS_SWORD)
                    entries.add(HEAVENS_MACE)
                }
                .build()
        )
    }
}
