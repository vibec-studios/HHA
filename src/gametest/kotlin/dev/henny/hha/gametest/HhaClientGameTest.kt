package dev.henny.hha.gametest

import dev.henny.hha.HhaItems
import dev.henny.hha.logic.boss.HermesFights
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext
import net.minecraft.item.ItemStack
import net.minecraft.util.math.BlockPos

/**
 * Startet das echte Spiel und schießt Bilder der Effekte. Über die Systemeigenschaft `hha.test` lässt
 * sich der Ablauf einschränken: `vfx`, `boss` oder (Standard) alles.
 */
class HhaClientGameTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        val mode = System.getProperty("hha.test", "all")
        context.worldBuilder()
            .adjustSettings { it.setGameMode(net.minecraft.client.gui.screen.world.WorldCreator.Mode.CREATIVE) }
            .create().use { world ->
                world.clientWorld.waitForChunksRender()
                setup(world)
                context.waitTicks(40)
                if (mode == "all" || mode == "vfx") effects(context, world)
                if (mode == "all" || mode == "boss") bossFight(context, world)
                if (mode == "intro") intro(context, world)
                if (mode == "soak") soak(context, world)
            }
    }

    /** Lässt jede Stage durch ihre ganze Rotation laufen und trifft dabei Hermes und seine Helfer wirklich. */
    private fun soak(context: ClientGameTestContext, world: TestSingleplayerContext) {
        val server = world.server
        server.runCommand("gamemode survival @p")
        server.runCommand("hha set hermes_damage 0")
        server.runCommand("hha set hermes_toughness 1000")
        server.runCommand("execute as @p at @s run hha hermes start")
        context.waitTicks(1000)
        val cutsceneLengths = listOf(0, 560, 560, 760)
        for (stage in 1..4) {
            for (round in 0 until 18) {
                context.waitTicks(100)
                server.runCommand("execute as @p run damage @e[type=hha:hermes,limit=1,sort=random] 10 minecraft:player_attack by @p")
                server.runCommand("execute as @p run damage @e[type=hha:herma,limit=1,sort=random] 25 minecraft:player_attack by @p")
                server.runCommand("execute as @p run damage @e[type=hha:serpent,limit=1,sort=random] 30 minecraft:player_attack by @p")
                if (round % 6 == 0) context.takeScreenshot("soak_s${stage}_$round")
            }
            // Der Kampf muss die ganze Stage überlebt haben, sonst ist der Testlauf wertlos.
            assertStage(world, stage)
            server.runCommand("execute as @p at @s run hha hermes skip")
            if (stage < 4) context.waitTicks(cutsceneLengths[stage] + 40)
        }
        context.waitTicks(1500)
        assertStage(world, 5)
        context.takeScreenshot("soak_finale")
        server.runCommand("execute as @p at @s run hha hermes skip")
        context.waitTicks(220)
        world.server.runOnServer<RuntimeException> { srv ->
            check(HermesFights.of(srv.overworld) == null) { "Kampf läuft nach dem Öffnen des Pakets noch" }
            val player = srv.playerManager.playerList.first()
            check(player.inventory.contains(ItemStack(HhaItems.HEAVEN_CHESTPLATE))) { "Keine Belohnung im Inventar" }
            check(player.entityWorld.getBlockState(BlockPos(0, 190, 0)).isAir) { "Forum wurde nicht abgebaut" }
        }
        context.takeScreenshot("soak_end")
    }

    private fun assertStage(world: TestSingleplayerContext, expected: Int) {
        world.server.runOnServer<RuntimeException> { srv ->
            val stage = HermesFights.of(srv.overworld)?.stage
            check(stage == expected) { "Erwartet Stage $expected, tatsächlich $stage" }
        }
    }

    private fun intro(context: ClientGameTestContext, world: TestSingleplayerContext) {
        world.server.runCommand("gamemode survival @p")
        world.server.runCommand("execute as @p at @s run hha hermes start")
        for (i in 0 until 8) {
            context.waitTicks(20)
            context.takeScreenshot("intro_$i")
        }
    }

    /** Spielt den ganzen Bossfight durch: alle Cutscenes, jede Stage ein Stück, dann das Finale. */
    private fun bossFight(context: ClientGameTestContext, world: TestSingleplayerContext) {
        val server = world.server
        server.runCommand("gamemode survival @p")
        server.runCommand("hha set hermes_damage 0")
        server.runCommand("execute as @p at @s run hha hermes start")
        fun shots(prefix: String, count: Int, every: Int) {
            for (i in 0 until count) {
                context.waitTicks(every)
                context.takeScreenshot("${prefix}_$i")
            }
        }
        shots("prologue", 16, 56)
        shots("stage1", 5, 50)
        server.runCommand("execute as @p at @s run hha hermes skip")
        shots("feather", 8, 62)
        shots("stage2", 5, 70)
        server.runCommand("execute as @p at @s run hha hermes skip")
        shots("descent", 8, 62)
        shots("stage3", 5, 70)
        server.runCommand("execute as @p at @s run hha hermes skip")
        shots("zeus", 10, 62)
        shots("stage4", 5, 70)
        server.runCommand("execute as @p at @s run hha hermes skip")
        shots("finale", 12, 60)
        context.waitTicks(100)
        server.runCommand("execute as @p at @s run hha hermes skip")
        shots("opened", 3, 40)
        context.waitTicks(140)
        context.takeScreenshot("back_on_ground")
    }

    private fun setup(world: TestSingleplayerContext) {
        val server = world.server
        server.runCommand("gamerule doDaylightCycle false")
        server.runCommand("time set noon")
        server.runCommand("gamerule doMobSpawning false")
        server.runCommand("tp @p 0 ~ 0 0 5")
    }

    private fun effects(context: ClientGameTestContext, world: TestSingleplayerContext) {
        val frames = linkedMapOf(
            "light_beam" to 8, "lava_beam" to 8, "light_wave" to 8, "shockwave" to 8, "magma_stomp" to 8,
            "bolt" to 3, "pillar" to 14, "soul_pillar" to 14, "chain" to 8, "rift" to 24, "nova" to 7, "rune" to 22,
            "tornado" to 24, "flood" to 30, "spear" to 50, "feathers" to 14, "telegraph" to 30,
        )
        for ((name, wait) in frames) {
            world.server.runCommand("execute as @p at @s run hha vfx $name")
            context.waitTicks(wait)
            context.takeScreenshot("vfx_$name")
            context.waitTicks(30)
        }
        // Effekte am eigenen Körper brauchen die Außenansicht.
        context.getInput().pressKey { it.togglePerspectiveKey }
        context.waitTicks(2)
        context.getInput().pressKey { it.togglePerspectiveKey }
        context.waitTicks(2)
        for ((name, wait) in linkedMapOf("wings" to 26, "fire_wings" to 26, "shield" to 8, "aura" to 20, "rings" to 20)) {
            world.server.runCommand("execute as @p at @s run hha vfx $name")
            context.waitTicks(wait)
            context.takeScreenshot("vfx_$name")
            context.waitTicks(60)
        }
        context.getInput().pressKey { it.togglePerspectiveKey }
    }
}
