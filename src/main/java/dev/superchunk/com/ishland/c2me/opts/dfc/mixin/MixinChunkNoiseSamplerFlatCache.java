package dev.superchunk.com.ishland.c2me.opts.dfc.mixin;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkNoiseSampler;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IFastCacheLike;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

// Yarn ChunkNoiseSampler.FlatCache -> Mojmap NoiseChunk.FlatCache
// Yarn fields: field_36611 -> this$0 (NoiseChunk), cache -> values, delegate -> noiseFiller
// Yarn BiomeCoords.fromBlock -> Mojmap QuartPos.fromBlock; getStartBiomeX/Z -> firstNoiseX/firstNoiseZ
@Mixin(NoiseChunk.FlatCache.class)
public abstract class MixinChunkNoiseSamplerFlatCache implements IFastCacheLike {

    @Shadow @Final NoiseChunk this$0;

    @Shadow @Final double[][] values;

    @Mutable
    @Shadow @Final private DensityFunction noiseFiller;

    @Override
    public double c2me$getCached(int x, int y, int z, EvalType evalType) {
        int i = QuartPos.fromBlock(x);
        int j = QuartPos.fromBlock(z);
        int k = i - ((IChunkNoiseSampler) this.this$0).getStartBiomeX();
        int l = j - ((IChunkNoiseSampler) this.this$0).getStartBiomeZ();
        int m = this.values.length;
        if (k >= 0 && l >= 0 && k < m && l < m) {
            return this.values[k][l];
        } else {
            return Double.longBitsToDouble(CACHE_MISS_NAN_BITS);
        }
    }

    @Override
    public boolean c2me$getCached(double[] res, int[] x, int[] y, int[] z, EvalType evalType) {
        for (int i = 0; i < res.length; i ++) {
            int i1 = QuartPos.fromBlock(x[i]);
            int j1 = QuartPos.fromBlock(z[i]);
            int k = i1 - ((IChunkNoiseSampler) this.this$0).getStartBiomeX();
            int l = j1 - ((IChunkNoiseSampler) this.this$0).getStartBiomeZ();
            int m = this.values.length;
            if (k >= 0 && l >= 0 && k < m && l < m) {
                res[i] = this.values[k][l];
            } else {
                return false; // partial hit possible
            }
        }
        return true;
    }

    @Override
    public void c2me$cache(int x, int y, int z, EvalType evalType, double cached) {
        // nop
    }

    @Override
    public void c2me$cache(double[] res, int[] x, int[] y, int[] z, EvalType evalType) {
        // nop
    }

    @Override
    public DensityFunction c2me$getDelegate() {
        return this.noiseFiller;
    }

    @Override
    public DensityFunction c2me$withDelegate(DensityFunction delegate) {
        this.noiseFiller = delegate;
        return this;
    }
}
