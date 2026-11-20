package dev.henny.hha.logic.boss.arena

import dev.henny.hha.Hha
import dev.henny.hha.HhaSounds
import dev.henny.hha.block.ArenaDeviceBlock
import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.HermesFights
import dev.henny.hha.world.arena.ArenaSpec
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.block.Block
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket
import net.minecraft.registry.Registries
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import java.util.UUID

/**
 * Bedienung der Arena-Geräte (Überarbeitung 4, PLAN 7.8, P05). Ein Kampf meldet je Geräteart einen Handler an ([on]);
 * ein Rechtsklick (Glocke auch Schlag) ruft ihn auf. Ablauf je Bedienung ([interact]):
 *
 * 1. Zuschauer lösen nichts aus. Ist an der Stelle eine Arena offen, zählt nur, wer an ihr teilnimmt ([Arenas.Arena.isMember]).
 * 2. **Zustand 1 (golden):** Handler der Geräteart. Liefert er `true`, klingt der Ton des Geräts ([sound]) und das Gerät
 *    geht auf Zustand 2. Liefert er `false`, bleibt alles, wie es ist (der Kampf meldet selbst, warum).
 *    Ohne offene Arena (Probe mit `/hha device 1`) wirkt das Gerät ohne Handler: Ton und Zustand 2.
 * 3. **Zustand 0 bzw. 2:** Statuszeile `hha.device.idle` bzw. `hha.device.spent` mit leisem Klick, höchstens einmal je
 *    [HINT_COOLDOWN] Ticks und Spieler.
 *
 * [setMode] schreibt über die `ArenaEdits` der offenen Arena, damit das Gerät beim Kampfende auf den Bauplan (Zustand 0)
 * zurückfällt; ohne Arena direkt. Außerhalb eines Kampfs stehen Geräte auf 0 und melden höchstens den Hinweis.
 *
 * Kein Gegenstand nötig, jede Hand: Die Rückrufe laufen in einer eigenen Phase vor allen anderen (`hha:devices`), damit
 * Schutzregeln für Blöcke in der Hand (Forum) das Gerät nicht verschlucken. Kreativ-Spieler, die schleichen, bauen
 * normal an Geräten an; Kreativ-Spieler zerschlagen eine Glocke nur, wenn sie nicht golden ist.
 */
object ArenaDevices {
    const val IDLE = ArenaDeviceBlock.IDLE
    const val READY = ArenaDeviceBlock.READY
    const val SPENT = ArenaDeviceBlock.SPENT

    /** Ticks zwischen zwei Hinweisen (Statuszeile und Klick) für denselben Spieler. */
    const val HINT_COOLDOWN = 20

    /** So lange (Ticks) zählt dieselbe Berührung desselben Geräts durch denselben Spieler nur einmal (gehaltene Taste). */
    const val TOUCH_LOCK = 6

    /** Lautstärke des Hinweis-Klicks. */
    const val HINT_VOLUME = 0.35f

    /** Lautstärke des Geräte-Tons beim Wirken. */
    const val SOUND_VOLUME = 1.0f

    /** Phase der Rückrufe, vor der Standardphase. */
    private val PHASE = Hha.id("devices")

    private val handlers = HashMap<String, (ServerWorld, BlockPos, ServerPlayerEntity) -> Boolean>()
    private val lastHint = HashMap<UUID, Long>()
    private val lastTouch = HashMap<UUID, Pair<Long, BlockPos>>()

    /** Handler für die Geräteart [kind] (`messenger_bell`, `grounding_rod`, `chain_post`, `sluice_wheel`); `true` = hat gewirkt. */
    fun on(kind: String, handler: (world: ServerWorld, pos: BlockPos, player: ServerPlayerEntity) -> Boolean) {
        handlers[kind] = handler
    }

    /** Zustand des Geräts an [pos] (0–2), [IDLE], wenn dort keins steht. */
    fun mode(world: ServerWorld, pos: BlockPos): Int {
        val state = world.getBlockState(pos)
        return if (state.block is ArenaDeviceBlock) state.get(ArenaDeviceBlock.MODE) else IDLE
    }

    /** Setzt den Zustand: über `ArenaEdits` der offenen Arena an dieser Stelle, sonst direkt. Ohne Gerät dort: nichts. */
    fun setMode(world: ServerWorld, pos: BlockPos, mode: Int) {
        val state = world.getBlockState(pos)
        if (state.block !is ArenaDeviceBlock) return
        val next = state.with(ArenaDeviceBlock.MODE, mode.coerceIn(IDLE, SPENT))
        if (next == state) return
        val edits = arenaAt(world, pos)?.edits
        if (edits != null) edits.set(pos, next) else world.setBlockState(pos, next, Block.NOTIFY_ALL)
    }

    /** Alle Geräte aus [spec] auf [IDLE] (für Kampf-Ausgänge, die nicht über `ArenaEdits` zurücksetzen). */
    fun reset(world: ServerWorld, spec: ArenaSpec) {
        for (spot in spec.devices) setMode(world, spot.pos, IDLE)
    }

    /** Ton des Geräts [kind] beim Wirken (PLAN 7.10). */
    fun sound(kind: String): SoundEvent = when (kind) {
        "messenger_bell" -> HhaSounds.ARENA_BELL
        "grounding_rod" -> HhaSounds.ARENA_ROD
        "chain_post" -> HhaSounds.ARENA_POST
        "sluice_wheel" -> HhaSounds.ARENA_SLUICE
        else -> HhaSounds.ARENA_BELL
    }

    /** Offene Arena, deren Kasten (bzw. ohne Vertrag: Fläche plus Rand) [pos] enthält. */
    fun arenaAt(world: ServerWorld, pos: BlockPos): Arenas.Arena? = Arenas.all().firstOrNull { arena ->
        arena.open && arena.world === world &&
            (arena.spec?.box?.contains(pos) ?: arena.near(Vec3d.ofCenter(pos)))
    }

    /** `UseBlockCallback` (Rechtsklick, jede Hand) und `AttackBlockCallback` (Glocke). */
    fun init() {
        UseBlockCallback.EVENT.addPhaseOrdering(PHASE, Event.DEFAULT_PHASE)
        AttackBlockCallback.EVENT.addPhaseOrdering(PHASE, Event.DEFAULT_PHASE)
        UseBlockCallback.EVENT.register(PHASE, UseBlockCallback { player, world, _, hit ->
            val pos = hit.blockPos
            val state = world.getBlockState(pos)
            val block = state.block as? ArenaDeviceBlock ?: return@UseBlockCallback ActionResult.PASS
            if (player.isSpectator || buildsOn(player)) return@UseBlockCallback ActionResult.PASS
            if (world is ServerWorld && player is ServerPlayerEntity) interact(world, pos, player, block)
            ActionResult.SUCCESS
        })
        AttackBlockCallback.EVENT.register(PHASE, AttackBlockCallback { player, world, _, pos, _ ->
            val state = world.getBlockState(pos)
            val block = state.block as? ArenaDeviceBlock ?: return@AttackBlockCallback ActionResult.PASS
            if (block.kind != "messenger_bell" || player.isSpectator) return@AttackBlockCallback ActionResult.PASS
            // Kreativ: nur die goldene Glocke wird angeschlagen, sonst lässt sie sich abbauen (Bau, Tests).
            if (player.isCreative && state.get(ArenaDeviceBlock.MODE) != READY) return@AttackBlockCallback ActionResult.PASS
            if (world is ServerWorld && player is ServerPlayerEntity) interact(world, pos, player, block)
            ActionResult.SUCCESS
        })
    }

    /** Kreativ-Spieler, die schleichen und etwas halten, bauen an das Gerät an statt es zu bedienen. */
    private fun buildsOn(player: PlayerEntity): Boolean = player.isCreative && player.isSneaking && !player.mainHandStack.isEmpty

    /** Eine Bedienung von [player] an [pos] (Ablauf im Kopfkommentar). `true` = das Gerät hat gewirkt. */
    internal fun interact(world: ServerWorld, pos: BlockPos, player: ServerPlayerEntity, block: ArenaDeviceBlock): Boolean {
        val now = world.time
        val touch = lastTouch[player.uuid]
        if (touch != null && touch.second == pos && now - touch.first < TOUCH_LOCK) return false
        lastTouch[player.uuid] = now to pos.toImmutable()

        // P24 (Freigabe A5, Wunsch P14 1): Das Himmelsforum ist keine `Arenas.Arena`. Läuft dort ein Hermes-Kampf, dürfen
        // nur seine Teilnehmer die Glocken bedienen, und es entscheidet immer der Handler (wie in einer Arena).
        val forum = HermesFights.of(world)?.takeIf { it.arena.contains(pos) }
        if (forum != null && !forum.isParticipant(player)) return false
        val arena = if (forum != null) null else arenaAt(world, pos)
        if (arena != null && !arena.isMember(player)) return false
        when (mode(world, pos)) {
            READY -> {
                val handler = handlers[block.kind]
                val worked = if (arena == null && forum == null) true else handler?.invoke(world, pos, player) ?: false
                if (!worked) return false
                world.playSound(null, pos, sound(block.kind), SoundCategory.BLOCKS, SOUND_VOLUME, 1.0f)
                setMode(world, pos, SPENT)
                return true
            }
            SPENT -> hint(world, pos, player, "hha.device.spent")
            else -> hint(world, pos, player, "hha.device.idle")
        }
        return false
    }

    /** Statuszeile (grau, nicht golden: Gold heißt „jetzt wirksam“) und leiser Klick nur für [player]. */
    private fun hint(world: ServerWorld, pos: BlockPos, player: ServerPlayerEntity, key: String) {
        val now = world.time
        val last = lastHint[player.uuid]
        if (last != null && now - last < HINT_COOLDOWN) return
        lastHint[player.uuid] = now
        player.sendMessage(Text.translatable(key).formatted(Formatting.GRAY, Formatting.ITALIC), true)
        val click = Registries.SOUND_EVENT.getEntry(SoundEvents.BLOCK_DISPENSER_FAIL)
        player.networkHandler.sendPacket(
            PlaySoundS2CPacket(click, SoundCategory.BLOCKS, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5, HINT_VOLUME, 1.4f, world.random.nextLong()),
        )
    }
}
