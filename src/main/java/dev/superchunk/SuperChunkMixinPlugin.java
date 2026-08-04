package dev.superchunk;

import dev.superchunk.config.FullSpeedLoading;
import dev.superchunk.config.PlayerLatency;
import dev.superchunk.config.PredictiveGen;
import dev.superchunk.config.SuperChunkConfig;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin config plugin for {@code superchunk.mixins.json}. Its only job is to run the unified-config
 * <b>write-through</b> ({@link SuperChunkConfig#applyEarly()}) as early as possible.
 *
 * <p>{@code onLoad} fires while the mixin subsystem prepares this config — before mixins apply and
 * before the mod constructor. Because {@code superchunk.mixins.json} is declared FIRST in
 * {@code neoforge.mods.toml}, this runs before {@code LithiumMixinPlugin.onLoad()} reads
 * {@code config/lithium.properties} and before C2ME's {@code ConfigSystem} initializes, so the
 * derived Lithium file + C2ME system-property overrides are in place in time.
 *
 * <p>Per-mixin gating ({@link #shouldApplyMixin}): almost all SuperChunk mixins apply
 * unconditionally and gate at RUNTIME via {@code -D} flags; the {@code *InlineHeights} pair is the
 * exception — its optimisation lives in soft-overridden methods (no runtime flag possible), so it
 * is gated at LOAD time on {@code superchunk.worldgen.inlineHeights}. Note
 * {@code MixinOreFeatureFast}'s y-clamp parity argument relies on the inline-heights values
 * matching the vanilla interface defaults, which holds with the gate on or off.
 * {@code MixinPlayerChunkSenderFullSpeed} (and the
 * notickvd counterpart registered in {@code c2me-notickvd.mixins.json}) gate at RUNTIME on the
 * {@code player.fullSpeedLoading} toggle instead: every hook is a checked no-op while the toggle
 * is OFF (static-final flag, JIT-eliminated), so default behavior is unchanged.
 */
public final class SuperChunkMixinPlugin implements IMixinConfigPlugin {

    /**
     * Runs the config write-through at the EARLIEST SuperChunk hook: Mixin instantiates this plugin
     * (ctor) immediately before calling {@link #onLoad}, so writing {@code config/lithium.properties}
     * here — rather than only in onLoad — narrows the window before a standalone Lithium's plugin
     * reads that file. Idempotent ({@link SuperChunkConfig#applyEarly()} guards on a flag), so the
     * onLoad call below is harmless. Cross-mod ordering is additionally pinned by an optional
     * {@code ordering = "BEFORE"} dependency on {@code lithium} in {@code neoforge.mods.toml}.
     */
    public SuperChunkMixinPlugin() {
        SuperChunkConfig.applyEarly();
    }

    @Override
    public void onLoad(String mixinPackage) {
        SuperChunkConfig.applyEarly();
        // One boot line, only when player.fullSpeedLoading=true, stating exactly which
        // server-side chunk-loading throttles are lifted this run.
        FullSpeedLoading.logBootLine();
        // Same contract for the player-latency fixes (player.sendAtChunkSending /
        // player.priorityBias / player.latencyMetrics): one boot line per enabled flag.
        PlayerLatency.logBootLine();
        // And for predictive chunk generation (player.predictiveGen): one boot line when ON.
        PredictiveGen.logBootLine();
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Load-time gates for mixins whose optimisation lives in soft-overridden methods
        // (no runtime flag possible). Each defaults ON; the -D kill-switch exists for
        // regression bisection.
        if (mixinClassName.endsWith("InlineHeights")) {
            return Boolean.parseBoolean(System.getProperty("superchunk.worldgen.inlineHeights", "true"));
        }
        // Post-process neighbour-shape no-op skip (see MixinLevelChunkPostProcessSkip): defers to
        // Bye?Pregen!'s own redirect of the same call site when it is installed (avoids a duplicate
        // injection), and honours the -D kill switch. Load-time gate so the applied path has no
        // per-call branch.
        if (mixinClassName.endsWith("PostProcessSkip")) {
            return !dev.superchunk.compat.ByePregenCompat.INSTALLED
                    && Boolean.parseBoolean(System.getProperty("superchunk.worldgen.postProcessSkipNoOp", "true"));
        }
        // Sodium-only: widen Sodium's hardcoded render-distance slider cap to client.maxRenderDistance.
        // Inert without Sodium (target class absent); the -D kill switch allows bisection.
        if (mixinClassName.endsWith("SodiumRenderDistanceCap")) {
            return Boolean.parseBoolean(System.getProperty("superchunk.client.sodiumRenderDistanceCap", "true"));
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
