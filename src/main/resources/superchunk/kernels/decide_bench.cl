// =====================================================================
// SuperChunk GPU backend — STAGE-2 batched DECIDE-kernel DUTY BENCH (synthetic).
//
// PURELY A DIAGNOSTIC (-Dsuperchunk.gpu.decideBench=N, default OFF): measures
// whether a compact-readback "decide" kernel fits the single-drainer duty
// budget (GO gate: fp64 <= 1.0 ms/chunk at K=32) BEFORE any server
// integration is built. Nothing here ever touches terrain: the host
// (DecideBench.java) uploads SYNTHETIC-but-plausible inputs once, times N
// dispatches + pinned-map readbacks of the 1-byte/block id buffer on ONE
// in-order queue, logs [decide-bench] lines, and discards everything.
//
// WHAT IT MODELS (per block of a 16x16x384 chunk, K chunks per dispatch,
// corner-buffer layout identical to df_batch_lattice_multichunk:
// corners[(c*M + g)*cornerN + (iy*dimX + ix)*dimZ + iz]):
//   1. IN-REGISTER 8-corner trilinear interpolation (mc_lerp3, X->Y->Z — the
//      finalDensity cell-cache order, see fullfield.cl header) of the
//      finalDensity core grid — no full-field intermediate is materialized;
//   2. a REPRESENTATIVE finalDensity tail: mul 0.64 -> squeeze-style
//      clamp + cubic -> min with a second lerped "noodle" grid -> a
//      rangeChoice-style branch on a third lerped value. The exact vanilla
//      math is IRRELEVANT for a duty bench — only the op mix is representative;
//   3. the REAL aquifer decision: aq_decide from aquifer.cl (the mode-A-proven
//      port, BUGS=0/29.85M), fed synthetic FluidStatus cell arrays + location
//      cache in the exact 315-cell (3 x 35 x 3) per-chunk geometry;
//   4. the ore-vein candidate path: positional Xoroshiro init + 3 nextFloat
//      draws (rng.cl, bit-exact port) + 2 extra vein-grid trilerps, gated
//      OreVeinifier-style (solid + Y in [-60,50] + |veininess| threshold).
// Output: ONE byte per block (id 0..9; bit7 = a near-tie flag standing in for
// the shouldScheduleFluidUpdate bit of the real design — it also keeps
// aq_decide's margin algebra live against dead-code elimination) into a
// K*98304 buffer, pinned-mapped back by the host (the ~96KB/chunk readback IS
// part of the measured duty).
//
// Host concatenation order (mirrors AquiferGpuVerify + buildBenchProgram):
//   fp64-enable pragma + rng.cl + noise.cl + aquifer.cl + this file.
// Built BOTH plain (fp64: real = double) and with -DUSE_FP32 (real = float).
// NOTE: aq_decide is hard-typed double (aquifer.cl), so the fp32 leg measures
// fp32 interp/tail + fp64 decision — cl_khr_fp64 is required either way and
// the host skips the whole bench on non-fp64 devices.
//
// Bench id alphabet (bit7 masked off):
//   0=air  1=stone  2=water  3=lava
//   4=copper-ore  5=raw-copper  6=granite-filler
//   7=iron-ore    8=raw-iron    9=tuff-filler
// =====================================================================

// XoroshiroRandomSource.nextFloat() = (float)(nextLong() >>> 40) * 5.9604645E-8F.
inline float db_next_float(__private xoro_t* s) {
    return (float)(((ulong) xoro_next_long(s)) >> 40) * 5.9604645e-8f;
}

// In-register 8-corner fetch + trilinear lerp (X->Y->Z, mc_lerp3 == Mth.lerp3):
// same corner flat index (Y-outer/X-mid/Z-inner) and n[X][Y][Z] naming as
// df_full_field_interp_lerp3 in fullfield.cl.
inline real db_lerp3_grid(__global const real* corners, int base,
                          int cx, int cy, int cz, int dimX, int dimZ,
                          real dx, real dy, real dz) {
    real n000 = corners[base + ((cy)     * dimX + (cx))     * dimZ + (cz)];
    real n010 = corners[base + ((cy + 1) * dimX + (cx))     * dimZ + (cz)];
    real n100 = corners[base + ((cy)     * dimX + (cx + 1)) * dimZ + (cz)];
    real n110 = corners[base + ((cy + 1) * dimX + (cx + 1)) * dimZ + (cz)];
    real n001 = corners[base + ((cy)     * dimX + (cx))     * dimZ + (cz + 1)];
    real n011 = corners[base + ((cy + 1) * dimX + (cx))     * dimZ + (cz + 1)];
    real n101 = corners[base + ((cy)     * dimX + (cx + 1)) * dimZ + (cz + 1)];
    real n111 = corners[base + ((cy + 1) * dimX + (cx + 1)) * dimZ + (cz + 1)];
    return mc_lerp3(dx, dy, dz, n000, n100, n010, n110, n001, n101, n011, n111);
}

__kernel void decide_bench(
        __global const real* corners,       // K*M grids, [(c*M+g)*cornerN + i] (df_batch_lattice_multichunk layout)
        const int M,                        // grids per chunk (grids 0..4 used: density, noodle, barrier/sel, vein, gap)
        const int cornerN,                  // dimX*dimY*dimZ per grid (1225)
        const int dimX, const int dimY, const int dimZ,    // 5, 49, 5
        const int sx, const int sy, const int sz,          // 4, 8, 4 (cell block sizes)
        const int fullX, const int fullY, const int fullZ, // 16, 384, 16
        const int fullN,                    // fullX*fullY*fullZ = 98304
        __global const int* origins,        // K*3 chunk block origins (x, y, z)
        __global const long* locCache,      // K*cellN packed BlockPos (aquifer location cache)
        __global const int* fluidLevel,     // K*cellN FluidStatus fluidLevel
        __global const int* fluidTypeId,    // K*cellN FluidStatus fluidTypeId
        const int cellN,                    // 315 aquifer cells per chunk (3 x 35 x 3)
        const int minGridY,                 // -7 for block Y in [-64, 320)
        const int gridSizeX, const int gridSizeZ,           // 3, 3
        const int seaLevel, const int lavaLevel, const int defaultFluidId,
        const long seedLo, const long seedHi,               // positional-RNG world seed halves
        const int total,                    // K*fullN — work-item guard
        __global uchar* outIds) {
    int gid = get_global_id(0);
    if (gid >= total) return;

    // ---- decode (chunk c, block bx/by/bz) — Y-outer/X-mid/Z-inner like fullfield.cl.
    int c = gid / fullN;
    int lin = gid - c * fullN;
    int plane = fullX * fullZ;
    int by = lin / plane;
    int rem = lin - by * plane;
    int bx = rem / fullZ;
    int bz = rem - bx * fullZ;

    int ox = origins[c * 3 + 0];
    int oy = origins[c * 3 + 1];
    int oz = origins[c * 3 + 2];
    int i = ox + bx;            // absolute block coords (aquifer + vein RNG inputs)
    int j = oy + by;
    int k = oz + bz;

    // ---- cell index + in-cell interpolation deltas.
    int cx = bx / sx; int lx = bx - cx * sx;
    int cy = by / sy; int ly = by - cy * sy;
    int cz = bz / sz; int lz = bz - cz * sz;
    real dx = (real) lx / (real) sx;
    real dy = (real) ly / (real) sy;
    real dz = (real) lz / (real) sz;

    int gbase = c * M * cornerN;

    // ---- (1) finalDensity core lerp + (2) representative tail.
    real d = db_lerp3_grid(corners, gbase, cx, cy, cz, dimX, dimZ, dx, dy, dz)
             * REAL_C(0.64);                                    // mul 0.64
    real sq = clamp(d, REAL_C(-1.0), REAL_C(1.0));              // squeeze-style clamp + cubic
    d = sq / REAL_C(2.0) - sq * sq * sq / REAL_C(24.0);
    real noodle = db_lerp3_grid(corners, gbase + cornerN,
                                cx, cy, cz, dimX, dimZ, dx, dy, dz);
    d = fmin(d, noodle);                                        // min with noodle grid
    real sel = db_lerp3_grid(corners, gbase + 2 * cornerN,      // rangeChoice-style branch driver;
                             cx, cy, cz, dimX, dimZ, dx, dy, dz); // doubles as the barrier value below
    if (sel >= REAL_C(-0.1) && sel < REAL_C(0.1)) {
        d = d * REAL_C(4.0);
    }

    int id;
    uchar flag = 0;
    if (d > REAL_C(0.0)) {
        // ---- (4) solid -> ore-vein candidate path (OreVeinifier-shaped gating).
        id = 1;   // stone
        if (j >= -60 && j <= 50) {
            real vein = db_lerp3_grid(corners, gbase + 3 * cornerN,
                                      cx, cy, cz, dimX, dimZ, dx, dy, dz);
            if (fabs(vein) >= REAL_C(0.3)) {
                xoro_t rs = xoro_at(seedLo, seedHi, i, j, k);   // per-block positional stream
                float r0 = db_next_float(&rs);                  // the 3 vanilla-pattern draws
                float r1 = db_next_float(&rs);
                float r2 = db_next_float(&rs);
                if (r0 <= 0.7f) {
                    real gap = db_lerp3_grid(corners, gbase + 4 * cornerN,
                                             cx, cy, cz, dimX, dimZ, dx, dy, dz);
                    if (gap < REAL_C(0.3)) {
                        int fam = (vein > REAL_C(0.0)) ? 0 : 3; // copper vs iron family
                        if (r1 < 0.02f)      id = 4 + fam + 1;  // raw block
                        else if (r2 < 0.5f)  id = 4 + fam;      // ore
                        else                 id = 4 + fam + 2;  // filler (granite/tuff)
                    }
                }
            }
        }
    } else {
        // ---- (3) REAL aq_decide (aquifer.cl) on this chunk's 315-cell slice.
        __global const long* lc = locCache    + c * cellN;
        __global const int*  fl = fluidLevel  + c * cellN;
        __global const int*  ft = fluidTypeId + c * cellN;
        int minGridX = (ox - 5) >> 4;
        int minGridZ = (oz - 5) >> 4;
        double m; int schedg; int dg1, flg1, ixg1; long pg1;
        int aq = aq_decide(i, j, k, (double) d, (double) sel,
                           lc, fl, ft,
                           minGridX, minGridY, minGridZ, gridSizeX, gridSizeZ,
                           seaLevel, lavaLevel, defaultFluidId,
                           aq_flowing_update_similarity(),
                           &m, &schedg, &dg1, &flg1, &ixg1, &pg1);
        // Map aquifer ids to the bench alphabet: NULL -> stone(1), AIR -> air(0).
        if (aq == AQ_NULL)      id = 1;
        else if (aq == AQ_AIR)  id = 0;
        else                    id = aq;                        // 2=water, 3=lava
        // bit7: near-tie flag (stand-in for the shouldScheduleFluidUpdate bit of
        // the real compact design) — also keeps the margin algebra live.
        if (m < 1e-7) flag = (uchar) 0x80;
    }
    outIds[gid] = (uchar)(id | flag);
}
