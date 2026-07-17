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
     * @reason remove frequent type conversion
     */
    @Overwrite
    public static double wrap(double value) {
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
