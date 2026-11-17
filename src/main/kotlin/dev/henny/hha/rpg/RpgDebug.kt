package dev.henny.hha.rpg

import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * Prüfbefehle `/hha rpg state|chapter <id>|arrive|rest` (registriert in `config/Overhaul3Commands`, OP 2). Rückgabe =
 * Brigadier-Ergebnis (1 = ausgeführt, 0 = nicht). Außerhalb einer RPG-Welt melden alle nur das.
 */
object RpgDebug {

    /** Kapitel, Ziel, Halte-Liste, Rast, Tagebuch. */
    fun state(source: ServerCommandSource): Int {
        val server = source.server
        val state = RpgWorld.of(server)
        if (!state.enabled) return notRpg(source)
        val chapter = RpgStory.current(server)
        val chapters = RpgStory.chapters(state.path)
        val lines = ArrayList<Text>()
        lines.add(Text.translatable("hha.rpg.cmd.state.head", state.path, chapter.id, chapters.indexOfFirst { it.id == chapter.id } + 1, chapters.size).formatted(Formatting.GOLD))
        lines.add(Text.translatable("hha.rpg.cmd.state.done", chapters.filter { state.isChapterDone(it.id) }.joinToString(", ") { it.id }.ifEmpty { "–" }).formatted(Formatting.GRAY))
        val player = source.player
        if (player != null) {
            val goal = RpgStory.goalFor(server, player)
            val goalText: Text = goal?.let { Text.translatable(it.key, *it.args.toTypedArray()) } ?: Text.literal("–")
            val target = goal?.target?.let { "${it.world.value} ${it.pos.x.toInt()} ${it.pos.y.toInt()} ${it.pos.z.toInt()}" } ?: "–"
            lines.add(Text.translatable("hha.rpg.cmd.state.goal", goalText, target).formatted(Formatting.GRAY))
            val rest = state.restOf(player.uuid)
            lines.add(
                Text.translatable(
                    "hha.rpg.cmd.state.rest",
                    rest?.let { Text.translatable(it.key) } ?: Text.literal("–"),
                    rest?.let { "${it.world} ${it.pos.x.toInt()} ${it.pos.y.toInt()} ${it.pos.z.toInt()}" } ?: "–",
                ).formatted(Formatting.GRAY),
            )
        }
        lines.add(Text.translatable("hha.rpg.cmd.state.journal", state.journal.joinToString(", ").ifEmpty { "–" }).formatted(Formatting.GRAY))
        lines.add(Text.translatable("hha.rpg.cmd.state.misc", state.visited.joinToString(", ").ifEmpty { "–" }, state.deaths).formatted(Formatting.GRAY))
        for (line in RpgArrival.describe(server)) lines.add(Text.literal(line).formatted(Formatting.DARK_GRAY))
        for (line in lines) source.sendFeedback({ line }, false)
        return 1
    }

    /** Setzt die Zustände so, dass [id] das laufende Kapitel ist. */
    fun chapter(source: ServerCommandSource, id: String): Int {
        val server = source.server
        val state = RpgWorld.of(server)
        if (!state.enabled) return notRpg(source)
        if (!RpgStory.applyChapter(server, id)) {
            source.sendError(Text.translatable("hha.rpg.cmd.chapter.unknown", id, RpgStory.chapters(state.path).joinToString(", ") { it.id }))
            return 0
        }
        source.sendFeedback({ Text.translatable("hha.rpg.cmd.chapter.set", id).formatted(Formatting.GOLD) }, true)
        return 1
    }

    /** Ankunft sofort (laufende Halte), sonst die Ankunftsszene noch einmal für alle in der Welt. */
    fun arrive(source: ServerCommandSource): Int {
        val server = source.server
        if (!RpgWorld.of(server).enabled) return notRpg(source)
        if (!RpgArrival.arriveNow(server)) {
            source.sendError(Text.translatable("hha.rpg.cmd.arrive.no"))
            return 0
        }
        source.sendFeedback({ Text.translatable("hha.rpg.cmd.arrive.ok").formatted(Formatting.GOLD) }, true)
        return 1
    }

    /** Rast hier (an der Stelle des Spielers). */
    fun rest(source: ServerCommandSource): Int {
        val server = source.server
        if (!RpgWorld.of(server).enabled) return notRpg(source)
        val player = source.playerOrThrow
        val world = player.entityWorld as? ServerWorld ?: return 0
        RpgRests.rest(player, "hha.rpg.rest.here", player.entityPos, world)
        source.sendFeedback({ Text.translatable("hha.rpg.cmd.rest.ok").formatted(Formatting.GOLD) }, false)
        return 1
    }

    private fun notRpg(source: ServerCommandSource): Int {
        source.sendError(Text.translatable("hha.rpg.cmd.not_rpg"))
        return 0
    }
}
