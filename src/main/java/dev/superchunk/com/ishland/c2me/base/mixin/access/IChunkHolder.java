package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.concurrent.Executor;

@Mixin(ChunkHolder.class)
public interface IChunkHolder {

    @Invoker("updateFutures")
    void invokeUpdateFutures(ChunkMap chunkStorage, Executor executor);

    /**
     * Forge's package-private {@code ChunkHolder.currentlyLoading} (a Forge field, so no SRG name):
     * ServerAccessible sets it while a chunk registers its block entities, as
     * ChunkMap.protoChunkToFullChunk does (GitHub #36).
     */
    @Accessor(value = "currentlyLoading", remap = false)
    void superchunk$setCurrentlyLoading(LevelChunk chunk);

}
