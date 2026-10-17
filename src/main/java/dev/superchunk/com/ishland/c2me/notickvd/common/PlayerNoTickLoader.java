package dev.superchunk.com.ishland.c2me.notickvd.common;

import dev.superchunk.com.ishland.c2me.notickvd.common.iterators.ChunkIterator;
import dev.superchunk.com.ishland.c2me.notickvd.common.iterators.SpiralIterator;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ChunkState;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.NewChunkHolderVanillaInterface;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ducks.IChunkSystemAccess;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemHolder;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemTicket;
import dev.superchunk.com.ishland.flowsched.scheduler.StatusAdvancingScheduler;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;

/**
 * Forge 1.20.1 port. Two ticket paths (portplans/vmp-notickvd-player.md §3.2 + the Wave-C
 * deviation recorded under '## Implementation deviations'):
 * <ul>
 *   <li><b>Chunk system ENABLED (Wave C default):</b> the neo path verbatim — a FlowSched ticket
 *       targeting {@code SERVER_ACCESSIBLE_CHUNK_SENDING} (so the CHUNK_SENDING send machinery
 *       sees the chunk exactly like on neo) plus the vanilla level-33 ticket; completion =
 *       {@code holder.getFutureForStatus(SERVER_ACCESSIBLE)}.</li>
 *   <li><b>Chunk system DISABLED ({@code -Dsuperchunk.chunksystem.disable}):</b> the plan's
 *       vanilla-ticket fallback — vanilla level-33 ticket only; the holder lookup is enqueued
 *       onto the main-thread after-ticket-ticks queue from within the before-queue drain, which
 *       guarantees the SAME runAllUpdates() call has created the holder; completion =
 *       {@code ChunkHolder.getFullChunkFuture()} (fulfilled via {@code prepareAccessibleChunk}).</li>
 * </ul>
 * The vanilla level-33 ticket is issued on BOTH paths (neo parity; keeps the vanilla
 * DistanceManager/TickingTracker view consistent with the no-tick ring).
 */
public class PlayerNoTickLoader {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final ItemTicket.TicketType TICKET_TYPE = new ItemTicket.TicketType("c2me:notickvd");
    public static final TicketType<ChunkPos> VANILLA_TICKET_TYPE = TicketType.create("c2me_notickvd", Comparator.comparingLong(ChunkPos::toLong));

    /** Whether the Wave-C c2me chunk system is live (never throws — safe default false). */
    public static final boolean CHUNK_SYSTEM_TICKETS = computeChunkSystemEnabled();

    private static boolean computeChunkSystemEnabled() {
        try {
            return dev.superchunk.com.ishland.c2me.rewrites.chunksystem.ModuleEntryPoint.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private final ChunkMap tacs;
    private final NoTickSystem noTickSystem;
    private final Long2ReferenceLinkedOpenHashMap<ChunkIterator> iterators = new Long2ReferenceLinkedOpenHashMap<>();
    private final LongSet managedChunks = new LongLinkedOpenHashSet();
    private final LongFunction<ChunkIterator> createFunction = pos -> new SpiralIterator(ChunkPos.getX(pos), ChunkPos.getZ(pos), this.viewDistance);
    private final ReferenceArrayList<CompletableFuture<Void>> chunkLoadFutures = new ReferenceArrayList<>();
    private final StuckLoadSweeper stuckLoads = new StuckLoadSweeper();
    private final AtomicBoolean closing = new AtomicBoolean(false);

    private int viewDistance = 12;
    private boolean dirtyManagedChunks = false;
    private boolean recreateIterators = false;
    private volatile long pendingLoadsCountSnapshot = 0L;
    private volatile int managedChunksCountSnapshot = 0;

    public PlayerNoTickLoader(ChunkMap tacs, NoTickSystem noTickSystem) {
        this.tacs = tacs;
        this.noTickSystem = noTickSystem;
    }

    public void addSource(ChunkPos chunkPos) {
        this.iterators.computeIfAbsent(chunkPos.toLong(), this.createFunction);
    }

    public void removeSource(ChunkPos chunkPos) {
        this.iterators.remove(chunkPos.toLong());
        this.dirtyManagedChunks = true;
    }

    public void setViewDistance(int viewDistance) {
        this.viewDistance = viewDistance;
        this.recreateIterators = true;
    }

    public void tick() {
        if (this.closing.get()) {
            clearTickets();
            return;
        }

        if (this.recreateIterators) {
            this.dirtyManagedChunks = true;
            ObjectBidirectionalIterator<Long2ReferenceMap.Entry<ChunkIterator>> iterator = this.iterators.long2ReferenceEntrySet().fastIterator();
            while (iterator.hasNext()) {
                Long2ReferenceMap.Entry<ChunkIterator> entry = iterator.next();
                entry.setValue(this.createFunction.apply(entry.getLongKey()));
            }
            this.recreateIterators = false;
        }

        if (this.dirtyManagedChunks) {
            LongIterator chunkIterator = this.managedChunks.iterator();
            ObjectBidirectionalIterator<Long2ReferenceMap.Entry<ChunkIterator>> iteratorIterator = this.iterators.long2ReferenceEntrySet().fastIterator();
            while (chunkIterator.hasNext()) {
                long pos = chunkIterator.nextLong();
                int packedX = ChunkPos.getX(pos);
                int packedZ = ChunkPos.getZ(pos);

                boolean isUsed = false;
                if (iteratorIterator.hasNext()) {
                    while (iteratorIterator.hasNext()) {
                        Long2ReferenceMap.Entry<ChunkIterator> entry = iteratorIterator.next();
                        isUsed |= entry.getValue().isInRange(packedX, packedZ);
                    }
                } else if (iteratorIterator.hasPrevious()) {
                    while (iteratorIterator.hasPrevious()) {
                        Long2ReferenceMap.Entry<ChunkIterator> entry = iteratorIterator.previous();
                        isUsed |= entry.getValue().isInRange(packedX, packedZ);
                    }
                }

                if (!isUsed) {
                    this.removeTicket0(packedX, packedZ);
                    chunkIterator.remove();
                }
            }
            this.dirtyManagedChunks = false;
        }

        this.tickFutures();
    }

    private void clearTickets() {
        LongIterator iterator = this.managedChunks.iterator();
        while (iterator.hasNext()) {
            long pos = iterator.nextLong();

            this.removeTicket0(ChunkPos.getX(pos), ChunkPos.getZ(pos));

            iterator.remove();
        }
    }

    void tickFutures() {
        // Forge: only the c2me chunk system has broken holders to sweep. With it disabled
        // (superchunk.chunksystem.disable) the ChunkMap does not implement IChunkSystemAccess.
        if (CHUNK_SYSTEM_TICKETS) this.stuckLoads.sweep(((IChunkSystemAccess) this.tacs).c2me$getTheChunkSystem());
        this.chunkLoadFutures.removeIf(CompletableFuture::isDone);

        if (this.closing.get()) return;
        while (this.chunkLoadFutures.size() < Config.maxConcurrentChunkLoads && this.addOneTicket());

        {
            long pendingLoadsCount = 0L;
            ObjectBidirectionalIterator<Long2ReferenceMap.Entry<ChunkIterator>> iterator = this.iterators.long2ReferenceEntrySet().fastIterator();
            while (iterator.hasNext()) {
                Long2ReferenceMap.Entry<ChunkIterator> entry = iterator.next();
                pendingLoadsCount += entry.getValue().remaining();
            }
            this.pendingLoadsCountSnapshot = pendingLoadsCount;
        }

        this.managedChunksCountSnapshot = this.managedChunks.size();
    }

    /** Diagnostic gauge (see {@code ChunkCensus}): size of the no-tick ring this loader holds. */
    public int getManagedChunksCount() {
        return this.managedChunksCountSnapshot;
    }

    private boolean addOneTicket() {
        ObjectBidirectionalIterator<Long2ReferenceMap.Entry<ChunkIterator>> iteratorIterator = this.iterators.long2ReferenceEntrySet().fastIterator();
        while (iteratorIterator.hasNext()) {
            Long2ReferenceMap.Entry<ChunkIterator> entry = iteratorIterator.next();
            ChunkIterator iterator = entry.getValue();
            while (iterator.hasNext()) {
                ChunkPos pos = iterator.next();
                if (this.managedChunks.add(pos.toLong())) {
                    this.chunkLoadFutures.add(loadChunk(pos.x, pos.z));
                    this.iterators.getAndMoveToLast(entry.getLongKey());
                    return true;
                }
            }
        }

        return false;
    }

    private CompletableFuture<Void> loadChunk(int x, int z) {
        CompletableFuture<Void> future = this.stuckLoads.track(this.loadChunk0(x, z), new ChunkPos(x, z));
        future.thenRunAsync(() -> {
            try {
                this.chunkLoadFutures.remove(future);
                this.tickFutures();
            } catch (Throwable t) {
                LOGGER.error("Error while loading chunk [{}, {}]", x, z, t);
            }
        }, this.noTickSystem.executor);
        return future;
    }

    private CompletableFuture<Void> loadChunk0(int x, int z) {
        final ChunkPos pos = new ChunkPos(x, z);
        if (CHUNK_SYSTEM_TICKETS) {
            // neo path: FlowSched ticket + vanilla level-33 ticket.
            ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder =
                    ((IChunkSystemAccess) this.tacs).c2me$getTheChunkSystem().addTicket(
                            pos,
                            TICKET_TYPE,
                            pos,
                            NewChunkStatus.SERVER_ACCESSIBLE_CHUNK_SENDING,
                            StatusAdvancingScheduler.NO_OP
                    );
            this.noTickSystem.mainBeforeTicketTicks.add(() -> this.tacs.getDistanceManager().addTicket(VANILLA_TICKET_TYPE, pos, 33, pos));
            return holder.getFutureForStatus(NewChunkStatus.SERVER_ACCESSIBLE);
        }
        // vanilla-ticket fallback (chunk system disabled).
        final CompletableFuture<Void> future = new CompletableFuture<>();
        this.noTickSystem.mainBeforeTicketTicks.add(() -> {
            this.tacs.getDistanceManager().addTicket(VANILLA_TICKET_TYPE, pos, 33, pos);
            // Enqueue the holder lookup from the MAIN thread while draining the before-queue:
            // guarantees the SAME runAllUpdates() call (HEAD drains before-queue, RETURN drains
            // after-queue) has created the holder before we look it up.
            // (ConcurrentLinkedQueue supports add-during-drain, so the nested enqueue is safe.)
            this.noTickSystem.mainAfterTicketTicks.add(() -> {
                final ChunkHolder holder = this.tacs.getUpdatingChunkIfPresent(pos.toLong()); // AT-public m_140174_
                if (holder == null) { // defensive; frees the slot
                    future.complete(null);
                    return;
                }
                holder.getFullChunkFuture().whenComplete((either, throwable) -> future.complete(null));
            });
        });
        return future;
    }

    private void removeTicket0(int x, int z) {
        final ChunkPos pos = new ChunkPos(x, z);
        if (CHUNK_SYSTEM_TICKETS) {
            ((IChunkSystemAccess) this.tacs).c2me$getTheChunkSystem().removeTicket(
                    pos,
                    TICKET_TYPE,
                    pos,
                    NewChunkStatus.SERVER_ACCESSIBLE_CHUNK_SENDING
            );
        }
        this.noTickSystem.mainBeforeTicketTicks.add(() -> this.tacs.getDistanceManager().removeTicket(VANILLA_TICKET_TYPE, pos, 33, pos));
    }

    public long getPendingLoadsCount() {
        return this.pendingLoadsCountSnapshot;
    }

    public void close() {
        this.closing.set(true);
    }
}
