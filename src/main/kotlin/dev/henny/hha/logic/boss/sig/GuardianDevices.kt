package dev.henny.hha.logic.boss.sig

import dev.henny.hha.block.ArenaDeviceBlock
import dev.henny.hha.entity.miniboss.TartarosGuardian
import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.Cover
import dev.henny.hha.logic.boss.GuardianArenas
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha.logic.boss.arena.ArenaDevices
import dev.henny.hha.logic.boss.timings.GuardianSigTimings
import dev.henny.hha.world.arena.ArenaSpec
import dev.henny.hha.world.arena.DeviceSpot
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import kotlin.math.hypot

/**
 * Signatur-Mechanik der Tartaros-Wächter (Überarbeitung 4, PLAN 4.2; Besitzer P21): Kettenpfähle (Kerkermeister),
 * Fels zurückrollen (Sisyphos), Fesseln (Ixion), Schleusen (Tantalos). Hier stehen die Geräte-Handler und die
 * Geometrie-Helfer; die Abläufe selbst stehen in den Wächterklassen (`MiniBosses.kt`), die nur diese Helfer rufen.
 *
 * **Ohne Arena nichts Neues:** Jede Stelle im Kampf fragt zuerst `GuardianArenas.of(wächter)`; ist sie `null` (Wächter per
 * Befehl abseits eines Bauwerks), läuft der Kampf wie vor Überarbeitung 4. Die Handler hier wirken nur in einer offenen
 * Wächter-Arena, deren Vertrag das Gerät enthält.
 *
 * Blöcke ändert nur `arena.edits` (Geräte über [ArenaDevices.setMode], Wasserlage über [drainWater]); `Arenas.close`
 * stellt am Kampfende alles nach dem Bauplan wieder her.
 */
object GuardianDevices {

    const val CHAIN_POST = "chain_post"
    const val SLUICE_WHEEL = "sluice_wheel"

    /** Teil des Tantalos-Vertrags: die oberste Wasserlage des Innenbeckens (PZ1). */
    const val WATER_PART = "water"

    /** Ein Wächter, an dessen Arena Geräte hängen. [onDevice] = `true`, wenn die Bedienung jetzt wirkt (Gerät → Zustand 2). */
    interface DeviceUser {
        fun onDevice(kind: String, pos: BlockPos, player: ServerPlayerEntity): Boolean
    }

    fun init() {
        ArenaDevices.on(CHAIN_POST) { world, pos, player -> dispatch(world, pos, player, CHAIN_POST) }
        ArenaDevices.on(SLUICE_WHEEL) { world, pos, player -> dispatch(world, pos, player, SLUICE_WHEEL) }
    }

    /**
     * Rechtsklick auf ein goldenes Gerät: Es muss im Vertrag der offenen Arena stehen, der Spieler Teilnehmer sein, und der
     * Wächter dieser Arena entscheidet ([DeviceUser.onDevice]).
     */
    private fun dispatch(world: ServerWorld, pos: BlockPos, player: ServerPlayerEntity, kind: String): Boolean {
        val arena = ArenaDevices.arenaAt(world, pos) ?: return false
        val spec = arena.spec ?: return false
        if (spec.devices.none { it.pos == pos }) return false
        if (!arena.isMember(player)) return false
        val b = spec.box
        val box = Box(b.minX.toDouble(), b.minY.toDouble(), b.minZ.toDouble(), b.maxX + 1.0, b.maxY + 1.0, b.maxZ + 1.0)
        val user = world.getEntitiesByClass(TartarosGuardian::class.java, box) { it.isAlive && GuardianArenas.of(it) === arena }
            .firstOrNull() as? DeviceUser ?: return false
        return user.onDevice(kind, pos, player)
    }

    /** Geräte der Art [kind] im Vertrag (nach dem Block an der Stelle; ein fehlender Block zählt nicht). */
    fun devices(world: ServerWorld, spec: ArenaSpec, kind: String): List<DeviceSpot> =
        spec.devices.filter { (world.getBlockState(it.pos).block as? ArenaDeviceBlock)?.kind == kind }

    /** Mitte eines Geräts (Block), [up] über seinem Fuß. */
    fun center(spot: DeviceSpot, up: Double = 0.5): Vec3d = Vec3d(spot.pos.x + 0.5, spot.pos.y + up, spot.pos.z + 0.5)

    // --- Kerkermeister ------------------------------------------------------------------------------------------

    /**
     * Ein **stehender** Kettenpfahl (Zustand 0), an dem die Kettenlinie [from] → [to] näher als
     * [GuardianSigTimings.POST_HOOK_RANGE] vorbeiläuft (waagerecht, zwischen Anfang und Ende); bei mehreren der vorderste.
     */
    fun postNear(world: ServerWorld, spec: ArenaSpec, from: Vec3d, to: Vec3d): DeviceSpot? {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val len = hypot(dx, dz)
        if (len < 1e-3) return null
        val ux = dx / len
        val uz = dz / len
        var best: DeviceSpot? = null
        var bestAlong = Double.MAX_VALUE
        for (spot in devices(world, spec, CHAIN_POST)) {
            if (ArenaDevices.mode(world, spot.pos) != ArenaDevices.IDLE) continue
            val c = center(spot)
            val along = (c.x - from.x) * ux + (c.z - from.z) * uz
            if (along < 0.0 || along > len) continue
            val across = kotlin.math.abs((c.x - from.x) * uz - (c.z - from.z) * ux)
            if (across >= GuardianSigTimings.POST_HOOK_RANGE) continue
            if (along < bestAlong) {
                best = spot
                bestAlong = along
            }
        }
        return best
    }

    /**
     * Pflicht 3 (Höhenband): Zeigt eine Linien-Ansage, die über eine Ebene läuft, dort ein zweites Mal auf Höhe der Ebene
     * (gleiche Gruppe, gleiche Zeit; der Client zeichnet flach). Nur das Stück über der Ebene; ohne Ebene nichts.
     */
    fun echoLine(group: Telegraph.Fight.Group, spec: ArenaSpec, from: Vec3d, to: Vec3d, width: Float, ticks: Int) {
        for (level in spec.levels) {
            var first = -1.0
            var last = -1.0
            for (i in 0..ECHO_SAMPLES) {
                val t = i.toDouble() / ECHO_SAMPLES
                val x = from.x + (to.x - from.x) * t
                val z = from.z + (to.z - from.z) * t
                if (!level.zone.contains(x, z)) continue
                if (first < 0.0) first = t
                last = t
            }
            if (first < 0.0) continue
            val y = level.floorY + 0.05
            val a = from.lerp(to, first).let { Vec3d(it.x, y, it.z) }
            val b = from.lerp(to, last).let { Vec3d(it.x, y, it.z) }
            if (a.squaredDistanceTo(b) < 0.25) continue
            group.line(a, b, width, ticks)
        }
    }

    // --- Deckung als Linienende (Sisyphos-Altar, Tantalos-Stamm und Fels) ----------------------------------------

    /**
     * Endpunkt einer Bodenlinie [from] → [to], die an Deckung gegen [attack] endet (`Cover.lineEnd` in Höhe [lift] über
     * dem Anfang). Nur ein Treffer in einer Deckung aus dem Vertrag zählt; Gelände (Hang, Absatz) kürzt die Linie nicht.
     */
    fun coverEnd(world: ServerWorld, spec: ArenaSpec, from: Vec3d, to: Vec3d, attack: String, lift: Double): Vec3d {
        val a = from.add(0.0, lift, 0.0)
        val b = Vec3d(to.x, from.y + lift, to.z)
        val hit = Cover.lineEnd(world, a, b)
        if (hit.squaredDistanceTo(b) < 1e-4) return to
        val spot = Cover.spotAt(spec, hit) ?: return to
        if (attack !in spot.protects) return to
        // Ein Stück vor der Deckung enden (wie die Kette des Kerkermeisters), auf der Höhe des Ziels.
        val dir = Vec3d(b.x - a.x, 0.0, b.z - a.z).normalize()
        return Vec3d(hit.x - dir.x * COVER_BACKOFF, to.y, hit.z - dir.z * COVER_BACKOFF)
    }

    // --- Tantalos -----------------------------------------------------------------------------------------------

    /** Die Schleuse, die [at] am nächsten liegt und nicht verbraucht ist (sonst die nächste überhaupt). */
    fun nearestSluice(world: ServerWorld, spec: ArenaSpec, at: Vec3d): DeviceSpot? {
        val all = devices(world, spec, SLUICE_WHEEL)
        val fresh = all.filter { ArenaDevices.mode(world, it.pos) != ArenaDevices.SPENT }
        return fresh.ifEmpty { all }.minByOrNull { center(it).squaredDistanceTo(at) }
    }

    /** Zellen der obersten Wasserlage (Teil [WATER_PART]), von außen nach innen sortiert; leer ohne Teil. */
    fun waterCells(spec: ArenaSpec): List<BlockPos> {
        val part = spec.parts.firstOrNull { it.id == WATER_PART } ?: return emptyList()
        val c = spec.centerVec
        return part.zone.cells().map { (x, z) -> BlockPos(x, part.minY, z) }
            .sortedByDescending { hypot(it.x + 0.5 - c.x, it.z + 0.5 - c.z) }.toList()
    }

    /**
     * Eine geschlossene Schleuse senkt das Innenbecken um eine Lage (`ArenaCollapse` nimmt keine Flüssigkeit, darum
     * hier über `arena.edits`). Liefert die Folge, die je Tick ein Stück leert ([GuardianSigTimings.WATER_DRAIN] Ticks).
     * Nur Wasser wird entfernt; was der Bauplan dort anders hat, bleibt.
     */
    fun drainWater(arena: Arenas.Arena): Sequence<Unit> = sequence {
        val spec = arena.spec ?: return@sequence
        val cells = waterCells(spec)
        if (cells.isEmpty()) return@sequence
        val per = (cells.size + GuardianSigTimings.WATER_DRAIN - 1) / GuardianSigTimings.WATER_DRAIN
        for (chunk in cells.chunked(per.coerceAtLeast(1))) {
            val edits = arena.edits ?: return@sequence
            if (!arena.open) return@sequence
            for (pos in chunk) if (arena.world.getFluidState(pos).isStill && arena.world.getBlockState(pos).isOf(net.minecraft.block.Blocks.WATER)) edits.clear(pos)
            yield(Unit)
        }
    }

    /** Wasserlage zurück nach dem Bauplan (nächstes Trinken, Leine). Am Kampfende macht es `Arenas.close`. */
    fun restoreWater(arena: Arenas.Arena) {
        val spec = arena.spec ?: return
        val part = spec.parts.firstOrNull { it.id == WATER_PART } ?: return
        arena.edits?.restore { it.y == part.minY && part.zone.containsCell(it.x, it.z) }
    }

    /** Alle Geräte der Art [kind] dieser Arena auf 0 (über `arena.edits`), außer verbrauchten, wenn [keepSpent]. */
    fun resetDevices(arena: Arenas.Arena, kind: String, keepSpent: Boolean) {
        val spec = arena.spec ?: return
        for (spot in devices(arena.world, spec, kind)) {
            val mode = ArenaDevices.mode(arena.world, spot.pos)
            if (mode == ArenaDevices.IDLE || (keepSpent && mode == ArenaDevices.SPENT)) continue
            ArenaDevices.setMode(arena.world, spot.pos, ArenaDevices.IDLE)
        }
    }

    /** Abtastpunkte für [echoLine]. */
    private const val ECHO_SAMPLES = 24

    /** So weit vor der Deckung endet eine Linie (Blöcke). */
    private const val COVER_BACKOFF = 0.3
}
