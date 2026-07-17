package dev.superchunk.gpu.dfc;

import dev.superchunk.gpu.OpenCLBackend;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SuperChunk GPU — thread-safe cross-chunk <b>AGGREGATOR</b> (producer/consumer).
 *
 * <p>Pools many concurrent per-chunk density-grid requests and serves them with as
 * few batched GPU dispatches as possible, returning a {@link CompletableFuture} per
 * chunk. Submitters (worldgen worker threads, or the self-test's executor) merely
 * {@link #submit} an origin + geometry and get a future back; they do NOT touch the
 * GPU.
 *
 * <p><b>PIPELINED (GPU-throughput rework).</b> Two threads now drive the dispatcher's
 * slot ring: the DRAINER collects/buckets requests and {@link
 * GpuBatchDispatcher#enqueueBatch enqueues} batches WITHOUT waiting (up to the ring's
 * {@code gpu.batchPipelineDepth} batches in flight), and the COMPLETER waits each
 * batch's readback event in FIFO order, copies the per-chunk slices straight out of
 * the slot's pinned staging, completes the futures, and recycles the slot. The GPU
 * therefore never idles while host-side collection/slicing/completion runs, and the
 * drainer only blocks when the GPU is a full pipeline behind — exactly when pooled
 * requests should be forming bigger batches anyway. Workers still never touch the
 * driver.
 *
 * <p><b>PROGRAM SLOTS.</b> A batcher carries an optional PRIMARY dispatcher (slot for
 * the density fused group — {@link #submit}/{@link #submitCompact}) and can serve any
 * number of AUX program slots via {@link #submitTo}: each request may name its own
 * {@link GpuBatchDispatcher} (e.g. the per-dimension CLIMATE fused group's
 * {@code df_batch_lattice_multichunk} program), and requests only ever share a batch
 * with requests targeting the SAME dispatcher (the target is part of the grouping
 * key). All driver calls still happen on this ONE drainer thread — submitters never
 * touch the GPU regardless of slot. Aux dispatchers are owned (closed) by their
 * registrants, NOT by {@link #shutdown()}.
 *
 * <p><b>Grouping + flush policy.</b> Requests are grouped by their geometry key
 * {@code (target,strx,stry,strz,dimX,dimY,dimZ)} — only same-target, same-geometry
 * requests can share one batch (all overworld chunks share one geometry, so in
 * practice there is one group per program slot).
 * A group is flushed when it reaches {@code batchLimit} pending requests OR
 * {@code batchWindowMicros} have elapsed since its oldest pending request — whichever
 * comes first. On flush, up to {@code batchLimit} chunks' origins are packed into an
 * {@code int[K*3]}, dispatched in ONE {@code batchFill}, and each chunk's future is
 * completed with its {@code M*n} slice ({@code out[(c*M+k)*n + i]} -> future result
 * {@code [k*n + i]}), where {@code M = }{@link GpuBatchDispatcher#rootCount()} of the
 * batch's target dispatcher.
 *
 * <p><b>Stage scope.</b> This is standalone infrastructure plus a multi-threaded
 * self-test ({@link GpuVanillaParityTest}) ONLY — it is NOT wired into the live
 * worldgen pipeline (zero risk to terrain). Gated by the {@code batchChunks} family of
 * config flags for the next stage. Never throws across {@link #submit}; a dispatch
 * failure completes the affected futures exceptionally rather than hanging them.
 */
public final class GpuChunkBatcher implements AutoCloseable {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    /** Bounded submit queue capacity (producer backpressure). */
    private static final int MAX_QUEUE = 1 << 16;
    /** Idle park when no group is pending; a submit wakes the drainer immediately anyway. */
    private static final long IDLE_PARK_NANOS = 10_000_000L; // 10 ms
    /** Max time {@link #shutdown()} waits for the drainer to finish + flush. */
    private static final long SHUTDOWN_JOIN_MILLIS = 5_000L;

    /** Live-path singleton for the next stage (and the {@link OpenCLBackend#shutdown()} hook). */
    private static volatile GpuChunkBatcher SHARED;

    /**
     * The PRIMARY program slot ({@link #submit}/{@link #submitCompact} target), or
     * {@code null} for a dedicated aux-only batcher ({@link #createDedicated}) whose
     * every request names its own dispatcher via {@link #submitTo}. Owned by this
     * batcher: closed in {@link #shutdown()} when present.
     */
    private final GpuBatchDispatcher dispatcher;
    private final int batchLimit;         // max chunks coalesced into one dispatch
    private final long batchWindowNanos;  // max time a request waits before its group flushes

    private final LinkedBlockingQueue<Req> queue = new LinkedBlockingQueue<>(MAX_QUEUE);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread drainer;

    // Pipeline handoff: enqueued batches (drainer) -> completion (completer thread).
    // Effectively bounded by the dispatcher slot ring (enqueueBatch blocks on slots).
    private final LinkedBlockingQueue<PendingBatch> completionQueue = new LinkedBlockingQueue<>();
    private final Thread completer;
    /** Sentinel pushed after the drainer joined; the completer drains then exits. */
    private static final PendingBatch POISON = new PendingBatch(null, null, 0, 0, false);

    // Drainer-thread-only scratch (no concurrency).
    private final Req[] liveScratch;
    // Grow-only origin scratch reused across dispatchBatch calls (the dispatcher copies
    // it into slot staging synchronously during enqueueBatch, so reuse is safe). Never
    // shrinks, so enqueueBatch may receive an array LONGER than K*3 (guards relaxed to
    // <). Only the drainer thread touches this.
    private int[] originScratch;

    // Observability counters.
    private final AtomicLong requestCount = new AtomicLong();   // submit() calls accepted
    private final AtomicLong rejectedCount = new AtomicLong();  // submit() calls REJECTED (queue full / not running)
    private final AtomicLong dispatchCount = new AtomicLong();  // batchFill() calls issued
    private final AtomicLong servedCount = new AtomicLong();    // chunks completed OK
    private final AtomicLong batchSizeSum = new AtomicLong();   // sum of K over all dispatches (for mean)
    private final AtomicLong maxBatchSize = new AtomicLong();   // largest K observed
    /** Achieved batch-size histogram: bins 1, 2-4, 5-8, 9-16, 17-32, 33-64, 65+. */
    private final AtomicLong[] batchHistogram = new AtomicLong[]{
            new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong(),
            new AtomicLong(), new AtomicLong(), new AtomicLong()};

    // Pipeline observability: host-side enqueue time, completer GPU-wait time, slice/
    // complete time, and the in-flight depth sampled at each enqueue (bins 1,2,3,4+).
    private final AtomicLong pipeEnqueueNanos = new AtomicLong();
    private final AtomicLong pipeAwaitNanos = new AtomicLong();
    private final AtomicLong pipeSliceNanos = new AtomicLong();
    private final AtomicLong[] inFlightHistogram = new AtomicLong[]{
            new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()};

    private GpuChunkBatcher(GpuBatchDispatcher dispatcher, int batchLimit, long batchWindowMicros,
                            String threadName) {
        this.dispatcher = dispatcher;
        this.batchLimit = Math.max(1, batchLimit);
        this.batchWindowNanos = Math.max(0L, batchWindowMicros) * 1_000L;
        this.liveScratch = new Req[this.batchLimit];
        this.drainer = new Thread(this::drainLoop, threadName);
        this.drainer.setDaemon(true);
        this.completer = new Thread(this::completeLoop, threadName + "-completer");
        this.completer.setDaemon(true);
        this.completer.start();
        this.drainer.start();
    }

    // ----------------------------------------------------------------------
    // Lifecycle (static singleton for the live path + the backend shutdown hook).
    // ----------------------------------------------------------------------

    /**
     * Creates the shared batcher over {@code dispatcher}, reading {@code batchLimit} /
     * {@code batchWindowMicros} from {@link dev.superchunk.gpu.GpuConfig}. Replaces any
     * existing shared batcher (shutting it down first). Returns the new instance.
     */
    public static GpuChunkBatcher init(GpuBatchDispatcher dispatcher) {
        return init(dispatcher, configBatchLimit(), configWindowMicros());
    }

    /** Creates the shared batcher with explicit knobs. Replaces any existing one. */
    public static GpuChunkBatcher init(GpuBatchDispatcher dispatcher, int batchLimit, long batchWindowMicros) {
        if (dispatcher == null) {
            return null;
        }
        shutdownShared();
        GpuChunkBatcher b = new GpuChunkBatcher(dispatcher, batchLimit, batchWindowMicros, "SuperChunk-GPU-batcher");
        SHARED = b;
        return b;
    }

    /**
     * Creates a DEDICATED batcher with NO primary dispatcher — every request must name
     * its program slot via {@link #submitTo}. Does NOT touch {@link #SHARED} (the caller
     * owns the instance and must {@link #shutdown()} it; aux dispatchers are the caller's
     * to close). Reads {@code batchLimit}/{@code batchWindowMicros} from the config like
     * {@link #init(GpuBatchDispatcher)}. Used by the biome-climate cross-chunk batching
     * ({@code BiomeClimateCache}), whose lifecycle (RandomState build) is independent of
     * the density batcher's ({@code batchChunks} + fused-group discovery).
     */
    public static GpuChunkBatcher createDedicated(String threadName) {
        return new GpuChunkBatcher(null, configBatchLimit(), configWindowMicros(), threadName);
    }

    private static int configBatchLimit() {
        try {
            if (OpenCLBackend.config() != null) {
                return OpenCLBackend.config().batchLimit();
            }
        } catch (Throwable ignored) {
        }
        return 32;
    }

    private static long configWindowMicros() {
        try {
            if (OpenCLBackend.config() != null) {
                return OpenCLBackend.config().batchWindowMicros();
            }
        } catch (Throwable ignored) {
        }
        return 500L;
    }

    /** The current shared batcher, or {@code null} if none is installed. */
    public static GpuChunkBatcher shared() {
        return SHARED;
    }

    /**
     * Shuts down the shared batcher if present (drains/serves pending requests, stops
     * the batcher thread, closes the dispatcher). Idempotent no-op when none is set.
     * Wired additively into {@link OpenCLBackend#shutdown()}.
     */
    public static void shutdownShared() {
        GpuChunkBatcher b = SHARED;
        if (b != null) {
            b.shutdown(); // instance shutdown clears SHARED
        }
    }

    // ----------------------------------------------------------------------
    // Public API.
    // ----------------------------------------------------------------------

    /**
     * Enqueues one chunk's density-grid request and returns a future that completes
     * with that chunk's {@code M*n} doubles (root {@code k} cell {@code i} at
     * {@code [k*n + i]}, {@code n = dimX*dimY*dimZ}). Safe to call concurrently from
     * many threads; the caller does not block on the GPU. The future completes
     * exceptionally if the batcher is shutting down, the bounded queue is full, or the
     * batched dispatch fails — never hangs.
     */
    public CompletableFuture<double[]> submit(int ox, int oy, int oz,
                                              int strx, int stry, int strz,
                                              int dimX, int dimY, int dimZ) {
        return submitCompact(ox, oy, oz, strx, stry, strz, dimX, dimY, dimZ, null).future();
    }

    /**
     * COMPACT-IDS (Stage 5) submit: like {@link #submit} but optionally carries the
     * chunk's decide-kernel aux ({@code null} = plain corner-only request, identical
     * to {@link #submit}). The returned handle exposes {@link SubmitHandle#ids()},
     * populated (drainer-side, before the future completes — the completion is the
     * happens-before edge) with the chunk's 1-byte/block ids when the chained decide
     * dispatch produced them; {@code null} ids = the chunk follows the normal
     * corner-only path.
     */
    public SubmitHandle submitCompact(int ox, int oy, int oz,
                                      int strx, int stry, int strz,
                                      int dimX, int dimY, int dimZ,
                                      CompactIds.ChunkAux aux) {
        CompletableFuture<double[]> f = new CompletableFuture<>();
        if (dispatcher == null) {
            f.completeExceptionally(new IllegalStateException(
                    "GpuChunkBatcher has no primary dispatcher — use submitTo(target, ...)"));
            return new SubmitHandle(f, null);
        }
        GeometryKey key = new GeometryKey(null, strx, stry, strz, dimX, dimY, dimZ);
        Req r = new Req(ox, oy, oz, key, f, aux);
        SubmitHandle handle = new SubmitHandle(f, r);
        enqueueReq(r, f);
        return handle;
    }

    /**
     * AUX-PROGRAM-SLOT submit: like {@link #submit} but the request targets
     * {@code target} (e.g. a dimension's CLIMATE fused-group batched dispatcher)
     * instead of this batcher's primary dispatcher. Requests only batch with
     * same-target, same-geometry requests; the single drainer thread issues the
     * dispatch, so the submitter never touches the GPU. The returned future
     * completes with the chunk's {@code target.rootCount()*n} doubles, or
     * exceptionally on shutdown / queue-full / dispatch failure — never hangs.
     * {@code target} must stay open until its futures complete (its owner closes
     * it only after shutting this batcher down).
     */
    public CompletableFuture<double[]> submitTo(GpuBatchDispatcher target,
                                                int ox, int oy, int oz,
                                                int strx, int stry, int strz,
                                                int dimX, int dimY, int dimZ) {
        CompletableFuture<double[]> f = new CompletableFuture<>();
        if (target == null) {
            f.completeExceptionally(new IllegalArgumentException("submitTo: null target dispatcher"));
            return f;
        }
        GeometryKey key = new GeometryKey(target, strx, stry, strz, dimX, dimY, dimZ);
        enqueueReq(new Req(ox, oy, oz, key, f, null), f);
        return f;
    }

    /** Common enqueue tail shared by {@link #submitCompact}, {@link #submitCombined} and {@link #submitTo}. */
    private void enqueueReq(Req r, CompletableFuture<double[]> f) {
        if (!running.get()) {
            rejectedCount.incrementAndGet();
            failReq(r, f, new IllegalStateException("GpuChunkBatcher is not running"));
            return;
        }
        if (!queue.offer(r)) {
            // Queue-full used to be COMPLETELY uncounted (it also skips requestCount, so it
            // didn't even show as a requests-vs-served divergence) — a saturated batcher was
            // indistinguishable from a healthy one. Count it; logStats echoes it.
            rejectedCount.incrementAndGet();
            failReq(r, f, new RejectedExecutionException("GpuChunkBatcher queue full"));
            return;
        }
        requestCount.incrementAndGet();
        // A late shutdown could have flipped running after our check; if so, make sure
        // this request can't be orphaned (the drainer may have already exited).
        if (!running.get() && queue.remove(r)) {
            requestCount.decrementAndGet();
            failReq(r, f, new IllegalStateException("GpuChunkBatcher is shutting down"));
        }
    }

    /** Fails a request's density future AND (when present) its climate future — no orphans. */
    private static void failReq(Req r, CompletableFuture<double[]> f, RuntimeException ex) {
        try {
            f.completeExceptionally(ex);
        } catch (Throwable ignored) {
        }
        if (r != null && r.climFuture != null) {
            try {
                r.climFuture.completeExceptionally(ex);
            } catch (Throwable ignored) {
            }
        }
    }

    /** The primary program slot's dispatcher, or {@code null} (aux-only batcher). */
    public GpuBatchDispatcher primaryDispatcher() {
        return dispatcher;
    }

    /**
     * CLIMATE-CHAIN combined submit (the "free ride"): ONE request whose batch computes
     * the chunk's density corner grids AND — via the primary dispatcher's attached
     * climate chain — its packed climate quart grids, in one slot round trip. The
     * density future behaves exactly like {@link #submit}; the climate future completes
     * with the {@code climM*climN} packed climate doubles, or exceptionally when the
     * chain didn't run for this batch (caller funnels the miss). Both futures complete
     * exceptionally on shutdown / queue-full / dispatch failure — never hang.
     */
    public CombinedHandle submitCombined(int ox, int oy, int oz,
                                         int strx, int stry, int strz,
                                         int dimX, int dimY, int dimZ) {
        CompletableFuture<double[]> f = new CompletableFuture<>();
        CompletableFuture<double[]> cf = new CompletableFuture<>();
        if (dispatcher == null) {
            RuntimeException ex = new IllegalStateException(
                    "GpuChunkBatcher has no primary dispatcher — combined submits need one");
            f.completeExceptionally(ex);
            cf.completeExceptionally(ex);
            return new CombinedHandle(f, cf);
        }
        GeometryKey key = new GeometryKey(null, strx, stry, strz, dimX, dimY, dimZ);
        Req r = new Req(ox, oy, oz, key, f, null, cf);
        enqueueReq(r, f);
        return new CombinedHandle(f, cf);
    }

    /** A combined submit's two result futures (density corner grids + climate quart grids). */
    public record CombinedHandle(CompletableFuture<double[]> density, CompletableFuture<double[]> climate) {
    }

    /** Handle for a compact submit: the grids future + the chunk's decide-kernel ids (if any). */
    public static final class SubmitHandle {
        private final CompletableFuture<double[]> future;
        private final Req req;

        SubmitHandle(CompletableFuture<double[]> future, Req req) {
            this.future = future;
            this.req = req;
        }

        public CompletableFuture<double[]> future() {
            return future;
        }

        /** The chunk's 1-byte/block ids, or {@code null}. Read AFTER the future completed. */
        public byte[] ids() {
            return req == null ? null : req.ids;
        }
    }

    public int batchLimit() {
        return batchLimit;
    }

    /** Max time (microseconds) a pooled request waits before its group flushes. */
    public long batchWindowMicros() {
        return batchWindowNanos / 1_000L;
    }

    /** Mean achieved batch size (chunks per dispatch), or 0 when nothing dispatched. */
    public double meanBatchSize() {
        long d = dispatchCount.get();
        return d == 0L ? 0.0 : (double) batchSizeSum.get() / d;
    }

    /**
     * Logs the achieved cross-chunk coalescing: requests pooled, batched dispatches
     * issued, the launch-reduction factor, and the batch-size distribution. Safe to
     * call any time (e.g. at server stop alongside the density-fill summary).
     */
    public void logStats(String when) {
        long req = requestCount.get();
        long disp = dispatchCount.get();
        long served = servedCount.get();
        long rejected = rejectedCount.get();
        if (req == 0L && disp == 0L && rejected == 0L) {
            return;
        }
        double mean = meanBatchSize();
        double reduction = disp == 0L ? 0.0 : (double) served / disp;
        LOGGER.info("[SuperChunk-GPU] [batch] cross-chunk batching '{}' ({}): requests pooled={}, served={}, REJECTED "
                        + "(queue full / not running)={}, batched dispatches={} "
                        + "(batchLimit={}, windowMicros={}); mean batch size={} chunks/dispatch, max={}; "
                        + "GPU-launch reduction ~{}x vs one-dispatch-per-chunk.",
                drainer.getName(), when, req, served, rejected, disp, batchLimit, batchWindowMicros(),
                String.format("%.1f", mean), maxBatchSize.get(), String.format("%.1f", reduction));
        LOGGER.info("[SuperChunk-GPU] [batch] achieved batch-size distribution '{}' ({}): "
                        + "[1]={} [2-4]={} [5-8]={} [9-16]={} [17-32]={} [33-64]={} [65+]={} dispatches.",
                drainer.getName(), when,
                batchHistogram[0].get(), batchHistogram[1].get(), batchHistogram[2].get(),
                batchHistogram[3].get(), batchHistogram[4].get(), batchHistogram[5].get(),
                batchHistogram[6].get());
        if (disp > 0L) {
            String depth = dispatcher != null ? String.valueOf(dispatcher.pipelineDepth()) : "per-target";
            String slotWait = dispatcher != null
                    ? String.format("%.1fms over %d acquires", dispatcher.slotWaitNanos() / 1e6, dispatcher.slotAcquires())
                    : "n/a (aux-only)";
            LOGGER.info("[SuperChunk-GPU] [batch-pipe] pipeline '{}' ({}): depth={}; per-batch host-enqueue={}us, "
                            + "gpu-await={}us, slice+complete={}us; drainer slot-wait {}; "
                            + "in-flight@enqueue [1]={} [2]={} [3]={} [4+]={}.",
                    drainer.getName(), when, depth,
                    String.format("%.1f", pipeEnqueueNanos.get() / 1e3 / disp),
                    String.format("%.1f", pipeAwaitNanos.get() / 1e3 / disp),
                    String.format("%.1f", pipeSliceNanos.get() / 1e3 / disp),
                    slotWait,
                    inFlightHistogram[0].get(), inFlightHistogram[1].get(),
                    inFlightHistogram[2].get(), inFlightHistogram[3].get());
        }
    }

    /** Records one dispatch's achieved batch size {@code K} into the mean/max/histogram. */
    private void recordBatchSize(int k) {
        batchSizeSum.addAndGet(k);
        // monotonic max
        long prev;
        while (k > (prev = maxBatchSize.get()) && !maxBatchSize.compareAndSet(prev, k)) {
            // retry
        }
        int bin;
        if (k <= 1) bin = 0;
        else if (k <= 4) bin = 1;
        else if (k <= 8) bin = 2;
        else if (k <= 16) bin = 3;
        else if (k <= 32) bin = 4;
        else if (k <= 64) bin = 5;
        else bin = 6;
        batchHistogram[bin].incrementAndGet();
    }

    /** Total accepted {@link #submit} calls. */
    public long requestCount() {
        return requestCount.get();
    }

    /** Total {@link GpuBatchDispatcher#batchFill} dispatches issued (coalesced). */
    public long dispatchCount() {
        return dispatchCount.get();
    }

    /** Total chunks completed successfully. */
    public long servedCount() {
        return servedCount.get();
    }

    @Override
    public void close() {
        shutdown();
    }

    /**
     * Stops accepting submits, lets the drainer flush every pending request, joins the
     * batcher thread, fails any straggler that slipped in, then closes the dispatcher.
     * Idempotent.
     */
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return; // already shut down
        }
        drainer.interrupt(); // wake the drainer out of poll(); it will flush + exit
        try {
            drainer.join(SHUTDOWN_JOIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Fail anything that slipped into the queue after running flipped (drainer gone).
        RuntimeException ex = new RuntimeException("GpuChunkBatcher shut down");
        Req r;
        while ((r = queue.poll()) != null) {
            try {
                r.future.completeExceptionally(ex);
            } catch (Throwable ignored) {
            }
        }
        // Stop the completer: poison AFTER the drainer stopped enqueueing, so every
        // in-flight batch is drained + served first (each bounded by one GPU round
        // trip). Longer join than the drainer's — it may have a full pipeline to eat.
        try {
            completionQueue.put(POISON);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            completer.join(SHUTDOWN_JOIN_MILLIS * 2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Abnormal path only (a timed-out join above): fail + release whatever is left
        // so no submitter hangs and no pipeline slot leaks.
        PendingBatch pb;
        while ((pb = completionQueue.poll()) != null) {
            if (pb == POISON) {
                continue;
            }
            failBatchReqs(pb.reqs(), ex);
            try {
                pb.inf().release();
            } catch (Throwable ignored) {
            }
        }
        // The PRIMARY dispatcher is owned by this batcher; aux program-slot dispatchers
        // (submitTo targets) are owned + closed by their registrants AFTER this join.
        if (dispatcher != null) {
            try {
                dispatcher.close();
            } catch (Throwable ignored) {
            }
        }
        if (SHARED == this) {
            SHARED = null;
        }
        LOGGER.info("[SuperChunk-GPU] [batch-agg] batcher '{}' shut down (requests={} dispatches={} served={}).",
                drainer.getName(), requestCount.get(), dispatchCount.get(), servedCount.get());
    }

    // ----------------------------------------------------------------------
    // Drainer (single consumer thread — the ONLY thread that touches the dispatcher).
    // ----------------------------------------------------------------------

    private void drainLoop() {
        final Map<GeometryKey, ArrayDeque<Req>> buckets = new HashMap<>();
        final Map<GeometryKey, Long> deadlineNanos = new HashMap<>();
        while (true) {
            try {
                long wait = computeWaitNanos(buckets, deadlineNanos);
                Req first = null;
                try {
                    first = queue.poll(wait, TimeUnit.NANOSECONDS);
                } catch (InterruptedException ie) {
                    // shutdown signal — fall through; the exit check below handles it.
                }
                if (first != null) {
                    bucketOrFail(first, buckets, deadlineNanos);
                    Req more;
                    while ((more = queue.poll()) != null) {
                        bucketOrFail(more, buckets, deadlineNanos);
                    }
                }
                boolean stopping = !running.get();
                flushReady(buckets, deadlineNanos, stopping);
                if (stopping && queue.isEmpty() && buckets.isEmpty()) {
                    return;
                }
            } catch (Throwable t) {
                LOGGER.warn("[SuperChunk-GPU] [batch-agg] drainer iteration failed — continuing.", t);
            }
        }
    }

    private long computeWaitNanos(Map<GeometryKey, ArrayDeque<Req>> buckets,
                                  Map<GeometryKey, Long> deadlineNanos) {
        if (buckets.isEmpty()) {
            return IDLE_PARK_NANOS;
        }
        long now = System.nanoTime();
        long min = Long.MAX_VALUE;
        for (long d : deadlineNanos.values()) {
            if (d < min) {
                min = d;
            }
        }
        long w = min - now;
        return w < 0 ? 0 : w;
    }

    /**
     * Inserts {@code r} into its geometry bucket, but never lets a failed handoff orphan
     * the request: a {@code queue.poll()} has already removed {@code r}, so if
     * {@link #addToBucket} throws (e.g. OOM allocating the bucket/map node) {@code r}
     * would be in NO bucket and unreachable by the per-bucket flush timeout, hanging its
     * submitter forever. Instead we complete its future exceptionally and let the drainer
     * continue flushing the already-bucketed requests. CompletableFuture completion is
     * idempotent.
     */
    private void bucketOrFail(Req r, Map<GeometryKey, ArrayDeque<Req>> buckets,
                              Map<GeometryKey, Long> deadlineNanos) {
        try {
            addToBucket(r, buckets, deadlineNanos);
        } catch (Throwable t) {
            try {
                r.future.completeExceptionally(new RuntimeException("GpuChunkBatcher bucket insert failed", t));
            } catch (Throwable ignored) {
            }
        }
    }

    private void addToBucket(Req r, Map<GeometryKey, ArrayDeque<Req>> buckets,
                             Map<GeometryKey, Long> deadlineNanos) {
        ArrayDeque<Req> b = buckets.get(r.key);
        if (b == null) {
            b = new ArrayDeque<>();
            buckets.put(r.key, b);
        }
        if (b.isEmpty()) {
            // Anchor the flush deadline to this group's oldest pending request — at its
            // SUBMIT time, not bucket time. Requests that arrived during the previous
            // synchronous dispatch (the steady-state case) have already waited part or all
            // of their window in the queue, so their group flushes on this drainer pass
            // instead of paying a fresh full window on top — the collection window now
            // overlaps the previous dispatch (free batching, no added GPU idle).
            deadlineNanos.put(r.key, r.submitNanos + batchWindowNanos);
        }
        b.add(r);
    }

    private void flushReady(Map<GeometryKey, ArrayDeque<Req>> buckets,
                            Map<GeometryKey, Long> deadlineNanos, boolean forceAll) {
        if (buckets.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        Iterator<Map.Entry<GeometryKey, ArrayDeque<Req>>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<GeometryKey, ArrayDeque<Req>> e = it.next();
            GeometryKey key = e.getKey();
            ArrayDeque<Req> b = e.getValue();
            Long dl = deadlineNanos.get(key);
            boolean expired = forceAll || (dl != null && now >= dl);
            // Each dispatchBatch removes >=1 element, so the bucket strictly shrinks.
            while (!b.isEmpty() && (b.size() >= batchLimit || expired)) {
                dispatchBatch(key, b);
            }
            if (b.isEmpty()) {
                it.remove();
                deadlineNanos.remove(key);
            }
        }
    }

    /**
     * Pops up to {@code batchLimit} live (non-cancelled) requests and ENQUEUES them as
     * ONE pipelined batch — no waiting here: the {@link GpuBatchDispatcher.InFlight}
     * handle goes to the completer thread, and this drainer immediately returns to
     * collecting the next batch. Blocks only inside {@code enqueueBatch} when every
     * pipeline slot is in flight (GPU a full pipeline behind = correct backpressure).
     */
    private void dispatchBatch(GeometryKey key, ArrayDeque<Req> b) {
        int K = 0;
        while (K < batchLimit && !b.isEmpty()) {
            Req r = b.poll();
            if (r.future.isCancelled()) {
                continue; // dropped; its future is already completed (cancelled)
            }
            liveScratch[K++] = r;
        }
        if (K == 0) {
            return; // only cancelled requests in this slice — no GPU work
        }
        // Program slot: the batch's target dispatcher (aux slot) or the primary.
        final GpuBatchDispatcher disp = key.target() != null ? key.target() : this.dispatcher;
        if (disp == null) {
            // Defensive — submit paths reject slotless requests on a primary-less batcher.
            RuntimeException ex = new IllegalStateException("GpuChunkBatcher batch has no dispatcher");
            for (int c = 0; c < K; c++) {
                try {
                    liveScratch[c].future.completeExceptionally(ex);
                } catch (Throwable ignored) {
                }
                liveScratch[c] = null;
            }
            return;
        }
        recordBatchSize(K);
        // Copy the popped requests OUT of the reusable scratch: the completer thread
        // reads them after this method returns, while the scratch is already being
        // reused for the next batch.
        final Req[] batchReqs = new Req[K];
        System.arraycopy(liveScratch, 0, batchReqs, 0, K);
        for (int c = 0; c < K; c++) {
            liveScratch[c] = null;
        }
        // Everything below is guarded: any throwable (OOM on the aux array, an
        // interrupted handoff, an enqueue fault) must still terminate EVERY popped
        // request's future — the Reqs are gone from the bucket, so an orphan would
        // hang its submitter forever.
        GpuBatchDispatcher.InFlight inf = null;
        boolean handedOff = false;
        try {
            final int M = disp.rootCount();
            final int n = key.dimX() * key.dimY() * key.dimZ();
            final int sliceLen = M * n;
            if (originScratch == null || originScratch.length < K * 3) {
                originScratch = new int[K * 3];
            }
            int[] origins = originScratch;
            for (int c = 0; c < K; c++) {
                Req r = batchReqs[c];
                origins[c * 3] = r.ox;
                origins[c * 3 + 1] = r.oy;
                origins[c * 3 + 2] = r.oz;
            }
            // COMPACT-IDS: collect the popped requests' auxes (if any) so the dispatcher
            // can chain the decide kernel; per-chunk id slices are read completer-side.
            CompactIds.ChunkAux[] auxes = null;
            for (int c = 0; c < K; c++) {
                if (batchReqs[c].aux != null) {
                    auxes = new CompactIds.ChunkAux[K];
                    for (int c2 = 0; c2 < K; c2++) {
                        auxes[c2] = batchReqs[c2].aux;
                    }
                    break;
                }
            }
            // CLIMATE CHAIN: any combined request in the batch turns the chained
            // climate program on for the WHOLE batch (climate origins derive from the
            // density origins, so every chunk's slice is valid; slices of plain
            // density requests are simply never read).
            boolean anyClim = false;
            for (int c = 0; c < K; c++) {
                if (batchReqs[c].climFuture != null) {
                    anyClim = true;
                    break;
                }
            }
            long t0 = System.nanoTime();
            inf = disp.enqueueBatch(origins, K,
                    key.strx(), key.stry(), key.strz(),
                    key.dimX(), key.dimY(), key.dimZ(), auxes, anyClim);
            pipeEnqueueNanos.addAndGet(System.nanoTime() - t0);
            dispatchCount.incrementAndGet();
            if (inf != null) {
                recordInFlight(completionQueue.size() + 1);
                completionQueue.put(new PendingBatch(inf, batchReqs, K, sliceLen, auxes != null));
                handedOff = true;
            }
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [batch-agg] batch enqueue failed (K={}).", K, t);
        }
        if (!handedOff) {
            RuntimeException ex = new RuntimeException("GPU batched dispatch failed");
            failBatchReqs(batchReqs, ex);
            if (inf != null) {
                try {
                    inf.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ----------------------------------------------------------------------
    // Completer (single consumer of the pipeline handoff queue).
    // ----------------------------------------------------------------------

    private void completeLoop() {
        while (true) {
            PendingBatch p;
            try {
                p = completionQueue.take();
            } catch (InterruptedException ie) {
                continue; // exit is poison-driven, not interrupt-driven
            }
            if (p == POISON) {
                return;
            }
            try {
                completeBatch(p);
            } catch (Throwable t) {
                LOGGER.warn("[SuperChunk-GPU] [batch-agg] completer iteration failed — continuing.", t);
            }
        }
    }

    /** Waits one in-flight batch, serves its futures from the slot's pinned staging, recycles the slot. */
    private void completeBatch(PendingBatch p) {
        boolean ok;
        long t0 = System.nanoTime();
        try {
            ok = p.inf.await();
        } catch (Throwable t) {
            ok = false;
        }
        pipeAwaitNanos.addAndGet(System.nanoTime() - t0);
        try {
            if (ok) {
                servedCount.addAndGet(p.k);
                long ts = System.nanoTime();
                if (p.anyAux && p.inf.decidePresent() > 0) {
                    long ti = System.nanoTime();
                    for (int c = 0; c < p.k; c++) {
                        // Written BEFORE the future completes (completion is the
                        // happens-before edge for readers of Req.ids).
                        p.reqs[c].ids = p.inf.ids(c);
                    }
                    CompactIds.recordDecide(p.inf.decidePresent(), p.inf.decideKernelNs(),
                            System.nanoTime() - ti);
                }
                final boolean climOk = p.inf.climateAvailable();
                final int climLen = p.inf.climateSliceLenLive();
                for (int c = 0; c < p.k; c++) {
                    Req r = p.reqs[c];
                    // CLIMATE CHAIN: serve the climate future FIRST — the biomes-seam
                    // Completable gates on it, and its consumer runs a stage EARLIER
                    // than the density consumer.
                    if (r.climFuture != null) {
                        if (climOk) {
                            double[] cs = new double[climLen];
                            p.inf.readClimSlice(c, cs);
                            r.climFuture.complete(cs);
                        } else {
                            try {
                                r.climFuture.completeExceptionally(new IllegalStateException(
                                        "climate chain unavailable for this batch"));
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    double[] s = new double[p.sliceLen];
                    p.inf.readSlice(c, s);
                    r.future.complete(s); // no-op if the future was cancelled meanwhile
                }
                pipeSliceNanos.addAndGet(System.nanoTime() - ts);
            } else {
                failBatchReqs(p.reqs, new RuntimeException("GPU batched dispatch failed"));
            }
        } catch (Throwable t) {
            // completeExceptionally is idempotent, so failing the whole batch also
            // covers any already-completed heads — no future is ever orphaned.
            LOGGER.warn("[SuperChunk-GPU] [batch-agg] batch completion failed (K={}).", p.k, t);
            failBatchReqs(p.reqs, new RuntimeException("GpuChunkBatcher completion aborted", t));
        } finally {
            try {
                p.inf.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void failBatchReqs(Req[] reqs, RuntimeException ex) {
        if (reqs == null) {
            return;
        }
        for (Req r : reqs) {
            if (r != null) {
                try {
                    r.future.completeExceptionally(ex);
                } catch (Throwable ignored) {
                }
                if (r.climFuture != null) {
                    try {
                        r.climFuture.completeExceptionally(ex);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private void recordInFlight(int depth) {
        int bin = depth <= 1 ? 0 : depth == 2 ? 1 : depth == 3 ? 2 : 3;
        inFlightHistogram[bin].incrementAndGet();
    }

    /** One enqueued batch riding the pipeline from drainer to completer. */
    private record PendingBatch(GpuBatchDispatcher.InFlight inf, Req[] reqs, int k, int sliceLen,
                                boolean anyAux) {
    }

    // ----------------------------------------------------------------------
    // Value types.
    // ----------------------------------------------------------------------

    /**
     * Grouping key — only requests with the SAME program slot ({@code target};
     * {@code null} = this batcher's primary dispatcher, compared by identity since
     * {@link GpuBatchDispatcher} does not override {@code equals}) AND the same
     * geometry share a batch.
     */
    private record GeometryKey(GpuBatchDispatcher target,
                               int strx, int stry, int strz, int dimX, int dimY, int dimZ) {
    }

    private static final class Req {
        final int ox;
        final int oy;
        final int oz;
        final GeometryKey key;
        final CompletableFuture<double[]> future;
        /** COMPACT-IDS: the chunk's decide-kernel aux, or null (plain corner request). */
        final CompactIds.ChunkAux aux;
        /**
         * CLIMATE CHAIN: completed with the chunk's packed {@code climM*climN} climate
         * quart grids when the batch ran the chained climate program, exceptionally when
         * it could not (caller funnels the miss); {@code null} = plain density request.
         */
        final CompletableFuture<double[]> climFuture;
        /**
         * SUBMIT time (nanoTime at enqueue): the documented flush-window contract anchors
         * at the group's OLDEST PENDING REQUEST, so the deadline must be derived from when
         * the submitter enqueued, NOT from when the drainer eventually bucketed it (which
         * can be a full synchronous dispatch later — silently adding up to one dispatch
         * duration of extra wait per request and idling the GPU during every window).
         */
        final long submitNanos;
        /**
         * COMPACT-IDS: this chunk's 1-byte/block ids, written by the drainer BEFORE it
         * completes {@link #future} (completion is the happens-before edge for readers).
         */
        volatile byte[] ids;

        Req(int ox, int oy, int oz, GeometryKey key, CompletableFuture<double[]> future,
            CompactIds.ChunkAux aux) {
            this(ox, oy, oz, key, future, aux, null);
        }

        Req(int ox, int oy, int oz, GeometryKey key, CompletableFuture<double[]> future,
            CompactIds.ChunkAux aux, CompletableFuture<double[]> climFuture) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.key = key;
            this.future = future;
            this.aux = aux;
            this.climFuture = climFuture;
            this.submitNanos = System.nanoTime();
        }
    }
}
