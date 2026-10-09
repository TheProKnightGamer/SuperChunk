package dev.superchunk.mixin;

import dev.superchunk.worldgen.ClimateColumns;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Tags the climate sampler biome fill uses with the router's per-column mask
 * ({@link ClimateColumns}): its functions are the router's, wrapped in this chunk's caches.
 */
@Mixin(NoiseChunk.class)
public abstract class MixinNoiseChunkClimateColumns {

    @Inject(method = "cachedClimateSampler(Lnet/minecraft/world/level/levelgen/NoiseRouter;Ljava/util/List;)Lnet/minecraft/world/level/biome/Climate$Sampler;",
            at = @At("RETURN"))
    private void superchunk$tagColumns(NoiseRouter router, List<Climate.ParameterPoint> spawnTarget,
                                       CallbackInfoReturnable<Climate.Sampler> cir) {
        ((ClimateColumns.Tagged) (Object) cir.getReturnValue()).superchunk$setColumnMask(ClimateColumns.maskFor(router));
    }
}
