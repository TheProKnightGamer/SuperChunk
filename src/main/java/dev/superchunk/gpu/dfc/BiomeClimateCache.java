package dev.superchunk.gpu.dfc;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.SubCompiledDensityFunction;
import dev.superchunk.gpu.OpenCLBackend;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.CheckerboardColumnBiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SuperChunk GPU — <b>biome-climate offload</b> (sibling of {@link ChunkGridCache}).
 *
 * <p><b>The problem.</b> During biome population ({@code NoiseBasedChunkGenerator
 * .createBiomes} &rarr; {@code ChunkAccess.fillBiomesFromNoise} &rarr; the per-section
 * 4&times;4&times;4 quart loop) each quart sample calls {@code Climate.Sampler.sample}
 * which evaluates SIX climate density functions single-point on the CPU. Three of them
 * — {@code continents}/{@code erosion}/{@code ridges} — are {@code flatCache}'d (one
 * column compute). The other three — {@code temperature}/{@code vegetation}/{@code depth}
 * — are <b>uncached</b> and recompute their full multi-octave noise on EVERY quart
 * sample (~3–5k noise evals/chunk). That is the CPU weight.
 *
 * <p><b>The fix.</b> The quart samples form a clean regular lattice (origin = chunk-min
 * block, stride 4 blocks in X/Z, contiguous over the section Y-range). At the router
 * build site ({@code MixinNoiseConfig}) the uncached climate DFs' GPU-compiled forms are
 * fused into ONE {@code df_batch_lattice_multi} program — plus (default, {@code
 * -Dsuperchunk.biome.batchClimate}) a byte-parity-gated CROSS-CHUNK
 * {@code df_batch_lattice_multichunk} sibling. Chunks' quart grids are normally
 * PREFETCHED in K-chunk batched dispatches at the BIOMES status boundary
 * ({@link GpuClimatePrefetch}) and consumed here at {@link #beginChunk} with no
 * per-chunk dispatch; a miss is FUNNELED as a single-chunk batch through the dedicated
 * climate drainer thread (workers never issue driver calls). Every {@code sample()} is
 * then served by quart-index lookup. The pre-batching per-chunk eager async dispatch
 * survives behind {@code -Dsuperchunk.gpu.workerDispatch=true} /
 * {@code -Dsuperchunk.biome.batchClimate=false}. The other DFs and biome selection are
 * UNCHANGED.
 *
 * <p><b>Bit-identical — enforced by a float-parity gate.</b> The GPU uses FMA contraction
 * Java does not, so it matches vanilla {@code compute()} only to ~1 ULP in {@code double};
 * for spline-bearing DFs (the overworld {@code depth} offset) the gap reaches ~1e-7 — large
 * enough that the {@code float} cast {@code Climate.Sampler.sample} applies before
 * {@code Climate.quantizeCoord} can DIFFER, which could flip a biome. So at registration
 * EACH candidate DF is verified against vanilla over a sample quart lattice on the
 * <b>biome-deciding float cast</b>, and only DFs that are float-bit-identical (e.g. the
 * spline-free temperature/vegetation) are offloaded; any DF that is not (e.g. depth) stays
 * on the CPU. Thus every value the GPU serves is float-for-float identical to vanilla, and
 * with the unchanged CPU DFs the selected biome is bit-for-bit identical. Any GPU failure
 * also falls back to vanilla {@code compute()}.
 *
 * <p><b>RELAXED PARITY (opt-in, default OFF).</b> {@code -Dsuperchunk.biome.offloadDepth=true}
 * FORCES depth through both parity gates whenever it GPU-compiled, accepting the known ~1e-7
 * spline float drift. Rationale: biome selection quantizes every climate value via
 * {@code Climate.quantizeCoord} (1e-4 resolution) BEFORE the RTree lookup, so a ~1e-7 error
 * changes the selected biome only at the rare points where {@code v*10000} straddles an
 * integer boundary AND that flips the nearest RTree entry — far rarer than the raw-float
 * mismatch rate the gate measures. The ACTUAL biome-flip rate is measured in-process by
 * {@code -Dsuperchunk.biome.depthFlipVerify=true} (see {@code MixinClimateSampler} /
 * {@link #recordDepthFlipSample}). Both flags default false: shipping behaviour is unchanged
 * (depth stays on the CPU, biomes bit-identical).
 *
 * <p><b>Per-thread, chunk-scoped.</b> {@code createBiomes} runs each chunk on a worker
 * thread; the cache is a {@link ThreadLocal}. {@link #beginChunk}/{@link #endChunk} scope
 * it to one chunk's biome pass; the context carries the {@link Climate.Sampler} identity so
 * a stale grid can never serve the wrong sampler.
 */
public final class BiomeClimateCache {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    /** Master enable for the biome-climate offload (wired from {@code GpuConfig.offloadBiome()}). */
    private static volatile boolean enabled = false;

    /**
     * RELAXED-PARITY depth offload (default FALSE — read once). When true, depth is GPU-served
     * whenever it GPU-compiled, even though its spline makes it NOT float-bit-identical
     * (~1e-7): both the per-DF and the fused-output float-parity gates are overridden for
     * depth ONLY (the real mismatch numbers are still measured and logged for the record).
     * Biomes MAY DRIFT at {@code Climate.quantizeCoord} boundaries; measure the actual flip
     * rate with {@link #DEPTH_FLIP_VERIFY}. When false, behaviour is byte-identical to the
     * strict gate (depth stays on the CPU).
     */
    private static final boolean OFFLOAD_DEPTH_RELAXED =
            Boolean.parseBoolean(System.getProperty("superchunk.biome.offloadDepth", "false"));

    /**
     * Biome-FLIP measurement (default FALSE — read once). When true, every GPU-served depth
     * quart sample also builds the full VANILLA {@link Climate.TargetPoint} (all six params
     * from vanilla {@code compute()}) next to the GPU-served one and counts, in-process:
     * quantized-depth-long mismatches, quantized-TargetPoint mismatches (EXACT
     * "would-the-selection-input-differ" bound), and ACTUAL biome flips resolved through the
     * dimension's {@link MultiNoiseBiomeSource} RTree when reachable. Counters accumulate
     * per-thread and flush once per chunk (same pattern as the OPT#11 serve counters).
     * Meaningful only with {@link #OFFLOAD_DEPTH_RELAXED} (otherwise depth is CPU-served and
     * there is nothing to compare). Read by {@code MixinClimateSampler}.
     */
    public static final boolean DEPTH_FLIP_VERIFY =
            Boolean.parseBoolean(System.getProperty("superchunk.biome.depthFlipVerify", "false"));

    /**
     * TRUE while the live climate chain serves fp32-computed values ({@code gpu.climateFp32},
     * set by {@code GpuBatchDispatcher.attachClimateChain}). {@code MixinClimateSampler}'s
     * serve seam then applies the QUANTIZATION GUARD: a served sample whose
     * {@code (float)value * 10000.0f} lands within {@link #CLIMATE_FP32_EPS} of an integer
     * ({@code Climate.quantizeCoord}'s truncation boundaries), or is non-finite, is
     * recomputed on the CPU (vanilla). Samples outside the band provably quantize to the
     * same long as the fp64 value would (fp32 drift ≪ the band), so TargetPoints — and
     * therefore biomes — stay bit-identical. The dedicated/funneled dispatcher and the
     * per-chunk fused program remain fp64 (their byte-identity gates unchanged).
     */
    private static volatile boolean CLIMATE_FP32_LIVE = false;

    /**
     * Quantization-guard band in scaled units ({@code value*10000}), i.e. fraction of one
     * quantization step. Default 0.05 = flag ~10% of samples for CPU recompute while
     * tolerating fp32 drift up to 5e-6 in value space — ~50x the drift the verify harness
     * measures for the (spline-free) temperature/vegetation noises. Tighten only with
     * measured max-scaled-error evidence ({@code -Dsuperchunk.biome.verify} reports it).
     */
    public static final float CLIMATE_FP32_EPS =
            Float.parseFloat(System.getProperty("superchunk.biome.climateFp32Eps", "0.05"));

    /** Called by the batch dispatcher when the climate chain attaches (or falls back). */
    public static void noteClimateFp32Live(boolean live) {
        CLIMATE_FP32_LIVE = live;
    }

    /** True when the serve seam must apply the fp32 quantization guard. */
    public static boolean climateFp32Live() {
        return CLIMATE_FP32_LIVE;
    }

    /**
     * CROSS-CHUNK CLIMATE BATCHING (default TRUE — read once). Restores the intended
     * architecture: climate quart-lattice fills ride the {@link GpuChunkBatcher} pattern
     * instead of a per-chunk {@code dispatchAsync} on every WORKER thread. Concretely:
     * <ol>
     *   <li>at the BIOMES status boundary ({@link GpuClimatePrefetch#wrapBiomes} — the
     *       biome stage runs at the {@code biomes} status, BEFORE {@code noise}, so the
     *       noise-boundary prefetch would be too late) each chunk's climate request is
     *       submitted to a DEDICATED climate batcher and the C2ME worker is RELEASED
     *       until the batched dispatch deposits the grids in {@link GpuClimateStore};</li>
     *   <li>{@link #beginChunk} CONSUMES the deposited grids (no per-chunk dispatch);</li>
     *   <li>a chunk with no deposit (miss/cancel/evict) is FUNNELED as a single-chunk
     *       request through the same drainer thread — workers NEVER call
     *       {@code clEnqueue*}/{@code clUnmap} (13 threads contending the NVIDIA context
     *       lock was the measured sink: ~70% of worker wall blocked in driver calls).</li>
     * </ol>
     * {@code -Dsuperchunk.biome.batchClimate=false} reverts to the per-chunk worker
     * dispatch exactly as before. Parity: the batched program must be BYTE-IDENTICAL to
     * the fused per-chunk kernel over the gate lattices ({@link #batchedByteIdentical})
     * or batching disables itself — nothing bypasses the float-parity gates above.
     */
    private static final boolean BATCH_CLIMATE =
            Boolean.parseBoolean(System.getProperty("superchunk.biome.batchClimate", "true"));

    /**
     * ESCAPE HATCH (default FALSE — read once): when true, per-chunk climate dispatches
     * stay on the WORKER thread ({@code dispatchAsync} at {@link #beginChunk}) exactly as
     * before the funneling, even with {@link #BATCH_CLIMATE} on. Diagnostic only.
     */
    private static final boolean WORKER_DISPATCH =
            Boolean.parseBoolean(System.getProperty("superchunk.gpu.workerDispatch", "false"));

    /**
     * Upper bound on a worker's wait for a FUNNELED single-chunk climate batch (the
     * batcher guarantees completion — window-timeout flush + exceptional completion on
     * every failure path — so this is pure defense in depth). On expiry the chunk falls
     * back to the vanilla CPU climate path (parity-safe).
     */
    private static final long BATCH_WAIT_MS = Long.getLong("superchunk.biome.batchWaitMillis", 10_000L);

    /** Hard cap on the per-chunk quart grid (defensive; a default overworld chunk is ~1536). */
    private static final int MAX_GRID_LEN = 1 << 20;

    /**
     * Maps the TEMPERATURE GPU density function (stable identity, shared by the router and
     * every per-chunk sampler that wraps it) to its fused biome group. One entry per
     * dimension's RandomState. Concurrent: written at router-compile time, read on gen.
     */
    private static final Map<GpuDensityFunction, Group> REGISTRY =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** All fused biome interpolators ever built, for shutdown release. */
    private static final java.util.Set<GpuFusedInterpolator> PROGRAMS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The DEDICATED climate batcher (single drainer thread, aux program slots only) —
     * built lazily under {@link #BUILD_LOCK} when the first dimension's batched climate
     * dispatcher passes the byte-parity gate; {@code null} when {@link #BATCH_CLIMATE}
     * is off or no dimension batches. Independent of the density batcher
     * ({@code GpuChunkBatcher.shared()}): climate groups register at RandomState build,
     * BEFORE any density fused group is discovered, and must survive that batcher's
     * {@code batchChunks}-gated rebuilds.
     */
    private static volatile GpuChunkBatcher CLIMATE_BATCHER;

    /** Every per-dimension batched climate dispatcher, for shutdown release (we own them, not the batcher). */
    private static final java.util.Set<GpuBatchDispatcher> CLIMATE_DISPATCHERS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Rate-limited counter for funneled-batch wait timeouts (defensive path; expected 0). */
    private static final java.util.concurrent.atomic.AtomicLong BATCH_WAIT_TIMEOUTS =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * The registering dimension's registry id (e.g. {@code minecraft:overworld}), pushed
     * around {@code RandomState.create} by {@code MixinChunkMapClimateDimLabel} on the
     * SAME thread that runs {@link #tryRegister} (the RandomState build happens inside the
     * {@code ChunkMap} constructor). {@code "unknown-dimension"} when a RandomState is
     * built outside a ChunkMap (tools, tests).
     */
    private static final ThreadLocal<String> DIM_LABEL = new ThreadLocal<>();

    private static final Object BUILD_LOCK = new Object();

    private BiomeClimateCache() {
    }

    /** Sets this thread's dimension label for registration-time logging. See {@link #DIM_LABEL}. */
    public static void pushDimensionLabel(String label) {
        if (label != null) {
            DIM_LABEL.set(label);
        }
    }

    /** Clears this thread's dimension label. */
    public static void popDimensionLabel() {
        DIM_LABEL.remove();
    }

    private static String dimLabel() {
        String s = DIM_LABEL.get();
        return s != null ? s : "unknown-dimension";
    }

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    /** Master enable state — lets the serve mixin skip its ThreadLocal fetch when the offload is OFF. */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * A dimension's fused biome group: the fused program over the SUBSET of
     * temperature/vegetation/depth that GPU-compiled AND passed the float-parity gate,
     * plus each one's member identity and its root index in the fused output ({@code -1} =
     * that DF is not GPU-served and stays on the CPU). temperature is the anchor (the
     * registry key). {@code veg}/{@code depth} are {@code null} when excluded (e.g. the
     * overworld {@code depth} contains a spline that is not float-bit-identical).
     */
    private record Group(GpuFusedInterpolator fused,
                         GpuDensityFunction temp, GpuDensityFunction veg, GpuDensityFunction depth,
                         int rootTemp, int rootVeg, int rootDepth, int backedCount,
                         GpuBatchDispatcher batchDisp, List<AstNode> batchRoots) {
        // batchDisp: the group's CROSS-CHUNK batched program slot (the same roots compiled
        // to df_batch_lattice_multichunk, byte-parity-gated against the fused kernel), or
        // null when BATCH_CLIMATE is off / the batched program failed to build or gate
        // (this dimension then keeps the per-chunk path).
        // batchRoots: the EXACT roots list batchDisp was compiled + byte-parity-gated
        // from. The climate CHAIN re-emits this same list (same order) so its program is
        // a CLProgramCache hit on the SAME binary — the gate proof transfers verbatim,
        // and slice indices keep the fused-members order the serve path expects.
    }

    // ------------------------------------------------------------------
    // Build-site registration (MixinNoiseConfig).
    // ------------------------------------------------------------------

    /**
     * Captures this dimension's UNCACHED climate DFs (temperature/vegetation/depth), keeps
     * only the ones whose GPU form is <b>float-bit-identical</b> to vanilla over a sample
     * quart lattice, fuses that subset into ONE multi-output program, and registers it
     * keyed by the temperature GPU function. Called once per {@code RandomState} build.
     * Skips cleanly (CPU climate path) if the offload is off, the GPU is unavailable, or
     * temperature (the anchor) is not float-exact-compilable. Never throws.
     */
    public static void tryRegister(NoiseRouter router) {
        if (!enabled || router == null || !OpenCLBackend.isAvailable()) {
            return;
        }
        GpuDensityFunction gt = gpuOf(router.temperature());
        GpuDensityFunction gv = gpuOf(router.vegetation());
        GpuDensityFunction gd = gpuOf(router.depth());
        synchronized (BUILD_LOCK) {
            if (gt != null && REGISTRY.containsKey(gt)) {
                return;   // already built for this dimension
            }
            // Float-parity gate per DF: only offload DFs whose GPU output is float-for-float
            // identical to vanilla compute() (so the biome-deciding cast never differs).
            boolean okT = gt != null && gt.sourceAst() != null && floatExact("temperature", router.temperature(), gt);
            if (!okT) {
                LOGGER.info("[SuperChunk-GPU] [biome] climate offload skipped for {} — temperature did not GPU-compile or is "
                        + "not float-bit-identical to vanilla. CPU climate path stays.", dimLabel());
                return;
            }
            boolean okV = gv != null && gv.sourceAst() != null && floatExact("vegetation", router.vegetation(), gv);
            // depth: run the REAL per-DF float-parity check unconditionally (its mismatch numbers
            // are logged for the record), but under RELAXED PARITY the verdict is overridden —
            // depth passes this gate whenever it GPU-compiled at all. Flag off (default):
            // okD == the strict float-exact verdict, byte-identical to before.
            boolean depthCompiled = gd != null && gd.sourceAst() != null;
            boolean depthFloatExact = depthCompiled && floatExact("depth", router.depth(), gd);
            boolean okD = OFFLOAD_DEPTH_RELAXED ? depthCompiled : depthFloatExact;
            if (OFFLOAD_DEPTH_RELAXED && depthCompiled && !depthFloatExact) {
                LOGGER.warn("[SuperChunk-GPU] [biome] RELAXED PARITY (-Dsuperchunk.biome.offloadDepth=true): depth is "
                        + "FORCED past the per-DF float-parity gate despite the mismatches logged above — biomes MAY "
                        + "DRIFT at Climate.quantizeCoord boundaries (measure with -Dsuperchunk.biome.depthFlipVerify=true).");
            }

            List<AstNode> roots = new ArrayList<>(3);
            List<GpuDensityFunction> members = new ArrayList<>(3);
            // Parallel to members/roots (same order, same index): the vanilla DF + label for
            // each fused root, so the FUSED-output gate below can compare each root's ACTUAL
            // fused bytes against vanilla at the very root index fillAllGrids writes.
            List<DensityFunction> vanillas = new ArrayList<>(3);
            List<String> names = new ArrayList<>(3);
            int rt = addRoot(roots, members, gt);
            vanillas.add(router.temperature());
            names.add("temperature");
            int rv = -1, rd = -1;
            if (okV) {
                rv = addRoot(roots, members, gv);
                vanillas.add(router.vegetation());
                names.add("vegetation");
            }
            if (okD) {
                rd = addRoot(roots, members, gd);
                vanillas.add(router.depth());
                names.add("depth");
            }

            GpuFusedInterpolator fused;
            try {
                fused = GpuFusedInterpolator.compile(roots, members);
            } catch (Throwable th) {
                LOGGER.warn("[SuperChunk-GPU] [biome] building fused climate interpolator threw for {} — CPU climate path.",
                        dimLabel(), th);
                return;
            }
            if (fused == null) {
                LOGGER.info("[SuperChunk-GPU] [biome] fused climate program did not build for {} — CPU climate path.",
                        dimLabel());
                return;
            }

            // AUTHORITATIVE acceptance gate: production serves the FUSED kernel (shared
            // NoiseRegistry / cross-DF CSE), NOT the per-DF kernels the gate above validated.
            // fused==per-DF is only checked offline on synthetic DFs, so a ~1-ULP FMA
            // divergence at a float-cast boundary could surface ONLY in the fused path and flip
            // a biome unvalidated. So re-run the float-parity check on the FUSED program's ACTUAL
            // output (the bytes production will serve) and DROP any root that diverges from
            // vanilla at the biome-deciding float cast back to the CPU. Parity-SAFER: a diverging
            // DF falls back to CPU = vanilla biomes. rootOk[k] is for root index k (== the kth
            // member); temperature is the mandatory anchor at root 0 (rt).
            boolean[] rootOk = floatExactFused(fused, vanillas.toArray(new DensityFunction[0]),
                    names.toArray(new String[0]));
            if (!rootOk[rt]) {
                LOGGER.info("[SuperChunk-GPU] [biome] fused-output gate for {}: temperature (anchor) is NOT float-bit-identical "
                        + "to vanilla in the FUSED kernel — CPU climate path stays (no biome offload).", dimLabel());
                try {
                    fused.close();
                } catch (Throwable ignored) {
                }
                return;
            }
            boolean serveV = okV && rootOk[rv];
            // depth: RELAXED PARITY also forces it past the fused-output gate (rootOk[rd] may be
            // false; floatExactFused logged the real fused mismatch numbers above for the record).
            // Flag off (default): serveD == okD && rootOk[rd], byte-identical to before. NB the
            // okD short-circuit keeps rd==-1 from ever indexing rootOk.
            boolean serveD = okD && (rootOk[rd] || OFFLOAD_DEPTH_RELAXED);
            if (OFFLOAD_DEPTH_RELAXED && okD && !rootOk[rd]) {
                LOGGER.warn("[SuperChunk-GPU] [biome] RELAXED PARITY (-Dsuperchunk.biome.offloadDepth=true): depth is "
                        + "FORCED past the FUSED-output float-parity gate despite the mismatches logged above — depth "
                        + "will be GPU-served with ~1e-7 spline drift.");
            }
            // True iff depth is served WITHOUT full float parity — only possible under the relaxed
            // flag (with it off, serveD already implies depthFloatExact && rootOk[rd]).
            boolean depthRelaxed = serveD && !(depthFloatExact && rootOk[rd]);
            int servedCount = 1 + (serveV ? 1 : 0) + (serveD ? 1 : 0);

            PROGRAMS.add(fused);
            // CROSS-CHUNK CLIMATE BATCHING (-Dsuperchunk.biome.batchClimate, default true):
            // compile the SAME roots into this group's SECOND program slot — a
            // df_batch_lattice_multichunk batched dispatcher — and accept it only if its
            // output is BYTE-IDENTICAL to the fused per-chunk kernel over the gate lattices
            // (so serving batched grids is EXACTLY equivalent to serving fused grids, and
            // the float-parity gates above transfer verbatim). Any failure leaves batchDisp
            // null: this dimension keeps the per-chunk path, offload still works.
            GpuBatchDispatcher batchDisp = null;
            if (BATCH_CLIMATE) {
                batchDisp = buildBatchedClimateDispatcher(roots, fused);
                if (batchDisp != null) {
                    CLIMATE_DISPATCHERS.add(batchDisp);
                    ensureClimateBatcher();
                    if (CLIMATE_BATCHER == null) {
                        // No drainer to dispatch on — the slot is useless; drop it.
                        CLIMATE_DISPATCHERS.remove(batchDisp);
                        try {
                            batchDisp.close();
                        } catch (Throwable ignored) {
                        }
                        batchDisp = null;
                    }
                }
            }
            // A root dropped by the fused-output gate keeps its slot in the fused program (it is
            // still computed, just unused) but is registered with root index -1 + member null,
            // so serve() returns NaN for it and the CPU computes it exactly as vanilla.
            REGISTRY.put(gt, new Group(fused, gt, serveV ? gv : null, serveD ? gd : null,
                    rt, serveV ? rv : -1, serveD ? rd : -1, servedCount, batchDisp,
                    batchDisp != null ? List.copyOf(roots) : null));
            StringBuilder on = new StringBuilder("temperature");
            if (serveV) on.append("+vegetation");
            if (serveD) on.append("+depth");
            String off = (serveV ? "" : "vegetation ") + (serveD ? "" : "depth ");
            String parityTail = depthRelaxed
                    ? "GPU-served values are float-bit-identical to vanilla EXCEPT depth (forced by "
                      + "-Dsuperchunk.biome.offloadDepth) -> biomes MAY DRIFT (depth GPU-served, relaxed parity)."
                    : "Every GPU-served value is float-bit-identical to vanilla (FUSED-output verified) -> biomes UNCHANGED.";
            String batchTail = !BATCH_CLIMATE
                    ? "Cross-chunk batching OFF (-Dsuperchunk.biome.batchClimate=false) -> per-chunk worker dispatch."
                    : (batchDisp != null
                        ? "Cross-chunk batching ON (byte-identical batched program) -> quart grids prefetched at the "
                          + "BIOMES boundary; ALL climate driver calls on the climate drainer thread."
                        : "Cross-chunk batching UNAVAILABLE (batched program failed to build/gate) -> per-chunk dispatch.");
            LOGGER.info("[SuperChunk-GPU] [biome] climate offload ENABLED for {}: GPU-served=[{}] ({} of 3 uncached climate DFs) "
                            + "-> ONE fused dispatch/chunk over the quart lattice; CPU (not float-exact / not compiled)=[{}]. {} {}",
                    dimLabel(), on, servedCount, off.isBlank() ? "none" : off.trim(), parityTail, batchTail);
            if (DEPTH_FLIP_VERIFY && !serveD) {
                LOGGER.warn("[SuperChunk-GPU] [biome] -Dsuperchunk.biome.depthFlipVerify=true but depth is NOT GPU-served "
                        + "for this dimension — the flip measurement will check 0 samples. Add "
                        + "-Dsuperchunk.biome.offloadDepth=true to force depth onto the GPU and measure its biome drift.");
            }
        }
    }

    private static int addRoot(List<AstNode> roots, List<GpuDensityFunction> members, GpuDensityFunction g) {
        roots.add(g.sourceAst());
        members.add(g);
        return members.size() - 1;
    }

    private static GpuDensityFunction gpuOf(DensityFunction df) {
        if (df instanceof SubCompiledDensityFunction scdf) {
            GpuDfcDelegate del = scdf.c2me$getGpuDelegate();
            if (del != null) {
                return del.gpu();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Cross-chunk climate batching: build + gate the batched program slot.
    // ------------------------------------------------------------------

    /**
     * Compiles this group's climate roots into a {@code df_batch_lattice_multichunk}
     * batched dispatcher (its cross-chunk program slot) and accepts it ONLY if
     * {@link #batchedByteIdentical} proves the batched output byte-identical to the
     * fused per-chunk kernel over the gate lattices. Returns {@code null} on any
     * failure (logged; never throws) — the dimension then keeps the per-chunk path.
     */
    private static GpuBatchDispatcher buildBatchedClimateDispatcher(List<AstNode> roots, GpuFusedInterpolator fused) {
        GpuBatchDispatcher d = null;
        try {
            // fp32 climate compute (gpu.climateFp32, fp64 builds only): fp32 roots with
            // double outputs, accepted at the QUANTIZED level (byte-identity cannot pass
            // by design; biome safety = quantized gate + the serve-side guard). Any
            // failure falls back to the proven fp64 dispatcher below.
            if (OpenCLBackend.config().climateFp32() && !OpenCLBackend.isFp32()) {
                GpuBatchDispatcher d32 = null;
                try {
                    d32 = GpuBatchDispatcher.compileClimateF32D(roots);
                    if (d32 != null && d32.rootCount() == fused.rootCount()
                            && batchedQuantIdentical(d32, fused)) {
                        noteClimateFp32Live(true);
                        LOGGER.info("[SuperChunk-GPU] [biome-batch] fp32 CLIMATE dispatcher ACCEPTED for {} "
                                + "(quantized gate + serve-side guard band {}).", dimLabel(),
                                String.format("%.3f", (double) CLIMATE_FP32_EPS));
                        return d32;
                    }
                } catch (Throwable t32) {
                    LOGGER.warn("[SuperChunk-GPU] [biome-batch] fp32 climate dispatcher build threw for {} — "
                            + "falling back to fp64.", dimLabel(), t32);
                }
                if (d32 != null) {
                    try {
                        d32.close();
                    } catch (Throwable ignored) {
                    }
                }
                LOGGER.info("[SuperChunk-GPU] [biome-batch] fp32 climate dispatcher not accepted for {} — "
                        + "using the fp64 dispatcher.", dimLabel());
            }
            d = GpuBatchDispatcher.compile(roots);
            if (d == null) {
                LOGGER.info("[SuperChunk-GPU] [biome-batch] batched climate program did not build for {} — "
                        + "per-chunk climate dispatch for this dimension.", dimLabel());
                return null;
            }
            if (d.rootCount() != fused.rootCount() || !batchedByteIdentical(d, fused)) {
                try {
                    d.close();
                } catch (Throwable ignored) {
                }
                return null;
            }
            return d;
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [biome-batch] building batched climate dispatcher threw for {} — "
                    + "per-chunk climate dispatch for this dimension.", dimLabel(), t);
            if (d != null) {
                try {
                    d.close();
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }

    /**
     * BATCHED-output acceptance gate: one K-chunk {@code batchFill} over ALL the
     * {@link #GATE_ORIGINS} quart lattices (exercising the real multichunk origin
     * indexing) compared slice-by-slice against the fused per-chunk kernel's
     * {@code fillAllGrids} — accepted only when EVERY root's every cell is
     * {@link Double#compare byte-identical}. Byte-identity to the fused kernel means
     * the float-parity verdicts established above transfer verbatim to batched serves;
     * nothing bypasses those gates. Any mismatch/failure disables batching for this
     * dimension (parity-safer: it keeps the already-gated per-chunk path).
     */
    private static boolean batchedByteIdentical(GpuBatchDispatcher disp, GpuFusedInterpolator fused) {
        int m = fused.rootCount();
        int n = GATE_DIMX * GATE_DIMY * GATE_DIMZ;
        int K = GATE_ORIGINS.length;
        int[] origins = new int[K * 3];
        for (int c = 0; c < K; c++) {
            origins[c * 3] = GATE_ORIGINS[c][0];
            origins[c * 3 + 1] = GATE_ORIGINS[c][1];
            origins[c * 3 + 2] = GATE_ORIGINS[c][2];
        }
        try {
            double[] out = new double[K * m * n];
            if (!disp.batchFill(origins, K, 4, 4, 4, GATE_DIMX, GATE_DIMY, GATE_DIMZ, out)) {
                LOGGER.info("[SuperChunk-GPU] [biome] gate(batched) for {}: batchFill failed — "
                        + "cross-chunk climate batching disabled for this dimension (per-chunk path).", dimLabel());
                return false;
            }
            long checked = 0, mism = 0;
            for (int c = 0; c < K; c++) {
                double[][] ref = new double[m][];   // fillAllGrids allocates each n-length slice
                if (!fused.fillAllGrids(ref, GATE_ORIGINS[c][0], GATE_ORIGINS[c][1], GATE_ORIGINS[c][2],
                        4, 4, 4, GATE_DIMX, GATE_DIMY, GATE_DIMZ)) {
                    LOGGER.info("[SuperChunk-GPU] [biome] gate(batched) for {}: fused reference fill failed — "
                            + "cross-chunk climate batching disabled for this dimension (per-chunk path).", dimLabel());
                    return false;
                }
                for (int k = 0; k < m; k++) {
                    for (int i = 0; i < n; i++) {
                        checked++;
                        if (Double.compare(out[(c * m + k) * n + i], ref[k][i]) != 0) {
                            mism++;
                        }
                    }
                }
            }
            boolean ok = mism == 0;
            LOGGER.info("[SuperChunk-GPU] [biome] gate(batched) for {}: checked={} byte-mismatch={} -> {}",
                    dimLabel(), checked, mism,
                    ok ? "BYTE-IDENTICAL to the fused per-chunk kernel (batched climate serves)"
                       : "NOT byte-identical — cross-chunk climate batching disabled for this dimension (per-chunk path)");
            return ok;
        } catch (Throwable t) {
            LOGGER.info("[SuperChunk-GPU] [biome] gate(batched) for {}: threw during verify — "
                    + "cross-chunk climate batching disabled for this dimension (per-chunk path).", dimLabel(), t);
            return false;
        }
    }

    /**
     * fp32-dispatcher acceptance gate ({@code gpu.climateFp32}): same lattice sweep as
     * {@link #batchedByteIdentical}, but the per-cell criterion mirrors the runtime
     * serve-side guard exactly — a cell is OK when EITHER (a) its float value lands
     * inside the guard band (the serve seam will recompute it on the CPU: vanilla), or
     * (b) its quantized coordinate ({@code Climate.quantizeCoord((float)v)}, the value
     * biome selection consumes) equals the fp64 reference's. Additionally REFUSES the
     * dispatcher when the measured max |scaled32 - scaled64| exceeds HALF the guard
     * band — the epsilon must dominate the drift with margin, or unguarded samples
     * could quantize differently on content the lattice didn't sample.
     */
    private static boolean batchedQuantIdentical(GpuBatchDispatcher disp, GpuFusedInterpolator fused) {
        int m = fused.rootCount();
        int n = GATE_DIMX * GATE_DIMY * GATE_DIMZ;
        int K = GATE_ORIGINS.length;
        int[] origins = new int[K * 3];
        for (int c = 0; c < K; c++) {
            origins[c * 3] = GATE_ORIGINS[c][0];
            origins[c * 3 + 1] = GATE_ORIGINS[c][1];
            origins[c * 3 + 2] = GATE_ORIGINS[c][2];
        }
        try {
            double[] out = new double[K * m * n];
            if (!disp.batchFill(origins, K, 4, 4, 4, GATE_DIMX, GATE_DIMY, GATE_DIMZ, out)) {
                LOGGER.info("[SuperChunk-GPU] [biome] gate(fp32-quant) for {}: batchFill failed.", dimLabel());
                return false;
            }
            long checked = 0, quantMism = 0, guarded = 0;
            double maxScaledErr = 0.0;
            for (int c = 0; c < K; c++) {
                double[][] ref = new double[m][];
                if (!fused.fillAllGrids(ref, GATE_ORIGINS[c][0], GATE_ORIGINS[c][1], GATE_ORIGINS[c][2],
                        4, 4, 4, GATE_DIMX, GATE_DIMY, GATE_DIMZ)) {
                    LOGGER.info("[SuperChunk-GPU] [biome] gate(fp32-quant) for {}: fused reference fill failed.", dimLabel());
                    return false;
                }
                for (int k = 0; k < m; k++) {
                    for (int i = 0; i < n; i++) {
                        checked++;
                        float fb = (float) out[(c * m + k) * n + i];
                        float fv = (float) ref[k][i];
                        double scaledErr = Math.abs((double) (fb * 10000.0f) - (double) (fv * 10000.0f));
                        if (scaledErr > maxScaledErr) {
                            maxScaledErr = scaledErr;
                        }
                        float scaled = fb * 10000.0f;
                        double dist = Math.abs((double) scaled - Math.rint(scaled));
                        boolean inGuardBand = !(dist > CLIMATE_FP32_EPS) || !Float.isFinite(fb);
                        if (inGuardBand) {
                            guarded++;   // serve() recomputes these on the CPU — always safe
                        } else if (Climate.quantizeCoord(fb) != Climate.quantizeCoord(fv)) {
                            quantMism++;
                        }
                    }
                }
            }
            boolean marginOk = maxScaledErr < CLIMATE_FP32_EPS * 0.5;
            boolean ok = quantMism == 0 && marginOk;
            LOGGER.info("[SuperChunk-GPU] [biome] gate(fp32-quant) for {}: checked={} quant-mismatch={} "
                            + "guard-band cells={} ({}%) max |scaled err|={} (band={}, need < half) -> {}",
                    dimLabel(), checked, quantMism, guarded,
                    String.format("%.2f", checked == 0 ? 0.0 : 100.0 * guarded / checked),
                    String.format("%.5f", maxScaledErr), String.format("%.3f", (double) CLIMATE_FP32_EPS),
                    ok ? "fp32 climate ACCEPTED (quantized-identical outside the guard band)"
                       : "REFUSED — fp64 climate dispatcher stays");
            return ok;
        } catch (Throwable t) {
            LOGGER.info("[SuperChunk-GPU] [biome] gate(fp32-quant) for {}: threw during verify — refused.", dimLabel(), t);
            return false;
        }
    }

    /** Lazily starts the dedicated climate batcher. Called under {@link #BUILD_LOCK}. */
    private static void ensureClimateBatcher() {
        if (CLIMATE_BATCHER != null) {
            return;
        }
        try {
            GpuChunkBatcher b = GpuChunkBatcher.createDedicated("SuperChunk-GPU-climate-batcher");
            CLIMATE_BATCHER = b;
            LOGGER.info("[SuperChunk-GPU] [biome-batch] dedicated climate batcher STARTED (batchLimit={}, windowMicros={}) "
                            + "— all climate GPU dispatches now run on this single drainer thread; workers never touch the driver.",
                    b.batchLimit(), b.batchWindowMicros());
        } catch (Throwable t) {
            CLIMATE_BATCHER = null;
            LOGGER.warn("[SuperChunk-GPU] [biome-batch] climate batcher failed to start — per-chunk climate dispatch stays.", t);
        }
    }

    /**
     * Whether the BIOMES-boundary climate prefetch seam should engage: offload on,
     * cross-chunk batching on, and the dedicated climate batcher live. Cheap volatile
     * reads — called per BIOMES status upgrade by {@code VanillaWorldGenerationDelegate}.
     */
    public static boolean isBatchClimateActive() {
        return enabled && BATCH_CLIMATE && CLIMATE_BATCHER != null;
    }

    /** The dedicated climate batcher, or {@code null} (stats/logging use). */
    public static GpuChunkBatcher climateBatcher() {
        return CLIMATE_BATCHER;
    }

    /**
     * PIPELINE MERGE EXPERIMENT ({@code -Dsuperchunk.biome.sharedBatcher}, DEFAULT FALSE
     * — MEASURED WORSE, do not re-attempt without new evidence): riding the SHARED
     * density batcher (climate as aux {@code submitTo} slots on one drainer+completer
     * pair) was hypothesized to reclaim the dedicated pair's core cost, but the 21-worker
     * r2048 interleaved A/B (2026-07-07) measured the merge at 742.1/742.1 cps vs the
     * DEDICATED pair's 777.0/795.8 (−5.6%): a single drainer COUPLES the two families'
     * backpressure — while it blocks in acquireSlot on a density batch, climate flushes
     * age head-of-line and their deposits arrive late for the biomes stage. Two
     * independent pipelines interleave better despite costing a thread pair. The flag is
     * kept for re-testing on other hardware; the {@code OpenCLBackend.shutdown} ordering
     * (shared batcher drained before our aux dispatchers close) is correct under either
     * routing and stays.
     */
    private static final boolean SHARED_BATCHER =
            Boolean.parseBoolean(System.getProperty("superchunk.biome.sharedBatcher", "false"));

    /** The batcher climate submits ride right now: shared (merge) if live, else dedicated. */
    private static GpuChunkBatcher climateSubmitBatcher() {
        if (SHARED_BATCHER) {
            GpuChunkBatcher shared = GpuChunkBatcher.shared();
            if (shared != null) {
                return shared;
            }
        }
        return CLIMATE_BATCHER;
    }

    /**
     * Everything {@link GpuClimatePrefetch} needs to prefetch one chunk's climate batch:
     * the dedicated batcher + this dimension's batched program slot, the store key +
     * expected packed length, and the quart lattice in BLOCK coords (stride 4).
     */
    public record ClimatePlan(GpuChunkBatcher batcher, GpuBatchDispatcher disp,
                              GpuClimateStore.Key key, int expectLen,
                              int ox, int oy, int oz, int dimX, int dimY, int dimZ,
                              List<AstNode> chainRoots) {
        // chainRoots: the gated batched program's exact roots (Group.batchRoots) — what
        // the climate CHAIN attaches with (see GpuBatchDispatcher.attachClimateChain).
    }

    /**
     * Plans the BIOMES-boundary climate prefetch for {@code chunk}: resolves the
     * dimension's registered fused climate group from {@code router} (the SAME
     * temperature-identity lookup {@link #beginChunk} serves by) and derives the quart
     * lattice EXACTLY as {@link #beginChunk} derives it (same fields, same formulas), so
     * a deposited grid is always found — and only found — by its own chunk's biome fill.
     * Returns {@code null} whenever batching can't apply (off, no batcher, group not
     * registered / not batched, underivable extent). Never throws.
     */
    public static ClimatePlan planBatch(ChunkAccess chunk, NoiseRouter router) {
        if (!enabled || !BATCH_CLIMATE || chunk == null || router == null || !OpenCLBackend.isAvailable()) {
            return null;
        }
        GpuChunkBatcher batcher = climateSubmitBatcher();
        if (batcher == null) {
            return null;
        }
        try {
            GpuDensityFunction t = gpuOf(router.temperature());
            if (t == null) {
                return null;
            }
            Group g = REGISTRY.get(t);
            if (g == null || g.batchDisp() == null) {
                return null;
            }
            ChunkPos cp = chunk.getPos();
            LevelHeightAccessor h = chunk.getHeightAccessorForGeneration();
            int minSection = h.getMinSection();
            int sections = h.getMaxSection() - minSection;
            if (cp == null || sections <= 0) {
                return null;
            }
            int oyQuart = QuartPos.fromSection(minSection);
            int dimY = sections * 4;
            long len = 4L * dimY * 4L;
            if (len <= 0 || len > MAX_GRID_LEN) {
                return null;
            }
            GpuClimateStore.Key key = new GpuClimateStore.Key(cp.toLong(), oyQuart, dimY, g.fused());
            long expect = (long) g.fused().rootCount() * len;
            if (expect > Integer.MAX_VALUE) {
                return null;
            }
            return new ClimatePlan(batcher, g.batchDisp(), key, (int) expect,
                    cp.getMinBlockX(), QuartPos.toBlock(oyQuart), cp.getMinBlockZ(), 4, dimY, 4,
                    g.batchRoots());
        } catch (Throwable th) {
            return null;
        }
    }

    // Float-parity gate sample lattice: a few chunk origins, quart stride, full overworld
    // height — same geometry the cache serves, varied across coordinate space.
    private static final int GATE_DIMX = 4, GATE_DIMY = 96, GATE_DIMZ = 4;
    private static final int[][] GATE_ORIGINS = {{96, -64, -240}, {-1024, -64, 512}, {4096, -64, -4096}, {0, -64, 0}};

    /**
     * Returns true iff {@code gpu}'s grid is float-for-float identical to the vanilla
     * {@code DensityFunction.compute()} at every quart-block position of the gate lattices.
     * The float cast is the one {@code Climate.Sampler.sample} applies before quantizing —
     * so float-equality here means this DF can be GPU-served without changing any biome.
     */
    private static boolean floatExact(String name, DensityFunction vanilla, GpuDensityFunction gpu) {
        int n = GATE_DIMX * GATE_DIMY * GATE_DIMZ;
        double[] grid = new double[n];
        long checked = 0, floatMism = 0, doubleMism = 0;
        double maxFloatErr = 0.0;
        try {
            for (int[] org : GATE_ORIGINS) {
                int ox = org[0], oy = org[1], oz = org[2];
                if (!gpu.fillWholeGrid(grid, ox, oy, oz, 4, 4, 4, GATE_DIMX, GATE_DIMY, GATE_DIMZ)) {
                    LOGGER.info("[SuperChunk-GPU] [biome] gate: {} fillWholeGrid failed — excluded (CPU).", name);
                    return false;
                }
                for (int ix = 0; ix < GATE_DIMX; ix++) {
                    for (int iy = 0; iy < GATE_DIMY; iy++) {
                        for (int iz = 0; iz < GATE_DIMZ; iz++) {
                            int flat = (iy * GATE_DIMX + ix) * GATE_DIMZ + iz;
                            int bx = ox + ix * 4, by = oy + iy * 4, bz = oz + iz * 4;
                            double vd = vanilla.compute(new DensityFunction.SinglePointContext(bx, by, bz));
                            double gd = grid[flat];
                            checked++;
                            if (Double.compare(vd, gd) != 0) doubleMism++;
                            if (Float.compare((float) vd, (float) gd) != 0) {
                                floatMism++;
                                double fe = Math.abs((double) (float) vd - (double) (float) gd);
                                if (fe > maxFloatErr) maxFloatErr = fe;
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.info("[SuperChunk-GPU] [biome] gate: {} threw during verify — excluded (CPU).", name, t);
            return false;
        }
        boolean ok = (floatMism == 0);
        LOGGER.info("[SuperChunk-GPU] [biome] gate: {} checked={} float-mismatch={} (maxFloatErr={}) double-mismatch={} -> {}",
                name, checked, floatMism, String.format("%.3e", maxFloatErr), doubleMism,
                ok ? "FLOAT-EXACT (offload to GPU)" : "NOT float-exact (keep on CPU — would change biomes)");
        return ok;
    }

    /**
     * AUTHORITATIVE fused-output gate. Re-runs the {@link #floatExact} float-parity check
     * against the {@link GpuFusedInterpolator}'s ACTUAL output — the bytes production serves
     * from the fused (shared-NoiseRegistry / cross-DF CSE) kernel, NOT the per-DF kernels the
     * per-DF gate validated — over the SAME gate lattices and the SAME biome-deciding float
     * cast. Returns {@code rootOk[k]} per fused root {@code k} (== the kth member, in
     * {@code vanilla}/{@code names} order): true iff that root is float-for-float identical to
     * {@code vanilla[k].compute()} at every quart-block position, so it can be GPU-served
     * without changing any biome. A root that diverges is dropped back to the CPU
     * (parity-safe). On any fill/throw failure every root is reported NOT float-exact (all drop
     * to CPU). {@code vanilla} and {@code names} are parallel to the fused roots.
     */
    private static boolean[] floatExactFused(GpuFusedInterpolator fused, DensityFunction[] vanilla, String[] names) {
        int m = fused.rootCount();
        boolean[] ok = new boolean[m];   // default false: a fill/throw failure leaves all roots on CPU
        long[] floatMism = new long[m];
        double[] maxFloatErr = new double[m];
        long checked = 0;
        try {
            for (int[] org : GATE_ORIGINS) {
                int ox = org[0], oy = org[1], oz = org[2];
                double[][] grid = new double[m][];   // fillAllGrids allocates each n-length slice
                if (!fused.fillAllGrids(grid, ox, oy, oz, 4, 4, 4, GATE_DIMX, GATE_DIMY, GATE_DIMZ)) {
                    LOGGER.info("[SuperChunk-GPU] [biome] gate(fused): fillAllGrids failed — all roots excluded (CPU).");
                    return ok;   // still all false
                }
                for (int ix = 0; ix < GATE_DIMX; ix++) {
                    for (int iy = 0; iy < GATE_DIMY; iy++) {
                        for (int iz = 0; iz < GATE_DIMZ; iz++) {
                            int flat = (iy * GATE_DIMX + ix) * GATE_DIMZ + iz;
                            int bx = ox + ix * 4, by = oy + iy * 4, bz = oz + iz * 4;
                            for (int k = 0; k < m; k++) {
                                double vd = vanilla[k].compute(new DensityFunction.SinglePointContext(bx, by, bz));
                                double gd = grid[k][flat];
                                if (Float.compare((float) vd, (float) gd) != 0) {
                                    floatMism[k]++;
                                    double fe = Math.abs((double) (float) vd - (double) (float) gd);
                                    if (fe > maxFloatErr[k]) maxFloatErr[k] = fe;
                                }
                            }
                        }
                    }
                }
                checked += (long) GATE_DIMX * GATE_DIMY * GATE_DIMZ;
            }
        } catch (Throwable t) {
            LOGGER.info("[SuperChunk-GPU] [biome] gate(fused): threw during verify — all roots excluded (CPU).", t);
            return ok;   // still all false
        }
        for (int k = 0; k < m; k++) {
            ok[k] = (floatMism[k] == 0);
            LOGGER.info("[SuperChunk-GPU] [biome] gate(fused): {} checked={} float-mismatch={} (maxFloatErr={}) -> {}",
                    names[k], checked, floatMism[k], String.format("%.3e", maxFloatErr[k]),
                    ok[k] ? "FLOAT-EXACT (serve fused)" : "NOT float-exact (drop root to CPU — would change biomes)");
        }
        return ok;
    }

    /** Closes the fused biome programs + clears the registry. Called from {@code OpenCLBackend.shutdown()}. */
    public static void shutdown() {
        // Cross-chunk climate batching teardown FIRST: stop the drainer (joins the
        // thread, flushing/failing every in-flight future) BEFORE closing the
        // per-dimension batched dispatchers it dispatches on. Even if the join times
        // out, GpuBatchDispatcher.close() blocks on its lifecycleLock until any
        // in-flight batchFill finishes — no native use-after-free is possible.
        GpuChunkBatcher cb = CLIMATE_BATCHER;
        CLIMATE_BATCHER = null;
        if (cb != null) {
            try {
                cb.shutdown();
            } catch (Throwable ignored) {
            }
        }
        for (GpuBatchDispatcher d : CLIMATE_DISPATCHERS) {
            try {
                d.close();
            } catch (Throwable ignored) {
            }
        }
        CLIMATE_DISPATCHERS.clear();
        GpuClimateStore.clear();
        REGISTRY.clear();
        for (GpuFusedInterpolator fused : PROGRAMS) {
            try {
                fused.close();
            } catch (Throwable ignored) {
            }
        }
        PROGRAMS.clear();
    }

    // ------------------------------------------------------------------
    // Per-thread, per-chunk state.
    // ------------------------------------------------------------------

    private static final class Ctx {
        Object sampler;          // the armed Climate.Sampler identity (serve only for THIS sampler)
        Group group;
        // quart-lattice extent.
        int oxQuart, oyQuart, ozQuart;   // quart origin (chunk-min)
        int dimX, dimY, dimZ;            // dimX=dimZ=4; dimY = sections*4
        int gridLen;
        // GPU dispatch handle + completed grids.
        GpuFusedInterpolator.PendingFusedFill pending;
        // FUNNELED single-chunk climate request (batchClimate miss path): the future from
        // the dedicated climate drainer. Mutually exclusive with `pending` — under
        // batchClimate the worker never enqueues driver calls itself; it waits on this
        // future at first serve instead. Cancelled (best-effort) at disarm.
        java.util.concurrent.CompletableFuture<double[]> pendingBatch;
        double[][] grids;        // [backedCount][gridLen] once completed
        boolean gridsFailed;     // dispatch/completion failed -> serve via CPU for this chunk
        boolean armed;
        // OPT#11: per-thread biome-serve counters, accumulated in serve() and flushed once
        // per chunk (in disarm) so the per-quart serve path never hits an AtomicLong under
        // 16-worker contention.
        long biGpuSamples, biGpuEvals, biCpuSamples;
        // Depth biome-FLIP measurement (DEPTH_FLIP_VERIFY only): the chunk's biome resolver
        // when it is a MultiNoiseBiomeSource (stashed at beginChunk so the serve mixin can
        // resolve the ACTUAL selected biome for GPU-vs-vanilla TargetPoints through the very
        // ParameterList RTree production uses), plus per-thread flip counters accumulated in
        // recordDepthFlipSample and flushed once per chunk in disarm (OPT#11 pattern).
        MultiNoiseBiomeSource flipSource;
        long dfvChecked;          // GPU-served depth quart samples compared
        long dfvDepthQuantMism;   // Climate.quantizeCoord(depth) longs differed
        long dfvTargetMism;      // full quantized TargetPoint records differed (EXACT selection-input bound)
        long dfvDecided;          // samples whose ACTUAL-biome equality was determined (TargetPoints equal, or RTree-resolved)
        long dfvBiomeFlips;       // ACTUAL biome flips (among decided samples)
        // OPT#12: reusable per-thread fused-grid buffer. Persists ACROSS chunks on this
        // thread (NOT nulled in disarm); re-grown only when (m,len) changes (dimension
        // switch). Each fill fully overwrites all m*len elements before it is published.
        double[][] gridsBuf;
        // Reusable {temperature, vegetation, depth} scratch returned by serve(): lives on
        // the per-thread Ctx so the serve hot path needs ONE ThreadLocal lookup (CTX.get())
        // instead of two (this used to be a separate ThreadLocal in MixinClimateSampler).
        // Consumed synchronously by the caller before the next serve() on this thread.
        final double[] tvd = new double[3];
    }

    private static final ThreadLocal<Ctx> CTX = ThreadLocal.withInitial(Ctx::new);

    /**
     * Begins this thread's biome cache for {@code chunk} + {@code sampler}: matches the
     * sampler's temperature GPU function to a registered fused biome group, derives the
     * chunk's quart lattice, and EAGERLY dispatches the fused grid with a non-blocking
     * readback (the worker keeps running; the wait is deferred to the first {@link #serve}).
     * No-op (CPU climate path) when the offload is off, no group matches, the extent can't
     * be derived, or the dispatch fails. {@code resolver} (may be null) is the chunk's
     * {@link BiomeResolver}; used ONLY under {@link #DEPTH_FLIP_VERIFY} to reach the
     * dimension's {@link MultiNoiseBiomeSource} for actual-biome flip resolution.
     */
    public static void beginChunk(ChunkAccess chunk, Climate.Sampler sampler, BiomeResolver resolver) {
        Ctx ctx = CTX.get();
        disarm(ctx);
        if (!enabled || chunk == null || sampler == null || !OpenCLBackend.isAvailable()) {
            return;
        }
        // Consumer check: a biome source that provably never calls Climate.Sampler.sample()
        // (The End's erosion-only resolver, fixed/checkerboard sources) would have its fused
        // climate grids computed, consumed/funneled and then discarded UNREAD every chunk —
        // pure GPU + drainer waste behind a green "offload ENABLED" boot line. Skip arming.
        if (resolver != null && neverSamplesClimate(resolver)) {
            return;
        }
        GpuDensityFunction t = gpuOf(sampler.temperature());
        if (t == null) {
            return;
        }
        Group g = REGISTRY.get(t);
        if (g == null) {
            return;
        }
        // Defensive: the GPU-served members must be the SAME GPU functions the group fused
        // (a different sampler with a colliding temperature -> CPU fallback). NB the
        // Climate.Sampler record names router.vegetation() as humidity().
        if (g.veg() != null && gpuOf(sampler.humidity()) != g.veg()) {
            return;
        }
        if (g.depth() != null && gpuOf(sampler.depth()) != g.depth()) {
            return;
        }
        try {
            ChunkPos cp = chunk.getPos();
            LevelHeightAccessor h = chunk.getHeightAccessorForGeneration();
            int minSection = h.getMinSection();
            int maxSection = h.getMaxSection();
            int sections = maxSection - minSection;
            if (sections <= 0) {
                return;
            }
            ctx.oxQuart = QuartPos.fromBlock(cp.getMinBlockX());
            ctx.ozQuart = QuartPos.fromBlock(cp.getMinBlockZ());
            ctx.oyQuart = QuartPos.fromSection(minSection);
            ctx.dimX = 4;
            ctx.dimZ = 4;
            ctx.dimY = sections * 4;
            long len = (long) ctx.dimX * ctx.dimY * ctx.dimZ;
            if (len <= 0 || len > MAX_GRID_LEN) {
                return;
            }
            ctx.gridLen = (int) len;
            ctx.group = g;
            ctx.sampler = sampler;
            ctx.grids = null;
            ctx.gridsFailed = false;
            ctx.armed = true;
            if (DEPTH_FLIP_VERIFY && resolver instanceof MultiNoiseBiomeSource mnbs) {
                // The vanilla createBiomes resolver is (usually) the dimension's biome source
                // itself; when it is a MultiNoiseBiomeSource the flip measurement can resolve
                // ACTUAL biomes through its public getNoiseBiome(TargetPoint) (the production
                // ParameterList RTree). Otherwise flipSource stays null and the measurement
                // falls back to the exact quantized-TargetPoint bound.
                ctx.flipSource = mnbs;
            }

            int ox = QuartPos.toBlock(ctx.oxQuart);
            int oy = QuartPos.toBlock(ctx.oyQuart);
            int oz = QuartPos.toBlock(ctx.ozQuart);
            if (BATCH_CLIMATE) {
                // 1) CROSS-CHUNK CONSUME: the BIOMES-boundary prefetch (GpuClimatePrefetch)
                //    already computed this chunk's quart grids in a BATCHED dispatch and
                //    deposited them — copy them into this thread's reused buffer exactly as
                //    the post-dispatch path would (byte-identical by the gate(batched) proof).
                //    NO GPU dispatch, no driver call, nothing to wait on.
                if (consumeBatchedClimate(ctx, cp, g)) {
                    return;
                }
                // 2) MISS (no prefetch deposit: seam not engaged for this chunk, cancel/evict
                //    race, or sampler/group mismatch) — counted, then FUNNELED as a
                //    single-chunk request through the same climate drainer thread so this
                //    WORKER still never calls clEnqueue*/clUnmap. The wait is deferred to
                //    the first serve() (ensureGrids), just like the eager-async path.
                GpuFillStats.recordClimateBatchMiss();
                if (!WORKER_DISPATCH) {
                    GpuChunkBatcher b = climateSubmitBatcher();
                    GpuBatchDispatcher bd = g.batchDisp();
                    if (b != null && bd != null) {
                        ctx.pendingBatch = b.submitTo(bd, ox, oy, oz, 4, 4, 4, ctx.dimX, ctx.dimY, ctx.dimZ);
                        return;
                    }
                    // No drainer / no gated program slot for this dimension: fall through to
                    // the per-chunk worker dispatch below (last resort, still fully gated).
                }
            }
            // EAGER ASYNC DISPATCH over the whole quart lattice (block coords) on the
            // WORKER thread — the pre-batching path. Reached only when batchClimate=false,
            // -Dsuperchunk.gpu.workerDispatch=true, or this dimension has no batched slot.
            ctx.pending = g.fused().dispatchAsync(ox, oy, oz, 4, 4, 4, ctx.dimX, ctx.dimY, ctx.dimZ);
            // pending may be null on dispatch failure -> ensureGrids() does a blocking fill.
        } catch (Throwable th) {
            disarm(ctx);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[SuperChunk-GPU] [biome] beginChunk threw — CPU climate path for this chunk.", th);
            }
        }
    }

    /** Once-per-class log guard for {@link #neverSamplesClimate}. */
    private static final java.util.Set<String> NON_SAMPLING_LOGGED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * TRUE when {@code source} provably never calls {@link Climate.Sampler#sample}: vanilla's
     * {@link TheEndBiomeSource} (erosion-only — the active c2me the_end_biome_cache overwrite
     * keeps that shape), {@link FixedBiomeSource} and {@link CheckerboardColumnBiomeSource}.
     * For these the whole climate offload (batched prefetch + per-chunk arm/consume/funnel)
     * is computed-then-never-read waste, so both seams skip it. Unknown/modded resolvers keep
     * the offload — they may genuinely sample. Logs once per source class.
     */
    public static boolean neverSamplesClimate(Object source) {
        if (source instanceof TheEndBiomeSource
                || source instanceof FixedBiomeSource
                || source instanceof CheckerboardColumnBiomeSource) {
            if (NON_SAMPLING_LOGGED.add(source.getClass().getName())) {
                LOGGER.info("[SuperChunk-GPU] [biome] climate offload SKIPPED for biome source {} — it never calls "
                                + "Climate.Sampler.sample(), so per-chunk GPU climate grids would be computed and thrown away.",
                        source.getClass().getSimpleName());
            }
            return true;
        }
        return false;
    }

    /** Ends this thread's biome cache: discards any in-flight dispatch + clears state. */
    public static void endChunk() {
        disarm(CTX.get());
    }

    private static void disarm(Ctx ctx) {
        // OPT#11: flush this chunk's accumulated biome-serve counters in ONE batched update
        // before resetting ctx state. disarm runs at endChunk AND at the next beginChunk AND
        // on failure, so counts (which only accrue between begin/end on this thread) are
        // flushed exactly when a chunk's biome pass ends or the next one begins. gridsBuf is
        // deliberately left intact (OPT#12 cross-chunk reuse).
        if (ctx.biGpuSamples != 0L || ctx.biCpuSamples != 0L) {
            GpuFillStats.recordBiomeSamples(ctx.biGpuSamples, ctx.biGpuEvals, ctx.biCpuSamples);
            ctx.biGpuSamples = 0L;
            ctx.biGpuEvals = 0L;
            ctx.biCpuSamples = 0L;
        }
        // Depth biome-FLIP measurement: flush this chunk's flip counters in ONE batched update
        // (same once-per-chunk cadence as the serve counters above). No-ops when the verify
        // flag is off (counters never move).
        if (ctx.dfvChecked != 0L) {
            GpuFillStats.recordDepthFlipSamples(ctx.dfvChecked, ctx.dfvDepthQuantMism, ctx.dfvTargetMism,
                    ctx.dfvDecided, ctx.dfvBiomeFlips);
            ctx.dfvChecked = 0L;
            ctx.dfvDepthQuantMism = 0L;
            ctx.dfvTargetMism = 0L;
            ctx.dfvDecided = 0L;
            ctx.dfvBiomeFlips = 0L;
        }
        ctx.flipSource = null;
        GpuFusedInterpolator.PendingFusedFill p = ctx.pending;
        if (p != null) {
            ctx.pending = null;
            try {
                p.discard();
            } catch (Throwable ignored) {
            }
        }
        // Funneled climate batch left unconsumed (chunk ended before any serve, or an
        // exception unwound the biome pass): cancel best-effort. Not-yet-dispatched ->
        // the drainer drops it; already-dispatched -> the result is simply discarded.
        // Either way nothing leaks and no thread waits.
        java.util.concurrent.CompletableFuture<double[]> fb = ctx.pendingBatch;
        if (fb != null) {
            ctx.pendingBatch = null;
            try {
                fb.cancel(false);
            } catch (Throwable ignored) {
            }
        }
        ctx.armed = false;
        ctx.sampler = null;
        ctx.group = null;
        ctx.grids = null;
        ctx.gridsFailed = false;
    }

    // ------------------------------------------------------------------
    // Serve (Climate.Sampler.sample mixin).
    // ------------------------------------------------------------------

    /**
     * Serves the GPU-backed climate DFs for the quart sample {@code (qx,qy,qz)}. On a hit
     * returns this thread's reusable {@code {temperature, vegetation, depth}} scratch
     * ({@code [0]=temperature}, {@code [1]=vegetation}, {@code [2]=depth}); a slot the group
     * does NOT GPU-serve (not float-exact) is set to {@link Double#NaN} and the caller
     * computes it on the CPU exactly as vanilla. Returns {@code null} (nothing to read)
     * when this thread is not armed for {@code sampler}, the point is outside the cached
     * lattice, or the grids are unavailable -> unchanged vanilla. The returned array is
     * thread-local and reused on the next call, so the caller must consume it synchronously.
     */
    public static double[] serve(Object sampler, int qx, int qy, int qz) {
        if (!enabled) {
            return null;
        }
        Ctx ctx = CTX.get();
        if (!ctx.armed || ctx.sampler != sampler) {
            return null;
        }
        int ix = qx - ctx.oxQuart;
        int iy = qy - ctx.oyQuart;
        int iz = qz - ctx.ozQuart;
        if (ix < 0 || ix >= ctx.dimX || iy < 0 || iy >= ctx.dimY || iz < 0 || iz >= ctx.dimZ) {
            return null;   // outside the chunk quart lattice -> vanilla
        }
        double[][] grids = ensureGrids(ctx);
        if (grids == null) {
            ctx.biCpuSamples++;   // OPT#11: per-thread; flushed once/chunk in disarm
            return null;   // dispatch failed -> vanilla CPU climate for this chunk
        }
        int flat = (iy * ctx.dimX + ix) * ctx.dimZ + iz;
        Group g = ctx.group;
        double[] out3 = ctx.tvd;
        // temperature is the mandatory anchor: tryRegister returns early unless it is
        // float-exact, and addRoot adds it FIRST, so rootTemp() is ALWAYS 0 (>= 0). The
        // vegetation/depth roots can be -1 (excluded -> CPU via NaN).
        out3[0] = grids[g.rootTemp()][flat];
        out3[1] = g.rootVeg() >= 0 ? grids[g.rootVeg()][flat] : Double.NaN;
        out3[2] = g.rootDepth() >= 0 ? grids[g.rootDepth()][flat] : Double.NaN;
        ctx.biGpuSamples++;                    // OPT#11: per-thread; flushed once/chunk
        ctx.biGpuEvals += g.backedCount();
        return out3;
    }

    /**
     * Depth biome-FLIP measurement ({@link #DEPTH_FLIP_VERIFY} only): the armed chunk's
     * {@link MultiNoiseBiomeSource}, or {@code null} when this thread is not armed or the
     * chunk's resolver was not a {@code MultiNoiseBiomeSource} (the caller then falls back
     * to the exact quantized-TargetPoint bound). Called by {@code MixinClimateSampler} right
     * after a successful {@link #serve} on the same thread.
     */
    public static MultiNoiseBiomeSource flipVerifyBiomeSource() {
        Ctx ctx = CTX.get();
        return ctx.armed ? ctx.flipSource : null;
    }

    /**
     * Depth biome-FLIP measurement ({@link #DEPTH_FLIP_VERIFY} only): accumulates one
     * GPU-served depth quart sample's comparison into this thread's per-chunk counters
     * (flushed once per chunk in {@code disarm} — OPT#11 pattern, no atomics on the sample
     * path). {@code depthQuantMismatch} = the {@code Climate.quantizeCoord(depth)} longs
     * differed; {@code targetMismatch} = the full quantized {@code Climate.TargetPoint}
     * records differed (EXACT "would the selection input differ" bound — equal TargetPoints
     * GUARANTEE the same biome); {@code biomeDecided} = actual-biome equality was determined
     * (TargetPoints equal, or both resolved through the {@code MultiNoiseBiomeSource} RTree);
     * {@code biomeFlip} = the ACTUAL selected biome differed (only ever true on a decided
     * sample).
     */
    public static void recordDepthFlipSample(boolean depthQuantMismatch, boolean targetMismatch,
                                             boolean biomeDecided, boolean biomeFlip) {
        Ctx ctx = CTX.get();
        ctx.dfvChecked++;
        if (depthQuantMismatch) ctx.dfvDepthQuantMism++;
        if (targetMismatch) ctx.dfvTargetMism++;
        if (biomeDecided) ctx.dfvDecided++;
        if (biomeFlip) ctx.dfvBiomeFlips++;
    }

    /**
     * OPT#12: this thread's reusable {@code [m][len]} grid buffer — re-grown only when
     * {@code (m,len)} changes (a dimension switch). Every fill site FULLY overwrites all
     * {@code m*len} elements before {@code ctx.grids} is published, so no stale value
     * from a prior chunk can ever be served. Shared by the batched-consume, funneled and
     * per-chunk fill paths (exactly one is active per chunk).
     */
    private static double[][] ensureGridsBuf(Ctx ctx, int m, int len) {
        double[][] out = ctx.gridsBuf;
        if (out == null || out.length != m || out[0].length != len) {
            out = new double[m][len];
            ctx.gridsBuf = out;
        }
        return out;
    }

    /**
     * If the BIOMES-boundary prefetch deposited this chunk's BATCHED climate grids in
     * {@link GpuClimateStore}, copy them into this thread's reused buffer and publish
     * them as the chunk's grids — byte-identical to what the per-chunk fused dispatch
     * would have produced (enforced by the boot-time {@code gate(batched)}). Returns
     * {@code false} (nothing consumed... except a same-key entry whose payload didn't
     * validate, which could never match anyway) when no matching deposit exists.
     * Never throws.
     */
    private static boolean consumeBatchedClimate(Ctx ctx, ChunkPos cp, Group g) {
        try {
            GpuClimateStore.Key key = new GpuClimateStore.Key(cp.toLong(), ctx.oyQuart, ctx.dimY, g.fused());
            GpuClimateStore.Stored st = GpuClimateStore.take(key);
            if (st == null || st.fused != g.fused()) {
                return false;
            }
            int m = g.fused().rootCount();
            int len = ctx.gridLen;
            if (st.grids == null || st.grids.length != (long) m * len) {
                return false;   // defensive; the prefetch validates length before put
            }
            double[][] out = ensureGridsBuf(ctx, m, len);
            for (int k = 0; k < m; k++) {
                System.arraycopy(st.grids, k * len, out[k], 0, len);
            }
            ctx.grids = out;
            GpuFillStats.recordClimateBatchServe();
            return true;
        } catch (Throwable th) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[SuperChunk-GPU] [biome-batch] consuming batched climate grid threw — per-chunk path.", th);
            }
            return false;
        }
    }

    /**
     * Lazily completes the eager async dispatch (deferred wait) — or waits on the
     * FUNNELED climate-batcher future, or runs a fresh blocking fused fill if neither
     * was issued — into this chunk's cached grids. Returns the grids, or {@code null}
     * once (sticky) on failure (the chunk then uses the vanilla CPU climate path).
     */
    private static double[][] ensureGrids(Ctx ctx) {
        if (ctx.grids != null) {
            return ctx.grids;
        }
        if (ctx.gridsFailed) {
            return null;
        }
        Group g = ctx.group;
        int len = ctx.gridLen;
        int m = g.fused().rootCount();
        double[][] out = ensureGridsBuf(ctx, m, len);
        // FUNNELED single-chunk climate batch (batchClimate miss path): the dispatch ran
        // (or will run, within the batch window) on the climate drainer thread; this
        // worker only waits on the future — it never touches the driver. The batcher
        // guarantees completion (window-timeout flush; every failure path completes the
        // future exceptionally), so the bounded get() is pure defense in depth. On ANY
        // failure the chunk falls back to the vanilla CPU climate path (parity-safe) —
        // deliberately NOT to a worker-side blocking fill.
        java.util.concurrent.CompletableFuture<double[]> fb = ctx.pendingBatch;
        if (fb != null) {
            ctx.pendingBatch = null;
            boolean fok = false;
            try {
                double[] flat = fb.get(BATCH_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (flat != null && flat.length == (long) m * len) {
                    for (int k = 0; k < m; k++) {
                        System.arraycopy(flat, k * len, out[k], 0, len);
                    }
                    fok = true;
                }
            } catch (java.util.concurrent.TimeoutException te) {
                try {
                    fb.cancel(false);
                } catch (Throwable ignored) {
                }
                long nTo = BATCH_WAIT_TIMEOUTS.incrementAndGet();
                if (nTo <= 3L || nTo % 1000L == 0L) {
                    LOGGER.warn("[SuperChunk-GPU] [biome-batch] funneled climate batch wait timed out after {} ms "
                            + "(occurrence #{}) — CPU climate path for this chunk.", BATCH_WAIT_MS, nTo);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                try {
                    fb.cancel(false);
                } catch (Throwable ignored) {
                }
            } catch (Throwable th) {
                // Dispatch failed / batcher shutting down — already logged drainer-side.
            }
            if (!fok) {
                ctx.gridsFailed = true;
                return null;
            }
            ctx.grids = out;
            return out;
        }
        boolean ok = false;
        GpuFusedInterpolator.PendingFusedFill pending = ctx.pending;
        ctx.pending = null;
        try {
            if (pending != null && pending.n() == len) {
                ok = pending.complete(out);
            } else {
                if (pending != null) {
                    pending.discard();
                }
                int ox = QuartPos.toBlock(ctx.oxQuart);
                int oy = QuartPos.toBlock(ctx.oyQuart);
                int oz = QuartPos.toBlock(ctx.ozQuart);
                ok = g.fused().fillAllGrids(out, ox, oy, oz, 4, 4, 4, ctx.dimX, ctx.dimY, ctx.dimZ);
            }
        } catch (Throwable th) {
            if (pending != null) {
                try { pending.discard(); } catch (Throwable ignored) { }
            }
            LOGGER.warn("[SuperChunk-GPU] [biome] fused climate grid fill threw — CPU climate path for this chunk.", th);
            ok = false;
        }
        if (!ok) {
            ctx.gridsFailed = true;
            return null;
        }
        ctx.grids = out;
        return out;
    }
}
