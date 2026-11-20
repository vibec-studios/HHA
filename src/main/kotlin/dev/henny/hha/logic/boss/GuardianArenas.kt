package dev.henny.hha.logic.boss

import dev.henny.hha.Hha
import dev.henny.hha.entity.miniboss.TartarosGuardian
import dev.henny.hha.world.arena.ArenaSpec
import net.minecraft.registry.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import java.util.IdentityHashMap

/**
 * Arenen der Tartaros-Wächter (Überarbeitung 4, PLAN 7.4 und 4.3; Besitzer P03). Die Arena hängt **außen** am Wesen
 * (`MiniBosses.kt` bleibt unberührt); [TartarosGuardian] erreicht sie über [of].
 *
 * Solange der Wächter steht, gilt (Regeln [Arenas.Rules.GUARDIAN]):
 * - **Blockschutz** im `spec.box` (über [Arenas.protects], also auch für Explosionen über `ExplosionImplMixin`).
 * - **K. o. statt Tod** an [open]s `post` (letzter Wegpunkt des Zuwegs vor dem Bauwerk), Text
 *   `hha.guardian.arena.knocked_out`. Teilnehmer sind alle Mitkämpfer des Wächters (`fighters()`, Kampfradius 48) und
 *   wer die Fläche betritt.
 * - **Fallschaden bleibt** (Kerker und Hügel behalten ihre Höhe), **keine Bö**.
 * - Edit-Register und Reparatur des Arena-Kerns aus dem Bauplan beim Öffnen ([Arenas.attach]).
 *
 * Verschwindet der Wächter (Sieg, `LONELY`, Entladen, `boss stop`), schließt [tick] die Arena im nächsten Server-Tick
 * und stellt ihre Blöcke wieder her. Mehrere Wächter derselben Art (zwei Festen) dürfen gleichzeitig kämpfen.
 */
object GuardianArenas {

    private val byGuardian = IdentityHashMap<TartarosGuardian, Arenas.Arena>()

    /** Besitzer der Wächter-Arena: Den Kampf führt der Wächter selbst, die Arena meldet nur. */
    private class GuardianOwner(val guardian: TartarosGuardian) : Arenas.Owner {
        override val bossKey: String = Registries.ENTITY_TYPE.getId(guardian.type).path

        /** Leere Arena: Der Wächter regelt das selbst (Leine, `LONELY`). */
        override fun onArenaEmpty() {}

        /** `/hha boss stop`: Der Wächter verschwindet wie bei `LONELY` (er erscheint beim nächsten Besuch neu). */
        override fun stopFight() {
            if (!guardian.isRemoved) guardian.discard()
        }

        override fun skipPhase() {}
    }

    /**
     * Öffnet die Arena für [guardian] aus [spec] (Bauplan [blueprintId] am Ursprung [origin]); K. o. führt nach [post].
     * Hat der Wächter schon eine offene Arena, kommt diese zurück.
     */
    fun open(guardian: TartarosGuardian, spec: ArenaSpec, blueprintId: String, origin: BlockPos, post: Vec3d): Arenas.Arena {
        byGuardian[guardian]?.takeIf { it.open }?.let { return it }
        val world = guardian.entityWorld as ServerWorld
        val arena = Arenas.Arena(
            world, spec.centerVec, spec.radius.toDouble(), spec.coreY.toDouble(), post, GuardianOwner(guardian), spec,
            Arenas.Rules.GUARDIAN, blueprintId, origin,
        )
        Arenas.attach(arena)
        byGuardian[guardian] = arena
        Hha.LOGGER.info("Wächter-Arena {} bei {} offen (K. o. nach {})", spec.id, origin, post)
        return arena
    }

    /** Offene Arena des Wächters, sonst `null`. */
    fun of(guardian: TartarosGuardian): Arenas.Arena? = byGuardian[guardian]?.takeIf { it.open }

    /** Schließt die Arena von [guardian] sofort (samt Wiederherstellung). Mehrfach aufrufen schadet nicht. */
    fun close(guardian: TartarosGuardian) {
        val arena = byGuardian.remove(guardian) ?: return
        Arenas.close(arena)
    }

    /** Alle offenen Wächter-Arenen (für Befehle und Prüfung). */
    fun all(): List<Arenas.Arena> = byGuardian.values.filter { it.open }

    /** Aus [Arenas.init] jeden Server-Tick: Mitkämpfer aufnehmen, verschwundene Wächter abmelden. */
    internal fun tick(server: MinecraftServer) {
        if (byGuardian.isEmpty()) return
        val iterator = byGuardian.entries.iterator()
        while (iterator.hasNext()) {
            val (guardian, arena) = iterator.next()
            if (arena.world.server !== server) continue
            if (guardian.isRemoved || !guardian.isAlive || !arena.open) {
                iterator.remove()
                Arenas.close(arena)
                continue
            }
            for (player in guardian.fighters()) arena.join(player)
        }
    }
}
