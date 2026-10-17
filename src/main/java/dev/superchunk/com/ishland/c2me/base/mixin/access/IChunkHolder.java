package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.Executor;

@Mixin(ChunkHolder.class)
public interface IChunkHolder {

    @Invoker("updateFutures")
    void invokeUpdateFutures(ChunkMap chunkStorage, Executor executor);

}
