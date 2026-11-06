package dev.henny.hha.client

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.text.Text

/**
 * `/hhac` — Clientbefehl für die visuellen Einstellungen aus [HhaClientConfig].
 * Änderungen greifen sofort und werden in `config/hha/client.json` gespeichert.
 */
object HhaClientCommands {

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommandManager.literal("hhac")
                    .then(boolNode("hud", "hud_enabled"))
                    .then(boolNode("pulse", "hud_pulse"))
                    .then(boolNode("background", "hud_background"))
                    .then(boolNode("ultrabar", "ultra_bar"))
                    .then(boolNode("vfx", "vfx_3d"))
                    .then(boolNode("shake", "screen_shake"))
                    .then(numberNode("scale", "hud_scale"))
                    .then(numberNode("density", "particle_density"))
                    .then(
                        ClientCommandManager.literal("offset")
                            .then(
                                ClientCommandManager.argument("x", IntegerArgumentType.integer(-1000, 1000))
                                    .then(
                                        ClientCommandManager.argument("y", IntegerArgumentType.integer(-1000, 1000))
                                            .executes { ctx ->
                                                val x = IntegerArgumentType.getInteger(ctx, "x")
                                                val y = IntegerArgumentType.getInteger(ctx, "y")
                                                HhaClientConfig.setNumber("hud_offset_x", x.toDouble())
                                                HhaClientConfig.setNumber("hud_offset_y", y.toDouble())
                                                ctx.source.sendFeedback(
                                                    Text.translatable("hha.clientcmd.set", "hud_offset", "$x, $y")
                                                )
                                                1
                                            }
                                    )
                            )
                    )
                    .then(
                        ClientCommandManager.literal("list").executes { ctx ->
                            ctx.source.sendFeedback(Text.translatable("hha.clientcmd.list_header"))
                            for ((key, _) in HhaClientConfig.TOGGLE_DEFAULTS) {
                                ctx.source.sendFeedback(
                                    Text.translatable("hha.clientcmd.list_entry", key, HhaClientConfig.enabled(key).toString())
                                )
                            }
                            for ((key, _) in HhaClientConfig.NUMBER_DEFAULTS) {
                                ctx.source.sendFeedback(
                                    Text.translatable("hha.clientcmd.list_entry", key, format(HhaClientConfig.num(key)))
                                )
                            }
                            1
                        }
                    )
                    .then(
                        ClientCommandManager.literal("reset").executes { ctx ->
                            HhaClientConfig.reset()
                            ctx.source.sendFeedback(Text.translatable("hha.clientcmd.reset"))
                            1
                        }
                    )
            )
        }
    }

    private fun boolNode(name: String, key: String): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommandManager.literal(name)
            .then(
                ClientCommandManager.argument("value", BoolArgumentType.bool())
                    .executes { ctx ->
                        val value = BoolArgumentType.getBool(ctx, "value")
                        HhaClientConfig.setToggle(key, value)
                        ctx.source.sendFeedback(Text.translatable("hha.clientcmd.set", key, value.toString()))
                        1
                    }
            )

    private fun numberNode(name: String, key: String): LiteralArgumentBuilder<FabricClientCommandSource> =
        ClientCommandManager.literal(name)
            .then(
                ClientCommandManager.argument("value", DoubleArgumentType.doubleArg())
                    .executes { ctx ->
                        HhaClientConfig.setNumber(key, DoubleArgumentType.getDouble(ctx, "value"))
                        ctx.source.sendFeedback(
                            Text.translatable("hha.clientcmd.set", key, format(HhaClientConfig.num(key)))
                        )
                        1
                    }
            )

    /** Ganzzahlige Werte ohne Nachkommastellen, sonst kurz gerundet. */
    private fun format(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value)
}
