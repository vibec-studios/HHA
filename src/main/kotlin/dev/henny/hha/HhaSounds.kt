package dev.henny.hha

import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.sound.SoundEvent

/** Eigene Klänge des Bossfights; die OGGs erzeugt tools/hha_assets/boss_sounds.py. */
object HhaSounds {

    val WHOOSH = register("boss.whoosh")
    val DASH = register("boss.dash")
    val IMPACT = register("boss.impact")
    val THUNDER = register("boss.thunder")
    val RIFT = register("boss.rift")
    val SOUL = register("boss.soul")
    val CHIME = register("boss.chime")
    val STING = register("boss.sting")
    val VOICE = register("boss.voice")
    val CHARGE = register("boss.charge")
    val BLINK = register("boss.blink")
    val DEFLECT = register("boss.deflect")
    val HIT = register("boss.hit")
    val FEATHER_VOLLEY = register("boss.feather_volley")
    val SPEAR = register("boss.spear")
    val ROAR = register("boss.roar")
    val HISS = register("boss.hiss")
    val BITE = register("boss.bite")
    val FLOOD = register("boss.flood")
    val PORTAL_HUM = register("boss.portal_hum")
    val PORTAL_ENTER = register("boss.portal_enter")
    val THEME_MESSENGER = register("music.messenger")
    val THEME_THIEF = register("music.thief")
    val THEME_GUIDE = register("music.guide")
    val THEME_FINALE = register("music.finale")
    val THEME_SHADE = register("music.shade")

    private fun register(path: String): SoundEvent {
        val id = Hha.id(path)
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id))
    }

    fun init() {}
}
