package dev.superchunk.gpu.dfc;

import dev.superchunk.gpu.OpenCLBackend;
import org.slf4j.Logger;

/**
 * Thread-safe adapter that routes a {@code SubCompiledDensityFunction}'s batch
 * fill to a {@link GpuDensityFunction}.
 *
 * <p><b>Stage 3b (concurrent).</b> Worldgen runs on many c2me-worker threads, all
 * calling {@link #fill}. The backing {@link GpuDensityFunction} is now fully
 * concurrent (per-thread queue + cloned kernel + reusable buffers), so there is
 * NO global lock here — each thread fills independently. The only synchronization
 * is the sticky {@code disabled} flag (set once on a hard fault).
 *
 * <p>{@link #fill} returns {@code true} only if the GPU produced the result; any
 * failure returns {@code false} so the caller uses the CPU bytecode path. Once a
 * GPU fill faults, GPU routing for this delegate is disabled (sticky) to avoid
 * thrashing a broken device.
 *
 * <p><b>FILL-SHAPE POLICY GATE (always-on; regression fix 2026-07-02).</b> The GPU
 * is only a measured WIN on the coalesced whole-chunk-lattice shapes served by
 * {@link ChunkGridCache#tryServe} (whole-grid / fused / cross-chunk-batched, then
 * sliced per column with no dispatch). A DIRECT per-fill dispatch is latency-bound:
 * a tiny blocking dispatch + readback whose fixed cost dwarfs the CPU eval for
 * small n. Historically the router-level giants (finalDensity, depth, the
 * sloped_cheese composites) failed to GPU-compile (opaque {@code BlendedNoise})
 * and so their per-block / per-cell / quart-plane / scan fills ran on CPU; once
 * the BlendedNoise port made EVERY DF compile (108/0, was 104/11), those same
 * small fills started hitting the direct-dispatch fallback and the default fill
 * path collapsed (GPU=100% of fill wall-time). The gate restores the effective
 * pre-regression routing: after a {@code tryServe} MISS, only fills with
 * {@code n >= }{@link #MIN_DIRECT_DISPATCH_N} may take the direct dispatch;
 * everything smaller goes straight to the CPU bytecode path with NO GPU attempt.
 * Escape hatch for experiments: {@code -Dsuperchunk.gpu.liveServeAll=true}
 * disables the gate (restores unconditional direct dispatch). Note this gates
 * ONLY the live delegate serving — parity self-tests, the compact-ids decide
 * plan, the biome-climate offload and on-device interpolation drive
 * {@link GpuDensityFunction}/fused programs directly and never pass through here.
 *
 * <p>Per-batch GPU/CPU counters feed the live integration summary
 * ({@link GpuFillStats}).
 */
public final class GpuDfcDelegate {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    /**
     * Escape hatch: {@code -Dsuperchunk.gpu.liveServeAll=true} disables the fill-shape
     * gate so EVERY delegate fill may take the direct per-fill dispatch (the behaviour
     * that regressed once all DFs compiled). Default false = gate ON.
     */
    private static final boolean LIVE_SERVE_ALL = Boolean.getBoolean("superchunk.gpu.liveServeAll");

    /**
     * Minimum fill size allowed to take the DIRECT per-fill dispatch after a coalesced
     * cache miss. Every chunk-bound lattice fill of ANY size is already served by
     * {@link ChunkGridCache#tryServe} above the gate, so what reaches this check is
     * exclusively cache-miss shapes: per-block fills (n=1), CacheAllInCell per-cell
     * block fills (4x8x4 = 128, stride 1 vs the 4/8 cell lattice), quart-resolution
     * flat-cache planes (~169-289, y off the cell lattice), aquifer scans and
     * out-of-window (no beginChunk) fills — all measured latency-bound on the GPU.
     * 1024 sits well above every such live shape while still letting a genuinely
     * large explicit fill (e.g. a whole-grid-scale bulk fill, OW grid = 1225) through.
     * Package-private so {@link GpuFillStats} can echo it in the summary line.
     */
    static final int MIN_DIRECT_DISPATCH_N = 1024;

    /** One-shot flag so this DF's FIRST gate rejection is logged (debug) for observability. */
    private volatile boolean gateRejectLogged = false;

    // Non-final + volatile so the MERGED-program path ({@code GpuDfcHook} batch) can hand back
    // a delegate at the call site and {@link #resolve} its backing GPU function later, once the
    // one shared program for the whole router has been built at the batch barrier. Until then
    // (or if the merged build failed) it stays null and {@link #fill} cleanly returns CPU.
    private volatile GpuDensityFunction gpu;
    private volatile boolean disabled = false;

    public GpuDfcDelegate(GpuDensityFunction gpu) {
        this.gpu = gpu;
    }

    /** Binds the backing GPU function once the deferred (merged) build resolves. */
    void resolve(GpuDensityFunction g) {
        this.gpu = g;
    }

    /**
     * SuperChunk: the backing GPU density function (may be {@code null} for a not-yet-resolved
     * deferred delegate). Exposed so the biome-climate offload ({@link BiomeClimateCache}) can
     * recover a router/sampler DF's compiled GPU form (and its AST) to fuse the uncached climate
     * DFs into one dispatch; it runs during gen, after the batch barrier, so this is resolved.
     */
    public GpuDensityFunction gpu() {
        return gpu;
    }

    /**
     * Fills {@code densities} on the GPU. Returns false (CPU fallback) when GPU
     * routing is disabled, the backend went away, lengths mismatch, or the GPU
     * eval faults. When it returns false the caller records a CPU batch.
     */
    public boolean fill(double[] densities, int[] x, int[] y, int[] z) {
        final GpuDensityFunction gpu = this.gpu;
        if (gpu == null || disabled || !OpenCLBackend.isAvailable()) {
            GpuFillStats.recordCpu(densities.length);
            return false;   // unresolved deferred delegate (or disabled) -> CPU
        }
        int n = densities.length;
        if (x.length < n || y.length < n || z.length < n) {
            GpuFillStats.recordCpu(n);
            return false;
        }
        try {
            if (disabled) {
                GpuFillStats.recordCpu(n);
                return false;
            }
            GpuFillStats.recordGpuAttempt();
            // SuperChunk GPU (coalescing): serve this column from the whole-chunk
            // grid cache when possible (computes the entire grid in ONE dispatch on
            // first touch, then slices every later column with NO dispatch). The
            // served data is the exact subset of the same lattice the per-column
            // path would compute, so it is bit-for-bit identical. Falls through to
            // the per-column GPU path when not applicable. tryServe reads only
            // [0, n) of x/y/z, so the possibly over-sized pooled arrays are safe
            // to pass unsliced (slicing is deferred until a dispatch is actually
            // taken, keeping gate-rejected fills allocation-free).
            if (ChunkGridCache.tryServe(gpu, densities, x, y, z, n)) {
                GpuFillStats.recordGpu(n);
                return true;
            }
            // FILL-SHAPE POLICY GATE (see class javadoc): a coalesced-cache MISS
            // below the direct-dispatch size floor goes straight to the CPU
            // bytecode path — NO tiny blocking dispatch+readback. This is the
            // effective pre-BlendedNoise-port routing for the router-level giants
            // (they had no GPU delegate at all) and strictly removes measured
            // latency-bound dispatches for everything else. Bit-exact: the caller
            // runs the same evalMulti it runs on any other CPU fallback.
            if (!LIVE_SERVE_ALL && n < MIN_DIRECT_DISPATCH_N) {
                GpuFillStats.recordGateReject(n);
                if (!gateRejectLogged) {
                    gateRejectLogged = true;
                    LOGGER.debug("[SuperChunk-GPU] fill-shape gate: first non-coalesced small fill (n={}) for this DF "
                            + "routed to CPU with NO GPU dispatch (floor n>={}; escape hatch -Dsuperchunk.gpu.liveServeAll=true).",
                            n, MIN_DIRECT_DISPATCH_N);
                }
                GpuFillStats.recordCpu(n);
                return false;
            }
            // The x/y/z arrays may be over-sized (pooled). Slice to exactly n.
            int[] sx = x.length == n ? x : java.util.Arrays.copyOf(x, n);
            int[] sy = y.length == n ? y : java.util.Arrays.copyOf(y, n);
            int[] sz = z.length == n ? z : java.util.Arrays.copyOf(z, n);
            boolean ok = gpu.fill(densities, sx, sy, sz);
            if (ok) {
                GpuFillStats.recordGpu(n);
            } else {
                GpuFillStats.recordCpu(n);
            }
            return ok;
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] GPU DF fill threw — disabling GPU for this DF, CPU fallback.", t);
            disabled = true;
            GpuFillStats.recordCpu(n);
            return false;
        }
    }
}
