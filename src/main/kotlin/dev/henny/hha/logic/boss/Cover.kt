package dev.henny.hha.logic.boss

import dev.henny.hha.HhaSounds
import dev.henny.hha.logic.Vfx
import dev.henny.hha.world.arena.ArenaSpec
import dev.henny.hha.world.arena.CoverSpot
import net.minecraft.block.ShapeContext
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.hit.HitResult
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import net.minecraft.world.RaycastContext
import java.util.UUID
import kotlin.math.max

/**
 * Deckung als Sichtlinie (Überarbeitung 4, PLAN 1 Leitlinie 18, 7.5; Besitzer P03).
 *
 * - [blocked]: Raycast `COLLIDER` (Flüssigkeit zählt nicht) von der Quelle zum Ziel.
 * - [sheltered]: **zwei** Strahlen, Füße + [FEET_LIFT] und Augen; gedeckt nur, wenn beide blockiert sind. Wer halb
 *   hinter einer Stufe steht, ist nicht gedeckt.
 * - [shelter]: die [CoverSpot] aus dem Vertrag, in deren (um [SPOT_MARGIN] geweitetem) Kasten der erste Treffer liegt,
 *   und nur, wenn sie gegen den genannten Angriff schützt.
 * - [mark]: goldene Rune an jeder Deckung, hinter der gerade ein Kampfteilnehmer geschützt ist (alle [MARK_EVERY] Ticks
 *   neu bestimmt), so lange wie die Ansage.
 * - [status]: Statuszeile „gedeckt“ (Gold) / „gesehen“ (Gefahrenfarbe), nur bei Wechsel und höchstens alle
 *   [STATUS_EVERY] Ticks, mit Ton `arena.cover`.
 * - [lineEnd]: Endpunkt einer Linie, die an Deckung endet.
 *
 * Rezept für Kampf-Pakete: `val spot = Cover.shelter(world, eye, player, spec, OlympusArenas.HEKATE_GAZE)`;
 * `Cover.status(player, spot != null)`; Schaden nur ohne `spot`; einmal je Ansage `Cover.mark(telegraph, spec, id, eye, ticks)`.
 */
object Cover {

    /** Der untere Strahl zielt so hoch über die Füße (nicht genau auf den Boden, sonst trifft er die Bodenkante). */
    const val FEET_LIFT = 0.4

    /** Der Treffer muss im Kasten der Deckung liegen, geweitet um so viel (Blockkanten, Treppen davor). */
    const val SPOT_MARGIN = 0.5

    /** [mark]: so oft (Ticks) neu bestimmen, wer hinter welcher Deckung steht. */
    const val MARK_EVERY = 5

    /** [mark]: Lebensdauer einer Rune; sie wird erneuert, solange die Deckung schützt (kurz überlappend, kein Flackern). */
    const val MARK_RUNE_TICKS = 12

    /** [mark]: Rune so weit über den halben Kasten hinaus (liegt sichtbar um den Fuß der Deckung). */
    const val MARK_RUNE_EXTRA = 0.9f

    /** [status]: höchstens so oft (Ticks) eine neue Zeile. */
    const val STATUS_EVERY = 10

    /** [status]: Lautstärke des Tons (die Datei ist schon leise eingestellt). */
    const val STATUS_VOLUME = 0.8f

    /** [status]: Tonhöhe „gedeckt“ bzw. „gesehen“. */
    const val STATUS_PITCH_SHELTERED = 1.25f
    const val STATUS_PITCH_SEEN = 0.8f

    /** [status]: Zustand vergessen, wenn so lange (Ticks) nicht mehr gefragt. */
    const val STATUS_FORGET = 200

    /** Liegt zwischen [from] und [to] ein fester Block (Kollisionsform)? */
    fun blocked(world: ServerWorld, from: Vec3d, to: Vec3d): Boolean = raycast(world, from, to).type != HitResult.Type.MISS

    /** Gedeckt heißt: Füße (+ [FEET_LIFT]) und Augen sind von [source] aus beide verdeckt. */
    fun sheltered(world: ServerWorld, source: Vec3d, player: ServerPlayerEntity): Boolean =
        blocked(world, source, feet(player)) && blocked(world, source, player.eyePos)

    /**
     * Die Deckung aus [spec], die [player] gerade gegen [attack] schützt, oder `null`. Voraussetzung ist [sheltered];
     * maßgeblich ist der erste Treffer des Augenstrahls (sonst des Fußstrahls): Liegt er im geweiteten Kasten einer
     * Deckung, zählt diese, und nur wenn [attack] in ihren `protects` steht. Eine andere Wand davor hebt den Schutz
     * nicht auf, zählt aber auch nicht als benannte Deckung (`null`).
     */
    fun shelter(world: ServerWorld, source: Vec3d, player: ServerPlayerEntity, spec: ArenaSpec, attack: String): CoverSpot? {
        val eyeHit = raycast(world, source, player.eyePos)
        if (eyeHit.type == HitResult.Type.MISS) return null
        val feetHit = raycast(world, source, feet(player))
        if (feetHit.type == HitResult.Type.MISS) return null
        val spot = spotAt(spec, eyeHit.pos) ?: spotAt(spec, feetHit.pos) ?: return null
        return spot.takeIf { attack in it.protects }
    }

    /** Die Deckung, in deren um [SPOT_MARGIN] geweitetem Kasten [hit] liegt. */
    fun spotAt(spec: ArenaSpec, hit: Vec3d): CoverSpot? = spec.cover.firstOrNull { boxOf(it).expand(SPOT_MARGIN).contains(hit) }

    /** Kasten einer Deckung als Weltkasten (Blockkanten eingeschlossen). */
    fun boxOf(spot: CoverSpot): Box {
        val b = spot.box
        return Box(b.minX.toDouble(), b.minY.toDouble(), b.minZ.toDouble(), b.maxX + 1.0, b.maxY + 1.0, b.maxZ + 1.0)
    }

    /**
     * Goldene Rune für [ticks] Ticks an jeder Deckung, die gerade einen Spieler des Kampfes [fight] gegen [attack] von
     * [source] aus schützt. Alle [MARK_EVERY] Ticks neu bestimmt; eine Deckung, die niemanden mehr schützt, verliert
     * ihre Rune nach höchstens [MARK_RUNE_TICKS]. Gold bleibt im Kampf Gold (läuft im Scope von [fight]).
     */
    fun mark(fight: Telegraph.Fight, spec: ArenaSpec, attack: String, source: Vec3d, ticks: Int) {
        if (ticks <= 0) return
        marks.add(Mark(fight, spec, attack, source, fight.world.time + ticks))
    }

    /** Statuszeile „gedeckt“ (Gold) bzw. „gesehen“ (Gefahrenfarbe), nur bei Wechsel, höchstens alle [STATUS_EVERY] Ticks. */
    fun status(player: ServerPlayerEntity, sheltered: Boolean) {
        val world = player.entityWorld as? ServerWorld ?: return
        val now = world.time
        val state = states.getOrPut(player.uuid) { StatusState() }
        state.asked = now
        if (state.shown == sheltered) return
        if (now - state.sent < STATUS_EVERY) return
        state.shown = sheltered
        state.sent = now
        val line = if (sheltered) {
            Text.translatable("hha.cover.sheltered").formatted(Formatting.GOLD)
        } else {
            Text.translatable("hha.cover.seen").formatted(Formatting.RED)
        }
        player.sendMessage(line, true)
        val pitch = if (sheltered) STATUS_PITCH_SHELTERED else STATUS_PITCH_SEEN
        player.networkHandler.sendPacket(
            PlaySoundS2CPacket(
                Registries.SOUND_EVENT.getEntry(HhaSounds.ARENA_COVER), SoundCategory.HOSTILE,
                player.x, player.eyeY, player.z, STATUS_VOLUME, pitch, world.random.nextLong(),
            ),
        )
    }

    /** Erster Block auf der Strecke [from] → [to] (Trefferpunkt), sonst [to]. Für Linien, die an Deckung enden. */
    fun lineEnd(world: ServerWorld, from: Vec3d, to: Vec3d): Vec3d {
        val hit = raycast(world, from, to)
        return if (hit.type == HitResult.Type.MISS) to else hit.pos
    }

    // --- intern ----------------------------------------------------------------------------------------------------

    private fun feet(player: ServerPlayerEntity): Vec3d = player.entityPos.add(0.0, FEET_LIFT, 0.0)

    private fun raycast(world: ServerWorld, from: Vec3d, to: Vec3d) =
        world.raycast(RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, ShapeContext.absent()))

    private class Mark(val fight: Telegraph.Fight, val spec: ArenaSpec, val attack: String, val source: Vec3d, val until: Long) {
        /** Bis wann die Rune je Deckung schon steht. */
        val shown = HashMap<String, Long>()
    }

    private class StatusState {
        var shown: Boolean? = null
        var sent: Long = Long.MIN_VALUE / 2
        var asked: Long = 0L
    }

    private val marks = ArrayList<Mark>()
    private val states = HashMap<UUID, StatusState>()

    /** Aus [Arenas.init] jeden Server-Tick. */
    internal fun tick(server: MinecraftServer) {
        if (marks.isNotEmpty()) {
            val iterator = marks.iterator()
            while (iterator.hasNext()) {
                val mark = iterator.next()
                val world = mark.fight.world
                if (world.server !== server) continue
                val now = world.time
                if (now >= mark.until) {
                    iterator.remove()
                    continue
                }
                if (now % MARK_EVERY != 0L) continue
                tickMark(mark, now)
            }
        }
        if (states.isNotEmpty() && server.ticks % STATUS_FORGET == 0) {
            val now = server.overworld.time
            states.values.removeIf { now - it.asked > STATUS_FORGET }
        }
    }

    private fun tickMark(mark: Mark, now: Long) {
        val world = mark.fight.world
        val active = HashSet<CoverSpot>()
        for (player in mark.fight.players()) {
            if (player.entityWorld !== world || !player.isAlive || player.isSpectator) continue
            shelter(world, mark.source, player, mark.spec, mark.attack)?.let(active::add)
        }
        for (spot in active) {
            val until = mark.shown[spot.id] ?: Long.MIN_VALUE
            if (until - now > MARK_EVERY) continue
            val life = MARK_RUNE_TICKS.coerceAtMost((mark.until - now).toInt()).coerceAtLeast(1)
            val radius = max(spot.box.blockCountX, spot.box.blockCountZ) * 0.5f + MARK_RUNE_EXTRA
            mark.fight.during { Vfx.rune(world, Vfx.Style.GOLD, spot.center.add(0.0, 0.08, 0.0), radius, life) }
            mark.shown[spot.id] = now + life
        }
    }
}
