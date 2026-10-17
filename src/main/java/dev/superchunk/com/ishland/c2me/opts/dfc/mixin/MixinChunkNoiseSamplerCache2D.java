package dev.superchunk.com.ishland.c2me.opts.dfc.mixin;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IFastCacheLike;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

// Yarn ChunkNoiseSampler.Cache2D -> Mojmap NoiseChunk.Cache2D
// Yarn fields: delegate -> function, lastSamplingColumnPos -> lastPos2D, lastSamplingResult -> lastValue
@Mixin(NoiseChunk.Cache2D.class)
public abstract class MixinChunkNoiseSamplerCache2D implements IFastCacheLike {

    @Shadow private long lastPos2D;

    @Shadow private double lastValue;

    @Mutable
    @Shadow @Final private DensityFunction function;

    @Override
    public double c2me$getCached(int x, int y, int z, EvalType evalType) {
        long l = ChunkPos.asLong(x, z);
        if (this.lastPos2D == l) {
            return this.lastValue;
        } else {
            return Double.longBitsToDouble(CACHE_MISS_NAN_BITS);
        }
    }

    @Override
    public boolean c2me$getCached(double[] res, int[] x, int[] y, int[] z, EvalType evalType) {
        return false;
    }

    @Override
    public void c2me$cache(int x, int y, int z, EvalType evalType, double cached) {
        this.lastPos2D = ChunkPos.asLong(x, z);
        this.lastValue = cached;
    }

    @Override
    public void c2me$cache(double[] res, int[] x, int[] y, int[] z, EvalType evalType) {
        // nop
    }

    @Override
    public DensityFunction c2me$getDelegate() {
        return this.function;
    }

    @Override
    public DensityFunction c2me$withDelegate(DensityFunction delegate) {
        this.function = delegate;
        return this;
    }
}
