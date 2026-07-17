package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.statuses;

import dev.superchunk.com.ishland.c2me.base.common.scheduler.LockTokenImpl;
import dev.superchunk.com.ishland.c2me.base.common.scheduler.ScheduledTask;
import dev.superchunk.com.ishland.c2me.base.common.scheduler.SchedulingManager;
import dev.superchunk.com.ishland.c2me.base.common.threadstate.ThreadInstrumentation;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IThreadedAnvilChunkStorage;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkState;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.threadstate.ChunkTaskWork;
import dev.superchunk.com.ishland.flowsched.executor.LockToken;
import dev.superchunk.com.ishland.flowsched.scheduler.Cancellable;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemHolder;
import dev.superchunk.com.ishland.flowsched.scheduler.KeyStatusPair;
import io.reactivex.rxjava3.core.Completable;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkDependencies;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class VanillaWorldGenerationDelegate extends NewChunkStatus {

    private static final Logger LOGGER = LoggerFactory.getLogger("VanillaWorldGenerationDelegate");

    private static KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] getDependencyFromStep(ChunkStep step) {
        ArrayList<KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>> deps = new ArrayList<>();
        final ChunkDependencies directDependencies = step.directDependencies();
        for (int x = -directDependencies.getRadius(); x <= directDependencies.getRadius(); x++) {
            for (int z = -directDependencies.getRadius(); z <= directDependencies.getRadius(); z++) {
                if (x == 0 && z == 0) continue;
                final KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext> dep =
                        new KeyStatusPair<>(
                                new ChunkPos(x, z), fromVanillaStatus(directDependencies.get(Math.max(Math.abs(x), Math.abs(z))))
                        );
                deps.add(dep);
            }
        }

        return deps.toArray(KeyStatusPair[]::new);
    }

    private static <T> CompletableFuture<T> runTaskWithLock(ChunkPos target, int radius, SchedulingManager schedulingManager, Supplier<CompletableFuture<T>> action) {
        ObjectArrayList<LockToken> lockTargets = new ObjectArrayList<>((2 * radius + 1) * (2 * radius + 1) + 1);
        for (int x = target.x - radius; x <= target.x + radius; x++)
            for (int z = target.z - radius; z <= target.z + radius; z++)
                lockTargets.add(new LockTokenImpl(schedulingManager.getId(), ChunkPos.asLong(x, z), LockTokenImpl.Usage.WORLDGEN));

        final ScheduledTask<T> task = new ScheduledTask<>(
                target.toLong(),
                action,
                lockTargets.toArray(LockToken[]::new));
        schedulingManager.enqueue(task);
        return task.getFuture();
    }

    private final ChunkStatus status;
    private final KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] genDeps;
    private final KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] loadDeps;
    @Nullable
    private final KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] toRemove;
    @Nullable
    private final KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] toAdd;

    public VanillaWorldGenerationDelegate(int ordinal, ChunkStatus status) {
        super(ordinal, status);
        this.status = status;
        final ChunkStep genStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
        final ChunkStep loadStep = ChunkPyramid.LOADING_PYRAMID.getStepTo(status);
        this.genDeps = getDependencyFromStep(genStep);
        this.loadDeps = getDependencyFromStep(loadStep);

        if (this.genDeps.length != this.loadDeps.length) {
            ObjectOpenHashSet<KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>> toRemove = new ObjectOpenHashSet<>(genDeps);
            toRemove.removeAll(List.of(loadDeps));
            this.toRemove = toRemove.toArray(KeyStatusPair[]::new);

            ObjectOpenHashSet<KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>> toAdd = new ObjectOpenHashSet<>(loadDeps);
            toAdd.removeAll(List.of(genDeps));
            this.toAdd = toAdd.toArray(KeyStatusPair[]::new);
        } else {
            if (Arrays.equals(this.genDeps, this.loadDeps)) {
                this.toRemove = EMPTY_DEPENDENCIES;
                this.toAdd = EMPTY_DEPENDENCIES;
            } else {
                LOGGER.warn("VanillaWorldGenerationDelegate with status {} has the same dependencies length for generation and loading", status);
                this.toRemove = null;
                this.toAdd = null;
            }
        }
    }

    @Override
    public Completable upgradeToThis(ChunkLoadingContext context, Cancellable cancellable) {
//        if (context.holder().getKey().equals(new ChunkPos(100, 100)) && this.status == ChunkStatus.FEATURES) {
//            throw new RuntimeException("boom");
//        }
        final ChunkState state = context.holder().getItem().get();
        if (state.reachedStatus().isOrAfter(this.status)) {
            return Completable.complete();
        }
        final WorldGenContext chunkGenerationContext = ((IThreadedAnvilChunkStorage) context.tacs()).getGenerationContext();
        ChunkAccess chunk = state.chunk();
        if (chunk.getPersistedStatus().isOrAfter(status)) {
            try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(context, this, true))) {
                return Completable.defer(() -> Completable.fromCompletionStage(
                        ChunkPyramid.LOADING_PYRAMID.getStepTo(status)
                                .apply(((IThreadedAnvilChunkStorage) context.tacs()).getGenerationContext(), context.chunks(), chunk)
                                .whenComplete((chunk1, throwable) -> {
                                    if (chunk1 != null) {
                                        context.holder().getItem().set(new ChunkState(chunk1, (ProtoChunk) chunk1, this.status));
                                    }
                                })
                ));
            }
        } else {
            final ChunkStep step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);

            int radius = Math.max(0, step.blockStateWriteRadius());
            final Completable genStep = Completable.defer(() -> Completable.fromCompletionStage(runTaskWithLock(chunk.getPos(), radius, context.schedulingManager(),
                    () -> {
                        try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(context, this, true))) {
                            return step.apply(chunkGenerationContext, context.chunks(), chunk)
                                    .whenComplete((chunk1, throwable) -> {
                                        if (chunk1 != null) {
                                            context.holder().getItem().set(new ChunkState(chunk1, (ProtoChunk) chunk1, this.status));
                                        }
                                    }).thenAccept(__ -> {
                                    });
                        }
                    }
            )));
            // SuperChunk GPU — LIVE cross-chunk CLIMATE batching (batchClimate, default
            // true): for the BIOMES status — which runs BEFORE noise, so the noise seam
            // below is too late for a chunk's own biome stage — kick off a BATCHED GPU
            // climate quart-grid dispatch for this chunk and free this worker until it
            // completes, then run the biome fill (which serves from the deposited grid
            // with NO per-chunk dispatch). Flag OFF / not-ready -> genStep is returned
            // verbatim. Never throws, never hangs.
            if (this.status == ChunkStatus.BIOMES && dev.superchunk.gpu.dfc.BiomeClimateCache.isBatchClimateActive()) {
                return dev.superchunk.gpu.dfc.GpuClimatePrefetch.wrapBiomes(chunkGenerationContext, chunk, genStep);
            }
            // SuperChunk GPU — LIVE cross-chunk batching (batchChunks): for the NOISE
            // status only, kick off a BATCHED GPU density-grid dispatch for this chunk
            // and free this worker until it completes, then run the fill (which serves
            // from the batched grid). Flag OFF / not-ready -> genStep is returned
            // verbatim, i.e. byte-for-byte today's behavior. Never throws, never hangs.
            if (this.status == ChunkStatus.NOISE && dev.superchunk.gpu.dfc.ChunkGridCache.isBatchEnabled()) {
                return dev.superchunk.gpu.dfc.GpuBatchPrefetch.wrapNoise(chunk, genStep);
            }
            return genStep;
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
        return Completable.complete();
    }

    @Override
    public KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] getDependencies(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, ?> holder) {
        final ChunkAccess chunk = holder.getItem().get().chunk();
        if (chunk == null) return genDeps;
        if (chunk.getPersistedStatus().isOrAfter(status)) {
            return relativeToAbsoluteDependencies(holder, loadDeps);
        } else {
            return relativeToAbsoluteDependencies(holder, genDeps);
        }
    }

    @Override
    public KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] getDependenciesToRemove(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, ?> holder) {
        if (this.toRemove == null) return super.getDependenciesToRemove(holder);
        final KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] curDep = holder.getDependencies(this);
        if (curDep.length == this.loadDeps.length) return EMPTY_DEPENDENCIES;
        if (curDep.length == this.genDeps.length) {
            final ChunkAccess chunk = holder.getItem().get().chunk();
            if (chunk == null) return EMPTY_DEPENDENCIES;
            if (!chunk.getPersistedStatus().isOrAfter(status)) return EMPTY_DEPENDENCIES;
            return relativeToAbsoluteDependencies(holder, toRemove);
        }
        LOGGER.warn("Suspicious dependencies length for VanillaWorldGenerationDelegate with status {} on holder {}", this.status, holder.getKey());
        return super.getDependenciesToRemove(holder);
    }

    @Override
    public KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] getDependenciesToAdd(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, ?> holder) {
        if (this.toAdd == null) return super.getDependenciesToAdd(holder);
        final KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] curDep = holder.getDependencies(this);
        if (curDep.length == this.loadDeps.length) return EMPTY_DEPENDENCIES;
        if (curDep.length == this.genDeps.length) {
            final ChunkAccess chunk = holder.getItem().get().chunk();
            if (chunk == null) return EMPTY_DEPENDENCIES;
            if (!chunk.getPersistedStatus().isOrAfter(status)) return EMPTY_DEPENDENCIES;
            return relativeToAbsoluteDependencies(holder, toAdd);
        }
        LOGGER.warn("Suspicious dependencies length for VanillaWorldGenerationDelegate with status {} on holder {}", this.status, holder.getKey());
        return super.getDependenciesToAdd(holder);
    }

    @Override
    public String toString() {
        return this.status.toString();
    }
}
