package dev.henny.hha.rpg

import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.world.HadesGate
import dev.henny.hha.net.RpgEventPayload
import dev.henny.hha.world.TartarusArenas
import dev.henny.hha.world.TartarusKind
import dev.henny.hha.world.TartarusPiece
import dev.henny.hha.world.TartarusStructure
import dev.henny.hha.world.olymp.OlympusLayout
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.entity.effect.StatusEffectInstance
import net.minecraft.entity.effect.StatusEffects
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World

/**
 * Rast-Punkte, Tod und Sturz im RPG (PLAN 3.2). Alles nur in RPG-Welten (`RpgWorld.enabled`) und erst, wenn der
 * Spieler angekommen ist (`started`).
 *
 * - **Rast** ([rest]): erzwungener Wiedereinstiegspunkt dorthin, volle Heilung, goldene Äpfel auf [APPLES],
 *   `RpgEventPayload(REST)`. Ausgelöst bei der Ankunft (an der Marke), beim ersten Betreten einer Insel bzw. des
 *   Tartaros (einmal je Ort und Spieler, [onPlace]) und nach jedem Sieg ([onVictory]).
 * - **Tod:** vorher `RpgEventPayload(FALLEN)`, `deaths` zählt; nach dem Wiedereinstieg an die Rast (Vanilla tut es über
 *   den Punkt; hier abgesichert, falls er versperrt war) und [RESPAWN_RESIST_TICKS] Ticks Resistenz.
 * - **Sturz:** Wer im Olymp unter [FALL_Y] fällt (weit über `OlympusLayout.FALL_Y`, damit die Sturz-Regel in die
 *   Oberwelt nie auslöst) und in keiner Arena kämpft, kommt mit kurzer Blende an die Rast, [FALL_DAMAGE] Schaden, nie
 *   tödlich.
 *
 * `RpgEventPayload`-Inhalt: `REST` → numbers = (x, y, z der Rast), texts = (Rast-Schlüssel);
 * `FALLEN` → numbers = (Tode der Welt), texts = (Rast-Schlüssel, zu der es zurückgeht).
 */
object RpgRests {

    /** Goldene Äpfel nach einer Rast. */
    const val APPLES = 6

    /** Resistenz nach dem Wiedereinstieg (Ticks; Stufe [RESPAWN_RESIST_LEVEL] + 1). */
    const val RESPAWN_RESIST_TICKS = 60
    const val RESPAWN_RESIST_LEVEL = 4

    /** Sturzgrenze des RPG im Olymp. Inseln liegen ab y 120; die Sturz-Regel in die Oberwelt greift erst bei 8. */
    const val FALL_Y = 64

    /** Schaden beim Sturz (nie tödlich: höchstens bis 1 Leben). */
    const val FALL_DAMAGE = 4f

    /** Blende beim Sturz (aus Schwarz auf). */
    const val FALL_FADE_TICKS = 30

    /** Wohin ein Wiedereinstieg mindestens nah sein muss, sonst wird nachgesetzt. */
    private const val RESPAWN_TOLERANCE = 3.0

    /** Orte mit eigener Rast beim ersten Betreten (je Spieler). */
    private val PLACE_RESTS = setOf("isle.b", "isle.c", "isle.d", "isle.e", "isle.f", "tartaros")

    fun init() {
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, _ ->
            val player = entity as? ServerPlayerEntity ?: return@register
            val server = player.entityWorld.server ?: return@register
            val state = RpgWorld.of(server)
            if (!state.enabled || !state.hasStarted(player.uuid)) return@register
            state.addDeath()
            RpgServer.event(listOf(player), RpgEventPayload.FALLEN, listOf(state.deaths.toDouble()), listOf(state.restOf(player.uuid)?.key ?: ""))
            RpgServer.markAllDirty()
        }
        ServerPlayerEvents.AFTER_RESPAWN.register { _, player, alive ->
            if (alive) return@register
            val server = player.entityWorld.server ?: return@register
            val state = RpgWorld.of(server)
            if (!state.enabled || !state.hasStarted(player.uuid)) return@register
            val rest = restOf(player)
            val world = rest?.let { server.getWorld(it.world) }
            if (rest == null || world == null) {
                // Ältere RPG-Welt ohne Rast: wie bisher nur aus der Oberwelt zurück an den Anfang des Wegs.
                if (player.entityWorld.registryKey == World.OVERWORLD) RpgServer.toPathStart(player)
            } else if (player.entityWorld !== world || player.entityPos.distanceTo(rest.pos) > RESPAWN_TOLERANCE) {
                RpgServer.teleportSafe(player, world, rest.pos, rest.yaw)
            }
            player.addStatusEffect(StatusEffectInstance(StatusEffects.RESISTANCE, RESPAWN_RESIST_TICKS, RESPAWN_RESIST_LEVEL, false, false, true))
            RpgServer.markDirty(player)
        }
        ServerTickEvents.END_SERVER_TICK.register(::tickFalls)
    }

    // ---------------------------------------------------------------------------------------------
    // Rast
    // ---------------------------------------------------------------------------------------------

    /** Rast für [player] mit Sprachschlüssel [key] bei [at] in [world] (volle Wirkung samt Meldung). */
    fun rest(player: ServerPlayerEntity, key: String, at: Vec3d, world: ServerWorld) {
        setRest(player, key, at, player.yaw, world, notify = true)
    }

    /**
     * Setzt die Rast. [notify] = volle Heilung, Äpfel und `REST`-Ereignis; ohne nur Punkt und Wiedereinstieg (z. B. vor
     * der Ankunftsszene; die Meldung folgt mit [restHere] am Szenenende).
     */
    fun setRest(player: ServerPlayerEntity, key: String, at: Vec3d, yaw: Float, world: ServerWorld, notify: Boolean) {
        val server = world.server ?: return
        val state = RpgWorld.of(server)
        if (!state.enabled) return
        state.setRest(player.uuid, RpgWorld.Rest(key, world.registryKey.value, at, yaw))
        RpgServer.forceSpawn(player, world, at, yaw)
        if (notify) restHere(player) else RpgServer.markDirty(player)
    }

    /** Rastet an der schon gesetzten Rast: volle Heilung, Äpfel auffüllen, `REST`-Ereignis. */
    fun restHere(player: ServerPlayerEntity) {
        val server = player.entityWorld.server ?: return
        val state = RpgWorld.of(server)
        if (!state.enabled) return
        refill(player)
        val rest = state.restOf(player.uuid)
        val pos = rest?.pos ?: player.entityPos
        RpgServer.event(listOf(player), RpgEventPayload.REST, listOf(pos.x, pos.y, pos.z), listOf(rest?.key ?: ""))
        RpgServer.markDirty(player)
    }

    /** Letzte Rast von [player] oder null. */
    fun restOf(player: ServerPlayerEntity): Arrival? {
        val server = player.entityWorld.server ?: return null
        val state = RpgWorld.of(server)
        if (!state.enabled) return null
        return state.restOf(player.uuid)?.toArrival()
    }

    /** Rast-Schlüssel der Ankunft des Wegs. */
    fun arrivalKey(path: String): String = when (path) {
        "heaven", "hell" -> "hha.rpg.rest.arrival.$path"
        else -> "hha.rpg.rest.arrival"
    }

    /** Volle Heilung, goldene Äpfel auf [APPLES]. */
    private fun refill(player: ServerPlayerEntity) {
        player.health = player.maxHealth
        player.extinguish()
        val have = player.inventory.count(Items.GOLDEN_APPLE)
        if (have < APPLES) player.inventory.offerOrDrop(ItemStack(Items.GOLDEN_APPLE, APPLES - have))
    }

    /** Erstes Betreten eines Orts durch [player] (von RpgStory): Orts-Rast einmal je Ort und Spieler. */
    fun onPlace(player: ServerPlayerEntity, place: String) {
        if (place !in PLACE_RESTS) return
        val server = player.entityWorld.server ?: return
        val state = RpgWorld.of(server)
        if (!state.enabled || !state.hasStarted(player.uuid)) return
        if (!state.markRested(player.uuid, place)) return
        val world = player.entityWorld as? ServerWorld ?: return
        val isle = isleOf(place)
        val at = if (isle != null && player.entityWorld.registryKey == OlympusLayout.WORLD) isle.entrance else ground(world, player.entityPos)
        rest(player, "hha.rpg.rest.$place", at, world)
    }

    /** Sieg über [boss] (von `BossRewards.onGrant`): Rast für alle Mitkämpfer. */
    fun onVictory(server: MinecraftServer, boss: String, players: List<ServerPlayerEntity>) {
        val state = RpgWorld.of(server)
        if (!state.enabled) return
        val isle = OlympusLayout.ISLES.firstOrNull { it.key == boss }
        for (player in players) {
            if (!state.hasStarted(player.uuid) || player.isRemoved) continue
            val world = player.entityWorld as? ServerWorld ?: continue
            if (isle != null && world.registryKey == OlympusLayout.WORLD) {
                rest(player, "hha.rpg.rest.isle.${isle.letter.lowercaseChar()}", isle.entrance, world)
            } else {
                val key = if (boss == "hades" || boss == "kronos") "hha.rpg.rest.palace" else "hha.rpg.rest.site.$boss"
                rest(player, key, ground(world, outsideArena(world, player.entityPos)), world)
            }
        }
    }

    /**
     * Rast nach einem Sieg im Tartaros nicht in der Arena (Überarbeitung 4, P23, Briefing 6): Steht [pos] in einem
     * Tartaros-Bauwerk, liegt die Rast am Eingang seines Zuwegs (`siteApproach(…).points.last()`, dort führt auch der
     * K. o. hin) bzw. beim Palast vor dem Siegel (`palaceEntrance`, außerhalb des Saals). Sonst [pos].
     */
    private fun outsideArena(world: ServerWorld, pos: Vec3d): Vec3d {
        if (world.registryKey != HadesGate.TARTAROS) return pos
        val start = world.structureAccessor.getStructureContaining(BlockPos.ofFloored(pos)) { it.value() is TartarusStructure }
        if (!start.hasChildren()) return pos
        val piece = start.children.firstOrNull() as? TartarusPiece ?: return pos
        if (piece.kind == TartarusKind.PALACE) return Vec3d.ofBottomCenter(TartarusArenas.palaceEntrance(piece.middle))
        return TartarusArenas.siteApproach(piece.kind, piece.siteVariant(), piece.middle).points.last().pos
    }

    private fun isleOf(place: String) = if (place.startsWith("isle.")) {
        OlympusLayout.ISLES.firstOrNull { it.letter.lowercaseChar() == place.last() }
    } else {
        null
    }

    // ---------------------------------------------------------------------------------------------
    // Sturz
    // ---------------------------------------------------------------------------------------------

    private fun tickFalls(server: MinecraftServer) {
        val olympus = server.getWorld(OlympusLayout.WORLD) ?: return
        if (olympus.players.isEmpty()) return
        val state = RpgWorld.of(server)
        if (!state.enabled) return
        for (player in olympus.players.toList()) {
            if (player.y >= FALL_Y || player.isSpectator || player.abilities.flying || !player.isAlive) continue
            if (!state.hasStarted(player.uuid) || RpgArrival.isProtected(player)) continue
            // In einer Arena wirft die Bö des Kampfes zurück (die greift weit oberhalb dieser Grenze).
            if (Arenas.of(player) != null) continue
            fallToRest(server, player)
        }
    }

    /** Kurze Blende, an die Rast (sonst Anfang des Wegs), [FALL_DAMAGE] Schaden, nie tödlich. */
    private fun fallToRest(server: MinecraftServer, player: ServerPlayerEntity) {
        val rest = restOf(player)
        val world = rest?.let { server.getWorld(it.world) }
        if (rest == null || world == null) RpgServer.toPathStart(player) else RpgServer.teleportSafe(player, world, rest.pos, rest.yaw)
        player.velocity = Vec3d.ZERO
        player.velocityDirty = true
        player.fallDistance = 0.0
        Cine.fade(listOf(player), 0x000000, 1.0, 0.0, FALL_FADE_TICKS)
        val amount = minOf(FALL_DAMAGE, player.health - 1f)
        val target = player.entityWorld as? ServerWorld
        if (amount > 0f && target != null) player.damage(target, target.damageSources.magic(), amount)
        RpgServer.markDirty(player)
    }

    // ---------------------------------------------------------------------------------------------
    // Hilfen
    // ---------------------------------------------------------------------------------------------

    /**
     * Fester Boden bei [pos]: Oberkante des ersten tragenden Blocks mit zwei freien Blöcken darüber, gesucht [up]
     * Blöcke hinauf bis [down] hinab. Ohne Treffer [pos] selbst.
     */
    fun ground(world: ServerWorld, pos: Vec3d, up: Int = 2, down: Int = 24): Vec3d {
        val base = BlockPos.ofFloored(pos)
        world.getChunk(base)
        for (dy in up downTo -down) {
            val feet = base.up(dy)
            val below = feet.down()
            val belowShape = world.getBlockState(below).getCollisionShape(world, below)
            if (belowShape.isEmpty) continue
            if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty) continue
            if (!world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty) continue
            return Vec3d(pos.x, below.y + belowShape.getMax(Direction.Axis.Y), pos.z)
        }
        return pos
    }
}
