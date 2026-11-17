package dev.henny.hha.client.audio

import dev.henny.hha.client.rpg.game.RpgClientState
import net.minecraft.client.MinecraftClient
import net.minecraft.client.sound.MovingSoundInstance
import net.minecraft.client.sound.SoundInstance
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.Identifier
import kotlin.math.max
import kotlin.math.min

/**
 * Musik der Mod (PLAN 2.1 Punkt 5, 3.1 Teil 8, REF T15), vier Kanäle in der Musik-Kategorie:
 *
 * - [force]: Boss- bzw. Szenenmusik (`Cine.OP_MUSIC`). Ein Wechsel **blendet über**: das neue Stück steigt um
 *   [FORCE_FADE_IN] je Tick, das alte fällt um [FORCE_FADE_OUT] je Tick. "" blendet aus.
 * - Gebietsmusik ([area] bzw. automatisch im Tick): leiser ([AREA_VOLUME]), nur wenn kein [force]-Stück gewünscht ist;
 *   nach dessen Ende blendet sie in [AREA_RETURN_TICKS] Ticks wieder ein. Automatisch **nur in RPG-Welten**
 *   (`RpgClientState.enabled`): Olymp → `music.olympus`, Tartaros → `music.tartaros`, sonst keine.
 * - [menu]: Musik ohne Welt (Lobby, `music.tunnel`); klingt nur, solange keine Welt geladen ist.
 * - [stinger]: einmaliges Stück über allem (Sieg); duckt alle anderen Kanäle für seine Dauer auf [STINGER_DUCK].
 *
 * Vanilla-Musik schweigt (`musicTracker.stop()` jeden Tick), solange ein eigener Kanal etwas will, und in RPG-Welten
 * immer (PLAN 3.1 Teil 8: Stille statt Vanilla-Musik). Im normalen Modus ohne Kampf bleibt alles wie in Vanilla.
 *
 * Klang-IDs ohne Namensraum bekommen `hha:` (also `"music.tunnel"` = `"hha:music.tunnel"`).
 */
object MusicDirector {

    /** Lautstärkeschritt je Tick, mit dem ein neues [force]-Stück einsetzt (≈ 12 Ticks bis voll). */
    const val FORCE_FADE_IN = 0.08f

    /** Lautstärkeschritt je Tick, mit dem ein abgelöstes Stück ausblendet (≈ 29 Ticks). */
    const val FORCE_FADE_OUT = 0.035f

    /** Pegel der Gebietsmusik. */
    const val AREA_VOLUME = 0.6f

    /** So lange braucht die Gebietsmusik nach dem Ende eines [force]-Stücks bis zum vollen Pegel. */
    const val AREA_RETURN_TICKS = 60

    /** Pegel aller anderen Kanäle, solange ein Stinger läuft. */
    const val STINGER_DUCK = 0.3f

    /** Ducken: Schritt je Tick nach unten (schnell) und wieder hoch (langsam). */
    const val DUCK_DOWN = 0.08f
    const val DUCK_UP = 0.02f

    /** Nach so vielen Ticks muss ein gestartetes Stück hörbar laufen, sonst gilt es als gestoppt (`/stopsound`). */
    const val RESTART_GRACE_TICKS = 40

    /** Höchstens so oft wird ein von außen gestopptes Stück neu gestartet, dann bleibt der Kanal still. */
    const val MAX_RESTARTS = 4

    /** Spätestens dann gilt ein Stinger als vorbei, falls das Ende nicht erkannt wird. */
    const val STINGER_MAX_TICKS = 20 * 30

    const val AREA_OLYMPUS = "hha:music.olympus"
    const val AREA_TARTAROS = "hha:music.tartaros"
    private const val WORLD_OLYMPUS = "hha:olympus"
    private const val WORLD_TARTAROS = "hha:tartaros"

    /** Sieg-Stinger: kommen sie über [force] (`Cine.music`), laufen sie als [stinger] und das Thema blendet aus. */
    val STINGERS = setOf("hha:music.victory_olymp", "hha:music.victory_tartaros")

    /** Stücke ohne Schleife; nach ihrem Ende wird der Kanal frei (Abspann über [force]). */
    val ONCE = STINGERS + "hha:music.credits"

    /** Ortsunabhängiger Musik-Klang, der sich selbst zum Ziel [target] blendet. */
    private class FadingSound(
        id: Identifier, loop: Boolean, private val fadeIn: Float, private val fadeOut: Float,
        var target: Float, private val ducked: Boolean,
    ) : MovingSoundInstance(SoundEvent.of(id), SoundCategory.MUSIC, SoundInstance.createRandom()) {
        private var level = 0.001f

        init {
            repeat = loop
            repeatDelay = 0
            relative = true
            attenuationType = SoundInstance.AttenuationType.NONE
            volume = level
        }

        override fun tick() {
            level = if (level < target) min(target, level + fadeIn) else max(target, level - fadeOut)
            volume = max(0.001f, level * if (ducked) duck else 1f)
            if (target <= 0f && level <= 0.001f) setDone()
        }

        fun fadeAway() {
            target = 0f
        }

        /** Startet auch bei Musikregler 0, damit späteres Hochdrehen wirkt (REF 6.11). */
        override fun shouldAlwaysPlay(): Boolean = true
    }

    /** Ein Kanal: der Wunsch [id] (bei Gebietsmusik aus dem Tick) und der Klang, der gerade [playing] spielt. */
    private class Channel(val fadeIn: Float, val fadeOut: Float, val gain: Float, val ducked: Boolean) {
        var id = ""
        var sound: FadingSound? = null
        var playing = ""
        var ticks = 0
        var restarts = 0
        /** Zuletzt im Tick gewünschtes Stück; ändert es sich, beginnen Neustart-Zähler und Ende von vorn. */
        var lastWanted = ""
        /** Ein Stück ohne Schleife, das schon zu Ende gespielt hat (wird nicht wiederholt). */
        var finished = ""

        val sounding get() = sound != null
    }

    private val forced = Channel(FORCE_FADE_IN, FORCE_FADE_OUT, 1f, true)
    private val areaCh = Channel(AREA_VOLUME / AREA_RETURN_TICKS, FORCE_FADE_OUT, AREA_VOLUME, true)
    private val menuCh = Channel(FORCE_FADE_IN, FORCE_FADE_OUT, 1f, true)
    private val stingerCh = Channel(1f, FORCE_FADE_OUT, 1f, false)
    private val channels = listOf(forced, areaCh, menuCh, stingerCh)

    /** Ausblendende Klänge abgelöster Stücke (für [reset]). */
    private val fading = ArrayList<FadingSound>()

    /** Gemeinsamer Duck-Faktor 0..1 der Kanäle außer dem Stinger. */
    private var duck = 1f

    /** Feste Gebietsmusik von außen; `null` = automatische Auswahl, "" = keine. */
    private var areaOverride: String? = null

    fun init() {}

    /** Je Client-Tick (auch ohne Welt). */
    fun tick(client: MinecraftClient) {
        val world = client.world
        val rpg = world != null && RpgClientState.enabled

        // Stinger zuerst: sein Ende gibt das Ducken frei.
        update(client, stingerCh, stingerCh.id, loop = false)
        if (stingerCh.sounding && stingerCh.ticks > STINGER_MAX_TICKS) {
            release(stingerCh)
            stingerCh.id = ""
        }
        duck = if (stingerCh.sounding) max(STINGER_DUCK, duck - DUCK_DOWN) else min(1f, duck + DUCK_UP)

        update(client, forced, forced.id, loop = forced.id !in ONCE)
        // Ein Stück ohne Schleife (Abspann) ist zu Ende: der Kanal wird frei, die Gebietsmusik kehrt zurück.
        if (forced.id.isNotEmpty() && forced.finished == forced.id) forced.id = ""
        if (stingerCh.id.isNotEmpty() && stingerCh.finished == stingerCh.id) stingerCh.id = ""

        val autoArea = if (rpg) when (world!!.registryKey.value.toString()) {
            WORLD_OLYMPUS -> AREA_OLYMPUS
            WORLD_TARTAROS -> AREA_TARTAROS
            else -> ""
        } else ""
        val areaWanted = if (world == null || forced.id.isNotEmpty()) "" else areaOverride ?: autoArea
        update(client, areaCh, areaWanted, loop = true)

        update(client, menuCh, if (world == null) menuCh.id else "", loop = true)

        fading.removeAll { it.isDone || !client.soundManager.isPlaying(it) }

        val ours = channels.any { it.sounding } || forced.id.isNotEmpty()
        if (ours || rpg) client.musicTracker.stop()
    }

    /**
     * Boss- bzw. Szenenmusik [id] (Registry-ID); "" blendet aus. Gleiche ID wie die laufende: nichts.
     * Sieg-Stinger ([STINGERS]) laufen als [stinger], das laufende Thema blendet dabei aus.
     */
    fun force(id: String) {
        val key = normalize(id)
        if (key in STINGERS) {
            want(forced, "")
            stinger(key)
            return
        }
        want(forced, key)
    }

    /**
     * Gebietsmusik fest auf [id] setzen ("" = keine) statt der automatischen Auswahl; `null` gibt an die
     * automatische Auswahl zurück. Sie klingt nur in einer Welt und nur, solange kein [force]-Stück gewünscht ist.
     */
    fun area(id: String?) {
        areaOverride = id?.let { normalize(it) }
    }

    /** Musik ohne Welt (Lobby: `music.tunnel`); `null` oder "" = aus. Gleiche Überblendung wie [force]. */
    fun menu(id: String?) {
        want(menuCh, normalize(id ?: ""))
    }

    /** Einmaliges Stück über allem (Sieg, Gefallen); duckt die übrige Musik für seine Dauer auf [STINGER_DUCK]. */
    fun stinger(id: String) {
        val key = normalize(id)
        if (key.isEmpty()) return
        // Ein neuer Stinger beginnt immer von vorn, auch mit derselben ID.
        release(stingerCh)
        stingerCh.id = key
        stingerCh.lastWanted = ""
        stingerCh.finished = ""
    }

    /** Verbindungsende: alles aus (der Menü-Wunsch der Lobby bleibt, er klingt ohnehin nur ohne Welt). */
    fun reset() {
        val manager = MinecraftClient.getInstance().soundManager
        for (ch in listOf(forced, areaCh, stingerCh)) {
            ch.sound?.let { manager.stop(it) }
            ch.sound = null
            ch.id = ""
            ch.playing = ""
            ch.lastWanted = ""
            ch.finished = ""
            ch.restarts = 0
        }
        for (s in fading) manager.stop(s)
        fading.clear()
        areaOverride = null
        duck = 1f
    }

    /** Was gerade gewünscht ist (für Prüfungen und Befehle): Kanal → ID. */
    fun state(): Map<String, String> = mapOf(
        "force" to forced.id, "area" to areaCh.playing, "menu" to menuCh.id,
        "stinger" to stingerCh.id,
    )

    // ---------------------------------------------------------------------------------------------

    private fun normalize(id: String): String {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return ""
        val full = if (':' in trimmed) trimmed else "hha:$trimmed"
        return Identifier.tryParse(full)?.toString() ?: ""
    }

    /** Setzt den Wunsch eines Kanals; der Wechsel selbst passiert im nächsten [tick]. */
    private fun want(ch: Channel, key: String) {
        if (key == ch.id) return
        release(ch)
        ch.id = key
    }

    /** Lässt den Klang des Kanals ausblenden; der Kanal ist danach frei. */
    private fun release(ch: Channel) {
        ch.sound?.let {
            it.fadeAway()
            fading += it
        }
        ch.sound = null
        ch.playing = ""
        ch.ticks = 0
    }

    /**
     * Bringt einen Kanal auf [wanted]: startet, blendet ab- und über, startet nach `/stopsound` neu. Ein Stück ohne
     * Schleife ([loop] = false), das zu Ende ist, wird als [Channel.finished] vermerkt und nicht wiederholt.
     */
    private fun update(client: MinecraftClient, ch: Channel, wanted: String, loop: Boolean) {
        if (wanted != ch.lastWanted) {
            ch.lastWanted = wanted
            ch.restarts = 0
            ch.finished = ""
        }
        val current = ch.sound
        if (current != null) {
            ch.ticks++
            val stopped = current.isDone || (ch.ticks > RESTART_GRACE_TICKS && !client.soundManager.isPlaying(current))
            if (stopped) {
                if (loop) ch.restarts++ else ch.finished = ch.playing
                ch.sound = null
                ch.playing = ""
                ch.ticks = 0
            }
        }
        if (ch.sound != null && ch.playing != wanted) release(ch)
        if (wanted.isEmpty() || ch.sound != null || ch.restarts > MAX_RESTARTS || ch.finished == wanted) return
        val id = Identifier.tryParse(wanted) ?: return
        // Ohne Eintrag in sounds.json bliebe der Klang stumm und würde bei jedem Neustart eine Warnung loggen.
        if (client.soundManager.get(id) == null) return
        val sound = FadingSound(id, loop, ch.fadeIn, ch.fadeOut, ch.gain, ch.ducked)
        ch.sound = sound
        ch.playing = wanted
        ch.ticks = 0
        client.soundManager.play(sound)
    }
}
