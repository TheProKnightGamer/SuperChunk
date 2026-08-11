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
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * SuperChunk GPU — <b>LIVE DF fusion</b> for the per-chunk interpolated density
 * grids. Computes M density-function whole-chunk grids ({@code finalDensity} +
 * {@code initialDensityWithoutJaggedness}) in ONE multi-output dispatch instead of
 * M separate per-DF dispatches.
 *
 * <p>This is the multi-threaded, live counterpart of {@link GpuFusedProgram} (which
 * is the boot-time, single-shared-queue self-test glue). It compiles a list of AST
 * roots via {@link OpenCLAstEmitter#emitMulti} into ONE {@code df_batch_lattice_multi}
 * program (shared {@link NoiseRegistry} &rarr; cross-DF CSE), uploads the shared,
 * read-only noise state ONCE, and keeps the program ALIVE for the backend's lifetime
 * (never close+recompiles the same source — that would hit {@code CLProgramCache}'s
 * latent use-after-free).
 *
 * <p><b>Threading model (mirrors {@link GpuDensityFunction}).</b> Worldgen runs on
 * many {@code c2me-worker} threads. The {@code cl_context}, {@code cl_program} and
 * the read-only noise buffers are shared; each calling thread lazily gets its OWN
 * command queue, its OWN cloned {@code df_batch_lattice_multi} kernel and its OWN
 * reusable {@code M*n} output buffer via a {@link ThreadLocal}. No hot-path locking.
 * Every per-thread bundle is tracked and released by {@link #close()} (driven by
 * {@code OpenCLBackend.shutdown()} through the resource-owner registry).
 *
 * <p><b>Bit-identical.</b> Each n-length output slice {@code out[k*n + gid]} is
 * computed by the very same kernel math as the single-root {@code df_batch_lattice}
 * for member {@code k}, so a fused grid is bit-for-bit identical to that member's
 * per-DF {@code fillWholeGrid} (validated by {@code GpuVanillaParityTest}'s
 * {@code [fused-emit]} check). On any failure the caller falls back to the per-DF
 * whole-grid path.
 *
 * <p>Returns {@code null} on any compile failure (logged; never throws).
 */
public final class GpuFusedInterpolator implements AutoCloseable {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    private static final String NOISE_CL = "/superchunk/kernels/noise.cl";
    private static final String SUPPORT_CL = "/superchunk/kernels/dfc_support.cl";
    private static final String KERNEL_NAME = "df_batch_lattice_multi";

    // ----------------------------------------------------------------------
    // STAGE-1 full-field interpolation microbenchmark (purely ADDITIVE timing;
    // gated by -Dsuperchunk.gpu.fullFieldBench, default OFF). When on, right after
    // a chunk's fused cell-corner grids are computed (and left device-resident in
    // the per-thread corner buffer rt.outMem), we ALSO dispatch df_full_field_interp
    // to interpolate the FULL 16x16x384 block field per grid on the GPU, read it
    // back, time both, and DISCARD the result. The discarded field NEVER touches the
    // CPU consumption path, so terrain output is unchanged. See FullFieldBench.
    // ----------------------------------------------------------------------
    private static final String FULLFIELD_CL = "/superchunk/kernels/fullfield.cl";
    private static final String BENCH_KERNEL_NAME = "df_full_field_interp";
    // STAGE 2: the X->Y->Z (Mth.lerp3) cell-cache full-field kernel, a sibling in
    // fullfield.cl (same benchProgram). Only needed for on-device interp (the bench
    // measures the value-path kernel only), so it is cloned per-thread iff ENABLED.
    private static final String LERP3_KERNEL_NAME = "df_full_field_interp_lerp3";
    private static final boolean FULLFIELD_BENCH = Boolean.getBoolean("superchunk.gpu.fullFieldBench");
    // STAGE 2: the same df_full_field_interp kernel is needed when on-device interpolation
    // is on (it KEEPS the field instead of discarding it). FIELD_PROGRAM gates building the
    // interp program + the read-write corner buffer for EITHER consumer.
    private static final boolean FIELD_PROGRAM = FULLFIELD_BENCH || OnDeviceInterp.ENABLED;
    // The canonical full vanilla-chunk block field the bench/field measures (16 x 384 x 16).
    private static final int FULL_X = 16, FULL_Y = 384, FULL_Z = 16;
    /** Blocks in one full vanilla-chunk field ({@value #FULL_X}*{@value #FULL_Y}*{@value #FULL_Z} = 98304). */
    private static final int FULL_N = FULL_X * FULL_Y * FULL_Z;

    private final CLProgram program;
    /**
     * The {@code df_full_field_interp} program for the STAGE-1 bench, or {@code null}
     * when the bench flag is off or the program failed to build (bench silently
     * disabled — terrain is unaffected). Shared/refcounted via {@code CLProgramCache}
     * just like {@link #program}; released in {@link #close()}.
     */
    private final CLProgram benchProgram;
    private final boolean fp32;
    private final int realBytes;
    private final boolean useMapped;
    private final int rootCount;
    /** Ordered members: root index k corresponds to {@code members.get(k)}. */
    private final List<GpuDensityFunction> members;

    /**
     * Lazily-built identity map member {@link GpuDensityFunction} &rarr; root index {@code k}
     * (see {@link #rootIndexMap()}). Immutable once published; {@code volatile} for the
     * double-checked lazy init. Depends only on the immutable {@link #members}, so it is
     * built ONCE and shared read-only across chunks/threads.
     */
    private volatile java.util.Map<GpuDensityFunction, Integer> rootIndexMap;

    // Immutable noise-state device buffers (uploaded once, READ-ONLY -> shared).
    private final long noiseDescMem;
    private final long noiseFactorsMem;
    private final long nPermMem;
    private final long nActiveMem;
    private final long nXoMem;
    private final long nYoMem;
    private final long nZoMem;
    private final long nAmpMem;

    /** Lifecycle: once closed, threads must not allocate new per-thread bundles. */
    private volatile boolean closed = false;

    /**
     * Guards in-flight fills against {@link #close()}. {@link #fillAllGrids} and
     * {@link #dispatchAsync} hold the READ lock for their whole body — many run in
     * parallel with no mutual exclusion — while {@link #close()} takes the WRITE
     * lock after setting {@code closed}. The write lock waits for every active fill
     * to finish before any queue/kernel/buffer is released, so close() can never
     * free a worker thread's resources mid-fill (use-after-free).
     */
    private final java.util.concurrent.locks.ReadWriteLock fillLock =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    private final ThreadLocal<PerThread> threadLocal = ThreadLocal.withInitial(this::createPerThread);

    private final java.util.Set<PerThread> allThreads =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private GpuFusedInterpolator(CLProgram program, boolean fp32, int rootCount,
                                 List<GpuDensityFunction> members,
                                 long noiseDescMem, long noiseFactorsMem, long nPermMem, long nActiveMem,
                                 long nXoMem, long nYoMem, long nZoMem, long nAmpMem,
                                 CLProgram benchProgram) {
        this.program = program;
        this.benchProgram = benchProgram;
        this.fp32 = fp32;
        this.realBytes = fp32 ? Float.BYTES : Double.BYTES;
        dev.superchunk.gpu.GpuConfig cfg = OpenCLBackend.config();
        this.useMapped = cfg == null || cfg.mappedBuffers();
        this.rootCount = rootCount;
        this.members = List.copyOf(members);
        this.noiseDescMem = noiseDescMem;
        this.noiseFactorsMem = noiseFactorsMem;
        this.nPermMem = nPermMem;
        this.nActiveMem = nActiveMem;
        this.nXoMem = nXoMem;
        this.nYoMem = nYoMem;
        this.nZoMem = nZoMem;
        this.nAmpMem = nAmpMem;
        OpenCLBackend.registerResourceOwner(this);
    }

    /** Number of fused roots M (each occupies an n-length slice of the output). */
    public int rootCount() {
        return rootCount;
    }

    /** The ordered fused members: root index k corresponds to {@code members().get(k)}. */
    public List<GpuDensityFunction> members() {
        return members;
    }

    /**
     * Returns an IDENTITY map from each non-null fused member to its root index {@code k}
     * (0..{@code rootCount()}-1). Built once and cached ({@link #members} is immutable), so
     * chunk-gen callers (e.g. {@code OnDeviceInterp.bindGroup}) reuse ONE shared read-only
     * map instead of rebuilding M entries per chunk. Integer values 0..M are in the
     * cached-{@link Integer} range (no boxing garbage). The returned map is read-only; do
     * not mutate it.
     */
    public java.util.Map<GpuDensityFunction, Integer> rootIndexMap() {
        java.util.Map<GpuDensityFunction, Integer> m = rootIndexMap;
        if (m == null) {
            synchronized (this) {
                m = rootIndexMap;
                if (m == null) {
                    java.util.IdentityHashMap<GpuDensityFunction, Integer> built =
                            new java.util.IdentityHashMap<>(Math.max(4, members.size() * 2));
                    for (int k = 0; k < members.size(); k++) {
                        GpuDensityFunction mem = members.get(k);
                        if (mem != null) {
                            built.put(mem, k);
                        }
                    }
                    m = built;
                    rootIndexMap = m;
                }
            }
        }
        return m;
    }

    /**
     * Compiles {@code roots} into one fused {@code df_batch_lattice_multi} program and
     * binds it to the ordered {@code members} (root index k &harr; {@code members.get(k)}).
     * {@code roots.size()} must equal {@code members.size()} and both must be &ge; 1.
     * Returns {@code null} on any failure (logged; never throws).
     */
    public static GpuFusedInterpolator compile(List<AstNode> roots, List<GpuDensityFunction> members) {
        if (!OpenCLBackend.isAvailable()) {
            return null;
        }
        if (roots == null || members == null || roots.size() != members.size() || roots.isEmpty()) {
            return null;
        }
        boolean fp32 = OpenCLBackend.isFp32();

        NoiseRegistry registry = new NoiseRegistry();
        OpenCLAstEmitter.MultiResult multi;
        try {
            multi = OpenCLAstEmitter.emitMulti(roots, registry);
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [fusion] emitMulti failed — fusion disabled (per-DF path remains).", t);
            return null;
        }
        if (multi.rootCount() != members.size()) {
            LOGGER.warn("[SuperChunk-GPU] [fusion] rootCount mismatch (emitMulti M={} vs members={}) — fusion disabled.",
                    multi.rootCount(), members.size());
            return null;
        }

        String noiseSrc = CLProgram.loadKernelSource(NOISE_CL);
        String supportSrc = CLProgram.loadKernelSource(SUPPORT_CL);
        if (noiseSrc == null || supportSrc == null) {
            LOGGER.warn("[SuperChunk-GPU] [fusion] could not load kernel resources — fusion disabled.");
            return null;
        }
        String fullSource = noiseSrc + "\n" + supportSrc + "\n" + multi.source();
        if (Boolean.getBoolean("superchunk.gpu.dumpKernel")) {
            LOGGER.info("[SuperChunk-GPU] === generated LIVE FUSED DF kernel (M={}) ===\n{}", multi.rootCount(), multi.source());
        }

        // Build via the shared program cache (in-memory dedup + on-disk binary cache).
        // Kept ALIVE for the backend's lifetime — never close+recompiled per chunk.
        CLProgram program = CLProgramCache.getOrBuild(fullSource, fp32 ? "-DUSE_FP32" : "");
        if (program == null) {
            LOGGER.warn("[SuperChunk-GPU] [fusion] fused kernel build failed — fusion disabled (per-DF path remains).");
            return null;
        }

        long descMem = NULL, factorsMem = NULL, permMem = NULL, activeMem = NULL,
                xoMem = NULL, yoMem = NULL, zoMem = NULL, ampMem = NULL;
        try {
            descMem = program.uploadInts(registry.descArray());
            factorsMem = program.uploadReals(fp32, registry.factorArray());
            permMem = program.uploadPerm(registry.permArray());
            activeMem = program.uploadInts(registry.activeArray());
            xoMem = program.uploadReals(fp32, registry.xoArray());
            yoMem = program.uploadReals(fp32, registry.yoArray());
            zoMem = program.uploadReals(fp32, registry.zoArray());
            ampMem = program.uploadReals(fp32, registry.ampArray());
            // STAGE 1/2: build the full-field interp program (shared/refcounted via
            // CLProgramCache) when EITHER the bench (discards) or on-device interp (keeps) is
            // on. A build failure NEVER fails the interpolator — benchProgram stays null and
            // both consumers are silently skipped (terrain unaffected -> CPU lerp).
            CLProgram benchProgram = FIELD_PROGRAM ? buildBenchProgram(fp32) : null;
            if (FIELD_PROGRAM) {
                if (benchProgram == null) {
                    LOGGER.warn("[SuperChunk-GPU] [fullfield] interp kernel did not build — "
                            + "{}{} disabled (terrain unaffected).",
                            FULLFIELD_BENCH ? "bench" : "", OnDeviceInterp.ENABLED ? (FULLFIELD_BENCH ? "+on-device-interp" : "on-device-interp") : "");
                } else if (OnDeviceInterp.ENABLED) {
                    LOGGER.info("[SuperChunk-GPU] [on-device-interp] {} — the full {}x{}x{} on-GPU interpolation "
                            + "will FEED the NoiseChunk fill (CPU updateForX/Y/Z lerp replaced for served roots).",
                            OnDeviceInterp.VERIFY ? "VERIFY ENABLED (returns vanilla)" : "ENABLED", FULL_X, FULL_Y, FULL_Z);
                } else {
                    LOGGER.info("[SuperChunk-GPU] [fullfield-bench] ENABLED — the full {}x{}x{} on-GPU interpolation "
                            + "will be timed per chunk and DISCARDED (terrain unchanged).", FULL_X, FULL_Y, FULL_Z);
                }
            }
            return new GpuFusedInterpolator(program, fp32, multi.rootCount(), members,
                    descMem, factorsMem, permMem, activeMem, xoMem, yoMem, zoMem, ampMem, benchProgram);
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [fusion] noise upload failed — fusion disabled (per-DF path remains).", t);
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

    /**
     * Dispatches {@code df_batch_lattice_multi} ONCE over the lattice
     * {@code (ox,oy,oz)+stride*(dim)} and splits the {@code M*n} result into
     * {@code outPerRoot}: root {@code k}'s whole-grid (length n) lands in
     * {@code outPerRoot[k]}. {@code outPerRoot.length} must equal {@link #rootCount()};
     * each slice is (re)used if already length n, else allocated.
     *
     * <p>Same gid&rarr;(xx,yy,zz) decode and the same 9 lattice scalars as
     * {@link GpuDensityFunction#fillWholeGrid}, so each slice is bit-identical to the
     * corresponding single-root {@code fillWholeGrid}. Returns {@code false} on any
     * failure (caller falls back to the per-DF whole-grid path; output undefined).
     */
    public boolean fillAllGrids(double[][] outPerRoot,
                                int ox, int oy, int oz,
                                int sx, int sy, int sz,
                                int dimX, int dimY, int dimZ) {
        if (outPerRoot == null || outPerRoot.length != rootCount) {
            return false;
        }
        int n = dimX * dimY * dimZ;
        if (n <= 0) {
            return false;
        }
        long total = (long) rootCount * n;
        if (total > Integer.MAX_VALUE) {
            return false;
        }
        // Hold the read lock for the entire body so close() (which takes the write
        // lock after setting `closed`) cannot release this thread's queue/kernel/
        // buffers mid-fill (use-after-free). Many fills run in parallel under the
        // shared read lock; only close() is exclusive.
        fillLock.readLock().lock();
        try {
            if (closed) {
                return false;
            }
            PerThread rt;
            try {
                rt = threadLocal.get();
            } catch (Throwable t) {
                LOGGER.warn("[SuperChunk-GPU] [fusion] per-thread GPU resource init failed — per-DF fallback.", t);
                return false;
            }
            if (rt == null) {
                return false;
            }
            try {
                rt.ensureCapacity(n);

                long kernel = rt.kernel;
                // Noise pointers (args 0..7) are bound once in the PerThread ctor; the
                // per-chunk scalars + output pointer start at index 8.
                int a = 8;
                program.setArgInt(kernel, a++, ox);
                program.setArgInt(kernel, a++, oy);
                program.setArgInt(kernel, a++, oz);
                program.setArgInt(kernel, a++, sx);   // strx
                program.setArgInt(kernel, a++, sy);   // stry
                program.setArgInt(kernel, a++, sz);   // strz
                program.setArgInt(kernel, a++, dimX); // dx
                program.setArgInt(kernel, a++, dimZ); // dz
                program.setArgPointer(kernel, a++, rt.outMem);
                program.setArgInt(kernel, a++, n);

                // One work-item per grid point; each writes all M roots (out[k*n + gid]).
                program.enqueue1DAsync(rt.queue, kernel, CLProgram.roundUpGlobal(n), null);

                // Single sync point: blocking readback, then split into per-root slices.
                rt.download(outPerRoot, n, rootCount);
                // STAGE-1 bench (no-op unless -Dsuperchunk.gpu.fullFieldBench): the corner
                // grids are now device-resident in rt.outMem AND the corner kernel is fully
                // host-synced by the blocking download above, so the additive full-field
                // interp can safely read them. Purely additive; result discarded.
                maybeRunFullFieldBench(rt, dimX, dimY, dimZ, sx, sy, sz);
                // STAGE 2: KEEP the full field (no-op unless on-device interp is on) — enqueue
                // it ASYNC from the same device-resident corners and register it for this chunk
                // so the NoiseChunk fill consumes it (waiting on the readback only at first use)
                // instead of running the CPU lerp.
                maybeDispatchFullFieldAsync(rt, dimX, dimY, dimZ, sx, sy, sz);
                return true;
            } catch (Throwable t) {
                LOGGER.warn("[SuperChunk-GPU] [fusion] fused fill failed — per-DF fallback for this chunk.", t);
                return false;
            }
        } finally {
            fillLock.readLock().unlock();
        }
    }

    /**
     * ASYNC (non-blocking) counterpart of {@link #fillAllGrids}: enqueues the fused
     * {@code df_batch_lattice_multi} kernel over the lattice AND a NON-blocking
     * readback of the {@code M*n} result into this thread's pending host buffer, then
     * returns IMMEDIATELY with a {@link PendingFusedFill} handle (the CL read event +
     * the buffer) — it does NOT wait. The caller does its own work, then later calls
     * {@link PendingFusedFill#complete} (waits on the event, splits into per-root
     * slices) when the densities are actually needed; any CPU work in between overlaps
     * the GPU dispatch/readback.
     *
     * <p>Same kernel + same readback bytes as {@link #fillAllGrids}, so the eventual
     * per-root slices are bit-identical to the blocking path. Returns {@code null} on
     * any failure (caller falls back to the blocking path; nothing is left in flight).
     */
    public PendingFusedFill dispatchAsync(int ox, int oy, int oz,
                                          int sx, int sy, int sz,
                                          int dimX, int dimY, int dimZ) {
        int n = dimX * dimY * dimZ;
        if (n <= 0) {
            return null;
        }
        long total = (long) rootCount * n;
        if (total > Integer.MAX_VALUE) {
            return null;
        }
        // Read lock for the whole body — see fillAllGrids(): keeps close() from
        // releasing this thread's GPU resources mid-dispatch (use-after-free).
        fillLock.readLock().lock();
        try {
            if (closed) {
                return null;
            }
            PerThread rt;
            try {
                rt = threadLocal.get();
            } catch (Throwable t) {
                LOGGER.warn("[SuperChunk-GPU] [fusion] per-thread GPU resource init failed — blocking fallback.", t);
                return null;
            }
            if (rt == null) {
                return null;
            }
            long ev = NULL;
            // ZERO-COPY async readback: when the corner buffer is host-resident (useMapped) AND
            // no on-device field/bench kernel reads it back on the GPU (no field program), the
            // async consume can MAP outMem in place instead of copying it into a pageable buffer.
            // With a field program on, a later kernel reads outMem on this queue — a map would
            // make that in-kernel read undefined — so that path keeps the copy, unchanged.
            boolean zeroCopyMap = useMapped && !FIELD_PROGRAM;
            long mappedPtr = NULL;
            long mappedBytes = 0;
            try {
                rt.ensureCapacity(n);
                // Only the copy path needs the pageable staging buffer; the map path reads
                // outMem in place, so hAsync* is not allocated there.
                if (!zeroCopyMap) {
                    rt.ensureAsyncHost((int) total);
                }

                long kernel = rt.kernel;
                // Noise pointers (args 0..7) are bound once in the PerThread ctor; the
                // per-chunk scalars + output pointer start at index 8.
                int a = 8;
                program.setArgInt(kernel, a++, ox);
                program.setArgInt(kernel, a++, oy);
                program.setArgInt(kernel, a++, oz);
                program.setArgInt(kernel, a++, sx);   // strx
                program.setArgInt(kernel, a++, sy);   // stry
                program.setArgInt(kernel, a++, sz);   // strz
                program.setArgInt(kernel, a++, dimX); // dx
                program.setArgInt(kernel, a++, dimZ); // dz
                program.setArgPointer(kernel, a++, rt.outMem);
                program.setArgInt(kernel, a++, n);

                // Enqueue the kernel, then either a NON-blocking MAP (zero-copy) or a NON-blocking
                // read of its output into the per-thread pending host buffer. In-order queue: the
                // map/read waits for the kernel. That event is the single deferred sync point.
                program.enqueue1DAsync(rt.queue, kernel, CLProgram.roundUpGlobal(n), null);
                long[] evOut = new long[1];
                if (zeroCopyMap) {
                    // ZERO-COPY: NON-blocking map of the already-host-resident corner buffer
                    // (mirrors the blocking download()'s mapRead, but non-blocking so the
                    // begin->first-use overlap is preserved). evOut completes when the corner
                    // kernel's writes AND the map are done — same deferred sync as the copy.
                    mappedBytes = total * realBytes;
                    mappedPtr = rt.mapAsyncRead((int) total, evOut);
                } else {
                    rt.enqueueAsyncRead((int) total, evOut);
                }
                ev = evOut[0];
                // STAGE 2 on-device interpolation (no-op unless the flag is on AND this is the
                // full vanilla-chunk extent): EAGERLY enqueue the two full-field kernels +
                // non-blocking readbacks on this SAME in-order queue, reading the corner buffer
                // the corner kernel just wrote (in-order => no re-dispatch of corners). The
                // NoiseChunk fill waits on the field readbacks lazily at first use, so the
                // field round-trip overlaps the whole chunk's CPU interpolation. Self-contained
                // best-effort: a field failure drains its own events and never aborts the corner
                // dispatch below.
                maybeDispatchFullFieldAsync(rt, dimX, dimY, dimZ, sx, sy, sz);
                // Submit the queued commands to the device now so the GPU works while the
                // host runs the CPU code between beginChunk and the first density use.
                program.flush(rt.queue);
                // Construct the pending handle FIRST — it becomes the single owner of the map.
                // Only AFTER it exists do we record the outstanding map on the thread, so if the
                // ctor ever threw the catch's invariant holds: rt.asyncMapAddr is still NULL, the
                // catch unmaps the local mappedPtr exactly once, and release() cannot double-unmap.
                PendingFusedFill pending =
                        new PendingFusedFill(this, rt, ev, mappedPtr, mappedBytes, n, sx, sy, sz, dimX, dimY, dimZ);
                // Record the outstanding map on the thread so a shutdown that never runs this
                // dispatch's complete()/discard() (it will observe `closed` under the read lock
                // and skip) can still unmap it in PerThread.release() — symmetric to how the
                // pageable buffer is drained + freed there. Set only after the pending exists; a
                // throw above leaves rt.asyncMapAddr NULL and the local mappedPtr is unmapped by the catch.
                if (mappedPtr != NULL) {
                    rt.asyncMapAddr = mappedPtr;
                    rt.asyncMapBytes = mappedBytes;
                }
                return pending;
            } catch (Throwable t) {
                LOGGER.warn("[SuperChunk-GPU] [fusion] async fused dispatch failed — blocking fallback for this chunk.", t);
                if (ev != NULL) {
                    // The map/read may already be in flight; drain it before reuse.
                    CLProgram.waitForEvent(ev);
                }
                if (mappedPtr != NULL) {
                    // Release the zero-copy map BEFORE the blocking fallback / next dispatch reuses
                    // outMem: the unmap is in-order on rt.queue, so it completes before any later
                    // kernel writes outMem. rt.asyncMapAddr was set only on the success path above,
                    // so it is still NULL here — release() will not double-unmap.
                    try {
                        program.unmap(rt.queue, rt.outMem, mappedPtr, mappedBytes);
                    } catch (Throwable ignored) {
                    }
                }
                if (ev != NULL) {
                    CLProgram.releaseEvent(ev);
                }
                return null;
            }
        } finally {
            fillLock.readLock().unlock();
        }
    }

    /**
     * Handle to one in-flight async fused dispatch: the CL read event + the
     * per-thread host buffer the readback lands in. Created by {@link #dispatchAsync}
     * and consumed exactly once on the SAME worker thread via {@link #complete} (use
     * the result) or {@link #discard} (abandon it). Idempotent: a second consume is a
     * no-op.
     */
    public static final class PendingFusedFill {
        private final GpuFusedInterpolator owner;
        private final PerThread rt;
        private long event;
        // ZERO-COPY async readback (useMapped && !FIELD_PROGRAM): the NON-blocking map of the
        // host-resident corner buffer rt.outMem this dispatch reads its per-root slices from
        // DIRECTLY (no copy into a pageable buffer). NULL when the copy path was used
        // (useMapped==false, OR a field/bench program reads outMem on the GPU after readback —
        // a map would make that in-kernel read undefined). complete()/discard() read from and
        // then unmap this pointer.
        private long mappedPtr;
        private long mappedBytes;
        private final int n;
        // Lattice the dispatch was issued for — kept so the STAGE-1 full-field bench in
        // complete() can interpolate to the full block field (it needs the strides/dims,
        // which n alone doesn't carry).
        private final int sx, sy, sz, dimX, dimY, dimZ;
        private boolean consumed;

        private PendingFusedFill(GpuFusedInterpolator owner, PerThread rt, long event,
                                 long mappedPtr, long mappedBytes, int n,
                                 int sx, int sy, int sz, int dimX, int dimY, int dimZ) {
            this.owner = owner;
            this.rt = rt;
            this.event = event;
            this.mappedPtr = mappedPtr;
            this.mappedBytes = mappedBytes;
            this.n = n;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.dimX = dimX;
            this.dimY = dimY;
            this.dimZ = dimZ;
        }

        /** The fused interpolator this pending dispatch belongs to. */
        public GpuFusedInterpolator fused() {
            return owner;
        }

        /** Per-grid point count n (== this chunk's gridLen) the dispatch was issued for. */
        public int n() {
            return n;
        }

        /**
         * Non-blocking instrumentation query: did the async readback already finish
         * (so {@link #complete} won't have to wait)? Measure this BEFORE {@link #complete}.
         */
        public boolean isComplete() {
            // Mirror complete()/discard(): take the SHARED read lock and re-check `closed`
            // so a measurement that races OpenCLBackend.shutdown() (which closes the owner
            // under the write lock, reclaiming the context + this read event) never calls
            // clGetEventInfo on a freed cl_event (use-after-free). When closed, report
            // "not complete" — the caller's complete() then takes its own closed path.
            owner.fillLock.readLock().lock();
            try {
                if (owner.closed) {
                    return false;
                }
                return CLProgram.eventComplete(event);
            } finally {
                owner.fillLock.readLock().unlock();
            }
        }

        /**
         * DEFERRED WAIT: blocks on the read event (only now), then splits the host
         * buffer into per-root slices ({@code outPerRoot[k][i] = out[k*n + i]}) exactly
         * as the blocking {@link PerThread#download} does — bit-identical results.
         * Returns {@code false} on any failure. Consumes the pending (idempotent).
         */
        public boolean complete(double[][] outPerRoot) {
            return complete(outPerRoot, false);
        }

        /**
         * As {@link #complete(double[][])}, but ALSO folds the async-hit probe into this
         * SAME read-lock cycle: right before the deferred wait it queries whether the
         * readback had already finished (the latency-fully-hidden case) and records it via
         * {@link GpuFillStats#recordAsyncServe}. The interpolated-grid path uses this instead
         * of a separate {@link #isComplete()} call, which otherwise takes a SECOND read-lock
         * cycle (and its own {@code clGetEventInfo}) purely to feed the same stat. The result
         * is bit-identical to {@link #complete(double[][])}.
         */
        public boolean completeRecordingAsyncServe(double[][] outPerRoot) {
            return complete(outPerRoot, true);
        }

        private boolean complete(double[][] outPerRoot, boolean recordAsyncServe) {
            if (consumed) {
                return false;
            }
            consumed = true;
            // Take the SHARED read lock so a deferred consume that races
            // OpenCLBackend.shutdown() serializes against close()'s write lock and
            // observes `closed` consistently: either we hold the read lock and close()
            // blocks until we finish (context stays valid through waitForEvent), or
            // close() already set `closed` and we skip the event entirely. Without this
            // the bare owner.closed read below is a TOCTOU (window only narrowed).
            owner.fillLock.readLock().lock();
            try {
                if (owner.closed) {
                    // The interpolator was closed (OpenCLBackend.shutdown()): the owning
                    // context and this read event were already reclaimed — touching the
                    // event would be a use-after-free. Skip; the event handle is reclaimed
                    // by clReleaseContext during context teardown, so nothing leaks. Any
                    // still-outstanding zero-copy map of outMem is unmapped by
                    // PerThread.release() under the write lock (via rt.asyncMapAddr), or
                    // reclaimed by clReleaseContext — so just drop our handle to it here.
                    event = NULL;
                    mappedPtr = NULL;
                    return false;
                }
                try {
                    // Fold the async-hit probe into this single lock cycle (interpolated path
                    // only): query completion BEFORE the wait for the "latency fully hidden"
                    // metric. Short-circuits so the biome path (recordAsyncServe=false) issues
                    // NO clGetEventInfo and NO second lock cycle.
                    boolean alreadyComplete = recordAsyncServe && CLProgram.eventComplete(event);
                    CLProgram.waitForEvent(event);
                    if (mappedPtr != NULL) {
                        // ZERO-COPY: read the per-root slices DIRECTLY from the mapped host-
                        // resident corner buffer (byte-for-byte the same conversion as the
                        // blocking download()), then release the map. The unmap is in-order on
                        // rt.queue, so it completes before this thread's next dispatchAsync
                        // rewrites outMem (one chunk per thread at a time).
                        try {
                            rt.splitMapped(outPerRoot, mappedPtr, n, owner.rootCount);
                        } finally {
                            rt.unmapAsyncRead(mappedPtr, mappedBytes);
                            mappedPtr = NULL;
                        }
                    } else {
                        rt.splitAsync(outPerRoot, n, owner.rootCount);
                    }
                    // STAGE-1 bench (no-op unless -Dsuperchunk.gpu.fullFieldBench): the
                    // corner grids are device-resident in rt.outMem and the corner kernel +
                    // async read are both complete (waitForEvent above), so the additive
                    // full-field interp can safely read them. Never throws; result discarded.
                    owner.maybeRunFullFieldBench(rt, dimX, dimY, dimZ, sx, sy, sz);
                    // STAGE 2: the full field was already enqueued ASYNC at dispatchAsync time
                    // (eager, overlapping this chunk's CPU work) — nothing to do here.
                    if (recordAsyncServe) {
                        GpuFillStats.recordAsyncServe(alreadyComplete);
                    }
                    return true;
                } catch (Throwable t) {
                    LOGGER.warn("[SuperChunk-GPU] [fusion] async fused completion failed — blocking fallback.", t);
                    return false;
                } finally {
                    CLProgram.releaseEvent(event);
                    event = NULL;
                }
            } finally {
                owner.fillLock.readLock().unlock();
            }
        }

        /**
         * Abandons an unconsumed dispatch (chunk abandoned, or completion took the
         * blocking path): waits for the in-flight read to finish — so the host buffer
         * is not being DMA'd into when the next dispatch reuses it — and releases the
         * event. Idempotent; never throws.
         */
        public void discard() {
            if (consumed) {
                return;
            }
            consumed = true;
            // Shared read lock: same close()-race protection as complete() above.
            owner.fillLock.readLock().lock();
            try {
                if (owner.closed) {
                    // The interpolator was closed (OpenCLBackend.shutdown()): the owning
                    // context and this read event were already reclaimed — touching the
                    // event would be a use-after-free. Skip; the event handle is reclaimed
                    // by clReleaseContext during context teardown, so nothing leaks. Any
                    // still-outstanding zero-copy map of outMem is unmapped by
                    // PerThread.release() (via rt.asyncMapAddr) or reclaimed by
                    // clReleaseContext — just drop our handle to it.
                    event = NULL;
                    mappedPtr = NULL;
                    return;
                }
                try {
                    CLProgram.waitForEvent(event);
                } catch (Throwable ignored) {
                }
                if (mappedPtr != NULL) {
                    // Release the zero-copy map so outMem is not left mapped when this thread's
                    // next dispatchAsync rewrites it (in-order on rt.queue). Clears
                    // rt.asyncMapAddr so release() won't try to unmap it again.
                    try {
                        rt.unmapAsyncRead(mappedPtr, mappedBytes);
                    } catch (Throwable ignored) {
                    }
                    mappedPtr = NULL;
                }
                CLProgram.releaseEvent(event);
                event = NULL;
            } finally {
                owner.fillLock.readLock().unlock();
            }
        }
    }

    /**
     * Handle to one in-flight async FULL-FIELD dispatch: the CL read events for the
     * value-path ({@code df_full_field_interp}) and, when it cloned, the lerp3 cell-cache
     * ({@code df_full_field_interp_lerp3}) readbacks into the per-thread pinned staging.
     * Created by {@link #maybeDispatchFullFieldAsync}, registered with {@link OnDeviceInterp},
     * and consumed exactly once on the SAME worker thread via {@link #complete} (wait, so the
     * pinned field is valid to read) or {@link #discard} (chunk abandoned). Idempotent.
     *
     * <p>Both reads are issued on ONE in-order queue with the lerp3 read last, so waiting on
     * either event drains the field; {@link #complete} waits on both for clarity. Mirrors
     * {@link PendingFusedFill}'s close()-race protection: takes the owner's shared read lock
     * and observes {@code closed} so a deferred consume that races
     * {@code OpenCLBackend.shutdown()} never touches an event whose context was reclaimed.
     */
    public static final class PendingFullField {
        private final GpuFusedInterpolator owner;
        private long evValue;
        private long evLerp3;
        private boolean consumed;

        private PendingFullField(GpuFusedInterpolator owner, long evValue, long evLerp3) {
            this.owner = owner;
            this.evValue = evValue;
            this.evLerp3 = evLerp3;
        }

        /**
         * DEFERRED WAIT: blocks on the field read events (only now), so the per-thread pinned
         * staging the caller registered is valid to read. Releases the events. Returns
         * {@code false} if the dispatch had no events or the owner was closed (caller then
         * falls back to the vanilla CPU lerp). Consumes the pending (idempotent).
         */
        public boolean complete() {
            if (consumed) {
                return false;
            }
            consumed = true;
            owner.fillLock.readLock().lock();
            try {
                if (owner.closed) {
                    // Context reclaimed by OpenCLBackend.shutdown() — touching the event would
                    // be a use-after-free. Skip; clReleaseContext reclaims the handle.
                    evValue = NULL;
                    evLerp3 = NULL;
                    return false;
                }
                boolean any = evValue != NULL || evLerp3 != NULL;
                try {
                    if (evLerp3 != NULL) {
                        CLProgram.waitForEvent(evLerp3);
                    }
                    if (evValue != NULL) {
                        CLProgram.waitForEvent(evValue);
                    }
                    return any;
                } finally {
                    CLProgram.releaseEvent(evValue);
                    CLProgram.releaseEvent(evLerp3);
                    evValue = NULL;
                    evLerp3 = NULL;
                }
            } finally {
                owner.fillLock.readLock().unlock();
            }
        }

        /**
         * Abandons an unconsumed field dispatch (chunk abandoned before any field use, or a
         * re-provision superseded it): waits for the in-flight reads to finish — so the pinned
         * staging is not being DMA'd into when the next dispatch reuses it — and releases the
         * events. Idempotent; never throws.
         */
        public void discard() {
            if (consumed) {
                return;
            }
            consumed = true;
            owner.fillLock.readLock().lock();
            try {
                if (owner.closed) {
                    evValue = NULL;
                    evLerp3 = NULL;
                    return;
                }
                try {
                    if (evLerp3 != NULL) {
                        CLProgram.waitForEvent(evLerp3);
                    }
                    if (evValue != NULL) {
                        CLProgram.waitForEvent(evValue);
                    }
                } catch (Throwable ignored) {
                }
                CLProgram.releaseEvent(evValue);
                CLProgram.releaseEvent(evLerp3);
                evValue = NULL;
                evLerp3 = NULL;
            } finally {
                owner.fillLock.readLock().unlock();
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Quiesce in-flight fills before releasing anything: the write lock waits for
        // every active fillAllGrids/dispatchAsync (read-lock holders) to finish, so we
        // never free a worker thread's queue/kernel/buffers underneath it
        // (use-after-free). New fills observe `closed` under the read lock and bail out.
        fillLock.writeLock().lock();
        try {
            synchronized (this) {
                for (PerThread rt : allThreads) {
                    rt.release();
                }
                allThreads.clear();
            }
            CLProgram.releaseMem(noiseDescMem);
            CLProgram.releaseMem(noiseFactorsMem);
            CLProgram.releaseMem(nPermMem);
            CLProgram.releaseMem(nActiveMem);
            CLProgram.releaseMem(nXoMem);
            CLProgram.releaseMem(nYoMem);
            CLProgram.releaseMem(nZoMem);
            CLProgram.releaseMem(nAmpMem);
            program.close();
            // Release the STAGE-1 bench program last (after every per-thread bench
            // kernel/queue above). No-op when the bench was off; refcounted by the cache.
            if (benchProgram != null) {
                benchProgram.close();
            }
            OpenCLBackend.unregisterResourceOwner(this);
        } finally {
            fillLock.writeLock().unlock();
        }
    }

    // ----------------------------------------------------------------------
    // STAGE-1 full-field interpolation microbenchmark (purely additive timing).
    // ----------------------------------------------------------------------

    /**
     * STAGE-1 ON-DEVICE-RESIDENCY BENCH (no-op unless {@code -Dsuperchunk.gpu.fullFieldBench}).
     * After a chunk's fused cell-corner grids have been computed into the per-thread,
     * device-resident corner buffer {@code rt.outMem} (and that corner dispatch has been
     * host-synced by the caller), dispatch {@code df_full_field_interp} to interpolate the
     * FULL {@value #FULL_X}x{@value #FULL_Z}x{@value #FULL_Y} block field for every density
     * grid ON the GPU, read it back, and DISCARD it.
     *
     * <p>Times the interp kernel via the CL profiling event ({@link CLProgram#eventProfile})
     * and the readback via {@code nanoTime} around the blocking read, accumulating into
     * {@link FullFieldBench}. The interp reads the corner buffer read-only and writes a
     * SEPARATE per-thread bench buffer — it never touches {@code rt.outMem} or the
     * caller's {@code outPerRoot}, so terrain output is byte-for-byte unchanged.
     *
     * <p>Scoped to the canonical full vanilla chunk ({@code fullX/fullY/fullZ ==
     * 16/384/16}, 98304 pts): this naturally excludes the biome quart-lattice fused
     * interpolator and any sub-region sampler. Never throws (a failure just skips this
     * chunk's measurement); runs under the caller's {@code fillLock} read lock.
     */
    private void maybeRunFullFieldBench(PerThread rt, int dimX, int dimY, int dimZ, int sx, int sy, int sz) {
        if (!FULLFIELD_BENCH || benchProgram == null || rt == null || rt.benchKernel == NULL) {
            return;
        }
        try {
            // Interpolated block extents: (corners-1) cells * cellSize blocks per axis.
            long fullX = (long) (dimX - 1) * sx;
            long fullY = (long) (dimY - 1) * sy;
            long fullZ = (long) (dimZ - 1) * sz;
            if (fullX != FULL_X || fullY != FULL_Y || fullZ != FULL_Z) {
                return;   // not the full vanilla chunk field — out of STAGE-1 scope.
            }
            int cornerN = dimX * dimY * dimZ;
            int fullN = (int) (fullX * fullY * fullZ);          // 98304
            long totalL = (long) rootCount * fullN;             // M work-items per output block
            if (totalL <= 0 || totalL > Integer.MAX_VALUE) {
                return;
            }
            int total = (int) totalL;
            rt.ensureBenchCapacity(total);

            long kernel = rt.benchKernel;
            int a = 0;
            program.setArgPointer(kernel, a++, rt.outMem);      // device-resident corner grid (read-only)
            program.setArgInt(kernel, a++, dimX);
            program.setArgInt(kernel, a++, dimY);
            program.setArgInt(kernel, a++, dimZ);
            program.setArgInt(kernel, a++, sx);
            program.setArgInt(kernel, a++, sy);
            program.setArgInt(kernel, a++, sz);
            program.setArgInt(kernel, a++, cornerN);
            program.setArgInt(kernel, a++, (int) fullX);
            program.setArgInt(kernel, a++, (int) fullY);
            program.setArgInt(kernel, a++, (int) fullZ);
            program.setArgInt(kernel, a++, fullN);
            program.setArgInt(kernel, a++, total);
            program.setArgPointer(kernel, a++, rt.benchOutMem); // separate bench output buffer

            // The "fill the GPU" dispatch: one work-item per output block per grid.
            long[] ev = new long[1];
            try {
                program.enqueue1DAsync(rt.benchQueue, kernel, CLProgram.roundUpGlobal(total), ev);
                // Finish so the profiling event holds the kernel's pure GPU compute time AND
                // so the readback wall-time below measures the transfer in isolation.
                program.finish(rt.benchQueue);
                long kernelNs = 0L;
                long[] prof = CLProgram.eventProfile(ev[0]);
                if (prof != null) {
                    kernelNs = prof[1];   // START->END device compute time
                }

                long r0 = System.nanoTime();
                rt.readBench(total);      // blocking device->host read of the full field (discarded)
                long readbackNs = System.nanoTime() - r0;

                FullFieldBench.record(kernelNs, readbackNs, (long) total * realBytes, rootCount);
            } finally {
                CLProgram.releaseEvent(ev[0]);
            }
        } catch (Throwable t) {
            // Purely additive timing — must NEVER disturb terrain. Swallow and skip.
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[SuperChunk-GPU] [fullfield-bench] dispatch failed — skipping this chunk (terrain unaffected).", t);
            }
        }
    }

    /**
     * STAGE 2 ON-DEVICE INTERPOLATION — <b>ASYNC + OVERLAPPED</b> (no-op unless
     * {@code -Dsuperchunk.gpu.onDeviceInterp} or its {@code .verify} variant). Counterpart
     * of {@link #maybeRunFullFieldBench} that KEEPS the result, but — unlike the old
     * synchronous {@code provideFullField} that blocked the worker on the full-field
     * round-trip every chunk — this only ENQUEUES the two field kernels + NON-blocking
     * readbacks on the SAME in-order corner queue ({@code rt.queue}) and returns
     * immediately. The corner buffer {@code rt.outMem} must already be device-resident:
     * <ul>
     *   <li>in the eager path it was written by the corner kernel enqueued just before on
     *       {@code rt.queue} (in-order =&gt; the field kernels see it);</li>
     *   <li>in the blocking {@link #fillAllGrids} path it was host-synced by the download;</li>
     *   <li>in the cross-chunk-batched path {@link #provideFullFieldFromHostCorners} uploaded
     *       the host corner grids into it first.</li>
     * </ul>
     * The CL read events are handed to a {@link PendingFullField} registered with
     * {@link OnDeviceInterp#setField}; the NoiseChunk fill WAITS on them lazily at the FIRST
     * {@code updateForZ}/cell-cache read, so the whole field round-trip overlaps the CPU
     * interpolation work between {@code beginChunk} and first use.
     *
     * <p>Reads the corner buffer read-only and writes SEPARATE per-thread field buffers.
     * Scoped to the canonical full vanilla chunk ({@code 16/384/16}); other extents
     * (sub-region samplers, biome quart lattice) are skipped and stay on the CPU lerp.
     * Runs under the caller's {@code fillLock} read lock, on the worker thread that also
     * consumes the field. Never throws — on any failure it drains any in-flight field read,
     * leaves the field unregistered, and the chunk uses the vanilla CPU lerp (terrain
     * unaffected).
     */
    private void maybeDispatchFullFieldAsync(PerThread rt, int dimX, int dimY, int dimZ, int sx, int sy, int sz) {
        if (!OnDeviceInterp.ENABLED || benchProgram == null || rt == null || rt.benchKernel == NULL) {
            return;
        }
        long evValue = NULL;
        long evLerp3 = NULL;
        try {
            long fullX = (long) (dimX - 1) * sx;
            long fullY = (long) (dimY - 1) * sy;
            long fullZ = (long) (dimZ - 1) * sz;
            if (fullX != FULL_X || fullY != FULL_Y || fullZ != FULL_Z) {
                return;   // not the full vanilla chunk field — stay on the CPU lerp.
            }
            int cornerN = dimX * dimY * dimZ;
            int fullN = (int) (fullX * fullY * fullZ);          // 98304
            long totalL = (long) rootCount * fullN;             // M roots
            if (totalL <= 0 || totalL > Integer.MAX_VALUE) {
                return;
            }
            int total = (int) totalL;
            rt.ensureFfCapacity(total);

            long kernel = rt.benchKernel;
            int a = 0;
            program.setArgPointer(kernel, a++, rt.outMem);      // device-resident corner grid (read-only)
            program.setArgInt(kernel, a++, dimX);
            program.setArgInt(kernel, a++, dimY);
            program.setArgInt(kernel, a++, dimZ);
            program.setArgInt(kernel, a++, sx);
            program.setArgInt(kernel, a++, sy);
            program.setArgInt(kernel, a++, sz);
            program.setArgInt(kernel, a++, cornerN);
            program.setArgInt(kernel, a++, (int) fullX);
            program.setArgInt(kernel, a++, (int) fullY);
            program.setArgInt(kernel, a++, (int) fullZ);
            program.setArgInt(kernel, a++, fullN);
            program.setArgInt(kernel, a++, total);
            program.setArgPointer(kernel, a++, rt.ffOutMem);    // separate field output buffer (KEPT)

            // Enqueue the value-path kernel + a NON-blocking read on the corner queue (in-order:
            // the read waits for the kernel, the kernel waits for the corner producer above it).
            program.enqueue1DAsync(rt.queue, kernel, CLProgram.roundUpGlobal(total), null);
            long[] evV = new long[1];
            rt.enqueueFfRead(total, evV);   // async device->pinned-host read of the Y->X->Z field
            evValue = evV[0];

            // STAGE 2 — SECOND field: the X->Y->Z (Mth.lerp3) cell-cache field, computed from
            // the SAME device-resident corner buffer (rt.outMem) by df_full_field_interp_lerp3,
            // into a SEPARATE output buffer + pinned staging. Serves the dominant finalDensity
            // cell-cache fill. Best-effort: if the lerp3 kernel didn't clone, leave its host
            // buffers null and the cell-cache fill stays on the CPU Mth.lerp3.
            if (rt.ffLerp3Kernel != NULL) {
                rt.ensureFfLerp3Capacity(total);
                long k3 = rt.ffLerp3Kernel;
                int b = 0;
                program.setArgPointer(k3, b++, rt.outMem);          // SAME corner grid (read-only)
                program.setArgInt(k3, b++, dimX);
                program.setArgInt(k3, b++, dimY);
                program.setArgInt(k3, b++, dimZ);
                program.setArgInt(k3, b++, sx);
                program.setArgInt(k3, b++, sy);
                program.setArgInt(k3, b++, sz);
                program.setArgInt(k3, b++, cornerN);
                program.setArgInt(k3, b++, (int) fullX);
                program.setArgInt(k3, b++, (int) fullY);
                program.setArgInt(k3, b++, (int) fullZ);
                program.setArgInt(k3, b++, fullN);
                program.setArgInt(k3, b++, total);
                program.setArgPointer(k3, b++, rt.ffLerp3OutMem);   // separate lerp3 field buffer (KEPT)
                program.enqueue1DAsync(rt.queue, k3, CLProgram.roundUpGlobal(total), null);
                long[] evL = new long[1];
                rt.enqueueFfLerp3Read(total, evL);   // async device->pinned-host read of the X->Y->Z field
                evLerp3 = evL[0];
            }

            // Submit so the GPU starts the field compute/readback while the worker runs the
            // corner-based CPU interpolation between here and the first field consumption.
            program.flush(rt.queue);

            // Hand the read events to a pending handle; the NoiseChunk fill waits on them
            // lazily at first use (OnDeviceInterp.ensureFieldReady). Register BOTH fields for
            // THIS chunk's pass (same thread); the lerp3 host buffers are null when the kernel
            // didn't clone -> sampleLerp3 returns NaN. Ownership of the events transfers to the
            // pending here, so null the locals — the catch must not also drain them (double-free).
            PendingFullField pending = new PendingFullField(this, evValue, evLerp3);
            evValue = NULL;
            evLerp3 = NULL;
            OnDeviceInterp.setField(this, fp32, rt.ffHostF, rt.ffHostD,
                    rt.ffLerp3Kernel != NULL ? rt.ffLerp3HostF : null,
                    rt.ffLerp3Kernel != NULL ? rt.ffLerp3HostD : null,
                    (int) fullX, (int) fullY, (int) fullZ, fullN, pending);
        } catch (Throwable t) {
            // Must NEVER disturb terrain: on any failure drain any in-flight field read (so the
            // pinned buffer isn't being DMA'd into when the next chunk reuses it) and leave the
            // field unregistered, so the NoiseChunk fill falls back to the vanilla CPU lerp.
            if (evLerp3 != NULL) {
                CLProgram.waitForEvent(evLerp3);
                CLProgram.releaseEvent(evLerp3);
            }
            if (evValue != NULL) {
                CLProgram.waitForEvent(evValue);
                CLProgram.releaseEvent(evValue);
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[SuperChunk-GPU] [on-device-interp] async field dispatch failed — vanilla CPU lerp this chunk.", t);
            }
        }
    }

    /**
     * STAGE 2 ON-DEVICE INTERPOLATION — cross-chunk-batched entry. When a chunk's
     * cell-corner grids were computed by the cross-chunk {@link GpuChunkBatcher} (and read
     * back to host into {@link GpuBatchStore}) rather than by this thread's per-chunk fused
     * dispatch, the corners are NOT device-resident on this worker. This uploads the host
     * corner grids ({@code cornerGrids}, the packed {@code rootCount*cornerN} doubles
     * bit-identical to the per-chunk {@code df_batch_lattice_multi} output) into this
     * thread's corner buffer {@code rt.outMem} and then runs the same async full-field
     * dispatch ({@link #maybeDispatchFullFieldAsync}), so on-device interpolation coexists
     * with cross-chunk batching. The upload is tiny (~tens of KB) next to the field readback.
     *
     * <p>Returns {@code true} when the async field dispatch was ATTEMPTED (corners uploaded and
     * {@link #maybeDispatchFullFieldAsync} invoked) — NOT a guarantee the field was registered:
     * that inner call is best-effort and may silently skip (leaving the chunk on the CPU lerp)
     * without changing this return value. Returns {@code false} on an early failure or a
     * non-full-chunk extent (the chunk stays on the vanilla CPU lerp). The sole caller ignores
     * the return value. Never throws.
     */
    public boolean provideFullFieldFromHostCorners(double[] cornerGrids,
                                                   int dimX, int dimY, int dimZ,
                                                   int sx, int sy, int sz) {
        if (!OnDeviceInterp.ENABLED || benchProgram == null || cornerGrids == null) {
            return false;
        }
        long fullX = (long) (dimX - 1) * sx;
        long fullY = (long) (dimY - 1) * sy;
        long fullZ = (long) (dimZ - 1) * sz;
        if (fullX != FULL_X || fullY != FULL_Y || fullZ != FULL_Z) {
            return false;   // not the full vanilla chunk field.
        }
        int cornerN = dimX * dimY * dimZ;
        long totalL = (long) rootCount * cornerN;
        if (totalL <= 0 || totalL > Integer.MAX_VALUE || cornerGrids.length != (int) totalL) {
            return false;
        }
        int total = (int) totalL;
        // Read lock for the whole body — see fillAllGrids(): keeps close() from releasing
        // this thread's GPU resources mid-dispatch (use-after-free).
        fillLock.readLock().lock();
        try {
            if (closed) {
                return false;
            }
            PerThread rt;
            try {
                rt = threadLocal.get();
            } catch (Throwable t) {
                return false;
            }
            if (rt == null || rt.benchKernel == NULL) {
                return false;
            }
            try {
                rt.ensureCapacity(cornerN);
                // Make the batched corners device-resident in rt.outMem (blocking write on the
                // in-order corner queue), then run the async field dispatch reading them.
                rt.uploadCorners(cornerGrids, total);
                maybeDispatchFullFieldAsync(rt, dimX, dimY, dimZ, sx, sy, sz);
                return true;
            } catch (Throwable t) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("[SuperChunk-GPU] [on-device-interp] batched-corner field provide failed — vanilla CPU lerp this chunk.", t);
                }
                return false;
            }
        } finally {
            fillLock.readLock().unlock();
        }
    }

    /**
     * STAGE 2 PREFETCH POOL — the {@code M*fullN} field element count for the canonical full
     * vanilla chunk, or {@code -1} when {@code (dims,strides)} is NOT that extent (so the
     * prefetch skips pooled production and the chunk uses the per-chunk path). Lets the
     * prefetch size a pooled holder before producing into it.
     */
    public int fullFieldTotalOrNeg(int dimX, int dimY, int dimZ, int sx, int sy, int sz) {
        long fx = (long) (dimX - 1) * sx;
        long fy = (long) (dimY - 1) * sy;
        long fz = (long) (dimZ - 1) * sz;
        if (fx != FULL_X || fy != FULL_Y || fz != FULL_Z) {
            return -1;
        }
        long t = (long) rootCount * FULL_N;
        return (t > 0 && t <= Integer.MAX_VALUE) ? (int) t : -1;
    }

    /**
     * STAGE 2 PREFETCH POOL — <b>SYNCHRONOUS</b> production of both full fields from host
     * corner grids INTO a caller-provided REUSED pooled {@link OnDeviceFieldPool.Holder}
     * (zero per-chunk field allocation). Uploads the batched corners to this thread's device
     * corner buffer, runs {@code df_full_field_interp_lerp3} then {@code df_full_field_interp}
     * (SAME kernels, SAME corner bytes, SAME readback as the fallback path — bit-identical),
     * waits on the readbacks, and copies the two pinned-staging fields into
     * {@code holder.value}/{@code holder.lerp3} (the only per-chunk memcpy; into reused arrays,
     * no allocation). fp32 fields are widened to {@code double}, which is exact and matches the
     * fast-path read {@code (double) hostF.get(gi)}.
     *
     * <p>Runs on the batcher/prefetch thread (off the worker's fill critical path); the worker
     * later serves the field READY with no GPU round-trip. Returns {@code true} on success
     * (holder fully populated), {@code false} on any failure or non-full-chunk extent (then the
     * consumer falls back to {@link #provideFullFieldFromHostCorners}). Never throws.
     */
    public boolean produceFullFieldToArrays(double[] cornerGrids,
                                            int dimX, int dimY, int dimZ,
                                            int sx, int sy, int sz,
                                            OnDeviceFieldPool.Holder holder) {
        if (!OnDeviceInterp.ENABLED || benchProgram == null || cornerGrids == null || holder == null) {
            return false;
        }
        long fullX = (long) (dimX - 1) * sx;
        long fullY = (long) (dimY - 1) * sy;
        long fullZ = (long) (dimZ - 1) * sz;
        if (fullX != FULL_X || fullY != FULL_Y || fullZ != FULL_Z) {
            return false;   // not the full vanilla chunk field.
        }
        int cornerN = dimX * dimY * dimZ;
        long cornerTotalL = (long) rootCount * cornerN;
        if (cornerTotalL <= 0 || cornerTotalL > Integer.MAX_VALUE || cornerGrids.length != (int) cornerTotalL) {
            return false;
        }
        int fullN = (int) (fullX * fullY * fullZ);
        long totalL = (long) rootCount * fullN;
        if (totalL <= 0 || totalL > Integer.MAX_VALUE) {
            return false;
        }
        int total = (int) totalL;
        // The caller sized the holder to >= total (via OnDeviceFieldPool.acquire(total)).
        if (holder.value == null || holder.value.length < total
                || holder.lerp3 == null || holder.lerp3.length < total) {
            return false;
        }
        if (OnDeviceInterp.SKIP_PRODUCTION) {
            // CEILING DIAGNOSTIC: skip ALL field production (corner upload + field kernels + readback).
            // The holder keeps STALE data -> garbage terrain (timing ONLY). Isolates the drainer's
            // GPU production cost from the consume/plumbing.
            holder.hasLerp3 = true;
            holder.total = total;
            holder.fullX = FULL_X;
            holder.fullY = FULL_Y;
            holder.fullZ = FULL_Z;
            holder.fullN = fullN;
            return true;
        }
        int cornerTotal = (int) cornerTotalL;
        // Read lock for the whole body — see fillAllGrids(): keeps close() from releasing this
        // thread's GPU resources mid-dispatch (use-after-free).
        fillLock.readLock().lock();
        try {
            if (closed) {
                return false;
            }
            PerThread rt;
            try {
                rt = threadLocal.get();
            } catch (Throwable t) {
                return false;
            }
            if (rt == null || rt.benchKernel == NULL) {
                return false;
            }
            try {
                rt.ensureCapacity(cornerN);
                // Make the batched corners device-resident in rt.outMem (blocking write), then
                // run + read back both fields, copying into the holder's reused arrays.
                rt.uploadCorners(cornerGrids, cornerTotal);
                return rt.dispatchFieldSync(dimX, dimY, dimZ, sx, sy, sz, cornerN, fullN, total, holder);
            } catch (Throwable t) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("[SuperChunk-GPU] [on-device-interp] [prefetch] pooled field production failed "
                            + "— chunk will use the per-chunk fallback.", t);
                }
                return false;
            }
        } finally {
            fillLock.readLock().unlock();
        }
    }

    /**
     * STAGE 2 ON-DEVICE INTERP — <b>shutdown use-after-free guard</b>. Copies the per-thread
     * pinned field staging (value field {@code hostF}/{@code hostD}; lerp3 cell-cache field
     * {@code hostFLerp3}/{@code hostDLerp3}) into caller-owned HEAP {@code double[]} arrays,
     * ONCE, under the shared fill READ lock with a post-lock re-check of {@code closed}.
     *
     * <p>Called by {@link OnDeviceInterp} right after {@link PendingFullField#complete()} waited
     * the field readbacks: the pinned staging is valid to read at that instant, but the moment
     * {@code complete()} released the read lock, {@link #close()} (driven by
     * {@code OpenCLBackend.shutdown()} on server-stop-during-worldgen) can free that staging under
     * the WRITE lock via {@code PerThread.freeFfHost()}/{@code freeFfLerp3Host()}. Re-acquiring the
     * READ lock blocks {@code close()}'s write lock so the staging stays valid for the copy;
     * if {@code close()} already ran, {@code closed} is observed {@code true} here and the method
     * returns {@code false} WITHOUT dereferencing the (freed) pinned buffers — mirroring
     * {@link PendingFullField#complete()}'s close()-race discipline. The caller then drops the
     * field and the chunk uses the vanilla CPU lerp; no per-block read ever touches freed memory.
     *
     * <p>On success the heap arrays hold the field with fp32 widened to {@code double} exactly as
     * the per-block read {@code (double) hostF.get(gi)} does, so serving from the heap array is
     * bit-identical to the pinned read. {@code lerp3Out} may be {@code null} (no lerp3 field this
     * chunk) and is filled only when the matching lerp3 staging is present. {@code total} is
     * {@code rootCount*fullN}.
     */
    boolean copyPinnedFieldToHeap(boolean srcFp32,
                                  FloatBuffer hostF, DoubleBuffer hostD,
                                  FloatBuffer hostFLerp3, DoubleBuffer hostDLerp3,
                                  int total, double[] valueOut, double[] lerp3Out) {
        if (valueOut == null || total <= 0) {
            return false;
        }
        // RE-ACQUIRE the shared read lock and RE-CHECK closed (see method javadoc): complete()
        // released the lock after the readback wait, so close() may have freed the pinned staging
        // in between. Under the read lock close()'s write lock is blocked, so the staging stays
        // valid for the copy; if close() already ran, `closed` is true and we must not read it.
        fillLock.readLock().lock();
        try {
            if (closed) {
                return false;   // pinned staging freed by close() — do NOT dereference it (UAF).
            }
            if (srcFp32) {
                if (hostF == null) {
                    return false;
                }
                for (int i = 0; i < total; i++) {
                    valueOut[i] = hostF.get(i);   // float -> double is exact (matches the per-block read)
                }
                if (lerp3Out != null && hostFLerp3 != null) {
                    for (int i = 0; i < total; i++) {
                        lerp3Out[i] = hostFLerp3.get(i);
                    }
                }
            } else {
                if (hostD == null) {
                    return false;
                }
                hostD.get(0, valueOut, 0, total);
                if (lerp3Out != null && hostDLerp3 != null) {
                    hostDLerp3.get(0, lerp3Out, 0, total);
                }
            }
            return true;
        } finally {
            fillLock.readLock().unlock();
        }
    }

    // ----------------------------------------------------------------------
    // Per-thread OpenCL resources (mirror GpuDensityFunction.PerThread).
    // ----------------------------------------------------------------------

    private PerThread createPerThread() {
        if (closed) {
            return null;
        }
        PerThread rt = null;
        try {
            rt = new PerThread();
            if (closed) {
                rt.release();
                return null;
            }
            allThreads.add(rt);
            if (closed) {
                if (allThreads.remove(rt)) {
                    rt.release();
                }
                return null;
            }
            return rt;
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [fusion] failed to create per-thread GPU resources on {} — per-DF fallback for this thread.",
                    Thread.currentThread().getName(), t);
            if (rt != null) {
                rt.release();
            }
            return null;
        }
    }

    /**
     * One worker thread's private resources for the fused program: a command queue,
     * a cloned {@code df_batch_lattice_multi} kernel, and a reusable {@code M*n}
     * output buffer (grown on demand to the largest grid this thread has seen).
     */
    private final class PerThread {
        final long queue;
        final long kernel;          // df_batch_lattice_multi (in-kernel coords)
        long outMem = NULL;
        int capacity = 0;            // per-grid n the output buffer can hold (buffer is rootCount*capacity reals)

        java.nio.FloatBuffer hOutF;
        java.nio.DoubleBuffer hOutD;

        // Async (non-blocking) readback staging: the single bounded pending buffer per
        // thread that dispatchAsync reads into and PendingFusedFill.complete splits.
        // Sized rootCount*n elements; reused (grown on demand) so at most ONE async
        // host buffer lives per worker thread.
        java.nio.FloatBuffer hAsyncF;
        java.nio.DoubleBuffer hAsyncD;
        int asyncCapacity = 0;        // total elements (rootCount*n) the async buffer holds

        // ZERO-COPY async map (useMapped && !FIELD_PROGRAM): the outstanding NON-blocking map of
        // outMem the async dispatch reads from directly (no pageable copy). At most one per thread
        // (one chunk per thread at a time). Set on a successful dispatchAsync, cleared by the
        // consuming complete()/discard() after the unmap; if a shutdown races and the consume is
        // skipped, release() unmaps this before freeing outMem.
        long asyncMapAddr = NULL;     // mapped host pointer, or NULL when none outstanding
        long asyncMapBytes = 0;       // bytes mapped (for the matching unmap)

        // STAGE 2 (cross-chunk-batched path) corner-UPLOAD staging: the per-thread host buffer
        // uploadCorners() packs the batched corner grids into before the blocking write into
        // outMem. Reused (grown on demand); only ever touched when on-device interp + batching
        // are both on. NULL otherwise.
        java.nio.FloatBuffer hUpF;
        java.nio.DoubleBuffer hUpD;

        // STAGE-1 full-field bench resources (NULL/0 unless the bench program built).
        // A SEPARATE PROFILING queue so the interp kernel's GPU time is readable via
        // clGetEventProfilingInfo; the corner kernel is host-synced before the bench
        // runs, so reading the corner buffer (written on `queue`) from benchQueue is safe.
        final long benchQueue;
        final long benchKernel;       // df_full_field_interp (cloned per thread)
        long benchOutMem = NULL;      // device WRITE_ONLY buffer the interp kernel writes (discarded)
        int benchCapacity = 0;        // total elements (rootCount*fullN) the bench buffers hold
        // Canonical NVIDIA pinned readback: the kernel writes the DEVICE buffer benchOutMem
        // (fast on-device), and the readback DESTINATION is pinned host memory. benchPinnedMem
        // is CL_MEM_ALLOC_HOST_PTR pinned host memory, mapped ONCE and kept mapped for its
        // lifetime; benchHostF/D are typed VIEWS over that mapped host pointer (NOT memAlloc'd),
        // so clEnqueueReadBuffer(benchOutMem -> pinned host ptr) is a fast DMA.
        long benchPinnedMem = NULL;   // pinned host staging buffer (CL_MEM_READ_WRITE | ALLOC_HOST_PTR)
        long benchPinnedAddr = NULL;  // raw host pointer from the persistent mapRead of benchPinnedMem
        long benchPinnedBytes = 0;    // bytes currently mapped (needed for the matching unmap)
        java.nio.FloatBuffer benchHostF;   // VIEW over benchPinnedAddr (fp32) — do NOT memFree
        java.nio.DoubleBuffer benchHostD;  // VIEW over benchPinnedAddr (fp64) — do NOT memFree

        // STAGE 2 on-device-interp resources (NULL/0 unless on-device interp is on). Same
        // pinned-staging layout as the bench, but the field is KEPT: ffOutMem is the device
        // WRITE_ONLY buffer the interp kernel writes; ffPinnedMem is CL_MEM_ALLOC_HOST_PTR
        // host memory mapped ONCE; ffHostF/D are typed VIEWS over the pinned host pointer that
        // the NoiseChunk fill reads directly (no copy) on this same worker thread. Reuses
        // benchQueue + benchKernel (the df_full_field_interp clone) for the dispatch.
        long ffOutMem = NULL;         // device WRITE_ONLY buffer the field kernel writes
        long ffPinnedMem = NULL;      // pinned host staging (CL_MEM_READ_WRITE | ALLOC_HOST_PTR)
        long ffPinnedAddr = NULL;     // raw host pointer from the persistent mapRead of ffPinnedMem
        long ffPinnedBytes = 0;       // bytes currently mapped (needed for the matching unmap)
        int ffCapacity = 0;           // total elements (rootCount*fullN) the field buffers hold
        java.nio.FloatBuffer ffHostF;      // VIEW over ffPinnedAddr (fp32) — do NOT memFree
        java.nio.DoubleBuffer ffHostD;     // VIEW over ffPinnedAddr (fp64) — do NOT memFree

        // STAGE 2 SECOND field — the X->Y->Z (Mth.lerp3) cell-cache field. Same pinned-staging
        // layout as ffOutMem above, computed by the df_full_field_interp_lerp3 clone from the
        // SAME corner buffer into its OWN device + pinned-host buffers (both fields live at once
        // for one chunk). ffLerp3Kernel is NULL unless on-device interp is enabled AND it cloned.
        final long ffLerp3Kernel;     // df_full_field_interp_lerp3 (cloned per thread), or NULL
        long ffLerp3OutMem = NULL;    // device WRITE_ONLY buffer the lerp3 kernel writes
        long ffLerp3PinnedMem = NULL; // pinned host staging (CL_MEM_READ_WRITE | ALLOC_HOST_PTR)
        long ffLerp3PinnedAddr = NULL;// raw host pointer from the persistent mapRead
        long ffLerp3PinnedBytes = 0;  // bytes currently mapped (matching unmap)
        int ffLerp3Capacity = 0;      // total elements (rootCount*fullN) the lerp3 buffers hold
        java.nio.FloatBuffer ffLerp3HostF;   // VIEW over ffLerp3PinnedAddr (fp32) — do NOT memFree
        java.nio.DoubleBuffer ffLerp3HostD;  // VIEW over ffLerp3PinnedAddr (fp64) — do NOT memFree

        PerThread() {
            this.queue = program.createQueue(false);
            this.kernel = program.kernel(KERNEL_NAME);
            // Bind the 8 immutable shared noise pointers (args 0..7) ONCE on this thread's
            // cloned kernel: they are read-only buffers that never change between dispatches,
            // so re-binding them on every fillAllGrids/dispatchAsync is pure redundant driver
            // work. Mirrors GpuDensityFunction.PerThread (which already binds once); each
            // PerThread owns its own cloned kernel, so this is safe with no locking.
            bindNoiseArgs(this.kernel);
            // Bench resources are best-effort: a failure here must NEVER break fusion
            // (terrain). On any error skip the bench on this thread (benchKernel == NULL).
            long bq = NULL, bk = NULL, lk = NULL;
            if (benchProgram != null) {
                try {
                    bq = benchProgram.createQueue(true);   // profiling-enabled
                    bk = benchProgram.kernel(BENCH_KERNEL_NAME);
                    // The lerp3 (X->Y->Z cell-cache) field is only needed for on-device interp;
                    // the bench-only build doesn't clone it. A clone failure must NEVER break the
                    // value-path field — just leaves lk == NULL so the cell-cache stays on the CPU.
                    if (OnDeviceInterp.ENABLED) {
                        lk = benchProgram.kernel(LERP3_KERNEL_NAME);
                    }
                } catch (Throwable t) {
                    LOGGER.warn("[SuperChunk-GPU] [fullfield] per-thread field resource init failed on {} "
                            + "— full-field skipped on this thread (terrain unaffected).", Thread.currentThread().getName(), t);
                    if (lk != NULL) { CLProgram.releaseKernel(lk); lk = NULL; }
                    if (bk != NULL) { CLProgram.releaseKernel(bk); bk = NULL; }
                    if (bq != NULL) { CLProgram.releaseQueue(bq); bq = NULL; }
                }
            }
            this.benchQueue = bq;
            this.benchKernel = bk;
            this.ffLerp3Kernel = lk;
        }

        /** Binds the 8 immutable shared noise buffers at arg indices 0..7 of kernel {@code k}. */
        private void bindNoiseArgs(long k) {
            program.setArgPointer(k, 0, noiseDescMem);
            program.setArgPointer(k, 1, noiseFactorsMem);
            program.setArgPointer(k, 2, nPermMem);
            program.setArgPointer(k, 3, nActiveMem);
            program.setArgPointer(k, 4, nXoMem);
            program.setArgPointer(k, 5, nYoMem);
            program.setArgPointer(k, 6, nZoMem);
            program.setArgPointer(k, 7, nAmpMem);
        }

        /**
         * Grows the bench buffers to hold at least {@code total} reals: the DEVICE
         * WRITE_ONLY buffer the interp kernel writes (kept on-device so the kernel's
         * writes stay fast), PLUS a pinned host staging buffer mapped ONCE as the fast
         * readback destination (the canonical NVIDIA pinned-staging readback pattern).
         */
        void ensureBenchCapacity(int total) {
            if (total <= benchCapacity) {
                return;
            }
            long bytes = (long) total * realBytes;
            // Drop the old buffers first. freeBenchHost() unmaps + releases the old pinned
            // staging buffer (it needs benchQueue, still live here) and nulls the views.
            CLProgram.releaseMem(benchOutMem);
            benchOutMem = NULL;
            freeBenchHost();
            benchCapacity = 0;
            // Device buffer the kernel WRITES — kept plain WRITE_ONLY device memory so the
            // kernel's on-device writes stay fast (NOT ALLOC_HOST_PTR, which would make the
            // kernel itself write its output over PCIe and slow the kernel down).
            benchOutMem = benchProgram.createOutputBuffer(bytes);
            // Pinned host staging: CL_MEM_READ_WRITE | CL_MEM_ALLOC_HOST_PTR, mapped ONCE and
            // KEPT mapped for the buffer's lifetime. benchHostF/D are typed VIEWS over the
            // pinned host pointer (NOT memAlloc'd), so the readBench() DMA below is a fast
            // device->pinned-host transfer instead of a slow staged copy.
            benchPinnedMem = benchProgram.createHostReadWriteBuffer(bytes);
            benchPinnedAddr = benchProgram.mapRead(benchQueue, benchPinnedMem, bytes);
            benchPinnedBytes = bytes;
            if (fp32) {
                benchHostF = MemoryUtil.memFloatBuffer(benchPinnedAddr, total);
            } else {
                benchHostD = MemoryUtil.memDoubleBuffer(benchPinnedAddr, total);
            }
            benchCapacity = total;
        }

        /** Blocking device->host read of the full interpolated field. Result intentionally DISCARDED. */
        void readBench(int total) {
            if (fp32) {
                benchHostF.clear();
                benchHostF.limit(total);
                program.readBufferBlocking(benchQueue, benchOutMem, benchHostF);
            } else {
                benchHostD.clear();
                benchHostD.limit(total);
                program.readBufferBlocking(benchQueue, benchOutMem, benchHostD);
            }
            // The interpolated field is NOT consumed — STAGE-1 measures cost only.
        }

        /**
         * Releases the pinned readback staging. benchHostF/D are VIEWS over the mapped
         * pinned buffer (NOT memAlloc'd), so they are NEVER memFree'd — they are simply
         * dropped, then the pinned cl_mem is unmapped (needs benchQueue, so callers must
         * run this BEFORE benchQueue is released) and released. Best-effort: a bench
         * cleanup failure must never throw.
         */
        void freeBenchHost() {
            benchHostF = null;
            benchHostD = null;
            if (benchPinnedMem != NULL) {
                if (benchPinnedAddr != NULL) {
                    try {
                        benchProgram.unmap(benchQueue, benchPinnedMem, benchPinnedAddr, benchPinnedBytes);
                    } catch (Throwable ignored) {
                    }
                    benchPinnedAddr = NULL;
                }
                CLProgram.releaseMem(benchPinnedMem);
                benchPinnedMem = NULL;
            }
            benchPinnedBytes = 0;
        }

        /**
         * STAGE 2: grows the field buffers to hold at least {@code total} reals — the device
         * WRITE_ONLY buffer the interp kernel writes (kept on-device so the writes stay fast)
         * PLUS a pinned host staging buffer mapped ONCE as the readback destination AND the
         * direct read source for the NoiseChunk fill. Mirrors {@link #ensureBenchCapacity}.
         */
        void ensureFfCapacity(int total) {
            if (total <= ffCapacity) {
                return;
            }
            long bytes = (long) total * realBytes;
            CLProgram.releaseMem(ffOutMem);
            ffOutMem = NULL;
            freeFfHost();             // unmaps + releases the old pinned staging (needs benchQueue)
            ffCapacity = 0;
            ffOutMem = benchProgram.createOutputBuffer(bytes);
            ffPinnedMem = benchProgram.createHostReadWriteBuffer(bytes);
            ffPinnedAddr = benchProgram.mapRead(benchQueue, ffPinnedMem, bytes);
            ffPinnedBytes = bytes;
            if (fp32) {
                ffHostF = MemoryUtil.memFloatBuffer(ffPinnedAddr, total);
            } else {
                ffHostD = MemoryUtil.memDoubleBuffer(ffPinnedAddr, total);
            }
            ffCapacity = total;
        }

        /**
         * STAGE 2: NON-blocking device-&gt;pinned-host read of the full interpolated field
         * (KEPT) on the in-order corner queue; the read event lands in {@code evOut[0]}. The
         * pinned staging must stay untouched until that event completes (waited lazily at
         * first field use via {@link PendingFullField#complete}).
         */
        void enqueueFfRead(int total, long[] evOut) {
            if (fp32) {
                ffHostF.clear();
                ffHostF.limit(total);
                program.enqueueReadAsync(queue, ffOutMem, ffHostF, evOut);
            } else {
                ffHostD.clear();
                ffHostD.limit(total);
                program.enqueueReadAsync(queue, ffOutMem, ffHostD, evOut);
            }
        }

        /**
         * STAGE 2 PREFETCH POOL — SYNCHRONOUS production of both full fields into {@code holder}'s
         * REUSED arrays. Assumes {@code outMem} already holds this chunk's corners (uploaded by
         * {@link #uploadCorners}). Enqueues the lerp3 (X-&gt;Y-&gt;Z, cell-cache, consumed first)
         * field + readback BEFORE the value (Y-&gt;X-&gt;Z) field (LEVER 3, free), flushes, WAITS on
         * both readbacks (production is off the worker's fill path), then copies the two pinned-
         * staging fields into {@code holder.value}/{@code holder.lerp3}. SAME kernels/args/readback
         * bytes as {@link #maybeDispatchFullFieldAsync}, so the served field is bit-identical.
         * Returns {@code true} on success. Never leaks the CL read events.
         */
        boolean dispatchFieldSync(int dimX, int dimY, int dimZ, int sx, int sy, int sz,
                                  int cornerN, int fullN, int total, OnDeviceFieldPool.Holder holder) {
            ensureFfCapacity(total);
            boolean lerp3 = ffLerp3Kernel != NULL;
            if (lerp3) {
                ensureFfLerp3Capacity(total);
            }
            long evLerp3 = NULL;
            long evValue = NULL;
            try {
                // LEVER 3: enqueue the X->Y->Z (Mth.lerp3) cell-cache field + readback FIRST
                // (it is consumed first by the dominant finalDensity fill). Bit-identical kernel
                // args to maybeDispatchFullFieldAsync; only the enqueue ORDER differs, which never
                // affects the values (both kernels read the SAME corner buffer read-only into
                // SEPARATE outputs, and this path waits on BOTH before returning).
                if (lerp3) {
                    long k3 = ffLerp3Kernel;
                    int b = 0;
                    program.setArgPointer(k3, b++, outMem);
                    program.setArgInt(k3, b++, dimX);
                    program.setArgInt(k3, b++, dimY);
                    program.setArgInt(k3, b++, dimZ);
                    program.setArgInt(k3, b++, sx);
                    program.setArgInt(k3, b++, sy);
                    program.setArgInt(k3, b++, sz);
                    program.setArgInt(k3, b++, cornerN);
                    program.setArgInt(k3, b++, FULL_X);
                    program.setArgInt(k3, b++, FULL_Y);
                    program.setArgInt(k3, b++, FULL_Z);
                    program.setArgInt(k3, b++, fullN);
                    program.setArgInt(k3, b++, total);
                    program.setArgPointer(k3, b++, ffLerp3OutMem);
                    program.enqueue1DAsync(queue, k3, CLProgram.roundUpGlobal(total), null);
                    if (!OnDeviceInterp.SKIP_READBACK) {
                        long[] evL = new long[1];
                        enqueueFfLerp3Read(total, evL);
                        evLerp3 = evL[0];
                    }
                }
                long kernel = benchKernel;
                int a = 0;
                program.setArgPointer(kernel, a++, outMem);
                program.setArgInt(kernel, a++, dimX);
                program.setArgInt(kernel, a++, dimY);
                program.setArgInt(kernel, a++, dimZ);
                program.setArgInt(kernel, a++, sx);
                program.setArgInt(kernel, a++, sy);
                program.setArgInt(kernel, a++, sz);
                program.setArgInt(kernel, a++, cornerN);
                program.setArgInt(kernel, a++, FULL_X);
                program.setArgInt(kernel, a++, FULL_Y);
                program.setArgInt(kernel, a++, FULL_Z);
                program.setArgInt(kernel, a++, fullN);
                program.setArgInt(kernel, a++, total);
                program.setArgPointer(kernel, a++, ffOutMem);
                program.enqueue1DAsync(queue, kernel, CLProgram.roundUpGlobal(total), null);
                if (!OnDeviceInterp.SKIP_READBACK) {
                    long[] evV = new long[1];
                    enqueueFfRead(total, evV);
                    evValue = evV[0];
                }

                program.flush(queue);

                if (OnDeviceInterp.SKIP_READBACK) {
                    // CEILING DIAGNOSTIC: drain the field kernels (so the GPU queue can't pile up)
                    // but skip the readback DMA + the pinned->array host copy. The holder keeps
                    // STALE data -> garbage terrain (timing ONLY). Bounds the max cps a compressed/
                    // eliminated field readback could recover.
                    program.finish(queue);
                } else {
                    // Synchronous production: wait on both readbacks (the worker never waits — it
                    // serves the field ready). Wait order is irrelevant to the values.
                    if (evLerp3 != NULL) {
                        CLProgram.waitForEvent(evLerp3);
                    }
                    CLProgram.waitForEvent(evValue);
                    // Copy pinned staging -> holder's REUSED arrays (the only per-chunk memcpy; no
                    // allocation). fp32 widens float->double (exact; matches the fast-path read).
                    copyStagingInto(holder.value, total, false);
                    if (lerp3) {
                        copyStagingInto(holder.lerp3, total, true);
                    }
                }
                holder.hasLerp3 = lerp3;
                holder.total = total;
                holder.fullX = FULL_X;
                holder.fullY = FULL_Y;
                holder.fullZ = FULL_Z;
                holder.fullN = fullN;
                return true;
            } finally {
                // Drain (in case a throw left a read in flight, so the pinned staging isn't being
                // DMA'd into when the next production reuses it) + release the events, exactly once.
                if (evLerp3 != NULL) {
                    CLProgram.waitForEvent(evLerp3);
                    CLProgram.releaseEvent(evLerp3);
                }
                if (evValue != NULL) {
                    CLProgram.waitForEvent(evValue);
                    CLProgram.releaseEvent(evValue);
                }
            }
        }

        /** Copies {@code total} field reals from this thread's pinned staging into {@code dst} (widening fp32). */
        private void copyStagingInto(double[] dst, int total, boolean lerp3) {
            if (fp32) {
                java.nio.FloatBuffer src = lerp3 ? ffLerp3HostF : ffHostF;
                for (int i = 0; i < total; i++) {
                    dst[i] = src.get(i);   // float -> double is exact
                }
            } else {
                java.nio.DoubleBuffer src = lerp3 ? ffLerp3HostD : ffHostD;
                src.get(0, dst, 0, total);
            }
        }

        /**
         * STAGE 2 (cross-chunk-batched path): makes the host corner grids {@code corners}
         * ({@code total} = rootCount*cornerN reals) device-resident in {@code outMem} via a
         * BLOCKING write on the in-order corner queue, so the full-field kernels enqueued
         * after it read the same corners the per-chunk corner kernel would have written. The
         * fp32 path narrows each double back to the float the per-chunk path stores, so the
         * field stays bit-identical. The staging buffer is per-thread, reused (grown on demand).
         */
        void uploadCorners(double[] corners, int total) {
            if (fp32) {
                if (hUpF == null || hUpF.capacity() < total) {
                    if (hUpF != null) {
                        MemoryUtil.memFree(hUpF);
                    }
                    hUpF = MemoryUtil.memAllocFloat(total);
                }
                hUpF.clear();
                for (int i = 0; i < total; i++) {
                    hUpF.put((float) corners[i]);
                }
                hUpF.flip();
                program.writeBufferBlocking(queue, outMem, hUpF);
            } else {
                if (hUpD == null || hUpD.capacity() < total) {
                    if (hUpD != null) {
                        MemoryUtil.memFree(hUpD);
                    }
                    hUpD = MemoryUtil.memAllocDouble(total);
                }
                hUpD.clear();
                hUpD.put(corners, 0, total);
                hUpD.flip();
                program.writeBufferBlocking(queue, outMem, hUpD);
            }
        }

        /**
         * STAGE 2: releases the field pinned staging. ffHostF/D are VIEWS over the mapped
         * pinned buffer (NOT memAlloc'd) so they are never memFree'd — dropped, then the
         * pinned cl_mem is unmapped (needs benchQueue, so callers run this BEFORE benchQueue
         * is released) and released. Best-effort: never throws. Mirrors {@link #freeBenchHost}.
         */
        void freeFfHost() {
            ffHostF = null;
            ffHostD = null;
            if (ffPinnedMem != NULL) {
                if (ffPinnedAddr != NULL) {
                    try {
                        benchProgram.unmap(benchQueue, ffPinnedMem, ffPinnedAddr, ffPinnedBytes);
                    } catch (Throwable ignored) {
                    }
                    ffPinnedAddr = NULL;
                }
                CLProgram.releaseMem(ffPinnedMem);
                ffPinnedMem = NULL;
            }
            ffPinnedBytes = 0;
        }

        /** STAGE 2: grows the X->Y->Z (lerp3) field buffers to hold at least {@code total} reals. Mirrors {@link #ensureFfCapacity}. */
        void ensureFfLerp3Capacity(int total) {
            if (total <= ffLerp3Capacity) {
                return;
            }
            long bytes = (long) total * realBytes;
            CLProgram.releaseMem(ffLerp3OutMem);
            ffLerp3OutMem = NULL;
            freeFfLerp3Host();        // unmaps + releases the old pinned staging (needs benchQueue)
            ffLerp3Capacity = 0;
            ffLerp3OutMem = benchProgram.createOutputBuffer(bytes);
            ffLerp3PinnedMem = benchProgram.createHostReadWriteBuffer(bytes);
            ffLerp3PinnedAddr = benchProgram.mapRead(benchQueue, ffLerp3PinnedMem, bytes);
            ffLerp3PinnedBytes = bytes;
            if (fp32) {
                ffLerp3HostF = MemoryUtil.memFloatBuffer(ffLerp3PinnedAddr, total);
            } else {
                ffLerp3HostD = MemoryUtil.memDoubleBuffer(ffLerp3PinnedAddr, total);
            }
            ffLerp3Capacity = total;
        }

        /**
         * STAGE 2: NON-blocking device-&gt;pinned-host read of the X-&gt;Y-&gt;Z (lerp3) field
         * (KEPT) on the in-order corner queue; the read event lands in {@code evOut[0]}.
         */
        void enqueueFfLerp3Read(int total, long[] evOut) {
            if (fp32) {
                ffLerp3HostF.clear();
                ffLerp3HostF.limit(total);
                program.enqueueReadAsync(queue, ffLerp3OutMem, ffLerp3HostF, evOut);
            } else {
                ffLerp3HostD.clear();
                ffLerp3HostD.limit(total);
                program.enqueueReadAsync(queue, ffLerp3OutMem, ffLerp3HostD, evOut);
            }
        }

        /** STAGE 2: releases the lerp3 field pinned staging. Mirrors {@link #freeFfHost}; never throws. */
        void freeFfLerp3Host() {
            ffLerp3HostF = null;
            ffLerp3HostD = null;
            if (ffLerp3PinnedMem != NULL) {
                if (ffLerp3PinnedAddr != NULL) {
                    try {
                        benchProgram.unmap(benchQueue, ffLerp3PinnedMem, ffLerp3PinnedAddr, ffLerp3PinnedBytes);
                    } catch (Throwable ignored) {
                    }
                    ffLerp3PinnedAddr = NULL;
                }
                CLProgram.releaseMem(ffLerp3PinnedMem);
                ffLerp3PinnedMem = NULL;
            }
            ffLerp3PinnedBytes = 0;
        }

        /** Grows the async readback host buffer to hold at least {@code totalElems} (= rootCount*n) reals. */
        void ensureAsyncHost(int totalElems) {
            if (totalElems <= asyncCapacity) {
                return;
            }
            freeAsyncHost();
            // Zero capacity BEFORE the (throwing) memAlloc — same guard as ensureCapacity above.
            // A failed grow then leaves asyncCapacity 0 (hAsync* NULL) so the next dispatch
            // re-allocates instead of reading through a NULL async host buffer.
            asyncCapacity = 0;
            if (fp32) {
                hAsyncF = MemoryUtil.memAllocFloat(totalElems);
            } else {
                hAsyncD = MemoryUtil.memAllocDouble(totalElems);
            }
            asyncCapacity = totalElems;
        }

        /** Enqueues the NON-blocking readback of {@code totalElems} reals into the async host buffer; event in {@code evOut[0]}. */
        void enqueueAsyncRead(int totalElems, long[] evOut) {
            if (fp32) {
                hAsyncF.clear();
                hAsyncF.limit(totalElems);
                program.enqueueReadAsync(queue, outMem, hAsyncF, evOut);
            } else {
                hAsyncD.clear();
                hAsyncD.limit(totalElems);
                program.enqueueReadAsync(queue, outMem, hAsyncD, evOut);
            }
        }

        /**
         * ZERO-COPY async-map counterpart of {@link #enqueueAsyncRead} (useMapped && no field
         * program): NON-blocking map of the host-resident corner buffer {@code outMem} for READ,
         * so {@link PendingFusedFill#complete} reads the corner bytes DIRECTLY (no copy into a
         * pageable buffer). Returns the raw mapped host pointer; {@code evOut[0]} gets the map
         * event, which completes once the corner kernel's writes AND the map are done (same
         * deferred-wait semantics as the copy path). Read via {@link #splitMapped} after the
         * event, then {@link #unmapAsyncRead} to release the map before outMem is rewritten.
         */
        long mapAsyncRead(int totalElems, long[] evOut) {
            long bytes = (long) totalElems * realBytes;
            return program.mapReadAsync(queue, outMem, bytes, evOut);
        }

        /**
         * Reads the {@code m*n} fused output split into per-root slices DIRECTLY from the mapped
         * corner buffer ({@code mappedPtr} from {@link #mapAsyncRead}) — the SAME conversion as
         * the blocking {@link #download} mapped branch, so bit-identical results. The caller must
         * have waited on the map event first and must {@link #unmapAsyncRead} after.
         */
        void splitMapped(double[][] outPerRoot, long mappedPtr, int n, int m) {
            int totalInt = m * n;
            if (fp32) {
                java.nio.FloatBuffer fb = MemoryUtil.memFloatBuffer(mappedPtr, totalInt);
                for (int k = 0; k < m; k++) {
                    double[] dst = sliceFor(outPerRoot, k, n);
                    int base = k * n;
                    for (int i = 0; i < n; i++) dst[i] = fb.get(base + i);
                }
            } else {
                java.nio.DoubleBuffer db = MemoryUtil.memDoubleBuffer(mappedPtr, totalInt);
                for (int k = 0; k < m; k++) {
                    double[] dst = sliceFor(outPerRoot, k, n);
                    int base = k * n;
                    db.get(base, dst, 0, n);
                }
            }
        }

        /**
         * Releases the outstanding zero-copy async map of {@code outMem} (enqueued on the in-order
         * corner queue, so it completes before this thread's next dispatchAsync rewrites outMem)
         * and clears the per-thread tracking so {@link #release} won't unmap it again.
         */
        void unmapAsyncRead(long mappedPtr, long bytes) {
            program.unmap(queue, outMem, mappedPtr, bytes);
            asyncMapAddr = NULL;
            asyncMapBytes = 0;
        }

        /**
         * Splits the already-read async host buffer into per-root slices
         * ({@code outPerRoot[k][i] = buf[k*n + i]}) — same conversion as the blocking
         * {@link #download}, so bit-identical results.
         */
        void splitAsync(double[][] outPerRoot, int n, int m) {
            if (fp32) {
                for (int k = 0; k < m; k++) {
                    double[] dst = sliceFor(outPerRoot, k, n);
                    int base = k * n;
                    for (int i = 0; i < n; i++) dst[i] = hAsyncF.get(base + i);
                }
            } else {
                for (int k = 0; k < m; k++) {
                    double[] dst = sliceFor(outPerRoot, k, n);
                    int base = k * n;
                    hAsyncD.get(base, dst, 0, n);
                }
            }
        }

        void freeAsyncHost() {
            if (hAsyncF != null) { MemoryUtil.memFree(hAsyncF); hAsyncF = null; }
            if (hAsyncD != null) { MemoryUtil.memFree(hAsyncD); hAsyncD = null; }
        }

        /** Grows the output buffer to hold at least {@code rootCount*n} elements. */
        void ensureCapacity(int n) {
            if (n <= capacity) {
                return;
            }
            CLProgram.releaseMem(outMem);
            outMem = NULL;
            // Zero capacity BEFORE the (throwing) alloc below — mirrors the sibling growers
            // ensureBenchCapacity/ensureFfCapacity/ensureFfLerp3Capacity and
            // GpuDensityFunction.ensureCapacity. If createBuffer OOMs, capacity stays 0 so a
            // later ensureCapacity(n) with n <= the OLD capacity re-attempts the alloc instead
            // of passing the early return and dispatching against a NULL outMem.
            capacity = 0;
            long elems = (long) rootCount * n;
            // df_full_field_interp (Stage-1 bench AND Stage-2 on-device interp) reads this
            // corner buffer back inside the kernel, so it must be READ_WRITE (a kernel reading
            // a WRITE_ONLY buffer is undefined behavior). Gated on FIELD_PROGRAM so the
            // production path (both flags OFF) keeps today's WRITE_ONLY allocation byte-for-
            // byte; READ_WRITE only relaxes access, never the values.
            if (useMapped) {
                outMem = FIELD_PROGRAM
                        ? program.createHostReadWriteBuffer(elems * realBytes)
                        : program.createHostOutputBuffer(elems * realBytes);
                freeHost();
            } else {
                outMem = FIELD_PROGRAM
                        ? program.createReadWriteBuffer(elems * realBytes)
                        : program.createOutputBuffer(elems * realBytes);
                freeHost();
                if (fp32) {
                    hOutF = MemoryUtil.memAllocFloat((int) elems);
                } else {
                    hOutD = MemoryUtil.memAllocDouble((int) elems);
                }
            }
            capacity = n;
        }

        /**
         * Blocking readback of the {@code m*n} fused output, split into per-root slices
         * ({@code outPerRoot[k][i] = out[k*n + i]}). This is the dispatch's sync point.
         */
        void download(double[][] outPerRoot, int n, int m) {
            int totalInt = m * n;
            if (useMapped) {
                long bytes = (long) totalInt * realBytes;
                long p = program.mapRead(queue, outMem, bytes);
                try {
                    if (fp32) {
                        java.nio.FloatBuffer fb = MemoryUtil.memFloatBuffer(p, totalInt);
                        for (int k = 0; k < m; k++) {
                            double[] dst = sliceFor(outPerRoot, k, n);
                            int base = k * n;
                            for (int i = 0; i < n; i++) dst[i] = fb.get(base + i);
                        }
                    } else {
                        java.nio.DoubleBuffer db = MemoryUtil.memDoubleBuffer(p, totalInt);
                        for (int k = 0; k < m; k++) {
                            double[] dst = sliceFor(outPerRoot, k, n);
                            int base = k * n;
                            db.get(base, dst, 0, n);
                        }
                    }
                } finally {
                    program.unmap(queue, outMem, p, bytes);
                }
            } else {
                if (fp32) {
                    hOutF.clear();
                    hOutF.limit(totalInt);
                    program.readBufferBlocking(queue, outMem, hOutF);
                    for (int k = 0; k < m; k++) {
                        double[] dst = sliceFor(outPerRoot, k, n);
                        int base = k * n;
                        for (int i = 0; i < n; i++) dst[i] = hOutF.get(base + i);
                    }
                } else {
                    hOutD.clear();
                    hOutD.limit(totalInt);
                    program.readBufferBlocking(queue, outMem, hOutD);
                    for (int k = 0; k < m; k++) {
                        double[] dst = sliceFor(outPerRoot, k, n);
                        int base = k * n;
                        hOutD.get(base, dst, 0, n);
                    }
                }
            }
        }

        private double[] sliceFor(double[][] outPerRoot, int k, int n) {
            double[] dst = outPerRoot[k];
            if (dst == null || dst.length != n) {
                dst = new double[n];
                outPerRoot[k] = dst;
            }
            return dst;
        }

        void freeHost() {
            if (hOutF != null) { MemoryUtil.memFree(hOutF); hOutF = null; }
            if (hOutD != null) { MemoryUtil.memFree(hOutD); hOutD = null; }
        }

        void release() {
            // Drain any in-flight non-blocking readback BEFORE freeing the host/device
            // buffers it DMAs into. dispatchAsync() enqueues a non-blocking read into
            // hAsync*/outMem and returns without waiting (the read lock is dropped before
            // PendingFusedFill is consumed), so close() — driven by OpenCLBackend.shutdown()
            // on server-stop mid-worldgen, with asyncReadback ON by default — can reach here
            // while a read is still in flight. clFinish blocks until that read completes into
            // the still-live host buffer; skipping it would memFree/releaseMem underneath an
            // active DMA (use-after-free). Swallowed so this stays non-throwing for close().
            try {
                program.finish(queue);
            } catch (Throwable ignored) {
            }
            // Drain the bench queue too before freeing its buffers (the bench is fully
            // synchronous, but finish() is cheap insurance against an in-flight read).
            if (benchQueue != NULL) {
                try {
                    program.finish(benchQueue);
                } catch (Throwable ignored) {
                }
            }
            // Release any outstanding zero-copy async map of outMem before freeing the buffer. A
            // shutdown mid-chunk can reach here with a map still outstanding: dispatchAsync mapped
            // outMem but this thread's PendingFusedFill.complete()/discard() hasn't run (it will
            // observe `closed` under the read lock and skip, since the context is being reclaimed).
            // finish(queue) above already drained the map command; unmap + finish so
            // releaseMem(outMem) below frees an unmapped buffer. Runs under the write lock, so it is
            // mutually exclusive with complete()/discard() (read lock) — no race on asyncMapAddr,
            // and no double-unmap (a normal consume clears asyncMapAddr under the read lock first).
            if (asyncMapAddr != NULL) {
                try {
                    program.unmap(queue, outMem, asyncMapAddr, asyncMapBytes);
                    program.finish(queue);
                } catch (Throwable ignored) {
                }
                asyncMapAddr = NULL;
                asyncMapBytes = 0;
            }
            CLProgram.releaseMem(outMem);
            CLProgram.releaseKernel(kernel);
            CLProgram.releaseQueue(queue);
            // Bench/field cleanup ordering: freeBenchHost()/freeFfHost()/freeFfLerp3Host()
            // unmap the pinned staging buffers via benchQueue, so they MUST run BEFORE
            // benchQueue is released (unmap before releaseMem; both before the queue release).
            // Then the device buffers + kernels, then the bench queue last. The
            // finish(benchQueue) above already drained any in-flight readBench/readFf/
            // readFfLerp3 DMA into the pinned buffers.
            freeBenchHost();
            freeFfHost();
            freeFfLerp3Host();
            CLProgram.releaseMem(benchOutMem);
            CLProgram.releaseMem(ffOutMem);
            CLProgram.releaseMem(ffLerp3OutMem);
            CLProgram.releaseKernel(benchKernel);
            CLProgram.releaseKernel(ffLerp3Kernel);
            CLProgram.releaseQueue(benchQueue);
            freeHost();
            freeAsyncHost();
            if (hUpF != null) { MemoryUtil.memFree(hUpF); hUpF = null; }
            if (hUpD != null) { MemoryUtil.memFree(hUpD); hUpD = null; }
            asyncCapacity = 0;
            benchCapacity = 0;
            ffCapacity = 0;
            ffLerp3Capacity = 0;
            outMem = NULL;
            benchOutMem = NULL;
            ffOutMem = NULL;
            ffLerp3OutMem = NULL;
        }
    }

    /**
     * Builds the STAGE-1 {@code df_full_field_interp} program (noise.cl prepended for
     * {@code real}/REAL_C + {@code mc_lerp} + {@code FP_CONTRACT OFF}), shared/refcounted
     * via {@link CLProgramCache}. Returns {@code null} on any failure (bench then disabled;
     * never throws).
     */
    private static CLProgram buildBenchProgram(boolean fp32) {
        try {
            String noiseSrc = CLProgram.loadKernelSource(NOISE_CL);
            String ffSrc = CLProgram.loadKernelSource(FULLFIELD_CL);
            if (noiseSrc == null || ffSrc == null) {
                LOGGER.warn("[SuperChunk-GPU] [fullfield-bench] could not load kernel resources — bench disabled.");
                return null;
            }
            String fullSource = noiseSrc + "\n" + ffSrc;
            if (Boolean.getBoolean("superchunk.gpu.dumpKernel")) {
                LOGGER.info("[SuperChunk-GPU] === generated FULL-FIELD bench kernel ===\n{}", ffSrc);
            }
            return CLProgramCache.getOrBuild(fullSource, fp32 ? "-DUSE_FP32" : "");
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [fullfield-bench] building interp program threw — bench disabled.", t);
            return null;
        }
    }
}
