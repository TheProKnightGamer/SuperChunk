package dev.superchunk.gpu.dfc;

import dev.superchunk.gpu.OpenCLBackend;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * STAGE-1 timing accumulator for the on-device full-field interpolation
 * microbenchmark (flag {@code -Dsuperchunk.gpu.fullFieldBench}, default OFF).
 *
 * <p>When the bench is on, {@link GpuFusedInterpolator} — right after a chunk's
 * fused cell-corner grids are computed (and left device-resident in the
 * per-thread corner buffer) — additionally dispatches {@code df_full_field_interp}
 * to interpolate the FULL {@code 16x16x384 = 98304}-block field for every density
 * grid on the GPU, reads it back, and DISCARDS it (terrain is untouched). It calls
 * {@link #record} with the interp-kernel GPU time (from the profiling event), the
 * blocking readback wall-time, and the bytes read back.
 *
 * <p>Counters are add-only {@link LongAdder}s updated from many c2me-worker
 * threads (de-contended). A {@code [fullfield-bench]} summary is logged every
 * {@link #LOG_EVERY} chunks measured and once more at server-stop via
 * {@link #logSummary}. When the bench flag is off, nothing here ever moves.
 */
public final class FullFieldBench {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    /** Log a running summary once every this many chunks measured. */
    private static final long LOG_EVERY = 256L;

    /** Output points interpolated per density grid for the canonical full chunk. */
    private static final int FULL_POINTS_PER_GRID = 16 * 16 * 384;   // 98304

    private static final LongAdder chunks = new LongAdder();
    private static final LongAdder kernelNanos = new LongAdder();
    private static final LongAdder readbackNanos = new LongAdder();
    private static final LongAdder bytesRead = new LongAdder();
    // Monotonic counter for the exact every-LOG_EVERY trigger (LongAdder has no
    // atomic increment-and-get).
    private static final AtomicLong totalForLog = new AtomicLong();
    private static volatile boolean firstLogged = false;
    /** Last-seen grid count per chunk (M roots) — for the report (effectively constant). */
    private static volatile int lastRoots = 0;

    private FullFieldBench() {
    }

    /**
     * Records ONE chunk's full-field bench: {@code kernelNs} = the
     * {@code df_full_field_interp} GPU compute time (CL profiling START->END, 0 if
     * unavailable), {@code readbackNs} = the blocking device->host read wall-time,
     * {@code bytes} = bytes read back ({@code M*98304*realBytes}), {@code roots} = the
     * grid count M. The interpolated field itself is discarded by the caller.
     */
    public static void record(long kernelNs, long readbackNs, long bytes, int roots) {
        chunks.increment();
        kernelNanos.add(kernelNs);
        readbackNanos.add(readbackNs);
        bytesRead.add(bytes);
        lastRoots = roots;
        if (!firstLogged) {
            firstLogged = true;
            LOGGER.info("[SuperChunk-GPU] [fullfield-bench] FIRST full-field GPU interpolation timed on thread '{}' "
                    + "({} grids x {} pts). Result DISCARDED — terrain unchanged.",
                    Thread.currentThread().getName(), roots, FULL_POINTS_PER_GRID);
        }
        if (totalForLog.incrementAndGet() % LOG_EVERY == 0L) {
            logSummary("running");
        }
    }

    /** Logs a one-line {@code [fullfield-bench]} summary. Safe to call any time. */
    public static void logSummary(String when) {
        long c = chunks.sum();
        if (c == 0L) {
            return;
        }
        long kNs = kernelNanos.sum();
        long rNs = readbackNanos.sum();
        long bytes = bytesRead.sum();
        double kMeanMs = kNs / 1_000_000.0 / c;
        double rMeanMs = rNs / 1_000_000.0 / c;
        double mbPerChunk = bytes / (1024.0 * 1024.0) / c;
        LOGGER.info("[SuperChunk-GPU] [fullfield-bench] ({}): chunks={} ({} grids/chunk x {} pts), "
                        + "interp-kernel mean={} ms/chunk, readback mean={} ms/chunk, readback={} MB/chunk; "
                        + "totals: kernel={} ms, readback={} ms, read={} MB.",
                when, c, lastRoots, FULL_POINTS_PER_GRID,
                String.format("%.3f", kMeanMs), String.format("%.3f", rMeanMs), String.format("%.2f", mbPerChunk),
                kNs / 1_000_000L, rNs / 1_000_000L, String.format("%.1f", bytes / (1024.0 * 1024.0)));
    }
}
