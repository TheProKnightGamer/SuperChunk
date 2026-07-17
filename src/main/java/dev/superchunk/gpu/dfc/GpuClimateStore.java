package dev.superchunk.gpu.dfc;

import dev.superchunk.gpu.OpenCLBackend;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SuperChunk GPU — chunk-keyed store for CROSS-CHUNK batcher-computed <b>biome-climate</b>
 * quart-lattice grids. The climate sibling of {@link GpuBatchStore} (same architecture,
 * simpler payload: no pooled field holders, no compact ids).
 *
 * <p>The async BIOMES-status prefetch ({@link GpuClimatePrefetch}) submits a chunk's
 * fused climate-group request to the dedicated climate {@link GpuChunkBatcher}; when the
 * batched dispatch completes (on the climate drainer thread) the resulting
 * {@code M*gridLen} grids are deposited here keyed by the chunk position + quart-lattice
 * geometry + the climate group's fused-program identity. The subsequent biome fill —
 * {@code ChunkAccess.fillBiomesFromNoise} on a worker, AFTER the prefetch completed —
 * consumes the entry in {@link BiomeClimateCache#beginChunk} and serves every quart
 * sample from it with NO per-chunk GPU dispatch.
 *
 * <p><b>Key.</b> {@code (chunkPosLong, oyQuart, dimY, fused)}: the horizontal quart
 * origin and {@code dimX=dimZ=4} are pure functions of the chunk pos, so only the
 * vertical extent ({@code oyQuart}, {@code dimY}) and the per-dimension fused-program
 * IDENTITY qualify the position. Two dimensions with identical height ranges (e.g.
 * nether/end) still get distinct keys because each dimension registers its own fused
 * climate program — entries can never alias across dimensions.
 *
 * <p><b>Bounded — never leaks.</b> An entry is normally consumed by the very next biome
 * fill on the same position. The only way an entry orphans is gen cancellation between
 * prefetch-complete and fill (or a sampler/group mismatch at the fill); a hard size cap
 * evicts stale entries so memory is bounded regardless. An evicted/unmatched chunk falls
 * back to the funneled per-chunk climate path — correctness unaffected.
 *
 * <p>Entirely dormant unless {@code -Dsuperchunk.biome.batchClimate} is on (nothing ever
 * calls {@link #put}); flag-OFF is zero behavior change.
 */
public final class GpuClimateStore {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    /**
     * Hard cap on live entries — a pure leak guard against cancelled chunks (each
     * entry is {@code M*gridLen} doubles, ~12-36 KB for a default overworld chunk).
     */
    private static final int MAX_ENTRIES = 4096;

    private static final Map<Key, Stored> STORE = new ConcurrentHashMap<>();

    /** Observability. */
    private static final AtomicLong puts = new AtomicLong();
    private static final AtomicLong takes = new AtomicLong();
    private static final AtomicLong evictions = new AtomicLong();

    private GpuClimateStore() {
    }

    /**
     * Store key: the {@link net.minecraft.world.level.ChunkPos#toLong()} position, the
     * quart-lattice vertical extent ({@code oyQuart} = quart origin Y, {@code dimY} =
     * quart rows), and the dimension's fused climate program identity (identity
     * {@code equals} — {@link GpuFusedInterpolator} does not override it). Both the
     * prefetch (deposit) and {@link BiomeClimateCache#beginChunk} (consume) derive these
     * fields from the SAME chunk via the SAME formulas, so a deposit is always found by
     * its own chunk's fill and can never alias another dimension's entry.
     */
    public record Key(long chunkPosLong, int oyQuart, int dimY, GpuFusedInterpolator fused) {
    }

    /**
     * One chunk's batched climate grids: the fused climate program they were computed
     * for (identity re-checked at consume) and the packed {@code M*gridLen} doubles
     * (root {@code k}'s quart grid at {@code [k*gridLen ..]}, matching the fused
     * program's root order — the exact bytes the per-chunk fused dispatch would have
     * produced, enforced by the boot-time byte-parity gate).
     */
    public static final class Stored {
        final GpuFusedInterpolator fused;
        final double[] grids;

        public Stored(GpuFusedInterpolator fused, double[] grids) {
            this.fused = fused;
            this.grids = grids;
        }
    }

    /** Whether an entry is currently held for {@code key} (idempotency guard for re-submits). */
    public static boolean contains(Key key) {
        return key != null && STORE.containsKey(key);
    }

    /**
     * Deposits {@code grids} for {@code key}. Bounded: over the cap, a batch of
     * arbitrary stale entries is dropped (those chunks use the funneled per-chunk
     * path). Called from the climate batcher drainer thread on dispatch completion.
     */
    public static void put(Key key, Stored grids) {
        if (key == null || grids == null) {
            return;
        }
        STORE.put(key, grids);
        puts.incrementAndGet();
        if (STORE.size() > MAX_ENTRIES) {
            evictSome();
        }
    }

    /**
     * Atomically removes and returns the entry for {@code key} for consumption, or
     * {@code null} if none (raced with an evict/cancel — the chunk then uses the
     * funneled per-chunk path).
     */
    public static Stored take(Key key) {
        if (key == null) {
            return null;
        }
        Stored s = STORE.remove(key);
        if (s != null) {
            takes.incrementAndGet();
        }
        return s;
    }

    /** Removes an orphaned entry (gen cancelled before consume). Plain grids — nothing to release. */
    public static void remove(Key key) {
        if (key != null && STORE.remove(key) != null) {
            takes.incrementAndGet();
        }
    }

    /** Clears the whole store (shutdown / group rebuild). */
    public static void clear() {
        STORE.clear();
    }

    public static int size() {
        return STORE.size();
    }

    public static long puts() {
        return puts.get();
    }

    public static long takes() {
        return takes.get();
    }

    public static long evictions() {
        return evictions.get();
    }

    /** Drops ~1/4 of the entries when over the cap. Cheap, best-effort, correctness-neutral. */
    private static void evictSome() {
        int target = MAX_ENTRIES / 4;
        int dropped = 0;
        for (Key k : STORE.keySet()) {
            if (dropped >= target) {
                break;
            }
            if (STORE.remove(k) != null) {
                dropped++;
            }
        }
        if (dropped > 0) {
            evictions.addAndGet(dropped);
            LOGGER.warn("[SuperChunk-GPU] [biome-batch] climate grid store over cap ({}) — evicted {} stale entries "
                    + "(those chunks use the funneled per-chunk climate path).", MAX_ENTRIES, dropped);
        }
    }
}
