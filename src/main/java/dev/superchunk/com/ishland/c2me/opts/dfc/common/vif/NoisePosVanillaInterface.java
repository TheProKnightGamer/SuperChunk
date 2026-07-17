package dev.superchunk.com.ishland.c2me.opts.dfc.common.vif;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IArrayCacheCapable;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.util.ArrayCache;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.Objects;

public class NoisePosVanillaInterface implements DensityFunction.FunctionContext {

    private final int x;
    private final int y;
    private final int z;
    private final EvalType type;

    public NoisePosVanillaInterface(int x, int y, int z, EvalType type) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.type = Objects.requireNonNull(type);
    }

    @Override
    public int blockX() {
        return x;
    }

    @Override
    public int blockY() {
        return y;
    }

    @Override
    public int blockZ() {
        return z;
    }

    public EvalType getType() {
        return type;
    }

}
