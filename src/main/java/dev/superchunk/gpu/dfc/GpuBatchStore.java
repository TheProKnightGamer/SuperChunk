package dev.superchunk.gpu.dfc;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkNoiseSampler;
import dev.superchunk.gpu.OpenCLBackend;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SuperChunk GPU — chunk-keyed store for CROSS-CHUNK batcher-computed density grids.
 *
 * <p>The async noise-status prefetch ({@link GpuBatchPrefetch}) submits a chunk's
 * fused-group density-grid request to the {@link GpuChunkBatcher}; when the batched
 * dispatch completes (on the batcher thread), the resulting {@code M*gridLen} grids
 * are deposited here keyed by the chunk's {@link ChunkPos}. The subsequent noise fill
 * — running on a worker AFTER the prefetch freed it — looks the grids up by
 * {@link Key} in {@link ChunkGridCache#beginChunk} and serves every column from
 * them with NO per-chunk GPU dispatch.
 *
 * <p><b>Why a dimension-qualified key, not NoiseChunk identity.</b> The {@code NoiseChunk}
 * cached on the {@code ChunkAccess} at the biomes/noise boundary (what the prefetch peeks) is
 * NOT necessarily the same instance that drives the terrain block-fill's interpolation loop,
 * and the fill also instantiates smaller sub-region samplers. A {@link ChunkPos} key + an
 * exact lattice-extent match is identity-independent and unambiguous: only the full-chunk fill
 * for that position (same origin/strides/dims) consumes the entry; sub-region samplers
 * (different dims) leave it untouched.
 *
 * <p><b>Why the key is qualified by dimension.</b> A {@link ChunkPos}-ONLY key aliases across
 * dimensions: with {@code batchChunks} on and two dimensions generating concurrently at a
 * matching {@code (x,z)}, one dimension's {@link #put} could replace another's peeked entry
 * between {@link #get} and {@link #take}, handing a chunk another dimension's grids. So the key
 * ({@link Key}) is the {@link ChunkPos} qualified by the chunk's per-dimension noise-lattice
 * GEOMETRY (cell strides, dims, and vertical origin {@code oy}=minY) — those fields are
 * pos-INDEPENDENT and differ between dimensions, so entries never alias across dimensions. For a
 * SINGLE dimension the geometry is constant across every chunk, so the key is a pure function of
 * {@link ChunkPos} and behavior is bit-for-bit identical to the old ChunkPos key.
 *
 * <p><b>Bounded — never leaks.</b> An entry is normally consumed by the very next
 * fill on the same position, so the live size is tiny. The only way an entry orphans
 * is gen cancellation between prefetch-complete and fill; a hard size cap evicts
 * stale entries so memory is bounded regardless. An evicted/unmatched chunk simply
 * falls back to the per-chunk dispatch path — correctness is unaffected.
 *
 * <p>Entirely dormant unless {@code batchChunks} is on (nothing ever calls
 * {@link #put}); flag-OFF is zero behavior change.
 */
public final class GpuBatchStore {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    /**
     * Hard cap on live entries. Far above any realistic concurrent in-flight count;
     * exists purely as a leak guard against cancelled chunks. Each entry is
     * {@code M*gridLen} doubles (~tens of KB), so even the cap is modest memory.
     */
    private static final int MAX_ENTRIES = 4096;

    private static final Map<Key, Stored> STORE = new ConcurrentHashMap<>();

    /** Observability. */
    private static final AtomicLong puts = new AtomicLong();
    private static final AtomicLong takes = new AtomicLong();
    private static final AtomicLong evictions = new AtomicLong();

    private GpuBatchStore() {
    }

    /**
     * Dimension-qualified store key: the chunk {@link ChunkPos} plus the chunk's per-dimension
     * noise-lattice GEOMETRY. The geometry fields ({@code sx}/{@code sy}/{@code sz} cell strides,
     * {@code dimX}/{@code dimY}/{@code dimZ} corner dims, and {@code oy}=minY) are pos-INDEPENDENT
     * and differ between dimensions, so a {@code (x,z)} shared by two dimensions maps to two
     * distinct keys — a cross-dimension {@link #put} can never replace, nor a {@link #take} steal,
     * another dimension's entry. For a single dimension the geometry is constant, so the key is a
     * pure function of {@link ChunkPos} (behavior identical to the old ChunkPos key).
     *
     * <p>The pos-derived horizontal origins ({@code ox}/{@code oz}) are excluded — {@link ChunkPos}
     * already carries the horizontal position. Where two distinct dimensions DO share identical
     * geometry AND position, the batched dispatch is a pure function of (geometry, position,
     * primary fused group), so both compute bit-identical grids and a shared slot is provably safe.
     */
    public record Key(ChunkPos pos, int sx, int sy, int sz, int oy, int dimX, int dimY, int dimZ) {
    }

    /**
     * Builds the dimension-qualified {@link Key} for {@code pos} from a chunk's cell-corner
     * lattice. Both the prefetch (deposit) and the fill (consume) derive these fields from the
     * SAME {@code NoiseChunk} geometry (via {@link Extent#derive}/{@link ChunkGridCache}'s
     * matching formulas), so a dimension's deposit is always found by that same dimension's fill.
     * {@code ox}/{@code oz} are accepted for symmetry with {@link Extent#matches} but excluded
     * from the key (pos-derived; {@code pos} already carries them).
     */
    public static Key key(ChunkPos pos, int ox, int oy, int oz, int sx, int sy, int sz,
                          int dimX, int dimY, int dimZ) {
        return new Key(pos, sx, sy, sz, oy, dimX, dimY, dimZ);
    }

    /** Whether an entry is currently held for {@code key} (idempotency guard for re-submits). */
    public static boolean contains(Key key) {
        return key != null && STORE.containsKey(key);
    }

    // ------------------------------------------------------------------
    // PENDING markers (climate-chain combined prefetch, 2026-07-07): a chunk whose
    // density request was already submitted at the BIOMES seam (riding the chained
    // climate batch) marks its key pending so the later NOISE-seam prefetch doesn't
    // double-submit. Cleared when the deposit lands / fails / is cancelled. Purely an
    // efficiency guard — a double submit would deposit bit-identical grids twice.
    // ------------------------------------------------------------------
    private static final java.util.Set<Key> PENDING = ConcurrentHashMap.newKeySet();

    /** Marks {@code key} as submitted-but-not-yet-deposited. False if already pending. */
    public static boolean markPending(Key key) {
        return key != null && PENDING.add(key);
    }

    /** Clears a pending marker (deposit landed, failed, or was cancelled). */
    public static void clearPending(Key key) {
        if (key != null) {
            PENDING.remove(key);
        }
    }

    /** Whether a biomes-seam combined submit is in flight for {@code key}. */
    public static boolean isPending(Key key) {
        return key != null && PENDING.contains(key);
    }

    /**
     * Deposits {@code grids} for {@code pos}. Bounded: if the cap is exceeded, a batch
     * of arbitrary stale entries is dropped (those chunks fall back to the per-chunk
     * path). Called from the batcher thread on dispatch completion. If an entry already
     * existed for {@code key}, its pooled field holder (if any) is returned to the pool.
     */
    public static void put(Key key, Stored grids) {
        if (key == null || grids == null) {
            return;
        }
        Stored prev = STORE.put(key, grids);
        if (prev != null) {
            releaseField(prev);   // replaced a live entry -> return its holder to the pool
        }
        puts.incrementAndGet();
        if (STORE.size() > MAX_ENTRIES) {
            evictSome();
        }
    }

    /** Peeks (does NOT remove) the stored grids for {@code key}, or {@code null}. */
    public static Stored get(Key key) {
        return key == null ? null : STORE.get(key);
    }

    /**
     * Atomically removes and RETURNS the entry for {@code key} for CONSUMPTION, transferring
     * ownership of any pooled field holder to the caller (this does NOT release it — the
     * consumer serves the field then returns the holder to the pool at {@code endChunk}).
     * Returns {@code null} if no entry (e.g. it raced with an evict/cancel). Used only by the
     * fill's {@link ChunkGridCache#consumeBatchedGrid}.
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

    /**
     * Removes the entry for {@code key} as an ORPHAN (gen cancelled before consume): returns
     * any pooled field holder to the pool so nothing leaks. Distinct from {@link #take}, which
     * hands holder ownership to the consumer.
     */
    public static void remove(Key key) {
        if (key == null) {
            return;
        }
        Stored s = STORE.remove(key);
        if (s != null) {
            takes.incrementAndGet();
            releaseField(s);   // orphaned prefetched field -> return its holder to the pool
        }
    }

    /** Clears the whole store (shutdown / batcher rebuild); returns every entry's holder to the pool. */
    public static void clear() {
        PENDING.clear();
        for (Key k : STORE.keySet()) {
            Stored s = STORE.remove(k);
            if (s != null) {
                releaseField(s);
            }
        }
    }

    /** Returns a dropped entry's pooled field holder (if any) to the pool. Never throws. */
    private static void releaseField(Stored s) {
        if (s != null && s.field != null) {
            try {
                OnDeviceFieldPool.release(s.field);
            } catch (Throwable ignored) {
            }
        }
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
            Stored s = STORE.remove(k);
            if (s != null) {
                releaseField(s);   // evicted (orphan) prefetched field -> return its holder to the pool
                dropped++;
            }
        }
        if (dropped > 0) {
            evictions.addAndGet(dropped);
            LOGGER.warn("[SuperChunk-GPU] [batch] grid store over cap ({}) — evicted {} stale entries (those chunks use the per-chunk path).",
                    MAX_ENTRIES, dropped);
        }
    }

    /**
     * One chunk's batched grids: the fused group they belong to (identity check at
     * serve time), the cell-corner lattice {@link Extent} they were computed over,
     * and the packed {@code M*gridLen} doubles (member {@code k}'s grid at
     * {@code [k*gridLen ..]}, matching {@link GpuFusedInterpolator#members()} order).
     */
    public static final class Stored {
        final GpuFusedInterpolator fused;
        final Extent ext;
        final double[] grids;
        /**
         * The full interpolated field PRE-PRODUCED at prefetch into a REUSED pooled holder
         * (on-device interp only), or {@code null} when it wasn't produced (flag off, pool
         * exhausted, or production failed) — then the consumer produces it per-chunk. Owned by
         * this entry until {@link #take taken} (ownership -> consumer) or dropped (holder returned
         * to {@link OnDeviceFieldPool}).
         */
        final OnDeviceFieldPool.Holder field;
        /**
         * COMPACT-IDS (Stage 5, {@code -Dsuperchunk.gpu.compactIds}): this chunk's
         * 1-byte/block decide-kernel ids (id | bit7 = shouldScheduleFluidUpdate), or
         * {@code null} when the chunk had no aux / the decide chain didn't run. In PROBE
         * mode the consumer ({@link ChunkGridCache}) deliberately IGNORES this field —
         * the ids exist only to prove the chained readback arrived (plumbing-tax probe).
         */
        final byte[] ids;

        public Stored(GpuFusedInterpolator fused, Extent ext, double[] grids, OnDeviceFieldPool.Holder field) {
            this(fused, ext, grids, field, null);
        }

        public Stored(GpuFusedInterpolator fused, Extent ext, double[] grids, OnDeviceFieldPool.Holder field,
                      byte[] ids) {
            this.fused = fused;
            this.ext = ext;
            this.grids = grids;
            this.field = field;
            this.ids = ids;
        }
    }

    /**
     * A chunk's whole-chunk cell-corner lattice extent, derived from its
     * {@link NoiseChunk} EXACTLY as {@link ChunkGridCache#beginChunk} derives it
     * (same fields, same formulas) — so a stored grid lines up bit-for-bit with the
     * grid the per-chunk path would have produced.
     */
    public static final class Extent {
        public final int ox, oy, oz;       // origin block coords (first cell corner)
        public final int sx, sy, sz;       // strides (cellWidth, cellHeight, cellWidth)
        public final int dimX, dimY, dimZ; // dims (cellCountXZ+1, cellCountY+1, cellCountXZ+1)
        public final int gridLen;          // dimX*dimY*dimZ

        private Extent(int ox, int oy, int oz, int sx, int sy, int sz,
                       int dimX, int dimY, int dimZ, int gridLen) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.dimX = dimX;
            this.dimY = dimY;
            this.dimZ = dimZ;
            this.gridLen = gridLen;
        }

        /**
         * Derives the extent from {@code nc}'s sampler fields, mirroring
         * {@link ChunkGridCache#beginChunk}. Returns {@code null} if the fields are
         * not (yet) valid (then the prefetch is skipped and the per-chunk path runs).
         */
        public static Extent derive(NoiseChunk nc) {
            try {
                IChunkNoiseSampler s = (IChunkNoiseSampler) nc;
                int cellWidth = s.getHorizontalCellBlockCount();
                int cellHeight = s.getVerticalCellBlockCount();
                int cellCountXZ = s.getHorizontalCellCount();
                int cellCountY = s.getVerticalCellCount();
                int firstCellX = s.getFirstCellX();
                int firstCellZ = s.getFirstCellZ();
                int cellNoiseMinY = s.getMinimumCellY();
                if (cellWidth <= 0 || cellHeight <= 0 || cellCountXZ < 0 || cellCountY < 0) {
                    return null;
                }
                int dimX = cellCountXZ + 1;
                int dimY = cellCountY + 1;
                int dimZ = cellCountXZ + 1;
                long len = (long) dimX * dimY * dimZ;
                if (len <= 0 || len > Integer.MAX_VALUE) {
                    return null;
                }
                return new Extent(
                        firstCellX * cellWidth, cellNoiseMinY * cellHeight, firstCellZ * cellWidth,
                        cellWidth, cellHeight, cellWidth,
                        dimX, dimY, dimZ, (int) len);
            } catch (Throwable t) {
                return null;
            }
        }

        /**
         * Canonical whole-chunk extent for {@code pos} from a dimension's KNOWN lattice
         * geometry — the BIOMES-seam (pre-NoiseChunk) equivalent of {@link #derive}: the
         * horizontal origins use the same {@code firstCell = floorDiv(minBlock, cellWidth)}
         * formula vanilla's NoiseChunk applies, and every other field comes from the
         * caller's registered per-dimension geometry, so the key/extent are exactly what
         * {@link #derive} would later produce for a standard full-chunk sampler. A chunk
         * whose real sampler deviates (blending etc.) simply misses this entry and rides
         * the per-chunk path. Returns {@code null} on any invalid input.
         */
        public static Extent canonical(ChunkPos pos, int oy, int sx, int sy, int sz,
                                       int dimX, int dimY, int dimZ) {
            if (pos == null || sx <= 0 || sy <= 0 || sz <= 0 || dimX <= 1 || dimY <= 1 || dimZ <= 1) {
                return null;
            }
            long len = (long) dimX * dimY * dimZ;
            if (len <= 0 || len > Integer.MAX_VALUE) {
                return null;
            }
            int ox = Math.floorDiv(pos.getMinBlockX(), sx) * sx;
            int oz = Math.floorDiv(pos.getMinBlockZ(), sz) * sz;
            return new Extent(ox, oy, oz, sx, sy, sz, dimX, dimY, dimZ, (int) len);
        }

        /** True iff this extent matches {@code ctx}'s derived lattice exactly. */
        public boolean matches(int ox, int oy, int oz, int sx, int sy, int sz,
                               int dimX, int dimY, int dimZ) {
            return this.ox == ox && this.oy == oy && this.oz == oz
                    && this.sx == sx && this.sy == sy && this.sz == sz
                    && this.dimX == dimX && this.dimY == dimY && this.dimZ == dimZ;
        }
    }
}
