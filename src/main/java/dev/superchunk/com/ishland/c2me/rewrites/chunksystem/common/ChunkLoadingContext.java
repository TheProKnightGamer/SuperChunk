package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common;

import dev.superchunk.com.ishland.c2me.base.common.scheduler.SchedulingManager;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemHolder;
import dev.superchunk.com.ishland.flowsched.scheduler.KeyStatusPair;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.GenerationChunkHolder;

public record ChunkLoadingContext(
        ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder,
        ChunkMap tacs, SchedulingManager schedulingManager,
        StaticCache2D<GenerationChunkHolder> chunks,
        KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] dependencies) {
}
