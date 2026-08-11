package dev.superchunk.gpu;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import dev.superchunk.gpu.dfc.PermFormat;
import org.lwjgl.opencl.CL10;
import org.lwjgl.opencl.CL11;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memUTF8;

/**
 * Thin helper around an OpenCL {@code cl_program}: compiles source, creates
 * kernels, allocates/transfers buffers, and dispatches an NDRange.
 *
 * <p>Every native call goes through {@link #checkCL(int)} which throws a
 * {@link CLException} on a non-{@code CL_SUCCESS} error code. Callers in the
 * backend wrap usage in try/catch so a GPU fault never propagates out as a hard
 * crash — graceful CPU fallback is mandatory.
 *
 * <p><b>Threading.</b> The {@code cl_context} and {@code cl_program} held here
 * are safe to share across threads. The convenience methods that use the
 * backend's <i>shared</i> command queue ({@link #enqueue1D}, {@link #readBuffer})
 * are NOT thread-safe and are intended for single-threaded boot-time self-tests.
 * For concurrent worldgen the caller must create a <b>per-thread</b> command
 * queue ({@link #createQueue()}) and use the queue-explicit overloads
 * ({@link #enqueue1D(long, long, long)}, {@link #readBuffer(long, long, FloatBuffer)},
 * etc.) together with a per-thread cloned {@link #kernel(String)} — see
 * {@code GpuDensityFunction} for the Stage-3 per-thread resource model.
 */
public final class CLProgram implements AutoCloseable {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    private final long context;
    private final long queue;
    private final long device;
    private final long program;

    /**
     * Refcount for cache-shared programs (see {@code CLProgramCache}). A program
     * may back many {@link dev.superchunk.gpu.dfc.GpuDensityFunction}s if their
     * generated kernels are byte-identical; {@link #close()} only releases the
     * underlying {@code cl_program} when the count hits zero. {@code -1} marks an
     * un-shared program (legacy: close() always releases it).
     */
    private final java.util.concurrent.atomic.AtomicInteger refCount =
            new java.util.concurrent.atomic.AtomicInteger(-1);

    /**
     * Guarantees the underlying {@code cl_program} is released at most once, no matter
     * how the refcount paths are driven. Closing the LAST shared reference (or an
     * un-shared program) flips this; any over-close then short-circuits instead of
     * calling {@code clReleaseProgram} a second time on an already-freed handle (a
     * double-free that can SIGSEGV the NVIDIA driver).
     */
    private final java.util.concurrent.atomic.AtomicBoolean released =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * OpenCL 1.2 core build flag forcing IEEE-754 correctly-rounded fp32 divide and sqrt
     * (instead of the default 2.5-ulp-relaxed versions). The LAST compiler-level lever
     * against the ~3e-8 spline drift (vanilla CubicSpline is Java float; every
     * source-level fix is refuted). Legal ONLY on devices whose
     * {@code CL_DEVICE_SINGLE_FP_CONFIG} advertises
     * {@code CL_FP_CORRECTLY_ROUNDED_DIVIDE_SQRT} — hence the device gate resolved once
     * at backend init ({@link OpenCLBackend#crDivideApplied()}).
     */
    private static final String CR_DIVIDE_FLAG = "-cl-fp32-correctly-rounded-divide-sqrt";

    /**
     * (round-4 driver-regression investigation, {@code -Dsuperchunk.gpu.clOptDisable}, default OFF)
     * Append {@code -cl-opt-disable} to every {@code clBuildProgram}. On the CUDA-13.2 / 595-open
     * driver the OpenCL optimizer is super-linearly slow on the large generated finalDensity
     * kernels (cold boot blew past ~1h vs ~470s on the prior driver). Since the SuperChunk kernel
     * is latency/readback-bound, NOT ALU-bound (fp64 vs fp32 A/B showed the ALU idle), disabling
     * kernel optimization should slash compile time at ~zero runtime cost. PARITY MUST be
     * re-verified with it on — disabling optimization should not change IEEE results (FP_CONTRACT
     * is already OFF in-source), but the boot parity self-test is the gate.
     */
    private static final String OPT_DISABLE_FLAG = "-cl-opt-disable";
    private static final boolean OPT_DISABLE = Boolean.getBoolean("superchunk.gpu.clOptDisable");

    /**
     * Diagnostic (default OFF, {@code -Dsuperchunk.gpu.nvVerbose=true}): makes ptxas print
     * "Used N registers, M bytes cumulative stack size, X bytes spill stores/loads" per kernel
     * into the build log — the occupancy/spill numbers this tree had never observed. Build-log
     * only (no codegen change, so parity-neutral), but it changes the cache key, forcing one
     * cold recompile of every program, and a program served from the disk binary cache produces
     * no build log at all. Hence opt-in: turn it on for one run when you want the numbers.
     */
    private static final String NV_VERBOSE_FLAG = "-cl-nv-verbose";
    private static final boolean NV_VERBOSE = Boolean.getBoolean("superchunk.gpu.nvVerbose");

    /**
     * Occupancy lever (default unset, {@code -Dsuperchunk.gpu.maxRegCount=N}): caps ptxas
     * register allocation at N per thread.
     *
     * <p>ptxas reports 84-92 registers for the generated corner kernels on sm_86. With 65536
     * registers and 1536 resident threads per SM that is ~46-51% occupancy, and these kernels
     * are permutation-gather LATENCY-bound — the one thing occupancy directly buys. Capping to
     * 64 gives 67%, to 40 gives 100%, at the cost of spills to local memory past some point;
     * where the crossover sits is an empirical question, hence a knob rather than a default.
     *
     * <p><b>Parity-neutral.</b> Register allocation and spilling cannot change IEEE results —
     * it is the same instruction stream with different storage. The boot parity self-test still
     * gates it. Folded into the options string, so the program caches key on it.
     */
    private static final String MAX_REG_COUNT = System.getProperty("superchunk.gpu.maxRegCount", "").trim();

    /**
     * (round-4 driver-regression fix, {@code -Dsuperchunk.gpu.noinlineHelpers}, default ON
     * since round 8 — disable with {@code =false}.)
     * Build every kernel with {@code -DSC_NOINLINE}, which flips the heavy {@code noise.cl} /
     * {@code dfc_support.cl} helpers ({@code sample_and_lerp}, {@code improved_noise},
     * {@code perlin_get_value}, {@code normal_noise}, {@code df_noise_value},
     * {@code df_blended_noise}) from {@code inline} to real non-inlined functions. That keeps each
     * generated finalDensity kernel small, so the CUDA-13.2 optimizer (super-linear on the large
     * inlined kernels) compiles quickly — WITH optimization on, so fp64 parity is preserved
     * (bit-exact validated). Cold first-boot compiles drop from ~1 h to ~5 min on
     * nvidia-driver-595. Default-on so a fresh install never hits the pathological compile.
     *
     * <p>Round 10 measured the runtime cost that was previously only assumed: in the noise
     * microbench ({@code tools/noisebench.c}) the INLINED build is 1.3% <b>slower</b>, not faster.
     * So noinline is not a throughput sacrifice here at all. (The old note that the kernels are
     * "noise-gather latency-bound" is also refuted — see {@link dev.superchunk.gpu.dfc.PermFormat}:
     * they are fp64-throughput bound.)
     *
     * <p>RE-CONFIRMED 2026-08-06: an attempt to measure the inlined build's RUNTIME (the number
     * this tree has never had) was aborted — with {@code =false} the cold compile reached 56 of
     * the ~70 programs and then sat on a single fused corner program for over an hour without
     * finishing. So the ~1 h figure is not stale, and the runtime question stays open: the
     * measurement costs a multi-hour cold build per driver update. Note the per-program disk
     * binary cache means a retry resumes from what already built, so this is restartable.
     */
    private static final String NOINLINE_FLAG = "-DSC_NOINLINE";
    private static final boolean NOINLINE_HELPERS =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.gpu.noinlineHelpers", "true"));

    /**
     * THE single build-options choke point: normalizes a caller's {@code clBuildProgram}
     * options string, appending {@link #CR_DIVIDE_FLAG} when the session applies it
     * ({@link OpenCLBackend#crDivideApplied()}) and {@link #OPT_DISABLE_FLAG} when the
     * clOptDisable lever is set. Idempotent (never double-appends), so it
     * is safe to run both in {@code CLProgramCache.getOrBuild} (where the normalized
     * string is folded into the mem/disk cache key BEFORE hashing — a flag flip therefore
     * misses every stale binary and forces the intended cold recompile) and again in
     * {@link #build(String, String)} / {@link #buildFromBinary(byte[], String)} (covering
     * direct, cache-bypassing callers: self-tests and benches). Every clBuildProgram in
     * the codebase lives in those two methods.
     */
    public static String effectiveOptions(String options) {
        StringBuilder sb = new StringBuilder(options == null ? "" : options);
        addFlag(sb, OpenCLBackend.crDivideApplied(), CR_DIVIDE_FLAG);
        addFlag(sb, OPT_DISABLE, OPT_DISABLE_FLAG);
        // Per-program noinline opt-out: a caller passing FORCE_INLINE_MARKER keeps its helpers
        // inlined even when the global noinlineHelpers flag is on. The marker stays in the
        // options string (a harmless -D define), so the mem/disk cache key differs from the
        // noinline build's — no stale-binary collisions. Used by the DECIDE program (one
        // moderate kernel — compiles fine inlined, and it IS on the GPU critical path where
        // noinline's call/spill overhead costs runtime); the large fused CORNER programs stay
        // noinline (their inlined cold compile is the pathological ~1 h case the flag exists for).
        addFlag(sb, NOINLINE_HELPERS && sb.indexOf(FORCE_INLINE_MARKER) < 0, NOINLINE_FLAG);
        addFlag(sb, NV_VERBOSE, NV_VERBOSE_FLAG);
        addFlag(sb, !MAX_REG_COUNT.isEmpty(), "-cl-nv-maxrregcount=" + MAX_REG_COUNT);
        // Permutation-table encoding. Must be in the options string (not just implied by the
        // uploaded bytes) so both program caches key on it — a binary built for `int` would
        // read PAIR bytes as ints and silently produce garbage terrain otherwise.
        addFlag(sb, !PermFormat.active().buildFlag().isEmpty(), PermFormat.active().buildFlag());
        return sb.toString();
    }

    /** Appends {@code flag} (space-separated) iff {@code on} and it is not already present. */
    private static void addFlag(StringBuilder sb, boolean on, String flag) {
        if (!on || sb.indexOf(flag) >= 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(flag);
    }

    /** See {@link #effectiveOptions}: opts this one program out of noinlineHelpers. */
    public static final String FORCE_INLINE_MARKER = "-DSC_FORCE_INLINE";

    private CLProgram(long context, long queue, long device, long program) {
        this.context = context;
        this.queue = queue;
        this.device = device;
        this.program = program;
    }

    /** Marks this program as cache-shared with the given initial refcount. */
    void markShared(int initial) {
        refCount.set(initial);
    }

    /**
     * Atomically increments the share refcount for a cache hand-out.
     *
     * <p>Returns {@code true} if the reference was acquired (the program is still
     * live), {@code false} if the program has already been released (refcount has
     * reached {@code 0}, e.g. {@code clearMemory()} freed it during shutdown) — in
     * which case the caller MUST treat it as a cache miss and rebuild rather than
     * resurrect a freed {@code cl_program} (use-after-free). The compare-and-set
     * loop closes the check-then-increment window where a concurrent {@link #close()}
     * could drop the count to 0 between the read and the increment.
     */
    boolean retain() {
        for (;;) {
            int c = refCount.get();
            if (c < 0) {
                return true;   // un-shared (legacy) program: always valid
            }
            if (c == 0) {
                return false;  // already released -> caller must rebuild (cache miss)
            }
            if (refCount.compareAndSet(c, c + 1)) {
                return true;
            }
        }
    }

    /**
     * Compiles {@code source} for the backend's chosen device.
     *
     * @return a built program, or {@code null} if compilation/build failed (the
     *         build log is logged at ERROR before returning).
     */
    public static CLProgram build(String source) {
        return build(source, "");
    }

    /**
     * Compiles {@code source} with explicit build {@code options} (e.g.
     * {@code "-DUSE_FP32"}). Use this for precision-parameterized kernels so the
     * caller can flip the {@code real} typedef based on device fp64 support.
     *
     * @return a built program, or {@code null} if compilation/build failed.
     */
    public static CLProgram build(String source, String options) {
        if (!OpenCLBackend.isAvailable()) {
            LOGGER.warn("[SuperChunk-GPU] CLProgram.build called but backend unavailable");
            return null;
        }
        long context = OpenCLBackend.context();
        long queue = OpenCLBackend.queue();
        long device = OpenCLBackend.device().device();
        long program = NULL;
        String opts = effectiveOptions(options); // appends the device-gated CR-divide flag
        // Drive the (best-effort, headless-safe) "compiling GPU kernels…" progress window.
        // This is THE source-JIT choke point for the whole codebase, so every slow compile
        // is reflected; warm boots (buildFromBinary relinks) never reach here so the window
        // correctly stays hidden. Fully isolated: a UI/class-load failure must never affect
        // the compile path (graceful CPU fallback is mandatory).
        try {
            CompileProgressWindow.onCompileBegin();
        } catch (Throwable ignored) {
        }
        boolean built = false;
        try (MemoryStack stack = stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            program = clCreateProgramWithSource(context, source, err);
            checkCL(err.get(0));

            int buildResult = clBuildProgram(program, device, opts, null, NULL);
            if (buildResult != CL_SUCCESS) {
                LOGGER.error("[SuperChunk-GPU] clBuildProgram failed ({}):\n{}",
                        buildResult, fetchBuildLog(program, device));
                clReleaseProgram(program);
                return null;
            }
            built = true;
            if (NV_VERBOSE) {
                // Success path normally discards the build log; -cl-nv-verbose puts the
                // ptxas register/spill report in it, so surface it when asked.
                LOGGER.info("[SuperChunk-GPU] [nv-verbose] build log for a {}-char program (opts: {}):\n{}",
                        source.length(), opts, fetchBuildLog(program, device));
            }
            return new CLProgram(context, queue, device, program);
        } catch (Throwable t) {
            LOGGER.error("[SuperChunk-GPU] CLProgram.build threw", t);
            if (program != NULL) {
                try {
                    clReleaseProgram(program);
                } catch (Throwable ignored) {
                }
            }
            return null;
        } finally {
            try {
                CompileProgressWindow.onCompileEnd(built);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String fetchBuildLog(long program, long device) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer sizeBuf = stack.mallocPointer(1);
            int rc = clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG,
                    (ByteBuffer) null, sizeBuf);
            if (rc != CL_SUCCESS) {
                return "(could not read build log: " + rc + ")";
            }
            int logSize = (int) sizeBuf.get(0);
            if (logSize <= 0) {
                return "";
            }
            ByteBuffer logBuf = org.lwjgl.system.MemoryUtil.memAlloc(logSize);
            try {
                rc = clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, logBuf, null);
                if (rc != CL_SUCCESS) {
                    return "(could not read build log: " + rc + ")";
                }
                return memUTF8(logBuf, logSize - 1); // strip trailing NUL
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(logBuf);
            }
        } catch (Throwable t) {
            return "(exception reading build log: " + t.getMessage() + ")";
        }
    }

    /** Creates a kernel by name. Throws {@link CLException} on failure. */
    public long kernel(String name) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long kernel = clCreateKernel(program, name, err);
            checkCL(err.get(0));
            return kernel;
        }
    }

    /**
     * Creates a read-only device buffer initialized from {@code data}
     * (CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR).
     */
    public long createInputBuffer(FloatBuffer data) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long mem = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, data, err);
            checkCL(err.get(0));
            return mem;
        }
    }

    /**
     * Creates a read-only device buffer initialized from {@code data}
     * (CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR).
     */
    public long createInputBuffer(IntBuffer data) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long mem = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, data, err);
            checkCL(err.get(0));
            return mem;
        }
    }

    /**
     * Creates a read-only device buffer initialized from {@code data}
     * (CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR).
     */
    public long createInputBuffer(ByteBuffer data) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long mem = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, data, err);
            checkCL(err.get(0));
            return mem;
        }
    }

    /**
     * Creates a read-only device buffer initialized from {@code data}
     * (CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR). Use only on fp64-capable devices.
     */
    public long createInputBuffer(DoubleBuffer data) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long mem = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, data, err);
            checkCL(err.get(0));
            return mem;
        }
    }

    /**
     * Creates a read-only device buffer initialized from {@code data}
     * (CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR). For {@code cl_long} buffers (e.g.
     * the aquifer location cache / packed BlockPos longs). {@code data} must be a
     * direct (off-heap) buffer — LWJGL CL has no LongBuffer overload, so it is
     * passed as a byte view of the same memory.
     */
    public long createInputBuffer(LongBuffer data) {
        ByteBuffer bb = org.lwjgl.system.MemoryUtil.memByteBuffer(
                org.lwjgl.system.MemoryUtil.memAddress(data), data.remaining() * Long.BYTES);
        try (MemoryStack stack = stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long mem = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, bb, err);
            checkCL(err.get(0));
            return mem;
        }
    }

    /** Creates a write-only device buffer of {@code bytes} bytes (CL_MEM_WRITE_ONLY). */
    public long createOutputBuffer(long bytes) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long mem = clCreateBuffer(context, CL_MEM_WRITE_ONLY, bytes, err);
            checkCL(err.get(0));
            return mem;
        }
    }

    /**
     * Creates a read-write device buffer of {@code bytes} bytes (CL_MEM_READ_WRITE).
     * Use when a kernel must BOTH write a buffer AND a later kernel read it back as an
     * input (e.g. the STAGE-1 full-field bench reading the device-resident corner grid —
     * a CL_MEM_WRITE_ONLY buffer is undefined behavior to read inside a kernel).
     */
    public long createReadWriteBuffer(long bytes) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long mem = clCreateBuffer(context, CL_MEM_READ_WRITE, bytes, err);
            checkCL(err.get(0));
            return mem;
        }
    }

    /** Read-write device buffer in pinned/host memory (CL_MEM_READ_WRITE | CL_MEM_ALLOC_HOST_PTR). */
    public long createHostReadWriteBuffer(long bytes) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long mem = clCreateBuffer(context, CL_MEM_READ_WRITE | CL_MEM_ALLOC_HOST_PTR, bytes, err);
            checkCL(err.get(0));
            return mem;
        }
    }

    /** Sets a {@code cl_mem} (pointer) kernel argument at {@code index}. */
    public void setArgPointer(long kernel, int index, long mem) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pb = stack.mallocPointer(1).put(0, mem);
            checkCL(clSetKernelArg(kernel, index, pb));
        }
    }

    /** Sets an {@code int} scalar kernel argument at {@code index}. */
    public void setArgInt(long kernel, int index, int value) {
        try (MemoryStack stack = stackPush()) {
            checkCL(clSetKernelArg(kernel, index, stack.ints(value)));
        }
    }

    /** Sets a {@code float} scalar kernel argument at {@code index} (USE_FP32 kernels). */
    public void setArgFloat(long kernel, int index, float value) {
        try (MemoryStack stack = stackPush()) {
            checkCL(clSetKernelArg(kernel, index, stack.floats(value)));
        }
    }

    /** Sets a {@code double} scalar kernel argument at {@code index} (fp64 kernels). */
    public void setArgDouble(long kernel, int index, double value) {
        try (MemoryStack stack = stackPush()) {
            checkCL(clSetKernelArg(kernel, index, stack.doubles(value)));
        }
    }

    /** Sets a {@code long} ({@code cl_long}) scalar kernel argument at {@code index}. */
    public void setArgLong(long kernel, int index, long value) {
        try (MemoryStack stack = stackPush()) {
            checkCL(clSetKernelArg(kernel, index, stack.longs(value)));
        }
    }

    /**
     * Enqueues a 1-D NDRange of {@code globalWorkSize} work-items (auto local
     * size), then blocks on {@link org.lwjgl.opencl.CL10#clFinish}.
     */
    public void enqueue1D(long kernel, long globalWorkSize) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer global = stack.mallocPointer(1).put(0, globalWorkSize);
            checkCL(clEnqueueNDRangeKernel(queue, kernel, 1, null, global, null, null, null));
            checkCL(clFinish(queue));
        }
    }

    /** Blocking read of {@code mem} into {@code dst} (offset 0). */
    public void readBuffer(long mem, FloatBuffer dst) {
        checkCL(clEnqueueReadBuffer(queue, mem, true, 0, dst, null, null));
    }

    /** Blocking read of {@code mem} into {@code dst} (offset 0). For fp64 kernels. */
    public void readBuffer(long mem, DoubleBuffer dst) {
        checkCL(clEnqueueReadBuffer(queue, mem, true, 0, dst, null, null));
    }

    /** Blocking read of {@code mem} into {@code dst} (offset 0). For {@code int} outputs. */
    public void readBuffer(long mem, IntBuffer dst) {
        checkCL(clEnqueueReadBuffer(queue, mem, true, 0, dst, null, null));
    }

    /**
     * Blocking read of {@code mem} into {@code dst} (offset 0). For {@code cl_long}
     * outputs. {@code dst} must be direct (off-heap); passed as a byte view.
     */
    public void readBuffer(long mem, LongBuffer dst) {
        ByteBuffer bb = org.lwjgl.system.MemoryUtil.memByteBuffer(
                org.lwjgl.system.MemoryUtil.memAddress(dst), dst.remaining() * Long.BYTES);
        checkCL(clEnqueueReadBuffer(queue, mem, true, 0, bb, null, null));
    }

    // ------------------------------------------------------------------
    // Per-thread (queue-explicit) API for concurrent worldgen (Stage 3b).
    //
    // OpenCL command queues are NOT thread-safe; neither is clSetKernelArg on a
    // shared kernel. So each worker thread creates its OWN queue + its OWN cloned
    // kernel and passes that queue into the methods below. The cl_context and
    // cl_program are shared (thread-safe per the OpenCL spec for object creation),
    // as are read-only input buffers.
    // ------------------------------------------------------------------

    /**
     * Creates a new in-order command queue on the shared context+device. Each
     * worldgen worker thread owns one. Throws {@link CLException} on failure.
     *
     * @param profiling when true the queue is created with
     *        {@code CL_QUEUE_PROFILING_ENABLE} so per-command event timing
     *        ({@code clGetEventProfilingInfo}) is available (Phase-A diagnostics).
     */
    public long createQueue(boolean profiling) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long props = profiling ? CL_QUEUE_PROFILING_ENABLE : 0L;
            long q = clCreateCommandQueue(context, device, props, err);
            checkCL(err.get(0));
            return q;
        }
    }

    /** Back-compat overload: non-profiling in-order queue. */
    public long createQueue() {
        return createQueue(false);
    }

    /** Releases a command queue created by {@link #createQueue()}. Swallows errors. */
    public static void releaseQueue(long q) {
        if (q != NULL) {
            try {
                clReleaseCommandQueue(q);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Creates a reusable read-only device buffer of {@code bytes} bytes WITHOUT
     * initial data (host writes happen later via {@link #writeBuffer}). Used for
     * per-thread input scratch buffers that are re-uploaded each batch.
     */
    public long createReusableInputBuffer(long bytes) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long mem = clCreateBuffer(context, CL_MEM_READ_ONLY, bytes, err);
            checkCL(err.get(0));
            return mem;
        }
    }

    /** Blocking host->device write of {@code src} into {@code mem} (offset 0) on {@code q}. */
    public void writeBuffer(long q, long mem, IntBuffer src) {
        checkCL(clEnqueueWriteBuffer(q, mem, true, 0, src, null, null));
    }

    /**
     * Enqueues a 1-D NDRange on the GIVEN queue and blocks on {@code clFinish}.
     * Thread-safe when {@code q} and {@code kernel} are owned by the calling thread.
     */
    public void enqueue1D(long q, long kernel, long globalWorkSize) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer global = stack.mallocPointer(1).put(0, globalWorkSize);
            checkCL(clEnqueueNDRangeKernel(q, kernel, 1, null, global, null, null, null));
            checkCL(clFinish(q));
        }
    }

    /** Blocking read of {@code mem} into {@code dst} on the GIVEN queue (fp32). */
    public void readBuffer(long q, long mem, FloatBuffer dst) {
        checkCL(clEnqueueReadBuffer(q, mem, true, 0, dst, null, null));
    }

    /** Blocking read of {@code mem} into {@code dst} on the GIVEN queue (fp64). */
    public void readBuffer(long q, long mem, DoubleBuffer dst) {
        checkCL(clEnqueueReadBuffer(q, mem, true, 0, dst, null, null));
    }

    // ------------------------------------------------------------------
    // Event-instrumented + async API (Phase A profiling / Phase B overlap).
    //
    // These mirror the methods above but take an optional output event handle so
    // the kernel can be timed (clGetEventProfilingInfo) and so transfers can be
    // chained non-blocking with a single sync at the end of a fill.
    // ------------------------------------------------------------------

    /**
     * NON-blocking 1-D NDRange on {@code q}. Does NOT call {@code clFinish}. If
     * {@code outEvent} is non-null, slot 0 receives the kernel event (the caller
     * owns it and must {@link #releaseEvent}). Use a blocking read or
     * {@code clFinish} afterwards to synchronise.
     */
    public void enqueue1DAsync(long q, long kernel, long globalWorkSize, long[] outEvent) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer global = stack.mallocPointer(1).put(0, globalWorkSize);
            PointerBuffer ev = outEvent != null ? stack.mallocPointer(1) : null;
            checkCL(clEnqueueNDRangeKernel(q, kernel, 1, null, global, null, null, ev));
            if (outEvent != null) {
                outEvent[0] = ev.get(0);
            }
        }
    }

    /** Non-blocking host->device write on {@code q} (no clFinish). */
    public void writeBufferAsync(long q, long mem, IntBuffer src) {
        checkCL(clEnqueueWriteBuffer(q, mem, false, 0, src, null, null));
    }

    /** Non-blocking host->device write on {@code q} (cl_long inputs; byte view, no clFinish). */
    public void writeBufferAsync(long q, long mem, LongBuffer src) {
        ByteBuffer bb = org.lwjgl.system.MemoryUtil.memByteBuffer(
                org.lwjgl.system.MemoryUtil.memAddress(src), src.remaining() * Long.BYTES);
        checkCL(clEnqueueWriteBuffer(q, mem, false, 0, bb, null, null));
    }

    /** Blocking read of {@code mem} into {@code dst} on {@code q}; this is the sync point. */
    public void readBufferBlocking(long q, long mem, FloatBuffer dst) {
        checkCL(clEnqueueReadBuffer(q, mem, true, 0, dst, null, null));
    }

    public void readBufferBlocking(long q, long mem, DoubleBuffer dst) {
        checkCL(clEnqueueReadBuffer(q, mem, true, 0, dst, null, null));
    }

    /** Blocking read of {@code mem} into {@code dst} on {@code q} (int outputs). */
    public void readBufferBlocking(long q, long mem, IntBuffer dst) {
        checkCL(clEnqueueReadBuffer(q, mem, true, 0, dst, null, null));
    }

    /** Blocking read of {@code mem} into {@code dst} on {@code q} (cl_long outputs; byte view). */
    public void readBufferBlocking(long q, long mem, LongBuffer dst) {
        ByteBuffer bb = org.lwjgl.system.MemoryUtil.memByteBuffer(
                org.lwjgl.system.MemoryUtil.memAddress(dst), dst.remaining() * Long.BYTES);
        checkCL(clEnqueueReadBuffer(q, mem, true, 0, bb, null, null));
    }

    /**
     * Blocking host-&gt;device write of {@code src} into {@code mem} on {@code q}; this
     * is a sync point (returns only once the write has completed). Used to make a host
     * array (e.g. cross-chunk-batched corner grids) device-resident so a later kernel on
     * the same in-order queue can read it. The {@code fp32}/{@code fp64} overload must
     * match the buffer's element type.
     */
    public void writeBufferBlocking(long q, long mem, FloatBuffer src) {
        checkCL(clEnqueueWriteBuffer(q, mem, true, 0, src, null, null));
    }

    public void writeBufferBlocking(long q, long mem, DoubleBuffer src) {
        checkCL(clEnqueueWriteBuffer(q, mem, true, 0, src, null, null));
    }

    /** Blocking host-&gt;device write of {@code src} into {@code mem} on {@code q} (int). */
    public void writeBufferBlocking(long q, long mem, IntBuffer src) {
        checkCL(clEnqueueWriteBuffer(q, mem, true, 0, src, null, null));
    }

    /** Blocking host-&gt;device write of {@code src} into {@code mem} on {@code q} (cl_long; byte view). */
    public void writeBufferBlocking(long q, long mem, LongBuffer src) {
        ByteBuffer bb = org.lwjgl.system.MemoryUtil.memByteBuffer(
                org.lwjgl.system.MemoryUtil.memAddress(src), src.remaining() * Long.BYTES);
        checkCL(clEnqueueWriteBuffer(q, mem, true, 0, bb, null, null));
    }

    /** Reads the [QUEUED->START, START->END] nanosecond deltas of a profiling event. */
    public static long[] eventProfile(long event) {
        long[] t = eventTimes(event);
        return t == null ? null : new long[]{t[1] - t[0], t[2] - t[1]};
    }

    /**
     * ABSOLUTE device timestamps {@code {queued, start, end}} (ns) of a profiling event,
     * or null if unavailable. Unlike {@link #eventProfile} (which returns deltas) these
     * are on the device's own timeline, so events from the SAME in-order queue can be
     * subtracted across commands to expose the inter-command gaps — i.e. the GPU idle
     * INSIDE a batch, which deltas alone cannot show.
     */
    public static long[] eventTimes(long event) {
        if (event == NULL) {
            return null;
        }
        try (MemoryStack stack = stackPush()) {
            LongBuffer queued = stack.mallocLong(1);
            LongBuffer start = stack.mallocLong(1);
            LongBuffer end = stack.mallocLong(1);
            if (clGetEventProfilingInfo(event, CL_PROFILING_COMMAND_QUEUED, queued, null) != CL_SUCCESS
                    || clGetEventProfilingInfo(event, CL_PROFILING_COMMAND_START, start, null) != CL_SUCCESS
                    || clGetEventProfilingInfo(event, CL_PROFILING_COMMAND_END, end, null) != CL_SUCCESS) {
                return null;
            }
            return new long[]{queued.get(0), start.get(0), end.get(0)};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Logs this kernel's driver-reported resource facts once, or nothing on error.
     *
     * <p>On GA104 these are what decide occupancy: 65536 registers/SM over 1536 resident
     * threads is 40 registers/thread for 100% occupancy, and a non-zero
     * {@code CL_KERNEL_PRIVATE_MEM_SIZE} is the register-spill signal (spills go to device
     * memory, so a spilling kernel is an IO problem wearing an ALU costume). Nothing in the
     * tree queried any of this before.
     */
    public void logKernelWorkGroupInfo(long kernel, String label) {
        if (kernel == NULL) {
            return;
        }
        try (MemoryStack stack = stackPush()) {
            PointerBuffer p = stack.mallocPointer(1);
            LongBuffer l = stack.mallocLong(1);
            if (CL10.clGetKernelWorkGroupInfo(kernel, device, CL10.CL_KERNEL_WORK_GROUP_SIZE, p, null) != CL_SUCCESS) {
                return;
            }
            long maxWg = p.get(0);
            long preferred = CL10.clGetKernelWorkGroupInfo(kernel, device,
                    CL11.CL_KERNEL_PREFERRED_WORK_GROUP_SIZE_MULTIPLE, p, null) == CL_SUCCESS ? p.get(0) : -1L;
            long localMem = CL10.clGetKernelWorkGroupInfo(kernel, device, CL10.CL_KERNEL_LOCAL_MEM_SIZE, l, null) == CL_SUCCESS ? l.get(0) : -1L;
            long privMem = CL10.clGetKernelWorkGroupInfo(kernel, device, CL11.CL_KERNEL_PRIVATE_MEM_SIZE, l, null) == CL_SUCCESS ? l.get(0) : -1L;
            LOGGER.info("[SuperChunk-GPU] [kernel-info] {}: maxWorkGroupSize={} preferredMultiple={} "
                            + "localMem={}B privateMem={}B{}",
                    label, maxWg, preferred, localMem, privMem,
                    privMem > 0 ? "  <-- NON-ZERO privateMem = register spill to device memory" : "");
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Shared kernel-setup helpers. These four were copy-pasted, byte-identical, into six
    // classes each (GpuFusedInterpolator, GpuFusedProgram, GpuDensityFunction,
    // GpuBatchDispatcher, DecideBench, GpuNoiseParityTest, RngSelfTest). One copy here.
    // ------------------------------------------------------------------

    /**
     * Uploads {@code data} as a read-only device buffer. OpenCL buffers must be non-empty,
     * so a zero-length array is padded to a single zero.
     */
    public long uploadInts(int[] data) {
        IntBuffer buf = org.lwjgl.system.MemoryUtil.memAllocInt(Math.max(1, data.length));
        try {
            if (data.length == 0) {
                buf.put(0).flip();
            } else {
                buf.put(data).flip();
            }
            return createInputBuffer(buf);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(buf);
        }
    }

    /**
     * Uploads a Perlin permutation table in the encoding {@link PermFormat#active()} selects.
     * The device-side element type comes from noise.cl's {@code perm_ptr} typedef, which the
     * matching {@code -D} (folded in by {@link #effectiveOptions}) switches — so host bytes
     * and kernel type can never disagree, and the program cache keys on the flag.
     *
     * @param perm 0..255 values, 256 per octave (exactly vanilla's {@code p[i] & 0xFF})
     */
    public long uploadPerm(int[] perm) {
        byte[] bytes = PermFormat.active().encode(perm);
        ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(Math.max(1, bytes.length));
        try {
            if (bytes.length == 0) {
                buf.put((byte) 0);
            } else {
                buf.put(bytes);
            }
            buf.flip();
            return createInputBuffer(buf);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(buf);
        }
    }

    /** {@link #uploadInts} for reals, narrowing to float when the program is built fp32. */
    public long uploadReals(boolean fp32, double[] data) {
        int len = Math.max(1, data.length);
        if (fp32) {
            FloatBuffer buf = org.lwjgl.system.MemoryUtil.memAllocFloat(len);
            try {
                if (data.length == 0) {
                    buf.put(0.0f);
                } else {
                    for (double v : data) {
                        buf.put((float) v);
                    }
                }
                buf.flip();
                return createInputBuffer(buf);
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(buf);
            }
        }
        DoubleBuffer buf = org.lwjgl.system.MemoryUtil.memAllocDouble(len);
        try {
            if (data.length == 0) {
                buf.put(0.0);
            } else {
                buf.put(data);
            }
            buf.flip();
            return createInputBuffer(buf);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(buf);
        }
    }

    /**
     * Rounds a 1-D global work size up to a multiple of 64. Every kernel in this tree guards
     * {@code if (gid >= total) return;}, so over-rounding costs only idle lanes. All launch
     * sites pass {@code local_work_size = NULL}, and OpenCL 1.2 uniform-work-group rules make
     * the driver's chosen local size divide the global size — so this value caps the group
     * size the driver may pick. Raising it to 256 was measured NEUTRAL on GA104 (both hot
     * kernels report {@code CL_KERNEL_PRIVATE_MEM_SIZE = 0}, i.e. occupancy is not
     * register-bound there), so 64 stands.
     */
    public static long roundUpGlobal(long n) {
        return ((n + 63) / 64) * 64L;
    }

    /** Reads a kernel source resource (absolute classpath path), or null if missing/unreadable. */
    public static String loadKernelSource(String path) {
        try (java.io.InputStream in = CLProgram.class.getResourceAsStream(path)) {
            if (in == null) {
                LOGGER.error("[SuperChunk-GPU] kernel resource not found: {}", path);
                return null;
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            LOGGER.error("[SuperChunk-GPU] failed reading kernel resource {}", path, e);
            return null;
        }
    }

    /** Releases a cl_event. Swallows errors. */
    public static void releaseEvent(long event) {
        if (event != NULL) {
            try {
                clReleaseEvent(event);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // ASYNC readback (non-blocking GPU readback for the fused per-chunk dispatch).
    //
    // enqueueReadAsync issues a NON-blocking device->host copy and returns the read
    // event in slot 0 of outEvent. The caller can do other (CPU) work, then
    // waitForEvent ONLY when the result is actually needed — overlapping the GPU
    // dispatch+readback with that intervening CPU work. eventComplete lets the
    // caller measure whether the read already finished (no wait = latency hidden).
    // ------------------------------------------------------------------

    /**
     * NON-blocking read of {@code mem} into {@code dst} on {@code q}. Slot 0 of
     * {@code outEvent} receives the read event (the caller owns it and must
     * {@link #releaseEvent} / sync via {@link #waitForEvent}). {@code dst} must stay
     * alive + untouched until the event completes.
     */
    public void enqueueReadAsync(long q, long mem, DoubleBuffer dst, long[] outEvent) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer ev = outEvent != null ? stack.mallocPointer(1) : null;
            checkCL(clEnqueueReadBuffer(q, mem, false, 0, dst, null, ev));
            if (outEvent != null) {
                outEvent[0] = ev.get(0);
            }
        }
    }

    /** fp32 overload of {@link #enqueueReadAsync(long, long, DoubleBuffer, long[])}. */
    public void enqueueReadAsync(long q, long mem, FloatBuffer dst, long[] outEvent) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer ev = outEvent != null ? stack.mallocPointer(1) : null;
            checkCL(clEnqueueReadBuffer(q, mem, false, 0, dst, null, ev));
            if (outEvent != null) {
                outEvent[0] = ev.get(0);
            }
        }
    }

    /** Byte overload of {@link #enqueueReadAsync(long, long, DoubleBuffer, long[])} (1-byte/block id readback). */
    public void enqueueReadAsync(long q, long mem, java.nio.ByteBuffer dst, long[] outEvent) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer ev = outEvent != null ? stack.mallocPointer(1) : null;
            checkCL(clEnqueueReadBuffer(q, mem, false, 0, dst, null, ev));
            if (outEvent != null) {
                outEvent[0] = ev.get(0);
            }
        }
    }

    /**
     * Issues a {@code clFlush} so previously-enqueued commands on {@code q} are
     * submitted to the device (non-blocking). Used after an async dispatch+read so
     * the GPU actually starts the work while the host does other things.
     */
    public void flush(long q) {
        checkCL(CL10.clFlush(q));
    }

    /** Blocks until {@code event} completes. Returns false on any error / NULL. */
    public static boolean waitForEvent(long event) {
        if (event == NULL) {
            return false;
        }
        try {
            return clWaitForEvents(event) == CL_SUCCESS;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Non-blocking query: true iff {@code event}'s command has already completed
     * (execution status == {@code CL_COMPLETE}). Used purely for instrumentation
     * (did the async readback finish before we needed it?). Never throws.
     */
    public static boolean eventComplete(long event) {
        if (event == NULL) {
            return false;
        }
        try (MemoryStack stack = stackPush()) {
            IntBuffer status = stack.mallocInt(1);
            int rc = clGetEventInfo(event, CL_EVENT_COMMAND_EXECUTION_STATUS, status, null);
            return rc == CL_SUCCESS && status.get(0) == CL_COMPLETE;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Mapped / pinned host buffers (Phase B: zero-copy on iGPU, pinned on dGPU).
    //
    // CL_MEM_ALLOC_HOST_PTR asks the driver to allocate the buffer in host-
    // accessible (pinned/unified) memory. clEnqueueMapBuffer then returns a host
    // pointer aliasing it: on a unified-memory iGPU no copy happens at all; on a
    // discrete GPU the map/unmap drives a pinned (fast) DMA.
    // ------------------------------------------------------------------

    /** Read-only device buffer in pinned/host memory (for coord uploads). */
    public long createHostInputBuffer(long bytes) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long mem = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_ALLOC_HOST_PTR, bytes, err);
            checkCL(err.get(0));
            return mem;
        }
    }

    /** Write-only device buffer in pinned/host memory (for result readback). */
    public long createHostOutputBuffer(long bytes) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            long mem = clCreateBuffer(context, CL_MEM_WRITE_ONLY | CL_MEM_ALLOC_HOST_PTR, bytes, err);
            checkCL(err.get(0));
            return mem;
        }
    }

    /**
     * Blocking map of {@code bytes} bytes of {@code mem} for WRITE (host fills it,
     * then {@link #unmap}). Returns a raw host pointer (off-heap address).
     */
    public long mapWrite(long q, long mem, long bytes) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            java.nio.ByteBuffer mapped = clEnqueueMapBuffer(q, mem, true, CL_MAP_WRITE, 0, bytes,
                    null, null, err, null);
            checkCL(err.get(0));
            return mapped == null ? NULL : org.lwjgl.system.MemoryUtil.memAddress(mapped);
        }
    }

    /**
     * Blocking map of {@code bytes} bytes of {@code mem} for READ (host reads the
     * result). Returns a raw host pointer. This map is the sync point for a
     * preceding non-blocking kernel on the same in-order queue.
     */
    public long mapRead(long q, long mem, long bytes) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            java.nio.ByteBuffer mapped = clEnqueueMapBuffer(q, mem, true, CL_MAP_READ, 0, bytes,
                    null, null, err, null);
            checkCL(err.get(0));
            return mapped == null ? NULL : org.lwjgl.system.MemoryUtil.memAddress(mapped);
        }
    }

    /**
     * NON-blocking map of {@code bytes} bytes of {@code mem} for READ — the zero-copy
     * (host-resident buffer, e.g. {@code CL_MEM_ALLOC_HOST_PTR}) counterpart of the async
     * {@link #enqueueReadAsync}. Returns the raw host pointer (off-heap address) IMMEDIATELY,
     * exactly like the blocking {@link #mapRead}, but does NOT wait: slot 0 of {@code outEvent}
     * receives the map event, which completes only once the map — and every preceding in-order
     * command that produced {@code mem}'s contents (e.g. the kernel that wrote it) — has
     * finished. The caller owns the event ({@link #releaseEvent} / sync via {@link #waitForEvent})
     * and MUST NOT read through the returned pointer before the event completes, then MUST
     * {@link #unmap} the SAME pointer before {@code mem} is written again. Mirrors {@link #mapRead}
     * (error/pointer handling) and the async-read (event handling) patterns.
     */
    public long mapReadAsync(long q, long mem, long bytes, long[] outEvent) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer err = stack.mallocInt(1);
            PointerBuffer ev = outEvent != null ? stack.mallocPointer(1) : null;
            java.nio.ByteBuffer mapped = clEnqueueMapBuffer(q, mem, false, CL_MAP_READ, 0, bytes,
                    null, ev, err, null);
            checkCL(err.get(0));
            if (outEvent != null) {
                outEvent[0] = ev.get(0);
            }
            return mapped == null ? NULL : org.lwjgl.system.MemoryUtil.memAddress(mapped);
        }
    }

    /** Unmaps a previously mapped buffer (host pointer {@code addr}, blocking). */
    public void unmap(long q, long mem, long addr, long bytes) {
        if (addr == NULL) {
            return;
        }
        java.nio.ByteBuffer mapped = org.lwjgl.system.MemoryUtil.memByteBuffer(addr, (int) bytes);
        checkCL(clEnqueueUnmapMemObject(q, mem, mapped, null, null));
    }

    /** Blocks until all commands on {@code q} complete (explicit sync). */
    public void finish(long q) {
        checkCL(CL10.clFinish(q));
    }

    /** Releases a {@code cl_mem} object. Swallows errors. */
    public static void releaseMem(long mem) {
        if (mem != NULL) {
            try {
                clReleaseMemObject(mem);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Releases a kernel. Swallows errors. */
    public static void releaseKernel(long kernel) {
        if (kernel != NULL) {
            try {
                clReleaseKernel(kernel);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public void close() {
        // Cache-shared programs (refCount >= 0) only release at the last owner.
        int rc = refCount.get();
        if (rc >= 0) {
            int n = refCount.decrementAndGet();
            if (n > 0) {
                return;   // still referenced
            }
            if (n < 0) {
                // Over-close: the count already reached 0 (the program was freed by the
                // last legitimate close). Restore the floor so a further close cannot
                // drop it again, and do NOT re-release (double-free guard).
                refCount.set(0);
                return;
            }
            // n == 0: this close dropped the LAST reference -> fall through to release.
        }
        // Un-shared (rc == -1) or last-shared (n == 0): release exactly once. The
        // compareAndSet closes the window where two threads both observe a releasable
        // state and would both call clReleaseProgram.
        if (released.compareAndSet(false, true) && program != NULL) {
            try {
                clReleaseProgram(program);
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Compiled-binary extraction / load (program binary cache).
    //
    // clBuildProgram is a driver-side JIT that runs once per distinct kernel at
    // world load (~100 builds, a few ms each = noticeable boot cost). We persist
    // the compiled binary so later boots clCreateProgramWithBinary instead — no
    // recompile. Binaries are device+driver specific, so the cache key includes
    // the device name + driver version (mismatch -> rebuild).
    // ------------------------------------------------------------------

    /** Extracts this program's single-device compiled binary, or {@code null}. */
    public byte[] getBinary() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer sizes = stack.mallocPointer(1);
            int rc = clGetProgramInfo(program, CL_PROGRAM_BINARY_SIZES, sizes, null);
            if (rc != CL_SUCCESS) {
                return null;
            }
            int size = (int) sizes.get(0);
            if (size <= 0) {
                return null;
            }
            java.nio.ByteBuffer bin = org.lwjgl.system.MemoryUtil.memAlloc(size);
            try {
                // CL_PROGRAM_BINARIES wants an array of pointers (one per device).
                PointerBuffer ptrs = stack.mallocPointer(1).put(0, org.lwjgl.system.MemoryUtil.memAddress(bin));
                rc = nclGetProgramInfo(program, CL_PROGRAM_BINARIES,
                        ptrs.remaining() * Pointer_SIZE(), ptrs.address(), NULL);
                if (rc != CL_SUCCESS) {
                    return null;
                }
                byte[] out = new byte[size];
                bin.get(out);
                return out;
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(bin);
            }
        } catch (Throwable t) {
            LOGGER.debug("[SuperChunk-GPU] getBinary failed", t);
            return null;
        }
    }

    private static int Pointer_SIZE() {
        return org.lwjgl.system.Pointer.POINTER_SIZE;
    }

    /**
     * Builds a program from a previously cached device binary. Returns the program
     * on success (already built), or {@code null} if the binary is rejected (the
     * caller then recompiles from source).
     */
    public static CLProgram buildFromBinary(byte[] binary, String options) {
        if (!OpenCLBackend.isAvailable() || binary == null || binary.length == 0) {
            return null;
        }
        long context = OpenCLBackend.context();
        long queue = OpenCLBackend.queue();
        long device = OpenCLBackend.device().device();
        long program = NULL;
        java.nio.ByteBuffer bin = null;
        try (MemoryStack stack = stackPush()) {
            bin = org.lwjgl.system.MemoryUtil.memAlloc(binary.length);
            bin.put(binary).flip();
            PointerBuffer devices = stack.mallocPointer(1).put(0, device);
            IntBuffer binStatus = stack.mallocInt(1);
            IntBuffer err = stack.mallocInt(1);
            program = clCreateProgramWithBinary(context, devices,
                    new java.nio.ByteBuffer[]{bin}, binStatus, err);
            if (err.get(0) != CL_SUCCESS || binStatus.get(0) != CL_SUCCESS || program == NULL) {
                if (program != NULL) clReleaseProgram(program);
                return null;
            }
            // Same choke-point normalization as build(): the binary was cached under an
            // options-folded key, so a flag flip never reaches here with a stale binary —
            // but keep the options string consistent for the (options-tolerant) binary build.
            int buildResult = clBuildProgram(program, device, effectiveOptions(options), null, NULL);
            if (buildResult != CL_SUCCESS) {
                clReleaseProgram(program);
                return null;
            }
            return new CLProgram(context, queue, device, program);
        } catch (Throwable t) {
            LOGGER.debug("[SuperChunk-GPU] buildFromBinary threw — will recompile from source.", t);
            if (program != NULL) {
                try { clReleaseProgram(program); } catch (Throwable ignored) {}
            }
            return null;
        } finally {
            if (bin != null) org.lwjgl.system.MemoryUtil.memFree(bin);
        }
    }

    /** Throws {@link CLException} if {@code errcode} is not {@code CL_SUCCESS}. */
    public static void checkCL(int errcode) {
        if (errcode != CL_SUCCESS) {
            throw new CLException(errcode);
        }
    }

    /** Unchecked exception carrying an OpenCL error code. */
    public static final class CLException extends RuntimeException {
        public final int code;

        public CLException(int code) {
            super("OpenCL error " + code);
            this.code = code;
        }
    }
}
