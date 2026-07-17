package dev.superchunk.gpu.aquifer;

import dev.superchunk.gpu.CLProgram;
import dev.superchunk.gpu.OpenCLBackend;
import net.minecraft.core.BlockPos;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * ISOLATED self-test for the aquifer_decide kernel: feeds the GPU a fully
 * controlled synthetic grid (locCache + per-cell FluidStatus + query blocks) and
 * compares the GPU's block id, flip-margin and nearest-cell against
 * {@link AquiferRef} — the single Java decision reference (also used by the live
 * {@link AquiferGpuVerify}) that ports the SAME C2ME decision op-for-op, and whose
 * {@link AquiferRef#decideWithMargin} additionally mirrors aquifer.cl's outMargin
 * bit-exactly. A local {@link #decideRef} grid-search port supplies the
 * dist1/fl1/idx1/pos1 diagnostics AquiferRef doesn't expose and is cross-checked
 * to agree with AquiferRef on the id. This removes all live-pregen plumbing so a
 * mismatch is unambiguously a kernel bug. Reusable.
 */
public final class AquiferKernelSelfTest {
    private static final Logger LOG = LoggerFactory.getLogger("SuperChunk-AquiferVerify");

    // synthetic grid geometry (a realistic single-chunk shape)
    private static final int MIN_GX = -2, MIN_GY = -7, MIN_GZ = -2;
    private static final int GSX = 4, GSZ = 4, SIZE_Y = 35;
    private static final int SEA = 63, LAVA = -54, DEF_FLUID = 2; // water
    private static final int WAY_BELOW = -32512;

    private AquiferKernelSelfTest() {
    }

    public static boolean run() {
        if (!OpenCLBackend.isAvailable() || !OpenCLBackend.device().fp64()) {
            return false;
        }
        String src = load("/superchunk/kernels/rng.cl");
        String aq = load("/superchunk/kernels/aquifer.cl");
        if (src == null || aq == null) {
            LOG.error("[aquifer-kernel-selftest] could not load kernels.");
            return false;
        }
        CLProgram program = CLProgram.build(
                "#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n#pragma OPENCL FP_CONTRACT OFF\n" + src + "\n" + aq);
        if (program == null) {
            LOG.error("[aquifer-kernel-selftest] build failed.");
            return false;
        }

        final int cells = GSX * SIZE_Y * GSZ;
        long[] locCache = new long[cells];
        int[] fl = new int[cells];
        int[] ft = new int[cells];
        // deterministic jitter (xoroshiro-like spread, but any deterministic values work) + FluidStatus
        var factory = new net.minecraft.world.level.levelgen.XoroshiroRandomSource.XoroshiroPositionalRandomFactory(
                0x1234L, 0x5678L);
        for (int y = 0; y < SIZE_Y; y++) {
            for (int z = 0; z < GSZ; z++) {
                for (int x = 0; x < GSX; x++) {
                    int x1 = x + MIN_GX, y1 = y + MIN_GY, z1 = z + MIN_GZ;
                    var r = factory.at(x1, y1, z1);
                    int px = x1 * 16 + r.nextInt(10);
                    int py = y1 * 12 + r.nextInt(9);
                    int pz = z1 * 16 + r.nextInt(10);
                    int idx = getIndex(x1, y1, z1);
                    locCache[idx] = BlockPos.asLong(px, py, pz);
                    // synthetic FluidStatus: most cells water@63, some "no fluid" (WAY_BELOW), a few lava
                    int sel = Math.floorMod(x1 * 31 + y1 * 17 + z1 * 7, 7);
                    if (sel == 0) {
                        fl[idx] = WAY_BELOW;
                        ft[idx] = DEF_FLUID;
                    } else if (sel == 1) {
                        fl[idx] = -54;
                        ft[idx] = 3; // lava
                    } else if (sel == 2) {
                        fl[idx] = 20 + Math.floorMod(y1 * 13, 40); // perched water
                        ft[idx] = 2;
                    } else {
                        fl[idx] = SEA;
                        ft[idx] = 2;
                    }
                }
            }
        }

        // Force the two aquifer cells nearest the (already-swept) query block (8, WOL_Y, 8)
        // to be high water at EQUAL distance, so that block's decision reaches the
        // water-over-lava applyPost branch (aquifer.cl:183-187 / decideRef:225) — reachable
        // ONLY at y == min(LAVA,SEA) — with similarity(dist1,dist2) > 0 and a WATER blockState.
        // (The lava-globalAt early-return, aquifer.cl:139-140, is covered by the by<=-55 sweep below.)
        final int WOL_Y = Math.min(LAVA, SEA);          // -54: globalAt(y)=WATER, globalAt(y-1)=LAVA
        int wolA = getIndex(0, -5, 0), wolB = getIndex(1, -5, 0); // both in (8,-54,8)'s 2x3x2 search
        locCache[wolA] = BlockPos.asLong(6, WOL_Y, 8);  fl[wolA] = SEA; ft[wolA] = 2; // dist^2 = 4
        locCache[wolB] = BlockPos.asLong(10, WOL_Y, 8); fl[wolB] = SEA; ft[wolB] = 2; // dist^2 = 4

        // query blocks across the grid's central region (incl. cell-edge x/z)
        java.util.ArrayList<int[]> q = new java.util.ArrayList<>();
        for (int bx = -20; bx <= 35; bx += 1) {
            for (int bz = -20; bz <= 35; bz += 7) {
                for (int by = -70; by <= 120; by += 1) {
                    q.add(new int[]{bx, by, bz});
                }
            }
        }
        int n = q.size();
        int[] qx = new int[n], qy = new int[n], qz = new int[n];
        double[] dens = new double[n], barr = new double[n];
        for (int t = 0; t < n; t++) {
            qx[t] = q.get(t)[0];
            qy[t] = q.get(t)[1];
            qz[t] = q.get(t)[2];
            dens[t] = ((t * 2654435761L) % 7 == 0) ? 0.0 : -((t % 13) * 0.05 + 1e-9); // many density<=0, some exactly 0
            barr[t] = ((t % 23) - 11) * 0.03;
        }

        int[] gpuId = new int[n], gpuD1 = new int[n], gpuF1 = new int[n], gpuIdx1 = new int[n];
        long[] gpuPos1 = new long[n];
        double[] gpuMargin = new double[n];
        try {
            dispatch(program, n, qx, qy, qz, dens, barr, locCache, fl, ft,
                    gpuId, gpuMargin, gpuD1, gpuF1, gpuIdx1, gpuPos1);
        } catch (Throwable t) {
            LOG.error("[aquifer-kernel-selftest] dispatch threw.", t);
            program.close();
            return false;
        }
        program.close();

        int mism = 0, gridMism = 0, marginMism = 0, refMism = 0;
        for (int t = 0; t < n; t++) {
            // AquiferRef is the SINGLE decision reference: it supplies the authoritative block id
            // AND the flip-margin oracle (bit-exact mirror of aquifer.cl's outMargin).
            AquiferRef.Decision dec = AquiferRef.decideWithMargin(qx[t], qy[t], qz[t], dens[t], barr[t],
                    locCache, fl, ft, MIN_GX, MIN_GY, MIN_GZ, GSX, GSZ, SEA, LAVA, DEF_FLUID);
            int refId = dec.id;
            double refMargin = dec.margin;
            // decideRef is kept only for the grid-search DIAGNOSTICS AquiferRef doesn't expose
            // (dist1/fl1/idx1/pos1); its own id (grid[0]) is cross-checked against AquiferRef below.
            int[] grid = decideRef(qx[t], qy[t], qz[t], dens[t], barr[t], locCache, fl, ft);
            int refD1 = grid[1], refF1 = grid[2], refIdx1 = grid[3];
            long refPos1 = ((long) grid[4] & 0xFFFFFFFFL) | (((long) grid[5]) << 32); // low|high 32 bits of pos1
            if (grid[0] != refId) {
                if (refMism < 8) {
                    LOG.error("[aquifer-kernel-selftest]   REF CROSS-CHECK DIFF @({},{},{}) density={} : decideRef id={} != AquiferRef id={} (the two Java decision ports disagree)",
                            qx[t], qy[t], qz[t], dens[t], grid[0], refId);
                }
                refMism++;
            }
            if (gpuD1[t] != refD1 || gpuF1[t] != refF1 || gpuIdx1[t] != refIdx1 || gpuPos1[t] != refPos1) {
                if (gridMism < 8) {
                    LOG.error("[aquifer-kernel-selftest]   GRID DIFF @({},{},{}): ref(dist1={},fl1={},idx1={},pos1={}) gpu(dist1={},fl1={},idx1={},pos1={})",
                            qx[t], qy[t], qz[t], refD1, refF1, refIdx1, refPos1, gpuD1[t], gpuF1[t], gpuIdx1[t], gpuPos1[t]);
                }
                gridMism++;
            }
            if (gpuId[t] != refId) {
                if (mism < 8) {
                    LOG.error("[aquifer-kernel-selftest]   ID DIFF @({},{},{}) density={} : ref id={} gpu id={} (ref dist1={} gpu dist1={})",
                            qx[t], qy[t], qz[t], dens[t], refId, gpuId[t], refD1, gpuD1[t]);
                }
                mism++;
            }
            if (gpuMargin[t] != refMargin) {   // bit-exact: fp64, FP_CONTRACT OFF, same op/fmin order
                if (marginMism < 8) {
                    LOG.error("[aquifer-kernel-selftest]   MARGIN DIFF @({},{},{}) density={} : ref margin={} gpu margin={}",
                            qx[t], qy[t], qz[t], dens[t], refMargin, gpuMargin[t]);
                }
                marginMism++;
            }
        }
        if (mism == 0 && gridMism == 0 && marginMism == 0 && refMism == 0) {
            LOG.info("[aquifer-kernel-selftest] PASS — aquifer_decide kernel matches the AquiferRef reference over {} synthetic blocks (grid search + decision id + flip-margin BIT-EXACT; the two Java decision ports agree).", n);
            return true;
        }
        LOG.error("[aquifer-kernel-selftest] FAIL — {} id diffs, {} grid diffs, {} margin diffs, {} ref cross-check diffs / {} blocks.", mism, gridMism, marginMism, refMism, n);
        return false;
    }

    private static int getIndex(int l, int m, int n) {
        return ((m - MIN_GY) * GSZ + (n - MIN_GZ)) * GSX + (l - MIN_GX);
    }

    private static int at(int fluidLevel, int fluidTypeId, int y) {
        return (y < fluidLevel) ? fluidTypeId : 1;
    }

    private static int globalAt(int y) {
        int lo = Math.min(LAVA, SEA);
        if (y < lo) return at(LAVA, 3, y);
        return at(SEA, DEF_FLUID, y);
    }

    private static double getQ(double i, double d, double j) {
        double e = i + 0.5 - d;
        double f = j / 2.0;
        double o = f - Math.abs(e);
        double qq;
        if (e > 0.0) qq = (o > 0.0) ? o / 1.5 : o / 2.5;
        else {
            double p = 3.0 + o;
            qq = (p > 0.0) ? p / 3.0 : p / 10.0;
        }
        return qq;
    }

    private static double pressure(int flA, int ftA, int flB, int ftB, int j, double barrier) {
        int bsA = at(flA, ftA, j), bsB = at(flB, ftB, j);
        boolean lavaWater = (bsA == 3 && bsB == 2) || (bsA == 2 && bsB == 3);
        if (!lavaWater) {
            int jj = Math.abs(flA - flB);
            if (jj == 0) return 0.0;
            double d0 = 0.5 * (double) (flA + flB);
            double qq = getQ((double) j, d0, (double) jj);
            double r = (!(qq < -2.0) && !(qq > 2.0)) ? barrier : 0.0;
            return 2.0 * (r + qq);
        }
        return 2.0;
    }

    /**
     * Grid-search DIAGNOSTICS port of aq_decide. Returns {id, dist1, fl1, idx1, posLo, posHi};
     * the dist1/fl1/idx1/pos1 fields are the nearest-cell diagnostics {@link AquiferRef} doesn't
     * expose (compared against the GPU's own outDist1/outFl1/outIdx1/outPos1). The {@code id} it
     * also computes is NOT the authoritative reference — that comes from {@link AquiferRef#decide}
     * (the single source of truth); this id is only cross-checked to agree with AquiferRef, so the
     * two Java decision ports cannot silently diverge.
     */
    private static int[] decideRef(int i, int j, int k, double density, double barrier,
                                   long[] locCache, int[] fluidLevel, int[] fluidTypeId) {
        int globalAtJ = globalAt(j);
        if (globalAtJ == 3) return new int[]{3, -1, Integer.MIN_VALUE, -1, 0, 0};

        int gx = (i - 5) >> 4;
        int gy = Math.floorDiv(j + 1, 12);
        int gz = (k - 5) >> 4;
        int dist1 = Integer.MAX_VALUE, dist2 = Integer.MAX_VALUE, dist3 = Integer.MAX_VALUE;
        long pos1 = 0, pos2 = 0, pos3 = 0;
        int fl1 = 0, ft1 = 0, fl2 = 0, ft2 = 0, fl3 = 0, ft3 = 0;
        for (int offY = -1; offY <= 1; offY++) {
            for (int offZ = 0; offZ <= 1; offZ++) {
                for (int offX = 0; offX <= 1; offX++) {
                    int idx = getIndex(gx + offX, gy + offY, gz + offZ);
                    long position = locCache[idx];
                    int cfl = fluidLevel[idx], cft = fluidTypeId[idx];
                    int dx = BlockPos.getX(position) - i;
                    int dy = BlockPos.getY(position) - j;
                    int dz = BlockPos.getZ(position) - k;
                    int dist = dx * dx + dy * dy + dz * dz;
                    if (dist3 >= dist) { pos3 = position; dist3 = dist; fl3 = cfl; ft3 = cft; }
                    if (dist2 >= dist) { pos3 = pos2; dist3 = dist2; fl3 = fl2; ft3 = ft2; pos2 = position; dist2 = dist; fl2 = cfl; ft2 = cft; }
                    if (dist1 >= dist) { pos2 = pos1; dist2 = dist1; fl2 = fl1; ft2 = ft1; pos1 = position; dist1 = dist; fl1 = cfl; ft1 = cft; }
                }
            }
        }
        int idx1 = getIndex(BlockPos.getX(pos1) >> 4, Math.floorDiv(BlockPos.getY(pos1), 12), BlockPos.getZ(pos1) >> 4);
        int blockState = at(fl1, ft1, j);
        int resultId;
        double d = 1.0 - (double) Math.abs(dist2 - dist1) / 25.0;
        if (d <= 0.0) {
            resultId = blockState;
        } else if (blockState == 2 && globalAt(j - 1) == 3) {
            resultId = blockState;
        } else {
            double e = d * pressure(fl1, ft1, fl2, ft2, j, barrier);
            if (density + e > 0.0) {
                resultId = 0;
            } else {
                resultId = blockState; // default fallthrough, overridden by checks below
                double f = 1.0 - (double) Math.abs(dist3 - dist1) / 25.0;
                boolean nulled = false;
                if (f > 0.0) {
                    double g1 = d * f * pressure(fl1, ft1, fl3, ft3, j, barrier);
                    if (density + g1 > 0.0) { resultId = 0; nulled = true; }
                }
                if (!nulled) {
                    double g = 1.0 - (double) Math.abs(dist3 - dist2) / 25.0;
                    if (g > 0.0) {
                        double g2 = d * g * pressure(fl2, ft2, fl3, ft3, j, barrier);
                        if (density + g2 > 0.0) { resultId = 0; }
                    }
                }
            }
        }
        return new int[]{resultId, dist1, fl1, idx1, (int) pos1, (int) (pos1 >>> 32)};
    }

    private static void dispatch(CLProgram program, int n, int[] qx, int[] qy, int[] qz,
                                 double[] dens, double[] barr, long[] locCache, int[] fl, int[] ft,
                                 int[] outId, double[] outMargin, int[] outD1, int[] outF1, int[] outIdx1, long[] outPos1) {
        IntBuffer bx = put(MemoryUtil.memAllocInt(n), qx, n), by = put(MemoryUtil.memAllocInt(n), qy, n),
                bz = put(MemoryUtil.memAllocInt(n), qz, n),
                bFl = put(MemoryUtil.memAllocInt(fl.length), fl, fl.length),
                bFt = put(MemoryUtil.memAllocInt(ft.length), ft, ft.length);
        DoubleBuffer bDe = put(MemoryUtil.memAllocDouble(n), dens, n), bBa = put(MemoryUtil.memAllocDouble(n), barr, n);
        LongBuffer bLoc = put(MemoryUtil.memAllocLong(locCache.length), locCache, locCache.length);
        IntBuffer rId = MemoryUtil.memAllocInt(n), rD1 = MemoryUtil.memAllocInt(n), rF1 = MemoryUtil.memAllocInt(n), rIx = MemoryUtil.memAllocInt(n);
        DoubleBuffer rMa = MemoryUtil.memAllocDouble(n);
        LongBuffer rP1 = MemoryUtil.memAllocLong(n);
        long mBx = NULL, mBy = NULL, mBz = NULL, mDe = NULL, mBa = NULL, mLoc = NULL, mFl = NULL, mFt = NULL,
                mId = NULL, mMa = NULL, mD1 = NULL, mF1 = NULL, mIx = NULL, mP1 = NULL, kernel = NULL;
        try {
            mBx = program.createInputBuffer(bx); mBy = program.createInputBuffer(by); mBz = program.createInputBuffer(bz);
            mDe = program.createInputBuffer(bDe); mBa = program.createInputBuffer(bBa); mLoc = program.createInputBuffer(bLoc);
            mFl = program.createInputBuffer(bFl); mFt = program.createInputBuffer(bFt);
            mId = program.createOutputBuffer((long) n * 4);
            mMa = program.createOutputBuffer((long) n * 8); // kernel arg 17 (margin); validated against the AquiferRef oracle below
            mD1 = program.createOutputBuffer((long) n * 4); mF1 = program.createOutputBuffer((long) n * 4);
            mIx = program.createOutputBuffer((long) n * 4); mP1 = program.createOutputBuffer((long) n * 8);
            kernel = program.kernel("aquifer_decide");
            program.setArgPointer(kernel, 0, mBx); program.setArgPointer(kernel, 1, mBy); program.setArgPointer(kernel, 2, mBz);
            program.setArgPointer(kernel, 3, mDe); program.setArgPointer(kernel, 4, mBa); program.setArgPointer(kernel, 5, mLoc);
            program.setArgPointer(kernel, 6, mFl); program.setArgPointer(kernel, 7, mFt);
            program.setArgInt(kernel, 8, MIN_GX); program.setArgInt(kernel, 9, MIN_GY); program.setArgInt(kernel, 10, MIN_GZ);
            program.setArgInt(kernel, 11, GSX); program.setArgInt(kernel, 12, GSZ);
            program.setArgInt(kernel, 13, SEA); program.setArgInt(kernel, 14, LAVA); program.setArgInt(kernel, 15, DEF_FLUID);
            program.setArgPointer(kernel, 16, mId); program.setArgPointer(kernel, 17, mMa);
            program.setArgPointer(kernel, 18, mD1); program.setArgPointer(kernel, 19, mF1);
            program.setArgPointer(kernel, 20, mIx); program.setArgPointer(kernel, 21, mP1);
            program.setArgInt(kernel, 22, n);
            program.enqueue1D(kernel, n);
            program.readBuffer(mId, rId); program.readBuffer(mMa, rMa); program.readBuffer(mD1, rD1); program.readBuffer(mF1, rF1);
            program.readBuffer(mIx, rIx); program.readBuffer(mP1, rP1);
            rId.get(outId, 0, n); rMa.get(outMargin, 0, n); rD1.get(outD1, 0, n); rF1.get(outF1, 0, n); rIx.get(outIdx1, 0, n); rP1.get(outPos1, 0, n);
        } finally {
            for (long m : new long[]{mBx, mBy, mBz, mDe, mBa, mLoc, mFl, mFt, mId, mMa, mD1, mF1, mIx, mP1}) CLProgram.releaseMem(m);
            CLProgram.releaseKernel(kernel);
            for (java.nio.Buffer b : new java.nio.Buffer[]{bx, by, bz, bDe, bBa, bLoc, bFl, bFt, rId, rMa, rD1, rF1, rIx, rP1}) MemoryUtil.memFree(b);
        }
    }

    private static IntBuffer put(IntBuffer b, int[] a, int n) { b.put(a, 0, n).flip(); return b; }
    private static DoubleBuffer put(DoubleBuffer b, double[] a, int n) { b.put(a, 0, n).flip(); return b; }
    private static LongBuffer put(LongBuffer b, long[] a, int n) { b.put(a, 0, n).flip(); return b; }

    private static String load(String resource) {
        try (InputStream in = AquiferKernelSelfTest.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
