package dev.superchunk.com.ishland.c2me.notickvd.mixin;

import dev.superchunk.com.ishland.c2me.notickvd.common.Config;
import com.llamalad7.mixinextras.injector.WrapWithCondition;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Mixin(ChunkMap.class)
public abstract class MixinThreadedAnvilChunkStorage {

    @Shadow public abstract List<ServerPlayer> getPlayers(ChunkPos chunkPos, boolean onlyOnWatchDistanceEdge);

    @Shadow @Final public BlockableEventLoop<Runnable> mainThreadExecutor;

    @Shadow @Final private PlayerMap playerMap;

    @Shadow protected abstract void onChunkReadyToSend(LevelChunk chunk);

    @ModifyArg(method = "setServerViewDistance", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(III)I"), index = 2)
    private int modifyMaxVD(int max) {
        return Config.maxViewDistance;
    }

    @Redirect(method = "prepareTickingChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkHolder;getTickingChunk()Lnet/minecraft/world/level/chunk/LevelChunk;"), require = 0)
    private LevelChunk redirectSendWatchPacketsGetWorldChunk(ChunkHolder chunkHolder) {
        return chunkHolder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
    }

    // TODO ensureChunkCorrectness
    @Inject(method = "prepareAccessibleChunk", at = @At("RETURN"))
    private void onMakeChunkAccessible(ChunkHolder chunkHolder, CallbackInfoReturnable<CompletableFuture<ChunkResult<LevelChunk>>> cir) {
        cir.getReturnValue().thenAccept(either -> either.ifSuccess(worldChunk -> {
            if (Config.compatibilityMode) {
                this.mainThreadExecutor.tell(() -> this.onChunkReadyToSend(worldChunk));
            } else {
                this.onChunkReadyToSend(worldChunk);
            }
        }));
    }

    @WrapWithCondition(method = "prepareTickingChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;onChunkReadyToSend(Lnet/minecraft/world/level/chunk/LevelChunk;)V"), require = 0)
    private boolean controlDuplicateChunkSending(ChunkMap instance, LevelChunk worldChunk) {
        return Config.ensureChunkCorrectness;
    }

}
