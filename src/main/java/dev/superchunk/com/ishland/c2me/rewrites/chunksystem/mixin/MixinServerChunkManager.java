package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IThreadedAnvilChunkStorage;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.Config;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ducks.IChunkSystemAccess;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.structs.ChunkSystemExecutors;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

@Mixin(ServerChunkCache.class)
public abstract class MixinServerChunkManager {

    @Shadow @Final public ChunkMap chunkMap;

    @Shadow @Final private ServerChunkCache.MainThreadExecutor mainThreadProcessor;

    @Shadow protected abstract boolean chunkAbsent(@Nullable ChunkHolder holder, int maxLevel);

    @Unique
    private long c2me$lastHolderUpdate = System.nanoTime();

    @Shadow
    @Nullable
    protected abstract ChunkHolder getVisibleChunkIfPresent(long pos);

    @Shadow public abstract int getLoadedChunksCount();

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("HEAD"), cancellable = true)
    private void shortcutGetChunk(int x, int z, ChunkStatus leastStatus, boolean create, CallbackInfoReturnable<ChunkAccess> cir) {
        // The exclusive dimension owner needs the normal cache/ticket path too.
        if (!this.mainThreadProcessor.isSameThread()) {
            final ChunkHolder holder = this.getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
            if (holder != null) {
                final CompletableFuture<ChunkResult<ChunkAccess>> future = holder.getOrCreateFuture(leastStatus); // thread-safe in new system
                ChunkAccess chunk = future.getNow(GenerationChunkHolder.UNLOADED_CHUNK).orElse(null);
                if (chunk instanceof ImposterProtoChunk readOnlyChunk) chunk = readOnlyChunk.getWrapped();
                if (chunk != null) {
                    cir.setReturnValue(chunk); // also cancels
                    return;
                }
            }
        }
    }

    /**
     * SuperChunk: optional diagnostic timeout for a non-creating {@code getChunk}.
     *
     * <p>Vanilla {@code getChunk(x, z, status, create)} calls {@code getChunkFutureMainThread} and
     * then {@code mainThreadProcessor.managedBlock(future::isDone)}. With {@code create == false} no
     * UNKNOWN ticket is added, so nothing bounds that wait. The primary cause of an unbounded one --
     * a MARK_BROKEN holder whose futures were abandoned -- is fixed at its source in
     * {@code ItemHolder#failPendingFuturesAbove}. This timeout is disabled by default so healthy
     * chunks keep vanilla's completion semantics, even when loading takes longer than expected.
     *
     * <p>When explicitly enabled, it uses a bounded wait. {@code chunkAbsent} goes false as
     * soon as {@code vanillaIf$setLevel} writes the managed ticket level, which is well before the
     * FlowSched load actually completes -- so "future not done" usually just means "loading, ready in
     * a few ms", and vanilla would have returned the real chunk. Bailing out there would hand back
     * null for a chunk that is merely in flight, and callers treat null as "no chunk": Lithium's
     * {@code ChunkAwareBlockCollisionSweeper} and vanilla's {@code BlockCollisions} both SKIP a
     * section whose chunk is null, so an entity moving into a just-ticketed chunk would fall through
     * terrain. A finite budget can cause the same problem under load, which is why it must remain
     * opt-in. While waiting, we keep draining main-thread tasks (which lets the chunk system make
     * progress) until the future completes or the budget expires, and only then substitute
     * {@code UNLOADED_CHUNK_FUTURE} so the outer {@code managedBlock} returns at once and
     * {@code join()} cannot block.
     *
     * <p>Only {@code create == false} is touched, so the creating path stays byte-identical to
     * vanilla and "Should always be able to create a chunk!" can never fire from here. Scoped to
     * {@code getChunk} alone -- {@code getChunkFuture} is left as vanilla wrote it, since its callers
     * consume the future rather than a nullable chunk. Note the wrap also covers the off-thread
     * branch of {@code getChunk}, which re-enters this same method on the main thread.
     *
     * <p>Budget: {@code -Dsuperchunk.chunkSystem.nonCreatingGetChunkBudgetMillis} (default -1; 0 =
     * never wait, negative = wait forever, i.e. vanilla).
     */
    @WrapOperation(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;getChunkFutureMainThread(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<ChunkResult<ChunkAccess>> superchunk$boundNonCreatingGetChunk(
            ServerChunkCache instance, int x, int z, ChunkStatus leastStatus, boolean create,
            Operation<CompletableFuture<ChunkResult<ChunkAccess>>> original) {
        final CompletableFuture<ChunkResult<ChunkAccess>> future = original.call(instance, x, z, leastStatus, create);
        if (create || Config.nonCreatingGetChunkBudgetNanos < 0L || future.isDone()) {
            return future;
        }
        if (Config.nonCreatingGetChunkBudgetNanos > 0L) {
            final long deadline = System.nanoTime() + Config.nonCreatingGetChunkBudgetNanos;
            this.mainThreadProcessor.managedBlock(() -> future.isDone() || System.nanoTime() - deadline >= 0L);
            if (future.isDone()) {
                return future; // completed in time: vanilla result, vanilla behaviour
            }
        }
        return GenerationChunkHolder.UNLOADED_CHUNK_FUTURE;
    }

    /**
     * SuperChunk: do not negative-cache a chunk that is merely still loading.
     *
     * <p>When {@link #superchunk$boundNonCreatingGetChunk} times out, {@code getChunk} goes on to
     * {@code storeInCache(pos, null, status)}. That 4-entry cache is shared with
     * {@code getChunkNow}, which -- unlike {@code getChunk} -- returns on a {@code (pos, FULL)} hit
     * REGARDLESS of whether the cached value is null. So one timed-out query would keep reporting the
     * chunk as absent after it had finished loading, until the entry is displaced; via
     * {@code PathNavigationRegion} that means mobs path through it as if it were air. NeoForge's
     * {@code getChunk} cache loop ({@code chunkaccess != null || !requireChunk}) also short-circuits
     * on a cached null, which would suppress the re-query that would otherwise pick the chunk up.
     *
     * <p>Skipped only for the loading case, identified statelessly by re-testing {@code chunkAbsent}:
     * a genuinely absent chunk still gets negative-cached exactly as vanilla intends, so the hot
     * "entity next to unloaded terrain" path keeps its cache hits.
     */
    @WrapOperation(
            method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;storeInCache(JLnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/chunk/status/ChunkStatus;)V"))
    private void superchunk$dontNegativeCacheLoadingChunk(
            ServerChunkCache instance, long posKey, ChunkAccess chunk, ChunkStatus status,
            Operation<Void> original) {
        if (chunk == null && !this.chunkAbsent(this.getVisibleChunkIfPresent(posKey), ChunkLevel.byStatus(status))) {
            return; // present-but-still-loading: leave the cache alone
        }
        original.call(instance, posKey, chunk, status);
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;Z)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;runDistanceManagerUpdates()Z", shift = At.Shift.AFTER))
    private void updateHolderMapAfterTick(CallbackInfo ci) {
        ((IThreadedAnvilChunkStorage) this.chunkMap).invokeUpdateHolderMap();
    }

    @WrapOperation(method = "runDistanceManagerUpdates", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;promoteChunkMap()Z"))
    private boolean disableUpdateHolderMapOnTask(ChunkMap instance, Operation<Boolean> original) { // holder map only used for compatibility layer
        if (System.nanoTime() - c2me$lastHolderUpdate > 50_000_000L) { // 50ms
            c2me$lastHolderUpdate = System.nanoTime();
            return original.call(instance);
        }
        return false;
    }

    @Redirect(method = "chunkAbsent", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;getTicketLevel()I"))
    private int replaceLevel(ChunkHolder instance) {
        return ((IChunkSystemAccess) this.chunkMap).c2me$getTheChunkSystem().vanillaIf$getManagedLevel(instance.getPos().toLong());
    }

    /**
     * @author ishland
     * @reason add debug string
     */
    @Overwrite
    public String gatherStats() {
        return Integer.toString(((IChunkSystemAccess) this.chunkMap).c2me$getTheChunkSystem().itemCount()) + ", " + Integer.toString(this.getLoadedChunksCount());
    }

    @WrapOperation(method = "runDistanceManagerUpdates", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/DistanceManager;runAllUpdates(Lnet/minecraft/server/level/ChunkMap;)Z"))
    private boolean consolidateSchedules(DistanceManager instance, ChunkMap completablefuture, Operation<Boolean> original) {
        Queue<Runnable> runnables = ChunkSystemExecutors.CONSOLIDATING_QUEUE.get();
        if (runnables != null) {
            new Throwable("CONSOLIDATING_QUEUE leak").printStackTrace();
            return original.call(instance, chunkMap);
        }

        ChunkSystemExecutors.CONSOLIDATING_QUEUE.set(runnables = new ArrayDeque<>());
        try {
            return original.call(instance, chunkMap);
        } finally {
            Queue<Runnable> finalRunnables = runnables;
            if (!finalRunnables.isEmpty()) {
                ChunkSystemExecutors.backingBackgroundExecutor.execute(() -> {
                    while (!finalRunnables.isEmpty()) {
                        try {
                            finalRunnables.remove().run();
                        } catch (Throwable t) {
                            t.printStackTrace();
                        }
                    }
                });
            }
            ChunkSystemExecutors.CONSOLIDATING_QUEUE.remove();
        }
    }

}
