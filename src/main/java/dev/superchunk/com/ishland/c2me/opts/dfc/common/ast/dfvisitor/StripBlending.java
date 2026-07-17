package dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.dfvisitor;

import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

public class StripBlending implements DensityFunction.Visitor {

    public static final StripBlending INSTANCE = new StripBlending();

    private StripBlending() {
    }

    @Override
    public DensityFunction apply(DensityFunction densityFunction) {
        return switch (densityFunction) {
            case NoiseChunk.BlendAlpha f -> DensityFunctions.constant(1.0);
            case NoiseChunk.BlendOffset f -> DensityFunctions.constant(0.0);
            case DensityFunctions.BlendAlpha f -> DensityFunctions.constant(1.0);
            case DensityFunctions.BlendOffset f -> DensityFunctions.constant(0.0);
            default -> densityFunction;
        };
    }

}
