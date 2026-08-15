package dev.superchunk.gpu.dfc;

import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.structs.ChunkSystemExecutors;
import dev.superchunk.gpu.OpenCLBackend;
import io.reactivex.rxjava3.core.Completable;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * SuperChunk GPU — ASYNC cross-chunk <b>CLIMATE</b> batch prefetch at the <b>BIOMES</b>
 * status boundary. The biome-climate sibling of {@link GpuBatchPrefetch}.
 *
 * <p><b>Why the BIOMES boundary, not the noise boundary.</b> The biome stage
 * ({@code ChunkAccess.fillBiomesFromNoise}, which {@link BiomeClimateCache} serves) runs
 * at the {@code biomes} status — BEFORE {@code noise} in the vanilla status pipeline.
 * The existing density prefetch seam ({@link GpuBatchPrefetch#wrapNoise}) fires at the
 * biomes→noise boundary, which is too late for a chunk's OWN biome stage. So
 * {@link dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.statuses.VanillaWorldGenerationDelegate
 * VanillaWorldGenerationDelegate} calls {@link #wrapBiomes} for the {@code biomes}
 * status' generation step, mirroring the noise wrap one status earlier:
 * <ol>
 *   <li>derive the chunk's quart lattice + resolve the dimension's fused climate group
 *       ({@link BiomeClimateCache#planBatch} — the chunk pos is known here and the quart
 *       lattice is a pure function of it and the generation height accessor);</li>
 *   <li>{@link GpuChunkBatcher#submitTo submit} the chunk's climate request to the
 *       DEDICATED climate batcher (aux program slot = this dimension's byte-parity-gated
 *       {@code df_batch_lattice_multichunk} climate program) and get a future back;</li>
 *   <li>return a {@link Completable} that completes when the batched dispatch finishes —
 *       the C2ME worker is <b>RELEASED</b> during the wait (resumed via callback), so
 *       concurrent chunks pile up at the batcher and coalesce into K-chunk dispatches;</li>
 *   <li>on completion, deposit the {@code M*gridLen} grids in {@link GpuClimateStore};
 *       the biome fill then serves every quart sample from them with NO per-chunk
 *       dispatch ({@link BiomeClimateCache#beginChunk} consume path).</li>
 * </ol>
 *
 * <p><b>Worker is truly freed.</b> The future's continuation runs on the climate
 * batcher thread; it is hopped onto a C2ME worker via
 * {@link ChunkSystemExecutors#backgroundScheduler} ({@code observeOn}) so scheduler
 * bookkeeping never runs on the single drainer thread and no worker is parked waiting
 * on a batch (the noise-path pattern, verbatim).
 *
 * <p><b>Never hangs, never throws into the scheduler.</b> Any failure — backend not
 * ready, group not registered/batched, bad extent, submit rejected, dispatch failed —
 * silently returns the unmodified step (or completes the prefetch without storing), and
 * the chunk uses the FUNNELED per-chunk climate path (counted as a miss). The batcher's
 * window timeout guarantees the future always completes, so the wait is always bounded.
 *
 * <p>Flag-gated: callers only invoke this when
 * {@link BiomeClimateCache#isBatchClimateActive()} ({@code -Dsuperchunk.biome.batchClimate},
 * default true). Flag OFF = this class is never reached.
 */
public final class GpuClimatePrefetch {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    /**
     * CLIMATE CHAIN ({@code -Dsuperchunk.biome.chainClimate}, default true): when the
     * LIVE density batcher exists and its dispatcher has (or can attach) the chained
     * climate program, submit ONE COMBINED request here at the BIOMES seam that
     * computes this chunk's climate quart grids AND its density corner grids in the
     * SAME slot round trip — the climate work rides GPU/pipeline time the density
     * batch already pays for (no dedicated climate pipeline threads, no second driver
     * stream, no extra completion event), and the density grids simply arrive one
     * status earlier (held in {@link GpuBatchStore} until the noise fill; the noise
     * seam skips its own submit via the PENDING marker). Falls back to the split path
     * (dedicated climate batcher + noise-seam density prefetch) whenever the chain
     * can't apply. Disabled under {@code compactIds} PROBE (its per-chunk aux capture
     * needs the NoiseChunk, which only exists at the noise seam).
     */
    private static final boolean CHAIN_CLIMATE =
            Boolean.parseBoolean(System.getProperty("superchunk.biome.chainClimate", "true"));

    private GpuClimatePrefetch() {
    }

    /**
     * Wraps the {@code biomes}-status generation step with an async batched climate
     * prefetch. Returns {@code step} unchanged whenever batching can't apply for this
     * chunk (behavior then identical to the funneled per-chunk path). Never throws.
     */
    public static Completable wrapBiomes(WorldGenContext genContext, ChunkAccess chunk, Completable step) {
        try {
            if (genContext == null || chunk == null) {
                return step;
            }
            // Consumer check: don't prefetch climate grids for a biome source that provably
            // never calls Climate.Sampler.sample() (The End etc.) — the deposit would be
            // consumed at beginChunk and then discarded UNREAD. See neverSamplesClimate.
            try {
                if (BiomeClimateCache.neverSamplesClimate(genContext.generator().getBiomeSource())) {
                    return step;
                }
            } catch (Throwable ignored) {
                // fall through — the beginChunk-side gate still protects the arm/funnel path
            }
            // The dimension's compiled router — the SAME temperature identity
            // BiomeClimateCache registered its fused climate group under (and the same
            // one the per-chunk sampler wraps at serve time).
            NoiseRouter router;
            try {
                RandomState rs = genContext.level().getChunkSource().randomState();
                router = rs == null ? null : rs.router();
            } catch (Throwable t) {
                return step;
            }

            final BiomeClimateCache.ClimatePlan plan = BiomeClimateCache.planBatch(chunk, router);
            if (plan == null) {
                return step;
            }
            // Already prefetched for this chunk (e.g. a re-upgrade) — don't double-submit.
            if (GpuClimateStore.contains(plan.key())) {
                return step;
            }

            // CLIMATE CHAIN: try the combined climate+density submit on the shared
            // density batcher first; null = chain not applicable -> split path below.
            if (CHAIN_CLIMATE && !CompactIds.PROBE) {
                Completable chained = tryChain(plan, chunk, step);
                if (chained != null) {
                    return chained;
                }
            }

            final GpuPrefetchHandle handle = submitSplit(plan);
            if (handle == null) {
                return step;
            }
            Completable prefetch = Completable.create(emitter -> {
                handle.done().whenComplete((v, err) -> {
                    // ALWAYS complete — never error into the scheduler, never hang.
                    try {
                        emitter.onComplete();
                    } catch (Throwable ignored) {
                    }
                });
                // Disposal: on NORMAL completion (resolved) leave the stored grid for the
                // fill to consume. Only a genuine gen cancellation BEFORE the batch
                // resolved cancels the future (the batcher drops cancelled requests) and
                // evicts any entry — so nothing leaks and no worker is waited on.
                emitter.setCancellable(handle::cancelIfUnresolved);
            });

            // Resume on a C2ME worker (NOT the single climate drainer thread), then run
            // the real biomes step there. This is what frees the worker during the wait.
            return prefetch
                    .observeOn(ChunkSystemExecutors.backgroundScheduler)
                    .andThen(step);
        } catch (Throwable t) {
            // Defensive: the batch path must never break gen.
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[SuperChunk-GPU] [biome-batch] climate prefetch wrap threw — per-chunk path for this chunk.", t);
            }
            return step;
        }
    }

    /**
     * Submits {@code chunk}'s climate quart-grid request on the DEDICATED climate batcher and
     * arranges the deposit into {@link GpuClimateStore} — the split (non-chained) path, exposed
     * for the Distant Horizons batch-generator seam
     * ({@link dev.superchunk.compat.DhWorldGenBridge}), which prefetches a whole DH generation
     * GROUP at its BIOMES step and then blocks its own DH thread on the handles.
     *
     * <p>Returns {@code null} — nothing submitted — whenever climate batching can't apply
     * (off, no batcher, group not registered, non-sampling biome source, already prefetched,
     * submit rejected); the chunk then takes the funneled per-chunk climate path exactly as
     * before. The chain path is deliberately NOT used here: it would deposit the density grids
     * one step early under the assumption that the same chunk's noise step follows on the chunk
     * system, whereas DH runs its own noise group and prefetches it separately. Never throws.
     */
    public static GpuPrefetchHandle submitForChunk(ChunkAccess chunk, ChunkGenerator generator,
                                                   NoiseRouter router) {
        try {
            if (chunk == null || router == null) {
                return null;
            }
            try {
                if (generator != null && BiomeClimateCache.neverSamplesClimate(generator.getBiomeSource())) {
                    return null;
                }
            } catch (Throwable ignored) {
                // fall through — the beginChunk-side gate still protects the arm/funnel path
            }
            final BiomeClimateCache.ClimatePlan plan = BiomeClimateCache.planBatch(chunk, router);
            if (plan == null || GpuClimateStore.contains(plan.key())) {
                return null;
            }
            return submitSplit(plan);
        } catch (Throwable t) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[SuperChunk-GPU] [biome-batch] climate submit threw — per-chunk path for this chunk.", t);
            }
            return null;
        }
    }

    /**
     * The SPLIT climate submit: one request on the dedicated climate batcher, deposited into
     * {@link GpuClimateStore} when it lands. Returns {@code null} when the submit could not be
     * made. Never throws.
     */
    private static GpuPrefetchHandle submitSplit(BiomeClimateCache.ClimatePlan plan) {
        final CompletableFuture<double[]> future;
        try {
            future = plan.batcher().submitTo(plan.disp(),
                    plan.ox(), plan.oy(), plan.oz(),
                    4, 4, 4,
                    plan.dimX(), plan.dimY(), plan.dimZ());
        } catch (Throwable t) {
            return null;
        }
        if (future == null) {
            return null;
        }
        // Tracks whether the future resolved (so the entry was stored for the fill).
        // RxJava fires the emitter's cancellable on NORMAL completion too (andThen
        // disposes this source before subscribing the next) — so we must NOT evict
        // the freshly-stored grid then. Only a cancellation BEFORE resolution evicts
        // (GpuPrefetchHandle.cancelIfUnresolved).
        final java.util.concurrent.atomic.AtomicBoolean resolved =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        // Completes (always normally) once the deposit attempt has run, so a waiter — the
        // chunk system's step, or a blocked DH worldgen thread — is always released.
        final CompletableFuture<Void> done = new CompletableFuture<>();
        future.whenComplete((grids, err) -> {
            resolved.set(true);
            try {
                if (err == null && grids != null && grids.length == plan.expectLen()) {
                    GpuClimateStore.put(plan.key(),
                            new GpuClimateStore.Stored(plan.key().fused(), grids));
                    GpuFillStats.recordClimatePrefetch();
                }
                // On any failure we DON'T store: beginChunk counts the miss and
                // FUNNELS a single-chunk request through the same drainer thread.
            } catch (Throwable ignored) {
                // never propagate
            }
            try {
                done.complete(null);
            } catch (Throwable ignored) {
            }
        });
        return new GpuPrefetchHandle(done, future, resolved, () -> GpuClimateStore.remove(plan.key()));
    }

    /**
     * The COMBINED climate+density prefetch (see {@link #CHAIN_CLIMATE}). Returns the
     * wrapped step, or {@code null} whenever the chain can't apply to this chunk —
     * the caller then runs the split path. Never throws.
     */
    private static Completable tryChain(BiomeClimateCache.ClimatePlan plan, ChunkAccess chunk,
                                        Completable step) {
        GpuBatchStore.Key dkey = null;
        boolean pendingMarked = false;
        try {
            GpuChunkBatcher batcher = ChunkGridCache.liveBatcher();
            GpuFusedInterpolator denFused = ChunkGridCache.batcherFused();
            GpuBatchDispatcher denDisp = batcher == null ? null : batcher.primaryDispatcher();
            if (batcher == null || denFused == null || denDisp == null || plan.chainRoots() == null) {
                return null;
            }
            // Attach once per dispatcher (idempotent; sticky-fail). The identity is the
            // dimension's climate fused group — a chunk from another dimension resolves a
            // different group and simply doesn't chain (its dedicated path stays).
            Object climIdentity = plan.key().fused();
            if (!denDisp.climateChainReadyFor(climIdentity)) {
                if (!denDisp.attachClimateChain(climIdentity, plan.chainRoots(),
                        plan.oy(), plan.dimX(), plan.dimY(), plan.dimZ())) {
                    return null;
                }
            } else if (!denDisp.climateChainGeometryMatches(plan.oy(), plan.dimX(), plan.dimY(), plan.dimZ())) {
                return null;
            }
            if (denDisp.climateSliceLen() != plan.expectLen()) {
                return null;
            }
            // Canonical density extent for this chunk (pre-NoiseChunk). The chain derives
            // climate origins from the density origins, so the horizontal origins must agree.
            final net.minecraft.world.level.ChunkPos pos = chunk.getPos();
            final GpuBatchStore.Extent ext = ChunkGridCache.canonicalBatcherExtent(pos);
            if (ext == null || ext.ox != plan.ox() || ext.oz != plan.oz()) {
                return null;
            }
            // DIMENSION GUARD: the batcher (and therefore this canonical extent) belongs to
            // ONE dimension. Only chain a chunk whose vertical world shape matches it —
            // same min-Y block origin and same total block height (cell span == quart span).
            // Without this, the FIRST dimension to reach the seam could attach its climate
            // roots to another dimension's density dispatcher (e.g. nether-first) and lock
            // the real dimension out of the chain. A mismatched chunk rides the split path.
            if (ext.oy != plan.oy() || (ext.dimY - 1) * ext.sy != plan.dimY() * 4) {
                return null;
            }
            dkey = GpuBatchStore.key(pos, ext.ox, ext.oy, ext.oz, ext.sx, ext.sy, ext.sz,
                    ext.dimX, ext.dimY, ext.dimZ);
            if (GpuBatchStore.contains(dkey) || !GpuBatchStore.markPending(dkey)) {
                // Density already deposited / in flight for this chunk (re-upgrade) —
                // let the split path handle climate alone.
                return null;
            }
            pendingMarked = true;
            final GpuBatchStore.Key fdkey = dkey;

            final int denExpect = denFused.rootCount() * ext.gridLen;
            final GpuChunkBatcher.CombinedHandle handle = batcher.submitCombined(
                    ext.ox, ext.oy, ext.oz, ext.sx, ext.sy, ext.sz, ext.dimX, ext.dimY, ext.dimZ);
            if (handle == null) {
                GpuBatchStore.clearPending(dkey);
                return null;
            }
            // DENSITY deposit (the free rider): exactly the noise-seam deposit, one stage
            // early. No OnDeviceFieldPool pre-production here (that path needs the fused
            // interpolator on this thread and is a default-OFF measured dead end — its
            // consumer falls back to per-chunk field production when the holder is null).
            handle.density().whenComplete((grids, err) -> {
                try {
                    if (err == null && grids != null && grids.length == denExpect) {
                        GpuBatchStore.put(fdkey, new GpuBatchStore.Stored(denFused, ext, grids, null));
                        GpuFillStats.recordBatchPrefetch();
                    } else {
                        GpuFillStats.recordBatchPrefetchFailed();
                    }
                } catch (Throwable ignored) {
                    // never propagate
                } finally {
                    GpuBatchStore.clearPending(fdkey);
                }
            });

            // CLIMATE deposit + step gating: mirrors the split path verbatim (the biomes
            // step resumes when the climate slice is served; the density slice completed
            // in the same completer pass an instant earlier).
            final java.util.concurrent.atomic.AtomicBoolean resolved =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            Completable prefetch = Completable.create(emitter -> {
                handle.climate().whenComplete((grids, err) -> {
                    resolved.set(true);
                    try {
                        if (err == null && grids != null && grids.length == plan.expectLen()) {
                            GpuClimateStore.put(plan.key(),
                                    new GpuClimateStore.Stored(plan.key().fused(), grids));
                            GpuFillStats.recordClimatePrefetch();
                        }
                        // On failure we DON'T store: beginChunk counts the miss and
                        // funnels a single-chunk request through the climate drainer.
                    } catch (Throwable ignored) {
                    }
                    try {
                        emitter.onComplete();
                    } catch (Throwable ignored) {
                    }
                });
                // Cancellation BEFORE resolution: cancel BOTH futures (the drainer drops
                // requests whose density future is cancelled) and evict both stores.
                emitter.setCancellable(() -> {
                    if (!resolved.get()) {
                        try {
                            handle.density().cancel(false);
                        } catch (Throwable ignored) {
                        }
                        try {
                            handle.climate().cancel(false);
                        } catch (Throwable ignored) {
                        }
                        GpuClimateStore.remove(plan.key());
                        GpuBatchStore.remove(fdkey);
                        GpuBatchStore.clearPending(fdkey);
                    }
                });
            });
            return prefetch
                    .observeOn(ChunkSystemExecutors.backgroundScheduler)
                    .andThen(step);
        } catch (Throwable t) {
            if (pendingMarked) {
                GpuBatchStore.clearPending(dkey);
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[SuperChunk-GPU] [biome-chain] combined prefetch threw — split path for this chunk.", t);
            }
            return null;
        }
    }
}
