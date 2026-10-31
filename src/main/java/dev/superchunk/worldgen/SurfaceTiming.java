package dev.superchunk.worldgen;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread CPU time spent in {@code SurfaceSystem.buildSurface} per chunk, reported at server stop. Enabled with
 * {@code -Dsuperchunk.debug.surfaceTiming=true}. {@code -Dsuperchunk.debug.surfaceTimingAB=<bits>} also turns those
 * surface shortcuts ({@link #UNIFORM_CELLS}, ...) off for every other chunk (a checkerboard) and reports the two halves
 * apart. The shortcuts are exact, so the world is the same either way.
 */
public final class SurfaceTiming {

    /** {@code MixinBiomeManagerQuartCache}'s uniform-cell biome shortcut. */
    public static final int UNIFORM_CELLS = 1;
    /** {@link DeepSurfaceRules}. */
    public static final int DEEP_RULES = 2;
    /** {@code MixinSurfaceColumnMemo}'s section-at-a-time reads. */
    public static final int COLUMN_SECTIONS = 4;
    /** {@code MixinSurfaceSystemFast}'s copy of the surface pass. */
    public static final int FAST_SURFACE = 8;
    /** {@link DeepSurfacePass}. */
    public static final int DEEP_PASS = 16;
    /** {@link CeilingDepth}. */
    public static final int CEILING_DEPTH = 32;

    public static final boolean ENABLED = Boolean.getBoolean("superchunk.debug.surfaceTiming");
    public static final int AB_MASK = ENABLED ? Integer.getInteger("superchunk.debug.surfaceTimingAB", 0) : 0;

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    // the shortcuts turned off on this thread while it builds a chunk of the second half
    private static final ThreadLocal<int[]> OFF = ThreadLocal.withInitial(() -> new int[1]);
    // [0] = the default shortcuts, [1] = with AB_MASK turned off
    private static final LongAdder[] CHUNKS = {new LongAdder(), new LongAdder()};
    private static final LongAdder[] NS = {new LongAdder(), new LongAdder()};

    private SurfaceTiming() {
    }

    /** The shortcuts the A/B timer has turned off for the chunk this thread is building, or 0. */
    public static int off() {
        return AB_MASK == 0 ? 0 : OFF.get()[0];
    }

    /** Which half a chunk belongs to: 1 (shortcuts in AB_MASK off) on odd squares of the checkerboard. */
    public static int half(int chunkX, int chunkZ) {
        return AB_MASK != 0 ? (chunkX + chunkZ) & 1 : 0;
    }

    /** Runs one chunk's surface, timed, with the half's shortcuts turned off. */
    public static void time(int half, Runnable build) {
        int[] off = AB_MASK != 0 ? OFF.get() : null;
        int before = off != null ? off[0] : 0;
        if (off != null) {
            off[0] = half == 1 ? AB_MASK : 0;
        }
        long start = THREADS.getCurrentThreadCpuTime();
        try {
            build.run();
        } finally {
            NS[half].add(THREADS.getCurrentThreadCpuTime() - start);
            CHUNKS[half].increment();
            if (off != null) {
                off[0] = before;
            }
        }
    }

    public static void report() {
        if (!ENABLED) {
            return;
        }
        for (int half = 0; half < (AB_MASK != 0 ? 2 : 1); half++) {
            long n = CHUNKS[half].sum(), ns = NS[half].sum();
            org.slf4j.LoggerFactory.getLogger("SuperChunk-Surface").info(
                    "[surface-timing{}] chunks={} {} us/chunk",
                    AB_MASK == 0 ? "" : half == 0 ? " A" : " B (without " + AB_MASK + ")", n,
                    n == 0 ? "n/a" : String.format("%.1f", ns / 1e3 / n));
        }
    }
}
