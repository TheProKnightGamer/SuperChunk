package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkCache.class)
public interface IServerChunkManager {

    @Accessor("distanceManager")
    DistanceManager getTicketManager();

    @Accessor("mainThreadProcessor")
    ServerChunkCache.MainThreadExecutor getMainThreadExecutor();

    @Invoker("runDistanceManagerUpdates")
    boolean invokeUpdateChunks();

}
