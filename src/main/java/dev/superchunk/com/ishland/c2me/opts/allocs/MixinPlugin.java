package dev.superchunk.com.ishland.c2me.opts.allocs;

import dev.superchunk.com.ishland.c2me.base.common.ModuleMixinPlugin;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;

public class MixinPlugin extends ModuleMixinPlugin {

    private static boolean isModLoaded(String modId) {
        try {
            final LoadingModList loadingModList = FMLLoader.getLoadingModList();
            if (loadingModList != null &&
                    loadingModList.getMods().stream().anyMatch(modInfo -> modInfo.getModId().equals(modId))) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!super.shouldApplyMixin(targetClassName, mixinClassName)) return false;

        // Upstream gates these on Lithium's ABSENCE (they duplicate its NBT optimization).
        // Lithium is compiled into this merged jar, so the answer is hard-wired: always
        // defer to the vendored Lithium — no loader/class-presence detection needed.
        if (mixinClassName.equals("dev.superchunk.com.ishland.c2me.opts.allocs.mixin.MixinNbtCompound") ||
                mixinClassName.equals("dev.superchunk.com.ishland.c2me.opts.allocs.mixin.MixinNbtCompound1"))
            return false;

        if (mixinClassName.startsWith("dev.superchunk.com.ishland.c2me.opts.allocs.mixin.surfacebuilder.")) {
            return !isModLoaded("quilted_fabric_api") &&
                   !isModLoaded("frozenlib");
        }

        return true;
    }

}
