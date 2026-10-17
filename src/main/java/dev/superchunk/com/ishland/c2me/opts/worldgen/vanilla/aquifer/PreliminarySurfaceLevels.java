package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * One {@code RandomState}'s cross-chunk memo of {@code NoiseChunk.computePreliminarySurfaceLevel}
 * (owned by its {@link ScAquiferCellCache}).
 *
 * <p>The preliminary surface level of a quart-aligned column scans
 * {@code initial_density_without_jaggedness} down the column at single points. Wherever the
 * aquifer cell cache exists, {@link AquiferCellSharing} has proven that function chunk-independent
 * down such columns (no {@code flat_cache} over a y-dependent function, no {@code cache_2d} over
 * one, no beardifier, nothing unknown), so the level is a pure function of the column for unblended
 * chunks. Vanilla memoizes it per {@code NoiseChunk}; the aquifer asks about columns up to 25
 * blocks outside the chunk, so neighbouring chunks recompute the same columns many times.
 *
 * <p>A direct-mapped table of single {@code long} entries (valid bit, quart x, quart z, level), so a
 * read never sees half an entry and a racing writer only stores the same pure value or evicts.
 * Entries are only valid for the noise settings the table was first used with.
 * Kill switch {@code -Dsuperchunk.worldgen.sharedSurfaceLevels=false}.
 */
public final class PreliminarySurfaceLevels {

    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.sharedSurfaceLevels", "true"));

    public static final int MISS = Integer.MIN_VALUE;

    public static final boolean VERIFY = Boolean.getBoolean("superchunk.worldgen.sharedSurfaceLevels.verify");
    public static final boolean METRICS = VERIFY || Boolean.getBoolean("superchunk.worldgen.sharedSurfaceLevels.metrics");
    public static final java.util.concurrent.atomic.AtomicLong HITS = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong MISSES = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong MISMATCHES = new java.util.concurrent.atomic.AtomicLong();

    public static void report() {
        if (METRICS) {
            long m = MISMATCHES.get();
            org.slf4j.LoggerFactory.getLogger("SuperChunk-Aquifer").info(
                    "[surface-levels] shared hits={} computed={}{}", HITS.get(), MISSES.get(),
                    VERIFY ? " VERIFY MISMATCHES=" + m + " -> " + (m == 0 ? "PASS" : "FAIL") : "");
        }
    }

    private static final int BITS = 17;
    private static final int MASK = (1 << BITS) - 1;
    private static final long VALID = 1L << 62;
    private static final int COORD_BIAS = 1 << 23;     // quart coordinates in [-2^23, 2^23)
    private static final int LEVEL_BIAS = 1 << 13;     // levels in [-2^13, 2^13 - 1)
    private static final int LEVEL_NONE = (1 << 14) - 1; // Integer.MAX_VALUE: no surface in the column
    private static final VarHandle SLOTS = MethodHandles.arrayElementVarHandle(long[].class);

    private final long[] slots = new long[1 << BITS];
    private volatile long settings = Long.MIN_VALUE;

    /** Whether this table may serve a chunk with these noise settings (the first caller's win). */
    public boolean accepts(int minY, int height, int cellHeight) {
        long key = ((long) minY << 40) ^ ((long) height << 16) ^ cellHeight;
        long current = this.settings;
        if (current == Long.MIN_VALUE) {
            synchronized (this) {
                if (this.settings == Long.MIN_VALUE) {
                    this.settings = key;
                }
                current = this.settings;
            }
        }
        return current == key;
    }

    private static boolean inRange(int quartX, int quartZ) {
        return quartX >= -COORD_BIAS && quartX < COORD_BIAS && quartZ >= -COORD_BIAS && quartZ < COORD_BIAS;
    }

    private static int slot(int quartX, int quartZ) {
        int h = quartX * 0x9E3779B1 + quartZ * 0x85EBCA77;
        return (h ^ (h >>> 15)) & MASK;
    }

    private static long tag(int quartX, int quartZ) {
        return VALID | ((long) (quartX + COORD_BIAS) << 38) | ((long) (quartZ + COORD_BIAS) << 14);
    }

    /** The cached level of the column at block (x, z) (quart-aligned), or {@link #MISS}. */
    public int get(int x, int z) {
        int qx = x >> 2;
        int qz = z >> 2;
        if (!inRange(qx, qz)) {
            return MISS;
        }
        long entry = (long) SLOTS.getOpaque(this.slots, slot(qx, qz));
        if ((entry & ~0x3FFFL) != tag(qx, qz)) {
            return MISS;
        }
        int level = (int) (entry & 0x3FFFL);
        return level == LEVEL_NONE ? Integer.MAX_VALUE : level - LEVEL_BIAS;
    }

    public void put(int x, int z, int level) {
        int qx = x >> 2;
        int qz = z >> 2;
        int encoded;
        if (level == Integer.MAX_VALUE) {
            encoded = LEVEL_NONE;
        } else if (level >= -LEVEL_BIAS && level < LEVEL_NONE - LEVEL_BIAS) {
            encoded = level + LEVEL_BIAS;
        } else {
            return;
        }
        if (inRange(qx, qz)) {
            SLOTS.setOpaque(this.slots, slot(qx, qz), tag(qx, qz) | encoded);
        }
    }
}
