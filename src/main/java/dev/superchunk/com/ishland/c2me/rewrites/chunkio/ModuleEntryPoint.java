package dev.superchunk.com.ishland.c2me.rewrites.chunkio;

import dev.superchunk.com.ishland.c2me.base.common.config.ConfigSystem;

public class ModuleEntryPoint {

    /**
     * Forge 1.20.1 port: {@code -Dsuperchunk.chunksystem.disable=true} is the shared master
     * kill switch of the three rewrites modules (yields a pure Waves-A+B server).
     */
    private static final boolean enabled = new ConfigSystem.ConfigAccessor()
            .key("ioSystem.replaceImpl")
            .comment("Whether to use the optimized implementation of IO system")
            .getBoolean(true, false)
            && !Boolean.getBoolean("superchunk.chunksystem.disable");

}
