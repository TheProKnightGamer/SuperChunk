package dev.superchunk.com.ishland.c2me.base.mixin.access;

import it.unimi.dsi.fastutil.doubles.Double2DoubleFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(DensityFunctions.WeirdScaledSampler.RarityValueMapper.class)
public interface IDensityFunctionTypesWeirdScaledSamplerRarityValueMapper {

    @Accessor("mapper")
    Double2DoubleFunction getScaleFunction();

}
