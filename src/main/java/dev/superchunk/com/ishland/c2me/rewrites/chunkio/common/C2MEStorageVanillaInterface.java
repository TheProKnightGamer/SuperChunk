package dev.superchunk.com.ishland.c2me.rewrites.chunkio.common;

import com.google.common.base.Preconditions;
import dev.superchunk.com.ishland.c2me.base.common.theinterface.IDirectStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class C2MEStorageVanillaInterface extends IOWorker implements IDirectStorage {

    private final C2MEStorageThread backend;

    public C2MEStorageVanillaInterface(RegionStorageInfo arg, Path path, boolean dsync) {
        super(arg, path, dsync);
        this.backend = new C2MEStorageThread(arg, path, dsync);
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

    @Override
    public CompletableFuture<Void> synchronize(boolean sync) {
        return this.backend.flush(true);
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

    @Override
    public RegionStorageInfo storageInfo() {
        return this.backend.getStorageKey();
    }
}
