package dev.superchunk.com.ishland.c2me.base.mixin.access;

import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlendedNoise.class)
public interface IInterpolatedNoiseSampler {

    @Accessor("minLimitNoise")
    PerlinNoise getLowerInterpolatedNoise();

    @Accessor("maxLimitNoise")
    PerlinNoise getUpperInterpolatedNoise();

    @Accessor("mainNoise")
    PerlinNoise getInterpolationNoise();

    @Accessor("xzMultiplier")
    double getScaledXzScale();

    @Accessor("yMultiplier")
    double getScaledYScale();

    @Accessor
    double getXzFactor();

    @Accessor
    double getYFactor();

    @Accessor
    double getSmearScaleMultiplier();

    @Accessor
    double getMaxValue();

    @Accessor
    double getXzScale();

    @Accessor
    double getYScale();

}
