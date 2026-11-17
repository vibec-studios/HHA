package dev.henny.hha.client.vfx.fx

import dev.henny.hha.client.HhaClientConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType

/**
 * Dauer-Effekte je lebendem Wesen eines Typs (PLAN 6.4, Budget 2.2: einer je Wesen, vier insgesamt). Der Effekt leitet
 * sein Bild aus dem getrackten Zustand des Wesens ab, damit Nachzügler ihn sofort sehen.
 *
 * Je Tick: alle Wesen der registrierten Typen im Umkreis [RANGE] um die Kamera suchen, die nächsten [MAX_TOTAL]
 * behalten, je Wesen genau einen Effekt halten, Effekte von Wesen entfernen, die weg sind (entfernt, tot oder weiter
 * als [RANGE] + [RANGE_SLACK]). Die Effekte laufen nicht im Deckel `VfxManager.MAX_EFFECTS`, werden aber in denselben
 * drei Phasen gezeichnet. Endet ein Effekt von selbst (Lebenszeit), wird er nach [RESPAWN_TICKS] neu erzeugt, solange
 * das Wesen da ist – Dauer-Effekte sollten also eine lange Lebenszeit haben und sich aus dem Wesen ableiten.
 */
object FxAttach {

    const val RANGE = 96.0
    const val RANGE_SLACK = 8.0
    const val MAX_TOTAL = 4
    const val RESPAWN_TICKS = 20

    private class Slot(val entity: Entity, var effect: FxEffect?, var wait: Int = 0)

    private val factories = LinkedHashMap<EntityType<*>, (Entity) -> FxEffect>()
    private val slots = LinkedHashMap<Int, Slot>()
    private val live = ArrayList<FxEffect>()

    /** Ein Dauer-Effekt für jedes Wesen vom Typ [type] in der Nähe. Doppelte Registrierung: die erste gewinnt. */
    fun register(type: EntityType<*>, factory: (Entity) -> FxEffect) {
        factories.putIfAbsent(type, factory)
    }

    /** Die laufenden Dauer-Effekte (für die Zeichenphasen im `VfxManager`). */
    fun effects(): List<FxEffect> = live

    /** Je Client-Tick (aus `FxClient.tick`): Wesen suchen, Effekte halten, ticken und entfernen. */
    fun tick(world: ClientWorld) {
        if (factories.isEmpty()) return
        if (!HhaClientConfig.enabled("vfx_3d")) {
            clear()
            return
        }
        val cam = MinecraftClient.getInstance().gameRenderer?.camera?.cameraPos ?: return
        // 1. Weg ist weg
        val keepRange = (RANGE + RANGE_SLACK) * (RANGE + RANGE_SLACK)
        slots.values.removeIf { s ->
            s.entity.isRemoved || !s.entity.isAlive || s.entity.entityWorld !== world || s.entity.squaredDistanceTo(cam) > keepRange
        }
        // 2. Nächste Wesen aufnehmen, bis MAX_TOTAL
        if (slots.size < MAX_TOTAL) {
            val range = RANGE * RANGE
            val found = ArrayList<Entity>()
            for (e in world.entities) {
                if (e.type in factories && e.isAlive && e.id !in slots && e.squaredDistanceTo(cam) <= range) found.add(e)
            }
            found.sortBy { it.squaredDistanceTo(cam) }
            for (e in found) {
                if (slots.size >= MAX_TOTAL) break
                slots[e.id] = Slot(e, null)
            }
        }
        // 3. Effekte halten und ticken
        for (s in slots.values) {
            val effect = s.effect
            if (effect == null) {
                if (s.wait > 0) {
                    s.wait--
                    continue
                }
                val factory = factories[s.entity.type] ?: continue
                s.effect = FxClient.guarded("attach:${EntityType.getId(s.entity.type)}") { factory(s.entity) }
                if (s.effect == null) s.wait = RESPAWN_TICKS
                continue
            }
            val alive = FxClient.guarded("attach.tick:${effect.javaClass.simpleName}") { effect.advance(world) } ?: false
            if (!alive) {
                s.effect = null
                s.wait = RESPAWN_TICKS
            }
        }
        live.clear()
        for (s in slots.values) s.effect?.let { live.add(it) }
    }

    /** Verbindungsende, `VfxManager.clear` (z. B. `Cine.OP_CLEAR_VFX`): alle Dauer-Effekte vergessen. */
    fun clear() {
        slots.clear()
        live.clear()
    }
}
