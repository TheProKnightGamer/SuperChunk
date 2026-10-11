package dev.superchunk.worldgen;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cell-corner columns shared between the chunks of one {@code RandomState}.
 *
 * <p>{@code NoiseChunk.fillSlice} fills every interpolated function down each cell-corner column
 * of the chunk: 5 x 5 columns of 49 values in a vanilla overworld chunk. Columns on a chunk's
 * edges are also corners of the neighbouring chunks, so only 16 of the 25 are the chunk's own; the
 * other 9 are computed again by a neighbour. Where {@code AquiferCellSharing.cornerReason} has
 * proven every interpolated function chunk-independent down such columns (decided once from the
 * vanilla router), and the chunk has no blending, a column computed by one chunk is copied by the
 * next instead of evaluated again. Values are exact copies of what that chunk computed.
 *
 * <p>A direct-mapped table of immutable entries (key, values), published with release/acquire
 * semantics: a reader sees a whole entry or none, a collision evicts. Entries are only valid for
 * the column layout (cell height, rows, first row) the table was first used with.
 *
 * <p>Kill switch {@code -Dsuperchunk.worldgen.cornerColumns=false}; {@code .verify=true}
 * recomputes every shared column and compares it bit for bit; size
 * {@code -Dsuperchunk.worldgen.cornerColumnsSize} (entries, a power of two).
 */
public final class CornerColumns {

    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.cornerColumns", "true"));
    public static final boolean VERIFY = Boolean.getBoolean("superchunk.worldgen.cornerColumns.verify");
    public static final boolean METRICS = VERIFY || Boolean.getBoolean("superchunk.worldgen.cornerColumns.metrics");

    /** Implemented on {@code RandomState}. */
    public interface Owner {
        void superchunk$decideCornerSharing(net.minecraft.world.level.levelgen.NoiseRouter vanillaRouter);

        /** The table, or {@code null} when this dimension's columns cannot be shared. */
        CornerColumns superchunk$cornerColumns();
    }

    private static final class Entry {
        final long key;
        final double[] values;

        Entry(long key, double[] values) {
            this.key = key;
            this.values = values;
        }
    }

    private static final VarHandle SLOTS = MethodHandles.arrayElementVarHandle(Entry[].class);
    private static final int COORD_BIAS = 1 << 25; // corner x/4, z/4 in [-2^25, 2^25)

    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();
    private static final AtomicLong MISMATCHES = new AtomicLong();

    private final Entry[] slots;
    private final int mask;
    private volatile long layout = Long.MIN_VALUE;

    public CornerColumns() {
        int size = Integer.getInteger("superchunk.worldgen.cornerColumnsSize", defaultSize());
        size = Integer.highestOneBit(Math.max(1024, Math.min(size, 1 << 20)));
        this.slots = new Entry[size];
        this.mask = size - 1;
    }

    /** About 450 bytes an entry: 2^16 (29 MB at most) from a 6 GB heap, less on smaller ones. */
    private static int defaultSize() {
        long heap = Runtime.getRuntime().maxMemory();
        return heap >= (6L << 30) ? 1 << 16 : heap >= (3L << 30) ? 1 << 15 : 1 << 14;
    }

    /** Whether this table may serve a chunk with this column layout (the first caller's wins). */
    public boolean accepts(int cellHeight, int cellNoiseMinY, int cellCountY, int cellWidth) {
        long key = ((long) cellHeight << 48) ^ ((long) (cellNoiseMinY & 0xFFFF) << 32) ^ ((long) cellCountY << 16) ^ cellWidth;
        long current = this.layout;
        if (current == Long.MIN_VALUE) {
            synchronized (this) {
                if (this.layout == Long.MIN_VALUE) {
                    this.layout = key;
                }
                current = this.layout;
            }
        }
        return current == key;
    }

    /** The key of interpolator {@code index}'s column at block (x, z), or -1 when out of range. */
    public static long key(int index, int x, int z) {
        int qx = x >> 2;
        int qz = z >> 2;
        if (index < 0 || index >= 64 || qx < -COORD_BIAS || qx >= COORD_BIAS || qz < -COORD_BIAS || qz >= COORD_BIAS) {
            return -1L;
        }
        return ((long) index << 52) | ((long) (qx + COORD_BIAS) << 26) | (qz + COORD_BIAS);
    }

    private int slot(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 40) & this.mask;
    }

    /** The shared column for {@code key}, or {@code null}. Callers copy it; it is never written. */
    public double[] get(long key, int length) {
        Entry e = (Entry) SLOTS.getAcquire(this.slots, slot(key));
        if (e != null && e.key == key && e.values.length == length) {
            if (METRICS) {
                HITS.incrementAndGet();
            }
            return e.values;
        }
        if (METRICS) {
            MISSES.incrementAndGet();
        }
        return null;
    }

    /** Publishes a copy of a column this chunk just computed. */
    public void put(long key, double[] column) {
        SLOTS.setRelease(this.slots, slot(key), new Entry(key, column.clone()));
    }

    /** Verify mode: a shared column must equal the recomputed one bit for bit. */
    public static void verify(double[] shared, double[] computed, int index, int x, int z) {
        for (int i = 0; i < computed.length; i++) {
            if (Double.doubleToRawLongBits(shared[i]) != Double.doubleToRawLongBits(computed[i])) {
                if (MISMATCHES.incrementAndGet() <= 16) {
                    org.slf4j.LoggerFactory.getLogger("SuperChunk-Corners").warn(
                            "[corner-columns] VERIFY MISMATCH: interpolator {} column {},{} row {}: shared {} computed {}",
                            index, x, z, i, shared[i], computed[i]);
                }
                return;
            }
        }
    }

    public static void report() {
        if (METRICS) {
            long m = MISMATCHES.get();
            org.slf4j.LoggerFactory.getLogger("SuperChunk-Corners").info(
                    "[corner-columns] shared={} computed={}{}", HITS.get(), MISSES.get(),
                    VERIFY ? " VERIFY MISMATCHES=" + m + " -> " + (m == 0 ? "PASS" : "FAIL") : "");
        }
    }
}
