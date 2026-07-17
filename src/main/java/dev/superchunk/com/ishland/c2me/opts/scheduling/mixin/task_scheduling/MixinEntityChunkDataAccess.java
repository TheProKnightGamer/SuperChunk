package dev.superchunk.com.ishland.c2me.opts.scheduling.mixin.task_scheduling;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IServerChunkManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.concurrent.Executor;

@Mixin(EntityStorage.class)
public class MixinEntityChunkDataAccess {

    @Shadow @Final private ServerLevel level;

    @ModifyArg(method = "loadEntities", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;thenApplyAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private Executor redirectExecutor(Executor executor) {
        return ((IServerChunkManager) this.level.getChunkSource()).getMainThreadExecutor();
    }

}
