package dev.henny.hha.client.vfx.fx

import dev.henny.hha.Hha
import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.fx.boss.BrontesFx
import dev.henny.hha.client.vfx.fx.boss.GuardianFx
import dev.henny.hha.client.vfx.fx.boss.HadesFx
import dev.henny.hha.client.vfx.fx.boss.HekateFx
import dev.henny.hha.client.vfx.fx.boss.HermesFx
import dev.henny.hha.client.vfx.fx.boss.KampeFx
import dev.henny.hha.client.vfx.fx.boss.KratosFx
import dev.henny.hha.client.vfx.fx.boss.KronosFx
import dev.henny.hha.client.vfx.fx.boss.RpgFx
import dev.henny.hha.client.vfx.fx.boss.ZeusFx
import dev.henny.hha.client.vfx.fx.lib.CoreFx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.net.FxPayload
import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d

/**
 * Einstieg der neuen Effekte am Client (Überarbeitung 3, P00; nach Welle 0 eingefroren):
 * - [init] ruft alle Registrierungen auf (Bildschirm/Kamera, Bibliothek, je Boss-Paket eine Datei unter `fx/boss/`).
 * - [handle] baut aus einem [FxPayload] die [FxArgs] und fragt [FxRegistry].
 * - [tick] und [reset] treiben die Helfer, die eigene Zustände halten.
 * - [guarded] ist die Schutzschicht: Ein Fehler in einem Effekt wird je Name **einmal** mit Stapel geloggt und kippt
 *   weder das Bild noch die anderen Effekte.
 */
object FxClient {

    private val failed = HashSet<String>()

    fun init() {
        guarded("init:core.screen") { CoreScreen.init() }
        guarded("init:core") { CoreFx.init() }
        guarded("init:hermes") { HermesFx.init() }
        guarded("init:hekate") { HekateFx.init() }
        guarded("init:brontes") { BrontesFx.init() }
        guarded("init:kratos") { KratosFx.init() }
        guarded("init:zeus") { ZeusFx.init() }
        guarded("init:hades") { HadesFx.init() }
        guarded("init:kronos") { KronosFx.init() }
        guarded("init:guardian") { GuardianFx.init() }
        guarded("init:kampe") { KampeFx.init() }
        guarded("init:rpg") { RpgFx.init() }
        Hha.LOGGER.info("FX: {} Effekt-IDs registriert", FxRegistry.ids().size)
    }

    /** Vom Netz: ein Effekt per Text-ID. */
    fun handle(payload: FxPayload, world: ClientWorld) {
        if (payload.id == FxPayload.ID_LIST) {
            listToChat()
            return
        }
        val style = Vfx.Style.entries.getOrNull(payload.style) ?: Vfx.Style.GOLD
        val danger = payload.flags and Vfx.FLAG_DANGER != 0
        val palette = if (danger) Palette.danger(style) else Palette.of(style)
        FxRegistry.play(
            FxArgs(
                payload.id, style, palette, danger,
                Vec3d(payload.x, payload.y, payload.z), Vec3d(payload.dx, payload.dy, payload.dz),
                payload.a, payload.b, payload.c, payload.d, payload.life.coerceAtLeast(1),
                if (payload.entity >= 0) world.getEntityById(payload.entity) else null,
                if (payload.entity2 >= 0) world.getEntityById(payload.entity2) else null,
                payload.extra, world, FxRegistry.nextSeed(), payload.entity,
            ),
        )
    }

    /** Je Client-Tick mit Welt. */
    fun tick(world: ClientWorld) {
        guarded("tick:clock") { FxClock.tick() }
        guarded("tick:attach") { FxAttach.tick(world) }
        guarded("tick:screen") { ScreenPass.tick() }
        guarded("tick:debris") { BlockDebris.tick(world) }
        guarded("tick:projectile") { ProjectileFx.tick(world) }
    }

    /** Verbindungsende. */
    fun reset() {
        guarded("reset") {
            FxAttach.clear()
            FxClock.reset()
            ScreenPass.clear()
            BlockDebris.clear()
            ProjectileFx.clear()
        }
    }

    /**
     * Führt [block] aus; wirft es, wird der Fehler je [name] einmal mit Stapel geloggt und `null` geliefert.
     * Gedacht für Zeichen-, Tick- und Fabrik-Code der Effekte und Szenen (Regel 16).
     */
    fun <T> guarded(name: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        if (failed.add(name)) Hha.LOGGER.error("FX-Fehler in '{}' (weitere Fehler hier werden nicht mehr geloggt)", name, e)
        null
    }

    /** `/hha fx list`: die am Client registrierten IDs in den Chat. */
    private fun listToChat() {
        val chat = MinecraftClient.getInstance().inGameHud?.chatHud ?: return
        val ids = FxRegistry.ids()
        chat.addMessage(Text.literal("HHA FX: ${ids.size} Effekt-IDs registriert").formatted(Formatting.GOLD))
        for (chunk in ids.chunked(8)) chat.addMessage(Text.literal(chunk.joinToString(", ")).formatted(Formatting.GRAY))
    }
}
