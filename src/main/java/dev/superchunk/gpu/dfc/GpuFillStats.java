package dev.superchunk.gpu.dfc;

import dev.superchunk.gpu.OpenCLBackend;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight, lock-free counters for the live density-fill integration test
 * (Stage 3b): how many batch density fills ran on the GPU vs the CPU during
 * concurrent worldgen, and how many density values each accounted for.
 *
 * <p>Updated from many c2me-worker threads via {@link java.util.concurrent.atomic.AtomicLong}.
 * A running summary line is logged at most every {@link #LOG_INTERVAL_NANOS} (and on
 * demand at gen end via {@link #logSummary}). When the GPU backend is disabled these
 * counters never move (the GPU code path is dormant), so there is zero overhead on the
 * pure-CPU production default.
 */
public final class GpuFillStats {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    // Hottest accumulators: increment-only / add-only, read only at log time via sum().
    // LongAdder de-contends the cross-core CAS / cache-line ping-pong at 12-24 workers.
    private static final LongAdder gpuBatches = new LongAdder();
    private static final LongAdder cpuBatches = new LongAdder();
    private static final LongAdder gpuValues = new LongAdder();
    private static final LongAdder cpuValues = new LongAdder();
    // Periodic-summary trigger: a PER-THREAD countdown (no shared write per fill) that
    // consults a rarely-written AtomicLong timestamp every LOG_CHECK_EVERY local fills.
    // The old scheme — one shared AtomicLong.incrementAndGet() per recordGpu/recordCpu —
    // was a second contended CAS line hit ~412k/s across 12-24 workers (measured
    // 17.29M/42s leg), violating the LongAdder rationale above for a purely cosmetic
    // "exact every-N-batches" log cadence.
    private static final int LOG_CHECK_EVERY = 4096;
    private static final long LOG_INTERVAL_NANOS = 10_000_000_000L; // >=10s between running summaries
    private static final ThreadLocal<int[]> LOG_COUNTDOWN = ThreadLocal.withInitial(() -> new int[1]);
    private static final AtomicLong lastLogNanos = new AtomicLong(System.nanoTime());
    /** Diagnostic: how many times a GPU delegate's fill() was invoked at all.
     * LongAdder, NOT AtomicLong: this is hit on EVERY delegate fill BEFORE tryServe and
     * before the fill-shape gate — i.e. by the dominant gate-rejected population (52.6M
     * fills in the 2026-07-02 diag JFRs) — so a single CAS line here is exactly the
     * OPT#11/12 contention class. Read only at log time via sum(). */
    private static final LongAdder gpuAttempts = new LongAdder();
    // FILL-SHAPE POLICY GATE (GpuDfcDelegate): delegate fills that MISSED the coalesced
    // whole-chunk-grid cache and were below the direct-dispatch size floor, so they were
    // routed straight to the CPU bytecode path with NO GPU dispatch. These fills are ALSO
    // counted in cpuBatches/cpuValues (the CPU did the work); this pair isolates how much
    // latency-bound per-fill dispatching the gate is preventing. Hot path (per-block /
    // per-cell fills of the router giants) -> LongAdder, not AtomicLong.
    private static final LongAdder gateRejectBatches = new LongAdder();
    private static final LongAdder gateRejectValues = new LongAdder();
    // Coalescing (Stage 5): whole-chunk grids computed (one dispatch each) and the
    // number of per-column fills served from the cache with NO dispatch.
    private static final AtomicLong coalescedGrids = new AtomicLong();
    private static final AtomicLong coalescedServes = new AtomicLong();
    private static final AtomicLong coalescedServeValues = new AtomicLong();
    // LIVE DF fusion: ONE multi-output dispatch produces M whole-chunk grids at once
    // (replacing M separate per-DF whole-grid dispatches). Count the fused dispatches
    // and the member grids they produced.
    private static final AtomicLong fusedDispatches = new AtomicLong();
    private static final AtomicLong fusedMemberGrids = new AtomicLong();
    // Async (non-blocking) GPU readback: of the fused dispatches that were issued
    // EAGERLY at beginChunk, how many had their readback ALREADY complete by the time
    // the first density use needed it (latency fully hidden, no park) vs how many the
    // worker still had to WAIT on. asyncAlreadyComplete/(asyncAlreadyComplete+asyncHadToWait)
    // is the fraction of GPU-park latency the overlap window actually reclaimed.
    private static final AtomicLong asyncAlreadyComplete = new AtomicLong();
    private static final AtomicLong asyncHadToWait = new AtomicLong();
    // Eager async ISSUE/DISCARD accounting (the blind spot that hid the cross-dimension
    // wasted-dispatch class): asyncIssued counts every eager dispatch actually enqueued at
    // beginChunk; asyncDiscarded counts pendings that were never consumed and had to be
    // waited-on-and-dropped (endChunk / re-entry). Consumed pendings are the
    // asyncAlreadyComplete+asyncHadToWait pair above; issued == consumed + discarded
    // (modulo in-flight). A high discarded/issued ratio means eager dispatches are being
    // thrown away wholesale — previously this printed NOTHING.
    private static final AtomicLong asyncIssued = new AtomicLong();
    private static final AtomicLong asyncDiscarded = new AtomicLong();
    private static volatile boolean asyncDiscardWarned = false;
    // LIVE cross-chunk batching: chunks whose fused grids were precomputed in a BATCHED
    // dispatch at the noise prefetch (batchPrefetched) and then served at fill time from
    // the chunk-keyed store with NO per-chunk dispatch (batchServed). batchServed counts
    // toward the fused-dispatch totals above; this pair isolates the cross-chunk win.
    private static final AtomicLong batchPrefetched = new AtomicLong();
    private static final AtomicLong batchServed = new AtomicLong();
    // Density-side batch MISS/FAILURE accounting (the climate path counts these —
    // recordClimateBatchMiss — but the density path had NO analog, so a 100%-miss
    // pipeline was indistinguishable from a healthy one):
    //   batchMisses          — full-chunk-shaped armed fills that found NO consumable
    //                          deposit (prefetch never ran / failed / raced) while the
    //                          batcher was live; each implies per-chunk dispatches the
    //                          batched design claims don't happen.
    //   batchPrefetchSkipped — wrapNoise bailouts with a live batcher (no cached
    //                          NoiseChunk, no extent, submit rejected/threw).
    //   batchPrefetchFailed  — submitted but the batched dispatch completed
    //                          exceptionally / with short grids, so nothing was stored.
    //   batchCrossDimSkipped — chunks whose lattice geometry does not belong to the
    //                          batcher's fused group (another dimension) — skipped
    //                          instead of wastefully batching the WRONG group's kernels.
    private static final AtomicLong batchMisses = new AtomicLong();
    private static final AtomicLong batchPrefetchSkipped = new AtomicLong();
    private static final AtomicLong batchPrefetchFailed = new AtomicLong();
    private static final AtomicLong batchCrossDimSkipped = new AtomicLong();
    private static volatile boolean firstCrossDimSkipLogged = false;
    // SUB-full-chunk NoiseChunk passes (structure-probe column samplers) left UNARMED and
    // routed to the CPU bytecode path because gpu.subLatticeGpu=false. Counts passes (one
    // beginChunk each), making the probe population visible for the A/B.
    private static final AtomicLong subLatticeCpuRouted = new AtomicLong();
    private static volatile boolean firstSubLatticeRouteLogged = false;
    // LIVE cross-chunk CLIMATE batching (-Dsuperchunk.biome.batchClimate): chunks whose
    // climate quart grids were precomputed in a BATCHED dispatch at the BIOMES-boundary
    // prefetch (climatePrefetched), then consumed at fillBiomesFromNoise time from the
    // climate store with NO per-chunk dispatch (climateBatchServed); climateBatchMisses
    // counts armed biome fills that found NO deposit (cancel/evict race, seam not
    // engaged) — those are FUNNELED as single-chunk requests through the climate
    // drainer thread (or worker-dispatched under -Dsuperchunk.gpu.workerDispatch=true).
    private static final AtomicLong climatePrefetched = new AtomicLong();
    private static final AtomicLong climateBatchServed = new AtomicLong();
    private static final AtomicLong climateBatchMisses = new AtomicLong();
    // Honest "who did the work" metric: WALL-TIME spent in each fill path. Value-count
    // (gpuValues/cpuValues) is misleading because the CPU side is mostly cheap lerp-cache
    // fills of corners the GPU already computed; wall-time attributes the real cost.
    private static final LongAdder gpuFillNanos = new LongAdder();
    private static final LongAdder cpuFillNanos = new LongAdder();
    // Biome-climate offload: how many Climate.Sampler.sample() quart samples were served
    // from the fused GPU grid (each offloads ~3 uncached multi-octave noise evals —
    // temperature+vegetation+depth — off the CPU) vs fell back to the vanilla CPU
    // compute() path; and how many fused biome grids (3 DFs over the whole quart lattice)
    // the GPU materialized.
    private static final AtomicLong biomeGpuSamples = new AtomicLong();
    private static final AtomicLong biomeCpuSamples = new AtomicLong();
    private static final AtomicLong biomeOffloadedEvals = new AtomicLong();
    // Wall-time spent in the whole biome-population stage (ChunkAccess.fillBiomesFromNoise:
    // the per-section quart loop incl. climate sampling + biome search). Timed in BOTH
    // offloadBiome ON and OFF (whenever the GPU backend is up), so the ON-vs-OFF delta of
    // this counter is the DIRECT measure of CPU climate work the GPU offload removed.
    private static final AtomicLong biomeStageNanos = new AtomicLong();
    private static final AtomicLong biomeStageChunks = new AtomicLong();
    // Optional real-DF verify (-Dsuperchunk.biome.verify=true): for every GPU-served climate
    // sample the mixin also computes the vanilla compute() value and compares the
    // biome-deciding FLOAT cast. checked = samples compared; floatMismatch = how many differed
    // as floats (could change the biome — expected 0); doubleMismatch = differed as doubles
    // (FMA epsilon, informational).
    private static final AtomicLong biomeVerifyChecked = new AtomicLong();
    private static final AtomicLong biomeVerifyFloatMismatch = new AtomicLong();
    private static final AtomicLong biomeVerifyDoubleMismatch = new AtomicLong();
    // fp32 climate chain (gpu.climateFp32): quantized-coordinate mismatches of the SERVED
    // float vs vanilla (the biome-deciding comparison — MUST stay 0; float/double drift is
    // expected under fp32), the max observed |scaled32 - scaled64| for auditing the
    // quantization-guard band, and how many served samples the guard refined on the CPU.
    private static final AtomicLong biomeVerifyQuantMismatch = new AtomicLong();
    private static final java.util.concurrent.atomic.DoubleAccumulator biomeVerifyMaxScaledErr =
            new java.util.concurrent.atomic.DoubleAccumulator(Math::max, 0.0);
    private static final java.util.concurrent.atomic.LongAdder climateGuardRefined =
            new java.util.concurrent.atomic.LongAdder();
    // Depth biome-FLIP measurement (-Dsuperchunk.biome.depthFlipVerify=true, with depth
    // GPU-served via -Dsuperchunk.biome.offloadDepth=true): per GPU-served depth quart sample,
    // the GPU-served Climate.TargetPoint vs the full-vanilla-compute() TargetPoint.
    // checked = samples compared; depthQuantMismatch = Climate.quantizeCoord(depth) longs
    // differed (tight per-param bound); targetMismatch = the whole quantized TargetPoint
    // differed (EXACT "selection input differs" bound); decided = samples whose ACTUAL-biome
    // equality was determined (TargetPoints equal, or resolved through the dimension's
    // MultiNoiseBiomeSource RTree); biomeFlips = ACTUAL selected-biome flips (among decided).
    private static final AtomicLong depthFlipChecked = new AtomicLong();
    private static final AtomicLong depthFlipDepthQuantMismatch = new AtomicLong();
    private static final AtomicLong depthFlipTargetMismatch = new AtomicLong();
    private static final AtomicLong depthFlipDecided = new AtomicLong();
    private static final AtomicLong depthFlipBiomeFlips = new AtomicLong();
    private static volatile boolean firstGpuLogged = false;
    private static volatile boolean firstBiomeLogged = false;
    private static volatile boolean firstAttemptLogged = false;

    private GpuFillStats() {
    }

    /** Diagnostic: records that a GPU delegate fill was attempted (before success/fail). */
    public static void recordGpuAttempt() {
        gpuAttempts.increment();
        if (!firstAttemptLogged) {
            firstAttemptLogged = true;
            LOGGER.info("[SuperChunk-GPU] FIRST GPU delegate fill ATTEMPTED on thread '{}' "
                    + "— the GPU density-fill seam is reachable during live gen.", Thread.currentThread().getName());
        }
    }

    /**
     * Records one delegate fill REJECTED by the fill-shape policy gate ({@code
     * GpuDfcDelegate}): coalesced-cache miss with {@code n} below the direct-dispatch
     * floor -> served by the CPU bytecode path with NO GPU dispatch. The fill is also
     * recorded via {@link #recordCpu} by the caller-side fallback accounting.
     */
    public static void recordGateReject(int n) {
        gateRejectBatches.increment();
        gateRejectValues.add(n);
    }

    /** Records one whole-chunk grid computed in a single coalesced dispatch (launch count only). */
    public static void recordCoalescedGrid() {
        coalescedGrids.incrementAndGet();
    }

    /** Records a per-column fill served from the whole-chunk grid cache (no dispatch). */
    public static void recordCoalescedServe(int n) {
        coalescedServes.incrementAndGet();
        coalescedServeValues.addAndGet(n);
    }

    /**
     * Records ONE fused multi-output dispatch that produced {@code memberCount}
     * whole-chunk grids in a single GPU launch — the LIVE DF-fusion win. Only the
     * launch + member-grid COUNTS are tracked (no per-grid value total). These member
     * grids are NOT also counted via {@link #recordCoalescedGrid} (fusion replaces
     * those per-DF dispatches).
     */
    public static void recordFusedDispatch(int memberCount) {
        fusedDispatches.incrementAndGet();
        fusedMemberGrids.addAndGet(memberCount);
    }

    /**
     * Records an eager async fused dispatch consumed at first density use:
     * {@code alreadyComplete} = the readback had already finished (no wait — latency
     * fully hidden), else the worker had to wait on the event. This is the honest
     * "how much GPU-park time did the overlap window actually reclaim" metric.
     */
    public static void recordAsyncServe(boolean alreadyComplete) {
        if (alreadyComplete) {
            asyncAlreadyComplete.incrementAndGet();
        } else {
            asyncHadToWait.incrementAndGet();
        }
    }

    /** Records one eager async fused dispatch ISSUED at beginChunk (consumed-or-discarded later). */
    public static void recordAsyncIssued() {
        asyncIssued.incrementAndGet();
    }

    /**
     * Records one eager async fused dispatch that was never consumed and had to be
     * waited-on-and-DISCARDED (endChunk of an abandoned chunk, or re-entry). Sustained
     * discards mean the eager work is being thrown away — see the async summary line.
     */
    public static void recordAsyncDiscarded() {
        asyncDiscarded.incrementAndGet();
    }

    /**
     * Records that a chunk's fused-group grids were precomputed in a cross-chunk BATCHED
     * dispatch at the noise prefetch (chunk count only).
     */
    public static void recordBatchPrefetch() {
        batchPrefetched.incrementAndGet();
    }

    /**
     * Records one full-chunk-shaped armed fill that found NO consumable batched deposit
     * while the batcher was live — the density twin of {@link #recordClimateBatchMiss}.
     * The chunk then pays the per-chunk dispatch path the batched design says it shouldn't.
     */
    public static void recordBatchMiss() {
        batchMisses.incrementAndGet();
    }

    /** Records one wrapNoise bailout with a live batcher (no NoiseChunk/extent, submit rejected/threw). */
    public static void recordBatchPrefetchSkipped() {
        batchPrefetchSkipped.incrementAndGet();
    }

    /**
     * Records one sub-full-chunk NoiseChunk pass (structure-probe column sampler) left
     * unarmed and routed to the CPU bytecode path ({@code gpu.subLatticeGpu=false} only).
     */
    public static void recordSubLatticeCpuRouted() {
        subLatticeCpuRouted.incrementAndGet();
        if (!firstSubLatticeRouteLogged) {
            firstSubLatticeRouteLogged = true;
            LOGGER.info("[SuperChunk-GPU] FIRST sub-full-chunk lattice routed to CPU (gpu.subLatticeGpu=false): "
                    + "structure-probe column samplers now take the bytecode path with no per-probe GPU dispatch/park.");
        }
    }

    /** Records one submitted prefetch whose batched dispatch failed (exception / short grids) — nothing stored. */
    public static void recordBatchPrefetchFailed() {
        batchPrefetchFailed.incrementAndGet();
    }

    /**
     * Records one chunk whose lattice geometry does not belong to the live batcher's fused
     * group (another dimension's chunk): the prefetch is SKIPPED instead of running the
     * WRONG group's kernels and counting the unusable deposit as a batch serve.
     */
    public static void recordBatchCrossDimSkip() {
        batchCrossDimSkipped.incrementAndGet();
        if (!firstCrossDimSkipLogged) {
            firstCrossDimSkipLogged = true;
            LOGGER.info("[SuperChunk-GPU] [batch] FIRST cross-dimension prefetch SKIPPED: a generating chunk's lattice "
                    + "geometry does not match the batcher's fused group (another dimension). It rides the per-chunk "
                    + "path; previously it would have paid a wasted wrong-group batched dispatch counted as a serve.");
        }
    }

    /** Records one chunk served at fill time from the cross-chunk batched grid store (no per-chunk dispatch). */
    public static void recordBatchServe() {
        batchServed.incrementAndGet();
    }

    /**
     * Records that a chunk's climate quart grids were precomputed in a cross-chunk
     * BATCHED dispatch at the BIOMES-boundary prefetch (chunk count only).
     */
    public static void recordClimatePrefetch() {
        climatePrefetched.incrementAndGet();
    }

    /** Records one chunk's biome fill served from the batched climate store (no per-chunk dispatch). */
    public static void recordClimateBatchServe() {
        climateBatchServed.incrementAndGet();
    }

    /**
     * Records one armed biome fill that found NO batched climate deposit — the chunk's
     * climate grid request is then FUNNELED through the climate drainer thread as a
     * single-chunk batch (per-chunk fallback; expected rare once the prefetch warms up).
     */
    public static void recordClimateBatchMiss() {
        climateBatchMisses.incrementAndGet();
    }

    /**
     * Flushes ONE chunk's biome-climate serve counters in a single batched update (instead
     * of an AtomicLong hit per quart sample): {@code gpuSamples} quart samples served from
     * the fused GPU grid, having offloaded {@code gpuEvals} uncached multi-octave noise
     * evals (the float-exact subset of temperature/vegetation/depth) off the CPU, and
     * {@code cpuSamples} quart samples that fell back to the vanilla CPU compute() path.
     * These are accumulated per-thread in the serve Ctx and flushed once per chunk (in
     * disarm), so the per-quart serve hot path stays atomic-free under 16-worker contention.
     */
    public static void recordBiomeSamples(long gpuSamples, long gpuEvals, long cpuSamples) {
        if (gpuSamples != 0L) {
            biomeGpuSamples.addAndGet(gpuSamples);
        }
        if (gpuEvals != 0L) {
            biomeOffloadedEvals.addAndGet(gpuEvals);
        }
        if (cpuSamples != 0L) {
            biomeCpuSamples.addAndGet(cpuSamples);
        }
        if (gpuSamples > 0L && !firstBiomeLogged) {
            firstBiomeLogged = true;
            LOGGER.info("[SuperChunk-GPU] [biome] FIRST climate quart sample served from the GPU on thread '{}' "
                    + "— biome-climate offload is running.", Thread.currentThread().getName());
        }
    }

    /**
     * Records the wall-time of one chunk's whole biome-population stage
     * ({@code ChunkAccess.fillBiomesFromNoise}). Accumulated in BOTH offloadBiome ON and
     * OFF so the ON-vs-OFF delta isolates the CPU climate work the GPU removed.
     */
    public static void recordBiomeStageTime(long nanos) {
        biomeStageNanos.addAndGet(nanos);
        biomeStageChunks.incrementAndGet();
    }

    /**
     * Records one real-DF verify comparison of a GPU-served climate sample vs vanilla
     * {@code compute()} ({@code -Dsuperchunk.biome.verify=true} only): {@code floatDiff} =
     * the biome-deciding float cast differed (could change the biome), {@code doubleDiff} =
     * the raw double differed (FMA epsilon).
     */
    public static void recordBiomeVerify(boolean floatDiff, boolean doubleDiff,
                                         boolean quantDiff, double scaledErr) {
        biomeVerifyChecked.incrementAndGet();
        if (floatDiff) biomeVerifyFloatMismatch.incrementAndGet();
        if (doubleDiff) biomeVerifyDoubleMismatch.incrementAndGet();
        if (quantDiff) biomeVerifyQuantMismatch.incrementAndGet();
        biomeVerifyMaxScaledErr.accumulate(scaledErr);
    }

    /** One served climate sample refined on the CPU by the fp32 quantization guard. */
    public static void recordClimateGuardRefine() {
        climateGuardRefined.increment();
    }

    /**
     * Flushes ONE chunk's depth biome-FLIP counters in a single batched update
     * ({@code -Dsuperchunk.biome.depthFlipVerify=true} only; accumulated per-thread in the
     * {@code BiomeClimateCache} serve Ctx, flushed once per chunk in disarm — the same
     * pattern as {@link #recordBiomeSamples}). See the field block above for semantics.
     */
    public static void recordDepthFlipSamples(long checked, long depthQuantMismatch, long targetMismatch,
                                              long decided, long biomeFlips) {
        if (checked != 0L) depthFlipChecked.addAndGet(checked);
        if (depthQuantMismatch != 0L) depthFlipDepthQuantMismatch.addAndGet(depthQuantMismatch);
        if (targetMismatch != 0L) depthFlipTargetMismatch.addAndGet(targetMismatch);
        if (decided != 0L) depthFlipDecided.addAndGet(decided);
        if (biomeFlips != 0L) depthFlipBiomeFlips.addAndGet(biomeFlips);
    }

    /** Records a batch of {@code n} density values filled on the GPU. */
    public static void recordGpu(int n) {
        gpuBatches.increment();
        gpuValues.add(n);
        if (!firstGpuLogged) {
            firstGpuLogged = true;
            LOGGER.info("[SuperChunk-GPU] FIRST live GPU density-fill executed on thread '{}' (n={}) "
                    + "— concurrent worldgen offload is running.", Thread.currentThread().getName(), n);
        }
        maybeLog();
    }

    /** Records a batch of {@code n} density values filled on the CPU (fallback or no-GPU DF). */
    public static void recordCpu(int n) {
        cpuBatches.increment();
        cpuValues.add(n);
        maybeLog();
    }

    /**
     * Records wall-time spent in a SUCCESSFUL GPU density fill (whole-grid dispatch +
     * readback, or a coalesced cache serve). This is the honest cost metric — see
     * {@link #gpuFillNanos}.
     */
    public static void recordGpuTime(long nanos) {
        gpuFillNanos.add(nanos);
    }

    /** Records wall-time spent in a CPU density fill ({@code evalMulti}: GPU fallback or no-GPU DF). */
    public static void recordCpuTime(long nanos) {
        cpuFillNanos.add(nanos);
    }

    private static void maybeLog() {
        int[] countdown = LOG_COUNTDOWN.get();
        if (++countdown[0] < LOG_CHECK_EVERY) {
            return;
        }
        countdown[0] = 0;
        long now = System.nanoTime();
        long last = lastLogNanos.get();
        if (now - last >= LOG_INTERVAL_NANOS && lastLogNanos.compareAndSet(last, now)) {
            logSummary("running");
        }
    }

    /** Logs a one-line GPU/CPU density-fill summary. Safe to call any time. */
    public static void logSummary(String when) {
        long gB = gpuBatches.sum();
        long cB = cpuBatches.sum();
        long gV = gpuValues.sum();
        long cV = cpuValues.sum();
        long totB = gB + cB;
        long totV = gV + cV;
        if (totB == 0L) {
            return;
        }
        double batchPct = 100.0 * gB / totB;
        double valuePct = totV == 0L ? 0.0 : 100.0 * gV / totV;
        LOGGER.info("[SuperChunk-GPU] density-fill stats ({}): GPU batches={} ({}%), CPU batches={}; "
                        + "GPU values={} ({}%), CPU values={} (total batches={}, gpuFillAttempts={})",
                when, gB, String.format("%.1f", batchPct), cB,
                gV, String.format("%.1f", valuePct), cV, totB, gpuAttempts.sum());
        long grB = gateRejectBatches.sum();
        if (grB > 0L) {
            LOGGER.info("[SuperChunk-GPU] fill-shape gate ({}): {} small/non-coalesced fills ({} values) sent straight "
                            + "to the CPU bytecode path with NO GPU dispatch (coalesced-cache miss below the n>={} "
                            + "direct-dispatch floor — each would have been a latency-bound blocking dispatch+readback). "
                            + "Escape hatch: -Dsuperchunk.gpu.liveServeAll=true.",
                    when, grB, gateRejectValues.sum(), GpuDfcDelegate.MIN_DIRECT_DISPATCH_N);
        }
        long cg = coalescedGrids.get();
        long fd = fusedDispatches.get();
        long fmg = fusedMemberGrids.get();
        if (cg > 0 || fd > 0) {
            long cs = coalescedServes.get();
            // Total whole-chunk grids materialized = per-DF coalesced dispatches + the
            // member grids produced by fused dispatches. With fusion the GPU launch count
            // is (per-DF grids + fused dispatches), strictly fewer than (per-DF grids +
            // fused member grids) — that delta is the dispatch/sync saving from fusion.
            long totalGrids = cg + fmg;
            long totalDispatches = cg + fd;
            LOGGER.info("[SuperChunk-GPU] coalescing ({}): whole-chunk grids materialized={} (per-DF dispatches={}, "
                            + "fused dispatches={} producing {} member grids), columns served from cache (no dispatch)={} ({} values); "
                            + "GPU launches={} (fusion saved {} launches vs per-DF); coalescing factor ~{}x",
                    when, totalGrids, cg, fd, fmg, cs, coalescedServeValues.get(), totalDispatches, (fmg - fd),
                    String.format("%.1f", totalDispatches == 0 ? 0.0 : (double) (cs + totalGrids) / totalDispatches));
        }
        long slr = subLatticeCpuRouted.get();
        if (slr > 0L) {
            LOGGER.info("[SuperChunk-GPU] sub-lattice routing ({}): {} sub-full-chunk passes (structure-probe column "
                    + "samplers) left unarmed and served by the CPU bytecode path (gpu.subLatticeGpu=false).", when, slr);
        }
        long aReady = asyncAlreadyComplete.get();
        long aWait = asyncHadToWait.get();
        long aIssued = asyncIssued.get();
        long aDiscarded = asyncDiscarded.get();
        if (aReady > 0 || aWait > 0 || aIssued > 0) {
            long aTot = aReady + aWait;
            double readyPct = aTot == 0L ? 0.0 : 100.0 * aReady / aTot;
            LOGGER.info("[SuperChunk-GPU] async readback ({}): eager fused dispatches issued={}, consumed={}, DISCARDED (never "
                            + "consumable/abandoned, waited on + dropped)={} — already-complete at first-serve={} "
                            + "({}%, latency fully hidden, no park), had-to-wait={} ({}%) — overlap window reclaimed {}% of GPU-park events.",
                    when, aIssued, aTot, aDiscarded, aReady, String.format("%.1f", readyPct), aWait,
                    String.format("%.1f", 100.0 - readyPct), String.format("%.1f", readyPct));
            // A sustained discard ratio means eager GPU work is being thrown away wholesale
            // (the failure mode that used to print NOTHING) — warn once, loudly.
            if (!asyncDiscardWarned && aIssued >= 1000L && aDiscarded * 10L > aIssued) {
                asyncDiscardWarned = true;
                LOGGER.warn("[SuperChunk-GPU] async readback: {}% of the {} eager fused dispatches were DISCARDED unconsumed "
                                + "— the eager/async path is mostly wasted GPU work (wrong fused group for these chunks, or "
                                + "chunks abandoned before first density use). Investigate before trusting async metrics.",
                        String.format("%.1f", 100.0 * aDiscarded / aIssued), aIssued);
            }
        }
        long bP = batchPrefetched.get();
        long bS = batchServed.get();
        // Print whenever the batch seam is SUPPOSED to be live, even at all-zeros — a dead
        // seam must show as "prefetched=0 served=0 misses=N", not as a vanished section
        // (absence-of-a-line is the failure signature that hid the getChunkToSend gap).
        if (bP > 0 || bS > 0 || ChunkGridCache.isBatchEnabled()) {
            LOGGER.info("[SuperChunk-GPU] [batch] cross-chunk consumer ({}): chunks prefetched into batched dispatches={}, "
                            + "chunks served from the batched grid store (no per-chunk dispatch)={}, MISSES (armed full-chunk "
                            + "fill, no deposit -> per-chunk dispatch)={}, prefetch skipped={}, prefetch failed={}, "
                            + "cross-dimension skipped={} (store: puts={}, consumed={}, residual={}, evicted={}).",
                    when, bP, bS, batchMisses.get(), batchPrefetchSkipped.get(), batchPrefetchFailed.get(),
                    batchCrossDimSkipped.get(),
                    GpuBatchStore.puts(), GpuBatchStore.takes(), GpuBatchStore.size(), GpuBatchStore.evictions());
            // The batcher's own view: pooled requests, dispatch count, achieved batch sizes.
            try {
                GpuChunkBatcher batcher = GpuChunkBatcher.shared();
                if (batcher != null) {
                    batcher.logStats(when);
                }
            } catch (Throwable ignored) {
            }
        }
        long cP = climatePrefetched.get();
        long cS = climateBatchServed.get();
        long cM = climateBatchMisses.get();
        if (cP > 0 || cS > 0 || cM > 0) {
            long cTot = cS + cM;
            double hitPct = cTot == 0L ? 0.0 : 100.0 * cS / cTot;
            LOGGER.info("[SuperChunk-GPU] [biome-batch] cross-chunk climate consumer ({}): chunks prefetched into batched "
                            + "dispatches={}, served from the climate store (no per-chunk dispatch)={} ({}% of armed fills), "
                            + "misses funneled per-chunk={} (store: puts={}, consumed={}, residual={}, evicted={}).",
                    when, cP, cS, String.format("%.1f", hitPct), cM,
                    GpuClimateStore.puts(), GpuClimateStore.takes(), GpuClimateStore.size(), GpuClimateStore.evictions());
            // The climate batcher's own view: pooled requests, dispatches, batch sizes.
            try {
                GpuChunkBatcher climateBatcher = BiomeClimateCache.climateBatcher();
                if (climateBatcher != null) {
                    climateBatcher.logStats(when);
                }
            } catch (Throwable ignored) {
            }
        }
        long bG = biomeGpuSamples.get();
        long bC = biomeCpuSamples.get();
        if (bG > 0 || bC > 0) {
            long bTot = bG + bC;
            double bPct = bTot == 0L ? 0.0 : 100.0 * bG / bTot;
            LOGGER.info("[SuperChunk-GPU] [biome] climate offload ({}): quart samples served on GPU={} ({}%), CPU={}; "
                            + "{} uncached climate-DF noise evals (the float-exact subset) offloaded off the CPU.",
                    when, bG, String.format("%.1f", bPct), bC, biomeOffloadedEvals.get());
        }
        long bsN = biomeStageNanos.get();
        long bsC = biomeStageChunks.get();
        if (bsC > 0) {
            LOGGER.info("[SuperChunk-GPU] [biome] biome-stage WALL-TIME ({}): {} ms over {} chunks ({} us/chunk) "
                            + "— compare offloadBiome ON vs OFF; the delta is the CPU climate work the GPU removed.",
                    when, bsN / 1_000_000L, bsC, bsC == 0 ? 0 : (bsN / 1000L / bsC));
        }
        long bvC = biomeVerifyChecked.get();
        if (bvC > 0) {
            // On the fp32 climate chain (gpu.climateFp32) the QUANTIZED comparison of the
            // SERVED float is the biome-deciding verdict (raw float/double drift is expected
            // there); on the fp64 chain the strict float comparison remains the verdict.
            boolean fp32Chain = BiomeClimateCache.climateFp32Live();
            long quant = biomeVerifyQuantMismatch.get();
            long fl = biomeVerifyFloatMismatch.get();
            boolean unchanged = fp32Chain ? quant == 0 : fl == 0;
            LOGGER.info("[SuperChunk-GPU] [biome] real-DF VERIFY ({}): checked={} GPU-served samples vs vanilla compute() "
                            + "— QUANTIZED (biome-deciding, post-guard) mismatches={}, raw FLOAT mismatches={}{}, "
                            + "raw double mismatches={}, max |scaled err|={} (guard band={}), guard-refined={} -> {}",
                    when, bvC, quant, fl, fp32Chain ? " (expected >0 under fp32)" : "",
                    biomeVerifyDoubleMismatch.get(),
                    String.format("%.5f", biomeVerifyMaxScaledErr.get()),
                    String.format("%.3f", (double) BiomeClimateCache.CLIMATE_FP32_EPS),
                    climateGuardRefined.sum(),
                    unchanged ? "BIOMES UNCHANGED" : "BIOME DIFFERENCE DETECTED");
        }
        long fC = depthFlipChecked.get();
        if (fC > 0) {
            long fQ = depthFlipDepthQuantMismatch.get();
            long fT = depthFlipTargetMismatch.get();
            long fD = depthFlipDecided.get();
            long fB = depthFlipBiomeFlips.get();
            // Undecided samples are exactly the TargetPoint-mismatch samples with no reachable
            // MultiNoiseBiomeSource; each is AT MOST one flip, so [fB, fB+undecided] brackets
            // the true flip count (tight: undecided==0 whenever the vanilla resolver is the
            // dimension's MultiNoiseBiomeSource, the normal case).
            long undecided = fC - fD;
            String flips = undecided == 0
                    ? String.format("ACTUAL biome flips=%d (EXACT — all samples decided)", fB)
                    : String.format("ACTUAL biome flips=%d..%d (%d TargetPoint-mismatch samples had no reachable "
                            + "MultiNoiseBiomeSource; their quantized-TargetPoint mismatch is the exact "
                            + "selection-input bound)", fB, fB + undecided, undecided);
            LOGGER.info("[SuperChunk-GPU] [biome] depth FLIP verify ({}): checked={} GPU-served depth quart samples vs "
                            + "full-vanilla TargetPoints — quantized-depth-long mismatches={} ({}%), quantized-TargetPoint "
                            + "mismatches={} ({}%), {}; flip RATE={}% ({} per million samples) -> {}",
                    when, fC,
                    fQ, String.format("%.4f", 100.0 * fQ / fC),
                    fT, String.format("%.4f", 100.0 * fT / fC),
                    flips,
                    String.format("%.6f", 100.0 * fB / fC), String.format("%.1f", 1.0e6 * fB / fC),
                    fB > 0 ? "BIOME DRIFT PRESENT (relaxed depth parity)"
                            : (undecided == 0 ? "NO BIOME FLIPS"
                                              : "NO CONFIRMED FLIPS (<=" + undecided + " possible among undecided)"));
        }
        long gNs = gpuFillNanos.sum();
        long cNs = cpuFillNanos.sum();
        if (gNs > 0L || cNs > 0L) {
            long totNs = gNs + cNs;
            double timePct = totNs == 0L ? 0.0 : 100.0 * gNs / totNs;
            LOGGER.info("[SuperChunk-GPU] density-fill WALL-TIME ({}): GPU={} ms ({}% of fill time), CPU={} ms "
                            + "— honest 'who did the work' metric (value-count above is misleading: CPU side is mostly cheap lerp-cache fills).",
                    when, gNs / 1_000_000L, String.format("%.1f", timePct), cNs / 1_000_000L);
        }
    }

}
