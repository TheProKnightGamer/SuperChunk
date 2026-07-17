package dev.superchunk.com.ishland.c2me.base.common.threadstate;

import dev.superchunk.com.ishland.c2me.base.common.util.TimeUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public record SyncLoadWork(ServerLevel world, ChunkPos chunkPos, ChunkStatus targetStatus, boolean create, long startTime) implements RunningWork {

    public SyncLoadWork(ServerLevel world, ChunkPos chunkPos, ChunkStatus targetStatus, boolean create) {
        this(world, chunkPos, targetStatus, create, System.nanoTime());
    }

    @Override
    public String toString() {
        return String.format("Sync load chunk %s to status %s in world %s (create=%s) (%s elapsed)",
                chunkPos, targetStatus, world.dimension().location(), create, TimeUtil.formatElapsedTime(System.nanoTime() - startTime));
    }

}
