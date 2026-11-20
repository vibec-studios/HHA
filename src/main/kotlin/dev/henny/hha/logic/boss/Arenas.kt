package dev.henny.hha.logic.boss

import dev.henny.hha.Hha
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.boss.arena.ArenaBlueprints
import dev.henny.hha.logic.boss.arena.ArenaEdits
import dev.henny.hha.world.arena.ArenaSpec
import dev.henny.hha.world.arena.Level
import dev.henny.hha.world.arena.Part
import dev.henny.hha.world.arena.Zone
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
 * Kampfflächen der neuen Bosse (Überarbeitung 2, PLAN 4.4) und der Tartaros-Wächter (Überarbeitung 4, PLAN 7.4). Eine
 * Arena ist Welt, Mitte, Radius, Bodenhöhe, Eingang und Besitzer (der laufende Kampf). Solange sie offen ist, gilt:
 *
 * - **Blockschutz:** [HermesFights.isProtected] fragt [protects]; Blöcke in der Arena lassen sich nicht abbauen oder
 *   setzen (der Kampf selbst darf Blöcke tauschen, über [Arena.edits]).
 * - **Kein Fallschaden** für Teilnehmer ([Rules.noFallDamage]; Wächter: nein).
 * - **Bö am Rand:** Wer unter die Fläche fällt, wird zurück auf die Fläche geworfen ([GUST_DAMAGE] Schaden;
 *   [Rules.gust], Wächter: nein).
 * - **K. o. statt Tod:** Ein tödlicher Treffer setzt den Spieler mit [KO_HEALTH] Leben an den Eingang; das Inventar
 *   bleibt. Er scheidet aus, bis er die Fläche wieder betritt.
 * - **Ende bei leerer Arena:** Ist kein Teilnehmer mehr da, ruft die Arena [Owner.onArenaEmpty] (der Boss verschwindet,
 *   der Altar ist wieder scharf).
 *
 * Teilnehmer ist, wer während des Kampfes auf der Fläche stand ([Arena.onFloor]), lebt, kein Zuschauer ist und sich
 * nicht weiter als [Rules.leaveMargin] über den Rand hinaus entfernt.
 *
 * **Mit Vertrag** ([Arena.spec], Überarbeitung 4): Böden je Ebene ([Arena.floorAt]), verlorene Teile ([Arena.lose]),
 * Bö unter der tiefsten noch vorhandenen Fläche, Schutz für `spec.box`, Edit-Register und Reparatur aus dem Bauplan.
 * **Ohne Vertrag** verhält sich jede Funktion wie vor Überarbeitung 4 (jede Stelle sagt im Kommentar, wie).
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

    /**
     * Spielregeln einer Arena. [BOSS] = Olymp-Duelle wie bisher; [GUARDIAN] = Wächter-Plätze (PLAN 4.3: K. o. und
     * Blockschutz neu, Fallschaden bleibt, weil Kerker und Hügel ihre Höhe behalten sollen, keine Bö).
     */
    class Rules(
        val gust: Boolean = true,
        val noFallDamage: Boolean = true,
        val knockOutKey: String = "hha.boss.arena.knocked_out",
        val leaveMargin: Double = LEAVE_MARGIN,
    ) {
        companion object {
            val BOSS = Rules()
            val GUARDIAN = Rules(gust = false, noFallDamage = false, knockOutKey = "hha.guardian.arena.knocked_out", leaveMargin = GUARDIAN_LEAVE_MARGIN)
        }
    }

    /** Schaden der Bö, die Gefallene zurückwirft. */
    const val GUST_DAMAGE = 4f

    /** Leben nach einem K. o. */
    const val KO_HEALTH = 6f

    /** So weit unter dem Boden greift die Bö. */
    const val GUST_DEPTH = 3.0

    /** Abklingzeit der Bö je Spieler (Ticks). */
    const val GUST_COOLDOWN = 20

    /** Ziel der Bö mit Vertrag: so viele Blöcke vom Rand der Fläche (alle Zellen in diesem Abstand gehören dazu). */
    const val GUST_EDGE = 2

    /** Ohne Vertrag: Ziel der Bö so weit innerhalb des Radius (wie bisher). */
    const val GUST_INSET = 3.0

    /** Wer weiter als so viele Blöcke über den Rand hinaus ist, scheidet aus. */
    const val LEAVE_MARGIN = 16.0

    /** Wächter: Mitkämpfer im Kampfradius ([BossTimings.Guardian.FIGHT_RADIUS] 48) bleiben dabei, auch am Hügelfuß. */
    const val GUARDIAN_LEAVE_MARGIN = 40.0

    /** Wer höher als so viel über dem Boden schwebt, tritt nicht neu bei (fliegt nur vorbei). */
    const val JOIN_HEIGHT = 12.0

    /** Wer so hoch über dem Boden ist, gehört nicht mehr zum Kampf (wie bisher). */
    const val NEAR_HEIGHT = 64.0

    /** Eine Ebene gilt als „unter“ einem Punkt, wenn ihr Boden höchstens so weit über seinen Füßen liegt (Stufenkante). */
    const val FLOOR_TOLERANCE = 0.6

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
        /** Geometrie-Vertrag der Arena (Überarbeitung 4, PLAN 7.4), `null` = nur Kreis wie bisher. */
        val spec: ArenaSpec? = null,
        /** Spielregeln (Bö, Fallschaden, K. o.-Text, Austritt). */
        val rules: Rules = Rules.BOSS,
        /** Bauplan für Reparatur und Wiederherstellung (Standard: `spec.id`, falls registriert). */
        val blueprintId: String? = spec?.id,
        /** Ursprung des Bauplans (Olymp und Forum: Mitte der Arena, Tartaros: Bauwerksmitte `o` = `spec.center`). */
        val origin: BlockPos = spec?.center ?: BlockPos.ofFloored(center),
    ) {
        private val members = LinkedHashSet<UUID>()
        private val lostParts = ArrayList<Part>()
        private val gustCooldown = HashMap<UUID, Int>()
        private var editState: ArenaEdits? = null

        /** Zellen je Fläche, die [GUST_EDGE] vom Rand liegen (Ziel der Bö); nach [lose] neu berechnet. */
        private var gustCells: List<Pair<Int, List<Long>>>? = null

        /** Ist die Arena noch offen? */
        var open = true
            internal set

        /** Waagerechter Abstand zur Mitte. */
        fun flat(pos: Vec3d): Double = hypot(pos.x - center.x, pos.z - center.z)

        /**
         * Steht [pos] auf der Fläche (waagerecht im Radius, nicht weit darüber oder darunter)?
         *
         * Ohne Vertrag wie bisher (Radius, [floorY] − [GUST_DEPTH] … [floorY] + [JOIN_HEIGHT]). Mit Vertrag zählt
         * zusätzlich jede Kern-, Ebenen- und `extra`-Fläche, auch außerhalb des Radius; die Höhe misst von [floorAt];
         * über einem verlorenen Teil ohne andere Fläche steht niemand.
         */
        fun onFloor(pos: Vec3d): Boolean {
            val s = spec ?: return flat(pos) <= radius && pos.y > floorY - GUST_DEPTH && pos.y < floorY + JOIN_HEIGHT
            val inside = flat(pos) <= radius || s.extra.any { it.contains(pos.x, pos.z) } || s.floors().any { it.first.contains(pos.x, pos.z) }
            if (!inside || !standable(pos.x, pos.z)) return false
            val f = floorAt(pos)
            return pos.y > f - GUST_DEPTH && pos.y < f + JOIN_HEIGHT
        }

        /** Gehört [pos] noch zum Kampf (Fläche plus Rand, beliebig tief darunter)? Mit Vertrag zählen auch `extra`-Flächen. */
        fun near(pos: Vec3d): Boolean {
            if (pos.y >= floorY + NEAR_HEIGHT) return false
            if (flat(pos) <= radius + rules.leaveMargin) return true
            return spec?.extra?.any { it.contains(pos.x, pos.z) } == true
        }

        /** Schützt die Arena diesen Block? Mit Vertrag: genau `spec.box`; ohne: Kreis Radius + 4, 8 unter bis 40 über dem Boden. */
        fun protects(pos: BlockPos): Boolean {
            if (!open) return false
            spec?.let { return it.box.contains(pos) }
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
            if (rules.gust) {
                val line = gustLine()
                for (player in players()) {
                    if (player.y < line) gust(player)
                }
            }
            if (members.isEmpty()) owner.onArenaEmpty()
        }

        /** Unter dieser Höhe greift die Bö: [GUST_DEPTH] unter der tiefsten noch vorhandenen Fläche (ohne Vertrag: [floorY]). */
        fun gustLine(): Double {
            if (spec == null) return floorY - GUST_DEPTH
            val lowest = gustTargets().minOfOrNull { it.first }?.toDouble() ?: floorY
            return lowest - GUST_DEPTH
        }

        /** Die Bö: zurück auf die Fläche am nächsten Rand, kleiner Schaden, keine Fallhöhe. */
        private fun gust(player: ServerPlayerEntity) {
            if (gustCooldown.containsKey(player.uuid)) return
            gustCooldown[player.uuid] = GUST_COOLDOWN
            val spot = gustSpot(player.entityPos)
            val inward = Vec3d(center.x - spot.x, 0.0, center.z - spot.z).let { if (it.lengthSquared() < 1e-4) Vec3d(0.0, 0.0, -1.0) else it.normalize() }
            player.fallDistance = 0.0
            player.teleport(world, spot.x, spot.y, spot.z, emptySet<PositionFlag>(), player.yaw, player.pitch, false)
            Fx.launchPlayer(player, inward.multiply(0.4).add(0.0, 0.5, 0.0))
            player.damage(world, world.damageSources.magic(), GUST_DAMAGE)
            player.sendMessage(Text.translatable("hha.boss.arena.gust").formatted(Formatting.GOLD), true)
        }

        /**
         * Wohin die Bö [from] setzt. Ohne Vertrag wie bisher: [GUST_INSET] innerhalb des Radius in Richtung des Spielers.
         * Mit Vertrag: nächste Zelle einer noch vorhandenen Fläche (Kern oder Ebene), die [GUST_EDGE] Blöcke vom Rand,
         * von Deckung und Geräten liegt; Füße auf deren Boden.
         */
        fun gustSpot(from: Vec3d): Vec3d {
            if (spec == null) {
                val away = Vec3d(from.x - center.x, 0.0, from.z - center.z)
                val dir = if (away.lengthSquared() < 1e-4) Vec3d(0.0, 0.0, 1.0) else away.normalize()
                return center.add(dir.multiply((radius - GUST_INSET).coerceAtLeast(0.0))).let { Vec3d(it.x, floorY + 0.2, it.z) }
            }
            var best: Vec3d? = null
            var bestD = Double.MAX_VALUE
            for ((y, cells) in gustTargets()) {
                for (packed in cells) {
                    val x = unpackX(packed) + 0.5
                    val z = unpackZ(packed) + 0.5
                    val d = (x - from.x) * (x - from.x) + (z - from.z) * (z - from.z)
                    if (d < bestD) {
                        bestD = d
                        best = Vec3d(x, y + 0.2, z)
                    }
                }
            }
            return best ?: Vec3d(center.x, floorY + 0.2, center.z)
        }

        /** Zellen je vorhandener Fläche (Fußhöhe → Zellen), auf die die Bö setzen darf; leere Flächen fehlen. */
        private fun gustTargets(): List<Pair<Int, List<Long>>> = gustCells ?: computeGustCells().also { gustCells = it }

        private fun computeGustCells(): List<Pair<Int, List<Long>>> {
            val s = spec ?: return emptyList()
            val blocked = HashSet<Long>()
            for (c in s.cover) {
                val b = c.box
                for (x in b.minX - 1..b.maxX + 1) for (z in b.minZ - 1..b.maxZ + 1) blocked.add(pack(x, z))
            }
            for (d in s.devices) blocked.add(pack(d.pos.x, d.pos.z))
            val result = ArrayList<Pair<Int, List<Long>>>()
            for ((zone, y) in s.floors()) {
                val cells = ArrayList<Long>()
                val all = ArrayList<Long>()
                for ((x, z) in zone.cells()) {
                    if (floorLost(x + 0.5, z + 0.5, y)) continue
                    all.add(pack(x, z))
                    if (pack(x, z) in blocked) continue
                    if (interior(zone, x, z, y)) cells.add(pack(x, z))
                }
                // Schmale Flächen (Stufe, Steg) haben keine Zelle so weit vom Rand: dann irgendeine ihrer Zellen.
                val use = cells.ifEmpty { all.filter { it !in blocked } }
                if (use.isNotEmpty()) result.add(y to use)
            }
            return result
        }

        /** Gehören alle Zellen im Abstand [GUST_EDGE] (Quadrat) zur Fläche [zone] und sind nicht verloren? */
        private fun interior(zone: Zone, x: Int, z: Int, y: Int): Boolean {
            for (dx in -GUST_EDGE..GUST_EDGE) for (dz in -GUST_EDGE..GUST_EDGE) {
                if (!zone.containsCell(x + dx, z + dz) || floorLost(x + dx + 0.5, z + dz + 0.5, y)) return false
            }
            return true
        }

        // --- Ebenen und verlorene Flächen (Überarbeitung 4, PLAN 7.4) ------------------------------------------------

        /**
         * Höhe (Füße) der Fläche unter [pos]: von den Flächen aus `spec.floors()` (Kern, Ebenen), die (x, z) enthalten
         * und dort nicht verloren sind, die höchste, deren Boden höchstens [FLOOR_TOLERANCE] über den Füßen liegt;
         * liegt [pos] unter allen, die tiefste. Keine Fläche (oder kein Vertrag): [floorY].
         */
        fun floorAt(pos: Vec3d): Double = floorAtOrNull(pos) ?: floorY

        /** Wie [floorAt], aber `null`, wenn [pos] auf keiner (vorhandenen) Vertragsfläche liegt oder kein Vertrag da ist. */
        fun floorAtOrNull(pos: Vec3d): Double? = pick(pos)?.second?.toDouble()

        /** Die Ebene aus [spec], auf der [pos] steht (Regel wie [floorAt]); `null` auf dem Kern, daneben oder ohne Vertrag. */
        fun levelAt(pos: Vec3d): Level? {
            val s = spec ?: return null
            val (zone, y) = pick(pos) ?: return null
            if (zone === s.core) return null
            return s.levels.firstOrNull { it.zone === zone && it.floorY == y }
        }

        private fun pick(pos: Vec3d): Pair<Zone, Int>? {
            val s = spec ?: return null
            var best: Pair<Zone, Int>? = null
            var lowest: Pair<Zone, Int>? = null
            for (floor in s.floors()) {
                val (zone, y) = floor
                if (!zone.contains(pos.x, pos.z) || floorLost(pos.x, pos.z, y)) continue
                if (y <= pos.y + FLOOR_TOLERANCE && (best == null || y > best.second)) best = floor
                if (lowest == null || y < lowest.second) lowest = floor
            }
            return best ?: lowest
        }

        /** Ist der Boden in Fußhöhe [y] bei (x, z) mit einem verlorenen Teil weggefallen (Bodenblock `y − 1` im Teil)? */
        fun floorLost(x: Double, z: Double, y: Int): Boolean = lostParts.any { it.zone.contains(x, z) && y - 1 in it.minY..it.maxY }

        /** Kann man bei (x, z) noch stehen? Nein, wenn ein verlorenes Teil dort liegt und keine andere Fläche bleibt. */
        fun standable(x: Double, z: Double): Boolean {
            if (!isLost(x, z)) return true
            val s = spec ?: return true
            return s.floors().any { (zone, y) -> zone.contains(x, z) && !floorLost(x, z, y) }
        }

        /**
         * Fläche [part] fällt weg: [onFloor], [floorAt], Beitritt und Bö kennen sie nicht mehr. Doppelt verlieren schadet
         * nicht. Die Blöcke nimmt [ArenaCollapse.start] weg; wer darauf steht, fällt und wird von der Bö geholt.
         */
        fun lose(part: Part) {
            if (lostParts.none { it.id == part.id }) {
                lostParts.add(part)
                gustCells = null
            }
        }

        /** Liegt (x, z) auf einer verlorenen Fläche (waagerecht, jede Höhe)? */
        fun isLost(x: Double, z: Double): Boolean = lostParts.any { it.zone.contains(x, z) }

        /** Bisher verlorene Teile. */
        val lost: List<Part> get() = lostParts

        /**
         * Edit-Register, solange die Arena offen ist (nur mit Vertrag): Jede Blockänderung des Kampfes läuft hierüber;
         * [Arenas.close] stellt alles wieder her. Ohne Vertrag `null` (der Kampf tauscht Blöcke wie bisher selbst).
         */
        val edits: ArenaEdits? get() = editState

        /** Beim Öffnen: Register auf, Arena-Kern aus dem Bauplan reparieren (falls einer registriert ist). */
        internal fun openEdits() {
            val s = spec ?: return
            if (editState != null) return
            val id = blueprintId ?: s.id
            editState = ArenaEdits.open(world, id, origin)
            if (ArenaBlueprints.get(id) != null) {
                val fixed = ArenaBlueprints.repair(world, id, origin, instant = true)
                if (fixed > 0) Hha.LOGGER.info("Arena {} bei {}: {} Blöcke nach Bauplan repariert", id, origin, fixed)
            }
        }

        /** Beim Schließen: alles zurück (Bauplan bzw. gemerkter Zustand), danach niemand im Block. Genau einmal wirksam. */
        internal fun restoreEdits() {
            val edits = editState ?: return
            editState = null
            val changed = edits.changed.toList()
            edits.restoreAll()
            if (changed.isNotEmpty()) unstick(changed)
        }

        /** Wer nach dem Wiederherstellen in einem Block steckt, kommt auf den nächsten freien Platz darüber. */
        private fun unstick(changed: List<BlockPos>) {
            val cells = changed.mapTo(HashSet()) { pack(it.x, it.z) }
            for (player in world.players) {
                if (player.isSpectator || pack(player.blockX, player.blockZ) !in cells) continue
                var pos = player.blockPos
                var guard = 0
                while (guard++ < UNSTICK_MAX && (blocksAt(pos) || blocksAt(pos.up()))) pos = pos.up()
                if (pos != player.blockPos) {
                    player.fallDistance = 0.0
                    player.teleport(world, player.x, pos.y.toDouble(), player.z, emptySet<PositionFlag>(), player.yaw, player.pitch, false)
                }
            }
        }

        private fun blocksAt(pos: BlockPos): Boolean = !world.getBlockState(pos).getCollisionShape(world, pos).isEmpty

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
            player.sendMessage(Text.translatable(rules.knockOutKey).formatted(Formatting.GOLD), false)
        }
    }

    private const val UNSTICK_MAX = 24

    /** Zelle (x, z) als ein Long (für Mengen). */
    internal fun pack(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
    internal fun unpackX(packed: Long): Int = (packed shr 32).toInt()
    internal fun unpackZ(packed: Long): Int = packed.toInt()

    private val arenas = ArrayList<Arena>()

    /** Öffnet [arena] (der Kampf beginnt). Eine zweite Arena für denselben Boss wird abgelehnt (`false`). */
    fun open(arena: Arena): Boolean {
        if (arenas.any { it.open && it.owner.bossKey == arena.owner.bossKey && it.world === arena.world }) return false
        attach(arena)
        return true
    }

    /**
     * Öffnet [arena] ohne die Ein-Boss-Regel (Wächter: mehrere Bauwerke derselben Art dürfen gleichzeitig kämpfen).
     * Mit Vertrag: Edit-Register auf und Reparatur aus dem Bauplan.
     */
    internal fun attach(arena: Arena) {
        if (arena in arenas) return
        arena.open = true
        arenas.add(arena)
        arena.openEdits()
    }

    /** Schließt [arena] (Sieg, Abbruch, leere Arena) und stellt ihre Blöcke wieder her. Mehrfaches Schließen schadet nicht. */
    fun close(arena: Arena) {
        arena.open = false
        arenas.remove(arena)
        arena.restoreEdits()
    }

    /** Alle offenen Arenen. */
    fun all(): List<Arena> = arenas.toList()

    /** Offene Arena, auf deren Fläche oder an deren Rand [pos] liegt (in [world]). */
    fun of(world: ServerWorld, pos: Vec3d): Arena? = arenas.firstOrNull { it.open && it.world === world && it.near(pos) }

    /** Arena, an der [player] teilnimmt (oder `null`). */
    fun of(player: ServerPlayerEntity): Arena? = arenas.firstOrNull { it.open && it.isMember(player) }

    /** Laufende Arena des Bosses [bossKey] (z. B. für `/hha boss stop`). */
    fun byBoss(server: MinecraftServer, bossKey: String): Arena? =
        arenas.firstOrNull { it.open && it.owner.bossKey == bossKey && it.world.server === server }

    /** Schützt irgendeine offene Arena diesen Block? (Boss- und Wächter-Arenen.) */
    fun protects(world: ServerWorld, pos: BlockPos): Boolean = arenas.any { it.world === world && it.protects(pos) }

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register { server ->
            for (arena in arenas.toList()) if (arena.open) arena.tick()
            GuardianArenas.tick(server)
            ArenaCollapse.tick(server)
            Cover.tick(server)
        }
        ServerLivingEntityEvents.ALLOW_DEATH.register { entity, _, _ ->
            val player = entity as? ServerPlayerEntity ?: return@register true
            val arena = of(player) ?: return@register true
            arena.knockOut(player)
            false
        }
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source: DamageSource, _ ->
            val player = entity as? ServerPlayerEntity ?: return@register true
            !(source.isIn(DamageTypeTags.IS_FALL) && of(player)?.rules?.noFallDamage == true)
        }
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            for (arena in arenas.toList()) arena.owner.stopFight()
            ArenaCollapse.finishAll()
            // Was nach stopFight noch offen ist (Wächter, eigenwillige Besitzer), schließt hier samt Wiederherstellung.
            for (arena in arenas.toList()) close(arena)
            arenas.clear()
        }
        if (java.lang.Boolean.getBoolean("hha.verifyWorldgen")) {
            // Prüflauf: Selbsttest des Regelwerks (reine Funktionen), bis die Zusammenführung ihn in WorldgenVerify ruft.
            val failures = ArenaRulesCheck.run()
            require(failures.isEmpty()) { "Selbsttest Regelwerk (P03): $failures" }
            println("HHA-VERIFY Regelwerk (P03): Selbsttest bestanden (${ArenaRulesCheck.CASES} Fälle)")
        }
    }
}
