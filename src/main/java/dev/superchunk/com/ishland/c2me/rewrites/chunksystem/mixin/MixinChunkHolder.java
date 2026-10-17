package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin;

import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.NewChunkHolderVanillaInterface;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.datafixers.util.Either;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;

/**
 * Forge 1.20.1 deltas (plan §1.2 row 3): the three promotion futures are Either-typed;
 * {@code saveSync} does NOT exist on 1.20.1 (1.20.2+) — shadow dropped; the 1.20.1 save chain
 * ({@code chunkToSave}) stays intact and is overridden by
 * {@link NewChunkHolderVanillaInterface#getChunkToSave()} instead of being nulled.
 */
@Mixin(ChunkHolder.class)
public class MixinChunkHolder {

    @Shadow private volatile CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> fullChunkFuture;

    @Shadow private volatile CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> tickingChunkFuture;

    @Shadow private volatile CompletableFuture<Either<LevelChunk, ChunkHolder.ChunkLoadingFailure>> entityTickingChunkFuture;

    @Shadow private CompletableFuture<Void> pendingFullStateConfirmation;

    @WrapWithCondition(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;setTicketLevel(I)V"))
    private boolean noopSetLevel(ChunkHolder instance, int level) {
        //noinspection ConstantValue
        return !((Object) this instanceof NewChunkHolderVanillaInterface);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void failFastIncompatibility(CallbackInfo ci) {
        //noinspection ConstantValue
        if (((Object) this instanceof NewChunkHolderVanillaInterface)) {
            this.fullChunkFuture = null;
            this.tickingChunkFuture = null;
            this.entityTickingChunkFuture = null;
            this.pendingFullStateConfirmation = null;
        }
    }

}
