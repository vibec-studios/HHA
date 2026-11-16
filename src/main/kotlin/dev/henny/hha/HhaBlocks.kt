package dev.henny.hha

import dev.henny.hha.block.GreenFireBlock
import dev.henny.hha.block.HadesAltarBlock
import dev.henny.hha.block.HadesPortalBlock
import dev.henny.hha.block.InscribedBlock
import dev.henny.hha.block.HadesSealBlock
import dev.henny.hha.block.OfferingPedestalBlock
import dev.henny.hha.block.SteleBlock
import net.minecraft.block.AbstractBlock
import net.minecraft.block.Block
import net.minecraft.block.MapColor
import net.minecraft.block.piston.PistonBehavior
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.sound.BlockSoundGroup

/** Nur Blöcke mit eigener Funktion: Bauten und Gelände bestehen komplett aus Vanilla-Blöcken. */
object HhaBlocks {

    lateinit var GREEN_FIRE: Block; private set
    lateinit var HADES_PORTAL: Block; private set
    lateinit var OFFERING_PEDESTAL: Block; private set
    lateinit var OLYMPUS_ALTAR: Block; private set
    lateinit var HADES_ALTAR: Block; private set
    lateinit var STELE: Block; private set
    lateinit var HADES_SEAL: Block; private set

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

        STELE = register("stele", sacred(MapColor.BLACK, 3), ::SteleBlock)
        HADES_SEAL = register("hades_seal", sacred(MapColor.EMERALD_GREEN, 11), ::HadesSealBlock)
    }
}
