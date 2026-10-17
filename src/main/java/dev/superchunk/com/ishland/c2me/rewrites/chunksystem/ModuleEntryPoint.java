package dev.superchunk.com.ishland.c2me.rewrites.chunksystem;

import dev.superchunk.com.ishland.c2me.rewrites.chunksystem.common.Config;

public class ModuleEntryPoint {

    /**
     * Forge 1.20.1 port master kill switch (plan §5): {@code -Dsuperchunk.chunksystem.disable=true}
     * reverts a production install to the vanilla chunk system (pure Waves-A+B server) without
     * editing c2me.toml. Shared by the chunk-serializer and chunkio rewrite modules.
     * Read reflectively by {@link dev.superchunk.com.ishland.c2me.base.common.ModuleMixinPlugin}
     * and directly by the GPU vanilla-seam mixin guard
     * ({@code dev.superchunk.mixin.MixinNoiseBasedChunkGeneratorGpuSeam}).
     */
    public static final boolean enabled = !Boolean.getBoolean("superchunk.chunksystem.disable");

    static {
        Config.init();
    }

    public static boolean isEnabled() {
        return enabled;
    }

}
