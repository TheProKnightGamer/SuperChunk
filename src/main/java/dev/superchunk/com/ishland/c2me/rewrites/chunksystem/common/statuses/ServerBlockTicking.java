package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.statuses;

import com.google.common.base.Suppliers;
import dev.superchunk.com.ishland.c2me.base.common.threadstate.ThreadInstrumentation;
import dev.superchunk.com.ishland.c2me.base.common.config.LateModStatuses;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IThreadedAnvilChunkStorage;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkState;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.Config;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.quirks.FlowableFluidUtils;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.threadstate.ChunkTaskWork;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.fapi.LifecycleEventInvoker;
import dev.superchunk.com.ishland.flowsched.scheduler.Cancellable;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemHolder;
import dev.superchunk.com.ishland.flowsched.scheduler.KeyStatusPair;
import dev.superchunk.com.ishland.flowsched.util.Assertions;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import it.unimi.dsi.fastutil.shorts.ShortList;
import it.unimi.dsi.fastutil.shorts.ShortListIterator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class ServerBlockTicking extends NewChunkStatus {

    private static final KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] deps;

    static {
        deps = new KeyStatusPair[] {
                new KeyStatusPair<>(new ChunkPos(-1, -1), NewChunkStatus.SERVER_ACCESSIBLE),
                new KeyStatusPair<>(new ChunkPos(-1, 0), NewChunkStatus.SERVER_ACCESSIBLE),
                new KeyStatusPair<>(new ChunkPos(-1, 1), NewChunkStatus.SERVER_ACCESSIBLE),
                new KeyStatusPair<>(new ChunkPos(0, -1), NewChunkStatus.SERVER_ACCESSIBLE),
                new KeyStatusPair<>(new ChunkPos(0, 1), NewChunkStatus.SERVER_ACCESSIBLE),
                new KeyStatusPair<>(new ChunkPos(1, -1), NewChunkStatus.SERVER_ACCESSIBLE),
                new KeyStatusPair<>(new ChunkPos(1, 0), NewChunkStatus.SERVER_ACCESSIBLE),
                new KeyStatusPair<>(new ChunkPos(1, 1), NewChunkStatus.SERVER_ACCESSIBLE),
        };
    }

    public ServerBlockTicking(int ordinal) {
        super(ordinal, ChunkStatus.FULL);
    }

    @Override
    public Completable upgradeToThis(ChunkLoadingContext context, Cancellable cancellable) {
        if (Config.filterFluidPostProcessing) {
            try {
                filterFluidTicks(context);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        return Completable
                .fromRunnable(() -> {
                    Assertions.assertTrue(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor().isSameThread());
                    try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(context, this, true))) {
                        final LevelChunk chunk = (LevelChunk) context.holder().getItem().get().chunk();
                        chunk.postProcessGeneration();
                        ServerLevel serverWorld = ((IThreadedAnvilChunkStorage) context.tacs()).getWorld();
                        chunk.registerTickContainerInLevel(serverWorld);
                        sendChunkToPlayer(context);
                        ((IThreadedAnvilChunkStorage) context.tacs()).getTotalChunksLoadedCount().incrementAndGet(); // never decremented in vanilla
                        if (LateModStatuses.fabric_lifecycle_events_v1_CHUNK_LEVEL_TYPE_CHANGE) {
                            LifecycleEventInvoker.invokeChunkLevelTypeChange(serverWorld, chunk, FullChunkStatus.FULL, FullChunkStatus.BLOCK_TICKING);
                        }
                    }
                })
                .subscribeOn(Schedulers.from(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor()));
    }

    private static void filterFluidTicks(ChunkLoadingContext context) {
        final LevelChunk chunk = (LevelChunk) context.holder().getItem().get().chunk();

        Supplier<WorldGenRegion> chunkRegionSupplier = Suppliers.memoize(() -> new WorldGenRegion(((IThreadedAnvilChunkStorage) context.tacs()).getWorld(), context.chunks(), ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.LIGHT), chunk));

        int total = 0;
        int eliminated = 0;
        ShortList[] postProcessingLists = chunk.getPostProcessing();
        for (int i = 0; i < postProcessingLists.length; i++) {
            if (postProcessingLists[i] != null) {
                for (ShortListIterator iterator = postProcessingLists[i].iterator(); iterator.hasNext(); ) {
                    Short short_ = iterator.next();
                    BlockPos blockPos = ProtoChunk.unpackOffsetCoordinates(short_, chunk.getSectionYFromSectionIndex(i), chunk.getPos());
                    BlockState blockState = chunk.getBlockState(blockPos);
                    FluidState fluidState = blockState.getFluidState();
                    if (!fluidState.isEmpty() && fluidState.getType() instanceof FlowingFluid) {
                        total ++;
                        if (!FlowableFluidUtils.needsPostProcessing(chunkRegionSupplier.get(), blockPos, blockState, fluidState)) {
                            iterator.remove();
//                            iterator.set((short) (short_ | (0x4000))); // set a flag
                            eliminated ++;
                        }
                    }
                }
            }
        }

//        if (total > 0) {
//            System.out.println(String.format("Eliminated %d/%d (%.2f%%) post processing fluids in chunk %s", eliminated, total, eliminated / (double) total * 100.0, context.holder().getKey()));
//        }
    }

    private static void sendChunkToPlayer(ChunkLoadingContext context) {
        final LevelChunk chunk = (LevelChunk) context.holder().getItem().get().chunk();
        CompletableFuture<?> completableFuturexx = context.holder().getUserData().get().getPostProcessingFuture();
        if (completableFuturexx.isDone()) {
            ((IThreadedAnvilChunkStorage) context.tacs()).invokeSendToPlayers(chunk);
        } else {
            completableFuturexx.thenAcceptAsync(v -> ((IThreadedAnvilChunkStorage) context.tacs()).invokeSendToPlayers(chunk), ((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor());
        }
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
        ServerLevel serverWorld = ((IThreadedAnvilChunkStorage) context.tacs()).getWorld();
        final LevelChunk chunk = (LevelChunk) context.holder().getItem().get().chunk();
        if (LateModStatuses.fabric_lifecycle_events_v1_CHUNK_LEVEL_TYPE_CHANGE && LifecycleEventInvoker.needsInvokeChunkLevelTypeChange()) {
            return Completable
                    .fromRunnable(() -> {
                        Assertions.assertTrue(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor().isSameThread());
                        LifecycleEventInvoker.invokeChunkLevelTypeChange(serverWorld, chunk, FullChunkStatus.BLOCK_TICKING, FullChunkStatus.FULL);
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
        return "Block Ticking";
    }
}
