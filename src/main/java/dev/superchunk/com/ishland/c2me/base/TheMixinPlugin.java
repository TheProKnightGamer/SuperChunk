package dev.superchunk.com.ishland.c2me.base;

import dev.superchunk.com.ishland.c2me.base.common.ModuleMixinPlugin;

/**
 * Used internally for c2me-base, do not subclass.
 */
public final class TheMixinPlugin extends ModuleMixinPlugin {

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!super.shouldApplyMixin(targetClassName, mixinClassName)) {
            return false;
        }

        return true;
    }
}
