package dev.superchunk.gpu.dfc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SuperChunk GPU — the handle returned by a submitted cross-chunk batch prefetch
 * ({@link GpuBatchPrefetch#submitForChunk density} / {@link GpuClimatePrefetch#submitForChunk
 * climate}).
 *
 * <p>It exists so the two CONSUMERS of a prefetch submit can share one implementation of
 * "submit the chunk's request, deposit the batched result in the store when it lands":
 * <ul>
 *   <li>the C2ME chunk-system status seam ({@code VanillaWorldGenerationDelegate}), which wraps
 *       the handle in an RxJava {@code Completable} so the worker is RELEASED during the wait, and</li>
 *   <li>the Distant Horizons batch-generator seam
 *       ({@link dev.superchunk.compat.DhWorldGenBridge}), which submits a whole generation GROUP
 *       and then BLOCKS its own DH thread on {@link #done()} — DH's steps must finish
 *       synchronously ({@code BatchGenerationEnvironment.confirmFutureWasRunSynchronously}), and a
 *       DH worldgen thread is DH's to block.</li>
 * </ul>
 *
 * <p>{@link #done()} completes (never exceptionally) once the deposit attempt has finished —
 * success or failure — so a waiter is always released. {@link #cancelIfUnresolved()} is the
 * cancellation half: it cancels the in-flight batch request and evicts any store entry, but ONLY
 * when the result has not already been deposited. That distinction matters because RxJava fires a
 * {@code Completable}'s cancellable on NORMAL completion too, and evicting there would throw away
 * the grid the fill is about to consume.
 */
public final class GpuPrefetchHandle {

    private final CompletableFuture<Void> done;
    private final CompletableFuture<double[]> future;
    private final AtomicBoolean resolved;
    private final Runnable evict;

    GpuPrefetchHandle(CompletableFuture<Void> done, CompletableFuture<double[]> future,
                      AtomicBoolean resolved, Runnable evict) {
        this.done = done;
        this.future = future;
        this.resolved = resolved;
        this.evict = evict;
    }

    /**
     * Completes — always normally — when the batched result has been deposited (or the deposit
     * was abandoned because the dispatch failed/timed out). Waiting on it is optional: the
     * deposit happens on the batcher's completer thread regardless of who is listening.
     */
    public CompletableFuture<Void> done() {
        return this.done;
    }

    /** True once the batch result arrived and the deposit attempt ran. */
    public boolean isResolved() {
        return this.resolved.get();
    }

    /**
     * Cancels this prefetch and evicts its (not yet existing) store entry — but only if the
     * result has NOT already been deposited. Safe to call more than once; never throws.
     */
    public void cancelIfUnresolved() {
        if (this.resolved.get()) {
            return;
        }
        try {
            this.future.cancel(false);
        } catch (Throwable ignored) {
        }
        try {
            this.evict.run();
        } catch (Throwable ignored) {
        }
    }
}
