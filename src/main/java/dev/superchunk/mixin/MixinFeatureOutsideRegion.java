package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.superchunk.worldgen.FeatureRegionReads;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Skips a placed feature that reads a chunk outside the decoration region instead of failing the
 * chunk ({@link FeatureRegionReads}). Wraps the call inside vanilla's per-feature {@code try}, so any
 * other exception still reaches vanilla's handler unchanged. The next feature reseeds its random
 * ({@code setFeatureSeed}), so skipping one does not shift the others.
 */
@Mixin(ChunkGenerator.class)
public abstract class MixinFeatureOutsideRegion {

    @WrapOperation(method = "applyBiomeDecoration", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;placeWithBiomeCheck(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"),
            require = 0)
    private boolean superchunk$skipOutsideRegion(PlacedFeature feature, WorldGenLevel level, ChunkGenerator generator,
                                                 RandomSource random, BlockPos origin, Operation<Boolean> original) {
        if (!FeatureRegionReads.SKIP_ENABLED) {
            return original.call(feature, level, generator, random, origin);
        }
        try {
            return original.call(feature, level, generator, random, origin);
        } catch (RuntimeException e) {
            if (!FeatureRegionReads.outsideRegion(e)) {
                throw e;
            }
            FeatureRegionReads.skipped(level, feature, origin);
            return false;
        }
    }
}
