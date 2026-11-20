package dev.henny.hha

import dev.henny.hha.block.ArenaDeviceBlock
import dev.henny.hha.block.StormCloudBlock
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup
import net.minecraft.block.AbstractBlock
import net.minecraft.block.Block
import net.minecraft.block.MapColor
import net.minecraft.block.PillarBlock
import net.minecraft.block.SlabBlock
import net.minecraft.block.StairsBlock
import net.minecraft.block.WallBlock
import net.minecraft.block.enums.NoteBlockInstrument
import net.minecraft.block.piston.PistonBehavior
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.sound.BlockSoundGroup
import net.minecraft.text.Text

/**
 * Die Deko-Blöcke der Überarbeitung 4 (PLAN 2.1, 7.1; P00 vollständig, danach eingefroren): 17 Grundblöcke in zwei
 * Familien (Olymp: Marmor, Bronze, Sturm; Tartaros: Basalt, Seelenader, Glut, Bronze), 14 Varianten (Treppe, Stufe,
 * Mauer) und 4 Arena-Geräte. Vanilla bleibt Grundmaterial der Bauten; diese Blöcke setzen Akzente.
 *
 * Werte: Härte 1,8, Sprengfestigkeit 6, Spitzhacke nötig; Klang Stein, Bronze `COPPER`, Wolke `WOOL`. Varianten erben
 * vom Grundblock (`copyShallow`, damit Loot und Name der Variante gehören). Leuchten: `storm_marble` 6,
 * `soul_vein_basalt` 7, `ember_basalt` 10; Geräte 9 im Zustand 1. Die Dateien (Blockstates, Modelle, Items, Loot, Tags,
 * Rezepte, Platzhalter-Texturen) schreibt `tools/hha_assets/deco_registry.py`.
 */
object HhaDecoBlocks {

    // --- Olymp ---------------------------------------------------------------------------------------------------
    lateinit var OLYMPUS_MARBLE: Block; private set
    lateinit var OLYMPUS_MARBLE_BRICKS: Block; private set
    lateinit var OLYMPUS_MARBLE_BRICK_STAIRS: Block; private set
    lateinit var OLYMPUS_MARBLE_BRICK_SLAB: Block; private set
    lateinit var OLYMPUS_MARBLE_BRICK_WALL: Block; private set
    lateinit var POLISHED_OLYMPUS_MARBLE: Block; private set
    lateinit var POLISHED_OLYMPUS_MARBLE_STAIRS: Block; private set
    lateinit var POLISHED_OLYMPUS_MARBLE_SLAB: Block; private set
    lateinit var CHISELED_OLYMPUS_MARBLE: Block; private set
    lateinit var OLYMPUS_MARBLE_PILLAR: Block; private set
    lateinit var GILDED_OLYMPUS_MARBLE: Block; private set
    lateinit var STORM_MARBLE: Block; private set
    lateinit var OLYMPUS_BRONZE: Block; private set
    lateinit var OLYMPUS_BRONZE_STAIRS: Block; private set
    lateinit var OLYMPUS_BRONZE_SLAB: Block; private set
    lateinit var STORM_CLOUD: Block; private set

    // --- Tartaros ------------------------------------------------------------------------------------------------
    lateinit var TARTARUS_BASALT: Block; private set
    lateinit var TARTARUS_BASALT_BRICKS: Block; private set
    lateinit var TARTARUS_BASALT_BRICK_STAIRS: Block; private set
    lateinit var TARTARUS_BASALT_BRICK_SLAB: Block; private set
    lateinit var TARTARUS_BASALT_BRICK_WALL: Block; private set
    lateinit var POLISHED_TARTARUS_BASALT: Block; private set
    lateinit var POLISHED_TARTARUS_BASALT_STAIRS: Block; private set
    lateinit var POLISHED_TARTARUS_BASALT_SLAB: Block; private set
    lateinit var CHISELED_TARTARUS_BASALT: Block; private set
    lateinit var TARTARUS_BASALT_PILLAR: Block; private set
    lateinit var SOUL_VEIN_BASALT: Block; private set
    lateinit var EMBER_BASALT: Block; private set
    lateinit var TARTARUS_BRONZE: Block; private set
    lateinit var TARTARUS_BRONZE_STAIRS: Block; private set
    lateinit var TARTARUS_BRONZE_SLAB: Block; private set

    // --- Arena-Geräte (Funktion: P05) ----------------------------------------------------------------------------
    lateinit var MESSENGER_BELL: Block; private set
    lateinit var GROUNDING_ROD: Block; private set
    lateinit var CHAIN_POST: Block; private set
    lateinit var SLUICE_WHEEL: Block; private set

    private val all = ArrayList<Block>()

    /** Alle 35 Blöcke in der Reihenfolge des Creative-Tabs `hha:world`. */
    val ALL: List<Block> get() = all

    /** Pfade (ohne `hha:`) aller Deko- und Geräte-Blöcke; für den Prüflauf und die Vorschau. Unabhängig von [init]. */
    val DECO_IDS: Set<String> = linkedSetOf(
        "olympus_marble", "olympus_marble_bricks", "olympus_marble_brick_stairs", "olympus_marble_brick_slab",
        "olympus_marble_brick_wall", "polished_olympus_marble", "polished_olympus_marble_stairs",
        "polished_olympus_marble_slab", "chiseled_olympus_marble", "olympus_marble_pillar", "gilded_olympus_marble",
        "storm_marble", "olympus_bronze", "olympus_bronze_stairs", "olympus_bronze_slab", "storm_cloud",
        "tartarus_basalt", "tartarus_basalt_bricks", "tartarus_basalt_brick_stairs", "tartarus_basalt_brick_slab",
        "tartarus_basalt_brick_wall", "polished_tartarus_basalt", "polished_tartarus_basalt_stairs",
        "polished_tartarus_basalt_slab", "chiseled_tartarus_basalt", "tartarus_basalt_pillar", "soul_vein_basalt",
        "ember_basalt", "tartarus_bronze", "tartarus_bronze_stairs", "tartarus_bronze_slab",
        "messenger_bell", "grounding_rod", "chain_post", "sluice_wheel",
    )

    /** Die vier Geräte (Pfade); im Prüflauf erlaubt, im Bau nur an `DeviceSpot`s. */
    val DEVICE_IDS: Set<String> = linkedSetOf("messenger_bell", "grounding_rod", "chain_post", "sluice_wheel")

    const val HARDNESS = 1.8f
    const val RESISTANCE = 6f

    private fun <T : Block> register(name: String, settings: AbstractBlock.Settings, make: (AbstractBlock.Settings) -> T): T {
        val key = RegistryKey.of(RegistryKeys.BLOCK, Hha.id(name))
        val block = Registry.register(Registries.BLOCK, key, make(settings.registryKey(key)))
        all.add(block)
        return block
    }

    /** Grundeinstellungen eines Steinblocks der Familie. */
    private fun stone(color: MapColor, sounds: BlockSoundGroup = BlockSoundGroup.STONE, light: Int = 0): AbstractBlock.Settings =
        AbstractBlock.Settings.create()
            .mapColor(color)
            .instrument(NoteBlockInstrument.BASEDRUM)
            .requiresTool()
            .strength(HARDNESS, RESISTANCE)
            .sounds(sounds)
            .luminance { light }

    private fun cube(name: String, settings: AbstractBlock.Settings): Block = register(name, settings, ::Block)
    private fun pillar(name: String, settings: AbstractBlock.Settings): Block = register(name, settings, ::PillarBlock)
    private fun stairs(name: String, base: Block): Block =
        register(name, AbstractBlock.Settings.copyShallow(base)) { StairsBlock(base.defaultState, it) }
    private fun slab(name: String, base: Block): Block = register(name, AbstractBlock.Settings.copyShallow(base), ::SlabBlock)
    private fun wall(name: String, base: Block): Block = register(name, AbstractBlock.Settings.copyShallow(base).solid(), ::WallBlock)

    /** Geräte: unzerstörbar wie `boss_altar`, Licht 9 nur im Zustand 1 (golden), ohne Drop. */
    private fun device(name: String, color: MapColor, sounds: BlockSoundGroup): Block = register(
        name,
        AbstractBlock.Settings.create()
            .mapColor(color)
            .strength(-1f, 3600000f)
            .dropsNothing()
            .nonOpaque()
            .sounds(sounds)
            .allowsSpawning { _, _, _, _ -> false }
            .pistonBehavior(PistonBehavior.BLOCK)
            .luminance { if (it.get(ArenaDeviceBlock.MODE) == ArenaDeviceBlock.READY) 9 else 0 },
    ) { ArenaDeviceBlock(it, name) }

    /** Nach `HhaBlocks.init()`. */
    fun init() {
        if (all.isNotEmpty()) return
        OLYMPUS_MARBLE = cube("olympus_marble", stone(MapColor.OFF_WHITE))
        OLYMPUS_MARBLE_BRICKS = cube("olympus_marble_bricks", stone(MapColor.OFF_WHITE))
        OLYMPUS_MARBLE_BRICK_STAIRS = stairs("olympus_marble_brick_stairs", OLYMPUS_MARBLE_BRICKS)
        OLYMPUS_MARBLE_BRICK_SLAB = slab("olympus_marble_brick_slab", OLYMPUS_MARBLE_BRICKS)
        OLYMPUS_MARBLE_BRICK_WALL = wall("olympus_marble_brick_wall", OLYMPUS_MARBLE_BRICKS)
        POLISHED_OLYMPUS_MARBLE = cube("polished_olympus_marble", stone(MapColor.OFF_WHITE))
        POLISHED_OLYMPUS_MARBLE_STAIRS = stairs("polished_olympus_marble_stairs", POLISHED_OLYMPUS_MARBLE)
        POLISHED_OLYMPUS_MARBLE_SLAB = slab("polished_olympus_marble_slab", POLISHED_OLYMPUS_MARBLE)
        CHISELED_OLYMPUS_MARBLE = cube("chiseled_olympus_marble", stone(MapColor.OFF_WHITE))
        OLYMPUS_MARBLE_PILLAR = pillar("olympus_marble_pillar", stone(MapColor.OFF_WHITE))
        GILDED_OLYMPUS_MARBLE = cube("gilded_olympus_marble", stone(MapColor.GOLD))
        STORM_MARBLE = cube("storm_marble", stone(MapColor.TERRACOTTA_BLUE, light = 6))
        OLYMPUS_BRONZE = cube("olympus_bronze", stone(MapColor.ORANGE, BlockSoundGroup.COPPER))
        OLYMPUS_BRONZE_STAIRS = stairs("olympus_bronze_stairs", OLYMPUS_BRONZE)
        OLYMPUS_BRONZE_SLAB = slab("olympus_bronze_slab", OLYMPUS_BRONZE)
        STORM_CLOUD = register(
            "storm_cloud",
            AbstractBlock.Settings.create()
                .mapColor(MapColor.LIGHT_GRAY)
                .strength(0.2f)
                .noCollision()
                .nonOpaque()
                .dropsNothing()
                .sounds(BlockSoundGroup.WOOL)
                .allowsSpawning { _, _, _, _ -> false }
                .solidBlock { _, _, _ -> false }
                .suffocates { _, _, _ -> false }
                .blockVision { _, _, _ -> false },
            ::StormCloudBlock,
        )

        TARTARUS_BASALT = cube("tartarus_basalt", stone(MapColor.BLACK))
        TARTARUS_BASALT_BRICKS = cube("tartarus_basalt_bricks", stone(MapColor.BLACK))
        TARTARUS_BASALT_BRICK_STAIRS = stairs("tartarus_basalt_brick_stairs", TARTARUS_BASALT_BRICKS)
        TARTARUS_BASALT_BRICK_SLAB = slab("tartarus_basalt_brick_slab", TARTARUS_BASALT_BRICKS)
        TARTARUS_BASALT_BRICK_WALL = wall("tartarus_basalt_brick_wall", TARTARUS_BASALT_BRICKS)
        POLISHED_TARTARUS_BASALT = cube("polished_tartarus_basalt", stone(MapColor.BLACK))
        POLISHED_TARTARUS_BASALT_STAIRS = stairs("polished_tartarus_basalt_stairs", POLISHED_TARTARUS_BASALT)
        POLISHED_TARTARUS_BASALT_SLAB = slab("polished_tartarus_basalt_slab", POLISHED_TARTARUS_BASALT)
        CHISELED_TARTARUS_BASALT = cube("chiseled_tartarus_basalt", stone(MapColor.BLACK))
        TARTARUS_BASALT_PILLAR = pillar("tartarus_basalt_pillar", stone(MapColor.BLACK))
        SOUL_VEIN_BASALT = cube("soul_vein_basalt", stone(MapColor.BLACK, light = 7))
        EMBER_BASALT = cube("ember_basalt", stone(MapColor.BLACK, light = 10))
        TARTARUS_BRONZE = cube("tartarus_bronze", stone(MapColor.TERRACOTTA_GREEN, BlockSoundGroup.COPPER))
        TARTARUS_BRONZE_STAIRS = stairs("tartarus_bronze_stairs", TARTARUS_BRONZE)
        TARTARUS_BRONZE_SLAB = slab("tartarus_bronze_slab", TARTARUS_BRONZE)

        MESSENGER_BELL = device("messenger_bell", MapColor.GOLD, BlockSoundGroup.ANVIL)
        GROUNDING_ROD = device("grounding_rod", MapColor.ORANGE, BlockSoundGroup.COPPER)
        CHAIN_POST = device("chain_post", MapColor.TERRACOTTA_GREEN, BlockSoundGroup.CHAIN)
        SLUICE_WHEEL = device("sluice_wheel", MapColor.TERRACOTTA_GREEN, BlockSoundGroup.COPPER)

        check(all.size == DECO_IDS.size) { "HhaDecoBlocks: ${all.size} Blöcke registriert, ${DECO_IDS.size} erwartet" }
        check(all.map { Registries.BLOCK.getId(it).path } == DECO_IDS.toList()) { "HhaDecoBlocks: Reihenfolge oder IDs passen nicht zu DECO_IDS" }
    }

    /** BlockItems und der Creative-Tab `hha:world` („H&H Welt“); aus `Hha.onInitialize` nach `HhaItems.init()`. */
    fun registerItems() {
        val items = all.map { Items.register(it) }
        val key = RegistryKey.of(RegistryKeys.ITEM_GROUP, Hha.id("world"))
        Registry.register(
            Registries.ITEM_GROUP, key,
            FabricItemGroup.builder()
                .icon { ItemStack(GILDED_OLYMPUS_MARBLE.asItem()) }
                .displayName(Text.translatable("itemGroup.hha.world"))
                .entries { _, entries -> items.forEach { entries.add(it) } }
                .build(),
        )
    }
}
