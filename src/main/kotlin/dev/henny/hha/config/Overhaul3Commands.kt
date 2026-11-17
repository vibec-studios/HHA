package dev.henny.hha.config

import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.net.FxPayload
import dev.henny.hha.rpg.RpgDebug
import dev.henny.hha.stage.StageScripts
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.command.CommandSource
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * Prüfbefehle der Überarbeitung 3 (P00), eigener Zweig unter `/hha` (Rechte wie die übrigen Test-Befehle: OP 2):
 * - `/hha fx list` – der Client schreibt die bei ihm registrierten Effekt-IDs in den Chat.
 * - `/hha fx play <id> [style]` – Effekt vor dem Spieler, Richtung = Blick; die `core.*`-IDs mit Vorführwerten über
 *   die `BossFx`-Helfer, jede andere ID mit `a = b = 3`, `life = 60`.
 * - `/hha fx screen <ripple|heat|eclipse|time|lens|clear>`, `/hha fx kick [pitch] [yaw] [roll]`.
 * - `/hha stage list`, `/hha stage play <id>` (Anker = Spieler, Darsteller 0 = Spieler), `/hha stage stop`.
 * - `/hha rpg state|chapter <id>|arrive|rest` → `RpgDebug`; `/hha rpg scene <id>` → `RpgScenes.playNow`.
 * Außerhalb von Kampf und Szene gelten die freien Deckel von `BossFx` (Ruck höchstens 1,5°).
 */
object Overhaul3Commands {

    private val ADMIN = CommandManager.requirePermissionLevel<ServerCommandSource>(CommandManager.GAMEMASTERS_CHECK)

    /** Laufende Vorführ-Szenen: Spieler → verbleibende Ticks. */
    private val demos = HashMap<UUID, Int>()

    /** Kapitel-IDs aus PLAN 3.3 (nur Vorschläge; die Prüfung macht `RpgDebug`). */
    private val CHAPTERS = listOf(
        "prologue", "h1_word", "h2_work", "h3_force", "h4_fear", "u0_threshold", "u1_chain", "u2_lord", "u3_titan", "epilogue",
    )

    private val SCREENS = mapOf(
        "ripple" to BossFx.Screen.SHOCK_RIPPLE,
        "heat" to BossFx.Screen.HEAT_HAZE,
        "eclipse" to BossFx.Screen.ECLIPSE,
        "time" to BossFx.Screen.TIME_STOP,
        "lens" to BossFx.Screen.GRAVITY_LENS,
    )

    fun init() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                literal("hha")
                    .then(fxCommand())
                    .then(stageCommand())
                    .then(rpgCommand()),
            )
        }
        ServerTickEvents.END_SERVER_TICK.register(::tickDemos)
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> demos.remove(handler.player.uuid) }
        Cine.onSkip { player ->
            if (player.uuid !in demos) return@onSkip false
            endDemo(player)
            true
        }
    }

    // ---------------------------------------------------------------------------------------------
    // /hha fx
    // ---------------------------------------------------------------------------------------------

    private fun fxCommand(): LiteralArgumentBuilder<ServerCommandSource> = literal("fx").requires(ADMIN)
        .then(literal("list").executes { ctx ->
            val player = ctx.source.playerOrThrow
            BossFx.play(player.entityWorld as ServerWorld, FxPayload.ID_LIST, Vfx.Style.GOLD, player.entityPos, to = player)
            1
        })
        .then(
            literal("play").then(
                argument("id", StringArgumentType.word())
                    .suggests { _, builder -> CommandSource.suggestMatching(BossFx.CORE_IDS, builder) }
                    .executes { ctx -> playFx(ctx, Vfx.Style.HEAVEN) }
                    .then(
                        argument("style", StringArgumentType.word())
                            .suggests { _, builder -> CommandSource.suggestMatching(Vfx.Style.entries.map { it.name.lowercase() }, builder) }
                            .executes { ctx ->
                                val name = StringArgumentType.getString(ctx, "style")
                                val style = Vfx.Style.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                                if (style == null) {
                                    ctx.source.sendError(Text.literal("Unbekannter Stil: $name"))
                                    0
                                } else {
                                    playFx(ctx, style)
                                }
                            },
                    ),
            ),
        )
        .then(
            literal("screen").then(
                argument("art", StringArgumentType.word())
                    .suggests { _, builder -> CommandSource.suggestMatching(SCREENS.keys + "clear", builder) }
                    .executes { ctx -> screenFx(ctx.source.playerOrThrow, StringArgumentType.getString(ctx, "art"), ctx.source) },
            ),
        )
        .then(
            literal("kick")
                .executes { ctx -> kick(ctx.source.playerOrThrow, 2.5f, 0f, 0f) }
                .then(
                    argument("pitch", FloatArgumentType.floatArg(-10f, 10f))
                        .executes { ctx -> kick(ctx.source.playerOrThrow, FloatArgumentType.getFloat(ctx, "pitch"), 0f, 0f) }
                        .then(
                            argument("yaw", FloatArgumentType.floatArg(-10f, 10f)).then(
                                argument("roll", FloatArgumentType.floatArg(-10f, 10f)).executes { ctx ->
                                    kick(
                                        ctx.source.playerOrThrow, FloatArgumentType.getFloat(ctx, "pitch"),
                                        FloatArgumentType.getFloat(ctx, "yaw"), FloatArgumentType.getFloat(ctx, "roll"),
                                    )
                                },
                            ),
                        ),
                ),
        )

    private fun kick(player: ServerPlayerEntity, pitch: Float, yaw: Float, roll: Float): Int {
        BossFx.kick(player, pitch, yaw, roll, 6)
        return 1
    }

    /** Waagerechte Blickrichtung und rechte Seite des Spielers. */
    private fun axes(player: ServerPlayerEntity): Pair<Vec3d, Vec3d> {
        val rad = Math.toRadians(player.yaw.toDouble())
        val forward = Vec3d(-sin(rad), 0.0, cos(rad))
        val right = Vec3d(-cos(rad), 0.0, -sin(rad))
        return forward to right
    }

    private fun playFx(ctx: CommandContext<ServerCommandSource>, style: Vfx.Style): Int {
        val player = ctx.source.playerOrThrow
        val world = player.entityWorld as ServerWorld
        val id = StringArgumentType.getString(ctx, "id")
        val (f, r) = axes(player)
        val up = Vec3d(0.0, 1.0, 0.0)
        val c = player.entityPos.add(f.multiply(6.0))
        when (id) {
            "core.arc" -> BossFx.arc(world, style, c.add(r.multiply(-3.0)).add(0.0, 1.5, 0.0), c.add(r.multiply(3.0)).add(0.0, 1.5, 0.0), 0.3f, 0.5f, 30, 2)
            "core.crack" -> BossFx.crack(world, style, c, f, 8f, 80, 10)
            "core.cracks" -> BossFx.cracks(world, style, c, 4f, 6, 80)
            "core.debris" -> BossFx.debris(world, c, 3f, 6)
            "core.debris_pull" -> BossFx.debrisPull(world, c, 5f, c.add(0.0, 3.0, 0.0), 50, 12)
            "core.sparks" -> BossFx.sparks(world, style, c.add(0.0, 1.0, 0.0), up, 14, 0.5f, 18)
            "core.swirl" -> BossFx.swirl(world, style, c.add(0.0, 1.5, 0.0), f.multiply(-1.0), 2f, 40)
            "core.charge" -> BossFx.charge(world, style, player, 2.5f, 40)
            "core.pillar_shatter" -> BossFx.pillarShatter(world, style, c, 1.5f, 6f, 10, 20)
            "core.wave_wall" -> BossFx.waveWall(world, style, c.add(r.multiply(-6.0)), c.add(r.multiply(6.0)), 10f, 3f, 60, listOf(0.4f to 0.6f))
            "core.sky_strike" -> BossFx.skyStrike(world, style, c, 2.5f, 30f, 20)
            "core.tear" -> BossFx.tear(world, style, c.add(0.0, 2.0, 0.0), up, 3f, 1f, 60)
            "core.dome" -> BossFx.dome(world, style, c.add(0.0, 1.0, 0.0), 2.5f, 40, shatter = true)
            "core.projectile" -> BossFx.play(world, "core.projectile", style, player.entityPos, b = BossFx.Look.HOT_IRON.ordinal.toFloat(), life = 100, entity = player)
            "core.drip" -> BossFx.drip(world, style, c, 2f, 10, 60)
            "core.kick" -> BossFx.kick(player, 2.5f)
            "core.screen" -> return screenFx(player, "eclipse", ctx.source)
            "core.screen_clear" -> return screenFx(player, "clear", ctx.source)
            "core.vignette" -> BossFx.vignette(player, style, 0.3f, 60, beat = true)
            "core.time_scale" -> BossFx.timeScale(world, c, 0f, 40)
            else -> BossFx.play(world, id, style, c, f, a = 3f, b = 3f, life = 60)
        }
        ctx.source.sendFeedback({ Text.literal("FX $id (${style.name.lowercase()})").formatted(Formatting.GRAY) }, false)
        return 1
    }

    private fun screenFx(player: ServerPlayerEntity, art: String, source: ServerCommandSource): Int {
        val world = player.entityWorld as ServerWorld
        val (f, _) = axes(player)
        if (art == "clear") {
            for (kind in BossFx.Screen.entries) BossFx.screenClear(world, player.entityPos, kind)
            return 1
        }
        val kind = SCREENS[art]
        if (kind == null) {
            source.sendError(Text.literal("Unbekannte Art: $art (ripple, heat, eclipse, time, lens, clear)"))
            return 0
        }
        val ahead = player.entityPos.add(f.multiply(8.0))
        val (center, safe) = when (kind) {
            BossFx.Screen.ECLIPSE, BossFx.Screen.TIME_STOP -> player.entityPos to 0f
            BossFx.Screen.GRAVITY_LENS -> ahead.add(0.0, 3.0, 0.0) to 3f
            else -> ahead to 0f
        }
        val ticks = if (kind == BossFx.Screen.SHOCK_RIPPLE) 10 else 100
        BossFx.screen(world, kind, Vfx.Style.AEON, center, 10f, BossFx.screenMaxStrength(kind), ticks, safeRadius = safe)
        return 1
    }

    // ---------------------------------------------------------------------------------------------
    // /hha stage
    // ---------------------------------------------------------------------------------------------

    private fun stageCommand(): LiteralArgumentBuilder<ServerCommandSource> = literal("stage").requires(ADMIN)
        .then(literal("list").executes { ctx ->
            val ids = StageScripts.ids()
            ctx.source.sendFeedback({ Text.literal("Bühne: ${ids.size} Skripte").formatted(Formatting.GOLD) }, false)
            for (chunk in ids.chunked(6)) {
                ctx.source.sendFeedback({ Text.literal(chunk.joinToString(", ")).formatted(Formatting.GRAY) }, false)
            }
            1
        })
        .then(
            literal("play").then(
                argument("id", StringArgumentType.word())
                    .suggests { _, builder -> CommandSource.suggestMatching(StageScripts.ids(), builder) }
                    .executes { ctx -> playStage(ctx.source.playerOrThrow, StringArgumentType.getString(ctx, "id"), ctx.source) },
            ),
        )
        .then(literal("stop").executes { ctx ->
            val player = ctx.source.playerOrThrow
            if (player.uuid in demos) endDemo(player)
            1
        })

    private fun playStage(player: ServerPlayerEntity, id: String, source: ServerCommandSource): Int {
        val script = StageScripts.get(id)
        if (script == null) {
            source.sendError(Text.literal("Unbekanntes Bühnen-Skript: $id"))
            return 0
        }
        if (Cine.isPlaying(player)) {
            source.sendError(Text.literal("Es läuft schon eine Szene."))
            return 0
        }
        val audience = listOf(player)
        Cine.start(audience)
        Cine.stage(audience, id, player.entityPos, player.yaw, listOf(player))
        demos[player.uuid] = script.length
        return 1
    }

    private fun tickDemos(server: MinecraftServer) {
        if (demos.isEmpty()) return
        val iterator = demos.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val left = entry.value - 1
            if (left > 0) {
                entry.setValue(left)
                continue
            }
            iterator.remove()
            server.playerManager.getPlayer(entry.key)?.let { Cine.end(listOf(it)) }
        }
    }

    private fun endDemo(player: ServerPlayerEntity) {
        demos.remove(player.uuid)
        val audience = listOf(player)
        Cine.stageStop(audience)
        Cine.end(audience)
        Cine.cut(audience)
    }

    // ---------------------------------------------------------------------------------------------
    // /hha rpg
    // ---------------------------------------------------------------------------------------------

    private fun rpgCommand(): LiteralArgumentBuilder<ServerCommandSource> = literal("rpg").requires(ADMIN)
        .then(literal("state").executes { ctx -> RpgDebug.state(ctx.source) })
        .then(
            literal("chapter").then(
                argument("id", StringArgumentType.word())
                    .suggests { _, builder -> CommandSource.suggestMatching(CHAPTERS, builder) }
                    .executes { ctx -> RpgDebug.chapter(ctx.source, StringArgumentType.getString(ctx, "id")) },
            ),
        )
        .then(literal("arrive").executes { ctx -> RpgDebug.arrive(ctx.source) })
        .then(literal("rest").executes { ctx -> RpgDebug.rest(ctx.source) })
        .then(
            literal("scene").then(
                argument("id", StringArgumentType.word())
                    .suggests { _, builder -> CommandSource.suggestMatching(StageScripts.ids().filter { it.startsWith("rpg.") }, builder) }
                    .executes { ctx -> rpgScene(ctx.source.playerOrThrow, StringArgumentType.getString(ctx, "id"), ctx.source) },
            ),
        )

    /** `/hha rpg scene <id>` (P20): RPG-Szene mit dem Anker, den sie im Spiel hätte (`RpgScenes.playNow`). */
    private fun rpgScene(player: ServerPlayerEntity, id: String, source: ServerCommandSource): Int {
        if (dev.henny.hha.rpg.RpgScenes.playNow(player, id)) return 1
        source.sendError(Text.translatable("hha.rpg.debug.scene_unavailable", id))
        return 0
    }
}
