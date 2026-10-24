package dev.superchunk.mixin;

import net.minecraft.world.level.levelgen.SurfaceRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SurfaceRules.Context.class)
public interface ISurfaceRulesContextAccess {
    @Accessor("blockX")
    int superchunk$blockX();

    @Accessor("blockY")
    int superchunk$blockY();

    @Accessor("blockZ")
    int superchunk$blockZ();

    @Invoker("getMinSurfaceLevel")
    int superchunk$getMinSurfaceLevel();

    @Invoker("updateY")
    void superchunk$updateY(int stoneDepthAbove, int stoneDepthBelow, int waterHeight, int blockX, int blockY, int blockZ);

    @Invoker("updateXZ")
    void superchunk$updateXZ(int blockX, int blockZ);

    @Invoker("<init>")
    static SurfaceRules.Context superchunk$create(net.minecraft.world.level.levelgen.SurfaceSystem system,
                                                  net.minecraft.world.level.levelgen.RandomState randomState,
                                                  net.minecraft.world.level.chunk.ChunkAccess chunk,
                                                  net.minecraft.world.level.levelgen.NoiseChunk noiseChunk,
                                                  java.util.function.Function<net.minecraft.core.BlockPos,
                                                          net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomeGetter,
                                                  net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomes,
                                                  net.minecraft.world.level.levelgen.WorldGenerationContext context) {
        throw new AssertionError();
    }
}
