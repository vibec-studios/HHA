package dev.henny.hha.logic.boss

import dev.henny.hha.Hha
import dev.henny.hha.HhaItems
import dev.henny.hha.config.HhaConfig
import dev.henny.hha.logic.world.RelicLedger
import net.minecraft.entity.ItemEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvents
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import net.minecraft.util.math.random.Random

/**
 * Die **einzige** Stelle, die Kampfbeute vergibt (Überarbeitung 2, PLAN 6.1/6.2).
 *
 * Ablauf von [grant]:
 * 1. **Persönliche Beute** an jeden Spieler der Liste (ins Inventar, sonst vor die Füße), Erfahrung als Level.
 *    Bei Hermes zusätzlich der Schwarze Brief (`hades_map`), wenn der Spieler keinen bei sich trägt.
 * 2. Je **Reliquie** des Bosses: Ist sie laut [RelicLedger] frei (oder `relic_drops` aus), fällt sie als leuchtender
 *    Gegenstand bei `at` (10 Minuten haltbar; die Gruppe entscheidet, wer sie nimmt), wird mit Boss, nächststehendem
 *    Spieler und Zeit eingetragen und allen Mitkämpfern gemeldet (`hha.relic.dropped`).
 * 3. War mindestens eine Reliquie des Bosses schon gefallen, bekommt jeder Spieler **einmal je Sieg** den Ersatz,
 *    mit dem Hinweis `hha.relic.already`.
 *
 * Boss-Schlüssel: `hermes`, `hekate`, `brontes`, `kratos`, `zeus`, `warden`, `sisyphus`, `ixion`, `tantalus`, `kampe`,
 * `hades`. Kits berühren das Register nie (`logic/Kits.kt`).
 */
object BossRewards {

    /** Ein Stapel persönlicher Beute: [min]..[max] Stück (gleichverteilt). */
    class Loot(val item: () -> Item, val min: Int, val max: Int = min) {
        fun roll(random: Random): ItemStack =
            ItemStack(item(), if (max > min) min + random.nextInt(max - min + 1) else min)
    }

    /**
     * Eine Zeile der Tabelle 6.1: [relics] fallen je Welt einmal, [replacement] je Spieler, wenn eine davon schon
     * gefallen ist, [personal] und [levels] bekommt jeder Spieler bei jedem Sieg.
     */
    class Reward(
        val relics: List<String>,
        val replacement: List<Loot>,
        val personal: List<Loot>,
        val levels: Int,
    )

    private fun loot(item: Item, min: Int, max: Int = min) = Loot({ item }, min, max)

    /** Für mod-eigene Items, die erst nach [HhaItems.register] feststehen. */
    private fun hha(min: Int, max: Int = min, item: () -> Item) = Loot(item, min, max)

    /** Persönliche Beute der fünf Wächter der Kette (Kampe bekommt zusätzlich Netheritschrott). */
    private val WARDEN_LOOT: List<Loot> by lazy {
        listOf(
            hha(2) { HhaItems.HELL_INGOT },
            loot(Items.DIAMOND, 2, 4),
            loot(Items.GOLDEN_APPLE, 1, 2),
        )
    }

    /** Tabelle 6.1 als Daten (Reihenfolge = Pfad). */
    val TABLE: Map<String, Reward> by lazy {
        linkedMapOf(
            "hermes" to Reward(
                relics = listOf("heaven_boots"),
                replacement = listOf(hha(2) { HhaItems.HEAVEN_INGOT }),
                personal = listOf(hha(3) { HhaItems.HERMES_FEATHER }, loot(Items.GOLDEN_APPLE, 1)),
                levels = 30,
            ),
            "hekate" to Reward(
                relics = emptyList(),
                replacement = emptyList(),
                personal = listOf(
                    hha(1) { HhaItems.JOTUNHEIMS_UPGRADE_TEMPLATE },
                    loot(Items.NETHERITE_SCRAP, 4),
                    loot(Items.GOLDEN_APPLE, 2),
                ),
                levels = 30,
            ),
            "brontes" to Reward(
                relics = listOf("heavens_mace", "heaven_leggings"),
                replacement = listOf(hha(3) { HhaItems.HEAVEN_INGOT }),
                personal = listOf(
                    hha(2) { HhaItems.HEAVEN_INGOT },
                    loot(Items.COPPER_BLOCK, 8),
                    loot(Items.ENCHANTED_GOLDEN_APPLE, 1),
                ),
                levels = 40,
            ),
            "kratos" to Reward(
                relics = listOf("heavens_sword"),
                replacement = listOf(hha(2) { HhaItems.HEAVEN_INGOT }),
                personal = listOf(
                    hha(2) { HhaItems.HEAVEN_INGOT },
                    loot(Items.DIAMOND, 4),
                    loot(Items.ENCHANTED_GOLDEN_APPLE, 1),
                ),
                levels = 40,
            ),
            "zeus" to Reward(
                relics = listOf("heaven_helmet", "heaven_chestplate"),
                replacement = listOf(hha(4) { HhaItems.HEAVEN_INGOT }),
                personal = listOf(
                    hha(3) { HhaItems.HEAVEN_INGOT },
                    loot(Items.NETHER_STAR, 1),
                    loot(Items.ENCHANTED_GOLDEN_APPLE, 2),
                ),
                levels = 80,
            ),
            "warden" to Reward(listOf("hells_mace"), listOf(hha(2) { HhaItems.HELL_INGOT }), WARDEN_LOOT, 30),
            "sisyphus" to Reward(listOf("hell_boots"), listOf(hha(2) { HhaItems.HELL_INGOT }), WARDEN_LOOT, 30),
            "ixion" to Reward(listOf("hells_sword"), listOf(hha(2) { HhaItems.HELL_INGOT }), WARDEN_LOOT, 30),
            "tantalus" to Reward(listOf("hell_leggings"), listOf(hha(2) { HhaItems.HELL_INGOT }), WARDEN_LOOT, 30),
            "kampe" to Reward(
                relics = listOf("hell_chestplate"),
                replacement = listOf(hha(3) { HhaItems.HELL_INGOT }),
                personal = WARDEN_LOOT + loot(Items.NETHERITE_SCRAP, 2),
                levels = 30,
            ),
            "hades" to Reward(
                relics = listOf("hell_helmet"),
                replacement = listOf(hha(4) { HhaItems.HELL_INGOT }),
                personal = listOf(
                    hha(3) { HhaItems.HELL_INGOT },
                    loot(Items.NETHER_STAR, 1),
                    loot(Items.ENCHANTED_GOLDEN_APPLE, 2),
                ),
                levels = 80,
            ),
        )
    }

    /** Alle Boss-Schlüssel, die [grant] kennt. */
    val BOSSES: List<String> get() = TABLE.keys.toList()

    /** So lange liegt eine gefallene Reliquie (Ticks): `setCovetedItem` gibt 12000 Ticks = 10 Minuten. */
    const val RELIC_LIFETIME = 12000

    private val grantListeners = ArrayList<(MinecraftServer, String, List<ServerPlayerEntity>) -> Unit>()

    /**
     * Überarbeitung 3 (PLAN 6.9, additiv): [listener] erfährt jeden Sieg als (Server, Boss-Schlüssel in Kleinbuchstaben,
     * Mitkämpfer). Gerufen **am Anfang** von [grant], also auch für Schlüssel ohne Tabellenzeile (z. B. `kronos`), aber
     * nur, wenn mindestens ein Mitkämpfer den Server liefert. Fehler eines Lauschers werden geloggt und stören die Beute nicht.
     */
    fun onGrant(listener: (MinecraftServer, String, List<ServerPlayerEntity>) -> Unit) {
        grantListeners.add(listener)
    }

    /** Sieg über [boss]: Beute an [players] (alle, die am Ende noch mitkämpfen), Reliquie bzw. Ersatz bei [at]. */
    fun grant(boss: String, players: List<ServerPlayerEntity>, at: Vec3d) {
        val key = boss.lowercase()
        players.firstNotNullOfOrNull { it.entityWorld.server }?.let { server ->
            for (listener in grantListeners) {
                try {
                    listener(server, key, players)
                } catch (e: Exception) {
                    Hha.LOGGER.error("BossRewards.onGrant: Lauscher für '{}' fehlgeschlagen", key, e)
                }
            }
        }
        val reward = TABLE[key]
        if (reward == null) {
            Hha.LOGGER.warn("BossRewards.grant: unbekannter Boss '{}'", boss)
            return
        }
        // Ohne Mitkämpfer weiß niemand, in welcher Welt die Beute liegen soll: Die Reliquie bleibt frei.
        val nearest = players.minByOrNull { it.squaredDistanceTo(at) }
        if (nearest == null) {
            Hha.LOGGER.info("BossRewards.grant({}): keine Mitkämpfer, keine Beute", key)
            return
        }
        val server = nearest.entityWorld.server ?: return
        val world = nearest.entityWorld as? ServerWorld ?: return

        for (player in players) {
            for (entry in reward.personal) give(player, entry.roll(world.random))
            if (key == "hermes" && !hasLetter(player)) {
                give(player, ItemStack(HhaItems.HADES_MAP))
                player.sendMessage(Text.translatable("hha.hades.map.found").formatted(Formatting.GREEN, Formatting.ITALIC), false)
            }
            if (reward.levels > 0) player.addExperienceLevels(reward.levels)
        }

        val unique = HhaConfig.enabled("relic_drops")
        var already = false
        for (id in reward.relics) {
            val item = relic(id) ?: continue
            if (unique && RelicLedger.isDropped(server, id)) {
                already = true
                continue
            }
            dropRelic(world, at, ItemStack(item))
            RelicLedger.mark(server, id, key, nearest.gameProfile.name)
            val message = Text.translatable("hha.relic.dropped", relicName(id)).formatted(Formatting.GRAY)
            for (player in players) player.sendMessage(message, false)
        }
        if (already && reward.replacement.isNotEmpty()) {
            for (player in players) {
                for (entry in reward.replacement) give(player, entry.roll(world.random))
                player.sendMessage(Text.translatable("hha.relic.already").formatted(Formatting.GRAY, Formatting.ITALIC), false)
            }
        }
    }

    /**
     * Gibt [player] die Reliquie [id] sofort und trägt sie als gefallen ein (`/hha relics drop`). [boss] steht im
     * Register (Standard `command`). `false` bei unbekannter ID.
     */
    fun forceDrop(server: MinecraftServer, id: String, player: ServerPlayerEntity, boss: String = "command"): Boolean {
        val item = relic(id) ?: return false
        give(player, ItemStack(item))
        RelicLedger.mark(server, id, boss, player.gameProfile.name)
        return true
    }

    /** Item der Reliquie [id] (siehe [RelicLedger.IDS]), `null` bei unbekannter ID. */
    fun relic(id: String): Item? {
        if (id !in RelicLedger.IDS) return null
        return Registries.ITEM.get(Hha.id(id)).takeIf { it != Items.AIR }
    }

    /** Alle Reliquien-IDs. */
    fun allRelics(): List<String> = RelicLedger.IDS

    /** Welcher Boss die Reliquie [id] laut Tabelle gibt (für `/hha relics`), sonst `null`. */
    fun bossOf(id: String): String? = TABLE.entries.firstOrNull { id in it.value.relics }?.key

    /** Gehört [id] zum Himmels-Set? (Sonst Hölle.) */
    fun isHeaven(id: String): Boolean = id.startsWith("heaven")

    /** Set-Farbe einer Reliquie: Gold für den Himmel, Rot für die Hölle. */
    fun setColor(id: String): Formatting = if (isHeaven(id)) Formatting.GOLD else Formatting.RED

    /** Name der Reliquie in der Set-Farbe (Item-Name aus der Sprachdatei). */
    fun relicName(id: String): MutableText {
        val item = relic(id)
        val name = if (item != null) Text.translatable(item.translationKey) else Text.literal(id)
        return name.formatted(setColor(id))
    }

    // ---------------------------------------------------------------------------------------------

    private fun hasLetter(player: ServerPlayerEntity): Boolean {
        val inventory = player.inventory
        for (slot in 0 until inventory.size()) if (inventory.getStack(slot).isOf(HhaItems.HADES_MAP)) return true
        return false
    }

    /** Liegt still, leuchtet, hält 10 Minuten; jeder darf sie aufheben. */
    private fun dropRelic(world: ServerWorld, at: Vec3d, stack: ItemStack) {
        val entity = ItemEntity(world, at.x, at.y + 0.5, at.z, stack, 0.0, 0.2, 0.0)
        entity.setCovetedItem()
        entity.setToDefaultPickupDelay()
        entity.isGlowing = true
        world.spawnEntity(entity)
        world.playSound(null, at.x, at.y, at.z, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.8f, 1.2f)
    }

    private fun give(player: ServerPlayerEntity, stack: ItemStack) {
        if (stack.isEmpty) return
        if (!player.giveItemStack(stack)) player.dropItem(stack, false)
    }
}
