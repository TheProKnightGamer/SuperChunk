package dev.superchunk.com.ishland.c2me.notickvd;

import dev.superchunk.com.ishland.c2me.base.common.ModuleMixinPlugin;
import dev.superchunk.com.ishland.c2me.notickvd.common.Config;

public class MixinPlugin extends ModuleMixinPlugin {

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!super.shouldApplyMixin(targetClassName, mixinClassName)) return false;

        if (mixinClassName.startsWith("dev.superchunk.com.ishland.c2me.notickvd.mixin.ext_render_distance.")) {
            // Per-player VD is a VMP concept on 1.20.1 — the override duck only exists with the
            // VMP module applied (plan §3.3), so honor the VMP kill-switch too.
            return Config.enableExtRenderDistanceProtocol
                    && Boolean.parseBoolean(System.getProperty("superchunk.vmp.enabled", "true"));
        }

        // Chunk-system-aware CHUNK_SENDING seat: only meaningful (and only safe — the overwrite
        // replaces the Wave-C status behavior) when the c2me chunk system is live. When it is
        // disabled, the vanilla-ticket path's ChunkMap hooks (prepareAccessibleChunk et al.) are
        // the send seat instead.
        if (mixinClassName.endsWith(".MixinServerAccessibleChunkSending")
                || mixinClassName.endsWith(".MixinServerBlockTicking")) {
            try {
                return dev.superchunk.com.ishland.c2me.rewrites.chunksystem.ModuleEntryPoint.isEnabled();
            } catch (Throwable t) {
                return false;
            }
        }

        return true;
    }
}
