package dev.superchunk.worldgen;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-worker-thread memo of the three jitter offsets ("fiddles") of vanilla's biome zoom.
 *
 * <p>{@code BiomeManager.getBiome} scores the 8 quart lattice points around a block with
 * {@code getFiddledDistance}, which derives three offsets from 9 LCG steps and 3 modulus/divide
 * sequences of {@code (seed, x, y, z)}, then adds the block's fractional position. JFR attributes
 * 4.7% (CPU mode) to 8.2% (GPU mode) of worldgen worker CPU to that method body. The offsets depend
 * only on the seed and the lattice point, and every block in a 4x4x4 cell shares the same 8 points,
 * so surface rules and placement filters recompute the same values dozens of times.
 *
 * <p>Two direct-mapped tables, both keyed by exact packed quart coordinates with the full key
 * compared on every probe: lattice points (3 offsets each), used by the per-corner hook, and
 * whole cells (the 24 offsets of a cell's 8 corners, in vanilla's corner order), used by
 * {@link #nearestCorner} so a block costs one probe instead of eight. A cache serves one seed at
 * a time and is cleared when a different seed arrives. Entries are pure functions of their key,
 * so there is nothing to invalidate otherwise.
 */
public final class BiomeFiddleCache {
    private static final int SLOTS = 4096;
    private static final int MASK = SLOTS - 1;
    private static final int CELL_SLOTS = 2048;
    private static final int CELL_MASK = CELL_SLOTS - 1;
    /** Distinguishes a stored key from the zero-filled empty slot. */
    private static final long VALID = 1L << 62;
    public static final long UNCACHEABLE = Long.MIN_VALUE;

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("SuperChunk-BiomeCache");
    private static final AtomicLong VERIFIED = new AtomicLong();
    private static final AtomicLong MISMATCHES = new AtomicLong();

    /** Verify mode: one cached distance compared bit-for-bit with the original method's. */
    public static void recordVerify(int x, int y, int z, double cached, double original) {
        long checked = VERIFIED.incrementAndGet();
        if (Double.doubleToRawLongBits(cached) != Double.doubleToRawLongBits(original)
                && MISMATCHES.incrementAndGet() <= 20) {
            LOGGER.error("[biome-fiddle-cache] VERIFY MISMATCH at lattice ({}, {}, {}): cached={} original={}",
                    x, y, z, cached, original);
        }
        if ((checked & ((1L << 24) - 1)) == 0) {
            LOGGER.info("[biome-fiddle-cache] verify: checked={} mismatches={}", checked, MISMATCHES.get());
        }
    }

    /** Verify mode: one cell-path biome compared by identity with vanilla {@code getBiome}'s. */
    public static void recordCellVerify(int x, int y, int z, Object cellPath, Object vanilla) {
        long checked = VERIFIED.incrementAndGet();
        if (cellPath != vanilla && MISMATCHES.incrementAndGet() <= 20) {
            LOGGER.error("[biome-fiddle-cache] VERIFY MISMATCH (cell path) at block ({}, {}, {}): cell={} vanilla={}",
                    x, y, z, cellPath, vanilla);
        }
        if ((checked & ((1L << 24) - 1)) == 0) {
            LOGGER.info("[biome-fiddle-cache] verify: checked={} mismatches={}", checked, MISMATCHES.get());
        }
    }

    public static void reportVerify() {
        long m = MISMATCHES.get();
        LOGGER.info("[biome-fiddle-cache] VERIFY: checked={} MISMATCHES={} -> {}", VERIFIED.get(), m, m == 0 ? "PASS" : "FAIL");
    }

    private static final ThreadLocal<BiomeFiddleCache> LOCAL = ThreadLocal.withInitial(BiomeFiddleCache::new);

    private final long[] keys = new long[SLOTS];
    private final double[] fiddles = new double[SLOTS * 3];
    private final long[] cellKeys = new long[CELL_SLOTS];
    private final double[] cellFiddles = new double[CELL_SLOTS * 24];
    private long seed;
    private boolean seeded;

    private BiomeFiddleCache() {
    }

    public static BiomeFiddleCache local() {
        return LOCAL.get();
    }

    /**
     * Vanilla {@code getFiddledDistance(seed, x, y, z, xNoise, yNoise, zNoise)}, with the three
     * offsets taken from the table when present.
     */
    public double distance(long seed, int x, int y, int z, double xNoise, double yNoise, double zNoise) {
        this.useSeed(seed);
        int f = this.latticeSlot(seed, x, y, z);
        if (f < 0) {
            return fiddledDistance(seed, x, y, z, xNoise, yNoise, zNoise);
        }
        double[] fiddles = this.fiddles;
        return square(zNoise + fiddles[f + 2]) + square(yNoise + fiddles[f + 1]) + square(xNoise + fiddles[f]);
    }

    /**
     * The corner vanilla {@code getBiome} picks for a block: bit 2 selects x+1, bit 1 y+1 and
     * bit 0 z+1 of the quart cell {@code ((blockX-2)>>2, (blockY-2)>>2, (blockZ-2)>>2)}, or -1
     * when the cell is outside the packed-key range and the caller must run vanilla's method.
     * Same fractions, same corner order, same operand order and the same strict comparison.
     */
    public int nearestCorner(long seed, int blockX, int blockY, int blockZ) {
        this.useSeed(seed);
        int i = blockX - 2;
        int j = blockY - 2;
        int k = blockZ - 2;
        int l = i >> 2;
        int i1 = j >> 2;
        int j1 = k >> 2;
        long key = encode(l, i1, j1);
        if (key == UNCACHEABLE) {
            return -1;
        }
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 52) & CELL_MASK;
        int base = slot * 24;
        double[] cells = this.cellFiddles;
        if (this.cellKeys[slot] != key) {
            for (int c = 0; c < 8; c++) {
                int x = (c & 4) == 0 ? l : l + 1;
                int y = (c & 2) == 0 ? i1 : i1 + 1;
                int z = (c & 1) == 0 ? j1 : j1 + 1;
                int f = this.latticeSlot(seed, x, y, z);
                int o = base + c * 3;
                if (f >= 0) {
                    cells[o] = this.fiddles[f];
                    cells[o + 1] = this.fiddles[f + 1];
                    cells[o + 2] = this.fiddles[f + 2];
                } else {
                    fiddlesInto(seed, x, y, z, cells, o);
                }
            }
            this.cellKeys[slot] = key;
        }
        double d0 = (double) (i & 3) / 4.0;
        double d1 = (double) (j & 3) / 4.0;
        double d2 = (double) (k & 3) / 4.0;
        int k1 = 0;
        double d3 = Double.POSITIVE_INFINITY;
        for (int c = 0; c < 8; c++) {
            double d4 = (c & 4) == 0 ? d0 : d0 - 1.0;
            double d5 = (c & 2) == 0 ? d1 : d1 - 1.0;
            double d6 = (c & 1) == 0 ? d2 : d2 - 1.0;
            int o = base + c * 3;
            double d7 = square(d6 + cells[o + 2]) + square(d5 + cells[o + 1]) + square(d4 + cells[o]);
            if (d3 > d7) {
                k1 = c;
                d3 = d7;
            }
        }
        return k1;
    }

    private void useSeed(long seed) {
        if (!this.seeded || seed != this.seed) {
            Arrays.fill(this.keys, 0L);
            Arrays.fill(this.cellKeys, 0L);
            this.seed = seed;
            this.seeded = true;
        }
    }

    /** Offset of lattice point (x, y, z)'s three fiddles in {@link #fiddles}, or -1 if uncacheable. */
    private int latticeSlot(long seed, int x, int y, int z) {
        long key = encode(x, y, z);
        if (key == UNCACHEABLE) {
            return -1;
        }
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 52) & MASK;
        int f = slot * 3;
        if (this.keys[slot] != key) {
            fiddlesInto(seed, x, y, z, this.fiddles, f);
            this.keys[slot] = key;
        }
        return f;
    }

    private static void fiddlesInto(long seed, int x, int y, int z, double[] out, int o) {
        long s = LinearCongruentialSteps.lattice(seed, x, y, z);
        out[o] = fiddle(s);
        s = LinearCongruentialSteps.next(s, seed);
        out[o + 1] = fiddle(s);
        s = LinearCongruentialSteps.next(s, seed);
        out[o + 2] = fiddle(s);
    }

    /** Packs quart coordinates exactly (x, z: 24 bits; y: 12 bits), or {@link #UNCACHEABLE}. */
    static long encode(int x, int y, int z) {
        if (((x + 0x800000) >>> 24) != 0 || ((z + 0x800000) >>> 24) != 0 || ((y + 0x800) >>> 12) != 0) {
            return UNCACHEABLE;
        }
        return VALID | ((long) (x & 0xFFFFFF) << 36) | ((long) (z & 0xFFFFFF) << 12) | (y & 0xFFF);
    }

    /** Uncached copy of vanilla {@code BiomeManager.getFiddledDistance}, operation for operation. */
    public static double fiddledDistance(long seed, int x, int y, int z, double xNoise, double yNoise, double zNoise) {
        long s = LinearCongruentialSteps.lattice(seed, x, y, z);
        double d0 = fiddle(s);
        s = LinearCongruentialSteps.next(s, seed);
        double d1 = fiddle(s);
        s = LinearCongruentialSteps.next(s, seed);
        double d2 = fiddle(s);
        return square(zNoise + d2) + square(yNoise + d1) + square(xNoise + d0);
    }

    /** Vanilla {@code BiomeManager.getFiddle}. */
    static double fiddle(long seed) {
        double d = (double) BiomeFiddleMath.floorMod1024(seed >> 24) / 1024.0;
        return (d - 0.5) * 0.9;
    }

    private static double square(double v) {
        return v * v;
    }

    /** {@code LinearCongruentialGenerator.next} and the six-step lattice prefix of the fiddle. */
    static final class LinearCongruentialSteps {
        private LinearCongruentialSteps() {
        }

        static long next(long left, long right) {
            left *= left * 6364136223846793005L + 1442695040888963407L;
            return left + right;
        }

        static long lattice(long seed, int x, int y, int z) {
            long s = next(seed, x);
            s = next(s, y);
            s = next(s, z);
            s = next(s, x);
            s = next(s, y);
            return next(s, z);
        }
    }
}
