package dev.superchunk.gpu.dfc;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkAccessNoiseChunk;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.structs.ChunkSystemExecutors;
import dev.superchunk.gpu.OpenCLBackend;
import io.reactivex.rxjava3.core.Completable;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * SuperChunk GPU — ASYNC cross-chunk batch PREFETCH at the noise status boundary.
 *
 * <p>This is the seam that wires the {@link GpuChunkBatcher} into the LIVE worldgen
 * pipeline. {@link dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.statuses.VanillaWorldGenerationDelegate
 * VanillaWorldGenerationDelegate} calls {@link #wrapNoise} for the {@code noise}
 * status' generation step. BEFORE the synchronous noise fill runs we:
 * <ol>
 *   <li>peek the chunk's already-created {@link NoiseChunk} and derive its
 *       cell-corner lattice extent (same math as {@link ChunkGridCache#beginChunk});</li>
 *   <li>{@link GpuChunkBatcher#submit submit} that chunk's fused-group request to the
 *       batcher, getting back a {@link CompletableFuture};</li>
 *   <li>return a {@link Completable} that completes only when the batched dispatch
 *       finishes — so the C2ME worker that hit this status boundary is <b>RELEASED</b>
 *       (the status machine resumes it via callback) rather than blocking. Many
 *       concurrent chunks therefore pile up at the batcher, forming bigger batches;</li>
 *   <li>on completion, deposit the grids in {@link GpuBatchStore}. The noise fill then
 *       runs (on a worker) and {@link ChunkGridCache} serves every column from the
 *       stored grids with NO per-chunk dispatch.</li>
 * </ol>
 *
 * <p><b>Worker is truly freed.</b> The future's continuation runs on the batcher
 * thread; it is hopped onto a C2ME worker via
 * {@link ChunkSystemExecutors#backgroundScheduler} ({@code observeOn}) so the heavy
 * scheduler bookkeeping never runs on the single batcher thread, and so workers are
 * never parked waiting on a batch no free worker can fill (the documented deadlock).
 *
 * <p><b>Never hangs, never throws into the scheduler.</b> Any failure — backend not
 * ready, no cached sampler, bad extent, submit rejected, batched dispatch failed —
 * silently returns the unmodified step (or completes the prefetch without storing),
 * and the chunk uses the existing per-chunk {@link ChunkGridCache} path. The
 * batcher's {@code batchWindowMicros} timeout guarantees the future always completes
 * even if a batch never fills, so the wait is always bounded.
 *
 * <p>Flag-gated: callers only invoke this when {@link ChunkGridCache#isBatchEnabled()}
 * (config {@code batchChunks}) is on. Flag OFF = this class is never reached.
 */
public final class GpuBatchPrefetch {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    private GpuBatchPrefetch() {
    }

    /**
     * Wraps the {@code noise}-status generation step with an async batched-grid
     * prefetch. Returns {@code step} unchanged whenever batching can't apply for this
     * chunk (so the behavior is identical to the per-chunk path). Never throws.
     */
    public static Completable wrapNoise(ChunkAccess chunk, Completable step) {
        try {
            GpuChunkBatcher batcher = ChunkGridCache.liveBatcher();
            GpuFusedInterpolator fused = ChunkGridCache.batcherFused();
            if (batcher == null || fused == null || chunk == null) {
                return step;
            }

            // Peek the NoiseChunk created at the biomes stage (no create). Null when
            // biomes were loaded from disk — then there is nothing to prefetch.
            NoiseChunk nc;
            try {
                nc = ((IChunkAccessNoiseChunk) chunk).superchunk$getNoiseChunk();
            } catch (Throwable t) {
                GpuFillStats.recordBatchPrefetchSkipped();
                return step;
            }
            if (nc == null) {
                GpuFillStats.recordBatchPrefetchSkipped();
                return step;
            }

            GpuBatchStore.Extent ext = GpuBatchStore.Extent.derive(nc);
            if (ext == null) {
                GpuFillStats.recordBatchPrefetchSkipped();
                return step;
            }
            // CROSS-DIMENSION GUARD (2026-07-02): only submit chunks whose lattice geometry
            // belongs to the batcher's fused group. The batcher is compiled over ONE
            // dimension's group; submitting another dimension's chunk would run the WRONG
            // group's kernels over its lattice, deposit grids under member identities that
            // dimension's fill never queries, and count the waste as a batch-serve WIN.
            // Skipped chunks ride the per-chunk path (their own group's eager/blocking fill).
            if (!ChunkGridCache.geometryMatchesBatcher(ext.oy, ext.sx, ext.sy, ext.sz,
                    ext.dimX, ext.dimY, ext.dimZ)) {
                GpuFillStats.recordBatchCrossDimSkip();
                return step;
            }
            final ChunkPos pos = chunk.getPos();
            if (pos == null) {
                GpuFillStats.recordBatchPrefetchSkipped();
                return step;
            }
            // Dimension-qualified store key: ChunkPos + this chunk's per-dimension noise-lattice
            // geometry, so a concurrently-generating other dimension at the same (x,z) can never
            // alias this entry. Derived from the SAME extent the consume side derives, so the
            // fill finds exactly this deposit.
            final GpuBatchStore.Key key = GpuBatchStore.key(pos,
                    ext.ox, ext.oy, ext.oz, ext.sx, ext.sy, ext.sz, ext.dimX, ext.dimY, ext.dimZ);
            // Already prefetched for this chunk (a re-upgrade, or the BIOMES-seam
            // climate-chain combined submit already carried this chunk's density
            // request — its deposit lands with that batch) — don't double-submit.
            if (GpuBatchStore.contains(key) || GpuBatchStore.isPending(key)) {
                return step;
            }

            final int expectLen = fused.rootCount() * ext.gridLen;
            // COMPACT-IDS (Stage 5): capture this chunk's decide-kernel aux WORKER-side,
            // right here at the prefetch seam where the NoiseChunk (and its aquifer)
            // exists — the 315-cell FluidStatus snapshot + beard AABB + vein seed route.
            // null (any capture failure / flag off) = the chunk rides corner-only.
            final CompactIds.ChunkAux aux = CompactIds.PROBE ? CompactIds.captureAux(nc) : null;
            final GpuChunkBatcher.SubmitHandle handle;
            CompletableFuture<double[]> future;
            try {
                handle = batcher.submitCompact(
                        ext.ox, ext.oy, ext.oz,
                        ext.sx, ext.sy, ext.sz,
                        ext.dimX, ext.dimY, ext.dimZ, aux);
                future = handle == null ? null : handle.future();
            } catch (Throwable t) {
                GpuFillStats.recordBatchPrefetchSkipped();
                return step;
            }
            if (future == null) {
                GpuFillStats.recordBatchPrefetchSkipped();
                return step;
            }

            final GpuFusedInterpolator ffused = fused;
            // Tracks whether the future resolved (so the entry was stored for the fill).
            // RxJava fires the emitter's cancellable on NORMAL completion too (andThen
            // disposes this source before subscribing the next) — so we must NOT evict
            // the freshly-stored grid then. Only a cancellation BEFORE resolution evicts.
            final java.util.concurrent.atomic.AtomicBoolean resolved =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            Completable prefetch = Completable.create(emitter -> {
                future.whenComplete((grids, err) -> {
                    resolved.set(true);
                    try {
                        // The batcher's DEAD-GRID SENTINEL: the chunk takes the compact-consume
                        // fast path, so its corner grids were never copied out of pinned staging
                        // (nothing reads them). Its IDS still must be deposited, so this is a
                        // normal store, not a failure. If such an entry is ever asked for grids
                        // anyway, ChunkGridCache.consumeBatchedGrid rejects it on its existing
                        // length check and takes the per-chunk path.
                        final boolean deadGrids = GpuChunkBatcher.isDeadGrids(grids);
                        if (err == null && grids != null && (grids.length == expectLen || deadGrids)) {
                            // ON-DEVICE INTERP PREFETCH POOL: produce the two full fields NOW (on
                            // THIS — the batcher — thread, off the worker's fill critical path) into
                            // a REUSED pooled holder, so the fill serves them READY with no GPU
                            // round-trip and ZERO per-chunk field allocation. If the pool is
                            // exhausted, or production fails, or this isn't the full-chunk extent, we
                            // store a null holder and the consumer falls back to the per-chunk
                            // provideFullFieldFromHostCorners path (correctness unaffected).
                            OnDeviceFieldPool.Holder holder = null;
                            if (OnDeviceInterp.ENABLED && !deadGrids) {
                                int total = ffused.fullFieldTotalOrNeg(ext.dimX, ext.dimY, ext.dimZ,
                                        ext.sx, ext.sy, ext.sz);
                                if (total > 0) {
                                    holder = OnDeviceFieldPool.acquire(total, ffused);
                                    if (holder != null) {
                                        boolean ok;
                                        try {
                                            ok = ffused.produceFullFieldToArrays(grids,
                                                    ext.dimX, ext.dimY, ext.dimZ,
                                                    ext.sx, ext.sy, ext.sz, holder);
                                        } catch (Throwable t) {
                                            ok = false;
                                        }
                                        if (!ok) {
                                            OnDeviceFieldPool.release(holder);
                                            holder = null;
                                            // Silent-degrade visibility: production failed ->
                                            // the consumer pays the per-chunk field fallback.
                                            OnDeviceInterp.recordFieldProductionFailed();
                                        }
                                    }
                                }
                            }
                            // COMPACT-IDS: the chunk's 1-byte/block ids ride the store next to
                            // the grids (PROBE: the consumer ignores them — counters prove
                            // arrival). Written drainer-side before the future completed.
                            byte[] ids = CompactIds.PROBE && handle != null ? handle.ids() : null;
                            GpuBatchStore.put(key, new GpuBatchStore.Stored(ffused, ext, grids, holder, ids));
                            if (CompactIds.PROBE) {
                                CompactIds.recordDeposit(ids);
                            }
                            GpuFillStats.recordBatchPrefetch();
                        } else {
                            // On any failure/timeout/cancel/short-grids we DON'T store: the
                            // fill transparently falls back to the per-chunk dispatch path.
                            // Count it — a silently-dying pipeline must show as failures,
                            // not as a shrinking prefetched count.
                            GpuFillStats.recordBatchPrefetchFailed();
                        }
                    } catch (Throwable ignored) {
                        // never propagate
                    }
                    // ALWAYS complete — never error into the scheduler, never hang.
                    try {
                        emitter.onComplete();
                    } catch (Throwable ignored) {
                    }
                });
                // Disposal: on NORMAL completion (resolved) leave the stored grid for the
                // fill to consume. Only a genuine gen cancellation BEFORE the batch
                // resolved cancels the future (the batcher drops cancelled requests) and
                // evicts any entry — so nothing leaks and the worker isn't waited on.
                emitter.setCancellable(() -> {
                    if (!resolved.get()) {
                        try {
                            future.cancel(false);
                        } catch (Throwable ignored) {
                        }
                        GpuBatchStore.remove(key);
                    }
                });
            });

            // Resume on a C2ME worker (NOT the single batcher thread), then run the
            // real noise step there. This is what frees the worker during the wait.
            return prefetch
                    .observeOn(ChunkSystemExecutors.backgroundScheduler)
                    .andThen(step);
        } catch (Throwable t) {
            // Defensive: the batch path must never break gen.
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[SuperChunk-GPU] [batch] prefetch wrap threw — per-chunk path for this chunk.", t);
            }
            return step;
        }
    }
}
