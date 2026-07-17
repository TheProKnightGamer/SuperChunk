package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.statuses;

import dev.superchunk.com.ishland.c2me.base.common.config.LateModStatuses;
import dev.superchunk.com.ishland.c2me.base.common.threadstate.ThreadInstrumentation;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IThreadedAnvilChunkStorage;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkState;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.fapi.LifecycleEventInvoker;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.threadstate.ChunkTaskWork;
import dev.superchunk.com.ishland.flowsched.scheduler.Cancellable;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemHolder;
import dev.superchunk.com.ishland.flowsched.scheduler.KeyStatusPair;
import dev.superchunk.com.ishland.flowsched.util.Assertions;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

public class ServerEntityTicking extends NewChunkStatus {

    private static final KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] deps;

    static {
        deps = new KeyStatusPair[] {
                new KeyStatusPair<>(new ChunkPos(-1, -1), NewChunkStatus.BLOCK_TICKING),
                new KeyStatusPair<>(new ChunkPos(-1, 0), NewChunkStatus.BLOCK_TICKING),
                new KeyStatusPair<>(new ChunkPos(-1, 1), NewChunkStatus.BLOCK_TICKING),
                new KeyStatusPair<>(new ChunkPos(0, -1), NewChunkStatus.BLOCK_TICKING),
                new KeyStatusPair<>(new ChunkPos(0, 1), NewChunkStatus.BLOCK_TICKING),
                new KeyStatusPair<>(new ChunkPos(1, -1), NewChunkStatus.BLOCK_TICKING),
                new KeyStatusPair<>(new ChunkPos(1, 0), NewChunkStatus.BLOCK_TICKING),
                new KeyStatusPair<>(new ChunkPos(1, 1), NewChunkStatus.BLOCK_TICKING),
        };
    }

    public ServerEntityTicking(int ordinal) {
        super(ordinal, ChunkStatus.FULL);
    }

    @Override
    public Completable upgradeToThis(ChunkLoadingContext context, Cancellable cancellable) {
        if (LateModStatuses.fabric_lifecycle_events_v1_CHUNK_LEVEL_TYPE_CHANGE && LifecycleEventInvoker.needsInvokeChunkLevelTypeChange()) {
            return Completable.fromRunnable(() -> {
                        Assertions.assertTrue(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor().isSameThread());
                        try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(context, this, true))) {
                            ServerLevel serverWorld = ((IThreadedAnvilChunkStorage) context.tacs()).getWorld();
                            final LevelChunk chunk = (LevelChunk) context.holder().getItem().get().chunk();
                            LifecycleEventInvoker.invokeChunkLevelTypeChange(serverWorld, chunk, FullChunkStatus.BLOCK_TICKING, FullChunkStatus.ENTITY_TICKING);
                        }
                    })
                    .subscribeOn(Schedulers.from(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor()));
        }
        return Completable.complete();
    }

    @Override
    public Completable postUpgradeToThis(ChunkLoadingContext context) {
        return Completable.complete();
    }

    @Override
    public Completable preDowngradeFromThis(ChunkLoadingContext context, Cancellable cancellable) {
        return Completable.complete();
    }

    @Override
    public Completable downgradeFromThis(ChunkLoadingContext context, Cancellable cancellable) {
        if (LateModStatuses.fabric_lifecycle_events_v1_CHUNK_LEVEL_TYPE_CHANGE && LifecycleEventInvoker.needsInvokeChunkLevelTypeChange()) {
            return Completable
                    .fromRunnable(() -> {
                        Assertions.assertTrue(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor().isSameThread());
                        try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(context, this, false))) {
                            ServerLevel serverWorld = ((IThreadedAnvilChunkStorage) context.tacs()).getWorld();
                            final LevelChunk chunk = (LevelChunk) context.holder().getItem().get().chunk();
                            LifecycleEventInvoker.invokeChunkLevelTypeChange(serverWorld, chunk, FullChunkStatus.ENTITY_TICKING, FullChunkStatus.BLOCK_TICKING);
                        }
                    })
                    .subscribeOn(Schedulers.from(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor()));
        }
        return Completable.complete();
    }

    @Override
    public KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] getDependencies(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, ?> holder) {
        return relativeToAbsoluteDependencies(holder, deps);
    }

    @Override
    public KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] getDependenciesToRemove(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, ?> holder) {
        return EMPTY_DEPENDENCIES;
    }

    @Override
    public KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] getDependenciesToAdd(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, ?> holder) {
        return EMPTY_DEPENDENCIES;
    }

    @Override
    public String toString() {
        return "Entity Ticking";
    }
}
