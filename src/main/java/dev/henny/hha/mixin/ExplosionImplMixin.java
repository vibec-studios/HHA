package dev.henny.hha.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.henny.hha.logic.Fx;
import dev.henny.hha.logic.boss.HermesFights;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.explosion.ExplosionImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Explosionen dürfen die Arenen nicht beschädigen. Außerdem: Wer durch eine Explosion k. o. ging, wird am Eingang
 * nicht noch vom selben Knall weggeschleudert (Vanilla gibt den Rückstoß erst nach dem Schaden; Review R3 m1).
 */
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

    @WrapOperation(method = "damageEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;addVelocity(Lnet/minecraft/util/math/Vec3d;)V"))
    private void hha$noKnockbackAfterKnockout(Entity entity, Vec3d velocity, Operation<Void> original) {
        if (entity instanceof ServerPlayerEntity player && !Fx.INSTANCE.canAffect(player)) return;
        original.call(entity, velocity);
    }

    @WrapOperation(method = "damageEntities", at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"))
    private Object hha$noKnockbackPacketAfterKnockout(Map<Object, Object> map, Object key, Object value, Operation<Object> original) {
        if (key instanceof ServerPlayerEntity player && !Fx.INSTANCE.canAffect(player)) return null;
        return original.call(map, key, value);
    }
}
