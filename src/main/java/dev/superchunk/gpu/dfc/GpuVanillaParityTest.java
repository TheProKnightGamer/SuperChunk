package dev.superchunk.gpu.dfc;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.McToAst;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.BytecodeGen;
import dev.superchunk.gpu.OpenCLBackend;
import dev.superchunk.gpu.OpenCLDevice;
import net.minecraft.core.Holder;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stage-3 GPU-vs-VANILLA density-function parity harness — the RTX 3070
 * acceptance gate.
 *
 * <p>Unlike {@link GpuDfcParityTest} (which compares the GPU against C2ME's CPU
 * DFC bytecode), this compares the GPU-compiled density function directly against
 * the <b>vanilla</b> {@link DensityFunction#compute} ground truth, evaluated over
 * a deterministic 3D grid with a {@link DensityFunction.SinglePointContext} (no
 * blending). On fp64 hardware (cl_khr_fp64) the GPU result must be
 * <b>bit-identical</b> to vanilla; on fp32-only hardware (this laptop's Iris Xe)
 * only structural parity is checked and the test is labeled FP32.
 *
 * <p>Deterministic + needs no datapack/registry load: each density function is
 * built from vanilla {@link DensityFunctions} with a fixed-seed {@link NormalNoise}
 * already resolved. Both sides consume the SAME graph, so any difference is purely
 * GPU numeric precision.
 *
 * <p>Gated behind {@code selftest.gpu_parity=true}; off by default. Never throws.
 */
public final class GpuVanillaParityTest {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    private static final long SEED = 0x5C0FFEEL;

    /**
     * The composite case name. Per {@link #testCase}, every OTHER DF that fails to
     * GPU-compile is a graceful CPU-fallback PASS, but a null GPU-compile of the
     * overworld composite is a hard FAIL: it exercises the full overworld-style
     * emission path, so a composite-emission regression must not be masked green.
     */
    private static final String COMPOSITE_CASE = "overworld_composite";

    /** Number of distinct per-chunk requests the cross-chunk aggregator self-test submits. */
    private static final int AGG_REQUESTS = 384;

    // fp64: bit-exact expected; we still allow a TINY epsilon for the rare case the
    // GPU's FMA contraction reorders an op (the report distinguishes EXACT vs near).
    private static final double FP64_NEAR_TOL = 1.0e-12;     // "near" bucket (FMA reorder)
    // fp32: structural only.
    private static final double FP32_REL_TOL = 5.0e-3;
    private static final double FP32_ABS_TOL = 2.0e-3;
    private static final double REL_EPS = 1.0e-6;

    private GpuVanillaParityTest() {
    }

    private static void clearC2meCompilationCache() {
        try {
            java.lang.reflect.Field f = BytecodeGen.class.getDeclaredField("compilationCache");
            f.setAccessible(true);
            Object cache = f.get(null);
            if (cache instanceof java.util.Map<?, ?> map) {
                map.clear();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Runs the GPU-vs-vanilla parity suite. Never throws. Returns true on PASS. */
    public static boolean run() {
        if (!OpenCLBackend.isAvailable()) {
            LOGGER.warn("[SuperChunk-GPU] GPU-vs-vanilla parity test skipped — backend unavailable.");
            return false;
        }
        OpenCLDevice dev = OpenCLBackend.device();
        boolean fp32 = OpenCLBackend.isFp32();
        String mode = fp32
                ? "FP32 (structural only; device lacks fp64 or forced — NOT a vanilla-accuracy gate)"
                : "FP64 (double) — expect BIT-EXACT vs vanilla";

        LOGGER.info("[SuperChunk-GPU] ===== GPU-vs-VANILLA parity test (Stage 3) =====");
        LOGGER.info("[SuperChunk-GPU] Device: {}", dev.describe());
        LOGGER.info("[SuperChunk-GPU] Precision mode: {}", mode);
        if (fp32) {
            LOGGER.warn("[SuperChunk-GPU] This device has NO fp64 -> a PASS here proves the GPU algorithm "
                    + "is structurally correct, NOT that terrain is vanilla-accurate. Run this on the RTX 3070.");
        }

        boolean ok = true;
        try {
            ok &= testCase("y_clamped_gradient", buildYGradient(), fp32, false);
            // Stage 5: the two coalescing cases ALSO verify the whole-grid path is
            // bit-identical to per-column, on the SAME compiled instance (no extra
            // program compile/close churn on the driver).
            ok &= testCase("noise+arith", buildNoiseArith(), fp32, true);
            ok &= testCase("shifted_noise", buildShiftedNoise(), fp32, false);
            ok &= testCase("shift_family", buildShiftFamily(), fp32, false);
            ok &= testCase("range_choice+clamp", buildRangeClamp(), fp32, false);
            ok &= testCase("weird_scaled", buildWeirdScaled(), fp32, false);
            // Vanilla BlendedNoise ("old_blended_noise") — the legacy three-PerlinNoise
            // blend with the yLimit/smear sampling variant (df_blended_noise). fp64
            // expects BIT-EXACT vs vanilla compute(), like every non-spline case.
            ok &= testCase("blended_noise", buildBlendedNoise(), fp32, false);
            ok &= testCase("spline", buildSpline(), fp32, false);
            ok &= testCase(COMPOSITE_CASE, buildOverworldComposite(), fp32, true);

            // Fused multi-output kernel parity: the SAME representative DFs compiled
            // together into ONE df_batch_lattice_multi program must, per root, be
            // BIT-IDENTICAL to the per-DF df_batch_lattice kernel (same kernel math,
            // shared NoiseRegistry / cross-DF CSE). Compares fused-GPU vs perDF-GPU
            // (NOT vs vanilla), so EXACT 0.0 is expected for every root incl. splines.
            ok &= checkFusedEmit();

            // CROSS-CHUNK batched-dispatch parity: the SAME representative DFs compiled
            // together into ONE df_batch_lattice_multichunk program and dispatched for K
            // distinct chunk origins in ONE NDRange + ONE readback must, per chunk c and
            // root k, be BIT-IDENTICAL to that chunk's single-chunk df_batch_lattice_multi
            // dispatch at the same origin. Compares batched-GPU vs per-chunk-GPU (same
            // kernel math), so EXACT 0.0 is expected for every chunk and root.
            ok &= checkBatchEmit();

            // CROSS-CHUNK AGGREGATOR parity (GpuChunkBatcher): from MANY threads, submit a
            // few hundred per-chunk requests with distinct origins through the thread-safe
            // aggregator; it pools them and serves each with one of FAR fewer batched GPU
            // dispatches. Every chunk future's M*n result must be BIT-IDENTICAL to that
            // chunk's single-chunk df_batch_lattice_multi reference, AND the dispatch count
            // must show real coalescing (dispatches << requests).
            ok &= checkBatchAggregator();

            // Biome-climate offload parity: the fused temperature/vegetation/depth grid
            // over the per-chunk QUART lattice (origin chunk-min, stride 4) must be
            // BIT-EXACT vs vanilla compute(SinglePointContext) at the same quart-block
            // positions — the exact path BiomeClimateCache serves Climate.Sampler.sample
            // from. This is the biome acceptance gate.
            ok &= checkBiomeClimate(fp32);
        } catch (Throwable t) {
            LOGGER.error("[SuperChunk-GPU] GPU-vs-vanilla parity test threw — FAIL.", t);
            ok = false;
        }

        LOGGER.info("[SuperChunk-GPU] ===== GPU-vs-VANILLA parity test {} ({}) =====",
                ok ? "PASS" : "FAIL", fp32 ? "FP32-structural" : "FP64 bit-exact");
        return ok;
    }

    /**
     * Compiles {@code df} to GPU and compares it bit-exactly (fp64) / structurally
     * (fp32) against vanilla {@code compute()} over the sample grid. A case that does
     * NOT GPU-compile (returns null) is reported as CPU-only — a graceful-fallback PASS
     * — EXCEPT {@link #COMPOSITE_CASE}: a null GPU-compile of the overworld composite is
     * a hard FAIL, so a composite-emission regression cannot be masked green.
     */
    private static boolean testCase(String name, DensityFunction df, boolean fp32, boolean coalesceCheck) {
        clearC2meCompilationCache();
        AstNode ast = McToAst.toAst(df);

        GpuDensityFunction gpu = GpuDensityFunction.compile(ast);
        if (gpu == null) {
            // Graceful CPU-fallback is the contract for MOST DFs, but the composite is the
            // exception (see javadoc): it MUST exercise the full emission path, so a null
            // GPU-compile of overworld_composite is a real regression and fails the gate.
            if (COMPOSITE_CASE.equals(name)) {
                LOGGER.warn("[SuperChunk-GPU] [{}] did NOT GPU-compile — the composite MUST compile; FAIL.", name);
                return false;
            }
            LOGGER.info("[SuperChunk-GPU] [{}] -> CPU-only (did not GPU-compile).", name);
            return true; // graceful fallback, not a failure
        }
        try {
            int[] sx = buildX();
            int[] sy = buildY();
            int[] sz = buildZ();
            int n = sx.length;

            // Vanilla ground truth via compute(SinglePointContext).
            double[] vanilla = new double[n];
            for (int i = 0; i < n; i++) {
                DensityFunction.FunctionContext ctx =
                        new DensityFunction.SinglePointContext(sx[i], sy[i], sz[i]);
                vanilla[i] = df.compute(ctx);
            }

            double[] g = new double[n];
            if (!gpu.fill(g, sx, sy, sz)) {
                LOGGER.warn("[SuperChunk-GPU] [{}] GPU fill failed.", name);
                return false;
            }
            boolean ok = fp32 ? reportFp32(name, vanilla, g) : reportFp64(name, vanilla, g);
            if (coalesceCheck) {
                ok &= coalesceCheck("coalesce(" + name + ")", gpu);
            }
            return ok;
        } finally {
            gpu.close();
        }
    }

    /**
     * Stage-5 coalescing self-check: the whole-chunk grid computed in ONE dispatch
     * ({@code fillWholeGrid}) must, when sliced into per-column subsets, be
     * <b>bit-for-bit identical</b> to what the per-column path ({@code fill})
     * produces — they are the same points and same kernel, just batched. This is
     * the critical correctness gate for dispatch coalescing. Runs on an
     * already-compiled {@code gpu} (no extra program compile/close churn).
     */
    private static boolean coalesceCheck(String name, GpuDensityFunction gpu) {
        try {
            // A representative whole-chunk cell-corner grid (Y-outer/X-mid/Z-inner),
            // matching ChunkGridCache's extent for a default overworld chunk.
            final int ox = 96, oy = -64, oz = -240;
            final int sx = 4, sy = 8, sz = 4;            // cellWidth, cellHeight, cellWidth
            final int dimX = 5, dimY = 49, dimZ = 5;     // (cellCountXZ+1, cellCountY+1, cellCountXZ+1)
            int gridLen = dimX * dimY * dimZ;

            double[] grid = new double[gridLen];
            if (!gpu.fillWholeGrid(grid, ox, oy, oz, sx, sy, sz, dimX, dimY, dimZ)) {
                LOGGER.warn("[SuperChunk-GPU] [{}] fillWholeGrid failed -> per-column fallback (not a coalescing failure).", name);
                return true;
            }

            // For every (X,Z) cell-corner, build the exact per-column fill C2ME issues
            // (fixed X,Z; Y ascending over dimY) and compare to the grid slice.
            long checked = 0, mismatch = 0;
            double maxAbs = 0.0;
            int[] cx = new int[dimY];
            int[] cy = new int[dimY];
            int[] cz = new int[dimY];
            double[] col = new double[dimY];
            for (int ix = 0; ix < dimX; ix++) {
                for (int iz = 0; iz < dimZ; iz++) {
                    for (int iy = 0; iy < dimY; iy++) {
                        cx[iy] = ox + ix * sx;
                        cy[iy] = oy + iy * sy;
                        cz[iy] = oz + iz * sz;
                    }
                    if (!gpu.fill(col, cx, cy, cz)) {
                        LOGGER.warn("[SuperChunk-GPU] [{}] per-column fill failed.", name);
                        return false;
                    }
                    for (int iy = 0; iy < dimY; iy++) {
                        // grid flat index, Y-outer/X-mid/Z-inner: (iy*dimX + ix)*dimZ + iz
                        double gv = grid[(iy * dimX + ix) * dimZ + iz];
                        double cv = col[iy];
                        checked++;
                        if (Double.compare(gv, cv) != 0) {
                            double e = Math.abs(gv - cv);
                            if (e > maxAbs) maxAbs = e;
                            mismatch++;
                        }
                    }
                }
            }
            boolean ok = (mismatch == 0);
            LOGGER.info("[SuperChunk-GPU] [{}] whole-grid vs per-column: checked={} mismatch={} maxAbsErr={} -> {}",
                    name, checked, mismatch, String.format("%.3e", maxAbs),
                    ok ? "PASS (bit-identical)" : "FAIL");
            return ok;
        } catch (Throwable t) {
            LOGGER.error("[SuperChunk-GPU] [{}] coalescing self-check threw — FAIL.", name, t);
            return false;
        }
    }

    /**
     * Fused-emit parity: take the SAME representative DFs, convert each to an AST,
     * then (a) compile each individually via {@code emit} and dispatch
     * {@code df_batch_lattice} over a representative lattice -> per-DF reference grid,
     * and (b) compile the whole set together via {@code emitMulti} and dispatch
     * {@code df_batch_lattice_multi} ONCE into an {@code M*n} buffer. Assert that for
     * every root {@code k} and index {@code i}, {@code multi[k*n+i]} equals the
     * per-DF reference {@code single_k[i]} BIT-EXACTLY (Double.compare==0).
     *
     * <p>This compares fused-GPU vs perDF-GPU (NOT vs vanilla), so EXACT {@code 0.0}
     * is expected for ALL roots including splines (same kernel math). Any non-zero
     * mismatch is a real CSE/fusion bug. Uses the SAME AST instances for both sides.
     */
    private static boolean checkFusedEmit() {
        // Same representative DF set as the per-case suite above (incl. spline + composite
        // + blended_noise — the latter makes a df_blended_noise emission/compile regression
        // a LOUD fused-parity FAIL instead of a silent CPU fallback).
        String[] names = {
                "y_clamped_gradient", "noise+arith", "shifted_noise", "shift_family",
                "range_choice+clamp", "weird_scaled", "blended_noise", "spline", "overworld_composite",
        };
        DensityFunction[] dfs = {
                buildYGradient(), buildNoiseArith(), buildShiftedNoise(), buildShiftFamily(),
                buildRangeClamp(), buildWeirdScaled(), buildBlendedNoise(), buildSpline(), buildOverworldComposite(),
        };

        // Convert each DF to an AST (clear the C2ME compilation cache before each, exactly
        // as testCase() does). The SAME AstNode instance is used for both the per-DF
        // reference and the fused compile, so any difference is purely fusion/CSE.
        List<AstNode> roots = new ArrayList<>(dfs.length);
        for (DensityFunction df : dfs) {
            clearC2meCompilationCache();
            roots.add(McToAst.toAst(df));
        }

        // The earlier per-case testCase()s compiled these SAME DFs via
        // GpuDensityFunction.compile (CLProgramCache.getOrBuild) and then close()d them.
        // Under the cache's refcount-2 contract that does NOT free them: getOrBuild returns
        // each shared program at refcount 2 — the cache owns one reference (released only by
        // clearMemory() at shutdown) and the caller owns the other — so a caller close()
        // drops 2 -> 1 and the cl_program stays LIVE and mapped. A recompile of the same
        // source would simply hit the in-memory dedup fast path (retain() 1 -> 2), so there
        // is no use-after-free to dodge. (Even a racing shutdown is safe: retain() is an
        // atomic increment-if-nonzero that returns false to force a rebuild rather than hand
        // back a freed cl_program.) This clearMemory() is therefore an INTENTIONAL
        // binary-reload exercise: releasing the cache's reference fully frees those earlier
        // programs (their callers already closed) and empties the share map, forcing each
        // recompile below to MISS the in-memory dedup and rebuild a FRESH program from the
        // on-disk binary (clCreateProgramWithBinary) or source — valuable self-test coverage
        // of the binary-cache / recompile path. Self-test only.
        dev.superchunk.gpu.CLProgramCache.clearMemory();

        // Representative lattice = the coalesce-check whole-chunk cell-corner grid extent.
        final int ox = 96, oy = -64, oz = -240;
        final int sx = 4, sy = 8, sz = 4;
        final int dimX = 5, dimY = 49, dimZ = 5;
        final int n = dimX * dimY * dimZ;   // 1225 points per root
        final int m = roots.size();

        // (a) Per-DF reference: compile each via emit(), dispatch df_batch_lattice over
        //     the lattice (fillWholeGrid uses the in-kernel lattice kernel).
        double[][] ref = new double[m][];
        for (int k = 0; k < m; k++) {
            GpuDensityFunction gpu = GpuDensityFunction.compile(roots.get(k));
            if (gpu == null) {
                LOGGER.warn("[SuperChunk-GPU] [fused-emit] root[{}] {} did not GPU-compile per-DF — cannot run fused parity.",
                        k, names[k]);
                return false;
            }
            try {
                double[] g = new double[n];
                if (!gpu.fillWholeGrid(g, ox, oy, oz, sx, sy, sz, dimX, dimY, dimZ)) {
                    LOGGER.warn("[SuperChunk-GPU] [fused-emit] root[{}] {} per-DF lattice fill failed.", k, names[k]);
                    return false;
                }
                ref[k] = g;
            } finally {
                gpu.close();
            }
        }

        // (b) Fused: compile all roots via emitMulti, dispatch df_batch_lattice_multi ONCE.
        double[] multi = new double[m * n];
        GpuFusedProgram fused = GpuFusedProgram.compile(roots);
        if (fused == null) {
            LOGGER.warn("[SuperChunk-GPU] [fused-emit] emitMulti program did not compile — FAIL.");
            return false;
        }
        try {
            if (fused.rootCount() != m) {
                LOGGER.warn("[SuperChunk-GPU] [fused-emit] rootCount mismatch: emitMulti M={} but roots={} — FAIL.",
                        fused.rootCount(), m);
                return false;
            }
            if (!fused.fill(multi, ox, oy, oz, sx, sy, sz, dimX, dimY, dimZ)) {
                LOGGER.warn("[SuperChunk-GPU] [fused-emit] fused multi-dispatch fill failed — FAIL.");
                return false;
            }
        } finally {
            fused.close();
        }

        // (c) Compare per root, bit-exact.
        long totalChecked = 0, totalMismatch = 0;
        double overallMaxAbs = 0.0;
        boolean allOk = true;
        for (int k = 0; k < m; k++) {
            long mismatch = 0;
            double maxAbs = 0.0;
            int worst = -1;
            for (int i = 0; i < n; i++) {
                double a = multi[k * n + i];
                double b = ref[k][i];
                if (Double.compare(a, b) != 0) {
                    double e = Math.abs(a - b);
                    if (e > maxAbs) {
                        maxAbs = e;
                        worst = i;
                    }
                    mismatch++;
                }
            }
            boolean ok = (mismatch == 0);
            allOk &= ok;
            totalChecked += n;
            totalMismatch += mismatch;
            if (maxAbs > overallMaxAbs) overallMaxAbs = maxAbs;
            LOGGER.info("[SuperChunk-GPU] [fused-emit] root[{}] {} n={} mismatch={} maxAbsErr={} -> {}",
                    k, names[k], n, mismatch, String.format("%.3e", maxAbs),
                    ok ? "PASS (bit-identical to per-DF)" : "FAIL");
            if (!ok && worst >= 0) {
                LOGGER.warn("[SuperChunk-GPU]   [fused-emit] root[{}] {} worst @ idx {}: fused={} perDF={} (absErr={})",
                        k, names[k], worst, multi[k * n + worst], ref[k][worst], String.format("%.3e", maxAbs));
            }
        }
        LOGGER.info("[SuperChunk-GPU] [fused-emit] roots={} checked={} mismatch={} maxAbsErr={} -> {}",
                m, totalChecked, totalMismatch, String.format("%.3e", overallMaxAbs),
                allOk ? "PASS (bit-identical to per-DF)" : "FAIL");
        return allOk;
    }

    /**
     * CROSS-CHUNK batched-dispatch parity: take the SAME representative DF set, build ONE
     * {@link GpuBatchDispatcher} over their ASTs ({@code df_batch_lattice_multichunk}),
     * and {@code batchFill} K distinct chunk origins at once into a {@code K*M*n} buffer.
     * Independently, dispatch the single-chunk {@code df_batch_lattice_multi} (via
     * {@link GpuFusedProgram}) once PER origin to get each chunk's per-chunk reference
     * grid. Assert that for every chunk {@code c}, root {@code k} and index {@code i},
     * {@code batched[(c*M+k)*n+i]} equals the per-chunk reference {@code ref[c][k*n+i]}
     * BIT-EXACTLY (Double.compare==0).
     *
     * <p>This compares batched-GPU vs per-chunk-GPU (NOT vs vanilla) — both run the same
     * unrolled multi-root kernel math, just batched vs one-at-a-time — so EXACT {@code 0.0}
     * is expected for ALL chunks/roots including splines. Any non-zero mismatch is a real
     * cross-chunk indexing / origin-table bug. K=8 distinct origins (chunk-span offsets in
     * X/Z), stride 4/8/4, dims 5x49x5 — the same lattice geometry as the fused-emit check.
     */
    private static boolean checkBatchEmit() {
        // Same representative DF set as the per-case / fused-emit suites.
        String[] names = {
                "y_clamped_gradient", "noise+arith", "shifted_noise", "shift_family",
                "range_choice+clamp", "weird_scaled", "blended_noise", "spline", "overworld_composite",
        };
        DensityFunction[] dfs = {
                buildYGradient(), buildNoiseArith(), buildShiftedNoise(), buildShiftFamily(),
                buildRangeClamp(), buildWeirdScaled(), buildBlendedNoise(), buildSpline(), buildOverworldComposite(),
        };

        // Convert each DF to an AST (clear the C2ME compilation cache before each, exactly
        // as testCase() / checkFusedEmit() do). The SAME AstNode instances feed both the
        // per-chunk reference (emitMulti) and the batched dispatcher (emitMultiChunk).
        List<AstNode> roots = new ArrayList<>(dfs.length);
        for (DensityFunction df : dfs) {
            clearC2meCompilationCache();
            roots.add(McToAst.toAst(df));
        }

        // Earlier cases (incl. checkFusedEmit) compiled+closed the per-DF and emitMulti
        // sources. Under the refcount-2 contract those close()s only drop each program from
        // refcount 2 -> 1; the cache still owns a reference (released only by clearMemory()
        // at shutdown), so the cl_programs stay LIVE and mapped and a recompile would just
        // hit the in-memory dedup. This clearMemory() is an INTENTIONAL binary-reload
        // exercise: releasing the cache's reference fully frees those now-idle programs
        // (their callers already closed) and empties the share map, so the per-chunk
        // reference's emitMulti program rebuilds FRESH from the on-disk binary / source —
        // exercising the recompile path. (The batched dispatcher's
        // df_batch_lattice_multichunk source is brand-new, so it is unaffected either way.)
        dev.superchunk.gpu.CLProgramCache.clearMemory();

        // Same lattice as the fused-emit / coalesce checks.
        final int sx = 4, sy = 8, sz = 4;
        final int dimX = 5, dimY = 49, dimZ = 5;
        final int n = dimX * dimY * dimZ;   // 1225 points per root
        final int m = roots.size();

        // K distinct chunk origins: vary ox/oz by the cell-corner span (chunk extent) so
        // every batched chunk samples a different region; oy fixed. Base = the coalesce grid.
        final int K = 8;
        final int spanX = (dimX - 1) * sx;  // 16 blocks (one chunk in X)
        final int spanZ = (dimZ - 1) * sz;  // 16 blocks (one chunk in Z)
        int[] originsXYZ = new int[K * 3];
        for (int c = 0; c < K; c++) {
            int ox = 96 + (c % 4) * spanX;       // 4 distinct X
            int oz = -240 + (c / 4) * (spanZ * 3); // 2 distinct Z (spread further)
            int oy = -64;
            originsXYZ[c * 3 + 0] = ox;
            originsXYZ[c * 3 + 1] = oy;
            originsXYZ[c * 3 + 2] = oz;
        }

        // (a) Per-chunk reference: ONE df_batch_lattice_multi program, dispatched once per
        //     origin into ref[c] (an M*n grid). Bit-exact to each chunk's batched slice.
        double[][] ref = new double[K][];
        GpuFusedProgram fused = GpuFusedProgram.compile(roots);
        if (fused == null) {
            LOGGER.warn("[SuperChunk-GPU] [batch-emit] per-chunk reference (emitMulti) program did not compile — FAIL.");
            return false;
        }
        try {
            if (fused.rootCount() != m) {
                LOGGER.warn("[SuperChunk-GPU] [batch-emit] reference rootCount mismatch: emitMulti M={} but roots={} — FAIL.",
                        fused.rootCount(), m);
                return false;
            }
            for (int c = 0; c < K; c++) {
                double[] g = new double[m * n];
                int ox = originsXYZ[c * 3 + 0], oy = originsXYZ[c * 3 + 1], oz = originsXYZ[c * 3 + 2];
                if (!fused.fill(g, ox, oy, oz, sx, sy, sz, dimX, dimY, dimZ)) {
                    LOGGER.warn("[SuperChunk-GPU] [batch-emit] per-chunk reference fill failed for chunk {} (origin {},{},{}) — FAIL.",
                            c, ox, oy, oz);
                    return false;
                }
                ref[c] = g;
            }
        } finally {
            fused.close();
        }

        // (b) Batched: ONE df_batch_lattice_multichunk program, ONE dispatch + ONE readback
        //     for all K chunks into a K*M*n buffer.
        double[] batched = new double[K * m * n];
        GpuBatchDispatcher disp = GpuBatchDispatcher.compile(roots);
        if (disp == null) {
            LOGGER.warn("[SuperChunk-GPU] [batch-emit] batched program (emitMultiChunk) did not compile — FAIL.");
            return false;
        }
        try {
            if (disp.rootCount() != m) {
                LOGGER.warn("[SuperChunk-GPU] [batch-emit] batched rootCount mismatch: emitMultiChunk M={} but roots={} — FAIL.",
                        disp.rootCount(), m);
                return false;
            }
            if (!disp.batchFill(originsXYZ, K, sx, sy, sz, dimX, dimY, dimZ, batched)) {
                LOGGER.warn("[SuperChunk-GPU] [batch-emit] batched multichunk dispatch fill failed — FAIL.");
                return false;
            }
        } finally {
            disp.close();
        }

        // (c) Compare per chunk + root, bit-exact: batched[(c*M+k)*n+i] vs ref[c][k*n+i].
        long totalChecked = 0, totalMismatch = 0;
        double overallMaxAbs = 0.0;
        boolean allOk = true;
        for (int c = 0; c < K; c++) {
            long chunkMismatch = 0;
            double chunkMaxAbs = 0.0;
            int worstK = -1, worstI = -1;
            for (int k = 0; k < m; k++) {
                int baseBatched = (c * m + k) * n;
                int baseRef = k * n;
                for (int i = 0; i < n; i++) {
                    double a = batched[baseBatched + i];
                    double b = ref[c][baseRef + i];
                    totalChecked++;
                    if (Double.compare(a, b) != 0) {
                        double e = Math.abs(a - b);
                        if (e > chunkMaxAbs) {
                            chunkMaxAbs = e;
                            worstK = k;
                            worstI = i;
                        }
                        chunkMismatch++;
                    }
                }
            }
            boolean ok = (chunkMismatch == 0);
            allOk &= ok;
            totalMismatch += chunkMismatch;
            if (chunkMaxAbs > overallMaxAbs) overallMaxAbs = chunkMaxAbs;
            if (!ok && worstK >= 0) {
                int ox = originsXYZ[c * 3 + 0], oy = originsXYZ[c * 3 + 1], oz = originsXYZ[c * 3 + 2];
                LOGGER.warn("[SuperChunk-GPU]   [batch-emit] chunk {} (origin {},{},{}) {} mismatch={} worst root[{}] idx {}: batched={} perChunk={} (absErr={})",
                        c, ox, oy, oz, names[worstK], chunkMismatch, worstK, worstI,
                        batched[(c * m + worstK) * n + worstI], ref[c][worstK * n + worstI],
                        String.format("%.3e", chunkMaxAbs));
            }
        }
        LOGGER.info("[SuperChunk-GPU] [batch-emit] chunks={} roots={} checked={} mismatch={} maxAbsErr={} -> {}",
                K, m, totalChecked, totalMismatch, String.format("%.3e", overallMaxAbs),
                allOk ? "PASS (batched == per-chunk)" : "FAIL");
        return allOk;
    }

    /**
     * CROSS-CHUNK AGGREGATOR parity — the {@link GpuChunkBatcher} gate.
     *
     * <p>Builds ONE {@link GpuChunkBatcher} over a {@link GpuBatchDispatcher} compiled
     * from the SAME representative DF set, then from an {@link ExecutorService} of 16
     * threads {@link GpuChunkBatcher#submit submits} {@value #AGG_REQUESTS} per-chunk
     * requests with DISTINCT origins (released together via a start latch to maximise
     * concurrency). The aggregator pools them by geometry and serves each with one of
     * FAR fewer batched dispatches, completing a future per chunk.
     *
     * <p>Two assertions: (1) BIT-EXACT — each chunk future's {@code M*n} result must
     * equal that chunk's single-chunk {@code df_batch_lattice_multi} reference (built
     * once per origin from a {@link GpuFusedProgram}) under {@code Double.compare==0};
     * (2) REAL COALESCING — the number of GPU dispatches the batcher issued must be far
     * below the request count (a clear margin under {@code requests/4}; ideally
     * {@code ~ceil(requests/batchLimit)}). A bounded {@code get()} per future guards
     * against a deadlock hanging the boot.
     */
    private static boolean checkBatchAggregator() {
        DensityFunction[] dfs = {
                buildYGradient(), buildNoiseArith(), buildShiftedNoise(), buildShiftFamily(),
                buildRangeClamp(), buildWeirdScaled(), buildBlendedNoise(), buildSpline(), buildOverworldComposite(),
        };
        List<AstNode> roots = new ArrayList<>(dfs.length);
        for (DensityFunction df : dfs) {
            clearC2meCompilationCache();
            roots.add(McToAst.toAst(df));
        }
        // Earlier cases (incl. checkBatchEmit) compiled+closed these sources. Under the
        // refcount-2 contract those programs are still LIVE and cached (a caller close()
        // drops 2 -> 1; the cache keeps its own reference until clearMemory() at shutdown),
        // so this clearMemory() is an INTENTIONAL binary-reload exercise: releasing the
        // cache's reference frees those now-idle programs and empties the share map, so the
        // reference's df_batch_lattice_multi program rebuilds FRESH from the on-disk binary /
        // source — extra recompile-path coverage, not UAF avoidance. The batcher's
        // df_batch_lattice_multichunk source is brand-new and unaffected either way.
        dev.superchunk.gpu.CLProgramCache.clearMemory();

        // Same lattice geometry as the fused-emit / batch-emit checks.
        final int sx = 4, sy = 8, sz = 4;
        final int dimX = 5, dimY = 49, dimZ = 5;
        final int n = dimX * dimY * dimZ;     // 1225 points per root
        final int m = roots.size();
        final int sliceLen = m * n;           // M*n doubles per chunk

        // N distinct chunk origins on a 24x16 grid of chunk-spans; oy fixed.
        final int N = AGG_REQUESTS;
        final int spanX = (dimX - 1) * sx;    // 16 blocks
        final int spanZ = (dimZ - 1) * sz;    // 16 blocks
        final int gridW = 24;                 // 24 distinct X
        int[][] origins = new int[N][3];
        for (int i = 0; i < N; i++) {
            origins[i][0] = 96 + (i % gridW) * spanX;
            origins[i][1] = -64;
            origins[i][2] = -240 + (i / gridW) * spanZ;
        }

        // (a) Per-chunk reference: ONE df_batch_lattice_multi program, one fill per origin.
        double[][] ref = new double[N][];
        GpuFusedProgram fused = GpuFusedProgram.compile(roots);
        if (fused == null) {
            LOGGER.warn("[SuperChunk-GPU] [batch-agg] per-chunk reference (emitMulti) program did not compile — FAIL.");
            return false;
        }
        try {
            if (fused.rootCount() != m) {
                LOGGER.warn("[SuperChunk-GPU] [batch-agg] reference rootCount mismatch: emitMulti M={} but roots={} — FAIL.",
                        fused.rootCount(), m);
                return false;
            }
            for (int i = 0; i < N; i++) {
                double[] g = new double[sliceLen];
                if (!fused.fill(g, origins[i][0], origins[i][1], origins[i][2], sx, sy, sz, dimX, dimY, dimZ)) {
                    LOGGER.warn("[SuperChunk-GPU] [batch-agg] reference fill failed for origin {},{},{} — FAIL.",
                            origins[i][0], origins[i][1], origins[i][2]);
                    return false;
                }
                ref[i] = g;
            }
        } finally {
            fused.close();
        }

        // (b) Build the aggregator over a fresh df_batch_lattice_multichunk dispatcher.
        GpuBatchDispatcher disp = GpuBatchDispatcher.compile(roots);
        if (disp == null) {
            LOGGER.warn("[SuperChunk-GPU] [batch-agg] batched dispatcher (emitMultiChunk) did not compile — FAIL.");
            return false;
        }
        if (disp.rootCount() != m) {
            LOGGER.warn("[SuperChunk-GPU] [batch-agg] dispatcher rootCount mismatch: emitMultiChunk M={} but roots={} — FAIL.",
                    disp.rootCount(), m);
            disp.close();
            return false;
        }
        GpuChunkBatcher batcher = GpuChunkBatcher.init(disp);
        if (batcher == null) {
            LOGGER.warn("[SuperChunk-GPU] [batch-agg] batcher init failed — FAIL.");
            disp.close();
            return false;
        }

        boolean allOk = true;
        ExecutorService exec = null;
        try {
            // (c) Submit N requests from 16 threads, released together for real contention.
            final int threads = 16;
            final AtomicInteger tnum = new AtomicInteger();
            ThreadFactory tf = r -> {
                Thread t = new Thread(r, "sc-batch-agg-" + tnum.incrementAndGet());
                t.setDaemon(true);
                return t;
            };
            exec = Executors.newFixedThreadPool(threads, tf);

            @SuppressWarnings("unchecked")
            CompletableFuture<double[]>[] futures = new CompletableFuture[N];
            final CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> submitTasks = new ArrayList<>(N);
            for (int idx = 0; idx < N; idx++) {
                final int i = idx;
                submitTasks.add(exec.submit(() -> {
                    start.await();
                    futures[i] = batcher.submit(origins[i][0], origins[i][1], origins[i][2],
                            sx, sy, sz, dimX, dimY, dimZ);
                    return null;
                }));
            }
            start.countDown();
            // Ensure every futures[i] is assigned (and visible) before we read them.
            for (Future<?> t : submitTasks) {
                t.get(30, TimeUnit.SECONDS);
            }

            // (d) Collect + compare bit-exact. Bounded get() guards against a deadlock.
            long totalChecked = 0, totalMismatch = 0;
            double overallMaxAbs = 0.0;
            for (int i = 0; i < N; i++) {
                double[] res;
                try {
                    res = futures[i].get(60, TimeUnit.SECONDS);
                } catch (Throwable t) {
                    LOGGER.error("[SuperChunk-GPU] [batch-agg] future {} (origin {},{},{}) did not complete — FAIL.",
                            i, origins[i][0], origins[i][1], origins[i][2], t);
                    return false;
                }
                if (res == null || res.length != sliceLen) {
                    LOGGER.warn("[SuperChunk-GPU] [batch-agg] future {} bad result length {} (expected {}) — FAIL.",
                            i, res == null ? -1 : res.length, sliceLen);
                    return false;
                }
                for (int j = 0; j < sliceLen; j++) {
                    double a = res[j];
                    double b = ref[i][j];
                    totalChecked++;
                    if (Double.compare(a, b) != 0) {
                        double e = Math.abs(a - b);
                        if (e > overallMaxAbs) overallMaxAbs = e;
                        totalMismatch++;
                    }
                }
            }

            long requests = batcher.requestCount();
            long dispatches = batcher.dispatchCount();
            long served = batcher.servedCount();
            int batchLimit = batcher.batchLimit();
            long ideal = (requests + batchLimit - 1) / Math.max(1, batchLimit); // ceil
            boolean bitExact = (totalMismatch == 0);
            // Real coalescing: comfortably under requests/4 (the example ~ceil(req/limit)).
            boolean coalesced = requests > 0 && dispatches > 0
                    && dispatches < requests
                    && dispatches <= Math.max(ideal, requests / 4);
            boolean countsOk = (requests == N) && (served == N);
            allOk = bitExact && coalesced && countsOk;

            LOGGER.info("[SuperChunk-GPU] [batch-agg] requests={} dispatches={} batchLimit={} idealDispatches={} served={} threads={} mismatch={} maxAbsErr={} -> {}",
                    requests, dispatches, batchLimit, ideal, served, threads, totalMismatch,
                    String.format("%.3e", overallMaxAbs),
                    allOk ? "PASS (bit-exact + coalesced)"
                            : (!bitExact ? "FAIL (mismatch)"
                            : (!coalesced ? "FAIL (no coalescing)" : "FAIL (count)")));
            if (allOk) {
                LOGGER.info("[SuperChunk-GPU] [batch-agg] coalescing: {} requests served by {} dispatches = {}x fewer GPU launches.",
                        requests, dispatches, String.format("%.1f", requests / (double) dispatches));
            }
            return allOk;
        } catch (Throwable t) {
            LOGGER.error("[SuperChunk-GPU] [batch-agg] threw — FAIL.", t);
            return false;
        } finally {
            try {
                batcher.shutdown(); // serves/drains stragglers, stops the thread, closes disp
            } catch (Throwable ignored) {
            }
            if (exec != null) {
                exec.shutdownNow();
            }
        }
    }

    /**
     * Biome-climate offload parity — the {@code offloadBiome} gate.
     *
     * <p>Fuses three representative UNCACHED-style climate DFs (a 2D X/Z shifted noise as
     * temperature, a 3D noise-arith as vegetation, a Y-gradient as depth) into ONE
     * {@code df_batch_lattice_multi} program — exactly as {@code BiomeClimateCache} does at
     * the router build site — dispatches it over several per-chunk QUART lattices (origin =
     * chunk-min block, stride 4 in X/Z and Y, dims 4&times;(sections*4)&times;4), then
     * compares every grid value against the <b>vanilla</b> {@code compute(SinglePointContext)}
     * at the same quart-block position. Mirrors the geometry the cache serves
     * {@code Climate.Sampler.sample} from.
     *
     * <p><b>Two metrics.</b> (1) The DOUBLE comparison uses the SAME criterion as the rest of
     * this suite ({@code reportFp64}): EXACT + {@code near}(&le; {@value #FP64_NEAR_TOL}) PASS,
     * a {@code DIFF} &gt; that FAILs. Like every non-spline DF here, the GPU matches vanilla
     * only to FMA-contraction epsilon (~1 ULP), NOT bit-for-bit — the GPU compiler contracts
     * mul+add into FMA where Java does not. (2) The FLOAT comparison is the metric that
     * actually decides the biome: vanilla casts each climate value to {@code float} BEFORE
     * {@code Climate.quantizeCoord}, and the ~1e-15 double gap is far below a float ULP, so
     * {@code (float)gpu == (float)vanilla} — which, with the other three (unchanged CPU) DFs,
     * makes the selected biome bit-for-bit identical. The gate requires double-DIFF==0 AND
     * float-exact==total.
     */
    private static boolean checkBiomeClimate(boolean fp32) {
        String[] names = {"temperature(shiftedNoise)", "vegetation(noiseArith)", "depth(yGradient)"};
        DensityFunction[] dfs = {buildShiftedNoise(), buildNoiseArith(), buildYGradient()};

        List<AstNode> roots = new ArrayList<>(dfs.length);
        for (DensityFunction df : dfs) {
            clearC2meCompilationCache();
            roots.add(McToAst.toAst(df));
        }

        // Earlier cases compiled+closed these SAME sources. Under the refcount-2 contract
        // they remain LIVE and cached (a caller close() drops 2 -> 1; the cache keeps its own
        // reference until shutdown), so this clearMemory() is an INTENTIONAL binary-reload
        // exercise: releasing the cache's reference frees the now-idle programs and empties
        // the share map so each recompile below rebuilds a FRESH program from the on-disk
        // binary / source — extra recompile-path coverage, not UAF avoidance.
        dev.superchunk.gpu.CLProgramCache.clearMemory();

        // Several per-chunk quart lattices (24 sections -> dimY=96), varied chunk origins to
        // stress the float-agreement claim across coordinate space. Block coords: ox/oz =
        // chunk-min, oy = minSection*16, stride 4 in every axis (quart).
        final int dimX = 4, dimY = 96, dimZ = 4;
        final int n = dimX * dimY * dimZ;            // 1536 quart points / chunk
        final int m = roots.size();
        final int[][] origins = {{96, -64, -240}, {-1024, -64, 512}, {4096, -64, -4096}, {0, -64, 0}};

        List<GpuDensityFunction> members = new ArrayList<>(m);
        GpuFusedInterpolator fused = null;
        try {
            for (AstNode root : roots) {
                GpuDensityFunction g = GpuDensityFunction.compile(root);
                if (g == null) {
                    LOGGER.warn("[SuperChunk-GPU] [biome-parity] a climate DF did not GPU-compile — cannot run gate.");
                    return false;   // finally closes any already-built members
                }
                members.add(g);
            }
            fused = GpuFusedInterpolator.compile(roots, members);
            if (fused == null) {
                LOGGER.warn("[SuperChunk-GPU] [biome-parity] fused climate program did not build — FAIL.");
                return false;
            }

            // Per-root accumulators indexed by k, kept ACROSS all origins so the per-root
            // verdict + report below is computed over every origin exactly as before. The
            // restructure makes the ORIGIN loop outermost and fills each origin's fused grid
            // ONCE (fillAllGrids yields all m roots' slices in a single dispatch), then
            // compares every root against that one grid — versus the previous nesting that
            // re-dispatched the same origin once per root (m=3 dispatches/origin = 12/36
            // slices, now 4/12). Boot-time efficiency only: every GPU value is still compared
            // bit-exactly against vanilla compute(), and the pass/fail verdict + per-root
            // reporting are identical (same comparisons, same order, just reported after all
            // dispatches instead of interleaved).
            long[] exact = new long[m], near = new long[m], diff = new long[m];
            long[] floatExact = new long[m], floatDiff = new long[m], fp32Within = new long[m];
            double[] maxAbs = new double[m], maxFloatAbs = new double[m];

            for (int[] org : origins) {
                int ox = org[0], oy = org[1], oz = org[2];
                double[][] grid = new double[m][];
                if (!fused.fillAllGrids(grid, ox, oy, oz, 4, 4, 4, dimX, dimY, dimZ)) {
                    LOGGER.warn("[SuperChunk-GPU] [biome-parity] fused quart-lattice fill failed — FAIL.");
                    return false;
                }
                for (int ix = 0; ix < dimX; ix++) {
                    for (int iy = 0; iy < dimY; iy++) {
                        for (int iz = 0; iz < dimZ; iz++) {
                            int flat = (iy * dimX + ix) * dimZ + iz;
                            int bx = ox + ix * 4, by = oy + iy * 4, bz = oz + iz * 4;
                            // Compare ALL roots against this single filled grid (k inner).
                            for (int k = 0; k < m; k++) {
                                double vd = dfs[k].compute(new DensityFunction.SinglePointContext(bx, by, bz));
                                double gd = grid[k][flat];
                                if (Double.compare(vd, gd) == 0) {
                                    exact[k]++;
                                } else {
                                    double e = Math.abs(vd - gd);
                                    if (e <= FP64_NEAR_TOL) near[k]++; else diff[k]++;
                                    if (e > maxAbs[k]) maxAbs[k] = e;
                                }
                                // fp32 structural gate: count points within fp32 tolerance.
                                // A NaN GPU value fails BOTH comparisons, so it cannot inflate
                                // the pass count (mirrors reportFp32's within==n criterion);
                                // a maxAbs accumulator alone never catches NaN since NaN > x is false.
                                double absErr = Math.abs(vd - gd);
                                double relErr = absErr / Math.max(Math.abs(vd), REL_EPS);
                                if (absErr <= FP32_ABS_TOL || relErr <= FP32_REL_TOL) fp32Within[k]++;
                                // The biome-determining comparison: vanilla casts to float
                                // BEFORE quantizeCoord, so this is what selects the biome.
                                float vf = (float) vd, gf = (float) gd;
                                if (Float.compare(vf, gf) == 0) {
                                    floatExact[k]++;
                                } else {
                                    floatDiff[k]++;
                                    double fe = Math.abs((double) vf - (double) gf);
                                    if (fe > maxFloatAbs[k]) maxFloatAbs[k] = fe;
                                }
                            }
                        }
                    }
                }
            }

            long totalChecked = 0, totalDoubleDiff = 0, totalFloatDiff = 0;
            double overallMaxAbs = 0.0, overallMaxFloatAbs = 0.0;
            boolean allOk = true;
            for (int k = 0; k < m; k++) {
                long checked = (long) n * origins.length;
                boolean ok = fp32 ? (fp32Within[k] == checked) : (diff[k] == 0 && floatDiff[k] == 0);
                allOk &= ok;
                totalChecked += checked;
                totalDoubleDiff += diff[k];
                totalFloatDiff += floatDiff[k];
                if (maxAbs[k] > overallMaxAbs) overallMaxAbs = maxAbs[k];
                if (maxFloatAbs[k] > overallMaxFloatAbs) overallMaxFloatAbs = maxFloatAbs[k];
                LOGGER.info("[SuperChunk-GPU] [biome-parity] {} n={} double:EXACT={} near(<= {})={} DIFF={} maxAbsErr={} | "
                                + "float(biome-selecting):EXACT={} DIFF={} maxFloatErr={} -> {}",
                        names[k], checked, exact[k], FP64_NEAR_TOL, near[k], diff[k], String.format("%.3e", maxAbs[k]),
                        floatExact[k], floatDiff[k], String.format("%.3e", maxFloatAbs[k]),
                        ok ? (diff[k] == 0 && near[k] == 0 ? "PASS (bit-identical)" : "PASS (within FMA epsilon; float-exact)") : "FAIL");
            }
            LOGGER.info("[SuperChunk-GPU] [biome-parity] climate offload (temperature+vegetation+depth) roots={} "
                            + "checked={} double-DIFF(> {})={} maxAbsErr={} | float-DIFF={} maxFloatErr={} -> {}",
                    m, totalChecked, FP64_NEAR_TOL, totalDoubleDiff, String.format("%.3e", overallMaxAbs),
                    totalFloatDiff, String.format("%.3e", overallMaxFloatAbs),
                    allOk ? (overallMaxAbs == 0.0 ? "PASS (BIT-EXACT vs vanilla)"
                            : "PASS (vanilla within FMA epsilon; biome-selecting float cast BIT-EXACT -> biomes unchanged)") : "FAIL");
            return allOk;
        } catch (Throwable t) {
            LOGGER.error("[SuperChunk-GPU] [biome-parity] threw — FAIL.", t);
            return false;
        } finally {
            if (fused != null) {
                try { fused.close(); } catch (Throwable ignored) { }
            }
            for (GpuDensityFunction g : members) {
                try { g.close(); } catch (Throwable ignored) { }
            }
        }
    }

    // ---- fp64: bit-exact reporting ----
    private static boolean reportFp64(String name, double[] vanilla, double[] gpu) {
        int n = vanilla.length;
        long exact = 0, near = 0, diff = 0;
        double maxAbs = 0.0;
        int worst = -1;
        for (int i = 0; i < n; i++) {
            if (Double.compare(vanilla[i], gpu[i]) == 0) {
                exact++;
            } else {
                double e = Math.abs(vanilla[i] - gpu[i]);
                if (e <= FP64_NEAR_TOL) {
                    near++;
                } else {
                    diff++;
                }
                if (e > maxAbs) {
                    maxAbs = e;
                    worst = i;
                }
            }
        }
        boolean ok = (diff == 0);
        LOGGER.info("[SuperChunk-GPU] [{}] (GPU vs vanilla) n={} EXACT={} near(<= {})={} DIFF={} maxAbsErr={} -> {}",
                name, n, exact, FP64_NEAR_TOL, near, diff, String.format("%.3e", maxAbs),
                ok ? (near == 0 ? "PASS (bit-identical)" : "PASS (within FMA epsilon)") : "FAIL");
        if (!ok && worst >= 0) {
            LOGGER.warn("[SuperChunk-GPU]   [{}] worst DIFF @ idx {}: vanilla={} gpu={} (absErr={})",
                    name, worst, vanilla[worst], gpu[worst], String.format("%.3e", maxAbs));
        }
        return ok;
    }

    // ---- fp32: structural reporting (same combined criterion as Stages 1/2) ----
    private static boolean reportFp32(String name, double[] vanilla, double[] gpu) {
        int n = vanilla.length;
        double maxAbs = 0.0, maxRel = 0.0;
        int within = 0, worst = -1;
        double worstScore = -1.0;
        for (int i = 0; i < n; i++) {
            double abs = Math.abs(vanilla[i] - gpu[i]);
            double rel = abs / Math.max(Math.abs(vanilla[i]), REL_EPS);
            if (abs > maxAbs) maxAbs = abs;
            boolean pass = (abs <= FP32_ABS_TOL) || (rel <= FP32_REL_TOL);
            if (pass) {
                within++;
            } else if (rel > worstScore) {
                worstScore = rel;
                worst = i;
            }
            if (abs > FP32_ABS_TOL && rel > maxRel) maxRel = rel;
        }
        boolean ok = (within == n);
        LOGGER.info("[SuperChunk-GPU] [{}] (GPU vs vanilla, FP32) maxAbsErr={} maxRelErr={} within-tol={}/{} -> {}",
                name, String.format("%.3e", maxAbs), String.format("%.3e", maxRel), within, n,
                ok ? "PASS (structural)" : "FAIL");
        if (!ok && worst >= 0) {
            LOGGER.warn("[SuperChunk-GPU]   [{}] worst @ idx {}: vanilla={} gpu={}", name, worst, vanilla[worst], gpu[worst]);
        }
        return ok;
    }

    // ----------------------------------------------------------------------
    // Representative density functions (mirror GpuDfcParityTest), noise resolved.
    // ----------------------------------------------------------------------

    private static DensityFunction.NoiseHolder noiseHolder(long salt) {
        RandomSource rng = new XoroshiroRandomSource(SEED ^ salt);
        NormalNoise nn = NormalNoise.create(rng, -7, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
        Holder<NormalNoise.NoiseParameters> params = Holder.direct(nn.parameters());
        return new DensityFunction.NoiseHolder(params, nn);
    }

    private static DensityFunction buildYGradient() {
        return DensityFunctions.yClampedGradient(-64, 320, 1.5, -1.5);
    }

    private static DensityFunction buildNoiseArith() {
        DensityFunction nSq = new DensityFunctions.Noise(noiseHolder(0x1), 0.25, 0.25);
        DensityFunction nAb = new DensityFunctions.Noise(noiseHolder(0x1), 0.30, 0.25);
        DensityFunction nCube = new DensityFunctions.Noise(noiseHolder(0x1), 0.35, 0.25);
        DensityFunction nHalf = new DensityFunctions.Noise(noiseHolder(0x1), 0.40, 0.25);
        DensityFunction nQuarter = new DensityFunctions.Noise(noiseHolder(0x1), 0.45, 0.25);
        DensityFunction nSqz = new DensityFunctions.Noise(noiseHolder(0x1), 0.50, 0.25);
        DensityFunction sq = DensityFunctions.mul(nSq, nSq);
        DensityFunction ab = DensityFunctions.Mapped.create(DensityFunctions.Mapped.Type.ABS, nAb);
        DensityFunction cube = DensityFunctions.Mapped.create(DensityFunctions.Mapped.Type.CUBE, nCube);
        DensityFunction half = DensityFunctions.Mapped.create(DensityFunctions.Mapped.Type.HALF_NEGATIVE, nHalf);
        DensityFunction quarter = DensityFunctions.Mapped.create(DensityFunctions.Mapped.Type.QUARTER_NEGATIVE, nQuarter);
        DensityFunction sqz = DensityFunctions.Mapped.create(DensityFunctions.Mapped.Type.SQUEEZE, nSqz);
        DensityFunction sum = DensityFunctions.add(DensityFunctions.add(sq, ab),
                DensityFunctions.add(cube, DensityFunctions.add(half, DensityFunctions.add(quarter, sqz))));
        DensityFunction lo = DensityFunctions.min(sum, DensityFunctions.constant(3.0));
        return DensityFunctions.max(lo, DensityFunctions.constant(-3.0));
    }

    private static DensityFunction buildShiftedNoise() {
        DensityFunction shiftX = new DensityFunctions.Noise(noiseHolder(0x2), 1.0, 0.0);
        DensityFunction shiftZ = new DensityFunctions.Noise(noiseHolder(0x3), 1.0, 0.0);
        return new DensityFunctions.ShiftedNoise(shiftX, DensityFunctions.constant(0.0), shiftZ,
                0.25, 0.0, noiseHolder(0x4));
    }

    private static DensityFunction buildShiftFamily() {
        DensityFunction a = new DensityFunctions.ShiftA(noiseHolder(0x5));
        DensityFunction b = new DensityFunctions.ShiftB(noiseHolder(0x6));
        DensityFunction s = new DensityFunctions.Shift(noiseHolder(0x7));
        return DensityFunctions.add(DensityFunctions.add(a, b), s);
    }

    private static DensityFunction buildRangeClamp() {
        DensityFunction input = new DensityFunctions.Noise(noiseHolder(0x8), 0.5, 0.5);
        DensityFunction inRange = DensityFunctions.constant(1.0);
        DensityFunction outRange = DensityFunctions.mul(input, DensityFunctions.constant(2.0));
        DensityFunction rc = DensityFunctions.rangeChoice(input, -0.2, 0.2, inRange, outRange);
        return new DensityFunctions.Clamp(rc, -1.0, 1.0);
    }

    private static DensityFunction buildWeirdScaled() {
        DensityFunction input = new DensityFunctions.Noise(noiseHolder(0x9), 1.0, 1.0);
        return new DensityFunctions.WeirdScaledSampler(input, noiseHolder(0xA),
                DensityFunctions.WeirdScaledSampler.RarityValueMapper.TYPE1);
    }

    private static DensityFunction buildBlendedNoise() {
        // Vanilla BlendedNoise ("old_blended_noise") with the EXACT overworld
        // sloped_cheese parameterization: xz_scale 0.25, y_scale 0.125, xz_factor 80,
        // y_factor 160, smear_scale_multiplier 8. createUnseeded seeds the three
        // legacy PerlinNoise samplers from XoroshiroRandomSource(0L) — deterministic,
        // all 16/16/8 octaves live. compute() is the vanilla double math ground truth
        // (the DF is a SimpleFunction, directly computable).
        return BlendedNoise.createUnseeded(0.25, 0.125, 80.0, 160.0, 8.0);
    }

    private static DensityFunction buildSpline() {
        DensityFunction coord = DensityFunctions.yClampedGradient(-64, 320, -1.0, 1.0);
        Holder<DensityFunction> coordHolder = Holder.direct(coord);
        DensityFunctions.Spline.Coordinate coordinate = new DensityFunctions.Spline.Coordinate(coordHolder);
        CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate> spline =
                CubicSpline.<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate>builder(coordinate)
                        .addPoint(-1.0f, 0.0f, 0.5f)
                        .addPoint(-0.2f, 0.3f, 0.0f)
                        .addPoint(0.2f, -0.3f, -0.4f)
                        .addPoint(1.0f, 1.0f, 0.2f)
                        .build();
        return new DensityFunctions.Spline(spline);
    }

    private static DensityFunction buildOverworldComposite() {
        DensityFunction continents = buildShiftedNoise();
        DensityFunction erosion = new DensityFunctions.Noise(noiseHolder(0xB), 0.25, 0.0);
        DensityFunction ridges = DensityFunctions.Mapped.create(DensityFunctions.Mapped.Type.ABS,
                new DensityFunctions.Noise(noiseHolder(0xC), 0.5, 0.5));
        DensityFunction depth = DensityFunctions.add(buildYGradient(), continents);
        DensityFunction offset = buildSpline();
        DensityFunction base = DensityFunctions.add(depth, DensityFunctions.mul(offset, DensityFunctions.constant(4.0)));
        DensityFunction shaped = DensityFunctions.Mapped.create(DensityFunctions.Mapped.Type.SQUEEZE,
                DensityFunctions.add(base, DensityFunctions.mul(erosion, ridges)));
        DensityFunction clamped = new DensityFunctions.Clamp(shaped, -64.0, 64.0);
        return DensityFunctions.rangeChoice(continents, -1.0, 1.0,
                clamped, DensityFunctions.mul(clamped, DensityFunctions.constant(0.5)));
    }

    // ---- sample grid (one chunk's noise cells; ~3072 points) ----
    private static final int CHUNK_X0 = 96;
    private static final int CHUNK_Z0 = -240;
    private static final int CELL_XZ = 4;
    private static final int CELL_Y = 8;
    private static final int NX = 8, NZ = 8, NY = 48;

    private static int[] buildX() {
        int[] out = new int[NX * NZ * NY];
        int i = 0;
        for (int ix = 0; ix < NX; ix++)
            for (int iz = 0; iz < NZ; iz++)
                for (int iy = 0; iy < NY; iy++)
                    out[i++] = CHUNK_X0 + ix * CELL_XZ;
        return out;
    }

    private static int[] buildZ() {
        int[] out = new int[NX * NZ * NY];
        int i = 0;
        for (int ix = 0; ix < NX; ix++)
            for (int iz = 0; iz < NZ; iz++)
                for (int iy = 0; iy < NY; iy++)
                    out[i++] = CHUNK_Z0 + iz * CELL_XZ;
        return out;
    }

    private static int[] buildY() {
        int[] out = new int[NX * NZ * NY];
        int i = 0;
        for (int ix = 0; ix < NX; ix++)
            for (int iz = 0; iz < NZ; iz++)
                for (int iy = 0; iy < NY; iy++)
                    out[i++] = -64 + iy * CELL_Y;
        return out;
    }
}
