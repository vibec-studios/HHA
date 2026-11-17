package dev.henny.hha.stage

import dev.henny.hha.logic.Vfx
import dev.henny.hha.logic.boss.BossFx

/*
 * Bühne der Überarbeitung 3 (PLAN 6.5): Szenen als **Daten** mit Zeitleiste. Der Server kennt damit Längen und Marken
 * (`StageScripts.length`, `StageScript.mark`), der Client (`client/cine/stage/StagePlayer`, P05) spielt sie ab.
 *
 * Diese Datei importiert absichtlich nichts aus `client/` und keine Minecraft-Client-Klassen: Sie wird auch auf dem
 * dedizierten Server geladen. Nach Welle 0 eingefroren.
 *
 * Zeiten sind Ticks ab Szenenbeginn. Orte sind [Key]s, die erst der Client auflöst.
 */

/** Ein Ort in einer Szene; aufgelöst vom Client zur Zeit des Bildes. */
sealed interface Key {
    /** Relativ zum Anker von `Cine.stage`: [f] Blöcke vor (in Blickrichtung `yaw`), [r] rechts, [u] hoch. */
    data class At(val f: Double, val r: Double, val u: Double) : Key

    /** Fester Weltpunkt. */
    data class World(val x: Double, val y: Double, val z: Double) : Key

    /** Darsteller [index] (Reihenfolge aus `Cine.stage(actors = …)`), [up] Blöcke über seinen Füßen. */
    data class Actor(val index: Int, val up: Double = 1.6) : Key

    /** Relativ zu Lage und Blick des Darstellers [index]: [f] vor, [r] rechts, [u] hoch. */
    data class ActorRel(val index: Int, val f: Double, val r: Double, val u: Double) : Key

    /** Bühnen-Puppe [name] ([StageBuilder.puppet]), [up] Blöcke über ihren Füßen. */
    data class Puppet(val name: String, val up: Double = 1.6) : Key

    /** Relativ zu Lage und Blick der Puppe [name]. */
    data class PuppetRel(val name: String, val f: Double, val r: Double, val u: Double) : Key

    /** Schwerpunkt aller Spieler-Darsteller, [up] Blöcke hoch. */
    data class Group(val up: Double = 1.4) : Key

    /** Die eigenen Augen (Kamera des Spielers ohne Szene). */
    data object Eye : Key

    /** Ein Punkt in Blickrichtung der eigenen Augen (Blickziel für [Eye]). */
    data object EyeLook : Key

    /** Das zuletzt gezeigte Kamerabild (Anschluss ohne Sprung). */
    data object Last : Key
}

/** Verlauf einer Einstellung über ihre Dauer. */
enum class EaseKind { LINEAR, SMOOTH, IN, OUT, OUT_BACK }

/** Posen der Spieler-Darsteller und Spieler-Puppen (`client/pose/HhaPose`). Nur hinten anfügen. */
enum class Pose { NONE, FLOAT, FLOAT_FAST, LAND, KNEEL, RISE, LOOK_UP, LOOK_HANDS, ARMS_WIDE, GUARD, POINT, BOW, STAGGER, CHEER }

/**
 * Eine Kamera-Einstellung von [from] bis [to] (Ticks, [to] exklusiv). [cam]/[look]: 1 Punkt = fest, 2 = Fahrt (mit
 * [arc]: Bogen um den Blickpunkt), ab 3 = Catmull-Rom-Kurve. [fov] = Sichtfeld am Anfang und Ende. [follow] =
 * Zeitkonstante des Folge-Blicks in Sekunden, [handheld] = Stärke der Handkamera (0 = ruhig), [cut] = harter Schnitt
 * (kein Nachziehen vom letzten Bild).
 */
class Shot(
    val from: Int,
    val to: Int,
    val cam: List<Key>,
    val look: List<Key>,
    val fov: Pair<Float, Float>,
    val ease: EaseKind,
    val arc: Boolean,
    val follow: Float,
    val handheld: Float,
    val cut: Boolean,
)

/** Ein Eintrag einer Spur. [at] = Beginn; [end] = letzter Tick, den der Eintrag als Spanne belegt (sonst [at]). */
sealed class Cue(val at: Int) {
    open val end: Int get() = at
}

/** Zeile in der Dialogbox; [ticks] 0 = Lesedauer. [nameKey]/[color] wie `Cine.dialog`. */
class Caption(at: Int, val speaker: Int, val key: String, val ticks: Int, val nameKey: String, val color: Int) : Cue(at)

/** Titelkarte (wie `Cine.title`). */
class Card(at: Int, val titleKey: String, val subKey: String, val ticks: Int) : Cue(at)

/** Kapitelkarte (wie `Cine.chapter`), [color] 0xRRGGBB. */
class Chapter(at: Int, val kickerKey: String, val titleKey: String, val subKey: String, val ticks: Int, val color: Int) : Cue(at)

/** Blende in [color] von Deckkraft [from] nach [to] über [ticks]. */
class Fade(at: Int, val color: Int, val from: Float, val to: Float, val ticks: Int) : Cue(at)

/** Bildschirmblitz (am Client gedeckelt auf 0,25). */
class Flash(at: Int, val color: Int, val strength: Float, val ticks: Int) : Cue(at)

/** Kamera-Ruck in Grad (am Client gedeckelt auf 4° je Achse). */
class Kick(at: Int, val pitch: Float, val yaw: Float, val roll: Float, val ticks: Int) : Cue(at)

/** Rütteln von [at] bis [until] (exklusiv) mit [strength] (Maß wie `Vfx.shake`). */
class Shake(at: Int, val until: Int, val strength: Float) : Cue(at) {
    override val end: Int get() = until
}

/** Klang [id] (Registry-ID, z. B. `hha:boss.thud`) an der Kamera. */
class Sound(at: Int, val id: String, val volume: Float, val pitch: Float) : Cue(at)

/** Musik über `MusicDirector.force`; "" = aus. */
class Music(at: Int, val id: String) : Cue(at)

/** Effekt per Text-ID (`FxRegistry.play`) an [pos], Richtung zu [dir] (null = keine). */
class FxCue(
    at: Int, val id: String, val style: Vfx.Style, val pos: Key, val dir: Key?, val a: Float, val b: Float, val c: Float,
    val d: Float, val life: Int, val extra: String,
) : Cue(at)

/** Bildschirm-Pass (`ScreenPass.request`) an [pos]. */
class ScreenCue(at: Int, val kind: BossFx.Screen, val pos: Key, val radius: Float, val strength: Float, val ticks: Int) : Cue(at)

/** Pose eines Darstellers für [ticks]; [actor] −1 = alle Spieler-Darsteller. */
class PoseCue(at: Int, val actor: Int, val pose: Pose, val ticks: Int) : Cue(at)

/** Kostüm aller Spieler-Darsteller an/aus; [dissolve] > 0 = löst sich über so viele Ticks auf. */
class Costume(at: Int, val on: Boolean, val dissolve: Int) : Cue(at)

/** Puppe [name] vom Typ [entityType] (Registry-ID) an [pos] mit Blick [yaw]; [costume] `heaven`/`hell`/leer. */
class PuppetSpawn(at: Int, val name: String, val entityType: String, val pos: Key, val yaw: Float, val costume: String) : Cue(at)

/** Puppe [name] läuft von [at] bis [until] entlang [path]; [lift] = Sprungbogen-Höhe. */
class PuppetMove(at: Int, val until: Int, val name: String, val path: List<Key>, val lift: Double) : Cue(at) {
    override val end: Int get() = until
}

/** Aktion [action] der Puppe [name] (Bedeutung je Wesen, `StagePuppets.driver`). */
class PuppetAct(at: Int, val name: String, val action: Int) : Cue(at)

/** Puppe [name] verschwindet. */
class PuppetRemove(at: Int, val name: String) : Cue(at)

/** Benannte Marke, für den Server lesbar ([StageScript.mark]). */
class Mark(at: Int, val name: String) : Cue(at)

/**
 * Ein fertiges, geprüftes Bühnen-Skript. [shots] liegen lückenlos von 0 bis [length]; [cues] sind nach [Cue.at]
 * sortiert (bei gleichem Tick in der Reihenfolge des Drehbuchs).
 */
class StageScript internal constructor(
    val id: String,
    val length: Int,
    val skippable: Boolean,
    val shots: List<Shot>,
    val cues: List<Cue>,
) {
    private val marks: Map<String, Int> = cues.filterIsInstance<Mark>().associate { it.name to it.at }

    /** Tick der Marke [name]; wirft [IllegalArgumentException], wenn das Skript sie nicht hat. */
    fun mark(name: String): Int =
        marks[name] ?: throw IllegalArgumentException("Bühnen-Skript '$id': unbekannte Marke '$name'")

    /** Hat das Skript die Marke [name]? */
    fun hasMark(name: String): Boolean = name in marks

    /** Alle Marken (Name → Tick). */
    fun marks(): Map<String, Int> = marks

    /** Die Einstellung, die zu Tick [t] läuft (null außerhalb 0 until [length]). */
    fun shotAt(t: Int): Shot? = shots.firstOrNull { t >= it.from && t < it.to }
}

/** Baut und prüft ein Bühnen-Skript; siehe [StageBuilder]. */
fun stage(id: String, length: Int, skippable: Boolean = true, build: StageBuilder.() -> Unit): StageScript =
    StageBuilder(id, length).apply(build).build(skippable)

/**
 * Drehbuch einer Szene. Prüfung beim Bauen ([build]), Fehler als [IllegalArgumentException] mit der Skript-ID:
 * - Einstellungen lückenlos und ohne Überlappung von 0 bis `length` (erste beginnt bei 0, jede nächste genau am Ende
 *   der vorigen, letzte endet bei `length`), jede mit `from < to` und mindestens einem Kamera- und Blickpunkt.
 * - Keine Spur jenseits von `length`: jeder Eintrag beginnt in `0..length`, Spannen ([shake], [puppetMove]) enden
 *   spätestens bei `length` und nach ihrem Beginn. Dauern von Karten, Blenden, Zeilen werden nicht geprüft (der Client
 *   schneidet sie am Szenenende ab).
 * - Puppen werden vor (oder im selben Tick wie) ihrer ersten Bewegung/Aktion/Entfernung erzeugt; Namen von Puppen und
 *   Marken sind eindeutig.
 */
class StageBuilder internal constructor(private val id: String, private val length: Int) {

    private val shots = ArrayList<Shot>()
    private val cues = ArrayList<Cue>()

    /** Kamera-Einstellung von [from] bis [to] (exklusiv). [cam]/[look] siehe [Shot]. */
    fun shot(
        from: Int, to: Int, cam: List<Key>, look: List<Key>, fov: Pair<Float, Float> = 70f to 70f,
        ease: EaseKind = EaseKind.SMOOTH, arc: Boolean = false, follow: Float = 0.1f, handheld: Float = 1f, cut: Boolean = false,
    ) {
        shots.add(Shot(from, to, cam.toList(), look.toList(), fov, ease, arc, follow, handheld, cut))
    }

    /** Zeile in der Dialogbox; [ticks] 0 = Lesedauer. */
    fun caption(at: Int, speaker: Int, key: String, ticks: Int = 0, nameKey: String = "", color: Int = 0) {
        cues.add(Caption(at, speaker, key, ticks, nameKey, color))
    }

    fun card(at: Int, titleKey: String, subKey: String, ticks: Int = 80) {
        cues.add(Card(at, titleKey, subKey, ticks))
    }

    fun chapter(at: Int, kickerKey: String, titleKey: String, subKey: String, ticks: Int = 100, color: Int = 0xFFD75E) {
        cues.add(Chapter(at, kickerKey, titleKey, subKey, ticks, color))
    }

    fun fade(at: Int, color: Int, from: Float, to: Float, ticks: Int) {
        cues.add(Fade(at, color, from, to, ticks))
    }

    fun flash(at: Int, color: Int, strength: Float, ticks: Int) {
        cues.add(Flash(at, color, strength, ticks))
    }

    fun kick(at: Int, pitch: Float, yaw: Float = 0f, roll: Float = 0f, ticks: Int = 6) {
        cues.add(Kick(at, pitch, yaw, roll, ticks))
    }

    /** Rütteln von [from] bis [to] (exklusiv). */
    fun shake(from: Int, to: Int, strength: Float) {
        cues.add(Shake(from, to, strength))
    }

    fun sound(at: Int, id: String, volume: Float = 1f, pitch: Float = 1f) {
        cues.add(Sound(at, id, volume, pitch))
    }

    /** Musik; "" = aus. */
    fun music(at: Int, id: String) {
        cues.add(Music(at, id))
    }

    fun fx(
        at: Int, id: String, style: Vfx.Style, pos: Key, dir: Key? = null, a: Float = 0f, b: Float = 0f, c: Float = 0f,
        d: Float = 0f, life: Int = 20, extra: String = "",
    ) {
        cues.add(FxCue(at, id, style, pos, dir, a, b, c, d, life, extra))
    }

    fun screen(at: Int, kind: BossFx.Screen, pos: Key, radius: Float, strength: Float, ticks: Int) {
        cues.add(ScreenCue(at, kind, pos, radius, strength, ticks))
    }

    /** Pose für [ticks]; [actor] −1 = alle Spieler-Darsteller. */
    fun pose(at: Int, actor: Int, pose: Pose, ticks: Int) {
        cues.add(PoseCue(at, actor, pose, ticks))
    }

    /** Kostüm aller Spieler-Darsteller. */
    fun costume(at: Int, on: Boolean, dissolve: Int = 0) {
        cues.add(Costume(at, on, dissolve))
    }

    fun puppet(at: Int, name: String, entityType: String, pos: Key, yaw: Float, costume: String = "") {
        cues.add(PuppetSpawn(at, name, entityType, pos, yaw, costume))
    }

    /** Bewegung von [from] bis [to] (exklusiv) entlang [path]. */
    fun puppetMove(from: Int, to: Int, name: String, path: List<Key>, lift: Double = 0.0) {
        cues.add(PuppetMove(from, to, name, path.toList(), lift))
    }

    fun puppetAct(at: Int, name: String, action: Int) {
        cues.add(PuppetAct(at, name, action))
    }

    fun puppetRemove(at: Int, name: String) {
        cues.add(PuppetRemove(at, name))
    }

    /** Benannte Marke (für den Server lesbar). */
    fun mark(at: Int, name: String) {
        cues.add(Mark(at, name))
    }

    internal fun build(skippable: Boolean): StageScript {
        fun fail(message: String): Nothing = throw IllegalArgumentException("Bühnen-Skript '$id': $message")

        if (id.isBlank()) fail("leere ID")
        if (length <= 0) fail("Länge $length ≤ 0")

        // Einstellungen: lückenlos und ohne Überlappung von 0 bis length.
        if (shots.isEmpty()) fail("keine Einstellung")
        val sorted = shots.sortedBy { it.from }
        var expected = 0
        for (s in sorted) {
            if (s.from < 0 || s.to <= s.from) fail("Einstellung ${s.from}..${s.to} ist leer oder verkehrt")
            if (s.from < expected) fail("Einstellung ${s.from}..${s.to} überlappt die vorige (die endet bei $expected)")
            if (s.from > expected) fail("Lücke zwischen $expected und ${s.from}")
            if (s.cam.isEmpty() || s.look.isEmpty()) fail("Einstellung ${s.from}..${s.to} ohne Kamera- oder Blickpunkt")
            expected = s.to
        }
        if (expected != length) fail("Einstellungen enden bei $expected statt bei $length")

        // Spuren: nichts jenseits von length.
        val spawned = HashMap<String, Int>()
        val marks = HashSet<String>()
        for (cue in cues) {
            if (cue.at < 0 || cue.at > length) fail("${cue::class.simpleName} bei ${cue.at} liegt außerhalb 0..$length")
            if (cue.end < cue.at || cue.end > length) fail("${cue::class.simpleName} ${cue.at}..${cue.end} reicht über $length hinaus oder ist verkehrt")
            when (cue) {
                is PuppetSpawn -> if (spawned.put(cue.name, cue.at) != null) fail("Puppe '${cue.name}' doppelt erzeugt")
                is Mark -> if (!marks.add(cue.name)) fail("Marke '${cue.name}' doppelt")
                else -> {}
            }
        }
        for (cue in cues) {
            val name = when (cue) {
                is PuppetMove -> cue.name
                is PuppetAct -> cue.name
                is PuppetRemove -> cue.name
                else -> continue
            }
            val born = spawned[name] ?: fail("Puppe '$name' wird benutzt, aber nie erzeugt")
            if (cue.at < born) fail("Puppe '$name' wird bei ${cue.at} benutzt, aber erst bei $born erzeugt")
        }
        for (cue in cues) {
            if (cue is PuppetMove && cue.path.isEmpty()) fail("Bewegung der Puppe '${cue.name}' ohne Weg")
        }

        return StageScript(id, length, skippable, sorted, cues.sortedBy { it.at })
    }
}
