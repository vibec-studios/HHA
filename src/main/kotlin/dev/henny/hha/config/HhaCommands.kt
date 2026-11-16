package dev.henny.hha.config

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.suggestion.SuggestionProvider
import dev.henny.hha.api.HhaAddons
import dev.henny.hha.api.set.ArmorSet
import dev.henny.hha.api.set.HhaSets
import dev.henny.hha.entity.boss.DuelBossEntity
import dev.henny.hha.logic.Trust
import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.BossRewards
import dev.henny.hha.logic.world.HadesGate
import dev.henny.hha.logic.world.OlympusProgress
import dev.henny.hha.logic.world.RelicLedger
import dev.henny.hha.logic.world.TartarosChain
import dev.henny.hha.net.HhaNetworking
import dev.henny.hha.world.olymp.OlympusLayout
import net.minecraft.block.Blocks
import net.minecraft.network.packet.s2c.play.PositionFlag
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraft.world.Heightmap
import kotlin.math.atan2
import kotlin.math.hypot
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.command.CommandSource
import net.minecraft.command.argument.EntityArgumentType
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * Developer-Menü: /hha — Fähigkeiten togglen und Werte live anpassen (OP Level 2).
 *
 *   /hha list                  – alle Einstellungen anzeigen
 *   /hha toggle <key> <bool>   – Fähigkeit an-/ausschalten
 *   /hha set <key> <zahl>      – Schaden/Schwelle setzen
 *   /hha get <key>             – einzelnen Wert anzeigen
 *   /hha reset                 – alles auf Default
 *   /hha kit heaven|hell       – Diamond-SMP-Kit mit Set-Rüstung (für alle,
 *                                sofern kit_mode aktiv ist; im Kreativmodus immer)
 *
 * Reliquien, Fortschritt und Kämpfe (Überarbeitung 2, PLAN 6.3, alle OP):
 *   /hha relics                          – alle zwölf Reliquien: frei oder gefallen (wann, bei wem, an wen)
 *   /hha relics reset <id|all>           – Eintrag löschen, die Reliquie kann wieder fallen
 *   /hha relics mark <id> <bool>         – als gefallen eintragen bzw. austragen, ohne etwas zu geben
 *   /hha relics drop <id> [spieler]      – Reliquie sofort geben und eintragen
 *   /hha progress                        – Olymp-Pfad und Tartaros-Kette, eine Zeile je Schlüssel
 *   /hha progress set <schlüssel> <bool> – hermes, hekate, brontes, kratos, zeus, warden, kampe, sisyphus,
 *                                          ixion, tantalus, hades
 *   /hha boss start <boss>               – hermes|hekate|brontes|kratos|zeus an seiner Insel (Aufrufer wird
 *                                          hingebracht, wenn er nicht im Olymp oder > 80 Blöcke entfernt ist)
 *   /hha boss stop|skip [boss]           – laufenden Kampf beenden / zur nächsten Phase
 *   /hha tp <ort>                        – olymp, tempel, dreiweg, schmiede, stufen, thron, tartaros
 */
object HhaCommands {

    private val TOGGLE_KEYS = SuggestionProvider<ServerCommandSource> { _, builder ->
        CommandSource.suggestMatching(HhaConfig.TOGGLE_DEFAULTS.keys, builder)
    }

    private val NUMBER_KEYS = SuggestionProvider<ServerCommandSource> { _, builder ->
        CommandSource.suggestMatching(HhaConfig.NUMBER_DEFAULTS.keys, builder)
    }

    /** Alle registrierten Sets — `hha:`-Sets ohne Präfix, Addon-Sets als `addonid.pfad`. */
    private val SET_KEYS = SuggestionProvider<ServerCommandSource> { _, builder ->
        CommandSource.suggestMatching(
            HhaSets.all().map { set ->
                if (set.id.namespace == "hha") set.id.path else "${set.id.namespace}.${set.id.path}"
            } + dev.henny.hha.logic.Kits.VanillaKit.entries.map { it.key },
            builder,
        )
    }

    /** Admin-Unterbefehle brauchen OP Level 2 — /hha kit bleibt für alle offen. */
    private val ADMIN = CommandManager.requirePermissionLevel<ServerCommandSource>(CommandManager.GAMEMASTERS_CHECK)

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                literal("hha")
                    .then(
                        literal("kit").then(
                            argument("set", StringArgumentType.word()).suggests(SET_KEYS).executes { ctx ->
                                giveKit(ctx.source, StringArgumentType.getString(ctx, "set"))
                            }
                        )
                    )
                    .then(literal("addons").executes { ctx -> listAddons(ctx.source) })
                    .then(relicsCommand())
                    .then(progressCommand())
                    .then(bossCommand())
                    .then(tpCommand())
                    .then(olympCommand())
                    .then(
                        literal("hermes").requires(ADMIN)
                            .then(literal("start").executes { ctx ->
                                if (dev.henny.hha.logic.boss.HermesFights.start(ctx.source.playerOrThrow)) 1 else 0
                            })
                            .then(literal("stop").executes { ctx ->
                                if (dev.henny.hha.logic.boss.HermesFights.stop(ctx.source.world)) 1 else 0
                            })
                            .then(literal("skip").executes { ctx ->
                                dev.henny.hha.logic.boss.HermesFights.of(ctx.source.world)?.forceNextStage()
                                1
                            })
                    )
                    .then(
                        literal("vfx").requires(ADMIN).then(
                            argument("name", StringArgumentType.word())
                                .suggests { _, builder -> CommandSource.suggestMatching(dev.henny.hha.logic.VfxDemo.NAMES, builder) }
                                .executes { ctx ->
                                    val name = StringArgumentType.getString(ctx, "name")
                                    if (dev.henny.hha.logic.VfxDemo.play(ctx.source.playerOrThrow, name)) {
                                        1
                                    } else {
                                        ctx.source.sendError(Text.literal("Unbekannter Effekt: $name"))
                                        0
                                    }
                                }
                                // Mit Stil: so, wie der Effekt im Kampf aussieht (Gefahrenfarbe, Effekt-Regeln), z. B. "/hha vfx shockwave aeon".
                                .then(
                                    argument("style", StringArgumentType.word())
                                        .suggests { _, builder -> CommandSource.suggestMatching(dev.henny.hha.logic.VfxDemo.STYLES, builder) }
                                        .executes { ctx ->
                                            val name = StringArgumentType.getString(ctx, "name")
                                            val styleName = StringArgumentType.getString(ctx, "style")
                                            val style = dev.henny.hha.logic.Vfx.Style.entries.firstOrNull { it.name.equals(styleName, ignoreCase = true) }
                                            when {
                                                style == null -> {
                                                    ctx.source.sendError(Text.literal("Unbekannter Stil: $styleName"))
                                                    0
                                                }
                                                dev.henny.hha.logic.VfxDemo.play(ctx.source.playerOrThrow, name, style) -> 1
                                                else -> {
                                                    ctx.source.sendError(Text.literal("Unbekannter Effekt: $name"))
                                                    0
                                                }
                                            }
                                        }
                                )
                        )
                    )
                    .then(literal("list").requires(ADMIN).executes { ctx ->
                        val source = ctx.source
                        source.sendFeedback({ header("Fähigkeiten") }, false)
                        HhaConfig.TOGGLE_DEFAULTS.keys.filter { '.' !in it }.forEach { key ->
                            source.sendFeedback({ toggleLine(key) }, false)
                        }
                        source.sendFeedback({ header("Werte") }, false)
                        HhaConfig.NUMBER_DEFAULTS.keys.filter { '.' !in it }.forEach { key ->
                            source.sendFeedback({ numberLine(key) }, false)
                        }
                        // Addon-Schlüssel (addonid.key) gruppiert je Addon anhängen.
                        val addonIds = (HhaConfig.TOGGLE_DEFAULTS.keys + HhaConfig.NUMBER_DEFAULTS.keys)
                            .filter { '.' in it }
                            .map { it.substringBefore('.') }
                            .distinct()
                        for (addonId in addonIds) {
                            source.sendFeedback({ header("Addon: $addonId") }, false)
                            HhaConfig.TOGGLE_DEFAULTS.keys
                                .filter { it.startsWith("$addonId.") }
                                .forEach { key -> source.sendFeedback({ toggleLine(key) }, false) }
                            HhaConfig.NUMBER_DEFAULTS.keys
                                .filter { it.startsWith("$addonId.") }
                                .forEach { key -> source.sendFeedback({ numberLine(key) }, false) }
                        }
                        1
                    })
                    .then(
                        literal("toggle").requires(ADMIN).then(
                            argument("key", StringArgumentType.word()).suggests(TOGGLE_KEYS).then(
                                argument("value", BoolArgumentType.bool()).executes { ctx ->
                                    val key = StringArgumentType.getString(ctx, "key")
                                    val value = BoolArgumentType.getBool(ctx, "value")
                                    if (HhaConfig.setToggle(key, value)) {
                                        ctx.source.sendFeedback({ toggleLine(key) }, true)
                                        if (key.endsWith("_recipe") || key == "relic_crafting") {
                                            ctx.source.server.commandManager
                                                .parseAndExecute(ctx.source.server.commandSource, "reload")
                                        }
                                        1
                                    } else {
                                        ctx.source.sendError(Text.literal("Unbekannte Fähigkeit: $key"))
                                        0
                                    }
                                }
                            )
                        )
                    )
                    .then(
                        literal("set").requires(ADMIN).then(
                            argument("key", StringArgumentType.word()).suggests(NUMBER_KEYS).then(
                                argument("value", DoubleArgumentType.doubleArg(0.0, 10000.0)).executes { ctx ->
                                    val key = StringArgumentType.getString(ctx, "key")
                                    val value = DoubleArgumentType.getDouble(ctx, "value")
                                    if (HhaConfig.setNumber(key, value)) {
                                        ctx.source.sendFeedback({ numberLine(key) }, true)
                                        HhaNetworking.syncConfig(ctx.source.server)
                                        1
                                    } else {
                                        ctx.source.sendError(Text.literal("Unbekannter Wert: $key"))
                                        0
                                    }
                                }
                            )
                        )
                    )
                    .then(
                        literal("get").requires(ADMIN).then(
                            argument("key", StringArgumentType.word()).suggests { ctx, builder ->
                                CommandSource.suggestMatching(
                                    HhaConfig.TOGGLE_DEFAULTS.keys + HhaConfig.NUMBER_DEFAULTS.keys, builder
                                )
                            }.executes { ctx ->
                                val key = StringArgumentType.getString(ctx, "key")
                                when {
                                    key in HhaConfig.TOGGLE_DEFAULTS -> {
                                        ctx.source.sendFeedback({ toggleLine(key) }, false); 1
                                    }
                                    key in HhaConfig.NUMBER_DEFAULTS -> {
                                        ctx.source.sendFeedback({ numberLine(key) }, false); 1
                                    }
                                    else -> {
                                        ctx.source.sendError(Text.literal("Unbekannter Schlüssel: $key")); 0
                                    }
                                }
                            }
                        )
                    )
                    .then(
                        literal("recipes").requires(ADMIN).then(
                            literal("reload").executes { ctx ->
                                CustomRecipePack.resync(ctx.source.server)
                                ctx.source.sendFeedback(
                                    {
                                        Text.literal("Custom-Rezepte neu geladen (config/hha/datapack).")
                                            .formatted(Formatting.GOLD)
                                    },
                                    true
                                )
                                1
                            }
                        )
                    )
                    .then(literal("reset").requires(ADMIN).executes { ctx ->
                        HhaConfig.reset()
                        HhaNetworking.syncConfig(ctx.source.server)
                        ctx.source.sendFeedback(
                            { Text.literal("HHA-Konfiguration auf Defaults zurückgesetzt.").formatted(Formatting.GOLD) },
                            true
                        )
                        1
                    })
                    .then(
                        literal("settings").requires(ADMIN)
                            .executes { ctx -> showParticleSettings(ctx.source) }
                            .then(
                                literal("particles")
                                    .executes { ctx -> showParticleSettings(ctx.source) }
                                    .then(particleSetting("player", "particle_player"))
                                    .then(particleSetting("effects", "particle_effects"))
                            )
                    )
                    .then(literal("save").requires(ADMIN).executes { ctx ->
                        HhaConfig.save()
                        ctx.source.sendFeedback(
                            { Text.literal("HHA-Konfiguration nach config/hha.json geschrieben.").formatted(Formatting.GOLD) },
                            true
                        )
                        1
                    })
                    .then(literal("load").requires(ADMIN).executes { ctx ->
                        HhaConfig.load()
                        HhaNetworking.syncConfig(ctx.source.server)
                        ctx.source.sendFeedback(
                            { Text.literal("HHA-Konfiguration aus config/hha.json neu geladen.").formatted(Formatting.GOLD) },
                            true
                        )
                        1
                    })
                    .then(literal("reload").requires(ADMIN).executes { ctx ->
                        HhaConfig.load()
                        CustomRecipePack.resync(ctx.source.server)
                        HhaNetworking.syncConfig(ctx.source.server)
                        ctx.source.sendFeedback(
                            {
                                Text.literal("HHA komplett neu geladen (Config, Rezepte, Client-Sync).")
                                    .formatted(Formatting.GOLD)
                            },
                            true
                        )
                        1
                    })
            )

            dispatcher.register(
                literal("trust")
                    .executes { ctx ->
                        val owner = ctx.source.playerOrThrow
                        val trusted = Trust.listFor(owner.uuid)
                        if (trusted.isEmpty()) {
                            ctx.source.sendFeedback(
                                { Text.translatable("hha.msg.trust_empty").formatted(Formatting.GRAY) }, false
                            )
                        } else {
                            val names = trusted.joinToString(", ") { uuid ->
                                ctx.source.server.playerManager.getPlayer(uuid)?.gameProfile?.name
                                    ?: uuid.toString().substring(0, 8)
                            }
                            ctx.source.sendFeedback(
                                { Text.translatable("hha.msg.trust_list", names).formatted(Formatting.GOLD) }, false
                            )
                        }
                        1
                    }
                    .then(
                        argument("player", EntityArgumentType.player()).executes { ctx ->
                            val owner = ctx.source.playerOrThrow
                            val target = EntityArgumentType.getPlayer(ctx, "player")
                            when {
                                target == owner -> {
                                    ctx.source.sendError(Text.translatable("hha.msg.trust_self"))
                                    0
                                }
                                Trust.add(owner.uuid, target.uuid) -> {
                                    ctx.source.sendFeedback(
                                        {
                                            Text.translatable("hha.msg.trust_added", target.gameProfile.name)
                                                .formatted(Formatting.GREEN)
                                        }, false
                                    )
                                    1
                                }
                                else -> {
                                    ctx.source.sendError(
                                        Text.translatable("hha.msg.trust_already", target.gameProfile.name)
                                    )
                                    0
                                }
                            }
                        }
                    )
            )

            dispatcher.register(
                literal("untrust").then(
                    argument("player", EntityArgumentType.player()).executes { ctx ->
                        val owner = ctx.source.playerOrThrow
                        val target = EntityArgumentType.getPlayer(ctx, "player")
                        if (Trust.remove(owner.uuid, target.uuid)) {
                            ctx.source.sendFeedback(
                                {
                                    Text.translatable("hha.msg.trust_removed", target.gameProfile.name)
                                        .formatted(Formatting.YELLOW)
                                }, false
                            )
                            1
                        } else {
                            ctx.source.sendError(
                                Text.translatable("hha.msg.trust_not_found", target.gameProfile.name)
                            )
                            0
                        }
                    }
                )
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Reliquien, Fortschritt, Kämpfe, Teleport (Überarbeitung 2, PLAN 6.3)
    // ---------------------------------------------------------------------------------------------

    private val RELIC_IDS = SuggestionProvider<ServerCommandSource> { _, builder ->
        CommandSource.suggestMatching(BossRewards.allRelics(), builder)
    }

    private val RELIC_IDS_ALL = SuggestionProvider<ServerCommandSource> { _, builder ->
        CommandSource.suggestMatching(BossRewards.allRelics() + "all", builder)
    }

    /** Schlüssel von `/hha progress set` in Pfad-Reihenfolge. */
    private val PROGRESS_KEYS: List<String>
        get() = OlympusProgress.KEYS + TartarosChain.Link.entries.map { it.key } + TartarosChain.HADES

    private val PROGRESS_SUGGEST = SuggestionProvider<ServerCommandSource> { _, builder ->
        CommandSource.suggestMatching(PROGRESS_KEYS, builder)
    }

    /** Bosse, die `/hha boss start` kennt. */
    private val BOSS_KEYS: List<String> get() = listOf(OlympusProgress.HERMES) + DuelBossEntity.KEYS

    private val BOSS_SUGGEST = SuggestionProvider<ServerCommandSource> { _, builder ->
        CommandSource.suggestMatching(BOSS_KEYS, builder)
    }

    private val TP_PLACES = listOf("olymp", "tempel", "dreiweg", "schmiede", "stufen", "thron", "tartaros")
    private val OLYMP_MOBS = listOf("kurete", "griffin", "automaton")

    /** Ab dieser Entfernung zur Insel bringt `/hha boss start` den Aufrufer zum Inseleingang. */
    private const val BOSS_TP_DISTANCE = 80.0

    private fun relicsCommand(): LiteralArgumentBuilder<ServerCommandSource> =
        literal("relics").requires(ADMIN)
            .executes { ctx -> listRelics(ctx.source) }
            .then(
                literal("reset").then(
                    argument("id", StringArgumentType.word()).suggests(RELIC_IDS_ALL).executes { ctx ->
                        val id = StringArgumentType.getString(ctx, "id")
                        val server = ctx.source.server
                        when {
                            id == "all" -> {
                                RelicLedger.of(server).clearAll()
                                ctx.source.sendFeedback({ Text.translatable("hha.cmd.relics.reset_all").formatted(Formatting.GOLD) }, true)
                                1
                            }
                            id in RelicLedger.IDS -> {
                                RelicLedger.clear(server, id)
                                ctx.source.sendFeedback({ Text.translatable("hha.cmd.relics.reset", BossRewards.relicName(id)).formatted(Formatting.GOLD) }, true)
                                1
                            }
                            else -> unknownRelic(ctx.source, id)
                        }
                    }
                )
            )
            .then(
                literal("mark").then(
                    argument("id", StringArgumentType.word()).suggests(RELIC_IDS).then(
                        argument("value", BoolArgumentType.bool()).executes { ctx ->
                            val id = StringArgumentType.getString(ctx, "id")
                            val value = BoolArgumentType.getBool(ctx, "value")
                            if (id !in RelicLedger.IDS) return@executes unknownRelic(ctx.source, id)
                            val server = ctx.source.server
                            if (value) {
                                RelicLedger.mark(server, id, "command", ctx.source.name)
                                ctx.source.sendFeedback({ Text.translatable("hha.cmd.relics.marked", BossRewards.relicName(id)).formatted(Formatting.GOLD) }, true)
                            } else {
                                RelicLedger.clear(server, id)
                                ctx.source.sendFeedback({ Text.translatable("hha.cmd.relics.reset", BossRewards.relicName(id)).formatted(Formatting.GOLD) }, true)
                            }
                            1
                        }
                    )
                )
            )
            .then(
                literal("drop").then(
                    argument("id", StringArgumentType.word()).suggests(RELIC_IDS)
                        .executes { ctx ->
                            dropRelic(ctx.source, StringArgumentType.getString(ctx, "id"), ctx.source.playerOrThrow)
                        }
                        .then(
                            argument("player", EntityArgumentType.player()).executes { ctx ->
                                dropRelic(ctx.source, StringArgumentType.getString(ctx, "id"), EntityArgumentType.getPlayer(ctx, "player"))
                            }
                        )
                )
            )

    private fun unknownRelic(source: ServerCommandSource, id: String): Int {
        source.sendError(Text.translatable("hha.cmd.relics.unknown", id))
        return 0
    }

    private fun dropRelic(source: ServerCommandSource, id: String, player: ServerPlayerEntity): Int {
        if (!BossRewards.forceDrop(source.server, id, player)) return unknownRelic(source, id)
        source.sendFeedback(
            { Text.translatable("hha.cmd.relics.given", BossRewards.relicName(id), player.gameProfile.name).formatted(Formatting.GOLD) },
            true,
        )
        return 1
    }

    private fun listRelics(source: ServerCommandSource): Int {
        val server = source.server
        val now = server.overworld.time
        source.sendFeedback({ Text.translatable("hha.cmd.relics.header").formatted(Formatting.GOLD, Formatting.BOLD) }, false)
        for (id in RelicLedger.IDS) {
            val entry = RelicLedger.of(server).entry(id)
            val boss = BossRewards.bossOf(id) ?: "-"
            val line = if (entry == null) {
                Text.translatable("hha.cmd.relics.free", BossRewards.relicName(id), boss).formatted(Formatting.GRAY)
            } else {
                val minutes = ((now - entry.time).coerceAtLeast(0L) / 1200L).toString()
                Text.translatable("hha.cmd.relics.dropped", BossRewards.relicName(id), minutes, entry.boss, entry.player)
                    .formatted(Formatting.DARK_GRAY)
            }
            source.sendFeedback({ Text.literal(" ").append(line) }, false)
        }
        if (!HhaConfig.enabled("relic_drops")) {
            source.sendFeedback({ Text.translatable("hha.cmd.relics.drops_off").formatted(Formatting.YELLOW) }, false)
        }
        return 1
    }

    private fun progressCommand(): LiteralArgumentBuilder<ServerCommandSource> =
        literal("progress").requires(ADMIN)
            .executes { ctx -> showProgress(ctx.source) }
            .then(
                literal("set").then(
                    argument("key", StringArgumentType.word()).suggests(PROGRESS_SUGGEST).then(
                        argument("value", BoolArgumentType.bool()).executes { ctx ->
                            val key = StringArgumentType.getString(ctx, "key").lowercase()
                            val value = BoolArgumentType.getBool(ctx, "value")
                            val server = ctx.source.server
                            val link = TartarosChain.Link.byKey(key)
                            when {
                                key in OlympusProgress.KEYS -> OlympusProgress.set(server, key, value)
                                link != null -> TartarosChain.set(server, link, value)
                                key == TartarosChain.HADES -> TartarosChain.of(server).hadesDone = value
                                else -> {
                                    ctx.source.sendError(Text.translatable("hha.cmd.progress.unknown", key))
                                    return@executes 0
                                }
                            }
                            ctx.source.sendFeedback({ progressLine(key, value) }, true)
                            1
                        }
                    )
                )
            )

    private fun showProgress(source: ServerCommandSource): Int {
        val server = source.server
        val olymp = OlympusProgress.of(server)
        val chain = TartarosChain.of(server)
        source.sendFeedback({ Text.translatable("hha.cmd.progress.olymp").formatted(Formatting.GOLD, Formatting.BOLD) }, false)
        for (key in OlympusProgress.KEYS) source.sendFeedback({ progressLine(key, olymp.isDone(key)) }, false)
        source.sendFeedback(
            { Text.translatable("hha.cmd.progress.tartaros", chain.brokenCount(), TartarosChain.Link.entries.size).formatted(Formatting.GOLD, Formatting.BOLD) },
            false,
        )
        for (link in TartarosChain.Link.entries) source.sendFeedback({ progressLine(link.key, chain.isBroken(link)) }, false)
        source.sendFeedback({ progressLine(TartarosChain.HADES, chain.hadesDone) }, false)
        return 1
    }

    private fun progressLine(key: String, done: Boolean): Text =
        Text.literal(" $key: ").formatted(Formatting.GRAY).append(
            Text.translatable(if (done) "hha.cmd.progress.done" else "hha.cmd.progress.open")
                .formatted(if (done) Formatting.GREEN else Formatting.RED)
        )

    private fun bossCommand(): LiteralArgumentBuilder<ServerCommandSource> =
        literal("boss").requires(ADMIN)
            .then(
                literal("start").then(
                    argument("boss", StringArgumentType.word()).suggests(BOSS_SUGGEST).executes { ctx ->
                        startBoss(ctx.source, StringArgumentType.getString(ctx, "boss").lowercase())
                    }
                )
            )
            .then(
                literal("stop")
                    .executes { ctx -> controlBoss(ctx.source, null, stop = true) }
                    .then(argument("boss", StringArgumentType.word()).suggests(BOSS_SUGGEST).executes { ctx ->
                        controlBoss(ctx.source, StringArgumentType.getString(ctx, "boss").lowercase(), stop = true)
                    })
            )
            .then(
                literal("skip")
                    .executes { ctx -> controlBoss(ctx.source, null, stop = false) }
                    .then(argument("boss", StringArgumentType.word()).suggests(BOSS_SUGGEST).executes { ctx ->
                        controlBoss(ctx.source, StringArgumentType.getString(ctx, "boss").lowercase(), stop = false)
                    })
            )

    /**
     * Startet [boss] an seiner Insel. Liegt der Aufrufer nicht im Olymp oder weiter als [BOSS_TP_DISTANCE] von der
     * Insel, kommt er erst zum Inseleingang. Fehlt die Olymp-Welt, entsteht eine Test-Arena um den Aufrufer.
     */
    private fun startBoss(source: ServerCommandSource, boss: String): Int {
        val player = source.playerOrThrow
        val isle = OlympusLayout.station(boss)
        if (boss !in BOSS_KEYS || isle == null) {
            source.sendError(Text.translatable("hha.cmd.boss.unknown", boss))
            return 0
        }
        val server = source.server
        val olymp = server.getWorld(OlympusLayout.WORLD)
        val started = if (olymp == null) {
            source.sendFeedback({ Text.translatable("hha.cmd.boss.no_olymp").formatted(Formatting.YELLOW) }, false)
            val here = player.entityWorld as? ServerWorld ?: return 0
            if (boss == OlympusProgress.HERMES) {
                dev.henny.hha.logic.boss.HermesFights.start(player)
            } else {
                DuelBossEntity.startAt(here, boss, player, player.entityPos) != null
            }
        } else {
            val far = player.entityWorld.registryKey != OlympusLayout.WORLD ||
                hypot(player.x - isle.centerVec.x, player.z - isle.centerVec.z) > BOSS_TP_DISTANCE
            if (far) {
                teleportFacing(player, olymp, isle.entrance, isle.centerVec)
                source.sendFeedback({ Text.translatable("hha.cmd.boss.teleported", boss).formatted(Formatting.GRAY) }, false)
            }
            if (boss == OlympusProgress.HERMES) {
                val altar = isle.altar
                if (altar != null) dev.henny.hha.logic.boss.HermesFights.startTemple(player, altar)
                else dev.henny.hha.logic.boss.HermesFights.start(player)
            } else {
                DuelBossEntity.startAt(olymp, boss, player) != null
            }
        }
        return if (started) {
            source.sendFeedback({ Text.translatable("hha.cmd.boss.started", boss).formatted(Formatting.GOLD) }, true)
            1
        } else {
            source.sendError(Text.translatable("hha.cmd.boss.failed", boss))
            0
        }
    }

    /**
     * `boss stop|skip [boss]`: ohne Schlüssel der Kampf, an dem der Aufrufer teilnimmt, sonst der Hermes-Kampf seiner
     * Welt, sonst die erste offene Arena. Hermes läuft (noch) über `HermesFights`, die neuen Bosse über [Arenas].
     */
    private fun controlBoss(source: ServerCommandSource, boss: String?, stop: Boolean): Int {
        val server = source.server
        val player = source.player
        val here = source.world
        val owner: Arenas.Owner? = when (boss) {
            null -> player?.let { Arenas.of(it)?.owner }
            else -> Arenas.byBoss(server, boss)?.owner
        }
        if (owner != null) {
            if (stop) owner.stopFight() else owner.skipPhase()
            return bossDone(source, owner.bossKey, stop)
        }
        if (boss == null || boss == OlympusProgress.HERMES) {
            val worlds = listOfNotNull(here, server.getWorld(OlympusLayout.WORLD)).distinct()
            for (world in worlds) {
                val fight = dev.henny.hha.logic.boss.HermesFights.of(world) ?: continue
                if (stop) dev.henny.hha.logic.boss.HermesFights.stop(world) else fight.forceNextStage()
                return bossDone(source, OlympusProgress.HERMES, stop)
            }
        }
        if (boss == null) {
            val any = Arenas.all().firstOrNull { it.world.server === server }?.owner
            if (any != null) {
                if (stop) any.stopFight() else any.skipPhase()
                return bossDone(source, any.bossKey, stop)
            }
        }
        source.sendError(Text.translatable("hha.cmd.boss.none"))
        return 0
    }

    private fun bossDone(source: ServerCommandSource, boss: String, stop: Boolean): Int {
        source.sendFeedback(
            { Text.translatable(if (stop) "hha.cmd.boss.stopped" else "hha.cmd.boss.skipped", boss).formatted(Formatting.GOLD) },
            true,
        )
        return 1
    }

    /**
     * Testbefehle für den Olymp (nur OP): `/hha olymp spawn <kurete|griffin|automaton>` stellt einen Testposten 8 Blöcke
     * vor dem Spieler auf (jede Welt, ohne Sperrzeit), `/hha olymp bridges` setzt alle Siegelbrücken sofort nach
     * (unabhängig vom Fortschritt, nur in geladenen Chunks).
     */
    private fun olympCommand(): LiteralArgumentBuilder<ServerCommandSource> =
        literal("olymp").requires(ADMIN)
            .then(
                literal("spawn").then(
                    argument("kind", StringArgumentType.word())
                        .suggests { _, builder -> CommandSource.suggestMatching(OLYMP_MOBS, builder) }
                        .executes { ctx -> olympSpawn(ctx.source, StringArgumentType.getString(ctx, "kind").lowercase()) }
                )
            )
            .then(literal("bridges").executes { ctx -> olympBridges(ctx.source) })

    private fun olympSpawn(source: ServerCommandSource, kind: String): Int {
        val player = source.playerOrThrow
        val count = dev.henny.hha.logic.world.OlympusLife.debugSpawn(player, kind)
        if (count < 0) {
            source.sendError(Text.translatable("hha.olympus.mob_unknown", kind))
            return 0
        }
        source.sendFeedback(
            { Text.translatable("hha.olympus.mob_spawned", Text.translatable("entity.hha.$kind"), count).formatted(Formatting.GOLD) },
            false,
        )
        return count
    }

    private fun olympBridges(source: ServerCommandSource): Int {
        val count = dev.henny.hha.logic.world.OlympusPath.forceRestore(source.server)
        source.sendFeedback({ Text.translatable("hha.cmd.olymp.bridges", count).formatted(Formatting.GOLD) }, true)
        return 1
    }

    private fun tpCommand(): LiteralArgumentBuilder<ServerCommandSource> =
        literal("tp").requires(ADMIN).then(
            argument("place", StringArgumentType.word())
                .suggests { _, builder -> CommandSource.suggestMatching(TP_PLACES, builder) }
                .executes { ctx -> teleportTo(ctx.source, StringArgumentType.getString(ctx, "place").lowercase()) }
        )

    private fun teleportTo(source: ServerCommandSource, place: String): Int {
        val player = source.playerOrThrow
        val server = source.server
        if (place == "tartaros") {
            val tartaros = server.getWorld(HadesGate.TARTAROS)
            if (tartaros == null) {
                source.sendError(Text.translatable("hha.cmd.tp.missing_world", HadesGate.TARTAROS.value.toString()))
                return 0
            }
            // Wie HadesGate.target: Ankunft 8 Blöcke vor dem Tor, das am Dreiweg steht.
            val gate = OlympusLayout.HELL_GATE
            val x = gate.x
            val z = gate.z + 8
            val (feet, built) = safeStand(tartaros, x, z)
            player.teleport(tartaros, x + 0.5, feet.toDouble(), z + 0.5, emptySet<PositionFlag>(), 180f, 0f, false)
            if (built) source.sendFeedback({ Text.translatable("hha.cmd.tp.platform").formatted(Formatting.GRAY) }, false)
            source.sendFeedback({ Text.translatable("hha.cmd.tp.done", place).formatted(Formatting.GOLD) }, false)
            return 1
        }
        if (place !in TP_PLACES) {
            source.sendError(Text.translatable("hha.cmd.tp.unknown", place))
            return 0
        }
        val olymp = server.getWorld(OlympusLayout.WORLD)
        val isle = OlympusLayout.station(place)
        if (olymp == null || isle == null) {
            source.sendError(Text.translatable("hha.cmd.tp.missing_world", OlympusLayout.WORLD.value.toString()))
            return 0
        }
        if (isle === OlympusLayout.A) {
            val spot = OlympusLayout.ARRIVAL_SPOT
            player.teleport(olymp, spot.x, spot.y, spot.z, emptySet<PositionFlag>(), OlympusLayout.ARRIVAL_YAW, 0f, false)
        } else {
            teleportFacing(player, olymp, isle.entrance, isle.centerVec)
        }
        source.sendFeedback({ Text.translatable("hha.cmd.tp.done", place).formatted(Formatting.GOLD) }, false)
        return 1
    }

    /** Teleportiert nach [spot] in [world], Blick auf [look]. */
    private fun teleportFacing(player: ServerPlayerEntity, world: ServerWorld, spot: Vec3d, look: Vec3d) {
        val yaw = Math.toDegrees(atan2(-(look.x - spot.x), look.z - spot.z)).toFloat()
        player.teleport(world, spot.x, spot.y, spot.z, emptySet<PositionFlag>(), yaw, 0f, false)
    }

    /**
     * Sicherer Stand bei ([x], [z]): oberster fester Block ohne Flüssigkeit darüber. Fehlt der (Leere, Lava), setzt sie
     * 3 × 3 Schwarzstein und macht darüber zwei Blöcke frei. Liefert Fußhöhe und ob gebaut wurde.
     */
    private fun safeStand(world: ServerWorld, x: Int, z: Int): Pair<Int, Boolean> {
        world.getChunk(x shr 4, z shr 4)
        val top = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z)
        val ground = BlockPos(x, top - 1, z)
        val solid = top > world.bottomY + 1 &&
            world.getBlockState(ground).isSolidBlock(world, ground) &&
            world.getFluidState(ground.up()).isEmpty
        if (solid) return top to false
        val floor = maxOf(top, SAFE_FLOOR_Y)
        for (dx in -1..1) for (dz in -1..1) {
            val base = BlockPos(x + dx, floor - 1, z + dz)
            world.setBlockState(base, Blocks.BLACKSTONE.defaultState)
            world.setBlockState(base.up(), Blocks.AIR.defaultState)
            world.setBlockState(base.up(2), Blocks.AIR.defaultState)
        }
        return floor to true
    }

    /** Untergrenze der Notplattform im Tartaros (wie `HadesGate.MIN_ARRIVAL_Y`). */
    private const val SAFE_FLOOR_Y = 58

    /** `/hha settings particles <name> [0..1]` — Anzeige ohne Wert, Setzen mit Wert. */
    private fun particleSetting(
        name: String,
        configKey: String,
    ): com.mojang.brigadier.builder.LiteralArgumentBuilder<ServerCommandSource> =
        literal(name)
            .executes { ctx ->
                ctx.source.sendFeedback({ numberLine(configKey) }, false)
                1
            }
            .then(
                argument("value", DoubleArgumentType.doubleArg(0.0, 1.0)).executes { ctx ->
                    val value = DoubleArgumentType.getDouble(ctx, "value")
                    HhaConfig.setNumber(configKey, value)
                    HhaNetworking.syncConfig(ctx.source.server)
                    ctx.source.sendFeedback({ numberLine(configKey) }, true)
                    1
                }
            )

    private fun showParticleSettings(source: ServerCommandSource): Int {
        source.sendFeedback({ header("Partikel-Einstellungen") }, false)
        source.sendFeedback({ numberLine("particle_player") }, false)
        source.sendFeedback({ numberLine("particle_effects") }, false)
        source.sendFeedback(
            {
                Text.literal(" /hha settings particles player|effects <0..1>")
                    .formatted(Formatting.DARK_GRAY)
            },
            false
        )
        return 1
    }

    /** Kit für ein registriertes Set — Auflösung: `pfad` (hha oder eindeutig), `addonid.pfad`. */
    private fun resolveSet(name: String): ArmorSet? {
        HhaSets.get(Identifier.of("hha", name))?.let { return it }
        if ('.' in name) {
            val namespace = name.substringBefore('.')
            val path = name.substringAfter('.')
            HhaSets.get(Identifier.of(namespace, path))?.let { return it }
        }
        return HhaSets.all().firstOrNull { it.id.path == name }
    }

    private fun giveKit(source: ServerCommandSource, setName: String): Int {
        val player = source.playerOrThrow
        // kit_mode an oder Kreativmodus (PLAN 6.3); Kits berühren das Reliquien-Register nie.
        if (!dev.henny.hha.logic.Kits.allowed(player)) {
            source.sendError(Text.translatable("hha.msg.kit_disabled"))
            return 0
        }
        val vanilla = dev.henny.hha.logic.Kits.VanillaKit.entries.firstOrNull { it.key == setName }
        if (vanilla != null) {
            dev.henny.hha.logic.Kits.give(player, vanilla)
            source.sendFeedback({ Text.translatable("hha.msg.kit_${vanilla.key}").formatted(Formatting.GOLD) }, false)
            return 1
        }
        val set = resolveSet(setName)
        if (set == null) {
            source.sendError(Text.literal("Unbekanntes Set: $setName"))
            return 0
        }
        dev.henny.hha.logic.Kits.give(player, set)
        val message = when (set.id) {
            HhaSets.HELL_ID -> Text.translatable("hha.msg.kit_hell")
            HhaSets.HEAVEN_ID -> Text.translatable("hha.msg.kit_heaven")
            else -> Text.translatable("hha.msg.kit_generic", set.id.path)
        }
        source.sendFeedback({ message.formatted(Formatting.GOLD) }, false)
        return 1
    }

    /** `/hha addons` — was ist geladen und was hat es registriert? */
    private fun listAddons(source: ServerCommandSource): Int {
        val addons = HhaAddons.all()
        if (addons.isEmpty()) {
            source.sendFeedback(
                { Text.literal("Keine HHA-Addons geladen.").formatted(Formatting.GRAY) }, false
            )
            return 1
        }
        source.sendFeedback({ header("Addons") }, false)
        for (addon in addons) {
            source.sendFeedback(
                {
                    Text.literal(" ${addon.id} ${addon.version}").formatted(Formatting.GOLD)
                        .append(Text.literal(" — ${addon.name}").formatted(Formatting.GRAY))
                }, false
            )
            source.sendFeedback(
                {
                    Text.literal(
                        "   Sets: ${addon.sets.size} | Abilities: ${addon.abilities.size} | " +
                            "Lore: ${addon.loreItemCount} Items | Config: ${addon.configKeys.size} Keys"
                    ).formatted(Formatting.DARK_GRAY)
                }, false
            )
        }
        return 1
    }

    private fun header(title: String): Text =
        Text.literal("— $title —").formatted(Formatting.GOLD, Formatting.BOLD)

    private fun toggleLine(key: String): Text {
        val on = HhaConfig.enabled(key)
        return Text.literal(" $key: ")
            .formatted(Formatting.GRAY)
            .append(
                Text.literal(if (on) "AN" else "AUS")
                    .formatted(if (on) Formatting.GREEN else Formatting.RED)
            )
    }

    private fun numberLine(key: String): Text =
        Text.literal(" $key: ")
            .formatted(Formatting.GRAY)
            .append(Text.literal(HhaConfig.num(key).toString()).formatted(Formatting.AQUA))
}
