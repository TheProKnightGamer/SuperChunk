package dev.superchunk.com.ishland.c2me.rewrites.chunkio.common;

import com.ibm.asyncutil.util.Either;
import dev.superchunk.com.ishland.c2me.base.common.GlobalExecutors;
import dev.superchunk.com.ishland.c2me.base.common.structs.RawByteArrayOutputStream;
import dev.superchunk.com.ishland.c2me.base.common.util.SneakyThrow;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IRegionBasedStorage;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IRegionFile;
import io.netty.util.internal.PlatformDependent;
import it.unimi.dsi.fastutil.longs.Long2ReferenceLinkedOpenHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

/**
 * SuperChunk: the worker is created on demand and retires after {@link #IDLE_RETIRE_MS} of idleness,
 * instead of being one OS thread pinned for the lifetime of the object.
 *
 * <p>Every {@code ChunkStorage}/{@code SimpleRegionStorage} constructor is redirected to build one of
 * these (see the {@code chunkio.mixin.Mixin*Storage} family), so the thread count tracks the number of
 * live storages. Vanilla's {@code IOWorker} instead shares {@code Util.ioPool()}, a cached pool whose
 * threads expire after 60s — so a mod that constructs storages and never closes them costs vanilla only
 * a few objects, while it cost us one permanently parked thread each. Distant Horizons does exactly
 * that: a watchdog dump from issue #7 has <b>435</b> live "C2ME Storage #n" threads, interleaved in the
 * thread-id order with repeated triplets of DH level threads, i.e. ~145 rebuilt DH levels each leaking
 * a chunk/poi/entity storage. Hundreds of parked threads plus their reserved stacks are the "slowly
 * freezes, looks like a memory leak" reports.
 *
 * <p>Retiring restores vanilla's shape: an idle storage costs no thread, and the next task transparently
 * starts a fresh worker. {@code -Dsuperchunk.io.storageIdleRetireMillis=0} disables retirement.
 */
public class C2MEStorageThread implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger("C2ME Storage");

    private static final AtomicLong SERIAL = new AtomicLong(0);

    // SuperChunk: optional override of the region Deflate compression level (default -1 =
    // vanilla level 6). The serialize+deflate runs on the saturated c2me-worker pool, so a
    // cheaper level frees worker CPU for worldgen on a CPU-core-bound pregen (~+0.4-0.5% at
    // level 1). Any 0-9 still emits a standard zlib stream readable by vanilla (region
    // format ID unchanged). Only applies to DEFLATE-format (id 2) regions. Default OFF.
    private static final int SC_DEFLATE_LEVEL = scResolveDeflateLevel();

    // SuperChunk: read + clamp the optional deflate level to a value java.util.zip.Deflater
    // accepts (0..9). Without the clamp a mistyped positive value (e.g. 10) makes EVERY
    // chunk-save's `new Deflater(level)` throw IllegalArgumentException, failing all writes.
    // < 0 stays the OFF sentinel (vanilla level 6); anything > 9 is capped to 9 (with a warn).
    private static int scResolveDeflateLevel() {
        int lvl = Integer.getInteger("superchunk.io.deflateLevel", -1);
        if (lvl < 0) {
            return -1;
        }
        if (lvl > 9) {
            LOGGER.warn("superchunk.io.deflateLevel={} is out of range; clamping to 9.", lvl);
            return 9;
        }
        return lvl;
    }

    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

    private final RegionFileStorage storage;
    private final AtomicInteger taskSize = new AtomicInteger();
    private final Long2ReferenceLinkedOpenHashMap<Either<CompoundTag, byte[]>> writeBacklog = new Long2ReferenceLinkedOpenHashMap<>();
    private final Long2ReferenceLinkedOpenHashMap<Either<CompoundTag, byte[]>> cache = new Long2ReferenceLinkedOpenHashMap<>();
    private final Queue<Runnable> pendingTasks = PlatformDependent.newMpscQueue();
    private final Executor executor = command -> {
        if (Thread.currentThread() == this.worker) {
            command.run();
        } else {
            final boolean empty = this.taskSize.getAndIncrement() == 0;
            pendingTasks.add(command);
            if (empty) this.wakeUp();
            ensureWorker(false); // no lock held here — see ensureWorker's note on lock ordering
        }
    };
    private final java.util.ArrayList<CompletableFuture<Void>> writeFutures = new java.util.ArrayList<>();
    private final Object sync = new Object();

    /** How long a worker may sit idle before it exits; {@code <= 0} pins it forever (legacy behaviour). */
    private static final long IDLE_RETIRE_MS = Long.getLong("superchunk.io.storageIdleRetireMillis", 60_000L);
    /** How long an idle storage thread polls before sleeping ({@code -Dsuperchunk.io.storageSpinMicros}, default 2000). */
    private static final long SPIN_NANOS = 1000L * Long.getLong("superchunk.io.storageSpinMicros", 2_000L);
    /**
     * Warn once at this many simultaneously-open storages. Three per loaded dimension (chunk / poi /
     * entities) is normal, so a big dimension-heavy pack can legitimately reach a few dozen — the
     * threshold is set well above that and the message is phrased as a hint, not an accusation.
     */
    private static final int LEAK_WARN_THRESHOLD = Integer.getInteger("superchunk.io.storageLeakWarnAt", 192);

    private static final AtomicInteger LIVE_STORAGES = new AtomicInteger();
    private static final AtomicBoolean LEAK_WARNED = new AtomicBoolean(false);

    private final String name;
    /**
     * Priority and context ClassLoader the worker runs at, captured from the CONSTRUCTING thread.
     * {@code new Thread()} copies both from whoever calls it, which used to be the constructor and is
     * now whichever thread happens to submit the first task (a worldgen worker, a CompletableFuture
     * completion thread, the server thread...) — and could differ between restarts of one storage.
     * Capturing them here keeps every worker identical to the old eager thread.
     */
    private final int priority;
    private final ClassLoader contextClassLoader;
    /** The running worker, or {@code null} while retired. Guarded for writes by {@link #workerLock}. */
    private volatile Thread worker;
    private final Object workerLock = new Object();

    public C2MEStorageThread(RegionStorageInfo arg, Path path, boolean dsync) {
        this.storage = new RegionFileStorage(arg, path, dsync);
        this.name = "C2ME Storage #%d".formatted(SERIAL.incrementAndGet());
        this.priority = Thread.currentThread().getPriority();
        this.contextClassLoader = Thread.currentThread().getContextClassLoader();
        final int live = LIVE_STORAGES.incrementAndGet();
        if (live >= LEAK_WARN_THRESHOLD && LEAK_WARNED.compareAndSet(false, true)) {
            LOGGER.warn("{} chunk storages are open at once ({} was the last). Three per loaded dimension is "
                    + "normal; far more than that usually means some mod constructs ChunkStorage/"
                    + "SimpleRegionStorage instances without closing them, and each one holds open region "
                    + "files. SuperChunk retires their idle IO threads, but any such leak is upstream.",
                    live, this.name);
        }
        // The worker starts on the first task (see #executor) rather than here, so a storage that is
        // built and then abandoned never costs a thread.
    }

    /**
     * Start a worker if none is running. Cheap and lock-free on the hot path (the volatile read).
     *
     * <p>Never called while {@link #sync} is held: the retirement path takes {@code sync} then
     * {@link #workerLock}, so acquiring them the other way round would deadlock. {@code #executor}
     * therefore does its {@link #wakeUp()} first and this second, with neither lock held across the
     * other.
     *
     * @param forShutdown start one even though {@link #closing} is set, to run the final flush
     */
    private void ensureWorker(boolean forShutdown) {
        if (this.worker != null) return;
        if (!forShutdown && this.closing.get()) return; // the shutdown worker will drain what is queued
        synchronized (this.workerLock) {
            if (this.worker != null) return;
            if (this.closeFuture.isDone()) return; // fully closed: never resurrect
            final Thread t = new Thread(this, this.name);
            t.setDaemon(true);
            t.setPriority(this.priority);
            t.setContextClassLoader(this.contextClassLoader);
            // Self-heal instead of wedging: if run() dies on an uncaught throwable the slot must be
            // released, or `worker` stays non-null forever, ensureWorker short-circuits for good and
            // close() never completes — blocking the world-save thread on its join().
            t.setUncaughtExceptionHandler((thread, e) -> {
                LOGGER.error("Thread %s died".formatted(thread), e);
                this.releaseWorker(thread);
            });
            this.worker = t;
            try {
                t.start();
            } catch (Throwable e) {
                // OutOfMemoryError: unable to create native thread is exactly the state this class
                // exists to avoid. Publishing `worker` before a failed start would wedge it forever.
                this.worker = null;
                throw e;
            }
        }
    }

    /** Release the worker slot if {@code thread} still owns it, so a later task can start a fresh one. */
    private void releaseWorker(Thread thread) {
        synchronized (this.workerLock) {
            if (this.worker == thread) {
                this.worker = null;
            }
        }
    }

    /**
     * Give up this worker if there is provably nothing to do.
     *
     * <p>No task can be lost across a retirement, and the argument rests on two invariants that are
     * easy to break by accident — do not change either without re-deriving this:
     * <ol>
     *   <li><b>The worker is the only thread that decrements {@code taskSize}</b>, and never while
     *       holding {@code sync}. So once the check below reads 0, every later operation on that
     *       counter is an increment, and the FIRST of them sees 0 and therefore takes the
     *       {@code empty} branch in {@code #executor} — i.e. it definitely calls {@link #wakeUp()}.
     *       (Producers that skip {@code wakeUp()} may read a stale non-null {@code worker}; that is
     *       harmless, because the replacement started for the first one drains the whole queue.)</li>
     *   <li><b>{@code wakeUp()} takes {@code sync}</b>, which this method is called while holding.
     *       That producer therefore cannot enter {@code sync} before we leave it, so our
     *       {@code worker = null} happens-before its {@code wakeUp()}, which happens-before its
     *       {@link #ensureWorker(boolean)} — and it sees {@code null} and starts a replacement.</li>
     * </ol>
     *
     * <p>The same {@code workerLock} release/acquire pair is also what safely hands the plain
     * (non-thread-safe) {@code writeBacklog} / {@code cache} / {@code writeFutures} from one worker
     * generation to the next: old worker's writes -> monitorexit here -> monitorenter in
     * {@code ensureWorker} -> {@code Thread.start()} -> new worker.
     */
    private boolean retireWorker() {
        synchronized (this.workerLock) {
            if (this.taskSize.get() != 0 || this.closing.get() || hasPendingTasks()) return false;
            this.worker = null;
            return true;
        }
    }

    @Override
    public void run() {
        main_loop:
        while (true) {
            boolean hasWork = false;
            hasWork |= pollTasks();

            runWriteFutureGC();

            if (!hasWork) {
                if (this.closing.get()) {
                    flush0(true);
                    try {
                        this.storage.close();
                    } catch (Throwable t) {
                        LOGGER.error("Error closing storage", t);
                    }
                    LIVE_STORAGES.decrementAndGet();
                    // Complete first, then release the worker slot: ensureWorker(true) bails on either
                    // a live worker or a done closeFuture, so a concurrent close() can never start a
                    // second worker that would re-run this block.
                    this.closeFuture.complete(null);
                    synchronized (this.workerLock) {
                        this.worker = null;
                    }
                    break;
                } else {
                    // Spin briefly before sleeping: within a burst the next task usually arrives in
                    // well under a millisecond. SuperChunk: bounded to SPIN_NANOS (was 5000 parks,
                    // ~0.3 s with timer slack, so each of a dimension's three storage threads woke
                    // ~16k times a second after every burst). Producers wake us through sync anyway.
                    if (!pollTasks()) {
                        Thread.interrupted(); // clear interrupt flag
                        final long spinUntil = System.nanoTime() + SPIN_NANOS;
                        while (System.nanoTime() - spinUntil < 0L) {
                            if (pollTasks() || this.closing.get()) continue main_loop;
                            LockSupport.parkNanos("Spin-waiting for tasks", 10_000);
                        }
                    }
                    synchronized (sync) {
                        if (this.taskSize.get() != 0 || this.closing.get()) continue main_loop;
                        if (IDLE_RETIRE_MS <= 0L) {
                            try {
                                sync.wait(); // retirement disabled: park until woken, as before
                            } catch (InterruptedException ignored) {
                            }
                            continue main_loop;
                        }
                        // Deadline loop, so a spurious wakeup or an interrupt cannot retire early and
                        // turn the idle timeout into thread-churn.
                        final long deadline = System.nanoTime() + IDLE_RETIRE_MS * 1_000_000L;
                        long remaining;
                        while ((remaining = deadline - System.nanoTime()) > 0L) {
                            if (this.taskSize.get() != 0 || this.closing.get()) continue main_loop;
                            try {
                                sync.wait(Math.max(1L, remaining / 1_000_000L));
                            } catch (InterruptedException ignored) {
                            }
                        }
                        if (this.taskSize.get() != 0 || this.closing.get()) continue main_loop;
                        if (retireWorker()) {
                            LOGGER.debug("Storage thread {} retired after {} ms idle", this.name, IDLE_RETIRE_MS);
                            return; // the next task starts a fresh worker
                        }
                    }
                }
            }
        }
        LOGGER.info("Storage thread {} stopped", this.name);
    }

    private boolean pollTasks() {
        boolean hasWork = false;
        hasWork = handleTasks() || hasWork;
        hasWork = writeBacklog() || hasWork;
        return hasWork;
    }

    private boolean hasPendingTasks() {
        return !this.pendingTasks.isEmpty() || !this.writeBacklog.isEmpty();
    }

    private void wakeUp() {
        synchronized (sync) {
            sync.notifyAll();
        }
    }

    /**
     * Read chunk data from storage
     * @param pos target pos
     * @param scanner if null then ignored, if non-null then used and produce null future
     * @return future
     */
    public CompletableFuture<CompoundTag> getChunkData(long pos, StreamTagVisitor scanner) {
        final CompletableFuture<CompoundTag> future = new CompletableFuture<>();
        if (this.closing.get()) {
            future.completeExceptionally(new CancellationException());
            return future.thenApply(Function.identity());
        }
        this.executor.execute(() -> this.read0(pos, future, scanner));
//        future.thenApply(Function.identity()).orTimeout(60, TimeUnit.SECONDS).exceptionally(throwable -> {
//            if (throwable instanceof TimeoutException) {
//                LOGGER.warn("Chunk read at pos {} took too long (> 1min)", new ChunkPos(pos).toLong());
//            }
//            return null;
//        });
        return future
                .thenApply(Function.identity());
    }

    public void setChunkData(long pos, @Nullable CompoundTag nbt) {
        this.executor.execute(() -> this.write0(pos, nbt != null ? Either.left(nbt) : null));
    }

    public void setChunkData(long pos, @Nullable byte[] data) {
        this.executor.execute(() -> this.write0(pos, data != null ? Either.right(data) : null));
    }

    public CompletableFuture<Void> flush(boolean sync) {
        return CompletableFuture.runAsync(() -> flush0(sync), this.executor);
    }

    private void flush0(boolean sync) {
        try {
            while (true) {
                runWriteFutureGC();
                if (handleTasks()) continue;
                if (writeBacklog()) continue;

                break;
            }
            flushBacklog();
            if (sync) this.storage.flush();
        } catch (Throwable t) {
            LOGGER.error("Error flushing storage", t);
        }
    }

    public RegionStorageInfo getStorageKey() {
        return this.storage.info();
    }

    public CompletableFuture<Void> close() {
        this.closing.set(true);
        this.wakeUp();
        // The worker may have retired while idle, in which case nobody is left to run the final
        // flush + storage.close() and complete closeFuture — and callers join() on it.
        this.ensureWorker(true);
        return this.closeFuture.thenApply(Function.identity());
    }

    private boolean handleTasks() {
        boolean hasWork = false;
        Runnable runnable;
        while ((runnable = this.pendingTasks.poll()) != null) {
            hasWork = true;
            this.taskSize.decrementAndGet();
            try {
                runnable.run();
            } catch (Throwable t) {
                LOGGER.error("Error while executing task", t);
            }
        }
        return hasWork;
    }

    private void write0(long pos, Either<CompoundTag, byte[]> nbt) {
        this.cache.put(pos, nbt);
        this.writeBacklog.put(pos, nbt);
    }

    private void read0(long pos, CompletableFuture<CompoundTag> future, StreamTagVisitor scanner) {
        if (this.cache.containsKey(pos)) {
            final Either<CompoundTag, byte[]> cached = this.cache.get(pos);
            if (cached == null) {
                future.complete(null);
            } else if (cached.left().isPresent()) {
                if (scanner != null) {
                    // SCAN PATH — runs HERE, on this storage thread, NOT on prioritizedScheduler.
                    // See the note on scheduleChunkRead: a scan future is the one future in this
                    // class that a worldgen worker BLOCKS on, and prioritizedScheduler IS the
                    // worldgen worker pool, so scheduling it there deadlocks the pool against
                    // itself. Scanners are field-selective and cheap, and vanilla's own IOWorker
                    // likewise parses scans on its IO thread.
                    try {
                        cached.left().get().acceptAsRoot(scanner);
                        future.complete(null);
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                } else {
                    future.complete(cached.left().get());
                }
            } else if (scanner != null) {
                // SCAN PATH — inline, same reasoning as above.
                try {
                    NbtIo.parse(new DataInputStream(new ByteArrayInputStream(cached.right().get())),
                            scanner, NbtAccounter.unlimitedHeap());
                    future.complete(null);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            } else {
                CompletableFuture.supplyAsync(() -> {
                            try {
                                final DataInputStream input = new DataInputStream(new ByteArrayInputStream(cached.right().get()));
                                final CompoundTag compound = NbtIo.read(input);
                                return compound;
                            } catch (IOException e) {
                                SneakyThrow.sneaky(e);
                                return null; // unreachable
                            }
                        }, GlobalExecutors.prioritizedScheduler.executor(16))
                        .thenAccept(future::complete)
                        .exceptionally(throwable -> {
                            future.completeExceptionally(throwable);
                            return null;
                        });
            }
        } else {
            scheduleChunkRead(pos, future, scanner);
        }
    }

    private boolean writeBacklog() {
        if (!this.writeBacklog.isEmpty()) {
            final long pos = this.writeBacklog.firstLongKey();
            final Either<CompoundTag, byte[]> nbt = this.writeBacklog.removeFirst();
            writeChunk(pos, nbt);
            return true;
        }
        return false;
    }

    private void runWriteFutureGC() {
        this.writeFutures.removeIf(CompletableFuture::isDone);
    }

    private void flushBacklog() {
        while (!this.writeFutures.isEmpty()) {
            while (writeBacklog()) ;
            runWriteFutureGC();
            final CompletableFuture<Void> allFuture = CompletableFuture.allOf(this.writeFutures.stream()
                    .map(future -> future.exceptionally(unused -> null))
                    .distinct()
                    .toArray(CompletableFuture[]::new));
            while (!allFuture.isDone()) {
                handleTasks();
            }
            runWriteFutureGC();
        }
    }

    /**
     * Reads a chunk off disk.
     *
     * <p><b>A scan ({@code scanner != null}) is parsed INLINE on this storage thread and must never
     * be handed to {@code prioritizedScheduler} — that is a deadlock, not a preference.</b> The
     * scan future is the only future this class produces that a caller BLOCKS on: vanilla's
     * {@code Blender.of()} -> {@code ChunkStorage.isOldChunkAround} -> {@code IOWorker
     * .isOldChunkAround} joins it, and that join runs on a worldgen worker. When lighting is
     * externally managed, {@code GlobalExecutors.prioritizedScheduler} IS that same worldgen worker
     * pool (see GlobalExecutors: the field is null and its sole dereference is @Overwrite-routed to
     * C2ME's scheduler). So scheduling the parse there makes the pool wait on work only the pool
     * can run: once enough chunks blend at once, every worker blocks in isOldChunkAround and
     * nothing ever completes.
     *
     * <p>Diagnosed 2026-08-15 on the Forge 1.20.1 port running a 110-mod client: 170/170 workers
     * blocked in isOldChunkAround, all storage threads idle (they had already dispatched the
     * parse), server thread parked in ServerChunkCache.getChunk, one tick growing 40s -> 201s.
     * Triggered by a mod force-loading chunks synchronously from a tick event, which needs an
     * existing world with old chunks to blend — which is why a fresh-world pregen never reproduces
     * it, and why this sat latent in both trees.
     *
     * <p>Inline is the right home for it: the disk read on the line above already happens on this
     * thread, scans are field-selective and cheap ({@code CollectFields} short-circuits), and
     * vanilla's own {@code IOWorker} likewise parses scans on its IO thread. Non-scan reads keep
     * the prioritized scheduler, since nothing blocks on those.
     */
    private void scheduleChunkRead(long pos, CompletableFuture<CompoundTag> future, StreamTagVisitor scanner) {
        try {
            final ChunkPos pos1 = new ChunkPos(pos);
            final RegionFile regionFile = ((IRegionBasedStorage) this.storage).invokeGetRegionFile(pos1);
            final DataInputStream chunkInputStream = regionFile.getChunkDataInputStream(pos1);
            if (chunkInputStream == null) {
                future.complete(null);
                return;
            }
            if (scanner != null) {
                try (DataInputStream inputStream = chunkInputStream) {
                    NbtIo.parse(inputStream, scanner, NbtAccounter.unlimitedHeap());
                    future.complete(null);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
                return;
            }
            CompletableFuture.supplyAsync(() -> {
                try {
                    try (DataInputStream inputStream = chunkInputStream) {
                        return NbtIo.read(inputStream);
                    }
                } catch (Throwable t) {
                    SneakyThrow.sneaky(t);
                    return null; // Unreachable anyway
                }
            }, GlobalExecutors.prioritizedScheduler.executor(16)).handle((compound, throwable) -> {
                if (throwable != null) future.completeExceptionally(throwable);
                else future.complete(compound);
                return null;
            });
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
    }

    private void writeChunk(long pos, Either<CompoundTag, byte[]> nbt) {
        if (nbt == null) {
            if (this.cache.get(pos) == null) {
                try {
                    final ChunkPos pos1 = new ChunkPos(pos);
                    final RegionFile regionFile = ((IRegionBasedStorage) this.storage).invokeGetRegionFile(pos1);
                    regionFile.clear(pos1);
                } catch (Throwable t) {
                    LOGGER.error("Error writing chunk %s".formatted(new ChunkPos(pos)), t);
                }
                this.cache.remove(pos);
            }
        } else {
            RegionFileVersion compressionFormat;
            {
                final ChunkPos pos1 = new ChunkPos(pos);
                try {
                    final RegionFile regionFile = ((IRegionBasedStorage) this.storage).invokeGetRegionFile(pos1);
                    compressionFormat = ((IRegionFile) regionFile).getCompressionFormat();
                } catch (Throwable t) {
                    LOGGER.warn("Failed to get compression format for chunk %s".formatted(pos1), t);
                    compressionFormat = RegionFileVersion.getSelected();
                }
            }
            RegionFileVersion finalCompressionFormat = compressionFormat;
            final CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
                try {
                    final RawByteArrayOutputStream out = new RawByteArrayOutputStream(8096);
                    // TODO [VanillaCopy] RegionFile.ChunkBuffer
                    out.write(0);
                    out.write(0);
                    out.write(0);
                    out.write(0);
                    out.write(finalCompressionFormat.getId());
                    // SuperChunk: optionally deflate at a cheaper level (still a valid zlib
                    // stream under the same region format id) to free worker-pool CPU.
                    // The Deflater is created with our own level, so DeflaterOutputStream.close()
                    // does NOT call end() on it (usesDefaultDeflater=false) — we must end() it
                    // ourselves in a finally, else its native zlib state leaks until GC (one per
                    // chunk save). The finally runs AFTER the stream chain closes, so finish()
                    // has already flushed the compressed output into `out`.
                    java.util.zip.Deflater scDeflater = null;
                    final java.io.OutputStream compStream;
                    if (SC_DEFLATE_LEVEL >= 0 && finalCompressionFormat.getId() == 2) {
                        scDeflater = new java.util.zip.Deflater(SC_DEFLATE_LEVEL);
                        compStream = new java.io.BufferedOutputStream(new java.util.zip.DeflaterOutputStream(out, scDeflater));
                    } else {
                        compStream = finalCompressionFormat.wrap(out);
                    }
                    try (DataOutputStream dataOutputStream = new DataOutputStream(compStream)) {
                        if (nbt.left().isPresent()) {
                            NbtIo.write(nbt.left().get(), dataOutputStream);
                        } else {
                            dataOutputStream.write(nbt.right().get());
                        }
                    } finally {
                        if (scDeflater != null) scDeflater.end();
                    }
                    return out;
                } catch (Throwable t) {
                    SneakyThrow.sneaky(t);
                    return null; // Unreachable anyway
                }
            }, GlobalExecutors.prioritizedScheduler.executor(16)).thenAcceptAsync(bytes -> {
                if (nbt == this.cache.get(pos)) { // only write if match to avoid overwrites
                    try {
                        final ChunkPos pos1 = new ChunkPos(pos);
                        final RegionFile regionFile = ((IRegionBasedStorage) this.storage).invokeGetRegionFile(pos1);
                        ByteBuffer byteBuffer = bytes.asByteBuffer();
                        // TODO [VanillaCopy] RegionFile.ChunkBuffer
                        byteBuffer.putInt(0, bytes.size() - 5 + 1);
                        ((IRegionFile) regionFile).invokeWriteChunk(pos1, byteBuffer);
                    } catch (Throwable t) {
                        SneakyThrow.sneaky(t);
                    }
                    this.cache.remove(pos);
                }
            }, this.executor).handleAsync((unused, throwable) -> {
                if (throwable != null) {
                    LOGGER.error("Error writing chunk %s".formatted(new ChunkPos(pos)), throwable);
                    // TODO error retry
                    // The serialize/compress stage threw, so the thenAcceptAsync stage that
                    // normally evicts the cache entry was bypassed. Evict here (still on the
                    // storage thread) so a permanently-failed write does not pin its
                    // CompoundTag/byte[] in the cache forever. Guard on identity so a newer
                    // write for the same pos that arrived meanwhile is not dropped.
                    if (nbt == this.cache.get(pos)) {
                        this.cache.remove(pos);
                    }
                }
                return null;
            }, this.executor);
            this.writeFutures.add(future);
        }
    }

}
