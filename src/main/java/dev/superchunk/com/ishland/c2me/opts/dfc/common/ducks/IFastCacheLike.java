package dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import net.minecraft.world.level.levelgen.DensityFunction;

public interface IFastCacheLike extends DensityFunction {

    public static final long CACHE_MISS_NAN_BITS = 0x7ffddb972d486a4fL;

    double c2me$getCached(int x, int y, int z, EvalType evalType);

    boolean c2me$getCached(double[] res, int[] x, int[] y, int[] z, EvalType evalType);

    /**
     * SuperChunk: fills {@code res} with exactly what calling {@link #c2me$getCached(int, int, int, EvalType)}
     * for each element would return, when none of those calls can miss or throw; {@code false}
     * (res untouched or partly written) otherwise, and the caller must go element by element.
     * Lets generated code read a cache in bulk where it used to read it per element
     * ({@code MulNode} with a constant factor). Only interpolators implement it.
     */
    default boolean c2me$getCachedPointwise(double[] res, int[] x, int[] y, int[] z, EvalType evalType) {
        return false;
    }

    void c2me$cache(int x, int y, int z, EvalType evalType, double cached);

    void c2me$cache(double[] res, int[] x, int[] y, int[] z, EvalType evalType);

    DensityFunction c2me$getDelegate();

    // called by generated code
    DensityFunction c2me$withDelegate(DensityFunction delegate);

}
