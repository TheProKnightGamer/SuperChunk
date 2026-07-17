package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IThreadedAnvilChunkStorage;
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

    @Shadow @Final private Thread mainThread;

    @Shadow @Final public ChunkMap chunkMap;

    @Unique
    private long c2me$lastHolderUpdate = System.nanoTime();

    @Shadow
    @Nullable
    protected abstract ChunkHolder getVisibleChunkIfPresent(long pos);

    @Shadow public abstract int getLoadedChunksCount();

    @Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", at = @At("HEAD"), cancellable = true)
    private void shortcutGetChunk(int x, int z, ChunkStatus leastStatus, boolean create, CallbackInfoReturnable<ChunkAccess> cir) {
        if (Thread.currentThread() != this.mainThread) {
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
