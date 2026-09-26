package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
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
 * <p><b>Boundedness.</b> Each cache is a set of striped, per-stripe access-ordered
 * (LRU) fastutil primitive-keyed maps with a per-stripe size cap; eviction removes the
 * least-recently-used cell, i.e. the cells trailing the generation frontier. Total memory is capped
 * at {@code superchunk.worldgen.aquiferCellCacheSize} entries (default 262144).
 *
 * <p><b>Thread-safety / determinism.</b> Multiple c2me worker threads share one cache
 * concurrently; each stripe guards its map with its own monitor. Stored values are the immutable
 * {@link Aquifer.FluidStatus} objects produced by {@code computeFluid}, so a hit returns a
 * byte-identical result to the per-instance path.
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

    private static final int STRIPES = 64;                 // power of two
    private static final int STRIPE_MASK = STRIPES - 1;
    private static final int MAX_TOTAL =
            Math.max(STRIPES, Integer.getInteger("superchunk.worldgen.aquiferCellCacheSize", 1 << 18));
    private static final int MAX_PER_STRIPE = Math.max(1, MAX_TOTAL / STRIPES);

    private final Long2ObjectLinkedOpenHashMap<Aquifer.FluidStatus>[] stripes;
    private final Object[] locks;

    @SuppressWarnings("unchecked")
    public ScAquiferCellCache() {
        this.stripes = new Long2ObjectLinkedOpenHashMap[STRIPES];
        this.locks = new Object[STRIPES];
        for (int i = 0; i < STRIPES; i++) {
            this.stripes[i] = new Long2ObjectLinkedOpenHashMap<>();
            this.locks[i] = new Object();
        }
    }

    // Fibonacci hashing so spatially-adjacent cells spread across stripes (reduces contention).
    private static int stripeOf(long key) {
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> 58) & STRIPE_MASK;
    }

    /** @return the cached status for the cell, or {@code null} on a miss. */
    public Aquifer.FluidStatus get(long key) {
        int s = stripeOf(key);
        synchronized (this.locks[s]) {
            return this.stripes[s].getAndMoveToFirst(key); // access => most-recently-used
        }
    }

    public void put(long key, Aquifer.FluidStatus value) {
        int s = stripeOf(key);
        Long2ObjectLinkedOpenHashMap<Aquifer.FluidStatus> map = this.stripes[s];
        synchronized (this.locks[s]) {
            map.putAndMoveToFirst(key, value);
            if (map.size() > MAX_PER_STRIPE) {
                map.removeLast(); // evict least-recently-used (trailing the generation frontier)
            }
        }
    }
}
