package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common;

import com.mojang.datafixers.util.Either;
import dev.superchunk.com.ishland.c2me.base.common.theinterface.IFastChunkHolder;
import dev.superchunk.com.ishland.c2me.base.common.util.SneakyThrow;
import dev.superchunk.com.ishland.flowsched.scheduler.ItemHolder;
import dev.superchunk.com.ishland.flowsched.scheduler.StatusAdvancingScheduler;
import dev.superchunk.com.ishland.flowsched.util.Assertions;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Bridges the FlowSched-driven chunk system onto vanilla's {@link ChunkHolder} API.
 *
 * <p>Forge 1.20.1 port note (plan §1.3): 1.20.1 has no {@code GenerationChunkHolder}/{@code ChunkResult}
 * — everything is on {@link ChunkHolder} with {@code Either}-typed futures. Override mapping vs neo:
 * {@code getOrCreateFuture(ChunkStatus)} → {@link #getOrScheduleFuture(ChunkStatus, ChunkMap)}
 * (plus {@link #getFutureIfPresent}/{@link #getFutureIfPresentUnchecked}, which vanilla callers use);
 * {@code getChunkIfPresent(Unchecked)}/{@code getLatestChunk}/{@code getLatestStatus}/{@code getPersistedStatus}
 * → {@link #getLastAvailable()}/{@link #getLastAvailableStatus()}; {@code getSavingFuture} →
 * {@link #getChunkToSave()} (+ {@link #addSaveDependency}); {@code getLevelType} → {@link #getFullStatus()};
 * {@code scheduleChunkGenerationTask}/{@code getChunkToSend}/{@code getSendSyncFuture}/{@code isReadyForSaving}
 * /{@code getGenerationRefCount} deleted (1.20.2+/1.21 APIs — on 1.20.1 sending happens in
 * {@code ServerBlockTicking.sendChunkToPlayer} + {@code ChunkMap.updateChunkTracking});
 * {@link #wasAccessibleSinceLastSave()}/{@link #refreshAccessibility()} (1.20.1-only save gating,
 * used by {@code ChunkMap.saveAllChunks}) reflect the flowsched state; {@code updateFutures} no-ops
 * so vanilla never runs its own promotion pipeline.
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

    /** J17 replacement for {@code CompletableFuture#exceptionNow()} (J19+, PORT-NOTES convention #9). */
    private static Throwable exceptionNow(CompletableFuture<?> future) {
        return future.handle((unused, throwable) -> throwable).join();
    }

    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> wrapOptionalChunkFuture(CompletableFuture<?> future) {
        if (future.isDone()) {
            if (future.isCompletedExceptionally()) {
                Throwable throwable = exceptionNow(future);
                while (throwable instanceof CompletionException) {
                    throwable = throwable.getCause();
                }
                if (throwable == ItemHolder.UNLOADED_EXCEPTION) {
                    return ChunkHolder.UNLOADED_CHUNK_FUTURE;
                } else {
                    //noinspection unchecked
                    return (CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>) future; // it's fine to cast here
                }
            } else {
                return CompletableFuture.completedFuture(Either.left(this.newHolder.getItem().get().chunk()));
            }
        }
        return future.handleAsync((unused, throwable) -> {
            while (throwable instanceof CompletionException) {
                throwable = throwable.getCause();
            }
            if (throwable == ItemHolder.UNLOADED_EXCEPTION) {
                return ChunkHolder.UNLOADED_CHUNK;
            } else if (throwable != null) {
                SneakyThrow.sneaky(throwable);
                return null;
            } else {
                return Either.<ChunkAccess, ChunkHolder.ChunkLoadingFailure>left(this.newHolder.getItem().get().chunk());
            }
        }, this.chunkSystem.vanillaCompletionExecutor);
    }

    private CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> wrapOptionalChunkProtoFuture(CompletableFuture<?> future) {
        if (future.isDone()) {
            if (future.isCompletedExceptionally()) {
                Throwable throwable = exceptionNow(future);
                while (throwable instanceof CompletionException) {
                    throwable = throwable.getCause();
                }
                if (throwable == ItemHolder.UNLOADED_EXCEPTION) {
                    return ChunkHolder.UNLOADED_CHUNK_FUTURE;
                } else {
                    //noinspection unchecked
                    return (CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>) future; // it's fine to cast here
                }
            } else {
                return CompletableFuture.completedFuture(Either.left(this.newHolder.getItem().get().protoChunk()));
            }
        }
        return future.handleAsync((unused, throwable) -> {
            while (throwable instanceof CompletionException) {
                throwable = throwable.getCause();
            }
            if (throwable == ItemHolder.UNLOADED_EXCEPTION) {
                return ChunkHolder.UNLOADED_CHUNK;
            } else if (throwable != null) {
                SneakyThrow.sneaky(throwable);
                return null;
            } else {
                return Either.<ChunkAccess, ChunkHolder.ChunkLoadingFailure>left(this.newHolder.getItem().get().protoChunk());
            }
        }, this.chunkSystem.vanillaCompletionExecutor);
    }

    private CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> wrapOptionalWorldChunkFuture(CompletableFuture<?> future) {
        if (future.isDone()) {
            if (future.isCompletedExceptionally()) {
                Throwable throwable = exceptionNow(future);
                while (throwable instanceof CompletionException) {
                    throwable = throwable.getCause();
                }
                if (throwable == ItemHolder.UNLOADED_EXCEPTION) {
                    return ChunkHolder.UNLOADED_LEVEL_CHUNK_FUTURE;
                } else {
                    //noinspection unchecked
                    return (CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>>) future; // it's fine to cast here
                }
            } else {
                final ChunkAccess chunk = this.newHolder.getItem().get().chunk();
                if (chunk instanceof LevelChunk worldChunk) {
                    return CompletableFuture.completedFuture(Either.left(worldChunk));
                } else {
                    return ChunkHolder.UNLOADED_LEVEL_CHUNK_FUTURE; // might have unloaded at this point
                }
            }
        }
        return future.handleAsync((unused, throwable) -> {
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
                    return Either.<LevelChunk, ChunkHolder.ChunkLoadingFailure>left(worldChunk);
                } else {
                    return ChunkHolder.UNLOADED_LEVEL_CHUNK; // might have unloaded at this point
                }
            }
        }, this.chunkSystem.vanillaCompletionExecutor);
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
    public CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> getTickingChunkFuture() {
        synchronized (this.newHolder) {
            return wrapOptionalWorldChunkFuture(this.newHolder.getFutureForStatus0(NewChunkStatus.BLOCK_TICKING));
        }
    }

    @Override
    public CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> getEntityTickingChunkFuture() {
        synchronized (this.newHolder) {
            return wrapOptionalWorldChunkFuture(this.newHolder.getFutureForStatus0(NewChunkStatus.ENTITY_TICKING));
        }
    }

    @Override
    public CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> getFullChunkFuture() {
        synchronized (this.newHolder) {
            return wrapOptionalWorldChunkFuture(this.newHolder.getFutureForStatus0(NewChunkStatus.SERVER_ACCESSIBLE));
        }
    }

    @Nullable
    @Override
    public LevelChunk getTickingChunk() {
        return this.getTickingChunkFuture().getNow(UNLOADED_LEVEL_CHUNK).left().orElse(null);
    }

    /**
     * SuperChunk {@code player.latencyMetrics}: {@link System#nanoTime()} stamp of the most recent
     * upgrade to {@link NewChunkStatus#SERVER_ACCESSIBLE_CHUNK_SENDING} (written by
     * {@link TheChunkSystem#onItemUpgrade}, only while the metrics flag is ON; 0 = never stamped).
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

    private volatile CompletableFuture<?> superchunk$lastSavingFuture = null;
    private volatile ChunkAccess superchunk$lastSavingChunk = null;
    private volatile CompletableFuture<ChunkAccess> superchunk$lastChunkToSave = null;

    /**
     * 1.20.1 seam for the neo {@code getSavingFuture} override: vanilla
     * {@code ChunkMap.saveAllChunks}/{@code saveChunkIfNeeded} drive saving through
     * {@code getChunkToSave().join()} — wrap the flowsched op future and yield the current chunk.
     *
     * <p>IDENTITY-STABILITY REQUIREMENT: {@code saveAllChunks} loops
     * {@code while (future != holder.getChunkToSave())} (src1201 ChunkMap:425-428) — two
     * successive calls MUST return the same instance once nothing new is pending, or the save
     * loop never terminates. {@code ItemHolder.getOpFuture()} is identity-stable when idle
     * (static completed future) and mints a fresh future per call while busy — cache the derived
     * future keyed on (op future, current chunk) so idle+unchanged is a cache hit.
     */
    @Override
    public CompletableFuture<ChunkAccess> getChunkToSave() {
        synchronized (this.newHolder) {
            final CompletableFuture<?> saving = this.getSavingFuture();
            final ChunkState state = this.newHolder.getItem().get();
            final ChunkAccess chunk = state != null ? state.chunk() : null;
            final CompletableFuture<ChunkAccess> last = this.superchunk$lastChunkToSave;
            if (last != null && this.superchunk$lastSavingFuture == saving && this.superchunk$lastSavingChunk == chunk) {
                return last;
            }
            final CompletableFuture<ChunkAccess> derived = saving.handle((unused, throwable) -> {
                final ChunkState state1 = this.newHolder.getItem().get();
                return state1 != null ? state1.chunk() : null;
            });
            this.superchunk$lastSavingFuture = saving;
            this.superchunk$lastSavingChunk = chunk;
            this.superchunk$lastChunkToSave = derived;
            return derived;
        }
    }

    @Override
    protected void addSaveDependency(String source, CompletableFuture<?> future) {
        this.combineSavingFuture(future);
    }

    public CompletableFuture<?> getPostProcessingFuture() {
        // C2ME does not gate sending on a separate post-processing future; complete immediately.
        return COMPLETED_VOID_FUTURE;
    }

    public FullChunkStatus getLevelType() {
        return ChunkLevel.fullStatus(this.getTicketLevel());
    }

    /** Vanilla reads the private {@code ticketLevel} field here — ours lives in the flowsched holder. */
    @Override
    public FullChunkStatus getFullStatus() {
        return this.getLevelType();
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
        // no-op (vanilla method is private; forge AT public-f m_140086_(I)V makes it overridable)
    }

    @Override
    protected void updateFutures(ChunkMap chunkStorage, Executor executor) {
        // no-op: the chunk system drives status promotion itself
    }

    public boolean isAccessible() {
        return this.newHolder.getStatus().ordinal() >= NewChunkStatus.SERVER_ACCESSIBLE.ordinal();
    }

    /**
     * 1.20.1-only save-gating pair (src1201 ChunkHolder:423-428): {@code ChunkMap.saveAllChunks}
     * filters on it, then calls {@code refreshAccessibility}. Reflect the flowsched state; the
     * refresh is a no-op because unload-time saving is handled by the chunk system's downgrades.
     */
    @Override
    public boolean wasAccessibleSinceLastSave() {
        return this.isAccessible();
    }

    @Override
    public void refreshAccessibility() {
        // no-op: flowsched downgrade chains own unload-time saving
    }

    /**
     * 1.20.1 name for the neo {@code getOrCreateFuture(ChunkStatus)} override — every vanilla
     * scheduling path ({@code ServerChunkCache.getChunkFutureMainThread}, {@code ChunkMap
     * .getChunkRangeFuture}, …) funnels through here; route it to the FlowSched target status
     * (raised via the deferred-load tickets). Thread-safe in the new system.
     */
    @Override
    public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> getOrScheduleFuture(ChunkStatus status, ChunkMap chunkMap) {
        NewChunkStatus status1 = NewChunkStatus.fromVanillaStatus(status);
        triggerDeferredLoad(status1);
        final CompletableFuture<Void> futureForStatus = this.newHolder.getFutureForStatus0(status1);
        return status == ChunkStatus.FULL ? this.wrapOptionalChunkFuture(futureForStatus) : this.wrapOptionalChunkProtoFuture(futureForStatus);
    }

    /** 1.20.1 mapping of the neo {@code getChunkIfPresentUnchecked} (non-scheduling read). */
    @Override
    public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> getFutureIfPresentUnchecked(ChunkStatus requestedStatus) {
        if (this.newHolder.getStatus().ordinal() >= NewChunkStatus.fromVanillaStatus(requestedStatus).ordinal()) {
            final ChunkState state = this.newHolder.getItem().get();
            final ChunkAccess chunk = state == null ? null
                    : (requestedStatus == ChunkStatus.FULL ? state.chunk() : state.protoChunk());
            if (chunk != null) {
                return CompletableFuture.completedFuture(Either.left(chunk));
            }
        }
        return ChunkHolder.UNLOADED_CHUNK_FUTURE;
    }

    /** 1.20.1 mapping of the neo {@code getChunkIfPresent} (target-status-gated read). */
    @Override
    public CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> getFutureIfPresent(ChunkStatus requestedStatus) {
        return this.newHolder.getTargetStatus().ordinal() >= NewChunkStatus.fromVanillaStatus(requestedStatus).ordinal()
                ? this.getFutureIfPresentUnchecked(requestedStatus) : ChunkHolder.UNLOADED_CHUNK_FUTURE;
    }

    /** 1.20.1 mapping of the neo {@code getLatestChunk}. */
    @Nullable
    @Override
    public ChunkAccess getLastAvailable() {
        final ChunkState state = this.newHolder.getItem().get();
        return state != null ? state.chunk() : null;
    }

    /** 1.20.1 mapping of the neo {@code getLatestStatus} (folds {@code getPersistedStatus}). */
    @Nullable
    @Override
    public ChunkStatus getLastAvailableStatus() {
        final NewChunkStatus status = (NewChunkStatus) this.newHolder.getStatus();
        if (status == NewChunkStatus.NEW) return null;
        return status.getEffectiveVanillaStatus();
    }

    public void combineSavingFuture(CompletableFuture<?> savingFuture) {
        this.newHolder.submitOp(savingFuture.thenAccept(o -> {}));
    }

    @Override
    public boolean c2me$blockTicking() {
        return this.newHolder.getStatus().ordinal() >= NewChunkStatus.BLOCK_TICKING.ordinal();
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
}
