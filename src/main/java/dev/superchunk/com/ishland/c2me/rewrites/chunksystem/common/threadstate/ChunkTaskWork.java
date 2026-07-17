package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.threadstate;

import dev.superchunk.com.ishland.c2me.base.common.threadstate.RunningWork;
import dev.superchunk.com.ishland.c2me.base.common.util.TimeUtil;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IThreadedAnvilChunkStorage;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public record ChunkTaskWork(ServerLevel world, ChunkPos chunkPos, NewChunkStatus status, boolean isUpgrade, long startTime) implements RunningWork {

    public ChunkTaskWork(ChunkLoadingContext context, NewChunkStatus status, boolean isUpgrade) {
        this(
                ((IThreadedAnvilChunkStorage) context.tacs()).getWorld(),
                context.holder().getKey(),
                status,
                isUpgrade,
                System.nanoTime()
        );
    }

    public ChunkTaskWork(ServerLevel world, ChunkPos chunkPos, NewChunkStatus status, boolean isUpgrade) {
        this(
                world,
                chunkPos,
                status,
                isUpgrade,
                System.nanoTime()
        );
    }

    @Override
    public String toString() {
        if (isUpgrade) {
            return String.format(
                    "Upgrading chunk %s to %s in world %s (%s elapsed)",
                    chunkPos,
                    status,
                    world.dimension().location(),
                    TimeUtil.formatElapsedTime(System.nanoTime() - startTime)
            );
        } else {
            return String.format(
                    "Downgrading chunk %s from %s in world %s (%s elapsed)",
                    chunkPos,
                    status,
                    world.dimension().location(),
                    TimeUtil.formatElapsedTime(System.nanoTime() - startTime)
            );
        }
    }
}
