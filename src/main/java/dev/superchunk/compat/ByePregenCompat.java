package dev.superchunk.compat;

import net.neoforged.fml.loading.LoadingModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interop with <b>Bye?Pregen!</b> ({@code byepregen}, by MoePus) — a worldgen mod that optimizes
 * block placement, chunk post-processing and the cascading loads post-processing triggers.
 *
 * <p><b>The gap this closes.</b> Bye Pregen and C2ME both filter/normalize the fluid
 * post-processing lists, so running both is redundant/conflicting. Bye Pregen solves this on its
 * side with {@code C2MEServerBlockTickingMixin}, which redirects
 * {@code Config.filterFluidPostProcessing} to {@code false} inside C2ME's
 * {@code ServerBlockTicking.upgradeToThis} — i.e. it turns C2ME's own fluid filter OFF and lets
 * Bye Pregen's post-process optimizer own that work. But that mixin
 * <ul>
 *   <li>targets the <i>un-relocated</i> {@code com.ishland.c2me.…ServerBlockTicking}, whereas
 *       SuperChunk bundles C2ME <i>relocated</i> under {@code dev.superchunk.com.ishland.c2me.…},
 *       and</li>
 *   <li>is gated {@code @MixinGate(requiredMods = "c2me_rewrites_chunk_system")}, a mod id
 *       SuperChunk does not register (it is one mod, {@code superchunk}).</li>
 * </ul>
 * So Bye Pregen's C2ME compat is inert against SuperChunk, and SuperChunk's relocated
 * {@code ServerBlockTicking.filterFluidTicks()} (gated by {@code chunkSystem.filterFluidPostProcessing},
 * default {@code true}) would keep running alongside Bye Pregen's optimizer.
 *
 * <p><b>What we do.</b> When {@code byepregen} is present we replicate Bye Pregen's decision for the
 * relocated copy: force {@code chunkSystem.filterFluidPostProcessing=false} via the same
 * {@code -Dc2me.base.config.override.<key>} system-property channel {@link
 * dev.superchunk.config.SuperChunkConfig} already uses (read by the relocated
 * {@code ConfigSystem.ConfigAccessor} with precedence over the toml). Only that one option is
 * touched — exactly what Bye Pregen's redirect does; {@code fluidPostProcessingToScheduledTick} is
 * left alone. Bye Pregen's own vanilla-path {@code LevelChunk.postProcessGeneration} hooks (its
 * HEAD pre-process inject and its {@code updateFromNeighbourShapes} redirect) coexist cleanly with
 * SuperChunk's injects on that method — they touch distinct instructions, so no mixin conflict.
 *
 * <p><b>Hygiene.</b> An explicit user override wins: if the property is already set (from the
 * command line, or because the user put {@code c2me.chunkSystem.filterFluidPostProcessing} in
 * {@code superchunk.properties}, which {@code SuperChunkConfig.applyC2me} pushes first), we do not
 * clobber it. Detection is via {@link LoadingModList} — the same early API SuperChunk's bundled
 * Lithium uses for stand-down detection, and the same one Bye Pregen uses itself — so it resolves
 * at mixin-plugin time, before the relocated {@code Config} class initializes. Never throws: any
 * trouble logs and leaves the shipped default (filter on) in place.
 */
public final class ByePregenCompat {

    private static final Logger LOGGER = LoggerFactory.getLogger("SuperChunk-Compat");

    /** Bye?Pregen! CurseForge/NeoForge mod id. */
    public static final String MOD_ID = "byepregen";

    /** The relocated C2ME config key whose fluid filter Bye Pregen disables for compatibility. */
    private static final String FILTER_KEY = "chunkSystem.filterFluidPostProcessing";

    /** C2ME's system-property override prefix (un-relocated string contract; see {@code ConfigSystem}). */
    private static final String C2ME_OVERRIDE_PROP = "c2me.base.config.override.";

    /** True when Bye?Pregen! is on the mod list. Resolved once, defensively (false on any error). */
    public static final boolean INSTALLED = detectInstalled();

    private ByePregenCompat() {
    }

    private static boolean detectInstalled() {
        try {
            return LoadingModList.get().getModFileById(MOD_ID) != null;
        } catch (Throwable t) {
            // If the mod list can't be read this early, fail toward shipped behavior (no override).
            LOGGER.debug("[SuperChunk-Compat] Could not query the mod list for '{}' — assuming absent.", MOD_ID, t);
            return false;
        }
    }

    /**
     * If Bye?Pregen! is installed, disable the relocated C2ME fluid post-processing filter (unless
     * the user has explicitly overridden it). Called from {@code SuperChunkConfig.applyEarly()} after
     * the normal C2ME write-through, so an explicit user value is already in place and wins. Never
     * throws; idempotent (re-setting the same property is a no-op).
     */
    public static void applyEarlyOverrides() {
        try {
            if (!INSTALLED) {
                return;
            }
            String prop = C2ME_OVERRIDE_PROP + FILTER_KEY;
            if (System.getProperty(prop) != null) {
                LOGGER.info("[SuperChunk-Compat] byepregen detected, but {} is already set to '{}' — respecting it.",
                        prop, System.getProperty(prop));
                return;
            }
            System.setProperty(prop, "false");
            LOGGER.info("[SuperChunk-Compat] byepregen detected — disabled relocated C2ME {}=false so Bye Pregen's "
                    + "post-process optimizer owns fluid post-processing (mirrors Bye Pregen's own C2ME compat, "
                    + "which cannot reach SuperChunk's relocated C2ME). Set -D{}=true to force it back on.",
                    FILTER_KEY, prop);
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-Compat] Failed to apply byepregen C2ME override — filter stays at its default.", t);
        }
    }
}
