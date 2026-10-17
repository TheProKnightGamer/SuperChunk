package dev.superchunk.com.ishland.c2me.base;

import dev.superchunk.com.ishland.c2me.base.common.config.ConfigSystem;

/**
 * C2ME base entry helper.
 *
 * <p>In standalone C2ME this is a NeoForge {@code @Mod("c2me_base")} class whose
 * {@code FMLCommonSetupEvent} listener flushes the config. In SuperChunk, C2ME is
 * merged into the single {@code superchunk} mod, so we do NOT register a second
 * {@code @Mod} here; SuperChunk's own entry point drives {@link #onSetup()} instead.
 */
public class C2MEBaseMod {

    public static void onSetup() {
        ConfigSystem.flushConfig();
    }

}
