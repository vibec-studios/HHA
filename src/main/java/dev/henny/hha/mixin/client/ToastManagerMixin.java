package dev.henny.hha.mixin.client;

import dev.henny.hha.client.rpg.game.RpgClientState;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Überarbeitung 3 (P00, PLAN 6.10): In RPG-Welten werden Fortschritts-, Rezept- und Tutorial-Toasts verworfen
 * ({@link RpgClientState#blocksToast}; im normalen Modus immer false).
 *
 * <p>Ziel geprüft (javap, Yarn 1.21.11+build.6): {@code public void ToastManager.add(Toast)}, Deskriptor
 * {@code (Lnet/minecraft/client/toast/Toast;)V} (einzige Methode dieses Namens).
 */
@Mixin(ToastManager.class)
public abstract class ToastManagerMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void hha$blockToast(Toast toast, CallbackInfo ci) {
        if (RpgClientState.blocksToast(toast)) {
            ci.cancel();
        }
    }
}
