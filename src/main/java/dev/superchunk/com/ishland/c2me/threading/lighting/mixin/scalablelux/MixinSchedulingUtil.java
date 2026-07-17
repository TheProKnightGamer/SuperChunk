package dev.superchunk.com.ishland.c2me.threading.lighting.mixin.scalablelux;

import dev.superchunk.com.ishland.c2me.base.common.GlobalExecutors;
import dev.superchunk.com.ishland.c2me.base.common.scheduler.LockTokenImpl;
import dev.superchunk.com.ishland.c2me.base.common.scheduler.SimplePrioritizedTask;
import dev.superchunk.com.ishland.flowsched.executor.LockToken;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;

import java.util.ArrayList;

@Pseudo
@Mixin(targets = "dev.superchunk.ca.spottedleaf.starlight.common.thread.SchedulingUtil")
public class MixinSchedulingUtil {

    /**
     * @author ishland
     * @reason merge thread pool
     */
    @Overwrite(remap = false)
    public static void scheduleTask(int ownerTag, Runnable task, int x, int z, int radius) {
        final ArrayList<LockToken> lockTokens = new ArrayList<>((radius * 2 + 1) * (radius * 2 + 1));
        for (int i = -radius; i <= radius; i++) {
            for (int j = -radius; j <= radius; j++) {
                lockTokens.add(new LockTokenImpl(ownerTag, ChunkPos.asLong(x + i, z + j), LockTokenImpl.Usage.LIGHTING));
            }
        }
        final SimplePrioritizedTask simpleTask = new SimplePrioritizedTask(task, lockTokens.toArray(LockToken[]::new), 17);
        GlobalExecutors.prioritizedScheduler.schedule(simpleTask);
    }

    /**
     * @author ishlabd
     * @reason merge thread pool
     */
    @Overwrite(remap = false)
    public static boolean isExternallyManaged() {
        return true;
    }

}
