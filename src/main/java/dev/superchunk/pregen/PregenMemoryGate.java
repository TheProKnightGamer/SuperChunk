package dev.superchunk.pregen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;

/**
 * Slows Chunky's request loop down while the heap is nearly full of live data.
 *
 * <p>Every chunk Chunky has in flight keeps its neighbourhood loaded (25 to 35 chunks each on a
 * long pregen), so the memory a pregen needs grows with the in-flight limit and with how heavy the
 * modpack's chunks are. {@code pregen.chunkyWorkingCount} sizes that limit for vanilla chunks; this
 * gate covers the rest. Without it a full heap does not fail cleanly: the collector runs back to
 * back, throughput falls to a few chunks per second and some mod's worldgen eventually throws
 * OutOfMemoryError, which reads as "Chunky froze".
 *
 * <p>Two measurements have to agree before a request is held back, because neither is reliable
 * alone. (1) What survived the last collection of the old generation, from the memory pools'
 * collection usage: the best estimate of live data, but stale until the next such collection.
 * (2) The lowest heap use seen in the last few seconds: always current, but generational
 * collectors leave dead old-generation objects in it for a long time. Live data cannot be above
 * either number.
 *
 * <p>Requests are never stopped outright: under pressure one is let through per
 * {@link #HELD_INTERVAL_MS}, so the chunks in flight finish and unload, and a heap that is simply
 * too small for the pack still makes progress at about vanilla speed.
 *
 * <p>Called from Chunky's own task thread (never the server thread), before it takes a permit.
 */
public final class PregenMemoryGate {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Pregen");

    /** {@code -Dsuperchunk.pregen.memoryGate=false} turns the gate off. */
    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.pregen.memoryGate", "true"));
    /** Fraction of the maximum heap above which requests are held back. */
    private static final double THRESHOLD = parseFraction(
            System.getProperty("superchunk.pregen.memoryGate.threshold"), 0.90);

    private static final int BUCKETS = 32;
    private static final long BUCKET_NANOS = 125_000_000L; // 32 x 125 ms = a 4 s window
    private static final long HELD_INTERVAL_MS = 50L;
    private static final long WARN_INTERVAL_NANOS = 60_000_000_000L;
    private static final long SURVIVED_REFRESH_NANOS = 250_000_000L;

    private static final long[] bucketMin = new long[BUCKETS];
    private static final long[] bucketStamp = new long[BUCKETS];
    private static long lastWarnNanos = Long.MIN_VALUE;

    private static final List<MemoryPoolMXBean> HEAP_POOLS = heapPools();
    private static long survived = -1;
    private static long survivedStamp = Long.MIN_VALUE;

    private PregenMemoryGate() {
    }

    /** Waits a moment if the heap is under pressure. Returns at once otherwise. */
    public static void await() {
        if (!ENABLED || HEAP_POOLS.isEmpty()) {
            return;
        }
        final long max = Runtime.getRuntime().maxMemory();
        if (max == Long.MAX_VALUE || max <= 0) {
            return;
        }
        final long limit = (long) (max * THRESHOLD);
        if (!underPressure(limit)) {
            return;
        }
        try {
            Thread.sleep(HELD_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Chunky's loop checks the flag and stops the task
            return;
        }
        warn(max);
    }

    private static synchronized boolean underPressure(long limit) {
        final long now = System.nanoTime();
        final long floor = floor(now); // always recorded, so the window is current when it matters
        if (floor <= limit) {
            return false;
        }
        if (survivedStamp == Long.MIN_VALUE || now - survivedStamp > SURVIVED_REFRESH_NANOS) {
            survivedStamp = now;
            survived = survivedLastCollection();
        }
        return survived > limit;
    }

    /** Records the current heap use and returns the lowest use seen in the window. */
    private static long floor(long now) {
        final Runtime rt = Runtime.getRuntime();
        final long used = rt.totalMemory() - rt.freeMemory();
        final long stamp = now / BUCKET_NANOS;
        final int slot = (int) Math.floorMod(stamp, (long) BUCKETS);
        if (bucketStamp[slot] != stamp) {
            bucketStamp[slot] = stamp;
            bucketMin[slot] = used;
        } else if (used < bucketMin[slot]) {
            bucketMin[slot] = used;
        }
        long floor = used;
        for (int i = 0; i < BUCKETS; i++) {
            if (stamp - bucketStamp[i] < BUCKETS && bucketMin[i] < floor) {
                floor = bucketMin[i];
            }
        }
        return floor;
    }

    /** Heap use right after each pool was last collected, summed; -1 if the JVM does not say. */
    private static long survivedLastCollection() {
        long sum = 0;
        boolean any = false;
        try {
            for (MemoryPoolMXBean pool : HEAP_POOLS) {
                final MemoryUsage usage = pool.getCollectionUsage();
                if (usage != null) {
                    sum += usage.getUsed();
                    any = true;
                }
            }
        } catch (Throwable t) {
            return -1;
        }
        return any ? sum : -1;
    }

    private static List<MemoryPoolMXBean> heapPools() {
        final List<MemoryPoolMXBean> pools = new ArrayList<>();
        try {
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP) {
                    pools.add(pool);
                }
            }
        } catch (Throwable t) {
            pools.clear(); // no management interface: the gate stays off
        }
        return pools;
    }

    private static synchronized void warn(long max) {
        final long now = System.nanoTime();
        if (lastWarnNanos != Long.MIN_VALUE && now - lastWarnNanos < WARN_INTERVAL_NANOS) {
            return;
        }
        lastWarnNanos = now;
        LOGGER.warn("Pre-generation is being slowed down because the server is almost out of memory "
                        + "(more than {}% of the {} MB heap is in use even right after garbage collection). "
                        + "Give the server more memory (-Xmx), or set pregen.chunkyWorkingCount to a smaller "
                        + "number in config/superchunk.properties.",
                (int) Math.round(THRESHOLD * 100), max >> 20);
    }

    private static double parseFraction(String s, double fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            final double v = Double.parseDouble(s.trim());
            return v > 0.2 && v < 1.0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
