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

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * A density function compiled to OpenCL: holds a built {@link CLProgram} (kernel
 * {@code df_batch}) and the uploaded, immutable noise-state buffers, and exposes
 * a batch {@link #fill} that evaluates the density at many points on the GPU.
 *
 * <p>Built by {@link #compile(AstNode)} from a C2ME density-function AST. Returns
 * {@code null} (rather than throwing) on any failure — a build error, an
 * {@link UnsupportedDfNodeException}, or an unavailable backend — so the caller
 * cleanly falls back to the CPU bytecode path for that density function.
 *
 * <p><b>Precision.</b> Built with {@code -DUSE_FP32} on devices lacking
 * {@code cl_khr_fp64} (this laptop's Iris Xe): {@code real == float}, structural
 * parity. On an fp64 device (RTX 3070) {@code real == double}, ULP-grade.
 *
 * <p><b>Thread-safe (Stage 3b).</b> C2ME generates on many {@code c2me-worker}
 * threads, all calling {@link #fill}. The shared {@code cl_context},
 * {@code cl_program} and the read-only noise-state buffers are shared across
 * threads; each calling thread lazily gets its OWN command queue, its OWN cloned
 * {@code cl_kernel} ({@code clSetKernelArg} is not thread-safe on a shared kernel)
 * and its OWN reusable I/O buffers via a {@link ThreadLocal}. No locking on the
 * hot path. Every per-thread bundle is tracked so {@link #close()} (and
 * {@code OpenCLBackend.shutdown()}) release them with no CL leaks.
 */
public final class GpuDensityFunction implements AutoCloseable {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    private static final String NOISE_CL = "/superchunk/kernels/noise.cl";
    private static final String SUPPORT_CL = "/superchunk/kernels/dfc_support.cl";

    private final CLProgram program;
    /**
     * Kernel-name prefix. {@code ""} for a standalone per-DF program (kernels {@code df_batch}
     * / {@code df_batch_lattice}); for the MERGED-program path, this DF's unique tag (e.g.
     * {@code "d3_"}) so it retrieves ITS entry points ({@code d3_df_batch}) from the one shared
     * program that holds every batched DF's kernels (headers compiled once).
     */
    private final String kernelTag;
    private final boolean fp32;
    private final int realBytes;
    /** The AST this DF was compiled from — reused by LIVE DF fusion ({@code emitMulti}). */
    private final AstNode sourceAst;
    /** Phase-A profiling on this DF (resolved once from config). */
    private final boolean profile;
    /** Phase-B: in-kernel lattice coords when the fill is a regular grid. */
    private final boolean useLattice;
    /** Phase-B: pinned/mapped host buffers (zero-copy iGPU / pinned dGPU). */
    private final boolean useMapped;
    /** Phase-A faithful baseline: legacy double-sync array path (both opts off). */
    private final boolean legacy;

    // Immutable noise-state device buffers (uploaded once at compile, READ-ONLY ->
    // shared across all worker threads). Written once in the ctor, then only nulled by
    // close() after release (so a stray second close can't re-release a stale handle);
    // hence not final.
    private long noiseDescMem;
    private long noiseFactorsMem;
    private long nPermMem;
    private long nActiveMem;
    private long nXoMem;
    private long nYoMem;
    private long nZoMem;
    private long nAmpMem;

    /** Lifecycle: once closed, threads must not allocate new per-thread bundles. */
    private volatile boolean closed = false;

    /**
     * Per-calling-thread OpenCL resources for THIS density function: a private
     * command queue, a private cloned kernel, and reusable I/O buffers (grown on
     * demand to the largest batch this thread has seen). All live on the shared
     * context+program; the read-only noise buffers above are bound fresh each call
     * but never re-uploaded.
     */
    private final ThreadLocal<PerThread> threadLocal = ThreadLocal.withInitial(this::createPerThread);

    /**
     * All per-thread bundles ever created, for centralized release on {@link #close()}.
     * Worker threads add concurrently; cleared under {@code this} on close.
     */
    private final java.util.Set<PerThread> allThreads =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Guards in-flight fills against {@link #close()}. {@link #fill} and
     * {@link #fillWholeGrid} hold the READ lock for their whole body — many run in
     * parallel with no mutual exclusion — while {@link #close()} takes the WRITE
     * lock after setting {@code closed}. The write lock waits for every active fill
     * to finish before any queue/kernel/buffer is released, so close() can never
     * free a worker thread's resources mid-fill (use-after-free).
     */
    private final java.util.concurrent.locks.ReadWriteLock fillLock =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    private GpuDensityFunction(CLProgram program, String kernelTag, boolean fp32, AstNode sourceAst,
                              long noiseDescMem, long noiseFactorsMem, long nPermMem, long nActiveMem,
                              long nXoMem, long nYoMem, long nZoMem, long nAmpMem) {
        this.program = program;
        this.kernelTag = kernelTag == null ? "" : kernelTag;
        this.fp32 = fp32;
        this.sourceAst = sourceAst;
        this.realBytes = fp32 ? Float.BYTES : Double.BYTES;
        dev.superchunk.gpu.GpuConfig cfg = OpenCLBackend.config();
        this.profile = cfg != null && cfg.profile();
        this.useLattice = cfg == null || cfg.latticeCoords();
        this.useMapped = cfg == null || cfg.mappedBuffers();
        // BASELINE mode (Phase-A faithful): both Phase-B opts off -> replicate the
        // original double-sync array path (blocking writes + clFinish + blocking read)
        // so the recorded baseline reflects the pre-optimization cost exactly.
        this.legacy = !this.useLattice && !this.useMapped;
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

    /**
     * Per-thread reason for the most recent {@link #compile}/{@link #compileShared}
     * {@code null} return, so the call sites that decide the CPU fallback
     * ({@code GpuDfcHook}) can log the DF id + reason at INFO without this class
     * changing its own (deliberately quiet) per-DF log levels. Set on every failure
     * path, cleared at {@link #compile} entry and consumed (cleared) by
     * {@link #takeLastCompileFailure}.
     */
    private static final ThreadLocal<String> LAST_COMPILE_FAILURE = new ThreadLocal<>();

    private static void noteFailure(String reason) {
        LAST_COMPILE_FAILURE.set(reason);
    }

    /** Returns AND CLEARS this thread's last compile-failure reason, or {@code null}. */
    public static String takeLastCompileFailure() {
        String s = LAST_COMPILE_FAILURE.get();
        LAST_COMPILE_FAILURE.remove();
        return s;
    }

    /**
     * Compiles an AST to a GPU density function. Returns {@code null} on any
     * failure (logged at DEBUG/INFO; never throws).
     */
    public static GpuDensityFunction compile(AstNode ast) {
        LAST_COMPILE_FAILURE.remove();
        if (!OpenCLBackend.isAvailable()) {
            noteFailure("GPU backend unavailable");
            return null;
        }
        boolean fp32 = OpenCLBackend.isFp32();

        NoiseRegistry registry = new NoiseRegistry();
        String generated;
        try {
            OpenCLAstEmitter.Result result = OpenCLAstEmitter.emit(ast, registry);
            generated = result.source();
        } catch (UnsupportedDfNodeException e) {
            // Per-DF CPU fallback (e.g. an unsupported node). DEBUG: this is normal
            // and frequent. Set -Dsuperchunk.gpu.logUnsupported=true to surface at INFO.
            if (Boolean.getBoolean("superchunk.gpu.logUnsupported")) {
                LOGGER.info("[SuperChunk-GPU] DF not GPU-compilable: {}", e.getMessage());
            } else {
                LOGGER.debug("[SuperChunk-GPU] DF not GPU-compilable: {}", e.getMessage());
            }
            noteFailure("unsupported node: " + e.getMessage());
            return null;
        } catch (Throwable t) {
            LOGGER.debug("[SuperChunk-GPU] DF emission threw — CPU fallback.", t);
            noteFailure("emission threw: " + t);
            return null;
        }

        String headers = kernelHeaders();
        if (headers == null) {
            noteFailure("kernel header resources unavailable");
            return null;
        }
        String fullSource = headers + "\n" + generated;
        if (Boolean.getBoolean("superchunk.gpu.dumpKernel")) {
            LOGGER.info("[SuperChunk-GPU] === generated DF kernel ===\n{}", generated);
        }
        // Standalone per-DF program (kernel tag ""): kernels df_batch / df_batch_lattice.
        return compileShared(fullSource, "", registry, fp32, ast);
    }

    /** Memoized kernel headers (the resources are immutable in the jar). */
    private static volatile String cachedHeaders;
    private static volatile boolean headersLoaded;

    /**
     * The shared kernel headers (noise.cl + dfc_support.cl) the generated kernel(s) depend on,
     * or {@code null} if a resource is unreadable. Factored out so the MERGED-program path
     * ({@code GpuDfcHook} batch) can prepend them ONCE to many DFs' generated bodies.
     *
     * <p>The header text is immutable jar content, so it is read + concatenated ONCE and
     * cached; every per-DF compile in a world load (~100+) then reuses the same String
     * instead of re-reading ~20KB from the classpath and rebuilding a fresh String each time.
     */
    static String kernelHeaders() {
        if (headersLoaded) {
            return cachedHeaders;
        }
        synchronized (GpuDensityFunction.class) {
            if (headersLoaded) {
                return cachedHeaders;
            }
            String noiseSrc = loadResource(NOISE_CL);
            String supportSrc = loadResource(SUPPORT_CL);
            String result;
            if (noiseSrc == null || supportSrc == null) {
                LOGGER.warn("[SuperChunk-GPU] Could not load kernel resources — CPU fallback.");
                result = null;
            } else {
                result = noiseSrc + "\n" + supportSrc;
            }
            cachedHeaders = result;
            headersLoaded = true;
            return result;
        }
    }

    /**
     * Builds a GpuDensityFunction backed by {@code source} — which must already include the
     * headers + the generated kernel(s) — retrieving its entry points with the {@code tag}
     * prefix ({@code tag + "df_batch"}, etc.). The per-DF path passes its own full source +
     * {@code ""}; the MERGED path ({@code GpuDfcHook}) passes the SAME merged source (headers
     * once + every batched DF's tagged body) + this DF's unique tag, so {@link CLProgramCache}
     * dedups it to ONE compiled program shared by all the batched DFs. Each DF still uploads
     * and binds its OWN noise buffers (kernel args), so the runtime values are unchanged.
     * Returns {@code null} on build/upload failure (CPU fallback). Never throws out.
     */
    public static GpuDensityFunction compileShared(String source, String tag, NoiseRegistry registry,
                                                   boolean fp32, AstNode ast) {
        CLProgram program = CLProgramCache.getOrBuild(source, fp32 ? "-DUSE_FP32" : "");
        if (program == null) {
            LOGGER.warn("[SuperChunk-GPU] DF kernel build failed — CPU fallback.");
            noteFailure("clBuildProgram failed (see build log above)");
            return null;
        }
        // Upload noise state (may be empty if the DF references no noises).
        long descMem = NULL, factorsMem = NULL, permMem = NULL, activeMem = NULL,
                xoMem = NULL, yoMem = NULL, zoMem = NULL, ampMem = NULL;
        try {
            descMem = uploadInts(program, registry.descArray());
            factorsMem = uploadReals(program, fp32, registry.factorArray());
            permMem = uploadInts(program, registry.permArray());
            activeMem = uploadInts(program, registry.activeArray());
            xoMem = uploadReals(program, fp32, registry.xoArray());
            yoMem = uploadReals(program, fp32, registry.yoArray());
            zoMem = uploadReals(program, fp32, registry.zoArray());
            ampMem = uploadReals(program, fp32, registry.ampArray());
            return new GpuDensityFunction(program, tag, fp32, ast, descMem, factorsMem, permMem, activeMem,
                    xoMem, yoMem, zoMem, ampMem);
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] DF noise upload failed — CPU fallback.", t);
            noteFailure("noise upload / program init threw: " + t);
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
     * Evaluates the density at each {@code (x[i], y[i], z[i])} into {@code out}.
     * All four arrays must be the same length. Returns {@code true} on success;
     * on any GPU error returns {@code false} (and {@code out} is undefined — the
     * caller should fall back to CPU for this batch).
     *
     * <p>Concurrency-safe: uses this thread's private queue/kernel/buffers, so
     * many c2me-worker threads can fill simultaneously with no global lock. The
     * shared read-only noise buffers are bound as kernel args (no re-upload).
     */
    public boolean fill(double[] out, int[] x, int[] y, int[] z) {
        int n = out.length;
        if (x.length != n || y.length != n || z.length != n) {
            throw new IllegalArgumentException("array length mismatch");
        }
        if (n == 0) {
            return true;
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
                LOGGER.warn("[SuperChunk-GPU] per-thread GPU resource init failed — CPU fallback.", t);
                return false;
            }
            if (rt == null) {
                return false;
            }
            try {
                rt.ensureCapacity(n);

                // Phase B #3: try in-kernel lattice coords (no x/y/z upload).
                LatticeCoords lat = useLattice ? LatticeCoords.detect(x, y, z, n) : null;
                if (lat != null) {
                    return fillLattice(rt, out, lat, n);
                }
                return fillArrays(rt, out, x, y, z, n);
            } catch (Throwable t) {
                LOGGER.warn("[SuperChunk-GPU] DF fill failed — CPU fallback for this batch.", t);
                return false;
            }
        } finally {
            fillLock.readLock().unlock();
        }
    }

    /**
     * SuperChunk GPU (coalescing): compute an ENTIRE chunk cell-corner grid in ONE
     * dispatch. The grid is a known regular lattice, so this goes straight to the
     * in-kernel lattice path (no coordinate upload, no per-column detection). The
     * points and math are identical to what the per-column path computes — this is
     * just the whole grid batched, so results are bit-for-bit identical.
     *
     * <p>{@code out.length} must equal {@code dimX*dimY*dimZ}. Returns {@code true}
     * on success; {@code false} on any GPU error (caller falls back to per-column).
     */
    public boolean fillWholeGrid(double[] out,
                                 int ox, int oy, int oz,
                                 int sx, int sy, int sz,
                                 int dimX, int dimY, int dimZ) {
        if (!useLattice) {
            // Coalescing relies on the in-kernel lattice kernel; if it is disabled
            // (faithful baseline mode) fall back to the per-column path.
            return false;
        }
        int n = out.length;
        long expect = (long) dimX * dimY * dimZ;
        if (expect != n || n <= 0) {
            return false;
        }
        // Read lock for the whole body — see fill(): keeps close() from releasing
        // this thread's GPU resources mid-fill (use-after-free).
        fillLock.readLock().lock();
        try {
            if (closed) {
                return false;
            }
            PerThread rt;
            try {
                rt = threadLocal.get();
            } catch (Throwable t) {
                LOGGER.warn("[SuperChunk-GPU] per-thread GPU resource init failed — CPU fallback.", t);
                return false;
            }
            if (rt == null) {
                return false;
            }
            try {
                rt.ensureCapacity(n);
                // LatticeCoords order: Y-outer (dy), X-mid (dx), Z-inner (dz). Map grid
                // (dimX,dimY,dimZ) accordingly: dx=dimX, dy=dimY, dz=dimZ.
                LatticeCoords lat = LatticeCoords.of(ox, oy, oz, sx, sy, sz, dimX, dimY, dimZ);
                return fillLattice(rt, out, lat, n);
            } catch (Throwable t) {
                LOGGER.warn("[SuperChunk-GPU] whole-grid fill failed — CPU fallback for this DF.", t);
                return false;
            }
        } finally {
            fillLock.readLock().unlock();
        }
    }

    /**
     * Lattice path: bind the shared noise buffers + 9 lattice scalars, enqueue the
     * coord-generating kernel, sync once on the (mapped or blocking) readback.
     * No coordinate uploads at all.
     */
    private boolean fillLattice(PerThread rt, double[] out, LatticeCoords lat, int n) {
        long t0 = profile ? System.nanoTime() : 0L;

        long kernel = rt.latticeKernel;
        // Noise pointers (args 0..7) are bound once in the PerThread ctor; scalars start at 8.
        int a = 8;
        program.setArgInt(kernel, a++, lat.ox);
        program.setArgInt(kernel, a++, lat.oy);
        program.setArgInt(kernel, a++, lat.oz);
        program.setArgInt(kernel, a++, lat.sx);
        program.setArgInt(kernel, a++, lat.sy);
        program.setArgInt(kernel, a++, lat.sz);
        program.setArgInt(kernel, a++, lat.dx);
        program.setArgInt(kernel, a++, lat.dz);
        program.setArgPointer(kernel, a++, rt.outMem);
        program.setArgInt(kernel, a++, n);

        long tKernel = profile ? System.nanoTime() : 0L;
        long[] ev = profile ? new long[1] : null;
        boolean recorded = false;
        try {
            program.enqueue1DAsync(rt.queue, kernel, roundUp(n), ev);

            long tReadStart = profile ? System.nanoTime() : 0L;
            rt.downloadReals(out, n);   // blocking — this is the single sync point
            long tEnd = profile ? System.nanoTime() : 0L;

            if (profile) {
                recordProfile(ev, /*uploadNs*/ 0L, tKernel - t0 + (tReadStart - tKernel), tEnd - tReadStart,
                        tEnd - t0, n, true);
                recorded = true;
            }
            return true;
        } finally {
            // recordProfile releases ev[0] on the success path; if the readback threw
            // before it ran, free the event here so profiling never leaks a cl_event.
            if (ev != null && ev[0] != 0L && !recorded) {
                CLProgram.releaseEvent(ev[0]);
            }
        }
    }

    /**
     * Array path (fallback for non-lattice fills): upload x/y/z (mapped or
     * blocking write), enqueue the array kernel, sync once on readback.
     */
    private boolean fillArrays(PerThread rt, double[] out, int[] x, int[] y, int[] z, int n) {
        // Lazily allocate this thread's coord input buffers (array path only) before the
        // upload/timing, so the dominant lattice path never pays for them.
        rt.ensureCoordCapacity(n);
        long t0 = profile ? System.nanoTime() : 0L;
        rt.uploadCoords(x, y, z, n);
        long tUploadEnd = profile ? System.nanoTime() : 0L;

        long kernel = rt.kernel;
        // Noise pointers (args 0..7) are bound once in the PerThread ctor; data args start at 8.
        int a = 8;
        program.setArgPointer(kernel, a++, rt.sxMem);
        program.setArgPointer(kernel, a++, rt.syMem);
        program.setArgPointer(kernel, a++, rt.szMem);
        program.setArgPointer(kernel, a++, rt.outMem);
        program.setArgInt(kernel, a++, n);

        long tKernel = profile ? System.nanoTime() : 0L;
        long[] ev = profile ? new long[1] : null;
        boolean recorded = false;
        try {
            program.enqueue1DAsync(rt.queue, kernel, roundUp(n), ev);
            if (legacy) {
                // Faithful Phase-A baseline: explicit clFinish BEFORE the (also-blocking)
                // readback, exactly as the original enqueue1D + readBuffer did.
                program.finish(rt.queue);
            }

            long tReadStart = profile ? System.nanoTime() : 0L;
            rt.downloadReals(out, n);   // blocking — single sync point (or 2nd in legacy)
            long tEnd = profile ? System.nanoTime() : 0L;

            if (profile) {
                recordProfile(ev, tUploadEnd - t0, tReadStart - tKernel, tEnd - tReadStart,
                        tEnd - t0, n, false);
                recorded = true;
            }
            return true;
        } finally {
            // See fillLattice: free the profiling event if the readback threw before
            // recordProfile released it.
            if (ev != null && ev[0] != 0L && !recorded) {
                CLProgram.releaseEvent(ev[0]);
            }
        }
    }

    private void recordProfile(long[] ev, long uploadNs, long kernelSyncNs, long readbackNs,
                               long totalNs, int n, boolean lattice) {
        GpuFillProfiler.recordFill(n, uploadNs, kernelSyncNs, readbackNs, totalNs, lattice);
        if (ev != null && ev[0] != 0L) {
            long[] prof = CLProgram.eventProfile(ev[0]);
            if (prof != null) {
                GpuFillProfiler.recordGpuEvent(prof[0], prof[1]);
            }
            CLProgram.releaseEvent(ev[0]);
        }
    }

    public boolean isFp32() {
        return fp32;
    }

    /** The AST this DF was compiled from (for LIVE DF fusion's {@code emitMulti}). */
    public AstNode sourceAst() {
        return sourceAst;
    }

    @Override
    public void close() {
        // Idempotency guard (matches GpuFusedInterpolator/GpuBatchDispatcher.close()):
        // shutdown's drain loop and registerResourceOwner's shutdown-race branch can both
        // invoke close() on the SAME owner. Without this, a second pass re-runs the whole
        // body — re-releasing the noise cl_mem handles and double-decrementing the shared
        // cl_program — a double-free that can SIGSEGV the NVIDIA driver.
        if (closed) {
            return;
        }
        closed = true;
        // Quiesce in-flight fills before releasing anything: the write lock waits for
        // every active fill/fillWholeGrid (read-lock holders) to finish, so we never
        // free a worker thread's queue/kernel/buffers underneath it (use-after-free).
        // New fills observe `closed` under the read lock and bail out.
        fillLock.writeLock().lock();
        try {
            // Release every per-thread bundle (their queues/kernels/buffers live on the
            // shared context). Do this before releasing the shared noise buffers/program.
            synchronized (this) {
                for (PerThread rt : allThreads) {
                    rt.release();
                }
                allThreads.clear();
            }
            // Null each handle right after release: defense-in-depth so the check-then-set
            // window above (two threads both passing the `closed` guard) cannot re-release
            // a freed buffer — the two bodies serialize on the write lock, so the second
            // sees NULL handles and releaseMem(NULL) is a no-op.
            CLProgram.releaseMem(noiseDescMem);   noiseDescMem = NULL;
            CLProgram.releaseMem(noiseFactorsMem); noiseFactorsMem = NULL;
            CLProgram.releaseMem(nPermMem);        nPermMem = NULL;
            CLProgram.releaseMem(nActiveMem);      nActiveMem = NULL;
            CLProgram.releaseMem(nXoMem);          nXoMem = NULL;
            CLProgram.releaseMem(nYoMem);          nYoMem = NULL;
            CLProgram.releaseMem(nZoMem);          nZoMem = NULL;
            CLProgram.releaseMem(nAmpMem);         nAmpMem = NULL;
            program.close();
            OpenCLBackend.unregisterResourceOwner(this);
        } finally {
            fillLock.writeLock().unlock();
        }
    }

    // ----------------------------------------------------------------------
    // Per-thread OpenCL resources (Stage 3b: concurrent worldgen).
    // ----------------------------------------------------------------------

    /** Builds this thread's private queue + cloned kernel. Registered for cleanup. */
    private PerThread createPerThread() {
        if (closed) {
            return null;
        }
        PerThread rt = null;
        try {
            rt = new PerThread();
            // Register only after full construction so close() never sees a half-built
            // bundle. If close() raced in front of us, release immediately.
            if (closed) {
                rt.release();
                return null;
            }
            allThreads.add(rt);
            if (closed) {
                // close() may have run between the check and the add — undo.
                if (allThreads.remove(rt)) {
                    rt.release();
                }
                return null;
            }
            return rt;
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] failed to create per-thread GPU resources on {} — CPU fallback for this thread.",
                    Thread.currentThread().getName(), t);
            if (rt != null) {
                rt.release();
            }
            return null;
        }
    }

    /**
     * One worker thread's private OpenCL resources for this density function:
     * a command queue, cloned {@code df_batch} + {@code df_batch_lattice} kernels,
     * and reusable input/output device buffers (grown on demand).
     *
     * <p><b>Buffers.</b> When {@code useMapped} the input/output buffers are
     * allocated with {@code CL_MEM_ALLOC_HOST_PTR} and accessed via
     * {@code clEnqueueMapBuffer} — zero-copy on a unified-memory iGPU, pinned DMA
     * on a discrete GPU. Otherwise the legacy off-heap staging + explicit
     * read/write copies are used (still correct; just an extra copy).
     */
    private final class PerThread {
        final long queue;
        final long kernel;          // df_batch (array coords)
        final long latticeKernel;   // df_batch_lattice (in-kernel coords)
        long sxMem = NULL, syMem = NULL, szMem = NULL, outMem = NULL;
        int capacity = 0;            // output device buffer capacity in elements
        int coordCapacity = 0;       // coord input buffer capacity (lazy: array path only)

        // Legacy off-heap staging (only used when !useMapped).
        java.nio.IntBuffer hx, hy, hz;
        java.nio.FloatBuffer hOutF;
        java.nio.DoubleBuffer hOutD;

        PerThread() {
            this.queue = program.createQueue(profile);
            this.kernel = program.kernel(kernelTag + "df_batch");
            this.latticeKernel = program.kernel(kernelTag + "df_batch_lattice");
            // Bind the immutable noise pointers (args 0..7) ONCE on both cloned kernels:
            // they are shared read-only buffers that never change between fills, so there
            // is no need to re-bind them on every dispatch. Each PerThread owns its own
            // cloned kernels, so this is safe with no locking. Done only after both
            // kernel() calls succeed so the ctor's failure/leak profile is unchanged.
            bindNoiseArgs(this.kernel);
            bindNoiseArgs(this.latticeKernel);
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
         * Grows the OUTPUT device buffer + output host staging to hold at least
         * {@code n} elements. The coordinate input buffers are allocated separately
         * (and lazily) by {@link #ensureCoordCapacity(int)} since the dominant
         * lattice path never uploads coordinates.
         */
        void ensureCapacity(int n) {
            if (n <= capacity) {
                return;
            }
            CLProgram.releaseMem(outMem);
            // Mark the buffer gone BEFORE any (possibly-throwing) allocation: if an
            // alloc below throws (e.g. CL_MEM_OBJECT_ALLOCATION_FAILURE on a constrained
            // device), this PerThread is left with capacity=0 and a NULL handle, so the
            // next fill cleanly reallocates and releaseMem(NULL) is a no-op — no
            // double-free / use-after-free. capacity is set to n only once every
            // allocation below has succeeded.
            outMem = NULL;
            capacity = 0;
            freeHostOut();
            if (useMapped) {
                outMem = program.createHostOutputBuffer((long) n * realBytes);
                freeHostOut();   // mapped path needs no off-heap staging
            } else {
                outMem = program.createOutputBuffer((long) n * realBytes);
                freeHostOut();
                if (fp32) {
                    hOutF = MemoryUtil.memAllocFloat(n);
                } else {
                    hOutD = MemoryUtil.memAllocDouble(n);
                }
            }
            capacity = n;
        }

        /**
         * Lazily grows the coordinate INPUT buffers + int host staging to hold at
         * least {@code n} elements. Only the array (non-lattice) fill path uploads
         * coordinates, so the dominant lattice path never allocates these.
         */
        void ensureCoordCapacity(int n) {
            if (n <= coordCapacity) {
                return;
            }
            CLProgram.releaseMem(sxMem);
            CLProgram.releaseMem(syMem);
            CLProgram.releaseMem(szMem);
            // Null the handles + reset coordCapacity BEFORE allocating (see
            // ensureCapacity): a throwing alloc leaves NULL handles + coordCapacity=0
            // for a clean retry, and releaseMem(NULL) is a no-op.
            sxMem = syMem = szMem = NULL;
            coordCapacity = 0;
            freeHostCoords();
            if (useMapped) {
                sxMem = program.createHostInputBuffer((long) n * Integer.BYTES);
                syMem = program.createHostInputBuffer((long) n * Integer.BYTES);
                szMem = program.createHostInputBuffer((long) n * Integer.BYTES);
                freeHostCoords();   // mapped path needs no off-heap staging
            } else {
                sxMem = program.createReusableInputBuffer((long) n * Integer.BYTES);
                syMem = program.createReusableInputBuffer((long) n * Integer.BYTES);
                szMem = program.createReusableInputBuffer((long) n * Integer.BYTES);
                freeHostCoords();
                hx = MemoryUtil.memAllocInt(n);
                hy = MemoryUtil.memAllocInt(n);
                hz = MemoryUtil.memAllocInt(n);
            }
            coordCapacity = n;
        }

        void uploadCoords(int[] x, int[] y, int[] z, int n) {
            ensureCoordCapacity(n);
            if (useMapped) {
                long bytes = (long) n * Integer.BYTES;
                long px = program.mapWrite(queue, sxMem, bytes);
                MemoryUtil.memIntBuffer(px, n).put(x, 0, n);
                program.unmap(queue, sxMem, px, bytes);
                long py = program.mapWrite(queue, syMem, bytes);
                MemoryUtil.memIntBuffer(py, n).put(y, 0, n);
                program.unmap(queue, syMem, py, bytes);
                long pz = program.mapWrite(queue, szMem, bytes);
                MemoryUtil.memIntBuffer(pz, n).put(z, 0, n);
                program.unmap(queue, szMem, pz, bytes);
            } else {
                hx.clear(); hy.clear(); hz.clear();
                hx.put(x, 0, n).flip();
                hy.put(y, 0, n).flip();
                hz.put(z, 0, n).flip();
                if (legacy) {
                    // Faithful baseline: blocking writes (original writeBuffer semantics).
                    program.writeBuffer(queue, sxMem, hx);
                    program.writeBuffer(queue, syMem, hy);
                    program.writeBuffer(queue, szMem, hz);
                } else {
                    program.writeBufferAsync(queue, sxMem, hx);
                    program.writeBufferAsync(queue, syMem, hy);
                    program.writeBufferAsync(queue, szMem, hz);
                }
            }
        }

        /** Reads {@code outMem} into {@code out}. Blocking — the dispatch's sync point. */
        void downloadReals(double[] out, int n) {
            if (useMapped) {
                long bytes = (long) n * realBytes;
                long p = program.mapRead(queue, outMem, bytes);
                if (fp32) {
                    java.nio.FloatBuffer fb = MemoryUtil.memFloatBuffer(p, n);
                    for (int i = 0; i < n; i++) out[i] = fb.get(i);
                } else {
                    java.nio.DoubleBuffer db = MemoryUtil.memDoubleBuffer(p, n);
                    db.get(0, out, 0, n);
                }
                program.unmap(queue, outMem, p, bytes);
            } else {
                if (fp32) {
                    hOutF.clear(); hOutF.limit(n);
                    program.readBufferBlocking(queue, outMem, hOutF);
                    for (int i = 0; i < n; i++) out[i] = hOutF.get(i);
                } else {
                    hOutD.clear(); hOutD.limit(n);
                    program.readBufferBlocking(queue, outMem, hOutD);
                    hOutD.get(0, out, 0, n);
                }
            }
        }

        void freeHostCoords() {
            if (hx != null) { MemoryUtil.memFree(hx); hx = null; }
            if (hy != null) { MemoryUtil.memFree(hy); hy = null; }
            if (hz != null) { MemoryUtil.memFree(hz); hz = null; }
        }

        void freeHostOut() {
            if (hOutF != null) { MemoryUtil.memFree(hOutF); hOutF = null; }
            if (hOutD != null) { MemoryUtil.memFree(hOutD); hOutD = null; }
        }

        void freeHost() {
            freeHostCoords();
            freeHostOut();
        }

        void release() {
            CLProgram.releaseMem(sxMem);
            CLProgram.releaseMem(syMem);
            CLProgram.releaseMem(szMem);
            CLProgram.releaseMem(outMem);
            CLProgram.releaseKernel(kernel);
            CLProgram.releaseKernel(latticeKernel);
            CLProgram.releaseQueue(queue);
            freeHost();
            sxMem = syMem = szMem = outMem = NULL;
        }
    }

    // ----------------------------------------------------------------------
    // Buffer helpers (mirror GpuNoiseParityTest; off-heap staging, copy-host-ptr).
    // ----------------------------------------------------------------------

    private static long uploadInts(CLProgram program, int[] data) {
        // OpenCL buffers must be non-empty; pad a zero-length array to 1.
        int len = Math.max(1, data.length);
        IntBuffer buf = MemoryUtil.memAllocInt(len);
        try {
            if (data.length == 0) {
                buf.put(0).flip();
            } else {
                buf.put(data).flip();
            }
            return program.createInputBuffer(buf);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    private static long uploadReals(CLProgram program, boolean fp32, double[] data) {
        int len = Math.max(1, data.length);
        if (fp32) {
            FloatBuffer buf = MemoryUtil.memAllocFloat(len);
            try {
                if (data.length == 0) {
                    buf.put(0.0f);
                } else {
                    for (double v : data) buf.put((float) v);
                }
                buf.flip();
                return program.createInputBuffer(buf);
            } finally {
                MemoryUtil.memFree(buf);
            }
        } else {
            DoubleBuffer buf = MemoryUtil.memAllocDouble(len);
            try {
                if (data.length == 0) {
                    buf.put(0.0);
                } else {
                    buf.put(data);
                }
                buf.flip();
                return program.createInputBuffer(buf);
            } finally {
                MemoryUtil.memFree(buf);
            }
        }
    }

    private static long roundUp(int n) {
        return ((n + 63) / 64) * 64L;
    }

    private static String loadResource(String path) {
        try (InputStream in = GpuDensityFunction.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("[SuperChunk-GPU] Failed reading {}", path, e);
            return null;
        }
    }
}
