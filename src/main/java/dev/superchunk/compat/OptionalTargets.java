package dev.superchunk.compat;

import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;

import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a mixin target that lives in another mod exists in the installed version of that mod.
 *
 * <p>A few compat mixins name classes that only some versions of their mod have (Sodium 0.8's
 * {@code SodiumConfigBuilder}, both of Skein's package names, both of Distant Horizons' step class
 * names). Mixin loads each named target before it can skip it, and logs "Error loading class" or
 * "@Mixin target ... was not found" at WARN for every absent one on every boot. Asking this from a
 * plugin's {@code shouldApplyMixin} skips absent targets before Mixin looks them up.
 *
 * <p>Answers from the mod jars' file listings (no class loading). When the listing cannot be read
 * it answers {@code true}, which leaves the decision to Mixin exactly as before.
 */
public final class OptionalTargets {

    private static final ConcurrentHashMap<String, Boolean> CACHE = new ConcurrentHashMap<>();

    private OptionalTargets() {
    }

    public static boolean present(String className) {
        return CACHE.computeIfAbsent(className, OptionalTargets::lookup);
    }

    private static Boolean lookup(String className) {
        final String path = className.replace('.', '/') + ".class";
        try {
            for (ModFileInfo info : LoadingModList.get().getModFiles()) {
                if (Files.exists(info.getFile().findResource(path))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }
}
