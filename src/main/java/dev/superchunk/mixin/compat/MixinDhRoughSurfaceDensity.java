package dev.superchunk.mixin.compat;

import dev.superchunk.compat.DhRoughSurface;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Distant Horizons' rough surface generator computes each column's height-independent terrain once.
 *
 * <p>For every LOD below full detail (nearly all of a large render distance), DH 3.3.3's
 * {@code DhRoughSurfaceGenerator} finds each sampled column's surface by evaluating the level's
 * final density at single points: down from the top in steps of 8, then a binary search, about
 * 30 evaluations per column. Each one recomputed continentalness, erosion, ridges and the terrain
 * splines. They now go through {@link DhRoughSurface#evaluate}, which returns the same value from
 * a per-thread copy that keeps those parts for the last column.
 *
 * <p>Applied only when DH's method hashes to the version it was checked against
 * ({@link dev.superchunk.compat.DhFastPaths}). Kill switch {@code -Dsuperchunk.dh.fastPaths=false}.
 */
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.worldGeneration.DhRoughSurfaceGenerator_neoforge", remap = false)
public abstract class MixinDhRoughSurfaceDensity {

    @Redirect(method = "isNoiseSolidAtBlockPos", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/DensityFunction;compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D"))
    private static double superchunk$columnMemo(DensityFunction density, DensityFunction.FunctionContext context) {
        return DhRoughSurface.evaluate(density, context);
    }
}
