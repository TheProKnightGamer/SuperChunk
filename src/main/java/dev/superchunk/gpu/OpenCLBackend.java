package dev.superchunk.gpu;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL;
import org.lwjgl.opencl.CLCapabilities;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.opencl.CL12.CL_DEVICE_DOUBLE_FP_CONFIG;
import static org.lwjgl.opencl.CL12.CL_FP_CORRECTLY_ROUNDED_DIVIDE_SQRT;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memUTF8;

/**
 * Static manager for SuperChunk's OpenCL backend (Stage 0).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Enumerate OpenCL platforms and their GPU devices, logging each device's
 *       capabilities.</li>
 *   <li>Pick a device (config-pinned, else first fp64-capable GPU unless
 *       {@code desiredFp=fp32}, else first GPU).</li>
 *   <li>Create a {@code cl_context} + command queue.</li>
 *   <li>Expose getters and {@link #shutdown()}.</li>
 * </ul>
 *
 * <p><b>Failure policy:</b> EVERYTHING is wrapped in try/catch. Any failure (no
 * OpenCL ICD installed, no GPU device, context/queue creation error) is logged
 * clearly and leaves {@link #isAvailable()} == false. {@link #init} NEVER throws
 * — the server must boot fine with no GPU (graceful CPU fallback).
 */
public final class OpenCLBackend {
    /** Shared logger for the whole gpu package. */
    public static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-GPU");

    private static volatile boolean available = false;
    private static GpuConfig config;
    private static OpenCLDevice chosenDevice;
    /** Resolved precision path: true = fp32 (float) kernels, false = fp64 (double). Set once in {@link #init}. */
    private static volatile boolean fp32 = false;
    /**
     * True iff {@code -cl-fp32-correctly-rounded-divide-sqrt} is appended to EVERY
     * {@code clBuildProgram} options string this session. Resolved once in {@link #init}:
     * the chosen device must advertise {@code CL_FP_CORRECTLY_ROUNDED_DIVIDE_SQRT} in
     * {@code CL_DEVICE_SINGLE_FP_CONFIG} AND {@code -Dsuperchunk.gpu.crDivide} must not be
     * {@code false} (default: applied whenever the device supports it — a conformance-
     * tightening flag, the last compiler-level fp32 spline-drift lever). Consumed by
     * {@link CLProgram#effectiveOptions(String)}, the single build-options choke point.
     */
    private static volatile boolean crDivide = false;
    private static long context = NULL;
    private static long queue = NULL;

    /**
     * Central registry of per-thread / per-DF GPU resource owners (created lazily
     * by {@code GpuDensityFunction} as worldgen worker threads first touch a GPU
     * density function). On {@link #shutdown()} every registered owner is released
     * so no {@code cl_command_queue}/{@code cl_kernel}/{@code cl_mem} leaks. Uses a
     * concurrent set because owners register from many c2me-worker threads.
     */
    private static final java.util.Set<AutoCloseable> RESOURCE_OWNERS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Set true at the top of {@link #shutdown()} (and cleared in {@link #init()}).
     * While set, {@link #registerResourceOwner(AutoCloseable)} closes the owner
     * immediately instead of registering it, so an owner created by a still-running
     * c2me worker thread after the shutdown drain loop cannot be leaked nor left
     * pointing at an already-released context.
     */
    private static volatile boolean shuttingDown = false;

    private OpenCLBackend() {
    }

    /**
     * Registers a per-thread/per-DF resource owner so {@link #shutdown()} can
     * release it. Idempotent per owner. Safe to call from any worker thread.
     */
    public static void registerResourceOwner(AutoCloseable owner) {
        if (owner == null) {
            return;
        }
        if (!shuttingDown) {
            RESOURCE_OWNERS.add(owner);
        }
        // Re-check after the add: if a shutdown started concurrently (and its drain
        // loop may already have iterated past this point), take ownership of closing
        // it here and make sure it is not left lingering in the registry. close() is
        // idempotent, so a double close with the drain loop is a harmless no-op.
        if (shuttingDown) {
            RESOURCE_OWNERS.remove(owner);
            try {
                owner.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Unregisters a resource owner that has already released itself. */
    public static void unregisterResourceOwner(AutoCloseable owner) {
        if (owner != null) {
            RESOURCE_OWNERS.remove(owner);
        }
    }

    /**
     * Enumerates OpenCL devices and, if enabled, sets up a context + queue on the
     * chosen device. Never throws. Idempotent-ish: calling twice re-inits.
     */
    public static synchronized void init() {
        // A prior shutdown() leaves shuttingDown=true so any late resource-owner
        // registration self-closes; clear it now that we are (re)initializing and
        // will legitimately accept resource owners again.
        shuttingDown = false;
        // Idempotent: mod construction can re-enter; if we already set up a context, keep it.
        if (isAvailable()) {
            return;
        }
        try {
            config = GpuConfig.load();
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] config load failed; backend disabled", t);
            return;
        }

        if (!config.isEnabled()) {
            LOGGER.info("[SuperChunk-GPU] GPU disabled (config enabled=false) — using CPU only.");
            return;
        }

        try {
            // Make the lwjgl-core native (liblwjgl.so / lwjgl.dll) loadable from our bundled
            // natives jar BEFORE CL.create() pulls in lwjgl — so the mod is self-contained on a
            // dedicated server without -Dorg.lwjgl.librarypath.
            ensureNativeLibrary();
            // Discover the system OpenCL ICD. CL.create() throws IllegalStateException if OpenCL
            // was already created (e.g. a prior init() pass, or another LWJGL consumer). Only
            // create when the function provider isn't set yet; otherwise reuse the live ICD.
            if (CL.getFunctionProvider() == null) {
                CL.create();
            }
        } catch (Throwable t) {
            // If it's the "already created" state, the ICD is actually live — fall through to
            // enumeration. Any other failure (no driver) means no GPU.
            if (CL.getFunctionProvider() == null) {
                LOGGER.warn("[SuperChunk-GPU] No OpenCL ICD/driver available — GPU disabled, CPU fallback. ({})",
                        t.toString());
                return;
            }
            LOGGER.info("[SuperChunk-GPU] OpenCL already initialized by another consumer — reusing ICD.");
        }

        try {
            List<OpenCLDevice> devices = enumerate();
            if (devices.isEmpty()) {
                LOGGER.warn("[SuperChunk-GPU] No OpenCL GPU devices found — GPU disabled, CPU fallback.");
                return;
            }

            OpenCLDevice picked = pick(devices);
            if (picked == null) {
                LOGGER.warn("[SuperChunk-GPU] Could not select a device — GPU disabled, CPU fallback.");
                return;
            }
            LOGGER.info("[SuperChunk-GPU] Selected device: {}", picked.describe());

            if (config.fpMode() == FpMode.FP64 && !picked.fp64()) {
                // PRODUCTION-SAFETY GUARD: desiredFp=fp64 (the default) but the selected
                // device has no fp64. fp32 worldgen is NOT vanilla-accurate and would
                // corrupt terrain, so we REFUSE to engage the GPU at all — the backend
                // stays unavailable and worldgen runs the bit-exact CPU path. (Set
                // desiredFp=auto to allow fp32 fallback, or =fp32 to force it.)
                LOGGER.warn("[SuperChunk-GPU] desiredFp=fp64 but the selected device lacks fp64 "
                        + "(cl_khr_fp64). REFUSING to engage the GPU to protect vanilla accuracy — "
                        + "worldgen falls back to the CPU. (Use an fp64 GPU, or set desiredFp=auto "
                        + "to allow non-accurate fp32, or desiredFp=fp32 to force it — INTEGRATION testing only.)");
                return; // available stays false -> clean CPU fallback
            }

            // Resolve the precision path ONCE, here, from the desired mode + device caps.
            // Every downstream consumer (kernel -DUSE_FP32 compile, all parity tests) reads
            // this via isFp32() so the reported mode and the actual math always agree.
            fp32 = config.fpMode().useFp32(picked.fp64());

            // Resolve the correctly-rounded fp32 divide/sqrt build flag ONCE, before any
            // clBuildProgram can run (builds require isAvailable(), set below). Default ON
            // when the device advertises the cap; -Dsuperchunk.gpu.crDivide=false overrides.
            // Flipping this changes CLProgramCache's options-folded disk key -> intended
            // cold recompile of every kernel.
            boolean crWant = Boolean.parseBoolean(System.getProperty("superchunk.gpu.crDivide", "true"));
            crDivide = crWant && picked.crDivideFp32();

            if (!createContextAndQueue(picked)) {
                return; // already logged
            }

            chosenDevice = picked;
            available = true;
            LOGGER.info("[SuperChunk-GPU] Precision: {} (desiredFp={}, device fp64={}).",
                    fp32 ? "FP32 (float) — NOT vanilla-accurate, perf/integration only"
                         : "FP64 (double) — vanilla-accurate",
                    config.fpMode().token(), picked.fp64());
            LOGGER.info("[SuperChunk-GPU] fp32 correctly-rounded divide/sqrt: device advertises "
                            + "CL_FP_CORRECTLY_ROUNDED_DIVIDE_SQRT={}, -cl-fp32-correctly-rounded-divide-sqrt "
                            + "applied={} (-Dsuperchunk.gpu.crDivide={}).",
                    picked.crDivideFp32(), crDivide, crWant);
            LOGGER.info("[SuperChunk-GPU] OpenCL backend ready (context + queue created).");
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] OpenCL init failed — GPU disabled, CPU fallback.", t);
            shutdown(); // release whatever partially allocated
        }
    }

    /**
     * Extracts the OS-appropriate lwjgl-core native from the bundled
     * {@code META-INF/jarjar/lwjgl-3.3.3-natives-<os>.jar} to a temp dir and points
     * {@link Configuration#LIBRARY_PATH} at it, so {@code CL.create()} can dlopen the
     * OpenCL ICD without an external {@code -Dorg.lwjgl.librarypath}.
     *
     * <p>Why this exists: the SERVER jar embeds the natives classifier jars; their FILES
     * survive under META-INF/jarjar/ even though JarJar only loads one org.lwjgl module.
     * A CLIENT jar deliberately bundles NO lwjgl core/natives (the game already provides
     * module org.lwjgl + its native), so the jar is simply absent here — that's expected,
     * not an error: {@code CL.create()} then uses the game's already-loaded lwjgl native.
     * Best-effort: respects a user-set {@code -Dorg.lwjgl.librarypath}; on any failure
     * it logs and lets {@code CL.create()} fall back (GPU disabled, CPU path).
     */
    private static void ensureNativeLibrary() {
        try {
            if (Configuration.LIBRARY_PATH.get() != null) {
                return; // a path was already provided (e.g. -Dorg.lwjgl.librarypath) — respect it
            }
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            String nativesJar;
            String libName;
            if (os.contains("win")) {
                nativesJar = "/META-INF/jarjar/lwjgl-3.3.3-natives-windows.jar";
                libName = "lwjgl.dll";
            } else if (os.contains("mac") || os.contains("darwin")) {
                LOGGER.info("[SuperChunk-GPU] no bundled macOS lwjgl native — set -Dorg.lwjgl.librarypath to enable GPU.");
                return;
            } else {
                nativesJar = "/META-INF/jarjar/lwjgl-3.3.3-natives-linux.jar";
                libName = "liblwjgl.so";
            }
            try (java.io.InputStream jarIn = OpenCLBackend.class.getResourceAsStream(nativesJar)) {
                if (jarIn == null) {
                    // Expected on a CLIENT jar: no lwjgl native is bundled, so fall through to the
                    // game's own already-loaded lwjgl native. Only matters on a headless server jar.
                    LOGGER.info("[SuperChunk-GPU] no bundled lwjgl native ({}) — using the host's "
                            + "lwjgl native (normal on a client). Set -Dorg.lwjgl.librarypath if GPU init fails.",
                            nativesJar);
                    return;
                }
                java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("superchunk-lwjgl");
                dir.toFile().deleteOnExit();
                try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(jarIn)) {
                    java.util.zip.ZipEntry e;
                    while ((e = zin.getNextEntry()) != null) {
                        if (!e.isDirectory() && e.getName().endsWith(libName)) {
                            java.nio.file.Path out = dir.resolve(libName);
                            java.nio.file.Files.copy(zin, out);
                            out.toFile().deleteOnExit();
                            Configuration.LIBRARY_PATH.set(dir.toAbsolutePath().toString());
                            LOGGER.info("[SuperChunk-GPU] extracted bundled LWJGL native {} -> {} (self-contained).",
                                    libName, dir);
                            return;
                        }
                    }
                }
                LOGGER.warn("[SuperChunk-GPU] {} not found inside {} — set -Dorg.lwjgl.librarypath if GPU init fails.",
                        libName, nativesJar);
            }
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] LWJGL native extraction failed — set -Dorg.lwjgl.librarypath if GPU init fails.", t);
        }
    }

    /** Enumerates every platform + GPU device (falling back to ALL), logging caps. */
    private static List<OpenCLDevice> enumerate() {
        List<OpenCLDevice> result = new ArrayList<>();
        try (MemoryStack stack = stackPush()) {
            IntBuffer numPlatforms = stack.mallocInt(1);
            int rc = clGetPlatformIDs(null, numPlatforms);
            if (rc != CL_SUCCESS || numPlatforms.get(0) == 0) {
                LOGGER.warn("[SuperChunk-GPU] clGetPlatformIDs found no platforms (rc={})", rc);
                return result;
            }
            int platformCount = numPlatforms.get(0);
            PointerBuffer platforms = stack.mallocPointer(platformCount);
            checkRC(clGetPlatformIDs(platforms, (IntBuffer) null), "clGetPlatformIDs");

            LOGGER.info("[SuperChunk-GPU] Found {} OpenCL platform(s):", platformCount);
            for (int p = 0; p < platformCount; p++) {
                long platform = platforms.get(p);
                String pName = getPlatformInfo(platform, CL_PLATFORM_NAME);
                String pVersion = getPlatformInfo(platform, CL_PLATFORM_VERSION);
                LOGGER.info("[SuperChunk-GPU]  Platform[{}]: {} ({})", p, pName, pVersion);

                long[] deviceIds = getDeviceIds(platform);
                if (deviceIds.length == 0) {
                    LOGGER.info("[SuperChunk-GPU]   (no devices on this platform)");
                    continue;
                }
                for (long deviceId : deviceIds) {
                    OpenCLDevice dev = readDevice(platform, deviceId);
                    result.add(dev);
                    LOGGER.info("[SuperChunk-GPU]   Device: {}", dev.describe());
                }
            }
        }
        return result;
    }

    /** GPU devices on a platform; falls back to ALL if no GPU. */
    private static long[] getDeviceIds(long platform) {
        long[] gpu = queryDeviceIds(platform, CL_DEVICE_TYPE_GPU);
        if (gpu.length > 0) {
            return gpu;
        }
        LOGGER.info("[SuperChunk-GPU]   No GPU devices; trying CL_DEVICE_TYPE_ALL.");
        return queryDeviceIds(platform, CL_DEVICE_TYPE_ALL);
    }

    private static long[] queryDeviceIds(long platform, long type) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            int rc = clGetDeviceIDs(platform, type, null, count);
            if (rc != CL_SUCCESS || count.get(0) == 0) {
                return new long[0];
            }
            int n = count.get(0);
            PointerBuffer ids = stack.mallocPointer(n);
            rc = clGetDeviceIDs(platform, type, ids, (IntBuffer) null);
            if (rc != CL_SUCCESS) {
                return new long[0];
            }
            long[] out = new long[n];
            for (int i = 0; i < n; i++) {
                out[i] = ids.get(i);
            }
            return out;
        } catch (Throwable t) {
            return new long[0];
        }
    }

    private static OpenCLDevice readDevice(long platform, long device) {
        String name = getDeviceInfoString(device, CL_DEVICE_NAME);
        String vendor = getDeviceInfoString(device, CL_DEVICE_VENDOR);
        String version = getDeviceInfoString(device, CL_DEVICE_VERSION);
        String extensions = getDeviceInfoString(device, CL_DEVICE_EXTENSIONS);
        int computeUnits = getDeviceInfoInt(device, CL_DEVICE_MAX_COMPUTE_UNITS);
        long globalMem = getDeviceInfoLong(device, CL_DEVICE_GLOBAL_MEM_SIZE);
        long fpConfig = getDeviceInfoLong(device, CL_DEVICE_DOUBLE_FP_CONFIG);
        boolean fp64 = fpConfig != 0L || (extensions != null && extensions.contains("cl_khr_fp64"));
        // CL_DEVICE_SINGLE_FP_CONFIG is a cl_device_fp_config (cl_bitfield = 64-bit), so the
        // 8-byte read is correct here (unlike the 4-byte cl_uint MAX_COMPUTE_UNITS above).
        // Bit (1<<7) = CL_FP_CORRECTLY_ROUNDED_DIVIDE_SQRT (core OpenCL 1.2): the device can
        // honor the -cl-fp32-correctly-rounded-divide-sqrt build flag.
        long singleFpConfig = getDeviceInfoLong(device, CL_DEVICE_SINGLE_FP_CONFIG);
        boolean crDivideFp32 = (singleFpConfig & CL_FP_CORRECTLY_ROUNDED_DIVIDE_SQRT) != 0L;
        return new OpenCLDevice(platform, device, name, vendor, version, computeUnits, globalMem, fp64, crDivideFp32);
    }

    /** Picks a device per config: explicit indices, else fp64-preferred GPU, else first. */
    private static OpenCLDevice pick(List<OpenCLDevice> devices) {
        int pIdx = config.platformIndex();
        int dIdx = config.deviceIndex();

        if (pIdx >= 0 && dIdx >= 0) {
            // Resolve platform-relative device index against the flat enumerated list.
            List<OpenCLDevice> onPlatform = new ArrayList<>();
            // Collect distinct platforms in enumeration order.
            List<Long> platformOrder = new ArrayList<>();
            for (OpenCLDevice d : devices) {
                if (!platformOrder.contains(d.platform())) {
                    platformOrder.add(d.platform());
                }
            }
            if (pIdx < platformOrder.size()) {
                long platform = platformOrder.get(pIdx);
                for (OpenCLDevice d : devices) {
                    if (d.platform() == platform) {
                        onPlatform.add(d);
                    }
                }
                if (dIdx < onPlatform.size()) {
                    return onPlatform.get(dIdx);
                }
            }
            LOGGER.warn("[SuperChunk-GPU] config platformIndex={}/deviceIndex={} out of range — auto-picking.",
                    pIdx, dIdx);
        }

        // Auto: prefer an fp64-capable device unless precision is force-fp32.
        if (config.fpMode().prefersFp64Device()) {
            for (OpenCLDevice d : devices) {
                if (d.fp64()) {
                    return d;
                }
            }
            LOGGER.warn("[SuperChunk-GPU] No fp64-capable device; falling back to first device.");
        }
        return devices.get(0);
    }

    private static boolean createContextAndQueue(OpenCLDevice dev) {
        try (MemoryStack stack = stackPush()) {
            // Context properties: CL_CONTEXT_PLATFORM, <platform>, 0
            PointerBuffer ctxProps = stack.mallocPointer(3);
            ctxProps.put(0, CL_CONTEXT_PLATFORM).put(1, dev.platform()).put(2, 0);

            PointerBuffer deviceBuf = stack.mallocPointer(1).put(0, dev.device());
            IntBuffer err = stack.mallocInt(1);

            // No callback (null) — keeps Stage 0 simple; errors surface via return codes.
            long ctx = clCreateContext(ctxProps, deviceBuf, null, NULL, err);
            if (err.get(0) != CL_SUCCESS || ctx == NULL) {
                LOGGER.warn("[SuperChunk-GPU] clCreateContext failed (err={})", err.get(0));
                return false;
            }
            context = ctx;

            // clCreateCommandQueue is fine for OpenCL 1.2 (deprecated in 2.0 but
            // still exported by all ICDs). Stage 0 needs no queue properties.
            long q = clCreateCommandQueue(context, dev.device(), 0L, err);
            if (err.get(0) != CL_SUCCESS || q == NULL) {
                LOGGER.warn("[SuperChunk-GPU] clCreateCommandQueue failed (err={})", err.get(0));
                // Release the context we just created so a later re-entrant init()
                // (which overwrites the `context` field) cannot leak this handle.
                if (context != NULL) {
                    clReleaseContext(context);
                    context = NULL;
                }
                return false;
            }
            queue = q;
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] context/queue creation threw", t);
            // Same reasoning: if the context was already created before the throw,
            // release it here so it is not leaked on the failure/retry path.
            if (context != NULL) {
                clReleaseContext(context);
                context = NULL;
            }
            return false;
        }
    }

    // ---- info helpers ----

    private static String getPlatformInfo(long platform, int param) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            if (clGetPlatformInfo(platform, param, (java.nio.ByteBuffer) null, size) != CL_SUCCESS) {
                return "?";
            }
            int n = (int) size.get(0);
            if (n <= 0) {
                return "";
            }
            java.nio.ByteBuffer buf = stack.malloc(n);
            if (clGetPlatformInfo(platform, param, buf, null) != CL_SUCCESS) {
                return "?";
            }
            return memUTF8(buf, n - 1); // strip trailing NUL
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String getDeviceInfoString(long device, int param) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            if (clGetDeviceInfo(device, param, (java.nio.ByteBuffer) null, size) != CL_SUCCESS) {
                return "";
            }
            int n = (int) size.get(0);
            if (n <= 0) {
                return "";
            }
            java.nio.ByteBuffer buf = stack.malloc(n);
            if (clGetDeviceInfo(device, param, buf, null) != CL_SUCCESS) {
                return "";
            }
            return memUTF8(buf, n - 1); // strip trailing NUL
        } catch (Throwable t) {
            return "";
        }
    }

    private static long getDeviceInfoLong(long device, int param) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer val = stack.mallocLong(1);
            if (clGetDeviceInfo(device, param, val, null) != CL_SUCCESS) {
                return 0L;
            }
            return val.get(0);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static int getDeviceInfoInt(long device, int param) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer val = stack.mallocInt(1);
            if (clGetDeviceInfo(device, param, val, null) != CL_SUCCESS) {
                return 0;
            }
            return val.get(0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void checkRC(int rc, String what) {
        if (rc != CL_SUCCESS) {
            throw new CLProgram.CLException(rc);
        }
    }

    // ---- public surface ----

    /** True only if init succeeded and a device/context exist. */
    public static boolean isAvailable() {
        return available && context != NULL && queue != NULL && chosenDevice != null;
    }

    public static long context() {
        return context;
    }

    public static long queue() {
        return queue;
    }

    public static OpenCLDevice device() {
        return chosenDevice;
    }

    /**
     * The resolved precision path: {@code true} = fp32 (float) kernels, {@code false}
     * = fp64 (double). Decided once at {@link #init} from {@code desiredFp} + device
     * caps ({@link FpMode#useFp32(boolean)}). Single source of truth for the kernel
     * compile ({@code -DUSE_FP32}) and every parity test's mode/tolerance.
     */
    public static boolean isFp32() {
        return fp32;
    }

    /**
     * True iff the {@code -cl-fp32-correctly-rounded-divide-sqrt} build flag is in effect
     * this session (device advertises the cap AND {@code -Dsuperchunk.gpu.crDivide} is not
     * {@code false}). Read by {@link CLProgram#effectiveOptions(String)} — the single choke
     * point every {@code clBuildProgram} options string passes through.
     */
    public static boolean crDivideApplied() {
        return crDivide;
    }

    public static GpuConfig config() {
        return config;
    }

    /** Releases queue + context. Safe to call multiple times / after a failed init. */
    public static synchronized void shutdown() {
        // Mark shutdown in progress FIRST: from here on registerResourceOwner() closes
        // owners on the spot instead of adding them, so an owner created by a still-live
        // c2me worker after the drain loop below cannot be leaked nor left dangling on
        // the context we are about to release.
        shuttingDown = true;
        // Close any lingering "compiling GPU kernels…" progress window (best-effort, no-op
        // if it was never shown / already auto-closed after the compile phase went quiet).
        try {
            CompileProgressWindow.finishNow();
        } catch (Throwable ignored) {
        }
        // LIVE DF fusion: close the fused interpolator(s) + clear the fusion registry
        // FIRST so no cl_program/cl_mem leaks. close() is idempotent, so the
        // resource-owner loop below closing them again is a harmless no-op.
        try {
            dev.superchunk.gpu.dfc.ChunkGridCache.shutdownFused();
        } catch (Throwable ignored) {
        }
        // Cross-chunk aggregator: stop the shared batcher thread pair, serve/drain its
        // pending futures and close its primary dispatcher. Idempotent no-op when no
        // batcher is installed (the boot self-test shuts down its own instance).
        // MUST run BEFORE BiomeClimateCache.shutdown(): with the pipeline merge
        // (sharedBatcher, 2026-07-07) climate requests ride THIS batcher targeting
        // BiomeClimateCache's aux dispatchers — those dispatchers may only close once
        // the shared drainer+completer are joined (no in-flight batch references them).
        try {
            dev.superchunk.gpu.dfc.GpuChunkBatcher.shutdownShared();
        } catch (Throwable ignored) {
        }
        try {
            dev.superchunk.gpu.dfc.BiomeClimateCache.shutdown();
        } catch (Throwable ignored) {
        }
        // Now that the batcher drainer thread has been joined by shutdownShared(),
        // no late whenComplete put() can land in GpuBatchStore anymore. Clear the
        // batch store + pooled field buffers HERE (split out of shutdownFused, which
        // ran before the drainer join) so those final-flush grids/holders can't leak
        // in the static store.
        try {
            dev.superchunk.gpu.dfc.ChunkGridCache.clearBatchStore();
        } catch (Throwable ignored) {
        }
        // Release every per-thread / per-DF resource owner FIRST (their cl_kernels,
        // cl_command_queues and cl_mem buffers live on this context). Closing them
        // before clReleaseContext avoids both leaks and use-after-free.
        if (!RESOURCE_OWNERS.isEmpty()) {
            LOGGER.info("[SuperChunk-GPU] Releasing {} GPU resource owner(s) on shutdown.", RESOURCE_OWNERS.size());
        }
        // Drain in a loop: with shuttingDown set, new registrations close themselves,
        // but an owner that slipped in before that flag was observed must still be
        // caught here. Re-loop until the registry is empty so nothing registered
        // between drain passes survives to dangle on the released context.
        while (!RESOURCE_OWNERS.isEmpty()) {
            for (AutoCloseable owner : RESOURCE_OWNERS) {
                RESOURCE_OWNERS.remove(owner);
                try {
                    owner.close();
                } catch (Throwable ignored) {
                }
            }
        }
        // Drop the in-memory shared-program map (its programs were just released by
        // their owners above); a fresh init would rebuild/reload from the disk cache.
        try {
            CLProgramCache.clearMemory();
        } catch (Throwable ignored) {
        }
        try {
            if (queue != NULL) {
                clReleaseCommandQueue(queue);
            }
        } catch (Throwable ignored) {
        } finally {
            queue = NULL;
        }
        try {
            if (context != NULL) {
                clReleaseContext(context);
            }
        } catch (Throwable ignored) {
        } finally {
            context = NULL;
        }
        chosenDevice = null;
        available = false;
        // Re-resolved from the next world's chosen device in init(); a different device
        // (multi-GPU / changed indices) may not advertise the cap.
        crDivide = false;
    }
}
