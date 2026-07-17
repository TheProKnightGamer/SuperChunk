package dev.superchunk.com.ishland.c2me.rewrites.chunksystem;

import dev.superchunk.com.ishland.c2me.base.common.ModuleMixinPlugin;
import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.Config;

public class MixinPlugin extends ModuleMixinPlugin {

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!super.shouldApplyMixin(targetClassName, mixinClassName))
            return false;

        if (mixinClassName.startsWith("dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin.fluid_postprocessing"))
            return Config.fluidPostProcessingToScheduledTick;
        if (mixinClassName.startsWith("dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin.async_chunkio."))
            return Config.asyncSerialization;
        if (mixinClassName.startsWith("dev.superchunk.com.ishland.c2me.rewrites.chunksystem.mixin.async_serialization"))
            return Config.asyncSerialization;

        return true;
    }
}
