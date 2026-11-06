package dev.henny.hha.mixin.client;

import dev.henny.hha.client.cine.CinePlayer;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hält die Blickrichtung in Cutscenes fest, damit der Spieler danach nicht verdreht dasteht. */
@Mixin(Mouse.class)
public abstract class MouseMixin {

    @Inject(method = "updateMouse", at = @At("HEAD"), cancellable = true)
    private void hha$freezeLook(double timeDelta, CallbackInfo ci) {
        if (CinePlayer.getActive()) {
            ci.cancel();
        }
    }
}
