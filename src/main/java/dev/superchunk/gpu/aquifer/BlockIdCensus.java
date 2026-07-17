package dev.superchunk.gpu.aquifer;

import dev.superchunk.gpu.CLProgram;
import dev.superchunk.gpu.OpenCLBackend;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAccumulator;

/**
 * STAGE-3 <b>self-consistent block-flip CENSUS</b> ({@code -Dsuperchunk.gpu.blockIdVerify},
 * default OFF — everything verify-only, vanilla output SHIPS unchanged).
 *
 * <p><b>What is measured.</b> Per noise-stage block, the GPU recomputes the block decision
 * ({@code aquifer_census} in aquifer.cl: density&gt;0 &rarr; solid; else the mode-A-proven
 * {@code aq_decide} algebra + the vanilla {@code shouldScheduleFluidUpdate} bit) and the
 * result is compared against what vanilla ACTUALLY SHIPPED for that block this run
 * (captured in-process at the {@code MaterialRuleList.calculate} /
 * {@code computeSubstance} seams). A full class-complete transition matrix over
 * {stone, air, water, lava, 6 vein states, beard-sentinel, other} is kept — the round-4
 * stone-deleted false-pass lesson: enumerate EVERY class.
 *
 * <p><b>mode-C (self-consistent) density — the construction and its proof leg.</b> The
 * census requires the GPU decision to consume the GPU's OWN finalDensity (corner grids +
 * the same interpolation the production decide kernel will use), NOT an independent CPU
 * density. This build obtains it via the EXISTING full-field kernels
 * ({@code fullfield.cl}, the "or the existing full-field kernel" option of the plan):
 * enabling this flag force-enables {@link dev.superchunk.gpu.dfc.OnDeviceInterp} VERIFY
 * mode, so every interpolated leaf value the CPU fill consumes is bit-compared per block
 * against the GPU full-field value ({@code df_full_field_interp[_lerp3]} over the SAME
 * device-resident corner grids). At fp64 the two are bit-identical (proven; BUGS
 * counters gate it LIVE in the same run), and the finalDensity tail
 * (mul/squeeze/min/rangeChoice — plain non-fused IEEE doubles, identical op stream on
 * either host) preserves the equality, so the density captured at the
 * {@code computeSubstance} seam and fed to the census dispatch IS the GPU's own
 * finalDensity bit-for-bit — <i>gated</i>, not assumed: any OnDeviceInterp VERIFY BUG
 * &gt; 0 invalidates the equality and is reported as a HARD-ZERO failure here. Chunks
 * without a live GPU full field are counted separately ({@code chunksFieldNone}) — for
 * those the fed density is only the shipped-pipeline density and the mode-C proof leg is
 * absent.
 *
 * <p><b>Veins ARE censused (Phase A — the carve-out is removed).</b> The vein DF
 * per-block values (veinToggle always; veinRidged/veinGap exactly as lazily as vanilla
 * computes them) are captured at the OreVeinifier seam ({@code ScVeinCaptureDf}
 * wrappers installed by {@code MixinNoiseChunkVeinCensus} — pure pass-through, shipped
 * values untouched), and the census kernel runs the SHARED production vein path
 * ({@code aq_vein_decide} in aquifer.cl: the exact function the compact-ids decide
 * kernel calls, including the bit-exact positional-Xoroshiro draws recomputed
 * IN-KERNEL from the ore seed) for every aquifer-null block. The captured vein values
 * are the GPU's own by the same OnDeviceInterp value-path proof leg that covers the
 * density (the vein grids are fused members; their per-block interpolated reads are
 * bit-compared live), so vein_id-vs-vein_id mismatches are HARD failures: with
 * bit-identical inputs and a bit-exact RNG, ANY vein-class disagreement is a port bug,
 * never drift. A 3-way vein oracle ({@link AquiferRef#veinDecide}) splits kernel/driver
 * faults from host capture bugs, and capture misses (the ref needed a value vanilla
 * computed but the seam did not capture) are their own HARD-ZERO counter. Chunks
 * without a vein oracle (non-Xoroshiro ore RNG) fall back to the old carve-out and are
 * counted separately ({@code veinOracleAbsent}) — never silently.
 *
 * <p><b>Beardifier:</b> blocks inside the chunk's beard AABB (the proven-exact beardSkip
 * bounds) get the beard-sentinel class and are EXCLUDED from flip accounting (the real
 * architecture routes them to the CPU anyway); counted separately.
 *
 * <p><b>3-way oracle:</b> every censused block also runs {@link AquiferRef#decideFull}
 * (the exact Java mirror of the kernel) on the SAME uploaded arrays, splitting
 * GPU!=ref (kernel/driver corruption — the known per-thread fp64 artifact class) from
 * ref!=CPU (host data-capture bug) so neither is ever misread as a genuine flip.
 *
 * <p>All log lines are greppable as {@code [blockid-census]}.
 */
public final class BlockIdCensus {

    public static final boolean ENABLED = Boolean.getBoolean("superchunk.gpu.blockIdVerify");
    private static final Logger LOG = LoggerFactory.getLogger("SuperChunk-BlockIdCensus");

    /** Per-dispatch batch (a full chunk is ~98304 blocks = ~6 flushes). */
    private static final int BATCH = 16384;

    // ---- class ids (CPU axis; GPU FINAL axis uses 0..9 — aquifer 0..3 + veins 4..9) ----
    public static final int CLS_STONE = 0;    // computeSubstance null -> default block
    public static final int CLS_AIR = 1;
    public static final int CLS_WATER = 2;
    public static final int CLS_LAVA = 3;
    public static final int CLS_COPPER_ORE = 4;
    public static final int CLS_RAW_COPPER = 5;
    public static final int CLS_GRANITE = 6;
    public static final int CLS_DEEPSLATE_IRON = 7;
    public static final int CLS_RAW_IRON = 8;
    public static final int CLS_TUFF = 9;
    public static final int CLS_BEARD = 10;   // inside beard AABB (sentinel, excluded)
    public static final int CLS_OTHER = 11;   // modded/unknown state (excluded)
    private static final int CLASSES = 12;
    private static final String[] CLS_NAME = {
            "stone", "air", "water", "lava", "copper_ore", "raw_copper", "granite",
            "deepslate_iron_ore", "raw_iron", "tuff", "beard-sentinel", "other"};

    private static volatile boolean ready = false;
    private static CLProgram program;
    private static final java.util.concurrent.locks.ReentrantReadWriteLock lifecycleLock =
            new java.util.concurrent.locks.ReentrantReadWriteLock();
    private static final ConcurrentHashMap<Long, PerThread> THREADS = new ConcurrentHashMap<>();

    // ---- per-thread arm/finalize handshake with MaterialRuleList.calculate ----
    private static final class Arm {
        boolean armed;
        int x, y, z;
    }

    private static final ThreadLocal<Arm> ARM = ThreadLocal.withInitial(Arm::new);

    // ---- per-thread beard AABB of the chunk currently being filled ----
    private static final class BeardBox {
        boolean hasBeard;
        int minX, minY, minZ, maxX, maxY, maxZ;
    }

    private static final ThreadLocal<BeardBox> BEARD = ThreadLocal.withInitial(BeardBox::new);

    // ---- live per-thread accumulators (flushed on chunk switch / shutdown) ----
    private static final ConcurrentHashMap<Long, Accumulator> ACTIVE = new ConcurrentHashMap<>();

    // =====================================================================
    // Global accounting (all thread-safe; enumerate EVERY class — round-4 lesson).
    // =====================================================================
    private static final AtomicLong covered = new AtomicLong();          // blocks GPU-censused
    private static final AtomicLong beardSentinel = new AtomicLong();    // inside beard AABB (excluded)
    private static final AtomicLong uncoveredOther = new AtomicLong();   // modded/unknown CPU state (excluded)
    private static final AtomicLong unfinalized = new AtomicLong();      // recorded but never finalized (excluded)
    // Vein-class blocks in chunks WITHOUT a vein oracle (non-Xoroshiro ore RNG):
    // excluded from the matrix/flips like beard, counted here — never silent.
    private static final AtomicLong veinOracleAbsent = new AtomicLong();

    // trans[cpuClass][gpuFinal 0..9] — the FULL post-vein id on the GPU axis
    private static final int GPU_CLASSES = 10;
    private static final AtomicLong[][] trans = new AtomicLong[CLASSES][GPU_CLASSES];
    private static final AtomicLong flips = new AtomicLong();
    private static final AtomicLong flipsLava = new AtomicLong();        // HARD ZERO
    private static final AtomicLong flipsAboveY106 = new AtomicLong();   // HARD ZERO
    private static final AtomicLong flipsVeinClass = new AtomicLong();   // HARD ZERO (vein-censused vein_id mismatch)
    private static final AtomicLong flipsMarginTiny = new AtomicLong();  // margin <= 1e-7 (aquifer-boundary flips)
    private static final AtomicLong flipsMarginSmall = new AtomicLong(); // 1e-7 < margin <= 1e-6
    private static final AtomicLong flipsMarginBig = new AtomicLong();   // margin > 1e-6  (HARD ZERO)
    private static final LongAccumulator flipMaxY = new LongAccumulator(Long::max, Long.MIN_VALUE);

    // ---- vein census accounting (Phase A) ----
    private static final AtomicLong veinCensused = new AtomicLong();     // aquifer-null blocks whose vein step was censused
    private static final AtomicLong veinBlocksCpu = new AtomicLong();    // shipped vein-class blocks censused
    private static final AtomicLong veinCaptureMiss = new AtomicLong();  // HARD ZERO: ref needed a value vanilla computed, seam did not capture it
    private static final AtomicLong bugsVeinGpuVsRef = new AtomicLong(); // HARD ZERO: kernel vein id != Java vein ref
    private static final AtomicLong bugsVeinRefVsCpu = new AtomicLong(); // HARD ZERO: Java vein ref != shipped final class (capture/host bug)

    // tie susceptibility over ALL covered density<=0 blocks (margin <= eps)
    private static final double[] EPS = {1e-9, 1e-7, 1e-6};
    private static final AtomicLong[] marginCount = new AtomicLong[EPS.length];
    private static final AtomicLong densLE0 = new AtomicLong();

    // fluid-tick bit compares (HARD: raw mismatch on unflipped)
    private static final AtomicLong schedRawMismatchUnflipped = new AtomicLong();
    private static final AtomicLong schedEffMismatchUnflipped = new AtomicLong();
    private static final AtomicLong schedCpuTrue = new AtomicLong();
    private static final AtomicLong schedGpuTrue = new AtomicLong();

    // 3-way oracle split (HARD ZERO both)
    private static final AtomicLong bugsGpuVsRef = new AtomicLong();     // kernel/driver corruption class
    private static final AtomicLong bugsRefVsCpu = new AtomicLong();     // host data-capture bug
    private static final AtomicLong bugsSchedGpuVsRef = new AtomicLong();
    private static final AtomicLong bugsSchedRefVsCpu = new AtomicLong();

    // per-chunk flip histogram (buckets 0..1022, 1023 = overflow) + chunk counters
    private static final AtomicLongArray chunkFlipHist = new AtomicLongArray(1024);
    private static final LongAccumulator chunkFlipMax = new LongAccumulator(Long::max, 0L);
    private static final AtomicLong chunksCensused = new AtomicLong();
    private static final AtomicLong chunksFieldFull = new AtomicLong();  // value+lerp3 GPU field live (mode-C proof leg present)
    private static final AtomicLong chunksFieldPartial = new AtomicLong();
    private static final AtomicLong chunksFieldNone = new AtomicLong();  // no GPU field (proof leg absent)
    private static final AtomicLong chunksFieldFp32 = new AtomicLong();  // fp32 field (proof leg is fp32-widened; flagged)

    private static final AtomicLong flipLogBudget = new AtomicLong(16);
    private static final AtomicLong nextLogAt = new AtomicLong(5_000_000L);

    static {
        for (int c = 0; c < CLASSES; c++) {
            for (int g = 0; g < GPU_CLASSES; g++) {
                trans[c][g] = new AtomicLong();
            }
        }
        for (int e = 0; e < EPS.length; e++) {
            marginCount[e] = new AtomicLong();
        }
    }

    private BlockIdCensus() {
    }

    // =====================================================================
    // Lifecycle.
    // =====================================================================

    /** Builds the census kernel at boot (no-op unless ENABLED + fp64). */
    public static void maybeInit() {
        if (!ENABLED) {
            return;
        }
        if (!OpenCLBackend.isAvailable()) {
            LOG.warn("[blockid-census] enabled but GPU backend unavailable — census will not run.");
            return;
        }
        if (!OpenCLBackend.device().fp64()) {
            LOG.warn("[blockid-census] enabled but device lacks fp64 — the aquifer decision is vanilla-double; census SKIPPED.");
            return;
        }
        try {
            String rng = load("/superchunk/kernels/rng.cl");
            String aq = load("/superchunk/kernels/aquifer.cl");
            if (rng == null || aq == null) {
                LOG.error("[blockid-census] could not load rng.cl/aquifer.cl — census disabled.");
                return;
            }
            String src = "#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n"
                    + "#pragma OPENCL FP_CONTRACT OFF\n" + rng + "\n" + aq;
            program = CLProgram.build(src);
            if (program == null) {
                LOG.error("[blockid-census] aquifer.cl build failed — census disabled.");
                return;
            }
            ready = true;
            LOG.info("[blockid-census] ENABLED on {} — Stage-3 self-consistent block-flip census armed. "
                            + "Vanilla output SHIPS unchanged; GPU decisions (aquifer_census kernel) are compared "
                            + "per block against the shipped result. mode-C density proof leg: OnDeviceInterp VERIFY "
                            + "(forced on by this flag) must report BUGS=0. Veins: CENSUSED (Phase A — the shared "
                            + "production aq_vein_decide runs per aquifer-null block on the seam-captured vein DF "
                            + "values + in-kernel Xoroshiro; any vein_id mismatch is a HARD failure).",
                    OpenCLBackend.device().name());
        } catch (Throwable t) {
            LOG.error("[blockid-census] init threw — census disabled.", t);
            ready = false;
        }
    }

    public static boolean isReady() {
        return ENABLED && ready;
    }

    // =====================================================================
    // Hooks (called from the mixins). All are cheap no-ops when the flag is off
    // because the callers guard on the static-final ENABLED.
    // =====================================================================

    /**
     * Called by {@code MaterialRuleList.calculate} BEFORE the filler chain runs: arms this
     * thread for exactly one {@code computeSubstance} recording at {@code (x,y,z)}. Only
     * armed calls are recorded, which structurally excludes carver-stage
     * {@code computeSubstance} reuse (carvers do not go through MaterialRuleList).
     * Returns whether the census is live (so the caller knows to finalize).
     */
    public static boolean armBlock(int x, int y, int z) {
        if (!isReady()) {
            return false;
        }
        Arm a = ARM.get();
        a.armed = true;
        a.x = x;
        a.y = y;
        a.z = z;
        return true;
    }

    /** Consumes the arm if it matches {@code (x,y,z)} (one recording per armed block). */
    public static boolean consumeArm(int x, int y, int z) {
        Arm a = ARM.get();
        if (a.armed && a.x == x && a.y == y && a.z == z) {
            a.armed = false;
            return true;
        }
        return false;
    }

    /**
     * Called by {@code MaterialRuleList.calculate} AFTER the filler chain: attributes the
     * final shipped result (null &rarr; default stone; vein states resolved here) to the
     * pending census entry for {@code (x,y,z)} on this thread, if any.
     */
    public static void finalizeCpuBlock(int x, int y, int z, BlockState finalState) {
        Accumulator acc = ACTIVE.get(Thread.currentThread().threadId());
        if (acc != null) {
            acc.finalizePending(x, y, z, classify(finalState));
        }
    }

    // ---- vein-capture hooks (Phase A; called from ScVeinCaptureDf, the pass-through
    // wrappers MixinNoiseChunkVeinCensus installs around the OreVeinifier's DFs). The
    // veinifier filler runs INSIDE MaterialRuleList.calculate right after the aquifer
    // filler recorded the block, so the pending census entry is still live. ----
    public static final int VEIN_TOGGLE = 0;
    public static final int VEIN_RIDGED = 1;
    public static final int VEIN_GAP = 2;

    /** Attributes one CPU-computed vein DF value to this thread's pending census entry. */
    public static void recordVein(int kind, int x, int y, int z, double value,
                                  long oreSeedLo, long oreSeedHi, boolean seedOk) {
        Accumulator acc = ACTIVE.get(Thread.currentThread().threadId());
        if (acc != null) {
            acc.recordVeinValue(kind, x, y, z, value, oreSeedLo, oreSeedHi, seedOk);
        }
    }

    /**
     * Called from the Beardifier compute seam: publishes the CURRENT chunk's beard AABB
     * (the proven-exact beardSkip bounds) for this worker thread. Blocks inside it get
     * the beard-sentinel class.
     */
    public static void noteBeardBounds(boolean hasBeard, int minX, int minY, int minZ,
                                       int maxX, int maxY, int maxZ) {
        BeardBox b = BEARD.get();
        b.hasBeard = hasBeard;
        b.minX = minX;
        b.minY = minY;
        b.minZ = minZ;
        b.maxX = maxX;
        b.maxY = maxY;
        b.maxZ = maxZ;
    }

    private static boolean insideBeard(int x, int y, int z) {
        BeardBox b = BEARD.get();
        return b.hasBeard
                && x >= b.minX && x <= b.maxX
                && y >= b.minY && y <= b.maxY
                && z >= b.minZ && z <= b.maxZ;
    }

    /** Maps the shipped MaterialRuleList result to a census class. */
    private static int classify(BlockState s) {
        if (s == null) return CLS_STONE;
        if (s.isAir()) return CLS_AIR;
        if (s.is(Blocks.WATER)) return CLS_WATER;
        if (s.is(Blocks.LAVA)) return CLS_LAVA;
        if (s.is(Blocks.COPPER_ORE)) return CLS_COPPER_ORE;
        if (s.is(Blocks.RAW_COPPER_BLOCK)) return CLS_RAW_COPPER;
        if (s.is(Blocks.GRANITE)) return CLS_GRANITE;
        if (s.is(Blocks.DEEPSLATE_IRON_ORE)) return CLS_DEEPSLATE_IRON;
        if (s.is(Blocks.RAW_IRON_BLOCK)) return CLS_RAW_IRON;
        if (s.is(Blocks.TUFF)) return CLS_TUFF;
        return CLS_OTHER;
    }

    // =====================================================================
    // Per-chunk accumulator (created by MixinAquiferSamplerImpl).
    // =====================================================================
    public static final class Accumulator {
        // per-instance grid + CPU-built per-cell FluidStatus (the plan's mode-A upload contract)
        private final long[] locCache;
        private final int[] cellFluidLevel;
        private final int[] cellFluidTypeId;
        private final int minGridX, minGridY, minGridZ, gridSizeX, gridSizeZ;
        private final int seaLevel, lavaLevel, defaultFluidId;
        private final double flowingUpdateSimilarity;
        /** 0 = no GPU field, 1 = value-only, 2 = value+lerp3, 3 = fp32 field. */
        private final int fieldState;

        // per-block batch
        private final int[] bx = new int[BATCH];
        private final int[] by = new int[BATCH];
        private final int[] bz = new int[BATCH];
        private final double[] dens = new double[BATCH];
        private final double[] barr = new double[BATCH];
        private final byte[] cpuAq = new byte[BATCH];     // 0..3 aquifer-level class; -1 uncovered
        private final byte[] cpuFinal = new byte[BATCH];  // final shipped class; -1 unfinalized
        private final byte[] cpuSched = new byte[BATCH];
        private final byte[] beard = new byte[BATCH];
        // vein census (Phase A): CPU-captured per-block vein DF values + capture mask
        // (bit0 toggle, bit1 ridged, bit2 gap — exactly the values vanilla computed).
        private final double[] veinT = new double[BATCH];
        private final double[] veinR = new double[BATCH];
        private final double[] veinG = new double[BATCH];
        private final int[] veinMask = new int[BATCH];
        // ore positional seed (per dimension; stashed from the first vein record)
        private long oreSeedLo, oreSeedHi;
        private boolean oreSeedOk;
        private int count = 0;
        private int pendingIdx = -1;

        // reusable readback
        private final int[] outId = new int[BATCH];       // aquifer-level 0..3 (pre-vein)
        private final int[] outFinal = new int[BATCH];    // final 0..9 (post-vein)
        private final double[] outMargin = new double[BATCH];
        private final int[] outSched = new int[BATCH];

        // per-chunk tallies (recorded into the histogram at finishChunk)
        private long chunkCensusable = 0;
        private long chunkFlips = 0;

        public Accumulator(long[] locCache, int[] cellFluidLevel, int[] cellFluidTypeId,
                           int minGridX, int minGridY, int minGridZ, int gridSizeX, int gridSizeZ,
                           int seaLevel, int lavaLevel, int defaultFluidId,
                           double flowingUpdateSimilarity, int fieldState) {
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
            this.flowingUpdateSimilarity = flowingUpdateSimilarity;
            this.fieldState = fieldState;
        }

        /**
         * Records one armed noise-stage block. {@code aqClass} is the aquifer-level result
         * (0=null,1=air,2=water,3=lava,-1=other), {@code sched} the vanilla
         * shouldScheduleFluidUpdate after the FULL path, {@code density} the fed mode-C
         * density (see class javadoc), {@code barrier} the memoized barrierNoise value
         * (only meaningful for density&le;0; pass 0 for solids).
         */
        public void add(int i, int j, int k, double density, double barrier, int aqClass, boolean sched) {
            long tid = Thread.currentThread().threadId();
            Accumulator prev = ACTIVE.get(tid);
            if (prev != this) {
                if (prev != null) {
                    prev.finishChunk();
                }
                ACTIVE.put(tid, this);
            }
            if (count == BATCH) {
                flush();   // flush BEFORE recording so the pending entry always stays in-batch
            }
            int idx = count++;
            bx[idx] = i;
            by[idx] = j;
            bz[idx] = k;
            dens[idx] = density;
            barr[idx] = barrier;
            cpuAq[idx] = (byte) aqClass;
            cpuFinal[idx] = (byte) -1;
            cpuSched[idx] = (byte) (sched ? 1 : 0);
            beard[idx] = (byte) (insideBeard(i, j, k) ? 1 : 0);
            veinT[idx] = Double.NaN;
            veinR[idx] = Double.NaN;
            veinG[idx] = Double.NaN;
            veinMask[idx] = 0;
            pendingIdx = idx;
        }

        /**
         * Attributes one CPU vein DF value (position-checked, same pattern as
         * {@link #finalizePending}) to the pending entry. The add()-time flush
         * guarantee ("the pending entry always stays in-batch") covers these
         * records too: the veinifier runs inside the same MaterialRuleList call.
         */
        void recordVeinValue(int kind, int x, int y, int z, double value,
                             long seedLo, long seedHi, boolean seedOk) {
            if (!this.oreSeedOk && seedOk) {
                this.oreSeedLo = seedLo;
                this.oreSeedHi = seedHi;
                this.oreSeedOk = true;
            }
            int idx = pendingIdx;
            if (idx < 0 || idx >= count) {
                return;
            }
            if (bx[idx] != x || by[idx] != y || bz[idx] != z) {
                return;   // foreign compute (column sampler etc.) — not our block
            }
            switch (kind) {
                case VEIN_TOGGLE -> { veinT[idx] = value; veinMask[idx] |= 1; }
                case VEIN_RIDGED -> { veinR[idx] = value; veinMask[idx] |= 2; }
                case VEIN_GAP -> { veinG[idx] = value; veinMask[idx] |= 4; }
                default -> { }
            }
        }

        /** Attributes the final shipped class to the pending entry (position-checked). */
        void finalizePending(int x, int y, int z, int cls) {
            int idx = pendingIdx;
            if (idx < 0 || idx >= count) {
                return;
            }
            if (bx[idx] != x || by[idx] != y || bz[idx] != z) {
                return;   // foreign MaterialRuleList (column sampler etc.) — not our block
            }
            cpuFinal[idx] = (byte) cls;
            pendingIdx = -1;
        }

        /**
         * Ends this accumulator's chunk accounting: flushes the residual batch and records
         * the per-chunk flip count. Only chunks with a real noise-stage fill footprint
         * (&ge; 4096 censusable blocks) enter the per-chunk histogram, so stray revisits
         * cannot dilute the p50/p99.
         */
        public void finishChunk() {
            flush();
            if (chunkCensusable >= 4096) {
                chunksCensused.incrementAndGet();
                int b = (int) Math.min(chunkFlips, 1023L);
                chunkFlipHist.incrementAndGet(b);
                chunkFlipMax.accumulate(chunkFlips);
                switch (fieldState) {
                    case 2 -> chunksFieldFull.incrementAndGet();
                    case 1 -> chunksFieldPartial.incrementAndGet();
                    case 3 -> chunksFieldFp32.incrementAndGet();
                    default -> chunksFieldNone.incrementAndGet();
                }
            }
            chunkCensusable = 0;
            chunkFlips = 0;
        }

        /** Dispatches the accumulated batch to the GPU and runs the full accounting. */
        public void flush() {
            int n = count;
            count = 0;
            pendingIdx = -1;
            if (n == 0 || !ready) {
                return;
            }
            try {
                dispatch(n, this);
            } catch (Throwable t) {
                LOG.warn("[blockid-census] GPU dispatch threw (batch dropped).", t);
                return;
            }
            boolean veinsOn = oreSeedOk;   // matches the veinsOn kernel arg of this dispatch
            for (int b = 0; b < n; b++) {
                if (beard[b] != 0) {
                    beardSentinel.incrementAndGet();
                    continue;   // beard-sentinel: CPU-decided in the real architecture
                }
                int cf = cpuFinal[b];
                if (cf < 0) {
                    unfinalized.incrementAndGet();
                    continue;
                }
                if (cf == CLS_OTHER || cpuAq[b] < 0) {
                    uncoveredOther.incrementAndGet();
                    continue;
                }
                boolean cpuVeinClass = cf >= CLS_COPPER_ORE && cf <= CLS_TUFF;
                if (!veinsOn && cpuVeinClass) {
                    // No vein oracle (non-Xoroshiro ore RNG): the old carve-out, but
                    // EXCLUDED from the matrix/flips like beard — counted, never silent.
                    veinOracleAbsent.incrementAndGet();
                    continue;
                }
                covered.incrementAndGet();
                chunkCensusable++;

                int gpuId = outId[b];          // aquifer-level decision (pre-vein)
                int gpuFin = outFinal[b];      // FINAL id after the vein step (0..9)
                boolean gpuSched = outSched[b] != 0;
                double margin = outMargin[b];
                double density = dens[b];
                int y = by[b];

                if (density <= 0.0) {
                    densLE0.incrementAndGet();
                    for (int e = 0; e < EPS.length; e++) {
                        if (margin <= EPS[e]) {
                            marginCount[e].incrementAndGet();
                        }
                    }
                }

                // ---- 3-way AQUIFER oracle: exact Java mirror on the SAME arrays. ----
                AquiferRef.Full ref = AquiferRef.decideFull(bx[b], y, bz[b], density, barr[b],
                        locCache, cellFluidLevel, cellFluidTypeId,
                        minGridX, minGridY, minGridZ, gridSizeX, gridSizeZ,
                        seaLevel, lavaLevel, defaultFluidId, flowingUpdateSimilarity);
                if (gpuId != ref.id) {
                    long nr = bugsGpuVsRef.incrementAndGet();
                    if (nr <= 8) {
                        LOG.error("[blockid-census]   KERNEL/DRIVER FAULT @({},{},{}): GPU id={} != Java-ref id={} "
                                        + "(density={}) — corruption class, NOT a flip.",
                                bx[b], y, bz[b], clsName(gpuId), clsName(ref.id), density);
                    }
                }
                if (gpuSched != ref.sched) {
                    bugsSchedGpuVsRef.incrementAndGet();
                }
                int cpuAqCls = cpuAq[b];
                if (ref.id != cpuAqCls) {
                    long nd = bugsRefVsCpu.incrementAndGet();
                    if (nd <= 8) {
                        LOG.error("[blockid-census]   DATA-CAPTURE BUG @({},{},{}): Java-ref id={} != CPU aquifer id={} "
                                        + "— uploaded arrays differ from what the CPU used.",
                                bx[b], y, bz[b], clsName(ref.id), clsName(cpuAqCls));
                    }
                }
                if (ref.sched != (cpuSched[b] != 0)) {
                    bugsSchedRefVsCpu.incrementAndGet();
                }

                // ---- 3-way VEIN oracle (Phase A): the Java mirror of aq_vein_decide
                // on the SAME captured values + ore seed. With bit-identical inputs
                // (OnDeviceInterp value-path proof) and a bit-exact RNG, any vein
                // disagreement is a port/capture bug — HARD, never drift. ----
                int mask = veinMask[b];
                if (veinsOn && ref.id == 0) {
                    if ((mask & 1) != 0) {
                        AquiferRef.Vein rv = AquiferRef.veinDecide(bx[b], y, bz[b],
                                veinT[b], veinR[b], veinG[b], oreSeedLo, oreSeedHi);
                        veinCensused.incrementAndGet();
                        if (cpuVeinClass) {
                            veinBlocksCpu.incrementAndGet();
                        }
                        boolean miss = (rv.usedRidged && (mask & 2) == 0) || (rv.usedGap && (mask & 4) == 0);
                        if (miss) {
                            long nm = veinCaptureMiss.incrementAndGet();
                            if (nm <= 8) {
                                LOG.error("[blockid-census]   VEIN CAPTURE MISS @({},{},{}): ref needed ridged/gap "
                                                + "the seam did not capture (mask={}) — oracle broken for this block.",
                                        bx[b], y, bz[b], mask);
                            }
                        } else {
                            if (gpuFin != rv.id) {
                                long nv = bugsVeinGpuVsRef.incrementAndGet();
                                if (nv <= 8) {
                                    LOG.error("[blockid-census]   VEIN KERNEL FAULT @({},{},{}): GPU final={} != "
                                                    + "Java vein-ref={} (vt={} vrid={} vgap={}) — port/driver bug, NOT drift.",
                                            bx[b], y, bz[b], clsName(gpuFin), clsName(rv.id),
                                            veinT[b], veinR[b], veinG[b]);
                                }
                            }
                            if (cpuAqCls == 0 && rv.id != cf) {
                                long nc = bugsVeinRefVsCpu.incrementAndGet();
                                if (nc <= 8) {
                                    LOG.error("[blockid-census]   VEIN DATA-CAPTURE BUG @({},{},{}): Java vein-ref={} != "
                                                    + "shipped={} on identical inputs — captured vein values/seed differ "
                                                    + "from what the CPU used.",
                                            bx[b], y, bz[b], clsName(rv.id), CLS_NAME[cf]);
                                }
                            }
                        }
                    } else if (cpuAqCls == 0) {
                        // The CPU veinifier ALWAYS computes veinToggle for aquifer-null
                        // blocks in vein-enabled dims — a missing toggle is a seam bug.
                        long nm = veinCaptureMiss.incrementAndGet();
                        if (nm <= 8) {
                            LOG.error("[blockid-census]   VEIN CAPTURE MISS @({},{},{}): no veinToggle captured for an "
                                            + "aquifer-null block (mask=0).", bx[b], y, bz[b]);
                        }
                    }
                }

                // ---- transition matrix + flips: EXACT class equality over the FULL
                // 0..9 alphabet (the veins-not-censused carve-out is REMOVED; the only
                // excluded vein blocks are the counted no-oracle ones above). ----
                if (gpuFin >= 0 && gpuFin < GPU_CLASSES) {
                    trans[cf][gpuFin].incrementAndGet();
                }
                boolean flip = cf != gpuFin;
                boolean veinInvolved = cpuVeinClass || (gpuFin >= CLS_COPPER_ORE && gpuFin <= CLS_TUFF);

                boolean cpuSchedRaw = cpuSched[b] != 0;
                if (cpuSchedRaw) schedCpuTrue.incrementAndGet();
                if (gpuSched) schedGpuTrue.incrementAndGet();

                if (flip) {
                    flips.incrementAndGet();
                    chunkFlips++;
                    flipMaxY.accumulate(y);
                    if (cf == CLS_LAVA || gpuFin == 3) {
                        flipsLava.incrementAndGet();
                    }
                    if (y > 106) {
                        flipsAboveY106.incrementAndGet();
                    }
                    if (veinInvolved) {
                        // vein-class mismatch: HARD (bit-exact inputs + RNG — a port
                        // bug, not a tie flip); kept out of the aquifer-margin buckets.
                        flipsVeinClass.incrementAndGet();
                    } else if (margin <= 1e-7) {
                        flipsMarginTiny.incrementAndGet();
                    } else if (margin <= 1e-6) {
                        flipsMarginSmall.incrementAndGet();
                    } else {
                        flipsMarginBig.incrementAndGet();
                    }
                    long budget = flipLogBudget.decrementAndGet();
                    if (budget >= 0) {
                        LOG.warn("[blockid-census]   FLIP @({},{},{}): shipped={} gpu={} (aq={}) density={} margin={} "
                                        + "sched cpu={} gpu={} (fieldState={})",
                                bx[b], y, bz[b], CLS_NAME[cf], clsName(gpuFin), clsName(gpuId), density, margin,
                                cpuSchedRaw, gpuSched, fieldState);
                    }
                } else {
                    // fluid-tick bit compares on UNFLIPPED blocks (HARD gate)
                    if (gpuSched != cpuSchedRaw) {
                        schedRawMismatchUnflipped.incrementAndGet();
                    }
                    boolean cpuEff = cpuSchedRaw && (cf == CLS_WATER || cf == CLS_LAVA);
                    boolean gpuEff = gpuSched && (gpuFin == 2 || gpuFin == 3);
                    if (cpuEff != gpuEff) {
                        schedEffMismatchUnflipped.incrementAndGet();
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

    // =====================================================================
    // Per-thread GPU resources (AquiferGpuVerify pattern: private queue + kernel
    // + persistent buffers per worker thread; verify-mode perf is irrelevant).
    // =====================================================================
    private static final class PerThread {
        final long queue;
        final long kernel;
        long dBx, dBy, dBz, dDe, dBa, dLoc, dFl, dFt;
        long dVt, dVr, dVg, dMask;
        long dOutId, dOutFin, dOutMa, dOutSc;
        int cellCap = 0;
        IntBuffer sInt = MemoryUtil.memAllocInt(BATCH);
        final DoubleBuffer sDbl = MemoryUtil.memAllocDouble(BATCH);
        LongBuffer sLong = MemoryUtil.memAllocLong(BATCH);

        PerThread() {
            try {
                this.queue = program.createQueue();
                this.kernel = program.kernel("aquifer_census");
                dBx = program.createReusableInputBuffer((long) BATCH * Integer.BYTES);
                dBy = program.createReusableInputBuffer((long) BATCH * Integer.BYTES);
                dBz = program.createReusableInputBuffer((long) BATCH * Integer.BYTES);
                dDe = program.createReusableInputBuffer((long) BATCH * Double.BYTES);
                dBa = program.createReusableInputBuffer((long) BATCH * Double.BYTES);
                dVt = program.createReusableInputBuffer((long) BATCH * Double.BYTES);
                dVr = program.createReusableInputBuffer((long) BATCH * Double.BYTES);
                dVg = program.createReusableInputBuffer((long) BATCH * Double.BYTES);
                dMask = program.createReusableInputBuffer((long) BATCH * Integer.BYTES);
                dOutId = program.createOutputBuffer((long) BATCH * Integer.BYTES);
                dOutFin = program.createOutputBuffer((long) BATCH * Integer.BYTES);
                dOutMa = program.createOutputBuffer((long) BATCH * Double.BYTES);
                dOutSc = program.createOutputBuffer((long) BATCH * Integer.BYTES);
            } catch (Throwable t) {
                release();
                throw t;
            }
        }

        void ensureCells(int cellsNeeded) {
            if (cellsNeeded <= cellCap) return;
            int cap = Math.max(cellsNeeded, Math.max(cellCap * 2, 1024));
            CLProgram.releaseMem(dLoc); dLoc = 0;
            CLProgram.releaseMem(dFl); dFl = 0;
            CLProgram.releaseMem(dFt); dFt = 0;
            cellCap = 0;
            dLoc = program.createReusableInputBuffer((long) cap * Long.BYTES);
            dFl = program.createReusableInputBuffer((long) cap * Integer.BYTES);
            dFt = program.createReusableInputBuffer((long) cap * Integer.BYTES);
            cellCap = cap;
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

        void run(int n, Accumulator a) {
            int cells = a.locCache.length;
            ensureCells(cells);
            uploadInt(dBx, a.bx, n);
            uploadInt(dBy, a.by, n);
            uploadInt(dBz, a.bz, n);
            uploadDouble(dDe, a.dens, n);
            uploadDouble(dBa, a.barr, n);
            uploadDouble(dVt, a.veinT, n);
            uploadDouble(dVr, a.veinR, n);
            uploadDouble(dVg, a.veinG, n);
            uploadInt(dMask, a.veinMask, n);
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
            program.setArgDouble(kernel, 16, a.flowingUpdateSimilarity);
            program.setArgPointer(kernel, 17, dVt);
            program.setArgPointer(kernel, 18, dVr);
            program.setArgPointer(kernel, 19, dVg);
            program.setArgPointer(kernel, 20, dMask);
            program.setArgLong(kernel, 21, a.oreSeedLo);
            program.setArgLong(kernel, 22, a.oreSeedHi);
            program.setArgInt(kernel, 23, a.oreSeedOk ? 1 : 0);
            program.setArgPointer(kernel, 24, dOutId);
            program.setArgPointer(kernel, 25, dOutFin);
            program.setArgPointer(kernel, 26, dOutMa);
            program.setArgPointer(kernel, 27, dOutSc);
            program.setArgInt(kernel, 28, n);

            program.enqueue1D(queue, kernel, n);

            downloadInt(dOutId, a.outId, n);
            downloadInt(dOutFin, a.outFinal, n);
            downloadDouble(dOutMa, a.outMargin, n);
            downloadInt(dOutSc, a.outSched, n);
        }

        void release() {
            for (long m : new long[]{dBx, dBy, dBz, dDe, dBa, dLoc, dFl, dFt,
                    dVt, dVr, dVg, dMask, dOutId, dOutFin, dOutMa, dOutSc}) {
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
            ready = false;
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

    /** Flushes every live per-thread accumulator (call before reporting at shutdown). */
    public static void flushAll() {
        for (Accumulator a : ACTIVE.values()) {
            try {
                a.finishChunk();
            } catch (Throwable ignored) {
            }
        }
        ACTIVE.clear();
    }

    // =====================================================================
    // Report.
    // =====================================================================

    public static void report(String reason) {
        if (!ENABLED) {
            return;
        }
        if (!ready) {
            LOG.info("[blockid-census] ({}) census did not run (kernel not built).", reason);
            return;
        }
        long cov = covered.get();
        long fl = flips.get();
        long chunks = chunksCensused.get();

        // transition matrix (nonzero DISAGREEING pairs; veins censused, so agreement
        // is EXACT class equality over the full 0..9 alphabet — any stone->vein,
        // vein->stone or vein_id->vein_id pair shows here and is HARD)
        StringBuilder tb = new StringBuilder();
        for (int c = 0; c < CLASSES; c++) {
            for (int g = 0; g < GPU_CLASSES; g++) {
                long v = trans[c][g].get();
                if (v == 0) {
                    continue;
                }
                if (c != g) {
                    tb.append(' ').append(CLS_NAME[c]).append("->").append(clsName(g)).append('=').append(v);
                }
            }
        }

        // per-chunk percentiles from the histogram
        long p50 = percentile(50), p99 = percentile(99);
        long pMax = chunkFlipMax.get();

        // HARD-ZERO verdicts
        long hMarginBig = flipsMarginBig.get();
        long hLava = flipsLava.get();
        long hY = flipsAboveY106.get();
        long hSched = schedRawMismatchUnflipped.get();
        long hGpuRef = bugsGpuVsRef.get() + bugsSchedGpuVsRef.get();
        long hRefCpu = bugsRefVsCpu.get() + bugsSchedRefVsCpu.get();
        long hVeinFlips = flipsVeinClass.get();
        long hVeinMiss = veinCaptureMiss.get();
        long hVeinGpuRef = bugsVeinGpuVsRef.get();
        long hVeinRefCpu = bugsVeinRefVsCpu.get();
        long odiBugs = dev.superchunk.gpu.dfc.OnDeviceInterp.verifyBugs();
        long odiCmp = dev.superchunk.gpu.dfc.OnDeviceInterp.verifyComparisons();
        double odiMax = dev.superchunk.gpu.dfc.OnDeviceInterp.verifyMaxAbsErr();
        boolean hardPass = hMarginBig == 0 && hLava == 0 && hY == 0 && hSched == 0
                && hGpuRef == 0 && hRefCpu == 0 && odiBugs == 0
                && hVeinFlips == 0 && hVeinMiss == 0 && hVeinGpuRef == 0 && hVeinRefCpu == 0;

        long fieldNone = chunksFieldNone.get();
        long fieldFp32 = chunksFieldFp32.get();
        String proofLeg = odiCmp == 0
                ? "ABSENT (OnDeviceInterp made 0 comparisons — no GPU full field ran; the fed density is only the "
                + "shipped-pipeline value, mode-C equality UNPROVEN. Run with the GPU batch/fusion path enabled.)"
                : String.format("comparisons=%d BUGS=%d maxAbsErr=%s%s", odiCmp, odiBugs, fmtD(odiMax),
                odiBugs == 0 ? " (density equality PROVEN bit-exact on every served leaf)"
                        : " (EQUALITY BROKEN — census densities are NOT provably the GPU's own)");

        LOG.info("[blockid-census] ({}) ================= STAGE-3 SELF-CONSISTENT BLOCK-FLIP CENSUS =================\n"
                        + "  coverage: censused blocks={} | chunks(censused)={} [GPU-field: full={} valueOnly={} fp32={} NONE={}]\n"
                        + "            beard-sentinel(excluded)={} | other/modded(excluded)={} | unfinalized(excluded)={}\n"
                        + "  mode-C density proof leg (OnDeviceInterp VERIFY): {}\n"
                        + "  FLIPS (GPU decision vs shipped, EXACT 0..9 class equality): total={} ({} per million) | by class-pair:{}\n"
                        + "  flips/chunk: p50={} p99={} max={} (SOFT envelope: p50<=~5, p99<=~50) | flipMaxY={}\n"
                        + "  flip margins (aquifer flips): <=1e-7:{} 1e-7..1e-6:{} >1e-6:{} | vein-class flips:{} (HARD) | Y-split: Y<=106:{} Y>106:{}\n"
                        + "  tie susceptibility (density<=0 blocks={}): margin<=1e-9:{} <=1e-7:{} <=1e-6:{}\n"
                        + "  veins: CENSUSED (aq_vein_decide on seam-captured values + in-kernel Xoroshiro) — vein-step blocks={} "
                        + "(shipped vein-class={}) | capture misses={} (HARD) | no-oracle vein blocks excluded={}\n"
                        + "  fluid-tick bit: raw mismatches on UNFLIPPED={} | effective(placed-fluid) mismatches on UNFLIPPED={} | schedTrue cpu={} gpu={}\n"
                        + "  fault split (3-way oracle): GPU!=Java-ref id/sched={}/{} (kernel/driver corruption class) | Java-ref!=CPU id/sched={}/{} (data-capture) | "
                        + "vein GPU!=ref={} | vein ref!=shipped={}\n"
                        + "  HARD-ZERO gates: margin>1e-6 flips={} | LAVA-involved flips={} | flips at Y>106={} | vein-class flips={} | "
                        + "vein capture misses={} | vein GPU-vs-ref={} | vein ref-vs-CPU={} | "
                        + "fluid-bit raw mismatch on unflipped={} | GPU-vs-ref faults={} | ref-vs-CPU faults={} | density-equality BUGS={}\n"
                        + "  VERDICT: {}",
                reason,
                cov, chunks, chunksFieldFull.get(), chunksFieldPartial.get(), fieldFp32, fieldNone,
                beardSentinel.get(), uncoveredOther.get(), unfinalized.get(),
                proofLeg,
                fl, cov == 0 ? "0" : String.format("%.2f", 1.0e6 * fl / cov),
                tb.length() == 0 ? " (none)" : tb.toString(),
                chunks == 0 ? "n/a" : String.valueOf(p50), chunks == 0 ? "n/a" : String.valueOf(p99), pMax,
                flipMaxY.get() == Long.MIN_VALUE ? "none" : String.valueOf(flipMaxY.get()),
                flipsMarginTiny.get(), flipsMarginSmall.get(), hMarginBig, hVeinFlips,
                fl - hY, hY,
                densLE0.get(), marginCount[0].get(), marginCount[1].get(), marginCount[2].get(),
                veinCensused.get(), veinBlocksCpu.get(), hVeinMiss, veinOracleAbsent.get(),
                hSched, schedEffMismatchUnflipped.get(), schedCpuTrue.get(), schedGpuTrue.get(),
                bugsGpuVsRef.get(), bugsSchedGpuVsRef.get(), bugsRefVsCpu.get(), bugsSchedRefVsCpu.get(),
                hVeinGpuRef, hVeinRefCpu,
                hMarginBig, hLava, hY, hVeinFlips, hVeinMiss, hVeinGpuRef, hVeinRefCpu,
                hSched, hGpuRef, hRefCpu, odiBugs,
                hardPass
                        ? (fl == 0 ? "HARD gates PASS, 0 flips — GPU-decided ids (incl. veins) match shipped output exactly."
                        : "HARD gates PASS — remaining flips are the accepted-drift class; judge the SOFT numbers above.")
                        : "HARD-ZERO FAILURE — at least one hard gate is nonzero; see the gate line. Do NOT read flips as drift.");
    }

    /** p-th percentile of flips/chunk from the bounded histogram (1023 = overflow bucket). */
    private static long percentile(int p) {
        long total = 0;
        for (int i = 0; i < 1024; i++) {
            total += chunkFlipHist.get(i);
        }
        if (total == 0) {
            return 0;
        }
        long target = (total * p + 99) / 100;
        long acc = 0;
        for (int i = 0; i < 1024; i++) {
            acc += chunkFlipHist.get(i);
            if (acc >= target) {
                return i;
            }
        }
        return 1023;
    }

    private static String clsName(int id) {
        return (id >= 0 && id < CLASSES) ? CLS_NAME[id] : ("?" + id);
    }

    private static String fmtD(double v) {
        return String.format("%.3e", v);
    }

    private static String load(String resource) {
        try (InputStream in = BlockIdCensus.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
