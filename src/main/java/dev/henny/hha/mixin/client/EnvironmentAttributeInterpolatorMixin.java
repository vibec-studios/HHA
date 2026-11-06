package dev.henny.hha.mixin.client;

import dev.henny.hha.client.vfx.SkyMood;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeInterpolator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lässt {@link SkyMood} die sichtbaren Himmelswerte überblenden: Farben, Sonne, Sterne, Nebel und Wolken. */
@Mixin(EnvironmentAttributeInterpolator.class)
public abstract class EnvironmentAttributeInterpolatorMixin {

    @SuppressWarnings("unchecked")
    @Inject(method = "get", at = @At("RETURN"), cancellable = true)
    private <V> void hha$skyMood(EnvironmentAttribute<V> attribute, float tickProgress, CallbackInfoReturnable<V> cir) {
        V value = cir.getReturnValue();
        Object replaced = SkyMood.override(attribute, value);
        if (replaced != value) {
            cir.setReturnValue((V) replaced);
        }
    }
}
