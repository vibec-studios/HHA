package dev.henny.hha.logic.world

import dev.henny.hha.Hha
import dev.henny.hha.HhaEntities
import dev.henny.hha.entity.mob.AutomatonEntity
import dev.henny.hha.entity.mob.GriffinEntity
import dev.henny.hha.entity.mob.KureteEntity
import dev.henny.hha.entity.mob.KureteSquad
import dev.henny.hha.entity.mob.OlympMobEntity
import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.HermesFights
import dev.henny.hha.logic.boss.timings.OlympMobTimings
import dev.henny.hha.world.olymp.OlympusLayout
import dev.henny.hha.world.olymp.OlympusPlans
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.entity.Entity
import net.minecraft.entity.SpawnReason
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Bewohner des Olymp an ihren **Posten** (Überarbeitung 2, PLAN 5.6). Ein Posten ist ein fester Ort mit Art und
 * Gruppengröße (aus den Bauplänen von P04, [OlympusPlans.posts]). Er besetzt sich, wenn ein Spieler naht, und bleibt
 * nach dem Tod seiner Gegner eine Weile leer. Die Gegner selbst ([OlympMobEntity]) werden nie gespeichert; die Uhren der
 * Posten leben nur bis zum Neustart des Servers.
 *
 * Regeln (die Konstanten stehen gleich darunter):
 * - **Besetzen**, wenn ein Spieler (nicht Kreativ/Zuschauer) näher als [SPAWN_DISTANCE] kommt, der Posten leer und
 *   nicht gesperrt ist.
 * - **Sperrzeit** [RESPAWN_TICKS] nach dem Tod des letzten Gegners; danach muss der Posten einmal frei von Spielern im
 *   Umkreis [RESPAWN_CLEAR] gewesen sein, bevor er sich wieder besetzt (nie vor jemandes Nase).
 * - **Ruhe bei Bosskämpfen:** Läuft auf derselben Insel ein Kampf ([Arenas], auf Insel B auch Hermes), besetzt sich
 *   nichts, und wer dort steht, zieht sich zurück. **Nie auf Insel A.**
 * - **Verschwinden**, wenn [DESPAWN_TICKS] lang kein Spieler im Umkreis [DESPAWN_DISTANCE] ist (das regelt jeder
 *   Gegner selbst; der Posten besetzt sich danach ohne Sperrzeit neu).
 */
object OlympusLife {

    // --- Posten-Regeln (PLAN 5.6, P11) --------------------------------------------------------------
    /** Besetzung, wenn ein Spieler näher als so viel kommt. */
    const val SPAWN_DISTANCE = 48.0
    /** Sperrzeit nach dem Tod des letzten Gegners (5 Minuten) … */
    const val RESPAWN_TICKS = 6000L
    /** … und danach muss einmal kein Spieler näher als so viel gewesen sein. */
    const val RESPAWN_CLEAR = 64.0
    /** Gegner verschwinden, wenn so lange kein Spieler im Umkreis [DESPAWN_DISTANCE] ist (60 s). */
    const val DESPAWN_TICKS = 1200
    const val DESPAWN_DISTANCE = 80.0
    /** Ein Posten gehört zu einer Insel, wenn er höchstens so weit hinter ihrem Rand liegt (Brückenköpfe). */
    const val ISLE_MARGIN = 8.0
    /** Außerhalb des Olymp (nur Testposten): Ruhe, wenn eine Arena näher als ihr Radius plus so viel ist. */
    const val ARENA_MARGIN = 24.0
    /** Die Posten werden so oft geprüft (Ticks). */
    const val PERIOD = 20

    /** Art eines Postens; die Gruppe ist höchstens so groß wie PLAN 5.6 sagt. */
    enum class Kind(val maxGroup: Int) {
        KURETE(OlympMobTimings.KURETE_GROUP),
        GRIFFIN(OlympMobTimings.GRIFFIN_GROUP),
        AUTOMATON(1);

        companion object {
            fun byName(name: String): Kind? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
    }

    /**
     * Ein Posten. [pos] = Mitte (Füße, Bodenblock darunter), Leine und Sperre zählen von hier. [spots] = Standplätze
     * je Gegner (Greif: oben auf seiner Säule, Wächterin: auf dem Sockel); leer = rund um [pos] verteilt. [yaw] =
     * Blickrichtung der Statue bzw. der Wachen (`null` = von der Inselmitte weg).
     */
    class Post(
        val world: RegistryKey<World>,
        val pos: BlockPos,
        val kind: Kind,
        val size: Int,
        val spots: List<BlockPos> = emptyList(),
        val yaw: Float? = null,
    )

    /** Zustand eines Postens zur Laufzeit. */
    private class Station(val post: Post, val test: Boolean = false) {
        val members = ArrayList<OlympMobEntity>()
        var killed = false
        var lockedUntil = 0L
        var needsClear = false
        val center: Vec3d get() = Vec3d(post.pos.x + 0.5, post.pos.y.toDouble(), post.pos.z + 0.5)
    }

    private val stations = ArrayList<Station>()
    private var loaded = false
    private var clock = 0

    fun init() {
        ServerTickEvents.END_SERVER_TICK.register(::tick)
        ServerLifecycleEvents.SERVER_STOPPED.register { _ ->
            stations.clear()
            loaded = false
            OlympMobEntity.clearHints()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Posten-Liste
    // ---------------------------------------------------------------------------------------------

    /**
     * Die Posten des Olymp aus den Bauplänen von P04 ([OlympusPlans.posts]). Einzelposten (Greif auf seiner Säule,
     * Wächterin auf ihrem Sockel) stehen genau auf `pos`; Gruppen verteilen sich um `pos`.
     */
    fun posts(): List<Post> = OlympusPlans.posts().map {
        val kind = Kind.valueOf(it.kind.name)
        Post(OlympusLayout.WORLD, it.pos, kind, it.group, if (it.group <= 1) listOf(it.pos) else emptyList())
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        for (post in posts()) {
            if (isleOf(post.world, post.pos)?.letter == 'A') {
                Hha.LOGGER.warn("Olymp-Posten auf Insel A ignoriert: {}", post.pos)
                continue
            }
            stations.add(Station(post))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Ablauf
    // ---------------------------------------------------------------------------------------------

    private fun tick(server: MinecraftServer) {
        if (++clock % PERIOD != 0) return
        ensureLoaded()
        for (station in stations.toList()) {
            val world = server.getWorld(station.post.world) ?: continue
            update(world, station)
        }
        stations.removeIf { it.test && it.members.isEmpty() }
    }

    private fun update(world: ServerWorld, station: Station) {
        // Wer ist noch da? Getötete zählen für die Sperrzeit, verschwundene nicht.
        val it = station.members.iterator()
        while (it.hasNext()) {
            val mob = it.next()
            if (!mob.isRemoved) continue
            if (mob.removalReason == Entity.RemovalReason.KILLED) station.killed = true
            it.remove()
        }
        val now = world.time
        if (busy(world, station)) {
            // Bosskampf auf dieser Insel: Die Posten räumen das Feld.
            for (mob in station.members) mob.discard()
            station.members.clear()
            return
        }
        if (station.members.isNotEmpty() || station.test) return
        if (station.killed) {
            station.killed = false
            station.lockedUntil = now + RESPAWN_TICKS
            station.needsClear = true
        }
        if (now < station.lockedUntil) return
        val center = station.center
        if (station.needsClear) {
            if (nearestSurvivor(world, center) < RESPAWN_CLEAR) return
            station.needsClear = false
        }
        if (nearestSurvivor(world, center) >= SPAWN_DISTANCE) return
        if (!world.isChunkLoaded(station.post.pos.x shr 4, station.post.pos.z shr 4)) return
        occupy(world, station)
    }

    /** Läuft auf der Insel dieses Postens ein Bosskampf? (Außerhalb des Olymp: eine Arena in der Nähe.) */
    private fun busy(world: ServerWorld, station: Station): Boolean {
        val center = station.center
        val isle = isleOf(world.registryKey, station.post.pos)
        if (isle == null) {
            return Arenas.all().any { it.open && it.world === world && flat(it.center, center) < it.radius + ARENA_MARGIN }
        }
        val reach = isle.radius + ISLE_MARGIN
        if (Arenas.all().any { it.open && it.world === world && flat(it.center, isle.centerVec) < reach }) return true
        return isle === OlympusLayout.B && HermesFights.of(world) != null
    }

    /** Die Insel des Pfads, zu der [pos] gehört (nur in der Olymp-Dimension). */
    private fun isleOf(world: RegistryKey<World>, pos: BlockPos): OlympusLayout.Isle? {
        if (world != OlympusLayout.WORLD) return null
        return OlympusLayout.ISLES.firstOrNull { hypot(pos.x + 0.5 - it.centerVec.x, pos.z + 0.5 - it.centerVec.z) <= it.radius + ISLE_MARGIN }
    }

    private fun nearestSurvivor(world: ServerWorld, center: Vec3d): Double =
        world.players.filter(OlympMobEntity::survivor).minOfOrNull { it.entityPos.distanceTo(center) } ?: Double.MAX_VALUE

    // ---------------------------------------------------------------------------------------------
    // Besetzen
    // ---------------------------------------------------------------------------------------------

    /** Stellt die Gegner des Postens auf; liefert, wie viele es wurden. */
    private fun occupy(world: ServerWorld, station: Station): Int {
        val post = station.post
        val count = post.size.coerceIn(1, post.kind.maxGroup)
        val center = station.center
        val yaw = post.yaw ?: outwardYaw(world, center)
        val squad = if (post.kind == Kind.KURETE && count > 1) KureteSquad() else null
        for (i in 0 until count) {
            val spot = spotFor(world, post, i, count) ?: continue
            val mob = create(world, post.kind) ?: continue
            mob.refreshPositionAndAngles(spot.x, spot.y, spot.z, yaw, 0f)
            mob.headYaw = yaw
            mob.bodyYaw = yaw
            if (!world.isSpaceEmpty(mob)) continue
            mob.home = center
            mob.post = spot
            when (mob) {
                is KureteEntity -> squad?.add(mob)
                is GriffinEntity -> mob.perch = spot
                is AutomatonEntity -> mob.statueYaw = yaw
            }
            world.spawnEntity(mob)
            station.members.add(mob)
        }
        return station.members.size
    }

    private fun create(world: ServerWorld, kind: Kind): OlympMobEntity? = when (kind) {
        Kind.KURETE -> HhaEntities.KURETE.create(world, SpawnReason.EVENT)
        Kind.GRIFFIN -> HhaEntities.GRIFFIN.create(world, SpawnReason.EVENT)
        Kind.AUTOMATON -> HhaEntities.AUTOMATON.create(world, SpawnReason.EVENT)
    }

    /**
     * Standplatz Nummer [i]: aus [Post.spots], sonst rund um die Mitte (Kureten im Dreieck, Greife einander gegenüber).
     * Greife ohne eigene Säule schweben [OlympMobTimings.TAKEOFF_HEIGHT] über dem Boden.
     */
    private fun spotFor(world: ServerWorld, post: Post, i: Int, count: Int): Vec3d? {
        post.spots.getOrNull(i)?.let { return Vec3d(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
        val radius = if (count == 1) 0.0 else if (post.kind == Kind.GRIFFIN) 4.0 else 1.8
        val a = Math.PI * 2.0 * i / count
        val x = post.pos.x + 0.5 + cos(a) * radius
        val z = post.pos.z + 0.5 + sin(a) * radius
        val floor = floorNear(world, MathHelper.floor(x), post.pos.y, MathHelper.floor(z)) ?: return null
        val lift = if (post.kind == Kind.GRIFFIN) OlympMobTimings.TAKEOFF_HEIGHT else 0.0
        return Vec3d(x, floor + lift, z)
    }

    /** Füße auf festem Boden mit zwei Blöcken Luft, gesucht von 6 über bis 8 unter [y]; sonst `null`. */
    private fun floorNear(world: ServerWorld, x: Int, y: Int, z: Int): Double? {
        for (dy in 6 downTo -8) {
            val feet = BlockPos(x, y + dy, z)
            val below = feet.down()
            val solid = !world.getBlockState(below).getCollisionShape(world, below).isEmpty
            if (!solid) continue
            if (!world.getBlockState(feet).getCollisionShape(world, feet).isEmpty) continue
            if (!world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty) continue
            return feet.y.toDouble()
        }
        return null
    }

    /** Blick von der Inselmitte weg (dorthin, woher Spieler über die Brücke kommen). */
    private fun outwardYaw(world: ServerWorld, at: Vec3d): Float {
        val isle = isleOf(world.registryKey, BlockPos.ofFloored(at)) ?: return 0f
        val dx = at.x - isle.centerVec.x
        val dz = at.z - isle.centerVec.z
        if (dx * dx + dz * dz < 1.0) return 0f
        return (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
    }

    private fun flat(a: Vec3d, b: Vec3d): Double = hypot(a.x - b.x, a.z - b.z)

    // ---------------------------------------------------------------------------------------------
    // Test
    // ---------------------------------------------------------------------------------------------

    /**
     * Für `/hha`-Tests (Befehl bei P06 gewünscht): stellt einen Testposten der Art [kind] 8 Blöcke vor [player] auf, in
     * jeder Welt, ohne Sperrzeit und ohne Neubesetzung (Kureten zu dritt, Greif schwebend ohne Säule, Wächterin als
     * Statue mit Blick zum Spieler). Liefert die Zahl der aufgestellten Gegner.
     */
    fun debugSpawn(player: ServerPlayerEntity, kind: Kind): Int {
        val world = player.entityWorld as? ServerWorld ?: return 0
        val look = player.rotationVector.let { Vec3d(it.x, 0.0, it.z) }.let { if (it.lengthSquared() < 1e-4) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
        val at = player.entityPos.add(look.multiply(8.0))
        val pos = BlockPos(MathHelper.floor(at.x), MathHelper.floor(player.y), MathHelper.floor(at.z))
        val facing = (Math.toDegrees(atan2(-look.z, -look.x)) - 90.0).toFloat()
        val station = Station(Post(world.registryKey, pos, kind, kind.maxGroup, yaw = facing), test = true)
        val n = occupy(world, station)
        if (n > 0) stations.add(station)
        return n
    }

    /** Dasselbe mit dem Namen der Art (`kurete`, `griffin`, `automaton`); `-1` = unbekannte Art. */
    fun debugSpawn(player: ServerPlayerEntity, kind: String): Int = Kind.byName(kind)?.let { debugSpawn(player, it) } ?: -1
}
