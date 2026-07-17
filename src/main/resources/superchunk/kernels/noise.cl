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

// SimplexNoise.GRADIENT — the exact 16x3 integer gradient table, flattened
// row-major (16 rows * 3). Indexed by (hash & 15).
__constant int GRADIENT[16][3] = {
    { 1,  1,  0}, {-1,  1,  0}, { 1, -1,  0}, {-1, -1,  0},
    { 1,  0,  1}, {-1,  0,  1}, { 1,  0, -1}, {-1,  0, -1},
    { 0,  1,  1}, { 0, -1,  1}, { 0,  1, -1}, { 0, -1, -1},
    { 1,  1,  0}, { 0, -1,  1}, {-1,  1,  0}, { 0, -1, -1}
};

// SimplexNoise.dot(GRADIENT[idx&15], x, y, z)
inline real grad_dot(int gradIndex, real x, real y, real z) {
    __constant int* g = GRADIENT[gradIndex & 15];
    return (real) g[0] * x + (real) g[1] * y + (real) g[2] * z;
}

// ImprovedNoise.p(i) = p[i & 0xFF] & 0xFF.
// `perm` holds the 256 permutation entries already normalized to 0..255 ints
// on the host (vanilla stores them as bytes; & 0xFF on read).
inline int perm_at(__global const int* perm, int i) {
    return perm[i & 0xFF] & 0xFF;
}

// ImprovedNoise.sampleAndLerp(...) — exact vanilla op order.
//   x,y,z   = local coordinates within the cell (localX/Y/Z)
//   fadeX   = the *fade* argument vanilla passes for X (p_164324_), which is
//             the ORIGINAL d4 (localY before yScale subtraction). In the
//             vanilla call sampleAndLerp(i,j,k, d3, d4-d6, d5, d4):
//               localX = d3, localY = d4-d6, localZ = d5, fadeArg = d4.
//   Note vanilla smoothsteps: d8=smoothstep(localX), d9=smoothstep(fadeArg),
//   d10=smoothstep(localZ).
SC_INLINE real sample_and_lerp(__global const int* perm,
                            int sx, int sy, int sz,
                            real lx, real ly, real lz, real fadeArg) {
    int i  = perm_at(perm, sx);
    int j  = perm_at(perm, sx + 1);
    int k  = perm_at(perm, i + sy);
    int l  = perm_at(perm, i + sy + 1);
    int i1 = perm_at(perm, j + sy);
    int j1 = perm_at(perm, j + sy + 1);

    real d0 = grad_dot(perm_at(perm, k  + sz),     lx,             ly,             lz);
    real d1 = grad_dot(perm_at(perm, i1 + sz),     lx - REAL_C(1.0), ly,           lz);
    real d2 = grad_dot(perm_at(perm, l  + sz),     lx,             ly - REAL_C(1.0), lz);
    real d3 = grad_dot(perm_at(perm, j1 + sz),     lx - REAL_C(1.0), ly - REAL_C(1.0), lz);
    real d4 = grad_dot(perm_at(perm, k  + sz + 1), lx,             ly,             lz - REAL_C(1.0));
    real d5 = grad_dot(perm_at(perm, i1 + sz + 1), lx - REAL_C(1.0), ly,           lz - REAL_C(1.0));
    real d6 = grad_dot(perm_at(perm, l  + sz + 1), lx,             ly - REAL_C(1.0), lz - REAL_C(1.0));
    real d7 = grad_dot(perm_at(perm, j1 + sz + 1), lx - REAL_C(1.0), ly - REAL_C(1.0), lz - REAL_C(1.0));

    real fx = mc_fade(lx);
    real fy = mc_fade(fadeArg);
    real fz = mc_fade(lz);
    return mc_lerp3(fx, fy, fz, d0, d1, d2, d3, d4, d5, d6, d7);
}

// ImprovedNoise.noise(x,y,z,yScale,yMax) — single Perlin octave.
// xo/yo/zo are this octave's offsets.
SC_INLINE real improved_noise(__global const int* perm,
                           real xo, real yo, real zo,
                           real x, real y, real z,
                           real yScale, real yMax) {
    real d0 = x + xo;
    real d1 = y + yo;
    real d2 = z + zo;
    int i = mc_floor(d0);
    int j = mc_floor(d1);
    int k = mc_floor(d2);
    real d3 = d0 - (real) i;
    real d4 = d1 - (real) j;
    real d5 = d2 - (real) k;

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

// PerlinNoise.wrap(value) = value - lfloor(value/3.3554432E7 + 0.5)*3.3554432E7
inline real perlin_wrap(real value) {
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
SC_INLINE real perlin_get_value(__global const int* perm, int permBase, int nOctaves,
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
    for (int o = 0; o < nOctaves; ++o) {
        if (octaveActive[o] != 0) {
            __global const int* p = perm + (permBase + o * 256);
            // vanilla non-broken path: yArg = wrap(y*d1), yScale=0, yMax=0.
            real g = improved_noise(p, xo[o], yo[o], zo[o],
                                    perlin_wrap(x * d1),
                                    perlin_wrap(y * d1),
                                    perlin_wrap(z * d1),
                                    REAL_C(0.0), REAL_C(0.0));
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

SC_INLINE real normal_noise(__global const int* perm, int nOctaves,
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
        __global const int*  perm,
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
        __global const int*  perm,
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
        __global const int*  perm,
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
