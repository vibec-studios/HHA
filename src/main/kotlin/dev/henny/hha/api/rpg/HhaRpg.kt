package dev.henny.hha.api.rpg

import dev.henny.hha.rpg.Arrival
import dev.henny.hha.rpg.RpgWorld
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.item.ItemStack
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity

/**
 * Öffentliche Schnittstelle des RPG-Modus für Addons.
 *
 * Der RPG-Modus startet im Hauptmenü („HHA RPG“): Eine Gruppe wählt einen **Weg** und die **Addons**, die mitspielen,
 * der Host startet, alle landen ohne Ladebildschirm in derselben neuen Welt. In einer RPG-Welt gelten nur Addons, die
 * den Modus **unterstützen** und in der Lobby gewählt wurden.
 *
 * Ein Addon unterstützt den RPG-Modus, wenn es
 * - in seiner `fabric.mod.json` unter `custom` den Eintrag `"hha:rpg": true` trägt, oder
 * - in `HhaAddon.onInitialize` [declareSupport] aufruft (oder einen [Path] registriert).
 *
 * Addons fragen mit [isAddonActive] ab, ob ihre Inhalte in der laufenden Welt aktiv sein dürfen (in normalen Welten
 * immer ja). Eigene Wege (eigener Startpunkt, eigener Endboss) registrieren sie mit [registerPath].
 */
object HhaRpg {

    /**
     * Ein Weg, den die Gruppe in der Lobby wählt. Texte: [titleKey], [descKey] (Sprachschlüssel).
     *
     * - Ohne [arrival] (bisheriges Verhalten): [start] wird für jeden Spieler einmal beim ersten Betreten der RPG-Welt
     *   aufgerufen, 20 Ticks nach dem Beitritt (Teleport zum Anfang, Startausrüstung …).
     * - Mit [arrival] (Überarbeitung 3): Die Welt beginnt direkt dort (Weltspawn), die Gruppe wartet gemeinsam und
     *   landet zusammen in der Ankunftsszene [arrivalStage] (Standard `rpg.arrival.<id>`). HHA teleportiert dann
     *   selbst; [start] läuft je Spieler **einmal nach seiner Ankunft** (nach der Szene) für weg-eigene Dinge.
     * - [costume]: Rüstung, die die Figuren in Lobby und Ankunftsszene tragen (rein am Client, nie im Inventar);
     *   leer = Heaven-Set.
     */
    class Path(
        val id: String,
        val addonId: String,
        val titleKey: String,
        val descKey: String,
        val color: Int,
        val start: (ServerPlayerEntity) -> Unit,
        val costume: () -> List<ItemStack> = { emptyList() },
        val arrival: ((MinecraftServer) -> Arrival)? = null,
        val arrivalStage: String? = null,
    ) {
        /**
         * Die bisherige Form (`Path(id, addon, titel, text, farbe) { player -> … }`): Ein nachgestelltes Lambda bindet
         * in Kotlin an den **letzten** Parameter; dieser Konstruktor hält deshalb [start] am Ende, damit Addons
         * unverändert kompilieren.
         */
        constructor(
            id: String,
            addonId: String,
            titleKey: String,
            descKey: String,
            color: Int,
            start: (ServerPlayerEntity) -> Unit,
        ) : this(id, addonId, titleKey, descKey, color, start, { emptyList() }, null, null)
    }

    private val paths = LinkedHashMap<String, Path>()
    private val declared = HashSet<String>()

    /** Registriert einen Weg. Der erste mit einer ID gewinnt. Gleichzeitig gilt das Addon als RPG-fähig. */
    fun registerPath(path: Path) {
        paths.putIfAbsent(path.id, path)
        declared.add(path.addonId)
    }

    /** Erklärt ein Addon als RPG-fähig (ohne eigenen Weg). */
    fun declareSupport(addonId: String) {
        declared.add(addonId)
    }

    /** Alle Wege (HHA zuerst, dann Addons in Ladereihenfolge). */
    fun paths(): List<Path> = paths.values.toList()

    fun path(id: String): Path? = paths[id]

    /** Unterstützt das Addon [addonId] den RPG-Modus? (Metadaten `hha:rpg`, [declareSupport] oder ein Weg) */
    fun supports(addonId: String): Boolean {
        if (addonId in declared) return true
        val meta = FabricLoader.getInstance().getModContainer(addonId).map { it.metadata }.orElse(null) ?: return false
        val value = meta.getCustomValue("hha:rpg") ?: return false
        return runCatching { value.asBoolean }.getOrDefault(false)
    }

    /** Läuft auf [server] eine RPG-Welt? */
    fun isRpgWorld(server: MinecraftServer): Boolean = RpgWorld.of(server).enabled

    /** Gewählter Weg der laufenden RPG-Welt (`null` außerhalb des RPG-Modus). */
    fun currentPath(server: MinecraftServer): String? = RpgWorld.of(server).takeIf { it.enabled }?.path

    /**
     * Dürfen die Inhalte von [addonId] in dieser Welt laufen? Außerhalb des RPG-Modus immer; im RPG-Modus nur, wenn das
     * Addon den Modus unterstützt und in der Lobby gewählt wurde.
     */
    fun isAddonActive(server: MinecraftServer, addonId: String): Boolean {
        val state = RpgWorld.of(server)
        if (!state.enabled) return true
        return supports(addonId) && addonId in state.addons
    }
}
