package dev.superchunk.com.ishland.c2me.opts.dfc.mixin;

import dev.superchunk.com.ishland.c2me.base.mixin.access.IChunkNoiseSampler;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ducks.IFastCacheLike;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.vif.NoisePosVanillaInterface;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

// Yarn ChunkNoiseSampler.CellCache -> Mojmap NoiseChunk.CacheAllInCell
// Yarn fields: field_36602 -> this$0 (NoiseChunk), cache -> values, delegate -> noiseFiller
// Yarn sample -> Mojmap compute; getHorizontalCellBlockCount -> cellWidth, getVerticalCellBlockCount -> cellHeight
@Mixin(targets = "net/minecraft/world/level/levelgen/NoiseChunk$CacheAllInCell")
public abstract class MixinChunkNoiseSamplerCellCache implements IFastCacheLike {

    @Shadow
    @Final
    NoiseChunk this$0;

    @Shadow
    @Final
    double[] values;

    @Mutable
    @Shadow
    @Final
    DensityFunction noiseFiller;

    @WrapMethod(method = "compute")
    private double wrapSample(DensityFunction.FunctionContext pos, Operation<Double> original) {
        if (pos instanceof NoiseChunk) {
            return original.call(pos);
        }
        if (pos instanceof NoisePosVanillaInterface vif && vif.getType() == EvalType.INTERPOLATION) {
            boolean isInInterpolationLoop = ((IChunkNoiseSampler) this.this$0).getIsInInterpolationLoop();
            if (!isInInterpolationLoop) {
                return original.call(pos);
            }
            int startBlockX = ((IChunkNoiseSampler) this.this$0).getStartBlockX();
            int startBlockY = ((IChunkNoiseSampler) this.this$0).getStartBlockY();
            int startBlockZ = ((IChunkNoiseSampler) this.this$0).getStartBlockZ();
            int horizontalCellBlockCount = ((IChunkNoiseSampler) this.this$0).getHorizontalCellBlockCount();
            int verticalCellBlockCount = ((IChunkNoiseSampler) this.this$0).getVerticalCellBlockCount();
            int cellBlockX = pos.blockX() - startBlockX;
            int cellBlockY = pos.blockY() - startBlockY;
            int cellBlockZ = pos.blockZ() - startBlockZ;
            return cellBlockX >= 0
                    && cellBlockY >= 0
                    && cellBlockZ >= 0
                    && cellBlockX < horizontalCellBlockCount
                    && cellBlockY < verticalCellBlockCount
                    && cellBlockZ < horizontalCellBlockCount
                    ? this.values[((verticalCellBlockCount - 1 - cellBlockY) * horizontalCellBlockCount + cellBlockX)
                    * horizontalCellBlockCount
                    + cellBlockZ]
                    : this.noiseFiller.compute(pos);
        }
        return original.call(pos);
    }

    @Override
    public double c2me$getCached(int x, int y, int z, EvalType evalType) {
        if (evalType == EvalType.INTERPOLATION) {
            boolean isInInterpolationLoop = ((IChunkNoiseSampler) this.this$0).getIsInInterpolationLoop();
            if (isInInterpolationLoop) {
                int startBlockX = ((IChunkNoiseSampler) this.this$0).getStartBlockX();
                int startBlockY = ((IChunkNoiseSampler) this.this$0).getStartBlockY();
                int startBlockZ = ((IChunkNoiseSampler) this.this$0).getStartBlockZ();
                int horizontalCellBlockCount = ((IChunkNoiseSampler) this.this$0).getHorizontalCellBlockCount();
                int verticalCellBlockCount = ((IChunkNoiseSampler) this.this$0).getVerticalCellBlockCount();
                int cellBlockX = x - startBlockX;
                int cellBlockY = y - startBlockY;
                int cellBlockZ = z - startBlockZ;
                if (cellBlockX >= 0 &&
                        cellBlockY >= 0 &&
                        cellBlockZ >= 0 &&
                        cellBlockX < horizontalCellBlockCount &&
                        cellBlockY < verticalCellBlockCount &&
                        cellBlockZ < horizontalCellBlockCount) {
                    return this.values[((verticalCellBlockCount - 1 - cellBlockY) * horizontalCellBlockCount + cellBlockX)
                            * horizontalCellBlockCount
                            + cellBlockZ];
                }
            }
        }

        return Double.longBitsToDouble(CACHE_MISS_NAN_BITS);
    }

    @Override
    public boolean c2me$getCached(double[] res, int[] x, int[] y, int[] z, EvalType evalType) {
        if (evalType == EvalType.INTERPOLATION) {
            boolean isInInterpolationLoop = ((IChunkNoiseSampler) this.this$0).getIsInInterpolationLoop();
            if (isInInterpolationLoop) {
                int startBlockX = ((IChunkNoiseSampler) this.this$0).getStartBlockX();
                int startBlockY = ((IChunkNoiseSampler) this.this$0).getStartBlockY();
                int startBlockZ = ((IChunkNoiseSampler) this.this$0).getStartBlockZ();
                int horizontalCellBlockCount = ((IChunkNoiseSampler) this.this$0).getHorizontalCellBlockCount();
                int verticalCellBlockCount = ((IChunkNoiseSampler) this.this$0).getVerticalCellBlockCount();
                for (int i = 0; i < res.length; i++) {
                    int cellBlockX = x[i] - startBlockX;
                    int cellBlockY = y[i] - startBlockY;
                    int cellBlockZ = z[i] - startBlockZ;
                    if (cellBlockX >= 0 &&
                            cellBlockY >= 0 &&
                            cellBlockZ >= 0 &&
                            cellBlockX < horizontalCellBlockCount &&
                            cellBlockY < verticalCellBlockCount &&
                            cellBlockZ < horizontalCellBlockCount) {
                        res[i] = this.values[((verticalCellBlockCount - 1 - cellBlockY) * horizontalCellBlockCount + cellBlockX) * horizontalCellBlockCount + cellBlockZ];
                    } else {
                        return false; // partial hit possible
                    }
                }
                return true; // full in-cell hit: res is fully populated, mirror FlatCache
            }
        }

        return false;
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
