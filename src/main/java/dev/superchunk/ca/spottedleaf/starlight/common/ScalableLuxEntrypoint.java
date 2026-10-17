package dev.superchunk.ca.spottedleaf.starlight.common;

import dev.superchunk.ca.spottedleaf.starlight.common.config.Config;

/**
 * ScalableLux init, merged into SuperChunk. Was a standalone {@code @Mod("scalablelux")}
 * entrypoint; now a plain init invoked from {@link dev.superchunk.SuperChunk} so the
 * merged mod exposes a single mod id.
 */
public final class ScalableLuxEntrypoint {
    private ScalableLuxEntrypoint() {}

    public static void init() {
        Config.init();
    }
}
