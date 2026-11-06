package dev.henny.hha.logic

import dev.henny.hha.net.VfxPayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.Entity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Vec3d

/**
 * Serverseite der 3D-Effekte: jeder Aufruf schickt ein [VfxPayload] an alle Spieler in Sichtweite,
 * der Client baut daraus echte Meshes (siehe `client/vfx`). Die Spiellogik bleibt komplett auf dem
 * Server — verliert ein Client den Effekt, ändert sich nichts am Ergebnis.
 */
object Vfx {

    /** Farbwelt eines Effekts. Ordinal = Wire-ID, deshalb nur hinten anfügen. */
    enum class Style { HEAVEN, HELL, FIRE, SOUL, STORM, GOLD, SHADOW, HADES }

    /** Effektarten. Ordinal = Wire-ID, deshalb nur hinten anfügen. */
    enum class Kind {
        BEAM, SHOCKWAVE, CRESCENT, BOLT, PILLAR, TELEGRAPH_RING, TELEGRAPH_LINE, SHIELD, WINGS, CHAIN,
        TRAIL, RIFT, NOVA, RUNE, TORNADO, AURA, FLOOD, SPEAR, FEATHERS, FLARE, SHAKE, SCREEN_FLASH,
        ORBIT_RINGS, FALLING_FEATHER, SKY_MOOD, CLOUDS, GOD_RAYS, ZEUS_EYES,
    }

    private const val RANGE = 160.0

    /** Solange gesetzt, laufen alle Effekte in diesem Stil. So kann ein ganzer Kampf die Farbe wechseln. */
    var styleOverride: Style? = null

    private fun send(world: ServerWorld, center: Vec3d, payload: VfxPayload) {
        for (player in world.players) {
            if (player.squaredDistanceTo(center) <= RANGE * RANGE) {
                ServerPlayNetworking.send(player, payload)
            }
        }
    }

    private fun emit(
        world: ServerWorld, kind: Kind, style: Style, pos: Vec3d, dir: Vec3d = Vec3d.ZERO,
        a: Float = 0f, b: Float = 0f, life: Int = 20, entity: Entity? = null, entity2: Entity? = null,
    ) {
        send(
            world, pos,
            VfxPayload(
                kind.ordinal, (styleOverride ?: style).ordinal, pos.x, pos.y, pos.z, dir.x, dir.y, dir.z, a, b, life,
                entity?.id ?: -1, entity2?.id ?: -1,
            ),
        )
    }

    /** Strahl von [start] nach [end]; [width] skaliert die Dicke. */
    fun beam(world: ServerWorld, style: Style, start: Vec3d, end: Vec3d, width: Float = 1f, life: Int = 14) =
        emit(world, Kind.BEAM, style, start, end, width, life = life)

    /** Bodenschockwelle mit Trümmern. */
    fun shockwave(world: ServerWorld, style: Style, center: Vec3d, radius: Float, life: Int = 18, height: Float = 2.4f) =
        emit(world, Kind.SHOCKWAVE, style, center, a = radius, b = height, life = life)

    /** Fliegende Sichel entlang [dir] über [range] Blöcke. */
    fun crescent(world: ServerWorld, style: Style, origin: Vec3d, dir: Vec3d, range: Float, width: Float = 3f, life: Int = 10) =
        emit(world, Kind.CRESCENT, style, origin, dir, range, width, life)

    /** Verästelter Blitz. */
    fun bolt(world: ServerWorld, style: Style, start: Vec3d, end: Vec3d, width: Float = 1f, life: Int = 8) =
        emit(world, Kind.BOLT, style, start, end, width, life = life)

    fun pillar(world: ServerWorld, style: Style, center: Vec3d, radius: Float, height: Float, life: Int = 24) =
        emit(world, Kind.PILLAR, style, center, a = radius, b = height, life = life)

    /** Warnkreis am Boden, der sich über [life] Ticks füllt. */
    fun telegraphRing(world: ServerWorld, style: Style, center: Vec3d, radius: Float, life: Int) =
        emit(world, Kind.TELEGRAPH_RING, style, center, a = radius, life = life)

    /** Warnlinie am Boden. */
    fun telegraphLine(world: ServerWorld, style: Style, from: Vec3d, to: Vec3d, width: Float, life: Int) =
        emit(world, Kind.TELEGRAPH_LINE, style, from, to, width, life = life)

    /** Schild-Hülle um ein Wesen, die am Ende zersplittert. */
    fun shield(world: ServerWorld, style: Style, target: Entity, radius: Float = 1.5f, life: Int = 26) =
        emit(world, Kind.SHIELD, style, target.entityPos, a = radius, life = life, entity = target)

    /** Flügel, die einem Wesen folgen; [spread] = Spannweite. */
    fun wings(world: ServerWorld, style: Style, target: Entity, life: Int, spread: Float = 1f) =
        emit(world, Kind.WINGS, style, target.entityPos, a = spread, life = life, entity = target)

    /** 3D-Kette von [from] (Spieler) zu einem Punkt oder einem Wesen. */
    fun chain(world: ServerWorld, style: Style, from: Entity, toPos: Vec3d, toEntity: Entity? = null, life: Int = 12) =
        emit(world, Kind.CHAIN, style, from.entityPos, toPos, life = life, entity = from, entity2 = toEntity)

    /** Leuchtendes Band hinter einem fliegenden Wesen. */
    fun trail(world: ServerWorld, style: Style, target: Entity, width: Float = 0.5f, life: Int = 40) =
        emit(world, Kind.TRAIL, style, target.entityPos, a = width, life = life, entity = target)

    /** Wirbelndes Portal; [normal] zeigt aus der Fläche heraus. */
    fun rift(world: ServerWorld, style: Style, center: Vec3d, normal: Vec3d, radius: Float, life: Int) =
        emit(world, Kind.RIFT, style, center, normal, radius, life = life)

    fun nova(world: ServerWorld, style: Style, center: Vec3d, radius: Float, life: Int = 14) =
        emit(world, Kind.NOVA, style, center, a = radius, life = life)

    /** Magischer Runenkreis, optional an ein Wesen geheftet. */
    fun rune(world: ServerWorld, style: Style, center: Vec3d, radius: Float, life: Int, follow: Entity? = null, normal: Vec3d = Vec3d(0.0, 1.0, 0.0)) =
        emit(world, Kind.RUNE, style, center, normal, radius, life = life, entity = follow)

    /** Wandernder Wirbelsturm. */
    fun tornado(world: ServerWorld, style: Style, center: Vec3d, velocity: Vec3d, radius: Float, height: Float, life: Int) =
        emit(world, Kind.TORNADO, style, center, velocity, radius, height, life)

    /** Spiralband-Aura um ein Wesen. */
    fun aura(world: ServerWorld, style: Style, target: Entity, life: Int, radius: Float = 1f) =
        emit(world, Kind.AURA, style, target.entityPos, a = radius, life = life, entity = target)

    /** Flutring zwischen [inner] und [outer] (Styx). */
    fun flood(world: ServerWorld, style: Style, center: Vec3d, outer: Float, inner: Float, life: Int) =
        emit(world, Kind.FLOOD, style, center, a = outer, b = inner, life = life)

    /** Riesiger Lichtspeer: bildet sich über [life] Ticks aus und schlägt dann ein. */
    fun spear(world: ServerWorld, style: Style, ground: Vec3d, height: Float, scale: Float, life: Int) =
        emit(world, Kind.SPEAR, style, ground, a = height, b = scale, life = life)

    fun feathers(world: ServerWorld, style: Style, center: Vec3d, count: Int, spread: Float = 1f, life: Int = 40) =
        emit(world, Kind.FEATHERS, style, center, a = count.toFloat(), b = spread, life = life)

    fun flare(world: ServerWorld, style: Style, center: Vec3d, size: Float, life: Int = 8) =
        emit(world, Kind.FLARE, style, center, a = size, life = life)

    /** Kameraschütteln für alle in [radius] Blöcken um [center], weicher zum Rand hin. */
    fun shake(world: ServerWorld, center: Vec3d, intensity: Float, ticks: Int, radius: Float = 32f) =
        emit(world, Kind.SHAKE, Style.GOLD, center, a = intensity, b = radius, life = ticks)

    /** Bildschirmblitz nur für [player]. */
    fun screenFlash(player: ServerPlayerEntity, style: Style, strength: Float, ticks: Int) {
        ServerPlayNetworking.send(
            player,
            VfxPayload(
                Kind.SCREEN_FLASH.ordinal, style.ordinal, player.x, player.y, player.z,
                0.0, 0.0, 0.0, strength, 0f, ticks, -1, -1,
            ),
        )
    }

    /** Kreisende Ringe um ein Wesen (Gyroskop). */
    fun orbitRings(world: ServerWorld, style: Style, target: Entity, radius: Float, life: Int) =
        emit(world, Kind.ORBIT_RINGS, style, target.entityPos, a = radius, life = life, entity = target)

    /** Färbt für alle in [radius] Blöcken kurz den echten Himmel; [strength] 0..1. */
    fun skyMood(world: ServerWorld, style: Style, center: Vec3d, strength: Float, ticks: Int, radius: Float = 48f) =
        emit(world, Kind.SKY_MOOD, style, center, a = strength, b = radius, life = ticks)

    /** Eigene 3D-Wolkenbank, die heranzieht und [height] Blöcke über dem Ort steht; folgt optional einem Wesen. */
    fun clouds(world: ServerWorld, style: Style, center: Vec3d, radius: Float, height: Float, life: Int, follow: Entity? = null) =
        emit(world, Kind.CLOUDS, style, center, a = radius, b = height, life = life, entity = follow)

    /** Lichtstrahlen, die schräg aus dem Himmel auf einen Ort fallen. */
    fun godRays(world: ServerWorld, style: Style, center: Vec3d, radius: Float, height: Float, life: Int) =
        emit(world, Kind.GOD_RAYS, style, center, a = radius, b = height, life = life)

    /** Zeus' Augen, [height] Blöcke über dem Ort im Himmel. */
    fun zeusEyes(world: ServerWorld, center: Vec3d, height: Float, life: Int) =
        emit(world, Kind.ZEUS_EYES, Style.STORM, center, a = height, life = life)

    /** Eine einzelne große Feder, die langsam zu Boden sinkt (Cutscenes). */
    fun fallingFeather(world: ServerWorld, style: Style, start: Vec3d, fall: Float, life: Int) =
        emit(world, Kind.FALLING_FEATHER, style, start, a = fall, life = life)
}
