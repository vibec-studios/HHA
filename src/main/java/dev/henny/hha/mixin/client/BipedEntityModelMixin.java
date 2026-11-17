package dev.henny.hha.mixin.client;

import dev.henny.hha.client.pose.HhaPose;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.state.BipedEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Überarbeitung 3 (P00, PLAN 6.10): Posen der Spieler als Darsteller und der Lobby-Figuren. Nach Vanillas Winkeln legt
 * {@link HhaPose#apply} die Pose auf; ohne Eintrag ändert es nichts. Weil derselbe Zustand an Körper- und
 * Rüstungsmodelle geht, folgen Rüstungsteile von selbst.
 *
 * <p>Ziel geprüft (javap, Yarn 1.21.11+build.6): {@code BipedEntityModel.setAngles(BipedEntityRenderState)}, Deskriptor
 * {@code (Lnet/minecraft/client/render/entity/state/BipedEntityRenderState;)V}. {@code PlayerEntityModel.setAngles}
 * setzt nur Sichtbarkeiten und ruft diese Methode als Letztes auf; TAIL liegt also hinter allen Vanilla-Winkeln.
 */
@Mixin(BipedEntityModel.class)
public abstract class BipedEntityModelMixin {

    @Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/BipedEntityRenderState;)V", at = @At("TAIL"))
    private void hha$pose(BipedEntityRenderState state, CallbackInfo ci) {
        HhaPose.apply((BipedEntityModel<?>) (Object) this, state);
    }
}
