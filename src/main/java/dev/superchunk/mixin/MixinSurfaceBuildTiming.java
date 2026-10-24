package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.superchunk.worldgen.SurfaceTiming;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Mixin;

/**
 * {@link SurfaceTiming}: times each chunk's {@code buildSurface} when {@code -Dsuperchunk.debug.surfaceTiming=true}. A higher
 * priority than {@code MixinSurfaceSystemFast}'s, so this wrapper is the outer one and times that copy too.
 */
@Mixin(value = SurfaceSystem.class, priority = 1500)
public abstract class MixinSurfaceBuildTiming {

    @WrapMethod(method = "buildSurface", require = 0)
    private void superchunk$timeSurface(RandomState randomState, BiomeManager biomeManager, Registry<Biome> biomes,
                                        boolean useLegacyRandomSource, WorldGenerationContext context, ChunkAccess chunk,
                                        NoiseChunk noiseChunk, SurfaceRules.RuleSource ruleSource, Operation<Void> original) {
        if (!SurfaceTiming.ENABLED) {
            original.call(randomState, biomeManager, biomes, useLegacyRandomSource, context, chunk, noiseChunk, ruleSource);
            return;
        }
        SurfaceTiming.time(SurfaceTiming.half(chunk.getPos().x, chunk.getPos().z),
                () -> original.call(randomState, biomeManager, biomes, useLegacyRandomSource, context, chunk, noiseChunk, ruleSource));
    }
}
