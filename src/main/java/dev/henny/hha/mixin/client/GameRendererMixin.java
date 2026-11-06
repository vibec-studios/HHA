package dev.henny.hha.mixin.client;

import dev.henny.hha.client.cine.CinePlayer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sichtfeld der Cutscene-Kamera (Zooms). */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void hha$cinematicFov(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (CinePlayer.getActive()) {
            cir.setReturnValue(CinePlayer.fov(cir.getReturnValueF(), tickProgress));
        }
    }
}
