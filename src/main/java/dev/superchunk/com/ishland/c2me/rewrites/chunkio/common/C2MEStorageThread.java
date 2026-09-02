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

public class C2MEStorageThread extends Thread {

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
        if (Thread.currentThread() == this) {
            command.run();
        } else {
            final boolean empty = this.taskSize.getAndIncrement() == 0;
            pendingTasks.add(command);
            if (empty) this.wakeUp();
        }
    };
    private final java.util.ArrayList<CompletableFuture<Void>> writeFutures = new java.util.ArrayList<>();
    private final Object sync = new Object();

    public C2MEStorageThread(RegionStorageInfo arg, Path path, boolean dsync) {
        this.storage = new RegionFileStorage(arg, path, dsync);
        this.setName("C2ME Storage #%d".formatted(SERIAL.incrementAndGet()));
        this.setDaemon(true);
        this.setUncaughtExceptionHandler((t, e) -> LOGGER.error("Thread %s died".formatted(t), e));
        this.start();
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
                    this.closeFuture.complete(null);
                    break;
                } else {
                    // attempt to spin-wait before sleeping
                    if (!pollTasks()) {
                        Thread.interrupted(); // clear interrupt flag
                        for (int i = 0; i < 5000; i ++) {
                            if (pollTasks() || this.closing.get()) continue main_loop;
                            LockSupport.parkNanos("Spin-waiting for tasks", 10_000); // 100us
                        }
                    }
                    synchronized (sync) {
                        if (this.taskSize.get() != 0 || this.closing.get()) continue main_loop;
                        try {
                            sync.wait();
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
        }
        LOGGER.info("Storage thread {} stopped", this);
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
