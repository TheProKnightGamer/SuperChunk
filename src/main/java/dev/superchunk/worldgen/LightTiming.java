package dev.superchunk.worldgen;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread CPU time spent lighting new chunks ({@code StarLightInterface.lightChunk}), split into sky and
 * block light, reported at server stop. Thread CPU time, not wall time, so other load on the machine
 * does not show up in it. Enabled with {@code -Dsuperchunk.debug.lightTiming=true}.
 *
 * <p>{@code -Dsuperchunk.debug.lightTimingAB=<bits>} also turns those {@code StarLightEngine.FAST_*} shortcuts
 * off for every other chunk (a checkerboard) and reports the two halves apart: an A/B within one run, under the
 * same load. The shortcuts are exact, so the world is the same either way.
 */
public final class LightTiming {

    public static final boolean ENABLED = Boolean.getBoolean("superchunk.debug.lightTiming");
    public static final int AB_MASK = ENABLED ? Integer.getInteger("superchunk.debug.lightTimingAB", 0) : 0;

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    // [0] = the default shortcuts, [1] = with AB_MASK turned off
    private static final LongAdder[] CHUNKS = {new LongAdder(), new LongAdder()};
    private static final LongAdder[] SKY_NS = {new LongAdder(), new LongAdder()};
    private static final LongAdder[] BLOCK_NS = {new LongAdder(), new LongAdder()};

    private LightTiming() {
    }

    public static long now() {
        return THREADS.getCurrentThreadCpuTime();
    }

    /** Which half a chunk belongs to: 1 (shortcuts in AB_MASK off) on odd squares of the checkerboard. */
    public static int half(int chunkX, int chunkZ) {
        return AB_MASK != 0 ? (chunkX + chunkZ) & 1 : 0;
    }

    public static void add(int half, long skyNs, long blockNs) {
        CHUNKS[half].increment();
        SKY_NS[half].add(skyNs);
        BLOCK_NS[half].add(blockNs);
    }

    public static void report() {
        if (!ENABLED) {
            return;
        }
        for (int half = 0; half < (AB_MASK != 0 ? 2 : 1); half++) {
            long n = CHUNKS[half].sum(), sky = SKY_NS[half].sum(), block = BLOCK_NS[half].sum();
            org.slf4j.LoggerFactory.getLogger("SuperChunk-Lighting").info(
                    "[light-timing{}] chunks={} sky={} us/chunk block={} us/chunk total={} us/chunk",
                    AB_MASK == 0 ? "" : half == 0 ? " A" : " B (without " + AB_MASK + ")", n,
                    n == 0 ? "n/a" : String.format("%.1f", sky / 1e3 / n),
                    n == 0 ? "n/a" : String.format("%.1f", block / 1e3 / n),
                    n == 0 ? "n/a" : String.format("%.1f", (sky + block) / 1e3 / n));
        }
    }
}
