package dev.henny.hha.client.rpg.game

import dev.henny.hha.Hha
import dev.henny.hha.HhaSounds
import dev.henny.hha.client.HhaClientConfig
import dev.henny.hha.client.cine.CinePlayer
import dev.henny.hha.client.gui.Ui
import dev.henny.hha.net.RpgEventPayload
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.event.Event
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.option.AttackIndicator
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.client.resource.language.I18n
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Arm
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Eigenes HUD der RPG-Welt (PLAN 3.5) über `HudElementRegistry.replaceElement`. Jedes ersetzte Vanilla-Element
 * entscheidet je Bild: Ist [active] (RPG-Welt und Schalter `rpg_hud`), zeichnet es das Eigene bzw. nichts, sonst ruft
 * es das Original unverändert auf ([replace]). Halte, Ankunft und Szenen zeigen kein HUD.
 *
 * - Lebensplatte über der linken Hälfte der Schnellleiste: zehn Segmente (halbe möglich), Zahl, Rüstungswert,
 *   Absorption als goldener Rand, verlorenes Stück leuchtet [TRAIL_HOLD_TICKS] nach und fällt weich ab, wenig Leben
 *   nur als roter Rand. Atemluft als dünne Linie darunter.
 * - Schnellleiste an Vanilla-Lage und -Maß (182 × 22, `CooldownHud` passt weiter), Auswahl in Wegfarbe.
 * - Kapitel und Ziel oben links mit Entfernung und Richtungspfeil; Gruppe links Mitte.
 * - Toasts ([RpgToasts]) und Tod ([RpgDeath]) liegen in einer späten Phase des `HudRenderCallback` über allem.
 *
 * Maße wie die Attrappe `tmp/ui_redesign/mock/rpg_game/rpg.js`. [init] ruft `HhaClient`.
 */
object RpgHud {

    /** Taste fürs Tagebuch (`key.hha.journal`, Standard J), gesetzt von `HhaClient`. */
    var journalKey: KeyBinding? = null

    // ------------------------------------------------------------------ Maße (GUI-Pixel)

    const val LIFE_W = 90
    const val LIFE_H = 20

    /** Abstand der Lebensplatte über der Schnellleiste (darin liegt die Atemluft-Linie). */
    const val LIFE_GAP = 3
    const val SEGMENTS = 10
    const val SEG_GAP = 2
    const val PLATE_ALPHA = 0.82f

    const val BAR_W = 182
    const val BAR_H = 22
    const val CELL = 20

    const val OBJ_X = 8
    const val OBJ_Y = 8
    const val OBJ_PAD = 7
    const val OBJ_MAX_W = 210

    /** Ziel bleibt so lange voll sichtbar, blendet dann in [OBJ_DIM_MS] auf [OBJ_DIM] ab. */
    const val OBJ_FULL_MS = 6000L
    const val OBJ_DIM_MS = 1000L
    const val OBJ_DIM = 0.6f
    const val OBJ_IN_MS = 300L

    /** Höhenunterschied, ab dem „höher/tiefer“ dasteht (Blöcke). */
    const val VERT_MIN = 6

    const val PARTY_W = 100
    const val PARTY_ROW = 18
    const val PARTY_MAX = 7
    const val PARTY_MIN_Y = 72

    /** Verlorenes Leben leuchtet so lange nach, dann fällt es je Tick um diesen Anteil ab. */
    const val TRAIL_HOLD_TICKS = 10
    const val TRAIL_DECAY = 0.2f
    const val TRAIL_ALPHA = 0.7f

    /** Späte Phase des `HudRenderCallback`: Toasts und Tod liegen über `CooldownHud`, `HudLine` und Blitzen. */
    private val LATE_PHASE: Identifier = Hha.id("rpg_late")

    private const val POISON_COLOR = 0xFF6BE38C.toInt()
    private const val WITHER_COLOR = 0xFF55555E.toInt()

    // ------------------------------------------------------------------ Zustand

    private val springs = Ui.Springs()
    private var lastNanos = 0L

    private var trackedPlayer: PlayerEntity? = null
    private var lastHealth = 0f
    private var trail = 0f
    private var prevTrail = 0f
    private var trailHold = 0

    private var lastObjective: String? = null
    private var objectiveShownAt = 0L
    private var wasHidden = true

    /** Zeichnet das RPG-HUD in diesem Bild (sonst reichen alle Elemente an Vanilla durch)? */
    fun active(): Boolean = RpgClientState.enabled && HhaClientConfig.enabled("rpg_hud")

    /** Halte, Ankunft oder Szene: das RPG-HUD zeigt nichts. */
    private fun hidden(): Boolean = RpgClientState.arriving || CinePlayer.active

    fun init() {
        replace(VanillaHudElements.HEALTH_BAR) { ctx, tick -> drawLife(ctx, tick) }
        replace(VanillaHudElements.HOTBAR) { ctx, tick -> drawHotbar(ctx, tick) }
        // Entfallen im RPG (PLAN 3.5): Rüstungs-, Hunger-, Luft-, Erfahrungs- und Kontextleiste
        replace(VanillaHudElements.ARMOR_BAR) { _, _ -> }
        replace(VanillaHudElements.FOOD_BAR) { _, _ -> }
        replace(VanillaHudElements.AIR_BAR) { _, _ -> }
        replace(VanillaHudElements.EXPERIENCE_LEVEL) { _, _ -> }
        // Kontextleiste: nur der Sprungbalken eines Reittiers bleibt (sonst wäre Reiten blind)
        HudElementRegistry.replaceElement(VanillaHudElements.INFO_BAR) { original ->
            HudElement { ctx, tick ->
                if (!active() || MinecraftClient.getInstance().player?.jumpingMount != null) original.render(ctx, tick)
            }
        }
        HudElementRegistry.addLast(Hha.id("rpg_objective")) { ctx, tick -> drawTop(ctx, tick) }
        HudRenderCallback.EVENT.register(LATE_PHASE) { ctx, _ ->
            RpgToasts.render(ctx)
            RpgDeath.drawOver(ctx, ctx.scaledWindowWidth, ctx.scaledWindowHeight)
        }
        HudRenderCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, LATE_PHASE)
        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
        RpgClientState.onEvent(::onEvent)
        RpgPauseScreen.register()
    }

    /** Ersetzt [id]: im RPG [draw] (außer in Halte/Szene), sonst das Original unverändert. */
    private fun replace(id: Identifier, draw: (DrawContext, RenderTickCounter) -> Unit) {
        HudElementRegistry.replaceElement(id) { original ->
            HudElement { ctx, tick ->
                if (active()) {
                    if (!hidden()) draw(ctx, tick)
                } else {
                    original.render(ctx, tick)
                }
            }
        }
    }

    // ================================================================== Ereignisse und Tick

    private fun onEvent(payload: RpgEventPayload) {
        when (payload.kind) {
            RpgEventPayload.TOAST -> {
                val key = payload.texts.firstOrNull() ?: return
                RpgToasts.push(Text.translatable("hha.rpg.hud.toast.notice"), Text.translatable(key, *payload.texts.drop(1).toTypedArray()), null)
            }
            RpgEventPayload.JOURNAL -> {
                val entry = payload.texts.firstOrNull() ?: return
                val done = entry.endsWith(".done")
                val boss = entry.removeSuffix(".done")
                val title = loreTitle(boss)
                RpgToasts.push(
                    Text.translatable("hha.rpg.hud.toast.journal"),
                    Text.translatable(if (done) "hha.rpg.hud.toast.journal.done" else "hha.rpg.hud.toast.journal.new", title),
                    HhaSounds.RPG_JOURNAL,
                )
            }
            RpgEventPayload.REST -> {
                val key = payload.texts.firstOrNull().orEmpty()
                val name = if (key.isEmpty()) Text.translatable("hha.rpg.rest.here") else Text.translatable(key)
                RpgToasts.push(Text.translatable("hha.rpg.hud.toast.rest"), Text.translatable("hha.rpg.hud.toast.rest.text", name), HhaSounds.RPG_REST)
            }
            RpgEventPayload.FALLEN -> RpgDeath.onFallen(payload.numbers.firstOrNull()?.toInt() ?: -1, payload.texts.firstOrNull().orEmpty())
            RpgEventPayload.CREDITS -> RpgCreditsScreen.open(payload.numbers, payload.texts)
        }
    }

    /** Titel eines Wissens-Eintrags (Texte von P17), sonst der Schlüssel als Name. */
    fun loreTitle(boss: String): Text =
        Text.translatableWithFallback("hha.rpg.lore.$boss.title", boss.replaceFirstChar { it.uppercase() })

    private fun tick(client: MinecraftClient) {
        val key = journalKey
        if (key != null) {
            while (key.wasPressed()) {
                if (RpgClientState.enabled && client.currentScreen == null && client.player != null && !hidden()) {
                    client.setScreen(RpgJournalScreen())
                }
            }
        }
        tickTrail(client.player)
        tickObjective()
        RpgToasts.tick()
        RpgDeath.tick(client)
    }

    private fun tickTrail(player: PlayerEntity?) {
        if (player == null) {
            trackedPlayer = null
            return
        }
        val hp = player.health
        prevTrail = trail
        if (player !== trackedPlayer) {
            // Neue Spielfigur (Beitritt, Wiedereinstieg): kein Nachleuchten
            trackedPlayer = player
            lastHealth = hp
            trail = hp
            prevTrail = hp
            trailHold = 0
            return
        }
        if (hp < lastHealth - 0.01f) {
            trail = max(trail, lastHealth)
            prevTrail = trail
            trailHold = TRAIL_HOLD_TICKS
        } else if (trailHold > 0) {
            trailHold--
        } else {
            trail += (hp - trail) * TRAIL_DECAY
        }
        if (trail < hp || abs(trail - hp) < 0.02f) trail = hp
        lastHealth = hp
    }

    private fun tickObjective() {
        val now = System.currentTimeMillis()
        val hiddenNow = !RpgClientState.enabled || hidden()
        val cur = if (RpgClientState.enabled && RpgClientState.objectiveKey.isNotEmpty()) {
            RpgClientState.chapter + "|" + RpgClientState.objectiveKey + "|" + RpgClientState.objectiveArgs.joinToString(",")
        } else {
            null
        }
        if (cur != lastObjective) {
            // Neues Ziel: kurz voll einblenden, mit Ton (nicht beim ersten Zustand nach dem Beitritt)
            if (cur != null) {
                if (lastObjective != null && !hiddenNow) RpgUi.play(HhaSounds.RPG_OBJECTIVE, 0.7f)
                objectiveShownAt = now
            }
            lastObjective = cur
        }
        // Nach Halte, Ankunft oder Szene erscheint das Ziel wieder voll
        if (wasHidden && !hiddenNow) objectiveShownAt = now
        wasHidden = hiddenNow
    }

    /** Verbindungsende. */
    fun reset() {
        trackedPlayer = null
        lastObjective = null
        wasHidden = true
        trailHold = 0
        RpgToasts.reset()
    }

    // ================================================================== Lebensplatte

    private fun drawLife(ctx: DrawContext, tick: RenderTickCounter) {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return
        val tr = client.textRenderer
        val cx = ctx.scaledWindowWidth / 2
        val h = ctx.scaledWindowHeight
        val x1 = cx - BAR_W / 2
        val x2 = x1 + LIFE_W
        val y2 = h - BAR_H - LIFE_GAP
        val y1 = y2 - LIFE_H
        val path = RpgUi.path()

        val max = player.maxHealth.coerceAtLeast(1f)
        val health = player.health.coerceIn(0f, max)
        val absorption = player.absorptionAmount
        val low = health <= max * RpgUi.LOW_HEALTH

        ctx.fill(x1, y1, x2, y2, Ui.alpha(Ui.SURFACE, PLATE_ALPHA))
        val frame = when {
            low -> Ui.alpha(Ui.BAD, 0.9f)
            absorption > 0f -> Ui.GOLD
            else -> Ui.LINE_2
        }
        Ui.frame(ctx, x1, y1, x2, y2, frame)

        // Zeile 1: Zahl, Absorption, Rüstung
        var tx = Ui.label(ctx, tr, ceil(health).toInt().toString(), x1 + 5, y1 + 3, if (low) Ui.BAD else Ui.TEXT)
        tx = Ui.label(ctx, tr, "/" + ceil(max).toInt(), tx, y1 + 3, Ui.DIM)
        if (absorption > 0f) Ui.label(ctx, tr, "+" + ceil(absorption).toInt(), tx + 4, y1 + 3, Ui.GOLD)
        val armor = player.armor
        if (armor > 0) {
            val s = armor.toString()
            val w = Ui.labelWidth(tr, s)
            Ui.label(ctx, tr, s, x2 - 5 - w, y1 + 3, Ui.MUTED)
            shield(ctx, x2 - 5 - w - 8, y1 + 4, Ui.DIM)
        }

        // Zeile 2: zehn Segmente (je ein Zehntel des Höchstwerts, halbe möglich), Nachleuchten weiß
        val fill = when {
            player.hasStatusEffect(StatusEffects.POISON) -> POISON_COLOR
            player.hasStatusEffect(StatusEffects.WITHER) -> WITHER_COLOR
            else -> path
        }
        val delta = tick.getTickProgress(false)
        val trailNow = MathHelper.lerp(delta, prevTrail, trail).coerceIn(health, max)
        segments(ctx, x1 + 5, y1 + 12, LIFE_W - 10, health / max, trailNow / max, fill)

        // Atemluft: dünne Linie zwischen Platte und Schnellleiste
        val maxAir = player.maxAir
        val air = player.air
        if (maxAir > 0 && air < maxAir) {
            ctx.fill(x1, y2 + 1, x2, y2 + 2, RpgUi.TRACK)
            ctx.fill(x1, y2 + 1, x1 + (LIFE_W * air.coerceAtLeast(0) / maxAir.toFloat()).roundToInt(), y2 + 2, Ui.alpha(Ui.TEXT, 0.85f))
        }
    }

    /** Zehn Segmente auf [bw] Breite; [frac] gefüllt, bis [trailFrac] das nachleuchtende Stück. */
    private fun segments(ctx: DrawContext, bx: Int, by: Int, bw: Int, frac: Float, trailFrac: Float, fill: Int) {
        val sw = (bw - (SEGMENTS - 1) * SEG_GAP) / SEGMENTS.toFloat()
        val light = Ui.brighten(fill, 0.45f)
        for (i in 0 until SEGMENTS) {
            val sx = (bx + i * (sw + SEG_GAP)).roundToInt()
            val ex = (bx + i * (sw + SEG_GAP) + sw).roundToInt()
            ctx.fill(sx, by, ex, by + 4, RpgUi.TRACK)
            val ft = (trailFrac * SEGMENTS - i).coerceIn(0f, 1f)
            if (ft > 0f) ctx.fill(sx, by, sx + ((ex - sx) * ft).roundToInt(), by + 4, Ui.alpha(0xFFFFFFFF.toInt(), TRAIL_ALPHA))
            val f = (frac * SEGMENTS - i).coerceIn(0f, 1f)
            if (f > 0f) {
                val e = sx + ((ex - sx) * f).roundToInt()
                ctx.fill(sx, by, e, by + 4, fill)
                ctx.fill(sx, by, e, by + 1, light)
            }
        }
    }

    /** Pixel-Schild 5 × 6 vor dem Rüstungswert. */
    private fun shield(ctx: DrawContext, x: Int, y: Int, c: Int) {
        ctx.fill(x, y, x + 5, y + 1, c)
        ctx.fill(x, y + 1, x + 1, y + 4, c)
        ctx.fill(x + 4, y + 1, x + 5, y + 4, c)
        ctx.fill(x + 1, y + 4, x + 2, y + 5, c)
        ctx.fill(x + 3, y + 4, x + 4, y + 5, c)
        ctx.fill(x + 2, y + 5, x + 3, y + 6, c)
        ctx.fill(x + 2, y + 1, x + 3, y + 4, Ui.alpha(c, 0.55f))
    }

    // ================================================================== Schnellleiste

    private fun drawHotbar(ctx: DrawContext, tick: RenderTickCounter) {
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return
        val nanos = System.nanoTime()
        springs.dt = if (lastNanos == 0L) 0.016f else ((nanos - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = nanos

        val tr = client.textRenderer
        val cx = ctx.scaledWindowWidth / 2
        val h = ctx.scaledWindowHeight
        val x1 = cx - BAR_W / 2
        val x2 = cx + BAR_W / 2
        val y1 = h - BAR_H
        val path = RpgUi.path()

        ctx.fill(x1, y1, x2, h, Ui.alpha(Ui.SURFACE, PLATE_ALPHA))
        Ui.frame(ctx, x1, y1, x2, h, Ui.LINE_2)
        for (i in 1 until 9) Ui.vline(ctx, x1 + 1 + i * CELL, y1 + 1, h - 1, Ui.LINE)

        // Auswahl gleitet weich zum neuen Platz: Rahmen in Wegfarbe, leichter Schein, 2-px-Unterkante
        val selected = player.inventory.selectedSlot.coerceIn(0, 8)
        val sx = x1 + springs.get("sel", (selected * CELL).toFloat(), 28f).roundToInt()
        ctx.fill(sx + 1, y1 + 1, sx + 21, h - 1, Ui.alpha(path, 0.10f))
        Ui.frame(ctx, sx, y1, sx + 22, h, path)
        ctx.fill(sx + 1, h - 3, sx + 21, h - 1, path)

        // Gegenstände wie Vanilla (InGameHud.renderHotbarItem ohne Hüpfen)
        for (i in 0 until 9) {
            val stack = player.inventory.getStack(i)
            if (stack.isEmpty) continue
            val ix = cx - 90 + i * CELL + 2
            val iy = h - 16 - 3
            ctx.drawItem(player, stack, ix, iy, i + 1)
            ctx.drawStackOverlay(tr, stack, ix, iy)
        }

        // Zweithand auf der Seite der freien Hand, wie Vanilla
        val offArm = player.mainArm.opposite
        val off = player.offHandStack
        if (!off.isEmpty) {
            val ox = if (offArm == Arm.LEFT) x1 - 29 else x2 + 7
            ctx.fill(ox, y1, ox + 22, h, Ui.alpha(Ui.SURFACE, PLATE_ALPHA))
            Ui.frame(ctx, ox, y1, ox + 22, h, Ui.LINE_2)
            val ix = if (offArm == Arm.LEFT) cx - 91 - 26 else cx + 91 + 10
            ctx.drawItem(player, off, ix, h - 16 - 3, 10)
            ctx.drawStackOverlay(tr, off, ix, h - 16 - 3)
        }

        // Angriffsanzeige in der Schnellleiste (Option „Schnellleiste“): schmaler Balken statt Schwert-Symbol
        if (client.options.attackIndicator.value == AttackIndicator.HOTBAR) {
            val progress = player.getAttackCooldownProgress(0f)
            if (progress < 1f) {
                val ax = if (offArm == Arm.RIGHT) x1 - 22 + 9 else x2 + 6 + 9
                ctx.fill(ax, h - 20, ax + 3, h - 2, RpgUi.TRACK)
                val top = h - 2 - (18 * progress).roundToInt()
                ctx.fill(ax, top, ax + 3, h - 2, Ui.alpha(Ui.TEXT, 0.9f))
            }
        }
    }

    // ================================================================== Kapitel, Ziel, Gruppe

    private fun drawTop(ctx: DrawContext, tick: RenderTickCounter) {
        if (!active() || hidden()) return
        val client = MinecraftClient.getInstance()
        if (client.player == null || client.options.hudHidden || client.debugHud.shouldShowDebugHud()) return
        drawObjective(ctx, tick)
        drawParty(ctx)
    }

    private class TargetRow(val where: String?, val dist: String, val vertical: String?, val angle: Float)

    private fun targetRow(client: MinecraftClient, delta: Float): TargetRow? {
        val target = RpgClientState.target ?: return null
        val player = client.player ?: return null
        val world = client.world ?: return null
        val here = world.registryKey.value.toString()
        if (RpgClientState.targetWorld.isNotEmpty() && RpgClientState.targetWorld != here) {
            val id = Identifier.tryParse(RpgClientState.targetWorld)
            val key = "hha.rpg.hud.area." + (id?.path ?: "")
            return TargetRow(RpgUi.tr(if (id != null && I18n.hasTranslation(key)) key else "hha.rpg.hud.elsewhere"), "", null, 0f)
        }
        val pos = player.getLerpedPos(delta)
        val dx = target.x - pos.x
        val dz = target.z - pos.z
        val dy = target.y - pos.y
        val dist = sqrt(dx * dx + dz * dz).roundToInt()
        // Peilung im Yaw-Maß (0 = +Z, im Uhrzeigersinn von oben), relativ zur Blickrichtung
        val bearing = Math.toDegrees(atan2(-dx, dz)).toFloat()
        val angle = MathHelper.wrapDegrees(bearing - player.getYaw(delta))
        val vertical = if (abs(dy) >= VERT_MIN) {
            RpgUi.tr(if (dy > 0) "hha.rpg.hud.above" else "hha.rpg.hud.below", abs(dy).roundToInt())
        } else {
            null
        }
        return TargetRow(null, RpgUi.tr("hha.rpg.hud.distance", dist), vertical, angle)
    }

    private fun drawObjective(ctx: DrawContext, tick: RenderTickCounter) {
        val chapter = RpgClientState.chapter
        if (chapter.isEmpty()) return
        val client = MinecraftClient.getInstance()
        val tr = client.textRenderer
        val path = RpgUi.path()
        val pad = OBJ_PAD
        val kicker = RpgUi.tr(RpgUi.kickerKey(chapter))
        val title = RpgUi.tr("hha.rpg.story.$chapter.title")
        val objective = RpgClientState.objective
        val lines = if (objective != null) tr.wrapLines(objective, OBJ_MAX_W - 2 * pad - 2) else emptyList()
        val row = targetRow(client, tick.getTickProgress(false))

        // Breite und Höhe vorab (dieselbe Rechnung wie die Attrappe)
        val head = Ui.labelWidth(tr, kicker) + 8 + Ui.displayWidth(tr, title)
        val rowW = when {
            row == null -> 0
            row.where != null -> Ui.labelWidth(tr, row.where)
            else -> 16 + Ui.labelWidth(tr, row.dist) + (row.vertical?.let { 8 + Ui.labelWidth(tr, it) } ?: 0)
        }
        val textW = lines.maxOfOrNull { tr.getWidth(it) } ?: 0
        val w = minOf(OBJ_MAX_W, maxOf(head, rowW, textW) + 2 * pad + 2)
        var h = pad + 9
        if (lines.isNotEmpty()) h += 4 + lines.size * 10
        if (row != null) h += 5 + 11
        h += pad - 4

        // Deckkraft: neu voll, nach 6 s auf 60 %; kurzes Hereingleiten
        val t = System.currentTimeMillis() - objectiveShownAt
        val a = if (t < OBJ_FULL_MS) 1f else 1f - (1f - OBJ_DIM) * ((t - OBJ_FULL_MS) / OBJ_DIM_MS.toFloat()).coerceIn(0f, 1f)
        val x = OBJ_X - ((1f - Ui.easeOutCubic(t / OBJ_IN_MS.toFloat())) * 6f).roundToInt()
        val y = OBJ_Y

        ctx.fill(x, y, x + w, y + h, Ui.alpha(Ui.SURFACE, 0.78f * a))
        Ui.frame(ctx, x, y, x + w, y + h, Ui.alpha(Ui.LINE, a))
        ctx.fill(x, y, x + 2, y + h, Ui.alpha(path, a))
        var ty = y + pad - 1
        val kx = Ui.label(ctx, tr, kicker, x + pad + 2, ty, Ui.alpha(path, a))
        Ui.display(ctx, tr, title, kx + 8, ty, Ui.alpha(Ui.TEXT, a))
        ty += 13
        for (line in lines) {
            ctx.drawText(tr, line, x + pad + 2, ty, Ui.alpha(Ui.TEXT, a), false)
            ty += 10
        }
        if (row == null) return
        ty += 3
        Ui.hline(ctx, x + pad + 2, x + w - pad, ty - 2, Ui.alpha(Ui.LINE, a))
        ty += 2
        if (row.where != null) {
            Ui.label(ctx, tr, row.where, x + pad + 2, ty + 1, Ui.alpha(Ui.MUTED, a))
        } else {
            RpgUi.arrow(ctx, x + pad + 7, ty + 4, row.angle, Ui.alpha(path, a))
            val ex = Ui.label(ctx, tr, row.dist, x + pad + 16, ty + 1, Ui.alpha(Ui.TEXT, a))
            if (row.vertical != null) Ui.label(ctx, tr, row.vertical, ex + 8, ty + 1, Ui.alpha(Ui.DIM, a))
        }
    }

    private fun drawParty(ctx: DrawContext) {
        val client = MinecraftClient.getInstance()
        val own = client.player?.uuidAsString
        val members = RpgClientState.party.filter { it.uuid != own }.take(PARTY_MAX)
        if (members.isEmpty()) return
        val tr = client.textRenderer
        val path = RpgUi.path()
        val total = members.size * PARTY_ROW - 2
        val y0 = maxOf(PARTY_MIN_Y, ctx.scaledWindowHeight / 2 - total / 2)
        val x1 = OBJ_X
        val x2 = x1 + PARTY_W
        members.forEachIndexed { i, m ->
            val y = y0 + i * PARTY_ROW
            ctx.fill(x1, y, x2, y + PARTY_ROW - 2, Ui.alpha(Ui.SURFACE, 0.7f))
            Ui.frame(ctx, x1, y, x2, y + PARTY_ROW - 2, Ui.LINE)
            val right = if (m.down) RpgUi.tr("hha.rpg.hud.down") else ceil(m.health).toInt().toString()
            val rw = Ui.labelWidth(tr, right)
            Ui.label(ctx, tr, RpgUi.trimLabel(tr, m.name, PARTY_W - 10 - rw - 6), x1 + 5, y + 2, if (m.down) Ui.DIM else Ui.TEXT)
            Ui.label(ctx, tr, right, x2 - 5 - rw, y + 2, if (m.down) Ui.DIM else Ui.MUTED)
            val bx1 = x1 + 5
            val bx2 = x2 - 5
            val by = y + 11
            ctx.fill(bx1, by, bx2, by + 2, RpgUi.TRACK)
            if (!m.down && m.maxHealth > 0f) {
                val f = (m.health / m.maxHealth).coerceIn(0f, 1f)
                ctx.fill(bx1, by, bx1 + ((bx2 - bx1) * f).roundToInt(), by + 2, if (f <= RpgUi.LOW_HEALTH) Ui.BAD else path)
            }
        }
    }
}
