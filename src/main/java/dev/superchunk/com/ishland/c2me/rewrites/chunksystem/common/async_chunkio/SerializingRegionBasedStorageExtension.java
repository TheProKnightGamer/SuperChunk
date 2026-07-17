package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.async_chunkio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

public interface SerializingRegionBasedStorageExtension {

    void update(ChunkPos pos, CompoundTag tag);

}
