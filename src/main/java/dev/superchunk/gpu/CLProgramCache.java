package dev.superchunk.gpu;

import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-layer cache for compiled OpenCL programs (answers "why don't we compile the
 * kernel once and cache it?").
 *
 * <p>During worldgen ~100 distinct density-function kernels are generated; many
 * chunks reuse byte-identical kernel source, and {@code clBuildProgram} is a
 * driver-side JIT that costs real time at world load. This cache eliminates both
 * the redundant builds and (across boots) the JIT itself:
 *
 * <ol>
 *   <li><b>In-memory dedup.</b> Keyed by {@code (effective options, source)} — the
 *       options half is normalized via {@link CLProgram#effectiveOptions(String)} so
 *       session-level build flags (e.g. the device-gated
 *       {@code -cl-fp32-correctly-rounded-divide-sqrt}) participate in the key: identical
 *       generated kernels are built once and the {@link CLProgram} is shared
 *       (refcounted via retain/close), so N density functions with the same
 *       structure share one {@code cl_program}.</li>
 *   <li><b>On-disk binary cache.</b> The first build's compiled binary
 *       (clGetProgramInfo CL_PROGRAM_BINARIES) is written under
 *       {@code config/superchunk-gpu-cache/}, keyed by a SHA-256 of
 *       deviceName + driverVersion + options + source. On a later boot the program
 *       is created with clCreateProgramWithBinary - no recompile. The device and
 *       driver are folded into the key so a driver/GPU change cleanly misses and
 *       rebuilds.</li>
 * </ol>
 *
 * <p>Thread-safe: many c2me-worker threads compile density functions concurrently.
 * The in-memory map is a {@link ConcurrentHashMap}; the first-time build for a
 * given key is guarded so two threads do not both JIT the same kernel.
 */
public final class CLProgramCache {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    /** key -> shared program. */
    private static final Map<String, CLProgram> MEM = new ConcurrentHashMap<>();
    /** per-key build lock so only one thread JITs a given kernel. */
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    private static volatile Path cacheDir;       // resolved lazily
    private static volatile String deviceTag;    // deviceName|openclVersion|driverVersion
    private static volatile boolean diskEnabled = true;

    /** Prune binaries untouched for longer than this (orphaned driver/source generations). */
    private static final long PRUNE_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000; // 30 days
    /** Hard cap on retained binaries; oldest beyond this are dropped. */
    private static final int PRUNE_MAX_FILES = 512;

    // counters (diagnostics) — LongAdder so cache lookups don't serialize on a global
    // synchronized(CLProgramCache.class); these are pure stats and racy adds are fine.
    private static final java.util.concurrent.atomic.LongAdder memHits = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder diskHits = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder builds = new java.util.concurrent.atomic.LongAdder();

    private CLProgramCache() {
    }

    /**
     * Returns a built program for {@code (source, options)}, reusing an in-memory
     * shared program if one exists, else loading from the on-disk binary cache,
     * else compiling from source and persisting the binary. The returned program
     * is refcounted: the caller MUST {@link CLProgram#close()} it exactly once.
     * Returns {@code null} on build failure (caller falls back to CPU).
     */
    public static CLProgram getOrBuild(String source, String options) {
        ensureDeviceTag();
        // Normalize to the EFFECTIVE build options (appends the device-gated
        // -cl-fp32-correctly-rounded-divide-sqrt flag when in effect) BEFORE hashing, so
        // the flag is folded into both the in-memory and on-disk keys: flipping
        // -Dsuperchunk.gpu.crDivide (or moving to a device without the cap) cleanly
        // misses every stale binary and recompiles instead of silently loading a binary
        // built under different fp32 divide/sqrt rounding. Idempotent — the same
        // normalization inside CLProgram.build/buildFromBinary is then a no-op.
        String opt = CLProgram.effectiveOptions(options);
        String key = sha256(opt + "\n" + source);

        // 1) in-memory dedup. retain() returns false if the program was concurrently
        // released (clearMemory at shutdown) AFTER our MEM.get() — treat that as a miss
        // and fall through to rebuild rather than hand back a freed cl_program.
        CLProgram existing = MEM.get(key);
        if (existing != null && existing.retain()) {
            memHits.increment();
            return existing;
        }

        // Guard the build so concurrent threads don't both JIT this key.
        Object lock = LOCKS.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            existing = MEM.get(key);
            if (existing != null && existing.retain()) {
                memHits.increment();
                return existing;
            }

            CLProgram program = null;

            // 2) on-disk binary cache.
            byte[] cached = readDisk(key);
            if (cached != null) {
                program = CLProgram.buildFromBinary(cached, opt);
                if (program != null) {
                    diskHits.increment();
                } else {
                    LOGGER.debug("[SuperChunk-GPU] cached binary rejected (driver change?) - recompiling.");
                }
            }

            // 3) compile from source + persist binary.
            if (program == null) {
                program = CLProgram.build(source, opt);
                if (program == null) {
                    return null; // build failed -> CPU fallback
                }
                builds.increment();
                byte[] bin = program.getBinary();
                if (bin != null) {
                    writeDisk(key, bin);
                }
            }

            // The cache OWNS one reference (conceptually held by MEM) so a cached
            // program is never freed while still cached, even if every external owner
            // close()s it. The returned program ALSO needs the caller's reference.
            // BUGFIX: previously markShared(1) was the caller's ONLY ref, so a caller
            // closing it to 0 ran clReleaseProgram while MEM still pointed at the freed
            // cl_program -> use-after-free / SIGSEGV in clCreateKernel on the next
            // getOrBuild of the same source. Now: refcount 2 (cache + caller).
            program.markShared(1);   // cache's own reference (released in clearMemory)
            MEM.put(key, program);
            // Honor retain()'s result, exactly like both fast paths above: if a racing
            // clearMemory() (shutdown) snapshotted+close()d this program between the
            // MEM.put and here, refcount has already hit 0 and the cl_program is freed —
            // retain() returns false. Report a build miss (caller falls back to CPU)
            // rather than hand back a freed cl_program -> UAF in clCreateKernel. In
            // normal operation retain() always succeeds (refcount 1 -> 2).
            if (!program.retain()) {  // caller's reference -> refcount 2
                MEM.remove(key, program); // no-op if clearMemory already cleared it
                return null;
            }
            return program;
        }
    }

    /**
     * Releases the cache's own reference to every shared program, then drops the
     * maps. Called at backend shutdown. Refcounting makes this safe alongside owner
     * teardown in any order: each cl_program is released exactly once, when BOTH the
     * cache reference and the last external owner have closed.
     */
    public static void clearMemory() {
        // Remove every entry from the maps BEFORE releasing the programs. close() may
        // run clReleaseProgram (dropping the cl_program to refcount 0); if the entry
        // were still reachable in MEM, a concurrent worldgen fast-path lookup could
        // MEM.get() and retain() the just-freed program and then kernel() it — a
        // use-after-free. Clearing first means new lookups miss and rebuild instead.
        java.util.List<CLProgram> doomed = new java.util.ArrayList<>(MEM.values());
        MEM.clear();
        LOCKS.clear();
        for (CLProgram p : doomed) {
            try {
                p.close();   // drop the cache's reference (frees iff it was the last)
            } catch (Throwable ignored) {
            }
        }
        // Reset the lazily-resolved cache identity. The backend is torn down + re-init'd
        // per world (ServerAboutToStart); if a later world selects a DIFFERENT OpenCL
        // device (multi-GPU, or a changed platformIndex/deviceIndex), keeping the FIRST
        // device's deviceTag/cacheDir would compute every disk key under the wrong device
        // — the cache would ping-pong (rebuild every boot) and write binaries under the
        // wrong device's key. Null them so the next init() re-resolves against the device
        // actually chosen for that world. (No-op for the common single-device session.)
        deviceTag = null;
        cacheDir = null;
        diskEnabled = true;
    }

    /** Logs cache effectiveness. */
    public static void logStats(String when) {
        LOGGER.info("[SuperChunk-GPU] program cache ({}): distinct kernels built={}, "
                        + "in-memory dedup hits={}, disk-binary hits={} (unique programs cached={})",
                when, builds.sum(), memHits.sum(), diskHits.sum(), MEM.size());
    }

    // ---- disk helpers ----

    private static void ensureDeviceTag() {
        if (deviceTag == null) {
            synchronized (CLProgramCache.class) {
                if (deviceTag == null) {
                    OpenCLDevice dev = OpenCLBackend.device();
                    if (dev != null) {
                        // CL_DEVICE_VERSION (openclVersion) is the platform feature level and
                        // typically stays constant across GPU driver updates, but the compiled
                        // program binary is driver-specific. Fold CL_DRIVER_VERSION into the tag
                        // too so a driver change cleanly misses and rebuilds — loading a stale
                        // binary could silently run old driver-JITed fp64 code whose rounding
                        // differs, breaking the bit-identical-with-vanilla parity guarantee.
                        deviceTag = dev.name() + "|" + dev.openclVersion()
                                + "|" + queryDriverVersion(dev.device());
                    } else {
                        deviceTag = "unknown";
                    }
                }
            }
        }
    }

    /** Queries {@code CL_DRIVER_VERSION} for {@code device}; "" if unavailable. */
    private static String queryDriverVersion(long device) {
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer size = stack.mallocPointer(1);
            if (org.lwjgl.opencl.CL10.clGetDeviceInfo(device, org.lwjgl.opencl.CL10.CL_DRIVER_VERSION,
                    (java.nio.ByteBuffer) null, size) != org.lwjgl.opencl.CL10.CL_SUCCESS) {
                return "";
            }
            int n = (int) size.get(0);
            if (n <= 0) {
                return "";
            }
            java.nio.ByteBuffer buf = stack.malloc(n);
            if (org.lwjgl.opencl.CL10.clGetDeviceInfo(device, org.lwjgl.opencl.CL10.CL_DRIVER_VERSION,
                    buf, null) != org.lwjgl.opencl.CL10.CL_SUCCESS) {
                return "";
            }
            return org.lwjgl.system.MemoryUtil.memUTF8(buf, n - 1); // strip trailing NUL
        } catch (Throwable t) {
            return "";
        }
    }

    private static Path dir() {
        Path d = cacheDir;
        if (d == null) {
            synchronized (CLProgramCache.class) {
                if (cacheDir == null) {
                    try {
                        Path base = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get()
                                .resolve("superchunk-gpu-cache");
                        Files.createDirectories(base);
                        prune(base);
                        cacheDir = base;
                    } catch (Throwable t) {
                        LOGGER.debug("[SuperChunk-GPU] could not init program cache dir - disk cache off.", t);
                        diskEnabled = false;
                        cacheDir = baseFallback();
                    }
                }
                d = cacheDir;
            }
        }
        return d;
    }

    private static Path baseFallback() {
        try {
            return Files.createTempDirectory("sc-gpu-cache");
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Best-effort prune of stale cache binaries, run once when the cache dir is
     * resolved. Every driver/source generation produces fresh SHA-256 keys, so old
     * generations leave {@code *.clbin} files that are never read again; interrupted
     * writes can also leave {@code *.clbin.tmp}. Delete any binary not touched in
     * {@link #PRUNE_MAX_AGE_MILLIS} (plus all leftover {@code .tmp} files), and if the
     * dir still holds more than {@link #PRUNE_MAX_FILES} binaries, drop the oldest
     * beyond the cap. Purely disk hygiene: deleting a still-valid binary only forces a
     * recompile, it never affects worldgen output. Any failure is swallowed.
     */
    private static void prune(Path base) {
        try {
            long now = System.currentTimeMillis();
            java.util.Map<Path, Long> live = new java.util.HashMap<>();
            try (java.nio.file.DirectoryStream<Path> ds =
                         Files.newDirectoryStream(base, "*.clbin*")) {
                for (Path p : ds) {
                    try {
                        String name = p.getFileName().toString();
                        long mtime = Files.getLastModifiedTime(p).toMillis();
                        if (name.endsWith(".clbin.tmp") || now - mtime > PRUNE_MAX_AGE_MILLIS) {
                            Files.deleteIfExists(p);
                        } else {
                            live.put(p, mtime);
                        }
                    } catch (Throwable ignored) {
                        // skip files we can't stat/delete
                    }
                }
            }
            if (live.size() > PRUNE_MAX_FILES) {
                java.util.List<Path> oldest = new java.util.ArrayList<>(live.keySet());
                oldest.sort(java.util.Comparator.comparingLong(live::get)); // oldest first
                int drop = live.size() - PRUNE_MAX_FILES;
                for (int i = 0; i < drop; i++) {
                    try {
                        Files.deleteIfExists(oldest.get(i));
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[SuperChunk-GPU] program cache prune failed", t);
        }
    }

    private static byte[] readDisk(String key) {
        if (!diskEnabled) {
            return null;
        }
        try {
            Path d = dir();
            if (d == null) {
                return null;
            }
            Path f = d.resolve(key + ".clbin");
            if (Files.isReadable(f)) {
                byte[] bytes = Files.readAllBytes(f);
                // Touch-on-read LRU: prune() evicts binaries untouched for
                // PRUNE_MAX_AGE_MILLIS (and the oldest beyond PRUNE_MAX_FILES) by mtime. A
                // still-live binary is written once and only ever read afterwards, so without
                // this its mtime never advances and prune would eventually evict it — forcing
                // a needless recompile. Refresh the mtime so a disk hit counts as a touch.
                // Best-effort; a failure only costs a later recompile, never worldgen output.
                try {
                    Files.setLastModifiedTime(f,
                            java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
                } catch (Throwable ignored) {
                }
                return bytes;
            }
        } catch (Throwable t) {
            LOGGER.debug("[SuperChunk-GPU] program cache read failed", t);
        }
        return null;
    }

    private static void writeDisk(String key, byte[] binary) {
        if (!diskEnabled) {
            return;
        }
        try {
            Path d = dir();
            if (d == null) {
                return;
            }
            Path tmp = d.resolve(key + ".clbin.tmp");
            Files.write(tmp, binary);
            Files.move(tmp, d.resolve(key + ".clbin"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            LOGGER.debug("[SuperChunk-GPU] program cache write failed", t);
        }
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            // fold the device+driver into the key so binaries can't cross devices.
            String tag = deviceTag != null ? deviceTag : "";
            md.update(tag.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] h = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
