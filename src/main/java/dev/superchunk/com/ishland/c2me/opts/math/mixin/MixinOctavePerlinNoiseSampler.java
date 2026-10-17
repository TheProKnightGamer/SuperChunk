package dev.superchunk.com.ishland.c2me.opts.math.mixin;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PerlinNoise.class)
public class MixinOctavePerlinNoiseSampler {

    @Shadow @Final private double lowestFreqInputFactor;

    @Shadow @Final private double lowestFreqValueFactor;

    @Shadow @Final private ImprovedNoise[] noiseLevels;

    @Shadow @Final private DoubleList amplitudes;

    /**
     * Kill switch for the wrap fast path below ({@code -Dsuperchunk.worldgen.perlinWrapFastPath
     * =false} restores the verbatim expression). {@code static final}, so the JIT folds the test
     * away entirely — it costs nothing on a method this hot, and it makes the A/B one flag wide.
     */
    @Unique
    private static final boolean superchunk$WRAP_FAST_PATH =
            !"false".equalsIgnoreCase(System.getProperty("superchunk.worldgen.perlinWrapFastPath", "true"));

    @Unique
    private int octaveSamplersCount = 0;

    @Unique
    private double[] amplitudesArray = null;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        this.octaveSamplersCount = this.noiseLevels.length;
        this.amplitudesArray = this.amplitudes.toDoubleArray();
    }

    /**
     * @author ishland
     * @reason remove frequent type conversion; SuperChunk: skip the arithmetic entirely in the
     *         range worldgen actually uses
     */
    @Overwrite
    public static double wrap(double value) {
        // FAST PATH, bit-exact (the same identity SuperChunk applies in noise.cl's perlin_wrap).
        // 3.3554432E7 is exactly 2^25, so the division is an exact power-of-two scaling: for
        // |value| < 2^23 the quotient lies in (-0.25, 0.25), the sum with 0.5 lies in (0.25, 0.75),
        // and no rounding can carry it out of [0, 1) — so floor(...) is 0.0 and the expression
        // collapses to `value - 0.0 * 2^25` == `value - 0.0` == `value`, bit-for-bit, for every
        // finite double including -0.0. (2^24 would NOT be safe: at the top of that range the sum
        // is 1 - 2^-54, which ties-to-even rounds UP to 1.0.) NaN fails the compare and takes the
        // verbatim expression, so the identity holds unconditionally.
        //
        // Worldgen feeds coordinate * inputFactor, orders of magnitude below the bound, so this is
        // the branch every octave of every sample takes: it removes a divide, an add, a floor, a
        // multiply and a subtract from each of the three wrap() calls per octave.
        if (superchunk$WRAP_FAST_PATH && Math.abs(value) < 8388608.0) {
            return value;
        }
        return value - Math.floor(value / 3.3554432E7 + 0.5) * 3.3554432E7;
    }

    /**
     * @author ishland
     * @reason optimize for common cases
     */
    @Overwrite
    public double getValue(double x, double y, double z) {
        double d = 0.0;
        double e = this.lowestFreqInputFactor;
        double f = this.lowestFreqValueFactor;

        for(int i = 0; i < this.octaveSamplersCount; ++i) {
            ImprovedNoise perlinNoiseSampler = this.noiseLevels[i];
            if (perlinNoiseSampler != null) {
                @SuppressWarnings("deprecation")
                double g = perlinNoiseSampler.noise(
                        wrap(x * e), wrap(y * e), wrap(z * e), 0.0, 0.0
                );
                d += this.amplitudesArray[i] * g * f;
            }

            e *= 2.0;
            f /= 2.0;
        }

        return d;
    }

}
