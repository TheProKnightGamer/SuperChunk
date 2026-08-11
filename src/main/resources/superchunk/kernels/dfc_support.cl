// =====================================================================
// SuperChunk GPU backend — Stage 2: density-function support layer.
//
// This file is PREPENDED with noise.cl by the host (OpenCLAstEmitter):
//   final source = noise.cl + dfc_support.cl + <generated eval_df + kernel>
//
// It adds:
//   * a NormalNoise lookup BY ID over combined (flattened) noise-state
//     buffers, so a single kernel can reference many distinct noises.
//   * vanilla math shims used by density-function nodes (clampedMap, the
//     float spline helpers) that mirror net.minecraft.util.Mth op-for-op.
//
// PRECISION: inherits the `real` / REAL_C() discipline from noise.cl. On a
// device without cl_khr_fp64 the whole program builds with -DUSE_FP32 and
// `real == float` (structural parity ~1e-3..1e-4); on an fp64 device it is
// `double` and matches vanilla to (near) ULP.
// =====================================================================

// ---------------------------------------------------------------------
// Combined noise-state layout.
//
// The host assigns each distinct DensityFunction.NoiseHolder an integer id
// in [0, noiseCount). For each id it uploads the state of the underlying
// NormalNoise (its two sub-PerlinNoise samplers, concatenated exactly as in
// normal_noise() of noise.cl). All ids share these flat buffers:
//
//   noiseDesc[id*8 + 0] = nOctaves           (octave count of ONE perlin)
//   noiseDesc[id*8 + 1] = permBase           (int index into nPerm[])
//   noiseDesc[id*8 + 2] = octaveBase         (int index into nActive/xo/yo/zo/amp[])
//   noiseDesc[id*8 + 3] = valid              (1 = has a non-null NormalNoise, else 0)
//   noiseDesc[id*8 + 4..7] = reserved (0)
//
//   noiseFactors[id*3 + 0] = lowestFreqInputFactor (shared by both sub-perlins)
//   noiseFactors[id*3 + 1] = lowestFreqValueFactor
//   noiseFactors[id*3 + 2] = valueFactor            (NormalNoise combine factor)
//
// Per-id the two sub-perlins occupy 2*nOctaves octave slots and
// 2*nOctaves*256 permutation entries, laid out first-then-second, IDENTICAL
// to the normal_noise() concatenation in noise.cl.
// ---------------------------------------------------------------------

// DensityFunction.NoiseHolder.getValue(x,y,z): 0.0 when the NormalNoise is
// absent (noise == null), else NormalNoise.getValue (== normal_noise()).
SC_INLINE real df_noise_value(
        int id,
        __global const int*  noiseDesc,
        __global const real* noiseFactors,
        perm_ptr nPerm,
        __global const int*  nActive,
        __global const real* nXo,
        __global const real* nYo,
        __global const real* nZo,
        __global const real* nAmp,
        real x, real y, real z) {
    int base = id * 8;
    int valid = noiseDesc[base + 3];
    if (valid == 0) {
        return REAL_C(0.0);
    }
    int nOctaves  = noiseDesc[base + 0];
    int permBase  = noiseDesc[base + 1];
    int octaveBase = noiseDesc[base + 2];

    int fb = id * 3;
    real lifInput = noiseFactors[fb + 0];
    real livValue = noiseFactors[fb + 1];
    real valueFactor = noiseFactors[fb + 2];

    // first sub-perlin: octave slots [octaveBase, octaveBase+nOctaves),
    // permutation [permBase, permBase + nOctaves*256).
    real first = perlin_get_value(nPerm, permBase, nOctaves,
                                  nActive + octaveBase,
                                  nXo + octaveBase, nYo + octaveBase, nZo + octaveBase,
                                  nAmp + octaveBase,
                                  lifInput, livValue, x, y, z);
    real dx = x * NORMAL_INPUT_FACTOR;
    real dy = y * NORMAL_INPUT_FACTOR;
    real dz = z * NORMAL_INPUT_FACTOR;
    int permBase2 = permBase + nOctaves * 256;
    int octaveBase2 = octaveBase + nOctaves;
    real second = perlin_get_value(nPerm, permBase2, nOctaves,
                                   nActive + octaveBase2,
                                   nXo + octaveBase2, nYo + octaveBase2, nZo + octaveBase2,
                                   nAmp + octaveBase2,
                                   lifInput, livValue, dx, dy, dz);
    return (first + second) * valueFactor;
}

// --- WeirdScaledSampler rarity mappers (NoiseRouterData.QuantizedSpaghettiRarity) ---
// TYPE1 = getSpaghettiRarity3D, TYPE2 = getSphaghettiRarity2D. Stepwise; mirror exactly.
inline real weird_rarity_type1(real value) {
    if (value < REAL_C(-0.5)) return REAL_C(0.75);
    if (value < REAL_C(0.0))  return REAL_C(1.0);
    return value < REAL_C(0.5) ? REAL_C(1.5) : REAL_C(2.0);
}
inline real weird_rarity_type2(real value) {
    if (value < REAL_C(-0.75)) return REAL_C(0.5);
    if (value < REAL_C(-0.5))  return REAL_C(0.75);
    if (value < REAL_C(0.5))   return REAL_C(1.0);
    return value < REAL_C(0.75) ? REAL_C(2.0) : REAL_C(3.0);
}

// --- Mth.clamp(double,min,max) = v<min?min:Math.min(v,max) -----------
inline real mth_clamp(real v, real lo, real hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

// --- Mth.inverseLerp(delta,start,end) = (delta-start)/(end-start) -----
inline real mth_inverse_lerp(real delta, real start, real end) {
    return (delta - start) / (end - start);
}

// --- Mth.clampedLerp(start,end,delta) --------------------------------
inline real mth_clamped_lerp(real start, real end, real delta) {
    if (delta < REAL_C(0.0)) return start;
    if (delta > REAL_C(1.0)) return end;
    return mc_lerp(delta, start, end); // == start + delta*(end-start)
}

// --- Mth.clampedMap(input,inMin,inMax,outMin,outMax) -----------------
inline real mth_clamped_map(real input, real inMin, real inMax, real outMin, real outMax) {
    return mth_clamped_lerp(outMin, outMax, mth_inverse_lerp(input, inMin, inMax));
}

// ---------------------------------------------------------------------
// Spline helpers — the spline math runs in FLOAT in vanilla (locations,
// derivatives, Mth.lerp(float,float,float)). We mirror that exactly.
// `sreal` is the spline numeric type. Under USE_FP32 it is identical to
// `real`; under fp64 we still keep spline arithmetic in float to match
// vanilla's float spline path bit-for-bit.
// ---------------------------------------------------------------------
typedef float sreal;

inline sreal spline_lerp(sreal delta, sreal start, sreal end) {
    return start + delta * (end - start);
}

// Correctly-rounded float divide for the spline interval parameter
// k = (point - loc0) / locDist.
//
// WHY: OpenCL C only requires <= 2.5 ulp for fp32 '/' and NVIDIA's compiler
// emits a reciprocal-based approximate divide unless the program is built
// with -cl-fp32-correctly-rounded-divide-sqrt. Java's float '/' IS correctly
// rounded, so the GPU spline drifted by exactly 1 float ULP on the sample
// planes where the approximate quotient rounds differently (measured:
// 3/48 Y-planes = 192/3072 points, maxAbsErr 2.980e-08 = 1 ulp @ 0.29).
// Doing the divide in fp64 and rounding once to float is PROVABLY the
// correctly-rounded float quotient: double rounding is innocuous when the
// intermediate precision p2 >= 2*p1 + 2 (53 >= 2*24 + 2 = 50).
// Under USE_FP32 (no fp64 on device) keep the native divide — that build is
// structural-parity only by contract.
inline float spline_div(float num, float den) {
#ifdef USE_FP32
    return num / den;
#else
    return (float)(((double) num) / ((double) den));
#endif
}

// SplineSupport.findRangeForLocation(locations,x)
// `locations`/`derivatives` are emitted as __constant arrays (fa<i>).
inline int spline_find_range(__constant const float* locations, int locCount, float x) {
    int minIdx = 0;
    int i = locCount;
    while (i > 0) {
        int j = i >> 1;
        int k = minIdx + j;
        if (x < locations[k]) {
            i = j;
        } else {
            minIdx = k + 1;
            i -= j + 1;
        }
    }
    return minIdx - 1;
}

// SplineSupport.sampleOutsideRange(point,locations,value,derivatives,i)
inline float spline_sample_outside(float point, __constant const float* locations,
                                   float value, __constant const float* derivatives, int i) {
    float f = derivatives[i];
    return f == 0.0f ? value : value + f * (point - locations[i]);
}

// ---------------------------------------------------------------------
// BlendedNoise ("old_blended_noise") — bit-exact port of vanilla 1.21.1
// net.minecraft.world.level.levelgen.synth.BlendedNoise.compute().
//
// State layout: the host (NoiseRegistry.registerBlended) appends the three
// legacy PerlinNoise samplers' octave state to the SAME flat noise buffers
// used by df_noise_value, contiguously and in this order:
//   minLimitNoise  -> octave slots [octBase,            octBase +   nLimit)
//   maxLimitNoise  -> octave slots [octBase +   nLimit, octBase + 2*nLimit)
//   mainNoise      -> octave slots [octBase + 2*nLimit, octBase + 2*nLimit + nMain)
// with permutation tables at nPerm[permBase + slotWithinBlended*256 ..].
// Vanilla always has nLimit=16 (octaves -15..0) and nMain=8 (octaves -7..0);
// the host REFUSES other shapes (compute() hardcodes those loop bounds).
//
// Faithfulness notes (all local names dN mirror the vanilla decompile):
//  * getOctaveNoise(i) == noiseLevels[len - 1 - i] — REVERSED indexing; the
//    loop's frequency divisor d11 starts at 1.0 and HALVES per iteration.
//  * compute() ignores PerlinNoise.amplitudes (all 1.0 for the legacy
//    factory) and sums noise/d11 directly — no amplitude multiply here.
//  * Every improved_noise call uses the yScale/yMax "smear" arguments (the
//    legacy sampleAndLerp-with-y-clamp variant already implemented — with the
//    float-promoted 1.0E-7F epsilon — in noise.cl's improved_noise; dormant
//    until now because perlin_get_value always passes yScale=0).
//  * Main loop: coords are the FACTOR-DIVIDED d3/d4/d5, yScale=d7*d11,
//    yMax=d4*d11 (unwrapped). Limit loops: coords d0/d1/d2 wrapped, yScale=
//    d15=d6*d11, yMax=d1*d11 (unwrapped). d6=yMult*smear, d7=d6/yFactor.
//  * flag1/flag2 skip the min/max limit sums exactly like vanilla — bit-safe
//    because Mth.clampedLerp then never lerps with the skipped side except at
//    delta==1.0/0.0, where lerp(1,0,b)==b and start is returned exactly.
//  * The runtime divides (/xzFactor, /yFactor, /d11) stay in-kernel: OpenCL
//    fp64 '/' is IEEE correctly rounded, matching Java double division.
//
// Takes the full NOISE_PARAMS pointer set so the emitter can forward its
// treeArgs verbatim (noiseDesc/noiseFactors/nAmp are deliberately unused).
// FP_CONTRACT OFF from noise.cl's file-scope pragma covers this function.
// ---------------------------------------------------------------------
SC_INLINE real df_blended_noise(
        __global const int*  noiseDesc,     // unused (uniform threading)
        __global const real* noiseFactors,  // unused (uniform threading)
        perm_ptr nPerm,
        __global const int*  nActive,
        __global const real* nXo,
        __global const real* nYo,
        __global const real* nZo,
        __global const real* nAmp,          // unused: compute() ignores amplitudes
        int permBase, int octBase, int nLimit, int nMain,
        real xzMult, real yMult, real xzFactor, real yFactor, real smear,
        real x, real y, real z) {
    real d0 = x * xzMult;
    real d1 = y * yMult;
    real d2 = z * xzMult;
    real d3 = d0 / xzFactor;
    real d4 = d1 / yFactor;
    real d5 = d2 / xzFactor;
    real d6 = yMult * smear;
    real d7 = d6 / yFactor;
    real d8 = REAL_C(0.0);
    real d9 = REAL_C(0.0);
    real d10 = REAL_C(0.0);
    real d11 = REAL_C(1.0);
    for (int i = 0; i < nMain; ++i) {
        // mainNoise.getOctaveNoise(i) = mainLevels[nMain - 1 - i]
        int slot = 2 * nLimit + (nMain - 1 - i);
        int o = octBase + slot;
        if (nActive[o] != 0) {
            perm_ptr p = nPerm + (permBase + slot * 256);
            d10 += improved_noise(p, nXo[o], nYo[o], nZo[o],
                                  perlin_wrap(d3 * d11),
                                  perlin_wrap(d4 * d11),
                                  perlin_wrap(d5 * d11),
                                  d7 * d11, d4 * d11) / d11;
        }
        d11 /= REAL_C(2.0);
    }
    real d16 = (d10 / REAL_C(10.0) + REAL_C(1.0)) / REAL_C(2.0);
    int flag1 = d16 >= REAL_C(1.0);
    int flag2 = d16 <= REAL_C(0.0);
    d11 = REAL_C(1.0);
    for (int j = 0; j < nLimit; ++j) {
        real d12 = perlin_wrap(d0 * d11);
        real d13 = perlin_wrap(d1 * d11);
        real d14 = perlin_wrap(d2 * d11);
        real d15 = d6 * d11;
        // min/maxLimitNoise.getOctaveNoise(j) = levels[nLimit - 1 - j]
        int slot = nLimit - 1 - j;
        if (!flag1) {
            int o = octBase + slot;
            if (nActive[o] != 0) {
                perm_ptr p = nPerm + (permBase + slot * 256);
                d8 += improved_noise(p, nXo[o], nYo[o], nZo[o],
                                     d12, d13, d14, d15, d1 * d11) / d11;
            }
        }
        if (!flag2) {
            int o = octBase + nLimit + slot;
            if (nActive[o] != 0) {
                perm_ptr p = nPerm + (permBase + (nLimit + slot) * 256);
                d9 += improved_noise(p, nXo[o], nYo[o], nZo[o],
                                     d12, d13, d14, d15, d1 * d11) / d11;
            }
        }
        d11 /= REAL_C(2.0);
    }
    // Mth.clampedLerp(d8/512, d9/512, d16) / 128.0
    return mth_clamped_lerp(d8 / REAL_C(512.0), d9 / REAL_C(512.0), d16) / REAL_C(128.0);
}
