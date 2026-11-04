package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SuperChunk: a creating {@code ServerChunkCache.getChunk} from another thread waits on that thread,
 * not on the server thread. See {@code MixinServerChunkManager#shortcutGetChunk}.
 *
 * <p>Vanilla runs such a call as a task on the server thread, which then blocks in
 * {@code managedBlock} until the chunk has generated, and runs any further off-thread requests
 * nested inside that wait. Distant Horizons generates outside the chunk system and reads the real
 * level from its own threads: placing a village cat, {@code Cat.finalizeSpawn} asks the structure
 * manager for STRUCTURE_REFERENCES at the cat's position, which needs structure starts for 17x17
 * chunks where no player is. Measured with DH 3.3.3 pregenerating a superflat world: server-thread
 * stalls of 0.6-2.5 s, TPS 13-17 over 5 s (vanilla 0.1-0.45 s, TPS 20).
 *
 * <p>Here the server thread only adds a ticket and returns the holder's future, which is what
 * {@code getChunkFutureMainThread} does but with a ticket that does not expire after a tick, and
 * the calling thread waits for the future. Afterwards a vanilla UNKNOWN ticket keeps the chunk for
 * one more tick, as vanilla's own ticket would.
 *
 * <p>Kill switch: {@code -Dsuperchunk.chunkSystem.offThreadChunkLoads=false}.
 */
public final class OffThreadChunkLoad {

    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.chunkSystem.offThreadChunkLoads", "true"));
    /** Keyed per request, so two callers loading the same chunk hold separate tickets. */
    public static final TicketType<Long> TICKET = TicketType.create("superchunk_off_thread_load", Long::compare);

    private static final AtomicLong NEXT_KEY = new AtomicLong();

    private OffThreadChunkLoad() {
    }

    public static Long nextKey() {
        return NEXT_KEY.incrementAndGet();
    }

    /** {@code future.join()}, except that it gives up once the server has stopped running. */
    public static ChunkResult<ChunkAccess> await(CompletableFuture<ChunkResult<ChunkAccess>> future, MinecraftServer server) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get(1, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    if (!server.isRunning()) {
                        return ChunkResult.error("server stopped");
                    }
                } catch (InterruptedException e) {
                    interrupted = true; // join() is not interruptible either
                } catch (ExecutionException e) {
                    throw new CompletionException(e.getCause());
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
