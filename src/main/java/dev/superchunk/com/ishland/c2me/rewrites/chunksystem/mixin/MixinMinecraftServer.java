package dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin;

import dev.superchunk.com.ishland.c2me.base.common.threadstate.ThreadInstrumentation;
import dev.superchunk.com.ishland.c2me.base.common.threadstate.ThreadState;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.ducks.IChunkSystemAccess;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer {

    @Shadow public abstract Iterable<ServerLevel> getAllLevels();

    @Shadow @Final private static Logger LOGGER;

    @Inject(method = "stopServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;waitUntilNextTick()V"))
    private void onTaskWait(CallbackInfo ci, @Share("c2me:shutdownLastPrint") LocalLongRef lastPrint, @Share("c2me:shutdownFirstPrint") LocalLongRef firstPrint) {
        long now = System.nanoTime();
        if (firstPrint.get() == 0) {
            firstPrint.set(now);
        }
        if (now - lastPrint.get() > 2_000_000_000L) {
            if (now - firstPrint.get() > 10_000_000_000L) {
                StringBuilder builder = new StringBuilder();
                builder.append('\n');
                boolean haveState = false;
                for (Map.Entry<Thread, ThreadState> entry : ThreadInstrumentation.entrySet()) {
                    String msg = ThreadInstrumentation.printState(entry.getKey().getName(), entry.getKey().threadId(), entry.getValue());
                    if (msg != null) {
                        builder.append(msg);
                        haveState = true;
                    }
                }
                if (haveState) {
                    LOGGER.info(builder.toString());
                }
            }

            for (ServerLevel world : this.getAllLevels()) {
                final int itemCount = ((IChunkSystemAccess) world.getChunkSource().chunkMap).c2me$getTheChunkSystem().itemCount();
                if (itemCount > 0) {
                    LOGGER.info("{}/{}: waiting for {} chunks to unload", world, world.dimension().location(), itemCount);
                }
            }
            lastPrint.set(now);
        }
    }

}
