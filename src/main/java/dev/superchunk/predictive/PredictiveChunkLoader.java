package dev.superchunk.predictive;

import com.mojang.logging.LogUtils;
import dev.superchunk.com.ishland.c2me.base.common.GlobalExecutors;
import dev.superchunk.com.ishland.c2me.notickvd.common.NoTickSystem;
import dev.superchunk.com.ishland.c2me.notickvd.common.PlayerNoTickLoader;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkState;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.NewChunkHolderVanillaInterface;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ducks.IChunkSystemAccess;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemHolder;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemTicket;
import dev.superchunk.com.ishland.flowsched.scheduler.StatusAdvancingScheduler;
import dev.superchunk.config.PredictiveGen;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SuperChunk {@code player.predictiveGen} — the scheduler-thread engine that turns velocity
 * snapshots into corridor tickets riding the EXISTING notickvd pipeline.
 *
 * <p><b>Threading contract</b> (identical to {@link PlayerNoTickLoader}): all mutable state is
 * confined to the c2me-sched thread ({@link GlobalExecutors#asyncScheduler} — the same executor
 * {@link NoTickSystem} uses). {@link NoTickSystem} calls {@link #updateCorridor}/
 * {@link #updatePosition}/{@link #removePlayer}/{@link #setViewDistance} through its
 * pending-actions queue and {@link #tick()} right after {@code PlayerNoTickLoader.tick()};
 * ticket adds/removes against the vanilla {@code DistanceManager} hop back to the main thread
 * through {@code NoTickSystem}'s before-ticket-ticks queue, exactly like the notickvd loader.
 *
 * <p><b>Ticket integration.</b> {@link #loadChunk0} mirrors {@code PlayerNoTickLoader.loadChunk0}
 * verbatim — a FlowSched ticket targeting {@code SERVER_ACCESSIBLE_CHUNK_SENDING} (so P1/send
 * machinery sees the chunk exactly like a notickvd chunk) plus a vanilla level-33 ticket — but
 * under its OWN ticket types ({@code superchunk:predictive_gen} / {@code superchunk_predictive}),
 * so predictor tickets and notickvd tickets on the same chunk are independent (FlowSched ticket
 * identity is (type, source, targetStatus); a duplicate add throws). Concurrency accounting is
 * NOT bypassed: predicted loads run under the separate small
 * {@link PredictiveGen#MAX_CONCURRENT_PREDICTED_LOADS} budget with the same futures-list pattern
 * {@code tickFutures} uses against {@code maxConcurrentChunkLoads}, so total in-flight loads per
 * world are bounded by the sum of the two budgets and the main loader's budget is never consumed
 * by predictions.
 *
 * <p><b>Hygiene.</b> Tickets are ref-counted per chunk across players ({@link #ticketRefCounts});
 * a chunk's ticket is removed when the LAST player stops desiring it — on corridor recompute
 * (turning), on {@link #removePlayer} (slowing down / disconnect / dimension change) and on
 * {@link #close} (world shutdown, mirroring {@code PlayerNoTickLoader.close}'s closing-flag +
 * clear-on-tick pattern). Hit-rate samples live independently of tickets: each issued ticket
 * records a (player, chunk, deadline) sample resolved as a HIT when the chunk enters that
 * player's no-tick view distance before the deadline, else a MISS.
 */
public class PredictiveChunkLoader {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ItemTicket.TicketType TICKET_TYPE = new ItemTicket.TicketType("superchunk:predictive_gen");
    public static final TicketType<ChunkPos> VANILLA_TICKET_TYPE = TicketType.create("superchunk_predictive", Comparator.comparingLong(ChunkPos::toLong));

    /** Same level as {@code PlayerNoTickLoader}'s vanilla ticket: keeps the chunk border-loaded, never ticking. */
    private static final int VANILLA_TICKET_LEVEL = 33;

    /** Bound on unresolved hit samples; overflow drops the OLDEST sample as untracked. */
    private static final int MAX_PENDING_HIT_SAMPLES = 8192;

    private final ChunkMap tacs;
    private final NoTickSystem noTickSystem;

    /** player -> corridor state; linked for the same round-robin fairness PlayerNoTickLoader gets from its iterator map. */
    private final Object2ReferenceLinkedOpenHashMap<UUID, PlayerCorridor> corridors = new Object2ReferenceLinkedOpenHashMap<>();
    /** chunk -> number of players holding a predictor ref; a ticket exists iff the chunk is a key here. */
    private final Long2IntOpenHashMap ticketRefCounts = new Long2IntOpenHashMap();
    private final ReferenceArrayList<CompletableFuture<Void>> chunkLoadFutures = new ReferenceArrayList<>();
    private final ObjectArrayList<PendingHit> pendingHits = new ObjectArrayList<>();
    private final AtomicBoolean closing = new AtomicBoolean(false);

    private int viewDistance = 12;

    // Gauges for the 1s [predictive-gen] line; written on the scheduler thread, read by the
    // metrics thread (volatile snapshots — approximate by design, like every 1s gauge here).
    volatile int gaugeActivePlayers;
    volatile int gaugeDesiredChunks;
    volatile int gaugeTicketedChunks;
    volatile int gaugeInFlightLoads;
    volatile int gaugePendingHitSamples;

    public PredictiveChunkLoader(ChunkMap tacs, NoTickSystem noTickSystem) {
        this.tacs = tacs;
        this.noTickSystem = noTickSystem;
    }

    /** Fed from the same {@code setNoTickViewDistance} path that feeds {@code PlayerNoTickLoader.setViewDistance}. */
    public void setViewDistance(int viewDistance) {
        this.viewDistance = Math.max(2, viewDistance);
    }

    /**
     * Recomputes the corridor for one fast-moving player. {@code x}/{@code z} are the player's
     * current position (blocks), {@code vxPerTick}/{@code vzPerTick} the EMA velocity in
     * blocks/tick. Runs on the scheduler thread via the pending-actions queue.
     */
    public void updateCorridor(UUID playerId, double x, double z, double vxPerTick, double vzPerTick) {
        if (this.closing.get()) return;
        PredictiveGenMetrics.register(this);
        PredictiveGenMetrics.ensureStarted();

        // Resolve hit samples against the player's REAL position first (pre-recompute).
        this.evaluateHits(playerId, Mth.floor(x) >> 4, Mth.floor(z) >> 4);

        final long[] desired = computeCorridor(x, z, vxPerTick, vzPerTick, this.viewDistance);

        PlayerCorridor corridor = this.corridors.get(playerId);
        if (corridor == null) {
            corridor = new PlayerCorridor();
            this.corridors.put(playerId, corridor);
        }

        final LongOpenHashSet desiredSet = new LongOpenHashSet(desired);
        // Retract tickets the recomputed corridor no longer wants (the "player turned" case) —
        // the predictor's analogue of PlayerNoTickLoader's out-of-range managedChunks removal.
        final LongIterator ticketedIterator = corridor.ticketed.iterator();
        while (ticketedIterator.hasNext()) {
            final long pos = ticketedIterator.nextLong();
            if (!desiredSet.contains(pos)) {
                ticketedIterator.remove();
                this.releaseRef(pos);
            }
        }
        corridor.desired = desired;
        corridor.desiredSet = desiredSet;
        corridor.scanIndex = 0;

        this.tickFutures();
        this.publishGauges();
    }

    /**
     * Position-only feed for a player who recently had a corridor but slowed below the speed
     * threshold: keeps resolving that player's outstanding hit samples (the chunks may still
     * enter view) without ticketing anything new. Bounded by the tracker to the hit window.
     */
    public void updatePosition(UUID playerId, double x, double z) {
        if (this.closing.get()) return;
        this.evaluateHits(playerId, Mth.floor(x) >> 4, Mth.floor(z) >> 4);
        this.publishGauges();
    }

    /**
     * Retracts every ticket held for this player: called when the player STOPS (speed below
     * threshold). Outstanding hit samples are left to resolve on their own deadline — a
     * retracted ticket whose chunk still enters view was still a good prediction, and the
     * tracker keeps a position-only feed running for the hit window so they can resolve.
     */
    public void removePlayer(UUID playerId) {
        final PlayerCorridor corridor = this.corridors.remove(playerId);
        if (corridor == null) return;
        final LongIterator iterator = corridor.ticketed.iterator();
        while (iterator.hasNext()) {
            this.releaseRef(iterator.nextLong());
        }
        this.publishGauges();
    }

    /**
     * {@link #removePlayer} plus immediate MISS resolution of the player's outstanding hit
     * samples: called when the player DEPARTED this world (disconnect or dimension change) —
     * no further positions will ever arrive, so whatever was predicted for them here is wasted
     * generation by definition and is counted as such right away.
     */
    public void purgePlayer(UUID playerId) {
        this.removePlayer(playerId);
        for (int i = this.pendingHits.size() - 1; i >= 0; i--) {
            if (this.pendingHits.get(i).playerId.equals(playerId)) {
                this.pendingHits.remove(i);
                PredictiveGenMetrics.MISSES.incrementAndGet();
            }
        }
        this.publishGauges();
    }

    /**
     * Called by {@link NoTickSystem} right after {@code PlayerNoTickLoader.tick()} on the
     * scheduler thread. Static-final-gated: with the flag OFF this whole path is never even
     * constructed (see {@code NoTickSystem}); the gate here is belt-and-braces.
     */
    public void tick() {
        if (!PredictiveGen.ENABLED) return;
        try {
            if (this.closing.get()) {
                this.clearAll();
                return;
            }
            this.expireHits(System.nanoTime());
            this.tickFutures();
            this.publishGauges();
        } catch (Throwable t) {
            // Never let the predictor take down the shared notickvd scheduler tick.
            LOGGER.error("[predictive-gen] tick failed", t);
        }
    }

    public void close() {
        this.closing.set(true);
        PredictiveGenMetrics.unregister(this);
    }

    // ===== ticket plumbing (mirrors PlayerNoTickLoader.tickFutures/addOneTicket/loadChunk*) =====

    private void tickFutures() {
        this.chunkLoadFutures.removeIf(CompletableFuture::isDone);
        if (this.closing.get()) return;
        while (this.chunkLoadFutures.size() < PredictiveGen.MAX_CONCURRENT_PREDICTED_LOADS && this.addOneTicket()) ;
    }

    /**
     * Issues at most ONE new predicted load, round-robin across players (same
     * {@code getAndMoveToLast} fairness rotation as {@code PlayerNoTickLoader.addOneTicket}).
     * Chunks already ticketed by another player only add a ref — they consume no budget because
     * they cause no new load.
     */
    private boolean addOneTicket() {
        final ObjectBidirectionalIterator<Object2ReferenceMap.Entry<UUID, PlayerCorridor>> iterator =
                this.corridors.object2ReferenceEntrySet().fastIterator();
        while (iterator.hasNext()) {
            final Object2ReferenceMap.Entry<UUID, PlayerCorridor> entry = iterator.next();
            final PlayerCorridor corridor = entry.getValue();
            while (corridor.scanIndex < corridor.desired.length) {
                final long pos = corridor.desired[corridor.scanIndex++];
                if (!corridor.ticketed.add(pos)) continue; // already holds a ref (kept across recompute)
                final int previousRefs = this.ticketRefCounts.addTo(pos, 1);
                if (previousRefs == 0) {
                    final int x = ChunkPos.getX(pos);
                    final int z = ChunkPos.getZ(pos);
                    this.chunkLoadFutures.add(this.loadChunk(x, z));
                    this.recordHitSample(entry.getKey(), pos);
                    PredictiveGenMetrics.TICKETS_ISSUED.incrementAndGet();
                    this.corridors.getAndMoveToLast(entry.getKey());
                    return true; // consumed one budget slot
                }
                // shared with another player's corridor: ref added, no new load — keep scanning
            }
        }
        return false;
    }

    private void releaseRef(long pos) {
        final int previousRefs = this.ticketRefCounts.addTo(pos, -1);
        if (previousRefs <= 1) {
            this.ticketRefCounts.remove(pos);
            this.removeTicket0(ChunkPos.getX(pos), ChunkPos.getZ(pos));
            PredictiveGenMetrics.TICKETS_RETRACTED.incrementAndGet();
        }
    }

    private CompletableFuture<Void> loadChunk(int x, int z) {
        final CompletableFuture<Void> future = this.loadChunk0(x, z);
        future.thenRunAsync(() -> {
            try {
                this.chunkLoadFutures.remove(future);
                this.tickFutures();
            } catch (Throwable t) {
                LOGGER.error("Error while predictively loading chunk [{}, {}]", x, z, t);
            }
        }, GlobalExecutors.asyncScheduler); // == NoTickSystem.executor: the c2me-sched thread
        return future;
    }

    private CompletableFuture<Void> loadChunk0(int x, int z) {
        final ChunkPos pos = new ChunkPos(x, z);
        final ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder =
                ((IChunkSystemAccess) this.tacs).c2me$getTheChunkSystem().addTicket(
                        pos,
                        TICKET_TYPE,
                        pos,
                        NewChunkStatus.SERVER_ACCESSIBLE_CHUNK_SENDING,
                        StatusAdvancingScheduler.NO_OP
                );
        this.noTickSystem.superchunk$runBeforeTicketTicks(
                () -> this.tacs.getDistanceManager().addTicket(VANILLA_TICKET_TYPE, pos, VANILLA_TICKET_LEVEL, pos));
        return holder.getFutureForStatus(NewChunkStatus.SERVER_ACCESSIBLE);
    }

    private void removeTicket0(int x, int z) {
        final ChunkPos pos = new ChunkPos(x, z);
        ((IChunkSystemAccess) this.tacs).c2me$getTheChunkSystem().removeTicket(
                pos,
                TICKET_TYPE,
                pos,
                NewChunkStatus.SERVER_ACCESSIBLE_CHUNK_SENDING
        );
        this.noTickSystem.superchunk$runBeforeTicketTicks(
                () -> this.tacs.getDistanceManager().removeTicket(VANILLA_TICKET_TYPE, pos, VANILLA_TICKET_LEVEL, pos));
    }

    private void clearAll() {
        final LongIterator iterator = this.ticketRefCounts.keySet().iterator();
        while (iterator.hasNext()) {
            final long pos = iterator.nextLong();
            this.removeTicket0(ChunkPos.getX(pos), ChunkPos.getZ(pos));
            iterator.remove();
        }
        this.corridors.clear();
        this.pendingHits.clear();
        this.publishGauges();
    }

    // ===== hit-rate accounting =====

    private void recordHitSample(UUID playerId, long pos) {
        if (this.pendingHits.size() >= MAX_PENDING_HIT_SAMPLES) {
            this.pendingHits.remove(0); // drop oldest as untracked
            PredictiveGenMetrics.HIT_SAMPLES_DROPPED.incrementAndGet();
        }
        final long deadline = System.nanoTime() + (long) (PredictiveGen.HIT_WINDOW_SECONDS * 1.0e9);
        this.pendingHits.add(new PendingHit(playerId, pos, deadline));
    }

    private void evaluateHits(UUID playerId, int playerChunkX, int playerChunkZ) {
        final int radius = this.viewDistance;
        for (int i = this.pendingHits.size() - 1; i >= 0; i--) {
            final PendingHit sample = this.pendingHits.get(i);
            if (!sample.playerId.equals(playerId)) continue;
            final int chebyshev = Math.max(
                    Math.abs(ChunkPos.getX(sample.chunkPos) - playerChunkX),
                    Math.abs(ChunkPos.getZ(sample.chunkPos) - playerChunkZ));
            if (chebyshev <= radius) {
                this.pendingHits.remove(i);
                PredictiveGenMetrics.HITS.incrementAndGet();
            }
        }
    }

    private void expireHits(long nowNanos) {
        for (int i = this.pendingHits.size() - 1; i >= 0; i--) {
            if (this.pendingHits.get(i).deadlineNanos - nowNanos < 0) {
                this.pendingHits.remove(i);
                PredictiveGenMetrics.MISSES.incrementAndGet();
            }
        }
    }

    private void publishGauges() {
        int desired = 0;
        for (PlayerCorridor corridor : this.corridors.values()) {
            desired += corridor.desired.length;
        }
        this.gaugeActivePlayers = this.corridors.size();
        this.gaugeDesiredChunks = desired;
        this.gaugeTicketedChunks = this.ticketRefCounts.size();
        this.gaugeInFlightLoads = this.chunkLoadFutures.size();
        this.gaugePendingHitSamples = this.pendingHits.size();
    }

    // ===== corridor geometry =====

    /**
     * Enumerates the corridor from (x, z) to the position projected
     * {@link PredictiveGen#LOOKAHEAD_SECONDS} ahead along the velocity vector, as packed chunk
     * positions ordered nearest-first along the path, capped at
     * {@link PredictiveGen#MAX_PREDICTED_CHUNKS}. Corridor width =
     * min(viewDistance, {@link PredictiveGen#CORRIDOR_WIDTH_CHUNKS}) chunks (half-width each side
     * of the path line); the path is sampled every 8 blocks so no diagonal chunk is skipped, and
     * each sample contributes its chebyshev ring cells center-out so ordering stays nearest-first.
     */
    static long[] computeCorridor(double x, double z, double vxPerTick, double vzPerTick, int viewDistance) {
        final double lookaheadTicks = PredictiveGen.LOOKAHEAD_SECONDS * 20.0;
        final double endX = x + vxPerTick * lookaheadTicks;
        final double endZ = z + vzPerTick * lookaheadTicks;
        final int halfWidth = Math.max(0, Math.min(viewDistance, PredictiveGen.CORRIDOR_WIDTH_CHUNKS) / 2);
        final int cap = PredictiveGen.MAX_PREDICTED_CHUNKS;

        final LongOpenHashSet seen = new LongOpenHashSet(Math.min(cap * 2, 256));
        final LongArrayList out = new LongArrayList(Math.min(cap, 128));

        final double dx = endX - x;
        final double dz = endZ - z;
        final double distance = Math.sqrt(dx * dx + dz * dz);
        final int steps = Math.min((int) Math.ceil(distance / 8.0), 4096); // 8 blocks = half a chunk per sample

        outer:
        for (int i = 0; i <= steps; i++) {
            final double t = steps == 0 ? 0.0 : (double) i / steps;
            final int centerX = Mth.floor(x + dx * t) >> 4;
            final int centerZ = Mth.floor(z + dz * t) >> 4;
            for (int r = 0; r <= halfWidth; r++) {
                if (r == 0) {
                    if (!addCorridorChunk(seen, out, centerX, centerZ, cap)) break outer;
                    continue;
                }
                for (int off = -r; off <= r; off++) { // top + bottom rows of the ring
                    if (!addCorridorChunk(seen, out, centerX + off, centerZ - r, cap)) break outer;
                    if (!addCorridorChunk(seen, out, centerX + off, centerZ + r, cap)) break outer;
                }
                for (int off = -r + 1; off <= r - 1; off++) { // left + right columns (corners done)
                    if (!addCorridorChunk(seen, out, centerX - r, centerZ + off, cap)) break outer;
                    if (!addCorridorChunk(seen, out, centerX + r, centerZ + off, cap)) break outer;
                }
            }
        }
        return out.toLongArray();
    }

    /** @return false when the cap is reached (caller stops enumerating). */
    private static boolean addCorridorChunk(LongOpenHashSet seen, LongArrayList out, int x, int z, int cap) {
        if (out.size() >= cap) return false;
        final long pos = ChunkPos.asLong(x, z);
        if (seen.add(pos)) {
            out.add(pos);
        }
        return out.size() < cap;
    }

    private static final class PlayerCorridor {
        /** Ordered corridor (nearest-first along the path), recomputed each interval. */
        long[] desired = new long[0];
        LongOpenHashSet desiredSet = new LongOpenHashSet();
        /** Chunks this player currently holds a predictor ref on. */
        final LongOpenHashSet ticketed = new LongOpenHashSet();
        /** Next index into {@link #desired} to consider for ticketing. */
        int scanIndex;
    }

    private record PendingHit(UUID playerId, long chunkPos, long deadlineNanos) {
    }
}
