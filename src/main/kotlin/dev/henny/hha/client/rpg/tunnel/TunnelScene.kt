package dev.henny.hha.client.rpg.tunnel

import net.minecraft.client.gui.ScreenRect
import net.minecraft.client.gui.render.state.special.SpecialGuiElementRenderState
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import kotlin.math.PI
import kotlin.math.max

/**
 * Eine schwebende Figur der Tunnelbühne: eigener, von Hand gefüllter Render-Zustand (je Bild neu, wird bis zum
 * Ausführen der Befehle nicht mehr verändert), Mitte des Körpers bei ([x], [y], [z]), Lichtring unter den Füßen mit
 * Stärke [ring] (0 = keiner, matt ≈ 0,3, bereit 1) in [ringColor] (0xRRGGBB), Schleier [veil] (< 0 = keiner, sonst
 * Lauf 0..1 von oben nach unten).
 */
class TunnelFigure(
    val state: PlayerEntityRenderState,
    val x: Float,
    val y: Float,
    val z: Float,
    val ring: Float,
    val ringColor: Int,
    val veil: Float = -1f,
)

/**
 * Zustand der Tunnelbühne für **ein** Bild (PLAN 4.1): Zeit, Fahrt, Tempo, Weg, Figuren, Austrittskreis, Lichtgipfel.
 * Wird als GUI-Sonderelement abgesetzt (`ctx.state.addSpecialElement`) und von [TunnelRenderer] gezeichnet.
 *
 * Kotlin-Falle: Die Java-Methoden `x1()` usw. werden überschrieben, nicht als gleichnamige Properties nachgebaut.
 */
class TunnelScene(
    private val left: Int,
    private val top: Int,
    private val right: Int,
    private val bottom: Int,
    /** Sekunden seit Start der Bühne (Kamera-Treiben). */
    val time: Float,
    /** Fahrt entlang der Röhre (Blöcke, mod [TunnelMotion.SCROLL_PERIOD]). */
    val scroll: Float,
    /** Drehung der Muster (Umläufe). */
    val rotation: Float,
    /** Tempo: Lobby 0,3, Halte 2,5, Rasen 6. */
    val speed: Float,
    val variant: TunnelPipelines.Variant,
    /** Wegfarbe 0xRRGGBB (Addon-Wege, Lichtringe). */
    val tint: Int,
    /** 0..1: das Tunnelende wird durchsichtig (Übergang in die Welt). */
    val exitOpen: Float,
    /** 0..1: Lichtgipfel (weißgoldenes Bild). */
    val flash: Float,
    val figures: List<TunnelFigure>,
    /** Leere Plätze: nur ein zarter Lichtring (x, y, z je Platz). */
    val freeSlots: List<FloatArray>,
    /** Maus −1..1 (Blickversatz). */
    val lookX: Float,
    val lookY: Float,
    private val scissor: ScreenRect? = null,
) : SpecialGuiElementRenderState {
    override fun x1(): Int = left
    override fun y1(): Int = top
    override fun x2(): Int = right
    override fun y2(): Int = bottom
    override fun scale(): Float = 1f
    override fun scissorArea(): ScreenRect? = scissor
    override fun bounds(): ScreenRect? = SpecialGuiElementRenderState.createBounds(left, top, right, bottom, scissor)
}

/**
 * Fortlaufende Bewegung des Tunnels über Lobby, Reise und Halte hinweg (eine Uhr, eine Fahrt): Wer die Bühne zeichnet,
 * ruft [advance] mit dem gewünschten Tempo; zweimal im selben Bild schadet nicht (die Zeit wächst nur um die echte
 * Zwischenzeit).
 */
object TunnelMotion {

    /** Fahrt-Periode: Alle Muster des Shaders sind entlang der Röhre damit periodisch. */
    const val SCROLL_PERIOD = 256f

    /** Blöcke je Sekunde bei Tempo 1. */
    const val UNITS_PER_SPEED = 12f

    /** Grund-Drehung der Muster (Umläufe je Sekunde) plus Anteil je Tempo. */
    const val TURN_BASE = 0.004f
    const val TURN_PER_SPEED = 0.002f

    var time = 0f
        private set
    var scroll = 0f
        private set
    var rotation = 0f
        private set
    var speed = 0.3f
        private set

    private var lastNanos = 0L

    /** Schreitet um die echte Zeit seit dem letzten Aufruf fort (höchstens 0,1 s, damit ein Standbild nicht springt). */
    fun advance(newSpeed: Float) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = now
        speed = max(0f, newSpeed)
        time += dt
        scroll = (scroll + speed * UNITS_PER_SPEED * dt) % SCROLL_PERIOD
        rotation = (rotation + dt * (TURN_BASE + TURN_PER_SPEED * speed)) % 1f
    }

    /** Winkel für Wippen/Drehen der Figuren (Bogenmaß je Sekunde). */
    const val TAU = (2.0 * PI).toFloat()
}
