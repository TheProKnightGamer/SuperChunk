package dev.superchunk.com.ishland.c2me.fixes.worldgen.vanilla_bugs.mixin.ensure_chunk_status_before_callback;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkHolder.class)
public abstract class MixinChunkHolder {

    @Shadow public abstract CompletableFuture<ChunkResult<LevelChunk>> getTickingChunkFuture();

    @Shadow public abstract CompletableFuture<ChunkResult<LevelChunk>> getFullChunkFuture();

    @Shadow public abstract CompletableFuture<ChunkResult<LevelChunk>> getEntityTickingChunkFuture();

    @WrapWithCondition(method = "lambda$scheduleFullChunkPromotion$4", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;onFullChunkStatusChange(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/server/level/FullChunkStatus;)V"))
    private boolean ensureChunkStatusBeforeCallback(ChunkMap instance, ChunkPos chunkPos, FullChunkStatus levelType) {
        return switch (levelType) {
            case INACCESSIBLE -> true;
            case FULL -> this.c2me$isStatusReached(this.getFullChunkFuture());
            case BLOCK_TICKING -> this.c2me$isStatusReached(this.getTickingChunkFuture());
            case ENTITY_TICKING -> this.c2me$isStatusReached(this.getEntityTickingChunkFuture());
        };
    }

    @Unique
    private boolean c2me$isStatusReached(CompletableFuture<ChunkResult<LevelChunk>> future) {
        return future.isDone() && !future.isCompletedExceptionally() && future.join().isSuccess();
    }

}
