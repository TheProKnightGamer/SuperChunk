package dev.superchunk.worldgen;

import dev.superchunk.MixinTargetScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether one of SuperChunk's replicas of a vanilla method may run. A replica answers the method
 * itself instead of calling it, so if another mod hooks that method — or a vanilla method the
 * replica inlines — the replica would silently skip that mod's code (owo-lib's ore hook on
 * {@code OreFeature.doPlace}, for one). It stands down to the original method instead.
 *
 * <p>Returns {@link #CLEAR}, {@link #HOOKED} or {@link #UNKNOWN}; callers cache the first definitive
 * answer in a static field, so the hot path is one field read. {@link #UNKNOWN} (a named class not
 * loaded yet) means "run the original this time and ask again".
 */
public final class ForeignHooks {

    public static final byte UNKNOWN = 0;
    public static final byte CLEAR = 1;
    public static final byte HOOKED = 2;

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Compat");
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private ForeignHooks() {
    }

    /** {@code classHashMethods}: {@code "pkg.Class#method"} for the method and everything it inlines. */
    public static byte state(String replica, String... classHashMethods) {
        final String hook = MixinTargetScan.foreignHook(classHashMethods);
        if (hook == null) {
            return CLEAR;
        }
        if (hook.startsWith("<")) {
            return UNKNOWN;
        }
        if (LOGGED.add(replica)) {
            LOGGER.info("[SuperChunk] {} stands down: {} also hooks it, so the original method runs.", replica, hook);
        }
        return HOOKED;
    }
}
