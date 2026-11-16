package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.superchunk.worldgen.DecorationBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@link DecorationBiomes} in the biome filter: for a feature every biome around the decorated
 * chunk lists, the filter gets one of those biomes without the lookup at the block.
 */
@Mixin(BiomeFilter.class)
public abstract class MixinBiomeFilterArea {

    @WrapOperation(method = "shouldPlace", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/WorldGenLevel;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"),
            require = 0)
    private Holder<Biome> superchunk$areaBiome(WorldGenLevel level, BlockPos pos, Operation<Holder<Biome>> original,
                                               @Local PlacedFeature feature) {
        if (DecorationBiomes.ENABLED && level instanceof DecorationBiomes.Region region
                && !(dev.superchunk.diag.StageAb.DECORATION_BIOMES && dev.superchunk.diag.StageAb.off())) {
            Holder<Biome> any = region.superchunk$everywhereBiome(feature, pos.getX(), pos.getZ());
            if (any != null) {
                if (DecorationBiomes.VERIFY) {
                    DecorationBiomes.verify(region.superchunk$collectedBiomes(), original.call(level, pos),
                            pos.getX(), pos.getY(), pos.getZ());
                }
                return any;
            }
        }
        return original.call(level, pos);
    }
}
