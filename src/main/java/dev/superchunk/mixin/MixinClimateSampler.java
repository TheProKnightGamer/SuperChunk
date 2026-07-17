package dev.superchunk.mixin;

import dev.superchunk.gpu.dfc.BiomeClimateCache;
import dev.superchunk.gpu.dfc.GpuFillStats;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SuperChunk GPU — biome-climate offload serve seam.
 *
 * <p>Intercepts {@code Climate.Sampler.sample(x,y,z)} at HEAD. When this worker thread is
 * armed for THIS sampler (by {@code MixinChunkAccessBiome} / {@link BiomeClimateCache#beginChunk})
 * and the quart point is inside the cached chunk lattice, the three UNCACHED climate DFs —
 * temperature, vegetation, depth — are served from the fused GPU grid instead of being
 * recomputed single-point on the CPU. The other three (continentalness/erosion/weirdness,
 * flatCache) are computed EXACTLY as vanilla via the same {@link DensityFunction.SinglePointContext},
 * and the same {@link Climate#target} is returned — so the {@code TargetPoint} (and thus the
 * selected biome) is bit-for-bit identical to vanilla. Any non-armed sampler, out-of-lattice
 * point, or GPU failure falls through to the unchanged vanilla {@code sample()}.
 *
 * <p>Exception: under RELAXED PARITY ({@code -Dsuperchunk.biome.offloadDepth=true}, default
 * off) depth is GPU-served with ~1e-7 spline float drift, so the TargetPoint MAY differ at
 * {@code Climate.quantizeCoord} boundaries; {@code -Dsuperchunk.biome.depthFlipVerify=true}
 * measures the ACTUAL biome-selection flips here at the live serve site (see
 * {@link #superchunk$measureDepthFlip}).
 */
@Mixin(Climate.Sampler.class)
public abstract class MixinClimateSampler {

    /** Real-DF correctness check: compare each GPU-served sample to vanilla compute(). Off by default. */
    private static final boolean superchunk$verify = Boolean.getBoolean("superchunk.biome.verify");

    @Inject(
            method = "sample(III)Lnet/minecraft/world/level/biome/Climate$TargetPoint;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void superchunk$sampleFromGpu(int x, int y, int z, CallbackInfoReturnable<Climate.TargetPoint> cir) {
        if (!BiomeClimateCache.isEnabled()) {
            return;   // offload OFF (default) -> skip even the ThreadLocal fetch; unchanged vanilla path
        }
        double[] tvd = BiomeClimateCache.serve(this, x, y, z);
        if (tvd == null) {
            return;   // not armed / outside lattice / GPU failed -> unchanged vanilla path
        }
        // Match vanilla Climate.Sampler.sample exactly: one SinglePointContext at the
        // quart-block position, the three flatCache DFs computed on the CPU as before, and
        // temperature/vegetation/depth taken from the GPU grid (bit-identical to compute()).
        Climate.Sampler self = (Climate.Sampler) (Object) this;
        int bx = QuartPos.toBlock(x);
        int by = QuartPos.toBlock(y);
        int bz = QuartPos.toBlock(z);
        DensityFunction.SinglePointContext ctx = new DensityFunction.SinglePointContext(bx, by, bz);
        // A slot the cache did NOT GPU-serve (not float-exact, e.g. depth's spline) is NaN ->
        // compute it on the CPU exactly as vanilla. GPU-served slots are float-bit-identical
        // on the fp64 chain; on the fp32 chain (gpu.climateFp32) superchunk$served applies
        // the QUANTIZATION GUARD (near-boundary/non-finite samples recompute on the CPU) so
        // the resulting TargetPoint is bit-identical either way.
        float temperature = superchunk$served(tvd[0], self.temperature(), ctx);
        float vegetation = superchunk$served(tvd[1], self.humidity(), ctx);
        float depth = superchunk$served(tvd[2], self.depth(), ctx);
        // Real-DF correctness check (gated): for each GPU-SERVED slot, compare against the
        // ACTUAL router climate DF's vanilla compute() on the biome-deciding float cast AND
        // on the quantized coordinate the biome selection actually consumes (the SERVED
        // float, post-guard — must NEVER mismatch; raw float/double drift is expected and
        // benign under the fp32 chain).
        if (superchunk$verify) {
            if (!Double.isNaN(tvd[0])) superchunk$verifyOne(self.temperature(), tvd[0], temperature, ctx);
            if (!Double.isNaN(tvd[1])) superchunk$verifyOne(self.humidity(), tvd[1], vegetation, ctx);
            if (!Double.isNaN(tvd[2])) superchunk$verifyOne(self.depth(), tvd[2], depth, ctx);
        }
        // The three flatCache DFs, computed on the CPU exactly as vanilla (same order as the
        // former inline Climate.target args); hoisted so the flip measurement below can share
        // the IDENTICAL values between the GPU-served and the vanilla TargetPoint.
        float continentalness = (float) self.continentalness().compute(ctx);   // CPU flatCache, unchanged
        float erosion = (float) self.erosion().compute(ctx);                   // CPU flatCache, unchanged
        float weirdness = (float) self.weirdness().compute(ctx);               // CPU flatCache, unchanged
        Climate.TargetPoint gpuTarget =
                Climate.target(temperature, vegetation, continentalness, erosion, depth, weirdness);
        // Depth biome-FLIP measurement (gated, default OFF): only meaningful when depth is
        // GPU-served (RELAXED PARITY, -Dsuperchunk.biome.offloadDepth) — otherwise tvd[2] is
        // NaN and depth above IS the vanilla compute, so there is nothing to compare.
        if (BiomeClimateCache.DEPTH_FLIP_VERIFY && !Double.isNaN(tvd[2])) {
            superchunk$measureDepthFlip(self, ctx, tvd, temperature, vegetation, depth,
                    continentalness, erosion, weirdness, gpuTarget);
        }
        cir.setReturnValue(gpuTarget);
    }

    /**
     * Counts an ACTUAL biome-selection flip caused by GPU-served depth, in-process (worldgen
     * is not bit-reproducible across runs, so no cross-run compare can do this). Builds the
     * full VANILLA {@link Climate.TargetPoint} — all six params from vanilla {@code compute()}
     * at this very quart sample; the three flatCache params are the IDENTICAL CPU values the
     * GPU-served TargetPoint used — and compares against the GPU-served one on three levels:
     * <ol>
     *   <li>{@code Climate.quantizeCoord(depth)} longs — the tight per-param upper bound
     *       (the ~1e-7 GPU spline drift only matters where {@code v*10000} crosses an
     *       integer);</li>
     *   <li>the whole quantized TargetPoint record ({@code equals} over the six longs) —
     *       EXACT for "would the biome-selection input differ": equal TargetPoints guarantee
     *       the identical biome, no RTree needed;</li>
     *   <li>on a TargetPoint mismatch, the ACTUAL selected biome, resolved through the
     *       dimension's {@link MultiNoiseBiomeSource#getNoiseBiome(Climate.TargetPoint)} —
     *       the very production ParameterList RTree (thread-safe: its search seed is a
     *       ThreadLocal). Vanilla target is looked up FIRST, GPU target SECOND, so the RTree
     *       seed this thread leaves behind is the GPU target's own nearest leaf — exactly
     *       what production's immediately-following lookup of the returned TargetPoint would
     *       have as its best seed (no behavioural perturbation). If no MultiNoiseBiomeSource
     *       is reachable (e.g. a below-zero-retrogen wrapper resolver), the sample stays
     *       "undecided" and the TargetPoint mismatch stands as the exact selection-input
     *       upper bound.</li>
     * </ol>
     * Counters accumulate per-thread and flush once per chunk (see
     * {@link BiomeClimateCache#recordDepthFlipSample}).
     */
    private static void superchunk$measureDepthFlip(Climate.Sampler self, DensityFunction.SinglePointContext ctx,
                                                    double[] tvd, float gpuTemp, float gpuVeg, float gpuDepth,
                                                    float continentalness, float erosion, float weirdness,
                                                    Climate.TargetPoint gpuTarget) {
        // Vanilla temperature/vegetation: recomputed via vanilla compute() when GPU-served
        // (rigor — the comparison assumes nothing about the float-exact gates); when a slot
        // was NOT GPU-served the passed-in value already IS the vanilla compute (reuse it).
        float vanTemp = Double.isNaN(tvd[0]) ? gpuTemp : (float) self.temperature().compute(ctx);
        float vanVeg = Double.isNaN(tvd[1]) ? gpuVeg : (float) self.humidity().compute(ctx);
        float vanDepth = (float) self.depth().compute(ctx);
        Climate.TargetPoint vanTarget =
                Climate.target(vanTemp, vanVeg, continentalness, erosion, vanDepth, weirdness);
        boolean depthQuantMismatch = Climate.quantizeCoord(gpuDepth) != Climate.quantizeCoord(vanDepth);
        boolean targetMismatch = !gpuTarget.equals(vanTarget);
        boolean biomeDecided = !targetMismatch;   // equal quantized TargetPoints => identical biome, decided for free
        boolean biomeFlip = false;
        if (targetMismatch) {
            MultiNoiseBiomeSource source = BiomeClimateCache.flipVerifyBiomeSource();
            if (source != null) {
                Holder<Biome> vanBiome = source.getNoiseBiome(vanTarget);   // FIRST (see javadoc ordering note)
                Holder<Biome> gpuBiome = source.getNoiseBiome(gpuTarget);   // SECOND
                biomeDecided = true;
                biomeFlip = !java.util.Objects.equals(gpuBiome, vanBiome);
            }
        }
        BiomeClimateCache.recordDepthFlipSample(depthQuantMismatch, targetMismatch, biomeDecided, biomeFlip);
    }

    /**
     * Resolves one served climate slot to the float the biome selection consumes.
     * NaN payload = "not GPU-served" sentinel -> vanilla compute (unchanged). On the
     * fp32 climate chain, applies the quantization guard: a value whose
     * {@code (float)v * 10000.0f} lands within {@link BiomeClimateCache#CLIMATE_FP32_EPS}
     * of an integer ({@code Climate.quantizeCoord} = float multiply + long truncation,
     * boundaries at the integers of scaled space for both signs), or is non-finite, is
     * recomputed on the CPU — so every float this method returns quantizes exactly as
     * the fp64/vanilla value would.
     */
    private static float superchunk$served(double v, DensityFunction df, DensityFunction.FunctionContext ctx) {
        if (Double.isNaN(v)) {
            return (float) df.compute(ctx);
        }
        float f = (float) v;
        if (BiomeClimateCache.climateFp32Live()) {
            float scaled = f * 10000.0f;   // EXACTLY quantizeCoord's float multiply
            double dist = Math.abs((double) scaled - Math.rint(scaled));
            if (!(dist > BiomeClimateCache.CLIMATE_FP32_EPS) || !Float.isFinite(f)) {
                // Refines are rare (~2*EPS of samples) so a per-refine counter is cheap;
                // the denominator comes from the existing per-chunk biome serve counters.
                GpuFillStats.recordClimateGuardRefine();
                return (float) df.compute(ctx);
            }
        }
        return f;
    }

    private static void superchunk$verifyOne(DensityFunction df, double gpuRaw, float served,
                                             DensityFunction.FunctionContext ctx) {
        double vanilla = df.compute(ctx);
        boolean floatDiff = Float.compare((float) vanilla, (float) gpuRaw) != 0;
        boolean doubleDiff = Double.compare(vanilla, gpuRaw) != 0;
        // The biome-deciding comparison: the SERVED float (post-guard) must quantize
        // exactly like the vanilla value. Any nonzero count here = a possible biome flip.
        boolean quantDiff = Climate.quantizeCoord(served) != Climate.quantizeCoord((float) vanilla);
        // Scaled-space drift census for auditing CLIMATE_FP32_EPS coverage.
        double scaledErr = Math.abs((double) ((float) gpuRaw * 10000.0f)
                - (double) ((float) vanilla * 10000.0f));
        GpuFillStats.recordBiomeVerify(floatDiff, doubleDiff, quantDiff, scaledErr);
    }
}
