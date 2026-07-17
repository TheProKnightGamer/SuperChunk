package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin.async_serialization;

import com.mojang.datafixers.DataFixer;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Mixin(ChunkMap.class)
public abstract class MixinThreadedAnvilChunkStorage extends ChunkStorage {

    public MixinThreadedAnvilChunkStorage(RegionStorageInfo info, Path folder, DataFixer fixerUpper, boolean sync) {
        super(info, folder, fixerUpper, sync);
    }

    @Shadow
    protected abstract CompoundTag upgradeChunkTag(CompoundTag tag);

    /**
     * @author ishland
     * @reason skip datafixer if possible (Yarn getUpdatedChunkNbt -> Mojmap readChunk)
     *
     * <p>Declared {@code public} to match the (public) target — a {@code private}
     * overwrite logs a "Static binding violation" warning at classload.
     */
    @Overwrite
    public CompletableFuture<Optional<CompoundTag>> readChunk(ChunkPos chunkPos) {
        return this.read(chunkPos).thenCompose(nbt -> {
            if (nbt.isPresent()) {
                final CompoundTag compound = nbt.get();
                if (ChunkStorage.getVersion(compound) != SharedConstants.getCurrentVersion().getDataVersion().getVersion()) {
                    return CompletableFuture.supplyAsync(() -> Optional.of(upgradeChunkTag(compound)), Util.backgroundExecutor());
                } else {
                    return CompletableFuture.completedFuture(nbt);
                }
            } else {
                return CompletableFuture.completedFuture(Optional.empty());
            }
        });
    }

}
