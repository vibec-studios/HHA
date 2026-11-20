package dev.henny.hha.config

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.logic.boss.ArenaCollapse
import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.arena.ArenaBlueprints
import dev.henny.hha.logic.boss.arena.ArenaDevices
import dev.henny.hha.logic.world.Approach
import dev.henny.hha.logic.world.WorldRetrofit
import dev.henny.hha.world.TartarusArenas
import dev.henny.hha.world.TartarusPiece
import dev.henny.hha.world.TartarusStructure
import dev.henny.hha.world.arena.ArenaPlans
import dev.henny.hha.world.arena.ArenaSpec
import dev.henny.hha.world.arena.Zone
import dev.henny.hha.world.olymp.OlympusArenas
import dev.henny.hha.world.olymp.OlympusStructures
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.command.CommandSource
import net.minecraft.item.ItemStack
import net.minecraft.particle.DustParticleEffect
import net.minecraft.particle.ParticleEffect
import net.minecraft.server.MinecraftServer
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d

/**
 * Prüfbefehle der Überarbeitung 4 (PLAN 7.13; P00 vollständig, eingefroren), eigener Zweig unter `/hha`, OP 2. Die
 * Befehle rufen die Gerüst-Funktionen; sie wirken, sobald das Besitzer-Paket fertig ist.
 *
 * - `/hha arena list` – die elf Baupläne mit Kern, Ebenen, Deckung, Teilen, Geräten.
 * - `/hha arena repair <id>` – Arena gegen den Bauplan reparieren (`ArenaBlueprints.repair`, P02). Olymp: feste Lage;
 *   Tartaros: das Bauwerk, in dem man steht; Forum: nur im laufenden Kampf.
 * - `/hha arena restore` – Edits der Arena, in der man kämpft, zurücksetzen.
 * - `/hha arena show <id> cover|levels|parts|devices` – Marken als Partikel (10 s).
 * - `/hha arena part <id>` – Teil im laufenden Kampf abwerfen (`ArenaCollapse.start`, P03).
 * - `/hha world retrofit status|run <teil>|all` – Olymp-Nachrüstung (P02).
 * - `/hha sub test <n>` – n Unterleisten zeigen (0 blendet aus; P04 zeichnet).
 * - `/hha device <mode>` – Gerät im Blick auf Zustand 0–2 setzen.
 * - `/hha approach` – Fortschritt und nächster Wegpunkt auf jedem Weg in der Nähe (P06).
 * - `/hha deco give` – alle Deko- und Geräte-Blöcke ins Inventar.
 */
object Overhaul4Commands {

    private val ADMIN = CommandManager.requirePermissionLevel<ServerCommandSource>(CommandManager.GAMEMASTERS_CHECK)
    private val WHAT = listOf("cover", "levels", "parts", "devices")

    /** Laufende Partikel-Marken: Welt, Punkte, Farbe, verbleibende Ticks. */
    private class Show(val world: ServerWorld, val points: List<Vec3d>, val particle: ParticleEffect, var left: Int)

    private val shows = ArrayList<Show>()

    fun init() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                literal("hha")
                    .then(arenaCommand())
                    .then(literal("world").requires(ADMIN).then(retrofitCommand()))
                    .then(subCommand())
                    .then(deviceCommand())
                    .then(literal("approach").requires(ADMIN).executes(::approach))
                    .then(literal("deco").requires(ADMIN).then(literal("give").executes(::giveDeco))),
            )
        }
        ServerTickEvents.END_SERVER_TICK.register { _ -> tickShows() }
    }

    // ---------------------------------------------------------------------------------------------
    // /hha arena
    // ---------------------------------------------------------------------------------------------

    private fun ids(builder: com.mojang.brigadier.suggestion.SuggestionsBuilder) = CommandSource.suggestMatching(ArenaPlans.IDS, builder)

    private fun arenaCommand(): LiteralArgumentBuilder<ServerCommandSource> = literal("arena").requires(ADMIN)
        .then(literal("list").executes { ctx ->
            for (id in ArenaPlans.IDS) {
                val spec = resolve(ctx.source.player, id, quiet = true)?.second
                val line = if (spec == null) "$id: Lage erst im Kampf bzw. im Bauwerk bekannt"
                else "$id: Kern y ${spec.coreY}, ${spec.levels.size} Ebenen, ${spec.cover.size} Deckung, ${spec.parts.size} Teile, ${spec.devices.size} Geräte"
                ctx.source.sendFeedback({ Text.literal(line).formatted(Formatting.GRAY) }, false)
            }
            ArenaPlans.IDS.size
        })
        .then(literal("repair").then(argument("id", StringArgumentType.word()).suggests { _, b -> ids(b) }.executes { ctx ->
            val id = StringArgumentType.getString(ctx, "id")
            val player = ctx.source.playerOrThrow
            val (origin, _) = resolve(player, id) ?: return@executes 0
            val count = ArenaBlueprints.repair(player.entityWorld as ServerWorld, id, origin, instant = true)
            ctx.source.sendFeedback({ Text.literal("Arena $id an $origin: $count Blöcke nach Bauplan gesetzt").formatted(Formatting.GOLD) }, true)
            1
        }))
        .then(literal("restore").executes { ctx ->
            val player = ctx.source.playerOrThrow
            val arena = Arenas.of(player)
            val edits = arena?.edits
            if (edits == null) {
                ctx.source.sendError(Text.literal("Keine offene Arena mit Edit-Register (Gerüst bis P02/P03)."))
                return@executes 0
            }
            val n = edits.changed.size
            edits.restoreAll()
            ctx.source.sendFeedback({ Text.literal("Arena: $n Stellen zurückgesetzt").formatted(Formatting.GOLD) }, true)
            1
        })
        .then(literal("show").then(argument("id", StringArgumentType.word()).suggests { _, b -> ids(b) }
            .then(argument("what", StringArgumentType.word()).suggests { _, b -> CommandSource.suggestMatching(WHAT, b) }.executes(::show))))
        .then(literal("part").then(argument("id", StringArgumentType.word()).suggests { ctx, b ->
            val spec = ctx.source.player?.let { Arenas.of(it)?.spec }
            CommandSource.suggestMatching(spec?.parts?.map { it.id } ?: emptyList(), b)
        }.executes { ctx ->
            val player = ctx.source.playerOrThrow
            val arena = Arenas.of(player)
            val spec = arena?.spec
            val id = StringArgumentType.getString(ctx, "id")
            val part = spec?.parts?.firstOrNull { it.id == id }
            if (arena == null || part == null) {
                ctx.source.sendError(Text.literal("Kein laufender Kampf mit Teil '$id' (Arena mit Spec nötig)."))
                return@executes 0
            }
            ArenaCollapse.start(arena, part) {}
            ctx.source.sendFeedback({ Text.literal("Teil $id abgeworfen").formatted(Formatting.GOLD) }, true)
            1
        }))

    /** Ursprung und Spec einer Arena: Olymp fest, Tartaros aus dem Bauwerk unter dem Spieler, Forum aus dem Kampf. */
    private fun resolve(player: ServerPlayerEntity?, id: String, quiet: Boolean = false): Pair<BlockPos, ArenaSpec>? {
        fun fail(message: String): Pair<BlockPos, ArenaSpec>? {
            if (!quiet) player?.sendMessage(Text.literal(message).formatted(Formatting.RED), false)
            return null
        }
        val blueprint = ArenaBlueprints.get(id) ?: return fail("Unbekannter Bauplan: $id (${ArenaPlans.IDS.joinToString()})")
        when (id) {
            ArenaPlans.OLYMP_C, ArenaPlans.OLYMP_D, ArenaPlans.OLYMP_E, ArenaPlans.OLYMP_F -> {
                val spec = blueprint.spec(BlockPos.ORIGIN)
                return spec.center to spec
            }
            ArenaPlans.FORUM -> {
                val arena = player?.let { Arenas.of(it) }?.spec?.takeIf { it.id == ArenaPlans.FORUM }
                    ?: return fail("Das Forum steht nur im laufenden Hermes-Kampf.")
                return arena.center to arena
            }
        }
        if (player == null) return null
        val world = player.entityWorld as ServerWorld
        val start = world.structureAccessor.getStructureContaining(player.blockPos) { it.value() is TartarusStructure }
        val piece = start.children.firstOrNull() as? TartarusPiece ?: return fail("Hier steht kein Tartaros-Bauwerk.")
        val spec = TartarusArenas.spec(piece.kind, piece.siteVariant(), piece.middle)
        if (spec.id != id) return fail("Dieses Bauwerk hat den Bauplan ${spec.id}, nicht $id.")
        return piece.middle to spec
    }

    private fun show(ctx: CommandContext<ServerCommandSource>): Int {
        val player = ctx.source.playerOrThrow
        val id = StringArgumentType.getString(ctx, "id")
        val what = StringArgumentType.getString(ctx, "what")
        val (_, spec) = resolve(player, id) ?: return 0
        val points = ArrayList<Vec3d>()
        val color: Int
        when (what) {
            "cover" -> {
                color = 0xFFD75E
                for (c in spec.cover) {
                    val b = c.box
                    for (x in b.minX..b.maxX + 1) for (z in listOf(b.minZ, b.maxZ + 1)) for (y in listOf(b.minY, b.maxY + 1)) points.add(Vec3d(x.toDouble(), y.toDouble(), z.toDouble()))
                    for (z in b.minZ..b.maxZ + 1) for (x in listOf(b.minX, b.maxX + 1)) for (y in listOf(b.minY, b.maxY + 1)) points.add(Vec3d(x.toDouble(), y.toDouble(), z.toDouble()))
                }
            }
            "levels" -> {
                color = 0x5AA6F0
                for (l in spec.levels) {
                    sample(l.zone, l.floorY + 0.1, points)
                    for (a in l.access) points.add(Vec3d.ofCenter(a).add(0.0, 0.6, 0.0))
                }
            }
            "parts" -> {
                color = 0xE8701A
                for (p in spec.parts) sample(p.zone, p.maxY + 1.1, points)
            }
            "devices" -> {
                color = 0x7AE582
                for (d in spec.devices) points.add(Vec3d.ofCenter(d.pos).add(0.0, 0.8, 0.0))
            }
            else -> {
                ctx.source.sendError(Text.literal("Unbekannt: $what (${WHAT.joinToString()})"))
                return 0
            }
        }
        val capped = if (points.size > MAX_POINTS) points.filterIndexed { i, _ -> i % (points.size / MAX_POINTS + 1) == 0 } else points
        shows.add(Show(player.entityWorld as ServerWorld, capped, DustParticleEffect(color, 1.2f), SHOW_TICKS))
        ctx.source.sendFeedback({ Text.literal("Arena $id: ${capped.size} Marken ($what), 10 s").formatted(Formatting.GOLD) }, false)
        return 1
    }

    private fun sample(zone: Zone, y: Double, out: MutableList<Vec3d>) {
        for ((x, z) in zone.cells()) if ((x + z) and 1 == 0) out.add(Vec3d(x + 0.5, y, z + 0.5))
    }

    private fun tickShows() {
        val iterator = shows.iterator()
        while (iterator.hasNext()) {
            val s = iterator.next()
            if (s.left % 10 == 0) for (p in s.points) s.world.spawnParticles(s.particle, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0)
            if (--s.left <= 0) iterator.remove()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // /hha world retrofit
    // ---------------------------------------------------------------------------------------------

    private fun retrofitCommand(): LiteralArgumentBuilder<ServerCommandSource> = literal("retrofit")
        .then(literal("status").executes { ctx ->
            val lines = WorldRetrofit.status(ctx.source.server)
            for (line in lines) ctx.source.sendFeedback({ Text.literal(line).formatted(Formatting.GRAY) }, false)
            if (!HhaConfig.enabled(WorldRetrofit.TOGGLE)) ctx.source.sendFeedback({ Text.translatable("hha.arena.retrofit.off").formatted(Formatting.GOLD) }, false)
            lines.size
        })
        .then(literal("run").then(argument("teil", StringArgumentType.word())
            .suggests { _, b -> CommandSource.suggestMatching(OlympusStructures.ELEMENTS.map { it.id }, b) }
            .executes { ctx -> runRetrofit(ctx.source.server, ctx.source, StringArgumentType.getString(ctx, "teil")) }))
        .then(literal("all").executes { ctx -> runRetrofit(ctx.source.server, ctx.source, null) })

    private fun runRetrofit(server: MinecraftServer, source: ServerCommandSource, element: String?): Int {
        if (element != null && OlympusStructures.element(element) == null) {
            source.sendError(Text.literal("Unbekanntes Pfad-Teil: $element"))
            return 0
        }
        val count = WorldRetrofit.run(server, element)
        source.sendFeedback({ Text.literal("Nachrüstung ${element ?: "alle Teile"}: $count Blöcke gesetzt").formatted(Formatting.GOLD) }, true)
        return 1
    }

    // ---------------------------------------------------------------------------------------------
    // /hha sub, /hha device, /hha approach, /hha deco
    // ---------------------------------------------------------------------------------------------

    private fun subCommand(): LiteralArgumentBuilder<ServerCommandSource> = literal("sub").requires(ADMIN)
        .then(literal("test").then(argument("n", IntegerArgumentType.integer(0, 4)).executes { ctx ->
            val player = ctx.source.playerOrThrow
            val n = IntegerArgumentType.getInteger(ctx, "n")
            val keys = listOf("hha.sub.test.anvil", "hha.sub.test.bia", "hha.sub.test.fetter", "hha.sub.test.lock")
            val bars = (0 until n).map { i -> Cine.SubBar(keys[i], 1f - i * 0.22f, i % 3, if (i == 0) 3 else 0) }
            Cine.subBars(listOf(player), bars)
            ctx.source.sendFeedback({ Text.literal("Unterleisten: $n").formatted(Formatting.GOLD) }, false)
            1
        }))

    private fun deviceCommand(): LiteralArgumentBuilder<ServerCommandSource> = literal("device").requires(ADMIN)
        .then(argument("mode", IntegerArgumentType.integer(0, 2)).executes { ctx ->
            val player = ctx.source.playerOrThrow
            val hit = player.raycast(6.0, 0f, false)
            if (hit.type != HitResult.Type.BLOCK) {
                ctx.source.sendError(Text.literal("Kein Block im Blick."))
                return@executes 0
            }
            val pos = (hit as BlockHitResult).blockPos
            val world = player.entityWorld as ServerWorld
            val mode = IntegerArgumentType.getInteger(ctx, "mode")
            ArenaDevices.setMode(world, pos, mode)
            ctx.source.sendFeedback({ Text.literal("Gerät $pos: Zustand ${ArenaDevices.mode(world, pos)}").formatted(Formatting.GOLD) }, false)
            1
        })

    private fun approach(ctx: CommandContext<ServerCommandSource>): Int {
        val player = ctx.source.playerOrThrow
        val world = player.entityWorld as ServerWorld
        val specs = ArrayList(OlympusArenas.APPROACHES.filter { it.world == world.registryKey })
        val start = world.structureAccessor.getStructureContaining(player.blockPos) { it.value() is TartarusStructure }
        (start.children.firstOrNull() as? TartarusPiece)?.let { specs.add(TartarusArenas.siteApproach(it.kind, it.siteVariant(), it.middle)) }
        if (specs.isEmpty()) {
            ctx.source.sendError(Text.literal("Kein Weg in dieser Welt bzw. diesem Bauwerk."))
            return 0
        }
        for (spec in specs) {
            val progress = Approach.progress(player, spec)
            val next = Approach.next(player, spec)
            val line = "${spec.id} (${spec.bossKey}): ${progress?.let { "%.0f %%".format(it * 100) } ?: "nicht auf dem Weg"}, nächster Punkt ${next?.id ?: "–"}"
            ctx.source.sendFeedback({ Text.literal(line).formatted(Formatting.GRAY) }, false)
        }
        return specs.size
    }

    private fun giveDeco(ctx: CommandContext<ServerCommandSource>): Int {
        val player = ctx.source.playerOrThrow
        for (block in HhaDecoBlocks.ALL) {
            val stack = ItemStack(block.asItem(), 64)
            if (!player.inventory.insertStack(stack)) player.dropItem(stack, false)
        }
        ctx.source.sendFeedback({ Text.literal("${HhaDecoBlocks.ALL.size} Deko-Blöcke").formatted(Formatting.GOLD) }, false)
        return HhaDecoBlocks.ALL.size
    }

    private const val MAX_POINTS = 900
    private const val SHOW_TICKS = 200
}
