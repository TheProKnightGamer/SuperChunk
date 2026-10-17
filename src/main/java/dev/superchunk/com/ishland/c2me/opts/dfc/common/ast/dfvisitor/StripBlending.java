package dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.dfvisitor;

import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

public class StripBlending implements DensityFunction.Visitor {

    public static final StripBlending INSTANCE = new StripBlending();

    private StripBlending() {
    }

    // Forge 1.20.1 port: instanceof chain instead of Java 21 switch patterns.
    @Override
    public DensityFunction apply(DensityFunction densityFunction) {
        if (densityFunction instanceof NoiseChunk.BlendAlpha || densityFunction instanceof DensityFunctions.BlendAlpha) {
            return DensityFunctions.constant(1.0);
        }
        if (densityFunction instanceof NoiseChunk.BlendOffset || densityFunction instanceof DensityFunctions.BlendOffset) {
            return DensityFunctions.constant(0.0);
        }
        return densityFunction;
    }

}
