package dev.superchunk.gpu.noise;

import dev.superchunk.gpu.CLProgram;
import dev.superchunk.gpu.OpenCLBackend;
import dev.superchunk.gpu.OpenCLDevice;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Stage-1 CPU-vs-GPU parity harness for SuperChunk's worldgen noise.
 *
 * <p>Constructs vanilla {@link ImprovedNoise}, {@link PerlinNoise} and
 * {@link NormalNoise} from a fixed seed, extracts their internal state
 * (permutation tables, octave offsets, amplitudes, factors, valueFactor),
 * uploads that state to the GPU, runs the {@code noise.cl} kernels over a few
 * thousand realistic worldgen sample points, and compares each kernel's output
 * against the actual vanilla (double-precision) sampler.
 *
 * <p><b>Precision.</b> The kernels are precision-parameterized. On a device
 * WITHOUT {@code cl_khr_fp64} (e.g. this laptop's Intel Iris Xe) the kernel is
 * built with {@code -DUSE_FP32} ({@code real == float}); we then validate
 * <i>structural</i> parity (relative error ~1e-3..1e-4 -> algorithm correct).
 * On an fp64 device (e.g. an RTX 3070) the same source builds {@code real ==
 * double} and should match vanilla to (near) ULP. The report clearly labels
 * which mode ran.
 *
 * <p>Never throws — logs PASS/FAIL and returns a boolean. Wired in behind the
 * {@code selftest.noise=true} GPU config key; off by default.
 */
public final class GpuNoiseParityTest {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;

    /** Resource path of the OpenCL kernel source. */
    private static final String KERNEL_RESOURCE = "/superchunk/kernels/noise.cl";

    /** Fixed seed so the test is deterministic + reproducible across machines. */
    private static final long SEED = 0x5C0FFEEL;

    /** Number of sample points (>= 4096 per task). */
    private static final int N = 8192;

    // A sample point PASSES if (relErr <= REL_TOL) OR (absErr <= ABS_TOL). This is
    // the standard combined float-comparison criterion: relative error is the right
    // measure for large values, but near a noise zero-crossing the value is ~0 and
    // a tiny absolute FP32 rounding error produces a huge *relative* error — there
    // the absolute tolerance is what matters. Noise output is ~[-1,1], so an
    // absolute tolerance a few times the FP32 epsilon of 1.0 captures those points
    // while still being a tight structural check.

    /** FP32-structural relative tolerance. ~1e-3 => algorithm correct (no port bug). */
    private static final double FP32_REL_TOL = 2.0e-3;
    /** FP32 absolute tolerance — covers near-zero-crossing points (FP32 noise ~1e-4 abs). */
    private static final double FP32_ABS_TOL = 5.0e-4;
    /** fp64 relative tolerance: essentially exact (allow a hair for FMA/order). */
    private static final double FP64_REL_TOL = 1.0e-9;
    /** fp64 absolute tolerance. */
    private static final double FP64_ABS_TOL = 1.0e-9;
    /** Floor for the rel-error denominator so the ratio is well-defined at ~0. */
    private static final double REL_EPS = 1.0e-6;

    private GpuNoiseParityTest() {
    }

    /** Runs the full parity suite. Never throws. Returns true if all three pass. */
    public static boolean run() {
        if (!OpenCLBackend.isAvailable()) {
            LOGGER.warn("[SuperChunk-GPU] Noise parity test skipped — backend unavailable.");
            return false;
        }
        OpenCLDevice dev = OpenCLBackend.device();
        boolean fp32 = OpenCLBackend.isFp32();
        double relTol = fp32 ? FP32_REL_TOL : FP64_REL_TOL;
        double absTol = fp32 ? FP32_ABS_TOL : FP64_ABS_TOL;
        String mode = fp32 ? "FP32 (structural; device lacks fp64 or forced)" : "FP64 (double, ULP-grade)";

        LOGGER.info("[SuperChunk-GPU] ===== Noise parity test (Stage 1) =====");
        LOGGER.info("[SuperChunk-GPU] Device: {}", dev.describe());
        LOGGER.info("[SuperChunk-GPU] Precision mode: {} ; pass if relErr<={} OR absErr<={}",
                mode, relTol, absTol);

        String source = loadKernelSource();
        if (source == null) {
            LOGGER.error("[SuperChunk-GPU] Could not load kernel source {} — aborting.", KERNEL_RESOURCE);
            return false;
        }

        CLProgram program = CLProgram.build(source, fp32 ? "-DUSE_FP32" : "");
        if (program == null) {
            LOGGER.error("[SuperChunk-GPU] Kernel build failed — aborting noise parity test.");
            return false;
        }

        try {
            // Sample points: realistic worldgen range, mix of integer + fractional.
            double[] sx = new double[N];
            double[] sy = new double[N];
            double[] sz = new double[N];
            buildSamplePoints(sx, sy, sz);

            boolean ok = true;
            ok &= testImprovedNoise(program, fp32, relTol, absTol, sx, sy, sz);
            ok &= testPerlinNoise(program, fp32, relTol, absTol, sx, sy, sz);
            ok &= testNormalNoise(program, fp32, relTol, absTol, sx, sy, sz);

            LOGGER.info("[SuperChunk-GPU] ===== Noise parity test {} ({}) =====",
                    ok ? "PASS" : "FAIL", mode);
            return ok;
        } catch (Throwable t) {
            LOGGER.error("[SuperChunk-GPU] Noise parity test threw — treating as FAIL.", t);
            return false;
        } finally {
            program.close();
        }
    }

    // ----------------------------------------------------------------------
    // ImprovedNoise (single Perlin octave)
    // ----------------------------------------------------------------------

    private static boolean testImprovedNoise(CLProgram program, boolean fp32, double relTol, double absTol,
                                             double[] sx, double[] sy, double[] sz) {
        RandomSource rng = new XoroshiroRandomSource(SEED);
        ImprovedNoise noise = new ImprovedNoise(rng);

        // yScale=yMax=0 is the path PerlinNoise (and thus all worldgen) takes; it's
        // the realistic case to validate. (The yScale!=0 branch is legacy/blended
        // noise; the kernel implements it identically but it isn't on the hot path.)
        final double yScale = 0.0;
        final double yMax = 0.0;

        // CPU reference (the actual vanilla sampler).
        double[] cpu = new double[N];
        for (int i = 0; i < N; i++) {
            cpu[i] = noise.noise(sx[i], sy[i], sz[i], yScale, yMax);
        }

        int[] perm = extractPerm(noise);

        long permMem = NULL, sxMem = NULL, syMem = NULL, szMem = NULL, outMem = NULL, kernel = NULL;
        double[] gpu;
        try {
            permMem = program.uploadPerm(perm);
            sxMem = program.uploadReals(fp32, sx);
            syMem = program.uploadReals(fp32, sy);
            szMem = program.uploadReals(fp32, sz);
            outMem = program.createOutputBuffer((long) N * realBytes(fp32));

            kernel = program.kernel("improved_noise_batch");
            int a = 0;
            program.setArgPointer(kernel, a++, permMem);
            a = setReal(program, kernel, a, fp32, noise.xo);
            a = setReal(program, kernel, a, fp32, noise.yo);
            a = setReal(program, kernel, a, fp32, noise.zo);
            a = setReal(program, kernel, a, fp32, yScale);
            a = setReal(program, kernel, a, fp32, yMax);
            program.setArgPointer(kernel, a++, sxMem);
            program.setArgPointer(kernel, a++, syMem);
            program.setArgPointer(kernel, a++, szMem);
            program.setArgPointer(kernel, a++, outMem);
            program.setArgInt(kernel, a++, N);

            program.enqueue1D(kernel, CLProgram.roundUpGlobal(N));
            gpu = downloadReals(program, fp32, outMem, N);
        } finally {
            CLProgram.releaseMem(permMem);
            CLProgram.releaseMem(sxMem);
            CLProgram.releaseMem(syMem);
            CLProgram.releaseMem(szMem);
            CLProgram.releaseMem(outMem);
            CLProgram.releaseKernel(kernel);
        }
        return report("ImprovedNoise", cpu, gpu, relTol, absTol);
    }

    // ----------------------------------------------------------------------
    // PerlinNoise (octave sum)
    // ----------------------------------------------------------------------

    private static boolean testPerlinNoise(CLProgram program, boolean fp32, double relTol, double absTol,
                                           double[] sx, double[] sy, double[] sz) {
        RandomSource rng = new XoroshiroRandomSource(SEED ^ 0xABCDEF1L);
        // firstOctave = -7, 8 octaves (realistic of vanilla continentalness-ish setups).
        PerlinNoise noise = PerlinNoise.create(rng, -7, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);

        double[] cpu = new double[N];
        for (int i = 0; i < N; i++) {
            cpu[i] = noise.getValue(sx[i], sy[i], sz[i]);
        }

        PerlinState ps = extractPerlin(noise);

        long permMem = NULL, actMem = NULL, xoMem = NULL, yoMem = NULL, zoMem = NULL,
                ampMem = NULL, sxMem = NULL, syMem = NULL, szMem = NULL, outMem = NULL, kernel = NULL;
        double[] gpu;
        try {
            permMem = program.uploadPerm(ps.perm);
            actMem = program.uploadInts(ps.active);
            xoMem = program.uploadReals(fp32, ps.xo);
            yoMem = program.uploadReals(fp32, ps.yo);
            zoMem = program.uploadReals(fp32, ps.zo);
            ampMem = program.uploadReals(fp32, ps.amplitudes);
            sxMem = program.uploadReals(fp32, sx);
            syMem = program.uploadReals(fp32, sy);
            szMem = program.uploadReals(fp32, sz);
            outMem = program.createOutputBuffer((long) N * realBytes(fp32));

            kernel = program.kernel("perlin_noise_batch");
            int a = 0;
            program.setArgPointer(kernel, a++, permMem);
            program.setArgInt(kernel, a++, ps.nOctaves);
            program.setArgPointer(kernel, a++, actMem);
            program.setArgPointer(kernel, a++, xoMem);
            program.setArgPointer(kernel, a++, yoMem);
            program.setArgPointer(kernel, a++, zoMem);
            program.setArgPointer(kernel, a++, ampMem);
            a = setReal(program, kernel, a, fp32, ps.lowestFreqInputFactor);
            a = setReal(program, kernel, a, fp32, ps.lowestFreqValueFactor);
            program.setArgPointer(kernel, a++, sxMem);
            program.setArgPointer(kernel, a++, syMem);
            program.setArgPointer(kernel, a++, szMem);
            program.setArgPointer(kernel, a++, outMem);
            program.setArgInt(kernel, a++, N);

            program.enqueue1D(kernel, CLProgram.roundUpGlobal(N));
            gpu = downloadReals(program, fp32, outMem, N);
        } finally {
            CLProgram.releaseMem(permMem);
            CLProgram.releaseMem(actMem);
            CLProgram.releaseMem(xoMem);
            CLProgram.releaseMem(yoMem);
            CLProgram.releaseMem(zoMem);
            CLProgram.releaseMem(ampMem);
            CLProgram.releaseMem(sxMem);
            CLProgram.releaseMem(syMem);
            CLProgram.releaseMem(szMem);
            CLProgram.releaseMem(outMem);
            CLProgram.releaseKernel(kernel);
        }
        return report("PerlinNoise", cpu, gpu, relTol, absTol);
    }

    // ----------------------------------------------------------------------
    // NormalNoise (two PerlinNoise combined by valueFactor)
    // ----------------------------------------------------------------------

    private static boolean testNormalNoise(CLProgram program, boolean fp32, double relTol, double absTol,
                                           double[] sx, double[] sy, double[] sz) {
        RandomSource rng = new XoroshiroRandomSource(SEED ^ 0x13579BDFL);
        // firstOctave = -7, amplitudes all 1.0 (8 octaves). NormalNoise.create builds
        // two PerlinNoise from consecutive draws of this RNG.
        NormalNoise noise = NormalNoise.create(rng, -7, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);

        double[] cpu = new double[N];
        for (int i = 0; i < N; i++) {
            cpu[i] = noise.getValue(sx[i], sy[i], sz[i]);
        }

        // Extract both sub-Perlin states + the valueFactor.
        PerlinNoise first = FieldAccess.normalFirst(noise);
        PerlinNoise second = FieldAccess.normalSecond(noise);
        double valueFactor = FieldAccess.normalValueFactor(noise);

        PerlinState p1 = extractPerlin(first);
        PerlinState p2 = extractPerlin(second);
        if (p1.nOctaves != p2.nOctaves) {
            LOGGER.error("[SuperChunk-GPU] NormalNoise sub-Perlins differ in octave count ({} vs {}) — abort.",
                    p1.nOctaves, p2.nOctaves);
            return false;
        }
        int nOct = p1.nOctaves;

        // Concatenate: first's octaves then second's octaves.
        int[] perm = concat(p1.perm, p2.perm);
        int[] active = concat(p1.active, p2.active);
        double[] xo = concat(p1.xo, p2.xo);
        double[] yo = concat(p1.yo, p2.yo);
        double[] zo = concat(p1.zo, p2.zo);
        double[] amp = concat(p1.amplitudes, p2.amplitudes);

        long permMem = NULL, actMem = NULL, xoMem = NULL, yoMem = NULL, zoMem = NULL,
                ampMem = NULL, sxMem = NULL, syMem = NULL, szMem = NULL, outMem = NULL, kernel = NULL;
        double[] gpu;
        try {
            permMem = program.uploadPerm(perm);
            actMem = program.uploadInts(active);
            xoMem = program.uploadReals(fp32, xo);
            yoMem = program.uploadReals(fp32, yo);
            zoMem = program.uploadReals(fp32, zo);
            ampMem = program.uploadReals(fp32, amp);
            sxMem = program.uploadReals(fp32, sx);
            syMem = program.uploadReals(fp32, sy);
            szMem = program.uploadReals(fp32, sz);
            outMem = program.createOutputBuffer((long) N * realBytes(fp32));

            kernel = program.kernel("normal_noise_batch");
            int a = 0;
            program.setArgPointer(kernel, a++, permMem);
            program.setArgInt(kernel, a++, nOct);
            program.setArgPointer(kernel, a++, actMem);
            program.setArgPointer(kernel, a++, xoMem);
            program.setArgPointer(kernel, a++, yoMem);
            program.setArgPointer(kernel, a++, zoMem);
            program.setArgPointer(kernel, a++, ampMem);
            // Both sub-Perlins share the same factors (same NoiseParameters).
            a = setReal(program, kernel, a, fp32, p1.lowestFreqInputFactor);
            a = setReal(program, kernel, a, fp32, p1.lowestFreqValueFactor);
            a = setReal(program, kernel, a, fp32, valueFactor);
            program.setArgPointer(kernel, a++, sxMem);
            program.setArgPointer(kernel, a++, syMem);
            program.setArgPointer(kernel, a++, szMem);
            program.setArgPointer(kernel, a++, outMem);
            program.setArgInt(kernel, a++, N);

            program.enqueue1D(kernel, CLProgram.roundUpGlobal(N));
            gpu = downloadReals(program, fp32, outMem, N);
        } finally {
            CLProgram.releaseMem(permMem);
            CLProgram.releaseMem(actMem);
            CLProgram.releaseMem(xoMem);
            CLProgram.releaseMem(yoMem);
            CLProgram.releaseMem(zoMem);
            CLProgram.releaseMem(ampMem);
            CLProgram.releaseMem(sxMem);
            CLProgram.releaseMem(syMem);
            CLProgram.releaseMem(szMem);
            CLProgram.releaseMem(outMem);
            CLProgram.releaseKernel(kernel);
        }
        return report("NormalNoise", cpu, gpu, relTol, absTol);
    }

    // ----------------------------------------------------------------------
    // State extraction
    // ----------------------------------------------------------------------

    /** ImprovedNoise.p is byte[256]; normalize to 0..255 ints (vanilla reads p[i]&0xFF). */
    private static int[] extractPerm(ImprovedNoise noise) {
        byte[] p = FieldAccess.improvedPerm(noise);
        int[] out = new int[256];
        for (int i = 0; i < 256; i++) {
            out[i] = p[i] & 0xFF;
        }
        return out;
    }

    /** Flattened per-octave state of a PerlinNoise for upload. */
    private static final class PerlinState {
        int nOctaves;
        int[] perm;         // nOctaves * 256 (inactive octaves -> 256 zeros, never read)
        int[] active;       // nOctaves (1 = noiseLevels[o] != null)
        double[] xo, yo, zo; // nOctaves
        double[] amplitudes; // nOctaves
        double lowestFreqInputFactor;
        double lowestFreqValueFactor;
    }

    private static PerlinState extractPerlin(PerlinNoise noise) {
        ImprovedNoise[] levels = FieldAccess.perlinNoiseLevels(noise);
        DoubleList amps = FieldAccess.perlinAmplitudes(noise);
        int n = levels.length;

        PerlinState s = new PerlinState();
        s.nOctaves = n;
        s.perm = new int[n * 256];
        s.active = new int[n];
        s.xo = new double[n];
        s.yo = new double[n];
        s.zo = new double[n];
        s.amplitudes = new double[n];
        s.lowestFreqInputFactor = FieldAccess.perlinLowestFreqInputFactor(noise);
        s.lowestFreqValueFactor = FieldAccess.perlinLowestFreqValueFactor(noise);

        for (int o = 0; o < n; o++) {
            s.amplitudes[o] = amps.getDouble(o);
            ImprovedNoise lvl = levels[o];
            if (lvl != null) {
                s.active[o] = 1;
                s.xo[o] = lvl.xo;
                s.yo[o] = lvl.yo;
                s.zo[o] = lvl.zo;
                byte[] p = FieldAccess.improvedPerm(lvl);
                int base = o * 256;
                for (int i = 0; i < 256; i++) {
                    s.perm[base + i] = p[i] & 0xFF;
                }
            } // else active=0; perm slice stays zero (kernel never reads it)
        }
        return s;
    }

    // ----------------------------------------------------------------------
    // Reporting
    // ----------------------------------------------------------------------

    private static boolean report(String name, double[] cpu, double[] gpu, double relTol, double absTol) {
        double maxAbs = 0.0;
        double maxRel = 0.0;     // tracked only over points whose abs error exceeds absTol
        int within = 0;
        int worstIdx = -1;       // worst FAILING point (for diagnostics)
        double worstScore = -1.0;
        for (int i = 0; i < cpu.length; i++) {
            double abs = Math.abs(cpu[i] - gpu[i]);
            double rel = abs / Math.max(Math.abs(cpu[i]), REL_EPS);
            if (abs > maxAbs) maxAbs = abs;
            // A point passes if it's close in EITHER absolute or relative terms.
            boolean pass = (abs <= absTol) || (rel <= relTol);
            if (pass) {
                within++;
            } else if (rel > worstScore) {
                worstScore = rel;
                worstIdx = i;
            }
            // Only let near-zero points (caught by absTol) skip the rel-max so the
            // reported maxRelErr reflects genuine large-value disagreement.
            if (abs > absTol && rel > maxRel) {
                maxRel = rel;
            }
        }
        boolean ok = (within == cpu.length);
        LOGGER.info("[SuperChunk-GPU] {} : maxAbsErr={} maxRelErr(|val|>absTol)={} within-tol={}/{} -> {}",
                name,
                String.format("%.3e", maxAbs),
                String.format("%.3e", maxRel),
                within, cpu.length,
                ok ? "PASS" : "FAIL");
        if (!ok && worstIdx >= 0) {
            LOGGER.warn("[SuperChunk-GPU]   worst FAIL @ idx {}: cpu={} gpu={} (absErr={}, relErr={})",
                    worstIdx, cpu[worstIdx], gpu[worstIdx],
                    String.format("%.3e", Math.abs(cpu[worstIdx] - gpu[worstIdx])),
                    String.format("%.3e", worstScore));
        }
        return ok;
    }

    // ----------------------------------------------------------------------
    // Buffer helpers (fp32 vs fp64)
    // ----------------------------------------------------------------------

    private static int realBytes(boolean fp32) {
        return fp32 ? Float.BYTES : Double.BYTES;
    }

    private static double[] downloadReals(CLProgram program, boolean fp32, long mem, int n) {
        // LWJGL's clEnqueueReadBuffer requires DIRECT (off-heap) NIO buffers.
        double[] out = new double[n];
        if (fp32) {
            FloatBuffer buf = MemoryUtil.memAllocFloat(n);
            try {
                program.readBuffer(mem, buf);
                for (int i = 0; i < n; i++) out[i] = buf.get(i);
            } finally {
                MemoryUtil.memFree(buf);
            }
        } else {
            DoubleBuffer buf = MemoryUtil.memAllocDouble(n);
            try {
                program.readBuffer(mem, buf);
                for (int i = 0; i < n; i++) out[i] = buf.get(i);
            } finally {
                MemoryUtil.memFree(buf);
            }
        }
        return out;
    }

    /** Sets a real scalar arg (float or double), returns next arg index. */
    private static int setReal(CLProgram program, long kernel, int index, boolean fp32, double v) {
        if (fp32) {
            program.setArgFloat(kernel, index, (float) v);
        } else {
            program.setArgDouble(kernel, index, v);
        }
        return index + 1;
    }

    // ----------------------------------------------------------------------
    // Misc
    // ----------------------------------------------------------------------

    private static void buildSamplePoints(double[] sx, double[] sy, double[] sz) {
        // Deterministic pseudo-random spread over a realistic worldgen range,
        // x,z in [-512,512], y in [-64,320]. Include exact integers and
        // fractional coords (a fraction of points snapped to integers).
        RandomSource r = new XoroshiroRandomSource(0xBADF00DL);
        int n = sx.length;
        for (int i = 0; i < n; i++) {
            double x = (r.nextDouble() * 2.0 - 1.0) * 512.0;
            double z = (r.nextDouble() * 2.0 - 1.0) * 512.0;
            double y = -64.0 + r.nextDouble() * (320.0 + 64.0);
            // Every 4th point: snap to integer block coordinates (exercises the
            // cell-boundary / Mth.floor path).
            if ((i & 3) == 0) {
                x = Math.rint(x);
                y = Math.rint(y);
                z = Math.rint(z);
            }
            sx[i] = x;
            sy[i] = y;
            sz[i] = z;
        }
    }

    private static int[] concat(int[] a, int[] b) {
        int[] r = new int[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static double[] concat(double[] a, double[] b) {
        double[] r = new double[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static String loadKernelSource() {
        try (InputStream in = GpuNoiseParityTest.class.getResourceAsStream(KERNEL_RESOURCE)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("[SuperChunk-GPU] Failed reading {}", KERNEL_RESOURCE, e);
            return null;
        }
    }
}
