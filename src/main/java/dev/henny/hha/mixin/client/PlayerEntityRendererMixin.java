package dev.henny.hha.mixin.client;

import dev.henny.hha.client.pose.HhaPose;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Überarbeitung 3 (P00, PLAN 6.10): Pose und Kostüm der Spieler als Darsteller werden je Bild in den Render-Zustand
 * übernommen ({@link HhaPose#extract}); ohne Eintrag ändert es nichts.
 *
 * <p>Ziel geprüft (javap, Yarn 1.21.11+build.6): {@code PlayerEntityRenderer<AvatarlikeEntity extends PlayerLikeEntity &
 * ClientPlayerLikeEntity>.updateRenderState(AvatarlikeEntity, PlayerEntityRenderState, float)}, Deskriptor
 * {@code (Lnet/minecraft/entity/PlayerLikeEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V}.
 * Die beiden Brückenmethoden mit {@code LivingEntity}/{@code Entity} schließt der volle Deskriptor aus.
 *
 * <p>P20 (Schweben, Marke {@code pose.lift:}): Die Hebung ({@link HhaPose#liftOf}) verschiebt die ganze Figur über
 * {@code getPositionOffset} (Körper, Rüstung, Kopf, Namensschild; der Schatten bleibt am Boden und blendet mit der
 * Höhe aus) und streckt die Sichtbox nach oben, damit die Figur nicht weggeschnitten wird. Ohne Hebung ändert sich
 * nichts. {@code getBoundingBox}/{@code getShadowRadius} erklärt {@code PlayerEntityRenderer} nicht selbst; die
 * Mixin-Klasse erbt deshalb von {@code LivingEntityRenderer} und überschreibt sie (geprüft per javap).
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin extends LivingEntityRenderer {

    private PlayerEntityRendererMixin(EntityRendererFactory.Context ctx, EntityModel model, float shadowRadius) {
        super(ctx, model, shadowRadius);
    }

    @Inject(
        method = "updateRenderState(Lnet/minecraft/entity/PlayerLikeEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
        at = @At("TAIL")
    )
    private void hha$pose(PlayerLikeEntity entity, PlayerEntityRenderState state, float tickProgress, CallbackInfo ci) {
        HhaPose.extract(entity, state);
        HhaPose.extractLift(entity, state, tickProgress);
    }

    @Inject(
        method = "getPositionOffset(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)Lnet/minecraft/util/math/Vec3d;",
        at = @At("RETURN"),
        cancellable = true
    )
    private void hha$lift(PlayerEntityRenderState state, CallbackInfoReturnable<Vec3d> cir) {
        Float lift = state.getData(HhaPose.LIFT_KEY);
        if (lift != null) cir.setReturnValue(cir.getReturnValue().add(0.0, lift, 0.0));
    }

    @Override
    protected Box getBoundingBox(LivingEntity entity) {
        Box box = super.getBoundingBox(entity);
        float lift = HhaPose.liftBound(entity.getId());
        return lift > 0f ? box.stretch(0.0, lift, 0.0) : box;
    }

    @Override
    protected float getShadowRadius(LivingEntityRenderState state) {
        float radius = super.getShadowRadius(state);
        Float lift = state.getData(HhaPose.LIFT_KEY);
        if (lift == null || lift <= 0f) return radius;
        return radius * Math.max(0f, 1f - lift / HhaPose.LIFT_SHADOW_FADE);
    }
}
