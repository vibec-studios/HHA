package dev.henny.hha.client.vfx.fx

import dev.henny.hha.client.vfx.VfxDraw
import dev.henny.hha.client.vfx.VfxEffect

/**
 * Basis der neuen Effekte (PLAN 6.4) mit drei Zeichenphasen. Läuft im selben `VfxManager` wie der Bestand (Deckel
 * `MAX_EFFECTS`, Tick, `clear`), zeichnet aber nicht über [render], sondern über:
 * 1. [solid] – `WorldRenderEvents.AFTER_ENTITIES`, schreibt Tiefe (Blöcke, Modellteile, Fels); der Puffer wird danach
 *    sofort geleert, damit alles vor `END_MAIN` in der Tiefe steht;
 * 2. [energy] – `END_MAIN`, Energie vor dem Bildschirm-Pass;
 * 3. [over] – nach dem Bildschirm-Pass (sehr helle Kerne, die der Pass nicht verzerren soll).
 * Jeder Aufruf läuft in `FxClient.guarded`: Ein Fehler wird je Effektklasse und Phase einmal geloggt.
 *
 * Zeit: [time] und [k] rechnen den Zeitfaktor von [FxClock] ein; eingefroren altert der Effekt nicht und läuft auch
 * nicht ab ([expired]). Zum Zeichnen [FxDraw] mit dem übergebenen Rahmen benutzen.
 */
abstract class FxEffect(life: Int) : VfxEffect(life) {

    /** Stand der verlorenen Zeit beim Start (gesetzt von [FxClock.age]). */
    internal var clockBase: Double = Double.NaN

    open fun solid(f: FxFrame) {}

    open fun energy(f: FxFrame) {}

    open fun over(f: FxFrame) {}

    /** Alter Zeichenweg: für neue Effekte leer und nicht überschreibbar. */
    final override fun render(d: VfxDraw, time: Float) {}

    /** Alter in Ticks mit Zwischenbild und Zeitfaktor ([FxClock]). */
    protected fun time(f: FxFrame): Float = FxClock.age(this, age + f.tickDelta)

    /** 0..1 über die Lebenszeit. */
    protected fun k(f: FxFrame): Float = (time(f) / life.coerceAtLeast(1)).coerceIn(0f, 1f)

    /**
     * Abgelaufen nach sichtbarem Alter. Sicherheitsnetz: nach [MAX_HOLD_TICKS] echten Ticks über der Lebenszeit endet
     * der Effekt auf jeden Fall (falls ein Zeitstopp nie zurückkäme).
     */
    override fun expired(): Boolean = FxClock.age(this, age.toFloat()) >= life || age >= life + MAX_HOLD_TICKS

    companion object {
        /** Längste Zeit, die ein Effekt über seine Lebenszeit hinaus eingefroren bleiben darf (Ticks). */
        const val MAX_HOLD_TICKS = 600
    }
}
