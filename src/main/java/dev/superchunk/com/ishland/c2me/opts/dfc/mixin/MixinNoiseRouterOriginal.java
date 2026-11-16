package dev.superchunk.com.ishland.c2me.opts.dfc.mixin;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IOriginalNoiseRouter;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Keeps the vanilla router beside the compiled one (see {@link IOriginalNoiseRouter}). */
@Mixin(NoiseRouter.class)
public class MixinNoiseRouterOriginal implements IOriginalNoiseRouter {

    @Unique
    private NoiseRouter c2me$original;

    @Override
    public NoiseRouter c2me$getOriginalNoiseRouter() {
        return this.c2me$original;
    }

    @Override
    public void c2me$setOriginalNoiseRouter(NoiseRouter original) {
        this.c2me$original = original;
    }
}
