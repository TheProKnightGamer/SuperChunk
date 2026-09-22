package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.compat.SkeinCompat;
import net.minecraft.server.level.ServerChunkCache;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps the chunk executor's thread check consistent with Skein's exclusive level owner. */
@Mixin(ServerChunkCache.MainThreadExecutor.class)
public abstract class MixinSkeinChunkMainThreadExecutor {

    /**
     * Skein lets a dimension owner call getChunk inline. Its managedBlock then drains this
     * level's queued load callbacks, including C2ME's status transitions. Those callbacks use
     * isSameThread(), which delegates here; without this bridge they reject the legitimate
     * owner and mark the chunk broken. ExecuteBlocking also needs the same ownership answer
     * to avoid waiting for work on the executor that this worker is already driving.
     *
     * The field receiver identifies the exact chunk source without relying on synthetic
     * enclosing-instance field names. Unrelated workers retain the original thread result.
     */
    @WrapOperation(
            method = "getRunningThread",
            at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
                    target = "Lnet/minecraft/server/level/ServerChunkCache;mainThread:Ljava/lang/Thread;"),
            require = 1)
    private Thread superchunk$dimensionOwnerThread(ServerChunkCache chunkSource, Operation<Thread> original) {
        return SkeinCompat.isDimensionTicker(chunkSource.getLevel())
                ? Thread.currentThread()
                : original.call(chunkSource);
    }
}
