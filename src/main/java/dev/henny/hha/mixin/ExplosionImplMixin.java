package dev.henny.hha.mixin;

import dev.henny.hha.logic.boss.HermesFights;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.explosion.ExplosionImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Explosionen dürfen die Arenen nicht beschädigen. */
@Mixin(ExplosionImpl.class)
public abstract class ExplosionImplMixin {

    @Shadow @Final private ServerWorld world;

    @Inject(method = "getBlocksToDestroy", at = @At("RETURN"), cancellable = true)
    private void hha$keepArenas(CallbackInfoReturnable<List<BlockPos>> cir) {
        List<BlockPos> blocks = cir.getReturnValue();
        if (blocks.isEmpty()) return;
        List<BlockPos> kept = new ArrayList<>(blocks.size());
        for (BlockPos pos : blocks) {
            if (!HermesFights.isProtected(world, pos)) kept.add(pos);
        }
        if (kept.size() != blocks.size()) cir.setReturnValue(kept);
    }
}
