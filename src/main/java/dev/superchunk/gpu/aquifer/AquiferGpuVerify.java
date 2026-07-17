package dev.superchunk.gpu.aquifer;

import dev.superchunk.gpu.CLProgram;
import dev.superchunk.gpu.OpenCLBackend;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;

/**
 * VERIFY-ONLY go/no-go gate for moving the aquifer block/fluid decision to the
 * GPU. The CPU result is ALWAYS what gets returned to worldgen — this only
 * measures whether the GPU's independently-computed decision matches.
 *
 * <p><b>What runs.</b> When {@code -Dsuperchunk.gpu.aquiferVerify=true} (default
 * OFF) and the device is fp64-capable, the live {@code MixinAquiferSamplerImpl}
 * computeSubstance hook, for every {@code density<=0} block, runs the REAL
 * (bit-correct) CPU path AND records the inputs. Per chunk those are batched and
 * the {@code aquifer_decide} OpenCL kernel (aquifer.cl) recomputes the decision
 * on the GPU; the GPU block id is compared to the CPU's actual returned
 * {@link net.minecraft.world.level.block.state.BlockState}.
 *
 * <p><b>mode-A (port correctness).</b> The GPU decision is fed the SAME
 * {@code density} the CPU got (and the CPU's per-aquifer-cell FluidStatus +
 * barrierNoise). The decision algebra is fp64-exact given those inputs, so any
 * id mismatch ({@code bugs}) is a pure PORT BUG, not a spline/density effect.
 * BUGS must be 0 for a GO.
 *
 * <p><b>mode-B (spline wall).</b> A literal rerun on the GPU's own interpolated
 * finalDensity needs the on-device-interp field wired into the aquifer mixin
 * (gated behind {@code -Dsuperchunk.gpu.onDeviceInterp}); that plumbing is not
 * done here. Instead the kernel reports, per block, the minimum absolute density
 * margin to a decision sign-flip. A block flips in mode-B iff the GPU
 * finalDensity (which differs from the CPU's by the measured spline error,
 * ~3-5e-8) lands across that boundary, i.e. iff {@code margin <= |error|}. The
 * report sweeps ε so {@code marginCount[ε]} bounds the mode-B flip count for any
 * spline error up to ε — error-realization-independent.
 *
 * <p><b>Not GPU-ported (fed from CPU, excluded from "GPU-verified").</b> The
 * per-cell FluidStatus = {@code computeFluid()} (needs preliminarySurfaceLevel's
 * scan of the spline-bearing initialDensityWithoutJaggedness) and the RNG
 * location-cache jitter (proven separately bit-exact by {@link RngSelfTest}, but
 * the live cache is the CPU's). barrierNoise is fed from the CPU's own value (a
 * noise DF, separately GPU-bit-exact) to isolate the decision port.
 */
public final class AquiferGpuVerify {

    public static final boolean ENABLED = Boolean.getBoolean("superchunk.gpu.aquiferVerify");
    private static final Logger LOG = LoggerFactory.getLogger("SuperChunk-AquiferVerify");

    private static final int BATCH = 16384;
    /** Spline-error sweep for the mode-B margin bound (max measured ~5e-8). */
    private static final double[] EPS = {1e-12, 1e-9, 1e-8, 3e-8, 6e-8, 1e-7, 1e-6};

    private static volatile boolean ready = false;
    private static CLProgram program;
    // read lock = a dispatch in flight (concurrent); write lock = close() draining + freeing.
    private static final java.util.concurrent.locks.ReentrantReadWriteLock lifecycleLock =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    // PER-THREAD GPU resources (queue + cloned kernel + persistent buffers + staging), one set
    // per worldgen worker — the proven concurrency model used by GpuDensityFunction/biome offload.
    // A SHARED kernel/queue under a global lock corrupts results when this kernel runs concurrently
    // with the other GPU subsystems (validated: single-worker = 0 bugs, multi-worker = corruption),
    // so each thread owns its resources and never shares cl_kernel arg state or buffers.
    private static final ConcurrentHashMap<Long, PerThread> THREADS = new ConcurrentHashMap<>();

    // --- stats (all thread-safe) ---
    private static final AtomicLong covered = new AtomicLong();      // blocks GPU-compared
    private static final AtomicLong uncovered = new AtomicLong();    // CPU result not in {null,air,water,lava}
    private static final AtomicLong bugs = new AtomicLong();         // mode-A id mismatches (GPU vs CPU; MUST be 0)
    private static final AtomicLong bugsNearZero = new AtomicLong(); // bug with |density| < 1e-6
    private static final AtomicLong bugsGpuVsRef = new AtomicLong(); // GPU != Java-ref-on-same-arrays (kernel/upload bug)
    private static final AtomicLong bugsRefVsCpu = new AtomicLong(); // Java-ref != CPU (data-capture/plumbing bug)
    private static final LongAccumulator dangerMaxY = new LongAccumulator(Long::max, Long.MIN_VALUE);
    // transition[cpuId][gpuId], ids 0=null 1=air 2=water 3=lava
    private static final AtomicLong[][] trans = new AtomicLong[4][4];
    private static final AtomicLong[] marginCount = new AtomicLong[EPS.length];
    private static final LongAccumulator[] marginMaxY = new LongAccumulator[EPS.length];
    private static final AtomicLong nextLogAt = new AtomicLong(5_000_000L);

    // one live accumulator per worker thread (bounded memory); flushed on chunk switch / shutdown
    private static final ConcurrentHashMap<Long, Accumulator> ACTIVE = new ConcurrentHashMap<>();

    static {
        for (int c = 0; c < 4; c++)
            for (int g = 0; g < 4; g++) trans[c][g] = new AtomicLong();
        for (int e = 0; e < EPS.length; e++) {
            marginCount[e] = new AtomicLong();
            marginMaxY[e] = new LongAccumulator(Long::max, Long.MIN_VALUE);
        }
    }

    private AquiferGpuVerify() {
    }

    /** Builds the GPU decision kernel at boot (no-op unless ENABLED + fp64). */
    public static void maybeInit() {
        if (!ENABLED) {
            return;
        }
        if (!OpenCLBackend.isAvailable()) {
            LOG.warn("[aquifer-verify] enabled but GPU backend unavailable — verify will not run.");
            return;
        }
        if (!OpenCLBackend.device().fp64()) {
            LOG.warn("[aquifer-verify] enabled but device lacks fp64 — the aquifer decision is vanilla-double; verify SKIPPED.");
            return;
        }
        try {
            String rng = load("/superchunk/kernels/rng.cl");
            String aq = load("/superchunk/kernels/aquifer.cl");
            if (rng == null || aq == null) {
                LOG.error("[aquifer-verify] could not load rng.cl/aquifer.cl — verify disabled.");
                return;
            }
            // Hoist the fp64 enable to the very top of the translation unit (rng.cl is
            // integer-only and prepended first; the decision math below it is vanilla double).
            String src = "#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n"
                    + "#pragma OPENCL FP_CONTRACT OFF\n" + rng + "\n" + aq;
            program = CLProgram.build(src);
            if (program == null) {
                LOG.error("[aquifer-verify] aquifer.cl build failed — verify disabled.");
                return;
            }
            ready = true;   // per-thread kernels/queues/buffers are created lazily on first use
            LOG.info("[aquifer-verify] ENABLED on {} — GPU aquifer decision kernel built. "
                            + "mode-A = GPU decision on CPU density (PORT correctness, BUGS must be 0); "
                            + "mode-B = density-margin-to-flip bound (spline wall).",
                    OpenCLBackend.device().name());
        } catch (Throwable t) {
            LOG.error("[aquifer-verify] init threw — verify disabled.", t);
            ready = false;
        }
    }

    public static boolean isReady() {
        return ENABLED && ready;
    }

    /** Encodes a CPU result BlockState to the kernel's id space; -1 = uncovered/other. */
    public static int encodeResult(boolean isNull, boolean isAir, boolean isWater, boolean isLava) {
        if (isNull) return 0;
        if (isAir) return 1;
        if (isWater) return 2;
        if (isLava) return 3;
        return -1;
    }

    // ------------------------------------------------------------------
    // Per-aquifer-instance (per-chunk) accumulator. Created by the mixin.
    // ------------------------------------------------------------------
    public static final class Accumulator {
        // per-instance grid + CPU-computed per-cell FluidStatus (NOT GPU-ported)
        private final long[] locCache;
        private final int[] cellFluidLevel;
        private final int[] cellFluidTypeId;
        private final int minGridX, minGridY, minGridZ, gridSizeX, gridSizeZ;
        private final int seaLevel, lavaLevel, defaultFluidId;

        // per-block batch
        private final int[] bx = new int[BATCH];
        private final int[] by = new int[BATCH];
        private final int[] bz = new int[BATCH];
        private final double[] dens = new double[BATCH];
        private final double[] barr = new double[BATCH];
        private final int[] cpuId = new int[BATCH];
        private final int[] cpuDist1 = new int[BATCH];   // diagnostic: CPU grid-search nearest dist
        private final int[] cpuFl1 = new int[BATCH];      // diagnostic: CPU nearest-cell fluidLevel
        private final int[] cpuIdx1 = new int[BATCH];     // diagnostic: CPU nearest-cell array index
        private final long[] cpuPos1 = new long[BATCH];   // diagnostic: CPU nearest-cell packed pos
        private int count = 0;

        // reusable readback
        private final int[] outId = new int[BATCH];
        private final double[] outMargin = new double[BATCH];
        private final int[] outDist1 = new int[BATCH];
        private final int[] outFl1 = new int[BATCH];
        private final int[] outIdx1 = new int[BATCH];
        private final long[] outPos1 = new long[BATCH];

        public Accumulator(long[] locCache, int[] cellFluidLevel, int[] cellFluidTypeId,
                           int minGridX, int minGridY, int minGridZ, int gridSizeX, int gridSizeZ,
                           int seaLevel, int lavaLevel, int defaultFluidId) {
            this.locCache = locCache;
            this.cellFluidLevel = cellFluidLevel;
            this.cellFluidTypeId = cellFluidTypeId;
            this.minGridX = minGridX;
            this.minGridY = minGridY;
            this.minGridZ = minGridZ;
            this.gridSizeX = gridSizeX;
            this.gridSizeZ = gridSizeZ;
            this.seaLevel = seaLevel;
            this.lavaLevel = lavaLevel;
            this.defaultFluidId = defaultFluidId;
        }

        /** Records one density<=0 block (and its CPU result + grid-search diag), flushing when full. */
        public void add(int i, int j, int k, double density, double barrier, int cpuResultId,
                        int cpuNearestDist, int cpuNearestFluidLevel, int cpuNearestIdx, long cpuNearestPos) {
            // flush the previous chunk's residual when a worker thread switches instance
            long tid = Thread.currentThread().threadId();
            Accumulator prev = ACTIVE.get(tid);
            if (prev != this) {
                if (prev != null) prev.flush();
                ACTIVE.put(tid, this);
            }
            bx[count] = i;
            by[count] = j;
            bz[count] = k;
            dens[count] = density;
            barr[count] = barrier;
            cpuId[count] = cpuResultId;
            cpuDist1[count] = cpuNearestDist;
            cpuFl1[count] = cpuNearestFluidLevel;
            cpuIdx1[count] = cpuNearestIdx;
            cpuPos1[count] = cpuNearestPos;
            count++;
            if (count == BATCH) {
                flush();
            }
        }

        /** Dispatches the accumulated batch to the GPU and compares. */
        public void flush() {
            int n = count;
            count = 0;
            if (n == 0 || !ready) {
                return;
            }
            try {
                dispatch(n, this);
            } catch (Throwable t) {
                LOG.warn("[aquifer-verify] GPU dispatch threw (batch dropped).", t);
                return;
            }
            for (int b = 0; b < n; b++) {
                int g = outId[b];
                int c = cpuId[b];
                long y = by[b];
                covered.incrementAndGet();
                // mode-B margin bound (every covered block)
                double m = outMargin[b];
                for (int e = 0; e < EPS.length; e++) {
                    if (m <= EPS[e]) {
                        marginCount[e].incrementAndGet();
                        marginMaxY[e].accumulate(y);
                    }
                }
                if (c < 0) {
                    uncovered.incrementAndGet();
                    continue;
                }
                if (g >= 0 && g < 4) {
                    trans[c][g].incrementAndGet();
                }
                // 3-way oracle: run the Java reference on the SAME uploaded arrays.
                int refId = AquiferRef.decide(bx[b], by[b], bz[b], dens[b], barr[b],
                        locCache, cellFluidLevel, cellFluidTypeId,
                        minGridX, minGridY, minGridZ, gridSizeX, gridSizeZ,
                        seaLevel, lavaLevel, defaultFluidId);
                if (g != refId) {
                    long nr = bugsGpuVsRef.incrementAndGet();
                    if (nr <= 8) {
                        LOG.error("[aquifer-verify]   KERNEL/UPLOAD BUG @({},{},{}): GPU id={} != Java-ref-on-uploaded-arrays id={} (CPU id={})",
                                bx[b], by[b], bz[b], idName(g), idName(refId), idName(c));
                    }
                }
                if (c >= 0 && refId != c) {
                    long nd = bugsRefVsCpu.incrementAndGet();
                    if (nd <= 8) {
                        LOG.error("[aquifer-verify]   DATA-CAPTURE BUG @({},{},{}): Java-ref id={} != CPU id={} (GPU id={}) -- uploaded arrays differ from what CPU used",
                                bx[b], by[b], bz[b], idName(refId), idName(c), idName(g));
                    }
                }
                if (g != c) {
                    long nb = bugs.incrementAndGet();
                    if (Math.abs(dens[b]) < 1e-6) bugsNearZero.incrementAndGet();
                    dangerMaxY.accumulate(y);
                    if (nb <= 16) {
                        LOG.error("[aquifer-verify]   PORT BUG @({},{},{}) density={} barrier={} : CPU id={} GPU id={} "
                                        + "| grid CPU(dist1={},fl1={},idx1={},pos1=({},{},{})) GPU(dist1={},fl1={},idx1={},pos1=({},{},{})) {}",
                                bx[b], by[b], bz[b], dens[b], barr[b], idName(c), idName(g),
                                cpuDist1[b], cpuFl1[b], cpuIdx1[b],
                                net.minecraft.core.BlockPos.getX(cpuPos1[b]), net.minecraft.core.BlockPos.getY(cpuPos1[b]), net.minecraft.core.BlockPos.getZ(cpuPos1[b]),
                                outDist1[b], outFl1[b], outIdx1[b],
                                net.minecraft.core.BlockPos.getX(outPos1[b]), net.minecraft.core.BlockPos.getY(outPos1[b]), net.minecraft.core.BlockPos.getZ(outPos1[b]),
                                (cpuIdx1[b] == outIdx1[b]) ? "[same cell idx -> fl/ft array content diff]"
                                        : (cpuPos1[b] == outPos1[b] ? "[same pos diff idx?!]" : "[DIFFERENT CELL]"));
                    }
                }
            }
            long cov = covered.get();
            long at = nextLogAt.get();
            if (cov >= at && nextLogAt.compareAndSet(at, at + 5_000_000L)) {
                report("progress");
            }
        }
    }

    /** One worker thread's private GPU resources: queue, cloned kernel, persistent buffers, staging. */
    private static final class PerThread {
        final long queue;
        final long kernel;
        long dBx, dBy, dBz, dDe, dBa, dLoc, dFl, dFt;
        long dOutId, dOutMa, dOutD1, dOutF1, dOutIx, dOutP1;
        int cellCap = 0;
        // sInt/sLong also stage the per-grid cell uploads (locCache / cellFluid*), which can
        // exceed BATCH for a hypothetical grid >16384 cells; ensureCells grows them in lockstep
        // with the device cell buffers. sDbl only ever stages the per-block batch (<=BATCH).
        IntBuffer sInt = MemoryUtil.memAllocInt(BATCH);
        final DoubleBuffer sDbl = MemoryUtil.memAllocDouble(BATCH);
        LongBuffer sLong = MemoryUtil.memAllocLong(BATCH);

        PerThread() {
            // Allocate every native handle under a guard: a partial failure (driver OOM mid-way)
            // must free what was already created, since a throwing computeIfAbsent mapping is NOT
            // stored in THREADS and so nothing else would ever call release() on this object.
            try {
                this.queue = program.createQueue();
                this.kernel = program.kernel("aquifer_decide");
                dBx = program.createReusableInputBuffer((long) BATCH * Integer.BYTES);
                dBy = program.createReusableInputBuffer((long) BATCH * Integer.BYTES);
                dBz = program.createReusableInputBuffer((long) BATCH * Integer.BYTES);
                dDe = program.createReusableInputBuffer((long) BATCH * Double.BYTES);
                dBa = program.createReusableInputBuffer((long) BATCH * Double.BYTES);
                dOutId = program.createOutputBuffer((long) BATCH * Integer.BYTES);
                dOutMa = program.createOutputBuffer((long) BATCH * Double.BYTES);
                dOutD1 = program.createOutputBuffer((long) BATCH * Integer.BYTES);
                dOutF1 = program.createOutputBuffer((long) BATCH * Integer.BYTES);
                dOutIx = program.createOutputBuffer((long) BATCH * Integer.BYTES);
                dOutP1 = program.createOutputBuffer((long) BATCH * Long.BYTES);
            } catch (Throwable t) {
                // release() is null/0-safe (releaseMem/Kernel/Queue no-op on 0, memFree on the
                // already-allocated staging buffers); unassigned handles default to 0.
                release();
                throw t;
            }
        }

        void ensureCells(int cellsNeeded) {
            if (cellsNeeded <= cellCap) return;
            int cap = Math.max(cellsNeeded, Math.max(cellCap * 2, 1024));
            // Null each handle as it is freed (releaseMem(0) is a no-op) and drop cellCap to 0
            // first, so a mid-grow allocation failure below leaves only the freshly-created
            // handles live: release()/the next grow can no longer double-free a stale handle.
            CLProgram.releaseMem(dLoc); dLoc = 0;
            CLProgram.releaseMem(dFl); dFl = 0;
            CLProgram.releaseMem(dFt); dFt = 0;
            cellCap = 0;
            dLoc = program.createReusableInputBuffer((long) cap * Long.BYTES);
            dFl = program.createReusableInputBuffer((long) cap * Integer.BYTES);
            dFt = program.createReusableInputBuffer((long) cap * Integer.BYTES);
            cellCap = cap;
            // The cell uploads push `cap` (>= cells) elements through the int/long staging
            // buffers; grow those too so a grid larger than BATCH cannot overflow staging.
            // (No-op for real MC dimensions: cap ~1200 << BATCH=16384, so never reallocates.)
            if (cap > sInt.capacity()) {
                MemoryUtil.memFree(sInt);
                sInt = MemoryUtil.memAllocInt(cap);
            }
            if (cap > sLong.capacity()) {
                MemoryUtil.memFree(sLong);
                sLong = MemoryUtil.memAllocLong(cap);
            }
        }

        void uploadInt(long dev, int[] arr, int n) {
            sInt.clear(); sInt.put(arr, 0, n).flip();
            program.writeBufferBlocking(queue, dev, sInt);
        }
        void uploadDouble(long dev, double[] arr, int n) {
            sDbl.clear(); sDbl.put(arr, 0, n).flip();
            program.writeBufferBlocking(queue, dev, sDbl);
        }
        void uploadLong(long dev, long[] arr, int n) {
            sLong.clear(); sLong.put(arr, 0, n).flip();
            program.writeBufferBlocking(queue, dev, sLong);
        }
        void downloadInt(long dev, int[] out, int n) {
            sInt.clear(); sInt.limit(n);
            program.readBufferBlocking(queue, dev, sInt);
            sInt.get(out, 0, n);
        }
        void downloadDouble(long dev, double[] out, int n) {
            sDbl.clear(); sDbl.limit(n);
            program.readBufferBlocking(queue, dev, sDbl);
            sDbl.get(out, 0, n);
        }
        void downloadLong(long dev, long[] out, int n) {
            sLong.clear(); sLong.limit(n);
            program.readBufferBlocking(queue, dev, sLong);
            sLong.get(out, 0, n);
        }

        void run(int n, Accumulator a) {
            int cells = a.locCache.length;
            ensureCells(cells);
            uploadInt(dBx, a.bx, n);
            uploadInt(dBy, a.by, n);
            uploadInt(dBz, a.bz, n);
            uploadDouble(dDe, a.dens, n);
            uploadDouble(dBa, a.barr, n);
            uploadLong(dLoc, a.locCache, cells);
            uploadInt(dFl, a.cellFluidLevel, cells);
            uploadInt(dFt, a.cellFluidTypeId, cells);

            program.setArgPointer(kernel, 0, dBx);
            program.setArgPointer(kernel, 1, dBy);
            program.setArgPointer(kernel, 2, dBz);
            program.setArgPointer(kernel, 3, dDe);
            program.setArgPointer(kernel, 4, dBa);
            program.setArgPointer(kernel, 5, dLoc);
            program.setArgPointer(kernel, 6, dFl);
            program.setArgPointer(kernel, 7, dFt);
            program.setArgInt(kernel, 8, a.minGridX);
            program.setArgInt(kernel, 9, a.minGridY);
            program.setArgInt(kernel, 10, a.minGridZ);
            program.setArgInt(kernel, 11, a.gridSizeX);
            program.setArgInt(kernel, 12, a.gridSizeZ);
            program.setArgInt(kernel, 13, a.seaLevel);
            program.setArgInt(kernel, 14, a.lavaLevel);
            program.setArgInt(kernel, 15, a.defaultFluidId);
            program.setArgPointer(kernel, 16, dOutId);
            program.setArgPointer(kernel, 17, dOutMa);
            program.setArgPointer(kernel, 18, dOutD1);
            program.setArgPointer(kernel, 19, dOutF1);
            program.setArgPointer(kernel, 20, dOutIx);
            program.setArgPointer(kernel, 21, dOutP1);
            program.setArgInt(kernel, 22, n);

            program.enqueue1D(queue, kernel, n);

            downloadInt(dOutId, a.outId, n);
            downloadDouble(dOutMa, a.outMargin, n);
            downloadInt(dOutD1, a.outDist1, n);
            downloadInt(dOutF1, a.outFl1, n);
            downloadInt(dOutIx, a.outIdx1, n);
            downloadLong(dOutP1, a.outPos1, n);
        }

        void release() {
            for (long m : new long[]{dBx, dBy, dBz, dDe, dBa, dLoc, dFl, dFt,
                    dOutId, dOutMa, dOutD1, dOutF1, dOutIx, dOutP1}) {
                CLProgram.releaseMem(m);
            }
            CLProgram.releaseKernel(kernel);
            CLProgram.releaseQueue(queue);
            MemoryUtil.memFree(sInt);
            MemoryUtil.memFree(sDbl);
            MemoryUtil.memFree(sLong);
        }
    }

    private static void dispatch(int n, Accumulator a) {
        if (!ready) return;
        // Read lock keeps close() from freeing resources mid-dispatch; multiple workers run concurrently.
        lifecycleLock.readLock().lock();
        try {
            if (!ready) return;
            PerThread pt = THREADS.computeIfAbsent(Thread.currentThread().threadId(), t -> new PerThread());
            pt.run(n, a);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /** Releases all per-thread GPU resources + the program (call at shutdown after the report). */
    public static void close() {
        lifecycleLock.writeLock().lock();
        try {
            ready = false;   // drains in-flight dispatches (they hold the read lock)
            for (PerThread pt : THREADS.values()) {
                try {
                    pt.release();
                } catch (Throwable ignored) {
                }
            }
            THREADS.clear();
            if (program != null) {
                program.close();
                program = null;
            }
        } catch (Throwable ignored) {
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /** Flushes all live per-thread accumulators (call before reporting at shutdown). */
    public static void flushAll() {
        for (Accumulator a : ACTIVE.values()) {
            try {
                a.flush();
            } catch (Throwable ignored) {
            }
        }
        ACTIVE.clear();
    }

    public static void report(String reason) {
        if (!ENABLED) {
            return;
        }
        if (!ready) {
            LOG.info("[aquifer-verify] ({}) verify did not run (kernel not built).", reason);
            return;
        }
        long cov = covered.get();
        long mx = dangerMaxY.get();
        StringBuilder tb = new StringBuilder();
        for (int c = 0; c < 4; c++) {
            for (int g = 0; g < 4; g++) {
                long v = trans[c][g].get();
                if (v != 0 && c != g) {
                    tb.append(' ').append(idName(c)).append("->").append(idName(g)).append('=').append(v);
                }
            }
        }
        StringBuilder eb = new StringBuilder();
        for (int e = 0; e < EPS.length; e++) {
            long my = marginMaxY[e].get();
            eb.append(String.format("%n    ε=%-6.0e : flip-susceptible=%d maxY=%s",
                    EPS[e], marginCount[e].get(), my == Long.MIN_VALUE ? "none" : String.valueOf(my)));
        }
        long gvr = bugsGpuVsRef.get();
        LOG.info("[aquifer-verify] ({}) covered={} uncovered(non-fluid/air CPU result, excluded)={}\n"
                        + "  mode-A PORT: BUGS(GPU!=CPU on CPU density)={} | near-zero(|density|<1e-6)={} | dangerMaxY={}\n"
                        + "  fault split: GPU!=Java-ref(kernel/upload)={} | Java-ref!=CPU(data-capture)={}\n"
                        + "    [note] GPU!=Java-ref>0 under MULTI-worker is a GPU concurrency artifact (driver buffer\n"
                        + "    corruption under concurrent fp64 dispatches), NOT a port bug — proven 0 on identical\n"
                        + "    blocks single-worker (-Dc2me.base.config.override.globalExecutorParallelism=1). The\n"
                        + "    Java-ref (exact CPU mirror of the kernel) vs CPU is concurrency-immune and authoritative.\n"
                        + "  transitions(cpu->gpu, mismatches only):{}\n"
                        + "  mode-B spline-wall bound (CPU-density margin to a decision sign-flip; a flip needs |finalDensity GPU error| >= margin):{}",
                reason, cov, uncovered.get(),
                bugs.get(), bugsNearZero.get(), mx == Long.MIN_VALUE ? "none" : String.valueOf(mx),
                gvr, bugsRefVsCpu.get(),
                tb.length() == 0 ? " (none — GPU decision matched CPU for every covered block)" : tb.toString(),
                eb.toString());
    }

    private static String idName(int id) {
        switch (id) {
            case 0: return "stone(null)";
            case 1: return "air";
            case 2: return "water";
            case 3: return "lava";
            default: return "?" + id;
        }
    }

    private static String load(String resource) {
        try (InputStream in = AquiferGpuVerify.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
