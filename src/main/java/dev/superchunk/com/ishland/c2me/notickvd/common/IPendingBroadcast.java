package dev.superchunk.com.ishland.c2me.notickvd.common;

/** Implemented on {@code ChunkHolder} by notickvd's {@code MixinChunkHolder}. */
public interface IPendingBroadcast {

    /** Vanilla {@code broadcastChanges}' own guard: whether it would send anything for this chunk. */
    boolean superchunk$hasPendingBroadcast();
}
