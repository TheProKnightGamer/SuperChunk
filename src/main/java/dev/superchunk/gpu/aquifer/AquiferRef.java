package dev.superchunk.gpu.aquifer;

import net.minecraft.core.BlockPos;

/**
 * Pure-Java reference of the aquifer_decide kernel (C2ME computeSubstance
 * density&lt;=0 algebra), ported op-for-op from aquifer.cl. This is the SINGLE
 * decision reference: {@link #decide} returns the decided block id and
 * {@link #decideWithMargin} additionally returns the mode-B flip-margin (a
 * bit-exact mirror of the kernel's {@code outMargin} fmin-accumulation).
 * {@link AquiferKernelSelfTest} compares the GPU id + margin against this oracle
 * (its own grid-search port is kept only for the diagnostics this class does not
 * expose, and is cross-checked to agree on the id), and the live
 * {@link AquiferGpuVerify} flush uses {@link #decide} as a 3-way oracle (GPU vs
 * this-ref vs the CPU's real result) to separate a kernel/upload bug
 * (GPU != ref) from a data-capture bug (ref != CPU). Ids: 0=null/stone 1=air
 * 2=water 3=lava.
 */
public final class AquiferRef {
    private AquiferRef() {
    }

    /** Decided block id plus the mode-B flip-margin (min |density| to a sign-flip boundary). */
    public static final class Decision {
        public final int id;
        public final double margin;

        Decision(int id, double margin) {
            this.id = id;
            this.margin = margin;
        }
    }

    /**
     * Census (Stage-3) decision: id + margin + the vanilla per-block
     * {@code shouldScheduleFluidUpdate} bit, mirroring aquifer.cl's
     * {@code aq_decide(..., outSched)} branch-for-branch. Used by
     * {@code BlockIdCensus} as the 3-way oracle so a kernel/driver fault
     * (GPU != this ref) is never misread as a real block flip.
     */
    public static final class Full {
        public final int id;
        public final double margin;
        public final boolean sched;

        Full(int id, double margin, boolean sched) {
            this.id = id;
            this.margin = margin;
            this.sched = sched;
        }
    }

    /**
     * Full census reference for ANY density (the kernel's {@code aquifer_census} path):
     * {@code density > 0} is the vanilla early solid return (id 0, sched false, margin =
     * density); otherwise the {@code aq_decide} algebra with the sched bit ported
     * branch-for-branch from the C2ME computeSubstance/applyPost chain.
     * {@code flowingUpdateSimilarity} is vanilla's FLOWING_UPDATE_SIMULARITY constant
     * (passed through from the mixin's shadow so the ref and kernel share the exact bits).
     */
    public static Full decideFull(int i, int j, int k, double density, double barrier,
                                  long[] locCache, int[] fl, int[] ft,
                                  int minGX, int minGY, int minGZ, int gsx, int gsz,
                                  int sea, int lava, int defFluid,
                                  double flowingUpdateSimilarity) {
        if (density > 0.0) {
            return new Full(0, density, false);
        }
        double margin = Math.abs(density);              // boundary: density vs 0
        if (globalAt(j, sea, lava, defFluid) == 3) return new Full(3, margin, false);

        int gx = (i - 5) >> 4;
        int gy = Math.floorDiv(j + 1, 12);
        int gz = (k - 5) >> 4;
        int dist1 = Integer.MAX_VALUE, dist2 = Integer.MAX_VALUE, dist3 = Integer.MAX_VALUE;
        int fl1 = 0, ft1 = 0, fl2 = 0, ft2 = 0, fl3 = 0, ft3 = 0;
        for (int offY = -1; offY <= 1; offY++) {
            for (int offZ = 0; offZ <= 1; offZ++) {
                for (int offX = 0; offX <= 1; offX++) {
                    int idx = getIndex(gx + offX, gy + offY, gz + offZ, minGX, minGY, minGZ, gsx, gsz);
                    long position = locCache[idx];
                    int cfl = fl[idx], cft = ft[idx];
                    int dx = BlockPos.getX(position) - i;
                    int dy = BlockPos.getY(position) - j;
                    int dz = BlockPos.getZ(position) - k;
                    int dist = dx * dx + dy * dy + dz * dz;
                    if (dist3 >= dist) { dist3 = dist; fl3 = cfl; ft3 = cft; }
                    if (dist2 >= dist) { dist3 = dist2; fl3 = fl2; ft3 = ft2; dist2 = dist; fl2 = cfl; ft2 = cft; }
                    if (dist1 >= dist) { dist2 = dist1; fl2 = fl1; ft2 = ft1; dist1 = dist; fl1 = cfl; ft1 = cft; }
                }
            }
        }
        int blockState = at(fl1, ft1, j);
        double d = 1.0 - (double) Math.abs(dist2 - dist1) / 25.0;
        if (d <= 0.0) return new Full(blockState, margin, d >= flowingUpdateSimilarity);
        if (blockState == 2 && globalAt(j - 1, sea, lava, defFluid) == 3) return new Full(blockState, margin, true);
        double e = d * pressure(fl1, ft1, fl2, ft2, j, barrier);
        margin = Math.min(margin, Math.abs(density + e));   // boundary: density+e vs 0
        if (density + e > 0.0) return new Full(0, margin, false);
        double f = 1.0 - (double) Math.abs(dist3 - dist1) / 25.0;
        if (f > 0.0) {
            double g1 = d * f * pressure(fl1, ft1, fl3, ft3, j, barrier);
            margin = Math.min(margin, Math.abs(density + g1)); // boundary: density+g1 vs 0
            if (density + g1 > 0.0) return new Full(0, margin, false);
        }
        double g = 1.0 - (double) Math.abs(dist3 - dist2) / 25.0;
        if (g > 0.0) {
            double g2 = d * g * pressure(fl2, ft2, fl3, ft3, j, barrier);
            margin = Math.min(margin, Math.abs(density + g2)); // boundary: density+g2 vs 0
            if (density + g2 > 0.0) return new Full(0, margin, false);
        }
        return new Full(blockState, margin, true);
    }

    // =====================================================================
    // ORE-VEIN reference (Phase A): pure-Java mirror of aquifer.cl's
    // aq_vein_decide — the vanilla OreVeinifier.create lambda fed the per-block
    // veinToggle/veinRidged/veinGap values, with the positional Xoroshiro drawn
    // from the ore seed exactly like the kernel. Used by BlockIdCensus as the
    // vein leg of the 3-way oracle (GPU != ref -> kernel/driver fault;
    // ref != CPU-shipped -> data-capture bug). usedRidged/usedGap report which
    // captured inputs the branch structure actually consumed, so the host can
    // hard-fail a capture miss instead of misreading it as a flip.
    // =====================================================================

    /** Vein reference result: final id (0 = null/stone, 4..9 vein) + consumed inputs. */
    public static final class Vein {
        public final int id;
        public final boolean usedRidged;
        public final boolean usedGap;

        Vein(int id, boolean usedRidged, boolean usedGap) {
            this.id = id;
            this.usedRidged = usedRidged;
            this.usedGap = usedGap;
        }
    }

    /** Vanilla OreVeinifier decision, op-for-op (called only for aquifer-null blocks). */
    public static Vein veinDecide(int x, int y, int z, double vt, double vrid, double vgap,
                                  long oreSeedLo, long oreSeedHi) {
        boolean copper = vt > 0.0;                       // VeinType: COPPER : IRON
        double d1 = Math.abs(vt);
        int jj = (copper ? 50 : -8) - y;                 // type.maxY - blockY
        int kk = y - (copper ? 0 : -60);                 // blockY - type.minY
        if (kk < 0 || jj < 0) {
            return new Vein(0, false, false);
        }
        int l = Math.min(jj, kk);
        double d2 = clampedMap(l, 0.0, 20.0, -0.2, 0.0);
        if (d1 + d2 < (double) 0.4f) {
            return new Vein(0, false, false);
        }
        long[] rs = xoroAt(oreSeedLo, oreSeedHi, x, y, z);
        if (nextFloat(rs) > 0.7f) {
            return new Vein(0, false, false);
        }
        if (vrid >= 0.0) {
            return new Vein(0, true, false);
        }
        double d3 = clampedMap(d1, (double) 0.4f, (double) 0.6f, (double) 0.1f, (double) 0.3f);
        // vanilla: `randomsource.nextFloat() < d3 && veinGap.compute(...) > -0.3F` —
        // r1 is ALWAYS drawn; veinGap is only computed (-> only captured) when
        // r1 < d3 (Java && short-circuit). usedGap mirrors that laziness so a
        // legitimately-uncaptured gap value is never flagged as a capture miss.
        boolean gapRead = (double) nextFloat(rs) < d3;
        if (gapRead && vgap > (double) -0.3f) {
            // 3rd draw ONLY inside the richness branch (vanilla stream order)
            int id = (nextFloat(rs) < 0.02f) ? (copper ? 5 : 8) : (copper ? 4 : 7);
            return new Vein(id, true, true);
        }
        return new Vein(copper ? 6 : 9, true, gapRead);
    }

    /** Mth.clampedMap(double,...): clampedLerp(outMin,outMax, inverseLerp(...)). */
    private static double clampedMap(double input, double inMin, double inMax,
                                     double outMin, double outMax) {
        double t = (input - inMin) / (inMax - inMin);
        if (t < 0.0) return outMin;
        if (t > 1.0) return outMax;
        return outMin + t * (outMax - outMin);
    }

    // --- Xoroshiro128++ mirror (rng.cl xoro_at/xoro_next_long, i.e. vanilla
    // XoroshiroPositionalRandomFactory.at + XoroshiroRandomSource.nextFloat). ---
    private static final long GOLDEN_RATIO_64 = -7046029254386353131L;
    private static final long SILVER_RATIO_64 = 7640891576956012809L;

    private static long[] xoroAt(long seedLo, long seedHi, int x, int y, int z) {
        long lo = net.minecraft.util.Mth.getSeed(x, y, z) ^ seedLo;
        long hi = seedHi;
        if ((lo | hi) == 0L) {                           // vanilla all-zero guard
            lo = GOLDEN_RATIO_64;
            hi = SILVER_RATIO_64;
        }
        return new long[]{lo, hi};
    }

    private static long nextLong(long[] s) {
        long i = s[0];
        long j = s[1];
        long k = Long.rotateLeft(i + j, 17) + i;
        j ^= i;
        s[0] = Long.rotateLeft(i, 49) ^ j ^ (j << 21);
        s[1] = Long.rotateLeft(j, 28);
        return k;
    }

    /** XoroshiroRandomSource.nextFloat() = (float)(nextLong() >>> 40) * 5.9604645E-8F. */
    private static float nextFloat(long[] s) {
        return (float) (nextLong(s) >>> 40) * 5.9604645E-8F;
    }

    private static int at(int fluidLevel, int fluidTypeId, int y) {
        return (y < fluidLevel) ? fluidTypeId : 1;
    }

    private static int globalAt(int y, int sea, int lava, int defFluid) {
        int lo = Math.min(lava, sea);
        if (y < lo) return at(lava, 3, y);
        return at(sea, defFluid, y);
    }

    private static double getQ(double i, double d, double j) {
        double e = i + 0.5 - d;
        double f = j / 2.0;
        double o = f - Math.abs(e);
        if (e > 0.0) return (o > 0.0) ? o / 1.5 : o / 2.5;
        double p = 3.0 + o;
        return (p > 0.0) ? p / 3.0 : p / 10.0;
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

    private static int getIndex(int l, int m, int n, int minGX, int minGY, int minGZ, int gsx, int gsz) {
        return ((m - minGY) * gsz + (n - minGZ)) * gsx + (l - minGX);
    }

    /** Returns the decided block id for a density&lt;=0 block. */
    public static int decide(int i, int j, int k, double density, double barrier,
                             long[] locCache, int[] fl, int[] ft,
                             int minGX, int minGY, int minGZ, int gsx, int gsz,
                             int sea, int lava, int defFluid) {
        return decideWithMargin(i, j, k, density, barrier, locCache, fl, ft,
                minGX, minGY, minGZ, gsx, gsz, sea, lava, defFluid).id;
    }

    /**
     * Returns the decided block id AND the mode-B flip-margin for a density&lt;=0 block.
     *
     * <p>The margin mirrors aquifer.cl's {@code outMargin} EXACTLY: it is initialised to
     * {@code fabs(density)} (the density-vs-0 boundary) and then {@code fmin}-accumulated with
     * {@code fabs(density + e/g1/g2)} at each of the kernel's density-sign decision points, in the
     * kernel's order, only along the branches the kernel actually reaches. The early-return paths
     * (globalAt=LAVA, similarity(d)&lt;=0, water-over-lava) leave it at {@code fabs(density)}, just
     * as the kernel does. All accumulated operands are non-negative finite, so {@link Math#min}
     * is bit-identical to OpenCL {@code fmin} here.
     */
    public static Decision decideWithMargin(int i, int j, int k, double density, double barrier,
                                            long[] locCache, int[] fl, int[] ft,
                                            int minGX, int minGY, int minGZ, int gsx, int gsz,
                                            int sea, int lava, int defFluid) {
        double margin = Math.abs(density);              // boundary: density vs 0
        if (globalAt(j, sea, lava, defFluid) == 3) return new Decision(3, margin);

        int gx = (i - 5) >> 4;
        int gy = Math.floorDiv(j + 1, 12);
        int gz = (k - 5) >> 4;
        int dist1 = Integer.MAX_VALUE, dist2 = Integer.MAX_VALUE, dist3 = Integer.MAX_VALUE;
        int fl1 = 0, ft1 = 0, fl2 = 0, ft2 = 0, fl3 = 0, ft3 = 0;
        for (int offY = -1; offY <= 1; offY++) {
            for (int offZ = 0; offZ <= 1; offZ++) {
                for (int offX = 0; offX <= 1; offX++) {
                    int idx = getIndex(gx + offX, gy + offY, gz + offZ, minGX, minGY, minGZ, gsx, gsz);
                    long position = locCache[idx];
                    int cfl = fl[idx], cft = ft[idx];
                    int dx = BlockPos.getX(position) - i;
                    int dy = BlockPos.getY(position) - j;
                    int dz = BlockPos.getZ(position) - k;
                    int dist = dx * dx + dy * dy + dz * dz;
                    if (dist3 >= dist) { dist3 = dist; fl3 = cfl; ft3 = cft; }
                    if (dist2 >= dist) { dist3 = dist2; fl3 = fl2; ft3 = ft2; dist2 = dist; fl2 = cfl; ft2 = cft; }
                    if (dist1 >= dist) { dist2 = dist1; fl2 = fl1; ft2 = ft1; dist1 = dist; fl1 = cfl; ft1 = cft; }
                }
            }
        }
        int blockState = at(fl1, ft1, j);
        double d = 1.0 - (double) Math.abs(dist2 - dist1) / 25.0;
        if (d <= 0.0) return new Decision(blockState, margin);
        if (blockState == 2 && globalAt(j - 1, sea, lava, defFluid) == 3) return new Decision(blockState, margin);
        double e = d * pressure(fl1, ft1, fl2, ft2, j, barrier);
        margin = Math.min(margin, Math.abs(density + e));   // boundary: density+e vs 0
        if (density + e > 0.0) return new Decision(0, margin);
        double f = 1.0 - (double) Math.abs(dist3 - dist1) / 25.0;
        if (f > 0.0) {
            double g1 = d * f * pressure(fl1, ft1, fl3, ft3, j, barrier);
            margin = Math.min(margin, Math.abs(density + g1)); // boundary: density+g1 vs 0
            if (density + g1 > 0.0) return new Decision(0, margin);
        }
        double g = 1.0 - (double) Math.abs(dist3 - dist2) / 25.0;
        if (g > 0.0) {
            double g2 = d * g * pressure(fl2, ft2, fl3, ft3, j, barrier);
            margin = Math.min(margin, Math.abs(density + g2)); // boundary: density+g2 vs 0
            if (density + g2 > 0.0) return new Decision(0, margin);
        }
        return new Decision(blockState, margin);
    }
}
