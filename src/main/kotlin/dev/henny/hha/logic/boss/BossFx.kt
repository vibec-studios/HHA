package dev.henny.hha.logic.boss

import dev.henny.hha.Hha
import dev.henny.hha.logic.Vfx
import dev.henny.hha.net.FxPayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.entity.Entity
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.util.math.Vec3d
import java.util.Locale

/**
 * Serverseite der neuen Effekte (Überarbeitung 3, PLAN 6.2): Jeder Aufruf schickt ein [FxPayload] mit einer **Text-ID**
 * an die Spieler in Sichtweite; der Client baut daraus den Effekt (`client/vfx/fx/FxRegistry`). Nach Welle 0
 * eingefroren. Die Kodierung jeder `core.*`-ID in `dir`, `a`–`d`, `entity`, `entity2` und `extra` steht in der Tabelle
 * „BossFx-Kodierung“ in `tmp/overhaul3/API.md`; sie ist zugleich in den KDoc-Zeilen der Helfer genannt.
 *
 * **Regeln, zentral durchgesetzt** (wie `Vfx.emit`):
 * - Nichts, solange [Cine.muted] (Vorspulen).
 * - Im Kampf-Scope ([Telegraph.current]) ohne laufende Szene bekommen Effekte [Vfx.FLAG_DANGER], außer Gold. Gold
 *   bleibt im Kampf Gold, sonst gilt [Vfx.styleOverride] wie im Bestand.
 * - `impact = true` (Flächeneffekt des Treffers) fragt [Telegraph.Fight.allowImpact]: höchstens einer je Treffer,
 *   zusammen mit `Vfx.shockwave/nova/pillar/crescent`.
 * - [kick] fragt [Telegraph.Fight.allowShake] und deckelt je Achse: Kampf [KICK_MAX_FIGHT]/[KICK_MAX_TICKS], Szene
 *   [KICK_MAX_CINE], sonst [KICK_MAX_FREE]/[KICK_MAX_TICKS].
 * - [debris], [cracks], [sparks] deckeln die Zahl ([DEBRIS_PER_CALL], [CRACKS_PER_CALL], [SPARKS_PER_CALL]),
 *   [debrisPull] auf [DEBRIS_PULL_MAX].
 * - [screen] deckelt Stärke und Dauer nach PLAN 6.3 ([screenMaxStrength], [screenMaxTicks]), [vignette] auf
 *   [VIGNETTE_MAX].
 * - Reichweite [RANGE] Blöcke (wie `Vfx`).
 */
object BossFx {

    /** Bild eines Projektils ([projectile]) bzw. der Tropfen ([drip]). Ordinal = Wire-ID: nur hinten anfügen. */
    enum class Look { ROCK_DUST, HOT_IRON, TORCH_FLAME, VENOM, KERAUNOS, SOUL }

    /** Art des Bildschirm-Passes ([screen], PLAN 6.3). Ordinal = Wire-ID: nur hinten anfügen. */
    enum class Screen { SHOCK_RIPPLE, HEAT_HAZE, ECLIPSE, TIME_STOP, GRAVITY_LENS }

    /** Kamera-Ruck im Kampf: höchstens so viele Grad je Achse … */
    const val KICK_MAX_FIGHT = 2.5f

    /** … in einer Szene (Empfänger sieht eine Cutscene) … */
    const val KICK_MAX_CINE = 4f

    /** … außerhalb von Kampf und Szene (Befehle, Fähigkeiten, Rätsel). */
    const val KICK_MAX_FREE = 1.5f

    /** Ruck-Dauer im Kampf und außerhalb von Szenen höchstens so viele Ticks. */
    const val KICK_MAX_TICKS = 8

    /** Beiwerk je Aufruf (PLAN 2.2). */
    const val DEBRIS_PER_CALL = 8
    const val CRACKS_PER_CALL = 6
    const val SPARKS_PER_CALL = 14

    /** Sog-Trümmer (Kronos, Verschlingen) je Aufruf. Am Client gilt zusätzlich `BlockDebris.MAX_LIVE` (24). */
    const val DEBRIS_PULL_MAX = 12

    /** Vignette höchstens so stark (P02: 0,35). */
    const val VIGNETTE_MAX = 0.35f

    /** Ein Projektil-Mantel lebt höchstens so lange (der Client beendet ihn früher, sobald das Wesen weg ist). */
    const val PROJECTILE_MAX_LIFE = 600

    /** Nach Aufstieg und Halten zerspringt die Säule über so viele Ticks ([pillarShatter]). */
    const val PILLAR_SHATTER_TICKS = 20

    /** Reichweite aller Sendungen (wie `Vfx`). */
    const val RANGE = 160.0

    private val UP = Vec3d(0.0, 1.0, 0.0)

    // ---------------------------------------------------------------------------------------------
    // Allgemein
    // ---------------------------------------------------------------------------------------------

    /**
     * Beliebiger Effekt per Text-ID (Präfixe PLAN 6.4). [impact] = zählt als der eine Flächeneffekt des Treffers.
     * [to] = nur dieser Spieler (sonst alle in [RANGE] um [pos]).
     */
    fun play(
        world: ServerWorld, id: String, style: Vfx.Style, pos: Vec3d, dir: Vec3d = Vec3d.ZERO,
        a: Float = 0f, b: Float = 0f, c: Float = 0f, d: Float = 0f, life: Int = 20,
        entity: Entity? = null, entity2: Entity? = null, impact: Boolean = false,
        extra: String = "", to: ServerPlayerEntity? = null,
    ) {
        if (Cine.muted) return
        var flags = 0
        val resolved = resolve(style)
        val fight = Telegraph.current
        if (fight != null && !fight.inCutscene()) {
            if (impact && !fight.allowImpact(pos)) return
            if (resolved != Vfx.Style.GOLD) flags = flags or Vfx.FLAG_DANGER
        }
        if (impact) flags = flags or FxPayload.FLAG_IMPACT
        val payload = FxPayload(
            id, resolved.ordinal, pos.x, pos.y, pos.z, dir.x, dir.y, dir.z, a, b, c, d, life.coerceAtLeast(1),
            entity?.id ?: -1, entity2?.id ?: -1, flags, extra,
        )
        if (to != null) {
            if (!to.isDisconnected) ServerPlayNetworking.send(to, payload)
            return
        }
        for (player in world.players) {
            if (player.squaredDistanceTo(pos) <= RANGE * RANGE) ServerPlayNetworking.send(player, payload)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Bibliothek core.* (Client: P03)
    // ---------------------------------------------------------------------------------------------

    /** `core.arc`: Energiebogen. pos = [from], dir = [to] (absoluter Punkt), a = bend, b = width, c = strands. */
    fun arc(
        world: ServerWorld, style: Vfx.Style, from: Vec3d, to: Vec3d, bend: Float = 0.25f, width: Float = 0.5f,
        life: Int = 12, strands: Int = 1, fromEntity: Entity? = null, toEntity: Entity? = null,
    ) = play(
        world, "core.arc", style, from, to, bend, width, strands.coerceIn(1, 2).toFloat(), life = life,
        entity = fromEntity, entity2 = toEntity,
    )

    /** `core.crack`: Bodensprung. pos = [from], dir = Richtung (Einheitsvektor), a = length, b = grow. */
    fun crack(world: ServerWorld, style: Vfx.Style, from: Vec3d, dir: Vec3d, length: Float, life: Int = 60, grow: Int = 8) =
        play(world, "core.crack", style, from, unit(dir), length, grow.toFloat(), life = life)

    /** `core.cracks`: sternförmige Risse. pos = [center], a = radius, b = count (≤ [CRACKS_PER_CALL]). */
    fun cracks(world: ServerWorld, style: Vfx.Style, center: Vec3d, radius: Float, count: Int = 6, life: Int = 60) =
        play(world, "core.cracks", style, center, a = radius, b = count.coerceIn(0, CRACKS_PER_CALL).toFloat(), life = life)

    /**
     * `core.debris`: echte Blöcke fliegen (nur optisch). pos = [center], a = radius, b = count (≤ [DEBRIS_PER_CALL]),
     * c = up, d = out. Stil = Kampfstil (ohne Bedeutung für die Blöcke selbst).
     */
    fun debris(world: ServerWorld, center: Vec3d, radius: Float, count: Int = 6, up: Float = 0.55f, out: Float = 0.25f) =
        play(
            world, "core.debris", fightStyle(), center,
            a = radius, b = count.coerceIn(0, DEBRIS_PER_CALL).toFloat(), c = up, d = out, life = 80,
        )

    /** `core.debris_pull`: Sog. pos = [center], dir = [target] (absolut), a = radius, b = count (≤ [DEBRIS_PULL_MAX]), life = [ticks]. */
    fun debrisPull(world: ServerWorld, center: Vec3d, radius: Float, target: Vec3d, ticks: Int, count: Int = 12) =
        play(
            world, "core.debris_pull", fightStyle(), center, target,
            a = radius, b = count.coerceIn(0, DEBRIS_PULL_MAX).toFloat(), life = ticks,
        )

    /** `core.sparks`: Funken. pos = [center], dir = Richtung (Einheitsvektor), a = count (≤ [SPARKS_PER_CALL]), b = speed. */
    fun sparks(
        world: ServerWorld, style: Vfx.Style, center: Vec3d, dir: Vec3d = UP, count: Int = 10, speed: Float = 0.5f,
        life: Int = 18,
    ) = play(world, "core.sparks", style, center, unit(dir), count.coerceIn(0, SPARKS_PER_CALL).toFloat(), speed, life = life)

    /** `core.swirl`: Wirbel. pos = [center], dir = [normal] (Einheitsvektor), a = radius, b = 1 schließend / 0 öffnend. */
    fun swirl(world: ServerWorld, style: Vfx.Style, center: Vec3d, normal: Vec3d, radius: Float, life: Int, closing: Boolean = false) =
        play(world, "core.swirl", style, center, unit(normal), radius, if (closing) 1f else 0f, life = life)

    /** `core.charge`: zieht sich auf [target] zusammen. pos = Lage von [target], a = radius, entity = [target]. */
    fun charge(world: ServerWorld, style: Vfx.Style, target: Entity, radius: Float, life: Int) =
        play(world, "core.charge", style, target.entityPos, a = radius, life = life, entity = target)

    /**
     * `core.pillar_shatter` (Flächeneffekt): pos = [base], a = radius, b = height, c = rise, d = hold;
     * life = rise + hold + [PILLAR_SHATTER_TICKS].
     */
    fun pillarShatter(world: ServerWorld, style: Vfx.Style, base: Vec3d, radius: Float, height: Float, rise: Int, hold: Int) =
        play(
            world, "core.pillar_shatter", style, base, a = radius, b = height, c = rise.toFloat(), d = hold.toFloat(),
            life = rise + hold + PILLAR_SHATTER_TICKS, impact = true,
        )

    /**
     * `core.wave_wall`: Wand von [from] nach [to] (dir = [to], absolut), läuft [travel] Blöcke quer; a = travel,
     * b = height. extra = Lücken als `start:ende;start:ende` (Anteile 0..1 entlang from→to, Punkt als Dezimaltrenner).
     */
    fun waveWall(
        world: ServerWorld, style: Vfx.Style, from: Vec3d, to: Vec3d, travel: Float, height: Float, life: Int,
        gaps: List<Pair<Float, Float>> = emptyList(),
    ) = play(world, "core.wave_wall", style, from, to, travel, height, life = life, extra = encodeGaps(gaps))

    /** `core.sky_strike` (Flächeneffekt): pos = [ground], a = radius, b = height, c = delay; life = Dauer nach dem Riss. */
    fun skyStrike(world: ServerWorld, style: Vfx.Style, ground: Vec3d, radius: Float, height: Float, delay: Int, life: Int = 16) =
        play(world, "core.sky_strike", style, ground, a = radius, b = height, c = delay.toFloat(), life = delay + life, impact = true)

    /** `core.tear`: Riss in der Luft. pos = [center], dir = [along] (Einheitsvektor), a = length, b = width, c = open. */
    fun tear(world: ServerWorld, style: Vfx.Style, center: Vec3d, along: Vec3d, length: Float, width: Float, life: Int, open: Int = 8) =
        play(world, "core.tear", style, center, unit(along), length, width, open.toFloat(), life = life)

    /** `core.dome`: Fresnel-Schale. pos = [center], a = radius, b = 1 zerspringt am Ende, entity = [follow]. */
    fun dome(world: ServerWorld, style: Vfx.Style, center: Vec3d, radius: Float, life: Int, follow: Entity? = null, shatter: Boolean = false) =
        play(world, "core.dome", style, center, a = radius, b = if (shatter) 1f else 0f, life = life, entity = follow)

    /**
     * `core.projectile`: Mantel und Spur an [projectile]. pos = dessen Lage, a = impactRadius, b = [Look]-Ordinal,
     * entity = [projectile], life = [PROJECTILE_MAX_LIFE] (endet am Client, sobald das Wesen weg ist).
     */
    fun projectile(world: ServerWorld, style: Vfx.Style, projectile: Entity, look: Look, impactRadius: Float = 0f) =
        play(
            world, "core.projectile", style, projectile.entityPos, a = impactRadius, b = look.ordinal.toFloat(),
            life = PROJECTILE_MAX_LIFE, entity = projectile,
        )

    /** `core.drip`: Tropfen und Lachen. pos = [center], a = radius, b = count, c = [Look]-Ordinal. */
    fun drip(world: ServerWorld, style: Vfx.Style, center: Vec3d, radius: Float, count: Int, life: Int, look: Look = Look.HOT_IRON) =
        play(world, "core.drip", style, center, a = radius, b = count.coerceAtLeast(0).toFloat(), c = look.ordinal.toFloat(), life = life)

    // ---------------------------------------------------------------------------------------------
    // Kamera und Bildschirm (Client: P02)
    // ---------------------------------------------------------------------------------------------

    /**
     * `core.kick`: gerichteter Kamera-Ruck für alle in [radius] um [center] (Abfall zum Rand am Client).
     * pos = [center], a = pitch, b = yaw, c = roll (Grad, gedeckelt), d = radius, life = ticks. Je Empfänger gedeckelt;
     * im Kampf nicht während einer laufenden Ansage ([Telegraph.Fight.allowShake]).
     */
    fun kick(world: ServerWorld, center: Vec3d, pitch: Float, yaw: Float = 0f, roll: Float = 0f, ticks: Int = 6, radius: Float = 32f) {
        if (Cine.muted) return
        for (player in world.players) {
            if (player.squaredDistanceTo(center) > radius.toDouble() * radius) continue
            sendKick(player, center, pitch, yaw, roll, ticks, radius)
        }
    }

    /** `core.kick` nur für [player], ohne Entfernungsabfall (d = 0). */
    fun kick(player: ServerPlayerEntity, pitch: Float, yaw: Float = 0f, roll: Float = 0f, ticks: Int = 6) {
        if (Cine.muted) return
        sendKick(player, player.entityPos, pitch, yaw, roll, ticks, 0f)
    }

    private fun sendKick(player: ServerPlayerEntity, center: Vec3d, pitch: Float, yaw: Float, roll: Float, ticks: Int, radius: Float) {
        val fight = Telegraph.current
        val cap: Float
        var duration = ticks.coerceAtLeast(1)
        if (Cine.isPlaying(player)) {
            cap = KICK_MAX_CINE
        } else if (fight != null) {
            if (!fight.allowShake(player, duration)) return
            cap = KICK_MAX_FIGHT
            duration = duration.coerceAtMost(KICK_MAX_TICKS)
        } else {
            cap = KICK_MAX_FREE
            duration = duration.coerceAtMost(KICK_MAX_TICKS)
        }
        play(
            player.entityWorld as ServerWorld, "core.kick", Vfx.Style.GOLD, center,
            a = pitch.coerceIn(-cap, cap), b = yaw.coerceIn(-cap, cap), c = roll.coerceIn(-cap, cap), d = radius,
            life = duration, to = player,
        )
    }

    /** Höchste Stärke eines Bildschirm-Passes (PLAN 6.3, vor `distortionEffectScale`). */
    fun screenMaxStrength(kind: Screen): Float = when (kind) {
        Screen.SHOCK_RIPPLE -> 0.6f
        Screen.HEAT_HAZE -> 0.35f
        Screen.ECLIPSE -> 0.7f
        Screen.TIME_STOP -> 0.8f
        Screen.GRAVITY_LENS -> 1f
    }

    /**
     * Längste Dauer eines Bildschirm-Passes in Ticks (PLAN 6.3). Die drei Kampf-Pässe decken die Dauer ihres Angriffs mit
     * Reserve: Mondfinsternis (W 50, verlängerbar, plus Halten), `HALT` (Treffer bei 70), `DEVOUR` (Ende bei 96).
     */
    fun screenMaxTicks(kind: Screen): Int = when (kind) {
        Screen.SHOCK_RIPPLE -> 10
        Screen.HEAT_HAZE -> 200
        Screen.ECLIPSE -> 240
        Screen.TIME_STOP -> 120
        Screen.GRAVITY_LENS -> 160
    }

    /**
     * `core.screen`: Bildschirm-Pass für alle in [range] um [center]. pos = [center], a = radius, b = strength
     * (gedeckelt), c = safeRadius, d = [Screen]-Ordinal, life = ticks (gedeckelt), entity = [follow].
     */
    fun screen(
        world: ServerWorld, kind: Screen, style: Vfx.Style, center: Vec3d, radius: Float, strength: Float, ticks: Int,
        follow: Entity? = null, safeRadius: Float = 0f, range: Float = 64f,
    ) {
        if (Cine.muted) return
        val s = strength.coerceIn(0f, screenMaxStrength(kind))
        val t = ticks.coerceIn(1, screenMaxTicks(kind))
        for (player in world.players) {
            if (player.squaredDistanceTo(center) > range.toDouble() * range) continue
            play(
                world, "core.screen", style, center, a = radius, b = s, c = safeRadius, d = kind.ordinal.toFloat(),
                life = t, entity = follow, to = player,
            )
        }
    }

    /** `core.screen_clear`: beendet den Pass [kind] bei allen in [range] um [center]. pos = [center], d = [Screen]-Ordinal. */
    fun screenClear(world: ServerWorld, center: Vec3d, kind: Screen, range: Float = 64f) {
        if (Cine.muted) return
        for (player in world.players) {
            if (player.squaredDistanceTo(center) > range.toDouble() * range) continue
            play(world, "core.screen_clear", Vfx.Style.GOLD, center, d = kind.ordinal.toFloat(), life = 1, to = player)
        }
    }

    /** `core.vignette` nur für [player]: a = strength (≤ [VIGNETTE_MAX]), b = 1 Herzschlag, life = ticks. */
    fun vignette(player: ServerPlayerEntity, style: Vfx.Style, strength: Float, ticks: Int, beat: Boolean = false) =
        play(
            player.entityWorld as ServerWorld, "core.vignette", style, player.entityPos,
            a = strength.coerceIn(0f, VIGNETTE_MAX), b = if (beat) 1f else 0f, life = ticks, to = player,
        )

    /** `core.time_scale`: Zeitfaktor laufender Effekte (0 = eingefroren, 1 = normal) für alle in [radius]. a = scale, life = ticks. */
    fun timeScale(world: ServerWorld, center: Vec3d, scale: Float, ticks: Int, radius: Float = 64f) {
        if (Cine.muted) return
        for (player in world.players) {
            if (player.squaredDistanceTo(center) > radius.toDouble() * radius) continue
            play(world, "core.time_scale", Vfx.Style.GOLD, center, a = scale.coerceIn(0f, 1f), life = ticks, to = player)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Hilfen
    // ---------------------------------------------------------------------------------------------

    /** Alle `core.*`-IDs, die [BossFx] sendet (Vorschläge für `/hha fx play`, Prüfliste in `API.md`). */
    val CORE_IDS: List<String> = listOf(
        "core.arc", "core.crack", "core.cracks", "core.debris", "core.debris_pull", "core.sparks", "core.swirl",
        "core.charge", "core.pillar_shatter", "core.wave_wall", "core.sky_strike", "core.tear", "core.dome",
        "core.projectile", "core.drip", "core.kick", "core.screen", "core.screen_clear", "core.vignette",
        "core.time_scale",
    )

    /** Stil nach Überschreibung, wie `Vfx.resolve`: Im Kampf (und mit [Vfx.spareGold]) bleibt Gold immer Gold. */
    private fun resolve(style: Vfx.Style): Vfx.Style {
        val spare = Vfx.spareGold || Telegraph.current != null
        return if (spare && style == Vfx.Style.GOLD) style else Vfx.styleOverride ?: style
    }

    private fun fightStyle(): Vfx.Style = Telegraph.current?.style ?: Vfx.Style.HEAVEN

    private fun unit(v: Vec3d): Vec3d = if (v.lengthSquared() < 1.0e-8) UP else v.normalize()

    /** Lücken der Wellenwand als `start:ende;…` (Locale.ROOT, damit überall ein Punkt steht). */
    fun encodeGaps(gaps: List<Pair<Float, Float>>): String =
        gaps.joinToString(";") { (s, e) -> String.format(Locale.ROOT, "%.4f:%.4f", s.coerceIn(0f, 1f), e.coerceIn(0f, 1f)) }

    /** Gegenstück zu [encodeGaps] (für den Client); fehlerhafte Einträge fallen weg und werden einmal geloggt. */
    fun decodeGaps(extra: String): List<Pair<Float, Float>> {
        if (extra.isBlank()) return emptyList()
        return extra.split(';').mapNotNull { part ->
            val bits = part.split(':')
            val s = bits.getOrNull(0)?.toFloatOrNull()
            val e = bits.getOrNull(1)?.toFloatOrNull()
            if (s == null || e == null) {
                Hha.LOGGER.warn("BossFx: unlesbare Lücke '{}' in '{}'", part, extra)
                null
            } else {
                s to e
            }
        }
    }
}
