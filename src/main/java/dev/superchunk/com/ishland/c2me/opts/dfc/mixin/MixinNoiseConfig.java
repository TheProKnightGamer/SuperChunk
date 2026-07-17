package dev.superchunk.com.ishland.c2me.opts.dfc.mixin;

import com.google.common.base.Stopwatch;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.BytecodeGen;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RandomState.class, priority = 900)
public class MixinNoiseConfig {

    @Mutable
    @Shadow @Final private NoiseRouter router;

    @Mutable
    @Shadow @Final private Climate.Sampler sampler;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void postCreate(CallbackInfo ci) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        // SuperChunk: optional in-process DFC vanilla-parity self-test. Captures the
        // ORIGINAL (uncompiled, vanilla) density functions, then after compilation
        // compares vanilla compute() vs compiled compute() over a deterministic 3D
        // grid -- noise-free proof of bit-equivalence (no parallel-gen variance).
        // Enable with -Dsuperchunk.dfc.paritytest=true . Off by default.
        final NoiseRouter c2me$vanillaRouter = Boolean.getBoolean("superchunk.dfc.paritytest") ? this.router : null;
        Reference2ReferenceMap<DensityFunction, DensityFunction> tempCache = new Reference2ReferenceOpenHashMap<>();
        // SuperChunk GPU (kernel merge): batch THIS router's DF kernels so they emit into ONE
        // OpenCL program (shared headers compiled once) built at endBatch, instead of one program
        // per DF. No-op when GPU off / mergeKernels off; per-DF fallback on any failure.
        dev.superchunk.gpu.dfc.GpuDfcHook.beginBatch();
        try {
        this.router = new NoiseRouter(
                BytecodeGen.compile(this.router.barrierNoise(), tempCache),
                BytecodeGen.compile(this.router.fluidLevelFloodednessNoise(), tempCache),
                BytecodeGen.compile(this.router.fluidLevelSpreadNoise(), tempCache),
                BytecodeGen.compile(this.router.lavaNoise(), tempCache),
                BytecodeGen.compile(this.router.temperature(), tempCache),
                BytecodeGen.compile(this.router.vegetation(), tempCache),
                BytecodeGen.compile(this.router.continents(), tempCache),
                BytecodeGen.compile(this.router.erosion(), tempCache),
                BytecodeGen.compile(this.router.depth(), tempCache),
                BytecodeGen.compile(this.router.ridges(), tempCache),
                BytecodeGen.compile(this.router.initialDensityWithoutJaggedness(), tempCache),
                BytecodeGen.compile(this.router.finalDensity(), tempCache),
                BytecodeGen.compile(this.router.veinToggle(), tempCache),
                BytecodeGen.compile(this.router.veinRidged(), tempCache),
                BytecodeGen.compile(this.router.veinGap(), tempCache)
        );
        this.sampler = new Climate.Sampler(
                BytecodeGen.compile(this.sampler.temperature(), tempCache),
                BytecodeGen.compile(this.sampler.humidity(), tempCache),
                BytecodeGen.compile(this.sampler.continentalness(), tempCache),
                BytecodeGen.compile(this.sampler.erosion(), tempCache),
                BytecodeGen.compile(this.sampler.depth(), tempCache),
                BytecodeGen.compile(this.sampler.weirdness(), tempCache),
                this.sampler.spawnTarget()
        );
        } finally {
            // Build the one merged program + resolve every deferred GPU delegate (or per-DF fallback).
            dev.superchunk.gpu.dfc.GpuDfcHook.endBatch();
        }
        stopwatch.stop();
        System.out.println(String.format("Density function compilation finished in %s", stopwatch));

        // SuperChunk GPU: biome-climate offload. Capture this dimension's UNCACHED climate
        // DFs (temperature/vegetation/depth) compiled GPU forms and fuse them into ONE
        // multi-output program, registered for the per-chunk BiomeClimateCache. No-op when
        // offloadBiome is off / the GPU is unavailable / they didn't all GPU-compile.
        try {
            dev.superchunk.gpu.dfc.BiomeClimateCache.tryRegister(this.router);
        } catch (Throwable t) {
            // never break router construction over the optional GPU offload — but never be
            // SILENT either: an early throw here kills the entire climate offload (and its
            // batching) for this dimension, and every downstream stats section is
            // count-gated, so without this line the run would show nothing at all.
            dev.superchunk.gpu.OpenCLBackend.LOGGER.warn(
                    "[SuperChunk-GPU] [biome] climate-offload registration THREW — GPU climate offload DISABLED for this dimension.", t);
        }

        // SuperChunk COMPACT-IDS (Stage 5, -Dsuperchunk.gpu.compactIds=probe): capture
        // this dimension's route for the chained decide kernel — the compiled
        // finalDensity/barrier/vein ASTs (stashed by BytecodeGen.compile; no GPU form
        // required — the full finalDensity never GPU-compiles as one kernel) + the
        // ore-vein positional seed from RandomState.oreRandom(). Fused-member identity
        // is resolved lazily at GpuBatchDispatcher.compile -> CompactIds.buildPlan, so
        // this is robust to any delegate-resolution ordering. No-op when the flag is
        // off; never breaks router construction.
        try {
            dev.superchunk.gpu.dfc.CompactIds.captureRouter((RandomState) (Object) this, this.router);
        } catch (Throwable t) {
            // optional probe capture — never break router construction, but say so
            // (probe-only, so INFO): a silent throw here would zero the compact-ids
            // probe for this dimension with no trace anywhere.
            dev.superchunk.gpu.OpenCLBackend.LOGGER.info(
                    "[SuperChunk-GPU] [compact-ids] captureRouter threw — probe inactive for this dimension: {}",
                    String.valueOf(t));
        }

        if (c2me$vanillaRouter != null) {
            c2me$runParityTest(c2me$vanillaRouter, this.router);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static void c2me$runParityTest(NoiseRouter vanilla, NoiseRouter compiled) {
        // Sample a deterministic spread of 3D points and compare each router entry's
        // vanilla compute() against the compiled compute(). With no blending and a
        // SinglePointContext, the compiled path must be bit-identical to vanilla.
        java.util.List<DensityFunction> v = java.util.List.of(
                vanilla.barrierNoise(), vanilla.fluidLevelFloodednessNoise(), vanilla.fluidLevelSpreadNoise(),
                vanilla.lavaNoise(), vanilla.temperature(), vanilla.vegetation(), vanilla.continents(),
                vanilla.erosion(), vanilla.depth(), vanilla.ridges(),
                vanilla.initialDensityWithoutJaggedness(), vanilla.finalDensity(),
                vanilla.veinToggle(), vanilla.veinRidged(), vanilla.veinGap());
        java.util.List<DensityFunction> c = java.util.List.of(
                compiled.barrierNoise(), compiled.fluidLevelFloodednessNoise(), compiled.fluidLevelSpreadNoise(),
                compiled.lavaNoise(), compiled.temperature(), compiled.vegetation(), compiled.continents(),
                compiled.erosion(), compiled.depth(), compiled.ridges(),
                compiled.initialDensityWithoutJaggedness(), compiled.finalDensity(),
                compiled.veinToggle(), compiled.veinRidged(), compiled.veinGap());
        String[] names = {"barrierNoise","fluidLevelFloodednessNoise","fluidLevelSpreadNoise","lavaNoise",
                "temperature","vegetation","continents","erosion","depth","ridges",
                "initialDensityWithoutJaggedness","finalDensity","veinToggle","veinRidged","veinGap"};
        long total = 0, exact = 0, mism = 0, threw = 0, asymThrew = 0;
        double maxAbsErr = 0.0;
        StringBuilder examples = new StringBuilder();
        java.util.Random rnd = new java.util.Random(8675309L);
        for (int i = 0; i < v.size(); i++) {
            for (int s = 0; s < 4000; s++) {
                int x = rnd.nextInt(8192) - 4096;
                int y = rnd.nextInt(384) - 64;
                int z = rnd.nextInt(8192) - 4096;
                DensityFunction.FunctionContext ctx = new DensityFunction.SinglePointContext(x, y, z);
                // Compute each side independently so we can tell which one threw:
                // an exception on exactly one side is itself a parity divergence, and a
                // sample where both sides throw must NOT silently count as a clean compare.
                double dv = Double.NaN, dc = Double.NaN;
                boolean vThrew = false, cThrew = false;
                try {
                    dv = v.get(i).compute(ctx);
                } catch (Throwable t) {
                    vThrew = true;
                }
                try {
                    dc = c.get(i).compute(ctx);
                } catch (Throwable t) {
                    cThrew = true;
                }
                if (vThrew || cThrew) {
                    threw++;
                    if (vThrew != cThrew) {
                        // exactly one side threw -> real divergence between vanilla and compiled
                        asymThrew++;
                        if (examples.length() < 400) {
                            examples.append(String.format("[%s @(%d,%d,%d) %s threw] ",
                                    names[i], x, y, z, vThrew ? "vanilla" : "compiled"));
                        }
                    }
                    continue;
                }
                total++;
                if (Double.compare(dv, dc) == 0) {
                    exact++;
                } else {
                    mism++;
                    double e = Math.abs(dv - dc);
                    if (e > maxAbsErr) maxAbsErr = e;
                    if (examples.length() < 400) {
                        examples.append(String.format("[%s @(%d,%d,%d) v=%.10g c=%.10g] ", names[i], x, y, z, dv, dc));
                    }
                }
            }
        }
        // PASS only if we actually compared something (total>0) AND there were neither numeric
        // mismatches nor asymmetric exceptions. Symmetric throws are tracked but don't fail the
        // test (both sides behaved the same); they're surfaced via THREW for diagnosis.
        boolean pass = total > 0 && mism == 0 && asymThrew == 0;
        String verdict = pass ? "PASS (bit-identical to vanilla)"
                : (total == 0 ? "FAIL (no samples compared -- every sample threw)" : "DIFF");
        System.out.println(String.format(
                "SuperChunk DFC parity self-test: total=%d EXACT=%d MISMATCH=%d THREW=%d (asym=%d) maxAbsErr=%.3e -> %s",
                total, exact, mism, threw, asymThrew, maxAbsErr, verdict));
        if (!pass) {
            System.out.println("SuperChunk DFC parity self-test examples: " + examples);
        }
    }

}
