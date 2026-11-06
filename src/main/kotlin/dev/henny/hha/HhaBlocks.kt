package dev.henny.hha

import dev.henny.hha.block.GreenFireBlock
import dev.henny.hha.block.HadesAltarBlock
import dev.henny.hha.block.HadesPortalBlock
import dev.henny.hha.block.InscribedBlock
import dev.henny.hha.block.OfferingPedestalBlock
import net.minecraft.block.AbstractBlock
import net.minecraft.block.Block
import net.minecraft.block.MapColor
import net.minecraft.block.piston.PistonBehavior
import net.minecraft.item.BlockItem
import net.minecraft.item.Item
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.sound.BlockSoundGroup

/** Blöcke von Olymp, Hades und Tartaros. Nur die Baublöcke des Ödlands gibt es auch als Items; der Rest entsteht in der Welt. */
object HhaBlocks {

    lateinit var GREEN_FIRE: Block; private set
    lateinit var HADES_PORTAL: Block; private set
    lateinit var OFFERING_PEDESTAL: Block; private set
    lateinit var OLYMPUS_ALTAR: Block; private set
    lateinit var HADES_ALTAR: Block; private set
    lateinit var ASH_STONE: Block; private set
    lateinit var DEEP_ASH: Block; private set
    lateinit var RUST_SOIL: Block; private set
    lateinit var GILDED_ASH: Block; private set
    lateinit var PALE_CRYSTAL: Block; private set
    lateinit var CRYSTAL_MOSS: Block; private set
    lateinit var GATE_STONE: Block; private set

    /** Die Baublöcke des Tartaros, die es auch als Items gibt. */
    val BUILDING = ArrayList<Item>()

    private fun building(name: String, settings: AbstractBlock.Settings): Block {
        val block = register(name, settings) { Block(it) }
        val key = RegistryKey.of(RegistryKeys.ITEM, Hha.id(name))
        val item = Registry.register(Registries.ITEM, key, BlockItem(block, Item.Settings().registryKey(key).useBlockPrefixedTranslationKey()))
        BUILDING.add(item)
        return block
    }

    private fun <T : Block> register(name: String, settings: AbstractBlock.Settings, make: (AbstractBlock.Settings) -> T): T {
        val key = RegistryKey.of(RegistryKeys.BLOCK, Hha.id(name))
        return Registry.register(Registries.BLOCK, key, make(settings.registryKey(key)))
    }

    /** Unzerstörbar wie Grundgestein: das Rätsel und der Kampf dürfen nicht an einem Spitzhacken-Schlag scheitern. */
    private fun sacred(color: MapColor, light: Int = 0): AbstractBlock.Settings = AbstractBlock.Settings.create()
        .mapColor(color)
        .strength(-1f, 3600000f)
        .dropsNothing()
        .allowsSpawning { _, _, _, _ -> false }
        .luminance { light }

    fun init() {
        GREEN_FIRE = register(
            "green_fire",
            AbstractBlock.Settings.create()
                .mapColor(MapColor.EMERALD_GREEN)
                .replaceable()
                .noCollision()
                .breakInstantly()
                .luminance { 15 }
                .sounds(BlockSoundGroup.WOOL)
                .pistonBehavior(PistonBehavior.DESTROY),
            ::GreenFireBlock,
        )
        HADES_PORTAL = register(
            "hades_portal",
            AbstractBlock.Settings.create()
                .mapColor(MapColor.EMERALD_GREEN)
                .noCollision()
                .strength(-1f, 3600000f)
                .dropsNothing()
                .luminance { 13 }
                .sounds(BlockSoundGroup.GLASS)
                .pistonBehavior(PistonBehavior.BLOCK),
            ::HadesPortalBlock,
        )
        OFFERING_PEDESTAL = register("offering_pedestal", sacred(MapColor.OFF_WHITE), ::OfferingPedestalBlock)
        OLYMPUS_ALTAR = register("olympus_altar", sacred(MapColor.GOLD, 9)) { InscribedBlock(it, "hha.olympus.altar") }
        HADES_ALTAR = register("hades_altar", sacred(MapColor.BLACK, 7), ::HadesAltarBlock)

        fun rock(color: MapColor, hardness: Float) = AbstractBlock.Settings.create().mapColor(color).requiresTool().strength(hardness, 6f).sounds(BlockSoundGroup.DEEPSLATE)
        ASH_STONE = building("ash_stone", rock(MapColor.DEEPSLATE_GRAY, 1.5f))
        DEEP_ASH = building("deep_ash", rock(MapColor.BLACK, 2.5f))
        GILDED_ASH = building("gilded_ash", rock(MapColor.STONE_GRAY, 1.5f))
        GATE_STONE = building("gate_stone", rock(MapColor.BLACK, 30f).luminance { 2 })
        RUST_SOIL = building("rust_soil", AbstractBlock.Settings.create().mapColor(MapColor.BROWN).strength(0.6f).sounds(BlockSoundGroup.ROOTED_DIRT))
        CRYSTAL_MOSS = building("crystal_moss", AbstractBlock.Settings.create().mapColor(MapColor.PALE_GREEN).strength(0.4f).luminance { 4 }.sounds(BlockSoundGroup.MOSS_BLOCK))
        PALE_CRYSTAL = building("pale_crystal", AbstractBlock.Settings.create().mapColor(MapColor.PALE_GREEN).requiresTool().strength(1.2f).luminance { 8 }.sounds(BlockSoundGroup.AMETHYST_BLOCK))
    }
}
