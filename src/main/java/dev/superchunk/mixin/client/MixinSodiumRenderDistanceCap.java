package dev.superchunk.mixin.client;

import dev.superchunk.client.ExtendedRenderDistance;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Sodium compatibility: widen <b>Sodium's own</b> render-distance slider cap.
 *
 * <p>{@link MixinOptionsExtendRenderDistance} widens the vanilla {@code Options} render-distance
 * {@code IntRange} to {@link ExtendedRenderDistance#maxRenderDistance()} — and that is the only cap
 * on a vanilla-renderer client. But the Sodium family <em>replaces</em> the video-settings screen
 * with its own and builds the render-distance slider from a hardcoded range (the option is still
 * bound to vanilla {@code Options.renderDistance()} — only the slider bounds are Sodium's). So with
 * a Sodium-family renderer installed the slider stopped at 32 regardless of the vanilla widening.
 * This lifts that slider maximum to the same configured cap; the binding to the vanilla option is
 * untouched.
 *
 * <p>{@code @Pseudo} + string {@code targets} + {@code remap = false}: the target is not
 * Mojang-mapped and is absent without the renderer mod — the mixin then simply never applies (no
 * hard dependency; nothing here imports a Sodium type). {@code require = 0}: if the fork
 * restructures the option we quietly leave its slider at 32 rather than crash the client.
 * Kill switch {@code -Dsuperchunk.client.sodiumRenderDistanceCap=false} (gated in
 * {@code SuperChunkMixinPlugin}).
 *
 * <p><b>FORGE 1.20.1 PORT — TARGET DIVERGES FROM NEO.</b> The neo (1.21.1) mixin
 * {@code @ModifyArg}s {@code IntegerOptionBuilder.setRange(III)} inside
 * {@code net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder.buildGeneralPage}. That whole
 * declarative config API is Sodium <b>0.6.9+</b> and <b>does not exist anywhere on the 1.20.1
 * line</b> — there is no official Sodium build for Forge 1.20.1 at all; the 1.20.1 Sodium-family
 * renderers are Sodium 0.5.x forks. Targeting the neo class here would have been a mixin that can
 * never apply, so the injection was re-derived from Sodium-0.5.x-fork bytecode:
 * <ul>
 *   <li>Class: {@code me.jellysquid.mods.sodium.client.gui.SodiumGameOptionPages} — the option-page
 *       factory the whole Sodium 0.5.x fork lineage inherits (Fabric Sodium 0.5.x, Rubidium,
 *       Embeddium all keep the {@code me.jellysquid} package). Confirmed present by disassembly in
 *       {@code embeddium-0.3.31+mc1.20.1} and in {@code rubidium-0.5.2a}.</li>
 *   <li>Method: {@code lambda$general$0} — the {@code setControl} lambda of the FIRST option built
 *       by {@code public static OptionPage general()}, i.e. the render-distance slider. Both jars
 *       agree: rubidium builds {@code new SliderControl(option, 2, 32, 1,
 *       ControlValueFormatter.translateVariable("options.chunks"))}; embeddium builds the same with
 *       {@code computeMaxRangeForRenderDistance(32)} in the max slot. Each contains exactly ONE
 *       {@code 32} constant, so the un-ordinal'd {@code @ModifyConstant} is unambiguous (the
 *       simulation-distance slider is a different lambda — {@code lambda$general$3}, min 5 not
 *       2).</li>
 *   <li>Shape: {@code @ModifyConstant} rather than neo's {@code @ModifyArg} + {@code @Slice} —
 *       there is no builder call to modify an argument of, the bound is a bare {@code bipush 32}.
 *       This is also exactly what upstream C2ME's own {@code c2me-client-uncapvd}
 *       ({@code MixinSodiumUserConfigCategories}) does for this MC line, so the shape is not
 *       invented here.</li>
 *   <li>{@code lambda$general$0} is {@code private static}, hence the {@code static} handler
 *       (mixin 0.8.5 static-ness rule).</li>
 * </ul>
 *
 * <p><b>How far the evidence actually goes — read before trusting this on 1.20.1.</b> Of the two
 * jars disassembled, only {@code embeddium-0.3.31+mc1.20.1} is a 1.20.1 build
 * ({@code loaderVersion="[47,)"}). {@code rubidium-0.5.2a} is a <b>1.18.2</b> build
 * ({@code loaderVersion="[39,)"}; it references {@code net.minecraft.network.chat.TranslatableComponent},
 * removed in 1.19, and the pre-{@code OptionInstance} {@code int} render-distance field) — it is
 * evidence that the class/lambda shape is STABLE across the 0.5.x fork lineage, <em>not</em>
 * evidence about 1.20.1. And on the one true 1.20.1 jar this mixin is a no-op: Embeddium's
 * {@code computeMaxRangeForRenderDistance} already returns
 * {@code Math.max(32, vanillaIntRange.maxInclusive())}, and {@link MixinOptionsExtendRenderDistance}
 * has already widened that vanilla {@code IntRange}. So the injection is <b>unexercised on 1.20.1</b>
 * until it is run against a fork that hardcodes the bound (Rubidium 0.7.x for 1.20.1, older
 * Embeddium 0.2.x) — the {@code [SuperChunk] Sodium render-distance slider cap} log line is the only
 * signal that it bound at all, since {@code require = 0} turns a miss into silence.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.gui.SodiumGameOptionPages", remap = false)
public class MixinSodiumRenderDistanceCap {

    @Dynamic
    @ModifyConstant(method = "lambda$general$0", constant = @Constant(intValue = 32), remap = false, require = 0)
    private static int superchunk$widenSodiumRenderDistanceCap(int max) {
        int widened = Math.max(max, ExtendedRenderDistance.maxRenderDistance());
        // Confirmation that Sodium's slider was widened. NOTE vs neo: neo's target builds the
        // config once at startup, so its line was one-time; the 0.5.x forks rebuild the option
        // pages in the SodiumOptionsGUI constructor, so this logs once per video-settings open.
        // Kept at INFO anyway — it is the ONLY signal the injection bound (require = 0).
        dev.superchunk.SuperChunk.LOGGER.info(
                "[SuperChunk] Sodium render-distance slider cap {} -> {}", max, widened);
        return widened;
    }
}
