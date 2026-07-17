package dev.superchunk.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.superchunk.gpu.OpenCLBackend;
import dev.superchunk.gpu.dfc.BiomeClimateCache;
import dev.superchunk.gpu.dfc.GpuFillStats;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;

/**
 * SuperChunk GPU — biome-climate offload lifecycle.
 *
 * <p>{@code ChunkAccess.fillBiomesFromNoise(resolver, sampler)} is the chunk-level entry
 * for biome population (it drives every section's 4&times;4&times;4 quart loop). We scope
 * the per-thread {@link BiomeClimateCache} to exactly this call: {@link BiomeClimateCache
 * #beginChunk} (eager fused dispatch over the chunk's quart lattice) before the body, and
 * {@link BiomeClimateCache#endChunk} (clear / discard any in-flight dispatch) after it.
 * The end hook runs in a {@code finally} so an exception thrown inside the vanilla body
 * (e.g. a failed climate sample) cannot leak an armed eager async GPU dispatch into the
 * persistent worker ThreadLocal, where it would later be discarded against a stale
 * {@code cl_event} in the next world (cross-world use-after-free). The actual per-sample
 * serving happens in {@code MixinClimateSampler}. Pure no-op when the offload is off or no
 * fused biome group matches the sampler.
 */
@Mixin(ChunkAccess.class)
public abstract class MixinChunkAccessBiome {

    @WrapMethod(
            method = "fillBiomesFromNoise(Lnet/minecraft/world/level/biome/BiomeResolver;Lnet/minecraft/world/level/biome/Climate$Sampler;)V")
    private void superchunk$wrapBiomeFill(BiomeResolver resolver, Climate.Sampler sampler, Operation<Void> original) {
        // The resolver rides along ONLY for -Dsuperchunk.biome.depthFlipVerify (it is usually
        // the dimension's MultiNoiseBiomeSource, whose RTree resolves actual biome flips).
        BiomeClimateCache.beginChunk((ChunkAccess) (Object) this, sampler, resolver);
        // Time the whole biome stage whenever the GPU backend is up, in BOTH offloadBiome
        // ON and OFF, so the ON-vs-OFF delta isolates the CPU climate work the GPU removed.
        long t0 = OpenCLBackend.isAvailable() ? System.nanoTime() : 0L;
        try {
            original.call(resolver, sampler);
        } finally {
            // Safety-critical cleanup FIRST: endChunk() disarms any in-flight async
            // dispatch. Recording the stat is best-effort and must never be able to
            // pre-empt the disarm (which would re-leak the armed Ctx.pending).
            BiomeClimateCache.endChunk();
            if (t0 != 0L) {
                GpuFillStats.recordBiomeStageTime(System.nanoTime() - t0);
            }
        }
    }
}
