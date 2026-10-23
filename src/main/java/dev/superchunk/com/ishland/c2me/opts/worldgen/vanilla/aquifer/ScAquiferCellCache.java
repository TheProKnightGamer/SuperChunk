package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseRouter;

/**
 * SuperChunk cross-chunk aquifer {@link Aquifer.FluidStatus} cache (worldgen lever 1).
 *
 * <p>Gated ON by default; kill-switch {@code -Dsuperchunk.worldgen.aquiferCellCache=false}. Only
 * reached from {@code MixinAquiferSamplerImpl}, and ONLY for chunks whose {@code Blender} is empty
 * (blending chunks — pre-1.18 upgraded worlds near blend boundaries — read blended functions, so
 * they keep the per-chunk path).
 *
 * <p>Vanilla/C2ME caches {@code computeFluid} only per aquifer instance, but each grid cell is
 * consulted by the 3x3 chunks around it, so it is recomputed up to 9x. This cache memoizes the
 * status per cell for one {@code RandomState} (one dimension of one seed), so each cell is computed
 * once — per variant: the status also depends on whether the cell's jittered (x, z) lies in the
 * asking chunk's flat-cache grid ({@link #packCell}), except when {@code computeFluid} returns
 * early (every cell well above the surface), which it does before those reads, so such a status is
 * stored for both variants. For routers where anything else could make it depend on the chunk, or
 * when another mod hooks the code involved, there is no cache at all ({@link AquiferCellSharing}).
 *
 * <p><b>Scoping.</b> Each {@code RandomState} owns its cache ({@link Owner}) and hands it to every
 * {@code NoiseChunk} built from it ({@link Holder}); it goes away with the {@code RandomState}.
 *
 * <p><b>Boundedness.</b> A 4-way set-associative table of
 * {@code superchunk.worldgen.aquiferCellCacheSize} entries (default 524288, about 14 MB full),
 * least recently used last in each set: a put goes into the first way and shifts the others down
 * to the old copy of the key, an empty way or the last (dropped); a hit moves its entry to the
 * first way. Until 2026-10-22 this was 64 locked, access-ordered (LRU) maps of 4,096 (about
 * 10.5 MB); keeping their order on every hit cost about 0.4% of all worldgen CPU in GPU mode, which
 * asks for every cell of a chunk up front. The lookups themselves (random memory reads, about 0.5%)
 * cost the same either way. Measured hit rates, r1024 GPU pregen: LRU 262,144 83.6-83.7%; this
 * table 262,144 82.2%, 524,288 83.4%, 1,048,576 83.9%; a miss recomputes the cell (about 2 us).
 *
 * <p><b>Thread-safety / determinism.</b> Multiple c2me worker threads share one cache
 * concurrently without locks. An entry is an immutable (key, status) object, so a read sees a whole
 * entry or another one, never half of one; racing writes can only drop or repeat an entry, which
 * costs a later miss.
 * Stored values are the immutable {@link Aquifer.FluidStatus} objects produced by
 * {@code computeFluid}, so a hit returns a byte-identical result to the per-instance path.
 */
public final class ScAquiferCellCache {

    /** Implemented on {@code RandomState}: its cache, or null when its router cannot share. */
    public interface Owner {
        /** Called with the vanilla router before DFC compiles it (compiled functions are opaque). */
        void superchunk$decideCellSharing(NoiseRouter vanillaRouter);

        ScAquiferCellCache superchunk$aquiferCellCache();
    }

    /** Implemented on {@code NoiseChunk}: its {@code RandomState}'s cache, or null. */
    public interface Holder {
        ScAquiferCellCache superchunk$aquiferCellCache();
    }

    /**
     * Pack a grid cell {@code (l=gridX, m=gridY, n=gridZ)} and its flat-cache variant into a long
     * with disjoint bit-fields and no boxing. gridX/gridZ get 26 bits each (|cell| &lt; 2^25 =>
     * |block| &lt; ~536M, far beyond the ±30M world border), gridY 11 bits (±1024 cells => ±12288
     * blocks, beyond any world height) and the variant the low bit.
     *
     * <p>The variant: {@code computeFluid} reads erosion, depth and floodedness at the cell's exact
     * jittered (x, z), and a {@code flat_cache} inside them answers from the calling chunk's own
     * quart grid (the value at the quart corner, y = 0) when (x, z) falls in that grid, and computes
     * at (x, z) itself otherwise. So vanilla gives the same cell one of two statuses depending on
     * the chunk asking; each is chunk-independent, and chunks share only the one they would compute.
     */
    public static long packCell(int l, int m, int n, boolean inFlatGrid) {
        return ((l & 0x3FFFFFFL) << 38) | ((n & 0x3FFFFFFL) << 12) | ((m & 0x7FFL) << 1) | (inFlatGrid ? 1L : 0L);
    }

    /** Hit/miss counters, reported at server stop ({@code -Dsuperchunk.worldgen.aquiferCellCache.metrics=true}). */
    public static final boolean METRICS = Boolean.getBoolean("superchunk.worldgen.aquiferCellCache.metrics");
    private static final java.util.concurrent.atomic.LongAdder HITS = new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder MISSES = new java.util.concurrent.atomic.LongAdder();

    public static void report() {
        if (METRICS) {
            long h = HITS.sum(), m = MISSES.sum();
            org.slf4j.LoggerFactory.getLogger("SuperChunk-Aquifer").info(
                    "[aquifer-cell-cache] hits={} misses={} hit rate={}%", h, m,
                    h + m == 0 ? "n/a" : String.format("%.2f", 100.0 * h / (h + m)));
        }
    }

    private static final int WAYS = 4;
    private static final int SETS = Integer.highestOneBit(
            Math.max(WAYS, Integer.getInteger("superchunk.worldgen.aquiferCellCacheSize", 1 << 19)) / WAYS);
    private static final VarHandle SLOTS = MethodHandles.arrayElementVarHandle(Entry[].class);

    private record Entry(long key, Aquifer.FluidStatus status) {
    }

    /** The same RandomState's shared preliminary surface levels (valid under the same proof). */
    public final PreliminarySurfaceLevels surfaceLevels = new PreliminarySurfaceLevels();

    private final Entry[] slots = new Entry[WAYS * SETS];

    // Fibonacci hashing (the product's top bits) so spatially-adjacent cells spread over the sets.
    private static int setOf(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> (64 - Integer.numberOfTrailingZeros(SETS)));
    }

    /** @return the cached status for the cell, or {@code null} on a miss. */
    public Aquifer.FluidStatus get(long key) {
        int i = setOf(key) * WAYS;
        Aquifer.FluidStatus hit = null;
        for (int w = 0; w < WAYS; w++) {
            Entry e = (Entry) SLOTS.getAcquire(this.slots, i + w);
            if (e == null) {
                break;
            }
            if (e.key == key) {
                hit = e.status;
                if (w > 0) {
                    // Most recently used goes first, as in an LRU (cells shared by chunks generated
                    // far apart in time stay cached).
                    for (int v = w; v > 0; v--) {
                        SLOTS.setRelease(this.slots, i + v, SLOTS.getAcquire(this.slots, i + v - 1));
                    }
                    SLOTS.setRelease(this.slots, i, e);
                }
                break;
            }
        }
        if (METRICS) {
            (hit != null ? HITS : MISSES).increment();
        }
        return hit;
    }

    public void put(long key, Aquifer.FluidStatus value) {
        int i = setOf(key) * WAYS;
        // Already there (another worker computed the same cell, or the other variant of a cell the
        // picker answers): keep the one copy, so a set holds WAYS distinct cells.
        int w = 0;
        while (w < WAYS - 1) {
            Entry e = (Entry) SLOTS.getAcquire(this.slots, i + w);
            if (e == null || e.key == key) {
                break;
            }
            w++;
        }
        for (; w > 0; w--) {
            SLOTS.setRelease(this.slots, i + w, SLOTS.getAcquire(this.slots, i + w - 1));
        }
        SLOTS.setRelease(this.slots, i, new Entry(key, value));
    }
}
