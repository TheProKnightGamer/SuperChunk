package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.statuses;

import com.mojang.datafixers.util.Either;
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
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Forge 1.20.1 re-derivation of the neo delegate (plan §1.1/§1.3 — THE hot spot). The 1.21
 * {@code ChunkPyramid}/{@code ChunkStep}/{@code WorldGenContext}/{@code StaticCache2D} model is
 * re-expressed against 1.20.1's {@code ChunkStatus.generate/load} + {@code ChunkMap
 * .getDependencyStatus} model:
 * <ul>
 *   <li><b>Dependencies</b>: radius = {@code status.getRange()}; per-chebyshev-distance status =
 *       the static math of {@code ChunkMap#getDependencyStatus} (src1201 ChunkMap:677 — pure
 *       {@code ChunkStatus} statics, re-implemented in {@link #getDependencyStatus1201} so no
 *       ChunkMap instance is needed at class-init). The 1.21 LOADING_PYRAMID collapses to:
 *       load deps = gen deps iff {@code status.hasLoadDependencies()} (1.20.1: only LIGHT;
 *       mirrors {@code ChunkMap.schedule} routing load-with-deps through
 *       {@code scheduleChunkGeneration}), else empty.</li>
 *   <li><b>Execution</b>: already-persisted → {@code status.load(level, stm, lightEngine, fn, chunk)};
 *       else {@code status.generate(Runnable::run, level, generator, stm, lightEngine, fn, neighbors)}
 *       under {@code runTaskWithLock} — the direct executor keeps the step body on the locked
 *       flowsched worker (neo design). The full-chunk converter is only invoked by FULL, which
 *       this delegate never handles (FULL is {@code ServerAccessible}).</li>
 *   <li><b>Write-lock radius</b>: constant table derived from the 1.21.1 pyramid —
 *       FEATURES → 1, everything else → 0.</li>
 *   <li><b>GPU seams</b>: same {@code GpuClimatePrefetch.wrapBiomes} (forge signature:
 *       BiomeSource + RandomState instead of WorldGenContext) / {@code GpuBatchPrefetch.wrapNoise}
 *       hooks as neo; the vanilla-seam mixin ({@code MixinNoiseBasedChunkGeneratorGpuSeam})
 *       stands down while this module is active (guard in that mixin), so the wraps fire exactly
 *       once per step.</li>
 * </ul>
 */
public class VanillaWorldGenerationDelegate extends NewChunkStatus {

    private static final Logger LOGGER = LoggerFactory.getLogger("VanillaWorldGenerationDelegate");

    /**
     * [VanillaCopy] {@code ChunkMap#getDependencyStatus(ChunkStatus, int)} (src1201 ChunkMap:677) —
     * pure static math on 1.20.1, so no {@code @Invoker} indirection is needed here.
     */
    private static ChunkStatus getDependencyStatus1201(ChunkStatus status, int distance) {
        return distance == 0 ? status.getParent() : ChunkStatus.getStatusAroundFullChunk(ChunkStatus.getDistance(status) + distance);
    }

    private static KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] getDependencies1201(ChunkStatus status, boolean load) {
        if (load && !status.hasLoadDependencies()) {
            return EMPTY_DEPENDENCIES;
        }
        final int radius = status.getRange();
        ArrayList<KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>> deps = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x == 0 && z == 0) continue; // the center's parent status is implied by the sequential pipeline
                final KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext> dep =
                        new KeyStatusPair<>(
                                new ChunkPos(x, z), fromVanillaStatus(getDependencyStatus1201(status, Math.max(Math.abs(x), Math.abs(z))))
                        );
                deps.add(dep);
            }
        }

        //noinspection unchecked
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

    /** Unwraps the 1.20.1 Either-typed step future; the right case never fires on the delegated statuses. */
    private static CompletableFuture<ChunkAccess> unwrap(CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future) {
        return future.thenApply(either -> either.map(Function.identity(), failure -> {
            throw new IllegalStateException("Chunk step returned failure: " + failure);
        }));
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
        this.genDeps = getDependencies1201(status, false);
        this.loadDeps = getDependencies1201(status, true);

        if (this.genDeps.length != this.loadDeps.length) {
            ObjectOpenHashSet<KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>> toRemove = new ObjectOpenHashSet<>(genDeps);
            toRemove.removeAll(List.of(loadDeps));
            //noinspection unchecked
            this.toRemove = toRemove.toArray(KeyStatusPair[]::new);

            ObjectOpenHashSet<KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>> toAdd = new ObjectOpenHashSet<>(loadDeps);
            toAdd.removeAll(List.of(genDeps));
            //noinspection unchecked
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
        final ChunkState state = context.holder().getItem().get();
        if (state.reachedStatus().isOrAfter(this.status)) {
            return Completable.complete();
        }
        final IThreadedAnvilChunkStorage tacs = (IThreadedAnvilChunkStorage) context.tacs();
        final ServerLevel level = tacs.getWorld();
        ChunkAccess chunk = state.chunk();
        // Only ever invoked by ChunkStatus.FULL, which this delegate never handles (plan §1.1).
        final Function<ChunkAccess, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> fullChunkConverter =
                unused -> CompletableFuture.completedFuture(Either.left(unused));
        if (chunk.getStatus().isOrAfter(status)) {
            try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(context, this, true))) {
                return Completable.defer(() -> Completable.fromCompletionStage(
                        unwrap(this.status.load(level, tacs.getStructureTemplateManager(), tacs.getLightingProvider(), fullChunkConverter, chunk))
                                .whenComplete((chunk1, throwable) -> {
                                    if (chunk1 != null) {
                                        context.holder().getItem().set(new ChunkState(chunk1, (ProtoChunk) chunk1, this.status));
                                    }
                                })
                ));
            }
        } else {
            // 1.21.1 ChunkStep.blockStateWriteRadius() constant table: FEATURES → 1, else 0 (plan §1.1).
            int radius = this.status == ChunkStatus.FEATURES ? 1 : 0;
            final Completable genStep = Completable.defer(() -> Completable.fromCompletionStage(runTaskWithLock(chunk.getPos(), radius, context.schedulingManager(),
                    () -> {
                        try (var ignored = ThreadInstrumentation.getCurrent().begin(new ChunkTaskWork(context, this, true))) {
                            return unwrap(this.status.generate(Runnable::run, level, tacs.getGenerator(), tacs.getStructureTemplateManager(), tacs.getLightingProvider(), fullChunkConverter, context.chunks()))
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
                return dev.superchunk.gpu.dfc.GpuClimatePrefetch.wrapBiomes(
                        tacs.getGenerator().getBiomeSource(), level.getChunkSource().randomState(), chunk, genStep);
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
        if (chunk.getStatus().isOrAfter(status)) {
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
            if (!chunk.getStatus().isOrAfter(status)) return EMPTY_DEPENDENCIES;
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
            if (!chunk.getStatus().isOrAfter(status)) return EMPTY_DEPENDENCIES;
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
