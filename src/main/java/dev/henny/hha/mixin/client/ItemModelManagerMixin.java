package dev.henny.hha.mixin.client;

import dev.henny.hha.client.model.FlailModelRenderer;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.HeldItemContext;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Merkt sich, wer den Gegenstand gerade hält, solange sein Modell aufgelöst wird. Sondermodelle bekommen nur den
 * Stapel; die Kettenphysik des Hells Mace ({@link FlailModelRenderer}) braucht aber den Träger.
 */
@Mixin(ItemModelManager.class)
public abstract class ItemModelManagerMixin {

    @Inject(method = "update", at = @At("HEAD"))
    private void hha$rememberHolder(ItemRenderState renderState, ItemStack stack, ItemDisplayContext displayContext,
                                    @Nullable World world, @Nullable HeldItemContext heldItemContext, int seed, CallbackInfo ci) {
        FlailModelRenderer.HOLDER.set(heldItemContext);
    }

    @Inject(method = "update", at = @At("RETURN"))
    private void hha$forgetHolder(ItemRenderState renderState, ItemStack stack, ItemDisplayContext displayContext,
                                  @Nullable World world, @Nullable HeldItemContext heldItemContext, int seed, CallbackInfo ci) {
        FlailModelRenderer.HOLDER.remove();
    }
}
