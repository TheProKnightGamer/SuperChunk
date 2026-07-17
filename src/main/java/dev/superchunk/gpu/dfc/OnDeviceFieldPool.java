package dev.superchunk.gpu.dfc;

import java.util.ArrayDeque;

/**
 * SuperChunk GPU — <b>bounded pool of REUSABLE full-field buffers</b> for the on-device
 * interpolation PREFETCH path (flag {@code -Dsuperchunk.gpu.onDeviceInterp[.verify]}).
 *
 * <p><b>Why.</b> When {@link GpuBatchPrefetch} deposits a chunk's batched cell-corner
 * grids, we PRODUCE the two full interpolated fields right then (on the batcher thread,
 * off the worker's fill critical path) so the fill later serves them READY with no GPU
 * round-trip. The naive version allocated a fresh {@code M*fullN} {@code double[]} field
 * array per chunk (~9-17 MB each) — at {@code -Xmx8G} those are G1 <i>humongous</i>
 * allocations, and ~100-200 GB of churn over a run triggers a GC storm. This pool holds a
 * SMALL, FIXED set of holders whose {@code double[]} field buffers are allocated at most
 * {@link #CAPACITY} times total and REUSED across chunks — <b>zero per-chunk field
 * allocation</b> on the steady-state path.
 *
 * <p><b>Bound.</b> {@link #CAPACITY} (= {@code -Dsuperchunk.gpu.onDeviceInterp.prefetchDepth},
 * default 8, clamped {@code >= 1}) caps the number of holders that can be <i>in use</i>
 * (acquired but not yet released) at once, and hence total field memory at
 * {@code CAPACITY * 2 * M*fullN} doubles (~{@code CAPACITY} * 10-21 MB). {@link #acquire}
 * is <b>non-blocking</b>: when the bound is hit it returns {@code null} and the caller
 * gracefully falls back to per-chunk production (no deadlock, no unbounded growth).
 *
 * <p><b>Ownership.</b> A holder is exclusively owned by whoever holds it between
 * {@link #acquire} and {@link #release}. The producer fully overwrites its arrays before
 * they are read, and a holder is never re-acquired while its chunk is still reading it
 * (the consumer keeps it live for the whole fill and releases it at {@code endChunk}), so
 * no buffer is ever aliased across two live chunks. Thread-safe: {@code acquire} runs on
 * the batcher thread, {@code release} on worker/eviction threads.
 *
 * <p>Holders store plain {@code double[]} (fp32 fields are widened to {@code double} at
 * production, which is exact and matches the fast-path read), so the pool owns NO
 * {@code cl_mem}; {@link #clear} simply drops the buffers for the GC. Entirely dormant
 * unless on-device interp is enabled.
 */
public final class OnDeviceFieldPool {

    /** Max holders that can be in use (= acquired-not-released) at once; bounds field memory. */
    public static final int CAPACITY = capacity();

    /**
     * When false, buffers are NOT reused: {@link #acquire} allocates a fresh {@code double[]} pair
     * every call and {@link #release} drops it for the GC — reproducing the original un-pooled
     * precompute (a fresh ~10-20 MB humongous array per chunk) so its GC behaviour can be measured
     * directly. Default true (pooled). Diagnostic only; the {@code inUse} bound still applies.
     */
    private static final boolean POOL_BUFFERS =
            Boolean.parseBoolean(System.getProperty("superchunk.gpu.onDeviceInterp.poolBuffers", "true"));

    /**
     * One reusable pair of full-field buffers (value + lerp3), sized once to {@code M*fullN}
     * and reused across chunks. The producer fills {@code [0, total)}; the consumer reads it.
     */
    public static final class Holder {
        double[] value;                 // Y->X->Z field, M*fullN reals (reused, never per-chunk)
        double[] lerp3;                 // X->Y->Z cell-cache field, M*fullN reals (reused)
        boolean hasLerp3;               // whether the lerp3 field was produced this cycle
        int total;                      // filled element count (= M*fullN)
        int fullX, fullY, fullZ, fullN; // produced field extent (canonical full chunk)
        GpuFusedInterpolator fused;     // fused group the produced field belongs to
    }

    private static final Object LOCK = new Object();
    /** Cache of released holders for reuse. Never exceeds {@link #CAPACITY} in steady state. */
    private static final ArrayDeque<Holder> FREE = new ArrayDeque<>();
    /** Holders currently acquired-not-released; the hard bound is {@code inUse <= CAPACITY}. */
    private static int inUse = 0;

    private OnDeviceFieldPool() {
    }

    private static int capacity() {
        int d = 8;
        try {
            Integer v = Integer.getInteger("superchunk.gpu.onDeviceInterp.prefetchDepth");
            if (v != null) {
                d = v;
            }
        } catch (Throwable ignored) {
        }
        if (d < 1) {
            d = 1;
        }
        if (d > 1024) {
            d = 1024;   // sanity cap — DEPTH * ~17 MB would already be absurd well below this
        }
        return d;
    }

    /**
     * Non-blocking acquire of a reusable holder whose two field buffers can hold at least
     * {@code total} reals. Returns {@code null} when the bound ({@link #CAPACITY} in-use
     * holders) is hit — the caller then produces per-chunk instead. Reuses a released
     * holder's arrays; allocates {@code double[]}s only for a fresh holder or when an
     * existing one must grow (both at most {@link #CAPACITY} times total in steady state).
     */
    static Holder acquire(int total, GpuFusedInterpolator fused) {
        if (!OnDeviceInterp.ENABLED || total <= 0) {
            return null;
        }
        Holder h;
        synchronized (LOCK) {
            if (inUse >= CAPACITY) {
                // exhausted -> graceful per-chunk fallback (never blocks). COUNTED: an
                // under-provisioned pool degrading the flagship path must be visible.
                OnDeviceInterp.recordPoolExhausted();
                return null;
            }
            h = POOL_BUFFERS ? FREE.pollFirst() : null;   // !POOL_BUFFERS -> always a fresh (un-reused) holder
            if (h == null) {
                h = new Holder();
            }
            inUse++;
        }
        // Size (or grow) the reused buffers OUTSIDE the lock: the holder is now exclusively
        // owned by this caller, so no other thread can touch it. A fresh holder allocates once;
        // a reused one only reallocates if a larger group needs more room (rare — M is stable).
        try {
            if (h.value == null || h.value.length < total) {
                h.value = new double[total];
                h.lerp3 = new double[total];
            }
        } catch (Throwable t) {
            // OOM sizing the buffers — return the slot (drop the holder) and fall back.
            synchronized (LOCK) {
                if (inUse > 0) {
                    inUse--;
                }
            }
            OnDeviceInterp.recordPoolExhausted();
            return null;
        }
        h.total = total;
        h.fused = fused;
        h.hasLerp3 = false;
        return h;
    }

    /** Returns a holder to the pool for reuse. Must be called exactly once per {@link #acquire}. */
    static void release(Holder h) {
        if (h == null) {
            return;
        }
        h.fused = null;   // drop the group ref so a rebuilt group can be GC'd
        synchronized (LOCK) {
            if (inUse > 0) {
                inUse--;
            }
            if (POOL_BUFFERS) {
                FREE.addFirst(h);   // cache for reuse; !POOL_BUFFERS drops it -> GC reclaims (measures the storm)
            }
        }
    }

    /**
     * Drops all cached holders and resets the bound (backend shutdown / fusion teardown).
     * Frees the field buffers for the GC. Any holder still in use when this runs is returned
     * to {@link #FREE} on its later {@link #release} (harmless transient during shutdown).
     */
    static void clear() {
        synchronized (LOCK) {
            FREE.clear();
            inUse = 0;
        }
    }
}
