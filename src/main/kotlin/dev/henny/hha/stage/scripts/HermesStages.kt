package dev.henny.hha.stage.scripts

import dev.henny.hha.logic.boss.BossTimings
import dev.henny.hha.logic.boss.HhaVoiceLengths
import dev.henny.hha.stage.EaseKind
import dev.henny.hha.stage.Key
import dev.henny.hha.stage.StageBuilder
import dev.henny.hha.stage.StageScript
import dev.henny.hha.stage.StageScripts
import dev.henny.hha.stage.stage
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Bühnen-Skripte von Hermes (P07, PLAN 2.4): die **Kamera** der fünf Szenen aus `HermesCutscenes`. Das Drehbuch selbst
 * (Zeilen, Posen, Bewegung, Effekte, Musik, Titel, Ende) läuft unverändert auf dem Server; nur die alten
 * `Cine.camera`/`Cine.orbit`-Aufrufe sind durch diese Zeitleisten ersetzt. Damit die Schnitte genau auf die Stellen
 * fallen, an denen früher die Kamera-Aufrufe standen, rechnet jede Zeitleiste mit **denselben** Wartezeiten wie das
 * Drehbuch: eine gesprochene Zeile dauert `HhaVoiceLengths.ticks(Schlüssel, Grundwert) + 4` ([say], wie
 * `HermesCutscenes.say`), dazu die festen `wait`-Zeiten. Jede Einstellung trägt eine Marke `camN`; das Drehbuch ruft an
 * der alten Stelle `cue("camN")` und setzt die Zeitleiste neu auf, falls sie abweicht (Sicherheitsnetz).
 *
 * Anker und Achsen: Anker = Mitte des Forums (`HermesFight.center`), Blick 0 (vorn = +z, rechts = −x). [w] übersetzt
 * die alten Welt-Versätze 1 : 1 ([Key.At]). Darsteller 0 = Hermes (Ausnahme Prolog Teil 1, s. u.); seine Blickrichtung
 * ist in allen Szenen 0 (`faceBossToPlayers`: der erste Spieler steht auf +z), [b] ist deshalb derselbe Versatz,
 * aber **relativ zu Hermes** ([Key.ActorRel]): Fliegt oder dreht er sich, bleibt die Kamera an ihm.
 *
 * Alte Kreisfahrten werden als Catmull-Rom durch fünf Punkte auf dem Kreis gefahren (nicht als Bogen um den Blickpunkt,
 * weil der Blick oft nicht in der Kreismitte liegt). Am Ende jeder Szene fährt die Kamera in [EYE] Ticks zurück in die
 * Augen, genau bis zum alten `Cine.end`.
 *
 * Prolog in zwei Teilen: Teil 1 am Altar (Anker = `HermesFight.origin`, Darsteller = alle Spieler, Blick auf die
 * Gruppe); bei Marke [PROLOG_FORUM] – das Bild ist weiß, Forum und Hermes sind eben entstanden – setzt das Drehbuch
 * dasselbe Skript mit Anker = Forum-Mitte und Darsteller 0 = Hermes ab diesem Tick neu auf. Ohne Tempel (Test am
 * Standort) fehlt die Altar-Zeile; dann beginnt das Skript bei [prologSkip].
 */
object HermesStages {

    const val PROLOG = "hermes.prolog"
    const val STAGE2 = "hermes.stage2"
    const val STAGE3 = "hermes.stage3"
    const val STAGE4 = "hermes.stage4"
    const val FINALE = "hermes.finale"

    /** Marke im Prolog, an der Anker und Darsteller wechseln (Forum steht, Hermes ist da). */
    const val PROLOG_FORUM = "forum"

    /** So viele Ticks vor dem alten `Cine.end` beginnt die Fahrt zurück in die Augen. */
    const val EYE = 20

    fun register() {
        StageScripts.register(prolog())
        StageScripts.register(stage2())
        StageScripts.register(stage3())
        StageScripts.register(stage4())
        StageScripts.register(finale())
    }

    /** Dauer einer gesprochenen Zeile im Drehbuch (`HermesCutscenes.say`): Länge der Aufnahme bzw. Grundwert + 4. */
    fun say(key: String, base: Int): Int = HhaVoiceLengths.ticks("hha.hermes.line.$key", base) + 4

    /** Prolog ohne Tempel: Das Drehbuch lässt die Altar-Zeile aus, das Skript beginnt um diese Ticks später. */
    fun prologSkip(): Int = say("prologue.altar", 56)

    // ---------------------------------------------------------------------------------------------------------------
    // Schlüssel
    // ---------------------------------------------------------------------------------------------------------------

    /** Alter Welt-Versatz (dx, dy, dz) vom Anker. */
    private fun w(dx: Double, dy: Double, dz: Double): Key = Key.At(dz, -dx, dy)

    /** Alter Welt-Versatz von Hermes (Blick 0), als Punkt relativ zu Hermes. */
    private fun b(dx: Double, dy: Double, dz: Double): Key = Key.ActorRel(0, dz, -dx, dy)

    /** Fünf Punkte einer alten Kreisfahrt (Winkel in Grad, wie `Cine.orbit`) um eine Mitte, gebaut von [at]. */
    private fun orbit(
        a0: Double, a1: Double, r0: Double, r1: Double, h0: Double, h1: Double,
        at: (Double, Double, Double) -> Key,
    ): List<Key> = List(ORBIT_POINTS) { i ->
        val k = i / (ORBIT_POINTS - 1.0)
        val a = Math.toRadians(a0 + (a1 - a0) * k)
        val r = r0 + (r1 - r0) * k
        at(cos(a) * r, h0 + (h1 - h0) * k, sin(a) * r)
    }

    private const val ORBIT_POINTS = 5

    /** Kreisfahrt um den Anker (+ Versatz der Mitte). */
    private fun orbitAt(a0: Double, a1: Double, r0: Double, r1: Double, h0: Double, h1: Double, cx: Double = 0.0, cy: Double = 0.0, cz: Double = 0.0) =
        orbit(a0, a1, r0, r1, h0, h1) { x, y, z -> w(cx + x, cy + y, cz + z) }

    /** Kreisfahrt um Hermes (+ Höhe der Mitte). */
    private fun orbitBoss(a0: Double, a1: Double, r0: Double, r1: Double, h0: Double, h1: Double, cy: Double = 0.0) =
        orbit(a0, a1, r0, r1, h0, h1) { x, y, z -> b(x, cy + y, z) }

    /** Letzte Einstellung: aus dem letzten Bild zurück in die Augen (ohne Handkamera). */
    private fun StageBuilder.eyes(from: Int, to: Int) {
        shot(from, to, cam = listOf(Key.Last, Key.Eye), look = listOf(Key.Last, Key.EyeLook), ease = EaseKind.SMOOTH, handheld = 0f)
        mark(from, "eye")
    }

    /** Einstellung mit Marke `camN` (harter Schnitt wie früher jeder Kamera-Aufruf). */
    private fun StageBuilder.cam(
        n: Int, from: Int, to: Int, cam: List<Key>, look: List<Key>, fov: Pair<Float, Float>,
        ease: EaseKind = EaseKind.SMOOTH, follow: Float = 0.1f,
    ) {
        shot(from, to, cam = cam, look = look, fov = fov, ease = ease, follow = follow, cut = true)
        mark(from, "cam$n")
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Prolog: Der Brief
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * | Marke | Tick (Drehbuch) | Bild |
     * | --- | --- | --- |
     * | cam0 | 0 | Kreis um den Altar, Blick auf die Gruppe (Teil 1, Anker Altar) |
     * | cam1 | 10 + altar | „Der Himmel saugt uns hinauf“: von unten in die Höhe, FOV 66 → 96 |
     * | forum | + pull + 20 + 8 | (weiß) Neuaufsetzen: Anker Forum, Darsteller 0 = Hermes; Totale über dem Forum |
     * | cam3 | + 58 | Hermes stürzt wie ein Komet – die Kamera **folgt ihm** hinab |
     * | cam4 | + 18 + 26 | Halbnahe um Hermes (Zustellung, Absender) |
     * | cam5 | + delivery + sender | näher (Bedingung) |
     * | cam6 | + condition | weiter Bogen (Apollon) |
     * | cam7 | + apollo | Nahaufnahme (Fang) |
     * | eye | + catch − 20 | zurück in die Augen bis zum alten `Cine.end` |
     */
    private fun prolog(): StageScript {
        val p1 = 10 + say("prologue.altar", 56)
        val p2 = p1 + say("prologue.pull", 44) + 20
        val p3 = p2 + 8
        val p4 = p3 + 58
        val p5 = p4 + 18 + 26
        val p6 = p5 + say("prologue.delivery", 54) + say("prologue.sender", 84)
        val p7 = p6 + say("prologue.condition", 74)
        val p8 = p7 + say("prologue.apollo", 104)
        val end = p8 + say("prologue.catch", 46)
        return stage(PROLOG, end) {
            // Teil 1 (Anker Altar, Darsteller = Spieler).
            cam(0, 0, p1, orbitAt(30.0, 95.0, 7.0, 4.5, 2.4, 1.6), listOf(Key.Group(1.2)), 62f to 56f)
            cam(1, p1, p3, listOf(w(5.0, 0.6, 5.0), w(1.5, 16.0, 1.5)), listOf(w(0.0, 26.0, 0.0)), 66f to 96f, ease = EaseKind.IN)
            // Teil 2 (Anker Forum, Darsteller 0 = Hermes).
            cam(2, p3, p4, orbitAt(200.0, 240.0, 40.0, 30.0, 16.0, 8.0), listOf(w(0.0, 0.0, 0.0)), 72f to 60f)
            mark(p3, PROLOG_FORUM)
            cam(3, p4, p5, listOf(w(12.0, 2.0, 12.0), w(9.0, 1.0, 9.0)), listOf(Key.Actor(0, 1.4)), 72f to 58f, ease = EaseKind.OUT, follow = 0.15f)
            cam(4, p5, p6, orbitBoss(75.0, 105.0, 3.8, 3.4, 1.6, 1.8), listOf(Key.Actor(0, 2.3)), 52f to 48f)
            cam(5, p6, p7, orbitBoss(110.0, 130.0, 3.0, 3.0, 2.3, 2.3), listOf(Key.Actor(0, 2.6)), 46f to 40f)
            cam(6, p7, p8, orbitBoss(150.0, 190.0, 5.5, 5.5, 1.2, 1.2), listOf(Key.Actor(0, 2.0)), 58f to 54f)
            cam(7, p8, end - EYE, listOf(b(0.0, 2.6, 3.4), b(0.0, 2.4, 2.4)), listOf(Key.Actor(0, 2.9)), 44f to 36f)
            eyes(end - EYE, end)
            mark(end, "end")
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Stufe 1 → 2: Die Feder
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * | Marke | Tick | Bild |
     * | --- | --- | --- |
     * | cam0 | 0 | Nahaufnahme: eine Feder löst sich vom Schienbein und sinkt (Kamera sinkt mit) |
     * | cam1 | falls | Halbnahe um Hermes (Moment, berührt) |
     * | cam2 | + moment + touched | Bogen (anderer Auftrag) |
     * | cam3 | + other_job | weiter Bogen: Dieb, Fingerschnipp, Trugbilder klappen aus ihm heraus |
     * | eye | + thief + 24 + 16 − 20 | zurück in die Augen |
     */
    private fun stage2(): StageScript {
        val f1 = say("feather.falls", 110)
        val f2 = f1 + say("feather.moment", 30) + say("feather.touched", 80)
        val f3 = f2 + say("feather.other_job", 70)
        val end = f3 + say("feather.thief", 76) + GHOST_SPREAD + GHOST_HOLD
        return stage(STAGE2, end) {
            // Schienbein: Hermes + (0,3; 0,5; 0).
            cam(0, 0, f1, listOf(b(2.1, 1.1, 1.8), b(1.6, -1.1, 1.3)), listOf(b(0.3, 0.5, 0.0), b(0.3, -1.9, 0.0)), 34f to 28f)
            cam(1, f1, f2, orbitBoss(60.0, 80.0, 3.4, 3.4, 0.6, 0.6), listOf(Key.Actor(0, 2.7)), 46f to 42f)
            cam(2, f2, f3, orbitBoss(80.0, 120.0, 4.6, 4.6, 0.3, 0.3), listOf(Key.Actor(0, 2.2)), 58f to 52f)
            cam(3, f3, end - EYE, orbitBoss(120.0, 160.0, 7.0, 7.0, 1.6, 1.6), listOf(Key.Actor(0, 1.6)), 62f to 66f)
            eyes(end - EYE, end)
            mark(end, "end")
        }
    }

    /** Trugbilder der Feder-Szene: so lange klappen sie aus ihm heraus, so lange stehen sie (wie `HermesCutscenes`). */
    const val GHOST_SPREAD = 24
    const val GHOST_HOLD = 16

    // ---------------------------------------------------------------------------------------------------------------
    // Stufe 2 → 3: Der Abstieg
    // ---------------------------------------------------------------------------------------------------------------

    /** Dauer des Schlangentanzes im Abstieg (wie `HermesCutscenes.descent`). */
    fun serpentDance(): Int = max(90, 14 + HhaVoiceLengths.ticks("hha.hermes.line.descent.serpents", 70))

    /** So lange kehren die Schlangen in den Stab zurück (`HermesCutscenes.RETURN_TICKS`). */
    const val SERPENT_RETURN = 14

    /**
     * | Marke | Tick | Bild |
     * | --- | --- | --- |
     * | cam0 | 0 | Fahrt auf Hermes zu („Genug“) |
     * | cam1 | 10 + enough | Bogen um ihn (die Toten, jede Seele) |
     * | cam2 | + dead_know + every_soul | Weite um das Forum: der Stab schlägt auf, das Reich der Toten steigt auf |
     * | cam3 | + Stoß + 90 | Halbnahe (der Weg ist kurz) |
     * | cam4 | + way_short | großer Bogen, Blick steigt mit den Schlangen |
     * | eye | + Tanz + 14 − 20 | zurück in die Augen |
     */
    private fun stage3(): StageScript {
        val d1 = 10 + say("descent.enough", 44)
        val d2 = d1 + say("descent.dead_know", 76) + say("descent.every_soul", 80)
        val d3 = d2 + BossTimings.Hermes.TWINS_PLANT_STRIKE + 90
        val d4 = d3 + say("descent.way_short", 62)
        val dance = serpentDance()
        val end = d4 + dance + SERPENT_RETURN
        // Die Schlangen steigen im Tanz um 0,09 je Tick; der Blick folgt der Mitte ihrer Spirale, etwas darunter.
        val rise = 2.0 + 0.09 * dance * 0.7
        return stage(STAGE3, end) {
            cam(0, 0, d1, listOf(b(0.0, 2.2, 5.0), b(0.0, 2.5, 3.0)), listOf(Key.Actor(0, 2.9)), 46f to 38f)
            cam(1, d1, d2, orbitBoss(100.0, 70.0, 3.4, 3.4, 1.0, 1.0), listOf(Key.Actor(0, 2.6)), 44f to 40f)
            // P24: Radius 15 statt 18 – bei 18 fuhr das Ende der Fahrt (70°) durch das Kapitell der Säule bei 67,5° (Ring 20, P14).
            cam(2, d2, d3, orbitAt(40.0, 70.0, 15.0, 15.0, 6.0, 6.0), listOf(Key.Actor(0, 1.0)), 64f to 74f)
            cam(3, d3, d4, orbitBoss(200.0, 230.0, 4.4, 4.4, 0.6, 0.6), listOf(Key.Actor(0, 2.4)), 52f to 48f)
            cam(4, d4, end - EYE, orbitBoss(230.0, 280.0, 11.0, 11.0, 2.0, 2.0, cy = 5.0), listOf(Key.Actor(0, 2.5), Key.Actor(0, rise)), 56f to 52f, follow = 0.2f)
            eyes(end - EYE, end)
            mark(end, "end")
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Stufe 3 → 4: Zeus' Wort
    // ---------------------------------------------------------------------------------------------------------------

    /** Höhe der Augen des Zeus über dem Forum (wie `HermesCutscenes.zeus`). */
    private const val EYES_HEIGHT = 84.0

    /** So lange steigt Hermes in seine letzte Form auf (`glide` im Drehbuch). */
    const val ASCEND_TICKS = 60

    /**
     * Darsteller: 0 = Hermes, 1 = der Held (erster Spieler; steht nach `gatherPlayers` auf +z, Blick zur Mitte = 180°).
     *
     * | Marke | Tick | Bild |
     * | --- | --- | --- |
     * | cam0 | 0 | ganz unten, Blick hinauf zu den Augen im Himmel |
     * | cam1 | 40 + name | über Hermes' Schulter, Blick vom Himmel herab auf ihn |
     * | cam2 | + end_it | Nahaufnahme („Ja, Vater“) |
     * | cam3 | + yes_father | Bogen um ihn (Spaß, Regel) |
     * | cam4 | + fun + rule | die Feder kommt zum Helden (fehlt er, setzt das Drehbuch auf cam5 neu auf) |
     * | cam5 | + feather | Weite: Hermes steigt auf |
     * | eye | + 60 + breath − 20 | zurück in die Augen |
     */
    private fun stage4(): StageScript {
        val z1 = 40 + say("zeus.name", 46)
        val z2 = z1 + say("zeus.end_it", 64)
        val z3 = z2 + say("zeus.yes_father", 44)
        val z4 = z3 + say("zeus.fun", 80) + say("zeus.rule", 88)
        val z5 = z4 + say("zeus.feather", 84)
        val end = z5 + ASCEND_TICKS + say("zeus.breath", 56)
        // Der Held: Versatz um ihn (Blick 180°): vorn = −z, rechts = +x.
        fun hero(dx: Double, dy: Double, dz: Double): Key = Key.ActorRel(1, -dz, dx, dy)
        return stage(STAGE4, end) {
            cam(0, 0, z1, listOf(w(9.0, 0.9, 13.0), w(4.0, 1.3, 7.0)), listOf(w(0.0, EYES_HEIGHT - 30.0, 0.0), w(0.0, EYES_HEIGHT, 0.0)), 78f to 62f)
            cam(1, z1, z2, listOf(b(-5.0, 0.6, 6.0), b(-3.4, 0.9, 4.0)), listOf(w(0.0, EYES_HEIGHT, 0.0), Key.Actor(0, 8.0)), 70f to 58f)
            cam(2, z2, z3, listOf(b(1.8, 0.8, 2.6), b(1.2, 1.0, 2.0)), listOf(Key.Actor(0, 1.1)), 42f to 36f)
            cam(3, z3, z4, orbitBoss(95.0, 70.0, 3.2, 3.2, 1.6, 1.6), listOf(Key.Actor(0, 2.4)), 44f to 40f)
            cam(4, z4, z5, orbit(20.0, 60.0, 3.8, 3.8, 1.4, 1.4) { x, y, z -> hero(x, y, z) }, listOf(Key.Actor(1, 1.3)), 50f to 44f)
            cam(5, z5, end - EYE, orbitAt(10.0, 50.0, 12.0, 12.0, 1.0, 0.6), listOf(Key.Actor(0, 2.0)), 60f to 76f, follow = 0.2f)
            eyes(end - EYE, end)
            mark(end, "end")
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Finale: Zustellung erfolgt
    // ---------------------------------------------------------------------------------------------------------------

    /** Landeplatz des Götterpakets relativ zur Mitte (z) und seine Fallhöhe (wie `HermesCutscenes.finale`). */
    private const val PARCEL_Z = -6.0
    private const val PARCEL_HEIGHT = 46.0

    /** Sinkzeit des Pakets und Pause danach. */
    const val PARCEL_FALL = 96
    const val PARCEL_REST = 10

    /** So lange sinkt Hermes am Anfang des Finales zu Boden. */
    const val FINALE_GLIDE = 40

    /**
     * | Marke | Tick | Bild |
     * | --- | --- | --- |
     * | cam0 | 0 | Kreis um das Forum, Hermes fällt (Blick folgt ihm) |
     * | cam1 | catch + 40 | Halbnahe: er kniet („gefangen“) |
     * | cam2 | + 16 + caught | Nahaufnahme (Unterschrift) |
     * | cam3 | + sign | das Götterpaket sinkt; Blick sinkt mit (OUT wie das Paket) |
     * | cam4 | + 96 + 10 | Bogen um Hermes („zugestellt“) |
     * | eye | + delivered − 20 | zurück in die Augen |
     */
    private fun finale(): StageScript {
        val n1 = say("finale.catch", 92) + FINALE_GLIDE
        val n2 = n1 + 16 + say("finale.caught", 56)
        val n3 = n2 + say("finale.sign", 50)
        val n4 = n3 + PARCEL_FALL + PARCEL_REST
        val end = n4 + say("finale.delivered", 84)
        return stage(FINALE, end) {
            cam(0, 0, n1, orbitAt(60.0, 100.0, 9.0, 9.0, 3.0, 3.0), listOf(Key.Actor(0, 1.0)), 50f to 56f, follow = 0.15f)
            cam(1, n1, n2, orbitBoss(80.0, 100.0, 3.4, 3.4, 0.6, 0.6), listOf(Key.Actor(0, 1.2)), 46f to 42f)
            cam(2, n2, n3, listOf(b(0.0, 2.2, 3.6), b(0.0, 2.2, 2.6)), listOf(Key.Actor(0, 2.4)), 46f to 40f)
            cam(
                3, n3, n4, orbitAt(320.0, 360.0, 14.0, 7.0, 1.0, 1.4, cz = PARCEL_Z),
                listOf(w(0.0, PARCEL_HEIGHT + 0.6, PARCEL_Z), w(0.0, 0.6, PARCEL_Z)), 62f to 52f, ease = EaseKind.OUT,
            )
            cam(4, n4, end - EYE, orbitBoss(100.0, 130.0, 4.0, 4.0, 1.0, 1.0), listOf(Key.Actor(0, 2.1)), 52f to 48f)
            eyes(end - EYE, end)
            mark(end, "end")
        }
    }
}
