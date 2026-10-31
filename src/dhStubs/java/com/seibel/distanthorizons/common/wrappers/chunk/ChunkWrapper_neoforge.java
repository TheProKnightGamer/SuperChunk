package com.seibel.distanthorizons.common.wrappers.chunk;

import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Compile-only stand-in for Distant Horizons' type of the same name (see build.gradle, dhStubs). */
public abstract class ChunkWrapper_neoforge implements IChunkWrapper {
    public ChunkAccess getChunk() {
        throw new UnsupportedOperationException();
    }
}
