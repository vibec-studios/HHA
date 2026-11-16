package dev.henny.hha.logic

import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Telegraph
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
 *
 * **Effekt-Budget (Plan 3.3), zentral durchgesetzt**, sobald ein Effekt aus einem Kampf kommt (also während
 * [Telegraph.Fight.during] läuft) und der Empfänger gerade keine Cutscene sieht:
 * - [shake]: Stärke höchstens [SHAKE_MAX], Dauer höchstens [SHAKE_MAX_TICKS]; kein Rütteln, solange für den Spieler
 *   eine Ansage läuft, und kein Warn-Rütteln, das so lang ist wie die Ansage, die im selben Tick beginnt.
 * - [screenFlash]: Stärke höchstens [FLASH_MAX].
 * - [shockwave], [nova], [pillar], [crescent]: höchstens einer davon je Treffer (gleicher Tick, ≤ 3 Blöcke Abstand).
 * - Gold wird nie überschrieben ([styleOverride] lässt Gold im Kampf stehen) und Gefahr-Stile bekommen auf dem Client
 *   ihre Gefahrenfarbe ([FLAG_DANGER], z. B. HEAVEN = Himmelblau-Weiß statt Gold-Weiß).
 * Cutscenes ([Cine.isPlaying]) und Spieler-Fähigkeiten (laufen nie in einem Kampf-Scope) bleiben unberührt.
 */
object Vfx {

    /** Farbwelt eines Effekts. Ordinal = Wire-ID, deshalb nur hinten anfügen. */
    enum class Style {
        HEAVEN, HELL, FIRE, SOUL, STORM, GOLD, SHADOW, HADES,

        /** Kronos: Indigo mit sternweißem Kern (Plan 3.1). Gefahrfarbe des Titanen. */
        AEON,
    }

    /** Effektarten. Ordinal = Wire-ID, deshalb nur hinten anfügen. */
    enum class Kind {
        BEAM, SHOCKWAVE, CRESCENT, BOLT, PILLAR, TELEGRAPH_RING, TELEGRAPH_LINE, SHIELD, WINGS, CHAIN,
        TRAIL, RIFT, NOVA, RUNE, TORNADO, AURA, FLOOD, SPEAR, FEATHERS, FLARE, SHAKE, SCREEN_FLASH,
        ORBIT_RINGS, FALLING_FEATHER, SKY_MOOD, CLOUDS, GOD_RAYS, ZEUS_EYES, FAR_BEACON,

        /** Kegel am Boden: Spitze = Position, Richtung = dir, a = Radius, b = halber Öffnungswinkel in Grad. */
        TELEGRAPH_CONE,
    }

    /** Bit in [VfxPayload.flags]: Effekt gehört zu einem Gegner; der Client nimmt die Gefahrenpalette des Stils. */
    const val FLAG_DANGER = 1

    /** Kamera-Rütteln im Kampf: höchstens so stark (≤ 4 für Angriffe, ≤ 7 für Saalweites, Plan 3.3) … */
    const val SHAKE_MAX = 7f

    /** … und höchstens so lang. */
    const val SHAKE_MAX_TICKS = 12

    /** Rütteln eines normalen Angriffs (Plan 3.3); siehe [Telegraph.Fight.hitShake]. */
    const val SHAKE_HIT_MAX = 4f

    /** Bildschirmblitz im Kampf außerhalb von Cutscenes (Kronos' Stillstand). */
    const val FLASH_MAX = 0.35f

    private const val RANGE = 160.0

    /** Arten, von denen ein Treffer genau eine bekommt (Plan 3.3). */
    private val IMPACT_KINDS = setOf(Kind.SHOCKWAVE, Kind.NOVA, Kind.PILLAR, Kind.CRESCENT)

    /** Himmel, Wolken und Augen bleiben in ihrer eigenen Farbe, auch im Kampf. */
    private val SCENERY_KINDS = setOf(Kind.SKY_MOOD, Kind.CLOUDS, Kind.GOD_RAYS, Kind.ZEUS_EYES, Kind.FAR_BEACON, Kind.SHAKE)

    /** Solange gesetzt, laufen alle Effekte in diesem Stil. So kann ein ganzer Kampf die Farbe wechseln. */
    var styleOverride: Style? = null

    /** Gold bleibt trotz Überschreibung Gold: Im Hades-Kampf heißt Gold immer „hier bist du sicher“. */
    var spareGold = false

    private fun send(world: ServerWorld, center: Vec3d, payload: VfxPayload) {
        if (Cine.muted) return
        for (player in world.players) {
            if (player.squaredDistanceTo(center) <= RANGE * RANGE) {
                ServerPlayNetworking.send(player, payload)
            }
        }
    }

    /** Stil nach Überschreibung. Im Kampf (und mit [spareGold]) bleibt Gold immer Gold. */
    private fun resolve(style: Style): Style {
        val spare = spareGold || Telegraph.current != null
        return if (spare && style == Style.GOLD) style else styleOverride ?: style
    }

    private fun emit(
        world: ServerWorld, kind: Kind, style: Style, pos: Vec3d, dir: Vec3d = Vec3d.ZERO,
        a: Float = 0f, b: Float = 0f, life: Int = 20, entity: Entity? = null, entity2: Entity? = null,
        flags: Int = 0,
    ) {
        var bits = flags
        val fight = Telegraph.current
        if (fight != null && !fight.inCutscene()) {
            if (kind in IMPACT_KINDS && !fight.allowImpact(pos)) return
            if (kind !in SCENERY_KINDS) bits = bits or FLAG_DANGER
        }
        send(
            world, pos,
            VfxPayload(
                kind.ordinal, resolve(style).ordinal, pos.x, pos.y, pos.z, dir.x, dir.y, dir.z, a, b, life,
                entity?.id ?: -1, entity2?.id ?: -1, bits,
            ),
        )
    }

    /** Strahl von [start] nach [end]; [width] skaliert die Dicke. */
    fun beam(world: ServerWorld, style: Style, start: Vec3d, end: Vec3d, width: Float = 1f, life: Int = 14) =
        emit(world, Kind.BEAM, style, start, end, width, life = life)

    /** Bodenschockwelle mit Trümmern. Im Kampf: höchstens ein Flächeneffekt je Treffer. */
    fun shockwave(world: ServerWorld, style: Style, center: Vec3d, radius: Float, life: Int = 18, height: Float = 2.4f) =
        emit(world, Kind.SHOCKWAVE, style, center, a = radius, b = height, life = life)

    /** Fliegende Sichel entlang [dir] über [range] Blöcke. Im Kampf: höchstens ein Flächeneffekt je Treffer. */
    fun crescent(world: ServerWorld, style: Style, origin: Vec3d, dir: Vec3d, range: Float, width: Float = 3f, life: Int = 10) =
        emit(world, Kind.CRESCENT, style, origin, dir, range, width, life)

    /** Verästelter Blitz. */
    fun bolt(world: ServerWorld, style: Style, start: Vec3d, end: Vec3d, width: Float = 1f, life: Int = 8) =
        emit(world, Kind.BOLT, style, start, end, width, life = life)

    /** Lichtsäule. Im Kampf: höchstens ein Flächeneffekt je Treffer. */
    fun pillar(world: ServerWorld, style: Style, center: Vec3d, radius: Float, height: Float, life: Int = 24) =
        emit(world, Kind.PILLAR, style, center, a = radius, b = height, life = life)

    /** Warnkreis am Boden, der sich über [life] Ticks füllt. */
    @Deprecated(
        "Ansagen nur noch über Telegraph (Plan 3.2): Budget, Mindestdauer und Trefferprüfung kommen von dort.",
        ReplaceWith("fight.telegraph.ring(center, radius, life)"),
    )
    fun telegraphRing(world: ServerWorld, style: Style, center: Vec3d, radius: Float, life: Int) =
        telegraphRingRaw(world, style, center, radius, 0f, life)

    /** Warnlinie am Boden; [width] ist hier die **halbe** Breite (alte Bedeutung). */
    @Deprecated(
        "Ansagen nur noch über Telegraph (Plan 3.2): Budget, Mindestdauer und Trefferprüfung kommen von dort.",
        ReplaceWith("fight.telegraph.line(from, to, width * 2f, life)"),
    )
    fun telegraphLine(world: ServerWorld, style: Style, from: Vec3d, to: Vec3d, width: Float, life: Int) =
        telegraphLineRaw(world, style, from, to, width, life)

    /**
     * Ansage-Kreis oder -Ring ohne Budget. Nur für [Telegraph] und die Vorschau (`/hha vfx`).
     * [inner] > 0 macht einen Ring (nur das Band zwischen [inner] und [radius] ist Trefferfläche).
     */
    internal fun telegraphRingRaw(
        world: ServerWorld, style: Style, center: Vec3d, radius: Float, inner: Float, life: Int, forceStyle: Boolean = false,
    ) = emitTelegraph(world, Kind.TELEGRAPH_RING, style, center, Vec3d.ZERO, radius, inner, life, forceStyle)

    /** Ansage-Linie ohne Budget; [halfWidth] = halbe Breite. Nur für [Telegraph] und die Vorschau. */
    internal fun telegraphLineRaw(
        world: ServerWorld, style: Style, from: Vec3d, to: Vec3d, halfWidth: Float, life: Int, forceStyle: Boolean = false,
    ) = emitTelegraph(world, Kind.TELEGRAPH_LINE, style, from, to, halfWidth, 0f, life, forceStyle)

    /** Ansage-Kegel ohne Budget; [halfAngle] in Grad. Nur für [Telegraph] und die Vorschau. */
    internal fun telegraphConeRaw(
        world: ServerWorld, style: Style, apex: Vec3d, direction: Vec3d, radius: Float, halfAngle: Float, life: Int,
        forceStyle: Boolean = false,
    ) {
        val flat = Vec3d(direction.x, 0.0, direction.z).let { if (it.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
        emitTelegraph(world, Kind.TELEGRAPH_CONE, style, apex, flat, radius, halfAngle.coerceIn(1f, 180f), life, forceStyle)
    }

    /** Ansagen sind immer Gefahr (außer Gold = sicher); [forceStyle] umgeht [styleOverride] (sichere Flächen). */
    private fun emitTelegraph(
        world: ServerWorld, kind: Kind, style: Style, pos: Vec3d, dir: Vec3d, a: Float, b: Float, life: Int, forceStyle: Boolean,
    ) {
        val final = if (forceStyle) style else resolve(style)
        val flags = if (final == Style.GOLD) 0 else FLAG_DANGER
        send(world, pos, VfxPayload(kind.ordinal, final.ordinal, pos.x, pos.y, pos.z, dir.x, dir.y, dir.z, a, b, life, -1, -1, flags))
    }

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

    /** Lichtausbruch. Im Kampf: höchstens ein Flächeneffekt je Treffer. */
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

    /**
     * Kameraschütteln für alle in [radius] Blöcken um [center], weicher zum Rand hin.
     *
     * Im Kampf (siehe Klassenbeschreibung) begrenzt auf [SHAKE_MAX]/[SHAKE_MAX_TICKS] und unterdrückt, solange für den
     * Empfänger eine Ansage läuft. Wer gerade eine Cutscene sieht, bekommt den Wert unverändert.
     */
    fun shake(world: ServerWorld, center: Vec3d, intensity: Float, ticks: Int, radius: Float = 32f) {
        if (Cine.muted) return
        val fight = Telegraph.current
        for (player in world.players) {
            if (player.squaredDistanceTo(center) > RANGE * RANGE) continue
            var strength = intensity
            var duration = ticks
            if (fight != null && !Cine.isPlaying(player)) {
                if (!fight.allowShake(player, ticks)) continue
                strength = strength.coerceAtMost(SHAKE_MAX)
                duration = duration.coerceAtMost(SHAKE_MAX_TICKS)
            }
            ServerPlayNetworking.send(
                player,
                VfxPayload(Kind.SHAKE.ordinal, Style.GOLD.ordinal, center.x, center.y, center.z, 0.0, 0.0, 0.0, strength, radius, duration, -1, -1),
            )
        }
    }

    /** Bildschirmblitz nur für [player]. Im Kampf außerhalb von Cutscenes höchstens [FLASH_MAX]. */
    fun screenFlash(player: ServerPlayerEntity, style: Style, strength: Float, ticks: Int) {
        if (Cine.muted) return
        val capped = if (Telegraph.current != null && !Cine.isPlaying(player)) strength.coerceAtMost(FLASH_MAX) else strength
        ServerPlayNetworking.send(
            player,
            VfxPayload(
                Kind.SCREEN_FLASH.ordinal, style.ordinal, player.x, player.y, player.z,
                0.0, 0.0, 0.0, capped, 0f, ticks, -1, -1,
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

    /** Die Säule am Horizont, nur für [player]; [fake] heißt: Hermes lügt gerade. */
    fun farBeacon(player: ServerPlayerEntity, pos: Vec3d, life: Int, fake: Boolean) {
        ServerPlayNetworking.send(
            player,
            VfxPayload(Kind.FAR_BEACON.ordinal, Style.HADES.ordinal, pos.x, pos.y, pos.z, 0.0, 0.0, 0.0, if (fake) 1f else 0f, 0f, life, -1, -1),
        )
    }

    /** Zeus' Augen, [height] Blöcke über dem Ort im Himmel. */
    fun zeusEyes(world: ServerWorld, center: Vec3d, height: Float, life: Int) =
        emit(world, Kind.ZEUS_EYES, Style.STORM, center, a = height, life = life)

    /** Eine einzelne große Feder, die langsam zu Boden sinkt (Cutscenes). */
    fun fallingFeather(world: ServerWorld, style: Style, start: Vec3d, fall: Float, life: Int) =
        emit(world, Kind.FALLING_FEATHER, style, start, a = fall, life = life)
}
