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

import java.util.BitSet;
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
        FluidMarkFilter fluidFilter = null;
        if (Config.filterFluidPostProcessing) {
            try {
                fluidFilter = filterFluidTicks(context);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
        final FluidMarkFilter fluidMarksToDrop = fluidFilter;
        return Completable
                .fromRunnable(() -> {
                    Assertions.assertTrue(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor().isSameThread());
                    try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(context, this, true))) {
                        final LevelChunk chunk = (LevelChunk) context.holder().getItem().get().chunk();
                        if (fluidMarksToDrop != null) {
                            dropFluidMarks(chunk, fluidMarksToDrop);
                        }
                        chunk.postProcessGeneration();
                        ServerLevel serverWorld = ((IThreadedAnvilChunkStorage) context.tacs()).getWorld();
                        // Vanilla ChunkMap.prepareTickingChunk does postProcessGeneration() then
                        // startTickingChunk() -> LevelChunk.unpackTicks(gameTime), which drains the
                        // chunk's SAVED ticks (LevelChunkTicks.pendingTicks, filled from the region
                        // NBT on load and from ProtoChunk.unpackBlockTicks on generation) into the
                        // live queues. Upstream C2ME does the same here (Yarn spells the method
                        // ServerWorld.disableTickSchedulers). The 1.21.1 port mistranslated it into
                        // registerTickContainerInLevel (Yarn addChunkTickSchedulers) -- which is a
                        // different method, and is already done at SERVER_ACCESSIBLE -- so unpack()
                        // never ran anywhere and every saved tick was stranded.
                        //
                        // This is not merely "saved ticks are late" -- it makes those positions
                        // permanently unschedulable. LevelChunkTicks' List constructor pre-seeds
                        // `ticksPerPosition` with a ScheduledTick.probe for every saved tick, and
                        // schedule() is a no-op when `ticksPerPosition.add` returns false. Entries
                        // leave that set only via poll()/removeIf, and a tick that was never unpacked
                        // is never in `tickQueue`, so it can never be polled. save() then rewrites the
                        // stranded ticks to disk, so it survives restarts and accumulates. (Vanilla's
                        // own collections -- the bundled Lithium world.tick_scheduler rewrite is in
                        // LITHIUM_FORCE_DISABLED and never applies.)
                        //
                        // Broken-tree leaves are the visible symptom (GitHub issue #6): the leaves'
                        // LeavesBlock.updateShape -> scheduleTick is silently dropped, DISTANCE never
                        // reaches 7, isRandomlyTicking() stays false and they never decay. Verified
                        // A/B on one identical saved world: without this line the leaves stay at
                        // distance=1 and cannot be rescheduled; with it they reach distance=7 on load.
                        serverWorld.startTickingChunk(chunk);
                        sendChunkToPlayer(context);
                        ((IThreadedAnvilChunkStorage) context.tacs()).getTotalChunksLoadedCount().incrementAndGet(); // never decremented in vanilla
                        if (LateModStatuses.fabric_lifecycle_events_v1_CHUNK_LEVEL_TYPE_CHANGE) {
                            LifecycleEventInvoker.invokeChunkLevelTypeChange(serverWorld, chunk, FullChunkStatus.FULL, FullChunkStatus.BLOCK_TICKING);
                        }
                    }
                })
                .subscribeOn(Schedulers.from(((IThreadedAnvilChunkStorage) context.tacs()).getMainThreadExecutor()));
    }

    /**
     * Per section, which marks of the chunk's post-processing list {@link #filterFluidTicks} dropped, and
     * the list's length when it looked.
     */
    private record FluidMarkFilter(BitSet[] drop, int[] sizes) {
    }

    /**
     * Finds the fluid post-processing marks that cannot flow, on this worldgen thread, without changing
     * the lists: the chunk is already full, so the server thread's autosave may be serializing them right
     * now, and {@code ChunkSerializer.packOffsets} throws when a list it iterates shrinks. The server thread
     * removes them in {@link #dropFluidMarks}, before {@code postProcessGeneration}.
     */
    private static FluidMarkFilter filterFluidTicks(ChunkLoadingContext context) {
        final LevelChunk chunk = (LevelChunk) context.holder().getItem().get().chunk();

        Supplier<WorldGenRegion> chunkRegionSupplier = Suppliers.memoize(() -> new WorldGenRegion(((IThreadedAnvilChunkStorage) context.tacs()).getWorld(), context.chunks(), ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.LIGHT), chunk));

        BitSet[] drop = null;
        int[] sizes = null;
        ShortList[] postProcessingLists = chunk.getPostProcessing();
        for (int i = 0; i < postProcessingLists.length; i++) {
            final ShortList list = postProcessingLists[i];
            if (list != null) {
                final int size = list.size();
                for (int j = 0; j < size; j++) {
                    short short_ = list.getShort(j);
                    BlockPos blockPos = ProtoChunk.unpackOffsetCoordinates(short_, chunk.getSectionYFromSectionIndex(i), chunk.getPos());
                    BlockState blockState = chunk.getBlockState(blockPos);
                    FluidState fluidState = blockState.getFluidState();
                    if (!fluidState.isEmpty() && fluidState.getType() instanceof FlowingFluid) {
                        if (!FlowableFluidUtils.needsPostProcessing(chunkRegionSupplier.get(), blockPos, blockState, fluidState)) {
                            if (drop == null) {
                                drop = new BitSet[postProcessingLists.length];
                                sizes = new int[postProcessingLists.length];
                            }
                            if (drop[i] == null) {
                                drop[i] = new BitSet(size);
                                sizes[i] = size;
                            }
                            drop[i].set(j);
                        }
                    }
                }
            }
        }
        return drop == null ? null : new FluidMarkFilter(drop, sizes);
    }

    /**
     * On the server thread: removes the marks {@link #filterFluidTicks} found, keeping the rest in order.
     * A list whose length changed since is left whole, as vanilla leaves every list.
     */
    private static void dropFluidMarks(LevelChunk chunk, FluidMarkFilter filter) {
        ShortList[] postProcessingLists = chunk.getPostProcessing();
        for (int i = 0; i < filter.drop().length; i++) {
            final BitSet drop = filter.drop()[i];
            final ShortList list = postProcessingLists[i];
            if (drop == null || list == null || list.size() != filter.sizes()[i]) continue;
            int kept = 0;
            for (int j = 0; j < list.size(); j++) {
                if (!drop.get(j)) {
                    list.set(kept++, list.getShort(j));
                }
            }
            list.size(kept);
        }
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
