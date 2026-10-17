package dev.superchunk.com.ishland.c2me.opts.dfc.common.ast;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.vif.EachApplierVanillaInterface;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;

public enum EvalType {
    NORMAL, INTERPOLATION;

    public static EvalType from(DensityFunction.FunctionContext pos) {
        return pos instanceof NoiseChunk ? INTERPOLATION : NORMAL;
    }

    public static EvalType from(DensityFunction.ContextProvider applier) {
        if (applier instanceof EachApplierVanillaInterface vif) {
            return vif.getType();
        }
        return applier instanceof NoiseChunk ? INTERPOLATION : NORMAL;
    }
}
