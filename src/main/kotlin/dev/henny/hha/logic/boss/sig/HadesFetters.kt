package dev.henny.hha.logic.boss.sig

import dev.henny.hha.HhaSounds
import dev.henny.hha.entity.boss.HadesPose
import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossFx
import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.Cover
import dev.henny.hha.logic.boss.HadesFight
import dev.henny.hha.logic.boss.SubTarget
import dev.henny.hha.logic.boss.SubTargetGroup
import dev.henny.hha.logic.boss.Telegraph
import dev.henny.hha.logic.boss.Tether
import dev.henny.hha.logic.boss.subTargetHandler
import dev.henny.hha.logic.boss.timings.HadesSigTimings
import dev.henny.hha.world.TartarusArenas
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.math.Vec3d
import java.util.UUID
import kotlin.math.min

/**
 * Hades: **Die Fesseln** (Überarbeitung 4, PLAN 4.1 „Greifen“, 4.2, 4.3; Besitzer P19). Ersetzt den Zug der Seelenketten
 * ab Phase II, an derselben Stelle der Auswahl (`HadesAttacks.pick`, „chains“, ab Abstand 10).
 *
 * Ablauf ([cast]):
 * 1. **Ansage** [HadesSigTimings.FETTER_W] 16: Die Kette fliegt (`Vfx.chain`, das Bild von heute), dazu ein Kreis r 1,5
 *    am Ziel auf dessen Ebene. Säulen, die das Ziel gerade decken, tragen die goldene Rune ([Cover.mark]), das Ziel
 *    sieht „gedeckt/gesehen“ ([Cover.status]).
 * 2. **Treffer:** Steht das Ziel hinter einer Säule ([Cover.shelter] mit [TartarusArenas.HADES_FETTER]), reißt die Kette
 *    an der Säule: Hades taumelt [HadesSigTimings.FETTER_BREAK_STAGGER] (offen). Hat es den Kreis verlassen, fällt die
 *    Kette zu Boden: kein Taumeln, nur seine Erholung wie nach dem Ausfall. Sonst ist es **gefesselt**.
 * 3. **Gefesselt** (höchstens [HadesSigTimings.FETTER_HOLD] 40 Ticks): [Tether] r 3 um den Punkt am Boden, an dem es
 *    getroffen wurde. Kette Hand → Anker → Spieler (`hades.fetter_hold`), in der Kette Hand → Anker das goldene
 *    Teilziel „Fessel“ (3 Kerben, jeder Treffer eine, jeder darf). Hades hält die Kette (Pose [HadesPose.HOLD]) und tut
 *    sonst nichts; Saal-Gefahren beginnen nicht ([HadesFight] läuft das als Höhepunkt `solo`), der Gefesselte nimmt
 *    keinen anderen Treffer ([shields]).
 * 4. **Auswege:** (a) eine Säule zwischen Hand und Gefesseltem (alle 2 Ticks [Cover.lineEnd] Hand → Körper, Treffer in
 *    einer Säule aus dem Vertrag) → Kette reißt, Taumeln 30; (b) Teilziel gebrochen → Kette reißt, Taumeln 30;
 *    (c) nach 40 Ticks reißt sie von selbst: Hades zieht sich heran und schlägt den angesagten Hieb
 *    ([HadesSigTimings.FETTER_HIT_W] 20, Kegel wie Hieb A, [HadesSigTimings.FETTER_HIT_DAMAGE] 20).
 *
 * Alle Ausgänge (Phase endet, k. o., Saal verlassen, Weltwechsel, `finish`, Szene, Ablauf ersetzt) laufen über [abort]
 * bzw. das Ende von [hold]: Leine lösen, Gruppe schließen, Kette weg (die Kette am Client endet mit der Pose).
 *
 * Kein Statuseffekt, keine Eingabesperre, kein Teleport außer dem einmaligen Zurücksetzen der Leine ([Tether]).
 */
object HadesFetters {

    /** Einstieg aus `Hha.onInitialize` (PLAN 7.7). Die Fessel braucht keine Ereignisse: Sie lebt im Ablauf des Kampfs. */
    fun init() {}

    /** Teilziel-Art (Darstellung `SubTargetRenderer`, P04) und Effekt-IDs (Client: `HadesFx`). */
    const val KIND = "hades.fetter"
    const val FX_HOLD = "hades.fetter_hold"
    const val FX_SNAP = "hades.fetter_snap"

    /** Sprachschlüssel: Name der Unterleiste und der einmalige Hinweis je Kampf. */
    const val SUB_NAME = "hha.hades.sub.fetter"
    const val HINT = "hha.hades.hint.fetter"

    /**
     * Was die Fessel aus dem Angriffsteil braucht (in `HadesAttacks`): Hand, Taumeln, Ausweich-Angriff, der Hieb und das
     * Zeigen auf den Ebenen. So bleibt die Kampf-Datei klein und die Fessel liest keine fremden Zustände.
     */
    interface Moves {
        /** Wo seine Hand die Kette hält. */
        fun hand(): Vec3d

        /** Taumeln (offen) für [ticks], danach Aufrichten. */
        fun staggered(ticks: Int): Sequence<Unit>

        /** Kein freies Ziel (alle gedeckt oder eine Saal-Gefahr läuft): stattdessen dieser Angriff (Ausfallschritt). */
        fun fallback(): Sequence<Unit>

        /** Läuft gerade eine Saal-Gefahr? Dann fesselt er nicht (der Gefesselte könnte ihr nicht ausweichen). */
        fun hazardRunning(): Boolean

        /** Der Hieb fällt: Treffer im Kegel, Sichelband, Ruck (wie Hieb A). */
        fun strike(cone: Telegraph.Cone, damage: Float)

        /** Gleiche Form auf den Ebenen zeigen, über denen sie liegt (Umgang, Podest). */
        fun echo(group: Telegraph.Fight.Group, cone: Telegraph.Cone)

        /** Höhenband für Körperangriffe (Kern bis zur höchsten Ebene + 3). */
        fun bodyBand(group: Telegraph.Fight.Group): Telegraph.Fight.Group
    }

    /**
     * Die Fessel eines Kampfs (eine je `HadesAttacks`, lebt den ganzen Kampf). Höchstens ein Gefesselter, weil der
     * Ablauf immer nur einen Angriff zugleich führt.
     */
    class Fetter(private val fight: HadesFight, private val moves: Moves) {

        private val world get() = fight.world
        private val calls get() = fight.hadesCalls

        private var tether: Tether? = null
        private var group: SubTargetGroup? = null
        private var bound: UUID? = null
        private var broken = false

        /** Der Hinweis kommt einmal je Kampf (Pflicht 8). */
        private var hinted = false

        /** Ist gerade jemand gefesselt? */
        val active: Boolean get() = bound != null

        /** Hält die Fessel gerade [player]? Dann nimmt er keinen anderen Treffer (`HadesFight.hurt`). */
        fun shields(player: ServerPlayerEntity): Boolean = bound == player.uuid

        /** Ausgang ohne Folgen (Phase endet, `finish`, Szene, Ablauf ersetzt): Leine lösen, Gruppe schließen, Kette weg. */
        fun abort() {
            if (bound == null && tether == null && group == null) return
            release()
            fight.boss?.let { if (it.pose == HadesPose.HOLD) it.pose = HadesPose.IDLE }
        }

        private fun release() {
            tether?.release()
            tether = null
            group?.close()
            group = null
            bound = null
            broken = false
        }

        // -----------------------------------------------------------------------------------------------------------
        // Ablauf
        // -----------------------------------------------------------------------------------------------------------

        private suspend fun SequenceScope<Unit>.wait(ticks: Int) {
            repeat(ticks) { yield(Unit) }
        }

        /** Darf [player] gerade gefesselt werden? Im Kampf, in dieser Welt, lebendig, kein Zuschauer. */
        private fun present(player: ServerPlayerEntity): Boolean =
            fight.isParticipant(player) && player.entityWorld === world && player.isAlive && !player.isRemoved && !player.isSpectator

        /** Sieht seine Hand [player]? (Bisher: Kopf → Augen frei.) */
        private fun inSight(hand: Vec3d, player: ServerPlayerEntity): Boolean = !Cover.blocked(world, hand, player.eyePos)

        /**
         * Die Fessel als Angriff. Ziel: in Sicht, in der Gruppe das mit dem größten Abstand. Niemand frei oder eine
         * Saal-Gefahr läuft → [Moves.fallback] (wie bisher, wenn niemand in Sicht war).
         */
        fun cast(): Sequence<Unit> = sequence {
            val entity = fight.boss ?: return@sequence
            val hand = moves.hand()
            val target = if (moves.hazardRunning()) null else fight.targets().filter { inSight(hand, it) }.maxByOrNull { it.squaredDistanceTo(entity) }
            if (target == null) {
                yieldAll(moves.fallback())
                return@sequence
            }
            entity.cloaked = false
            fight.face(target.entityPos)
            entity.pose = HadesPose.CHAINS
            val group = moves.bodyBand(calls.boss(pace = Telegraph.Pace.HEAVY))
            val catchAt = fight.onLevel(target.entityPos)
            val ring = group.ring(catchAt, HadesSigTimings.FETTER_CATCH_RADIUS, HadesSigTimings.FETTER_W)
            // Die Kette fliegt (Bild von heute) zum Kreis; sie zieht sich am Client von selbst zurück.
            Vfx.chain(world, Vfx.Style.HADES, entity, catchAt.add(0.0, CHAIN_LIFT, 0.0), null, ring.ticks + THROW_CHAIN_AFTER)
            Cover.mark(calls, fight.spec, TartarusArenas.HADES_FETTER, hand, ring.ticks)
            calls.sound(HhaSounds.CHAIN, entity.entityPos, 3f, 1f)
            repeat(ring.ticks) {
                if (present(target)) Cover.status(target, Cover.shelter(world, moves.hand(), target, fight.spec, TartarusArenas.HADES_FETTER) != null)
                yield(Unit)
            }
            group.end()
            if (fight.isEnded || !present(target)) {
                entity.pose = HadesPose.IDLE
                return@sequence
            }
            val from = moves.hand()
            if (Cover.shelter(world, from, target, fight.spec, TartarusArenas.HADES_FETTER) != null) {
                // Die Säule hält die Kette auf: Sie reißt dort, und das bringt ihn aus dem Tritt (wie heute).
                snap(Cover.lineEnd(world, from, target.eyePos), from)
                yieldAll(moves.staggered(HadesSigTimings.FETTER_BREAK_STAGGER))
                return@sequence
            }
            if (!ring.contains(target)) {
                // Ausgewichen: Die Kette schlägt leer auf den Boden. Kein Fenster, nur seine Erholung.
                calls.sound(HhaSounds.DEFLECT, catchAt, 1.8f, 0.5f)
                BossFx.sparks(world, Vfx.Style.HADES, catchAt.add(0.0, 0.2, 0.0), count = MISS_SPARKS, speed = MISS_SPARK_SPEED)
                wait(BossTimings.Hades.THRUST_RECOVER)
                entity.pose = HadesPose.IDLE
                return@sequence
            }
            yieldAll(hold(target, Vec3d(target.x, catchAt.y, target.z)))
        }

        /** Gefesselt: Leine, Teilziel, Auswege. Endet immer mit [release]. */
        private fun hold(target: ServerPlayerEntity, anchor: Vec3d): Sequence<Unit> = sequence {
            val entity = fight.boss ?: return@sequence
            release()
            bound = target.uuid
            tether = Tether(target, { anchor }, HadesSigTimings.FETTER_RADIUS, HadesSigTimings.FETTER_HOLD)
            entity.pose = HadesPose.HOLD
            val hand = moves.hand()
            val knotAt = knotPoint(hand, anchor)
            val subs = SubTargetGroup(world, { fight.players() }, subTargetHandler(
                // Jeder Treffer zählt genau eine Kerbe: mindestens das volle Leben, der Deckel (1/3) kürzt.
                onHit = { t, _, _ -> t.maxHealth },
                onBroken = { broken = true },
            ))
            group = subs
            val knot: SubTarget = subs.add(KNOT_ID, SUB_NAME, HadesSigTimings.FETTER_HEALTH, 1f / HadesSigTimings.FETTER_NOTCHES, HadesSigTimings.FETTER_NOTCHES)
            knot.anchor = knotAt
            subs.spawn(knot, knotAt.subtract(0.0, KNOT_HANG, 0.0), KIND)
            knot.open = true
            BossFx.play(world, FX_HOLD, Vfx.Style.HADES, anchor, life = HadesSigTimings.FETTER_HOLD + HOLD_FX_AFTER, entity = entity, entity2 = target)
            calls.sound(HhaSounds.ARENA_FETTER, anchor, 2.6f, 0.8f)
            if (!hinted) {
                hinted = true
                for (player in fight.targets()) player.sendMessage(Text.translatable(HINT).formatted(Formatting.GOLD), true)
            }
            var exit = Exit.TIME
            var snapAt = anchor
            for (tick in 0 until HadesSigTimings.FETTER_HOLD) {
                subs.tick()
                if (broken) {
                    exit = Exit.BROKEN
                    snapAt = knotAt
                    break
                }
                val leash = tether
                if (fight.isEnded || leash == null || !present(target) || !leash.tick()) {
                    exit = Exit.GONE
                    break
                }
                if (tick % HadesSigTimings.FETTER_COVER_EVERY == 0) {
                    pillarBetween(moves.hand(), target)?.let {
                        exit = Exit.PILLAR
                        snapAt = it
                    }
                    if (exit == Exit.PILLAR) break
                }
                fight.face(target.entityPos)
                yield(Unit)
            }
            release()
            when (exit) {
                Exit.GONE -> {
                    // Ausgeschieden, k. o. oder fort: Die Kette lässt los, ohne dass es als Riss zählt.
                    entity.pose = HadesPose.IDLE
                    wait(BossTimings.Hades.THRUST_RECOVER)
                }
                Exit.PILLAR, Exit.BROKEN -> {
                    snap(snapAt, moves.hand())
                    kickFreed(target)
                    yieldAll(moves.staggered(HadesSigTimings.FETTER_BREAK_STAGGER))
                }
                Exit.TIME -> {
                    snap(anchor, moves.hand())
                    yieldAll(selfSnapStrike(target, anchor))
                }
            }
        }

        /**
         * Ausweg (c): Die Kette reißt von selbst. Hades zieht sich an ihr heran (in der Ansage) und schlägt den Hieb:
         * Kegel wie Hieb A, Spitze [HadesSigTimings.FETTER_HIT_REACH] vor dem Anker, Ansage [HadesSigTimings.FETTER_HIT_W].
         */
        private fun selfSnapStrike(target: ServerPlayerEntity, anchor: Vec3d): Sequence<Unit> = sequence {
            val entity = fight.boss ?: return@sequence
            val start = entity.entityPos
            val toward = Vec3d(anchor.x - start.x, 0.0, anchor.z - start.z)
            val dir = if (toward.lengthSquared() < 1e-4) Vec3d(0.0, 0.0, 1.0) else toward.normalize()
            val stand = fight.clampToCore(Vec3d(anchor.x, fight.center.y, anchor.z).subtract(dir.multiply(HadesSigTimings.FETTER_HIT_REACH)))
            val group = moves.bodyBand(calls.boss(pace = Telegraph.Pace.HEAVY))
            val cone = group.cone(stand, dir, BossTimings.Hades.SWEEP_RADIUS, BossTimings.Hades.SWEEP_HALF_ANGLE, HadesSigTimings.FETTER_HIT_W)
            moves.echo(group, cone)
            calls.sound(HhaSounds.WHOOSH, stand, 1.8f, 0.55f)
            // Heranziehen an der gerissenen Kette (die Kette am Client endet mit der Pose „hält“), dann Hieb A mit seinem
            // eigenen Ausholen.
            val close = min(HadesSigTimings.FETTER_HIT_CLOSE, (cone.ticks - BossTimings.Hades.SWEEP_A_WINDUP).coerceAtLeast(1))
            entity.pose = HadesPose.IDLE
            for (i in 1..close) {
                val k = i.toDouble() / close
                entity.setPosition(start.lerp(stand, k * k * (3.0 - 2.0 * k)))
                fight.face(anchor)
                yield(Unit)
            }
            wait((cone.remaining - BossTimings.Hades.SWEEP_A_WINDUP).coerceAtLeast(0))
            if (present(target)) fight.face(target.entityPos)
            entity.pose = HadesPose.SWEEP
            wait(cone.remaining)
            moves.strike(cone, HadesSigTimings.FETTER_HIT_DAMAGE)
            group.end()
            wait(BossTimings.Hades.SWEEP_WINDOW)
            entity.pose = HadesPose.IDLE
        }

        // -----------------------------------------------------------------------------------------------------------
        // Hilfen
        // -----------------------------------------------------------------------------------------------------------

        /**
         * Steht eine Säule aus dem Vertrag zwischen Hand und Körper des Gefesselten (eine Sichtlinie, Briefing:
         * `Cover.blocked`)? Liefert den Riss-Punkt. Andere Hindernisse (Thron, Treppe) reißen die Kette nicht.
         */
        private fun pillarBetween(hand: Vec3d, player: ServerPlayerEntity): Vec3d? {
            val body = player.entityPos.add(0.0, player.height * BODY_SHARE, 0.0)
            if (!Cover.blocked(world, hand, body)) return null
            val hit = Cover.lineEnd(world, hand, body)
            val spot = Cover.spotAt(fight.spec, hit) ?: return null
            return hit.takeIf { TartarusArenas.HADES_FETTER in spot.protects }
        }

        /** Mitte der Kette Hand → Anker, höchstens [HadesSigTimings.FETTER_KNOT_MAX] vom Anker (der Gefesselte erreicht sie). */
        private fun knotPoint(hand: Vec3d, anchor: Vec3d): Vec3d {
            val span = hand.subtract(anchor)
            val length = span.length()
            if (length < 1e-3) return anchor.add(0.0, KNOT_HANG + 0.4, 0.0)
            val share = min(0.5, HadesSigTimings.FETTER_KNOT_MAX / length)
            return anchor.add(span.multiply(share))
        }

        /** Kette reißt: eigenes Bild (`hades.fetter_snap`: Ruck, Glieder fallen), Funken und der Ton der Fessel. */
        private fun snap(at: Vec3d, hand: Vec3d) {
            val back = hand.subtract(at)
            BossFx.play(world, FX_SNAP, Vfx.Style.HADES, at, back, life = SNAP_LIFE, entity = fight.boss)
            BossFx.sparks(world, Vfx.Style.HADES, at, count = SNAP_SPARKS, speed = SNAP_SPARK_SPEED)
            Vfx.flare(world, Vfx.Style.GOLD, at, SNAP_FLARE, SNAP_FLARE_LIFE)
            calls.sound(HhaSounds.ARENA_FETTER, at, 2.4f, 1.25f)
        }

        /** Der Ruck beim Befreien: nur für den, der gefesselt war (klein, PLAN 2.9 der Überarbeitung 3). */
        private fun kickFreed(player: ServerPlayerEntity) {
            if (present(player)) BossFx.kick(player, pitch = FREED_KICK, ticks = FREED_KICK_TICKS)
        }

        private enum class Exit { TIME, PILLAR, BROKEN, GONE }
    }

    private const val KNOT_ID = "fetter"

    /**
     * Die geworfene Kette (Ansage) zielt so hoch über den Kreis und lebt so viel länger als die Ansage: Das Kettenbild
     * holt ab 65 % seiner Lebenszeit ein, so beginnt das Einholen erst mit dem Treffer (16 / 0,65 ≈ 26).
     */
    private const val CHAIN_LIFT = 0.2
    private const val THROW_CHAIN_AFTER = 10

    /** Das Teilziel-Modell hängt am oberen Punkt (≈ 1,6, P04); die Kettenmitte liegt etwas darunter. */
    private const val KNOT_HANG = 0.8

    /** Höhe des Körperstrahls für Ausweg (a), Anteil der Spielerhöhe. */
    private const val BODY_SHARE = 0.5

    /** Die Kette am Client darf so viel länger leben; sie endet ohnehin mit der Pose. */
    private const val HOLD_FX_AFTER = 4

    /** Riss: Lebenszeit des Bilds, Funken, goldener Blitz. */
    private const val SNAP_LIFE = 30
    private const val SNAP_SPARKS = 8
    private const val SNAP_SPARK_SPEED = 0.35f
    private const val SNAP_FLARE = 2f
    private const val SNAP_FLARE_LIFE = 8

    /** Leerer Wurf: ein paar Funken am Boden. */
    private const val MISS_SPARKS = 5
    private const val MISS_SPARK_SPEED = 0.25f

    /** Ruck für den Befreiten (Grad, Ticks). */
    private const val FREED_KICK = 1.5f
    private const val FREED_KICK_TICKS = 6
}
