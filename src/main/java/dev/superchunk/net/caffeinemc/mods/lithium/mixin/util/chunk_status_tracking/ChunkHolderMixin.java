package dev.superchunk.net.caffeinemc.mods.lithium.mixin.util.chunk_status_tracking;

import com.mojang.datafixers.util.Either;
import dev.superchunk.net.caffeinemc.mods.lithium.common.world.chunk.ChunkStatusTracker;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * PORT-1.20.1 (REWRITE, plan §6.1 util.chunk_status_tracking — the top-risk file): 1.20.1
 * {@code ChunkHolder} has no {@code GenerationChunkHolder} superclass and no
 * {@code getChunkIfPresentUnchecked}, and its {@code updateFutures} body carries far more live
 * locals than 1.21.1's, so neo's 2-local {@code CAPTURE_FAILHARD} cannot match. Re-anchored:
 * same injection point role — the 7th {@code FullChunkStatus.isOrAfter} INVOKE is the
 * promotion/demotion epilogue check ({@code !fullchunkstatus1.isOrAfter(fullchunkstatus)}),
 * AFTER all future reassignments and BEFORE {@code oldTicketLevel = ticketLevel} — with the
 * prev/new statuses recomputed from the shadowed ticket-level fields via
 * {@code ChunkLevel.fullStatus} (bit-identical to the locals neo captured) instead of a local
 * capture. The FULL chunk lookup uses 1.20.1's
 * {@code getFutureIfPresentUnchecked(ChunkStatus.FULL).getNow(UNLOADED_CHUNK)}.
 *
 * <p>{@code require = 0} is kept (neo had it for moonrise): the C2ME chunk-system rewrite
 * replaces this transition path — its unload seam notifies the tracker through
 * {@code LithiumChunkStatusTrackerInvoker} (plan §5.4) — so a non-match under the patched
 * holder is expected, not an error.
 */
@Mixin(value = ChunkHolder.class, priority = 1010) //Higher priority for moonrise compatibility
public abstract class ChunkHolderMixin {

    @Shadow
    @Final
    ChunkPos pos;

    @Shadow
    private int oldTicketLevel;

    @Shadow
    private int ticketLevel;

    @Shadow
    public abstract CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> getFutureIfPresentUnchecked(ChunkStatus status);

    @Inject(
            method = "updateFutures(Lnet/minecraft/server/level/ChunkMap;Ljava/util/concurrent/Executor;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/FullChunkStatus;isOrAfter(Lnet/minecraft/server/level/FullChunkStatus;)Z", ordinal = 6),
            require = 0
    )
    private void trackUpdate(ChunkMap chunkMap, Executor executor, CallbackInfo ci) {
        ServerLevel serverLevel = chunkMap.level;

        // At this point oldTicketLevel is still the PRE-update level (it is assigned from
        // ticketLevel only at the end of updateFutures) — these are exactly the
        // fullchunkstatus/fullchunkstatus1 locals of the method body.
        FullChunkStatus prevStatus = ChunkLevel.fullStatus(this.oldTicketLevel);
        FullChunkStatus status = ChunkLevel.fullStatus(this.ticketLevel);

        boolean loaded = status.isOrAfter(FullChunkStatus.FULL);
        boolean wasLoaded = prevStatus.isOrAfter(FullChunkStatus.FULL);
        if (!loaded && wasLoaded) {
            ChunkStatusTracker.onChunkInaccessible(serverLevel, this.pos);
        } else if (!wasLoaded) {
            //The chunk is loaded. Either the future still has work (-> the other mixin will handle it), or
            // the chunk is available immediately (-> we have to handle it here). This commonly happens at the edge of
            // the render distance when the player turns around and reloads a chunk that was only barely unloaded.
            ChunkAccess chunkAccess = this.getFutureIfPresentUnchecked(ChunkStatus.FULL)
                    .getNow(ChunkHolder.UNLOADED_CHUNK).left().orElse(null);
            if (chunkAccess instanceof LevelChunk chunk) {
                ChunkStatusTracker.onChunkAccessible(serverLevel, chunk);
            }
        }

    }
}
