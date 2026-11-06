package dev.henny.hha.mixin;

import com.mojang.datafixers.util.Pair;
import dev.henny.hha.world.HhaBiomes;
import java.util.function.Consumer;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.biome.source.util.VanillaBiomeParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Bringt Olymp und Hades in die Biom-Verteilung der Oberwelt; Fabric hat dafür keine eigene Schnittstelle. */
@Mixin(VanillaBiomeParameters.class)
public abstract class VanillaBiomeParametersMixin {

    @ModifyVariable(method = "writeOverworldBiomeParameters", at = @At("HEAD"), argsOnly = true)
    private Consumer<Pair<MultiNoiseUtil.NoiseHypercube, RegistryKey<Biome>>> hha$addBiomes(
        Consumer<Pair<MultiNoiseUtil.NoiseHypercube, RegistryKey<Biome>>> original
    ) {
        return HhaBiomes.wrap(original);
    }
}
