package dev.superchunk.com.ishland.c2me.opts.scheduling.mixin.task_scheduling;

import dev.superchunk.com.ishland.c2me.opts.scheduling.common.DuckChunkHolder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.LightLayer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerChunkCache.class)
public abstract class MixinServerChunkManager {

    @Shadow @Nullable protected abstract ChunkHolder getVisibleChunkIfPresent(long pos);

    @Shadow @Final public ServerChunkCache.MainThreadExecutor mainThreadProcessor;

    /**
     * @author ishland
     * @reason reduce scheduling overhead with mainInvokingExecutor
     */
    @Overwrite
    public void onLightUpdate(LightLayer type, SectionPos pos) {
        ChunkHolder chunkHolder = this.getVisibleChunkIfPresent(pos.chunk().toLong()); // thread-safe
        if (chunkHolder != null) {
            ((DuckChunkHolder) chunkHolder).c2me$queueLightSectionDirty(type, pos.y());
            if (((DuckChunkHolder) chunkHolder).c2me$shouldScheduleUndirty()) {
                this.mainThreadProcessor.execute(((DuckChunkHolder) chunkHolder)::c2me$undirtyLight);
            }
        }
    }

}
