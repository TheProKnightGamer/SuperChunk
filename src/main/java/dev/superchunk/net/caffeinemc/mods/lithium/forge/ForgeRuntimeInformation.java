package dev.superchunk.net.caffeinemc.mods.lithium.forge;

import dev.superchunk.net.caffeinemc.mods.lithium.common.services.PlatformRuntimeInformation;
import net.minecraftforge.fml.loading.FMLConfig;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.LoadingModList;

import java.nio.file.Path;

public class ForgeRuntimeInformation implements PlatformRuntimeInformation {
    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    @Override
    public Path getGameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path getConfigDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean platformHasEarlyLoadingScreen() {
        return FMLConfig.getBoolConfigValue(FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL);
    }

    @Override
    public boolean platformUsesRefmap() {
        // Forge 1.20.1 uses refmaps (superchunk.refmap.json — the shared sourceset refmap).
        return true;
    }

    @Override
    public boolean isModInLoadingList(String modId) {
        return LoadingModList.get().getModFileById(modId) != null;
    }

    @Override
    public boolean usesAlphaMultiplication() {
        return true;
    }
}
