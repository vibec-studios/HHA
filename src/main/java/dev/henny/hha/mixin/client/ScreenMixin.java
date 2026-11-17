package dev.henny.hha.mixin.client;

import dev.henny.hha.client.rpg.RpgScreens;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Überarbeitung 3 (P00, PLAN 6.10): Während der RPG-Reise zeichnen Lade- und Verbindungsbildschirme nichts, ihre Logik
 * läuft weiter ({@link RpgScreens#replaceRender} entscheidet; ohne Reise immer false).
 *
 * <p>Ziel geprüft (javap, Yarn 1.21.11+build.6): {@code public final void Screen.renderWithTooltip(DrawContext, int, int,
 * float)}, Deskriptor {@code (Lnet/minecraft/client/gui/DrawContext;IIF)V}.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Inject(method = "renderWithTooltip", at = @At("HEAD"), cancellable = true)
    private void hha$replaceRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (RpgScreens.replaceRender((Screen) (Object) this, context, mouseX, mouseY, delta)) {
            ci.cancel();
        }
    }
}
