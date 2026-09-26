package dev.superchunk.mixin.compat;

import dev.superchunk.compat.SkeinCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Keep startup, file reloads, and command reloads inside the supported dimension-only configuration. */
@Pseudo
@Mixin(targets = {
        "com.theproknightgamr.skein.SkeinConfig",
        "com.asher.skein.SkeinConfig"
}, remap = false)
public abstract class MixinSkeinConfig {
    @Inject(method = "refresh", at = @At("HEAD"), cancellable = true, require = 1)
    private static void superchunk$deferRefresh(CallbackInfo ci) {
        if (SkeinCompat.deferConfigMutation()) {
            ci.cancel();
        }
    }

    @Inject(method = "refresh", at = @At("RETURN"), require = 1)
    private static void superchunk$pinSupportedPhases(CallbackInfo ci) {
        SkeinCompat.afterConfigRefresh();
    }

    // Skein's reload command calls this immediately after refresh. Canceling
    // only refresh would leave these shared sets rebuilding inside the barrier.
    @Inject(method = "resolveRegistries", at = @At("HEAD"), cancellable = true, require = 1)
    private static void superchunk$deferRegistryRebuild(CallbackInfoReturnable<List<String>> cir) {
        if (SkeinCompat.deferConfigMutation()) {
            cir.setReturnValue(List.of());
        }
    }
}
