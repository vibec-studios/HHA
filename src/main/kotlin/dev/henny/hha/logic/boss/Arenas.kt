package dev.henny.hha.logic.boss

import dev.henny.hha.logic.Fx
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.effect.StatusEffectCategory
import net.minecraft.network.packet.s2c.play.PositionFlag
import net.minecraft.registry.tag.DamageTypeTags
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.hypot

/**
 * Kampfflächen der neuen Bosse (Überarbeitung 2, PLAN 4.4). Eine Arena ist Welt, Mitte, Radius, Bodenhöhe, Eingang und
 * Besitzer (der laufende Kampf). Solange sie offen ist, gilt:
 *
 * - **Blockschutz:** [HermesFights.isProtected] fragt [protects]; Blöcke in der Arena lassen sich nicht abbauen oder
 *   setzen (der Kampf selbst darf Blöcke tauschen, z. B. Zeus' Pfeiler).
 * - **Kein Fallschaden** für Teilnehmer.
 * - **Bö am Rand:** Wer unter die Fläche fällt, wird zurück auf die Fläche geworfen ([GUST_DAMAGE] Schaden).
 * - **K. o. statt Tod:** Ein tödlicher Treffer setzt den Spieler mit [KO_HEALTH] Leben an den Eingang; das Inventar
 *   bleibt. Er scheidet aus, bis er die Fläche wieder betritt.
 * - **Ende bei leerer Arena:** Ist kein Teilnehmer mehr da, ruft die Arena [Owner.onArenaEmpty] (der Boss verschwindet,
 *   der Altar ist wieder scharf).
 *
 * Teilnehmer ist, wer während des Kampfes auf der Fläche stand (waagerecht ≤ Radius, höchstens [JOIN_HEIGHT] über dem
 * Boden), lebt, kein Zuschauer ist und sich nicht weiter als [LEAVE_MARGIN] über den Rand hinaus entfernt.
 */
object Arenas {

    /** Wer eine Arena führt (ein laufender Kampf). */
    interface Owner {
        /** Schlüssel des Bosses (`hekate`, `brontes`, `kratos`, `zeus` …), wie in [dev.henny.hha.logic.world.OlympusProgress]. */
        val bossKey: String

        /** Alle Teilnehmer sind weg oder k. o.: Kampf ohne Sieg beenden. */
        fun onArenaEmpty()

        /** Von außen beenden (Befehl `boss stop`). */
        fun stopFight()

        /** Für Tests: zur nächsten Phase springen (Befehl `boss skip`). */
        fun skipPhase()
    }

    /** Schaden der Bö, die Gefallene zurückwirft. */
    const val GUST_DAMAGE = 4f

    /** Leben nach einem K. o. */
    const val KO_HEALTH = 6f

    /** So weit unter dem Boden greift die Bö. */
    const val GUST_DEPTH = 3.0

    /** Wer weiter als so viele Blöcke über den Rand hinaus ist, scheidet aus. */
    const val LEAVE_MARGIN = 16.0

    /** Wer höher als so viel über dem Boden schwebt, tritt nicht neu bei (fliegt nur vorbei). */
    const val JOIN_HEIGHT = 12.0

    /** Blockschutz: über den Rand hinaus … */
    const val PROTECT_MARGIN = 4

    /** … von so tief unter … */
    const val PROTECT_BELOW = 8

    /** … bis so hoch über dem Boden. */
    const val PROTECT_ABOVE = 40

    class Arena(
        val world: ServerWorld,
        /** Mitte der Kampffläche (y = Bodenhöhe). */
        val center: Vec3d,
        val radius: Double,
        /** Höhe der Füße auf der Fläche (Oberkante des Bodens). */
        val floorY: Double,
        /** Wohin ein K. o. führt (außerhalb der Fläche, am Inseleingang). */
        val entrance: Vec3d,
        val owner: Owner,
    ) {
        private val members = LinkedHashSet<UUID>()
        private val gustCooldown = HashMap<UUID, Int>()

        /** Ist die Arena noch offen? */
        var open = true
            internal set

        /** Waagerechter Abstand zur Mitte. */
        fun flat(pos: Vec3d): Double = hypot(pos.x - center.x, pos.z - center.z)

        /** Steht [pos] auf der Fläche (waagerecht im Radius, nicht weit darüber oder darunter)? */
        fun onFloor(pos: Vec3d): Boolean = flat(pos) <= radius && pos.y > floorY - GUST_DEPTH && pos.y < floorY + JOIN_HEIGHT

        /** Gehört [pos] noch zum Kampf (Fläche plus Rand, beliebig tief darunter)? */
        fun near(pos: Vec3d): Boolean = flat(pos) <= radius + LEAVE_MARGIN && pos.y < floorY + 64.0

        /** Schützt die Arena diesen Block? */
        fun protects(pos: BlockPos): Boolean {
            if (!open) return false
            val y = pos.y
            if (y < floorY - PROTECT_BELOW || y > floorY + PROTECT_ABOVE) return false
            return hypot(pos.x + 0.5 - center.x, pos.z + 0.5 - center.z) <= radius + PROTECT_MARGIN
        }

        /** Die Teilnehmer (lebend, in dieser Welt, keine Zuschauer). Kreativ-Spieler zählen, kämpfen aber nicht mit. */
        fun players(): List<ServerPlayerEntity> = members.mapNotNull { world.getPlayerByUuid(it) as? ServerPlayerEntity }
            .filter { it.isAlive && !it.isRemoved && !it.isSpectator && it.entityWorld === world }

        /** Mitkämpfer im Sinn von [BossScaling.crowd]: Teilnehmer ohne Kreativ-Spieler. */
        fun fighters(): List<ServerPlayerEntity> = players().filter { !it.isCreative }

        fun isMember(player: ServerPlayerEntity): Boolean = player.uuid in members

        /** Nimmt [player] auf (z. B. wer den Altar benutzt hat). */
        fun join(player: ServerPlayerEntity) {
            if (player.entityWorld === world && !player.isSpectator) members.add(player.uuid)
        }

        fun leave(player: ServerPlayerEntity) {
            members.remove(player.uuid)
            gustCooldown.remove(player.uuid)
        }

        internal fun tick() {
            for (player in world.players) {
                if (player.isSpectator || !player.isAlive) continue
                if (player.uuid !in members && onFloor(player.entityPos)) members.add(player.uuid)
            }
            val iterator = members.iterator()
            while (iterator.hasNext()) {
                val id = iterator.next()
                val player = world.getPlayerByUuid(id) as? ServerPlayerEntity
                if (player == null || player.isRemoved || player.isSpectator || player.entityWorld !== world || !near(player.entityPos)) {
                    iterator.remove()
                    gustCooldown.remove(id)
                }
            }
            gustCooldown.replaceAll { _, left -> left - 1 }
            gustCooldown.values.removeIf { it <= 0 }
            for (player in players()) {
                if (player.y < floorY - GUST_DEPTH) gust(player)
            }
            if (members.isEmpty()) owner.onArenaEmpty()
        }

        /** Die Bö: zurück auf die Fläche am nächsten Rand, kleiner Schaden, keine Fallhöhe. */
        private fun gust(player: ServerPlayerEntity) {
            if (gustCooldown.containsKey(player.uuid)) return
            gustCooldown[player.uuid] = 20
            val away = Vec3d(player.x - center.x, 0.0, player.z - center.z)
            val dir = if (away.lengthSquared() < 1e-4) Vec3d(0.0, 0.0, 1.0) else away.normalize()
            val spot = center.add(dir.multiply((radius - 3.0).coerceAtLeast(0.0))).let { Vec3d(it.x, floorY + 0.2, it.z) }
            player.fallDistance = 0.0
            player.teleport(world, spot.x, spot.y, spot.z, emptySet<PositionFlag>(), player.yaw, player.pitch, false)
            Fx.launchPlayer(player, dir.multiply(-0.4).add(0.0, 0.5, 0.0))
            player.damage(world, world.damageSources.magic(), GUST_DAMAGE)
            player.sendMessage(Text.translatable("hha.boss.arena.gust").formatted(Formatting.GOLD), true)
        }

        /** K. o.: Leben auf [KO_HEALTH], an den Eingang, scheidet aus. */
        internal fun knockOut(player: ServerPlayerEntity) {
            // Der tödliche Angriff läuft nach diesem Aufruf weiter (Rückstoß, Konter): Seine Folgen sollen den
            // Eingang nicht erreichen, wie bei Hermes und Hades (Review R1 M3).
            Fx.shield(player)
            player.health = KO_HEALTH
            player.extinguish()
            // Schädliche Dauer-Effekte (Wither, Gift …) dürfen ihn draußen nicht doch noch töten.
            for (effect in player.statusEffects.map { it.effectType }.filter { it.value().category == StatusEffectCategory.HARMFUL }) {
                player.removeStatusEffect(effect)
            }
            player.fallDistance = 0.0
            player.teleport(world, entrance.x, entrance.y, entrance.z, emptySet<PositionFlag>(), player.yaw, player.pitch, false)
            player.velocity = Vec3d.ZERO
            player.velocityDirty = true
            leave(player)
            player.sendMessage(Text.translatable("hha.boss.arena.knocked_out").formatted(Formatting.GOLD), false)
        }
    }

    private val arenas = ArrayList<Arena>()

    /** Öffnet [arena] (der Kampf beginnt). Eine zweite Arena für denselben Boss wird abgelehnt (`false`). */
    fun open(arena: Arena): Boolean {
        if (arenas.any { it.open && it.owner.bossKey == arena.owner.bossKey && it.world === arena.world }) return false
        arena.open = true
        arenas.add(arena)
        return true
    }

    /** Schließt [arena] (Sieg, Abbruch, leere Arena). Mehrfaches Schließen schadet nicht. */
    fun close(arena: Arena) {
        arena.open = false
        arenas.remove(arena)
    }

    /** Alle offenen Arenen. */
    fun all(): List<Arena> = arenas.toList()

    /** Offene Arena, auf deren Fläche oder an deren Rand [pos] liegt (in [world]). */
    fun of(world: ServerWorld, pos: Vec3d): Arena? = arenas.firstOrNull { it.open && it.world === world && it.near(pos) }

    /** Arena, an der [player] teilnimmt (oder `null`). P03 nutzt das für die Sturz-Regel des Olymp. */
    fun of(player: ServerPlayerEntity): Arena? = arenas.firstOrNull { it.open && it.isMember(player) }

    /** Laufende Arena des Bosses [bossKey] (z. B. für `/hha boss stop`). */
    fun byBoss(server: MinecraftServer, bossKey: String): Arena? =
        arenas.firstOrNull { it.open && it.owner.bossKey == bossKey && it.world.server === server }

    /** Schützt irgendeine offene Arena diesen Block? */
    fun protects(world: ServerWorld, pos: BlockPos): Boolean = arenas.any { it.world === world && it.protects(pos) }

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register { _ ->
            for (arena in arenas.toList()) if (arena.open) arena.tick()
        }
        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, _, _ ->
            val player = entity as? ServerPlayerEntity ?: return@register true
            val arena = of(player) ?: return@register true
            arena.knockOut(player)
            false
        }
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source: DamageSource, _ ->
            val player = entity as? ServerPlayerEntity ?: return@register true
            !(source.isIn(DamageTypeTags.IS_FALL) && of(player) != null)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            for (arena in arenas.toList()) arena.owner.stopFight()
            arenas.clear()
        }
    }
}
