package dev.superchunk.gpu.dfc;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkNoiseSampler;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IOnDeviceInterpCache;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.SubCompiledDensityFunction;
import dev.superchunk.gpu.OpenCLBackend;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.slf4j.Logger;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * SuperChunk GPU — <b>STAGE 2: on-device full-field interpolation residency</b>
 * (flag {@code -Dsuperchunk.gpu.onDeviceInterp}, default OFF; verify variant
 * {@code -Dsuperchunk.gpu.onDeviceInterp.verify}).
 *
 * <p>Stage 1 left the per-chunk COARSE cell-corner grid device-resident and
 * dispatched {@code df_full_field_interp} ({@code fullfield.cl}) to interpolate the
 * FULL {@code 16x384x16 = 98304}-point block field per density grid on the GPU —
 * but it <b>discarded</b> the result (timing only). Stage 2 actually USES that field:
 * {@link GpuFusedInterpolator#maybeDispatchFullFieldAsync} keeps the readback in a per-thread,
 * device-pinned host buffer and registers it here (waited lazily at first use), scoped to the
 * chunk currently
 * being interpolated on this worker thread. The vanilla/C2ME {@code NoiseChunk} fill
 * then reads the per-block interpolated value FROM that GPU field instead of running
 * {@code NoiseInterpolator.updateForX/Y/Z + Mth.lerp} on the CPU.
 *
 * <p><b>Which consumption path.</b> C2ME consumes an interpolated density-function in
 * two distinct ways during a chunk fill, and they differ at the bit level because
 * floating-point lerp is non-associative:
 * <ul>
 *   <li><b>{@code this.value} (Y&rarr;X&rarr;Z order)</b> — produced by
 *       {@code updateForY}/{@code updateForX}/{@code updateForZ} (lerp Y first, then X,
 *       then Z) and read whenever an interpolator is sampled DIRECTLY during block
 *       iteration ({@code fillingCell == false}): the aquifer substance DFs, the ore-vein
 *       DFs, etc. This is the path {@code fullfield.cl} mirrors exactly.</li>
 *   <li><b>{@code Mth.lerp3} (X&rarr;Y&rarr;Z order)</b> — produced per block during the
 *       per-cell {@code CacheAllInCell} fill ({@code fillingCell == true}); this is how the
 *       dominant {@code finalDensity} terrain shape reaches block placement (read back out of
 *       the cell cache). Its lerp order is X&rarr;Y&rarr;Z, so a Y&rarr;X&rarr;Z field would
 *       NOT be bit-identical to it.</li>
 * </ul>
 * Stage 2 serves BOTH paths from the GPU, each from its own full field computed off the
 * SAME device-resident cell-corner buffer but with the matching lerp order:
 * <ul>
 *   <li>the {@code this.value} (Y&rarr;X&rarr;Z) field ({@code df_full_field_interp}),
 *       consumed at the TAIL of {@code updateForZ} via {@code MixinNoiseInterpolatorOnDeviceInterp}
 *       and read here by {@link #sample};</li>
 *   <li>the {@code Mth.lerp3} (X&rarr;Y&rarr;Z) cell-cache field ({@code df_full_field_interp_lerp3}),
 *       consumed at the cell-cache fill sites in {@code MixinChunkNoiseSamplerDensityInterpolator}
 *       and read here by {@link #sampleLerp3}.</li>
 * </ul>
 * Both are bit-exact to their respective vanilla CPU lerp at fp64.
 *
 * <p><b>Per-root mapping.</b> The GPU field has M roots = {@code fused.rootCount()};
 * root {@code k} is the interpolated field of the fused member {@code members.get(k)}
 * (a {@link GpuDensityFunction}). A {@link NoiseChunk.NoiseInterpolator} maps to root
 * {@code k} iff its {@code noiseFiller} (the wrapped DF) is a
 * {@link SubCompiledDensityFunction} whose attached {@link GpuDensityFunction} is that
 * member — i.e. exactly the DF whose cell-corner grid {@link ChunkGridCache} served into
 * the interpolator's slices. Interpolators whose filler is not a fused member (CPU-only,
 * or a different fused group) are NOT served and fall back to the vanilla CPU lerp.
 *
 * <p><b>Verify mode</b> ({@code -Dsuperchunk.gpu.onDeviceInterp.verify}): computes BOTH
 * the GPU field value and the vanilla CPU {@code this.value}, compares per block (counts
 * non-bit-identical mismatches as BUGS, tracks {@code maxAbsErr}), and RETURNS THE VANILLA
 * value so terrain stays correct while parity is proven. At fp64 (the RTX 3070 default)
 * expect {@code BUGS=0}. This is the gate before trusting the fast path.
 *
 * <p><b>Threading / lifecycle.</b> Worldgen runs one chunk at a time per worker thread,
 * so the field context is a {@link ThreadLocal}; {@link #beginChunk}/{@link #endChunk}
 * (driven from {@link ChunkGridCache}) scope it to one chunk's interpolation pass. The
 * host buffer is the per-thread, reused {@code GpuFusedInterpolator.PerThread} pinned
 * staging — read directly (no copy) on the SAME worker thread that produced it, between
 * {@link #setField} and {@link #endChunk}. When the flag is OFF, every method is a
 * constant-folded no-op (zero behaviour change).
 */
public final class OnDeviceInterp {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    /**
     * Parity-verify mode: compute both, compare, return VANILLA. Implies {@link #ENABLED}.
     * ALSO forced on by the Stage-3 block-id census ({@code -Dsuperchunk.gpu.blockIdVerify}):
     * the census's mode-C construction needs the GPU full field produced AND bit-compared
     * live against the CPU lerp per served block — that comparison (BUGS=0) is the census's
     * proof that the density it feeds the decision kernel IS the GPU's own finalDensity.
     * VERIFY always returns the vanilla value, so terrain still ships unchanged.
     */
    public static final boolean VERIFY = Boolean.getBoolean("superchunk.gpu.onDeviceInterp.verify")
            || Boolean.getBoolean("superchunk.gpu.blockIdVerify");
    /** Master switch (verify implies enabled). When false, all hooks constant-fold away. */
    public static final boolean ENABLED = Boolean.getBoolean("superchunk.gpu.onDeviceInterp") || VERIFY;

    /** DIAGNOSTIC ONLY (default false): in the drainer's field production, run + drain the field
     * kernels but SKIP the readback DMA and the pinned-&gt;array host copy — the holder keeps STALE
     * data (garbage terrain; timing only, NOT parity-valid). Used to measure the MAXIMUM cps that a
     * compressed/eliminated field readback could ever recover (the readback-compression ceiling). */
    public static final boolean SKIP_READBACK = Boolean.getBoolean("superchunk.gpu.onDeviceInterp.skipReadback");

    /** DIAGNOSTIC ONLY (default false): sample()/sampleLerp3() return a constant WITHOUT reading the
     * field array — isolates the per-block CONSUME (memory-read) cost. Garbage terrain, timing only. */
    public static final boolean SKIP_CONSUME = Boolean.getBoolean("superchunk.gpu.onDeviceInterp.skipConsume");

    /** DIAGNOSTIC ONLY (default false): the drainer skips ALL field production (corner upload + field
     * kernels + readback); the holder keeps STALE data — isolates the GPU PRODUCTION cost (the drainer
     * pipeline). Garbage terrain, timing only. */
    public static final boolean SKIP_PRODUCTION = Boolean.getBoolean("superchunk.gpu.onDeviceInterp.skipProduction");

    // ---- verify accounting (additive; only moves in VERIFY mode) ----
    private static final LongAdder comparisons = new LongAdder();
    private static final LongAdder bugs = new LongAdder();
    private static final AtomicLong mismatchLogged = new AtomicLong();
    private static volatile double maxAbsErr = 0.0;   // racy read/write — diagnostic only
    private static volatile boolean firstServeLogged = false;
    /** Served-block count (summary only) — added-to ONCE PER CHUNK from {@code FieldCtx.fastServed};
     * the per-block hot path uses a thread-local long (never this shared adder). */
    private static final LongAdder served = new LongAdder();
    // ---- lerp3 (X->Y->Z cell-cache) accounting — SEPARATE comparison/served
    // counters (so the new path is clearly labelled in the summary), but the SAME
    // bugs/maxAbsErr accounting as the value path (one parity gate). ----
    private static final LongAdder lerp3Comparisons = new LongAdder();
    private static final LongAdder lerp3Served = new LongAdder();
    private static volatile boolean firstServeLerp3Logged = false;
    // ---- prefetch-pool health (the silent-degrade fraction of the flagship path) ----
    // fieldsServedReady      — chunks whose full field was PRE-PRODUCED at prefetch and
    //                          served READY at consume (the designed steady state).
    // fieldsFallbackPerChunk — chunks that fell back to per-chunk field production at
    //                          consume (no/failed pre-produced field) — correct but
    //                          strictly slower; previously measured NOWHERE, so an
    //                          under-provisioned pool silently halved the benefit.
    // poolExhausted          — OnDeviceFieldPool.acquire() refusals (capacity bound / OOM).
    // productionFailed       — produceFullFieldToArrays failures at prefetch.
    // All recorded at most once per chunk (batcher thread / consume), never per block.
    private static final LongAdder fieldsServedReady = new LongAdder();
    private static final LongAdder fieldsFallbackPerChunk = new LongAdder();
    private static final LongAdder poolExhausted = new LongAdder();
    private static final LongAdder productionFailed = new LongAdder();
    private static volatile boolean poolExhaustWarned = false;

    /**
     * Monotonically-unique field GENERATION stamp source. Bumped once per field install
     * ({@link #setField}/{@link #setFieldReady}) and once per field teardown
     * ({@code FieldCtx.clearField}). Each {@code FieldCtx.generation} takes a value from here, so
     * a generation value uniquely identifies ONE field installation on ONE thread at ONE instant.
     * The per-interpolator {@link IOnDeviceInterpCache} caches the generation its root/base were
     * resolved for; the hoisted fast path trusts the cache only while the cached generation still
     * equals the context's current generation, so a reused interpolator can never serve a stale
     * root/base/array from a prior chunk (see {@link #resolveCtx}).
     */
    private static final AtomicLong FIELD_GEN = new AtomicLong();

    private static long nextGen() {
        return FIELD_GEN.incrementAndGet();
    }

    /** Records one chunk served a READY pre-produced field (prefetch-pool steady state). */
    static void recordFieldServedReady() {
        fieldsServedReady.increment();
    }

    /** Records one chunk that fell back to per-chunk field production at consume. */
    static void recordFieldFallbackPerChunk() {
        fieldsFallbackPerChunk.increment();
    }

    /** Records one {@link OnDeviceFieldPool#acquire} refusal (capacity bound hit, or OOM sizing). */
    static void recordPoolExhausted() {
        poolExhausted.increment();
    }

    /** Records one failed full-field production at prefetch (consumer will fall back per-chunk). */
    static void recordFieldProductionFailed() {
        productionFailed.increment();
    }

    private OnDeviceInterp() {
    }

    /** One worker thread's active full field, scoped to the chunk being interpolated. */
    private static final class FieldCtx {
        NoiseChunk chunk;          // the chunk whose interpolation pass is active
        boolean hasField;          // a field has been registered for `chunk`
        boolean fp32;
        FloatBuffer hostF;         // pinned host view (fp32) — Y->X->Z field, absolute get(idx)
        DoubleBuffer hostD;        // pinned host view (fp64) — Y->X->Z field, absolute get(idx)
        // X->Y->Z (Mth.lerp3 cell-cache) field — SAME corner buffer, SAME layout/roots,
        // different lerp order. Null when the lerp3 kernel didn't build/run this chunk.
        FloatBuffer hostFLerp3;
        DoubleBuffer hostDLerp3;
        // READY (pre-produced, POOLED) full-field arrays — non-null iff this chunk was served
        // from the prefetch pool ({@link #setFieldReady}: fieldReady=true, pending=null, no wait).
        // sample()/sampleLerp3() read these DIRECTLY (no fp32 branch — fp32 was widened to double
        // at production). Mutually exclusive with the pinned hostF/hostD path above; when valueArr
        // is set, the hostF/D fields are null. valueArr present + lerp3Arr null => no lerp3 field.
        double[] valueArr;
        double[] lerp3Arr;
        // The pooled holder backing valueArr/lerp3Arr. Held for the WHOLE chunk fill (the field is
        // read throughout block iteration) and returned to the pool at endChunk/discard/re-provision
        // — NEVER released while this chunk is still reading it, so its buffers can't be re-acquired
        // and overwritten by another chunk's production mid-fill.
        OnDeviceFieldPool.Holder heldHolder;
        int fullX, fullY, fullZ, fullN;
        // Cached chunk field origin (invariant for the whole chunk pass: the field origin is
        // the chunk min block coords, fixed between setField and endChunk). Cached here so the
        // per-block serve methods don't re-cast to IChunkNoiseSampler and recompute
        // originX/Y/Z (= firstCell*cellBlocks) on every one of ~98304 blocks/chunk.
        int originX, originY, originZ;
        // member GpuDensityFunction -> root index k (identity), for the active fused group.
        Map<GpuDensityFunction, Integer> rootOf;
        // The fused group backing this chunk's field. Held so the pinned-staging path can
        // re-acquire fused.fillLock (re-checking closed) to copy the pinned field into a heap
        // array before any per-block read — closing the shutdown use-after-free (see
        // materializePinnedField). Set by bindGroup (both install paths); irrelevant on the
        // pooled path (which never reads pinned staging).
        GpuFusedInterpolator fused;
        // Field GENERATION stamp (unique per install/teardown; see FIELD_GEN). VOLATILE because a
        // migrated interpolator (its owning NoiseChunk reassigned to a different worker) may read
        // this thread's stamp cross-thread when validating its cached resolution; a volatile long
        // read is atomic (never torn) and, combined with global uniqueness, guarantees the stale
        // cache can never accidentally match. Only ever WRITTEN by this ctx's owning thread.
        volatile long generation;
        // ASYNC field readback: the in-flight full-field dispatch for `chunk` (value + lerp3
        // read events), or null once waited. The first field consumption waits on it
        // (ensureFieldReady); an unconsumed pending is discarded at endChunk/re-provision.
        GpuFusedInterpolator.PendingFullField pendingField;
        // True once the field readbacks have been waited on (the pinned staging is valid to
        // read). Set by ensureFieldReady; reset by clearField.
        boolean fieldReady;
        // Per-thread serve tallies, incremented on the per-block hot path (sample/sampleLerp3)
        // with a PLAIN long (no atomics), then flushed to the global LongAdders once per chunk in
        // discardField. A contended per-block LongAdder increment across all workers measured ~9%
        // of CPU under 12 workers (profiled); a thread-local long is free.
        long fastServed;
        long fastLerp3Served;

        void clearField() {
            // Bump FIRST: any field teardown invalidates every per-interpolator cache stamped with
            // the OLD generation, so a cached root/base can never be served against the cleared
            // (or about-to-be-nulled) arrays below.
            generation = nextGen();
            hasField = false;
            fieldReady = false;
            pendingField = null;
            hostF = null;
            hostD = null;
            hostFLerp3 = null;
            hostDLerp3 = null;
            valueArr = null;
            lerp3Arr = null;
            // Drop the ref only — the holder is RELEASED by releasePriorField (always called
            // before clearField). Standalone clearField (ensureFieldReady wait-failure) only runs
            // on the pinned path, where heldHolder is already null, so nothing is leaked here.
            heldHolder = null;
            rootOf = null;
            fused = null;
        }
    }

    /**
     * Tears down any prior field state before a new field is installed (or the ctx is cleared):
     * drains any unconsumed in-flight readback (releasing its CL events) AND returns any held
     * pooled holder to {@link OnDeviceFieldPool} (so a prefetched chunk's field buffer is reusable
     * once its fill is done / superseded). Both references are nulled so nothing is double-freed.
     */
    private static void releasePriorField(FieldCtx c) {
        GpuFusedInterpolator.PendingFullField p = c.pendingField;
        if (p != null) {
            c.pendingField = null;
            try {
                p.discard();
            } catch (Throwable ignored) {
            }
        }
        OnDeviceFieldPool.Holder h = c.heldHolder;
        if (h != null) {
            c.heldHolder = null;
            OnDeviceFieldPool.release(h);
        }
    }

    /** Discards any unconsumed in-flight field / held pooled holder on {@code c}, then clears. */
    private static void discardField(FieldCtx c) {
        flushServeTallies(c);
        releasePriorField(c);
        c.clearField();
    }

    /**
     * Flushes this thread's per-chunk serve tallies to the global {@link #served}/{@link #lerp3Served}
     * LongAdders — called once per chunk (at each begin/end boundary via {@link #discardField}), so the
     * per-block hot path never touches a shared atomic. Idempotent (resets the locals to 0).
     */
    private static void flushServeTallies(FieldCtx c) {
        if (c.fastServed != 0L) {
            served.add(c.fastServed);
            c.fastServed = 0L;
        }
        if (c.fastLerp3Served != 0L) {
            lerp3Served.add(c.fastLerp3Served);
            c.fastLerp3Served = 0L;
        }
    }

    /**
     * Caches the chunk field origin (invariant this chunk) and builds the member-&gt;root map for
     * {@code fused} (shared by both {@link #setField} and {@link #setFieldReady}). The per-chunk
     * generation bump (done by the callers) is what forces every interpolator to re-resolve its
     * root/base against the new field/group.
     */
    private static void bindGroup(FieldCtx c, GpuFusedInterpolator fused) {
        IChunkNoiseSampler s = (IChunkNoiseSampler) c.chunk;
        int cw = s.getHorizontalCellBlockCount();
        int ch = s.getVerticalCellBlockCount();
        c.originX = s.getFirstCellX() * cw;
        c.originY = s.getMinimumCellY() * ch;
        c.originZ = s.getFirstCellZ() * cw;
        // HOISTED: the member->root map depends only on the immutable fused.members(), so
        // build/cache it ONCE per interpolator (GpuFusedInterpolator.rootIndexMap()) and
        // reuse the shared read-only map across chunks instead of rebuilding M entries
        // every chunk. rootOf is only ever read (identity .get lookups) here, so sharing
        // one immutable IdentityHashMap is bit-neutral.
        c.rootOf = fused.rootIndexMap();
        // Remember the fused group so the pinned-staging UAF guard (materializePinnedField) can
        // re-acquire its fillLock + re-check its closed flag before copying the pinned field.
        c.fused = fused;
    }

    private static final ThreadLocal<FieldCtx> CTX = ThreadLocal.withInitial(FieldCtx::new);

    // ------------------------------------------------------------------
    // Lifecycle (driven by ChunkGridCache.beginChunk / endChunk).
    // ------------------------------------------------------------------

    /** Binds this thread's field context to {@code chunk}; drains + drops any prior field. */
    public static void beginChunk(NoiseChunk chunk) {
        if (!ENABLED) {
            return;
        }
        FieldCtx c = CTX.get();
        discardField(c);   // drain any stale in-flight field from a prior (re-entered) chunk
        c.chunk = chunk;
    }

    /** Unbinds this thread's field context (next chunk starts clean); drains any unconsumed field. */
    public static void endChunk() {
        if (!ENABLED) {
            return;
        }
        FieldCtx c = CTX.get();
        discardField(c);   // chunk abandoned before any field use -> drain the in-flight readback
        c.chunk = null;
    }

    /**
     * Registers the GPU full field for the CURRENT chunk on this thread, computed by
     * {@link GpuFusedInterpolator#maybeDispatchFullFieldAsync} from {@code fused}'s device-resident
     * cell-corner grids. {@code hostF}/{@code hostD} is the per-thread pinned staging the
     * field was read into (absolute-indexed {@code root*fullN + (by*fullX+bx)*fullZ+bz}).
     * {@code hostFLerp3}/{@code hostDLerp3} is the matching X&rarr;Y&rarr;Z (Mth.lerp3 cell-cache)
     * field staging, or {@code null} when the lerp3 kernel didn't run this chunk (then
     * {@link #sampleLerp3} returns NaN and the cell-cache fill stays on the CPU lerp).
     * {@code pending} carries the in-flight field read events; the FIRST consumption waits on
     * them ({@link #ensureFieldReady}) so the field round-trip overlaps the chunk's CPU work.
     * No-op if no chunk is bound (defensive). Any prior un-waited field on this thread is
     * DRAINED first (so a re-provision after an async-corner fallback can't tear the pinned
     * staging or leak its events). Clears the interpolator&rarr;root cache so the new
     * field/group is resolved fresh.
     */
    static void setField(GpuFusedInterpolator fused, boolean fp32,
                         FloatBuffer hostF, DoubleBuffer hostD,
                         FloatBuffer hostFLerp3, DoubleBuffer hostDLerp3,
                         int fullX, int fullY, int fullZ, int fullN,
                         GpuFusedInterpolator.PendingFullField pending) {
        FieldCtx c = CTX.get();
        if (c.chunk == null) {
            // No chunk bound — abandon the just-issued dispatch (drain its reads, release events).
            if (pending != null) {
                try {
                    pending.discard();
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        // Bump the generation BEFORE tearing down / installing: every per-interpolator cache
        // stamped with the prior generation is now invalid, so none can serve against the old
        // (about-to-be-released) field or the new arrays until it re-resolves.
        c.generation = nextGen();
        // Drain any prior un-waited field / held pooled holder on this thread before installing.
        releasePriorField(c);
        c.fp32 = fp32;
        c.hostF = hostF;
        c.hostD = hostD;
        c.hostFLerp3 = hostFLerp3;
        c.hostDLerp3 = hostDLerp3;
        c.valueArr = null;      // pinned-staging path — not the pooled-array path
        c.lerp3Arr = null;
        c.fullX = fullX;
        c.fullY = fullY;
        c.fullZ = fullZ;
        c.fullN = fullN;
        c.pendingField = pending;
        c.fieldReady = (pending == null);
        bindGroup(c, fused);    // caches the field origin + builds the member->root map
        c.hasField = true;
    }

    /**
     * Registers a PRE-PRODUCED (pooled) full field for the CURRENT chunk on this thread — the
     * PREFETCH path. The two fields were computed at prefetch deposit into {@code holder}'s
     * reused {@code double[]} buffers ({@link GpuFusedInterpolator#produceFullFieldToArrays}),
     * so the field is READY immediately: {@code fieldReady=true}, no pending, no wait — the fill
     * never blocks on the GPU. {@code holder.value} feeds {@link #sample} and (when
     * {@code holder.hasLerp3}) {@code holder.lerp3} feeds {@link #sampleLerp3}, both bit-identical
     * to the pinned-staging fallback path.
     *
     * <p><b>Ownership.</b> This method takes ownership of {@code holder}: it is held for the whole
     * chunk fill and returned to {@link OnDeviceFieldPool} at {@code endChunk} / re-provision
     * (via {@link #releasePriorField}) — NEVER while the chunk is still reading it. If the field
     * can't be installed (no chunk bound, or an unexpected error), the holder is returned to the
     * pool immediately so it can never leak.
     */
    static void setFieldReady(GpuFusedInterpolator fused, OnDeviceFieldPool.Holder holder) {
        if (holder == null) {
            return;
        }
        FieldCtx c = CTX.get();
        try {
            if (c.chunk == null) {
                OnDeviceFieldPool.release(holder);   // nothing to serve — don't leak the holder
                return;
            }
            // Bump BEFORE install (invalidates every prior-generation interpolator cache). Done
            // inside the try so the catch-path teardown also leaves caches invalidated.
            c.generation = nextGen();
            // Drain any prior un-waited field / held holder before installing the new one.
            releasePriorField(c);
            c.hostF = null;
            c.hostD = null;
            c.hostFLerp3 = null;
            c.hostDLerp3 = null;
            c.valueArr = holder.value;
            c.lerp3Arr = holder.hasLerp3 ? holder.lerp3 : null;
            c.fullX = holder.fullX;
            c.fullY = holder.fullY;
            c.fullZ = holder.fullZ;
            c.fullN = holder.fullN;
            c.pendingField = null;
            c.fieldReady = true;    // ready — no readback to wait on
            bindGroup(c, fused);    // caches the field origin + builds the member->root map
            c.heldHolder = holder;  // ctx now owns it; returned to the pool at endChunk/re-provision
            c.hasField = true;
        } catch (Throwable t) {
            // Installation failed — never leak the holder; drop the (partial) field so the chunk
            // falls back to the vanilla CPU lerp.
            c.heldHolder = null;
            c.hasField = false;
            c.valueArr = null;
            c.lerp3Arr = null;
            OnDeviceFieldPool.release(holder);
        }
    }

    /**
     * Ensures the in-flight field readback for {@code c} has completed before its pinned
     * staging is read. The FIRST field consumption on this chunk waits on the CL read events
     * (so the GPU field round-trip overlapped the CPU work since {@code beginChunk}); later
     * consumptions are a plain flag check. On wait failure (owner closed, or no events) the
     * field is dropped so the rest of the chunk falls back to the vanilla CPU lerp. Returns
     * {@code true} iff the field is valid to read.
     */
    private static boolean ensureFieldReady(FieldCtx c) {
        if (c.fieldReady) {
            return c.hasField;
        }
        GpuFusedInterpolator.PendingFullField p = c.pendingField;
        if (p == null) {
            c.fieldReady = true;
            return c.hasField;
        }
        c.pendingField = null;
        boolean ok;
        try {
            ok = p.complete();   // blocks on the field read events (only now), releases them
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            c.clearField();      // couldn't obtain the field -> vanilla CPU lerp for this chunk
            return false;
        }
        // SHUTDOWN UAF GUARD: complete() waited the field readbacks under fused.fillLock, so the
        // pinned staging is valid RIGHT NOW — but the instant complete() released that lock,
        // OpenCLBackend.shutdown() -> GpuFusedInterpolator.close() -> PerThread.freeFfHost() can
        // free it concurrently, and the per-block reads in sample()/sampleLerp3()/fillLerp3Bulk()
        // take no lock. Copy the pinned staging into a per-chunk heap double[] ONCE (under fused's
        // re-acquired read lock, re-checking closed) and switch every subsequent read to the heap
        // array; on a closed race drop the field so the chunk uses the CPU lerp (never read freed
        // memory).
        if (!materializePinnedField(c)) {
            c.clearField();
            return false;
        }
        c.fieldReady = true;
        return true;
    }

    /**
     * SHUTDOWN UAF GUARD (pinned-staging path only). Converts the just-waited pinned field
     * staging referenced by {@code c.hostF/hostD/hostFLerp3/hostDLerp3} into per-chunk HEAP
     * {@code double[]} arrays via {@link GpuFusedInterpolator#copyPinnedFieldToHeap} (which does
     * the copy under {@code fused.fillLock.readLock()} with a re-check of {@code closed}), then
     * nulls the pinned views and points {@code c.valueArr}/{@code c.lerp3Arr} at the heap arrays.
     * This makes every later per-block read take the SAME path as the pooled (READY) field — it
     * never dereferences the pinned native buffer again — so {@link GpuFusedInterpolator#close()}
     * freeing the staging on server-stop-during-worldgen can no longer be read after free.
     *
     * <p>Bit-neutral: the heap arrays hold the identical values (fp32 widened to double exactly as
     * the per-block read did). No-op returning {@code true} on the pooled path (nothing pinned) and
     * when no value staging is registered (defensive). Returns {@code false} only when the
     * interpolator was closed mid-flight (staging may be freed) — the caller drops the field and
     * the chunk falls back to the vanilla CPU lerp.
     */
    private static boolean materializePinnedField(FieldCtx c) {
        if (c.valueArr != null) {
            return true;   // pooled/ready path — no pinned staging to copy.
        }
        boolean haveValue = c.fp32 ? (c.hostF != null) : (c.hostD != null);
        if (!haveValue) {
            return true;   // no value staging registered (defensive) — nothing to copy.
        }
        GpuFusedInterpolator fused = c.fused;
        if (fused == null) {
            return false;  // cannot re-acquire the fill lock safely -> CPU lerp (defensive).
        }
        long totalL = (long) fused.rootCount() * c.fullN;
        if (totalL <= 0 || totalL > Integer.MAX_VALUE) {
            return false;
        }
        int total = (int) totalL;
        boolean haveLerp3 = c.fp32 ? (c.hostFLerp3 != null) : (c.hostDLerp3 != null);
        double[] valueArr = new double[total];
        double[] lerp3Arr = haveLerp3 ? new double[total] : null;
        boolean copied = fused.copyPinnedFieldToHeap(c.fp32, c.hostF, c.hostD,
                c.hostFLerp3, c.hostDLerp3, total, valueArr, lerp3Arr);
        if (!copied) {
            return false;   // closed mid-flight -> never serve the (possibly freed) staging.
        }
        // Switch every subsequent per-block read to the heap arrays (identical to the pooled path);
        // null the pinned views so sample()/sampleLerp3()/fillLerp3Bulk() never read them again.
        c.valueArr = valueArr;
        c.lerp3Arr = lerp3Arr;
        c.hostF = null;
        c.hostD = null;
        c.hostFLerp3 = null;
        c.hostDLerp3 = null;
        return true;
    }

    // ------------------------------------------------------------------
    // Per-block serve (called from the NoiseInterpolator.updateForZ mixin and the
    // NoiseChunk.NoiseInterpolator cell-cache fill mixin).
    // ------------------------------------------------------------------

    /**
     * Hoisted resolution: returns the {@link FieldCtx} that {@code interpolator} is served from
     * this chunk (with its per-interpolator root/base cached on the interpolator via
     * {@link IOnDeviceInterpCache}), or {@code null} when no GPU field serves this chunk. This is
     * the ONE-TIME plumbing lifted out of the per-block consume:
     * <ul>
     *   <li><b>Cache hit</b> (the common per-block case): the interpolator's cached context is
     *       non-null, is the chunk being filled, and its cached {@code generation} still equals the
     *       context's current generation — return it directly. NO {@code ThreadLocal} get, NO
     *       {@code IdentityHashMap} lookup, NO {@code resolveRoot}, NO {@code ensureFieldReady}.</li>
     *   <li><b>Cache miss</b> (first block per interpolator per chunk, or after a field
     *       re-provision): fall back to {@code CTX.get()}, wait the field ready ONCE, resolve the
     *       root via the fused member map, and stamp the result onto the interpolator with the
     *       context's current generation.</li>
     * </ul>
     *
     * <p><b>Why no stale root/array can ever be served.</b> {@code FieldCtx} is per-thread and a
     * chunk is filled by exactly one worker at a time. A {@code generation} value is globally
     * unique (from {@link #FIELD_GEN}); every field install ({@link #setField}/{@link #setFieldReady})
     * and every teardown ({@code clearField}) bumps the owning context's generation. So a cached
     * generation identifies exactly one field installation. When an interpolator is reused for a new
     * chunk on the SAME thread, that thread ended its prior chunk (bumping the generation via
     * {@code endChunk}/{@code beginChunk} -> {@code discardField} -> {@code clearField}) and
     * installed the new field (another bump) -> the cached stamp no longer matches -> cache miss ->
     * re-resolve. When an interpolator MIGRATES to another worker (its owning {@code NoiseChunk} is
     * reassigned), the cached context is the OLD thread's; that thread finished the interpolator's
     * prior chunk (bumping its generation) before the chunk could be handed off, so its current
     * generation (read volatile, so atomically, never torn) no longer equals the cached stamp ->
     * cache miss -> {@code CTX.get()} returns the CURRENT thread's context. The {@code c.chunk ==
     * chunk} guard rejects any residual case before the generation is even consulted. Therefore a
     * cache hit implies {@code c} is the current thread's context holding the exact field the root
     * was resolved against, and {@code c}'s arrays/origin/extent are the live values the
     * un-hoisted {@code CTX.get()} path would have read.
     */
    private static FieldCtx resolveCtx(IOnDeviceInterpCache cache, DensityFunction noiseFiller, NoiseChunk chunk) {
        FieldCtx c = (FieldCtx) cache.superchunk$odiCtx();
        if (c != null && c.chunk == chunk && c.generation == cache.superchunk$odiGen()) {
            return c;   // cache hit: c is this thread's ctx at the resolved field generation
        }
        // Cache miss: resolve once against this thread's live context and stamp the interpolator.
        c = CTX.get();
        if (!c.hasField || c.chunk != chunk) {
            return null;   // no GPU field for this chunk -> caller returns NaN (re-checked per block)
        }
        // Wait on the in-flight field readback at first use (overlap window ends here).
        if (!ensureFieldReady(c)) {
            return null;   // field wait failed -> vanilla CPU lerp for the rest of the chunk
        }
        int root = resolveRoot(c, noiseFiller);
        // Stamp the resolution (root may be -1 = resolved-but-not-served; cache it either way so a
        // not-served interpolator does not re-run resolveRoot every block). base == root*fullN.
        cache.superchunk$odiStore(c, c.generation, root, root >= 0 ? root * c.fullN : 0);
        return c;
    }

    /**
     * Returns the GPU full-field interpolated value for {@code interpolator} at the
     * chunk's CURRENT block, or {@link Double#NaN} when this interpolator is not served
     * (no field, stale chunk, filler not a fused member, or block outside the field).
     * The caller (fast path) overwrites {@code this.value} with this; verify mode compares.
     *
     * <p>The {@code (ctx, root, base)} plumbing is hoisted to {@link #resolveCtx} and cached on the
     * interpolator, so the per-block hot path is: validate the cached stamp (a field access), then a
     * bare {@code array[base + idx]} read — no {@code ThreadLocal}, no map lookup.
     */
    public static double sample(Object interpolator, DensityFunction noiseFiller, NoiseChunk chunk) {
        IOnDeviceInterpCache cache = (IOnDeviceInterpCache) interpolator;
        FieldCtx c = resolveCtx(cache, noiseFiller, chunk);
        if (c == null) {
            return Double.NaN;
        }
        int root = cache.superchunk$odiRoot();
        if (root < 0) {
            return Double.NaN;   // resolved-but-not-served (filler not a fused member)
        }
        // Field origin + extent are cached on the ctx (invariant this chunk); only the CURRENT
        // block position is per-call, so the cast is still needed to read it.
        IChunkNoiseSampler s = (IChunkNoiseSampler) chunk;
        int bx = (s.getStartBlockX() + s.getCellBlockX()) - c.originX;
        int by = (s.getStartBlockY() + s.getCellBlockY()) - c.originY;
        int bz = (s.getStartBlockZ() + s.getCellBlockZ()) - c.originZ;
        if (bx < 0 || by < 0 || bz < 0 || bx >= c.fullX || by >= c.fullY || bz >= c.fullZ) {
            return Double.NaN;
        }
        int gi = cache.superchunk$odiBase() + (by * c.fullX + bx) * c.fullZ + bz;   // base == root*fullN
        // POOLED (ready) path reads the double[] directly (fp32 already widened at production);
        // pinned-staging path reads the pinned buffer as before. Bit-identical either way.
        double v = SKIP_CONSUME ? 0.0
                : (c.valueArr != null) ? c.valueArr[gi]
                : (c.fp32 ? (double) c.hostF.get(gi) : c.hostD.get(gi));
        c.fastServed++;   // per-thread; flushed to `served` once per chunk (no per-block CAS)
        if (!firstServeLogged) {
            firstServeLogged = true;
            LOGGER.info("[SuperChunk-GPU] [on-device-interp] FIRST GPU full-field value consumed on thread '{}' "
                    + "(mode={}). The {}x{}x{} interpolated field is now feeding the NoiseChunk fill.",
                    Thread.currentThread().getName(), VERIFY ? "VERIFY (returns vanilla)" : "FAST", c.fullX, c.fullY, c.fullZ);
        }
        return v;
    }

    /**
     * Returns the GPU <b>lerp3 (X&rarr;Y&rarr;Z) cell-cache</b> field value for
     * {@code interpolator} at ABSOLUTE block {@code (blockX,blockY,blockZ)}, or
     * {@link Double#NaN} when not served (no field, no lerp3 field this chunk, stale
     * chunk, filler not a fused member, or block outside the field). Called from the
     * {@code NoiseChunk.NoiseInterpolator} cell-cache fill ({@code fillingCell == true})
     * where the CPU would compute {@code Mth.lerp3}; bit-exact to it at fp64.
     *
     * <p>Unlike {@link #sample} (which reads the chunk's CURRENT {@code inCellX/Y/Z}),
     * the cell-cache fill batches arbitrary block positions, so the caller passes the
     * absolute coords directly. The field origin and index layout are identical to
     * {@link #sample}; only the source buffer ({@code hostFLerp3/hostDLerp3}) differs.
     */
    public static double sampleLerp3(Object interpolator, DensityFunction noiseFiller, NoiseChunk chunk,
                                     int blockX, int blockY, int blockZ) {
        IOnDeviceInterpCache cache = (IOnDeviceInterpCache) interpolator;
        FieldCtx c = resolveCtx(cache, noiseFiller, chunk);
        if (c == null) {
            return Double.NaN;
        }
        // No lerp3 field registered this chunk (kernel didn't build/run) -> CPU lerp3.
        if (c.valueArr != null) {
            if (c.lerp3Arr == null) {
                return Double.NaN;   // pooled value field but no lerp3 -> CPU lerp3
            }
        } else if (c.fp32 ? (c.hostFLerp3 == null) : (c.hostDLerp3 == null)) {
            return Double.NaN;
        }
        int root = cache.superchunk$odiRoot();
        if (root < 0) {
            return Double.NaN;
        }
        // Field origin == chunk min block coords (cached on the ctx). The cell-cache fill passes
        // absolute coords directly, so no IChunkNoiseSampler cast is needed here.
        int bx = blockX - c.originX;
        int by = blockY - c.originY;
        int bz = blockZ - c.originZ;
        if (bx < 0 || by < 0 || bz < 0 || bx >= c.fullX || by >= c.fullY || bz >= c.fullZ) {
            return Double.NaN;
        }
        int gi = cache.superchunk$odiBase() + (by * c.fullX + bx) * c.fullZ + bz;   // base == root*fullN
        // POOLED (ready) path reads the lerp3 double[] directly; pinned path reads the pinned
        // buffer. Bit-identical (fp32 widened at production; matches the fast-path read).
        double v = SKIP_CONSUME ? 0.0
                : (c.valueArr != null) ? c.lerp3Arr[gi]
                : (c.fp32 ? (double) c.hostFLerp3.get(gi) : c.hostDLerp3.get(gi));
        c.fastLerp3Served++;   // per-thread; flushed to `lerp3Served` once per chunk (no per-block CAS)
        if (!firstServeLerp3Logged) {
            firstServeLerp3Logged = true;
            LOGGER.info("[SuperChunk-GPU] [on-device-interp] [lerp3] FIRST GPU cell-cache (X->Y->Z) value consumed "
                    + "on thread '{}' (mode={}). The dominant finalDensity Mth.lerp3 fill is now GPU-served.",
                    Thread.currentThread().getName(), VERIFY ? "VERIFY (returns vanilla)" : "FAST");
        }
        return v;
    }

    /**
     * TRUE BULK lerp3 fill for the array entry point
     * {@code c2me$getCached(double[] res, int[] x, int[] y, int[] z, INTERPOLATION)} (the
     * {@code CacheAllInCell} batch fill). Resolves {@code (ctx, root, base, lerp3 array, origin)}
     * ONCE via {@link #resolveCtx}, then a tight loop over the batch with NO per-index
     * {@code CTX.get()}, {@code IdentityHashMap} lookup, or per-index method dispatch for the served
     * in-field case — just {@code res[i] = lerp3Arr[base + fieldIndex(x[i],y[i],z[i])]}.
     *
     * <p>Returns {@code false} (without touching {@code res}) when this interpolator is NOT served
     * by the GPU lerp3 field (no field / not ready / filler not a fused member / no lerp3 field this
     * chunk); the caller then runs its existing per-index CPU loop, exactly as before. When it
     * returns {@code true}, {@code res} is FULLY written: GPU values for in-field indices, and the
     * bit-identical vanilla {@code Mth.lerp3} (via {@link IOnDeviceInterpCache#superchunk$odiVanillaLerp3})
     * for any out-of-field index — matching what the per-index {@link #sampleLerp3} + CPU fallback
     * produced.
     *
     * <p><b>VERIFY:</b> for each served in-field index it computes the CPU {@code Mth.lerp3} too,
     * records the GPU-vs-CPU comparison into the shared parity counters, and writes the VANILLA
     * value into {@code res} — identical accounting and result to the per-index verify path
     * (including the per-served {@code fastLerp3Served} tally). The deltas {@code tx/ty/tz} are
     * computed exactly as the caller's per-index loop ({@code (double) cellBlock / cellBlockCount},
     * {@code cellBlockCount} as {@code double}), so the CPU value is bit-identical.
     */
    public static boolean fillLerp3Bulk(Object interpolator, DensityFunction noiseFiller, NoiseChunk chunk,
                                        double[] res, int[] x, int[] y, int[] z,
                                        int startBlockX, int startBlockY, int startBlockZ,
                                        double horizontalCellBlockCount, double verticalCellBlockCount) {
        IOnDeviceInterpCache cache = (IOnDeviceInterpCache) interpolator;
        FieldCtx c = resolveCtx(cache, noiseFiller, chunk);
        if (c == null) {
            return false;   // no GPU field / not ready -> caller uses its CPU per-index loop
        }
        int root = cache.superchunk$odiRoot();
        if (root < 0) {
            return false;   // filler not a fused member -> caller uses its CPU per-index loop
        }
        // No lerp3 field registered this chunk -> caller uses its CPU per-index loop.
        boolean pooled = (c.valueArr != null);
        if (pooled) {
            if (c.lerp3Arr == null) {
                return false;
            }
        } else if (c.fp32 ? (c.hostFLerp3 == null) : (c.hostDLerp3 == null)) {
            return false;
        }
        // Hoist everything the loop touches into locals (no ctx/cache re-reads per index).
        final int base = cache.superchunk$odiBase();   // root*fullN
        final int fullX = c.fullX, fullY = c.fullY, fullZ = c.fullZ;
        final int ox = c.originX, oy = c.originY, oz = c.originZ;
        final boolean fp32 = c.fp32;
        final double[] lerp3Arr = c.lerp3Arr;
        final FloatBuffer hostFLerp3 = c.hostFLerp3;
        final DoubleBuffer hostDLerp3 = c.hostDLerp3;
        final boolean verify = VERIFY;
        final int n = res.length;
        long servedInc = 0L;
        for (int i = 0; i < n; i++) {
            int bx = x[i] - ox;
            int by = y[i] - oy;
            int bz = z[i] - oz;
            if (bx < 0 || by < 0 || bz < 0 || bx >= fullX || by >= fullY || bz >= fullZ) {
                // Out of field -> bit-identical vanilla Mth.lerp3 (same fallback sampleLerp3 forces).
                double tx = (double) (x[i] - startBlockX) / horizontalCellBlockCount;
                double ty = (double) (y[i] - startBlockY) / verticalCellBlockCount;
                double tz = (double) (z[i] - startBlockZ) / horizontalCellBlockCount;
                res[i] = cache.superchunk$odiVanillaLerp3(tx, ty, tz);
                continue;
            }
            int gi = base + (by * fullX + bx) * fullZ + bz;
            double gpu = SKIP_CONSUME ? 0.0
                    : pooled ? lerp3Arr[gi]
                    : (fp32 ? (double) hostFLerp3.get(gi) : hostDLerp3.get(gi));
            if (verify) {
                double tx = (double) (x[i] - startBlockX) / horizontalCellBlockCount;
                double ty = (double) (y[i] - startBlockY) / verticalCellBlockCount;
                double tz = (double) (z[i] - startBlockZ) / horizontalCellBlockCount;
                double cpu = cache.superchunk$odiVanillaLerp3(tx, ty, tz);
                recordCompareLerp3(gpu, cpu);
                res[i] = cpu;   // return vanilla while parity is proven
            } else {
                res[i] = gpu;   // FAST: GPU field replaces the CPU Mth.lerp3
            }
            servedInc++;
        }
        c.fastLerp3Served += servedInc;   // per-thread; flushed to `lerp3Served` once per chunk
        if (!firstServeLerp3Logged && servedInc > 0) {
            firstServeLerp3Logged = true;
            LOGGER.info("[SuperChunk-GPU] [on-device-interp] [lerp3] FIRST GPU cell-cache (X->Y->Z) value consumed "
                    + "on thread '{}' (mode={}). The dominant finalDensity Mth.lerp3 fill is now GPU-served.",
                    Thread.currentThread().getName(), VERIFY ? "VERIFY (returns vanilla)" : "FAST");
        }
        return true;
    }

    /** Resolves {@code noiseFiller} to its root index in the active fused group, or -1. */
    private static int resolveRoot(FieldCtx c, DensityFunction noiseFiller) {
        if (!(noiseFiller instanceof SubCompiledDensityFunction scdf)) {
            return -1;
        }
        GpuDfcDelegate del = scdf.c2me$getGpuDelegate();
        if (del == null) {
            return -1;
        }
        GpuDensityFunction gpu = del.gpu();
        if (gpu == null || c.rootOf == null) {
            return -1;
        }
        Integer k = c.rootOf.get(gpu);
        return k == null ? -1 : k;
    }

    /**
     * VERIFY: records one GPU-vs-CPU comparison. Counts a BUG when the two are not
     * bit-identical (both-NaN excepted); tracks the max absolute error and logs the first
     * few mismatches. The caller returns the vanilla value either way.
     */
    public static void recordCompare(double gpu, double cpu) {
        comparisons.increment();
        accumulate(gpu, cpu, "value");
    }

    /**
     * VERIFY: records one GPU-vs-CPU comparison for the <b>lerp3 cell-cache</b> path.
     * Increments the SEPARATE {@link #lerp3Comparisons} counter but shares the SAME
     * {@code bugs}/{@code maxAbsErr} parity accounting as {@link #recordCompare} (one
     * gate). The caller returns the vanilla value either way.
     */
    public static void recordCompareLerp3(double gpu, double cpu) {
        lerp3Comparisons.increment();
        accumulate(gpu, cpu, "lerp3");
    }

    /** Shared bug/maxAbsErr accounting for both verify paths (bit-identical = pass). */
    private static void accumulate(double gpu, double cpu, String tag) {
        if (Double.doubleToRawLongBits(gpu) == Double.doubleToRawLongBits(cpu)) {
            return;   // bit-identical
        }
        if (Double.isNaN(gpu) && Double.isNaN(cpu)) {
            return;   // both NaN (different bit patterns) — not a value divergence
        }
        bugs.increment();
        double err = Math.abs(gpu - cpu);
        if (err > maxAbsErr) {
            maxAbsErr = err;
        }
        if (mismatchLogged.incrementAndGet() <= 20) {
            LOGGER.warn("[SuperChunk-GPU] [on-device-interp] [verify] [{}] MISMATCH #{}: gpu={} cpu={} absErr={}",
                    tag, mismatchLogged.get(), gpu, cpu, err);
        }
    }

    // ------------------------------------------------------------------
    // Stage-3 block-id census accessors (read-only; used by BlockIdCensus
    // to surface the mode-C density-equality proof leg in its report).
    // ------------------------------------------------------------------

    /** Total verify comparisons (value + lerp3 paths). 0 = the proof leg never ran. */
    public static long verifyComparisons() {
        return comparisons.sum() + lerp3Comparisons.sum();
    }

    /** Verify BUGS (non-bit-identical GPU-vs-CPU leaf values). Census HARD gate: must be 0. */
    public static long verifyBugs() {
        return bugs.sum();
    }

    /** Max |gpu-cpu| over all verify mismatches (0.0 when none). */
    public static double verifyMaxAbsErr() {
        return maxAbsErr;
    }

    /**
     * Census helper: the GPU full-field state for {@code chunk} on the CURRENT thread.
     * Returns 0 = no live field (mode-C proof leg absent for this chunk), 1 = value-path
     * field only, 2 = value + lerp3 fields (full proof leg), 3 = field present but fp32
     * (equality holds only at the widened-float level — flagged separately by the census).
     */
    public static int fieldStateFor(NoiseChunk chunk) {
        if (!ENABLED || chunk == null) {
            return 0;
        }
        try {
            FieldCtx c = CTX.get();
            if (c == null || c.chunk != chunk || !c.hasField) {
                return 0;
            }
            if (c.fp32) {
                return 3;
            }
            boolean lerp3 = (c.valueArr != null) ? (c.lerp3Arr != null) : (c.hostDLerp3 != null);
            return lerp3 ? 2 : 1;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Logs the running / final summary (BUGS=0 is the parity gate in verify mode). */
    public static void logSummary(String when) {
        if (!ENABLED) {
            return;
        }
        long srv = served.sum();
        if (VERIFY) {
            LOGGER.info("[SuperChunk-GPU] [on-device-interp] [verify] ({}): value-path[Y->X->Z] comparisons={}, served={}; "
                    + "lerp3-path[X->Y->Z cell-cache] comparisons={}, served={}; TOTAL comparisons={}, BUGS={}, maxAbsErr={}.",
                    when, comparisons.sum(), srv, lerp3Comparisons.sum(), lerp3Served.sum(),
                    comparisons.sum() + lerp3Comparisons.sum(), bugs.sum(), maxAbsErr);
        } else {
            LOGGER.info("[SuperChunk-GPU] [on-device-interp] (fast) ({}): served={} value-path[Y->X->Z] + {} lerp3-path[X->Y->Z "
                    + "cell-cache] GPU full-field block values (CPU lerp replaced).", when, srv, lerp3Served.sum());
        }
        // Prefetch-pool health: the ready-vs-fallback split is the honest "is the flagship
        // path actually running pre-produced?" number — a silently under-provisioned pool
        // shows up here instead of nowhere.
        long ready = fieldsServedReady.sum();
        long fb = fieldsFallbackPerChunk.sum();
        long exh = poolExhausted.sum();
        long pf = productionFailed.sum();
        if (ready > 0 || fb > 0 || exh > 0 || pf > 0) {
            LOGGER.info("[SuperChunk-GPU] [on-device-interp] prefetch-pool ({}): fields served READY={}, per-chunk "
                            + "fallback={}, pool acquire refused (exhausted/OOM)={}, production failed={} (prefetchDepth={}).",
                    when, ready, fb, exh, pf, OnDeviceFieldPool.CAPACITY);
            long acq = ready + exh;
            if (!poolExhaustWarned && acq >= 100L && exh * 20L > acq) {
                poolExhaustWarned = true;
                LOGGER.warn("[SuperChunk-GPU] [on-device-interp] prefetch pool exhausted on {}% of acquires — the "
                                + "pre-produced-field path is silently degrading to per-chunk production. Consider raising "
                                + "-Dsuperchunk.gpu.onDeviceInterp.prefetchDepth (currently {}).",
                        String.format("%.1f", 100.0 * exh / acq), OnDeviceFieldPool.CAPACITY);
            }
        }
    }
}
