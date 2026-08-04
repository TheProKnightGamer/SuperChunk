package dev.superchunk.mixin.client;

import dev.superchunk.client.ExtendedRenderDistance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Sodium compatibility: widen <b>Sodium's own</b> render-distance slider cap.
 *
 * <p>{@link MixinOptionsExtendRenderDistance} widens the vanilla {@code Options} render-distance
 * {@code IntRange} to {@link ExtendedRenderDistance#maxRenderDistance()} — and that is the only cap
 * on a vanilla-renderer client. But Sodium <em>replaces</em> the video-settings screen with its own
 * and builds the render-distance slider from a hardcoded range,
 * {@code setRange(2, 32, 1)} in {@code SodiumConfigBuilder.buildGeneralPage} (the option is still
 * bound to vanilla {@code Options.renderDistance()} — only the slider bounds are Sodium's). So with
 * Sodium installed the slider stopped at 32 regardless of the vanilla widening. This lifts Sodium's
 * slider maximum to the same configured cap; the binding to the vanilla option is untouched.
 *
 * <p>String {@code targets} + {@code remap = false}: Sodium's classes are not Mojang-mapped and are
 * absent without Sodium — the mixin then simply never applies (no hard dependency; nothing here
 * imports a Sodium type). Anchored to the {@code "options.renderDistance"} name constant so the
 * first {@code setRange} after it is the render-distance one (not simulation/entity distance).
 * {@code require = 0}: if Sodium restructures the option we quietly leave its slider at 32 rather
 * than crash the client.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.gui.SodiumConfigBuilder", remap = false)
public class MixinSodiumRenderDistanceCap {

    @ModifyArg(
            method = "buildGeneralPage",
            slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=options.renderDistance")),
            at = @At(value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/api/config/structure/IntegerOptionBuilder;"
                            + "setRange(III)Lnet/caffeinemc/mods/sodium/api/config/structure/IntegerOptionBuilder;",
                    ordinal = 0),
            index = 1,
            require = 0)
    private int superchunk$widenSodiumRenderDistanceCap(int max) {
        int widened = Math.max(max, ExtendedRenderDistance.maxRenderDistance());
        // One-time confirmation (Sodium builds its config once): shows Sodium's slider was widened.
        dev.superchunk.SuperChunk.LOGGER.info(
                "[SuperChunk] Sodium render-distance slider cap {} -> {}", max, widened);
        return widened;
    }
}
