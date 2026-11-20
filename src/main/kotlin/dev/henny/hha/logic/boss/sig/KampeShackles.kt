package dev.henny.hha.logic.boss.sig

import dev.henny.hha.HhaSounds
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.ArenaCollapse
import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.logic.boss.SubTarget
import dev.henny.hha.logic.boss.SubTargetGroup
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha.logic.boss.subTargetHandler
import dev.henny.hha.logic.boss.timings.KampeSigTimings
import dev.henny.hha.world.TartarusArenas
import dev.henny.hha.world.arena.ArenaSpec
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.util.math.Vec3d
import kotlin.math.hypot

/**
 * Einstiegspunkt der Signatur-Mechanik: Kampe: Die Kettenschlösser (PLAN 4.2). Gerüst P00 (Überarbeitung 4, PLAN 7.7), Besitzer P22.
 * [init] ruft `Hha.onInitialize` genau einmal auf (Ereignisse anmelden, Geräte-Handler, Renderer-Arten nur am Client).
 *
 * **Mechanik** (PLAN 4.2, Briefing P22): Vier Kettenschlösser (Teilziele mit Wesen `kampe.shackle`) stehen auf den Sockeln
 * des Grubenbodens (`TartarusArenas.prisonLock`, r 17 bei 45°/135°/225°/315°). Sie liegen ab Kampfbeginn gedämpft da:
 * Treffer prallen ab, keine Leiste. Hängt Kampe am Titanen (Sturzstoß-Folge ab 50 %), wird das **heile Schloss, das am
 * weitesten von ihr entfernt ist**, golden ([Shackles.openFarthest]); nur dessen Leiste erscheint (drei Kerben). Drei
 * volle Treffer sprengen es ([Shackles.takeBroken]): Der Kampf lässt den Titanen zucken, die Ketten `chain_i` und
 * `lock_chain_i` fallen ([Shackles.dropChains], über `ArenaCollapse` und damit über `arena.edits`), Kampe stürzt und
 * liegt 80 offen. Jedes Schloss einmal je Kampf. Endet die Folge ohne Bruch, wird das Schloss wieder gedämpft
 * ([Shackles.shut]).
 *
 * Die Teilziele **handeln nicht**; die Ketten sind Blöcke, kein Wesen. Ohne Kerker-Arena (Befehl abseits eines Kerkers)
 * liefert [create] `null`, und der Kampf läuft wie vor der Überarbeitung.
 *
 * Je Schloss eine eigene [SubTargetGroup]: Eine Gruppe sendet immer alle ihre Leisten, die Mechanik will aber nur die des
 * offenen Schlosses zeigen. Die Zuschauer einer Gruppe sind darum die Mitkämpfer nur, solange ihre Leiste sichtbar sein
 * soll (offen, oder kurz nach dem Bruch); sonst niemand (die Gruppe blendet dann bei den bisherigen Zuschauern aus).
 */
object KampeShackles {

    fun init() {}

    /** Nur im Titanenkerker gibt es Schlösser. */
    const val ARENA_ID = TartarusArenas.PRISON_ID

    /** Darstellung des Wesens (P04: großes Bronzeschloss, offen golden). */
    const val KIND = "kampe.shackle"

    /** Name der Unterleiste (kurz, passt ins Etikett). */
    const val NAME_KEY = "hha.kampe.sub.shackle"

    /** Einmaliger Hinweis je Kampf, wenn das erste Schloss golden wird. */
    const val HINT_KEY = "hha.kampe.hint.shackle"

    /** Zeile beim Bruch („Die Ketten!“). */
    const val LINE_KEY = "hha.kampe.line.chains"

    /** Effekte (Client: `KampeFx`). */
    const val FX_GLOW = "kampe.shackle_glow"
    const val FX_JOLT = "kampe.titan_jolt"

    /** Teile des Vertrags je Schloss [i] (beide fallen beim Bruch, Hinweis Welle 2). */
    fun chainPart(i: Int) = "chain_$i"
    fun lockChainPart(i: Int) = "lock_chain_$i"

    /**
     * Schlösser für einen Kampf in [arena], oder `null` ohne Kerker-Vertrag. [broken] = in diesem Kampf schon gesprengte
     * Schlösser (nach einem Neustart über die Leine bleiben sie gesprengt, die Ketten fehlen ja weiter); die Menge wird
     * beim Bruch fortgeschrieben. [fighters] = Mitkämpfer (Zuschauer der Leisten, Skalierung beim Anlegen),
     * [theme] = Thema der ruhenden Leiste (`10 + Vfx.Style.ordinal`, wie `Cine.phaseBar`).
     */
    fun create(
        world: ServerWorld, arena: Arenas.Arena, fighters: () -> List<ServerPlayerEntity>, broken: MutableSet<Int>, theme: Int,
    ): Shackles? {
        val spec = arena.spec ?: return null
        if (spec.id != ARENA_ID || !arena.open) return null
        return Shackles(world, arena, spec, fighters, broken, theme)
    }

    /** Die Schlösser eines Kampfs. Lebt von [create] bis [close]; jeder Ausgang des Kampfs ruft [close]. */
    class Shackles internal constructor(
        val world: ServerWorld,
        val arena: Arenas.Arena,
        val spec: ArenaSpec,
        private val fighters: () -> List<ServerPlayerEntity>,
        private val broken: MutableSet<Int>,
        theme: Int,
    ) {
        /** Bauwerksmitte `o` (der Kerker-Vertrag rechnet alle Punkte relativ dazu). */
        val origin = spec.center

        private val slots: List<Slot> = TartarusArenas.PRISON_LOCK_ANGLES.indices.filter { it !in broken }.map { Slot(it, theme) }

        /** Gebrochen, aber vom Kampf noch nicht abgeholt ([takeBroken]). */
        private var pending: Int? = null

        /** Das gerade goldene Schloss, sonst `null`. */
        var openIndex: Int? = null
            private set

        var closed = false
            private set

        /** Ein Schloss: Teilziel, Wesen, Gruppe; Leiste sichtbar bis [showUntil] (Welt-Tick). */
        private inner class Slot(val index: Int, theme: Int) {
            val at: Vec3d = TartarusArenas.prisonLock(origin, index)
            var showUntil = 0L

            /** Nur während [SubTargetGroup.add]: Zuschauer = Mitkämpfer, damit das Leben nach Spielerzahl skaliert. */
            private var scaling = true

            val group = SubTargetGroup(
                world,
                { if (scaling || world.time < showUntil) fighters() else emptyList() },
                subTargetHandler(onBroken = { onBroken(index) }),
            )
            val target: SubTarget = group.add(
                "lock_$index", NAME_KEY, KampeSigTimings.SHACKLE_HEALTH, KampeSigTimings.SHACKLE_CAP, KampeSigTimings.SHACKLE_NOTCHES,
            ).also {
                scaling = false
                it.anchor = at.add(0.0, 0.8, 0.0)
            }

            init {
                group.theme = theme
                group.spawn(target, at, KIND, KampeSigTimings.LOCK_SIZE)
            }

            val visible: Boolean get() = world.time < showUntil
        }

        /** Heile Schlösser (Index). */
        fun intact(): List<Int> = slots.filter { !it.target.broken }.map { it.index }

        /** Fußpunkt des Schlosses [i] (Mitte des Sockels, Grubenboden). */
        fun lockPos(i: Int): Vec3d = TartarusArenas.prisonLock(origin, i)

        /** Wo die Kette des Schlosses [i] am Titanen hängt (Handgelenk bzw. Schulter, Blockmitte). */
        fun chainTarget(i: Int): Vec3d = Vec3d.ofCenter(TartarusArenas.prisonLockChainTarget(origin, i))

        /** Liegt [pos] (waagerecht) näher als [clearance] an einem heilen Schloss? Für Kampes Landepunkte. */
        fun nearLock(pos: Vec3d, clearance: Double): Boolean =
            slots.any { !it.target.broken && hypot(it.at.x - pos.x, it.at.z - pos.z) < clearance }

        /**
         * Macht das heile Schloss golden, das waagerecht am weitesten von [from] (Kampes Platz am Titanen) liegt, und zeigt
         * seine Leiste. Liefert den Index oder `null` (keins mehr heil, schon eins offen, geschlossen).
         */
        fun openFarthest(from: Vec3d): Int? {
            if (closed || openIndex != null) return null
            val slot = slots.filter { !it.target.broken }.maxByOrNull { hypot(it.at.x - from.x, it.at.z - from.z) } ?: return null
            slot.showUntil = Long.MAX_VALUE
            slot.target.open = true
            openIndex = slot.index
            val entity = slot.group.entityOf(slot.target)
            // Ruhiger goldener Schein am Schloss und entlang seiner Kette zum Titanen; endet am Client, sobald das Wesen
            // nicht mehr offen ist (Zustand des Wesens), spätestens nach GLOW_MAX_LIFE.
            BossFx.play(world, FX_GLOW, Vfx.Style.GOLD, slot.at, dir = chainTarget(slot.index), life = KampeSigTimings.GLOW_MAX_LIFE, entity = entity)
            sound(HhaSounds.CHAIN, slot.at, 2.2f, 0.7f)
            return slot.index
        }

        /** Das offene Schloss wird wieder gedämpft und seine Leiste blendet aus (Folge ohne Bruch vorbei, Abbruch). */
        fun shut() {
            val i = openIndex ?: return
            openIndex = null
            val slot = slots.firstOrNull { it.index == i } ?: return
            slot.target.open = false
            if (!slot.target.broken) slot.showUntil = 0L
        }

        /** Das zuletzt gesprengte Schloss, einmal (danach `null`). Der Kampf fragt jeden Tick. */
        fun takeBroken(): Int? = pending.also { pending = null }

        /** Aus dem Trefferweg der Gruppe (Spielerschlag): nur merken, der Kampf handelt in seinem nächsten Tick. */
        private fun onBroken(index: Int) {
            broken.add(index)
            pending = index
            if (openIndex == index) openIndex = null
            slots.firstOrNull { it.index == index }?.let { it.showUntil = world.time + KampeSigTimings.BROKEN_BAR_TICKS }
        }

        /**
         * Die Ketten des Schlosses [i] fallen (Teil `chain_i` und `lock_chain_i`, oben zuerst, über `ArenaCollapse` und
         * damit über `arena.edits`; `Arenas.close` stellt sie zum Kampfende wieder her). Beide Teile liegen nicht im
         * Boden (`lock_chain_i` ab Fußhöhe, `chain_i` ab o.y + 2): Ihr Verlust nimmt keine Fläche weg.
         */
        fun dropChains(i: Int) {
            if (!arena.open) return
            sound(HhaSounds.CHAIN, chainTarget(i), 3f, 0.55f)
            for (id in listOf(lockChainPart(i), chainPart(i))) {
                val part = spec.parts.firstOrNull { it.id == id } ?: continue
                if (part in arena.lost) continue
                ArenaCollapse.start(arena, part, KampeSigTimings.CHAIN_FALL_PER_TICK)
            }
        }

        /** Jeden Kampf-Tick: Wesen halten, Leisten senden. Erst die verborgenen Gruppen, dann die sichtbare (sie sendet zuletzt). */
        fun tick() {
            if (closed) return
            for (slot in slots.sortedBy { it.visible }) slot.group.tick()
        }

        /** Räumt Wesen und Leisten weg (alle Ausgänge). Mehrfach harmlos. */
        fun close() {
            if (closed) return
            closed = true
            openIndex = null
            pending = null
            for (slot in slots) slot.group.close()
        }

        private fun sound(event: net.minecraft.sound.SoundEvent, at: Vec3d, volume: Float, pitch: Float) {
            Telegraph.current?.sound(event, at, volume, pitch)
                ?: world.playSound(null, at.x, at.y, at.z, event, SoundCategory.HOSTILE, volume, pitch)
        }
    }
}
