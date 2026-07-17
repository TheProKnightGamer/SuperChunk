// =====================================================================
// SuperChunk GPU backend — Minecraft 1.21.1 positional RNG in OpenCL C.
//
// EXACT bit-for-bit port of (vanilla, decompiled):
//   net.minecraft.world.level.levelgen.Xoroshiro128PlusPlus  (xoroshiro128++)
//   net.minecraft.world.level.levelgen.XoroshiroRandomSource (nextLong/nextInt)
//   net.minecraft.world.level.levelgen.XoroshiroRandomSource.XoroshiroPositionalRandomFactory.at(x,y,z)
//   net.minecraft.world.level.levelgen.RandomSupport (upgradeSeedTo128bit / mixStafford13)
//   net.minecraft.util.Mth.getSeed(x,y,z)
//   net.minecraft.core.BlockPos.asLong/getX/getY/getZ (bit-packing)
//
// Pure 64-bit signed integer math (cl_long); needs NO cl_khr_fp64, so this is
// the same on every device. Validated against the Java XoroshiroRandomSource by
// dev.superchunk.gpu.aquifer.RngSelfTest (boot-time, logged PASS/FAIL).
//
// This file is self-contained and is also host-prepended ahead of noise.cl +
// dfc_support.cl + the aquifer kernel so the aquifer port can reuse the RNG.
// =====================================================================

#ifndef SC_RNG_CL
#define SC_RNG_CL

// Vanilla constants.
#define SC_GOLDEN_RATIO_64 (-7046029254386353131L)
#define SC_SILVER_RATIO_64 ( 7640891576956012809L)

// --- Long.rotateLeft(x, k), 0 < k < 64 (the only range vanilla uses) ----
inline long mc_rotl(long x, int k) {
    ulong u = (ulong) x;
    return (long)((u << k) | (u >> (64 - k)));
}

// --- Mth.getSeed(int x,int y,int z) -------------------------------------
// long i = (long)(x*3129871) ^ (long)z*116129781L ^ (long)y;
// i = i*i*42317861L + i*11L; return i >> 16;   (x*3129871 is 32-bit int mul)
inline long mc_getSeed(int x, int y, int z) {
    long i = (long)(x * 3129871) ^ (((long) z) * 116129781L) ^ ((long) y);
    i = i * i * 42317861L + i * 11L;
    return i >> 16;   // arithmetic (signed) right shift, matches Java
}

// --- RandomSupport.mixStafford13(seed) ----------------------------------
inline long mc_mixStafford13(long s) {
    s = (s ^ (long)(((ulong) s) >> 30)) * (-4658895280553007687L);
    s = (s ^ (long)(((ulong) s) >> 27)) * (-7723592293110705685L);
    return s ^ (long)(((ulong) s) >> 31);
}

// xoroshiro128++ 128-bit state.
typedef struct { long lo; long hi; } xoro_t;

// Xoroshiro128PlusPlus(seedLo, seedHi) — with the all-zero guard.
inline xoro_t xoro_init(long lo, long hi) {
    xoro_t s;
    if ((lo | hi) == 0L) {
        s.lo = SC_GOLDEN_RATIO_64;   // vanilla: seedLo <- GOLDEN, seedHi <- SILVER
        s.hi = SC_SILVER_RATIO_64;
    } else {
        s.lo = lo;
        s.hi = hi;
    }
    return s;
}

// RandomSupport.upgradeSeedTo128bit(seed) -> mixed 128-bit state.
inline xoro_t xoro_from_seed(long seed) {
    long i = seed ^ SC_SILVER_RATIO_64;
    long j = i + SC_GOLDEN_RATIO_64;
    return xoro_init(mc_mixStafford13(i), mc_mixStafford13(j));
}

// XoroshiroPositionalRandomFactory.at(x,y,z):
//   long i = Mth.getSeed(x,y,z); state = (i ^ seedLo, seedHi)  (zero-guarded)
inline xoro_t xoro_at(long seedLo, long seedHi, int x, int y, int z) {
    long i = mc_getSeed(x, y, z);
    return xoro_init(i ^ seedLo, seedHi);
}

// Xoroshiro128PlusPlus.nextLong()
inline long xoro_next_long(__private xoro_t* s) {
    long i = s->lo;
    long j = s->hi;
    long k = mc_rotl(i + j, 17) + i;
    j ^= i;
    s->lo = mc_rotl(i, 49) ^ j ^ (j << 21);
    s->hi = mc_rotl(j, 28);
    return k;
}

// XoroshiroRandomSource.nextInt() = (int) nextLong()
inline int xoro_next_int_raw(__private xoro_t* s) {
    return (int) xoro_next_long(s);
}

// XoroshiroRandomSource.nextInt(bound) — Lemire's bounded-int (bound > 0).
//   long i = Integer.toUnsignedLong(nextInt());
//   long j = i * bound; long k = j & 0xFFFFFFFFL;
//   if (k < bound) { int l = Integer.remainderUnsigned(~bound+1, bound);
//                    while (k < l) { i = toUnsignedLong(nextInt()); j = i*bound; k = j & 0xFFFFFFFFL; } }
//   return (int)(j >> 32);
inline int xoro_next_int(__private xoro_t* s, int bound) {
    long i = ((long) xoro_next_int_raw(s)) & 0xFFFFFFFFL;
    long j = i * (long) bound;
    long k = j & 0xFFFFFFFFL;
    if (k < (long) bound) {
        uint l = ((uint)(~bound + 1)) % ((uint) bound);   // Integer.remainderUnsigned(-bound, bound)
        while (k < (long) l) {
            i = ((long) xoro_next_int_raw(s)) & 0xFFFFFFFFL;
            j = i * (long) bound;
            k = j & 0xFFFFFFFFL;
        }
    }
    return (int)(j >> 32);
}

// --- BlockPos bit-packing (PACKED_X/Z_LENGTH=26, PACKED_Y_LENGTH=12;
//      X_OFFSET=38, Z_OFFSET=12, Y_OFFSET=0) -------------------------------
#define SC_PACK_X_MASK 0x3FFFFFFL
#define SC_PACK_Y_MASK 0xFFFL
#define SC_PACK_Z_MASK 0x3FFFFFFL
inline long mc_bp_asLong(int x, int y, int z) {
    long i = 0L;
    i |= (((long) x) & SC_PACK_X_MASK) << 38;
    i |= (((long) y) & SC_PACK_Y_MASK);
    i |= (((long) z) & SC_PACK_Z_MASK) << 12;
    return i;
}
inline int mc_bp_getX(long p) { return (int)((p << 0)  >> 38); }   // (p << 64-38-26) >> 64-26
inline int mc_bp_getY(long p) { return (int)((p << 52) >> 52); }   // (p << 64-12)    >> 64-12
inline int mc_bp_getZ(long p) { return (int)((p << 26) >> 38); }   // (p << 64-12-26) >> 64-26

// =====================================================================
// SELF-TEST kernels (validated against Java in RngSelfTest).
// =====================================================================

// (1) aquifer location-cache jitter — the EXACT vanilla pattern:
//     RandomSource r = factory.at(gx,gy,gz);
//     asLong(gx*16 + r.nextInt(10), gy*12 + r.nextInt(9), gz*16 + r.nextInt(10))
// Also emits the first raw nextLong of a fresh stream + the three bounded ints,
// so a divergence is pinpointed to nextLong vs nextInt vs the packing.
__kernel void rng_selftest_jitter(
        const long seedLo, const long seedHi,
        __global const int* gx, __global const int* gy, __global const int* gz,
        __global long* outRawLong,
        __global int*  outI0,
        __global int*  outI1,
        __global int*  outI2,
        __global long* outPacked,
        const int n) {
    int gid = get_global_id(0);
    if (gid >= n) return;
    int x = gx[gid], y = gy[gid], z = gz[gid];

    xoro_t sr = xoro_at(seedLo, seedHi, x, y, z);
    outRawLong[gid] = xoro_next_long(&sr);

    xoro_t s = xoro_at(seedLo, seedHi, x, y, z);
    int i0 = xoro_next_int(&s, 10);
    int i1 = xoro_next_int(&s, 9);
    int i2 = xoro_next_int(&s, 10);
    outI0[gid] = i0;
    outI1[gid] = i1;
    outI2[gid] = i2;
    outPacked[gid] = mc_bp_asLong(x * 16 + i0, y * 12 + i1, z * 16 + i2);
}

// (2) bounded-int stream — draws `count` consecutive nextInt(bound) from ONE
// stream seeded by at(x,y,z). Run with a large bound (e.g. 0x60000000) so the
// Lemire REJECTION loop fires on ~25% of draws, exercising that branch which the
// aquifer's small bounds (9,10) statistically never reach.
__kernel void rng_selftest_bound_stream(
        const long seedLo, const long seedHi,
        const int x, const int y, const int z,
        const int bound, const int count,
        __global int* out) {
    int gid = get_global_id(0);
    if (gid != 0) return;             // single deterministic stream
    xoro_t s = xoro_at(seedLo, seedHi, x, y, z);
    for (int t = 0; t < count; t++) {
        out[t] = xoro_next_int(&s, bound);
    }
}

// (3) BlockPos unpack test — reads packed longs from a __global long* INPUT buffer
// (exactly the aquifer locCache path: createInputBuffer(LongBuffer) upload) and unpacks
// getX/getY/getZ. Catches both a long-upload byte/endian bug and a getX/Y/Z bug.
__kernel void bp_unpack_test(
        __global const long* packed,
        __global int* outX, __global int* outY, __global int* outZ,
        const int n) {
    int gid = get_global_id(0);
    if (gid >= n) return;
    long p = packed[gid];
    outX[gid] = mc_bp_getX(p);
    outY[gid] = mc_bp_getY(p);
    outZ[gid] = mc_bp_getZ(p);
}

#endif // SC_RNG_CL
