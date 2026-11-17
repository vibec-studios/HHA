package dev.henny.hha.client.rpg.game

import dev.henny.hha.Hha
import dev.henny.hha.api.rpg.HhaRpg
import dev.henny.hha.net.RpgEventPayload
import dev.henny.hha.net.RpgPartyPayload
import dev.henny.hha.net.RpgStatePayload
import net.minecraft.client.toast.AdvancementToast
import net.minecraft.client.toast.RecipeToast
import net.minecraft.client.toast.Toast
import net.minecraft.client.toast.TutorialToast
import net.minecraft.text.Text
import net.minecraft.util.math.Vec3d

/**
 * Was der Client über die RPG-Welt weiß (PLAN 6.6), gefüllt aus [RpgStatePayload], [RpgPartyPayload] und
 * [RpgEventPayload]. Im normalen Modus bleibt [enabled] false und alles leer (der Server schickt dort nichts).
 * Bei Verbindungsende setzt [reset] alles zurück. P16 und P18 lesen; Ereignisse über [onEvent].
 *
 * Ereignisse ([RpgEventPayload], Inhalt festgelegt von P06):
 * - `TOAST`: texts = (Schlüssel, Argumente …)
 * - `JOURNAL`: texts = (Eintrag `<boss>` oder `<boss>.done`)
 * - `FALLEN`: numbers = (Tode der Welt), texts = (Rast-Schlüssel, zu der es zurückgeht)
 * - `REST`: numbers = (x, y, z der Rast), texts = (Rast-Schlüssel)
 * - `CREDITS`: numbers = (Spielzeit in Ticks, Tode), texts = Namen (`RpgStory.credits`, geschickt von P17)
 */
object RpgClientState {

    /** Läuft gerade eine RPG-Welt? Alle RPG-Funktionen am Client hängen daran. */
    @JvmStatic
    var enabled = false
        private set
    var path = ""
        private set

    /** [RpgStatePayload.PHASE_PLAY], [RpgStatePayload.PHASE_HOLD], [RpgStatePayload.PHASE_ARRIVE]. */
    var phase = RpgStatePayload.PHASE_PLAY
        private set
    var chapter = ""
        private set
    var chapterIndex = 0
        private set

    /** Phase als Klartext für Lesende: true in Halte oder Ankunftsszene (kein HUD). */
    val arriving: Boolean get() = enabled && phase != RpgStatePayload.PHASE_PLAY

    /** Schlüssel und Argumente des Ziels (leer = keins); [objective] ist der fertige Text. */
    var objectiveKey = ""
        private set
    var objectiveArgs: List<String> = emptyList()
        private set

    /** Zieltext (übersetzt) oder null. */
    var objective: Text? = null
        private set

    /** Zielpunkt für den Richtungspfeil oder null; [targetWorld] = Registry-ID seiner Dimension. */
    var target: Vec3d? = null
        private set
    var targetWorld = ""
        private set
    var waiting: List<String> = emptyList()
        private set
    var done: List<String> = emptyList()
        private set
    var journal: List<String> = emptyList()
        private set
    var restKey = ""
        private set
    var party: List<RpgPartyPayload.Member> = emptyList()
        private set

    private val eventListeners = ArrayList<(RpgEventPayload) -> Unit>()

    fun init() {}

    /** Farbe des Wegs (0xRRGGBB): Himmel Gold, Unterwelt Seelenblau, Addon-Wege ihre eigene. */
    fun color(): Int = when (path) {
        "heaven" -> 0xFFD75E
        "hell" -> 0x3FD8FF
        else -> HhaRpg.path(path)?.color?.and(0xFFFFFF) ?: 0xFFD75E
    }

    fun handleState(payload: RpgStatePayload) {
        enabled = payload.enabled
        path = payload.path
        phase = payload.phase
        chapter = payload.chapter
        chapterIndex = payload.chapterIndex
        objectiveKey = payload.objectiveKey
        objectiveArgs = payload.objectiveArgs
        objective = if (payload.objectiveKey.isEmpty()) null else Text.translatable(payload.objectiveKey, *payload.objectiveArgs.toTypedArray())
        target = if (payload.hasTarget) Vec3d(payload.tx, payload.ty, payload.tz) else null
        targetWorld = payload.targetWorld
        waiting = payload.waiting
        done = payload.done
        journal = payload.journal
        restKey = payload.restKey
    }

    fun handleParty(payload: RpgPartyPayload) {
        party = payload.members
    }

    /** Reicht ein Ereignis an alle Lauscher ([onEvent]) weiter (außerhalb einer RPG-Welt nicht). */
    fun handleEvent(payload: RpgEventPayload) {
        if (!enabled) return
        for (listener in eventListeners) {
            try {
                listener(payload)
            } catch (e: Exception) {
                // Ein fehlerhafter Lauscher darf die Verbindung nicht trennen; er wird geloggt.
                Hha.LOGGER.error("RpgClientState.onEvent: Lauscher für Ereignis {} fehlgeschlagen", payload.kind, e)
            }
        }
    }

    /** Lauscher für [RpgEventPayload] (Toasts, Tagebuch, Tod, Rast, Abspann). */
    fun onEvent(listener: (RpgEventPayload) -> Unit) {
        eventListeners.add(listener)
    }

    /**
     * Aus dem Mixin an `ToastManager.add`: true = Toast verwerfen. In RPG-Welten Fortschritts-, Rezept- und
     * Tutorial-Toasts (PLAN 3.5); alles andere (System, „Läuft gerade“, eigene) und der normale Modus bleiben.
     */
    @JvmStatic
    fun blocksToast(toast: Toast): Boolean =
        enabled && (toast is AdvancementToast || toast is RecipeToast || toast is TutorialToast)

    /** Verbindungsende: zurück in den normalen Modus. */
    fun reset() {
        handleState(RpgStatePayload.DISABLED)
        party = emptyList()
    }
}
