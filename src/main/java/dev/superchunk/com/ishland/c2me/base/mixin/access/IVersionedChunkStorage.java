package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ChunkStorage.class)
public interface IVersionedChunkStorage {

    @Accessor("worker")
    IOWorker getWorker();

    // 1.20.1: RegionStorageInfo / ChunkStorage.storageInfo() do not exist (1.20.5+) —
    // invokeGetStorageKey deleted (port plan §1.7).

}
