package dev.superchunk.gpu;

import org.slf4j.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * One-shot diagnostic: does this OpenCL driver compile kernels FASTER in parallel than
 * sequentially? The live path issues {@code clBuildProgram} for the router's ~100 density
 * functions one-after-another on a single thread (see {@code MixinNoiseConfig}); on a cold
 * program cache that is the dominant first-boot cost. Parallelizing it only pays off if the
 * driver's compiler actually runs concurrently — NVIDIA historically serialized builds on an
 * internal lock. This measures it directly so we don't guess.
 *
 * <p>Enabled with {@code -Dsuperchunk.gpu.compileBench=<N>} (number of kernels; e.g. 32). Builds
 * N realistic kernels (the real noise.cl + dfc_support.cl headers — the bulk of the compile cost —
 * plus a unique trailing kernel) sequentially, then N more concurrently across a thread pool, and
 * logs both wall-times + the speedup. A per-run nonce makes every kernel unique so the driver's own
 * compute cache can't mask the JIT. Uses {@link CLProgram#build} directly (bypasses SuperChunk's
 * program cache). Never throws.
 */
public final class GpuCompileBench {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    private GpuCompileBench() {
    }

    public static void run() {
        int n = Integer.getInteger("superchunk.gpu.compileBench", 0);
        if (n <= 0) {
            return;
        }
        if (!OpenCLBackend.isAvailable()) {
            LOGGER.info("[SuperChunk-GPU] compile-bench requested but backend unavailable.");
            return;
        }
        try {
            String noise = load("/superchunk/kernels/noise.cl");
            String support = load("/superchunk/kernels/dfc_support.cl");
            if (noise == null || support == null) {
                LOGGER.warn("[SuperChunk-GPU] compile-bench: kernel headers missing.");
                return;
            }
            String headers = noise + "\n" + support + "\n";
            String opts = "";   // fp64 path (the vanilla-accurate default)
            long nonce = System.nanoTime();   // makes every run cold (defeats the driver compute cache)
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);

            LOGGER.info("[SuperChunk-GPU] ===== compile-concurrency bench: n={} kernels, {} threads =====", n, threads);

            // ---- warm-up: pay the one-time first-touch costs (noise.cl/dfc_support.cl math-builtin
            // JIT, compiler-library load, CPU turbo ramp) ONCE before t0, so all three timed passes
            // start from an equally warm driver/cache state instead of dumping it all on sequential.
            CLProgram warm = CLProgram.build(headers + "__kernel void scbwarm(__global int* o){ if (get_global_id(0) == 0) o[0] = 0; }", opts);
            if (warm != null) {
                warm.close();
            }

            // ---- sequential ----
            String[] seqSrc = sources(headers, "scbseq", nonce, n);
            long t0 = System.nanoTime();
            int seqOk = 0;
            for (int i = 0; i < n; i++) {
                CLProgram p = CLProgram.build(seqSrc[i], opts);
                if (p != null) {
                    p.close();
                    seqOk++;
                }
            }
            long seqMs = (System.nanoTime() - t0) / 1_000_000L;
            if (seqOk == 0) {
                LOGGER.warn("[SuperChunk-GPU] compile-bench: 0/{} kernels compiled for opts='{}' on this device (likely no cl_khr_fp64 — bench hardcodes the fp64 path); skipping RESULT/VERDICT.", n, opts);
                return;
            }

            // ---- parallel (different salt -> no cross-pass cache hit) ----
            String[] parSrc = sources(headers, "scbpar", nonce, n);
            ExecutorService ex = Executors.newFixedThreadPool(threads);
            long parMs;
            try {
                long t1 = System.nanoTime();
                List<Future<?>> futures = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    final String s = parSrc[i];
                    futures.add(ex.submit(() -> {
                        CLProgram p = CLProgram.build(s, opts);
                        if (p != null) {
                            p.close();
                        }
                    }));
                }
                for (Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Throwable ignored) {
                    }
                }
                parMs = (System.nanoTime() - t1) / 1_000_000L;
            } finally {
                ex.shutdownNow();
            }

            // ---- MERGED: ONE program containing all N kernels -> the headers (noise.cl +
            // dfc_support.cl, the bulk of per-kernel cost) compile ONCE instead of N times.
            // This is the upper bound on what "merge the kernels" buys for cold-compile time.
            StringBuilder merged = new StringBuilder(headers.length() + n * 96);
            merged.append(headers);
            for (int i = 0; i < n; i++) {
                merged.append("__kernel void scbmrg_").append(nonce).append('_').append(i)
                        .append("(__global int* o){ if (get_global_id(0) == 0) o[0] = ")
                        .append(i ^ (int) nonce).append("; }\n");
            }
            long t2 = System.nanoTime();
            CLProgram mp = CLProgram.build(merged.toString(), opts);
            if (mp != null) {
                mp.close();
            }
            long mergedMs = (System.nanoTime() - t2) / 1_000_000L;

            double speedup = parMs > 0 ? seqMs / (double) parMs : 0.0;
            double mergeGain = mergedMs > 0 ? seqMs / (double) mergedMs : 0.0;
            LOGGER.info("[SuperChunk-GPU] compile-bench RESULT: sequential(N programs)={} ms ({} ms/kernel), parallel({} thr)={} ms ({}x), MERGED(1 program, N kernels)={} ms ({}x vs sequential)",
                    seqMs, n > 0 ? seqMs / n : 0, threads, parMs, String.format("%.2f", speedup),
                    mergedMs, String.format("%.2f", mergeGain));
            LOGGER.info("[SuperChunk-GPU] compile-bench VERDICT: parallelizing -> {}; merging into one program -> {} (headers compile once vs {}x).",
                    speedup >= 1.5 ? "WORTH IT" : "no real gain (driver serializes clBuildProgram)",
                    mergeGain >= 1.5 ? "BIG WIN (cold-compile dominated by shared-header recompilation)" : "marginal (bodies dominate, not headers)",
                    n);
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] compile-bench threw.", t);
        }
    }

    private static String[] sources(String headers, String tag, long nonce, int n) {
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            // unique kernel name + unique constant -> a distinct translation unit each time, so the
            // driver genuinely JITs it (no compute-cache hit) while the heavy headers dominate cost.
            out[i] = headers + "__kernel void " + tag + "_" + nonce + "_" + i
                    + "(__global int* o){ if (get_global_id(0) == 0) o[0] = " + (i ^ (int) nonce) + "; }\n";
        }
        return out;
    }

    private static String load(String path) {
        try (InputStream in = GpuCompileBench.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
