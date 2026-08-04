package dev.superchunk.mixin;

import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code HeightmapPlacement.heightmap} so the imperative placement fast path can compute
 * the surface position without allocating {@code Stream.of(pos)}/{@code Stream.of()}.
 * See {@code MixinPlacedFeatureImperative}.
 */
@Mixin(HeightmapPlacement.class)
public interface IHeightmapPlacementAccess {
    @Accessor("heightmap")
    Heightmap.Types superchunk$heightmap();
}
