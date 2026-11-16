package dev.henny.hha.client.model

import dev.henny.hha.Hha
import dev.henny.hha.HhaEntities
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
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

    // Überarbeitung 2: Olymp
    val HEKATE = EntityModelLayer(Hha.id("hekate"), "main")
    val BRONTES = EntityModelLayer(Hha.id("brontes"), "main")
    val KRATOS = EntityModelLayer(Hha.id("kratos"), "main")
    val BIA = EntityModelLayer(Hha.id("bia"), "main")
    val ZEUS = EntityModelLayer(Hha.id("zeus"), "main")
    val KURETE = EntityModelLayer(Hha.id("kurete"), "main")
    val GRIFFIN = EntityModelLayer(Hha.id("griffin"), "main")
    val AUTOMATON = EntityModelLayer(Hha.id("automaton"), "main")
    val OLYMP_PROPS = EntityModelLayer(Hha.id("olymp_props"), "main")
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

    // Überarbeitung 2 (PLAN 10.3): Modellhöhe × Faktor / 16 = Höhe im Spiel.

    /** 74 Einheiten (schwebt 6 über dem Boden) → 3,2 Blöcke. */
    const val HEKATE = 0.69f

    /** 124 Einheiten → 5,4 Blöcke. */
    const val BRONTES = 0.70f

    /** 100 Einheiten → 4,4 Blöcke. */
    const val KRATOS = 0.70f

    /** 92 Einheiten → 4,0 Blöcke. */
    const val BIA = 0.70f

    /** 116 Einheiten → 5,0 Blöcke. */
    const val ZEUS = 0.69f

    /** 48 Einheiten → 2,1 Blöcke. */
    const val KURETE = 0.70f

    /** 42 hoch, 60 lang → 1,8 Blöcke. */
    const val GRIFFIN = 0.69f

    /** 64 Einheiten → 2,8 Blöcke. */
    const val AUTOMATON = 0.70f
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
        net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap.putBlock(dev.henny.hha.HhaBlocks.OLYMPUS_PORTAL, net.minecraft.client.render.BlockRenderLayer.TRANSLUCENT)
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.DIVINE_PARCEL, ::ParcelRenderer)
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(dev.henny.hha.HhaEntities.HERMES_BOLT, ::BoltRenderer)
        registerOlymp()
    }

    /** Überarbeitung 2: Schichten und Renderer der neuen Bosse, Gegner und Requisiten des Olymp (PLAN 10.1/10.3). */
    private fun registerOlymp() {
        EntityModelLayerRegistry.registerModelLayer(BossLayers.HEKATE) { HhaModelData.hekate() }
        EntityModelLayerRegistry.registerModelLayer(BossLayers.BRONTES) { HhaModelData.brontes() }
        EntityModelLayerRegistry.registerModelLayer(BossLayers.KRATOS) { HhaModelData.kratos() }
        EntityModelLayerRegistry.registerModelLayer(BossLayers.BIA) { HhaModelData.bia() }
        EntityModelLayerRegistry.registerModelLayer(BossLayers.ZEUS) { HhaModelData.zeus() }
        EntityModelLayerRegistry.registerModelLayer(BossLayers.KURETE) { HhaModelData.kurete() }
        EntityModelLayerRegistry.registerModelLayer(BossLayers.GRIFFIN) { HhaModelData.griffin() }
        EntityModelLayerRegistry.registerModelLayer(BossLayers.AUTOMATON) { HhaModelData.automaton() }
        EntityModelLayerRegistry.registerModelLayer(BossLayers.OLYMP_PROPS) { HhaModelData.olymp_props() }
        EntityRendererRegistry.register(HhaEntities.HEKATE) {
            GuardianRenderer(it, HekateModel(it.getPart(BossLayers.HEKATE)), "hekate", 0.6f, GuardianScale.HEKATE) { s -> if (s.echo) "hekate_echo" else null }
        }
        EntityRendererRegistry.register(HhaEntities.BRONTES) { GuardianRenderer(it, BrontesModel(it.getPart(BossLayers.BRONTES)), "brontes", 1.3f, GuardianScale.BRONTES) }
        EntityRendererRegistry.register(HhaEntities.KRATOS) { GuardianRenderer(it, KratosModel(it.getPart(BossLayers.KRATOS)), "kratos", 0.9f, GuardianScale.KRATOS) }
        EntityRendererRegistry.register(HhaEntities.BIA) { GuardianRenderer(it, BiaModel(it.getPart(BossLayers.BIA)), "bia", 0.7f, GuardianScale.BIA) }
        EntityRendererRegistry.register(HhaEntities.ZEUS) { GuardianRenderer(it, ZeusModel(it.getPart(BossLayers.ZEUS)), "zeus", 0.9f, GuardianScale.ZEUS) }
        EntityRendererRegistry.register(HhaEntities.KURETE) { GuardianRenderer(it, KureteModel(it.getPart(BossLayers.KURETE)), "kurete", 0.4f, GuardianScale.KURETE) }
        EntityRendererRegistry.register(HhaEntities.GRIFFIN) { GuardianRenderer(it, GriffinModel(it.getPart(BossLayers.GRIFFIN)), "griffin", 0.7f, GuardianScale.GRIFFIN) }
        // Schlafende Wächterin: eigene Textur ohne Leuchtmaske, damit sie wie eine Blockstatue wirkt (Wunsch P11_A).
        EntityRendererRegistry.register(HhaEntities.AUTOMATON) {
            GuardianRenderer(it, AutomatonModel(it.getPart(BossLayers.AUTOMATON)), "automaton", 0.55f, GuardianScale.AUTOMATON) { s ->
                if (s.awake) null else "automaton_sleep"
            }
        }
    }
}
