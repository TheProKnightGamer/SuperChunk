package dev.superchunk.predictive;

import dev.superchunk.com.ishland.c2me.base.common.scheduler.SchedulingManager;
import dev.superchunk.com.ishland.c2me.notickvd.common.ChunkTicketManagerExtension;
import dev.superchunk.com.ishland.c2me.notickvd.common.NoTickSystem;
import dev.superchunk.config.PlayerLatency;
import dev.superchunk.config.PredictiveGen;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;

import java.util.List;
import java.util.UUID;

/**
 * SuperChunk {@code player.predictiveGen} — the MAIN-THREAD side: per-player velocity tracking
 * and corridor triggering. One instance per world, owned by the {@code ChunkMap} mixin and
 * ticked once per server tick from {@code ChunkMap.tick()} (the vanilla per-tick player-update
 * path — the same cadence VMP's playerwatching area map ticks on).
 *
 * <p><b>Velocity source.</b> Horizontal position deltas measured across ticks
 * ({@code getX/getZ} history), smoothed with an EMA — deliberately NOT
 * {@code getDeltaMovement()}, which is unreliable for elytra/vehicle movement. A per-tick
 * displacement above {@link #TELEPORT_DISTANCE_SQ} is treated as a teleport and resets the EMA
 * instead of polluting it.
 *
 * <p><b>Cost when idle.</b> Below {@link PredictiveGen#MIN_SPEED_BPS} nothing is published:
 * per player per tick this is a map lookup and a handful of doubles. Corridor recomputation,
 * ticket bookkeeping and hit accounting all live on the c2me-sched thread
 * (see {@link PredictiveChunkLoader}); publishing is one queue push per fast player per
 * {@link PredictiveGen#RECOMPUTE_INTERVAL_TICKS}.
 *
 * <p><b>P2 feed.</b> While {@code player.priorityBias} is ON, the predicted MID and END chunk
 * positions of the corridor are also registered as player sources in the world's
 * {@link SchedulingManager} priority band (add/remove diffed each recompute, removed on
 * stop/disconnect), so in-pipeline work along the predicted path outranks background pregen.
 * {@code addPlayerSource}/{@code removePlayerSource} are already ref-counted per position and
 * checked no-ops while P2 is OFF.
 *
 * <p><b>Hygiene.</b> Players absent from the world's player list (disconnect or dimension
 * change) are swept every recompute interval: their corridor tickets are retracted via
 * {@link NoTickSystem#superchunk$predictiveRemove} and their predicted P2 sources removed. After
 * a player slows below the threshold, a position-only feed continues for the hit window so
 * outstanding hit samples can still resolve, then the player goes fully idle.
 */
public final class PredictiveGenTracker {

    /** EMA smoothing factor for the per-tick displacement (≈ 3.3-tick time constant). */
    private static final double EMA_ALPHA = 0.3;

    /**
     * Per-tick displacement (squared) above which the movement is treated as a teleport:
     * 16 blocks/tick = 320 blocks/s, beyond any legitimate continuous movement.
     */
    private static final double TELEPORT_DISTANCE_SQ = 16.0 * 16.0;

    private final NoTickSystem noTickSystem;
    private final SchedulingManager schedulingManager;
    private final Object2ReferenceOpenHashMap<UUID, PlayerMotion> players = new Object2ReferenceOpenHashMap<>();
    private long tickCounter;

    public PredictiveGenTracker(ChunkTicketManagerExtension distanceManagerExtension, SchedulingManager schedulingManager) {
        this.noTickSystem = distanceManagerExtension.superchunk$getNoTickSystem();
        this.schedulingManager = schedulingManager;
    }

    /** Called once per server tick from the ChunkMap mixin (flag already checked there). */
    public void tick(List<ServerPlayer> worldPlayers) {
        this.tickCounter++;
        final boolean publish = this.tickCounter % PredictiveGen.RECOMPUTE_INTERVAL_TICKS == 0;

        for (ServerPlayer player : worldPlayers) {
            PlayerMotion motion = this.players.get(player.getUUID());
            if (motion == null) {
                motion = new PlayerMotion();
                motion.lastX = player.getX();
                motion.lastZ = player.getZ();
                this.players.put(player.getUUID(), motion);
                motion.seenTick = this.tickCounter;
                continue; // no delta on the first observed tick
            }
            motion.seenTick = this.tickCounter;

            final double x = player.getX();
            final double z = player.getZ();
            final double dx = x - motion.lastX;
            final double dz = z - motion.lastZ;
            motion.lastX = x;
            motion.lastZ = z;

            if (dx * dx + dz * dz > TELEPORT_DISTANCE_SQ) {
                // Teleport/respawn: forget the motion history; the next interval retires any
                // corridor because the EMA speed collapses below the threshold.
                motion.emaVx = 0.0;
                motion.emaVz = 0.0;
            } else {
                motion.emaVx += EMA_ALPHA * (dx - motion.emaVx);
                motion.emaVz += EMA_ALPHA * (dz - motion.emaVz);
            }

            if (publish) {
                this.publish(player, motion);
            }
        }

        if (publish && this.players.size() != worldPlayers.size()) {
            // A tracked player is missing from the world's player list (disconnect / dimension
            // change): retire their corridor. Joins and leaves in the same interval still differ
            // in seenTick, so the size guard is only an optimization for the common no-change case.
            this.sweepDeparted();
        }
    }

    private void publish(ServerPlayer player, PlayerMotion motion) {
        final double speedBps = Math.sqrt(motion.emaVx * motion.emaVx + motion.emaVz * motion.emaVz) * 20.0;
        final UUID playerId = player.getUUID();
        if (speedBps >= PredictiveGen.MIN_SPEED_BPS) {
            motion.corridorActive = true;
            // Position feed outlives the last corridor by the full hit window (+ slack for EMA
            // lag), so every sample issued before the slow-down reaches its own deadline while
            // feeds — and therefore expiry sweeps — are still flowing.
            motion.hitFeedTicksRemaining = (int) Math.ceil(PredictiveGen.HIT_WINDOW_SECONDS * 20.0)
                    + 2 * PredictiveGen.RECOMPUTE_INTERVAL_TICKS;
            this.noTickSystem.superchunk$predictiveUpdate(playerId, motion.lastX, motion.lastZ, motion.emaVx, motion.emaVz);
            this.updatePredictedSources(motion, motion.lastX, motion.lastZ, motion.emaVx, motion.emaVz);
            return;
        }
        if (motion.corridorActive) {
            motion.corridorActive = false;
            this.noTickSystem.superchunk$predictiveRemove(playerId);
            this.clearPredictedSources(motion);
        }
        if (motion.hitFeedTicksRemaining > 0) {
            // Slowed down recently: keep feeding positions (only) so outstanding hit samples of
            // this player can still resolve as hits; strictly bounded by the hit window.
            motion.hitFeedTicksRemaining -= PredictiveGen.RECOMPUTE_INTERVAL_TICKS;
            this.noTickSystem.superchunk$predictivePosition(playerId, motion.lastX, motion.lastZ);
        }
        // Fully idle (walking/standing): zero calls, zero queue traffic.
    }

    /** Removes state for players that left this world (disconnect or dimension change). */
    private void sweepDeparted() {
        final ObjectIterator<Object2ReferenceMap.Entry<UUID, PlayerMotion>> iterator =
                this.players.object2ReferenceEntrySet().fastIterator();
        while (iterator.hasNext()) {
            final Object2ReferenceMap.Entry<UUID, PlayerMotion> entry = iterator.next();
            if (entry.getValue().seenTick != this.tickCounter) {
                // Purge (not remove): a departed player can never resolve hit samples, so the
                // loader flushes them as misses along with the tickets. Sent unconditionally —
                // even an inactive player may still have samples in the hit window.
                this.noTickSystem.superchunk$predictivePurge(entry.getKey());
                this.clearPredictedSources(entry.getValue());
                iterator.remove();
            }
        }
    }

    // ===== P2 (player.priorityBias) predicted-source feed =====

    private void updatePredictedSources(PlayerMotion motion, double x, double z, double vxPerTick, double vzPerTick) {
        if (!PlayerLatency.PRIORITY_BIAS) return;
        final double lookaheadTicks = PredictiveGen.LOOKAHEAD_SECONDS * 20.0;
        final long endChunk = chunkAt(x + vxPerTick * lookaheadTicks, z + vzPerTick * lookaheadTicks);
        final long midChunk = chunkAt(x + vxPerTick * lookaheadTicks * 0.5, z + vzPerTick * lookaheadTicks * 0.5);
        final long[] newSources = midChunk == endChunk ? new long[]{endChunk} : new long[]{midChunk, endChunk};

        // Diff against the previously registered sources (SchedulingManager ref-counts per pos).
        final long[] oldSources = motion.predictedSources;
        for (long oldSource : oldSources) {
            if (!contains(newSources, oldSource)) {
                this.schedulingManager.removePlayerSource(oldSource);
            }
        }
        for (long newSource : newSources) {
            if (!contains(oldSources, newSource)) {
                this.schedulingManager.addPlayerSource(newSource);
            }
        }
        motion.predictedSources = newSources;
    }

    private void clearPredictedSources(PlayerMotion motion) {
        for (long source : motion.predictedSources) {
            this.schedulingManager.removePlayerSource(source);
        }
        motion.predictedSources = EMPTY_SOURCES;
    }

    private static long chunkAt(double x, double z) {
        return ChunkPos.asLong(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
    }

    private static boolean contains(long[] array, long value) {
        for (long element : array) {
            if (element == value) return true;
        }
        return false;
    }

    private static final long[] EMPTY_SOURCES = new long[0];

    private static final class PlayerMotion {
        double lastX;
        double lastZ;
        /** EMA of the per-tick horizontal displacement, blocks/tick. */
        double emaVx;
        double emaVz;
        long seenTick;
        boolean corridorActive;
        /** Position-only hit feed remaining after slowing down, in ticks. */
        int hitFeedTicksRemaining;
        /** Chunk positions currently registered in the P2 band as predicted sources. */
        long[] predictedSources = EMPTY_SOURCES;
    }
}
