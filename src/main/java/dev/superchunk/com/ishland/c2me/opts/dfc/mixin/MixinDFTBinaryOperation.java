package dev.superchunk.com.ishland.c2me.opts.dfc.mixin;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IArrayCacheCapable;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.util.ArrayCache;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

// Yarn DensityFunctionTypes.BinaryOperation -> Mojmap DensityFunctions$Ap2 (a package-private
// record implementing TwoArgumentSimpleFunction). Targeted by internal name to avoid widening it.
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Ap2")
public class MixinDFTBinaryOperation {

    @Shadow @Final private DensityFunctions.TwoArgumentSimpleFunction.Type type;

    @Shadow @Final private DensityFunction argument1;

    @Shadow @Final private DensityFunction argument2;

    @WrapMethod(method = "fillArray")
    private void wrapFill(double[] densities, DensityFunction.ContextProvider applier, Operation<Void> original) {
        if (this.type == DensityFunctions.TwoArgumentSimpleFunction.Type.ADD) {
            this.argument1.fillArray(densities, applier);
            double[] ds;

            ArrayCache arrayCache = applier instanceof IArrayCacheCapable arrayCacheCapable ? arrayCacheCapable.c2me$getArrayCache() : null;

            if (arrayCache != null) {
                ds = arrayCache.getDoubleArray(densities.length, false);
            } else {
                ds = new double[densities.length];
            }

            this.argument2.fillArray(ds, applier);

            for (int i = 0; i < densities.length; i++) {
                densities[i] += ds[i];
            }

            if (arrayCache != null) {
                arrayCache.recycle(ds);
            }
        } else {
            original.call(densities, applier);
        }
    }

}
