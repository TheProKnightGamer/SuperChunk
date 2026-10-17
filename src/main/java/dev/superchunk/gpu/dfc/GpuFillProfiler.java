package dev.superchunk.gpu.dfc;

import dev.superchunk.gpu.OpenCLBackend;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase-A instrumentation for the GPU density-fill path.
 *
 * <p>Lock-free accumulators (one set of {@link AtomicLong}s, summed from many
 * c2me-worker threads) capturing, over one pregen:
 * <ul>
 *   <li>total GPU dispatches and the batch-size distribution (work-items per
 *       dispatch: total / min / max);</li>
 *   <li>a host wall-clock split of each fill into
 *       <b>upload</b> (host&rarr;device coord write), <b>kernel+sync</b>
 *       (NDRange enqueue + the blocking wait on completion) and <b>readback</b>
 *       (device&rarr;host result copy);</li>
 *   <li>the GPU-side kernel execution time from OpenCL event profiling
 *       ({@code CL_PROFILING_COMMAND_START}&rarr;{@code END}) and the queue
 *       latency ({@code QUEUED}&rarr;{@code START}), so we can separate genuine
 *       compute from launch/scheduling overhead.</li>
 * </ul>
 *
 * <p><b>Zero overhead when off.</b> Everything here is only touched when
 * {@code config.profile()} is true (resolved once into {@link #ENABLED}); the hot
 * path checks that flag before timing, so the production default pays nothing.
 *
 * <p>The wall-clock split is the load-bearing diagnostic: if {@code upload +
 * readback + sync} dominates {@code kernel}, the path is overhead-bound (the
 * Phase-A hypothesis) and dispatch-coalescing / async-overlap / in-kernel coords
 * are the fixes.
 */
public final class GpuFillProfiler {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    /** Resolved once from config; when false NOTHING in this class is exercised. */
    public static volatile boolean ENABLED = false;

    // Dispatch + batch-size stats.
    private static final AtomicLong dispatches = new AtomicLong();
    private static final AtomicLong workItems = new AtomicLong();
    private static final AtomicLong minBatch = new AtomicLong(Long.MAX_VALUE);
    private static final AtomicLong maxBatch = new AtomicLong(0);

    // Host wall-clock split (nanos).
    private static final AtomicLong uploadNanos = new AtomicLong();
    private static final AtomicLong kernelSyncNanos = new AtomicLong(); // NDRange enqueue + blocking wait
    private static final AtomicLong readbackNanos = new AtomicLong();
    private static final AtomicLong totalFillNanos = new AtomicLong();   // whole fill() body

    // GPU event-profiling (nanos), only when the queue has CL_QUEUE_PROFILING_ENABLE.
    private static final AtomicLong gpuComputeNanos = new AtomicLong(); // START -> END
    private static final AtomicLong gpuQueueNanos = new AtomicLong();   // QUEUED -> START
    private static final AtomicLong gpuEventSamples = new AtomicLong();

    // How many fills used in-kernel lattice coords vs uploaded arrays (Phase B).
    private static final AtomicLong latticeFills = new AtomicLong();
    private static final AtomicLong arrayFills = new AtomicLong();

    private GpuFillProfiler() {
    }

    /** Resolve the enabled flag from config (called once at backend init). */
    public static void configure(boolean enabled) {
        ENABLED = enabled;
        if (enabled) {
            LOGGER.info("[SuperChunk-GPU] density-fill PROFILING enabled (Phase A) — "
                    + "OpenCL event timing + host upload/compute/readback/sync split will be reported at server stop.");
        }
    }

    /** Log a running profile breakdown once every this many dispatches. */
    private static final long LOG_EVERY = 20000L;

    /** Records one dispatch of {@code n} work-items with its host-side timing split. */
    public static void recordFill(int n, long uploadNs, long kernelSyncNs, long readbackNs,
                                  long totalNs, boolean lattice) {
        long d = dispatches.incrementAndGet();
        recordFillRest(n, uploadNs, kernelSyncNs, readbackNs, totalNs, lattice);
        if (d % LOG_EVERY == 0L) {
            logBreakdown("running");
        }
    }

    private static void recordFillRest(int n, long uploadNs, long kernelSyncNs, long readbackNs,
                                       long totalNs, boolean lattice) {
        workItems.addAndGet(n);
        updateMin(minBatch, n);
        updateMax(maxBatch, n);
        uploadNanos.addAndGet(uploadNs);
        kernelSyncNanos.addAndGet(kernelSyncNs);
        readbackNanos.addAndGet(readbackNs);
        totalFillNanos.addAndGet(totalNs);
        if (lattice) {
            latticeFills.incrementAndGet();
        } else {
            arrayFills.incrementAndGet();
        }
    }

    /** Records GPU event-profiling deltas for one kernel (nanoseconds). */
    public static void recordGpuEvent(long queueNs, long computeNs) {
        if (computeNs > 0) {
            gpuComputeNanos.addAndGet(computeNs);
        }
        if (queueNs > 0) {
            gpuQueueNanos.addAndGet(queueNs);
        }
        gpuEventSamples.incrementAndGet();
    }

    private static double pct(double part, double tot) {
        return tot <= 0 ? 0.0 : 100.0 * part / tot;
    }

    private static void updateMin(AtomicLong tgt, long v) {
        long cur;
        while (v < (cur = tgt.get())) {
            if (tgt.compareAndSet(cur, v)) return;
        }
    }

    private static void updateMax(AtomicLong tgt, long v) {
        long cur;
        while (v > (cur = tgt.get())) {
            if (tgt.compareAndSet(cur, v)) return;
        }
    }

    /** Logs the full Phase-A / Phase-C breakdown. Safe to call any time. */
    public static void logBreakdown(String when) {
        long d = dispatches.get();
        if (d == 0L) {
            LOGGER.info("[SuperChunk-GPU] profiler ({}): no GPU dispatches recorded.", when);
            return;
        }
        long wi = workItems.get();
        long mn = minBatch.get();
        long mx = maxBatch.get();
        double avg = (double) wi / d;

        double upMs = uploadNanos.get() / 1.0e6;
        double ksMs = kernelSyncNanos.get() / 1.0e6;
        double rbMs = readbackNanos.get() / 1.0e6;
        double totMs = totalFillNanos.get() / 1.0e6;
        // "other" = thread-local capacity grow, arg-binding, slicing, bookkeeping.
        double otherMs = Math.max(0.0, totMs - upMs - ksMs - rbMs);

        LOGGER.info("[SuperChunk-GPU] ===== density-fill PROFILE ({}) =====", when);
        LOGGER.info("[SuperChunk-GPU]  dispatches={}  work-items: total={} avg={} min={} max={}",
                d, wi, String.format("%.1f", avg), mn == Long.MAX_VALUE ? 0 : mn, mx);
        LOGGER.info("[SuperChunk-GPU]  HOST wall-time split (sum over all worker threads):");
        LOGGER.info("[SuperChunk-GPU]    upload (H->D coords) : {} ms ({}%)",
                String.format("%.1f", upMs), String.format("%.1f", pct(upMs, totMs)));
        LOGGER.info("[SuperChunk-GPU]    kernel+sync (NDRange+clFinish/wait) : {} ms ({}%)",
                String.format("%.1f", ksMs), String.format("%.1f", pct(ksMs, totMs)));
        LOGGER.info("[SuperChunk-GPU]    readback (D->H result) : {} ms ({}%)",
                String.format("%.1f", rbMs), String.format("%.1f", pct(rbMs, totMs)));
        LOGGER.info("[SuperChunk-GPU]    other (bind/grow/copy) : {} ms ({}%)",
                String.format("%.1f", otherMs), String.format("%.1f", pct(otherMs, totMs)));
        LOGGER.info("[SuperChunk-GPU]    TOTAL GPU-fill wall   : {} ms  ({} per dispatch)",
                String.format("%.1f", totMs), String.format("%.4f ms", totMs / d));

        long ev = gpuEventSamples.get();
        if (ev > 0) {
            double gcMs = gpuComputeNanos.get() / 1.0e6;
            double gqMs = gpuQueueNanos.get() / 1.0e6;
            LOGGER.info("[SuperChunk-GPU]  GPU event profiling ({} samples):", ev);
            LOGGER.info("[SuperChunk-GPU]    actual kernel compute (START->END) : {} ms ({}% of total fill wall)",
                    String.format("%.1f", gcMs), String.format("%.1f", pct(gcMs, totMs)));
            LOGGER.info("[SuperChunk-GPU]    queue latency (QUEUED->START)       : {} ms ({}% of total fill wall)",
                    String.format("%.1f", gqMs), String.format("%.1f", pct(gqMs, totMs)));
            LOGGER.info("[SuperChunk-GPU]    => per-dispatch OVERHEAD (total - compute) : {} ms ({}%)",
                    String.format("%.1f", totMs - gcMs), String.format("%.1f", pct(totMs - gcMs, totMs)));
        }
        long lat = latticeFills.get();
        long arr = arrayFills.get();
        if (lat + arr > 0) {
            LOGGER.info("[SuperChunk-GPU]  coord source: in-kernel lattice={} ({}%), uploaded arrays={}",
                    lat, String.format("%.1f", 100.0 * lat / (lat + arr)), arr);
        }
        LOGGER.info("[SuperChunk-GPU] ===========================================");
    }
}
