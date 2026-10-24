package dev.superchunk.worldgen;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread CPU time spent in {@code OreFeature.place} (its {@code doPlace} included), reported at server stop.
 * Enabled with {@code -Dsuperchunk.debug.oreTiming=true}. {@code -Dsuperchunk.debug.oreTimingAB=<bits>} also turns
 * those {@link OreRuns} shortcuts off in every other chunk (a checkerboard of the chunks being decorated) and
 * reports the two halves apart. Each half tries the same ores, so the time per call compares directly.
 */
public final class OreTiming {

    public static final boolean ENABLED = Boolean.getBoolean("superchunk.debug.oreTiming");
    public static final int AB_MASK = ENABLED ? Integer.getInteger("superchunk.debug.oreTimingAB", 0) : 0;

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    // [0] = the default shortcuts, [1] = with AB_MASK turned off
    private static final LongAdder[] CALLS = {new LongAdder(), new LongAdder()};
    private static final LongAdder[] NS = {new LongAdder(), new LongAdder()};

    private OreTiming() {
    }

    public static long now() {
        return THREADS.getCurrentThreadCpuTime();
    }

    /** Which half a call belongs to: 1 (shortcuts in AB_MASK off) on odd squares of the checkerboard. */
    public static int half(WorldGenLevel level) {
        if (AB_MASK == 0 || !(level instanceof WorldGenRegion region)) {
            return 0;
        }
        ChunkPos center = region.getCenter();
        return (center.x + center.z) & 1;
    }

    public static void add(int half, long ns) {
        CALLS[half].increment();
        NS[half].add(ns);
    }

    public static void report() {
        if (!ENABLED) {
            return;
        }
        for (int half = 0; half < (AB_MASK != 0 ? 2 : 1); half++) {
            long n = CALLS[half].sum(), ns = NS[half].sum();
            org.slf4j.LoggerFactory.getLogger("SuperChunk-Ores").info(
                    "[ore-timing{}] calls={} total={} ms, {} us/call",
                    AB_MASK == 0 ? "" : half == 0 ? " A" : " B (without " + AB_MASK + ")", n,
                    ns / 1_000_000, n == 0 ? "n/a" : String.format("%.3f", ns / 1e3 / n));
        }
    }
}
