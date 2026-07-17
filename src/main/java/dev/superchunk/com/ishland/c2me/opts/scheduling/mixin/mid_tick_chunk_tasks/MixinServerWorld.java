package dev.superchunk.com.ishland.c2me.opts.scheduling.mixin.mid_tick_chunk_tasks;

import dev.superchunk.com.ishland.c2me.opts.scheduling.common.ServerMidTickTask;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public class MixinServerWorld {

    @Shadow @Final private MinecraftServer server;

    @Inject(method = {"tickBlock", "tickFluid"}, at = @At("RETURN"), require = 2)
    private void onPostTickBlockAndFluid(CallbackInfo info) {
        ((ServerMidTickTask) this.server).executeTasksMidTick((ServerLevel) (Object) this);
    }

}
