package dev.henny.hha.mixin.client;

import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Zugriff auf das Himmelsflackern nach einem Blitz, das Himmelsstimmungen durchlassen sollen. */
@Mixin(ClientWorld.class)
public interface ClientWorldAccessor {

    @Accessor("lightningTicksLeft")
    int hha$getLightningTicksLeft();
}
