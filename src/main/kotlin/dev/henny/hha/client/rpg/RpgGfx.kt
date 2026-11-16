package dev.henny.hha.client.rpg

import dev.henny.hha.Hha
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gui.DrawContext
import net.minecraft.util.Identifier
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Gemeinsame Bausteine für Lobby und Startanimation: weiße Alpha-Masken aus `textures/gui/rpg/`
 * (erzeugt von `tools/hha_assets/rpg_ui_textures.py`), im Spiel eingefärbt, gedreht und skaliert.
 */
object RpgGfx {

    val RAYS: Identifier = Hha.id("textures/gui/rpg/rays.png")
    val GLOW: Identifier = Hha.id("textures/gui/rpg/glow.png")
    val RING: Identifier = Hha.id("textures/gui/rpg/ring.png")
    val IRIS: Identifier = Hha.id("textures/gui/rpg/iris.png")
    val SPOT: Identifier = Hha.id("textures/gui/rpg/spot.png")

    private val SIZES = mapOf(RAYS to (256 to 256), GLOW to (128 to 128), RING to (256 to 256), IRIS to (256 to 256), SPOT to (128 to 32))

    /** Lobby-Abschied bis zur geschlossenen Iris; danach übernimmt die Startanimation und öffnet sie wieder. */
    const val DEPART_MS = 650L

    /** Zeichnet eine ganze Textur mittig um (cx, cy) in w × h, gedreht um [turns] volle Umdrehungen, eingefärbt. */
    fun texture(ctx: DrawContext, id: Identifier, cx: Int, cy: Int, w: Int, h: Int, turns: Float, color: Int) {
        if (w <= 0 || h <= 0 || (color ushr 24) == 0) return
        val (tw, th) = SIZES[id] ?: (256 to 256)
        val m = ctx.matrices
        m.pushMatrix()
        m.translate(cx.toFloat(), cy.toFloat())
        if (turns != 0f) m.rotate((turns * 2.0 * PI).toFloat())
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, id, -w / 2, -h / 2, 0f, 0f, w, h, tw, th, tw, th, color)
        m.popMatrix()
    }

    /**
     * Irisblende um (cx, cy): innerhalb des Radius durchsichtig mit weichem Rand, außerhalb schwarz.
     * [alpha] blendet die ganze Blende (für das Ausblenden im Spiel).
     */
    fun iris(ctx: DrawContext, width: Int, height: Int, cx: Int, cy: Int, radius: Int, alpha: Float = 1f) {
        val black = ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)
        if (black ushr 24 == 0) return
        if (radius <= 2) {
            ctx.fill(0, 0, width, height, black)
            return
        }
        // Die Maske ist bis 62 % des Radius durchsichtig und am Rand schwarz; so sitzt die weiche Kante am Radius.
        val r = (radius / 0.98f).toInt()
        texture(ctx, IRIS, cx, cy, r * 2, r * 2, 0f, (black or 0xFFFFFF))
        val top = max(0, cy - r)
        val bottom = min(height, cy + r)
        ctx.fill(0, 0, width, top, black)
        ctx.fill(0, bottom, width, height, black)
        ctx.fill(0, top, max(0, cx - r), bottom, black)
        ctx.fill(min(width, cx + r), top, width, bottom, black)
    }

    /** Kleines Überschwingen beim Auftauchen (wie eine Feder, die einrastet). */
    fun easeOutBack(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val x = t.coerceIn(0f, 1f) - 1f
        return 1f + c3 * x.pow(3) + c1 * x.pow(2)
    }

    fun easeInOut(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
    }

    /** Pseudo-Zufall 0..1 aus einer Zahl (stabil über Bilder hinweg). */
    fun hash(n: Int): Float {
        var x = n * 0x27d4eb2d
        x = x xor (x ushr 15)
        x *= 0x2c1b3c6d
        x = x xor (x ushr 12)
        return (x and 0xFFFFFF) / 16777215f
    }
}
