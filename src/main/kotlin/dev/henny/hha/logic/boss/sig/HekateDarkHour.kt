package dev.henny.hha.logic.boss.sig

import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.olymp.HekateAction
import dev.henny.hha.logic.Fx
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.ArenaCollapse
import dev.henny.hha.logic.boss.ArenaNav
import dev.henny.hha.logic.boss.Arenas
import dev.henny.hha.logic.boss.BossDefense
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.logic.boss.Cine
import dev.henny.hha.logic.boss.Cover
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha.logic.boss.timings.HekateTimings
import dev.henny.hha.world.arena.ArenaSpec
import dev.henny.hha.world.arena.Part
import dev.henny.hha.world.arena.Sector
import dev.henny.hha.world.olymp.OlympusArenas
import dev.henny.hha.world.olymp.OlympusLayout
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.entity.LivingEntity
import net.minecraft.particle.ParticleTypes
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Box
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Hekate: „Die dunkle Stunde“, die Schleichphase des Mods (Überarbeitung 4, PLAN 4.1 Zeile „Schleichphase“, 4.2;
 * Besitzer P15). Ersetzt in Phase III die Mondfinsternis als Saal-Gefahr.
 *
 * Ablauf einer Stunde ([HekateTimings.DARK_HOUR_TICKS] = 200 Ticks), eine einzige Saal-Gefahr-Gruppe:
 * 1. **Beginn:** Pass „eclipse“ wie bisher (über [Host.startPass], abschaltbar, Rückfall Vignette), die Fackeln der Arena
 *    „erlöschen“ (nur Rauch an den Feuerstellen, keine Blöcke), Titelzeile einmal je Kampf, Zeile, Hinweis einmal je
 *    Kampf. Sie tritt in einem Wirbel an das Hekataion ([HekateTimings.DARK_HOUR_SETUP]) und greift nicht an.
 * 2. **Drei Schwenks** ([sweep]): Richtung auf den Schwerpunkt der Spieler, die noch nicht im Kegel waren, sonst 120°
 *    im Uhrzeigersinn weiter ([chooseDirection]). Ansage [HekateTimings.GAZE_W] mit zwei Kegeln derselben Gruppe (außen
 *    r 18 für alle, die nicht schleichen; innen r 9 für alle; 60° halb), darunter der kalte Lichtkegel `hekate.gaze`;
 *    dann steht der Blick [HekateTimings.GAZE_HOLD] Ticks und prüft alle [HekateTimings.GAZE_CHECK_EVERY] Ticks.
 * 3. **Gewittert** ([check]) ist, wer im geltenden Kegel steht und von ihrem Auge aus nicht hinter benannter Deckung
 *    gegen `hekate.gaze` steht ([Cover.shelter]). Kein Höhenband: Der Blick reicht auf die Terrassen. Folge: eigene
 *    Kreis-Ansage r 3 unter dem Spieler ([scent], folgt ihm nicht), 20 Ticks, 18 Schaden; je Spieler höchstens einmal je
 *    Schwenk (gewittert und getroffen).
 * 4. **Ende** ([finish]): Wurde niemand gewittert, flammen die Fackeln auf (`hekate.dawn`), sie ist geblendet:
 *    offen [HekateTimings.DAWN_F] mit Rune an ihr. Jeder Fackelturm, der in dieser Stunde jemanden gedeckt hat,
 *    bröckelt auf Stumpf 2 ([crumbleTowers]); Tore und Hekataion bleiben. Rückkehr erst mit dem Kampfende
 *    (`Arenas.close` über das Edit-Register).
 *
 * Keine Blindheit, keine Dunkelheit als Statuseffekt, keine Langsamkeit. Ansagen und Gold zeichnen nach dem Pass
 * (`VfxManager`: Telegraphs sind Effekte mit eigener Phase; `hekate.gaze` zeichnet in `over`, also über dem Pass).
 *
 * Alle Ausgänge (Sieg, Abbruch, leere Arena, `boss stop`, Serverstopp, Leine/Neustart) laufen über die Kampf-Datei:
 * [State.abort] vergisst die laufende Stunde, der Pass endet dort, die Arena stellt die Türme beim Schließen wieder her.
 */
object HekateDarkHour {

    /** Nichts anzumelden: Die Stunde läuft im Drehbuch der Hekate, die Bilder meldet `HekateFx` am Client an. */
    fun init() {}

    /** Angriffs-ID im Vertrag (`CoverSpot.protects` der Tore, Türme und des Hekataion). */
    const val ATTACK = OlympusArenas.HEKATE_GAZE

    // --- Effekt-IDs (Client: `client/vfx/fx/boss/HekateFx.kt`) ------------------------------------------------------
    /** Kalter Lichtkegel am Boden unter der Ansage: pos = Spitze, dir = Blick, a = r außen, b = halber Winkel, c = r innen, d = Ansage, life = Ansage + Blick. */
    const val FX_GAZE = "hekate.gaze"
    /** Eine Feuerstelle flammt auf: pos = Feuer, a = Höhe der Flamme, life. */
    const val FX_DAWN = "hekate.dawn"

    // --- Texte ---------------------------------------------------------------------------------------------------------
    const val TITLE = "hha.hekate.dark_hour.title"
    const val TITLE_SUB = "hha.hekate.dark_hour.sub"
    const val HINT = "hha.hekate.hint.dark_hour"
    const val HINT_DAWN = "hha.hekate.hint.dawn"
    const val SCENT_LINE = "hha.hekate.dark_hour.scent"

    // --- Darstellung (reine Bildgrößen, keine Kampfzahlen) -----------------------------------------------------------
    const val TITLE_TICKS = 50
    /** Der Pass klingt so lange nach (endet sonst am Ende der Stunde). */
    const val PASS_TAIL = 20
    /** Rauch an jeder Feuerstelle beim Erlöschen. */
    const val DOUSE_SMOKE = 8
    /** Wirbel beim Wechsel an das Hekataion. */
    const val SWIRL_HEIGHT = 1.4
    const val SWIRL_RADIUS = 1.6f
    /** Im Wirbel wechselt sie nach so vielen Ticks den Ort. */
    const val SETUP_SWAP = 5
    /** Aufflammen: Höhe der Flamme, Dauer. */
    const val DAWN_FLAME = 2.6f
    const val DAWN_TICKS = 40
    /** Zwischen zwei bröckelnden Türmen so viele Ticks (Ton-Budget: höchstens zwei Töne je Tick). */
    const val CRUMBLE_GAP = 6
    /** Ein Schwerpunkt näher als so viel an der Mitte zeigt keine Richtung. */
    const val MIN_AIM = 1.5
    /** Statuszeilen so oft (Ticks) neu bestimmen (wie `Cover.MARK_EVERY`). */
    const val STATUS_EVERY = 5
    /** Präfix der Fackeltürme in `OlympusArenas.C`. */
    const val TOWER_PREFIX = "tower_"

    /**
     * Was die Stunde von der Kampf-Datei braucht. `HekateEntity` liefert das als inneres Objekt (geschützte Glieder wie
     * `strike`, `open`, `hint` bleiben so in der Kampf-Datei).
     */
    interface Host {
        val world: ServerWorld
        val boss: LivingEntity
        val telegraph: Telegraph.Fight
        val arena: Arenas.Arena?
        val style: Vfx.Style
        fun fighters(): List<ServerPlayerEntity>
        fun viewers(): List<ServerPlayerEntity>
        /** Mitte des Platzes (Mitte des Hekataion, y = Boden). */
        fun plaza(): Vec3d
        fun floorY(): Double
        fun strike(player: ServerPlayerEntity, base: Float, shape: Telegraph.Shape): Boolean
        fun open(ticks: Int)
        fun hint(key: String)
        fun say(key: String)
        fun act(action: Int)
        fun face(dir: Vec3d?)
        fun stop()
        fun place(pos: Vec3d)
        fun startPass(ticks: Int)
        fun endPass()
        /** Saal-Gefahr vorbei (für `HAZARD_GAP`). */
        fun hazardEnded()
    }

    /** Was über eine Stunde hinaus gilt (je Kampf). */
    class State {
        /** Titelzeile schon gezeigt? */
        var titled = false

        /** Schon gebröckelte Türme (bis Kampfende). */
        val crumbled = HashSet<String>()

        /** Die laufende Stunde, sonst `null`. */
        var current: Hour? = null
            internal set

        /** Läuft gerade eine Stunde? */
        val running: Boolean get() = current != null

        /** Ausgang mitten in der Stunde: vergessen (Pass und Gruppe nimmt die Kampf-Datei zurück). */
        fun abort() {
            current = null
        }
    }

    /** Zustand einer Stunde. */
    class Hour(val start: Long) {
        /** Wer in dieser Stunde schon im (äußeren) Kegel stand. */
        val seen = HashSet<UUID>()

        /** Wer in dieser Stunde gewittert wurde (leer = Belohnung). */
        val scented = HashSet<UUID>()

        /** Fackeltürme, die in dieser Stunde jemanden gedeckt haben (in den Prüfticks gemerkt). */
        val towers = LinkedHashSet<String>()

        /** Laufende Witterungs-Ansagen. */
        val scents = ArrayList<Scent>()

        /** Wer je Schwenk schon Witterungs-Schaden nahm. */
        val struck = HashMap<Int, HashSet<UUID>>()

        var lastDir: Vec3d? = null
        var sweep = 0
        var lineSaid = false
    }

    /** Eine Witterungs-Ansage unter einem Spieler, gehört zum Schwenk [sweep]. */
    class Scent(val ring: Telegraph.Ring, val sweep: Int)

    /**
     * Beginnt eine Stunde, wenn der Platz für eine Saal-Gefahr frei ist; sonst `null` (dann später erneut). Die Gruppe
     * hält den Platz die ganze Stunde.
     */
    fun start(host: Host, state: State): Sequence<Unit>? {
        if (state.running) return null
        val hazard = host.telegraph.hazard(reserve = HekateTimings.DARK_HOUR_TICKS) ?: return null
        val hour = Hour(host.world.time)
        state.current = hour
        return hour(host, state, hour, hazard)
    }

    private fun hour(host: Host, state: State, hour: Hour, hazard: Telegraph.Fight.Group): Sequence<Unit> = sequence {
        val world = host.world
        val end = hour.start + HekateTimings.DARK_HOUR_TICKS
        // 1. Beginn
        host.stop()
        host.act(HekateAction.DARK_HOUR)
        host.startPass(HekateTimings.DARK_HOUR_TICKS + PASS_TAIL)
        douse(host)
        if (!state.titled) {
            state.titled = true
            Cine.title(host.viewers(), TITLE, TITLE_SUB, TITLE_TICKS)
        }
        host.say("dark_hour")
        host.hint(HINT)
        host.telegraph.sound(HhaSounds.HOWL, host.boss.entityPos, 2.4f, 0.6f)
        val first = chooseDirection(host, hour)
        yieldAll(setup(host, standSpot(host, first), first))
        // 2. Drei Schwenks
        for (k in 0 until HekateTimings.GAZE_SWEEPS) {
            if (state.current !== hour) return@sequence
            val dir = if (k == 0) first else chooseDirection(host, hour)
            yieldAll(sweep(host, hour, hazard, dir, glide = k > 0))
        }
        // Rest der Stunde: Stille, offene Witterungen fallen noch.
        while (world.time < end) {
            if (state.current !== hour) return@sequence
            tickScents(host, hour)
            yield(Unit)
        }
        yieldAll(finish(host, state, hour, hazard))
    }

    /** Wirbel an ihrem Platz, Wechsel an den Standplatz, Wirbel dort ([HekateTimings.DARK_HOUR_SETUP] Ticks). */
    private fun setup(host: Host, spot: Vec3d, dir: Vec3d): Sequence<Unit> = sequence {
        val world = host.world
        val up = Vec3d(0.0, 1.0, 0.0)
        BossFx.swirl(world, host.style, host.boss.entityPos.add(0.0, SWIRL_HEIGHT, 0.0), up, SWIRL_RADIUS, SETUP_SWAP + 4, closing = true)
        host.telegraph.sound(HhaSounds.BLINK, host.boss.entityPos, 1.6f, 0.7f)
        repeat(SETUP_SWAP) { yield(Unit) }
        host.place(spot)
        host.face(dir)
        BossFx.swirl(world, host.style, spot.add(0.0, SWIRL_HEIGHT, 0.0), up, SWIRL_RADIUS, HekateTimings.DARK_HOUR_SETUP - SETUP_SWAP + 8)
        repeat(HekateTimings.DARK_HOUR_SETUP - SETUP_SWAP) {
            host.face(dir)
            yield(Unit)
        }
    }

    /**
     * Ein Schwenk: zwei Kegel derselben Gruppe, Lichtkegel, Runen an schützender Deckung, Statuszeilen; nach der Ansage
     * steht der Blick und prüft. Mit [glide] gleitet sie zu Beginn der Ansage um das Hekataion an den neuen Standplatz.
     */
    private fun sweep(host: Host, hour: Hour, hazard: Telegraph.Fight.Group, dir: Vec3d, glide: Boolean): Sequence<Unit> = sequence {
        val world = host.world
        val spec = host.arena?.spec
        val center = host.plaza()
        val spot = standSpot(host, dir)
        val eyeLift = host.boss.eyeY - host.boss.y
        val ground = Vec3d(spot.x, host.floorY() + 0.05, spot.z)
        hour.lastDir = dir
        val index = hour.sweep++
        val outer = hazard.cone(ground, dir, HekateTimings.GAZE_RADIUS, HekateTimings.GAZE_HALF_ANGLE, HekateTimings.GAZE_W)
        val inner = hazard.cone(ground, dir, HekateTimings.GAZE_RADIUS_SNEAK, HekateTimings.GAZE_HALF_ANGLE, HekateTimings.GAZE_W)
        val windup = outer.ticks
        val total = windup + HekateTimings.GAZE_HOLD
        BossFx.play(
            world, FX_GAZE, host.style, ground, dir, a = HekateTimings.GAZE_RADIUS, b = HekateTimings.GAZE_HALF_ANGLE,
            c = HekateTimings.GAZE_RADIUS_SNEAK, d = windup.toFloat(), life = total,
        )
        if (spec != null) Cover.mark(host.telegraph, spec, ATTACK, spot.add(0.0, eyeLift, 0.0), total)
        host.telegraph.sound(HhaSounds.APPROACH_DREAD, spot, 2.2f, 0.8f)
        val from = host.boss.entityPos
        val move = if (glide) HekateTimings.GAZE_MOVE.coerceAtMost(windup) else 0
        val scentedNow = HashSet<UUID>()
        for (t in 0 until total) {
            if (t < move) host.place(around(center, from, spot, (t + 1).toDouble() / move))
            host.face(dir)
            val eye = host.boss.eyePos
            if (t % STATUS_EVERY == 0) status(host, hour, eye, outer, inner)
            if (t >= windup && (t - windup) % HekateTimings.GAZE_CHECK_EVERY == 0) check(host, hour, hazard, eye, outer, inner, index, scentedNow)
            tickScents(host, hour)
            yield(Unit)
        }
    }

    /** Steht [player] im geltenden Kegel (innen immer, außen nur ohne Schleichen)? */
    private fun inGaze(player: ServerPlayerEntity, outer: Telegraph.Cone, inner: Telegraph.Cone): Boolean =
        inner.contains(player) || (outer.contains(player) && !player.isSneaking)

    /** Gedeckt gegen den Blick: benannte Deckung (mit Vertrag) bzw. irgendein Block (ohne Vertrag, Test anderswo). */
    private fun cover(host: Host, eye: Vec3d, player: ServerPlayerEntity): Pair<Boolean, String?> {
        val spec = host.arena?.spec ?: return Cover.sheltered(host.world, eye, player) to null
        val spot = Cover.shelter(host.world, eye, player, spec, ATTACK)
        return (spot != null) to spot?.id
    }

    /** Statuszeile „gedeckt/gesehen“ für alle im äußeren Kegel (wer außerhalb steht, sieht nichts Neues). */
    private fun status(host: Host, hour: Hour, eye: Vec3d, outer: Telegraph.Cone, inner: Telegraph.Cone) {
        for (player in host.fighters()) {
            if (!outer.contains(player)) continue
            hour.seen.add(player.uuid)
            val exposed = inGaze(player, outer, inner) && !cover(host, eye, player).first
            Cover.status(player, !exposed)
        }
    }

    /** Prüftick des Blicks: Gewittert wird, wer im geltenden Kegel steht und nicht gedeckt ist. */
    private fun check(
        host: Host, hour: Hour, hazard: Telegraph.Fight.Group, eye: Vec3d, outer: Telegraph.Cone, inner: Telegraph.Cone,
        sweep: Int, scentedNow: MutableSet<UUID>,
    ) {
        for (player in host.fighters()) {
            if (outer.contains(player)) hour.seen.add(player.uuid)
            if (player.uuid in scentedNow || !inGaze(player, outer, inner)) continue
            val (covered, spotId) = cover(host, eye, player)
            if (covered) {
                if (spotId != null && spotId.startsWith(TOWER_PREFIX)) hour.towers.add(spotId)
                continue
            }
            scentedNow.add(player.uuid)
            scent(host, hour, hazard, player, sweep)
        }
    }

    /** Gewittert: eigene Kreis-Ansage r 3 unter dem Spieler auf seiner Fläche (folgt ihm nicht), Stil der Gefahr. */
    private fun scent(host: Host, hour: Hour, hazard: Telegraph.Fight.Group, player: ServerPlayerEntity, sweep: Int) {
        val floor = BossDefense.floorFor(host.arena, player.entityPos)
        val ring = hazard.ring(Vec3d(player.x, floor + 0.05, player.z), HekateTimings.SCENT_RADIUS, HekateTimings.SCENT_W)
        ring.band(floor)
        hour.scents.add(Scent(ring, sweep))
        hour.scented.add(player.uuid)
        player.sendMessage(Text.translatable(SCENT_LINE).formatted(Formatting.RED), true)
        host.telegraph.sound(HhaSounds.HOWL, ring.center, 1.6f, 1.25f)
        if (!hour.lineSaid) {
            hour.lineSaid = true
            host.say("scent")
        }
    }

    /** Fällige Witterungen: Treffer, höchstens einmal je Spieler und Schwenk. */
    private fun tickScents(host: Host, hour: Hour) {
        if (hour.scents.isEmpty()) return
        val iterator = hour.scents.iterator()
        while (iterator.hasNext()) {
            val scent = iterator.next()
            if (scent.ring.running) continue
            iterator.remove()
            val struck = hour.struck.getOrPut(scent.sweep) { HashSet() }
            for (player in scent.ring.hits(host.fighters())) {
                if (!struck.add(player.uuid)) continue
                host.strike(player, HekateTimings.SCENT_DAMAGE, scent.ring)
            }
            host.telegraph.during { Vfx.shockwave(host.world, host.style, scent.ring.center, HekateTimings.SCENT_RADIUS, 10, 1.2f) }
            host.telegraph.sound(HhaSounds.IMPACT, scent.ring.center, 1.6f, 1.2f)
        }
    }

    /** Ende der Stunde: Pass aus, Gruppe frei; Belohnung oder nicht; Türme bröckeln; dann zurück ins Drehbuch. */
    private fun finish(host: Host, state: State, hour: Hour, hazard: Telegraph.Fight.Group): Sequence<Unit> = sequence {
        host.endPass()
        hazard.end()
        host.hazardEnded()
        state.current = null
        val towers = hour.towers.filter { it !in state.crumbled }
        val dawn = hour.scented.isEmpty()
        if (dawn) {
            for (fire in fires(host)) {
                if (fire.first in towers) continue
                BossFx.play(host.world, FX_DAWN, Vfx.Style.GOLD, fire.second, a = DAWN_FLAME, life = DAWN_TICKS)
            }
            host.telegraph.sound(HhaSounds.TORCH, host.plaza(), 2.6f, 0.9f)
            host.say("dawn")
            host.hint(HINT_DAWN)
            host.act(HekateAction.STAGGER)
            host.open(HekateTimings.DAWN_F)
        } else {
            host.act(0)
        }
        val rest = if (dawn) HekateTimings.DAWN_F else HekateTimings.DARK_HOUR_RECOVER
        var waited = 0
        for (id in towers) {
            crumbleTower(host, state, id)
            repeat(CRUMBLE_GAP) {
                yield(Unit)
                waited++
            }
        }
        while (waited < rest) {
            yield(Unit)
            waited++
        }
        host.act(0)
    }

    /**
     * Fackelturm [id] bröckelt bis auf den Stumpf ([OlympusArenas.C_TOWER_STUMP]) über das Edit-Register der Arena.
     * Die Feuerschale (Lagerfeuer mit Block-Entity) räumt `ArenaCollapse` nicht ab: Sie wird vorher selbst gelöscht,
     * sonst schwebte sie über dem Stumpf.
     */
    private fun crumbleTower(host: Host, state: State, id: String) {
        val arena = host.arena ?: return
        val spec = arena.spec ?: return
        val edits = arena.edits ?: return
        if (!state.crumbled.add(id)) return
        val box = spec.cover.firstOrNull { it.id == id }?.box ?: return
        val keep = spec.coreY + OlympusArenas.C_TOWER_STUMP
        for (y in keep..box.maxY) for (x in box.minX..box.maxX) for (z in box.minZ..box.maxZ) {
            val pos = BlockPos(x, y, z)
            if (host.world.getBlockState(pos).hasBlockEntity()) edits.clear(pos)
        }
        host.telegraph.during { ArenaCollapse.crumble(edits, box, keep) }
    }

    /** Rauch an allen Feuerstellen: Die Fackeln „erlöschen“ (nur Bild, die Blöcke bleiben). */
    private fun douse(host: Host) {
        for ((_, at) in fires(host)) Fx.burst(host.world, ParticleTypes.LARGE_SMOKE, at, DOUSE_SMOKE, 0.25, 0.02)
    }

    // --- Geometrie ---------------------------------------------------------------------------------------------------

    /**
     * Feuerstellen am Dreiweg als (Deckungs-ID, Weltpunkt): die Lagerfeuer der drei Fackeltürme (oberste Lage, P07) und
     * das Seelenfeuer auf der Spitze des Hekataion (+8). Ohne Vertrag (Test außerhalb des Olymp) keine.
     */
    fun fires(host: Host): List<Pair<String, Vec3d>> {
        val spec = host.arena?.spec ?: return emptyList()
        if (spec.id != OlympusArenas.C.id) return emptyList()
        val y = spec.coreY
        val list = ArrayList<Pair<String, Vec3d>>()
        for (i in OlympusArenas.C_TOWER_ANGLES.indices) {
            val t = OlympusArenas.cTower(i)
            list += "$TOWER_PREFIX${OlympusArenas.C_TOWER_ANGLES[i].toInt()}" to Vec3d(t.x + 0.5, y + OlympusArenas.C_TOWER_HEIGHT - 1 + 0.45, t.z + 0.5)
        }
        val c = spec.center
        list += "hekataion" to Vec3d(c.x + 0.5, y + OlympusLayout.HEKATAION_HEIGHT + 1 + 0.45, c.z + 0.5)
        return list
    }

    /** Standplatz am Hekataion in Blickrichtung [dir] (Boden). */
    fun standSpot(host: Host, dir: Vec3d): Vec3d {
        val c = host.plaza()
        return Vec3d(c.x + dir.x * HekateTimings.GAZE_STAND_RADIUS, host.floorY(), c.z + dir.z * HekateTimings.GAZE_STAND_RADIUS)
    }

    /**
     * Blickrichtung des nächsten Schwenks: zum Schwerpunkt der Spieler, die noch nicht im Kegel waren (liegt er fast in
     * der Mitte: zum ersten davon, der nicht in der Mitte steht); sonst die letzte Richtung 120° im Uhrzeigersinn weiter.
     */
    fun chooseDirection(host: Host, hour: Hour): Vec3d {
        val c = host.plaza()
        val fresh = host.fighters().filter { it.uuid !in hour.seen }
        if (fresh.isNotEmpty()) {
            val d = Vec3d(fresh.sumOf { it.x } / fresh.size - c.x, 0.0, fresh.sumOf { it.z } / fresh.size - c.z)
            if (d.length() > MIN_AIM) return d.normalize()
            for (player in fresh) {
                val p = Vec3d(player.x - c.x, 0.0, player.z - c.z)
                if (p.length() > MIN_AIM) return p.normalize()
            }
        }
        val last = hour.lastDir ?: return flat(host.boss.rotationVector)
        return rotate(last, HekateTimings.GAZE_TURN)
    }

    /** Punkt auf dem Weg um die Mitte [c] von [from] nach [to] (Winkel auf kürzestem Weg, Radius linear, weich). */
    private fun around(c: Vec3d, from: Vec3d, to: Vec3d, t: Double): Vec3d {
        val u = t.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
        val a0 = atan2(from.z - c.z, from.x - c.x)
        val a1 = atan2(to.z - c.z, to.x - c.x)
        var d = a1 - a0
        while (d > Math.PI) d -= 2 * Math.PI
        while (d < -Math.PI) d += 2 * Math.PI
        val r0 = hypot(from.x - c.x, from.z - c.z)
        val r1 = hypot(to.x - c.x, to.z - c.z)
        val a = a0 + d * u
        val r = r0 + (r1 - r0) * u
        return Vec3d(c.x + r * cos(a), to.y, c.z + r * sin(a))
    }

    /** Dreht [dir] um [degrees] (von oben im Uhrzeigersinn positiv, wie `GuardianEntity.rotate`). */
    fun rotate(dir: Vec3d, degrees: Double): Vec3d {
        val a = Math.toRadians(degrees)
        return Vec3d(dir.x * cos(a) - dir.z * sin(a), 0.0, dir.x * sin(a) + dir.z * cos(a))
    }

    private fun flat(v: Vec3d): Vec3d = Vec3d(v.x, 0.0, v.z).let { if (it.lengthSquared() < 1e-6) Vec3d(0.0, 0.0, 1.0) else it.normalize() }
}

/**
 * Fallgitter der Schlüsseldrehung (Überarbeitung 4, P15, PLAN 3.2 a): Mit Beginn der Ansage fällt in den zwei falschen
 * Toren das Gitter (Teile `portcullis_<winkel>`, Füllung `waxed_weathered_copper_grate`, HINWEISE_WELLE1 2), von oben
 * nach unten in [HekateTimings.PORTCULLIS_DROP] Ticks, Ton `arena.gate`. Nach Treffer und Fenster hebt es sich wieder
 * (unten zuerst, [HekateTimings.PORTCULLIS_LIFT]). Das Gitter ist Bild, die Regel ist die Zone: Die Trefferlogik der
 * Schlüsseldrehung prüft weiter den Weg. Alles läuft über `arena.edits`; ohne Register (Test ohne Vertrag) kein Gitter.
 * Wo gerade ein Wesen steht, bleibt die Zelle frei (niemand wird eingeschlossen).
 */
object HekatePortcullis {

    const val PREFIX = "portcullis_"

    /** Füllung (Vanilla-Gitter, durchsichtig, Vollblock). */
    val GRATE: BlockState get() = Blocks.WAXED_WEATHERED_COPPER_GRATE.defaultState

    /** Teil des Fallgitters am Weg [path] (Index in `OlympusLayout.C_PATH_ANGLES`). */
    fun part(spec: ArenaSpec, path: Int): Part? {
        val angle = OlympusLayout.C_PATH_ANGLES.getOrNull(path) ?: return null
        return spec.parts.firstOrNull { it.id == PREFIX + angle.toInt() }
    }

    private fun contains(part: Part, pos: BlockPos): Boolean = pos.y in part.minY..part.maxY && part.zone.containsCell(pos.x, pos.z)

    private fun middle(part: Part): Vec3d {
        val cells = part.zone.cells().toList()
        val x = cells.sumOf { it.first + 0.5 } / cells.size.coerceAtLeast(1)
        val z = cells.sumOf { it.second + 0.5 } / cells.size.coerceAtLeast(1)
        return Vec3d(x, (part.minY + part.maxY + 1) * 0.5, z)
    }

    /** Lässt die Gitter der Wege [paths] fallen (Aufgabe für `launch`). */
    fun drop(arena: Arenas.Arena, paths: List<Int>, fight: Telegraph.Fight): Sequence<Unit> = sequence {
        val spec = arena.spec ?: return@sequence
        val edits = arena.edits ?: return@sequence
        val parts = paths.mapNotNull { part(spec, it) }
        if (parts.isEmpty()) return@sequence
        for (part in parts) fight.sound(HhaSounds.ARENA_GATE, middle(part), 2.2f, 0.85f)
        val top = parts.maxOf { it.maxY }
        val bottom = parts.minOf { it.minY }
        val layers = top - bottom + 1
        var tick = 0
        for (i in 0 until layers) {
            val y = top - i
            val at = (i + 1) * HekateTimings.PORTCULLIS_DROP / layers
            while (tick < at) {
                yield(Unit)
                tick++
            }
            if (!arena.open || arena.edits !== edits) return@sequence
            for (part in parts) {
                if (y !in part.minY..part.maxY) continue
                for ((x, z) in part.zone.cells()) {
                    val pos = BlockPos(x, y, z)
                    if (!arena.world.getBlockState(pos).isAir || occupied(arena, pos)) continue
                    edits.set(pos, GRATE)
                }
            }
        }
    }

    /** Hebt die Gitter der Wege [paths] (unten zuerst, über den Bauplan zurück; Aufgabe für `launch`). */
    fun lift(arena: Arenas.Arena, paths: List<Int>, fight: Telegraph.Fight): Sequence<Unit> = sequence {
        val spec = arena.spec ?: return@sequence
        val edits = arena.edits ?: return@sequence
        val parts = paths.mapNotNull { part(spec, it) }
        if (parts.isEmpty()) return@sequence
        for (part in parts) fight.sound(HhaSounds.ARENA_GATE, middle(part), 1.8f, 1.15f)
        val top = parts.maxOf { it.maxY }
        val bottom = parts.minOf { it.minY }
        val layers = top - bottom + 1
        var tick = 0
        for (i in 0 until layers) {
            val y = bottom + i
            val at = (i + 1) * HekateTimings.PORTCULLIS_LIFT / layers
            while (tick < at) {
                yield(Unit)
                tick++
            }
            if (!arena.open || arena.edits !== edits) return@sequence
            edits.restore { pos -> pos.y == y && parts.any { contains(it, pos) } }
        }
    }

    /** Sofort alle Fallgitter zurück (Ausgänge mitten in der Schlüsseldrehung, bei offener Arena). */
    fun liftNow(arena: Arenas.Arena?) {
        val spec = arena?.spec ?: return
        val edits = arena.edits ?: return
        val parts = spec.parts.filter { it.id.startsWith(PREFIX) }
        if (parts.isEmpty()) return
        edits.restore { pos -> parts.any { contains(it, pos) } }
    }

    /**
     * Umweg für die Erreichbarkeit: Wer auf einem Weg mit geschlossenem Tor jenseits davon steht (Ring > Tor, quer
     * innerhalb der Pfeiler), läuft um einen Pfeiler herum: [HekateTimings.PORTCULLIS_DETOUR] Blöcke mehr.
     */
    fun detour(pos: Vec3d, center: Vec3d, closed: List<Int>): Double {
        for (path in closed) {
            val d = OlympusLayout.dir(OlympusLayout.C_PATH_ANGLES[path])
            val dx = pos.x - center.x
            val dz = pos.z - center.z
            val along = dx * d.x + dz * d.z
            val across = kotlin.math.abs(dx * d.z - dz * d.x)
            if (along >= OlympusArenas.C_GATE_RADIUS + 0.5 && across <= OlympusArenas.C_GATE_PILLAR_OFFSET + 1.0) return HekateTimings.PORTCULLIS_DETOUR
        }
        return 0.0
    }

    private fun occupied(arena: Arenas.Arena, pos: BlockPos): Boolean =
        arena.world.getEntitiesByClass(LivingEntity::class.java, Box(pos)) { it.isAlive }.isNotEmpty()
}

/**
 * Ebenen und Standplätze am Dreiweg (Überarbeitung 4, P15, Pflicht 3 und Schritt 6).
 *
 * - [echo]: Der Client zeichnet Ansagen flach auf ihrer Höhe. Trifft ein Körperangriff (Band bis
 *   [HekateTimings.BODY_BAND_ABOVE]) eine Terrasse, legt [echo] auf deren Höhe (+3) Stücke derselben Gruppe über die
 *   betroffenen Viertel der Terrasse (je 20°, Sehne auf der Ringmitte). Die Stücke sind nur Bild: Getroffen wird mit den
 *   ursprünglichen Formen (die Angriffe prüfen ihre Formen einzeln, nie `group.hits`).
 * - [freeStand]: Ein fester Standplatz der Hekate, der in einer Deckung (samt Sockel), auf einer Terrasse oder auf
 *   einer Terrassentreppe läge, wird auf den nächsten freien Kernpunkt gezogen (`ArenaNav.nearestCore`).
 */
object HekateLevels {

    /** Stücke je Terrasse (±40° → je 20°). */
    const val SEGMENTS = 4

    /** Ein Standplatz gilt als „in der Deckung“, wenn er näher als so viel am Kasten liegt (halbe Breite 0,6 + Sockel). */
    const val STAND_MARGIN = 1.2

    /** Spielraum der Kernprüfung am Rand (Blöcke zur Mitte hin). */
    const val CORE_TOLERANCE = 0.05

    /** Band für Körperangriffe: vom Kernboden bis zur Terrasse plus 3. */
    fun bodyBand(shape: Telegraph.Shape, coreY: Double): Telegraph.Shape = shape.band(coreY, 1.0, HekateTimings.BODY_BAND_ABOVE)

    /**
     * Zweite Form auf der Höhe jeder Terrasse, die eine der [shapes] berührt (nur Gefahr-Formen zählen). [ticks] =
     * Dauer der ursprünglichen Ansage (dieselbe Gruppe, dieselbe Mindestdauer). Ohne Vertrag nichts.
     */
    fun echo(group: Telegraph.Fight.Group, shapes: List<Telegraph.Shape>, spec: ArenaSpec?, ticks: Int) {
        if (spec == null || ticks <= 0) return
        val danger = shapes.filter { !it.safe }
        if (danger.isEmpty()) return
        for (level in spec.levels) {
            val zone = level.zone as? Sector ?: continue
            val span = (zone.to - zone.from) / SEGMENTS
            for (s in 0 until SEGMENTS) {
                val a0 = zone.from + span * s
                val a1 = a0 + span
                if (!touches(zone, a0, a1, danger)) continue
                val mid = (zone.inner + zone.outer) * 0.5
                val y = level.floorY + 0.05
                val from = polar(zone, mid, a0, y)
                val to = polar(zone, mid, a1, y)
                group.line(from, to, (zone.outer - zone.inner).toFloat(), ticks).band(level.floorY.toDouble())
            }
        }
    }

    /** Berührt eine der Formen das Stück [a0] … [a1] der Terrasse? 3 × 3 Stichproben. */
    private fun touches(zone: Sector, a0: Double, a1: Double, shapes: List<Telegraph.Shape>): Boolean {
        val radii = doubleArrayOf(zone.inner + 0.5, (zone.inner + zone.outer) * 0.5, zone.outer - 0.5)
        val angles = doubleArrayOf(a0 + 2.0, (a0 + a1) * 0.5, a1 - 2.0)
        for (r in radii) for (a in angles) {
            val p = polar(zone, r, a, 0.0)
            if (shapes.any { it.contains(p) }) return true
        }
        return false
    }

    private fun polar(zone: Sector, r: Double, deg: Double, y: Double): Vec3d {
        val a = Math.toRadians(deg)
        return Vec3d(zone.cx + r * cos(a), y, zone.cz + r * sin(a))
    }

    /** [pos] als freier Standplatz auf dem Kern (siehe Kopf); ohne Vertrag unverändert. */
    fun freeStand(pos: Vec3d, spec: ArenaSpec?): Vec3d {
        if (spec == null || free(pos.x, pos.z, spec)) return pos
        var best: Vec3d? = null
        var bestD = Double.MAX_VALUE
        for ((x, z) in spec.core.cells()) {
            val cx = x + 0.5
            val cz = z + 0.5
            val d = (cx - pos.x) * (cx - pos.x) + (cz - pos.z) * (cz - pos.z)
            if (d >= bestD || !free(cx, cz, spec)) continue
            bestD = d
            best = Vec3d(cx, pos.y, cz)
        }
        return best ?: ArenaNav.nearestCore(pos, spec)?.let { Vec3d(it.x, pos.y, it.z) } ?: pos
    }

    /** Frei: auf dem Kern, nicht an einer Deckung (samt Sockel), nicht auf oder neben einer Terrassentreppe. */
    fun free(x: Double, z: Double, spec: ArenaSpec): Boolean {
        // Wegenden liegen genau auf dem Ende des Weg-Streifens: einen Hauch zur Mitte hin prüfen (Rundung).
        val c = spec.centerVec
        val d = hypot(x - c.x, z - c.z).coerceAtLeast(1e-6)
        val ix = x - (x - c.x) / d * CORE_TOLERANCE
        val iz = z - (z - c.z) / d * CORE_TOLERANCE
        if (!spec.core.contains(x, z) && !spec.core.contains(ix, iz)) return false
        if (spec.cover.any { ArenaNav.inflate(it.box, STAND_MARGIN).inside(x, z) }) return false
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        return spec.levels.none { level -> level.access.any { kotlin.math.abs(it.x - bx) <= 1 && kotlin.math.abs(it.z - bz) <= 1 } }
    }
}
