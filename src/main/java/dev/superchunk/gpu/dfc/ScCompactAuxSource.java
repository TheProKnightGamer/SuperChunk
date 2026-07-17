package dev.superchunk.gpu.dfc;

/**
 * SuperChunk compact-ids (Stage 5): implemented by the C2ME
 * {@code Aquifer.NoiseBasedAquifer} mixin ({@code MixinAquiferSamplerImpl}).
 * Packages the aquifer side of the per-chunk decide-kernel aux upload — the
 * EXACT mode-A contract the Stage-3 census already proved bit-exact
 * ({@code scBuildAquiferAccumulator} pattern): per-cell FluidStatus
 * (fluidLevel/fluidTypeId) for all aquifer cells, the CPU-preloaded location
 * cache, grid geometry, probed global-picker params, the authoritative
 * FLOWING_UPDATE_SIMULARITY constant, and the (compiled) barrierNoise DF used
 * to resolve the chunk's per-dimension route.
 *
 * <p>Called WORKER-side at the batch-prefetch seam ({@code GpuBatchPrefetch}),
 * strictly before this chunk's noise fill consumes the same aquifer instance,
 * so the lazy FluidStatus computation here is race-free and simply warms the
 * same caches the fill would.
 *
 * <p>Dimensions without a {@code NoiseBasedAquifer} (nether/end/disabled
 * aquifers) never implement this on their aquifer object, which is exactly the
 * clean skip-the-decide-chain fallback.
 */
public interface ScCompactAuxSource {

    /** Builds the aquifer-side aux snapshot for the CURRENT chunk, or {@code null} on any failure. */
    CompactIds.AquiferAux superchunk$compactAquiferAux();
}
