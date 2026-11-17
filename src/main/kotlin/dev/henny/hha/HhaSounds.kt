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
    val CHAIN = register("boss.chain")
    val PORTAL_HUM = register("boss.portal_hum")
    val PORTAL_ENTER = register("boss.portal_enter")
    val THEME_MESSENGER = register("music.messenger")
    val THEME_THIEF = register("music.thief")
    val THEME_GUIDE = register("music.guide")
    val THEME_FINALE = register("music.finale")
    val THEME_HADES = register("music.hades")

    // Überarbeitung 2 (PLAN 10.1): zunächst auf vorhandene Dateien gelegt (tmp/overhaul2/snippets/P00/sounds.json).
    val OLYMPUS_PORTAL_HUM = register("olympus_portal_hum")
    val OLYMPUS_PORTAL_ENTER = register("olympus_portal_enter")
    // Hinweis: `boss.thunder` gibt es schon ([THUNDER]); der Plan zählt es mit auf.
    val THUNDER_CALL = register("boss.thunder_call")
    val ANVIL = register("boss.anvil")
    val FORGE = register("boss.forge")
    val COMMAND = register("boss.command")
    val SHIELD = register("boss.shield")
    val TORCH = register("boss.torch")
    val HOWL = register("boss.howl")
    val KEY = register("boss.key")
    val KURETE_CLASH = register("mob.kurete_clash")
    val GRIFFIN_CRY = register("mob.griffin_cry")
    val AUTOMATON_WAKE = register("mob.automaton_wake")
    val THEME_OLYMPUS = register("music.olympus")
    val THEME_HEKATE = register("music.hekate")
    val THEME_BRONTES = register("music.brontes")
    val THEME_KRATOS = register("music.kratos")
    val THEME_ZEUS = register("music.zeus")

    // Überarbeitung 3 (PLAN 6.8): IDs vorab registriert (P00); Dateien und sounds.json-Einträge liefert P04.
    // Musik (Schleifen außer den Stingern):
    val THEME_TARTAROS = register("music.tartaros")
    val THEME_TUNNEL = register("music.tunnel")
    val THEME_HEKATE_2 = register("music.hekate_2")
    val THEME_BRONTES_2 = register("music.brontes_2")
    val THEME_KRATOS_2 = register("music.kratos_2")
    val THEME_ZEUS_2 = register("music.zeus_2")
    val THEME_ZEUS_3 = register("music.zeus_3")
    val THEME_HADES_2 = register("music.hades_2")
    val THEME_KRONOS = register("music.kronos")
    val THEME_KRONOS_2 = register("music.kronos_2")
    val THEME_GUARDIAN = register("music.guardian")
    val THEME_GUARDIAN_2 = register("music.guardian_2")
    val VICTORY_OLYMP = register("music.victory_olymp")
    val VICTORY_TARTAROS = register("music.victory_tartaros")
    val THEME_CREDITS = register("music.credits")

    // Klänge der neuen Effekte:
    val CRACK = register("boss.crack")
    val DEBRIS = register("boss.debris")
    /** Tiefer Schlag zum Kamera-Ruck. */
    val THUD = register("boss.thud")
    val LENS = register("boss.lens")
    val TIME_STOP = register("boss.time_stop")
    val TEAR = register("boss.tear")
    val SWIRL = register("boss.swirl")
    val WAVE = register("boss.wave")

    // RPG-Modus:
    val RPG_TUNNEL_LOOP = register("rpg.tunnel_loop")
    val RPG_DEPART = register("rpg.depart")
    val RPG_ARRIVE = register("rpg.arrive")
    val RPG_CHAPTER = register("rpg.chapter")
    val RPG_OBJECTIVE = register("rpg.objective")
    val RPG_JOURNAL = register("rpg.journal")
    val RPG_REST = register("rpg.rest")
    val RPG_FALLEN = register("rpg.fallen")
    val RPG_DISSOLVE = register("rpg.dissolve")

    private fun register(path: String): SoundEvent {
        val id = Hha.id(path)
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id))
    }

    fun init() {}
}
