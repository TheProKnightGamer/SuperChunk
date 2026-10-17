package dev.superchunk.mixin;

import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code RandomOffsetPlacement.xzSpread}/{@code ySpread} so the imperative placement fast
 * path can sample them in the exact vanilla order (xz, y, xz) without allocating a {@code Stream}.
 * See {@code MixinPlacedFeatureImperative}.
 */
@Mixin(RandomOffsetPlacement.class)
public interface IRandomOffsetPlacementAccess {
    @Accessor("xzSpread")
    IntProvider superchunk$xzSpread();

    @Accessor("ySpread")
    IntProvider superchunk$ySpread();
}
