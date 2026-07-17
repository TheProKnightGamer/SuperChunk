package dev.superchunk.com.ishland.c2me.opts.worldgen.vanilla.aquifer;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.world.level.levelgen.Aquifer;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * SuperChunk cross-chunk aquifer {@link Aquifer.FluidStatus} cache (worldgen lever 1).
 *
 * <p>Gated ON by default; kill-switch {@code -Dsuperchunk.worldgen.aquiferCellCache=false}. Only
 * reached from {@code MixinAquiferSamplerImpl}, and ONLY for chunks whose {@code Blender} is empty
 * ({@code scCellCache()} resolves to null for blending chunks — pre-1.18 upgraded worlds near blend
 * boundaries — because there {@code computeFluid} is chunk-dependent and must not be shared).
 *
 * <p>For non-blending chunks {@code computeFluid(cellJitteredPos)} is a pure deterministic function
 * of the grid cell {@code (gx,gy,gz)} + seed ({@code preliminarySurfaceLevel} is a pure function of
 * {@code (x,z)}). Vanilla/C2ME caches it only per-aquifer-INSTANCE, but each grid cell
 * is shared by ~4 neighbouring chunks, so it is recomputed up to ~4x. This cache memoizes the
 * status per cell across every chunk of one dimension, so each cell is computed once.
 *
 * <p><b>Per-dimension scoping.</b> The registry is keyed by the identity of the dimension's
 * {@code globalFluidPicker} — a single lambda instance memoized once per {@code NoiseBasedChunkGenerator}
 * (i.e. once per dimension) and shared by every {@code NoiseChunk}/aquifer of that dimension. Distinct
 * dimensions have distinct generators, hence distinct picker instances, so cells never collide across
 * dimensions (which is essential: the same cell {@code (gx,gy,gz)} has a different FluidStatus in a
 * different dimension). Keys are weak so a discarded generator (world reload) drops its cache.
 *
 * <p><b>Boundedness.</b> Each per-dimension cache is a set of striped, per-stripe access-ordered
 * (LRU) fastutil primitive-keyed maps with a per-stripe size cap; eviction removes the
 * least-recently-used cell, i.e. the cells trailing the generation frontier. Total memory is capped
 * at {@code superchunk.worldgen.aquiferCellCacheSize} entries (default 262144).
 *
 * <p><b>Thread-safety / determinism.</b> Multiple c2me worker threads share one per-dimension cache
 * concurrently; each stripe guards its map with its own monitor. Stored values are the immutable
 * {@link Aquifer.FluidStatus} objects produced by {@code computeFluid}, so a hit returns a
 * byte-identical result to the per-instance path.
 */
public final class ScAquiferCellCache {

    // Per-dimension registry keyed by the identity of the dimension's memoized globalFluidPicker.
    // Weak keys: when a generator (and thus its picker) is GC'd on world unload, the cache is dropped.
    // Lookups are rare (once per aquifer instance; the reference is then cached on the instance).
    private static final Map<Aquifer.FluidPicker, ScAquiferCellCache> REGISTRY =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static ScAquiferCellCache forDimension(Aquifer.FluidPicker picker) {
        synchronized (REGISTRY) {
            ScAquiferCellCache c = REGISTRY.get(picker);
            if (c == null) {
                c = new ScAquiferCellCache();
                REGISTRY.put(picker, c);
            }
            return c;
        }
    }

    /**
     * Pack a grid cell {@code (l=gridX, m=gridY, n=gridZ)} into a long with disjoint bit-fields and
     * no boxing. gridX/gridZ get 26 bits each (|cell| &lt; 2^25 => |block| &lt; ~536M, far beyond the
     * ±30M world border) and gridY gets 12 bits (±2048 cells => ±24576 blocks, far beyond world
     * height). Disjoint OR-composed fields => no collision within these ranges.
     */
    public static long packCell(int l, int m, int n) {
        return ((l & 0x3FFFFFFL) << 38) | ((n & 0x3FFFFFFL) << 12) | (m & 0xFFFL);
    }

    private static final int STRIPES = 64;                 // power of two
    private static final int STRIPE_MASK = STRIPES - 1;
    private static final int MAX_TOTAL =
            Math.max(STRIPES, Integer.getInteger("superchunk.worldgen.aquiferCellCacheSize", 1 << 18));
    private static final int MAX_PER_STRIPE = Math.max(1, MAX_TOTAL / STRIPES);

    private final Long2ObjectLinkedOpenHashMap<Aquifer.FluidStatus>[] stripes;
    private final Object[] locks;

    @SuppressWarnings("unchecked")
    private ScAquiferCellCache() {
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
