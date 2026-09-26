package dev.superchunk.com.ishland.c2me.opts.scheduling.mixin.shutdown;

import dev.superchunk.com.ishland.c2me.opts.scheduling.common.ITryFlushable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProgressListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.locks.LockSupport;

/**
 * Upstream C2ME {@code shutdown.MixinServerWorld}, ported to Mojang names (it had been left out of
 * the port, while {@code task_scheduling.MixinEntityChunkDataAccess} was kept).
 *
 * <p>That kept mixin moves entity deserialization from {@code EntityStorage}'s own mailbox onto the
 * server's main-thread queue. Vanilla's {@code saveAll} — {@code /save-all flush}, a backup mod's
 * flushing save, server stop — loops on the main thread until every chunk's entities are stored,
 * and a chunk whose entity load is still PENDING cannot be stored; vanilla gets it unstuck because
 * {@code EntityStorage.flush} drains the mailbox. With the task on the main-thread queue instead,
 * that drain finds nothing and nothing else runs the task, so the loop spun forever. Pump the
 * main-thread queue until one pass completes, then let vanilla's {@code saveAll} run (a no-op).
 */
@Mixin(ServerLevel.class)
public abstract class MixinServerLevel {

    @Shadow @Final private MinecraftServer server;

    @Shadow @Final private PersistentEntitySectionManager<Entity> entityManager;

    @Shadow public abstract ServerChunkCache getChunkSource();

    @Inject(method = "save", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/entity/PersistentEntitySectionManager;saveAll()V"))
    private void superchunk$flushEntitiesPumpingTasks(ProgressListener progress, boolean flush, boolean skipSave, CallbackInfo ci) {
        while (!((ITryFlushable) this.entityManager).c2me$tryFlush()) {
            this.server.pollTask();
            this.getChunkSource().pollTask();
            LockSupport.parkNanos("waiting for entity loads", 10_000_000L);
        }
    }
}
