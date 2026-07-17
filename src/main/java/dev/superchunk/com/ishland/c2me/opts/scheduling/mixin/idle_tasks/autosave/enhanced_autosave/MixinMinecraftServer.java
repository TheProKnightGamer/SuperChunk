package dev.superchunk.com.ishland.c2me.opts.scheduling.mixin.idle_tasks.autosave.enhanced_autosave;

import dev.superchunk.com.ishland.c2me.opts.scheduling.common.idle_tasks.IThreadedAnvilChunkStorage;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.ReentrantBlockableEventLoop;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer extends ReentrantBlockableEventLoop<TickTask> {

    @Shadow protected abstract boolean haveTime();

    @Shadow public abstract Iterable<ServerLevel> getAllLevels();

    @Shadow private long nextTickTimeNanos;

    @Shadow @Final private ServerTickRateManager tickRateManager;

    public MixinMinecraftServer(String string) {
        super(string);
    }

    @Unique
    private boolean c2me$shouldKeepSavingChunks() {
        return this.getPendingTasksCount() > 0 || Util.getNanos() < (this.nextTickTimeNanos - 1_000_000L); // reserve 1ms
    }

    /**
     * @author ishland
     * @reason improve task execution when waiting for next tick
     */
    @ModifyReturnValue(method = "pollTask", at = @At("RETURN"))
    private boolean postRunTask(boolean original) {
        if (original) return true;
        if (this.c2me$shouldKeepSavingChunks()) {
            for (ServerLevel serverWorld : this.getAllLevels()) {
                if (((IThreadedAnvilChunkStorage) serverWorld.getChunkSource().chunkMap).c2me$runOneChunkAutoSave()) {
                    return false;
                }
            }
        }
        return false;
    }

}
