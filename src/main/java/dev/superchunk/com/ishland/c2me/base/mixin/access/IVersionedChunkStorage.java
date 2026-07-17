package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkStorage.class)
public interface IVersionedChunkStorage {

    @Accessor("worker")
    IOWorker getWorker();

    @Invoker("storageInfo")
    RegionStorageInfo invokeGetStorageKey();

}
