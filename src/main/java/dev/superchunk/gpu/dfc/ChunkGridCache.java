package dev.superchunk.gpu.dfc;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkNoiseSampler;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode;
import dev.superchunk.gpu.OpenCLBackend;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SuperChunk GPU — Stage 5: <b>dispatch coalescing</b> for the GPU density-fill.
 *
 * <p><b>The problem.</b> C2ME's interpolation loop ({@code NoiseChunk.fillSlice}
 * &rarr; {@code NoiseInterpolator.fillArray} &rarr; the GPU-attached
 * {@code SubCompiledDensityFunction}) calls the batch fill <b>one vertical
 * cell-corner column at a time</b> — exactly {@code cellCountY+1} samples (49 for
 * the default overworld) at a fixed {@code (cellX, cellZ)}. Over a chunk that is
 * roughly {@code (cellCountXZ+1)} X-slices &times; {@code (cellCountXZ+1)} Z-columns
 * &times; ~2 (slice0/slice1 swap) tiny dispatches <i>per interpolated density
 * function</i>. Profiling on Iris Xe showed ~79k dispatches of 49 work-items each
 * and 93.5% per-dispatch overhead vs only 6.5% real compute — the path was
 * overhead-bound, not compute-bound.
 *
 * <p><b>The fix (eager whole-chunk precompute + per-thread cache).</b> The first
 * time a given {@code (NoiseChunk, GpuDensityFunction)} pair is asked for any
 * column during a chunk's interpolation, we compute the <b>entire</b> cell-corner
 * grid {@code (cellCountXZ+1) &times; (cellCountY+1) &times; (cellCountXZ+1)} in
 * ONE GPU dispatch (the existing in-kernel lattice kernel — same points, same
 * math, just the whole grid), store it in a per-thread cache, and serve every
 * subsequent column from that cache by a plain CPU array-slice copy. That turns
 * ~hundreds of 49-item dispatches per (chunk, DF) into exactly ONE whole-grid
 * dispatch.
 *
 * <p><b>Bit-for-bit identical.</b> The whole-grid points are the strict superset
 * of the per-column points, computed by the very same kernel; a served column is
 * the exact subset the per-column path would have produced. So parity is preserved
 * (the {@code selftest.gpu_parity} gate stays unchanged).
 *
 * <p><b>Per-thread, chunk-scoped.</b> C2ME generates chunks on worker threads, so
 * the cache is a {@link ThreadLocal} — lock-free and correct. {@link #beginChunk}
 * (interpolation start) and {@link #endChunk} (interpolation stop), driven by a
 * {@code NoiseChunk} mixin, scope the cache to exactly one chunk's interpolation
 * pass on that thread. The cache key carries the {@code NoiseChunk} identity so a
 * stale grid can never serve the wrong chunk.
 *
 * <p><b>Safety.</b> If the grid extent can't be determined, the requested column
 * isn't a clean subset of the grid, or the whole-grid dispatch fails, we return
 * {@code false} and the caller uses the existing per-column GPU/CPU path. Never
 * hangs, never corrupts.
 */
public final class ChunkGridCache {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    /** Master enable for coalescing (defaults true; off restores the per-column path). */
    private static volatile boolean enabled = true;

    /**
     * (Audit round-4, flag {@code -Dsuperchunk.gpu.gridDetectCache}, default OFF) Reuse the
     * previous served column's detected lattice SHAPE (strides+dims) as a hint: every
     * cell-corner column in a chunk shares strides+dims, only the origin moves, so
     * re-anchoring the cached shape and re-verifying skips the O(n) lattice DERIVATION in
     * {@link LatticeCoords#detect} while keeping the O(n) verify (bit-identical serve).
     */
    private static final boolean GRID_DETECT_CACHE = Boolean.getBoolean("superchunk.gpu.gridDetectCache");

    /**
     * Master enable for LIVE DF fusion: when a fused-group member's whole-chunk grid
     * is first requested, compute the WHOLE fused group in ONE multi-output dispatch
     * (instead of one per-DF dispatch each). Off restores the per-DF whole-grid path.
     */
    private static volatile boolean fusionEnabled = false;

    /**
     * Master enable for ASYNC (non-blocking) GPU readback of the fused per-chunk
     * dispatch: eagerly enqueue the fused kernel + a non-blocking read at
     * {@link #beginChunk}, then wait on the CL event only at the first density use
     * ({@link #tryServe}) so intervening CPU work overlaps the GPU dispatch/readback.
     * Off restores today's blocking lazy path. Wired from {@code GpuConfig.asyncReadback()}.
     */
    private static volatile boolean asyncEnabled = false;

    /**
     * The most-roots fused group (realistically the main dimension's interpolated-grids
     * group). The cross-chunk BATCHER is built over this group ({@link #ensureBatcher})
     * and {@link #consumeBatchedGrid} serves only its deposits. NOTE (2026-07-02): the
     * EAGER async dispatch at {@link #beginChunk} no longer uses this global — it
     * resolves the chunk's OWN group from its lattice geometry ({@link #fusedForGeometry}),
     * so non-primary dimensions stop paying wasted wrong-group dispatches.
     */
    private static volatile GpuFusedInterpolator primaryFused = null;

    /**
     * Master enable for LIVE CROSS-CHUNK batching ({@code batchChunks}): wire the
     * {@link GpuChunkBatcher} into the noise status boundary so concurrently-generating
     * chunks' fused-group grids are computed in BATCHED dispatches with the worker freed
     * during the wait. Off restores the per-chunk fused/async path verbatim. Set from
     * {@code GpuConfig.batchChunks()} at init.
     */
    private static volatile boolean batchEnabled = false;

    /**
     * Whether SUB-full-chunk lattices (structure-probe column samplers: (dim-1)*stride != 16
     * on either horizontal axis) may arm the coalesced GPU path. Default TRUE = current
     * behavior. FALSE leaves those passes unarmed so their fills fall through
     * {@link #tryServe} to the delegate's fill-shape gate and run on the CPU bytecode path
     * — removing the per-probe worker-side dispatch + park. Wired from
     * {@code GpuConfig.subLatticeGpu()} at init.
     */
    private static volatile boolean subLatticeGpu = true;

    /** Guards building/replacing the live cross-chunk batcher. */
    private static final Object BATCHER_LOCK = new Object();

    /**
     * The live cross-chunk batcher (built lazily over {@link #primaryFused} once a
     * fused group is discovered AND {@code batchChunks} is on), or null. The noise
     * prefetch ({@link GpuBatchPrefetch}) submits to this; the consumer
     * ({@link #beginChunk}) serves the precomputed grids it deposited.
     */
    private static volatile GpuChunkBatcher liveBatcher = null;

    /** The exact fused group the {@link #liveBatcher} was compiled for (member k &harr; batch root k). */
    private static volatile GpuFusedInterpolator batcherFused = null;

    /** Bound the per-thread map; one entry per interpolated DF (~11), small. */
    private static final int MAX_ENTRIES = 64;

    /**
     * Maps each fused {@link GpuDensityFunction} member to its fused group + root index.
     * Identity-keyed (GpuDensityFunction uses default identity equals/hashCode), so a
     * {@link java.util.concurrent.ConcurrentHashMap} gives identity semantics AND
     * thread-safe reads on the gen hot path / writes at router-compile time. Populated
     * by {@link #registerFused}, cleared by {@link #shutdownFused}.
     */
    private static final Map<GpuDensityFunction, FusedMembership> FUSED_REGISTRY =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** All fused interpolators ever registered, for shutdown release. */
    private static final java.util.Set<GpuFusedInterpolator> FUSED_PROGRAMS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** A member's membership in a fused group: which fused program, and which root slot. */
    private record FusedMembership(GpuFusedInterpolator fused, int rootIndex) {
    }

    // ------------------------------------------------------------------
    // CROSS-DIMENSION FIX (2026-07-02): fused groups are discovered PER DIMENSION
    // (each dimension's router has distinct GpuDensityFunction members), but the
    // eager-async dispatch and the batch prefetch used to key everything off the ONE
    // global primaryFused. In any non-primary dimension that meant: beginChunk eagerly
    // dispatched the WRONG dimension's fused program over every chunk AND every
    // structure height-probe lattice, the pending could never be consumed
    // (pending.fused() != the chunk's own group), and endChunk BLOCKED on the wasted
    // readback in discard() — pure GPU + worker tax, invisible under green counters.
    // The batch leg was worse: wrong-group deposits were consumed under member
    // identities the chunk never looks up and counted as batch-serve WINS.
    //
    // Fix: a registry mapping a chunk's LATTICE GEOMETRY (the same pos-independent
    // fields GpuBatchStore.Key uses: oy + strides + dims) to the fused group PROVEN to
    // serve chunks of that geometry (recorded when a member of that group is actually
    // requested on that lattice, and at fusion-build time). The eager dispatch fires
    // the geometry's OWN group (so nether/end now get a consumable eager dispatch
    // instead of a guaranteed-discarded one), and the batch prefetch skips chunks whose
    // geometry doesn't belong to the batcher's group. Unknown geometry = today's lazy
    // blocking path (exactly the pre-discovery window). Where two dimensions genuinely
    // share a geometry, the most-roots group wins — reproducing the old primaryFused
    // behavior for the collision case, never worse than before.
    // ------------------------------------------------------------------

    /** A chunk lattice's pos-independent geometry (same field set as {@link GpuBatchStore.Key}). */
    private record GeomKey(int oy, int sx, int sy, int sz, int dimX, int dimY, int dimZ) {
    }

    /** Leak guard: distinct geometries are ~2 per dimension (full chunk + column probe). */
    private static final int MAX_GEOMETRIES = 64;

    /** Geometry -> the fused group proven to serve chunks of that geometry (most roots wins on collision). */
    private static final Map<GeomKey, GpuFusedInterpolator> GEOM_GROUPS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Records that a member of {@code fused} was requested on {@code ctx}'s lattice geometry. */
    private static void recordGeometry(GpuFusedInterpolator fused, Ctx ctx) {
        if (fused == null || !ctx.extentOk) {
            return;
        }
        recordGeometry(fused, new GeomKey(ctx.oy, ctx.sx, ctx.sy, ctx.sz, ctx.dimX, ctx.dimY, ctx.dimZ));
    }

    /** As above but keyed directly — the async fusion build passes the discovery chunk's snapshot. */
    private static void recordGeometry(GpuFusedInterpolator fused, GeomKey key) {
        if (fused == null || key == null) {
            return;
        }
        if (GEOM_GROUPS.size() >= MAX_GEOMETRIES && !GEOM_GROUPS.containsKey(key)) {
            return;   // pathological geometry churn — new geometries just keep the lazy path
        }
        GEOM_GROUPS.merge(key, fused, (prev, cur) -> cur.rootCount() > prev.rootCount() ? cur : prev);
    }

    /** The fused group registered for {@code ctx}'s lattice geometry, or {@code null} (lazy path). */
    private static GpuFusedInterpolator fusedForGeometry(Ctx ctx) {
        return GEOM_GROUPS.get(new GeomKey(ctx.oy, ctx.sx, ctx.sy, ctx.sz, ctx.dimX, ctx.dimY, ctx.dimZ));
    }

    /**
     * Whether a chunk lattice with this geometry belongs to the live batcher's fused group —
     * the batch prefetch's cross-dimension guard ({@link GpuBatchPrefetch#wrapNoise}). A
     * mismatching chunk (another dimension) must NOT be submitted: the batcher would run the
     * WRONG group's kernels over its lattice, the deposit would be consumed under member
     * identities the chunk never queries, and the waste would be counted as a batch serve.
     */
    static boolean geometryMatchesBatcher(int oy, int sx, int sy, int sz, int dimX, int dimY, int dimZ) {
        GpuFusedInterpolator bf = batcherFused;
        return bf != null && GEOM_GROUPS.get(new GeomKey(oy, sx, sy, sz, dimX, dimY, dimZ)) == bf;
    }

    // Cached inverse GEOM_GROUPS lookup for the batcher's fused group (climate-chain
    // combined prefetch — called per chunk at the biomes seam). Invalidation is by
    // fused-group identity: a rebuilt batcher simply misses and re-scans the tiny map.
    private static volatile GpuFusedInterpolator batcherGeomFor;
    private static volatile GeomKey batcherGeomKey;

    /**
     * The canonical whole-chunk cell-corner {@link GpuBatchStore.Extent} for {@code pos}
     * under the LIVE batcher's per-dimension lattice geometry, or {@code null} when no
     * batcher is live or its geometry hasn't been observed yet (GEOM_GROUPS is populated
     * by the first armed fill of that geometry — early chunks just ride the split path).
     * Used by the BIOMES-seam combined prefetch, where the chunk's {@code NoiseChunk}
     * does not exist yet.
     */
    static GpuBatchStore.Extent canonicalBatcherExtent(net.minecraft.world.level.ChunkPos pos) {
        GpuFusedInterpolator bf = batcherFused;
        if (bf == null || liveBatcher == null || pos == null) {
            return null;
        }
        GeomKey g = batcherGeomFor == bf ? batcherGeomKey : null;
        if (g == null) {
            for (Map.Entry<GeomKey, GpuFusedInterpolator> e : GEOM_GROUPS.entrySet()) {
                if (e.getValue() == bf) {
                    g = e.getKey();
                    batcherGeomKey = g;
                    batcherGeomFor = bf;
                    break;
                }
            }
            if (g == null) {
                return null;
            }
        }
        return GpuBatchStore.Extent.canonical(pos, g.oy(), g.sx(), g.sy(), g.sz(),
                g.dimX(), g.dimY(), g.dimZ());
    }

    private ChunkGridCache() {
    }

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    /** Toggles LIVE DF fusion (wired from {@code GpuConfig.fuseInterpolated()} at init). */
    public static void setFusionEnabled(boolean v) {
        fusionEnabled = v;
    }

    /** Toggles ASYNC (non-blocking) GPU readback (wired from {@code GpuConfig.asyncReadback()} at init). */
    public static void setAsyncEnabled(boolean v) {
        asyncEnabled = v;
    }

    /** Toggles LIVE cross-chunk batching (wired from {@code GpuConfig.batchChunks()} at init). */
    public static void setBatchEnabled(boolean v) {
        batchEnabled = v;
    }

    /** Toggles GPU arming of sub-full-chunk lattices (wired from {@code GpuConfig.subLatticeGpu()} at init). */
    public static void setSubLatticeGpu(boolean v) {
        subLatticeGpu = v;
    }

    public static boolean isBatchEnabled() {
        return batchEnabled;
    }

    /** The live cross-chunk batcher, or null when batching is off / not yet built. */
    public static GpuChunkBatcher liveBatcher() {
        return liveBatcher;
    }

    /** The fused group the live batcher was compiled for (its members map to batch roots). */
    public static GpuFusedInterpolator batcherFused() {
        return batcherFused;
    }

    /**
     * Builds (or rebuilds) the live cross-chunk {@link GpuChunkBatcher} over the primary
     * fused group's roots, so the noise prefetch can coalesce concurrent chunks into
     * batched dispatches. Member {@code k}'s AST becomes batch root {@code k}, so a
     * batched slice maps straight back onto {@code fused.members().get(k)}. No-op when
     * batching is off, no fused group exists, or the batcher is already current. Never
     * throws — on any failure the prefetch stays disabled and the per-chunk path runs.
     */
    private static void ensureBatcher(GpuFusedInterpolator fused) {
        if (!batchEnabled || fused == null) {
            return;
        }
        synchronized (BATCHER_LOCK) {
            if (liveBatcher != null && batcherFused == fused) {
                return; // already built for this exact group
            }
            java.util.List<GpuDensityFunction> members = fused.members();
            java.util.List<AstNode> roots = new ArrayList<>(members.size());
            for (GpuDensityFunction m : members) {
                AstNode ast = m == null ? null : m.sourceAst();
                if (ast == null) {
                    LOGGER.warn("[SuperChunk-GPU] [batch] a fused member has no source AST — cross-chunk batching unavailable.");
                    return;
                }
                roots.add(ast);
            }
            GpuBatchDispatcher disp;
            try {
                // The fused group rides along so the COMPACT-IDS decide chain (Stage 5,
                // -Dsuperchunk.gpu.compactIds=probe) can be chain-built for exactly these
                // member<->root indices. No-op unless the probe flag is on.
                disp = GpuBatchDispatcher.compile(roots, fused);
            } catch (Throwable t) {
                LOGGER.warn("[SuperChunk-GPU] [batch] batched dispatcher compile threw — cross-chunk batching unavailable.", t);
                return;
            }
            if (disp == null) {
                LOGGER.warn("[SuperChunk-GPU] [batch] batched dispatcher did not compile — cross-chunk batching unavailable.");
                return;
            }
            GpuChunkBatcher b;
            try {
                // init() shuts down + closes any prior SHARED batcher (different group).
                b = GpuChunkBatcher.init(disp);
            } catch (Throwable t) {
                LOGGER.warn("[SuperChunk-GPU] [batch] batcher init threw — cross-chunk batching unavailable.", t);
                disp.close();
                return;
            }
            if (b == null) {
                LOGGER.warn("[SuperChunk-GPU] [batch] batcher init failed — cross-chunk batching unavailable.");
                disp.close();
                return;
            }
            GpuBatchStore.clear(); // any grids from a prior (different-group) batcher are stale
            liveBatcher = b;
            batcherFused = fused;
            LOGGER.info("[SuperChunk-GPU] [batch] LIVE cross-chunk batching ENABLED: batcher built over the primary fused "
                            + "group (M={} roots, batchLimit={}, windowMicros={}). Concurrent chunks' density grids are now "
                            + "coalesced into BATCHED GPU dispatches with the worker freed during the wait.",
                    fused.rootCount(), b.batchLimit(), b.batchWindowMicros());
        }
    }

    /**
     * Registers a fused interpolator: maps every member {@code k} of {@code fused} to
     * {@code (fused, k)} so {@link #computeGrid} can compute the whole group in ONE
     * dispatch when any member's grid is first requested. Idempotent-ish (re-maps a
     * member to the latest fused group). Safe to call from the router-compile thread.
     */
    public static void registerFused(GpuFusedInterpolator fused) {
        if (fused == null) {
            return;
        }
        FUSED_PROGRAMS.add(fused);
        java.util.List<GpuDensityFunction> members = fused.members();
        for (int k = 0; k < members.size(); k++) {
            GpuDensityFunction m = members.get(k);
            if (m != null) {
                FUSED_REGISTRY.put(m, new FusedMembership(fused, k));
            }
        }
        // Pick the fused group with the most roots as the BATCHER's group (realistically
        // the main dimension's interpolated-grids group). Members of any other group get
        // their own geometry-resolved eager/blocking fused path (see fusedForGeometry);
        // only the cross-chunk batcher is bound to this single group.
        GpuFusedInterpolator pf = primaryFused;
        if (pf == null || fused.rootCount() > pf.rootCount()) {
            primaryFused = fused;
        }
        LOGGER.info("[SuperChunk-GPU] [fusion] registered fused interpolator (M={} whole-grid DFs -> 1 dispatch/chunk).",
                members.size());
        // LIVE cross-chunk batching: (re)build the batcher over the (possibly updated)
        // primary fused group so the noise prefetch can coalesce concurrent chunks.
        // No-op unless batchChunks is on; never throws.
        if (batchEnabled) {
            try {
                ensureBatcher(primaryFused);
            } catch (Throwable t) {
                LOGGER.warn("[SuperChunk-GPU] [batch] ensureBatcher threw — cross-chunk batching stays off.", t);
            }
        }
    }

    /**
     * Closes all registered fused interpolators and clears the fusion registry. Called
     * from {@code OpenCLBackend.shutdown()} so no {@code cl_program}/{@code cl_mem} leaks.
     * The interpolators' {@link GpuFusedInterpolator#close()} is idempotent, so the
     * resource-owner shutdown loop closing them again is a no-op.
     */
    public static void shutdownFused() {
        FUSED_REGISTRY.clear();
        GEOM_GROUPS.clear();
        primaryFused = null;
        // Cross-chunk batching: drop our refs + the grid store. The batcher instance
        // itself (== GpuChunkBatcher.SHARED) is stopped/closed by
        // GpuChunkBatcher.shutdownShared() later in OpenCLBackend.shutdown().
        liveBatcher = null;
        batcherFused = null;
        // NOTE: the batch store / field pool are intentionally NOT cleared here.
        // shutdownFused() runs BEFORE GpuChunkBatcher.shutdownShared() in
        // OpenCLBackend.shutdown(), so the batcher drainer thread can still be live
        // and land a whenComplete put() into GpuBatchStore.STORE after this point.
        // Clearing them here would leak those late grids/holders. The stores are
        // instead cleared by clearBatchStore(), called AFTER shutdownShared() joins
        // the drainer (see OpenCLBackend.shutdown()).
        for (GpuFusedInterpolator fused : FUSED_PROGRAMS) {
            try {
                fused.close();
            } catch (Throwable ignored) {
            }
        }
        FUSED_PROGRAMS.clear();
        SEEN_WHOLEGRID.clear();
    }

    /**
     * Drops the cross-chunk batch store and its pooled field buffers. Called from
     * {@code OpenCLBackend.shutdown()} AFTER {@link GpuChunkBatcher#shutdownShared()}
     * has joined the batcher drainer thread, so no late {@code whenComplete} put()
     * can race a stored grid/holder in after the clear (which would leak in the
     * static {@link GpuBatchStore} map). Split out of {@link #shutdownFused} for
     * exactly this ordering guarantee.
     */
    public static void clearBatchStore() {
        GpuBatchStore.clear();          // returns any stored prefetch holders to the pool
        OnDeviceFieldPool.clear();      // then drop the pooled field buffers for the GC
    }

    // ------------------------------------------------------------------
    // Per-thread, per-chunk state.
    // ------------------------------------------------------------------

    /** One precomputed whole-chunk grid for a single DF. */
    private static final class Grid {
        final double[] values;     // whole grid, lattice order (Y-outer, X-mid, Z-inner)
        final boolean valid;       // false => the whole-grid dispatch failed; don't retry
        Grid(double[] values, boolean valid) {
            this.values = values;
            this.valid = valid;
        }
    }

    /** Per-thread context: the chunk currently being interpolated + its grids. */
    private static final class Ctx {
        NoiseChunk chunk;
        // whole-chunk cell-corner lattice extent (block coords).
        int ox, oy, oz;            // origin block coords (first cell corner)
        int sx, sy, sz;            // strides (cellWidth, cellHeight, cellWidth)
        int dimX, dimY, dimZ;      // dims (cellCountXZ+1, cellCountY+1, cellCountXZ+1)
        int gridLen;               // dimX*dimY*dimZ
        boolean extentOk;          // grid extent successfully derived
        // GpuDensityFunction identity -> its precomputed grid.
        final Map<GpuDensityFunction, Grid> grids = new IdentityHashMap<>();
        // LIVE DF fusion discovery: distinct NON-fused whole-grid DFs reached this
        // chunk, in first-touch order. At endChunk, if >= 2, they are fused into ONE
        // multi-output program (built once). After fusion is registered these DFs route
        // to the fused path and stop being collected here.
        final java.util.List<GpuDensityFunction> chunkWholeGrid = new java.util.ArrayList<>();
        // ASYNC readback: the single in-flight eager fused dispatch for THIS chunk (the
        // CL read event + its pending host buffer), or null. Issued at beginChunk,
        // consumed (waited on) at the first tryServe of a fused member, discarded at
        // endChunk if the chunk was abandoned before any density use. One per thread.
        GpuFusedInterpolator.PendingFusedFill pendingAsync;
        // OPT#13: reusable per-thread [M][len] grid buffer for the batched/fused M-grid
        // fills (consumeBatchedGrid / computeFusedGroup / completeAsyncFused). Mirrors
        // BiomeClimateCache OPT#12's gridsBuf: persists ACROSS chunks on this thread (NOT
        // cleared in endChunk); re-grown only when (owner, M, len) changes. Every one of
        // these sites FULLY overwrites all M*len elements (copy-into for the batched path;
        // download/split for the fused paths) before any row is wrapped in a Grid, so no
        // stale value from a prior chunk — or from a failed async attempt earlier in THIS
        // chunk — can ever be served.
        double[][] gridsBuf;
        // OPT#13: the fused group whose grids currently back gridsBuf. UNLIKE OPT#12 (one
        // climate group per chunk), ctx.grids here is a MULTI-group map: a chunk can hold
        // live Grids from two disjoint fused groups at once. So we reallocate (never
        // overwrite) whenever the owner changes — the previous group's still-live Grids
        // keep their old array while the new group gets a fresh one, so no live Grid is
        // ever aliased across groups. The common single-group-per-chunk workload keeps the
        // same owner across chunks and thus reuses the buffer fully (the allocation win).
        GpuFusedInterpolator gridsBufOwner;
        // (round-4 gridDetectCache) last successfully-detected served-column lattice
        // shape, reused as a detect() hint. Persists across chunks (re-verified every
        // use, so a stale shape can never mis-serve). Null until the first serve.
        LatticeCoords lastCol;
    }

    private static final ThreadLocal<Ctx> CTX = ThreadLocal.withInitial(Ctx::new);

    /**
     * Returns this thread's reusable {@code [m][len]} grid buffer for fused group
     * {@code owner} (OPT#13; mirrors {@code BiomeClimateCache.ensureGrids}'s OPT#12
     * {@code gridsBuf}), re-growing it whenever {@code (owner, m, len)} changes. Callers
     * MUST fully overwrite all {@code m*len} elements before wrapping any row in a
     * {@link Grid}; the buffer is reused for the next chunk on this thread, so a partial
     * fill would leak a stale value. Reallocating on an owner change is what keeps a
     * multi-group chunk bit-exact: the prior group's live Grids retain their old backing
     * array instead of being overwritten by this group's fill.
     */
    private static double[][] ensureGridsBuf(Ctx ctx, GpuFusedInterpolator owner, int m, int len) {
        double[][] out = ctx.gridsBuf;
        if (out == null || ctx.gridsBufOwner != owner || out.length != m || out[0].length != len) {
            out = new double[m][len];
            ctx.gridsBuf = out;
            ctx.gridsBufOwner = owner;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Lifecycle (driven by the NoiseChunk mixin).
    // ------------------------------------------------------------------

    /**
     * Called at the start of a chunk's interpolation loop
     * ({@code NoiseChunk.initializeForFirstCellX}). Binds this thread's cache to
     * {@code chunk} and derives the whole-chunk cell-corner lattice extent. Any new
     * chunk evicts the previous chunk's grids (bounded memory).
     */
    public static void beginChunk(NoiseChunk chunk) {
        // STAGE 2 on-device interpolation: bind the per-thread GPU full-field context to
        // this chunk (no-op unless -Dsuperchunk.gpu.onDeviceInterp[.verify]). Done before
        // the `enabled` guard so begin/endChunk stay symmetric even if coalescing is off.
        OnDeviceInterp.beginChunk(chunk);
        if (!enabled) {
            return;
        }
        Ctx ctx = CTX.get();
        // New chunk (or re-entry) -> discard any stale in-flight async dispatch, then
        // drop the old grids and rebind.
        discardPending(ctx);
        ctx.grids.clear();
        ctx.chunkWholeGrid.clear();
        ctx.chunk = chunk;
        ctx.extentOk = false;
        try {
            IChunkNoiseSampler s = (IChunkNoiseSampler) chunk;
            int cellWidth = s.getHorizontalCellBlockCount();
            int cellHeight = s.getVerticalCellBlockCount();
            int cellCountXZ = s.getHorizontalCellCount();
            int cellCountY = s.getVerticalCellCount();
            int firstCellX = s.getFirstCellX();
            int firstCellZ = s.getFirstCellZ();
            int cellNoiseMinY = s.getMinimumCellY();
            if (cellWidth <= 0 || cellHeight <= 0 || cellCountXZ < 0 || cellCountY < 0) {
                return;
            }
            // Whole-chunk cell-CORNER grid, matching c2me's column coords:
            //   x = (firstCellX + ix) * cellWidth          ix in [0, cellCountXZ]
            //   y = (cellNoiseMinY + iy) * cellHeight       iy in [0, cellCountY]
            //   z = (firstCellZ + iz) * cellWidth          iz in [0, cellCountXZ]
            // Lattice order is Y-outer / X-mid / Z-inner (same as df_batch_lattice).
            ctx.ox = firstCellX * cellWidth;
            ctx.oy = cellNoiseMinY * cellHeight;
            ctx.oz = firstCellZ * cellWidth;
            ctx.sx = cellWidth;
            ctx.sy = cellHeight;
            ctx.sz = cellWidth;
            ctx.dimX = cellCountXZ + 1;
            ctx.dimY = cellCountY + 1;
            ctx.dimZ = cellCountXZ + 1;
            long len = (long) ctx.dimX * ctx.dimY * ctx.dimZ;
            if (len <= 0 || len > Integer.MAX_VALUE) {
                return;
            }
            ctx.gridLen = (int) len;
            ctx.extentOk = true;
            // SUB-LATTICE ROUTING (gpu.subLatticeGpu=false): a non-full-chunk lattice —
            // vanilla's 1-cell column sampler from getBaseHeight/iterateNoiseColumn probes
            // ((dim-1)*stride != 16 horizontally) — is left UNARMED so its fills miss
            // tryServe and take the delegate's CPU bytecode gate instead of paying a
            // worker-side fused dispatch + park per probe. Default TRUE keeps today's
            // behavior (probes ride the whole-grid GPU path).
            if (!subLatticeGpu
                    && !((ctx.dimX - 1) * ctx.sx == 16 && (ctx.dimZ - 1) * ctx.sz == 16)) {
                ctx.extentOk = false;
                GpuFillStats.recordSubLatticeCpuRouted();
                return;
            }
        } catch (Throwable t) {
            ctx.extentOk = false;
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[SuperChunk-GPU] coalescing: could not derive chunk grid extent — per-column path.", t);
            }
        }
        // LIVE CROSS-CHUNK BATCHING: if the noise-boundary prefetch already computed
        // this chunk's fused grids in a BATCHED dispatch, pre-populate the per-member
        // cache from them now — NO per-chunk dispatch, no eager async. Bit-identical to
        // the per-chunk fused path (same kernel math, same readback bytes). Falls
        // through to the eager async path when no batched grid is available.
        if (consumeBatchedGrid(ctx, chunk)) {
            return;
        }
        // Density twin of recordClimateBatchMiss: a FULL-CHUNK-shaped armed fill found no
        // consumable deposit while the batcher was live — this chunk now pays the per-chunk
        // dispatch path the batched design claims doesn't happen. Column probes and
        // sub-region samplers ((dim-1)*stride != 16) are excluded: nothing should have
        // prefetched those. Pure observability; the serve path is unchanged.
        if (batchEnabled && ctx.extentOk && liveBatcher != null
                && (ctx.dimX - 1) * ctx.sx == 16 && (ctx.dimZ - 1) * ctx.sz == 16) {
            GpuFillStats.recordBatchMiss();
        }
        // EAGER ASYNC DISPATCH: once the extent is derived AND a fused group exists,
        // enqueue the fused kernel + non-blocking readback NOW (do NOT wait). The
        // worker keeps running its between-begin-and-first-density CPU work while the
        // GPU dispatch/readback overlaps; tryServe waits on the event only when the
        // densities are first needed. Before a group is discovered, pendingAsync stays
        // null and the first chunk(s) take today's lazy blocking path.
        maybeDispatchAsync(ctx);
    }

    /**
     * If a cross-chunk batched grid exists for {@code chunk} (deposited by the noise
     * prefetch in {@link GpuBatchStore}), populate {@code ctx.grids} for every fused
     * member from it and return {@code true} — so {@link #tryServe} serves every column
     * from cache with NO GPU dispatch. Returns {@code false} (untouched) when batching
     * is off, no entry exists, the fused group has changed since the prefetch, or the
     * stored extent doesn't match this chunk — leaving the caller's normal path intact.
     * Never throws.
     */
    private static boolean consumeBatchedGrid(Ctx ctx, NoiseChunk chunk) {
        if (!batchEnabled || !ctx.extentOk || chunk == null) {
            return false;
        }
        try {
            // The full-chunk fill's origin is the chunk's min block coords, so its
            // ChunkPos is (floorDiv(ox,16), floorDiv(oz,16)). Sub-region samplers map to
            // some pos too but their dims won't match the stored full-chunk extent below.
            net.minecraft.world.level.ChunkPos pos =
                    new net.minecraft.world.level.ChunkPos(Math.floorDiv(ctx.ox, 16), Math.floorDiv(ctx.oz, 16));
            // Dimension-qualified key: ChunkPos + this chunk's per-dimension noise-lattice geometry
            // (same fields the prefetch keyed the deposit with), so we can only ever peek/take THIS
            // dimension's entry — a concurrently-generating other dimension at (x,z) has a different
            // key and cannot alias it.
            GpuBatchStore.Key key = GpuBatchStore.key(pos,
                    ctx.ox, ctx.oy, ctx.oz, ctx.sx, ctx.sy, ctx.sz, ctx.dimX, ctx.dimY, ctx.dimZ);
            GpuBatchStore.Stored sg = GpuBatchStore.get(key);   // PEEK (no remove yet)
            if (sg == null) {
                return false;
            }
            GpuFusedInterpolator fused = primaryFused;
            // Only serve when the stored grids belong to the CURRENT primary fused group
            // (and hence the current batcher) — otherwise member<->root mapping is stale.
            if (fused == null || sg.fused != fused || sg.fused != batcherFused) {
                return false;
            }
            GpuBatchStore.Extent ext = sg.ext;
            // The stored lattice must match exactly what this chunk derived — this is
            // what rejects sub-region samplers without consuming the full-chunk entry.
            if (ext == null
                    || !ext.matches(ctx.ox, ctx.oy, ctx.oz, ctx.sx, ctx.sy, ctx.sz, ctx.dimX, ctx.dimY, ctx.dimZ)) {
                return false;
            }
            int len = ctx.gridLen;
            int m = fused.rootCount();
            java.util.List<GpuDensityFunction> members = fused.members();
            if (sg.grids == null || sg.grids.length != (long) m * len) {
                return false;
            }
            // Match confirmed — atomically remove + TAKE holder ownership. A sub-region sampler
            // never reaches here: its extent mismatch returned false above WITHOUT removing, so the
            // real full-chunk fill can still consume the entry. take() (unlike remove()) does NOT
            // return the holder to the pool — ownership transfers to us here.
            GpuBatchStore.Stored taken = GpuBatchStore.take(key);
            if (taken == null) {
                return false;   // raced with an evict/cancel -> per-chunk path (nothing consumed)
            }
            OnDeviceFieldPool.Holder holder = taken.field;
            boolean holderServed = false;
            try {
                // OPT#13: copy each batched member grid INTO this thread's reused [M][len]
                // buffer instead of allocating M fresh double[len] per chunk. We still COPY
                // from taken.grids (never alias the batcher's flat buffer), so ownership /
                // reuse of taken.grids is unaffected. buf[k] for every non-null member is
                // fully overwritten by the arraycopy before it is wrapped in a Grid, and
                // ctx.grids is cleared at endChunk (dropping these Grids), so no stale
                // prior-chunk value can be read on the next chunk's serve — bit-identical.
                double[][] buf = ensureGridsBuf(ctx, fused, m, len);
                for (int k = 0; k < m; k++) {
                    GpuDensityFunction mem = members.get(k);
                    if (mem == null) {
                        continue;
                    }
                    double[] g = buf[k];
                    System.arraycopy(taken.grids, k * len, g, 0, len);
                    ctx.grids.put(mem, new Grid(g, true));
                }
                // STAGE 2 on-device interpolation (no-op unless the flag is on).
                if (OnDeviceInterp.ENABLED) {
                    if (holder != null && holder.fused == fused) {
                        // The full field was PRE-PRODUCED at prefetch (on the batcher thread) into a
                        // REUSED pooled holder — serve it READY with NO worker wait and NO per-chunk
                        // field allocation. OnDeviceInterp takes ownership and returns the holder to
                        // the pool at endChunk. Bit-identical to the per-chunk fallback below.
                        holderServed = true;
                        OnDeviceInterp.recordFieldServedReady();
                        OnDeviceInterp.setFieldReady(fused, holder);
                    } else {
                        OnDeviceInterp.recordFieldFallbackPerChunk();
                        // No pre-produced field (pool exhausted / production failed): upload the
                        // batched corners to this worker's device buffer and ENQUEUE the full field
                        // ASYNC from them (unchanged fallback — the worker waits lazily at first
                        // field use). Best-effort: on failure the chunk uses the vanilla CPU lerp.
                        try {
                            fused.provideFullFieldFromHostCorners(taken.grids,
                                    ctx.dimX, ctx.dimY, ctx.dimZ, ctx.sx, ctx.sy, ctx.sz);
                        } catch (Throwable t) {
                            if (LOGGER.isDebugEnabled()) {
                                LOGGER.debug("[SuperChunk-GPU] [on-device-interp] batched-corner field provide threw — CPU lerp this chunk.", t);
                            }
                        }
                    }
                }
                // Record ONLY the cross-chunk serve — the GPU launches for these grids were
                // the batcher's BATCHED dispatches (counted by GpuChunkBatcher.dispatchCount),
                // NOT a per-chunk fused dispatch, so we must not double-count them here.
                GpuFillStats.recordBatchServe();
                return true;
            } finally {
                // If the holder was NOT handed to OnDeviceInterp (flag off, null, or a group
                // mismatch), return it to the pool now so it can't leak.
                if (holder != null && !holderServed) {
                    OnDeviceFieldPool.release(holder);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [batch] consuming batched grid threw — per-chunk path for this chunk.", t);
            return false;
        }
    }

    /**
     * Eagerly enqueues THIS chunk geometry's fused-group whole-chunk dispatch with a
     * non-blocking readback for {@code ctx}'s lattice, storing the pending handle in
     * {@code ctx}. No-op (leaves the lazy blocking path) when async/fusion is off, the
     * extent isn't derived, no fused group is registered for this geometry yet, or the
     * dispatch fails.
     *
     * <p>CROSS-DIMENSION FIX (2026-07-02): the group is resolved from {@code ctx}'s
     * lattice geometry (see {@link #recordGeometry}), NOT the global {@link #primaryFused}.
     * The old primaryFused dispatch fired the WRONG dimension's fused program for every
     * non-primary-dimension chunk and probe — structurally unconsumable
     * ({@code pending.fused()} never matched the chunk's own group), then BLOCKED on the
     * wasted readback at endChunk's discard. Now a nether/end chunk eagerly dispatches
     * its OWN group (consumable, same math its blocking path would run), and an
     * unknown-geometry chunk simply takes the lazy blocking path exactly like the
     * pre-discovery window (at most the FIRST chunk of a (group, geometry) pair).
     */
    private static void maybeDispatchAsync(Ctx ctx) {
        if (!asyncEnabled || !fusionEnabled || !ctx.extentOk) {
            return;
        }
        GpuFusedInterpolator fused = fusedForGeometry(ctx);
        if (fused == null) {
            return;
        }
        try {
            ctx.pendingAsync = fused.dispatchAsync(
                    ctx.ox, ctx.oy, ctx.oz,
                    ctx.sx, ctx.sy, ctx.sz,
                    ctx.dimX, ctx.dimY, ctx.dimZ);   // may be null on failure -> lazy path
            if (ctx.pendingAsync != null) {
                GpuFillStats.recordAsyncIssued();
            }
        } catch (Throwable t) {
            ctx.pendingAsync = null;
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("[SuperChunk-GPU] [fusion] eager async dispatch threw — lazy blocking path for this chunk.", t);
            }
        }
    }

    /** Discards any in-flight async dispatch on {@code ctx} (waits for + releases it). Never throws. */
    private static void discardPending(Ctx ctx) {
        GpuFusedInterpolator.PendingFusedFill p = ctx.pendingAsync;
        if (p != null) {
            ctx.pendingAsync = null;
            GpuFillStats.recordAsyncDiscarded();
            try {
                p.discard();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Called at the end of a chunk's interpolation loop
     * ({@code NoiseChunk.stopInterpolation}). Drops this chunk's grids so the next
     * chunk on this thread starts clean.
     */
    public static void endChunk() {
        // STAGE 2 on-device interpolation: unbind this thread's GPU full-field context
        // (no-op unless the flag is on). Symmetric with beginChunk above.
        OnDeviceInterp.endChunk();
        if (!enabled) {
            return;
        }
        Ctx ctx = CTX.get();
        // ASYNC readback: if a pending eager dispatch was never consumed (chunk
        // abandoned before any density use), wait for its readback to finish and
        // release it — no orphan events, no leaked buffer, no torn reuse next chunk.
        discardPending(ctx);
        // LIVE DF fusion: once a chunk has exercised >= 2 distinct whole-grid DFs that
        // aren't yet fused, build ONE fused multi-output program over exactly that set
        // (built once; subsequent chunks route those DFs to the fused dispatch). The
        // compile runs on a background thread — maybeBuildFusion never blocks this
        // worker — and the build thread warms the geometry registry from the discovery
        // chunk's snapshot, so post-build chunks still get the eager async dispatch.
        if (fusionEnabled && ctx.chunkWholeGrid.size() >= 2) {
            maybeBuildFusion(ctx.chunkWholeGrid, ctx);
        }
        ctx.grids.clear();
        ctx.chunkWholeGrid.clear();
        ctx.chunk = null;
        ctx.extentOk = false;
    }

    // ------------------------------------------------------------------
    // Serve.
    // ------------------------------------------------------------------

    /**
     * Tries to serve a per-column fill from the whole-chunk grid (coalesced path).
     *
     * <p>Returns {@code true} iff {@code out} was fully populated from the cache
     * (the whole grid is computed once on first touch). Returns {@code false} —
     * with {@code out} untouched — when coalescing isn't applicable for this call,
     * so the caller falls back to the existing per-column GPU/CPU path.
     *
     * @param gpu the GPU density function (cache identity for this DF)
     * @param out destination densities (length n)
     * @param x   per-column X block coords (length >= n)
     * @param y   per-column Y block coords (length >= n)
     * @param z   per-column Z block coords (length >= n)
     * @param n   number of points in this column
     */
    public static boolean tryServe(GpuDensityFunction gpu, double[] out,
                                   int[] x, int[] y, int[] z, int n) {
        if (!enabled || n <= 0) {
            return false;
        }
        Ctx ctx = CTX.get();
        if (!ctx.extentOk || ctx.chunk == null) {
            return false;
        }

        // O(1) SHAPE PRE-CHECK (slow-gen fix 2026-07-02). A servable fill must have
        // EVERY point exactly on the armed chunk lattice and in range — that is what
        // offsetIndex/axisFits below guarantee for all n points of a served column.
        // So probe the LAST point (then the first) before paying the O(n)
        // LatticeCoords.detect + verify: the dominant live miss — the CacheAllInCell
        // per-cell block fill (n=128, stride 1 vs the 4/8/4 cell lattice; measured
        // 52.6M of 52.6M rejected fills, ~2% of all worker CPU in the 2026-07-02
        // diag JFRs) — is rejected here in a few int ops instead of ~3n coordinate
        // checks per fill. Strictly a NECESSARY condition of the serve path below:
        // a fill rejected here could never have been served, so serving (and every
        // served value) stays bit-identical.
        int last = n - 1;
        if (!onGrid(x[last], ctx.ox, ctx.sx, ctx.dimX)
                || !onGrid(y[last], ctx.oy, ctx.sy, ctx.dimY)
                || !onGrid(z[last], ctx.oz, ctx.sz, ctx.dimZ)
                || !onGrid(x[0], ctx.ox, ctx.sx, ctx.dimX)
                || !onGrid(y[0], ctx.oy, ctx.sy, ctx.dimY)
                || !onGrid(z[0], ctx.oz, ctx.sz, ctx.dimZ)) {
            return false;
        }

        // The requested column must be a clean lattice that fits inside the chunk
        // grid (same strides; origins offset by a whole number of strides).
        LatticeCoords col;
        LatticeCoords hint = ctx.lastCol;
        if (GRID_DETECT_CACHE && hint != null && hint.n() == n) {
            // Same column shape as the previous serve (the common case). Re-anchor the
            // cached shape to this column's origin and verify; skips the O(n) lattice
            // derivation, keeps the O(n) verify (so a shape mismatch cleanly falls back).
            LatticeCoords cand = LatticeCoords.of(x[0], y[0], z[0],
                    hint.sx, hint.sy, hint.sz, hint.dx, hint.dy, hint.dz);
            col = cand.matches(x, y, z, n) ? cand : LatticeCoords.detect(x, y, z, n);
        } else {
            col = LatticeCoords.detect(x, y, z, n);
        }
        if (col == null) {
            return false;
        }
        ctx.lastCol = col;
        // Compute the column's base index within the whole grid; verify every axis
        // is grid-aligned and in-range. If anything is off, bail to per-column path.
        int baseIx = offsetIndex(col.ox, ctx.ox, ctx.sx);
        int baseIy = offsetIndex(col.oy, ctx.oy, ctx.sy);
        int baseIz = offsetIndex(col.oz, ctx.oz, ctx.sz);
        if (baseIx < 0 || baseIy < 0 || baseIz < 0) {
            return false;
        }
        // Column dims and strides must be a sub-lattice of the chunk grid.
        if (!axisFits(col.dx, col.sx, ctx.sx, baseIx, ctx.dimX)
                || !axisFits(col.dy, col.sy, ctx.sy, baseIy, ctx.dimY)
                || !axisFits(col.dz, col.sz, ctx.sz, baseIz, ctx.dimZ)) {
            return false;
        }

        Grid grid = ctx.grids.get(gpu);
        if (grid == null) {
            grid = computeGrid(ctx, gpu);
            // Bound the per-thread map. Evict a SINGLE entry (not the whole chunk's
            // cache) so a rare 64th distinct DF can't trigger a re-dispatch storm for
            // every already-cached DF. Served values are unaffected either way.
            if (ctx.grids.size() >= MAX_ENTRIES) {
                java.util.Iterator<GpuDensityFunction> it = ctx.grids.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            ctx.grids.put(gpu, grid);
        }
        if (grid == null || !grid.valid) {
            return false;   // whole-grid dispatch failed -> per-column fallback
        }

        // Slice the requested column out of the whole grid.
        copyColumn(grid.values, out, n,
                baseIx, baseIy, baseIz,
                col.dx, col.dy, col.dz,
                col.sx / ctx.sx, col.sy / ctx.sy, col.sz / ctx.sz,
                ctx.dimX, ctx.dimZ);
        GpuFillStats.recordCoalescedServe(n);
        return true;
    }

    /** DIAGNOSTIC: distinct GPU functions ever seen reaching the whole-grid path. */
    private static final java.util.Set<GpuDensityFunction> SEEN_WHOLEGRID =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Computes the entire chunk grid for {@code gpu} in one GPU dispatch. */
    private static Grid computeGrid(Ctx ctx, GpuDensityFunction gpu) {
        // LIVE DF fusion: if gpu belongs to a fused group, compute the WHOLE group in
        // ONE multi-output dispatch and cache every member's grid in this ctx, so the
        // other members are later served from cache with NO additional dispatch.
        // Bit-identical to the per-DF whole-grid path (same kernel math). Any failure
        // falls through to it. If gpu is NOT yet fused, remember it for fusion discovery
        // at endChunk (the set of whole-grid DFs a chunk actually exercises).
        if (fusionEnabled) {
            FusedMembership fm = FUSED_REGISTRY.get(gpu);
            if (fm != null) {
                Grid g = computeFusedGroup(ctx, fm.fused(), gpu);
                if (g != null) {
                    return g;
                }
                // else: fused dispatch failed -> graceful per-DF fallback below.
            } else if (gpu.sourceAst() != null && !ctx.chunkWholeGrid.contains(gpu)) {
                if (SEEN_WHOLEGRID.add(gpu)) {
                    LOGGER.info("[SuperChunk-GPU] [fusion] discovered whole-grid DF #{} (not yet fused) on chunk gen.",
                            SEEN_WHOLEGRID.size());
                }
                ctx.chunkWholeGrid.add(gpu);
            }
        }
        int len = ctx.gridLen;
        try {
            double[] values = new double[len];
            boolean ok = gpu.fillWholeGrid(values,
                    ctx.ox, ctx.oy, ctx.oz,
                    ctx.sx, ctx.sy, ctx.sz,
                    ctx.dimX, ctx.dimY, ctx.dimZ);
            if (ok) {
                GpuFillStats.recordCoalescedGrid();
                return new Grid(values, true);
            }
            return new Grid(null, false);
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] coalescing: whole-grid dispatch threw — per-column fallback for this DF.", t);
            return new Grid(null, false);
        }
    }

    /**
     * Computes a whole fused group in ONE multi-output dispatch over {@code ctx}'s
     * cell-corner lattice, caches EVERY member's grid into {@code ctx.grids}, and
     * returns the {@code requested} member's grid. Records ONE fused dispatch (NOT a
     * per-DF coalesced grid for each member). Returns {@code null} on failure (the
     * caller then runs the per-DF whole-grid path for {@code requested}).
     */
    private static Grid computeFusedGroup(Ctx ctx, GpuFusedInterpolator fused, GpuDensityFunction requested) {
        // A member of `fused` was genuinely requested on this lattice geometry — record
        // the (geometry -> group) proof so beginChunk's eager dispatch / the batch
        // prefetch can target the RIGHT group for future chunks of this geometry.
        recordGeometry(fused, ctx);
        // DEFERRED WAIT: if there's an in-flight eager async dispatch for THIS fused
        // group, wait on its event ONLY NOW (any CPU work since beginChunk has already
        // overlapped the GPU dispatch/readback), populate every member's grid, and
        // serve. On any async failure we fall through to the blocking path below.
        GpuFusedInterpolator.PendingFusedFill pending = ctx.pendingAsync;
        if (pending != null && pending.fused() == fused) {
            ctx.pendingAsync = null;
            Grid g = completeAsyncFused(ctx, pending, fused, requested);
            if (g != null) {
                return g;
            }
            // async completion failed/discarded -> fresh blocking dispatch below.
        }
        int len = ctx.gridLen;
        int m = fused.rootCount();
        java.util.List<GpuDensityFunction> members = fused.members();
        // OPT#13: fill INTO this thread's reused [M][len] buffer instead of allocating
        // double[m][] + M fresh double[len] each chunk (the closest analog to
        // BiomeClimateCache OPT#12). fillAllGrids -> download() writes all M*len elements
        // on success (each row is already length len == n, so its sliceFor reuses the row
        // in place); we only wrap rows in Grids AFTER ok==true, so a failed/partial fill
        // stores nothing and the buffer is re-overwritten by the next fill before any read.
        // ctx.grids is cleared at endChunk. Bit-identical to the fresh-array path.
        double[][] out = ensureGridsBuf(ctx, fused, m, len);
        boolean ok;
        try {
            ok = fused.fillAllGrids(out,
                    ctx.ox, ctx.oy, ctx.oz,
                    ctx.sx, ctx.sy, ctx.sz,
                    ctx.dimX, ctx.dimY, ctx.dimZ);
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [fusion] fused dispatch threw — per-DF whole-grid fallback for this chunk.", t);
            return null;
        }
        if (!ok) {
            return null;
        }
        Grid requestedGrid = null;
        for (int k = 0; k < m; k++) {
            GpuDensityFunction mem = members.get(k);
            if (mem == null) {
                continue;
            }
            Grid g = new Grid(out[k], true);
            ctx.grids.put(mem, g);
            if (mem == requested) {
                requestedGrid = g;
            }
        }
        GpuFillStats.recordFusedDispatch(m);
        // If the requested DF somehow wasn't a member, fall back (shouldn't happen:
        // the registry only maps members to their own group).
        return requestedGrid;
    }

    /**
     * Completes an in-flight eager async fused dispatch: waits on its read event (only
     * now), splits the result into EVERY member's grid (cached in {@code ctx.grids}),
     * and returns the {@code requested} member's grid. Records the async hit/wait
     * instrumentation + the fused dispatch. Returns {@code null} on any failure (the
     * pending is always consumed/discarded first, draining the queue so the caller's
     * blocking re-dispatch is safe). Bit-identical to {@link #computeFusedGroup}'s
     * blocking path (same kernel, same readback bytes).
     */
    private static Grid completeAsyncFused(Ctx ctx, GpuFusedInterpolator.PendingFusedFill pending,
                                           GpuFusedInterpolator fused, GpuDensityFunction requested) {
        int len = ctx.gridLen;
        int m = fused.rootCount();
        // Sanity: the eager dispatch must have been issued for THIS chunk's grid extent.
        if (pending.n() != len) {
            pending.discard();
            return null;
        }
        // OPT#13: complete INTO this thread's reused [M][len] buffer instead of allocating
        // double[m][] + M fresh double[len] each chunk. This is the SAME reuse
        // BiomeClimateCache OPT#12 already relies on (its ensureGrids calls
        // pending.complete(gridsBuf)): complete() -> splitAsync/splitMapped writes all
        // M*len elements on ok==true (each row is length len == pending.n(), so sliceFor
        // reuses it in place). Grids are wrapped only after ok==true, so a failed
        // completion stores nothing and computeFusedGroup's blocking re-dispatch fully
        // overwrites this same buffer before any read. Bit-identical.
        double[][] out = ensureGridsBuf(ctx, fused, m, len);
        boolean ok;
        try {
            // completeRecordingAsyncServe folds the "already complete?" probe + the
            // GpuFillStats.recordAsyncServe stat into complete()'s single read-lock cycle,
            // so the async-hit metric no longer costs a separate isComplete() lock +
            // clGetEventInfo round-trip on this hot path.
            ok = pending.completeRecordingAsyncServe(out);
        } catch (Throwable t) {
            pending.discard();   // idempotent if complete() already consumed it
            LOGGER.warn("[SuperChunk-GPU] [fusion] async fused completion threw — blocking fallback for this chunk.", t);
            return null;
        }
        if (!ok) {
            return null;
        }
        java.util.List<GpuDensityFunction> members = fused.members();
        Grid requestedGrid = null;
        for (int k = 0; k < m; k++) {
            GpuDensityFunction mem = members.get(k);
            if (mem == null) {
                continue;
            }
            Grid g = new Grid(out[k], true);
            ctx.grids.put(mem, g);
            if (mem == requested) {
                requestedGrid = g;
            }
        }
        GpuFillStats.recordFusedDispatch(m);
        return requestedGrid;
    }

    // ------------------------------------------------------------------
    // LIVE DF fusion: runtime discovery + build.
    //
    // The set of DFs that get whole-grid (cell-corner-aligned) fills per chunk is a
    // RUNTIME property (which cache markers NoiseChunk instantiates + samples over the
    // cell grid). We observe exactly that set in computeGrid, then at endChunk build
    // ONE multi-output program over it (built once per distinct set). emitMulti shares
    // a single NoiseRegistry across all roots -> cross-DF CSE, so the fused dispatch is
    // typically MUCH cheaper than the sum of the per-DF dispatches it replaces.
    // ------------------------------------------------------------------

    /**
     * True while a fused build is running on the background thread. Workers that lose
     * the CAS just return — a later chunk re-triggers if its set is still unfused.
     *
     * <p>The build MUST NOT run on a c2me worker: the fused multi-output kernel is the
     * largest, coldest compile in the system (it cannot be disk-cached in advance — the
     * member set is a runtime discovery), and on regressed drivers (CUDA 13.2) a cold
     * {@code clBuildProgram} of it takes minutes. When it ran inline under a global
     * lock, every worker finishing a chunk queued behind it at {@code endChunk}, chunk
     * futures stalled, and the server thread (sync-waiting on the player's chunk during
     * fast flight) wedged — even world-leave couldn't proceed. Until the background
     * build lands, chunks keep using the per-DF whole-grid path, which is the existing
     * correctness fallback.
     */
    private static final AtomicBoolean FUSION_BUILD_INFLIGHT = new AtomicBoolean();

    /**
     * Kicks off ONE background build+register of a fused interpolator over
     * {@code wholeGridGpus} (the non-fused whole-grid DFs a chunk exercised), unless
     * this set was already fused or a build is already in flight. Never blocks the
     * calling worker; bit-identical to the per-DF whole-grid path. Never throws.
     */
    private static void maybeBuildFusion(List<GpuDensityFunction> wholeGridGpus, Ctx ctx) {
        // Cheap worker-side pre-checks only. If any member is already fused, this set
        // (or a superset) was already built by an earlier chunk/thread — skip.
        for (GpuDensityFunction g : wholeGridGpus) {
            if (FUSED_REGISTRY.containsKey(g)) {
                return;
            }
        }
        List<GpuDensityFunction> members = new ArrayList<>(wholeGridGpus);
        List<AstNode> asts = new ArrayList<>(members.size());
        for (GpuDensityFunction g : members) {
            AstNode ast = g.sourceAst();
            if (ast == null) {
                return;   // cannot fuse without ASTs
            }
            asts.add(ast);
        }
        if (!FUSION_BUILD_INFLIGHT.compareAndSet(false, true)) {
            return;
        }
        // Snapshot the discovery chunk's lattice geometry NOW (primitives only): ctx is
        // thread-local mutable state that endChunk clears right after this call, so the
        // build thread must not touch it. The snapshot preserves the old inline build's
        // "no one-chunk warmup gap" geometry warming.
        GeomKey discoveryGeom = ctx.extentOk
                ? new GeomKey(ctx.oy, ctx.sx, ctx.sy, ctx.sz, ctx.dimX, ctx.dimY, ctx.dimZ)
                : null;
        try {
            Thread builder = new Thread(() -> buildFusion(members, asts, discoveryGeom), "SuperChunk-GPU-fusion-build");
            builder.setDaemon(true);
            builder.start();
        } catch (Throwable t) {
            FUSION_BUILD_INFLIGHT.set(false);
            LOGGER.warn("[SuperChunk-GPU] [fusion] could not start background build thread — per-DF whole-grid path remains.", t);
        }
    }

    /** The background half of {@link #maybeBuildFusion}. Runs off the worker threads. */
    private static void buildFusion(List<GpuDensityFunction> members, List<AstNode> asts, GeomKey discoveryGeom) {
        try {
            if (!OpenCLBackend.isAvailable()) {
                return;   // world tearing down — don't compile into a dead backend
            }
            for (GpuDensityFunction g : members) {
                if (FUSED_REGISTRY.containsKey(g)) {
                    return;   // an earlier build already covered this set
                }
            }
            GpuFusedInterpolator fused;
            try {
                fused = GpuFusedInterpolator.compile(asts, members);
            } catch (Throwable t) {
                LOGGER.warn("[SuperChunk-GPU] [fusion] building fused interpolator threw — per-DF whole-grid path remains.", t);
                return;
            }
            if (fused != null) {
                // A shutdown racing the compile is safe: registration into a shutting-down
                // backend self-closes the owner (OpenCLBackend.registerResourceOwner).
                registerFused(fused);
                recordGeometry(fused, discoveryGeom);
                LOGGER.info("[SuperChunk-GPU] [fusion] LIVE DF fusion ENABLED: {} whole-grid DFs -> ONE multi-output "
                        + "dispatch/chunk (replaces {} per-DF whole-grid dispatches/chunk).", members.size(), members.size());
            } else {
                LOGGER.info("[SuperChunk-GPU] [fusion] fused program did not build for {} whole-grid DFs — keeping per-DF path.",
                        members.size());
            }
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [fusion] background fusion build failed — per-DF whole-grid path remains.", t);
        } finally {
            FUSION_BUILD_INFLIGHT.set(false);
        }
    }

    // ------------------------------------------------------------------
    // Index math.
    // ------------------------------------------------------------------

    /** Returns the lattice index along an axis for block-origin {@code o}, or -1 if not aligned. */
    /**
     * O(1) point-on-lattice test for the {@link #tryServe} pre-check: {@code true}
     * iff block coordinate {@code v} lies exactly on the armed chunk-grid axis
     * ({@code origin} + k*{@code stride}, 0 <= k < {@code dim}). Mirrors the
     * per-axis semantics of {@link #offsetIndex} (alignment, non-negative index)
     * plus the {@link #axisFits} range bound, applied to a single point. Every
     * point of a servable column satisfies this, so failing it anywhere proves
     * the fill could never be served.
     */
    private static boolean onGrid(int v, int origin, int stride, int dim) {
        int diff = v - origin;
        if (stride == 0) {
            return diff == 0;
        }
        if (diff % stride != 0) {
            return false;
        }
        int idx = diff / stride;
        return idx >= 0 && idx < dim;
    }

    private static int offsetIndex(int o, int origin, int stride) {
        if (stride == 0) {
            return o == origin ? 0 : -1;
        }
        int diff = o - origin;
        if (diff % stride != 0) {
            return -1;
        }
        int idx = diff / stride;
        return idx >= 0 ? idx : -1;
    }

    /**
     * Verifies a column axis is a sub-lattice of the grid axis: the column stride
     * is a whole multiple of the grid stride (or both degenerate), and the highest
     * sampled index stays within the grid dimension.
     */
    private static boolean axisFits(int colDim, int colStride, int gridStride, int baseIdx, int gridDim) {
        if (colDim <= 0) {
            return false;
        }
        if (colDim == 1) {
            return baseIdx < gridDim;        // single sample; stride irrelevant
        }
        if (gridStride == 0 || colStride == 0) {
            return false;                    // column advances but grid axis is degenerate
        }
        if (colStride % gridStride != 0) {
            return false;                    // not a sub-lattice
        }
        int step = colStride / gridStride;
        long maxIdx = (long) baseIdx + (long) (colDim - 1) * step;
        return maxIdx < gridDim;
    }

    /**
     * Copies the requested column out of the whole grid into {@code out}.
     * The whole grid is stored Y-outer / X-mid / Z-inner with dims
     * {@code (dimY, dimX, dimZ)}; flat index = {@code (iy*dimX + ix)*dimZ + iz}.
     * The column is iterated in its own lattice order (also Y-outer/X-mid/Z-inner),
     * mapped onto grid indices via base + per-axis step.
     */
    private static void copyColumn(double[] grid, double[] out, int n,
                                   int baseIx, int baseIy, int baseIz,
                                   int colDx, int colDy, int colDz,
                                   int stepX, int stepY, int stepZ,
                                   int gridDimX, int gridDimZ) {
        int i = 0;
        for (int cy = 0; cy < colDy; cy++) {
            int iy = baseIy + cy * stepY;
            int rowY = iy * gridDimX;
            for (int cx = 0; cx < colDx; cx++) {
                int ix = baseIx + cx * stepX;
                int planeXZ = (rowY + ix) * gridDimZ;
                for (int cz = 0; cz < colDz; cz++) {
                    int iz = baseIz + cz * stepZ;
                    out[i++] = grid[planeXZ + iz];
                    if (i >= n) {
                        return;
                    }
                }
            }
        }
    }
}
