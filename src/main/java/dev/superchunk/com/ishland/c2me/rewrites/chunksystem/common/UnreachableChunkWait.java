package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common;

import dev.superchunk.com.ishland.flowsched.scheduler.ItemHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * SuperChunk: the stop condition that ends a server-thread {@code ServerChunkCache.getChunk} wait
 * for a chunk that can never load because a chunk it depends on is broken. See
 * {@code MixinServerChunkManager#superchunk$abandonUnreachableChunkWait} and
 * {@link dev.superchunk.com.ishland.flowsched.scheduler.StatusAdvancingScheduler#findBrokenBlocker}.
 *
 * <p>Costs one volatile read per poll while nothing is broken. Otherwise the dependency walk runs at
 * once and then at most every {@link #RECHECK_NANOS}, bounded by {@link #NODE_BUDGET} items.
 *
 * <p>Kill switch: {@code -Dsuperchunk.chunkSystem.abandonUnreachableWaits=false}.
 */
public final class UnreachableChunkWait {

    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.chunkSystem.abandonUnreachableWaits", "true"));

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-ChunkWait");
    private static final long RECHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final int NODE_BUDGET = 512;
    /** Chunk position to the nanoTime it was last reported; cleared wholesale past 256 entries. */
    private static final ConcurrentHashMap<Long, Long> LAST_LOGGED = new ConcurrentHashMap<>();

    private final TheChunkSystem system;
    private final ChunkPos pos;
    private final ChunkStatus vanillaStatus;
    private final NewChunkStatus wanted;
    private long nextCheck = System.nanoTime();
    private ChunkPos blocker;

    public UnreachableChunkWait(TheChunkSystem system, int x, int z, ChunkStatus vanillaStatus) {
        this.system = system;
        this.pos = new ChunkPos(x, z);
        this.vanillaStatus = vanillaStatus;
        this.wanted = NewChunkStatus.fromVanillaStatus(vanillaStatus);
    }

    /** Polled by {@code managedBlock} together with the future's own {@code isDone}. */
    public boolean isUnreachable() {
        if (this.blocker != null) {
            return true;
        }
        if (ItemHolder.brokenItemCount() == 0) {
            return false;
        }
        final long now = System.nanoTime();
        if (now - this.nextCheck < 0L) {
            return false;
        }
        this.nextCheck = now + RECHECK_NANOS;
        this.blocker = this.system.findBrokenBlocker(this.pos, this.wanted, NODE_BUDGET);
        return this.blocker != null;
    }

    /**
     * Called once the wait was abandoned. At most one line per chunk per {@link #LOG_INTERVAL_NANOS}:
     * a collision sweep asks for the same chunk every tick, but each distinct chunk is reported.
     */
    public void log(boolean create) {
        final long now = System.nanoTime();
        if (LAST_LOGGED.size() > 256) {
            LAST_LOGGED.clear();
        }
        final Long last = LAST_LOGGED.get(this.pos.toLong());
        if (last != null && now - last < LOG_INTERVAL_NANOS) {
            return;
        }
        LAST_LOGGED.put(this.pos.toLong(), now);
        final String consequence = "Treating it as not loaded instead of blocking the server thread forever"
                + (create ? "; the caller required it and gets \"Chunk not there when requested\"." : ".");
        if (this.blocker.equals(this.pos)) {
            LOGGER.warn("Chunk {} can never reach {}: it failed to generate (see the \"Error upgrading chunk\" "
                    + "error for it). {}", this.pos, this.vanillaStatus, consequence);
        } else {
            LOGGER.warn("Chunk {} can never reach {}: it waits on chunk {}, which failed to generate (see the "
                    + "\"Error upgrading chunk\" error for it). {}", this.pos, this.vanillaStatus, this.blocker, consequence);
        }
    }
}
