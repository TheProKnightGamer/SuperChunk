// =====================================================================
// SuperChunk GPU backend — Aquifer block/fluid DECISION in OpenCL C.
//
// VERIFY-ONLY port of the per-block aquifer decision as the test server
// actually runs it: the C2ME @Overwrite of
//   net.minecraft.world.level.levelgen.Aquifer.NoiseBasedAquifer.computeSubstance
// (dev.superchunk...mixin.aquifer.MixinAquiferSamplerImpl) for the density<=0
// branch — ported op-for-op (incl. C2ME's refreshDistPosIdx / applyPost order)
// so that, FED THE SAME INPUTS the CPU used, the GPU returns the identical
// block id.
//
// WHAT IS PORTED (and thus GPU-verified): the global fluid picker, the 2x3x2
// nearest-3 aquifer-cell grid search over the (CPU-preloaded) location cache,
// similarity(), the pressure/barrier algebra (calculatePressure / getQ /
// postCalculateDensity) and the density-sign decisions.
//
// WHAT IS NOT PORTED (fed from the CPU, NOT GPU-verified — clearly labelled in
// the host report): per-aquifer-cell FluidStatus = computeFluid(...), because it
// needs preliminarySurfaceLevel's 48-step scan of the spline-bearing
// initialDensityWithoutJaggedness. The host uploads the CPU's per-cell
// (fluidLevel, fluidTypeId). barrierNoise(x,y,z) is also fed from the CPU's own
// value (a noise DF, separately proven GPU-bit-exact) so the decision port is
// isolated.
//
// PRECISION: the aquifer math is vanilla `double`; this kernel REQUIRES fp64
// (cl_khr_fp64). On a non-fp64 device the host skips the aquifer verify. The
// integer RNG helpers come from the host-prepended rng.cl.
//
// FP_CONTRACT OFF so a*b+c is two separately-rounded ops (vanilla never fuses).
// =====================================================================

#pragma OPENCL EXTENSION cl_khr_fp64 : enable
#pragma OPENCL FP_CONTRACT OFF

// Block-id encoding (matches the host mapping of the CPU's returned BlockState):
//   0 = null  (computeSubstance returned null -> default block / stone)
//   1 = AIR
//   2 = WATER
//   3 = LAVA
#define AQ_NULL  0
#define AQ_AIR   1
#define AQ_WATER 2
#define AQ_LAVA  3

// Math.floorDiv(x,y) (used with y>0).
inline int aq_floorDiv(int x, int y) {
    int q = x / y;
    if (((x ^ y) < 0) && (q * y != x)) q--;
    return q;
}

// NoiseBasedAquifer.getIndex(l,m,n).
inline int aq_getIndex(int l, int m, int n, int minGridX, int minGridY, int minGridZ,
                       int gridSizeX, int gridSizeZ) {
    int i = l - minGridX;
    int j = m - minGridY;
    int k = n - minGridZ;
    return (j * gridSizeZ + k) * gridSizeX + i;
}

// NoiseBasedAquifer.similarity(a,b) = 1.0 - (double)Math.abs(b-a)/25.0.
inline double aq_similarity(int a, int b) {
    int d = b - a;
    int ad = d < 0 ? -d : d;
    return 1.0 - (double) ad / 25.0;
}

// FluidStatus.at(y) -> id, given (fluidLevel, fluidTypeId).
inline int aq_at(int fluidLevel, int fluidTypeId, int y) {
    return (y < fluidLevel) ? fluidTypeId : AQ_AIR;
}

// globalFluidPicker.computeFluid(.,y,.).at(y) -> id.
//   y < min(lavaLevel, seaLevel) ? FluidStatus(lavaLevel,LAVA) : FluidStatus(seaLevel,defaultFluid)
inline int aq_globalAt(int y, int seaLevel, int lavaLevel, int defaultFluidId) {
    int fl, ft;
    int lo = (lavaLevel < seaLevel) ? lavaLevel : seaLevel;
    if (y < lo) { fl = lavaLevel; ft = AQ_LAVA; }
    else        { fl = seaLevel;  ft = defaultFluidId; }
    return aq_at(fl, ft, y);
}

// aquiferExtracted$getQ(i,d,j) — i=blockY, d=0.5*(flA+flB), j=abs(flA-flB).
inline double aq_getQ(double i, double d, double j) {
    double e = i + 0.5 - d;
    double f = j / 2.0;
    double o = f - fabs(e);
    double q;
    if (e > 0.0) {
        q = (o > 0.0) ? (o / 1.5) : (o / 2.5);
    } else {
        double p = 3.0 + o;
        q = (p > 0.0) ? (p / 3.0) : (p / 10.0);
    }
    return q;
}

// c2me$calculateDensityModified(fsA, fsB) using the single fed barrier value.
//   barrier is barrierNoise.compute(pos) (the MutableDouble memoized once/block).
inline double aq_pressure(int flA, int ftA, int flB, int ftB, int j, double barrier) {
    int bsA = aq_at(flA, ftA, j);
    int bsB = aq_at(flB, ftB, j);
    // NOT( (LAVA&&WATER) || (WATER&&LAVA) )  -> compute; else 2.0
    bool lavaWater = (bsA == AQ_LAVA && bsB == AQ_WATER) || (bsA == AQ_WATER && bsB == AQ_LAVA);
    if (!lavaWater) {
        int dd = flA - flB;
        int jj = dd < 0 ? -dd : dd;          // Math.abs(flA - flB)
        if (jj == 0) return 0.0;
        double d0 = 0.5 * (double)(flA + flB);
        double q = aq_getQ((double) j, d0, (double) jj);
        double r;
        if (!(q < -2.0) && !(q > 2.0)) r = barrier; else r = 0.0;
        return 2.0 * (r + q);
    } else {
        return 2.0;
    }
}

// C2ME/vanilla FLOWING_UPDATE_SIMULARITY = similarity(Mth.square(10), Mth.square(12))
// = 1.0 - (double)|144-100|/25.0. Same IEEE ops as Java -> bit-identical constant.
inline double aq_flowing_update_similarity() {
    return 1.0 - (double) 44 / 25.0;
}

// =====================================================================
// HIGH-AIR fast path (production sc_decide_multichunk only; the census
// kernels below keep calling the FULL aq_decide).
//
// For a density<=0 block with y >= airSkipY, where
//   airSkipY = max(all candidate cells' fluidLevel, seaLevel) + 5   (host aux[17]),
// the full aq_decide provably returns AIR, and its sched bit depends ONLY on
// d = similarity(dist1, dist2):
//   * globalAt(y): y >= seaLevel+5 > min(lava, sea) -> the air branch, never LAVA.
//   * blockState = aq_at(fl1, ft1, y): fl1 <= airSkipY-5 < y -> AIR.
//   * d <= 0 early return: returns AIR with sched = (d >= FLOWING_UPDATE_SIMULARITY).
//   * water-over-lava branch: blockState is AIR, never taken.
//   * every pressure pair (flA, flB): both aq_at are AIR (no lavaWater); jj == 0
//     returns 0; else o = jj/2 - |y+0.5-mid| = max(flA,flB) - y - 0.5 <= -5.5
//     (y >= max+5) -> q = o/2.5 < -2 -> the |q|<=2 window is closed, so the
//     BARRIER NOISE IS NEVER CONSUMED and pressure = 2*q < -4 -> every
//     density+e / density+g term stays <= 0 -> no branch can return stone ->
//     falls through to `return blockState (AIR), sched=1`.
// Hence the byte is exactly (AIR | (d<=0 ? (d>=flowingSim) : 1) << 7), and only
// dist1/dist2 are needed: a reduced top-2 distance loop with NO fluidLevel/
// fluidTypeId loads, no 3rd tier, no pos/fl/ft tracking, no blockState, no
// pressure chain, no barrier evaluation. The 2-tier insertion below evolves
// dist1/dist2 EXACTLY like the full 3-tier cascade (case analysis: dist<=dist1
// -> shift; dist1<dist<=dist2 -> replace dist2; else no-op — identical in all
// three cases, ties included).
// =====================================================================
inline double aq_airpath_similarity(int i, int j, int k,
        __global const long* locCache,
        int minGridX, int minGridY, int minGridZ, int gridSizeX, int gridSizeZ) {
    int gx = (i - 5) >> 4;
    int gy = aq_floorDiv(j + 1, 12);
    int gz = (k - 5) >> 4;
    int dist1 = 0x7FFFFFFF, dist2 = 0x7FFFFFFF;
    for (int offY = -1; offY <= 1; ++offY) {
        for (int offZ = 0; offZ <= 1; ++offZ) {
            for (int offX = 0; offX <= 1; ++offX) {
                int idx = aq_getIndex(gx + offX, gy + offY, gz + offZ,
                                      minGridX, minGridY, minGridZ, gridSizeX, gridSizeZ);
                long position = locCache[idx];
                int dx = mc_bp_getX(position) - i;
                int dy = mc_bp_getY(position) - j;
                int dz = mc_bp_getZ(position) - k;
                int dist = dx * dx + dy * dy + dz * dz;
                if (dist2 >= dist) { dist2 = dist; }
                if (dist1 >= dist) { dist2 = dist1; dist1 = dist; }
            }
        }
    }
    return aq_similarity(dist1, dist2);
}

// =====================================================================
// ORE-VEIN decision (vanilla OreVeinifier.create lambda, 1.21.1) — the SINGLE
// shared implementation used by BOTH the production compact-ids decide kernel
// (sc_decide_multichunk, generated by CompactIds.kernelEntry) and the Stage-3
// census kernel (aquifer_census below), so the census proves exactly the code
// the production path runs.
//
// Vanilla semantics (MaterialRuleList order): the OreVeinifier filler runs for
// every block where the aquifer returned null — BOTH the density>0 early solid
// AND the density<=0 barrier-solidified stone (all computeSubstance null paths
// set shouldScheduleFluidUpdate=false). Callers therefore invoke this whenever
// the aquifer-level id is AQ_NULL (and veins are enabled for the dimension).
//
// Inputs are the per-block values of the three vein router slots:
//   vt   = veinToggle  — interpolated(rangeChoice(Y,-60,51, noise(ORE_VEININESS,1.5,1.5), 0))
//   vrid = veinRidged  — add(-0.08f, max(abs(interp(rangeChoice(..A..))), abs(interp(rangeChoice(..B..)))))
//   vgap = veinGap     — noise(ORE_GAP)   (plain noise, no interpolated marker)
// (production kernel: value-order grid trilerps + the emitted REAL tail ASTs;
// census: the CPU-captured per-block values — bit-identical by the OnDeviceInterp
// value-path proof). The vein DFs are pure, so eager evaluation of vrid/vgap by a
// caller cannot change the decision; the RNG stream below draws in EXACT vanilla
// order (r1 always drawn before the vgap test; r2 only inside the ore branch).
//
// Returns 0 (vanilla null -> stone falls through) or the vein ids
//   4 copper_ore  5 raw_copper_block  6 granite   (COPPER, y in [0,50])
//   7 deepslate_iron_ore  8 raw_iron_block  9 tuff (IRON,   y in [-60,-8])
// =====================================================================

// Mth.clampedMap(double,...) = clampedLerp(outMin, outMax, inverseLerp(input, inMin, inMax)),
// hard-typed double (aquifer.cl builds without dfc_support.cl's `real` helpers).
inline double aq_clamped_map(double input, double inMin, double inMax,
                             double outMin, double outMax) {
    double t = (input - inMin) / (inMax - inMin);   // Mth.inverseLerp
    if (t < 0.0) return outMin;                     // Mth.clampedLerp
    if (t > 1.0) return outMax;
    return outMin + t * (outMax - outMin);          // Mth.lerp
}

// XoroshiroRandomSource.nextFloat() = (float)(nextLong() >>> 40) * 5.9604645E-8F
// (bit-exact: the 24-bit value fits a float mantissa exactly; xoro_next_long is
// the bit-exact-proven Xoroshiro128++ port from rng.cl).
inline float aq_next_float(__private xoro_t* s) {
    return (float)(((ulong) xoro_next_long(s)) >> 40) * 5.9604645e-8f;
}

// LAZY-STAGED form. Vanilla's own lambda evaluates veinGap lazily (the
// `nextFloat() < d3 && veinGap.compute(pos) > -0.3F` short-circuit) and only
// reads veinRidged after the band/veininess/skip gates — so callers that
// evaluate vrid/vgap ONLY when the corresponding stage demands them are not
// merely value-identical (pure functions), they reproduce vanilla's exact
// evaluation order. RNG stream order is preserved across the stages: the skip
// draw in stage 1, the d3 draw in stage 2 (always drawn when reached), the ore
// draw in stage 3 (only when vgap passed) — identical to the monolithic form.
#define AQ_VEIN_CONT (-3)

// Stage 1 (needs only vt): vein type + y-band + veininess edge gate + the 0.7f
// skip draw. Returns AQ_NULL or AQ_VEIN_CONT with *rs/*copper/*d1 primed.
inline int aq_vein_stage1(int x, int y, int z, double vt,
                          long oreSeedLo, long oreSeedHi,
                          __private xoro_t* rs, __private int* copper,
                          __private double* d1) {
    int cp = (vt > 0.0) ? 1 : 0;                     // VeinType: COPPER : IRON
    double dd1 = fabs(vt);                           // Math.abs(d0)
    int jj = (cp ? 50 : -8) - y;                     // type.maxY - blockY
    int kk = y - (cp ? 0 : -60);                     // blockY - type.minY
    if (kk < 0 || jj < 0) {                          // outside the vein type's band
        return AQ_NULL;
    }
    int l = jj < kk ? jj : kk;                       // Math.min(j, k)
    double d2 = aq_clamped_map((double) l, 0.0, 20.0, -0.2, 0.0);
    if (dd1 + d2 < (double) 0.4f) {                  // veininess edge gate (0.4F -> double)
        return AQ_NULL;
    }
    *rs = xoro_at(oreSeedLo, oreSeedHi, x, y, z);    // random.at(blockX, blockY, blockZ)
    if (aq_next_float(rs) > 0.7f) {                  // skip rate (FLOAT compare)
        return AQ_NULL;
    }
    *copper = cp;
    *d1 = dd1;
    return AQ_VEIN_CONT;
}

// Stage 2 (needs vrid): ridged gate + the d3 draw. Returns AQ_NULL, a filler id
// (the d3 draw failed — vanilla never evaluates vgap on this path), or
// AQ_VEIN_CONT (caller must evaluate vgap and finish with stage 3).
inline int aq_vein_stage2(double vrid, double d1, int copper,
                          __private xoro_t* rs) {
    if (vrid >= 0.0) {                               // ridged gate
        return AQ_NULL;
    }
    double d3 = aq_clamped_map(d1, (double) 0.4f, (double) 0.6f, (double) 0.1f, (double) 0.3f);
    if ((double) aq_next_float(rs) < d3) {
        return AQ_VEIN_CONT;
    }
    return copper ? 6 : 9;                           // filler (granite / tuff)
}

// Stage 3 (needs vgap): the gap gate + the ore/raw draw (drawn ONLY when the
// gap gate passes — vanilla stream order).
inline int aq_vein_stage3(double vgap, int copper, __private xoro_t* rs) {
    if (vgap > (double) -0.3f) {
        return (aq_next_float(rs) < 0.02f) ? (copper ? 5 : 8)    // raw ore block
                                           : (copper ? 4 : 7);   // ore
    }
    return copper ? 6 : 9;                           // filler (granite / tuff)
}

// The vanilla lambda, op-for-op — now a thin composition of the three stages
// (eager callers like the census kernels are unchanged; the staging is
// draw-order-identical to the previous monolithic body).
inline int aq_vein_decide(int x, int y, int z,
                          double vt, double vrid, double vgap,
                          long oreSeedLo, long oreSeedHi) {
    xoro_t rs;
    int copper;
    double d1;
    int r = aq_vein_stage1(x, y, z, vt, oreSeedLo, oreSeedHi, &rs, &copper, &d1);
    if (r != AQ_VEIN_CONT) {
        return AQ_NULL;
    }
    r = aq_vein_stage2(vrid, d1, copper, &rs);
    if (r != AQ_VEIN_CONT) {
        return r;
    }
    return aq_vein_stage3(vgap, copper, &rs);
}

// =====================================================================
// The decision. Returns the block id; writes the minimum absolute density
// margin to a sign-flip boundary into *outMargin (smaller = closer to flipping
// if `density` is perturbed by the GPU finalDensity spline error -> the mode-B
// "does it flip blocks" susceptibility).
//
// STAGE-3 census extension: *outSched receives the vanilla per-block
// shouldScheduleFluidUpdate bit (1/0), ported branch-for-branch from the C2ME
// computeSubstance/applyPost chain (MixinAquiferSamplerImpl):
//   globalAt==LAVA           -> 0
//   similarity d <= 0        -> (d >= FLOWING_UPDATE_SIMULARITY)
//   water over global lava   -> 1
//   any pressure null (stone)-> 0
//   final fluid/air result   -> 1
// doFill only ACTS on the bit when the placed state has a non-empty fluid
// (water/lava) — the host compares both the raw bit and that effective bit.
// =====================================================================
inline int aq_decide(
        int i, int j, int k, double density, double barrier,
        __global const long* locCache,
        __global const int* fluidLevel,
        __global const int* fluidTypeId,
        int minGridX, int minGridY, int minGridZ, int gridSizeX, int gridSizeZ,
        int seaLevel, int lavaLevel, int defaultFluidId,
        double flowingUpdateSimilarity,
        __private double* outMargin, __private int* outSched,
        __private int* outDist1, __private int* outFl1,
        __private int* outIdx1, __private long* outPos1) {

    // density<=0 branch only (host filters; density>0 -> AQ_NULL handled host-side).
    double margin = fabs(density);              // boundary: density vs 0 (density>0 -> null)
    *outDist1 = -1; *outFl1 = -2147483648; *outIdx1 = -1; *outPos1 = 0; // diagnostics
    *outSched = 0;

    int globalAtJ = aq_globalAt(j, seaLevel, lavaLevel, defaultFluidId);
    if (globalAtJ == AQ_LAVA) { *outMargin = margin; return AQ_LAVA; }

    // ----- refreshDistPosIdx (C2ME order: offY -1..1, offZ 0..1, offX 0..1) -----
    int gx = (i - 5) >> 4;
    int gy = aq_floorDiv(j + 1, 12);
    int gz = (k - 5) >> 4;
    int dist1 = 0x7FFFFFFF, dist2 = 0x7FFFFFFF, dist3 = 0x7FFFFFFF;
    long pos1 = 0, pos2 = 0, pos3 = 0;
    int fl1 = 0, ft1 = 0, fl2 = 0, ft2 = 0, fl3 = 0, ft3 = 0;

    for (int offY = -1; offY <= 1; ++offY) {
        for (int offZ = 0; offZ <= 1; ++offZ) {
            for (int offX = 0; offX <= 1; ++offX) {
                int idx = aq_getIndex(gx + offX, gy + offY, gz + offZ,
                                      minGridX, minGridY, minGridZ, gridSizeX, gridSizeZ);
                long position = locCache[idx];
                int cfl = fluidLevel[idx];
                int cft = fluidTypeId[idx];
                int dx = mc_bp_getX(position) - i;
                int dy = mc_bp_getY(position) - j;
                int dz = mc_bp_getZ(position) - k;
                int dist = dx * dx + dy * dy + dz * dz;
                if (dist3 >= dist) { pos3 = position; dist3 = dist; fl3 = cfl; ft3 = cft; }
                if (dist2 >= dist) { pos3 = pos2; dist3 = dist2; fl3 = fl2; ft3 = ft2;
                                     pos2 = position; dist2 = dist; fl2 = cfl; ft2 = cft; }
                if (dist1 >= dist) { pos2 = pos1; dist2 = dist1; fl2 = fl1; ft2 = ft1;
                                     pos1 = position; dist1 = dist; fl1 = cfl; ft1 = cft; }
            }
        }
    }

    *outDist1 = dist1; *outFl1 = fl1;                    // diagnostics
    *outPos1 = pos1;
    *outIdx1 = aq_getIndex(mc_bp_getX(pos1) >> 4, aq_floorDiv(mc_bp_getY(pos1), 12),
                           mc_bp_getZ(pos1) >> 4, minGridX, minGridY, minGridZ, gridSizeX, gridSizeZ);

    // ----- applyPost -----
    int blockState = aq_at(fl1, ft1, j);                 // fluidLevel2.at(j)
    double d = aq_similarity(dist1, dist2);
    if (d <= 0.0) {
        // vanilla: shouldScheduleFluidUpdate = d >= FLOWING_UPDATE_SIMULARITY
        *outSched = (d >= flowingUpdateSimilarity) ? 1 : 0;
        *outMargin = margin;
        return blockState;
    }
    // blockState.is(WATER) && global(i,j-1,k).at(j-1).is(LAVA)
    if (blockState == AQ_WATER && aq_globalAt(j - 1, seaLevel, lavaLevel, defaultFluidId) == AQ_LAVA) {
        *outSched = 1;                                   // vanilla: true
        *outMargin = margin;
        return blockState;
    }
    double e = d * aq_pressure(fl1, ft1, fl2, ft2, j, barrier);
    margin = fmin(margin, fabs(density + e));            // boundary: density+e vs 0
    if (density + e > 0.0) { *outSched = 0; *outMargin = margin; return AQ_NULL; }

    // getFinalBlockState
    double f = aq_similarity(dist1, dist3);
    if (f > 0.0) {
        double g1 = d * f * aq_pressure(fl1, ft1, fl3, ft3, j, barrier);
        margin = fmin(margin, fabs(density + g1));
        if (density + g1 > 0.0) { *outSched = 0; *outMargin = margin; return AQ_NULL; }
    }
    double g = aq_similarity(dist2, dist3);
    if (g > 0.0) {
        double g2 = d * g * aq_pressure(fl2, ft2, fl3, ft3, j, barrier);
        margin = fmin(margin, fabs(density + g2));
        if (density + g2 > 0.0) { *outSched = 0; *outMargin = margin; return AQ_NULL; }
    }
    *outSched = 1;                                       // vanilla: true (final fluid/air result)
    *outMargin = margin;
    return blockState;
}

__kernel void aquifer_decide(
        __global const int* bx, __global const int* by, __global const int* bz,
        __global const double* dens,
        __global const double* barr,
        __global const long* locCache,
        __global const int* fluidLevel,
        __global const int* fluidTypeId,
        const int minGridX, const int minGridY, const int minGridZ,
        const int gridSizeX, const int gridSizeZ,
        const int seaLevel, const int lavaLevel, const int defaultFluidId,
        __global int* outId,
        __global double* outMargin,
        __global int* outDist1,
        __global int* outFl1,
        __global int* outIdx1,
        __global long* outPos1,
        const int n) {
    int gid = get_global_id(0);
    if (gid >= n) return;
    double m;
    int sched;                                  // computed but not read back on this legacy path
    int d1, f1, ix1;
    long p1;
    int id = aq_decide(bx[gid], by[gid], bz[gid], dens[gid], barr[gid],
                       locCache, fluidLevel, fluidTypeId,
                       minGridX, minGridY, minGridZ, gridSizeX, gridSizeZ,
                       seaLevel, lavaLevel, defaultFluidId,
                       aq_flowing_update_similarity(),
                       &m, &sched, &d1, &f1, &ix1, &p1);
    outId[gid] = id;
    outMargin[gid] = m;
    outDist1[gid] = d1;
    outFl1[gid] = f1;
    outIdx1[gid] = ix1;
    outPos1[gid] = p1;
}

// =====================================================================
// STAGE-3 BLOCK-ID CENSUS kernel (-Dsuperchunk.gpu.blockIdVerify).
//
// Per candidate block: the GPU makes the NOISE-STAGE aquifer decision on the
// GPU's OWN finalDensity (the host feeds the per-block density of the
// self-consistent mode-C regime — see BlockIdCensus.java for the proof leg
// that the fed value IS the GPU full-field density bit-for-bit) and emits
//   outId    — 0=null/stone (incl. every density>0 solid), 1=air, 2=water, 3=lava
//              (the AQUIFER-level decision, pre-vein — the existing 3-way oracle)
//   outFinal — the FINAL block id after the ore-vein filler (0..9): whenever the
//              aquifer said null AND veins are enabled for this dispatch, the
//              shared aq_vein_decide (the exact production vein path) runs on the
//              CPU-captured per-block vein DF values (veinT/veinR/veinG) + the
//              in-kernel positional Xoroshiro draws. veinMask bit0 says the CPU
//              veinifier actually computed veinToggle for this block (it always
//              does for aquifer-null blocks in vein-enabled dims); without it the
//              vein step is skipped and the host counts a capture miss.
//   outSched — the vanilla shouldScheduleFluidUpdate bit (see aq_decide header)
//   outMargin— min |distance| to a decision sign-flip boundary (tie susceptibility)
// density>0 solids are decided IN-KERNEL (vanilla computeSubstance's early
// null return, sched=false) so the whole block alphabet rides one dispatch.
// Beard-sentinel blocks are dispatched too but EXCLUDED host-side (their
// density carries the CPU-only beard contribution).
// =====================================================================
__kernel void aquifer_census(
        __global const int* bx, __global const int* by, __global const int* bz,
        __global const double* dens,
        __global const double* barr,
        __global const long* locCache,
        __global const int* fluidLevel,
        __global const int* fluidTypeId,
        const int minGridX, const int minGridY, const int minGridZ,
        const int gridSizeX, const int gridSizeZ,
        const int seaLevel, const int lavaLevel, const int defaultFluidId,
        const double flowingUpdateSimilarity,
        __global const double* veinT,
        __global const double* veinR,
        __global const double* veinG,
        __global const int* veinMask,
        const long oreSeedLo, const long oreSeedHi,
        const int veinsOn,
        __global int* outId,
        __global int* outFinal,
        __global double* outMargin,
        __global int* outSched,
        const int n) {
    int gid = get_global_id(0);
    if (gid >= n) return;
    double density = dens[gid];
    int id;
    double m;
    int sched;
    if (density > 0.0) {
        // vanilla computeSubstance: density>0 -> null (solid), shouldScheduleFluidUpdate=false
        id = AQ_NULL;
        m = density;                            // distance to the density-vs-0 boundary
        sched = 0;
    } else {
        int d1, f1, ix1;
        long p1;
        id = aq_decide(bx[gid], by[gid], bz[gid], density, barr[gid],
                       locCache, fluidLevel, fluidTypeId,
                       minGridX, minGridY, minGridZ, gridSizeX, gridSizeZ,
                       seaLevel, lavaLevel, defaultFluidId,
                       flowingUpdateSimilarity,
                       &m, &sched, &d1, &f1, &ix1, &p1);
    }
    // MaterialRuleList order: every aquifer-null block falls through to the
    // OreVeinifier filler (density>0 solids AND barrier-solidified stone).
    int fin = id;
    if (id == AQ_NULL && veinsOn != 0 && (veinMask[gid] & 1) != 0) {
        fin = aq_vein_decide(bx[gid], by[gid], bz[gid],
                             veinT[gid], veinR[gid], veinG[gid],
                             oreSeedLo, oreSeedHi);
    }
    outId[gid] = id;
    outFinal[gid] = fin;
    outMargin[gid] = m;
    outSched[gid] = sched;
}
