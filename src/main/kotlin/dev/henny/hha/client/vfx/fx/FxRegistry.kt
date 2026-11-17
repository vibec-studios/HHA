package dev.henny.hha.client.vfx.fx

import dev.henny.hha.Hha
import dev.henny.hha.client.HhaClientConfig
import dev.henny.hha.client.vfx.Palette
import dev.henny.hha.client.vfx.VfxEffect
import dev.henny.hha.client.vfx.VfxManager
import dev.henny.hha.logic.Vfx
import net.minecraft.client.world.ClientWorld
import net.minecraft.entity.Entity
import net.minecraft.util.math.Vec3d

/**
 * Alles, was eine Effekt-Fabrik über einen Aufruf weiß (aus `FxPayload` oder lokal aus der Bühne). Die Bedeutung von
 * [dir], [a]–[d], [entity], [entity2] und [extra] hängt von [id] ab (Kodierung der `core.*`-IDs: `API.md`).
 * [palette] ist schon die Gefahrenpalette, wenn [danger] gesetzt ist. [seed] ist je Aufruf verschieden.
 */
class FxArgs(
    val id: String,
    val style: Vfx.Style,
    val palette: Palette,
    val danger: Boolean,
    val pos: Vec3d,
    val dir: Vec3d,
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val life: Int,
    val entity: Entity?,
    val entity2: Entity?,
    val extra: String,
    val world: ClientWorld,
    val seed: Long,
    /** Netz-ID von [entity] (−1 = keine); auch dann gesetzt, wenn der Client das Wesen beim Eintreffen noch nicht kennt. */
    val entityId: Int = -1,
)

/**
 * Verteiler der Text-IDs (PLAN 6.4). Jedes Paket registriert seine IDs mit seinem Präfix in seiner eigenen
 * `init()`; eine Fabrik liefert einen Effekt oder `null` (Aktion ohne Effekt: Ruck, Pass, Zeitfaktor …).
 * Gerüst P00, Besitzer danach P01.
 */
object FxRegistry {

    private val factories = LinkedHashMap<String, (FxArgs) -> VfxEffect?>()
    private val unknown = HashSet<String>()
    private var seed = 0L

    /** Registriert [id]. Doppelte ID: die erste gewinnt, Warnung im Log. */
    fun register(id: String, factory: (FxArgs) -> VfxEffect?) {
        if (factories.putIfAbsent(id, factory) != null) Hha.LOGGER.warn("FxRegistry: ID '{}' doppelt registriert, die erste gilt", id)
    }

    /** Ist [id] registriert? */
    fun has(id: String): Boolean = id in factories

    /** Alle registrierten IDs (sortiert), z. B. für `/hha fx list`. */
    fun ids(): List<String> = factories.keys.sorted()

    /** Spielt [args] lokal ab. Unbekannte IDs werden je ID einmal geloggt; ohne `vfx_3d` entfällt nur das Bild. */
    fun play(args: FxArgs) {
        val factory = factories[args.id]
        if (factory == null) {
            if (unknown.add(args.id)) Hha.LOGGER.warn("FxRegistry: unbekannte Effekt-ID '{}'", args.id)
            return
        }
        val effect = FxClient.guarded("fx:${args.id}") { factory(args) } ?: return
        if (HhaClientConfig.enabled("vfx_3d")) VfxManager.addEffect(effect)
    }

    /** Lokaler Aufruf ohne Server (Bühne, Vorschau). Gefahrenpalette aus, Palette des Stils. */
    fun play(
        world: ClientWorld, id: String, style: Vfx.Style, pos: Vec3d, dir: Vec3d = Vec3d.ZERO, a: Float = 0f, b: Float = 0f,
        c: Float = 0f, d: Float = 0f, life: Int = 20, entity: Entity? = null, entity2: Entity? = null, extra: String = "",
    ) = play(
        FxArgs(id, style, Palette.of(style), false, pos, dir, a, b, c, d, life.coerceAtLeast(1), entity, entity2, extra, world, nextSeed()),
    )

    /** Fortlaufender Seed je Aufruf. */
    fun nextSeed(): Long = ++seed
}
