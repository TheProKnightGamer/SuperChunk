package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;

public record ChunkState(ChunkAccess chunk, ProtoChunk protoChunk, ChunkStatus reachedStatus) {
}
