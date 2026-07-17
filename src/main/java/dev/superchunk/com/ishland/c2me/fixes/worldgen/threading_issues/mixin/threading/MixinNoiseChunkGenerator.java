package dev.superchunk.com.ishland.c2me.fixes.worldgen.threading_issues.mixin.threading;

import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class MixinNoiseChunkGenerator extends ChunkGenerator {
    public MixinNoiseChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

//    @Dynamic
//    @Redirect(method = "*", at = @At(value = "NEW", target = "net/minecraft/world/gen/StructureWeightSampler"), require = 4)
//    private static StructureWeightSampler redirectStructureWeightSamplers(StructureAccessor structureAccessor, Chunk chunk) {
//        return new ThreadLocalStructureWeightSampler(structureAccessor, chunk);
//    }

//    @Inject(method = "<init>(Lnet/minecraft/util/registry/Registry;Lnet/minecraft/world/biome/source/BiomeSource;Lnet/minecraft/world/biome/source/BiomeSource;JLjava/util/function/Supplier;)V", at = @At(value = "RETURN"))
//    private void onInit(CallbackInfo info) {
//        ((IChunkGenerator) this).invokeGenerateStrongholdPositions();
//        System.out.println("Stronghold positions initialized");
//    }

}
