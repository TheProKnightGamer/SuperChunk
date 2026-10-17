package dev.superchunk.com.ishland.c2me.opts.dfc.common.vif;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IArrayCacheCapable;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.util.ArrayCache;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.Objects;

public class EachApplierVanillaInterface implements DensityFunction.ContextProvider, IArrayCacheCapable {

    private final int[] x;
    private final int[] y;
    private final int[] z;
    private final EvalType type;
    private final ArrayCache cache;

    public EachApplierVanillaInterface(int[] x, int[] y, int[] z, EvalType type) {
        this(x, y, z, type, new ArrayCache());
    }

    public EachApplierVanillaInterface(int[] x, int[] y, int[] z, EvalType type, ArrayCache cache) {
        this.x = Objects.requireNonNull(x);
        this.y = Objects.requireNonNull(y);
        this.z = Objects.requireNonNull(z);
        this.type = Objects.requireNonNull(type);
        this.cache = Objects.requireNonNull(cache);
    }

    @Override
    public DensityFunction.FunctionContext forIndex(int index) {
        return new NoisePosVanillaInterface(x[index], y[index], z[index], type);
    }

    @Override
    public void fillAllDirectly(double[] densities, DensityFunction densityFunction) {
        for (int i = 0; i < x.length; i++) {
            densities[i] = densityFunction.compute(this.forIndex(i));
        }
    }

    public int[] getX() {
        return x;
    }

    public int[] getY() {
        return y;
    }

    public int[] getZ() {
        return z;
    }

    public EvalType getType() {
        return type;
    }

    @Override
    public ArrayCache c2me$getArrayCache() {
        return this.cache;
    }
}
