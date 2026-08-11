package dev.superchunk.gpu.dfc;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode;
import dev.superchunk.gpu.CLProgram;
import dev.superchunk.gpu.CLProgramCache;
import dev.superchunk.gpu.OpenCLBackend;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * SuperChunk GPU — <b>CROSS-CHUNK batched dispatch</b> mechanism. Computes MANY
 * chunks' density-noise grids in ONE OpenCL NDRange + ONE readback, instead of one
 * dispatch per chunk.
 *
 * <p>Sibling of {@link GpuFusedInterpolator}: where that class fuses M density
 * functions for ONE chunk into one {@code df_batch_lattice_multi} dispatch, this
 * class batches K chunks (each producing M roots) into one
 * {@code df_batch_lattice_multichunk} dispatch ({@link OpenCLAstEmitter#emitMultiChunk}).
 * All batched chunks share the SAME program (roots), the SAME strides/dims
 * (overworld chunks have identical cell-corner dims) and the SAME read-only noise
 * state; only the per-chunk ORIGIN differs, supplied through an uploaded
 * {@code origins} table (K*3 ints). The output buffer is {@code K*M*n}, where chunk
 * {@code c}'s M*n slice {@code out[c*M*n ..]} is BIT-IDENTICAL to the single-chunk
 * {@code df_batch_lattice_multi} output for that chunk's origin.
 *
 * <p><b>PIPELINED SLOT RING (GPU-throughput rework).</b> The dispatcher owns
 * {@code depth} independent SLOTS (config {@code gpu.batchPipelineDepth}), each with
 * its OWN in-order command queue, its own cloned kernel(s) and its own
 * origins/out/pinned/aux/ids buffers. {@link #enqueueBatch} acquires a free slot,
 * enqueues the whole batch NON-blocking (origins upload &rarr; corner NDRange &rarr;
 * optional decide chain &rarr; async readback into the slot's persistently-mapped
 * pinned staging), flushes the queue so the GPU starts immediately, and returns an
 * {@link InFlight} handle WITHOUT waiting. Up to {@code depth} batches are in flight
 * at once: the GPU never sits idle between batches waiting for host-side slice/copy/
 * future-completion work, and readback DMA of batch {@code i} overlaps the corner
 * NDRange of batch {@code i+1} (independent in-order queues). When every slot is in
 * flight, {@link #enqueueBatch} blocks — that backpressure is exactly what lets
 * submit-side requests pool into BIGGER batches instead of stalling workers.
 * {@code depth=1} degenerates to the previous serial dispatch&rarr;wait behavior.
 *
 * <p>Slot exclusivity is the concurrency model: a slot's queue/kernels/buffers are
 * touched only by the thread that acquired it (the enqueue path) and, after handoff,
 * by the completion consumer of its {@link InFlight} (await/readSlice/ids/release) —
 * never both at once. {@code clSetKernelArg} statefulness is safe because kernels
 * are per-slot clones. The blocking {@link #batchFill} (parity self-tests, climate
 * gate) is now a thin enqueue+await wrapper over the same path.
 *
 * <p>Returns {@code null} on any compile failure (logged; never throws). Every
 * {@code batchFill} returns {@code false} on any failure rather than throwing.
 */
public final class GpuBatchDispatcher implements AutoCloseable {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    private static final String NOISE_CL = "/superchunk/kernels/noise.cl";
    private static final String SUPPORT_CL = "/superchunk/kernels/dfc_support.cl";
    private static final String KERNEL_NAME = "df_batch_lattice_multichunk";

    private final CLProgram program;
    private final boolean fp32;
    /** Slot queues carry CL_QUEUE_PROFILING_ENABLE — gates the per-command event capture. */
    private final boolean profilingQueues;

    /**
     * COLUMN DEDUPLICATION ({@code -Dsuperchunk.gpu.colDedup=true}, default OFF until the
     * measured redundancy justifies it).
     *
     * <p>Chunks in one batch share cell columns: a chunk's lattice is {@code dimX x dimZ}
     * columns with {@code (dimX-1)*strx == 16}, so its last row/column of columns is the
     * neighbour's first. When on, the corner grid is produced by
     * {@code df_batch_lattice_uniqcol} over the batch's DISTINCT columns followed by
     * {@code sc_expand_columns}, which writes the identical {@code out} layout — the same
     * root functions at the same integer coordinates, so bit-exact by construction. Falls
     * back to the single-kernel path whenever the tables cannot be built, the kernels are
     * absent, the batch spans more than one {@code oy}, or there is no redundancy to remove.
     */
    private static final boolean COL_DEDUP = Boolean.getBoolean("superchunk.gpu.colDedup");
    /** {@code df_batch_lattice_uniqcol} / {@code sc_expand_columns} both resolved on this program. */
    private final boolean colDedupAvailable;
    private final java.util.concurrent.atomic.AtomicLong colDedupBatches = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong colDedupCols = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong colDedupUnique = new java.util.concurrent.atomic.AtomicLong();

    private final String kernelName;
    private final int realBytes;
    private final int rootCount;

    // Immutable noise-state device buffers (uploaded once, READ-ONLY, shared by all slots).
    private final long noiseDescMem;
    private final long noiseFactorsMem;
    private final long nPermMem;
    private final long nActiveMem;
    private final long nXoMem;
    private final long nYoMem;
    private final long nZoMem;
    private final long nAmpMem;

    // ------------------------------------------------------------------
    // COMPACT-IDS decide chain (Stage 5, -Dsuperchunk.gpu.compactIds=probe).
    // Chained on the SLOT's in-order queue between the corner NDRange and the
    // corner readback; reads the SAME device-resident slot corner buffer.
    // Shared plan/program/noise-state; per-slot cloned kernel + aux/ids buffers.
    // ------------------------------------------------------------------
    private CompactIds.Plan decidePlan;
    private CLProgram decideProgram;               // null = decide chain unavailable
    // the decide program's OWN read-only noise state (its registry != the corner program's)
    private final long[] decideNoiseMems = new long[8];
    /**
     * Sticky OFF switch for the decide chain: flipped (never cleared) on any decide
     * enqueue/map fault so subsequent batches ride corner-only. The chain's native
     * resources are only actually freed in {@link #close()} — a mid-flight free would
     * race other slots' in-flight decide dispatches.
     */
    private volatile boolean decideDisabled = false;
    /** Per-chunk aux int-table stride — MUST match SC_AUX_INTS in decide.cl. */
    private static final int AUX_INTS = 18;

    // ------------------------------------------------------------------
    // CLIMATE CHAIN (2026-07-07, the "free ride" architecture): a SECOND
    // df_batch_lattice_multichunk program — the dimension's byte-parity-gated fused
    // CLIMATE group — chained on the SAME slot queue between the corner NDRange and
    // the readback event. A combined submit therefore computes a chunk's density
    // corner grids AND its biome-climate quart grids in ONE slot round trip: zero
    // extra pipeline threads, zero extra driver streams, zero extra completion
    // events (vs the dedicated climate batcher, whose thread pair + cross-pipeline
    // driver contention measurably cost cps at worker saturation). The climate
    // program compiles from the EXACT roots list the dedicated climate dispatcher
    // was gated with — identical source -> CLProgramCache HIT (same binary, the
    // byte-identity gate proof transfers) and identical root order (store contract).
    // Shared program + noise state; per-slot cloned kernel + origins/out/pinned bufs.
    // Climate origins derive drainer-side from the density origins (same chunk x/z,
    // fixed per-dimension quart-lattice Y origin) — no extra per-request payload.
    // ------------------------------------------------------------------
    private volatile boolean climChainReady = false;
    private volatile boolean climChainFailed = false;   // sticky OFF on any fault
    // Whether the attached chain program is the fp32-compute/double-out variant
    // (gpu.climateFp32; biome safety = the serve-side quantization guard).
    private volatile boolean climChainFp32 = false;
    private CLProgram climProgram;
    private final long[] climNoiseMems = new long[8];
    /** Identity of the climate fused group the chain was attached for. */
    private volatile Object climAttachedTo;
    private int climRootCount;
    private int climOyBlock;            // quart-lattice Y origin (block coords), dimension-constant
    private int climDimX;
    private int climDimY;
    private int climDimZ;
    private int climN;                  // climDimX*climDimY*climDimZ
    private final Object climAttachLock = new Object();

    // ------------------------------------------------------------------
    // Slot ring.
    // ------------------------------------------------------------------
    private final Slot[] slots;
    private final ArrayBlockingQueue<Slot> freeSlots;

    private volatile boolean closed = false;
    /**
     * Read side: the enqueue span, and InFlight release/ids-map (short, driver-call
     * bounded — never held across an event WAIT). Write side: {@link #close()}. This
     * replaces the old single mutex so multiple slots can be serviced concurrently
     * while close() still gets exclusive access before freeing native handles.
     */
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

    // Pipeline observability (drainer-side backpressure).
    private final AtomicLong slotWaitNanos = new AtomicLong();
    private final AtomicLong slotAcquires = new AtomicLong();

    private GpuBatchDispatcher(CLProgram program, boolean fp32, int rootCount, boolean profilingQueues,
                               int depth, String kernelName,
                               long noiseDescMem, long noiseFactorsMem, long nPermMem, long nActiveMem,
                               long nXoMem, long nYoMem, long nZoMem, long nAmpMem) {
        this.program = program;
        this.fp32 = fp32;
        this.profilingQueues = profilingQueues;
        this.kernelName = kernelName;
        this.realBytes = fp32 ? Float.BYTES : Double.BYTES;
        this.rootCount = rootCount;
        this.noiseDescMem = noiseDescMem;
        this.noiseFactorsMem = noiseFactorsMem;
        this.nPermMem = nPermMem;
        this.nActiveMem = nActiveMem;
        this.nXoMem = nXoMem;
        this.nYoMem = nYoMem;
        this.nZoMem = nZoMem;
        this.nAmpMem = nAmpMem;
        // The column-dedup kernels only exist in the standard emitMultiChunk source (the
        // fp32 climate variant has no use for them), so probe rather than assume.
        boolean dedupOk = false;
        if (COL_DEDUP) {
            long probeA = NULL, probeB = NULL;
            try {
                probeA = program.kernel("df_batch_lattice_uniqcol");
                probeB = program.kernel("sc_expand_columns");
                dedupOk = probeA != NULL && probeB != NULL;
            } catch (Throwable t) {
                dedupOk = false;
            } finally {
                CLProgram.releaseKernel(probeA);
                CLProgram.releaseKernel(probeB);
            }
            if (!dedupOk) {
                LOGGER.warn("[SuperChunk-GPU] [batch] column dedup requested but this program has no "
                        + "df_batch_lattice_uniqcol/sc_expand_columns — using the single-kernel corner path.");
            }
        }
        this.colDedupAvailable = dedupOk;
        this.slots = new Slot[depth];
        this.freeSlots = new ArrayBlockingQueue<>(depth);
        try {
            for (int i = 0; i < depth; i++) {
                this.slots[i] = new Slot(profilingQueues);
                this.freeSlots.add(this.slots[i]);
            }
        } catch (Throwable t) {
            // Free the partially-created ring before rethrowing so compile()'s catch
            // (which releases the shared mems + program) can't leak slot resources.
            for (Slot s : this.slots) {
                if (s != null) {
                    try {
                        s.free();
                    } catch (Throwable ignored) {
                    }
                }
            }
            throw t;
        }
        OpenCLBackend.registerResourceOwner(this);
    }

    /** Number of roots M per chunk (each occupies an n-length slice of every chunk's output). */
    public int rootCount() {
        return rootCount;
    }

    /** Pipeline depth (number of independent in-flight batch slots). */
    public int pipelineDepth() {
        return slots.length;
    }

    /** Total nanoseconds enqueue callers spent blocked waiting for a free slot (backpressure). */
    public long slotWaitNanos() {
        return slotWaitNanos.get();
    }

    /** Total slot acquisitions (== enqueued batches; denominator for {@link #slotWaitNanos()}). */
    public long slotAcquires() {
        return slotAcquires.get();
    }

    private static int configPipelineDepth() {
        try {
            if (OpenCLBackend.config() != null) {
                return OpenCLBackend.config().batchPipelineDepth();
            }
        } catch (Throwable ignored) {
        }
        return 4;
    }

    /**
     * Compiles {@code roots} into one cross-chunk {@code df_batch_lattice_multichunk}
     * program (shared {@link NoiseRegistry} -> cross-DF CSE), uploads the shared
     * read-only noise state ONCE, and creates the pipelined slot ring (per-slot queue
     * + cloned kernel). The program is kept ALIVE via {@link CLProgramCache} (never
     * close+recompiled). Returns {@code null} on any failure (logged; never throws).
     */
    public static GpuBatchDispatcher compile(List<AstNode> roots) {
        return compile(roots, null, false);
    }

    /**
     * fp32-compute / double-out CLIMATE dispatcher ({@code gpu.climateFp32}): the roots
     * evaluate in fp32 (~30-60x the fp64 ALU rate on consumer NVIDIA) but the output
     * buffer/readback stays double, so every host path is unchanged. Callers must gate
     * acceptance at the QUANTIZED level ({@code BiomeClimateCache.batchedQuantIdentical})
     * — the byte-identity gate cannot pass by design — and biome safety additionally
     * rests on the serve-side quantization guard. fp64 builds only (no-op under global
     * fp32, where the standard path is already float).
     */
    public static GpuBatchDispatcher compileClimateF32D(List<AstNode> roots) {
        return compile(roots, null, true);
    }

    /**
     * Live-path variant: {@code fused} is the fused group these roots came from
     * (member {@code k} == root {@code k}). When {@code -Dsuperchunk.gpu.compactIds=probe}
     * is on it additionally builds the CHAINED per-block decide kernel (interp + REAL
     * finalDensity tail + aq_decide + veins &rarr; 1 byte/block) for this group; any
     * failure leaves the decide chain off and the corner dispatcher untouched.
     * {@code fused == null} (self-test path) never builds the decide chain.
     */
    public static GpuBatchDispatcher compile(List<AstNode> roots, GpuFusedInterpolator fused) {
        return compile(roots, fused, false);
    }

    private static GpuBatchDispatcher compile(List<AstNode> roots, GpuFusedInterpolator fused, boolean climF32d) {
        if (!OpenCLBackend.isAvailable()) {
            return null;
        }
        if (roots == null || roots.isEmpty()) {
            return null;
        }
        boolean fp32 = OpenCLBackend.isFp32();
        if (climF32d && fp32) {
            climF32d = false;   // global fp32 build: the standard path is already float
        }

        NoiseRegistry registry = new NoiseRegistry();
        OpenCLAstEmitter.MultiResult multi;
        try {
            multi = climF32d
                    ? OpenCLAstEmitter.emitMultiChunkF32D(roots, registry)
                    : OpenCLAstEmitter.emitMultiChunk(roots, registry);
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [batch] emitMultiChunk failed — batched dispatch unavailable.", t);
            return null;
        }
        if (multi.rootCount() != roots.size()) {
            LOGGER.warn("[SuperChunk-GPU] [batch] rootCount mismatch (emitMultiChunk M={} vs roots={}) — batched dispatch unavailable.",
                    multi.rootCount(), roots.size());
            return null;
        }

        String noiseSrc = CLProgram.loadKernelSource(NOISE_CL);
        String supportSrc = CLProgram.loadKernelSource(SUPPORT_CL);
        if (noiseSrc == null || supportSrc == null) {
            LOGGER.warn("[SuperChunk-GPU] [batch] could not load kernel resources — batched dispatch unavailable.");
            return null;
        }
        String fullSource = noiseSrc + "\n" + supportSrc + "\n" + multi.source();
        if (Boolean.getBoolean("superchunk.gpu.dumpKernel")) {
            LOGGER.info("[SuperChunk-GPU] === generated CROSS-CHUNK batched DF kernel (M={}) ===\n{}", multi.rootCount(), multi.source());
        }

        CLProgram program = CLProgramCache.getOrBuild(fullSource, (climF32d || fp32) ? "-DUSE_FP32" : "");
        if (program == null) {
            LOGGER.warn("[SuperChunk-GPU] [batch] batched kernel build failed — batched dispatch unavailable.");
            return null;
        }

        boolean floatState = climF32d || fp32;   // registry element type must match `real`
        long descMem = NULL, factorsMem = NULL, permMem = NULL, activeMem = NULL,
                xoMem = NULL, yoMem = NULL, zoMem = NULL, ampMem = NULL;
        try {
            descMem = program.uploadInts(registry.descArray());
            factorsMem = program.uploadReals(floatState, registry.factorArray());
            permMem = program.uploadPerm(registry.permArray());
            activeMem = program.uploadInts(registry.activeArray());
            xoMem = program.uploadReals(floatState, registry.xoArray());
            yoMem = program.uploadReals(floatState, registry.yoArray());
            zoMem = program.uploadReals(floatState, registry.zoArray());
            ampMem = program.uploadReals(floatState, registry.ampArray());
            // probe/verify modes: create the slot queues with profiling so the chained
            // decide kernel's device time can be attributed (CLProgram.eventProfile).
            // Production CONSUME runs skip it (per-command driver timestamping on every
            // enqueue across all slot queues); -Dsuperchunk.gpu.decideProfile=true forces
            // it back on when the decide-kernel metric is wanted in an `on` run.
            boolean profiling = CompactIds.PROBE && fused != null
                    && (!CompactIds.CONSUME || Boolean.getBoolean("superchunk.gpu.decideProfile"));
            GpuBatchDispatcher d = new GpuBatchDispatcher(program, fp32, multi.rootCount(), profiling,
                    Math.max(1, configPipelineDepth()),
                    climF32d ? "df_batch_lattice_multichunk_f32d" : KERNEL_NAME,
                    descMem, factorsMem, permMem, activeMem, xoMem, yoMem, zoMem, ampMem);
            program.logKernelWorkGroupInfo(d.slots[0].kernel, "df_batch_lattice_multichunk (corner)");
            // COMPACT-IDS (Stage 5): chain-build the decide kernel for this fused group.
            // Entirely fail-soft: any failure logs + counts and leaves d's corner path as-is.
            if (CompactIds.PROBE && fused != null) {
                try {
                    d.initDecide(fused);
                } catch (Throwable t) {
                    CompactIds.noteDecideFault(t);
                }
            }
            return d;
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [batch] noise upload / queue / kernel create failed — batched dispatch unavailable.", t);
            CLProgram.releaseMem(descMem);
            CLProgram.releaseMem(factorsMem);
            CLProgram.releaseMem(permMem);
            CLProgram.releaseMem(activeMem);
            CLProgram.releaseMem(xoMem);
            CLProgram.releaseMem(yoMem);
            CLProgram.releaseMem(zoMem);
            CLProgram.releaseMem(ampMem);
            program.close();
            return null;
        }
    }

    // ----------------------------------------------------------------------
    // Async pipelined API (the batcher drainer's path).
    // ----------------------------------------------------------------------

    /**
     * Acquires a free slot and enqueues one whole batch NON-blocking: origins upload,
     * corner NDRange, optional chained decide dispatch, async readback into the slot's
     * pinned staging — then flushes the queue (GPU starts immediately) and returns an
     * {@link InFlight} handle. Blocks while all {@code depth} slots are in flight
     * (backpressure). Returns {@code null} on shutdown or invalid args, and on any
     * enqueue failure (the slot is drained + returned to the pool). Never throws.
     *
     * <p>The caller (one logical producer per acquired slot) must eventually call
     * {@link InFlight#await()} then {@link InFlight#release()} — release recycles the
     * slot; a dropped handle permanently shrinks the pipeline.
     */
    public InFlight enqueueBatch(int[] originsXYZ, int K,
                                 int strx, int stry, int strz,
                                 int dimX, int dimY, int dimZ,
                                 CompactIds.ChunkAux[] auxes) {
        return enqueueBatch(originsXYZ, K, strx, stry, strz, dimX, dimY, dimZ, auxes, false);
    }

    /**
     * CLIMATE-CHAIN variant: when {@code withClimate} and the chain is attached, the
     * batch ALSO runs the chained climate program over every chunk's quart lattice
     * (origins derived from the density origins) and reads the K climate slices back
     * alongside the corners — read them via {@link InFlight#readClimSlice}. When the
     * chain isn't available the batch silently completes corner-only
     * ({@link InFlight#climateAvailable()} false; callers fail/funnel their climate
     * futures).
     */
    public InFlight enqueueBatch(int[] originsXYZ, int K,
                                 int strx, int stry, int strz,
                                 int dimX, int dimY, int dimZ,
                                 CompactIds.ChunkAux[] auxes, boolean withClimate) {
        if (originsXYZ == null || K <= 0 || originsXYZ.length < K * 3) {
            return null;
        }
        int n = dimX * dimY * dimZ;
        if (n <= 0 || validateSizes(K, n)) {
            return null;
        }
        return doEnqueue(originsXYZ, K, strx, stry, strz, dimX, dimY, dimZ, n, auxes, withClimate);
    }

    /**
     * COLUMN-DEDUPLICATED corner production. Evaluates each DISTINCT cell column of the batch
     * once ({@code df_batch_lattice_uniqcol}) and fans the result out into the normal
     * {@code out[c*M*n + k*n + cellIdx]} layout ({@code sc_expand_columns}), so every consumer
     * downstream — the chained decide kernel, the corner readback, the per-chunk grid store —
     * sees exactly the bytes the single-kernel path would have written.
     *
     * <p>Returns false (caller falls back to the one-kernel path, nothing enqueued) when dedup
     * is off/unavailable, when the batch spans more than one {@code oy} — a column is only
     * interchangeable between chunks whose vertical origin matches — or when the batch has no
     * duplicate columns to remove, in which case the extra copy would be pure loss.
     *
     * <p>Any throwable leaves the slot's queue with at most some harmless uploads on it and
     * returns false, so the caller's normal enqueue still produces a correct grid.
     */
    private boolean enqueueCornerDeduped(Slot s, int[] originsXYZ, int K,
                                         int strx, int stry, int strz,
                                         int dimX, int dimY, int dimZ, int n,
                                         int total, long[] cornerEvtOut) {
        if (!colDedupAvailable || s.uniqKernel == NULL || s.expandKernel == NULL) {
            return false;
        }
        final int oy = originsXYZ[1];
        for (int c = 1; c < K; c++) {
            if (originsXYZ[c * 3 + 1] != oy) {
                return false;   // mixed vertical origins: columns are not shared
            }
        }
        try {
            final int plane = dimX * dimZ;
            final int cols = K * plane;
            final int U = s.buildColumnTables(originsXYZ, K, strx, strz, dimX, dimZ);
            if (U <= 0 || U >= cols) {
                return false;   // nothing shared — the fan-out copy would be pure overhead
            }
            final long uniqElems = (long) rootCount * U * dimY;
            final long uniqTotal = (long) U * dimY;
            if (uniqElems > Integer.MAX_VALUE || uniqTotal > Integer.MAX_VALUE) {
                return false;
            }
            s.ensureUniqCapacity(uniqElems);
            program.writeBufferAsync(s.queue, s.colXZMem, s.hColXZ);
            program.writeBufferAsync(s.queue, s.colIdxMem, s.hColIdx);

            int a = 8;   // args 0..7 are the immutable noise pointers, bound once per slot
            program.setArgPointer(s.uniqKernel, a++, s.colXZMem);
            program.setArgInt(s.uniqKernel, a++, oy);
            program.setArgInt(s.uniqKernel, a++, stry);
            program.setArgInt(s.uniqKernel, a++, dimY);
            program.setArgInt(s.uniqKernel, a++, U);
            program.setArgInt(s.uniqKernel, a++, (int) uniqTotal);
            program.setArgPointer(s.uniqKernel, a, s.uniqMem);
            program.enqueue1DAsync(s.queue, s.uniqKernel, CLProgram.roundUpGlobal(uniqTotal), cornerEvtOut);

            int b = 0;
            program.setArgPointer(s.expandKernel, b++, s.uniqMem);
            program.setArgPointer(s.expandKernel, b++, s.colIdxMem);
            program.setArgInt(s.expandKernel, b++, dimX);
            program.setArgInt(s.expandKernel, b++, dimZ);
            program.setArgInt(s.expandKernel, b++, dimY);
            program.setArgInt(s.expandKernel, b++, U);
            program.setArgInt(s.expandKernel, b++, n);
            program.setArgInt(s.expandKernel, b++, rootCount);
            program.setArgInt(s.expandKernel, b++, total);
            program.setArgPointer(s.expandKernel, b, s.outMem);
            program.enqueue1DAsync(s.queue, s.expandKernel, CLProgram.roundUpGlobal(total), null);

            colDedupBatches.incrementAndGet();
            colDedupCols.addAndGet(cols);
            colDedupUnique.addAndGet(U);
            return true;
        } catch (Throwable t) {
            // The caller's single-kernel enqueue still writes the whole grid (the slot queue
            // is in-order, so it lands after anything half-enqueued above). Drop any event
            // this method already created — the caller is about to overwrite the slot.
            if (cornerEvtOut != null) {
                CLProgram.releaseEvent(cornerEvtOut[0]);
                cornerEvtOut[0] = NULL;
            }
            LOGGER.warn("[SuperChunk-GPU] [batch] column-dedup enqueue failed — single-kernel corner path.", t);
            return false;
        }
    }

    /** True when the K*n / K*M*n sizes overflow int (batch not dispatchable). */
    private boolean validateSizes(int K, int n) {
        long totalWork = (long) K * n;
        long outElemsL = (long) K * rootCount * n;
        return totalWork > Integer.MAX_VALUE || outElemsL > Integer.MAX_VALUE;
    }

    private InFlight doEnqueue(int[] originsXYZ, int K,
                               int strx, int stry, int strz,
                               int dimX, int dimY, int dimZ, int n,
                               CompactIds.ChunkAux[] auxes, boolean withClimate) {
        final int total = K * n;
        final int outElems = K * rootCount * n;
        Slot s = acquireSlot();
        if (s == null) {
            return null; // closed / interrupted shutdown
        }
        boolean ok = false;
        boolean enqueuedAny = false;
        InFlight inf = null;
        lifecycleLock.readLock().lock();
        try {
            if (closed) {
                return null;
            }
            s.ensureOriginsCapacity(K);
            s.ensureOutCapacity(outElems);

            // Upload the per-chunk origin table (NON-blocking write on the slot's own
            // in-order queue). The slot's staging buffers are exclusively ours until
            // InFlight.release(), so there is no host-buffer-reuse hazard.
            s.hOrigins.clear();
            s.hOrigins.put(originsXYZ, 0, K * 3);
            s.hOrigins.flip();
            enqueuedAny = true;
            program.writeBufferAsync(s.queue, s.originsMem, s.hOrigins);

            // Noise pointers (args 0..7) are bound once per slot kernel; only the args
            // that legitimately vary start at index 8.
            int a = 8;
            program.setArgPointer(s.kernel, a++, s.originsMem);
            program.setArgInt(s.kernel, a++, strx);
            program.setArgInt(s.kernel, a++, stry);
            program.setArgInt(s.kernel, a++, strz);
            program.setArgInt(s.kernel, a++, dimX);  // dx
            program.setArgInt(s.kernel, a++, dimZ);  // dz
            program.setArgInt(s.kernel, a++, n);
            program.setArgInt(s.kernel, a++, rootCount); // M
            program.setArgInt(s.kernel, a++, total);
            program.setArgPointer(s.kernel, a++, s.outMem);

            // One work-item per (chunk, cell); each writes all M roots for its chunk —
            // unless the column-dedup path takes over below and produces the same `out`.
            long[] cornerEvtOut = profilingQueues ? new long[1] : null;
            if (!enqueueCornerDeduped(s, originsXYZ, K, strx, stry, strz, dimX, dimY, dimZ, n,
                    total, cornerEvtOut)) {
                program.enqueue1DAsync(s.queue, s.kernel, CLProgram.roundUpGlobal(total), cornerEvtOut);
            }

            // Kick the corner kernel NOW. Everything below (aux staging, 5 uploads, the
            // decide NDRange, 2 readbacks) is enqueue-side host work; without this flush the
            // driver may hold the whole batch back until the trailing flush, delaying the
            // kernel by that staging time. Same reasoning as the trailing flush's comment.
            program.flush(s.queue);

            inf = new InFlight(s, K, rootCount * n, outElems);
            if (cornerEvtOut != null) {
                inf.cornerEvt = cornerEvtOut[0];
            }

            // ---- COMPACT-IDS decide chain: aux upload (async) + chained decide
            // NDRange over the SAME device-resident slot corner buffer. Enqueued
            // BEFORE the corner readback so the in-order slot queue orders
            // corners -> decide -> ids readback -> corner readback, and the corner
            // readback event implies all of them.
            if (auxes != null && decideProgram != null && !decideDisabled && s.decideKernel != NULL) {
                long[] evtOut = new long[2];   // [0]=decide NDRange event, [1]=ids readback event
                try {
                    int[] prep = enqueueDecide(s, K, strx, stry, strz, dimX, dimY, dimZ, n, auxes, evtOut);
                    if (prep != null) {
                        inf.decidePresent = prep[0];
                        inf.decideFullN = prep[1];
                        inf.decideEvt = evtOut[0];
                        inf.idsReadEvt = evtOut[1];
                    }
                } catch (Throwable t) {
                    // A throw can land after the decide/ids events were created but
                    // before they were handed to inf — release them or they leak.
                    CLProgram.releaseEvent(evtOut[0]);
                    CLProgram.releaseEvent(evtOut[1]);
                    // Sticky-disable the decide chain; the corner path is unaffected
                    // (any half-enqueued decide work orders harmlessly before the
                    // readback on the in-order queue and writes only slot buffers).
                    decideDisabled = true;
                    CompactIds.noteDecideFault(t);
                }
            }

            // ---- CLIMATE CHAIN: derive quart-lattice origins from the density
            // origins (same chunk x/z, fixed dimension-constant quart Y), enqueue the
            // chained climate NDRange + its async readback BEFORE the corner readback,
            // so the corner read event (last command on the in-order queue) implies
            // the climate slices are in their pinned staging too. Fail-soft: a chain
            // fault sticky-disables it and the batch completes corner-only.
            if (withClimate && climChainReady && !climChainFailed && s.climKernel != NULL) {
                try {
                    long climOutL = (long) K * climRootCount * climN;
                    long climTotalL = (long) K * climN;
                    if (climOutL <= Integer.MAX_VALUE && climTotalL <= Integer.MAX_VALUE) {
                        int climOutElems = (int) climOutL;
                        int climTotal = (int) climTotalL;
                        s.ensureClimOriginsCapacity(K);
                        s.ensureClimOutCapacity(climOutElems);
                        s.hClimOrigins.clear();
                        for (int c = 0; c < K; c++) {
                            s.hClimOrigins.put(c * 3, originsXYZ[c * 3]);
                            s.hClimOrigins.put(c * 3 + 1, climOyBlock);
                            s.hClimOrigins.put(c * 3 + 2, originsXYZ[c * 3 + 2]);
                        }
                        s.hClimOrigins.position(0).limit(K * 3);
                        program.writeBufferAsync(s.queue, s.climOriginsMem, s.hClimOrigins);
                        int a3 = 8;
                        climProgram.setArgPointer(s.climKernel, a3++, s.climOriginsMem);
                        climProgram.setArgInt(s.climKernel, a3++, 4);          // strx (quart)
                        climProgram.setArgInt(s.climKernel, a3++, 4);          // stry
                        climProgram.setArgInt(s.climKernel, a3++, 4);          // strz
                        climProgram.setArgInt(s.climKernel, a3++, climDimX);   // dx
                        climProgram.setArgInt(s.climKernel, a3++, climDimZ);   // dz
                        climProgram.setArgInt(s.climKernel, a3++, climN);
                        climProgram.setArgInt(s.climKernel, a3++, climRootCount); // M
                        climProgram.setArgInt(s.climKernel, a3++, climTotal);
                        climProgram.setArgPointer(s.climKernel, a3, s.climOutMem);
                        climProgram.enqueue1DAsync(s.queue, s.climKernel, CLProgram.roundUpGlobal(climTotal), null);
                        if (fp32) {
                            s.hClimOutF.clear();
                            s.hClimOutF.limit(climOutElems);
                            program.enqueueReadAsync(s.queue, s.climOutMem, s.hClimOutF, null);
                        } else {
                            s.hClimOutD.clear();
                            s.hClimOutD.limit(climOutElems);
                            program.enqueueReadAsync(s.queue, s.climOutMem, s.hClimOutD, null);
                        }
                        inf.climAvailable = true;
                        inf.climSliceLen = climRootCount * climN;
                    }
                } catch (Throwable t) {
                    // The corner path is unaffected (in-order queue; slot buffers only).
                    inf.climAvailable = false;
                    climChainFailed = true;
                    LOGGER.warn("[SuperChunk-GPU] [biome-chain] chained climate enqueue failed — chain sticky-disabled, "
                            + "split climate path resumes.", t);
                }
            }

            // Async readback of the whole K*M*n corner buffer into the slot's
            // persistently-mapped pinned staging; the read event is the batch's
            // completion signal (in-order queue -> it implies kernel + decide + climate done).
            long[] readEvt = new long[1];
            if (fp32) {
                s.hOutF.clear();
                s.hOutF.limit(outElems);
                program.enqueueReadAsync(s.queue, s.outMem, s.hOutF, readEvt);
            } else {
                s.hOutD.clear();
                s.hOutD.limit(outElems);
                program.enqueueReadAsync(s.queue, s.outMem, s.hOutD, readEvt);
            }
            inf.readEvent = readEvt[0];
            // Kick the GPU now — without a flush the driver may defer submission
            // until a blocking call, serializing the pipeline right back.
            program.flush(s.queue);
            ok = true;
            return inf;
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [batch] batched enqueue failed (K={}, M={}).", K, rootCount, t);
            // Drain whatever was partially enqueued so the slot is quiescent before it
            // returns to the pool. MUST run under the read lock (still held here) — a
            // post-unlock finish could race close() freeing the queue (UAF).
            if (enqueuedAny) {
                try {
                    program.finish(s.queue);
                } catch (Throwable ignored) {
                }
            }
            // The discarded inf never reaches a consumer, so its events would leak.
            if (inf != null) {
                CLProgram.releaseEvent(inf.readEvent);
                inf.readEvent = NULL;
                CLProgram.releaseEvent(inf.decideEvt);
                inf.decideEvt = NULL;
                CLProgram.releaseEvent(inf.idsReadEvt);
                inf.idsReadEvt = NULL;
                CLProgram.releaseEvent(inf.cornerEvt);
                inf.cornerEvt = NULL;
            }
            return null;
        } finally {
            lifecycleLock.readLock().unlock();
            if (!ok) {
                // Return the slot on every non-success path — a lost slot would
                // silently shrink the pipeline forever.
                freeSlots.offer(s);
            }
        }
    }

    /** Blocks for a free slot; null on close/interrupt. Records backpressure wait time. */
    private Slot acquireSlot() {
        long t0 = System.nanoTime();
        try {
            while (!closed) {
                Slot s = freeSlots.poll(50, TimeUnit.MILLISECONDS);
                if (s != null) {
                    slotWaitNanos.addAndGet(System.nanoTime() - t0);
                    slotAcquires.incrementAndGet();
                    return s;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /**
     * Handle for one in-flight batched dispatch. Lifecycle (single consumer):
     * {@link #await()} &rarr; {@link #readSlice}/{@link #ids} &rarr; {@link #release()}.
     * The underlying slot (and therefore every accessor's backing memory) stays
     * exclusively owned by this handle until release.
     */
    public final class InFlight {
        private final Slot slot;
        private final int k;
        private final int sliceLen;
        private final int outElems;
        long readEvent = NULL;
        long decideEvt = NULL;
        long idsReadEvt = NULL;
        // Corner NDRange event. Captured ONLY when the slot queues carry
        // CL_QUEUE_PROFILING_ENABLE (probe/verify, or -Dsuperchunk.gpu.decideProfile).
        // Before this existed the corner kernel and both DMAs were enqueued with a null
        // event, so ~40% of in-batch GPU time was structurally unattributable.
        long cornerEvt = NULL;
        // 0 = unchecked, 1 = ids DMA verified CL_COMPLETE, -1 = failed (corner-only fallback)
        private int idsState;
        int decidePresent;
        int decideFullN;
        // CLIMATE CHAIN: whether this batch carries K climate slices in the slot's
        // climate pinned staging, and each slice's packed length (climM * climN).
        boolean climAvailable;
        int climSliceLen;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private InFlight(Slot slot, int k, int sliceLen, int outElems) {
            this.slot = slot;
            this.k = k;
            this.sliceLen = sliceLen;
            this.outElems = outElems;
        }

        /**
         * Blocks until the batch's readback completed. False on any error — the
         * caller must then fail the batch's futures and still {@link #release()}.
         */
        public boolean await() {
            return CLProgram.waitForEvent(readEvent);
        }

        /** Chunks with a decide-chain id slice this batch (0 = corner-only batch). */
        public int decidePresent() {
            return decidePresent;
        }

        /**
         * Decide kernel device-time (ns), or -1. Gated on {@link #profilingQueues}: decideEvt is
         * captured unconditionally, but on a non-profiling queue the driver only answers
         * CL_PROFILING_INFO_NOT_AVAILABLE — so without this the production path paid three
         * always-failing driver round trips per batch on the completer thread.
         */
        public long decideKernelNs() {
            if (!profilingQueues || decideEvt == NULL) {
                return -1L;
            }
            long[] prof = CLProgram.eventProfile(decideEvt);
            return prof != null ? prof[1] : -1L;
        }

        /**
         * Full in-batch device timeline, or null when the slot queue is not profiling.
         *
         * <p>Returns {@code {cornerNs, decideNs, idsReadNs, cornerReadNs, spanNs}}: the four
         * commands' device busy times and the wall span from the corner kernel's START to the
         * last command's END, all on the device clock. The commands share ONE in-order queue, so
         * subtracting timestamps across them is meaningful, and the GPU idle INSIDE the batch is
         * simply {@code span - sum(busy)} — derived at report time rather than accumulated here.
         * A missing event makes that component -1 and drops out of both sums.
         */
        public long[] deviceTimeline() {
            if (cornerEvt == NULL) {
                return null;
            }
            try {
                // In-order queue order: corner -> decide -> ids read -> corner read.
                long[][] seq = {CLProgram.eventTimes(cornerEvt), CLProgram.eventTimes(decideEvt),
                        CLProgram.eventTimes(idsReadEvt), CLProgram.eventTimes(readEvent)};
                if (seq[0] == null) {
                    return null;
                }
                long[] out = new long[5];
                long[] last = seq[0];
                for (int i = 0; i < seq.length; i++) {
                    if (seq[i] == null) {
                        out[i] = -1L;
                        continue;
                    }
                    out[i] = seq[i][2] - seq[i][1];
                    last = seq[i];
                }
                out[4] = last[2] - seq[0][1];
                return out;
            } catch (Throwable t) {
                return null;
            }
        }

        /**
         * Copies chunk {@code c}'s {@code M*n} slice out of the pinned staging into
         * {@code dst[0..sliceLen)}. Call only after a successful {@link #await()}.
         */
        public void readSlice(int c, double[] dst) {
            int base = c * sliceLen;
            if (fp32) {
                FloatBuffer f = slot.hOutF;
                for (int i = 0; i < sliceLen; i++) {
                    dst[i] = f.get(base + i);
                }
            } else {
                slot.hOutD.get(base, dst, 0, sliceLen);
            }
        }

        /** Whether this batch carries climate slices ({@link #readClimSlice} valid). */
        public boolean climateAvailable() {
            return climAvailable;
        }

        /** This batch's per-chunk packed climate length; 0 when {@link #climateAvailable()} is false. */
        public int climateSliceLenLive() {
            return climAvailable ? climSliceLen : 0;
        }

        /**
         * Copies chunk {@code c}'s packed climate slice ({@code climM*climN} doubles,
         * climate root {@code k}'s quart grid at {@code [k*climN ..]}) out of the slot's
         * climate pinned staging into {@code dst[0..climSliceLen)}. Call only after a
         * successful {@link #await()} and only when {@link #climateAvailable()}.
         */
        public void readClimSlice(int c, double[] dst) {
            int base = c * climSliceLen;
            if (fp32) {
                FloatBuffer f = slot.hClimOutF;
                for (int i = 0; i < climSliceLen; i++) {
                    dst[i] = f.get(base + i);
                }
            } else {
                slot.hClimOutD.get(base, dst, 0, climSliceLen);
            }
        }

        /** Bulk variant: the whole {@code K*M*n} buffer into {@code out} (blocking batchFill compat). */
        void readAll(double[] out) {
            if (fp32) {
                FloatBuffer f = slot.hOutF;
                for (int i = 0; i < outElems; i++) {
                    out[i] = f.get(i);
                }
            } else {
                slot.hOutD.get(0, out, 0, outElems);
            }
        }

        /**
         * Chunk {@code c}'s 1-byte/block decide ids, or {@code null} when the chunk
         * rode corner-only (no aux / route mismatch / chain off). Copies out of the
         * slot's map-once pinned staging, which the async ids readback filled before
         * the corner read event (in-order queue). Call only after a successful
         * {@link #await()}.
         */
        public byte[] ids(int c) {
            if (decidePresent <= 0) {
                return null;
            }
            if (idsState == 0) {
                // await() (the corner-read event) proves the ids DMA finished
                // EXECUTING (in-order queue), not that it terminated normally — an
                // abnormally-terminated DMA would leave the PREVIOUS batch's bytes in
                // the staging. Verify CL_COMPLETE once per batch; on failure every
                // chunk falls back to the CPU decide path (the old blocking map
                // detected this class implicitly).
                idsState = CLProgram.eventComplete(idsReadEvt) ? 1 : -1;
                if (idsState < 0) {
                    CompactIds.noteDecideFault(new IllegalStateException(
                            "ids readback event not CL_COMPLETE after batch await — corner-only fallback"));
                }
            }
            if (idsState < 0) {
                return null;
            }
            lifecycleLock.readLock().lock();
            try {
                // closed guards the stagings' backing memory: close() (write lock)
                // frees the pinned mapping AND the aux host buffers in Slot.free(),
                // so BOTH native reads below must sit under the same guard.
                if (closed || slot.hIds == null || slot.hAuxInts == null) {
                    return null;
                }
                if (slot.hAuxInts.get(c * AUX_INTS) == 0) {
                    return null;   // absent (no aux / route or cell mismatch)
                }
                byte[] ids = new byte[decideFullN];
                slot.hIds.get(c * decideFullN, ids, 0, decideFullN);
                return ids;
            } finally {
                lifecycleLock.readLock().unlock();
            }
        }

        /**
         * Recycles the slot (releases the events, returns the slot to the free
         * pool). Idempotent; never throws. MUST be called exactly once per handle
         * on every path (success or failure).
         */
        public void release() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            CLProgram.releaseEvent(readEvent);
            readEvent = NULL;
            CLProgram.releaseEvent(decideEvt);
            decideEvt = NULL;
            CLProgram.releaseEvent(idsReadEvt);
            idsReadEvt = NULL;
            CLProgram.releaseEvent(cornerEvt);
            cornerEvt = NULL;
            freeSlots.offer(slot);
        }
    }

    // ----------------------------------------------------------------------
    // Blocking API (parity self-tests + the biome batched gate).
    // ----------------------------------------------------------------------

    /**
     * Dispatches {@code df_batch_lattice_multichunk} ONCE over all K chunks and reads
     * the {@code K*M*n} result back into {@code out}. Chunk {@code c}, root {@code k},
     * cell {@code i} lands at {@code out[(c*M + k)*n + i]} where {@code M = }
     * {@link #rootCount()} and {@code n = dimX*dimY*dimZ}. Every batched chunk shares
     * the given strides/dims; only its origin (from {@code originsXYZ[c*3 + 0/1/2]})
     * differs. {@code out.length} must be at least {@code K*M*n}.
     *
     * <p>Same per-cell {@code (xx,yy,zz)} decode and same root math as the single-chunk
     * {@code df_batch_lattice_multi}, so each chunk's M*n slice is bit-identical to that
     * chunk's per-chunk dispatch. Grows the slot buffers as needed. Returns
     * {@code false} on any failure (output undefined). Thread-safe: concurrent callers
     * simply acquire different slots.
     */
    public boolean batchFill(int[] originsXYZ, int K,
                             int strx, int stry, int strz,
                             int dimX, int dimY, int dimZ,
                             double[] out) {
        return batchFill(originsXYZ, K, strx, stry, strz, dimX, dimY, dimZ, out, null, null);
    }

    /**
     * COMPACT-IDS variant (Stage 5): same corner dispatch + readback, plus — when
     * {@code auxes} is non-null and the decide chain built — the CHAINED
     * {@code sc_decide_multichunk} NDRange whose K*fullN 1-byte/block ids land in
     * {@code idsOut[c]} iff chunk {@code c}'s aux was present + route-matched
     * (otherwise {@code idsOut[c]} stays null = the normal corner-only path).
     * A decide-chain fault never fails the corner result: the chain sticky-disables
     * itself and the batch completes corner-only.
     */
    public boolean batchFill(int[] originsXYZ, int K,
                             int strx, int stry, int strz,
                             int dimX, int dimY, int dimZ,
                             double[] out, CompactIds.ChunkAux[] auxes, byte[][] idsOut) {
        int n = dimX * dimY * dimZ;
        if (n <= 0 || validateSizes(K, n)) {
            return false;
        }
        int outElems = K * rootCount * n;
        if (out == null || out.length < outElems) {
            return false;
        }
        InFlight inf = enqueueBatch(originsXYZ, K, strx, stry, strz, dimX, dimY, dimZ, auxes);
        if (inf == null) {
            return false;
        }
        try {
            if (!inf.await()) {
                return false;
            }
            inf.readAll(out);
            if (idsOut != null && inf.decidePresent() > 0) {
                long tIds0 = System.nanoTime();
                for (int c = 0; c < K; c++) {
                    idsOut[c] = inf.ids(c);
                }
                CompactIds.recordDecide(inf.decidePresent(), inf.decideKernelNs(), System.nanoTime() - tIds0);
            }
            CompactIds.recordTimeline(inf.deviceTimeline());
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [batch] batched fill failed (K={}, M={}).", K, rootCount, t);
            return false;
        } finally {
            inf.release();
        }
    }

    // ----------------------------------------------------------------------
    // COMPACT-IDS decide chain (Stage 5).
    // ----------------------------------------------------------------------

    /**
     * Builds the decide program (called once from
     * {@link #compile(List, GpuFusedInterpolator)} when PROBE is on) and clones the
     * decide kernel into every slot. Fail-soft: leaves the chain off on any failure.
     * The decide program has its OWN noise state (its registry only holds the
     * tail/barrier noises).
     */
    private void initDecide(GpuFusedInterpolator fused) {
        CompactIds.Plan plan = CompactIds.buildPlan(fused);
        if (plan == null) {
            return;
        }
        // fp32 decision math (gpu.decideFp32, default true): the decide chain is the one
        // ALU-bound GPU stage. The corner buffer + aq_decide comparison algebra stay fp64
        // in the kernel source either way; -DUSE_FP32 switches only `real` (interpolation
        // deltas + emitted tail/barrier/vein ASTs + the noise-registry element type — so
        // the uploads below must match). Fail-soft: an fp32 build failure retries the
        // proven fp64 program before giving up on the chain.
        boolean decFp32 = OpenCLBackend.config().decideFp32();
        // FMA contraction for the fp32 decide program only (gpu.decideFmaContract,
        // default false — measured throughput-neutral on GA104): flip ONLY the FIRST
        // FP_CONTRACT pragma (noise.cl's, which governs the emitted real ASTs); the
        // second occurrence (aquifer.cl's) keeps the aquifer/vein double algebra
        // uncontracted, matching the decide.cl precision contract. Never applied to
        // the fp64 decide fallback (or any other program) — fp64 bit-exactness
        // requires OFF. Output envelope re-gated by the flip census when enabled.
        boolean fma = decFp32 && OpenCLBackend.config().decideFmaContract();
        String fp32Src = fma
                ? plan.source.replaceFirst(
                        java.util.regex.Pattern.quote("#pragma OPENCL FP_CONTRACT OFF"),
                        "#pragma OPENCL FP_CONTRACT ON")
                : plan.source;
        // Inline helpers for the decide program (-Dsuperchunk.gpu.decideInline, default
        // true): noinlineHelpers' call/spill overhead was accepted when the GPU was off
        // the critical path — no longer true. The decide program is ONE moderate kernel
        // (compile time fine inlined; timed + logged below); inlining changes no IEEE op
        // (FP_CONTRACT stays per-source), and the bit-exact/census gates re-prove it.
        boolean decInline = !"false".equalsIgnoreCase(
                System.getProperty("superchunk.gpu.decideInline", "true"));
        String opts = (decFp32 ? "-DUSE_FP32" : "")
                + (decInline ? (decFp32 ? " " : "") + CLProgram.FORCE_INLINE_MARKER : "");
        long tBuild0 = System.nanoTime();
        CLProgram p = CLProgramCache.getOrBuild(fp32Src, opts);
        if (p == null && decFp32) {
            LOGGER.warn("[SuperChunk-GPU] [compact-ids] fp32 decide clBuildProgram failed — "
                    + "falling back to the fp64 decide program.");
            decFp32 = false;
            p = CLProgramCache.getOrBuild(plan.source,
                    decInline ? CLProgram.FORCE_INLINE_MARKER : "");
        }
        LOGGER.info("[SuperChunk-GPU] [compact-ids] decide program ready in {} ms (inlineHelpers={}).",
                (System.nanoTime() - tBuild0) / 1_000_000L, decInline);
        if (p == null) {
            CompactIds.notePlanFail("decide clBuildProgram failed (see build log above)");
            return;
        }
        try {
            decideNoiseMems[0] = p.uploadInts(plan.registry.descArray());
            decideNoiseMems[1] = p.uploadReals(decFp32, plan.registry.factorArray());
            decideNoiseMems[2] = p.uploadPerm(plan.registry.permArray());
            decideNoiseMems[3] = p.uploadInts(plan.registry.activeArray());
            decideNoiseMems[4] = p.uploadReals(decFp32, plan.registry.xoArray());
            decideNoiseMems[5] = p.uploadReals(decFp32, plan.registry.yoArray());
            decideNoiseMems[6] = p.uploadReals(decFp32, plan.registry.zoArray());
            decideNoiseMems[7] = p.uploadReals(decFp32, plan.registry.ampArray());
            for (Slot s : slots) {
                long k = p.kernel(plan.kernelName);
                for (int i = 0; i < 8; i++) {
                    p.setArgPointer(k, i, decideNoiseMems[i]);
                }
                s.decideKernel = k;
            }
            p.logKernelWorkGroupInfo(slots[0].decideKernel, "sc_decide_multichunk");
            decideProgram = p;
            decidePlan = plan;
            CompactIds.noteDecideFp(decFp32 ? "fp32" : "fp64");
            LOGGER.info("[SuperChunk-GPU] [compact-ids] decide chain READY on the batched dispatcher "
                    + "(kernel={}, gridSlots={}, veins={}, slots={}, decideFp={}, fmaContract={}). Batched chunks with "
                    + "aux now get a chained 1-byte/block id dispatch; PROBE discards the ids after readback.",
                    plan.kernelName, plan.gridSlots, plan.veins, slots.length, decFp32 ? "fp32" : "fp64", fma && decFp32);
        } catch (Throwable t) {
            for (Slot s : slots) {
                CLProgram.releaseKernel(s.decideKernel);
                s.decideKernel = NULL;
            }
            for (int i = 0; i < 8; i++) {
                CLProgram.releaseMem(decideNoiseMems[i]);
                decideNoiseMems[i] = NULL;
            }
            try {
                p.close();
            } catch (Throwable ignored) {
            }
            CompactIds.noteDecideFault(t);
        }
    }

    // ----------------------------------------------------------------------
    // CLIMATE CHAIN attach + accessors.
    // ----------------------------------------------------------------------

    /**
     * Attaches the CLIMATE CHAIN for {@code climateGroupIdentity}: compiles
     * {@code climateRoots} into this dispatcher's second chained program (an exact
     * source match with the dimension's gated dedicated climate program — a
     * {@link CLProgramCache} hit, same binary), uploads its own noise state, and
     * clones the kernel into every slot. {@code oyBlock}/{@code dims} are the
     * dimension-constant quart lattice (stride 4). Idempotent per identity;
     * sticky-fails on any fault (chain off, combined submits fall back to split
     * paths). Returns true when the chain is ready for that identity+geometry.
     */
    public boolean attachClimateChain(Object climateGroupIdentity, List<AstNode> climateRoots,
                                      int oyBlock, int dimX, int dimY, int dimZ) {
        if (climChainFailed || closed || climateRoots == null || climateRoots.isEmpty()) {
            return false;
        }
        if (climChainReady) {
            return climateChainReadyFor(climateGroupIdentity)
                    && climateChainGeometryMatches(oyBlock, dimX, dimY, dimZ);
        }
        synchronized (climAttachLock) {
            if (climChainReady) {
                return climateChainReadyFor(climateGroupIdentity)
                        && climateChainGeometryMatches(oyBlock, dimX, dimY, dimZ);
            }
            if (climChainFailed) {
                return false;
            }
            long n = (long) dimX * dimY * dimZ;
            if (n <= 0 || n > Integer.MAX_VALUE) {
                climChainFailed = true;
                return false;
            }
            lifecycleLock.readLock().lock();
            try {
                if (closed) {
                    return false;
                }
                String noiseSrc = CLProgram.loadKernelSource(NOISE_CL);
                String supportSrc = CLProgram.loadKernelSource(SUPPORT_CL);
                if (noiseSrc == null || supportSrc == null) {
                    climChainFailed = true;
                    return false;
                }
                // fp32 climate compute (gpu.climateFp32, fp64 builds only): emit the
                // f32d variant — fp32 root evaluation, DOUBLE outputs (exact widenings)
                // — so climOutMem/hClimOutD/readClimSlice stay byte-layout-identical.
                // Biome safety = the serve-side quantization guard (MixinClimateSampler).
                // Fail-soft: an f32d build failure falls back to the proven fp64 chain.
                boolean climF32 = !fp32 && OpenCLBackend.config().climateFp32();
                NoiseRegistry registry = new NoiseRegistry();
                OpenCLAstEmitter.MultiResult multi = climF32
                        ? OpenCLAstEmitter.emitMultiChunkF32D(climateRoots, registry)
                        : OpenCLAstEmitter.emitMultiChunk(climateRoots, registry);
                if (multi.rootCount() != climateRoots.size()) {
                    climChainFailed = true;
                    return false;
                }
                CLProgram p = CLProgramCache.getOrBuild(noiseSrc + "\n" + supportSrc + "\n" + multi.source(),
                        (climF32 || fp32) ? "-DUSE_FP32" : "");
                if (p == null && climF32) {
                    LOGGER.warn("[SuperChunk-GPU] [biome-chain] fp32 climate program build failed — "
                            + "falling back to the fp64 climate chain.");
                    climF32 = false;
                    registry = new NoiseRegistry();
                    multi = OpenCLAstEmitter.emitMultiChunk(climateRoots, registry);
                    if (multi.rootCount() != climateRoots.size()) {
                        climChainFailed = true;
                        return false;
                    }
                    p = CLProgramCache.getOrBuild(noiseSrc + "\n" + supportSrc + "\n" + multi.source(), "");
                }
                if (p == null) {
                    climChainFailed = true;
                    return false;
                }
                try {
                    boolean floatState = climF32 || fp32;   // registry element type must match `real`
                    climNoiseMems[0] = p.uploadInts(registry.descArray());
                    climNoiseMems[1] = p.uploadReals(floatState, registry.factorArray());
                    climNoiseMems[2] = p.uploadPerm(registry.permArray());
                    climNoiseMems[3] = p.uploadInts(registry.activeArray());
                    climNoiseMems[4] = p.uploadReals(floatState, registry.xoArray());
                    climNoiseMems[5] = p.uploadReals(floatState, registry.yoArray());
                    climNoiseMems[6] = p.uploadReals(floatState, registry.zoArray());
                    climNoiseMems[7] = p.uploadReals(floatState, registry.ampArray());
                    for (Slot s : slots) {
                        long k = p.kernel(climF32 ? "df_batch_lattice_multichunk_f32d" : KERNEL_NAME);
                        for (int i = 0; i < 8; i++) {
                            p.setArgPointer(k, i, climNoiseMems[i]);
                        }
                        s.climKernel = k;
                    }
                    climChainFp32 = climF32;
                    BiomeClimateCache.noteClimateFp32Live(climF32);
                } catch (Throwable t) {
                    for (Slot s : slots) {
                        CLProgram.releaseKernel(s.climKernel);
                        s.climKernel = NULL;
                    }
                    for (int i = 0; i < 8; i++) {
                        CLProgram.releaseMem(climNoiseMems[i]);
                        climNoiseMems[i] = NULL;
                    }
                    try {
                        p.close();
                    } catch (Throwable ignored) {
                    }
                    climChainFailed = true;
                    LOGGER.warn("[SuperChunk-GPU] [biome-chain] climate chain attach failed — split climate path stays.", t);
                    return false;
                }
                climProgram = p;
                climRootCount = multi.rootCount();
                climOyBlock = oyBlock;
                climDimX = dimX;
                climDimY = dimY;
                climDimZ = dimZ;
                climN = (int) n;
                climAttachedTo = climateGroupIdentity;
                climChainReady = true;
                LOGGER.info("[SuperChunk-GPU] [biome-chain] climate chain ATTACHED to the density dispatcher "
                                + "(M={} climate roots, quart lattice {}x{}x{} oy={}, slots={}, climateFp={}): combined "
                                + "submits now compute density corners + climate quart grids in ONE slot round trip "
                                + "(no dedicated climate pipeline on this path).",
                        climRootCount, dimX, dimY, dimZ, oyBlock, slots.length, climChainFp32 ? "fp32+guard" : "fp64");
                return true;
            } catch (Throwable t) {
                climChainFailed = true;
                LOGGER.warn("[SuperChunk-GPU] [biome-chain] climate chain attach threw — split climate path stays.", t);
                return false;
            } finally {
                lifecycleLock.readLock().unlock();
            }
        }
    }

    /** True when the chain is live and was attached for exactly this climate group. */
    public boolean climateChainReadyFor(Object climateGroupIdentity) {
        return climChainReady && !climChainFailed && climAttachedTo == climateGroupIdentity;
    }

    /** True when the chain's dimension-constant quart geometry matches the caller's plan. */
    public boolean climateChainGeometryMatches(int oyBlock, int dimX, int dimY, int dimZ) {
        return climChainReady && climOyBlock == oyBlock
                && climDimX == dimX && climDimY == dimY && climDimZ == dimZ;
    }

    /** The chain's per-chunk packed climate length ({@code climM * climN}); 0 when not attached. */
    public int climateSliceLen() {
        return climChainReady ? climRootCount * climN : 0;
    }

    /**
     * Uploads the per-chunk aux tables (async, the slot's in-order queue) and enqueues
     * the chained decide NDRange over the slot's device-resident corner buffer.
     * Returns {@code {presentChunks, fullN}} or {@code null} when nothing is decidable
     * this batch. {@code evtOut[0]} receives the decide kernel event (the InFlight
     * owns + releases it).
     */
    private int[] enqueueDecide(Slot s, int K, int strx, int stry, int strz,
                                int dimX, int dimY, int dimZ, int cornerN,
                                CompactIds.ChunkAux[] auxes, long[] evtOut) {
        final CompactIds.Plan plan = this.decidePlan;
        if (plan == null) {
            return null;
        }
        int fullX = (dimX - 1) * strx;
        int fullY = (dimY - 1) * stry;
        int fullZ = (dimZ - 1) * strz;
        if (fullX <= 0 || fullY <= 0 || fullZ <= 0) {
            return null;
        }
        long fullNL = (long) fullX * fullY * fullZ;
        long totalL = fullNL * K;
        if (fullNL > Integer.MAX_VALUE || totalL > Integer.MAX_VALUE) {
            return null;
        }
        int fullN = (int) fullNL;
        int total = (int) totalL;
        // Uniform cell count across the batch (per-dimension constant in practice;
        // a mismatching chunk simply stays absent).
        int cellN = -1;
        int present = 0;
        double flowingSim = 0.0;
        for (int c = 0; c < K; c++) {
            CompactIds.ChunkAux a = auxes.length > c ? auxes[c] : null;
            if (a == null || a.route != plan.route) {
                continue;
            }
            // Phase A vein gate: a veins-enabled chunk decided by a veins=false plan
            // would ship plain stone where vanilla places ore veins — terrain
            // divergence. Such chunks stay ABSENT (normal corner-only path).
            if (a.veinsEnabled && !plan.veins) {
                CompactIds.noteDecideSkipVeinless();
                continue;
            }
            int cells = a.aq.locCache.length;
            if (cellN == -1) {
                cellN = cells;
                flowingSim = a.aq.flowingUpdateSimilarity;
            }
            if (cells != cellN) {
                continue;
            }
            present++;
        }
        if (present == 0 || cellN <= 0) {
            return null;
        }
        s.ensureAuxCapacity(K, cellN);
        s.ensureIdsCapacity((long) K * fullN);

        // Host staging (absolute puts; zero the absent slices so uploads are defined).
        s.hAuxInts.clear();
        s.hAuxLongs.clear();
        s.hAuxLoc.clear();
        s.hAuxFl.clear();
        s.hAuxFt.clear();
        for (int c = 0; c < K; c++) {
            CompactIds.ChunkAux a = auxes.length > c ? auxes[c] : null;
            boolean ok = a != null && a.route == plan.route && a.aq.locCache.length == cellN
                    && (!a.veinsEnabled || plan.veins);
            int ib = c * AUX_INTS;
            int cb = c * cellN;
            if (!ok) {
                for (int i = 0; i < AUX_INTS; i++) {
                    s.hAuxInts.put(ib + i, 0);
                }
                s.hAuxLongs.put(c * 2, 0L);
                s.hAuxLongs.put(c * 2 + 1, 0L);
                for (int i = 0; i < cellN; i++) {
                    s.hAuxLoc.put(cb + i, 0L);
                    s.hAuxFl.put(cb + i, 0);
                    s.hAuxFt.put(cb + i, 0);
                }
                continue;
            }
            CompactIds.AquiferAux aq = a.aq;
            s.hAuxInts.put(ib, 1);
            s.hAuxInts.put(ib + 1, aq.minGridX);
            s.hAuxInts.put(ib + 2, aq.minGridY);
            s.hAuxInts.put(ib + 3, aq.minGridZ);
            s.hAuxInts.put(ib + 4, aq.gridSizeX);
            s.hAuxInts.put(ib + 5, aq.gridSizeZ);
            s.hAuxInts.put(ib + 6, aq.seaLevel);
            s.hAuxInts.put(ib + 7, aq.lavaLevel);
            s.hAuxInts.put(ib + 8, aq.defaultFluidId);
            s.hAuxInts.put(ib + 9, a.hasBeard ? 1 : 0);
            s.hAuxInts.put(ib + 10, a.bMinX);
            s.hAuxInts.put(ib + 11, a.bMinY);
            s.hAuxInts.put(ib + 12, a.bMinZ);
            s.hAuxInts.put(ib + 13, a.bMaxX);
            s.hAuxInts.put(ib + 14, a.bMaxY);
            s.hAuxInts.put(ib + 15, a.bMaxZ);
            s.hAuxInts.put(ib + 16, a.veinsEnabled && plan.veins ? 1 : 0);
            s.hAuxInts.put(ib + 17, aq.airSkipY);
            s.hAuxLongs.put(c * 2, a.route.oreSeedLo);
            s.hAuxLongs.put(c * 2 + 1, a.route.oreSeedHi);
            // Absolute BULK puts (Java 13+): three memcpys instead of 3*cellN = 945
            // bounds-checked scalar puts per chunk (~30k per K=32 batch), on the drainer
            // thread that sits directly in front of the next GPU dispatch. Byte-identical.
            s.hAuxLoc.put(cb, aq.locCache, 0, cellN);
            s.hAuxFl.put(cb, aq.fluidLevel, 0, cellN);
            s.hAuxFt.put(cb, aq.fluidTypeId, 0, cellN);
        }
        // Async uploads on the slot's in-order queue (they order after the corner
        // NDRange — harmless — and before the decide NDRange — required). The staging
        // buffers stay exclusively this batch's until InFlight.release().
        s.hAuxInts.position(0).limit(K * AUX_INTS);
        program.writeBufferAsync(s.queue, s.auxIntsMem, s.hAuxInts);
        s.hAuxLongs.position(0).limit(K * 2);
        program.writeBufferAsync(s.queue, s.auxLongsMem, s.hAuxLongs);
        s.hAuxLoc.position(0).limit(K * cellN);
        program.writeBufferAsync(s.queue, s.auxLocMem, s.hAuxLoc);
        s.hAuxFl.position(0).limit(K * cellN);
        program.writeBufferAsync(s.queue, s.auxFlMem, s.hAuxFl);
        s.hAuxFt.position(0).limit(K * cellN);
        program.writeBufferAsync(s.queue, s.auxFtMem, s.hAuxFt);

        // Args 0..7 (decide noise state) bound once per slot kernel in initDecide.
        int a2 = 8;
        decideProgram.setArgPointer(s.decideKernel, a2++, s.outMem);        // device-resident corners
        decideProgram.setArgPointer(s.decideKernel, a2++, s.originsMem);    // same origins table
        decideProgram.setArgInt(s.decideKernel, a2++, rootCount);           // M
        decideProgram.setArgInt(s.decideKernel, a2++, cornerN);
        decideProgram.setArgInt(s.decideKernel, a2++, dimX);
        decideProgram.setArgInt(s.decideKernel, a2++, dimZ);
        decideProgram.setArgInt(s.decideKernel, a2++, strx);                // sx
        decideProgram.setArgInt(s.decideKernel, a2++, stry);                // sy
        decideProgram.setArgInt(s.decideKernel, a2++, strz);                // sz
        decideProgram.setArgInt(s.decideKernel, a2++, fullX);
        decideProgram.setArgInt(s.decideKernel, a2++, fullY);
        decideProgram.setArgInt(s.decideKernel, a2++, fullZ);
        decideProgram.setArgInt(s.decideKernel, a2++, fullN);
        decideProgram.setArgInt(s.decideKernel, a2++, total);
        decideProgram.setArgPointer(s.decideKernel, a2++, s.auxIntsMem);
        decideProgram.setArgPointer(s.decideKernel, a2++, s.auxLongsMem);
        decideProgram.setArgPointer(s.decideKernel, a2++, s.auxLocMem);
        decideProgram.setArgPointer(s.decideKernel, a2++, s.auxFlMem);
        decideProgram.setArgPointer(s.decideKernel, a2++, s.auxFtMem);
        decideProgram.setArgInt(s.decideKernel, a2++, cellN);
        decideProgram.setArgDouble(s.decideKernel, a2++, flowingSim);
        decideProgram.setArgPointer(s.decideKernel, a2, s.idsMem);
        // Z-RUN decomposition: one work-item per sz-block z-cell run (fullZ =
        // (dimZ-1)*strz makes total/strz exact) — the kernel derives totalRuns
        // from the same (total, sz) args.
        decideProgram.enqueue1DAsync(s.queue, s.decideKernel, CLProgram.roundUpGlobal(total / strz), evtOut);
        // Async readback of the K*fullN id bytes into the slot's map-once pinned
        // staging (mirrors the corner readback at the end of doEnqueue). In-order
        // queue: it orders after the decide NDRange above, and the corner readback
        // event — enqueued last — implies it finished EXECUTING; its own event
        // (evtOut[1]) lets InFlight.ids() verify it terminated normally (a DMA that
        // aborts mid-queue must not let stale slot bytes ship as terrain).
        s.hIds.clear();
        s.hIds.limit(total);
        long[] idsEvt = new long[1];
        decideProgram.enqueueReadAsync(s.queue, s.idsMem, s.hIds, idsEvt);
        evtOut[1] = idsEvt[0];
        return new int[]{present, fullN};
    }

    @Override
    public void close() {
        lifecycleLock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            long db = colDedupBatches.get();
            if (db > 0) {
                long cc = colDedupCols.get(), cu = colDedupUnique.get();
                LOGGER.info("[SuperChunk-GPU] [batch] column dedup: {} batches, columns {} -> {} distinct "
                                + "({}% of the corner kernel's column-evaluations removed).",
                        db, cc, cu, String.format("%.1f", 100.0 * (cc - cu) / cc));
            }
            // Reclaim the ring. Under the documented shutdown ordering (the owning
            // batcher joins its drainer+completer BEFORE closing the dispatcher) every
            // slot is already back in the pool; the bounded poll below only matters on
            // abnormal paths (a timed-out join). We free ALL slots regardless — any
            // straggler InFlight sees `closed` under the read lock and skips native use.
            int reclaimed = 0;
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (reclaimed < slots.length && System.nanoTime() < deadline) {
                Slot s = freeSlots.poll();
                if (s != null) {
                    reclaimed++;
                } else {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (reclaimed < slots.length) {
                LOGGER.warn("[SuperChunk-GPU] [batch] close(): only {}/{} pipeline slots reclaimed — "
                        + "freeing anyway (in-flight work was already failed by the batcher).",
                        reclaimed, slots.length);
            }
            for (Slot s : slots) {
                if (s != null) {
                    s.free();
                }
            }
            // Shared decide resources (slot decide kernels were freed in s.free()).
            for (int i = 0; i < 8; i++) {
                CLProgram.releaseMem(decideNoiseMems[i]);
                decideNoiseMems[i] = NULL;
            }
            if (decideProgram != null) {
                try {
                    decideProgram.close();
                } catch (Throwable ignored) {
                }
                decideProgram = null;
            }
            decidePlan = null;
            // Shared climate-chain resources (slot climate kernels were freed in s.free()).
            climChainReady = false;
            for (int i = 0; i < 8; i++) {
                CLProgram.releaseMem(climNoiseMems[i]);
                climNoiseMems[i] = NULL;
            }
            if (climProgram != null) {
                try {
                    climProgram.close();
                } catch (Throwable ignored) {
                }
                climProgram = null;
            }
            climAttachedTo = null;
            CLProgram.releaseMem(noiseDescMem);
            CLProgram.releaseMem(noiseFactorsMem);
            CLProgram.releaseMem(nPermMem);
            CLProgram.releaseMem(nActiveMem);
            CLProgram.releaseMem(nXoMem);
            CLProgram.releaseMem(nYoMem);
            CLProgram.releaseMem(nZoMem);
            CLProgram.releaseMem(nAmpMem);
            program.close();
            OpenCLBackend.unregisterResourceOwner(this);
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    // ----------------------------------------------------------------------
    // Slot: one pipeline stage's private queue + kernels + buffers.
    // ----------------------------------------------------------------------

    private final class Slot {
        final long queue;
        final long kernel;
        long decideKernel = NULL;
        // CLIMATE CHAIN: per-slot cloned kernel + buffers (slot-exclusive, grow-only).
        long climKernel = NULL;
        long climOriginsMem = NULL;
        int climOriginsCapacityInts = 0;
        IntBuffer hClimOrigins;
        long climOutMem = NULL;
        int climOutCapacityElems = 0;
        long climPinnedMem = NULL;
        long climPinnedAddr = NULL;
        long climPinnedBytes = 0;
        FloatBuffer hClimOutF;
        DoubleBuffer hClimOutD;

        // COLUMN DEDUP (see COL_DEDUP): the distinct-column corner kernel + the fan-out
        // copy, plus their slot-exclusive tables. Left NULL when dedup is unavailable.
        long uniqKernel = NULL;
        long expandKernel = NULL;
        long colXZMem = NULL;          // 2*U ints (x,z of each distinct column)
        int colXZCapacityInts = 0;
        long colIdxMem = NULL;         // K*dimX*dimZ ints (cell column -> distinct index)
        int colIdxCapacityInts = 0;
        long uniqMem = NULL;           // M*U*dimY reals
        long uniqCapacityElems = 0;
        IntBuffer hColXZ;
        IntBuffer hColIdx;
        // Open-addressed (x,z) -> distinct-index map, drainer-thread-only, grow-only.
        long[] colKeys;
        int[] colVals;

        // Growable per-dispatch buffers (slot-exclusive).
        long originsMem = NULL;        // K*3 ints, re-uploaded each batch
        int originsCapacityInts = 0;
        long outMem = NULL;            // K*M*n reals
        int outCapacityElems = 0;

        // Reusable host staging buffers (grown alongside the device buffers).
        IntBuffer hOrigins;
        FloatBuffer hOutF;
        DoubleBuffer hOutD;
        // Pinned readback staging (CL_MEM_READ_WRITE | CL_MEM_ALLOC_HOST_PTR), mapped once and
        // kept mapped: the device->host readback of the whole K*M*n buffer DMAs into pinned
        // host memory (~2x bandwidth vs pageable on the discrete GPU). hOutF/hOutD are VIEWS
        // into pinnedAddr, so the readback + per-slice copy code reads it directly.
        long pinnedMem = NULL;
        long pinnedAddr = NULL;
        long pinnedBytes = 0;

        // COMPACT-IDS per-batch aux device buffers (growable, slot-exclusive).
        long auxIntsMem = NULL;                // K * AUX_INTS ints
        long auxLongsMem = NULL;               // K * 2 longs (ore seed lo/hi)
        long auxLocMem = NULL;                 // K * cellN longs
        long auxFlMem = NULL;                  // K * cellN ints
        long auxFtMem = NULL;                  // K * cellN ints
        int auxCapacityChunks = 0;
        int auxCellCapacity = 0;               // capacity in K*cellN cells
        // 1-byte/block id output: the kernel writes a device-local buffer, which is
        // async-read into map-once pinned staging (hIds) right after the decide NDRange —
        // the same pattern as the corner buffer (outMem/pinnedMem). The previous design
        // (kernel writes ALLOC_HOST_PTR, completer blocking-maps it per batch) cost
        // ~7 ms/batch on the completer thread (NVIDIA shadow-copy map, device-serial too)
        // and was the pipeline's dominant serial cost.
        long idsMem = NULL;
        long idsCapacityBytes = 0;
        long idsPinnedMem = NULL;
        long idsPinnedAddr = NULL;
        long idsPinnedBytes = 0;
        java.nio.ByteBuffer hIds;   // view into idsPinnedAddr (kept mapped)
        // reusable host staging for the aux uploads
        IntBuffer hAuxInts;
        LongBuffer hAuxLongs;
        LongBuffer hAuxLoc;
        IntBuffer hAuxFl;
        IntBuffer hAuxFt;

        Slot(boolean profiling) {
            long q = program.createQueue(profiling);
            long k = NULL;
            try {
                k = program.kernel(kernelName);
                // Bind the 8 immutable noise-buffer pointers (args 0..7) ONCE per slot
                // kernel: they never change for the dispatcher's lifetime, and the slot
                // kernel is only ever enqueued by the thread owning the slot, so
                // clSetKernelArg state persists safely across enqueues.
                program.setArgPointer(k, 0, noiseDescMem);
                program.setArgPointer(k, 1, noiseFactorsMem);
                program.setArgPointer(k, 2, nPermMem);
                program.setArgPointer(k, 3, nActiveMem);
                program.setArgPointer(k, 4, nXoMem);
                program.setArgPointer(k, 5, nYoMem);
                program.setArgPointer(k, 6, nZoMem);
                program.setArgPointer(k, 7, nAmpMem);
            } catch (Throwable t) {
                CLProgram.releaseKernel(k);
                CLProgram.releaseQueue(q);
                throw t;
            }
            this.queue = q;
            this.kernel = k;
            if (colDedupAvailable) {
                try {
                    uniqKernel = program.kernel("df_batch_lattice_uniqcol");
                    for (int i = 0; i < 8; i++) {
                        program.setArgPointer(uniqKernel, i, switch (i) {
                            case 0 -> noiseDescMem; case 1 -> noiseFactorsMem; case 2 -> nPermMem;
                            case 3 -> nActiveMem;   case 4 -> nXoMem;          case 5 -> nYoMem;
                            case 6 -> nZoMem;       default -> nAmpMem;
                        });
                    }
                    expandKernel = program.kernel("sc_expand_columns");
                } catch (Throwable t) {
                    // Never fatal: the slot simply keeps the single-kernel corner path.
                    CLProgram.releaseKernel(uniqKernel);
                    CLProgram.releaseKernel(expandKernel);
                    uniqKernel = NULL;
                    expandKernel = NULL;
                    LOGGER.warn("[SuperChunk-GPU] [batch] could not clone the column-dedup kernels for a slot.", t);
                }
            }
        }

        /**
         * Fills {@link #hColXZ} / {@link #hColIdx} for this batch and returns the number of
         * DISTINCT columns, or -1 if the tables could not be built. Drainer-thread only.
         */
        int buildColumnTables(int[] origins, int K, int strx, int strz, int dimX, int dimZ) {
            final int plane = dimX * dimZ;
            final int cols = K * plane;
            if (cols <= 0) {
                return -1;
            }
            int cap = Integer.highestOneBit(Math.max(16, cols * 2 - 1)) << 1;   // >= 2x load factor
            if (colKeys == null || colKeys.length < cap) {
                colKeys = new long[cap];
                colVals = new int[cap];
            }
            java.util.Arrays.fill(colKeys, 0, cap, Long.MIN_VALUE);
            ensureColCapacity(cols);
            final int mask = cap - 1;
            int u = 0;
            hColIdx.clear();
            hColXZ.clear();
            for (int c = 0; c < K; c++) {
                final int ox = origins[c * 3], oz = origins[c * 3 + 2];
                for (int ix = 0; ix < dimX; ix++) {
                    final int xx = ox + ix * strx;
                    for (int iz = 0; iz < dimZ; iz++) {
                        final int zz = oz + iz * strz;
                        final long key = (((long) xx) << 32) | (zz & 0xFFFFFFFFL);
                        int h = (int) ((key ^ (key >>> 32)) * 0x9E3779B1L) & mask;
                        int idx;
                        while (true) {
                            long cur = colKeys[h];
                            if (cur == Long.MIN_VALUE) {
                                colKeys[h] = key;
                                idx = colVals[h] = u++;
                                hColXZ.put(xx).put(zz);
                                break;
                            }
                            if (cur == key) {
                                idx = colVals[h];
                                break;
                            }
                            h = (h + 1) & mask;
                        }
                        hColIdx.put(idx);
                    }
                }
            }
            hColXZ.flip();
            hColIdx.flip();
            return u;
        }

        /** Grows the column tables (host staging + device buffers) for {@code cols} columns. */
        void ensureColCapacity(int cols) {
            if (cols * 2 > colXZCapacityInts) {
                CLProgram.releaseMem(colXZMem);
                colXZMem = NULL;
                colXZCapacityInts = 0;
                colXZMem = program.createReusableInputBuffer((long) cols * 2 * Integer.BYTES);
                if (hColXZ != null) {
                    MemoryUtil.memFree(hColXZ);
                    hColXZ = null;
                }
                hColXZ = MemoryUtil.memAllocInt(cols * 2);
                colXZCapacityInts = cols * 2;
            }
            if (cols > colIdxCapacityInts) {
                CLProgram.releaseMem(colIdxMem);
                colIdxMem = NULL;
                colIdxCapacityInts = 0;
                colIdxMem = program.createReusableInputBuffer((long) cols * Integer.BYTES);
                if (hColIdx != null) {
                    MemoryUtil.memFree(hColIdx);
                    hColIdx = null;
                }
                hColIdx = MemoryUtil.memAllocInt(cols);
                colIdxCapacityInts = cols;
            }
        }

        /** Grows the distinct-column corner buffer to {@code elems} reals. */
        void ensureUniqCapacity(long elems) {
            if (elems <= uniqCapacityElems) {
                return;
            }
            CLProgram.releaseMem(uniqMem);
            uniqMem = NULL;
            uniqCapacityElems = 0;
            uniqMem = program.createReadWriteBuffer(elems * realBytes);
            uniqCapacityElems = elems;
        }

        void ensureOriginsCapacity(int K) {
            int neededInts = K * 3;
            if (neededInts <= originsCapacityInts) {
                return;
            }
            // Null the field + reset capacity BEFORE creating the replacement: if the
            // create throws (e.g. clCreateBuffer fails on VRAM exhaustion -> CLException),
            // the field is left holding a NULL (safely re-releasable) handle rather than
            // the OLD, already-freed handle — otherwise the next grow path would
            // double-release the stale handle (SIGSEGV).
            CLProgram.releaseMem(originsMem);
            originsMem = NULL;
            originsCapacityInts = 0;
            originsMem = program.createReusableInputBuffer((long) neededInts * Integer.BYTES);
            if (hOrigins != null) {
                MemoryUtil.memFree(hOrigins);
                hOrigins = null;
            }
            hOrigins = MemoryUtil.memAllocInt(neededInts);
            originsCapacityInts = neededInts;
        }

        void ensureOutCapacity(int outElems) {
            if (outElems <= outCapacityElems) {
                return;
            }
            // See ensureOriginsCapacity: null-before-create so a create-time throw can
            // never leave an already-freed handle behind.
            CLProgram.releaseMem(outMem);
            outMem = NULL;
            outCapacityElems = 0;
            // READ_WRITE, not createOutputBuffer(CL_MEM_WRITE_ONLY): the chained decide
            // kernel binds this same buffer as `__global const double* corners` and READS it
            // (see enqueueDecide's first setArgPointer). Reading a WRITE_ONLY buffer inside a
            // kernel is undefined behaviour — CLProgram.createOutputBuffer's own javadoc says
            // so, and GpuFusedInterpolator already switched to READ_WRITE for exactly this
            // reason. Relaxing the access hint cannot change any value.
            outMem = program.createReadWriteBuffer((long) outElems * realBytes);
            freeHostOut();
            // Pinned host staging for the readback DMA (see field comment). Kept mapped.
            long bytes = (long) outElems * realBytes;
            pinnedMem = program.createHostReadWriteBuffer(bytes);
            pinnedAddr = program.mapRead(queue, pinnedMem, bytes);
            pinnedBytes = bytes;
            if (fp32) {
                hOutF = MemoryUtil.memFloatBuffer(pinnedAddr, outElems);
            } else {
                hOutD = MemoryUtil.memDoubleBuffer(pinnedAddr, outElems);
            }
            outCapacityElems = outElems;
        }

        void freeHostOut() {
            // hOutF/hOutD are VIEWS into the pinned mapping (not owned allocations) -> just
            // drop the refs; the backing host memory is released by unmapping + releasing
            // pinnedMem.
            hOutF = null;
            hOutD = null;
            if (pinnedAddr != NULL) {
                program.unmap(queue, pinnedMem, pinnedAddr, pinnedBytes);
                pinnedAddr = NULL;
            }
            if (pinnedMem != NULL) {
                CLProgram.releaseMem(pinnedMem);
                pinnedMem = NULL;
            }
            pinnedBytes = 0;
        }

        void ensureAuxCapacity(int K, int cellN) {
            if (K > auxCapacityChunks) {
                // null-before-create pattern (see ensureOriginsCapacity).
                CLProgram.releaseMem(auxIntsMem);
                auxIntsMem = NULL;
                CLProgram.releaseMem(auxLongsMem);
                auxLongsMem = NULL;
                auxCapacityChunks = 0;
                auxIntsMem = decideProgram.createReusableInputBuffer((long) K * AUX_INTS * Integer.BYTES);
                auxLongsMem = decideProgram.createReusableInputBuffer((long) K * 2 * Long.BYTES);
                if (hAuxInts != null) {
                    MemoryUtil.memFree(hAuxInts);
                    hAuxInts = null;
                }
                if (hAuxLongs != null) {
                    MemoryUtil.memFree(hAuxLongs);
                    hAuxLongs = null;
                }
                hAuxInts = MemoryUtil.memAllocInt(K * AUX_INTS);
                hAuxLongs = MemoryUtil.memAllocLong(K * 2);
                auxCapacityChunks = K;
            }
            long neededCells = (long) K * cellN;
            if (neededCells > auxCellCapacity) {
                CLProgram.releaseMem(auxLocMem);
                auxLocMem = NULL;
                CLProgram.releaseMem(auxFlMem);
                auxFlMem = NULL;
                CLProgram.releaseMem(auxFtMem);
                auxFtMem = NULL;
                auxCellCapacity = 0;
                auxLocMem = decideProgram.createReusableInputBuffer(neededCells * Long.BYTES);
                auxFlMem = decideProgram.createReusableInputBuffer(neededCells * Integer.BYTES);
                auxFtMem = decideProgram.createReusableInputBuffer(neededCells * Integer.BYTES);
                if (hAuxLoc != null) {
                    MemoryUtil.memFree(hAuxLoc);
                    hAuxLoc = null;
                }
                if (hAuxFl != null) {
                    MemoryUtil.memFree(hAuxFl);
                    hAuxFl = null;
                }
                if (hAuxFt != null) {
                    MemoryUtil.memFree(hAuxFt);
                    hAuxFt = null;
                }
                hAuxLoc = MemoryUtil.memAllocLong((int) neededCells);
                hAuxFl = MemoryUtil.memAllocInt((int) neededCells);
                hAuxFt = MemoryUtil.memAllocInt((int) neededCells);
                auxCellCapacity = (int) neededCells;
            }
        }

        void ensureIdsCapacity(long bytes) {
            if (bytes <= idsCapacityBytes) {
                return;
            }
            if (bytes > Integer.MAX_VALUE) {
                // The sole caller pre-guards K*fullN <= MAX_VALUE; enforce it here too
                // so a future caller fails soft (decide sticky-disable) instead of
                // silently truncating the view below.
                throw new IllegalArgumentException("ids capacity overflows int: " + bytes);
            }
            // null-before-create pattern (see ensureOriginsCapacity).
            CLProgram.releaseMem(idsMem);
            idsMem = NULL;
            idsCapacityBytes = 0;
            // Device-local kernel output + map-once pinned staging for the async
            // readback (see the field comment; mirrors ensureOutCapacity exactly).
            idsMem = decideProgram.createOutputBuffer(bytes);
            freeIdsHost();
            idsPinnedMem = decideProgram.createHostReadWriteBuffer(bytes);
            idsPinnedAddr = decideProgram.mapRead(queue, idsPinnedMem, bytes);
            if (idsPinnedAddr == NULL) {
                // Fail soft via the enqueueDecide catch (decide sticky-disable) rather
                // than wrapping address 0 and SIGSEGV-ing on the first ids() copy.
                throw new IllegalStateException("ids pinned staging map returned NULL");
            }
            idsPinnedBytes = bytes;
            hIds = MemoryUtil.memByteBuffer(idsPinnedAddr, (int) bytes);
            idsCapacityBytes = bytes;
        }

        void freeIdsHost() {
            // hIds is a VIEW into the pinned mapping (not an owned allocation) -> just
            // drop the ref; the backing host memory is released by unmapping + releasing
            // idsPinnedMem (same contract as freeHostOut()).
            hIds = null;
            if (idsPinnedAddr != NULL) {
                decideProgram.unmap(queue, idsPinnedMem, idsPinnedAddr, idsPinnedBytes);
                idsPinnedAddr = NULL;
            }
            if (idsPinnedMem != NULL) {
                CLProgram.releaseMem(idsPinnedMem);
                idsPinnedMem = NULL;
            }
            idsPinnedBytes = 0;
        }

        void ensureClimOriginsCapacity(int K) {
            int neededInts = K * 3;
            if (neededInts <= climOriginsCapacityInts) {
                return;
            }
            // null-before-create pattern (see ensureOriginsCapacity).
            CLProgram.releaseMem(climOriginsMem);
            climOriginsMem = NULL;
            climOriginsCapacityInts = 0;
            climOriginsMem = program.createReusableInputBuffer((long) neededInts * Integer.BYTES);
            if (hClimOrigins != null) {
                MemoryUtil.memFree(hClimOrigins);
                hClimOrigins = null;
            }
            hClimOrigins = MemoryUtil.memAllocInt(neededInts);
            climOriginsCapacityInts = neededInts;
        }

        void ensureClimOutCapacity(int outElems) {
            if (outElems <= climOutCapacityElems) {
                return;
            }
            CLProgram.releaseMem(climOutMem);
            climOutMem = NULL;
            climOutCapacityElems = 0;
            climOutMem = program.createOutputBuffer((long) outElems * realBytes);
            freeClimHostOut();
            long bytes = (long) outElems * realBytes;
            climPinnedMem = program.createHostReadWriteBuffer(bytes);
            climPinnedAddr = program.mapRead(queue, climPinnedMem, bytes);
            climPinnedBytes = bytes;
            if (fp32) {
                hClimOutF = MemoryUtil.memFloatBuffer(climPinnedAddr, outElems);
            } else {
                hClimOutD = MemoryUtil.memDoubleBuffer(climPinnedAddr, outElems);
            }
            climOutCapacityElems = outElems;
        }

        void freeClimHostOut() {
            hClimOutF = null;
            hClimOutD = null;
            if (climPinnedAddr != NULL) {
                program.unmap(queue, climPinnedMem, climPinnedAddr, climPinnedBytes);
                climPinnedAddr = NULL;
            }
            if (climPinnedMem != NULL) {
                CLProgram.releaseMem(climPinnedMem);
                climPinnedMem = NULL;
            }
            climPinnedBytes = 0;
        }

        /** Frees every native resource this slot owns. Called only from close() (write lock). */
        void free() {
            CLProgram.releaseKernel(decideKernel);
            decideKernel = NULL;
            CLProgram.releaseKernel(climKernel);
            climKernel = NULL;
            CLProgram.releaseMem(climOriginsMem);
            climOriginsMem = NULL;
            if (hClimOrigins != null) {
                MemoryUtil.memFree(hClimOrigins);
                hClimOrigins = null;
            }
            try {
                freeClimHostOut();
            } catch (Throwable ignored) {
            }
            CLProgram.releaseMem(climOutMem);
            climOutMem = NULL;
            CLProgram.releaseMem(auxIntsMem);
            auxIntsMem = NULL;
            CLProgram.releaseMem(auxLongsMem);
            auxLongsMem = NULL;
            CLProgram.releaseMem(auxLocMem);
            auxLocMem = NULL;
            CLProgram.releaseMem(auxFlMem);
            auxFlMem = NULL;
            CLProgram.releaseMem(auxFtMem);
            auxFtMem = NULL;
            try {
                freeIdsHost();
            } catch (Throwable ignored) {
            }
            CLProgram.releaseMem(idsMem);
            idsMem = NULL;
            auxCapacityChunks = 0;
            auxCellCapacity = 0;
            idsCapacityBytes = 0;
            if (hAuxInts != null) {
                MemoryUtil.memFree(hAuxInts);
                hAuxInts = null;
            }
            if (hAuxLongs != null) {
                MemoryUtil.memFree(hAuxLongs);
                hAuxLongs = null;
            }
            if (hAuxLoc != null) {
                MemoryUtil.memFree(hAuxLoc);
                hAuxLoc = null;
            }
            if (hAuxFl != null) {
                MemoryUtil.memFree(hAuxFl);
                hAuxFl = null;
            }
            if (hAuxFt != null) {
                MemoryUtil.memFree(hAuxFt);
                hAuxFt = null;
            }
            CLProgram.releaseMem(originsMem);
            originsMem = NULL;
            if (hOrigins != null) {
                MemoryUtil.memFree(hOrigins);
                hOrigins = null;
            }
            try {
                freeHostOut();
            } catch (Throwable ignored) {
            }
            CLProgram.releaseMem(outMem);
            outMem = NULL;
            // Column dedup (no-ops when it was never armed).
            CLProgram.releaseMem(colXZMem);
            colXZMem = NULL;
            colXZCapacityInts = 0;
            CLProgram.releaseMem(colIdxMem);
            colIdxMem = NULL;
            colIdxCapacityInts = 0;
            CLProgram.releaseMem(uniqMem);
            uniqMem = NULL;
            uniqCapacityElems = 0;
            if (hColXZ != null) {
                MemoryUtil.memFree(hColXZ);
                hColXZ = null;
            }
            if (hColIdx != null) {
                MemoryUtil.memFree(hColIdx);
                hColIdx = null;
            }
            CLProgram.releaseKernel(uniqKernel);
            uniqKernel = NULL;
            CLProgram.releaseKernel(expandKernel);
            expandKernel = NULL;
            CLProgram.releaseKernel(kernel);
            CLProgram.releaseQueue(queue);
        }
    }
}
