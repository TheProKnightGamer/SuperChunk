package dev.superchunk.net.caffeinemc.mods.lithium.forge;

import dev.superchunk.net.caffeinemc.mods.lithium.common.config.Option;
import dev.superchunk.net.caffeinemc.mods.lithium.common.services.PlatformMixinOverrides;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ForgeMixinOverrides implements PlatformMixinOverrides {
    // Kept as "lithium:options" so mods that declare option overrides for radium/standalone
    // Lithium also affect the bundled module.
    protected static final String JSON_KEY_LITHIUM_OPTIONS = "lithium:options";

    /**
     * Voxy's ingest ({@code WorldConversionFactory.setupLocalPalette}) accepts the vanilla palettes,
     * plus {@code net.caffeinemc.mods.lithium.common.world.chunk.LithiumHashPalette} only when a mod
     * with id {@code lithium} is loaded. This bundled copy is neither: its palette lives under
     * {@code dev.superchunk.*}, so every section {@code mixin.chunk.palette} gave a hash palette throws
     * "Unknown palette type" and Voxy never ingests it (GitHub issue #8). Turning the rule off here,
     * as if Voxy declared it in {@code lithium:options}, affects only the bundled copy: a standalone
     * Lithium makes this one stand down, and Voxy recognises its palette. Kill switch:
     * {@code -Dsuperchunk.compat.voxy=false}.
     *
     * <p>Forge 1.20.1: no Voxy build for this line is known (the fix was found and verified on
     * NeoForge 1.21.1 via Roxy). Kept so a "voxy" mod id, however it arrives, gets the same
     * treatment; it costs nothing when absent, and when present it only selects vanilla's palette.
     */
    private static final boolean VOXY_COMPAT =
            Boolean.parseBoolean(System.getProperty("superchunk.compat.voxy", "true"));

    @Override
    public void applyLithiumCompat(Map<String, Option> options) {

    }

    @Override
    public List<PlatformMixinOverrides.MixinOverride> applyModOverrides() {
        List<MixinOverride> list = new ArrayList<>();

        for (ModInfo meta : FMLLoader.getLoadingModList().getMods()) {
            if (VOXY_COMPAT && "voxy".equals(meta.getModId())) {
                list.add(new MixinOverride(meta.getModId(), "mixin.chunk.palette", false));
            }
            // NOTE: the IModFileInfo INTERFACE (forgespi 7.x) does NOT declare getConfigElement —
            // it is declared on the concrete ModFileInfo returned by ModInfo.getOwningFile()
            // (javap-verified on fmlloader 1.20.1-47.3.0). Call it on the concrete type.
            meta.getOwningFile().getConfigElement(JSON_KEY_LITHIUM_OPTIONS).ifPresent(override -> {
                if (override instanceof Map<?, ?> overrides && overrides.keySet().stream().allMatch(key -> key instanceof String)) {
                    overrides.forEach((key, value) -> {
                        if (!(value instanceof Boolean) || !(key instanceof String)) {
                            System.out.printf("[Lithium] Mod '%s' attempted to override option '%s' with an invalid value, ignoring", meta.getModId(), key);
                            return;
                        }

                        list.add(new MixinOverride(meta.getModId(), (String) key, (Boolean) value));
                    });
                } else {
                    System.out.printf("[Lithium] '%s' contains invalid Lithium option overrides, ignoring", meta.getModId());
                }
            });
        }

        return list;
    }
}
