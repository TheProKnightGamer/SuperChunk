package dev.superchunk.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.superchunk.worldgen.BiomeCellLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Function;

/**
 * The surface rules' biome getter ({@code biomeManager::getBiome}) asks the biome cell path
 * directly first ({@link BiomeCellLookup}), skipping {@code getBiome}'s per-call wrapper allocation.
 * The biomes are the same: the cell path is what {@code getBiome} itself runs whenever it applies.
 */
@Mixin(SurfaceSystem.class)
public abstract class MixinSurfaceSystemCellBiome {

    @ModifyArg(method = "buildSurface", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/SurfaceRules$Context;<init>(Lnet/minecraft/world/level/levelgen/SurfaceSystem;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;Ljava/util/function/Function;Lnet/minecraft/core/Registry;Lnet/minecraft/world/level/levelgen/WorldGenerationContext;)V"),
            index = 4, require = 0)
    private Function<BlockPos, Holder<Biome>> superchunk$cellBiomes(Function<BlockPos, Holder<Biome>> getter,
                                                                    @Local(argsOnly = true) BiomeManager biomeManager) {
        return BiomeCellLookup.of(biomeManager, getter);
    }
}
