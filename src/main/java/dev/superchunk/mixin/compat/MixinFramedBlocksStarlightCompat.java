package dev.superchunk.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.invoke.MethodHandles;

/**
 * FramedBlocks gives a camouflaged block the light emission of the block it copies, read from its block entity.
 * Starlight asks for emission from its light threads with the level as the getter, and 1.20.1's
 * {@code Level.getBlockEntity} returns null off the server thread, so FramedBlocks reads the chunk's block entity
 * directly instead when {@code StarlightCompat} finds the {@code starlight} mod. The bundled ScalableLux is a
 * Starlight fork with the same light threads, but no such mod id, so framed light sources stayed dark under it.
 * This sets the same flag. Missing or changed in a future FramedBlocks, it does nothing.
 */
@Pseudo
@Mixin(targets = "xfacthd.framedblocks.common.compat.starlight.StarlightCompat", remap = false)
public abstract class MixinFramedBlocksStarlightCompat {
    @Inject(method = "init", at = @At("TAIL"), require = 0, remap = false)
    private static void superchunk$bundledStarlight(CallbackInfo ci) {
        try {
            // Runs inside StarlightCompat, so this lookup may set its private field.
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            lookup.findStaticSetter(lookup.lookupClass(), "loaded", boolean.class).invokeExact(true);
        } catch (Throwable ignored) {
        }
    }
}
