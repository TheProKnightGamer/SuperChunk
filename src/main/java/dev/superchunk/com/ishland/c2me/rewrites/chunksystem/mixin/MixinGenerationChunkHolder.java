package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin;

import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.NewChunkHolderVanillaInterface;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Vanilla's private {@code getOrCreateFuture} is still called from {@code applyStep}. Our holders
 * used to override it through a {@code public-f} access transformer, which crashes any mod that
 * {@code @Overwrite}s it as private ("cannot reduce visibiliy", GitHub #31). Forward to our version
 * here instead.
 */
@Mixin(GenerationChunkHolder.class)
public abstract class MixinGenerationChunkHolder {

    @Inject(method = "getOrCreateFuture", at = @At("HEAD"), cancellable = true)
    private void c2me$forwardNewHolder(ChunkStatus status, CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> cir) {
        if ((Object) this instanceof NewChunkHolderVanillaInterface holder) {
            cir.setReturnValue(holder.c2me$getOrCreateFuture(status));
        }
    }
}
