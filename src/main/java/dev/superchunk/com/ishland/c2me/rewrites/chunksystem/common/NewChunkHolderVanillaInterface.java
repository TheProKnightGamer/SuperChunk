package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common;

import dev.superchunk.com.ishland.c2me.base.common.theinterface.IFastChunkHolder;
import dev.superchunk.com.ishland.c2me.base.common.util.SneakyThrow;
import dev.superchunk.config.PlayerLatency;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemHolder;
import dev.superchunk.com.ishland.flowsched.scheduler.StatusAdvancingScheduler;
import dev.superchunk.com.ishland.flowsched.util.Assertions;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Bridges the FlowSched-driven chunk system onto vanilla's {@link ChunkHolder} API.
 * <p>
 * NeoForge/1.21.1 port note: upstream (1.21.4-era Yarn) overrode an {@code AbstractChunkHolder}
 * load/generate/createLoader model that does not exist in 1.21.1. Here we extend the 1.21.1
 * {@link ChunkHolder}/{@code GenerationChunkHolder} directly, drive its three full-status futures
 * ({@code fullChunkFuture}/{@code tickingChunkFuture}/{@code entityTickingChunkFuture}) and
 * generation futures from the FlowSched holder, and no-op {@code updateFutures} so vanilla never
 * runs its own promotion pipeline.
 */
public class NewChunkHolderVanillaInterface extends ChunkHolder implements IFastChunkHolder {

    private static final Logger LOGGER = LoggerFactory.getLogger(NewChunkHolderVanillaInterface.class);
    private static final CompletableFuture<Void> COMPLETED_VOID_FUTURE = CompletableFuture.completedFuture(null);

    private final TheChunkSystem chunkSystem;
    private final ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> newHolder;
    private NewChunkStatus deferredStatus = null;
    private NewChunkStatus loadedDeferredStatus = null;

    public NewChunkHolderVanillaInterface(TheChunkSystem chunkSystem, ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> newHolder, LevelHeightAccessor world, LevelLightEngine lightingProvider, ChunkHolder.PlayerProvider playersWatchingChunkProvider) {
        super(newHolder.getKey(), ChunkLevel.MAX_LEVEL, world, lightingProvider, (pos1, levelGetter, targetLevel, levelSetter) -> {}, playersWatchingChunkProvider);
        this.chunkSystem = chunkSystem;
        this.newHolder = newHolder;
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<ChunkResult<ChunkAccess>> wrapOptionalChunkFuture(CompletableFuture<?> future) {
        if (future.isDone()) {
            if (future.isCompletedExceptionally()) {
                Throwable throwable = future.exceptionNow();
                while (throwable instanceof CompletionException) {
                    throwable = throwable.getCause();
                }
                if (throwable == ItemHolder.UNLOADED_EXCEPTION) {
                    return GenerationChunkHolderUnloaded.UNLOADED_CHUNK_FUTURE;
                } else {
                    return (CompletableFuture<ChunkResult<ChunkAccess>>) future; // it's fine to cast here
                }
            } else {
                return CompletableFuture.completedFuture(ChunkResult.of(this.newHolder.getItem().get().chunk()));
            }
        }
        return future.handle((unused, throwable) -> {
            while (throwable instanceof CompletionException) {
                throwable = throwable.getCause();
            }
            if (throwable == ItemHolder.UNLOADED_EXCEPTION) {
                return GenerationChunkHolderUnloaded.UNLOADED_CHUNK;
            } else if (throwable != null) {
                SneakyThrow.sneaky(throwable);
                return null;
            } else {
                return ChunkResult.of(this.newHolder.getItem().get().chunk());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<ChunkResult<ChunkAccess>> wrapOptionalChunkProtoFuture(CompletableFuture<?> future) {
        if (future.isDone()) {
            if (future.isCompletedExceptionally()) {
                Throwable throwable = future.exceptionNow();
                while (throwable instanceof CompletionException) {
                    throwable = throwable.getCause();
                }
                if (throwable == ItemHolder.UNLOADED_EXCEPTION) {
                    return GenerationChunkHolderUnloaded.UNLOADED_CHUNK_FUTURE;
                } else {
                    return (CompletableFuture<ChunkResult<ChunkAccess>>) future; // it's fine to cast here
                }
            } else {
                return CompletableFuture.completedFuture(ChunkResult.of(this.newHolder.getItem().get().protoChunk()));
            }
        }
        return future.handle((unused, throwable) -> {
            while (throwable instanceof CompletionException) {
                throwable = throwable.getCause();
            }
            if (throwable == ItemHolder.UNLOADED_EXCEPTION) {
                return GenerationChunkHolderUnloaded.UNLOADED_CHUNK;
            } else if (throwable != null) {
                SneakyThrow.sneaky(throwable);
                return null;
            } else {
                return ChunkResult.of(this.newHolder.getItem().get().protoChunk());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<ChunkResult<LevelChunk>> wrapOptionalWorldChunkFuture(CompletableFuture<?> future) {
        if (future.isDone()) {
            if (future.isCompletedExceptionally()) {
                Throwable throwable = future.exceptionNow();
                while (throwable instanceof CompletionException) {
                    throwable = throwable.getCause();
                }
                if (throwable == ItemHolder.UNLOADED_EXCEPTION) {
                    return ChunkHolder.UNLOADED_LEVEL_CHUNK_FUTURE;
                } else {
                    return (CompletableFuture<ChunkResult<LevelChunk>>) future; // it's fine to cast here
                }
            } else {
                final ChunkAccess chunk = this.newHolder.getItem().get().chunk();
                if (chunk instanceof LevelChunk worldChunk) {
                    return CompletableFuture.completedFuture(ChunkResult.of(worldChunk));
                } else {
                    return ChunkHolder.UNLOADED_LEVEL_CHUNK_FUTURE; // might have unloaded at this point
                }
            }
        }
        return future.handle((unused, throwable) -> {
            while (throwable instanceof CompletionException) {
                throwable = throwable.getCause();
            }
            if (throwable == ItemHolder.UNLOADED_EXCEPTION) {
                return ChunkHolder.UNLOADED_LEVEL_CHUNK;
            } else if (throwable != null) {
                SneakyThrow.sneaky(throwable);
                return null;
            } else {
                final ChunkAccess chunk = this.newHolder.getItem().get().chunk();
                if (chunk instanceof LevelChunk worldChunk) {
                    return ChunkResult.of(worldChunk);
                } else {
                    return ChunkHolder.UNLOADED_LEVEL_CHUNK; // might have unloaded at this point
                }
            }
        });
    }

    /**
     * @apiNote it is the caller's responsibility to ensure the holder is kept loaded
     */
    public void updateDeferredStatus(NewChunkStatus status) {
        synchronized (this) {
            if (this.deferredStatus == status) return;
            if (this.deferredStatus == null) { // && status != null
                Assertions.assertTrue(this.loadedDeferredStatus == null);
                this.deferredStatus = status;
                return;
            }
            if (status == null) { // && this.deferredStatus != null
                if (this.loadedDeferredStatus != null) {
                    ChunkPos pos1 = this.getPos();
                    this.chunkSystem.removeTicket(pos1, TicketTypeExtension.VANILLA_DEFERRED_LOAD, pos1, this.loadedDeferredStatus);
                    this.loadedDeferredStatus = null;
                }
                this.deferredStatus = status;
            }
            // both nonnull and different
            if (this.loadedDeferredStatus != null && this.loadedDeferredStatus.ordinal() > status.ordinal()) {
                ChunkPos pos1 = this.getPos();
                if (status.getPrev() != null) { // don't add unloaded tickets
                    ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder1 = this.chunkSystem.addTicket(pos1, TicketTypeExtension.VANILLA_DEFERRED_LOAD, pos1, status, StatusAdvancingScheduler.NO_OP);
                    Assertions.assertTrue(holder1 == this.newHolder);
                    this.chunkSystem.removeTicket(pos1, TicketTypeExtension.VANILLA_DEFERRED_LOAD, pos1, this.loadedDeferredStatus);
                    this.loadedDeferredStatus = status;
                } else {
                    this.chunkSystem.removeTicket(pos1, TicketTypeExtension.VANILLA_DEFERRED_LOAD, pos1, this.loadedDeferredStatus);
                    this.loadedDeferredStatus = null;
                }
            }
            this.deferredStatus = status;
        }
    }

    public void triggerDeferredLoad(NewChunkStatus requestedStatus) {
        if (Config.useLegacyScheduling) return;
        synchronized (this) {
            if (this.loadedDeferredStatus != null && this.loadedDeferredStatus.ordinal() >= requestedStatus.ordinal()) {
                return; // nothing to do
            }
            if (this.deferredStatus == null || this.deferredStatus.ordinal() < requestedStatus.ordinal()) {
                return; // not deferred
            }
            // the holder should be valid here
            NewChunkStatus ticketToDiscard = this.loadedDeferredStatus;
            ChunkPos pos1 = this.getPos();
            ItemHolder<ChunkPos, ChunkState, ChunkLoadingContext, NewChunkHolderVanillaInterface> holder1 = this.chunkSystem.addTicket(pos1, TicketTypeExtension.VANILLA_DEFERRED_LOAD, pos1, requestedStatus, StatusAdvancingScheduler.NO_OP);
            Assertions.assertTrue(holder1 == this.newHolder);
            if (ticketToDiscard != null) {
                this.chunkSystem.removeTicket(pos1, TicketTypeExtension.VANILLA_DEFERRED_LOAD, pos1, ticketToDiscard);
            }
            this.loadedDeferredStatus = requestedStatus;
        }
    }

    @Override
    public CompletableFuture<ChunkResult<LevelChunk>> getTickingChunkFuture() {
        synchronized (this.newHolder) {
            return wrapOptionalWorldChunkFuture(this.newHolder.getFutureForStatus0(NewChunkStatus.BLOCK_TICKING));
        }
    }

    @Override
    public CompletableFuture<ChunkResult<LevelChunk>> getEntityTickingChunkFuture() {
        synchronized (this.newHolder) {
            return wrapOptionalWorldChunkFuture(this.newHolder.getFutureForStatus0(NewChunkStatus.ENTITY_TICKING));
        }
    }

    @Override
    public CompletableFuture<ChunkResult<LevelChunk>> getFullChunkFuture() {
        synchronized (this.newHolder) {
            return wrapOptionalWorldChunkFuture(this.newHolder.getFutureForStatus0(NewChunkStatus.SERVER_ACCESSIBLE));
        }
    }

    @Nullable
    @Override
    public LevelChunk getTickingChunk() {
        return this.getTickingChunkFuture().getNow(UNLOADED_LEVEL_CHUNK).orElse(null);
    }

    /**
     * SuperChunk {@code player.sendAtChunkSending} (P1) — 1.21.1 port-defect fix.
     *
     * <p>The port overrides {@link #getTickingChunk()} to the FlowSched BLOCK_TICKING future but
     * never overrode vanilla {@code ChunkHolder.getChunkToSend()}
     * ({@code !sendSync.isDone() ? null : getTickingChunk()}). Consequence: chunks only become
     * client-sendable at BLOCK_TICKING — behind the PLAYER-ticket level throttler, the 8-neighbor
     * gate and an extra main-thread hop — and with view distance &gt; simulation distance the outer
     * no-tick rings never reach BLOCK_TICKING, so their pending-to-send chunks (marked by notickvd's
     * {@code MixinServerAccessibleChunkSending} at the CHUNK_SENDING upgrade via
     * {@code ChunkMap.onChunkReadyToSend}) are filtered back to null by
     * {@code PlayerChunkSender.collectChunksToSend} every tick and NEVER send.
     *
     * <p>With the flag ON, the chunk is exposed to the sender as soon as:
     * <ol>
     *   <li>vanilla {@code sendSync} is done — {@code MixinChunkHolder.failFastIncompatibility}
     *       nulls the vanilla promotion futures but deliberately leaves {@code sendSync} intact,
     *       so light-send dependencies added by {@code ChunkMap} ({@code addSendDependency(
     *       lightEngine.waitForPendingTasks(...))}) remain respected here; and</li>
     *   <li>the FlowSched status is at least {@link NewChunkStatus#SERVER_ACCESSIBLE_CHUNK_SENDING}
     *       — the status whose entire purpose is chunk sending, with LIGHT-status deps on all
     *       8 neighbors (see the notickvd mixin's {@code <clinit>} injection).</li>
     * </ol>
     * The held item is a real {@link LevelChunk} from SERVER_ACCESSIBLE onward
     * ({@code ServerAccessible.upgradeToThis} stores {@code new ChunkState(worldChunk, ...)});
     * the {@code instanceof} below guards the unload/downgrade race.
     *
     * <p>Note: upstream C2ME intends exposure of non-postprocessed chunks to players at this
     * status — see the {@code chunkSystem.suppressGhostMushrooms} comment in
     * {@link Config} (MC-276863 workaround, "notickvd ... exposes non-postprocessed chunks
     * to players").
     *
     * <p>Flag OFF (default): delegates to the inherited vanilla method — shipped behavior
     * byte-identical.
     */
    @Nullable
    @Override
    public LevelChunk getChunkToSend() {
        if (!PlayerLatency.SEND_AT_CHUNK_SENDING) {
            return super.getChunkToSend();
        }
        if (!this.getSendSyncFuture().isDone()) {
            return null;
        }
        if (this.newHolder.getStatus().ordinal() < NewChunkStatus.SERVER_ACCESSIBLE_CHUNK_SENDING.ordinal()) {
            return null;
        }
        final ChunkState state = this.newHolder.getItem().get();
        if (state != null && state.chunk() instanceof LevelChunk worldChunk) {
            return worldChunk;
        }
        return null;
    }

    /**
     * SuperChunk {@code player.latencyMetrics}: {@link System#nanoTime()} stamp of the most recent
     * upgrade to {@link NewChunkStatus#SERVER_ACCESSIBLE_CHUNK_SENDING} (written by
     * {@link TheChunkSystem#onItemUpgrade}, only while the metrics flag is ON; 0 = never stamped).
     * Read by the {@code PlayerChunkSender.sendChunk} metrics mixin to log
     * "now - CHUNK_SENDING completion" send latency.
     */
    private volatile long superchunk$sendingReadyNanos = 0L;

    public void superchunk$markSendingReady(long nanoTime) {
        this.superchunk$sendingReadyNanos = nanoTime;
    }

    public long superchunk$getSendingReadyNanos() {
        return this.superchunk$sendingReadyNanos;
    }

    public CompletableFuture<?> getSavingFuture() {
        synchronized (this.newHolder) {
            if (this.newHolder.isOpen()) {
                return this.newHolder.getOpFuture(); // already safe to use as the implementation creates a new future
            } else {
                return COMPLETED_VOID_FUTURE;
            }
        }
    }

    public CompletableFuture<?> getPostProcessingFuture() {
        // C2ME does not gate sending on a separate post-processing future; complete immediately.
        return COMPLETED_VOID_FUTURE;
    }

    public FullChunkStatus getLevelType() {
        return ChunkLevel.fullStatus(this.getTicketLevel());
    }

    @Override
    public ChunkPos getPos() {
        return this.newHolder.getKey();
    }

    @Override
    public int getTicketLevel() {
        return ((NewChunkStatus) this.newHolder.getTargetStatus()).toVanillaLevel();
    }

    @Override
    public int getQueueLevel() {
        return ((NewChunkStatus) this.newHolder.getStatus()).toVanillaLevel();
    }

    @Override
    public void setTicketLevel(int level) {
        // no-op: ticket levels are driven by the chunk system, not vanilla
    }

    @Override
    public void setQueueLevel(int queueLevel) {
        // no-op
    }

    @Override
    protected void updateFutures(ChunkMap chunkStorage, Executor executor) {
        // no-op: the chunk system drives status promotion itself
    }

    public boolean isAccessible() {
        return this.newHolder.getStatus().ordinal() >= NewChunkStatus.SERVER_ACCESSIBLE.ordinal();
    }

    @Override
    public boolean isReadyForSaving() {
        return this.getGenerationRefCount() == 0 && this.getSavingFuture().isDone();
    }

    @Override
    public CompletableFuture<ChunkResult<ChunkAccess>> getOrCreateFuture(ChunkStatus status) {
        NewChunkStatus status1 = NewChunkStatus.fromVanillaStatus(status);
        triggerDeferredLoad(status1);
        final CompletableFuture<Void> futureForStatus = this.newHolder.getFutureForStatus0(status1);
        return status == ChunkStatus.FULL ? this.wrapOptionalChunkFuture(futureForStatus) : this.wrapOptionalChunkProtoFuture(futureForStatus);
    }

    /**
     * NeoForge/1.21.1 port: vanilla {@code ServerChunkCache.getChunkFutureMainThread} drives generation
     * through {@link net.minecraft.server.level.GenerationChunkHolder#scheduleChunkGenerationTask}, which
     * gates on the vanilla {@code highestAllowedStatus}/{@code isStatusDisallowed} machinery and on vanilla's
     * own {@code ChunkGenerationTask} pipeline. The chunk system drives status promotion itself, so we bypass
     * all of that and route directly to our {@link #getOrCreateFuture(ChunkStatus)} (which raises the FlowSched
     * target status via the deferred-load tickets). Without this, the new holder never sets
     * {@code highestAllowedStatus}, so vanilla short-circuits to UNLOADED ("Chunk not there when requested").
     */
    @Override
    public CompletableFuture<ChunkResult<ChunkAccess>> scheduleChunkGenerationTask(ChunkStatus targetStatus, ChunkMap chunkMap) {
        return this.getOrCreateFuture(targetStatus);
    }

    @Nullable
    @Override
    public ChunkAccess getChunkIfPresentUnchecked(ChunkStatus requestedStatus) {
        return this.newHolder.getStatus().ordinal() >= NewChunkStatus.fromVanillaStatus(requestedStatus).ordinal()
                ? this.newHolder.getItem().get().chunk() : null;
    }

    @Nullable
    @Override
    public ChunkAccess getChunkIfPresent(ChunkStatus requestedStatus) {
        return this.newHolder.getTargetStatus().ordinal() >= NewChunkStatus.fromVanillaStatus(requestedStatus).ordinal()
                ? this.getChunkIfPresentUnchecked(requestedStatus) : null;
    }

    @Nullable
    @Override
    public ChunkAccess getLatestChunk() {
        return this.newHolder.getItem().get().chunk();
    }

    @Nullable
    @Override
    public ChunkStatus getPersistedStatus() {
        final ChunkAccess chunk = this.getLatestChunk();
        return chunk != null ? chunk.getPersistedStatus() : null;
    }

    @Nullable
    @Override
    public ChunkStatus getLatestStatus() {
        return ((NewChunkStatus) this.newHolder.getStatus()).getEffectiveVanillaStatus();
    }

    public void combineSavingFuture(CompletableFuture<?> savingFuture) {
        this.newHolder.submitOp(savingFuture.thenAccept(o -> {}));
    }

    @Override
    public LevelChunk c2me$immediateWorldChunk() {
        final ChunkAccess chunk = this.newHolder.getItem().get().chunk();
        if (chunk instanceof LevelChunk worldChunk) {
            return worldChunk;
        } else {
            return null;
        }
    }

    private static final class GenerationChunkHolderUnloaded {
        private static final ChunkResult<ChunkAccess> UNLOADED_CHUNK = net.minecraft.server.level.GenerationChunkHolder.UNLOADED_CHUNK;
        private static final CompletableFuture<ChunkResult<ChunkAccess>> UNLOADED_CHUNK_FUTURE = net.minecraft.server.level.GenerationChunkHolder.UNLOADED_CHUNK_FUTURE;
    }
}
