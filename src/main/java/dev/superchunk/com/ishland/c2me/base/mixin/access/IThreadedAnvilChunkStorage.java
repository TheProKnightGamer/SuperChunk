package dev.superchunk.com.ishland.c2me.base.mixin.access;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Mixin(ChunkMap.class)
public interface IThreadedAnvilChunkStorage {

    @Accessor("level")
    ServerLevel getWorld();

    @Invoker("promoteChunkMap")
    boolean invokeUpdateHolderMap();

    @Invoker("save")
    boolean invokeSave(ChunkAccess chunk);

    @Accessor("mainThreadExecutor")
    BlockableEventLoop<Runnable> getMainThreadExecutor();

    @Accessor("lightEngine")
    ThreadedLevelLightEngine getLightingProvider();

    @Accessor("worldGenContext")
    WorldGenContext getGenerationContext();

    @Invoker("onChunkReadyToSend")
    void invokeSendToPlayers(LevelChunk chunk);

    @Accessor("progressListener")
    ChunkProgressListener getWorldGenerationProgressListener();

    @Accessor("tickingGenerated")
    AtomicInteger getTotalChunksLoadedCount();

    @Invoker("getUpdatingChunkIfPresent")
    ChunkHolder invokeGetChunkHolder(long pos);

    @Invoker("onFullChunkStatusChange")
    void invokeOnChunkStatusChange(ChunkPos chunkPos, FullChunkStatus levelType);

    @Accessor("chunkSaveCooldowns")
    Long2LongMap getChunkToNextSaveTimeMs();

    @Accessor("updatingChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> getCurrentChunkHolders();

    @Accessor("modified")
    void setChunkHolderListDirty(boolean value);

    @Invoker("readChunk")
    CompletableFuture<Optional<CompoundTag>> invokeGetUpdatedChunkNbt(ChunkPos chunkPos);

    @Accessor("poiManager")
    PoiManager getPointOfInterestStorage();

}
