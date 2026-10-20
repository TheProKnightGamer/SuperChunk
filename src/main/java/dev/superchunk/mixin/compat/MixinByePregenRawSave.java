package dev.superchunk.mixin.compat;

import dev.superchunk.compat.ByePregenCompat;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Save Bye?Pregen!'s worldgen chunks through the bundled C2ME, not its raw path ({@link ByePregenCompat#c2meStoresChunks}). */
@Pseudo
@Mixin(targets = "com.moepus.byepregen.chunksave.serialize.GcFreeChunkSerializer", remap = false)
public abstract class MixinByePregenRawSave {
    @Inject(method = "shouldUseGcFree", at = @At("HEAD"), cancellable = true, require = 1)
    private static void superchunk$saveThroughC2me(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
        if (ByePregenCompat.c2meStoresChunks()) {
            cir.setReturnValue(false);
        }
    }
}
