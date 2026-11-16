package dev.superchunk.mixin.compat;

import dev.superchunk.pregen.PregenMemoryGate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Chunky: hold new chunk requests back while the heap is nearly full ({@link PregenMemoryGate}).
 * The hook sits before the permit acquire in Chunky's task loop, which runs on Chunky's own thread.
 * {@code require = 0}: a Chunky build without this call site simply runs ungated.
 */
@Pseudo
@Mixin(targets = "org.popcraft.chunky.GenerationTask", remap = false)
public abstract class MixinChunkyGenerationTask {
    @Inject(method = "run",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/Semaphore;acquire()V"),
            require = 0, remap = false)
    private void superchunk$waitForMemory(CallbackInfo ci) {
        PregenMemoryGate.await();
    }
}
