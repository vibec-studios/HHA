package dev.henny.hha.mixin.client;

import dev.henny.hha.client.cine.CinePlayer;
import dev.henny.hha.client.vfx.UnderworldShader;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.memory.ObjectPool;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sichtfeld der Cutscene-Kamera (Zooms) und der Bildfilter der Unterwelt. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Shadow @Final private ObjectPool pool;

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void hha$cinematicFov(Camera camera, float tickProgress, boolean changingFov, CallbackInfoReturnable<Float> cir) {
        if (CinePlayer.getActive()) {
            cir.setReturnValue(CinePlayer.fov(cir.getReturnValueF(), tickProgress));
        }
    }

    /** Direkt nach der Welt, vor HUD und Menüs: So färbt der Filter nur die Unterwelt selbst. */
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;drawEntityOutlinesFramebuffer()V", shift = At.Shift.AFTER))
    private void hha$underworldShader(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        UnderworldShader.render(this.pool);
    }
}
