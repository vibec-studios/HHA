package dev.henny.hha.config

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * Spielerhilfe für Commands, Steuerung und alle Set-/Waffenfähigkeiten.
 * Die Seiten sind absichtlich kurz genug, um den Chat nicht mit einer einzigen
 * riesigen Nachricht zu füllen; `/info all` zeigt auf Wunsch alles auf einmal.
 */
object InfoCommand {

    private data class Page(val id: String, val lines: Int)

    private val OVERVIEW = Page("overview", 5)
    // 9–12: Reliquien, Fortschritt, Kämpfe und Teleport (Überarbeitung 2, PLAN 6.3).
    private val COMMANDS = Page("commands", 13)
    private val GENERAL = Page("general", 7)
    private val HELL = Page("hell", 10)
    private val HEAVEN = Page("heaven", 10)
    private val ULTRA = Page("ultra", 3)
    private val ABILITY_PAGES = listOf(GENERAL, HELL, HEAVEN, ULTRA)
    private val ALL_PAGES = listOf(COMMANDS) + ABILITY_PAGES

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                literal("info")
                    .executes { context -> show(context.source, OVERVIEW, true) }
                    .then(literal("commands").executes { context -> show(context.source, COMMANDS, true) })
                    .then(literal("general").executes { context -> show(context.source, GENERAL, true) })
                    .then(literal("hell").executes { context -> show(context.source, HELL, true) })
                    .then(literal("heaven").executes { context -> show(context.source, HEAVEN, true) })
                    .then(literal("ultra").executes { context -> show(context.source, ULTRA, true) })
                    .then(literal("abilities").executes { context -> showMany(context.source, ABILITY_PAGES) })
                    .then(literal("all").executes { context -> showMany(context.source, ALL_PAGES) })
            )
        }
    }

    private fun showMany(source: ServerCommandSource, pages: List<Page>): Int {
        pages.forEach { show(source, it, false) }
        footer(source)
        return 1
    }

    private fun show(source: ServerCommandSource, page: Page, withFooter: Boolean): Int {
        source.sendFeedback(
            {
                Text.literal("── ")
                    .formatted(Formatting.DARK_GRAY)
                    .append(Text.translatable("hha.info.${page.id}.title").formatted(Formatting.GOLD, Formatting.BOLD))
                    .append(Text.literal(" ──").formatted(Formatting.DARK_GRAY))
            },
            false,
        )
        for (line in 1..page.lines) {
            source.sendFeedback(
                {
                    Text.literal(" • ")
                        .formatted(Formatting.DARK_GRAY)
                        .append(Text.translatable("hha.info.${page.id}.$line").formatted(Formatting.GRAY))
                },
                false,
            )
        }
        if (withFooter) footer(source)
        return 1
    }

    private fun footer(source: ServerCommandSource) {
        source.sendFeedback(
            { Text.translatable("hha.info.footer").formatted(Formatting.YELLOW) },
            false,
        )
    }
}
