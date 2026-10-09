package dev.superchunk.com.ishland.c2me.opts.scheduling.mixin.task_scheduling;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IServerChunkManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

/**
 * Upstream C2ME: entity deserialization runs on the main thread instead of vanilla's
 * {@code entityDeserializerQueue} mailbox.
 *
 * <p>SuperChunk: each deserialization goes through {@link #superchunk$deserializeQueue}, and the
 * main-thread task only runs the next entry. {@code EntityStorage.flush} drains that queue at its
 * tail, exactly where vanilla drains {@code entityDeserializerQueue}. Vanilla's
 * {@code PersistentEntitySectionManager.saveAll} ({@code /save-all flush}, backup mods, server stop)
 * relies on that drain to finish chunks whose entity load is pending; without it the loop never
 * ended. Upstream instead pumped the whole main-thread queue from {@code ServerLevel.save}, one task
 * per 10 ms with an fsync of every entity region file in between. That pump also ran new chunk
 * loads, whose entity loads were then pending in the next pass, so a flush during a pregen or fast
 * exploration never finished and the watchdog killed the server ("A single server tick took 60.00
 * seconds", 2026-10-08, 321-mod pack).
 */
@Mixin(EntityStorage.class)
public class MixinEntityChunkDataAccess {

    @Shadow @Final private ServerLevel level;

    @Unique
    private final Queue<Runnable> superchunk$deserializeQueue = new ConcurrentLinkedQueue<>();

    @ModifyArg(method = "loadEntities", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;thenApplyAsync(Ljava/util/function/Function;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private Executor redirectExecutor(Executor executor) {
        final Executor mainThread = ((IServerChunkManager) this.level.getChunkSource()).getMainThreadExecutor();
        return task -> {
            this.superchunk$deserializeQueue.add(task);
            mainThread.execute(this::superchunk$runNextDeserialize);
        };
    }

    @Unique
    private void superchunk$runNextDeserialize() {
        final Runnable task = this.superchunk$deserializeQueue.poll();
        if (task != null) {
            task.run();
        }
    }

    @Inject(method = "flush", at = @At("TAIL"))
    private void superchunk$drainDeserializeQueue(boolean synchronize, CallbackInfo ci) {
        if (!this.level.getServer().isSameThread()) {
            return; // the queued work must run on the main thread; its own tasks will run it
        }
        Runnable task;
        while ((task = this.superchunk$deserializeQueue.poll()) != null) {
            task.run();
        }
    }

}
