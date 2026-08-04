package dev.superchunk.mixin;

import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code HeightRangePlacement.height} so the imperative placement fast path can sample it
 * directly (one draw, identical order) without allocating {@code Stream.of(pos.atY(...))}.
 * See {@code MixinPlacedFeatureImperative}.
 */
@Mixin(HeightRangePlacement.class)
public interface IHeightRangePlacementAccess {
    @Accessor("height")
    HeightProvider superchunk$height();
}
