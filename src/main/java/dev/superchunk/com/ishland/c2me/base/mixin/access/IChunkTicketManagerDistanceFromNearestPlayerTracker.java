package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DistanceManager.FixedPlayerDistanceChunkTracker.class)
public interface IChunkTicketManagerDistanceFromNearestPlayerTracker {

    @Accessor("maxDistance")
    int getMaxDistance();

}
