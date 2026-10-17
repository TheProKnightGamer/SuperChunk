package dev.superchunk.com.ishland.c2me.opts.scheduling.mixin.mid_tick_chunk_tasks;

import dev.superchunk.com.ishland.c2me.opts.scheduling.common.ServerMidTickTask;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public class MixinServerChunkManager {

    @Shadow @Final ServerLevel level;

    @Dynamic
    @Inject(method = "tickChunks", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tickChunk(Lnet/minecraft/world/level/chunk/LevelChunk;I)V"))
    private void onPostTickChunk(CallbackInfo ci) {
        ((ServerMidTickTask) this.level.getServer()).executeTasksMidTick(this.level);
    }

}
