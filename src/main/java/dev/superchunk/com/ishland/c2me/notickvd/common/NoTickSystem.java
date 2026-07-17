package dev.superchunk.com.ishland.c2me.notickvd.common;

import dev.superchunk.com.ishland.c2me.base.common.GlobalExecutors;
import dev.superchunk.config.PredictiveGen;
import dev.superchunk.predictive.PredictiveChunkLoader;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public class NoTickSystem {

    private final PlayerNoTickLoader playerNoTickLoader;
    /**
     * SuperChunk player.predictiveGen: predicted-path corridor loader sharing this system's
     * scheduler-thread discipline and main-thread ticket queue. Null when the flag is OFF
     * (static-final), so the OFF path never constructs or touches predictor state.
     */
    private final PredictiveChunkLoader predictiveLoader;
    private final ConcurrentLinkedQueue<Runnable> pendingActionsOnScheduler = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<Runnable> mainBeforeTicketTicks = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<Runnable> mainAfterTicketTicks = new ConcurrentLinkedQueue<>();

    private final AtomicBoolean isTicking = new AtomicBoolean();
    final Executor executor = GlobalExecutors.asyncScheduler;
    private volatile boolean pendingPurge = false;
    private volatile long age = 0;

    public NoTickSystem(ChunkMap tacs) {
        this.playerNoTickLoader = new PlayerNoTickLoader(tacs, this);
        this.predictiveLoader = PredictiveGen.ENABLED ? new PredictiveChunkLoader(tacs, this) : null;
    }

    public void addPlayerSource(ChunkPos chunkPos) {
        this.pendingActionsOnScheduler.add(() -> this.playerNoTickLoader.addSource(chunkPos));
    }

    public void removePlayerSource(ChunkPos chunkPos) {
        this.pendingActionsOnScheduler.add(() -> this.playerNoTickLoader.removeSource(chunkPos));
    }

    public void setNoTickViewDistance(int viewDistance) {
        this.pendingActionsOnScheduler.add(() -> {
            this.playerNoTickLoader.setViewDistance(viewDistance);
            if (this.predictiveLoader != null) this.predictiveLoader.setViewDistance(viewDistance);
        });
    }

    // ===== SuperChunk player.predictiveGen feeds (main thread -> scheduler thread) =====
    // Same pending-actions handoff as addPlayerSource/removePlayerSource above; only ever called
    // by the ChunkMap tick mixin while the flag is ON, so predictiveLoader is non-null here.

    public void superchunk$predictiveUpdate(UUID playerId, double x, double z, double vxPerTick, double vzPerTick) {
        if (this.predictiveLoader == null) return;
        this.pendingActionsOnScheduler.add(() -> this.predictiveLoader.updateCorridor(playerId, x, z, vxPerTick, vzPerTick));
    }

    public void superchunk$predictivePosition(UUID playerId, double x, double z) {
        if (this.predictiveLoader == null) return;
        this.pendingActionsOnScheduler.add(() -> this.predictiveLoader.updatePosition(playerId, x, z));
    }

    public void superchunk$predictiveRemove(UUID playerId) {
        if (this.predictiveLoader == null) return;
        this.pendingActionsOnScheduler.add(() -> this.predictiveLoader.removePlayer(playerId));
    }

    public void superchunk$predictivePurge(UUID playerId) {
        if (this.predictiveLoader == null) return;
        this.pendingActionsOnScheduler.add(() -> this.predictiveLoader.purgePlayer(playerId));
    }

    /**
     * SuperChunk player.predictiveGen: lets {@link PredictiveChunkLoader} (outside this package)
     * enqueue its vanilla DistanceManager ticket add/removes onto the same main-thread
     * before-ticket-ticks queue {@link PlayerNoTickLoader} uses.
     */
    public void superchunk$runBeforeTicketTicks(Runnable runnable) {
        this.mainBeforeTicketTicks.add(runnable);
    }

    public void beforeTicketTicks() {
        drainQueue(this.mainBeforeTicketTicks);
    }

    public void afterTicketTicks() {
        drainQueue(this.mainAfterTicketTicks);
    }

    public void tick() {
        scheduleTick();
    }

    private void scheduleTick() {
        if (!this.pendingActionsOnScheduler.isEmpty() && this.isTicking.compareAndSet(false, true)) {
            List<Runnable> tasks = new ArrayList<>(this.pendingActionsOnScheduler.size() + 3);
            {
                Runnable r;
                while ((r = this.pendingActionsOnScheduler.poll()) != null) {
                    tasks.add(r);
                }
            }
            executor.execute(() -> {
                try {
                    for (Runnable task : tasks) {
                        try {
                            task.run();
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    }

                    this.playerNoTickLoader.tick();
                    if (this.predictiveLoader != null) this.predictiveLoader.tick();
                    if (!this.pendingActionsOnScheduler.isEmpty() || !tasks.isEmpty()) scheduleTick(); // run more tasks
                } finally {
                    this.isTicking.set(false);
                }
            });
        }
    }

    private void drainQueue(ConcurrentLinkedQueue<Runnable> queue) {
        Runnable runnable;
        while ((runnable = queue.poll()) != null) {
            try {
                runnable.run();
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    public void runPurge(long age) {
        this.age = age;
        this.pendingPurge = true;
    }

    public long getPendingLoadsCount() {
        return this.playerNoTickLoader.getPendingLoadsCount();
    }

    public void close() {
        this.playerNoTickLoader.close();
        if (this.predictiveLoader != null) this.predictiveLoader.close();
        executor.execute(() -> {
            try {
                this.playerNoTickLoader.tick();
            } catch (Throwable t) {
                t.printStackTrace();
            }
            if (this.predictiveLoader != null) {
                this.predictiveLoader.tick(); // closing flag set -> clears all predictor tickets
            }
        });
    }
}
