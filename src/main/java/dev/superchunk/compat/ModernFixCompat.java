package dev.superchunk.compat;

import net.minecraftforge.fml.loading.LoadingModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns off the ModernFix patches that ModernFix itself turns off for C2ME.
 *
 * <p>ModernFix disables a few of its options when the {@code c2me} mod is installed
 * ({@code ModernFixEarlyConfig.disableIfModPresent}): they patch the vanilla chunk pipeline (the
 * full-chunk promotion executor, proto-chunk release, chunk-save NBT handling) that C2ME's chunk
 * system replaces. It checks the mod id only. The C2ME bundled in SuperChunk has none, so with
 * SuperChunk those patches stayed on, against a chunk system their authors keep them off for.
 * ModernFix's other mod checks are left alone: the bundled Lithium copy needs none of them, and its
 * {@code vmp} one is for a VMP module (ingredients) that SuperChunk does not bundle.
 *
 * <p>Mixin constructs each config's plugin in registration order, which SuperChunk cannot choose,
 * but constructs every one of them before any decides which of its mixins apply. So either
 * ModernFix's plugin exists already, and its loaded option gets a mod override (as ModernFix's own
 * check adds), or it does not, and the {@code modernfix.config.<option>} system property it reads
 * when it loads is set instead. An option the user set with that property is left as they set it.
 * Kill switch: {@code -Dsuperchunk.compat.modernfix=false}.
 */
public final class ModernFixCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Compat");
    private static final String PLUGIN = "org.embeddedt.modernfix.core.ModernFixMixinPlugin";
    /** What ModernFix 5.27 disables when c2me is installed. Options this ModernFix build lacks are skipped. */
    private static final String[] C2ME_OPTIONS = {
            "mixin.bugfix.paper_chunk_patches",
            "mixin.perf.nbt_memory_usage",
            "mixin.bugfix.chunk_deadlock",
            "mixin.perf.release_protochunks",
    };

    private ModernFixCompat() {
    }

    /** Called once from {@code SuperChunkMixinPlugin.onLoad}. Never throws. */
    public static void apply() {
        if (!Boolean.parseBoolean(System.getProperty("superchunk.compat.modernfix", "true"))) {
            return;
        }
        try {
            if (LoadingModList.get().getModFileById("modernfix") == null) {
                return;
            }
            Map<?, ?> loaded = loadedOptions();
            List<String> changed = new ArrayList<>();
            for (String name : C2ME_OPTIONS) {
                String property = "modernfix.config." + name;
                if (System.getProperty(property) != null) {
                    continue;
                }
                if (loaded == null) {
                    System.setProperty(property, "false");
                    changed.add(name);
                    continue;
                }
                Object option = loaded.get(name);
                if (option != null) {
                    option.getClass().getMethod("addModOverride", Object.class, String.class)
                            .invoke(option, Boolean.FALSE, "superchunk");
                    changed.add(name);
                }
            }
            if (!changed.isEmpty()) {
                LOGGER.info("[SuperChunk-Compat] ModernFix: switching off {}, as ModernFix does itself when C2ME is "
                        + "installed (SuperChunk bundles C2ME's chunk system).", String.join(", ", changed));
            }
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-Compat] Could not turn off ModernFix's C2ME-conflicting options; they stay as "
                    + "ModernFix's config sets them.", t);
        }
    }

    /** ModernFix's option map if its mixin plugin has been constructed, else {@code null}. */
    private static Map<?, ?> loadedOptions() throws ReflectiveOperationException {
        Class<?> plugin;
        try {
            plugin = Class.forName(PLUGIN, false, ModernFixCompat.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
        Object instance = plugin.getField("instance").get(null);
        if (instance == null) {
            return null;
        }
        Object config = plugin.getField("config").get(instance);
        return config == null ? null : (Map<?, ?>) config.getClass().getMethod("getOptionMap").invoke(config);
    }
}
