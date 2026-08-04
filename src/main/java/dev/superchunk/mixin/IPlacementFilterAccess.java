package dev.superchunk.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementFilter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code PlacementFilter.shouldPlace} (protected) so the imperative placement fast path
 * can decide a filter's 0/1 output without allocating its {@code Stream.of(pos)}/{@code Stream.of()}.
 * Covers all filter subclasses (BiomeFilter, BlockPredicateFilter, RarityFilter, SurfaceWaterDepth,
 * SurfaceRelativeThreshold). See {@code MixinPlacedFeatureImperative}.
 */
@Mixin(PlacementFilter.class)
public interface IPlacementFilterAccess {
    @Invoker("shouldPlace")
    boolean superchunk$shouldPlace(PlacementContext ctx, RandomSource src, BlockPos pos);
}
