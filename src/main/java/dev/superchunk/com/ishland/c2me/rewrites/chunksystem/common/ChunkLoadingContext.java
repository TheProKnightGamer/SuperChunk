package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common;

import dev.superchunk.com.ishland.c2me.base.common.scheduler.SchedulingManager;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemHolder;
import dev.superchunk.com.ishland.flowsched.scheduler.KeyStatusPair;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.List;

/**
 * Forge 1.20.1 port (plan §1.1/§1.3): 1.20.1 has no {@code StaticCache2D}/{@code GenerationChunkHolder}
 * (1.20.5+). The neighbor cache is the flowsched holder square instead, and {@link #chunks()}
 * materializes the row-major (x-fastest, mirroring vanilla {@code ChunkMap.getChunkRangeFuture})
 * {@code List<ChunkAccess>} that 1.20.1 {@code ChunkStatus.generate}/{@code WorldGenRegion} want.
 * Resolution happens at call time (task execution), matching the lazy resolution the 1.21
 * {@code StaticCache2D<GenerationChunkHolder>} + {@code ChunkStep.apply} pair performed.
 */
public record ChunkLoadingContext(
        ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder,
        ChunkMap tacs, SchedulingManager schedulingManager,
        List<ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface>> chunkHolders,
        int radius,
        KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] dependencies) {

    /**
     * The (2r+1)² neighbor chunk list centered on the target — z-outer/x-inner order exactly
     * like vanilla {@code getChunkRangeFuture} (src1201 ChunkMap:283-287), so
     * {@code list.get(list.size() / 2)} is the center and {@code WorldGenRegion} indexing holds.
     */
    public List<ChunkAccess> chunks() {
        final ObjectArrayList<ChunkAccess> chunks = new ObjectArrayList<>(this.chunkHolders.size());
        for (ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder : this.chunkHolders) {
            final ChunkState state = holder.getItem().get();
            chunks.add(state != null ? state.chunk() : null);
        }
        return chunks;
    }
}
