package dev.superchunk.com.ishland.c2me.notickvd.common;

import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.TheChunkSystem;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemHolder;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * SuperChunk: frees the load slots of a chunk loader ({@link PlayerNoTickLoader},
 * {@code PredictiveChunkLoader}) that are held by loads which can never finish.
 *
 * <p>Both loaders cap their loads in flight and count a load until its SERVER_ACCESSIBLE future
 * completes. A chunk parked behind a broken neighbour (see
 * {@link dev.superchunk.com.ishland.flowsched.scheduler.StatusAdvancingScheduler#findBrokenBlocker})
 * never completes it while its ticket is held, so each such chunk kept a slot for as long as the
 * player stayed nearby: the eight around one broken chunk nearly fill the default no-tick cap
 * (workers + 1), and two broken chunks in view stopped view-distance loading altogether.
 *
 * <p>A stuck load's future (the loader's own copy: {@code getFutureForStatus} derives a fresh one
 * per call) is failed with {@link ItemHolder#UNLOADED_EXCEPTION}, which is what the loader sees when
 * a ticket is dropped. The ticket itself stays, so nothing else changes. Only runs while something
 * is broken, and at most every {@link #INTERVAL_NANOS} per loader. Not thread-safe: call it from the
 * loader's own thread, where it prunes its futures.
 */
public final class StuckLoadSweeper {

    private static final long INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final int NODE_BUDGET = 64;

    private final Reference2ObjectOpenHashMap<CompletableFuture<Void>, ChunkPos> positions = new Reference2ObjectOpenHashMap<>();
    private long nextSweep = System.nanoTime();

    public CompletableFuture<Void> track(CompletableFuture<Void> future, ChunkPos pos) {
        this.positions.put(future, pos);
        return future;
    }

    /** Call just before the loader prunes its done futures. */
    public void sweep(TheChunkSystem system) {
        this.positions.keySet().removeIf(CompletableFuture::isDone);
        if (this.positions.isEmpty() || ItemHolder.brokenItemCount() == 0) {
            return;
        }
        final long now = System.nanoTime();
        if (now - this.nextSweep < 0L) {
            return;
        }
        this.nextSweep = now + INTERVAL_NANOS;
        ReferenceArrayList<CompletableFuture<Void>> stuck = null;
        for (Reference2ObjectMap.Entry<CompletableFuture<Void>, ChunkPos> entry : this.positions.reference2ObjectEntrySet()) {
            if (system.findBrokenBlocker(entry.getValue(), NewChunkStatus.SERVER_ACCESSIBLE, NODE_BUDGET) != null) {
                if (stuck == null) stuck = new ReferenceArrayList<>();
                stuck.add(entry.getKey());
            }
        }
        if (stuck != null) {
            for (CompletableFuture<Void> future : stuck) {
                this.positions.remove(future);
                future.completeExceptionally(ItemHolder.UNLOADED_EXCEPTION);
            }
        }
    }
}
