package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common;

import dev.superchunk.com.ishland.c2me.base.common.scheduler.IVanillaChunkManager;
import dev.superchunk.com.ishland.c2me.base.common.scheduler.SchedulingManager;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IThreadedAnvilChunkStorage;
import dev.superchunk.com.ishland.c2me.base.mixin.access.IVersionedChunkStorage;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.structs.ChunkSystemExecutors;
import dev.superchunk.com.ishland.flowsched.scheduler.ExceptionHandlingAction;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemHolder;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemStatus;
import dev.superchunk.com.ishland.flowsched.scheduler.KeyStatusPair;
import dev.superchunk.com.ishland.flowsched.scheduler.StatusAdvancingScheduler;
import dev.superchunk.com.ishland.flowsched.util.Assertions;
import io.reactivex.rxjava3.core.Scheduler;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.util.StaticCache2D;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;

public class TheChunkSystem extends StatusAdvancingScheduler<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> {

    private final Logger LOGGER;

    private final Long2IntMap managedTickets = Long2IntMaps.synchronize(new Long2IntOpenHashMap());
    private final SchedulingManager schedulingManager;
    private final ChunkMap tacs;

    public TheChunkSystem(ChunkMap tacs) {
        super(TheSpeedyObjectFactory.INSTANCE);
        this.tacs = tacs;
        this.schedulingManager =  ((IVanillaChunkManager) tacs).c2me$getSchedulingManager();
        this.LOGGER = LoggerFactory.getLogger("ChunkAccess System of %s".formatted(((IThreadedAnvilChunkStorage) tacs).getWorld().dimension().location()));
        managedTickets.defaultReturnValue(NewChunkStatus.vanillaLevelToStatus.length - 1);
    }

    /**
     * SuperChunk: runs a task at once on the server thread, else queues it there. The chunk futures
     * handed to vanilla and other mods complete through this, so their callbacks run on the server
     * thread as in vanilla, where a FlowSched worker completing a future (an unload failing pending
     * requests, for one) used to run them on that worker instead.
     */
    final Executor vanillaCompletionExecutor = this::runOnMainThread;

    private void runOnMainThread(Runnable command) {
        final BlockableEventLoop<Runnable> mainThread = ((IThreadedAnvilChunkStorage) this.tacs).getMainThreadExecutor();
        if (mainThread.isSameThread()) {
            command.run();
        } else {
            mainThread.execute(command);
        }
    }

    @Override
    protected Executor getBackgroundExecutor() {
        return ChunkSystemExecutors.consolidatingBackgroundExecutor;
    }

    @Override
    protected Scheduler getSchedulerBackedByBackgroundExecutor() {
        return ChunkSystemExecutors.consolidatingBackgroundScheduler;
    }

    @Override
    protected ItemStatus<ChunkPos, ChunkState, ChunkLoadingContext> getUnloadedStatus() {
        return NewChunkStatus.NEW;
    }

    @Override
    protected ChunkLoadingContext makeContext(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder, ItemStatus<ChunkPos, ChunkState, ChunkLoadingContext> nextStatus, KeyStatusPair<ChunkPos, ChunkState, ChunkLoadingContext>[] dependencies, boolean isUpgrade) {
        Assertions.assertTrue(nextStatus instanceof NewChunkStatus);
        final NewChunkStatus nextStatus1 = (NewChunkStatus) nextStatus;

        int radius;
        if (dependencies.length == 0) {
            radius = 0;
        } else {
            int actualDependencies = dependencies.length + 1;
            radius = (int) ((Math.sqrt(actualDependencies) - 1) / 2);
            Assertions.assertTrue((radius * 2 + 1) * (radius * 2 + 1) == actualDependencies);
        }

        return new ChunkLoadingContext(holder, this.tacs, this.schedulingManager, StaticCache2D.create(holder.getKey().x, holder.getKey().z, radius,
                (x, z) -> this.getHolder(new ChunkPos(x, z)).getUserData().get()), dependencies);
    }

    @Override
    protected ExceptionHandlingAction handleTransactionException(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder, ItemStatus<ChunkPos, ChunkState, ChunkLoadingContext> nextStatus, boolean isUpgrade, Throwable throwable) {
        if (isUpgrade) {
            LOGGER.error("Error upgrading chunk {} to \"{}\"", holder.getKey(), nextStatus, throwable);
        } else {
            LOGGER.error("Error downgrading chunk {} to \"{}\"", holder.getKey(), nextStatus, throwable);
        }
        final MinecraftServer server = ((IThreadedAnvilChunkStorage) this.tacs).getWorld().getServer();
        server.execute(() -> server.reportChunkLoadFailure(throwable, ((IVersionedChunkStorage) this.tacs).invokeGetStorageKey(), holder.getKey()));
        return ExceptionHandlingAction.MARK_BROKEN;
    }

    @Override
    protected void onItemCreation(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder) {
        super.onItemCreation(holder);
        holder.getUserData().set(new NewChunkHolderVanillaInterface(this, holder, ((IThreadedAnvilChunkStorage) this.tacs).getWorld(), ((IThreadedAnvilChunkStorage) this.tacs).getLightingProvider(), this.tacs));
        holder.getItem().set(new ChunkState(null, null, null));
    }

    @Override
    protected void onItemRemoval(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder) {
        super.onItemRemoval(holder);
    }

    @Override
    protected void onItemUpgrade(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder, ItemStatus<ChunkPos, ChunkState, ChunkLoadingContext> statusReached) {
        super.onItemUpgrade(holder, statusReached);
        // SuperChunk player.latencyMetrics: stamp the SERVER_ACCESSIBLE_CHUNK_SENDING completion
        // time so the PlayerChunkSender.sendChunk metrics mixin can log send latency
        // (now - this stamp). Volatile write on the scheduler thread; flag OFF = zero work.
        if (dev.superchunk.config.PlayerLatency.LATENCY_METRICS && statusReached == NewChunkStatus.SERVER_ACCESSIBLE_CHUNK_SENDING) {
            final NewChunkHolderVanillaInterface vanillaHolder = holder.getUserData().get();
            if (vanillaHolder != null) {
                vanillaHolder.superchunk$markSendingReady(System.nanoTime());
            }
        }
        final NewChunkStatus statusReached1 = (NewChunkStatus) statusReached;
        final NewChunkStatus prevStatus = (NewChunkStatus) statusReached.getPrev();
        final ChunkProgressListener listener = ((IThreadedAnvilChunkStorage) this.tacs).getWorldGenerationProgressListener();
        if (listener != null && prevStatus.getEffectiveVanillaStatus() != statusReached1.getEffectiveVanillaStatus()) {
            listener.onStatusChange(holder.getKey(), statusReached1.getEffectiveVanillaStatus());
        }
        if (prevStatus.toChunkLevelType() != statusReached1.toChunkLevelType()) {
            ((IThreadedAnvilChunkStorage) this.tacs).getMainThreadExecutor().execute(
                    () -> ((IThreadedAnvilChunkStorage) this.tacs).invokeOnChunkStatusChange(holder.getKey(), statusReached1.toChunkLevelType()));
        }
    }

    @Override
    protected void onItemDowngrade(ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder, ItemStatus<ChunkPos, ChunkState, ChunkLoadingContext> statusReached) {
        super.onItemDowngrade(holder, statusReached);
        final NewChunkStatus statusReached1 = (NewChunkStatus) statusReached;
        final NewChunkStatus prevStatus = (NewChunkStatus) statusReached.getNext();
        final ChunkProgressListener listener = ((IThreadedAnvilChunkStorage) this.tacs).getWorldGenerationProgressListener();
        if (listener != null && prevStatus.getEffectiveVanillaStatus() != statusReached1.getEffectiveVanillaStatus()) {
            listener.onStatusChange(holder.getKey(), statusReached1.getEffectiveVanillaStatus());
        }
        if (prevStatus.toChunkLevelType() != statusReached1.toChunkLevelType()) {
            ((IThreadedAnvilChunkStorage) this.tacs).getMainThreadExecutor().execute(
                    () -> ((IThreadedAnvilChunkStorage) this.tacs).invokeOnChunkStatusChange(holder.getKey(), statusReached1.toChunkLevelType()));
        }
    }

    /**
     * SuperChunk: the full chunks a flushing {@code ChunkMap.saveAllChunks(true)} cannot see, saved
     * on the main thread just before it flushes the storage. Vanilla's flush saves the holders in its
     * holder map and, through {@code processUnloads}, every chunk on its way out. Here a holder leaves
     * that map ({@link NewChunkHolderVanillaInterface#wasAccessibleSinceLastSave()} false) as soon as it
     * drops below SERVER_ACCESSIBLE, but keeps its full chunk until the last unload step
     * ({@code ReadFromDisk.downgradeFromThis}) saves it after a light sync, and can wait there while
     * neighbours still need it. A flush in that window skipped the chunk and returned, so a crash right
     * after {@code /save-all flush} lost ~25% of recently generated chunks (vanilla: none). The later
     * unload save then finds nothing unsaved, as it does after any vanilla save.
     *
     * @return how many chunks were written
     */
    public int saveFullChunksOutsideHolderMap() {
        if (!((IThreadedAnvilChunkStorage) this.tacs).getMainThreadExecutor().isSameThread()) {
            return 0; // the unload steps this races with run on the main thread; never save beside them
        }
        int saved = 0;
        for (ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder : this.snapshotHolders()) {
            final NewChunkHolderVanillaInterface vanillaHolder = holder.getUserData().get();
            if (vanillaHolder == null || vanillaHolder.wasAccessibleSinceLastSave()) {
                continue; // in the holder map: vanilla's own flush loop saved it
            }
            final ChunkState state = holder.getItem().get();
            net.minecraft.world.level.chunk.ChunkAccess chunk = state != null ? state.chunk() : null;
            // ImposterProtoChunk.isUnsaved() is always false: save the full chunk it wraps, as the unload step does
            if (chunk instanceof net.minecraft.world.level.chunk.ImposterProtoChunk imposter) {
                chunk = imposter.getWrapped();
            }
            if (chunk instanceof net.minecraft.world.level.chunk.LevelChunk && ((IThreadedAnvilChunkStorage) this.tacs).invokeSave(chunk)) {
                saved++;
            }
        }
        return saved;
    }

    public ChunkHolder vanillaIf$setLevel(long pos, int level) {
        assert !Thread.holdsLock(this.managedTickets);
        synchronized (this.managedTickets) {
            final int oldLevel = this.managedTickets.put(pos, level);
            NewChunkStatus oldStatus = c2me$getDeferredStatusFromVanillaLevel(oldLevel);
            NewChunkStatus newStatus = c2me$getDeferredStatusFromVanillaLevel(level);
            final ChunkPos key = new ChunkPos(pos);
            if (oldStatus != newStatus) {
                NewChunkHolderVanillaInterface vanillaHolder;
                ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder;
                boolean shouldReturnVanillaHolder;
                if (newStatus != this.getUnloadedStatus()) {
                    holder = this.addTicket(key, TicketTypeExtension.VANILLA_LEVEL, key, newStatus, NO_OP);
                    shouldReturnVanillaHolder = true;
                } else {
                    this.managedTickets.remove(pos);
                    holder = this.getHolder(key);
                    shouldReturnVanillaHolder = false;
                }
                Assertions.assertTrue(holder != null, "Holder should be managed by the vanilla interface");
                assert holder != null;
                vanillaHolder = holder.getUserData().get();
                if (!Config.useLegacyScheduling) {
                    vanillaHolder.updateDeferredStatus(NewChunkStatus.fromVanillaLevel(level));
                }

                if (oldStatus != this.getUnloadedStatus()) {
                    this.removeTicket(key, TicketTypeExtension.VANILLA_LEVEL, key, oldStatus);
                }
                return shouldReturnVanillaHolder ? vanillaHolder : null;
            } else {
                final ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder = this.getHolder(key);
                NewChunkHolderVanillaInterface vanillaHolder;
                if (holder != null) {
                    vanillaHolder = holder.getUserData().get();

                } else {
                    vanillaHolder = null;
                }
                if (!Config.useLegacyScheduling && vanillaHolder != null) {
                    vanillaHolder.updateDeferredStatus(NewChunkStatus.fromVanillaLevel(level));
                }
                if (newStatus != this.getUnloadedStatus() && vanillaHolder != null) {
                    return vanillaHolder;
                }
                return null;
            }
        }
    }

    private static NewChunkStatus c2me$getDeferredStatusFromVanillaLevel(int level) {
        NewChunkStatus status = NewChunkStatus.fromVanillaLevel(level);
        if (!Config.useLegacyScheduling) {
            if (status == NewChunkStatus.NEW) {
                return status;
            } else if (status.ordinal() < NewChunkStatus.SERVER_ACCESSIBLE.ordinal()) {
                return NewChunkStatus.DEFERRED;
            } else {
                return status;
            }
        } else {
            return status;
        }
    }

    public int vanillaIf$getManagedLevel(long pos) {
        return this.managedTickets.get(pos);
    }
}
