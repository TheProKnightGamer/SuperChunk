// =====================================================================
// SuperChunk GPU backend — Stage 1: Minecraft worldgen noise in OpenCL C.
//
// Faithful port of vanilla 1.21.1:
//   net.minecraft.world.level.levelgen.synth.ImprovedNoise
//   net.minecraft.world.level.levelgen.synth.PerlinNoise
//   net.minecraft.world.level.levelgen.synth.NormalNoise
//
// PRECISION-PARAMETERIZED. The host (CLProgram) passes -DUSE_FP32 when the
// chosen OpenCL device lacks cl_khr_fp64 (e.g. Intel Iris Xe). On an fp64
// device (e.g. RTX 3070) USE_FP32 is NOT defined, cl_khr_fp64 is enabled and
// `real` becomes `double`, matching vanilla's double precision exactly.
//
// All algebra below mirrors the vanilla source op-for-op so that, at fp64,
// the result is bit-for-bit identical. At fp32 it is structurally identical
// (same algorithm, ~1e-3..1e-4 relative error from reduced mantissa).
// =====================================================================

#ifdef USE_FP32
typedef float  real;
// NOTE: do NOT typedef a name like `rint` — it shadows the OpenCL built-in
// round-to-nearest-even function and is a hard compile error.
#define REAL_C(x) ((float)(x))
#else
#pragma OPENCL EXTENSION cl_khr_fp64 : enable
typedef double real;
#define REAL_C(x) ((double)(x))
#endif

// Disable floating-point contraction (a*b+c fused into a single-rounding fma).
// OpenCL C defaults FP_CONTRACT to ON, so the device compiler (e.g. NVIDIA on
// the RTX 3070 fp64 target) would fuse multiply-adds in grad_dot, mc_lerp,
// mc_fade, mc_lerp2/3 and the octave accumulation. Vanilla Java never contracts
// (two separately-rounded ops), so contraction diverges the result by ~1 ULP at
// each site, compounding across octaves and both NormalNoise samplers and
// breaking the BIT-IDENTICAL-on-fp64 parity requirement. Placed at file scope
// (outside any function) it applies to the entire translation unit per the
// OpenCL spec, covering both the fp32 and fp64 precision branches above.
#pragma OPENCL FP_CONTRACT OFF

// SC_INLINE: normally `inline` (the whole noise chain inlines into each generated
// kernel). When the host builds with -DSC_NOINLINE (gated by
// -Dsuperchunk.gpu.noinlineHelpers) the HEAVY helpers become real non-inlined
// functions instead, so each generated finalDensity kernel stays small. This is a
// COLD-COMPILE lever for drivers whose OpenCL optimizer is super-linearly slow on
// the large inlined kernels (CUDA 13.2 / nvidia-driver-595-open: cold boot ~1h).
// Optimization stays ON, so fp64 results are unchanged (inlining does not alter IEEE
// arithmetic; FP_CONTRACT is OFF regardless) — the boot parity self-test is the gate.
// The tiny leaf helpers (mc_lerp/mc_fade/grad_dot/perm_at/...) stay `inline`: forcing
// calls on them only adds overhead without shrinking the kernel meaningfully.
#ifdef SC_NOINLINE
#define SC_INLINE __attribute__((noinline))
#else
#define SC_INLINE inline
#endif

// --- Mth.floor(double) -> int (vanilla util) -----------------------------
// int i=(int)v; return v < (double)i ? i-1 : i;
inline int mc_floor(real v) {
    int i = (int) v;
    return (v < (real) i) ? (i - 1) : i;
}

// --- Mth.floor(v) AND v - (double)Mth.floor(v), in one shot --------------
//
// Every ImprovedNoise axis needs both the integer lattice cell and the in-cell fraction,
// and pays mc_floor's convert-toward-zero + convert-back + compare + select before the
// subtract. The hardware `floor` is ONE DP op and, for |v| < 2^31 (the only range in which
// mc_floor's int means anything), `(real) mc_floor(v) == floor(v)` by the definition of
// floor — so the int comes straight out of it.
//
// The fraction is deliberately taken against the INT ROUND-TRIP `(real) i`, not against
// `f`, and the two differ at exactly one input: v == -0.0, where floor gives -0.0 and
// `v - f` is +0.0 while vanilla's `v - (double) 0` is -0.0. Sign-of-zero survives into
// grad_dot (which negates its operands) and mc_fade, so that one input has to match. The
// round-trip costs one convert and keeps the identity unconditional rather than resting on
// "ImprovedNoise's offsets are non-negative so v is never -0.0" — true today, but not
// something terrain parity should depend on.
//
// This matters at all because the corner kernel is fp64-THROUGHPUT bound: the identical
// code built fp32 runs 16.8x faster on GA104, so DP-pipeline op count is the cost that counts.
inline int mc_floor_frac(real v, real* frac) {
    real f = floor(v);
    int i = (int) f;
    *frac = v - (real) i;
    return i;
}

// --- Mth.lfloor(double) -> long (used by PerlinNoise.wrap) ---------------
// At fp32 the magnitudes involved in wrap() stay small enough that a 32-bit
// path is exact for our sample ranges; we still use a long for fp64 fidelity.
inline long mc_lfloor(real v) {
    long i = (long) v;
    return (v < (real) i) ? (i - 1L) : i;
}

// --- Mth.smoothstep(t) = t*t*t*(t*(t*6-15)+10) (the Perlin fade curve) ---
inline real mc_fade(real t) {
    return t * t * t * (t * (t * REAL_C(6.0) - REAL_C(15.0)) + REAL_C(10.0));
}

// --- Mth.lerp(t,a,b) = a + t*(b-a) --------------------------------------
inline real mc_lerp(real t, real a, real b) {
    return a + t * (b - a);
}

// --- Mth.lerp2 ----------------------------------------------------------
inline real mc_lerp2(real tx, real ty, real x0y0, real x1y0, real x0y1, real x1y1) {
    return mc_lerp(ty, mc_lerp(tx, x0y0, x1y0), mc_lerp(tx, x0y1, x1y1));
}

// --- Mth.lerp3 ----------------------------------------------------------
inline real mc_lerp3(real tx, real ty, real tz,
                     real v000, real v100, real v010, real v110,
                     real v001, real v101, real v011, real v111) {
    return mc_lerp(tz,
                   mc_lerp2(tx, ty, v000, v100, v010, v110),
                   mc_lerp2(tx, ty, v001, v101, v011, v111));
}

// SimplexNoise.GRADIENT — REFERENCE ONLY. No kernel reads this table since grad_dot went
// branchless below; it is kept because grad_dot's coordinate selection is derived from it and
// must stay checkable against it. If a row ever changes, grad_dot must be re-derived.
__constant int GRADIENT[16][3] = {
    { 1,  1,  0}, {-1,  1,  0}, { 1, -1,  0}, {-1, -1,  0},
    { 1,  0,  1}, {-1,  0,  1}, { 1,  0, -1}, {-1,  0, -1},
    { 0,  1,  1}, { 0, -1,  1}, { 0,  1, -1}, { 0, -1, -1},
    { 1,  1,  0}, { 0, -1,  1}, {-1,  1,  0}, { 0, -1, -1}
};

// SimplexNoise.dot(GRADIENT[idx&15], x, y, z) — BIT-EXACT branchless form.
//
// Every row above has exactly one 0 and two +/-1 entries, so the table version spends 3 fp64
// multiplies (two by +/-1, one by 0) plus a per-lane __constant gather to compute what two
// negations and two adds give. On GA104 that costs twice over: fp64 runs at 1/64 rate, and a
// divergent __constant read serializes per distinct address in a warp. grad_dot is called 8x per
// improved_noise, and the corner kernel driving it is 69% of GPU time here (measured,
// [gpu-timeline]).
//
// This is vanilla ImprovedNoise/classic-Perlin's own u/v selection, which is why there is no
// special case: u is the coordinate scaled by the FIRST non-zero entry, v by the second, w the
// one scaled by 0. Exactness, term by term, against `(real)g[k] * c`:
//   g=+1 -> 1.0*c  == c                    (exact for every c)
//   g=-1 -> -1.0*c == -c                   (exact for every c, incl. +/-0.0)
//   g= 0 -> 0.0*c  == copysign(0.0, c)     (finite c)
// The zero term is NOT dropped: 0.0*c is -0.0 for c<0, and (-0.0)+(+0.0) = +0.0 while
// (-0.0)+(-0.0) = -0.0, so discarding it can flip the sign of a zero result. It is placed last
// rather than in its original x/y/z position: a single IEEE add is commutative bit-for-bit, and a
// sum is -0.0 only when every addend is -0.0, which no reordering changes.
//
// MEASURED (RTX 3070, r2048 GPU pregen): the largest single win of the round. Dropping the fp64
// multiplies and the gather took the change ladder 69 s -> 64 s; moving from a hand-derived
// per-row case analysis to this u/v form (3 copysigns -> 1, no h==14 special case) took an
// interleaved A/B a further 62 s -> 59 s. All GPU parity gates stay at exactly 0.000e+00
// (ImprovedNoise / PerlinNoise / NormalNoise / DFC / GPU-vs-vanilla bit-exact) at every step, and
// compactIds=verify shows byte-for-byte the same 38 pre-existing fp32-decide mismatches as the
// control, i.e. zero added divergence.
inline real grad_dot(int gradIndex, real x, real y, real z) {
    const int h = gradIndex & 15;
    const bool zZero = (h < 4) || (h == 12) || (h == 14);   // the 0 entry is on z
    const bool yZero = (h >= 4) && (h < 8);                 //   ... on y  (else on x)
    const real u = (h < 8) ? x : y;                         // scaled by the first  +/-1
    const real v = (h < 4) ? y : (zZero ? x : z);           // scaled by the second +/-1
    const real w = zZero ? z : (yZero ? y : x);             // scaled by 0
    return (((h & 1) ? -u : u) + ((h & 2) ? -v : v)) + copysign((real) REAL_C(0.0), w);
}

// =====================================================================
// PERMUTATION TABLE ENCODING (host-selected — see dev.superchunk.gpu.dfc.PermFormat;
// the host uploads the matching bytes and passes the matching -D).
//
//   (default)        __global const int*    1024 B/octave   14 gathers per sample
//   -DSC_PERM_U8     __global const uchar*    256 B/octave   14 gathers per sample
//   -DSC_PERM_PAIR   __global const uchar2*   512 B/octave    7 gathers per sample
//
// Same values in all three (vanilla's `p[i] & 0xFF`); only the encoding differs, so
// every variant is bit-identical by construction.
//
// WHY: perm is the hot gather. A warp's 32 lanes draw effectively random indices out
// of one octave's table, so the table's BYTE SIZE sets how many 32-byte sectors the
// load touches — 32 sectors as int, 8 as uchar. The PAIR form additionally exploits
// the fact that all 14 of sampleAndLerp's lookups are 7 (i, i+1) pairs (see
// perm_pair), turning 14 gather instructions into 7 at 512 B/octave.
// =====================================================================
#if defined(SC_PERM_PAIR)
typedef __global const uchar2* perm_ptr;
#elif defined(SC_PERM_U8)
typedef __global const uchar* perm_ptr;
#else
typedef __global const int* perm_ptr;
#endif

// ImprovedNoise.p(i) = p[i & 0xFF] & 0xFF, for i and i+1 at once.
//
// Vanilla's index wraps with & 0xFF, and ((i & 0xFF) + 1) & 0xFF == (i + 1) & 0xFF, so
// entry (i & 0xFF) of the PAIR table — built host-side as {p[i], p[(i+1) & 0xFF]} —
// is exactly this pair. Returned as int2 {p(i), p(i+1)} in every encoding.
inline int2 perm_pair(perm_ptr perm, int i) {
#if defined(SC_PERM_PAIR)
    uchar2 v = perm[i & 0xFF];
    return (int2)((int) v.x, (int) v.y);
#elif defined(SC_PERM_U8)
    return (int2)((int) perm[i & 0xFF], (int) perm[(i + 1) & 0xFF]);
#else
    return (int2)(perm[i & 0xFF] & 0xFF, perm[(i + 1) & 0xFF] & 0xFF);
#endif
}

// ImprovedNoise.sampleAndLerp(...) — exact vanilla op order.
//   x,y,z   = local coordinates within the cell (localX/Y/Z)
//   fadeX   = the *fade* argument vanilla passes for X (p_164324_), which is
//             the ORIGINAL d4 (localY before yScale subtraction). In the
//             vanilla call sampleAndLerp(i,j,k, d3, d4-d6, d5, d4):
//               localX = d3, localY = d4-d6, localZ = d5, fadeArg = d4.
//   Note vanilla smoothsteps: d8=smoothstep(localX), d9=smoothstep(fadeArg),
//   d10=smoothstep(localZ).
SC_INLINE real sample_and_lerp(perm_ptr perm,
                            int sx, int sy, int sz,
                            real lx, real ly, real lz, real fadeArg) {
    // Vanilla reads 14 permutation entries; they are exactly 7 (n, n+1) pairs, so one
    // perm_pair per pair covers all of them (identical values, see perm_pair).
    int2 ij   = perm_pair(perm, sx);        int i  = ij.x,  j  = ij.y;
    int2 kl   = perm_pair(perm, i + sy);    int k  = kl.x,  l  = kl.y;
    int2 i1j1 = perm_pair(perm, j + sy);    int i1 = i1j1.x, j1 = i1j1.y;

    int2 gk  = perm_pair(perm, k  + sz);    // {p(k +sz), p(k +sz+1)}
    int2 gi1 = perm_pair(perm, i1 + sz);
    int2 gl  = perm_pair(perm, l  + sz);
    int2 gj1 = perm_pair(perm, j1 + sz);

    real d0 = grad_dot(gk.x,  lx,             ly,             lz);
    real d1 = grad_dot(gi1.x, lx - REAL_C(1.0), ly,           lz);
    real d2 = grad_dot(gl.x,  lx,             ly - REAL_C(1.0), lz);
    real d3 = grad_dot(gj1.x, lx - REAL_C(1.0), ly - REAL_C(1.0), lz);
    real d4 = grad_dot(gk.y,  lx,             ly,             lz - REAL_C(1.0));
    real d5 = grad_dot(gi1.y, lx - REAL_C(1.0), ly,           lz - REAL_C(1.0));
    real d6 = grad_dot(gl.y,  lx,             ly - REAL_C(1.0), lz - REAL_C(1.0));
    real d7 = grad_dot(gj1.y, lx - REAL_C(1.0), ly - REAL_C(1.0), lz - REAL_C(1.0));

    real fx = mc_fade(lx);
    real fy = mc_fade(fadeArg);
    real fz = mc_fade(lz);
    return mc_lerp3(fx, fy, fz, d0, d1, d2, d3, d4, d5, d6, d7);
}

// ImprovedNoise.noise(x,y,z,yScale,yMax) — single Perlin octave.
// xo/yo/zo are this octave's offsets.
SC_INLINE real improved_noise(perm_ptr perm,
                           real xo, real yo, real zo,
                           real x, real y, real z,
                           real yScale, real yMax) {
    real d0 = x + xo;
    real d1 = y + yo;
    real d2 = z + zo;
    real d3, d4, d5;
    int i = mc_floor_frac(d0, &d3);   // == mc_floor(d0), d3 == d0 - (real) i
    int j = mc_floor_frac(d1, &d4);
    int k = mc_floor_frac(d2, &d5);

    real d6;
    if (yScale != REAL_C(0.0)) {
        real d7;
        if (yMax >= REAL_C(0.0) && yMax < d4) {
            d7 = yMax;
        } else {
            d7 = d4;
        }
        // vanilla: (double) Mth.floor(d7 / yScale + 1.0E-7F) * yScale
        // NOTE the epsilon is a *float* literal (1.0E-7F) in vanilla, promoted to
        // double inside the double expression. For exact fp64 ULP parity we must
        // use that promoted value, NOT the double 1.0E-7 (they differ by ~1.2e-15,
        // which can flip a floor() tie). At fp32 this rounds back to 1.0E-7F.
        d6 = (real) mc_floor(d7 / yScale + REAL_C(1.0000000116860974E-7)) * yScale;
    } else {
        d6 = REAL_C(0.0);
    }
    return sample_and_lerp(perm, i, j, k, d3, d4 - d6, d5, d4);
}

// ImprovedNoise.noise(x,y,z, 0.0, 0.0) — the ONLY shape PerlinNoise (and therefore all
// NormalNoise worldgen) ever calls. Specialising it removes, per octave:
//   * two fp64 arguments across a non-inlined call boundary (-DSC_NOINLINE is the
//     production build), and
//   * the `yScale != 0` test, and
//   * the `d4 - d6` subtract: d6 is exactly +0.0 on this path and `d4 - 0.0 == d4`
//     bit-for-bit for every finite double, -0.0 included.
// BlendedNoise is the one caller that really does pass a non-zero yScale; it keeps using
// the general improved_noise above, unchanged.
SC_INLINE real improved_noise_octave(perm_ptr perm,
                           real xo, real yo, real zo,
                           real x, real y, real z) {
    real d0 = x + xo;
    real d1 = y + yo;
    real d2 = z + zo;
    real d3, d4, d5;
    int i = mc_floor_frac(d0, &d3);
    int j = mc_floor_frac(d1, &d4);
    int k = mc_floor_frac(d2, &d5);
    return sample_and_lerp(perm, i, j, k, d3, d4, d5, d4);
}

// PerlinNoise.wrap(value) = value - lfloor(value/3.3554432E7 + 0.5)*3.3554432E7
//
// FAST PATH, bit-exact. 3.3554432E7 is exactly 2^25, so `value / 3.3554432E7` is an exact
// power-of-two scaling. For |value| < 2^23 the exact quotient therefore lies in
// (-0.25, 0.25) and the sum with 0.5 lies in (0.25, 0.75) — no rounding can push it out of
// [0, 1) — so lfloor(...) is 0 and vanilla's expression collapses to
//   value - (double) 0 * 2^25  ==  value - 0.0  ==  value
// which is bit-for-bit `value` for EVERY finite double, -0.0 included (-0.0 - 0.0 == -0.0).
// (2^24 would NOT be safe: at the top of that range the sum is 1 - 2^-54, which ties-to-even
// rounds UP to 1.0 and makes lfloor 1.)
//
// This is the branch every lane takes in practice — worldgen feeds coordinate*inputFactor,
// orders of magnitude below 2^23 — and it removes a divide, an add, two conversions, a
// multiply and a subtract from EVERY octave of EVERY noise. On a card whose fp64 rate is
// 1/64 of fp32 that is real time. Coordinates that do exceed the bound simply take the
// verbatim vanilla expression below, so the identity holds unconditionally.
inline real perlin_wrap(real value) {
    if (fabs(value) < REAL_C(8388608.0)) {
        return value;
    }
    return value - (real) mc_lfloor(value / REAL_C(3.3554432E7) + REAL_C(0.5)) * REAL_C(3.3554432E7);
}

// PerlinNoise.getValue(x,y,z) — sum of octaves.
//   nOctaves                = number of octave slots (== amplitudes.size())
//   permBase                = base into the flat permutation array (perm for
//                             octave o starts at (permBase + o*256))
//   octaveActive[o]         = 1 if noiseLevels[o] != null, else 0 (skipped)
//   xo/yo/zo[o]             = octave offsets
//   amplitudes[o]           = per-octave amplitude
//   lowestFreqInputFactor   = starting input factor (d1)
//   lowestFreqValueFactor   = starting value factor (d2)
SC_INLINE real perlin_get_value(perm_ptr perm, int permBase, int nOctaves,
                             __global const int*  octaveActive,
                             __global const real* xo,
                             __global const real* yo,
                             __global const real* zo,
                             __global const real* amplitudes,
                             real lowestFreqInputFactor,
                             real lowestFreqValueFactor,
                             real x, real y, real z) {
    real sum = REAL_C(0.0);
    real d1 = lowestFreqInputFactor;
    real d2 = lowestFreqValueFactor;

    // WRAP HOISTING. perlin_wrap is the identity whenever |v| < 2^23 (see its proof). The
    // per-octave input factor only ever DOUBLES, so every |x*d1|, |y*d1|, |z*d1| this loop
    // can produce is bounded by (|x|+|y|+|z|) * |lowestFreqInputFactor| * 2^(nOctaves-1).
    // Establish that bound once and the whole loop drops the three per-octave fabs+compares
    // as well as the wrap arithmetic they guard.
    //
    // Every term is taken in ABSOLUTE value so a negative input factor cannot make the
    // product negative and pass the test vacuously. The sum is used rather than fmax for two
    // reasons: it is still an upper bound on each individual |coordinate| (all terms are
    // non-negative), and it PROPAGATES NaN, whereas OpenCL's fmax returns the non-NaN operand
    // — a NaN coordinate must fail the test and take the verbatim wrapped loop, not skip it.
    //
    // The 2^22 threshold (not 2^23) covers rounding on both multiplies (`d1max` and the
    // per-octave `x * d1` each round at most one ulp); one halving of the bound is many
    // orders of magnitude more slack than that needs. Anything above it falls through to the
    // verbatim wrapped loop, so this is a fast path, not an assumption.
    real d1max = fabs(lowestFreqInputFactor);
    for (int o = 1; o < nOctaves; ++o) {
        d1max *= REAL_C(2.0);       // exact: power-of-two scaling
    }
    real abound = fabs(x) + fabs(y) + fabs(z);
    if (abound * d1max < REAL_C(4194304.0)) {
        for (int o = 0; o < nOctaves; ++o) {
            if (octaveActive[o] != 0) {
                perm_ptr p = perm + (permBase + o * 256);
                real g = improved_noise_octave(p, xo[o], yo[o], zo[o], x * d1, y * d1, z * d1);
                sum += amplitudes[o] * g * d2;
            }
            d1 *= REAL_C(2.0);
            d2 /= REAL_C(2.0);
        }
        return sum;
    }

    for (int o = 0; o < nOctaves; ++o) {
        if (octaveActive[o] != 0) {
            perm_ptr p = perm + (permBase + o * 256);
            // vanilla non-broken path: yArg = wrap(y*d1), yScale=0, yMax=0.
            real g = improved_noise_octave(p, xo[o], yo[o], zo[o],
                                    perlin_wrap(x * d1),
                                    perlin_wrap(y * d1),
                                    perlin_wrap(z * d1));
            sum += amplitudes[o] * g * d2;
        }
        d1 *= REAL_C(2.0);
        d2 /= REAL_C(2.0);
    }
    return sum;
}

// NormalNoise.getValue(x,y,z):
//   d = (x,y,z)*INPUT_FACTOR (1.0181268882175227)
//   return (first.getValue(x,y,z) + second.getValue(d)) * valueFactor
// State for the two PerlinNoise samplers is concatenated in the flat buffers:
//   first  octaves occupy slot range [0, nOctaves)
//   second octaves occupy slot range [nOctaves, 2*nOctaves)
// (both PerlinNoise have identical octave count from the same NoiseParameters).
#define NORMAL_INPUT_FACTOR REAL_C(1.0181268882175227)

SC_INLINE real normal_noise(perm_ptr perm, int nOctaves,
                         __global const int*  octaveActive,   // 2*nOctaves
                         __global const real* xo,             // 2*nOctaves
                         __global const real* yo,
                         __global const real* zo,
                         __global const real* amplitudes,
                         real lifInput, real livValue,         // shared by both perlin
                         real valueFactor,
                         real x, real y, real z) {
    real first = perlin_get_value(perm, 0, nOctaves,
                                  octaveActive, xo, yo, zo, amplitudes,
                                  lifInput, livValue, x, y, z);
    real dx = x * NORMAL_INPUT_FACTOR;
    real dy = y * NORMAL_INPUT_FACTOR;
    real dz = z * NORMAL_INPUT_FACTOR;
    // second sampler: its octave state lives at slot offset nOctaves; its
    // permutation lives at permBase = nOctaves*256.
    real second = perlin_get_value(perm, nOctaves * 256, nOctaves,
                                   octaveActive + nOctaves,
                                   xo + nOctaves, yo + nOctaves, zo + nOctaves,
                                   amplitudes + nOctaves,
                                   lifInput, livValue, dx, dy, dz);
    return (first + second) * valueFactor;
}

// =====================================================================
// Batch kernels: one work-item per sample point. Sample coordinates come in
// as three parallel real arrays (sx, sy, sz). Output goes to `out`.
// =====================================================================

// ImprovedNoise batch (single octave). offsets passed as scalars.
__kernel void improved_noise_batch(
        perm_ptr perm,
        const real xo, const real yo, const real zo,
        const real yScale, const real yMax,
        __global const real* sx,
        __global const real* sy,
        __global const real* sz,
        __global real* out,
        const int n) {
    int gid = get_global_id(0);
    if (gid >= n) return;
    out[gid] = improved_noise(perm, xo, yo, zo, sx[gid], sy[gid], sz[gid], yScale, yMax);
}

// PerlinNoise batch (octave sum).
__kernel void perlin_noise_batch(
        perm_ptr perm,
        const int nOctaves,
        __global const int*  octaveActive,
        __global const real* xo,
        __global const real* yo,
        __global const real* zo,
        __global const real* amplitudes,
        const real lowestFreqInputFactor,
        const real lowestFreqValueFactor,
        __global const real* sx,
        __global const real* sy,
        __global const real* sz,
        __global real* out,
        const int n) {
    int gid = get_global_id(0);
    if (gid >= n) return;
    out[gid] = perlin_get_value(perm, 0, nOctaves, octaveActive, xo, yo, zo,
                                amplitudes, lowestFreqInputFactor, lowestFreqValueFactor,
                                sx[gid], sy[gid], sz[gid]);
}

// NormalNoise batch (two perlin samplers combined).
__kernel void normal_noise_batch(
        perm_ptr perm,
        const int nOctaves,
        __global const int*  octaveActive,
        __global const real* xo,
        __global const real* yo,
        __global const real* zo,
        __global const real* amplitudes,
        const real lowestFreqInputFactor,
        const real lowestFreqValueFactor,
        const real valueFactor,
        __global const real* sx,
        __global const real* sy,
        __global const real* sz,
        __global real* out,
        const int n) {
    int gid = get_global_id(0);
    if (gid >= n) return;
    out[gid] = normal_noise(perm, nOctaves, octaveActive, xo, yo, zo, amplitudes,
                            lowestFreqInputFactor, lowestFreqValueFactor, valueFactor,
                            sx[gid], sy[gid], sz[gid]);
}
