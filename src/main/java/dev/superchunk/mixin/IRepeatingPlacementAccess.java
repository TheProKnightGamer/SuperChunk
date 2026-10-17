package dev.superchunk.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.RepeatingPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code RepeatingPlacement.count} (protected) so the imperative placement fast path can
 * loop {@code count} times without the {@code IntStream.range(...).mapToObj(p -> pos)} pipeline.
 * Covers CountPlacement, NoiseBasedCountPlacement, NoiseThresholdCountPlacement.
 * See {@code MixinPlacedFeatureImperative}.
 */
@Mixin(RepeatingPlacement.class)
public interface IRepeatingPlacementAccess {
    @Invoker("count")
    int superchunk$count(RandomSource src, BlockPos pos);
}
