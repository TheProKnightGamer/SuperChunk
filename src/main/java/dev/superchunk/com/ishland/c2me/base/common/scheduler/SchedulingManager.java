package dev.superchunk.com.ishland.c2me.base.common.scheduler;

import dev.superchunk.com.ishland.c2me.base.common.GlobalExecutors;
import dev.superchunk.config.PlayerLatency;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

public class SchedulingManager {

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    public static final int MAX_LEVEL = ChunkLevel.MAX_LEVEL + 1;
    private final ConcurrentMap<Long, FreeableTaskList> pos2Tasks = new ConcurrentHashMap<>();
    private final Long2IntOpenHashMap prioritiesFromLevel = new Long2IntOpenHashMap() {
        @Override
        protected void rehash(int newN) {
            if (n < newN) {
                super.rehash(newN);
            }
        }
    };
    private final StampedLock prioritiesLock = new StampedLock();
    private final int id = COUNTER.getAndIncrement();
    private volatile ChunkPos currentSyncLoad = null;

    // ===== SuperChunk player.priorityBias (P2): player-proximity priority band =====
    // Clone of the currentSyncLoad boost below: a per-world set of PLAYER chunk positions (fed
    // from the notickvd player-source hooks on DistanceManager.addPlayer/removePlayer, i.e. the
    // same section-crossing signals that drive PlayerNoTickLoader), mutated ONLY on this.executor
    // (the c2me-sched thread) exactly like sync-load updates. getPriority() gives tasks within
    // (view distance + 8) chebyshev of the nearest player the band clamp(18 + distance, 18, 32):
    // floor 18 keeps 15 (ChunkSystemExecutors control plane) / 16 (chunk IO saves) / 17 (light)
    // ahead; ceiling 32 stays ahead of the vanilla FULL level 33 and the 34+ generation-dependency
    // rings. Priorities of QUEUED tasks live-update on player movement via the same
    // updatePriorityInternal -> DynamicPriorityQueue.changePriority sweep the sync-load boost
    // uses. Pure reordering of existing work: no budget, no throttle, no new tasks.
    private static final int PLAYER_BAND_FLOOR = 18;
    private static final int PLAYER_BAND_CEILING = 32;
    private static final int PLAYER_BAND_MARGIN = 8;
    /** chunk pos -> number of players whose section is in that chunk; c2me-sched thread only. */
    private final Long2IntOpenHashMap playerSourceCounts = new Long2IntOpenHashMap();
    /** Immutable snapshot of {@link #playerSourceCounts} keys for lock-free reads in getPriority; null = empty. */
    private volatile long[] playerSourcesSnapshot = null;
    /** noTickViewDistance + {@link #PLAYER_BAND_MARGIN}; matches PlayerNoTickLoader's default of 12 until fed. */
    private volatile int playerBandRadius = 12 + PLAYER_BAND_MARGIN;

    private boolean consolidatingLevelUpdates = false;
    private Queue<Runnable> consolidatedLevelUpdates = new ArrayDeque<>();

    private final Executor executor;

    {
        prioritiesFromLevel.defaultReturnValue(MAX_LEVEL);
    }

    public SchedulingManager(Executor executor) {
        this.executor = executor;
    }

    public void enqueue(AbstractPosAwarePrioritizedTask task) {
        retry:
        while (true) {
            final long pos = task.getPos();
            final FreeableTaskList locks = this.pos2Tasks.computeIfAbsent(pos, unused -> new FreeableTaskList());
            synchronized (locks) {
                if (locks.freed) continue retry;
                locks.add(task);
            }
            task.setPriority(this.getPriority(pos));
            task.addPostExec(() -> {
                final FreeableTaskList tasks = this.pos2Tasks.get(task.getPos());
                if (tasks != null) {
                    synchronized (tasks) {
                        if (tasks.freed) return;
                        tasks.remove(task);
                        if (tasks.isEmpty()) {
                            tasks.freed = true;
                        }
                    }
                    if (tasks.freed) {
                        this.pos2Tasks.remove(task.getPos());
                    }
                }
            });
            GlobalExecutors.prioritizedScheduler.schedule(task);
            return;
        }
    }

    public void enqueue(long pos, Runnable command) {
        this.enqueue(new WrappingTask(pos, command));
    }

    public Executor positionedExecutor(long pos) {
        return command -> this.enqueue(pos, command);
    }

    public void updatePriorityFromLevel(long pos, int level) {
        this.executor.execute(() -> {
            updatePriorityFromLevel0(pos, level);
        });
    }

    private void updatePriorityFromLevel0(long pos, int level) {
        if (this.getPriorityFromMap(pos) == level) return;
        final long stamp = this.prioritiesLock.writeLock();
        try {
            if (level < MAX_LEVEL) {
                this.prioritiesFromLevel.put(pos, level);
            } else {
                this.prioritiesFromLevel.remove(pos);
            }
        } finally {
            this.prioritiesLock.unlockWrite(stamp);
        }
        updatePriorityInternal(pos);
    }

    public void updatePriorityFromLevelOnMain(long pos, int level) {
        if (this.consolidatingLevelUpdates) {
            this.consolidatedLevelUpdates.add(() -> updatePriorityFromLevel0(pos, level));
        } else {
            updatePriorityFromLevel(pos, level);
        }
    }

    public void setConsolidatingLevelUpdates(boolean value) {
        this.consolidatingLevelUpdates = value;
        if (!value) {
            if (!this.consolidatedLevelUpdates.isEmpty()) {
                Queue<Runnable> runnables = this.consolidatedLevelUpdates;
                this.consolidatedLevelUpdates = new ArrayDeque<>();
                this.executor.execute(() -> {
                    for (Runnable runnable : runnables) {
                        try {
                            runnable.run();
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    }
                });
            }
        }
    }

    private void updatePriorityInternal(long pos) {
        final int priority = getPriority(pos);
        final FreeableTaskList locks = this.pos2Tasks.get(pos);
        if (locks != null) {
            synchronized (locks) {
                if (locks.freed) return;
                for (AbstractPosAwarePrioritizedTask lock : locks) {
                    lock.setPriority(priority);
                    GlobalExecutors.prioritizedScheduler.notifyPriorityChange(lock);
                }
            }
        }
    }

    private int getPriority(long pos) {
        final int fromLevel = getPriorityFromMap(pos);
        int fromSyncLoad;
        ChunkPos currentSyncLoad1 = currentSyncLoad;
        if (currentSyncLoad1 != null) {
            final int chebyshevDistance = chebyshev(new ChunkPos(pos), currentSyncLoad1);
            if (chebyshevDistance <= 8) {
                fromSyncLoad = chebyshevDistance;
//                System.out.println("dist for chunk [%d,%d] is %d".formatted(currentSyncLoad.x, currentSyncLoad.z, chebyshevDistance));
            } else {
                fromSyncLoad = MAX_LEVEL;
            }
        } else {
            fromSyncLoad = MAX_LEVEL;
        }
        // SuperChunk player.priorityBias (P2): player-proximity band. The snapshot is only ever
        // published while the flag is ON, so with the flag OFF (default) this reads one null
        // volatile and the result is byte-identical to shipped behavior.
        int fromPlayers = MAX_LEVEL;
        final long[] playerSources = this.playerSourcesSnapshot;
        if (playerSources != null) {
            int nearest = Integer.MAX_VALUE;
            for (long playerPos : playerSources) {
                final int chebyshevDistance = chebyshev(pos, playerPos);
                if (chebyshevDistance < nearest) {
                    nearest = chebyshevDistance;
                }
            }
            if (nearest <= this.playerBandRadius) {
                fromPlayers = Math.min(PLAYER_BAND_FLOOR + nearest, PLAYER_BAND_CEILING);
            }
        }
        return Math.min(Math.min(fromLevel, fromSyncLoad), fromPlayers);
    }

    private int getPriorityFromMap(long pos) {
        int fromLevel = MAX_LEVEL;
        long stamp = this.prioritiesLock.tryOptimisticRead();
        try {
            fromLevel = this.prioritiesFromLevel.get(pos);
        } catch (Throwable t) {
        }
        if (!this.prioritiesLock.validate(stamp)) {
            stamp = this.prioritiesLock.readLock();
            try {
                fromLevel = this.prioritiesFromLevel.get(pos);
            } finally {
                this.prioritiesLock.unlockRead(stamp);
            }
        }
        return fromLevel;
    }

    public void setCurrentSyncLoad(ChunkPos pos) {
        executor.execute(() -> {
            if (this.currentSyncLoad != null) {
                final ChunkPos lastSyncLoad = this.currentSyncLoad;
                this.currentSyncLoad = null;
                updateSyncLoadInternal(lastSyncLoad);
            }
            if (pos != null) {
                this.currentSyncLoad = pos;
                updateSyncLoadInternal(pos);
            }
        });
    }

    public int getId() {
        return this.id;
    }

    /**
     * SuperChunk player.priorityBias (P2): a player's section entered this chunk (join, or
     * section crossing observed by the notickvd DistanceManager.addPlayer hook). Hops to the
     * c2me-sched thread exactly like {@link #setCurrentSyncLoad(ChunkPos)}. No-op while the
     * flag is OFF.
     */
    public void addPlayerSource(long pos) {
        if (!PlayerLatency.PRIORITY_BIAS) return;
        this.executor.execute(() -> {
            if (this.playerSourceCounts.addTo(pos, 1) == 0) { // 0 -> 1: new source position
                this.publishPlayerSourcesSnapshot();
                this.updatePlayerSourceInternal(pos, this.playerBandRadius);
            }
        });
    }

    /**
     * SuperChunk player.priorityBias (P2): a player's section left this chunk (disconnect, or
     * section crossing observed by the notickvd DistanceManager.removePlayer hook). No-op while
     * the flag is OFF.
     */
    public void removePlayerSource(long pos) {
        if (!PlayerLatency.PRIORITY_BIAS) return;
        this.executor.execute(() -> {
            final int previousCount = this.playerSourceCounts.addTo(pos, -1);
            if (previousCount <= 1) { // 1 -> 0 (or an unbalanced remove): source position gone
                this.playerSourceCounts.remove(pos);
                this.publishPlayerSourcesSnapshot();
                this.updatePlayerSourceInternal(pos, this.playerBandRadius);
            }
        });
    }

    /**
     * SuperChunk player.priorityBias (P2): band radius feed — {@code noTickViewDistance}
     * (vanilla view distance + 1, as fed to PlayerNoTickLoader.setViewDistance), so the band
     * covers the whole no-tick ring plus an 8-chunk margin. No-op while the flag is OFF.
     */
    public void setPlayerPriorityViewDistance(int viewDistance) {
        if (!PlayerLatency.PRIORITY_BIAS) return;
        this.executor.execute(() -> {
            final int newRadius = Math.min(Math.max(viewDistance, 0), 64) + PLAYER_BAND_MARGIN;
            final int oldRadius = this.playerBandRadius;
            if (newRadius == oldRadius) return;
            this.playerBandRadius = newRadius;
            // Re-sweep every source with the larger of the two radii so both newly covered and
            // newly uncovered chunks get their queued-task priorities recomputed.
            final int sweepRadius = Math.max(oldRadius, newRadius);
            for (long pos : this.playerSourceCounts.keySet().toLongArray()) {
                this.updatePlayerSourceInternal(pos, sweepRadius);
            }
        });
    }

    private void publishPlayerSourcesSnapshot() {
        this.playerSourcesSnapshot = this.playerSourceCounts.isEmpty() ? null : this.playerSourceCounts.keySet().toLongArray();
    }

    /**
     * Live-updates the priorities of QUEUED tasks around a changed player source — the same
     * updatePriorityInternal -> DynamicPriorityQueue.changePriority sweep as
     * {@link #updateSyncLoadInternal(ChunkPos)}, with the band radius instead of 8. Runs on the
     * c2me-sched thread.
     */
    private void updatePlayerSourceInternal(long pos, int radius) {
        final int x = ChunkPos.getX(pos);
        final int z = ChunkPos.getZ(pos);
        for (int xOff = -radius; xOff <= radius; xOff++) {
            for (int zOff = -radius; zOff <= radius; zOff++) {
                updatePriorityInternal(ChunkPos.asLong(x + xOff, z + zOff));
            }
        }
    }

    private void updateSyncLoadInternal(ChunkPos pos) {
        long startTime = System.nanoTime();
        for (int xOff = -8; xOff <= 8; xOff++) {
            for (int zOff = -8; zOff <= 8; zOff++) {
                updatePriorityInternal(ChunkPos.asLong(pos.x + xOff, pos.z + zOff));
            }
        }
        long endTime = System.nanoTime();
    }

    private static int chebyshev(ChunkPos a, ChunkPos b) {
        return Math.max(Math.abs(a.x - b.x), Math.abs(a.z - b.z));
    }

    private static int chebyshev(long a, long b) {
        return Math.max(Math.abs(ChunkPos.getX(a) - ChunkPos.getX(b)), Math.abs(ChunkPos.getZ(a) - ChunkPos.getZ(b)));
    }

    private static class FreeableTaskList extends ObjectArraySet<AbstractPosAwarePrioritizedTask> {

        private boolean freed = false;

    }

}
