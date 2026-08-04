package dev.superchunk.mixin.client;

import dev.superchunk.client.ExtendedRenderDistance;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

/**
 * Widens the vanilla render-distance slider cap (32) to
 * {@code client.maxRenderDistance} — see {@link ExtendedRenderDistance} for the full
 * capability wiring. With the default (32) this is an exact no-op: {@code Math.max}
 * returns vanilla's own cap.
 *
 * <p>Anchoring: the target is the {@code new OptionInstance.IntRange(2, is64Bit ? 32 : 16,
 * false)} in {@code Options.<init>}, disambiguated from the other IntRange constructions
 * (simulation distance etc.) by slicing from the {@code "options.renderDistance"} string
 * constant immediately preceding it. {@code require = 0}: if another mod restructures the
 * constructor, we quietly stay at vanilla rather than crash the client.
 */
@Mixin(Options.class)
public class MixinOptionsExtendRenderDistance {

    @ModifyArg(
            method = "<init>",
            slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=options.renderDistance")),
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/OptionInstance$IntRange;<init>(IIZ)V",
                    ordinal = 0),
            index = 1,
            require = 0)
    private int superchunk$extendRenderDistanceMax(int maxInclusive) {
        int cap = ExtendedRenderDistance.maxRenderDistance();
        int widened = Math.max(maxInclusive, cap);
        // One-time boot line (Options.<init> runs once): confirms this mixin applied and shows the
        // values. If you never see it, the mixin didn't apply; if widened==maxInclusive, the config
        // read client.maxRenderDistance as the vanilla cap.
        dev.superchunk.SuperChunk.LOGGER.info(
                "[SuperChunk] render-distance slider cap {} -> {} (client.maxRenderDistance={})",
                maxInclusive, widened, cap);
        return widened;
    }
}
