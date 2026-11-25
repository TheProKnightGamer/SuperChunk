package dev.superchunk.com.ishland.c2me.opts.dfc.mixin;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IRouterCompile;
import dev.superchunk.compat.ByePregenCompat;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SuperChunk: compiles the router after Bye?Pregen!'s constructor hooks when it is installed
 * ({@link ByePregenCompat#compileRouterLate}).
 *
 * <p>Callbacks at a constructor's RETURN run in mixin priority order. {@link MixinNoiseConfig} is
 * priority 900, so its compile runs before other mods' hooks there, and Bye Pregen (priority 1000)
 * then reads the compiled router. Up to 1.1.x it compiles its own final-density column and biome
 * columns there. It cannot see into a compiled function, so binding the column failed for every
 * NoiseChunk: a warning with a stack trace each time, and Bye Pregen's evaluator off. Bye Pregen
 * 1.2 reads the router when the field is first written instead, which is not affected. This
 * mixin's callback runs after Bye Pregen's, so it reads the vanilla router in every version.
 */
@Mixin(value = RandomState.class, priority = 1100)
public class MixinNoiseConfigLate {

    @Inject(method = "<init>", at = @At("RETURN"))
    private void superchunk$compileAfterByePregen(CallbackInfo ci) {
        if (ByePregenCompat.compileRouterLate()) {
            ((IRouterCompile) (Object) this).superchunk$compileRouter();
        }
    }
}
