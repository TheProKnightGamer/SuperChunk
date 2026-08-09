package dev.superchunk.gpu.dfc;

import dev.superchunk.gpu.CLProgram;
import dev.superchunk.gpu.OpenCLBackend;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * STAGE-2 <b>batched decide-kernel duty bench</b> (flag
 * {@code -Dsuperchunk.gpu.decideBench=N}, default OFF/0 = zero production impact).
 *
 * <p>Answers ONE question with NO server-integration risk: does a compact-readback
 * "decide" kernel (in-register 8-corner trilinear interpolation + representative
 * finalDensity tail + the REAL {@code aq_decide} aquifer path + per-block vein
 * Xoroshiro draws, writing 1 byte/block) fit the single-drainer duty budget?
 * <b>GO gate: fp64 &lt;= 1.0 ms/chunk (kernel + id readback) at K=32.</b>
 *
 * <p>Mirrors the {@link FullFieldBench} diagnostic pattern (kernel time from the CL
 * profiling event, readback wall-time measured separately) and the
 * {@code GpuCompileBench} one-shot hook (invoked from
 * {@code SuperChunk.initGpuBackend()} at ServerAboutToStart; internally gated on the
 * flag). Everything is SYNTHETIC: corner grids in the exact
 * {@code df_batch_lattice_multichunk} K*M*1225 layout, plausible 315-cell FluidStatus
 * arrays + jittered aquifer location caches, uploaded ONCE; N warm-up + N timed
 * iterations run on ONE in-order queue; the K*98304-byte id buffer is pinned-mapped
 * back each iteration (the ~96KB/chunk readback IS part of the measured duty). The
 * ids are histogrammed for a non-degeneracy sanity check and then DISCARDED — terrain
 * is never touched, no production kernel or dispatch path is modified.
 *
 * <p>Runs an fp64 leg and (if the {@code -DUSE_FP32} program builds) an fp32 leg.
 * NOTE: {@code aq_decide} is hard-typed {@code double} (aquifer.cl), so the fp32 leg
 * measures fp32 interpolation/tail + fp64 decision math, and the whole bench is
 * skipped on devices without {@code cl_khr_fp64}. All log lines are greppable as
 * {@code [decide-bench]}. Never throws.
 */
public final class DecideBench {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    private static final String RNG_CL = "/superchunk/kernels/rng.cl";
    private static final String NOISE_CL = "/superchunk/kernels/noise.cl";
    private static final String AQUIFER_CL = "/superchunk/kernels/aquifer.cl";
    private static final String DECIDE_CL = "/superchunk/kernels/decide_bench.cl";
    private static final String KERNEL_NAME = "decide_bench";

    // ---- multichunk batch shape (mirrors the live batcher at batchLimit=32). ----
    /** Chunks per dispatch (the GO gate is defined at K=32). */
    private static final int K = Math.max(1, Integer.getInteger("superchunk.gpu.decideBench.k", 32));
    /** Grids (roots) per chunk in the corner buffer — mirrors the production M≈7 footprint. */
    private static final int M = 7;

    // ---- canonical full-chunk cell-corner geometry (cellWidth=4, cellHeight=8). ----
    private static final int DIM_X = 5, DIM_Y = 49, DIM_Z = 5;    // corner dims
    private static final int SX = 4, SY = 8, SZ = 4;              // cell block sizes
    private static final int CORNER_N = DIM_X * DIM_Y * DIM_Z;    // 1225
    private static final int FULL_X = 16, FULL_Y = 384, FULL_Z = 16;
    private static final int FULL_N = FULL_X * FULL_Y * FULL_Z;   // 98304
    private static final int MIN_Y = -64;

    // ---- aquifer cell-grid geometry for Y in [-64, 320) (3 x 35 x 3 = 315 cells). ----
    private static final int GRID_X = 3, GRID_Y = 35, GRID_Z = 3;
    private static final int CELL_N = GRID_X * GRID_Y * GRID_Z;   // 315
    private static final int MIN_GRID_Y = -7;
    private static final int SEA_LEVEL = 63, LAVA_LEVEL = -54, DEFAULT_FLUID_ID = 2; // 2 = water

    /** Arbitrary fixed world-seed halves for the positional vein RNG (any value works). */
    private static final long SEED_LO = 0x5C_DEC1DEBEA7L;
    private static final long SEED_HI = 0x9E3779B97F4A7C15L;

    /** Untimed warm-up iterations before the timed loop (JIT/clock/cache settle). */
    private static final int WARMUP = 3;

    private DecideBench() {
    }

    /**
     * One-shot entry point, called from {@code SuperChunk.initGpuBackend()} (the
     * ServerAboutToStart hook, exactly where {@code GpuCompileBench.run()} lives).
     * No-op unless {@code -Dsuperchunk.gpu.decideBench=N} with N &gt; 0.
     */
    public static void run() {
        int iters = Integer.getInteger("superchunk.gpu.decideBench", 0);
        if (iters <= 0) {
            return;
        }
        if (!OpenCLBackend.isAvailable()) {
            LOGGER.info("[SuperChunk-GPU] [decide-bench] requested but backend unavailable — skipped.");
            return;
        }
        try {
            if (!OpenCLBackend.device().fp64()) {
                LOGGER.info("[SuperChunk-GPU] [decide-bench] device lacks cl_khr_fp64 — aq_decide (vanilla double) "
                        + "cannot build; bench skipped.");
                return;
            }
            String source = loadSources();
            if (source == null) {
                return;
            }
            Synth synth = generate();
            LOGGER.info("[SuperChunk-GPU] [decide-bench] ===== batched decide-kernel duty bench on {}: K={} chunks/batch, "
                            + "M={} grids ({} corner reals/chunk), {}x{}x{}={} blocks/chunk, {} work-items/batch, "
                            + "id readback {} KB/batch ({} KB/chunk), {} timed iters (+{} warm-up), ONE in-order queue =====",
                    OpenCLBackend.device().name(), K, M, M * CORNER_N, FULL_X, FULL_Y, FULL_Z, FULL_N,
                    (long) K * FULL_N, (K * FULL_N) / 1024, FULL_N / 1024, iters, WARMUP);

            double[] fp64 = runLeg(source, false, synth, iters);
            double[] fp32 = runLeg(source, true, synth, iters);

            if (fp64 != null) {
                double perChunk = fp64[0] + fp64[1];
                LOGGER.info("[SuperChunk-GPU] [decide-bench] fp64 GO-GATE (<= 1.0 ms/chunk kernel+readback at K={}): {} "
                                + "({} ms/chunk = kernel {} + readback {}).",
                        K, perChunk <= 1.0 ? "GO" : "NO-GO",
                        fmt(perChunk), fmt(fp64[0]), fmt(fp64[1]));
            }
            if (fp64 != null && fp32 != null && fp32[0] > 0.0) {
                LOGGER.info("[SuperChunk-GPU] [decide-bench] fp32 vs fp64 kernel delta: fp64={} ms/chunk, fp32={} ms/chunk "
                                + "({}x) — NOTE the fp32 leg keeps aq_decide in fp64 (aquifer.cl is double-typed), so this "
                                + "bounds only the interp/tail share of the fp32 lever.",
                        fmt(fp64[0]), fmt(fp32[0]),
                        String.format("%.2f", fp32[0] > 0.0 ? fp64[0] / fp32[0] : 0.0));
            }
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [decide-bench] threw — bench aborted (production unaffected).", t);
        }
    }

    /**
     * Runs one precision leg: build, upload once, warm-up, N timed iterations of
     * (async NDRange -> finish [kernel time from profiling event] -> pinned mapRead
     * [readback wall-time] -> histogram on the last iter -> unmap). Returns
     * {@code {kernelMsPerChunk, readbackMsPerChunk}} or {@code null} if the leg
     * could not run (logged).
     */
    private static double[] runLeg(String source, boolean fp32, Synth s, int iters) {
        String leg = fp32 ? "fp32" : "fp64";
        CLProgram program = CLProgram.build(source, fp32 ? "-DUSE_FP32" : "");
        if (program == null) {
            LOGGER.warn("[SuperChunk-GPU] [decide-bench] {} program build FAILED — leg skipped.", leg);
            return null;
        }
        long queue = NULL, kernel = NULL;
        long cornersMem = NULL, originsMem = NULL, locMem = NULL, flMem = NULL, ftMem = NULL, idsMem = NULL;
        final long idsBytes = (long) K * FULL_N;
        try {
            queue = program.createQueue(true);   // profiling ON: kernel START->END device time
            kernel = program.kernel(KERNEL_NAME);

            cornersMem = program.uploadReals(fp32, s.corners);
            originsMem = program.uploadInts(s.origins);
            locMem = uploadLongs(program, s.locCache);
            flMem = program.uploadInts(s.fluidLevel);
            ftMem = program.uploadInts(s.fluidTypeId);
            idsMem = program.createHostOutputBuffer(idsBytes);   // pinned (ALLOC_HOST_PTR) id buffer

            int total = K * FULL_N;
            int a = 0;
            program.setArgPointer(kernel, a++, cornersMem);
            program.setArgInt(kernel, a++, M);
            program.setArgInt(kernel, a++, CORNER_N);
            program.setArgInt(kernel, a++, DIM_X);
            program.setArgInt(kernel, a++, DIM_Y);
            program.setArgInt(kernel, a++, DIM_Z);
            program.setArgInt(kernel, a++, SX);
            program.setArgInt(kernel, a++, SY);
            program.setArgInt(kernel, a++, SZ);
            program.setArgInt(kernel, a++, FULL_X);
            program.setArgInt(kernel, a++, FULL_Y);
            program.setArgInt(kernel, a++, FULL_Z);
            program.setArgInt(kernel, a++, FULL_N);
            program.setArgPointer(kernel, a++, originsMem);
            program.setArgPointer(kernel, a++, locMem);
            program.setArgPointer(kernel, a++, flMem);
            program.setArgPointer(kernel, a++, ftMem);
            program.setArgInt(kernel, a++, CELL_N);
            program.setArgInt(kernel, a++, MIN_GRID_Y);
            program.setArgInt(kernel, a++, GRID_X);
            program.setArgInt(kernel, a++, GRID_Z);
            program.setArgInt(kernel, a++, SEA_LEVEL);
            program.setArgInt(kernel, a++, LAVA_LEVEL);
            program.setArgInt(kernel, a++, DEFAULT_FLUID_ID);
            program.setArgLong(kernel, a++, SEED_LO);
            program.setArgLong(kernel, a++, SEED_HI);
            program.setArgInt(kernel, a++, total);
            program.setArgPointer(kernel, a++, idsMem);

            long gws = CLProgram.roundUpGlobal(total);

            // ---- warm-up (untimed; same dispatch + map/unmap shape as the timed loop).
            for (int w = 0; w < WARMUP; w++) {
                program.enqueue1DAsync(queue, kernel, gws, null);
                long addr = program.mapRead(queue, idsMem, idsBytes);
                if (addr != NULL) {
                    program.unmap(queue, idsMem, addr, idsBytes);
                }
            }
            program.finish(queue);

            // ---- timed loop.
            long kSum = 0L, kMin = Long.MAX_VALUE, kMax = 0L;
            int kProfiled = 0;
            long dSum = 0L;                     // enqueue+finish wall (fallback if profiling absent)
            long rSum = 0L, rMin = Long.MAX_VALUE, rMax = 0L;
            long[] hist = new long[16];
            long flagBits = 0L;
            for (int it = 0; it < iters; it++) {
                long[] ev = new long[1];
                try {
                    long d0 = System.nanoTime();
                    program.enqueue1DAsync(queue, kernel, gws, ev);
                    // Finish so the profiling event holds pure GPU compute time AND the
                    // pinned map below measures the id transfer in isolation (mirrors
                    // maybeRunFullFieldBench).
                    program.finish(queue);
                    dSum += System.nanoTime() - d0;
                    long[] prof = CLProgram.eventProfile(ev[0]);
                    if (prof != null) {
                        long kNs = prof[1];
                        kSum += kNs;
                        kProfiled++;
                        if (kNs < kMin) kMin = kNs;
                        if (kNs > kMax) kMax = kNs;
                    }

                    long r0 = System.nanoTime();
                    long addr = program.mapRead(queue, idsMem, idsBytes);  // pinned-map readback (the duty's transfer leg)
                    long rNs = System.nanoTime() - r0;
                    rSum += rNs;
                    if (rNs < rMin) rMin = rNs;
                    if (rNs > rMax) rMax = rNs;
                    if (addr != NULL) {
                        if (it == iters - 1) {
                            ByteBuffer ids = MemoryUtil.memByteBuffer(addr, (int) idsBytes);
                            for (int p = 0; p < (int) idsBytes; p++) {
                                int b = ids.get(p) & 0xFF;
                                hist[b & 0x0F]++;
                                if ((b & 0x80) != 0) flagBits++;
                            }
                        }
                        program.unmap(queue, idsMem, addr, idsBytes);
                    }
                } finally {
                    CLProgram.releaseEvent(ev[0]);
                }
            }

            boolean profiled = kProfiled > 0;
            double kMeanBatchMs = profiled ? kSum / 1e6 / kProfiled : dSum / 1e6 / iters;
            double kMinMs = profiled ? kMin / 1e6 : 0.0;
            double kMaxMs = profiled ? kMax / 1e6 : 0.0;
            double rMeanBatchMs = rSum / 1e6 / iters;
            double kPerChunk = kMeanBatchMs / K;
            double rPerChunk = rMeanBatchMs / K;
            LOGGER.info("[SuperChunk-GPU] [decide-bench] {} RESULT: K={} M={} iters={}: kernel mean={} ms/batch "
                            + "({} ms/chunk; min {} / max {} ms/batch{}), readback(pinned map {} KB) mean={} ms/batch "
                            + "({} ms/chunk; min {} / max {} ms/batch); TOTAL {} ms/chunk.",
                    leg, K, M, iters,
                    fmt(kMeanBatchMs), fmt(kPerChunk), fmt(kMinMs), fmt(kMaxMs),
                    profiled ? "" : "; NO profiling events — kernel time is enqueue+finish WALL",
                    idsBytes / 1024, fmt(rMeanBatchMs), fmt(rPerChunk),
                    fmt(rMin == Long.MAX_VALUE ? 0.0 : rMin / 1e6), fmt(rMax / 1e6),
                    fmt(kPerChunk + rPerChunk));
            logHistogram(leg, hist, flagBits, idsBytes);
            return new double[]{kPerChunk, rPerChunk};
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [decide-bench] {} leg failed — skipped.", leg, t);
            return null;
        } finally {
            CLProgram.releaseMem(cornersMem);
            CLProgram.releaseMem(originsMem);
            CLProgram.releaseMem(locMem);
            CLProgram.releaseMem(flMem);
            CLProgram.releaseMem(ftMem);
            CLProgram.releaseMem(idsMem);
            CLProgram.releaseKernel(kernel);
            CLProgram.releaseQueue(queue);
            program.close();
        }
    }

    /** Sanity: the synthetic decide output must be non-degenerate (several id classes present). */
    private static void logHistogram(String leg, long[] hist, long flagBits, long totalBytes) {
        long air = hist[0], stone = hist[1], water = hist[2], lava = hist[3];
        long veins = hist[4] + hist[5] + hist[6] + hist[7] + hist[8] + hist[9];
        int distinct = 0;
        StringBuilder classes = new StringBuilder();
        for (int idx = 0; idx <= 9; idx++) {
            double share = hist[idx] * 100.0 / totalBytes;
            if (share >= 0.5) {
                distinct++;
                if (classes.length() > 0) classes.append(", ");
                classes.append("id").append(idx).append('=').append(String.format("%.1f%%", share));
            }
        }
        LOGGER.info("[SuperChunk-GPU] [decide-bench] {} id histogram (last iter, {} blocks): air={} stone={} water={} "
                        + "lava={} veins[4..9]={} ({}/{}/{}/{}/{}/{}), tie-flag bit7={}; classes>=0.5%: [{}].",
                leg, totalBytes, air, stone, water, lava, veins,
                hist[4], hist[5], hist[6], hist[7], hist[8], hist[9], flagBits, classes);
        if (distinct < 3) {
            LOGGER.warn("[SuperChunk-GPU] [decide-bench] {} id histogram is DEGENERATE ({} classes >= 0.5%) — the duty "
                    + "number is suspect (branches not exercised); check the synthetic inputs.", leg, distinct);
        }
    }

    // ----------------------------------------------------------------------
    // Synthetic inputs (uploaded once; deterministic).
    // ----------------------------------------------------------------------

    /** Host-side synthetic input set, shared by both precision legs. */
    private static final class Synth {
        final double[] corners;     // K*M*CORNER_N, df_batch_lattice_multichunk layout
        final int[] origins;        // K*3 block origins
        final long[] locCache;      // K*CELL_N packed BlockPos
        final int[] fluidLevel;     // K*CELL_N
        final int[] fluidTypeId;    // K*CELL_N

        Synth(double[] corners, int[] origins, long[] locCache, int[] fluidLevel, int[] fluidTypeId) {
            this.corners = corners;
            this.origins = origins;
            this.locCache = locCache;
            this.fluidLevel = fluidLevel;
            this.fluidTypeId = fluidTypeId;
        }
    }

    /**
     * Generates plausible synthetic data: grid 0 = density-like with a vertical
     * gradient (solid deep, air high, a mixed boundary band around sea level so BOTH
     * the vein and aquifer branches fire at realistic rates); grid 1 = mostly-positive
     * "noodle"; grid 2 = small "barrier/sel"; grids 3/4 = vein/gap; grids 5..M-1 =
     * filler (present only to mirror the production corner-buffer footprint).
     * FluidStatus cells + jittered location caches follow the exact vanilla 315-cell
     * per-chunk geometry the kernel's aq_decide indexes into.
     */
    private static Synth generate() {
        double[] corners = new double[K * M * CORNER_N];
        for (int c = 0; c < K; c++) {
            for (int g = 0; g < M; g++) {
                int base = (c * M + g) * CORNER_N;
                for (int iy = 0; iy < DIM_Y; iy++) {
                    int blockY = MIN_Y + iy * SY;
                    for (int ix = 0; ix < DIM_X; ix++) {
                        for (int iz = 0; iz < DIM_Z; iz++) {
                            int flat = (iy * DIM_X + ix) * DIM_Z + iz;
                            double h = hashSigned(((long) base + flat) * 0x100000001B3L + 0x5EEDL);
                            double v;
                            switch (g) {
                                case 0 -> {   // density core: vertical gradient + noise
                                    double grad = (64.0 - blockY) / 96.0;
                                    if (grad > 1.5) grad = 1.5;
                                    if (grad < -1.5) grad = -1.5;
                                    v = grad + 0.45 * h;
                                }
                                case 1 -> v = 0.3 + 0.5 * h;      // noodle: mostly positive, sometimes carves
                                case 2 -> v = 0.3 * h;            // barrier / rangeChoice driver: small
                                default -> v = h;                 // veininess / gap / filler grids
                            }
                            corners[base + flat] = v;
                        }
                    }
                }
            }
        }

        int[] origins = new int[K * 3];
        long[] locCache = new long[K * CELL_N];
        int[] fluidLevel = new int[K * CELL_N];
        int[] fluidTypeId = new int[K * CELL_N];
        for (int c = 0; c < K; c++) {
            int ox = c * 16, oy = MIN_Y, oz = 0;
            origins[c * 3] = ox;
            origins[c * 3 + 1] = oy;
            origins[c * 3 + 2] = oz;
            int minGridX = (ox - 5) >> 4;
            int minGridZ = (oz - 5) >> 4;
            Random rnd = new Random(0xDEC1DEL * 31 + c);
            for (int jy = 0; jy < GRID_Y; jy++) {           // aq_getIndex layout: (jy*gridSizeZ + kz)*gridSizeX + ix
                int gy = MIN_GRID_Y + jy;
                for (int kz = 0; kz < GRID_Z; kz++) {
                    int gz = minGridZ + kz;
                    for (int ix = 0; ix < GRID_X; ix++) {
                        int gx = minGridX + ix;
                        int idx = c * CELL_N + (jy * GRID_Z + kz) * GRID_X + ix;
                        // Vanilla location-cache jitter: (gx*16 + rand(10), gy*12 + rand(9), gz*16 + rand(10)).
                        int x = gx * 16 + rnd.nextInt(10);
                        int y = gy * 12 + rnd.nextInt(9);
                        int z = gz * 16 + rnd.nextInt(10);
                        locCache[idx] = packBlockPos(x, y, z);
                        // Plausible FluidStatus: ~1/4 local aquifer near the cell's Y, else global-ish sea level;
                        // deep cells occasionally lava.
                        int cellY = gy * 12;
                        int fl = (rnd.nextInt(4) == 0) ? cellY + rnd.nextInt(24) - 6 : SEA_LEVEL;
                        int ft = (fl < LAVA_LEVEL + 8 && rnd.nextInt(3) == 0) ? 3 : 2;
                        fluidLevel[idx] = fl;
                        fluidTypeId[idx] = ft;
                    }
                }
            }
        }
        return new Synth(corners, origins, locCache, fluidLevel, fluidTypeId);
    }

    /** splitmix64-style hash -> [-1, 1]. */
    private static double hashSigned(long key) {
        long z = key * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= (z >>> 31);
        return ((z >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    /** BlockPos.asLong (X_OFFSET=38, Z_OFFSET=12, 26/12/26-bit fields) — matches rng.cl's mc_bp_asLong. */
    private static long packBlockPos(int x, int y, int z) {
        return ((x & 0x3FFFFFFL) << 38) | ((z & 0x3FFFFFFL) << 12) | (y & 0xFFFL);
    }


    private static long uploadLongs(CLProgram program, long[] data) {
        int len = Math.max(1, data.length);
        LongBuffer buf = MemoryUtil.memAllocLong(len);
        try {
            if (data.length == 0) {
                buf.put(0L);
            } else {
                buf.put(data);
            }
            buf.flip();
            return program.createInputBuffer(buf);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    private static String fmt(double ms) {
        return String.format("%.3f", ms);
    }

    /**
     * Loads + concatenates the kernel sources in the proven order (mirrors
     * AquiferGpuVerify.maybeInit + buildBenchProgram): hoisted fp64 enable + rng.cl
     * (integer-only) + noise.cl (real/REAL_C/mc_lerp3 + FP_CONTRACT OFF) + aquifer.cl
     * (aq_decide) + decide_bench.cl. Returns null on failure (logged).
     */
    private static String loadSources() {
        String rng = CLProgram.loadKernelSource(RNG_CL);
        String noise = CLProgram.loadKernelSource(NOISE_CL);
        String aq = CLProgram.loadKernelSource(AQUIFER_CL);
        String decide = CLProgram.loadKernelSource(DECIDE_CL);
        if (rng == null || noise == null || aq == null || decide == null) {
            LOGGER.warn("[SuperChunk-GPU] [decide-bench] could not load kernel resources — bench disabled.");
            return null;
        }
        String src = "#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n"
                + "#pragma OPENCL FP_CONTRACT OFF\n"
                + rng + "\n" + noise + "\n" + aq + "\n" + decide;
        if (Boolean.getBoolean("superchunk.gpu.dumpKernel")) {
            LOGGER.info("[SuperChunk-GPU] === decide-bench kernel (synthetic decide, diagnostic only) ===\n{}", decide);
        }
        return src;
    }
}
