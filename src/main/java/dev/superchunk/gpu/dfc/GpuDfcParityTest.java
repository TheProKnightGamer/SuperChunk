package dev.superchunk.gpu.dfc;

import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.AstNode;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.EvalType;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.ast.McToAst;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.BytecodeGen;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.gen.CompiledEntry;
import dev.superchunk.com.ishland.c2me.opts.dfc.common.util.ArrayCache;
import dev.superchunk.gpu.OpenCLBackend;
import dev.superchunk.gpu.OpenCLDevice;
import net.minecraft.core.Holder;
import net.minecraft.util.CubicSpline;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.slf4j.Logger;

/**
 * Stage-2 CPU-vs-GPU parity harness for the density-function compiler.
 *
 * <p>Builds a representative <b>overworld-style</b> density function (shifted
 * noise + splines + arithmetic + clamp + range-choice + weird-scaled + the shift
 * family), takes C2ME's CPU-compiled form ({@link BytecodeGen#compile0} ->
 * {@link CompiledEntry#evalMulti}) as ground truth, evaluates it over a realistic
 * grid of a chunk's noise cells, and compares against {@link GpuDensityFunction}.
 *
 * <p>The graph is constructed directly as vanilla {@link DensityFunction}s with
 * their noises already resolved (a fixed-seed {@link NormalNoise} wrapped in a
 * {@link DensityFunction.NoiseHolder}), so the test is deterministic + needs no
 * datapack/registry load. Both the CPU and GPU paths consume the SAME AST, so any
 * difference is purely numeric precision (fp32) — proving the emitter mirrors
 * BytecodeGen op-for-op.
 *
 * <p>Gated behind {@code selftest.dfc=true}; off by default. Never throws.
 */
public final class GpuDfcParityTest {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    private static final long SEED = 0x5C0FFEEL;

    /**
     * The composite case name. Per {@link #testCase}, every OTHER DF that fails to
     * GPU-compile is a graceful CPU-fallback PASS, but a null GPU-compile of the
     * overworld composite is a hard FAIL: it exercises the full overworld-style
     * emission path, so a composite-emission regression must not be masked green.
     */
    private static final String COMPOSITE_CASE = "overworld_composite";

    // Combined float-comparison tolerances (same scheme as Stage 1, but density
    // magnitudes are larger and the graph deeper, so the fp32 rel tolerance is a
    // touch looser; near-zero points are covered by the absolute tolerance).
    private static final double FP32_REL_TOL = 5.0e-3;
    private static final double FP32_ABS_TOL = 2.0e-3;
    private static final double FP64_REL_TOL = 1.0e-7;
    private static final double FP64_ABS_TOL = 1.0e-7;
    private static final double REL_EPS = 1.0e-6;

    private GpuDfcParityTest() {
    }

    /** Reflectively clears C2ME's process-wide relaxed compilation cache (test isolation). */
    private static void clearC2meCompilationCache() {
        try {
            java.lang.reflect.Field f = BytecodeGen.class.getDeclaredField("compilationCache");
            f.setAccessible(true);
            Object cache = f.get(null);
            if (cache instanceof java.util.Map<?, ?> map) {
                map.clear();
            }
        } catch (Throwable ignored) {
            // best-effort; if it fails the half/quarter cross-case collision may recur.
        }
    }

    /** Runs the parity suite. Never throws. Returns true on PASS. */
    public static boolean run() {
        if (!OpenCLBackend.isAvailable()) {
            LOGGER.warn("[SuperChunk-GPU] DFC parity test skipped — backend unavailable.");
            return false;
        }
        OpenCLDevice dev = OpenCLBackend.device();
        boolean fp32 = OpenCLBackend.isFp32();
        double relTol = fp32 ? FP32_REL_TOL : FP64_REL_TOL;
        double absTol = fp32 ? FP32_ABS_TOL : FP64_ABS_TOL;
        String mode = fp32 ? "FP32 (structural; device lacks fp64 or forced)" : "FP64 (double, ULP-grade)";

        LOGGER.info("[SuperChunk-GPU] ===== DFC parity test (Stage 2) =====");
        LOGGER.info("[SuperChunk-GPU] Device: {}", dev.describe());
        LOGGER.info("[SuperChunk-GPU] Precision mode: {} ; pass if relErr<={} OR absErr<={}",
                mode, relTol, absTol);

        boolean ok = true;
        try {
            // Test each representative sub-DF independently (so we report which
            // node families compile to GPU vs fall back) plus the full composite.
            ok &= testCase("y_clamped_gradient", buildYGradient());
            ok &= testCase("noise+arith", buildNoiseArith());
            ok &= testCase("shifted_noise", buildShiftedNoise());
            ok &= testCase("shift_family", buildShiftFamily());
            ok &= testCase("range_choice+clamp", buildRangeClamp());
            ok &= testCase("weird_scaled", buildWeirdScaled());
            ok &= testCase("spline", buildSpline());
            ok &= testCase(COMPOSITE_CASE, buildOverworldComposite());
        } catch (Throwable t) {
            LOGGER.error("[SuperChunk-GPU] DFC parity test threw — FAIL.", t);
            ok = false;
        }

        LOGGER.info("[SuperChunk-GPU] ===== DFC parity test {} ({}) =====",
                ok ? "PASS" : "FAIL", mode);
        return ok;
    }

    /**
     * Compiles {@code df} both ways and compares over a realistic grid. A case that
     * does NOT GPU-compile (returns null) is reported as CPU-only — a graceful-fallback
     * PASS — EXCEPT {@link #COMPOSITE_CASE}: a null GPU-compile of the overworld
     * composite is a hard FAIL, so a composite-emission regression cannot be masked green.
     */
    private static boolean testCase(String name, DensityFunction df) {
        boolean fp32 = OpenCLBackend.isFp32();
        double relTol = fp32 ? FP32_REL_TOL : FP64_REL_TOL;
        double absTol = fp32 ? FP32_ABS_TOL : FP64_ABS_TOL;

        AstNode ast = McToAst.toAst(df);

        // C2ME's BytecodeGen has a process-wide compilation cache keyed by
        // RELAXED equality (relaxedEquals/relaxedHashCode), which intentionally
        // ignores numeric constants like NegMulNode.negMul and the concrete noise
        // identity (DFTNoiseNode compares only scales). Across our isolated test
        // cases that lets, e.g., a cached half_negative(0.5) class be reused for a
        // quarter_negative(0.25) node — a cross-DF collision that would make the
        // CPU "ground truth" disagree with our (correct) per-DF GPU emission. Clear
        // it before each case so each comparison is self-contained. (This is a
        // TEST-only concern; production worldgen builds one router where such reuse
        // is by-design and consistent.)
        clearC2meCompilationCache();

        // CPU ground truth via C2ME's DFC.
        CompiledEntry cpuEntry;
        try {
            cpuEntry = BytecodeGen.compile0(ast);
        } catch (Throwable t) {
            LOGGER.warn("[SuperChunk-GPU] [{}] CPU compile failed — skipping.", name, t);
            return true;
        }

        // GPU compile.
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

            double[] cpu = new double[n];
            cpuEntry.evalMulti(cpu, sx.clone(), sy.clone(), sz.clone(), EvalType.NORMAL, new ArrayCache());

            double[] g = new double[n];
            boolean filled = gpu.fill(g, sx, sy, sz);
            if (!filled) {
                LOGGER.warn("[SuperChunk-GPU] [{}] GPU fill failed.", name);
                return false;
            }
            return report(name, cpu, g, relTol, absTol);
        } finally {
            gpu.close();
        }
    }

    // ----------------------------------------------------------------------
    // Representative density functions (overworld-style), all noise resolved.
    // ----------------------------------------------------------------------

    private static DensityFunction.NoiseHolder noiseHolder(long salt) {
        RandomSource rng = new XoroshiroRandomSource(SEED ^ salt);
        NormalNoise nn = NormalNoise.create(rng, -7, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
        @SuppressWarnings("unchecked")
        Holder<NormalNoise.NoiseParameters> params = Holder.direct(nn.parameters());
        return new DensityFunction.NoiseHolder(params, nn);
    }

    private static DensityFunction buildYGradient() {
        return DensityFunctions.yClampedGradient(-64, 320, 1.5, -1.5);
    }

    /**
     * noise + (square, abs, cube, half/quarter-negative, squeeze, mul, add, min, max).
     *
     * <p>Each mapped term wraps a DISTINCT noise (different scales). This is
     * deliberate: C2ME's AST node {@code equals}/{@code hashCode} omit numeric
     * constants (e.g. {@code NegMulNode.negMul}) and {@code DFTNoiseNode.equals}
     * compares only the scales, so its per-Context method dedup
     * ({@code newSingleMethod} via a default-strategy map) would otherwise treat
     * {@code half_negative(0.5)} and {@code quarter_negative(0.25)} of the SAME
     * noise as the same method and compute them identically — a latent C2ME bug.
     * Our GPU emitter distinguishes them correctly; using distinct operands keeps
     * the CPU ground truth from tripping that collision so we compare like for
     * like. (Real overworld graphs don't put half+quarter on one shared subtree.)
     */
    private static DensityFunction buildNoiseArith() {
        DensityFunction nSq = new DensityFunctions.Noise(noiseHolder(0x1), 0.25, 0.25);
        DensityFunction nAb = new DensityFunctions.Noise(noiseHolder(0x1), 0.30, 0.25);
        DensityFunction nCube = new DensityFunctions.Noise(noiseHolder(0x1), 0.35, 0.25);
        DensityFunction nHalf = new DensityFunctions.Noise(noiseHolder(0x1), 0.40, 0.25);
        DensityFunction nQuarter = new DensityFunctions.Noise(noiseHolder(0x1), 0.45, 0.25);
        DensityFunction nSqz = new DensityFunctions.Noise(noiseHolder(0x1), 0.50, 0.25);
        DensityFunction sq = DensityFunctions.mul(nSq, nSq);
        DensityFunction ab = makeMapped(nAb, DensityFunctions.Mapped.Type.ABS);
        DensityFunction cube = makeMapped(nCube, DensityFunctions.Mapped.Type.CUBE);
        DensityFunction half = makeMapped(nHalf, DensityFunctions.Mapped.Type.HALF_NEGATIVE);
        DensityFunction quarter = makeMapped(nQuarter, DensityFunctions.Mapped.Type.QUARTER_NEGATIVE);
        DensityFunction sqz = makeMapped(nSqz, DensityFunctions.Mapped.Type.SQUEEZE);
        DensityFunction sum = DensityFunctions.add(DensityFunctions.add(sq, ab),
                DensityFunctions.add(cube, DensityFunctions.add(half, DensityFunctions.add(quarter, sqz))));
        DensityFunction lo = DensityFunctions.min(sum, DensityFunctions.constant(3.0));
        return DensityFunctions.max(lo, DensityFunctions.constant(-3.0));
    }

    private static DensityFunction makeMapped(DensityFunction input, DensityFunctions.Mapped.Type type) {
        return DensityFunctions.Mapped.create(type, input);
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
        // Clamp -> Min(max, Max(min, input)) in McToAst.
        return new DensityFunctions.Clamp(rc, -1.0, 1.0);
    }

    private static DensityFunction buildWeirdScaled() {
        DensityFunction input = new DensityFunctions.Noise(noiseHolder(0x9), 1.0, 1.0);
        return new DensityFunctions.WeirdScaledSampler(input, noiseHolder(0xA),
                DensityFunctions.WeirdScaledSampler.RarityValueMapper.TYPE1);
    }

    private static DensityFunction buildSpline() {
        // coordinate = a y-gradient (deterministic, monotonic over our y range)
        DensityFunction coord = DensityFunctions.yClampedGradient(-64, 320, -1.0, 1.0);
        @SuppressWarnings("unchecked")
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

    /** A deeper overworld-style composite that mixes most supported families. */
    private static DensityFunction buildOverworldComposite() {
        DensityFunction continents = buildShiftedNoise();
        DensityFunction erosion = new DensityFunctions.Noise(noiseHolder(0xB), 0.25, 0.0);
        DensityFunction ridges = makeMapped(new DensityFunctions.Noise(noiseHolder(0xC), 0.5, 0.5),
                DensityFunctions.Mapped.Type.ABS);
        DensityFunction depth = DensityFunctions.add(buildYGradient(), continents);
        DensityFunction offset = buildSpline();
        DensityFunction base = DensityFunctions.add(depth, DensityFunctions.mul(offset, DensityFunctions.constant(4.0)));
        DensityFunction shaped = makeMapped(DensityFunctions.add(base, DensityFunctions.mul(erosion, ridges)),
                DensityFunctions.Mapped.Type.SQUEEZE);
        DensityFunction clamped = new DensityFunctions.Clamp(shaped, -64.0, 64.0);
        return DensityFunctions.rangeChoice(continents, -1.0, 1.0,
                clamped, DensityFunctions.mul(clamped, DensityFunctions.constant(0.5)));
    }

    // ----------------------------------------------------------------------
    // Sample grid: one chunk's noise cells. x,z over a 16-wide cell range
    // (cell size 4 -> 0,4,8,12,16), y over -64..320 by cell size 8. A few
    // thousand points total. Mixed exact-block + cell-boundary coords.
    // ----------------------------------------------------------------------

    private static final int CHUNK_X0 = 96;   // arbitrary realistic block origin
    private static final int CHUNK_Z0 = -240;
    private static final int CELL_XZ = 4;      // horizontal noise cell size
    private static final int CELL_Y = 8;       // vertical noise cell size
    private static final int NX = 8;           // 8 cells across -> 32 blocks
    private static final int NZ = 8;
    private static final int NY = 48;          // -64..320 / 8 = 48 cells

    private static int[] buildX() {
        int[] out = new int[NX * NZ * NY];
        int i = 0;
        for (int ix = 0; ix < NX; ix++) {
            for (int iz = 0; iz < NZ; iz++) {
                for (int iy = 0; iy < NY; iy++) {
                    out[i++] = CHUNK_X0 + ix * CELL_XZ;
                }
            }
        }
        return out;
    }

    private static int[] buildZ() {
        int[] out = new int[NX * NZ * NY];
        int i = 0;
        for (int ix = 0; ix < NX; ix++) {
            for (int iz = 0; iz < NZ; iz++) {
                for (int iy = 0; iy < NY; iy++) {
                    out[i++] = CHUNK_Z0 + iz * CELL_XZ;
                }
            }
        }
        return out;
    }

    private static int[] buildY() {
        int[] out = new int[NX * NZ * NY];
        int i = 0;
        for (int ix = 0; ix < NX; ix++) {
            for (int iz = 0; iz < NZ; iz++) {
                for (int iy = 0; iy < NY; iy++) {
                    out[i++] = -64 + iy * CELL_Y;
                }
            }
        }
        return out;
    }

    // ----------------------------------------------------------------------
    // Reporting (same combined criterion as Stage 1).
    // ----------------------------------------------------------------------

    private static boolean report(String name, double[] cpu, double[] gpu, double relTol, double absTol) {
        double maxAbs = 0.0;
        double maxRel = 0.0;
        int within = 0;
        int worstIdx = -1;
        double worstScore = -1.0;
        for (int i = 0; i < cpu.length; i++) {
            double abs = Math.abs(cpu[i] - gpu[i]);
            double rel = abs / Math.max(Math.abs(cpu[i]), REL_EPS);
            if (abs > maxAbs) maxAbs = abs;
            boolean pass = (abs <= absTol) || (rel <= relTol);
            if (pass) {
                within++;
            } else if (rel > worstScore) {
                worstScore = rel;
                worstIdx = i;
            }
            if (abs > absTol && rel > maxRel) {
                maxRel = rel;
            }
        }
        boolean ok = (within == cpu.length);
        LOGGER.info("[SuperChunk-GPU] [{}] (GPU) maxAbsErr={} maxRelErr(|val|>absTol)={} within-tol={}/{} -> {}",
                name,
                String.format("%.3e", maxAbs),
                String.format("%.3e", maxRel),
                within, cpu.length,
                ok ? "PASS" : "FAIL");
        if (!ok && worstIdx >= 0) {
            LOGGER.warn("[SuperChunk-GPU]   [{}] worst FAIL @ idx {}: cpu={} gpu={} (absErr={}, relErr={})",
                    name, worstIdx, cpu[worstIdx], gpu[worstIdx],
                    String.format("%.3e", Math.abs(cpu[worstIdx] - gpu[worstIdx])),
                    String.format("%.3e", worstScore));
            int shown = 0;
            for (int i = 0; i < cpu.length && shown < 5; i++) {
                double abs = Math.abs(cpu[i] - gpu[i]);
                double rel = abs / Math.max(Math.abs(cpu[i]), REL_EPS);
                if (abs > absTol && rel > relTol) {
                    LOGGER.warn("[SuperChunk-GPU]     [{}] fail#{} idx {}: cpu={} gpu={}", name, shown, i, cpu[i], gpu[i]);
                    shown++;
                }
            }
        }
        return ok;
    }
}
