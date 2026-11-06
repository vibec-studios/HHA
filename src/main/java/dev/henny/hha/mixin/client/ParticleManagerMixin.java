package dev.henny.hha.mixin.client;

import dev.henny.hha.client.HhaClientConfig;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Dünnt HHA-Partikel clientseitig aus, wenn die Client-Einstellung
 * {@code particle_density} unter 1 liegt (0 = keine HHA-Partikel).
 * Funktionale Visuals wie die Grapple-Kette bleiben unangetastet, damit
 * die Lesbarkeit der Fähigkeit erhalten bleibt.
 */
@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {

    @Inject(
        method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void hha$thinOutParticles(ParticleEffect effect, double x, double y, double z,
                                      double velocityX, double velocityY, double velocityZ,
                                      CallbackInfoReturnable<Particle> cir) {
        Identifier id = Registries.PARTICLE_TYPE.getId(effect.getType());
        if (id == null || !id.getNamespace().equals("hha")) return;
        if (id.getPath().equals("chain_link")) {
            // Mit 3D-Effekten übernimmt die echte Kette (ChainEffect) die Darstellung.
            if (HhaClientConfig.INSTANCE.enabled("vfx_3d")) cir.setReturnValue(null);
            return;
        }
        double density = HhaClientConfig.INSTANCE.num("particle_density");
        if (density >= 1.0) return;
        if (density <= 0.0 || ThreadLocalRandom.current().nextDouble() >= density) {
            cir.setReturnValue(null);
        }
    }
}
