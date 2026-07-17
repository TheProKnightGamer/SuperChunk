package dev.superchunk.gpu.aquifer;

import dev.superchunk.gpu.CLProgram;
import dev.superchunk.gpu.OpenCLBackend;
import dev.superchunk.gpu.OpenCLDevice;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Foundation self-test for the aquifer GPU port: proves the OpenCL port of the
 * Minecraft 1.21.1 positional RNG (xoroshiro128++ {@code XoroshiroRandomSource}
 * via {@code XoroshiroPositionalRandomFactory.at(x,y,z)} + the bounded
 * {@code nextInt(bound)}) reproduces the Java RNG <b>bit-for-bit</b>.
 *
 * <p>This is the riskiest-to-get-exact piece of the aquifer port — the per-cell
 * jitter that seeds {@code aquiferLocationCache} is {@code at(cx,cy,cz)} then
 * {@code nextInt(10)/nextInt(9)/nextInt(10)}. We test exactly that pattern (and
 * the resulting {@link BlockPos#asLong} packing) plus a rejection-heavy
 * {@code nextInt} stream that exercises the Lemire reject branch the aquifer's
 * small bounds statistically never reach.
 *
 * <p>Never throws; logs a clear {@code [aquifer-rng]} PASS/FAIL. Returns true on
 * an exact match.
 */
public final class RngSelfTest {
    private static final Logger LOGGER = OpenCLBackend.LOGGER;
    private static final String KERNEL_RESOURCE = "/superchunk/kernels/rng.cl";

    // A handful of factory (seedLo, seedHi) pairs, incl. (0,0) to hit the
    // xoroshiro all-zero state guard.
    private static final long[][] SEEDS = {
            {0L, 0L},
            {0x9E3779B97F4A7C15L, 0x6A09E667F3BCC909L},
            {-7046029254386353131L, 7640891576956012809L},
            {1234567890123L, -9876543210987L},
            {0x0123456789ABCDEFL, 0x1L},
    };

    private RngSelfTest() {
    }

    public static boolean run() {
        if (!OpenCLBackend.isAvailable()) {
            LOGGER.warn("[aquifer-rng] self-test skipped — GPU backend unavailable.");
            return false;
        }
        OpenCLDevice dev = OpenCLBackend.device();
        String source = loadResource();
        if (source == null) {
            LOGGER.error("[aquifer-rng] self-test FAIL — could not load {}", KERNEL_RESOURCE);
            return false;
        }
        CLProgram program = CLProgram.build(source);
        if (program == null) {
            LOGGER.error("[aquifer-rng] self-test FAIL — rng.cl build returned null.");
            return false;
        }
        try {
            long totalChecks = 0;
            long mismatches = 0;
            for (long[] seed : SEEDS) {
                long[] r = testJitter(program, seed[0], seed[1]);
                totalChecks += r[0];
                mismatches += r[1];
            }
            // Rejection-branch coverage: bound 0x60000000 makes ~25% of draws enter
            // the Lemire reject check (vs ~10/2^32 for the aquifer's bound=9,10).
            long[] rs = testBoundStream(program, 0x0123456789ABCDEFL, 0x7L, 12345, -678, 90, 0x60000000, 8192);
            totalChecks += rs[0];
            mismatches += rs[1];
            // Plus the aquifer's own small bounds, long stream.
            for (int b : new int[]{10, 9, 2, 7, 256, 1000}) {
                long[] rb = testBoundStream(program, 0xABCDEF0123456789L, 0x55L, -33, 71, 1024, b, 4096);
                totalChecks += rb[0];
                mismatches += rb[1];
            }
            // BlockPos unpack over the long-input-buffer path (the aquifer locCache path).
            long[] ru = testBpUnpack(program);
            totalChecks += ru[0];
            mismatches += ru[1];

            if (mismatches == 0) {
                LOGGER.info("[aquifer-rng] self-test PASS — Xoroshiro128++ positional RNG + nextInt() "
                                + "BIT-EXACT vs Java over {} draws on: {} [{}] fp64={}",
                        totalChecks, dev.name(), dev.vendor(), dev.fp64() ? "yes" : "no");
                return true;
            }
            LOGGER.error("[aquifer-rng] self-test FAIL — {} / {} GPU RNG draws diverged from Java.",
                    mismatches, totalChecks);
            return false;
        } catch (Throwable t) {
            LOGGER.error("[aquifer-rng] self-test FAIL — exception.", t);
            return false;
        } finally {
            program.close();
        }
    }

    /** Tests at(x,y,z).nextInt(10)/nextInt(9)/nextInt(10) + asLong packing + raw nextLong. */
    private static long[] testJitter(CLProgram program, long seedLo, long seedHi) {
        final int n = 4096;
        IntBuffer gx = null, gy = null, gz = null, i0 = null, i1 = null, i2 = null;
        LongBuffer rawLong = null, packed = null;
        long mGx = NULL, mGy = NULL, mGz = NULL, mRaw = NULL, mI0 = NULL, mI1 = NULL, mI2 = NULL, mPk = NULL;
        long kernel = NULL;
        long mism = 0;
        try {
            gx = MemoryUtil.memAllocInt(n);
            gy = MemoryUtil.memAllocInt(n);
            gz = MemoryUtil.memAllocInt(n);
            // deterministic spread over +/- coords (incl. values that overflow x*3129871).
            for (int p = 0; p < n; p++) {
                int x = (p * 73856093) ^ (p << 3);
                int y = ((p * 19349663) >> 4) - 64 + (p % 384);
                int z = (p * 83492791) ^ (-p);
                gx.put(p, x);
                gy.put(p, y);
                gz.put(p, z);
            }
            mGx = program.createInputBuffer(gx);
            mGy = program.createInputBuffer(gy);
            mGz = program.createInputBuffer(gz);
            mRaw = program.createOutputBuffer((long) n * Long.BYTES);
            mI0 = program.createOutputBuffer((long) n * Integer.BYTES);
            mI1 = program.createOutputBuffer((long) n * Integer.BYTES);
            mI2 = program.createOutputBuffer((long) n * Integer.BYTES);
            mPk = program.createOutputBuffer((long) n * Long.BYTES);

            kernel = program.kernel("rng_selftest_jitter");
            program.setArgLong(kernel, 0, seedLo);
            program.setArgLong(kernel, 1, seedHi);
            program.setArgPointer(kernel, 2, mGx);
            program.setArgPointer(kernel, 3, mGy);
            program.setArgPointer(kernel, 4, mGz);
            program.setArgPointer(kernel, 5, mRaw);
            program.setArgPointer(kernel, 6, mI0);
            program.setArgPointer(kernel, 7, mI1);
            program.setArgPointer(kernel, 8, mI2);
            program.setArgPointer(kernel, 9, mPk);
            program.setArgInt(kernel, 10, n);
            program.enqueue1D(kernel, n);

            rawLong = MemoryUtil.memAllocLong(n);
            packed = MemoryUtil.memAllocLong(n);
            i0 = MemoryUtil.memAllocInt(n);
            i1 = MemoryUtil.memAllocInt(n);
            i2 = MemoryUtil.memAllocInt(n);
            program.readBuffer(mRaw, rawLong);
            program.readBuffer(mI0, i0);
            program.readBuffer(mI1, i1);
            program.readBuffer(mI2, i2);
            program.readBuffer(mPk, packed);

            var factory = new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(seedLo, seedHi);
            for (int p = 0; p < n; p++) {
                int x = gx.get(p), y = gy.get(p), z = gz.get(p);
                long jRaw = factory.at(x, y, z).nextLong();
                RandomSource r = factory.at(x, y, z);
                int a = r.nextInt(10), b = r.nextInt(9), c = r.nextInt(10);
                long jPacked = BlockPos.asLong(x * 16 + a, y * 12 + b, z * 16 + c);
                if (jRaw != rawLong.get(p) || a != i0.get(p) || b != i1.get(p) || c != i2.get(p)
                        || jPacked != packed.get(p)) {
                    if (mism < 5) {
                        LOGGER.error("[aquifer-rng]   MISMATCH seed=({},{}) pos=({},{},{}): "
                                        + "rawLong gpu={} java={} | nextInt gpu=({},{},{}) java=({},{},{}) | packed gpu={} java={}",
                                seedLo, seedHi, x, y, z, rawLong.get(p), jRaw,
                                i0.get(p), i1.get(p), i2.get(p), a, b, c, packed.get(p), jPacked);
                    }
                    mism++;
                }
            }
            return new long[]{n, mism};
        } finally {
            CLProgram.releaseKernel(kernel);
            CLProgram.releaseMem(mGx); CLProgram.releaseMem(mGy); CLProgram.releaseMem(mGz);
            CLProgram.releaseMem(mRaw); CLProgram.releaseMem(mI0); CLProgram.releaseMem(mI1);
            CLProgram.releaseMem(mI2); CLProgram.releaseMem(mPk);
            free(gx); free(gy); free(gz); free(i0); free(i1); free(i2); free(rawLong); free(packed);
        }
    }

    /** Tests a `count`-long stream of nextInt(bound) from one at(x,y,z) stream. */
    private static long[] testBoundStream(CLProgram program, long seedLo, long seedHi,
                                          int x, int y, int z, int bound, int count) {
        IntBuffer out = null;
        long mOut = NULL, kernel = NULL;
        long mism = 0;
        try {
            mOut = program.createOutputBuffer((long) count * Integer.BYTES);
            kernel = program.kernel("rng_selftest_bound_stream");
            program.setArgLong(kernel, 0, seedLo);
            program.setArgLong(kernel, 1, seedHi);
            program.setArgInt(kernel, 2, x);
            program.setArgInt(kernel, 3, y);
            program.setArgInt(kernel, 4, z);
            program.setArgInt(kernel, 5, bound);
            program.setArgInt(kernel, 6, count);
            program.setArgPointer(kernel, 7, mOut);
            program.enqueue1D(kernel, 1);  // single deterministic stream

            out = MemoryUtil.memAllocInt(count);
            program.readBuffer(mOut, out);

            var factory = new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(seedLo, seedHi);
            RandomSource r = factory.at(x, y, z);
            for (int t = 0; t < count; t++) {
                int jv = r.nextInt(bound);
                if (jv != out.get(t)) {
                    if (mism < 5) {
                        LOGGER.error("[aquifer-rng]   STREAM MISMATCH bound={} t={}: gpu={} java={}",
                                bound, t, out.get(t), jv);
                    }
                    mism++;
                }
            }
            return new long[]{count, mism};
        } finally {
            CLProgram.releaseKernel(kernel);
            CLProgram.releaseMem(mOut);
            free(out);
        }
    }

    /** Tests createInputBuffer(LongBuffer) upload + mc_bp_getX/Y/Z unpack vs Java BlockPos. */
    private static long[] testBpUnpack(CLProgram program) {
        final int n = 4096;
        LongBuffer packed = null;
        IntBuffer ox = null, oy = null, oz = null;
        long mPk = NULL, mX = NULL, mY = NULL, mZ = NULL, kernel = NULL;
        long mism = 0;
        int[] jx = new int[n], jy = new int[n], jz = new int[n];
        try {
            packed = MemoryUtil.memAllocLong(n);
            for (int p = 0; p < n; p++) {
                int x = (p * 73856093) ^ (p << 5);          // arbitrary, incl. negatives
                int y = -2000 + (p % 4000);                  // packed-Y range is signed 12-bit
                int z = (p * 19349663) ^ (-p * 3);
                // clamp to the packed field ranges so asLong is lossless (26/12/26-bit signed)
                x = (x << 6) >> 6;
                y = (y << 20) >> 20;
                z = (z << 6) >> 6;
                jx[p] = x; jy[p] = y; jz[p] = z;
                packed.put(p, BlockPos.asLong(x, y, z));
            }
            mPk = program.createInputBuffer(packed);
            mX = program.createOutputBuffer((long) n * Integer.BYTES);
            mY = program.createOutputBuffer((long) n * Integer.BYTES);
            mZ = program.createOutputBuffer((long) n * Integer.BYTES);
            kernel = program.kernel("bp_unpack_test");
            program.setArgPointer(kernel, 0, mPk);
            program.setArgPointer(kernel, 1, mX);
            program.setArgPointer(kernel, 2, mY);
            program.setArgPointer(kernel, 3, mZ);
            program.setArgInt(kernel, 4, n);
            program.enqueue1D(kernel, n);
            ox = MemoryUtil.memAllocInt(n);
            oy = MemoryUtil.memAllocInt(n);
            oz = MemoryUtil.memAllocInt(n);
            program.readBuffer(mX, ox);
            program.readBuffer(mY, oy);
            program.readBuffer(mZ, oz);
            for (int p = 0; p < n; p++) {
                if (ox.get(p) != jx[p] || oy.get(p) != jy[p] || oz.get(p) != jz[p]) {
                    if (mism < 5) {
                        LOGGER.error("[aquifer-rng]   BP UNPACK MISMATCH p={}: gpu=({},{},{}) java=({},{},{}) packed={}",
                                p, ox.get(p), oy.get(p), oz.get(p), jx[p], jy[p], jz[p], packed.get(p));
                    }
                    mism++;
                }
            }
            return new long[]{n, mism};
        } finally {
            CLProgram.releaseKernel(kernel);
            CLProgram.releaseMem(mPk); CLProgram.releaseMem(mX); CLProgram.releaseMem(mY); CLProgram.releaseMem(mZ);
            free(packed); free(ox); free(oy); free(oz);
        }
    }

    private static void free(java.nio.Buffer b) {
        if (b != null) MemoryUtil.memFree(b);
    }

    private static String loadResource() {
        try (InputStream in = RngSelfTest.class.getResourceAsStream(KERNEL_RESOURCE)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
