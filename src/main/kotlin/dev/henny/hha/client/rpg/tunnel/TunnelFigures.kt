package dev.henny.hha.client.rpg.tunnel

import dev.henny.hha.HhaItems
import dev.henny.hha.api.rpg.HhaRpg
import dev.henny.hha.client.pose.HhaPose
import dev.henny.hha.stage.Pose
import net.minecraft.client.MinecraftClient
import net.minecraft.client.render.entity.state.EntityRenderState
import net.minecraft.client.render.entity.state.PlayerEntityRenderState
import net.minecraft.client.texture.PlayerSkinCache
import net.minecraft.client.util.DefaultSkinHelper
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.ProfileComponent
import net.minecraft.entity.EntityType
import net.minecraft.entity.EquipmentSlot
import net.minecraft.entity.player.SkinTextures
import net.minecraft.item.ItemStack
import net.minecraft.util.math.MathHelper
import java.util.UUID
import java.util.function.Supplier
import kotlin.math.atan2
import kotlin.math.sin

/**
 * Die Figuren der Tunnelbühne (PLAN 4.1, 6.7): Plätze in V-Formation, Skin, Kostüm des Wegs, Schwebe-Pose über
 * [HhaPose], Wippen und Drehen je Figur. Jede Figur bekommt **je Bild einen eigenen** `PlayerEntityRenderState`
 * (der Zustand darf bis zum Ausführen der Zeichenbefehle nicht mehr verändert werden).
 */
object TunnelFigures {

    /** Sichtbare Plätze (Körpermitte x, y, z), Gastgeber vorn in der Mitte. Mehr als acht: Chip „+N“. */
    val SLOTS: List<FloatArray> = listOf(
        floatArrayOf(0f, 0f, 0f),
        floatArrayOf(-1.9f, 0.25f, -1.2f), floatArrayOf(1.9f, 0.25f, -1.2f),
        floatArrayOf(-3.7f, 0.6f, -2.6f), floatArrayOf(3.7f, 0.6f, -2.6f),
        floatArrayOf(-5.4f, 1.0f, -4.2f), floatArrayOf(5.4f, 1.0f, -4.2f),
        floatArrayOf(0f, 1.5f, -5.5f),
    )

    /** Wippen (Blöcke) und seine Dauer (Sekunden je Schwingung, je Figur leicht versetzt). */
    const val BOB = 0.12f
    const val BOB_PERIOD = 4.6f

    /** Leichtes Drehen um die Blickrichtung (Grad) und seine Dauer. */
    const val SWAY_DEG = 12f
    const val SWAY_PERIOD = 9f

    /** Wo die Figuren hinschauen, solange sie nicht fliegen: zur Kamera. */
    private const val CAMERA_Z = 7.5f

    private const val FULL_BRIGHT = 15728880

    /** Was eine Figur je Bild braucht. [facing] 0 = zur Kamera, 1 = in Flugrichtung (−Z). [fly] 0..1 mischt FLOAT → FLOAT_FAST. */
    class Spec(
        val uuid: String,
        val name: String,
        val slot: Int,
        val path: String,
        val ring: Float,
        val ringColor: Int,
        val scale: Float = 1f,
        val facing: Float = 0f,
        val fly: Float = 0f,
        val forward: Float = 0f,
        val veil: Float = -1f,
    )

    /** Lage eines Platzes; Plätze ab 8 liegen hinter der sichtbaren Gruppe (nur für die Rechnung). */
    fun slot(index: Int): FloatArray = SLOTS.getOrElse(index) { floatArrayOf(0f, 1.8f, -8f) }

    /**
     * Baut die Figur für [spec] zur Zeit [time] (Sekunden). [gui] = für `ctx.addEntity` (2D-Rückfall, dort zeigt die
     * Körperdrehung 180° zum Betrachter), sonst für die Tunnelbühne (Weltachsen, 0° = zur Kamera).
     */
    fun build(spec: Spec, time: Float, gui: Boolean = false): TunnelFigure {
        val base = slot(spec.slot)
        val seed = spec.slot * 7 + (spec.uuid.hashCode() and 0xFF)
        val phase = seed * 0.37f
        val bob = BOB * sin(time * TunnelMotion.TAU / BOB_PERIOD + phase)
        val x = base[0]
        val y = base[1] + bob
        val z = base[2] - spec.forward

        // Blick: zur Kamera mit leichtem Pendeln, beim Aufbruch Drehung in Flugrichtung (die Seite bestimmt den Drehsinn)
        val toCamera = Math.toDegrees(atan2(-(0f - x).toDouble(), (CAMERA_Z - z).toDouble())).toFloat()
        val sway = SWAY_DEG * sin(time * TunnelMotion.TAU / SWAY_PERIOD + phase * 1.3f) * (1f - spec.facing)
        val away = if (x < 0f) -180f else 180f
        val yaw = MathHelper.lerp(smooth(spec.facing), toCamera + sway, away)

        val state = PlayerEntityRenderState()
        state.entityType = EntityType.PLAYER
        state.skinTextures = skin(spec.uuid)
        val costume = costume(spec.path)
        state.equippedHeadStack = costume[0]
        state.equippedChestStack = costume[1]
        state.equippedLegsStack = costume[2]
        state.equippedFeetStack = costume[3]
        state.width = 0.6f
        state.height = 1.8f
        state.standingEyeHeight = 1.62f
        state.baseScale = spec.scale.coerceAtLeast(0.001f)
        state.light = FULL_BRIGHT
        state.outlineColor = EntityRenderState.NO_OUTLINE
        state.age = time * 20f
        state.bodyYaw = if (gui) yaw + 180f else yaw
        state.relativeHeadYaw = 8f * sin(time * 0.5f + phase) * (1f - spec.facing)
        state.pitch = -4f + 3f * sin(time * 0.4f + phase)
        state.hatVisible = true
        state.jacketVisible = true
        state.leftPantsLegVisible = true
        state.rightPantsLegVisible = true
        state.leftSleeveVisible = true
        state.rightSleeveVisible = true
        state.capeVisible = true
        state.id = -1 - spec.slot
        val ticks = time * 20f
        val fly = spec.fly.coerceIn(0f, 1f)
        state.setData(
            HhaPose.KEY,
            when {
                fly <= 0.001f -> HhaPose.Frame(HhaPose.angles(Pose.FLOAT, ticks, seed), null, 1f)
                fly >= 0.999f -> HhaPose.Frame(HhaPose.angles(Pose.FLOAT_FAST, ticks, seed), null, 1f)
                else -> HhaPose.Frame(HhaPose.angles(Pose.FLOAT_FAST, ticks, seed), HhaPose.angles(Pose.FLOAT, ticks, seed), smooth(fly))
            },
        )
        return TunnelFigure(state, x, y, z, spec.ring, spec.ringColor, spec.veil)
    }

    private fun smooth(k: Float): Float {
        val t = k.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // ---------------------------------------------------------------------------------------------
    // Skins: eigener sofort, fremde über den Skin-Cache (asynchron, bis dahin Standard-Skin)
    // ---------------------------------------------------------------------------------------------

    private val skins = HashMap<String, Supplier<SkinTextures>>()

    fun skin(uuid: String): SkinTextures {
        val supplier = skins.getOrPut(uuid) { supplierOf(uuid) }
        return supplier.get() ?: DefaultSkinHelper.getSteve()
    }

    private fun supplierOf(uuid: String): Supplier<SkinTextures> {
        val client = MinecraftClient.getInstance()
        val id = runCatching { UUID.fromString(uuid) }.getOrNull() ?: return Supplier { DefaultSkinHelper.getSteve() }
        val fallback = DefaultSkinHelper.getSkinTextures(id)
        if (id == client.session.uuidOrNull) {
            val own = runCatching { client.skinProvider.supplySkinTextures(client.gameProfile, true) }.getOrNull()
            if (own != null) return Supplier { own.get() ?: fallback }
        }
        val cached: Supplier<PlayerSkinCache.Entry>? = runCatching { client.playerSkinCache.getSupplier(ProfileComponent.ofDynamic(id)) }.getOrNull()
        return if (cached == null) Supplier { fallback } else Supplier { cached.get()?.textures ?: fallback }
    }

    /** Lobby verlassen: Skins neu laden lassen (sie können sich geändert haben). */
    fun clearSkins() = skins.clear()

    // ---------------------------------------------------------------------------------------------
    // Kostüm: Rüstung des Wegs (nur im Render-Zustand; im Inventar liegt nie ein Teil)
    // ---------------------------------------------------------------------------------------------

    private val costumes = HashMap<String, Array<ItemStack>>()

    /** Kopf, Brust, Beine, Füße: Himmel Heaven-Set, Unterwelt Hell-Set, Addon-Weg `HhaRpg.Path.costume` (sonst Heaven). */
    fun costume(path: String): Array<ItemStack> = costumes.getOrPut(path) {
        when (path) {
            "hell" -> arrayOf(ItemStack(HhaItems.HELL_HELMET), ItemStack(HhaItems.HELL_CHESTPLATE), ItemStack(HhaItems.HELL_LEGGINGS), ItemStack(HhaItems.HELL_BOOTS))
            "heaven" -> heaven()
            else -> addon(path) ?: heaven()
        }
    }

    private fun heaven() =
        arrayOf(ItemStack(HhaItems.HEAVEN_HELMET), ItemStack(HhaItems.HEAVEN_CHESTPLATE), ItemStack(HhaItems.HEAVEN_LEGGINGS), ItemStack(HhaItems.HEAVEN_BOOTS))

    private fun addon(path: String): Array<ItemStack>? {
        val items = HhaRpg.path(path)?.costume?.invoke() ?: return null
        if (items.isEmpty()) return null
        val out = Array(4) { ItemStack.EMPTY }
        for (stack in items) {
            when (stack.get(DataComponentTypes.EQUIPPABLE)?.slot()) {
                EquipmentSlot.HEAD -> out[0] = stack
                EquipmentSlot.CHEST -> out[1] = stack
                EquipmentSlot.LEGS -> out[2] = stack
                EquipmentSlot.FEET -> out[3] = stack
                else -> {}
            }
        }
        return out
    }
}
