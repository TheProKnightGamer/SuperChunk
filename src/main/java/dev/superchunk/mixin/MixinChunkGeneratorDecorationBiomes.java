package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.superchunk.worldgen.DecorationBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;
import java.util.Set;

/**
 * {@link DecorationBiomes} in {@code applyBiomeDecoration}: notes whether vanilla's collected biome
 * set is complete, and around each top-level feature whether every collected biome lists it.
 */
@Mixin(ChunkGenerator.class)
public abstract class MixinChunkGeneratorDecorationBiomes {

    @SuppressWarnings("unchecked")
    @WrapOperation(method = "applyBiomeDecoration", at = @At(value = "INVOKE",
            target = "Ljava/util/Set;retainAll(Ljava/util/Collection;)Z"), require = 0)
    private boolean superchunk$collectedBiomes(Set<?> biomes, Collection<?> possible, Operation<Boolean> original,
                                               @Local(argsOnly = true) WorldGenLevel level,
                                               @Local(argsOnly = true) ChunkAccess chunk) {
        boolean removed = original.call(biomes, possible);
        if (DecorationBiomes.ENABLED && level instanceof DecorationBiomes.Region region) {
            // retainAll returns whether it removed anything: only an untouched set holds every biome around
            region.superchunk$decorating(chunk.getPos().x, chunk.getPos().z,
                    removed || biomes.isEmpty() ? null : (Set<Holder<Biome>>) biomes);
        }
        return removed;
    }

    @WrapOperation(method = "applyBiomeDecoration", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;placeWithBiomeCheck(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"),
            require = 0)
    private boolean superchunk$featureEverywhere(PlacedFeature feature, WorldGenLevel level, ChunkGenerator generator,
                                                 RandomSource random, BlockPos origin, Operation<Boolean> original) {
        Set<Holder<Biome>> biomes;
        if (!DecorationBiomes.ENABLED || !(level instanceof DecorationBiomes.Region region)
                || (biomes = region.superchunk$collectedBiomes()) == null || !DecorationBiomes.unhooked()) {
            return original.call(feature, level, generator, random, origin);
        }
        Holder<Biome> any = null;
        for (Holder<Biome> biome : biomes) {
            if (!generator.getBiomeGenerationSettings(biome).hasFeature(feature)) {
                any = null;
                break;
            }
            any = biome;
        }
        if (any == null) {
            return original.call(feature, level, generator, random, origin);
        }
        region.superchunk$everywhere(feature, any);
        try {
            return original.call(feature, level, generator, random, origin);
        } finally {
            region.superchunk$everywhere(null, null);
        }
    }

    /** {@link dev.superchunk.diag.StageAb}: decoration CPU time per chunk, switches on against off. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "applyBiomeDecoration", at = @At("HEAD"), require = 0)
    private void superchunk$abBegin(WorldGenLevel level, ChunkAccess chunk,
                                    net.minecraft.world.level.StructureManager structureManager,
                                    org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (dev.superchunk.diag.StageAb.FEATURES) {
            dev.superchunk.diag.StageAb.begin(chunk.getPos().x, chunk.getPos().z);
        }
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "applyBiomeDecoration", at = @At("RETURN"), require = 0)
    private void superchunk$abEnd(WorldGenLevel level, ChunkAccess chunk,
                                  net.minecraft.world.level.StructureManager structureManager,
                                  org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (dev.superchunk.diag.StageAb.FEATURES) {
            dev.superchunk.diag.StageAb.end("features", false);
        }
    }
}
