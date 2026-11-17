package dev.henny.hha.client.rpg.game

import dev.henny.hha.HhaSounds
import dev.henny.hha.client.HudLine
import dev.henny.hha.client.gui.Ui
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * Eigener Tod der RPG-Welt (PLAN 3.5). Sofortiger Wiedereinstieg ist an (P06), es gibt keinen `DeathScreen`. Bei
 * `RpgEventPayload(FALLEN)` oder sobald die eigene Gesundheit 0 ist: Blende in Wegfarbe (dunkel), Karte „Gefallen“,
 * Zeile darunter, Ton `rpg.fallen`. Hält, bis der Spieler wieder lebt und [ALIVE_TICKS] vergangen sind (höchstens
 * [MAX_HOLD_TICKS]), blendet dann aus und zeigt die Statuszeile mit der Rast ([HudLine]).
 *
 * [drawOver] zeichnen das HUD (späte Phase, siehe [RpgHud]) und die unterdrückten Ladebildschirme
 * (`RpgScreens.replaceRender`, P18). Maße wie `rpgDeath` in der Attrappe.
 */
object RpgDeath {

    /** So lange muss der Spieler nach dem Wiedereinstieg leben, bevor die Blende geht. */
    const val ALIVE_TICKS = 20

    /** Rückfall: Spätestens dann geht die Blende, auch ohne Wiedereinstieg (nichts hängt). */
    const val MAX_HOLD_TICKS = 300

    const val FADE_IN_MS = 400L
    const val FADE_OUT_MS = 600L

    /** Deckkraft der Blende und Anteil der Wegfarbe darin. */
    const val SHADE_ALPHA = 0.9f
    const val SHADE_TINT = 0.10f

    /** Die Statuszeile nach dem Wiedereinstieg steht so lange. */
    const val BACK_LINE_TICKS = 80

    private enum class State { IDLE, HOLD, FADE }

    private var state = State.IDLE
    private var startedAt = 0L
    private var fadeAt = 0L
    private var heldTicks = 0
    private var aliveTicks = 0
    private var deaths = -1
    private var restKey = ""

    /** Liegt gerade die Tod-Blende über dem Bild? */
    val active: Boolean get() = state != State.IDLE

    /** Ereignis `FALLEN` (P06): Tode der Welt und Rast, zu der es zurückgeht. */
    fun onFallen(deaths: Int, restKey: String) {
        if (deaths >= 0) this.deaths = deaths
        if (restKey.isNotEmpty()) this.restKey = restKey
        if (state != State.HOLD) start()
    }

    private fun start() {
        if (state == State.IDLE || state == State.FADE) {
            state = State.HOLD
            startedAt = System.currentTimeMillis()
            heldTicks = 0
            aliveTicks = 0
            if (restKey.isEmpty()) restKey = RpgClientState.restKey
            RpgUi.play(HhaSounds.RPG_FALLEN, 1f)
        }
    }

    /** Je Client-Tick (aus [RpgHud]). */
    fun tick(client: MinecraftClient) {
        if (!RpgClientState.enabled) {
            if (active) reset()
            return
        }
        val player = client.player
        val dead = player != null && (player.isDead || player.health <= 0f)
        if (state == State.IDLE && dead) start()
        if (state == State.HOLD) {
            heldTicks++
            aliveTicks = if (player != null && !dead && client.world != null) aliveTicks + 1 else 0
            if (aliveTicks >= ALIVE_TICKS || heldTicks >= MAX_HOLD_TICKS) {
                state = State.FADE
                fadeAt = System.currentTimeMillis()
                val rest = if (restKey.isEmpty()) Text.translatable("hha.rpg.rest.here") else Text.translatable(restKey)
                HudLine.show(Text.translatable("hha.rpg.death.back", rest), BACK_LINE_TICKS, RpgClientState.color())
            }
        } else if (state == State.FADE && System.currentTimeMillis() - fadeAt >= FADE_OUT_MS) {
            state = State.IDLE
            deaths = -1
            restKey = ""
        }
    }

    /** Zeichnet die Blende über den ganzen Bildschirm ([width] × [height] in GUI-Pixeln). Ohne Tod: nichts. */
    fun drawOver(ctx: DrawContext, width: Int, height: Int) {
        if (state == State.IDLE) return
        val now = System.currentTimeMillis()
        val inT = Ui.easeOutCubic((now - startedAt) / FADE_IN_MS.toFloat())
        val outT = if (state == State.FADE) 1f - ((now - fadeAt) / FADE_OUT_MS.toFloat()).coerceIn(0f, 1f) else 1f
        val a = (inT * outT).coerceIn(0f, 1f)
        if (a <= 0.01f) return
        val tr = MinecraftClient.getInstance().textRenderer
        val path = RpgUi.path()

        // Blende: dunkel mit einem Hauch Wegfarbe, oben und unten dunkler
        val tint = Ui.lerp(0xFF000000.toInt(), path, SHADE_TINT)
        ctx.fill(0, 0, width, height, Ui.alpha(tint, SHADE_ALPHA * a))
        ctx.fillGradient(0, 0, width, height / 3, Ui.alpha(0xFF000000.toInt(), 0.55f * a), 0)
        ctx.fillGradient(0, height - height / 3, width, height, 0, Ui.alpha(0xFF000000.toInt(), 0.55f * a))

        // Karte: Tode, „Gefallen“ (steigt auf), Lichtlinie wächst aus der Mitte, Zeile, Rast
        val title = RpgUi.tr("hha.rpg.death.title")
        val scale = RpgUi.displayScale(tr, title, width - 40, 4, 3, 2)
        val tw = Ui.displayWidth(tr, title, scale)
        val cx = width / 2
        val cy = (height * 0.42f).toInt()
        val rise = ((1f - inT) * 6f).toInt()
        if (deaths > 0) {
            val count = RpgUi.tr("hha.rpg.death.count", deaths)
            Ui.label(ctx, tr, count, cx - Ui.labelWidth(tr, count) / 2, cy - 8 * scale, Ui.alpha(Ui.DIM, a))
        }
        Ui.display(ctx, tr, title, cx - tw / 2, cy - 9 * scale / 2 + 2 + rise, Ui.alpha(Ui.TEXT, a), scale)
        val lineY = cy + 5 * scale / 2 + 8
        RpgUi.lightLine(ctx, cx, lineY, (tw * 0.55f * inT).toInt(), path, a)
        val line = RpgUi.tr("hha.rpg.death.line")
        ctx.drawText(tr, line, cx - tr.getWidth(line) / 2, lineY + 8, Ui.alpha(Ui.MUTED, a), false)
        if (restKey.isNotEmpty()) {
            val rest = RpgUi.tr("hha.rpg.death.rest", Text.translatable(restKey))
            Ui.label(ctx, tr, rest, cx - Ui.labelWidth(tr, rest) / 2, lineY + 24, Ui.alpha(path, 0.85f * a))
        }
    }

    /** Verbindungsende: [active] geht zurück. */
    fun reset() {
        state = State.IDLE
        deaths = -1
        restKey = ""
        heldTicks = 0
        aliveTicks = 0
    }
}
