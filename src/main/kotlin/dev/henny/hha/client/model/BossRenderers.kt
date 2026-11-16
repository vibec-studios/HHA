package dev.henny.hha.client.model

import dev.henny.hha.Hha
import net.minecraft.client.render.OverlayTexture
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.command.OrderedRenderCommandQueue
import net.minecraft.client.render.entity.EmptyEntityRenderer
import net.minecraft.client.render.entity.feature.FeatureRenderer
import net.minecraft.client.render.entity.feature.FeatureRendererContext
import net.minecraft.client.render.entity.model.EntityModel
import net.minecraft.client.render.entity.model.EntityModelLayer
import net.minecraft.client.render.entity.state.LivingEntityRenderState
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier

/*
 * Modellschichten, gemeinsame Hilfen und die Registrierung aller Boss-, Wächter- und Geschoss-Renderer. Die Renderer
 * selbst wohnen bei ihren Modellen: HermesModel.kt, HadesModel.kt, KronosModel.kt, SerpentModel.kt, PropsModels.kt
 * (Herma, Paket, Geschosse, Kerne), ImpModel.kt, KampeModel.kt, GuardianModels.kt (Wächter).
 */

/** Textur eines Boss-Modells unter textures/entity/boss/. */
internal fun boss(name: String): Identifier = Hha.id("textures/entity/boss/$name.png")

object BossLayers {
    val HERMES = EntityModelLayer(Hha.id("hermes"), "main")
    val HADES = EntityModelLayer(Hha.id("hades"), "main")
    val SERPENT = EntityModelLayer(Hha.id("serpent"), "main")
    val HERMA = EntityModelLayer(Hha.id("herma"), "main")
    val PARCEL = EntityModelLayer(Hha.id("parcel"), "main")
    val PROJECTILES = EntityModelLayer(Hha.id("projectiles"), "main")
    val IMP = EntityModelLayer(Hha.id("imp"), "main")
    val KRONOS = EntityModelLayer(Hha.id("kronos"), "main")
    val WARDEN = EntityModelLayer(Hha.id("warden_of_chains"), "main")
    val SISYPHUS = EntityModelLayer(Hha.id("sisyphus"), "main")
    val IXION = EntityModelLayer(Hha.id("ixion"), "main")
    val TANTALUS = EntityModelLayer(Hha.id("tantalus"), "main")
    val KAMPE = EntityModelLayer(Hha.id("kampe"), "main")
}

/**
 * Feste Modellfaktoren der Wächter (Plan 6.3): Modellhöhe in Einheiten × Faktor / 16 = Höhe im Spiel. Die Wächter
 * werden nicht mehr über das SCALE-Attribut aufgeblasen; die Trefferbox kommt aus dem Entity-Typ.
 */
object GuardianScale {
    /**
     * Das fertige Modell ist bis zum Helmscheitel 113 Einheiten hoch (Plan: 96); 0,62 bringt den Scheitel auf ~4,4
     * Blöcke, nahe an die Trefferbox von 4,2. Der Hände-Fächer und das geschulterte Beil ragen darüber.
     */
    const val WARDEN = 0.62f

    /** 88 Einheiten → 3,8 Blöcke. */
    const val SISYPHUS = 0.69f

    /** Rad-Durchmesser 74 → 3,2 Blöcke. */
    const val IXION = 0.70f

    /** 90 Einheiten → 3,9 Blöcke. */
    const val TANTALUS = 0.69f

    /** 116 Einheiten aufgerichtet → 4,6 Blöcke. */
    const val KAMPE = 0.64f
}

/** Zeichnet eine Leuchtmaske über ein Modell — vollhell, unabhängig vom Licht. */
internal class GlowFeature<S : LivingEntityRenderState, M : EntityModel<S>>(
    context: FeatureRendererContext<S, M>,
    private val layer: (S) -> RenderLayer,
    private val tint: (S) -> Int,
) : FeatureRenderer<S, M>(context) {
    override fun render(matrices: MatrixStack, queue: OrderedRenderCommandQueue, light: Int, state: S, limbAngle: Float, limbDistance: Float) {
        if (state.invisibleToPlayer) return
        queue.getBatchingQueue(1).submitModel(contextModel, state, matrices, layer(state), FULL_BRIGHT, OverlayTexture.DEFAULT_UV, tint(state), null, 0, null)
    }
}

internal const val FULL_BRIGHT = 0xF000F0

/** Registrierung aller Modell-Layer und Renderer. */
object BossClient {
    fun register() {
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.HERMES) { HhaModelData.hermes() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.HADES) { HhaModelData.hades() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.SERPENT) { HhaModelData.serpent() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.HERMA) { HhaModelData.herma() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.PARCEL) { HhaModelData.parcel() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.PROJECTILES) { HhaModelData.projectiles() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.IMP) { HhaModelData.imp() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.KRONOS) { HhaModelData.kronos() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.IMP, ::ImpRenderer)
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.WARDEN) { HhaModelData.warden_of_chains() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.SISYPHUS) { HhaModelData.sisyphus() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.IXION) { HhaModelData.ixion() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.TANTALUS) { HhaModelData.tantalus() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry.registerModelLayer(BossLayers.KAMPE) { HhaModelData.kampe() }
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.WARDEN_OF_CHAINS) { GuardianRenderer(it, WardenModel(it.getPart(BossLayers.WARDEN)), "warden_of_chains", 0.9f, GuardianScale.WARDEN) }
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.SISYPHUS) { GuardianRenderer(it, SisyphusModel(it.getPart(BossLayers.SISYPHUS)), "sisyphus", 0.85f, GuardianScale.SISYPHUS) }
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.IXION) { GuardianRenderer(it, IxionModel(it.getPart(BossLayers.IXION)), "ixion", 1.1f, GuardianScale.IXION) }
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.TANTALUS) { GuardianRenderer(it, TantalusModel(it.getPart(BossLayers.TANTALUS)), "tantalus", 0.7f, GuardianScale.TANTALUS) }
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.KAMPE, ::KampeRenderer)
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.KRONOS, ::KronosRenderer)
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.CORE, ::CoreRenderer)
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.HERMES, ::HermesRenderer)
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.HADES, ::HadesRenderer)
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.SERPENT, ::SerpentRenderer)
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.HERMA, ::HermaRenderer)
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(dev.henny.hha.HhaBlocks.GREEN_FIRE, net.minecraft.client.render.BlockRenderLayer.CUTOUT)
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(dev.henny.hha.HhaBlocks.HADES_PORTAL, net.minecraft.client.render.BlockRenderLayer.TRANSLUCENT)
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.DIVINE_PARCEL, ::ParcelRenderer)
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.HERMES_BOLT, ::BoltRenderer)
    }
}
