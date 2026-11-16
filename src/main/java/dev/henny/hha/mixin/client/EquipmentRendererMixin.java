package dev.henny.hha.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.LayeringTransform;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.entity.equipment.EquipmentRenderer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.HashMap;
import java.util.Map;

/**
 * Leuchtende Rüstungs-Trims (Überarbeitung 2, PLAN 9.3): Heaven- und Hell-Rüstung bekommen je eine zweite
 * Equipment-Ebene ({@code hha:heaven_glow} bzw. {@code hha:hell_glow}, nur die Trim-Pixel). Jede Equipment-Textur im
 * Namensraum {@code hha}, deren Pfad auf {@code _glow.png} endet, wird mit der Augen-Pipeline gezeichnet (leuchtet bei Tag
 * und Nacht) und pulsiert ruhig zwischen 75 % und 100 % (3,2 s).
 *
 * <p>Wichtig (Falle aus {@code ARMOR_NOTES.md} der Schwester-Mods): Vanillas {@code RenderLayers.eyes()} reicht nicht.
 * Rüstung wird mit {@link LayeringTransform#VIEW_OFFSET_Z_LAYERING} gezeichnet; eine Ebene ohne dieses Layering fällt
 * durch den Tiefentest. Deshalb ein eigener RenderLayer: Augen-Pipeline plus dasselbe Layering wie die Rüstung.
 *
 * <p>{@code @WrapOperation} statt {@code @Redirect}, damit es sich mit HHA Blood und HHA Void verträgt, die dieselbe
 * Stelle anfassen. Greift nur für Texturen, die über {@code equipment/heaven.json} bzw. {@code hell.json} als zweite
 * Ebene kommen ({@code hha:heaven_glow}, {@code hha:hell_glow}; erzeugt von {@code tools/textures/GenHeavenHellArmour.java}).
 *
 * <p>Ziele gegen die Yarn-Jar 1.21.11+build.6 geprüft (javap): {@code EquipmentRenderer.render(LayerType, RegistryKey,
 * Model, Object, ItemStack, MatrixStack, OrderedRenderCommandQueue, int, Identifier, int, int)}, darin
 * {@code RenderLayers.armorCutoutNoCull(Identifier)} direkt vor dem ersten {@code RenderCommandQueue.submitModel(...)}
 * (Index 6 = Tönung); der zweite submitModel-Aufruf (Glint) folgt erst danach, das Flag ist dann schon verbraucht.
 */
@Mixin(EquipmentRenderer.class)
public abstract class EquipmentRendererMixin {

    private static final String RENDER =
        "render(Lnet/minecraft/client/render/entity/equipment/EquipmentModel$LayerType;Lnet/minecraft/registry/RegistryKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/util/Identifier;II)V";

    /** Periodendauer des Pulses in Millisekunden (PLAN 9.3: 3,2 s). */
    @Unique
    private static final long HHA_PULSE_MS = 3200L;

    /** Gesetzt, während die Argumente des submitModel-Aufrufs der Glow-Ebene ausgewertet werden. */
    @Unique
    private static boolean hha$glowPending = false;

    @Unique
    private static final Map<Identifier, RenderLayer> HHA_GLOW_LAYERS = new HashMap<>();

    @Unique
    private static RenderLayer hha$glow(Identifier texture) {
        return HHA_GLOW_LAYERS.computeIfAbsent(texture, t -> RenderLayer.of(
            "hha_armor_glow",
            RenderSetup.builder(RenderPipelines.ENTITY_EYES)
                .texture("Sampler0", t)
                .layeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                .translucent()
                .build()
        ));
    }

    @WrapOperation(
        method = RENDER,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/RenderLayers;armorCutoutNoCull(Lnet/minecraft/util/Identifier;)Lnet/minecraft/client/render/RenderLayer;")
    )
    private RenderLayer hha$glowLayer(Identifier texture, Operation<RenderLayer> original) {
        if ("hha".equals(texture.getNamespace()) && texture.getPath().endsWith("_glow.png")) {
            hha$glowPending = true;
            return hha$glow(texture);
        }
        return original.call(texture);
    }

    @ModifyArg(
        method = RENDER,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/command/RenderCommandQueue;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/RenderLayer;IIILnet/minecraft/client/texture/Sprite;ILnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;)V"),
        index = 6
    )
    private int hha$glowPulse(int tint) {
        if (!hha$glowPending) return tint;
        hha$glowPending = false;
        // ruhiger Puls zwischen 75 % und 100 %
        double t = (System.currentTimeMillis() % HHA_PULSE_MS) / (double) HHA_PULSE_MS;
        double f = 0.75 + 0.25 * (0.5 + 0.5 * Math.sin(t * Math.PI * 2));
        int v = (int) Math.round(255 * f);
        return 0xFF000000 | (v << 16) | (v << 8) | v;
    }
}
