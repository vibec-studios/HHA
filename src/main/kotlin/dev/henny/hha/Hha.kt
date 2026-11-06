package dev.henny.hha

import dev.henny.hha.config.HhaCommands
import dev.henny.hha.config.HhaConfig
import dev.henny.hha.config.InfoCommand
import dev.henny.hha.logic.CombatEvents
import dev.henny.hha.logic.DivineShield
import dev.henny.hha.logic.FactionLock
import dev.henny.hha.logic.PassiveEffects
import dev.henny.hha.net.HhaNetworking
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory

class Hha : ModInitializer {

    override fun onInitialize() {
        HhaSounds.init()
        HhaBlocks.init()
        HhaItems.init()
        dev.henny.hha.world.HhaWorldgen.init()
        HhaEntities.init()
        HhaParticles.init()
        HhaBuiltins.init()

        // Addons registrieren Items, Sets, Abilities und Config-Defaults —
        // deshalb vor HhaConfig.load(), damit gespeicherte Addon-Werte greifen.
        dev.henny.hha.addon.AddonLoader.init()

        HhaConfig.load()
        dev.henny.hha.config.ConfigResourceCondition.register()
        dev.henny.hha.config.CustomRecipePack.init()
        dev.henny.hha.logic.Trust.load()
        HhaCommands.register()
        InfoCommand.register()
        HhaNetworking.init()
        FactionLock.init()
        CombatEvents.init()
        DivineShield.init()
        dev.henny.hha.logic.boss.HermesFights.init()
        ServerTickEvents.END_SERVER_TICK.register(PassiveEffects::tick)
        LOGGER.info("Hell's & Heaven's Set geladen — stay crispy, fly high.")
    }

    companion object {
        const val MOD_ID = "hha"
        val LOGGER = LoggerFactory.getLogger(MOD_ID)!!

        fun id(path: String): Identifier = Identifier.of(MOD_ID, path)
    }
}
