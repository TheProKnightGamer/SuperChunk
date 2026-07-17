package dev.superchunk.com.ishland.c2me.base.mixin.scheduler;

import dev.superchunk.com.ishland.c2me.base.common.scheduler.IVanillaChunkManager;
import dev.superchunk.com.ishland.c2me.base.common.scheduler.SchedulingManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.DistanceManager;
import net.minecraft.server.level.ServerChunkCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerChunkCache.class)
public class MixinServerChunkManager {

    @Shadow @Final public ChunkMap chunkMap;

    @WrapOperation(method = "runDistanceManagerUpdates", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/DistanceManager;runAllUpdates(Lnet/minecraft/server/level/ChunkMap;)Z"))
    private boolean consolidatePriorityUpdates(DistanceManager instance, ChunkMap completablefuture, Operation<Boolean> original) {
        SchedulingManager schedulingManager = ((IVanillaChunkManager) this.chunkMap).c2me$getSchedulingManager();
        schedulingManager.setConsolidatingLevelUpdates(true);
        try {
            return original.call(instance, chunkMap);
        } finally {
            schedulingManager.setConsolidatingLevelUpdates(false);
        }
    }

}
