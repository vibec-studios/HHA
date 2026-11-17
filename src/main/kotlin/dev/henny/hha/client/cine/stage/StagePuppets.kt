package dev.henny.hha.client.cine.stage

import com.mojang.authlib.GameProfile
import dev.henny.hha.Hha
import dev.henny.hha.client.pose.HhaPose
import dev.henny.hha.entity.boss.HadesEntity
import dev.henny.hha.entity.boss.HermesEntity
import dev.henny.hha.entity.boss.KronosEntity
import dev.henny.hha.entity.miniboss.MiniBoss
import dev.henny.hha.entity.miniboss.MiniBossKit
import dev.henny.hha.stage.Pose
import net.minecraft.client.network.OtherClientPlayerEntity
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityType
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.SpawnReason
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import java.util.UUID

/**
 * Puppen der Bühne (PLAN 6.5): Client-Wesen **nur für die Szene**, ohne Server-Gegenstück. IDs ab [ID_BASE]; sie werden
 * bei `StagePlayer.stop()`, beim Dimensionswechsel und beim Verbindungsende entfernt. Für sie geht nie ein Paket hinaus
 * (die Eingaben sind in Szenen gesperrt; ein Server kennt die IDs nicht und verwirft sie).
 *
 * - Typ aus der Registry-ID; `minecraft:player` wird ein [OtherClientPlayerEntity] (Standard-Skin aus dem Namen,
 *   ohne Namensschild, Kostüm über [HhaPose.costume]).
 * - [move]: Lage und Blick je Tick entlang einer Catmull-Rom-Kurve mit Trapez-Tempo, Blick in Laufrichtung mit
 *   höchstens [TURN_STEP]° je Tick, optional Sprungbogen.
 * - [act]: über Treiber je Typ bzw. Klasse ([driver]). Eingebaut: Hermes, Hades, Kronos (Pose = Aktion), alle Wächter
 *   und Bosse auf `GuardianEntity` (Aktion 0..40 wie `MiniBossKit.act`), Spieler-Puppen (Aktion = `Pose`-Ordinal).
 * - Lässt sich eine Puppe nicht erzeugen, läuft die Szene ohne sie weiter (einmal geloggt; PLAN 5).
 */
object StagePuppets {

    /** Erste ID der Puppen (weit weg von Server-IDs). */
    const val ID_BASE = HhaPose.PUPPET_ID_BASE

    /** Höchste Drehung je Tick beim Laufen (Grad). */
    const val TURN_STEP = 12f

    /** Anteil der Laufzeit für Anfahren und Bremsen (Trapez). */
    const val RAMP = 0.2

    /** So viele Stützpunkte voraus schaut die Puppe beim Laufen. */
    private const val LOOK_AHEAD = 4

    private class Puppet(val name: String, val entity: Entity)

    private class Move(val puppet: Puppet, val from: Int, val until: Int, val samples: CameraCraft.Samples, val lift: Double)

    private val puppets = LinkedHashMap<String, Puppet>()
    private val moves = ArrayList<Move>()
    private var nextId = ID_BASE
    private var world: ClientWorld? = null
    private val warned = HashSet<String>()

    private val typeDrivers = HashMap<EntityType<*>, (Entity, Int) -> Unit>()
    private val classDrivers = ArrayList<Pair<Class<*>, (Entity, Int) -> Unit>>()

    /** Treiber für Puppen des Typs [type]: `action` kommt aus `puppetAct`. Spätere Anmeldung ersetzt frühere. */
    fun driver(type: EntityType<*>, block: (Entity, Int) -> Unit) {
        typeDrivers[type] = block
    }

    /** Treiber für alle Puppen, deren Wesen [kind] ist (nach den Typ-Treibern geprüft, erste passende gewinnt). */
    fun driver(kind: Class<*>, block: (Entity, Int) -> Unit) {
        classDrivers.add(kind to block)
    }

    /** Eingebaute Treiber; aus `StagePlayer.init`. */
    fun init() {
        driver(HermesEntity::class.java) { e, action -> (e as HermesEntity).pose = action }
        driver(HadesEntity::class.java) { e, action -> (e as HadesEntity).pose = action }
        driver(KronosEntity::class.java) { e, action -> (e as KronosEntity).pose = action }
        // Wächter und Bosse auf GuardianEntity (Hekate, Brontes, Kratos, Bia, Zeus, Kampe, die Tartaros-Wächter): am
        // Client kommt eine Aktion als Status-Byte an (MiniBossKit.onStatus); genau das spielen wir der Puppe vor.
        driver(MiniBoss::class.java) { e, action ->
            if (action in 0..MiniBossKit.ACTION_KINDS) e.handleStatus((MiniBossKit.ACTION_STATUS + action).toByte())
        }
        driver(OtherClientPlayerEntity::class.java) { e, action ->
            Pose.entries.getOrNull(action)?.let { HhaPose.pose(e.id, it) }
        }
    }

    /** Die Puppe [name] oder null. */
    fun find(name: String): Entity? = puppets[name]?.entity

    /**
     * Erzeugt die Puppe [name] vom Typ [typeId] an [pos] mit Blick [yaw]; [costume] für Spieler-Puppen. Gibt es den
     * Namen schon, wird die alte ersetzt.
     */
    fun spawn(world: ClientWorld, name: String, typeId: String, pos: Vec3d, yaw: Float, costume: String) {
        if (this.world !== world) clear()
        this.world = world
        remove(name)
        val entity = create(world, name, typeId) ?: return
        entity.id = nextId++
        entity.refreshPositionAndAngles(pos, yaw, 0f)
        entity.resetPosition()
        if (entity is LivingEntity) {
            entity.headYaw = yaw
            entity.bodyYaw = yaw
            entity.lastHeadYaw = yaw
            entity.lastBodyYaw = yaw
        }
        world.addEntity(entity)
        puppets[name] = Puppet(name, entity)
        if (costume.isNotEmpty()) HhaPose.costume(entity.id, costume)
    }

    private fun create(world: ClientWorld, name: String, typeId: String): Entity? {
        val id = Identifier.tryParse(typeId)
        if (id == null) {
            warn(typeId, "ungültige Typ-ID")
            return null
        }
        if (id.toString() == "minecraft:player") {
            val profile = GameProfile(UUID.nameUUIDFromBytes("hha-stage:$name".toByteArray()), "hha_stage")
            return OtherClientPlayerEntity(world, profile)
        }
        val type = Registries.ENTITY_TYPE.getOptionalValue(id).orElse(null)
        if (type == null) {
            warn(typeId, "unbekannter Typ")
            return null
        }
        val entity = type.create(world, SpawnReason.LOAD)
        if (entity == null) warn(typeId, "lässt sich nicht erzeugen")
        return entity
    }

    private fun warn(typeId: String, why: String) {
        if (warned.add(typeId)) Hha.LOGGER.warn("Bühne: Puppe '{}' {} – die Szene läuft ohne sie weiter", typeId, why)
    }

    /**
     * Die Puppe [name] läuft von Tick [from] bis [until] (Szenen-Uhr) über [path] (schon aufgelöste Punkte; der erste
     * Punkt der Kurve ist ihre jetzige Lage). [lift] = Höhe des Sprungbogens.
     */
    fun move(name: String, path: List<Vec3d>, from: Int, until: Int, lift: Double) {
        val puppet = puppets[name] ?: return
        moves.removeIf { it.puppet === puppet }
        if (until <= from) {
            path.lastOrNull()?.let { place(puppet.entity, it, puppet.entity.yaw) }
            return
        }
        val samples = CameraCraft.samples(listOf(puppet.entity.entityPos) + path)
        moves.add(Move(puppet, from, until, samples, lift))
    }

    /** Aktion [action] der Puppe [name] über ihren Treiber (ohne Treiber bleibt sie in Ruhepose). */
    fun act(name: String, action: Int) {
        val entity = puppets[name]?.entity ?: return
        val drive = typeDrivers[entity.type] ?: classDrivers.firstOrNull { it.first.isInstance(entity) }?.second
        if (drive == null) {
            warn("${entity.type}#act", "hat keinen Treiber")
            return
        }
        drive(entity, action)
    }

    /** Die Puppe [name] verschwindet. */
    fun remove(name: String) {
        val puppet = puppets.remove(name) ?: return
        moves.removeIf { it.puppet === puppet }
        HhaPose.costume(puppet.entity.id, "")
        HhaPose.pose(puppet.entity.id, Pose.NONE)
        world?.removeEntity(puppet.entity.id, Entity.RemovalReason.DISCARDED)
    }

    /** Je Tick der Szene ([clock] = Szenen-Uhr nach dem Weiterzählen). */
    fun tick(clock: Int) {
        if (moves.isEmpty()) return
        val iterator = moves.iterator()
        while (iterator.hasNext()) {
            val m = iterator.next()
            val span = (m.until - m.from).toDouble()
            val x = ((clock - m.from) / span).coerceIn(0.0, 1.0)
            val s = CameraCraft.trapezoid(x, RAMP)
            val p = m.samples.at(s).add(0.0, m.lift * 4.0 * x * (1.0 - x), 0.0)
            val entity = m.puppet.entity
            val i = m.samples.indexAt(s)
            val ahead = m.samples.points[minOf(i + LOOK_AHEAD, m.samples.points.size - 1)]
            val yaw = if (ahead.squaredDistanceTo(p.x, ahead.y, p.z) > 0.04) {
                MathHelper.stepUnwrappedAngleTowards(entity.yaw, CameraCraft.yawOf(p, ahead), TURN_STEP)
            } else entity.yaw
            val before = entity.entityPos
            place(entity, p, yaw)
            if (entity is LivingEntity) {
                val moved = before.subtract(p).horizontalLength().toFloat()
                entity.limbAnimator.updateLimbs((moved * 4f).coerceAtMost(1f), 0.4f, 1f)
            }
            if (clock >= m.until) iterator.remove()
        }
    }

    /** Setzt Lage und Blick; die Zeichnung blendet vom letzten Tick (der Welt-Tick hat `lastX` schon gesetzt). */
    private fun place(entity: Entity, pos: Vec3d, yaw: Float) {
        entity.setPosition(pos.x, pos.y, pos.z)
        entity.yaw = yaw
        if (entity is LivingEntity) {
            entity.headYaw = yaw
            entity.bodyYaw = yaw
        }
    }

    /** Alle Puppen weg (Szenenende, Dimensionswechsel). */
    fun clear() {
        for (name in puppets.keys.toList()) remove(name)
        puppets.clear()
        moves.clear()
        world = null
    }

    /** Verbindungsende: die Welt ist schon fort, nur vergessen. */
    fun reset() {
        for (p in puppets.values) {
            HhaPose.costume(p.entity.id, "")
        }
        puppets.clear()
        moves.clear()
        world = null
        nextId = ID_BASE
    }
}
