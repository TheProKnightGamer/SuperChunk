package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.server.level.ClientInformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientInformation.class)
public interface ISyncedClientOptions {

    @Mutable
    @Accessor("viewDistance")
    void setViewDistance(int viewDistance);

}
