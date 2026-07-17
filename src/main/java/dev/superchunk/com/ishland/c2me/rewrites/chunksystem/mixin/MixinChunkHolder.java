package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin;

import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.NewChunkHolderVanillaInterface;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;

@Mixin(ChunkHolder.class)
public class MixinChunkHolder {

    @Shadow private volatile CompletableFuture<ChunkResult<LevelChunk>> fullChunkFuture;

    @Shadow private volatile CompletableFuture<ChunkResult<LevelChunk>> tickingChunkFuture;

    @Shadow private volatile CompletableFuture<ChunkResult<LevelChunk>> entityTickingChunkFuture;

    @Shadow private CompletableFuture<?> pendingFullStateConfirmation;

    @Shadow private CompletableFuture<?> saveSync;

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
            this.saveSync = null;
        }
    }

}
