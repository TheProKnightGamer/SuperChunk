package dev.superchunk.com.ishland.c2me.threading.lighting;

import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.LoadingModList;

public class ModuleEntryPoint {

    private static final boolean enabled = !isModLoaded("lightbench");

    private static boolean isModLoaded(String modId) {
        try {
            final LoadingModList loadingModList = FMLLoader.getLoadingModList();
            if (loadingModList == null) return false;
            return loadingModList.getMods().stream().anyMatch(modInfo -> modInfo.getModId().equals(modId));
        } catch (Throwable t) {
            return false;
        }
    }

}
