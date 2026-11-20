package dev.henny.hha.logic.boss.sig

import dev.henny.hha.HhaDecoBlocks
import dev.henny.hha.HhaSounds
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.SubTarget
import dev.henny.hha.logic.boss.SubTargetGroup
import dev.henny.hha.logic.boss.subTargetHandler
import dev.henny.hha.logic.boss.timings.BrontesTimings
import net.minecraft.block.AnvilBlock
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.enums.SlabType
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundCategory
import net.minecraft.state.property.Properties
import net.minecraft.util.math.BlockBox
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import kotlin.math.abs

/**
 * Signatur-Mechanik von Brontes (Überarbeitung 4, PLAN 4.2 „Hammer steckt, Amboss bricht“; Besitzer P16).
 *
 * **Hammer steckt** ([stuckAllowed]): Nach einem echten Fehlschlag steckt der Hammer [BrontesTimings.STUCK_F] Ticks im
 * Boden (offen, goldene Rune am Hammerkopf), sonst bleibt es bei „steht 28“ ohne Rune. Die vier Bedingungen sind eine
 * reine Funktion, damit sie sich aus dem Code prüfen lassen.
 *
 * **Amboss bricht** ([Anvil]): Teilziel **ohne** Wesen mit [BrontesTimings.ANVIL_CRACKS] Kerben. Nur Brontes' Hammer zählt:
 * Endet die Hammerlinie am Amboss und stand ein Spieler gedeckt dahinter, bekommt er einen Riss ([Anvil.crack]). Jeder
 * Riss tauscht die Ambossblöcke über `arena.edits` auf die nächste Stufe ([stageState]):
 *
 * | Risse | Bild | deckt? |
 * | --- | --- | --- |
 * | 0 | Plan (`IsleD.anvilBody`) | ja |
 * | 1 | Fugen der unteren Lage glühen (`ember_basalt` auf den vier Kantenmitten), Amboss oben angeschlagen | ja |
 * | 2 | zusätzlich Ecken der unteren Lage gesprungen, zwei Eckstufen oben abgeplatzt | ja |
 * | 3 | gebrochen: obere Lage weg, untere Lage nur noch Trümmer aus Stufen (½ hoch) | **nein** |
 *
 * Gebrochen deckt er nicht mehr, weil die Welt ihn nicht mehr hat: `Cover.sheltered` und die Hammerlinie (Strahl in
 * [BrontesTimings.COVER_HEIGHT]) laufen über die halben Blöcke hinweg. Phase II holt einen neuen ([Anvil.renew]: Plan über
 * `arena.edits.restore`, Leiste voll). Kampfende jeder Art stellt den Plan über `Arenas.close` wieder her.
 */
object BrontesAnvil {
    /** ID der Ambossdeckung im Vertrag (`OlympusArenas.D`). */
    const val ANVIL_ID = "anvil"

    /** Name der Unterleiste. */
    const val SUB_KEY = "hha.brontes.sub.anvil"

    /** Effekt-IDs (Client `BrontesFx`). */
    const val FX_CRACK = "brontes.anvil_crack"
    const val FX_BREAK = "brontes.anvil_break"

    /** Ton je Riss: `boss.anvil`, mit jedem Riss tiefer; beim Bruch noch tiefer. */
    const val CRACK_PITCH = 0.62f
    const val CRACK_PITCH_STEP = 0.08f
    const val BREAK_PITCH = 0.42f
    const val CRACK_VOLUME = 2.4f

    /** Darstellung: Lebenszeit der Effekte, Trümmer beim Bruch. */
    const val CRACK_FX_LIFE = 40
    const val BREAK_FX_LIFE = 56
    const val BREAK_DEBRIS_RADIUS = 2.2f
    const val BREAK_DEBRIS = 8
    const val CRACK_DEBRIS = 3

    fun init() {}

    // -----------------------------------------------------------------------------------------------------------------
    // Hammer steckt
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Darf der Hammer stecken (PLAN 4.2 (1), Briefing P16 Schritt 1)? Alle vier Bedingungen müssen gelten:
     * - (a) [struckAnyone] = false: Der Hammer traf niemanden.
     * - (b) [endedAtAnvil] = false und [hitIngot] = false: Die Linie endete nicht am Amboss und zerschlug keinen glühenden
     *   Rohling (das ist „Geblendet“).
     * - (c) [sideways] ≥ [BrontesTimings.STUCK_MISS_DISTANCE] und [distance] ≤ [BrontesTimings.STUCK_MAX_RANGE]: Das Ziel
     *   stand beim Treffer neben der Mittellinie und nah genug (`null` = Ziel weg → nein).
     * - (d) Das letzte Stecken ([lastStuck], Welt-Tick) liegt mindestens [BrontesTimings.STUCK_COOLDOWN] zurück.
     */
    fun stuckAllowed(
        struckAnyone: Boolean, endedAtAnvil: Boolean, hitIngot: Boolean, sideways: Double?, distance: Double?, now: Long, lastStuck: Long,
    ): Boolean {
        val t = BrontesTimings
        if (struckAnyone) return false
        if (endedAtAnvil || hitIngot) return false
        if (sideways == null || distance == null) return false
        if (sideways < t.STUCK_MISS_DISTANCE || distance > t.STUCK_MAX_RANGE) return false
        return now - lastStuck >= t.STUCK_COOLDOWN
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Amboss
    // -----------------------------------------------------------------------------------------------------------------

    /** Ergebnis eines Hammerschlags auf den Amboss. */
    enum class Crack { NONE, CRACKED, BROKEN }

    /**
     * Der Amboss eines Kampfes. [arena] liefert die laufende Arena (Vertrag und Edit-Register); ohne Vertrag (Test-Arena
     * außerhalb des Olymp) gibt es weder Leiste noch Risse. [viewers] sehen die Unterleiste.
     */
    class Anvil(private val world: ServerWorld, private val arena: () -> Arenas.Arena?, private val viewers: () -> Collection<ServerPlayerEntity>) {
        /** Risse seit dem letzten neuen Amboss (0 … [BrontesTimings.ANVIL_CRACKS]). */
        var cracks = 0
            private set

        val broken: Boolean get() = cracks >= BrontesTimings.ANVIL_CRACKS

        private var group: SubTargetGroup? = null
        private var target: SubTarget? = null
        private var closed = false

        /** Kasten der Ambossdeckung aus dem Vertrag, sonst `null`. */
        fun box(): BlockBox? = arena()?.spec?.cover?.firstOrNull { it.id == ANVIL_ID }?.box

        /** Mitte der Ambossoberkante (Ort für Klang, Effekte, Rune). */
        fun top(): Vec3d? = box()?.let { Vec3d((it.minX + it.maxX + 1) / 2.0, it.maxY + 1.0, (it.minZ + it.maxZ + 1) / 2.0) }

        /** Jeden Kampf-Tick: Leiste anlegen, sobald Arena und Vertrag da sind, dann halten (sendet die Leiste). */
        fun tick() {
            if (closed) return
            if (group == null && box() != null && viewers().isNotEmpty()) open()
            group?.tick()
        }

        private fun open() {
            val g = SubTargetGroup(world, viewers, subTargetHandler())
            g.theme = Vfx.Style.FIRE.ordinal + THEME_OFFSET
            val t = g.add(ANVIL_ID, SUB_KEY, ANVIL_HEALTH, 1f / BrontesTimings.ANVIL_CRACKS, BrontesTimings.ANVIL_CRACKS)
            t.anchor = top()
            group = g
            target = t
        }

        /**
         * Ein Riss (nur Brontes' Hammer, nur wenn ein Spieler dahinter gedeckt stand; prüft der Kampf). Teilziel ein
         * Drittel, Blöcke auf die nächste Stufe, Effekt und Ton. Gebrochen: [Crack.NONE] (er deckt ja nicht mehr).
         */
        fun crack(): Crack {
            if (closed || broken) return Crack.NONE
            val a = arena() ?: return Crack.NONE
            val box = box() ?: return Crack.NONE
            val edits = a.edits ?: return Crack.NONE
            if (group == null) open()
            cracks++
            // Treffer ohne Wesen: Nur jetzt nimmt das Teilziel Schaden („golden nur, solange es Treffer nimmt“ – für den
            // einen Tick; gesendet wird die Leiste erst im nächsten tick(), also ohne Goldblitz).
            target?.let { t ->
                // Beim Bruch ohne Ort: Die Gruppe würde sonst einen goldenen Blitz an den Amboss setzen, der jetzt
                // gerade NICHT mehr sicher ist (Gold = sicher/offen). Bild und Ton des Bruchs kommen unten.
                if (broken) t.anchor = null
                t.open = true
                t.hit(if (broken) t.maxHealth else t.maxHealth / BrontesTimings.ANVIL_CRACKS)
                if (broken && !t.broken) t.hit(t.health)
                t.open = false
            }
            for (pos in cells(box)) edits.set(pos, stageState(world.getBlockState(pos), pos, box, cracks))
            val at = top() ?: return Crack.CRACKED
            if (broken) {
                BossFx.play(world, FX_BREAK, Vfx.Style.FIRE, at, a = BREAK_DEBRIS_RADIUS, life = BREAK_FX_LIFE, impact = true)
                BossFx.debris(world, at.add(0.0, -1.0, 0.0), BREAK_DEBRIS_RADIUS, BREAK_DEBRIS)
                sound(at, BREAK_PITCH)
                sound(at, BREAK_PITCH * 0.8f, Blocks.ANVIL.defaultState.soundGroup.breakSound)
                return Crack.BROKEN
            }
            BossFx.play(world, FX_CRACK, Vfx.Style.FIRE, at, a = cracks.toFloat(), life = CRACK_FX_LIFE)
            BossFx.debris(world, at.add(0.0, -0.5, 0.0), 1.4f, CRACK_DEBRIS)
            sound(at, CRACK_PITCH - CRACK_PITCH_STEP * (cracks - 1))
            return Crack.CRACKED
        }

        /**
         * Neuer Amboss (Phase II, Szene `brontes.phase2` bzw. ihr `finish`): Blöcke zurück auf den Plan, Leiste voll.
         * Auch ein heiler oder angerissener Amboss wird erneuert. Mehrfacher Aufruf schadet nicht.
         */
        fun renew() {
            if (closed) return
            val box = box()
            if (box != null) arena()?.edits?.restore { box.contains(it) }
            val hadCracks = cracks > 0
            cracks = 0
            // Die Leiste kennt kein Auffüllen: neue Gruppe, neues Teilziel (die alte meldet sich mit leerer Liste ab).
            group?.close()
            group = null
            target = null
            if (box() != null && viewers().isNotEmpty()) open()
            if (hadCracks) top()?.let { sound(it, RENEW_PITCH, Blocks.ANVIL.defaultState.soundGroup.placeSound) }
        }

        /** Jedes Kampfende (Sieg, Abbruch, Entfernen): Leiste weg. Die Blöcke stellt `Arenas.close` aus dem Plan her. */
        fun close() {
            closed = true
            group?.close()
            group = null
            target = null
        }

        private fun sound(at: Vec3d, pitch: Float, event: net.minecraft.sound.SoundEvent = HhaSounds.ANVIL) {
            if (Cine.muted) return
            world.playSound(null, at.x, at.y, at.z, event, SoundCategory.HOSTILE, CRACK_VOLUME, pitch)
        }
    }

    /** Alle Zellen des Ambosskastens (3 × 3 × 2). */
    fun cells(box: BlockBox): List<BlockPos> {
        val out = ArrayList<BlockPos>()
        for (y in box.minY..box.maxY) for (x in box.minX..box.maxX) for (z in box.minZ..box.maxZ) out.add(BlockPos(x, y, z))
        return out
    }

    /**
     * Block an [pos] in Stufe [cracks] (1, 2, 3). [current] ist der Block in der Welt (für Richtung und Ambossstufe).
     * Lage relativ zur Kastenmitte; untere Lage = `box.minY`, obere = `box.maxY`.
     */
    fun stageState(current: BlockState, pos: BlockPos, box: BlockBox, cracks: Int): BlockState {
        val cx = (box.minX + box.maxX) / 2
        val cz = (box.minZ + box.maxZ) / 2
        val ox = pos.x - cx
        val oz = pos.z - cz
        val bottom = pos.y == box.minY
        val edgeMid = (ox == 0) != (oz == 0)
        val corner = ox != 0 && oz != 0
        if (cracks >= BrontesTimings.ANVIL_CRACKS) {
            // Gebrochen: oben nichts mehr, unten Trümmer aus halben Blöcken (deckt nicht).
            if (!bottom) return Blocks.AIR.defaultState
            val slab = when {
                ox == 0 && oz == 0 -> Blocks.POLISHED_BLACKSTONE_SLAB
                corner -> Blocks.BLACKSTONE_SLAB
                else -> HhaDecoBlocks.TARTARUS_BASALT_BRICK_SLAB
            }
            return slab.defaultState.with(Properties.SLAB_TYPE, SlabType.BOTTOM)
        }
        if (bottom) {
            return when {
                edgeMid -> HhaDecoBlocks.EMBER_BASALT.defaultState
                corner && cracks >= 2 -> Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultState
                else -> current
            }
        }
        // Obere Lage: der Amboss in der Mitte nimmt Schaden, ab zwei Rissen platzen zwei Eckstufen ab.
        if (current.block is AnvilBlock) {
            val facing = if (current.contains(Properties.HORIZONTAL_FACING)) current.get(Properties.HORIZONTAL_FACING) else Direction.NORTH
            val worse = when (current.block) {
                Blocks.ANVIL -> Blocks.CHIPPED_ANVIL
                Blocks.CHIPPED_ANVIL -> Blocks.DAMAGED_ANVIL
                else -> Blocks.DAMAGED_ANVIL
            }
            return worse.defaultState.with(Properties.HORIZONTAL_FACING, facing)
        }
        if (cracks >= 2 && corner && abs(ox + oz) == 2) return Blocks.AIR.defaultState
        return current
    }

    /** Leben des Teilziels vor Skalierung (nur Anteile zählen: jeder Riss ein Drittel). */
    private const val ANVIL_HEALTH = 3f

    /** `SubTargetGroup.theme` = 10 + Stil (wie `Cine.phaseBar`). */
    private const val THEME_OFFSET = 10

    /** Neuer Amboss: Ton beim Absetzen. */
    private const val RENEW_PITCH = 0.7f
}
