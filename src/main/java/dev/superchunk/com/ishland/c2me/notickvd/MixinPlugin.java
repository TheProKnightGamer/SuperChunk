package dev.superchunk.com.ishland.c2me.notickvd;

import dev.superchunk.com.ishland.c2me.base.common.ModuleMixinPlugin;
import dev.superchunk.com.ishland.c2me.notickvd.common.Config;

public class MixinPlugin extends ModuleMixinPlugin {

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!super.shouldApplyMixin(targetClassName, mixinClassName)) return false;

        if (mixinClassName.startsWith("dev.superchunk.com.ishland.c2me.notickvd.mixin.ext_render_distance.")) {
            return Config.enableExtRenderDistanceProtocol;
        }

        return true;
    }
}
