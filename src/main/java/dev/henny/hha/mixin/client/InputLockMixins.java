package dev.henny.hha.mixin.client;

import dev.henny.hha.client.cine.CinePlayer;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Während einer Cutscene laufen keine Tastenaktionen (Angreifen, Hotbar, Inventar). */
@Mixin(MinecraftClient.class)
public abstract class InputLockMixins {

    @Inject(method = "handleInputEvents", at = @At("HEAD"), cancellable = true)
    private void hha$lockActions(CallbackInfo ci) {
        if (CinePlayer.getActive()) {
            ci.cancel();
        }
    }
}
