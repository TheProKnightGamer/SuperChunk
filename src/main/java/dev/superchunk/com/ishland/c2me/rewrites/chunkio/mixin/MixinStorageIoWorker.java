package dev.superchunk.com.ishland.c2me.rewrites.chunkio.mixin;

import dev.superchunk.com.ishland.c2me.rewrites.chunkio.common.C2MEStorageVanillaInterface;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.util.thread.StrictQueue;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(IOWorker.class)
public class MixinStorageIoWorker {

    @Mutable
    @Shadow @Final private Map<ChunkPos, IOWorker.PendingStore> pendingWrites;

    @Mutable
    @Shadow @Final private RegionFileStorage storage;

    @Mutable
    @Shadow @Final private AtomicBoolean shutdownRequested;

    @Mutable
    @Shadow @Final private ProcessorMailbox<StrictQueue.IntRunnable> mailbox;

    @SuppressWarnings("ConstantConditions")
    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        if ((Object) this instanceof C2MEStorageVanillaInterface) {
            // fail-fast incompatibility
            this.pendingWrites = null;
            this.storage = null;
            this.shutdownRequested = null;
            this.mailbox = null;
        }
    }

}
