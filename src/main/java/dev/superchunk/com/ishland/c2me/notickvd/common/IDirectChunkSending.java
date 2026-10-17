package dev.superchunk.com.ishland.c2me.notickvd.common;

import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Forge-1.20.1-port addition (no neo counterpart). On neo the CHUNK_SENDING send path called
 * {@code ChunkMap.onChunkReadyToSend} through an invoker; 1.20.1 has no such method — sends go
 * per-player through {@code playerLoadedChunk}, which the notickvd ChunkMap mixin gates against
 * duplicate vanilla ticking-promotion sends (plan §3.3 (d)). This duck (implemented on ChunkMap
 * by {@code notickvd.mixin.MixinThreadedAnvilChunkStorage}) is the sanctioned direct-send entry:
 * it flags the send as legitimate, then loops {@code getPlayers(pos, false)} +
 * {@code playerLoadedChunk}. Main-thread only.
 */
public interface IDirectChunkSending {

    void c2me_notickvd$sendToWatchers(LevelChunk chunk);

}
