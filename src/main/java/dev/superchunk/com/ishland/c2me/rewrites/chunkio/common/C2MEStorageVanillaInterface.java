package dev.superchunk.com.ishland.c2me.rewrites.chunkio.common;

import com.google.common.base.Preconditions;
import dev.superchunk.com.ishland.c2me.base.common.theinterface.IDirectStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Forge 1.20.1 port: IOWorker ctor is {@code (Path, boolean, String)} (no RegionStorageInfo);
 * the 1.20.5+ {@code storageInfo()} override is deleted (plan §1.4).
 */
public class C2MEStorageVanillaInterface extends IOWorker implements IDirectStorage {

    /**
     * SuperChunk: set once this replaces vanilla's IOWorker, so code outside the chunk storage can
     * tell that vanilla's write path (its mailbox) is not there ({@code ByePregenCompat}).
     */
    public static volatile boolean inUse;

    private final C2MEStorageThread backend;

    public C2MEStorageVanillaInterface(Path path, boolean dsync, String name) {
        super(path, dsync, name);
        this.backend = new C2MEStorageThread(path, dsync);
        inUse = true;
    }

    @Override
    public CompletableFuture<Void> store(ChunkPos pos, @Nullable CompoundTag nbt) {
        this.backend.setChunkData(pos.toLong(), nbt);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Optional<CompoundTag>> loadAsync(ChunkPos pos) {
        return this.backend.getChunkData(pos.toLong(), null).thenApply(Optional::ofNullable);
    }

    /**
     * SuperChunk: {@code sync} decides the fsync, as in vanilla's {@code IOWorker.synchronize}
     * (upstream always fsynced). Both wait for every queued write.
     */
    @Override
    public CompletableFuture<Void> synchronize(boolean sync) {
        return this.backend.flush(sync);
    }

    @Override
    public CompletableFuture<Void> scanChunk(ChunkPos pos, StreamTagVisitor scanner) {
        Preconditions.checkNotNull(scanner, "scanner");
        return this.backend.getChunkData(pos.toLong(), scanner).thenApply(unused -> null);
    }

    @Override
    public void close() {
        this.backend.close().join();
    }

    @Override
    public boolean isOldChunkAround(ChunkPos chunkPos, int i) {
        return super.isOldChunkAround(chunkPos, i);
    }


    @Override
    public CompletableFuture<Void> setRawChunkData(ChunkPos pos, byte[] data) {
        this.backend.setChunkData(pos.toLong(), data);
        return CompletableFuture.completedFuture(null);
    }
}
