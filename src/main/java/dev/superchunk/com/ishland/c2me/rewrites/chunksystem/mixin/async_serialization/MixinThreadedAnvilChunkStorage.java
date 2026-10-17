package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin.async_serialization;

import com.mojang.datafixers.DataFixer;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Forge 1.20.1 (plan §1.2 row 19, REFACTORED): mixin superclass ctor is 1.20.1
 * {@code ChunkStorage(Path, DataFixer, boolean)}; the overwrite mirrors 1.20.1
 * {@code ChunkMap.readChunk} (private, src1201 ChunkMap:957) keeping the
 * skip-datafixer-if-current-version optimization; the shadowed helper is 1.20.1's private
 * {@code upgradeChunkTag(CompoundTag)} (src1201 ChunkMap:963).
 */
@Mixin(ChunkMap.class)
public abstract class MixinThreadedAnvilChunkStorage extends ChunkStorage {

    public MixinThreadedAnvilChunkStorage(Path folder, DataFixer fixerUpper, boolean sync) {
        super(folder, fixerUpper, sync);
    }

    @Shadow
    protected abstract CompoundTag upgradeChunkTag(CompoundTag tag);

    /**
     * @author ishland
     * @reason skip datafixer if possible (Yarn getUpdatedChunkNbt -> Mojmap readChunk)
     *
     * <p>Declared {@code private} to match the 1.20.1 target's visibility.
     */
    @Overwrite
    private CompletableFuture<Optional<CompoundTag>> readChunk(ChunkPos chunkPos) {
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
